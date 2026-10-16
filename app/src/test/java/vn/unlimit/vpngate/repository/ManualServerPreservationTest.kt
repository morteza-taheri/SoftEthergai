package vn.unlimit.vpngate.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.data.model.VpnProtocolNames
import vn.unlimit.vpngate.data.model.VpnRecords
import vn.unlimit.vpngate.data.model.VpnServerRecord
import vn.unlimit.vpngate.data.remote.ManualServer
import vn.unlimit.vpngate.data.remote.ManualServerEntity
import vn.unlimit.vpngate.data.remote.ManualServerDao
import vn.unlimit.vpngate.merger.VpnServerMerger
import vn.unlimit.vpngate.models.VPNGateItem

/**
 * Z1-IMPL-D (Roadmap §12.5.2 item D): the MANUAL-preservation invariant.
 *
 * Spec §11.11 requires that "manual servers must survive ordinary online
 * server-list updates" and that a manually entered record "must not be
 * silently overwritten by any source refresh";
 * `SERVER_SOURCE_ARCHITECTURE.md` §4.3 requires that manual records are
 * "re-emitted on each merge as long as they remain valid".
 *
 * The frozen merge policy is the opposite of precedence: across sources
 * "canonical (api/html) always wins over emergency over manual" (arch doc
 * §3.3) and a manual record is "never promoted above canonical" (§4.2).
 * Preservation and precedence are therefore tested separately here, because
 * they are different promises:
 *
 *  - **Preservation** — the user's row is never deleted. Tested against the
 *    production write boundary, [withManualRows], which is the one place the
 *    stored dataset is composed.
 *  - **Precedence** — a value both sides claim goes to the higher-authority
 *    source, and the disagreement is recorded rather than discarded. Tested
 *    against [VpnServerMerger], the record-layer merge.
 *
 * Everything here is pure JVM: an in-memory [ManualServerDao] fake, no
 * Android, no Room, no network.
 */
class ManualServerPreservationTest {

    // ---------------------------------------------------------------- fakes

    /**
     * An in-memory stand-in for the generated DAO. It implements the
     * interface directly rather than through Room, so the table's real
     * constraints (the unique index on host+port+protocol, the
     * autoGenerate primary key) are what this class mimics by hand — which
     * is exactly the behaviour the store relies on.
     */
    private class FakeManualServerDao : ManualServerDao {
        private val rows = LinkedHashMap<Long, ManualServerEntity>()
        private var nextId = 1L

        override fun observeAll(): Flow<List<ManualServerEntity>> =
            flowOf(rows.values.toList())

        override suspend fun listAll(): List<ManualServerEntity> = rows.values.toList()

        override suspend fun listEnabled(): List<ManualServerEntity> =
            rows.values.filter { it.enabled }

        override suspend fun findByKey(
            host: String,
            port: Int,
            protocol: String,
        ): ManualServerEntity? = rows.values.firstOrNull {
            it.host == host && it.port == port && it.protocol == protocol
        }

        override suspend fun upsert(item: ManualServerEntity): Long {
            val id = if (item.id == 0L) nextId++ else item.id
            rows[id] = item.copy(id = id)
            return id
        }

        override suspend fun deleteById(id: Long) {
            rows.remove(id)
        }
    }

    private val host = "203.0.113.7"

    /** Network hosts that are never the manual server's address. */
    private val otherHost = "198.51.100.4"
    private val secondOtherHost = "192.0.2.9"

    private fun row(hostName: String): VPNGateItem = VPNGateItem(
        hostName = hostName,
        ip = hostName,
        countryLong = null,
        countryShort = null,
        logType = null,
        operator = null,
        message = null,
        openVpnConfigData = null,
    )

    /** A network dataset. It never contains [host] unless asked to. */
    private fun networkRows(vararg extraHosts: String): List<VPNGateItem> =
        listOf(otherHost, *extraHosts).map { row(it) }

    /** A collected row for [host], so the two datasets collide on it. */
    private fun collidingNetworkRow(): VPNGateItem =
        row(host).copy(isSSTPSupport = true, sstpPort = 443)

    /** One canonical record for [host] advertising MS-SSTP on [sstpPort]. */
    private fun sstpRecord(ip: String, sstpPort: Int, source: String): VpnServerRecord {
        val record = VpnRecords.newServer()
        VpnRecords.setField(record, "identity.ip", ip, source)
        VpnRecords.setField(record, "identity.hostname", "host-$ip", source)
        VpnRecords.setField(record, "protocols.sstp.supported", true, source)
        VpnRecords.setField(record, "protocols.sstp.port", sstpPort, source)
        VpnRecords.markSupported(record, VpnProtocolNames.SSTP, source)
        VpnRecords.sourceAdd(record, source)
        return record
    }

    // ------------------------------------------------- 1. the no-op invariant

    @Test
    fun `no enabled manual server leaves the network dataset untouched`() = runBlocking {
        val store = ManualServerStore(FakeManualServerDao())
        val network = networkRows("198.51.100.4")

        val manual = store.toItems(store.listEnabled())
        assertTrue("an untouched store has no manual rows", manual.isEmpty())

        // The strongest available proof of "zero behaviour change": the
        // caller's own list instance is handed straight back, so not even
        // its order or its element identities differ.
        assertSame(network, withManualRows(network, manual))
    }

    @Test
    fun `a disabled manual server behaves exactly like none`() = runBlocking {
        val dao = FakeManualServerDao()
        val store = ManualServerStore(dao)
        assertTrue(store.add(host, "443", VpnProtocolNames.SSTP, null, null))

        val id = store.list().single().id
        store.setEnabled(id, false)

        val manual = store.toItems(store.listEnabled())
        assertTrue(manual.isEmpty())
        // The row is kept — the user's address and notes are theirs — it
        // simply stops taking part in the server list.
        assertEquals(1, store.list().size)

        val network = networkRows()
        assertSame(network, withManualRows(network, manual))
    }

    // --------------------------------------------------- 2. preservation

    @Test
    fun `an enabled manual server survives a refresh that replaces the list`() = runBlocking {
        val dao = FakeManualServerDao()
        val store = ManualServerStore(dao)
        assertTrue(store.add(host, "443", VpnProtocolNames.SSTP, "Home", null))

        val manual = store.toItems(store.listEnabled())
        val stored = withManualRows(networkRows(secondOtherHost), manual)

        assertEquals(3, stored.size)
        val mine = stored.single { it.hostName == host }
        assertTrue(mine.isSSTPSupport)
        assertEquals(443, mine.sstpPort)
        assertEquals("Home", mine.operator)
        // A crawled row's telemetry must never be invented for a manual one.
        assertEquals(0, mine.score)
    }

    @Test
    fun `a manual server survives two refreshes back to back`() = runBlocking {
        val dao = FakeManualServerDao()
        val store = ManualServerStore(dao)
        assertTrue(store.add(host, "1701", VpnProtocolNames.L2TP_IPSEC, null, null))

        // Each refresh replaces the network rows wholesale and re-reads the
        // user's rows from the store — the same sequence the production
        // wiring runs, twice.
        val first = withManualRows(networkRows(secondOtherHost), store.toItems(store.listEnabled()))
        val second = withManualRows(networkRows(), store.toItems(store.listEnabled()))

        assertTrue(first.any { it.hostName == host && it.isL2TPSupport })
        assertTrue(second.any { it.hostName == host && it.isL2TPSupport })
        assertEquals(3, first.size)
        assertEquals(2, second.size)
    }

    @Test
    fun `removing a manual server makes it disappear on the next refresh`() = runBlocking {
        val dao = FakeManualServerDao()
        val store = ManualServerStore(dao)
        assertTrue(store.add(host, "1194", VpnProtocolNames.OPENVPN, null, null))
        val id = store.list().single().id

        val network = networkRows()
        assertTrue(
            withManualRows(network, store.toItems(store.listEnabled()))
                .any { it.hostName == host },
        )

        store.remove(id)

        val after = withManualRows(network, store.toItems(store.listEnabled()))
        assertFalse(after.any { it.hostName == host })
        assertSame(network, after)
    }

    // ------------------------------------------------- 3. the collision rule

    @Test
    fun `a manual server whose host the network already serves is not added twice`() = runBlocking {
        val dao = FakeManualServerDao()
        val store = ManualServerStore(dao)
        // The user typed a different port than the feed publishes — exactly
        // the case the frozen policy has to settle.
        assertTrue(store.add(host, "8443", VpnProtocolNames.SSTP, null, null))

        val stored = withManualRows(listOf(collidingNetworkRow()), store.toItems(store.listEnabled()))

        // One row, and it is the collected one (arch doc §3.3 / §4.2:
        // canonical outranks manual, never the other way round).
        assertEquals(1, stored.size)
        assertEquals(443, stored.single().sstpPort)

        // The manual entry is not destroyed, and it comes back the moment
        // the feeds stop listing that host — "re-emitted as long as they
        // remain valid" (arch doc §4.3).
        assertEquals(1, store.list().size)
        val revived = withManualRows(networkRows(), store.toItems(store.listEnabled()))
        assertEquals(8443, revived.single { it.hostName == host }.sstpPort)
    }

    @Test
    fun `two manual servers on one host do not collide on the storage key`() = runBlocking {
        val dao = FakeManualServerDao()
        val store = ManualServerStore(dao)
        // Both are legitimate decisions the user can make: the same address
        // over two different protocols. The unique index covers
        // (host, port, protocol), so nothing is rejected — but the table's
        // primary key is the host name alone, and a duplicate there would
        // abort the whole transactional replacement.
        assertTrue(store.add(host, "443", VpnProtocolNames.SSTP, null, null))
        assertTrue(store.add(host, "5555", VpnProtocolNames.SOFTETHER, null, null))

        val stored = withManualRows(emptyList(), store.toItems(store.listEnabled()))

        assertEquals(
            "one row per Room identity",
            1,
            stored.map { it.hostName.trim() }.toSet().size,
        )
        // Recorded limitation, not a silent surprise: the survivor is chosen
        // by VPNGateItem.dedupeForStorage's existing "most connectable,
        // then first inserted" order — the one Stage 2F-A policy — and when
        // two rows are equally connectable that is insertion order. Both
        // entries remain in `manual_server` and in Settings, so nothing is
        // lost; only one of them can occupy a row whose primary key is the
        // host name alone. Documented in the report.
        assertTrue(stored.single().isSSTPSupport)
        assertEquals(443, stored.single().sstpPort)
    }

    // ------------------------------------------------------- 3. precedence

    @Test
    fun `manual is the lowest authority in the record-layer merge`() {
        // Frozen policy, arch doc §3.3 / §4.2: api > html > (mirror) > manual.
        assertEquals(0, VpnServerMerger.sourcePriority("api"))
        assertEquals(10, VpnServerMerger.sourcePriority("html"))
        assertEquals(30, VpnServerMerger.sourcePriority("manual"))
        assertTrue(
            VpnServerMerger.sourcePriority("manual") >
                VpnServerMerger.sourcePriority("html"),
        )
    }

    @Test
    fun `a colliding manual record survives and its value is recorded not discarded`() {
        val api = sstpRecord(host, 443, "api")
        val manual = sstpRecord(host, 8443, "manual")

        val merged = VpnServerMerger.mergeRecords(listOf(api, manual))

        // One record, not two: they describe the same server.
        assertEquals(1, merged.size)

        // Canonical wins the value both sides claim (arch doc §3.3).
        val sstp = VpnRecords.map(VpnRecords.protocols(merged[0])[VpnProtocolNames.SSTP])
        assertEquals(443, VpnRecords.int(sstp["port"]))

        // The user's number is not silently dropped: §38 requires the
        // disagreement to be auditable, and §11.11 requires a manual record
        // not be *silently* overwritten.
        @Suppress("UNCHECKED_CAST")
        val conflicts = merged[0]["conflicts"] as? List<Map<String, Any?>> ?: emptyList()
        val entry = conflicts.single { it["field"] == "protocols.sstp.port" }
        @Suppress("UNCHECKED_CAST")
        val values = entry["values"] as Map<String, Any?>
        assertEquals(443, values["api"])
        assertEquals(8443, values["manual"])

        // And the record still carries the manual provenance group, so the
        // user's entry is visible in the merge rather than erased by it.
        assertTrue(VpnRecords.sources(merged[0]).contains("manual"))
    }

    @Test
    fun `a manual record with no collected counterpart keeps every field`() {
        val manual = sstpRecord(host, 8443, "manual")
        val merged = VpnServerMerger.mergeRecords(listOf(manual))

        assertEquals(1, merged.size)
        val sstp = VpnRecords.map(VpnRecords.protocols(merged[0])[VpnProtocolNames.SSTP])
        // Nothing to disagree with, so the user's value is the answer.
        assertEquals(8443, VpnRecords.int(sstp["port"]))
        assertNull(merged[0]["conflicts"])
    }

    @Test
    fun `a non-colliding manual record and a collected record stay separate`() {
        val api = sstpRecord("198.51.100.4", 443, "api")
        val manual = sstpRecord(host, 8443, "manual")

        val merged = VpnServerMerger.mergeRecords(listOf(api, manual))
        assertEquals(2, merged.size)
    }

    // ------------------------------------------------- 4. store validation

    @Test
    fun `the store refuses an address that is not an IPv4 literal`() = runBlocking {
        val store = ManualServerStore(FakeManualServerDao())
        // The record layer keys a server on a valid IPv4 and
        // normalizeServer rejects anything else, so a DNS name would be
        // accepted here and then silently dropped by the merge.
        assertFalse(store.add("vpn.example.com", "443", VpnProtocolNames.SSTP, null, null))
        assertFalse(store.add("", "443", VpnProtocolNames.SSTP, null, null))
        assertFalse(store.add("999.1.1.1", "443", VpnProtocolNames.SSTP, null, null))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `the store refuses a port or protocol it cannot use`() = runBlocking {
        val store = ManualServerStore(FakeManualServerDao())
        assertFalse(store.add(host, "0", VpnProtocolNames.SSTP, null, null))
        assertFalse(store.add(host, "65536", VpnProtocolNames.SSTP, null, null))
        assertFalse(store.add(host, "not-a-port", VpnProtocolNames.SSTP, null, null))
        assertFalse(store.add(host, "443", "pptp", null, null))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `re-entering the same server updates it instead of duplicating it`() = runBlocking {
        val dao = FakeManualServerDao()
        val store = ManualServerStore(dao)
        assertTrue(store.add(host, "443", VpnProtocolNames.SSTP, "Home", null))
        assertTrue(store.add(host, "443", "SSTP", "Home server", null))

        val rows = store.list()
        assertEquals(1, rows.size)
        assertEquals("Home server", rows.single().label)
        assertEquals("sstp", rows.single().protocol)
    }

    // --------------------------------------------------- 5. the row mapping

    @Test
    fun `each protocol writes only the columns it owns`() = runBlocking {
        val dao = FakeManualServerDao()
        val store = ManualServerStore(dao)
        assertTrue(store.add(host, "5555", VpnProtocolNames.SOFTETHER, null, null))
        assertTrue(store.add(otherHost, "443", VpnProtocolNames.OPENVPN, null, null))
        assertTrue(store.add(secondOtherHost, "1701", VpnProtocolNames.L2TP_IPSEC, null, null))
        assertTrue(store.add("203.0.113.8", "443", VpnProtocolNames.SSTP, null, null))

        val byHost = store.toItems(store.listEnabled()).associateBy { it.hostName }

        val softether = byHost.getValue(host)
        assertEquals(5555, softether.seTcpPort)
        assertFalse(softether.isSSTPSupport)
        assertFalse(softether.isL2TPSupport)
        // An unknown SoftEther UDP listener is never filled from the TCP one.
        assertEquals(0, softether.seUdpPort)

        val openvpn = byHost.getValue(otherHost)
        assertEquals(443, openvpn.tcpPort)
        assertFalse(openvpn.isSSTPSupport)

        val l2tp = byHost.getValue(secondOtherHost)
        assertTrue(l2tp.isL2TPSupport)

        val sstp = byHost.getValue("203.0.113.8")
        assertTrue(sstp.isSSTPSupport)
        assertEquals(443, sstp.sstpPort)
    }

    @Test
    fun `a manual record carries no invented telemetry`() = runBlocking {
        val store = ManualServerStore(FakeManualServerDao())
        assertTrue(store.add(host, "443", VpnProtocolNames.SSTP, null, null))
        val item = store.toItem(store.list().single())

        assertNull(item.countryLong)
        assertNull(item.countryShort)
        assertNull(item.logType)
        assertNull(item.message)
        assertNull(item.openVpnConfigData)
        assertEquals(0, item.score)
        assertEquals(0, item.ping)
        assertEquals(0, item.speed)
        // Notes are the user's annotation for the manual-server screen, not
        // a server's operator message.
        assertNull(item.message)
    }
}
