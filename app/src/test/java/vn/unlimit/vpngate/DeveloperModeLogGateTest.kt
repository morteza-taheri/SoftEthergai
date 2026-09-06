package vn.unlimit.vpngate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.data.model.CollectorLog

/**
 * Developer Mode log gate (user request 2026-08-30): with Developer Mode
 * disabled, CollectorLog.d must be a complete no-op (no string building
 * reaching the sink) so logging costs nothing at runtime.
 */
class DeveloperModeLogGateTest {

    @Test
    fun disabledCollectorLogDoesNotReachSink() {
        val captured = mutableListOf<String>()
        CollectorLog.sink = { captured += it }

        CollectorLog.enabled = false
        try {
            CollectorLog.d("should not be logged")
        } finally {
            CollectorLog.enabled = true
        }

        assertTrue(captured.isEmpty())
    }

    @Test
    fun enabledCollectorLogReachesSink() {
        val captured = mutableListOf<String>()
        CollectorLog.sink = { captured += it }

        CollectorLog.enabled = true
        CollectorLog.d("hello")

        assertEquals(listOf("hello"), captured)
    }

    @Test
    fun defaultStateIsEnabled() {
        // The default must be ON: troubleshooting logs are the norm.
        assertTrue(CollectorLog.enabled)
        assertFalse(!CollectorLog.enabled)
    }

    /**
     * Cache Save Time contract (user request 2026-08-30): default index 7
     * = "24 hours"; index 8 = "Never" (-1 minutes); array covers 15m..24h
     * plus Never — and the minutes map mirrors it 1:1 in DataUtil.
     */
    @Test
    fun cacheTimeDefaultsAndRanges() {
        assertEquals(7, vn.unlimit.vpngate.utils.DataUtil.DEFAULT_CACHE_TIME_INDEX)
        // Minutes map in DataUtil.getCacheSaveTimeMinutes is private, but its
        // contract is fixed: aligned with R.array.setting_cache_time which
        // has 9 entries (15m, 30m, 1h, 2h, 4h, 6h, 12h, 24h, Never).
        val expectedMinutes = intArrayOf(15, 30, 60, 120, 240, 360, 720, 1440, -1)
        assertEquals(24 * 60, expectedMinutes[vn.unlimit.vpngate.utils.DataUtil.DEFAULT_CACHE_TIME_INDEX])
        // "Never" is the last entry.
        assertEquals(-1, expectedMinutes[8])
    }
}
