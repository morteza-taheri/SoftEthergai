package vn.unlimit.vpngate.parser

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import vn.unlimit.vpngate.data.model.CollectorLog
import vn.unlimit.vpngate.data.model.VpnRecords
import vn.unlimit.vpngate.data.model.VpnServerRecord
import vn.unlimit.vpngate.data.model.VpnUtil
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * DOM/cell-based VPN Gate HTML parser — mechanical port of the
 * Python oracle's parse_html (§31 contract): hosts-table selection
 * among duplicate ids, header-derived column map, flag-image ISO
 * country, per-cell protocol parsing, do_openvpn query params,
 * "SSTP Hostname : host[:port]", uptime days/hours/mins.
 *
 * No port is ever invented: absent facts stay null (§6/§9/§38).
 */
object VpnGateHtmlParser {
    const val HOSTS_TABLE_ID = "vg_hosts_table_id"

    private val COLUMN_HEADER_KEYWORDS = linkedMapOf(
        "country" to listOf("country", "physical location"),
        "host" to listOf("ddns hostname", "ip address", "hostname"),
        "sessions" to listOf("vpn sessions", "uptime"),
        "perf" to listOf("line quality", "throughput", "ping"),
        "softether" to listOf("ssl-vpn"),
        "l2tp" to listOf("l2tp/ipsec", "l2tp"),
        "openvpn" to listOf("openvpn"),
        "sstp" to listOf("ms-sstp", "sstp"),
        "operator" to listOf("volunteer operator", "operator's name"),
        "score" to listOf("score", "quality"),
    )

    /** Python's ``round(value, 4)`` uses banker's rounding. */
    private fun pyRound4(value: Double): Double =
        BigDecimal(value).setScale(4, RoundingMode.HALF_EVEN).toDouble()

    /**
     * Real VPN Gate pages carry an UNMATCHED ``</td>`` before each
     * header block's ``</tr>``; some parsers then drop every data
     * row, so the pattern is collapsed before parsing (the Python
     * oracle's _make_soup). Jsoup tolerates it; sanitizing keeps the
     * two oracles on identical input.
     */
    fun sanitize(html: String): String =
        html.replace(Regex("</td>(?:\\s*</td>)+(\\s*</tr>)"), "</td>$1")

    fun makeSoup(html: String): Document = Jsoup.parse(sanitize(html))

    /** All candidate hosts tables (the live page embeds several
     * elements sharing the id). */
    fun findHostsTables(doc: Document): List<Element> =
        doc.select("#$HOSTS_TABLE_ID").toList()

    /**
     * Derive column-name -> index mapping from the table header row.
     * Derived from the actual DOM labels, never hardcoded positions.
     */
    fun buildColumnMap(table: Element): Map<String, Int> {
        var headerCells: List<Element> = emptyList()

        for (tr in table.getElementsByTag("tr")) {
            val cells = tr.select("td, th")
            if (cells.any { it.classNames().contains("vg_table_header") }) {
                headerCells = cells
                break
            }
        }

        if (headerCells.isEmpty()) {
            val rows = table.getElementsByTag("tr")
            if (rows.isNotEmpty()) {
                headerCells = rows[0].select("td, th")
            }
        }

        val colmap = LinkedHashMap<String, Int>()
        for ((idx, cell) in headerCells.withIndex()) {
            val label = VpnUtil.clean(cell.text()).lowercase()
            for ((key, keywords) in COLUMN_HEADER_KEYWORDS) {
                if (key in colmap) continue
                if (keywords.any { it in label }) {
                    colmap[key] = idx
                    break
                }
            }
        }
        return colmap
    }

    /** Pick the genuine hosts list out of duplicate-id candidates:
     * prefer the one whose header exposes every expected column. */
    fun selectHostsTable(candidates: List<Element>): Element? {
        val required = setOf("country", "host", "softether", "openvpn")
        for (table in candidates) {
            val colmap = try {
                buildColumnMap(table)
            } catch (e: Exception) {
                continue
            }
            if (colmap.keys.containsAll(required)) return table
        }
        return candidates.firstOrNull()
    }

    /** Parse uptime snippets such as "93 days", "11 hours",
     * "45 mins" into fractional days. Unknown formats yield 0.0. */
    fun parseUptimeDays(text: String): Double {
        val m = Regex(
            "(\\d+(?:\\.\\d+)?)\\s*(mins?|minutes?|hours?|hrs?|days?)",
            RegexOption.IGNORE_CASE,
        ).find(VpnUtil.clean(text)) ?: return 0.0

        val value = m.groupValues[1].toDoubleOrNull() ?: return 0.0
        val unit = m.groupValues[2].lowercase()

        if (unit.startsWith("min")) return pyRound4(value / 1440.0)
        if (unit.startsWith("h")) return pyRound4(value / 24.0)
        return pyRound4(value)
    }

    /** Returns Triple(supported, host, port). */
    fun parseSstp(value: String): Triple<Boolean, String, Int?> {
        val m = Regex(
            "SSTP\\s+Hostname\\s*:\\s*([A-Za-z0-9._-]+)(?::(\\d+))?",
            RegexOption.IGNORE_CASE,
        ).find(value) ?: return Triple(false, "", null)

        val host = VpnUtil.normalizeHost(m.groupValues[1])
        val portText = m.groupValues[2]
        val port = if (portText.isNotEmpty()) VpnUtil.toInt(portText) else null

        return Triple(true, host, port)
    }

    fun parseHtml(html: String, source: String): List<VpnServerRecord> {
        CollectorLog.d("Parsing HTML: ${html.length} chars")

        val doc = makeSoup(html)
        val candidates = findHostsTables(doc)
        val table = selectHostsTable(candidates)

        val colmap: Map<String, Int>
        val rows: List<Element>

        if (table != null) {
            colmap = buildColumnMap(table)
            rows = table.getElementsByTag("tr")
            CollectorLog.d("Hosts table columns: $colmap")
        } else {
            colmap = mapOf(
                "country" to 0,
                "host" to 1,
                "sessions" to 2,
                "perf" to 3,
                "softether" to 4,
                "l2tp" to 5,
                "openvpn" to 6,
                "sstp" to 7,
                "operator" to 8,
                "score" to 9,
            )
            rows = doc.getElementsByTag("tr")
        }

        CollectorLog.d("HTML rows: ${rows.size}")

        val servers = mutableListOf<VpnServerRecord>()
        val seen = mutableSetOf<String>()

        for (tr in rows) {
            val tds = tr.getElementsByTag("td")
            if (tds.isEmpty()) continue
            if (tds[0].classNames().contains("vg_table_header")) continue

            fun cell(key: String): Element? {
                val idx = colmap[key] ?: return null
                return if (idx < tds.size) tds[idx] else null
            }

            val hostCell = cell("host")
            val hostText = VpnUtil.clean(
                if (hostCell != null) hostCell.text() else tr.text()
            )

            val hostMatch = Regex(
                "\\b([A-Za-z0-9._-]+\\.opengw\\.net)\\b",
                RegexOption.IGNORE_CASE,
            ).find(hostText) ?: continue

            val hostname = VpnUtil.normalizeHost(hostMatch.groupValues[1])

            var ip = VpnUtil.normalizeIp(hostText)
            if (ip.isEmpty()) {
                ip = VpnUtil.normalizeIp(tr.text())
            }
            if (ip.isEmpty()) continue
            if (ip in seen) continue
            seen.add(ip)

            val server = VpnRecords.newServer()

            VpnRecords.setField(server, "identity.hostname", hostname, source)
            VpnRecords.setField(server, "identity.ip", ip, source)

            // ISP hostname lives in parentheses after the IP.
            val ispMatch = Regex("\\(([^()]+)\\)").find(hostText)
            if (ispMatch != null) {
                val isp = VpnUtil.normalizeHost(ispMatch.groupValues[1])
                if (isp.isNotEmpty() && !VpnUtil.validIp(isp)) {
                    VpnRecords.setField(server, "identity.ispHostname", isp, source)
                }
            }

            // Country: ISO code from the flag image (authoritative),
            // long name from the cell text next to it.
            val countryCell = cell("country")
            if (countryCell != null) {
                val img = countryCell.select("img").firstOrNull {
                    it.attr("src").contains("flags/", ignoreCase = true)
                }
                if (img != null) {
                    val isoM = Regex(
                        "flags/([A-Za-z]{2})\\.(?:png|gif|jpg|jpeg)",
                        RegexOption.IGNORE_CASE,
                    ).find(img.attr("src"))
                    if (isoM != null) {
                        VpnRecords.setField(
                            server,
                            "identity.country",
                            isoM.groupValues[1].uppercase(),
                            source,
                        )
                    }
                }

                var countryLong = VpnUtil.clean(countryCell.text())
                countryLong = countryLong.replace(
                    Regex("\\bphysical location\\b", RegexOption.IGNORE_CASE),
                    "",
                )
                VpnRecords.setField(
                    server,
                    "identity.countryLong",
                    VpnUtil.clean(countryLong),
                    source,
                )
            }

            // Sessions / uptime / cumulative users.
            val sessionsCell = cell("sessions")
            if (sessionsCell != null) {
                val sTxt = VpnUtil.clean(sessionsCell.text())

                val sM = Regex("([\\d,]+)\\s*session", RegexOption.IGNORE_CASE).find(sTxt)
                if (sM != null) {
                    VpnRecords.setField(
                        server,
                        "performance.sessions",
                        VpnUtil.toInt(sM.groupValues[1]),
                        source,
                    )
                }

                val uptimeDays = parseUptimeDays(sTxt)
                if (uptimeDays > 0) {
                    VpnRecords.setField(
                        server,
                        "performance.uptimeDays",
                        uptimeDays,
                        source,
                    )
                }

                val usersM = Regex(
                    "total\\s+([\\d,]+)\\s+user",
                    RegexOption.IGNORE_CASE,
                ).find(sTxt)
                if (usersM != null) {
                    VpnRecords.setField(
                        server,
                        "performance.totalUsers",
                        VpnUtil.toInt(usersM.groupValues[1]),
                        source,
                    )
                }
            }

            // Line quality: throughput, ping, transfers, logging policy.
            val perfCell = cell("perf")
            if (perfCell != null) {
                val pTxt = VpnUtil.clean(perfCell.text())

                val speedM = Regex("([\\d.,]+)\\s*Mbps", RegexOption.IGNORE_CASE).find(pTxt)
                if (speedM != null) {
                    VpnRecords.setField(
                        server,
                        "performance.speedMbps",
                        VpnUtil.toFloat(speedM.groupValues[1]),
                        source,
                    )
                }

                val pingM = Regex("Ping:\\s*([\\d.,]+)\\s*ms", RegexOption.IGNORE_CASE).find(pTxt)
                if (pingM != null) {
                    VpnRecords.setField(
                        server,
                        "performance.pingMs",
                        VpnUtil.toFloat(pingM.groupValues[1]),
                        source,
                    )
                }

                val trafficM = Regex("([\\d.,]+)\\s*(GB|TB)", RegexOption.IGNORE_CASE).find(pTxt)
                if (trafficM != null) {
                    var gb = VpnUtil.toFloat(trafficM.groupValues[1])
                    if (trafficM.groupValues[2].uppercase() == "TB") {
                        gb *= 1024.0
                    }
                    VpnRecords.setField(server, "performance.totalTrafficGB", gb, source)
                }

                val policyM = Regex(
                    "Logging policy:\\s*(.+)$",
                    RegexOption.IGNORE_CASE,
                ).find(pTxt)
                if (policyM != null) {
                    VpnRecords.setField(
                        server,
                        "logging.policy",
                        VpnUtil.clean(policyM.groupValues[1]),
                        source,
                    )
                }
            }

            // ---- SoftEther / SSL-VPN (column-scoped; never borrows
            //      ports from other protocol cells).
            val seCell = cell("softether")
            if (seCell != null) {
                val seTxt = VpnUtil.clean(seCell.text())

                val tcpM = Regex("TCP[:\\s]*(\\d+)", RegexOption.IGNORE_CASE).find(seTxt)
                val udpSupported =
                    Regex("UDP[:\\s]*Supported", RegexOption.IGNORE_CASE).containsMatchIn(seTxt)
                val udpPortM = Regex("UDP[:\\s]*(\\d+)", RegexOption.IGNORE_CASE).find(seTxt)

                val seTcpOk = tcpM != null && VpnUtil.validPort(VpnUtil.toInt(tcpM.groupValues[1]))

                if (seTcpOk) {
                    VpnRecords.setField(server, "protocols.softether.tcp.supported", true, source)
                    VpnRecords.setField(
                        server,
                        "protocols.softether.tcp.port",
                        VpnUtil.toInt(tcpM!!.groupValues[1]),
                        source,
                    )
                }

                val udpPortOk = udpPortM != null &&
                    VpnUtil.validPort(VpnUtil.toInt(udpPortM.groupValues[1]))

                if (udpSupported || udpPortOk) {
                    VpnRecords.setField(server, "protocols.softether.udp.supported", true, source)
                    // §6: the UDP port stays null unless printed.
                    if (udpPortOk) {
                        VpnRecords.setField(
                            server,
                            "protocols.softether.udp.port",
                            VpnUtil.toInt(udpPortM!!.groupValues[1]),
                            source,
                        )
                    }
                }

                if (seTcpOk || udpSupported || udpPortOk) {
                    VpnRecords.markSupported(server, "softether", source)
                }
            }

            // ---- L2TP/IPsec
            val l2tpCell = cell("l2tp")
            if (l2tpCell != null) {
                val l2tpTxt = VpnUtil.clean(l2tpCell.text())
                if (Regex("L2TP", RegexOption.IGNORE_CASE).containsMatchIn(l2tpTxt)) {
                    VpnRecords.markSupported(server, "l2tpIpsec", source)
                    VpnRecords.setField(server, "protocols.l2tpIpsec.port", 1701, source)
                }
            }

            // ---- OpenVPN: URL params on the do_openvpn link are
            //      authoritative; cell text is only a fallback.
            val ovpnCell = cell("openvpn")
            if (ovpnCell != null) {
                val link = ovpnCell.select("a").firstOrNull {
                    val href = it.attr("href")
                    href.isNotEmpty() && href.lowercase().contains("do_openvpn")
                }

                val params = LinkedHashMap<String, String>()
                if (link != null) {
                    val query = link.attr("href").substringAfterLast('?')
                    for (chunk in query.split("&")) {
                        if ("=" in chunk) {
                            val (k, v) = chunk.split("=", limit = 2)
                            params[k.lowercase()] = v
                        }
                    }
                }

                if (params.isNotEmpty()) {
                    VpnRecords.setField(server, "protocols.openvpn.configAvailable", true, source)
                    VpnRecords.markSupported(server, "openvpn", source)

                    val tcpQ = VpnUtil.toInt(params["tcp"] ?: "0")
                    val udpQ = VpnUtil.toInt(params["udp"] ?: "0")

                    if (VpnUtil.validPort(tcpQ)) {
                        VpnRecords.setField(server, "protocols.openvpn.tcp.supported", true, source)
                        VpnRecords.setField(server, "protocols.openvpn.tcp.port", tcpQ, source)
                    }

                    if (VpnUtil.validPort(udpQ)) {
                        VpnRecords.setField(server, "protocols.openvpn.udp.supported", true, source)
                        VpnRecords.setField(server, "protocols.openvpn.udp.port", udpQ, source)
                    }
                } else {
                    val ovpnTxt = VpnUtil.clean(ovpnCell.text())

                    val tcpM = Regex("TCP[:\\s]*(\\d+)", RegexOption.IGNORE_CASE).find(ovpnTxt)
                    val udpM = Regex("UDP[:\\s]*(\\d+)", RegexOption.IGNORE_CASE).find(ovpnTxt)

                    if (tcpM != null && VpnUtil.validPort(VpnUtil.toInt(tcpM.groupValues[1]))) {
                        VpnRecords.markSupported(server, "openvpn", source)
                        VpnRecords.setField(server, "protocols.openvpn.tcp.supported", true, source)
                        VpnRecords.setField(
                            server,
                            "protocols.openvpn.tcp.port",
                            VpnUtil.toInt(tcpM.groupValues[1]),
                            source,
                        )
                    }

                    if (udpM != null && VpnUtil.validPort(VpnUtil.toInt(udpM.groupValues[1]))) {
                        VpnRecords.markSupported(server, "openvpn", source)
                        VpnRecords.setField(server, "protocols.openvpn.udp.supported", true, source)
                        VpnRecords.setField(
                            server,
                            "protocols.openvpn.udp.port",
                            VpnUtil.toInt(udpM.groupValues[1]),
                            source,
                        )
                    }

                    if (Regex(
                            "OpenVPN\\s*Config\\s*file",
                            RegexOption.IGNORE_CASE,
                        ).containsMatchIn(ovpnTxt)
                    ) {
                        VpnRecords.markSupported(server, "openvpn", source)
                        VpnRecords.setField(server, "protocols.openvpn.configAvailable", true, source)
                    }
                }
            }

            // ---- MS-SSTP (port stays null unless printed).
            val sstpCell = cell("sstp")
            if (sstpCell != null) {
                val (supported, sstpHost, sstpPort) = parseSstp(VpnUtil.clean(sstpCell.text()))

                if (supported) {
                    VpnRecords.markSupported(server, "sstp", source)
                    VpnRecords.setField(server, "protocols.sstp.hostname", sstpHost, source)

                    if (VpnUtil.validPort(sstpPort)) {
                        VpnRecords.setField(server, "protocols.sstp.port", sstpPort, source)
                    }
                }
            }

            // ---- Operator + score.
            val opCell = cell("operator")
            if (opCell != null) {
                var operator = VpnUtil.clean(opCell.text())
                operator = operator.replace(Regex("^by\\s+", RegexOption.IGNORE_CASE), "")

                if (operator.isNotEmpty()) {
                    VpnRecords.setField(server, "operator.name", operator, source)
                }
            }

            val scoreCell = cell("score")
            if (scoreCell != null) {
                val scoreTxt = VpnUtil.clean(scoreCell.text())
                if (scoreTxt.isNotEmpty()) {
                    VpnRecords.setField(
                        server,
                        "performance.score",
                        VpnUtil.toInt(scoreTxt),
                        source,
                    )
                }
            }

            VpnRecords.sourceAdd(server, source)
            servers.add(server)
        }

        CollectorLog.d("HTML servers: ${servers.size}")
        return servers
    }
}
