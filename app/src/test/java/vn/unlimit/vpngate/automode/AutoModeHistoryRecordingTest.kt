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
    fun `a never-succeeded server with a failure streak is demoted behind healthy ones`() {
        val scope = newScope()
        val adapter = RecordingAdapter()
        // BLOCK v1.0.75 (C2): demotion reads the CURRENT network's counter, so
        // the streak that demotes must be the Wi-Fi one.
        val history = mapOf(
            "s_dead" to ServerHistoryView(
                hostName = "s_dead", successCount = 0, failureCount = 5, consecutiveFailures = 5,
                lastSuccessAt = 0L, wifiSuccessCount = 0, cellularSuccessCount = 0,
                unknownNetworkSuccessCount = 0, wifiConsecutiveFailures = 5,
            ),
        )
        val c = controller(scope, adapter, emptyList(), history = history)
        val sorted = with(c) {
            // v1.0.67 feature 4: s_dead is the FASTEST, but a never-succeeded
            // failure streak must fall behind the healthy (no-history) servers so
            // the run does not spend its first attempts on a proven-dead endpoint.
            listOf(
                server("s_dead", speed = 99_000_000L, ping = 10),
                server("s_ok1", speed = 10_000_000L, ping = 100),
                server("s_ok2", speed = 1_000L, ping = 900),
            ).sortedForAutoConnect(history, NetworkType.WIFI)
        }
        assertEquals(listOf("s_ok1", "s_ok2", "s_dead"), sorted.map { it.hostname })
    }

    @Test
    fun `a failure streak on ANOTHER network does not demote on this one`() {
        val scope = newScope()
        val adapter = RecordingAdapter()
        // C2: the SAME server, the same global streak, but the failures happened
        // on cellular — on Wi-Fi it keeps its (best) quality position.
        val history = mapOf(
            "s_dead" to ServerHistoryView(
                hostName = "s_dead", successCount = 0, failureCount = 5, consecutiveFailures = 5,
                lastSuccessAt = 0L, wifiSuccessCount = 0, cellularSuccessCount = 0,
                unknownNetworkSuccessCount = 0, cellularConsecutiveFailures = 5,
            ),
        )
        val c = controller(scope, adapter, emptyList(), history = history)
        val sorted = with(c) {
            listOf(
                server("s_dead", speed = 99_000_000L, ping = 10),
                server("s_ok1", speed = 10_000_000L, ping = 100),
            ).sortedForAutoConnect(history, NetworkType.WIFI)
        }
        assertEquals(listOf("s_dead", "s_ok1"), sorted.map { it.hostname })
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

    // ==================================================================
    // BLOCK v1.0.69 PART 1 — producer wiring that the pure tests cannot see
    // ==================================================================

    /**
     * PART 1A producer: leaving a just-connected server via the user's
     * Disconnect action must emit exactly one NEUTRAL QUALITY_REJECTED record.
     * The window/dedup decision itself is pinned by [QualityRejectWindowTest];
     * this test proves the controller actually calls it, with the right event
     * type and outcome, and never touches a failure counter.
     */
    @Test
    fun `disconnecting a just-connected server records one neutral quality rejection`() = runBlocking {
        val adapter = RecordingAdapter()
        val sink = RecordingSink()
        val c = controller(newScope(), adapter, listOf(server("s1")), sink = sink)

        c.start()
        val terminal = c.awaitTerminal()
        assertTrue("expected Connected, got $terminal", terminal is AutoModeState.Connected)

        c.disconnectNow()

        // The producer dispatches its write onto the controller scope.
        withTimeoutOrNull(2_000) {
            while (sink.records.none { it.eventType == HistoryEventType.QUALITY_REJECTED }) delay(10)
        }
        val rejects = sink.records.filter { it.eventType == HistoryEventType.QUALITY_REJECTED }
        assertEquals(
            "exactly one quality rejection per disconnect: ${sink.records.map { it.eventType }}",
            1,
            rejects.size,
        )
        assertEquals(
            "a quality rejection is neutral, never a server failure",
            HistoryOutcome.NEUTRAL,
            rejects.single().outcome,
        )
    }

    /**
     * OD-9: the durable CONNECTED write is retried up to
     * `HISTORY_MAX_ATTEMPTS` with bounded backoff, so two transient database
     * failures must still end in a third, successful attempt — and must not
     * change the connection result.
     */
    @Test
    fun `a transient CONNECTED history write is retried up to three attempts`() = runBlocking {
        val adapter = RecordingAdapter()
        var connectedAttempts = 0
        val sink = object : vn.unlimit.vpngate.history.ServerHistorySink {
            override suspend fun record(record: HistoryRecord) {
                if (record.eventType != HistoryEventType.CONNECTED) return
                connectedAttempts++
                if (connectedAttempts <= 2) throw IllegalStateException("transient lock")
            }
        }
        val c = controller(newScope(), adapter, listOf(server("s1")), sink = sink)

        c.start()
        val terminal = c.awaitTerminal()
        withTimeoutOrNull(3_000) { while (connectedAttempts < 3) delay(10) }

        assertTrue("the connection must still succeed, got $terminal", terminal is AutoModeState.Connected)
        assertEquals(
            "two transient failures then success must be exactly three attempts",
            3,
            connectedAttempts,
        )
    }

    // ═════════════════════════════════════════════════════════════════════
    // BLOCK v1.0.75 PART C — history actually speeds the connection up
    // ═════════════════════════════════════════════════════════════════════

    private fun now() = System.currentTimeMillis()

    private fun knownGoodView(
        host: String,
        successAt: Long = now() - 60_000,
        failuresAt: Long? = null,
        rejections: Int = 0,
    ) = ServerHistoryView(
        hostName = host,
        successCount = 1,
        failureCount = if (failuresAt != null) 1 else 0,
        consecutiveFailures = 0,
        lastSuccessAt = successAt,
        wifiSuccessCount = 1,
        cellularSuccessCount = 0,
        unknownNetworkSuccessCount = 0,
        qualityRejectionCount = rejections,
        wifiLastFailureAt = failuresAt,
    )

    @Test
    fun `known-good servers go first and the rest keep the quality order`() {
        val c = controller(newScope(), RecordingAdapter(), emptyList())
        val history = mapOf("s_slow_good" to knownGoodView("s_slow_good"))
        val servers = listOf(
            server("s_fast", speed = 90_000_000L),
            server("s_mid", speed = 50_000_000L),
            server("s_slow_good", speed = 1_000_000L),
        )
        val sorted = with(c) { servers.sortedForAutoConnect(history, NetworkType.WIFI) }
        assertEquals(
            "D-4: the known-good server leads; the rest keep the quality order",
            listOf("s_slow_good", "s_fast", "s_mid"),
            sorted.map { it.hostname },
        )
    }

    @Test
    fun `at most three known-good servers are promoted`() {
        val c = controller(newScope(), RecordingAdapter(), emptyList())
        val history = (1..4).associate { "good$it" to knownGoodView("good$it") }
        val servers = (1..4).map { server("good$it", speed = (10 - it) * 1_000_000L) } +
            server("fresh", speed = 99_000_000L)
        val sorted = with(c) { servers.sortedForAutoConnect(history, NetworkType.WIFI) }
        assertEquals(
            "D-4: KNOWN_GOOD_MAX = 3 — the fourth known-good stays in quality order",
            listOf("good1", "good2", "good3", "fresh", "good4"),
            sorted.map { it.hostname },
        )
    }

    @Test
    fun `an unknown network never credits history`() {
        val c = controller(newScope(), RecordingAdapter(), emptyList())
        val history = mapOf("s_slow_good" to knownGoodView("s_slow_good"))
        val servers = listOf(
            server("s_fast", speed = 90_000_000L),
            server("s_slow_good", speed = 1_000_000L),
        )
        val sorted = with(c) { servers.sortedForAutoConnect(history, NetworkType.UNKNOWN) }
        assertEquals(
            "C1: an unidentified network never promotes anything",
            listOf("s_fast", "s_slow_good"),
            sorted.map { it.hostname },
        )
    }

    @Test
    fun `a success outside the window is not known-good`() {
        val c = controller(newScope(), RecordingAdapter(), emptyList())
        val stale = now() - (KNOWN_GOOD_WINDOW_DAYS + 1) * 24L * 60L * 60L * 1000L
        val history = mapOf("s_slow_good" to knownGoodView("s_slow_good", successAt = stale))
        val servers = listOf(
            server("s_fast", speed = 90_000_000L),
            server("s_slow_good", speed = 1_000_000L),
        )
        val sorted = with(c) { servers.sortedForAutoConnect(history, NetworkType.WIFI) }
        assertEquals(listOf("s_fast", "s_slow_good"), sorted.map { it.hostname })
    }

    @Test
    fun `a later failure on the same network cancels known-good`() {
        val c = controller(newScope(), RecordingAdapter(), emptyList())
        val successAt = now() - 120_000
        val history = mapOf(
            "s_slow_good" to knownGoodView("s_slow_good", successAt = successAt, failuresAt = successAt + 60_000),
        )
        val servers = listOf(
            server("s_fast", speed = 90_000_000L),
            server("s_slow_good", speed = 1_000_000L),
        )
        val sorted = with(c) { servers.sortedForAutoConnect(history, NetworkType.WIFI) }
        assertEquals(
            "D-4: a later server-attributable failure on this network cancels the promotion",
            listOf("s_fast", "s_slow_good"),
            sorted.map { it.hostname },
        )
    }

    @Test
    fun `two quality rejections with no success on this network demote`() {
        val c = controller(newScope(), RecordingAdapter(), emptyList())
        val history = mapOf(
            "s_fast" to ServerHistoryView(
                hostName = "s_fast", successCount = 0, failureCount = 0, consecutiveFailures = 0,
                lastSuccessAt = 0L, wifiSuccessCount = 0, cellularSuccessCount = 0,
                unknownNetworkSuccessCount = 0, qualityRejectionCount = QUALITY_REJECT_DEMOTE_AFTER,
            ),
        )
        val servers = listOf(
            server("s_fast", speed = 90_000_000L),
            server("s_ok", speed = 1_000_000L),
        )
        val sorted = with(c) { servers.sortedForAutoConnect(history, NetworkType.WIFI) }
        assertEquals(listOf("s_ok", "s_fast"), sorted.map { it.hostname })
    }

    @Test
    fun `missed refreshes with no success on this network demote`() {
        val c = controller(newScope(), RecordingAdapter(), emptyList())
        val history = mapOf(
            "s_fast" to ServerHistoryView(
                hostName = "s_fast", successCount = 0, failureCount = 0, consecutiveFailures = 0,
                lastSuccessAt = 0L, wifiSuccessCount = 0, cellularSuccessCount = 0,
                unknownNetworkSuccessCount = 0, missedRefreshCount = MISSED_REFRESH_DEMOTE_AFTER,
            ),
        )
        val servers = listOf(
            server("s_fast", speed = 90_000_000L),
            server("s_ok", speed = 1_000_000L),
        )
        val sorted = with(c) { servers.sortedForAutoConnect(history, NetworkType.WIFI) }
        assertEquals(
            "D-8: absent from enough accepted fresh refreshes and never proven here -> demoted, not deleted",
            listOf("s_ok", "s_fast"),
            sorted.map { it.hostname },
        )
        assertEquals("demotion never deletes", 2, sorted.size)
    }

    @Test
    fun `remembered protocol goes first for a server with history`() {
        val c = controller(newScope(), RecordingAdapter(), emptyList())
        val supported = listOf(AutoModeProtocol.SOFTETHER_TCP, AutoModeProtocol.SOFTETHER_UDP)
        val view = ServerHistoryView(
            hostName = "s1", successCount = 1, failureCount = 0, consecutiveFailures = 0,
            lastSuccessAt = now(), wifiSuccessCount = 1, cellularSuccessCount = 0,
            unknownNetworkSuccessCount = 0, lastSuccessProtocol = AutoModeProtocol.SOFTETHER_UDP.id,
        )
        assertEquals(
            "D-6: the remembered protocol leads, the rest keep their order",
            listOf(AutoModeProtocol.SOFTETHER_UDP, AutoModeProtocol.SOFTETHER_TCP),
            with(c) { c.orderWithRememberedProtocol(supported, view) },
        )
        assertEquals(
            "no history -> the user's order untouched",
            supported,
            with(c) { c.orderWithRememberedProtocol(supported, null) },
        )
        val disabled = ServerHistoryView(
            hostName = "s1", successCount = 1, failureCount = 0, consecutiveFailures = 0,
            lastSuccessAt = now(), wifiSuccessCount = 1, cellularSuccessCount = 0,
            unknownNetworkSuccessCount = 0, lastSuccessProtocol = AutoModeProtocol.MS_SSTP.id,
        )
        assertEquals(
            "D-6: a protocol the user disabled is never introduced",
            supported,
            with(c) { c.orderWithRememberedProtocol(supported, disabled) },
        )
    }

    @Test
    fun `cooldown defers a server that failed recently on this network`() {
        val c = controller(newScope(), RecordingAdapter(), emptyList())
        val recent = ServerHistoryView(
            hostName = "s1", successCount = 0, failureCount = 1, consecutiveFailures = 1,
            lastSuccessAt = 0L, wifiSuccessCount = 0, cellularSuccessCount = 0,
            unknownNetworkSuccessCount = 0, wifiLastFailureAt = now() - 5 * 60_000L,
        )
        val stale = ServerHistoryView(
            hostName = "s2", successCount = 0, failureCount = 1, consecutiveFailures = 1,
            lastSuccessAt = 0L, wifiSuccessCount = 0, cellularSuccessCount = 0,
            unknownNetworkSuccessCount = 0, wifiLastFailureAt = now() - FAILURE_COOLDOWN_MS - 60_000L,
        )
        val history = mapOf("s1" to recent, "s2" to stale)
        val s1 = server("s1")
        val s2 = server("s2")
        assertTrue("D-7: a recent failure on this network cools down", with(c) { c.inFailureCooldown(s1, history, NetworkType.WIFI, now()) })
        assertFalse("D-7: the cooldown expires", with(c) { c.inFailureCooldown(s2, history, NetworkType.WIFI, now()) })
        assertFalse(
            "D-7: a Wi-Fi failure does not cool down cellular",
            with(c) { c.inFailureCooldown(s1, history, NetworkType.CELLULAR, now()) },
        )
    }

    @Test
    fun `exploration picks exactly one previously attempted server by oldest attempt`() {
        val c = controller(newScope(), RecordingAdapter(), emptyList())
        val history = mapOf(
            "old" to ServerHistoryView(
                hostName = "old", successCount = 0, failureCount = 2, consecutiveFailures = 2,
                lastSuccessAt = 0L, wifiSuccessCount = 0, cellularSuccessCount = 0,
                unknownNetworkSuccessCount = 0, lastAttemptAt = now() - 900_000,
            ),
            "recent" to ServerHistoryView(
                hostName = "recent", successCount = 0, failureCount = 1, consecutiveFailures = 1,
                lastSuccessAt = 0L, wifiSuccessCount = 0, cellularSuccessCount = 0,
                unknownNetworkSuccessCount = 0, lastAttemptAt = now() - 60_000,
            ),
        )
        val servers = listOf(server("first"), server("second"), server("old"), server("recent"))
        val reordered = with(c) {
            servers.withSingleExploration(
                startAt = 0,
                key = { it.hostname ?: "" },
                eligible = { s ->
                    val h = history[s.hostname]
                    h != null && h.successesOn(NetworkType.WIFI) == 0 && h.lastAttemptAt != null
                },
                ageKey = { s -> history[s.hostname]?.lastAttemptAt },
            )
        }
        assertEquals(
            "C7: the oldest attempted server goes second; the first pick never moves",
            listOf("first", "old", "second", "recent"),
            reordered.map { it.hostname },
        )
    }

    @Test
    fun `a never-attempted server is never the exploration candidate`() {
        val c = controller(newScope(), RecordingAdapter(), emptyList())
        val history = mapOf(
            "untried" to ServerHistoryView(
                hostName = "untried", successCount = 0, failureCount = 0, consecutiveFailures = 0,
                lastSuccessAt = 0L, wifiSuccessCount = 0, cellularSuccessCount = 0,
                unknownNetworkSuccessCount = 0, lastAttemptAt = null,
            ),
        )
        val servers = listOf(server("first"), server("second"), server("untried"))
        val reordered = with(c) {
            servers.withSingleExploration(
                startAt = 0,
                key = { it.hostname ?: "" },
                eligible = { s ->
                    val h = history[s.hostname]
                    h != null && h.successesOn(NetworkType.WIFI) == 0 && h.lastAttemptAt != null
                },
                ageKey = { s -> history[s.hostname]?.lastAttemptAt },
            )
        }
        assertEquals(
            "C7: no attempts -> no exploration pick, list unchanged",
            listOf("first", "second", "untried"),
            reordered.map { it.hostname },
        )
    }

    @Test
    fun `exploration can be placed first every K-th run`() {
        val c = controller(newScope(), RecordingAdapter(), emptyList())
        val history = mapOf(
            "old" to ServerHistoryView(
                hostName = "old", successCount = 0, failureCount = 1, consecutiveFailures = 1,
                lastSuccessAt = 0L, wifiSuccessCount = 0, cellularSuccessCount = 0,
                unknownNetworkSuccessCount = 0, lastAttemptAt = now() - 900_000,
            ),
        )
        val servers = listOf(server("first"), server("second"), server("old"))
        val every = 2
        val onRun0 = with(c) {
            servers.withSingleExploration(
                startAt = 0, key = { it.hostname ?: "" },
                eligible = { s -> history[s.hostname]?.let { it.successesOn(NetworkType.WIFI) == 0 && it.lastAttemptAt != null } == true },
                ageKey = { s -> history[s.hostname]?.lastAttemptAt },
                firstEveryK = every, runIndex = 0,
            )
        }
        val onRun1 = with(c) {
            servers.withSingleExploration(
                startAt = 0, key = { it.hostname ?: "" },
                eligible = { s -> history[s.hostname]?.let { it.successesOn(NetworkType.WIFI) == 0 && it.lastAttemptAt != null } == true },
                ageKey = { s -> history[s.hostname]?.lastAttemptAt },
                firstEveryK = every, runIndex = 1,
            )
        }
        assertEquals("K-th run: the exploration candidate leads", "old", onRun0.first().hostname)
        assertEquals("other runs: it stays second", listOf("first", "old", "second"), onRun1.map { it.hostname })
    }
}
