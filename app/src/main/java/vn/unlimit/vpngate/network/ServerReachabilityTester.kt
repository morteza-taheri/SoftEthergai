package vn.unlimit.vpngate.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import vn.unlimit.vpngate.automode.AutoModeCandidate
import vn.unlimit.vpngate.automode.AutoModeProtocol
import vn.unlimit.vpngate.automode.ReachabilityGate
import vn.unlimit.vpngate.automode.ReachabilityTarget
import vn.unlimit.vpngate.automode.endpointPort
import vn.unlimit.vpngate.models.VPNGateConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

data class ReachabilityResult(
    val ip: String,
    val port: Int,
    val isReachable: Boolean,
    val rttMs: Long = -1L,
)

/**
 * Stage 2F-F.14 (D10 + Y5 + D11): the identity a reachability result is stored
 * under. These two implementations ARE the two namespaces — a key cannot be
 * built for one namespace and filed under the other, because the type carries the
 * namespace with the identity.
 *
 * The app answers two different questions and used to file both answers under the
 * bare IP, so whichever workflow finished last silently overwrote the other:
 *
 * - **SERVER_LIST** ([ServerListReachabilityKey]) is deliberately protocol-less
 *   (Y5). It answers "can this server endpoint be quickly reached?", which is a
 *   question about the server, not about a VPN transport. The Server List must not
 *   be made to choose a protocol in order to store an answer.
 * - **AUTO_CONNECT** ([AutoConnectReachabilityKey]) answers "can this server be
 *   reached *for this protocol*?" (D9). Identity is IP **plus** the protocol
 *   enum, so `X + SOFTETHER_TCP` and `X + OPENVPN_TCP` are two distinct facts.
 *   The protocol is the enum, never a display string and never a priority
 *   position: reordering the user's Protocol Priority must not change what a
 *   stored result means.
 *
 * No port or transport dimension is added. A server publishes one endpoint per
 * protocol, so IP + protocol already identifies the endpoint, and broadening the
 * key further would only create more identities than there are distinct facts.
 *
 * Runtime-only: these are in-memory probe results and are deliberately never
 * persisted.
 */
sealed interface ReachabilityKey {
    val ip: String

    /** SERVER_LIST namespace: protocol-less quick-test identity (Y5/D11). */
    data class ServerList(override val ip: String) : ReachabilityKey

    /** AUTO_CONNECT namespace: IP + protocol identity (D9). */
    data class AutoConnect(
        override val ip: String,
        val protocol: AutoModeProtocol,
    ) : ReachabilityKey
}

object ServerReachabilityTester {
    const val DEFAULT_TIMEOUT_MS = 1500
    const val MAX_CONCURRENCY = 60

    /**
     * Stage 2F-F.16 (Y3): the two namespaces are stored in **separate maps typed
     * by their own key type**, not one map with a union value.
     *
     * That is stronger than the Stage 2F-F.14 single map: a `ServerList` key
     * cannot be passed to the Auto Connect map (the compiler rejects it) and a
     * tri-state Auto Connect result cannot be handed to the Server List accessors
     * as a binary `ReachabilityResult`. Cross-namespace contamination and
     * "which semantics am I holding" are now unrepresentable rather than merely
     * discouraged.
     *
     * Both remain process-local runtime state. Nothing here is persisted.
     */
    private val _serverListResults =
        ConcurrentHashMap<ReachabilityKey.ServerList, ReachabilityResult>()
    private val _autoConnectResults =
        ConcurrentHashMap<ReachabilityKey.AutoConnect, AutoConnectReachabilityResult>()

    /**
     * Every stored identity across both namespaces, for diagnostics and tests.
     *
     * This is a key-only view on purpose: with two differently-typed result maps
     * there is no single honest result type to expose, and a merged
     * `Map<ReachabilityKey, Any>` would reintroduce exactly the untyped access
     * Stage 2F-F.14 closed.
     */
    val resultKeys: Set<ReachabilityKey>
        get() = _serverListResults.keys + _autoConnectResults.keys

    /** Typed view of the SERVER_LIST identities, for diagnostics and tests. */
    val serverListResultKeys: Set<ReachabilityKey.ServerList>
        get() = _serverListResults.keys

    /** Typed view of the AUTO_CONNECT identities, for diagnostics and tests. */
    val autoConnectResultKeys: Set<ReachabilityKey.AutoConnect>
        get() = _autoConnectResults.keys

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    private val _testedCount = MutableStateFlow(0)
    val testedCount: StateFlow<Int> = _testedCount.asStateFlow()

    private val _totalCount = MutableStateFlow(0)
    val totalCount: StateFlow<Int> = _totalCount.asStateFlow()

    private val _resultsVersion = MutableStateFlow(0L)
    val resultsVersion: StateFlow<Long> = _resultsVersion.asStateFlow()

    // ---- SERVER_LIST namespace (protocol-less, binary, Y5) ----

    /** The Server List quick-test result for [ip], or null. Never an Auto Connect result. */
    fun serverListResult(ip: String?): ReachabilityResult? {
        if (ip.isNullOrBlank()) return null
        return _serverListResults[ReachabilityKey.ServerList(ip)]
    }

    fun isServerListReachable(ip: String?): Boolean = serverListResult(ip)?.isReachable == true

    fun hasServerListResult(ip: String?): Boolean = serverListResult(ip) != null

    /**
     * The SERVER_LIST results as a plain IP-keyed map, for the server list UI.
     *
     * The list is protocol-less (Y5), so its own view of the store is keyed by IP
     * too — but this map is built from the SERVER_LIST map only. An Auto Connect
     * verdict for the same IP therefore cannot surface as a quick-test result on a
     * server card (D11), which is what reading one shared loose map used to allow.
     *
     * Stage 2F-F.16 leaves this exactly as it was: the Server List stays binary
     * and protocol-less, and does not adopt the Auto Connect tri-state.
     *
     * Stage 2F-F.28S: this is a **copy**, not a view. It is the immutable
     * snapshot an ordering or filtering pass should be given, and because
     * nothing else holds a reference to it, later writes to the live map
     * cannot change a sort or filter that is already using it.
     */
    fun serverListResultsMap(): Map<String, ReachabilityResult> =
        _serverListResults.entries.associate { (key, value) -> key.ip to value }

    /**
     * Drop the Server List quick-test results only.
     *
     * Auto Connect evidence is left alone: it answers a different question about
     * a different identity, and a user refreshing the server list must not throw
     * away what Auto Connect has already learned (D10/D11).
     */
    fun clearServerList() {
        if (_serverListResults.isNotEmpty()) {
            _serverListResults.clear()
            _resultsVersion.value++
        }
    }

    // ---- AUTO_CONNECT namespace (IP + protocol, tri-state, D9/Y3) ----

    /**
     * The Auto Connect verdict for this exact server/protocol pair.
     *
     * There is intentionally no IP-only overload: an IP-only Auto Connect read is
     * the contamination bug itself, and the signature makes it unrepresentable
     * (D9/TEST 12).
     */
    fun autoConnectResult(ip: String?, protocol: AutoModeProtocol): AutoConnectReachabilityResult? {
        if (ip.isNullOrBlank()) return null
        return _autoConnectResults[ReachabilityKey.AutoConnect(ip, protocol)]
    }

    fun autoConnectState(
        ip: String?,
        protocol: AutoModeProtocol,
    ): AutoConnectReachabilityState? = autoConnectResult(ip, protocol)?.state

    /** True only for a definitive REACHABLE. UNKNOWN is not reachable, but is not a failure either. */
    fun isAutoConnectReachable(ip: String?, protocol: AutoModeProtocol): Boolean =
        autoConnectResult(ip, protocol)?.isReachable == true

    fun hasAutoConnectResult(ip: String?, protocol: AutoModeProtocol): Boolean =
        autoConnectResult(ip, protocol) != null

    /** Drop the Auto Connect results only, leaving the Server List quick test intact. */
    fun clearAutoConnect() {
        if (_autoConnectResults.isNotEmpty()) {
            _autoConnectResults.clear()
            _resultsVersion.value++
        }
    }

    /**
     * Clear everything, both namespaces.     *
     * This is the pre-existing API and it stays a global reset on purpose — tests
     * and a genuine "forget every reachability answer" action both want it. The
     * scoped [clearServerList] / [clearAutoConnect] are there for the cases where
     * one workflow must not disturb the other (TEST 10).
     */
    fun clear() {
        _serverListResults.clear()
        _autoConnectResults.clear()
        _testedCount.value = 0
        _totalCount.value = 0
        _resultsVersion.value++
    }

    suspend fun probe(host: String, port: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): ReachabilityResult =
        withContext(Dispatchers.IO) {
            if (host.isBlank() || port <= 0 || port > 65535) {
                return@withContext ReachabilityResult(host, port, false)
            }
            val start = System.currentTimeMillis()
            try {
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.soTimeout = timeoutMs
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    val rtt = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                    ReachabilityResult(host, port, isReachable = true, rttMs = rtt)
                }
            } catch (_: Throwable) {
                ReachabilityResult(host, port, isReachable = false)
            }
        }

    suspend fun testConnections(
        connections: List<VPNGateConnection>,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        concurrency: Int = MAX_CONCURRENCY,
    ): Map<ReachabilityKey.ServerList, ReachabilityResult> {
        return try {
            coroutineScope {
                _isTesting.value = true
                _totalCount.value = connections.size
                _testedCount.value = 0

                val semaphore = Semaphore(concurrency)
                val tasks = connections.map { conn ->
                    async(Dispatchers.IO) {
                        val ip = conn.ip ?: ""
                        val port = resolvePort(conn)
                        if (ip.isBlank() || port <= 0) {
                            _testedCount.value += 1
                            return@async null
                        }
                        val res = semaphore.withPermit {
                            probe(ip, port, timeoutMs)
                        }
// Stage 2F-F.14 (Y5): the Server List quick test is
                        // protocol-less and files under the SERVER_LIST
                        // namespace. It must never land on an Auto Connect key.
                        _serverListResults[ReachabilityKey.ServerList(ip)] = res
                        _testedCount.value += 1
                        _resultsVersion.value++
                        res
                    }
                }

                tasks.awaitAll()
                _serverListResults
            }
        } finally {
            // Also reached when the caller's scope is cancelled (e.g. the
            // user leaves the server list), so the progress UI never sticks.
            _isTesting.value = false
            _resultsVersion.value++
        }
    }

    /**
     * Quick-test ordering for the server list: reachable servers first
     * (lowest RTT first), then the servers the last run did not touch, and
     * the blocked ones last. The underlying database order is preserved
     * inside every group.
     *
     * Stage 2F-F.28S. [snapshot] is a required argument, and that is the fix.
     *
     * The comparator used to read `_serverListResults` on every single
     * comparison, so an element's sort key was a function of shared mutable
     * state rather than of the element. The quick test writes that map from up
     * to `MAX_CONCURRENCY` coroutines on `Dispatchers.IO` while the Home
     * screen sorts on the main thread, so a server could move from
     * untested to reachable *between two comparisons inside one TimSort run*.
     * The resulting ordering was not transitive over time and the production
     * app died with `IllegalArgumentException: Comparison method violates its
     * general contract!`.
     *
     * Both keys now come from [snapshot], so the comparator is a pure function
     * of the connection plus that snapshot. The live map may keep changing
     * during the sort without affecting it.
     *
     * The ordering itself is unchanged: rank first (0 reachable, 1 untested,
     * 2 blocked), then RTT, then the input order preserved within each group.
     */
fun orderByReachability(
        connections: List<VPNGateConnection>,
        snapshot: Map<String, ReachabilityResult>,
        descending: Boolean = false,
    ): List<VPNGateConnection> {
        val ordered = connections.sortedWith(
            compareBy<VPNGateConnection> { reachabilityRank(snapshot, it.ip) }
                .thenBy { snapshotResult(snapshot, it.ip)?.rttMs ?: Long.MAX_VALUE },
        )
        return if (descending) ordered.reversed() else ordered
    }

    /** Quick-test filter: only the servers the last run proved reachable. */
    fun onlyReachable(
        connections: List<VPNGateConnection>,
        snapshot: Map<String, ReachabilityResult>,
    ): List<VPNGateConnection> =
        connections.filter { snapshotResult(snapshot, it.ip)?.isReachable == true }

    /** Number of servers of [connections] with a stored result. */
    fun testedCount(connections: List<VPNGateConnection>): Int =
        connections.count { hasServerListResult(it.ip) }

    /** Number of servers of [connections] the last run could reach. */
    fun reachableCount(connections: List<VPNGateConnection>): Int =
        connections.count { isServerListReachable(it.ip) }

    /**
     * The answer [snapshot] holds for [ip].
     *
     * This mirrors [serverListResult]'s blank-address rule, so a null, empty
     * or blank IP is "no result" instead of a lookup of `""`. Stage 2F-F.28S:
     * every key used while ordering or filtering comes through here, and this
     * is the only place the ordering code touches reachability data — which is
     * why it reads the passed snapshot rather than the live store.
     */
    private fun snapshotResult(
        snapshot: Map<String, ReachabilityResult>,
        ip: String?,
    ): ReachabilityResult? = if (ip.isNullOrBlank()) null else snapshot[ip]

    /** 0 = reachable, 1 = not tested yet, 2 = blocked. */
    private fun reachabilityRank(
        snapshot: Map<String, ReachabilityResult>,
        ip: String?,
    ): Int {
        val result = snapshotResult(snapshot, ip) ?: return 1
        return if (result.isReachable) 0 else 2
    }

    /**
     * Stage 2F-F.13 (Y4): probe every protocol/endpoint pair the Auto Connect
     * controller selected, and return a [ReachabilityGate].
     *
     * The signature is the point. It used to take `(candidates, protocol)` and
     * decide things for itself: which port a protocol had, whether that port
     * existed, and therefore whether a server deserved to stay in the Auto
     * Connect queue. Those are capability claims, and the capability knowledge
     * lives in `AutoModeController.usableProtocols` (user Protocol Priority,
     * data-model support, per-protocol port validity, what the app can drive
     * unattended). A probe that filters candidates cannot be trusted as evidence
     * — it would be reporting a capability verdict as a network result.
     *
     * So the tester only measures: probe the endpoint it is handed on that
     * protocol's own transport, record the tri-state verdict, and apply the D1
     * gate over the aggregate. It never consults `AndroidConnectionAdapter`,
     * never re-derives or borrows a port, and never decides whether a protocol is
     * supported, automatable, or worth attempting.
     *
     * The caller hands over **one target per usable protocol**. Phase 5 handed
     * over only the first one, which made "every usable protocol is unreachable"
     * unrepresentable: a server whose first-priority endpoint refused was removed
     * on that evidence alone, while a perfectly good second protocol went
     * untested. Protocol Priority decides *attempt* order; it never narrows what
     * the gate is allowed to look at.
     */
    suspend fun filterReachableCandidates(
        targets: List<ReachabilityTarget>,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        concurrency: Int = MAX_CONCURRENCY,
        probe: AutoConnectReachabilityProbe = SocketAutoConnectReachabilityProbe,
    ): ReachabilityGate = coroutineScope {
        if (targets.isEmpty()) {
            // Nothing was measured. That is NOT an all-unreachable verdict, it is
            // the absence of one, so the caller must keep its queue.
            return@coroutineScope ReachabilityGate(kept = emptyList(), allUnreachable = false)
        }

        val semaphore = Semaphore(concurrency)
        val verdicts = targets.map { target ->
            async(Dispatchers.IO) {
                val ip = target.candidate.ip
                if (ip.isNullOrBlank()) return@async null

                // Stage 2F-F.16 (Y2): there is nothing to send to, so there is no
                // conclusion — recorded as UNKNOWN rather than left absent or
                // reported as UNREACHABLE. Both of those would claim something
                // about the server we cannot support: an absent result reads as
                // "fine" to every `?:` downstream, and UNREACHABLE would drop a
                // server whose only defect is that we could not test it.
                val result = if (target.port <= 0) {
                    ProbeReport(WireOutcome.SILENT).toResult()
                } else {
                    // Stage 2F-F.16 (D7): the probe chooses the transport from the
                    // protocol, so a UDP endpoint is never tested with a TCP
                    // socket. Every applicable verdict is recorded, UNKNOWN
                    // included, so "we do not know" is visible in the store
                    // instead of being indistinguishable from "unreachable".
                    semaphore.withPermit { probe.probe(target, timeoutMs) }.toResult()
                }

                // Stage 2F-F.14 (D9): filed under IP + the protocol this target was
                // selected for, so probing X for OpenVPN TCP can never overwrite
                // what we learned about X over SoftEther TCP — nor anything the
                // Server List stored for the same IP.
                _autoConnectResults[ReachabilityKey.AutoConnect(ip, target.protocol)] = result

                target.candidate to result
            }
        }.awaitAll().filterNotNull()

        // Stage 2F-F.17 (D1 = FILTER_ONLY): the gate.
        //
        // Verdicts are aggregated **per candidate**, never per target. One refused
        // endpoint says nothing about the server: OpenVPN TCP refusing while
        // SoftEther UDP answers is a healthy server on a healthy network, and
        // removing it would be exactly the cross-protocol mistake D9 exists to
        // prevent.
        //
        // A candidate is removed only when it was actually measured — it has at
        // least one verdict — and **every** one of them is
        // [AutoConnectReachabilityState.UNREACHABLE]. A single REACHABLE or a
        // single UNKNOWN keeps it. This is precisely what the binary verdict
        // could not express: "not answered" was one value that had to mean either
        // "keep" or "drop", and both answers were wrong somewhere — keep it and a
        // dead server never leaves the queue, drop it and every server behind a
        // firewall vanishes.
        //
        // A candidate with **no** verdict is never in `doomed`. That is what keeps
        // "no usable protocol" from collapsing into "every usable protocol is
        // unreachable": the first is a capability fact this layer is not allowed
        // to know about, the second is a network measurement it is.
        val verdictsByCandidate = verdicts.groupBy({ it.first }, { it.second })
        val doomed = verdictsByCandidate
            .filterValues { results: List<AutoConnectReachabilityResult> ->
                results.all { it.state == AutoConnectReachabilityState.UNREACHABLE }
            }
            .keys

        // A subsequence of `targets`, in the caller's order. Reachability is a
        // gate and never a ranker: no RTT ordering, no "reachable first"
        // ordering, no protocol-priority ordering.
        val kept = targets.map { it.candidate }.distinct().filterNot { it in doomed }

        // `allUnreachable` is true only when something was genuinely measured and
        // every measured candidate came back definitively down. Anything else — no
        // targets, a probe that threw, a verdict of REACHABLE or UNKNOWN, or a
        // candidate with no usable protocol — leaves it false, and the caller
        // keeps its full list rather than emptying it on no evidence.
        val measured = verdicts.map { it.first }.distinct()
        val allUnreachable = measured.isNotEmpty() && measured.all { it in doomed }

        ReachabilityGate(kept = kept, allUnreachable = allUnreachable)
    }

    private fun resolvePort(conn: VPNGateConnection): Int {
        return when {
            conn.seTcpPort > 0 -> conn.seTcpPort
            conn.tcpPort > 0 -> conn.tcpPort
            conn.seUdpPort > 0 -> conn.seUdpPort
            conn.udpPort > 0 -> conn.udpPort
            else -> 443
        }
    }

    /**
     * Stage 2F-F.2 (Q-4), relocated by Stage 2F-F.13 (Y4): the port that belongs
     * to [protocol] — and only to [protocol].
     *
     * The mapping now lives in the automode layer, next to the capability rules
     * that use it, because choosing the endpoint is part of answering "is this
     * protocol usable for this server". This function remains as a thin delegate
     * so the existing assertions keep testing the same single source of truth.
     *
     * `internal` rather than `private` so the resolution can be asserted
     * directly instead of inferred from socket behaviour.
     */
    internal fun resolveCandidatePort(c: AutoModeCandidate, protocol: AutoModeProtocol): Int =
        protocol.endpointPort(c)
}
