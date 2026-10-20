package vn.unlimit.vpngate.utils

import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ROUTING-REACHABILITY-AND-EPERM-DIAGNOSTICS (M1, spec §1.6.36), T-NEW-46..49 — the
 * persisted "always prefer VPN" collector-routing opt-in.
 *
 * Until this phase nothing wrote PREF_ALWAYS_PREFER_VPN ("vpn_always_prefer_vpn"), so
 * GlobalVpnTracker.prefersVpnForCollector() could never return true for a user
 * (DEVICE_VERIFY_CLUSTER_v1.0.43_REPORT.md §8). DataUtil.getPrefersVpnForCollector() /
 * setPrefersVpnForCollector() are the single writer the Settings toggle calls, and they
 * reuse the exact key literal so the behaviour is independent of the (frozen)
 * GlobalVpnTracker constant. The unit tests run on a plain JVM (JUnit 4 only — no
 * Robolectric, no Mockito), injecting an in-memory SharedPreferences through the
 * internal constructor.
 *
 * The in-memory SharedPreferences classes are named uniquely (PrefersVpnFake*) to avoid
 * a top-level private-class collision with the same-named fakes in
 * DataUtilBatteryFlagTest.kt in the same package.
 */
class DataUtilPrefersVpnTest {

    private val prefs = PrefersVpnFakeSharedPreferences()
    private val dataUtil = DataUtil(prefs)

    // ── T-NEW-46: a fresh install does not prefer VPN ─────────────────────

    @Test
    fun `a fresh install does not prefer VPN`() {
        assertFalse(
            "the opt-in must default off so the v1.0.41 VPN rung stays dormant",
            dataUtil.getPrefersVpnForCollector(),
        )
    }

    // ── T-NEW-47: setting makes it read back as preferred ─────────────────

    @Test
    fun `setting the opt-in makes it read back as preferred`() {
        dataUtil.setPrefersVpnForCollector(true)

        assertTrue(dataUtil.getPrefersVpnForCollector())
    }

    // ── T-NEW-48: the opt-in survives a restart ───────────────────────────

    @Test
    fun `the opt-in survives a new DataUtil over the same store`() {
        dataUtil.setPrefersVpnForCollector(true)

        // A new instance stands in for the next app launch: same preferences
        // file, fresh object.
        val next = DataUtil(prefs)

        assertTrue(
            "the opt-in must not revert after a restart, or M1 returns",
            next.getPrefersVpnForCollector(),
        )
    }

    // ── T-NEW-49: the key literal is exactly the documented one ───────────

    @Test
    fun `the opt-in is stored under the documented key literal`() {
        dataUtil.setPrefersVpnForCollector(true)

        assertTrue(
            "a different key would silently defeat the v1.0.41 routing read",
            prefs.contains("vpn_always_prefer_vpn"),
        )
    }
}

/**
 * Minimal in-memory [SharedPreferences]. Only the accessors [DataUtil]'s flag
 * methods actually use are implemented faithfully; the listener hooks are no-ops
 * because nothing in this test observes preference changes.
 */
private class PrefersVpnFakeSharedPreferences : SharedPreferences {

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

    override fun edit(): SharedPreferences.Editor = PrefersVpnFakeEditor(values)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
}

private class PrefersVpnFakeEditor(
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
