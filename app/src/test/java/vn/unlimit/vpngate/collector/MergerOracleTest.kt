package vn.unlimit.vpngate.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.data.model.VpnRecords
import vn.unlimit.vpngate.data.model.VpnServerRecord
import vn.unlimit.vpngate.merger.Provenance
import vn.unlimit.vpngate.merger.VpnServerMerger
import vn.unlimit.vpngate.parser.VpnGateApiParser

/**
 * Kotlin mirror of tests/test_merge.py — Mission §31 Tests 3/4:
 * multi-source merge, dedup, provenance, conflict recording (§38)
 * and mirror-independence rules (§16).
 */
class MergerOracleTest {
    private fun htmlRecordFor206(): VpnServerRecord {
        val rec = VpnRecords.newServer()
        VpnRecords.setField(rec, "identity.hostname", "public-vpn-206.opengw.net", "html")
        VpnRecords.setField(rec, "identity.ip", "219.100.37.165", "html")
        return rec
    }

    @Test
    fun htmlPlusApiMergeToOneRecord() {
        // §31 Test 3: same server from HTML + API must merge into ONE
        // record with API winning metadata conflicts — recorded (§38).
        val apiRecord = VpnGateApiParser.parseApi(
            "*vpn_servers\n" + CollectorTestFixtures.makeApiRow(
                mapOf("NumVpnSessions" to "52")
            ),
            "api",
        )[0]

        // Simulate an HTML record for the same IP carrying a stale
        // sessions value and SoftEther info only present in HTML.
        val htmlRecord = htmlRecordFor206()
        VpnRecords.setField(htmlRecord, "identity.countryLong", "Japan", "html")
        VpnRecords.setField(htmlRecord, "performance.sessions", 69, "html")
        VpnRecords.setField(htmlRecord, "protocols.softether.tcp.supported", true, "html")
        VpnRecords.setField(htmlRecord, "protocols.softether.tcp.port", 443, "html")
        VpnRecords.setField(htmlRecord, "protocols.softether.udp.supported", true, "html")
        VpnRecords.sourceAdd(htmlRecord, "html")

        val merged = VpnServerMerger.mergeRecords(listOf(apiRecord, htmlRecord))

        assertEquals(1, merged.size)                          // ONE record, not two

        val server = merged[0]
        val p = VpnRecords.protocols(server)
        val se = VpnRecords.map(p["softether"])
        val ov = VpnRecords.map(p["openvpn"])

        // Union of protocol knowledge from BOTH sources:
        assertEquals(443, VpnRecords.int(VpnRecords.transport(se, "tcp")["port"]))
        assertEquals(443, VpnRecords.int(VpnRecords.transport(ov, "tcp")["port"]))
        assertTrue(VpnRecords.bool(ov["configAvailable"]))
        assertTrue(VpnRecords.bool(VpnRecords.transport(se, "udp")["supported"]))
        assertNull(VpnRecords.transport(se, "udp")["port"])   // never invented

        assertEquals(listOf("api", "html"), VpnRecords.sources(server).sorted())

        // Sessions disagreed (html=69 vs api=52): API wins (§17) and
        // the conflict is RECORDED, not silently overwritten.
        assertEquals(52, VpnRecords.int(VpnRecords.performance(server)["sessions"]))

        val conflict = VpnRecords.conflicts(server).firstOrNull {
            it.field == "performance.sessions"
        }
        assertTrue("sessions conflict not recorded", conflict != null)
        assertEquals(52, VpnRecords.int(conflict!!.values["api"]))
        assertEquals(69, VpnRecords.int(conflict.values["html"]))

        val fs = VpnRecords.fieldSources(server)
        assertEquals(listOf("api", "html"), fs["performance.sessions"]!!.sorted())
    }

    @Test
    fun apiCountryBeatsPartialHtml() {
        // §11: HTML's partial country must not replace API's full
        // value; missing API fields get filled FROM HTML without
        // conflicts.
        val apiRecord = VpnGateApiParser.parseApi(
            "*vpn_servers\n" + CollectorTestFixtures.makeApiRow(
                mapOf("CountryShort" to "", "CountryLong" to "")
            ),
            "api",
        )[0]

        assertEquals("", VpnRecords.identity(apiRecord)["countryLong"])

        val htmlRecord = htmlRecordFor206()
        VpnRecords.setField(htmlRecord, "identity.countryLong", "Japan", "html")
        VpnRecords.setField(htmlRecord, "identity.country", "JP", "html")
        VpnRecords.sourceAdd(htmlRecord, "html")

        val merged = VpnServerMerger.mergeRecords(listOf(htmlRecord, apiRecord))

        assertEquals(1, merged.size)

        val identity = VpnRecords.identity(merged[0])

        // HTML filled the gap; nothing was overwritten because the
        // API had no country data here.
        assertEquals("JP", identity["country"])
        assertEquals("Japan", identity["countryLong"])

        assertTrue(
            VpnRecords.conflicts(merged[0]).none {
                it.field.startsWith("identity.country")
            }
        )
    }

    @Test
    fun sameIpInSixMirrorsIsOneRecord() {
        // §31 Test 4: an IP seen across html + 6 mirrors stays ONE
        // record; mirrors collapse into a single independent group
        // (§16); confidence never exceeds the policy ceiling.
        val main = htmlRecordFor206()
        VpnRecords.setField(main, "protocols.softether.tcp.supported", true, "html")
        VpnRecords.setField(main, "protocols.softether.tcp.port", 443, "html")
        VpnRecords.sourceAdd(main, "html")

        fun mirrorTemplate(): VpnServerRecord {
            // Clone WITHOUT provenance, then re-tag as a mirror.
            @Suppress("UNCHECKED_CAST")
            val mirror = VpnServerMerger.deepCopy(main) as VpnServerRecord
            mirror["sources"] = mutableListOf<String>()
            mirror["sourceCount"] = 0
            mirror["fieldSources"] = LinkedHashMap<String, MutableList<String>>()
            return mirror
        }

        val records = mutableListOf(main)

        for (i in 1..6) {                                     // six mirrors
            val mirror = mirrorTemplate()
            VpnRecords.setField(mirror, "protocols.softether.tcp.port", 443, "mirror_$i")
            VpnRecords.setField(mirror, "protocols.softether.tcp.supported", true, "mirror_$i")
            VpnRecords.sourceAdd(mirror, "mirror_$i")
            records.add(mirror)
        }

        val merged = VpnServerMerger.mergeRecords(records)

        assertEquals(1, merged.size)                          // ONE record

        val server = merged[0]

        assertTrue(VpnRecords.sources(server).size >= 7)

        // §16: {html/mirror} — html + one shared mirror group = 2.
        val groups = Provenance.independentSourceGroups(server)
        assertEquals(listOf("html", "mirror"), groups)

        val conf = Provenance.computeConfidence(server)

        // §15: two INDEPENDENT groups => 0.80; six mirrors add NOTHING.
        assertEquals(0.8, conf["softether"]!!, 1e-9)
    }

    @Test
    fun confidencePolicyMatchesMission15() {
        assertEquals(0.6, Provenance.confidenceForGroups(listOf("html")), 1e-9)
        assertEquals(0.8, Provenance.confidenceForGroups(listOf("api", "html")), 1e-9)
        assertEquals(1.0, Provenance.confidenceForGroups(listOf("api", "html", "mirror")), 1e-9)
        assertEquals(0.0, Provenance.confidenceForGroups(emptyList()), 1e-9)
    }

    @Test
    fun priorityOrdering() {
        assertTrue(VpnServerMerger.sourcePriority("api") < VpnServerMerger.sourcePriority("html"))
        assertTrue(
            VpnServerMerger.sourcePriority("html") <
                VpnServerMerger.sourcePriority("mirror_9")
        )
    }

    @Test
    fun mirrorSpeedNeverOverwritesApi() {
        // A lower-authority source (mirror) disagreeing on speed must
        // NOT overwrite the API value, and the attempt IS visible (§38).
        val apiRecord = VpnGateApiParser.parseApi(
            "*vpn_servers\n" + CollectorTestFixtures.makeApiRow(
                mapOf("Speed" to "659456803")
            ),
            "api",
        )[0]

        val mirror = VpnRecords.newServer()
        VpnRecords.setField(mirror, "identity.ip", "219.100.37.165", "mirror_1")
        VpnRecords.setField(mirror, "identity.hostname", "public-vpn-206.opengw.net", "mirror_1")
        VpnRecords.setField(mirror, "performance.speedMbps", 111.0, "mirror_1")
        VpnRecords.sourceAdd(mirror, "mirror_1")

        val merged = VpnServerMerger.mergeRecords(listOf(apiRecord, mirror))

        assertEquals(1, merged.size)

        // API wins:
        assertEquals(
            659.456803,
            VpnRecords.num(VpnRecords.performance(merged[0])["speedMbps"]),
            1e-9,
        )

        // Disagreement was not silent:
        val speedConflict = VpnRecords.conflicts(merged[0]).firstOrNull {
            it.field == "performance.speedMbps"
        }
        assertTrue("speed conflict not recorded", speedConflict != null)
        assertEquals(659.456803, VpnRecords.num(speedConflict!!.values["api"]), 1e-9)
        assertEquals(111.0, VpnRecords.num(speedConflict.values["mirror_1"]), 1e-9)
    }
}
