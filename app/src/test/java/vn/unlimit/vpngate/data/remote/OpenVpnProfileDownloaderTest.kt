package vn.unlimit.vpngate.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vn.unlimit.vpngate.models.VPNGateConnection

class OpenVpnProfileDownloaderTest {
    private class FakeFetcher(private val body: String) : HttpFetcher {
        var requestedUrl: String? = null

        override suspend fun get(url: String, redirectPolicy: RedirectPolicy?): HttpResponse {
            requestedUrl = url
            return HttpResponse(
                requestedUrl = url,
                finalUrl = url,
                status = 200,
                contentType = "application/x-openvpn-profile",
                body = body,
            )
        }
    }

    /**
     * A mirror that behaves the way the live one does: an old `sid` is bounced
     * back to the page (an HTML body), a current one returns the profile.
     */
    private class TokenAwareFetcher(
        private val page: String,
        private val freshSid: String,
        private val profile: String,
    ) : HttpFetcher {
        val urls = mutableListOf<String>()
        var pageFetches = 0

        override suspend fun get(url: String, redirectPolicy: RedirectPolicy?): HttpResponse {
            urls += url
            if (url.contains("/en/")) {
                pageFetches++
                return HttpResponse(url, url, 200, "text/html", page)
            }
            if (url.contains("sid=$freshSid")) {
                return HttpResponse(url, url, 200, "application/x-openvpn-profile", profile)
            }
            // Stale generation: the endpoint answers with its own page.
            return HttpResponse(url, url, 200, "text/html", OBJECT_MOVED)
        }
    }

    /**
     * The generation a fresh collection produces. It must be "young" so the
     * downloader uses it before spending a page read on a repair.
     */
    /**
     * The generation a fresh collection produces. Deliberately a minute older
     * than [pageSid] so the two are always distinct values, and young enough to
     * be trusted — a young token is tried before any page is read.
     */
    private fun freshSid(): String = (System.currentTimeMillis() - 60_000L).toString()

    /**
     * The token a live page load publishes. Captured per test so no assertion
     * depends on wall-clock time: a fixed literal would age past
     * [GENERATION_TRUST_MS] and start exercising the wrong branch.
     */
    private fun pageSid(): String = System.currentTimeMillis().toString()

    private fun connection(sid: String = freshSid()): VPNGateConnection = VPNGateConnection().apply {
        hostName = "vpn53"
        ip = "203.0.113.53"
        tcpPort = 443
        udpPort = 1195
        openVpnDownloadSid = sid
        openVpnDownloadHid = "24992073"
        openVpnDownloadHost = "public-vpn-53.opengw.net"
    }

    @Before
    fun resetPageCache() {
        OpenVpnProfileDownloader.clearPageCache()
    }

    @Test
    fun buildsOfficialPerServerTcpDownloadAndAcceptsMatchingProfile() = runBlocking {
        val fetcher = FakeFetcher(TCP_PROFILE)
        val sid = freshSid()

        val result = OpenVpnProfileDownloader(fetcher).download(connection(sid), useUdp = false)

        assertEquals(
            TCP_PROFILE.replace("remote public-vpn-53.opengw.net 443", "remote 203.0.113.53 443"),
            result,
        )
        val url = fetcher.requestedUrl.orEmpty()
        assertTrue(url.startsWith("https://www.vpngate.net/common/openvpn_download.aspx?"))
        assertTrue(url.contains("sid=$sid"))
        assertTrue(url.contains("udp=0"))
        assertTrue(url.contains("host=public-vpn-53.opengw.net"))
        assertTrue(url.contains("port=443"))
        assertTrue(url.contains("hid=24992073"))
        assertTrue(url.endsWith("vpngate_public-vpn-53.opengw.net_tcp_443.ovpn"))
    }

    /**
     * Mirrors are tried before the canonical host, and the canonical host is
     * still available as a last resort.
     *
     * The order is a censored-network decision, not a preference: the canonical
     * host is the one that is blocked, while a mirror is the authority that just
     * carried the list refresh. Device log 2026-11-25 showed the old order
     * spending the entire per-profile budget on the blocked host before a mirror
     * was even attempted.
     */
    @Test
    fun triesCachedMirrorsBeforeTheCanonicalHost() = runBlocking {
        val mirrorUrl = "http://150.40.105.20:16086/common/openvpn_download.aspx"
        val mirrorFetcher = object : HttpFetcher {
            val urls = mutableListOf<String>()
            override suspend fun get(url: String, redirectPolicy: RedirectPolicy?): HttpResponse? {
                urls += url
                if (url.startsWith("http://")) return null // the mirror is blocked
                return HttpResponse(url, url, 200, "application/x-openvpn-profile", TCP_PROFILE)
            }
        }

        val result = OpenVpnProfileDownloader(mirrorFetcher).download(
            connection(),
            useUdp = false,
            mirrorBaseUrls = listOf("http://150.40.105.20:16086"),
        )

        assertTrue(result.orEmpty().contains("remote 203.0.113.53 443"))
        assertTrue(
            "the cached mirror must be the first authority tried: ${mirrorFetcher.urls}",
            mirrorFetcher.urls.first().startsWith("$mirrorUrl?"),
        )
        assertTrue(
            "the canonical host must remain the last resort: ${mirrorFetcher.urls}",
            mirrorFetcher.urls.last()
                .startsWith("https://www.vpngate.net/common/openvpn_download.aspx?"),
        )
    }

    /**
     * The core repair: a `sid` the endpoint no longer honours is answered with
     * the mirror's own page, so the downloader re-reads the live hosts page and
     * retries with the token that page publishes.
     *
     * Device evidence (2026-11-25): 98 rows shared one `sid`, that generation
     * answered `302 Location: /` (followed, so an HTML body arrived), while a
     * freshly scraped `sid` for the same mirror returned a 10 KB profile.
     */
    @Test
    fun refreshesThePageTokenWhenTheStoredGenerationIsRejected() = runBlocking {
        val mirror = "http://150.40.105.20:16086"
        val storedSid = freshSid()
        val pageToken = pageSid()
        val fetcher = TokenAwareFetcher(
            page = hostsPage(sid = pageToken),
            freshSid = pageToken,
            profile = TCP_PROFILE,
        )

        val result = OpenVpnProfileDownloader(fetcher).download(
            connection(storedSid),
            useUdp = false,
            mirrorBaseUrls = listOf(mirror),
        )

        assertTrue("expected a downloaded profile, got $result", result.orEmpty().contains("remote 203.0.113.53 443"))
        assertEquals("the hosts page must be read exactly once", 1, fetcher.pageFetches)
        assertTrue(
            "the retry must carry the fresh token: ${fetcher.urls}",
            fetcher.urls.any { it.contains("sid=$pageToken") },
        )
        assertTrue(
            "a young stored token must be tried first: ${fetcher.urls}",
            fetcher.urls.first().contains("sid=$storedSid"),
        )
    }

    /**
     * A generation that is clearly past its lifetime is refreshed BEFORE it is
     * tried, so the repair costs one page read plus one retry instead of a
     * doomed request first. That ordering is what keeps it inside one attempt
     * on a link where a single request can take 12 s.
     */
    @Test
    fun refreshesBeforeTryingAGenerationThatIsClearlyOld() = runBlocking {
        val mirror = "http://150.40.105.20:16086"
        val pageToken = pageSid()
        val fetcher = TokenAwareFetcher(
            page = hostsPage(sid = pageToken),
            freshSid = pageToken,
            profile = TCP_PROFILE,
        )

        val result = OpenVpnProfileDownloader(fetcher).download(
            connection(STALE_SID),
            useUdp = false,
            mirrorBaseUrls = listOf(mirror),
        )

        assertTrue(result.orEmpty().contains("remote 203.0.113.53 443"))
        assertTrue(
            "the page must be read before the old token is sent: ${fetcher.urls}",
            fetcher.urls.first().contains("/en/"),
        )
        assertTrue(
            "the old token must not be sent at all when the refresh worked: ${fetcher.urls}",
            fetcher.urls.none { it.contains("sid=$STALE_SID") },
        )
    }

    /**
     * The age check is only a hint. When the page cannot supply a token the old
     * one still gets its turn, so a change to the `sid` encoding — or a skewed
     * device clock — can cost time but never correctness.
     */
    @Test
    fun stillTriesAnOldTokenWhenThePageCannotHelp() = runBlocking {
        val mirror = "http://150.40.105.20:16086"
        val pageToken = pageSid()
        val fetcher = TokenAwareFetcher(
            page = hostsPage(sid = pageToken, ip = "198.51.100.7"),
            freshSid = pageToken,
            profile = TCP_PROFILE,
        )

        val result = OpenVpnProfileDownloader(fetcher).download(
            connection(STALE_SID),
            useUdp = false,
            mirrorBaseUrls = listOf(mirror),
        )

        assertNull(result)
        assertTrue(
            "the old token must still be tried as a last resort: ${fetcher.urls}",
            fetcher.urls.any { it.contains("sid=$STALE_SID") },
        )
    }

    /**
     * One page load re-arms the whole sweep: the second server sharing the same
     * stale generation is served from the cached page, not another 7 s fetch.
     */
    @Test
    fun reusesOnePageLoadForEveryServerInTheSweep() = runBlocking {
        val mirror = "http://150.40.105.20:16086"
        val pageToken = pageSid()
        val fetcher = TokenAwareFetcher(
            page = hostsPage(sid = pageToken),
            freshSid = pageToken,
            profile = TCP_PROFILE,
        )
        val downloader = OpenVpnProfileDownloader(fetcher)

        val first = downloader.download(connection(), useUdp = false, mirrorBaseUrls = listOf(mirror))
        val second = downloader.download(connection(), useUdp = false, mirrorBaseUrls = listOf(mirror))

        assertTrue(first.orEmpty().contains("remote 203.0.113.53 443"))
        assertTrue(second.orEmpty().contains("remote 203.0.113.53 443"))
        assertEquals("the page must be parsed once for both servers", 1, fetcher.pageFetches)
    }

    /**
     * A server that has rotated off the front page has no downloadable profile,
     * and the downloader must say so instead of looping on page reads.
     */
    @Test
    fun givesUpWhenThePageNoLongerListsTheServer() = runBlocking {
        val mirror = "http://150.40.105.20:16086"
        val pageToken = pageSid()
        val fetcher = TokenAwareFetcher(
            page = hostsPage(sid = pageToken, ip = "198.51.100.7"),
            freshSid = pageToken,
            profile = TCP_PROFILE,
        )

        val result = OpenVpnProfileDownloader(fetcher).download(
            connection(),
            useUdp = false,
            mirrorBaseUrls = listOf(mirror),
        )

        assertNull(result)
        assertEquals("one page read is enough to conclude the server is absent", 1, fetcher.pageFetches)
    }

    /**
     * The endpoint returns its UDP template even for a `udp=0` request (probe
     * 2026-11-25: `proto udp` + `remote public-vpn-75.opengw.net 443`), so a
     * TCP download must not be refused for the profile's own `proto` line. The
     * query port selects the listener and `OpenVpnConfigTransform.forTransport`
     * rewrites proto/port for the transport the user actually dials.
     */
    @Test
    fun acceptsTheUdpTemplateTheEndpointReturnsForATcpRequest() = runBlocking {
        val fetcher = FakeFetcher(
            """
            client
            dev tun
            proto udp
            remote public-vpn-53.opengw.net 443
            nobind
            """.trimIndent(),
        )

        val result = OpenVpnProfileDownloader(fetcher).download(connection(), useUdp = false)

        assertTrue(
            "a TCP request must accept the endpoint's UDP template: $result",
            result.orEmpty().contains("remote 203.0.113.53 443"),
        )
        assertTrue(fetcher.requestedUrl.orEmpty().contains("udp=0"))
    }

    @Test
    fun buildsUdpDownloadAndRejectsWrongRemotePort() = runBlocking {
        val fetcher = FakeFetcher(
            """
            client
            dev tun
            proto udp
            remote public-vpn-53.opengw.net 443
            nobind
            """.trimIndent(),
        )

        val result = OpenVpnProfileDownloader(fetcher).download(connection(), useUdp = true)

        assertNull(result)
        assertTrue(fetcher.requestedUrl.orEmpty().contains("udp=1"))
        assertTrue(fetcher.requestedUrl.orEmpty().contains("port=1195"))
    }

    @Test
    fun refusesMissingOrMalformedDownloadIdentityBeforeNetworkRequest() = runBlocking {
        val fetcher = FakeFetcher(TCP_PROFILE)
        val conn = connection().apply { openVpnDownloadSid = "not-a-number" }

        assertNull(OpenVpnProfileDownloader(fetcher).download(conn, useUdp = false))
        assertEquals(null, fetcher.requestedUrl)
    }

    private companion object {
        /** A generation from the 2026-11-24 collection: well past its lifetime. */
        const val STALE_SID = "1795539088851"

        const val TCP_PROFILE = """
            client
            dev tun
            proto tcp-client
            remote public-vpn-53.opengw.net 443
            nobind
        """

        /** What the endpoint returns for a token it no longer honours. */
        const val OBJECT_MOVED =
            "<html><head><title>Object moved</title></head><body>" +
                "<h2>Object moved to <a href=\"/\">here</a>.</h2></body></html>"

        /**
         * A minimal hosts page in the shape the parser requires: the id'd
         * table, the header cells it keys columns from, and a `do_openvpn`
         * link carrying `sid`/`hid`/`fqdn`.
         */
        fun hostsPage(
            sid: String,
            ip: String = "203.0.113.53",
            host: String = "public-vpn-53.opengw.net",
        ): String = """
            <html><body>
            <table border='1' id='vg_hosts_table_id'>
            <tr>
            <td class='vg_table_header'><b>Country</b><BR>(Physical location)</td>
            <td class='vg_table_header'><b>DDNS hostname<BR>IP Address</b></td>
            <td class='vg_table_header'><b>SSL-VPN</b></td>
            <td class='vg_table_header'><b>OpenVPN</b></td>
            </tr>
            <tr>
            <td class='vg_table_row_1'><img src='../images/flags/JP.png' /><br>Japan</td>
            <td class='vg_table_row_1'><b><span>$host</span></b><br><span>$ip</span></td>
            <td class='vg_table_row_1'>TCP:443</td>
            <td class='vg_table_row_1'><a href='/common/do_openvpn.aspx?fqdn=$host&amp;ip=$ip&amp;tcp=443&amp;udp=1195&amp;sid=$sid&amp;hid=24992073'>OpenVPN Config file</a></td>
            </tr>
            </table>
            </body></html>
        """.trimIndent()
    }
}
