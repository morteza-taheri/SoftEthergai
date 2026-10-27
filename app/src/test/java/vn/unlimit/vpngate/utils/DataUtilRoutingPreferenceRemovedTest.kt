package vn.unlimit.vpngate.utils

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v1.0.52 (§1.6.44) TASK A — the **removal** of the manual collector-routing
 * opt-in, and the proof that a stale stored value is inert.
 *
 * This file REPLACES `DataUtilPrefersVpnTest.kt` (T-NEW-46..49, T-NEW-54a..d).
 * Those tests pinned behaviour that this phase deletes on purpose:
 * `DataUtil.getPrefersVpnForCollector()` / `setPrefersVpnForCollector()`, the
 * two-store write that made the Settings toggle reach
 * `GlobalVpnTracker.prefersVpnForCollector()`, and the key literal
 * `vpn_always_prefer_vpn` as a live read path. Deleting them silently would
 * have left the removal unpinned, so each one is replaced by a test of the new
 * state rather than dropped.
 *
 * Two kinds of assertion, both needed:
 *  - **Compile-time** — this file cannot reference the removed accessors at
 *    all, so its very existence is a proof they are gone.
 *  - **Source-shape** — the three other places the switch touched (the
 *    `GlobalVpnTracker` reader and constant, the Settings row, the string
 *    resources) are asserted clean, because a JVM test cannot load them.
 *
 * Plus one behavioural test: a preferences store that still contains the old
 * key must change nothing. That is the actual upgrade path — millions of
 * installed users have that key on disk, and the requirement is that it is
 * simply never read again rather than migrated away.
 *
 * Uses [DataUtil]'s `internal constructor(sharedPreferences)` seam, so no
 * Android runtime is needed.
 */
class DataUtilRoutingPreferenceRemovedTest {

    // ── source-shape: the symbols are gone from every file that held them ──

    private fun sourceOf(relativePath: String): String {
        for (path in listOf(relativePath, "../$relativePath", "app/$relativePath")) {
            val file = File(path)
            // Normalise CRLF: the repo checks out CRLF on Windows.
            if (file.isFile) return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
        }
        error("source file not found: $relativePath (cwd=${File(".").absolutePath})")
    }

    @Test
    fun `T-REMOVED-01 DataUtil no longer exposes the collector routing accessors`() {
        val source = sourceOf("src/main/java/vn/unlimit/vpngate/utils/DataUtil.kt")

        assertFalse(
            "getPrefersVpnForCollector must be gone",
            source.contains("fun getPrefersVpnForCollector"),
        )
        assertFalse(
            "setPrefersVpnForCollector must be gone",
            source.contains("fun setPrefersVpnForCollector"),
        )
        // The two-store seam existed only for that write and is gone with it.
        assertFalse(
            "the two-store test seam must be gone too",
            source.contains("defaultPreferences: SharedPreferences,"),
        )
        assertFalse(
            "the write-only field must not linger",
            source.contains("private var defaultPreferences"),
        )
    }

    @Test
    fun `T-REMOVED-02 GlobalVpnTracker no longer reads or declares the preference`() {
        val source = sourceOf("src/main/java/vn/unlimit/vpngate/state/GlobalVpnTracker.kt")

        assertFalse(
            "prefersVpnForCollector() must be gone",
            source.contains("fun prefersVpnForCollector"),
        )
        assertFalse(
            "the PREF_ALWAYS_PREFER_VPN constant must be gone",
            source.contains("const val PREF_ALWAYS_PREFER_VPN"),
        )
        // The constant's value may still be named in an explanatory comment;
        // what must not survive is a declaration or a read.
        assertFalse(
            "nothing may read the key literal any more",
            source.contains("getBoolean(PREF_ALWAYS_PREFER_VPN"),
        )
    }

    @Test
    fun `T-REMOVED-03 the Settings switch and its strings are gone`() {
        val settings = sourceOf(
            "src/main/java/vn/unlimit/vpngate/ui/screens/settings/SettingsScreen.kt",
        )
        assertFalse(
            "SettingsScreen must not keep the collector-routing switch state",
            settings.contains("prefersVpn"),
        )

        val strings = sourceOf("src/main/res/values/strings.xml")
        assertFalse(
            "the switch label resource must be gone",
            strings.contains("setting_prefer_vpn_collector_label"),
        )
        assertFalse(
            "the switch summary resource must be gone",
            strings.contains("setting_prefer_vpn_collector_summary"),
        )
    }

    @Test
    fun `T-REMOVED-04 the automatic reconnection switch moved to Auto Connect`() {
        // TASK B.1 moved this row out of Settings. Both halves are asserted so
        // the row cannot exist in two places (two switches, one preference) or
        // in neither (the setting becomes unreachable).
        val settings = sourceOf(
            "src/main/java/vn/unlimit/vpngate/ui/screens/settings/SettingsScreen.kt",
        )
        assertFalse(
            "Settings must no longer host the automatic-reconnection switch",
            settings.contains("getAutoReconnectInAutoMode"),
        )

        val autoMode = sourceOf(
            "src/main/java/vn/unlimit/vpngate/ui/screens/automode/AutoModeScreen.kt",
        )
        assertTrue(
            "AutoModeScreen must host the automatic-reconnection switch",
            autoMode.contains("getAutoReconnectInAutoMode"),
        )
        assertTrue(
            "the switch must be settable from AutoModeScreen",
            autoMode.contains("setAutoReconnectInAutoMode"),
        )
    }

    // ── behavioural: a stale stored value is inert ─────────────────────────

    @Test
    fun `T-REMOVED-05 a stale stored preference changes no DataUtil reading`() {
        // A store as an upgraded install has it: the old key still on disk.
        val withStaleKey = RoutingPrefsFakeSharedPreferences().apply {
            edit().putBoolean("vpn_always_prefer_vpn", true).apply()
        }
        val withoutKey = RoutingPrefsFakeSharedPreferences()

        // Every surviving reading must be identical, because nothing consults
        // the stale key. If a future change starts reading it again, at least
        // one of these diverges.
        assertEquals(
            "the auto-mode timeout must not depend on the stale routing key",
            DataUtil(withoutKey).getAutoModeTimeoutSeconds(),
            DataUtil(withStaleKey).getAutoModeTimeoutSeconds(),
        )
        assertEquals(
            "the auto-reconnect default must not depend on the stale routing key",
            DataUtil(withoutKey).getAutoReconnectInAutoMode(),
            DataUtil(withStaleKey).getAutoReconnectInAutoMode(),
        )
        assertEquals(
            "the default timeout is 12 s regardless of the stale key (REQ-TIMEOUT-001)",
            12,
            DataUtil(withStaleKey).getAutoModeTimeoutSeconds(),
        )
    }
}

/**
 * Minimal in-memory [SharedPreferences]. Named uniquely so it cannot collide
 * with the other top-level private fakes in this package
 * (`TimeoutFakeSharedPreferences`, `BatteryFakeSharedPreferences`, …).
 */
private class RoutingPrefsFakeSharedPreferences : SharedPreferences {

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

    override fun edit(): SharedPreferences.Editor = RoutingPrefsFakeEditor(values)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
}

private class RoutingPrefsFakeEditor(
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
