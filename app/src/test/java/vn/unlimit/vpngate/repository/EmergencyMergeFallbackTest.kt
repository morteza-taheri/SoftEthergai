package vn.unlimit.vpngate.repository

import androidx.sqlite.db.SupportSQLiteQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.db.VPNGateItemDao
import vn.unlimit.vpngate.models.VPNGateItem

/**
 * v1.0.63 Part B: a *supplementary* merge — the path the emergency rung now
 * takes — must not erase a stored OpenVPN profile merely because the incoming
 * row (an excerpt of the feed that the publisher strips to stay under the body
 * cap) lacks one, and it must preserve retention timestamps rather than forge
 * them to `now`.
 *
 * `mergeFallback` is the function [ServerListUpdates] now routes the EMERGENCY
 * rung through: `build()`'s `emergencyFetch` lambda sets the origin flag true,
 * so [ServerListUpdater.Store.replaceAll] (the real `DaoStore`) calls
 * `mergeFallback` instead of `mergeAll`. These tests pin the behaviour of that
 * function directly; the routing itself is verified by reading `update()`
 * (the store reads the origin flag via the same lambda the cache branch uses).
 */
class EmergencyMergeFallbackTest {

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

    private fun item(
        host: String,
        ip: String = "203.0.113.1",
        openVpnConfigData: String? = null,
        firstSeenAt: Long = 0L,
        lastSeenAt: Long = 0L,
        healthScore: Int = 0,
        seUdpSupported: Boolean = false,
    ) = VPNGateItem(
        hostName = host,
        ip = ip,
        countryLong = "Japan",
        countryShort = "JP",
        logType = null,
        operator = null,
        message = null,
        openVpnConfigData = openVpnConfigData,
        firstSeenAt = firstSeenAt,
        lastSeenAt = lastSeenAt,
        healthScore = healthScore,
        seUdpSupported = seUdpSupported,
    )

    @Test
    fun `mergeFallback keeps a stored openvpn profile when the emergency row omits it`() {
        val dao = FakeDao()
        dao.rows = listOf(
            item(
                "vpn-1",
                openVpnConfigData = "client\nproto tcp\nremote 203.0.113.1 443\n",
                firstSeenAt = 100L,
                lastSeenAt = 900L,
                healthScore = 7,
            ),
        )
        // The emergency excerpt for the same host carries no profile.
        val emergency = listOf(item("vpn-1", openVpnConfigData = null))

        mergeFallback(dao, emergency, emptyList(), now = 5_000L)

        val stored = dao.rows.single()
        assertEquals("vpn-1", stored.hostName)
        assertEquals(
            "a stored profile must survive a profile-less emergency row",
            "client\nproto tcp\nremote 203.0.113.1 443\n",
            stored.openVpnConfigData,
        )
    }

    @Test
    fun `mergeFallback lets an incoming profile win when the source provides one`() {
        val dao = FakeDao()
        dao.rows = listOf(item("vpn-1", openVpnConfigData = "STORED-PROFILE"))
        val emergency = listOf(item("vpn-1", openVpnConfigData = "EMERGENCY-PROFILE"))

        mergeFallback(dao, emergency, emptyList(), now = 5_000L)

        // A blank value must not erase a stored one; a real (newer) value does win.
        assertEquals("EMERGENCY-PROFILE", dao.rows.single().openVpnConfigData)
    }

    @Test
    fun `mergeFallback preserves retention timestamps for a stored host`() {
        val dao = FakeDao()
        dao.rows = listOf(
            item("vpn-1", firstSeenAt = 100L, lastSeenAt = 900L, healthScore = 7),
        )
        val emergency = listOf(item("vpn-1"))

        mergeFallback(dao, emergency, emptyList(), now = 5_000L)

        val stored = dao.rows.single()
        // Never forged to `now`; the real observation moments are kept verbatim.
        assertEquals(100L, stored.firstSeenAt)
        assertEquals(900L, stored.lastSeenAt)
        assertEquals(7, stored.healthScore)
    }

    @Test
    fun `mergeFallback initialises a genuinely new host at now`() {
        val dao = FakeDao()
        dao.rows = listOf(item("stored-1", firstSeenAt = 100L, lastSeenAt = 900L))

        mergeFallback(dao, listOf(item("new-1")), emptyList(), now = 5_000L)

        val byHost = dao.rows.associateBy { it.hostName }
        assertEquals(5_000L, byHost.getValue("new-1").firstSeenAt)
        assertEquals(5_000L, byHost.getValue("new-1").lastSeenAt)
        // The previously stored host is retained unchanged.
        assertEquals(100L, byHost.getValue("stored-1").firstSeenAt)
    }

    @Test
    fun `mergeFallback retains stored hosts the emergency excerpt omits`() {
        val dao = FakeDao()
        dao.rows = listOf(item("stored-only"), item("overlap"))
        val emergency = listOf(item("overlap"), item("emergency-only"))

        mergeFallback(dao, emergency, emptyList(), now = 5_000L)

        val hosts = dao.rows.map { it.hostName }.toSet()
        assertTrue(hosts.contains("stored-only"))
        assertTrue(hosts.contains("overlap"))
        assertTrue(hosts.contains("emergency-only"))
    }

    @Test
    fun `mergeFallback keeps a manual row the emergency feed does not list`() {
        val dao = FakeDao()
        dao.rows = listOf(item("collected-1"))
        val manual = item("my-server.example", ip = "198.51.100.7")

        mergeFallback(dao, listOf(item("collected-1")), listOf(manual), now = 5_000L)

        assertTrue(
            "a user-owned row must never be pruned by a supplementary source",
            dao.rows.any { it.hostName == "my-server.example" },
        )
    }

    @Test
    fun `mergeFallback carries the emergency SE UDP support when present`() {
        // When the emergency CSV does declare support, the value is a real fact
        // and rides through to the merged row (contrast: a profile-less row must
        // not erase a stored profile — that is the test above).
        val dao = FakeDao()
        dao.rows = listOf(item("vpn-1", seUdpSupported = false))
        val emergency = listOf(item("vpn-1", seUdpSupported = true))

        mergeFallback(dao, emergency, emptyList(), now = 5_000L)

        assertEquals(true, dao.rows.single().seUdpSupported)
    }
}
