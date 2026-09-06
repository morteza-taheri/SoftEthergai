package vn.unlimit.vpngate.merger

import vn.unlimit.vpngate.data.model.VpnProtocolNames
import vn.unlimit.vpngate.data.model.VpnRecords
import vn.unlimit.vpngate.data.model.VpnServerRecord

/**
 * Provenance confidence (mission §15/§16) — port of the Python
 * oracle: mirrors collapse into ONE independent group and never
 * raise confidence beyond what html alone grants.
 */
object Provenance {
    private val SOURCE_PRIORITY = mapOf("api" to 0, "html" to 10)
    private val MIRROR_GROUP_RE = Regex("^mirror_\\d+$", RegexOption.IGNORE_CASE)
    private val CONFIDENCE_SINGLE_GROUP = mapOf(
        "api" to 0.75,
        "html" to 0.6,
        "mirror" to 0.35,
    )
    val PROTOCOL_NAMES: List<String> = VpnProtocolNames.ALL

    /** Collapse mirror_N aliases into a single independent group. */
    fun sourceGroup(source: String): String {
        val name = source.trim().lowercase()
        return if (MIRROR_GROUP_RE.matches(name)) "mirror" else name
    }

    /** Independent provenance groups contributing to this record. */
    fun independentSourceGroups(server: VpnServerRecord): List<String> {
        val groups = VpnRecords.sources(server)
            .filter { it.isNotEmpty() }
            .map { sourceGroup(it) }
            .filter { it.isNotEmpty() }
            .toSortedSet()
        return groups.toList()
    }

    /**
     * Confidence policy (§15):
     *  - no sources             -> 0.0
     *  - one independent group  -> per-group base score
     *  - two independent groups -> 0.8
     *  - three or more          -> 1.0
     */
    fun confidenceForGroups(groups: List<String>): Double {
        val unique = groups.map { it.lowercase() }.filter { it.isNotEmpty() }.toSet()

        if (unique.isEmpty()) return 0.0
        if (unique.size >= 3) return 1.0
        if (unique.size == 2) return 0.8

        return CONFIDENCE_SINGLE_GROUP[unique.first()] ?: 0.3
    }

    /** Independent groups that contributed fields of one protocol. */
    fun protocolSourceGroups(server: VpnServerRecord, protocol: String): List<String> {
        val prefix = "protocols.$protocol."
        val groups = mutableSetOf<String>()

        for ((fieldPath, pathSources) in VpnRecords.fieldSources(server)) {
            if (fieldPath.startsWith(prefix)) {
                groups.addAll(pathSources.filter { it.isNotEmpty() }.map { sourceGroup(it) })
            }
        }
        groups.remove("")
        return groups.toSortedSet().toList()
    }

    /** Per-protocol confidence derived from INDEPENDENT provenance. */
    fun computeConfidence(server: VpnServerRecord): Map<String, Double> =
        PROTOCOL_NAMES.associateWith { name ->
            confidenceForGroups(protocolSourceGroups(server, name))
        }
}
