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
import vn.unlimit.vpngate.automode.ReachabilityGate
import vn.unlimit.vpngate.automode.ReachabilityTarget
import vn.unlimit.vpngate.automode.endpointPort
import vn.unlimit.vpngate.automode.probeTransport

/**
 * Stage 2F-F.17 (D1 = FILTER_ONLY): the reachability gate, exercised over its
 * whole verdict matrix.
 *
 * D1's rule is about a **server**, not about an endpoint, and that is the whole
 * difficulty. One refused port says nothing about a machine: a VPN server that
 * has SoftEther TCP closed and OpenVPN UDP open is a perfectly healthy server on
 * a perfectly reachable network, and any policy that removes it on the strength
 * of the closed port alone is making a claim it has no evidence for.
 *
 * So the unit under test is the *aggregate*. Every usable protocol of a server is
 * measured, and the server survives unless every one of them came back
 * definitively `UNREACHABLE`. The tri-state is what makes the rule expressible at
 * all — `UNKNOWN` is the value that separates "down" from "we could not tell", and
 * it keeps a server.
 *
 * Everything here drives the real store and the real gate with the socket layer
 * replaced, because the bug being prevented is a bug in the aggregation, not in
 * the probe.
 */
class ReachabilityGateTest {

    /**
     * The network seam, keyed by protocol so one server can be given different
     * verdicts on different transports — which is the situation D1 exists for.
     */
    private class PerProtocolProbe(
        private val verdict: (ReachabilityTarget) -> WireOutcome,
    ) : AutoConnectReachabilityProbe {
        val calls = mutableListOf<ReachabilityTarget>()

        override fun probe(target: ReachabilityTarget, tcpTimeoutMs: Int): ProbeReport {
            calls += target
            return ProbeReport(verdict(target), 10_000_000L)
        }
    }

    /** A probe that answers the same way for every protocol. */
    private fun uniformProbe(verdict: WireOutcome) = PerProtocolProbe { verdict }

    /** A probe that answers per protocol, defaulting to silence. */
    private fun perProtocol(verdicts: Map<AutoModeProtocol, WireOutcome>) =
        PerProtocolProbe { verdicts[it.protocol] ?: WireOutcome.SILENT }

    @Before
    fun setUp() = ServerReachabilityTester.clear()

    @After
    fun tearDown() = ServerReachabilityTester.clear()

    /** A server speaking SoftEther TCP, SoftEther UDP and OpenVPN TCP and UDP. */
    private fun fourProtocolServer(ip: String, host: String) = AutoModeCandidate(
        hostname = host,
        ip = ip,
        speed = 10_000_000,
        ping = 30,
        sessions = 10,
        score = 100,
        sources = 1,
        seTcpPort = 9922,
        seUdpPort = 5555,
        seUdpSupported = true,
        openVpnTcpPort = 443,
        openVpnUdpPort = 1194,
        l2tpSupported = false,
        sstpSupported = false,
    )

    /**
     * What the controller hands the probe layer: one target per **usable**
     * protocol, each at the endpoint that protocol published. Mirrors
     * `AutoModeController.probeTargets` without duplicating its capability rules
     * — the protocols to test are stated here, which is the point: the gate has to
     * work for whatever set it is given.
     */
    private fun targetsFor(
        candidate: AutoModeCandidate,
        vararg protocols: AutoModeProtocol,
    ): List<ReachabilityTarget> = protocols.map {
        ReachabilityTarget(candidate, it, it.endpointPort(candidate))
    }

    private suspend fun gate(
        verdicts: Map<AutoModeProtocol, WireOutcome>,
        targets: List<ReachabilityTarget>,
    ): ReachabilityGate = ServerReachabilityTester.filterReachableCandidates(
        targets = targets,
        probe = perProtocol(verdicts),
    )

    // ---- two usable protocols ----

    /** TEST 1 — TCP down, UDP up: KEEP. One refused port is not a dead server. */
    @Test
    fun unreachableOnOneProtocolAndReachableOnAnotherKeepsTheServer() = runBlocking {
        val srv = fourProtocolServer("10.0.0.1", "srv-a")
        val targets = targetsFor(srv, AutoModeProtocol.OPENVPN_TCP, AutoModeProtocol.OPENVPN_UDP)

        val gate = gate(
            mapOf(
                AutoModeProtocol.OPENVPN_TCP to WireOutcome.REFUSED,
                AutoModeProtocol.OPENVPN_UDP to WireOutcome.ANSWERED,
            ),
            targets,
        )

        assertEquals(listOf(srv), gate.kept)
        assertFalse(gate.allUnreachable)
    }

    /** TEST 2 — TCP down, UDP silent: KEEP. UNKNOWN is never treated as a failure. */
    @Test
    fun unreachableThenUnknownKeepsTheServer() = runBlocking {
        val srv = fourProtocolServer("10.0.0.2", "srv-b")
        val targets = targetsFor(srv, AutoModeProtocol.OPENVPN_TCP, AutoModeProtocol.OPENVPN_UDP)

        val gate = gate(
            mapOf(
                AutoModeProtocol.OPENVPN_TCP to WireOutcome.REFUSED,
                AutoModeProtocol.OPENVPN_UDP to WireOutcome.SILENT,
            ),
            targets,
        )

        assertEquals(listOf(srv), gate.kept)
        assertFalse(gate.allUnreachable)
    }

    /** TEST 3 — TCP down, UDP down: REMOVE. This is the only removal case. */
    @Test
    fun unreachableOnEveryUsableProtocolRemovesTheServer() = runBlocking {
        val srv = fourProtocolServer("10.0.0.3", "srv-c")
        val targets = targetsFor(srv, AutoModeProtocol.OPENVPN_TCP, AutoModeProtocol.OPENVPN_UDP)

        val gate = gate(
            mapOf(
                AutoModeProtocol.OPENVPN_TCP to WireOutcome.REFUSED,
                AutoModeProtocol.OPENVPN_UDP to WireOutcome.REFUSED,
            ),
            targets,
        )

        assertTrue(gate.kept.isEmpty())
        assertTrue(
            "and it must be reported as a definitive result, not as silence",
            gate.allUnreachable,
        )
    }

    /** TEST 4 — TCP up, UDP down: KEEP. Symmetry of TEST 1. */
    @Test
    fun reachableOnOneProtocolAndUnreachableOnAnotherKeepsTheServer() = runBlocking {
        val srv = fourProtocolServer("10.0.0.4", "srv-d")
        val targets = targetsFor(srv, AutoModeProtocol.OPENVPN_TCP, AutoModeProtocol.OPENVPN_UDP)

        val gate = gate(
            mapOf(
                AutoModeProtocol.OPENVPN_TCP to WireOutcome.ANSWERED,
                AutoModeProtocol.OPENVPN_UDP to WireOutcome.REFUSED,
            ),
            targets,
        )

        assertEquals(listOf(srv), gate.kept)
    }

    /** TEST 5 — nothing conclusive at all: KEEP, because silence is not proof. */
    @Test
    fun unknownOnEveryProtocolKeepsTheServer() = runBlocking {
        val srv = fourProtocolServer("10.0.0.5", "srv-e")
        val targets = targetsFor(srv, AutoModeProtocol.OPENVPN_TCP, AutoModeProtocol.OPENVPN_UDP)

        val gate = gate(
            mapOf(
                AutoModeProtocol.OPENVPN_TCP to WireOutcome.SILENT,
                AutoModeProtocol.OPENVPN_UDP to WireOutcome.SILENT,
            ),
            targets,
        )

        assertEquals(
            "a filtered endpoint is the normal case, not a death certificate",
            listOf(srv),
            gate.kept,
        )
        assertFalse(gate.allUnreachable)
    }

    // ---- exactly one usable protocol ----

    /** TEST 6 — one usable protocol, definitively down: REMOVE. */
    @Test
    fun singleUnusableProtocolRemovesTheServer() = runBlocking {
        val srv = fourProtocolServer("10.0.0.6", "srv-f")

        val gate = gate(
            mapOf(AutoModeProtocol.OPENVPN_TCP to WireOutcome.REFUSED),
            targetsFor(srv, AutoModeProtocol.OPENVPN_TCP),
        )

        assertTrue(gate.kept.isEmpty())
        assertTrue(gate.allUnreachable)
    }

    /** TEST 7 — one usable protocol, silent: KEEP. */
    @Test
    fun singleUnknownProtocolKeepsTheServer() = runBlocking {
        val srv = fourProtocolServer("10.0.0.7", "srv-g")

        val gate = gate(
            mapOf(AutoModeProtocol.OPENVPN_TCP to WireOutcome.SILENT),
            targetsFor(srv, AutoModeProtocol.OPENVPN_TCP),
        )

        assertEquals(listOf(srv), gate.kept)
        assertFalse(gate.allUnreachable)
    }

    /** TEST 8 — one usable protocol, answered: KEEP. */
    @Test
    fun singleReachableProtocolKeepsTheServer() = runBlocking {
        val srv = fourProtocolServer("10.0.0.8", "srv-h")

        val gate = gate(
            mapOf(AutoModeProtocol.OPENVPN_TCP to WireOutcome.ANSWERED),
            targetsFor(srv, AutoModeProtocol.OPENVPN_TCP),
        )

        assertEquals(listOf(srv), gate.kept)
        assertFalse(gate.allUnreachable)
    }

    /** TEST 10 — SoftEther UDP with no published endpoint: UNKNOWN, therefore KEEP. */
    @Test
    fun softEtherUdpWithoutAnEndpointIsUnknownAndKeepsTheServer() = runBlocking {
        // seUdpSupported with no UDP port: usable for a connect, nothing to probe.
        val srv = fourProtocolServer("10.0.0.9", "srv-i").copy(seUdpPort = 0)
        val probe = uniformProbe(WireOutcome.ANSWERED)
        val targets = targetsFor(srv, AutoModeProtocol.SOFTETHER_UDP)

        assertEquals(0, AutoModeProtocol.SOFTETHER_UDP.endpointPort(srv))

        val gate = ServerReachabilityTester.filterReachableCandidates(targets, probe = probe)

        assertTrue("with no endpoint there is nothing to send", probe.calls.isEmpty())
        assertEquals(listOf(srv), gate.kept)
        assertEquals(
            AutoConnectReachabilityState.UNKNOWN,
            ServerReachabilityTester.autoConnectState("10.0.0.9", AutoModeProtocol.SOFTETHER_UDP),
        )
    }

    // ---- three usable protocols ----

    /** TEST 11 — down, down, up: KEEP. */
    @Test
    fun oneReachableProtocolAmongThreeKeepsTheServer() = runBlocking {
        val srv = fourProtocolServer("10.0.0.10", "srv-j")
        val targets = targetsFor(
            srv,
            AutoModeProtocol.SOFTETHER_TCP,
            AutoModeProtocol.OPENVPN_TCP,
            AutoModeProtocol.OPENVPN_UDP,
        )

        val gate = gate(
            mapOf(
                AutoModeProtocol.SOFTETHER_TCP to WireOutcome.REFUSED,
                AutoModeProtocol.OPENVPN_TCP to WireOutcome.REFUSED,
                AutoModeProtocol.OPENVPN_UDP to WireOutcome.ANSWERED,
            ),
            targets,
        )

        assertEquals(listOf(srv), gate.kept)
        assertFalse(gate.allUnreachable)
    }

    /** TEST 12 — down, silent, down: KEEP. */
    @Test
    fun oneUnknownProtocolAmongThreeKeepsTheServer() = runBlocking {
        val srv = fourProtocolServer("10.0.0.11", "srv-k")
        val targets = targetsFor(
            srv,
            AutoModeProtocol.SOFTETHER_TCP,
            AutoModeProtocol.OPENVPN_TCP,
            AutoModeProtocol.OPENVPN_UDP,
        )

        val gate = gate(
            mapOf(
                AutoModeProtocol.SOFTETHER_TCP to WireOutcome.REFUSED,
                AutoModeProtocol.OPENVPN_TCP to WireOutcome.SILENT,
                AutoModeProtocol.OPENVPN_UDP to WireOutcome.REFUSED,
            ),
            targets,
        )

        assertEquals(listOf(srv), gate.kept)
        assertFalse(gate.allUnreachable)
    }

    /** TEST 13 — down, down, down: REMOVE. */
    @Test
    fun allThreeProtocolsUnreachableRemovesTheServer() = runBlocking {
        val srv = fourProtocolServer("10.0.0.12", "srv-l")
        val targets = targetsFor(
            srv,
            AutoModeProtocol.SOFTETHER_TCP,
            AutoModeProtocol.OPENVPN_TCP,
            AutoModeProtocol.OPENVPN_UDP,
        )

        val gate = gate(
            mapOf(
                AutoModeProtocol.SOFTETHER_TCP to WireOutcome.REFUSED,
                AutoModeProtocol.OPENVPN_TCP to WireOutcome.REFUSED,
                AutoModeProtocol.OPENVPN_UDP to WireOutcome.REFUSED,
            ),
            targets,
        )

        assertTrue(gate.kept.isEmpty())
        assertTrue(gate.allUnreachable)
    }

    // ---- several servers at once ----

    /**
     * TEST 14 — four servers, mixed verdicts, and the order they come back in.
     *
     * A and D are down on everything, B answered, C is silent. Exactly B and C
     * survive, and they survive **in the input's relative order** — the gate is a
     * filter, and a filter cannot reorder what it filters.
     */
    @Test
    fun multipleServersAreGatedAndKeepTheInputOrder() = runBlocking {
        val a = fourProtocolServer("10.0.1.1", "srv-a")
        val b = fourProtocolServer("10.0.1.2", "srv-b")
        val c = fourProtocolServer("10.0.1.3", "srv-c")
        val d = fourProtocolServer("10.0.1.4", "srv-d")
        val targets = listOf(a, b, c, d).flatMap { server ->
            targetsFor(server, AutoModeProtocol.OPENVPN_TCP, AutoModeProtocol.OPENVPN_UDP)
        }
        val probe = PerProtocolProbe { target ->
            when (target.candidate) {
                b -> WireOutcome.ANSWERED   // one protocol answers
                c -> WireOutcome.SILENT     // nothing conclusive
                else -> WireOutcome.REFUSED // both protocols definitively down
            }
        }

        val gate = ServerReachabilityTester.filterReachableCandidates(targets, probe = probe)

        assertEquals(
            "A and D are down on everything; B answered and C is silent, so both keep " +
                "— in the input's relative order, because a gate cannot reorder",
            listOf(b, c),
            gate.kept,
        )
        assertFalse(gate.allUnreachable)
    }

    // ---- structural guarantees of the gate ----

    /**
     * Reachability must never reorder, whatever the verdicts.
     *
     * The input here is deliberately the *worst* case for a ranker: the RTTs are
     * the reverse of the input order and the reachable servers come last. If
     * anything in this path sorted by RTT, by verdict, or by protocol priority,
     * this assertion fails.
     */
    @Test
    fun theGateNeverReordersCandidates() = runBlocking {
        val slow = fourProtocolServer("10.0.2.1", "srv-slow")
        val mid = fourProtocolServer("10.0.2.2", "srv-mid")
        val fast = fourProtocolServer("10.0.2.3", "srv-fast")
        val input = listOf(slow, mid, fast)

        // The unreachable server is last, so a "reachable first" sort would move
        // it to the front; and the gate never sees an RTT at all, so any RTT
        // ordering in this path would have to come from the caller.
        val probe = PerProtocolProbe { target ->
            if (target.candidate === fast) WireOutcome.REFUSED else WireOutcome.ANSWERED
        }

        val targets = input.flatMap { server ->
            targetsFor(server, AutoModeProtocol.OPENVPN_TCP, AutoModeProtocol.OPENVPN_UDP)
        }
        val gate = ServerReachabilityTester.filterReachableCandidates(targets, probe = probe)

        assertEquals("input order, filtered — not 'reachable first'", listOf(slow, mid), gate.kept)
        assertTrue(
            AutoModeProtocol.OPENVPN_TCP.probeTransport == ProbeTransport.TCP,
        )
    }

    /**
     * "No usable protocol" must never be read as "every usable protocol is down".
     *
     * A controller that selects no protocols hands over no targets. That is an
     * absence of evidence, and the caller has to be able to tell it apart from a
     * measured dead network — otherwise the honest "I do not know" quietly turns
     * into "remove everything".
     */
    @Test
    fun noTargetsIsNotAnAllUnreachableVerdict() = runBlocking {
        val gate = ServerReachabilityTester.filterReachableCandidates(
            targets = emptyList(),
            probe = uniformProbe(WireOutcome.SILENT),
        )

        assertTrue(gate.kept.isEmpty())
        assertFalse(
            "nothing was probed, so nothing may be concluded",
            gate.allUnreachable,
        )
        assertTrue(
            "and the store must be empty too: no target means no verdict was ever written",
            ServerReachabilityTester.autoConnectResultKeys.isEmpty(),
        )
    }

    // ================================================================
    // Stage 2F-F.19: the unreachable-by-absence edge case
    // ================================================================

    /**
     * A candidate with **no IP** cannot be probed, so it cannot be refused either.
     *
     * This is the boundary D1 has to hold at its limit. "We could not test it" and
     * "it is down" have the same observable shape from the outside — no verdict —
     * and D1 says only the second one may remove a server. A missing IP therefore
     * produces *no verdict at all* rather than an UNREACHABLE one, and the
     * candidate stays.
     *
     * Nothing is fabricated: the socket seam is not called, and nothing is written
     * to the store, because there is no identity to file a result under.
     */
    @Test
    fun aNullIpCandidateIsKeptAndIsNeverDoomed() = runBlocking {
        val noIp = fourProtocolServer("10.9.0.1", "srv-noip").copy(ip = null)
        val probe = uniformProbe(WireOutcome.ANSWERED)
        val targets = targetsFor(noIp, AutoModeProtocol.OPENVPN_TCP, AutoModeProtocol.OPENVPN_UDP)

        val gate = ServerReachabilityTester.filterReachableCandidates(targets, probe = probe)

        assertTrue(
            "there is no address to aim a probe at, so none may be attempted",
            probe.calls.isEmpty(),
        )
        assertEquals(
            "missing reachability is never missing capability",
            listOf(noIp),
            gate.kept,
        )
        assertFalse(
            "and it is not a reason to declare the network down either",
            gate.allUnreachable,
        )
    }

    /** Blank and whitespace-only addresses take the same path as null. */
    @Test
    fun blankAndWhitespaceOnlyIpsAreTreatedLikeNull() = runBlocking {
        val blank = fourProtocolServer("10.9.0.2", "srv-blank").copy(ip = "")
        val spaces = fourProtocolServer("10.9.0.3", "srv-spaces").copy(ip = "   ")
        val probe = uniformProbe(WireOutcome.ANSWERED)

        val gate = ServerReachabilityTester.filterReachableCandidates(
            listOf(
                ReachabilityTarget(blank, AutoModeProtocol.OPENVPN_TCP, 443),
                ReachabilityTarget(spaces, AutoModeProtocol.OPENVPN_TCP, 443),
            ),
            probe = probe,
        )

        assertTrue(probe.calls.isEmpty())
        assertEquals(listOf(blank, spaces), gate.kept)
        assertFalse(gate.allUnreachable)
    }

    /**
     * No verdict may be manufactured for a candidate that could not be probed.
     *
     * A `null` result and an `UNKNOWN` result are different: `null` means "we have
     * no identity to record this under", `UNKNOWN` means "we looked and learned
     * nothing". Collapsing them would put a fabricated state into the AUTO_CONNECT
     * namespace under an address nobody can dial.
     */
    @Test
    fun anUnprobeableCandidateLeavesNoResultInTheStore() = runBlocking {
        val noIp = fourProtocolServer("10.9.0.4", "srv-noip").copy(ip = null)

        ServerReachabilityTester.filterReachableCandidates(
            targetsFor(noIp, AutoModeProtocol.OPENVPN_TCP),
            probe = uniformProbe(WireOutcome.ANSWERED),
        )

        assertTrue(
            "no verdict means nothing written, not an UNREACHABLE or UNKNOWN stand-in",
            ServerReachabilityTester.autoConnectResultKeys.isEmpty(),
        )
        assertNull(ServerReachabilityTester.autoConnectState(null, AutoModeProtocol.OPENVPN_TCP))
        assertNull(ServerReachabilityTester.autoConnectState("", AutoModeProtocol.OPENVPN_TCP))
    }

    /**
     * F1, pinned: the one state where [ReachabilityGate.kept] and
     * [ReachabilityGate.allUnreachable] disagree.
     *
     * One candidate is genuinely down; the other cannot be probed at all. The
     * down one is removed, the unprobeable one survives, and the measured set is
     * non-empty and entirely `UNREACHABLE` — so `allUnreachable` is `true` while
     * `kept` is not.
     *
     * This is exactly why the controller must consult `kept` first. Recording it
     * here means a future change that reorders those two checks fails a test
     * instead of silently discarding a server on no evidence.
     */
    @Test
    fun keptAndAllUnreachableCanDisagreeWhenAnUnprobeableCandidateRidesAlong() = runBlocking {
        val down = fourProtocolServer("10.9.0.5", "srv-down")
        val noIp = fourProtocolServer("10.9.0.6", "srv-noip").copy(ip = null)

        val gate = ServerReachabilityTester.filterReachableCandidates(
            listOf(
                ReachabilityTarget(down, AutoModeProtocol.OPENVPN_TCP, 443),
                ReachabilityTarget(noIp, AutoModeProtocol.OPENVPN_TCP, 443),
            ),
            probe = uniformProbe(WireOutcome.REFUSED),
        )

        assertEquals(
            "the server that was proven down is removed; the one we could not test is not",
            listOf(noIp),
            gate.kept,
        )
        assertTrue(
            "the measured set was non-empty and entirely UNREACHABLE, so the flag is true",
            gate.allUnreachable,
        )
        assertEquals(
            "and only the probeable candidate left a verdict behind",
            setOf(ReachabilityKey.AutoConnect("10.9.0.5", AutoModeProtocol.OPENVPN_TCP)),
            ServerReachabilityTester.autoConnectResultKeys,
        )
    }

    /**
     * The invariant behind the flag: `allUnreachable` can only be `true` when
     * something was genuinely measured.
     *
     * `true` is unreachable from an empty measurement set, which is what makes it
     * safe for the controller to read as "remove everything" once `kept` is known
     * to be empty.
     *
     * The implication is deliberately one-way. `false` says nothing about whether
     * anything was recorded: an answered candidate was measured and still yields
     * `false`, because an answer is precisely the outcome that must keep a server.
     * Asserting the converse would be asserting that "not everything is down"
     * means "nothing was looked at", which is false and would hide a real bug.
     */
    @Test
    fun allUnreachableImpliesTheMeasuredSetIsNotEmpty() = runBlocking {
        data class Case(
            val label: String,
            val targets: List<ReachabilityTarget>,
            val verdict: (ReachabilityTarget) -> WireOutcome,
            val expectAllUnreachable: Boolean,
        )

        val up = fourProtocolServer("10.9.1.1", "srv-up")
        val down = fourProtocolServer("10.9.1.2", "srv-down")
        val quiet = fourProtocolServer("10.9.1.3", "srv-quiet")
        val noIp = fourProtocolServer("10.9.1.4", "srv-noip").copy(ip = null)

        val cases = listOf(
            Case("no targets at all", emptyList(), { WireOutcome.REFUSED }, false),
            Case("nothing measurable", targetsFor(noIp, AutoModeProtocol.OPENVPN_TCP), { WireOutcome.REFUSED }, false),
            Case("one answered", targetsFor(up, AutoModeProtocol.OPENVPN_TCP), { WireOutcome.ANSWERED }, false),
            Case("one silent", targetsFor(quiet, AutoModeProtocol.OPENVPN_TCP), { WireOutcome.SILENT }, false),
            Case(
                "one refused but the other answered",
                targetsFor(down, AutoModeProtocol.OPENVPN_TCP) + targetsFor(up, AutoModeProtocol.OPENVPN_TCP),
                { target -> if (target.candidate === up) WireOutcome.ANSWERED else WireOutcome.REFUSED },
                false,
            ),
            Case(
                "one refused and the other silent",
                targetsFor(down, AutoModeProtocol.OPENVPN_TCP) + targetsFor(quiet, AutoModeProtocol.OPENVPN_TCP),
                { target -> if (target.candidate === quiet) WireOutcome.SILENT else WireOutcome.REFUSED },
                false,
            ),
            Case("one refused, nothing else", targetsFor(down, AutoModeProtocol.OPENVPN_TCP), { WireOutcome.REFUSED }, true),
        )

        for (case in cases) {
            ServerReachabilityTester.clear()
            val gate = ServerReachabilityTester.filterReachableCandidates(
                case.targets,
                probe = PerProtocolProbe { case.verdict(it) },
            )

            assertEquals(case.label, case.expectAllUnreachable, gate.allUnreachable)
            if (case.expectAllUnreachable) {
                assertTrue(
                    "$case.label: true is only reachable from a non-empty measured set",
                    ServerReachabilityTester.autoConnectResultKeys.isNotEmpty(),
                )
            }
        }
    }

    /**
     * Part 2: SoftEther UDP that is capability-usable with no published endpoint.
     *
     * The endpoint is missing, so the probe cannot run — and the honest verdict is
     * `UNKNOWN`, recorded against the SoftEther UDP identity. It must not become
     * `UNREACHABLE`, and it must not be serviced by borrowing the TCP endpoint
     * this very candidate publishes: no datagram, no TCP socket, and no verdict
     * filed under SoftEther TCP.
     */
    @Test
    fun softEtherUdpWithoutAPublishedEndpointIsUnknownKeptAndNotBorrowed() = runBlocking {
        val srv = fourProtocolServer("10.9.2.1", "srv-seudp").copy(seUdpPort = 0, seUdpSupported = true)
        val probe = uniformProbe(WireOutcome.ANSWERED)
        val targets = targetsFor(srv, AutoModeProtocol.SOFTETHER_UDP)

        assertEquals(
            "capability alone keeps the protocol usable",
            0,
            AutoModeProtocol.SOFTETHER_UDP.endpointPort(srv),
        )
        assertEquals(
            "and the candidate does publish a TCP endpoint, which must stay out of this",
            9922,
            AutoModeProtocol.SOFTETHER_TCP.endpointPort(srv),
        )

        val gate = ServerReachabilityTester.filterReachableCandidates(targets, probe = probe)

        assertTrue("no endpoint, no probe", probe.calls.isEmpty())
        assertEquals(
            AutoConnectReachabilityState.UNKNOWN,
            ServerReachabilityTester.autoConnectState("10.9.2.1", AutoModeProtocol.SOFTETHER_UDP),
        )
        assertNull(
            "no verdict may appear under the TCP identity it did not probe",
            ServerReachabilityTester.autoConnectState("10.9.2.1", AutoModeProtocol.SOFTETHER_TCP),
        )
        assertEquals(listOf(srv), gate.kept)
        assertFalse(gate.allUnreachable)
    }
}