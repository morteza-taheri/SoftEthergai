package vn.unlimit.vpngate.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.automode.AutoModeProtocol
import vn.unlimit.vpngate.automode.AutoModeState
import vn.unlimit.vpngate.state.VpnConnectionStatus

/**
 * AUTOCONNECT-DEVICE-BUGS-FIX (spec §1.6.32), T-NEW-28 — the rule that decides
 * what the Auto Mode hero button shows.
 *
 * BUG-1 on the device was a *display* bug: the button rendered from
 * [AutoModeState] alone, and that state is written only by
 * `AutoModeController.setState`, so a tunnel established outside an Auto Mode
 * run left the button reading "Disconnected" while the tunnel was up. The tap
 * then hit the D-4 refusal (`[AUTO] start refused: a tunnel is already active`)
 * and nothing happened.
 *
 * [combineAutoModeState] is the whole fix, expressed as a pure function so it
 * can be exercised here: no Android, no coroutines, no tracker. `VpnConnectionStatus`
 * is a plain enum, so referencing it does not initialise `GlobalVpnTracker` (whose
 * object initialiser needs `Looper.getMainLooper()`).
 */
class AutoModeDisplayStateTest {

    private val runConnected = AutoModeState.Connected(
        hostname = "vpn12345.opengw.net",
        ip = "203.0.113.7",
        protocol = AutoModeProtocol.SOFTETHER_TCP,
        speed = 12_500_000L,
        ping = 42,
    )

    private val runConnecting = AutoModeState.Connecting(
        hostname = "vpn12345.opengw.net",
        ip = "203.0.113.7",
        protocol = AutoModeProtocol.OPENVPN_TCP,
        speed = 0L,
        ping = 0,
        attempt = 2,
        total = 30,
    )

    // ── T-NEW-28a: the quiet case is unchanged ──────────────────────────

    @Test
    fun `disconnected run and no tunnel stays Disconnected`() {
        val out = combineAutoModeState(AutoModeState.Disconnected, VpnConnectionStatus.DISCONNECTED)

        assertSame(AutoModeState.Disconnected, out.display)
        assertFalse("no tunnel is up, so the button must not read Connected", out.isExternal)
        assertFalse("there is nothing to disconnect", out.tapMeansDisconnect)
        assertEquals(VpnConnectionStatus.DISCONNECTED, out.tunnelStatus)
    }

    // ── T-NEW-28b: BUG-1 itself — an external tunnel must show ──────────

    @Test
    fun `external tunnel while the run is Disconnected shows Connected`() {
        val out = combineAutoModeState(AutoModeState.Disconnected, VpnConnectionStatus.CONNECTED)

        assertTrue("BUG-1: a live tunnel must not render as Disconnected", out.display is AutoModeState.Connected)
        assertTrue("the screen marks this as not-ours", out.isExternal)
        assertTrue("Connected + tap means disconnect", out.tapMeansDisconnect)

        // The run state is preserved untouched so the log, the error card and
        // "Try next server" keep reading the Auto Mode truth.
        assertSame(AutoModeState.Disconnected, out.modeState)

        // The synthesized state must not invent a server identity: the tracker
        // knows the host but this type is not the place to copy it from.
        val shown = out.display as AutoModeState.Connected
        assertNull("no fabricated hostname", shown.hostname)
        assertNull("no fabricated ip", shown.ip)
    }

    // ── T-NEW-28c: a tunnel still coming up is "Connecting", not "idle" ──

    @Test
    fun `external tunnel while the run is Disconnected shows Connecting`() {
        val out = combineAutoModeState(AutoModeState.Disconnected, VpnConnectionStatus.CONNECTING)

        assertTrue(out.display is AutoModeState.Connecting)
        assertTrue(out.isExternal)
        assertTrue("tap while connecting means stop", out.tapMeansDisconnect)
    }

    // ── T-NEW-28d: teardown is not "connected" ──────────────────────────

    @Test
    fun `a tunnel being torn down is not shown as Connected`() {
        val out = combineAutoModeState(AutoModeState.Disconnected, VpnConnectionStatus.DISCONNECTING)

        assertSame(AutoModeState.Disconnected, out.display)
        assertFalse(out.isExternal)
        assertFalse("nothing is up, so a tap must start a run", out.tapMeansDisconnect)
    }

    // ── T-NEW-28e: the run owns the display while it is Connected ───────

    @Test
    fun `run Connected is shown even when the tracker has not caught up`() {
        val out = combineAutoModeState(runConnected, VpnConnectionStatus.DISCONNECTED)

        assertSame("the run's own Connected is the truth here", runConnected, out.display)
        assertFalse("this is our tunnel, not an external one", out.isExternal)
        assertTrue(out.tapMeansDisconnect)
    }

    // ── T-NEW-28f: same for Connecting ──────────────────────────────────

    @Test
    fun `run Connecting is shown even when the tracker already says Connected`() {
        val out = combineAutoModeState(runConnecting, VpnConnectionStatus.CONNECTED)

        assertSame(runConnecting, out.display)
        assertFalse("a run in flight owns the display", out.isExternal)
    }

    // ── T-NEW-28g: a live tunnel outranks a stale Error ─────────────────

    @Test
    fun `an external tunnel after a run error still shows Connected`() {
        val out = combineAutoModeState(AutoModeState.Error("no server answered"), VpnConnectionStatus.CONNECTED)

        assertTrue(
            "the tracker is the truth after a run error; the button must not claim a failed run",
            out.display is AutoModeState.Connected,
        )
        assertTrue(out.isExternal)
        assertTrue(out.tapMeansDisconnect)
        assertTrue("the error itself is preserved for the error card", out.modeState is AutoModeState.Error)
    }

    // ── T-NEW-28h: an Error with no tunnel stays an Error ───────────────

    @Test
    fun `an error with no tunnel stays Error and offers a retry`() {
        val error = AutoModeState.Error("no server answered")
        val out = combineAutoModeState(error, VpnConnectionStatus.DISCONNECTED)

        assertSame(error, out.display)
        assertFalse(out.isExternal)
        assertFalse("Error + tap means retry, i.e. start", out.tapMeansDisconnect)
    }
}
