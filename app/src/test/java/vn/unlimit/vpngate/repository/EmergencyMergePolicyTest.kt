package vn.unlimit.vpngate.repository

import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.db.VPNGateItemDao
import vn.unlimit.vpngate.models.VPNGateItem
import vn.unlimit.vpngate.repository.DatasetIntegrity
import vn.unlimit.vpngate.repository.SourceCapability

/**
 * v1.0.63 §4 — field-level supplementary-merge policy, exercised directly
 * against the real [mergeFallback] / [mergeAll] functions (the same functions
 * the production `DaoStore` delegates to) and against `DatasetIntegrity`.
 *
 * The field policy established in the v1.0.63 spec:
 *  - OpenVPN config: a blank incoming value is "not provided" (the publisher
 *    strips profiles), so a stored profile is kept. A provided profile wins.
 *  - Quality (score/ping/speed/uptime/sessions/traffic): for a *matched* host
 *    the stored measurement is preserved (§2.4 — the supplement is not a fresh
 *    live observation); a new host takes the incoming value.
 *  - Protocol/port facts (SoftEther/SSTP/L2TP/OpenVPN ports, support flags):
 *    the §10.2 emergency CSV always carries these columns, so the incoming
 *    value is authoritative and wins (no "absent" signal exists for them).
 *  - Retention (firstSeenAt/lastSeenAt/healthScore): preserved for a matched
 *    host, initialised at `now` only for a genuinely new host.
 */
class EmergencyMergePolicyTest {

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
        score: Int = 0,
        ping: Int = 0,
        speed: Int = 0,
        numVpnSession: Int = 0,
        uptime: Int = 0,
        totalUser: Int = 0,
        totalTraffic: Long = 0,
        seUdpSupported: Boolean = false,
        seUdpPort: Int = 0,
        seTcpPort: Int = 0,
        isSstpSupport: Boolean = false,
        sstpPort: Int = 0,
        isL2tpSupport: Boolean = false,
        firstSeenAt: Long = 0L,
        lastSeenAt: Long = 0L,
        healthScore: Int = 0,
    ) = VPNGateItem(
        hostName = host,
        ip = ip,
        countryLong = "Japan",
        countryShort = "JP",
        logType = null,
        operator = null,
        message = null,
        openVpnConfigData = openVpnConfigData,
        score = score,
        ping = ping,
        speed = speed,
        numVpnSession = numVpnSession,
        uptime = uptime,
        totalUser = totalUser,
        totalTraffic = totalTraffic,
        seUdpSupported = seUdpSupported,
        seUdpPort = seUdpPort,
        seTcpPort = seTcpPort,
        isSSTPSupport = isSstpSupport,
        sstpPort = sstpPort,
        isL2TPSupport = isL2tpSupport,
        firstSeenAt = firstSeenAt,
        lastSeenAt = lastSeenAt,
        healthScore = healthScore,
    )

    // ── §4.1 OpenVPN configuration ────────────────────────────────────────

    @Test
    fun `stored openvpn profile preserved when the emergency row omits it`() {
        val dao = FakeDao()
        dao.rows = listOf(
            item("vpn-1", openVpnConfigData = "PROFILE-A", firstSeenAt = 100, lastSeenAt = 900, healthScore = 7),
        )
        mergeFallback(dao, listOf(item("vpn-1", openVpnConfigData = null)), emptyList(), now = 5000)
        assertEquals("PROFILE-A", dao.rows.single().openVpnConfigData)
    }

    @Test
    fun `incoming openvpn profile wins when the source actually provides one`() {
        val dao = FakeDao()
        dao.rows = listOf(item("vpn-1", openVpnConfigData = "STORED"))
        mergeFallback(dao, listOf(item("vpn-1", openVpnConfigData = "EMERGENCY")), emptyList(), now = 5000)
        assertEquals("EMERGENCY", dao.rows.single().openVpnConfigData)
    }

    // ── §4.2 SoftEther UDP support and port ────────────────────────────────

    @Test
    fun `stored seUdpSupported updated by an emergency declaration without a port`() {
        // The emergency CSV carries SE_UDP_Supported; the incoming value is
        // authoritative. Part A guarantees no port is fabricated when it is
        // absent, so this is a clean support-only update.
        val dao = FakeDao()
        dao.rows = listOf(item("vpn-1", seUdpSupported = false))
        mergeFallback(dao, listOf(item("vpn-1", seUdpSupported = true, seUdpPort = 0)), emptyList(), now = 5000)
        assertTrue(dao.rows.single().seUdpSupported)
        assertEquals(0, dao.rows.single().seUdpPort)
    }

    @Test
    fun `a stored seUdpSupported stays true when the emergency declares it true with no port`() {
        val dao = FakeDao()
        dao.rows = listOf(item("vpn-1", seUdpSupported = true))
        mergeFallback(dao, listOf(item("vpn-1", seUdpSupported = true, seUdpPort = 0)), emptyList(), now = 5000)
        assertTrue(dao.rows.single().seUdpSupported)
    }

    // ── §4.3 SSTP / L2TP support and ports ────────────────────────────────

    @Test
    fun `stored sstp support and port preserved when the emergency agrees`() {
        // The §10.2 schema always publishes SSTP_Supported / SSTP_Port, so the
        // realistic "no data loss" case is the emergency echoing the same facts.
        val dao = FakeDao()
        dao.rows = listOf(item("vpn-1", isSstpSupport = true, sstpPort = 443))
        mergeFallback(dao, listOf(item("vpn-1", isSstpSupport = true, sstpPort = 443)), emptyList(), now = 5000)
        val r = dao.rows.single()
        assertTrue(r.isSSTPSupport)
        assertEquals(443, r.sstpPort)
    }

    @Test
    fun `stored l2tp support preserved through a fallback merge`() {
        val dao = FakeDao()
        dao.rows = listOf(item("vpn-1", isL2tpSupport = true))
        mergeFallback(dao, listOf(item("vpn-1", isL2tpSupport = true)), emptyList(), now = 5000)
        assertTrue(dao.rows.single().isL2TPSupport)
    }

    // ── §4.4 incoming valid fact fills a missing stored value ──────────────

    @Test
    fun `incoming valid sstp port fills a previously missing stored value`() {
        val dao = FakeDao()
        dao.rows = listOf(item("vpn-1", isSstpSupport = false, sstpPort = 0))
        mergeFallback(dao, listOf(item("vpn-1", isSstpSupport = true, sstpPort = 992)), emptyList(), now = 5000)
        val r = dao.rows.single()
        assertTrue(r.isSSTPSupport)
        assertEquals(992, r.sstpPort)
    }

    // ── §2.4 / §4.6 stale quality values preserved for an existing host ───

    @Test
    fun `matched host keeps stored quality, not the emergency excerpt`() {
        val dao = FakeDao()
        dao.rows = listOf(
            item(
                "vpn-1",
                score = 500, ping = 10, speed = 1, uptime = 2,
                numVpnSession = 3, totalTraffic = 4L,
                firstSeenAt = 100, lastSeenAt = 900, healthScore = 7,
            ),
        )
        val emergency = listOf(
            item("vpn-1", score = 999, ping = 99, speed = 8, uptime = 7, numVpnSession = 6, totalTraffic = 5L),
        )
        mergeFallback(dao, emergency, emptyList(), now = 5000)
        val r = dao.rows.single()
        // Quality must come from STORED, never the incoming excerpt (§2.4).
        assertEquals(500, r.score)
        assertEquals(10, r.ping)
        assertEquals(1, r.speed)
        assertEquals(2, r.uptime)
        assertEquals(3, r.numVpnSession)
        assertEquals(4L, r.totalTraffic)
        // Retention also preserved.
        assertEquals(900, r.lastSeenAt)
        assertEquals(7, r.healthScore)
    }

    // ── §4.7 incoming quality used for a new emergency-only host ──────────

    @Test
    fun `new emergency-only host takes incoming quality and is initialised now`() {
        val dao = FakeDao()
        mergeFallback(dao, listOf(item("new-1", score = 777, ping = 5)), emptyList(), now = 5000)
        val r = dao.rows.single()
        assertEquals(777, r.score)
        assertEquals(5000, r.firstSeenAt)
        assertEquals(5000, r.lastSeenAt)
    }

    // ── §4.8 / §4.9 retention and manual rows ─────────────────────────────

    @Test
    fun `server omitted from the emergency feed remains stored`() {
        val dao = FakeDao()
        dao.rows = listOf(item("stored-only"), item("overlap"))
        mergeFallback(dao, listOf(item("overlap")), emptyList(), now = 5000)
        val hosts = dao.rows.map { it.hostName }.toSet()
        assertTrue(hosts.contains("stored-only"))
        assertTrue(hosts.contains("overlap"))
    }

    @Test
    fun `manual rows preserved through a fallback merge`() {
        val dao = FakeDao()
        dao.rows = listOf(item("collected-1"))
        val manual = item("my-server.example", ip = "198.51.100.7")
        mergeFallback(dao, listOf(item("collected-1")), listOf(manual), now = 5000)
        assertTrue(dao.rows.any { it.hostName == "my-server.example" })
    }

    // ── §4.10 no duplicate identities after merging ───────────────────────

    @Test
    fun `no duplicate host identities after merging overlapping rows`() {
        val dao = FakeDao()
        dao.rows = listOf(item("shared"), item("only-stored"))
        val emergency = listOf(
            item("shared", ip = "203.0.113.10"),
            item("shared", ip = "203.0.113.11"),
            item("only-emergency"),
        )
        mergeFallback(dao, emergency, emptyList(), now = 5000)
        val hosts = dao.rows.map { it.hostName }
        assertEquals(hosts.size, hosts.toSet().size)
        assertTrue(hosts.contains("shared"))
        assertTrue(hosts.contains("only-stored"))
        assertTrue(hosts.contains("only-emergency"))
    }

    // ── §4.11 DatasetIntegrity still rejects a degraded dataset ───────────

    @Test
    fun `DatasetIntegrity rejects a degraded emergency excerpt vs a healthy store`() {
        val stored = (1..261).map { item("stored-$it") }
        val emergency = (1..10).map { item("emg-$it") }
        val verdict = DatasetIntegrity.evaluate(emergency, stored, SourceCapability.UNKNOWN)
        assertFalse("a 10-row excerpt must be refused against a 261-row store", verdict.accepted)
    }

    // ── §4.12 primary refresh behavior unchanged ──────────────────────────

    @Test
    fun `primary mergeAll refreshes lastSeenAt and takes incoming quality`() {
        val dao = FakeDao()
        dao.rows = listOf(
            item("vpn-1", score = 500, lastSeenAt = 900, firstSeenAt = 100, healthScore = 7),
        )
        mergeAll(dao, listOf(item("vpn-1", score = 999)), emptyList(), now = 5000)
        val r = dao.rows.single()
        assertEquals(999, r.score)
        assertEquals(5000, r.lastSeenAt)
    }

    // ── §3 origin cross-run regression ────────────────────────────────────

    @Test
    fun `emergency then primary through one updater instance does not leak origin`() = runBlocking {
        // Both refreshes use a 12-row dataset so they clear DatasetIntegrity's
        // MIN_ACCEPTABLE_DATASET_SIZE floor and are actually written.
        val hosts = (1..12).map { "stored-$it" }
        val dao = FakeDao()
        dao.rows = hosts.map {
            item(it, score = 500, lastSeenAt = 900, firstSeenAt = 100, healthScore = 7)
        }
        val NOW = 5000L

        // Faithful replica of ServerListUpdates.DaoStore routing: fallback ->
        // mergeFallback, network -> mergeAll. Uses the real internal merge
        // functions so the routing decision is exercised end-to-end.
        var origin = false
        val store = object : ServerListUpdater.Store {
            override fun currentCount(): Int = dao.count()
            override fun currentOpenVpnConfigCount(): Int = dao.countWithOpenVpnConfig()
            override fun currentDataset(): List<VPNGateItem> = dao.getAll()
            override fun replaceAll(items: List<VPNGateItem>) {
                if (origin) mergeFallback(dao, items, emptyList(), NOW)
                else mergeAll(dao, items, emptyList(), NOW)
            }

            override fun saveLastKnownGood(items: List<VPNGateItem>): Boolean = true
        }

        var run = 0
        val updater = ServerListUpdater(
            fetch = {
                // Mirror ServerListUpdates.build(): clear any stale origin at the
                // start of every fetch (the v1.0.63 §3 fix).
                origin = false
                when (++run) {
                    1 -> null // primary fails -> emergency rung
                    else -> hosts.map { item(it, score = if (it == "stored-1") 999 else 500) }
                }
            },
            store = store,
            fetchedFromCache = { origin },
            emergencyFetch = {
                origin = true
                hosts.map { item(it, openVpnConfigData = null) }
            },
        )

        updater.update()

        // Run 1: emergency rung -> mergeFallback -> lastSeenAt preserved,
        // quality preserved from stored (§2.4).
        var r = dao.rows.first { it.hostName == "stored-1" }
        assertEquals("run 1 must preserve stored lastSeenAt (mergeFallback)", 900, r.lastSeenAt)
        assertEquals("run 1 must not overwrite stored quality (§2.4)", 500, r.score)

        updater.update()

        // Run 2: primary succeeds -> mergeAll -> lastSeenAt refreshed to now,
        // no emergency-origin leaked from run 1.
        r = dao.rows.first { it.hostName == "stored-1" }
        assertEquals("run 2 must refresh lastSeenAt (mergeAll, no origin leak)", NOW, r.lastSeenAt)
        assertEquals("run 2 primary takes incoming quality", 999, r.score)
    }
}
