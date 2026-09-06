package vn.unlimit.vpngate.collector

import vn.unlimit.vpngate.data.model.OpenVpnRemote
import vn.unlimit.vpngate.data.model.VpnProtocolNames
import vn.unlimit.vpngate.data.model.VpnRecords
import vn.unlimit.vpngate.data.model.VpnServerRecord
import vn.unlimit.vpngate.merger.Provenance
import java.util.Locale

/**
 * Per-server provenance dump (§33) — mechanical port of the Python
 * oracle's format_server_debug so the in-app debug panel and the
 * ``--debug-ip`` CLI show identical layouts.
 */
object CollectorDebugDump {
    fun format(server: VpnServerRecord): String {
        val sb = StringBuilder()
        val identity = VpnRecords.identity(server)
        val perf = VpnRecords.performance(server)
        val protocols = VpnRecords.protocols(server)

        sb.appendLine("=".repeat(72))
        sb.appendLine(
            "SERVER DUMP: ${VpnRecords.str(identity["hostname"])} " +
                "(${VpnRecords.str(identity["ip"])})"
        )
        sb.appendLine("=".repeat(72))

        sb.appendLine("[identity]")
        sb.appendLine(
            "  hostname=${identity["hostname"]} ip=${identity["ip"]} " +
                "isp=${identity["ispHostname"]} country=${identity["country"]} " +
                "countryLong=${identity["countryLong"]}"
        )

        val groups = Provenance.independentSourceGroups(server)
        sb.appendLine("[sources]")
        sb.appendLine(
            "  tags: ${VpnRecords.sources(server).joinToString(", ").ifEmpty { "(none)" }}"
        )
        sb.appendLine(
            "  independent groups: ${groups.joinToString(", ").ifEmpty { "(none)" }}"
        )

        val quality = VpnRecords.map(server["quality"])
        sb.appendLine("[quality]")
        sb.appendLine(
            "  overall=${quality["overall"]} softether=${quality["softether"]} " +
                "openvpn=${quality["openvpn"]} sstp=${quality["sstp"]} l2tp=${quality["l2tp"]}"
        )

        val confidence = Provenance.computeConfidence(server)
        sb.appendLine("[confidence]")
        sb.appendLine(
            "  " + confidence.entries.joinToString(" ") { (name, value) ->
                String.format(Locale.US, "%s=%.2f", name, value)
            }
        )

        for (name in VpnProtocolNames.ALL) {
            val proto = VpnRecords.map(protocols[name])
            when (name) {
                VpnProtocolNames.SOFTETHER, VpnProtocolNames.OPENVPN -> {
                    val tcp = VpnRecords.transport(proto, "tcp")
                    val udp = VpnRecords.transport(proto, "udp")
                    val extra = if (name == VpnProtocolNames.OPENVPN) {
                        @Suppress("UNCHECKED_CAST")
                        val configs = proto["configs"] as? List<OpenVpnRemote> ?: emptyList()
                        val rendered = configs.joinToString(", ") {
                            "${it.host}:${it.port}(${it.proto.ifEmpty { "-" }})"
                        }
                        " configAvailable=${proto["configAvailable"]} configs=[$rendered]"
                    } else {
                        ""
                    }
                    sb.appendLine(
                        "[$name] supported=${proto["supported"]} " +
                            "tcp(supported=${tcp["supported"]} port=${tcp["port"]}) " +
                            "udp(supported=${udp["supported"]} port=${udp["port"]})" +
                            extra
                    )
                }
                VpnProtocolNames.L2TP_IPSEC -> sb.appendLine(
                    "[l2tpIpsec] supported=${proto["supported"]} port=${proto["port"]}"
                )
                else -> sb.appendLine(
                    "[$name] supported=${proto["supported"]} " +
                        "hostname=${proto["hostname"]} port=${proto["port"]}"
                )
            }
        }

        sb.appendLine("[performance]")
        sb.appendLine(
            "  score=${perf["score"]} pingMs=${perf["pingMs"]} " +
                "speedMbps=${perf["speedMbps"]} sessions=${perf["sessions"]} " +
                "uptimeDays=${perf["uptimeDays"]} totalUsers=${perf["totalUsers"]} " +
                "totalTrafficGB=${perf["totalTrafficGB"]}"
        )
        sb.appendLine("[logging] policy=${VpnRecords.map(server["logging"])["policy"]}")
        val operator = VpnRecords.map(server["operator"])
        sb.appendLine("[operator] name=${operator["name"]} message=${operator["message"]}")

        sb.appendLine("[field sources]")
        val fieldSources = VpnRecords.fieldSources(server)
        if (fieldSources.isEmpty()) {
            sb.appendLine("  (none)")
        } else {
            for (path in fieldSources.keys.sorted()) {
                sb.appendLine(
                    "  ${path.padEnd(44)} <- ${fieldSources[path]!!.joinToString(", ")}"
                )
            }
        }

        sb.appendLine("[conflicts]")
        val conflicts = VpnRecords.conflicts(server)
        if (conflicts.isEmpty()) {
            sb.appendLine("  (none)")
        } else {
            for (conflict in conflicts) {
                val rendered = conflict.values.entries.joinToString(" vs ") {
                    (owner, value) -> "$owner=$value"
                }
                sb.appendLine("  ${conflict.field}: $rendered")
            }
        }

        return sb.toString()
    }
}
