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
 * T-NEW-61..63 and in v1.0.52 (§1.6.44) TASK A with T-LADDER-20..23.
 *
 * Covers the halves of the routing change that cannot be observed from a pure
 * function:
 *  - **T-NEW-36** is a source-shape test: `productionRouting()` must feed the
 *    health wrapper ([GlobalVpnTracker.isTunnelUsableForRouting]) instead of
 *    the weaker `isConnected && network != null` pair, and must not OR the
 *    live tunnel state back into the routing input (the v1.0.27 FIX-3a
 *    regression). v1.0.52 adds the mirror-image rule: it must not read a
 *    preference either, because there is no longer one to read.
 *  - **T-NEW-37** is behavioural: `resolvePrimary()` is called directly with
 *    every permutation, because the gate itself is the change under test and
 *    it is a pure function of its input.
 *  - **T-NEW-38** pins the fallback rung and asserts that the fetcher layer
 *    holds no routing decision of its own. (Before v1.0.52 this asserted the
 *    three-way AND inside `CollectorRouting.choose`; that object is gone.)
 *  - **T-NEW-61..63** (v1.0.45) pin the three mechanics that cannot be
 *    observed from a pure function: the `[ladder] order=` diagnostic, the
 *    single-flight guard, and the total refresh deadline.
 *  - **T-LADDER-20..23** (v1.0.52) pin the automatic-ladder mechanics: the
 *    bounded one-shot TUNNEL retry, the path-memory wiring, and the fact that
 *    the retry stays inside the single-flight guard.
 *
 * Why source-shape: `productionRouting()` reads process-wide Android state
 * (`GlobalVpnTracker`) and `fetchOnChosenPath` is exercised end-to-end by
 * `VpnServerRepositoryR4bTest` / `VpnServerRepositoryRoutingTest`. Reading the
 * source pins the wiring without duplicating those suites or class-loading the
 * Android-dependent tracker.
 */
class VpnServerRepositoryRoutingDynamicTest {

    private fun sourceOf(relativePath: String): String {
        for (path in listOf(relativePath, "../$relativePath", "app/$relativePath")) {
            val file = File(path)
            // Normalise CRLF: the repo checks out CRLF on Windows and every
            // multi-line marker below is written with LF.
            if (file.isFile) return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
        }
        error("source file not found: $relativePath (cwd=${File(".").absolutePath})")
    }

    private fun repositorySource(): String =
        sourceOf("src/main/java/vn/unlimit/vpngate/repository/VpnServerRepository.kt")

    private fun fetcherSource(): String =
        sourceOf("src/main/java/vn/unlimit/vpngate/data/remote/HttpFetcher.kt")

    /** The body of `productionRouting()`, bounded by the next member. */
    private fun productionRoutingBody(source: String): String {
        val start = source.indexOf("internal fun productionRouting(")
        assertTrue("productionRouting() must exist", start >= 0)
        // v1.0.52: PathMemory now sits between productionRouting() and
        // routeLadder(), so bound the body on whichever member comes first.
        val end = listOf("internal class PathMemory(", "internal fun routeLadder(")
            .map { source.indexOf(it, start) }
            .filter { it > start }
            .minOrNull()
        assertTrue("productionRouting() must be followed by another member", end != null)
        return source.substring(start, end!!)
    }

    @Test
    fun `T-NEW-36 productionRouting feeds the usability wrapper, not the raw connected flag`() {
        val body = productionRoutingBody(repositorySource())

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
            "the live tunnel state must not be OR-ed into the input (v1.0.27 FIX-3a): $body",
            body.contains("||"),
        )
        // v1.0.52 (§1.6.44): the user preference is gone. A `preferVpn` read
        // here would silently resurrect the manual switch.
        assertFalse(
            "productionRouting must not read the removed preferVpn preference: $body",
            body.contains("preferVpn") || body.contains("PREF_ALWAYS_PREFER_VPN"),
        )
    }

    @Test
    fun `T-NEW-37 resolvePrimary is VPN-first when a usable bound tunnel exists, PHYSICAL otherwise`() {
        // BLOCK v1.0.64: `resolvePrimary` is a projection of `routeLadder(input)`
        // — the *no-memory* ladder. With a usable, bound VPN Network the TUNNEL
        // rung is the primary; otherwise PHYSICAL leads. The v1.0.41 lesson still
        // holds: a usable tunnel with no bound network adds no TUNNEL rung.
        for (tunnelUsable in listOf(true, false)) {
            for (hasBoundNetwork in listOf(true, false)) {
                val expected = if (tunnelUsable && hasBoundNetwork) {
                    CollectorPath.TUNNEL
                } else {
                    CollectorPath.PHYSICAL
                }
                assertEquals(
                    "resolvePrimary must be VPN-first only when a usable+bound tunnel exists " +
                        "(tunnelUsable=$tunnelUsable, hasBoundNetwork=$hasBoundNetwork)",
                    expected,
                    VpnServerRepository.resolvePrimary(
                        CollectorRoutingInput(tunnelUsable, hasBoundNetwork),
                    ),
                )
            }
        }
        // A dead tunnel (usable but unbound) must add no TUNNEL rung.
        assertFalse(
            VpnServerRepository.routeLadder(CollectorRoutingInput(true, false))
                .contains(CollectorPath.TUNNEL),
        )
    }

    @Test
    fun `T-NEW-38 there is no opposite-rung fallback and the fetcher holds no decision`() {
        val repository = repositorySource()
        assertFalse(
            "REQ-ROUTE-001: fetchOnChosenPath must not compute an opposite rung",
            repository.contains("val canFallback = when (fallback)"),
        )
        assertFalse(
            "no `falling back to` diagnostic may survive in the collector",
            repository.contains("falling back to"),
        )
        assertTrue(
            "a TUNNEL failure must be reported, not bypassed",
            repository.contains("no physical fallback (VPN active)"),
        )
        assertTrue(
            "an unavailable TUNNEL rung must fail without a physical request",
            repository.contains("tunnel unavailable (no bound VPN network); no physical fallback"),
        )

        // v1.0.52 (§1.6.44): the routing decision no longer lives in the
        // fetcher layer at all. `CollectorRouting.choose` was deleted, and with
        // it the last `preferVpn` reference in that file.
        val fetcher = fetcherSource()
        assertFalse(
            "the fetcher layer must not carry a routing decision: CollectorRouting is gone",
            fetcher.contains("object CollectorRouting"),
        )
        assertFalse(
            "the fetcher layer must not reference the removed preferVpn input",
            fetcher.contains("preferVpn"),
        )
        assertTrue(
            "the two measurements must still be the routing input's shape",
            fetcher.contains("val tunnelUsable: Boolean") &&
                fetcher.contains("val hasBoundNetwork: Boolean"),
        )
    }

    @Test
    fun `T-NEW-61 refresh logs the ladder order before any attempt`() {
        val repository = repositorySource()
        assertTrue(
            "the ladder must be computed from the routing inputs and the path memory",
            repository.contains("val ladder = routeLadder(input, pathMemory)"),
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
        assertFalse(
            "resolvePrimary must not reintroduce a preference term",
            repository.contains("if (input.preferVpn"),
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
            repository.contains("withTimeoutOrNull(mainTimeoutMs) {"),
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
        val body = repository.substringAfter("suspend fun refresh()")
            .substringBefore("private suspend fun refreshLocked()")
        assertFalse("refresh() must not retry in a loop: $body", body.contains("while ("))
    }

    // ── v1.0.52 TASK A.4/A.5: the automatic-ladder mechanics ──────────────

    @Test
    fun `T-LADDER-20 the one-shot retry is CAS-guarded, bounded and cannot loop`() {
        val repository = repositorySource()
        val body = repository.substringAfter("suspend fun refresh()")
            .substringBefore("private suspend fun refreshLocked()")

        assertTrue(
            "the retry must be spent through a compare-and-set so concurrent callers cannot both spend it",
            body.contains("tunnelRetryAvailable.compareAndSet(true, false)"),
        )
        assertTrue(
            "the retry must only fire when the failed run had no usable tunnel",
            body.contains("lastRunFailedWithoutTunnel"),
        )
        assertTrue(
            "the retry must be bounded by its own smaller budget",
            body.contains("withTimeoutOrNull(TUNNEL_RETRY_TIMEOUT_MS)"),
        )
        assertTrue(
            "the retry must be announced in the fetch log",
            body.contains("[ladder] one-shot retry: a tunnel became usable"),
        )
        // Exactly one retry call site: a second `refreshLocked()` inside the
        // wrapper would be a second attempt, i.e. a loop by unrolling.
        assertEquals(
            "the wrapper must call refreshLocked() exactly twice: the run and the one retry",
            2,
            body.split("refreshLocked()").size - 1,
        )
        assertFalse("the retry must not be a loop: $body", body.contains("while ("))
        assertFalse("the retry must not be recursive: $body", body.contains("refresh()"))
    }

    @Test
    fun `T-LADDER-21 the retry stays inside the single-flight guard`() {
        val repository = repositorySource()
        val body = repository.substringAfter("suspend fun refresh()")
            .substringBefore("private suspend fun refreshLocked()")

        // `withLock` opens before the first run and the retry block is inside
        // that lambda, so a second caller queues behind the retry instead of
        // racing it.
        val lockAt = body.indexOf("refreshMutex.withLock {")
        val retryAt = body.indexOf("tunnelRetryAvailable.compareAndSet")
        assertTrue("the lock must be taken", lockAt >= 0)
        assertTrue("the retry must exist", retryAt >= 0)
        assertTrue("the retry must be inside the lock, not after it", retryAt > lockAt)
        // Nothing may release the lock before the retry: a `return@withLock`
        // that precedes it is the only allowed early exit, and it is the
        // deadline branch, which returns *before* any retry by design.
        val deadlineAt = body.indexOf("return@withLock result")
        assertTrue("the deadline branch must return early", deadlineAt in 0 until retryAt)
    }

    @Test
    fun `T-LADDER-22 the retry is re-armed only by a run that starts with a usable tunnel`() {
        val repository = repositorySource()
        val body = repository.substringAfter("private suspend fun refreshLocked()")
            .substringBefore("private fun bindHttpFetcher()")

        assertTrue(
            "a run that starts with a usable bound tunnel must re-arm the retry",
            body.contains("tunnelRetryAvailable.set(true)"),
        )
        assertTrue(
            "re-arming must be conditioned on the tunnel being usable AND bound",
            body.contains("if (input.tunnelUsable && input.hasBoundNetwork)"),
        )
        assertTrue(
            "each run must clear the previous run's failure reason",
            body.contains("lastRunFailedWithoutTunnel = false"),
        )
        assertTrue(
            "only a failure with no usable tunnel may arm the retry",
            body.contains("lastRunFailedWithoutTunnel = !input.tunnelUsable"),
        )
        // The arming site must be the empty-records branch, which is reached
        // before the successful return — not the success path.
        val armAt = body.indexOf("lastRunFailedWithoutTunnel = !input.tunnelUsable")
        val successReturnAt = body.indexOf("return CollectResult(")
        assertTrue("the arming site must exist", armAt >= 0)
        assertTrue("the success return must exist", successReturnAt >= 0)
        assertTrue(
            "the retry must be armed before the successful return, never after it",
            armAt < successReturnAt,
        )
    }

    @Test
    fun `T-LADDER-23 fetchOnChosenPath records the chosen rung's outcome`() {
        val repository = repositorySource()
        val body = repository.substringAfter("internal suspend fun <T> fetchOnChosenPath(")
            .substringBefore("private fun ")

        assertTrue(
            "a successful rung must be remembered",
            body.contains("pathMemory.recordSuccess(path)"),
        )
        assertTrue(
            "a failed rung must start that path's cooldown",
            body.contains("pathMemory.recordFailure(path)"),
        )
        assertFalse(
            "REQ-ROUTE-001: no fallback rung may be recorded",
            body.contains("pathMemory.recordSuccess(fallback)") ||
                body.contains("pathMemory.recordFailure(fallback)"),
        )
        // The tunnel-unavailable short-circuit returns before any memory write:
        // a rung that was never attempted must not be recorded as failed.
        val shortCircuitAt = body.indexOf("tunnel unavailable (no bound VPN network)")
        val firstRecordAt = body.indexOf("pathMemory.record")
        assertTrue("the short-circuit must exist", shortCircuitAt >= 0)
        assertTrue("the memory must be written", firstRecordAt >= 0)
        assertTrue(
            "the short-circuit must return before any recordSuccess/recordFailure",
            shortCircuitAt < firstRecordAt,
        )
    }

    @Test
    fun `T-LADDER-24 no preference symbol survives anywhere in the collector`() {
        // Belt-and-braces across the three files the removed switch touched.
        val files = listOf(
            "src/main/java/vn/unlimit/vpngate/repository/VpnServerRepository.kt",
            "src/main/java/vn/unlimit/vpngate/data/remote/HttpFetcher.kt",
            "src/main/java/vn/unlimit/vpngate/utils/DataUtil.kt",
        )
        for (relative in files) {
            val text = sourceOf(relative)
            // Declarations, not mere mentions: the files legitimately name the
            // removed accessors in the comments that record their removal.
            assertFalse(
                "$relative must not declare getPrefersVpnForCollector/setPrefersVpnForCollector",
                text.contains("fun getPrefersVpnForCollector") ||
                    text.contains("fun setPrefersVpnForCollector"),
            )
        }
        val dataUtil = sourceOf("src/main/java/vn/unlimit/vpngate/utils/DataUtil.kt")
        assertTrue(
            "REQ-TIMEOUT-001: the auto-mode timeout default must be 12 s",
            dataUtil.contains("getIntSetting(SETTING_AUTO_MODE_TIMEOUT_SECONDS, 12)"),
        )
        assertFalse(
            "no stale 8 s default may survive",
            dataUtil.contains("getIntSetting(SETTING_AUTO_MODE_TIMEOUT_SECONDS, 8)"),
        )
        assertTrue(
            "REQ-TIMEOUT-001: the range must be clamped to 8..30",
            dataUtil.contains(".coerceIn(8, 30)"),
        )
    }
}
