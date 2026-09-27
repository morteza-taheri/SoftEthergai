package vn.unlimit.vpngate.automode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VpnM Phase 8 — end-to-end walk of the server-list to auto-connect journey,
 * wired only through public API.
 *
 * The individual phases each have focused tests. This one catches the class of
 * bug unit tests miss: the pieces disagreeing with each other. It exercises
 * server ordering, protocol priority, the empty-list guard, the server cursor
 * and an established tunnel together — the sequence the spec's Phase 8 scenario
 * describes, minus the parts that need a real device.
 */
class AutoModeEndToEndTest {

    private class ScriptedAdapter : AutoModeController.ConnectionAdapter {
        val connectOrder = mutableListOf<String>()
        val protocolOrder = mutableListOf<String>()
        /** Hosts whose tunnel never comes up. */
        val deadHosts = mutableSetOf<String>()

        override suspend fun connect(candidate: AutoModeCandidate, protocol: AutoModeProtocol) {
            connectOrder += candidate.hostname.orEmpty()
            protocolOrder += protocol.id
        }

        override suspend fun disconnect() = Unit

        override suspend fun awaitTunnel(protocol: AutoModeProtocol, timeoutMs: Long): Boolean {
            val host = connectOrder.lastOrNull { it.isNotBlank() }.orEmpty()
            return host !in deadHosts
        }

        override fun log(message: String) = Unit
    }

    private fun server(host: String, speed: Long, seTcp: Boolean = true, seUdp: Boolean = false) =
        AutoModeCandidate(
            hostname = host,
            // Distinct per host: the controller de-duplicates candidates by
            // "ip|hostname", so a shared IP is harmless, but a distinct one
            // keeps the fixture honest about what a real list looks like.
            ip = "10.0.0." + (host.first().code % 250 + 1),
            speed = speed,
            ping = 10,
            sessions = 0,
            score = 0,
            sources = 1,
            seTcpPort = if (seTcp) 443 else 0,
            seUdpPort = 0,
            seUdpSupported = seUdp,
            openVpnTcpPort = 0,
            openVpnUdpPort = 0,
            l2tpSupported = false,
            sstpSupported = false,
        )

    private suspend fun AutoModeController.settle() {
        withTimeoutOrNull(8_000) {
            while (state.value !is AutoModeState.Connected && state.value !is AutoModeState.Error) {
                kotlinx.coroutines.delay(25)
            }
        }
    }

    @Test
    fun fullJourneyPicksFastestServerAndHonoursProtocolPriority() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = ScriptedAdapter()
        val servers = listOf(
            server("alpha", 1_000_000L),
            server("bravo", 9_000_000L, seTcp = true, seUdp = true),
            server("charlie", 5_000_000L),
        )
        val controller = AutoModeController(
            scope = scope,
            adapter = adapter,
            protocolProvider = { AutoModeProtocol.SOFTETHER_TCP },
            // User priority: UDP first, then TCP.
            protocolPriorityProvider = { listOf(AutoModeProtocol.SOFTETHER_UDP, AutoModeProtocol.SOFTETHER_TCP) },
            serverProvider = { servers },
            emptyServerListMessage = { "Server list is empty. Please update the server list first." },
            attemptTimeoutMs = 200,

        )

        controller.start()
        controller.settle()

        assertTrue("expected Connected, got ${controller.state.value}", controller.state.value is AutoModeState.Connected)
        // Server ordering is independent of protocol ordering: "bravo" is fastest,
        // so it must be dialed first even though TCP is the second choice.
        assertEquals("bravo", adapter.connectOrder.first())
        // bravo supports UDP, which is the user's first choice.
        assertEquals(AutoModeProtocol.SOFTETHER_UDP.id, adapter.protocolOrder.first())
        assertEquals(3, controller.totalServerCount)
        scope.cancel()
    }
    @Test
    fun fallsThroughDeadServersInQualityOrder() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = ScriptedAdapter()
        // The two fastest are dead, so the run must reach the third.
        adapter.deadHosts += setOf("fast", "mid")
        val servers = listOf(
            server("slow", 1_000_000L),
            server("mid", 5_000_000L),
            server("fast", 9_000_000L),
        )
        val controller = AutoModeController(
            scope = scope,
            adapter = adapter,
            protocolProvider = { AutoModeProtocol.SOFTETHER_TCP },
            serverProvider = { servers },
            attemptTimeoutMs = 200,
        )

        controller.start()
        controller.settle()

        assertTrue(controller.state.value is AutoModeState.Connected)
        // The run dials in quality order (fast, mid, slow) and the first two
        // never come up, so the server that actually connects is "slow" — the
        // last one dialled.
        assertEquals(
            "dial order must follow quality, highest speed first",
            listOf("fast", "mid", "slow"),
            adapter.connectOrder,
        )
        // 0-based: "slow" is the third and last entry of the quality ordering.
        assertEquals(2, controller.currentServerIndex)
        assertEquals(3, controller.totalServerCount)
        scope.cancel()
    }

    @Test
    fun emptyListStopsAtTheGateAndNeverDials() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = ScriptedAdapter()
        val controller = AutoModeController(
            scope = scope,
            adapter = adapter,
            protocolProvider = { AutoModeProtocol.SOFTETHER_TCP },
            serverProvider = { emptyList() },
            emptyServerListMessage = { "Server list is empty. Please update the server list first." },
        )

        controller.start()
        controller.settle()

        val state = controller.state.value
        assertTrue("expected Error, got $state", state is AutoModeState.Error)
        assertEquals(
            "Server list is empty. Please update the server list first.",
            (state as AutoModeState.Error).message,
        )
        assertTrue("nothing may be dialed", adapter.connectOrder.isEmpty())
        assertEquals(-1, controller.currentServerIndex)
        scope.cancel()
    }

    @Test
    fun allProtocolsUnsupportedOnBestServerSkipsToTheNext() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = ScriptedAdapter()
        // "vpnonly" is fastest but supports neither SoftEther protocol, so the
        // Phase 2 compatibility rule must skip it entirely.
        val servers = listOf(
            server("vpnonly", 9_999_000L, seTcp = false, seUdp = false),
            server("se-host", 1_000_000L, seTcp = true),
        )
        val controller = AutoModeController(
            scope = scope,
            adapter = adapter,
            protocolProvider = { AutoModeProtocol.SOFTETHER_TCP },
            serverProvider = { servers },
            attemptTimeoutMs = 200,
        )

        controller.start()
        controller.settle()

        assertEquals("only the compatible server may be dialed", listOf("se-host"), adapter.connectOrder)
        assertTrue(controller.state.value is AutoModeState.Connected)
        // The cursor points at the real index, not a collapsed 0.
        assertEquals(1, controller.currentServerIndex)
        scope.cancel()
    }
}
