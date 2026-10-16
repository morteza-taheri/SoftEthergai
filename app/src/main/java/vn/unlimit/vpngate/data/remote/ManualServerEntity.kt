package vn.unlimit.vpngate.data.remote

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Z1-IMPL-D (Roadmap §12.5.2 item D): one manually entered server.
 *
 * The unique index on `(host, port, protocol)` is what makes the table a
 * set of user decisions rather than a log of taps: adding the same server
 * twice updates the existing row instead of creating a duplicate the user
 * then has to find and delete.
 *
 * Room annotations here are CLASS-retention and are deliberately never read
 * by reflection in tests — the compiled abstract methods and the KSP-exported
 * schema JSON are the ground truth, as established in item B.
 */
@Entity(
    tableName = "manual_server",
    indices = [Index(value = ["host", "port", "protocol"], unique = true)],
)
data class ManualServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val host: String,
    val port: Int,
    val protocol: String,
    val label: String? = null,
    val notes: String? = null,
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
)
