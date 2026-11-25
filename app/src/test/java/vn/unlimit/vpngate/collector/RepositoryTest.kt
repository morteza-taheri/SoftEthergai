package vn.unlimit.vpngate.collector

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        // TIERED SOURCES (v2.4.21): when the page parsed, the CSV feed is NOT
        // fetched at all — it is 1.33 MB, 99.1% of it Base64, and it carries no
        // protocol columns. So no row arrives with an inline profile; each must
        // instead expose the sid/hid that openvpn_download.aspx needs.
        var downloadable = 0
        var withInlineProfile = 0
        for (i in 0 until result.connectionList.size()) {
            val conn = result.connectionList.get(i) ?: continue
            if (conn.canDownloadOpenVpnConfig) downloadable++
            if (!conn.openVpnConfigData.isNullOrBlank()) withInlineProfile++
        }
        assertTrue("no server exposes sid/hid for an on-demand profile", downloadable > 0)
        assertEquals(
            "the CSV feed must not be fetched when the page parsed",
            0,
            withInlineProfile,
        )

        // The capability must describe the dataset honestly: protocol columns
        // present, profiles absent.
        assertTrue(result.capability.providesProtocolColumns)
        assertFalse(result.capability.providesOpenVpnProfiles)
        Unit
    }

    /**
     * The CSV feed is a genuine fallback, not dead code: when the page cannot
     * be parsed the run must still produce a dataset, and that dataset is the
     * one case where inline OpenVPN profiles exist.
     */
    @Test
    fun refreshUsesCsvFeedWhenHtmlProducesNoRecords(): Unit = runBlocking {
        val repo = VpnServerRepository(
            fetcher = FakeFetcher(
                mapOf(
                    // A body that is not a hosts table: the HTML source is
                    // rejected by schema validation.
                    VpnGateUrls.MAIN_URL to "<html><body>no table here</body></html>",
                    VpnGateUrls.API_URL to
                        CollectorTestFixtures.load(CollectorTestFixtures.API_SAMPLE),
                    VpnGateUrls.MIRRORS_URL to mirrorDirectoryPage("http://140.199.128.1:1770/"),
                    "http://140.199.128.1:1770/en/" to
                        "<html><body>no table here</body></html>",
                ),
            ),
        )

        val result = repo.refresh()

        assertNotNull("the CSV fallback must produce a dataset", result)
        result!!
        assertTrue("no servers collected from the CSV", result.serverCount > 0)
        assertTrue(result.capability.providesOpenVpnProfiles)
        // The CSV has no protocol columns, so the capability must not claim
        // them — that is what stops a CSV-only run from being judged as a
        // protocol collapse.
        assertFalse(result.capability.providesProtocolColumns)

        var withProfile = 0
        for (i in 0 until result.connectionList.size()) {
            if (!result.connectionList.get(i)?.openVpnConfigData.isNullOrBlank()) withProfile++
        }
        assertTrue("the CSV feed carries inline OpenVPN profiles", withProfile > 0)
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
