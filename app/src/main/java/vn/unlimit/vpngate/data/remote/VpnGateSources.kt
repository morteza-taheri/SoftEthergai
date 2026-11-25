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
/**
 * v1.0.87: fallbacks for callers that do not supply a budget (tests, the
 * debug panel). Production always passes a value derived from the user's
 * configured update timeout.
 */
const val DEFAULT_SOURCE_TIMEOUT_MS = 12_000L
const val DEFAULT_DIRECTORY_TIMEOUT_MS = 8_000L

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
     * These are reached by IP and expose no TLS endpoint, so they are the only
     * cleartext (HTTP) hosts this app talks to. They are NOT a static security
     * boundary any more: `CleartextMirrorPolicy` permits cleartext for these
     * addresses, for any mirror discovered from the official directory, and for
     * any global IPv4 inside the mirror hosting ranges — because these
     * addresses rotate. See `CleartextMirrorPolicy.kt`.
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

    /**
     * v1.0.88 — make [identities] fetchable over cleartext **before** they are
     * handed to a fetcher.
     *
     * Device log, 2026-11-25, first run after the mirror race was introduced:
     *
     *     GET http://150.40.105.5:32536/en/            -> answered
     *     CLEARTEXT-BLOCKED host=122.133.207.17 ...    -> refused locally
     *     CLEARTEXT-BLOCKED host=14.7.148.12   ...     -> refused locally
     *     Mirrors available: 12 (discovered=5, cached=12, bootstrap=6)
     *
     * Eight of the twelve mirrors in that run were cached addresses the app
     * itself had stored on a previous refresh, and its own cleartext policy
     * refused all of them: the registry was only fed by
     * [numberedMirrors], which runs *after* the directory race had already
     * fired requests at the cached set. The result was a 12-slot mirror pool
     * with 4 usable slots — the app paid for the fan-out and could not use it.
     *
     * Registration is deliberately keyed on identities that have already been
     * through [SourceUrl.forMirror] (a global IPv4 literal with an explicit
     * port, or a validated host), so this cannot be used to widen cleartext
     * access to an arbitrary authority.
     */
    fun registerForCleartext(identities: Collection<SourceIdentity>): Int {
        if (identities.isEmpty()) return CleartextMirrorPolicy.registeredHosts().size
        return CleartextMirrorPolicy.registerAll(identities.map { it.baseUrl })
    }

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
        // Register before any of them is fetched, so the cleartext interceptor
        // already knows this run's mirror set. Without this a freshly
        // discovered mirror would be refused by the app's own policy.
        CleartextMirrorPolicy.registerAll(combined.map { it.baseUrl })
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
 *
 * v1.0.87: [timeoutMs] is supplied by the repository from the user's configured
 * update budget. It used to be a hard-coded 12 s, which silently capped every
 * refresh regardless of the Settings value — a 120 s setting still aborted each
 * source after 12 s.
 */
class VpnGateHtmlSource(
    private val fetcher: HttpFetcher,
    private val timeoutMs: Long = DEFAULT_SOURCE_TIMEOUT_MS,
) {
    suspend fun fetch(extraCandidates: List<SourceIdentity> = emptyList()): FetchedSource? {
        // v1.0.88: same ordering rule as the directory race — a mirror must be
        // registered before it is fetched, otherwise the app's own cleartext
        // policy refuses it and the race is decided by the canonical host
        // alone, which is exactly the host a censored network cannot reach.
        VpnGateSourceCandidates.registerForCleartext(extraCandidates)
        val candidates = VpnGateSourceCandidates.canonicalHtml() +
            extraCandidates.map { it.withPath(VpnGateUrls.HTML_PATH) }
        return raceFetch(
            candidates,
            fetcher,
            validate = { VpnGateSourceValidation.validateHtml(it) },
            timeoutMs = timeoutMs,
        )
    }
}

/**
 * The canonical CSV feed. Mirror endpoints on this path are intentionally
 * NOT candidates: a mirror's CSV schema is not defined in this project,
 * and an undefined schema is refused rather than parsed on a guess.
 */
class VpnGateApiSource(
    private val fetcher: HttpFetcher,
    private val timeoutMs: Long = DEFAULT_SOURCE_TIMEOUT_MS,
) {
    suspend fun fetch(extraCandidates: List<SourceIdentity> = emptyList()): FetchedSource? {
        val candidates = VpnGateSourceCandidates.canonicalApi() + extraCandidates
        return raceFetch(
            candidates,
            fetcher,
            validate = { VpnGateSourceValidation.validateApi(it) },
            timeoutMs = timeoutMs,
        )
    }
}

/** Discovery of official mirrors from the canonical `sites.aspx` directory. */
class VpnGateMirrorSource(
    private val fetcher: HttpFetcher,
    private val directoryTimeoutMs: Long = DEFAULT_DIRECTORY_TIMEOUT_MS,
    private val pageTimeoutMs: Long = DEFAULT_SOURCE_TIMEOUT_MS,
) {

    /**
     * Discover official mirrors and return them as numbered identities.
     * The persisted cache is a list of URLs; each one is turned back into
     * an identity deterministically, and anything that is not a usable
     * mirror URL is dropped instead of being trusted.
     */
    suspend fun discoverMirrors(cachedMirrorUrls: List<String> = emptyList()): List<SourceIdentity> {
        val cached = VpnGateSourceCandidates.cachedMirrors(cachedMirrorUrls)
        val bootstrap = VpnGateSourceCandidates.bootstrapMirrors()

        // v1.0.88: register BEFORE the race, not in `numberedMirrors` after it.
        // See `registerForCleartext` for the device evidence.
        VpnGateSourceCandidates.registerForCleartext(cached + bootstrap)

        val discoveryCandidates =
            VpnGateSourceCandidates.canonicalDirectory() +
                cached.map { it.withPath(VpnGateUrls.DIRECTORY_PATH) } +
                bootstrap.map { it.withPath(VpnGateUrls.DIRECTORY_PATH) }

        val page = raceFetch(
            discoveryCandidates,
            fetcher,
            validate = { VpnGateSourceValidation.validateDirectory(it) },
            timeoutMs = directoryTimeoutMs,
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
            timeoutMs = pageTimeoutMs,
        )
    }
}
