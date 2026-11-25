package vn.unlimit.vpngate.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Resolves an SSTP server hostname to an IPv4 address the SSTP stack can dial.
 *
 * ## Why the stack cannot just do this itself
 *
 * `SSLTerminal` creates its socket with `InetSocketAddress(hostname, port)`,
 * which asks the **system** resolver. On a network that poisons or filters DNS
 * (the environment this app is built for) `*.opengw.net` can resolve to a
 * black-hole address or to nothing at all, and the connection fails before a
 * single SSTP byte is sent. The TLS layer still needs the *hostname* for SNI
 * and certificate verification, so replacing the hostname everywhere is not an
 * option — only the connect target may change.
 *
 * This resolver therefore returns an address, the app writes it into the
 * stack's own preference, and `SSLTerminal` uses it for `connect()` while
 * keeping the hostname for `SSLEngine`/`verify`. If resolution fails the
 * preference is cleared and the old behaviour is preserved exactly.
 *
 * ## Order
 *
 *  1. the system resolver, accepting only a **global** IPv4 address (a poisoned
 *     answer is usually a private/loopback address, which is rejected);
 *  2. DNS-over-HTTPS, which the censor cannot rewrite, tried against two
 *     independent providers.
 *
 * The JSON parsing is a pure function so it is unit-testable on the JVM without
 * `org.json` (an Android stub in unit tests) or a network.
 */
object SstpHostResolver {

    /** JSON API endpoints; both answer `application/dns-json`. */
    private val DOH_ENDPOINTS = listOf(
        "https://1.1.1.1/dns-query",
        "https://dns.google/resolve",
    )

    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 4_000
    private const val MAX_BODY_BYTES = 16 * 1024

    /** Rate-limits the "DoH also failed" warning so a flapping connect cannot spam the log. */
    private val warned = AtomicBoolean(false)

    /**
     * Resolve [hostname] to a global IPv4 literal, or null when neither the
     * system resolver nor DoH produced one. Never throws.
     */
    suspend fun resolve(hostname: String): String? = withContext(Dispatchers.IO) {
        val host = hostname.trim()
        if (host.isEmpty() || isIpLiteral(host)) return@withContext null
        systemLookup(host) ?: dohLookup(host).also { address ->
            if (address == null && warned.compareAndSet(false, true)) {
                android.util.Log.w(
                    "SstpHostResolver",
                    "No address for $host from system DNS or DoH; the stack will use its own resolver",
                )
            }
        }
    }

    /** True for a literal address, which must not be re-resolved. */
    fun isIpLiteral(value: String): Boolean =
        value.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")) || value.contains(':')

    /**
     * A usable public IPv4 address: not loopback, link-local, site-local,
     * multicast or the unspecified address.
     *
     * A poisoned DNS answer is typically one of those, so rejecting them turns
     * a hijack into "no answer" instead of "connect to 10.x.x.x and fail".
     */
    fun isGlobalAddress(address: InetAddress): Boolean =
        address is Inet4Address &&
            !address.isLoopbackAddress &&
            !address.isLinkLocalAddress &&
            !address.isSiteLocalAddress &&
            !address.isMulticastAddress &&
            !address.isAnyLocalAddress

    /**
     * First A record in a DoH JSON answer, or null.
     *
     * Minimal on purpose: the response shape is
     * `{"Answer":[{"name":"…","type":1,"TTL":…,"data":"1.2.3.4"}]}`. A `type`
     * other than 1 (CNAME, AAAA) is skipped; a malformed body yields null.
     */
    fun parseDnsJson(json: String): String? {
        val answerStart = json.indexOf("\"Answer\"")
        if (answerStart < 0) return null
        val arrayStart = json.indexOf('[', answerStart)
        if (arrayStart < 0) return null
        val arrayEnd = json.indexOf(']', arrayStart)
        if (arrayEnd < 0) return null
        val array = json.substring(arrayStart + 1, arrayEnd)
        for (entry in array.split("},")) {
            val type = Regex("\"type\"\\s*:\\s*(\\d+)").find(entry)?.groupValues?.get(1)?.toIntOrNull()
            if (type != 1) continue
            val data = Regex("\"data\"\\s*:\\s*\"([^\"]+)\"").find(entry)?.groupValues?.get(1)
            val candidate = data?.trim() ?: continue
            val parsed = runCatching { InetAddress.getByName(candidate) }.getOrNull() ?: continue
            if (isGlobalAddress(parsed)) return parsed.hostAddress
        }
        return null
    }

    private fun systemLookup(host: String): String? = runCatching {
        InetAddress.getAllByName(host)
            .firstOrNull { isGlobalAddress(it) }
            ?.hostAddress
    }.getOrNull()

    private fun dohLookup(host: String): String? {
        for (endpoint in DOH_ENDPOINTS) {
            val body = httpsGet("$endpoint?name=$host&type=A") ?: continue
            parseDnsJson(body)?.let { return it }
        }
        return null
    }

    private fun httpsGet(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/dns-json")
            }
            if (connection.responseCode !in 200..299) return null
            BufferedInputStream(connection.inputStream).use { input ->
                val buffer = ByteArray(MAX_BODY_BYTES)
                var total = 0
                while (total < buffer.size) {
                    val read = input.read(buffer, total, buffer.size - total)
                    if (read <= 0) break
                    total += read
                }
                String(buffer, 0, total, Charsets.UTF_8)
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }
}
