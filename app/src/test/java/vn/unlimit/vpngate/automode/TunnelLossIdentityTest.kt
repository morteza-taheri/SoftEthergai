package vn.unlimit.vpngate.automode

import de.blinkt.openvpn.core.ConnectionStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vn.unlimit.softether.SoftEtherVpnService

/**
 * BLOCK v1.0.81 (review finding #4) — a tunnel loss must be reported by the
 * stack that is actually carrying the session, and by no other.
 *
 * The defect this pins down: [TunnelStateWatcher] forwarded every stack's
 * terminal event to `onTunnelLost` with no identity check. Terminal events
 * arrive late by nature (a teardown of the previous attempt finishes after the
 * next tunnel is already up), so a stale SoftEther `STATE_DISCONNECTED` could
 * tear down a freshly connected OpenVPN or SSTP session. That is one of the
 * mechanisms behind "connects, then immediately disconnects".
 */
class TunnelLossIdentityTest {

    private var losses = 0

    /**
     * NOTE: `TunnelStateWatcher.detach()` must NOT be used here.
     *
     * It calls `SoftEtherVpnService.removeStateListener(...)`, which forces that
     * service class to initialise; its initialiser builds a `Handler` on the
     * main looper and throws in a plain JVM test. `beginAttempt`/`endAttempt`
     * touch only the watcher's own fields, which is all this test needs.
     */
    @Before
    fun setUp() {
        losses = 0
        TunnelStateWatcher.onTunnelLost = { losses++ }
    }

    @After
    fun tearDown() {
        TunnelStateWatcher.endAttempt()
        TunnelStateWatcher.onTunnelLost = null
        losses = 0
    }

    private fun arm(protocol: AutoModeProtocol) {
        TunnelStateWatcher.beginAttempt(protocol) { }
    }

    @Test
    fun `a late SoftEther disconnect does not tear down an OpenVPN session`() {
        arm(AutoModeProtocol.OPENVPN_TCP)
        // OpenVPN reports CONNECTED: it is now the stack carrying the session.
        TunnelStateWatcher.updateState(
            "CONNECTED", "", 0, ConnectionStatus.LEVEL_CONNECTED, null,
        )

        // A SoftEther teardown from the PREVIOUS attempt finishes late.
        TunnelStateWatcher.onSoftEtherStateChanged(SoftEtherVpnService.STATE_DISCONNECTED, "")

        assertEquals(
            "a foreign stack's terminal event must not report a loss",
            0,
            losses,
        )
    }

    @Test
    fun `the connected stack still reports its own loss`() {
        arm(AutoModeProtocol.OPENVPN_TCP)
        TunnelStateWatcher.updateState(
            "CONNECTED", "", 0, ConnectionStatus.LEVEL_CONNECTED, null,
        )

        TunnelStateWatcher.updateState(
            "DISCONNECTED", "", 0, ConnectionStatus.LEVEL_NOTCONNECTED, null,
        )

        assertEquals("the owning stack's terminal event must report a loss", 1, losses)
    }

    @Test
    fun `a loss is reported at most once per session`() {
        arm(AutoModeProtocol.SOFTETHER_TCP)
        TunnelStateWatcher.onSoftEtherStateChanged(SoftEtherVpnService.STATE_CONNECTED, "10.0.0.2")

        // The service publishes both DISCONNECTED and (on error paths) ERROR.
        TunnelStateWatcher.onSoftEtherStateChanged(SoftEtherVpnService.STATE_DISCONNECTED, "")
        TunnelStateWatcher.onSoftEtherStateChanged(SoftEtherVpnService.STATE_ERROR, "")

        assertEquals("a duplicated terminal event must not double-report", 1, losses)
    }

    @Test
    fun `an unconnected watcher never reports a loss`() {
        // No attempt armed, no CONNECTED seen: a stray terminal event from any
        // stack must be ignored rather than treated as "our tunnel died".
        TunnelStateWatcher.onSoftEtherStateChanged(SoftEtherVpnService.STATE_DISCONNECTED, "")
        TunnelStateWatcher.updateState(
            "DISCONNECTED", "", 0, ConnectionStatus.LEVEL_NOTCONNECTED, null,
        )

        assertEquals(0, losses)
    }

    @Test
    fun `the silence window absorbs a single timed-out probe`() {
        // BLOCK v1.0.81 (review finding #1). The worst legitimate gap caused by
        // ONE probe that times out is
        //   probeTimeout(3s) + retry(2s) + probeTimeout(3s) = 8s
        // and by one that times out on the slow path
        //   interval(8s) + probeTimeout(3s) + retry(2s) + probeTimeout(3s) = 16s.
        // The watchdog must not fire on that.
        val wd = TunnelLivenessWatchdog()
        wd.onProvenAlive(0L)

        var firedAt: Long? = null
        var t = 0L
        while (t <= 40_000L) {
            t += 1_000L
            if (wd.onSample(t)) {
                firedAt = t
                break
            }
        }

        assertTrue("the watchdog must eventually fire", firedAt != null)
        assertTrue(
            "must not fire on a 16 s probe gap, fired at ${firedAt}ms",
            firedAt!! >= 30_000L,
        )
        assertEquals(
            // The first unhealthy sample lands exactly ON the 30 s boundary
            // (silentFor >= timeout), so the threshold adds threshold-1 further
            // samples: 30 s + 2 s = 32 s.
            "the window plus hysteresis is 30 s + 2 samples",
            32_000L,
            firedAt,
        )
    }

    @Test
    fun `the documented invariant still holds`() {
        assertTrue(
            "interval + timeout must fit inside the silence window",
            TunnelLivenessProbe.DEFAULT_INTERVAL_MS +
                TunnelLivenessProbe.DEFAULT_PROBE_TIMEOUT_MS <
                TunnelLivenessWatchdog.DEFAULT_SILENCE_TIMEOUT_MS,
        )
        assertTrue(
            "a failed probe's worst gap must also fit",
            TunnelLivenessProbe.DEFAULT_INTERVAL_MS +
                TunnelLivenessProbe.DEFAULT_PROBE_TIMEOUT_MS +
                TunnelLivenessProbe.PROBE_RETRY_MS +
                TunnelLivenessProbe.DEFAULT_PROBE_TIMEOUT_MS <
                TunnelLivenessWatchdog.DEFAULT_SILENCE_TIMEOUT_MS,
        )
    }

    @Test
    fun `the probe offers more than one target`() {
        assertTrue(
            "one filtered endpoint must not be able to condemn every tunnel",
            TunnelLivenessProbe.DEFAULT_TARGETS.size >= 2,
        )
        assertFalse(
            "targets must be distinct",
            TunnelLivenessProbe.DEFAULT_TARGETS.size !=
                TunnelLivenessProbe.DEFAULT_TARGETS.toSet().size,
        )
    }
}
