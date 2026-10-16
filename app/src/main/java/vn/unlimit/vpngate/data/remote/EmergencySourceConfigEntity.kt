package vn.unlimit.vpngate.data.remote

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Z1-IMPL-B (Roadmap §12.5.2 item B): one user-configured emergency server
 * source.
 *
 * The table is created by `AppDatabase.MIGRATION_5_6` and is deliberately
 * additive: it has no foreign keys and no relationship to the collected
 * server table, so a version-5 database keeps every stored server and
 * excluded app exactly as it was.
 *
 * [enabled] carries a SQL DEFAULT of 1 so a row written by any older code
 * path is enabled rather than NULL or silently disabled — matching the
 * MIGRATION_5_6 DDL exactly (Room compares the created schema with this
 * definition when the database is opened).
 */
@Entity(tableName = "emergency_source_config")
data class EmergencySourceConfigEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val url: String,
    val label: String? = null,
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
)
