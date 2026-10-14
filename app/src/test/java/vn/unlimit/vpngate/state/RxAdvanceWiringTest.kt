package vn.unlimit.vpngate.state

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.softether.TunnelHealth

/**
 * R4b — the RX half of `SERVER_SOURCE_ARCHITECTURE.md` §7 Q1, now that the
 * counter has a producer.
 *
 * R4a built the counter and the decision but deliberately left
 * `noteRxAdvance()` uncalled, so `isTunnelUsable()` could only ever report
 * false. R4b added the producer at the tunnel receive path
 * (`ConnectionController.startDataForwarding`, one call per received batch).
 * These tests prove the counter now actually moves and that the decision
 * follows it through the 10 s window.
 *
 * Targets `TunnelHealth` directly rather than `GlobalVpnTracker` because the
 * tracker's initializer needs `Handler(Looper.getMainLooper())`, which does not
 * exist on a plain JVM; the tracker only supplies the two live inputs.
 */
class RxAdvanceWiringTest {

    @After
    fun resetCounters() {
        TunnelHealth.resetHealthCountersForTest()
    }

    @Test
    fun `noteRxAdvance increments the counter and stamps the clock`() {
        TunnelHealth.resetHealthCountersForTest()
        val before = TunnelHealth.rxCounter()

        TunnelHealth.noteRxAdvance()

        assertEquals(before + 1, TunnelHealth.rxCounter())
        assertTrue(TunnelHealth.lastRxAdvanceAtMs() > 0)
    }

    @Test
    fun `each received batch advances the counter`() {
        TunnelHealth.resetHealthCountersForTest()
        repeat(50) { TunnelHealth.noteRxAdvance() }
        assertEquals(50L, TunnelHealth.rxCounter())
    }

    @Test
    fun `tunnel becomes usable once an rx advance is observed`() {
        TunnelHealth.resetHealthCountersForTest()
        val now = 1_000_000L

        // Connected but no RX evidence yet: fails closed, as §7 Q1 requires.
        assertFalse(TunnelHealth.isUsable(isConnected = true, nowMs = now))

        TunnelHealth.noteRxAdvance()
        TunnelHealth.setLastRxAdvanceAtMsForTest(now)

        assertTrue(TunnelHealth.isUsable(isConnected = true, nowMs = now))
    }

    @Test
    fun `tunnel becomes unusable once the rx window elapses`() {
        TunnelHealth.resetHealthCountersForTest()
        val stamp = 1_000_000L
        TunnelHealth.noteRxAdvance()
        TunnelHealth.setLastRxAdvanceAtMsForTest(stamp)

        // Fresh inside the window.
        assertTrue(TunnelHealth.isUsable(isConnected = true, nowMs = stamp + TunnelHealth.RX_WINDOW_MS - 1))
        // Exactly at the window boundary is stale.
        assertFalse(TunnelHealth.isUsable(isConnected = true, nowMs = stamp + TunnelHealth.RX_WINDOW_MS))
        // Any later time is stale too.
        assertFalse(TunnelHealth.isUsable(isConnected = true, nowMs = stamp + TunnelHealth.RX_WINDOW_MS + 60_000))
    }

    @Test
    fun `a wrapped connection with a stale rx stays unusable`() {
        TunnelHealth.resetHealthCountersForTest()
        TunnelHealth.noteRxAdvance()
        TunnelHealth.setLastRxAdvanceAtMsForTest(500_000L)

        // Disconnected outright.
        assertFalse(TunnelHealth.isUsable(isConnected = false, nowMs = 500_100L))
        // Connected but the last advance is far outside the window.
        assertFalse(TunnelHealth.isUsable(isConnected = true, nowMs = 500_000L + 90_000L))
    }

    @Test
    fun `rx window constant matches the seven-q1 requirement`() {
        assertEquals(10_000L, TunnelHealth.RX_WINDOW_MS)
        assertEquals(30_000L, TunnelHealth.EIO_WINDOW_MS)
    }

    @Test
    fun `recent eio overrides a healthy rx`() {
        TunnelHealth.resetHealthCountersForTest()
        val now = 2_000_000L
        TunnelHealth.noteRxAdvance()
        TunnelHealth.setLastRxAdvanceAtMsForTest(now)

        assertTrue(TunnelHealth.isUsable(isConnected = true, nowMs = now))

        TunnelHealth.noteTunEio()
        TunnelHealth.setLastEioAtMsForTest(now)
        assertFalse(TunnelHealth.isUsable(isConnected = true, nowMs = now))

        // Once the EIO window has passed and RX is still fresh, usable again.
        TunnelHealth.setLastRxAdvanceAtMsForTest(now + TunnelHealth.EIO_WINDOW_MS + 1)
        assertTrue(TunnelHealth.isUsable(isConnected = true, nowMs = now + TunnelHealth.EIO_WINDOW_MS + 1))
    }
}
