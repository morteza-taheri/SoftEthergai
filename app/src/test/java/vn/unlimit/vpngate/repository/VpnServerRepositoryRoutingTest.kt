package vn.unlimit.vpngate.repository

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
 * v1.0.45 — the PHYSICAL / TUNNEL route ladder (was v1.0.27 OPEN-first /
 * VPN-fallback). Updated in v1.0.52 (§1.6.44) TASK A, which removed the manual
 * "prefer the VPN for the collector" opt-in.
 *
 * The v1.0.27 contract this file used to pin was "OPEN first, VPN only as a
 * fallback", where `OPEN` meant *unbound*. v1.0.45 established that an unbound
 * client is not "outside the tunnel": with the app's own tunnel up it silently
 * follows the OS default, i.e. it goes *through* the tunnel. So the two rungs
 * are now explicit and bound — `PHYSICAL` (a NOT_VPN network) and `TUNNEL` (the
 * app's VPN network).
 *
 * The contract under test (see [VpnServerRepository.fetchOnChosenPath]) is the
 * *mechanics of a chosen path*, deliberately independent of how the path was
 * chosen — that decision is `VpnServerRepository.routeLadder` and is pinned by
 * `VpnServerRepositoryLadderTest`. Here the path is always passed explicitly:
 *   - The PRIMARY rung is whatever `path` says. A TUNNEL primary needs a bound
 *     fetcher; without one the rung is unavailable and the request FAILS — it
 *     does not degrade to PHYSICAL.
 *   - **REQ-ROUTE-001 (v1.0.70): there is no opposite-rung fallback.** Each
 *     source uses exactly one network path. A TUNNEL failure is reported and
 *     never retried over PHYSICAL; a PHYSICAL failure is reported and never
 *     retried over TUNNEL.
 *   - EMERGENCY is never a transport fallback: it carries no network.
 *
 * The `[fetch] path=...` diagnostics (tag `VpnServerFetch`) are asserted here so
 * a future regression that silently changes a rung is caught.
 *
 * No Android, no tracker, no socket: the physical and bound fetchers are
 * identity tokens the `fetch` lambda branches on, so both outcomes are
 * simulated without any network.
 */
class VpnServerRepositoryRoutingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** `[collector] path=...` lines (per-source transport log). */
    private val collectorLogs = CopyOnWriteArrayList<String>()

    /** `[fetch] path=...` diagnostic lines (routing decision log). */
    private val fetchLogs = CopyOnWriteArrayList<String>()

    private class EmptyFetcher : HttpFetcher {
        override suspend fun get(url: String, redirectPolicy: RedirectPolicy?): Nothing? = null
    }

    /** Identity tokens: the `fetch` lambda returns PHYSICAL/TUNNEL outcomes by reference. */
    private val physicalFetcher = EmptyFetcher()
    private val tunnelFetcher = EmptyFetcher()

    /**
     * Build a repo and run one [VpnServerRepository.fetchOnChosenPath] call.
     *
     * @param path the primary rung chosen for this call (normally the first rung
     *   of [VpnServerRepository.routeLadder]; passed explicitly so each
     *   permutation is independent of the routing decision).
     * @param boundFetcher the TUNNEL-bound fetcher, or null when the tunnel is down.
     * @param physicalResult what the PHYSICAL rung yields (null = failure).
     * @param tunnelResult what the TUNNEL rung yields (null = failure).
     */
    private suspend fun runFetch(
        path: CollectorPath,
        boundFetcher: HttpFetcher?,
        physicalResult: String? = "PHYSICAL_OK",
        tunnelResult: String? = "TUNNEL_OK",
    ): String? {
        val repo = VpnServerRepository(
            fetcher = physicalFetcher,
            cacheDir = tempFolder.newFolder(),
            boundFetcherFactory = { boundFetcher },
            pathLog = { collectorLogs.add(it) },
            fetchLog = { fetchLogs.add(it) },
        )
        return repo.fetchOnChosenPath<String>(
            sourceTag = "html",
            path = path,
            boundFetcher = boundFetcher,
            // The lambda returns null on failure, and fetchOnChosenPath already
            // treats a null primary result as a failure, so any non-null body
            // counts as success here.
            succeeded = { true },
            physicalFetcher = physicalFetcher,
        ) { f ->
            when (f) {
                physicalFetcher -> physicalResult
                tunnelFetcher -> tunnelResult
                else -> null
            }
        }
    }

    // ── Permutation 1 ────────────────────────────────────────────────────────
    // tunnel DOWN + PHYSICAL succeeds → returns PHYSICAL result, TUNNEL never attempted.
    @Test
    fun `tunnel down plus PHYSICAL succeeds returns PHYSICAL and never touches TUNNEL`() = runBlocking {
        collectorLogs.clear(); fetchLogs.clear()
        val result = runFetch(CollectorPath.PHYSICAL, boundFetcher = null, physicalResult = "PHYSICAL_OK")

        assertEquals("PHYSICAL_OK", result)
        assertEquals(listOf("[fetch] path=physical source=html"), fetchLogs.toList())
        assertEquals(listOf("[collector] path=physical source=html"), collectorLogs.toList())
        assertTrue(
            "TUNNEL must not be attempted when the tunnel is down: $fetchLogs",
            fetchLogs.none { it.startsWith("[fetch] path=tunnel") },
        )
    }

    // ── Permutation 2 ────────────────────────────────────────────────────────
    // tunnel DOWN + PHYSICAL fails → no fallback possible (TUNNEL rung unavailable).
    @Test
    fun `tunnel down plus PHYSICAL fails has no fallback`() = runBlocking {
        collectorLogs.clear(); fetchLogs.clear()
        val result = runFetch(CollectorPath.PHYSICAL, boundFetcher = null, physicalResult = null)

        assertNull(result)
        assertEquals(
            listOf(
                "[fetch] path=physical source=html",
                "[fetch] path=physical failed (no result)",
            ),
            fetchLogs.toList(),
        )
        assertEquals(listOf("[collector] path=physical source=html"), collectorLogs.toList())
        assertTrue(
            "TUNNEL must not be attempted when the tunnel is down: $fetchLogs",
            fetchLogs.none { it.startsWith("[fetch] path=tunnel") },
        )
    }

    // ── Permutation 3 ────────────────────────────────────────────────────────
    // tunnel UP + PHYSICAL ok → with PHYSICAL passed as the explicit PRIMARY,
    // TUNNEL is NOT tried (the primary already succeeded). (Routing order is
    // decided by routeLadder; this suite pins the fetch mechanics per path.)
    @Test
    fun `tunnel up plus PHYSICAL succeeds does not try TUNNEL`() = runBlocking {
        collectorLogs.clear(); fetchLogs.clear()
        val result = runFetch(CollectorPath.PHYSICAL, boundFetcher = tunnelFetcher, physicalResult = "PHYSICAL_OK")

        assertEquals("PHYSICAL_OK", result)
        assertEquals(listOf("[fetch] path=physical source=html"), fetchLogs.toList())
        assertEquals(listOf("[collector] path=physical source=html"), collectorLogs.toList())
        assertTrue(
            "TUNNEL must NOT be the fallback when PHYSICAL already succeeded: $fetchLogs",
            fetchLogs.none { it.startsWith("[fetch] path=tunnel") },
        )
    }

    // ── Permutation 4 ────────────────────────────────────────────────────────
    // PHYSICAL fails → no fallback to TUNNEL (REQ-ROUTE-001).
    @Test
    fun `tunnel up plus PHYSICAL fails does not fall back to TUNNEL`() = runBlocking {
        collectorLogs.clear(); fetchLogs.clear()
        val result = runFetch(
            CollectorPath.PHYSICAL,
            boundFetcher = tunnelFetcher,
            physicalResult = null,
            tunnelResult = "TUNNEL_OK",
        )

        assertNull(result)
        assertEquals(
            listOf(
                "[fetch] path=physical source=html",
                "[fetch] path=physical failed (no result)",
            ),
            fetchLogs.toList(),
        )
        assertEquals(listOf("[collector] path=physical source=html"), collectorLogs.toList())
    }

    // ── Permutation 5 ────────────────────────────────────────────────────────
    // PHYSICAL fails and TUNNEL would fail too → still no fallback, returns null.
    @Test
    fun `tunnel up plus PHYSICAL and TUNNEL both fail returns null`() = runBlocking {
        collectorLogs.clear(); fetchLogs.clear()
        val result = runFetch(
            CollectorPath.PHYSICAL,
            boundFetcher = tunnelFetcher,
            physicalResult = null,
            tunnelResult = null,
        )

        assertNull(result)
        assertEquals(
            listOf(
                "[fetch] path=physical source=html",
                "[fetch] path=physical failed (no result)",
            ),
            fetchLogs.toList(),
        )
        assertEquals(listOf("[collector] path=physical source=html"), collectorLogs.toList())
    }

    // ── Permutation 6 ────────────────────────────────────────────────────────
    // TUNNEL chosen as primary (the ladder promotes it when PHYSICAL is cooling
    // down or the tunnel is the proven transport) + tunnel UP → TUNNEL only.
    @Test
    fun `a promoted TUNNEL primary rung succeeds without touching PHYSICAL`() = runBlocking {
        collectorLogs.clear(); fetchLogs.clear()
        val result = runFetch(
            CollectorPath.TUNNEL,
            boundFetcher = tunnelFetcher,
            physicalResult = null,
            tunnelResult = "TUNNEL_OK",
        )

        assertEquals("TUNNEL_OK", result)
        assertEquals(listOf("[fetch] path=tunnel source=html"), fetchLogs.toList())
        assertEquals(listOf("[collector] path=tunnel source=html"), collectorLogs.toList())
        assertTrue(
            "PHYSICAL must not be tried first when the ladder promoted TUNNEL: $fetchLogs",
            fetchLogs.none { it.startsWith("[fetch] path=physical") },
        )
    }

    // ── Permutation 7 ────────────────────────────────────────────────────────
    // TUNNEL primary but the tunnel went down between the decision and the
    // fetch → the rung is unavailable and the request FAILS (no PHYSICAL).
    @Test
    fun `a TUNNEL primary with the tunnel down fails without a physical fallback`() = runBlocking {
        collectorLogs.clear(); fetchLogs.clear()
        val result = runFetch(
            CollectorPath.TUNNEL,
            boundFetcher = null,
            physicalResult = "PHYSICAL_OK",
            tunnelResult = null,
        )

        assertNull(result)
        assertEquals(
            listOf("[fetch] path=tunnel unavailable (no bound VPN network); no physical fallback"),
            fetchLogs.toList(),
        )
        assertTrue(
            "no PHYSICAL request may be issued while the VPN is active: $collectorLogs",
            collectorLogs.isEmpty(),
        )
    }

    // ── Integration: refresh() uses TUNNEL only when the VPN is active ───────
    // REQ-ROUTE-001 (v1.0.70): with a usable, bound VPN Network the ladder is
    // TUNNEL → EMERGENCY. A tunnel failure is reported and never retried on
    // PHYSICAL.
    @Test
    fun `refresh uses TUNNEL only when a usable bound tunnel exists`() = runBlocking {
        collectorLogs.clear(); fetchLogs.clear()
        val repo = VpnServerRepository(
            fetcher = physicalFetcher,
            cacheDir = tempFolder.newFolder(),
            routing = { CollectorRoutingInput(tunnelUsable = true, hasBoundNetwork = true) },
            boundFetcherFactory = { tunnelFetcher },
            pathLog = { collectorLogs.add(it) },
            fetchLog = { fetchLogs.add(it) },
        )
        repo.refresh()

        val firstAttempt = fetchLogs.first { it.startsWith("[fetch]") }
        assertTrue(
            "the very first routing attempt must be TUNNEL, not PHYSICAL: $fetchLogs",
            firstAttempt.contains("path=tunnel"),
        )
        assertTrue(
            "TUNNEL must be the PRIMARY rung when a usable+bound tunnel exists: $fetchLogs",
            fetchLogs.any { it.startsWith("[fetch] path=tunnel source") },
        )
        assertTrue(
            "no PHYSICAL attempt may happen while the VPN is active: $fetchLogs",
            fetchLogs.none { it.startsWith("[fetch] path=physical") },
        )
        assertTrue(
            "each source must report the tunnel failure: $fetchLogs",
            fetchLogs.any { it.contains("no physical fallback (VPN active)") },
        )
        assertTrue(
            "the ladder order line must list TUNNEL,EMERGENCY: $fetchLogs",
            fetchLogs.any { it == "[ladder] order=TUNNEL,EMERGENCY" },
        )
    }

    // ── BLOCK v1.0.64: a usable bound tunnel makes every refresh TUNNEL-first ─
    // The VPN-first ordering is input-driven (a usable+bound tunnel), so it does
    // not depend on a prior PHYSICAL failure: every run starts on TUNNEL.
    @Test
    fun `a usable bound tunnel makes refresh try TUNNEL first on every run (deterministic)`() = runBlocking {
        collectorLogs.clear(); fetchLogs.clear()
        val repo = VpnServerRepository(
            fetcher = physicalFetcher,
            cacheDir = tempFolder.newFolder(),
            routing = { CollectorRoutingInput(tunnelUsable = true, hasBoundNetwork = true) },
            boundFetcherFactory = { tunnelFetcher },
            pathLog = { collectorLogs.add(it) },
            fetchLog = { fetchLogs.add(it) },
        )

        // Run 1: a usable+bound tunnel makes TUNNEL the primary immediately.
        repo.refresh()
        assertTrue(
            "run 1 must start TUNNEL-first when a usable+bound tunnel exists: $fetchLogs",
            fetchLogs.contains("[ladder] order=TUNNEL,EMERGENCY"),
        )
        assertTrue(
            "run 1's first attempt must be TUNNEL: $fetchLogs",
            fetchLogs.first { it.startsWith("[fetch]") }.contains("path=tunnel"),
        )

        fetchLogs.clear()

        // Run 2: the same inputs still yield the same deterministic order — no
        // memory evidence is required to prefer the VPN path.
        repo.refresh()
        assertTrue(
            "run 2 must still be TUNNEL-first (deterministic VPN-first): $fetchLogs",
            fetchLogs.contains("[ladder] order=TUNNEL,EMERGENCY"),
        )
        assertTrue(
            "run 2's first attempt must be TUNNEL: $fetchLogs",
            fetchLogs.first { it.startsWith("[fetch]") }.contains("path=tunnel"),
        )
    }
}
