package vn.unlimit.vpngate.data.remote

import vn.unlimit.vpngate.data.model.VpnProtocolNames
import vn.unlimit.vpngate.data.model.VpnUtil

/**
 * Z1-IMPL-D (Roadmap §12.5.2 item D): the domain view of one manually
 * entered server.
 *
 * A manual server is user-owned data, not an observation of a feed: the
 * user typed the address, the protocol and the port, and nothing may
 * silently overwrite them (spec §11.11). It therefore lives in its own
 * table rather than being folded into the collector's dataset at parse
 * time, and it is re-emitted on every accepted write so a refresh cannot
 * drop it.
 *
 * The field set is the one the feature needs — an address, one protocol
 * with its port, a label for the user's own bookkeeping, notes, and an
 * enable switch. Spec §11.11 deliberately defines no schema ("Do not
 * design or implement a new database schema here"); `SERVER_SOURCE_
 * ARCHITECTURE.md` §4.1 fixes the minimum as "a single server record
 * (host/IP + at least one protocol+port)", which is exactly what this is.
 * Nothing here is invented beyond that.
 */
data class ManualServer(
    val id: Long = 0,
    val host: String,
    val port: Int,
    val protocol: String,
    val label: String? = null,
    val notes: String? = null,
    val enabled: Boolean = true,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    companion object {

        /**
         * The record layer's protocol names are the single vocabulary: a
         * manual entry uses the same four the collector produces, so a
         * manual row and a collected row describe a server the same way.
         *
         * They are mixed-case (`softether`, `openvpn`, `l2tpIpsec`,
         * `sstp`), so matching is case-insensitive and the canonical
         * spelling — not the user's — is what gets stored.
         */
        val PROTOCOLS: List<String> = VpnProtocolNames.ALL

        /**
         * The canonical spelling of [raw], or [raw] lower-cased when it names
         * no known protocol. An unknown protocol is returned rather than
         * guessed at, so the caller's validation — not this function —
         * decides whether it is usable.
         */
        fun normalizeProtocol(raw: String): String {
            val trimmed = raw.trim()
            return PROTOCOLS.firstOrNull { it.equals(trimmed, ignoreCase = true) }
                ?: trimmed.lowercase()
        }

        fun isValidProtocol(raw: String): Boolean =
            PROTOCOLS.any { it.equals(raw.trim(), ignoreCase = true) }

        /**
         * A manual entry must carry a dialable address. The record layer
         * keys a server on a valid IPv4 (`VpnServerMerger.normalizeServer`
         * rejects anything else, and `VpnConnectionMapper.toConnection`
         * returns null without one), so a bare DNS name cannot become a
         * row in this phase: it would be accepted by the form and then
         * silently dropped by the pipeline. Refusing it at the boundary
         * keeps the promise the Settings UI makes.
         */
        fun isValidHost(host: String): Boolean = VpnUtil.validIp(host.trim())

        fun isValidPort(port: Int): Boolean = port in 1..65535

        fun fromEntity(entity: ManualServerEntity): ManualServer = ManualServer(
            id = entity.id,
            host = entity.host,
            port = entity.port,
            protocol = entity.protocol,
            label = entity.label,
            notes = entity.notes,
            enabled = entity.enabled,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
        )
    }

    fun toEntity(): ManualServerEntity = ManualServerEntity(
        id = id,
        host = host,
        port = port,
        protocol = protocol,
        label = label,
        notes = notes,
        enabled = enabled,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
