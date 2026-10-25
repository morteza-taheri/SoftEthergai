package vn.unlimit.vpngate.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * F.29 — the only writer of the three history tables.
 *
 * The single write path is [record]: it reads the current rows, applies the pure
 * arithmetic in [HistoryCounters], upserts both summaries and appends one event,
 * **all inside one Room transaction**. A crash or a thrown error can therefore
 * never leave a counter updated without its event, or an event without its
 * counter.
 *
 * Reads are bounded on purpose (F.29 §10.3):
 *  - [historyViews] is the *only* thing Auto Mode's ranking reads, and it is one
 *    query over one row per server — never the event table;
 *  - [eventsFor] is for diagnosis, always `LIMIT`-ed.
 */
@Dao
interface ServerHistoryDao {

    // ---------------------------------------------------------------------
    // Reads used by Auto Mode's ranking — one query, narrow projection.
    // ---------------------------------------------------------------------

    /**
     * Every summary as the narrow ordering view.
     *
     * `IFNULL(lastSuccessAt, 0)` is not cosmetic: the column is nullable in the
     * schema (a server that never connected has no timestamp) while the view's
     * field is a primitive, so the query states the sentinel explicitly instead
     * of letting Room hand a NULL to a `Long`.
     */
    @Query(
        "SELECT hostName AS hostName, " +
            "successCount AS successCount, " +
            "failureCount AS failureCount, " +
            "consecutiveFailures AS consecutiveFailures, " +
            "IFNULL(lastSuccessAt, 0) AS lastSuccessAt, " +
            "wifiSuccessCount AS wifiSuccessCount, " +
            "cellularSuccessCount AS cellularSuccessCount, " +
            "unknownNetworkSuccessCount AS unknownNetworkSuccessCount " +
            "FROM server_history_summary",
    )
    fun historyViews(): List<ServerHistoryView>

    // ---------------------------------------------------------------------
    // Reads used by the recorder and by diagnosis.
    // ---------------------------------------------------------------------

    @Query("SELECT * FROM server_history_summary WHERE hostName = :hostName")
    fun summary(hostName: String): ServerHistorySummary?

    @Query("SELECT * FROM server_history_protocol WHERE hostName = :hostName")
    fun protocols(hostName: String): List<ServerProtocolSummary>

    @Query("SELECT * FROM server_history_protocol WHERE hostName = :hostName AND protocol = :protocol")
    fun protocolSummary(hostName: String, protocol: String): ServerProtocolSummary?

    @Query(
        "SELECT * FROM server_history_event WHERE hostName = :hostName " +
            "ORDER BY createdAt DESC, id DESC LIMIT :limit",
    )
    fun eventsFor(hostName: String, limit: Int): List<ServerHistoryEvent>

    @Query("SELECT COUNT(*) FROM server_history_event")
    fun eventCount(): Long

    @Query("SELECT COUNT(*) FROM server_history_summary")
    fun summaryCount(): Int

    @Query("SELECT COUNT(*) FROM server_history_event WHERE attemptToken = :token AND eventType = :eventType")
    fun countToken(token: String, eventType: String): Int

    // ---------------------------------------------------------------------
    // Writes
    // ---------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertSummary(row: ServerHistorySummary)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertProtocol(row: ServerProtocolSummary)

    @Insert
    fun insertEvent(row: ServerHistoryEvent)

    /**
     * The one atomic write.
     *
     * Idempotency is scoped to the **success** record, because that is the only
     * outcome a second code path could plausibly deliver twice: the attempt
     * token is `run|server|protocol`, so two runs can never collide and one run
     * can never record the same success twice. Every other event is emitted from
     * a branch that executes once per attempt by construction (the attempt loop
     * visits each `(server, protocol)` pair at most once), so a token check there
     * would be ceremony without a defect to prevent.
     *
     * A blank identity is dropped rather than written: a row all blank hostnames
     * would share would merge unrelated servers into one history.
     */
    @Transaction
    fun record(record: HistoryRecord) {
        if (record.hostName.isBlank()) return
        if (record.eventType == HistoryEventType.CONNECTED &&
            record.token != null &&
            countToken(record.token, record.eventType.name) > 0
        ) {
            return
        }

        upsertSummary(HistoryCounters.nextSummary(summary(record.hostName), record))

        val existingProtocol = record.protocol?.let { protocolSummary(record.hostName, it) }
        HistoryCounters.nextProtocol(existingProtocol, record)?.let { upsertProtocol(it) }

        insertEvent(ServerHistoryEvent.fromRecord(record))
    }
}
