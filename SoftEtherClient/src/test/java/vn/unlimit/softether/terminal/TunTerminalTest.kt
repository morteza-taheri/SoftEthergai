package vn.unlimit.softether.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.softether.TunnelHealth

/**
 * R5 unit tests — the TUN read-error classification added in
 * `AUDIT_AXES_A_B_C_v1.0.11.md` §5.2.
 *
 * [TunTerminal.isTunEio] is the single decision point that separates a
 * revoked-interface read (EBADF or EIO — clean shutdown, no onError, no
 * reconnect) from a genuine I/O error (onError → triggerReconnect). It is
 * exercised here directly because the enclosing `readLoop` needs a live
 * ParcelFileDescriptor, which is not available in a JVM test.
 */
class TunTerminalTest {

    @Test
    fun `raw errno EIO is classified as EIO`() {
        assertTrue(TunTerminal.isTunEio("read failed: EIO"))
    }

    @Test
    fun `localized input output error is classified as EIO`() {
        assertTrue(TunTerminal.isTunEio("Input/output error"))
    }

    @Test
    fun `EBADF is not classified as EIO`() {
        // EBADF has its own branch and must stay distinguishable from EIO.
        assertFalse(TunTerminal.isTunEio("read failed: EBADF"))
        assertFalse(TunTerminal.isTunEio("Bad file descriptor"))
    }

    @Test
    fun `unrelated errors are not classified as EIO`() {
        assertFalse(TunTerminal.isTunEio("Connection reset by peer"))
        assertFalse(TunTerminal.isTunEio(""))
        assertFalse(TunTerminal.isTunEio("Permission denied"))
    }

    @Test
    fun `read buffer exceeds the TUN MTU so truncation cannot occur`() {
        // R6 records the relationship: TUN MTU is 1500, BUFFER_SIZE is 8192.
        // A read() on a TUN fd returns one packet per call and a packet larger
        // than the buffer would be truncated silently — impossible at 8192 vs 1500.
        val tunMtu = 1500
        assertTrue(TunTerminal.BUFFER_SIZE > tunMtu)
    }

    @Test
    fun `tunEio counter starts at zero for a fresh process`() {
        TunnelHealth.resetHealthCountersForTest()
        assertEquals(0L, TunnelHealth.tunEioCount())
        assertFalse(TunnelHealth.hasSeenEio())
        TunnelHealth.noteTunEio()
        assertEquals(1L, TunnelHealth.tunEioCount())
        assertTrue(TunnelHealth.hasSeenEio())
        TunnelHealth.resetHealthCountersForTest()
    }
}
