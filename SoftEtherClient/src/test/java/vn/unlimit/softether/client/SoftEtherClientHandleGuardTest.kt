package vn.unlimit.softether.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F.28AL-AUTOCONNECT-BUG3-FIX — the `SoftEtherClient` handle-guard regression.
 *
 * ## Why these are source-shape assertions, not behavioural mocks
 *
 * [SoftEtherClient] cannot be constructed in a JVM unit test: its `init` block calls
 * `System.loadLibrary("softether")` (SoftEtherClient.kt:20-22), which throws
 * `UnsatisfiedLinkError` off-device. Its native methods are `external fun` bindings
 * with no injectable seam, so a mock of `nativeSetOption` is not available either.
 *
 * This mirrors the established repo pattern (`AutoModeEndToEndTest
 * .connectSoftEtherPlumbsAttemptTimeoutMs`): the source file is read from disk and
 * the *shape* that carries the fix is asserted. That is sufficient here because the
 * bug was purely a source-level guard mismatch — the fix is a call-site/handle-arity
 * change, and these tests pin exactly the shape that determines whether the native
 * call is reachable:
 *
 *   1. an explicit-handle `setTimeout(handle, timeoutMs)` overload exists and calls
 *      `nativeSetOption(handle, OPTION_TIMEOUT, …)` with the **parameter**, not a field;
 *   2. an explicit-handle `setHalfConnection(handle, half)` overload exists and calls
 *      `nativeSetHalfConnection(handle, …)` with the **parameter**;
 *   3. `ConnectionController` passes its own `nativeHandle` at every call site, so
 *      the controller-owned handle — not the client's permanently-zero private field —
 *      reaches native.
 *
 * ## What is NOT proven here
 *
 * That the native side actually applies `conn->timeout_ms` and bounds the race is a
 * native-runtime fact, verified separately on-device (F.28AL-AUTOCONNECT-BUG3-VERIFY).
 */
class SoftEtherClientHandleGuardTest {

    // The Gradle unit-test CWD for this module is `SoftEtherClient/`.
    private val clientSource = File(
        "src/main/java/vn/unlimit/softether/client/SoftEtherClient.kt",
    )
    private val controllerSource = File(
        "src/main/java/vn/unlimit/softether/controller/ConnectionController.kt",
    )

    private fun readClient(): String {
        assertTrue(
            "SoftEtherClient.kt must be readable from the module test CWD (${clientSource.absolutePath})",
            clientSource.exists(),
        )
        return clientSource.readText()
    }

    private fun readController(): String {
        assertTrue(
            "ConnectionController.kt must be readable from the module test CWD (${controllerSource.absolutePath})",
            controllerSource.exists(),
        )
        return controllerSource.readText()
    }

    // ----------------------------------------------------------------------- T1

    /**
     * T1: the explicit-handle timeout overload exists, guards on its own parameter,
     * and forwards that parameter to `nativeSetOption` with `OPTION_TIMEOUT`.
     *
     * The regression this pins: the legacy `setTimeout(timeoutMs)` guarded on the
     * client's private `nativeHandle` (permanently 0 on the controller-managed path),
     * so `nativeSetOption` was never reached and the native race kept 30000 ms.
     */
    @Test
    fun explicitHandleSetTimeoutReachesNative() {
        val text = readClient()

        assertTrue(
            "SoftEtherClient must declare `fun setTimeout(handle: Long, timeoutMs: Int)`",
            text.contains("fun setTimeout(handle: Long, timeoutMs: Int)"),
        )
        assertTrue(
            "the explicit overload must guard on its `handle` parameter (`if (handle != 0L)`)",
            text.contains("if (handle != 0L)"),
        )
        assertTrue(
            "the explicit overload must forward the `handle` parameter to nativeSetOption",
            text.contains("nativeSetOption(handle, OPTION_TIMEOUT, timeoutMs.toLong())"),
        )
        assertTrue(
            "OPTION_TIMEOUT must still be the option code passed for the timeout",
            text.contains("const val OPTION_TIMEOUT = 1"),
        )
    }

    // ----------------------------------------------------------------------- T2

    /**
     * T2: the explicit-handle duplex overload exists, guards on its own parameter,
     * and forwards that parameter to `nativeSetHalfConnection`.
     *
     * Route note: `setHalfConnection` does NOT go through `nativeSetOption`; it uses
     * the dedicated `nativeSetHalfConnection(handle, halfConnection)` binding. The
     * old body (`if (nativeHandle == 0L) return; nativeSetHalfConnection(nativeHandle, …)`)
     * is preserved verbatim on the deprecated method.
     */
    @Test
    fun explicitHandleSetHalfConnectionReachesNative() {
        val text = readClient()

        assertTrue(
            "SoftEtherClient must declare `fun setHalfConnection(handle: Long, halfConnection: Boolean)`",
            text.contains("fun setHalfConnection(handle: Long, halfConnection: Boolean)"),
        )
        assertTrue(
            "the explicit duplex overload must forward the `handle` parameter to nativeSetHalfConnection",
            text.contains("nativeSetHalfConnection(handle, halfConnection)"),
        )
        // The legacy body must be preserved (only marked @Deprecated).
        assertTrue(
            "the legacy setHalfConnection body must be preserved for backward compatibility",
            text.contains("fun setHalfConnection(halfConnection: Boolean)") &&
                text.contains("nativeSetHalfConnection(nativeHandle, halfConnection)"),
        )
    }

    // ----------------------------------------------------------------------- T3

    /**
     * T3: the deprecated legacy overloads are retained (not deleted) and now resolve
     * through the published `externalHandle` first, so callers that rely on the
     * client's own handle tracking keep working after the fix.
     *
     * `ThroughputBenchmarkTest` (androidTest, outside this phase's edit scope) still
     * calls the single-argument forms, so removal would break that consumer.
     */
    @Test
    fun deprecatedLegacyOverloadsRetainedAndExternalHandleAware() {
        val text = readClient()

        assertTrue(
            "the legacy `setTimeout(timeoutMs: Int)` must remain declared",
            text.contains("fun setTimeout(timeoutMs: Int)"),
        )
        assertTrue(
            "the legacy `setHalfConnection(halfConnection: Boolean)` must remain declared",
            text.contains("fun setHalfConnection(halfConnection: Boolean)"),
        )
        // Both legacy methods must be annotated @Deprecated pointing at the new overloads.
        assertTrue(
            "the legacy setTimeout must be @Deprecated with a pointer to the explicit overload",
            text.contains("@Deprecated(") &&
                text.contains("Use setTimeout(handle, timeoutMs)."),
        )
        assertTrue(
            "the legacy setHalfConnection must be @Deprecated with a pointer to the explicit overload",
            text.contains("Use setHalfConnection(handle, halfConnection)."),
        )
        // The legacy setTimeout body now resolves externalHandle first (backward compat).
        assertTrue(
            "the legacy setTimeout must resolve the published externalHandle first",
            text.contains("val h = externalHandle.takeIf { it != 0L } ?: nativeHandle"),
        )
    }

    // ----------------------------------------------------------------------- T4

    /**
     * T4 (regression, fix-completeness): every `ConnectionController` call site passes
     * its own `nativeHandle` explicitly, and the timeout call still precedes the
     * native connect (the race must be configured before it starts).
     */
    @Test
    fun controllerPassesOwnNativeHandleAtEveryCallSite() {
        val text = readController()

        assertTrue(
            "initial connect must call client.setTimeout(nativeHandle, config.connectTimeoutMs)",
            text.contains("client.setTimeout(nativeHandle, config.connectTimeoutMs)"),
        )
        assertTrue(
            "the reconnect path must call client.setTimeout(nativeHandle, config.connectTimeoutMs)",
            text.contains("client.setTimeout(nativeHandle, config.connectTimeoutMs)"),
        )
        assertTrue(
            "duplex selection must pass nativeHandle explicitly",
            text.contains("client.setHalfConnection(nativeHandle, !fullDuplex)"),
        )
        assertTrue(
            "reconnect duplex selection must pass nativeHandle explicitly",
            text.contains("client.setHalfConnection(nativeHandle, !reconnectFullDuplex)"),
        )

        // Ordering: setTimeout(nativeHandle, …) must appear before nativeConnectWithHub.
        val timeoutIdx = text.indexOf("client.setTimeout(nativeHandle, config.connectTimeoutMs)")
        val connectIdx = text.indexOf("client.nativeConnectWithHub(")
        assertTrue(
            "setTimeout must be configured before nativeConnectWithHub starts the race",
            timeoutIdx in 0 until connectIdx,
        )

        // No single-arg legacy call may remain in the controller.
        assertFalse(
            "no legacy single-arg setTimeout(…) may remain in ConnectionController",
            Regex("""client\.setTimeout\(\s*config\.connectTimeoutMs\s*\)""").containsMatchIn(text),
        )
        assertFalse(
            "no legacy single-arg setHalfConnection(…) may remain in ConnectionController",
            Regex("""client\.setHalfConnection\(\s*!""").containsMatchIn(text),
        )
    }
}
