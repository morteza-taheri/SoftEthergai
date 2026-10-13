package vn.unlimit.vpngate.network

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vn.unlimit.vpngate.automode.AutoModeCandidate
import vn.unlimit.vpngate.automode.AutoModeProtocol
import vn.unlimit.vpngate.automode.ReachabilityTarget
import vn.unlimit.vpngate.data.model.VpnPortSource
import vn.unlimit.vpngate.data.model.VpnProtocolDefaults
import vn.unlimit.vpngate.models.VPNGateConnection
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * JVM tests for the quick reachability test of the server list: the TCP
 * probe itself, the per-IP result cache/progress and the in-memory
 * ordering/filtering helpers the list and the Auto Mode pre-filter use.
 *
 * Everything runs against loopback only: a bound [ServerSocket] is a
 * reachable endpoint, a port that was bound and released is a blocked one.
 */
class ServerReachabilityTesterTest {

    private lateinit var listener: ServerSocket
    private var openPort = 0
    private var closedPort = 0

    @Before
    fun setUp() {
        ServerReachabilityTester.clear()
        listener = ServerSocket(0)
        openPort = listener.localPort
        closedPort = freePort()
    }

    @After
    fun tearDown() {
        listener.close()
        ServerReachabilityTester.clear()
    }

    /** Binds an ephemeral port and releases it again so nothing listens there. */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun connection(
        ip: String? = "127.0.0.1",
        port: Int = 0,
        host: String? = null,
    ) = VPNGateConnection().apply {
        this.ip = ip
        this.hostName = host
        this.seTcpPort = port
    }

    private fun candidate(ip: String?, port: Int) = AutoModeCandidate(
        hostname = "srv-$port",
        ip = ip,
        speed = 10_000_000,
        ping = 30,
        sessions = 10,
        score = 100,
        sources = 1,
        seTcpPort = port,
        seUdpPort = 0,
        seUdpSupported = true,
        openVpnTcpPort = 0,
        openVpnUdpPort = 0,
        l2tpSupported = false,
        sstpSupported = false,
    )

    /**
     * Stage 2F-F.2 (Q-4): every protocol must resolve to ITS OWN port.
     *
     * Each protocol is given a distinct port on every transport, so a
     * resolution that borrows from another transport cannot accidentally
     * produce the expected number.
     */
    private fun fullyLoadedCandidate(
        seTcpPort: Int = 1001,
        seUdpPort: Int = 1002,
        openVpnTcpPort: Int = 1003,
        openVpnUdpPort: Int = 1004,
        sstpConnectPort: Int = 1005,
        l2tpSupported: Boolean = true,
        sstpSupported: Boolean = true,
    ) = AutoModeCandidate(
        hostname = "multi",
        ip = "127.0.0.1",
        speed = 10_000_000,
        ping = 30,
        sessions = 10,
        score = 100,
        sources = 1,
        seTcpPort = seTcpPort,
        seUdpPort = seUdpPort,
        seUdpSupported = true,
        openVpnTcpPort = openVpnTcpPort,
        openVpnUdpPort = openVpnUdpPort,
        l2tpSupported = l2tpSupported,
        sstpSupported = sstpSupported,
        sstpPort = if (sstpConnectPort > 0) sstpConnectPort else 0,
        sstpPortSource = VpnPortSource.EXPLICIT,
        sstpConnectPort = sstpConnectPort,
    )

    @Test
    fun eachProtocolResolvesToItsOwnPort() {
        val c = fullyLoadedCandidate()
        fun port(protocol: AutoModeProtocol) =
            ServerReachabilityTester.resolveCandidatePort(c, protocol)

        assertEquals(1001, port(AutoModeProtocol.SOFTETHER_TCP))
        assertEquals(1002, port(AutoModeProtocol.SOFTETHER_UDP))
        assertEquals(1003, port(AutoModeProtocol.OPENVPN_TCP))
        assertEquals(1004, port(AutoModeProtocol.OPENVPN_UDP))
        assertEquals(1005, port(AutoModeProtocol.MS_SSTP))
    }

    @Test
    fun softEtherUdpNeverUsesTheSoftEtherTcpPort() {
        // TCP populated, UDP absent: the old code fell back to seTcpPort.
        val c = fullyLoadedCandidate(seTcpPort = 443, seUdpPort = 0)
        assertEquals(
            0,
            ServerReachabilityTester.resolveCandidatePort(c, AutoModeProtocol.SOFTETHER_UDP),
        )
        assertEquals(
            443,
            ServerReachabilityTester.resolveCandidatePort(c, AutoModeProtocol.SOFTETHER_TCP),
        )
    }

    @Test
    fun openVpnUdpNeverUsesTheOpenVpnTcpPort() {
        val c = fullyLoadedCandidate(openVpnTcpPort = 1194, openVpnUdpPort = 0)
        assertEquals(
            0,
            ServerReachabilityTester.resolveCandidatePort(c, AutoModeProtocol.OPENVPN_UDP),
        )
        assertEquals(
            1194,
            ServerReachabilityTester.resolveCandidatePort(c, AutoModeProtocol.OPENVPN_TCP),
        )
    }

    @Test
    fun sstpNeverUsesSeTcpPortNorFabricatesFourFourThree() {
        // SSTP confirmed, port unknown -> sstpConnectPort is 0. The old code
        // returned seTcpPort, or 443 when there was none.
        val c = fullyLoadedCandidate(
            seTcpPort = 443,
            sstpConnectPort = 0,
        )
        assertEquals(
            "an UNKNOWN sstp port must not become seTcpPort or 443",
            0,
            ServerReachabilityTester.resolveCandidatePort(c, AutoModeProtocol.MS_SSTP),
        )
    }

    @Test
    fun l2tpNeverUsesSeTcpPortAndUsesTheProjectDefault() {
        val withTcp = fullyLoadedCandidate(seTcpPort = 5555, l2tpSupported = true)
        assertEquals(
            "L2TP must resolve to the IANA default, not to the SoftEther TCP port",
            VpnProtocolDefaults.L2TP_PORT,
            ServerReachabilityTester.resolveCandidatePort(withTcp, AutoModeProtocol.L2TP_IPSEC),
        )
        assertFalse(
            "L2TP must not be resolved through another transport's port",
            ServerReachabilityTester.resolveCandidatePort(withTcp, AutoModeProtocol.L2TP_IPSEC) == 5555,
        )

        // Even with no SoftEther TCP port at all, L2TP is still 1701 — never
        // the fabricated 443 the old fallback produced.
        val withoutTcp = fullyLoadedCandidate(seTcpPort = 0, l2tpSupported = true)
        assertEquals(
            VpnProtocolDefaults.L2TP_PORT,
            ServerReachabilityTester.resolveCandidatePort(withoutTcp, AutoModeProtocol.L2TP_IPSEC),
        )
        assertEquals(1701, VpnProtocolDefaults.L2TP_PORT)
    }

    @Test
    fun aMissingProtocolPortYieldsNoProbeablePort() {
        val bare = fullyLoadedCandidate(
            seTcpPort = 0,
            seUdpPort = 0,
            openVpnTcpPort = 0,
            openVpnUdpPort = 0,
            sstpConnectPort = 0,
        )
        fun port(protocol: AutoModeProtocol) =
            ServerReachabilityTester.resolveCandidatePort(bare, protocol)

        assertEquals(0, port(AutoModeProtocol.SOFTETHER_TCP))
        assertEquals(0, port(AutoModeProtocol.SOFTETHER_UDP))
        assertEquals(0, port(AutoModeProtocol.OPENVPN_TCP))
        assertEquals(0, port(AutoModeProtocol.OPENVPN_UDP))
        assertEquals(0, port(AutoModeProtocol.MS_SSTP))
    }

    @Test
    fun probeReportsListeningPortAsReachable() = runBlocking {
        val result = ServerReachabilityTester.probe("127.0.0.1", openPort, timeoutMs = 1_000)

        assertTrue("bound port must be reachable", result.isReachable)
        assertTrue("RTT must be measured", result.rttMs >= 1L)
        assertEquals(openPort, result.port)
    }

    @Test
    fun probeReportsReleasedPortAsUnreachable() = runBlocking {
        val result = ServerReachabilityTester.probe("127.0.0.1", closedPort, timeoutMs = 1_000)

        assertFalse("released port must not be reachable", result.isReachable)
    }

    @Test
    fun testConnectionsCachesResultAndResetsProgress() = runBlocking {
        val servers = listOf(connection(port = openPort))

        val results = ServerReachabilityTester.testConnections(servers, timeoutMs = 1_000)

        assertTrue(
            "the Server List quick test files under the protocol-less SERVER_LIST namespace",
            results[ReachabilityKey.ServerList("127.0.0.1")]?.isReachable == true,
        )
        assertFalse("progress flag must be cleared", ServerReachabilityTester.isTesting.value)
        assertEquals(1, ServerReachabilityTester.totalCount.value)
        assertEquals(1, ServerReachabilityTester.testedCount.value)
        assertEquals(1, ServerReachabilityTester.testedCount(servers))
        assertEquals(1, ServerReachabilityTester.reachableCount(servers))
        assertTrue(ServerReachabilityTester.hasServerListResult("127.0.0.1"))
        assertFalse(ServerReachabilityTester.hasServerListResult("10.9.9.9"))
    }

    @Test
    fun orderByReachabilityPutsReachableFirstThenUntested() = runBlocking {
        val reachable = connection(port = openPort)
        val untested = connection(ip = "10.99.0.1", host = "untested-host")
        ServerReachabilityTester.testConnections(listOf(reachable), timeoutMs = 1_000)

        val snapshot = ServerReachabilityTester.serverListResultsMap()
        val ordered = ServerReachabilityTester.orderByReachability(
            listOf(untested, reachable),
            snapshot,
        )

        assertEquals(listOf(reachable, untested), ordered)
    }

    @Test
    fun orderByReachabilityPutsBlockedLastAndReversesOnDescending() = runBlocking {
        val blocked = connection(port = closedPort)
        val untested = connection(ip = "10.99.0.1", host = "untested-host")
        ServerReachabilityTester.testConnections(listOf(blocked), timeoutMs = 1_000)

        val snapshot = ServerReachabilityTester.serverListResultsMap()
        val ascending = ServerReachabilityTester.orderByReachability(
            listOf(blocked, untested),
            snapshot,
        )
        val descending = ServerReachabilityTester.orderByReachability(
            listOf(untested, blocked),
            snapshot,
            descending = true,
        )

        assertEquals(listOf(untested, blocked), ascending)
        assertEquals(listOf(blocked, untested), descending)
    }

    @Test
    fun onlyReachableKeepsOnlyHealthyServers() = runBlocking {
        val blocked = connection(port = closedPort)
        val untested = connection(ip = "10.99.0.1", host = "untested-host")
        ServerReachabilityTester.testConnections(listOf(blocked), timeoutMs = 1_000)

        assertTrue(
            ServerReachabilityTester.onlyReachable(
                listOf(blocked, untested),
                ServerReachabilityTester.serverListResultsMap(),
            ).isEmpty(),
        )

        ServerReachabilityTester.clear()
        val reachable = connection(port = openPort)
        ServerReachabilityTester.testConnections(listOf(reachable), timeoutMs = 1_000)

        assertEquals(
            listOf(reachable),
            ServerReachabilityTester.onlyReachable(
                listOf(reachable, untested),
                ServerReachabilityTester.serverListResultsMap(),
            ),
        )
    }

    @Test
    fun filterReachableCandidatesDropsBlockedServers() = runBlocking {
        val reachable = candidate("127.0.0.1", openPort)
        val blocked = candidate("127.0.0.1", closedPort)

        // Stage 2F-F.13 (Y4): the tester is handed selected endpoints, so the
        // endpoint under test is stated here rather than derived from a protocol.
        val gate = ServerReachabilityTester.filterReachableCandidates(
            listOf(
                ReachabilityTarget(blocked, AutoModeProtocol.SOFTETHER_TCP, closedPort),
                ReachabilityTarget(reachable, AutoModeProtocol.SOFTETHER_TCP, openPort),
            ),
            timeoutMs = 1_000,
        )

        assertEquals(listOf(reachable), gate.kept)
        assertTrue(
            "one server survived, so this is not an all-unreachable result",
            !gate.allUnreachable,
        )
    }

    /**
     * Stage 2F-F.17 (D1): a refused endpoint is a definitive verdict, and a list
     * that loses every server for that reason is a *measurement*, not an absence
     * of one. The two must be distinguishable, because the controller's response
     * to them is opposite — honour the removal, or keep the queue.
     */
    @Test
    fun filterReachableCandidatesReportsDefinitiveAllUnreachable() = runBlocking {
        val blocked = candidate("127.0.0.1", closedPort)

        val gate = ServerReachabilityTester.filterReachableCandidates(
            listOf(ReachabilityTarget(blocked, AutoModeProtocol.SOFTETHER_TCP, closedPort)),
            timeoutMs = 1_000,
        )

        assertTrue(gate.kept.isEmpty())
        assertTrue(
            "a refused connection is proof, so the caller must be told to remove it",
            gate.allUnreachable,
        )
    }

    /**
     * Stage 2F-F.13 (Y4), objective 9/10: the tester must measure the endpoint it
     * was given, not re-derive one.
     *
     * This candidate publishes NO SoftEther TCP port at all, so any capability
     * logic inside the tester would conclude "nothing to probe" and drop it. It
     * is nevertheless probed — and found reachable — because the controller
     * already decided this OpenVPN TCP endpoint is usable. Likewise a target
     * with no endpoint at all is not probed, without the tester having to reach
     * a verdict about the protocol.
     */
    @Test
    fun testerProbesTheSuppliedEndpointWithoutReDerivingIt() = runBlocking {
        val seTcpBlank = candidate("127.0.0.1", 0).copy(openVpnTcpPort = openPort)

        val gate = ServerReachabilityTester.filterReachableCandidates(
            listOf(
                ReachabilityTarget(seTcpBlank, AutoModeProtocol.OPENVPN_TCP, openPort),
                // A target with no endpoint is recorded UNKNOWN, not probed.
                ReachabilityTarget(seTcpBlank, AutoModeProtocol.SOFTETHER_TCP, 0),
            ),
            timeoutMs = 1_000,
        )

        assertEquals(listOf(seTcpBlank), gate.kept)
        assertTrue(!gate.allUnreachable)
    }

    // ------------------------------------------------------- Stage 2F-F.28S

    /**
     * 500 distinct addresses, one per connection.
     *
     * `10.0.0` … `10.0.249` then `10.1.0` … `10.1.249`, so no two connections
     * share an IP and every one of them gets its own snapshot entry.
     */
    private fun largeServerList(count: Int = 500): List<VPNGateConnection> =
        List(count) { index -> connection(ip = "10.${index / 250}.${index % 250}") }

    /**
     * A snapshot covering every shape the comparator can meet, laid out so the
     * expected order can be asserted exactly:
     *
     * - `0..149` reachable, RTT `10,20,…,1500` — ascending in input order.
     * - `150..199` reachable, all sharing RTT `5` — one tie group, and the
     *   lowest RTT, so it must lead and keep its input order.
     * - `200..349` no entry at all — untested.
     * - `350..499` blocked, stored with the `-1` default RTT.
     */
    private fun largeSnapshot(
        connections: List<VPNGateConnection>,
    ): Map<String, ReachabilityResult> {
        val snapshot = LinkedHashMap<String, ReachabilityResult>()
        connections.forEachIndexed { index, connection ->
            val ip = connection.ip ?: return@forEachIndexed
            when {
                index < 150 -> snapshot[ip] = ReachabilityResult(ip, 443, true, rttMs = (index + 1) * 10L)
                index < 200 -> snapshot[ip] = ReachabilityResult(ip, 443, true, rttMs = 5L)
                index < 350 -> Unit
                else -> snapshot[ip] = ReachabilityResult(ip, 443, false)
            }
        }
        return snapshot
    }

    /**
     * Stage 2F-F.28S: the production crash class, as a regression test.
     *
     * F.28R traced `IllegalArgumentException: Comparison method violates its
     * general contract!` to this ordering taking its keys from the live
     * `_serverListResults` map, which the quick test rewrites while the Home
     * screen sorts. The comparator is now a pure function of the connection
     * and a snapshot, so a list far larger than TimSort's 32-element run
     * threshold must simply order correctly.
     *
     * The snapshot is built directly rather than by probing: after the fix it
     * is the only input the comparator may read, so the test exercises the
     * production shape and stays fast and fully deterministic.
     */
    @Test
    fun orderByReachabilityOrdersAListLargeEnoughToTripTimSort() {
        val connections = largeServerList()
        val snapshot = largeSnapshot(connections)

        val ordered = ServerReachabilityTester.orderByReachability(connections, snapshot)
        val ips = ordered.map { it.ip }

        assertEquals("no element is lost", connections.size, ordered.size)
        assertEquals(
            "sorting is a permutation: every input appears exactly once",
            connections.map { it.ip }.toSet(),
            ips.toSet(),
        )

        assertEquals(
            "equal RTT ties keep their input order and lead on the lowest RTT",
            connections.subList(150, 200).map { it.ip },
            ips.subList(0, 50),
        )
        assertEquals(
            "reachable servers then order by ascending RTT",
            connections.subList(0, 150).map { it.ip },
            ips.subList(50, 200),
        )
        assertEquals(
            "untested servers follow, in input order",
            connections.subList(200, 350).map { it.ip },
            ips.subList(200, 350),
        )
        assertEquals(
            "blocked servers come last, in input order",
            connections.subList(350, 500).map { it.ip },
            ips.subList(350, 500),
        )
    }

    /**
     * CONSISTENCY, which is the contract the live-map comparator broke:
     * repeated comparison of unchanged data must give the same answer, and
     * descending must stay the exact reverse of ascending.
     */
    @Test
    fun orderByReachabilityIsDeterministicAndReversesOnDescending() {
        val connections = largeServerList()
        val snapshot = largeSnapshot(connections)

        val ascending = ServerReachabilityTester.orderByReachability(connections, snapshot)
        repeat(5) {
            assertEquals(
                "an unchanged snapshot must order identically every time",
                ascending,
                ServerReachabilityTester.orderByReachability(connections, snapshot),
            )
        }

        assertEquals(
            "descending is the reverse of ascending",
            ascending.reversed(),
            ServerReachabilityTester.orderByReachability(connections, snapshot, descending = true),
        )
    }

    /**
     * A missing, empty or blank address has no result, so it must not be
     * looked up as a map key. The snapshot deliberately contains an `""` entry
     * that is reachable with the lowest possible RTT: if the blank addresses
     * were treated as keys, they would outrank the genuinely reachable server
     * and this test would fail.
     */
    @Test
    fun orderByReachabilityTreatsBlankAndMissingAddressesAsUntested() {
        val nullIp = connection(ip = null)
        val blankIp = connection(ip = "   ")
        val emptyIp = connection(ip = "")
        val reachable = connection(ip = "127.0.0.1")

        val snapshot = mapOf(
            "127.0.0.1" to ReachabilityResult("127.0.0.1", 443, true, rttMs = 12L),
            "" to ReachabilityResult("", 443, true, rttMs = 1L),
        )

        val ordered = ServerReachabilityTester.orderByReachability(
            listOf(nullIp, blankIp, emptyIp, reachable),
            snapshot,
        )

        assertEquals(
            "reachable first, then every address-less server in input order",
            listOf(reachable, nullIp, blankIp, emptyIp),
            ordered,
        )
    }

    /**
     * Stage 2F-F.28S: the comparator must not read the live result store.
     *
     * A writer thread runs the real quick test throughout, so the live answers
     * for these very IPs keep changing underneath the sort — and it writes the
     * verdict that *contradicts* the snapshot, so any live read would visibly
     * reorder the list rather than merely perturb a tie. The ordering comes
     * from the snapshot alone, so the result must be identical to the one
     * produced before the writer started.
     *
     * This is deliberately not `assertFailsWith<IllegalArgumentException>`:
     * the fix makes the throw impossible by construction, so the property worth
     * pinning is independence from the live store, and that assertion cannot
     * flake — a fixed snapshot always produces one fixed answer.
     */
    @Test
    fun orderByReachabilityIgnoresLiveStoreMutationsWhileSorting() = runBlocking {
        val connections = largeServerList()
        val snapshot = largeSnapshot(connections)
        val expected = ServerReachabilityTester.orderByReachability(connections, snapshot)

        // The snapshot calls these reachable; the live store is made to say blocked.
        val contradicted = connections.take(5).map { it.ip!! }
        val contradicting = contradicted.map { connection(ip = it, port = closedPort) }

        // Seed the contradicting verdicts BEFORE sorting anything, so the
        // disagreement between live store and snapshot is a fact rather than a
        // race. Otherwise a background writer that has not been scheduled yet
        // would let a live-reading comparator slip through untested.
        ServerReachabilityTester.testConnections(contradicting, timeoutMs = 1_000)
        contradicted.forEach {
            assertTrue(
                "the live store must really disagree with the snapshot",
                !ServerReachabilityTester.isServerListReachable(it),
            )
        }

        val stop = AtomicBoolean(false)
        val writer = thread(name = "reachability-writer") {
            while (!stop.get()) {
                runBlocking {
                    ServerReachabilityTester.testConnections(contradicting, timeoutMs = 50)
                }
            }
        }

        try {
            repeat(200) {
                assertEquals(
                    "the live store changed underneath the sort and the order must not notice",
                    expected,
                    ServerReachabilityTester.orderByReachability(connections, snapshot),
                )
            }
        } finally {
            stop.set(true)
            writer.join()
        }
    }

    /**
     * Stage 2F-F.28S: one composition takes one snapshot, and both the
     * healthy-only filter and the ordering are answered by it.
     *
     * Note the two passes deliberately disagree on *order* — filtering keeps
     * the input order, sorting ranks by RTT — so this asserts that they agree
     * on *membership*: the servers the filter keeps are exactly the ones the
     * ordering puts first.
     */
    @Test
    fun filterAndOrderAgreeOnOneSnapshot() {
        val connections = largeServerList()
        val snapshot = largeSnapshot(connections)

        val filtered = ServerReachabilityTester.onlyReachable(connections, snapshot)
        val ordered = ServerReachabilityTester.orderByReachability(connections, snapshot)

        assertEquals(
            "only the reachable servers survive the filter",
            connections.subList(0, 200).map { it.ip },
            filtered.map { it.ip },
        )
        assertEquals(
            "and the ordering puts exactly those same servers first",
            filtered.map { it.ip }.toSet(),
            ordered.subList(0, 200).map { it.ip }.toSet(),
        )
    }
}
