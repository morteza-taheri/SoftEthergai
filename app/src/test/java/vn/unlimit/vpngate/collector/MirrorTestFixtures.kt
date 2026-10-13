package vn.unlimit.vpngate.collector

/**
 * Stage 2C-A mirror fixtures.
 *
 * Small, deterministic and offline. They are SYNTHETIC documents built to
 * exercise schema validation and source identity — not captured live
 * server data — and the hostnames/IPs used are the IETF documentation
 * ranges, so nothing here can be mistaken for a real VPN Gate server.
 */
object MirrorTestFixtures {

    const val SYNTHETIC_IP = "203.0.113.10"
    const val SYNTHETIC_HOST = "mirror-vpn-0001.opengw.net"

    const val BOOTSTRAP_MIRROR_URL = "http://198.51.100.20:1770"
    const val OTHER_MIRROR_URL = "http://198.51.100.21:1771"

    private const val HEADER_COUNTRY =
        "<td class='vg_table_header'><b>Country</b><BR>(Physical location)</td>"
    private const val HEADER_HOST =
        "<td class='vg_table_header'><b>DDNS hostname<BR>IP Address</b><BR>(ISP hostname)</td>"
    private const val HEADER_SESSIONS =
        "<td class='vg_table_header'><b>VPN sessions</b><BR>Uptime<BR>Cumulative users</td>"
    private const val HEADER_QUALITY =
        "<td class='vg_table_header'><b>Line quality</b><br>Throughput and Ping</td>"
    private const val HEADER_SOFTEHER =
        "<td class='vg_table_header'><b>SSL-VPN</b><br>Windows</td>"
    private const val HEADER_L2TP =
        "<td class='vg_table_header'><b>L2TP/IPsec</b><br>No client required</td>"
    private const val HEADER_OPENVPN =
        "<td class='vg_table_header'><b>OpenVPN</b><br>Windows, Mac</td>"
    private const val HEADER_SSTP =
        "<td class='vg_table_header'><b>MS-SSTP</b><br>No client required</td>"
    private const val HEADER_OPERATOR =
        "<td class='vg_table_header'><b>Volunteer operator's name</b></td>"
    private const val HEADER_SCORE =
        "<td class='vg_table_header'><b>Score</b><BR>(Quality)</td>"

    private fun row(
        hostname: String = SYNTHETIC_HOST,
        ip: String = SYNTHETIC_IP,
        softether: String = "TCP:443",
        openvpn: String = "TCP:1300",
    ): String = """
        <tr>
        <td class='vg_table_row_1'><img src='../images/flags/US.png' width='32' height='32' /><br>United States</td>
        <td class='vg_table_row_1'><b><span>$hostname</span></b><br><span>$ip</span></td>
        <td class='vg_table_row_1'>52 sessions<br>93 days<br>15699569 total users</td>
        <td class='vg_table_row_1'>30 Mbps<br>Ping: 21 ms</td>
        <td class='vg_table_row_1'>$softether</td>
        <td class='vg_table_row_1'>L2TP</td>
        <td class='vg_table_row_1'>$openvpn</td>
        <td class='vg_table_row_1'>SSTP Hostname : $hostname:995</td>
        <td class='vg_table_row_1'>By Synthetic Operator</td>
        <td class='vg_table_row_1'>27853</td>
        </tr>
    """.trimIndent()

    private fun hostsTable(vararg rows: String, includeOpenVpn: Boolean = true): String {
        val headers = buildList {
            add(HEADER_COUNTRY)
            add(HEADER_HOST)
            add(HEADER_SESSIONS)
            add(HEADER_QUALITY)
            add(HEADER_SOFTEHER)
            add(HEADER_L2TP)
            if (includeOpenVpn) add(HEADER_OPENVPN)
            add(HEADER_SSTP)
            add(HEADER_OPERATOR)
            add(HEADER_SCORE)
        }.joinToString("\n")

        return """
            <html><head><title>VPN Gate Mirror</title></head><body>
            <table border='1' id='vg_hosts_table_id' cellspacing='0' cellpadding='4'>
            <tr>
            $headers
            </td>
            </tr>
            ${rows.joinToString("\n")}
            </table>
            </body></html>
        """.trimIndent()
    }

    /** 1. VALID_MIRROR_HTML — canonical-like, complete schema. */
    val VALID_MIRROR_HTML: String get() = hostsTable(row())

    /** 2. INVALID_MIRROR_MISSING_COLUMNS — the OpenVPN column is gone. */
    val INVALID_MIRROR_MISSING_COLUMNS: String get() = hostsTable(row(), includeOpenVpn = false)

    /** 3. KEYWORD_ONLY_PAGE — the keywords, no hosts table. */
    val KEYWORD_ONLY_PAGE: String = """
        <html><head><title>VPN Gate mirror</title></head><body>
        <h1>OpenVPN and SoftEther are supported by this mirror</h1>
        <p>Please browse the server list from the main site.</p>
        </body></html>
    """.trimIndent()

    /** 4. ERROR_PAGE_WITH_KEYWORDS — an error/interstitial page with a keyword. */
    val ERROR_PAGE_WITH_KEYWORDS: String = """
        <html><head><title>503 Service Unavailable</title></head><body>
        <h1>Access denied</h1>
        <p>The requested resource is temporarily unavailable. OpenVPN and SoftEther
        services are offline while maintenance is in progress.</p>
        </body></html>
    """.trimIndent()

    /** A mirror that answers the HTML path with CSV: undefined schema. */
    val MIRROR_CSV_BODY: String = """
        *vpn_servers
        #HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64
        $SYNTHETIC_HOST,$SYNTHETIC_IP,100,20,10000000,United States,US,1,1000,1,1,2weeks,Synthetic,,
    """.trimIndent()

    /** The official mirror directory page, with one acceptable mirror. */
    val MIRROR_DIRECTORY_PAGE: String = """
        <html><body>
        <h2>VPNGate.net Mirror Sites</h2>
        <a href="$BOOTSTRAP_MIRROR_URL/">mirror one</a>
        <a href="https://www.vpngate.net/en/">main site (ignored)</a>
        <a href="http://www.tsukuba.ac.jp/english/">university (ignored)</a>
        <a href="ftp://example.com/pub/">wrong scheme (ignored)</a>
        </body></html>
    """.trimIndent()

    /** A directory page whose only link is unacceptable. */
    val DIRECTORY_WITHOUT_USABLE_MIRRORS: String = """
        <html><body>
        <h2>VPNGate.net Mirror Sites</h2>
        <a href="https://www.vpngate.net/en/">main site only</a>
        </body></html>
    """.trimIndent()

    /** The canonical CSV fixture reused for API validation tests. */
    fun canonicalApiCsv(): String = CollectorTestFixtures.load(CollectorTestFixtures.API_SAMPLE)
}
