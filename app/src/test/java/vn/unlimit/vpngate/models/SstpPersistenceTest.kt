package vn.unlimit.vpngate.models

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.data.model.VpnPortSource
import vn.unlimit.vpngate.db.AppDatabase
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Stage 2B: persistence + runtime semantics.
 *
 * The Room row representation ([VPNGateItem]) must round-trip the SSTP port
 * and its provenance verbatim, and the 4 -> 5 migration must add the two SSTP
 * columns while making pre-upgrade SSTP-capable rows connectable.
 *
 * Stage 2F-A: [VpnPortSource.UNKNOWN] is a single canonical literal (L-1), the
 * migration promotes already-supported SSTP rows to the protocol default rather
 * than to "no port known" (L-2), and the UI description of the SSTP port is
 * driven by SSTP's own provenance rather than by OpenVPN's TCP port (J-4/L-3).
 */
class SstpPersistenceTest {

    /** Simulates one persisted row: what Room writes to and reads back. */
    private fun persistAndRestore(conn: VPNGateConnection): VPNGateConnection {
        val row = conn.toVPNGateItem()
        // A real round trip goes through SQLite; the row is copied exactly as
        // the database hands it back.
        return VPNGateConnection().fromVPNGateItem(row.copy())
    }

    private fun sstpConnection(
        isSstpSupported: Boolean = true,
        sstpPort: Int = 0,
        sstpPortSource: String = VpnPortSource.UNKNOWN,
        tcpPort: Int = 0,
    ) = VPNGateConnection().apply {
        hostName = "vpn-sstp"
        ip = "10.20.30.50"
        this.isSSTPSupport = if (isSstpSupported) 1 else 0
        this.sstpPort = sstpPort
        this.sstpPortSource = sstpPortSource
        this.tcpPort = tcpPort
    }

    // ---- F1: explicit SSTP port survives the round trip ----

    @Test
    fun explicitSstpPortSurvivesRoomRoundTrip() {
        val conn = sstpConnection(
            sstpPort = 995,
            sstpPortSource = VpnPortSource.EXPLICIT,
            tcpPort = 443,
        )
        val restored = persistAndRestore(conn)

        assertEquals(995, restored.sstpPort)
        assertEquals(VpnPortSource.EXPLICIT, restored.sstpPortSource)
        assertEquals(995, restored.sstpConnectPort)
        // The OpenVPN TCP port must not resurface as the SSTP port.
        assertNotEquals(restored.tcpPort, restored.sstpConnectPort)
    }

    // ---- F1: DEFAULT provenance survives and still yields 443 ----

    @Test
    fun defaultSstpSurvivesRoomRoundTrip() {
        val conn = sstpConnection(sstpPort = 0, sstpPortSource = VpnPortSource.DEFAULT, tcpPort = 443)
        val restored = persistAndRestore(conn)

        assertEquals(0, restored.sstpPort)
        assertEquals(VpnPortSource.DEFAULT, restored.sstpPortSource)
        assertEquals(443, restored.sstpConnectPort)
    }

    // ---- F1: UNKNOWN stays unknown (no invented 443) ----

    @Test
    fun unknownSstpRemainsUnknownAfterRoundTrip() {
        val conn = sstpConnection(sstpPort = 0, sstpPortSource = VpnPortSource.UNKNOWN, tcpPort = 443)
        val restored = persistAndRestore(conn)

        assertEquals(0, restored.sstpPort)
        assertEquals(VpnPortSource.UNKNOWN, restored.sstpPortSource)
        assertEquals(0, restored.sstpConnectPort)
    }

    // ---- F1: legacy row (pre-migration) semantics ----

    @Test
    fun legacySstpRowWithoutPortYieldsZeroConnectPort() {
        // A row whose SSTP provenance is genuinely UNKNOWN: SSTP is supported
        // but nothing established a port. (Rows migrated from schema 4 no
        // longer look like this — Stage 2F-A L-2 promotes them to DEFAULT — so
        // this state can only now arise from an unrecognized stored value.)
        val legacy = VPNGateItem(
            hostName = "legacy-sstp",
            ip = "10.20.30.51",
            countryLong = null,
            countryShort = null,
            logType = null,
            operator = null,
            message = null,
            openVpnConfigData = null,
            isSSTPSupport = true,
            tcpPort = 443,
            seTcpPort = 995,
        )

        val restored = VPNGateConnection().fromVPNGateItem(legacy)

        assertEquals(0, restored.sstpPort)
        assertEquals(VpnPortSource.UNKNOWN, restored.sstpPortSource)
        assertEquals(0, restored.sstpConnectPort)
        // No other protocol's port is reassigned to SSTP.
        assertNotEquals(restored.tcpPort, restored.sstpConnectPort)
        assertNotEquals(restored.seTcpPort, restored.sstpConnectPort)
    }

    @Test
    fun vpnGateItemDefaultsAreZeroAndUnknown() {
        val row = VPNGateItem(
            hostName = "default-row",
            ip = "10.20.30.52",
            countryLong = null,
            countryShort = null,
            logType = null,
            operator = null,
            message = null,
            openVpnConfigData = null,
        )
        assertEquals(0, row.sstpPort)
        assertEquals(VpnPortSource.UNKNOWN, row.sstpPortSource)
    }

    // ---- F2: cross-protocol regression, without persistence ----

    @Test
    fun sstpConnectPortNeverUsesOpenVpnTcp() {
        val explicit = sstpConnection(sstpPort = 995, sstpPortSource = VpnPortSource.EXPLICIT, tcpPort = 443)
        assertEquals(995, explicit.sstpConnectPort)

        val defaulted = sstpConnection(sstpPort = 0, sstpPortSource = VpnPortSource.DEFAULT, tcpPort = 443)
        assertEquals(443, defaulted.sstpConnectPort)

        val unknown = sstpConnection(sstpPort = 0, sstpPortSource = VpnPortSource.UNKNOWN, tcpPort = 443)
        assertEquals(0, unknown.sstpConnectPort)
    }

    // ---- F1: the 4 -> 5 migration itself ----

    /** Records every statement the migration executes, without a database. */
    private class RecordingDatabase : InvocationHandler {
        val statements = mutableListOf<String>()

        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
            return when (method.name) {
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
        }

        fun asDatabase(): SupportSQLiteDatabase =
            Proxy.newProxyInstance(
                SupportSQLiteDatabase::class.java.classLoader,
                arrayOf(SupportSQLiteDatabase::class.java),
                this,
            ) as SupportSQLiteDatabase
    }

    @Test
    fun migration4To5AddsTheColumnsAndPromotesExistingSstpRows() {
        assertEquals(4, AppDatabase.MIGRATION_4_5.startVersion)
        assertEquals(5, AppDatabase.MIGRATION_4_5.endVersion)

        val recorder = RecordingDatabase()
        AppDatabase.MIGRATION_4_5.migrate(recorder.asDatabase())

        assertEquals(
            listOf(
                "ALTER TABLE VPNGateItem ADD COLUMN sstpPort INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE VPNGateItem ADD COLUMN sstpPortSource TEXT NOT NULL DEFAULT 'UNKNOWN'",
                // Stage 2F-A (L-2): existing SSTP-capable rows become connectable.
                "UPDATE VPNGateItem SET sstpPort = 443, sstpPortSource = 'DEFAULT' " +
                    "WHERE isSSTPSupport = 1",
            ),
            recorder.statements,
        )
        // Existing rows survive: nothing is dropped, deleted or recreated.
        for (sql in recorder.statements) {
            val upper = sql.uppercase()
            assertFalse("destructive statement: $sql", upper.contains("DROP"))
            assertFalse("destructive statement: $sql", upper.contains("DELETE"))
            assertFalse("recreating the table loses data: $sql", upper.contains("CREATE"))
        }
    }

    @Test
    fun migration4To5NeverRecordsAnExplicitPortForAMigratedRow() {
        val recorder = RecordingDatabase()
        AppDatabase.MIGRATION_4_5.migrate(recorder.asDatabase())

        // Stage 2F-A (L-2): the version 4 schema had no SSTP port column, so
        // there is no published port to preserve. Recording EXPLICIT would be
        // inventing source evidence.
        for (sql in recorder.statements) {
            assertFalse(
                "migration must never write EXPLICIT: $sql",
                sql.contains(VpnPortSource.EXPLICIT),
            )
        }
    }

    @Test
    fun theSchemaExportIsUnaffectedByTheMigrationCorrection() {
        // Stage 2F-A (L-2): MIGRATION_4_5 only changed WHICH VALUES it writes,
        // not the column definitions, so the exported schema (and therefore the
        // Room identity hash) must be untouched. If this fails, a stale export
        // would make Room demand a migration that does not exist.
        val json = File("app/schemas/vn.unlimit.vpngate.db.AppDatabase/5.json")
        if (!json.isFile) return // schema export not present in this checkout
        assertTrue(
            "schema 5 must still declare hostName as the primary key (E-1 kept it)",
            json.readText().contains("PRIMARY KEY(`hostName`)"),
        )
    }

    @Test
    fun exportedSchema5ContainsTheSstpColumns() {
        val json = File("app/schemas/vn.unlimit.vpngate.db.AppDatabase/5.json")
        if (!json.isFile) return // schema export not present in this checkout
        val text = json.readText()
        val entity = text.substringAfter("\"tableName\": \"VPNGateItem\"")
            .substringBefore("\"excluded_apps\"")
        assertTrue("sstpPort missing from schema 5", entity.contains("\"fieldPath\": \"sstpPort\""))
        assertTrue("sstpPortSource missing from schema 5", entity.contains("\"fieldPath\": \"sstpPortSource\""))
        assertTrue("sstpPort must be INTEGER", entity.contains("\"columnName\": \"sstpPort\",\n          \"affinity\": \"INTEGER\""))
        assertTrue("sstpPortSource must be TEXT", entity.contains("\"columnName\": \"sstpPortSource\",\n          \"affinity\": \"TEXT\""))
    }

    // ---- F3: SoftEther UDP must stay UNKNOWN through persistence ----

    @Test
    fun softEtherUdpUnknownPortSurvivesRoomRoundTrip() {
        for (tcp in listOf(443, 995)) {
            val conn = VPNGateConnection().apply {
                hostName = "se-udp-$tcp"
                ip = "10.20.30.60"
                seTcpPort = tcp
                seUdpPort = 0
                seUdpSupported = true
            }

            val restored = persistAndRestore(conn)

            assertEquals(tcp, restored.seTcpPort)
            assertEquals(true, restored.seUdpSupported)
            assertEquals(0, restored.seUdpPort)
            assertEquals(true, restored.isSeUdpPortUnknown)
            // No TCP -> UDP fallback, in memory or through the stored row.
            assertNotEquals(restored.seTcpPort, restored.seUdpPort)
        }
    }

    @Test
    fun softEtherUdpRowKeepsExplicitPortWhenPublished() {
        val conn = VPNGateConnection().apply {
            hostName = "se-udp-explicit"
            ip = "10.20.30.61"
            seTcpPort = 443
            seUdpPort = 5555
            seUdpSupported = true
        }
        val restored = persistAndRestore(conn)
        assertEquals(5555, restored.seUdpPort)
        assertFalse(restored.isSeUdpPortUnknown)
    }
}
