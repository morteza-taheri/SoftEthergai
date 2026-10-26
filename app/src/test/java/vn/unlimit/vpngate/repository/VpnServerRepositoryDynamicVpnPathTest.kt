package vn.unlimit.vpngate.repository

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import vn.unlimit.vpngate.data.remote.CollectorPath
import vn.unlimit.vpngate.data.remote.CollectorRoutingInput
import vn.unlimit.vpngate.data.remote.HttpFetcher
import vn.unlimit.vpngate.data.remote.RedirectPolicy

/**
 * BLOCK v1.0.64 — DYNAMIC VPN-AWARE SERVER LIST UPDATER: automated routing
 * test matrix (A–Q).
 *
 * Pins the deterministic VPN-first policy introduced in BLOCK v1.0.64:
 *  - When a usable, bound VPN Network exists, the TUNNEL rung is the PRIMARY
 *    and is genuinely attempted; PHYSICAL is the fallback; EMERGENCY is last.
 *  - When no usable+bound VPN Network exists, the TUNNEL rung is not offered
 *    and PHYSICAL leads.
 *  - Both rung outcomes are recorded independently in [VpnServerRepository.PathMemory].
 *  - No process-wide binding, no VpnService.protect() change, bounded timeouts,
 *    no duplicate writes, no stale-network hang.
 *
 * No Android, no tracker, no socket: the physical and bound fetchers are fake
 * tokens the `fetch` lambda branches on, so both outcomes are simulated without
 * any network. Source-shape tests (J/K/L) read the production source directly to
 * pin the wiring that cannot be observed from a pure function.
 */
class VpnServerRepositoryDynamicVpnPathTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val collectorLogs = CopyOnWriteArrayList<String>()
    private val fetchLogs = CopyOnWriteArrayList<String>()

    /** A fetcher token; the `fetch` lambda branches on its reference, not its body. */
    private class TokenFetcher : HttpFetcher {
        override suspend fun get(url: String, redirectPolicy: RedirectPolicy?): Nothing? = null
    }

    private val physicalFetcher = TokenFetcher()
    private val tunnelFetcher = TokenFetcher()

    private typealias PathMemory = VpnServerRepository.Companion.PathMemory

    private fun ladder(
        tunnelUsable: Boolean,
        hasBoundNetwork: Boolean,
        memory: PathMemory? = null,
    ) = VpnServerRepository.routeLadder(
        CollectorRoutingInput(tunnelUsable = tunnelUsable, hasBoundNetwork = hasBoundNetwork),
        memory,
    )

    private fun repoWith(
        routingInput: CollectorRoutingInput,
        memory: PathMemory = PathMemory(),
    ): Pair<VpnServerRepository, PathMemory> {
        val r = VpnServerRepository(
            fetcher = physicalFetcher,
            cacheDir = tempFolder.newFolder(),
            routing = { routingInput },
            boundFetcherFactory = { tunnelFetcher },
            pathLog = { collectorLogs.add(it) },
            fetchLog = { fetchLogs.add(it) },
            pathMemory = memory,
        )
        return r to memory
    }

    private fun sourceOf(relativePath: String): String {
        for (path in listOf(relativePath, "../$relativePath", "app/$relativePath")) {
            val file = File(path)
            if (file.isFile) return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
        }
        error("source file not found: $relativePath (cwd=${File(".").absolutePath})")
    }

    // ── A: no usable VPN network → PHYSICAL leads, TUNNEL not offered ────────
    @Test
    fun `A no usable VPN network offers only PHYSICAL then EMERGENCY`() {
        assertEquals(
            listOf(CollectorPath.PHYSICAL, CollectorPath.EMERGENCY),
            ladder(tunnelUsable = false, hasBoundNetwork = false),
        )
    }

    // ── B: usable + bound VPN → TUNNEL leads the ladder ──────────────────────
    @Test
    fun `B usable bound VPN makes TUNNEL the primary rung`() {
        assertEquals(
            listOf(CollectorPath.TUNNEL, CollectorPath.PHYSICAL, CollectorPath.EMERGENCY),
            ladder(tunnelUsable = true, hasBoundNetwork = true),
        )
    }

    // ── C: usable tunnel but no bound network → no TUNNEL rung ───────────────
    @Test
    fun `C usable tunnel without a bound network adds no TUNNEL rung`() {
        val result = ladder(tunnelUsable = true, hasBoundNetwork = false)
        assertEquals(listOf(CollectorPath.PHYSICAL, CollectorPath.EMERGENCY), result)
        assertFalse("TUNNEL must not be offered without a Network to bind", result.contains(CollectorPath.TUNNEL))
    }

    // ── D: bound network but dead tunnel → no TUNNEL rung ────────────────────
    @Test
    fun `D bound network with a dead tunnel adds no TUNNEL rung`() {
        val result = ladder(tunnelUsable = false, hasBoundNetwork = true)
        assertEquals(listOf(CollectorPath.PHYSICAL, CollectorPath.EMERGENCY), result)
        assertFalse("TUNNEL must not be offered for a dead tunnel", result.contains(CollectorPath.TUNNEL))
    }

    // ── E: refresh() with usable+bound tunnel → TUNNEL first, PHYSICAL fallback
    @Test
    fun `E refresh attempts TUNNEL first and falls back to PHYSICAL on tunnel failure`() = runBlocking {
        collectorLogs.clear(); fetchLogs.clear()
        repoWith(CollectorRoutingInput(tunnelUsable = true, hasBoundNetwork = true)).first.refresh()

        val firstAttempt = fetchLogs.first { it.startsWith("[fetch]") }
        assertTrue("the very first attempt must be TUNNEL: $fetchLogs", firstAttempt.contains("path=tunnel"))
        assertTrue(
            "ladder order must be TUNNEL,PHYSICAL,EMERGENCY: $fetchLogs",
            fetchLogs.any { it == "[ladder] order=TUNNEL,PHYSICAL,EMERGENCY" },
        )
        // BLOCK v1.0.65: after MIRROR's TUNNEL attempt fails, API/HTML are
        // downgraded to a PHYSICAL primary (a plain `path=physical source=...`
        // line). A PHYSICAL line is therefore legitimate either as that
        // downgrade or as a fallback after a TUNNEL failure — but MIRROR itself
        // must never lead with PHYSICAL while the tunnel is offered.
        assertTrue(
            "the dead tunnel must downgrade API/HTML to PHYSICAL: $fetchLogs",
            fetchLogs.any { it.contains("tunnelFailedEarly") },
        )
        assertTrue(
            "MIRROR must not lead with PHYSICAL while TUNNEL is offered: $fetchLogs",
            fetchLogs.none { it.startsWith("[fetch] path=physical source=mirror") },
        )
        assertTrue(
            "every PHYSICAL attempt must follow the TUNNEL failure: $fetchLogs",
            fetchLogs.filter { it.startsWith("[fetch] path=physical") }
                .all {
                    it.contains("after tunnel failed") ||
                        it.contains("failed (no result)") ||
                        it.startsWith("[fetch] path=physical source=api") ||
                        it.startsWith("[fetch] path=physical source=html")
                },
        )
    }

    // ── F: TUNNEL primary succeeds → recorded, no PHYSICAL attempt ───────────
    @Test
    fun `F a successful TUNNEL primary is recorded and does not touch PHYSICAL`() = runBlocking {
        collectorLogs.clear(); fetchLogs.clear()
        val (r, mem) = repoWith(CollectorRoutingInput(tunnelUsable = true, hasBoundNetwork = true))
        r.fetchOnChosenPath<String>(
            sourceTag = "html",
            path = CollectorPath.TUNNEL,
            boundFetcher = tunnelFetcher,
            succeeded = { true },
            physicalFetcher = physicalFetcher,
        ) { f -> if (f == tunnelFetcher) "body" else null }

        assertEquals(listOf("[collector] path=tunnel source=html"), collectorLogs.toList())
        assertFalse("TUNNEL success must clear its cooldown", mem.isCoolingDown(CollectorPath.TUNNEL))
        assertEquals("TUNNEL must be the preferred path after success", CollectorPath.TUNNEL, mem.preferred())
    }

    // ── G: TUNNEL fails + PHYSICAL succeeds → both outcomes recorded ─────────
    @Test
    fun `G tunnel failure and physical success are both recorded independently`() = runBlocking {
        val (r, mem) = repoWith(CollectorRoutingInput(tunnelUsable = true, hasBoundNetwork = true))
        r.fetchOnChosenPath<String>(
            sourceTag = "html",
            path = CollectorPath.TUNNEL,
            boundFetcher = tunnelFetcher,
            succeeded = { true },
            physicalFetcher = physicalFetcher,
        ) { f ->
            when (f) {
                tunnelFetcher -> null
                physicalFetcher -> "PHYSICAL_OK"
                else -> null
            }
        }

        assertTrue("TUNNEL failure must be recorded", mem.isCoolingDown(CollectorPath.TUNNEL))
        assertEquals("PHYSICAL success must be the preferred path", CollectorPath.PHYSICAL, mem.preferred())
    }

    // ── H: both rungs fail → both recorded as failures (independent) ─────────
    @Test
    fun `H both rungs failing records both as failures independently`() = runBlocking {
        val (r, mem) = repoWith(CollectorRoutingInput(tunnelUsable = true, hasBoundNetwork = true))
        r.fetchOnChosenPath<String>(
            sourceTag = "html",
            path = CollectorPath.TUNNEL,
            boundFetcher = tunnelFetcher,
            succeeded = { true },
            physicalFetcher = physicalFetcher,
        ) { null }

        assertTrue("TUNNEL failure must be recorded", mem.isCoolingDown(CollectorPath.TUNNEL))
        assertTrue("PHYSICAL failure must be recorded", mem.isCoolingDown(CollectorPath.PHYSICAL))
        assertNull("no path should be preferred after both fail", mem.preferred())
    }

    // ── I: EMERGENCY always last, never promoted, never a fallback rung ──────
    @Test
    fun `I EMERGENCY is always last, never promoted, never a fallback`() {
        val seeds = listOf<(PathMemory) -> Unit>(
            { },
            { it.recordFailure(CollectorPath.PHYSICAL) },
            { it.recordSuccess(CollectorPath.TUNNEL) },
            { it.recordFailure(CollectorPath.TUNNEL) },
            { it.recordFailure(CollectorPath.EMERGENCY) },
        )
        for (tu in listOf(true, false)) {
            for (hb in listOf(true, false)) {
                for (seed in seeds) {
                    val mem = PathMemory()
                    seed(mem)
                    val result = ladder(tu, hb, mem)
                    assertEquals("EMERGENCY must be last ($tu,$hb)", CollectorPath.EMERGENCY, result.last())
                    assertEquals("EMERGENCY exactly once ($tu,$hb)", 1, result.count { it == CollectorPath.EMERGENCY })
                    assertTrue(
                        "EMERGENCY must not appear before the last position ($tu,$hb)",
                        CollectorPath.EMERGENCY !in result.dropLast(1),
                    )
                }
            }
        }
    }

    // ── J: no process-wide binding (bindProcessToNetwork forbidden) ──────────
    @Test
    fun `J no process-wide network binding is introduced`() {
        val src = sourceOf("src/main/java/vn/unlimit/vpngate/repository/VpnServerRepository.kt")
        assertFalse(
            "the ladder/refresh/fetch path must not call bindProcessToNetwork: $src",
            src.contains(".bindProcessToNetwork"),
        )
    }

    // ── K: no VpnService.protect() change in the routing path ─────────────────
    @Test
    fun `K no VpnService protect change in the routing path`() {
        val src = sourceOf("src/main/java/vn/unlimit/vpngate/repository/VpnServerRepository.kt")
        assertFalse(
            "refresh/routeLadder/fetchOnChosenPath must not call VpnService.protect: $src",
            src.contains("VpnService.protect"),
        )
    }

    // ── L: bounded timeout: connect/read timeouts + total refresh deadline ────
    @Test
    fun `L the request path is bounded by connect read timeouts and a total deadline`() {
        val repo = sourceOf("src/main/java/vn/unlimit/vpngate/repository/VpnServerRepository.kt")
        val fetcher = sourceOf("src/main/java/vn/unlimit/vpngate/data/remote/HttpFetcher.kt")
        assertTrue("the bound client must set a connect timeout", fetcher.contains("connectTimeout"))
        assertTrue("the bound client must set a read timeout", fetcher.contains("readTimeout"))
        assertTrue("refresh must be bounded by the total deadline (mainTimeoutMs)", repo.contains("withTimeoutOrNull(mainTimeoutMs)"))
        assertTrue("a timed-out refresh must preserve the DB", repo.contains("[ladder] deadline exceeded"))
    }

    // ── M: no duplicate writes — each rung attempted at most once per source ──
    @Test
    fun `M each rung is attempted at most once per source (no duplicate writes)`() = runBlocking {
        collectorLogs.clear(); fetchLogs.clear()
        val (r, _) = repoWith(CollectorRoutingInput(tunnelUsable = true, hasBoundNetwork = true))
        r.fetchOnChosenPath<String>(
            sourceTag = "html",
            path = CollectorPath.TUNNEL,
            boundFetcher = tunnelFetcher,
            succeeded = { true },
            physicalFetcher = physicalFetcher,
        ) { f -> if (f == tunnelFetcher) "body" else null }

        assertEquals(listOf("[collector] path=tunnel source=html"), collectorLogs.toList())
        assertEquals(
            "exactly one [fetch] line for the TUNNEL primary",
            1,
            fetchLogs.count { it.startsWith("[fetch] path=tunnel source=html") },
        )
    }

    // ── N: inputs re-read every refresh (no stale-network adherence) ─────────
    @Test
    fun `N routing inputs are re-read every refresh, no stale-network hang`() = runBlocking {
        var input = CollectorRoutingInput(tunnelUsable = false, hasBoundNetwork = false)
        val r = VpnServerRepository(
            fetcher = physicalFetcher,
            cacheDir = tempFolder.newFolder(),
            routing = { input },
            boundFetcherFactory = { tunnelFetcher },
            pathLog = { collectorLogs.add(it) },
            fetchLog = { fetchLogs.add(it) },
        )
        collectorLogs.clear(); fetchLogs.clear()
        r.refresh()
        assertTrue(
            "first run (no tunnel) must take PHYSICAL",
            fetchLogs.any { it == "[ladder] order=PHYSICAL,EMERGENCY" } ||
                fetchLogs.any { it.startsWith("[fetch] path=physical") },
        )

        input = CollectorRoutingInput(tunnelUsable = true, hasBoundNetwork = true)
        collectorLogs.clear(); fetchLogs.clear()
        r.refresh()
        assertTrue(
            "second run (tunnel up) must take TUNNEL first",
            fetchLogs.any { it == "[ladder] order=TUNNEL,PHYSICAL,EMERGENCY" },
        )
    }

    // ── O: invariants across all inputs + memory states ──────────────────────
    @Test
    fun `O ladder invariants hold across all inputs and memory states`() {
        val seeds = listOf<(PathMemory) -> Unit>(
            { },
            { it.recordFailure(CollectorPath.PHYSICAL) },
            { it.recordSuccess(CollectorPath.TUNNEL) },
            { it.recordFailure(CollectorPath.TUNNEL) },
            { it.recordFailure(CollectorPath.PHYSICAL); it.recordSuccess(CollectorPath.TUNNEL) },
            { it.recordSuccess(CollectorPath.PHYSICAL) },
        )
        for (tu in listOf(true, false)) {
            for (hb in listOf(true, false)) {
                for (seed in seeds) {
                    val mem = PathMemory()
                    seed(mem)
                    val result = ladder(tu, hb, mem)
                    assertTrue("must contain PHYSICAL ($tu,$hb)", result.contains(CollectorPath.PHYSICAL))
                    assertEquals("EMERGENCY last ($tu,$hb)", CollectorPath.EMERGENCY, result.last())
                    assertEquals("EMERGENCY once ($tu,$hb)", 1, result.count { it == CollectorPath.EMERGENCY })
                    assertEquals("no duplicates ($tu,$hb)", result.size, result.toSet().size)
                    assertTrue("size 2 or 3 ($tu,$hb)", result.size in 2..3)
                    assertEquals(
                        "TUNNEL iff usable&&bound ($tu,$hb)",
                        result.contains(CollectorPath.TUNNEL),
                        tu && hb,
                    )
                }
            }
        }
    }

    // ── P: resolvePrimary reflects VPN-first ─────────────────────────────────
    @Test
    fun `P resolvePrimary is TUNNEL only when a usable bound tunnel exists`() {
        assertEquals(CollectorPath.PHYSICAL, VpnServerRepository.resolvePrimary(CollectorRoutingInput(false, false)))
        assertEquals(CollectorPath.PHYSICAL, VpnServerRepository.resolvePrimary(CollectorRoutingInput(true, false)))
        assertEquals(CollectorPath.PHYSICAL, VpnServerRepository.resolvePrimary(CollectorRoutingInput(false, true)))
        assertEquals(CollectorPath.TUNNEL, VpnServerRepository.resolvePrimary(CollectorRoutingInput(true, true)))
    }

    // ── Q: TUNNEL primary genuinely invokes the bound VPN fetcher ─────────────
    @Test
    fun `Q the TUNNEL primary genuinely invokes the bound VPN fetcher`() = runBlocking {
        val used = CopyOnWriteArrayList<HttpFetcher>()
        val r = VpnServerRepository(
            fetcher = physicalFetcher,
            cacheDir = tempFolder.newFolder(),
            routing = { CollectorRoutingInput(true, true) },
            boundFetcherFactory = { tunnelFetcher },
            pathLog = { collectorLogs.add(it) },
            fetchLog = { fetchLogs.add(it) },
        )
        r.fetchOnChosenPath<String>(
            sourceTag = "html",
            path = CollectorPath.TUNNEL,
            boundFetcher = tunnelFetcher,
            succeeded = { true },
            physicalFetcher = physicalFetcher,
        ) { f ->
            used.add(f)
            if (f == tunnelFetcher) "body" else null
        }
        assertTrue("the bound VPN fetcher must be invoked for the TUNNEL primary", used.contains(tunnelFetcher))
        assertFalse("the physical fetcher must NOT be used when TUNNEL succeeds", used.contains(physicalFetcher))
    }
}
