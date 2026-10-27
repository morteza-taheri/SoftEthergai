package vn.unlimit.vpngate.repository

import vn.unlimit.vpngate.models.VPNGateItem

/**
 * Stage 2C-B: dataset-level integrity.
 *
 * A dataset is "usable" one row at a time (see [ServerListUpdater.isUsable])
 * and a dataset is "downloadable" when its sources validated and it parsed.
 * Neither of those makes a dataset WORTH STORING: a truncated table, a
 * partially rendered page or a schema drift can produce a small, perfectly
 * valid, completely wrong server list. Replacing a healthy list with it is
 * silent data loss, so the candidate is compared against what is already
 * stored before anything is written.
 *
 * What counts as "last known good" is defined by this file plus the storage
 * acceptance in [ServerListUpdater]: downloaded, parsed and non-empty are
 * explicitly NOT sufficient.
 */

/**
 * What the sources of one run were actually able to provide.
 *
 * Source capability is NOT data corruption. The canonical CSV feed carries
 * OpenVPN profiles but never SoftEther/SSTP/L2TP facts, so a run where only
 * the API answered must not be judged as having "lost" those protocols. The
 * Stage 2C-A source identities decide which checks apply.
 */
data class SourceCapability(
    /** An HTML-shaped source (canonical or mirror) contributed its protocol columns. */
    val providesProtocolColumns: Boolean,
    /** The canonical CSV API contributed OpenVPN profiles. */
    val providesOpenVpnProfiles: Boolean,
) {
    companion object {
        /** Canonical HTML and/or API both answered. */
        val FULL = SourceCapability(providesProtocolColumns = true, providesOpenVpnProfiles = true)

        /** Only an HTML-shaped source answered: profiles are impossible, not missing. */
        val HTML_ONLY = SourceCapability(providesProtocolColumns = true, providesOpenVpnProfiles = false)

        /** Only the CSV API answered: SoftEther/SSTP/L2TP are impossible, not missing. */
        val API_ONLY = SourceCapability(providesProtocolColumns = false, providesOpenVpnProfiles = true)

        /**
         * Default when a caller cannot report what it fetched: assume the
         * run could have provided everything, so every check stays active.
         * Losing a dataset is worse than keeping a stale one.
         */
        val UNKNOWN = FULL
    }
}

/**
 * Typed holder for the capability of the most recent fetch, so the updater
 * can consult it without a mutable field on the updater itself. Written by
 * the fetch lambda, read once after it returns, and serialized by the
 * updater's process-wide gate.
 */
class LastFetchCapability {
    @Volatile
    var value: SourceCapability = SourceCapability.UNKNOWN
}

/**
 * Dataset-level capability counts. These are dataset totals, never a
 * requirement that individual servers support anything in particular.
 */
data class DatasetCoverage(
    val total: Int = 0,
    val softEtherTcp: Int = 0,
    val softEtherUdp: Int = 0,
    val sstp: Int = 0,
    val openVpnTcp: Int = 0,
    val openVpnUdp: Int = 0,
    val l2tp: Int = 0,
    val openVpnProfiles: Int = 0,
)

/** Why a candidate dataset was refused. All of these are degradations, not parse errors. */
enum class DatasetDefect {
    NONE,
    TOO_SMALL,
    SIZE_COLLAPSE,
    PROTOCOL_COLLAPSE,
    PROFILE_COLLAPSE,
}

/** The verdict for one candidate dataset. */
data class DatasetVerdict(
    val accepted: Boolean,
    val defect: DatasetDefect = DatasetDefect.NONE,
    val detail: String = "",
)

/**
 * Z1-IMPL-G (Roadmap §12.5.2 item G): the three acceptance outcomes named by
 * `SERVER_SOURCE_ARCHITECTURE.md` §9.6 and §10.8.
 *
 * **Read this before extending it — the vocabulary and the behaviour are two
 * different things here.**
 *
 * The names are taken verbatim from the architecture document. What this
 * revision adds is the *classification*, not a new decision: §9.6 says the
 * outcomes "layer on top of the existing `DatasetIntegrity` threshold logic
 * rather than replacing it", and that is exactly what [DatasetIntegrity.outcome]
 * does. Every threshold in [DatasetIntegrity.evaluate] is untouched, so which
 * datasets are accepted has not moved.
 *
 * What the names mean in the document, and how much of it exists today:
 *
 *  - [REJECT_DEGRADED] is implemented **in substance**. It is the existing
 *    rejection path: a dataset that fails a threshold, or is corrupt, empty or
 *    below the floor, is refused and the stored dataset is left untouched. §9.6
 *    — "a degraded dataset must never replace or prune a known-good one" — is
 *    what this class has always enforced, now with the name it was given.
 *  - [ACCEPT_NEW] and [MERGE_WITH_EXISTING] are **names only**. In the document
 *    both mean *cumulative addition*: §9.6 says "add all new records" and "add
 *    new records only; never drop existing healthy servers", and §9.1 says "the
 *    database grows across updates rather than being replaced by the latest
 *    snapshot". That behaviour is **Cumulative Server Retention** (§9.2's
 *    `firstSeenAt` / `lastSeenAt` / `healthScore` fields, a new Room migration,
 *    the §9.3 priority algorithm and the §9.4 settings), which is a separate
 *    phase and is explicitly **not** implemented here. Until it is, an accepted
 *    dataset is still *replaced*, so classifying a write as
 *    `MERGE_WITH_EXISTING` says "the stored list is being superseded by a
 *    comparable one", not "the two were added together".
 *
 * The distinction is kept explicit in the code rather than papered over,
 * because a future reader who sees `MERGE_WITH_EXISTING` and assumes merging
 * would be reading a promise the implementation has not made yet.
 */
enum class DatasetOutcome {
    /** No comparable stored dataset, so this is the first list the app holds. */
    ACCEPT_NEW,

    /**
     * A comparable stored dataset exists and the candidate was accepted, so
     * the stored list is superseded. **Not** an add-only merge — see the type
     * KDoc; that semantics is Cumulative Server Retention (§9.1).
     */
    MERGE_WITH_EXISTING,

    /**
     * The candidate is degraded: it failed a threshold, or it is corrupt,
     * empty or below the absolute floor. The stored dataset is left untouched.
     */
    REJECT_DEGRADED,
}

/**
 * The dataset quality model.
 *
 * Thresholds and the evidence behind each of them (see the Stage 2C-B
 * report):
 *
 *  - [MIN_ACCEPTABLE_DATASET_SIZE]: every real dataset captured in this
 *    repository is far larger — the bundled seed holds 99 rows / 96
 *    distinct servers, the live canonical HTML capture 23 servers and the
 *    live nested HTML capture 14 (the parser test itself demands at least
 *    12). A floor of 10 therefore sits below every real capture and above
 *    the 1–3 row artifacts a truncated or partially rendered page produces.
 *  - [MIN_RELATIVE_DATASET_RATIO]: the stored list is always a complete
 *    feed, never a filtered subset, while a partial live capture is only
 *    ~12–15% of a full feed. Losing more than half of the stored rows in
 *    one refresh is therefore not natural churn. 0.5 is deliberately
 *    conservative: it only rejects severe collapse, and it is the value to
 *    revisit if day-to-day feed volatility is ever measured.
 *  - [MIN_PROTOCOL_COVERAGE_RATIO] / [MIN_PROTOCOL_COVERAGE_BASELINE]: a
 *    lost source column removes a protocol from every row at once, so a
 *    protocol's server count cannot halve by chance; below 20 stored
 *    servers a protocol count is noise and the check is skipped.
 *  - [MIN_PROFILE_COVERAGE_RATIO] / [MIN_PROFILE_COVERAGE_BASELINE]: same
 *    reasoning for OpenVPN profiles, which come from a separate feed and
 *    can also be truncated on their own.
 */
object DatasetIntegrity {

    /** A dataset smaller than this is never stored, on a fresh install or over an existing list. */
    const val MIN_ACCEPTABLE_DATASET_SIZE = 10

    /** Candidate must hold at least this share of the stored row count. */
    const val MIN_RELATIVE_DATASET_RATIO = 0.5

    /** A protocol's server count may not fall below this share of the stored count. */
    const val MIN_PROTOCOL_COVERAGE_RATIO = 0.5

    /** Below this stored count a protocol count is too small to judge. */
    const val MIN_PROTOCOL_COVERAGE_BASELINE = 20

    /** A candidate must hold at least this share of the stored OpenVPN profiles. */
    const val MIN_PROFILE_COVERAGE_RATIO = 0.5

    /** Below this stored profile count the profile check is skipped. */
    const val MIN_PROFILE_COVERAGE_BASELINE = 20

    private val PROTOCOLS = listOf(
        "SoftEther TCP" to { c: DatasetCoverage -> c.softEtherTcp },
        "SoftEther UDP" to { c: DatasetCoverage -> c.softEtherUdp },
        "SSTP" to { c: DatasetCoverage -> c.sstp },
        "OpenVPN TCP" to { c: DatasetCoverage -> c.openVpnTcp },
        "OpenVPN UDP" to { c: DatasetCoverage -> c.openVpnUdp },
        "L2TP/IPsec" to { c: DatasetCoverage -> c.l2tp },
    )

    /**
     * Dataset-level capability counts.
     *
     * SoftEther UDP counts servers that offer UDP at all, including those
     * with no published port: "supported with an unknown port" is a real,
     * useful state and must not be scored as absent.
     */
    fun coverage(items: List<VPNGateItem>): DatasetCoverage {
        var seTcp = 0
        var seUdp = 0
        var sstp = 0
        var ovpnTcp = 0
        var ovpnUdp = 0
        var l2tp = 0
        var profiles = 0

        for (item in items) {
            if (item.seTcpPort > 0) seTcp++
            if (item.seUdpSupported || item.seUdpPort > 0) seUdp++
            if (item.isSSTPSupport) sstp++
            if (item.tcpPort > 0) ovpnTcp++
            if (item.udpPort > 0) ovpnUdp++
            if (item.isL2TPSupport) l2tp++
            if (!item.openVpnConfigData.isNullOrBlank()) profiles++
        }

        return DatasetCoverage(
            total = items.size,
            softEtherTcp = seTcp,
            softEtherUdp = seUdp,
            sstp = sstp,
            openVpnTcp = ovpnTcp,
            openVpnUdp = ovpnUdp,
            l2tp = l2tp,
            openVpnProfiles = profiles,
        )
    }

    /**
     * Evaluate one candidate dataset against what is already stored.
     *
     * @param candidate the merged, normalized, individually usable dataset.
     * @param stored the currently stored dataset; empty means "nothing stored
     *   yet", in which case only the absolute floor applies.
     * @param capability what this run's sources could provide; checks that a
     *   source is incapable of satisfying are skipped rather than failing.
     */
    fun evaluate(
        candidate: List<VPNGateItem>,
        stored: List<VPNGateItem>,
        capability: SourceCapability,
        /**
         * BLOCK v1.0.75 FIX: the size of the last ACCEPTED fresh dataset, when
         * the caller knows it. 0 = unknown, in which case the baseline is
         * derived from [stored] (the pre-fix behaviour).
         */
        lastAcceptedSize: Int = 0,
    ): DatasetVerdict {
        if (candidate.size < MIN_ACCEPTABLE_DATASET_SIZE) {
            return DatasetVerdict(
                accepted = false,
                defect = DatasetDefect.TOO_SMALL,
                detail = "candidate has ${candidate.size} server(s), floor is $MIN_ACCEPTABLE_DATASET_SIZE",
            )
        }

        // BLOCK v1.0.75 FIX (device evidence 2026-10-27: the server list never
        // updated). `stored` is the RETENTION union: mergeAll keeps every server
        // it has ever seen (up to the 500 cap) and refreshes `lastSeenAt` only
        // for the rows the new feed mentioned. A live feed is ~100 servers while
        // the union had grown to 400, so `candidate < stored * 0.5` tripped on
        // EVERY refresh and the list froze at whatever it had accumulated.
        //
        // The comparable baseline is the LAST FRESH OBSERVATION — the stored rows
        // carrying the newest `lastSeenAt`, i.e. exactly the set the previous
        // accepted refresh stamped (`mergeAll` sets `lastSeenAt = now` for every
        // incoming row and leaves absent rows untouched). A genuinely truncated
        // response is still caught (10 of 100 is < 50 %); a legitimately smaller
        // live feed is not. When no row carries a timestamp, the union is used,
        // which is the previous behaviour.
        val previousSeenAt = stored.maxOfOrNull { it.lastSeenAt } ?: 0L
        val baseline = if (previousSeenAt > 0L) {
            stored.filter { it.lastSeenAt == previousSeenAt }
        } else {
            stored
        }

        // No comparable dataset means no baseline to compare against: the
        // absolute floor and the structural checks are all that apply.
        if (!hasComparableBaseline(baseline)) {
            return DatasetVerdict(
                accepted = true,
                detail = "no comparable stored dataset (${stored.size} server(s), " +
                    "previous observation ${baseline.size})",
            )
        }

        val candidateCoverage = coverage(candidate)
        val baselineCoverage = coverage(baseline)

        // BLOCK v1.0.75 FIX: with no RECORDED baseline the union is not a
        // comparable baseline — it mixes retention rows and emergency-merge rows
        // (device: the newest group was 250 rows while the live feed was ~98), so
        // comparing against it rejected every refresh forever. The first fresh
        // response is therefore judged by the absolute floor only, and the
        // updater records its size so the NEXT response is checked strictly.
        if (lastAcceptedSize <= 0) {
            return DatasetVerdict(
                accepted = true,
                detail = "no recorded baseline; accepting and recording ${candidate.size} server(s)",
            )
        }

        // The explicit size of the last ACCEPTED fresh dataset is the only
        // baseline free of retention/emergency inflation.
        val sizeFloor = lastAcceptedSize * MIN_RELATIVE_DATASET_RATIO
        if (candidate.size < sizeFloor) {
            return DatasetVerdict(
                accepted = false,
                defect = DatasetDefect.SIZE_COLLAPSE,
                detail = "candidate ${candidate.size} server(s) vs last accepted $lastAcceptedSize " +
                    "(stored union ${stored.size}; ratio floor $MIN_RELATIVE_DATASET_RATIO)",
            )
        }

        // A source that cannot publish a column at all must not be judged as
        // having lost it.
        //
        // BLOCK v1.0.75 FIX: coverage is compared as a SHARE of each dataset,
        // not as absolute counts. The stored union is larger than a live feed by
        // design, so absolute counts made every live feed look like a protocol
        // collapse (e.g. 98 SSTP servers against 250 stored). A real collapse —
        // a transport vanishing from the response — still trips this.
        if (capability.providesProtocolColumns) {
            for ((label, count) in PROTOCOLS) {
                val storedCount = count(baselineCoverage)
                if (storedCount < MIN_PROTOCOL_COVERAGE_BASELINE) continue
                val storedShare = storedCount.toDouble() / baseline.size
                val candidateShare = count(candidateCoverage).toDouble() / candidate.size
                if (candidateShare < storedShare * MIN_PROTOCOL_COVERAGE_RATIO) {
                    return DatasetVerdict(
                        accepted = false,
                        defect = DatasetDefect.PROTOCOL_COLLAPSE,
                        detail = "$label collapsed from ${"%.0f".format(storedShare * 100)}% " +
                            "to ${"%.0f".format(candidateShare * 100)}% of the dataset",
                    )
                }
            }
        }

        if (capability.providesOpenVpnProfiles) {
            val storedProfiles = baselineCoverage.openVpnProfiles
            if (storedProfiles >= MIN_PROFILE_COVERAGE_BASELINE) {
                val candidateProfiles = candidateCoverage.openVpnProfiles
                // A candidate with no profile at all is caught by the
                // updater's own dropsOpenVpnProfiles rule; this check exists
                // for the partial collapse, where a few surviving profiles
                // would otherwise look like success.
                val storedShare = storedProfiles.toDouble() / baseline.size
                val candidateShare = candidateProfiles.toDouble() / candidate.size
                if (candidateProfiles > 0 &&
                    candidateShare < storedShare * MIN_PROFILE_COVERAGE_RATIO
                ) {
                    return DatasetVerdict(
                        accepted = false,
                        defect = DatasetDefect.PROFILE_COLLAPSE,
                        detail = "OpenVPN profiles collapsed from " +
                            "${"%.0f".format(storedShare * 100)}% to " +
                            "${"%.0f".format(candidateShare * 100)}% of the dataset",
                    )
                }
            }
        }

        return DatasetVerdict(
            accepted = true,
            detail = "candidate ${candidate.size} server(s) vs stored ${stored.size}",
        )
    }

    /**
     * Whether [stored] is large enough for the relative checks to mean
     * anything. Below the absolute floor a stored list is a remnant, not a
     * baseline, so nothing is compared against it.
     */
    private fun hasComparableBaseline(stored: List<VPNGateItem>): Boolean =
        stored.size >= MIN_ACCEPTABLE_DATASET_SIZE

    /**
     * Z1-IMPL-G: classify an already-decided [verdict] against what was
     * stored when it was produced.
     *
     * A pure relabelling — it can never contradict [verdict], and a caller
     * that ignores it behaves exactly as before. It exists so the three names
     * the architecture document gives these acceptance results (§9.6, §10.8)
     * are visible in logs and reasoning instead of being an absent
     * [DatasetDefect] and a `Preserved` reason.
     *
     * The one piece of state the verdict does **not** carry is whether there
     * was a comparable stored dataset, because `evaluate` returns the same
     * "accepted, nothing to compare against" shape either way. Pass the same
     * `stored` list here that was passed to [evaluate].
     */
    fun outcome(verdict: DatasetVerdict, stored: List<VPNGateItem>): DatasetOutcome = when {
        !verdict.accepted -> DatasetOutcome.REJECT_DEGRADED
        !hasComparableBaseline(stored) -> DatasetOutcome.ACCEPT_NEW
        else -> DatasetOutcome.MERGE_WITH_EXISTING
    }
}
