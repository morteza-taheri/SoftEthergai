package vn.unlimit.vpngate.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.models.VPNGateItem
import vn.unlimit.vpngate.utils.DataUtil

/**
 * Contract tests for [ServerListUpdater] and the scheduling rule.
 *
 * The two properties under test are the ones that cost users their server list
 * when they regress: a failed update must never clear the stored data, and two
 * updates must never run at once.
 *
 * Every test is deterministic. The concurrency tests synchronise on a
 * [CompletableDeferred] that the fetch itself completes, so the overlap is
 * observed by construction rather than by waiting and hoping — no sleeps and
 * no timing guesses anywhere.
 */
class ServerListUpdaterTest {

    /** In-memory stand-in for the Room table. */
    private class FakeStore(initial: List<VPNGateItem> = emptyList()) : ServerListUpdater.Store {
        var items: List<VPNGateItem> = initial
        var replaceCount = 0
        var failNextReplace = false

        override fun currentCount(): Int = items.size

        override fun currentOpenVpnConfigCount(): Int =
            items.count { !it.openVpnConfigData.isNullOrBlank() }

        override fun replaceAll(items: List<VPNGateItem>) {
            if (failNextReplace) throw IllegalStateException("disk full")
            // Atomic swap, mirroring the DAO's @Transaction behaviour.
            this.items = items
            replaceCount++
        }
    }

    /**
     * A server row as the collector would hand it over. [VPNGateItem] has no
     * default for every column, so the factory fills them in the same shape the
     * real pipeline produces.
     */
    private fun server(host: String, ip: String = "10.0.0.1") = VPNGateItem(
        hostName = host,
        ip = ip,
        countryLong = "Japan",
        countryShort = "JP",
        logType = null,
        operator = null,
        message = null,
        openVpnConfigData = null,
    )

    private fun servers(vararg hosts: String) = hosts.map { server(it) }

    /**
     * A dataset of [count] servers named `[prefix]-1 … [prefix]-count`.
     *
     * Stage 2C-B: a dataset smaller than
     * [DatasetIntegrity.MIN_ACCEPTABLE_DATASET_SIZE] is never stored, so the
     * tests that assert an ACCEPTED outcome use a realistic row count. The
     * intent of each test (atomic swap, guard behaviour, profile protection)
     * is unchanged — only the size of the fixture is realistic now.
     */
    private fun serversN(count: Int, prefix: String = "new") =
        (1..count).map { server("$prefix-$it") }

    /** Same, for rows that carry an OpenVPN profile. */
    private fun serversWithConfigN(count: Int, prefix: String = "new") =
        (1..count).map { serverWithConfig("$prefix-$it") }

    /** A row that carries a usable OpenVPN profile, as the API feed delivers it. */
    private fun serverWithConfig(host: String) = VPNGateItem(
        hostName = host,
        ip = "10.0.0.1",
        countryLong = "Japan",
        countryShort = "JP",
        logType = null,
        operator = null,
        message = null,
        openVpnConfigData = "client\nproto tcp\nremote 10.0.0.1 443\n",
    )

    private fun serversWithConfig(vararg hosts: String) = hosts.map { serverWithConfig(it) }

    /** A row that parsed but carries nothing dialable. */
    private fun blank() = server(host = "", ip = "")

    // ------------------------------------------------------- happy path

    @Test
    fun `a valid dataset replaces the old list atomically`() = runBlocking {
        val store = FakeStore(servers("old-1", "old-2"))
        val candidate = serversN(12)
        val updater = ServerListUpdater(fetch = { candidate }, store = store)

        val outcome = updater.update()

        assertEquals(ServerListUpdater.Outcome.Updated(12), outcome)
        assertEquals(1, store.replaceCount)
        assertEquals(
            "the new list must be the one stored, with the old entries gone",
            candidate.map { it.hostName },
            store.items.map { it.hostName },
        )
    }

    // ------------------------------------------- old list is never lost

    @Test
    fun `a network failure keeps the current list`() = runBlocking {
        val store = FakeStore(servers("old-1", "old-2"))
        val updater = ServerListUpdater(fetch = { null }, store = store)

        val outcome = updater.update()

        assertEquals(
            ServerListUpdater.Outcome.Preserved(ServerListUpdater.Reason.NETWORK_FAILURE, 2),
            outcome,
        )
        assertEquals("no write may happen on a failed download", 0, store.replaceCount)
        assertEquals(listOf("old-1", "old-2"), store.items.map { it.hostName })
    }

    @Test
    fun `an empty response keeps the current list`() = runBlocking {
        val store = FakeStore(servers("old-1"))
        val updater = ServerListUpdater(fetch = { emptyList() }, store = store)

        val outcome = updater.update()

        assertEquals(
            ServerListUpdater.Outcome.Preserved(ServerListUpdater.Reason.EMPTY_RESPONSE, 1),
            outcome,
        )
        assertEquals(0, store.replaceCount)
        assertEquals(listOf("old-1"), store.items.map { it.hostName })
    }

    @Test
    fun `a malformed response keeps the current list`() = runBlocking {
        // A parser produced rows, but none of them is a usable server: with no
        // hostname and no address there is nothing to dial.
        val store = FakeStore(servers("old-1", "old-2"))
        val garbage = listOf(
            blank(),
            server(host = "   ", ip = "  "),
        )
        val updater = ServerListUpdater(fetch = { garbage }, store = store)

        val outcome = updater.update()

        assertEquals(
            ServerListUpdater.Outcome.Preserved(ServerListUpdater.Reason.INVALID_DATASET, 2),
            outcome,
        )
        assertEquals(0, store.replaceCount)
        assertEquals(listOf("old-1", "old-2"), store.items.map { it.hostName })
    }

    @Test
    fun `a parse failure keeps the current list`() = runBlocking {
        val store = FakeStore(servers("old-1"))
        val updater = ServerListUpdater(
            fetch = { throw ServerListParseException(IllegalStateException("bad csv")) },
            store = store,
        )

        val outcome = updater.update()

        assertEquals(
            ServerListUpdater.Outcome.Preserved(ServerListUpdater.Reason.PARSE_FAILURE, 1),
            outcome,
        )
        assertEquals(0, store.replaceCount)
        assertEquals(listOf("old-1"), store.items.map { it.hostName })
    }

    @Test
    fun `an unexpected exception during download keeps the current list`() = runBlocking {
        val store = FakeStore(servers("old-1"))
        val updater = ServerListUpdater(
            fetch = { throw java.net.SocketTimeoutException("timeout") },
            store = store,
        )

        val outcome = updater.update()

        assertEquals(
            ServerListUpdater.Outcome.Preserved(ServerListUpdater.Reason.NETWORK_FAILURE, 1),
            outcome,
        )
        assertEquals(0, store.replaceCount)
        assertEquals(listOf("old-1"), store.items.map { it.hostName })
    }

    @Test
    fun `a store failure keeps the current list`() = runBlocking {
        val store = FakeStore(servers("old-1"))
        store.failNextReplace = true
        val updater = ServerListUpdater(fetch = { serversN(12) }, store = store)

        val outcome = updater.update()

        assertEquals(
            ServerListUpdater.Outcome.Preserved(ServerListUpdater.Reason.STORE_FAILURE, 1),
            outcome,
        )
        assertEquals(listOf("old-1"), store.items.map { it.hostName })
    }

    @Test
    fun `unusable rows are dropped but the usable remainder is stored`() = runBlocking {
        val store = FakeStore(servers("old-1"))
        val mixed = serversN(11) + blank()
        val updater = ServerListUpdater(fetch = { mixed }, store = store)

        val outcome = updater.update()

        assertEquals(ServerListUpdater.Outcome.Updated(11), outcome)
        assertEquals(
            listOf("new-1", "new-2", "new-3", "new-4", "new-5", "new-6", "new-7", "new-8", "new-9", "new-10", "new-11"),
            store.items.map { it.hostName },
        )
    }

    // ------------------------------------------------- degraded dataset

    @Test
    fun `a dataset with no OpenVPN config cannot replace a list that has one`() = runBlocking {
        // The API serves the server list and the profile list as separate
        // feeds, so a partial collection produces dialable-looking rows that
        // carry no profile at all. Storing those would leave every server
        // unconnectable, which is strictly worse than the list we already have.
        val store = FakeStore(serversWithConfig("old-1", "old-2"))
        val updater = ServerListUpdater(fetch = { servers("new-1", "new-2") }, store = store)

        val outcome = updater.update()

        assertEquals(
            ServerListUpdater.Outcome.Preserved(ServerListUpdater.Reason.DEGRADED_DATASET, 2),
            outcome,
        )
        assertEquals(0, store.replaceCount)
        assertEquals(listOf("old-1", "old-2"), store.items.map { it.hostName })
    }

    @Test
    fun `a dataset carrying OpenVPN config replaces the stored list`() = runBlocking {
        val store = FakeStore(serversWithConfig("old-1"))
        val candidate = serversWithConfigN(12)
        val updater = ServerListUpdater(fetch = { candidate }, store = store)

        val outcome = updater.update()

        assertEquals(ServerListUpdater.Outcome.Updated(12), outcome)
        assertEquals(candidate.map { it.hostName }, store.items.map { it.hostName })
    }

    @Test
    fun `a dataset with no config is accepted when nothing connectable is stored`() = runBlocking {
        // Nothing to protect: refusing this would leave a fresh install with an
        // empty list forever.
        val store = FakeStore(servers("old-1"))
        val updater = ServerListUpdater(fetch = { serversN(12) }, store = store)

        assertEquals(ServerListUpdater.Outcome.Updated(12), updater.update())
    }

    // ------------------------------------------------------- concurrency

    @Test
    fun `a concurrent update is ignored instead of run in parallel`() = runBlocking {
        val store = FakeStore(servers("old-1"))
        // The first fetch parks until the test releases it, so the second
        // request is guaranteed to arrive while the first is still in flight.
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var callCount = 0

        val updater = ServerListUpdater(
            fetch = {
                callCount++
                if (callCount == 1) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
                serversN(12, "new-$callCount")
            },
            store = store,
        )

        val first = async { updater.update() }
        firstStarted.await() // deterministic: the first update is now running

        assertEquals(ServerListUpdater.Outcome.SkippedConcurrent, updater.update())

        releaseFirst.complete(Unit)
        assertEquals(ServerListUpdater.Outcome.Updated(12), first.await())
        assertEquals("the ignored request must not have downloaded anything", 1, callCount)
        assertEquals("only the winning update may write", 1, store.replaceCount)
    }

    @Test
    fun `many simultaneous requests produce exactly one download`() = runBlocking {
        val store = FakeStore(servers("old-1"))
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var callCount = 0

        val updater = ServerListUpdater(
            fetch = {
                callCount++
                if (callCount == 1) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
                serversN(12, "new-$callCount")
            },
            store = store,
        )

        coroutineScope {
            val first = async { updater.update() }
            firstStarted.await()
            val rest = (1..8).map { async { updater.update() } }
            releaseFirst.complete(Unit)
            val outcomes = listOf(first.await()) + rest.awaitAll()
            assertEquals(1, outcomes.count { it is ServerListUpdater.Outcome.Updated })
            assertEquals(8, outcomes.count { it == ServerListUpdater.Outcome.SkippedConcurrent })
        }
        assertEquals(1, callCount)
        assertEquals(1, store.replaceCount)
    }

    @Test
    fun `a failed update releases the guard so the next one can run`() = runBlocking {
        val store = FakeStore(servers("old-1"))
        var attempt = 0
        val updater = ServerListUpdater(
            fetch = {
                attempt++
                if (attempt == 1) null else serversN(12)
            },
            store = store,
        )

        assertTrue(updater.update() is ServerListUpdater.Outcome.Preserved)
        assertFalse("the guard must not stay closed after a failure", updater.isRunning)
        assertEquals(ServerListUpdater.Outcome.Updated(12), updater.update())
    }

    @Test
    fun `a scheduled update and a manual update never overlap`() = runBlocking {
        // The screen and the scheduler each own their own updater instance, so
        // the guard has to be process-wide: two instances writing the same table
        // would otherwise be free to interleave.
        val store = FakeStore(servers("old-1"))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scheduled = ServerListUpdater(
            fetch = {
                started.complete(Unit)
                release.await()
                serversN(12, "scheduled")
            },
            store = store,
        )
        val manual = ServerListUpdater(fetch = { serversN(12, "manual") }, store = store)

        val inFlight = async { scheduled.update() }
        started.await()
        assertEquals(ServerListUpdater.Outcome.SkippedConcurrent, manual.update())

        release.complete(Unit)
        assertEquals(ServerListUpdater.Outcome.Updated(12), inFlight.await())
        assertEquals(1, store.replaceCount)
    }

    @Test
    fun `a skipped caller can wait for the running update and then use its result`() = runBlocking {
        // The screen colliding with the scheduled update is the normal case on a
        // cold start: without this wait the screen would keep an empty list and
        // spin forever.
        val store = FakeStore(servers("old-1"))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scheduled = ServerListUpdater(
            fetch = {
                started.complete(Unit)
                release.await()
                serversWithConfigN(12, "scheduled")
            },
            store = store,
        )
        val screen = ServerListUpdater(fetch = { serversN(12, "screen") }, store = store)

        val inFlight = async { scheduled.update() }
        started.await()
        val skipped = async {
            val outcome = screen.update()
            screen.awaitRunningUpdate()
            outcome
        }
        release.complete(Unit)
        assertEquals(ServerListUpdater.Outcome.SkippedConcurrent, skipped.await())
        assertEquals(ServerListUpdater.Outcome.Updated(12), inFlight.await())
        assertEquals(
            "the screen must end up with the winner's list",
            (1..12).map { "scheduled-$it" },
            store.items.map { it.hostName },
        )
    }

    // ----------------------------------------------------- scheduling rule

    @Test
    fun `a list that was never updated is due immediately`() {
        assertTrue(ServerListUpdateScheduler.isDue(0L, 12, 1_000L))
    }

    @Test
    fun `an update is not due before the interval elapses`() {
        val now = 1_000_000_000_000L
        val twelveHours = 12 * 60L * 60L * 1000L
        assertFalse(ServerListUpdateScheduler.isDue(now - twelveHours + 1, 12, now))
    }

    @Test
    fun `an update is due exactly at the interval boundary`() {
        val now = 1_000_000_000_000L
        val twelveHours = 12 * 60L * 60L * 1000L
        assertTrue(ServerListUpdateScheduler.isDue(now - twelveHours, 12, now))
    }

    @Test
    fun `the configured interval is respected`() {
        val now = 1_000_000_000_000L
        val hour = 60L * 60L * 1000L
        assertTrue(ServerListUpdateScheduler.isDue(now - hour, 1, now))
        assertFalse(ServerListUpdateScheduler.isDue(now - hour, 12, now))
    }

    @Test
    fun `an out of range interval cannot produce a busy loop`() {
        val now = 1_000_000_000_000L
        val hour = 60L * 60L * 1000L
        // A stored 0 would mean "every instant" if honoured literally; clamping
        // to the 1-hour floor is what stops the app hammering the API.
        assertFalse(ServerListUpdateScheduler.isDue(now - 60_000L, 0, now))
        assertTrue(ServerListUpdateScheduler.isDue(now - hour, 0, now))
        // A stored 9999 must not postpone the update forever either.
        assertTrue(ServerListUpdateScheduler.isDue(now - 169 * hour, 9999, now))
    }

    // ---------------------------------------------- interval setting bounds

    @Test
    fun `the server update interval defaults to one week`() {
        assertEquals(168, DataUtil.DEFAULT_SERVER_LIST_UPDATE_INTERVAL_HOURS)
    }

    @Test
    fun `the server update interval is bounded to one through one hundred sixty eight hours`() {
        assertEquals(1, DataUtil.MIN_SERVER_LIST_UPDATE_INTERVAL_HOURS)
        assertEquals(168, DataUtil.MAX_SERVER_LIST_UPDATE_INTERVAL_HOURS)
    }

    @Test
    fun `the selectable intervals all fall inside the allowed range`() {
        val options = intArrayOf(1, 3, 6, 12, 18, 24, 168)
        val range =
            DataUtil.MIN_SERVER_LIST_UPDATE_INTERVAL_HOURS..DataUtil.MAX_SERVER_LIST_UPDATE_INTERVAL_HOURS
        assertTrue(options.all { it in range })
        assertTrue("12 hours must be selectable", options.contains(12))
    }
}
