package vn.unlimit.vpngate

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.room.Room
import de.blinkt.openvpn.core.OpenVPNService
import vn.unlimit.vpngate.activities.DetailActivity
import vn.unlimit.vpngate.activities.MainActivity
import vn.unlimit.vpngate.db.AppDatabase
import vn.unlimit.vpngate.db.ExcludedAppDao
import vn.unlimit.vpngate.db.VPNGateItemDao
import vn.unlimit.vpngate.models.ExcludedApp
import vn.unlimit.vpngate.utils.AppConfig
import vn.unlimit.vpngate.utils.DataUtil

class App : Application() {
    var dataUtil: DataUtil? = null
        private set
    private lateinit var appDatabase: AppDatabase
    lateinit var vpnGateItemDao: VPNGateItemDao
    lateinit var excludedAppDao: ExcludedAppDao

    override fun onCreate() {
        super.onCreate()
        appDatabase = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "vpn_gate_connector")
            .addMigrations(object : androidx.room.migration.Migration(1, 2) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    // Migration from version 1 to 2: create excluded_apps table
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `excluded_apps` (" +
                                "`packageName` TEXT NOT NULL, " +
                                "`appName` TEXT NOT NULL, " +
                                "PRIMARY KEY(`packageName`))"
                    )
                }
            })
            .addMigrations(AppDatabase.MIGRATION_2_3)
            .addMigrations(AppDatabase.MIGRATION_3_4)
            .allowMainThreadQueries() // Allow main thread queries for VPN profile configuration
            .build()
        vpnGateItemDao = appDatabase.vpnGateItemDao()
        excludedAppDao = appDatabase.excludedAppDao()

        // Initialize default excluded apps
        initializeDefaultExcludedApps()
        instance = this
        dataUtil = DataUtil(this)
        isImportToOpenVPN = AppConfig.getBoolean("vpn_import_open_vpn")

        // First launch language detection: fa if device is Persian, else en
        val savedLang = dataUtil!!.getStringSetting("app_saved_language", null)
        if (savedLang == null) {
            val sysLang = java.util.Locale.getDefault().language
            val initialLang = if (sysLang.equals("fa", ignoreCase = true)) "fa" else "en"
            dataUtil!!.setStringSetting("app_saved_language", initialLang)
            AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.forLanguageTags(initialLang)
            )
        }

        // Apply the user-selected appearance (System / Light / Dark) before any
        // activity is created so the whole app follows Theme.Material3.DayNight.
        // Default theme is Dark (2).
        val themeMode = when (dataUtil!!.getIntSetting(DataUtil.SETTING_THEME, 2)) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            0 -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(themeMode)

        // Route collector logs to logcat, gated by Developer Mode: when the
        // user turns it off, all collector/auto-mode log lines become no-ops
        // for speed (Settings > Developer Mode).
        vn.unlimit.vpngate.data.model.CollectorLog.sink = { Log.d("VpnGateCollector", it) }
        vn.unlimit.vpngate.data.model.CollectorLog.enabled = dataUtil!!.getDeveloperMode()
        vn.unlimit.vpngate.automode.AutoModeLogStore.setPaused(!dataUtil!!.getDeveloperMode())
        // Initialize global VPN connection state tracker
        vn.unlimit.vpngate.state.GlobalVpnTracker.init(this)

        // Make notification open DetailActivity
        OpenVPNService.setNotificationActivityClass(
            if (dataUtil!!.getIntSetting(
                    DataUtil.SETTING_STARTUP_SCREEN,
                    0
                ) == 0
            ) DetailActivity::class.java else MainActivity::class.java
        )
    }

    private fun initializeDefaultExcludedApps() {
        // Always exclude self — hidden from user, cannot be removed
        val selfPackage = ExcludedApp(
            packageName = packageName,
            appName = "Self"
        )
        try {
            val existingSelf = excludedAppDao.isAppExcluded(selfPackage.packageName)
            if (existingSelf == 0) {
                excludedAppDao.insertExcludedApp(selfPackage)
                Log.d(TAG, "Added self as default excluded app")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing self excluded app", e)
        }

        // Add Android Auto as default excluded app
        val androidAuto = ExcludedApp(
            packageName = "com.google.android.projection.gearhead",
            appName = "Android Auto"
        )

        // Check if Android Auto is already added
        try {
            val existing = excludedAppDao.isAppExcluded(androidAuto.packageName)
            if (existing == 0) {
                // First time - add synchronously to ensure it's available immediately
                excludedAppDao.insertExcludedApp(androidAuto)
                Log.d(TAG, "Added Android Auto as default excluded app")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing default excluded apps", e)
            // Try to add on background thread as fallback
            Thread {
                try {
                    val existing = excludedAppDao.isAppExcluded(androidAuto.packageName)
                    if (existing == 0) {
                        excludedAppDao.insertExcludedApp(androidAuto)
                        Log.d(TAG, "Added Android Auto as default excluded app (fallback)")
                    }
                } catch (e2: Exception) {
                    Log.e(TAG, "Error in fallback initialization", e2)
                }
            }.start()
        }
    }

    companion object {
        private const val TAG = "VpnGateApp"

        @JvmStatic
        var instance: App? = null
            private set
        var isImportToOpenVPN: Boolean = false
            private set

        fun getResourceString(resId: Int): String {
            return instance!!.getString(resId)
        }
        // Default VpnProfileCompat mode for openvpn2.4.x compatibility with softether vpn server
        const val VPN_PROFILE_COMPAT_MODE_24X = 20400
    }
}
