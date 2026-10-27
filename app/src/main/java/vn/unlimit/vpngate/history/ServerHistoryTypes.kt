package vn.unlimit.vpngate.history

/**
 * F.29 — Smart Server History & Adaptive AutoConnect: the vocabulary.
 *
 * This file is deliberately **pure Kotlin**: no Room, no Android, no
 * `AutoModeController`. Everything here is unit-testable on a plain JVM, and
 * the dependency direction stays one-way — `automode` may depend on `history`,
 * `history` never depends on `automode`. That is what lets the ordering pass be
 * tested without a database and the recorder be tested without a state machine.
 */

/**
 * The **physical** network a connection was made over.
 *
 * Deliberately only three values (F.29 §7): Wi-Fi, mobile data, and the honest
 * third answer. Ethernet, a hotspot we cannot classify, a VPN-only view, or a
 * `ConnectivityManager` that will not answer all collapse to [UNKNOWN] rather
 * than being guessed into one of the two real buckets. There is no `OTHER`
 * bucket because nothing in the product would do anything different with it,
 * and a fourth value would invite a caller to treat it as "known".
 */
enum class NetworkType {
    WIFI,
    CELLULAR,
    UNKNOWN;

    companion object {
        /**
         * Storage-safe round trip. An unrecognised or absent string becomes
         * [UNKNOWN] — never [WIFI] and never [CELLULAR], because a value this
         * build does not understand is a value it cannot claim to know.
         */
        fun fromStorage(value: String?): NetworkType =
            entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

/**
 * The bounded event taxonomy (F.29 §6.3).
 *
 * **Five** of these have a producer in the current lifecycle — CONNECTED,
 * PROTOCOL_ATTEMPT, SERVER_ABANDONED, CONNECTION_LOST and ATTEMPT_CANCELLED.
 * Two have none, for different reasons:
 *
 *  - [QUALITY_REJECTED] — the application has **no** post-connect
 *    quality-rejection trigger (see `PROJECT_SPECIFICATION.md` §11.15.2.5), so
 *    there is nothing to observe. It is declared so that the day such a
 *    criterion exists it has a home, and so the counter can never be invented
 *    by a caller that assumed one.
 *  - [SERVER_FIRST_SEEN] — `firstObservedAt` is already stamped once, by
 *    `HistoryCounters.nextSummary`, on the first event of **any** type, so a
 *    dedicated event would add a row carrying no fact the summary does not
 *    already hold.
 *
 * Both counters are implemented in `HistoryCounters` anyway, so neither can be
 * wired to the wrong event by accident.
 */
enum class HistoryEventType {
    /** A verified tunnel came up. The one success record. */
    CONNECTED,

    /**
     * One protocol attempt reached a terminal that is **not** success.
     *
     * Per (server, protocol): the diagnostic record that answers "how often did
     * each protocol fail, and why". A failure here does **not** by itself
     * condemn the server — see [SERVER_ABANDONED].
     */
    PROTOCOL_ATTEMPT,

    /**
     * Auto Mode gave up on a server after its usable protocols were exhausted.
     *
     * This is the only event that may increment the server-level failure
     * counters, and only when at least one protocol was rejected at the endpoint
     * layer during that server's turn.
     */
    SERVER_ABANDONED,

    /** An established tunnel was lost. Never retro-erases the success. */
    CONNECTION_LOST,

    /**
     * The first time this identity was observed by the history layer.
     *
     * Declared, with its counter branch implemented, but **no producer emits
     * it** — see this enum's header. `firstObservedAt` is stamped by whichever
     * event happens to arrive first.
     */
    SERVER_FIRST_SEEN,

    /** Reserved: no producer exists in this build. */
    QUALITY_REJECTED,

    /** The attempt was cancelled by the user (skip / stop). */
    ATTEMPT_CANCELLED,
}

/** How an event is counted. `NEUTRAL` is a record, not a verdict. */
enum class HistoryOutcome {
    SUCCESS,
    FAILURE,
    NEUTRAL,
}

/**
 * Failure classification constants (F.29 §8).
 *
 * Stored as text so a future build can read an old row without a migration.
 * The set is closed on purpose: every value here is reachable from a real
 * branch of the current controller, and anything else must be recorded as
 * [UNKNOWN] rather than guessed.
 */
object HistoryFailureReason {
    /**
     * The endpoint answered and refused, or the stack reported a terminal
     * failure for this protocol (refused port, rejected TLS handshake, a
     * non-SoftEther answer to the watermark, auth rejection).
     *
     * This is the **only** reason that can make a server-attributable failure.
     */
    const val SERVER_REJECTED = "SERVER_REJECTED"

    /**
     * The per-attempt budget expired with no verdict.
     *
     * Explicitly **not** a server failure: a silent or filtered endpoint looks
     * exactly like a dead one. Counted in `timeoutCount` only.
     */
    const val TIMEOUT_UNATTRIBUTED = "TIMEOUT_UNATTRIBUTED"

    /** This application's own previous native operation still held the slot. */
    const val NATIVE_SLOT_BUSY = "NATIVE_SLOT_BUSY"

    /** The protocol had no data to dial (no `.ovpn` bytes). A data defect. */
    const val DATA_MISSING_CONFIG = "DATA_MISSING_CONFIG"

    /** The VPN permission is missing. Local. */
    const val PERMISSION_DENIED = "PERMISSION_DENIED"

    /** The user skipped or stopped. Local. */
    const val USER_CANCELLED = "USER_CANCELLED"

    /** No physical network at all. Local/network, never the server's fault. */
    const val NETWORK_UNAVAILABLE = "NETWORK_UNAVAILABLE"

    /** The connect call threw before any verdict existed. Local. */
    const val CONNECT_THREW = "CONNECT_THREW"

    /** Every protocol was tried and none produced a verdict worth recording. */
    const val NO_VERDICT = "NO_VERDICT"

    /** The OS revoked the tunnel. Attribution unknown. */
    const val LOST_EXTERNAL = "LOST_EXTERNAL"

    /** `GlobalVpnTracker` reported the tunnel down on its 1 s poll. */
    const val LOST_PASSIVE = "LOST_PASSIVE"

    /** The liveness watchdog saw no proof of life. Attribution unknown. */
    const val LOST_SILENT = "LOST_SILENT"

    /** Anything the current evidence cannot attribute. */
    const val UNKNOWN = "UNKNOWN"
}

/**
 * One fact to persist.
 *
 * Deliberately a plain data class rather than a Room entity: the controller
 * builds this, the sink persists it, and the mapping between the two lives in
 * exactly one place ([vn.unlimit.vpngate.history.ServerHistoryEvent.fromRecord]).
 *
 * [at] is supplied by the caller so the whole record shares one timestamp and
 * tests can be deterministic. [token] is the per-attempt correlation string
 * (`run|server|protocol`); it exists to make the success record idempotent, not
 * to model a connection attempt.
 */
data class HistoryRecord(
    val hostName: String,
    val ip: String?,
    val eventType: HistoryEventType,
    val outcome: HistoryOutcome,
    val networkType: NetworkType,
    val protocol: String?,
    val token: String?,
    val durationMs: Long?,
    val failureReason: String?,
    val at: Long,
)

/**
 * The write contract.
 *
 * A sink may fail — a locked database, a full disk, a corrupt file. F.29 §17
 * requires that failure to be **invisible to the connection result**: the
 * caller catches and logs, and the tunnel that came up stays up.
 */
fun interface ServerHistorySink {
    suspend fun record(record: HistoryRecord)
}

/**
 * The **read** model for ordering.
 *
 * Narrow on purpose (F.29 §10.3): the ranking pass needs eight integers and one
 * timestamp, so it reads exactly those instead of loading a 20-column summary
 * per server or scanning the event table. `null`/absent means *no history*,
 * which the ordering treats as "unknown", never as "bad".
 */
data class ServerHistoryView(
    val hostName: String,
    val successCount: Int,
    val failureCount: Int,
    val consecutiveFailures: Int,
    val lastSuccessAt: Long,
    val wifiSuccessCount: Int,
    val cellularSuccessCount: Int,
    val unknownNetworkSuccessCount: Int,
    // ── BLOCK v1.0.69 PART 1 ───────────────────────────────────────────
    val timeoutCount: Int = 0,
    val qualityRejectionCount: Int = 0,
    val wifiConsecutiveFailures: Int = 0,
    val cellularConsecutiveFailures: Int = 0,
    val unknownConsecutiveFailures: Int = 0,
    val lastRefreshSeenAt: Long? = null,
    val firstListedAt: Long? = null,
    // ── BLOCK v1.0.75 PART C (D-4/D-5/D-7/D-8) ─────────────────────────
    /** Protocol of the last verified success — the remembered-protocol hint (D-6). */
    val lastSuccessProtocol: String? = null,
    /** Last protocol attempt of any kind — the exploration pick's age key (C7). */
    val lastAttemptAt: Long? = null,
    /** Consecutive accepted fresh refreshes that did not list this host (D-8). */
    val missedRefreshCount: Int = 0,
    /** Per-network last server-attributable failure — the cooldown clock (D-7). */
    val wifiLastFailureAt: Long? = null,
    val cellularLastFailureAt: Long? = null,
    val unknownLastFailureAt: Long? = null,
) {
    /** Successes proven on [network]. For [NetworkType.UNKNOWN] this is its own bucket. */
    fun successesOn(network: NetworkType): Int = when (network) {
        NetworkType.WIFI -> wifiSuccessCount
        NetworkType.CELLULAR -> cellularSuccessCount
        NetworkType.UNKNOWN -> unknownNetworkSuccessCount
    }

    /**
     * BLOCK v1.0.69 (OD-6): history exists only when an **attempt** was recorded.
     * A summary row can be created by a refresh for a host that was never
     * attempted, so "row exists" is not "has history"; such a row must score
     * exactly like no row at all.
     */
    val hasHistory: Boolean
        get() = successCount + failureCount + timeoutCount > 0

    /** Consecutive server-attributable failures on [network] (PART 1). */
    fun consecutiveFailuresOn(network: NetworkType): Int = when (network) {
        NetworkType.WIFI -> wifiConsecutiveFailures
        NetworkType.CELLULAR -> cellularConsecutiveFailures
        NetworkType.UNKNOWN -> unknownConsecutiveFailures
    }

    /** BLOCK v1.0.75 (D-7): when this server last failed ON [network], or null. */
    fun lastFailureAtOn(network: NetworkType): Long? = when (network) {
        NetworkType.WIFI -> wifiLastFailureAt
        NetworkType.CELLULAR -> cellularLastFailureAt
        NetworkType.UNKNOWN -> unknownLastFailureAt
    }
}
