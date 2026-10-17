package vn.unlimit.vpngate.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.data.model.VpnProtocolNames
import vn.unlimit.vpngate.data.remote.ManualServerDao
import vn.unlimit.vpngate.data.remote.ManualServerEntity
import vn.unlimit.vpngate.models.VPNGateItem

/**
 * v1.0.26: a manual server is visible in the main list as soon as it is added.
 *
 * Before this revision a manual row reached the server list only through
 * `ServerListUpdates.withManualRows`, which runs inside an *accepted* refresh.
 * So the user could add a server, see it in Settings, and see nothing in the
 * list until some later refresh happened to be accepted — a restart only
 * looked like the fix because a restart triggers one. `ManualServerStore
 * .materialize` is the write-through that closes that gap, and it is
 * deliberately the *same* `withManualRows` the refresh path uses, so both paths
 * share one preservation policy rather than two that could drift.
 *
 * Pure JVM: an in-memory [ManualServerDao] fake, no Android, no Room, no
 * network. `materialize` is exercised against a plain `List<VPNGateItem>` that
 * stands in for the table, which is exactly what the production caller reads
 * and writes back.
 */
class ManualServerImmediateMaterializeTest {

    // ---------------------------------------------------------------- fakes

    /**
     * The same shape as `ManualServerPreservationTest`'s fake: the interface is
     * implemented directly, so the table's real constraints (the unique index
     * on host+port+protocol, the autoGenerate key) are mimicked by hand — which
     * is what the store actually relies on.
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

    // -------------------------------------------------------------- helpers

    private val networkHost = "198.51.100.4"
    private val otherNetworkHost = "192.0.2.9"

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

    /** A stored network dataset that does not contain either test address. */
    private fun networkRows(): List<VPNGateItem> =
        listOf(row(networkHost), row(otherNetworkHost))

    // ------------------------------------------------------------- 1. the add

    @Test
    fun `adding a manual server materialises it into the stored dataset immediately`() = runBlocking {
        val store = ManualServerStore(FakeManualServerDao())
        val network = networkRows()

        assertTrue(
            "the store must accept a valid SoftEther entry",
            store.add("8.8.8.8", "443", VpnProtocolNames.SOFTETHER, "immediate-test", null),
        )

        val merged = store.materialize(network)

        val added = merged.firstOrNull { it.hostName == "8.8.8.8" }
        assertNotNull("the new address must be in the dataset without any refresh", added)
        // The port belongs to the protocol that owns it — never another one.
        assertEquals(443, added!!.seTcpPort)
        assertEquals("the label is the operator field", "immediate-test", added.operator)
        assertEquals("no other row may be dropped", 3, merged.size)
        assertTrue(merged.any { it.hostName == networkHost })
        assertTrue(merged.any { it.hostName == otherNetworkHost })
    }

    @Test
    fun `materialising into an empty dataset writes only the manual row`() = runBlocking {
        val store = ManualServerStore(FakeManualServerDao())
        store.add("8.8.8.8", "443", VpnProtocolNames.SOFTETHER, "immediate-test", null)

        // The fresh-install case: nothing has ever been refreshed successfully,
        // so the table is empty. The result must be the manual row alone — not
        // an empty list, which `replaceAll` would refuse anyway.
        val merged = store.materialize(emptyList())

        assertEquals(1, merged.size)
        assertEquals("8.8.8.8", merged.single().hostName)
    }

    // -------------------------------------------------------- 2. the collision

    @Test
    fun `an address the network already serves is not duplicated and nothing is dropped`() = runBlocking {
        val store = ManualServerStore(FakeManualServerDao())
        // The network already lists 8.8.8.8 with MS-SSTP on 995.
        val network = listOf(
            row("8.8.8.8").copy(isSSTPSupport = true, sstpPort = 995),
            row(networkHost),
        )

        store.add("8.8.8.8", "443", VpnProtocolNames.SOFTETHER, "dup-test", null)
        val merged = store.materialize(network)

        assertEquals("no duplicate row may be created", 2, merged.size)
        assertEquals(1, merged.count { it.hostName == "8.8.8.8" })
        // Canonical outranks manual (arch doc §3.3/§4.2): the network's row is
        // the one that survives, so its SSTP facts are still there.
        val kept = merged.first { it.hostName == "8.8.8.8" }
        assertEquals(995, kept.sstpPort)
        assertTrue(kept.isSSTPSupport)
        assertEquals("the unrelated network row must survive", 1, merged.count { it.hostName == networkHost })
    }

    // ------------------------------------------------------- 3. two in sequence

    @Test
    fun `two manual servers added in sequence are both materialised`() = runBlocking {
        val store = ManualServerStore(FakeManualServerDao())

        store.add("8.8.8.8", "443", VpnProtocolNames.SOFTETHER, "first", null)
        val afterFirst = store.materialize(networkRows())
        assertEquals(3, afterFirst.size)

        store.add("9.9.9.9", "443", VpnProtocolNames.SOFTETHER, "second", null)
        // The second add materialises against what the first one left behind,
        // which is what the production caller does (read the table, merge,
        // write it back).
        val afterSecond = store.materialize(afterFirst)

        assertEquals(4, afterSecond.size)
        assertTrue(afterSecond.any { it.hostName == "8.8.8.8" })
        assertTrue(afterSecond.any { it.hostName == "9.9.9.9" })
        assertTrue(afterSecond.any { it.hostName == networkHost })
        assertTrue(afterSecond.any { it.hostName == otherNetworkHost })
    }

    // ------------------------------------------- 4. removal is not a deletion

    @Test
    fun `removing a manual server does not remove the already materialised row`() = runBlocking {
        val dao = FakeManualServerDao()
        val store = ManualServerStore(dao)

        store.add("8.8.8.8", "443", VpnProtocolNames.SOFTETHER, "removed-later", null)
        val table = store.materialize(networkRows())
        assertTrue(table.any { it.hostName == "8.8.8.8" })

        val id = store.list().single().id
        store.remove(id)

        // Deliberate limitation, recorded rather than fixed: removing the
        // manual server stops it being re-emitted, but it does not reach into
        // VPNGateItem to delete a row. With no enabled manual servers the
        // merge is a no-op by identity, so the row survives until the next
        // accepted refresh replaces the table (at which point `withManualRows`
        // no longer re-adds it, because it is gone from `manual_server`).
        val afterRemoval = store.materialize(table)
        assertSame("an empty manual list must leave the dataset untouched", table, afterRemoval)
        assertTrue(
            "the row is not deleted eagerly — it goes at the next accepted refresh",
            afterRemoval.any { it.hostName == "8.8.8.8" },
        )
    }
}
