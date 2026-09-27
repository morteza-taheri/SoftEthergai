package vn.unlimit.vpngate.automode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VpnM Phase 5 — the active tunnel liveness watchdog.
 *
 * The failure mode these guard against is a dead tunnel that the UI keeps
 * showing as CONNECTED. The equally important failure mode is the opposite
 * one: reporting a healthy but idle tunnel as dead, which would drop real
 * users. Both directions are pinned here.
 */
class TunnelLivenessWatchdogTest {

    private fun watchdog(
        timeoutMs: Long = 15_000L,
        threshold: Int = 3,
    ) = TunnelLivenessWatchdog(timeoutMs, threshold)

    @Test
    fun noLossIsReportedBeforeAnyProofIsSeen() {
        val wd = watchdog()
        // 10 minutes of samples with no proof ever observed. We cannot claim the
        // tunnel is dead just because we have not watched it yet.
        repeat(600) { i ->
            assertFalse("sample $i must not report a loss", wd.onSample(i * 1000L))
        }
        assertEquals(0, wd.unhealthySamples)
    }

    @Test
    fun healthyTunnelNeverReportsLoss() {
        val wd = watchdog()
        var now = 0L
        // A tunnel that proves life every second is healthy no matter how long
        // the test runs.
        repeat(600) {
            now += 1000L
            wd.onProvenAlive(now)
            assertFalse(wd.onSample(now))
        }
        assertEquals(0, wd.unhealthySamples)
        assertFalse(wd.hasReportedLoss)
    }

    @Test
    fun idleButAliveTunnelIsNotKilled() {
        val wd = watchdog(timeoutMs = 15_000L)
        var now = 0L
        wd.onProvenAlive(now)
        // One proof, then 14s of genuine silence: under the threshold, so a
        // quiet-but-healthy tunnel must survive.
        repeat(14) {
            now += 1000L
            assertFalse("must tolerate ${it + 1}s of silence", wd.onSample(now))
        }
        assertFalse(wd.hasReportedLoss)
    }

    @Test
    fun lossRequiresThresholdConsecutiveUnhealthySamples() {
        val wd = watchdog(timeoutMs = 15_000L, threshold = 3)
        var now = 0L
        wd.onProvenAlive(now)
        // Cross the silence timeout, but only for 2 samples: not yet enough.
        now += 16_000L
        assertFalse(wd.onSample(now))
        now += 1_000L
        assertFalse(wd.onSample(now))
        assertEquals(2, wd.unhealthySamples)
        assertFalse(wd.hasReportedLoss)
        // The third consecutive unhealthy sample is the trigger.
        now += 1_000L
        assertTrue("third unhealthy sample must report the loss", wd.onSample(now))
        assertTrue(wd.hasReportedLoss)
    }

    @Test
    fun lossIsReportedOnlyOnce() {
        val wd = watchdog(timeoutMs = 1_000L, threshold = 1)
        var now = 0L
        wd.onProvenAlive(now)
        now += 2_000L
        assertTrue("first report", wd.onSample(now))
        // Further samples must not keep re-firing: the caller has already
        // transitioned to Disconnected.
        repeat(10) {
            now += 1_000L
            assertFalse("must not re-report after a loss", wd.onSample(now))
        }
    }

    @Test
    fun anIntermittentProofResetsTheFailureCount() {
        val wd = watchdog(timeoutMs = 5_000L, threshold = 3)
        var now = 0L
        wd.onProvenAlive(now)
        now += 6_000L
        assertFalse(wd.onSample(now))
        now += 1_000L
        assertFalse(wd.onSample(now))
        assertEquals(2, wd.unhealthySamples)
        // One proof resets everything, so two more bad samples are not enough.
        now += 1_000L
        wd.onProvenAlive(now)
        assertEquals(0, wd.unhealthySamples)
        now += 6_000L
        assertFalse(wd.onSample(now))
        now += 1_000L
        assertFalse(wd.onSample(now))
        assertFalse("a flapping network must not be declared dead", wd.hasReportedLoss)
    }

    @Test
    fun proofAfterALossReArmsTheWatchdog() {
        val wd = watchdog(timeoutMs = 1_000L, threshold = 1)
        var now = 0L
        wd.onProvenAlive(now)
        now += 2_000L
        assertTrue(wd.onSample(now))
        // A successful reconnect proves life again and re-arms the watchdog.
        wd.onProvenAlive(now)
        assertFalse(wd.hasReportedLoss)
        assertEquals(0, wd.unhealthySamples)
    }

    @Test
    fun resetForgetsEverything() {
        val wd = watchdog(timeoutMs = 1_000L, threshold = 1)
        wd.onProvenAlive(0L)
        assertTrue(wd.onSample(2_000L))
        wd.reset()
        assertFalse(wd.hasReportedLoss)
        assertEquals(0, wd.unhealthySamples)
        // After a reset, with no proof seen, nothing may be reported.
        assertFalse(wd.onSample(100_000L))
    }
}
