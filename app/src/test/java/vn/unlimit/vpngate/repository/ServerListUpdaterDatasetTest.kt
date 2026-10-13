package vn.unlimit.vpngate.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import vn.unlimit.vpngate.collector.CollectorTestFixtures
import vn.unlimit.vpngate.data.remote.SourceIdentity
import vn.unlimit.vpngate.data.model.VpnRecords
import vn.unlimit.vpngate.merger.VpnServerMerger
import vn.unlimit.vpngate.models.VPNGateItem
import vn.unlimit.vpngate.parser.VpnGateHtmlParser

/**
 * Stage 2C-B: dataset integrity as the updater applies it, including the
 * last-known-good snapshot on a real file in a temporary directory.
 *
 * Deterministic and offline. Datasets are built from the model or from the
 * real captured HTML fixtures, never from a live server count.
 */
class ServerListUpdaterDatasetTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * Room stand-in plus a real snapshot file, so the storage acceptance
     * path — table swap, then last-known-good write — is exercised as one
     * sequence rather than mocked away.
     */
    private class FakeStore(
        initial: List<VPNGateItem> = emptyList(),
        private val snapshot: ((List<VPNGateItem>) -> Boolean)? = null,
    ) : ServerListUpdater.Store {
        var items: List<VPNGateItem> = initial
        var replaceCount = 0
        var snapshotWrites = 0

        override fun currentCount(): Int = items.size

        override fun currentOpenVpnConfigCount(): Int =
            items.count { !it.openVpnConfigData.isNullOrBlank() }

        override fun currentDataset(): List<VPNGateItem> = items

        override fun replaceAll(items: List<VPNGateItem>) {
            this.items = items
            replaceCount++
        }

        override fun saveLastKnownGood(items: List<VPNGateItem>): Boolean {
            snapshotWrites++
            return snapshot?.invoke(items) ?: true
        }
    }

    // ------------------------------------------------------------ fixtures

    private fun server(
        name: String,
        profile: Boolean = true,
        seTcp: Boolean = true,
        sstp: Boolean = true,
    ) = VPNGateItem(
        hostName = name,
        ip = "10.0.0.1",
        countryLong = "Japan",
        countryShort = "JP",
        logType = null,
        operator = null,
        message = null,
        openVpnConfigData = if (profile) "client\nproto tcp\nremote 10.0.0.1 443\n" else null,
        tcpPort = 443,
        isSSTPSupport = sstp,
        seTcpPort = if (seTcp) 443 else 0,
    )

    private fun dataset(count: Int, prefix: String = "stored", profile: Boolean = true) =
        (1..count).map { server("$prefix-$it", profile = profile) }

    private fun snapshotHosts(repository: VpnServerRepository): List<String?> {
        val list = repository.readLastKnownGoodSnapshot() ?: return emptyList()
        return (0 until list.size()).map { list.get(it)?.hostName }
    }

    // ------------------------------------------------------------ A: empty

    @Test
    fun anEmptyCandidateChangesNothing() = runBlocking {
        val good = dataset(40)
        val repository = VpnServerRepository(cacheDir = tempFolder.newFolder("empty"), fetcher = Fetcherless())
        assertTrue(repository.saveLastKnownGood(good))
        val store = FakeStore(good) { repository.saveLastKnownGood(it) }

        val outcome = ServerListUpdater(fetch = { emptyList() }, store = store).update()

        assertEquals(
            ServerListUpdater.Outcome.Preserved(ServerListUpdater.Reason.EMPTY_RESPONSE, 40),
            outcome,
        )
        assertEquals(0, store.replaceCount)
        assertEquals(0, store.snapshotWrites)
        assertEquals(40, snapshotHosts(repository).size)
    }

    // ------------------------------------- B: one row, nothing stored yet

    @Test
    fun aSingleRowIsRejectedEvenOnAFreshInstall() = runBlocking {
        val repository = VpnServerRepository(cacheDir = tempFolder.newFolder("one"), fetcher = Fetcherless())
        val store = FakeStore(emptyList()) { repository.saveLastKnownGood(it) }

        val outcome = ServerListUpdater(fetch = { listOf(server("only-1")) }, store = store).update()

        assertEquals(
            ServerListUpdater.Outcome.Preserved(ServerListUpdater.Reason.DEGRADED_DATASET, 0),
            outcome,
        )
        assertEquals(0, store.replaceCount)
        assertEquals(0, store.snapshotWrites)
        assertTrue("nothing may be promoted to last-known-good", snapshotHosts(repository).isEmpty())
    }

    // ------------------------------- C/K/L: tiny vs healthy, no poisoning

    @Test
    fun aTinyCandidateCannotReplaceAHealthyListNorTheSnapshot() = runBlocking {
        val good = dataset(100)
        val repository = VpnServerRepository(cacheDir = tempFolder.newFolder("tiny"), fetcher = Fetcherless())
        assertTrue(repository.saveLastKnownGood(good))
        val store = FakeStore(good) { repository.saveLastKnownGood(it) }

        val outcome = ServerListUpdater(fetch = { dataset(3, "tiny") }, store = store).update()

        assertEquals(
            ServerListUpdater.Outcome.Preserved(ServerListUpdater.Reason.DEGRADED_DATASET, 100),
            outcome,
        )
        assertEquals("Room must be untouched", 0, store.replaceCount)
        assertEquals(good.map { it.hostName }, store.items.map { it.hostName })
        assertEquals("the snapshot must be untouched", 0, store.snapshotWrites)
        assertEquals(good.map { it.hostName }, snapshotHosts(repository))
    }

    // --------------------------------------- D/E: plausible and larger sets

    @Test
    fun aModerateReductionIsStoredAndPromoted() = runBlocking {
        val good = dataset(100)
        val repository = VpnServerRepository(cacheDir = tempFolder.newFolder("moderate"), fetcher = Fetcherless())
        assertTrue(repository.saveLastKnownGood(good))
        val store = FakeStore(good) { repository.saveLastKnownGood(it) }

        val outcome = ServerListUpdater(fetch = { dataset(85, "smaller") }, store = store).update()

        assertEquals(ServerListUpdater.Outcome.Updated(85), outcome)
        assertEquals(1, store.replaceCount)
        assertEquals(85, store.items.size)
        assertEquals(1, store.snapshotWrites)
        assertEquals(85, snapshotHosts(repository).size)
        assertEquals("smaller-1", snapshotHosts(repository).first())
    }

    @Test
    fun aLargerCandidateReplacesTheStoredList() = runBlocking {
        val store = FakeStore(dataset(20))
        val outcome = ServerListUpdater(fetch = { dataset(150, "bigger") }, store = store).update()

        assertEquals(ServerListUpdater.Outcome.Updated(150), outcome)
        assertEquals(150, store.items.size)
    }

    // ------------------------------------------ F: profile collapse

    @Test
    fun aPartialOpenVpnProfileCollapseIsRejected() = runBlocking {
        val stored = (1..100).map { server("stored-$it") }
        val candidate = (1..100).map { i ->
            server("candidate-$i", profile = i <= 5)
        }
        val store = FakeStore(stored)

        val outcome = ServerListUpdater(fetch = { candidate }, store = store).update()

        assertEquals(
            ServerListUpdater.Outcome.Preserved(ServerListUpdater.Reason.DEGRADED_DATASET, 100),
            outcome,
        )
        assertEquals(0, store.replaceCount)
        assertEquals(0, store.snapshotWrites)
    }

    @Test
    fun aProfileFreeCandidateIsStillRefusedByTheStage23Rule() = runBlocking {
        val stored = (1..60).map { server("stored-$it") }
        val candidate = (1..60).map { server("candidate-$it", profile = false) }
        val store = FakeStore(stored)

        val outcome = ServerListUpdater(fetch = { candidate }, store = store).update()

        assertEquals(
            ServerListUpdater.Outcome.Preserved(ServerListUpdater.Reason.DEGRADED_DATASET, 60),
            outcome,
        )
        assertEquals(0, store.replaceCount)
    }

    // ------------------------------------------ G: protocol collapse

    @Test
    fun aProtocolCollapseIsRejectedEvenWithTheSameRowCount() = runBlocking {
        val stored = (1..100).map { server("stored-$it", sstp = true) }
        val candidate = (1..100).map { server("candidate-$it", sstp = false) }
        val store = FakeStore(stored)

        val outcome = ServerListUpdater(fetch = { candidate }, store = store).update()

        assertEquals(
            ServerListUpdater.Outcome.Preserved(ServerListUpdater.Reason.DEGRADED_DATASET, 100),
            outcome,
        )
        assertEquals(0, store.replaceCount)
        assertEquals("the stored SSTP list must survive", 100, store.items.size)
    }

    // ------------------------- H: an API-only run must not be misjudged

    @Test
    fun anApiOnlyRunIsAcceptedWithoutSoftEtherOrSstp() = runBlocking {
        // The stored list came from HTML; this run the API answered alone.
        // SoftEther/SSTP/L2TP are impossible from that source, so their
        // absence must not be read as corruption.
        val stored = dataset(100)
        val apiOnly = (1..100).map {
            VPNGateItem(
                hostName = "api-$it",
                ip = "10.0.0.2",
                countryLong = "Japan",
                countryShort = "JP",
                logType = null,
                operator = null,
                message = null,
                openVpnConfigData = "client\nproto tcp\nremote 10.0.0.2 443\n",
                tcpPort = 443,
            )
        }
        val store = FakeStore(stored)
        val capability = LastFetchCapability()
        capability.value = SourceCapability.API_ONLY

        val outcome = ServerListUpdater(
            fetch = { apiOnly },
            fetchCapability = { capability.value },
            store = store,
        ).update()

        assertEquals(ServerListUpdater.Outcome.Updated(100), outcome)
        assertEquals(1, store.replaceCount)
    }

    @Test
    fun theSameApiOnlyRunIsRejectedWhenAProtocolSourceCouldHaveAnswered() = runBlocking {
        // Same candidate, but this run DID have an HTML-shaped source: the
        // missing protocols are then a real collapse.
        val stored = dataset(100)
        val apiOnly = (1..100).map {
            VPNGateItem(
                hostName = "api-$it",
                ip = "10.0.0.2",
                countryLong = "Japan",
                countryShort = "JP",
                logType = null,
                operator = null,
                message = null,
                openVpnConfigData = "client\nproto tcp\nremote 10.0.0.2 443\n",
                tcpPort = 443,
            )
        }
        val store = FakeStore(stored)

        val outcome = ServerListUpdater(
            fetch = { apiOnly },
            fetchCapability = { SourceCapability.FULL },
            store = store,
        ).update()

        assertEquals(
            ServerListUpdater.Outcome.Preserved(ServerListUpdater.Reason.DEGRADED_DATASET, 100),
            outcome,
        )
        assertEquals(0, store.replaceCount)
    }

    // --------------------- I: canonical + mirror, judged AFTER the merge

    @Test
    fun aCanonicalAndMirrorMergeIsJudgedAsOneDataset() = runBlocking {
        val canonical = VpnGateHtmlParser.parseHtml(
            CollectorTestFixtures.load(CollectorTestFixtures.TABLE_SAMPLE),
            SourceIdentity.HTML_TAG,
        )
        val mirror = VpnGateHtmlParser.parseHtml(
            CollectorTestFixtures.load(CollectorTestFixtures.NESTED_SAMPLE),
            "mirror_1",
        )

        val merged = VpnServerMerger.validateServers(
            VpnServerMerger.mergeRecords(canonical + mirror),
        )
        val candidate = vn.unlimit.vpngate.repository.VpnConnectionMapper
            .toConnectionList(merged)
            .toVPNGateItems()

        assertTrue("the fixture merge must produce a dataset", candidate.size >= 20)
        val allSources = merged.flatMap { VpnRecords.sources(it) }.toSet()
        assertTrue(
            "both sources must have contributed: $allSources",
            allSources.containsAll(listOf("html", "mirror_1")),
        )

        // The previous list came from an HTML-shaped source too, so it has no
        // OpenVPN payload either: this test is about the merged size and the
        // post-merge provenance, not about profile coverage.
        val store = FakeStore(dataset(12, "previous", profile = false))
        val outcome = ServerListUpdater(fetch = { candidate }, store = store).update()

        assertEquals(ServerListUpdater.Outcome.Updated(candidate.size), outcome)
        assertEquals(candidate.size, store.items.size)
    }

    // ------------------------------- J: a truncated but valid HTML parse

    @Test
    fun aTruncatedHtmlParseCannotReplaceAHealthyList() = runBlocking {
        val html = CollectorTestFixtures.load(CollectorTestFixtures.TABLE_SAMPLE)
        val truncated = VpnConnectionMapper.toConnectionList(
            VpnServerMerger.validateServers(
                VpnServerMerger.mergeRecords(
                    VpnGateHtmlParser.parseHtml(html, SourceIdentity.HTML_TAG),
                ),
            ),
        ).toVPNGateItems().take(3)

        assertEquals(3, truncated.size)
        val stored = dataset(100)
        val repository = VpnServerRepository(cacheDir = tempFolder.newFolder("trunc"), fetcher = Fetcherless())
        assertTrue(repository.saveLastKnownGood(stored))
        val store = FakeStore(stored) { repository.saveLastKnownGood(it) }

        val outcome = ServerListUpdater(fetch = { truncated }, store = store).update()

        assertEquals(
            ServerListUpdater.Outcome.Preserved(ServerListUpdater.Reason.DEGRADED_DATASET, 100),
            outcome,
        )
        assertEquals(0, store.replaceCount)
        assertEquals(stored.map { it.hostName }, snapshotHosts(repository))
    }

    // ------------------------------- N: snapshot write failure semantics

    @Test
    fun aFailedSnapshotWriteKeepsTheTableValidAndReportsIt() = runBlocking {
        val previous = dataset(60)
        val repository = VpnServerRepository(cacheDir = tempFolder.newFolder("snapfail"), fetcher = Fetcherless())
        assertTrue(repository.saveLastKnownGood(previous))

        // Snapshot writes fail; the previous file must be left alone.
        val store = FakeStore(previous) { false }
        val outcome = ServerListUpdater(fetch = { dataset(70, "fresh") }, store = store).update()

        assertEquals(ServerListUpdater.Outcome.Updated(70, snapshotSaved = false), outcome)
        assertEquals("the table holds the new dataset", 70, store.items.size)
        assertEquals(1, store.replaceCount)
        assertEquals(
            "the previous snapshot must still be intact",
            previous.map { it.hostName },
            snapshotHosts(repository),
        )
    }

    @Test
    fun anAcceptedRefreshUpdatesTableAndSnapshotTogether() = runBlocking {
        val repository = VpnServerRepository(cacheDir = tempFolder.newFolder("accept"), fetcher = Fetcherless())
        val store = FakeStore(emptyList()) { repository.saveLastKnownGood(it) }

        val outcome = ServerListUpdater(fetch = { dataset(120, "accepted") }, store = store).update()

        assertEquals(ServerListUpdater.Outcome.Updated(120), outcome)
        assertEquals(120, store.items.size)
        assertEquals(120, snapshotHosts(repository).size)
        assertEquals("accepted-1", snapshotHosts(repository).first())
    }

    /** The snapshot round trip keeps the per-server rows intact. */
    @Test
    fun theSnapshotRoundTripPreservesTheDataset() = runBlocking {
        val repository = VpnServerRepository(cacheDir = tempFolder.newFolder("roundtrip"), fetcher = Fetcherless())
        val items = dataset(30, "round")
        assertTrue(repository.saveLastKnownGood(items))

        val list = repository.readLastKnownGoodSnapshot()
        assertNotNull(list)
        assertEquals(30, list!!.size())
        assertEquals("round-1", list.get(0)?.hostName)
        assertEquals(
            items.first().hostName, list.get(0)?.hostName,
        )
        assertTrue(
            "no temp file may be left behind",
            tempFolder.root.listFiles()?.none { it.name.endsWith(".tmp") } ?: true,
        )
    }

    /** No fetcher is ever touched by this suite; collection is upstream. */
    private class Fetcherless : vn.unlimit.vpngate.data.remote.HttpFetcher {
        override suspend fun get(
            url: String,
            redirectPolicy: vn.unlimit.vpngate.data.remote.RedirectPolicy?,
        ): vn.unlimit.vpngate.data.remote.HttpResponse? = null
    }
}
