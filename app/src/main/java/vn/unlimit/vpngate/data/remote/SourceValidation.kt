package vn.unlimit.vpngate.data.remote

import vn.unlimit.vpngate.data.model.CollectorLog

/**
 * Stage 2C-A: a body that still knows where it came from.
 *
 * The identity travels with the body all the way to the parser, so a
 * mirror body is recorded under its own `mirror_<n>` tag and can never be
 * mistaken for the canonical feed.
 */
data class FetchedSource(
    val identity: SourceIdentity,
    val response: HttpResponse,
) {
    val body: String get() = requireNotNull(response.body)

    val isSuccessful: Boolean
        get() = response.status in 200..299 &&
            !response.redirectRejected &&
            !response.body.isNullOrBlank()

    /**
     * The identity the response ACTUALLY came from, as reported by the
     * final URL — or `null` when the reported URL is unusable.
     */
    fun finalIdentity(): SourceIdentity? =
        SourceUrl.parse(response.finalUrl, identity.kind, identity.discoveryOrigin)
}

/** Why a fetched body was refused. */
enum class SourceRejectReason {
    NETWORK_FAILURE,
    HTTP_ERROR,
    REDIRECT_REJECTED,
    REDIRECT_LEFT_SOURCE,
    EMPTY_BODY,
    NOT_HTML,
    UNSUPPORTED_CONTENT_TYPE,
    UNSUPPORTED_SCHEMA,
    NO_HOSTS_TABLE,
    MISSING_REQUIRED_COLUMNS,
    NO_PARSEABLE_ROW,
    PARSE_FAILED,
    NO_DIRECTORY_MARKER,
    NO_ACCEPTABLE_MIRROR,
}

/** The outcome of structural validation for one fetched body. */
data class SourceValidation(
    val accepted: Boolean,
    val reason: SourceRejectReason? = null,
    val detail: String = "",
    val columnCount: Int = 0,
    val rowCount: Int = 0,
) {
    companion object {
        fun accept(detail: String, columnCount: Int = 0, rowCount: Int = 0) =
            SourceValidation(true, null, detail, columnCount, rowCount)

        fun reject(reason: SourceRejectReason, detail: String) =
            SourceValidation(false, reason, detail)
    }
}

/**
 * Stage 2C-A: acceptance is structural, never keyword-only.
 *
 * Order is fixed: HTTP status -> redirect confinement -> content type ->
 * schema (hosts table + required columns) -> parse sanity. A page that
 * merely contains "OpenVPN"/"SoftEther" — a search page, a maintenance
 * notice, an error interstitial, a captcha wall — has no hosts table and
 * is therefore rejected here, before the parser is ever called.
 */
object VpnGateSourceValidation {

    /** Canonical and mirror HTML must expose these columns. */
    val REQUIRED_HTML_COLUMNS = listOf("country", "host", "softether", "openvpn")

    /** Canonical CSV must expose at least these header names to be usable. */
    val REQUIRED_API_COLUMNS = listOf("HostName", "IP")

    private val HOSTS_TABLE_MARKERS = listOf("vg_hosts_table_id")

    private val DIRECTORY_MARKERS = listOf("vpngate.net mirror sites", "sites.aspx")

    private val REJECTED_HTML_CONTENT_TYPES = listOf(
        "application/json",
        "text/csv",
        "application/csv",
        "image/",
        "video/",
        "audio/",
        "application/zip",
        "application/pdf",
        "application/octet-stream",
    )

    private val INTERSTITIAL_MARKERS = listOf(
        "<title>captcha",
        "cf-browser-verification",
        "g-recaptcha",
        "checking your browser",
        "just a moment",
        "access denied",
        "are you a robot",
    )

    // ---- HTML (canonical AND mirror) ----

    fun validateHtml(source: FetchedSource): SourceValidation {
        val identity = source.identity
        val response = source.response

        val transport = validateTransport(source)
        if (transport != null) return transport

        val body = response.body?.takeIf { it.isNotBlank() } ?: return reject(
            SourceRejectReason.EMPTY_BODY,
            identity.sourceTag,
        )

        if (contentTypeRejected(response.contentType)) {
            return reject(
                SourceRejectReason.UNSUPPORTED_CONTENT_TYPE,
                "${identity.sourceTag}: content-type ${response.contentType}",
            )
        }

        // A CSV body on the HTML path means the source serves a format
        // whose schema is not defined here. That is refused, never handed
        // to the API parser on a guess.
        if (CSV_HEADER_RE.containsMatchIn(body)) {
            return reject(
                SourceRejectReason.UNSUPPORTED_SCHEMA,
                "${identity.sourceTag}: body is CSV, an undefined schema for this endpoint",
            )
        }

        val lowered = body.lowercase()
        if (INTERSTITIAL_MARKERS.any { it in lowered }) {
            return reject(SourceRejectReason.NOT_HTML, "${identity.sourceTag}: interstitial/denied page")
        }
        if (!looksLikeHtml(body)) {
            return reject(SourceRejectReason.NOT_HTML, "${identity.sourceTag}: not an HTML document")
        }

        val doc = vn.unlimit.vpngate.parser.VpnGateHtmlParser.makeSoup(body)
        val candidates = vn.unlimit.vpngate.parser.VpnGateHtmlParser.findHostsTables(doc)
        if (candidates.isEmpty()) {
            return reject(
                SourceRejectReason.NO_HOSTS_TABLE,
                "${identity.sourceTag}: no hosts table",
            )
        }

        val table = vn.unlimit.vpngate.parser.VpnGateHtmlParser.selectHostsTable(candidates)
        if (table == null) {
            return reject(
                SourceRejectReason.MISSING_REQUIRED_COLUMNS,
                "${identity.sourceTag}: required columns ${REQUIRED_HTML_COLUMNS.joinToString(",")} not all present",
            )
        }

        val colmap = vn.unlimit.vpngate.parser.VpnGateHtmlParser.buildColumnMap(table)
        val missing = REQUIRED_HTML_COLUMNS.filter { it !in colmap }
        if (missing.isNotEmpty()) {
            return reject(
                SourceRejectReason.MISSING_REQUIRED_COLUMNS,
                "${identity.sourceTag}: missing ${missing.joinToString(",")}",
            )
        }

        val records = try {
            vn.unlimit.vpngate.parser.VpnGateHtmlParser.parseHtml(body, identity.sourceTag)
        } catch (e: Exception) {
            return reject(
                SourceRejectReason.PARSE_FAILED,
                "${identity.sourceTag}: ${e.message}",
            )
        }
        if (records.isEmpty()) {
            return reject(
                SourceRejectReason.NO_PARSEABLE_ROW,
                "${identity.sourceTag}: no row with a valid hostname and IPv4",
            )
        }

        return SourceValidation.accept(
            "${identity.sourceTag}: ${colmap.size} columns, ${records.size} rows",
            columnCount = colmap.size,
            rowCount = records.size,
        )
    }

    // ---- Canonical CSV ----

    fun validateApi(source: FetchedSource): SourceValidation {
        val identity = source.identity
        val response = source.response

        val transport = validateTransport(source)
        if (transport != null) return transport

        val contentType = response.contentType?.lowercase()
        if (contentType != null && ("text/html" in contentType || "xhtml" in contentType)) {
            return reject(
                SourceRejectReason.UNSUPPORTED_CONTENT_TYPE,
                "${identity.sourceTag}: HTML body on the CSV endpoint",
            )
        }

        val body = response.body?.takeIf { it.isNotBlank() } ?: return reject(
            SourceRejectReason.EMPTY_BODY,
            identity.sourceTag,
        )

        val headerIndex = vn.unlimit.vpngate.parser.VpnGateApiParser.findApiHeader(body.lines())
        if (headerIndex < 0) {
            return reject(
                SourceRejectReason.UNSUPPORTED_SCHEMA,
                "${identity.sourceTag}: no #HostName header",
            )
        }

        val header = body.lines()[headerIndex]
        val missing = REQUIRED_API_COLUMNS.filter { it !in header }
        if (missing.isNotEmpty()) {
            return reject(
                SourceRejectReason.MISSING_REQUIRED_COLUMNS,
                "${identity.sourceTag}: missing ${missing.joinToString(",")}",
            )
        }

        val records = try {
            vn.unlimit.vpngate.parser.VpnGateApiParser.parseApi(body, identity.sourceTag)
        } catch (e: Exception) {
            return reject(
                SourceRejectReason.PARSE_FAILED,
                "${identity.sourceTag}: ${e.message}",
            )
        }
        if (records.isEmpty()) {
            return reject(
                SourceRejectReason.NO_PARSEABLE_ROW,
                "${identity.sourceTag}: no row with a valid hostname and IPv4",
            )
        }

        return SourceValidation.accept(
            "${identity.sourceTag}: ${records.size} rows",
            rowCount = records.size,
        )
    }

    // ---- Mirror directory ----

    fun validateDirectory(source: FetchedSource): SourceValidation {
        val identity = source.identity

        val transport = validateTransport(source)
        if (transport != null) return transport

        val body = source.response.body?.takeIf { it.isNotBlank() } ?: return reject(
            SourceRejectReason.EMPTY_BODY,
            identity.sourceTag,
        )

        val lowered = body.lowercase()
        val hasMarker = DIRECTORY_MARKERS.any { it in lowered } ||
            HOSTS_TABLE_MARKERS.any { it in body }
        if (!hasMarker) {
            return reject(
                SourceRejectReason.NO_DIRECTORY_MARKER,
                "${identity.sourceTag}: not a mirror directory page",
            )
        }

        val anchors = countAcceptableAnchors(body)
        if (anchors == 0) {
            return reject(
                SourceRejectReason.NO_ACCEPTABLE_MIRROR,
                "${identity.sourceTag}: no acceptable mirror link",
            )
        }

        return SourceValidation.accept("${identity.sourceTag}: $anchors mirror link(s)", rowCount = anchors)
    }

    // ---- shared ----

    private val CSV_HEADER_RE = Regex("^\\s*#?HostName\\s*,", RegexOption.MULTILINE)

    private fun validateTransport(source: FetchedSource): SourceValidation? {
        val response = source.response
        val identity = source.identity

        if (response.redirectRejected) {
            return reject(
                SourceRejectReason.REDIRECT_REJECTED,
                "${identity.sourceTag}: ${response.redirectRejectionReason}",
            )
        }
        if (response.status !in 200..299) {
            return reject(
                SourceRejectReason.HTTP_ERROR,
                "${identity.sourceTag}: HTTP ${response.status}",
            )
        }
        // Second, independent check: the reported final URL must still be
        // this source, so a fetch implementation that ignored the policy
        // still cannot smuggle a foreign body in.
        if (!identity.redirectPolicy().allowsFinal(response.finalUrl)) {
            return reject(
                SourceRejectReason.REDIRECT_LEFT_SOURCE,
                "${identity.sourceTag}: final url ${response.finalUrl} left the source",
            )
        }
        return null
    }

    private fun reject(reason: SourceRejectReason, detail: String): SourceValidation {
        CollectorLog.d("Source rejected: $detail")
        return SourceValidation.reject(reason, detail)
    }

    private fun looksLikeHtml(body: String): Boolean {
        val lowered = body.lowercase()
        return "<html" in lowered || "<!doctype html" in lowered ||
            "<table" in lowered || "<body" in lowered
    }

    private fun contentTypeRejected(contentType: String?): Boolean {
        if (contentType.isNullOrBlank()) return false
        val lowered = contentType.lowercase()
        return REJECTED_HTML_CONTENT_TYPES.any { lowered.startsWith(it) || lowered.contains(it) }
    }

    /** Number of mirror links in a directory page that pass the shape rules. */
    fun countAcceptableAnchors(html: String): Int {
        val doc = vn.unlimit.vpngate.parser.VpnGateHtmlParser.makeSoup(html)
        var count = 0
        for (a in doc.getElementsByTag("a")) {
            if (isAcceptableMirrorHref(a.attr("href"))) count++
        }
        return count
    }

    /**
     * The official directory's own rule: an absolute http(s) link whose
     * host is an IP literal or a dedicated `opengw.net` host, excluding
     * the canonical site and unrelated university pages.
     */
    fun isAcceptableMirrorHref(href: String?): Boolean {
        val clean = href?.trim().orEmpty()
        if (clean.isEmpty()) return false
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) return false

        val authority = try {
            java.net.URI(clean).authority?.lowercase()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        } ?: return false

        if ("vpngate.net" in authority) return false
        if ("tsukuba.ac.jp" in authority) return false
        if (authority.contains('@')) return false
        return SourceUrl.isAcceptableMirrorAuthority(authority)
    }
}
