package vn.unlimit.vpngate.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.automode.AutoModeRun
import vn.unlimit.vpngate.favourites.FavouriteServers
import vn.unlimit.vpngate.state.GlobalVpnTracker

/**
 * BLOCK v1.0.81 — the Settings "purge server list" action.
 *
 * Deletes every stored server except the one the app is connected to, together
 * with that server's history rows (summary, per-protocol counters and the event
 * log). The operator's rule: keep the tunnel that is up, throw the catalogue
 * away.
 *
 * Three details that matter:
 *
 *  * **The survivor is resolved, not assumed.** A SoftEther session publishes
 *    `assignedIp` but not always `serverHost`, so the connected host is taken
 *    from the tracker when it is known and from Auto Mode's last successful
 *    candidate otherwise. When neither names a server, everything is purged —
 *    "keep the connected one" with nothing connected means "keep nothing".
 *  * **Favourites go with the rows.** A star on a deleted server would otherwise
 *    protect it from the retention cap forever, which is the opposite of what
 *    the user just asked for.
 *  * **The list is re-read from Room.** `DataUtil.connectionsCache` is a getter
 *    over the table (not a separate file), so it cannot resurrect a purged row.
 */
object ServerPurge {

    /** Result of a purge, for the confirmation message. */
    data class Result(val removedServers: Int, val keptHost: String?)

    suspend fun purgeAllExceptConnected(context: Context): Result = withContext(Dispatchers.IO) {
        val app = App.instance ?: return@withContext Result(0, null)
        val keepHost = connectedHostName()

        val dao = app.vpnGateItemDao
        val before = dao.count()
        val keep = keepHost ?: ""

        // Raw SQL, deliberately. The four deletes do not belong on the Dao
        // interfaces: adding abstract methods there would force every test fake
        // to implement them, and the purge is one atomic maintenance operation
        // rather than part of the repository's normal query surface.
        val db = app.appDatabase.openHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM vpngateitem WHERE hostName != ?", arrayOf<Any>(keep))
            db.execSQL("DELETE FROM server_history_summary WHERE hostName != ?", arrayOf<Any>(keep))
            db.execSQL("DELETE FROM server_history_protocol WHERE hostName != ?", arrayOf<Any>(keep))
            db.execSQL("DELETE FROM server_history_event WHERE hostName != ?", arrayOf<Any>(keep))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        if (keepHost == null) {
            FavouriteServers.clear()
        } else {
            // Drop the stars of the servers that no longer exist, keep the survivor's.
            FavouriteServers.removeAll(
                FavouriteServers.snapshot().filterNot { it.equals(keepHost, ignoreCase = true) },
            )
        }

        // No legacy cache to clear: DataUtil.connectionsCache reads the Room
        // table directly, so it reflects the purge on the next read.

        val after = dao.count()
        Result((before - after).coerceAtLeast(0), keepHost)
    }

    /**
     * The host name of the server the app is connected to, or null when nothing
     * is connected (or the stack did not name it).
     */
    private fun connectedHostName(): String? {
        val state = GlobalVpnTracker.vpnState.value
        state.serverHost?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val candidate = AutoModeRun.lastSuccess
        return candidate?.hostname?.trim()?.takeIf { it.isNotEmpty() }
            ?: candidate?.ip?.trim()?.takeIf { it.isNotEmpty() }
    }
}
