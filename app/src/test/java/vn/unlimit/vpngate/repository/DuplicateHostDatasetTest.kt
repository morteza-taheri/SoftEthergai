package vn.unlimit.vpngate.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.data.model.VpnPortSource
import vn.unlimit.vpngate.models.VPNGateItem

/**
 * Stage 2F-A (E-1): a duplicate Room identity must never cost the user a whole
 * refresh.
 *
 * The table's primary key is `hostName` while the collector merges on IP, so
 * one feed can legitimately carry the same hostname on two addresses. Before
 * Stage 2F-A the duplicate key aborted the whole transactional replacement and
 * was reported as a generic store failure.
 */
class DuplicateHostDatasetTest {

    private fun row(
        host: String,
        ip: String,
        profile: String? = null,
        seTcpPort: Int = 0,
        score: Int = 0,
        speed: Int = 0,
        ping: Int = 0,
    ) = VPNGateItem(
        hostName = host,
        ip = ip,
        score = score,
        ping = ping,
        speed = speed,
        countryLong = "Japan",
        countryShort = "JP",
        numVpnSession = 1,
        uptime = 86_400_000,
        totalUser = 5,
        totalTraffic = 1_000L,
        logType = null,
        operator = null,
        message = null,
        openVpnConfigData = profile,
        tcpPort = 0,
        udpPort = 0,
        isL2TPSupport = false,
        isSSTPSupport = false,
        seTcpPort = seTcpPort,
    )

    /** Filler rows so the dataset clears the absolute integrity floor. */
    private fun filler(from: Int, count: Int): List<VPNGateItem> =
        (0 until count).map { row("filler-$from-$it", "10.9.$from.$it", profile = "profile-$from-$it") }

    /**
     * A store that behaves like Room: the primary key is hostName and a
     * duplicate key raises, exactly as `insertAll` with the default ABORT
     * strategy does.
     */
    private class RoomLikeStore(initial: List<VPNGateItem> = emptyList()) : ServerListUpdater.Store {
        var items: List<VPNGateItem> = initial
            private set
        var snapshot: List<VPNGateItem> = emptyList()
            private set
        var replaceAllCalls = 0
            private set

        /** Stand-in for SQLiteConstraintException, recognised by class name. */
        class ConstraintException(message: String) : RuntimeException(message)

        override fun currentCount(): Int = items.size
        override fun currentOpenVpnConfigCount(): Int = items.count { !it.openVpnConfigData.isNullOrBlank() }
        override fun currentDataset(): List<VPNGateItem> = items

        override fun saveLastKnownGood(items: List<VPNGateItem>): Boolean {
            snapshot = items
            return true
        }

        override fun replaceAll(items: List<VPNGateItem>) {
            replaceAllCalls++
            // Room semantics: one row per hostName, ABORT on a duplicate.
            val seen = mutableSetOf<String>()
            for (item in VPNGateItem.dedupeForStorage(items)) {
                if (!seen.add(item.hostName.trim())) {
                    throw ConstraintException("UNIQUE constraint failed: vpngateitem.hostName")
                }
            }
            this.items = items
        }
    }

    private fun updater(store: RoomLikeStore, fetch: () -> List<VPNGateItem>) =
        ServerListUpdater(fetch = { fetch() }, store = store)

    // ---- A) Same hostname, different IP: update succeeds, no collision ----

    @Test
    fun `the same host name on two addresses does not fail the update`() = runBlocking {
        val store = RoomLikeStore()
        val duplicated = row("vpn.example.opengw.net", "1.2.3.4") to
            row("vpn.example.opengw.net", "5.6.7.8")
        val out = updater(store) { listOf(duplicated.first, duplicated.second) + filler(0, 12) }.update()

        assertTrue(
            "update must not be reported as a failure, was $out",
            out is ServerListUpdater.Outcome.Updated,
        )
        val stored = store.items
        assertEquals(1, stored.count { it.hostName == "vpn.example.opengw.net" })
        assertEquals(13, stored.size)
    }

    @Test
    fun `one host name on two addresses collapses to a single usable row`() {
        val richer = row(
            "vpn.example.opengw.net",
            "5.6.7.8",
            profile = "openvpn-profile",
            seTcpPort = 443,
        )
        val poorer = row("vpn.example.opengw.net", "1.2.3.4")

        // The winner is chosen by connectability, not by input order.
        assertEquals(listOf(richer), VPNGateItem.dedupeForStorage(listOf(poorer, richer)))
        assertEquals(listOf(richer), VPNGateItem.dedupeForStorage(listOf(richer, poorer)))
    }

    @Test
    fun `a true duplicate pair keeps one row and the result does not depend on order`() {
        val a = row("dup.opengw.net", "10.0.0.1", profile = "p", score = 5)
        val b = row("dup.opengw.net", "10.0.0.2", profile = "p", score = 5)

        // Identical connectability and metrics: the smaller address wins, so
        // the choice is stable no matter which order the collector produced.
        assertEquals(
            listOf(a),
            VPNGateItem.dedupeForStorage(listOf(a, b)),
        )
        assertEquals(
            listOf(a),
            VPNGateItem.dedupeForStorage(listOf(b, a)),
        )
    }

    // ---- B) Different hostname, different IP: both survive ----

    @Test
    fun `two different servers on two addresses both survive`() {
        val a = row("a.opengw.net", "1.1.1.1")
        val b = row("b.opengw.net", "2.2.2.2")

        assertEquals(listOf(a, b), VPNGateItem.dedupeForStorage(listOf(a, b)))
    }

    @Test
    fun `distinct servers are all stored`() = runBlocking {
        val store = RoomLikeStore()
        val out = updater(store) { filler(1, 12) }.update()

        assertTrue(out is ServerListUpdater.Outcome.Updated)
        assertEquals(12, store.items.size)
        assertEquals(12, store.items.map { it.hostName }.toSet().size)
    }

    // ---- C) Same hostname + same IP: one deterministic record ----

    @Test
    fun `an exact duplicate collapses to a single row`() {
        val a = row("same.opengw.net", "3.3.3.3")
        val b = a.copy()

        val result = VPNGateItem.dedupeForStorage(listOf(a, b))

        assertEquals(1, result.size)
        assertEquals("same.opengw.net", result.first().hostName)
    }

    // ---- D) Hostname missing: records remain distinct ----

    @Test
    fun `an ip keyed row is not collapsed with a named row`() {
        val named = row("named.opengw.net", "4.4.4.4")
        val ipKeyed = row("4.4.4.4", "4.4.4.4")

        // The mapper falls back to the IP when a record has no host name, so a
        // name-less server is stored under its own address and cannot collide
        // with a named one.
        val result = VPNGateItem.dedupeForStorage(listOf(named, ipKeyed))

        assertEquals(2, result.size)
        assertEquals(setOf("named.opengw.net", "4.4.4.4"), result.map { it.hostName }.toSet())
    }

    @Test
    fun `two name-less rows on different addresses remain distinct`() {
        val a = row("5.5.5.5", "5.5.5.5")
        val b = row("6.6.6.6", "6.6.6.6")

        assertEquals(2, VPNGateItem.dedupeForStorage(listOf(a, b)).size)
    }

    // ---- E) Canonical + mirror duplicate: deterministic, no collision ----

    @Test
    fun `a canonical and a mirror row for one server collapse deterministically`() {
        val fromCanonical = row("shared.opengw.net", "7.7.7.7", seTcpPort = 443)
        val fromMirror = row("shared.opengw.net", "7.7.7.7", seTcpPort = 443)

        val result = VPNGateItem.dedupeForStorage(listOf(fromCanonical, fromMirror))

        assertEquals(1, result.size)
        assertEquals("7.7.7.7", result.first().ip)
    }

    @Test
    fun `a mirror row with different data wins on connectability, not on order`() = runBlocking {
        val store = RoomLikeStore()
        val thin = row("m.opengw.net", "8.8.8.8")
        val rich = row("m.opengw.net", "8.8.8.8", profile = "cfg", seTcpPort = 443)

        val out = updater(store) { listOf(rich, thin) + filler(2, 12) }.update()

        assertTrue(out is ServerListUpdater.Outcome.Updated)
        val stored = store.items.single { it.hostName == "m.opengw.net" }
        assertEquals("cfg", stored.openVpnConfigData)
        assertEquals(443, stored.seTcpPort)
    }

    // ---- The snapshot must not drift from the table ----

    @Test
    fun `the snapshot stores the same deduplicated rows as the table`() = runBlocking {
        val store = RoomLikeStore()
        val dup = row("snap.opengw.net", "9.9.9.1")
        val dup2 = row("snap.opengw.net", "9.9.9.2", profile = "cfg")

        updater(store) { listOf(dup, dup2) + filler(3, 12) }.update()

        assertEquals(
            store.items.map { it.hostName }.sorted(),
            store.snapshot.map { it.hostName }.sorted(),
        )
        assertEquals(1, store.snapshot.count { it.hostName == "snap.opengw.net" })
    }

    // ---- Already-unique datasets are passed through untouched ----

    @Test
    fun `a dataset with no duplicate identity is returned unchanged`() {
        val items = filler(4, 5)
        assertEquals(items, VPNGateItem.dedupeForStorage(items))
        assertEquals(emptyList<VPNGateItem>(), VPNGateItem.dedupeForStorage(emptyList()))
        assertEquals(listOf(items.first()), VPNGateItem.dedupeForStorage(listOf(items.first())))
    }

    // ---- A real conflict is no longer a generic store failure ----

    @Test
    fun `a constraint violation is reported as a store conflict not a disk failure`() {
        assertTrue(ServerListUpdater.isKeyConflict(RoomLikeStore.ConstraintException("UNIQUE")))
        assertTrue(
            ServerListUpdater.isKeyConflict(
                RuntimeException("wrapper", RoomLikeStore.ConstraintException("UNIQUE")),
            ),
        )
        assertTrue(
            "a violation type must be recognised too",
            ServerListUpdater.isKeyConflict(SQLiteConstraintViolation()),
        )
    }

    @Test
    fun `an ordinary failure is not mistaken for a conflict`() {
        assertTrue(!ServerListUpdater.isKeyConflict(RuntimeException("disk I/O error")))
        assertTrue(!ServerListUpdater.isKeyConflict(java.io.IOException("no space left")))
    }

    @Test
    fun `a store that still raises a conflict is reported distinctly`() = runBlocking {
        val exploding = object : ServerListUpdater.Store {
            override fun currentCount(): Int = 0
            override fun currentOpenVpnConfigCount(): Int = 0
            override fun currentDataset(): List<VPNGateItem> = emptyList()
            override fun replaceAll(items: List<VPNGateItem>) =
                throw RoomLikeStore.ConstraintException("UNIQUE constraint failed: vpngateitem.hostName")
        }

        val out = ServerListUpdater(
            fetch = { filler(5, 12) },
            store = exploding,
        ).update()

        assertTrue(out is ServerListUpdater.Outcome.Preserved)
        val preserved = out as ServerListUpdater.Outcome.Preserved
        assertEquals(ServerListUpdater.Reason.STORE_CONFLICT, preserved.reason)
    }

    // ---- The dedupe must not disturb SSTP provenance ----

    @Test
    fun `deduplication preserves sstp port provenance on the surviving row`() {
        val withDefault = row("s.opengw.net", "10.1.1.1").copy(
            isSSTPSupport = true,
            sstpPort = 0,
            sstpPortSource = VpnPortSource.DEFAULT,
        )
        val thin = row("s.opengw.net", "10.1.1.2")

        val winner = VPNGateItem.dedupeForStorage(listOf(thin, withDefault)).single()

        assertEquals(VpnPortSource.DEFAULT, winner.sstpPortSource)
        assertEquals(0, winner.sstpPort)
    }
}

/** A second constraint-shaped type, so the classifier is not name-exact. */
private class SQLiteConstraintViolation : RuntimeException("constraint")
