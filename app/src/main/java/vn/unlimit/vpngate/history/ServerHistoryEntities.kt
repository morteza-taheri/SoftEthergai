package vn.unlimit.vpngate.history

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * F.29 — the three durable history tables.
 *
 * ## Why these are separate tables and not columns on `VPNGateItem`
 *
 * `VPNGateItemDao.replaceAll` is `deleteAll()` + `insertAll()` inside a
 * transaction, and **both** server-list write paths end there — the network
 * merge rebuilds the list in memory, and the snapshot fallback passes the
 * incoming list straight through with no previous-row read
 * (`ServerListUpdates.kt:301-305`). A history column on that entity would
 * therefore be zeroed by any refresh served from a snapshot, which is precisely
 * the "a refresh must not reset history" rule F.29 forbids breaking.
 *
 * ## Why there is no foreign key
 *
 * A `FOREIGN KEY(hostName) REFERENCES VPNGateItem(hostName) ON DELETE CASCADE`
 * would delete every event the moment `replaceAll` ran its `deleteAll()`. There
 * is no FK here on purpose. An orphaned summary row — a server that has left
 * every source — is harmless: it is never matched, and it is the record of a
 * server that once worked. Referential integrity is maintained by the
 * application, in one direction, by construction: a summary is only ever
 * written for an identity that Auto Mode actually attempted.
 */

/**
 * One row per stable server identity (`hostName`, the Room primary key).
 *
 * **A missing row means "no history", never "bad server".** That distinction is
 * load-bearing: the ordering pass treats absence as *unknown* and leaves the
 * candidate where quality put it.
 *
 * Timestamps are nullable rather than `0`-sentinel: "never connected" and "connected
 * at the Unix epoch" are different facts, and only one of them is real.
 */
@Entity(tableName = "server_history_summary")
data class ServerHistorySummary(
    @PrimaryKey val hostName: String,

    /** First time the history layer saw this identity. */
    val firstObservedAt: Long? = null,

    /** Last protocol attempt of any kind, successful or not. */
    val lastAttemptAt: Long? = null,

    /** Last **verified** tunnel. */
    val lastSuccessAt: Long? = null,

    /** Last server-attributable failure. Never set by a timeout. */
    val lastFailureAt: Long? = null,

    /** Last timeout with no verdict. */
    val lastTimeoutAt: Long? = null,

    val successCount: Int = 0,

    /** Server-attributable failures only (F.29 §8.2). */
    val failureCount: Int = 0,

    /** Timeouts with no verdict (F.29 §8.3) — never folded into [failureCount]. */
    val timeoutCount: Int = 0,

    /** Server-attributable failures since the last verified tunnel. */
    val consecutiveFailures: Int = 0,

    val lastSuccessProtocol: String? = null,
    val lastSuccessNetwork: String? = null,
    val lastFailureReason: String? = null,

    val wifiSuccessCount: Int = 0,
    val wifiFailureCount: Int = 0,
    val cellularSuccessCount: Int = 0,
    val cellularFailureCount: Int = 0,
    val unknownNetworkSuccessCount: Int = 0,
    val unknownNetworkFailureCount: Int = 0,

    /**
     * Reserved (F.29 §2.4 / OD-5): the application has no post-connect quality
     * rejection, so nothing increments this. It exists so a future criterion has
     * a home and so no caller can silently invent one.
     */
    val qualityRejectionCount: Int = 0,
) {
    /** The narrow read model the ordering pass consumes. */
    fun toView(): ServerHistoryView = ServerHistoryView(
        hostName = hostName,
        successCount = successCount,
        failureCount = failureCount,
        consecutiveFailures = consecutiveFailures,
        lastSuccessAt = lastSuccessAt ?: 0L,
        wifiSuccessCount = wifiSuccessCount,
        cellularSuccessCount = cellularSuccessCount,
        unknownNetworkSuccessCount = unknownNetworkSuccessCount,
    )
}

/**
 * One row per `(serverIdentity, protocol)`.
 *
 * Answers the operator's "how often did each protocol succeed or fail" without
 * widening the summary, and **never** reorders protocol attempts: the user's
 * Protocol Priority profile remains the only authority on attempt order
 * (F.29 §2 / OD-4). This table is diagnostic.
 */
@Entity(
    tableName = "server_history_protocol",
    primaryKeys = ["hostName", "protocol"],
)
data class ServerProtocolSummary(
    val hostName: String,
    /** `AutoModeProtocol.id` — the same identifier the settings store. */
    val protocol: String,

    val successCount: Int = 0,
    val failureCount: Int = 0,
    val timeoutCount: Int = 0,

    val lastSuccessAt: Long? = null,
    val lastFailureAt: Long? = null,

    /** Last classified outcome for this protocol, e.g. `SERVER_REJECTED`. */
    val lastOutcome: String? = null,
)

/**
 * The append-only event log.
 *
 * Indexes cover the three queries that actually exist: "this server's events,
 * newest first", "this server's events for this protocol", and "events of this
 * type over time". No index is added for a query nobody writes.
 */
@Entity(
    tableName = "server_history_event",
    indices = [
        Index(value = ["hostName", "createdAt"]),
        Index(value = ["hostName", "protocol", "createdAt"]),
        Index(value = ["eventType", "createdAt"]),
        // The success record is idempotent on its attempt token; this index is
        // what keeps that check a lookup rather than a scan.
        Index(value = ["attemptToken", "eventType"]),
    ],
)
data class ServerHistoryEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** The stable identity. **Not** a foreign key — see this file's header. */
    val hostName: String,

    /** The endpoint as observed at the time. Provenance, never identity. */
    val ip: String? = null,

    val eventType: String,
    val outcome: String,
    val networkType: String,

    val protocol: String? = null,
    val attemptToken: String? = null,
    val durationMs: Long? = null,
    val failureReason: String? = null,

    /** Reserved (OD-5): always null in this build. */
    val qualityResult: String? = null,

    val createdAt: Long,
) {
    companion object {
        /** The single mapping from the pure record to the persisted row. */
        fun fromRecord(record: HistoryRecord): ServerHistoryEvent = ServerHistoryEvent(
            hostName = record.hostName,
            ip = record.ip,
            eventType = record.eventType.name,
            outcome = record.outcome.name,
            networkType = record.networkType.name,
            protocol = record.protocol,
            attemptToken = record.token,
            durationMs = record.durationMs,
            failureReason = record.failureReason,
            qualityResult = null,
            createdAt = record.at,
        )
    }
}
