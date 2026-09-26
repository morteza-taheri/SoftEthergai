package vn.unlimit.vpngate.ui.screens.status

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the Status screen's traffic arithmetic.
 *
 * These pin the edge cases the UI actually hits: a coalesced tick, a clock that
 * does not move forward, and the overflow boundary of the delta*1000 multiply.
 */
class TrafficStatsTest {

    @Test
    fun normalDeltaOverOneSecond() {
        assertEquals(1000L, TrafficStats.bytesPerSecond(1000L, 1000L))
    }

    @Test
    fun scalesWithTheMeasuredInterval() {
        // 2000 B over 2000 ms is 1000 B/s, not 2000 B/s: a slow tick must not
        // inflate the rate.
        assertEquals(1000L, TrafficStats.bytesPerSecond(2000L, 2000L))
        // 2000 B over 4000 ms is 500 B/s.
        assertEquals(500L, TrafficStats.bytesPerSecond(2000L, 4000L))
    }

    @Test
    fun zeroDeltaIsZero() {
        assertEquals(0L, TrafficStats.bytesPerSecond(0L, 1000L))
    }

    @Test
    fun nonPositiveIntervalIsZeroNotAnError() {
        // Clock going backwards, or a snapshot published twice.
        assertEquals(0L, TrafficStats.bytesPerSecond(1000L, 0L))
        assertEquals(0L, TrafficStats.bytesPerSecond(1000L, -5L))
    }

    @Test
    fun negativeDeltaIsClampedToZero() {
        assertEquals(0L, TrafficStats.bytesPerSecond(-1000L, 1000L))
    }

    @Test
    fun hugeDeltaDoesNotOverflow() {
        // Long.MAX_VALUE * 1000 would wrap negative without the guard.
        assertEquals(Long.MAX_VALUE, TrafficStats.bytesPerSecond(Long.MAX_VALUE, 1000L))
    }

    @Test
    fun zeroLabelsMatchTheRenderedFormat() {
        // The formatter's speed branch emits bit/s, so the default state must not
        // claim "kbps" (which would be a different unit than every real value).
        assertEquals("0 bit/s", TrafficStats.zeroSpeed())
        assertEquals("0 B", TrafficStats.zeroVolume())
    }
}
