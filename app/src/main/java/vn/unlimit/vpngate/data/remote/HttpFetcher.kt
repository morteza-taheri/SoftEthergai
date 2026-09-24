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
import java.net.URI
import java.util.concurrent.TimeUnit

/** Minimal fetch boundary so parsers stay unit-testable offline. */
interface HttpFetcher {
    suspend fun get(url: String): String?
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
        .followRedirects(true)
        .build(),
    private val requestDelayMs: Long = 0,
) : HttpFetcher {
    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/145.0 Safari/537.36"
    }

    override suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        CollectorLog.d("GET $url")
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                )
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Connection", "keep-alive")
                .build()

            val body = client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()
                } else {
                    CollectorLog.d("HTTP ${response.code} for $url")
                    null
                }
            }
            if (requestDelayMs > 0) delay(requestDelayMs)
            body
        } catch (e: IOException) {
            CollectorLog.d("Request failed: ${e.message}")
            null
        }
    }
}
