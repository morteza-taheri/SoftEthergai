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

    // ------------------------------------------------------- T-NEW-73 (e)

    /**
     * T-NEW-73 (e) — v1.0.88: the **third** application point, OpenVPN.
     *
     * v1.0.47 fixed SoftEther and MS-SSTP but not `vpnLib`, which applies the
     * same list in `OpenVPNService.newVPNBuilder`. Device evidence,
     * 2026-11-25: with an OpenVPN tunnel up, the VPN's `TransportInfo` listed
     * UIDs `…-10342, 10345-…` — the app's own UID 10344 was missing — and every
     * collector request bound to that network failed with `Connection reset`,
     * so the server list could not be refreshed over OpenVPN while it
     * refreshed normally over MS-SSTP.
     */
    @Test
    fun `T-NEW-73 openvpn builder also skips the app's own package`() {
        val source = sourceOf("vpnLib/src/main/java/de/blinkt/openvpn/core/OpenVPNService.java")

        assertTrue(
            "OpenVPNService must compare the excluded package with its own package name",
            source.contains("pkg.equals(getPackageName())"),
        )
        assertTrue(
            "user-added exclusions must still reach the builder on the OpenVPN path",
            source.contains("builder.addDisallowedApplication(pkg)"),
        )
        assertTrue(
            "the skip must be documented so a future reader sees it as deliberate",
            source.contains("v1.0.88"),
        )
    }

    /**
     * T-NEW-73 (f) — v1.0.88: the row itself is gone, and stays gone.
     *
     * Filtering at the builders is the belt; not storing the row is the braces.
     * The seeding call is what created the condition in the first place, and it
     * runs on every start, so it would re-create a row that a user deleted.
     */
    @Test
    fun `T-NEW-73 the app no longer seeds itself into excluded_apps`() {
        val app = sourceOf("app/src/main/java/vn/unlimit/vpngate/App.kt")

        assertFalse(
            "App must not insert itself into excluded_apps",
            app.contains("insertExcludedApp(selfPackage)"),
        )
        assertTrue(
            "existing installs already have the row, so it must be deleted on start",
            app.contains("deleteByPackageName(packageName)"),
        )
    }

    /**
     * T-NEW-73 (g) — the two save paths must not put it back.
     *
     * `ExcludedAppsSheet` writes the table itself and `ExcludeAppsManager`
     * writes it for the non-Compose path; both used to force-add self after the
     * user's selection. Either one alone would resurrect the defect.
     */
    @Test
    fun `T-NEW-73 neither save path force-adds the app itself`() {
        for ((label, source) in listOf(
            "ExcludedAppsSheet.kt" to
                sourceOf("app/src/main/java/vn/unlimit/vpngate/ui/screens/settings/ExcludedAppsSheet.kt"),
            "ExcludeAppsManager.kt" to
                sourceOf("app/src/main/java/vn/unlimit/vpngate/utils/ExcludeAppsManager.kt"),
        )) {
            assertFalse(
                "$label must not add a \"Self\" row after saving",
                source.contains("ExcludedApp(context.packageName, \"Self\")") ||
                    source.contains("ExcludedApp(selfPackageName, \"Self\")"),
            )
        }
    }
}
