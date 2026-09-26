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
import vn.unlimit.vpngate.models.VPNGateConnection
import java.net.ServerSocket

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

        assertTrue(results["127.0.0.1"]?.isReachable == true)
        assertFalse("progress flag must be cleared", ServerReachabilityTester.isTesting.value)
        assertEquals(1, ServerReachabilityTester.totalCount.value)
        assertEquals(1, ServerReachabilityTester.testedCount.value)
        assertEquals(1, ServerReachabilityTester.testedCount(servers))
        assertEquals(1, ServerReachabilityTester.reachableCount(servers))
        assertTrue(ServerReachabilityTester.hasResult("127.0.0.1"))
        assertFalse(ServerReachabilityTester.hasResult("10.9.9.9"))
    }

    @Test
    fun orderByReachabilityPutsReachableFirstThenUntested() = runBlocking {
        val reachable = connection(port = openPort)
        val untested = connection(ip = "10.99.0.1", host = "untested-host")
        ServerReachabilityTester.testConnections(listOf(reachable), timeoutMs = 1_000)

        val ordered = ServerReachabilityTester.orderByReachability(listOf(untested, reachable))

        assertEquals(listOf(reachable, untested), ordered)
    }

    @Test
    fun orderByReachabilityPutsBlockedLastAndReversesOnDescending() = runBlocking {
        val blocked = connection(port = closedPort)
        val untested = connection(ip = "10.99.0.1", host = "untested-host")
        ServerReachabilityTester.testConnections(listOf(blocked), timeoutMs = 1_000)

        val ascending = ServerReachabilityTester.orderByReachability(listOf(blocked, untested))
        val descending = ServerReachabilityTester.orderByReachability(
            listOf(untested, blocked),
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

        assertTrue(ServerReachabilityTester.onlyReachable(listOf(blocked, untested)).isEmpty())

        ServerReachabilityTester.clear()
        val reachable = connection(port = openPort)
        ServerReachabilityTester.testConnections(listOf(reachable), timeoutMs = 1_000)

        assertEquals(
            listOf(reachable),
            ServerReachabilityTester.onlyReachable(listOf(reachable, untested)),
        )
    }

    @Test
    fun filterReachableCandidatesDropsBlockedServers() = runBlocking {
        val reachable = candidate("127.0.0.1", openPort)
        val blocked = candidate("127.0.0.1", closedPort)

        val filtered = ServerReachabilityTester.filterReachableCandidates(
            listOf(blocked, reachable),
            AutoModeProtocol.SOFTETHER_TCP,
            timeoutMs = 1_000,
        )

        assertEquals(listOf(reachable), filtered)
    }

    @Test
    fun filterReachableCandidatesFallsBackToOriginalList() = runBlocking {
        val blocked = candidate("127.0.0.1", closedPort)
        val servers = listOf(blocked)

        val filtered = ServerReachabilityTester.filterReachableCandidates(
            servers,
            AutoModeProtocol.SOFTETHER_TCP,
            timeoutMs = 1_000,
        )

        // Nothing reachable: probing must never empty the Auto Mode queue.
        assertSame(servers, filtered)
    }
}
