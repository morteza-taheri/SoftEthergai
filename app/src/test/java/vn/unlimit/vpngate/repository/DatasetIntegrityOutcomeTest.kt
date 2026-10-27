package vn.unlimit.vpngate.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.models.VPNGateItem

/**
 * Z1-IMPL-G (Roadmap §12.5.2 item G): the three acceptance outcomes named by
 * `SERVER_SOURCE_ARCHITECTURE.md` §9.6 and §10.8.
 *
 * The outcomes are a **classification layered on the existing verdict**, not a
 * new decision — §9.6's own words are that they "layer on top of the existing
 * `DatasetIntegrity` threshold logic rather than replacing it". Every case below
 * therefore asserts two things: the outcome name, and that `evaluate`'s accept /
 * reject decision is the one the outcome describes. If a future change moves a
 * threshold, these tests are where the outcome names stop matching the
 * behaviour.
 *
 * The two cases the phase brief got wrong are marked. `SERVER_SOURCE_ARCHITECTURE.md`
 * §9.1 is explicit that "a shrunken response is treated as `MERGE_WITH_EXISTING`,
 * **never** as a signal to prune previously-seen healthy records", so a smaller —
 * but still viable — dataset is a merge, not a rejection. What is a rejection is
 * the dataset that collapses below the existing floor, which is what
 * `SIZE_COLLAPSE` has always meant.
 */
class DatasetIntegrityOutcomeTest {

    private fun row(name: String, sstpPort: Int = 0): VPNGateItem = VPNGateItem(
        hostName = name,
        ip = "203.0.113.${name.hashCode().mod(200) + 1}",
        countryLong = null,
        countryShort = null,
        logType = null,
        operator = null,
        message = null,
        openVpnConfigData = null,
    ).copy(isSSTPSupport = sstpPort > 0, sstpPort = sstpPort)

    /** A dataset comfortably above every threshold. */
    private fun healthy(prefix: String, size: Int): List<VPNGateItem> =
        (1..size).map { row("$prefix-$it.example.com", sstpPort = 443) }

    private fun verdictFor(
        candidate: List<VPNGateItem>,
        stored: List<VPNGateItem>,
    ) = DatasetIntegrity.evaluate(
        candidate, stored, SourceCapability.FULL,
        lastAcceptedSize = stored.size, strictSizeBaseline = true,
    )

    private fun outcomeFor(
        candidate: List<VPNGateItem>,
        stored: List<VPNGateItem>,
    ): DatasetOutcome {
        val verdict = verdictFor(candidate, stored)
        return DatasetIntegrity.outcome(verdict, stored)
    }

    // ------------------------------------------------------------- ACCEPT_NEW

    @Test
    fun `an empty stored dataset makes an acceptable candidate ACCEPT_NEW`() {
        val candidate = healthy("new", 20)

        val verdict = verdictFor(candidate, emptyList())

        assertTrue(verdict.accepted)
        assertEquals(
            DatasetOutcome.ACCEPT_NEW,
            DatasetIntegrity.outcome(verdict, emptyList()),
        )
    }

    @Test
    fun `a stored remnant too small to be a baseline is still ACCEPT_NEW`() {
        // Nine stored rows are below MIN_ACCEPTABLE_DATASET_SIZE, so nothing
        // is compared against them: the floor and the structural checks are
        // all that apply, exactly as before item G.
        val candidate = healthy("new", 20)
        val stored = healthy("old", 9)

        val verdict = verdictFor(candidate, stored)

        assertTrue(verdict.accepted)
        assertEquals(
            DatasetOutcome.ACCEPT_NEW,
            DatasetIntegrity.outcome(verdict, stored),
        )
    }

    // ------------------------------------------------- MERGE_WITH_EXISTING

    @Test
    fun `an accepted candidate over a comparable stored dataset is MERGE_WITH_EXISTING`() {
        val stored = healthy("old", 30)
        val candidate = healthy("new", 40)

        val verdict = verdictFor(candidate, stored)

        assertTrue(verdict.accepted)
        assertEquals(
            DatasetOutcome.MERGE_WITH_EXISTING,
            DatasetIntegrity.outcome(verdict, stored),
        )
    }

    @Test
    fun `an accepted candidate the same size as the stored one is MERGE_WITH_EXISTING`() {
        val stored = healthy("old", 30)
        val candidate = healthy("new", 30)

        val verdict = verdictFor(candidate, stored)

        assertTrue(verdict.accepted)
        assertEquals(
            DatasetOutcome.MERGE_WITH_EXISTING,
            DatasetIntegrity.outcome(verdict, stored),
        )
    }

    @Test
    fun `a shrunken but still viable dataset is MERGE_WITH_EXISTING, not REJECT`() {
        // The case the phase brief classified as REJECT_DEGRADED.
        // `SERVER_SOURCE_ARCHITECTURE.md` §9.1: "Do not lose healthy servers
        // when a source returns a smaller set. A shrunken response is treated
        // as MERGE_WITH_EXISTING (§9.6), never as a signal to prune
        // previously-seen healthy records." 25 of 40 stored is above the 0.5
        // size floor, so the existing thresholds accept it and the outcome is
        // a merge.
        val stored = healthy("old", 40)
        val candidate = healthy("new", 25)

        val verdict = verdictFor(candidate, stored)

        assertTrue("a viable smaller dataset must still be accepted", verdict.accepted)
        assertEquals(
            DatasetOutcome.MERGE_WITH_EXISTING,
            DatasetIntegrity.outcome(verdict, stored),
        )
    }

    @Test
    fun `a disjoint dataset of a viable size is MERGE_WITH_EXISTING`() {
        // Also brief-REJECT in the phase brief, and also a merge in the arch
        // doc: the candidate shares no identity with the stored list but is
        // large enough to stand on its own, so the existing thresholds accept
        // it. Whether its records should then be *added* rather than
        // substituted is Cumulative Server Retention (§9.1), not this class.
        val stored = healthy("old", 30)
        val candidate = healthy("new", 30)

        val verdict = verdictFor(candidate, stored)

        assertTrue(verdict.accepted)
        assertTrue(candidate.none { c -> stored.any { it.hostName == c.hostName } })
        assertEquals(
            DatasetOutcome.MERGE_WITH_EXISTING,
            DatasetIntegrity.outcome(verdict, stored),
        )
    }

    // -------------------------------------------------------- REJECT_DEGRADED

    @Test
    fun `a dataset that collapses below the size floor is REJECT_DEGRADED`() {
        // 10 of 40 stored is half the floor, so SIZE_COLLAPSE fires. This is
        // the genuine degradation — the case §9.6's REJECT_DEGRADED names.
        val stored = healthy("old", 40)
        val candidate = healthy("new", 10)

        val verdict = verdictFor(candidate, stored)

        assertTrue(!verdict.accepted)
        assertEquals(DatasetDefect.SIZE_COLLAPSE, verdict.defect)
        assertEquals(
            DatasetOutcome.REJECT_DEGRADED,
            DatasetIntegrity.outcome(verdict, stored),
        )
    }

    @Test
    fun `a dataset below the acceptable floor is REJECT_DEGRADED`() {
        // Too small to be a dataset at all, which is the other genuine
        // degradation §9.6 names. This is the floor itself, not a collapse
        // against a baseline: `evaluate` refuses a 3-row candidate whatever
        // the store holds.
        val stored = healthy("old", 40)
        val candidate = healthy("new", 3)

        val verdict = verdictFor(candidate, stored)

        assertTrue(!verdict.accepted)
        assertEquals(DatasetDefect.TOO_SMALL, verdict.defect)
        assertEquals(
            DatasetOutcome.REJECT_DEGRADED,
            DatasetIntegrity.outcome(verdict, stored),
        )
    }

    @Test
    fun `a protocol collapse is REJECT_DEGRADED`() {
        // Stored carries 30 SSTP servers; the candidate carries none, which is
        // a PROTOCOL_COLLAPSE rather than a shrunken set.
        val stored = healthy("old", 30)
        val candidate = healthy("new", 30).map { it.copy(isSSTPSupport = false, sstpPort = 0) }

        val verdict = verdictFor(candidate, stored)

        assertTrue(!verdict.accepted)
        assertEquals(DatasetDefect.PROTOCOL_COLLAPSE, verdict.defect)
        assertEquals(
            DatasetOutcome.REJECT_DEGRADED,
            DatasetIntegrity.outcome(verdict, stored),
        )
    }

    // ------------------------------------------------- the classification rule

    @Test
    fun `the outcome can never contradict the verdict`() {
        // The relabelling must be a function of the verdict alone: an accepted
        // verdict is never REJECT_DEGRADED, and a refused one never claims a
        // successful outcome. This is what makes layering the outcomes on top
        // of `evaluate` safe.
        val datasets = listOf(
            healthy("a", 30) to healthy("b", 40),
            healthy("a", 40) to healthy("b", 30),
            healthy("a", 30) to healthy("b", 30),
            healthy("a", 10) to healthy("b", 40),
            healthy("a", 3) to healthy("b", 40),
            healthy("a", 40) to emptyList(),
            healthy("a", 40) to healthy("b", 9),
        )

        for ((candidate, stored) in datasets) {
            val verdict = verdictFor(candidate, stored)
            val outcome = DatasetIntegrity.outcome(verdict, stored)

            if (verdict.accepted) {
                assertNotEquals(DatasetOutcome.REJECT_DEGRADED, outcome)
            } else {
                assertEquals(DatasetOutcome.REJECT_DEGRADED, outcome)
            }
        }
    }

    @Test
    fun `ACCEPT_NEW is exactly the case with no comparable stored baseline`() {
        // 9 stored rows is the remnant; 10 is the smallest baseline that
        // counts. The boundary is the one `evaluate` has always used.
        val candidate = healthy("new", 20)

        assertEquals(
            DatasetOutcome.ACCEPT_NEW,
            outcomeFor(candidate, healthy("old", 9)),
        )
        assertEquals(
            DatasetOutcome.MERGE_WITH_EXISTING,
            outcomeFor(candidate, healthy("old", 10)),
        )
    }
}
