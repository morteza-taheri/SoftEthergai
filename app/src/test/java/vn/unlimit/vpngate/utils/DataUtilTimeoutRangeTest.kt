package vn.unlimit.vpngate.utils

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.0.46 (§1.6.39) — T-NEW-70..72: the Auto Mode timeout range is 8..60.
 * Updated in v1.0.52 (§1.6.44) TASK B.2, which moved the DEFAULT from 12 to 8.
 *
 * CHANGE B (v1.0.46) raised the floor from 5 to 8 seconds. The maximum (60) is
 * unchanged, so this file pins all three numbers: the default, the floor and the
 * ceiling. v1.0.52 lowered the default to the floor, so the default is now 8 and
 * "no stored value" and "a value below the floor" converge on the same number.
 *
 * The clamp lives in **both** the reader and the writer. Clamping on read is
 * what makes the change safe for an existing install: a value of 5, 6 or 7
 * written by an older build reads back as 8 without a migration.
 *
 * Uses [DataUtil]'s `internal constructor(sharedPreferences)` seam, so no
 * Android runtime is needed.
 */
class DataUtilTimeoutRangeTest {

    private fun dataUtil() = DataUtil(TimeoutFakeSharedPreferences())

    // ── T-NEW-70: the default is now 8 (was 12 before v1.0.52) ────────────

    @Test
    fun `T-NEW-70 the default timeout with no stored value is 8 seconds`() {
        assertEquals(
            "v1.0.52 (§1.6.44) TASK B.2 lowered the default from 12 to the 8 s floor",
            8,
            dataUtil().getAutoModeTimeoutSeconds(),
        )
    }

    @Test
    fun `T-NEW-70b the default equals the controller fallback`() {
        // The two numbers used to be able to drift apart silently (25 / 12 / 5
        // before v1.0.31). Pinning them against each other here means a future
        // change to either one fails this file as well as the controller test.
        assertEquals(
            "the Settings default must equal AutoModeController.DEFAULT_ATTEMPT_TIMEOUT_MS",
            vn.unlimit.vpngate.automode.AutoModeController.DEFAULT_ATTEMPT_TIMEOUT_MS,
            dataUtil().getAutoModeTimeoutSeconds() * 1_000L,
        )
    }

    // ── T-NEW-71: the floor is 8 ──────────────────────────────────────────

    @Test
    fun `T-NEW-71 setting 5 seconds is clamped up to 8 on read`() {
        val dataUtil = dataUtil()

        dataUtil.setAutoModeTimeoutSeconds(5)

        assertEquals(
            "the v1.0.46 floor is 8 s; a 5 s write must not survive",
            8,
            dataUtil.getAutoModeTimeoutSeconds(),
        )
    }

    @Test
    fun `T-NEW-71b a value stored by an older build at 7 reads back as 8`() {
        // Simulates an upgrade: the stored int is 7, written before this phase.
        val prefs = TimeoutFakeSharedPreferences()
        prefs.edit()
            .putInt(DataUtil.SETTING_AUTO_MODE_TIMEOUT_SECONDS, 7)
            .apply()

        assertEquals(
            "clamp-on-read is what protects existing installs, with no migration",
            8,
            DataUtil(prefs).getAutoModeTimeoutSeconds(),
        )
    }

    // ── T-NEW-72: the ceiling and the exact floor still hold ──────────────

    @Test
    fun `T-NEW-72 setting 100 seconds is clamped down to 60 and 8 is stored as 8`() {
        val dataUtil = dataUtil()

        dataUtil.setAutoModeTimeoutSeconds(100)
        assertEquals(
            "the maximum must stay 60 s",
            60,
            dataUtil.getAutoModeTimeoutSeconds(),
        )

        dataUtil.setAutoModeTimeoutSeconds(8)
        assertEquals(
            "8 s is inside the range and must be stored verbatim",
            8,
            dataUtil.getAutoModeTimeoutSeconds(),
        )
    }

    @Test
    fun `T-NEW-72b in-range values pass through untouched`() {
        val dataUtil = dataUtil()
        for (seconds in listOf(9, 12, 30, 59, 60)) {
            dataUtil.setAutoModeTimeoutSeconds(seconds)
            assertEquals("$seconds s is in range and must round-trip", seconds, dataUtil.getAutoModeTimeoutSeconds())
        }
    }
}

/**
 * In-memory [SharedPreferences]. Named uniquely so it cannot collide with the
 * top-level private fakes in the other test files of this package.
 */
private class TimeoutFakeSharedPreferences : SharedPreferences {

    private val values = LinkedHashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

    override fun getString(key: String, defValue: String?): String? =
        values[key] as? String ?: defValue

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST")
        (values[key] as? MutableSet<String>) ?: defValues

    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = TimeoutFakeEditor(values)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
}

private class TimeoutFakeEditor(
    private val values: MutableMap<String, Any?>,
) : SharedPreferences.Editor {

    private val pending = LinkedHashMap<String, Any?>()
    private val removed = HashSet<String>()
    private var cleared = false

    override fun putString(key: String, value: String?): SharedPreferences.Editor {
        pending[key] = value
        return this
    }

    override fun putStringSet(
        key: String,
        values: MutableSet<String>?,
    ): SharedPreferences.Editor {
        pending[key] = values
        return this
    }

    override fun putInt(key: String, value: Int): SharedPreferences.Editor {
        pending[key] = value
        return this
    }

    override fun putLong(key: String, value: Long): SharedPreferences.Editor {
        pending[key] = value
        return this
    }

    override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
        pending[key] = value
        return this
    }

    override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
        pending[key] = value
        return this
    }

    override fun remove(key: String): SharedPreferences.Editor {
        removed.add(key)
        return this
    }

    override fun clear(): SharedPreferences.Editor {
        cleared = true
        return this
    }

    override fun commit(): Boolean {
        apply()
        return true
    }

    override fun apply() {
        if (cleared) values.clear()
        removed.forEach { values.remove(it) }
        values.putAll(pending)
    }
}
