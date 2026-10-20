package vn.unlimit.vpngate.automode

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.42 (§1.6.35 / B-4) — T-NEW-45.
 *
 * `ERROR_NO_NETWORK` is a new terminal, so it needs a user-facing sentence. The
 * generic fallback would have shown "Auto Connect failed" for a condition the
 * user can actually fix in two taps, which is the whole reason the terminal
 * exists separately from `ERROR_NO_SERVER`.
 *
 * Both halves of the mapping are asserted, because either one alone is silent:
 * a string resource with no branch renders the generic message, and a branch
 * with no resource does not compile — but only after someone notices. A
 * source-shape test is the right tool here because `AutoModeScreen` is Compose
 * and needs an Android runtime, and the resource table needs `R`, which a plain
 * JVM unit test does not have.
 */
class AutoModeNoNetworkMessageTest {

    private fun sourceOf(relativePath: String): String {
        for (path in listOf(relativePath, "../$relativePath", "app/$relativePath")) {
            val file = File(path)
            if (file.isFile) return file.readText(Charsets.UTF_8)
        }
        error("source file not found: $relativePath (cwd=${File(".").absolutePath})")
    }

    @Test
    fun theNoNetworkTerminalHasItsOwnStringResource() {
        val strings = sourceOf("src/main/res/values/strings.xml")

        val start = strings.indexOf("name=\"auto_mode_error_no_network\"")
        assertTrue(
            "strings.xml must define auto_mode_error_no_network",
            start >= 0,
        )
        val end = strings.indexOf("</string>", start)
        assertTrue("the resource must be a closed <string> element", end > start)
        val body = strings.substring(start, end)

        assertTrue(
            "the message must tell the user what to do, not just that it failed: $body",
            body.contains("Wi-Fi") || body.contains("mobile data"),
        )
    }

    @Test
    fun theNoNetworkTerminalIsMappedToThatResource() {
        val screen = sourceOf(
            "src/main/java/vn/unlimit/vpngate/ui/screens/automode/AutoModeScreen.kt",
        )

        assertTrue(
            "AutoModeScreen must branch on AutoModeController.ERROR_NO_NETWORK",
            screen.contains("AutoModeController.ERROR_NO_NETWORK"),
        )
        assertTrue(
            "the branch must resolve to R.string.auto_mode_error_no_network",
            screen.contains("R.string.auto_mode_error_no_network"),
        )
    }

    @Test
    fun theTerminalConstantIsStable() {
        // The value is persisted in logs and asserted by AutoModeNetworkGuardTest,
        // so a rename would silently change what a device log means.
        assertTrue(
            "the terminal id must stay \"no_network\"",
            AutoModeController.ERROR_NO_NETWORK == "no_network",
        )
    }
}
