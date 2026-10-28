package vn.unlimit.vpngate.data.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import vn.unlimit.vpngate.data.model.CollectorLog
import vn.unlimit.vpngate.parser.VpnGateHtmlParser

/**
 * The live sources of the collector, as typed identities.
 *
 * Stage 2C-A: every candidate is a [SourceIdentity] from the start, so a
 * mirror can never be reported as the canonical feed, and a body is only
 * accepted after HTTP, redirect, content-type, schema and parse-sanity
 * checks have all passed.
 */
object VpnGateUrls {
    const val MAIN_URL = "https://www.vpngate.net/en/"
    const val API_URL = "https://www.vpngate.net/api/iphone/"
    const val MIRRORS_URL = "https://www.vpngate.net/en/sites.aspx"
    const val MAX_MIRRORS = 12

    const val HTML_PATH = "/en/"
    const val API_PATH = "/api/iphone/"
    const val DIRECTORY_PATH = "/en/sites.aspx"

    /**
     * Reliable official IP mirrors provided by VPN Gate project to bypass DNS/SNI
     * censorship.
     *
     * SEC-02: these are reached by IP and expose no TLS endpoint, so they are the
     * ONLY cleartext (HTTP) hosts the app permits. Each IP is listed explicitly in
     * `app/src/main/res/xml/network_security_config.xml` under a
     * `<domain-config cleartextTrafficPermitted="true">`. Every other host —
     * including `www.vpngate.net`, whose HTTP fallback URLs were removed — is
     * HTTPS-only.
     */
    val BOOTSTRAP_MIRRORS = listOf(
        "http://150.40.105.19:35399",
        "http://150.40.105.6:11803",
        "http://150.40.105.11:4917",
        "http://150.40.105.23:64629",
        "http://194.156.89.134:47774",
        "http://121.186.186.97:40587",
    )
}

/**
 * Builds the candidate list per source class. Canonical and mirror
 * candidates never share an identity object, so "the mirror won the race"
 * is visible in the logs and in every record's provenance.
 */
object VpnGateSourceCandidates {

    fun canonicalHtml(): List<SourceIdentity> = listOfNotNull(
        SourceUrl.parse(VpnGateUrls.MAIN_URL, SourceKind.CANONICAL_HTML),
    )

    fun canonicalApi(): List<SourceIdentity> = listOfNotNull(
        SourceUrl.parse(VpnGateUrls.API_URL, SourceKind.CANONICAL_API),
    )

    fun canonicalDirectory(): List<SourceIdentity> = listOfNotNull(
        SourceUrl.parse(VpnGateUrls.MIRRORS_URL, SourceKind.CANONICAL_HTML)
            ?.withPath(VpnGateUrls.DIRECTORY_PATH),
    )

    fun bootstrapMirrors(): List<SourceIdentity> = VpnGateUrls.BOOTSTRAP_MIRRORS.mapNotNull {
        SourceUrl.forMirror(it, SourceKind.BOOTSTRAP_MIRROR, MirrorOrigin.BOOTSTRAP_LIST)
    }

    /** Cached mirror URLs become identities deterministically; junk is dropped. */
    fun cachedMirrors(urls: List<String>): List<SourceIdentity> = urls.mapNotNull { url ->
        val identity = SourceUrl.forMirror(url, SourceKind.CACHED_MIRROR, MirrorOrigin.MIRROR_CACHE)
        if (identity == null) {
            CollectorLog.d("Mirror cache entry ignored (not a usable mirror URL): $url")
        }
        identity
    }

    /**
     * The mirror set for one run: discovered, then cached, then the
     * built-in bootstrap list, de-duplicated by authority and capped.
     * Each entry gets a stable `mirror_<n>` id, which is the source tag
     * every record collected from that mirror will carry.
     */
    fun numberedMirrors(
        discovered: List<SourceIdentity>,
        cached: List<SourceIdentity>,
    ): List<SourceIdentity> {
        val combined = (discovered + cached + bootstrapMirrors())
            .distinctBy { it.baseUrl }
            .take(VpnGateUrls.MAX_MIRRORS)
        return combined.mapIndexed { index, identity ->
            identity.withMirrorId(MIRROR_TAG_PREFIX + (index + 1))
        }
    }

    const val MIRROR_TAG_PREFIX = "mirror_"
}

/**
 * Concurrent race, kept for latency, but acceptance is now a structural
 * validation rather than a keyword test. First VALID body wins; a body
 * that only satisfies a keyword predicate can no longer win.
 */
private suspend fun raceFetch(
    candidates: List<SourceIdentity>,
    fetcher: HttpFetcher,
    validate: (FetchedSource) -> SourceValidation,
    timeoutMs: Long = 12_000L,
): FetchedSource? = coroutineScope {
    val result = CompletableDeferred<FetchedSource>()

    val jobs = candidates.distinctBy { it.url }.map { identity ->
        launch(Dispatchers.IO) {
            runCatching {
                val response = fetcher.get(identity.url, identity.redirectPolicy())
                if (response == null) {
                    CollectorLog.d("No response from ${identity.sourceTag}: ${identity.url}")
                    return@runCatching
                }
                val source = FetchedSource(identity, response)
                val check = validate(source)
                if (check.accepted) {
                    CollectorLog.d("Accepted ${identity.sourceTag} from ${identity.url} (${check.detail})")
                    if (result.complete(source)) {
                        CollectorLog.d("Fastest valid response from: ${identity.url}")
                    }
                } else {
                    CollectorLog.d(
                        "Rejected ${identity.sourceTag} from ${identity.url}: ${check.reason} (${check.detail})",
                    )
                }
            }
        }
    }

    launch {
        jobs.joinAll()
        if (!result.isCompleted) {
            result.completeExceptionally(NoSuchElementException())
        }
    }

    try {
        withTimeoutOrNull(timeoutMs) { result.await() }
    } catch (e: kotlinx.coroutines.CancellationException) {
        // BLOCK v1.0.76 audit fix: a user/lifecycle cancellation must never be
        // swallowed into a "no result" — it would let fallbacks run after the
        // caller gave up. Rethrow; only the timeout itself yields null.
        throw e
    } catch (e: Throwable) {
        null
    } finally {
        jobs.forEach { it.cancel() }
    }
}

/**
 * Canonical + mirror HTML. Every accepted body keeps its own identity:
 * `"html"` for the canonical page, `mirror_<n>` for a mirror.
 */
class VpnGateHtmlSource(private val fetcher: HttpFetcher) {
    suspend fun fetch(extraCandidates: List<SourceIdentity> = emptyList()): FetchedSource? {
        val candidates = VpnGateSourceCandidates.canonicalHtml() +
            extraCandidates.map { it.withPath(VpnGateUrls.HTML_PATH) }
        return raceFetch(
            candidates,
            fetcher,
            validate = { VpnGateSourceValidation.validateHtml(it) },
            timeoutMs = 12_000L,
        )
    }
}

/**
 * The canonical CSV feed. Mirror endpoints on this path are intentionally
 * NOT candidates: a mirror's CSV schema is not defined in this project,
 * and an undefined schema is refused rather than parsed on a guess.
 */
class VpnGateApiSource(private val fetcher: HttpFetcher) {
    suspend fun fetch(extraCandidates: List<SourceIdentity> = emptyList()): FetchedSource? {
        val candidates = VpnGateSourceCandidates.canonicalApi() + extraCandidates
        return raceFetch(
            candidates,
            fetcher,
            validate = { VpnGateSourceValidation.validateApi(it) },
            timeoutMs = 12_000L,
        )
    }
}

/** Discovery of official mirrors from the canonical `sites.aspx` directory. */
class VpnGateMirrorSource(private val fetcher: HttpFetcher) {

    /**
     * Discover official mirrors and return them as numbered identities.
     * The persisted cache is a list of URLs; each one is turned back into
     * an identity deterministically, and anything that is not a usable
     * mirror URL is dropped instead of being trusted.
     */
    suspend fun discoverMirrors(cachedMirrorUrls: List<String> = emptyList()): List<SourceIdentity> {
        val cached = VpnGateSourceCandidates.cachedMirrors(cachedMirrorUrls)
        val bootstrap = VpnGateSourceCandidates.bootstrapMirrors()

        val discoveryCandidates =
            VpnGateSourceCandidates.canonicalDirectory() +
                cached.map { it.withPath(VpnGateUrls.DIRECTORY_PATH) } +
                bootstrap.map { it.withPath(VpnGateUrls.DIRECTORY_PATH) }

        val page = raceFetch(
            discoveryCandidates,
            fetcher,
            validate = { VpnGateSourceValidation.validateDirectory(it) },
            timeoutMs = 8_000L,
        )

        val discovered = if (page == null) {
            emptyList()
        } else {
            extractMirrors(page.body)
        }

        val combined = VpnGateSourceCandidates.numberedMirrors(discovered, cached)
        CollectorLog.d(
            "Mirrors available: ${combined.size} " +
                "(discovered=${discovered.size}, cached=${cached.size}, bootstrap=${bootstrap.size})",
        )
        return combined
    }

    /** Mirror links that pass the official directory's own shape rules. */
    fun extractMirrors(html: String): List<SourceIdentity> {
        val discovered = mutableListOf<SourceIdentity>()
        val doc = VpnGateHtmlParser.makeSoup(html)
        for (a in doc.getElementsByTag("a")) {
            val href = a.attr("href").trim()
            if (!VpnGateSourceValidation.isAcceptableMirrorHref(href)) continue
            val clean = href.trimEnd('/')
            val identity = SourceUrl.forMirror(
                clean,
                SourceKind.DISCOVERED_MIRROR,
                MirrorOrigin.CANONICAL_DIRECTORY,
            ) ?: continue
            if (discovered.none { it.baseUrl == identity.baseUrl }) {
                discovered.add(identity)
            }
        }
        return discovered
    }

    suspend fun fetchMirror(url: String): FetchedSource? {
        val identity = SourceUrl.forMirror(url, SourceKind.DISCOVERED_MIRROR)
            ?: return withContext(Dispatchers.IO) { null }
        return raceFetch(
            listOf(identity.withPath(VpnGateUrls.HTML_PATH)),
            fetcher,
            validate = { VpnGateSourceValidation.validateHtml(it) },
        )
    }
}
