package vn.unlimit.vpngate.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * BLOCK v1.0.69 PART 1D — deterministic tests for [QualityRejectWindow].
 *
 * Pure JVM, injected clock: no Android, no database, no real 10 s sleeps.
 */
class QualityRejectWindowTest {

    private var now = 1_000_000L
    private fun window() = QualityRejectWindow(windowMs = 10_000L, clock = { now })

    @Test
    fun `the first rejection for a key is recorded`() {
        val w = window()
        assertTrue(w.shouldRecord("vpn-1"))
    }

    @Test
    fun `a duplicate inside the window is deduplicated`() {
        val w = window()
        assertTrue(w.shouldRecord("vpn-1"))
        now += 9_999
        assertFalse("still inside the 10 s window", w.shouldRecord("vpn-1"))
    }

    @Test
    fun `a rejection after the window is recorded again`() {
        val w = window()
        assertTrue(w.shouldRecord("vpn-1"))
        now += 10_000
        assertTrue("exactly at the window boundary is a new window", w.shouldRecord("vpn-1"))
    }

    @Test
    fun `different keys are independent`() {
        val w = window()
        assertTrue(w.shouldRecord("vpn-1"))
        assertTrue("a different server is never suppressed", w.shouldRecord("vpn-2"))
    }

    @Test
    fun `the boundary is the documented 10 s`() {
        assertEquals(10_000L, QualityRejectWindow.QUALITY_REJECT_WINDOW_MS)
        val w = window()
        assertTrue(w.shouldRecord("k"))
        now += 10_001
        assertTrue(w.shouldRecord("k"))
    }

    @Test
    fun `concurrent calls record exactly once`() {
        val w = window() // fixed clock: all callers see the same instant
        val threads = 16
        val accepted = AtomicInteger(0)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) {
            Thread {
                start.await()
                if (w.shouldRecord("shared")) accepted.incrementAndGet()
                done.countDown()
            }.start()
        }
        start.countDown()
        done.await()
        assertEquals("exactly one of the concurrent rejections may be recorded", 1, accepted.get())
    }

    // ── PART 1 §4.3: bounded state ────────────────────────────────────────

    @Test
    fun `expired entries are pruned`() {
        val w = window()
        assertTrue(w.shouldRecord("a"))
        assertEquals(1, w.trackedKeyCount())
        now += 10_000 // "a" is now expired
        assertTrue(w.shouldRecord("b")) // this call prunes "a"
        assertEquals("only the live key remains", 1, w.trackedKeyCount())
    }

    @Test
    fun `active entries are not pruned prematurely`() {
        val w = window()
        assertTrue(w.shouldRecord("a"))
        now += 5_000 // "a" still active
        assertTrue(w.shouldRecord("b"))
        assertEquals("the active key must survive", 2, w.trackedKeyCount())
    }

    @Test
    fun `sustained unique keys stay bounded by the window`() {
        val w = window()
        repeat(100) { i ->
            assertTrue(w.shouldRecord("k$i"))
        }
        // All 100 are within one window -> bounded by the live set, then expire.
        now += 10_000
        assertTrue(w.shouldRecord("fresh"))
        assertEquals("after expiry only the fresh key is live", 1, w.trackedKeyCount())
    }
}
