package vn.unlimit.vpngate.automode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * VpnM Phase 3 "Cursor".
 *
 * The spec asks for a real server cursor (`currentServerIndex`) so "Try next
 * server" from #5 continues at #6 and never wraps to #1. These tests pin the
 * cursor's absolute position across a run, independent of how many protocols
 * each server supports.
 */
class AutoModeServerCursorTest {

    private class RecordingAdapter : AutoModeController.ConnectionAdapter {
        val connectOrder = mutableListOf<String>()
        override suspend fun connect(candidate: AutoModeCandidate, protocol: AutoModeProtocol) {
            connectOrder += candidate.hostname.orEmpty()
        }
        override suspend fun disconnect() = Unit
        // Never verifies a tunnel, so the run walks the whole list and ends in
        // ERROR_NO_SERVER, which is what lets us observe every cursor position.
        override suspend fun awaitTunnel(protocol: AutoModeProtocol, timeoutMs: Long) = false
        override fun log(message: String) = Unit
    }

    private fun server(host: String, speed: Long) = AutoModeCandidate(
        hostname = host,
        ip = "10.0.0.1",
        speed = speed,
        ping = 10,
        sessions = 0,
        score = 0,
        sources = 1,
        seTcpPort = 443,
        seUdpPort = 0,
        seUdpSupported = false,
        openVpnTcpPort = 0,
        openVpnUdpPort = 0,
        l2tpSupported = false,
        sstpSupported = false,
    )

    private fun controller(
        adapter: RecordingAdapter,
        servers: List<AutoModeCandidate>,
        scope: CoroutineScope,
    ) = AutoModeController(
        scope = scope,
        adapter = adapter,
        protocolProvider = { AutoModeProtocol.SOFTETHER_TCP },
        serverProvider = { servers },
        attemptTimeoutMs = 100,
    )

    private suspend fun AutoModeController.awaitDone() {
        while (isRunning) {
            kotlinx.coroutines.delay(25)
        }
    }

    @Test
    fun cursorStartsUnsetAndTotalStartsAtZero() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = controller(RecordingAdapter(), listOf(server("a", 1L)), scope)

        assertEquals("cursor must not claim a position before a run", -1, controller.currentServerIndex)
        assertEquals("total must be unknown before a run", 0, controller.totalServerCount)
        scope.cancel()
    }

    @Test
    fun cursorReportsPositionInTheSortedList() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = RecordingAdapter()
        // Highest speed sorts first, so the first dialed server is index 0.
        val servers = listOf(server("slow", 1L), server("fast", 9_000_000L), server("mid", 5_000_000L))
        val controller = controller(adapter, servers, scope)

        controller.start()
        withTimeoutOrNull(5_000) { controller.awaitDone() }

        assertEquals(3, controller.totalServerCount)
        // Every server was dialed (no tunnel ever verifies), so the cursor must
        // have ended on the last position, not wrapped back to 0.
        assertEquals(2, controller.currentServerIndex)
        assertEquals(listOf("fast", "mid", "slow"), adapter.connectOrder)
        scope.cancel()
    }

    @Test
    fun cursorNeverReturnsToZeroAfterTheFirstServer() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = RecordingAdapter()
        val servers = (1..5).map { server("s$it", it * 1_000_000L) }
        val controller = controller(adapter, servers, scope)

        controller.start()
        withTimeoutOrNull(5_000) { controller.awaitDone() }

        // 5 servers, none connects: the cursor must have passed 0,1,2,3,4 and
        // stay at 4. A cursor that reset to 0 would let "try next" go back to
        // the first server, which the spec forbids.
        assertEquals(4, controller.currentServerIndex)
        assertEquals(5, adapter.connectOrder.size)
        assertEquals("servers must be unique per visit", 5, adapter.connectOrder.toSet().size)
        scope.cancel()
    }
}
