package vn.unlimit.vpngate.repository

import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import vn.unlimit.vpngate.collector.CollectorTestFixtures
import vn.unlimit.vpngate.data.remote.HttpFetcher
import vn.unlimit.vpngate.data.remote.HttpResponse
import vn.unlimit.vpngate.data.remote.RedirectPolicy
import vn.unlimit.vpngate.db.VPNGateItemDao
import vn.unlimit.vpngate.models.VPNGateItem
import java.io.File

/**
 * Stage 2D-B: one authoritative writer.
 *
 * Every path here goes through the production wiring ([ServerListUpdates]) with
 * a real [VpnServerRepository] — real source validation, parsing, merging and
 * [DatasetIntegrity] — and an in-memory DAO that counts every write. What is
 * asserted throughout is that the DAO is only ever written from inside the
 * updater's acceptance boundary, and that a dataset which fails the gate
 * reaches neither the table, nor the snapshot, nor a consumer.
 *
 * Deterministic and offline: bodies come from the captured fixtures.
 */
class ServerListAcquisitionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ------------------------------------------------------------- fakes

    /** In-memory Room. Every mutation is counted, so "who wrote" is observable. */
    private class FakeDao(initial: List<VPNGateItem> = emptyList()) : VPNGateItemDao {
        var items: List<VPNGateItem> = initial
        var replaceAllCalls = 0

        override fun getAll(): List<VPNGateItem> = items

        override fun filterAndSort(query: SupportSQLiteQuery): List<VPNGateItem> = items

        override fun insertAll(vararg vpnGateItem: VPNGateItem) {
            items = items + vpnGateItem
        }

        override fun deleteAll() {
            items = emptyList()
        }

        override fun count(): Int = items.size

        override fun countWithOpenVpnConfig(): Int =
            items.count { !it.openVpnConfigData.isNullOrBlank() }

        override fun replaceAll(items: List<VPNGateItem>) {
            if (items.isEmpty()) return
            replaceAllCalls++
            this.items = items
        }
    }

    /** Serves the captured fixtures by URL shape, the way the real sources ask. */
    private class FixtureFetcher(
        private val html: String? = null,
        private val csv: String? = null,
        /** When set, every request parks here until the test releases it. */
        private val gate: CompletableDeferred<Unit>? = null,
        private val entered: CompletableDeferred<Unit>? = null,
    ) : HttpFetcher {
        var requests = 0

        override suspend fun get(url: String, redirectPolicy: RedirectPolicy?): HttpResponse? {
            requests++
            entered?.let { if (!it.isCompleted) it.complete(Unit) }
            gate?.await()
            return when {
                url.contains("/api/iphone") ->
                    csv?.let { HttpResponse(url, url, 200, "text/csv", it) }
                url.contains("sites.aspx") -> null
                else -> html?.let { HttpResponse(url, url, 200, "text/html", it) }
            }
        }
    }

    /** No network at all, so the repository can only fall back. */
    private class Fetcherless : HttpFetcher {
        override suspend fun get(url: String, redirectPolicy: RedirectPolicy?): HttpResponse? = null
    }

    // --------------------------------------------------------- fixtures

    private val htmlSample: String get() = CollectorTestFixtures.load(CollectorTestFixtures.TABLE_SAMPLE)
    private val apiSample: String get() = CollectorTestFixtures.load(CollectorTestFixtures.API_SAMPLE)

    /** A CSV with exactly [rows] data rows under one header line. */
    private fun csv(rows: Int): String {
        val header = CollectorTestFixtures.makeApiRow().lineSequence().first()
        val body = (1..rows).map { i ->
            CollectorTestFixtures.makeApiRow(
                mapOf("HostName" to "public-vpn-$i", "IP" to "219.100.37.$i"),
            ).lineSequence().drop(1).joinToString("\n")
        }
        return (listOf(header) + body).joinToString("\n")
    }

    private fun repository(fetcher: HttpFetcher, name: String) =
        VpnServerRepository(fetcher = fetcher, cacheDir = tempFolder.newFolder(name))

    private fun rows(items: List<VPNGateItem>) = items.map { it.hostName }

    private fun snapshotHosts(repository: VpnServerRepository): List<String?> {
        val list = repository.readLastKnownGoodSnapshot() ?: return emptyList()
        return (0 until list.size()).map { list.get(it)?.hostName }
    }

    private fun accepted(count: Int, prefix: String, withProfile: Boolean = true) =
        (1..count).map { i ->
            VPNGateItem(
                hostName = "$prefix-$i",
                ip = "10.0.0.$i",
                countryLong = "Japan",
                countryShort = "JP",
                logType = null,
                operator = null,
                message = null,
                openVpnConfigData = if (withProfile) "client\n" else null,
                tcpPort = 443,
            )
        }

    // ============================================= B: accepted network data

    @Test
    fun anEmptyStoreIsFilledWithAcceptedNetworkDataThroughTheGate() = runBlocking {
        val dao = FakeDao()
        val repository = repository(FixtureFetcher(htmlSample, apiSample), "b")

        val stored = ServerListUpdates.forAcquisition(repository, dao).acquire()

        assertTrue("an accepted dataset must reach the store", stored.isNotEmpty())
        assertEquals("exactly one authoritative write", 1, dao.replaceAllCalls)
        assertEquals(rows(stored), rows(dao.items))
        assertEquals(
            "the accepted dataset is promoted to last-known-good",
            rows(dao.items),
            snapshotHosts(repository),
        )
    }

    @Test
    fun anAcceptedNetworkFillIsNotReportedAsCacheSourced() = runBlocking {
        val dao = FakeDao()
        val updater = ServerListUpdates.forUpdateTrigger(
            repository(FixtureFetcher(htmlSample, apiSample), "b2"),
            dao,
        )

        val outcome = updater.update()

        assertTrue("expected acceptance, got $outcome", outcome is ServerListUpdater.Outcome.Updated)
        outcome as ServerListUpdater.Outcome.Updated
        assertFalse("a network dataset is not cache-sourced", outcome.sourcedFromCache)
        assertTrue(outcome.snapshotSaved)
    }

    // ============================================== C: degraded network data

    @Test
    fun aDegradedNetworkDatasetNeverReachesAnEmptyStore() = runBlocking {
        val dao = FakeDao()
        val repository = repository(FixtureFetcher(csv = csv(3)), "c")

        val outcome = ServerListUpdates.forUpdateTrigger(repository, dao).update()

        assertEquals(
            ServerListUpdater.Outcome.Preserved(ServerListUpdater.Reason.DEGRADED_DATASET, 0),
            outcome,
        )
        assertEquals("no authoritative write", 0, dao.replaceAllCalls)
        assertTrue("Room stays empty", dao.items.isEmpty())
        assertTrue("no snapshot may be created", snapshotHosts(repository).isEmpty())
    }

    @Test
    fun aRejectedDatasetIsNeverHandedOutAsCandidates() = runBlocking {
        val dao = FakeDao()
        val acquisition = ServerListUpdates.forAcquisition(
            repository(FixtureFetcher(csv = csv(3)), "c2"),
            dao,
        )

        val candidates = acquisition.acquire()

        assertTrue("Auto Connect must get nothing, not a rejected dataset", candidates.isEmpty())
        assertEquals(0, dao.replaceAllCalls)
    }

    // ============================================ E: snapshot fallback policy

    @Test
    fun aSnapshotMayFillAnEmptyStoreButIsNotPromotedAgain() = runBlocking {
        val dao = FakeDao()
        val repository = repository(Fetcherless(), "e")
        val previous = accepted(40, "accepted")
        assertTrue(repository.saveLastKnownGood(previous))

        val outcome = ServerListUpdates.forAcquisition(repository, dao).update()

        assertTrue("expected acceptance, got $outcome", outcome is ServerListUpdater.Outcome.Updated)
        outcome as ServerListUpdater.Outcome.Updated
        assertTrue("a snapshot fill is cache-sourced", outcome.sourcedFromCache)
        assertEquals("the accepted snapshot populates the store", 40, dao.items.size)
        assertEquals(1, dao.replaceAllCalls)
        assertEquals(
            "the snapshot must be left alone, not rewritten from itself",
            rows(previous),
            snapshotHosts(repository),
        )
    }

    @Test
    fun anUpdateTriggerStillRefusesASnapshotFallback() = runBlocking {
        val dao = FakeDao()
        val repository = repository(Fetcherless(), "e2")
        assertTrue(repository.saveLastKnownGood(accepted(40, "s")))

        val outcome = ServerListUpdates.forUpdateTrigger(repository, dao).update()

        assertEquals(
            "the Update button must treat a fallback as no news at all",
            ServerListUpdater.Outcome.Preserved(ServerListUpdater.Reason.NETWORK_FAILURE, 0),
            outcome,
        )
        assertEquals(0, dao.replaceAllCalls)
        assertTrue(dao.items.isEmpty())
    }

    @Test
    fun aCacheSourcedCandidateIsJudgedByTheSameGateAsANetworkOne() = runBlocking {
        // A fallback that is structurally fine but collapsed must be refused
        // exactly like a network dataset of that size would be: cached data
        // does not get a cheaper rule.
        val dao = FakeDao(accepted(100, "stored", withProfile = false))
        val repository = repository(Fetcherless(), "e3")
        assertTrue(repository.saveLastKnownGood(accepted(40, "s")))

        val outcome = ServerListUpdates.forAcquisition(repository, dao).update()

        assertEquals(
            ServerListUpdater.Outcome.Preserved(ServerListUpdater.Reason.DEGRADED_DATASET, 100),
            outcome,
        )
        assertEquals(0, dao.replaceAllCalls)
        assertEquals(100, dao.items.size)
    }

    // ============================================== D: bundled seed policy

    @Test
    fun aBundledSeedIsClassifiedCacheSourcedSoItFacesTheSameGate() {
        // The seed asset cannot be opened from a JVM test, but it reaches
        // consumers through exactly one place: the seed branch of
        // loadSnapshot(). Asserting that branch reports fromCache = true is
        // what makes the policy claim testable — the seed is therefore offered
        // to the acceptance boundary (as the snapshot tests above prove) and
        // can never be persisted unvalidated.
        val seedBranch = sourceOf(
            "src/main/java/vn/unlimit/vpngate/repository/VpnServerRepository.kt",
        ).substringAfter("Bundled seed asset fallback").substringBefore("return null")

        assertTrue(
            "the bundled seed must be reported as a cache fallback",
            seedBranch.contains("fromCache = true"),
        )
    }

    @Test
    fun anEmptyStoreWithNothingAvailableStaysEmpty() = runBlocking {
        val dao = FakeDao()
        val acquisition = ServerListUpdates.forAcquisition(repository(Fetcherless(), "d"), dao)

        val candidates = acquisition.acquire()

        assertTrue("no fake or partial list may be created", candidates.isEmpty())
        assertEquals(0, dao.replaceAllCalls)
        assertTrue(dao.items.isEmpty())
    }

    // ============================================= F/G: the updater always wins

    @Test
    fun anAcceptedManualDatasetSurvivesAConcurrentAcquisition() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val dao = FakeDao()
        val manualRepository = repository(FixtureFetcher(htmlSample, apiSample, release, entered), "f")
        val acquisitionRepository = repository(FixtureFetcher(htmlSample, apiSample), "f2")
        val manual = ServerListUpdates.forUpdateTrigger(manualRepository, dao)
        val acquisition = ServerListUpdates.forAcquisition(acquisitionRepository, dao)

        val manualJob = async(Dispatchers.Default) { manual.update() }
        entered.await()
        assertEquals("the manual update is still in flight", 0, dao.replaceAllCalls)

        val acquireJob = async(Dispatchers.Default) { acquisition.acquire() }
        delay(150)
        release.complete(Unit)

        val manualOutcome = manualJob.await()
        val candidates = acquireJob.await()

        assertTrue(manualOutcome is ServerListUpdater.Outcome.Updated)
        assertEquals(
            "the accepted dataset must stay authoritative: exactly one write",
            1,
            dao.replaceAllCalls,
        )
        assertEquals(
            "Auto Connect must consume what the updater accepted",
            rows(dao.items),
            rows(candidates),
        )
        assertEquals(rows(dao.items), snapshotHosts(manualRepository))
    }

    @Test
    fun anAcceptedScheduledDatasetSurvivesAConcurrentAcquisition() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val dao = FakeDao()
        val scheduledRepository = repository(FixtureFetcher(htmlSample, apiSample, release, entered), "g")
        val acquisitionRepository = repository(FixtureFetcher(htmlSample, apiSample), "g2")
        // Two independent updater instances, exactly as production has one per
        // trigger; they share the process-wide gate.
        val scheduled = ServerListUpdates.forUpdateTrigger(scheduledRepository, dao)
        val acquisition = ServerListUpdates.forAcquisition(acquisitionRepository, dao)

        val scheduledJob = async(Dispatchers.Default) { scheduled.update() }
        entered.await()

        val acquireJob = async(Dispatchers.Default) { acquisition.acquire() }
        delay(150)
        release.complete(Unit)

        val scheduledOutcome = scheduledJob.await()
        val candidates = acquireJob.await()

        assertTrue(scheduledOutcome is ServerListUpdater.Outcome.Updated)
        assertEquals("only the updater may write, exactly once", 1, dao.replaceAllCalls)
        assertEquals(rows(dao.items), rows(candidates))
        assertEquals(rows(dao.items), snapshotHosts(scheduledRepository))
    }

    // ========================================== I: rejection vs accepted data

    @Test
    fun aRejectedDatasetNeverReplacesAnAcceptedOne() = runBlocking {
        val accepted = accepted(100, "accepted")
        val dao = FakeDao(accepted)
        val repository = repository(FixtureFetcher(csv = csv(3)), "i")
        assertTrue(repository.saveLastKnownGood(accepted))

        val outcome = ServerListUpdates.forUpdateTrigger(repository, dao).update()

        assertEquals(
            ServerListUpdater.Outcome.Preserved(ServerListUpdater.Reason.DEGRADED_DATASET, 100),
            outcome,
        )
        assertEquals(0, dao.replaceAllCalls)
        assertEquals(rows(accepted), rows(dao.items))
        assertEquals(rows(accepted), snapshotHosts(repository))
    }

    // ==================================================== J: last-known-good

    @Test
    fun lastKnownGoodIsUntouchedByARejectedCandidate() = runBlocking {
        val accepted = accepted(80, "lkg")
        val dao = FakeDao(accepted)
        val repository = repository(FixtureFetcher(csv = csv(2)), "j")
        assertTrue(repository.saveLastKnownGood(accepted))

        val outcome = ServerListUpdates.forAcquisition(repository, dao).update()

        assertTrue(outcome is ServerListUpdater.Outcome.Preserved)
        assertEquals(rows(accepted), rows(dao.items))
        assertEquals(rows(accepted), snapshotHosts(repository))
    }

    @Test
    fun acquisitionReturnsTheStoredDatasetWithoutFetchingOrWriting() = runBlocking {
        val accepted = accepted(30, "stored")
        val dao = FakeDao(accepted)
        val fetcher = FixtureFetcher(htmlSample, apiSample)

        val stored = ServerListUpdates.forAcquisition(repository(fetcher, "j2"), dao).acquire()

        assertEquals(rows(accepted), rows(stored))
        assertEquals("nothing may be written", 0, dao.replaceAllCalls)
        assertEquals("nothing may be downloaded", 0, fetcher.requests)
    }

    // ---------------------------------------------------------- helpers

    /** Reads a production source file, relative to the module directory. */
    private fun sourceOf(relativePath: String): String {
        for (path in listOf(relativePath, "../$relativePath", "app/$relativePath")) {
            val file = File(path)
            if (file.isFile) return file.readText(Charsets.UTF_8)
        }
        error("source file not found: $relativePath (cwd=${File(".").absolutePath})")
    }
}
