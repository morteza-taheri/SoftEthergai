package vn.unlimit.vpngate.automode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.history.NetworkType
import vn.unlimit.vpngate.history.ServerHistoryView

/**
 * F.29 §10 — the history-aware ordering.
 *
 * These tests exercise the pure comparator and the tier mapping. The
 * controller-level guarantees (history only permutes inside an equal-quality
 * window, the reachability gate still runs afterwards, protocol priority is
 * untouched) are covered by `AutoModeHistoryOrderingControllerTest`, which
 * drives the real `sortedForAutoConnect`.
 */
class AutoModeHistoryOrderingTest {

    private fun candidate(host: String): AutoModeCandidate = AutoModeCandidate(
        hostname = host,
        ip = "10.0.0.1",
        speed = 10_000_000L,
        ping = 100,
        sessions = 10,
        score = 10,
        sources = 1,
        seTcpPort = 0,
        seUdpPort = 0,
        seUdpSupported = false,
        openVpnTcpPort = 0,
        openVpnUdpPort = 0,
        l2tpSupported = false,
        sstpSupported = false,
    )

    private fun view(
        host: String,
        successes: Int = 0,
        failures: Int = 0,
        consecutive: Int = 0,
        lastSuccessAt: Long = 0L,
        wifiSuccesses: Int = 0,
        cellularSuccesses: Int = 0,
        unknownSuccesses: Int = 0,
    ) = ServerHistoryView(
        hostName = host,
        successCount = successes,
        failureCount = failures,
        consecutiveFailures = consecutive,
        lastSuccessAt = lastSuccessAt,
        wifiSuccessCount = wifiSuccesses,
        cellularSuccessCount = cellularSuccesses,
        unknownNetworkSuccessCount = unknownSuccesses,
    )

    private fun <T> List<T>.names(): List<String> = map {
        when (it) {
            is AutoModeCandidate -> it.hostname
            else -> it.toString()
        } as String
    }

    // ---------------------------------------------------------------------
    // Tier mapping (F.29 §10.2)
    // ---------------------------------------------------------------------

    @Test
    fun `no history at all is inconclusive not failing`() {
        assertEquals(HistoryTier.INCONCLUSIVE, historyTierOf(null, NetworkType.WIFI))
    }

    @Test
    fun `a success on the current network is the top tier`() {
        val v = view("a", successes = 1, wifiSuccesses = 1)
        assertEquals(HistoryTier.CURRENT_NETWORK_SUCCESS, historyTierOf(v, NetworkType.WIFI))
    }

    @Test
    fun `a success on another network is the second tier`() {
        val v = view("a", successes = 1, wifiSuccesses = 1)
        assertEquals(HistoryTier.OTHER_NETWORK_SUCCESS, historyTierOf(v, NetworkType.CELLULAR))
    }

    @Test
    fun `consecutive failures are the bottom tier`() {
        val v = view("a", failures = 3, consecutive = 3)
        assertEquals(HistoryTier.SERVER_FAILURES, historyTierOf(v, NetworkType.WIFI))
    }

    @Test
    fun `an unknown current network can never earn the top tier`() {
        val v = view("a", successes = 5, wifiSuccesses = 5)
        // A Wi-Fi success is not evidence about a network we could not identify.
        assertEquals(HistoryTier.OTHER_NETWORK_SUCCESS, historyTierOf(v, NetworkType.UNKNOWN))
    }

    @Test
    fun `an unknown current network grants no tier zero credit at all`() {
        // F.29 §10.1: an UNKNOWN network earns no tier-0 credit — not even for
        // a server whose successes were themselves recorded as UNKNOWN. A
        // success on a network we could not identify is not evidence about the
        // network we still cannot identify.
        val unknownSuccess = view("a", successes = 2, unknownSuccesses = 2)
        assertEquals(HistoryTier.OTHER_NETWORK_SUCCESS, historyTierOf(unknownSuccess, NetworkType.UNKNOWN))

        val wifiSuccess = view("b", successes = 5, wifiSuccesses = 5)
        assertEquals(HistoryTier.OTHER_NETWORK_SUCCESS, historyTierOf(wifiSuccess, NetworkType.UNKNOWN))

        val cellularSuccess = view("c", successes = 5, cellularSuccesses = 5)
        assertEquals(HistoryTier.OTHER_NETWORK_SUCCESS, historyTierOf(cellularSuccess, NetworkType.UNKNOWN))
    }

    @Test
    fun `timeouts alone leave a server inconclusive`() {
        // A timeout never increments consecutiveFailures, so a silent endpoint
        // is not a known-bad one.
        val v = view("a", failures = 0, consecutive = 0)
        assertEquals(HistoryTier.INCONCLUSIVE, historyTierOf(v, NetworkType.WIFI))
    }

    @Test
    fun `a failure streak with a later success is not the bottom tier`() {
        val v = view("a", successes = 1, failures = 4, consecutive = 0, wifiSuccesses = 1)
        assertEquals(HistoryTier.CURRENT_NETWORK_SUCCESS, historyTierOf(v, NetworkType.WIFI))
    }

    @Test
    fun `a lifetime failure with no streak is inconclusive not failing`() {
        val v = view("a", failures = 2, consecutive = 0)
        assertEquals(HistoryTier.INCONCLUSIVE, historyTierOf(v, NetworkType.WIFI))
    }

    // ---------------------------------------------------------------------
    // Comparator ordering
    // ---------------------------------------------------------------------

    @Test
    fun `a current-network success outranks an other-network success`() {
        val history = mapOf(
            "wifi" to view("wifi", successes = 1, wifiSuccesses = 1, lastSuccessAt = 100L),
            "cell" to view("cell", successes = 1, cellularSuccesses = 1, lastSuccessAt = 200L),
        )
        val sorted = listOf(candidate("cell"), candidate("wifi"))
            .sortedWith(historyComparator(history, NetworkType.WIFI))
        assertEquals(listOf("wifi", "cell"), sorted.names())
    }

    @Test
    fun `a known-good server outranks a server with no history`() {
        val history = mapOf("known" to view("known", successes = 1, wifiSuccesses = 1, lastSuccessAt = 100L))
        val sorted = listOf(candidate("new"), candidate("known"))
            .sortedWith(historyComparator(history, NetworkType.WIFI))
        // Unknown must not be demoted below known-good, but it must not be
        // promoted above it either.
        assertEquals(listOf("known", "new"), sorted.names())
    }

    @Test
    fun `a server with no history outranks a server with a failure streak`() {
        val history = mapOf("bad" to view("bad", failures = 2, consecutive = 2))
        val sorted = listOf(candidate("bad"), candidate("new"))
            .sortedWith(historyComparator(history, NetworkType.WIFI))
        assertEquals(listOf("new", "bad"), sorted.names())
    }

    @Test
    fun `the most recent success wins inside the top tier`() {
        val history = mapOf(
            "old" to view("old", successes = 1, wifiSuccesses = 1, lastSuccessAt = 100L),
            "recent" to view("recent", successes = 1, wifiSuccesses = 1, lastSuccessAt = 900L),
        )
        val sorted = listOf(candidate("old"), candidate("recent"))
            .sortedWith(historyComparator(history, NetworkType.WIFI))
        assertEquals(listOf("recent", "old"), sorted.names())
    }

    @Test
    fun `the shorter failure streak wins inside the bottom tier`() {
        val history = mapOf(
            "worse" to view("worse", failures = 5, consecutive = 5),
            "less" to view("less", failures = 2, consecutive = 2),
        )
        val sorted = listOf(candidate("worse"), candidate("less"))
            .sortedWith(historyComparator(history, NetworkType.WIFI))
        assertEquals(listOf("less", "worse"), sorted.names())
    }

    @Test
    fun `fewer lifetime failures breaks a full tie`() {
        val history = mapOf(
            "many" to view("many", failures = 9, consecutive = 0),
            "few" to view("few", failures = 1, consecutive = 0),
        )
        val sorted = listOf(candidate("many"), candidate("few"))
            .sortedWith(historyComparator(history, NetworkType.WIFI))
        assertEquals(listOf("few", "many"), sorted.names())
    }

    @Test
    fun `a full tie keeps the incoming order`() {
        val history = mapOf(
            "a" to view("a", successes = 1, wifiSuccesses = 1, lastSuccessAt = 100L),
            "b" to view("b", successes = 1, wifiSuccesses = 1, lastSuccessAt = 100L),
        )
        val sorted = listOf(candidate("a"), candidate("b"))
            .sortedWith(historyComparator(history, NetworkType.WIFI))
        assertEquals(listOf("a", "b"), sorted.names())
    }

    // ---------------------------------------------------------------------
    // Identity lookup
    // ---------------------------------------------------------------------

    @Test
    fun `the lookup trims the hostname the way the merger does`() {
        val history = mapOf("vpn.example.com" to view("vpn.example.com", successes = 1, wifiSuccesses = 1))
        val padded = candidate("vpn.example.com")
            .copy(hostname = "  vpn.example.com  ")
        assertEquals(history["vpn.example.com"], history.viewOf(padded))
    }

    @Test
    fun `a candidate without a hostname has no history`() {
        val history = mapOf("vpn.example.com" to view("vpn.example.com", successes = 1, wifiSuccesses = 1))
        assertEquals(null, history.viewOf(candidate("x").copy(hostname = null)))
        assertEquals(null, history.viewOf(candidate("x").copy(hostname = "   ")))
    }

    // ---------------------------------------------------------------------
    // Transitivity (the TimSort contract)
    // ---------------------------------------------------------------------

    @Test
    fun `the comparator is transitive on a real-sized list`() {
        // A non-transitive comparator makes TimSort throw
        // "Comparison method violates its general contract" on real list sizes.
        val history = mapOf(
            "t0" to view("t0", successes = 3, wifiSuccesses = 3, lastSuccessAt = 500L),
            "t1" to view("t1", successes = 1, wifiSuccesses = 1, lastSuccessAt = 900L),
            "t2" to view("t2", successes = 0, failures = 4, consecutive = 4),
            "t3" to view("t3"),
            "t4" to view("t4", successes = 2, cellularSuccesses = 2, lastSuccessAt = 300L),
            "t5" to view("t5", failures = 1, consecutive = 1),
            "t6" to view("t6", successes = 1, wifiSuccesses = 1, lastSuccessAt = 100L),
            "t7" to view("t7", successes = 0, failures = 0, consecutive = 0),
        )
        val candidates = listOf("t7", "t2", "t0", "t5", "t3", "t6", "t1", "t4").map(::candidate)
        // Must not throw.
        val sorted = candidates.sortedWith(historyComparator(history, NetworkType.WIFI))
        assertEquals(8, sorted.size)
        // Tier 0 first (wifi successes, most recent first), then tier 1.
        assertEquals(listOf("t1", "t0", "t6", "t4"), sorted.take(4).names())
    }

    @Test
    fun `the comparator is consistent with itself for equal inputs`() {
        val history = mapOf("a" to view("a", successes = 1, wifiSuccesses = 1, lastSuccessAt = 10L))
        val comparator = historyComparator(history, NetworkType.WIFI)
        assertEquals(0, comparator.compare(candidate("a"), candidate("a")))
        assertTrue(comparator.compare(candidate("a"), candidate("zzz")) < 0)
        assertTrue(comparator.compare(candidate("zzz"), candidate("a")) > 0)
    }
}
