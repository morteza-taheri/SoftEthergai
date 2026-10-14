package vn.unlimit.vpngate.repository

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
 * R4b — the repository's open-vs-VPN routing, its per-source logging, and its
 * fallback behaviour.
 *
 * The fetcher is a fake and the routing/bind/log lambdas are injected, so the
 * whole decision is exercised without Android, a tracker or a network. The
 * fallback itself is asserted both through [VpnServerRepository.refresh] (the
 * log contract) and directly through `fetchOnChosenPath` (the retry
 * mechanics), because "did it retry" is not observable from logs alone.
 */
class VpnServerRepositoryR4bTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val logs = mutableListOf<String>()

    /** Always "fails": sources report nothing acquired. */
    private class EmptyFetcher : HttpFetcher {
        override suspend fun get(url: String, redirectPolicy: RedirectPolicy?): Nothing? = null
    }

    /** Records every call, then fails like [EmptyFetcher]. */
    private class CountingFetcher : HttpFetcher {
        var calls = 0
        override suspend fun get(url: String, redirectPolicy: RedirectPolicy?): Nothing? {
            calls++
            return null
        }
    }

    private val openInput = CollectorRoutingInput(false, true, true)
    private val vpnInput = CollectorRoutingInput(true, true, true)

    /**
     * The bound fetcher is injected, not built from a socket factory, so no
     * test ever opens a socket: a real one here would reach vpngate.net and
     * make the suite depend on the build machine having a network.
     */
    private fun repository(
        routingInput: CollectorRoutingInput,
        openFetcher: HttpFetcher,
        boundFetcher: HttpFetcher? = EmptyFetcher(),
    ): VpnServerRepository = VpnServerRepository(
        fetcher = openFetcher,
        cacheDir = tempFolder.newFolder(),
        routing = { routingInput },
        boundFetcherFactory = { boundFetcher },
        pathLog = { logs.add(it) },
    )

    @Test
    fun `open path is logged per source`() = runBlocking {
        logs.clear()
        repository(openInput, EmptyFetcher()).refresh()

        assertEquals(
            listOf(
                "[collector] path=open source=mirror",
                "[collector] path=open source=api",
                "[collector] path=open source=html",
            ).sorted(),
            logs.sorted(),
        )
        assertTrue("no vpn line may appear on the open path", logs.none { it.contains("path=vpn") })
        assertTrue(
            "no fallback line may appear when the VPN rung was never taken",
            logs.none { it.contains("fallback-to-open") },
        )
    }

    @Test
    fun `vpn path is chosen when every input holds`() = runBlocking {
        logs.clear()
        repository(vpnInput, EmptyFetcher()).refresh()

        // html and api fail on the VPN rung (the fake returns nothing) and
        // retry on open. The mirror rung does not retry — see
        // `mirror discovery never reports empty because of the bootstrap list`.
        assertEquals(
            listOf(
                "[collector] path=fallback-to-open source=api",
                "[collector] path=fallback-to-open source=html",
                "[collector] path=vpn source=api",
                "[collector] path=vpn source=html",
                "[collector] path=vpn source=mirror",
            ),
            logs.sorted(),
        )
        assertTrue("no plain open line may appear", logs.none { it == "[collector] path=open source=api" })
    }

    /**
     * `VpnGateMirrorSource.discoverMirrors` merges the discovered, cached AND
     * built-in bootstrap mirror lists, so it can never come back empty — even
     * with no network at all. The mirror rung therefore has nothing to fall
     * back from, and must not log a fallback it did not perform.
     */
    @Test
    fun `mirror discovery never reports empty because of the bootstrap list`() = runBlocking {
        logs.clear()
        // Even a fetcher that always fails leaves the bootstrap mirrors.
        repository(vpnInput, EmptyFetcher()).refresh()

        assertTrue(
            "mirror must not claim a fallback it never made: $logs",
            logs.none { it.contains("source=mirror") && it.contains("fallback") },
        )
        assertEquals(
            listOf("[collector] path=vpn source=mirror"),
            logs.filter { it.endsWith("source=mirror") },
        )
    }

    @Test
    fun `missing bind factory degrades to open rather than to the vpn rung`() = runBlocking {
        logs.clear()
        repository(vpnInput, EmptyFetcher(), boundFetcher = null).refresh()

        assertTrue(
            "expected open when nothing can be bound, got $logs",
            logs.isNotEmpty() && logs.all { it.startsWith("[collector] path=open") },
        )
    }

    @Test
    fun `a failed vpn fetch falls back to open and actually retries on the open fetcher`() = runBlocking {
        // html and api fail on the VPN rung; the open fetcher counts its calls,
        // so the retry is provable rather than merely logged.
        val open = CountingFetcher()
        logs.clear()
        repository(vpnInput, open).refresh()

        for (tag in listOf("html", "api")) {
            assertEquals(
                "wrong log sequence for $tag",
                listOf(
                    "[collector] path=vpn source=$tag",
                    "[collector] path=fallback-to-open source=$tag",
                ),
                logs.filter { it.endsWith("source=$tag") },
            )
        }
        // Both fallbacks must have re-issued the request on the open fetcher.
        assertTrue("open fetcher was never retried (calls=${open.calls})", open.calls >= 2)
    }

    @Test
    fun `a successful vpn fetch is not retried on open`() = runBlocking {
        val repo = VpnServerRepository(
            fetcher = EmptyFetcher(),
            cacheDir = tempFolder.newFolder(),
            routing = { vpnInput },
            boundFetcherFactory = { EmptyFetcher() },
            pathLog = { logs.add(it) },
        )
        logs.clear()
        val won = repo.fetchOnChosenPath(
            sourceTag = "html",
            path = CollectorPath.VPN,
            boundFetcher = EmptyFetcher(),
            succeeded = { true },
        ) { "body" }

        assertEquals("body", won)
        assertEquals(listOf("[collector] path=vpn source=html"), logs)
    }

    @Test
    fun `an empty mirror list counts as failure and retries on open`() = runBlocking {
        // discoverMirrors() reports failure as an empty list, not null — the
        // case a plain `result == null` fallback would silently have missed.
        val repo = VpnServerRepository(
            fetcher = EmptyFetcher(),
            cacheDir = tempFolder.newFolder(),
            routing = { vpnInput },
            boundFetcherFactory = { EmptyFetcher() },
            pathLog = { logs.add(it) },
        )

        logs.clear()
        val mirrors = repo.fetchOnChosenPath(
            sourceTag = "mirror",
            path = CollectorPath.VPN,
            boundFetcher = EmptyFetcher(),
            succeeded = { it.isNotEmpty() },
        ) { emptyList<String>() }

        assertEquals(emptyList<String>(), mirrors)
        assertEquals(
            listOf(
                "[collector] path=vpn source=mirror",
                "[collector] path=fallback-to-open source=mirror",
            ),
            logs,
        )
    }

    @Test
    fun `routing is re-read per refresh rather than frozen at construction`() {
        var input = openInput
        val repo = VpnServerRepository(
            fetcher = EmptyFetcher(),
            cacheDir = tempFolder.newFolder(),
            routing = { input },
            boundFetcherFactory = { EmptyFetcher() },
            pathLog = { logs.add(it) },
        )

        logs.clear()
        runBlocking { repo.refresh() }
        val firstRun = logs.toList()
        assertTrue(firstRun.isNotEmpty() && firstRun.all { it.contains("path=open") })

        // Tunnel comes up and the user opts in: the next run must follow.
        input = vpnInput
        logs.clear()
        runBlocking { repo.refresh() }
        assertTrue(
            "second refresh ignored the changed inputs: $logs",
            logs.any { it.contains("path=vpn") },
        )
    }

    @Test
    fun `a null vpn result is treated as failure`() = runBlocking {
        val repo = VpnServerRepository(
            fetcher = EmptyFetcher(),
            cacheDir = tempFolder.newFolder(),
            routing = { vpnInput },
            boundFetcherFactory = { EmptyFetcher() },
            pathLog = { logs.add(it) },
        )
        logs.clear()

        val result = repo.fetchOnChosenPath<String>(
            sourceTag = "api",
            path = CollectorPath.VPN,
            boundFetcher = EmptyFetcher(),
            succeeded = { true },
        ) { null }

        assertNull(result)
        assertEquals(
            listOf(
                "[collector] path=vpn source=api",
                "[collector] path=fallback-to-open source=api",
            ),
            logs,
        )
    }
}
