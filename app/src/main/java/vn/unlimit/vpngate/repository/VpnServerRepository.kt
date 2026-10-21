package vn.unlimit.vpngate.repository

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import vn.unlimit.vpngate.collector.CollectorDebugDump
import vn.unlimit.vpngate.data.model.CollectorLog
import vn.unlimit.vpngate.data.model.VpnRecords
import vn.unlimit.vpngate.data.model.VpnServerRecord
import vn.unlimit.vpngate.data.model.VpnUtil
import vn.unlimit.vpngate.data.persistence.CollectorSnapshotStore
import vn.unlimit.vpngate.data.remote.CollectorPath
import vn.unlimit.vpngate.data.remote.CollectorRouting
import vn.unlimit.vpngate.data.remote.CollectorRoutingInput
import vn.unlimit.vpngate.data.remote.HttpFetcher
import vn.unlimit.vpngate.data.remote.OkHttpFetcher
import vn.unlimit.vpngate.data.remote.VpnGateApiSource
import vn.unlimit.vpngate.data.remote.VpnGateHtmlSource
import vn.unlimit.vpngate.data.remote.VpnGateMirrorSource
import vn.unlimit.vpngate.data.remote.boundToNetwork
import vn.unlimit.vpngate.data.remote.boundToPhysicalNetwork
import vn.unlimit.vpngate.merger.VpnServerMerger
import vn.unlimit.vpngate.models.VPNGateConnection
import vn.unlimit.vpngate.models.VPNGateConnectionList
import vn.unlimit.vpngate.models.VPNGateItem
import vn.unlimit.vpngate.parser.VpnGateApiParser
import vn.unlimit.vpngate.parser.VpnGateHtmlParser
import vn.unlimit.vpngate.ranking.ServerQualityCalculator
import vn.unlimit.vpngate.state.GlobalVpnTracker
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
    /**
     * R4b: the three inputs to the open-vs-VPN decision, re-read once per
     * [refresh]. The default reads the app's live tracker, so production picks
     * the transport from real state; tests inject a constant.
     */
    private val routing: () -> CollectorRoutingInput = { productionRouting() },
    /**
     * R4b: the fetcher used on the VPN rung, or null when there is nothing to
     * bind to. Built once per [refresh] and shared by every source on that
     * run.
     *
     * Injected rather than constructed inline so tests can supply a fake: a
     * real [OkHttpFetcher] here would make live requests to vpngate.net, which
     * both makes the suite slow and makes it pass or fail depending on whether
     * the build machine happens to have a network. Production uses
     * [productionTunnelFetcher] (v1.0.45).
     */
    private val boundFetcherFactory: () -> HttpFetcher? = { productionTunnelFetcher() },
    /**
     * R4b: where the `[collector] path=...` line goes. Default is INFO on a
     * device. Wrapped in runCatching because this class's unit tests run on a
     * plain JVM where `android.util.Log` is an unmocked stub — the same reason
     * [ServerListUpdater.isKeyConflict] matches by class name instead of type.
     */
    private val pathLog: (String) -> Unit = { runCatching { Log.i(TAG, it) } },
    /**
     * v1.0.27: where the `[fetch] path=...` diagnostic lines go (tag
     * `VpnServerFetch`). Wrapped in runCatching for the same JVM-stub reason as
     * [pathLog]; tests inject a recorder to assert on routing decisions.
     */
    private val fetchLog: (String) -> Unit = { runCatching { Log.d("VpnServerFetch", it) } },
) {
    /**
     * v1.0.45 (§1.6.38): single-flight guard. At most one refresh runs at a
     * time; a second caller waits for the first rather than starting a parallel
     * collection (which the old code allowed, doubling the request volume).
     */
    private val refreshMutex = Mutex()

    companion object {
        private const val MAIN_TIMEOUT_MS = 45_000L
        private const val MIRROR_TIMEOUT_MS = 30_000L
        private const val RAW_CAPTURE_LIMIT = 512 * 1024
        private const val TAG = "VpnServerRepository"

        /** Source tags used in the R4b `[collector] path=...` log lines. */
        internal const val SOURCE_TAG_HTML = "html"
        internal const val SOURCE_TAG_API = "api"
        internal const val SOURCE_TAG_MIRROR = "mirror"

        /**
         * Production routing inputs.
         *
         * v1.0.41 (§1.6.34) routing model — opt-in **and** health gated:
         *   - [preferVpn] is the user's "always prefer VPN" opt-in
         *     ([GlobalVpnTracker.prefersVpnForCollector], default false). It
         *     carries the user's *intent* only; it no longer decides the rung
         *     on its own.
         *   - [tunnelUsable] is the live health signal
         *     ([GlobalVpnTracker.isTunnelUsableForRouting]): connected,
         *     OS-bound, and either passing traffic right now or still inside
         *     the post-connect grace period. This function only *supplies* it;
         *     the gate itself lives in [resolvePrimary].
         *   - [hasBoundNetwork] says a VPN `Network` exists to bind with. It
         *     never forces the VPN rung on its own.
         *
         * The VPN rung is chosen only when `preferVpn && tunnelUsable`. With
         * the opt-in OFF (the default) the primary rung is always OPEN,
         * whatever the tunnel is doing. With the opt-in ON but the tunnel
         * dead, OPEN is chosen **directly**: the pre-v1.0.41 code took the VPN
         * rung anyway and relied on [fetchOnChosenPath] to recover, paying a
         * full timeout for an attempt that could not succeed.
         *
         * The rung is still never forced by tunnel state alone — the earlier
         * `preferVpn ||= tunnelUp` form did exactly that and wasted tunnel
         * bandwidth on directly-reachable lists. [resolvePrimary] +
         * [fetchOnChosenPath] remain the only consumers.
         */
        internal fun productionRouting(): CollectorRoutingInput {
            // v1.0.41 (§1.6.34): feed the real usability signal. The gate
            // decision is made in resolvePrimary; this function only supplies
            // the inputs.
            val usable = GlobalVpnTracker.isTunnelUsableForRouting()
            return CollectorRoutingInput(
                // FIX-3a (v1.0.27), kept: honour the user opt-in as the user's
                // intent; do NOT OR the live tunnel state in here. OPEN-first /
                // VPN-fallback is decided by resolvePrimary + fetchOnChosenPath.
                preferVpn = GlobalVpnTracker.prefersVpnForCollector(),
                tunnelUsable = usable,
                hasBoundNetwork = GlobalVpnTracker.currentVpnNetwork() != null,
            )
        }

        /**
         * v1.0.45 (§1.6.38): the automatic route ladder for one refresh.
         *
         * Replaces the v1.0.27/v1.0.41 single-primary model. Every rung is now
         * an explicit, bound transport:
         *
         *  - `PHYSICAL` is always the base rung and is *bound* to a NOT_VPN +
         *    INTERNET network, so it really leaves outside the tunnel. The old
         *    `OPEN` was unbound and therefore silently followed the OS default,
         *    which IS the app's own tunnel whenever that tunnel is up.
         *  - `TUNNEL` is inserted only when the tunnel is usable **and** a VPN
         *    network handle exists ([CollectorRoutingInput.hasBoundNetwork]) —
         *    the same three-way AND the v1.0.41 gate used, so reachability is
         *    unchanged; only the transport is now honest.
         *  - `EMERGENCY` (bundled `servers.csv`) is always the last rung; the
         *    caller logs a skip when no emergency source is configured.
         *
         * The user opt-in (`preferVpn`) no longer *gates* the tunnel, it only
         * **reorders** the ladder: with the opt-in ON and a usable tunnel the
         * TUNNEL rung moves to the front. That is the whole point of v1.0.45 —
         * a blocked physical path recovers through the app's own tunnel
         * automatically, with no manual toggle and no DB wipe.
         */
        internal fun routeLadder(input: CollectorRoutingInput): List<CollectorPath> {
            val tunnelRungAvailable = input.tunnelUsable && input.hasBoundNetwork
            val ladder = mutableListOf(CollectorPath.PHYSICAL)
            if (tunnelRungAvailable) ladder += CollectorPath.TUNNEL
            ladder += CollectorPath.EMERGENCY
            if (input.preferVpn && tunnelRungAvailable) {
                ladder.remove(CollectorPath.TUNNEL)
                ladder.add(0, CollectorPath.TUNNEL)
            }
            return ladder
        }

        /** The first rung of [routeLadder]; kept as a named seam for tests. */
        internal fun resolvePrimary(input: CollectorRoutingInput): CollectorPath =
            routeLadder(input).first()

        /**
         * v1.0.45 (§1.6.38): the PHYSICAL rung fetcher — bound to an explicit
         * NOT_VPN + INTERNET network (never the unbound default). Null when no
         * physical network exists; the caller then degrades to the injected
         * `fetcher` so a JVM test or airplane-mode run still has a transport.
         *
         * This is one of only two places an [android.net.Network] is touched,
         * and only to ask it for its `SocketFactory` — never
         * `bindProcessToNetwork()` (`SERVER_SOURCE_ARCHITECTURE.md` §7 Q2).
         */
        internal fun productionBoundFetcher(): HttpFetcher? {
            val network = GlobalVpnTracker.currentPhysicalNetwork() ?: return null
            return runCatching {
                OkHttpFetcher(client = boundToPhysicalNetwork(network))
            }.getOrNull()
        }

        /**
         * v1.0.45 (§1.6.38): the TUNNEL rung fetcher — bound to the app's own
         * VPN network. Same narrow per-request binding as before (never
         * `bindProcessToNetwork`). Null when there is no VPN network to bind to.
         */
        internal fun productionTunnelFetcher(): HttpFetcher? {
            val network = GlobalVpnTracker.currentVpnNetwork() ?: return null
            return runCatching {
                OkHttpFetcher(client = boundToNetwork(network))
            }.getOrNull()
        }
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
     *
     * R4b: the transport is chosen once per run (see
     * [CollectorRouting.choose]) and applied per source, so each source's
     * `[collector] path=...` line names the transport it actually used. The
     * three sources are built inside the run rather than once at
     * construction precisely so the chosen fetcher can differ between runs.
     */
    suspend fun refresh(): CollectResult? = withContext(Dispatchers.IO) {
        // v1.0.45 (§1.6.38) FIX-5: single-flight + a total refresh deadline.
        // `completed` distinguishes a genuine null result from a timeout, since
        // withTimeoutOrNull returns null for both.
        refreshMutex.withLock {
            var completed = false
            val result = withTimeoutOrNull(MAIN_TIMEOUT_MS) {
                refreshLocked().also { completed = true }
            }
            if (!completed) {
                fetchLog("[ladder] deadline exceeded (${MAIN_TIMEOUT_MS}ms); preserving DB")
            }
            result
        }
    }

    /**
     * v1.0.45 (§1.6.38): the body of [refresh], run under the single-flight
     * guard and the total deadline. Kept separate so the wrapper stays readable.
     */
    private suspend fun refreshLocked(): CollectResult? {
        // Set once the parallel fetches have resolved; it describes what this
        // run's sources could provide, and is reported with the dataset.
        var collectedCapability = SourceCapability.UNKNOWN

        // v1.0.45: re-read per refresh, never cached across runs, so a tunnel
        // that comes up between two refreshes is honoured by the next one. The
        // ladder is PHYSICAL -> TUNNEL (if usable) -> EMERGENCY; the toggle only
        // reorders it (TUNNEL first when opted in).
        val input = routing()
        val ladder = routeLadder(input)
        fetchLog("[ladder] order=${ladder.joinToString(",") { it.name }}")
        val primary = ladder.first()
        val physicalFetcher: HttpFetcher =
            runCatching { productionBoundFetcher() }.getOrNull() ?: fetcher
        val boundFetcher: HttpFetcher? = bindHttpFetcher()

        val records = coroutineScope {
            val cachedMirrors = runCatching {
                vn.unlimit.vpngate.App.instance?.dataUtil?.getCachedMirrors().orEmpty()
            }.getOrDefault(emptyList())

            val mirrorsDeferred = async {
                fetchOnChosenPath(SOURCE_TAG_MIRROR, primary, boundFetcher, { it.isNotEmpty() }, physicalFetcher) { f ->
                    VpnGateMirrorSource(f).discoverMirrors(cachedMirrors)
                }
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
                // A null result IS the failure signal for this source.
                fetchOnChosenPath(SOURCE_TAG_API, primary, boundFetcher, { true }, physicalFetcher) { f ->
                    VpnGateApiSource(f).fetch()
                }
            }
            val htmlDeferred = async {
                fetchOnChosenPath(SOURCE_TAG_HTML, primary, boundFetcher, { true }, physicalFetcher) { f ->
                    VpnGateHtmlSource(f).fetch(mirrors)
                }
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
            // v1.0.45: EMERGENCY is the last rung; skipped when no emergency
            // sources are configured (none are wired in this build).
            if (!hasEmergencySources()) {
                fetchLog("[ladder] skip=EMERGENCY (none configured)")
            }
            fetchLog(
                "[ladder] ${primary.name} failed (no result) " +
                    "network=${networkLabel(primary)} " +
                    "selfExcluded=${selfExclusionLabel()}",
            )
            fetchLog("[ladder] all rungs failed; preserving DB")
            CollectorLog.d("No records collected; trying last-known-good snapshot")
            return loadSnapshot()
        }

        val merged = VpnServerMerger.mergeRecords(records)
        val valid = VpnServerMerger.validateServers(merged)

        if (valid.isEmpty()) {
            CollectorLog.d("No valid servers after merge; trying last-known-good snapshot")
            return loadSnapshot()
        }

        ServerQualityCalculator.scoreAll(valid)

        // §33: retain merged records for per-server provenance dump.
        lastRecords = valid

        val connectionList = VpnConnectionMapper.toConnectionList(valid)
        if (connectionList.size() == 0) {
            return loadSnapshot()
        }

        val now = System.currentTimeMillis()
        // Stage 2C-B: the snapshot is NOT written here. Collection does not
        // decide that a dataset is worth keeping — storage acceptance does,
        // after dataset integrity has been evaluated against the stored list.
        // Writing it here is what allowed a degraded dataset to overwrite the
        // last-known-good fallback before anything had judged it.
        CollectorLog.d("Collected ${connectionList.size()} servers (capability=$collectedCapability)")
        return CollectResult(
            connectionList,
            valid.size,
            fromCache = false,
            savedAt = now,
            capability = collectedCapability,
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // R4b transport routing, v1.0.45 ladder.
    //
    // `SERVER_SOURCE_ARCHITECTURE.md` §7 Q2 binds a request by handing
    // `network.socketFactory` to that request's own client — never
    // `bindProcessToNetwork()`, which would capture every other socket in the
    // process. Since v1.0.45 both network rungs are explicit and bound:
    // PHYSICAL is a NOT_VPN network, TUNNEL is the app's own VPN network, and
    // EMERGENCY (bundled servers.csv) carries no network at all. The old
    // unbound `OPEN` is gone precisely because it was never "outside the
    // tunnel" when the tunnel was up.
    // ─────────────────────────────────────────────────────────────────────

    /**
     * The fetcher bound to the captured VPN network, or null when there is
     * nothing to bind to.
     *
     * Built once per [refresh] and shared by every source on that run: the
     * clients differ only in their socket factory (see
     * [collectorClientBuilder][vn.unlimit.vpngate.data.remote.collectorClientBuilder]),
     * so one bound client per run is equivalent to one per request while
     * creating a single connection pool instead of three.
     */
    private fun bindHttpFetcher(): HttpFetcher? = boundFetcherFactory()

    /**
     * v1.0.45: whether any emergency source is configured. No emergency source
     * is wired in this build, so EMERGENCY is skipped (logged) and the DB is
     * preserved by the last-known-good snapshot.
     */
    private fun hasEmergencySources(): Boolean = false

    /** v1.0.45: a readable network handle for a rung, for failure diagnostics. */
    private fun networkLabel(path: CollectorPath): String = when (path) {
        CollectorPath.PHYSICAL ->
            runCatching { GlobalVpnTracker.currentPhysicalNetwork()?.toString() }.getOrNull() ?: "none"
        CollectorPath.TUNNEL ->
            runCatching { GlobalVpnTracker.currentVpnNetwork()?.toString() }.getOrNull() ?: "none"
        CollectorPath.EMERGENCY -> "none"
    }

    /**
     * v1.0.45: the self-exclusion diagnostic as a log-safe string.
     *
     * Wrapped because this class's unit tests run on a plain JVM where
     * [GlobalVpnTracker] cannot initialise; a diagnostic must never be the
     * reason a refresh throws.
     */
    private fun selfExclusionLabel(): String =
        runCatching { GlobalVpnTracker.isSelfExcludedFromOwnTunnel().name }.getOrNull() ?: "UNKNOWN"

    /**
     * Runs one source's fetch on the transport [path] chose for this run.
     *
     * The source tag is part of the log line because the transport is chosen
     * per run but the *outcome* is per source: one source can succeed on the
     * VPN rung while another produces nothing, and that must be visible.
     *
     * [succeeded] is supplied by the caller rather than assumed to be
     * `result != null`, because the three sources do not report failure the
     * same way: `VpnGateApiSource.fetch` and `VpnGateHtmlSource.fetch` return
     * null, while `VpnGateMirrorSource.discoverMirrors` returns an empty
     * list. Keying the fallback on null alone would silently never retry the
     * mirror discovery.
     *
     * @param boundFetcher the already-built VPN-bound fetcher, or null when
     *   the VPN rung was asked for but no network was available.
     */
    internal suspend fun <T> fetchOnChosenPath(
        sourceTag: String,
        path: CollectorPath,
        boundFetcher: HttpFetcher?,
        succeeded: (T) -> Boolean,
        physicalFetcher: HttpFetcher = fetcher,
        fetch: suspend (HttpFetcher) -> T?,
    ): T? {
        // A TUNNEL attempt needs a bound VPN network. If none is available the
        // rung is unusable, so degrade to PHYSICAL without claiming a fallback
        // we did not perform.
        if (path == CollectorPath.TUNNEL && boundFetcher == null) {
            fetchLog("[fetch] path=physical (tunnel rung unavailable)")
            pathLog("[collector] path=physical source=$sourceTag")
            return fetch(physicalFetcher)
        }

        // --- primary rung ---
        val primaryResult = if (path == CollectorPath.TUNNEL) {
            fetchLog("[fetch] path=tunnel source=$sourceTag")
            pathLog("[collector] path=tunnel source=$sourceTag")
            fetch(boundFetcher!!)
        } else {
            fetchLog("[fetch] path=physical source=$sourceTag")
            pathLog("[collector] path=physical source=$sourceTag")
            fetch(physicalFetcher)
        }
        if (primaryResult != null && succeeded(primaryResult)) return primaryResult

        // --- fallback to the opposite main rung, only when it is reachable ---
        val fallback =
            if (path == CollectorPath.PHYSICAL) CollectorPath.TUNNEL else CollectorPath.PHYSICAL
        val canFallback = when (fallback) {
            CollectorPath.TUNNEL -> boundFetcher != null
            CollectorPath.PHYSICAL -> true
            CollectorPath.EMERGENCY -> false
        }
        if (!canFallback) {
            // Diagnostic only — the `[collector]` stream stays clean so existing
            // per-source log assertions are unaffected.
            fetchLog("[fetch] path=physical failed (no result); no fallback (tunnel down)")
            return null
        }

        // Emit the rung names lowercase so the [fetch] diagnostics read
        // `path=physical` / `path=tunnel` / `fallback-to-*` consistently.
        fetchLog("[fetch] path=${path.name.lowercase()} failed (no result); falling back to ${fallback.name.lowercase()}")
        pathLog("[collector] path=fallback-to-${fallback.name.lowercase()} source=$sourceTag")
        val fallbackResult = if (fallback == CollectorPath.TUNNEL) {
            fetch(boundFetcher!!)
        } else {
            fetch(physicalFetcher)
        }
        if (fallbackResult != null && succeeded(fallbackResult)) {
            fetchLog("[fetch] path=${fallback.name.lowercase()} succeeded after ${path.name.lowercase()} failed")
            return fallbackResult
        }
        fetchLog("[fetch] path=${fallback.name.lowercase()} failed (no result)")
        return null
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
