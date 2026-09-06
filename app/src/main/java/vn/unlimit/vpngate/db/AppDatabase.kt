package vn.unlimit.vpngate.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import vn.unlimit.vpngate.models.ExcludedApp
import vn.unlimit.vpngate.models.VPNGateItem

@Database(entities = [VPNGateItem::class, ExcludedApp::class], version = 4)
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
    }
}
