package vn.unlimit.vpngate.repository

import vn.unlimit.vpngate.App

/**
 * BLOCK v1.0.86 — the operator-configurable server-list ceiling.
 *
 * `capRetainedServers` is a pure function used from three places, and
 * `ManualServerAdmission` is built once as a process-wide singleton. Neither
 * can hold a Context, so the configured capacity is resolved through this
 * seam: production reads the SharedPreferences setting, tests override
 * [resolver] with a fixed number.
 *
 * The quota rule the operator asked for: **half** of the configured ceiling is
 * reserved for user-supplied servers (manual form + CSV import). The other half
 * belongs to the network feed. An import that would cross that line is rejected
 * with a warning by [ManualImportStore].
 */
object ServerListCap {

    const val MIN: Int = 10
    const val MAX: Int = 2000
    // One API response is ~100-280 rows; the operator requires retention of
    // servers discovered from HTML/mirrors as well, so the default ceiling is
    // 1000. Still fully configurable within [MIN, MAX].
    const val DEFAULT: Int = 1000

    /** Injected by tests; production falls through to the app's DataUtil. */
    @Volatile
    var resolver: (() -> Int)? = null

    fun current(): Int {
        val injected = resolver?.invoke()
        if (injected != null) return injected.coerceIn(MIN, MAX)
        return App.instance?.dataUtil?.getServerListCap() ?: DEFAULT
    }

    /** Half of the ceiling is the manual/import quota. */
    fun manualQuota(): Int = current() / 2
}
