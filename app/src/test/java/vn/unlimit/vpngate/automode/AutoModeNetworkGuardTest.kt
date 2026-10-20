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

/**
 * v1.0.42 (§1.6.35 / F2) — T-NEW-39.
 *
 * Pins the network guard: an offline AutoConnect must stop with
 * [AutoModeController.ERROR_NO_NETWORK] **before** any server is dialled and
 * before any wedge is recorded.
 *
 * Why this matters (the finding this closes): without the guard, an offline run
 * walked the whole list, marked every server `TIMED_OUT`, and handed each one a
 * [WedgeTracker] strike. Two such runs excluded every healthy server for the
 * rest of the process, so the next online press had nothing left to dial.
 *
 * The controller takes `isNetworkAvailable` as a lambda, so these tests need no
 * Android runtime.
 */
class AutoModeNetworkGuardTest {

    private class RecordingAdapter : AutoModeController.ConnectionAdapter {
        val logs = mutableListOf<String>()
        val connects = mutableListOf<Pair<String?, AutoModeProtocol>>()
        val failing = mutableSetOf<String>()

        /** Fired with the running connect count, so a test can flip state mid-run. */
        var onConnect: ((Int) -> Unit)? = null

        override suspend fun connect(candidate: AutoModeCandidate, protocol: AutoModeProtocol) {
            connects += candidate.hostname to protocol
            logs += "connect:${candidate.hostname}"
            onConnect?.invoke(connects.size)
        }

        override suspend fun disconnect() {
            logs += "disconnect"
        }

        override suspend fun awaitTunnel(protocol: AutoModeProtocol, timeoutMs: Long): Boolean {
            val host = logs.lastOrNull { it.startsWith("connect:") }
                ?.removePrefix("connect:") ?: return false
            return host !in failing
        }

        override fun log(message: String) {
            logs += message
        }
    }

    private fun server(host: String) = AutoModeCandidate(
        hostname = host,
        ip = "10.0.0.${host.filter { it.isDigit() }.ifEmpty { "0" }}",
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

    private fun controller(
        scope: CoroutineScope,
        adapter: RecordingAdapter,
        servers: List<AutoModeCandidate>,
        isNetworkAvailable: () -> Boolean,
    ) = AutoModeController(
        scope = scope,
        adapter = adapter,
        protocolPriorityProvider = { listOf(AutoModeProtocol.SOFTETHER_UDP) },
        serverProvider = { servers },
        attemptTimeoutMsProvider = { 300L },
        isNetworkAvailable = isNetworkAvailable,
        wedgeTracker = WedgeTracker(),
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

    // ── T-NEW-39a ────────────────────────────────────────────────────────────
    @Test
    fun offlineRunStopsBeforeAnyAttempt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = RecordingAdapter()
        val servers = listOf(server("s1"), server("s2"), server("s3"))
        val c = controller(scope, adapter, servers, isNetworkAvailable = { false })

        c.start()
        val terminal = c.awaitTerminal()

        assertTrue("expected an Error terminal, got $terminal", terminal is AutoModeState.Error)
        assertEquals(
            AutoModeController.ERROR_NO_NETWORK,
            (terminal as AutoModeState.Error).message,
        )
        assertEquals("no server may be dialled while offline", emptyList<Any>(), adapter.connects)
        assertTrue(
            "the run must say why it stopped: ${adapter.logs}",
            adapter.logs.any { it.contains("No network available; stopping before any attempt") },
        )
    }

    // ── T-NEW-39b ────────────────────────────────────────────────────────────
    @Test
    fun offlineRunRecordsNoWedge() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = RecordingAdapter()
        val servers = listOf(server("s1"), server("s2"), server("s3"))
        val c = controller(scope, adapter, servers, isNetworkAvailable = { false })

        c.start()
        c.awaitTerminal()

        for (s in servers) {
            assertEquals(
                "an offline run must not blame ${s.hostname}",
                0,
                c.wedgeCountFor(s),
            )
        }
    }

    // ── T-NEW-39c ────────────────────────────────────────────────────────────
    @Test
    fun onlineRunStillDialsAndConnects() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = RecordingAdapter()
        val c = controller(
            scope,
            adapter,
            listOf(server("s1"), server("s2")),
            isNetworkAvailable = { true },
        )

        c.start()
        val terminal = c.awaitTerminal()

        assertTrue("expected Connected, got $terminal", terminal is AutoModeState.Connected)
        assertEquals("s1", (terminal as AutoModeState.Connected).hostname)
        assertFalse("the guard must not block an online run", adapter.connects.isEmpty())
    }

    // ── T-NEW-39d ────────────────────────────────────────────────────────────
    @Test
    fun networkLossMidRunStopsBeforeTheNextServer() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = RecordingAdapter()
        val servers = listOf(server("s1"), server("s2"), server("s3"))
        // s1 fails, so the run reaches for s2 — but the network is gone by then.
        adapter.failing += "s1"
        var online = true
        adapter.onConnect = { count -> if (count == 1) online = false }
        val c = controller(scope, adapter, servers, isNetworkAvailable = { online })

        c.start()
        val terminal = c.awaitTerminal()

        assertTrue("expected an Error terminal, got $terminal", terminal is AutoModeState.Error)
        assertEquals(
            AutoModeController.ERROR_NO_NETWORK,
            (terminal as AutoModeState.Error).message,
        )
        assertEquals(
            "the run must stop after the attempt in flight, not walk the rest of the list",
            1,
            adapter.connects.size,
        )
        assertTrue(
            "the mid-run stop must be distinguishable in the log: ${adapter.logs}",
            adapter.logs.any { it.contains("Network lost; stopping before the next attempt") },
        )
    }
}
