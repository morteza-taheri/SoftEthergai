package vn.unlimit.vpngate.repository

import androidx.sqlite.db.SupportSQLiteQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.data.model.VpnPortSource
import vn.unlimit.vpngate.db.VPNGateItemDao
import vn.unlimit.vpngate.models.VPNGateItem

/**
 * REQ-RET-001 (v1.0.70) — the approved 500-server retention cap.
 *
 * The cap is exercised through the pure [capRetainedServers] helper and through
 * [mergeAll] with an in-memory DAO. No Android/Room runtime is required.
 *
 * Priority order under test: **quality (`healthScore`) → protocol breadth →
 * successful-connection history → recency**, with a deterministic host-name
 * tie-break. Manual rows are never pruned.
 */
class ServerRetentionCapTest {

    private class FakeDao(initial: List<VPNGateItem> = emptyList()) : VPNGateItemDao {
        var rows: List<VPNGateItem> = initial
        var replaceCount = 0

        override fun getAll(): List<VPNGateItem> = rows
        override fun replaceAll(items: List<VPNGateItem>) {
            rows = VPNGateItem.dedupeForStorage(items)
            replaceCount++
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
        health: Int = 0,
        lastSeen: Long = 0L,
        profile: String? = null,
        seTcp: Int = 0,
        seUdpSupported: Boolean = false,
        sstp: Boolean = false,
        sstpPort: Int = 0,
        l2tp: Boolean = false,
    ): VPNGateItem = VPNGateItem(
        hostName = host,
        ip = "203.0.113.1",
        score = 1,
        ping = 1,
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
        sstpPort = sstpPort,
        sstpPortSource = VpnPortSource.UNKNOWN,
        seTcpPort = seTcp,
        seUdpPort = 0,
        seUdpSupported = seUdpSupported,
        firstSeenAt = 0L,
        lastSeenAt = lastSeen,
        healthScore = health,
    )

    // ── size boundaries ───────────────────────────────────────────────────

    @Test
    fun `a dataset below the cap is returned unchanged`() {
        val rows = (1..10).map { item("h$it", health = it) }
        assertEquals(rows, capRetainedServers(rows, emptySet()))
    }

    @Test
    fun `exactly 500 is returned unchanged`() {
        val rows = (1..500).map { item("h$it", health = it) }
        assertEquals(500, capRetainedServers(rows, emptySet()).size)
    }

    @Test
    fun `more than 500 keeps the 500 highest-quality rows`() {
        val rows = (1..600).map { item("h$it", health = it) }
        val kept = capRetainedServers(rows, emptySet())

        assertEquals(500, kept.size)
        assertEquals(
            "the 100 lowest-health rows must be dropped",
            (101..600).map { "h$it" }.toSet(),
            kept.map { it.hostName }.toSet(),
        )
    }

    // ── priority order ────────────────────────────────────────────────────

    @Test
    fun `protocol breadth breaks a quality tie`() {
        val narrow = item("narrow", health = 10, seTcp = 443)
        val broad = item("broad", health = 10, seTcp = 443, seUdpSupported = true, sstp = true, sstpPort = 443, l2tp = true)

        val kept = capRetainedServers(listOf(narrow, broad), emptySet(), cap = 1)

        assertEquals(listOf("broad"), kept.map { it.hostName })
    }

    @Test
    fun `successful-connection history breaks a quality and breadth tie`() {
        val a = item("a", health = 10, seTcp = 443)
        val b = item("b", health = 10, seTcp = 443)

        val kept = capRetainedServers(
            listOf(a, b),
            emptySet(),
            successCounts = mapOf("a" to 7, "b" to 0),
            cap = 1,
        )

        assertEquals(listOf("a"), kept.map { it.hostName })
    }

    @Test
    fun `recency breaks the remaining tie`() {
        val old = item("old", health = 10, seTcp = 443, lastSeen = 1_000L)
        val recent = item("recent", health = 10, seTcp = 443, lastSeen = 9_000L)

        val kept = capRetainedServers(listOf(old, recent), emptySet(), cap = 1)

        assertEquals(listOf("recent"), kept.map { it.hostName })
    }

    @Test
    fun `a full tie is broken deterministically by host name`() {
        val rows = (1..600).map { item("h%03d".format(it), health = 5) }

        val first = capRetainedServers(rows, emptySet()).map { it.hostName }
        val second = capRetainedServers(rows.shuffled(), emptySet()).map { it.hostName }

        assertEquals("the same input set must produce the same kept set", first.toSet(), second.toSet())
        assertEquals(500, first.size)
    }

    // ── manual records ────────────────────────────────────────────────────

    @Test
    fun `manual rows are never pruned`() {
        val network = (1..600).map { item("n$it", health = it) }
        val manual = listOf(item("manual-a"), item("manual-b"), item("manual-c"))
        val manualHosts = manual.map { it.hostName }.toSet()

        val kept = capRetainedServers(network + manual, manualHosts)

        assertEquals(500, kept.size)
        for (host in manualHosts) {
            assertTrue("manual row $host must survive the cap", kept.any { it.hostName == host })
        }
        assertEquals("497 network rows fill the remaining room", 497, kept.count { it.hostName !in manualHosts })
    }

    @Test
    fun `manual rows alone above the cap are all kept`() {
        // No requirement permits deleting user-supplied records to satisfy the
        // cap; this is the documented deviation.
        val manual = (1..501).map { item("m$it") }
        val kept = capRetainedServers(manual, manual.map { it.hostName }.toSet())

        assertEquals(501, kept.size)
    }

    // ── merge path ────────────────────────────────────────────────────────

    @Test
    fun `mergeAll caps the retained union of stored and incoming rows`() {
        val stored = (1..400).map { item("s$it", health = it) }
        val incoming = (1..400).map { item("i$it", health = it) }
        val dao = FakeDao(stored)

        mergeAll(dao, incoming, manualRows = emptyList(), now = 1_000L)

        assertEquals(500, dao.rows.size)
        assertEquals(1, dao.replaceCount)
    }

    @Test
    fun `mergeAll keeps quiet stored rows when the union stays under the cap`() {
        val stored = (1..10).map { item("s$it") }
        val incoming = (1..10).map { item("i$it") }
        val dao = FakeDao(stored)

        mergeAll(dao, incoming, manualRows = emptyList(), now = 1_000L)

        assertEquals(20, dao.rows.size)
        assertTrue("a quiet stored row must be retained", dao.rows.any { it.hostName == "s1" })
        assertEquals(
            "no incoming row may be lost",
            10,
            dao.rows.count { it.hostName.startsWith("i") },
        )
    }
}
