package vn.unlimit.vpngate.parser

import vn.unlimit.vpngate.data.model.OpenVpnRemote
import vn.unlimit.vpngate.data.model.VpnUtil

/**
 * Decodes OpenVPN_ConfigData_Base64 and inspects remote/proto
 * directives — port of the Python oracle's decode_openvpn_config.
 *
 * No TCP/UDP ports are assumed; remote/proto directives are read
 * from the actual configuration when available (§7).
 */
object OpenVpnConfigParser {
    data class Decoded(
        val decoded: Boolean,
        val protocols: List<String>,
        val remotes: List<OpenVpnRemote>,
        val rawPreview: String,
        val raw: String,
    )

    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /**
     * Lenient Base64 decode matching Python's
     * ``base64.b64decode(value, validate=False)``: characters outside
     * the alphabet (whitespace etc.) are discarded. Pure Kotlin so it
     * works on API 24 devices (java.util.Base64 needs API 26) and in
     * plain JVM unit tests.
     */
    fun decodeBase64(input: String): ByteArray? = try {
        val chars = input.filter { it in ALPHABET }
        val out = ArrayList<Byte>(chars.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in chars) {
            buffer = (buffer shl 6) or ALPHABET.indexOf(c)
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer shr bits) and 0xff).toByte())
            }
        }
        out.toByteArray()
    } catch (e: Exception) {
        null
    }

    fun decode(value: String): Decoded {
        if (value.isEmpty()) {
            return Decoded(false, emptyList(), emptyList(), "", "")
        }

        val rawBytes = decodeBase64(value)
            ?: return Decoded(false, emptyList(), emptyList(), "", "")

        val raw = try {
            String(rawBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            return Decoded(false, emptyList(), emptyList(), "", "")
        }

        val protocols = mutableListOf<String>()
        val protoRegex = Regex("^\\s*proto\\s+(\\S+)", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
        for (m in protoRegex.findAll(raw)) {
            val proto = m.groupValues[1].lowercase()
            if (proto !in protocols) protocols.add(proto)
        }

        val remotes = mutableListOf<OpenVpnRemote>()
        for (rawLine in raw.lines()) {
            val line = rawLine.trim()
            if (!line.lowercase().startsWith("remote ")) continue

            val parts = line.split(Regex("\\s+"))
            if (parts.size < 2) continue

            val host = parts[1]
            var port: Int? = null
            var proto = ""

            if (parts.size >= 3 && VpnUtil.validPort(parts[2])) {
                port = VpnUtil.toInt(parts[2])
            }
            if (parts.size >= 4) {
                proto = parts[3].lowercase()
            }

            remotes.add(OpenVpnRemote(host, port, proto))
        }

        return Decoded(
            decoded = true,
            protocols = protocols,
            remotes = remotes,
            rawPreview = raw.take(500),
            raw = raw,
        )
    }

    /** True when the remote's proto names the TCP family. */
    fun isTcpFamily(proto: String): Boolean = proto in setOf("tcp", "tcp-client")

    /** True when the remote's proto names the UDP family. */
    fun isUdpFamily(proto: String): Boolean = proto in setOf("udp", "udp4", "udp6")
}
