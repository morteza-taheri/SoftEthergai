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
    const val MAX_MIRRORS = 12

    /**
     * Active verified seed mirrors of vpngate.net: used as immediate fallbacks
     * when the primary vpngate.net domain is unreachable, censored, or times out.
     */
    val SEED_MIRRORS = listOf(
        "http://150.40.105.10:46711/",
        "http://150.40.105.24:38827/",
        "http://160.251.62.107:46080/",
        "http://62.133.35.246:2265/",
        "http://150.40.105.3:24869/",
        "http://150.40.105.17:50406/",
    )
}

class VpnGateHtmlSource(private val fetcher: HttpFetcher) {
    suspend fun fetch(): String? = fetcher.get(VpnGateUrls.MAIN_URL)

    suspend fun fetchFromMirror(mirrorUrl: String): String? {
        val base = mirrorUrl.trim().removeSuffix("/").removeSuffix("/en")
        return fetcher.get("$base/en/")
    }
}

class VpnGateApiSource(private val fetcher: HttpFetcher) {
    suspend fun fetch(): String? = fetcher.get(VpnGateUrls.API_URL)

    suspend fun fetchFromMirror(mirrorUrl: String): String? {
        val base = mirrorUrl.trim().removeSuffix("/").removeSuffix("/en")
        return fetcher.get("$base/api/iphone/")
    }
}

class VpnGateMirrorSource(private val fetcher: HttpFetcher) {
    /**
     * Discover official mirrors — queries the primary site and seed mirrors
     * for active mirror sites (/en/sites.aspx) and merges them with seed mirrors.
     */
    suspend fun discoverMirrors(): List<String> {
        val mirrors = mutableListOf<String>()
        val ipHost = Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+(?::\\d+)?$")

        fun parseMirrorsFromHtml(html: String) {
            val doc = VpnGateHtmlParser.makeSoup(html)
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

                // Ignore unrelated university pages
                if ("tsukuba.ac.jp" in host) continue

                // VPN Gate mirror candidates are IP:PORT or dedicated opengw hosts
                if (ipHost.matches(host) || "opengw.net" in host) {
                    val normalized = if (href.endsWith("/")) href else "$href/"
                    if (normalized !in mirrors) {
                        mirrors.add(normalized)
                    }
                }
            }
        }

        // 1. Try primary dynamic mirror directory
        val primaryHtml = fetcher.get(VpnGateUrls.MIRRORS_URL)
        if (!primaryHtml.isNullOrBlank()) {
            parseMirrorsFromHtml(primaryHtml)
        }

        // 2. If primary dynamic mirror list was blocked or returned no mirrors, query seed mirrors
        if (mirrors.isEmpty()) {
            for (seed in VpnGateUrls.SEED_MIRRORS) {
                val base = seed.removeSuffix("/")
                val seedSitesHtml = fetcher.get("$base/en/sites.aspx") ?: fetcher.get("$base/sites.aspx")
                if (!seedSitesHtml.isNullOrBlank()) {
                    parseMirrorsFromHtml(seedSitesHtml)
                    if (mirrors.isNotEmpty()) break
                }
            }
        }

        // 3. Always append verified seed mirrors as reliable backups
        for (seed in VpnGateUrls.SEED_MIRRORS) {
            val normalized = if (seed.endsWith("/en/")) seed else if (seed.endsWith("/")) "${seed}en/" else "$seed/en/"
            if (seed !in mirrors && normalized !in mirrors) {
                mirrors.add(normalized)
            }
        }

        CollectorLog.d("Mirrors discovered: ${mirrors.size}")
        return mirrors.take(VpnGateUrls.MAX_MIRRORS)
    }

    suspend fun fetchMirror(url: String): String? = fetcher.get(url)
}
