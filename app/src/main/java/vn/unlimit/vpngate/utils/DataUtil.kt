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
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.BuildConfig
import vn.unlimit.vpngate.automode.AutoModeProtocol
import vn.unlimit.vpngate.data.persistence.VpnLastConnectionStore
import vn.unlimit.vpngate.models.Cache
import vn.unlimit.vpngate.models.VPNGateConnection
import vn.unlimit.vpngate.models.VPNGateConnectionList
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.Date

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

    var connectionsCache: VPNGateConnectionList?
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
         * Legacy cache marker only.
         *
         * Assigning this property no longer writes server data. Persisting a
         * dataset is the job of
         * [vn.unlimit.vpngate.repository.ServerListUpdater] and of nothing
         * else, so a server list can only reach the database after it passed
         * dataset integrity — through one writer, under one mutex. What is
         * left here is the historical on-disk cache-expiry marker, which no
         * code path uses to decide anything.
         */
        set(value) {
            try {
                val cache = Cache()
                val calendar = Calendar.getInstance()
                val minute = getCacheSaveTimeMinutes()
                if (minute < 0) {
                    calendar.set(Calendar.YEAR, 9999)
                } else {
                    calendar.add(Calendar.MINUTE, minute)
                }
                cache.expires = calendar.time
                val outFile = File(mContext!!.filesDir, CONNECTION_CACHE_KEY)
                val out = FileOutputStream(outFile)
                val writer = JsonWriter(OutputStreamWriter(out, StandardCharsets.UTF_8))
                gson!!.toJson(cache, Cache::class.java, writer)
                writer.close()
                setConnectionCacheExpire(cache.expires)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

    /**
     * Records that the on-disk cache file was just rewritten from a freshly
     * downloaded list, so its expiry is relative to *now* rather than to
     * whenever the list was last downloaded.
     */
    fun markConnectionsCacheFresh() {
        try {
            val cache = Cache()
            val calendar = Calendar.getInstance()
            val minute = getCacheSaveTimeMinutes()
            if (minute < 0) {
                calendar.set(Calendar.YEAR, 9999)
            } else {
                calendar.add(Calendar.MINUTE, minute)
            }
            cache.expires = calendar.time
            val outFile = File(mContext!!.filesDir, CONNECTION_CACHE_KEY)
            FileOutputStream(outFile).use { out ->
                JsonWriter(OutputStreamWriter(out, StandardCharsets.UTF_8)).use { writer ->
                    gson!!.toJson(cache, Cache::class.java, writer)
                }
            }
            setConnectionCacheExpire(cache.expires)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Clear connection cache
     *
     * @return boolean
     */
    fun clearConnectionCache(): Boolean {
        val inFile = File(mContext!!.filesDir, CONNECTION_CACHE_KEY)
        return inFile.isFile && inFile.delete()
    }

    private fun setConnectionCacheExpire(expires: Date?) {
        val editor = sharedPreferencesSetting!!.edit()
        editor.putString("vpn_cache_time", gson!!.toJson(expires))
        editor.apply()
    }

    val connectionCacheExpires: Date?
        /**
         * Get connection cache from shared preferences
         *
         * @return
         */
        get() {
            try {
                val jsonString =
                    sharedPreferencesSetting!!.getString("vpn_cache_time", null) ?: return null
                return gson!!.fromJson(jsonString, Date::class.java)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return null
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

    // §3 §4 §5 Auto Mode connection timeout per-server (8–60 seconds, default 8)
    //
    // v1.0.46: minimum raised from 5 to 8 seconds (operator requirement).
    // v1.0.52 (§1.6.44) TASK B.2: the DEFAULT moves 12 -> 8 seconds, so the
    // default is now the floor: a fresh install gives each protocol attempt
    // 8 s, and Auto Connect walks protocols and servers faster. The maximum
    // (60) is unchanged. Both the reader and the writer clamp, so a value
    // stored by an older build at 5/6/7 reads back as 8 and any stored value
    // in 8..60 keeps working untouched.
    fun getAutoModeTimeoutSeconds(): Int =
        getIntSetting(SETTING_AUTO_MODE_TIMEOUT_SECONDS, 8).coerceIn(8, 60)

    fun setAutoModeTimeoutSeconds(seconds: Int) {
        setIntSetting(SETTING_AUTO_MODE_TIMEOUT_SECONDS, seconds.coerceIn(8, 60))
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
     * An empty result means the user enabled no protocol, and Auto Connect
     * refuses to run — it must never substitute a protocol of its own. New
     * protocols are picked up automatically — nothing is hard-coded here
     * (§32 §33).
     */
    fun getAutoModeProtocolPriority(): List<AutoModeProtocol> =
        getStringSetting(SETTING_AUTO_PROTOCOL_PRIORITY, null)
            ?.split(',')
            ?.mapNotNull { id ->
                AutoModeProtocol.entries.firstOrNull { it.id == id.trim() }
            }
            .orEmpty()

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

    /** The selectable values, in hours. Index 0 is the default. */
    val serverListUpdateIntervalOptions: IntArray
        get() = intArrayOf(1, 3, 6, 12, 18, 24)

    fun setDeveloperMode(enabled: Boolean) {
        setBooleanSetting(SETTING_DEVELOPER_MODE, enabled)
    }

    // Cache Save Time: values align 1:1 with R.array.setting_cache_time;
    // -1 means "Never" (the on-disk cache never expires on its own).
    fun getCacheSaveTimeMinutes(): Int {
        val minutes = intArrayOf(15, 30, 60, 120, 240, 360, 720, 1440, -1)
        val index = getIntSetting(SETTING_CACHE_TIME_KEY, DEFAULT_CACHE_TIME_INDEX)
        return minutes.getOrElse(index) { minutes[DEFAULT_CACHE_TIME_INDEX] }
    }

    companion object {
        const val TAG = "DataUtil"

    /** Preference key holding the last used VPN connection. See VpnLastConnectionStore. */
    private const val PREF_LAST_VPN_CONNECTION = VpnLastConnectionStore.PREF_KEY
        const val SETTING_CACHE_TIME_KEY: String = "SETTING_CACHE_TIME_KEY"
        const val SETTING_DEVELOPER_MODE: String = "SETTING_DEVELOPER_MODE"

        /** Index of "24 hours" in the cache-time array — the default. */
        const val DEFAULT_CACHE_TIME_INDEX = 7
        const val SETTING_HIDE_OPERATOR_MESSAGE_COUNT: String =
            "SETTING_HIDE_OPERATOR_MESSAGE_COUNT"
        const val USER_ALLOWED_VPN: String = "USER_ALLOWED_VPN"
        const val SETTING_BLOCK_ADS: String = "SETTING_BLOCK_ADS"
        const val INCLUDE_UDP_SERVER: String = "INCLUDE_UDP_SERVER"
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

        /** Server List Update Interval: default 12 hours, hard bounds 1..24. */
        const val DEFAULT_SERVER_LIST_UPDATE_INTERVAL_HOURS: Int = 12
        const val MIN_SERVER_LIST_UPDATE_INTERVAL_HOURS: Int = 1
        const val MAX_SERVER_LIST_UPDATE_INTERVAL_HOURS: Int = 24
        const val AUTO_LAST_SUCCESS_HOST: String = "AUTO_LAST_SUCCESS_HOST"
        const val AUTO_LAST_SUCCESS_PROTOCOL: String = "AUTO_LAST_SUCCESS_PROTOCOL"
        private const val USE_ALTERNATIVE_SERVER = "USE_ALTERNATIVE_SERVER"
        const val CONNECTION_CACHE_KEY = "CONNECTION_CACHE_KEY"
        const val CONNECTION_CACHE_UPDATED_AT_KEY = "CONNECTION_CACHE_UPDATED_AT_KEY"

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
