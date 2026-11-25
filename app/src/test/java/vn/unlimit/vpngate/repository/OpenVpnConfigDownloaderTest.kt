package vn.unlimit.vpngate.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.data.remote.HttpFetcher
import vn.unlimit.vpngate.data.remote.HttpResponse
import vn.unlimit.vpngate.data.remote.RedirectPolicy

/**
 * Stage G-7: the on-demand OpenVPN profile download.
 *
 * These tests are offline by construction — the fetch boundary is a fake — so
 * they assert the *contract* of the downloader (which URL is asked for, which
 * base is tried next, what counts as a profile) rather than live behaviour.
 */
class OpenVpnConfigDownloaderTest {

    private class RecordingFetcher(
        private val responses: Map<String, HttpResponse?>,
        private val default: HttpResponse? = null,
    ) : HttpFetcher {
        val requested = mutableListOf<String>()
        val policies = mutableListOf<RedirectPolicy?>()

        override suspend fun get(url: String, redirectPolicy: RedirectPolicy?): HttpResponse? {
            requested.add(url)
            policies.add(redirectPolicy)
            return responses[url] ?: default
        }
    }

    private fun ok(url: String, body: String) = HttpResponse(
        requestedUrl = url,
        finalUrl = url,
        status = 200,
        contentType = "application/x-openvpn-profile",
        body = body,
    )

    private fun html(url: String) = HttpResponse(
        requestedUrl = url,
        finalUrl = url,
        status = 200,
        contentType = "text/html",
        body = "<!DOCTYPE html><html><body>Invalid request</body></html>",
    )

    private val profile = """
        client
        dev tun
        proto tcp
        remote 219.100.37.206 443
        resolv-retry infinite
        nobind
    """.trimIndent()

    private val request = OpenVpnConfigDownloader.Request(
        sid = "1787849731438",
        hid = "24869517",
        hostName = "public-vpn-219",
        tcpPort = 443,
        udpPort = 0,
    )

    @Test
    fun `builds the documented endpoint and returns the profile`() = runBlocking {
        val url = "https://www.vpngate.net/common/openvpn_download.aspx" +
            "?sid=1787849731438&udp=0&host=public-vpn-219.opengw.net&port=443" +
            "&hid=24869517/name.ovpn"
        val fetcher = RecordingFetcher(mapOf(url to ok(url, profile)))

        val downloader = OpenVpnConfigDownloader(
            fetcher = fetcher,
            bases = { listOf("https://www.vpngate.net") },
        )

        val result = downloader.download(request, preferUdp = false)

        assertEquals(profile, result)
        assertEquals(listOf(url), fetcher.requested)
        // The download may not wander off the host it started on.
        assertEquals(setOf("www.vpngate.net"), fetcher.policies.single()?.allowedAuthorities)
    }

    @Test
    fun `prefers the udp variant when the server publishes no tcp port`() = runBlocking {
        val url = "https://www.vpngate.net/common/openvpn_download.aspx" +
            "?sid=1&udp=1&host=public-vpn-1.opengw.net&port=1194&hid=2/name.ovpn"
        val fetcher = RecordingFetcher(mapOf(url to ok(url, profile)))

        val downloader = OpenVpnConfigDownloader(
            fetcher = fetcher,
            bases = { listOf("https://www.vpngate.net") },
        )

        val result = downloader.download(
            OpenVpnConfigDownloader.Request("1", "2", "public-vpn-1", tcpPort = 0, udpPort = 1194),
            preferUdp = false,
        )

        assertEquals(profile, result)
        assertTrue(fetcher.requested.single().contains("udp=1"))
    }

    @Test
    fun `moves on to the next base when the first answers with an error page`() = runBlocking {
        val canonical = "https://www.vpngate.net/common/openvpn_download.aspx" +
            "?sid=1787849731438&udp=0&host=public-vpn-219.opengw.net&port=443" +
            "&hid=24869517/name.ovpn"
        val mirror = "http://150.40.105.19:35399/common/openvpn_download.aspx" +
            "?sid=1787849731438&udp=0&host=public-vpn-219.opengw.net&port=443" +
            "&hid=24869517/name.ovpn"

        val fetcher = RecordingFetcher(
            mapOf(
                canonical to html(canonical),
                mirror to ok(mirror, profile),
            ),
        )

        val downloader = OpenVpnConfigDownloader(
            fetcher = fetcher,
            bases = { listOf("https://www.vpngate.net", "http://150.40.105.19:35399") },
        )

        val result = downloader.download(request, preferUdp = false)

        assertEquals(profile, result)
        assertEquals(listOf(canonical, mirror), fetcher.requested)
    }

    @Test
    fun `an html page is never accepted as a profile`() = runBlocking {
        val fetcher = RecordingFetcher(emptyMap(), default = null)
        val downloader = OpenVpnConfigDownloader(
            fetcher = fetcher,
            bases = { listOf("https://www.vpngate.net") },
        )

        assertNull(downloader.download(request, preferUdp = false))

        val htmlOnly = RecordingFetcher(emptyMap())
        val url = "https://www.vpngate.net/common/openvpn_download.aspx" +
            "?sid=1787849731438&udp=0&host=public-vpn-219.opengw.net&port=443" +
            "&hid=24869517/name.ovpn"
        val pageFetcher = object : HttpFetcher {
            override suspend fun get(url: String, redirectPolicy: RedirectPolicy?) =
                html(url)
        }
        assertNull(
            OpenVpnConfigDownloader(pageFetcher, { listOf("https://www.vpngate.net") })
                .download(request, preferUdp = false),
        )
        // Silence the unused-construction warning while keeping the intent explicit.
        assertTrue(htmlOnly.requested.isEmpty())
    }

    @Test
    fun `an incomplete request is never sent`() = runBlocking {
        val fetcher = RecordingFetcher(emptyMap(), default = null)
        val downloader = OpenVpnConfigDownloader(
            fetcher = fetcher,
            bases = { listOf("https://www.vpngate.net") },
        )

        assertNull(
            downloader.download(
                OpenVpnConfigDownloader.Request("", "2", "h", 443, 0),
                preferUdp = false,
            ),
        )
        assertNull(
            downloader.download(
                OpenVpnConfigDownloader.Request("1", "2", "h", 0, 0),
                preferUdp = false,
            ),
        )
        assertTrue("nothing should have been requested", fetcher.requested.isEmpty())
    }

    @Test
    fun `a hostname that already carries a dot is not given the opengw suffix`() = runBlocking {
        val url = "https://www.vpngate.net/common/openvpn_download.aspx" +
            "?sid=9&udp=0&host=custom.example.org&port=443&hid=8/name.ovpn"
        val fetcher = RecordingFetcher(mapOf(url to ok(url, profile)))

        val downloader = OpenVpnConfigDownloader(
            fetcher = fetcher,
            bases = { listOf("https://www.vpngate.net") },
        )

        assertEquals(
            profile,
            downloader.download(
                OpenVpnConfigDownloader.Request("9", "8", "custom.example.org", 443, 0),
                preferUdp = false,
            ),
        )
    }

    /**
     * `sid` is a PAGE-GENERATION token, not a per-server identity: it is a
     * timestamp shared by every row of one page. A stored `sid` therefore goes
     * stale as soon as the page regenerates, and the endpoint then answers 302
     * to "/" instead of a profile. `hid` is stable, so the downloader re-reads
     * the page once and retries with the `sid` published next to that `hid`.
     */
    @Test
    fun `a stale sid is replaced by the sid published on the current page`() = runBlocking {
        val staleUrl = "https://www.vpngate.net/common/openvpn_download.aspx" +
            "?sid=1787849731438&udp=0&host=public-vpn-219.opengw.net&port=443" +
            "&hid=24869517/name.ovpn"
        val freshUrl = "https://www.vpngate.net/common/openvpn_download.aspx" +
            "?sid=1795539642828&udp=0&host=public-vpn-219.opengw.net&port=443" +
            "&hid=24869517/name.ovpn"
        val pageUrl = "https://www.vpngate.net/en/"
        val page = "<html><body><a href='do_openvpn.aspx?fqdn=public-vpn-219.opengw.net" +
            "&ip=219.100.37.206&tcp=443&udp=0&sid=1795539642828&hid=24869517'>cfg</a>" +
            "</body></html>"

        val fetcher = RecordingFetcher(
            mapOf(
                staleUrl to html(staleUrl),
                pageUrl to ok(pageUrl, page),
                freshUrl to ok(freshUrl, profile),
            ),
        )

        val downloader = OpenVpnConfigDownloader(
            fetcher = fetcher,
            bases = { listOf("https://www.vpngate.net") },
        )

        assertEquals(profile, downloader.download(request, preferUdp = false))
        assertTrue(
            "the page must be re-read to obtain a fresh sid",
            fetcher.requested.contains(pageUrl),
        )
        assertTrue("the retry must use the fresh sid", fetcher.requested.contains(freshUrl))
    }

    @Test
    fun `a page with no sid for this hid still yields no profile`() = runBlocking {
        val staleUrl = "https://www.vpngate.net/common/openvpn_download.aspx" +
            "?sid=1787849731438&udp=0&host=public-vpn-219.opengw.net&port=443" +
            "&hid=24869517/name.ovpn"
        val pageUrl = "https://www.vpngate.net/en/"
        val page = "<html><body><a href='do_openvpn.aspx?sid=1&hid=99999'>other</a>" +
            "</body></html>"

        val fetcher = RecordingFetcher(
            mapOf(staleUrl to html(staleUrl), pageUrl to ok(pageUrl, page)),
        )

        val downloader = OpenVpnConfigDownloader(
            fetcher = fetcher,
            bases = { listOf("https://www.vpngate.net") },
        )

        assertNull(downloader.download(request, preferUdp = false))
    }
}
