package vn.unlimit.vpngate.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import vn.unlimit.vpngate.data.model.VpnPortSource
import vn.unlimit.vpngate.data.model.VpnProtocolDefaults
import vn.unlimit.vpngate.data.remote.EmergencySourceConfigDao
import vn.unlimit.vpngate.data.remote.EmergencySourceConfigEntity
import vn.unlimit.vpngate.data.remote.ManualServerDao
import vn.unlimit.vpngate.data.remote.ManualServerEntity
import vn.unlimit.vpngate.history.ServerHistoryDao
import vn.unlimit.vpngate.history.ServerHistoryEvent
import vn.unlimit.vpngate.history.ServerHistorySummary
import vn.unlimit.vpngate.history.ServerProtocolSummary
import vn.unlimit.vpngate.models.ExcludedApp
import vn.unlimit.vpngate.models.VPNGateItem

@Database(
    entities = [
        VPNGateItem::class,
        ExcludedApp::class,
        EmergencySourceConfigEntity::class,
        ManualServerEntity::class,
        ServerHistorySummary::class,
        ServerProtocolSummary::class,
        ServerHistoryEvent::class,
    ],
    version = 9,
)
abstract class AppDatabase: RoomDatabase() {
    abstract fun vpnGateItemDao() : VPNGateItemDao
    abstract fun excludedAppDao(): ExcludedAppDao
    abstract fun emergencySourceConfigDao(): EmergencySourceConfigDao

    // Z1-IMPL-D (Roadmap §12.5.2 item D): manually entered servers.
    abstract fun manualServerDao(): ManualServerDao

    // F.29: durable server history. Deliberately not reachable through
    // VPNGateItemDao — a history read must never be able to run inside the
    // same transaction as `replaceAll`'s deleteAll() and observe a deleted
    // server list (see ServerHistoryEntities.kt's header).
    abstract fun serverHistoryDao(): ServerHistoryDao
    
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

        // Migration from version 5 to 6 — Z1-IMPL-B (Roadmap §12.5.2 item B):
        // user-configured emergency server sources. Purely additive: the new
        // table is self-contained (no foreign keys), so every stored server
        // and excluded app survives the upgrade untouched. The DDL matches
        // EmergencySourceConfigEntity exactly, including the SQL DEFAULT of 1
        // on `enabled`, so Room's schema check passes and a row that predates
        // any toggle is enabled rather than ambiguous.
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `emergency_source_config` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`url` TEXT NOT NULL, " +
                            "`label` TEXT, " +
                            "`enabled` INTEGER NOT NULL DEFAULT 1, " +
                            "`createdAt` INTEGER NOT NULL, " +
                            "`updatedAt` INTEGER NOT NULL)"
                )
            }
        }

        // Migration from version 6 to 7 — Z1-IMPL-D (Roadmap §12.5.2 item D):
        // manually entered servers. Same shape as 5 -> 6 and for the same
        // reason: the new table is self-contained (no foreign keys), so every
        // stored server, excluded app and emergency source survives the
        // upgrade untouched. The DDL matches ManualServerEntity column for
        // column, including the SQL DEFAULT of 1 on `enabled`, so Room's
        // schema check passes and a manual row that predates any toggle is
        // enabled rather than ambiguous.
        //
        // The unique index is the second statement and is as much a part of
        // the schema as the table: it is what makes `(host, port, protocol)`
        // a set of user decisions instead of a log of taps, and
        // ManualServerStore.add relies on it to update an existing entry
        // rather than silently fail on a duplicate.
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `manual_server` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`host` TEXT NOT NULL, " +
                            "`port` INTEGER NOT NULL, " +
                            "`protocol` TEXT NOT NULL, " +
                            "`label` TEXT, " +
                            "`notes` TEXT, " +
                            "`enabled` INTEGER NOT NULL DEFAULT 1, " +
                            "`createdAt` INTEGER NOT NULL, " +
                            "`updatedAt` INTEGER NOT NULL)"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                            "`index_manual_server_host_port_protocol` " +
                            "ON `manual_server` (`host`, `port`, `protocol`)"
                )
            }
        }

        // Migration from version 7 to 8 — RETENTION-IMPL-PHASE-1 (spec
        // §1.6.31 / ROADMAP §12.5.2 item G, phase 1): three additive columns
        // for Cumulative Server Retention (SERVER_SOURCE_ARCHITECTURE.md §9.2).
        // Each new column is NOT NULL DEFAULT 0, so every existing row and
        // every pre-existing table survive the upgrade untouched and Room's
        // schema validation passes against the KSP-exported version 8 schema.
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE VPNGateItem ADD COLUMN firstSeenAt INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE VPNGateItem ADD COLUMN lastSeenAt INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE VPNGateItem ADD COLUMN healthScore INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // Migration from version 8 to 9 — F.29: durable server history.
        //
        // Three new tables, no column changes, no backfill, and no foreign
        // keys. Every statement is purely additive, so every stored server,
        // excluded app, emergency source and manual server survives the
        // upgrade byte for byte; the history layer simply starts empty, which
        // is exactly the "unknown, not bad" state the ordering pass expects
        // for a server with no history row.
        //
        // Why no FK: `VPNGateItemDao.replaceAll` is `deleteAll()` +
        // `insertAll()`, and a cascade on `VPNGateItem.hostName` would delete
        // the entire history on every refresh. The tables are intentionally
        // orphans; see ServerHistoryEntities.kt's header.
        //
        // Why no SQL DEFAULT clauses on the counters: Room derives NOT NULL
        // from Kotlin nullability, and a Kotlin `= 0` default is applied by
        // the constructor, not by SQLite. Adding a SQL DEFAULT would make the
        // DDL disagree with the KSP-exported 9.json and fail validation.
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `server_history_summary` (" +
                            "`hostName` TEXT NOT NULL, " +
                            "`firstObservedAt` INTEGER, " +
                            "`lastAttemptAt` INTEGER, " +
                            "`lastSuccessAt` INTEGER, " +
                            "`lastFailureAt` INTEGER, " +
                            "`lastTimeoutAt` INTEGER, " +
                            "`successCount` INTEGER NOT NULL, " +
                            "`failureCount` INTEGER NOT NULL, " +
                            "`timeoutCount` INTEGER NOT NULL, " +
                            "`consecutiveFailures` INTEGER NOT NULL, " +
                            "`lastSuccessProtocol` TEXT, " +
                            "`lastSuccessNetwork` TEXT, " +
                            "`lastFailureReason` TEXT, " +
                            "`wifiSuccessCount` INTEGER NOT NULL, " +
                            "`wifiFailureCount` INTEGER NOT NULL, " +
                            "`cellularSuccessCount` INTEGER NOT NULL, " +
                            "`cellularFailureCount` INTEGER NOT NULL, " +
                            "`unknownNetworkSuccessCount` INTEGER NOT NULL, " +
                            "`unknownNetworkFailureCount` INTEGER NOT NULL, " +
                            "`qualityRejectionCount` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`hostName`))"
                )
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `server_history_protocol` (" +
                            "`hostName` TEXT NOT NULL, " +
                            "`protocol` TEXT NOT NULL, " +
                            "`successCount` INTEGER NOT NULL, " +
                            "`failureCount` INTEGER NOT NULL, " +
                            "`timeoutCount` INTEGER NOT NULL, " +
                            "`lastSuccessAt` INTEGER, " +
                            "`lastFailureAt` INTEGER, " +
                            "`lastOutcome` TEXT, " +
                            "PRIMARY KEY(`hostName`, `protocol`))"
                )
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `server_history_event` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`hostName` TEXT NOT NULL, " +
                            "`ip` TEXT, " +
                            "`eventType` TEXT NOT NULL, " +
                            "`outcome` TEXT NOT NULL, " +
                            "`networkType` TEXT NOT NULL, " +
                            "`protocol` TEXT, " +
                            "`attemptToken` TEXT, " +
                            "`durationMs` INTEGER, " +
                            "`failureReason` TEXT, " +
                            "`qualityResult` TEXT, " +
                            "`createdAt` INTEGER NOT NULL)"
                )
                // The four indexes are part of the schema, not an optimisation
                // bolted on afterwards: the DAO's only ranking read and the
                // success-idempotency check are both lookups these back.
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                            "`index_server_history_event_hostName_createdAt` " +
                            "ON `server_history_event` (`hostName`, `createdAt`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                            "`index_server_history_event_hostName_protocol_createdAt` " +
                            "ON `server_history_event` (`hostName`, `protocol`, `createdAt`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                            "`index_server_history_event_eventType_createdAt` " +
                            "ON `server_history_event` (`eventType`, `createdAt`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                            "`index_server_history_event_attemptToken_eventType` " +
                            "ON `server_history_event` (`attemptToken`, `eventType`)"
                )
            }
        }
    }
}
