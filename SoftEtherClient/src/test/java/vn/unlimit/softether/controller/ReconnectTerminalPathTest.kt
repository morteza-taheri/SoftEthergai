package vn.unlimit.softether.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FIX-G1-IMPL — the two terminal reconnect paths route through `onError`, and the
 * reconnect budget is reset on the way out.
 *
 * ## Why these tests are source-pinned rather than behavioural
 *
 * `ConnectionController` cannot be constructed off-device: its constructor takes a
 * concrete `SoftEtherVpnService` (a final Android `VpnService` subclass) and the
 * class passes that instance straight to JNI (`service.protect(...)`), plus
 * `ParcelFileDescriptor` for the TUN. This is the same limitation the two
 * neighbouring suites already document —
 * `NativeHandleSingleOwnerTest` ("No `ConnectionController`. It cannot be
 * constructed off-device…") and `NativeAttemptCoordinatorTest` ("…the controller's
 * use of it is a source-level fact").
 *
 * `attemptReconnect()` is a `private suspend` function and `reconnectAttempts` is a
 * private `AtomicInteger`, so neither is reachable from a JVM test. The routes left
 * are (a) extract an interface / add a test seam, which this phase's authorized-file
 * list forbids, or (b) pin the production source text, which is the pattern this
 * codebase already uses where a body needs a real `Context` or service —
 * `AutoModeLifecycleF28Test.disconnectBodySource()` and
 * `AutoModeNativeBusyTest` ("AndroidConnectionAdapter.disconnect() needs a real
 * Context, so what is pinned here is the branch's decision").
 *
 * These tests therefore assert **structural invariants of the production source**:
 * which channel the two terminal branches use, that the local `disconnect()` second
 * channel is gone from the budget branch, and that the counter reset is present on
 * both. A behaviour-level pass/fail still requires a device — see "What is NOT
 * proven" below.
 *
 * ## What these tests DO pin
 *
 *  T1  budget exhaustion resets `reconnectAttempts` before `onError`
 *  T2  the budget branch routes through `onError` and has no local `disconnect()`
 *  T3  the generic catch routes through `onError` (it previously had none)
 *  T4  both terminal paths are single-channel and idempotent in shape
 *
 * ## What is NOT proven
 *
 *  1. **Runtime behaviour.** That `onError` actually reaches
 *     `stopVpn(disconnectByError = true)` on a device, and that the resulting
 *     teardown clears `sessionResourcesLive`, requires the service and a device.
 *  2. **That the stranded session is gone.** The end-to-end symptom
 *     (`REFUSE_CONNECTED` after a network-loss teardown) can only be confirmed on a
 *     device; this phase explicitly excludes device verification.
 *  3. **ART scheduling.** Nothing here models thread interleaving between
 *     `attemptReconnect`'s coroutine, the service's main handler and the
 *     coordinator's lock.
 */
class ReconnectTerminalPathTest {

    // ----------------------------------------------------------------- harness

    /**
     * The production source, located relative to whichever module directory Gradle
     * runs this test from (the library module's working dir is the module root).
     */
    private val controllerSource: String by lazy {
        val candidates = listOf(
            File("src/main/java/vn/unlimit/softether/controller/ConnectionController.kt"),
            File("SoftEtherClient/src/main/java/vn/unlimit/softether/controller/ConnectionController.kt"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("ConnectionController.kt not found from ${File(".").absolutePath}")
        file.readText()
    }

    /** Line comments and KDoc are dropped so prose cannot satisfy a structural assertion. */
    private fun stripComments(text: String): String = text.lineSequence()
        .filterNot {
            val t = it.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        .joinToString("\n")

    /**
     * The body of `attemptReconnect()`: from its declaration to the closing brace of
     * the function. Located by the declaration, then cut at the `finally` block's
     * transfer so both terminal branches are inside the slice.
     */
    private fun attemptReconnectBody(): String {
        val code = stripComments(controllerSource)
        val start = code.indexOf("private suspend fun attemptReconnect()")
        assertTrue("attemptReconnect() must exist", start >= 0)
        // The function ends where the next top-level private fun begins.
        val rest = code.substring(start)
        val end = rest.indexOf("\n    private fun ", startIndex = 1)
        return if (end > 0) rest.substring(0, end) else rest
    }

    /** The budget-exhaustion `if` block: from the guard to its closing brace. */
    private fun budgetBranch(): String {
        val body = attemptReconnectBody()
        val start = body.indexOf("reconnectAttempts.incrementAndGet() >= MAX_RECONNECT_ATTEMPTS")
        assertTrue("the budget-exhaustion guard must exist", start >= 0)
        // To the end of the block: the closing brace at the branch's own indent.
        val end = body.indexOf("\n            }", startIndex = start)
        assertTrue("the budget branch must be a closed block", end > 0)
        return body.substring(start, end)
    }

    /** The `catch (e: Exception)` block of attemptReconnect, up to its `finally`. */
    private fun catchBranch(): String {
        val body = attemptReconnectBody()
        val start = body.indexOf("} catch (e: Exception) {", startIndex = body.indexOf("Reconnection successful"))
        assertTrue("attemptReconnect() must have a trailing catch (e: Exception)", start >= 0)
        val end = body.indexOf("} finally {", startIndex = start)
        assertTrue("the catch must be followed by finally", end > 0)
        return body.substring(start, end)
    }

    // ------------------------------------------------------------------- T1

    /**
     * T1 — `reconnectBudgetExhaustion_resetsCounter`.
     *
     * The budget branch must reset `reconnectAttempts` so a later connect is not born
     * pre-exhausted: the counter is shared with `connect()`'s retry loop.
     */
    @Test
    fun `T1 budget exhaustion resets the reconnect counter`() {
        val branch = budgetBranch()
        assertTrue(
            "the budget-exhaustion branch must reset reconnectAttempts",
            branch.contains("reconnectAttempts.set(0)"),
        )
        // Ordering: the reset must precede onError, so the counter is already clean
        // for any session that the resulting teardown starts.
        val resetAt = branch.indexOf("reconnectAttempts.set(0)")
        val notifyAt = branch.indexOf("onError(")
        assertTrue(
            "the reset must come BEFORE onError (resetAt=$resetAt notifyAt=$notifyAt)",
            resetAt in 0 until notifyAt,
        )
    }

    // ------------------------------------------------------------------- T2

    /**
     * T2 — `reconnectFailure_routesThroughOnError`, single channel.
     *
     * The budget branch must notify through `onError` and must NOT also run the
     * controller-local `disconnect()`, which touches none of the three service fields
     * the start gate reads and would recreate the dual-channel teardown.
     */
    @Test
    fun `T2 budget branch routes through onError and not through local disconnect`() {
        val branch = budgetBranch()
        assertEquals(
            "the budget branch must call onError exactly once",
            1,
            Regex("""\bonError\s*\(""").findAll(branch).count(),
        )
        assertTrue(
            "the budget branch must carry the terminal-reconnect message",
            branch.contains("Connection lost - max reconnection attempts reached"),
        )
        assertFalse(
            "the local disconnect() second channel must be gone from the budget branch",
            Regex("""(^|\n)\s*disconnect\s*\(\s*\)\s*$""", RegexOption.MULTILINE)
                .containsMatchIn(branch),
        )
    }

    // ------------------------------------------------------------------- T3

    /**
     * T3 — `reconnectThrownException_routesThroughOnError`.
     *
     * The generic catch previously had NO `onError` call, so a reconnect that failed
     * by exception never reached the service at all. It must now notify, and reset the
     * counter.
     */
    @Test
    fun `T3 generic catch routes through onError and resets the counter`() {
        val branch = catchBranch()
        assertTrue(
            "the catch must notify through onError (it previously had no notification at all)",
            branch.contains("onError("),
        )
        assertTrue(
            "the catch must carry a message naming the failure",
            branch.contains("Reconnect failed:"),
        )
        assertTrue(
            "the catch must reset the reconnect counter, same rationale as the budget branch",
            branch.contains("reconnectAttempts.set(0)"),
        )
    }

    /**
     * T3b — the ordering choice is the one the report justifies: local cleanup first,
     * notification last. `onError` reaches `stopVpn`, which dispatches
     * `destroyResources()` on a background coroutine, so notifying first would race
     * this method's own `claimAndDestroyNativeHandle()` for the same handle.
     */
    @Test
    fun `T3b catch cleans up before notifying the service`() {
        val branch = catchBranch()
        val cleanupAt = branch.indexOf("claimAndDestroyNativeHandle()")
        val notifyAt = branch.indexOf("onError(")
        assertTrue("the catch must still claim-and-destroy the handle", cleanupAt >= 0)
        assertTrue(
            "local cleanup must run BEFORE onError so the handle has one owner (cleanupAt=$cleanupAt notifyAt=$notifyAt)",
            cleanupAt in 0 until notifyAt,
        )
    }

    // ------------------------------------------------------------------- T4

    /**
     * T4 — `reconnectTerminalPath_idempotentOnSecondCall` (shape).
     *
     * Both terminal branches must be single-channel: exactly one `onError` each, and
     * neither may call `stopVpn` directly. `stopVpn`'s own resource-aware
     * `beginTeardown` is what makes repeated teardown a no-op; the controller must not
     * pre-empt it.
     */
    @Test
    fun `T4 both terminal branches are single-channel and never call stopVpn`() {
        val budget = budgetBranch()
        val catch = catchBranch()
        assertEquals("budget branch: one onError", 1, Regex("""\bonError\s*\(""").findAll(budget).count())
        assertEquals("catch branch: one onError", 1, Regex("""\bonError\s*\(""").findAll(catch).count())
        assertFalse(
            "the controller must not call stopVpn directly — that is the service's channel",
            budget.contains("stopVpn(") || catch.contains("stopVpn("),
        )
        // The controller's only direct service call on a terminal path must remain the
        // TUN release, which is an ownership handoff and not a teardown channel.
        assertFalse(
            "no second cleanup channel may be introduced into the terminal paths",
            Regex("""service\s*\.\s*(stopVpn|stopSelf)\s*\(""").containsMatchIn(budget + catch),
        )
    }

    /**
     * T4b — the terminal branches must not re-clear `isCancelled`. Re-clearing it
     * after a teardown decision re-arms a zombie flow; `attemptReconnect`'s single
     * `isCancelled.set(false)` sits earlier, on the in-progress path only.
     */
    @Test
    fun `T4b terminal branches do not re-clear isCancelled`() {
        val budget = budgetBranch()
        val catch = catchBranch()
        assertFalse(
            "the budget branch must not clear isCancelled",
            budget.contains("isCancelled.set(false)"),
        )
        assertFalse(
            "the catch must not clear isCancelled",
            catch.contains("isCancelled.set(false)"),
        )
    }

    /**
     * T4c — `disconnect()` must still exist as a method (it remains legitimately
     * reachable from `reconnect()`), so the removal at the budget branch cannot be
     * mistaken for a deletion of the API.
     */
    @Test
    fun `T4c local disconnect still exists as an API`() {
        val code = stripComments(controllerSource)
        assertTrue(
            "disconnect() must remain defined; only its second-channel call was removed",
            code.contains("fun disconnect()"),
        )
    }
}
