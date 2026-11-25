package vn.unlimit.vpngate.repository

import kotlinx.coroutines.withTimeoutOrNull
import vn.unlimit.vpngate.data.model.CollectorLog
import vn.unlimit.vpngate.data.remote.CleartextMirrorPolicy
import vn.unlimit.vpngate.data.remote.HttpFetcher
import vn.unlimit.vpngate.data.remote.RedirectPolicy
import vn.unlimit.vpngate.data.remote.VpnGateUrls
import java.net.URI

/**
 * Stage G-7 — fetch ONE server's `.ovpn` profile, on demand.
 *
 * Why this exists
 * ---------------
 * VPN Gate prints a `do_openvpn.aspx?...&sid=..&hid=..` link next to every
 * server that has a profile. Until now the app only ever consumed the
 * `OpenVPN_ConfigData_Base64` column of the API feed, which meant:
 *
 *  - a server discovered through the HTML page had *no* profile at all, even
 *    though the page had just told us it had one, and
 *  - every refresh carried every profile inline, which is the bulk of the
 *    CSV/HTML weight the user complained about.
 *
 * The identifiers the page publishes are enough to ask VPN Gate for that one
 * profile later, at the moment somebody actually dials the server:
 *
 * ```
 * https://www.vpngate.net/common/openvpn_download.aspx
 *     ?sid=<sid>&udp=<0|1>&host=<fqdn>&port=<port>&hid=<hid>/name.ovpn
 * ```
 *
 * So the collector now stores `sid`/`hid` (two short strings) and the profile
 * itself is downloaded only when it is needed. Nothing about the collected
 * list grows; a server whose profile was never downloaded is still dialable.
 *
 * Failure policy
 * --------------
 * Every failure — no base reachable, HTTP error, an HTML error page instead of
 * a profile, a body that is not a profile — returns `null`. The caller then
 * keeps its existing "no config payload" behaviour (a MISSING_CONFIG outcome),
 * which is exactly what it did before this class existed. Nothing is guessed,
 * nothing is fabricated, and no partial body is ever handed to the parser.
 */
class OpenVpnConfigDownloader(
    private val fetcher: HttpFetcher,
    /**
     * Ordered bases to try. Defaults to the canonical site followed by the
     * official IP mirrors, because the whole reason this class exists is that
     * `vpngate.net` itself is filtered in some networks while a mirror is
     * still reachable.
     */
    private val bases: () -> List<String> = { defaultBases() },
    /** Per-attempt budget; the caller may impose a tighter one. */
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    /** Everything the endpoint needs, all of it collected from the page. */
    data class Request(
        val sid: String,
        val hid: String,
        /** Host name WITHOUT the `.opengw.net` suffix, as stored in the DB. */
        val hostName: String,
        val tcpPort: Int,
        val udpPort: Int,
    ) {
        val isUsable: Boolean
            get() = sid.isNotBlank() && hid.isNotBlank() && hostName.isNotBlank() &&
                (tcpPort > 0 || udpPort > 0)
    }

    /**
     * Download the profile for [request], preferring the transport that is
     * actually going to be dialled.
     *
     * The profile is returned as plain text; storing it is the caller's job.
     * A `udp=0` request is preferred when a TCP port exists, because the rest
     * of the app treats the stored profile as the canonical TCP one and
     * derives the UDP variant from it. The `udp=1` form is only used when the
     * server publishes no TCP port, so the result is still a profile the
     * existing transform can handle (it is idempotent for an already-UDP
     * profile).
     */
    suspend fun download(request: Request, preferUdp: Boolean): String? {
        if (!request.isUsable) {
            CollectorLog.d(
                "[ovpn-download] not downloadable: sid/hid/host/port incomplete " +
                    "for ${request.hostName}",
            )
            return null
        }

        val udp = preferUdp || request.tcpPort <= 0
        val port = if (udp) request.udpPort else request.tcpPort
        if (port <= 0) {
            CollectorLog.d("[ovpn-download] no usable port for ${request.hostName}")
            return null
        }

        val fqdn = if (request.hostName.contains('.')) {
            request.hostName
        } else {
            "${request.hostName}.opengw.net"
        }

        attemptAllBases(request, fqdn, udp, port)?.let { return it }

        // ── Second pass with a refreshed `sid` ────────────────────────────
        //
        // `sid` is not a per-server identity: it is a PAGE-GENERATION token.
        // Every server on one page shares it, and it is a timestamp
        // (`1795539642828`). Once the page regenerates, a stored `sid` makes
        // the endpoint answer 302 to "/" instead of serving a profile —
        // verified against the operator's own example link, which had already
        // expired by the time it was used.
        //
        // `hid` is the per-server identity and does not change. So when the
        // first pass fails, re-read the current page once, take the fresh
        // `sid` that appears next to this server's `hid`, and retry. This is
        // what makes a list collected an hour ago still able to fetch a
        // profile, and it costs one page read (35 KB) only on the failure path.
        for (base in bases()) {
            val freshSid = freshSidFor(base, request.hid) ?: continue
            if (freshSid == request.sid) continue
            CollectorLog.d(
                "[ovpn-download] stored sid was stale for ${request.hostName}; " +
                    "retrying with the sid from the current page",
            )
            val refreshed = request.copy(sid = freshSid)
            attemptAllBases(refreshed, fqdn, udp, port)?.let { return it }
            break
        }

        CollectorLog.d("[ovpn-download] exhausted every base for ${request.hostName}")
        return null
    }

    /** One pass over [bases] with a fixed request; null when none serves a profile. */
    private suspend fun attemptAllBases(
        request: Request,
        fqdn: String,
        udp: Boolean,
        port: Int,
    ): String? {
        for (base in bases()) {
            val url = buildUrl(base, request, fqdn, udp, port) ?: continue
            val policy = policyFor(base)
            val body = withTimeoutOrNull(timeoutMs) {
                runCatching { fetcher.get(url, policy) }.getOrNull()?.body
            }

            if (looksLikeProfile(body)) {
                CollectorLog.d(
                    "[ovpn-download] ok ${request.hostName} " +
                        "(udp=$udp port=$port from $base)",
                )
                return body
            }
            CollectorLog.d("[ovpn-download] no profile from $base for ${request.hostName}")
        }
        return null
    }

    /**
     * The `sid` currently published next to [hid] on [base]'s server page.
     *
     * Matching is by `hid`, which is the stable per-server identifier; `sid`
     * is what we are trying to obtain. Both parameter orders are tried because
     * the page's link order is not part of any contract.
     */
    private suspend fun freshSidFor(base: String, hid: String): String? {
        val pageUrl = base.trimEnd('/') + "/en/"
        val policy = policyFor(base)
        val body = withTimeoutOrNull(timeoutMs) {
            runCatching { fetcher.get(pageUrl, policy) }.getOrNull()?.body
        } ?: return null

        val quoted = Regex.escape(hid)
        val patterns = listOf(
            Regex("do_openvpn\\.aspx\\?[^\"'<>]*?sid=(\\d+)[^\"'<>]*?hid=$quoted\\b"),
            Regex("do_openvpn\\.aspx\\?[^\"'<>]*?hid=$quoted\\b[^\"'<>]*?sid=(\\d+)"),
        )
        for (p in patterns) {
            val m = p.find(body)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    /**
     * The exact endpoint VPN Gate exposes. Returns null when [base] cannot be
     * parsed, rather than producing a URL on an unknown host.
     */
    private fun buildUrl(
        base: String,
        request: Request,
        fqdn: String,
        udp: Boolean,
        port: Int,
    ): String? {
        val authority = runCatching { URI(base).rawAuthority }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null
        val scheme = runCatching { URI(base).scheme }.getOrNull()
            ?.takeIf { it.equals("http", true) || it.equals("https", true) } ?: return null

        return buildString {
            append(scheme.lowercase())
            append("://")
            append(authority)
            append(PATH)
            append("?sid=")
            append(request.sid)
            append("&udp=")
            append(if (udp) "1" else "0")
            append("&host=")
            append(fqdn)
            append("&port=")
            append(port)
            append("&hid=")
            append(request.hid)
            append(FILENAME_SUFFIX)
        }
    }

    /**
     * A download may not wander off the host it started on: the endpoint is
     * the only thing being trusted here, so the policy pins the base's own
     * authority and permits the http->https upgrade only.
     */
    private fun policyFor(base: String): RedirectPolicy? {
        val authority = runCatching { URI(base).rawAuthority }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null
        return RedirectPolicy(allowedAuthorities = setOf(authority))
    }

    /**
     * Is [body] an OpenVPN profile, or is it an error page?
     *
     * Deliberately strict: VPN Gate answers a bad `sid`/`hid` with an HTML
     * page, and handing that to `ConfigParser` would produce an empty profile
     * that fails much later with a misleading error. A profile always declares
     * a `client`/`dev`/`remote` directive.
     */
    private fun looksLikeProfile(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        if (body.length > MAX_PROFILE_CHARS) return false
        val lower = body.lowercase()
        if ("<html" in lower || "<!doctype" in lower) return false
        return "remote " in lower && ("client" in lower || "dev tun" in lower)
    }

    companion object {
        /** `/common/openvpn_download.aspx` — the documented download endpoint. */
        const val PATH = "/common/openvpn_download.aspx"

        /**
         * Browsers name the file from the `hid` parameter; the endpoint
         * tolerates (and VPN Gate's own links use) this trailing segment.
         */
        const val FILENAME_SUFFIX = "/name.ovpn"

        const val DEFAULT_TIMEOUT_MS = 15_000L

        /** A profile is a few kilobytes; anything larger is not a profile. */
        private const val MAX_PROFILE_CHARS = 512 * 1024

        /**
         * Canonical first, then every mirror this run actually knows about:
         * the built-in bootstrap list plus whatever the directory published
         * and `CleartextMirrorPolicy` has registered. Evaluated per call, so a
         * mirror discovered by a refresh is usable by the very next download.
         *
         * This matters precisely in the situation the class exists for: the
         * canonical host is filtered, and the built-in mirror list has gone
         * stale. Before this the downloader could only try addresses that were
         * hard-coded at build time.
         */
        fun defaultBases(): List<String> =
            (listOf(CANONICAL_BASE) +
                VpnGateUrls.BOOTSTRAP_MIRRORS +
                CleartextMirrorPolicy.registeredBases())
                .distinct()

        const val CANONICAL_BASE = "https://www.vpngate.net"
    }
}
