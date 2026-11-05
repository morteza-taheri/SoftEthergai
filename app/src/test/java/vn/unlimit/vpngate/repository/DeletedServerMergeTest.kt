package vn.unlimit.vpngate.repository

import androidx.sqlite.db.SupportSQLiteQuery
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.db.VPNGateItemDao
import vn.unlimit.vpngate.models.VPNGateConnectionList
import vn.unlimit.vpngate.models.VPNGateItem

/**
 * SERVER-ACTIONS: "delete server" must be permanent. A later accepted refresh
 * may still list the host (the feed is unaware of the user's choice), and the
 * retention merge deliberately keeps stored rows the feed omitted. Both paths
 * must drop a tombstoned host, and the displayed list must never show it.
 */
class DeletedServerMergeTest {

    private class FakeDao : VPNGateItemDao {
        var rows: List<VPNGateItem> = emptyList()

        override fun getAll(): List<VPNGateItem> = rows
        override fun replaceAll(items: List<VPNGateItem>) {
            rows = VPNGateItem.dedupeForStorage(items)
        }
        override fun filterAndSort(query: SupportSQLiteQuery): List<VPNGateItem> = emptyList()
        override fun insertAll(vararg vpnGateItem: VPNGateItem) {}
        override fun deleteAll() {}
        override fun count(): Int = rows.size
        override fun countWithOpenVpnConfig(): Int =
            rows.count { !it.openVpnConfigData.isNullOrBlank() }
    }

    private fun item(host: String) = VPNGateItem(
        hostName = host,
        ip = "203.0.113.1",
        countryLong = "Japan",
        countryShort = "JP",
        logType = null,
        operator = null,
        message = null,
        openVpnConfigData = null,
    )

    @After
    fun tearDown() {
        DeletedServers.replaceForTest(emptySet())
        ServerListCap.resolver = null
    }

    @Test
    fun `a refresh listing a deleted host cannot re-insert it`() {
        DeletedServers.mark("gone")
        val dao = FakeDao()
        dao.rows = listOf(item("kept"))

        mergeAll(
            dao = dao,
            incoming = listOf(item("gone"), item("kept")),
            manualRows = emptyList(),
            now = 1_000L,
        )

        assertEquals(listOf("kept"), dao.rows.map { it.hostName })
    }

    @Test
    fun `a stored deleted row is dropped even when the feed omits it`() {
        DeletedServers.mark("gone")
        val dao = FakeDao()
        dao.rows = listOf(item("gone"), item("kept"))

        mergeAll(
            dao = dao,
            incoming = listOf(item("kept")),
            manualRows = emptyList(),
            now = 1_000L,
        )

        assertFalse(dao.rows.any { it.hostName == "gone" })
        assertTrue(dao.rows.any { it.hostName == "kept" })
    }

    @Test
    fun `the fallback merge obeys the same tombstone`() {
        DeletedServers.mark("GONE")
        val dao = FakeDao()
        dao.rows = listOf(item("gone"))

        mergeFallback(
            dao = dao,
            incoming = listOf(item("gone"), item("kept")),
            manualRows = emptyList(),
            now = 1_000L,
        )

        assertEquals(listOf("kept"), dao.rows.map { it.hostName })
    }

    @Test
    fun `a deleted host never reaches the displayed list or Auto Mode dataset`() {
        DeletedServers.mark("gone")
        val list = VPNGateConnectionList().fromVPNGateItems(
            listOf(item("gone"), item("kept")),
        )
        assertEquals(listOf("kept"), list.toList().map { it.hostName })
    }

    @Test
    fun `when every incoming row is deleted the stale stored row is still purged`() {
        DeletedServers.mark("gone")
        val dao = FakeDao()
        dao.rows = listOf(item("gone"), item("kept"))

        mergeAll(
            dao = dao,
            incoming = listOf(item("gone")),
            manualRows = emptyList(),
            now = 1_000L,
        )

        assertFalse("stale deleted row must be gone", dao.rows.any { it.hostName == "gone" })
        assertTrue("unrelated row must survive", dao.rows.any { it.hostName == "kept" })
    }

    @Test
    fun `no tombstones leaves merge behaviour unchanged`() {
        DeletedServers.replaceForTest(emptySet())
        val dao = FakeDao()
        dao.rows = listOf(item("stale"))

        mergeAll(
            dao = dao,
            incoming = listOf(item("fresh")),
            manualRows = emptyList(),
            now = 1_000L,
        )

        assertEquals(setOf("fresh", "stale"), dao.rows.map { it.hostName }.toSet())
    }
}
