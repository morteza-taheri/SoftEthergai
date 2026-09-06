package vn.unlimit.vpngate.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.data.model.OpenVpnRemote
import vn.unlimit.vpngate.data.model.VpnRecords
import vn.unlimit.vpngate.data.model.VpnUtil
import vn.unlimit.vpngate.merger.VpnServerMerger
import vn.unlimit.vpngate.parser.VpnGateApiParser

/**
 * Kotlin mirror of tests/test_api_parser.py — Mission §31 Test 2:
 * API CSV parsing with unit normalization (§18) using the REAL
 * header captured live from /api/iphone/.
 */
class ApiParserOracleTest {
    @Test
    fun sameServerFromApi() {
        val text = "*vpn_servers\n" + CollectorTestFixtures.makeApiRow()

        val servers = VpnGateApiParser.parseApi(text, "api")

        assertEquals(1, servers.size)

        val server = servers[0]
        val identity = VpnRecords.identity(server)

        assertEquals("public-vpn-206", identity["hostname"])
        assertEquals("219.100.37.165", identity["ip"])
        assertEquals("JP", identity["country"])
        assertEquals("Japan", identity["countryLong"])

        val perf = VpnRecords.performance(server)

        assertEquals(659.456803, VpnRecords.num(perf["speedMbps"]), 1e-9) // bps -> Mbps
        assertEquals(21.0, VpnRecords.num(perf["pingMs"]), 1e-9)
        assertEquals(52, VpnRecords.int(perf["sessions"]))
        assertEquals(93.0, VpnRecords.num(perf["uptimeDays"]), 1e-9) // ms -> days
        assertEquals(15699569, VpnRecords.int(perf["totalUsers"]))
        assertEquals(
            633908788074369.0 / (1024.0 * 1024.0 * 1024.0),
            VpnRecords.num(perf["totalTrafficGB"]),
            1e-6 * (633908788074369.0 / (1024.0 * 1024.0 * 1024.0)),
        )
        assertEquals("2weeks", VpnRecords.map(server["logging"])["policy"])

        // Protocol truth from the decoded config itself (§7):
        val p = VpnRecords.protocol(server, "openvpn")
        assertTrue(VpnRecords.bool(p["supported"]))
        assertTrue(VpnRecords.bool(p["configAvailable"]))
        assertTrue(VpnRecords.bool(VpnRecords.transport(p, "tcp")["supported"]))
        assertEquals(443, VpnUtil.toInt(VpnRecords.transport(p, "tcp")["port"]))
        assertFalse(VpnRecords.bool(VpnRecords.transport(p, "udp")["supported"]))
        assertNull(VpnRecords.transport(p, "udp")["port"])        // NEVER guessed 1194

        @Suppress("UNCHECKED_CAST")
        val configs = p["configs"] as List<OpenVpnRemote>
        assertEquals(1, configs.size)
        assertEquals(OpenVpnRemote("219.100.37.165", 443, ""), configs[0])

        // Provenance (§14/§17): metadata marked as coming from api.
        val fs = VpnRecords.fieldSources(server)
        assertEquals(listOf("api"), fs["performance.speedMbps"])
        assertEquals(listOf("api"), fs["performance.score"])
        assertEquals(listOf("api"), fs["identity.country"])
    }

    @Test
    fun realApiFixtureParses() {
        // Same-file oracle: the REAL /api/iphone/ capture parses with
        // sane unit conversions.
        val text = CollectorTestFixtures.load(CollectorTestFixtures.API_SAMPLE)

        val servers = VpnGateApiParser.parseApi(text, "api")

        assertEquals(5, servers.size)

        for (server in servers) {
            val identity = VpnRecords.identity(server)
            assertTrue(VpnUtil.validIp(VpnRecords.str(identity["ip"])))
            assertTrue(VpnRecords.str(identity["hostname"]).isNotEmpty())
            // bps -> Mbps and ms -> days actually happened.
            assertTrue(VpnRecords.num(VpnRecords.performance(server)["speedMbps"]) > 1)
            assertTrue(VpnRecords.num(VpnRecords.performance(server)["uptimeDays"]) >= 0.5)
        }
    }

    @Test
    fun realApiFixtureOpenVpnConfigDecoded() {
        val text = CollectorTestFixtures.load(CollectorTestFixtures.API_SAMPLE)

        val servers = VpnGateApiParser.parseApi(text, "api")
        val withConfig = servers.filter {
            @Suppress("UNCHECKED_CAST")
            (VpnRecords.protocol(it, "openvpn")["configs"] as List<OpenVpnRemote>).isNotEmpty()
        }

        assertTrue("no decoded OpenVPN configs in live rows", withConfig.isNotEmpty())

        for (server in withConfig) {
            val ovpn = VpnRecords.protocol(server, "openvpn")
            assertTrue(VpnRecords.bool(ovpn["configAvailable"]))

            @Suppress("UNCHECKED_CAST")
            for (remote in ovpn["configs"] as List<OpenVpnRemote>) {
                assertTrue(remote.host.isNotEmpty())
                // §7: port only from a real `remote ... <port>` directive.
                assertTrue(remote.port == null || VpnUtil.validPort(remote.port))
            }
        }
    }

    @Test
    fun normalizeServerFillsL2tpPortAndDropsInvalidIp() {
        val text = "*vpn_servers\n" + CollectorTestFixtures.makeApiRow()
        val server = VpnGateApiParser.parseApi(text, "api")[0]

        // Mark L2TP supported without a port: normalization must use
        // the protocol-standard 1701 (never invent anything else).
        VpnRecords.markSupported(server, "l2tpIpsec", "api")
        VpnRecords.protocol(server, "l2tpIpsec")["port"] = null

        assertTrue(VpnServerMerger.normalizeServer(server))
        assertEquals(
            1701,
            VpnUtil.toInt(VpnRecords.protocol(server, "l2tpIpsec")["port"]),
        )

        val broken = VpnGateApiParser.parseApi(text, "api")[0]
        VpnRecords.identity(broken)["ip"] = "not-an-ip"
        assertFalse(VpnServerMerger.normalizeServer(broken))
    }
}
