package vn.unlimit.vpngate.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import vn.unlimit.vpngate.models.VPNGateItem

/**
 * Stage 2C-B: the dataset quality model on its own, with no updater, no
 * network and no Room.
 *
 * Every dataset here is built explicitly from the model, so the numbers in
 * the assertions are the numbers under test and never a live server count.
 */
class DatasetIntegrityTest {

    /** One deterministic server, with each capability switchable. */
    private fun server(
        name: String,
        seTcp: Boolean = false,
        seUdp: Boolean = false,
        sstp: Boolean = false,
        ovpnTcp: Boolean = false,
        ovpnUdp: Boolean = false,
        l2tp: Boolean = false,
        profile: Boolean = false,
    ) = VPNGateItem(
        hostName = name,
        ip = "10.0.0.1",
        countryLong = "Japan",
        countryShort = "JP",
        logType = null,
        operator = null,
        message = null,
        openVpnConfigData = if (profile) "client\nproto tcp\nremote 10.0.0.1 443\n" else null,
        tcpPort = if (ovpnTcp) 443 else 0,
        udpPort = if (ovpnUdp) 1195 else 0,
        isL2TPSupport = l2tp,
        isSSTPSupport = sstp,
        seTcpPort = if (seTcp) 443 else 0,
        seUdpPort = 0,
        seUdpSupported = seUdp,
    )

    /** A healthy mixed dataset: every protocol represented by most rows. */
    private fun healthy(count: Int, prefix: String = "stored") = (1..count).map { i ->
        server(
            name = "$prefix-$i",
            seTcp = true,
            seUdp = i % 3 != 0,
            sstp = i % 2 == 0,
            ovpnTcp = true,
            ovpnUdp = i % 4 == 0,
            l2tp = i % 5 == 0,
            profile = true,
        )
    }

    /** Every server offers every protocol: isolates the rule under test. */
    private fun uniform(count: Int, prefix: String = "uniform", profile: Boolean = true) =
        (1..count).map {
            server(
                name = "$prefix-$it",
                seTcp = true,
                seUdp = true,
                sstp = true,
                ovpnTcp = true,
                ovpnUdp = true,
                l2tp = true,
                profile = profile,
            )
        }

    private val full = SourceCapability.FULL

    // ------------------------------------------------- absolute floor

    @Test
    fun anEmptyDatasetIsNotStored() {
        val verdict = DatasetIntegrity.evaluate(emptyList(), healthy(30), full)
        assertFalse(verdict.accepted)
        assertEquals(DatasetDefect.TOO_SMALL, verdict.defect)
    }

    @Test
    fun oneRowWithNoStoredDatasetIsRejectedByTheAbsoluteFloor() {
        // Documented behaviour: one server is not a server list, even on a
        // fresh install where there is nothing to compare against.
        val verdict = DatasetIntegrity.evaluate(listOf(server("only")), emptyList(), full)
        assertFalse(verdict.accepted)
        assertEquals(DatasetDefect.TOO_SMALL, verdict.defect)
        assertTrue(verdict.detail.contains("${DatasetIntegrity.MIN_ACCEPTABLE_DATASET_SIZE}"))
    }

    @Test
    fun aDatasetExactlyAtTheFloorIsAcceptedOnAFreshInstall() {
        val candidate = healthy(DatasetIntegrity.MIN_ACCEPTABLE_DATASET_SIZE, "fresh")
        val verdict = DatasetIntegrity.evaluate(candidate, emptyList(), full)
        assertTrue(verdict.accepted)
    }

    @Test
    fun oneRowBelowTheFloorIsRejected() {
        val candidate = healthy(DatasetIntegrity.MIN_ACCEPTABLE_DATASET_SIZE - 1, "small")
        val verdict = DatasetIntegrity.evaluate(candidate, healthy(30), full)
        assertFalse(verdict.accepted)
        assertEquals(DatasetDefect.TOO_SMALL, verdict.defect)
    }

    // ------------------------------------------- relative degradation

    @Test
    fun aTinyCandidateIsBelowTheAbsoluteFloor() {
        val verdict = DatasetIntegrity.evaluate(healthy(3, "tiny"), healthy(100), full)
        assertFalse(verdict.accepted)
        assertEquals(DatasetDefect.TOO_SMALL, verdict.defect)
    }

    @Test
    fun aCandidateAboveTheFloorButFarBelowTheStoredCountCollapses() {
        // 12 rows are a plausible dataset on their own, but they are 12% of
        // a stored list of 100 � that is truncation, not churn.
        val stored = uniform(100)
        val candidate = uniform(12, "tiny")
        val verdict = DatasetIntegrity.evaluate(candidate, stored, full, lastAcceptedSize = stored.size, strictSizeBaseline = true)
        assertFalse(verdict.accepted)
        assertEquals(DatasetDefect.SIZE_COLLAPSE, verdict.defect)
    }
    @Test
    fun aModerateNaturalReductionIsAccepted() {
        val stored = healthy(100)
        // 85% of the stored size: normal churn, far from the ratio floor.
        val candidate = healthy(85, "smaller")
        val verdict = DatasetIntegrity.evaluate(candidate, stored, full, lastAcceptedSize = stored.size, strictSizeBaseline = true)
        assertTrue(verdict.accepted)
    }

    @Test
    fun theRatioFloorIsExactlyHalfTheStoredSize() {
        val stored = uniform(100, "stored")
        val ratio = DatasetIntegrity.MIN_RELATIVE_DATASET_RATIO
        val justAbove = uniform((stored.size * ratio).toInt() + 1, "above")
        val justBelow = uniform((stored.size * ratio).toInt() - 1, "below")

        assertTrue(DatasetIntegrity.evaluate(justAbove, stored, full, lastAcceptedSize = stored.size, strictSizeBaseline = true).accepted)
        assertFalse(DatasetIntegrity.evaluate(justBelow, stored, full, lastAcceptedSize = stored.size, strictSizeBaseline = true).accepted)
    }

    @Test
    fun aCandidateLargerThanTheStoredListIsAccepted() {
        val stored = healthy(20)
        val candidate = healthy(250, "bigger")
        assertTrue(DatasetIntegrity.evaluate(candidate, stored, full, lastAcceptedSize = stored.size, strictSizeBaseline = true).accepted)
    }

    @Test
    fun aLargeCandidateAgainstATinyStoredListUsesTheAbsoluteFloorOnly() {
        // Nothing meaningful to compare against: 12 rows over 3 is an
        // improvement, not a collapse.
        val verdict = DatasetIntegrity.evaluate(healthy(12, "big"), healthy(3, "tiny"), full)
        assertTrue(verdict.accepted)
    }

    // ------------------------------------------- protocol coverage

    @Test
    fun losingSstpAlmostCompletelyIsRejected() {
        val stored = healthy(100)
        // Same server count, SSTP gone.
        val candidate = (1..100).map {
            server("candidate-$it", seTcp = true, seUdp = true, ovpnTcp = true, profile = true)
        }
        val verdict = DatasetIntegrity.evaluate(candidate, stored, full, lastAcceptedSize = stored.size, strictSizeBaseline = true)
        assertFalse(verdict.accepted)
        assertEquals(DatasetDefect.PROTOCOL_COLLAPSE, verdict.defect)
        assertTrue(verdict.detail.startsWith("SSTP"))
    }

    @Test
    fun losingSoftEtherUdpIsRejected() {
        val stored = healthy(100)
        val candidate = (1..100).map {
            server("candidate-$it", seTcp = true, sstp = true, ovpnTcp = true, profile = true)
        }
        val verdict = DatasetIntegrity.evaluate(candidate, stored, full, lastAcceptedSize = stored.size, strictSizeBaseline = true)
        assertFalse(verdict.accepted)
        assertEquals(DatasetDefect.PROTOCOL_COLLAPSE, verdict.defect)
        assertTrue(verdict.detail.startsWith("SoftEther UDP"))
    }

    @Test
    fun aModestProtocolReductionIsAccepted() {
        val stored = healthy(100)
        // SSTP present on one server in ten: far below the stored 50, but
        // above the coverage floor of 25.
        val candidate = (1..100).map { i ->
            server(
                "candidate-$i",
                seTcp = true,
                seUdp = i % 3 != 0,
                sstp = i % 3 == 0,
                ovpnTcp = true,
                ovpnUdp = i % 4 == 0,
                l2tp = i % 5 == 0,
                profile = true,
            )
        }
        assertTrue(DatasetIntegrity.evaluate(candidate, stored, full, lastAcceptedSize = stored.size, strictSizeBaseline = true).accepted)
    }

    @Test
    fun softEtherUdpWithoutAPublishedPortStillCountsAsCoverage() {
        // "supported with an unknown port" is a real capability and must not
        // be scored as absent.
        val coverage = DatasetIntegrity.coverage(healthy(30))
        assertEquals(30, coverage.softEtherTcp)
        assertEquals(20, coverage.softEtherUdp) // every third server has no UDP
        assertEquals(30, coverage.openVpnProfiles)
    }

    @Test
    fun protocolChecksAreSkippedWhenTheStoredCountIsBelowTheBaseline() {
        // 23 servers is the size of the live canonical HTML capture: a
        // protocol held by 15 of them is noise, not a collapse.
        val stored = (1..23).map { i -> server("stored-$i", seTcp = true, sstp = i <= 15) }
        val candidate = (1..23).map { server("candidate-$it", seTcp = true) }
        val verdict = DatasetIntegrity.evaluate(candidate, stored, full, lastAcceptedSize = stored.size, strictSizeBaseline = true)
        assertTrue(verdict.accepted)
    }

    // ------------------------------------------- OpenVPN profiles

    @Test
    fun aPartialProfileCollapseIsRejected() {
        // Full protocol coverage, but the profile feed collapsed: the shape
        // the OpenVPN-only protection exists for.
        val stored = uniform(100)
        val candidate = (1..100).map { i ->
            server(
                "candidate-$i",
                seTcp = true,
                seUdp = true,
                sstp = true,
                ovpnTcp = true,
                ovpnUdp = true,
                l2tp = true,
                profile = i <= 5,
            )
        }
        val verdict = DatasetIntegrity.evaluate(candidate, stored, full, lastAcceptedSize = stored.size, strictSizeBaseline = true)
        assertFalse(verdict.accepted)
        assertEquals(DatasetDefect.PROFILE_COLLAPSE, verdict.defect)
    }

    @Test
    fun aProfileFreeCandidateIsLeftToTheUpdaterRule() {
        // The model does not reject it: the updater's dropsOpenVpnProfiles
        // already refuses a profile-less list when one is stored, and a
        // source without an API feed is not a corruption.
        val stored = uniform(100)
        val candidate = uniform(100, "candidate", profile = false)
        val verdict = DatasetIntegrity.evaluate(candidate, stored, full, lastAcceptedSize = stored.size, strictSizeBaseline = true)
        assertTrue(verdict.detail, verdict.accepted)
    }

    // ------------------------------------------- source capability

    @Test
    fun anApiOnlyRunIsNotPunishedForHavingNoSoftEther() {
        // Mandatory regression: the CSV feed carries profiles and never
        // SoftEther/SSTP/L2TP facts. Losing them is a source limitation.
        val stored = healthy(100)
        val apiOnly = (1..100).map { server("api-$it", ovpnTcp = true, profile = true) }
        val verdict = DatasetIntegrity.evaluate(apiOnly, stored, SourceCapability.API_ONLY, lastAcceptedSize = stored.size, strictSizeBaseline = true)
        assertTrue(
            "an API-only dataset must not be rejected for SoftEther: ${verdict.detail}",
            verdict.accepted,
        )
    }

    @Test
    fun anHtmlOnlyRunIsNotPunishedForHavingNoProfiles() {
        val stored = healthy(100)
        val htmlOnly = healthy(100, "html")
        val verdict = DatasetIntegrity.evaluate(htmlOnly, stored, SourceCapability.HTML_ONLY, lastAcceptedSize = stored.size, strictSizeBaseline = true)
        assertTrue(verdict.accepted)
    }

    @Test
    fun capabilityIsHonouredPerRunNotGlobally() {
        // The same candidate that is fine for an API-only run is a real
        // collapse when the run could have published protocol columns.
        val stored = healthy(100)
        val apiOnly = (1..100).map { server("api-$it", ovpnTcp = true, profile = true) }
        assertTrue(
            DatasetIntegrity.evaluate(apiOnly, stored, SourceCapability.API_ONLY, lastAcceptedSize = stored.size, strictSizeBaseline = true).accepted
        )
        assertEquals(
            DatasetDefect.PROTOCOL_COLLAPSE,
            DatasetIntegrity.evaluate(apiOnly, stored, full, lastAcceptedSize = stored.size, strictSizeBaseline = true).defect,
        )
    }

    // ------------------------------------------- coverage math

    @Test
    fun coverageCountsEachCapabilityIndependently() {
        val items = listOf(
            server("a", seTcp = true, seUdp = true, sstp = true, ovpnTcp = true, ovpnUdp = true, l2tp = true, profile = true),
            server("b", seTcp = true, ovpnUdp = true, profile = true),
            server("c", ovpnTcp = true),
        )
        val coverage = DatasetIntegrity.coverage(items)
        assertEquals(3, coverage.total)
        assertEquals(2, coverage.softEtherTcp)
        assertEquals(1, coverage.softEtherUdp)
        assertEquals(1, coverage.sstp)
        assertEquals(2, coverage.openVpnTcp)
        assertEquals(2, coverage.openVpnUdp)
        assertEquals(1, coverage.l2tp)
        assertEquals(2, coverage.openVpnProfiles)
    }

    @Test
    fun thresholdsAreOrderedAsDocumented() {
        // The documented reasoning assumes a floor below every real capture
        // (14 servers in the nested live fixture) and a ratio that only
        // rejects severe collapse.
        assertTrue(DatasetIntegrity.MIN_ACCEPTABLE_DATASET_SIZE <= 14)
        assertTrue(DatasetIntegrity.MIN_RELATIVE_DATASET_RATIO <= 0.5)
        assertTrue(DatasetIntegrity.MIN_PROTOCOL_COVERAGE_RATIO <= 0.5)
    }

    // ------------------------------------------------- BLOCK v1.0.75 FIX
    // The baseline is the LAST FRESH OBSERVATION (the stored rows carrying the
    // newest lastSeenAt), not the retention union. Device evidence 2026-10-27:
    // a 400-row union vs a ~100-row live feed rejected EVERY refresh.

    @Test
    fun `a retention union far larger than the last observation accepts a live feed`() {
        val previousObservation = healthy(100, "prev").onEach { it.lastSeenAt = 2_000L }
        val older = healthy(300, "old").onEach { it.lastSeenAt = 1_000L }
        val stored = previousObservation + older
        val live = healthy(99, "live")

        val verdict = DatasetIntegrity.evaluate(live, stored, full, lastAcceptedSize = 100)

        assertTrue(
            "a live feed of 99 against a previous observation of 100 must be accepted " +
                "even though the retention union is ${stored.size}: ${verdict.detail}",
            verdict.accepted,
        )
    }

    @Test
    fun `a genuinely truncated feed is still rejected against the last observation`() {
        val stored = healthy(100, "prev").onEach { it.lastSeenAt = 2_000L }
        val truncated = healthy(10, "live")

        val verdict = DatasetIntegrity.evaluate(truncated, stored, full, lastAcceptedSize = 100, strictSizeBaseline = true)

        assertFalse("10 of 100 is a collapse and must stay rejected", verdict.accepted)
        assertEquals(DatasetDefect.SIZE_COLLAPSE, verdict.defect)
    }

    @Test
    fun `protocol coverage is judged against the last observation not the union`() {
        // The union has 400 SSTP rows; the previous observation had 100 and the
        // live feed 99 — a healthy feed that a union-based check would reject.
        val previous = uniform(100, "prev").onEach { it.lastSeenAt = 2_000L }
        val older = uniform(300, "old").onEach { it.lastSeenAt = 1_000L }
        val live = uniform(99, "live")

        val verdict = DatasetIntegrity.evaluate(live, previous + older, full, lastAcceptedSize = 99)

        assertTrue(
            "99 of the previous 100 must not look like a protocol collapse: ${verdict.detail}",
            verdict.accepted,
        )
    }

    // ------------------------------------------------- BLOCK v1.0.75 FIX (2)
    // The explicit last-accepted size is the authoritative baseline: the stored
    // union can be inflated by an emergency merge (device: the newest
    // lastSeenAt group was 250 rows while the live feed was ~98).

    @Test
    fun `an explicit last-accepted baseline accepts a live feed over an inflated union`() {
        val stored = healthy(250, "union").onEach { it.lastSeenAt = 5_000L } +
            healthy(150, "old").onEach { it.lastSeenAt = 1_000L }
        val live = healthy(98, "live")

        val verdict = DatasetIntegrity.evaluate(live, stored, full, lastAcceptedSize = 98)

        assertTrue(
            "98 against an accepted baseline of 98 must pass even with a 400-row union: " +
                verdict.detail,
            verdict.accepted,
        )
    }

    @Test
    fun `an explicit last-accepted baseline still rejects a truncated response`() {
        val stored = healthy(250, "union").onEach { it.lastSeenAt = 5_000L }
        val truncated = healthy(20, "live")

        val verdict = DatasetIntegrity.evaluate(
            truncated, stored, full, lastAcceptedSize = 98, strictSizeBaseline = true,
        )

        assertFalse("20 of an accepted 98 is a collapse", verdict.accepted)
        assertEquals(DatasetDefect.SIZE_COLLAPSE, verdict.defect)
    }
}
