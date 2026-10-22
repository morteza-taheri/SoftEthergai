package vn.unlimit.vpngate.data.remote

import android.net.Network
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
 * v1.0.45 (§1.6.38): the two honest, explicit transports replace the old
 * `OPEN`/`VPN` pair. `OPEN` was unbound, so when the app's own tunnel was up it
 * silently followed the OS default — which IS the tunnel. `PHYSICAL` is bound
 * to a NOT_VPN + INTERNET network; `TUNNEL` is bound to the app's VPN network.
 * `EMERGENCY` is the last rung (bundled servers.csv) and carries no network.
 */
enum class CollectorPath {
    /** An explicit non-VPN network (Wi-Fi/cellular), bound per request. */
    PHYSICAL,

    /** The app's own VPN network, bound per request. */
    TUNNEL,

    /** Bundled/emergency servers.csv source; no network binding. */
    EMERGENCY,
}

/**
 * The two inputs the routing decision needs.
 *
 * v1.0.52 (§1.6.44): the user-facing "try the tunnel first" opt-in is **gone**.
 * Routing is fully automatic — the ladder is derived from measured signals
 * only: whether the tunnel is usable right now, whether a VPN `Network` handle
 * exists to bind with, and (in `VpnServerRepository`) the in-process path
 * memory that records which transport last worked. A stale stored
 * `vpn_always_prefer_vpn` value is simply never read again.
 *
 * Both must hold for the `TUNNEL` rung to be *offered*:
 *
 *  1. [tunnelUsable] — `GlobalVpnTracker.isTunnelUsableForRouting()`, i.e. a
 *     tunnel that is CONNECTED *and* forwarding, not a half-up one.
 *  2. [hasBoundNetwork] — a system VPN Network is actually at hand.
 *
 * Anything missing keeps the request on `CollectorPath.PHYSICAL` — an explicit
 * non-VPN network, never the unbound OS default.
 */
data class CollectorRoutingInput(
    val tunnelUsable: Boolean,
    val hasBoundNetwork: Boolean,
)

