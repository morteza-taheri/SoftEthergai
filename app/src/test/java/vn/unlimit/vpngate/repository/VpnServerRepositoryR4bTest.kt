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
 * R4b — the repository's PHYSICAL-vs-TUNNEL routing, its per-source logging,
 * and its fallback behaviour.
 *
 * v1.0.45 renamed the two rungs: the old `OPEN` was unbound (so it silently
 * followed the OS default, i.e. the app's own tunnel when that was up) and is
 * now `PHYSICAL`, an explicitly bound NOT_VPN network. `VPN` became `TUNNEL`.
 * The assertions below moved with the labels; the mechanics they pin are the
 * same ones R4b established.
 *
 * The fetcher is a fake and the routing/bind/log lambdas are injected, so the
 * whole decision is exercised without Android, a tracker or a network. The
 * fallback itself is asserted both through [VpnServerRepository.refresh] (the
 * log contract) and directly through `fetchOnChosenPath` (the retry
 * mechanics), because "did it retry" is not observable from logs alone.
 */
class VpnServerRepositoryR4bTest {

    /**
     * v1.0.52 (§1.6.44): `PathMemory` is declared inside `VpnServerRepository`'s
     * companion object, so the qualified path from outside is
     * `VpnServerRepository.Companion.PathMemory`.
     */
    private typealias PathMemory = VpnServerRepository.Companion.PathMemory

    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * OPEN-1: [VpnServerRepository.refresh] writes this from two concurrent
     * `async` coroutines (the api and html fetches), so a plain `ArrayList`
     * could silently drop a line under a concurrent `add`. A
     * `CopyOnWriteArrayList` makes every write visible; the assertions below
     * are unchanged.
     */
    private val logs = CopyOnWriteArrayList<String>()

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

    // v1.0.52 (§1.6.44): the routing input is two measurements, not three —
    // the user-facing "prefer the tunnel" term is gone. "Physical path, no
    // tunnel" is modelled as (usable=false, bound=false); a usable bound tunnel
    // as (true, true). Which of the two leads the ladder is decided by
    // VpnServerRepository.routeLadder and pinned by VpnServerRepositoryLadderTest.
    private val physicalInput = CollectorRoutingInput(tunnelUsable = false, hasBoundNetwork = false)
    private val tunnelInput = CollectorRoutingInput(tunnelUsable = true, hasBoundNetwork = true)

    /**
     * The bound fetcher is injected, not built from a socket factory, so no
     * test ever opens a socket: a real one here would reach vpngate.net and
     * make the suite depend on the build machine having a network.
     */
    private fun repository(
        routingInput: CollectorRoutingInput,
        physicalFetcher: HttpFetcher,
        boundFetcher: HttpFetcher? = EmptyFetcher(),
        memory: PathMemory = PathMemory(),
    ): VpnServerRepository = VpnServerRepository(
        fetcher = physicalFetcher,
        cacheDir = tempFolder.newFolder(),
        routing = { routingInput },
        boundFetcherFactory = { boundFetcher },
        pathLog = { logs.add(it) },
        pathMemory = memory,
    )

    /**
     * A repository whose ladder puts TUNNEL first.
     *
     * v1.0.52 (§1.6.44) removed the user-facing "prefer the VPN" switch: a
     * usable tunnel is now only *offered* as the second rung, and it is
     * *promoted* to first only on evidence. The tests below drive runs that
     * start on the TUNNEL rung, so they seed that evidence explicitly — a
     * recorded PHYSICAL failure is exactly what the real ladder acts on after
     * a blocked physical path, so this is the production mechanism, not a
     * shortcut around it.
     */
    private fun tunnelRepository(
        physicalFetcher: HttpFetcher,
        boundFetcher: HttpFetcher? = EmptyFetcher(),
    ): VpnServerRepository {
        val memory = PathMemory()
        memory.recordFailure(CollectorPath.PHYSICAL)
        return repository(tunnelInput, physicalFetcher, boundFetcher, memory)
    }

    @Test
    fun `physical path is logged per source`() = runBlocking {
        // v1.0.27 — D-2 fix (bucket A): physicalInput is (false,false,false) — no
        // opt-in, no usable tunnel, no bound network. The PHYSICAL primary
        // therefore has no TUNNEL rung to fall back to, so we pass a null bound
        // fetcher to model that faithfully; the [collector] stream then carries
        // exactly one `path=physical` line per source and no fallback line.
        logs.clear()
        repository(physicalInput, EmptyFetcher(), boundFetcher = null).refresh()

        assertEquals(
            listOf(
                "[collector] path=physical source=mirror",
                "[collector] path=physical source=api",
                "[collector] path=physical source=html",
            ).sorted(),
            logs.sorted(),
        )
        assertTrue(
            "no TUNNEL rung may be taken when there is no bound network",
            logs.none { it.contains("path=tunnel") },
        )
        assertTrue(
            "no fallback line may appear when the TUNNEL rung was never taken",
            logs.none { it.contains("fallback-to-physical") },
        )
    }

    @Test
    fun `tunnel path is chosen when every input holds`() = runBlocking {
        logs.clear()
        tunnelRepository(EmptyFetcher()).refresh()

        // html and api fail on the TUNNEL rung (the fake returns nothing) and
        // retry on PHYSICAL. The mirror rung does not retry — see
        // `mirror discovery never reports empty because of the bootstrap list`.
        assertEquals(
            listOf(
                "[collector] path=fallback-to-physical source=api",
                "[collector] path=fallback-to-physical source=html",
                "[collector] path=tunnel source=api",
                "[collector] path=tunnel source=html",
                "[collector] path=tunnel source=mirror",
            ),
            logs.sorted(),
        )
        assertTrue(
            "no plain physical line may appear",
            logs.none { it == "[collector] path=physical source=api" },
        )
    }

    /**
     * OPEN-1 regression: [VpnServerRepository.refresh] runs its api and html
     * fetches as two concurrent `async` coroutines, and both write their
     * `[collector] path=...` lines through the same `pathLog` lambda. Before
     * this fix the collector was a plain `ArrayList`, whose `add` is not
     * thread-safe, so a concurrent pair of `add`s could drop a line — exactly
     * the intermittently-missing `source=html` line seen in CI.
     *
     * The test drives that concurrent path 50 times and asserts the exact
     * per-line counts, so a lost write shows up as a short count rather than as
     * a rare, unexplained suite failure.
     */
    @Test
    fun `tunnel-path-logs-are-never-lost-under-concurrent-fetch`() = runBlocking {
        val iterations = 50
        val repo = tunnelRepository(EmptyFetcher())

        logs.clear()
        repeat(iterations) { repo.refresh() }

        // Every run on the TUNNEL rung with a failing bound fetcher emits, per
        // source: mirror -> a single `path=tunnel` line (the bootstrap mirrors
        // make it succeed, so it never falls back); api and html -> one
        // `path=tunnel` line each plus one `path=fallback-to-physical` line
        // each. Five lines per run, so any dropped `add` shortens the total.
        val perRun = listOf(
            "[collector] path=tunnel source=mirror",
            "[collector] path=tunnel source=api",
            "[collector] path=tunnel source=html",
            "[collector] path=fallback-to-physical source=api",
            "[collector] path=fallback-to-physical source=html",
        )

        assertEquals(
            "expected ${perRun.size * iterations} lines from $iterations runs, got ${logs.size}: $logs",
            perRun.size * iterations,
            logs.size,
        )
        for (line in perRun) {
            assertEquals(
                "lost writes for '$line' across $iterations concurrent runs: $logs",
                iterations,
                logs.count { it == line },
            )
        }
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
        tunnelRepository(EmptyFetcher()).refresh()

        assertTrue(
            "mirror must not claim a fallback it never made: $logs",
            logs.none { it.contains("source=mirror") && it.contains("fallback") },
        )
        assertEquals(
            listOf("[collector] path=tunnel source=mirror"),
            logs.filter { it.endsWith("source=mirror") },
        )
    }

    @Test
    fun `missing bind factory degrades to PHYSICAL rather than to the TUNNEL rung`() = runBlocking {
        logs.clear()
        tunnelRepository(EmptyFetcher(), boundFetcher = null).refresh()

        assertTrue(
            "expected PHYSICAL when nothing can be bound, got $logs",
            logs.isNotEmpty() && logs.all { it.startsWith("[collector] path=physical") },
        )
    }

    @Test
    fun `a failed tunnel fetch falls back to physical and actually retries on the physical fetcher`() = runBlocking {
        // html and api fail on the TUNNEL rung; the physical fetcher counts its
        // calls, so the retry is provable rather than merely logged.
        val physical = CountingFetcher()
        logs.clear()
        tunnelRepository(physical).refresh()

        for (tag in listOf("html", "api")) {
            assertEquals(
                "wrong log sequence for $tag",
                listOf(
                    "[collector] path=tunnel source=$tag",
                    "[collector] path=fallback-to-physical source=$tag",
                ),
                logs.filter { it.endsWith("source=$tag") },
            )
        }
        // Both fallbacks must have re-issued the request on the physical fetcher.
        assertTrue("physical fetcher was never retried (calls=${physical.calls})", physical.calls >= 2)
    }

    @Test
    fun `a successful tunnel fetch is not retried on physical`() = runBlocking {
        val repo = VpnServerRepository(
            fetcher = EmptyFetcher(),
            cacheDir = tempFolder.newFolder(),
            routing = { tunnelInput },
            boundFetcherFactory = { EmptyFetcher() },
            pathLog = { logs.add(it) },
        )
        logs.clear()
        val won = repo.fetchOnChosenPath(
            sourceTag = "html",
            path = CollectorPath.TUNNEL,
            boundFetcher = EmptyFetcher(),
            succeeded = { true },
        ) { "body" }

        assertEquals("body", won)
        assertEquals(listOf("[collector] path=tunnel source=html"), logs)
    }

    @Test
    fun `an empty mirror list counts as failure and retries on physical`() = runBlocking {
        // discoverMirrors() reports failure as an empty list, not null — the
        // case a plain `result == null` fallback would silently have missed.
        val repo = VpnServerRepository(
            fetcher = EmptyFetcher(),
            cacheDir = tempFolder.newFolder(),
            routing = { tunnelInput },
            boundFetcherFactory = { EmptyFetcher() },
            pathLog = { logs.add(it) },
        )

        logs.clear()
        val mirrors = repo.fetchOnChosenPath(
            sourceTag = "mirror",
            path = CollectorPath.TUNNEL,
            boundFetcher = EmptyFetcher(),
            succeeded = { it.isNotEmpty() },
        ) { emptyList<String>() }

        // v1.0.27 — D-2 fix: a total failure (primary TUNNEL + PHYSICAL fallback
        // both empty) returns null; the caller treats null as empty via
        // `.orEmpty()`. The user-visible behaviour — retry on PHYSICAL — is
        // asserted by the log lines below.
        assertNull(mirrors)
        assertEquals(
            listOf(
                "[collector] path=tunnel source=mirror",
                "[collector] path=fallback-to-physical source=mirror",
            ),
            logs,
        )
    }

    @Test
    fun `routing is re-read per refresh rather than frozen at construction`() {
        var input = physicalInput
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
        // v1.0.27 — D-2 fix: with a bound tunnel the PHYSICAL primary now falls
        // back to TUNNEL on failure, so the first run (physicalInput, no opt-in)
        // logs `path=physical` as its primary plus `fallback-to-tunnel` lines.
        // Assert the primary rung taken was PHYSICAL.
        assertTrue(
            "first refresh did not take the PHYSICAL rung: $firstRun",
            firstRun.isNotEmpty() && firstRun.any { it.contains("path=physical") },
        )

        // Tunnel comes up and the user opts in: the next run must follow.
        input = tunnelInput
        logs.clear()
        runBlocking { repo.refresh() }
        assertTrue(
            "second refresh ignored the changed inputs: $logs",
            logs.any { it.contains("path=tunnel") },
        )
    }

    @Test
    fun `a null tunnel result is treated as failure`() = runBlocking {
        val repo = VpnServerRepository(
            fetcher = EmptyFetcher(),
            cacheDir = tempFolder.newFolder(),
            routing = { tunnelInput },
            boundFetcherFactory = { EmptyFetcher() },
            pathLog = { logs.add(it) },
        )
        logs.clear()

        val result = repo.fetchOnChosenPath<String>(
            sourceTag = "api",
            path = CollectorPath.TUNNEL,
            boundFetcher = EmptyFetcher(),
            succeeded = { true },
        ) { null }

        assertNull(result)
        assertEquals(
            listOf(
                "[collector] path=tunnel source=api",
                "[collector] path=fallback-to-physical source=api",
            ),
            logs,
        )
    }
}
