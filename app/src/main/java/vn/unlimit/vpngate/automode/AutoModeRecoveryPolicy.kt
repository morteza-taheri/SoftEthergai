package vn.unlimit.vpngate.automode

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Pure, JVM-testable recovery policy for Auto Mode.
 *
 * It replaces the old "5 losses in 10 minutes -> ERROR_RECONNECT_LIMIT"
 * behaviour, which turned a transient run of bad servers into a permanently
 * dead Auto Mode. The policy instead produces a bounded exponential-backoff
 * delay with jitter, rotates away from the failed server after the first
 * loss, and *pauses* (never terminates) after [pauseAfter] consecutive losses.
 * A successful connection restores confidence immediately.
 *
 * No Android types are used here so the policy can be unit tested directly.
 */
data class AutoModeRecoveryPlan(
    val consecutiveFailures: Int,
    val delayMs: Long,
    val rotateAwayFromDropped: Boolean,
    val paused: Boolean,
)

class AutoModeRecoveryPolicy(
    private val baseDelayMs: Long,
    private val maxDelayMs: Long,
    private val pauseAfter: Int,
    private val pauseDelayMs: Long,
    private val jitterFraction: Double = 0.2,
    private val random: Random = Random.Default,
) {
    private var consecutiveFailures: Int = 0

    val failures: Int get() = consecutiveFailures

    /**
     * Records another unexpected loss and returns the next action.
     *
     * Delay is `min(base * 2^(n-1), max)` with symmetric bounded jitter, so a
     * fleet of devices does not retry in lockstep. After [pauseAfter] losses
     * the run pauses for [pauseDelayMs] and starts a fresh backoff cycle
     * instead of entering a terminal error.
     */
    fun recordFailure(): AutoModeRecoveryPlan {
        consecutiveFailures++
        if (consecutiveFailures > pauseAfter) {
            consecutiveFailures = 1
            return AutoModeRecoveryPlan(
                consecutiveFailures = 1,
                delayMs = pauseDelayMs,
                rotateAwayFromDropped = true,
                paused = true,
            )
        }
        val exponent = (consecutiveFailures - 1).coerceAtMost(MAX_EXPONENT)
        val nominal = min(maxDelayMs.toDouble(), baseDelayMs * 2.0.pow(exponent))
        val jitterRange = nominal * jitterFraction.coerceIn(0.0, 1.0)
        val jitter = if (jitterRange > 0.0) random.nextDouble(-jitterRange, jitterRange) else 0.0
        val delay = (nominal + jitter).toLong().coerceAtLeast(0L).coerceAtMost(maxDelayMs)
        return AutoModeRecoveryPlan(
            consecutiveFailures = consecutiveFailures,
            delayMs = delay,
            rotateAwayFromDropped = consecutiveFailures > 1,
            paused = false,
        )
    }

    /** A verified tunnel restores confidence without destroying history. */
    fun recordSuccess() {
        consecutiveFailures = 0
    }

    /** A user stop or disconnect ends the recovery session. */
    fun reset() {
        consecutiveFailures = 0
    }

    private companion object {
        const val MAX_EXPONENT = 20
    }
}
