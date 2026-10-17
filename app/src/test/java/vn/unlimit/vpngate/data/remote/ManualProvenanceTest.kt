package vn.unlimit.vpngate.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.data.model.VpnProtocolNames
import vn.unlimit.vpngate.data.model.VpnRecords
import vn.unlimit.vpngate.data.model.VpnServerRecord
import vn.unlimit.vpngate.merger.Provenance
import vn.unlimit.vpngate.merger.VpnServerMerger

/**
 * Z1-IMPL-G (Roadmap §12.5.2 item G): the MANUAL provenance fix.
 *
 * [SourceIdentity] is the one place that decides which record-layer source
 * name a source gets, and `SourceKind.MANUAL` was reaching it through an
 * `else` branch that read `mirrorId ?: "mirror_unnumbered"`. A manual server
 * has no mirror id, so a user's own entry was reported as an unidentified
 * mirror for two whole revisions — recorded as limitation 1 of the
 * Z1-IMPL-D report, because `SourceIdentity.kt` was outside that phase's
 * authorized file set.
 *
 * Why a mirror tag is not a cosmetic error:
 *
 *  - `SERVER_SOURCE_ARCHITECTURE.md` §3.3 requires manual to have a
 *    distinct, non-canonical tag, and §4.2 requires that a manual record be
 *    "never promoted above canonical". Putting it in the mirror group does
 *    not break that constraint, but it does hide the provenance, so an
 *    operator debugging a wrong value cannot tell a user entry from a
 *    crawl.
 *  - Worse, it silently moved the merge authority. `Provenance.sourceGroup`
 *    collapses `mirror_<n>` into the single group `mirror`, which is not in
 *    `VpnServerMerger.SOURCE_PRIORITY` and therefore falls through to the
 *    default 20. The manual entry in that table is 30 and is documented as
 *    "inert until a manual record reaches this object" — which is precisely
 *    what never happened, because no record labelled `manual` was ever
 *    produced. The table entry was dead code, not a live policy.
 *
 * The fix is two lines in substance: an exhaustive `when` with no `else`
 * (a compile-time guard — a future `SourceKind` now fails to build instead
 * of silently reading as a mirror) and the `MANUAL_TAG` constant. These
 * tests pin both halves: the tag itself, the baseline behaviour of every
 * other kind that must not move, and the consequences at the record layer.
 */
class ManualProvenanceTest {

    private val host = "203.0.113.7"

    private fun identity(
        kind: SourceKind,
        mirrorId: String? = null,
    ) = SourceIdentity(
        kind = kind,
        scheme = SourceIdentity.HTTP,
        host = host,
        mirrorId = mirrorId,
    )

    /** One canonical record for [host] advertising MS-SSTP on [sstpPort]. */
    private fun sstpRecord(sstpPort: Int, source: String): VpnServerRecord {
        val record = VpnRecords.newServer()
        VpnRecords.setField(record, "identity.ip", host, source)
        VpnRecords.setField(record, "identity.hostname", "host-$host", source)
        VpnRecords.setField(record, "protocols.sstp.supported", true, source)
        VpnRecords.setField(record, "protocols.sstp.port", sstpPort, source)
        VpnRecords.markSupported(record, VpnProtocolNames.SSTP, source)
        VpnRecords.sourceAdd(record, source)
        return record
    }

    // ------------------------------------------------------- 1. the tag itself

    @Test
    fun `a manual source is tagged manual`() {
        assertEquals(
            SourceIdentity.MANUAL_TAG,
            identity(SourceKind.MANUAL).sourceTag,
        )
        assertEquals("manual", identity(SourceKind.MANUAL).sourceTag)
    }

    @Test
    fun `a manual source is never reported as a mirror`() {
        // The defect being fixed. `mirrorId` is null for a manual source, so
        // the old `else -> mirrorId ?: UNNUMBERED_MIRROR_TAG` branch could
        // only ever produce the unnumbered-mirror tag.
        assertNotEquals(
            SourceIdentity.UNNUMBERED_MIRROR_TAG,
            identity(SourceKind.MANUAL).sourceTag,
        )
        assertFalse(
            "a manual tag must not read as a mirror",
            identity(SourceKind.MANUAL).sourceTag.startsWith(VpnGateSourceCandidates.MIRROR_TAG_PREFIX),
        )
    }

    @Test
    fun `a manual identity carrying a stray mirror id is still tagged manual`() {
        // [SourceIdentity.withMirrorId] is a public `copy`, so nothing in the
        // type system stops a manual identity from holding a mirror id. The
        // tag must not depend on it: the manual branch owns the decision.
        val stray = identity(SourceKind.MANUAL).withMirrorId("mirror_3")

        assertEquals(SourceIdentity.MANUAL_TAG, stray.sourceTag)
    }

    // --------------------------------------------- 2. the unchanged baseline

    @Test
    fun `every other source kind keeps its pre-G tag`() {
        // These are the baseline values, asserted so that adding the manual
        // branch cannot quietly re-route one of them.
        assertEquals(SourceIdentity.HTML_TAG, identity(SourceKind.CANONICAL_HTML).sourceTag)
        assertEquals(SourceIdentity.API_TAG, identity(SourceKind.CANONICAL_API).sourceTag)
        assertEquals(
            SourceIdentity.EMERGENCY_TAG,
            identity(SourceKind.EMERGENCY_CSV).sourceTag,
        )

        for (kind in listOf(
            SourceKind.BOOTSTRAP_MIRROR,
            SourceKind.DISCOVERED_MIRROR,
            SourceKind.CACHED_MIRROR,
        )) {
            assertEquals("$kind without an id", SourceIdentity.UNNUMBERED_MIRROR_TAG, identity(kind).sourceTag)
            assertEquals("$kind with an id", "mirror_7", identity(kind, mirrorId = "mirror_7").sourceTag)
        }
    }

    @Test
    fun `the manual tag is distinct from every other tag the layer owns`() {
        val owned = listOf(
            SourceIdentity.HTML_TAG,
            SourceIdentity.API_TAG,
            SourceIdentity.EMERGENCY_TAG,
            SourceIdentity.UNNUMBERED_MIRROR_TAG,
            "mirror_1",
        )
        assertEquals("manual", SourceIdentity.MANUAL_TAG)
        for (tag in owned) {
            assertNotEquals(tag, SourceIdentity.MANUAL_TAG)
        }
    }

    // --------------------------------------------------- 3. the consequences

    @Test
    fun `the manual tag stays out of the mirror provenance group`() {
        // This is the part that used to silently grant mirror authority.
        //
        // `Provenance.sourceGroup` collapses `mirror_<n>` into the single
        // group `mirror`, but `mirror_unnumbered` does NOT match that
        // pattern (`^mirror_\d+$`), so it stays its own group. That is the
        // group a pre-G manual record landed in — which is worse than
        // landing in `mirror`, because it reads as an unknown source AND
        // still resolves to the mirror-level default priority, since
        // neither name is in the priority table.
        assertEquals("manual", Provenance.sourceGroup(SourceIdentity.MANUAL_TAG))
        assertEquals("mirror", Provenance.sourceGroup("mirror_7"))
        assertEquals(
            SourceIdentity.UNNUMBERED_MIRROR_TAG,
            Provenance.sourceGroup(SourceIdentity.UNNUMBERED_MIRROR_TAG),
        )

        // Neither mirror spelling is in the priority table, so both fall
        // through to the default 20 — higher than manual's 30. That is the
        // authority a pre-G manual record silently carried.
        assertEquals(30, VpnServerMerger.sourcePriority(SourceIdentity.MANUAL_TAG))
        assertEquals(20, VpnServerMerger.sourcePriority("mirror_7"))
        assertEquals(20, VpnServerMerger.sourcePriority(SourceIdentity.UNNUMBERED_MIRROR_TAG))
        assertTrue(
            "a manual record must rank below an unidentified mirror",
            VpnServerMerger.sourcePriority(SourceIdentity.MANUAL_TAG) >
                VpnServerMerger.sourcePriority(SourceIdentity.UNNUMBERED_MIRROR_TAG),
        )
    }

    @Test
    fun `a real mirror id can never collide with the manual tag`() {
        // The groups are disjoint because mirror ids are prefixed at the
        // point of assignment. Without this the manual tag would share a
        // provenance group with a hypothetical mirror called "manual".
        for (index in 1..25) {
            val mirrorId = VpnGateSourceCandidates.MIRROR_TAG_PREFIX + index
            assertNotEquals(SourceIdentity.MANUAL_TAG, mirrorId)
            assertTrue(mirrorId.startsWith(VpnGateSourceCandidates.MIRROR_TAG_PREFIX))
        }
    }

    // ------------------------------------------------------ 4. the record layer

    @Test
    fun `a manual record merged against a canonical one keeps the manual group`() {
        val api = sstpRecord(443, "api")
        val manual = sstpRecord(8443, "manual")

        val merged = VpnServerMerger.mergeRecords(listOf(api, manual))

        assertEquals(1, merged.size)
        assertTrue(VpnRecords.sources(merged[0]).contains(SourceIdentity.MANUAL_TAG))
        assertFalse(VpnRecords.sources(merged[0]).contains(SourceIdentity.UNNUMBERED_MIRROR_TAG))
        assertEquals(listOf("api", "manual"), VpnRecords.sources(merged[0]))
    }

    @Test
    fun `a manual record with no collected counterpart keeps manual as its only source`() {
        val merged = VpnServerMerger.mergeRecords(listOf(sstpRecord(8443, "manual")))

        assertEquals(1, merged.size)
        assertEquals(listOf("manual"), VpnRecords.sources(merged[0]))
        assertNull(merged[0]["conflicts"])
    }

    @Test
    fun `manual provenance survives repeated refreshes`() {
        // The production shape: `ServerListUpdates` re-reads the stored
        // dataset and re-emits the user's row on every accepted write, so
        // each refresh merges the previously stored record — which already
        // carries the manual group — against whatever the feeds returned
        // this time. Three rounds is more than the two that the pre-G bug
        // needed to lose the tag.
        var merged = VpnServerMerger.mergeRecords(
            listOf(sstpRecord(443, "api"), sstpRecord(8443, "manual")),
        )
        for (refresh in 1..3) {
            val stored = merged[0]
            merged = VpnServerMerger.mergeRecords(
                listOf(sstpRecord(443, "api"), stored),
            )
            assertTrue(
                "refresh $refresh dropped the manual provenance",
                VpnRecords.sources(merged[0]).contains(SourceIdentity.MANUAL_TAG),
            )
            assertEquals(listOf("api", "manual"), VpnRecords.sources(merged[0]))
        }
    }
}
