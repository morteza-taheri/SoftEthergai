package vn.unlimit.vpngate.automode

/**
 * VpnM Phase 5 — tunnel liveness watchdog.
 *
 * The reported problem is that a dead tunnel can keep the UI on CONNECTED for a
 * long time. The existing detection is entirely passive: the VPN service, the
 * OS network callback, or a stack-specific state listener has to volunteer a
 * "lost" signal. When a tunnel dies quietly (server stops responding, the
 * path black-holes, the device roams between networks) nothing volunteers, and
 * the UI stays green indefinitely.
 *
 * This class is the active half: a small, dependency-free state machine that
 * decides when "we have not seen proof of life for a while" should be treated
 * as a loss. It holds no Android types, so the policy is unit-testable on the
 * JVM and the caller supplies whatever proof signal it has.
 *
 * Design constraints that matter:
 *
 * - An idle tunnel is NOT a dead tunnel. VPN sessions routinely carry no bytes
 *   for minutes, so counting only byte movement would disconnect healthy but
 *   quiet users. A silence window must therefore be *armed* by the caller only
 *   when it has a positive proof signal (a keepalive round-trip, a state
 *   refresh), not merely because bytes stopped.
 * - Hysteresis: the loss is only reported after [lossThreshold] consecutive
 *   unhealthy samples, so one dropped poll or one slow network cannot flap the
 *   UI.
 * - Recovery is explicit. Once reported, the caller must call [onProvenAlive]
 *   to re-arm, which mirrors how a real reconnect re-establishes proof of life.
 */
internal class TunnelLivenessWatchdog(
    /**
     * How long without a proof-of-life signal before a sample counts as
     * unhealthy.
     */
    private val silenceTimeoutMs: Long = DEFAULT_SILENCE_TIMEOUT_MS,
    /** Consecutive unhealthy samples required before reporting a loss. */
    private val lossThreshold: Int = DEFAULT_LOSS_THRESHOLD,
) {
    /**
     * Wall-clock time of the last proof of life, or [NO_PROOF] when none has
     * been observed. A sentinel is required rather than 0 because a watchdog
     * fed a monotonic clock (or a test clock) may legitimately see proof at
     * t=0.
     */
    private var lastProofAtMs: Long = NO_PROOF
    private var consecutiveUnhealthy: Int = 0
    private var reportedLoss: Boolean = false

    /**
     * A positive proof of life: a keepalive reply, a state refresh, a
     * successful socket read. Resets the silence window and the failure count.
     */
    fun onProvenAlive(nowMs: Long) {
        lastProofAtMs = nowMs
        consecutiveUnhealthy = 0
        reportedLoss = false
    }

    /**
     * Feed one sample taken at [nowMs].
     *
     * @return true exactly once when the tunnel should be considered lost.
     */
    fun onSample(nowMs: Long): Boolean {
        if (reportedLoss) return false
        // No proof has ever been seen: we cannot claim the tunnel is dead just
        // because we have not observed it yet.
        if (lastProofAtMs == NO_PROOF) return false

        val silentFor = nowMs - lastProofAtMs
        if (silentFor < silenceTimeoutMs) {
            consecutiveUnhealthy = 0
            return false
        }

        consecutiveUnhealthy++
        if (consecutiveUnhealthy < lossThreshold) return false

        reportedLoss = true
        return true
    }

    /** The number of consecutive unhealthy samples seen so far. Exposed for tests/UI. */
    val unhealthySamples: Int get() = consecutiveUnhealthy

    /** True once a loss has been reported and until proof returns. */
    val hasReportedLoss: Boolean get() = reportedLoss

    /** Forget everything, e.g. when a run stops. */
    fun reset() {
        lastProofAtMs = NO_PROOF
        consecutiveUnhealthy = 0
        reportedLoss = false
    }

    companion object {
        /** Marks "no proof of life observed yet", distinct from a clock reading of 0. */
        const val NO_PROOF: Long = Long.MIN_VALUE

        /**
         * How long without proof of life before the tunnel is considered lost.
         *
         * BLOCK v1.0.81 (review finding #1): raised from 15 s to 30 s.
         *
         * The window has to absorb the worst *legitimate* gap between two
         * proofs, not just the happy path. Samples arrive once per second, so
         * the reported loss lands at `silenceTimeoutMs + lossThreshold` (33 s
         * here). A probe that times out at 3 s and then waits a full 8 s
         * interval produced a 14 s gap before the next success could even be
         * recorded; with the old 15 s window that left ~1 s of margin, and any
         * extra jitter tore down a working tunnel. With 30 s plus the probe's
         * 2 s retry-after-failure, one slow or filtered probe cannot reach the
         * threshold.
         *
         * The cost is detection latency for a genuinely dead tunnel (33 s
         * instead of 18 s), which is the right trade: a false disconnect
         * interrupts a working session, a late detection merely delays a
         * reconnect that Auto Mode then performs anyway.
         */
        const val DEFAULT_SILENCE_TIMEOUT_MS: Long = 30_000L

        /** 3 samples, matching KeepAliveManager.MISSED_THRESHOLD. */
        const val DEFAULT_LOSS_THRESHOLD: Int = 3
    }
}
