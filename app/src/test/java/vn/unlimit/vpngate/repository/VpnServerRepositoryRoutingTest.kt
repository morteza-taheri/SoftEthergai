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
 * v1.0.27 — OPEN-first / VPN-fallback routing regression.
 *
 * This pins the fix for the bug where `productionRouting()` did
 * `preferVpn ||= tunnelUp`, so the app *preferred* the VPN rung whenever any
 * tunnel was up, wasting tunnel bandwidth on a directly-reachable server list.
 *
 * The contract under test (see [VpnServerRepository.fetchOnChosenPath]):
 *   - The PRIMARY rung is OPEN unless the user opted in (PREF_ALWAYS_PREFER_VPN);
 *     the tunnel being up never promotes VPN to primary on its own.
 *   - The VPN rung is only a FALLBACK, tried when OPEN fails AND a bound network
 *     is reachable.
 *   - When the user opted in, VPN is primary and OPEN is the fallback.
 *   - When the user opted in but no tunnel is bound, the VPN rung is simply
 *     unavailable and we degrade straight to OPEN (no claimed fallback).
 *
 * The `[fetch] path=...` diagnostics (tag `VpnServerFetch`) are asserted here so
 * a future regression that silently re-prefers VPN is caught: the very first
 * `[fetch] path=...` line in every no-opt-in case must be `path=open`.
 *
 * No Android, no tracker, no socket: the open and bound fetchers are identity
 * tokens the `fetch` lambda branches on, so OPEN and VPN outcomes are simulated
 * without any network.
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

    /** Identity tokens: the `fetch` lambda returns OPEN/VPN outcomes by reference. */
    private val openFetcher = EmptyFetcher()
    private val vpnFetcher = EmptyFetcher()

    /**
     * Build a repo and run one [VpnServerRepository.fetchOnChosenPath] call.
     *
     * @param path the primary rung chosen for this call (normally derived from
     *   [CollectorRoutingInput.preferVpn]; passed explicitly so each permutation
     *   is independent of the routing decision.
     * @param boundFetcher the VPN-bound fetcher, or null when the tunnel is down.
     * @param openResult what the OPEN rung yields (null = failure).
     * @param vpnResult what the VPN rung yields (null = failure).
     */
    private suspend fun runFetch(
        path: CollectorPath,
        boundFetcher: HttpFetcher?,
        openResult: String? = "OPEN_OK",
        vpnResult: String? = "VPN_OK",
    ): String? {
        val repo = VpnServerRepository(
            fetcher = openFetcher,
            cacheDir = tempFolder.newFolder(),
            boundFetcherFactory = { boundFetcher },
            pathLog = { collectorLogs.add(it) },
            fetchLog = { fetchLogs.add(it) },
        )
        return repo.fetchOnChosenPath<String>(
            sourceTag = "html",
            path = path,
            boundFetcher = boundFetcher,
            succeeded = { it != null },
        ) { f ->
            when (f) {
                openFetcher -> openResult
                vpnFetcher -> vpnResult
                else -> null
            }
        }
    }

    // ── Permutation 1 ────────────────────────────────────────────────────────
    // tunnel DOWN + OPEN succeeds → returns OPEN result, VPN never attempted.
    @Test
    fun `tunnel down plus OPEN succeeds returns OPEN and never touches VPN`() = runBlocking {
        collectorLogs.clear(); fetchLogs.clear()
        val result = runFetch(CollectorPath.OPEN, boundFetcher = null, openResult = "OPEN_OK")

        assertEquals("OPEN_OK", result)
        assertEquals(listOf("[fetch] path=open source=html"), fetchLogs.toList())
        assertEquals(listOf("[collector] path=open source=html"), collectorLogs.toList())
        assertTrue(
            "VPN must not be attempted when the tunnel is down: $fetchLogs",
            fetchLogs.none { it.startsWith("[fetch] path=vpn") },
        )
    }

    // ── Permutation 2 ────────────────────────────────────────────────────────
    // tunnel DOWN + OPEN fails → no fallback possible (VPN rung unavailable).
    @Test
    fun `tunnel down plus OPEN fails has no fallback`() = runBlocking {
        collectorLogs.clear(); fetchLogs.clear()
        val result = runFetch(CollectorPath.OPEN, boundFetcher = null, openResult = null)

        assertNull(result)
        assertEquals(
            listOf(
                "[fetch] path=open source=html",
                "[fetch] path=open failed (no result); no fallback (tunnel down)",
            ),
            fetchLogs.toList(),
        )
        assertEquals(listOf("[collector] path=open source=html"), collectorLogs.toList())
        assertTrue(
            "VPN must not be attempted when the tunnel is down: $fetchLogs",
            fetchLogs.none { it.startsWith("[fetch] path=vpn") },
        )
    }

    // ── Permutation 3 ────────────────────────────────────────────────────────
    // tunnel UP + OPEN ok → VPN is NOT tried (OPEN-first, primary succeeds).
    @Test
    fun `tunnel up plus OPEN succeeds does not try VPN`() = runBlocking {
        collectorLogs.clear(); fetchLogs.clear()
        val result = runFetch(CollectorPath.OPEN, boundFetcher = vpnFetcher, openResult = "OPEN_OK")

        assertEquals("OPEN_OK", result)
        assertEquals(listOf("[fetch] path=open source=html"), fetchLogs.toList())
        assertEquals(listOf("[collector] path=open source=html"), collectorLogs.toList())
        assertTrue(
            "VPN must NOT be the fallback when OPEN already succeeded: $fetchLogs",
            fetchLogs.none { it.startsWith("[fetch] path=vpn") },
        )
    }

    // ── Permutation 4 ────────────────────────────────────────────────────────
    // tunnel UP + OPEN fails + VPN ok → falls back to VPN, returns VPN result.
    @Test
    fun `tunnel up plus OPEN fails falls back to VPN and succeeds`() = runBlocking {
        collectorLogs.clear(); fetchLogs.clear()
        val result = runFetch(
            CollectorPath.OPEN,
            boundFetcher = vpnFetcher,
            openResult = null,
            vpnResult = "VPN_OK",
        )

        assertEquals("VPN_OK", result)
        assertEquals(
            listOf(
                "[fetch] path=open source=html",
                "[fetch] path=open failed (no result); falling back to vpn",
                "[fetch] path=vpn succeeded after open failed",
            ),
            fetchLogs.toList(),
        )
        assertEquals(
            listOf(
                "[collector] path=open source=html",
                "[collector] path=fallback-to-vpn source=html",
            ),
            collectorLogs.toList(),
        )
    }

    // ── Permutation 5 ────────────────────────────────────────────────────────
    // tunnel UP + OPEN fails + VPN fails → both fail, returns null.
    @Test
    fun `tunnel up plus OPEN and VPN both fail returns null`() = runBlocking {
        collectorLogs.clear(); fetchLogs.clear()
        val result = runFetch(
            CollectorPath.OPEN,
            boundFetcher = vpnFetcher,
            openResult = null,
            vpnResult = null,
        )

        assertNull(result)
        assertEquals(
            listOf(
                "[fetch] path=open source=html",
                "[fetch] path=open failed (no result); falling back to vpn",
                "[fetch] path=vpn failed (no result)",
            ),
            fetchLogs.toList(),
        )
        assertEquals(
            listOf(
                "[collector] path=open source=html",
                "[collector] path=fallback-to-vpn source=html",
            ),
            collectorLogs.toList(),
        )
    }

    // ── Permutation 6 ────────────────────────────────────────────────────────
    // userPreferVpn=true + tunnel UP → VPN is PRIMARY (opt-in honoured first).
    @Test
    fun `user opt-in makes VPN the primary rung`() = runBlocking {
        collectorLogs.clear(); fetchLogs.clear()
        val result = runFetch(
            CollectorPath.VPN,
            boundFetcher = vpnFetcher,
            openResult = null,
            vpnResult = "VPN_OK",
        )

        assertEquals("VPN_OK", result)
        assertEquals(listOf("[fetch] path=vpn source=html"), fetchLogs.toList())
        assertEquals(listOf("[collector] path=vpn source=html"), collectorLogs.toList())
        assertTrue(
            "OPEN must not be tried first when the user opted into VPN: $fetchLogs",
            fetchLogs.none { it.startsWith("[fetch] path=open") },
        )
    }

    // ── Permutation 7 ────────────────────────────────────────────────────────
    // userPreferVpn=true + tunnel DOWN → VPN rung unavailable, degrade to OPEN.
    @Test
    fun `user opt-in with tunnel down degrades straight to OPEN`() = runBlocking {
        collectorLogs.clear(); fetchLogs.clear()
        val result = runFetch(
            CollectorPath.VPN,
            boundFetcher = null,
            openResult = "OPEN_OK",
            vpnResult = null,
        )

        assertEquals("OPEN_OK", result)
        assertEquals(
            listOf("[fetch] path=open (vpn rung unavailable)"),
            fetchLogs.toList(),
        )
        assertEquals(listOf("[collector] path=open source=html"), collectorLogs.toList())
        assertTrue(
            "No VPN primary attempt may be logged when the tunnel is down: $fetchLogs",
            fetchLogs.none { it.startsWith("[fetch] path=vpn") },
        )
    }

    // ── Integration: refresh() honours OPEN-first even with a tunnel up ───────
    // The original bug lived in productionRouting(): it did `preferVpn ||= tunnelUp`,
    // so refresh() tried VPN first whenever any tunnel was up. This proves the new
    // model tries OPEN first (and only falls back to VPN) when the user has NOT
    // opted in, regardless of tunnel state.
    @Test
    fun `refresh tries OPEN first even when a tunnel is up and no opt-in`() = runBlocking {
        collectorLogs.clear(); fetchLogs.clear()
        val repo = VpnServerRepository(
            fetcher = openFetcher,
            cacheDir = tempFolder.newFolder(),
            // No opt-in, tunnel reported up (bound fetcher available), both fail.
            routing = { CollectorRoutingInput(false, true, true) },
            boundFetcherFactory = { vpnFetcher },
            pathLog = { collectorLogs.add(it) },
            fetchLog = { fetchLogs.add(it) },
        )
        repo.refresh()

        assertTrue(
            "the very first routing attempt must be OPEN, not VPN: $fetchLogs",
            fetchLogs.first().contains("path=open"),
        )
        assertTrue(
            "VPN must never be the PRIMARY rung without opt-in (only a fallback): $fetchLogs",
            fetchLogs.none { it.startsWith("[fetch] path=vpn source") },
        )
        assertTrue(
            "every VPN attempt must be a fallback after an OPEN failure: $fetchLogs",
            fetchLogs.filter { it.startsWith("[fetch] path=vpn") }
                .all { it.contains("after open failed") || it.contains("failed (no result)") },
        )
    }
}
