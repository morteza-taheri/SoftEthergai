package vn.unlimit.vpngate.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private fun connection(): VPNGateConnection = VPNGateConnection().apply {
        hostName = "vpn53"
        ip = "203.0.113.53"
        tcpPort = 443
        udpPort = 1195
        openVpnDownloadSid = "1795539088851"
        openVpnDownloadHid = "24992073"
        openVpnDownloadHost = "public-vpn-53.opengw.net"
    }

    @Test
    fun buildsOfficialPerServerTcpDownloadAndAcceptsMatchingProfile() = runBlocking {
        val profile = """
            client
            dev tun
            proto tcp-client
            remote public-vpn-53.opengw.net 443
            nobind
        """.trimIndent()
        val fetcher = FakeFetcher(profile)

        val result = OpenVpnProfileDownloader(fetcher).download(connection(), useUdp = false)

        assertEquals(
            profile.replace("remote public-vpn-53.opengw.net 443", "remote 203.0.113.53 443"),
            result,
        )
        val url = fetcher.requestedUrl.orEmpty()
        assertTrue(url.startsWith("https://www.vpngate.net/common/openvpn_download.aspx?"))
        assertTrue(url.contains("sid=1795539088851"))
        assertTrue(url.contains("udp=0"))
        assertTrue(url.contains("host=public-vpn-53.opengw.net"))
        assertTrue(url.contains("port=443"))
        assertTrue(url.contains("hid=24992073"))
        assertTrue(url.endsWith("vpngate_public-vpn-53.opengw.net_tcp_443.ovpn"))
    }

    @Test
    fun buildsUdpDownloadAndRejectsWrongRemotePort() = runBlocking {
        val profile = """
            client
            dev tun
            proto udp
            remote public-vpn-53.opengw.net 443
            nobind
        """.trimIndent()
        val fetcher = FakeFetcher(profile)

        val result = OpenVpnProfileDownloader(fetcher).download(connection(), useUdp = true)

        assertNull(result)
        assertTrue(fetcher.requestedUrl.orEmpty().contains("udp=1"))
        assertTrue(fetcher.requestedUrl.orEmpty().contains("port=1195"))
    }

    @Test
    fun refusesMissingOrMalformedDownloadIdentityBeforeNetworkRequest() = runBlocking {
        val fetcher = FakeFetcher("client\nproto tcp-client\nremote public-vpn-53.opengw.net 443\n")
        val conn = connection().apply { openVpnDownloadSid = "not-a-number" }

        assertNull(OpenVpnProfileDownloader(fetcher).download(conn, useUdp = false))
        assertEquals(null, fetcher.requestedUrl)
    }
}
