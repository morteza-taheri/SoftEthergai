package vn.unlimit.vpngate.automode

import vn.unlimit.vpngate.data.model.VpnProtocolDefaults

/**
 * Protocols Auto Mode can drive. The list is open-ended: adding a new
 * protocol means adding an enum constant plus a `supports` rule and a
 * connect branch in the connection adapter â€” no controller rewrite.
 */
enum class AutoModeProtocol(val id: String) {
    SOFTETHER_TCP("SOFTETHER_TCP"),
    SOFTETHER_UDP("SOFTETHER_UDP"),
    OPENVPN_TCP("OPENVPN_TCP"),
    OPENVPN_UDP("OPENVPN_UDP"),
    L2TP_IPSEC("L2TP_IPSEC"),
    MS_SSTP("MS_SSTP");

    companion object {
        /** §24 default when the user never changed the setting. */
        const val DEFAULT_ID = "SOFTETHER_UDP"

        /**
         * AUTOCONNECT-AUDIT D-7: the protocol [id] names, or **null** when it names none.
         *
         * This used to fall back to [SOFTETHER_UDP], so any unrecognised string — a value
         * written by an older version, a corrupted preference, a typo — became a real,
         * dialable protocol with no diagnostic at all. That is the same class of silent
         * fallback v1.0.26 fix (D) removed from `manualProtocolLabel`: after it, a caller
         * cannot tell "the user chose SoftEther UDP" from "we did not recognise what the
         * user chose", and the two need opposite handling.
         *
         * A caller that legitimately wants a default for an **unset** setting must say so
         * explicitly with [DEFAULT_ID]. The default belongs to the setting, never to the
         * parser.
         */
        fun fromId(id: String?): AutoModeProtocol? =
            entries.firstOrNull { it.id == id }
    }

    /**
     * Human-facing protocol name, matching the labels the connection-status
     * screens already show ("SoftEther", "OpenVPN", "SSTP"). The global tunnel
     * state records this rather than the enum id so Status, the notification
     * and Auto Mode all display the same word for the same transport.
     */
    val displayName: String
        get() = when (this) {
            SOFTETHER_TCP, SOFTETHER_UDP -> "SoftEther"
            OPENVPN_TCP, OPENVPN_UDP -> "OpenVPN"
            MS_SSTP -> "SSTP"
            L2TP_IPSEC -> "L2TP"
        }
}

/** Stage 2F-F.16 (D7): which transport a reachability probe for a protocol must use. */
enum class ProbeTransport { TCP, UDP }

/**
 * Stage 2F-F.16 (D7): the transport this protocol runs on.
 *
 * The probe mechanism follows the protocol, so a UDP endpoint is probed over UDP
 * and a TCP endpoint over TCP. Probing one over the other produces evidence about
 * the wrong transport — a TCP success says nothing about a UDP endpoint, and a TCP
 * failure does not mean the UDP one is closed — which is the same
 * cross-transport mistake Phase 2F-F.2 removed from endpoint resolution.
 *
 * L2TP/IPsec reports TCP because that is its transport; it is never probed by Auto
 * Connect at all, since the Phase 2 capability decision excludes it before a
 * target is ever created. This property deliberately does not decide
 * automatability — that stays with the controller (Y4).
 */
val AutoModeProtocol.probeTransport: ProbeTransport
    get() = when (this) {
        AutoModeProtocol.SOFTETHER_UDP, AutoModeProtocol.OPENVPN_UDP -> ProbeTransport.UDP
        AutoModeProtocol.SOFTETHER_TCP,
        AutoModeProtocol.OPENVPN_TCP,
        AutoModeProtocol.MS_SSTP,
        AutoModeProtocol.L2TP_IPSEC,
        -> ProbeTransport.TCP
    }

/**
 * Stage 2F-F.13 (Y4): the endpoint Auto Connect would dial for [candidate] over
 * this protocol, or `<= 0` when the protocol publishes no usable port.
 *
 * This resolution is owned by the automode layer because it is part of the
 * capability decision: "is this protocol usable for this server" is answered
 * here and in [AutoModeController], and the probe is then handed the endpoint
 * that decision produced. The probe must never re-derive an endpoint, because
 * a probe that picks its own port is a probe making a capability claim it has
 * no basis for (Stage 2F-F.2, Q-4).
 *
 * Phase 2F-F.2 already fixed the per-protocol mapping; this is the same mapping,
 * relocated rather than rewritten:
 *
 * - SOFTETHER_TCP / OPENVPN_TCP / OPENVPN_UDP use their own published port.
 * - SOFTETHER_UDP uses the server's own published UDP endpoint. It is NOT the
 *   TCP listener: the TCP port belongs to SOFTETHER_TCP and must not vouch for
 *   UDP. (Auto Connect *connect* eligibility for SoftEther UDP is a separate
 *   question answered by `supports()`: the native stack accelerates over the
 *   TCP control channel and learns the UDP endpoint at runtime, so
 *   `seUdpSupported` alone is a legitimate connectable state. A `<= 0` result
 *   here therefore means "nothing published to probe", not "not connectable".)
 * - MS_SSTP uses `sstpConnectPort`, which already encodes the project's own
 *   EXPLICIT / DEFAULT / UNKNOWN provenance rule. UNKNOWN yields 0, so an
 *   unknown SSTP port is never turned into 443.
 * - L2TP/IPsec publishes no per-server port; the record layer already records
 *   the IANA listener as the protocol default, so that centralized constant is
 *   the port. That is the project's existing L2TP semantic, not a value
 *   invented here. Auto Connect never selects L2TP in any case (see
 *   `canAutoConnect` / `usableProtocols`), so this branch exists only to keep
 *   the resolution total.
 *
 * **No port is ever fabricated and no protocol's port stands in for another's.**
 */
fun AutoModeProtocol.endpointPort(candidate: AutoModeCandidate): Int = when (this) {
    AutoModeProtocol.SOFTETHER_TCP -> candidate.seTcpPort
    AutoModeProtocol.SOFTETHER_UDP -> candidate.seUdpPort
    AutoModeProtocol.OPENVPN_TCP -> candidate.openVpnTcpPort
    AutoModeProtocol.OPENVPN_UDP -> candidate.openVpnUdpPort
    AutoModeProtocol.MS_SSTP -> candidate.sstpConnectPort
    AutoModeProtocol.L2TP_IPSEC -> VpnProtocolDefaults.L2TP_PORT
}
