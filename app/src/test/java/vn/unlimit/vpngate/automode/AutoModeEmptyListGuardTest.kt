package vn.unlimit.vpngate.automode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VpnM Phase 1.5 — the empty-server-list guard.
 *
 * Kept in its own file because it exercises a single behaviour with its own
 * fixture shape, and because the spec calls out this scenario explicitly.
 */
class AutoModeEmptyListGuardTest {

    private val EMPTY_MESSAGE = "Server list is empty. Please update the server list first."

    private class RecordingAdapter : AutoModeController.ConnectionAdapter {
        val connectCalls = mutableListOf<String>()
        override suspend fun connect(candidate: AutoModeCandidate, protocol: AutoModeProtocol) {
            connectCalls += candidate.hostname ?: candidate.ip.orEmpty()
        }
        override suspend fun disconnect() = Unit
        override suspend fun awaitTunnel(protocol: AutoModeProtocol, timeoutMs: Long) = false
        override fun log(message: String) = Unit
    }

    private fun controller(
        adapter: RecordingAdapter,
        servers: List<AutoModeCandidate>,
        scope: CoroutineScope,
    ) = AutoModeController(
        scope = scope,
        adapter = adapter,
        protocolProvider = { AutoModeProtocol.SOFTETHER_TCP },
        serverProvider = { servers },
        emptyServerListMessage = { EMPTY_MESSAGE },
    )

    @Test
    fun emptyListRefusesToStart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = RecordingAdapter()
        val controller = controller(adapter, emptyList(), scope)

        val started = controller.startOrRefuseIfNoServers()

        assertFalse("an empty list must not start a run", started)
        assertTrue("no connection attempt may be made", adapter.connectCalls.isEmpty())
        assertFalse("no run may be left active", controller.isRunning)
        scope.cancel()
    }

    @Test
    fun emptyListReportsTheDedicatedMessage() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = RecordingAdapter()
        val controller = controller(adapter, emptyList(), scope)

        controller.startOrRefuseIfNoServers()

        val state = controller.state.value
        assertTrue("expected an Error state, got $state", state is AutoModeState.Error)
        assertEquals(
            "the guard must surface the actionable message, not a generic one",
            EMPTY_MESSAGE,
            (state as AutoModeState.Error).message,
        )
        scope.cancel()
    }

    @Test
    fun emptyListMessageIsNotTheGenericErrorCode() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = RecordingAdapter()
        val controller = controller(adapter, emptyList(), scope)

        controller.startOrRefuseIfNoServers()

        val message = (controller.state.value as AutoModeState.Error).message
        // The Auto Mode screen maps these two codes to their own strings; an
        // empty list must not be reported as either, or the user would be told
        // to retry a connection that can never start.
        assertFalse(message == AutoModeController.ERROR_NO_SERVER)
        assertFalse(message == AutoModeController.ERROR_VPN_PERMISSION)
        scope.cancel()
    }

    private fun server(hostname: String) = AutoModeCandidate(
        hostname = hostname,
        ip = "10.0.0.1",
        speed = 1L,
        ping = 1,
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

    @Test
    fun nonEmptyListStillStarts() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = RecordingAdapter()
        val controller = controller(adapter, listOf(server("good.example")), scope)

        val started = controller.startOrRefuseIfNoServers()

        assertTrue("a populated list must start normally", started)
        assertTrue("the run must be active", controller.isRunning)
        scope.cancel()
    }

    @After
    fun noop() = Unit
}
