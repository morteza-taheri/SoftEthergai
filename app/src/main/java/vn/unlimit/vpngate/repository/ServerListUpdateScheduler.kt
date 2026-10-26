package vn.unlimit.vpngate.repository

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.data.model.CollectorLog

/**
 * Decides *whether* a background server-list update is due, and runs it.
 *
 * There is deliberately no WorkManager/AlarmManager here: the app has no
 * background-scheduler dependency today, and adding one would change the
 * dependency graph for a single, self-rescheduling job. Instead the check is
 * cheap, persisted and idempotent — [isDue] is a pure function of the last
 * successful update timestamp and the user's interval, so calling it as often
 * as we like (app start, screen open) can never produce a second download
 * inside one interval.
 *
 * The actual work is delegated to [ServerListUpdater], which owns the
 * concurrency guard, so a scheduled run and a manual button press racing each
 * other still result in exactly one download.
 */
object ServerListUpdateScheduler {

    private const val TAG = "ServerListScheduler"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Pure due-check.
     *
     * @param lastUpdatedAt epoch millis of the last *successful* update; 0 when
     *   the list has never been refreshed.
     * @param intervalHours the user's configured interval, already clamped to
     *   1..168 by `DataUtil`.
     * @param now current time, injected so the rule is testable without a clock.
     */
    fun isDue(lastUpdatedAt: Long, intervalHours: Int, now: Long): Boolean {
        val interval = intervalHours.coerceIn(1, 168)
        // A list that was never successfully updated is due immediately: the
        // alternative is a fresh install that never fills its server list.
        if (lastUpdatedAt <= 0L) return true
        return now - lastUpdatedAt >= interval * 60L * 60L * 1000L
    }

    /**
     * Runs a background update if one is due. Safe to call from anywhere and as
     * often as you like: a not-due call does nothing, and an already-running
     * update makes this a no-op.
     */
    fun runIfDue(force: Boolean = false) {
        val app = App.instance ?: return
        val dataUtil = app.dataUtil ?: return
        val last = dataUtil.connectionCacheUpdatedAt
        if (!force && !isDue(last, dataUtil.getServerListUpdateIntervalHours(), System.currentTimeMillis())) {
            return
        }
        scope.launch {
            val outcome = runCatching { buildUpdater(app).update() }
                .getOrElse { e ->
                    Log.w(TAG, "Scheduled server list update failed", e)
                    return@launch
                }
            CollectorLog.d("Scheduled server list update: $outcome")
            if (outcome is ServerListUpdater.Outcome.Updated) {
                // Only a genuinely successful update moves the clock, so a
                // failed attempt is retried on the next check instead of
                // being silently postponed by a full interval. An accepted
                // fallback (snapshot / stored / bundled seed) is not a
                // successful update either, so it must not postpone the first
                // real download.
                if (!outcome.sourcedFromCache) {
                    dataUtil.setConnectionCacheUpdatedAt(System.currentTimeMillis())
                }
            }
        }
    }

    /**
     * Wires the production [ServerListUpdater]: the existing multi-source
     * collector produces the candidate dataset, the Room DAO is the store.
     *
     * A null result from the repository means the download/parse/validate
     * pipeline produced nothing usable, which is exactly the "keep the current
     * list" case — the updater reports it as a failure rather than replacing.
     */
    private fun buildUpdater(app: App): ServerListUpdater =
        ServerListUpdates.forUpdateTrigger(app)
}
