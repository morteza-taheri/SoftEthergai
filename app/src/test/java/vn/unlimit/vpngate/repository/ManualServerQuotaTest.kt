package vn.unlimit.vpngate.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import vn.unlimit.vpngate.data.model.VpnProtocolNames
import vn.unlimit.vpngate.data.remote.ManualServerDao
import vn.unlimit.vpngate.data.remote.ManualServerEntity
import vn.unlimit.vpngate.models.VPNGateItem

/**
 * REQ-RETENTION-001..009 (v1.0.71) — the configurable total capacity, the
 * derived manual-server quota, and the enforcement that keeps the two apart.
 *
 * The rule under test is arithmetic, not a magic number:
 *
 * ```
 * reserved = ceil(total × 10%)
 * manual   = total − reserved
 * ```
 *
 * so 500 → 50 reserved → 450 manual, and nothing anywhere hard-codes 450.
 *
 * Pure JVM: an in-memory [ManualServerDao] fake and a temporary file, no
 * Android, no Room, no network.
 */
class ManualServerQuotaTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ------------------------------------------------------------- fakes

    /** In-memory stand-in for the generated DAO (unique key by host+port+protocol). */
    private class FakeDao(initial: List<ManualServerEntity> = emptyList()) : ManualServerDao {
        private val rows = LinkedHashMap<Long, ManualServerEntity>()
        private var nextId = 1L

        init {
            // Not via upsert(): it is suspend, and seeding needs no suspension.
            initial.forEach { row ->
                val id = if (row.id == 0L) nextId++ else row.id
                rows[id] = row.copy(id = id)
            }
        }

        override fun observeAll(): Flow<List<ManualServerEntity>> = flowOf(rows.values.toList())

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

        fun count(): Int = rows.size
    }

    /** A distinct, valid IPv4 for index [i]. */
    private fun host(i: Int): String = "10.${i / 250}.${i % 250}.1"

    private fun entity(i: Int) = ManualServerEntity(
        host = host(i),
        port = 443,
        protocol = VpnProtocolNames.SSTP,
        createdAt = i.toLong(),
        updatedAt = i.toLong(),
    )

    private fun admission(dao: FakeDao, quota: Int = manualServerQuota()) =
        ManualServerAdmission(
            quota = quota,
            totalCapacity = MAX_RETAINED_SERVERS,
            count = { dao.listAll().size },
        )

    private fun store(dao: FakeDao, quota: Int = manualServerQuota()) =
        ManualServerStore(dao, admission(dao, quota))

    // ============================================================ A. defaults

    @Test
    fun `A the default capacity reserves ten percent and leaves 450 manual slots`() {
        assertEquals("the approved total capacity", 500, MAX_RETAINED_SERVERS)
        assertEquals("10% of 500, rounded up", 50, reservedCapacity())
        assertEquals("500 - 50", 450, manualServerQuota())
    }

    @Test
    fun `A the quota is derived, never stored as an independent constant`() {
        // The only literal is the percentage; every count follows from it.
        assertEquals(RESERVED_CAPACITY_PERCENT, 10)
        for (total in listOf(100, 250, 500, 501, 1000)) {
            assertEquals(
                "manual quota for $total must equal total - reserved",
                total - reservedCapacity(total),
                manualServerQuota(total),
            )
        }
    }

    // ========================================================== B. below limit

    @Test
    fun `B adding the 450th manual server succeeds`() = runBlocking {
        val dao = FakeDao((0 until 449).map { entity(it) })
        val store = store(dao)

        val outcome = store.addDetailed(host(449), "443", VpnProtocolNames.SSTP, null, null)

        assertTrue("expected Stored, got $outcome", outcome is AddOutcome.Stored)
        assertFalse("a new record is not an update", (outcome as AddOutcome.Stored).updated)
        assertEquals("the quota is now exactly full", 450, dao.count())
        assertTrue("the quota is not exceeded", dao.count() <= manualServerQuota())
    }

    // ============================================================= C. at limit

    @Test
    fun `C adding a new manual server at the limit is rejected and writes nothing`() = runBlocking {
        val dao = FakeDao((0 until 450).map { entity(it) })
        val store = store(dao)

        val outcome = store.addDetailed(host(450), "443", VpnProtocolNames.SSTP, null, null)

        assertTrue("expected QuotaReached, got $outcome", outcome is AddOutcome.QuotaReached)
        val rejected = outcome as AddOutcome.QuotaReached
        assertEquals("the count the user is shown", 450, rejected.manualCount)
        assertEquals("the quota the user is shown", 450, rejected.quota)
        assertEquals("the capacity the user is shown", 500, rejected.totalCapacity)

        assertEquals("no record may be added past the quota", 450, dao.count())
        assertTrue(
            "existing records must be untouched",
            (0 until 450).all { i -> dao.listAll().any { it.host == host(i) } },
        )
        assertTrue(
            "the rejected host must not be stored",
            dao.listAll().none { it.host == host(450) },
        )
    }

    @Test
    fun `C the Boolean form reports the rejection too`() = runBlocking {
        val dao = FakeDao((0 until 450).map { entity(it) })
        val store = store(dao)

        assertFalse(
            "a full quota must not look like success",
            store.add(host(450), "443", VpnProtocolNames.SSTP, null, null),
        )
        assertEquals(450, dao.count())
    }

    // ======================================================== D. duplicate

    @Test
    fun `D a duplicate submission at the limit consumes no extra slot`() = runBlocking {
        val dao = FakeDao((0 until 450).map { entity(it) })
        val store = store(dao)

        // Same (host, port, protocol) as row 0 — an edit, not a new server.
        val outcome = store.addDetailed(host(0), "443", VpnProtocolNames.SSTP, "Home", null)

        assertTrue("a duplicate edit must still be stored, got $outcome", outcome is AddOutcome.Stored)
        assertTrue("and it must be reported as an update", (outcome as AddOutcome.Stored).updated)
        assertEquals("no extra record", 450, dao.count())
        assertEquals(
            "the edit landed on the existing row",
            "Home",
            dao.listAll().first { it.host == host(0) }.label,
        )
    }

    // ==================================================== E. dynamic capacity

    @Test
    fun `E the reserved share follows the configured capacity`() {
        val expected = mapOf(
            100 to (10 to 90),
            250 to (25 to 225),
            500 to (50 to 450),
            501 to (51 to 450),
            1000 to (100 to 900),
        )
        for ((total, pair) in expected) {
            val (reserved, manual) = pair
            assertEquals("reserved for $total", reserved, reservedCapacity(total))
            assertEquals("manual quota for $total", manual, manualServerQuota(total))
        }
    }

    @Test
    fun `E a capacity not divisible by ten rounds the reservation upward`() {
        // 123 × 10% = 12.3 → 13 reserved (never 12: at least 10% stays free).
        assertEquals(13, reservedCapacity(123))
        assertEquals(110, manualServerQuota(123))

        // 501 × 10% = 50.1 → 51, so the manual quota stays at 450.
        assertEquals(51, reservedCapacity(501))
        assertEquals(450, manualServerQuota(501))

        // Small capacities still reserve something rather than nothing.
        assertEquals(1, reservedCapacity(1))
        assertEquals(1, reservedCapacity(9))
        assertEquals(0, reservedCapacity(0))
        assertEquals(0, manualServerQuota(0))
    }

    @Test
    fun `E the quota recalculates when the capacity changes`() = runBlocking {
        // The same persisted state, two capacities: only the derived number moves.
        val dao = FakeDao((0 until 95).map { entity(it) })
        assertTrue(
            "95 records already exceed a 100-slot capacity's 90 manual slots",
            95 > manualServerQuota(100),
        )
        val small = store(dao, quota = manualServerQuota(100))
        assertTrue(
            "a 100-slot capacity leaves only 90 manual slots",
            small.addDetailed(host(95), "443", VpnProtocolNames.SSTP, null, null)
                is AddOutcome.QuotaReached,
        )

        val large = store(dao, quota = manualServerQuota(1000))
        assertTrue(
            "a 1000-slot capacity leaves 900, so the same add fits",
            large.addDetailed(host(95), "443", VpnProtocolNames.SSTP, null, null)
                is AddOutcome.Stored,
        )
    }

    // ===================================================== F. total capacity

    private fun row(name: String, score: Int = 0) = VPNGateItem(
        hostName = name,
        ip = name,
        countryLong = null,
        countryShort = null,
        logType = null,
        operator = null,
        message = null,
        openVpnConfigData = null,
    ).copy(healthScore = score)

    @Test
    fun `F the retained list never exceeds the total capacity`() {
        val manual = (0 until 450).map { row(host(it)) }
        val network = (0 until 500).map { row("net-$it.example", score = it) }
        val capped = capRetainedServers(
            rows = network + manual,
            manualHostNames = manual.mapTo(HashSet()) { it.hostName.trim() },
        )

        assertEquals("the capacity is a hard ceiling", MAX_RETAINED_SERVERS, capped.size)
        assertTrue(
            "every manual row must survive the cap",
            manual.all { m -> capped.any { it.hostName == m.hostName } },
        )
        assertEquals(
            "the leftover room goes to the collected rows",
            MAX_RETAINED_SERVERS - manual.size,
            capped.count { !it.hostName.startsWith("10.") },
        )
    }

    @Test
    fun `F manual rows cannot bypass the capacity`() {
        // More manual rows than the capacity itself: nothing is deleted (user
        // data is never pruned) but no collected row is admitted either.
        val manual = (0 until 60).map { row(host(it)) }
        val capped = capRetainedServers(
            rows = manual + (0 until 40).map { row("net-$it.example") },
            manualHostNames = manual.mapTo(HashSet()) { it.hostName.trim() },
            cap = 50,
        )

        assertEquals("all user rows kept, no collected rows added", 60, capped.size)
        assertTrue(
            "no collected row may be admitted while user rows exceed the cap",
            capped.none { it.hostName.startsWith("net-") },
        )
    }

    @Test
    fun `F collected rows are still ranked by quality inside the cap`() {
        val rows = listOf(
            row("low.example", score = 1),
            row("high.example", score = 90),
            row("mid.example", score = 50),
        )
        val capped = capRetainedServers(rows = rows, manualHostNames = emptySet(), cap = 2)

        assertEquals(listOf("high.example", "mid.example"), capped.map { it.hostName })
    }

    // ================================================ G. already over the limit

    @Test
    fun `G data already over the quota is preserved and cannot get worse`() = runBlocking {
        // 460 persisted manual records against a 450 quota — the state a user
        // can be in after a capacity change, or from a build without the cap.
        val dao = FakeDao((0 until 460).map { entity(it) })
        val store = store(dao)

        val outcome = store.addDetailed(host(999), "443", VpnProtocolNames.SSTP, null, null)

        assertTrue("the add must be refused, got $outcome", outcome is AddOutcome.QuotaReached)
        assertEquals("nothing may be deleted", 460, dao.count())
        assertTrue(
            "the over-cap state must be reported honestly, not rounded down",
            (outcome as AddOutcome.QuotaReached).manualCount == 460,
        )
    }

    @Test
    fun `G an over-cap user can still edit and delete`() = runBlocking {
        val dao = FakeDao((0 until 460).map { entity(it) })
        val store = store(dao)

        // Editing an existing record takes no slot, so it must still work —
        // otherwise an over-cap user could not even fix their own data.
        assertTrue(
            "an edit must be allowed over the cap",
            store.addDetailed(host(0), "443", VpnProtocolNames.SSTP, "Edited", null)
                is AddOutcome.Stored,
        )
        assertEquals(460, dao.count())

        val firstId = dao.listAll().first { it.host == host(0) }.id
        store.remove(firstId)
        assertEquals("deleting is how the user recovers", 459, dao.count())
    }

    // ========================================================= H. concurrency

    @Test
    fun `H concurrent additions cannot exceed the quota`() = runBlocking {
        val quota = 10
        val dao = FakeDao()
        val store = store(dao, quota = quota)

        val outcomes = coroutineScope {
            (0 until 40).map { i ->
                async { store.addDetailed(host(i), "443", VpnProtocolNames.SSTP, null, null) }
            }.awaitAll()
        }

        val stored = outcomes.count { it is AddOutcome.Stored }
        val rejected = outcomes.count { it is AddOutcome.QuotaReached }
        assertEquals("exactly the quota may be stored", quota, stored)
        assertEquals("everything else must be refused", 40 - quota, rejected)
        assertEquals("and the persisted count must agree", quota, dao.count())
    }

    @Test
    fun `H a rejected addition leaves no partial record`() = runBlocking {
        val dao = FakeDao((0 until 450).map { entity(it) })
        val before = dao.listAll().map { it.host to it.updatedAt }.toSet()
        val store = store(dao)

        store.addDetailed(host(450), "443", VpnProtocolNames.SSTP, "Nope", "note")

        assertEquals("the table is byte-identical", before, dao.listAll().map { it.host to it.updatedAt }.toSet())
    }

    // ==================================================== import surface quota

    private fun item(name: String) = VPNGateItem(
        hostName = name,
        ip = "203.0.113.7",
        countryLong = null,
        countryShort = null,
        logType = null,
        operator = null,
        message = null,
        openVpnConfigData = null,
    )

    @Test
    fun `the import surface is bounded by the same quota`() = runBlocking {
        val file = tempFolder.newFile("imported.json")
        val store = ManualImportStore(file)
        val admission = ManualServerAdmission(
            quota = 3,
            totalCapacity = MAX_RETAINED_SERVERS,
            count = { store.items().size },
        )

        val result = store.addWithinQuota(
            listOf(item("a.example"), item("b.example"), item("c.example"),
                item("d.example"), item("e.example")),
            admission,
        )

        assertEquals("only the rows that fit are stored", 3, result.added)
        assertEquals("nothing was updated", 0, result.updated)
        assertEquals("the overflow is reported, not silently dropped", 2, result.quotaRejected)
        assertEquals(3, store.items().size)
        assertEquals(3, result.quota)
        assertEquals(MAX_RETAINED_SERVERS, result.totalCapacity)
    }

    @Test
    fun `a duplicate import consumes no quota slot`() = runBlocking {
        val file = tempFolder.newFile("imported.json")
        val store = ManualImportStore(file)
        val admission = ManualServerAdmission(
            quota = 2,
            totalCapacity = MAX_RETAINED_SERVERS,
            count = { store.items().size },
        )

        store.addWithinQuota(listOf(item("a.example"), item("b.example")), admission)
        val second = store.addWithinQuota(listOf(item("a.example"), item("b.example")), admission)

        assertEquals("no new records", 0, second.added)
        assertEquals("both were updates", 2, second.updated)
        assertEquals("and neither was refused", 0, second.quotaRejected)
        assertEquals(2, store.items().size)
    }

    @Test
    fun `an import that only updates is admitted even at the quota`() = runBlocking {
        val file = tempFolder.newFile("imported.json")
        val store = ManualImportStore(file)
        val admission = ManualServerAdmission(
            quota = 2,
            totalCapacity = MAX_RETAINED_SERVERS,
            count = { store.items().size },
        )

        store.addWithinQuota(listOf(item("a.example"), item("b.example")), admission)
        // Full quota, one new row: the update applies, the new row is refused.
        val mixed = store.addWithinQuota(
            listOf(item("a.example"), item("c.example")),
            admission,
        )

        assertEquals("the new row must be refused", 0, mixed.added)
        assertEquals("the duplicate must still apply", 1, mixed.updated)
        assertEquals(1, mixed.quotaRejected)
        assertEquals("the file still holds exactly the quota", 2, store.items().size)
        assertNotNull(
            "the existing row is still there",
            store.items().firstOrNull { it.hostName == "a.example" },
        )
    }
}
