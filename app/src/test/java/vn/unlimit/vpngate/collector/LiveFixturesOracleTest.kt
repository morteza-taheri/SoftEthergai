package vn.unlimit.vpngate.collector

import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.data.model.VpnRecords
import vn.unlimit.vpngate.data.model.VpnUtil
import vn.unlimit.vpngate.merger.Provenance
import vn.unlimit.vpngate.merger.VpnServerMerger
import vn.unlimit.vpngate.parser.VpnGateApiParser
import vn.unlimit.vpngate.parser.VpnGateHtmlParser
import vn.unlimit.vpngate.ranking.ServerQualityCalculator

/**
 * Kotlin mirror of tests/test_live_fixtures.py — regressions
 * captured from the LIVE site: the nested hosts-table layout with
 * its unmatched </td> (plan T2 DOM fix) and end-to-end pipeline
 * sanity (merge → validate → score).
 */
class LiveFixturesOracleTest {
    @Test
    fun nestedFixtureHasLiveMalformation() {
        val html = CollectorTestFixtures.load(CollectorTestFixtures.NESTED_SAMPLE)

        assertTrue(html.contains("Label_Table"))
        assertTrue(html.contains("vg_hosts_table_id"))
        assertTrue(
            Regex("</td>\\s*</td>\\s*</tr>").containsMatchIn(html)
        )
    }

    @Test
    fun nestedFixtureParsesAllRows() {
        val html = CollectorTestFixtures.load(CollectorTestFixtures.NESTED_SAMPLE)
        val servers = VpnGateHtmlParser.parseHtml(html, "html")

        // header + 15 captured data rows; at least 12 unique servers
        // must survive the stray </td> malformation.
        assertTrue("only ${servers.size} servers parsed", servers.size >= 12)

        for (s in servers) {
            val identity = VpnRecords.identity(s)
            assertTrue(VpnRecords.str(identity["ip"]).isNotEmpty())
            assertTrue(
                VpnRecords.str(identity["hostname"]).endsWith(".opengw.net")
            )
        }
    }

    @Test
    fun endToEndFixturePipeline() {
        // Same-file oracle across all Kotlin pieces: parse both
        // sources, merge, validate, score — no invented ports, all
        // quality/confidence machinery runs on real data.
        val html = CollectorTestFixtures.load(CollectorTestFixtures.TABLE_SAMPLE)
        val api = CollectorTestFixtures.load(CollectorTestFixtures.API_SAMPLE)

        val records = VpnGateHtmlParser.parseHtml(html, "html").toMutableList()
        records.add(VpnGateApiParser.parseApi(api, "api").first())

        val merged = VpnServerMerger.mergeRecords(records)
        val valid = VpnServerMerger.validateServers(merged)

        assertTrue(valid.isNotEmpty())

        ServerQualityCalculator.scoreAll(valid)

        for (server in valid) {
            val quality = VpnRecords.map(server["quality"])
            assertTrue(VpnRecords.int(quality["overall"]) in 0..100)

            val conf = Provenance.computeConfidence(server)
            assertEquals4Protocols(conf)
        }

        val ranked = ServerQualityCalculator.sortServers(valid)
        assertTrue(ranked.size == valid.size)

        val top = VpnRecords.int(VpnRecords.map(ranked.first()["quality"])["overall"])
        val bottom = VpnRecords.int(VpnRecords.map(ranked.last()["quality"])["overall"])
        assertTrue(top >= bottom)
    }

    private fun assertEquals4Protocols(conf: Map<String, Double>) {
        for (name in Provenance.PROTOCOL_NAMES) {
            val value = conf[name]
            assertTrue("missing confidence for $name", value != null)
            assertTrue("confidence out of range: $value", value!! in 0.0..1.0)
        }
    }

    @Test
    fun validPortGuards() {
        assertTrue(VpnUtil.validPort(443))
        assertTrue(!VpnUtil.validPort(null))
        assertTrue(!VpnUtil.validPort(0))
        assertTrue(!VpnUtil.validPort(70000))
    }
}
