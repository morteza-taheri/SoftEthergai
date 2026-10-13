package vn.unlimit.vpngate.models

/**
 * Null-safe transforms for a stored VPN Gate OpenVPN profile.
 *
 * VPN Gate publishes one profile per server and it is always `proto tcp`;
 * the UDP variant and the "use domain to connect" variant are derived from
 * it. Both derivations used to dereference the profile with `!!`, which threw
 * a NullPointerException whenever the server carried a port but no profile
 * (exactly the state a partially-collected server list produces), and the
 * exception escaped into the Auto Mode connect path.
 *
 * These helpers are pure so the behaviour is unit-testable without Android:
 * a missing profile, a missing IP or a missing hostname yields the profile
 * unchanged, or null when there is no profile at all. No input can make them
 * throw.
 */
object OpenVpnConfigTransform {

    /**
     * Derive the UDP profile from the stored TCP profile.
     *
     * Returns null when there is no profile. When the server publishes no TCP
     * port, or no IP to match the `remote` line against, the stored profile is
     * returned unchanged rather than throwing.
     */
    fun forUdp(config: String?, tcpPort: Int, udpPort: Int, ip: String?): String? {
        val base = config ?: return null
        if (tcpPort <= 0) return base
        val host = ip?.takeIf { it.isNotBlank() } ?: return base
        return base
            .replace("proto tcp", "proto udp")
            .replace("remote $host $tcpPort", "remote $host $udpPort")
    }

    /**
     * Rewrite the profile's IP into the `*.opengw.net` hostname when the user
     * asked to connect by domain.
     *
     * Returns null when there is no profile, and the profile unchanged when
     * the domain form cannot be built (no IP to replace, no hostname).
     */
    fun forDomain(
        config: String?,
        ip: String?,
        hostName: String?,
        useDomain: Boolean,
    ): String? {
        val base = config ?: return null
        if (!useDomain) return base
        val host = ip?.takeIf { it.isNotBlank() } ?: return base
        val name = hostName?.takeIf { it.isNotBlank() } ?: return base
        return base.replace(host, "$name.opengw.net")
    }
}
