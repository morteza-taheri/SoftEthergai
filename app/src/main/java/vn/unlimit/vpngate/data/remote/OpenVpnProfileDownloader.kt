package vn.unlimit.vpngate.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import vn.unlimit.vpngate.data.model.CollectorLog
import vn.unlimit.vpngate.data.model.VpnProtocolNames
import vn.unlimit.vpngate.data.model.VpnRecords
import vn.unlimit.vpngate.data.model.VpnServerRecord
import vn.unlimit.vpngate.data.model.VpnUtil
import vn.unlimit.vpngate.models.VPNGateConnection
import vn.unlimit.vpngate.parser.VpnGateHtmlParser

/**
 * Fetches one VPN Gate OpenVPN profile on demand.
 *
 * The bulk iPhone CSV may omit its Base64 profile to keep the response small.
 * The HTML row still supplies the short-lived sid/hid/host identity and the
 * protocol-specific port, which is enough to request that one .ovpn file.
 *
 * ## The `sid` is a page-load generation token, not a server id
 *
 * Every row scraped from one page load carries the SAME `sid` (device DB,
 * 2026-11-25: 98 rows shared `1795595525891`) and the same token is accepted by
 * every mirror (probe: `sid=1795595525891` returned `200
 * application/x-openvpn-profile` on both 150.40.105.5:32536 and
 * 150.40.105.15:38301, for both `udp=0` and `udp=1`). A token from an older
 * collection is dead instead: the endpoint answers `302 Location: /`, the
 * client follows it, and the "profile" arrives as the mirror's own HTML page
 * (device log 2026-11-25: `profile download: http://150.40.105.5:32536
 * returned a page, not a profile` for a 17-hour-old token, while a freshly
 * scraped token for the same mirror returned a 10 KB profile).
 *
 * So a rejected token is repaired the only way that works: re-read a live page,
 * take the current generation token (plus the `hid`/`host` that page publishes
 * for this very server), and ask again. The parsed page is cached for
 * [PAGE_TTL_MS] because one page load re-arms the whole batch — without the
 * cache an Auto Mode sweep would pay a full page fetch per server.
 */
class OpenVpnProfileDownloader(
    private val fetcher: HttpFetcher = OkHttpFetcher(),
) {
    /** One `/common/openvpn_download.aspx` request identity. */
    private data class Identity(val sid: String, val hid: String, val host: String)

    private sealed interface Outcome {
        /** Short name used in the per-attempt log line. */
        val label: String

        class Profile(val body: String) : Outcome {
            override val label = "profile"
        }

        /**
         * The endpoint answered with its own page instead of a profile: the
         * token generation is stale (or was never valid). Conclusive for the
         * whole generation, hence for every mirror.
         */
        data object TokenRejected : Outcome {
            override val label = "token-rejected"
        }

        /** No answer, bad status, or a profile that does not target this server. */
        data object Unavailable : Outcome {
            override val label = "unavailable"
        }
    }

    /** Tokens published by one page load, keyed by the server IP they belong to. */
    private class PageTokens(val loadedAtMs: Long, val byIp: Map<String, Identity>)

    /** Result of a token lookup, and whether it came from the cache. */
    private class PageLookup(val identity: Identity?, val fromCache: Boolean)

    companion object {
        private const val MAX_PROFILE_CHARS = 1_048_576
        private const val MAX_MIRROR_ATTEMPTS = 2
        /**
         * Per-authority budget, matched to the collector's own source timeout.
         *
         * Measured against a live mirror (2026-11-25): `GET /en/` took 7.3 s on
         * the development host but 25 s from the phone on the same network, and
         * the profile endpoint answered in 1.7-12 s. A 5 s cap cancelled
         * perfectly good downloads mid-flight and reported them as "no response".
         */
        private const val SOURCE_TIMEOUT_MS = 12_000L

        /**
         * A `sid` is the millisecond timestamp of the page load that produced
         * it: `1795595525875` is 2026-11-25T08:32:05Z, and the row it came
         * from was collected at 08:35 UTC. The endpoint stops honouring a
         * generation well inside an hour — 14 minutes old was accepted, 40
         * minutes old answered `302` — so a descriptor older than this is
         * refreshed BEFORE it is tried. That ordering is what keeps the repair
         * inside one attempt on a slow link: refresh + retry instead of
         * try + refresh + retry.
         *
         * This is an optimisation, never a verdict: a token judged old is still
         * tried if the refresh yields nothing, so a change to the encoding (or
         * a skewed clock) can only cost time, not correctness.
         */
        private const val GENERATION_TRUST_MS = 20 * 60_000L

        /** 2020-01-01T00:00:00Z — below this a `sid` is not a millisecond epoch. */
        private const val EPOCH_FLOOR_MS = 1_577_836_800_000L

        /**
         * How long a parsed page's tokens are reused before the page is read
         * again. Deliberately short: the point of the cache is to serve one
         * Auto Mode sweep, not to keep a token alive across a session.
         */
        private const val PAGE_TTL_MS = 5 * 60_000L
        private const val MAX_CACHED_PAGES = 4
        private const val CANONICAL_BASE = "https://www.vpngate.net"

        private val HOST_RE = Regex("^[a-z0-9.-]{1,253}$")
        private val DECIMAL_RE = Regex("\\d{1,20}")
        private val REMOTE_RE = Regex("(?m)^\\s*remote\\s+([^\\s#]+)\\s+(\\d+)(?:\\s+.*)?$")

        /**
         * Access-ordered and bounded: the two or three mirrors a sweep actually
         * reaches are the only pages worth keeping.
         */
        private val pageCache = object : LinkedHashMap<String, PageTokens>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PageTokens>) =
                size > MAX_CACHED_PAGES
        }

        private fun cachedPage(base: String): PageTokens? = synchronized(pageCache) {
            pageCache[base]?.takeIf { System.currentTimeMillis() - it.loadedAtMs <= PAGE_TTL_MS }
        }

        private fun storePage(base: String, tokens: PageTokens) {
            synchronized(pageCache) { pageCache[base] = tokens }
        }

        /**
         * Test hook. The cache is process-wide on purpose — the downloader is
         * built per call, so an instance cache could never serve a sweep — and
         * a unit test needs to start from a known state.
         */
        internal fun clearPageCache() {
            synchronized(pageCache) { pageCache.clear() }
        }
    }

    suspend fun download(
        connection: VPNGateConnection,
        useUdp: Boolean,
        mirrorBaseUrls: List<String> = emptyList(),
    ): String? {
        val stored = storedIdentity(connection) ?: return null
        val port = if (useUdp) connection.udpPort else connection.tcpPort
        if (!VpnUtil.validPort(port)) return null

        // Cached mirrors first, canonical HTTPS last.
        //
        // Order matters on a censored network, which is the normal case for
        // this app: the canonical host is blocked, so putting it first spent the
        // whole per-profile budget on a connect that could never succeed before
        // a mirror was even tried (device log 2026-11-25: the fast-path profile
        // download timed out at 12 s with the canonical host still pending,
        // while twelve reachable HTTP mirrors were already cached). The mirrors
        // are the authorities that just carried the list refresh, so they are
        // the ones most likely to answer.
        val bases = buildList {
            mirrorBaseUrls.asSequence().map { it.trim().trimEnd('/') }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(MAX_MIRROR_ATTEMPTS)
                .forEach { base -> if (base !in this) add(base) }
            add(CANONICAL_BASE)
        }

        // Phase 1 — the descriptor the collector stored.
        //
        // One rejection ends the phase: the token is shared by every row of its
        // generation and accepted or refused identically by every mirror, so
        // re-sending it to the next mirror would only burn the budget.
        val generationAge = generationAgeMs(stored.sid)
        val trusted = generationAge == null || generationAge <= GENERATION_TRUST_MS
        if (!trusted) {
            CollectorLog.d(
                "profile download: stored token ${stored.sid.take(8)}… is " +
                    "${generationAge!! / 60_000} min old; refreshing before the first try",
            )
        }

        if (trusted) {
            when (val outcome = tryToken(bases, stored, port, useUdp, connection)) {
                is Outcome.Profile -> return outcome.body
                // Nothing answered at all: a token refresh cannot help.
                else -> if (outcome !is Outcome.TokenRejected) return null
            }
        }

        // Phase 2 — re-arm from a live page, then retry.
        downloadWithRefreshedToken(bases, connection, port, useUdp, stored)?.let { return it }

        // The age check is a heuristic, so an untrusted token still gets its
        // turn — after the repair, never before it.
        if (!trusted) {
            val outcome = tryToken(bases, stored, port, useUdp, connection)
            if (outcome is Outcome.Profile) return outcome.body
        }
        return null
    }

    /**
     * Send one token to every authority in turn.
     *
     * A [Outcome.TokenRejected] ends the loop: the generation is refused by
     * every mirror alike, so repeating it would only spend the budget.
     */
    private suspend fun tryToken(
        bases: List<String>,
        identity: Identity,
        port: Int,
        useUdp: Boolean,
        connection: VPNGateConnection,
    ): Outcome {
        for (base in bases) {
            val outcome = attempt(base, identity, port, useUdp, connection)
            if (outcome is Outcome.Profile || outcome is Outcome.TokenRejected) return outcome
        }
        return Outcome.Unavailable
    }

    /**
     * How old the page generation behind this token is, in milliseconds, or
     * null when the token is not a plausible millisecond epoch.
     */
    private fun generationAgeMs(sid: String): Long? {
        val epochMs = sid.toLongOrNull() ?: return null
        if (epochMs < EPOCH_FLOOR_MS) return null
        val age = System.currentTimeMillis() - epochMs
        // A timestamp in the future by more than a day, or older than a week,
        // means the field is not what this heuristic assumes.
        return age.takeIf { it >= -86_400_000L && it <= 7L * 86_400_000L }
    }

    /**
     * Resolve a current-generation token, then ask every authority once.
     *
     * Returns null when no page publishes this server at all — the honest
     * "this server has no downloadable profile right now" answer.
     */
    private suspend fun downloadWithRefreshedToken(
        bases: List<String>,
        connection: VPNGateConnection,
        port: Int,
        useUdp: Boolean,
        stored: Identity,
    ): String? {
        // The cached page is what serves the rest of a sweep, so it is
        // consulted first. A cache miss is not the end: the cache may hold a
        // page that has since aged out, so one live read is allowed — but only
        // when a cache was actually in play, never on top of a page just read.
        // Only the authorities that just answered are asked for a page: the
        // canonical host is the one a censored network cannot reach, so paying
        // its 9 s timeout for a token after a mirror already answered would
        // cost more than it can return. With no mirror at all it is the only
        // authority left and is used as such.
        val pageBases = bases
            .filter { !it.equals(CANONICAL_BASE, ignoreCase = true) }
            .ifEmpty { bases }

        var consultedCache = false
        var fresh: Identity? = null
        for (base in pageBases) {
            val lookup = refreshIdentity(base, connection, forceReload = false)
            if (lookup.fromCache) consultedCache = true
            if (lookup.identity != null) {
                fresh = lookup.identity
                break
            }
        }
        if (fresh == null && consultedCache) {
            for (base in pageBases) {
                val lookup = refreshIdentity(base, connection, forceReload = true)
                if (lookup.identity != null) {
                    fresh = lookup.identity
                    break
                }
            }
        }
        if (fresh == null) {
            CollectorLog.d("profile download: no live page publishes ${connection.hostName}")
            return null
        }
        if (fresh == stored) {
            CollectorLog.d("profile download: the page republished the stored token; nothing to retry")
            return null
        }

        // One line per retry: without it a failure here is silent, and a silent
        // failure in this phase once cost a full debugging session (the retry
        // had in fact succeeded; the caller mis-decoded the body).
        for (base in bases) {
            val outcome = attempt(base, fresh, port, useUdp, connection)
            CollectorLog.d(
                "profile download: retry token ${fresh.sid.take(8)}… on $base -> ${outcome.label}",
            )
            if (outcome is Outcome.Profile) return outcome.body
        }
        return null
    }

    /** The descriptor the collector stored, with the connect path's own host form as fallback. */
    private fun storedIdentity(connection: VPNGateConnection): Identity? {
        val sid = connection.openVpnDownloadSid?.takeIf { DECIMAL_RE.matches(it) } ?: return null
        val hid = connection.openVpnDownloadHid?.takeIf { DECIMAL_RE.matches(it) } ?: return null
        // The link normally names the authority (`host`/`fqdn`). When it does
        // not, fall back to the same `<hostName>.opengw.net` form the connect
        // path itself uses, rather than refusing to download at all.
        val declaredHost = connection.openVpnDownloadHost?.takeIf { it.isNotBlank() }
            ?: connection.hostName?.takeIf { it.isNotBlank() }?.let { "$it.opengw.net" }
        val host = declaredHost?.lowercase()?.takeIf { usableHost(it) } ?: return null
        return Identity(sid, hid, host)
    }

    private fun usableHost(host: String): Boolean =
        HOST_RE.matches(host) && (host.endsWith(".opengw.net") || VpnUtil.validIp(host))

    /**
     * Read a live hosts page and return the current token for this server.
     *
     * The page is the same `/en/` document the collector parses, so the token
     * it publishes is by construction one the download endpoint accepts.
     */
    private suspend fun refreshIdentity(
        base: String,
        connection: VPNGateConnection,
        forceReload: Boolean,
    ): PageLookup {
        val ip = connection.ip?.trim().orEmpty()

        if (!forceReload) {
            cachedPage(base)?.let { page ->
                val hit = page.byIp[ip]
                if (hit != null) {
                    CollectorLog.d(
                        "profile download: reusing page token ${hit.sid.take(8)}… " +
                            "from $base for ${connection.hostName}",
                    )
                    return PageLookup(hit, fromCache = true)
                }
                // A page read moments ago that does not list this server is
                // conclusive for its TTL: the server rotated off the front
                // page, so no token from it can help until the page changes.
                CollectorLog.d("profile download: page from $base does not list ${connection.hostName}")
                return PageLookup(null, fromCache = true)
            }
        }

        val pageIdentity = identityForPage(base) ?: return PageLookup(null, fromCache = false)
        val response = get(pageIdentity.url, pageIdentity) ?: run {
            CollectorLog.d("profile download: no page response from $base")
            return PageLookup(null, fromCache = false)
        }
        if (response.status !in 200..299) {
            CollectorLog.d("profile download: $base page answered status=${response.status}")
            return PageLookup(null, fromCache = false)
        }
        val html = response.body ?: return PageLookup(null, fromCache = false)
        val records = VpnGateHtmlParser.parseHtml(html, "profile-identity")
        if (records.isEmpty()) {
            CollectorLog.d("profile download: $base page carried no parsable hosts table")
            return PageLookup(null, fromCache = false)
        }

        val byIp = LinkedHashMap<String, Identity>()
        for (record in records) {
            val recordIp = VpnRecords.str(VpnRecords.identity(record)["ip"])
            val descriptor = descriptorOf(record) ?: continue
            if (recordIp.isNotEmpty()) byIp[recordIp] = descriptor
        }
        storePage(base, PageTokens(System.currentTimeMillis(), byIp))
        CollectorLog.d(
            "profile download: $base page published ${byIp.size} downloadable descriptor(s)" +
                (if (byIp.containsKey(ip)) "; ${connection.hostName} is listed" else "; ${connection.hostName} is absent"),
        )
        return PageLookup(byIp[ip], fromCache = false)
    }

    /**
     * Read the per-server download identity out of a parsed record.
     *
     * `host` falls back to the record's own hostname so a page that only
     * publishes `sid`/`hid` still yields a usable descriptor.
     */
    private fun descriptorOf(record: VpnServerRecord): Identity? {
        val openVpn = VpnRecords.map(VpnRecords.protocols(record)[VpnProtocolNames.OPENVPN])
        val download = VpnRecords.map(openVpn["download"])
        val sid = VpnRecords.str(download["sid"]).takeIf { DECIMAL_RE.matches(it) } ?: return null
        val hid = VpnRecords.str(download["hid"]).takeIf { DECIMAL_RE.matches(it) } ?: return null
        val published = VpnRecords.str(download["host"]).lowercase().takeIf { it.isNotEmpty() }
        val hostname = VpnRecords.str(VpnRecords.identity(record)["hostname"]).lowercase()
        val host = (published ?: hostname.takeIf { it.isNotEmpty() })?.takeIf { usableHost(it) }
            ?: return null
        return Identity(sid, hid, host)
    }

    /** Same `<base>/en/` document the collector parses, built the same way. */
    private fun identityForPage(base: String): SourceIdentity? =
        if (base.equals(CANONICAL_BASE, ignoreCase = true)) {
            SourceUrl.parse(VpnGateUrls.MAIN_URL, SourceKind.CANONICAL_HTML)
        } else {
            SourceUrl.forMirror(base, SourceKind.DISCOVERED_MIRROR)
                ?.withPath(VpnGateUrls.HTML_PATH)
        }

    /**
     * One download attempt against one authority.
     *
     * Mirrors are cleartext IP authorities that the app registers before use;
     * [SourceUrl.forMirror] is what validates the authority and produces the
     * redirect policy that keeps a response on its own host.
     */
    private suspend fun attempt(
        base: String,
        identity: Identity,
        port: Int,
        useUdp: Boolean,
        connection: VPNGateConnection,
    ): Outcome {
        val endpoint = "$base/common/openvpn_download.aspx"
        val sourceIdentity = if (base.equals(CANONICAL_BASE, ignoreCase = true)) {
            SourceUrl.parse(endpoint, SourceKind.CANONICAL_HTML)
        } else {
            SourceUrl.forMirror(endpoint, SourceKind.DISCOVERED_MIRROR)
        } ?: return Outcome.Unavailable

        val transport = if (useUdp) "udp" else "tcp"
        val udpFlag = if (useUdp) 1 else 0
        val fileName = "vpngate_${identity.host}_${transport}_${port}.ovpn"
        val query = "?sid=${identity.sid}&udp=$udpFlag&host=${identity.host}" +
            "&port=$port&hid=${identity.hid}&/$fileName"

        val response = get(endpoint + query, sourceIdentity) ?: run {
            CollectorLog.d("profile download: no response from $base")
            return Outcome.Unavailable
        }

        if (response.status !in 200..299 || response.redirectRejected) {
            CollectorLog.d(
                "profile download: $base answered status=${response.status}" +
                    (if (response.redirectRejected) " (redirect rejected: ${response.redirectRejectionReason})" else ""),
            )
            return Outcome.Unavailable
        }
        if (!sourceIdentity.redirectPolicy().allowsFinal(response.finalUrl)) {
            CollectorLog.d("profile download: $base redirected outside its authority")
            return Outcome.Unavailable
        }
        val profile = response.body?.takeIf { it.length in 50..MAX_PROFILE_CHARS }
        if (profile == null) {
            CollectorLog.d("profile download: $base returned no usable body")
            return Outcome.Unavailable
        }
        if (profile.trimStart().startsWith("<")) {
            // The endpoint bounced us back to the page: a stale token
            // generation, not a broken mirror.
            CollectorLog.d("profile download: $base returned a page, not a profile")
            return Outcome.TokenRejected
        }

        val expectedHost = identity.host.lowercase()
        val expectedIp = connection.ip?.trim()
        val remotes = REMOTE_RE.findAll(profile)
            .map { it.groupValues[1].lowercase() to it.groupValues[2].toIntOrNull() }
            .toList()
        if (remotes.none { (remoteHost, remotePort) ->
                (remoteHost == expectedHost || remoteHost == expectedIp) && remotePort == port
            }
        ) {
            CollectorLog.d(
                "profile download: $base profile does not target $expectedHost:$port",
            )
            return Outcome.Unavailable
        }

        // No `proto` gate here on purpose. The endpoint always returns the UDP
        // template — a `udp=0` request for public-vpn-75:443 came back as
        // `proto udp` + `remote ... 443` (probe 2026-11-25) — so comparing
        // `proto` against the requested transport rejected every TCP download
        // while carrying no information: the port in the query is what selects
        // the listener, and the `remotes` check above already enforces it.
        // `OpenVpnConfigTransform.forTransport` rewrites `proto` and the remote
        // port for the transport the user actually dials, so the profile this
        // returns is transport-agnostic by design.

        // Normalize the profile's remote host to the server IP. The app's
        // existing UDP/domain transforms are intentionally IP-based; keeping
        // a hostname here would prevent their protocol-specific port rewrite.
        val normalizedIp = expectedIp?.takeIf { VpnUtil.validIp(it) }
        return Outcome.Profile(
            if (normalizedIp != null && expectedHost != normalizedIp) {
                Regex("(?m)^(\\s*remote\\s+)${Regex.escape(expectedHost)}(\\s+\\d+)")
                    .replace(profile) { match ->
                        "${match.groupValues[1]}${normalizedIp}${match.groupValues[2]}"
                    }
            } else profile,
        )
    }

    /**
     * GET with the per-source budget.
     *
     * Cancellation is rethrown rather than folded into "no response": a caller
     * that gave up must not see a fallback run afterwards.
     */
    private suspend fun get(url: String, identity: SourceIdentity): HttpResponse? = try {
        withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
            fetcher.get(url, identity.redirectPolicy())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        CollectorLog.d("profile download: ${identity.sourceTag} failed: ${e.message}")
        null
    }
}
