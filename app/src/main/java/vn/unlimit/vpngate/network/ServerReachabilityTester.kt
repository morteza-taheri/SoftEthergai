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

object ServerReachabilityTester {
    const val DEFAULT_TIMEOUT_MS = 1500
    const val MAX_CONCURRENCY = 60

    private val _results = ConcurrentHashMap<String, ReachabilityResult>()
    val resultsMap: Map<String, ReachabilityResult> get() = _results

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    private val _testedCount = MutableStateFlow(0)
    val testedCount: StateFlow<Int> = _testedCount.asStateFlow()

    private val _totalCount = MutableStateFlow(0)
    val totalCount: StateFlow<Int> = _totalCount.asStateFlow()

    private val _resultsVersion = MutableStateFlow(0L)
    val resultsVersion: StateFlow<Long> = _resultsVersion.asStateFlow()

    fun getResult(ip: String?): ReachabilityResult? {
        if (ip.isNullOrBlank()) return null
        return _results[ip]
    }

    fun isServerReachable(ip: String?): Boolean {
        if (ip.isNullOrBlank()) return false
        return _results[ip]?.isReachable == true
    }

    fun clear() {
        _results.clear()
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
    ): Map<String, ReachabilityResult> {
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
                        _results[ip] = res
                        _testedCount.value += 1
                        _resultsVersion.value++
                        res
                    }
                }

                tasks.awaitAll()
                _results
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
     */
    fun orderByReachability(
        connections: List<VPNGateConnection>,
        descending: Boolean = false,
    ): List<VPNGateConnection> {
        val ordered = connections.sortedWith(
            compareBy<VPNGateConnection> { reachabilityRank(it.ip) }
                .thenBy { getResult(it.ip)?.rttMs ?: Long.MAX_VALUE },
        )
        return if (descending) ordered.reversed() else ordered
    }

    /** Quick-test filter: only the servers the last run proved reachable. */
    fun onlyReachable(connections: List<VPNGateConnection>): List<VPNGateConnection> =
        connections.filter { isServerReachable(it.ip) }

    /** Number of servers of [connections] with a stored result. */
    fun testedCount(connections: List<VPNGateConnection>): Int =
        connections.count { hasResult(it.ip) }

    /** Number of servers of [connections] the last run could reach. */
    fun reachableCount(connections: List<VPNGateConnection>): Int =
        connections.count { isServerReachable(it.ip) }

    fun hasResult(ip: String?): Boolean = !ip.isNullOrBlank() && _results.containsKey(ip)

    /** 0 = reachable, 1 = not tested yet, 2 = blocked. */
    private fun reachabilityRank(ip: String?): Int {
        val result = getResult(ip) ?: return 1
        return if (result.isReachable) 0 else 2
    }

    suspend fun filterReachableCandidates(
        candidates: List<AutoModeCandidate>,
        protocol: AutoModeProtocol,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        concurrency: Int = MAX_CONCURRENCY,
    ): List<AutoModeCandidate> = coroutineScope {
        if (candidates.isEmpty()) return@coroutineScope emptyList()

        val semaphore = Semaphore(concurrency)
        val scored = candidates.map { candidate ->
            async(Dispatchers.IO) {
                val ip = candidate.ip ?: return@async null
                val port = resolveCandidatePort(candidate, protocol)
                if (port <= 0) return@async null

                val res = semaphore.withPermit {
                    probe(ip, port, timeoutMs)
                }
                _results[ip] = res
                if (res.isReachable) Pair(candidate, res.rttMs) else null
            }
        }.awaitAll().filterNotNull()

        if (scored.isNotEmpty()) {
            scored.sortedBy { it.second }.map { it.first }
        } else {
            candidates
        }
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

    private fun resolveCandidatePort(c: AutoModeCandidate, protocol: AutoModeProtocol): Int {
        return when (protocol) {
            AutoModeProtocol.SOFTETHER_TCP -> c.seTcpPort
            AutoModeProtocol.SOFTETHER_UDP -> if (c.seTcpPort > 0) c.seTcpPort else c.seUdpPort
            AutoModeProtocol.OPENVPN_TCP -> c.openVpnTcpPort
            AutoModeProtocol.OPENVPN_UDP -> if (c.openVpnTcpPort > 0) c.openVpnTcpPort else c.openVpnUdpPort
            else -> if (c.seTcpPort > 0) c.seTcpPort else 443
        }
    }
}
