package vn.unlimit.vpngate.collector

import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.data.model.VpnRecords
import vn.unlimit.vpngate.data.model.VpnServerRecord
import vn.unlimit.vpngate.merger.VpnServerMerger
import vn.unlimit.vpngate.parser.VpnGateApiParser
import vn.unlimit.vpngate.parser.VpnGateHtmlParser
import vn.unlimit.vpngate.ranking.ServerQualityCalculator

/**
 * Kotlin mirror of tests/test_debug_dump.py — the §33 provenance
 * dump (same layout as Python's --debug-ip) must expose identity,
 * per-field sources, conflicts and confidence.
 */
class DebugDumpTest {
    private fun mergedServer(): VpnServerRecord {
        val apiRecord = VpnGateApiParser.parseApi(
            "*vpn_servers\n" + CollectorTestFixtures.makeApiRow(
                mapOf("NumVpnSessions" to "52")
            ),
            "api",
        )[0]

        val htmlRecord = VpnRecords.newServer()
        VpnRecords.setField(htmlRecord, "identity.hostname", "public-vpn-206.opengw.net", "html")
        VpnRecords.setField(htmlRecord, "identity.ip", "219.100.37.165", "html")
        VpnRecords.setField(htmlRecord, "performance.sessions", 69, "html")
        VpnRecords.setField(htmlRecord, "protocols.softether.tcp.supported", true, "html")
        VpnRecords.setField(htmlRecord, "protocols.softether.tcp.port", 443, "html")
        VpnRecords.sourceAdd(htmlRecord, "html")

        val merged = VpnServerMerger.mergeRecords(listOf(apiRecord, htmlRecord))
        assertTrue(merged.size == 1)
        return merged[0]
    }

    @Test
    fun dumpContainsIdentitySourcesConfidenceConflicts() {
        val dump = CollectorDebugDump.format(mergedServer())

        assertTrue("SERVER DUMP: public-vpn-206 (219.100.37.165)" in dump)
        assertTrue("[sources]" in dump)
        assertTrue("independent groups: api, html" in dump)

        assertTrue("[field sources]" in dump)
        assertTrue("identity.ip" in dump)
        assertTrue("protocols.softether.tcp.port" in dump)

        // Conflict visible with both owners' values (§38).
        assertTrue("[conflicts]" in dump)
        assertTrue("performance.sessions" in dump)
        assertTrue("52" in dump && "69" in dump)

        assertTrue("[confidence]" in dump)
        assertTrue("softether=0.60" in dump)
        assertTrue("openvpn=0.75" in dump)
    }

    @Test
    fun dumpWorksForEveryFixtureServer() {
        val html = CollectorTestFixtures.load(CollectorTestFixtures.TABLE_SAMPLE)
        val servers = VpnServerMerger.mergeRecords(
            VpnGateHtmlParser.parseHtml(html, "html")
        )
        val valid = VpnServerMerger.validateServers(servers)
        ServerQualityCalculator.scoreAll(valid)

        assertTrue(valid.isNotEmpty())
        for (server in valid) {
            val dump = CollectorDebugDump.format(server)
            assertTrue(dump.startsWith("=".repeat(72)))
            assertTrue("[conflicts]" in dump)
            assertTrue("[field sources]" in dump)
        }
    }
}
