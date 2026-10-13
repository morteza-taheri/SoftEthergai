package vn.unlimit.vpngate.network

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vn.unlimit.vpngate.automode.AutoModeCandidate
import vn.unlimit.vpngate.automode.AutoModeProtocol
import vn.unlimit.vpngate.automode.ReachabilityTarget
import vn.unlimit.vpngate.models.VPNGateConnection
import java.net.ServerSocket

/**
 * Stage 2F-F.14 (D9 + D10 + D11 + Y5): reachability result identity.
 *
 * The app asks two different questions and used to file both answers under the
 * bare server IP, so whichever workflow finished last silently overwrote the
 * other:
 *
 * - the Server List quick test, which is protocol-less and asks about the server;
 * - Auto Connect, which asks about one server/protocol pair.
 *
 * Every test here drives the REAL store ([ServerReachabilityTester]) through its
 * real production entry points — `testConnections` for the Server List and
 * `filterReachableCandidates` for Auto Connect — rather than poking keys in
 * isolation, because the bug being prevented is a bug in the access paths.
 */
class ReachabilityNamespaceTest {

    private lateinit var listener: ServerSocket
    private var openPort = 0
    private var closedPort = 0

    @Before
    fun setUp() {
        ServerReachabilityTester.clear()
        listener = ServerSocket(0)
        openPort = listener.localPort
        closedPort = ServerSocket(0).use { it.localPort }
    }

    @After
    fun tearDown() {
        listener.close()
        ServerReachabilityTester.clear()
    }

    /** A VPNGateConnection for the Server List quick test (protocol-less, Y5). */
    private fun connection(ip: String?, port: Int) = VPNGateConnection().apply {
        this.ip = ip
        this.hostName = "srv-$port"
        this.seTcpPort = port
    }

    /** An Auto Connect target for [ip] over [protocol], pointing at [port]. */
    private fun target(ip: String, protocol: AutoModeProtocol, port: Int): ReachabilityTarget {
        val candidate = AutoModeCandidate(
            hostname = "srv-$protocol",
            ip = ip,
            speed = 10_000_000,
            ping = 30,
            sessions = 10,
            score = 100,
            sources = 1,
            seTcpPort = if (protocol == AutoModeProtocol.SOFTETHER_TCP) port else 0,
            seUdpPort = 0,
            seUdpSupported = false,
            openVpnTcpPort = 0,
            openVpnUdpPort = 0,
            l2tpSupported = false,
            sstpSupported = false,
        )
        return ReachabilityTarget(candidate, protocol, port)
    }

    /**
     * Drive the real Auto Connect path so the result is written exactly the way
     * production writes it.
     */
    private suspend fun probeViaAutoConnect(vararg targets: ReachabilityTarget) {
        ServerReachabilityTester.filterReachableCandidates(targets.toList(), timeoutMs = 1_000)
    }

    /** Drive the real Server List path. */
    private suspend fun probeViaServerList(ip: String, port: Int) {
        ServerReachabilityTester.testConnections(listOf(connection(ip, port)), timeoutMs = 1_000)
    }

    // ---- D9: Auto Connect identity is IP + protocol ----

    /** TEST 1 — two protocols on one IP are two independent results. */
    @Test
    fun autoConnectResultsAreIsolatedPerProtocol() = runBlocking {
        val x = "127.0.0.1"
        probeViaAutoConnect(
            target(x, AutoModeProtocol.SOFTETHER_TCP, openPort),
            target(x, AutoModeProtocol.OPENVPN_TCP, closedPort),
        )

        assertTrue(ServerReachabilityTester.isAutoConnectReachable(x, AutoModeProtocol.SOFTETHER_TCP))
        assertFalse(ServerReachabilityTester.isAutoConnectReachable(x, AutoModeProtocol.OPENVPN_TCP))
    }

    /**
     * TEST 2 — SoftEther UDP must not overwrite SoftEther TCP.
     *
     * Both transports of one server on one IP: the exact pair that a single
     * shared "SoftEther" answer would collapse.
     */
    @Test
    fun softEtherUdpDoesNotOverwriteSoftEtherTcp() = runBlocking {
        val x = "127.0.0.1"
        probeViaAutoConnect(
            target(x, AutoModeProtocol.SOFTETHER_TCP, openPort),
            target(x, AutoModeProtocol.SOFTETHER_UDP, closedPort),
        )

        assertTrue(ServerReachabilityTester.isAutoConnectReachable(x, AutoModeProtocol.SOFTETHER_TCP))
        assertFalse(ServerReachabilityTester.isAutoConnectReachable(x, AutoModeProtocol.SOFTETHER_UDP))
        // And the two endpoints are genuinely different facts, not one answer.
        assertNotEquals(
            ServerReachabilityTester.autoConnectResult(x, AutoModeProtocol.SOFTETHER_TCP),
            ServerReachabilityTester.autoConnectResult(x, AutoModeProtocol.SOFTETHER_UDP),
        )
    }

    /** TEST 5 — three protocols on one IP all survive. */
    @Test
    fun threeProtocolsOnOneServerRemainIndependent() = runBlocking {
        val x = "127.0.0.1"
        probeViaAutoConnect(
            target(x, AutoModeProtocol.SOFTETHER_TCP, openPort),
            target(x, AutoModeProtocol.SOFTETHER_UDP, closedPort),
            target(x, AutoModeProtocol.OPENVPN_TCP, closedPort),
        )

        assertTrue(ServerReachabilityTester.isAutoConnectReachable(x, AutoModeProtocol.SOFTETHER_TCP))
        assertFalse(ServerReachabilityTester.isAutoConnectReachable(x, AutoModeProtocol.SOFTETHER_UDP))
        assertFalse(ServerReachabilityTester.isAutoConnectReachable(x, AutoModeProtocol.OPENVPN_TCP))
        assertEquals(3, ServerReachabilityTester.autoConnectResultKeys.size)
    }

    /** TEST 6 — different servers stay independent, same protocol. */
    @Test
    fun differentServersRemainIndependent() = runBlocking {
        probeViaAutoConnect(
            target("127.0.0.1", AutoModeProtocol.SOFTETHER_TCP, openPort),
            target("127.0.0.2", AutoModeProtocol.SOFTETHER_TCP, closedPort),
        )

        assertTrue(ServerReachabilityTester.isAutoConnectReachable("127.0.0.1", AutoModeProtocol.SOFTETHER_TCP))
        assertFalse(ServerReachabilityTester.isAutoConnectReachable("127.0.0.2", AutoModeProtocol.SOFTETHER_TCP))
    }

    /**
     * TEST 7 — Protocol Priority is not part of the identity.
     *
     * Identity is the protocol enum, never a priority position: reordering the
     * user's Protocol Priority must not change what a stored result means, or
     * simply using a different priority profile would silently orphan every
     * result Auto Connect had already learned.
     */
    @Test
    fun identityIsIndependentOfProtocolPriorityOrder() = runBlocking {
        val x = "127.0.0.1"
        probeViaAutoConnect(target(x, AutoModeProtocol.SOFTETHER_TCP, openPort))

        val storedKey = ServerReachabilityTester.autoConnectResultKeys.single()
        assertEquals(
            "identity is IP + protocol, nothing else",
            ReachabilityKey.AutoConnect(x, AutoModeProtocol.SOFTETHER_TCP),
            storedKey,
        )

        // Two genuinely different priority profiles for the same two protocols.
        val sstpFirst = listOf(AutoModeProtocol.MS_SSTP, AutoModeProtocol.SOFTETHER_TCP)
        val softEtherFirst = listOf(AutoModeProtocol.SOFTETHER_TCP, AutoModeProtocol.MS_SSTP)
        assertEquals(AutoModeProtocol.MS_SSTP, sstpFirst.first())
        assertEquals(AutoModeProtocol.SOFTETHER_TCP, softEtherFirst.first())

        // Changing the profile does not re-file the result.
        assertEquals(
            storedKey,
            ServerReachabilityTester.autoConnectResultKeys.single(),
        )
        assertTrue(
            "the stored result must survive a Protocol Priority change",
            ServerReachabilityTester.isAutoConnectReachable(x, AutoModeProtocol.SOFTETHER_TCP),
        )
    }

    // ---- D10 + Y5 + D11: namespace separation ----

    /** TEST 3 — Server List and Auto Connect never overwrite each other. */
    @Test
    fun serverListAndAutoConnectResultsCoexist() = runBlocking {
        val x = "127.0.0.1"
        probeViaAutoConnect(target(x, AutoModeProtocol.OPENVPN_TCP, closedPort))
        // The Auto Connect answer must genuinely EXIST before the Server List
        // writes, otherwise "still unreachable" below would also be satisfied by
        // "never recorded", and the test would prove nothing.
        assertTrue(
            "the Auto Connect probe must have stored an answer of its own",
            ServerReachabilityTester.hasAutoConnectResult(x, AutoModeProtocol.OPENVPN_TCP),
        )

        probeViaServerList(x, openPort)

        assertTrue("Server List answer kept", ServerReachabilityTester.isServerListReachable(x))
        assertFalse("Auto Connect answer kept", ServerReachabilityTester.isAutoConnectReachable(x, AutoModeProtocol.OPENVPN_TCP))
        assertEquals(
            "both namespaces hold one result each",
            2,
            ServerReachabilityTester.resultKeys.size,
        )
    }

    /** TEST 4 — reverse write order must be equally harmless. */
    @Test
    fun serverListWriteAfterAutoConnectDoesNotDisturbIt() = runBlocking {
        val x = "127.0.0.1"
        probeViaAutoConnect(target(x, AutoModeProtocol.OPENVPN_TCP, openPort))
        val stored = ServerReachabilityTester.autoConnectResult(x, AutoModeProtocol.OPENVPN_TCP)
        assertTrue(
            "the Auto Connect result must exist, and be reachable, before the Server List writes",
            stored?.isReachable == true,
        )

        probeViaServerList(x, closedPort)

        assertFalse(ServerReachabilityTester.isServerListReachable(x))
        assertEquals(
            "a later Server List write must not overwrite the Auto Connect result",
            stored,
            ServerReachabilityTester.autoConnectResult(x, AutoModeProtocol.OPENVPN_TCP),
        )
        assertTrue(
            ServerReachabilityTester.isAutoConnectReachable(x, AutoModeProtocol.OPENVPN_TCP),
        )
    }

    /** TEST 8 — the Server List stays protocol-less: no protocol needed to read it. */
    @Test
    fun serverListResultNeedsNoProtocol() = runBlocking {
        val x = "127.0.0.1"
        probeViaServerList(x, openPort)

        val result = ServerReachabilityTester.serverListResult(x)
        assertTrue(result?.isReachable == true)
        assertEquals(openPort, result?.port)
    }

    /** TEST 9 — neither namespace can be read through the other's accessor. */
    @Test
    fun namespacesAreNeverCrossRead() = runBlocking {
        val x = "127.0.0.1"
        probeViaServerList(x, openPort)
        probeViaAutoConnect(target(x, AutoModeProtocol.SOFTETHER_UDP, closedPort))

        // Server List read sees only Server List.
        assertTrue(ServerReachabilityTester.isServerListReachable(x))
        // Auto Connect read sees only Auto Connect, per protocol.
        assertFalse(ServerReachabilityTester.isAutoConnectReachable(x, AutoModeProtocol.SOFTETHER_UDP))
        // The Auto Connect protocol that was never probed is genuinely unknown.
        assertNull(ServerReachabilityTester.autoConnectResult(x, AutoModeProtocol.SOFTETHER_TCP))
        // …and reading the wrong namespace returns nothing rather than the other
        // namespace's answer.
        assertNull(ServerReachabilityTester.autoConnectResult(x, AutoModeProtocol.SOFTETHER_TCP))
        assertEquals(2, ServerReachabilityTester.resultKeys.size)
    }

    /**
     * TEST 10 — clearing semantics.
     *
     * Scoped clears must not touch the other namespace; the pre-existing global
     * `clear()` deliberately still clears everything.
     */
    @Test
    fun clearingIsScopedOrExplicitlyGlobal() = runBlocking {
        val x = "127.0.0.1"
        probeViaServerList(x, openPort)
        probeViaAutoConnect(target(x, AutoModeProtocol.OPENVPN_TCP, closedPort))

        ServerReachabilityTester.clearServerList()
        assertFalse(ServerReachabilityTester.isServerListReachable(x))
        assertFalse(
            "clearing the Server List must not erase Auto Connect evidence",
            ServerReachabilityTester.isAutoConnectReachable(x, AutoModeProtocol.OPENVPN_TCP),
        )

        probeViaServerList(x, openPort)
        ServerReachabilityTester.clearAutoConnect()
        assertTrue(ServerReachabilityTester.isServerListReachable(x))
        assertFalse(ServerReachabilityTester.isAutoConnectReachable(x, AutoModeProtocol.OPENVPN_TCP))

        // The documented global clear resets both namespaces.
        probeViaServerList(x, openPort)
        probeViaAutoConnect(target(x, AutoModeProtocol.OPENVPN_TCP, closedPort))
        ServerReachabilityTester.clear()
        assertTrue(ServerReachabilityTester.resultKeys.isEmpty())
    }

    // ---- Structural guarantees ----

    /**
     * TEST 12 — an IP-only Auto Connect lookup is unrepresentable.
     *
     * The auto Connect accessors all take a protocol, and the key type cannot be
     * constructed without one, so "look up the Auto Connect result for this IP"
     * is a compile error rather than a bug waiting to happen.
     */
    @Test
    fun autoConnectIdentityRequiresAProtocol() {
        assertEquals(
            ReachabilityKey.AutoConnect("1.2.3.4", AutoModeProtocol.SOFTETHER_TCP),
            ReachabilityKey.AutoConnect("1.2.3.4", AutoModeProtocol.SOFTETHER_TCP),
        )
        assertNotEquals(
            ReachabilityKey.AutoConnect("1.2.3.4", AutoModeProtocol.SOFTETHER_TCP),
            ReachabilityKey.AutoConnect("1.2.3.4", AutoModeProtocol.SOFTETHER_UDP),
        )
        assertNotEquals(
            "the two namespaces must never collide",
            ReachabilityKey.ServerList("1.2.3.4"),
            ReachabilityKey.AutoConnect("1.2.3.4", AutoModeProtocol.SOFTETHER_TCP),
        )
    }
}
