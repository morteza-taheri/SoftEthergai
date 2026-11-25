package vn.unlimit.vpngate.repository

import vn.unlimit.vpngate.parser.EmergencyCsvParser
import vn.unlimit.vpngate.parser.OpenVpnConfigParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.models.VPNGateConnection

/**
 * SERVER-ACTIONS: the long-press export must contain exactly ONE server and
 * must be re-importable through the app's own CSV importer. The delete
 * tombstones must survive as a persistent, case-insensitive set.
 */
class ServerActionsTest {

    @After
    fun tearDown() {
        DeletedServers.replaceForTest(emptySet())
    }

    /** Minimal RFC4180 row splitter: quoted fields may contain commas/quotes. */
    private fun csvRow(line: String): List<String> {
        val out = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    field.append('"'); i++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += field.toString(); field.setLength(0) }
                else -> field.append(c)
            }
            i++
        }
        out += field.toString()
        return out
    }

    private fun connection() = VPNGateConnection().apply {
        hostName = "vpn123456789"
        ip = "203.0.114.25"
        score = 8123
        ping = 42
        speed = 95_000_000
        countryLong = "Japan"
        countryShort = "JP"
        numVpnSession = 37
        uptime = 86_400_000
        totalUser = 1234
        totalTraffic = 2L * 1024 * 1024 * 1024
        logType = "2weeks"
        operator = "Test, Operator"       // comma -> must be quoted
        message = "hello \"world\""       // quote -> must be escaped
        openVpnConfigData = "client\nremote 203.0.114.25 1194\n"
    }

    @Test
    fun `export has exactly one header and one data row`() {
        val csv = ServerActions.toCsv(connection())
        assertEquals(
            EmergencyCsvParser.ALL_COLUMNS.joinToString(","),
            csv.lines()[0].removePrefix("#"),
        )
        assertEquals(1, csv.lines().count { it.isNotBlank() } - 1)
        assertTrue(csv.endsWith("\n"))
    }

    @Test
    fun `exported CSV is detected and re-imported as one row`() {
        val csv = ServerActions.toCsv(connection())
        assertEquals(
            ManualCsvImport.Format.APP_CSV,
            ManualCsvImport.detect(csv),
        )
        val result = ManualCsvImport.parse(csv, now = 1_000L)
        assertTrue("parse must succeed: $result", result is ManualCsvImport.Result.Success)
        val success = result as ManualCsvImport.Result.Success
        assertEquals(1, success.items.size)
        val item = success.items.single()
        assertEquals("vpn123456789", item.hostName)
        assertEquals("203.0.114.25", item.ip)
        assertEquals("Japan", item.countryLong)
        assertEquals("JP", item.countryShort)
    }

    @Test
    fun `numeric and text fields round-trip through the importer`() {
        val original = connection()
        val result = ManualCsvImport.parse(ServerActions.toCsv(original), now = 1_000L)
        val item = (result as ManualCsvImport.Result.Success).items.single()
        assertEquals(original.score, item.score)
        assertEquals(original.ping, item.ping)
        assertEquals(original.speed, item.speed)
        assertEquals(original.uptime, item.uptime)
        assertEquals(original.totalTraffic, item.totalTraffic)
        assertEquals(original.numVpnSession, item.numVpnSession)
        assertEquals(original.totalUser, item.totalUser)
        assertEquals(original.operator, item.operator)
        assertEquals(original.message, item.message)
    }

    @Test
    fun `openvpn config is base64 encoded and decoded on re-import`() {
        val original = connection()
        val csv = ServerActions.toCsv(original)
        val columns = csv.lines()[0].removePrefix("#").split(',')
        val index = columns.indexOf("OpenVPN_ConfigData_Base64")
        val encoded = csvRow(csv.lines()[1])[index]
        assertFalse("config column must not be empty", encoded.isBlank())
        assertEquals(
            original.openVpnConfigData,
            String(OpenVpnConfigParser.decodeBase64(encoded)!!, Charsets.UTF_8),
        )
        val item = (ManualCsvImport.parse(csv, 1_000L) as ManualCsvImport.Result.Success)
            .items.single()
        assertFalse(item.openVpnConfigData.isNullOrBlank())
    }

    @Test
    fun `tcp and udp port facts survive export and re-import`() {
        val original = connection().apply {
            seTcpPort = 5555
            seUdpPort = 1194
            seUdpSupported = true
            tcpPort = 443
            udpPort = 1195
        }
        val result = ManualCsvImport.parse(ServerActions.toCsv(original), now = 1_000L)
        val item = (result as ManualCsvImport.Result.Success).items.single()
        assertEquals(5555, item.seTcpPort)
        assertEquals(1194, item.seUdpPort)
        assertTrue(item.seUdpSupported)
        assertEquals(443, item.tcpPort)
        assertEquals(1195, item.udpPort)
    }

    @Test
    fun `filename is sanitized and always ends with csv`() {
        assertEquals("vpn123456789.csv", ServerActions.fileNameFor(connection()))
        val nasty = connection().apply { hostName = "../a b:c\\d" }
        val name = ServerActions.fileNameFor(nasty)
        assertTrue(name.endsWith(".csv"))
        assertFalse(name.contains(".."))
        assertFalse(name.contains(" "))
        assertFalse(name.contains(":"))
    }

    @Test
    fun `tombstones are case insensitive and filter all funnel shapes`() {
        DeletedServers.mark("  VPN123456789  ")
        assertTrue(DeletedServers.isDeleted("vpn123456789"))
        assertTrue(DeletedServers.isDeleted("Vpn123456789"))
        assertFalse(DeletedServers.isDeleted("other"))
        assertFalse(DeletedServers.isDeleted(null))

        val rows = listOf("vpn123456789", "other", "VPN123456789")
        assertEquals(listOf("other"), DeletedServers.filter(rows) { it })
    }

    @Test
    fun `an empty tombstone set is a no-op for every funnel`() {
        DeletedServers.replaceForTest(emptySet())
        val rows = listOf("a", "b")
        assertEquals(rows, DeletedServers.filter(rows) { it })
    }
}
