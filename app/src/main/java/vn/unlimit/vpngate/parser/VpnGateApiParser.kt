package vn.unlimit.vpngate.parser

import vn.unlimit.vpngate.data.model.CollectorLog
import vn.unlimit.vpngate.data.model.VpnRecords
import vn.unlimit.vpngate.data.model.VpnServerRecord
import vn.unlimit.vpngate.data.model.VpnUtil

/**
 * VPN Gate /api/iphone/ CSV parser — mechanical port of the Python
 * oracle's parse_api (§18 unit normalization: Speed bps→Mbps,
 * Uptime ms→days, TotalTraffic bytes→GB; §7/§8 OpenVPN config
 * decoding with global-proto inheritance).
 */
object VpnGateApiParser {
    private const val CANONICAL_COLUMNS = 15

    /**
     * The feed currently starts with ``*vpn_servers`` then the real
     * ``#HostName,IP,...`` header line.
     */
    fun findApiHeader(lines: List<String>): Int {
        for (i in lines.indices) {
            if (Regex("^\\s*#?HostName\\s*,").containsMatchIn(lines[i])) return i
        }
        return -1
    }

    /**
     * Split CSV text into records of raw field values (RFC4180-style:
     * double-quoted fields with "" escapes; no unquoted field may
     * contain a comma — matches Python's csv module default dialect
     * for this feed).
     */
    fun splitCsvRecords(text: String): List<List<String>> {
        val records = mutableListOf<MutableList<String>>()
        var field = StringBuilder()
        var record = mutableListOf<String>()
        var inQuotes = false
        var i = 0

        fun endField() {
            record.add(field.toString())
            field = StringBuilder()
        }

        fun endRecord() {
            endField()
            records.add(record)
            record = mutableListOf()
        }

        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> when {
                    c == '"' -> {
                        if (i + 1 < text.length && text[i + 1] == '"') {
                            field.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    }
                    else -> field.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' -> endField()
                c == '\n' -> endRecord()
                c == '\r' -> Unit
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || record.isNotEmpty()) {
            endRecord()
        }
        return records.filter { it.size > 1 || it.firstOrNull().orEmpty().isNotEmpty() }
    }

    fun parseApi(text: String, source: String = "api"): List<VpnServerRecord> {
        CollectorLog.d("Parsing VPN Gate API CSV...")

        val lines = text.lines()
        val headerIndex = findApiHeader(lines)

        if (headerIndex < 0) {
            CollectorLog.d("Actual CSV header not found")
            return emptyList()
        }

        var csvText = lines.subList(headerIndex, lines.size).joinToString("\n")
        csvText = csvText.trimStart()
        if (csvText.startsWith("#")) {
            csvText = csvText.substring(1)
        }

        val allRecords = splitCsvRecords(csvText)
        if (allRecords.isEmpty()) {
            CollectorLog.d("CSV initialization error: no records")
            return emptyList()
        }

        val fieldnames = allRecords.first()
        val dataRecords = allRecords.drop(1)

        CollectorLog.d("API columns: ${fieldnames.joinToString(", ")}")

        val servers = mutableListOf<VpnServerRecord>()

        for (values in dataRecords) {
            if (values.isEmpty()) continue

            // ------------------------------------------------------
            // Repair mis-split rows. Free-text Operator/Message
            // fields may contain UNQUOTED commas; the Base64 payload
            // never does, so it stays right-anchored. Rebuild
            // positional values and realign them to the canonical
            // 15 columns.
            // ------------------------------------------------------
            val orderedValues = values.toList()

            val repairedValues: List<String> = when {
                orderedValues.size > CANONICAL_COLUMNS ->
                    orderedValues.subList(0, 12) + listOf(
                        orderedValues[12],
                        orderedValues.subList(13, orderedValues.size - 1).joinToString(","),
                        orderedValues[orderedValues.size - 1],
                    )
                orderedValues.size < CANONICAL_COLUMNS ->
                    orderedValues + List(CANONICAL_COLUMNS - orderedValues.size) { "" }
                else -> orderedValues
            }

            val row = HashMap<String, String>()
            for ((idx, name) in fieldnames.withIndex()) {
                row[name] = repairedValues.getOrElse(idx) { "" }
            }

            val hostname = VpnUtil.clean(row["HostName"])
            val ip = VpnUtil.normalizeIp(row["IP"])

            if (hostname.isEmpty() || !VpnUtil.validIp(ip)) continue

            val server = VpnRecords.newServer()

            VpnRecords.setField(server, "identity.hostname", hostname.lowercase(), source)
            VpnRecords.setField(server, "identity.ip", ip, source)
            VpnRecords.setField(server, "identity.country", VpnUtil.clean(row["CountryShort"]), source)
            VpnRecords.setField(server, "identity.countryLong", VpnUtil.clean(row["CountryLong"]), source)
            VpnRecords.setField(server, "performance.score", VpnUtil.toInt(row["Score"]), source)
            VpnRecords.setField(server, "performance.pingMs", VpnUtil.toFloat(row["Ping"]), source)

            // API Speed is bits/sec -> Mbps.
            val speedBps = VpnUtil.toFloat(row["Speed"])
            if (speedBps > 0) {
                VpnRecords.setField(
                    server,
                    "performance.speedMbps",
                    speedBps / 1_000_000.0,
                    source,
                )
            }

            VpnRecords.setField(
                server,
                "performance.sessions",
                VpnUtil.toInt(row["NumVpnSessions"]),
                source,
            )

            // API Uptime is milliseconds in the current feed.
            val uptimeMs = VpnUtil.toFloat(row["Uptime"])
            if (uptimeMs > 0) {
                VpnRecords.setField(
                    server,
                    "performance.uptimeDays",
                    uptimeMs / 86_400_000.0,
                    source,
                )
            }

            VpnRecords.setField(
                server,
                "performance.totalUsers",
                VpnUtil.toInt(row["TotalUsers"]),
                source,
            )

            val totalTraffic = VpnUtil.toFloat(row["TotalTraffic"])
            if (totalTraffic > 0) {
                // Current API unit is bytes.
                VpnRecords.setField(
                    server,
                    "performance.totalTrafficGB",
                    totalTraffic / (1024.0 * 1024.0 * 1024.0),
                    source,
                )
            }

            VpnRecords.setField(server, "logging.policy", VpnUtil.clean(row["LogType"]), source)
            VpnRecords.setField(server, "operator.name", VpnUtil.clean(row["Operator"]), source)
            VpnRecords.setField(server, "operator.message", VpnUtil.clean(row["Message"]), source)

            val configB64 = VpnUtil.clean(row["OpenVPN_ConfigData_Base64"])

            if (configB64.isNotEmpty()) {
                VpnRecords.markSupported(server, "openvpn", source)
                VpnRecords.setField(server, "protocols.openvpn.configAvailable", true, source)

                val configInfo = OpenVpnConfigParser.decode(configB64)

                if (configInfo.raw.isNotEmpty()) {
                    // App-only extension (beyond the Python oracle):
                    // keep the FULL decoded config so the connection
                    // layer can build a working OpenVPN profile.
                    VpnRecords.setField(
                        server,
                        "protocols.openvpn.rawConfig",
                        configInfo.raw,
                        source,
                    )
                }

                // Global "proto" directive can carry the transport
                // family for remote lines that omit it (§7).
                val globalFamilies = configInfo.protocols
                    .filter { it.startsWith("tcp") || it.startsWith("udp") }
                    .map { it.split("-")[0] }
                    .toSortedSet()
                    .toList()

                val inheritedProto = if (globalFamilies.size == 1) globalFamilies[0] else ""

                @Suppress("UNCHECKED_CAST")
                val configs = VpnRecords.protocol(server, "openvpn")["configs"]
                    as MutableList<Any?>

                for (remote in configInfo.remotes) {
                    configs.add(remote)

                    var proto = remote.proto.lowercase()
                    if (proto.isEmpty()) {
                        proto = inheritedProto
                    }

                    val port = remote.port

                    if (OpenVpnConfigParser.isTcpFamily(proto)) {
                        VpnRecords.setField(server, "protocols.openvpn.tcp.supported", true, source)
                        if (VpnUtil.validPort(port)) {
                            VpnRecords.setField(server, "protocols.openvpn.tcp.port", port, source)
                        }
                    } else if (OpenVpnConfigParser.isUdpFamily(proto)) {
                        VpnRecords.setField(server, "protocols.openvpn.udp.supported", true, source)
                        if (VpnUtil.validPort(port)) {
                            VpnRecords.setField(server, "protocols.openvpn.udp.port", port, source)
                        }
                    }
                }

                // Some configs use "proto" globally.
                for (proto in configInfo.protocols) {
                    if (OpenVpnConfigParser.isTcpFamily(proto)) {
                        VpnRecords.setField(server, "protocols.openvpn.tcp.supported", true, source)
                    } else if (OpenVpnConfigParser.isUdpFamily(proto)) {
                        VpnRecords.setField(server, "protocols.openvpn.udp.supported", true, source)
                    }
                }
            }

            VpnRecords.sourceAdd(server, source)
            servers.add(server)
        }

        CollectorLog.d("API servers: ${servers.size}")
        return servers
    }
}
