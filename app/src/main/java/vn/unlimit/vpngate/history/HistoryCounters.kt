package vn.unlimit.vpngate.history

/**
 * F.29 §8/§9 — the counter arithmetic, as a pure function.
 *
 * Everything the recorder does to the two summary tables is decided here, in
 * code with no database and no coroutine, so the rules that matter can be
 * exhaustively tested:
 *
 *   - a **timeout** never touches a failure counter;
 *   - a **local** event (busy slot, missing config, permission, cancel, throw)
 *     never touches a counter at all;
 *   - only [HistoryEventType.SERVER_ABANDONED] with [HistoryOutcome.FAILURE]
 *     raises the server-level failure counters, and the controller only marks
 *     it `FAILURE` when a protocol was actually rejected at the endpoint;
 *   - a **success** resets `consecutiveFailures`, so a server is never punished
 *     forever for one bad afternoon;
 *   - a **connection loss** never erases a success.
 *
 * The functions are total: `current == null` means "first event for this
 * identity", and the result is a row carrying only what this record proves.
 */
object HistoryCounters {

    /**
     * The next summary row for [record].
     *
     * @param current the stored row, or null when this identity has no history
     *   yet. Absence is **not** treated as a zero-quality row: it simply starts
     *   from empty counters.
     */
    fun nextSummary(current: ServerHistorySummary?, record: HistoryRecord): ServerHistorySummary {
        val base = current ?: ServerHistorySummary(hostName = record.hostName)

        // First observation is stamped once and never moved.
        val firstObservedAt = base.firstObservedAt ?: record.at

        return when (record.eventType) {
            HistoryEventType.SERVER_FIRST_SEEN -> base.copy(firstObservedAt = firstObservedAt)

            HistoryEventType.CONNECTED -> base.copy(
                firstObservedAt = firstObservedAt,
                lastAttemptAt = record.at,
                lastSuccessAt = record.at,
                successCount = base.successCount + 1,
                // A verified tunnel is proof; the failure streak ends here.
                consecutiveFailures = 0,
                lastSuccessProtocol = record.protocol ?: base.lastSuccessProtocol,
                lastSuccessNetwork = record.networkType.name,
                wifiSuccessCount = base.wifiSuccessCount + record.ifNetwork(NetworkType.WIFI),
                cellularSuccessCount = base.cellularSuccessCount + record.ifNetwork(NetworkType.CELLULAR),
                unknownNetworkSuccessCount =
                base.unknownNetworkSuccessCount + record.ifNetwork(NetworkType.UNKNOWN),
                // v1.0.69 PART 1: a success on this network clears that network's
                // failure streak; the other networks' streaks are untouched.
                wifiConsecutiveFailures =
                if (record.networkType == NetworkType.WIFI) 0 else base.wifiConsecutiveFailures,
                cellularConsecutiveFailures =
                if (record.networkType == NetworkType.CELLULAR) 0 else base.cellularConsecutiveFailures,
                unknownConsecutiveFailures =
                if (record.networkType == NetworkType.UNKNOWN) 0 else base.unknownConsecutiveFailures,
                // BLOCK v1.0.75 (D-7): a success clears this network's cooldown.
                wifiLastFailureAt = if (record.networkType == NetworkType.WIFI) null else base.wifiLastFailureAt,
                cellularLastFailureAt =
                if (record.networkType == NetworkType.CELLULAR) null else base.cellularLastFailureAt,
                unknownLastFailureAt =
                if (record.networkType == NetworkType.UNKNOWN) null else base.unknownLastFailureAt,
            )

            HistoryEventType.PROTOCOL_ATTEMPT -> when {
                // A timeout is "no answer", not "proven dead" (§8.3).
                record.failureReason == HistoryFailureReason.TIMEOUT_UNATTRIBUTED -> base.copy(
                    firstObservedAt = firstObservedAt,
                    lastAttemptAt = record.at,
                    lastTimeoutAt = record.at,
                    timeoutCount = base.timeoutCount + 1,
                )
                // Everything else at attempt level is per-protocol detail only.
                // SERVER_REJECTED is deliberately NOT a server-level failure here:
                // one refused protocol must not condemn a server that still has a
                // usable one (§8.2 / §12).
                else -> base.copy(
                    firstObservedAt = firstObservedAt,
                    lastAttemptAt = record.at,
                )
            }

            HistoryEventType.SERVER_ABANDONED -> when (record.outcome) {
                HistoryOutcome.FAILURE -> base.copy(
                    firstObservedAt = firstObservedAt,
                    lastAttemptAt = record.at,
                    lastFailureAt = record.at,
                    failureCount = base.failureCount + 1,
                    consecutiveFailures = base.consecutiveFailures + 1,
                    lastFailureReason = record.failureReason ?: HistoryFailureReason.SERVER_REJECTED,
                    wifiFailureCount = base.wifiFailureCount + record.ifNetwork(NetworkType.WIFI),
                    cellularFailureCount =
                    base.cellularFailureCount + record.ifNetwork(NetworkType.CELLULAR),
                    unknownNetworkFailureCount =
                    base.unknownNetworkFailureCount + record.ifNetwork(NetworkType.UNKNOWN),
                    // v1.0.69 PART 1: the same-network failure streak.
                    wifiConsecutiveFailures =
                    base.wifiConsecutiveFailures + record.ifNetwork(NetworkType.WIFI),
                    cellularConsecutiveFailures =
                    base.cellularConsecutiveFailures + record.ifNetwork(NetworkType.CELLULAR),
                    unknownConsecutiveFailures =
                    base.unknownConsecutiveFailures + record.ifNetwork(NetworkType.UNKNOWN),
                    // BLOCK v1.0.75 (D-7): stamp the cooldown clock for the
                    // network the failure happened on.
                    wifiLastFailureAt =
                    if (record.networkType == NetworkType.WIFI) record.at else base.wifiLastFailureAt,
                    cellularLastFailureAt =
                    if (record.networkType == NetworkType.CELLULAR) record.at else base.cellularLastFailureAt,
                    unknownLastFailureAt =
                    if (record.networkType == NetworkType.UNKNOWN) record.at else base.unknownLastFailureAt,
                )
                // Abandoned without a verdict — every protocol timed out, or was
                // skipped locally. Recorded, not counted.
                HistoryOutcome.NEUTRAL, HistoryOutcome.SUCCESS -> base.copy(
                    firstObservedAt = firstObservedAt,
                    lastAttemptAt = record.at,
                )
            }

            // An established tunnel that was later lost. The success stands.
            HistoryEventType.CONNECTION_LOST -> base.copy(firstObservedAt = firstObservedAt)

            HistoryEventType.ATTEMPT_CANCELLED -> base.copy(
                firstObservedAt = firstObservedAt,
                lastAttemptAt = record.at,
            )

            // Reserved (OD-5): no producer exists, so this branch is unreachable
            // in production. It is implemented anyway so the counter can never be
            // wired to the wrong event by accident.
            HistoryEventType.QUALITY_REJECTED -> base.copy(
                firstObservedAt = firstObservedAt,
                qualityRejectionCount = base.qualityRejectionCount + 1,
            )
        }
    }

    /**
     * The next protocol row, or null when [record] says nothing about a protocol.
     *
     * Null for: server-level events ([HistoryEventType.SERVER_ABANDONED]), losses,
     * cancellations, first-sightings, and any record without a protocol. Creating a
     * protocol row from those would invent a per-protocol fact out of a
     * server-level one.
     */
    fun nextProtocol(current: ServerProtocolSummary?, record: HistoryRecord): ServerProtocolSummary? {
        val protocol = record.protocol ?: return null
        return when (record.eventType) {
            HistoryEventType.CONNECTED -> {
                val base = current ?: ServerProtocolSummary(record.hostName, protocol)
                base.copy(
                    successCount = base.successCount + 1,
                    lastSuccessAt = record.at,
                    lastOutcome = HistoryEventType.CONNECTED.name,
                )
            }

            HistoryEventType.PROTOCOL_ATTEMPT -> {
                val base = current ?: ServerProtocolSummary(record.hostName, protocol)
                when (record.failureReason) {
                    HistoryFailureReason.SERVER_REJECTED -> base.copy(
                        failureCount = base.failureCount + 1,
                        lastFailureAt = record.at,
                        lastOutcome = record.failureReason,
                    )
                    HistoryFailureReason.TIMEOUT_UNATTRIBUTED -> base.copy(
                        timeoutCount = base.timeoutCount + 1,
                        lastOutcome = record.failureReason,
                    )
                    else -> base.copy(lastOutcome = record.failureReason ?: HistoryFailureReason.UNKNOWN)
                }
            }

            else -> null
        }
    }

    /** 1 when [record] happened on this network, else 0 — the increment helper. */
    private fun HistoryRecord.ifNetwork(network: NetworkType): Int =
        if (networkType == network) 1 else 0
}
