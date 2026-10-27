package vn.unlimit.vpngate.utils

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REQ-DNS-001 (v1.0.70) — Custom DNS is **OFF** by default.
 *
 * Every consumer reads `USE_CUSTOM_DNS` with a `false` default, so a fresh
 * install (or an install with the key missing) takes the default-DNS path. A
 * saved custom configuration must not be applied while the switch is off — the
 * `when` branches select custom DNS only when the value is `true`.
 *
 * Source-shape test: the consumers need an Android `Context`/`SharedPreferences`,
 * and this module's unit tests are plain JVM (JUnit only).
 */
class CustomDnsDefaultTest {

    private fun sourceOf(relativePath: String): String {
        for (path in listOf(relativePath, "../$relativePath", "app/$relativePath")) {
            val file = File(path)
            if (file.isFile) return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
        }
        error("source file not found: $relativePath (cwd=${File(".").absolutePath})")
    }

    @Test
    fun `every consumer defaults USE_CUSTOM_DNS to false`() {
        val consumers = listOf(
            "src/main/java/vn/unlimit/vpngate/activities/DetailActivity.kt",
            "src/main/java/vn/unlimit/vpngate/automode/AndroidConnectionAdapter.kt",
            "src/main/java/vn/unlimit/vpngate/ui/screens/status/StatusViewModel.kt",
            "src/main/java/vn/unlimit/vpngate/ui/screens/settings/SettingsScreen.kt",
        )
        for (relative in consumers) {
            val text = sourceOf(relative)
            assertTrue(
                "$relative must read USE_CUSTOM_DNS with a false default",
                text.contains("USE_CUSTOM_DNS, false"),
            )
            assertFalse(
                "$relative must not default USE_CUSTOM_DNS to true",
                text.contains("USE_CUSTOM_DNS, true"),
            )
        }
    }
}
