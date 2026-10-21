package vn.unlimit.vpngate.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-NEW-78..80 — source-shape regression for v1.0.49 (§1.6.42)
 * EMERGENCY-LADDER-WIRING.
 *
 * ## Why a source-shape test and not a behavioural one
 *
 * The defect lives in `VpnServerRepository.refreshLocked`, which needs a fully
 * wired repository (a real `App.instance`, `GlobalVpnTracker`, bound/unbound
 * `HttpFetcher`s, the Room DAOs) and, on the emergency rung, a real network
 * fetch through `EmergencySourceFetcher`. This module's unit tests run on a
 * plain JVM (no Robolectric, no instrumentation): `GlobalVpnTracker` cannot
 * initialise here, and `emergencyRecords()` performs an HTTP GET, so neither
 * `refreshLocked` nor the emergency fetch can be driven end-to-end in this
 * harness. The contract is therefore pinned textually — the same approach
 * `SelfExclusionRemovedTest` and `AutoModeControllerTest` already use.
 *
 * ## What is actually being protected
 *
 * The operator's original requirement: "if the physical path is blocked,
 * update through the app's own tunnel; if the tunnel also fails, use
 * servers.csv." The ladder is `PHYSICAL -> TUNNEL -> EMERGENCY`, but three
 * things had to change for the EMERGENCY rung to be reachable:
 *
 *  1. `hasEmergencySources()` was a stub returning `false` (v1.0.45), so the
 *     ladder logged `skip=EMERGENCY (none configured)` on every refresh even
 *     when the v1.0.46 seeded default emergency source existed. It now
 *     consults the `emergency_source_config` DAO.
 *  2. Even when configured, the rung was never attempted by `refreshLocked`:
 *     on primary failure it returned the last-known-good snapshot (non-null),
 *     which intercepted the fallthrough, so `ServerListUpdater`'s
 *     `emergencyFetch` — the only other place that reached `emergencyDataset()`
 *     — was almost never invoked. `refreshLocked` now attempts the rung itself.
 *  3. The fetch is the existing v1.0.21 path (`EmergencySourceFetcher` +
 *     `EmergencyCsvParser`), reused — never duplicated.
 *
 * These tests pin exactly that triad of properties.
 */
class EmergencyLadderWiringTest {

    // ---------------------------------------------------------------- paths

    private fun read(relativePath: String): String {
        for (candidate in listOf(
            relativePath,
            "app/$relativePath",
            "../$relativePath",
            "../../$relativePath",
            "../../../$relativePath",
        )) {
            val file = File(candidate)
            if (file.isFile) return file.readText(Charsets.UTF_8)
        }
        error("source file not found: $relativePath (cwd=${File(".").absolutePath})")
    }

    private fun repository(): String =
        read("src/main/java/vn/unlimit/vpngate/repository/VpnServerRepository.kt")

    private fun updates(): String =
        read("src/main/java/vn/unlimit/vpngate/repository/ServerListUpdates.kt")

    // ------------------------------------------------------- T-NEW-78

    /**
     * T-NEW-78 — with zero enabled emergency sources the ladder still logs the
     * "none configured" skip and does NOT attempt a fetch. The pre-fix behaviour
     * (a correct outcome for the no-source case) must be preserved; what changes
     * is only that the decision is now real instead of a hardcoded `false`.
     */
    @Test
    fun `T-NEW-78 no enabled source keeps the EMERGENCY skip and skips the fetch`() {
        val repo = repository()

        assertTrue(
            "the 'none configured' skip must remain in the ladder",
            repo.contains("skip=EMERGENCY (none configured)"),
        )

        assertTrue(
            "the EMERGENCY branch must be gated by hasEmergencySources()",
            repo.contains("if (hasEmergencySources())"),
        )

        assertTrue(
            "when sources ARE configured the rung must attempt the fetch",
            repo.contains("ServerListUpdates.emergencyRecords()"),
        )

        assertTrue(
            "the real decision must consult the DAO, not a constant",
            repo.contains("emergencySourceConfigDao") && repo.contains("listEnabled()"),
        )
    }

    // ------------------------------------------------------- T-NEW-79

    /**
     * T-NEW-79 — with an enabled source the ladder no longer unconditionally
     * skips: the EMERGENCY branch now reaches the v1.0.21 fetch path, and the
     * seam `emergencyRecords()` is exposed by `ServerListUpdates`.
     */
    @Test
    fun `T-NEW-79 an enabled source wires the EMERGENCY rung to the v1_0_21 fetch path`() {
        val repo = repository()
        val updates = updates()

        assertTrue(
            "the ladder must call the emergency fetch seam",
            repo.contains("ServerListUpdates.emergencyRecords()"),
        )

        assertTrue(
            "the rung must report that it attempted the emergency path",
            repo.contains("[ladder] path=emergency source="),
        )

        assertTrue(
            "ServerListUpdates must expose the records-returning seam",
            updates.contains("fun emergencyRecords()"),
        )

        assertTrue(
            "the seam must be module-visible so the ladder can call it without duplication",
            updates.contains("internal suspend fun emergencyRecords()"),
        )
    }

    // ------------------------------------------------------- T-NEW-80

    /**
     * T-NEW-80 — the EMERGENCY rung, when attempted, folds its records into the
     * SAME `collected` pipeline as PHYSICAL/TUNNEL, so the data still passes
     * through `DatasetIntegrity` on write (no privileged path), and the fetch
     * logic is the shared v1.0.21 path (not a second copy).
     */
    @Test
    fun `T-NEW-80 emergency records flow through the integrity-gated pipeline and are not duplicated`() {
        val repo = repository()
        val updates = updates()

        assertTrue(
            "the emergency records must be added to the shared collected list",
            repo.contains("records.addAll(emergency)"),
        )

        assertTrue(
            "the shared collected list is what merge/validate/score consume next",
            repo.contains("VpnServerMerger.mergeRecords(records)"),
        )

        // No duplication: the v1.0.21 fetch logic (EmergencySourceFetcher
        // instantiation) appears exactly once in ServerListUpdates — inside
        // emergencyRecords(). emergencyDataset() must delegate, not re-implement.
        val fetcherCalls = updates.split("EmergencySourceFetcher()").size - 1
        assertEquals(
            "EmergencySourceFetcher must be instantiated exactly once (in emergencyRecords)",
            fetcherCalls,
            1,
        )

        assertTrue(
            "emergencyDataset must delegate to emergencyRecords rather than re-fetch",
            updates.contains("private suspend fun emergencyDataset()") &&
                updates.contains("val valid = emergencyRecords() ?: return null"),
        )
    }
}
