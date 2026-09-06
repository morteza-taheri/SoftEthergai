package vn.unlimit.vpngate.data.remote

import vn.unlimit.vpngate.data.model.CollectorLog
import vn.unlimit.vpngate.data.model.VpnUtil
import vn.unlimit.vpngate.parser.VpnGateHtmlParser
import java.net.URI

/**
 * The three live sources of the Mode B collector (§23): the VPN Gate
 * main HTML page, the official iPhone CSV API, and official mirrors
 * discovered from /en/sites.aspx.
 */
object VpnGateUrls {
    const val MAIN_URL = "https://www.vpngate.net/en/"
    const val API_URL = "https://www.vpngate.net/api/iphone/"
    const val MIRRORS_URL = "https://www.vpngate.net/en/sites.aspx"
    const val MAX_MIRRORS = 10
}

class VpnGateHtmlSource(private val fetcher: HttpFetcher) {
    suspend fun fetch(): String? = fetcher.get(VpnGateUrls.MAIN_URL)
}

class VpnGateApiSource(private val fetcher: HttpFetcher) {
    suspend fun fetch(): String? = fetcher.get(VpnGateUrls.API_URL)
}

class VpnGateMirrorSource(private val fetcher: HttpFetcher) {
    /**
     * Discover official mirrors — port of the Python oracle's
     * discover_mirrors: only IP:port or *.opengw.net hosts, capped
     * at [VpnGateUrls.MAX_MIRRORS].
     */
    suspend fun discoverMirrors(): List<String> {
        val html = fetcher.get(VpnGateUrls.MIRRORS_URL) ?: return emptyList()

        val doc = VpnGateHtmlParser.makeSoup(html)
        val mirrors = mutableListOf<String>()
        val ipHost = Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+(?::\\d+)?$")

        for (a in doc.getElementsByTag("a")) {
            val href = VpnUtil.clean(a.attr("href"))

            if (!href.startsWith("http://") && !href.startsWith("https://")) {
                continue
            }

            val host = try {
                URI(href).authority?.lowercase() ?: ""
            } catch (e: Exception) {
                ""
            }

            if (host.isEmpty()) continue
            if ("vpngate.net" in host) continue

            // Ignore unrelated university pages such as
            // www.tsukuba.ac.jp/english/.
            if ("tsukuba.ac.jp" in host) continue

            // VPN Gate mirror candidates are usually IP:PORT or
            // dedicated opengw hosts.
            if (ipHost.matches(host) || "opengw.net" in host) {
                if (href !in mirrors) {
                    mirrors.add(href)
                }
            }
        }

        CollectorLog.d("Mirrors discovered: ${mirrors.size}")
        return mirrors.take(VpnGateUrls.MAX_MIRRORS)
    }

    suspend fun fetchMirror(url: String): String? = fetcher.get(url)
}
