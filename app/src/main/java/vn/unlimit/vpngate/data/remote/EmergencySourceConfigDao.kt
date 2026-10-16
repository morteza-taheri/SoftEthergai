package vn.unlimit.vpngate.data.remote

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Z1-IMPL-B (Roadmap §12.5.2 item B): persistence for user-configured
 * emergency server sources.
 *
 * Config-only: no source stored here is fetched yet (retrieval wiring is
 * roadmap item C), so [listEnabled] exists for that future phase and is
 * verified against the table here.
 *
 * `createdAt, id` is the stable ordering so two sources created within the
 * same millisecond never swap places. Duplicate-URL protection is NOT a
 * schema constraint (the table follows the MIGRATION_5_6 DDL literally);
 * the ViewModel checks [getByUrl] before inserting, so an "Add" of an
 * already-configured URL refreshes that row instead of appending a twin.
 */
@Dao
interface EmergencySourceConfigDao {
    @Query("SELECT * FROM emergency_source_config ORDER BY createdAt, id")
    fun observeAll(): Flow<List<EmergencySourceConfigEntity>>

    @Query("SELECT * FROM emergency_source_config WHERE enabled = 1 ORDER BY createdAt, id")
    suspend fun listEnabled(): List<EmergencySourceConfigEntity>

    @Query("SELECT * FROM emergency_source_config WHERE url = :url")
    suspend fun getByUrl(url: String): EmergencySourceConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: EmergencySourceConfigEntity)

    @Query("DELETE FROM emergency_source_config WHERE url = :url")
    suspend fun deleteByUrl(url: String)
}
