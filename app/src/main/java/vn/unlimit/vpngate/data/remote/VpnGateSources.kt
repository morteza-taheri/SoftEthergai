package vn.unlimit.vpngate.data.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    const val MAIN_HTTP_URL = "http://www.vpngate.net/en/"
    const val API_URL = "https://www.vpngate.net/api/iphone/"
    const val API_HTTP_URL = "http://www.vpngate.net/api/iphone/"
    const val MIRRORS_URL = "https://www.vpngate.net/en/sites.aspx"
    const val MIRRORS_HTTP_URL = "http://www.vpngate.net/en/sites.aspx"
    const val MAX_MIRRORS = 12

    // Reliable official IP mirrors provided by VPN Gate project to bypass DNS/SNI censorship
    val BOOTSTRAP_MIRRORS = listOf(
        "http://150.40.105.19:35399",
        "http://150.40.105.6:11803",
        "http://150.40.105.11:4917",
        "http://150.40.105.23:64629",
        "http://194.156.89.134:47774",
        "http://121.186.186.97:40587",
    )
}

private suspend fun raceFetch(
    urls: List<String>,
    fetcher: HttpFetcher,
    predicate: (String) -> Boolean,
    timeoutMs: Long = 12_000L,
): String? = coroutineScope {
    val result = CompletableDeferred<String>()
    val jobs = urls.distinct().map { url ->
        launch(Dispatchers.IO) {
            runCatching {
                val body = fetcher.get(url)
                if (body != null && predicate(body)) {
                    if (result.complete(body)) {
                        CollectorLog.d("Fastest successful response from: $url")
                    }
                }
            }
        }
    }

    launch {
        jobs.joinAll()
        if (!result.isCompleted) {
            result.completeExceptionally(NoSuchElementException())
        }
    }

    try {
        withTimeoutOrNull(timeoutMs) { result.await() }
    } catch (e: Throwable) {
        null
    } finally {
        jobs.forEach { it.cancel() }
    }
}

class VpnGateHtmlSource(private val fetcher: HttpFetcher) {
    suspend fun fetch(extraCandidates: List<String> = emptyList()): String? {
        val candidates = buildList {
            add(VpnGateUrls.MAIN_URL)
            add(VpnGateUrls.MAIN_HTTP_URL)
            addAll(extraCandidates)
            VpnGateUrls.BOOTSTRAP_MIRRORS.forEach { mirror ->
                val base = mirror.removeSuffix("/en").removeSuffix("/en/").trimEnd('/')
                add("$base/en/")
            }
        }
        return raceFetch(
            candidates,
            fetcher,
            predicate = { it.contains("vpngate_main_table") || it.contains("OpenVPN") || it.contains("SoftEther") },
            timeoutMs = 12_000L,
        )
    }
}

class VpnGateApiSource(private val fetcher: HttpFetcher) {
    suspend fun fetch(extraCandidates: List<String> = emptyList()): String? {
        val candidates = buildList {
            add(VpnGateUrls.API_URL)
            add(VpnGateUrls.API_HTTP_URL)
            addAll(extraCandidates)
            VpnGateUrls.BOOTSTRAP_MIRRORS.forEach { mirror ->
                val base = mirror.removeSuffix("/en").removeSuffix("/en/").trimEnd('/')
                add("$base/api/iphone/")
            }
        }
        return raceFetch(
            candidates,
            fetcher,
            predicate = { it.contains("#HostName") || it.contains("*vpn_servers") },
            timeoutMs = 12_000L,
        )
    }
}

class VpnGateMirrorSource(private val fetcher: HttpFetcher) {
    /**
     * Discover official mirrors — port of the Python oracle's
     * discover_mirrors: only IP:port or *.opengw.net hosts, capped
     * at [VpnGateUrls.MAX_MIRRORS].
     */
    suspend fun discoverMirrors(cachedMirrors: List<String> = emptyList()): List<String> {
        val candidateSitesUrls = buildList {
            add(VpnGateUrls.MIRRORS_URL)
            add(VpnGateUrls.MIRRORS_HTTP_URL)
            cachedMirrors.forEach { mirror ->
                val base = mirror.removeSuffix("/en").removeSuffix("/en/").trimEnd('/')
                add("$base/en/sites.aspx")
            }
            VpnGateUrls.BOOTSTRAP_MIRRORS.forEach { mirror ->
                val base = mirror.removeSuffix("/en").removeSuffix("/en/").trimEnd('/')
                add("$base/en/sites.aspx")
            }
        }

        val html = raceFetch(
            candidateSitesUrls,
            fetcher,
            predicate = { it.contains("vpngate.net Mirror Sites") || it.contains("sites.aspx") },
            timeoutMs = 8_000L,
        )

        val discovered = mutableListOf<String>()
        if (html != null) {
            val doc = VpnGateHtmlParser.makeSoup(html)
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
                    val cleanHref = href.trimEnd('/')
                    if (cleanHref !in discovered) {
                        discovered.add(cleanHref)
                    }
                }
            }
        }

        val combined = (discovered + cachedMirrors + VpnGateUrls.BOOTSTRAP_MIRRORS)
            .distinct()
            .take(VpnGateUrls.MAX_MIRRORS)

        CollectorLog.d("Mirrors available: ${combined.size} (discovered=${discovered.size})")
        return combined
    }

    suspend fun fetchMirror(url: String): String? = fetcher.get(url)
}
