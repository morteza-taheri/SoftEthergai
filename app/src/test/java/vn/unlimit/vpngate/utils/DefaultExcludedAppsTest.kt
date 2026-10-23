package vn.unlimit.vpngate.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.54 (§1.6.50) PART 5 — [DefaultExcludedApps.parse], the rules that turn
 * `assets/default_excluded_apps` into package names.
 *
 * Pure JVM: text in, list out. The asset read itself is a one-line
 * `assets.open(...)` and is not worth a Robolectric test; the *rules* are what
 * can silently break, so they are what this class pins.
 */
class DefaultExcludedAppsTest {

    @Test
    fun `a plain list is returned in order`() {
        val text = "com.a\ncom.b\ncom.c\n"
        assertEquals(listOf("com.a", "com.b", "com.c"), DefaultExcludedApps.parse(text))
    }

    @Test
    fun `blank lines are ignored`() {
        val text = "\n\ncom.a\n\n   \ncom.b\n\n"
        assertEquals(listOf("com.a", "com.b"), DefaultExcludedApps.parse(text))
    }

    @Test
    fun `full-line comments are ignored`() {
        val text = "# header\ncom.a\n# why this one\ncom.b\n"
        assertEquals(listOf("com.a", "com.b"), DefaultExcludedApps.parse(text))
    }

    @Test
    fun `a trailing comment on an entry is stripped`() {
        // Lets the operator annotate an entry in place instead of maintaining a
        // separate comment block that drifts out of sync.
        val text = "com.a   # the chat app\ncom.b\t# the news app\n"
        assertEquals(listOf("com.a", "com.b"), DefaultExcludedApps.parse(text))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals(listOf("com.a"), DefaultExcludedApps.parse("   com.a   \n"))
    }

    @Test
    fun `duplicates collapse to the first occurrence`() {
        assertEquals(listOf("com.a", "com.b"), DefaultExcludedApps.parse("com.a\ncom.b\ncom.a\n"))
    }

    @Test
    fun `crlf line endings are handled`() {
        assertEquals(listOf("com.a", "com.b"), DefaultExcludedApps.parse("com.a\r\ncom.b\r\n"))
    }

    @Test
    fun `a BOM does not become part of the first package name`() {
        // A BOM left on the first line would produce a package name that never
        // matches anything, so the entry would silently seed nothing.
        assertEquals(listOf("com.a"), DefaultExcludedApps.parse("\uFEFFcom.a\n"))
    }

    @Test
    fun `the shipped asset carries no entries yet`() {
        // The seed ships with its format and no package names, because the real
        // list is on the operator's device (report DEFERRED item D1). This test
        // is the reminder: when the names are added, this expectation changes
        // with them, deliberately, in one place.
        val shipped = java.io.File("src/main/assets/default_excluded_apps")
        assertTrue("asset not found at ${shipped.absolutePath}", shipped.isFile)
        val parsed = DefaultExcludedApps.parse(shipped.readText(Charsets.UTF_8))
        assertEquals(emptyList<String>(), parsed)
    }

    @Test
    fun `the shipped asset documents its own format`() {
        val shipped = java.io.File("src/main/assets/default_excluded_apps")
        val text = shipped.readText(Charsets.UTF_8)
        assertTrue("the asset must explain the format", text.contains("package name per line"))
        assertTrue("the asset must explain the skip rule", text.contains("SKIPPED"))
        assertTrue("the asset must point at the deferred extraction", text.contains("D1"))
    }

    @Test
    fun `an empty string yields an empty list`() {
        assertEquals(emptyList<String>(), DefaultExcludedApps.parse(""))
    }

    @Test
    fun `a comment-only file yields an empty list`() {
        assertEquals(emptyList<String>(), DefaultExcludedApps.parse("# a\n# b\n"))
    }
}
