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
 *
 * v1.0.56: the asset is no longer empty. The 12 package names were extracted
 * from the operator's device in BLOCK v1.0.56 PART B and confirmed by the
 * operator. [EXPECTED_D1_ENTRIES] is the single place that expectation lives,
 * so adding or removing an entry is a one-line edit here plus the asset.
 */
class DefaultExcludedAppsTest {

    /**
     * The confirmed D1 list, in the asset's shipped (sorted) order.
     *
     * Extracted from `excluded_apps` on device 5585fecc on 2026-10-24. The
     * operator's device had 15 rows; the three non-defaults
     * (DeepSeek / Android Auto / the app itself) are deliberately excluded and
     * pinned absent by `the shipped asset excludes the three non-default packages`.
     */
    private val EXPECTED_D1_ENTRIES = listOf(
        "app.rbmain.a",
        "cab.snapp.passenger",
        "com.byagowi.persiancalendar",
        "com.farsitel.bazaar",
        "com.myirancell",
        "com.pmb.mobile",
        "com.rahand.qbank",
        "ir.bmi.bam.nativeweb",
        "ir.easytrader.orbis.m.twa",
        "ir.eitaa.messenger",
        "ir.nasim",
        "org.rajman.neshan.traffic.tehran.navigator",
    )

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
    fun `the shipped asset carries the extracted D1 entries`() {
        // v1.0.56: the list was extracted from the operator's device in BLOCK
        // v1.0.56 PART B and confirmed with "CONFIRM D1: ALL CLASSIFIED-IRANIAN".
        // The old expectation ("carries no entries yet") was the reminder that
        // the extraction was pending; it is now deliberately replaced by the
        // real content, in this one place.
        val shipped = java.io.File("src/main/assets/default_excluded_apps")
        assertTrue("asset not found at ${shipped.absolutePath}", shipped.isFile)
        val parsed = DefaultExcludedApps.parse(shipped.readText(Charsets.UTF_8))
        assertEquals(EXPECTED_D1_ENTRIES, parsed)
    }

    @Test
    fun `every shipped entry is a plausible Android package name`() {
        // Android package names are dot-separated Java identifiers. This is a
        // shape check, not Android's full grammar: a malformed name would never
        // match an installed package, so the seeder would skip it silently and
        // the exclusion would be lost without any visible error.
        val shipped = java.io.File("src/main/assets/default_excluded_apps")
        val parsed = DefaultExcludedApps.parse(shipped.readText(Charsets.UTF_8))
        assertTrue("the asset must ship entries", parsed.isNotEmpty())
        val segment = Regex("[A-Za-z_][A-Za-z0-9_]*")
        for (pkg in parsed) {
            val segments = pkg.split(".")
            assertTrue(
                "'$pkg' must have at least two dot-separated segments",
                segments.size >= 2,
            )
            for (seg in segments) {
                assertTrue(
                    "'$pkg' has an invalid segment '$seg'",
                    seg.isNotEmpty() && segment.matches(seg),
                )
            }
        }
    }

    @Test
    fun `the shipped asset contains no duplicate entries`() {
        val shipped = java.io.File("src/main/assets/default_excluded_apps")
        val parsed = DefaultExcludedApps.parse(shipped.readText(Charsets.UTF_8))
        assertEquals(
            "duplicates found: " +
                parsed.groupBy { it }.filter { it.value.size > 1 }.keys,
            parsed.size,
            parsed.toSet().size,
        )
    }

    @Test
    fun `the shipped entries are sorted and carry no comment leakage`() {
        // Sorted keeps the diff small when an entry is added. And a comment
        // marker surviving into an entry would make the name unmatchable.
        val shipped = java.io.File("src/main/assets/default_excluded_apps")
        val parsed = DefaultExcludedApps.parse(shipped.readText(Charsets.UTF_8))
        assertEquals(parsed.sorted(), parsed)
        for (pkg in parsed) {
            assertTrue("'$pkg' contains '#'", !pkg.contains("#"))
            assertTrue("'$pkg' contains whitespace", pkg.none { it.isWhitespace() })
        }
    }

    @Test
    fun `the shipped asset excludes the three non-default packages`() {
        // The operator's device had 15 rows; three must NOT ship to all users:
        // DeepSeek (Chinese, personal), Android Auto (Google, user-added), and
        // the app itself (self-excluded in App.kt). This pins that decision so
        // a future edit cannot quietly re-add them.
        val shipped = java.io.File("src/main/assets/default_excluded_apps")
        val parsed = DefaultExcludedApps.parse(shipped.readText(Charsets.UTF_8)).toSet()
        for (mustNotShip in listOf(
            "com.deepseek.chat",
            "com.google.android.projection.gearhead",
            "vn.unlimit.vpngatepro",
        )) {
            assertTrue(
                "'$mustNotShip' must not ship as a default for all users",
                mustNotShip !in parsed,
            )
        }
    }

    @Test
    fun `the shipped asset documents its own format`() {
        val shipped = java.io.File("src/main/assets/default_excluded_apps")
        val text = shipped.readText(Charsets.UTF_8)
        assertTrue("the asset must explain the format", text.contains("package name per line"))
        assertTrue("the asset must explain the skip rule", text.contains("SKIPPED"))
        assertTrue("the asset must record the D1 provenance", text.contains("D1"))
        assertTrue("the asset must record the confirmation", text.contains("CONFIRM D1"))
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
