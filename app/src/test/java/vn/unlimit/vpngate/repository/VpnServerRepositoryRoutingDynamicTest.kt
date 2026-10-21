package vn.unlimit.vpngate.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.data.remote.CollectorPath
import vn.unlimit.vpngate.data.remote.CollectorRoutingInput
import java.io.File

/**
 * v1.0.41 (§1.6.34) T-NEW-36..38, extended in v1.0.45 (§1.6.38) with
 * T-NEW-61..63.
 *
 * Covers both halves of the routing change:
 *  - **T-NEW-36** is a source-shape test: `productionRouting()` must feed the
 *    health wrapper ([GlobalVpnTracker.isTunnelUsableForRouting]) instead of
 *    the weaker `isConnected && network != null` pair, and must not OR the
 *    live tunnel state back into `preferVpn` (the v1.0.27 FIX-3a regression).
 *  - **T-NEW-37** is behavioural: `resolvePrimary()` is called directly with
 *    all permutations, because the gate itself is the change under test and it
 *    is a pure function of its input.
 *  - **T-NEW-38** pins the fallback rung and the pure `CollectorRouting.choose`
 *    expression, now over the three-value [CollectorPath].
 *  - **T-NEW-61..63** (v1.0.45) pin the three new mechanics that cannot be
 *    observed from a pure function: the `[ladder] order=` diagnostic, the
 *    single-flight guard, and the total refresh deadline.
 *
 * Why source-shape for 36/38/61..63: `productionRouting()` reads process-wide
 * Android state (`GlobalVpnTracker`) and `fetchOnChosenPath` is exercised
 * end-to-end by `VpnServerRepositoryR4bTest` / `VpnServerRepositoryRoutingTest`.
 * Reading the source pins the wiring without duplicating those suites or
 * class-loading the Android-dependent tracker.
 */
class VpnServerRepositoryRoutingDynamicTest {

    private fun sourceOf(relativePath: String): String {
        for (path in listOf(relativePath, "../$relativePath", "app/$relativePath")) {
            val file = File(path)
            if (file.isFile) return file.readText(Charsets.UTF_8)
        }
        error("source file not found: $relativePath (cwd=${File(".").absolutePath})")
    }

    private fun repositorySource(): String =
        sourceOf("src/main/java/vn/unlimit/vpngate/repository/VpnServerRepository.kt")

    private fun fetcherSource(): String =
        sourceOf("src/main/java/vn/unlimit/vpngate/data/remote/HttpFetcher.kt")

    @Test
    fun `T-NEW-36 productionRouting feeds the usability wrapper, not the raw connected flag`() {
        val source = repositorySource()
        val start = source.indexOf("internal fun productionRouting(")
        assertTrue("productionRouting() must exist", start >= 0)
        // v1.0.45: routeLadder() now sits between productionRouting() and
        // resolvePrimary(), so bound the body on the *next* member instead.
        val end = source.indexOf("internal fun routeLadder(", start)
        assertTrue("routeLadder() must follow productionRouting()", end > start)
        val body = source.substring(start, end)

        assertTrue(
            "tunnelUsable must come from the grace-period wrapper: $body",
            body.contains("GlobalVpnTracker.isTunnelUsableForRouting()"),
        )
        assertTrue(
            "the wrapper's value must be what is passed as tunnelUsable: $body",
            body.contains("tunnelUsable = usable"),
        )
        assertFalse(
            "the weaker isConnected check must no longer feed the routing input: $body",
            body.contains("isConnected"),
        )
        assertFalse(
            "preferVpn must not OR the live tunnel state (v1.0.27 FIX-3a): $body",
            body.contains("||"),
        )
    }

    @Test
    fun `T-NEW-37 resolvePrimary requires both the opt-in and a usable tunnel`() {
        // Opt-in ON, tunnel healthy and bound -> the TUNNEL rung.
        assertEquals(
            CollectorPath.TUNNEL,
            VpnServerRepository.resolvePrimary(CollectorRoutingInput(true, true, true)),
        )
        // Opt-in ON, tunnel dead -> PHYSICAL directly. This is the v1.0.41 change:
        // pre-v1.0.41 this returned the tunnel rung and paid a timeout before
        // falling back.
        assertEquals(
            CollectorPath.PHYSICAL,
            VpnServerRepository.resolvePrimary(CollectorRoutingInput(true, false, true)),
        )
        assertEquals(
            CollectorPath.PHYSICAL,
            VpnServerRepository.resolvePrimary(CollectorRoutingInput(true, false, false)),
        )
        // Opt-in OFF -> PHYSICAL regardless of the tunnel (the default).
        assertEquals(
            CollectorPath.PHYSICAL,
            VpnServerRepository.resolvePrimary(CollectorRoutingInput(false, true, true)),
        )
        assertEquals(
            CollectorPath.PHYSICAL,
            VpnServerRepository.resolvePrimary(CollectorRoutingInput(false, false, false)),
        )
        // v1.0.45: a usable-looking tunnel with no bound network cannot be a rung.
        assertEquals(
            CollectorPath.PHYSICAL,
            VpnServerRepository.resolvePrimary(CollectorRoutingInput(true, true, false)),
        )
    }

    @Test
    fun `T-NEW-38 the fallback rung and the pure choose decision are unchanged`() {
        val repository = repositorySource()
        assertTrue(
            "fetchOnChosenPath must still compute an opposite rung to fall back to",
            repository.contains("val canFallback = when (fallback)"),
        )
        assertTrue(
            "the TUNNEL fallback must still require a bound fetcher",
            repository.contains("CollectorPath.TUNNEL -> boundFetcher != null"),
        )
        assertTrue(
            "the PHYSICAL fallback must stay unconditional",
            repository.contains("CollectorPath.PHYSICAL -> true"),
        )
        assertTrue(
            "EMERGENCY must never be a fallback rung (it carries no network)",
            repository.contains("CollectorPath.EMERGENCY -> false"),
        )

        val fetcher = fetcherSource()
        assertTrue(
            "CollectorRouting.choose must keep its three-way AND",
            fetcher.contains("if (input.preferVpn && input.tunnelUsable && input.hasBoundNetwork)"),
        )
    }

    @Test
    fun `T-NEW-61 refresh logs the ladder order before any attempt`() {
        val repository = repositorySource()
        assertTrue(
            "the ladder must be computed from the routing inputs",
            repository.contains("val ladder = routeLadder(input)"),
        )
        assertTrue(
            "the ladder order must be logged so a device run can read the decision",
            repository.contains("""fetchLog("[ladder] order="""),
        )
        // The order line must precede the fetchers so it is the first line a run emits.
        val ladderLine = repository.indexOf("""fetchLog("[ladder] order=""")
        val firstFetchLine = repository.indexOf("val mirrorsDeferred = async")
        assertTrue("ladder log must be emitted", ladderLine >= 0)
        assertTrue("first source must be launched after it", firstFetchLine > ladderLine)
    }

    @Test
    fun `T-NEW-62 resolvePrimary is a thin projection of routeLadder`() {
        val repository = repositorySource()
        assertTrue(
            "resolvePrimary must delegate to the ladder, not re-derive the gate",
            repository.contains("routeLadder(input).first()"),
        )
        assertFalse(
            "resolvePrimary must not keep its own if/else gate",
            repository.contains("if (input.preferVpn && input.tunnelUsable) CollectorPath."),
        )
    }

    @Test
    fun `T-NEW-63 refresh is single-flight and bounded by one total deadline`() {
        val repository = repositorySource()
        assertTrue(
            "a mutex must serialise refreshes",
            repository.contains("private val refreshMutex = Mutex()"),
        )
        assertTrue(
            "the body must run under the lock",
            repository.contains("refreshMutex.withLock {"),
        )
        assertTrue(
            "the whole refresh must be bounded by the total deadline",
            repository.contains("withTimeoutOrNull(MAIN_TIMEOUT_MS) {"),
        )
        assertTrue(
            "a timed-out refresh must say it preserved the DB",
            repository.contains("[ladder] deadline exceeded"),
        )
        assertTrue(
            "the DB-preserving branch must survive the ladder rewrite",
            repository.contains("[ladder] all rungs failed; preserving DB"),
        )
        // FIX-5 forbids a retry loop: no `while` around the refresh body.
        val body = repository.substringAfter("suspend fun refresh()").substringBefore("private suspend fun refreshLocked()")
        assertFalse("refresh() must not retry in a loop: $body", body.contains("while ("))
    }
}
