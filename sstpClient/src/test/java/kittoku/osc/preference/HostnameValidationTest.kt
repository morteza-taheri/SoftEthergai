package kittoku.osc.preference

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SEC-F15 (deep-review refresh, 2026-11-05): HOME_HOSTNAME and PROXY_HOSTNAME
 * are interpolated verbatim into an SSTP `Host:` header and a proxy `CONNECT`
 * request line. Control characters / delimiters must be rejected, and the
 * proxy branch must validate the *proxy* field (it used to re-check HOME).
 */
class HostnameValidationTest {

    @Test
    fun `ordinary DNS names and IPv4 literals are accepted`() {
        listOf("vpn.example.com", "opengw.net", "192.0.2.10", "a-b.c_d.example")
            .forEach { assertTrue("valid host must be accepted: $it", isValidHostname(it)) }
    }

    @Test
    fun `header injection characters are rejected`() {
        listOf(
            "vpn.example.com\r\nX-Injected: 1",
            "vpn.example.com\nHost: evil",
            "vpn.example.com:443",   // colon belongs in the port pref, not the host
            "vpn example.com",
            "vpn\texample.com",
            "user@vpn.example.com",
            "vpn/example.com",
            "",
            ".leading.example",
            "trailing.example.",
            "a".repeat(254),
        ).forEach { assertFalse("invalid host must be rejected: $it", isValidHostname(it)) }
    }

    @Test
    fun `checkPreferences validates the proxy hostname field, not HOME_HOSTNAME`() {
        val source = sourceOf("sstpClient/src/main/java/kittoku/osc/preference/check.kt")
        val branch = source.indexOf("PROXY_DO_USE_PROXY")
        assertTrue(branch >= 0)
        val segment = source.substring(branch, minOf(branch + 900, source.length))
        assertTrue(
            "the proxy branch must read PROXY_HOSTNAME",
            segment.contains("getStringPrefValue(OscPrefKey.PROXY_HOSTNAME, prefs)"),
        )
        assertTrue(
            "the proxy branch must validate the hostname it read",
            segment.contains("isValidHostname(it)"),
        )
    }

    private fun sourceOf(relative: String): String {
        for (path in listOf(relative, "../$relative", "../../$relative")) {
            val file = File(path)
            if (file.isFile) return file.readText(Charsets.UTF_8)
        }
        error("source file not found: $relative (cwd=${File(".").absolutePath})")
    }
}
