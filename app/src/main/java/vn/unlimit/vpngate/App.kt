package vn.unlimit.vpngate

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.room.Room
import de.blinkt.openvpn.core.OpenVPNService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import vn.unlimit.vpngate.activities.DetailActivity
import vn.unlimit.vpngate.activities.MainActivity
import vn.unlimit.vpngate.data.remote.EmergencySourceConfigDao
import vn.unlimit.vpngate.data.remote.EmergencySourceConfigEntity
import vn.unlimit.vpngate.data.remote.ManualServerDao
import vn.unlimit.vpngate.data.remote.SourceKind
import vn.unlimit.vpngate.data.remote.SourceUrl
import vn.unlimit.vpngate.db.AppDatabase
import vn.unlimit.vpngate.db.ExcludedAppDao
import vn.unlimit.vpngate.db.VPNGateItemDao
import vn.unlimit.vpngate.history.NetworkTypeClassifier
import vn.unlimit.vpngate.history.RoomServerHistorySink
import vn.unlimit.vpngate.history.ServerHistoryDao
import vn.unlimit.vpngate.history.ServerHistorySink
import vn.unlimit.vpngate.models.ExcludedApp
import vn.unlimit.vpngate.utils.AppConfig
import vn.unlimit.vpngate.utils.DataUtil
import vn.unlimit.vpngate.utils.ExcludedAppsSeeder

class App : Application() {
    var dataUtil: DataUtil? = null
        private set
    internal lateinit var appDatabase: AppDatabase
    lateinit var vpnGateItemDao: VPNGateItemDao
    lateinit var excludedAppDao: ExcludedAppDao
    lateinit var emergencySourceConfigDao: EmergencySourceConfigDao

    // Z1-IMPL-D (Roadmap §12.5.2 item D): manually entered servers.
    lateinit var manualServerDao: ManualServerDao

    /**
     * F.29: durable server history.
     *
     * Exposed as its own field (not reached through [vpnGateItemDao]) so a
     * history read can never be folded into the same transaction as
     * `replaceAll`'s `deleteAll()`.
     */
    lateinit var serverHistoryDao: ServerHistoryDao

    /**
     * F.29: the single writer every Auto Mode run records through.
     *
     * Constructed once here so there is exactly one sink per process; a
     * second one would still be safe (the DAO transaction is the real
     * guarantee), but there is no reason for two.
     */
    lateinit var serverHistorySink: ServerHistorySink
        private set

    /**
     * v1.0.54 (§1.6.49) PART 4: servers imported from a CSV file or pasted text.
     *
     * A JSON file rather than a Room table on purpose — see
     * [vn.unlimit.vpngate.repository.ManualImportStore] for why the
     * `manual_server` schema cannot hold a CSV row and why this phase may not
     * widen it. Null only if `filesDir` is somehow unavailable, in which case
     * import is simply unavailable rather than crashing.
     */
    val manualImportStore: vn.unlimit.vpngate.repository.ManualImportStore? by lazy {
        runCatching {
            vn.unlimit.vpngate.repository.ManualImportStore(
                java.io.File(filesDir, "manual_import_servers.json"),
            )
        }.getOrNull()
    }

    /**
     * v1.0.46 (§1.6.39): process-lifetime scope for one-shot startup work.
     *
     * Same shape as `ServerListUpdateScheduler`'s private scope
     * (`CoroutineScope(SupervisorJob() + Dispatchers.IO)`). Used only for the
     * emergency-default seed, which must never block [onCreate].
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
            .addMigrations(AppDatabase.MIGRATION_4_5)
            // Z1-IMPL-B (Roadmap §12.5.2 item B): emergency server sources.
            .addMigrations(AppDatabase.MIGRATION_5_6)
            // Z1-IMPL-D (Roadmap §12.5.2 item D): manual server entry.
            .addMigrations(AppDatabase.MIGRATION_6_7)
            // RETENTION-IMPL-PHASE-1 (spec §1.6.31): version 7 -> 8 columns.
            .addMigrations(AppDatabase.MIGRATION_7_8)
            // F.29: version 8 -> 9, the three server-history tables. Purely
            // additive — see the migration's own comment.
            .addMigrations(AppDatabase.MIGRATION_8_9)
            // BLOCK v1.0.69 PART 1: version 9 -> 10, additive history columns.
            .addMigrations(AppDatabase.MIGRATION_9_10)
            .allowMainThreadQueries() // Allow main thread queries for VPN profile configuration
            .build()
        vpnGateItemDao = appDatabase.vpnGateItemDao()
        excludedAppDao = appDatabase.excludedAppDao()
        emergencySourceConfigDao = appDatabase.emergencySourceConfigDao()
        manualServerDao = appDatabase.manualServerDao()
        serverHistoryDao = appDatabase.serverHistoryDao()
        // The sink is constructed after the DAO exists but before any Auto Mode
        // run can start, so a run can never observe a half-built sink.
        serverHistorySink = RoomServerHistorySink(serverHistoryDao)

        // Initialize default excluded apps
        initializeDefaultExcludedApps()
        instance = this
        dataUtil = DataUtil(this)
        isImportToOpenVPN = AppConfig.getBoolean("vpn_import_open_vpn")

        // v1.0.46 (§1.6.39) CHANGE A: seed the default emergency source from
        // R.string.default_emergency_source_url (written by
        // emergency_csv_publisher.py). Fire-and-forget on IO so startup is
        // never blocked, and wrapped so a seed failure cannot prevent the app
        // from starting. The "once only" decision lives inside
        // [seedEmergencyDefaultIfNeeded]; this block only supplies the inputs.
        appScope.launch {
            runCatching {
                val url = getString(R.string.default_emergency_source_url)
                seedEmergencyDefaultIfNeeded(dataUtil!!, emergencySourceConfigDao, url)
            }.onFailure { logW("Default emergency source seed failed", it) }
        }

        // v1.0.54 (§1.6.50) PART 5: seed the default Excluded Apps list, once
        // per install, from assets/default_excluded_apps.
        //
        // Same fire-and-forget shape as the emergency seed above, for the same
        // reason: `getInstalledApplications` on a device with a few hundred
        // packages is tens of milliseconds, and blocking onCreate for it would
        // slow every cold start. The one-shot rule, the installed-only rule and
        // the "empty asset does not burn the flag" rule all live inside
        // [ExcludedAppsSeeder.seedOnce], which is why this block is only the
        // wiring.
        appScope.launch {
            runCatching {
                ExcludedAppsSeeder.runIfNeeded(this@App)
            }.onFailure { logW("Default excluded apps seed failed", it) }
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
        vn.unlimit.vpngate.automode.TunnelStateWatcher.attach(this)

        // Scheduled server list refresh. isDue() is a cheap persisted check, so
        // calling it on every app start can never produce more than one download
        // per configured interval, and a manual Update press racing this is
        // rejected by the updater's own concurrency guard.
        vn.unlimit.vpngate.repository.ServerListUpdateScheduler.runIfDue()

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

        /**
         * v1.0.46 (§1.6.39) — seed the default emergency source exactly once.
         *
         * The publisher writes the URL into
         * `R.string.default_emergency_source_url`, but before this phase no
         * Kotlin code read it, so a fresh install had an empty
         * `emergency_source_config` table and `ServerListUpdates`'s
         * `emergencyDataset()` (which calls `dao.listEnabled()`) never had a
         * source to fetch.
         *
         * Contract:
         *  - **Once only.** Guarded by
         *    [DataUtil.hasSeededEmergencyDefault]. A user who deletes the
         *    seeded row has made an explicit choice, so an empty table after
         *    the flag is set is NOT re-seeded.
         *  - **Non-destructive.** If the table already holds any row (enabled
         *    or not — a user may have disabled their own source), nothing is
         *    inserted and only the flag is set.
         *  - **Idempotent.** Re-running with the flag set is a no-op, so a
         *    duplicate row can never be created.
         *  - **Non-fatal.** Never throws; a failure leaves the flag unset so
         *    the next start retries.
         *
         * Deliberately takes its three collaborators as parameters so a plain
         * JVM test can drive it with a fake DAO and the in-memory preference
         * seam, without an `Application`, a `Context` or Room.
         *
         * @return true only when this call inserted the row.
         */
        internal suspend fun seedEmergencyDefaultIfNeeded(
            dataUtil: DataUtil,
            dao: EmergencySourceConfigDao,
            url: String,
            now: Long = System.currentTimeMillis(),
        ): Boolean = try {
            if (dataUtil.hasSeededEmergencyDefault()) {
                false
            } else {
                val candidate = url.trim()
                when {
                    candidate.isEmpty() -> {
                        // Publisher has not run yet, or the resource was cleared.
                        false
                    }
                    SourceUrl.parse(candidate, SourceKind.EMERGENCY_CSV) == null -> {
                        logW("Default emergency URL is not valid; skipping seed")
                        false
                    }
                    else -> {
                        val existing = dao.observeAll().first()
                        if (existing.isNotEmpty()) {
                            // The user already has a source; never overwrite it.
                            dataUtil.markEmergencyDefaultSeeded()
                            logD("Emergency source already configured; default not seeded")
                            false
                        } else {
                            dao.upsert(
                                EmergencySourceConfigEntity(
                                    url = candidate,
                                    label = DEFAULT_EMERGENCY_LABEL,
                                    enabled = true,
                                    createdAt = now,
                                    updatedAt = now,
                                ),
                            )
                            dataUtil.markEmergencyDefaultSeeded()
                            logD("Seeded default emergency source")
                            true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Never let a seed failure stop the app from starting.
            logW("Default emergency source seed failed", e)
            false
        }

        /** Label written on the seeded row, so the UI can tell it from user rows. */
        internal const val DEFAULT_EMERGENCY_LABEL: String = "Default"

        /**
         * `android.util.Log` is a throwing stub on a plain JVM (this module's
         * unit tests have no `returnDefaultValues`), so every log call in the
         * seeder is wrapped: a diagnostic must never change the outcome.
         */
        private fun logD(message: String) {
            runCatching { Log.d(TAG, message) }
        }

        private fun logW(message: String, error: Throwable? = null) {
            runCatching { if (error != null) Log.w(TAG, message, error) else Log.w(TAG, message) }
        }
        // Default VpnProfileCompat mode for openvpn2.4.x compatibility with softether vpn server
        const val VPN_PROFILE_COMPAT_MODE_24X = 20400
    }
}
