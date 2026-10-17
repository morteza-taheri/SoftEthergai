package vn.unlimit.vpngate.data.remote

/**
 * Stage 2C-A: typed source identity for every fetchable VPN Gate source.
 *
 * Before this stage a fetch returned a bare [String] body and the parser
 * was told `"html"`, so a mirror body was indistinguishable from the
 * canonical one. Identity is now carried as a value: kind, scheme, host,
 * port, path, mirror id and discovery origin, and it survives the whole
 * way to the record layer's `source` tag.
 */
enum class SourceKind {
    /** Canonical VPN Gate main page, `/en/`. */
    CANONICAL_HTML,

    /** Canonical VPN Gate CSV feed, `/api/iphone/`. */
    CANONICAL_API,

    /** Hard-coded `http://IP:PORT` mirror shipped with the app. */
    BOOTSTRAP_MIRROR,

    /** Mirror discovered in the official `sites.aspx` directory. */
    DISCOVERED_MIRROR,

    /** Mirror restored from the persisted mirror cache. */
    CACHED_MIRROR,

    /**
     * Z1-IMPL-B (Roadmap §12.5.2 item B): a user-configured emergency CSV
     * source. Configuration only for now — no fetch is wired yet (item C).
     */
    EMERGENCY_CSV,

    /**
     * Z1-IMPL-D (Roadmap §12.5.2 item D): a manually entered server.
     * Persisted in its own table and re-emitted on every accepted write, so
     * it is the one entry in the server list that is not an observation of a
     * feed.
     */
    MANUAL,
}

/** Where a mirror was learned from — carried for diagnostics/provenance. */
object MirrorOrigin {
    const val CANONICAL_DIRECTORY = "canonical_directory"
    const val MIRROR_CACHE = "mirror_cache"
    const val BOOTSTRAP_LIST = "bootstrap_list"
}

/**
 * One fetchable source. Never reconstructed from a loose [String] at the
 * point of use: it is parsed once, validated, and then carried by value.
 *
 * [sourceTag] is the record-layer source name. Canonical HTML/API keep
 * `"html"`/`"api"` so the existing merge policy (api > html > mirror) is
 * unchanged; every mirror gets its own `"mirror_<n>"` tag so it forms a
 * separate provenance group and can never be mistaken for canonical HTML.
 */
data class SourceIdentity(
    val kind: SourceKind,
    val scheme: String,
    val host: String,
    val port: Int? = null,
    val path: String = "/",
    val mirrorId: String? = null,
    val discoveryOrigin: String? = null,
) {

    val isCanonical: Boolean
        get() = kind == SourceKind.CANONICAL_HTML || kind == SourceKind.CANONICAL_API

    val isMirror: Boolean get() = !isCanonical

    val isSecureTransport: Boolean get() = scheme == HTTPS

    /** `host` or `host:port` — exactly what appears in the URL. */
    val authority: String get() = if (port == null) host else "$host:$port"

    val url: String get() = "$scheme://$authority$path"

    /** Scheme + authority, without the per-feed path (what a mirror cache stores). */
    val baseUrl: String get() = "$scheme://$authority"

    /**
     * The record-layer provenance tag. A mirror NEVER yields `"html"` or
     * `"api"`: it yields its own mirror id, or a clearly-marked unknown
     * mirror tag when it has not been numbered yet.
     *
     * Z1-IMPL-G (Roadmap §12.5.2 item G): the `when` below is deliberately
     * **exhaustive with no `else` branch**, listing every [SourceKind] by
     * name. That is a compile-time guard, not a style choice. Before this
     * revision the mirror kinds were reached through an `else`, which is how
     * `SourceKind.MANUAL` silently fell through to
     * [UNNUMBERED_MIRROR_TAG] and was reported as a mirror for two whole
     * revisions (Z1-IMPL-D report, "recorded limitations" item 1). Adding a
     * kind to the enum without giving it a tag used to compile clean and
     * mislabel records at runtime; now it fails to compile instead.
     *
     * `manual` is the tag, matching the source naming of §3.3 and the merge
     * priority table in `VpnServerMerger` (`api > html > mirror > manual`).
     */
    val sourceTag: String
        get() = when (kind) {
            SourceKind.CANONICAL_HTML -> HTML_TAG
            SourceKind.CANONICAL_API -> API_TAG

            // Z1-IMPL-C: an emergency source gets its own tag rather than
            // falling through to the mirror branch. Before this it would
            // have been reported as `mirror_unnumbered`, which both
            // mislabels it and lets an emergency record sit in the mirror
            // provenance group. `SERVER_SOURCE_ARCHITECTURE.md` §3 requires
            // a dedicated, non-canonical tag that never reads as `api` or
            // `html`, so an emergency record can never speak with canonical
            // authority in the merger's `api > html > …` policy.
            SourceKind.EMERGENCY_CSV -> EMERGENCY_TAG

            // Z1-IMPL-G: the same rule, now applied to manual servers. The
            // debt is recorded in the Z1-IMPL-D report — `SourceIdentity.kt`
            // was outside that phase's authorized file set, so
            // `SourceKind.MANUAL` kept reading `mirror_unnumbered` and a
            // user's own entry was reported as an unidentified mirror.
            // `SERVER_SOURCE_ARCHITECTURE.md` §3.3 requires a distinct,
            // non-canonical tag; §4.2 requires that a manual record be
            // "never promoted above canonical", which a mirror tag actively
            // works against, because mirrors are numbered by the official
            // directory and a user entry is not.
            SourceKind.MANUAL -> MANUAL_TAG

            // The three mirror kinds share one branch: they are told apart
            // by [mirrorId], not by their tag.
            SourceKind.BOOTSTRAP_MIRROR,
            SourceKind.DISCOVERED_MIRROR,
            SourceKind.CACHED_MIRROR,
            -> mirrorId ?: UNNUMBERED_MIRROR_TAG
        }

    fun withMirrorId(id: String): SourceIdentity = copy(mirrorId = id)

    fun withPath(newPath: String): SourceIdentity = copy(path = newPath)

    /** The redirect policy this source is allowed to follow. */
    fun redirectPolicy(): RedirectPolicy =
        if (isCanonical) RedirectPolicy.canonical() else RedirectPolicy.forMirror(this)

    override fun toString(): String = "SourceIdentity(${sourceTag}, $url)"

    companion object {
        const val HTTPS = "https"
        const val HTTP = "http"

        /** Provenance tags owned by the canonical sources. */
        const val HTML_TAG = "html"
        const val API_TAG = "api"

        /**
         * Z1-IMPL-C: the record-layer tag of a user-configured emergency CSV
         * source. Same rule as a mirror — never `html`, never `api`.
         */
        const val EMERGENCY_TAG = "emergency"

        /**
         * Z1-IMPL-G: the record-layer tag of a manually entered server.
         *
         * Same rule again — never `html`, never `api`, and not a mirror tag
         * either. Before this existed, `SourceKind.MANUAL` fell through the
         * `else` of `sourceTag` and was reported as `mirror_unnumbered`,
         * which put a user's own entry in the mirror provenance group and
         * gave it the mirror merge authority (`VpnServerMerger.SOURCE_PRIORITY`,
         * default 20) instead of the manual one (30).
         */
        const val MANUAL_TAG = "manual"

        /** A mirror that has not been numbered yet is still NOT canonical. */
        const val UNNUMBERED_MIRROR_TAG = "mirror_unnumbered"
    }
}

/**
 * URL parsing for [SourceIdentity]. Returns `null` — never a partially
 * trusted identity — for anything that is not an absolute http(s) URL
 * with a usable host and port.
 */
object SourceUrl {

    /** DNS name or IPv4 literal, lower-case. */
    private val HOST_RE = Regex("^[a-z0-9]([a-z0-9._-]*[a-z0-9])?$")

    private val IPV4_RE = Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")

    /**
     * Mirror authorities are officially IP:PORT or dedicated `*.opengw.net`
     * hosts (the same rule the official directory enforces). It is applied
     * to discovered AND cached mirror URLs, so a corrupted cache entry can
     * never become a trusted candidate.
     */
    fun isAcceptableMirrorAuthority(authority: String): Boolean {
        val host = authority.substringBeforeLast(':', authority)
        if (host.isEmpty()) return false
        return IPV4_RE.matches(host) || host.endsWith(".opengw.net") || host == "opengw.net"
    }

    fun parse(
        url: String,
        kind: SourceKind,
        discoveryOrigin: String? = null,
    ): SourceIdentity? {
        val raw = url.trim()
        if (raw.isEmpty()) return null

        val scheme = raw.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme != SourceIdentity.HTTPS && scheme != SourceIdentity.HTTP) return null

        val remainder = raw.substringAfter("://", missingDelimiterValue = "")
        if (remainder.isEmpty()) return null

        val authorityPart = remainder.substringBefore('/')
        val pathPart = remainder.substringAfter('/', missingDelimiterValue = "")
            .let { if (it.isEmpty()) "/" else "/$it" }

        // Credentials in a source URL are never legitimate here.
        if (authorityPart.contains('@')) return null

        val hostPart: String
        var port: Int? = null
        val colon = authorityPart.lastIndexOf(':')
        if (colon > -1 && authorityPart.indexOf(':') == colon) {
            hostPart = authorityPart.substring(0, colon).lowercase()
            val portText = authorityPart.substring(colon + 1)
            port = portText.toIntOrNull() ?: return null
            if (port !in 1..65535) return null
        } else {
            hostPart = authorityPart.lowercase()
        }

        if (hostPart.isEmpty() || !HOST_RE.matches(hostPart)) return null
        if (hostPart.endsWith(".")) return null
        if (IPV4_RE.matches(hostPart)) {
            if (hostPart.split('.').any { it.toIntOrNull() !in 0..255 }) return null
        } else if (!hostPart.contains('.')) {
            return null
        }

        return SourceIdentity(
            kind = kind,
            scheme = scheme,
            host = hostPart,
            port = port,
            path = pathPart,
            discoveryOrigin = discoveryOrigin,
        )
    }

    /** A mirror URL from the directory or the cache; `null` when unusable. */
    fun forMirror(
        url: String,
        kind: SourceKind,
        discoveryOrigin: String? = null,
    ): SourceIdentity? {
        val identity = parse(url, kind, discoveryOrigin) ?: return null
        if (!isAcceptableMirrorAuthority(identity.authority)) return null
        return identity
    }
}
