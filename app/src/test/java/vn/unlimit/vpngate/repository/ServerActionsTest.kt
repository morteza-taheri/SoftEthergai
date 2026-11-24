package vn.unlimit.vpngate.repository

import vn.unlimit.vpngate.parser.EmergencyCsvParser
import vn.unlimit.vpngate.data.model.VpnPortSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.models.VPNGateConnection

/**
 * SERVER-ACTIONS: single-server and full-list exports must be re-importable
 * through the app's own CSV importer without changing protocol-port identity.
 * Delete tombstones must survive as a persistent, case-insensitive set.
 */
class ServerActionsTest {

    @After
    fun tearDown() {
        DeletedServers.replaceForTest(emptySet())
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
        openVpnConfigData = "client\nproto tcp-client\nremote 203.0.114.25 1194\n"
        tcpPort = 1194
        udpPort = 1195
        seTcpPort = 992
        seUdpPort = 5555
        seUdpSupported = true
        isL2TPSupport = 1
        isSSTPSupport = 1
        sstpPort = 4443
        sstpPortSource = VpnPortSource.EXPLICIT
    }

    @Test
    fun `export has exactly one header and one data row`() {
        val csv = ServerActions.toCsv(connection())
        assertEquals(EmergencyCsvParser.ALL_COLUMNS.joinToString(","), csv.lines()[0])
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
        val item = (ManualCsvImport.parse(csv, 1_000L) as ManualCsvImport.Result.Success)
            .items.single()
        assertEquals(original.openVpnConfigData, item.openVpnConfigData)
    }

    @Test
    fun `all protocol ports round-trip without TCP UDP column swaps`() {
        val original = connection()
        val item = (ManualCsvImport.parse(ServerActions.toCsv(original), 1_000L)
            as ManualCsvImport.Result.Success).items.single()

        assertEquals(1194, item.tcpPort)
        assertEquals(1195, item.udpPort)
        assertEquals(992, item.seTcpPort)
        assertEquals(5555, item.seUdpPort)
        assertTrue(item.seUdpSupported)
        assertTrue(item.isL2TPSupport)
        assertTrue(item.isSSTPSupport)
        assertEquals(4443, item.sstpPort)
    }

    @Test
    fun `bulk export writes one header and preserves each server protocol mapping`() {
        val first = connection()
        val second = connection().apply {
            hostName = "vpn987654321"
            ip = "198.51.100.77"
            openVpnConfigData = "client\nproto tcp-client\nremote 198.51.100.77 443\n"
            tcpPort = 443
            udpPort = 1198
            seTcpPort = 993
            seUdpPort = 5556
            sstpPort = 445
        }
        val csv = ServerActions.toCsv(listOf(first, second))
        val result = ManualCsvImport.parse(csv, 1_000L) as ManualCsvImport.Result.Success

        assertEquals(2, csv.lines().count { it.isNotBlank() } - 1)
        assertEquals(2, result.items.size)
        val importedFirst = result.items.first { it.hostName == first.hostName }
        val importedSecond = result.items.first { it.hostName == second.hostName }
        assertEquals(1194, importedFirst.tcpPort)
        assertEquals(1195, importedFirst.udpPort)
        assertEquals(992, importedFirst.seTcpPort)
        assertEquals(5555, importedFirst.seUdpPort)
        assertEquals(443, importedSecond.tcpPort)
        assertEquals(1198, importedSecond.udpPort)
        assertEquals(993, importedSecond.seTcpPort)
        assertEquals(5556, importedSecond.seUdpPort)
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
