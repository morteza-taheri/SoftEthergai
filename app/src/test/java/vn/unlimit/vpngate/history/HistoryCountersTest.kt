package vn.unlimit.vpngate.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F.29 §8/§9 — the counter arithmetic, exhaustively.
 *
 * These rules are the whole reason the history layer is trustworthy enough to
 * reorder anything, so each one is pinned on its own:
 *
 *  - a timeout is "no answer", never a failure (§8.3);
 *  - local causes (busy slot, missing config, permission, cancel, throw, no
 *    network) never touch a server-failure counter (§8.3);
 *  - only an endpoint-rejected abandonment is a server failure (§8.2);
 *  - a success resets the streak (§8.6);
 *  - a loss never erases a success (§8.4);
 *  - `firstObservedAt` is stamped once and never moved (§9.1).
 */
class HistoryCountersTest {

    private fun record(
        eventType: HistoryEventType,
        outcome: HistoryOutcome = HistoryOutcome.NEUTRAL,
        reason: String? = null,
        network: NetworkType = NetworkType.WIFI,
        protocol: String? = "openvpn_udp",
        at: Long = 1_000L,
        token: String? = null,
    ) = HistoryRecord(
        hostName = "vpn.example.com",
        ip = "1.2.3.4",
        eventType = eventType,
        outcome = outcome,
        networkType = network,
        protocol = protocol,
        token = token,
        durationMs = 250L,
        failureReason = reason,
        at = at,
    )

    // ---------------------------------------------------------------------
    // First observation
    // ---------------------------------------------------------------------

    @Test
    fun `a null current row starts from empty counters`() {
        val summary = HistoryCounters.nextSummary(null, record(HistoryEventType.SERVER_FIRST_SEEN))
        assertEquals("vpn.example.com", summary.hostName)
        assertEquals(1_000L, summary.firstObservedAt)
        assertEquals(0, summary.successCount)
        assertEquals(0, summary.failureCount)
        assertEquals(0, summary.timeoutCount)
        assertEquals(0, summary.consecutiveFailures)
        assertNull(summary.lastSuccessAt)
        assertNull(summary.lastFailureAt)
        assertNull(summary.lastTimeoutAt)
    }

    @Test
    fun `firstObservedAt is stamped once and never moved`() {
        val first = HistoryCounters.nextSummary(
            null,
            record(HistoryEventType.SERVER_FIRST_SEEN, at = 1_000L),
        )
        assertEquals(1_000L, first.firstObservedAt)

        // A later event on the same identity must not move it.
        val later = HistoryCounters.nextSummary(
            first,
            record(HistoryEventType.CONNECTED, HistoryOutcome.SUCCESS, at = 9_000L),
        )
        assertEquals(1_000L, later.firstObservedAt)
        assertEquals(9_000L, later.lastSuccessAt)
    }

    // ---------------------------------------------------------------------
    // Success
    // ---------------------------------------------------------------------

    @Test
    fun `a success increments the success count and the matching network bucket`() {
        val wifi = HistoryCounters.nextSummary(
            null,
            record(HistoryEventType.CONNECTED, HistoryOutcome.SUCCESS, network = NetworkType.WIFI),
        )
        assertEquals(1, wifi.successCount)
        assertEquals(1, wifi.wifiSuccessCount)
        assertEquals(0, wifi.cellularSuccessCount)
        assertEquals(0, wifi.unknownNetworkSuccessCount)

        val cellular = HistoryCounters.nextSummary(
            wifi,
            record(HistoryEventType.CONNECTED, HistoryOutcome.SUCCESS, network = NetworkType.CELLULAR, at = 2_000L),
        )
        assertEquals(2, cellular.successCount)
        assertEquals(1, cellular.wifiSuccessCount)
        assertEquals(1, cellular.cellularSuccessCount)
        assertEquals(0, cellular.unknownNetworkSuccessCount)
        assertEquals(2_000L, cellular.lastSuccessAt)
        assertEquals(NetworkType.CELLULAR.name, cellular.lastSuccessNetwork)
    }

    @Test
    fun `an unknown network success is counted in its own bucket and nowhere else`() {
        val summary = HistoryCounters.nextSummary(
            null,
            record(HistoryEventType.CONNECTED, HistoryOutcome.SUCCESS, network = NetworkType.UNKNOWN),
        )
        assertEquals(1, summary.successCount)
        assertEquals(1, summary.unknownNetworkSuccessCount)
        assertEquals(0, summary.wifiSuccessCount)
        assertEquals(0, summary.cellularSuccessCount)
    }

    @Test
    fun `a success resets the consecutive failure streak`() {
        var summary = HistoryCounters.nextSummary(
            null,
            record(HistoryEventType.SERVER_ABANDONED, HistoryOutcome.FAILURE, reason = HistoryFailureReason.SERVER_REJECTED),
        )
        summary = HistoryCounters.nextSummary(
            summary,
            record(HistoryEventType.SERVER_ABANDONED, HistoryOutcome.FAILURE, reason = HistoryFailureReason.SERVER_REJECTED, at = 2_000L),
        )
        assertEquals(2, summary.consecutiveFailures)
        assertEquals(2, summary.failureCount)

        summary = HistoryCounters.nextSummary(
            summary,
            record(HistoryEventType.CONNECTED, HistoryOutcome.SUCCESS, at = 3_000L),
        )
        assertEquals(0, summary.consecutiveFailures)
        // The lifetime totals are not erased by the reset.
        assertEquals(2, summary.failureCount)
        assertEquals(1, summary.successCount)
    }

    // ---------------------------------------------------------------------
    // Timeouts (F.29 §8.3)
    // ---------------------------------------------------------------------

    @Test
    fun `a timeout increments only the timeout counters`() {
        val summary = HistoryCounters.nextSummary(
            null,
            record(HistoryEventType.PROTOCOL_ATTEMPT, HistoryOutcome.NEUTRAL, reason = HistoryFailureReason.TIMEOUT_UNATTRIBUTED),
        )
        assertEquals(1, summary.timeoutCount)
        assertEquals(1_000L, summary.lastTimeoutAt)
        assertEquals(0, summary.failureCount)
        assertEquals(0, summary.consecutiveFailures)
        assertNull("a timeout must never set lastFailureAt", summary.lastFailureAt)
        assertEquals(0, summary.wifiFailureCount)
    }

    @Test
    fun `a timeout never turns into a server failure later`() {
        var summary = HistoryCounters.nextSummary(
            null,
            record(HistoryEventType.PROTOCOL_ATTEMPT, HistoryOutcome.NEUTRAL, reason = HistoryFailureReason.TIMEOUT_UNATTRIBUTED),
        )
        // The server is then abandoned with no endpoint rejection — the honest
        // outcome for "every protocol was silent".
        summary = HistoryCounters.nextSummary(
            summary,
            record(HistoryEventType.SERVER_ABANDONED, HistoryOutcome.NEUTRAL, reason = HistoryFailureReason.NO_VERDICT, at = 2_000L),
        )
        assertEquals(1, summary.timeoutCount)
        assertEquals(0, summary.failureCount)
        assertEquals(0, summary.consecutiveFailures)
    }

    // ---------------------------------------------------------------------
    // Local causes (F.29 §8.3)
    // ---------------------------------------------------------------------

    @Test
    fun `local causes never touch a failure counter`() {
        val localReasons = listOf(
            HistoryFailureReason.NATIVE_SLOT_BUSY,
            HistoryFailureReason.DATA_MISSING_CONFIG,
            HistoryFailureReason.PERMISSION_DENIED,
            HistoryFailureReason.USER_CANCELLED,
            HistoryFailureReason.NETWORK_UNAVAILABLE,
            HistoryFailureReason.CONNECT_THREW,
        )
        for (reason in localReasons) {
            val summary = HistoryCounters.nextSummary(
                null,
                record(HistoryEventType.PROTOCOL_ATTEMPT, HistoryOutcome.NEUTRAL, reason = reason),
            )
            assertEquals("failureCount for $reason", 0, summary.failureCount)
            assertEquals("consecutiveFailures for $reason", 0, summary.consecutiveFailures)
            assertEquals("timeoutCount for $reason", 0, summary.timeoutCount)
            assertEquals("wifiFailureCount for $reason", 0, summary.wifiFailureCount)
            assertEquals("lastAttemptAt for $reason", 1_000L, summary.lastAttemptAt)
        }
    }

    @Test
    fun `a user cancellation is a record and not a verdict`() {
        val summary = HistoryCounters.nextSummary(
            null,
            record(HistoryEventType.ATTEMPT_CANCELLED, HistoryOutcome.NEUTRAL, reason = HistoryFailureReason.USER_CANCELLED),
        )
        assertEquals(0, summary.failureCount)
        assertEquals(0, summary.timeoutCount)
        assertEquals(1_000L, summary.lastAttemptAt)
    }

    // ---------------------------------------------------------------------
    // Server-attributable failure (F.29 §8.2)
    // ---------------------------------------------------------------------

    @Test
    fun `an endpoint-rejected abandonment is the only server failure`() {
        val summary = HistoryCounters.nextSummary(
            null,
            record(
                HistoryEventType.SERVER_ABANDONED,
                HistoryOutcome.FAILURE,
                reason = HistoryFailureReason.SERVER_REJECTED,
                network = NetworkType.CELLULAR,
            ),
        )
        assertEquals(1, summary.failureCount)
        assertEquals(1, summary.consecutiveFailures)
        assertEquals(1, summary.cellularFailureCount)
        assertEquals(1_000L, summary.lastFailureAt)
        assertEquals(HistoryFailureReason.SERVER_REJECTED, summary.lastFailureReason)
    }

    @Test
    fun `a single refused protocol at attempt level does not condemn the server`() {
        // SERVER_REJECTED at PROTOCOL_ATTEMPT level is per-protocol detail: the
        // server may still have a usable protocol, so no server-level failure.
        val summary = HistoryCounters.nextSummary(
            null,
            record(HistoryEventType.PROTOCOL_ATTEMPT, HistoryOutcome.FAILURE, reason = HistoryFailureReason.SERVER_REJECTED),
        )
        assertEquals(0, summary.failureCount)
        assertEquals(0, summary.consecutiveFailures)
        assertEquals(0, summary.wifiFailureCount)
        assertEquals(1_000L, summary.lastAttemptAt)
    }

    @Test
    fun `an abandoned with no verdict is recorded and not counted`() {
        val summary = HistoryCounters.nextSummary(
            null,
            record(HistoryEventType.SERVER_ABANDONED, HistoryOutcome.NEUTRAL, reason = HistoryFailureReason.NO_VERDICT),
        )
        assertEquals(0, summary.failureCount)
        assertEquals(0, summary.consecutiveFailures)
        assertEquals(1_000L, summary.lastAttemptAt)
    }

    @Test
    fun `a failure defaults its reason to SERVER_REJECTED when none is supplied`() {
        val summary = HistoryCounters.nextSummary(
            null,
            record(HistoryEventType.SERVER_ABANDONED, HistoryOutcome.FAILURE, reason = null),
        )
        assertEquals(HistoryFailureReason.SERVER_REJECTED, summary.lastFailureReason)
    }

    // ---------------------------------------------------------------------
    // Connection loss (F.29 §8.4)
    // ---------------------------------------------------------------------

    @Test
    fun `a loss never erases a success`() {
        var summary = HistoryCounters.nextSummary(
            null,
            record(HistoryEventType.CONNECTED, HistoryOutcome.SUCCESS),
        )
        summary = HistoryCounters.nextSummary(
            summary,
            record(
                HistoryEventType.CONNECTION_LOST,
                HistoryOutcome.NEUTRAL,
                reason = HistoryFailureReason.LOST_PASSIVE,
                at = 5_000L,
            ),
        )
        assertEquals(1, summary.successCount)
        assertEquals(1_000L, summary.lastSuccessAt)
        assertEquals(0, summary.failureCount)
        assertEquals(0, summary.consecutiveFailures)
        assertNull("a loss must never set lastFailureAt", summary.lastFailureAt)
    }

    // ---------------------------------------------------------------------
    // Reserved quality rejection (OD-5)
    // ---------------------------------------------------------------------

    @Test
    fun `the reserved quality counter exists and is reachable only by its own event`() {
        val summary = HistoryCounters.nextSummary(
            null,
            record(HistoryEventType.QUALITY_REJECTED, HistoryOutcome.NEUTRAL),
        )
        assertEquals(1, summary.qualityRejectionCount)
        // It must not look like a failure or a success.
        assertEquals(0, summary.failureCount)
        assertEquals(0, summary.successCount)
        assertEquals(0, summary.consecutiveFailures)
    }

    // ---------------------------------------------------------------------
    // Per-protocol rows
    // ---------------------------------------------------------------------

    @Test
    fun `a success writes a protocol row`() {
        val row = HistoryCounters.nextProtocol(
            null,
            record(HistoryEventType.CONNECTED, HistoryOutcome.SUCCESS, protocol = "softether_tcp"),
        )
        assertEquals("softether_tcp", row!!.protocol)
        assertEquals(1, row.successCount)
        assertEquals(1_000L, row.lastSuccessAt)
        assertEquals(HistoryEventType.CONNECTED.name, row.lastOutcome)
    }

    @Test
    fun `a refused protocol writes a protocol failure row`() {
        val row = HistoryCounters.nextProtocol(
            null,
            record(HistoryEventType.PROTOCOL_ATTEMPT, HistoryOutcome.FAILURE, reason = HistoryFailureReason.SERVER_REJECTED),
        )
        assertEquals(1, row!!.failureCount)
        assertEquals(1_000L, row.lastFailureAt)
        assertEquals(HistoryFailureReason.SERVER_REJECTED, row.lastOutcome)
    }

    @Test
    fun `a protocol timeout writes a protocol timeout row and not a failure`() {
        val row = HistoryCounters.nextProtocol(
            null,
            record(HistoryEventType.PROTOCOL_ATTEMPT, HistoryOutcome.NEUTRAL, reason = HistoryFailureReason.TIMEOUT_UNATTRIBUTED),
        )
        assertEquals(0, row!!.failureCount)
        assertEquals(1, row.timeoutCount)
        assertEquals(HistoryFailureReason.TIMEOUT_UNATTRIBUTED, row.lastOutcome)
    }

    @Test
    fun `server-level events produce no protocol row`() {
        val events = listOf(
            HistoryEventType.SERVER_ABANDONED,
            HistoryEventType.CONNECTION_LOST,
            HistoryEventType.SERVER_FIRST_SEEN,
            HistoryEventType.ATTEMPT_CANCELLED,
            HistoryEventType.QUALITY_REJECTED,
        )
        for (event in events) {
            assertNull("$event must not create a protocol row", HistoryCounters.nextProtocol(null, record(event)))
        }
    }

    @Test
    fun `a record without a protocol produces no protocol row`() {
        assertNull(HistoryCounters.nextProtocol(null, record(HistoryEventType.CONNECTED, HistoryOutcome.SUCCESS, protocol = null)))
    }

    // ---------------------------------------------------------------------
    // Totality
    // ---------------------------------------------------------------------

    @Test
    fun `every event type produces a summary row from a null current`() {
        for (event in HistoryEventType.entries) {
            val summary = HistoryCounters.nextSummary(null, record(event))
            assertTrue("$event must produce a row", summary.hostName == "vpn.example.com")
        }
    }
}
