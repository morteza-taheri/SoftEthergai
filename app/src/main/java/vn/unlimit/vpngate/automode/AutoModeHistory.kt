package vn.unlimit.vpngate.automode

import vn.unlimit.vpngate.history.NetworkType
import vn.unlimit.vpngate.history.ServerHistoryView

/**
 * F.29 §10 — the history-aware ordering, as a **tie-break inside equal quality**.
 *
 * ## Where it sits, and why that is the whole design
 *
 * The pipeline is unchanged:
 *
 * ```
 * withActiveRtt → sortedWith(byQuality) → [RTT pass] → [HISTORY pass] → reachability gate → servers
 * ```
 *
 * The history pass only ever permutes candidates that `byQuality` already
 * declared **equal** (the same `runStart..runEnd` window the RTT pass uses), so:
 *
 *  - a server with history can never outrank a server with better current
 *    quality — quality stays the sole ranking authority;
 *  - the reachability gate still runs afterwards and returns a *subsequence*, so
 *    a historical success can never re-admit an endpoint the probe removed;
 *  - Protocol Priority is untouched: this orders **servers**, never protocols.
 *
 * ## Tiers
 *
 * | tier | meaning |
 * |------|---------|
 * | 0 | verified success **on the current physical network type** |
 * | 1 | verified success, on some other network type |
 * | 2 | no conclusive history — a new server, no summary row, or only inconclusive outcomes |
 * | 3 | server-attributable failures since the last success |
 *
 * Three properties are deliberate:
 *
 *  1. **A missing summary row is tier 2, never tier 3.** "We know nothing" and
 *     "we know it failed" are different facts, and the ordering must not turn
 *     the first into the second. A brand-new server therefore keeps its quality
 *     position and stays fully eligible for exploration.
 *  2. **A timeout is tier 2.** `TIMED_OUT` never increments `consecutiveFailures`
 *     (see `HistoryCounters`), so a silent endpoint is inconclusive, not bad.
 *  3. **When the current network is `UNKNOWN`, tier 0 is unreachable.** A Wi-Fi
 *     success is not evidence about a network we could not identify, so such a
 *     server lands in tier 1 instead of being credited with a match.
 *
 * ## No invented numbers
 *
 * Every key is an enum ordinal, a stored counter, or a stored timestamp. There is
 * no weight, no score, no decay, no cooldown and no threshold — the only
 * predicates are `> 0`. Within a tier the order is: most recent success first,
 * then fewest consecutive failures, then fewest failures overall. Full ties keep
 * the incoming order (the sort is stable), so the result is deterministic and
 * reproducible from the same inputs.
 */
enum class HistoryTier {
    CURRENT_NETWORK_SUCCESS,
    OTHER_NETWORK_SUCCESS,
    INCONCLUSIVE,
    SERVER_FAILURES,
}

/**
 * The tier of [view] for the network we are on right now.
 *
 * `null` means this identity has no summary row at all.
 */
internal fun historyTierOf(view: ServerHistoryView?, network: NetworkType): HistoryTier {
    if (view == null) return HistoryTier.INCONCLUSIVE

    // Only claim a network match when the current network is actually known.
    if (network != NetworkType.UNKNOWN && view.successesOn(network) > 0) {
        return HistoryTier.CURRENT_NETWORK_SUCCESS
    }
    if (view.successCount > 0) return HistoryTier.OTHER_NETWORK_SUCCESS
    if (view.consecutiveFailures > 0) return HistoryTier.SERVER_FAILURES

    // Attempted (or not), but nothing conclusive: only timeouts, cancellations,
    // local failures, or no events at all.
    return HistoryTier.INCONCLUSIVE
}

/**
 * A **total, transitive** comparator over the history keys.
 *
 * It has to be transitive: `sortedWith` delegates to TimSort, which throws
 * `IllegalArgumentException: Comparison method violates its general contract!`
 * on real list sizes when a comparator is not. Every key here is a plain ordinal
 * or a stored number, compared in a fixed order, so the relation is a
 * lexicographic total preorder — unlike the RTT tie-break, which cannot be
 * expressed as a key at all and therefore uses an insertion pass instead.
 */
internal fun historyComparator(
    history: Map<String, ServerHistoryView>,
    network: NetworkType,
): Comparator<AutoModeCandidate> = Comparator { left, right ->
    val a = history.viewOf(left)
    val b = history.viewOf(right)

    val byTier = historyTierOf(a, network).ordinal.compareTo(historyTierOf(b, network).ordinal)
    if (byTier != 0) return@Comparator byTier

    // Most recent verified success first. 0 means "never", which sorts last
    // inside a tier that can only contain servers with a success anyway.
    val byRecency = (b?.lastSuccessAt ?: 0L).compareTo(a?.lastSuccessAt ?: 0L)
    if (byRecency != 0) return@Comparator byRecency

    val byStreak = (a?.consecutiveFailures ?: 0).compareTo(b?.consecutiveFailures ?: 0)
    if (byStreak != 0) return@Comparator byStreak

    (a?.failureCount ?: 0).compareTo(b?.failureCount ?: 0)
}

/**
 * Identity lookup for the ordering.
 *
 * `hostName.trim()` is the Room primary key and the same normalisation
 * `ServerListUpdates.mergeAll` uses (`stored.associateBy { it.hostName.trim() }`),
 * so the summary row that was written for a server is the row that is read for
 * it. A candidate with no hostname has no identity and therefore no history —
 * `null` view, tier 2.
 */
internal fun Map<String, ServerHistoryView>.viewOf(candidate: AutoModeCandidate): ServerHistoryView? {
    val key = candidate.hostname?.trim() ?: return null
    if (key.isEmpty()) return null
    return this[key]
}
