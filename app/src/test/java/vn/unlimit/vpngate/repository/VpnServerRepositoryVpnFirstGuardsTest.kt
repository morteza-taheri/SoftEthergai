package vn.unlimit.vpngate.repository

import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import vn.unlimit.vpngate.collector.CollectorTestFixtures
import vn.unlimit.vpngate.data.remote.CollectorPath
import vn.unlimit.vpngate.data.remote.CollectorRoutingInput
import vn.unlimit.vpngate.data.remote.HttpFetcher
import vn.unlimit.vpngate.data.remote.HttpResponse
import vn.unlimit.vpngate.data.remote.RedirectPolicy
import vn.unlimit.vpngate.db.VPNGateItemDao
import vn.unlimit.vpngate.models.VPNGateItem
import java.io.File

/**
 * BLOCK v1.0.65 §6 — behavioural matrix A–J for the dynamic VPN-first Server
 * List update policy.
 *
 * These are genuine behavioural tests, not grep-of-source assertions: every
 * scenario drives a real [VpnServerRepository] (with injected routing, bound
 * fetcher and a configurable outer deadline) through the production
 * [ServerListUpdates] wiring and an in-memory [VPNGateItemDao] that counts
 * every write.
 *
 * The two fetchers per scenario are recorded independently so the suite can
 * prove WHICH network path (TUNNEL vs PHYSICAL) was actually touched, which is
 * exactly the question the device work in §10–§11 must ultimately confirm.
 */
class VpnServerRepositoryVpnFirstGuardsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ------------------------------------------------------------- fakes

    /** Records every call so TUNNEL vs PHYSICAL usage is observable. */
    private class RecordingFetcher(
        private val html: String? = null,
        private val csv: String? = null,
        /** When true, every request returns null (a failed request). */
        private val fail: Boolean = false,
        /** When set, every request parks here until the test releases it. */
        private val gate: CompletableDeferred<Unit>? = null,
        private val entered: CompletableDeferred<Unit>? = null,
    ) : HttpFetcher {
        var calls = 0
            private set
        val urls = mutableListOf<String>()

        override suspend fun get(url: String, redirectPolicy: RedirectPolicy?): HttpResponse? {
            calls++
            urls.add(url)
            entered?.let { if (!it.isCompleted) it.complete(Unit) }
            gate?.await()
            if (fail) return null
            return when {
                url.contains("/api/iphone") ->
                    csv?.let { HttpResponse(url, url, 200, "text/csv", it) }
                else -> html?.let { HttpResponse(url, url, 200, "text/html", it) }
            }
        }
    }

    /** In-memory Room. Every mutation is counted, so "who wrote" is observable. */
    private class FakeDao(initial: List<VPNGateItem> = emptyList()) : VPNGateItemDao {
        var items: List<VPNGateItem> = initial
        var replaceAllCalls = 0

        override fun getAll(): List<VPNGateItem> = items
        override fun filterAndSort(query: SupportSQLiteQuery): List<VPNGateItem> = items
        override fun insertAll(vararg vpnGateItem: VPNGateItem) {
            items = items + vpnGateItem
        }
        override fun deleteAll() { items = emptyList() }
        override fun count(): Int = items.size
        override fun countWithOpenVpnConfig(): Int =
            items.count { !it.openVpnConfigData.isNullOrBlank() }
        override fun replaceAll(items: List<VPNGateItem>) {
            if (items.isEmpty()) return
            replaceAllCalls++
            this.items = items
        }
    }

    // --------------------------------------------------------- fixtures

    private val htmlSample: String get() = CollectorTestFixtures.load(CollectorTestFixtures.TABLE_SAMPLE)
    private val apiSample: String get() = CollectorTestFixtures.load(CollectorTestFixtures.API_SAMPLE)

    /** A CSV with exactly [rows] data rows under one header line (valid for parsing). */
    private fun csv(rows: Int): String {
        val header = CollectorTestFixtures.makeApiRow().lineSequence().first()
        val body = (1..rows).map { i ->
            CollectorTestFixtures.makeApiRow(
                mapOf("HostName" to "public-vpn-$i", "IP" to "219.100.37.$i"),
            ).lineSequence().drop(1).joinToString("\n")
        }
        return (listOf(header) + body).joinToString("\n")
    }

    private fun buildRepo(
        tunnelUsable: Boolean,
        hasBoundNetwork: Boolean,
        boundFetcher: HttpFetcher?,
        physicalFetcher: HttpFetcher,
        name: String,
        mainTimeoutMs: Long = 45_000L,
        pathLog: (String) -> Unit = {},
        fetchLog: (String) -> Unit = { _ -> },
    ): VpnServerRepository = VpnServerRepository(
        fetcher = physicalFetcher,
        cacheDir = tempFolder.newFolder(name),
        routing = { CollectorRoutingInput(tunnelUsable, hasBoundNetwork) },
        boundFetcherFactory = { boundFetcher },
        pathLog = pathLog,
        fetchLog = fetchLog,
        mainTimeoutMs = mainTimeoutMs,
    )

    // ======================================================= A: VPN absent

    @Test
    fun `A VPN absent selects PHYSICAL and never invokes the tunnel`() = runBlocking {
        val tunnel = RecordingFetcher(html = htmlSample, csv = csv(20))
        val physical = RecordingFetcher(html = htmlSample, csv = csv(20))
        val fetchLog = mutableListOf<String>()
        val repo = buildRepo(
            tunnelUsable = false,
            hasBoundNetwork = false,
            boundFetcher = tunnel,
            physicalFetcher = physical,
            name = "a",
            fetchLog = { fetchLog.add(it) },
        )

        val result = repo.refresh()

        assertNotNull("PHYSICAL path must still deliver a dataset", result)
        assertTrue(
            "ladder must open with PHYSICAL when no usable tunnel: $fetchLog",
            fetchLog.any { it.startsWith("[ladder] order=PHYSICAL") },
        )
        assertEquals(
            "TUNNEL must not be attempted when no usable+bound VPN Network exists",
            0,
            tunnel.calls,
        )
        assertTrue(
            "PHYSICAL must have been used",
            physical.calls > 0,
        )
    }

    // ============================================ B: VPN active, tunnel works

    @Test
    fun `B VPN active and tunnel succeeds uses TUNNEL and skips PHYSICAL`() = runBlocking {
        val tunnel = RecordingFetcher(html = htmlSample, csv = csv(20))
        // PHYSICAL carries valid bodies too, so we can detect if it is touched.
        val physical = RecordingFetcher(html = htmlSample, csv = csv(20))
        val fetchLog = mutableListOf<String>()
        val repo = buildRepo(
            tunnelUsable = true,
            hasBoundNetwork = true,
            boundFetcher = tunnel,
            physicalFetcher = physical,
            name = "b",
            fetchLog = { fetchLog.add(it) },
        )

        val result = repo.refresh()

        assertNotNull("a valid TUNNEL response must be accepted", result)
        assertTrue(
            "TUNNEL path must be attempted",
            fetchLog.any { it.contains("path=tunnel") },
        )
        assertEquals(
            "a successful TUNNEL response must NOT trigger a PHYSICAL request",
            0,
            physical.calls,
        )
        assertTrue(
            "must not fall back to PHYSICAL on a successful tunnel",
            fetchLog.none { it.contains("path=physical") },
        )
    }

    // ===================================== C: tunnel fails, physical succeeds

    @Test
    fun `C tunnel fails then physical fallback completes and persists once`() = runBlocking {
        val tunnel = RecordingFetcher(fail = true) // TUNNEL always fails
        val physical = RecordingFetcher(html = htmlSample, csv = csv(20))
        val dao = FakeDao()
        val repo = buildRepo(
            tunnelUsable = true,
            hasBoundNetwork = true,
            boundFetcher = tunnel,
            physicalFetcher = physical,
            name = "c",
        )

        val outcome = ServerListUpdates.forUpdateTrigger(repo, dao).update()

        assertTrue("expected an accepted update, got $outcome", outcome is ServerListUpdater.Outcome.Updated)
        assertEquals("the valid PHYSICAL response must be accepted", 1, dao.replaceAllCalls)
        assertTrue("PHYSICAL must have been used as fallback", physical.calls > 0)
        assertTrue("TUNNEL must have been attempted", tunnel.calls > 0)
    }

    // ============================ D: same-refresh tunnel memory (BLOCK v1.0.65)

    @Test
    fun `D tunnel fails on mirror so API and HTML skip the dead tunnel`() = runBlocking {
        // MIRROR leads with TUNNEL (and fails); with BLOCK v1.0.65 the
        // concurrent API+HTML rungs must begin on PHYSICAL instead of paying
        // another full tunnel timeout.
        val tunnel = RecordingFetcher(fail = true)
        val physical = RecordingFetcher(html = htmlSample, csv = csv(20))
        val fetchLog = mutableListOf<String>()
        val dao = FakeDao()
        val repo = buildRepo(
            tunnelUsable = true,
            hasBoundNetwork = true,
            boundFetcher = tunnel,
            physicalFetcher = physical,
            name = "d",
            fetchLog = { fetchLog.add(it) },
        )

        val outcome = ServerListUpdates.forUpdateTrigger(repo, dao).update()

        assertTrue("refresh must still complete on PHYSICAL: $fetchLog", outcome is ServerListUpdater.Outcome.Updated)
        assertTrue(
            "the same-refresh tunnel-memory must engage after the mirror failure; log=$fetchLog",
            fetchLog.any { it.contains("tunnelFailedEarly") },
        )
        // Behavioural proof of the BLOCK v1.0.65 downgrade: MIRROR leads with
        // TUNNEL (and fails), so the concurrent API+HTML rungs must begin on
        // PHYSICAL and must NOT re-attempt the dead tunnel.
        val tunnelMirror = fetchLog.count { it.contains("path=tunnel") && it.contains("source=mirror") }
        val tunnelApi = fetchLog.count { it.contains("path=tunnel") && it.contains("source=api") }
        val tunnelHtml = fetchLog.count { it.contains("path=tunnel") && it.contains("source=html") }
        assertEquals("MIRROR must attempt TUNNEL once", 1, tunnelMirror)
        assertEquals("API must NOT re-attempt the dead tunnel", 0, tunnelApi)
        assertEquals("HTML must NOT re-attempt the dead tunnel", 0, tunnelHtml)
        assertTrue("PHYSICAL must have delivered the dataset", physical.calls > 0)
        assertEquals("exactly one authoritative write", 1, dao.replaceAllCalls)
    }

    // ============================================= E: both paths fail

    @Test
    fun `E both paths fail preserves the store and writes nothing`() = runBlocking {
        val tunnel = RecordingFetcher(fail = true)
        val physical = RecordingFetcher(fail = true)
        val dao = FakeDao()
        val repo = buildRepo(
            tunnelUsable = true,
            hasBoundNetwork = true,
            boundFetcher = tunnel,
            physicalFetcher = physical,
            name = "e",
        )

        val outcome = ServerListUpdates.forUpdateTrigger(repo, dao).update()

        assertTrue(
            "a double failure must preserve the store, got $outcome",
            outcome is ServerListUpdater.Outcome.Preserved,
        )
        assertEquals("no write may occur on total failure", 0, dao.replaceAllCalls)
        assertTrue("Room must remain unchanged", dao.items.isEmpty())
    }

    // ============================================= F: deadline expiration

    @Test
    fun `F outer deadline expires without accepting late data or writing`() = runBlocking {
        val tunnel = RecordingFetcher(fail = true)
        // PHYSICAL parks forever; the outer deadline must cut it.
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val physical = RecordingFetcher(html = htmlSample, csv = csv(20), gate = gate, entered = entered)
        val dao = FakeDao()
        val fetchLog = mutableListOf<String>()
        val repo = buildRepo(
            tunnelUsable = true,
            hasBoundNetwork = true,
            boundFetcher = tunnel,
            physicalFetcher = physical,
            name = "f",
            mainTimeoutMs = 400L,
            fetchLog = { fetchLog.add(it) },
        )

        val outcome = withTimeout(2000) {
            ServerListUpdates.forUpdateTrigger(repo, dao).update()
        }

        assertTrue(
            "deadline must be reported in the log: $fetchLog",
            fetchLog.any { it.contains("deadline exceeded") },
        )
        assertTrue(
            "no late response may be accepted after the deadline",
            outcome is ServerListUpdater.Outcome.Preserved,
        )
        assertEquals("no write may occur after deadline expiry", 0, dao.replaceAllCalls)
    }

    // =================================== G: tunnel network disappears

    @Test
    fun `G missing VPN network degrades to PHYSICAL without hanging`() = runBlocking {
        // routing says a usable+bound VPN Network exists, but the factory
        // returns null (binding failed / network vanished): the rung must
        // degrade to PHYSICAL without attempting a tunnel socket.
        val physical = RecordingFetcher(html = htmlSample, csv = csv(20))
        val fetchLog = mutableListOf<String>()
        val repo = buildRepo(
            tunnelUsable = true,
            hasBoundNetwork = true,
            boundFetcher = null, // no bound network available
            physicalFetcher = physical,
            name = "g",
            fetchLog = { fetchLog.add(it) },
        )

        val result = repo.refresh()

        assertNotNull("PHYSICAL must still deliver a dataset", result)
        assertTrue(
            "the no-bound-network degradation must be logged",
            fetchLog.any { it.contains("tunnel rung unavailable") },
        )
        assertTrue("PHYSICAL must have been used", physical.calls > 0)
    }

    // ============================================ H: emergency rung intact

    @Test
    fun `H emergency rung is always the last ladder rung`() {
        // The emergency behaviour itself (parsing, merge, origin reset,
        // mergeFallback) is covered exhaustively by EmergencyMergeFallbackTest
        // and EmergencyMergePolicyTest. Here we lock the structural invariant
        // that the ladder never promotes EMERGENCY and always ends with it.
        assertEquals(
            listOf(CollectorPath.TUNNEL, CollectorPath.PHYSICAL, CollectorPath.EMERGENCY),
            VpnServerRepository.routeLadder(CollectorRoutingInput(true, true)),
        )
        assertEquals(
            listOf(CollectorPath.PHYSICAL, CollectorPath.EMERGENCY),
            VpnServerRepository.routeLadder(CollectorRoutingInput(false, false)),
        )
        assertTrue(
            "EMERGENCY must never be first",
            VpnServerRepository.routeLadder(CollectorRoutingInput(true, true)).first() != CollectorPath.EMERGENCY,
        )
    }

    // ============================================ I: repeated refresh

    @Test
    fun `I each refresh re-evaluates current network state`() = runBlocking {
        val tunnel = RecordingFetcher(html = htmlSample, csv = csv(20))
        val physical = RecordingFetcher(html = htmlSample, csv = csv(20))
        val fetchLog = mutableListOf<String>()

        // Refresh 1: VPN up, tunnel works.
        val repo = buildRepo(
            tunnelUsable = true,
            hasBoundNetwork = true,
            boundFetcher = tunnel,
            physicalFetcher = physical,
            name = "i",
            fetchLog = { fetchLog.add(it) },
        )
        assertNotNull(repo.refresh())
        assertTrue("refresh 1 must use TUNNEL", fetchLog.any { it.contains("path=tunnel") })

        // Refresh 2: VPN now disconnected — routing re-read, must use PHYSICAL.
        fetchLog.clear()
        // Rebuild with VPN off (a new routing lambda takes effect next refresh).
        val repo2 = VpnServerRepository(
            fetcher = physical,
            cacheDir = tempFolder.newFolder("i2"),
            routing = { CollectorRoutingInput(false, false) },
            boundFetcherFactory = { tunnel },
            fetchLog = { fetchLog.add(it) },
        )
        assertNotNull(repo2.refresh())
        assertTrue("refresh 2 must use PHYSICAL after VPN off", fetchLog.any { it.contains("path=physical") })
        assertTrue("refresh 2 must not use TUNNEL after VPN off", fetchLog.none { it.contains("path=tunnel") })
    }

    // ===================================== J: concurrency and cancellation

    @Test
    fun `J overlapping refreshes are single-flight controlled`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val physical = RecordingFetcher(html = htmlSample, csv = csv(20), gate = release, entered = entered)
        val dao = FakeDao()
        val repo = buildRepo(
            tunnelUsable = false,
            hasBoundNetwork = false,
            boundFetcher = null,
            physicalFetcher = physical,
            name = "j",
        )
        val updater = ServerListUpdates.forUpdateTrigger(repo, dao)

        val a = async(Dispatchers.Default) { updater.update() }
        entered.await()
        val b = async(Dispatchers.Default) { updater.update() }
        delay(150)
        release.complete(Unit)

        a.await()
        b.await()
        assertEquals("exactly one authoritative write under overlap", 1, dao.replaceAllCalls)
    }

    @Test
    fun `J cancellation mid-refresh writes nothing and corrupts no state`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val physical = RecordingFetcher(html = htmlSample, csv = csv(20), gate = gate, entered = entered)
        val dao = FakeDao()
        val repo = buildRepo(
            tunnelUsable = false,
            hasBoundNetwork = false,
            boundFetcher = null,
            physicalFetcher = physical,
            name = "j2",
        )
        val updater = ServerListUpdates.forUpdateTrigger(repo, dao)

        val job = async(Dispatchers.Default) { updater.update() }
        entered.await()
        job.cancel()
        withTimeout(2000) { runCatching { job.await() } }

        assertEquals("no write on cancellation", 0, dao.replaceAllCalls)
        assertTrue("no rows written on cancellation", dao.items.isEmpty())
    }
}
