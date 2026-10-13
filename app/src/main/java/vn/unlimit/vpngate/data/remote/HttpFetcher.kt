package vn.unlimit.vpngate.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import vn.unlimit.vpngate.data.model.CollectorLog
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * One HTTP exchange, including where it ACTUALLY ended up.
 *
 * Stage 2C-A: the previous fetcher returned a bare body, so a redirect
 * could move a canonical or mirror fetch to any host and the origin was
 * simply lost. The requested URL, the final URL, the status, the content
 * type and the followed/rejected redirect chain are now all part of the
 * result, so acceptance can be decided on evidence instead of a keyword.
 */
data class HttpResponse(
    val requestedUrl: String,
    val finalUrl: String,
    val status: Int,
    val contentType: String?,
    val body: String?,
    val redirectChain: List<String> = emptyList(),
    val redirectRejected: Boolean = false,
    val redirectRejectionReason: String? = null,
)

/** Minimal fetch boundary so parsers stay unit-testable offline. */
interface HttpFetcher {
    /**
     * @param redirectPolicy when present, only hops this policy allows are
     *   followed; a hop outside it ends the chain and the response is
     *   reported as rejected instead of silently accepted.
     */
    suspend fun get(
        url: String,
        redirectPolicy: RedirectPolicy? = null,
    ): HttpResponse?
}

/**
 * Resilient DNS provider that prevents DNS poisoning/filtering of
 * vpngate.net domain (common in censored environments like Iran).
 * Falls back to known genuine server IP pool when DNS fails or returns
 * local/poisoned redirect IPs.
 */
class ResilientDns : Dns {
    companion object {
        private val VPNGATE_FALLBACK_IPS = listOf(
            "130.158.75.39",
            "130.158.75.35",
            "130.158.75.42",
            "130.158.75.44",
            "130.158.75.40",
            "130.158.75.38",
            "130.158.75.36",
            "130.158.75.48",
        )
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val lower = hostname.lowercase()
        return try {
            val systemAddresses = Dns.SYSTEM.lookup(hostname)
            // Filter out poisoned / private redirect IP addresses (e.g. 10.x.x.x)
            val valid = systemAddresses.filterNot { it.isSiteLocalAddress || it.isLoopbackAddress || it.isAnyLocalAddress }
            if (valid.isNotEmpty()) {
                valid
            } else if (lower == "www.vpngate.net" || lower == "vpngate.net") {
                VPNGATE_FALLBACK_IPS.mapNotNull { ip ->
                    runCatching { InetAddress.getByName(ip) }.getOrNull()
                }
            } else {
                systemAddresses
            }
        } catch (e: Exception) {
            if (lower == "www.vpngate.net" || lower == "vpngate.net") {
                VPNGATE_FALLBACK_IPS.mapNotNull { ip ->
                    runCatching { InetAddress.getByName(ip) }.getOrNull()
                }
            } else {
                throw e
            }
        }
    }
}

class OkHttpFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(ResilientDns())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        // Redirects are followed by hand so every hop can be checked against
        // the source's own policy. OkHttp must not follow them blindly.
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
    private val requestDelayMs: Long = 0,
) : HttpFetcher {
    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/145.0 Safari/537.36"
    }

    override suspend fun get(
        url: String,
        redirectPolicy: RedirectPolicy?,
    ): HttpResponse? = withContext(Dispatchers.IO) {
        execute(url, redirectPolicy)
    }

    /**
     * One request per hop. Every hop is checked against the source's own
     * policy; a hop outside it ends the chain with the response marked as
     * rejected, so no foreign body can ever be returned.
     */
    private suspend fun execute(url: String, redirectPolicy: RedirectPolicy?): HttpResponse? {
        CollectorLog.d("GET $url")
        val chain = mutableListOf<String>()
        var current = url
        var hops = 0
        val maxHops = redirectPolicy?.maxHops ?: RedirectPolicy.DEFAULT_MAX_HOPS

        while (true) {
            var followTo: String? = null

            val response: HttpResponse = try {
                val request = Request.Builder()
                    .url(current)
                    .header("User-Agent", USER_AGENT)
                    .header(
                        "Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    )
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Connection", "keep-alive")
                    .build()

                client.newCall(request).execute().use { raw ->
                    val status = raw.code
                    val location = raw.header("Location")
                    val isRedirect = status in 300..399

                    if (isRedirect && location.isNullOrBlank()) {
                        HttpResponse(url, current, status, null, null, chain.toList())
                    } else if (isRedirect) {
                        val next = raw.request.url.resolve(location.orEmpty())?.toString()
                        if (next == null) {
                            HttpResponse(
                                url, current, status, null, null, chain.toList(),
                                redirectRejected = true,
                                redirectRejectionReason = "unresolvable redirect target",
                            )
                        } else {
                            val rejection = redirectPolicy?.rejectionReason(current, next)
                            if (rejection != null) {
                                CollectorLog.d("Redirect rejected for $current -> $next: $rejection")
                                HttpResponse(
                                    requestedUrl = url,
                                    finalUrl = current,
                                    status = status,
                                    contentType = null,
                                    body = null,
                                    redirectChain = chain.toList(),
                                    redirectRejected = true,
                                    redirectRejectionReason = rejection,
                                )
                            } else {
                                hops++
                                if (hops > maxHops) {
                                    HttpResponse(
                                        requestedUrl = url,
                                        finalUrl = current,
                                        status = status,
                                        contentType = null,
                                        body = null,
                                        redirectChain = chain.toList(),
                                        redirectRejected = true,
                                        redirectRejectionReason = "too many redirects",
                                    )
                                } else {
                                    CollectorLog.d("Redirect $current -> $next")
                                    chain.add(next)
                                    followTo = next
                                    HttpResponse(
                                        requestedUrl = url,
                                        finalUrl = current,
                                        status = status,
                                        contentType = null,
                                        body = null,
                                        redirectChain = chain.toList(),
                                    )
                                }
                            }
                        }
                    } else {
                        val bodyText = if (raw.isSuccessful) raw.body?.string() else null
                        if (!raw.isSuccessful) {
                            CollectorLog.d("HTTP ${raw.code} for $current")
                        }
                        HttpResponse(
                            requestedUrl = url,
                            finalUrl = current,
                            status = status,
                            contentType = raw.body?.contentType()?.toString(),
                            body = bodyText,
                            redirectChain = chain.toList(),
                        )
                    }
                }
            } catch (e: IOException) {
                CollectorLog.d("Request failed: ${e.message}")
                return null
            }

            val next = followTo
            if (next != null) {
                current = next
                continue
            }

            if (requestDelayMs > 0) delay(requestDelayMs)
            return response
        }
    }
}
