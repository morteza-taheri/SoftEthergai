package vn.unlimit.vpngate.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import vn.unlimit.vpngate.models.VPNGateItem

@Dao
interface VPNGateItemDao {
    @Query("SELECT * FROM vpngateitem")
    fun getAll(): List<VPNGateItem>

    @RawQuery
    fun filterAndSort(query: SupportSQLiteQuery): List<VPNGateItem>

    @Insert
    fun insertAll(vararg vpnGateItem: VPNGateItem)

    @Query("DELETE FROM vpngateitem")
    fun deleteAll()

    @Query("SELECT COUNT(hostName) FROM vpngateitem")
    fun count(): Int

    /**
     * How many stored servers carry an OpenVPN profile.
     *
     * Used to tell a healthy list from one whose profiles are missing, so a
     * degraded download cannot replace a connectable list.
     */
    @Query("SELECT COUNT(hostName) FROM vpngateitem WHERE openVpnConfigData IS NOT NULL AND openVpnConfigData != ''")
    fun countWithOpenVpnConfig(): Int

    /**
     * Atomically swap the stored dataset.
     *
     * Stage 2F-A (E-1): [hostName] is the primary key but the collector merges
     * on IP, so a feed can contain one hostname on two addresses. `insertAll`
     * aborts on a duplicate key, which would fail the entire transactional
     * replacement and leave the store half-written. The rows are therefore
     * collapsed to one per Room identity first — deterministically, and by a
     * documented policy (see [VPNGateItem.dedupeForStorage]) — so a duplicate
     * can never cost the user a whole refresh. The updater deduplicates before
     * it gets here as well; this is the last line of defence for any other
     * caller of this method.
     */
    @androidx.room.Transaction
    fun replaceAll(items: List<VPNGateItem>) {
        if (items.isEmpty()) return
        val unique = VPNGateItem.dedupeForStorage(items)
        deleteAll()
        insertAll(*unique.toTypedArray())
    }
}