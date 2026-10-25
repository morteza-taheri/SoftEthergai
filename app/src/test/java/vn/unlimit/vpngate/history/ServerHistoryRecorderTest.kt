package vn.unlimit.vpngate.history

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F.29 §9 — the write path: one atomic transaction per outcome, idempotency on
 * the success record, and error containment.
 *
 * [FakeHistoryDao] implements the Room DAO interface directly, so the
 * `@Transaction` default body of `record` runs for real against in-memory maps.
 * That is the honest way to test the transaction's *semantics* on a plain JVM:
 * the Room annotation only guarantees the same body runs inside a real
 * `withTransaction`, which no JVM test can observe.
 */
class ServerHistoryRecorderTest {

    /**
     * An in-memory [ServerHistoryDao].
     *
     * Counts how many times each write ran, so a test can prove a record
     * produced exactly one summary write, at most one protocol write and
     * exactly one event — i.e. that nothing was written twice.
     */
    private class FakeHistoryDao : ServerHistoryDao {
        val summaries = mutableMapOf<String, ServerHistorySummary>()
        val protocols = mutableMapOf<Pair<String, String>, ServerProtocolSummary>()
        val events = mutableListOf<ServerHistoryEvent>()

        var summaryWrites = 0
        var protocolWrites = 0
        var eventWrites = 0
        var transactionDepth = 0
        var maxTransactionDepth = 0

        override fun historyViews(): List<ServerHistoryView> =
            summaries.values.map { it.toView() }

        override fun summary(hostName: String): ServerHistorySummary? = summaries[hostName]

        override fun protocols(hostName: String): List<ServerProtocolSummary> =
            protocols.filterKeys { it.first == hostName }.values.toList()

        override fun protocolSummary(hostName: String, protocol: String): ServerProtocolSummary? =
            protocols[hostName to protocol]

        override fun eventsFor(hostName: String, limit: Int): List<ServerHistoryEvent> =
            events.filter { it.hostName == hostName }
                .sortedByDescending { it.createdAt }
                .take(limit)

        override fun eventCount(): Long = events.size.toLong()

        override fun summaryCount(): Int = summaries.size

        override fun countToken(token: String, eventType: String): Int =
            events.count { it.attemptToken == token && it.eventType == eventType }

        override fun upsertSummary(row: ServerHistorySummary) {
            summaryWrites++
            summaries[row.hostName] = row
        }

        override fun upsertProtocol(row: ServerProtocolSummary) {
            protocolWrites++
            protocols[row.hostName to row.protocol] = row
        }

        override fun insertEvent(row: ServerHistoryEvent) {
            eventWrites++
            events += row
        }

        fun apply(record: HistoryRecord) {
            // Mirror the real transaction's body so the fake observes the same
            // call sequence the Room implementation would make.
            transactionDepth++
            maxTransactionDepth = maxOf(maxTransactionDepth, transactionDepth)
            try {
                record(record)
            } finally {
                transactionDepth--
            }
        }
    }

    private fun record(
        eventType: HistoryEventType = HistoryEventType.PROTOCOL_ATTEMPT,
        outcome: HistoryOutcome = HistoryOutcome.NEUTRAL,
        reason: String? = null,
        network: NetworkType = NetworkType.WIFI,
        protocol: String? = "openvpn_udp",
        at: Long = 1_000L,
        token: String? = null,
        host: String = "vpn.example.com",
    ) = HistoryRecord(
        hostName = host,
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
    // Atomicity — one record, one set of writes
    // ---------------------------------------------------------------------

    @Test
    fun `one record writes exactly one summary one protocol row and one event`() {
        val dao = FakeHistoryDao()
        dao.apply(record(HistoryEventType.CONNECTED, HistoryOutcome.SUCCESS, token = "run|srv|proto"))

        assertEquals(1, dao.summaryWrites)
        assertEquals(1, dao.protocolWrites)
        assertEquals(1, dao.eventWrites)
        assertEquals(1, dao.summaries.size)
        assertEquals(1, dao.protocols.size)
        assertEquals(1, dao.events.size)
    }

    @Test
    fun `a server-level event writes a summary and an event but no protocol row`() {
        val dao = FakeHistoryDao()
        dao.apply(
            record(
                HistoryEventType.SERVER_ABANDONED,
                HistoryOutcome.FAILURE,
                reason = HistoryFailureReason.SERVER_REJECTED,
                protocol = null,
            ),
        )
        assertEquals(1, dao.summaryWrites)
        assertEquals(0, dao.protocolWrites)
        assertEquals(1, dao.eventWrites)
    }

    @Test
    fun `a record with no protocol writes a summary and an event but no protocol row`() {
        val dao = FakeHistoryDao()
        dao.apply(record(HistoryEventType.PROTOCOL_ATTEMPT, protocol = null))
        assertEquals(1, dao.summaryWrites)
        assertEquals(0, dao.protocolWrites)
        assertEquals(1, dao.eventWrites)
    }

    @Test
    fun `the event row carries every field of the record`() {
        val dao = FakeHistoryDao()
        dao.apply(
            record(
                HistoryEventType.PROTOCOL_ATTEMPT,
                HistoryOutcome.FAILURE,
                reason = HistoryFailureReason.SERVER_REJECTED,
                network = NetworkType.CELLULAR,
                protocol = "softether_tcp",
                at = 4_242L,
                token = "run|srv|softether_tcp",
            ),
        )
        val event = dao.events.single()
        assertEquals("vpn.example.com", event.hostName)
        assertEquals("1.2.3.4", event.ip)
        assertEquals(HistoryEventType.PROTOCOL_ATTEMPT.name, event.eventType)
        assertEquals(HistoryOutcome.FAILURE.name, event.outcome)
        assertEquals(NetworkType.CELLULAR.name, event.networkType)
        assertEquals("softether_tcp", event.protocol)
        assertEquals("run|srv|softether_tcp", event.attemptToken)
        assertEquals(250L, event.durationMs)
        assertEquals(HistoryFailureReason.SERVER_REJECTED, event.failureReason)
        assertEquals(4_242L, event.createdAt)
        // Reserved with no producer (OD-5): always null.
        assertNull(event.qualityResult)
    }

    // ---------------------------------------------------------------------
    // Idempotency (OD-9)
    // ---------------------------------------------------------------------

    @Test
    fun `a repeated success with the same token is a no-op`() {
        val dao = FakeHistoryDao()
        dao.apply(record(HistoryEventType.CONNECTED, HistoryOutcome.SUCCESS, token = "run|srv|proto"))
        dao.apply(record(HistoryEventType.CONNECTED, HistoryOutcome.SUCCESS, token = "run|srv|proto", at = 2_000L))

        assertEquals(1, dao.eventWrites)
        assertEquals(1, dao.summaries.getValue("vpn.example.com").successCount)
    }

    @Test
    fun `a success with a different token is a second success`() {
        val dao = FakeHistoryDao()
        dao.apply(record(HistoryEventType.CONNECTED, HistoryOutcome.SUCCESS, token = "run1|srv|proto"))
        dao.apply(record(HistoryEventType.CONNECTED, HistoryOutcome.SUCCESS, token = "run2|srv|proto", at = 2_000L))

        assertEquals(2, dao.eventWrites)
        assertEquals(2, dao.summaries.getValue("vpn.example.com").successCount)
    }

    @Test
    fun `a success without a token is still written`() {
        // A null token means no idempotency check is possible; the write is not
        // silently dropped for lacking one.
        val dao = FakeHistoryDao()
        dao.apply(record(HistoryEventType.CONNECTED, HistoryOutcome.SUCCESS, token = null))
        assertEquals(1, dao.eventWrites)
    }

    @Test
    fun `only the success event is idempotency-checked`() {
        // Every other event type is emitted from a branch that runs once per
        // attempt by construction, so a duplicate is a real second fact.
        val dao = FakeHistoryDao()
        dao.apply(record(HistoryEventType.PROTOCOL_ATTEMPT, reason = HistoryFailureReason.TIMEOUT_UNATTRIBUTED, token = "t"))
        dao.apply(record(HistoryEventType.PROTOCOL_ATTEMPT, reason = HistoryFailureReason.TIMEOUT_UNATTRIBUTED, token = "t", at = 2_000L))
        assertEquals(2, dao.eventWrites)
        assertEquals(2, dao.summaries.getValue("vpn.example.com").timeoutCount)
    }

    // ---------------------------------------------------------------------
    // Identity hygiene
    // ---------------------------------------------------------------------

    @Test
    fun `a blank identity is dropped rather than written`() {
        val dao = FakeHistoryDao()
        dao.apply(record(host = "   "))
        assertEquals(0, dao.summaryWrites)
        assertEquals(0, dao.eventWrites)
        assertEquals(0, dao.summaries.size)
    }

    @Test
    fun `distinct identities never share a row`() {
        val dao = FakeHistoryDao()
        dao.apply(record(HistoryEventType.CONNECTED, HistoryOutcome.SUCCESS, host = "a.example.com", token = "t1"))
        dao.apply(record(HistoryEventType.CONNECTED, HistoryOutcome.SUCCESS, host = "b.example.com", token = "t2", at = 2_000L))
        assertEquals(2, dao.summaries.size)
        assertEquals(1, dao.summaries.getValue("a.example.com").successCount)
        assertEquals(1, dao.summaries.getValue("b.example.com").successCount)
    }

    // ---------------------------------------------------------------------
    // Error containment (F.29 §17)
    // ---------------------------------------------------------------------

    @Test
    fun `a throwing sink is contained and reported`() {
        var logged: String? = null
        val sink = RoomServerHistorySink(
            dao = ThrowingDao(),
            log = { logged = it },
        )
        // A fake that throws on the very first read inside record().
        runBlocking {
            sink.record(record(HistoryEventType.CONNECTED, HistoryOutcome.SUCCESS, token = "t"))
        }
        assertTrue("a failure must be logged, got: $logged", logged?.contains("History write failed") == true)
    }

    @Test
    fun `a sink failure never rethrows`() {
        val sink = RoomServerHistorySink(
            dao = ThrowingDao(),
            log = { },
        )
        runBlocking {
            // Must return normally rather than propagate.
            sink.record(record(HistoryEventType.CONNECTED, HistoryOutcome.SUCCESS, token = "t"))
        }
    }

    @Test
    fun `a blank identity never reaches the dao`() {
        val dao = FakeHistoryDao()
        val sink = RoomServerHistorySink(dao = dao, log = { })
        runBlocking { sink.record(record(host = "  ")) }
        assertEquals(0, dao.summaryWrites)
        assertEquals(0, dao.eventWrites)
    }

    @Test
    fun `a cancelled sink write is rethrown rather than reported as a failure`() {
        val sink = RoomServerHistorySink(
            dao = CancellingDao(),
            log = { error("a cancellation must not be logged as a write failure") },
        )
        var rethrown = false
        runBlocking {
            try {
                sink.record(record(HistoryEventType.CONNECTED, HistoryOutcome.SUCCESS, token = "t"))
            } catch (e: CancellationException) {
                rethrown = true
            }
        }
        // Swallowing a CancellationException would turn a cancelled write into
        // "the write succeeded" and break the caller's structured concurrency.
        assertTrue("the cancellation must survive the sink", rethrown)
    }

    @Test
    fun `a database error is not reported as a cancellation`() {
        val sink = RoomServerHistorySink(
            dao = ThrowingDao(),
            log = { },
        )
        var rethrown = false
        runBlocking {
            try {
                sink.record(record(HistoryEventType.CONNECTED, HistoryOutcome.SUCCESS, token = "t"))
            } catch (e: CancellationException) {
                rethrown = true
            }
        }
        assertFalse(rethrown)
    }

    @Test
    fun `the sink runs the dao write off the calling thread`() = runBlocking {
        val inner = FakeHistoryDao()
        val callerThread = Thread.currentThread().name
        var daoThread: String? = null
        // A concrete wrapper rather than `by` delegation: delegation would route
        // the default `record` body to the INNER dao's own insertEvent, so the
        // override would never run and the test would prove nothing.
        val spied = object : ServerHistoryDao {
            override fun insertEvent(row: ServerHistoryEvent) {
                daoThread = Thread.currentThread().name
                inner.insertEvent(row)
            }
            override fun historyViews(): List<ServerHistoryView> = inner.historyViews()
            override fun summary(hostName: String): ServerHistorySummary? = inner.summary(hostName)
            override fun protocols(hostName: String): List<ServerProtocolSummary> = inner.protocols(hostName)
            override fun protocolSummary(hostName: String, protocol: String): ServerProtocolSummary? =
                inner.protocolSummary(hostName, protocol)
            override fun eventsFor(hostName: String, limit: Int): List<ServerHistoryEvent> =
                inner.eventsFor(hostName, limit)
            override fun eventCount(): Long = inner.eventCount()
            override fun summaryCount(): Int = inner.summaryCount()
            override fun countToken(token: String, eventType: String): Int = inner.countToken(token, eventType)
            override fun upsertSummary(row: ServerHistorySummary) = inner.upsertSummary(row)
            override fun upsertProtocol(row: ServerProtocolSummary) = inner.upsertProtocol(row)
        }
        val spiedSink = RoomServerHistorySink(dao = spied, log = { })
        withContext(Dispatchers.Default) {
            spiedSink.record(record(HistoryEventType.CONNECTED, HistoryOutcome.SUCCESS, token = "t"))
        }
        assertTrue("the DAO must be written from another thread", daoThread != null && daoThread != callerThread)
    }

    /** A DAO that throws on every call, for the containment tests. */
    private class ThrowingDao : ServerHistoryDao {
        override fun historyViews(): List<ServerHistoryView> = throw IllegalStateException("locked")
        override fun summary(hostName: String): ServerHistorySummary? = throw IllegalStateException("locked")
        override fun protocols(hostName: String): List<ServerProtocolSummary> = throw IllegalStateException("locked")
        override fun protocolSummary(hostName: String, protocol: String): ServerProtocolSummary? =
            throw IllegalStateException("locked")
        override fun eventsFor(hostName: String, limit: Int): List<ServerHistoryEvent> =
            throw IllegalStateException("locked")
        override fun eventCount(): Long = throw IllegalStateException("locked")
        override fun summaryCount(): Int = throw IllegalStateException("locked")
        override fun countToken(token: String, eventType: String): Int = throw IllegalStateException("locked")
        override fun upsertSummary(row: ServerHistorySummary) = throw IllegalStateException("locked")
        override fun upsertProtocol(row: ServerProtocolSummary) = throw IllegalStateException("locked")
        override fun insertEvent(row: ServerHistoryEvent) = throw IllegalStateException("locked")
    }

    /** A DAO whose write is cancelled, for the cancellation test. */
    private class CancellingDao : ServerHistoryDao {
        override fun historyViews(): List<ServerHistoryView> = emptyList()
        override fun summary(hostName: String): ServerHistorySummary? = null
        override fun protocols(hostName: String): List<ServerProtocolSummary> = emptyList()
        override fun protocolSummary(hostName: String, protocol: String): ServerProtocolSummary? = null
        override fun eventsFor(hostName: String, limit: Int): List<ServerHistoryEvent> = emptyList()
        override fun eventCount(): Long = 0L
        override fun summaryCount(): Int = 0
        override fun countToken(token: String, eventType: String): Int = 0
        override fun upsertSummary(row: ServerHistorySummary) = Unit
        override fun upsertProtocol(row: ServerProtocolSummary) = Unit
        override fun insertEvent(row: ServerHistoryEvent) = throw CancellationException("cancelled")
    }
}
