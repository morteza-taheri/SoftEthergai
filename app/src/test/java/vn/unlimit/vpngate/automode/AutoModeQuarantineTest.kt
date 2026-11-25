package vn.unlimit.vpngate.automode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v1.0.87 — Auto Mode must stop re-dialling a server that connects and then
 * drops within seconds.
 *
 * The unit contract lives in [ServerQuarantineTest]; this test drives the real
 * controller so the *wiring* is covered: the uptime is recorded on the
 * production loss funnel, the candidate list is filtered before the run, and an
 * exhausted override list falls back to the provider instead of failing.
 */
class AutoModeQuarantineTest {

    private class FakeAdapter : AutoModeController.ConnectionAdapter {
        val logs = mutableListOf<String>()
        val connects = mutableListOf<String?>()

        override suspend fun connect(candidate: AutoModeCandidate, protocol: AutoModeProtocol) {
            connects += candidate.hostname
        }

        override suspend fun disconnect() = Unit

        override suspend fun awaitTunnel(protocol: AutoModeProtocol, timeoutMs: Long): Boolean = true

        override fun log(message: String) {
            logs += message
        }
    }

    private lateinit var scope: CoroutineScope
    private lateinit var adapter: FakeAdapter
    private lateinit var quarantine: ServerQuarantine

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        adapter = FakeAdapter()
        quarantine = ServerQuarantine()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun candidate(host: String, ip: String) = AutoModeCandidate(
        hostname = host, ip = ip, speed = 10_000_000, ping = 50,
        sessions = 100, score = 1000, sources = 1,
        seTcpPort = 443, seUdpPort = 0, seUdpSupported = true,
        openVpnTcpPort = 0, openVpnUdpPort = 0,
        l2tpSupported = false, sstpSupported = false,
    )

    private fun controller(servers: List<AutoModeCandidate>) = AutoModeController(
        scope = scope,
        adapter = adapter,
        protocolPriorityProvider = { listOf(AutoModeProtocol.SOFTETHER_UDP) },
        serverProvider = { servers },
        attemptTimeoutMsProvider = { 500 },
        autoReconnectEnabled = { true },
        reconnectInitialDelayMs = 0L,
        recoveryPauseDelayMs = 50L,
        quarantine = quarantine,
    )

    private suspend fun AutoModeController.awaitConnected() {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (state.value is AutoModeState.Connected && !isRunning) return
            delay(10)
        }
        error("never reached Connected; last=${state.value}")
    }

    private suspend fun AutoModeController.flap() {
        awaitConnected()
        onUnexpectedDisconnectForTests()
        delay(150)
    }

    @Test
    fun `a server that flaps twice is skipped on the next run`() = runBlocking {
        val alpha = candidate("alpha", "10.0.0.1")
        val beta = candidate("beta", "10.0.0.2")
        val controller = controller(listOf(alpha, beta))

        controller.start()
        // First cycle: alpha connects, dies quickly. One strike — still eligible.
        controller.flap()
        assertEquals("one strike is not a quarantine", 1, quarantine.strikesFor("10.0.0.1|alpha"))

        // Second cycle: alpha is re-dialled (rotateAway is false on the first
        // loss) and dies again. That is the second strike.
        controller.flap()
        assertEquals(2, quarantine.strikesFor("10.0.0.1|alpha"))
        assertTrue("alpha must be quarantined", quarantine.isQuarantined("10.0.0.1|alpha"))

        // Third run: alpha must not be dialled again.
        controller.awaitConnected()
        assertEquals("beta", adapter.connects.last())
        assertTrue(
            "the skip must be visible in the log; logs=${adapter.logs}",
            adapter.logs.any { it.contains("Quarantine: skipping") },
        )
    }

    @Test
    fun `a fully quarantined list still starts instead of failing`() = runBlocking {
        val alpha = candidate("alpha", "10.0.0.1")
        val controller = controller(listOf(alpha))
        quarantine.onTunnelLost("10.0.0.1|alpha", 1_000)
        quarantine.onTunnelLost("10.0.0.1|alpha", 1_000)
        assertTrue(quarantine.isQuarantined("10.0.0.1|alpha"))

        controller.start()
        controller.awaitConnected()
        assertTrue(
            "the run must not refuse to start; logs=${adapter.logs}",
            adapter.logs.any { it.contains("every candidate is cooling down") },
        )
    }
}
