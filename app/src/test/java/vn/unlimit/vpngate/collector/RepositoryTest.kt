package vn.unlimit.vpngate.collector

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import vn.unlimit.vpngate.data.model.VpnPortSource
import vn.unlimit.vpngate.data.model.VpnRecords
import vn.unlimit.vpngate.data.remote.HttpFetcher
import vn.unlimit.vpngate.data.remote.HttpResponse
import vn.unlimit.vpngate.data.remote.RedirectPolicy
import vn.unlimit.vpngate.data.remote.VpnGateUrls
import vn.unlimit.vpngate.merger.VpnServerMerger
import vn.unlimit.vpngate.parser.VpnGateApiParser
import vn.unlimit.vpngate.repository.VpnConnectionMapper
import vn.unlimit.vpngate.repository.VpnServerRepository

/**
 * Kotlin mirror of tests/test_live_fixtures.py end-to-end flow plus
 * repository-level behaviors: multi-source collection, merge →
 * validate → score → map into app connections, and the
 * last-known-good fallback.
 */
class RepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * Stage 2C-A: the fetch boundary now returns the response metadata, so
     * the fake answers with an [HttpResponse] per URL. The mirror serves
     * the same canonical-like table as the main site, which keeps the
     * assertions deterministic no matter which candidate wins the race.
     */
    private class FakeFetcher(val pages: Map<String, String>) : HttpFetcher {
        var fetchCount = 0

        override suspend fun get(
            url: String,
            redirectPolicy: RedirectPolicy?,
        ): HttpResponse? {
            fetchCount++
            val body = pages[url] ?: return null
            val contentType = if (url.contains("/api/iphone/")) "text/csv" else "text/html; charset=utf-8"
            return HttpResponse(
                requestedUrl = url,
                finalUrl = url,
                status = 200,
                contentType = contentType,
                body = body,
            )
        }
    }

    private fun mirrorDirectoryPage(mirrorUrl: String): String =
        """
        <html><body>
        <h2>VPNGate.net Mirror Sites</h2>
        <a href="$mirrorUrl">mirror</a>
        <a href="https://www.vpngate.net/en/">main site (ignored)</a>
        <a href="http://www.tsukuba.ac.jp/english/">university (ignored)</a>
        </body></html>
        """.trimIndent()

    private fun fixtureFetcher(): FakeFetcher {
        val mirrorUrl = "http://140.199.128.1:1770/"
        val table = CollectorTestFixtures.load(CollectorTestFixtures.TABLE_SAMPLE)
        return FakeFetcher(
            mapOf(
                VpnGateUrls.MAIN_URL to table,
                VpnGateUrls.API_URL to
                    CollectorTestFixtures.load(CollectorTestFixtures.API_SAMPLE),
                VpnGateUrls.MIRRORS_URL to mirrorDirectoryPage(mirrorUrl),
                "${mirrorUrl}en/" to table,
            )
        )
    }

    @Test
    fun refreshCollectsAndMergesAllSources(): Unit = runBlocking {
        val repo = VpnServerRepository(fetcher = fixtureFetcher())

        val result = repo.refresh()

        assertNotNull(result)
        result!!
        assertEquals(false, result.fromCache)
        assertTrue("no servers collected", result.serverCount > 0)
        assertTrue(result.connectionList.size() > 0)

        // public-vpn-206 comes from the HTML source: identity plus
        // SoftEther/OpenVPN facts must survive merge.
        var found = false
        for (i in 0 until result.connectionList.size()) {
            val conn = result.connectionList.get(i) ?: continue
            if (conn.hostName == "public-vpn-206") {
                found = true
                assertEquals("219.100.37.165", conn.ip)
                assertEquals("JP", conn.countryShort)
                // OpenVPN TCP 443 from the do_openvpn URL params.
                assertEquals(443, conn.tcpPort)
                // SoftEther ports from the HTML facts (§6).
                assertTrue(conn.seTcpPort > 0 || conn.seUdpPort > 0)
                break
            }
        }
        assertTrue("public-vpn-206 missing", found)

        // API-only rows (public-vpn-219) carry the decoded OpenVPN
        // profile needed for connecting.
        var apiRowFound = false
        for (i in 0 until result.connectionList.size()) {
            val conn = result.connectionList.get(i) ?: continue
            if (conn.hostName == "public-vpn-219") {
                apiRowFound = true
                assertEquals("219.100.37.206", conn.ip)
                assertNotNull(conn.openVpnConfigData)
                break
            }
        }
        assertTrue("public-vpn-219 (API row) missing", apiRowFound)
        Unit
    }

    @Test
    fun refreshFallsBackToLastKnownGoodSnapshot(): Unit = runBlocking {
        val cacheDir = tempFolder.newFolder("cache")
        val goodRepo = VpnServerRepository(fetcher = fixtureFetcher(), cacheDir = cacheDir)

        val good = goodRepo.refresh()
        assertNotNull(good)
        assertEquals(false, good!!.fromCache)

        // Stage 2C-B: collection no longer promotes itself to last-known-good.
        // The snapshot is written by the storage acceptance path, once the
        // dataset has passed integrity, so this test seeds it the same way
        // the updater does.
        val goodItems = good.connectionList.toVPNGateItems()
        assertTrue(goodRepo.saveLastKnownGood(goodItems))

        // Total network failure -> last-known-good snapshot served.
        val brokenRepo = VpnServerRepository(fetcher = FakeFetcher(emptyMap()), cacheDir = cacheDir)
        val fallback = brokenRepo.refresh()

        assertNotNull(fallback)
        fallback!!
        assertEquals(true, fallback.fromCache)
        assertEquals(good.connectionList.size(), fallback.connectionList.size())

        // No snapshot anywhere -> null.
        val emptyRepo = VpnServerRepository(fetcher = FakeFetcher(emptyMap()))
        assertNull(emptyRepo.refresh())
        Unit
    }

    @Test
    fun mapperFollowsAppConventions() {
        val record = VpnGateApiParser.parseApi(
            "*vpn_servers\n" + CollectorTestFixtures.makeApiRow(),
            "api",
        )[0]

        val conn = VpnConnectionMapper.toConnection(record)
        assertNotNull(conn)
        conn!!

        // hostName stored WITHOUT the .opengw.net suffix (UI re-appends).
        assertEquals("public-vpn-206", conn.hostName)
        assertEquals("public-vpn-206.opengw.net", conn.calculateHostName)

        // API facts mapped with unit conventions.
        assertEquals(659456803, conn.speed)        // bps
        assertEquals(21, conn.ping)
        assertEquals(443, conn.tcpPort)            // from decoded config
        assertEquals(0, conn.udpPort)
        assertEquals("JP", conn.countryShort)

        // 93 days uptime → ms, clamped into Int range (app displays).
        assertTrue(conn.uptime > 0)

        // OpenVPN profile preserved for connecting.
        assertNotNull(conn.openVpnConfigData)
        assertTrue(conn.openVpnConfigData!!.contains("dev tun"))
    }

    @Test
    fun mapperClampsOverflowingValues() {
        val record = VpnGateApiParser.parseApi(
            "*vpn_servers\n" + CollectorTestFixtures.makeApiRow(
                mapOf("Uptime" to "999999999999999") // far beyond Int ms
            ),
            "api",
        )[0]

        val conn = VpnConnectionMapper.toConnection(record)!!
        assertEquals(Int.MAX_VALUE, conn.uptime)
    }

    @Test
    fun sstpConnectPortIsZeroWhenSstpIsUnsupported() {
        // The API/CSV source never states SSTP support, so nothing may
        // be invented: the effective SSTP port is 0, not 443.
        val record = VpnGateApiParser.parseApi(
            "*vpn_servers\n" + CollectorTestFixtures.makeApiRow(
                mapOf("OpenVPN_ConfigData_Base64" to "")
            ),
            "api",
        )[0]

        val conn = VpnConnectionMapper.toConnection(record)!!
        assertEquals(0, conn.tcpPort)
        assertEquals(false, conn.isSSTPSupport())
        assertEquals(0, conn.sstpPort)
        assertEquals(VpnPortSource.UNKNOWN, conn.sstpPortSource)
        assertEquals(0, conn.sstpConnectPort)
    }

    @Test
    fun mapperMarksSoftEtherUdpUnknownPort() {
        // §6/§9 + plan T3.8: an HTML "UDP: Supported" server with no
        // printed port stays connectable=unknown; nothing is invented.
        val record = VpnRecords.newServer()
        VpnRecords.setField(record, "identity.hostname", "udp-only.opengw.net", "html")
        VpnRecords.setField(record, "identity.ip", "10.20.30.40", "html")
        VpnRecords.setField(record, "protocols.softether.udp.supported", true, "html")
        VpnRecords.sourceAdd(record, "html")

        assertTrue(VpnServerMerger.normalizeServer(record))

        val conn = VpnConnectionMapper.toConnection(record)
        assertNotNull(conn)
        conn!!
        assertEquals(0, conn.seTcpPort)
        assertEquals(0, conn.seUdpPort)          // no invented port
        assertEquals(true, conn.seUdpSupported)  // UI "port unknown" state
        assertTrue(conn.isSeUdpPortUnknown)
    }

    @Test
    fun softEtherUdpNeverInheritsTheTcpPort() {
        // TEST A: UDP supported, no published UDP port, TCP 995 present.
        // The UDP port must stay unknown instead of copying 995.
        val record = VpnRecords.newServer()
        VpnRecords.setField(record, "identity.hostname", "se-both.opengw.net", "html")
        VpnRecords.setField(record, "identity.ip", "10.20.30.41", "html")
        VpnRecords.setField(record, "protocols.softether.tcp.supported", true, "html")
        VpnRecords.setField(record, "protocols.softether.tcp.port", 995, "html")
        VpnRecords.setField(record, "protocols.softether.udp.supported", true, "html")
        VpnRecords.sourceAdd(record, "html")

        val conn = VpnConnectionMapper.toConnection(record)!!
        assertEquals(995, conn.seTcpPort)
        assertEquals(true, conn.seUdpSupported)
        assertEquals(0, conn.seUdpPort)
        assertEquals(true, conn.isSeUdpPortUnknown)
        assertTrue("UDP must not borrow the TCP port", conn.seUdpPort != conn.seTcpPort)
    }
}
