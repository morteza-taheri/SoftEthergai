package vn.unlimit.vpngate.data.remote

import kotlinx.coroutines.withTimeoutOrNull
import vn.unlimit.vpngate.data.model.CollectorLog

/**
 * HTTP boundary for **user-configured emergency CSV sources**
 * (`SERVER_SOURCE_ARCHITECTURE.md` §2.5 path 3, §10.1).
 *
 * This class exists so the emergency rung reuses the project's one HTTP
 * stack instead of growing a second one: the caller injects the same
 * [HttpFetcher] the canonical collector uses, and every transport property —
 * `ResilientDns`, the 10 s connect / 15 s read pair, manual per-hop redirect
 * handling — is inherited from `collectorClientBuilder()`. §2.6 asks for
 * exactly that ("Emergency: existing pair; retry each enabled source once").
 *
 * Three deliberate restrictions:
 *
 *  1. **No VPN binding.** The default fetcher is the open-network client.
 *     The emergency rung is by definition reached *after* the open and VPN
 *     rungs have both produced nothing (§2.5), so re-binding to a tunnel
 *     that just failed would replay a failure; and an emergency URL points
 *     at a host the app knows nothing about, where binding is a loop risk
 *     rather than a bypass (§2.4). A caller that wants binding can inject a
 *     bound fetcher — the class does not forbid it, it just does not choose
 *     it.
 *  2. **The source's own authority is the only authority allowed.** The
 *     redirect policy is the source's own ([SourceIdentity.redirectPolicy]),
 *     so an emergency URL can never be walked onto a foreign host. Combined
 *     with `OkHttpFetcher`'s per-hop checks this is the confinement
 *     `SourceValidation.validateTransport` applies to every other source.
 *  3. **No parsing, no persistence.** This class answers "did we get a
 *     body, and was it small enough to be worth reading". Deciding what the
 *     body *means* is [vn.unlimit.vpngate.parser.EmergencyCsvParser]'s job,
 *     and whether it may be stored is `ServerListUpdater`'s. Keeping the
 *     three apart is what makes each unit-testable offline.
 *
 * Pure JVM: [HttpFetcher] is an interface, so tests supply a fake and no
 * socket is ever opened.
 */
class EmergencySourceFetcher(
    private val fetcher: HttpFetcher = OkHttpFetcher(),
) {

    /**
     * One source, one bounded attempt chain.
     *
     * @param url the user-configured raw URL, taken verbatim from the
     *   stored configuration. It is re-validated here rather than trusted,
     *   because the configuration was written at a different time.
     */
    suspend fun fetch(url: String): FetchResult {
        val identity = SourceUrl.parse(url, SourceKind.EMERGENCY_CSV)
            ?: return FetchResult.NetworkError("not a usable http(s) source url")

        var last: FetchResult = FetchResult.NetworkError("no attempt was made")

        // §2.6: "Emergency: existing pair; retry each enabled source once",
        // under one deadline so a cascading fallback cannot run unbounded.
        val outcome = withTimeoutOrNull(DEADLINE_MS) {
            for (attempt in 1..ATTEMPTS) {
                val result = attempt(identity)
                last = result
                if (result is FetchResult.Success) return@withTimeoutOrNull result
                CollectorLog.d(
                    "Emergency source attempt $attempt/$ATTEMPTS for ${identity.url}: $result",
                )
            }
            last
        }

        return outcome ?: FetchResult.NetworkError(
            "deadline of ${DEADLINE_MS}ms exceeded for ${identity.url}",
        )
    }

    private suspend fun attempt(identity: SourceIdentity): FetchResult {
        val response = try {
            fetcher.get(identity.url, identity.redirectPolicy())
        } catch (e: kotlinx.coroutines.CancellationException) {
            // BLOCK v1.0.76 audit fix: rethrow cancellation before the generic
            // catch turns it into an ordinary network error.
            throw e
        } catch (e: Exception) {
            // A fetcher that throws instead of returning null must not be
            // able to abort the whole update; §2.7 preserves the stored list.
            return FetchResult.NetworkError("${e.javaClass.simpleName}: ${e.message}")
        } ?: return FetchResult.NetworkError("no response")

        if (response.redirectRejected) {
            return FetchResult.NetworkError(
                "redirect rejected: ${response.redirectRejectionReason ?: "unspecified"}",
            )
        }
        if (response.status !in 200..299) {
            return FetchResult.HttpError(response.status)
        }

        val body = response.body
        if (body.isNullOrBlank()) return FetchResult.NetworkError("empty body")

        if (body.length > MAX_BODY_CHARS) {
            return FetchResult.TooLarge(body.length)
        }

        // The reported final URL must still be this source: a fetcher that
        // ignored its own redirect policy must not be able to smuggle a
        // foreign body in.
        if (!identity.redirectPolicy().allowsFinal(response.finalUrl)) {
            return FetchResult.NetworkError(
                "final url ${response.finalUrl} left the source",
            )
        }

        return FetchResult.Success(body)
    }

    /** What one emergency source was able to provide. */
    sealed interface FetchResult {

        /** A body that is small enough to be worth parsing. */
        data class Success(val body: String) : FetchResult

        /** The source answered and refused: a stale or wrong URL. */
        data class HttpError(val status: Int) : FetchResult

        /**
         * Nothing usable came back — offline, DNS failure, timeout, an empty
         * body, or a redirect that left the source.
         */
        data class NetworkError(val reason: String) : FetchResult

        /**
         * A body beyond [MAX_BODY_CHARS]. Refused rather than truncated:
         * a silently cut CSV would parse as a valid, smaller dataset, which
         * is exactly the "truncated remnant" §2.7 exists to refuse.
         */
        data class TooLarge(val length: Int) : FetchResult
    }

    companion object {
        /**
         * One initial attempt plus one retry (§2.6).
         */
        const val ATTEMPTS = 2

        /**
         * Per-source deadline.
         *
         * The transport itself is bounded by the shared client's 10 s
         * connect / 15 s read timeouts, so this covers two attempts plus the
         * retry decision. It is a ceiling, not a target: a healthy source
         * returns in one round trip.
         */
        const val DEADLINE_MS = 40_000L

        /**
         * Largest emergency body accepted, in characters.
         *
         * A `servers.csv` is hand-authored or generated from a capped
         * dataset (§10.9 Q3 discusses a top-N cap). 1 MiB is ~10k rows —
         * orders of magnitude beyond any legitimate emergency file — and
         * bounds both parse time and how much user-configured text the
         * process holds at once.
         *
         * Honest limitation: [HttpFetcher] returns a fully decoded `String`,
         * so this bounds parsing and retention, not the bytes on the wire.
         * The download itself is bounded by the shared client's read timeout.
         * Closing that gap would mean a second HTTP client, which this phase
         * deliberately does not build.
         */
        const val MAX_BODY_CHARS = 1024 * 1024
    }
}
