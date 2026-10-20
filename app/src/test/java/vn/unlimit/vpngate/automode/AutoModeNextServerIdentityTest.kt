package vn.unlimit.vpngate.automode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
        serverProvider: suspend () -> List<AutoModeCandidate>,
    ) = AutoModeController(
        scope = scope,
        adapter = adapter,
        protocolPriorityProvider = { listOf(AutoModeProtocol.SOFTETHER_UDP) },
        serverProvider = serverProvider,
        attemptTimeoutMsProvider = timeoutProvider,
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

    // ── T-NEW-40 ─────────────────────────────────────────────────────────────
    /**
     * A re-sort between the two runs must not shift the continuation.
     *
     * Run 1 resolves `[s1, s2, s3, s4]`, connects on s2 (s1 fails). Run 2 resolves
     * `[s1, s3, s2, s4]` — s2 and s3 swapped. Identity continuation starts after
     * s2, i.e. at s4. The old index continuation would have started at index 2,
     * which now names s2 again: the same server the user just asked to leave.
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
        c.onButtonPressed() // Connected press → continue after s2
        c.awaitTerminal()

        assertEquals(
            "the continuation must start after s2's IDENTITY, not after its old index",
            "s4" to AutoModeProtocol.SOFTETHER_UDP,
            adapter.connects.first(),
        )
        assertTrue(
            "the run must say where it resumed: ${adapter.logs}",
            adapter.logs.any { it.contains("Continuing after") },
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
    @Test
    fun aVanishedServerRestartsFromTheTopAndLogsIt() = runBlocking {
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
            "a vanished server must restart at the top, not silently resume somewhere else",
            "s1" to AutoModeProtocol.SOFTETHER_UDP,
            adapter.connects.first(),
        )
        assertTrue(
            "the fallback must be logged: ${adapter.logs}",
            adapter.logs.any { it.contains("no longer in the list; starting from the top") },
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
        // `skipToNextServer()` launches its restart in a coroutine, so for a
        // moment the state is still the previous `Connected`. Waiting only for
        // the terminal condition would therefore return that stale state with
        // no dial at all; wait for the dial (or for the run to fail) first.
        withTimeoutOrNull(5_000) {
            while (adapter.connects.isEmpty() && c.state.value is AutoModeState.Connected) delay(5)
        }
        val second = c.awaitTerminal()

        assertTrue(
            "the skip must dial the remaining server: ${adapter.logs}",
            adapter.connects.isNotEmpty(),
        )
        assertEquals(
            "a different server on the same IP must survive the skip filter",
            "s2" to AutoModeProtocol.SOFTETHER_UDP,
            adapter.connects.first(),
        )
        assertTrue("expected Connected, got $second", second is AutoModeState.Connected)
        assertEquals("s2", (second as AutoModeState.Connected).hostname)
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
}
