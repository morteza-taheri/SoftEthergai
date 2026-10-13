package vn.unlimit.vpngate.network

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vn.unlimit.vpngate.automode.AutoModeCandidate
import vn.unlimit.vpngate.automode.AutoModeProtocol
import vn.unlimit.vpngate.automode.ProbeTransport
import vn.unlimit.vpngate.automode.ReachabilityTarget
import vn.unlimit.vpngate.automode.endpointPort
import vn.unlimit.vpngate.automode.probeTransport
import vn.unlimit.vpngate.models.VPNGateConnection
import java.net.ServerSocket

/**
 * Stage 2F-F.16 (D7 + Y2 + Y3): transport-appropriate probing, the tri-state
 * Auto Connect verdict, and the separate namespaces.
 *
 * The single decision everything else hangs off: **the probe mechanism follows the
 * protocol.** Phase 2 fixed endpoint *selection* — each protocol uses the port it
 * published — but the probe itself was still a single TCP `connect()`. For a UDP
 * protocol that answer was not about the protocol at all. A TCP connect to a host
 * whose UDP endpoint is wide open fails, and a TCP connect to a closed host says
 * nothing about UDP: two different transports through two different firewall
 * rules. Reading one as the other is how a working UDP server gets deleted.
 *
 * So the probe is chosen per protocol, and its result is tri-state, because a
 * single transport cannot produce two kinds of certainty:
 *
 * - `REACHABLE` — the endpoint answered on its own transport;
 * - `UNREACHABLE` — the endpoint refused on its own transport (TCP RST, ICMP port
 *   unreachable). A refusal is proof;
 * - `UNKNOWN` — silence, or nothing to test. A timeout is **not** proof of
 *   anything, and neither is an absent endpoint.
 *
 * Everything is driven through the real store ([ServerReachabilityTester]) and the
 * real classification, with the network replaced by a recording probe. Nothing
 * here asserts on a mock's return value alone: the point is what production
 * *stores* and what the queue then does with it.
 */
class AutoConnectProbeTransportTest {

    private lateinit var listener: ServerSocket
    private var openPort = 0

    @Before
    fun setUp() {
        ServerReachabilityTester.clear()
        listener = ServerSocket(0)
        openPort = listener.localPort
    }

    @After
    fun tearDown() {
        listener.close()
        ServerReachabilityTester.clear()
    }

    /**
     * The network seam.
     *
     * Records every target it was asked about, so a test can assert not just the
     * verdict but *whether a probe happened at all* — which is the only way to tell
     * "we had nothing to test" apart from "we tested it and it was silent".
     */
    private class RecordingProbe(
        private val outcome: (ReachabilityTarget) -> WireOutcome,
    ) : AutoConnectReachabilityProbe {
        val calls = mutableListOf<ReachabilityTarget>()

        override fun probe(target: ReachabilityTarget, tcpTimeoutMs: Int): ProbeReport {
            calls += target
            return ProbeReport(outcome(target), 25_000_000L) // 25 ms
        }
    }

    private fun candidate(
        ip: String?,
        seTcpPort: Int = 0,
        seUdpPort: Int = 0,
    ) = AutoModeCandidate(
        hostname = "srv-${ip ?: "none"}",
        ip = ip,
        speed = 10_000_000,
        ping = 30,
        sessions = 10,
        score = 100,
        sources = 1,
        seTcpPort = seTcpPort,
        seUdpPort = seUdpPort,
        seUdpSupported = seUdpPort > 0,
        openVpnTcpPort = 0,
        openVpnUdpPort = 0,
        l2tpSupported = false,
        sstpSupported = false,
    )

    private fun target(
        ip: String?,
        protocol: AutoModeProtocol,
        port: Int,
        seTcpPort: Int = 0,
        seUdpPort: Int = 0,
    ) = ReachabilityTarget(candidate(ip, seTcpPort, seUdpPort), protocol, port)

    /** A VPNGateConnection for the Server List quick test (protocol-less, Y5). */
    private fun connection(ip: String, port: Int) = VPNGateConnection().apply {
        this.ip = ip
        this.hostName = "srv-$port"
        this.seTcpPort = port
    }

    /**
     * Drive the real Auto Connect entry point with a substituted network.
     *
     * [filterReachableCandidates] is the production call the controller makes, so
     * the store is written exactly the way production writes it — only the socket
     * is replaced.
     */
    private suspend fun probe(
        probe: AutoConnectReachabilityProbe,
        vararg targets: ReachabilityTarget,
    ): List<AutoModeCandidate> =
        ServerReachabilityTester.filterReachableCandidates(targets.toList(), probe = probe).kept

    // ---- D7: the transport follows the protocol ----

    /** TEST 1 — the UDP protocols are probed over UDP. */
    @Test
    fun udpProtocolsDeclareUdpTransport() {
        assertEquals(ProbeTransport.UDP, AutoModeProtocol.SOFTETHER_UDP.probeTransport)
        assertEquals(ProbeTransport.UDP, AutoModeProtocol.OPENVPN_UDP.probeTransport)
    }

    /** TEST 2 — everything else is probed over TCP. */
    @Test
    fun tcpProtocolsDeclareTcpTransport() {
        assertEquals(ProbeTransport.TCP, AutoModeProtocol.SOFTETHER_TCP.probeTransport)
        assertEquals(ProbeTransport.TCP, AutoModeProtocol.OPENVPN_TCP.probeTransport)
        assertEquals(ProbeTransport.TCP, AutoModeProtocol.MS_SSTP.probeTransport)
        assertEquals(ProbeTransport.TCP, AutoModeProtocol.L2TP_IPSEC.probeTransport)
    }

    /** TEST 4 — a UDP protocol reaches the UDP path, and a good answer is REACHABLE. */
    @Test
    fun udpProtocolIsProbedOverUdpAndCanSucceed() = runBlocking {
        val seen = mutableListOf<ProbeTransport>()
        val probe = RecordingProbe { t ->
            seen += t.protocol.probeTransport
            WireOutcome.ANSWERED
        }

        val kept = probe(
            probe,
            target("127.0.0.1", AutoModeProtocol.SOFTETHER_UDP, openPort),
        )

        assertEquals(
            "a UDP endpoint must be tested with a datagram, never a TCP socket",
            listOf(ProbeTransport.UDP),
            seen,
        )
        assertEquals(1, kept.size)
        assertEquals(
            AutoConnectReachabilityState.REACHABLE,
            ServerReachabilityTester.autoConnectState("127.0.0.1", AutoModeProtocol.SOFTETHER_UDP),
        )
    }

    /** TEST 5 — a TCP protocol reaches the TCP path. */
    @Test
    fun tcpProtocolIsProbedOverTcp() = runBlocking {
        val seen = mutableListOf<ProbeTransport>()
        val probe = RecordingProbe { t ->
            seen += t.protocol.probeTransport
            WireOutcome.ANSWERED
        }

        probe(probe, target("127.0.0.1", AutoModeProtocol.SOFTETHER_TCP, openPort))

        assertEquals(listOf(ProbeTransport.TCP), seen)
        assertEquals(
            AutoConnectReachabilityState.REACHABLE,
            ServerReachabilityTester.autoConnectState("127.0.0.1", AutoModeProtocol.SOFTETHER_TCP),
        )
    }

    /**
     * TEST 3 (D7 vs D8) — the transport is not a capability decision.
     *
     * L2TP/IPsec declares TCP, which is honest about how it runs, but Auto Connect
     * still must never probe it: it cannot drive that protocol automatically. The
     * two decisions are kept in separate places on purpose, so widening the probe
     * layer cannot quietly widen what Auto Connect will attempt.
     * (`AutoModeControllerTest` proves the filtering end of this; the assertion here
     * is that the transport property carries no such opinion.)
     */
    @Test
    fun transportDeclarationSaysNothingAboutAutomatability() {
        assertEquals(
            "L2TP runs over TCP and is still not automatable",
            ProbeTransport.TCP,
            AutoModeProtocol.L2TP_IPSEC.probeTransport,
        )
        assertEquals(
            "SoftEther UDP is automatable and runs over UDP — two UDP protocols, and "
                + "only the transport property is asserted here",
            ProbeTransport.UDP,
            AutoModeProtocol.SOFTETHER_UDP.probeTransport,
        )
    }

    // ---- Y2: three verdicts, and only three ----

    /** TEST 8 — a refusal is proof, so TCP refusal is UNREACHABLE. */
    @Test
    fun refusalIsUnreachable() = runBlocking {
        val probe = RecordingProbe { WireOutcome.REFUSED }
        probe(probe, target("127.0.0.1", AutoModeProtocol.SOFTETHER_TCP, openPort))

        assertEquals(
            AutoConnectReachabilityState.UNREACHABLE,
            ServerReachabilityTester.autoConnectState("127.0.0.1", AutoModeProtocol.SOFTETHER_TCP),
        )
    }

    /** TEST 10 — UDP ICMP port-unreachable is the same kind of proof. */
    @Test
    fun udpPortUnreachableIsUnreachable() = runBlocking {
        val probe = RecordingProbe { WireOutcome.REFUSED }
        probe(probe, target("127.0.0.1", AutoModeProtocol.OPENVPN_UDP, openPort))

        assertEquals(
            AutoConnectReachabilityState.UNREACHABLE,
            ServerReachabilityTester.autoConnectState("127.0.0.1", AutoModeProtocol.OPENVPN_UDP),
        )
    }

    /** TEST 9 — silence is not proof. A timeout is UNKNOWN, never UNREACHABLE. */
    @Test
    fun tcpSilenceIsUnknown() = runBlocking {
        val probe = RecordingProbe { WireOutcome.SILENT }
        probe(probe, target("127.0.0.1", AutoModeProtocol.SOFTETHER_TCP, openPort))

        assertEquals(
            "a timeout proves nothing, so it must not be recorded as a failure",
            AutoConnectReachabilityState.UNKNOWN,
            ServerReachabilityTester.autoConnectState("127.0.0.1", AutoModeProtocol.SOFTETHER_TCP),
        )
    }

    /** TEST 11 — UDP silence is UNKNOWN too. Most VPN servers ignore a neutral datagram. */
    @Test
    fun udpSilenceIsUnknown() = runBlocking {
        val probe = RecordingProbe { WireOutcome.SILENT }
        probe(probe, target("127.0.0.1", AutoModeProtocol.SOFTETHER_UDP, openPort))

        assertEquals(
            AutoConnectReachabilityState.UNKNOWN,
            ServerReachabilityTester.autoConnectState("127.0.0.1", AutoModeProtocol.SOFTETHER_UDP),
        )
    }

    /** TEST 13 — the classification itself, exhaustively, and no rule it must not have. */
    @Test
    fun classificationIsExhaustiveAndReportsNoRttWithoutAnAnswer() {
        assertEquals(AutoConnectReachabilityState.REACHABLE, classify(WireOutcome.ANSWERED))
        assertEquals(AutoConnectReachabilityState.UNREACHABLE, classify(WireOutcome.REFUSED))
        assertEquals(AutoConnectReachabilityState.UNKNOWN, classify(WireOutcome.SILENT))

        assertNull(
            "a refusal has no round trip to report",
            ProbeReport(WireOutcome.REFUSED, 5_000_000L).toResult().rttMs,
        )
        assertNull(
            "and silence has none either, however long we waited",
            ProbeReport(WireOutcome.SILENT, 999_000_000L).toResult().rttMs,
        )
        assertEquals(
            "an elapsed time is only reported for an answer",
            12L,
            ProbeReport(WireOutcome.ANSWERED, 12_000_000L).toResult().rttMs,
        )
    }

    /**
     * TEST 14 — a usable RTT exists only for a definitive REACHABLE.
     *
     * The Phase 4 comparator treats a missing RTT as neutral, so a state that
     * donated `0` or a guessed number here would quietly move servers in the final
     * tie-break. Nothing may reach the ranking without an answer behind it.
     */
    @Test
    fun rttIsOnlyAvailableForAReachableVerdict() {
        assertEquals(40L, ProbeReport(WireOutcome.ANSWERED, 40_000_000L).toResult().rttIfReachable())
        assertNull("UNREACHABLE contributes nothing", AutoConnectReachabilityResult.unreachable().rttIfReachable())
        assertNull("UNKNOWN contributes nothing", AutoConnectReachabilityResult.unknown().rttIfReachable())
        assertEquals(
            "a sub-millisecond answer is clamped up, never reported as a 0 ms round trip",
            1L,
            ProbeReport(WireOutcome.ANSWERED, 400_000L).toResult().rttIfReachable(),
        )
    }

    // ---- Y2: nothing to test is UNKNOWN, not an absence ----

    /**
     * TEST 6 — a protocol with no published endpoint is recorded UNKNOWN.
     *
     * Phase 2 skipped it, leaving no record at all — the same absence a "no result
     * for this IP" lookup returns, which is what made it read as benign. Now the
     * probe is not even attempted (there is nothing to aim at) and the verdict is
     * explicitly UNKNOWN.
     */
    @Test
    fun unpublishedEndpointIsUnknownAndIsNotProbed() = runBlocking {
        val probe = RecordingProbe { WireOutcome.ANSWERED }
        val srv = candidate("127.0.0.1", seTcpPort = 9922, seUdpPort = 0)

        probe(probe, ReachabilityTarget(srv, AutoModeProtocol.SOFTETHER_UDP, 0))

        assertTrue("no endpoint means no datagram is sent", probe.calls.isEmpty())
        assertEquals(
            AutoConnectReachabilityState.UNKNOWN,
            ServerReachabilityTester.autoConnectState("127.0.0.1", AutoModeProtocol.SOFTETHER_UDP),
        )
    }

    /** TEST 7 — and a server with no IP at all cannot be probed either. */
    @Test
    fun missingIpIsNotProbedAndIsNotStored() = runBlocking {
        val probe = RecordingProbe { WireOutcome.ANSWERED }
        probe(probe, target(null, AutoModeProtocol.SOFTETHER_TCP, openPort))

        assertTrue(probe.calls.isEmpty())
        assertNull(
            ServerReachabilityTester.autoConnectState(null, AutoModeProtocol.SOFTETHER_TCP),
        )
    }

    /**
     * TEST 18 — no port is borrowed to make a probe possible.
     *
     * This is the SoftEther case Phase 2 called out: `seUdpSupported` with no
     * published UDP port. The candidate *does* publish a TCP port, and a probe is
     * easy to run against it — but that would be evidence about SoftEther TCP
     * filed under SoftEther UDP, which is the exact substitution D7 forbids. The
     * honest answer is UNKNOWN.
     */
    @Test
    fun softEtherUdpWithoutAnEndpointNeverBorrowsTheTcpPort() = runBlocking {
        val probe = RecordingProbe { WireOutcome.ANSWERED }
        val srv = candidate("127.0.0.1", seTcpPort = 9922, seUdpPort = 0)

        assertEquals(0, AutoModeProtocol.SOFTETHER_UDP.endpointPort(srv))
        probe(probe, ReachabilityTarget(srv, AutoModeProtocol.SOFTETHER_UDP, 0))

        assertTrue(probe.calls.isEmpty())
        assertEquals(
            AutoConnectReachabilityState.UNKNOWN,
            ServerReachabilityTester.autoConnectState("127.0.0.1", AutoModeProtocol.SOFTETHER_UDP),
        )
        assertNull(
            "and no TCP verdict may appear under the UDP identity",
            ServerReachabilityTester.autoConnectState("127.0.0.1", AutoModeProtocol.SOFTETHER_TCP),
        )
    }

    // ---- the gate: only UNREACHABLE may remove a server ----

    /** TEST 15 — UNREACHABLE drops the server from the queue. */
    @Test
    fun unreachableServerIsRemoved() = runBlocking {
        val probe = RecordingProbe { WireOutcome.REFUSED }

        val kept = probe(probe, target("127.0.0.1", AutoModeProtocol.SOFTETHER_TCP, openPort))

        assertTrue("a definitive failure may gate the server out", kept.isEmpty())
    }

    /** TEST 16 — UNKNOWN keeps it, and keeps the reason visible. */
    @Test
    fun unknownServerIsKept() = runBlocking {
        val probe = RecordingProbe { WireOutcome.SILENT }

        val kept = probe(probe, target("127.0.0.1", AutoModeProtocol.SOFTETHER_TCP, openPort))

        assertEquals(1, kept.size)
        assertFalse(
            "UNKNOWN is not reachable, and must not pretend to be",
            ServerReachabilityTester.isAutoConnectReachable("127.0.0.1", AutoModeProtocol.SOFTETHER_TCP),
        )
        assertEquals(
            AutoConnectReachabilityState.UNKNOWN,
            ServerReachabilityTester.autoConnectState("127.0.0.1", AutoModeProtocol.SOFTETHER_TCP),
        )
    }

    /**
     * TEST 17 — a server whose every protocol is UNKNOWN survives.
     *
     * This is the case that motivates the whole tri-state. Under a binary verdict,
     * "no protocol answered" is one value, and it has to mean either "usable" or
     * "drop it". Choose "drop it" and every server behind a firewall disappears;
     * choose "usable" and a dead server with one refused port stays forever.
     */
    @Test
    fun serverWithOnlyUnknownProtocolsSurvives() = runBlocking {
        val probe = RecordingProbe { WireOutcome.SILENT }
        val srv = candidate("127.0.0.1", seTcpPort = 9922, seUdpPort = 5555)

        val kept = probe(
            probe,
            ReachabilityTarget(srv, AutoModeProtocol.SOFTETHER_TCP, 9922),
            ReachabilityTarget(srv, AutoModeProtocol.SOFTETHER_UDP, 5555),
        )

        assertEquals(1, kept.size)
        listOf(AutoModeProtocol.SOFTETHER_TCP, AutoModeProtocol.SOFTETHER_UDP).forEach { p ->
            assertEquals(
                AutoConnectReachabilityState.UNKNOWN,
                ServerReachabilityTester.autoConnectState("127.0.0.1", p),
            )
        }
    }

    /** TEST 12 — an answer on the protocol's own transport yields an RTT for the ranking. */
    @Test
    fun answeredProbeYieldsAnRtt() = runBlocking {
        val probe = RecordingProbe { WireOutcome.ANSWERED }

        probe(probe, target("127.0.0.1", AutoModeProtocol.OPENVPN_UDP, openPort))

        val rtt = ServerReachabilityTester
            .autoConnectResult("127.0.0.1", AutoModeProtocol.OPENVPN_UDP)
            ?.rttIfReachable()
        assertEquals(25L, rtt)
    }

    // ---- Y3: two namespaces, two result types ----

    /**
     * TEST 19 — one IP, two questions, two result types.
     *
     * The Server List quick test is protocol-less and binary; Auto Connect is
     * per-protocol and tri-state. They must coexist for the same IP without
     * either overwriting the other, and the Server List's view of the store must
     * never contain an Auto Connect verdict.
     */
    @Test
    fun namespacesCoexistWithTheirOwnResultTypes() = runBlocking {
        val x = "127.0.0.1"
        probe(RecordingProbe { WireOutcome.SILENT }, target(x, AutoModeProtocol.OPENVPN_TCP, openPort))
        ServerReachabilityTester.testConnections(listOf(connection(x, openPort)), timeoutMs = 1_000)

        assertTrue(ServerReachabilityTester.isServerListReachable(x))
        assertFalse(ServerReachabilityTester.isAutoConnectReachable(x, AutoModeProtocol.OPENVPN_TCP))
        assertEquals(
            "one identity in each namespace",
            setOf<ReachabilityKey>(
                ReachabilityKey.ServerList(x),
                ReachabilityKey.AutoConnect(x, AutoModeProtocol.OPENVPN_TCP),
            ),
            ServerReachabilityTester.resultKeys,
        )
        assertEquals(
            setOf(ReachabilityKey.AutoConnect(x, AutoModeProtocol.OPENVPN_TCP)),
            ServerReachabilityTester.autoConnectResultKeys,
        )
        assertEquals(
            "the list view is built from the SERVER_LIST namespace only",
            setOf(ReachabilityKey.ServerList(x)),
            ServerReachabilityTester.serverListResultKeys,
        )
        assertEquals(
            "and it holds exactly the one binary quick-test verdict for this IP",
            mapOf(x to (openPort to true)),
            ServerReachabilityTester.serverListResultsMap().mapValues { it.value.port to it.value.isReachable },
        )
    }

    /**
     * TEST 20 — clears stay scoped, and the Server List stays binary.
     *
     * Refreshing the server list must not throw away what Auto Connect learned,
     * and the list must keep its protocol-less binary result rather than adopting
     * the tri-state — the two systems answer different questions (D11/Y5).
     */
    @Test
    fun clearsAreScopedAndTheServerListStaysBinary() = runBlocking {
        val x = "127.0.0.1"
        ServerReachabilityTester.testConnections(listOf(connection(x, openPort)), timeoutMs = 1_000)
        probe(RecordingProbe { WireOutcome.ANSWERED }, target(x, AutoModeProtocol.SOFTETHER_UDP, openPort))

        ServerReachabilityTester.clearServerList()
        assertFalse(ServerReachabilityTester.hasServerListResult(x))
        assertEquals(
            "clearing the list must not erase Auto Connect evidence",
            AutoConnectReachabilityState.REACHABLE,
            ServerReachabilityTester.autoConnectState(x, AutoModeProtocol.SOFTETHER_UDP),
        )

        // The mirror image: the whole Auto Connect namespace goes, the list stays.
        ServerReachabilityTester.testConnections(listOf(connection(x, openPort)), timeoutMs = 1_000)
        probe(RecordingProbe { WireOutcome.REFUSED }, target(x, AutoModeProtocol.SOFTETHER_TCP, openPort))
        ServerReachabilityTester.clearAutoConnect()
        assertTrue(
            "clearing Auto Connect removes every protocol verdict, not just the newest",
            ServerReachabilityTester.autoConnectResultKeys.isEmpty(),
        )
        assertTrue(
            "and it still does not touch the Server List",
            ServerReachabilityTester.isServerListReachable(x),
        )

        // The Server List still hands out a plain binary result with no protocol
        // and no tri-state, and asking for it by IP alone still works.
        val listResult: ReachabilityResult? = ServerReachabilityTester.serverListResult(x)
        assertNotNull(listResult)
        assertTrue(listResult!!.isReachable)
        assertEquals(openPort, listResult.port)
        assertEquals(
            "the list view stays protocol-less and binary: an IP key is the whole key, "
                + "and only the quick test ever writes it",
            setOf(x),
            ServerReachabilityTester.serverListResultsMap().keys,
        )
    }
}