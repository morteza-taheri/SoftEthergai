package vn.unlimit.vpngate.history

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * F.29 — the Room-backed [ServerHistorySink].
 *
 * Two responsibilities, both narrow: keep the write **off the main thread**, and
 * keep a write failure **invisible to the connection result**.
 *
 * The application enables `allowMainThreadQueries()` (`App.kt`), so Room will
 * happily run this on the main thread and nothing in the framework will stop it.
 * The `Dispatchers.IO` hop is therefore the enforcement, not a convention: this
 * class is the only path from the controller to the history tables, and it
 * always leaves the calling dispatcher.
 *
 * Errors are logged and swallowed. A full disk or a locked database must never
 * turn a live tunnel into a "failed" connection, must never increment a server
 * failure counter, and must never trigger a disconnect (F.29 §9/§17). There is
 * no retry: an unbounded retry against a failing database is a battery bug, and
 * the next attempt will record a newer, more useful fact anyway.
 */
class RoomServerHistorySink(
    private val dao: ServerHistoryDao,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) : ServerHistorySink {

    override suspend fun record(record: HistoryRecord) {
        if (record.hostName.isBlank()) {
            log("Dropped ${record.eventType} for a blank server identity")
            return
        }
        try {
            withContext(Dispatchers.IO) { dao.record(record) }
        } catch (e: CancellationException) {
            // Structured concurrency wins over containment: a cancelled write
            // must stay cancelled, or the caller's own cancellation would be
            // silently turned into "the write succeeded".
            throw e
        } catch (e: Exception) {
            // Deliberately no rethrow and no state change: the caller's tunnel
            // result is the authoritative one, and a persistence problem is not a
            // connection problem.
            log(
                "History write failed for ${record.eventType} on ${record.hostName}: " +
                    "${e.javaClass.simpleName}: ${e.message}",
            )
        }
    }

    private companion object {
        const val TAG = "ServerHistory"
    }
}
