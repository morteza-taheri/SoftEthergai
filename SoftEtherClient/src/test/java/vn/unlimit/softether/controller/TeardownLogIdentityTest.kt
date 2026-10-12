package vn.unlimit.softether.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FIX-LOG-IDENTITY (G-2) — the controller's teardown logs now name their attempt and
 * session, so they can be correlated with the service's teardown logs instead of
 * appearing to contradict them.
 *
 * ## The defect this pins
 *
 * `DEEP_REVIEW_VERIFY_REPORT.md` §10 G-2: a device log contained both
 *
 *   `ConnectionController: Native cleanup confirmed: structure destroyed…`
 *   `[AUTO] Teardown finished but NATIVE CLEANUP UNCONFIRMED (… NOOP_REENTRANT)…`
 *
 * which reads as a contradiction but is not — they describe two *different* teardowns.
 * The pair was unreadable because only the second carried identity. The fix adds
 * `attempt=`/`session=` to the controller's lines.
 *
 * ## Why these tests are source-pinned
 *
 * `ConnectionController` cannot be constructed off-device — its constructor takes a
 * concrete `SoftEtherVpnService` (a final Android `VpnService`) passed straight to JNI,
 * plus `ParcelFileDescriptor`. `destroyResources()` is a public non-suspend method, but
 * there is no way to reach it without an instance. This is the same limitation the
 * neighbouring suites document, and the same remedy they use: pin the production
 * source (see `AutoModeLifecycleF28Test.disconnectBodySource()`, `AutoModeNativeBusyTest`).
 *
 * ## What these tests DO pin
 *
 *  L1  both "cleanup confirmed" lines carry `attempt=` and `session=`
 *  L2  the identity is built from the session param and the native-lease attempt id
 *  L3  the format matches the service's `session=` vocabulary
 *  L4  the unresolved line carries the same identity (all three correlate)
 *
 * ## What is NOT proven
 *
 *  Runtime log output. These tests assert the source's format strings; that the
 *  formatted line reaches logcat with real values requires a device.
 */
class TeardownLogIdentityTest {

    private val controllerSource: String by lazy {
        val candidates = listOf(
            File("src/main/java/vn/unlimit/softether/controller/ConnectionController.kt"),
            File("SoftEtherClient/src/main/java/vn/unlimit/softether/controller/ConnectionController.kt"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("ConnectionController.kt not found from ${File(".").absolutePath}")
        file.readText()
    }

    /**
     * The `destroyResources()` teardown-reporting block: from `requestTeardown` to the
     * end of the `when (outcome)` that reports the three teardown outcomes. Comments
     * are stripped so prose cannot satisfy a structural assertion.
     */
    private fun teardownLogBlock(): String {
        val code = controllerSource.lineSequence()
            .filterNot {
                val t = it.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")
        val start = code.indexOf("val outcome = nativeLifecycle.requestTeardown")
        assertTrue("the teardown request must exist", start >= 0)
        // Through to the outcome mapping that follows the when-block.
        val end = code.indexOf("return when (outcome)", startIndex = start)
        assertTrue("the outcome mapping must follow the when-block", end > start)
        return code.substring(start, end)
    }

    // ------------------------------------------------------------------- L1

    /**
     * L1 — both "cleanup confirmed" log lines must carry the identity.
     */
    @Test
    fun `L1 both cleanup-confirmed log lines carry attempt and session identity`() {
        val block = teardownLogBlock()
        assertTrue(
            "the CONFIRMED line must interpolate the teardown identity",
            block.contains("\"Native cleanup confirmed (\$teardownIdentity): structure destroyed and slot released\""),
        )
        assertTrue(
            "the ALREADY_CONFIRMED line must interpolate the teardown identity",
            block.contains("\"Native cleanup already confirmed by another teardown caller (\$teardownIdentity)\""),
        )
    }

    // ------------------------------------------------------------------- L2

    /**
     * L2 — the identity must be built from the session constructor param and the
     * native-lifetime lease's attempt id. It must NOT invent a new source of identity,
     * and it must tolerate a null lease (the no-reservation case).
     */
    @Test
    fun `L2 identity is built from the session param and the native lease attempt id`() {
        val block = teardownLogBlock()
        assertTrue(
            "the identity must be a local val (no new field or constant)",
            block.contains("val teardownIdentity ="),
        )
        assertTrue(
            "the identity must read the native lease's attempt id",
            block.contains("nativeLease?.attemptId"),
        )
        assertTrue(
            "the identity must name the session",
            block.contains("session=\$sessionId"),
        )
        assertTrue(
            "a null lease must render as a named placeholder, not a silent blank",
            block.contains("?: \"none\""),
        )
    }

    // ------------------------------------------------------------------- L3

    /**
     * L3 — the format must use the service's `session=` vocabulary, so a reader can
     * match the controller's line to the service's `teardown#N(session=M, …)` line.
     */
    @Test
    fun `L3 controller format matches the service teardown vocabulary`() {
        val block = teardownLogBlock()
        assertTrue(
            "the controller must emit the same `session=` key the service's describe() uses",
            block.contains("session=\$sessionId"),
        )
        assertTrue("the controller must emit the `attempt=` key", block.contains("attempt="))
        // The counterpart format, for the correlation the report records.
        val completionSource = listOf(
            File("src/main/java/vn/unlimit/softether/TeardownCompletion.kt"),
            File("SoftEtherClient/src/main/java/vn/unlimit/softether/TeardownCompletion.kt"),
        ).firstOrNull { it.isFile }
            ?: error("TeardownCompletion.kt not found from ${File(".").absolutePath}")
        assertTrue(
            "TeardownCompletion.describe() must still render session= — it is the line the controller now correlates with",
            completionSource.readText().contains("session=\$sessionId"),
        )
    }

    // ------------------------------------------------------------------- L4

    /**
     * L4 — the UNRESOLVED line must carry the same identity, so all three teardown
     * outcomes correlate. Previously it named only the coordinator's phase string.
     */
    @Test
    fun `L4 the unresolved line carries the same identity`() {
        val block = teardownLogBlock()
        assertTrue(
            "the UNRESOLVED line must interpolate the teardown identity too",
            block.contains("\"Native cleanup unresolved (\$teardownIdentity,"),
        )
        assertTrue(
            "the UNRESOLVED line must keep the coordinator's phase detail",
            block.contains("nativeLifecycle.describe()"),
        )
    }

    /**
     * L5 — the fix is diagnosability-only: it must not have altered the reported
     * outcome, the log levels, or the branches.
     */
    @Test
    fun `L5 log levels and outcome mapping are unchanged`() {
        val block = teardownLogBlock()
        assertEquals(
            "the CONFIRMED line must still be Log.d",
            1,
            Regex("""Log\.d\(TAG, "Native cleanup confirmed""").findAll(block).count(),
        )
        assertEquals(
            "the ALREADY_CONFIRMED line must still be Log.d",
            1,
            Regex("""Log\.d\(TAG, "Native cleanup already confirmed""").findAll(block).count(),
        )
        assertEquals(
            "the UNRESOLVED line must still be Log.w",
            1,
            Regex("""Log\.w\(""").findAll(block).count(),
        )
        // The outcome mapping that follows must be untouched.
        val code = controllerSource
        assertTrue(
            "the outcome mapping must still return NATIVE_CONFIRMED / NATIVE_UNRESOLVED",
            code.contains("TeardownCompletion.Outcome.NATIVE_CONFIRMED") &&
                    code.contains("TeardownCompletion.Outcome.NATIVE_UNRESOLVED"),
        )
        assertFalse(
            "no ownership release may be introduced by a log fix",
            block.contains("confirmTeardown("),
        )
    }
}
