package vn.unlimit.vpngate.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.automode.AutoModeProtocol
import vn.unlimit.vpngate.automode.AutoModeState
import vn.unlimit.vpngate.automode.ConnectionOrigin
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

    // ── BLOCK v1.0.68 PART A: "Try Next Server" enablement ──────────────

    @Test
    fun `try next is disabled only when the display is genuinely Disconnected`() {
        assertFalse(tryNextEnabled(AutoModeState.Disconnected))
    }

    @Test
    fun `try next is enabled while Connecting, Connected and Error`() {
        assertTrue(tryNextEnabled(runConnecting))
        assertTrue(tryNextEnabled(runConnected))
        assertTrue(tryNextEnabled(AutoModeState.Error("no server answered")))
    }

    @Test
    fun `try next stays enabled for an external connected tunnel`() {
        // PART A defect: for a manual/external tunnel the controller is
        // Disconnected, so the old `state !is Disconnected` disabled the button
        // while the screen showed "Connected (external)". Deriving from the
        // display state fixes it; skipToNextServer() routes this via skipFromExternal.
        val externalConnected = combineAutoModeState(AutoModeState.Disconnected, VpnConnectionStatus.CONNECTED)
        assertTrue(externalConnected.isExternal)
        assertTrue("external connected must keep Try Next enabled", tryNextEnabled(externalConnected.display))

        val externalConnecting = combineAutoModeState(AutoModeState.Disconnected, VpnConnectionStatus.CONNECTING)
        assertTrue("external connecting must keep Try Next enabled", tryNextEnabled(externalConnecting.display))

        val reallyIdle = combineAutoModeState(AutoModeState.Disconnected, VpnConnectionStatus.DISCONNECTED)
        assertFalse("nothing is up, so Try Next is disabled", tryNextEnabled(reallyIdle.display))
    }

    // ── BLOCK v1.0.75 PART A (D-3): the connection origin ───────────────

    @Test
    fun `a run Connected keeps the origin the controller stamped on it`() {
        val autoConnect = runConnected.copy(origin = ConnectionOrigin.AUTO_CONNECT)
        val out = combineAutoModeState(autoConnect, VpnConnectionStatus.DISCONNECTED)
        assertSame(autoConnect, out.display)
        assertEquals(ConnectionOrigin.AUTO_CONNECT, (out.display as AutoModeState.Connected).origin)
        assertFalse(out.isExternal)
    }

    @Test
    fun `a reconnect run reports AUTO_RECONNECT through the combination`() {
        val reconnected = runConnected.copy(origin = ConnectionOrigin.AUTO_RECONNECT)
        val out = combineAutoModeState(reconnected, VpnConnectionStatus.CONNECTED)
        // The run owns the display even while the tracker also says CONNECTED.
        assertEquals(ConnectionOrigin.AUTO_RECONNECT, (out.display as AutoModeState.Connected).origin)
        assertFalse(out.isExternal)
    }

    @Test
    fun `every origin survives the combination for Connecting runs`() {
        for (origin in ConnectionOrigin.entries) {
            val connecting = runConnecting.copy(origin = origin)
            val out = combineAutoModeState(connecting, VpnConnectionStatus.CONNECTED)
            assertEquals("origin $origin must pass through untouched", origin, (out.display as AutoModeState.Connecting).origin)
            assertFalse(out.isExternal)
        }
    }

    @Test
    fun `an external tunnel reports MANUAL with the REAL tracker identity`() {
        val out = combineAutoModeState(
            AutoModeState.Disconnected,
            VpnConnectionStatus.CONNECTED,
            externalHost = "manual.example.net",
            externalIp = "198.51.100.9",
            externalProtocolId = "SSTP",
        )

        val shown = out.display as AutoModeState.Connected
        assertTrue(out.isExternal)
        assertEquals("the manual tunnel shows its REAL host, not null", "manual.example.net", shown.hostname)
        assertEquals("198.51.100.9", shown.ip)
        assertEquals("the tracker names ONE transport, so it is shown", AutoModeProtocol.MS_SSTP, shown.protocol)
        assertEquals(ConnectionOrigin.MANUAL, shown.origin)
    }

    @Test
    fun `an external tunnel with an ambiguous protocol shows NO protocol`() {
        for (ambiguous in listOf("SoftEther", "OpenVPN", null)) {
            val out = combineAutoModeState(
                AutoModeState.Disconnected,
                VpnConnectionStatus.CONNECTED,
                externalHost = "manual.example.net",
                externalIp = "198.51.100.9",
                externalProtocolId = ambiguous,
            )
            val shown = out.display as AutoModeState.Connected
            assertNull("protocol '$ambiguous' must never be fabricated", shown.protocol)
            assertEquals(ConnectionOrigin.MANUAL, shown.origin)
        }
    }

    @Test
    fun `an external CONNECTING tunnel reports MANUAL with the real identity`() {
        val out = combineAutoModeState(
            AutoModeState.Disconnected,
            VpnConnectionStatus.CONNECTING,
            externalHost = "manual.example.net",
            externalIp = "198.51.100.9",
            externalProtocolId = "SSTP",
        )
        val shown = out.display as AutoModeState.Connecting
        assertTrue(out.isExternal)
        assertEquals("manual.example.net", shown.hostname)
        assertEquals(AutoModeProtocol.MS_SSTP, shown.protocol)
        assertEquals(ConnectionOrigin.MANUAL, shown.origin)
    }

    @Test
    fun `an external tunnel after a run error still reports MANUAL`() {
        val out = combineAutoModeState(
            AutoModeState.Error("no server answered"),
            VpnConnectionStatus.CONNECTED,
            externalHost = "manual.example.net",
            externalIp = "198.51.100.9",
            externalProtocolId = "SSTP",
        )
        val shown = out.display as AutoModeState.Connected
        assertTrue(out.isExternal)
        assertEquals(ConnectionOrigin.MANUAL, shown.origin)
        assertTrue("the run error is preserved for the error card", out.modeState is AutoModeState.Error)
    }

    @Test
    fun `external defaults keep the no-fabrication rule when the caller passes no identity`() {
        // Legacy 2-arg callers (tests, future call sites): the synthesized
        // external state must not invent host, IP or protocol.
        val out = combineAutoModeState(AutoModeState.Disconnected, VpnConnectionStatus.CONNECTED)
        val shown = out.display as AutoModeState.Connected
        assertNull(shown.hostname)
        assertNull(shown.ip)
        assertNull(shown.protocol)
        assertEquals(ConnectionOrigin.MANUAL, shown.origin)
    }

    // ── BLOCK v1.0.75 PART A: the tracker-label → protocol mapping ──────

    @Test
    fun `tracker protocol labels map honestly`() {
        assertEquals(AutoModeProtocol.MS_SSTP, AutoModeProtocol.fromTrackerId("SSTP"))
        assertEquals(AutoModeProtocol.SOFTETHER_TCP, AutoModeProtocol.fromTrackerId("SOFTETHER_TCP"))
        assertEquals(AutoModeProtocol.SOFTETHER_UDP, AutoModeProtocol.fromTrackerId("SOFTETHER_UDP"))
        assertNull(AutoModeProtocol.fromTrackerId("SoftEther"))
        assertNull(AutoModeProtocol.fromTrackerId("OpenVPN"))
        assertNull(AutoModeProtocol.fromTrackerId("something-else"))
        assertNull(AutoModeProtocol.fromTrackerId(null))
    }

    // ── BLOCK v1.0.77 (REQ-ORIGIN-001): the persisted origin ────────────────

    @Test
    fun `a persisted auto connect origin survives a process restart for the same tunnel`() {
        val out = combineAutoModeState(
            AutoModeState.Disconnected,
            VpnConnectionStatus.CONNECTED,
            externalHost = "vpn595476537.opengw.net",
            externalIp = "1.2.3.4",
            externalProtocolId = "SOFTETHER_TCP",
            persistedOrigin = ConnectionOrigin.AUTO_CONNECT,
            persistedServerKey = "1.2.3.4|vpn595476537.opengw.net",
        )

        assertEquals(
            "the app did start this tunnel, so MANUAL would be a lie",
            ConnectionOrigin.AUTO_CONNECT,
            (out.display as AutoModeState.Connected).origin,
        )
        assertTrue("the tunnel is still external to the run", out.isExternal)
    }

    @Test
    fun `a persisted auto reconnect origin is preserved too`() {
        val out = combineAutoModeState(
            AutoModeState.Disconnected,
            VpnConnectionStatus.CONNECTED,
            externalHost = "vpn1.opengw.net",
            externalIp = "5.6.7.8",
            persistedOrigin = ConnectionOrigin.AUTO_RECONNECT,
            persistedServerKey = "5.6.7.8|vpn1.opengw.net",
        )

        assertEquals(
            ConnectionOrigin.AUTO_RECONNECT,
            (out.display as AutoModeState.Connected).origin,
        )
    }

    @Test
    fun `a stale record never labels a different tunnel`() {
        val out = combineAutoModeState(
            AutoModeState.Disconnected,
            VpnConnectionStatus.CONNECTED,
            externalHost = "someone-elses-vpn",
            externalIp = "9.9.9.9",
            persistedOrigin = ConnectionOrigin.AUTO_CONNECT,
            persistedServerKey = "1.2.3.4|vpn595476537.opengw.net",
        )

        assertEquals(
            "a record for another server must degrade to MANUAL",
            ConnectionOrigin.MANUAL,
            (out.display as AutoModeState.Connected).origin,
        )
    }

    @Test
    fun `no record and a manual record both read as manual`() {
        assertEquals(
            ConnectionOrigin.MANUAL,
            persistedOriginFor(null, null, "1.2.3.4", "h"),
        )
        assertEquals(
            ConnectionOrigin.MANUAL,
            persistedOriginFor(ConnectionOrigin.MANUAL, "1.2.3.4|h", "1.2.3.4", "h"),
        )
    }

    @Test
    fun `an empty recorded key is never trusted`() {
        assertEquals(
            ConnectionOrigin.MANUAL,
            persistedOriginFor(ConnectionOrigin.AUTO_CONNECT, "  ", "1.2.3.4", "h"),
        )
        assertEquals(
            ConnectionOrigin.MANUAL,
            persistedOriginFor(ConnectionOrigin.AUTO_CONNECT, null, "1.2.3.4", "h"),
        )
    }

    @Test
    fun `the hostname is the fallback identity when no ip is known`() {
        // The tracker sometimes names the host but not the address.
        assertEquals(
            ConnectionOrigin.AUTO_CONNECT,
            persistedOriginFor(
                ConnectionOrigin.AUTO_CONNECT,
                "1.2.3.4|vpn595476537.opengw.net",
                ip = null,
                hostname = "VPN595476537.opengw.net",
            ),
        )
        assertEquals(
            ConnectionOrigin.MANUAL,
            persistedOriginFor(
                ConnectionOrigin.AUTO_CONNECT,
                "1.2.3.4|vpn595476537.opengw.net",
                ip = null,
                hostname = "other.opengw.net",
            ),
        )
    }
}
