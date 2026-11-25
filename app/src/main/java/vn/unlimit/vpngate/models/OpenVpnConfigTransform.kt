package vn.unlimit.vpngate.models

/**
 * Null-safe transforms for a stored VPN Gate OpenVPN profile.
 *
 * ## Why the transform reads the profile instead of assuming its transport
 *
 * The original code assumed "VPN Gate publishes TCP profiles only", so
 * [forUdp] did a literal `replace("proto tcp", "proto udp")` and swapped the
 * remote port. That assumption stopped being true once a profile could be
 * downloaded on demand from `openvpn_download.aspx` with `udp=1`, and once a
 * server publishes UDP but no TCP (`udp=1195&tcp=0`). With a stored UDP
 * profile:
 *
 *  - the "TCP" option handed the core the UDP profile unchanged (the literal
 *    replace found nothing), and
 *  - the "UDP" option was a no-op only by accident — the `remote <ip> <tcpPort>`
 *    replacement never matched because the port was the UDP one.
 *
 * The visible result was a TCP/UDP swap in connect and export. [forTransport]
 * therefore parses the profile's real `proto` and `remote` lines and rewrites
 * them, so the output is correct regardless of what was stored.
 *
 * ## What is deliberately not touched
 *
 * Only the `proto` transport token and the port of the matching `remote` line
 * are rewritten. Everything else — ciphers, `dev tun`, routes, `remote-random`,
 * comments — is preserved byte for byte, because VPN Gate profiles carry
 * provider-specific settings this app has no business editing.
 *
 * These helpers are pure so the behaviour is unit-testable without Android:
 * a missing profile, a missing IP or a missing hostname yields the profile
 * unchanged, or null when there is no profile at all. No input can make them
 * throw.
 */
object OpenVpnConfigTransform {

    /** `proto tcp`, `proto tcp-client`, `proto udp`, `proto udp6`, ... */
    private val PROTO_LINE = Regex(
        pattern = "(?m)^([ \\t]*proto[ \\t]+)(?:tcp|udp)(?:-client|-server)?(?:4|6)?([ \\t\\r]*)$",
        option = RegexOption.IGNORE_CASE,
    )

    /** `remote <host> <port>` — a bare `remote-random` never matches. */
    private val REMOTE_LINE = Regex(
        pattern = "(?m)^([ \\t]*remote[ \\t]+)(\\S+)([ \\t]+)(\\d+)([ \\t\\r]*)$",
        option = RegexOption.IGNORE_CASE,
    )

    /**
     * Derive the profile for a chosen transport.
     *
     * @param wantUdp true for UDP, false for TCP.
     * @param tcpPort the server's published OpenVPN TCP port, 0 when unknown.
     * @param udpPort the server's published OpenVPN UDP port, 0 when unknown.
     * @param ip the server IP the `remote` line is expected to carry; when null
     *   or not found, the first `remote` line is rewritten instead.
     *
     * Returns null only when there is no profile. When the requested transport
     * has no published port the stored profile is returned unchanged: inventing
     * a port would produce a profile that cannot connect and cannot be
     * explained to the user.
     */
    fun forTransport(
        config: String?,
        wantUdp: Boolean,
        tcpPort: Int,
        udpPort: Int,
        ip: String?,
    ): String? {
        val base = config ?: return null
        val targetPort = if (wantUdp) udpPort else tcpPort
        if (targetPort <= 0) return base

        val proto = if (wantUdp) "udp" else "tcp"
        var out = PROTO_LINE.replace(base) { match ->
            match.groupValues[1] + proto + match.groupValues[2]
        }
        out = rewriteRemotePort(out, targetPort, ip)
        return out
    }

    /**
     * Backwards-compatible name for the UDP derivation.
     *
     * Kept because it is the documented entry point for the "UDP" option; it now
     * delegates to [forTransport], so a stored UDP profile is no longer left
     * untouched when the user asks for TCP and vice versa.
     */
    fun forUdp(config: String?, tcpPort: Int, udpPort: Int, ip: String?): String? {
        val base = config ?: return null
        // Preserved contract: with no TCP port published, or no IP to match the
        // `remote` line against, the old derivation had nothing to rewrite and
        // returned the profile unchanged.
        if (tcpPort <= 0) return base
        if (ip.isNullOrBlank()) return base
        return forTransport(base, wantUdp = true, tcpPort = tcpPort, udpPort = udpPort, ip = ip)
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

    /** True when the profile's own `proto` line says UDP. */
    fun isUdp(config: String?): Boolean {
        val base = config ?: return false
        val match = PROTO_LINE.find(base) ?: return false
        return match.value.contains("udp", ignoreCase = true)
    }

    /**
     * Set the port on the `remote` line for [ip], or on the first `remote` line
     * when the IP is unknown or absent (a domain-form profile).
     */
    private fun rewriteRemotePort(config: String, port: Int, ip: String?): String {
        val host = ip?.takeIf { it.isNotBlank() }
        val matches = REMOTE_LINE.findAll(config).toList()
        if (matches.isEmpty()) return config
        val target = if (host != null) {
            matches.firstOrNull { it.groupValues[2] == host } ?: matches.first()
        } else {
            matches.first()
        }
        val prefix = target.groupValues[1]
        val targetHost = target.groupValues[2]
        val trailing = target.groupValues[5]
        return config.replaceRange(
            target.range,
            "$prefix$targetHost $port$trailing",
        )
    }
}
