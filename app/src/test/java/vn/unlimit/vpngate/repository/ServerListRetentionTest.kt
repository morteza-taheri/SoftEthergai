package vn.unlimit.vpngate.repository

import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.data.model.VpnPortSource
import vn.unlimit.vpngate.db.VPNGateItemDao
import vn.unlimit.vpngate.models.VPNGateItem

/**
 * RETENTION-IMPL-PHASE-1 (spec §1.6.31): the add-only merge of Cumulative
 * Server Retention, exercised directly through [mergeAll] with an in-memory
 * fake DAO. No Android / Room runtime is required.
 */
class ServerListRetentionTest {

    /** Minimal in-memory VPNGateItemDao for merge assertions. */
    private class FakeDao : VPNGateItemDao {
        var rows: List<VPNGateItem> = emptyList()

        override fun getAll(): List<VPNGateItem> = rows

        override fun replaceAll(items: List<VPNGateItem>) {
            // Mirror the real @Transaction replaceAll: dedupe by Room identity,
            // then swap the whole table.
            rows = VPNGateItem.dedupeForStorage(items)
        }

        override fun filterAndSort(query: SupportSQLiteQuery): List<VPNGateItem> = emptyList()
        override fun insertAll(vararg vpnGateItem: VPNGateItem) {}
        override fun deleteAll() {}
        override fun count(): Int = rows.size
        override fun countWithOpenVpnConfig(): Int =
            rows.count { !it.openVpnConfigData.isNullOrBlank() }
    }

    private fun item(
        host: String,
        ip: String = "203.0.113.1",
        score: Int = 1,
        ping: Int = 1,
        profile: String? = "ovpn",
        seTcp: Int = 0,
        seUdpSupported: Boolean = false,
        sstp: Boolean = false,
        l2tp: Boolean = false,
        firstSeen: Long = 0L,
        lastSeen: Long = 0L,
        health: Int = 0,
    ): VPNGateItem = VPNGateItem(
        hostName = host,
        ip = ip,
        score = score,
        ping = ping,
        speed = 1,
        countryLong = "Japan",
        countryShort = "JP",
        numVpnSession = 1,
        uptime = 1,
        totalUser = 1,
        totalTraffic = 1L,
        logType = "dummy",
        operator = "dummy",
        message = null,
        openVpnConfigData = profile,
        tcpPort = if (profile != null) 443 else 0,
        udpPort = 0,
        isL2TPSupport = l2tp,
        isSSTPSupport = sstp,
        sstpPort = 0,
        sstpPortSource = VpnPortSource.UNKNOWN,
        seTcpPort = seTcp,
        seUdpPort = 0,
        seUdpSupported = seUdpSupported,
        firstSeenAt = firstSeen,
        lastSeenAt = lastSeen,
        healthScore = health,
    )

    @Test
    fun `T-NEW-23 new server gets firstSeenAt now and a quiet stored server is retained`() {
        val dao = FakeDao()
        dao.rows = listOf(item("old.vpngate.net", firstSeen = 1000L, lastSeen = 1000L))
        val now = 5_000L
        mergeAll(dao, listOf(item("new.vpngate.net")), emptyList(), now)

        val byHost = dao.rows.associateBy { it.hostName }
        assertEquals(2, dao.rows.size)
        // New server: firstSeenAt == now, lastSeenAt == now.
        val newRow = byHost["new.vpngate.net"]!!
        assertEquals(now, newRow.firstSeenAt)
        assertEquals(now, newRow.lastSeenAt)
        // Quiet stored server retained unchanged.
        val oldRow = byHost["old.vpngate.net"]!!
        assertEquals(1000L, oldRow.firstSeenAt)
        assertEquals(1000L, oldRow.lastSeenAt)
    }

    @Test
    fun `T-NEW-24 returning server keeps firstSeenAt and refreshes lastSeenAt`() {
        val dao = FakeDao()
        dao.rows = listOf(item("a.vpngate.net", firstSeen = 1000L, lastSeen = 2000L))
        val now = 9_000L
        mergeAll(dao, listOf(item("a.vpngate.net")), emptyList(), now)

        assertEquals(1, dao.rows.size)
        val row = dao.rows[0]
        assertEquals(1000L, row.firstSeenAt) // preserved
        assertEquals(now, row.lastSeenAt) // refreshed
    }

    @Test
    fun `T-NEW-25 phase-1 health score ranks connectability`() {
        val dao = FakeDao()
        val now = 1_000L
        // Brief STEP 5 formula: 0 if lastSeenAt == 0 (reserved sentinel; an
        // incoming row is always stamped `now`, so it never lands here);
        // 100 if score > 0 && ping > 0; 50 if score > 0 || ping > 0;
        // 25 otherwise.
        mergeAll(
            dao,
            listOf(
                item("p100", score = 1, ping = 1), // both axes -> 100
                item("p50a", score = 1, ping = 0), // score only -> 50
                item("p50b", score = 0, ping = 1), // ping only -> 50
                item("p25", score = 0, ping = 0), // neither -> 25
            ),
            emptyList(),
            now,
        )
        val byHost = dao.rows.associateBy { it.hostName }
        assertEquals(100, byHost["p100"]!!.healthScore)
        assertEquals(50, byHost["p50a"]!!.healthScore)
        assertEquals(50, byHost["p50b"]!!.healthScore)
        assertEquals(25, byHost["p25"]!!.healthScore)
    }

    @Test
    fun `T-NEW-25b the 0 health sentinel is reserved for never-refreshed rows`() {
        // A pre-phase-1 row that has never been observed by a merge keeps
        // lastSeenAt == 0 and is retained (not recomputed) by `mergeAll`, so
        // its healthScore carries forward at 0 — the brief's reserved sentinel.
        val dao = FakeDao()
        dao.rows = listOf(item("legacy.vpngate.net", firstSeen = 0L, lastSeen = 0L, health = 0))
        val now = 1_000L
        mergeAll(dao, listOf(item("feed.vpngate.net")), emptyList(), now)

        val legacy = dao.rows.first { it.hostName == "legacy.vpngate.net" }
        assertEquals(0L, legacy.lastSeenAt)
        assertEquals(0, legacy.healthScore)
    }

    @Test
    fun `T-NEW-26 manual servers survive the merge overlay`() {
        val dao = FakeDao()
        dao.rows = listOf(item("stored.vpngate.net"))
        val now = 1_000L
        val manual = item("manual.vpngate.net", ip = "198.51.100.7", profile = "ovpn", seTcp = 443)
        mergeAll(dao, listOf(item("feed.vpngate.net")), listOf(manual), now)

        val hosts = dao.rows.map { it.hostName }.toSet()
        assertTrue(hosts.contains("manual.vpngate.net")) // manual survived
        assertTrue(hosts.contains("feed.vpngate.net")) // feed present
        assertTrue(hosts.contains("stored.vpngate.net")) // quiet stored retained
        assertEquals(3, dao.rows.size)
    }

    @Test
    fun `T-NEW-27 the merge is additive so a quiet server is never dropped`() {
        val dao = FakeDao()
        // Stored has three servers.
        dao.rows = listOf(
            item("keep1.vpngate.net", firstSeen = 100L, lastSeen = 100L),
            item("keep2.vpngate.net", firstSeen = 200L, lastSeen = 200L),
            item("drop.vpngate.net", firstSeen = 300L, lastSeen = 300L),
        )
        val now = 9_999L
        // Incoming feed mentions only "drop" (updating it) plus two brand-new.
        mergeAll(
            dao,
            listOf(
                item("drop.vpngate.net"),
                item("brandnew1.vpngate.net"),
                item("brandnew2.vpngate.net"),
            ),
            emptyList(),
            now,
        )
        val hosts = dao.rows.map { it.hostName }.toSet()
        // The two quiet servers that the feed omitted are retained.
        assertTrue(hosts.contains("keep1.vpngate.net"))
        assertTrue(hosts.contains("keep2.vpngate.net"))
        // The updated one keeps its original firstSeenAt.
        assertEquals(100L, dao.rows.first { it.hostName == "keep1.vpngate.net" }.firstSeenAt)
        // 3 stored + 2 new = 5 rows; nothing pruned in phase 1.
        assertEquals(5, dao.rows.size)
    }
}
