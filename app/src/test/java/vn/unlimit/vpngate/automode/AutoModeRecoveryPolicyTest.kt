package vn.unlimit.vpngate.automode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AutoModeRecoveryPolicyTest {

    private fun policy(
        random: Random = Random(0),
        base: Long = 1_000L,
        max: Long = 8_000L,
        pauseAfter: Int = 3,
        pause: Long = 60_000L,
        jitterFraction: Double = 0.2,
    ) = AutoModeRecoveryPolicy(
        baseDelayMs = base,
        maxDelayMs = max,
        pauseAfter = pauseAfter,
        pauseDelayMs = pause,
        jitterFraction = jitterFraction,
        random = random,
    )

    @Test
    fun `delay grows exponentially and is capped`() {
        val p = policy(jitterFraction = 0.0)
        val d1 = p.recordFailure()
        val d2 = p.recordFailure()
        val d3 = p.recordFailure()
        assertEquals(1_000L, d1.delayMs)
        assertEquals(2_000L, d2.delayMs)
        assertEquals(4_000L, d3.delayMs)
    }

    @Test
    fun `jitter stays bounded`() {
        val p = policy(random = Random(42), base = 10_000L, max = 100_000L, pauseAfter = 10)
        repeat(8) {
            val plan = p.recordFailure()
            val nominal = minOf(100_000L, 10_000L * (1L shl it))
            assertTrue(plan.delayMs in (nominal * 0.8).toLong()..(nominal * 1.2).toLong())
        }
    }

    @Test
    fun `rotation starts after the first failure`() {
        val p = policy(pauseAfter = 10)
        assertFalse(p.recordFailure().rotateAwayFromDropped)
        assertTrue(p.recordFailure().rotateAwayFromDropped)
    }

    @Test
    fun `consecutive failures pause instead of terminating`() {
        val p = policy(pauseAfter = 2, pause = 60_000L)
        p.recordFailure()
        p.recordFailure()
        val paused = p.recordFailure()
        assertTrue(paused.paused)
        assertEquals(60_000L, paused.delayMs)
        assertEquals(1, paused.consecutiveFailures)
    }

    @Test
    fun `success restores confidence`() {
        val p = policy(pauseAfter = 10)
        p.recordFailure()
        p.recordFailure()
        p.recordSuccess()
        assertEquals(0, p.failures)
        assertEquals(1, p.recordFailure().consecutiveFailures)
    }

    @Test
    fun `reset clears a partial failure run`() {
        val p = policy(pauseAfter = 10)
        p.recordFailure()
        p.reset()
        assertEquals(0, p.failures)
    }
}
