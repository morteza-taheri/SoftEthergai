package vn.unlimit.vpngate.utils

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.0.88 — [DataUtil.getAutoMaxAttemptsPerServer] / `setAutoMaxAttemptsPerServer`.
 *
 * ## What this pins
 *
 * The operator asked for the number of attempts Auto Mode makes on a single
 * unstable server to be configurable, and for it to move to the next server
 * after that number. The controller honours the value it is given; this class
 * pins the value itself:
 *
 *  - the default (2) reproduces the pre-v1.0.88 behaviour — re-dial a dropped
 *    server once, then rotate — so an existing install does not silently
 *    change behaviour on upgrade;
 *  - the clamp lives in **both** the reader and the writer, so a value stored
 *    outside 1..10 (a corrupted preference, or a range narrowed in a future
 *    build) reads back inside the range with no migration;
 *  - `1` is a legal, meaningful value: "never re-dial a server that just
 *    dropped, go straight to the next one". It must not be normalised away.
 *
 * Uses [DataUtil]'s `internal constructor(sharedPreferences)` seam, so no
 * Android runtime is needed.
 */
class DataUtilMaxAttemptsTest {

    private fun dataUtil() = DataUtil(MaxAttemptsFakeSharedPreferences())

    @Test
    fun `the default is two attempts per server`() {
        assertEquals(
            "the default must reproduce the pre-v1.0.88 behaviour (re-dial once, then rotate)",
            2,
            dataUtil().getAutoMaxAttemptsPerServer(),
        )
    }

    @Test
    fun `the default constant matches the documented value`() {
        assertEquals(2, DataUtil.DEFAULT_AUTO_MAX_ATTEMPTS_PER_SERVER)
        assertEquals(1, DataUtil.MIN_AUTO_MAX_ATTEMPTS_PER_SERVER)
        assertEquals(10, DataUtil.MAX_AUTO_MAX_ATTEMPTS_PER_SERVER)
    }

    @Test
    fun `one is a legal value and is not normalised away`() {
        val du = dataUtil()
        du.setAutoMaxAttemptsPerServer(1)
        assertEquals(
            "1 means 'move on immediately' and must survive the clamp",
            1,
            du.getAutoMaxAttemptsPerServer(),
        )
    }

    @Test
    fun `a value above the ceiling reads back as the ceiling`() {
        val du = dataUtil()
        du.setAutoMaxAttemptsPerServer(999)
        assertEquals(
            DataUtil.MAX_AUTO_MAX_ATTEMPTS_PER_SERVER,
            du.getAutoMaxAttemptsPerServer(),
        )
    }

    @Test
    fun `a value below the floor reads back as the floor`() {
        val du = dataUtil()
        du.setAutoMaxAttemptsPerServer(0)
        assertEquals(
            DataUtil.MIN_AUTO_MAX_ATTEMPTS_PER_SERVER,
            du.getAutoMaxAttemptsPerServer(),
        )
    }

    @Test
    fun `a negative value reads back as the floor`() {
        val du = dataUtil()
        du.setAutoMaxAttemptsPerServer(-5)
        assertEquals(DataUtil.MIN_AUTO_MAX_ATTEMPTS_PER_SERVER, du.getAutoMaxAttemptsPerServer())
    }

    @Test
    fun `a value written directly into preferences is clamped on read`() {
        // Simulates a preference written by a build whose range was wider.
        val prefs = MaxAttemptsFakeSharedPreferences()
        prefs.edit().putInt(DataUtil.SETTING_AUTO_MAX_ATTEMPTS_PER_SERVER, 42).apply()
        assertEquals(
            "a stored 42 must read back as the ceiling without a migration",
            DataUtil.MAX_AUTO_MAX_ATTEMPTS_PER_SERVER,
            DataUtil(prefs).getAutoMaxAttemptsPerServer(),
        )
    }
}

/** Minimal in-memory [SharedPreferences] — only the operations [DataUtil] uses. */
private class MaxAttemptsFakeSharedPreferences : SharedPreferences {

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

    override fun edit(): SharedPreferences.Editor = MaxAttemptsFakeEditor(values)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
}

private class MaxAttemptsFakeEditor(
    private val values: MutableMap<String, Any?>,
) : SharedPreferences.Editor {

    private val pending = LinkedHashMap<String, Any?>()
    private val removed = HashSet<String>()
    private var cleared = false

    override fun putString(key: String, value: String?): SharedPreferences.Editor =
        apply { pending[key] = value }

    override fun putStringSet(
        key: String,
        values: MutableSet<String>?,
    ): SharedPreferences.Editor = apply { pending[key] = values }

    override fun putInt(key: String, value: Int): SharedPreferences.Editor =
        apply { pending[key] = value }

    override fun putLong(key: String, value: Long): SharedPreferences.Editor =
        apply { pending[key] = value }

    override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
        apply { pending[key] = value }

    override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
        apply { pending[key] = value }

    override fun remove(key: String): SharedPreferences.Editor = apply { removed += key }

    override fun clear(): SharedPreferences.Editor = apply { cleared = true }

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
