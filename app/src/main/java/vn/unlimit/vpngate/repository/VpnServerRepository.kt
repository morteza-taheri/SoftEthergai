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
    /**
     * v1.0.52 (§1.6.44) TASK A.5: the bounded, in-process memory of which
     * transport last worked. Injected rather than constructed inline for the
     * same reason as [boundFetcherFactory]: a test that needs the TUNNEL rung
     * to lead the ladder has to be able to seed the evidence that promotes it,
     * and a test that needs the one-shot retry has to be able to observe the
     * memory the run wrote. Production always gets a fresh instance.
     */
    private val pathMemory: PathMemory = PathMemory(),
    /**
     * BLOCK v1.0.65 §5 — the outer refresh deadline. Exposed as an injectable
     * parameter (defaulting to [MAIN_TIMEOUT_MS]) so behavioural tests can
     * exercise the deadline path with a short, deterministic value instead of
     * waiting the full 45 seconds. Production always uses [MAIN_TIMEOUT_MS].
     */
    private val mainTimeoutMs: Long = MAIN_TIMEOUT_MS,
) {
    /**
     * v1.0.45 (§1.6.38): single-flight guard. At most one refresh runs at a
     * time; a second caller waits for the first rather than starting a parallel
     * collection (which the old code allowed, doubling the request volume).
     */
    private val refreshMutex = Mutex()

    // v1.0.52 (§1.6.44): `pathMemory` is now a constructor parameter (see the
    // class header) so a test can seed the evidence that promotes the TUNNEL
    // rung; it used to be constructed inline here.

    /**
     * v1.0.52 (§1.6.44) TASK A.4: at most **one** one-shot TUNNEL retry per
     * "no usable tunnel" episode.
     *
     * Armed (true) while no retry is outstanding. [refreshLocked] re-arms it as
     * soon as a run *starts* with a usable tunnel, so the next time the tunnel
     * is lost and comes back the recovery is available again. Spending it is a
     * CAS, so two concurrent callers can never both retry, and because the
     * wrapper never retries twice in a row this cannot become a loop.
     */
    private val tunnelRetryAvailable = java.util.concurrent.atomic.AtomicBoolean(true)

    /**
     * v1.0.52 (§1.6.44) TASK A.4: set by [refreshLocked] when a run ended with
     * no records **and** no usable tunnel — i.e. the exact situation a later
     * tunnel can rescue. Read by [refresh]'s one-shot branch.
     */
    @Volatile
    private var lastRunFailedWithoutTunnel: Boolean = false

    companion object {
        private const val MAIN_TIMEOUT_MS = 45_000L
        private const val RAW_CAPTURE_LIMIT = 512 * 1024
        private const val TAG = "VpnServerRepository"

        /**
         * v1.0.52 (§1.6.44): how long a successful transport stays "proven".
         * 30 minutes is long enough to keep a blocked-physical/tunnel-up
         * session on the tunnel across many refreshes, and short enough that a
         * stale success cannot outlive the network it described.
         */
        internal const val PATH_MEMORY_SUCCESS_TTL_MS = 30 * 60_000L

        /**
         * v1.0.52 (§1.6.44): how long a failed transport is skipped. Two
         * minutes covers a refresh burst (pull-to-refresh, retry, auto-mode
         * connect) without pinning the ladder for the rest of the session.
         */
        internal const val PATH_MEMORY_FAILURE_COOLDOWN_MS = 2 * 60_000L

        /**
         * v1.0.52 (§1.6.44): the budget of the single one-shot TUNNEL retry.
         * Deliberately smaller than [MAIN_TIMEOUT_MS]: it is a recovery
         * attempt, not a second full refresh.
         */
        internal const val TUNNEL_RETRY_TIMEOUT_MS = 20_000L

        /** Source tags used in the R4b `[collector] path=...` log lines. */
        internal const val SOURCE_TAG_HTML = "html"
        internal const val SOURCE_TAG_API = "api"
        internal const val SOURCE_TAG_MIRROR = "mirror"

        /**
         * Production routing inputs.
         *
         * v1.0.52 (§1.6.44): routing is **fully automatic**. The user-facing
         * "try the tunnel first" opt-in (`preferVpn`) was removed in this
         * revision, together with its Settings switch, its `DataUtil`
         * accessors and `GlobalVpnTracker.prefersVpnForCollector()`. A stale
         * stored `vpn_always_prefer_vpn` value is therefore ignored safely:
         * nothing reads it any more, and nothing has to migrate it away.
         *
         * What is left are two *measurements*, not preferences:
         *   - [CollectorRoutingInput.tunnelUsable] is the live health signal
         *     ([GlobalVpnTracker.isTunnelUsableForRouting]): connected,
         *     OS-bound, and either passing traffic right now or still inside
         *     the post-connect grace period.
         *   - [CollectorRoutingInput.hasBoundNetwork] says a VPN `Network`
         *     exists to bind with.
         *
         * These two booleans are the *only* inputs to the decision; the order in
         * which the rungs are tried is decided by [routeLadder]. BLOCK v1.0.64
         * makes that order deterministic VPN-first: when both booleans are true
         * the TUNNEL rung leads and PHYSICAL is the fallback. No user toggle
         * exists — the collector decides from the network state alone.
         */
        internal fun productionRouting(): CollectorRoutingInput {
            val usable = GlobalVpnTracker.isTunnelUsableForRouting()
            return CollectorRoutingInput(
                tunnelUsable = usable,
                hasBoundNetwork = GlobalVpnTracker.currentVpnNetwork() != null,
            )
        }

        /**
         * v1.0.52 (§1.6.44) — bounded, in-process memory of which transport last
         * worked, and which one just failed.
         *
         * This is what replaces the removed manual toggle: instead of asking
         * the user to declare "my physical path is blocked", the collector
         * *measures* it. A PHYSICAL attempt that produced nothing starts a
         * short cooldown; while that cooldown is active and a usable tunnel
         * exists, the ladder puts TUNNEL first. A successful path is
         * remembered for [successTtlMs] and wins ties.
         *
         * **Bounded by construction.** The key space is the three-value
         * [CollectorPath] enum, so at most three success timestamps and three
         * failure timestamps are ever held, regardless of how long the process
         * runs. No eviction policy is needed because no unbounded key can be
         * created.
         *
         * **No personal data.** Entries are `(enum, epochMillis)` pairs. No
         * credentials, no hostnames, no IPs, no SSIDs, no user identifiers —
         * nothing is written to disk and a process restart clears everything.
         *
         * The clock is injectable so the cooldown and TTL can be tested
         * without sleeping.
         */
        class PathMemory(
            private val clock: () -> Long = { System.currentTimeMillis() },
            private val successTtlMs: Long = PATH_MEMORY_SUCCESS_TTL_MS,
            private val failureCooldownMs: Long = PATH_MEMORY_FAILURE_COOLDOWN_MS,
        ) {
            private val lock = Any()
            private val lastSuccessAt = HashMap<CollectorPath, Long>()
            private val lastFailureAt = HashMap<CollectorPath, Long>()

            /** Records that [path] delivered a dataset; clears its cooldown. */
            fun recordSuccess(path: CollectorPath) {
                synchronized(lock) {
                    lastSuccessAt[path] = clock()
                    lastFailureAt.remove(path)
                }
            }

            /** Records that [path] produced nothing; starts its cooldown. */
            fun recordFailure(path: CollectorPath) {
                synchronized(lock) { lastFailureAt[path] = clock() }
            }

            /** True while [path] is inside its post-failure cooldown. */
            fun isCoolingDown(path: CollectorPath): Boolean {
                synchronized(lock) {
                    val failedAt = lastFailureAt[path] ?: return false
                    return clock() - failedAt < failureCooldownMs
                }
            }

            /** The most recent still-fresh success, or null when none is known. */
            fun preferred(): CollectorPath? {
                synchronized(lock) {
                    val now = clock()
                    return lastSuccessAt.entries
                        .filter { now - it.value < successTtlMs }
                        .maxByOrNull { it.value }
                        ?.key
                }
            }

            /** Test/teardown seam: forget everything. */
            fun clear() {
                synchronized(lock) {
                    lastSuccessAt.clear()
                    lastFailureAt.clear()
                }
            }
        }

        /**
         * v1.0.52 (§1.6.44) / BLOCK v1.0.64 — the automatic route ladder for one
         * refresh.
         *
         * Replaces the v1.0.45 ladder-with-user-reorder. Every rung is an
         * explicit, bound transport:
         *
         *  - `PHYSICAL` is the base rung and is *bound* to a NOT_VPN +
         *    INTERNET network, so it really leaves outside the tunnel.
         *  - `TUNNEL` is offered only when the tunnel is usable **and** a VPN
         *    network handle exists.
         *  - `EMERGENCY` (bundled `servers.csv`) is always the last rung and is
         *    **never** promoted.
         *
         * BLOCK v1.0.64 — deterministic VPN-first: when a usable, bound VPN
         * Network exists the TUNNEL rung is the **primary** and is genuinely
         * attempted (its outcome is recorded by [fetchOnChosenPath] into
         * [PathMemory]); PHYSICAL is the immediate fallback. When the TUNNEL rung
         * is not available the ladder is `PHYSICAL -> EMERGENCY`. The
         * evidence-based promotion below is retained for API stability but is a
         * no-op once TUNNEL is already leading.
         */
        internal fun routeLadder(
            input: CollectorRoutingInput,
            memory: PathMemory? = null,
        ): List<CollectorPath> {
            val tunnelRungAvailable = input.tunnelUsable && input.hasBoundNetwork
            // v1.0.70 (REQ-ROUTE-001) — VPN-only while active.
            //
            // When a usable, bound VPN Network exists the ONLY network rung is
            // TUNNEL. PHYSICAL is deliberately absent: a VPN-bound failure must
            // be reported, not silently retried over Wi-Fi/cellular. When no
            // usable+bound VPN Network exists, PHYSICAL (the Android default
            // network) is the only network rung. EMERGENCY stays last in both
            // cases; it is a separate dataset source, not a transport fallback.
            val ladder = if (tunnelRungAvailable) {
                mutableListOf(CollectorPath.TUNNEL)
            } else {
                mutableListOf(CollectorPath.PHYSICAL)
            }
            ladder += CollectorPath.EMERGENCY
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
     * R4b: the transport is chosen once per run (see [routeLadder]) and
     * applied per source, so each source's `[collector] path=...` line names
     * the transport it actually used. The
     * three sources are built inside the run rather than once at
     * construction precisely so the chosen fetcher can differ between runs.
     */
    suspend fun refresh(): CollectResult? = withContext(Dispatchers.IO) {
        // v1.0.45 (§1.6.38) FIX-5: single-flight + a total refresh deadline.
        // `completed` distinguishes a genuine null result from a timeout, since
        // withTimeoutOrNull returns null for both.
        refreshMutex.withLock {
            var completed = false
            val result = withTimeoutOrNull(mainTimeoutMs) {
                refreshLocked().also { completed = true }
            }
            if (!completed) {
                fetchLog("[ladder] deadline exceeded (${mainTimeoutMs}ms); preserving DB")
                return@withLock result
            }

            // v1.0.52 (§1.6.44) TASK A.4 — exactly one bounded retry.
            //
            // The trigger is the case the ladder cannot cover by itself: the
            // run had no usable tunnel at all (so TUNNEL was never offered) and
            // failed, and by now a tunnel has become usable. The retry is
            // spent once per episode (CAS), runs inside the same
            // `refreshMutex` (so it is single-flight against every other
            // caller) and is bounded by [TUNNEL_RETRY_TIMEOUT_MS]. It cannot
            // loop: `tunnelRetryAvailable` is only re-armed by a run that
            // *starts* with a usable tunnel, never by a failure.
            if (result == null && lastRunFailedWithoutTunnel &&
                tunnelRetryAvailable.compareAndSet(true, false)
            ) {
                val usableNow = runCatching {
                    GlobalVpnTracker.isTunnelUsableForRouting() &&
                        GlobalVpnTracker.currentVpnNetwork() != null
                }.getOrDefault(false)
                if (usableNow) {
                    fetchLog("[ladder] one-shot retry: a tunnel became usable; retrying through TUNNEL")
                    val retried = withTimeoutOrNull(TUNNEL_RETRY_TIMEOUT_MS) {
                        refreshLocked()
                    }
                    if (retried == null) {
                        fetchLog(
                            "[ladder] one-shot retry produced nothing " +
                                "(${TUNNEL_RETRY_TIMEOUT_MS}ms); preserving DB",
                        )
                    }
                    return@withLock retried
                }
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
        // that comes up between two refreshes is honoured by the next one.
        //
        // BLOCK v1.0.64: the ladder is TUNNEL (when usable and bound) ->
        // PHYSICAL -> EMERGENCY. With a usable, bound VPN Network the TUNNEL
        // rung is the primary and PHYSICAL the fallback; with no usable+bound
        // tunnel the ladder is PHYSICAL -> EMERGENCY. EMERGENCY is always last.
        val input = routing()
        // Re-arm the one-shot retry whenever a run starts with a usable tunnel:
        // the recovery it guards is "the tunnel came back", so its availability
        // is tied to the tunnel being present, never to a previous failure.
        if (input.tunnelUsable && input.hasBoundNetwork) {
            tunnelRetryAvailable.set(true)
        }
        lastRunFailedWithoutTunnel = false
        val ladder = routeLadder(input, pathMemory)
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

            // REQ-ROUTE-001 (v1.0.70): every source uses the one rung chosen
            // for this refresh. There is no same-refresh downgrade to PHYSICAL
            // and no cross-rung retry — a dead VPN path is reported, not
            // bypassed.
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
            // v1.0.49 (§1.6.42): attempt the EMERGENCY rung before falling
            // back to the last-known-good snapshot. The operator's requirement
            // is PHYSICAL -> TUNNEL -> EMERGENCY: if the network rungs cannot
            // deliver a dataset, the bundled/seed servers.csv must be tried.
            //
            // Before this revision `hasEmergencySources()` was a stub returning
            // `false`, so this branch only ever logged the skip and the rung
            // was never attempted. The snapshot (returned just below) is
            // non-null whenever one exists, so ServerListUpdater's
            // emergencyFetch — the only other place that reaches
            // emergencyDataset() — was almost never reached either: the
            // snapshot intercepted the fallthrough.
            //
            // The rung calls the existing v1.0.21 fetch path via
            // `ServerListUpdates.emergencyRecords()` (which reuses
            // EmergencySourceFetcher + EmergencyCsvParser); no fetch logic is
            // duplicated, and the records fold into the SAME `collected`
            // pipeline as PHYSICAL/TUNNEL, so DatasetIntegrity still gates the
            // eventual write.
            if (hasEmergencySources()) {
                val emergency = ServerListUpdates.emergencyRecords()
                if (!emergency.isNullOrEmpty()) {
                    fetchLog("[ladder] path=emergency source=${emergency.size}")
                    records.addAll(emergency)
                } else {
                    fetchLog("[ladder] skip=EMERGENCY (fetch produced no usable records)")
                }
            } else {
                fetchLog("[ladder] skip=EMERGENCY (none configured)")
            }
        }

        if (records.isEmpty()) {
            // v1.0.52 (§1.6.44) TASK A.4: remember *why* this run failed. Only
            // a failure with no usable tunnel can be rescued by a tunnel that
            // appears later, so only that case arms the one-shot retry.
            lastRunFailedWithoutTunnel = !input.tunnelUsable
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
     * v1.0.49 (§1.6.42): whether any emergency source is configured.
     *
     * Previously a stub returning `false`, which made the ladder log
     * `skip=EMERGENCY (none configured)` on every refresh even when the
     * v1.0.46 startup seed had written an enabled default row into
     * `emergency_source_config`. Now it consults that DAO directly. The
     * surrounding [refreshLocked] is a suspend function, so the DAO's
     * `listEnabled()` is called directly rather than through `runBlocking`.
     */
    private suspend fun hasEmergencySources(): Boolean =
        runCatching {
            vn.unlimit.vpngate.App.instance
                ?.emergencySourceConfigDao
                ?.listEnabled()
                ?.isNotEmpty() == true
        }.getOrDefault(false)

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
     * list.
     *
     * **v1.0.70 (REQ-ROUTE-001): exactly one rung per source.** There is no
     * fallback to the opposite network path. When [path] is TUNNEL a failure is
     * returned and logged; it is never retried over PHYSICAL. When [path] is
     * PHYSICAL the Android default network is used.
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
        // REQ-ROUTE-001 (v1.0.70): a source uses exactly the rung chosen for
        // this refresh. When the VPN is active the rung is TUNNEL and a failure
        // is reported — never silently retried over the physical network. When
        // the VPN is inactive the rung is PHYSICAL and the default network is
        // used. EMERGENCY is a separate dataset source, not a fallback of this
        // request.
        if (path == CollectorPath.TUNNEL && boundFetcher == null) {
            fetchLog("[fetch] path=tunnel unavailable (no bound VPN network); no physical fallback")
            return null
        }

        val result = if (path == CollectorPath.TUNNEL) {
            fetchLog("[fetch] path=tunnel source=$sourceTag")
            pathLog("[collector] path=tunnel source=$sourceTag")
            fetch(boundFetcher!!)
        } else {
            fetchLog("[fetch] path=physical source=$sourceTag")
            pathLog("[collector] path=physical source=$sourceTag")
            fetch(physicalFetcher)
        }
        if (result != null && succeeded(result)) {
            // v1.0.52 (§1.6.44): remember the transport that actually worked.
            pathMemory.recordSuccess(path)
            return result
        }
        // BLOCK v1.0.75 FIX (device evidence 18:27: "Binding socket to network
        // 152 failed: EPERM (Operation not permitted)" for api AND html after a
        // burst of VPN reconnects): the Network object captured at run start can
        // go stale within the same run, and every tunnel-bound request then dies
        // with EPERM. One REBIND RETRY heals it: rebuild the fetcher from the
        // tracker's CURRENT VPN network and retry the same TUNNEL rung once.
        // This is not a downgrade — the retry never touches the physical path
        // (REQ-ROUTE-001 is preserved).
        if (path == CollectorPath.TUNNEL &&
            GlobalVpnTracker.currentVpnNetwork() != null &&
            GlobalVpnTracker.isTunnelUsableForRouting()
        ) {
            val rebuilt = runCatching { boundFetcherFactory() }.getOrNull()
            if (rebuilt != null && rebuilt !== boundFetcher) {
                fetchLog("[fetch] path=tunnel rebind retry source=$sourceTag (stale VPN network suspected)")
                val retry = fetch(rebuilt)
                if (retry != null && succeeded(retry)) {
                    pathMemory.recordSuccess(path)
                    return retry
                }
            }
        }
        // The chosen transport produced nothing for this source. Record the
        // failure and stop: no cross-rung retry, by policy.
        pathMemory.recordFailure(path)
        if (path == CollectorPath.TUNNEL) {
            fetchLog("[fetch] path=tunnel failed (no result); no physical fallback (VPN active)")
        } else {
            fetchLog("[fetch] path=physical failed (no result)")
        }
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
