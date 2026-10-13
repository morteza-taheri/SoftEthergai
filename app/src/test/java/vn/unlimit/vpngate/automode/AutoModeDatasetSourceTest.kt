package vn.unlimit.vpngate.automode

import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import vn.unlimit.vpngate.collector.CollectorTestFixtures
import vn.unlimit.vpngate.automode.AutoModeEngine.toCandidate
import vn.unlimit.vpngate.data.remote.HttpFetcher
import vn.unlimit.vpngate.data.remote.HttpResponse
import vn.unlimit.vpngate.data.remote.RedirectPolicy
import vn.unlimit.vpngate.data.model.VpnPortSource
import vn.unlimit.vpngate.data.model.VpnProtocolDefaults
import vn.unlimit.vpngate.db.VPNGateItemDao
import vn.unlimit.vpngate.models.VPNGateConnection
import vn.unlimit.vpngate.models.VPNGateItem
import vn.unlimit.vpngate.repository.ServerListUpdates
import vn.unlimit.vpngate.repository.VpnServerRepository
import java.io.File

/**
 * Stage 2D-B, Auto Connect side.
 *
 * Auto Connect is a consumer. These tests pin that down from the outside: with
 * a populated store it neither downloads nor writes anything, with an empty one
 * it can obtain a dataset, and every dataset it obtains came through the
 * acceptance boundary rather than around it.
 */
class AutoModeDatasetSourceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

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

    private class FixtureFetcher(
        private val html: String? = null,
        private val csv: String? = null,
    ) : HttpFetcher {
        var requests = 0

        override suspend fun get(url: String, redirectPolicy: RedirectPolicy?): HttpResponse? {
            requests++
            return when {
                url.contains("/api/iphone") ->
                    csv?.let { HttpResponse(url, url, 200, "text/csv", it) }
                url.contains("sites.aspx") -> null
                else -> html?.let { HttpResponse(url, url, 200, "text/html", it) }
            }
        }
    }

    private fun repository(fetcher: HttpFetcher, name: String) =
        VpnServerRepository(fetcher = fetcher, cacheDir = tempFolder.newFolder(name))

    /** No network at all, so the repository can only fall back. */
    private fun offlineFetcher() = object : HttpFetcher {
        override suspend fun get(url: String, redirectPolicy: RedirectPolicy?): HttpResponse? = null
    }

    private fun accepted(count: Int, prefix: String) = (1..count).map { i ->
        VPNGateItem(
            hostName = "$prefix-$i",
            ip = "10.9.0.$i",
            countryLong = "Japan",
            countryShort = "JP",
            logType = null,
            operator = null,
            message = null,
            openVpnConfigData = "client\n",
            tcpPort = 443,
            seTcpPort = 443,
        )
    }

    // ================================================ A: populated Room

    @Test
    fun aPopulatedRoomIsUsedAsIsWithNoDownloadAndNoWrite() = runBlocking {
        val stored = accepted(25, "stored")
        val dao = FakeDao(stored)
        val fetcher = FixtureFetcher(
            CollectorTestFixtures.load(CollectorTestFixtures.TABLE_SAMPLE),
            CollectorTestFixtures.load(CollectorTestFixtures.API_SAMPLE),
        )

        val candidates = AutoModeEngine
            .gatedSource { ServerListUpdates.forAcquisition(repository(fetcher, "a"), dao) }
            .dataset()

        val rows = (0 until candidates!!.size()).map { candidates.get(it)?.hostName }
        assertEquals(stored.map { it.hostName }, rows)
        assertEquals("Auto Connect must not write", 0, dao.replaceAllCalls)
        assertEquals("Auto Connect must not download", 0, fetcher.requests)
        assertEquals("the stored dataset must be untouched", stored, dao.items)
    }

    // ======================================= empty Room: acquisition works

    @Test
    fun anEmptyRoomIsFilledThroughTheAcceptanceBoundary() = runBlocking {
        val dao = FakeDao()
        val fetcher = FixtureFetcher(
            CollectorTestFixtures.load(CollectorTestFixtures.TABLE_SAMPLE),
            CollectorTestFixtures.load(CollectorTestFixtures.API_SAMPLE),
        )

        val candidates = AutoModeEngine
            .gatedSource { ServerListUpdates.forAcquisition(repository(fetcher, "acq"), dao) }
            .dataset()

        assertTrue("Auto Connect must get candidates on a fresh install", candidates!!.size() > 0)
        assertEquals("the write came from the updater, exactly once", 1, dao.replaceAllCalls)
        assertEquals(
            "candidates must be the accepted, stored dataset",
            dao.items.map { it.hostName },
            (0 until candidates.size()).map { candidates.get(it)?.hostName },
        )
    }

    @Test
    fun anEmptyRoomWithNothingUsableYieldsNoCandidates() = runBlocking {
        val dao = FakeDao()
        val offline = offlineFetcher()

        val candidates = AutoModeEngine
            .gatedSource { ServerListUpdates.forAcquisition(repository(offline, "empty"), dao) }
            .dataset()

        assertTrue("no data may be invented", candidates == null || candidates.size() == 0)
        assertEquals(0, dao.replaceAllCalls)
    }

    // ============================================= candidate mapping intact

    @Test
    fun candidatesCarryTheStoredPerServerFacts() = runBlocking {
        val dao = FakeDao(accepted(3, "map"))
        val source = AutoModeEngine.gatedSource {
            ServerListUpdates.forAcquisition(repository(offlineFetcher(), "map"), dao)
        }

        val candidates = AutoModeEngine.defaultServers(source)

        assertEquals(3, candidates.size)
        val first = candidates.first { it.hostname == "map-1" }
        assertEquals("10.9.0.1", first.ip)
        assertTrue("SoftEther TCP must survive the mapping", first.seTcpPort > 0)
        assertTrue("OpenVPN TCP must survive the mapping", first.openVpnTcpPort > 0)
    }

    /**
     * Stage 2F-F.2 (Q-6): the candidate must carry MS-SSTP's port and its
     * provenance verbatim, and the dialable port must come from the
     * connection's own existing rule rather than being re-derived here.
     */
    @Test
    fun candidatesCarrySstpPortAndItsProvenance() {
        fun candidate(
            supported: Boolean,
            port: Int,
            source: String,
        ) = sstpConnection(
            ip = "10.9.0.9",
            sstp = supported,
            sstpPort = port,
            sstpSource = source,
        )

        // The source printed the port: kept, and dialable at that exact port.
        val explicit = candidate(true, 8443, VpnPortSource.EXPLICIT).toCandidate()
        assertEquals(8443, explicit.sstpPort)
        assertEquals(VpnPortSource.EXPLICIT, explicit.sstpPortSource)
        assertEquals(8443, explicit.sstpConnectPort)

        // Confirmed but unpublished: provenance DEFAULT survives and the
        // protocol default becomes the dialable port.
        val default = candidate(true, 0, VpnPortSource.DEFAULT).toCandidate()
        assertEquals(0, default.sstpPort)
        assertEquals(VpnPortSource.DEFAULT, default.sstpPortSource)
        assertEquals(VpnProtocolDefaults.SSTP_PORT, default.sstpConnectPort)

        // Nothing known: UNKNOWN survives and there is no dialable port. This
        // is the state Auto Connect must refuse.
        val unknown = candidate(true, 0, VpnPortSource.UNKNOWN).toCandidate()
        assertEquals(VpnPortSource.UNKNOWN, unknown.sstpPortSource)
        assertEquals(0, unknown.sstpConnectPort)

        // Unsupported: no capability and no port, whatever the record claims.
        val unsupported = candidate(false, 8443, VpnPortSource.EXPLICIT).toCandidate()
        assertTrue(!unsupported.sstpSupported)
        assertEquals(0, unsupported.sstpConnectPort)

        // A legacy/garbage provenance must be normalized, never passed through
        // as something Auto Connect could mistake for a known port.
        val garbage = candidate(true, 0, "").toCandidate()
        assertEquals(VpnPortSource.UNKNOWN, garbage.sstpPortSource)
        assertEquals(0, garbage.sstpConnectPort)
    }

    private fun sstpConnection(
        ip: String,
        sstp: Boolean,
        sstpPort: Int,
        sstpSource: String,
    ) = VPNGateConnection().apply {
        this.ip = ip
        this.hostName = "sstp-host"
        this.isSSTPSupport = if (sstp) 1 else 0
        this.sstpPort = sstpPort
        this.sstpPortSource = sstpSource
    }

    @Test
    fun aNullDatasetBecomesNoCandidates() = runBlocking {
        val candidates = AutoModeEngine.defaultServers(
            AutoModeEngine.ServerDatasetSource { null },
        )

        assertTrue(candidates.isEmpty())
    }

    // ================================ source invariants of the writer rule

    @Test
    fun autoModeEngineHoldsNoDaoAtAll() {
        val source = sourceOf("src/main/java/vn/unlimit/vpngate/automode/AutoModeEngine.kt")

        assertTrue(
            "Auto Connect must not reference the DAO at all",
            !source.contains("vpnGateItemDao"),
        )
        assertTrue(
            "Auto Connect must not reference replaceAll",
            !source.contains("replaceAll"),
        )
        assertTrue(
            "Auto Connect must not assign connectionsCache for persistence",
            !source.contains("connectionsCache"),
        )
    }

    @Test
    fun theConnectionsCacheSetterCannotWriteServerRows() {
        val source = sourceOf("src/main/java/vn/unlimit/vpngate/utils/DataUtil.kt")
        val setter = source.substringAfter("var connectionsCache: VPNGateConnectionList?")
            .substringAfter("set(value) {")
            .substringBefore("\n        }")

        assertTrue(
            "the setter must not reach the DAO",
            !setter.contains("vpnGateItemDao"),
        )
        assertTrue(
            "the setter must not replace stored rows",
            !setter.contains("replaceAll"),
        )
        assertTrue(
            "the setter must not build server rows",
            !setter.contains("toVPNGateItems"),
        )
    }

    /** Reads a production source file, relative to the module directory. */
    private fun sourceOf(relativePath: String): String {
        for (path in listOf(relativePath, "../$relativePath", "app/$relativePath")) {
            val file = File(path)
            if (file.isFile) return file.readText(Charsets.UTF_8)
        }
        error("source file not found: $relativePath (cwd=${File(".").absolutePath})")
    }
}
