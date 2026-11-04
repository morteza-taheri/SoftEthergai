package vn.unlimit.vpngate.ui.screens.status

import android.content.Context
import vn.unlimit.vpngate.App
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BLOCK v1.0.82 — "today's traffic" for the Status report.
 *
 * The screen already shows the SESSION totals (this tunnel) and the ALL-TIME
 * totals (persisted by [TotalTraffic]). Neither answers the question a user
 * actually asks — "how much did I move today?" — so this keeps one extra pair of
 * counters, reset when the calendar day changes.
 *
 * Written from the traffic snapshots the ViewModel already receives: the
 * caller passes the SESSION bytes, and this stores the largest value seen for
 * the day rather than summing deltas. That is deliberate — a listener can fire
 * twice with the same snapshot, and a re-connect restarts the session counter at
 * zero; max-per-session avoids both the double count and the negative delta.
 */
internal object DailyTraffic {

    private const val PREFS = "daily_traffic"
    private const val KEY_DAY = "day"
    private const val KEY_IN = "in"
    private const val KEY_OUT = "out"

    private fun prefs(): android.content.SharedPreferences? {
        val ctx: Context = App.instance ?: return null
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Records a session snapshot and returns today's (in, out) totals. */
    fun record(sessionIn: Long, sessionOut: Long): Pair<Long, Long> {
        val p = prefs() ?: return sessionIn to sessionOut
        val day = today()
        if (p.getString(KEY_DAY, null) != day) {
            p.edit().putString(KEY_DAY, day).putLong(KEY_IN, 0L).putLong(KEY_OUT, 0L).apply()
        }
        val inToday = maxOf(p.getLong(KEY_IN, 0L), sessionIn.coerceAtLeast(0L))
        val outToday = maxOf(p.getLong(KEY_OUT, 0L), sessionOut.coerceAtLeast(0L))
        p.edit().putLong(KEY_IN, inToday).putLong(KEY_OUT, outToday).apply()
        return inToday to outToday
    }

    /** Today's totals without recording anything (for the first paint). */
    fun peek(): Pair<Long, Long> {
        val p = prefs() ?: return 0L to 0L
        if (p.getString(KEY_DAY, null) != today()) return 0L to 0L
        return p.getLong(KEY_IN, 0L) to p.getLong(KEY_OUT, 0L)
    }

    fun clear() {
        prefs()?.edit()?.putLong(KEY_IN, 0L)?.putLong(KEY_OUT, 0L)?.apply()
    }
}
