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
 *     fetcher; without one the rung is unavailable and we degrade straight to
 *     PHYSICAL (no claimed fallback).
 *   - The opposite rung is the FALLBACK, tried only when the primary produced
 *     nothing and the fallback is reachable.
 *   - EMERGENCY is never a fallback: it carries no network.
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
                "[fetch] path=physical failed (no result); no fallback (tunnel down)",
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
    // tunnel UP + PHYSICAL ok → TUNNEL is NOT tried (PHYSICAL-first, primary succeeds).
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
    // tunnel UP + PHYSICAL fails + TUNNEL ok → falls back to TUNNEL (the P2 recovery).
    @Test
    fun `tunnel up plus PHYSICAL fails falls back to TUNNEL and succeeds`() = runBlocking {
        collectorLogs.clear(); fetchLogs.clear()
        val result = runFetch(
            CollectorPath.PHYSICAL,
            boundFetcher = tunnelFetcher,
            physicalResult = null,
            tunnelResult = "TUNNEL_OK",
        )

        assertEquals("TUNNEL_OK", result)
        assertEquals(
            listOf(
                "[fetch] path=physical source=html",
                "[fetch] path=physical failed (no result); falling back to tunnel",
                "[fetch] path=tunnel succeeded after physical failed",
            ),
            fetchLogs.toList(),
        )
        assertEquals(
            listOf(
                "[collector] path=physical source=html",
                "[collector] path=fallback-to-tunnel source=html",
            ),
            collectorLogs.toList(),
        )
    }

    // ── Permutation 5 ────────────────────────────────────────────────────────
    // tunnel UP + PHYSICAL fails + TUNNEL fails → both fail, returns null.
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
                "[fetch] path=physical failed (no result); falling back to tunnel",
                "[fetch] path=tunnel failed (no result)",
            ),
            fetchLogs.toList(),
        )
        assertEquals(
            listOf(
                "[collector] path=physical source=html",
                "[collector] path=fallback-to-tunnel source=html",
            ),
            collectorLogs.toList(),
        )
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
    // fetch → the rung is unavailable, degrade to PHYSICAL.
    @Test
    fun `a TUNNEL primary with the tunnel down degrades straight to PHYSICAL`() = runBlocking {
        collectorLogs.clear(); fetchLogs.clear()
        val result = runFetch(
            CollectorPath.TUNNEL,
            boundFetcher = null,
            physicalResult = "PHYSICAL_OK",
            tunnelResult = null,
        )

        assertEquals("PHYSICAL_OK", result)
        assertEquals(
            listOf("[fetch] path=physical (tunnel rung unavailable)"),
            fetchLogs.toList(),
        )
        assertEquals(listOf("[collector] path=physical source=html"), collectorLogs.toList())
        assertTrue(
            "No TUNNEL primary attempt may be logged when the tunnel is down: $fetchLogs",
            fetchLogs.none { it.startsWith("[fetch] path=tunnel") },
        )
    }

    // ── Integration: refresh() is PHYSICAL-first with no memory evidence ─────
    // The original bug lived in productionRouting(): it did `preferVpn ||= tunnelUp`,
    // so refresh() tried the tunnel first whenever any tunnel was up. This proves the
    // v1.0.52 ladder tries PHYSICAL first (and only falls back to TUNNEL) when the
    // path memory holds no evidence, regardless of tunnel state.
    @Test
    fun `refresh tries PHYSICAL first even when a tunnel is up and memory is empty`() = runBlocking {
        collectorLogs.clear(); fetchLogs.clear()
        val repo = VpnServerRepository(
            fetcher = physicalFetcher,
            cacheDir = tempFolder.newFolder(),
            // No memory evidence; the tunnel reports usable and bound; both fail.
            routing = { CollectorRoutingInput(tunnelUsable = true, hasBoundNetwork = true) },
            boundFetcherFactory = { tunnelFetcher },
            pathLog = { collectorLogs.add(it) },
            fetchLog = { fetchLogs.add(it) },
        )
        repo.refresh()

        // The ladder order line is emitted before any attempt; skip it.
        val firstAttempt = fetchLogs.first { it.startsWith("[fetch]") }
        assertTrue(
            "the very first routing attempt must be PHYSICAL, not TUNNEL: $fetchLogs",
            firstAttempt.contains("path=physical"),
        )
        assertTrue(
            "TUNNEL must never be the PRIMARY rung without memory evidence: $fetchLogs",
            fetchLogs.none { it.startsWith("[fetch] path=tunnel source") },
        )
        assertTrue(
            "every TUNNEL attempt must be a fallback after a PHYSICAL failure: $fetchLogs",
            fetchLogs.filter { it.startsWith("[fetch] path=tunnel") }
                .all { it.contains("after physical failed") || it.contains("failed (no result)") },
        )
        // And the ladder really did advertise the tunnel as a rung.
        assertTrue(
            "the ladder order line must list PHYSICAL,TUNNEL,EMERGENCY: $fetchLogs",
            fetchLogs.any { it == "[ladder] order=PHYSICAL,TUNNEL,EMERGENCY" },
        )
    }

    // ── v1.0.52: a PHYSICAL failure teaches the ladder for the NEXT run ──────
    // The replacement for the removed manual switch: the collector measures the
    // physical path failing, and the next refresh promotes TUNNEL on its own.
    @Test
    fun `a failed refresh makes the next refresh try TUNNEL first`() = runBlocking {
        collectorLogs.clear(); fetchLogs.clear()
        val repo = VpnServerRepository(
            fetcher = physicalFetcher,
            cacheDir = tempFolder.newFolder(),
            routing = { CollectorRoutingInput(tunnelUsable = true, hasBoundNetwork = true) },
            boundFetcherFactory = { tunnelFetcher },
            pathLog = { collectorLogs.add(it) },
            fetchLog = { fetchLogs.add(it) },
        )

        // Run 1: both rungs fail, so PHYSICAL is recorded as failing.
        repo.refresh()
        assertTrue(
            "run 1 must have started PHYSICAL-first: $fetchLogs",
            fetchLogs.contains("[ladder] order=PHYSICAL,TUNNEL,EMERGENCY"),
        )

        fetchLogs.clear()

        // Run 2: the cooldown from run 1 is still active, so TUNNEL leads.
        repo.refresh()
        assertTrue(
            "run 2 must have promoted TUNNEL from the recorded failure: $fetchLogs",
            fetchLogs.contains("[ladder] order=TUNNEL,PHYSICAL,EMERGENCY"),
        )
        val firstAttempt = fetchLogs.first { it.startsWith("[fetch]") }
        assertTrue(
            "the first attempt of run 2 must be TUNNEL: $fetchLogs",
            firstAttempt.contains("path=tunnel"),
        )
    }
}
