package vn.unlimit.vpngate.utils

import android.content.Context
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.models.ExcludedApp

/**
 * v1.0.54 (§1.6.50) PART 5 — the one-shot first-run Excluded Apps seed.
 *
 * ## What it does
 *
 * On the first run after install, every package in
 * [DefaultExcludedApps] that is *actually installed* on this device is added to
 * `excluded_apps`, so the operator does not have to tick twenty boxes by hand.
 *
 * ## The four rules, and why each one exists
 *
 * 1. **Installed only.** A package that is not on the device is skipped, never
 *    inserted. A phantom row would show in the sheet as an excluded app that
 *    does not exist, cannot be unticked meaningfully, and would make the count
 *    in Settings a lie.
 *
 * 2. **Once per install.** The flag [FLAG_KEY] is set when the seed runs, so a
 *    user who deliberately removes a seeded app does not get it back on the
 *    next launch. Re-seeding on every start would make the sheet's Save button
 *    a suggestion rather than a decision.
 *
 * 3. **Never overwrite a name the user chose.** The insert is
 *    `OnConflictStrategy.REPLACE` at the DAO, but the seeder checks membership
 *    first, so an existing row keeps its stored `appName`.
 *
 * 4. **No candidates means no flag.** The bundled asset ships empty on purpose
 *    while the real package names are still on the operator's phone. Setting
 *    the flag on an empty list would permanently disable the seed for the very
 *    build that finally carries the names.
 *
 * ## Why the core takes lambdas
 *
 * [seedOnce] does no Android work: it receives "is the flag set", "set the
 * flag", "is this package installed" and "insert this row" as functions. That
 * makes every rule above provable in a pure JVM test, with no Robolectric, no
 * Room and no device — which matters because the device is not available while
 * this ships.
 */
object ExcludedAppsSeeder {

    /** The one-shot marker. Present = the seed has already run. */
    const val FLAG_KEY: String = "SETTING_EXCLUDED_APPS_SEEDED"

    /**
     * Result of one seeding attempt.
     *
     * @param ran whether the seed actually executed (false when the flag was
     *   already set, or when there was nothing to consider).
     * @param seeded packages written to the database, in seed order.
     * @param skipped packages in the seed that are not installed here.
     * @param alreadyPresent packages that were already excluded before the run;
     *   not written again, and not counted in [seeded].
     */
    data class Outcome(
        val ran: Boolean,
        val seeded: List<String>,
        val skipped: List<String>,
        val alreadyPresent: List<String>,
    ) {
        val total: Int get() = seeded.size
    }

    /**
     * The device-independent core.
     *
     * @param candidates package names from the asset, in order.
     * @param isSeeded reads the one-shot flag.
     * @param markSeeded writes the one-shot flag.
     * @param isInstalled whether the package exists on this device.
     * @param isAlreadyExcluded whether the package is already a row.
     * @param insert writes one row.
     */
    fun seedOnce(
        candidates: List<String>,
        isSeeded: () -> Boolean,
        markSeeded: () -> Unit,
        isInstalled: (String) -> Boolean,
        isAlreadyExcluded: (String) -> Boolean,
        insert: (ExcludedApp) -> Unit,
    ): Outcome {
        if (candidates.isEmpty()) {
            // Nothing to consider: do NOT burn the flag (rule 4).
            return Outcome(ran = false, seeded = emptyList(), skipped = emptyList(), alreadyPresent = emptyList())
        }
        if (isSeeded()) {
            return Outcome(ran = false, seeded = emptyList(), skipped = emptyList(), alreadyPresent = emptyList())
        }

        val seeded = ArrayList<String>(candidates.size)
        val skipped = ArrayList<String>(0)
        val present = ArrayList<String>(0)
        for (packageName in candidates) {
            if (!isInstalled(packageName)) {
                skipped.add(packageName)
                continue
            }
            if (isAlreadyExcluded(packageName)) {
                present.add(packageName)
                continue
            }
            // The stored appName is the package name: the seed carries no
            // labels, and resolving one here would need a PackageManager call
            // per row for a value the sheet displays only as a fallback.
            insert(ExcludedApp(packageName, packageName))
            seeded.add(packageName)
        }

        // Set the flag even when nothing was installed: the run happened, and
        // auto-excluding a seed entry that the user installs next week would be
        // a surprise they never opted into (rule 2).
        markSeeded()
        return Outcome(ran = true, seeded = seeded, skipped = skipped, alreadyPresent = present)
    }

    /**
     * The Android entry point. Safe to call on every launch; it returns
     * immediately once the flag is set.
     *
     * Runs on the calling thread — the caller must be off the main thread. It
     * is invoked once from [App.onCreate] on a background dispatcher, because
     * `getInstalledApplications` on a device with 300 packages is tens of
     * milliseconds and the sheet's own query already pays that cost on demand.
     */
    fun runIfNeeded(context: Context): Outcome {
        val app = context.applicationContext as? App
            ?: return Outcome(false, emptyList(), emptyList(), emptyList())
        val dataUtil = app.dataUtil
            ?: return Outcome(false, emptyList(), emptyList(), emptyList())
        val dao = app.excludedAppDao
            ?: return Outcome(false, emptyList(), emptyList(), emptyList())

        val candidates = DefaultExcludedApps.load(context)
        if (candidates.isEmpty()) {
            return Outcome(false, emptyList(), emptyList(), emptyList())
        }

        val packageManager = context.packageManager
        val installed = try {
            packageManager.getInstalledApplications(0)
                .mapTo(HashSet()) { it.packageName }
        } catch (e: Exception) {
            // Package visibility can make this empty rather than throwing; an
            // actual throw here means the query is unusable, so seed nothing
            // rather than seed blind.
            return Outcome(false, emptyList(), emptyList(), emptyList())
        }

        val existing = try {
            dao.getAllExcludedApps().mapTo(HashSet()) { it.packageName }
        } catch (e: Exception) {
            emptySet()
        }

        return seedOnce(
            candidates = candidates,
            isSeeded = { dataUtil.getBooleanSetting(FLAG_KEY, false) },
            markSeeded = { dataUtil.setBooleanSetting(FLAG_KEY, true) },
            isInstalled = { it in installed },
            isAlreadyExcluded = { it in existing },
            insert = { dao.insertExcludedApp(it) },
        )
    }

    /** Test/QA helper: clears the one-shot marker so the next launch re-seeds. */
    fun reset(context: Context) {
        (context.applicationContext as? App)?.dataUtil
            ?.setBooleanSetting(FLAG_KEY, false)
    }
}
