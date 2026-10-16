package vn.unlimit.vpngate.data.remote

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Z1-IMPL-D (Roadmap §12.5.2 item D): persistence for manually entered
 * servers.
 *
 * The surface is deliberately the minimum the feature needs, and it
 * mirrors `EmergencySourceConfigDao` so the two tables are read the same
 * way. [observeAll] drives the Settings list, [listEnabled] drives the
 * merge that re-emits manual rows on every accepted write, and [upsert]
 * leans on the table's unique index so an unchanged re-entry updates the
 * row instead of adding a second one.
 */
@Dao
interface ManualServerDao {

    @Query("SELECT * FROM manual_server ORDER BY createdAt, id")
    fun observeAll(): Flow<List<ManualServerEntity>>

    /** Every row, enabled or not, for the Settings list. */
    @Query("SELECT * FROM manual_server ORDER BY createdAt, id")
    suspend fun listAll(): List<ManualServerEntity>

    /**
     * Only the rows that take part in the server list.
     *
     * A disabled manual server is a server the user does not want dialled,
     * so it must not be re-emitted; it is still kept, because removing the
     * row would lose the user's address, port and notes.
     */
    @Query("SELECT * FROM manual_server WHERE enabled = 1 ORDER BY createdAt, id")
    suspend fun listEnabled(): List<ManualServerEntity>

    /** The row for one `(host, port, protocol)`, or null when it is new. */
    @Query(
        "SELECT * FROM manual_server WHERE host = :host AND port = :port AND protocol = :protocol",
    )
    suspend fun findByKey(host: String, port: Int, protocol: String): ManualServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ManualServerEntity): Long

    @Query("DELETE FROM manual_server WHERE id = :id")
    suspend fun deleteById(id: Long)
}
