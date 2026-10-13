package vn.unlimit.vpngate.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import vn.unlimit.vpngate.data.model.VpnPortSource
import vn.unlimit.vpngate.data.model.VpnProtocolDefaults
import vn.unlimit.vpngate.models.ExcludedApp
import vn.unlimit.vpngate.models.VPNGateItem

@Database(entities = [VPNGateItem::class, ExcludedApp::class], version = 5)
abstract class AppDatabase: RoomDatabase() {
    abstract fun vpnGateItemDao() : VPNGateItemDao
    abstract fun excludedAppDao(): ExcludedAppDao
    
    companion object {
        // Migration from version 2 to 3 - adding seTcpPort and seUdpPort fields
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add the new columns with default value 0
                database.execSQL(
                    "ALTER TABLE VPNGateItem ADD COLUMN seTcpPort INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE VPNGateItem ADD COLUMN seUdpPort INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // Migration from version 3 to 4 - SoftEther UDP may be
        // supported with an unknown port (§6/§9: nothing invented);
        // seUdpSupported marks that state for the UI.
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE VPNGateItem ADD COLUMN seUdpSupported INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // Migration from version 4 to 5 - MS-SSTP now carries its own port
        // and that port's provenance.
        //
        // Stage 2F-A (L-2): a row that already advertised SSTP confirmed the
        // capability, so the protocol default applies and the row must become
        // connectable. It used to be migrated to 0 / UNKNOWN instead, which
        // left every pre-upgrade SSTP server permanently unconnectable while
        // still showing its SSTP badge in the UI — and which contradicts both
        // the record layer (normalizeServer promotes a confirmed capability to
        // DEFAULT) and the legacy CSV path (VPNGateConnection.fromCsv).
        //
        // This is a DEFAULT, never EXPLICIT: the version 4 schema had no SSTP
        // port column at all, so there is no published port to preserve and
        // nothing that could be recorded as source evidence. Rows without SSTP
        // keep 0 / UNKNOWN, which means "no SSTP port known" and never lets
        // another protocol's port stand in for it.
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE VPNGateItem ADD COLUMN sstpPort INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE VPNGateItem ADD COLUMN sstpPortSource TEXT NOT NULL " +
                        "DEFAULT '${VpnPortSource.UNKNOWN}'"
                )
                database.execSQL(
                    "UPDATE VPNGateItem SET sstpPort = ${VpnProtocolDefaults.SSTP_PORT}, " +
                        "sstpPortSource = '${VpnPortSource.DEFAULT}' WHERE isSSTPSupport = 1"
                )
            }
        }
    }
}
