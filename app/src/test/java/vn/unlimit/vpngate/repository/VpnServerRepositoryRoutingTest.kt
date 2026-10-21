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
 * VPN-fallback).
 *
 * The v1.0.27 contract this file used to pin was "OPEN first, VPN only as a
 * fallback", where `OPEN` meant *unbound*. v1.0.45 established that an unbound
 * client is not "outside the tunnel": with the app's own tunnel up it silently
 * follows the OS default, i.e. it goes *through* the tunnel. So the two rungs
 * are now explicit and bound — `PHYSICAL` (a NOT_VPN network) and `TUNNEL` (the
 * app's VPN network) — and the opt-in only *reorders* them.
 *
 * The contract under test (see [VpnServerRepository.fetchOnChosenPath]):
 *   - The PRIMARY rung is PHYSICAL unless the user opted in
 *     (`PREF_ALWAYS_PREFER_VPN`) **and** the tunnel is usable; a tunnel that is
 *     merely up never promotes TUNNEL to primary on its own.
 *   - The TUNNEL rung is a FALLBACK, tried when PHYSICAL fails **and** a bound
 *     network is reachable.
 *   - When the user opted in, TUNNEL is primary and PHYSICAL is the fallback.
 *   - When TUNNEL is primary but no tunnel is bound, the rung is simply
 *     unavailable and we degrade straight to PHYSICAL (no claimed fallback).
 *
 * The `[fetch] path=...` diagnostics (tag `VpnServerFetch`) are asserted here so
 * a future regression that silently re-prefers the tunnel is caught: the very
 * first `[fetch] path=...` line in every no-opt-in case must be `path=physical`.
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
     * @param path the primary rung chosen for this call (normally derived from
     *   [CollectorRoutingInput.preferVpn]; passed explicitly so each permutation
     *   is independent of the routing decision.
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
    // userPreferVpn=true + tunnel UP → TUNNEL is PRIMARY (opt-in reorders the ladder).
    @Test
    fun `user opt-in makes TUNNEL the primary rung`() = runBlocking {
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
            "PHYSICAL must not be tried first when the user opted into the tunnel: $fetchLogs",
            fetchLogs.none { it.startsWith("[fetch] path=physical") },
        )
    }

    // ── Permutation 7 ────────────────────────────────────────────────────────
    // userPreferVpn=true + tunnel DOWN → TUNNEL rung unavailable, degrade to PHYSICAL.
    @Test
    fun `user opt-in with tunnel down degrades straight to PHYSICAL`() = runBlocking {
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

    // ── Integration: refresh() is PHYSICAL-first even with a tunnel up ────────
    // The original bug lived in productionRouting(): it did `preferVpn ||= tunnelUp`,
    // so refresh() tried the tunnel first whenever any tunnel was up. This proves the
    // new ladder tries PHYSICAL first (and only falls back to TUNNEL) when the user
    // has NOT opted in, regardless of tunnel state.
    @Test
    fun `refresh tries PHYSICAL first even when a tunnel is up and no opt-in`() = runBlocking {
        collectorLogs.clear(); fetchLogs.clear()
        val repo = VpnServerRepository(
            fetcher = physicalFetcher,
            cacheDir = tempFolder.newFolder(),
            // No opt-in, tunnel reported up (bound fetcher available), both fail.
            routing = { CollectorRoutingInput(false, true, true) },
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
            "TUNNEL must never be the PRIMARY rung without opt-in (only a fallback): $fetchLogs",
            fetchLogs.none { it.startsWith("[fetch] path=tunnel source") },
        )
        assertTrue(
            "every TUNNEL attempt must be a fallback after a PHYSICAL failure: $fetchLogs",
            fetchLogs.filter { it.startsWith("[fetch] path=tunnel") }
                .all { it.contains("after physical failed") || it.contains("failed (no result)") },
        )
    }
}
