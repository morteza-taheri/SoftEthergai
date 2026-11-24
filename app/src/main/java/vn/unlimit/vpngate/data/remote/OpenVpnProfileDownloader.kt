package vn.unlimit.vpngate.data.remote

import vn.unlimit.vpngate.data.model.VpnUtil
import vn.unlimit.vpngate.models.VPNGateConnection

/**
 * Fetches one VPN Gate OpenVPN profile on demand.
 *
 * The bulk iPhone CSV may omit its Base64 profile to keep the response small.
 * The HTML row still supplies the short-lived sid/hid/host identity and the
 * protocol-specific port, which is enough to request that one .ovpn file.
 */
class OpenVpnProfileDownloader(
    private val fetcher: HttpFetcher = OkHttpFetcher(),
) {
    companion object {
        private const val MAX_PROFILE_CHARS = 1_048_576
        private val HOST_RE = Regex("^[a-z0-9.-]{1,253}$")
        private val REMOTE_RE = Regex("(?m)^\\s*remote\\s+([^\\s#]+)\\s+(\\d+)(?:\\s+.*)?$")
    }

    suspend fun download(
        connection: VPNGateConnection,
        useUdp: Boolean,
    ): String? {
        val sid = connection.openVpnDownloadSid?.takeIf { it.matches(Regex("\\d{1,20}")) }
            ?: return null
        val hid = connection.openVpnDownloadHid?.takeIf { it.matches(Regex("\\d{1,20}")) }
            ?: return null
        val host = connection.openVpnDownloadHost?.lowercase()
            ?.takeIf { HOST_RE.matches(it) && (it.endsWith(".opengw.net") || VpnUtil.validIp(it)) }
            ?: return null
        val port = if (useUdp) connection.udpPort else connection.tcpPort
        if (!VpnUtil.validPort(port)) return null

        val transport = if (useUdp) "udp" else "tcp"
        val udpFlag = if (useUdp) 1 else 0
        val fileName = "vpngate_${host}_${transport}_${port}.ovpn"
        val url = "https://www.vpngate.net/common/openvpn_download.aspx" +
            "?sid=$sid&udp=$udpFlag&host=$host&port=$port&hid=$hid&/$fileName"
        val identity = SourceUrl.parse(
            "https://www.vpngate.net/common/openvpn_download.aspx",
            SourceKind.CANONICAL_HTML,
        ) ?: return null
        val response = runCatching {
            fetcher.get(url, identity.redirectPolicy())
        }.getOrNull() ?: return null

        if (response.status !in 200..299 || response.redirectRejected) return null
        if (!response.finalUrl.startsWith("https://www.vpngate.net/", ignoreCase = true)) return null
        val profile = response.body?.takeIf { it.length in 50..MAX_PROFILE_CHARS } ?: return null
        if (profile.trimStart().startsWith("<")) return null

        val remotes = REMOTE_RE.findAll(profile)
            .map { it.groupValues[1].lowercase() to it.groupValues[2].toIntOrNull() }
            .toList()
        val expectedHost = host.lowercase()
        val expectedIp = connection.ip?.trim()
        if (remotes.none { (remoteHost, remotePort) ->
                (remoteHost == expectedHost || remoteHost == expectedIp) && remotePort == port
            }
        ) return null

        val proto = Regex("(?m)^\\s*proto\\s+(\\S+)", RegexOption.IGNORE_CASE)
            .find(profile)?.groupValues?.get(1)?.lowercase()
        if (proto != null && (if (useUdp) !proto.startsWith("udp") else !proto.startsWith("tcp"))) {
            return null
        }

        // Normalize the profile's remote host to the server IP. The app's
        // existing UDP/domain transforms are intentionally IP-based; keeping
        // a hostname here would prevent their protocol-specific port rewrite.
        val normalizedIp = expectedIp?.takeIf { VpnUtil.validIp(it) }
        return if (normalizedIp != null && expectedHost != normalizedIp) {
            Regex("(?m)^(\\s*remote\\s+)${Regex.escape(expectedHost)}(\\s+\\d+)")
                .replace(profile) { match ->
                    "${match.groupValues[1]}${normalizedIp}${match.groupValues[2]}"
                }
        } else profile
    }
}
