package vn.unlimit.vpngate.state

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vn.unlimit.softether.TunnelHealth

/**
 * R4a unit tests — the tunnel-health counters and the `isTunnelUsable()`
 * decision that lift the blocker recorded in `AUDIT_AXES_A_B_C_v1.0.11.md`
 * §4.4 / §5.4.
 *
 * These target `TunnelHealth` (a pure top-level object in `SoftEtherClient`)
 * rather than `GlobalVpnTracker` directly: `GlobalVpnTracker`'s object
 * initializer needs the Android runtime (`Handler(Looper.getMainLooper())`)
 * and therefore cannot be initialized in a plain JVM test. The §7 Q1
 * decision is a pure function in `TunnelHealth.isUsable`, so it is fully
 * exercisable here.
 */
class GlobalVpnTrackerTest {

    private val now = 1_000_000L

    @Before
    fun setUp() {
        TunnelHealth.resetHealthCountersForTest()
    }

    @After
    fun tearDown() {
        TunnelHealth.resetHealthCountersForTest()
    }

    // ── isUsable: the three §7 Q1 conditions ────────────────────────────

    @Test
    fun `not connected is never usable even with fresh rx and no eio`() {
        TunnelHealth.setLastRxAdvanceAtMsForTest(now - 1_000L)
        assertFalse(TunnelHealth.isUsable(isConnected = false, now))
    }

    @Test
    fun `connected with recent rx and no eio is usable`() {
        TunnelHealth.setLastRxAdvanceAtMsForTest(now - 1_000L)
        assertTrue(TunnelHealth.isUsable(isConnected = true, now))
    }

    @Test
    fun `never-observed rx is not usable`() {
        // lastRxAdvanceAtMs == 0 → fails closed on missing evidence.
        assertFalse(TunnelHealth.isUsable(isConnected = true, now))
    }

    @Test
    fun `rx older than the 10s window is not usable`() {
        // Exactly at the boundary is already stale (>= window).
        TunnelHealth.setLastRxAdvanceAtMsForTest(now - TunnelHealth.RX_WINDOW_MS)
        assertFalse(TunnelHealth.isUsable(isConnected = true, now))
    }

    @Test
    fun `rx just inside the 10s window is usable`() {
        TunnelHealth.setLastRxAdvanceAtMsForTest(now - TunnelHealth.RX_WINDOW_MS + 1_000L)
        assertTrue(TunnelHealth.isUsable(isConnected = true, now))
    }

    @Test
    fun `eio inside the 30s window blocks usability`() {
        TunnelHealth.setLastRxAdvanceAtMsForTest(now - 1_000L)
        TunnelHealth.setLastEioAtMsForTest(now - 1_000L)
        assertFalse(TunnelHealth.isUsable(isConnected = true, now))
    }

    @Test
    fun `eio older than the 30s window does not block usability`() {
        TunnelHealth.setLastRxAdvanceAtMsForTest(now - 1_000L)
        TunnelHealth.setLastEioAtMsForTest(now - TunnelHealth.EIO_WINDOW_MS - 1_000L)
        assertTrue(TunnelHealth.isUsable(isConnected = true, now))
    }

    @Test
    fun `eio exactly at the window boundary does not block usability`() {
        TunnelHealth.setLastRxAdvanceAtMsForTest(now - 1_000L)
        TunnelHealth.setLastEioAtMsForTest(now - TunnelHealth.EIO_WINDOW_MS)
        assertTrue(TunnelHealth.isUsable(isConnected = true, now))
    }

    @Test
    fun `stale rx and recent eio both fail closed`() {
        TunnelHealth.setLastRxAdvanceAtMsForTest(now - TunnelHealth.RX_WINDOW_MS)
        TunnelHealth.setLastEioAtMsForTest(now - 1_000L)
        assertFalse(TunnelHealth.isUsable(isConnected = true, now))
    }

    // ── counter behaviour ──────────────────────────────────────────────

    @Test
    fun `rx advance increments counter and stamps clock`() {
        assertEquals(0L, TunnelHealth.rxCounter())
        TunnelHealth.noteRxAdvance()
        TunnelHealth.noteRxAdvance()
        assertEquals(2L, TunnelHealth.rxCounter())
        assertTrue(TunnelHealth.lastRxAdvanceAtMs() > 0L)
    }

    @Test
    fun `noteTunEio increments counter and stamps clock`() {
        assertEquals(0L, TunnelHealth.tunEioCount())
        assertFalse(TunnelHealth.hasSeenEio())
        TunnelHealth.noteTunEio()
        assertEquals(1L, TunnelHealth.tunEioCount())
        assertTrue(TunnelHealth.hasSeenEio())
        assertTrue(TunnelHealth.lastEioAtMs() > 0L)
    }

    @Test
    fun `window constants match the already-decided Q1 contract`() {
        // SERVER_SOURCE_ARCHITECTURE.md §7 Q1: RX within 10 s, no EIO in 30 s.
        // These are USER-DIRECTED REQUIREMENT values — assert they are not drifted.
        assertEquals(10_000L, TunnelHealth.RX_WINDOW_MS)
        assertEquals(30_000L, TunnelHealth.EIO_WINDOW_MS)
    }
}
