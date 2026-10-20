package vn.unlimit.vpngate.utils

import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUTOCONNECT-DEVICE-BUGS-FIX (spec §1.6.32), T-NEW-29 — the persisted
 * "battery prompt already shown" flag.
 *
 * BUG-3 on the device was the same dialog on every press. The gate was the live
 * OS state alone (`PowerManager.isIgnoringBatteryOptimizations`), which stays
 * false for ever after the user taps "Later", so the prompt re-opened each time.
 * `SETTING_BATTERY_OPT_ASKED` makes it one-shot.
 *
 * The flag is reached through [DataUtil]'s preferences accessors. This module's
 * unit tests run on a plain JVM (JUnit 4 only — no Robolectric, no Mockito), so
 * the test injects an in-memory [SharedPreferences] through the internal
 * constructor instead of building an Android `Context`.
 */
class DataUtilBatteryFlagTest {

    private val prefs = FakeSharedPreferences()
    private val dataUtil = DataUtil(prefs)

    // ── T-NEW-29a: a fresh install has not been asked ───────────────────

    @Test
    fun `a fresh install has not been asked`() {
        assertFalse(
            "the prompt must be allowed to fire once on a fresh install",
            dataUtil.hasAskedBatteryOptimization(),
        )
    }

    // ── T-NEW-29b: asking makes it permanent ────────────────────────────

    @Test
    fun `setting the flag makes it read back as asked`() {
        dataUtil.setAskedBatteryOptimization()

        assertTrue(dataUtil.hasAskedBatteryOptimization())
    }

    @Test
    fun `the flag survives a new DataUtil over the same store`() {
        dataUtil.setAskedBatteryOptimization()

        // A new instance stands in for the next app launch: same preferences
        // file, fresh object.
        val next = DataUtil(prefs)

        assertTrue(
            "the ask must not repeat after a restart, or BUG-3 comes back",
            next.hasAskedBatteryOptimization(),
        )
    }

    // ── T-NEW-29c: the key is the documented one ────────────────────────

    @Test
    fun `the flag is stored under the documented key`() {
        dataUtil.setAskedBatteryOptimization()

        assertTrue(
            "a different key would silently disable the gate",
            prefs.contains(DataUtil.SETTING_BATTERY_OPT_ASKED),
        )
    }

    // ── T-NEW-29d: no cross-talk with the language flag ─────────────────

    @Test
    fun `answering the language prompt does not answer the battery prompt`() {
        dataUtil.markLocaleAnswered()

        assertFalse(
            "the two one-shot prompts are independent",
            dataUtil.hasAskedBatteryOptimization(),
        )
    }

    @Test
    fun `answering the battery prompt does not answer the language prompt`() {
        dataUtil.setAskedBatteryOptimization()

        assertFalse(
            "the language picker must still appear when unanswered",
            dataUtil.getBooleanSetting(DataUtil.SETTING_LOCALE_ANSWERED, false),
        )
    }
}

/**
 * Minimal in-memory [SharedPreferences]. Only the accessors [DataUtil]'s flag
 * methods actually use are implemented faithfully; the listener hooks are no-ops
 * because nothing in this test observes preference changes.
 */
private class FakeSharedPreferences : SharedPreferences {

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

    override fun edit(): SharedPreferences.Editor = FakeEditor(values)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
}

private class FakeEditor(
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
