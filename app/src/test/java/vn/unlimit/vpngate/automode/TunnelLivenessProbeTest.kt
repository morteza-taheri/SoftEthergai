package vn.unlimit.vpngate.automode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/**
 * VpnM Phase 5 — [TunnelLivenessProbe].
 *
 * A real loopback server stands in for the internet: a connect that reaches it
 * is the same shape of event as a round trip through the tunnel, and closing
 * the listener makes every later probe fail, which is how a black-holed path
 * behaves. The behaviours pinned here are the ones a user would notice: an
 * idle-but-healthy tunnel must never be declared dead, and a tunnel that stops
 * carrying traffic must stop producing proof.
 */
class TunnelLivenessProbeTest {

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun probe(
        scope: CoroutineScope,
        port: Int,
        intervalMs: Long = 60L,
        timeoutMs: Int = 500,
    ) = TunnelLivenessProbe(
        scope = scope,
        probeHost = "127.0.0.1",
        probePort = port,
        probeTimeoutMs = timeoutMs,
        intervalMs = intervalMs,
    )

    private fun awaitProof(p: TunnelLivenessProbe, timeoutMs: Long = 5_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (p.consumeProof()) return true
            Thread.sleep(20)
        }
        return false
    }

    @Test
    fun noProofIsReportedBeforeTheFirstProbeCompletes() = runBlocking {
        ServerSocket(0).use { listener ->
            val s = scope()
            val p = probe(s, listener.localPort)
            // Not started: a probe that was never armed must not claim life.
            assertFalse(p.hasProof)
            assertFalse(p.consumeProof())
            s.cancel()
        }
    }

    @Test
    fun aReachableEndpointProducesConsumableProof() = runBlocking {
        ServerSocket(0).use { listener ->
            val s = scope()
            val p = probe(s, listener.localPort)
            p.start(AutoModeProtocol.SOFTETHER_UDP)
            try {
                assertTrue(
                    "probe should report a completed round trip",
                    awaitProof(p),
                )
            } finally {
                p.stop()
                s.cancel()
            }
        }
    }

    @Test
    fun anIdleButReachableTunnelKeepsProducingProof() = runBlocking {
        ServerSocket(0).use { listener ->
            val s = scope()
            val p = probe(s, listener.localPort, intervalMs = 40L)
            p.start(AutoModeProtocol.OPENVPN_TCP)
            try {
                // Three separate successes: an idle tunnel must not fall silent
                // just because no user traffic is flowing.
                repeat(3) { i ->
                    assertTrue("idle proof $i missing", awaitProof(p))
                }
            } finally {
                p.stop()
                s.cancel()
            }
        }
    }

    @Test
    fun proofStopsOnceThePathStopsCarryingTraffic() = runBlocking {
        val listener = ServerSocket(0)
        val port = listener.localPort
        val s = scope()
        val p = probe(s, port, intervalMs = 40L)
        p.start(AutoModeProtocol.SOFTETHER_TCP)
        try {
            assertTrue("initial proof missing", awaitProof(p))
            // Drain any proof still queued by the first probe, then black-hole
            // the path exactly as a dropped route would.
            p.consumeProof()
            listener.close()

            Thread.sleep(250)
            assertFalse("a dead path must not keep proving life", p.consumeProof())
        } finally {
            p.stop()
            s.cancel()
        }
    }

    @Test
    fun proofIsConsumedExactlyOncePerSuccessfulProbe() = runBlocking {
        ServerSocket(0).use { listener ->
            val s = scope()
            val p = probe(s, listener.localPort, intervalMs = 10_000L)
            p.start(AutoModeProtocol.MS_SSTP)
            try {
                assertTrue(awaitProof(p))
                // The watchdog samples far more often than the probe runs; the
                // same proof must not satisfy two consecutive samples or the
                // silence window could never elapse.
                assertFalse(p.consumeProof())
                assertFalse(p.consumeProof())
            } finally {
                p.stop()
                s.cancel()
            }
        }
    }

    @Test
    fun stopClearsProofAndPreventsFurtherProbing() = runBlocking {
        ServerSocket(0).use { listener ->
            val s = scope()
            val p = probe(s, listener.localPort, intervalMs = 40L)
            p.start(AutoModeProtocol.SOFTETHER_UDP)
            assertTrue(awaitProof(p))
            p.stop()
            assertFalse("a stopped probe must forget its proof", p.hasProof)
            // A stopped probe must not resurrect itself on the next tick.
            delay(150)
            assertFalse(p.consumeProof())
            s.cancel()
        }
    }

    @Test
    fun startIsIdempotentSoReconnectsDoNotStackProbes() = runBlocking {
        ServerSocket(0).use { listener ->
            val s = scope()
            val p = probe(s, listener.localPort, intervalMs = 40L)
            p.start(AutoModeProtocol.SOFTETHER_UDP)
            // Repeated starts must not create overlapping loops.
            repeat(5) { p.start(AutoModeProtocol.SOFTETHER_UDP) }
            try {
                assertTrue(awaitProof(p))
            } finally {
                p.stop()
                s.cancel()
            }
        }
    }

    @Test
    fun anUnreachableEndpointNeverClaimsProof() = runBlocking {
        val listener = ServerSocket(0)
        val port = listener.localPort
        listener.close() // nothing is listening on this port any more
        val s = scope()
        val p = probe(s, port, intervalMs = 40L)
        p.start(AutoModeProtocol.OPENVPN_UDP)
        try {
            delay(300)
            assertFalse(p.hasProof)
            assertFalse(p.consumeProof())
        } finally {
            p.stop()
            s.cancel()
        }
    }

    @Test
    fun theDefaultProbeTargetIsAValidAnycastEndpoint() {
        // Guards the shipped default: a blank host or an out-of-range port would
        // silently disable the watchdog on every device.
        assertTrue(TunnelLivenessProbe.DEFAULT_PROBE_HOST.isNotBlank())
        assertTrue(TunnelLivenessProbe.DEFAULT_PROBE_PORT in 1..65535)
        assertTrue(TunnelLivenessProbe.DEFAULT_PROBE_TIMEOUT_MS > 0)
        // A healthy tunnel must always have time to deliver the next proof
        // before the watchdog's silence window expires, otherwise a good
        // tunnel would be declared lost between two probes.
        assertTrue(
            "probe interval plus timeout must fit inside the silence window",
            TunnelLivenessProbe.DEFAULT_INTERVAL_MS + TunnelLivenessProbe.DEFAULT_PROBE_TIMEOUT_MS <
                TunnelLivenessWatchdog.DEFAULT_SILENCE_TIMEOUT_MS,
        )
    }
}