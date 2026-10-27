package vn.unlimit.vpngate.automode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.history.NetworkType
import vn.unlimit.vpngate.history.ServerHistoryView
import vn.unlimit.vpngate.state.GlobalVpnTracker
import vn.unlimit.vpngate.state.VpnConnectionStatus

/**
 * v1.0.42 (§1.6.35 / F6 + F1) — T-NEW-40..43.
 *
 * The Next-Server continuation used to be an integer index
 * (`resumeFromIndex = currentServerIndex + 1`). It is now the server's stable
 * identity (`ip|hostname`), so a re-sorted list cannot shift the start
 * position. **This is a deliberate reversal of a v1.0.31 decision**, not a bug
 * fix: the old KDoc accepted the drift on the theory that identity could skip a
 * candidate that had become better. See `AutoModeController.resumeFromServerKey`.
 *
 * T-NEW-43 covers the other half of the same cluster: the per-attempt timeout is
 * now read through a provider, so a Settings change applies to the next attempt.
 */
class AutoModeNextServerIdentityTest {

    private class RecordingAdapter : AutoModeController.ConnectionAdapter {
        val logs = mutableListOf<String>()
        val connects = mutableListOf<Pair<String?, AutoModeProtocol>>()
        val failing = mutableSetOf<String>()
        /** Every budget the controller handed to a tunnel wait, in call order. */
        val timeouts = mutableListOf<Long>()
        /** Fired after each recorded budget, so a test can change the setting mid-run. */
        var onAwaitTunnel: ((Long) -> Unit)? = null

        override suspend fun connect(candidate: AutoModeCandidate, protocol: AutoModeProtocol) {
            connects += candidate.hostname to protocol
            logs += "connect:${candidate.hostname}"
        }

        override suspend fun disconnect() {
            logs += "disconnect"
        }

        override suspend fun awaitTunnel(protocol: AutoModeProtocol, timeoutMs: Long): Boolean {
            timeouts += timeoutMs
            onAwaitTunnel?.invoke(timeoutMs)
            val host = logs.lastOrNull { it.startsWith("connect:") }
                ?.removePrefix("connect:") ?: return false
            return host !in failing
        }

        override fun log(message: String) {
            logs += message
        }
    }

    private fun server(host: String, ip: String? = null) = AutoModeCandidate(
        hostname = host,
        ip = ip ?: "10.0.0.${host.filter { it.isDigit() }.ifEmpty { "0" }}",
        speed = 10_000_000,
        ping = 50,
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

    /**
     * `serverProvider` is deliberately the LAST parameter so the tests can pass
     * it as a trailing lambda; `timeoutProvider` has a default and is passed by
     * name where a test needs to change it.
     */
    private fun controller(
        scope: CoroutineScope,
        adapter: RecordingAdapter,
        timeoutProvider: () -> Long = { 300L },
        history: Map<String, ServerHistoryView> = emptyMap(),
        network: NetworkType = NetworkType.WIFI,
        serverProvider: suspend () -> List<AutoModeCandidate>,
    ) = AutoModeController(
        scope = scope,
        adapter = adapter,
        protocolPriorityProvider = { listOf(AutoModeProtocol.SOFTETHER_UDP) },
        serverProvider = serverProvider,
        attemptTimeoutMsProvider = timeoutProvider,
        wedgeTracker = WedgeTracker(),
        historyProvider = { history },
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
        return reached ?: error(
            "controller did not reach a terminal state within ${timeoutMs}ms; last=${state.value}",
        )
    }

    // ── T-NEW-40 ─────────────────────────────────────────────────────────────
    /**
     * A re-sort between the two runs must not shift the continuation.
     *
     * Run 1 resolves `[s1, s2, s3, s4]`, connects on s2 (s1 fails). Run 2 resolves
     * `[s1, s3, s2, s4]` — s2 and s3 swapped. Identity continuation starts after
     * s2, i.e. at s4. The old index continuation would have started at index 2,
     * which now names s2 again: the same server the user just asked to leave.
     */
    /**
     * BLOCK v1.0.75 (D-2): the continuation is the BEST UNTRIED candidate of
     * the run ordering, keyed by identity. The resort cannot re-offer a server
     * the session already tried (s1 failed, s2 connected — both tried), so the
     * first dial after the press is s3 — the best untried candidate in the new
     * order — and neither s1 nor s2 is ever dialled again.
     */
    @Test
    fun aResortDoesNotShiftTheContinuation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = RecordingAdapter()
        val order1 = listOf(server("s1"), server("s2"), server("s3"), server("s4"))
        val order2 = listOf(server("s1"), server("s3"), server("s2"), server("s4"))
        var runs = 0
        val c = controller(scope, adapter) {
            if (runs++ == 0) order1 else order2
        }

        adapter.failing += "s1"
        c.onButtonPressed()
        val first = c.awaitTerminal()
        assertTrue("expected the first run to connect, got $first", first is AutoModeState.Connected)
        assertEquals("s2", (first as AutoModeState.Connected).hostname)

        adapter.connects.clear()
        adapter.failing.clear()
        c.onButtonPressed() // Connected press → best untried candidate
        c.awaitTerminal()

        assertEquals(
            "the continuation dials the BEST UNTRIED candidate (s3), never a tried one",
            "s3" to AutoModeProtocol.SOFTETHER_UDP,
            adapter.connects.first(),
        )
        assertTrue(
            "tried servers must not be dialled again: ${adapter.connects}",
            adapter.connects.none { it.first in setOf("s1", "s2") },
        )
    }

    // ── T-NEW-41 ─────────────────────────────────────────────────────────────
    /**
     * The connected server has left the dataset: start from the top, and say so.
     *
     * The brief's wording ("connected to the last server → identity not found")
     * conflates two cases. The last server is still *found*, and that case keeps
     * its v1.0.31 meaning — no attempts, `ERROR_NO_SERVER` — which the unchanged
     * `pressingWhileConnectedToTheLastServerEndsWithoutRestarting` still pins.
     * The genuine "not found" case is a server that dropped out of the list.
     */
    /**
     * BLOCK v1.0.75 (D-2): the connected server has left the dataset. The
     * tried-set survives (s1 failed, s2 connected — both tried), so the
     * continuation dials the best UNTRIED candidate of the new list — s3 — and
     * the failed s1 is never re-dialled. The old position-resume restart at
     * the top would have re-dialled a server the session already proved bad.
     */
    @Test
    fun aVanishedServerFallsBackToTheBestUntried() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = RecordingAdapter()
        val order1 = listOf(server("s1"), server("s2"), server("s3"))
        val order2 = listOf(server("s1"), server("s3"))
        var runs = 0
        val c = controller(scope, adapter) {
            if (runs++ == 0) order1 else order2
        }

        adapter.failing += "s1"
        c.onButtonPressed()
        val first = c.awaitTerminal()
        assertEquals("s2", (first as AutoModeState.Connected).hostname)

        adapter.connects.clear()
        adapter.failing.clear()
        c.onButtonPressed() // s2 is gone from the new list
        val second = c.awaitTerminal()

        assertEquals(
            "the continuation must dial the best UNTRIED server (s3), not the already-failed s1",
            "s3" to AutoModeProtocol.SOFTETHER_UDP,
            adapter.connects.first(),
        )
        assertTrue("expected a terminal state, got $second", second is AutoModeState.Connected)
    }

    // ── T-NEW-42 ─────────────────────────────────────────────────────────────
    /**
     * The skip filter now uses the same identity as the continuation.
     *
     * Two servers share one IP (a NAT / load-balancer shape) and differ by
     * hostname. Connected to the second one, "Try next server" must fall back to
     * the first. An IP-only filter would drop **both** and end the run with
     * `ERROR_NO_SERVER`.
     */
    /**
     * The skip bookkeeping uses the same `ip|hostname` identity as the
     * continuation. Two servers share one IP (NAT / load-balancer shape) and
     * differ by hostname: the run tries s2 (fails) and connects s3, so BOTH are
     * tried in this session. BLOCK v1.0.75 (D-2): pressing Try Next now finds
     * no untried candidate — s2 was genuinely tried and must not be re-offered
     * just because it shares s3's IP. The distinct keys are what let s3 connect
     * while s2 was marked tried in the first place.
     */
    @Test
    fun skipFiltersByIdentityNotByIp() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = RecordingAdapter()
        val shared = "10.0.0.9"
        val servers = listOf(server("s2", ip = shared), server("s3", ip = shared))
        val c = controller(scope, adapter) { servers }

        adapter.failing += "s2"
        c.onButtonPressed()
        val first = c.awaitTerminal()
        assertEquals("s3", (first as AutoModeState.Connected).hostname)

        adapter.connects.clear()
        adapter.failing.clear()
        c.skipToNextServer()
        val second = c.awaitTerminal()

        assertTrue("expected the exhausted-session terminal, got $second", second is AutoModeState.Error)
        assertEquals(
            "both same-IP servers were tried in this session",
            AutoModeController.ERROR_NO_MORE_UNTRIED,
            (second as AutoModeState.Error).message,
        )
        assertTrue(
            "the failed same-IP sibling must not be re-dialled: ${adapter.connects}",
            adapter.connects.none { it.first == "s2" },
        )
    }

    // ── T-NEW-43 ─────────────────────────────────────────────────────────────
    /**
     * The per-attempt timeout is read per attempt, not frozen at construction.
     *
     * The adapter raises the setting after the first attempt; the second attempt
     * must observe the new value. Before v1.0.42 the controller had captured the
     * old value and every attempt in the process kept using it.
     */
    @Test
    fun theAttemptTimeoutIsReadFreshPerAttempt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = RecordingAdapter()
        var configured = 2_000L
        adapter.onAwaitTunnel = { _ -> configured = 4_000L }
        val c = controller(
            scope,
            adapter,
            serverProvider = { listOf(server("s1"), server("s2")) },
            timeoutProvider = { configured },
        )
        adapter.failing += "s1"

        c.onButtonPressed()
        c.awaitTerminal()

        assertEquals(
            "each attempt must read the provider; a frozen field would repeat the first value",
            listOf(2_000L, 4_000L),
            adapter.timeouts.take(2),
        )
    }

    // ── PART C / device finding V7 ──────────────────────────────────────────────
    /**
     * Next-Server must work after a MANUAL (externally-owned) connect.
     *
     * The controller never ran in this scenario, so its own `_state` is
     * `Disconnected`; only the app-wide [GlobalVpnTracker] knows a tunnel is up.
     * Before the PART C fix `skipToNextServer()` bailed at the
     * "disconnected or inactive" guard and the button did nothing. After the fix
     * it reads the tracker, tears the manual tunnel down and continues from the
     * next server.
     */
    @Test
    fun nextServerWorksAfterManualConnect() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = RecordingAdapter()
        val servers = listOf(server("s1"), server("s2"), server("s3"))
        val c = controller(scope, adapter) { servers }

        // Simulate a manual connect: controller state stays Disconnected, but the
        // global tracker reports a live tunnel to s1 (host + ip known).
        GlobalVpnTracker.updateState(
            VpnConnectionStatus.CONNECTED,
            protocol = "SoftEther",
            serverHost = "s1",
            serverIp = "10.0.0.1",
        )
        try {
            assertFalse(
                "precondition: controller must believe it is disconnected for a manual tunnel",
                c.state.value is AutoModeState.Connected,
            )

            adapter.connects.clear()
            c.skipToNextServer()

            // Wait for the restart to actually dial (skip launches a coroutine).
            withTimeoutOrNull(5_000) {
                while (adapter.connects.isEmpty()) delay(5)
            }
            val final = c.awaitTerminal()

            assertTrue("expected Connected, got $final", final is AutoModeState.Connected)
            // s1 must be excluded; the run resumes at the next server.
            assertEquals(
                "the manually-connected server must be skipped",
                "s2" to AutoModeProtocol.SOFTETHER_UDP,
                adapter.connects.first(),
            )
            assertEquals(
                "s1 must never be dialed again after the manual skip",
                0,
                adapter.connects.count { it.first == "s1" },
            )
            assertTrue(
                "must log the external skip: ${adapter.logs}",
                adapter.logs.any { it.contains("External/manual tunnel skipped") },
            )
        } finally {
            GlobalVpnTracker.updateState(VpnConnectionStatus.DISCONNECTED, protocol = "SoftEther")
        }
    }

    /**
     * When the stack did not report the manual server's identity, the run starts
     * from the top of the list (any server) rather than erroring out — still
     * "move on from the manual tunnel", just without the precise exclusion
     * (SoftEther/OpenVPN publish only `assignedIp` to the tracker).
     */
    @Test
    fun nextServerAfterManualConnectWithUnknownIdentityStartsFromTop() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = RecordingAdapter()
        val servers = listOf(server("s1"), server("s2"), server("s3"))
        val c = controller(scope, adapter) { servers }

        // SoftEther/OpenVPN publish only assignedIp to the tracker, so host/ip are
        // unknown here — mirroring a real manual SoftEther connect.
        GlobalVpnTracker.updateState(VpnConnectionStatus.CONNECTED, protocol = "SoftEther")
        try {
            adapter.connects.clear()
            c.skipToNextServer()

            withTimeoutOrNull(5_000) {
                while (adapter.connects.isEmpty()) delay(5)
            }
            val final = c.awaitTerminal()
            assertTrue("expected Connected, got $final", final is AutoModeState.Connected)
            // No identity → no exclusion → starts from the top (s1).
            assertEquals(
                "without a known identity the run starts from the top",
                "s1" to AutoModeProtocol.SOFTETHER_UDP,
                adapter.connects.first(),
            )
        } finally {
            GlobalVpnTracker.updateState(VpnConnectionStatus.DISCONNECTED, protocol = "SoftEther")
        }
    }

    // ── PART C (v1.0.61) — the continuation contract ────────────────────────────
    //
    // The operator's requirement, stated as tests: whenever the app is connected,
    // Next Server continues from the FOLLOWING server by stable key (ip|hostname)
    // in the list the user sees - never from the beginning, never the connected
    // server again, and never re-ordered by something the run did not use.

    private fun view(
        host: String,
        success: Int = 0,
        failures: Int = 0,
        consecutive: Int = 0,
        wifi: Int = 0,
    ) = ServerHistoryView(
        hostName = host,
        successCount = success,
        failureCount = failures,
        consecutiveFailures = consecutive,
        lastSuccessAt = if (success > 0) 1_000L else 0L,
        wifiSuccessCount = wifi,
        cellularSuccessCount = 0,
        unknownNetworkSuccessCount = 0,
    )

    /** Simulate a manual tunnel to [host] and press Next Server once. */
    private suspend fun manualConnectAndSkip(
        c: AutoModeController,
        adapter: RecordingAdapter,
        host: String,
        ip: String,
    ) {
        GlobalVpnTracker.updateState(
            VpnConnectionStatus.CONNECTED,
            protocol = "SoftEther",
            serverHost = host,
            serverIp = ip,
        )
        adapter.connects.clear()
        c.skipToNextServer()
        withTimeoutOrNull(5_000) {
            while (adapter.connects.isEmpty()) delay(5)
        }
    }

    /**
     * BLOCK v1.0.75 (D-2): on a manual tunnel the session starts with the
     * connected server ({s5}); Next dials the BEST UNTRIED candidate of the
     * ordering — s1 — and the manual server is never dialled again.
     */
    @Test
    fun nextServerOnAManualTunnelDialsTheBestUntried() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = RecordingAdapter()
        val servers = (1..8).map { server("s$it") }
        val c = controller(scope, adapter) { servers }
        try {
            manualConnectAndSkip(c, adapter, "s5", "10.0.0.5")
            val final = c.awaitTerminal()
            assertTrue("expected Connected, got $final", final is AutoModeState.Connected)
            assertEquals(
                "the best untried candidate is the top of the ordering (s1)",
                "s1" to AutoModeProtocol.SOFTETHER_UDP,
                adapter.connects.first(),
            )
            assertEquals("s5 must not be dialled again", 0, adapter.connects.count { it.first == "s5" })
        } finally {
            GlobalVpnTracker.updateState(VpnConnectionStatus.DISCONNECTED, protocol = "SoftEther")
        }
    }

    @Test
    fun whenTheUntriedHeadFailsTheWalkContinuesToTheNextUntried() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = RecordingAdapter()
        val servers = (1..8).map { server("s$it") }
        adapter.failing += setOf("s1", "s2", "s3", "s4", "s6")
        val c = controller(scope, adapter) { servers }
        try {
            manualConnectAndSkip(c, adapter, "s5", "10.0.0.5")
            val final = c.awaitTerminal()
            assertTrue("expected Connected, got $final", final is AutoModeState.Connected)
            // D-2: the walk starts at the best untried (s1) and the normal
            // server walk carries on over untried candidates — s5 (the manual
            // server, tried) is skipped, failures rotate on, s7 connects.
            assertEquals(
                "the walk dials untried candidates in ordering order",
                listOf("s1", "s2", "s3", "s4", "s6", "s7"),
                adapter.connects.map { it.first },
            )
        } finally {
            GlobalVpnTracker.updateState(VpnConnectionStatus.DISCONNECTED, protocol = "SoftEther")
        }
    }

    @Test
    fun aResortBetweenPressesDoesNotShiftTheContinuation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = RecordingAdapter()
        var servers = listOf(server("s1"), server("s2"), server("s3"), server("s4"), server("s5"))
        val c = controller(scope, adapter) { servers }
        try {
            // Manual tunnel to s2, first press -> best untried (s1) connects.
            manualConnectAndSkip(c, adapter, "s2", "10.0.0.2")
            c.awaitTerminal()
            assertEquals("s1" to AutoModeProtocol.SOFTETHER_UDP, adapter.connects.first())

            // Re-sort; the tried-set (s2 seeded, s1 visited) survives it.
            servers = listOf(server("s5"), server("s4"), server("s3"), server("s1"), server("s2"))
            adapter.connects.clear()
            c.skipToNextServer()
            withTimeoutOrNull(5_000) { while (adapter.connects.isEmpty()) delay(5) }
            c.awaitTerminal()

            assertEquals(
                "the continuation dials the best UNTRIED candidate in the new order (s5), " +
                    "never a tried one (s1, s2)",
                "s5",
                adapter.connects.first().first,
            )
            assertEquals(
                "tried servers must not be dialled again: ${adapter.connects}",
                0,
                adapter.connects.count { it.first in setOf("s1", "s2") },
            )
        } finally {
            GlobalVpnTracker.updateState(VpnConnectionStatus.DISCONNECTED, protocol = "SoftEther")
        }
    }

    @Test
    fun aConnectedServerMissingFromTheListStartsFromTheTop() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = RecordingAdapter()
        val servers = listOf(server("s1"), server("s2"), server("s3"))
        val c = controller(scope, adapter) { servers }
        try {
            // Identity known, but that server is no longer in the dataset.
            manualConnectAndSkip(c, adapter, "gone.example", "10.9.9.9")
            val final = c.awaitTerminal()
            assertTrue("expected Connected, got $final", final is AutoModeState.Connected)
            assertEquals(
                "nothing matches, so nothing is filtered and the run starts at the top",
                "s1",
                adapter.connects.first().first,
            )
        } finally {
            GlobalVpnTracker.updateState(VpnConnectionStatus.DISCONNECTED, protocol = "SoftEther")
        }
    }

    @Test
    fun theContinuationUsesTheSameHistoryAwareOrderingAsTheRun() = runBlocking {
        val servers = listOf(server("s1"), server("s2"), server("s3"), server("s4"), server("s5"))
        // A history that WOULD reorder: s5 proven on this network, s4 on a failure streak.
        val history = mapOf(
            "s5" to view("s5", success = 1, wifi = 1),
            "s4" to view("s4", failures = 3, consecutive = 3),
        )

        // (a) A normal run does use it - otherwise the assertion below proves nothing.
        val runScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runAdapter = RecordingAdapter()
        val runController = controller(runScope, runAdapter, history = history) { servers }
        runController.start()
        runController.awaitTerminal()
        assertEquals(
            "precondition: the tie-break must promote s5 in a normal run",
            "s5",
            runAdapter.connects.first().first,
        )

        // (b) BLOCK v1.0.75 (D-2): the manual continuation sorts WITH history —
        // the same ordering the run uses — so the best untried candidate is the
        // history-promoted s5, and the demoted s4 stays behind it.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = RecordingAdapter()
        val c = controller(scope, adapter, history = history) { servers }
        try {
            manualConnectAndSkip(c, adapter, "s3", "10.0.0.3")
            val final = c.awaitTerminal()
            assertTrue("expected Connected, got $final", final is AutoModeState.Connected)
            assertEquals(
                "the best untried candidate under the run's own ordering is the promoted s5",
                "s5",
                adapter.connects.first().first,
            )
        } finally {
            GlobalVpnTracker.updateState(VpnConnectionStatus.DISCONNECTED, protocol = "SoftEther")
        }
    }
}
