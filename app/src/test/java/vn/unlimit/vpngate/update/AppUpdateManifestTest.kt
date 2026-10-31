package vn.unlimit.vpngate.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BLOCK v1.0.76 — the pure update-check vocabulary.
 *
 * Fail-closed parsing: a hostile, truncated or oversized manifest body can
 * never produce a decision other than `Unknown`, so nothing in the app can be
 * tricked into opening a foreign URL or announcing a bogus version.
 */
class AppUpdateManifestTest {

    private val valid = """
        {
          "schema_version": "1.0",
          "versionCode": 146,
          "versionName": "2.4.13",
          "releasePageUrl": "https://github.com/morteza-taheri/SoftEthergai/releases/latest",
          "notes": "Auto update checker"
        }
    """.trimIndent()

    // ---------------- parsing: happy path ----------------

    @Test
    fun `a valid manifest parses`() {
        val m = AppUpdateCheck.parseManifest(valid)
        assertEquals(146, m?.versionCode)
        assertEquals("2.4.13", m?.versionName)
        assertEquals(
            "https://github.com/morteza-taheri/SoftEthergai/releases/latest",
            m?.releasePageUrl,
        )
        assertEquals("Auto update checker", m?.notes)
    }

    @Test
    fun `parsing tolerates whitespace and field order`() {
        val m = AppUpdateCheck.parseManifest(
            """{"notes":"n",   "versionCode" : 7 ,  "versionName":"x", "releasePageUrl":"https://github.com/morteza-taheri/SoftEthergai/releases"}""",
        )
        assertEquals(7, m?.versionCode)
        assertEquals("x", m?.versionName)
        assertEquals("https://github.com/morteza-taheri/SoftEthergai/releases", m?.releasePageUrl)
    }

    @Test
    fun `a release page on a foreign host is refused (audit fix)`() {
        assertNull(
            AppUpdateCheck.parseManifest(
                """{"versionCode":5,"versionName":"x","releasePageUrl":"https://evil.example.com/a.b/c"}""",
            ),
        )
        assertNull(
            AppUpdateCheck.parseManifest(
                """{"versionCode":5,"versionName":"x","releasePageUrl":"https://github.com/other-project/releases"}""",
            ),
        )
    }

    @Test
    fun `a same-owner lookalike repository is refused (path boundary)`() {
        // BLOCK v1.0.77: `/morteza-taheri/SoftEthergaiXYZ` is a DIFFERENT
        // repository owned by the same account. A bare prefix check accepted it.
        assertNull(
            AppUpdateCheck.parseManifest(
                """{"versionCode":5,"versionName":"x","releasePageUrl":"https://github.com/morteza-taheri/SoftEthergaiXYZ/releases"}""",
            ),
        )
        // The real repository, with and without a trailing path, is accepted.
        assertEquals(
            "https://github.com/morteza-taheri/SoftEthergai",
            AppUpdateCheck.parseManifest(
                """{"versionCode":5,"versionName":"x","releasePageUrl":"https://github.com/morteza-taheri/SoftEthergai"}""",
            )?.releasePageUrl,
        )
        assertEquals(
            "https://raw.githubusercontent.com/morteza-taheri/SoftEthergai/main/app-update/version.json",
            AppUpdateCheck.parseManifest(
                """{"versionCode":5,"versionName":"x","releasePageUrl":"https://raw.githubusercontent.com/morteza-taheri/SoftEthergai/main/app-update/version.json"}""",
            )?.releasePageUrl,
        )
    }

    // ---------------- parsing: fail-closed ----------------

    @Test
    fun `null or blank bodies parse to null`() {
        assertNull(AppUpdateCheck.parseManifest(null))
        assertNull(AppUpdateCheck.parseManifest(""))
        assertNull(AppUpdateCheck.parseManifest("   "))
    }

    @Test
    fun `a body over the size cap is refused`() {
        val huge = "{\"versionCode\":1,\"pad\":\"" + "x".repeat(AppUpdateManifest.MAX_MANIFEST_BYTES + 1) + "\"}"
        assertNull(AppUpdateCheck.parseManifest(huge))
    }

    @Test
    fun `missing or malformed fields parse to null`() {
        assertNull("no versionCode", AppUpdateCheck.parseManifest("""{"versionName":"x"}"""))
        assertNull("versionCode not a number", AppUpdateCheck.parseManifest("""{"versionCode":"x"}"""))
        assertNull("versionCode zero", AppUpdateCheck.parseManifest("""{"versionCode":0}"""))
        assertNull("versionCode negative", AppUpdateCheck.parseManifest("""{"versionCode":-3}"""))
        assertNull("no versionName", AppUpdateCheck.parseManifest("""{"versionCode":5}"""))
        assertNull("no url", AppUpdateCheck.parseManifest("""{"versionCode":5,"versionName":"x"}"""))
        assertNull(
            "url must be https",
            AppUpdateCheck.parseManifest(
                """{"versionCode":5,"versionName":"x","releasePageUrl":"http://a.b/c"}""",
            ),
        )
        assertNull("not json at all", AppUpdateCheck.parseManifest("<html>gateway error</html>"))
    }

    @Test
    fun `a non-https release url is refused`() {
        assertNull(
            AppUpdateCheck.parseManifest(
                """{"versionCode":5,"versionName":"x","releasePageUrl":"ftp://a.b/c"}""",
            ),
        )
    }

    // ---------------- decide ----------------

    @Test
    fun `a newer remote version is UpdateAvailable`() {
        val m = AppUpdateCheck.parseManifest(valid)!!
        val d = AppUpdateCheck.decide(m, installedVersionCode = 145)
        assertTrue(d is AppUpdateDecision.UpdateAvailable)
        assertEquals(146, (d as AppUpdateDecision.UpdateAvailable).manifest.versionCode)
    }

    @Test
    fun `the same or an older remote version is UpToDate`() {
        val m = AppUpdateCheck.parseManifest(valid)!!
        assertTrue(AppUpdateCheck.decide(m, 146) is AppUpdateDecision.UpToDate)
        assertTrue(AppUpdateCheck.decide(m, 200) is AppUpdateDecision.UpToDate)
    }

    @Test
    fun `a null manifest is Unknown and never navigates`() {
        val d = AppUpdateCheck.decide(null, 145)
        assertTrue(d is AppUpdateDecision.Unknown)
    }

    // ---------------- weekly scheduling ----------------

    @Test
    fun `never-checked means check now`() {
        assertTrue(AppUpdateCheck.shouldAutoCheck(lastCheckAtMs = 0, nowMs = 1_000))
        assertTrue(AppUpdateCheck.shouldAutoCheck(lastCheckAtMs = -5, nowMs = 1_000))
    }

    @Test
    fun `a check within the week is skipped and an old one is due`() {
        val now = 10_000_000_000L
        val week = AppUpdateCheck.WEEKLY_INTERVAL_MS
        assertFalse(
            "6 days ago -> not due",
            AppUpdateCheck.shouldAutoCheck(lastCheckAtMs = now - week + 86_400_000L, nowMs = now),
        )
        assertTrue(
            "8 days ago -> due",
            AppUpdateCheck.shouldAutoCheck(lastCheckAtMs = now - week - 86_400_000L, nowMs = now),
        )
        assertTrue("exactly at the interval -> due", AppUpdateCheck.shouldAutoCheck(now - week, now))
    }
}
