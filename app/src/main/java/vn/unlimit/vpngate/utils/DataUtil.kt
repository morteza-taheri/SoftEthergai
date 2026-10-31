package vn.unlimit.vpngate.utils

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager
import com.google.gson.reflect.TypeToken
import com.google.gson.Gson
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.BuildConfig
import vn.unlimit.vpngate.automode.AutoModeProtocol
import vn.unlimit.vpngate.data.persistence.VpnLastConnectionStore
import vn.unlimit.vpngate.models.VPNGateConnection
import vn.unlimit.vpngate.models.VPNGateConnectionList
import java.io.File

/**
 * Manager shared preferences data
 */
class DataUtil(context: Context?) {
    private var mContext: Context? = null
    private var sharedPreferencesSetting: SharedPreferences? = null
    private var gson: Gson? = null

    // v1.0.52 (§1.6.44): the `defaultPreferences` field and the two-argument
    // test seam that injected it are GONE. They existed only so the retired
    // collector-routing preference write could reach both stores and be
    // asserted on a plain JVM; with collector routing now fully automatic there
    // is no second store to inject, and the field had become write-only.
    // [DataUtil]`(sharedPreferences)` below is the only test seam left.

    init {
        try {
            mContext = context
            sharedPreferencesSetting = mContext!!.getSharedPreferences(
                "vpn_setting_data_" + BuildConfig.FLAVOR,
                Context.MODE_PRIVATE
            )
            gson = Gson()
        } catch (e: NullPointerException) {
            e.printStackTrace()
        }
    }

    /**
     * Test seam: build a [DataUtil] over a supplied preferences store.
     *
     * The app module's unit tests run on a plain JVM with no Robolectric, so
     * there is no `Context` to hand to the primary constructor and
     * `getSharedPreferences` cannot be reached. Everything the flag accessors
     * below touch is the preferences store alone, so injecting it makes those
     * accessors testable without pulling in an Android runtime. Production
     * always goes through [DataUtil]`(context)`.
     */
    internal constructor(sharedPreferences: SharedPreferences) : this(null) {
        sharedPreferencesSetting = sharedPreferences
    }

    /**
     * The stored server list, read from the Room dataset.
     *
     * v1.0.54 (§1.6.47) PART 2.3: this used to be a `var` whose setter wrote a
     * legacy on-disk "cache expiry marker" (`CONNECTION_CACHE_KEY`) computed
     * from the removed "Cache save time" setting. Nothing ever read that
     * marker to decide anything — the KDoc of the setter said so explicitly —
     * and no code assigned this property, so it is a `val` now and the marker
     * is gone. Server-list freshness is governed solely by
     * [getServerListUpdateIntervalHours].
     */
    val connectionsCache: VPNGateConnectionList?
        /**
         * Get connection cache from internal Room database
         *
         * @return VPNGateConnectionList
         */
        get() {
            try {
                Log.d(TAG, "get connectionsCache from internal database")
                val app = App.instance
                if (app != null) {
                    val items = app.vpnGateItemDao.getAll()
                    if (items.isNotEmpty()) {
                        Log.d(TAG, "Retrieved ${items.size} servers from internal database")
                        return VPNGateConnectionList().fromVPNGateItems(items)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Got exception when reading server list from database", e)
            }
            return null
        }

    /**
     * v1.0.54 (§1.6.47) PART 2.3: the single manual "Clear cache" action.
     *
     * What it clears — and nothing else:
     *  - the app's transient `cacheDir` (downloaded flag images, HTTP response
     *    cache, and any other throwaway file the platform put there);
     *  - the legacy `CONNECTION_CACHE_KEY` expiry marker, if an older install
     *    left one behind.
     *
     * What it must NEVER touch, by contract (and because it is not reachable
     * from here): the persisted server dataset in Room, imported/manual
     * servers, emergency-source configuration, the excluded-apps table, and
     * every user preference. There is no database access in this function at
     * all.
     *
     * @return true when the cleanup ran to completion (an already-empty cache
     *   is a success, not a failure).
     */
    fun clearTransientCache(): Boolean {
        val ok = clearTransientCache(mContext?.cacheDir, mContext?.filesDir)
        if (!ok) Log.w(TAG, "Clear cache finished with at least one entry it could not delete")
        return ok
    }

    val connectionCacheUpdatedAt: Long
        get() = sharedPreferencesSetting?.getLong(CONNECTION_CACHE_UPDATED_AT_KEY, 0L) ?: 0L

    fun setConnectionCacheUpdatedAt(time: Long) {
        val editor = sharedPreferencesSetting?.edit() ?: return
        editor.putLong(CONNECTION_CACHE_UPDATED_AT_KEY, time)
        editor.apply()
    }

    fun setStringSetting(key: String?, value: String?) {
        val editor = sharedPreferencesSetting!!.edit()
        editor.putString(key, value)
        editor.apply()
    }

    fun getStringSetting(key: String?, defVal: String?): String? {
        return sharedPreferencesSetting!!.getString(key, defVal)
    }

    /**
     * Set int setting value
     *
     * @param key   setting key
     * @param value setting value
     */
    fun setIntSetting(key: String?, value: Int) {
        val editor = sharedPreferencesSetting!!.edit()
        editor.putInt(key, value)
        editor.apply()
    }

    /**
     * Get int setting value
     *
     * @param key    setting key
     * @param defVal default value
     * @return int
     */
    fun getIntSetting(key: String, defVal: Int): Int {
        var retVal = sharedPreferencesSetting!!.getInt(key, defVal)
        if (key == SETTING_HIDE_OPERATOR_MESSAGE_COUNT && retVal > 0) {
            setIntSetting(key, retVal--)
        }
        return retVal
    }

    /**
     * The last used VPN connection, as read from (and written to) the
     * `vpn_last_connection` preference.
     *
     * Stage 2F-D: serialization goes through [VpnLastConnectionStore], never
     * through Gson reflection over [VPNGateConnection]. That class has no
     * `@SerializedName` annotations and no keep rule, so reflecting over it
     * directly made the stored format an R8 artifact — adding `sstpPort` /
     * `sstpPortSource` in Stage 2F-A shifted R8's naming and made every already
     * saved connection unreadable, crashing the Status screen.
     *
     * A payload still in the legacy R8-named format is decoded, validated and
     * immediately rewritten as canonical, so the migration happens in place and
     * only after a successful decode. An unusable payload yields null and is
     * reported; the stored bytes are left untouched rather than overwritten.
     */
    var lastVPNConnection: VPNGateConnection?
        get() {
            try {
                val jsonString = sharedPreferencesSetting!!.getString(
                    VpnLastConnectionStore.PREF_KEY,
                    null
                ) ?: return null
                return when (val result = VpnLastConnectionStore.decode(jsonString)) {
                    is VpnLastConnectionStore.DecodeResult.Decoded -> {
                        if (result.format == VpnLastConnectionStore.Format.LEGACY_R8) {
                            migrateLastConnection(result.connection)
                        }
                        result.connection
                    }

                    is VpnLastConnectionStore.DecodeResult.Invalid -> {
                        Log.w(TAG, "Ignoring unreadable $PREF_LAST_VPN_CONNECTION: ${result.reason}")
                        null
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return null
        }
        set(vpnGateConnection) {
            try {
                if (vpnGateConnection != null) {
                    persistLastVPNConnection(vpnGateConnection)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

    /**
     * Rewrites a just-decoded legacy connection in the canonical format.
     *
     * `commit()` rather than `apply()` on purpose: this is a one-time migration
     * of a single small key, and it must be on disk before the caller goes on
     * to use the connection, so that a process death cannot leave the payload
     * half-migrated.
     */
    private fun migrateLastConnection(connection: VPNGateConnection) {
        if (persistLastVPNConnection(connection)) {
            Log.i(
                TAG,
                "Migrated legacy $PREF_LAST_VPN_CONNECTION to canonical schema " +
                    "${VpnLastConnectionStore.SCHEMA_VERSION}"
            )
        }
    }

    /**
     * Stores [connection] in the canonical format. Returns whether the write
     * reached disk; on failure the previous value is left in place.
     */
    private fun persistLastVPNConnection(connection: VPNGateConnection): Boolean {
        return try {
            sharedPreferencesSetting!!.edit()
                .putString(PREF_LAST_VPN_CONNECTION, VpnLastConnectionStore.encode(connection))
                .commit()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getBooleanSetting(key: String?, defVal: Boolean): Boolean {
        return sharedPreferencesSetting!!.getBoolean(key, defVal)
    }

    fun setBooleanSetting(key: String?, value: Boolean) {
        val editor = sharedPreferencesSetting!!.edit()
        editor.putBoolean(key, value)
        editor.apply()
    }

    /**
     * VpnM Phase 6.3 — the language the user selected, or null when they have
     * not chosen one and the app should follow the system language.
     *
     * The first-run picker is what first writes a value here; until then this
     * returns null, which is also the correct "follow the system" state.
     */
    fun getSelectedLocale(): String? =
        getStringSetting(SETTING_LOCALE, null)?.takeIf { it.isNotBlank() }

    /** Persists the user's language choice. Pass null to follow the system. */
    fun setSelectedLocale(tag: String?) {
        val editor = sharedPreferencesSetting!!.edit()
        if (tag.isNullOrBlank()) editor.remove(SETTING_LOCALE) else editor.putString(SETTING_LOCALE, tag)
        editor.apply()
    }

    /**
     * True until the user has answered the first-run language prompt. Drives
     * whether the picker is shown.
     */
    fun isFirstRun(): Boolean = !getBooleanSetting(SETTING_LOCALE_ANSWERED, false)

    fun markLocaleAnswered() = setBooleanSetting(SETTING_LOCALE_ANSWERED, true)

    /**
     * AUTOCONNECT-DEVICE-BUGS-FIX (BUG-3, spec §1.6.32) — true once the battery
     * optimisation prompt has been shown at least once.
     *
     * The gate used to be the live OS state alone
     * (`isIgnoringBatteryOptimizations`), so a user who declined stayed
     * un-ignored and got the same dialog on every press. The prompt is now
     * one-shot: it fires only while this is false.
     */
    fun hasAskedBatteryOptimization(): Boolean = getBooleanSetting(SETTING_BATTERY_OPT_ASKED, false)

    /** Marks the battery optimisation prompt as shown; see [hasAskedBatteryOptimization]. */
    fun setAskedBatteryOptimization() = setBooleanSetting(SETTING_BATTERY_OPT_ASKED, true)

    // §3 §4 §5 Auto Mode connection timeout per-server (8–30 seconds, default 12)
    //
    // v1.0.46: minimum raised from 5 to 8 seconds (operator requirement).
    // v1.0.52 (§1.6.44) TASK B.2: the default moved 12 -> 8 seconds.
    // v1.0.70 (REQ-TIMEOUT-001): the approved baseline restores the DEFAULT to
    // 12 seconds and lowers the CEILING from 60 to 30, keeping the 8 s floor.
    // Both the reader and the writer clamp, so a value stored by an older build
    // outside 8..30 (e.g. 60) reads back as 30 with no migration, and a stored
    // 5/6/7 reads back as 8.
    fun getAutoModeTimeoutSeconds(): Int =
        getIntSetting(SETTING_AUTO_MODE_TIMEOUT_SECONDS, 12).coerceIn(8, 30)

    fun setAutoModeTimeoutSeconds(seconds: Int) {
        setIntSetting(SETTING_AUTO_MODE_TIMEOUT_SECONDS, seconds.coerceIn(8, 30))
    }

    /**
     * v1.0.46 (§1.6.39) — true once the default emergency source has been
     * seeded into `emergency_source_config`.
     *
     * This flag, not the table contents, is what makes the seed **once only**.
     * A user who deletes the seeded row has made an explicit choice, so an
     * empty table after the flag is set must NOT be re-seeded.
     */
    fun hasSeededEmergencyDefault(): Boolean =
        getBooleanSetting(SETTING_EMERGENCY_DEFAULT_SEEDED, false)

    /** Marks the default emergency source as seeded; see [hasSeededEmergencyDefault]. */
    fun markEmergencyDefaultSeeded() =
        setBooleanSetting(SETTING_EMERGENCY_DEFAULT_SEEDED, true)

    /**
     * R8 (§11.13): whether Auto Mode automatically re-establishes a tunnel
     * after an **unexpected** disconnect of a server Auto Mode itself connected.
     *
     * Default is **ENABLED (true)** per the user-directed requirement
     * (PROJECT_SPECIFICATION.md §11.13.2): a user who started Auto Mode has
     * expressed the intent to stay connected, so a later silent disconnect does
     * not match that intent. A missing key therefore reads as enabled, which is
     * exactly how a fresh install behaves with no write required.
     */
    fun getAutoReconnectInAutoMode(): Boolean =
        getBooleanSetting(SETTING_AUTO_RECONNECT_IN_AUTO_MODE, true)

    fun setAutoReconnectInAutoMode(enabled: Boolean) {
        setBooleanSetting(SETTING_AUTO_RECONNECT_IN_AUTO_MODE, enabled)
    }

    // v1.0.52 (§1.6.44): `getPrefersVpnForCollector()` / `setPrefersVpnForCollector()`
    // were REMOVED here. Collector routing is fully automatic, so there is no
    // user preference left to read or write. The key
    // `"vpn_always_prefer_vpn"` may still exist in an upgraded install's
    // SharedPreferences; it is inert because no code reads it, and no
    // migration is performed (deleting it would be an unrequested data change).

    /**
     * Auto Mode protocol priority profile: ordered ids of ENABLED protocols
     * (priority = list order).
     *
     * v1.0.54 (§1.6.47) PART 2.4 (operator requirement): when **nothing** is
     * stored — a fresh install, or an upgrade that never touched the profile —
     * the default is SoftEther TCP then SoftEther UDP, both enabled, in that
     * order. Before this, an unset profile read as an EMPTY list, and an empty
     * list is the contract for "the user disabled every protocol", so Auto
     * Connect refused to start on a fresh install. The two states are now
     * distinct:
     *
     *  - key absent (`null`) -> [DEFAULT_AUTO_PROTOCOL_PRIORITY];
     *  - key present but empty (`""`) -> empty list, i.e. the user switched
     *    everything off. Auto Connect must never substitute a protocol of its
     *    own, so this stays a refusal.
     *
     * A stored user choice is NEVER overwritten by the default: the default is
     * only the answer to "there is no choice".
     */
    fun getAutoModeProtocolPriority(): List<AutoModeProtocol> =
        parseAutoModeProtocolPriority(getStringSetting(SETTING_AUTO_PROTOCOL_PRIORITY, null))

    fun setAutoModeProtocolPriority(protocols: List<AutoModeProtocol>) {
        setStringSetting(SETTING_AUTO_PROTOCOL_PRIORITY, protocols.joinToString(",") { it.id })
    }

    // SoftEther client concurrent TCP connections (1–8, native MAX_SE_CONNECTIONS).
    //
    // The default mirrors the reference client shipped in-tree
    // (SoftEtherClient): softether_protocol.c initializes
    // `conn->max_connection = 4` and ConnectionConfig.maxConnections defaults
    // to 4, so a fresh install asks for the same 4 connections the native
    // layer would use on its own. Users can still pick any value 1..8.
    fun getSoftEtherMaxConnections(): Int =
        getIntSetting(SETTING_SOFTETHER_MAX_CONNECTIONS, DEFAULT_SOFTETHER_MAX_CONNECTIONS)
            .coerceIn(1, MAX_SOFTETHER_CONNECTIONS)

    fun setSoftEtherMaxConnections(value: Int) {
        setIntSetting(SETTING_SOFTETHER_MAX_CONNECTIONS, value.coerceIn(1, MAX_SOFTETHER_CONNECTIONS))
    }

    fun hasAds(): Boolean {
        // Ads have been fully removed. This always returns false.
        return false
    }

    fun hasProInstalled(): Boolean {
        try {
            val mPm = mContext!!.packageManager // 1
            val info = mPm.getPackageInfo("vn.unlimit.vpngatepro", 0) // 2,3
            return info != null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    fun hasOpenVPNInstalled(): Boolean {
        try {
            val mPm = mContext!!.packageManager // 1
            val info = mPm.getPackageInfo("net.openvpn.openvpn", 0) // 2,3
            return info != null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    val baseUrl: String
        get() {
            try {
                if (sharedPreferencesSetting!!.getBoolean(USE_ALTERNATIVE_SERVER, false)) {
                    return AppConfig.getString("vpn_alternative_api")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return "https://www.vpngate.net"
        }

    fun setUseAlternativeServer(useAlternativeServer: Boolean?) {
        val editor = sharedPreferencesSetting!!.edit()
        editor.putBoolean(USE_ALTERNATIVE_SERVER, useAlternativeServer!!)
        editor.apply()
    }

    fun getCachedMirrors(): List<String> {
        val json = getStringSetting("KEY_CACHED_MIRRORS", null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<String>>() {}.type
            gson?.fromJson<List<String>>(json, type).orEmpty()
        }.getOrDefault(emptyList())
    }

    fun setCachedMirrors(mirrors: List<String>) {
        if (mirrors.isEmpty()) return
        val json = gson?.toJson(mirrors.take(15)) ?: return
        setStringSetting("KEY_CACHED_MIRRORS", json)
    }

    // Developer Mode: on = keep all diagnostic logging; off = suppress every
    // collector/auto-mode log line for speed (logcat writes, logcat tap and the
    // Auto Mode log window). A fresh install starts OFF; the user's choice is
    // persisted so it survives restarts.
    fun getDeveloperMode(): Boolean = getBooleanSetting(SETTING_DEVELOPER_MODE, DEFAULT_DEVELOPER_MODE)

    // How often the server list may refresh itself in the background.
    //
    // The value is stored in hours and is always clamped: anything outside
    // 1..24 is corrected on read *and* on write, so a corrupted or
    // hand-edited preference can never produce a 0-minute update loop (which
    // would hammer the upstream API) or a multi-day stale list.
    //
    // 12 hours is the default and matches the cache-time default the app
    // already shipped with, so a fresh install behaves as before.
    fun getServerListUpdateIntervalHours(): Int =
        getIntSetting(
            SETTING_SERVER_LIST_UPDATE_INTERVAL_HOURS,
            DEFAULT_SERVER_LIST_UPDATE_INTERVAL_HOURS,
        ).coerceIn(MIN_SERVER_LIST_UPDATE_INTERVAL_HOURS, MAX_SERVER_LIST_UPDATE_INTERVAL_HOURS)

    fun setServerListUpdateIntervalHours(hours: Int) {
        setIntSetting(
            SETTING_SERVER_LIST_UPDATE_INTERVAL_HOURS,
            hours.coerceIn(MIN_SERVER_LIST_UPDATE_INTERVAL_HOURS, MAX_SERVER_LIST_UPDATE_INTERVAL_HOURS),
        )
    }

    /**
     * BLOCK v1.0.75 FIX — the size of the last ACCEPTED fresh network dataset.
     *
     * This is the only honest baseline for the size-collapse check: the stored
     * table is a RETENTION union (up to 500 rows, including rows an emergency
     * merge added), while a live feed is ~100 servers. Comparing the two
     * rejected every refresh on the device (400 vs ~100, floor 50 %).
     * 0 means "unknown" — the check then falls back to the stored dataset.
     */
    fun getLastAcceptedDatasetSize(): Int =
        getIntSetting(SETTING_LAST_ACCEPTED_DATASET_SIZE, 0).coerceAtLeast(0)

    fun setLastAcceptedDatasetSize(size: Int) {
        setIntSetting(SETTING_LAST_ACCEPTED_DATASET_SIZE, size.coerceAtLeast(0))
    }

    // ── BLOCK v1.0.77 — REQ-ORIGIN-001: PERSISTED CONNECTION ORIGIN ───────
    //
    // D-3 already labels a live tunnel with why it is up, but the label lived
    // only in the controller's memory (`runOrigin`). If the process is killed
    // while the tunnel stays up — the OS does this routinely under memory
    // pressure, and the VPN service survives — the label degraded to MANUAL,
    // which is exactly the "app did not start this" reading D-3 forbids for a
    // tunnel the app did start. These three keys persist (origin, server key)
    // at the moment a tunnel is verified and are cleared on an explicit
    // disconnect. A reader MUST validate the stored key against the live
    // tunnel before trusting the origin (see AutoModeViewModel).

    /** Origin of the tunnel that was up at the last verified connect; null when unknown. */
    fun getLastConnectionOrigin(): vn.unlimit.vpngate.automode.ConnectionOrigin? =
        when (getStringSetting(SETTING_LAST_CONNECTION_ORIGIN, null)) {
            vn.unlimit.vpngate.automode.ConnectionOrigin.AUTO_CONNECT.name ->
                vn.unlimit.vpngate.automode.ConnectionOrigin.AUTO_CONNECT
            vn.unlimit.vpngate.automode.ConnectionOrigin.AUTO_RECONNECT.name ->
                vn.unlimit.vpngate.automode.ConnectionOrigin.AUTO_RECONNECT
            vn.unlimit.vpngate.automode.ConnectionOrigin.MANUAL.name ->
                vn.unlimit.vpngate.automode.ConnectionOrigin.MANUAL
            else -> null
        }

    /**
     * Stable `ip|hostname` key of the server behind [getLastConnectionOrigin].
     * Empty when nothing is recorded. A reader compares it with the live
     * tunnel's identity so a stale record can never mislabel a different tunnel.
     */
    fun getLastConnectionServerKey(): String =
        getStringSetting(SETTING_LAST_CONNECTION_SERVER_KEY, "") ?: ""

    fun setLastConnectionOrigin(
        origin: vn.unlimit.vpngate.automode.ConnectionOrigin,
        serverKey: String,
    ) {
        sharedPreferencesSetting?.edit()
            ?.putString(SETTING_LAST_CONNECTION_ORIGIN, origin.name)
            ?.putString(SETTING_LAST_CONNECTION_SERVER_KEY, serverKey)
            ?.apply()
    }

    /** An explicit disconnect ends the record: no origin outlives its tunnel. */
    fun clearLastConnectionOrigin() {
        sharedPreferencesSetting?.edit()
            ?.remove(SETTING_LAST_CONNECTION_ORIGIN)
            ?.remove(SETTING_LAST_CONNECTION_SERVER_KEY)
            ?.apply()
    }

    // ── BLOCK v1.0.76 — APP UPDATE CHECKER ───────────────────────────────

    /** Last automatic update check (epoch ms; 0 = never checked). */
    fun getAppUpdateLastCheckAt(): Long =
        sharedPreferencesSetting?.getLong(SETTING_APP_UPDATE_LAST_CHECK_AT, 0L) ?: 0L

    fun setAppUpdateLastCheckAt(at: Long) {
        sharedPreferencesSetting?.edit()?.putLong(SETTING_APP_UPDATE_LAST_CHECK_AT, at)?.apply()
    }

    /** The newest version code the user has already been notified about (0 = none). */
    fun getAppUpdateDismissedCode(): Int = getIntSetting(SETTING_APP_UPDATE_DISMISSED_CODE, 0)

    fun setAppUpdateDismissedCode(code: Int) =
        setIntSetting(SETTING_APP_UPDATE_DISMISSED_CODE, code)

    /** The selectable values, in hours. Index 0 is the default. */
    val serverListUpdateIntervalOptions: IntArray
        get() = intArrayOf(1, 3, 6, 12, 18, 24, 168)

    fun setDeveloperMode(enabled: Boolean) {
        setBooleanSetting(SETTING_DEVELOPER_MODE, enabled)
    }

    // v1.0.54 (§1.6.47) PART 2.3: `getCacheSaveTimeMinutes()` and the
    // "Cache save time" preference are REMOVED. The value only ever fed the
    // legacy on-disk expiry marker (see [clearTransientCache]), which no code
    // path consulted; server-list freshness has always been decided by
    // [getServerListUpdateIntervalHours]. The fixed policy is therefore: the
    // list refreshes on the configured 1..24 h interval (default 12 h) and on
    // an explicit user refresh, and the only manual cache control is
    // [clearTransientCache]. A `SETTING_CACHE_TIME_KEY` left behind by an
    // older install is inert — nothing reads it — and is deliberately not
    // deleted (removing user data was not requested).

    companion object {
        const val TAG = "DataUtil"

    /** Preference key holding the last used VPN connection. See VpnLastConnectionStore. */
    private const val PREF_LAST_VPN_CONNECTION = VpnLastConnectionStore.PREF_KEY
        const val SETTING_DEVELOPER_MODE: String = "SETTING_DEVELOPER_MODE"

        const val SETTING_HIDE_OPERATOR_MESSAGE_COUNT: String =
            "SETTING_HIDE_OPERATOR_MESSAGE_COUNT"
        const val USER_ALLOWED_VPN: String = "USER_ALLOWED_VPN"
        const val LAST_CONNECT_USE_UDP: String = "LAST_CONNECT_USE_UDP"
        const val LAST_CONNECT_SOFTETHER_USE_UDP: String = "LAST_CONNECT_SOFTETHER_USE_UDP"
        const val LAST_CONNECT_METHOD: String = "LAST_CONNECT_METHOD"
        const val USE_CUSTOM_DNS: String = "USE_CUSTOM_DNS"
        const val CUSTOM_DNS_IP_1: String = "CUSTOM_DNS_IP_1"
        const val CUSTOM_DNS_IP_2: String = "CUSTOM_DNS_IP_2"

        /** Id of the selected [DnsPresets] entry (or [DnsPresets.ID_CUSTOM]). */
        const val CUSTOM_DNS_PRESET: String = "CUSTOM_DNS_PRESET"
        const val USE_DOMAIN_TO_CONNECT: String = "USE_DOMAIN_TO_CONNECT"
        const val SETTING_STARTUP_SCREEN: String = "SETTING_STARTUP_SCREEN"
        const val SETTING_THEME: String = "SETTING_THEME"

    /**
     * VpnM Phase 6.3 — the language the user picked. Empty / absent means
     * "follow the system language", which is also the state a fresh install
     * starts in before the first-run picker is answered.
     *
     * Holds an IETF language tag ("en", "fa", "vi") suitable for
     * AppCompatDelegate.setApplicationLocales.
     */
    const val SETTING_LOCALE: String = "SETTING_LOCALE"

    /** VpnM Phase 6.3 — set once the user answers the first-run language prompt. */
    const val SETTING_LOCALE_ANSWERED: String = "SETTING_LOCALE_ANSWERED"

    /**
     * AUTOCONNECT-DEVICE-BUGS-FIX (BUG-3, spec §1.6.32) — set once the battery
     * optimisation prompt has been shown, so it does not fire on every press.
     */
    const val SETTING_BATTERY_OPT_ASKED: String = "SETTING_BATTERY_OPT_ASKED"
        const val SETTING_NOTIFY_SPEED: String = "SETTING_NOTIFY_SPEED"
        const val SETTING_AUTO_PROTOCOL_PRIORITY: String = "SETTING_AUTO_PROTOCOL_PRIORITY"
        const val SETTING_AUTO_MODE_TIMEOUT_SECONDS: String = "SETTING_AUTO_MODE_TIMEOUT_SECONDS"

        /**
         * v1.0.54 (§1.6.47) PART 2.4: the profile a fresh install starts with.
         * v1.0.70 (REQ-PROTO-001): the approved order is SoftEther UDP first,
         * then SoftEther TCP, then Microsoft SSTP. It is an ORDER, so the first
         * entry is attempted first on every server; a stored user choice always
         * wins over this default.
         */
        val DEFAULT_AUTO_PROTOCOL_PRIORITY: List<AutoModeProtocol> = listOf(
            AutoModeProtocol.SOFTETHER_UDP,
            AutoModeProtocol.SOFTETHER_TCP,
            AutoModeProtocol.MS_SSTP,
        )

        /**
         * The one place the stored protocol-priority string is interpreted.
         *
         * `null` means "never stored" and yields the default; `""` means "the
         * user enabled nothing" and yields an empty list. Unknown ids are
         * dropped rather than guessed at (the same rule
         * [AutoModeProtocol.fromId] enforces), so a value written by a future
         * build cannot silently become a protocol this build can dial.
         */
        internal fun parseAutoModeProtocolPriority(raw: String?): List<AutoModeProtocol> {
            if (raw == null) return DEFAULT_AUTO_PROTOCOL_PRIORITY
            return raw.split(',')
                .mapNotNull { id -> AutoModeProtocol.fromId(id.trim()) }
        }

        /**
         * v1.0.46 (§1.6.39): set once the default emergency source URL from
         * `R.string.default_emergency_source_url` has been written into
         * `emergency_source_config`. See [hasSeededEmergencyDefault].
         */
        internal const val SETTING_EMERGENCY_DEFAULT_SEEDED: String =
            "SETTING_EMERGENCY_DEFAULT_SEEDED"

        /**
         * R8 (§11.13): Auto-Reconnect in Auto Mode. Default ENABLED — see
         * [getAutoReconnectInAutoMode].
         */
        const val SETTING_AUTO_RECONNECT_IN_AUTO_MODE: String = "SETTING_AUTO_RECONNECT_IN_AUTO_MODE"
        const val SETTING_SOFTETHER_MAX_CONNECTIONS: String = "SETTING_SOFTETHER_MAX_CONNECTIONS"

        /**
         * Default for "Enable speed/statistics in notify".
         *
         * A fresh install ships ON, so the user sees throughput/latency in the
         * notification without having to find the toggle first. Once the user
         * touches the switch the stored value wins and this default is no
         * longer consulted, so turning it OFF sticks across restarts.
         */
        const val DEFAULT_NOTIFY_SPEED: Boolean = true

        /**
         * Default target number of concurrent SoftEther TCP connections.
         *
         * Taken from the reference client in this repository
         * (SoftEtherClient): `softether_protocol.c` initializes
         * `conn->max_connection = 4`, and `ConnectionConfig.maxConnections`
         * defaults to 4 — so 4 is what the native layer uses when the app does
         * not override it. Hard upper bound is the native `MAX_SE_CONNECTIONS`.
         */
        const val DEFAULT_SOFTETHER_MAX_CONNECTIONS: Int = 4
        const val MAX_SOFTETHER_CONNECTIONS: Int = 8

        /** Developer Mode (verbose logging) ships disabled on a fresh install. */
        const val DEFAULT_DEVELOPER_MODE: Boolean = false

        const val SETTING_SERVER_LIST_UPDATE_INTERVAL_HOURS: String =
            "SETTING_SERVER_LIST_UPDATE_INTERVAL_HOURS"

        /** BLOCK v1.0.75 FIX: size of the last accepted fresh dataset (0 = unknown). */
        const val SETTING_LAST_ACCEPTED_DATASET_SIZE: String =
            "SETTING_LAST_ACCEPTED_DATASET_SIZE"

        /** BLOCK v1.0.77 (REQ-ORIGIN-001): persisted origin of the live tunnel. */
        const val SETTING_LAST_CONNECTION_ORIGIN: String =
            "SETTING_LAST_CONNECTION_ORIGIN"

        /** BLOCK v1.0.77 (REQ-ORIGIN-001): `ip|hostname` the origin belongs to. */
        const val SETTING_LAST_CONNECTION_SERVER_KEY: String =
            "SETTING_LAST_CONNECTION_SERVER_KEY"

        /** BLOCK v1.0.76: last automatic update check (epoch ms). */
        const val SETTING_APP_UPDATE_LAST_CHECK_AT: String =
            "SETTING_APP_UPDATE_LAST_CHECK_AT"

        /** BLOCK v1.0.76: newest version code the user was notified about. */
        const val SETTING_APP_UPDATE_DISMISSED_CODE: String =
            "SETTING_APP_UPDATE_DISMISSED_CODE"

        /** Server List Update Interval: default 12 hours, hard bounds 1..24. */
        const val DEFAULT_SERVER_LIST_UPDATE_INTERVAL_HOURS: Int = 168
        const val MIN_SERVER_LIST_UPDATE_INTERVAL_HOURS: Int = 1
        const val MAX_SERVER_LIST_UPDATE_INTERVAL_HOURS: Int = 168
        const val AUTO_LAST_SUCCESS_HOST: String = "AUTO_LAST_SUCCESS_HOST"
        const val AUTO_LAST_SUCCESS_PROTOCOL: String = "AUTO_LAST_SUCCESS_PROTOCOL"
        private const val USE_ALTERNATIVE_SERVER = "USE_ALTERNATIVE_SERVER"
        const val CONNECTION_CACHE_KEY = "CONNECTION_CACHE_KEY"
        const val CONNECTION_CACHE_UPDATED_AT_KEY = "CONNECTION_CACHE_UPDATED_AT_KEY"

        /**
         * v1.0.55 (`V1054_VERIFY_REPORT` G-1): the pure half of
         * [clearTransientCache], taking the two directories explicitly.
         *
         * It exists because the contract this function has to keep — *"delete
         * the transient cache and the legacy marker, and nothing else"* — was
         * asserted in a KDoc comment and nowhere else. The instance method needs
         * a `Context`, and this module's unit tests run on a plain JVM with no
         * Robolectric, so the contract could not be tested at all. Taking
         * `cacheDir` and `filesDir` as arguments makes it testable against two
         * real temp directories, and it is the same code production runs.
         *
         * Deletes:
         *  - every child of [cacheDir] (the directory itself is kept — it is
         *    the platform's cache location and removing it would be a change of
         *    ownership, not a cleanup);
         *  - `<filesDir>/CONNECTION_CACHE_KEY`, the legacy expiry marker only.
         *
         * Never deletes: any other entry in [filesDir] — which is where
         * `manual_import_servers.json`, the Room database and every other
         * durable file live — and nothing in this function touches a database,
         * a DAO or a preference.
         *
         * @return false when something could not be deleted; an absent directory
         *   or an already-clean cache is a success, not a failure.
         */
        internal fun clearTransientCache(cacheDir: File?, filesDir: File?): Boolean {
            var ok = true
            try {
                cacheDir?.listFiles()?.forEach { child ->
                    if (!child.deleteRecursively()) ok = false
                }
            } catch (e: Exception) {
                ok = false
            }
            try {
                val legacy = filesDir?.let { File(it, CONNECTION_CACHE_KEY) }
                if (legacy != null && legacy.isFile && !legacy.delete()) ok = false
            } catch (e: Exception) {
                ok = false
            }
            return ok
        }

        /**
         * Check device connect to a network or not
         *
         * @param context
         * @return connect to network result
         */
        @JvmStatic
        fun isOnline(context: Context): Boolean {
            var result = false
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cm?.run {
                    cm.getNetworkCapabilities(cm.activeNetwork)?.run {
                        result = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                                hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                                hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                                hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    }
                }
            } else {
                cm?.run {
                    @Suppress("DEPRECATION")
                    cm.activeNetworkInfo?.run {
                        result = isConnected
                    }
                }
            }
            return result
        }
    }
}
