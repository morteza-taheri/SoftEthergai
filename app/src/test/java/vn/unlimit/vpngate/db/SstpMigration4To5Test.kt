package vn.unlimit.vpngate.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.data.model.VpnPortSource
import vn.unlimit.vpngate.data.model.VpnProtocolDefaults
import vn.unlimit.vpngate.data.model.VpnProtocolNames
import vn.unlimit.vpngate.data.model.VpnRecords
import vn.unlimit.vpngate.data.model.VpnServerRecord
import vn.unlimit.vpngate.merger.VpnServerMerger
import vn.unlimit.vpngate.models.VPNGateConnection
import vn.unlimit.vpngate.models.VPNGateItem
import vn.unlimit.vpngate.parser.VpnGateHtmlParser
import vn.unlimit.vpngate.repository.VpnConnectionMapper
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Stage 2F-A: the 4 -> 5 migration, and the SSTP provenance invariants around
 * it.
 *
 * L-1: [VpnPortSource.UNKNOWN] must have exactly ONE representation, so a
 * stored "unknown" and an empty column cannot drift into two different states.
 *
 * L-2: a row that already advertised SSTP must end the migration CONNECTABLE.
 * The version 4 schema had no SSTP port column, so there is no published port
 * to preserve and the promotion is a protocol DEFAULT — never EXPLICIT.
 *
 * The row-level expectations for freshly collected data (C, D, E) are verified
 * through the real parser -> merger -> mapper -> model path rather than by
 * asserting on the migration SQL, because those states are produced by
 * collection, not by a migration.
 */
class SstpMigration4To5Test {

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** Records the statements the migration runs, without a database. */
    private class RecordingDatabase : InvocationHandler {
        val statements = mutableListOf<String>()

        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? = when (method.name) {
            "execSQL" -> {
                statements.add(args!![0] as String)
                null
            }
            "toString" -> "RecordingDatabase"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args!![0]
            else -> when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                String::class.java -> ""
                else -> null
            }
        }

        fun asDatabase(): SupportSQLiteDatabase =
            Proxy.newProxyInstance(
                SupportSQLiteDatabase::class.java.classLoader,
                arrayOf(SupportSQLiteDatabase::class.java),
                this,
            ) as SupportSQLiteDatabase
    }

    private fun migratedStatements(): List<String> =
        RecordingDatabase().also { AppDatabase.MIGRATION_4_5.migrate(it.asDatabase()) }.statements

    /** The single statement that gives pre-existing SSTP rows a port. */
    private fun sstpPromotionStatement(): String =
        migratedStatements().single { it.trimStart().startsWith("UPDATE", ignoreCase = true) }

    // ---------------------------------------------------------------------
    // A) An existing SSTP-capable row becomes 443 / DEFAULT
    // ---------------------------------------------------------------------

    @Test
    fun `existing sstp row is migrated to the protocol default, not to unknown`() {
        val update = sstpPromotionStatement()

        // Scoped strictly to rows that already advertised SSTP.
        assertTrue(
            "the promotion must apply only to SSTP-capable rows, was: $update",
            update.contains("WHERE isSSTPSupport = 1"),
        )
        // It writes the protocol default and DEFAULT provenance.
        assertTrue("expected sstpPort = 443, was: $update", update.contains("sstpPort = 443"))
        assertEquals(443, VpnProtocolDefaults.SSTP_PORT)
        assertTrue(
            "expected DEFAULT provenance, was: $update",
            update.contains("sstpPortSource = '${VpnPortSource.DEFAULT}'"),
        )
    }

    @Test
    fun `the columns default to no known port for rows the promotion skips`() {
        val statements = migratedStatements()
        assertTrue(
            "sstpPort column must default to 0",
            statements.any { it.contains("sstpPort INTEGER NOT NULL DEFAULT 0") },
        )
        assertTrue(
            "sstpPortSource column must default to the canonical UNKNOWN",
            statements.any {
                it.contains("sstpPortSource TEXT NOT NULL DEFAULT '${VpnPortSource.UNKNOWN}'")
            },
        )
    }

    // ---------------------------------------------------------------------
    // B) A row without SSTP is untouched
    // ---------------------------------------------------------------------

    @Test
    fun `a row without sstp support is never given a port`() {
        val update = sstpPromotionStatement()

        // isSSTPSupport = 0 does not match, so the column defaults apply.
        assertTrue(update.contains("WHERE isSSTPSupport = 1"))
        assertFalse(
            "the promotion must not be unconditional, was: $update",
            update.contains("WHERE") && !update.contains("isSSTPSupport"),
        )

        // And the value such a row carries is UNKNOWN with no port, which the
        // model must never turn into a connectable port.
        val row = VPNGateItem(
            hostName = "no-sstp",
            ip = "10.0.0.1",
            countryLong = null,
            countryShort = null,
            logType = null,
            operator = null,
            message = null,
            openVpnConfigData = null,
            isSSTPSupport = false,
        )
        assertEquals(0, row.sstpPort)
        assertEquals(VpnPortSource.UNKNOWN, row.sstpPortSource)
    }

    // ---------------------------------------------------------------------
    // C) Freshly collected SSTP with an explicit port
    // ---------------------------------------------------------------------

    @Test
    fun `a collected sstp port printed by the source is explicit`() {
        val item = collectSingleServer(sstpCell = "SSTP Hostname : public-vpn-206.opengw.net:1194")

        assertTrue("SSTP must be recognised", item.isSSTPSupport)
        assertEquals(1194, item.sstpPort)
        assertEquals(VpnPortSource.EXPLICIT, item.sstpPortSource)
    }

    // ---------------------------------------------------------------------
    // D) Freshly collected SSTP with no published port
    // ---------------------------------------------------------------------

    @Test
    fun `a collected sstp capability with no port becomes the protocol default`() {
        val item = collectSingleServer(sstpCell = "SSTP Hostname : public-vpn-206.opengw.net")

        assertTrue("SSTP must be recognised", item.isSSTPSupport)
        assertEquals(0, item.sstpPort)
        assertEquals(VpnPortSource.DEFAULT, item.sstpPortSource)
    }

    // ---------------------------------------------------------------------
    // E) Invalid / missing port — never guessed, never cross-protocol
    // ---------------------------------------------------------------------

    @Test
    fun `an out of range collected sstp port is discarded rather than clamped`() {
        val item = collectSingleServer(sstpCell = "SSTP Hostname : public-vpn-206.opengw.net:70000")

        assertTrue("SSTP must still be recognised", item.isSSTPSupport)
        assertNotEquals(70000, item.sstpPort)
        // The port is unknown, and no other protocol's port may stand in.
        assertEquals(0, item.sstpPort)
    }

    @Test
    fun `no collected port is ever borrowed from another protocol`() {
        // The fixture publishes an OpenVPN TCP port, a SoftEther TCP port and
        // an SSTP capability, so there are three other numbers the SSTP port
        // could wrongly have become.
        val conn = collectSingleConnection(sstpCell = "SSTP Hostname : public-vpn-206.opengw.net")
        val item = conn.toVPNGateItem()

        assertEquals(0, item.sstpPort)
        // The SSTP port is none of the three other published ports.
        assertNotEquals(1300, item.sstpPort)
        assertNotEquals(443, item.sstpPort)
        assertNotEquals(5555, item.sstpPort)
        // DEFAULT provenance, so 443 is intentional and documented rather than
        // a value lifted from the OpenVPN or SoftEther column.
        assertEquals(VpnPortSource.DEFAULT, item.sstpPortSource)
        assertEquals(443, conn.sstpConnectPort)
        assertNotEquals(conn.tcpPort, item.sstpPort)
        assertNotEquals(conn.seTcpPort, item.sstpPort)
    }

    // ---------------------------------------------------------------------
    // L-1 fallout: a canonical UNKNOWN must not block real provenance
    // ---------------------------------------------------------------------

    @Test
    fun `the canonical unknown sentinel does not block a later explicit port`() {
        // Stage 2F-A (L-1): UNKNOWN is no longer the empty string, so a fresh
        // record's `portSource` leaf holds a non-empty value. If that sentinel
        // were read as a meaningful value, no parser could ever establish a
        // port provenance and every server would silently fall back to
        // DEFAULT / UNKNOWN.
        val record = VpnRecords.newServer()
        assertEquals(
            VpnPortSource.UNKNOWN,
            VpnRecords.str(VpnRecords.map(VpnRecords.protocols(record)[VpnProtocolNames.SSTP])["portSource"]),
        )

        VpnRecords.setField(record, "protocols.sstp.portSource", VpnPortSource.EXPLICIT, "html")
        val sstp = VpnRecords.map(VpnRecords.protocols(record)[VpnProtocolNames.SSTP])
        assertEquals(VpnPortSource.EXPLICIT, VpnRecords.str(sstp["portSource"]))
    }

    // ---------------------------------------------------------------------
    // L-1: one canonical UNKNOWN representation
    // ---------------------------------------------------------------------

    @Test
    fun `unknown has exactly one representation`() {
        assertEquals("UNKNOWN", VpnPortSource.UNKNOWN)
        assertNotEquals(VpnPortSource.DEFAULT, VpnPortSource.UNKNOWN)
        assertNotEquals(VpnPortSource.EXPLICIT, VpnPortSource.DEFAULT)
        assertNotEquals(VpnPortSource.UNKNOWN, VpnPortSource.EXPLICIT)
    }

    @Test
    fun `every defensive variant of unknown folds onto the canonical value`() {
        for (raw in listOf(null, "", "   ", "UNKNOWN", "unknown", "Unknown", 0, listOf<String>())) {
            assertEquals(
                "raw=$raw must normalize to UNKNOWN",
                VpnPortSource.UNKNOWN,
                VpnPortSource.normalized(raw),
            )
        }
    }

    @Test
    fun `explicit and default survive normalization unchanged`() {
        assertEquals(VpnPortSource.EXPLICIT, VpnPortSource.normalized("EXPLICIT"))
        assertEquals(VpnPortSource.EXPLICIT, VpnPortSource.normalized(" explicit "))
        assertEquals(VpnPortSource.DEFAULT, VpnPortSource.normalized("DEFAULT"))
        assertEquals(VpnPortSource.DEFAULT, VpnPortSource.normalized("Default"))
    }

    @Test
    fun `a row written with the old empty-string unknown still reads as unknown`() {
        // The pre-L-1 code stored "" for UNKNOWN. Such a row must not become a
        // third, unrecognized state that code could mistake for a known value.
        val legacy = VPNGateItem(
            hostName = "legacy-empty",
            ip = "10.0.0.9",
            countryLong = null,
            countryShort = null,
            logType = null,
            operator = null,
            message = null,
            openVpnConfigData = null,
            isSSTPSupport = true,
            sstpPort = 0,
            sstpPortSource = "",
        )

        assertEquals(VpnPortSource.UNKNOWN, VpnPortSource.normalized(legacy.sstpPortSource))
        assertEquals(VpnPortSource.UNKNOWN, legacy.copy().let { VpnPortSource.normalized(it.sstpPortSource) })
    }

    @Test
    fun `the migration default matches the canonical unknown literal`() {
        // If these ever diverge again, a non-SSTP row migrated from schema 4
        // would be stored with a value the model cannot interpret.
        assertTrue(
            migratedStatements().any {
                it.contains("sstpPortSource TEXT NOT NULL DEFAULT '${VpnPortSource.UNKNOWN}'")
            },
        )
        assertEquals("UNKNOWN", VpnPortSource.UNKNOWN)
    }

    // ---------------------------------------------------------------------
    // Migration safety
    // ---------------------------------------------------------------------

    @Test
    fun `the migration is not destructive and is bounded to 4 to 5`() {
        assertEquals(4, AppDatabase.MIGRATION_4_5.startVersion)
        assertEquals(5, AppDatabase.MIGRATION_4_5.endVersion)

        for (sql in migratedStatements()) {
            val upper = sql.uppercase()
            assertFalse("destructive: $sql", upper.contains("DROP"))
            assertFalse("destructive: $sql", upper.contains("DELETE"))
            assertFalse("destructive: $sql", upper.contains("TRUNCATE"))
            assertFalse("recreating the table would lose data: $sql", upper.contains("CREATE"))
        }
    }

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    /** One row of HTML through the real parse -> merge -> validate -> map path. */
    private fun collectSingleConnection(sstpCell: String): VPNGateConnection {
        val records: List<VpnServerRecord> = VpnGateHtmlParser.parseHtml(htmlFixture(sstpCell), "html")
        assertEquals("fixture must yield exactly one record", 1, records.size)

        val merged = VpnServerMerger.mergeRecords(records)
        val valid = VpnServerMerger.validateServers(merged)
        assertEquals("fixture must yield exactly one valid record", 1, valid.size)

        val conn = VpnConnectionMapper.toConnection(valid.first())
        assertNotNull("fixture must map to a connection", conn)
        return conn!!
    }

    /** The same row, persisted. */
    private fun collectSingleServer(sstpCell: String): VPNGateItem =
        collectSingleConnection(sstpCell).toVPNGateItem()

    /**
     * One row of HTML through the real parse -> merge -> validate -> map path.
     *
     * The document mirrors the published column layout exactly (country, host,
     * sessions, quality, SoftEther, L2TP, OpenVPN, MS-SSTP, operator, score),
     * and deliberately publishes an OpenVPN TCP port, a SoftEther TCP port and
     * a SoftEther UDP port so there are three other numbers an SSTP port could
     * wrongly have become.
     */
    private fun htmlFixture(sstpCell: String): String {
        val headers = listOf(
            "<td class='vg_table_header'><b>Country</b><BR>(Physical location)</td>",
            "<td class='vg_table_header'><b>DDNS hostname<BR>IP Address</b><BR>(ISP hostname)</td>",
            "<td class='vg_table_header'><b>VPN sessions</b><BR>Uptime<BR>Cumulative users</td>",
            "<td class='vg_table_header'><b>Line quality</b><br>Throughput and Ping</td>",
            "<td class='vg_table_header'><b>SSL-VPN</b><br>Windows</td>",
            "<td class='vg_table_header'><b>L2TP/IPsec</b><br>No client required</td>",
            "<td class='vg_table_header'><b>OpenVPN</b><br>Windows, Mac</td>",
            "<td class='vg_table_header'><b>MS-SSTP</b><br>No client required</td>",
            "<td class='vg_table_header'><b>Volunteer operator's name</b></td>",
            "<td class='vg_table_header'><b>Score</b><BR>(Quality)</td>",
        ).joinToString("\n")

        val row = """
            <tr>
            <td class='vg_table_row_1'><img src='../images/flags/US.png' width='32' height='32' /><br>United States</td>
            <td class='vg_table_row_1'><b><span>public-vpn-206.opengw.net</span></b><br><span>219.126.84.5</span></td>
            <td class='vg_table_row_1'>52 sessions<br>93 days<br>15699569 total users</td>
            <td class='vg_table_row_1'>30 Mbps<br>Ping: 21 ms</td>
            <td class='vg_table_row_1'>TCP:443<br>UDP:5555</td>
            <td class='vg_table_row_1'>L2TP</td>
            <td class='vg_table_row_1'>TCP:1300</td>
            <td class='vg_table_row_1'>$sstpCell</td>
            <td class='vg_table_row_1'>By Test Operator</td>
            <td class='vg_table_row_1'>27853</td>
            </tr>
        """.trimIndent()

        return """
            <html><head><title>VPN Gate</title></head><body>
            <table border='1' id='vg_hosts_table_id' cellspacing='0' cellpadding='4'>
            <tr>
            $headers
            </td>
            </tr>
            $row
            </table>
            </body></html>
        """.trimIndent()
    }
}
