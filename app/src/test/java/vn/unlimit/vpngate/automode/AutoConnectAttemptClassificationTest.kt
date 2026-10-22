package vn.unlimit.vpngate.automode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v1.0.53 (§1.6.45) TASK B.6: the developer-log classification of one Auto
 * Connect attempt outcome.
 *
 * Pure JVM: `classifyAttempt` is a total function over [AutoModeController.AttemptOutcome],
 * so the table can be pinned without a controller, an adapter or a device.
 */
class AutoConnectAttemptClassificationTest {

    @Test
    fun everyOutcomeHasAClass() {
        assertEquals(
            AutoConnectAttemptClass.CONNECTED,
            classifyAttempt(AutoModeController.AttemptOutcome.TUNNEL_UP),
        )
        assertEquals(
            AutoConnectAttemptClass.REFUSED_START,
            classifyAttempt(AutoModeController.AttemptOutcome.NATIVE_BUSY),
        )
        assertEquals(
            AutoConnectAttemptClass.TIMEOUT_NO_VERDICT,
            classifyAttempt(AutoModeController.AttemptOutcome.TIMED_OUT),
        )
        assertEquals(
            AutoConnectAttemptClass.ENDPOINT_REJECTED,
            classifyAttempt(AutoModeController.AttemptOutcome.NATIVE_ERROR),
        )
        assertEquals(
            AutoConnectAttemptClass.MISSING_CONFIG,
            classifyAttempt(AutoModeController.AttemptOutcome.MISSING_CONFIG),
        )
    }

    /**
     * The table must stay total: a new [AutoModeController.AttemptOutcome] value
     * without a class would make the `when` non-exhaustive and fail to compile,
     * so this guards the intent rather than the compiler.
     */
    @Test
    fun theClassificationCoversEveryOutcomeConstant() {
        val outcomes = AutoModeController.AttemptOutcome.entries
        val classes = outcomes.map { classifyAttempt(it) }.toSet()
        assertEquals(outcomes.size, classes.size)
    }

    /**
     * `MISSING_CONFIG` must be distinguishable from a timeout: that is the whole
     * point of the new outcome. Before v1.0.53 a missing profile was reported as
     * TIMED_OUT, which recorded a wedge against a server that was never dialled.
     */
    @Test
    fun missingConfigIsNotReportedAsATimeout() {
        assertFalse(
            classifyAttempt(AutoModeController.AttemptOutcome.MISSING_CONFIG) ==
                classifyAttempt(AutoModeController.AttemptOutcome.TIMED_OUT),
        )
    }

    /**
     * Source-shape guard: the adapter must report `MISSING_CONFIG` BEFORE it
     * waits, and must not `return` silently on a missing payload.
     *
     * Reads the shipped file as text (CRLF-normalised) rather than reflecting on
     * Android classes, because the class under test cannot be instantiated on the
     * JVM.
     */
    @Test
    fun theAdapterChecksMissingConfigBeforeWaiting() {
        val source = File(
            "src/main/java/vn/unlimit/vpngate/automode/AndroidConnectionAdapter.kt",
        ).readText().replace("\r\n", "\n")

        val guard = source.indexOf("if (lastMissingConfig)")
        val wait = source.indexOf("val up = awaitArmedTunnel(protocol, timeoutMs)")
        assertTrue("the missing-config guard must exist", guard >= 0)
        assertTrue("the guard must precede the wait", wait > guard)

        // The old silent return must be gone: the block now sets the flag.
        assertTrue(source.contains("lastMissingConfig = true"))
        assertTrue(source.contains("reporting MISSING_CONFIG instead of waiting"))
    }
}
