package vn.unlimit.vpngate.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
import vn.unlimit.vpngate.data.remote.HttpFetcher
import vn.unlimit.vpngate.data.remote.OkHttpFetcher
import vn.unlimit.vpngate.data.remote.VpnGateApiSource
import vn.unlimit.vpngate.data.remote.VpnGateHtmlSource
import vn.unlimit.vpngate.data.remote.VpnGateMirrorSource
import vn.unlimit.vpngate.merger.VpnServerMerger
import vn.unlimit.vpngate.models.VPNGateConnection
import vn.unlimit.vpngate.models.VPNGateConnectionList
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
        private const val CACHE_FILE = "collector_vpn_servers.json"
        private const val MAIN_TIMEOUT_MS = 45_000L
        private const val MIRROR_TIMEOUT_MS = 30_000L
        private const val RAW_CAPTURE_LIMIT = 512 * 1024
    }

    data class CollectResult(
        val connectionList: VPNGateConnectionList,
        val serverCount: Int,
        val fromCache: Boolean,
        val savedAt: Long = 0L,
    )

    /** §33 debug panel payload for one server. */
    data class DebugPayload(
        val dump: String,
        val rawHtml: String?,
        val rawApi: String?,
    )

    private val gson = Gson()
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
     */
    suspend fun refresh(): CollectResult? = withContext(Dispatchers.IO) {
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
                    vn.unlimit.vpngate.App.instance?.dataUtil?.setCachedMirrors(mirrors)
                }
            }

            val mirrorApiUrls = mirrors.map { m ->
                val base = m.removeSuffix("/en").removeSuffix("/en/").trimEnd('/')
                "$base/api/iphone/"
            }
            val mirrorHtmlUrls = mirrors.map { m ->
                val base = m.removeSuffix("/en").removeSuffix("/en/").trimEnd('/')
                "$base/en/"
            }

            val apiDeferred = async {
                apiSource.fetch(mirrorApiUrls)
            }
            val htmlDeferred = async {
                htmlSource.fetch(mirrorHtmlUrls)
            }

            val apiText = apiDeferred.await()
            val html = htmlDeferred.await()

            CollectorLog.d(
                "Sources: html=${html != null} api=${apiText != null} mirrors=${mirrors.size}"
            )

            val collected = mutableListOf<VpnServerRecord>()

            apiText?.let {
                runCatching { collected.addAll(VpnGateApiParser.parseApi(it, "api")) }
                    .onFailure { e -> CollectorLog.d("API parse failed: ${e.message}") }
            }

            html?.let {
                runCatching { collected.addAll(VpnGateHtmlParser.parseHtml(it, "html")) }
                    .onFailure { e -> CollectorLog.d("HTML parse failed: ${e.message}") }
            }

            // §33: keep bounded raw captures for the debug panel.
            lastRawHtml = html?.take(RAW_CAPTURE_LIMIT)
            lastRawApi = apiText?.take(RAW_CAPTURE_LIMIT)

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
        saveSnapshot(connectionList)
        CollectResult(connectionList, valid.size, fromCache = false, savedAt = now)
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

    private fun cacheFile(): File? = cacheDir?.let { File(it, CACHE_FILE) }

    private fun saveSnapshot(list: VPNGateConnectionList) {
        val file = cacheFile() ?: return
        try {
            val servers = (0 until list.size()).map { list.get(it) }
            val payload = linkedMapOf(
                "savedAt" to System.currentTimeMillis(),
                "count" to servers.size,
                "servers" to servers,
            )
            file.writeText(gson.toJson(payload), Charsets.UTF_8)
            CollectorLog.d("Snapshot saved: ${servers.size} servers")
        } catch (e: Exception) {
            CollectorLog.d("Snapshot save failed: ${e.message}")
        }
    }

    private fun loadSnapshot(): CollectResult? {
        val file = cacheFile()
        if (file != null && file.isFile) {
            val result = try {
                val json = file.readText(Charsets.UTF_8)
                val savedAt = runCatching {
                    gson.fromJson<Map<String, Any?>>(
                        json,
                        object : TypeToken<Map<String, Any?>>() {}.type,
                    )["savedAt"] as? Number
                }.getOrNull()?.toLong() ?: 0L

                val servers: List<VPNGateConnection> = run {
                    val element = com.google.gson.JsonParser.parseString(json).asJsonObject
                    val array = element.getAsJsonArray("servers") ?: return@run emptyList()
                    gson.fromJson(
                        array,
                        object : TypeToken<List<VPNGateConnection>>() {}.type,
                    )
                }

                if (servers.isNotEmpty()) {
                    val list = VPNGateConnectionList()
                    servers.forEach { list.add(it) }
                    CollectorLog.d("Loaded last-known-good snapshot (savedAt=$savedAt): ${servers.size}")
                    CollectResult(list, servers.size, fromCache = true, savedAt = savedAt)
                } else null
            } catch (e: Exception) {
                CollectorLog.d("Snapshot load failed: ${e.message}")
                null
            }
            if (result != null) return result
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
