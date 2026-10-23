package vn.unlimit.vpngate.utils

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.automode.AutoModeProtocol

/**
 * v1.0.54 (§1.6.47) PART 2 — the defaults that replaced removed settings.
 *
 * Three contracts are pinned here, all through [DataUtil]'s
 * `internal constructor(sharedPreferences)` seam so no Android runtime is
 * needed:
 *
 *  1. **PART 2.4** — an unset Auto Connect protocol profile reads as
 *     SoftEther TCP then SoftEther UDP, both enabled. Before v1.0.54 it read as
 *     an EMPTY list, and an empty list is the contract for "the user disabled
 *     everything", so Auto Connect refused to start on a fresh install.
 *  2. **PART 2.4** — a stored choice is never overwritten by that default, and
 *     an explicitly empty profile stays empty (a refusal, not a silent
 *     substitution).
 *  3. **PART 2.3** — the removed "Cache save time" policy is replaced by the
 *     fixed refresh-interval policy, clamped on write and on read.
 */
class DataUtilDefaultsV1054Test {

    private fun dataUtil(prefs: SharedPreferences = DefaultsFakeSharedPreferences()) =
        DataUtil(prefs)

    // ── PART 2.4: fresh install ───────────────────────────────────────────

    @Test
    fun `unset protocol profile defaults to SoftEther TCP then UDP`() {
        assertEquals(
            listOf(AutoModeProtocol.SOFTETHER_TCP, AutoModeProtocol.SOFTETHER_UDP),
            dataUtil().getAutoModeProtocolPriority(),
        )
    }

    @Test
    fun `the default is both SoftEther transports and nothing else`() {
        val default = DataUtil.DEFAULT_AUTO_PROTOCOL_PRIORITY
        assertEquals(2, default.size)
        assertTrue(default.none { it == AutoModeProtocol.L2TP_IPSEC })
        assertTrue(default.none { it == AutoModeProtocol.MS_SSTP })
        assertTrue(default.none { it == AutoModeProtocol.OPENVPN_TCP })
        assertTrue(default.none { it == AutoModeProtocol.OPENVPN_UDP })
    }

    // ── PART 2.4: a stored choice always wins ─────────────────────────────

    @Test
    fun `a stored user choice is never overwritten by the default`() {
        val prefs = DefaultsFakeSharedPreferences()
        val util = dataUtil(prefs)
        util.setAutoModeProtocolPriority(
            listOf(AutoModeProtocol.MS_SSTP, AutoModeProtocol.OPENVPN_UDP),
        )

        assertEquals(
            listOf(AutoModeProtocol.MS_SSTP, AutoModeProtocol.OPENVPN_UDP),
            dataUtil(prefs).getAutoModeProtocolPriority(),
        )
    }

    @Test
    fun `an explicitly empty profile stays empty so Auto Connect still refuses`() {
        val prefs = DefaultsFakeSharedPreferences()
        dataUtil(prefs).setAutoModeProtocolPriority(emptyList())

        assertEquals(
            "an empty stored profile means the user disabled every protocol",
            emptyList<AutoModeProtocol>(),
            dataUtil(prefs).getAutoModeProtocolPriority(),
        )
    }

    @Test
    fun `unknown ids are dropped instead of guessed at`() {
        val prefs = DefaultsFakeSharedPreferences()
        prefs.edit()
            .putString(
                DataUtil.SETTING_AUTO_PROTOCOL_PRIORITY,
                "WIREGUARD,SOFTETHER_UDP,SOFTETHER_TCP",
            )
            .apply()

        assertEquals(
            listOf(AutoModeProtocol.SOFTETHER_UDP, AutoModeProtocol.SOFTETHER_TCP),
            dataUtil(prefs).getAutoModeProtocolPriority(),
        )
    }

    // ── PART 2.3: the fixed cache/refresh policy ──────────────────────────

    @Test
    fun `refreshIntervalPolicyIsFixedAndClamped`() {
        val prefs = DefaultsFakeSharedPreferences()
        val util = dataUtil(prefs)

        assertEquals("default is 12 hours", 12, util.getServerListUpdateIntervalHours())
        assertEquals(
            listOf(1, 3, 6, 12, 18, 24),
            util.serverListUpdateIntervalOptions.toList(),
        )

        util.setServerListUpdateIntervalHours(0)
        assertEquals("0 h is clamped up to the 1 h floor", 1, util.getServerListUpdateIntervalHours())

        util.setServerListUpdateIntervalHours(99)
        assertEquals("99 h is clamped down to the 24 h ceiling", 24, util.getServerListUpdateIntervalHours())

        util.setServerListUpdateIntervalHours(6)
        assertEquals(6, util.getServerListUpdateIntervalHours())
    }

    @Test
    fun `a stale cache-time preference from an older install is harmlessly ignored`() {
        val prefs = DefaultsFakeSharedPreferences()
        // Written by a pre-v1.0.54 build; nothing reads it any more.
        prefs.edit().putInt("SETTING_CACHE_TIME_KEY", 3).apply()

        val util = dataUtil(prefs)
        assertEquals("the setting no longer influences anything", 12, util.getServerListUpdateIntervalHours())
        assertEquals(
            listOf(AutoModeProtocol.SOFTETHER_TCP, AutoModeProtocol.SOFTETHER_UDP),
            util.getAutoModeProtocolPriority(),
        )
    }
}

/** In-memory [SharedPreferences]; uniquely named to avoid collisions. */
private class DefaultsFakeSharedPreferences : SharedPreferences {

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

    override fun edit(): SharedPreferences.Editor = DefaultsFakeEditor(values)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
}

private class DefaultsFakeEditor(
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
