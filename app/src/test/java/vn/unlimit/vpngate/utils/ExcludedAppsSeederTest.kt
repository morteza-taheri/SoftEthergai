package vn.unlimit.vpngate.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.models.ExcludedApp

/**
 * v1.0.54 (§1.6.50) PART 5 — [ExcludedAppsSeeder], the one-shot first-run seed
 * that fills Excluded Apps from `assets/default_excluded_apps`.
 *
 * Pure JVM. The seeder's core takes lambdas for every Android-facing decision
 * ("is the flag set", "is this installed", "insert this row"), so all four
 * rules can be proven here without Robolectric, Room or a device — which is the
 * point, because the device is unavailable while this ships.
 *
 * The rules under test:
 *  1. installed-only — a seed entry that is not on the device is skipped;
 *  2. once per install — a second run is a no-op;
 *  3. never overwrite the user's name;
 *  4. no candidates, no flag — an empty asset must not disable a later build.
 */
class ExcludedAppsSeederTest {

    /** Minimal recording harness: stands in for the flag, the PM and the DAO. */
    private class Harness(
        var seeded: Boolean = false,
        val installed: MutableSet<String> = mutableSetOf(),
        val existing: MutableSet<String> = mutableSetOf(),
    ) {
        val written = ArrayList<ExcludedApp>()
        var flagWrites = 0

        fun run(candidates: List<String>): ExcludedAppsSeeder.Outcome =
            ExcludedAppsSeeder.seedOnce(
                candidates = candidates,
                isSeeded = { seeded },
                markSeeded = {
                    seeded = true
                    flagWrites++
                },
                isInstalled = { it in installed },
                isAlreadyExcluded = { it in existing },
                insert = {
                    written.add(it)
                    existing.add(it.packageName)
                },
            )
    }

    // ── rule 1: installed only ───────────────────────────────────────────

    @Test
    fun `only installed packages are written`() {
        val h = Harness(installed = mutableSetOf("com.a", "com.c"))
        val outcome = h.run(listOf("com.a", "com.b", "com.c"))

        assertEquals(listOf("com.a", "com.c"), outcome.seeded)
        assertEquals(listOf("com.b"), outcome.skipped)
        assertEquals(listOf("com.a", "com.c"), h.written.map { it.packageName })
    }

    @Test
    fun `a package that is not installed is never written as a phantom row`() {
        val h = Harness(installed = mutableSetOf())
        val outcome = h.run(listOf("com.ghost"))

        assertTrue(h.written.isEmpty())
        assertEquals(listOf("com.ghost"), outcome.skipped)
        assertEquals(0, outcome.total)
    }

    @Test
    fun `the flag is still set when nothing was installed`() {
        // The run happened. If the flag were left clear, installing one of the
        // seed apps next week would silently auto-exclude it — an opt-in the
        // user never made.
        val h = Harness(installed = mutableSetOf())
        val outcome = h.run(listOf("com.ghost"))

        assertTrue(outcome.ran)
        assertTrue(h.seeded)
        assertEquals(1, h.flagWrites)
    }

    // ── rule 2: once per install ─────────────────────────────────────────

    @Test
    fun `a second run does nothing`() {
        val h = Harness(installed = mutableSetOf("com.a"))
        h.run(listOf("com.a"))
        val second = h.run(listOf("com.a"))

        assertFalse(second.ran)
        assertTrue(second.seeded.isEmpty())
        assertEquals(1, h.written.size)
    }

    @Test
    fun `a seeded app the user later removes is not re-added`() {
        val h = Harness(installed = mutableSetOf("com.a"))
        h.run(listOf("com.a"))

        // The user deletes it from the sheet.
        h.existing.remove("com.a")

        val second = h.run(listOf("com.a"))
        assertFalse(second.ran)
        assertTrue("the seed must not fight the user", h.written.size == 1)
    }

    @Test
    fun `an already-seeded install never queries again`() {
        val h = Harness(seeded = true, installed = mutableSetOf("com.a"))
        var installedChecks = 0
        val outcome = ExcludedAppsSeeder.seedOnce(
            candidates = listOf("com.a"),
            isSeeded = { true },
            markSeeded = { h.flagWrites++ },
            isInstalled = {
                installedChecks++
                true
            },
            isAlreadyExcluded = { false },
            insert = { h.written.add(it) },
        )

        assertFalse(outcome.ran)
        assertEquals(0, installedChecks)
        assertEquals(0, h.flagWrites)
    }

    // ── rule 3: never overwrite ──────────────────────────────────────────

    @Test
    fun `an already-excluded package keeps its stored name`() {
        val h = Harness(
            installed = mutableSetOf("com.a"),
            existing = mutableSetOf("com.a"),
        )
        val outcome = h.run(listOf("com.a"))

        assertTrue(h.written.isEmpty())
        assertEquals(listOf("com.a"), outcome.alreadyPresent)
        assertTrue(outcome.seeded.isEmpty())
        // The run still counts as done: there was a candidate and it was
        // considered.
        assertTrue(h.seeded)
    }

    @Test
    fun `the seeded row names itself after its package`() {
        // No labels are shipped with the seed. Using the package name as the
        // label is honest; inventing a display name would be a guess.
        val h = Harness(installed = mutableSetOf("org.example.chat"))
        h.run(listOf("org.example.chat"))

        val row = h.written.single()
        assertEquals("org.example.chat", row.packageName)
        assertEquals("org.example.chat", row.appName)
    }

    // ── rule 4: no candidates, no flag ───────────────────────────────────

    @Test
    fun `an empty seed list does not burn the one-shot flag`() {
        // This is the state the app actually ships in: the asset carries the
        // format and no entries while the package names are still on the
        // operator's phone. If an empty list set the flag, the build that
        // finally carries the names could never seed an existing install.
        val h = Harness(installed = mutableSetOf("com.a"))
        val outcome = h.run(emptyList())

        assertFalse(outcome.ran)
        assertFalse(h.seeded)
        assertEquals(0, h.flagWrites)
        assertTrue(h.written.isEmpty())
    }

    // ── ordering ─────────────────────────────────────────────────────────

    @Test
    fun `seeded rows keep the seed file's order`() {
        val h = Harness(installed = mutableSetOf("com.z", "com.a", "com.m"))
        val outcome = h.run(listOf("com.z", "com.a", "com.m"))
        assertEquals(listOf("com.z", "com.a", "com.m"), outcome.seeded)
    }

    @Test
    fun `the outcome accounts for every candidate exactly once`() {
        val h = Harness(
            installed = mutableSetOf("com.a", "com.c"),
            existing = mutableSetOf("com.c"),
        )
        val outcome = h.run(listOf("com.a", "com.b", "com.c"))

        val all = outcome.seeded + outcome.skipped + outcome.alreadyPresent
        assertEquals(listOf("com.a", "com.b", "com.c").sorted(), all.sorted())
        assertEquals(3, all.size)
    }

    // ── the flag key is a contract with the stored preferences ────────────

    @Test
    fun `the flag key is stable`() {
        // Changing this string silently re-runs the seed for every existing
        // install, re-adding apps the user removed.
        assertEquals("SETTING_EXCLUDED_APPS_SEEDED", ExcludedAppsSeeder.FLAG_KEY)
    }

    @Test
    fun `the asset name matches the packaged file`() {
        assertEquals("default_excluded_apps", DefaultExcludedApps.ASSET_NAME)
    }
}
