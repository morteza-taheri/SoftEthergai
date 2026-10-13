package vn.unlimit.vpngate.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import vn.unlimit.vpngate.collector.CollectorDebugDump
import vn.unlimit.vpngate.data.model.CollectorLog
import vn.unlimit.vpngate.data.model.VpnRecords
import vn.unlimit.vpngate.data.model.VpnServerRecord
import vn.unlimit.vpngate.data.model.VpnUtil
import vn.unlimit.vpngate.data.persistence.CollectorSnapshotStore
import vn.unlimit.vpngate.data.remote.HttpFetcher
import vn.unlimit.vpngate.data.remote.OkHttpFetcher
import vn.unlimit.vpngate.data.remote.VpnGateApiSource
import vn.unlimit.vpngate.data.remote.VpnGateHtmlSource
import vn.unlimit.vpngate.data.remote.VpnGateMirrorSource
import vn.unlimit.vpngate.merger.VpnServerMerger
import vn.unlimit.vpngate.models.VPNGateConnection
import vn.unlimit.vpngate.models.VPNGateConnectionList
import vn.unlimit.vpngate.models.VPNGateItem
import vn.unlimit.vpngate.parser.VpnGateApiParser
import vn.unlimit.vpngate.parser.VpnGateHtmlParser
import vn.unlimit.vpngate.ranking.ServerQualityCalculator
import java.io.File

/**
 * In-app multi-source collector (Mode B): the app collects server
 * data itself from the VPN Gate main HTML + official API + official
 * mirrors, then merges/validates/scores with the oracle-ported
 * engine. Replaces the former GitHub-hosted JSON enrichment.
 *
 * All networking runs via coroutines on IO (never the main thread);
 * each request has its own timeout; mirrors are fetched sequentially
 * (the fetcher rate-limits); a timestamped JSON snapshot provides a
 * last-known-good fallback.
 */
class VpnServerRepository(
    private val fetcher: HttpFetcher = OkHttpFetcher(),
    private val cacheDir: File? = null,
) {
    companion object {
        private const val MAIN_TIMEOUT_MS = 45_000L
        private const val MIRROR_TIMEOUT_MS = 30_000L
        private const val RAW_CAPTURE_LIMIT = 512 * 1024
    }

    /**
     * @param capability what the sources of this run were actually able to
     *   provide. A run where only the API answered carries no SoftEther/SSTP/
     *   L2TP facts, which is a source limitation and not a dataset defect;
     *   the updater needs this to avoid rejecting such a run as degraded.
     */
    data class CollectResult(
        val connectionList: VPNGateConnectionList,
        val serverCount: Int,
        val fromCache: Boolean,
        val savedAt: Long,
        val capability: SourceCapability = SourceCapability.UNKNOWN,
    )

    /** §33 debug panel payload for one server. */
    data class DebugPayload(
        val dump: String,
        val rawHtml: String?,
        val rawApi: String?,
    )

    private val htmlSource = VpnGateHtmlSource(fetcher)
    private val apiSource = VpnGateApiSource(fetcher)
    private val mirrorSource = VpnGateMirrorSource(fetcher)

    // §33: in-memory provenance from the LAST network collection.
    // Not persisted: after restart the debug panel reports no data.
    @Volatile
    private var lastRecords: List<VpnServerRecord> = emptyList()

    @Volatile
    private var lastRawHtml: String? = null

    @Volatile
    private var lastRawApi: String? = null

    /**
     * Network-first collection: main HTML + API in parallel, mirrors
     * concurrently, then merge -> validate -> score -> map. On total
     * failure falls back to the last-known-good snapshot or bundled seed.
     *
     * Every body is parsed under its OWN source tag: the canonical feeds
     * as `"html"`/`"api"`, and a mirror as its own `mirror_<n>` tag, so a
     * mirror body can never be attributed to the canonical source.
     */
    suspend fun refresh(): CollectResult? = withContext(Dispatchers.IO) {
        // Set once the parallel fetches have resolved; it describes what this
        // run's sources could provide, and is reported with the dataset.
        var collectedCapability = SourceCapability.UNKNOWN

        val records = coroutineScope {
            val cachedMirrors = runCatching {
                vn.unlimit.vpngate.App.instance?.dataUtil?.getCachedMirrors().orEmpty()
            }.getOrDefault(emptyList())

            val mirrorsDeferred = async {
                mirrorSource.discoverMirrors(cachedMirrors)
            }
            val mirrors = mirrorsDeferred.await().orEmpty()

            if (mirrors.isNotEmpty()) {
                runCatching {
                    vn.unlimit.vpngate.App.instance?.dataUtil
                        ?.setCachedMirrors(mirrors.map { it.baseUrl })
                }
            }

            val apiDeferred = async {
                // Canonical CSV only: a mirror's CSV schema is undefined
                // for this project, so it is never fed to the API parser.
                apiSource.fetch()
            }
            val htmlDeferred = async {
                htmlSource.fetch(mirrors)
            }

            val apiFetched = apiDeferred.await()
            val htmlFetched = htmlDeferred.await()

            CollectorLog.d(
                "Sources: html=${htmlFetched?.identity?.sourceTag} " +
                    "api=${apiFetched?.identity?.sourceTag} mirrors=${mirrors.size}",
            )

            collectedCapability = SourceCapability(
                providesProtocolColumns = htmlFetched != null,
                providesOpenVpnProfiles = apiFetched != null,
            )

            val collected = mutableListOf<VpnServerRecord>()

            apiFetched?.let { fetched ->
                runCatching {
                    collected.addAll(
                        VpnGateApiParser.parseApi(fetched.body, fetched.identity.sourceTag),
                    )
                }.onFailure { e -> CollectorLog.d("API parse failed: ${e.message}") }
            }

            htmlFetched?.let { fetched ->
                runCatching {
                    collected.addAll(
                        VpnGateHtmlParser.parseHtml(fetched.body, fetched.identity.sourceTag),
                    )
                }.onFailure { e -> CollectorLog.d("HTML parse failed: ${e.message}") }
            }

            // §33: keep bounded raw captures for the debug panel.
            lastRawHtml = htmlFetched?.body?.take(RAW_CAPTURE_LIMIT)
            lastRawApi = apiFetched?.body?.take(RAW_CAPTURE_LIMIT)

            collected
        }

        if (records.isEmpty()) {
            CollectorLog.d("No records collected; trying last-known-good snapshot")
            return@withContext loadSnapshot()
        }

        val merged = VpnServerMerger.mergeRecords(records)
        val valid = VpnServerMerger.validateServers(merged)

        if (valid.isEmpty()) {
            CollectorLog.d("No valid servers after merge; trying last-known-good snapshot")
            return@withContext loadSnapshot()
        }

        ServerQualityCalculator.scoreAll(valid)

        // §33: retain merged records for per-server provenance dump.
        lastRecords = valid

        val connectionList = VpnConnectionMapper.toConnectionList(valid)
        if (connectionList.size() == 0) {
            return@withContext loadSnapshot()
        }

        val now = System.currentTimeMillis()
        // Stage 2C-B: the snapshot is NOT written here. Collection does not
        // decide that a dataset is worth keeping — storage acceptance does,
        // after dataset integrity has been evaluated against the stored list.
        // Writing it here is what allowed a degraded dataset to overwrite the
        // last-known-good fallback before anything had judged it.
        CollectorLog.d("Collected ${connectionList.size()} servers (capability=$collectedCapability)")
        CollectResult(
            connectionList,
            valid.size,
            fromCache = false,
            savedAt = now,
            capability = collectedCapability,
        )
    }

    /**
     * §33: locate the merged record behind a displayed server and
     * build its provenance dump plus raw-source captures. Returns
     * null when the last network collection is unavailable (e.g.
     * list restored from cache after restart).
     */
    fun debugPayload(ip: String, hostname: String): DebugPayload? {
        val record = findRecord(ip, hostname) ?: return null

        return DebugPayload(
            dump = CollectorDebugDump.format(record),
            rawHtml = lastRawHtml,
            rawApi = lastRawApi,
        )
    }

    private fun findRecord(ip: String, hostname: String): VpnServerRecord? {
        val host = VpnUtil.normalizeHost(hostname)

        return lastRecords.firstOrNull { record ->
            val identity = VpnRecords.identity(record)
            (ip.isNotEmpty() && VpnRecords.str(identity["ip"]) == ip) ||
                (host.isNotEmpty() &&
                    VpnUtil.normalizeHost(identity["hostname"]) == host)
        }
    }

    private fun cacheFile(): File? = CollectorSnapshotStore.file(cacheDir)

    /**
     * Stage 2C-B: promote an ACCEPTED dataset to last-known-good.
     *
     * This is deliberately not part of collection: only a dataset that has
     * already passed source validation, parsing, merging, normalization and
     * [DatasetIntegrity] evaluation may be written here, and only after the
     * database already holds it.
     *
     * The write is temp-file + atomic rename, so a failure (or a crash) can
     * never leave a half-written file behind and can never destroy the
     * previous, still-valid snapshot: the old file is only replaced once the
     * new bytes are safely on disk.
     *
     * @return true when the snapshot now holds [items]; false when the
     *   write failed and the previous snapshot was left in place.
     */
    fun saveLastKnownGood(items: List<VPNGateItem>): Boolean {
        val file = cacheFile() ?: return false
        if (items.isEmpty()) return false
        return try {
            val list = VPNGateConnectionList()
            items.forEach { list.add(VPNGateConnection().fromVPNGateItem(it)) }
            // get() is platform-typed; the store requires a non-null list so
            // that `count` and `servers` can never disagree.
            val servers = (0 until list.size()).mapNotNull { list.get(it) }

            when (val result = CollectorSnapshotStore.write(file, System.currentTimeMillis(), servers)) {
                is CollectorSnapshotStore.WriteResult.Written -> {
                    CollectorLog.d("Last-known-good snapshot saved: ${result.serverCount} servers")
                    true
                }
                is CollectorSnapshotStore.WriteResult.Failed -> {
                    CollectorLog.d("Snapshot save failed (${result.reason}): ${result.detail}")
                    false
                }
            }
        } catch (e: Exception) {
            CollectorLog.d("Snapshot save failed: ${e.message}")
            false
        }
    }

    /**
     * The snapshot file only, without the Room/seed fallbacks. Diagnostics and tests.
     *
     * Deliberately pure: it decodes but never rewrites, so a diagnostic run
     * cannot silently migrate a user's file. [loadSnapshot] is the only
     * production path that upgrades a legacy payload.
     */
    fun readLastKnownGoodSnapshot(): VPNGateConnectionList? {
        val file = cacheFile() ?: return null
        return when (val result = CollectorSnapshotStore.read(file)) {
            is CollectorSnapshotStore.ReadResult.Loaded -> {
                if (result.servers.isEmpty()) {
                    null
                } else {
                    VPNGateConnectionList().apply { result.servers.forEach { add(it) } }
                }
            }
            is CollectorSnapshotStore.ReadResult.Invalid -> {
                CollectorLog.d("Snapshot read rejected (${result.reason}): ${result.detail}")
                null
            }
            CollectorSnapshotStore.ReadResult.Absent -> null
        }
    }

    private fun loadSnapshot(): CollectResult? {
        val file = cacheFile()
        if (file != null && file.isFile) {
            when (val read = CollectorSnapshotStore.read(file)) {
                is CollectorSnapshotStore.ReadResult.Loaded -> {
                    val list = VPNGateConnectionList()
                    read.servers.forEach { list.add(it) }

                    // Stage 2F-E.2: a legacy payload is upgraded in place so the
                    // next start reads a canonical file. A failed rewrite is
                    // logged and the decoded data is still used — losing a
                    // readable snapshot because the upgrade could not be
                    // persisted would be strictly worse than reading it again.
                    if (read.format != CollectorSnapshotStore.Format.CANONICAL) {
                        when (val migrated = CollectorSnapshotStore.migrate(file, read)) {
                            is CollectorSnapshotStore.WriteResult.Written ->
                                CollectorLog.d("Migrated ${read.format} snapshot to canonical v${CollectorSnapshotStore.SCHEMA_VERSION}: ${migrated.serverCount} servers")
                            is CollectorSnapshotStore.WriteResult.Failed ->
                                CollectorLog.d("Snapshot migration write failed (${migrated.reason}): ${migrated.detail}; using decoded data")
                        }
                    }

                    CollectorLog.d("Loaded last-known-good snapshot (savedAt=${read.savedAt}, ${read.format}): ${read.servers.size}")
                    return CollectResult(list, read.servers.size, fromCache = true, savedAt = read.savedAt)
                }
                is CollectorSnapshotStore.ReadResult.Invalid ->
                    CollectorLog.d("Snapshot load rejected (${read.reason}): ${read.detail}")
                CollectorSnapshotStore.ReadResult.Absent -> Unit
            }
        }

        // Room database fallback
        val dbItems = runCatching { vn.unlimit.vpngate.App.instance?.vpnGateItemDao?.getAll() }.getOrNull()
        if (!dbItems.isNullOrEmpty()) {
            val list = VPNGateConnectionList()
            dbItems.forEach { list.add(VPNGateConnection().fromVPNGateItem(it)) }
            CollectorLog.d("Loaded ${dbItems.size} servers from internal database fallback")
            val dbSavedAt = runCatching { vn.unlimit.vpngate.App.instance?.dataUtil?.connectionCacheUpdatedAt }.getOrNull() ?: 0L
            return CollectResult(list, dbItems.size, fromCache = true, savedAt = dbSavedAt)
        }

        // Bundled seed asset fallback (for initial install / offline)
        val assetCsv = runCatching {
            vn.unlimit.vpngate.App.instance?.assets?.open("seed_vpn_servers.csv")?.bufferedReader()?.readText()
        }.getOrNull()
        if (!assetCsv.isNullOrBlank()) {
            val seedRecords = runCatching { vn.unlimit.vpngate.parser.VpnGateApiParser.parseApi(assetCsv, "asset_seed") }.getOrNull().orEmpty()
            if (seedRecords.isNotEmpty()) {
                val merged = VpnServerMerger.mergeRecords(seedRecords)
                val valid = VpnServerMerger.validateServers(merged)
                ServerQualityCalculator.scoreAll(valid)
                val connectionList = VpnConnectionMapper.toConnectionList(valid)
                if (connectionList.size() > 0) {
                    CollectorLog.d("Loaded ${connectionList.size()} servers from bundled seed asset")
                    return CollectResult(connectionList, connectionList.size(), fromCache = true, savedAt = System.currentTimeMillis())
                }
            }
        }

        return null
    }
}
