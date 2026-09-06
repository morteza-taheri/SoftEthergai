package vn.unlimit.vpngate.collector

import java.io.File

/**
 * Shared-fixture access for the Kotlin oracle tests. The fixtures
 * live in the repo-root tests/fixtures/ directory (identical files
 * are used by the Python tests — one behavioral oracle).
 */
object CollectorTestFixtures {
    const val TABLE_SAMPLE = "vpngate_table_sample.html"
    const val NESTED_SAMPLE = "vpngate_table_nested_sample.html"
    const val API_SAMPLE = "vpngate_api_sample.csv"

    fun load(name: String): String {
        val candidates = listOf(
            "tests/fixtures/$name",
            "../tests/fixtures/$name",
            "../../tests/fixtures/$name",
        )
        for (path in candidates) {
            val f = File(path)
            if (f.isFile) return f.readText(Charsets.UTF_8)
        }
        error("fixture not found: $name (cwd=${File(".").absolutePath})")
    }

    const val OPENVPN_CONFIG_BODY = "# OpenVPN 2.0 Sample Configuration File\n" +
        "dev tun\n" +
        "proto tcp\n" +
        "remote 219.100.37.165 443\n"

    /** Port of tests/test_api_parser.py::make_api_row. */
    fun makeApiRow(overrides: Map<String, String> = emptyMap()): String {
        val fields = listOf(
            "HostName", "IP", "Score", "Ping", "Speed",
            "CountryLong", "CountryShort", "NumVpnSessions",
            "Uptime", "TotalUsers", "TotalTraffic", "LogType",
            "Operator", "Message", "OpenVPN_ConfigData_Base64",
        )

        val row = linkedMapOf(
            "HostName" to "public-vpn-206",
            "IP" to "219.100.37.165",
            "Score" to "2785379",
            "Ping" to "21",
            "Speed" to "659456803",
            "CountryLong" to "Japan",
            "CountryShort" to "JP",
            "NumVpnSessions" to "52",
            // ~93 days in ms
            "Uptime" to "8035200000",
            "TotalUsers" to "15699569",
            "TotalTraffic" to "633908788074369",
            "LogType" to "2weeks",
            "Operator" to "Daiyuu Nobori, Japan. Academic Use Only.",
            "Message" to "",
            "OpenVPN_ConfigData_Base64" to
                java.util.Base64.getEncoder()
                    .encodeToString(OPENVPN_CONFIG_BODY.toByteArray(Charsets.UTF_8)),
        )

        row.putAll(overrides)

        return (
            "#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort," +
                "NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType," +
                "Operator,Message,OpenVPN_ConfigData_Base64\n" +
                fields.joinToString(",") { row.getValue(it) }
            )
    }
}
