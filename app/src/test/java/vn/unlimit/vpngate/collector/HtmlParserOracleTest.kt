package vn.unlimit.vpngate.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import vn.unlimit.vpngate.data.model.OpenVpnRemote
import vn.unlimit.vpngate.data.model.VpnRecords
import vn.unlimit.vpngate.data.model.VpnServerRecord
import vn.unlimit.vpngate.data.model.VpnUtil
import vn.unlimit.vpngate.parser.VpnGateHtmlParser

/**
 * Kotlin mirror of tests/test_html_parser.py — DOM-based HTML parsing
 * against the REAL VPN Gate snapshot (same fixture file as Python,
 * one behavioral oracle). Mission §31 Tests 1/5/6/7.
 */
class HtmlParserOracleTest {
    companion object {
        private val fixtureHtml: String by lazy {
            CollectorTestFixtures.load(CollectorTestFixtures.TABLE_SAMPLE)
        }

        private val servers: Map<String, VpnServerRecord> by lazy {
            VpnGateHtmlParser.parseHtml(fixtureHtml, "html")
                .associateBy { VpnRecords.str(VpnRecords.identity(it)["hostname"]) }
        }

        /** Independent mini-extractor used ONLY by tests as ground
         * truth (different code path from the parser under test). */
        private fun rowForHost(needle: String): org.jsoup.nodes.Element? {
            val doc = VpnGateHtmlParser.makeSoup(fixtureHtml)
            for (table in doc.select("#${VpnGateHtmlParser.HOSTS_TABLE_ID}")) {
                for (tr in table.getElementsByTag("tr")) {
                    if (needle in tr.text()) {
                        return tr
                    }
                }
            }
            return null
        }

        private fun rowCells(tr: org.jsoup.nodes.Element): List<String> =
            tr.getElementsByTag("td").map { it.text() }
    }

    @Test
    fun fixtureIsRealVpngateDom() {
        assertTrue(fixtureHtml.contains("vg_hosts_table_id"))
        assertTrue(fixtureHtml.contains("opengw.net"))
    }

    @Test
    fun fullRecordPublicVpn206() {
        val server = servers["public-vpn-206.opengw.net"]
        assertNotNull("public-vpn-206 missing from snapshot", server)
        server!!

        val identity = VpnRecords.identity(server)
        assertEquals("public-vpn-206.opengw.net", identity["hostname"])
        assertEquals("219.100.37.165", identity["ip"])
        assertEquals("JP", identity["country"])           // ISO from flag image
        assertEquals("Japan", identity["countryLong"])

        val tr = rowForHost("219.100.37.165")
        assertNotNull(tr)
        tr!!

        val cells = rowCells(tr)

        fun findCell(snippet: String): String = cells.firstOrNull { snippet in it } ?: ""

        val p = VpnRecords.protocols(server)

        // --- SoftEther: STRICTLY from the SSL-VPN cell ---
        val seCell = findCell("SSL-VPN")
        assertTrue("SSL-VPN cell absent", seCell.isNotEmpty())

        val se = VpnRecords.map(p["softether"])
        val seTcpM = Regex("TCP\\s*:\\s*(\\d+)").find(seCell)

        if (seTcpM != null) {
            assertTrue(VpnRecords.bool(VpnRecords.transport(se, "tcp")["supported"]))
            assertEquals(
                seTcpM.groupValues[1].toInt(),
                VpnUtil.toInt(VpnRecords.transport(se, "tcp")["port"]),
            )
        } else {
            assertFalse(VpnRecords.bool(VpnRecords.transport(se, "tcp")["supported"]))
            assertNull(VpnRecords.transport(se, "tcp")["port"])
        }

        if (Regex("UDP\\s*:\\s*Supported").containsMatchIn(seCell)) {
            assertTrue(VpnRecords.bool(VpnRecords.transport(se, "udp")["supported"]))
            assertNull(VpnRecords.transport(se, "udp")["port"])     // §6
        }

        // --- OpenVPN: URL params are authoritative ---
        val a = tr.select("a").firstOrNull {
            val href = it.attr("href")
            href.isNotEmpty() && href.lowercase().contains("do_openvpn")
        }
        assertNotNull(a)
        a!!

        val href = a.attr("href")
        val mTcp = Regex("[?&]tcp=(\\d+)").find(href)
        val mUdp = Regex("[?&]udp=(\\d+)").find(href)

        val ov = VpnRecords.map(p["openvpn"])

        if (mTcp != null && mTcp.groupValues[1].toInt() > 0) {
            assertTrue(VpnRecords.bool(VpnRecords.transport(ov, "tcp")["supported"]))
            assertEquals(
                mTcp.groupValues[1].toInt(),
                VpnUtil.toInt(VpnRecords.transport(ov, "tcp")["port"]),
            )
        } else {
            assertFalse(VpnRecords.bool(VpnRecords.transport(ov, "tcp")["supported"]))
            assertNull(VpnRecords.transport(ov, "tcp")["port"])
        }

        if (mUdp != null && mUdp.groupValues[1].toInt() > 0) {
            assertTrue(VpnRecords.bool(VpnRecords.transport(ov, "udp")["supported"]))
            assertEquals(
                mUdp.groupValues[1].toInt(),
                VpnUtil.toInt(VpnRecords.transport(ov, "udp")["port"]),
            )
        }

        assertTrue(VpnRecords.bool(ov["configAvailable"]))

        // --- SSTP: port null unless printed (§9) ---
        val sstpCell = findCell("SSTP Hostname")
        val hm = Regex(
            "SSTP\\s+Hostname\\s*:\\s*([A-Za-z0-9._-]+)(?::(\\d+))?",
        ).find(sstpCell)

        val sstp = VpnRecords.map(p["sstp"])
        if (hm != null) {
            assertTrue(VpnRecords.bool(sstp["supported"]))
            assertEquals(hm.groupValues[1], sstp["hostname"])
            if (hm.groupValues[2].isNotEmpty()) {
                assertEquals(hm.groupValues[2].toInt(), VpnUtil.toInt(sstp["port"]))
            } else {
                assertNull(sstp["port"])
            }
        }

        // --- L2TP ---
        val l2tpSupportedAnywhere = cells.any { "L2TP" in it }
        val l2 = VpnRecords.map(p["l2tpIpsec"])
        assertEquals(l2tpSupportedAnywhere, VpnRecords.bool(l2["supported"]))
        if (l2tpSupportedAnywhere) {
            assertEquals(1701, VpnUtil.toInt(l2["port"]))
        }

        // --- Performance sanity ---
        val perf = VpnRecords.performance(server)
        assertTrue(VpnRecords.num(perf["speedMbps"]) > 0)
        assertTrue(VpnRecords.int(perf["sessions"]) >= 0)
        assertTrue(VpnRecords.num(perf["uptimeDays"]) > 0)    // 93 days

        // --- Provenance present (§14) ---
        val fs = VpnRecords.fieldSources(server)
        assertEquals(listOf("html"), fs["identity.ip"])
        assertEquals(listOf("html"), fs["protocols.softether.tcp.port"])
        assertEquals(listOf("html"), fs["protocols.openvpn.udp.port"])
    }

    @Test
    fun openvpnUdpOnlyFromUrlParams() {
        // §31 Test 5 — REAL server whose do_openvpn link carries
        // tcp=0&udp=N params: TCP must stay unsupported.
        val doc = VpnGateHtmlParser.makeSoup(fixtureHtml)

        var targetLink: org.jsoup.nodes.Element? = null
        var targetQuery = emptyMap<String, String>()

        for (a in doc.select("a[href]")) {
            val href = a.attr("href")
            if (!href.lowercase().contains("do_openvpn")) continue

            val query = href.substringAfterLast('?')
                .split("&")
                .filter { "=" in it }
                .associate {
                    val (k, v) = it.split("=", limit = 2)
                    k to v
                }

            if (query["tcp"] == "0" && (query["udp"] ?: "").all { c -> c.isDigit() }) {
                targetLink = a
                targetQuery = query
                break
            }
        }

        assumeTrue("no tcp=0&udp=N server in current snapshot", targetLink != null)

        val fqdn = Regex("fqdn=([^&]+)").find(targetLink!!.attr("href"))!!.groupValues[1]
        val server = servers[fqdn]!!

        val ov = VpnRecords.protocol(server, "openvpn")

        assertFalse(VpnRecords.bool(VpnRecords.transport(ov, "tcp")["supported"]))
        assertNull(VpnRecords.transport(ov, "tcp")["port"])
        assertTrue(VpnRecords.bool(VpnRecords.transport(ov, "udp")["supported"]))
        assertEquals(
            targetQuery.getValue("udp").toInt(),
            VpnUtil.toInt(VpnRecords.transport(ov, "udp")["port"]),
        )
        assertTrue(VpnRecords.bool(ov["configAvailable"]))
    }

    @Test
    fun sstpWithoutPortStaysNull() {
        // §31 Test 6: any snapshot server whose SSTP cell prints no
        // :port must yield port=null (never a guessed 443).
        var checked = 0

        val doc = VpnGateHtmlParser.makeSoup(fixtureHtml)

        for (tr in doc.getElementsByTag("tr")) {
            val text = tr.text()
            if ("SSTP Hostname" !in text) continue

            val m = Regex(
                "SSTP\\s+Hostname\\s*:\\s*([A-Za-z0-9._-]+)(?::(\\d+))?",
            ).find(text)

            assertNotNull("SSTP cell unparseable: ${text.take(120)}", m)
            m!!

            val host = m.groupValues[1].lowercase()
            val server = servers[host]
            assertNotNull("$host not parsed", server)

            val sstp = VpnRecords.protocol(server!!, "sstp")

            assertTrue(VpnRecords.bool(sstp["supported"]))

            if (m.groupValues[2].isNotEmpty()) {
                assertEquals(m.groupValues[2].toInt(), VpnUtil.toInt(sstp["port"]))
            } else {
                assertNull(sstp["port"])                          // the invariant
            }

            checked++
        }

        assertTrue(checked > 0)
    }

    @Test
    fun softetherUdpOnlyNeverBorrowsOpenvpnTcp() {
        // §31 Test 7 — REAL row whose SSL-VPN cell says
        // 'UDP: Supported' without TCP: udp=true/port=null and TCP
        // must NOT be borrowed from the OpenVPN column (Bug 1).
        val doc = VpnGateHtmlParser.makeSoup(fixtureHtml)

        var targetTds: List<org.jsoup.nodes.Element>? = null

        outer@
        for (table in doc.select("#${VpnGateHtmlParser.HOSTS_TABLE_ID}")) {
            for (tr in table.getElementsByTag("tr")) {
                val tds = tr.getElementsByTag("td")
                if (tds.size < 8) continue

                val firstClass = tds[0].classNames()
                if (firstClass.contains("vg_table_header")) continue

                val seText = tds[4].text()
                val ovpnText = tds[6].text()

                if ("SSL-VPN" in seText &&
                    Regex("UDP\\s*:\\s*Supported").containsMatchIn(seText) &&
                    !Regex("TCP\\s*:").containsMatchIn(seText) &&
                    Regex("TCP\\s*:\\s*\\d+").containsMatchIn(ovpnText)
                ) {
                    targetTds = tds
                    break@outer
                }
            }
        }

        assumeTrue(
            "no SE-UDP-only + OVPN-TCP row in current snapshot",
            targetTds != null,
        )

        val tds = targetTds!!

        val hostBlob = tds[1].text()
        val hm = Regex("[A-Za-z0-9._-]+\\.opengw\\.net").find(hostBlob)
        assertNotNull(hm)

        val hostname = hm!!.value.lowercase()
        val server = servers[hostname]!!

        val se = VpnRecords.protocol(server, "softether")

        assertTrue(VpnRecords.bool(se["supported"]))
        assertTrue(VpnRecords.bool(VpnRecords.transport(se, "udp")["supported"]))
        assertNull(VpnRecords.transport(se, "udp")["port"])       // §6
        assertFalse(VpnRecords.bool(VpnRecords.transport(se, "tcp")["supported"]))
        assertNull(VpnRecords.transport(se, "tcp")["port"])

        // Explicit prohibition check: OpenVPN TCP exists in this row
        // but was NOT copied into SoftEther.
        val ovpnTcpM = Regex("TCP\\s*:\\s*(\\d+)").find(tds[6].text())
        assertNotNull(ovpnTcpM)
        val ovpnPort = VpnUtil.toInt(VpnRecords.protocol(server, "openvpn").let {
            VpnRecords.transport(VpnRecords.map(it), "tcp")["port"]
        })
        assertEquals(ovpnTcpM!!.groupValues[1].toInt(), ovpnPort)
        assertTrue(
            VpnUtil.toInt(VpnRecords.transport(se, "tcp")["port"]) !=
                ovpnTcpM.groupValues[1].toInt()
        )
    }

    @Test
    fun operatorByPrefixStripped() {
        // 'By <name>' cell text must be stripped of its 'By ' prefix.
        val server = servers["public-vpn-206.opengw.net"]
        assertNotNull(server)

        val name = VpnRecords.str(VpnRecords.map(server!!["operator"])["name"])

        assertEquals("Daiyuu Nobori, Japan. Academic Use Only.", name)
        assertFalse(Regex("^by\\s", RegexOption.IGNORE_CASE).containsMatchIn(name))
    }

    @Test
    fun columnMapDerivedNotHardcoded() {
        val doc = VpnGateHtmlParser.makeSoup(fixtureHtml)
        val table = VpnGateHtmlParser.selectHostsTable(
            VpnGateHtmlParser.findHostsTables(doc)
        )

        assertNotNull("real hosts table not selectable", table)

        val colmap = VpnGateHtmlParser.buildColumnMap(table!!)

        // Verified against the live DOM header order.
        assertEquals(0, colmap["country"])
        assertEquals(1, colmap["host"])
        assertEquals(2, colmap["sessions"])
        assertEquals(3, colmap["perf"])
        assertEquals(4, colmap["softether"])
        assertEquals(5, colmap["l2tp"])
        assertEquals(6, colmap["openvpn"])
        assertEquals(7, colmap["sstp"])
        assertEquals(8, colmap["operator"])
        assertEquals(9, colmap["score"])
    }

    @Test
    fun duplicateTablesDoNotBreakSelection() {
        // The live page embeds THREE tables with the same id; the
        // selector must pick the real hosts list. The fixture stores
        // only the genuine hosts table; ensure the selector still
        // accepts it.
        val doc = VpnGateHtmlParser.makeSoup(fixtureHtml)
        val candidates = VpnGateHtmlParser.findHostsTables(doc)

        assertTrue(candidates.isNotEmpty())
        assertEquals(candidates[0], VpnGateHtmlParser.selectHostsTable(candidates))
    }

    @Test
    fun uptimeHoursNormalized() {
        assertEquals(11.0 / 24, VpnGateHtmlParser.parseUptimeDays("11 hours"), 1e-4)
        assertEquals(93.0, VpnGateHtmlParser.parseUptimeDays("93 days"), 1e-9)
        assertEquals(45.0 / 1440, VpnGateHtmlParser.parseUptimeDays("45 mins"), 1e-4)
        assertEquals(0.0, VpnGateHtmlParser.parseUptimeDays("no uptime here"), 1e-9)
    }

    @Test
    fun openVpnRemoteModelMatchesPythonFact() {
        val r = OpenVpnRemote("219.100.37.165", 443, "")
        assertEquals("219.100.37.165", r.host)
        assertEquals(443, r.port)
        assertEquals("", r.proto)
    }
}
