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
    ): DatasetVerdict {
        if (candidate.size < MIN_ACCEPTABLE_DATASET_SIZE) {
            return DatasetVerdict(
                accepted = false,
                defect = DatasetDefect.TOO_SMALL,
                detail = "candidate has ${candidate.size} server(s), floor is $MIN_ACCEPTABLE_DATASET_SIZE",
            )
        }

        // No stored dataset means no baseline to compare against: the
        // absolute floor and the structural checks are all that apply.
        if (stored.size < MIN_ACCEPTABLE_DATASET_SIZE) {
            return DatasetVerdict(
                accepted = true,
                detail = "no comparable stored dataset (${stored.size} server(s))",
            )
        }

        val candidateCoverage = coverage(candidate)
        val storedCoverage = coverage(stored)

        val sizeFloor = stored.size * MIN_RELATIVE_DATASET_RATIO
        if (candidate.size < sizeFloor) {
            return DatasetVerdict(
                accepted = false,
                defect = DatasetDefect.SIZE_COLLAPSE,
                detail = "candidate ${candidate.size} server(s) vs stored ${stored.size} " +
                    "(ratio floor $MIN_RELATIVE_DATASET_RATIO)",
            )
        }

        // A source that cannot publish a column at all must not be judged as
        // having lost it.
        if (capability.providesProtocolColumns) {
            for ((label, count) in PROTOCOLS) {
                val storedCount = count(storedCoverage)
                if (storedCount < MIN_PROTOCOL_COVERAGE_BASELINE) continue
                val candidateCount = count(candidateCoverage)
                if (candidateCount < storedCount * MIN_PROTOCOL_COVERAGE_RATIO) {
                    return DatasetVerdict(
                        accepted = false,
                        defect = DatasetDefect.PROTOCOL_COLLAPSE,
                        detail = "$label collapsed from $storedCount to $candidateCount server(s)",
                    )
                }
            }
        }

        if (capability.providesOpenVpnProfiles) {
            val storedProfiles = storedCoverage.openVpnProfiles
            if (storedProfiles >= MIN_PROFILE_COVERAGE_BASELINE) {
                val candidateProfiles = candidateCoverage.openVpnProfiles
                // A candidate with no profile at all is caught by the
                // updater's own dropsOpenVpnProfiles rule; this check exists
                // for the partial collapse, where a few surviving profiles
                // would otherwise look like success.
                if (candidateProfiles > 0 &&
                    candidateProfiles < storedProfiles * MIN_PROFILE_COVERAGE_RATIO
                ) {
                    return DatasetVerdict(
                        accepted = false,
                        defect = DatasetDefect.PROFILE_COLLAPSE,
                        detail = "OpenVPN profiles collapsed from $storedProfiles to $candidateProfiles",
                    )
                }
            }
        }

        return DatasetVerdict(
            accepted = true,
            detail = "candidate ${candidate.size} server(s) vs stored ${stored.size}",
        )
    }
}
