package vn.unlimit.vpngate.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-NEW-73 — source-shape regression for v1.0.47 (§1.6.40)
 * REMOVE-SELF-EXCLUSION.
 *
 * ## Why a source-shape test and not a behavioural one
 *
 * The defect lives in `VpnService.Builder` calls, which need a real Android
 * `VpnService` and a real `PackageManager`. This module's unit tests run on a
 * plain JVM (no Robolectric, no instrumentation), so the builder cannot be
 * driven here. The contract is therefore pinned textually — the same approach
 * `AutoModeControllerTest` already uses for `DataUtil` / `AutoModeEngine`.
 *
 * ## What is actually being protected
 *
 * The app inserts a hidden `ExcludedApp(packageName = <self>, appName = "Self")`
 * row into the `excluded_app` table at startup ("hidden from user, cannot be
 * removed"). That row flows into **two independent** exclusion application
 * points:
 *
 *  1. SoftEther SSL-VPN — `config.excludedApps` → `SoftEtherVpnService.buildTun`
 *  2. MS-SSTP           — `ROUTE_EXCLUDED_APPS` → `IPTerminal.addExcludedAppRules`
 *
 * Applying the app's own package at either point makes every socket the
 * collector binds to the VPN network fail with
 * `EPERM (Operation not permitted)`, so the TUNNEL rung can never deliver data.
 * Device evidence: `DEVICE_VERIFY_v1.0.46_REPORT.md` §7.4.
 *
 * Both points must skip the app's own package **and** keep applying every
 * user-added exclusion. These tests pin exactly that pair of properties.
 */
class SelfExclusionRemovedTest {

    // ---------------------------------------------------------------- paths

    private fun sourceOf(relativePath: String): String {
        for (path in listOf(
            relativePath,
            "../$relativePath",
            "app/$relativePath",
            "../../$relativePath",
        )) {
            val file = File(path)
            if (file.isFile) return file.readText(Charsets.UTF_8)
        }
        error("source file not found: $relativePath (cwd=${File(".").absolutePath})")
    }

    private fun softEtherService(): String =
        sourceOf("SoftEtherClient/src/main/java/vn/unlimit/softether/SoftEtherVpnService.kt")

    private fun sstpTerminal(): String =
        sourceOf("sstpClient/src/main/java/kittoku/osc/terminal/IPTerminal.kt")

    // ------------------------------------------------------- T-NEW-73 (a)

    /**
     * T-NEW-73 — the SoftEther builder must not exclude the app's own package.
     *
     * The pre-v1.0.47 code was
     * `config.excludedApps.forEach { packageName -> builder.addDisallowedApplication(packageName) }`,
     * where the lambda parameter `packageName` shadowed `Context.packageName`.
     * That exact call is the defect and must never come back.
     */
    @Test
    fun `T-NEW-73 softether builder does not exclude the app's own package`() {
        val source = softEtherService()

        assertFalse(
            "SoftEtherVpnService must not call addDisallowedApplication(packageName) — " +
                "the lambda parameter shadows Context.packageName and excludes the app " +
                "from its own tunnel (v1.0.47 §1.6.40)",
            source.contains("addDisallowedApplication(packageName)"),
        )

        assertTrue(
            "the service's own package must be captured before the loop, where " +
                "`packageName` still resolves to Context.packageName",
            source.contains("val selfPackage = packageName"),
        )

        assertTrue(
            "the self package must be filtered out of config.excludedApps",
            source.contains("filterNot { it == selfPackage }"),
        )
    }

    /**
     * T-NEW-73 (b) — user-added exclusions must still be applied.
     *
     * The fix must skip ONLY the app's own package. If it ever degenerated into
     * "stop applying exclusions", a user who excluded an app from the tunnel
     * would silently start leaking that app's traffic into the VPN.
     */
    @Test
    fun `T-NEW-73 user exclusions are still applied by the softether builder`() {
        val source = softEtherService()

        assertTrue(
            "buildTun must still iterate the user's exclusion list",
            source.contains("config.excludedApps"),
        )

        assertTrue(
            "the remaining (non-self) packages must still reach the builder",
            source.contains("addDisallowedApplication(excludedPackage)"),
        )

        assertTrue(
            "a missing package must still be tolerated, not fatal",
            source.contains("PackageManager.NameNotFoundException"),
        )
    }

    // ------------------------------------------------------- T-NEW-73 (c)

    /**
     * T-NEW-73 (c) — the MS-SSTP path needs the same treatment.
     *
     * This is the path the device actually exercised: the tunnel that produced
     * `Binding socket to network 109 failed: EPERM` was MS-SSTP, and its
     * exclusion list comes from the `ROUTE_EXCLUDED_APPS` preference rather
     * than from `ConnectionConfig`. Fixing only the SoftEther builder would
     * leave the observed failure untouched.
     */
    @Test
    fun `T-NEW-73 sstp builder also skips the app's own package`() {
        val source = sstpTerminal()

        assertTrue(
            "IPTerminal must know the app's own package to skip it",
            source.contains("bridge.service.packageName"),
        )

        assertTrue(
            "IPTerminal must filter the self package out of bridge.excludedApps",
            source.contains("filterNot { it == selfPackage }"),
        )

        assertTrue(
            "user-added exclusions must still be applied on the SSTP path",
            source.contains("bridge.builder.addDisallowedApplication(it)"),
        )
    }

    /**
     * T-NEW-73 (d) — provenance.
     *
     * Both call sites must carry the revision marker, so a future reader can
     * tell a deliberate skip from an accidental omission.
     */
    @Test
    fun `T-NEW-73 both call sites record the v1_0_47 provenance`() {
        for ((label, source) in listOf(
            "SoftEtherVpnService.kt" to softEtherService(),
            "IPTerminal.kt" to sstpTerminal(),
        )) {
            assertTrue(
                "$label must record why the self package is skipped (v1.0.47 / §1.6.40)",
                source.contains("v1.0.47") && source.contains("§1.6.40"),
            )
        }
    }
}
