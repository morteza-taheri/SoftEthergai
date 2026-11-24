package vn.unlimit.vpngate.data.remote

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import vn.unlimit.vpngate.data.model.CollectorLog
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.Socket
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

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

/**
 * The one place every collector client gets its knobs.
 *
 * R4b added a *second* client that is bound to a specific [Network]. If the
 * open and the bound client drifted apart — a different timeout, a different
 * DNS provider, redirects followed on one and not the other — then "the same
 * request on the other path" would stop being the same request, and a
 * fallback to open would silently change what is fetched. Building both from
 * this one builder makes that drift impossible.
 */
internal fun collectorClientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
    .dns(ResilientDns())
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    // Redirects are followed by hand so every hop can be checked against
    // the source's own policy. OkHttp must not follow them blindly.
    .followRedirects(false)
    .followSslRedirects(false)

/** The default (open-network) collector client. */
fun defaultCollectorClient(): OkHttpClient = collectorClientBuilder().build()

/**
 * R4b: a collector client whose sockets come from [socketFactory] instead of
 * from the system default.
 *
 * This is the whole of the binding mechanism, and it is deliberately narrow:
 * it changes *which interface the socket is opened on* and nothing else.
 * `Network.bindProcessToNetwork()` is NOT used anywhere — see
 * `SERVER_SOURCE_ARCHITECTURE.md` §7 Q2, which requires per-request binding
 * precisely so that a bound fetch can never capture the rest of the process.
 *
 * Split out from [boundToNetwork] so it can be unit-tested on a plain JVM:
 * `android.net.Network` cannot be constructed there, but a [SocketFactory]
 * can.
 */
fun boundToSocketFactory(socketFactory: SocketFactory): OkHttpClient =
    collectorClientBuilder().socketFactory(socketFactory).build()

/**
 * R4b: same as [boundToSocketFactory], taking the [Network] the caller holds.
 *
 * @param network typically `GlobalVpnTracker.currentVpnNetwork()` — the
 *   system VPN network the tracker's watcher observed.
 */
/**
 * ROUTING-REACHABILITY-AND-EPERM-DIAGNOSTICS (M2, spec §1.6.36) — diagnostics-only
 * wrapper around the VPN-network binding.
 *
 * On the device (DEVICE_VERIFY_CLUSTER_v1.0.43_REPORT.md §8) a VPN-rung fetch failed
 * with `EPERM (Operation not permitted)` while binding a socket to the VPN network.
 * The binding itself is narrow (`Network.getSocketFactory()`, never
 * `bindProcessToNetwork()` — see SERVER_SOURCE_ARCHITECTURE.md §7 Q2), but the failure
 * was previously only visible as a generic "Request failed". These two log lines make
 * the bind attempt and its failure attribute to a network, so the next device run can
 * tell a *construction-time* failure (a stale/revoked `Network`, surfaced here) from a
 * *socket-open-time* EPERM (surfaced later in `OkHttpFetcher.execute`). Control flow
 * is unchanged: every exception is re-thrown.
 */
fun boundToNetwork(network: Network): OkHttpClient =
    try {
        val client = boundToSocketFactory(network.socketFactory)
        CollectorLog.d("[bind] ok network=$network")
        client
    } catch (e: Throwable) {
        CollectorLog.d("[bind] FAILED network=$network errno=${e.message}")
        throw e
    }

/**
 * v1.0.45 (§1.6.38) — an explicit non-VPN client.
 *
 * The old `path=open` was *unbound*: it followed whatever network the OS had as
 * default, which is the app's own tunnel whenever that tunnel is up and the app
 * is not self-excluded. So "open" was never "outside the tunnel". This helper
 * binds to a NOT_VPN + INTERNET network (see
 * [GlobalVpnTracker.currentPhysicalNetwork]) so the request really leaves over
 * Wi-Fi or cellular. The log lines mirror [boundToNetwork] so a device run can
 * tell a construction-time failure from a socket-open-time EPERM.
 */
fun boundToPhysicalNetwork(network: Network): OkHttpClient =
    try {
        val client = boundToSocketFactory(network.socketFactory)
        CollectorLog.d("[bind-physical] ok network=$network")
        client
    } catch (e: Throwable) {
        CollectorLog.d("[bind-physical] FAILED network=$network errno=${e.message}")
        throw e
    }

class OkHttpFetcher(
    private val client: OkHttpClient = defaultCollectorClient(),
    private val requestDelayMs: Long = 0,
) : HttpFetcher {
    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/145.0 Safari/537.36"

        /**
         * BLOCK v1.0.77: the largest response body this client will buffer.
         *
         * The real feeds are well under 1 MB (the emergency CSV is ~0.9 MB, the
         * HTML page ~0.5 MB), so 8 MB leaves ample head-room while keeping a
         * hostile or misconfigured source from exhausting the heap.
         */
        const val MAX_RESPONSE_BYTES: Long = 8L * 1024L * 1024L
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
        // Android's cleartext policy is static and the official VPN Gate mirror
        // directory intentionally rotates IP:port endpoints. A fixed XML
        // allowlist therefore becomes stale. Use a narrowly scoped raw-socket
        // HTTP/1.1 path only for SourceUrl-validated public mirrors; canonical
        // feeds and all HTTPS traffic remain on OkHttp with normal TLS checks.
        if (url.startsWith("http://", ignoreCase = true) &&
            SourceUrl.forMirror(url, SourceKind.DISCOVERED_MIRROR) != null
        ) {
            return executeCleartextMirror(url, redirectPolicy)
        }
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

                callWithCancel(request).use { raw ->
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
                        // BLOCK v1.0.77 (deep-review fix): the body was read with
                        // `raw.body?.string()`, i.e. fully buffered with no cap. A
                        // hostile or broken source could then allocate unbounded
                        // memory inside the app before any parser saw a byte. The
                        // declared length is checked first, and an undeclared body
                        // is peeked at MAX_RESPONSE_BYTES + 1 so an oversize body
                        // is REFUSED rather than silently truncated.
                        val bodyText = if (raw.isSuccessful) {
                            val declared = raw.body?.contentLength() ?: -1L
                            when {
                                declared > MAX_RESPONSE_BYTES -> {
                                    CollectorLog.d(
                                        "Refusing an oversized response: $declared bytes " +
                                            "(cap $MAX_RESPONSE_BYTES) for $current",
                                    )
                                    null
                                }
                                else -> {
                                    val peeked = raw.peekBody(MAX_RESPONSE_BYTES + 1)
                                    if (peeked.contentLength() > MAX_RESPONSE_BYTES) {
                                        CollectorLog.d(
                                            "Refusing an oversized response " +
                                                "(cap $MAX_RESPONSE_BYTES) for $current",
                                        )
                                        null
                                    } else {
                                        peeked.string()
                                    }
                                }
                            }
                        } else {
                            null
                        }
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
                // v1.0.44 (diagnostic placement fix): the EPERM observed on device
                // (DEVICE_VERIFY_CLUSTER_v1.0.43_REPORT.md §8) was produced HERE, at
                // socket-open time — not at boundToNetwork construction. Production
                // never calls boundToNetwork (VpnServerRepository.kt:163 uses
                // boundToSocketFactory directly), so the [bind] diagnostic there never
                // fires on device. The network handle is already carried in e.message
                // ("Binding socket to network N failed: EPERM"); we add only the
                // exception class for attribution. The return below is unchanged.
                val causeClass = e.javaClass.simpleName
                CollectorLog.d("Request failed: [exception=$causeClass] ${e.message}")
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

    /**
     * Fetch a dynamically discovered public HTTP mirror without weakening the
     * app-wide Android cleartext policy. The source identity has already been
     * restricted to an official mirror authority; redirects are checked on
     * every hop and only same-source redirects are followed.
     */
    private suspend fun executeCleartextMirror(
        initialUrl: String,
        redirectPolicy: RedirectPolicy?,
    ): HttpResponse? = withContext(Dispatchers.IO) {
        val chain = mutableListOf<String>()
        var current = initialUrl
        val maxHops = redirectPolicy?.maxHops ?: RedirectPolicy.DEFAULT_MAX_HOPS
        var hops = 0
        while (true) {
            val uri = runCatching { URI(current) }.getOrNull() ?: return@withContext null
            val identity = SourceUrl.forMirror(current, SourceKind.DISCOVERED_MIRROR)
                ?: return@withContext null
            if (uri.scheme != "http" || uri.userInfo != null || uri.host.isNullOrBlank()) {
                return@withContext null
            }
            val port = if (uri.port > 0) uri.port else 80
            val socket = client.socketFactory.createSocket()
            val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            val cancellation = job?.invokeOnCompletion { cause ->
                if (cause is kotlinx.coroutines.CancellationException) runCatching { socket.close() }
            }
            try {
                socket.connect(InetSocketAddress(uri.host, port), 10_000)
                socket.soTimeout = 15_000
                socket.tcpNoDelay = true
                val output = socket.getOutputStream()
                val pathAndQuery = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") +
                    (uri.rawQuery?.let { "?$it" } ?: "")
                val hostHeader = if (port == 80) uri.host else "${uri.host}:$port"
                val request = buildString {
                    append("GET $pathAndQuery HTTP/1.1\\r\\n")
                    append("Host: $hostHeader\\r\\n")
                    append("User-Agent: Mozilla/5.0 (Android) VPNGateCollector\\r\\n")
                    append("Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8\\r\\n")
                    append("Accept-Encoding: gzip\\r\\n")
                    append("Connection: close\\r\\n\\r\\n")
                }
                output.write(request.toByteArray(StandardCharsets.US_ASCII))
                output.flush()

                val input = BufferedInputStream(socket.getInputStream())
                val statusLine = readHttpLine(input) ?: return@withContext null
                val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
                    ?: return@withContext null
                val headers = LinkedHashMap<String, String>()
                while (true) {
                    val line = readHttpLine(input) ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val key = line.substring(0, colon).trim().lowercase()
                        val value = line.substring(colon + 1).trim()
                        headers[key] = if (headers.containsKey(key)) "${headers[key]}, $value" else value
                    }
                }

                if (status in 300..399) {
                    val location = headers["location"]
                    if (location.isNullOrBlank() || hops >= maxHops) {
                        return@withContext HttpResponse(
                            initialUrl, current, status, headers["content-type"], null,
                            chain.toList(), redirectRejected = true,
                            redirectRejectionReason = if (location.isNullOrBlank()) "redirect without Location" else "too many redirects",
                        )
                    }
                    val next = runCatching { uri.resolve(location).toString() }.getOrNull()
                        ?: return@withContext null
                    val rejection = redirectPolicy?.rejectionReason(current, next)
                    if (rejection != null) {
                        return@withContext HttpResponse(
                            initialUrl, current, status, headers["content-type"], null,
                            chain.toList(), redirectRejected = true,
                            redirectRejectionReason = rejection,
                        )
                    }
                    val nextUri = runCatching { URI(next) }.getOrNull()
                        ?: return@withContext null
                    if (nextUri.scheme != "http" || nextUri.host != uri.host ||
                        (if (nextUri.port > 0) nextUri.port else 80) != port
                    ) {
                        return@withContext HttpResponse(
                            initialUrl, current, status, headers["content-type"], null,
                            chain.toList(), redirectRejected = true,
                            redirectRejectionReason = "cleartext mirror redirect changed authority or scheme",
                        )
                    }
                    chain.add(next)
                    current = next
                    hops++
                    continue
                }

                if (status !in 200..299) {
                    return@withContext HttpResponse(
                        initialUrl, current, status, headers["content-type"], null, chain.toList(),
                    )
                }
                val encodedBody = when {
                    headers["transfer-encoding"]?.contains("chunked", true) == true ->
                        readChunkedBody(input, MAX_RESPONSE_BYTES) ?: return@withContext null
                    headers["content-length"]?.toLongOrNull()?.let { it > MAX_RESPONSE_BYTES } == true ->
                        return@withContext null
                    headers["content-length"]?.toLongOrNull()?.let { it >= 0L } != null ->
                        readFixedBody(
                            input,
                            headers["content-length"]!!.toLong(),
                            MAX_RESPONSE_BYTES,
                        ) ?: return@withContext null
                    else -> readUntilEof(input, MAX_RESPONSE_BYTES) ?: return@withContext null
                }
                val bodyBytes = if (headers["content-encoding"]?.contains("gzip", true) == true) {
                    runCatching {
                        GZIPInputStream(encodedBody.inputStream()).use { it.readBounded(MAX_RESPONSE_BYTES) }
                    }.getOrNull() ?: return@withContext null
                } else encodedBody
                val body = bodyBytes.toString(StandardCharsets.UTF_8)
                return@withContext HttpResponse(
                    initialUrl, current, status, headers["content-type"], body, chain.toList(),
                )
            } catch (e: Exception) {
                CollectorLog.d("Cleartext mirror request failed: ${e.javaClass.simpleName}: ${e.message}")
                return@withContext null
            } finally {
                cancellation?.dispose()
                runCatching { socket.close() }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    private fun readHttpLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        var previous = -1
        while (bytes.size() <= 16_384) {
            val value = input.read()
            if (value < 0) return if (bytes.size() == 0) null else bytes.toString("UTF-8")
            if (previous == 13 && value == 10) {
                val raw = bytes.toByteArray()
                return String(raw, 0, (raw.size - 1).coerceAtLeast(0), StandardCharsets.UTF_8)
            }
            bytes.write(value)
            previous = value
        }
        throw IOException("HTTP header line exceeded limit")
    }

    private fun readFixedBody(input: InputStream, length: Long, cap: Long): ByteArray? {
        if (length < 0 || length > cap) return null
        val output = ByteArrayOutputStream(length.toInt())
        val buffer = ByteArray(8192)
        var remaining = length
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) return null
            output.write(buffer, 0, read)
            remaining -= read
        }
        return output.toByteArray()
    }

    private fun readUntilEof(input: InputStream, cap: Long): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size().toLong() + read > cap) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun readChunkedBody(input: InputStream, cap: Long): ByteArray? {
        val output = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readHttpLine(input) ?: return null
            val size = sizeLine.substringBefore(';').trim().toLongOrNull(16) ?: return null
            if (size == 0L) {
                while (!readHttpLine(input).isNullOrEmpty()) { /* trailers */ }
                return output.toByteArray()
            }
            if (size < 0 || output.size().toLong() + size > cap) return null
            val chunk = readFixedBody(input, size, cap) ?: return null
            output.write(chunk)
            // Each chunk is followed by CRLF.
            if (input.read() != 13 || input.read() != 10) return null
        }
    }

    private fun InputStream.readBounded(cap: Long): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            if (output.size().toLong() + read > cap) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    /**
     * v1.0.69 (overnight consolidation) — B-1 fix: make a blocking OkHttp
     * exchange cancellation-cooperative.
     *
     * Previously [get] called [OkHttpClient.newCall].execute() directly inside
     * [withContext]; a blocking call on the IO dispatcher is not cooperatively
     * cancellable. When the surrounding coroutine was cancelled — e.g. the
     * 45 s [MAIN_TIMEOUT_MS] deadline in VpnServerRepository fires — the socket
     * kept reading until OkHttp's own connect(10 s) + read(15 s) budget elapsed
     * (once per redirect hop), which is why device runs were observed hanging
     * for 128 s / 139 s instead of the nominal 45 s.
     *
     * The fix registers a completion handler that cancels the underlying [Call]
     * the moment the coroutine is cancelled, so the blocked read is interrupted
     * immediately and the socket is released. Success-path behaviour, timeouts,
     * DNS and redirect handling are unchanged.
     */
    private suspend fun callWithCancel(request: Request): Response {
        val call = client.newCall(request)
        return suspendCancellableCoroutine { cont ->
            // Cancel the underlying OkHttp Call the moment the coroutine is
            // cancelled, so a blocked read is interrupted immediately and the
            // socket is released rather than lingering until OkHttp's own
            // connect(10 s) + read(15 s) budget elapses.
            cont.invokeOnCancellation { call.cancel() }
            try {
                val raw = call.execute()
                if (cont.isActive) {
                    cont.resumeWith(Result.success(raw))
                } else {
                    // Cancelled between execute() returning and this check:
                    // we already own the response, so close it to avoid a leak.
                    raw.close()
                }
            } catch (e: IOException) {
                // On cancellation invokeOnCancellation has already called
                // call.cancel(), which makes execute() throw IOException.
                // Do not resume a cancelled continuation.
                if (cont.isActive) {
                    cont.resumeWith(Result.failure(e))
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// R4b — collector transport routing.
//
// SERVER_SOURCE_ARCHITECTURE.md §7 Q2 requires per-request binding via
// `Network.getSocketFactory()`, never `bindProcessToNetwork()`. §7 Q3's
// "open → VPN fallback → emergency → manual" ordering is implemented only
// in its first two rungs here: open stays the default, the VPN rung is
// auto-selected when a usable tunnel exists (see
// `VpnServerRepository.productionRouting`) and falls back to open, and
// emergency/manual sources are out of scope for this phase.
//
// The decision is a pure value so it can be unit-tested without Android, a
// Looper or a live tunnel: the caller gathers the inputs into
// [CollectorRoutingInput] and `VpnServerRepository.routeLadder` orders the
// rungs from them. v1.0.52 (§1.6.44) removed the standalone routing helper
// that used to live here together with its third input, the user's manual
// preference: the ladder is now derived from measured signals only, so there
// is no preference left to fold in at this layer.
// ─────────────────────────────────────────────────────────────────────

/**
 * Which transport a collector request should be sent on.
 *
 * PHYSICAL is bound to NOT_VPN Wi-Fi/cellular; TUNNEL is the verified tunnel
 * established by this app; SYSTEM_VPN is any Android VPN Network when this
 * app's own tunnel is not the active verified route (including a VPN owned by
 * another app). EMERGENCY is the bundled servers.csv source and carries no
 * network.
 */
enum class CollectorPath {
    /** An explicit non-VPN network (Wi-Fi/cellular), bound per request. */
    PHYSICAL,

    /** The app's own VPN network, bound per request. */
    TUNNEL,

    /** A VPN network established by another app, bound per request. */
    SYSTEM_VPN,

    /** Bundled/emergency servers.csv source; no network binding. */
    EMERGENCY,
}

/**
 * The two inputs the routing decision needs.
 *
 * v1.0.52 (§1.6.44): the user-facing "try the tunnel first" opt-in is **gone**.
 * Routing is fully automatic — the ladder is derived from measured signals:
 * whether this app's tunnel is usable and whether Android exposes any system
 * VPN Network to bind with. A stale stored
 * `vpn_always_prefer_vpn` value is simply never read again.
 *
 * [tunnelUsable] plus [hasBoundNetwork] selects this app's verified TUNNEL.
 * When only [hasBoundNetwork] is true, SYSTEM_VPN is selected so a different
 * VPN app is not bypassed. PHYSICAL is selected only when no system VPN
 * Network exists; it is bound to NOT_VPN Wi-Fi/cellular.
 */
data class CollectorRoutingInput(
    val tunnelUsable: Boolean,
    val hasBoundNetwork: Boolean,
)

