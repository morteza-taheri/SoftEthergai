package vn.unlimit.vpngate.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import vn.unlimit.vpngate.data.model.CollectorLog
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Which hosts may be fetched over **cleartext HTTP**, decided at runtime.
 *
 * ## Why this exists
 *
 * VPN Gate's official mirrors are plain HTTP on an IP and port
 * (`http://150.40.105.20:16086/en/`). They expose no TLS endpoint at all —
 * verified: an `https://` request to the same authority fails to connect.
 * The canonical site is HTTPS and stays HTTPS.
 *
 * The previous design expressed the mirror exception as a **static** list in
 * `network_security_config.xml`. That cannot work, because the mirror
 * addresses rotate: the directory at `sites.aspx` published
 * `150.40.105.20:16086`, `150.40.105.19:35399`, `150.40.105.5:32536` and
 * `133.18.161.231:45457` on 2026-11-24, of which only one was in the static
 * list. Every newly discovered mirror was therefore rejected by the platform
 * with `CLEARTEXT communication ... not permitted`, which is exactly why the
 * server list failed to update on a censored network: the canonical HTTPS
 * host is filtered, and the fallbacks were blocked locally.
 *
 * ## What replaced it
 *
 * The platform flag is now permissive (`base-config
 * cleartextTrafficPermitted="true"`) and the **actual** restriction moved
 * into [CleartextMirrorInterceptor], which is installed on every collector
 * client through [collectorClientBuilder]. So the rule is still enforced, but
 * it is enforced by code that can follow the directory, instead of by an XML
 * file that cannot.
 *
 * A cleartext request is permitted only when the host is:
 *
 *  1. a **registered** mirror — the built-in bootstrap list, anything
 *     discovered from the official `sites.aspx` directory, or a mirror URL
 *     restored from the persisted cache; or
 *  2. an IPv4 literal inside a **known VPN Gate mirror hosting range**
 *     ([MIRROR_HOST_PREFIXES]). The directory has only ever published
 *     mirrors inside these two /24s plus a handful of standalone hosts; the
 *     prefix rule lets a brand-new mirror in the same range work on the very
 *     first run, before it has been discovered and cached.
 *
 * Everything else over `http://` is refused, including `vpngate.net` itself
 * (which must be HTTPS) and any authority that [SourceUrl] would reject as a
 * mirror in the first place — so a tampered directory response still cannot
 * aim the app at loopback, RFC1918, CGNAT or any other non-global address.
 */
object CleartextMirrorPolicy {

    /**
     * Hosting ranges the official directory has published mirrors in.
     *
     * `150.40.105.0/24` is the Croatian mirror host that has served the
     * majority of entries for months (`.5 .6 .11 .19 .20 .21 .23 .25` all
     * observed), and `14.40.125.0/24` is the Korean host. Both are /24s, so
     * the rule is bounded: 512 addresses, all of them inside the ranges VPN
     * Gate actually uses, and each one still has to be a *global* IPv4 and
     * still has to answer with a body that passes schema validation.
     */
    private val MIRROR_HOST_PREFIXES = listOf(
        "150.40.105.",
        "14.40.125.",
    )

    /** Authorities (host or host:port) explicitly known to be mirrors. */
    private val registry: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Reusable `http://host:port` bases for the same mirrors, so the
     * per-server config downloader can try a mirror that is actually alive
     * today instead of only the built-in list.
     */
    private val bases: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        // The built-in list is known-good by construction; seed it so a cold
        // start can reach a mirror before any directory response is parsed.
        VpnGateUrls.BOOTSTRAP_MIRRORS.forEach { register(it) }
    }

    /**
     * Record [urlOrAuthority] as a mirror. Accepts a full URL, a `host:port`
     * authority, or a bare host; anything unparseable is ignored rather than
     * trusted.
     */
    fun register(urlOrAuthority: String?) {
        val raw = urlOrAuthority?.trim().orEmpty()
        if (raw.isEmpty()) return
        val host = hostOf(raw) ?: return
        if (host.isEmpty()) return
        registry.add(host.lowercase())
        baseOf(raw)?.let { bases.add(it) }
    }

    /** Register every entry of [urls]; returns how many are now known. */
    fun registerAll(urls: Collection<String>): Int {
        urls.forEach { register(it) }
        return registry.size
    }

    /** Hosts currently permitted, for diagnostics. */
    fun registeredHosts(): List<String> = registry.toList().sorted()

    /** Live `http://host:port` mirror bases, newest registrations included. */
    fun registeredBases(): List<String> = bases.toList().sorted()

    /**
     * True only when a **cleartext** request to [host] is acceptable.
     *
     * `vpngate.net` and `tsukuba.ac.jp` are never cleartext: they have working
     * TLS and are the canonical, authenticating endpoints.
     */
    fun isCleartextPermitted(host: String): Boolean {
        val h = host.trim().lowercase()
        if (h.isEmpty()) return false
        if (h == "vpngate.net" || h.endsWith(".vpngate.net")) return false
        if (h == "tsukuba.ac.jp" || h.endsWith(".tsukuba.ac.jp")) return false
        if (h in registry) return true
        // A newly published mirror in a known range, before discovery has
        // recorded it. Still required to be a globally routable literal, so
        // this cannot be used to reach a private or reserved address.
        if (MIRROR_HOST_PREFIXES.any { h.startsWith(it) } && SourceUrl.isGlobalIpv4(h)) {
            return true
        }
        return false
    }

    /** Test seam: forget every registered host except the bootstrap list. */
    internal fun resetToBootstrap() {
        registry.clear()
        bases.clear()
        VpnGateUrls.BOOTSTRAP_MIRRORS.forEach { register(it) }
    }

    private fun hostOf(raw: String): String? {
        val withScheme = if (raw.contains("://")) raw else "http://$raw"
        return try {
            java.net.URI(withScheme).host?.lowercase()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * `http://host:port` for a mirror entry, dropping any path. Returns null
     * when no port is present, because a bare IP mirror has no meaningful
     * default port to guess.
     */
    private fun baseOf(raw: String): String? {
        val withScheme = if (raw.contains("://")) raw else "http://$raw"
        return try {
            val uri = java.net.URI(withScheme)
            val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
            val port = uri.port
            if (port <= 0) return null
            "http://${host.lowercase()}:$port"
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Enforces [CleartextMirrorPolicy] on the collector's clients.
 *
 * Installed as an **application** interceptor, so it sees the request before
 * any connection is attempted and before OkHttp's own platform check. The
 * collector follows redirects by hand (`followRedirects(false)`), so each hop
 * arrives here as its own request and is checked on its own merits — a mirror
 * cannot bounce the app onto an unapproved cleartext host.
 */
class CleartextMirrorInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.isHttps) {
            val host = request.url.host
            if (!CleartextMirrorPolicy.isCleartextPermitted(host)) {
                CollectorLog.d(
                    "CLEARTEXT-BLOCKED host=$host " +
                        "(not a registered VPN Gate mirror and not in a known mirror range)",
                )
                throw IOException(
                    "Cleartext HTTP to $host is not permitted: only VPN Gate mirrors may be " +
                        "fetched over HTTP",
                )
            }
        }
        return chain.proceed(request)
    }
}
