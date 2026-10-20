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

/**
 * v1.0.42 (§1.6.35 / B-3) — T-NEW-44.
 *
 * The native-slot wait budget is the *second* place where a Settings value was
 * frozen at construction: `nativeSlotAwaitMs` was a constructor `val` computed
 * from the same "Auto Connect timeout" preference as the attempt timeout, so a
 * user who shortened the timeout to recover from a wedged run kept paying the
 * old, longer slot wait until the process restarted.
 *
 * This test pins the fix at the only place the value is observable from a test
 * double: [AutoModeController.ConnectionAdapter.awaitNativeSlotRelease] receives
 * the budget as its argument, and the post-attempt site calls it once per
 * attempt. The adapter raises the setting between the two attempts, so a frozen
 * field would report the first value twice.
 *
 * The budget is `min(nativeSlotAwaitMs, attemptTimeoutMs)`, so the attempt
 * timeout here is deliberately large — otherwise the cap would mask the value
 * under test. The adapter returns `true` immediately, so nothing actually waits.
 */
class AutoModeSlotWaitBudgetTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private class RecordingAdapter : AutoModeController.ConnectionAdapter {
        val logs = mutableListOf<String>()
        /** Every slot-wait budget the controller handed down, in call order. */
        val slotBudgets = mutableListOf<Long>()

        override suspend fun connect(candidate: AutoModeCandidate, protocol: AutoModeProtocol) {
            logs += "connect:${candidate.hostname}"
        }

        override suspend fun disconnect() {
            logs += "disconnect"
        }

        /** Both servers time out instantly; the wait is not what is under test. */
        override suspend fun awaitTunnel(protocol: AutoModeProtocol, timeoutMs: Long): Boolean = false

        override suspend fun awaitNativeSlotRelease(timeoutMs: Long): Boolean {
            slotBudgets += timeoutMs
            return true
        }

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

    @Test
    fun theNativeSlotWaitIsReadFreshPerAttempt() = runBlocking {
        val adapter = RecordingAdapter()
        var slotWait = 7_000L
        // A per-attempt call the adapter can use to change the setting mid-run.
        val c = AutoModeController(
            scope = scope,
            adapter = object : AutoModeController.ConnectionAdapter by adapter {
                override suspend fun disconnect() {
                    adapter.logs += "disconnect"
                    // Fires between attempt 1 and attempt 2 (the loop disconnects
                    // before rotating), i.e. after the first budget was read.
                    if (adapter.slotBudgets.size == 1) slotWait = 12_000L
                }
            },
            protocolPriorityProvider = { listOf(AutoModeProtocol.SOFTETHER_UDP) },
            serverProvider = { listOf(server("s1"), server("s2")) },
            attemptTimeoutMsProvider = { 60_000L },
            nativeSlotAwaitMsProvider = { slotWait },
            wedgeTracker = WedgeTracker(),
        )

        c.start()
        withTimeoutOrNull(20_000) {
            while (c.isRunning) delay(5)
        }

        assertTrue(
            "both attempts must reach the post-attempt slot wait: ${adapter.logs}",
            adapter.slotBudgets.size >= 2,
        )
        assertEquals(
            "each attempt must read the provider; a frozen field would repeat 7000",
            listOf(7_000L, 12_000L),
            adapter.slotBudgets.take(2),
        )
    }
}
