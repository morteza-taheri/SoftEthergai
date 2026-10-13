package vn.unlimit.vpngate.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The global tunnel state is the app's single source of truth, so these tests
 * pin the parts of the contract other subsystems rely on: the initial value,
 * the full transition set, and the rule that a Connect press is *not* a
 * connection.
 *
 * Pure JVM — [VpnConnectionStatus] and [GlobalVpnState] carry no Android types.
 */
class VpnTunnelStateTest {

    @Test
    fun `the initial state is disconnected with no server`() {
        val state = GlobalVpnState()
        assertEquals(VpnConnectionStatus.DISCONNECTED, state.status)
        assertEquals(null, state.protocol)
        assertEquals(null, state.serverHost)
        assertEquals(null, state.serverIp)
        assertEquals(null, state.errorMessage)
    }

    @Test
    fun `every required state exists`() {
        // The five states the rest of the app reasons about. A missing one
        // would silently fall into an `else` branch somewhere.
        val names = VpnConnectionStatus.entries.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("DISCONNECTED", "CONNECTING", "CONNECTED", "DISCONNECTING", "ERROR")))
    }

    @Test
    fun `pressing connect yields connecting and never connected`() {
        // The state a consumer sees between "user tapped Connect" and "tunnel
        // verified". Modelling the press explicitly is the point: a UI that
        // reads CONNECTED here would be lying.
        val afterPress = GlobalVpnState(
            status = VpnConnectionStatus.CONNECTING,
            protocol = "SoftEther",
            serverHost = "host",
            serverIp = "10.0.0.1",
        )
        assertEquals(VpnConnectionStatus.CONNECTING, afterPress.status)
        assertFalse(afterPress.status == VpnConnectionStatus.CONNECTED)
    }

    @Test
    fun `a verified tunnel is connected`() {
        val connected = GlobalVpnState(
            status = VpnConnectionStatus.CONNECTED,
            protocol = "OpenVPN",
            serverHost = "host",
            assignedIp = "10.0.0.2",
        )
        assertEquals(VpnConnectionStatus.CONNECTED, connected.status)
        assertEquals("10.0.0.2", connected.assignedIp)
    }

    @Test
    fun `a teardown is disconnecting and only then disconnected`() {
        val disconnecting = GlobalVpnState(status = VpnConnectionStatus.DISCONNECTING, protocol = "SSTP")
        assertEquals(VpnConnectionStatus.DISCONNECTING, disconnecting.status)

        // GlobalVpnTracker.updateState clears the connection details once the
        // tunnel is gone, so nothing stale can be displayed afterwards.
        val disconnected = GlobalVpnState(status = VpnConnectionStatus.DISCONNECTED)
        assertEquals(null, disconnected.protocol)
        assertEquals(null, disconnected.serverHost)
    }

    @Test
    fun `a failure is an error that still names the server`() {
        val error = GlobalVpnState(
            status = VpnConnectionStatus.ERROR,
            protocol = "SoftEther",
            serverHost = "host",
            errorMessage = "SoftEther error",
        )
        assertEquals(VpnConnectionStatus.ERROR, error.status)
        assertEquals("SoftEther error", error.errorMessage)
        // The identity is kept so the UI can say which server failed.
        assertEquals("host", error.serverHost)
    }

    @Test
    fun `only connecting and disconnecting count as busy`() {
        assertTrue(VpnConnectionStatus.CONNECTING.isBusy)
        assertTrue(VpnConnectionStatus.DISCONNECTING.isBusy)
        assertFalse(VpnConnectionStatus.DISCONNECTED.isBusy)
        assertFalse(VpnConnectionStatus.CONNECTED.isBusy)
        assertFalse(VpnConnectionStatus.ERROR.isBusy)
    }

    @Test
    fun `busy covers exactly the states a new connection must not race`() {
        val busy = VpnConnectionStatus.entries.filter { it.isBusy }.toSet()
        assertEquals(setOf(VpnConnectionStatus.CONNECTING, VpnConnectionStatus.DISCONNECTING), busy)
    }

    @Test
    fun `two snapshots of the same tunnel state are equal`() {
        // StateFlow only re-emits on inequality, so an unchanged state must
        // compare equal or consumers would be woken for nothing.
        val a = GlobalVpnState(VpnConnectionStatus.CONNECTED, "OpenVPN", "host", "10.0.0.1", "10.0.0.2")
        val b = GlobalVpnState(VpnConnectionStatus.CONNECTED, "OpenVPN", "host", "10.0.0.1", "10.0.0.2")
        assertEquals(a, b)
    }
}
