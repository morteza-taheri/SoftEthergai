package vn.unlimit.vpngate.automode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.history.HistoryEventType
import vn.unlimit.vpngate.history.HistoryFailureReason
import vn.unlimit.vpngate.history.HistoryOutcome
import vn.unlimit.vpngate.history.HistoryRecord
import vn.unlimit.vpngate.history.NetworkType
import vn.unlimit.vpngate.history.ServerHistoryView

/**
 * F.29 §8/§10/§17 — the controller wiring.
 *
 * Three questions, each of which the pure-unit tests cannot answer:
 *
 *  1. **Which events actually fire, with which reason** (§8). The lifecycle
 *     hooks are the only thing standing between the feature and a history full
 *     of invented facts.
 *  2. **Does a history failure change a connection result?** (§17). It must
 *     not — this is the one rule the whole design bends around.
 *  3. **Is the pre-F.29 behaviour intact?** (§10). Quality, reachability and
 *     Protocol Priority are all unchanged; history only permutes inside an
 *     equal-quality window.
 */
class AutoModeHistoryRecordingTest {

    /** Everything the controller recorded, in call order. */
    private class RecordingSink : vn.unlimit.vpngate.history.ServerHistorySink {
        val records = mutableListOf<HistoryRecord>()
        override suspend fun record(record: HistoryRecord) {
            records += record
        }
    }

    /** A sink that fails on demand, for the containment tests. */
    private class FailingSink(private val fail: Boolean = true) :
        vn.unlimit.vpngate.history.ServerHistorySink {
        var attempts = 0
        override suspend fun record(record: HistoryRecord) {
            attempts++
            if (fail) throw IllegalStateException("simulated database failure")
        }
    }

    private class RecordingAdapter : AutoModeController.ConnectionAdapter {
        val logs = mutableListOf<String>()
        val connects = mutableListOf<Pair<String?, AutoModeProtocol>>()
        val failing = mutableSetOf<String>()
        val outcomeScripts = mutableMapOf<Pair<String, AutoModeProtocol>, ArrayDeque<AutoModeController.AttemptOutcome>>()

        /** Per-(host, protocol) scripted outcomes, consumed in order. */
        fun script(host: String, protocol: AutoModeProtocol, vararg outcomes: AutoModeController.AttemptOutcome) {
            outcomeScripts[host to protocol] = ArrayDeque(outcomes.toList())
        }

        override suspend fun connect(candidate: AutoModeCandidate, protocol: AutoModeProtocol) {
            connects += candidate.hostname to protocol
            logs += "connect:${candidate.hostname}"
        }

        override suspend fun disconnect() {
            logs += "disconnect"
        }

        override suspend fun awaitTunnel(protocol: AutoModeProtocol, timeoutMs: Long): Boolean {
            val host = logs.lastOrNull { it.startsWith("connect:") }?.removePrefix("connect:") ?: return false
            return host !in failing
        }

        override suspend fun awaitArmedOutcome(
            protocol: AutoModeProtocol,
            timeoutMs: Long,
        ): AutoModeController.AttemptOutcome {
            val host = logs.lastOrNull { it.startsWith("connect:") }?.removePrefix("connect:") ?: return AutoModeController.AttemptOutcome.TIMED_OUT
            val script = outcomeScripts[host to protocol]
            if (script != null && script.isNotEmpty()) return script.removeFirst()
            return if (host in failing) AutoModeController.AttemptOutcome.TIMED_OUT
            else AutoModeController.AttemptOutcome.TUNNEL_UP
        }

        override fun log(message: String) {
            logs += message
        }
    }

    private fun server(host: String, speed: Long = 10_000_000L, ping: Int = 50) = AutoModeCandidate(
        hostname = host,
        ip = "10.0.0.1",
        speed = speed,
        ping = ping,
        sessions = 100,
        score = 1000,
        sources = 2,
        seTcpPort = 443,
        seUdpPort = 0,
        seUdpSupported = true,
        openVpnTcpPort = 0,
        openVpnUdpPort = 0,
        l2tpSupported = false,
        sstpSupported = false,
    )

    private fun controller(
        scope: CoroutineScope,
        adapter: RecordingAdapter,
        servers: List<AutoModeCandidate>,
        sink: vn.unlimit.vpngate.history.ServerHistorySink? = null,
        history: Map<String, ServerHistoryView> = emptyMap(),
        network: NetworkType = NetworkType.WIFI,
        priorities: List<AutoModeProtocol> = listOf(AutoModeProtocol.SOFTETHER_TCP, AutoModeProtocol.SOFTETHER_UDP),
    ) = AutoModeController(
        scope = scope,
        adapter = adapter,
        protocolPriorityProvider = { priorities },
        serverProvider = { servers },
        attemptTimeoutMsProvider = { 300L },
        isNetworkAvailable = { true },
        wedgeTracker = WedgeTracker(),
        historyProvider = { history },
        historySink = sink,
        networkTypeProvider = { network },
    )

    private suspend fun AutoModeController.awaitTerminal(timeoutMs: Long = 10_000): AutoModeState {
        val reached = withTimeoutOrNull(timeoutMs) {
            var result: AutoModeState? = null
            while (result == null) {
                val s = state.value
                if ((s is AutoModeState.Connected || s is AutoModeState.Error) && !isRunning) {
                    result = s
                } else {
                    delay(25)
                }
            }
            result
        }
        return reached ?: error("no terminal state within ${timeoutMs}ms; last=${state.value}")
    }

    private val scopes = mutableListOf<CoroutineScope>()

    private fun newScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        scopes.clear()
    }

    // ==================================================================
    // §8 — which events fire, with which reason
    // ==================================================================

    @Test
    fun `a verified tunnel records exactly one success and nothing else`() = runBlocking {
        val adapter = RecordingAdapter()
        val sink = RecordingSink()
        val c = controller(newScope(), adapter, listOf(server("s1")), sink = sink)

        c.start()
        val terminal = c.awaitTerminal()

        assertTrue("expected Connected, got $terminal", terminal is AutoModeState.Connected)
        val successes = sink.records.filter { it.eventType == HistoryEventType.CONNECTED }
        assertEquals("exactly one success record", 1, successes.size)
        val success = successes.single()
        assertEquals(HistoryOutcome.SUCCESS, success.outcome)
        assertEquals("s1", success.hostName)
        assertEquals("10.0.0.1", success.ip)
        assertEquals(AutoModeProtocol.SOFTETHER_TCP.id, success.protocol)
        assertEquals(NetworkType.WIFI, success.networkType)
        assertNull("a success carries no failure reason", success.failureReason)
        assertTrue("the success carries an attempt token", success.token?.isNotEmpty() == true)
        // No other event type may be emitted for a clean first-try success.
        assertEquals(
            "a clean success must not also emit attempt or abandonment events: ${sink.records.map { it.eventType }}",
            emptyList<HistoryRecord>(),
            sink.records.filter { it.eventType != HistoryEventType.CONNECTED },
        )
    }

    @Test
    fun `a timed-out protocol records an unattributed timeout and not a failure`() = runBlocking {
        val adapter = RecordingAdapter()
        val sink = RecordingSink()
        adapter.script("s1", AutoModeProtocol.SOFTETHER_TCP, AutoModeController.AttemptOutcome.TIMED_OUT)
        adapter.script("s1", AutoModeProtocol.SOFTETHER_UDP, AutoModeController.AttemptOutcome.TIMED_OUT)
        val c = controller(newScope(), adapter, listOf(server("s1")), sink = sink)

        c.start()
        c.awaitTerminal()

        val timeouts = sink.records.filter { it.eventType == HistoryEventType.PROTOCOL_ATTEMPT }
        assertEquals("both protocols timed out", 2, timeouts.size)
        for (t in timeouts) {
            assertEquals(HistoryFailureReason.TIMEOUT_UNATTRIBUTED, t.failureReason)
            assertEquals(HistoryOutcome.NEUTRAL, t.outcome)
        }
        // The server was abandoned with no endpoint rejection, so the
        // abandonment is NEUTRAL and carries NO_VERDICT — never SERVER_REJECTED.
        val abandoned = sink.records.filter { it.eventType == HistoryEventType.SERVER_ABANDONED }
        assertEquals(1, abandoned.size)
        assertEquals(HistoryOutcome.NEUTRAL, abandoned.single().outcome)
        assertEquals(HistoryFailureReason.NO_VERDICT, abandoned.single().failureReason)
    }

    @Test
    fun `an endpoint rejection makes the abandonment a server-attributable failure`() = runBlocking {
        val adapter = RecordingAdapter()
        val sink = RecordingSink()
        adapter.script("s1", AutoModeProtocol.SOFTETHER_TCP, AutoModeController.AttemptOutcome.NATIVE_ERROR)
        adapter.script("s1", AutoModeProtocol.SOFTETHER_UDP, AutoModeController.AttemptOutcome.NATIVE_ERROR)
        val c = controller(newScope(), adapter, listOf(server("s1")), sink = sink)

        c.start()
        c.awaitTerminal()

        val attempts = sink.records.filter { it.eventType == HistoryEventType.PROTOCOL_ATTEMPT }
        assertEquals(2, attempts.size)
        for (a in attempts) {
            assertEquals(HistoryFailureReason.SERVER_REJECTED, a.failureReason)
            assertEquals(HistoryOutcome.FAILURE, a.outcome)
        }
        val abandoned = sink.records.filter { it.eventType == HistoryEventType.SERVER_ABANDONED }.single()
        assertEquals(HistoryOutcome.FAILURE, abandoned.outcome)
        assertEquals(HistoryFailureReason.SERVER_REJECTED, abandoned.failureReason)
    }

    @Test
    fun `a single refused protocol followed by a success records no server failure`() = runBlocking {
        val adapter = RecordingAdapter()
        val sink = RecordingSink()
        // TCP is refused at the endpoint, UDP then connects.
        adapter.script("s1", AutoModeProtocol.SOFTETHER_TCP, AutoModeController.AttemptOutcome.NATIVE_ERROR)
        adapter.script("s1", AutoModeProtocol.SOFTETHER_UDP, AutoModeController.AttemptOutcome.TUNNEL_UP)
        val c = controller(newScope(), adapter, listOf(server("s1")), sink = sink)

        c.start()
        val terminal = c.awaitTerminal()

        assertTrue("expected Connected via UDP, got $terminal", terminal is AutoModeState.Connected)
        assertEquals(
            "one success, one protocol attempt, and NO abandonment: ${sink.records.map { it.eventType }}",
            0,
            sink.records.count { it.eventType == HistoryEventType.SERVER_ABANDONED },
        )
        assertEquals(1, sink.records.count { it.eventType == HistoryEventType.CONNECTED })
        assertEquals(1, sink.records.count { it.eventType == HistoryEventType.PROTOCOL_ATTEMPT })
    }

    @Test
    fun `a native busy refusal is recorded as a local event and never a server failure`() = runBlocking {
        val adapter = RecordingAdapter()
        val sink = RecordingSink()
        // One refusal, then the slot is released and the retry connects.
        adapter.script(
            "s1",
            AutoModeProtocol.SOFTETHER_TCP,
            AutoModeController.AttemptOutcome.NATIVE_BUSY,
            AutoModeController.AttemptOutcome.TUNNEL_UP,
        )
        val c = controller(newScope(), adapter, listOf(server("s1")), sink = sink)

        c.start()
        val terminal = c.awaitTerminal()

        assertTrue("expected Connected, got $terminal", terminal is AutoModeState.Connected)
        val busy = sink.records.filter { it.failureReason == HistoryFailureReason.NATIVE_SLOT_BUSY }
        assertEquals(1, busy.size)
        assertEquals(HistoryOutcome.NEUTRAL, busy.single().outcome)
        // The refusal is a per-protocol record, not a server verdict.
        assertEquals(0, sink.records.count { it.eventType == HistoryEventType.SERVER_ABANDONED && it.outcome == HistoryOutcome.FAILURE })
    }

    @Test
    fun `every protocol failing on every server records one abandonment per server`() = runBlocking {
        val adapter = RecordingAdapter()
        val sink = RecordingSink()
        for (host in listOf("s1", "s2")) {
            adapter.script(host, AutoModeProtocol.SOFTETHER_TCP, AutoModeController.AttemptOutcome.TIMED_OUT)
            adapter.script(host, AutoModeProtocol.SOFTETHER_UDP, AutoModeController.AttemptOutcome.TIMED_OUT)
        }
        val c = controller(newScope(), adapter, listOf(server("s1"), server("s2")), sink = sink)

        c.start()
        val terminal = c.awaitTerminal()

        assertTrue("expected Error, got $terminal", terminal is AutoModeState.Error)
        val abandoned = sink.records.filter { it.eventType == HistoryEventType.SERVER_ABANDONED }
        assertEquals(2, abandoned.size)
        assertEquals(setOf("s1", "s2"), abandoned.map { it.hostName }.toSet())
    }

    @Test
    fun `each recorded event carries the network type the run is on`() = runBlocking {
        val adapter = RecordingAdapter()
        val sink = RecordingSink()
        adapter.script("s1", AutoModeProtocol.SOFTETHER_TCP, AutoModeController.AttemptOutcome.TIMED_OUT)
        adapter.script("s1", AutoModeProtocol.SOFTETHER_UDP, AutoModeController.AttemptOutcome.TUNNEL_UP)
        val c = controller(newScope(), adapter, listOf(server("s1")), sink = sink, network = NetworkType.CELLULAR)

        c.start()
        c.awaitTerminal()

        assertTrue("every record must carry the network type", sink.records.isNotEmpty())
        assertTrue(
            "expected CELLULAR everywhere, got ${sink.records.map { it.networkType }}",
            sink.records.all { it.networkType == NetworkType.CELLULAR },
        )
    }

    // ==================================================================
    // §17 — a history failure must never change the connection result
    // ==================================================================

    @Test
    fun `a failing history sink still yields a connected result`() = runBlocking {
        val adapter = RecordingAdapter()
        val sink = FailingSink()
        val c = controller(newScope(), adapter, listOf(server("s1")), sink = sink)

        c.start()
        val terminal = c.awaitTerminal()

        assertTrue("a broken history must not break the connection: $terminal", terminal is AutoModeState.Connected)
        assertTrue("the sink must actually have been tried", sink.attempts > 0)
        // The failure is visible in the log, which is the only place it belongs.
        assertTrue(
            "the failure must be logged: ${adapter.logs.filter { it.contains("HISTORY") }}",
            adapter.logs.any { it.contains("[HISTORY]") },
        )
    }

    @Test
    fun `a failing history sink does not stop the run early`() = runBlocking {
        val adapter = RecordingAdapter()
        val sink = FailingSink()
        adapter.script("s1", AutoModeProtocol.SOFTETHER_TCP, AutoModeController.AttemptOutcome.TIMED_OUT)
        adapter.script("s1", AutoModeProtocol.SOFTETHER_UDP, AutoModeController.AttemptOutcome.TUNNEL_UP)
        val c = controller(newScope(), adapter, listOf(server("s1")), sink = sink)

        c.start()
        val terminal = c.awaitTerminal()

        assertTrue("expected Connected, got $terminal", terminal is AutoModeState.Connected)
        assertEquals("both protocols were still tried", 2, adapter.connects.size)
    }

    @Test
    fun `no sink at all keeps the run working`() = runBlocking {
        val adapter = RecordingAdapter()
        val c = controller(newScope(), adapter, listOf(server("s1")), sink = null)

        c.start()
        val terminal = c.awaitTerminal()

        assertTrue("expected Connected, got $terminal", terminal is AutoModeState.Connected)
    }

    // ==================================================================
    // §10 — the pre-F.29 behaviour is intact
    // ==================================================================

    @Test
    fun `history never outranks better quality`() {
        // s_bad has a perfect history; s_good has none. Quality must win.
        val history = mapOf(
            "s_bad" to ServerHistoryView(
                hostName = "s_bad", successCount = 99, failureCount = 0, consecutiveFailures = 0,
                lastSuccessAt = 1L, wifiSuccessCount = 99, cellularSuccessCount = 0,
                unknownNetworkSuccessCount = 0,
            ),
        )
        val scope = newScope()
        val adapter = RecordingAdapter()
        val c = controller(scope, adapter, emptyList(), history = history)
        val sorted = with(c) {
            listOf(server("s_bad", speed = 1_000L, ping = 900), server("s_good", speed = 99_000_000L, ping = 10))
                .sortedForAutoConnect(history, NetworkType.WIFI)
        }
        assertEquals("quality stays the sole ranking authority", listOf("s_good", "s_bad"), sorted.map { it.hostname })
    }

    @Test
    fun `history reorders only inside an equal-quality window`() {
        val history = mapOf(
            "s_bad" to ServerHistoryView(
                hostName = "s_bad", successCount = 99, failureCount = 0, consecutiveFailures = 0,
                lastSuccessAt = 1L, wifiSuccessCount = 99, cellularSuccessCount = 0,
                unknownNetworkSuccessCount = 0,
            ),
        )
        val scope = newScope()
        val adapter = RecordingAdapter()
        val c = controller(scope, adapter, emptyList(), history = history)
        // Three candidates of identical quality; history must lift s_bad to the
        // front of its own group without touching the other group.
        val groupA = listOf(server("s_bad", speed = 10_000_000L), server("s_mid"), server("s_new"))
        val groupB = listOf(server("s_fast", speed = 50_000_000L))
        val sorted = with(c) { (groupA + groupB).sortedForAutoConnect(history, NetworkType.WIFI) }
        assertEquals(
            "the fast server keeps its quality lead; history only permutes equals",
            listOf("s_fast", "s_bad", "s_mid", "s_new"),
            sorted.map { it.hostname },
        )
    }

    @Test
    fun `an empty history map leaves the order untouched`() {
        val scope = newScope()
        val adapter = RecordingAdapter()
        val c = controller(scope, adapter, emptyList())
        val servers = listOf(server("s1"), server("s2", speed = 5_000_000L), server("s3", speed = 20_000_000L))
        val plain = with(c) { servers.sortedForAutoConnect() }
        val withEmpty = with(c) { servers.sortedForAutoConnect(emptyMap(), NetworkType.WIFI) }
        assertEquals(plain.map { it.hostname }, withEmpty.map { it.hostname })
    }

    @Test
    fun `protocol priority is the only authority on attempt order`() = runBlocking {
        val adapter = RecordingAdapter()
        val sink = RecordingSink()
        adapter.script("s1", AutoModeProtocol.SOFTETHER_TCP, AutoModeController.AttemptOutcome.TIMED_OUT)
        adapter.script("s1", AutoModeProtocol.SOFTETHER_UDP, AutoModeController.AttemptOutcome.TUNNEL_UP)
        val c = controller(
            newScope(),
            adapter,
            listOf(server("s1")),
            sink = sink,
            priorities = listOf(AutoModeProtocol.SOFTETHER_TCP, AutoModeProtocol.SOFTETHER_UDP),
        )

        c.start()
        val terminal = c.awaitTerminal()

        assertTrue("expected Connected, got $terminal", terminal is AutoModeState.Connected)
        assertEquals(
            "the user's order, unchanged by history",
            listOf(AutoModeProtocol.SOFTETHER_TCP, AutoModeProtocol.SOFTETHER_UDP),
            adapter.connects.map { it.second },
        )
    }

    @Test
    fun `the run reads the history exactly once`() = runBlocking {
        val adapter = RecordingAdapter()
        var reads = 0
        val c = AutoModeController(
            scope = newScope(),
            adapter = adapter,
            protocolPriorityProvider = { listOf(AutoModeProtocol.SOFTETHER_TCP) },
            serverProvider = { listOf(server("s1")) },
            attemptTimeoutMsProvider = { 300L },
            isNetworkAvailable = { true },
            wedgeTracker = WedgeTracker(),
            historyProvider = { reads++; emptyMap() },
            historySink = RecordingSink(),
            networkTypeProvider = { NetworkType.WIFI },
        )
        c.start()
        c.awaitTerminal()
        assertEquals("one bounded DAO query per run (F.29 §10.3)", 1, reads)
    }

    @Test
    fun `a throwing history provider does not stop the run`() = runBlocking {
        val adapter = RecordingAdapter()
        val c = AutoModeController(
            scope = newScope(),
            adapter = adapter,
            protocolPriorityProvider = { listOf(AutoModeProtocol.SOFTETHER_TCP) },
            serverProvider = { listOf(server("s1")) },
            attemptTimeoutMsProvider = { 300L },
            isNetworkAvailable = { true },
            wedgeTracker = WedgeTracker(),
            historyProvider = { throw IllegalStateException("database unavailable") },
            historySink = RecordingSink(),
            networkTypeProvider = { NetworkType.WIFI },
        )
        c.start()
        val terminal = c.awaitTerminal()
        assertTrue("a history read failure must fall back to the existing order", terminal is AutoModeState.Connected)
        assertFalse(adapter.connects.isEmpty())
    }

    @Test
    fun `a throwing network type provider degrades to unknown`() = runBlocking {
        val adapter = RecordingAdapter()
        val sink = RecordingSink()
        val c = AutoModeController(
            scope = newScope(),
            adapter = adapter,
            protocolPriorityProvider = { listOf(AutoModeProtocol.SOFTETHER_TCP) },
            serverProvider = { listOf(server("s1")) },
            attemptTimeoutMsProvider = { 300L },
            isNetworkAvailable = { true },
            wedgeTracker = WedgeTracker(),
            historyProvider = { emptyMap() },
            historySink = sink,
            networkTypeProvider = { throw IllegalStateException("no connectivity service") },
        )
        c.start()
        val terminal = c.awaitTerminal()
        assertTrue("expected Connected, got $terminal", terminal is AutoModeState.Connected)
        assertTrue(
            "an unreadable network must be recorded as UNKNOWN, not guessed: ${sink.records.map { it.networkType }}",
            sink.records.all { it.networkType == NetworkType.UNKNOWN },
        )
    }
}
