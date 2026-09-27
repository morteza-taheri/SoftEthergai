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
         * Matches the SoftEther keepalive timeout (10 s) used in
         * ConnectionController.startKeepalive, with headroom for a slow poll.
         */
        const val DEFAULT_SILENCE_TIMEOUT_MS: Long = 15_000L

        /** 3 samples, matching KeepAliveManager.MISSED_THRESHOLD. */
        const val DEFAULT_LOSS_THRESHOLD: Int = 3
    }
}
