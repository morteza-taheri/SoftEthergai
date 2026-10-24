package vn.unlimit.vpngate.repository

import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.db.VPNGateItemDao
import vn.unlimit.vpngate.models.VPNGateItem

/**
 * v1.0.58 (BLOCK v1.0.58 PART B, device finding V9): the EMERGENCY rung is a
 * supplement, and a supplement may never shrink the dataset or become the
 * last-known-good snapshot.
 *
 * ## What V9 reported, and what the device actually held
 *
 * With the tunnel down and PHYSICAL failing, the emergency rung accepted 170
 * records and the operator saw the dataset go 261 -> 170.
 *
 * Measured on the device (`run-as … cat databases/vpn_gate_connector`,
 * `files/collector_vpn_servers.json`):
 *
 *  - `VPNGateItem` = **261** rows, 261 distinct IPs, 103 with an OpenVPN
 *    profile — the table had **not** shrunk.
 *  - `files/collector_vpn_servers.json` = `{"count": 170, …}` — the
 *    last-known-good snapshot had been **overwritten with the emergency
 *    excerpt**, and its first row (`vpn898813861` / `1.241.242.170`) is the
 *    same server as the table's first row, i.e. the emergency file overlaps
 *    the stored dataset rather than replacing it.
 *
 * So the merge was already correct (`mergeAll`, because the emergency rung
 * leaves the origin flag false) and the loss happened **one refresh later**:
 * the emergency run promoted its own 170-row subset to last-known-good, and
 * the next run that had to fall back to the snapshot (`loadSnapshot`,
 * `fromCache = true`) replaced the table with that subset wholesale.
 *
 * The fix is at the promotion site in [ServerListUpdater.update]: a dataset
 * supplied by the emergency rung is never written to last-known-good. These
 * tests pin both halves — the merge that was already right, and the promotion
 * that was not.
 */
class EmergencyMergeRetentionTest {

    /** Minimal in-memory `VPNGateItemDao`, mirroring the real `@Transaction` swap. */
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

    /** Room stand-in that counts the two writes this contract is about. */
    private class FakeStore(initial: List<VPNGateItem> = emptyList()) : ServerListUpdater.Store {
        var items: List<VPNGateItem> = initial
        var replaceCount = 0
        var snapshotWrites = 0
        var lastSnapshot: List<VPNGateItem> = emptyList()

        override fun currentCount(): Int = items.size

        override fun currentOpenVpnConfigCount(): Int =
            items.count { !it.openVpnConfigData.isNullOrBlank() }

        override fun currentDataset(): List<VPNGateItem> = items

        override fun replaceAll(items: List<VPNGateItem>) {
            this.items = VPNGateItem.dedupeForStorage(items)
            replaceCount++
        }

        override fun saveLastKnownGood(items: List<VPNGateItem>): Boolean {
            snapshotWrites++
            lastSnapshot = items
            return true
        }
    }

    private fun row(
        host: String,
        ip: String = "203.0.113.1",
        profile: Boolean = false,
        lastSeenAt: Long = 0L,
    ) = VPNGateItem(
        hostName = host,
        ip = ip,
        countryLong = "Japan",
        countryShort = "JP",
        logType = null,
        operator = null,
        message = null,
        openVpnConfigData = if (profile) "client\nproto tcp\nremote $ip 443\n" else null,
        lastSeenAt = lastSeenAt,
    )

    /** 261 stored rows, the device's row count when V9 was observed. */
    private fun storedDataset() = (1..261).map { row("stored-$it") }

    /**
     * 170 emergency rows, the size of the published `servers.csv`: 120 of them
     * describe servers the table already holds and 50 are new, which is the
     * shape the device showed (the emergency file is an excerpt of the same
     * feed, not a disjoint set).
     */
    private fun emergencyDataset(): List<VPNGateItem> {
        val overlapping = (1..120).map { row("stored-$it") }
        val fresh = (1..50).map { row("emergency-$it") }
        return overlapping + fresh
    }

    // ------------------------------------------------------------ the merge

    @Test
    fun `an emergency refresh never shrinks the stored dataset`() {
        val dao = FakeDao()
        dao.rows = storedDataset()
        val before = dao.rows.map { it.hostName }.toSet()

        mergeAll(dao, emergencyDataset(), emptyList(), now = 1_000L)

        val after = dao.rows.map { it.hostName }.toSet()
        assertEquals(
            "not one of the 261 stored rows may be lost to a 170-row supplement",
            emptySet<String>(),
            before - after,
        )
        // 261 stored + the 50 servers only the emergency file knew about.
        assertEquals(311, dao.rows.size)
        assertTrue(dao.rows.size >= before.size)
    }

    @Test
    fun `overlapping emergency rows are refreshed and absent rows are retained`() {
        val dao = FakeDao()
        dao.rows = (1..261).map { row("stored-$it", lastSeenAt = 500L) }
        val firstSeen = dao.rows.associate { it.hostName to it.firstSeenAt }

        mergeAll(dao, emergencyDataset(), emptyList(), now = 9_000L)

        val byHost = dao.rows.associateBy { it.hostName }
        // Refreshed: an overlapping row takes the new observation timestamp…
        assertEquals(9_000L, byHost.getValue("stored-1").lastSeenAt)
        // …but keeps the moment it was first seen.
        assertEquals(firstSeen.getValue("stored-1"), byHost.getValue("stored-1").firstSeenAt)
        // Added: a server only the emergency file knew about.
        assertTrue(byHost.containsKey("emergency-1"))
        // Retained: a stored server the emergency excerpt does not mention.
        assertTrue(byHost.containsKey("stored-261"))
        assertEquals(500L, byHost.getValue("stored-261").lastSeenAt)
    }

    @Test
    fun `a manual row survives an emergency merge`() {
        val dao = FakeDao()
        dao.rows = storedDataset()
        val manual = row("my-server.example", ip = "198.51.100.7")

        mergeAll(dao, emergencyDataset(), listOf(manual), now = 1_000L)

        assertTrue(
            "a user-owned row must never be pruned by a supplementary source",
            dao.rows.any { it.hostName == "my-server.example" },
        )
    }

    // ------------------------------------------------- the snapshot promotion

    @Test
    fun `an emergency dataset is stored but never promoted to last-known-good`() = runBlocking {
        val store = FakeStore(storedDataset())
        val updater = ServerListUpdater(
            fetch = { null },
            store = store,
            emergencyFetch = { emergencyDataset() },
        )

        val outcome = updater.update()

        assertTrue("the emergency dataset is usable and must be stored", outcome is ServerListUpdater.Outcome.Updated)
        assertEquals(1, store.replaceCount)
        assertEquals(
            "the emergency excerpt must not overwrite the complete snapshot",
            0,
            store.snapshotWrites,
        )
    }

    @Test
    fun `a normal observation still becomes the last-known-good dataset`() = runBlocking {
        val store = FakeStore(storedDataset())
        val candidate = (1..200).map { row("fresh-$it") }
        val updater = ServerListUpdater(fetch = { candidate }, store = store)

        updater.update()

        assertEquals(1, store.snapshotWrites)
        assertEquals(200, store.lastSnapshot.size)
    }

    /**
     * The V9 regression, expressed as the sequence that actually happened:
     * an emergency run, then a run that has to fall back to the snapshot.
     *
     * The snapshot is whatever the last *complete* observation wrote. Because
     * the emergency run no longer writes it, the fallback still replaces the
     * table with a complete dataset — and the 261 -> 170 shrink cannot occur.
     */
    @Test
    fun `an emergency run followed by a snapshot fallback keeps the full dataset`() = runBlocking {
        val store = FakeStore(storedDataset())
        // The last complete observation: the 261 stored rows, already promoted.
        store.saveLastKnownGood(storedDataset())
        store.snapshotWrites = 0
        val completeSnapshot = store.lastSnapshot

        ServerListUpdater(
            fetch = { null },
            store = store,
            emergencyFetch = { emergencyDataset() },
        ).update()

        assertEquals("the emergency run must not touch the snapshot", 0, store.snapshotWrites)
        assertEquals(261, completeSnapshot.size)

        // Now the fallback: the fetch returns the snapshot, flagged as such.
        ServerListUpdater(
            fetch = { completeSnapshot },
            store = store,
            fetchedFromCache = { true },
        ).update()

        assertEquals(
            "a snapshot fallback must still replace — with the COMPLETE snapshot",
            261,
            store.items.size,
        )
    }

    // --------------------------------------------- the integrity gate stands

    @Test
    fun `a degraded emergency file is still refused`() = runBlocking {
        // The device held 103 OpenVPN profiles. An emergency file with none is
        // exactly the truncation `DatasetIntegrity` exists to refuse.
        val store = FakeStore(
            (1..261).map { row("stored-$it", profile = it <= 103) },
        )
        val updater = ServerListUpdater(
            fetch = { null },
            store = store,
            emergencyFetch = { (1..170).map { row("stored-$it") } },
        )

        val outcome = updater.update()

        assertEquals(
            ServerListUpdater.Outcome.Preserved(
                ServerListUpdater.Reason.DEGRADED_DATASET,
                261,
            ),
            outcome,
        )
        assertEquals(0, store.replaceCount)
        assertEquals(0, store.snapshotWrites)
    }

    @Test
    fun `a real cache fallback still replaces and never rewrites the snapshot`() = runBlocking {
        val store = FakeStore((1..50).map { row("old-$it") })
        val cached = (1..200).map { row("cached-$it") }

        val outcome = ServerListUpdater(
            fetch = { cached },
            store = store,
            fetchedFromCache = { true },
        ).update()

        assertTrue(outcome is ServerListUpdater.Outcome.Updated)
        assertEquals(200, store.items.size)
        assertTrue(
            "a fallback must be reported as such, so the UI does not call it a refresh",
            (outcome as ServerListUpdater.Outcome.Updated).sourcedFromCache,
        )
        assertEquals("a fallback is not news and must not be promoted", 0, store.snapshotWrites)
    }
}
