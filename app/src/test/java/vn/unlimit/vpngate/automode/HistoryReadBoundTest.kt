package vn.unlimit.vpngate.automode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.history.HistoryRecord
import vn.unlimit.vpngate.history.NetworkType
import vn.unlimit.vpngate.history.ServerHistoryView

/**
 * v1.0.61 (§1.6.59) PART B — the once-per-run history read is bounded.
 *
 * The read is advisory: it may improve the order, it must never delay a run. These
 * four tests are the four ways that promise can be broken — a database that hangs,
 * a database that throws, a fast database whose answer must still be used, and a
 * user who stops the run while the read is in flight.
 *
 * The bound is [AutoModeController.HISTORY_READ_TIMEOUT_MS] (1500 ms) and it is
 * applied in `loadHistory()`, so it covers every provider — production, these fakes,
 * and any future one.
 */
class HistoryReadBoundTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private class SilentAdapter : AutoModeController.ConnectionAdapter {
        val connects = mutableListOf<String?>()
        val logs = mutableListOf<String>()

        override suspend fun connect(candidate: AutoModeCandidate, protocol: AutoModeProtocol) {
            connects += candidate.hostname
            logs += "connect:${candidate.hostname}"
        }

        override suspend fun disconnect() {
            logs += "disconnect"
        }

        override suspend fun awaitTunnel(protocol: AutoModeProtocol, timeoutMs: Long): Boolean = true

        override suspend fun awaitArmedOutcome(
            protocol: AutoModeProtocol,
            timeoutMs: Long,
        ): AutoModeController.AttemptOutcome = AutoModeController.AttemptOutcome.TUNNEL_UP

        override fun log(message: String) {
            logs += message
        }
    }

    private fun server(host: String) = AutoModeCandidate(
        hostname = host,
        ip = "10.0.0.1",
        speed = 10_000_000L,
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
        adapter: SilentAdapter,
        servers: List<AutoModeCandidate>,
        history: suspend () -> Map<String, ServerHistoryView>,
    ) = AutoModeController(
        scope = scope,
        adapter = adapter,
        protocolPriorityProvider = { listOf(AutoModeProtocol.SOFTETHER_TCP) },
        serverProvider = { servers },
        attemptTimeoutMsProvider = { 300L },
        isNetworkAvailable = { true },
        wedgeTracker = WedgeTracker(),
        historyProvider = history,
        historySink = null,
        networkTypeProvider = { NetworkType.WIFI },
    )

    private suspend fun AutoModeController.awaitTerminal(timeoutMs: Long = 12_000): AutoModeState {
        val reached = withTimeoutOrNull(timeoutMs) {
            var result: AutoModeState? = null
            while (result == null) {
                val s = state.value
                if ((s is AutoModeState.Connected || s is AutoModeState.Error) && !isRunning) {
                    result = s
                } else {
                    delay(10)
                }
            }
            result
        }
        return reached ?: error("no terminal state within ${timeoutMs}ms; last=${state.value}")
    }

    // ------------------------------------------------------------------ 1

    @Test
    fun `a hanging history read is abandoned at the bound and the run proceeds`() = runBlocking {
        val adapter = SilentAdapter()
        val c = controller(adapter, listOf(server("a.example"))) {
            delay(10_000)
            emptyMap()
        }

        val started = System.currentTimeMillis()
        c.start()
        val terminal = c.awaitTerminal()
        val elapsed = System.currentTimeMillis() - started

        assertTrue("the run must still connect, got $terminal", terminal is AutoModeState.Connected)
        // The bound was actually applied: we waited for it, and then stopped waiting.
        assertTrue("the bound must be honoured (waited ${elapsed}ms)", elapsed >= 1_400)
        assertTrue("the run must not wait for the 10s read (waited ${elapsed}ms)", elapsed < 6_000)
        assertTrue(
            "the timeout must be logged once: ${adapter.logs}",
            adapter.logs.any { it.contains("read timed out") },
        )
        assertEquals(listOf("a.example"), adapter.connects)
    }

    // ------------------------------------------------------------------ 2

    @Test
    fun `a throwing history read leaves the run on the quality order`() = runBlocking {
        val adapter = SilentAdapter()
        val c = controller(adapter, listOf(server("a.example"), server("b.example"))) {
            throw IllegalStateException("simulated database failure")
        }

        c.start()
        val terminal = c.awaitTerminal()

        assertTrue("the run must connect despite the read failing", terminal is AutoModeState.Connected)
        assertTrue(
            "the failure must be logged: ${adapter.logs}",
            adapter.logs.any { it.contains("read failed") },
        )
        assertEquals("the quality order is untouched", listOf("a.example"), adapter.connects)
    }

    // ------------------------------------------------------------------ 3

    @Test
    fun `a fast history read still breaks the equal-quality tie`() = runBlocking {
        val adapter = SilentAdapter()
        val first = server("first.example")
        val second = server("second.example")
        val c = controller(adapter, listOf(first, second)) {
            mapOf(
                "second.example" to ServerHistoryView(
                    hostName = "second.example",
                    successCount = 1,
                    failureCount = 0,
                    consecutiveFailures = 0,
                    lastSuccessAt = 1_000L,
                    wifiSuccessCount = 1,
                    cellularSuccessCount = 0,
                    unknownNetworkSuccessCount = 0,
                ),
            )
        }

        c.start()
        val terminal = c.awaitTerminal()

        assertTrue("the run must connect", terminal is AutoModeState.Connected)
        assertEquals(
            "a proven server must be dialled before an equal-quality unknown one",
            listOf("second.example"),
            adapter.connects,
        )
    }

    // ------------------------------------------------------------------ 4

    @Test
    fun `stop during the history read does not hang`() = runBlocking {
        val adapter = SilentAdapter()
        val c = controller(adapter, listOf(server("a.example"))) {
            delay(10_000)
            emptyMap()
        }

        c.start()
        // Let the run reach the read, then stop it while it is waiting there.
        delay(150)
        val stopStarted = System.currentTimeMillis()
        c.stop()
        val finished = withTimeoutOrNull(4_000) {
            while (c.isRunning) delay(10)
            true
        }
        val elapsed = System.currentTimeMillis() - stopStarted

        assertTrue("stop() must not hang behind the read (took ${elapsed}ms)", finished == true)
        assertTrue("the run must be over", !c.isRunning)
        assertTrue(
            "nothing may be dialled after a stop: ${adapter.connects}",
            adapter.connects.isEmpty(),
        )
    }
}
