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
    fun developerModeDefaultIsOff() {
        // Fresh install: Developer Mode (verbose logging) must be OFF. The
        // user's explicit choice is stored in SharedPreferences and therefore
        // survives restarts unchanged.
        assertFalse(vn.unlimit.vpngate.utils.DataUtil.DEFAULT_DEVELOPER_MODE)
    }

    // v1.0.54 (§1.6.47) PART 2.3: the "Cache save time" contract test that used
    // to live here is GONE with the setting it pinned. Its replacement is
    // `DataUtilDefaultsV1054Test.refreshIntervalPolicyIsFixedAndClamped`, which
    // pins the fixed policy that actually governs server-list freshness.
}
