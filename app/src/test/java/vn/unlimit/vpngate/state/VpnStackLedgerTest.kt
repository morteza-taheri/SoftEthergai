package vn.unlimit.vpngate.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A-3 (v1.0.11 audit §3.3): the protocol-reconciliation decision.
 *
 * These target [VpnStackLedger] — the pure fold the tracker delegates to —
 * rather than [GlobalVpnTracker] itself. `GlobalVpnTracker` is a Kotlin
 * `object` whose initialiser needs `Handler(Looper.getMainLooper())`, so it
 * cannot be class-loaded in a plain JVM test; the reconciliation is therefore
 * deliberately modelled as a stateless function over plain data
 * ([VpnStackReport] / [GlobalVpnState] carry no Android types), which makes the
 * whole §3.3 contract exercisable here.
 *
 * The defect under test: `GlobalVpnState` is a single slot but two stacks
 * publish into it, so every terminal branch guarded itself with
 * `if (_vpnState.value.protocol == "<me>")` and silently *dropped* the event
 * otherwise. Dropping a stale teardown is fine; dropping the teardown of the
 * stack that IS published — or having no answer at all when two stacks are
 * legitimately live — is not. A DISCONNECTED for stack X must clear X's stale
 * CONNECTED and must not clobber stack Y.
 */
class VpnStackLedgerTest {

    // ── helpers ───────────────────────────────────────────────────────

    private fun connected(
        protocol: String,
        host: String? = null,
        ip: String? = null,
        assigned: String? = null,
    ) = GlobalVpnState(
        status = VpnConnectionStatus.CONNECTED,
        protocol = protocol,
        serverHost = host,
        serverIp = ip,
        assignedIp = assigned,
    )

    private fun event(
        status: VpnConnectionStatus,
        protocol: String?,
        host: String? = null,
        ip: String? = null,
        assigned: String? = null,
        errorMessage: String? = null,
    ) = VpnStackEvent(
        status = status,
        protocol = protocol,
        serverHost = host,
        serverIp = ip,
        assignedIp = assigned,
        errorMessage = errorMessage,
    )

    private fun report(
        protocol: String,
        host: String? = null,
        ip: String? = null,
        assigned: String? = null,
    ) = VpnStackReport(protocol = protocol, serverHost = host, serverIp = ip, assignedIp = assigned)

    /** Fold a sequence of events, starting from an empty ledger / idle state. */
    private fun fold(events: List<VpnStackEvent>): GlobalVpnState? {
        var live: List<VpnStackReport> = emptyList()
        var current = GlobalVpnState()
        var published: GlobalVpnState? = null
        for (e in events) {
            val verdict = VpnStackLedger.reconcile(live, current, e)
            live = verdict.live
            verdict.publish?.let {
                current = it
                published = it
            }
        }
        return published
    }

    private fun List<VpnStackReport>.onlyProtocol() = single().protocol

    // ── single-stack behaviour must not regress ────────────────────────

    @Test
    fun `a disconnected for the published stack clears the slot`() {
        val live = listOf(report("SoftEther", host = "se.example", assigned = "10.1.1.2"))
        val current = connected("SoftEther", host = "se.example", assigned = "10.1.1.2")

        val verdict = VpnStackLedger.reconcile(
            live = live,
            current = current,
            event = event(VpnConnectionStatus.DISCONNECTED, protocol = "SoftEther"),
        )

        assertTrue("the stack must leave the ledger", verdict.live.isEmpty())
        assertEquals(VpnConnectionStatus.DISCONNECTED, verdict.publish?.status)
        assertNull("a teardown must blank the identity", verdict.publish?.protocol)
        assertNull(verdict.publish?.serverHost)
        assertNull(verdict.publish?.assignedIp)
    }

    @Test
    fun `an error for the published stack keeps its identity`() {
        val live = listOf(report("OpenVPN", host = "ovpn.example", ip = "1.2.3.4"))
        val current = connected("OpenVPN", host = "ovpn.example", ip = "1.2.3.4")

        val verdict = VpnStackLedger.reconcile(
            live = live,
            current = current,
            event = event(VpnConnectionStatus.ERROR, protocol = "OpenVPN", errorMessage = "Auth failed"),
        )

        assertTrue(verdict.live.isEmpty())
        assertEquals(VpnConnectionStatus.ERROR, verdict.publish?.status)
        assertEquals("OpenVPN", verdict.publish?.protocol)
        assertEquals("ovpn.example", verdict.publish?.serverHost)
        assertEquals("1.2.3.4", verdict.publish?.serverIp)
        assertEquals("Auth failed", verdict.publish?.errorMessage)
    }

    @Test
    fun `connecting and disconnecting are passthrough and keep the stack live`() {
        val live = listOf(report("SoftEther", host = "se.example"))

        for (status in listOf(VpnConnectionStatus.CONNECTING, VpnConnectionStatus.DISCONNECTING)) {
            val verdict = VpnStackLedger.reconcile(
                live = live,
                current = connected("SoftEther", host = "se.example"),
                event = event(status, protocol = "SoftEther"),
            )

            assertEquals("$status must not retire the stack", live, verdict.live)
            assertEquals(status, verdict.publish?.status)
            assertEquals("SoftEther", verdict.publish?.protocol)
            assertEquals("se.example", verdict.publish?.serverHost)
        }
    }

    @Test
    fun `a reconnected stack refreshes its record instead of duplicating it`() {
        val first = VpnStackLedger.reconcile(
            live = emptyList(),
            current = GlobalVpnState(),
            event = event(VpnConnectionStatus.CONNECTED, "SoftEther", host = "se.example"),
        )
        val second = VpnStackLedger.reconcile(
            live = first.live,
            current = first.publish!!,
            event = event(VpnConnectionStatus.CONNECTED, "SoftEther", assigned = "10.9.9.9"),
        )

        assertEquals("one stack, one ledger row", 1, second.live.size)
        assertEquals("SoftEther", second.live.onlyProtocol())
        assertEquals("the host survives a refresh that did not repeat it", "se.example", second.live.single().serverHost)
        assertEquals("the new address is recorded", "10.9.9.9", second.live.single().assignedIp)
    }

    // ── the A-3 requirement: clear X, do not clobber Y ─────────────────

    @Test
    fun `a disconnected for one stack republishes the surviving stack`() {
        // Both stacks are live; OpenVPN is the one currently published.
        val live = listOf(
            report("OpenVPN", host = "ovpn.example", ip = "1.2.3.4", assigned = "10.0.0.2"),
            report("SoftEther", host = "se.example", assigned = "10.1.1.2"),
        )
        val current = connected("SoftEther", host = "se.example", assigned = "10.1.1.2")

        val verdict = VpnStackLedger.reconcile(
            live = live,
            current = current,
            event = event(VpnConnectionStatus.DISCONNECTED, protocol = "SoftEther"),
        )

        assertEquals("only SoftEther left the ledger", listOf("OpenVPN"), verdict.live.map { it.protocol })
        val published = verdict.publish!!
        assertEquals(
            "a survivor must be republished as CONNECTED, never as a teardown",
            VpnConnectionStatus.CONNECTED,
            published.status,
        )
        assertEquals("OpenVPN", published.protocol)
        assertEquals(
            "the survivor carries its OWN identity, not the dead stack's",
            "ovpn.example",
            published.serverHost,
        )
        assertEquals("1.2.3.4", published.serverIp)
        assertEquals("10.0.0.2", published.assignedIp)
        assertNull("a live tunnel is not an error", published.errorMessage)
    }

    @Test
    fun `an error for one stack also republishes the surviving stack`() {
        val live = listOf(
            report("OpenVPN", host = "ovpn.example", ip = "1.2.3.4"),
            report("SoftEther", host = "se.example"),
        )
        val current = connected("SoftEther", host = "se.example")

        val verdict = VpnStackLedger.reconcile(
            live = live,
            current = current,
            event = event(VpnConnectionStatus.ERROR, protocol = "SoftEther", errorMessage = "SoftEther error"),
        )

        assertEquals(VpnConnectionStatus.CONNECTED, verdict.publish?.status)
        assertEquals("OpenVPN", verdict.publish?.protocol)
        assertEquals("the error message must not leak onto a live tunnel", null, verdict.publish?.errorMessage)
    }

    @Test
    fun `a teardown of the last survivor then clears the slot`() {
        val live = listOf(report("OpenVPN", host = "ovpn.example"))
        val current = connected("OpenVPN", host = "ovpn.example")

        val verdict = VpnStackLedger.reconcile(
            live = live,
            current = current,
            event = event(VpnConnectionStatus.DISCONNECTED, protocol = "OpenVPN"),
        )

        assertTrue(verdict.live.isEmpty())
        assertEquals(VpnConnectionStatus.DISCONNECTED, verdict.publish?.status)
        assertNull(verdict.publish?.protocol)
    }

    @Test
    fun `the published survivor is the most recently connected stack`() {
        // OpenVPN came up first, then SoftEther took the slot. When OpenVPN
        // dies, SoftEther — not OpenVPN — must be republished.
        val published = fold(
            listOf(
                event(VpnConnectionStatus.CONNECTED, "OpenVPN", host = "ovpn.example", ip = "1.2.3.4"),
                event(VpnConnectionStatus.CONNECTED, "SoftEther", host = "se.example", assigned = "10.1.1.2"),
                event(VpnConnectionStatus.DISCONNECTED, "OpenVPN"),
            ),
        )

        assertEquals("the survivor takes over the slot", VpnConnectionStatus.CONNECTED, published?.status)
        assertEquals("SoftEther", published?.protocol)
        assertEquals("se.example", published?.serverHost)
    }

    // ── stale events: accounted for, but never published ───────────────

    @Test
    fun `a stale teardown from an unowned stack leaves the published state alone`() {
        // OpenVPN is still CONNECTING (never CONNECTED, so not in the ledger)
        // when a leftover SoftEther teardown arrives.
        val verdict = VpnStackLedger.reconcile(
            live = emptyList(),
            current = GlobalVpnState(status = VpnConnectionStatus.CONNECTING, protocol = "OpenVPN"),
            event = event(VpnConnectionStatus.DISCONNECTED, protocol = "SoftEther"),
        )

        assertTrue("the stale stack leaves no ledger row", verdict.live.isEmpty())
        assertNull(
            "a teardown from a stack that owns nothing must not be published",
            verdict.publish,
        )
    }

    @Test
    fun `a stale teardown never clears a live survivor either`() {
        val live = listOf(report("OpenVPN", host = "ovpn.example", ip = "1.2.3.4"))
        val current = connected("OpenVPN", host = "ovpn.example", ip = "1.2.3.4")

        val verdict = VpnStackLedger.reconcile(
            live = live,
            current = current,
            event = event(VpnConnectionStatus.DISCONNECTED, protocol = "SoftEther"),
        )

        assertEquals(listOf("OpenVPN"), verdict.live.map { it.protocol })
        assertEquals(VpnConnectionStatus.CONNECTED, verdict.publish?.status)
        assertEquals("OpenVPN", verdict.publish?.protocol)
    }

    @Test
    fun `an event without a protocol is unattributable and changes nothing`() {
        val live = listOf(report("OpenVPN", host = "ovpn.example"))
        val current = connected("OpenVPN", host = "ovpn.example")

        for (status in VpnConnectionStatus.entries) {
            val verdict = VpnStackLedger.reconcile(live, current, event(status, protocol = null))
            assertEquals("$status must not move the ledger", live, verdict.live)
            assertNull("$status must not be published without attribution", verdict.publish)
        }
    }

    // ── purity of the fold ─────────────────────────────────────────────

    @Test
    fun `reconcile does not mutate its inputs`() {
        val live = listOf(report("OpenVPN", host = "ovpn.example"), report("SoftEther", host = "se.example"))
        val snapshot = live.toList()
        val current = connected("SoftEther", host = "se.example")

        val verdict = VpnStackLedger.reconcile(
            live = live,
            current = current,
            event = event(VpnConnectionStatus.DISCONNECTED, protocol = "SoftEther"),
        )

        assertEquals("the caller's ledger list must be untouched", snapshot, live)
        assertEquals("the caller's current state must be untouched", "SoftEther", current.protocol)
        assertEquals(VpnConnectionStatus.CONNECTED, current.status)
        assertTrue(
            "the verdict must be a fresh list, not the caller's",
            verdict.live !== live,
        )
    }

    @Test
    fun `a connected event for a new stack takes the slot`() {
        val verdict = VpnStackLedger.reconcile(
            live = emptyList(),
            current = GlobalVpnState(),
            event = event(
                VpnConnectionStatus.CONNECTED,
                "SoftEther",
                host = "se.example",
                ip = "5.6.7.8",
                assigned = "10.1.1.2",
            ),
        )

        assertEquals(1, verdict.live.size)
        val published = verdict.publish!!
        assertEquals(VpnConnectionStatus.CONNECTED, published.status)
        assertEquals("SoftEther", published.protocol)
        assertEquals("se.example", published.serverHost)
        assertEquals("5.6.7.8", published.serverIp)
        assertEquals("10.1.1.2", published.assignedIp)
        assertNull("a fresh connection clears any earlier error message", published.errorMessage)
    }
}
