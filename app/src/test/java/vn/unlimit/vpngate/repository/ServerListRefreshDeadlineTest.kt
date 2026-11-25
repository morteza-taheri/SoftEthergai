package vn.unlimit.vpngate.repository

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import vn.unlimit.vpngate.data.remote.CollectorRoutingInput
import vn.unlimit.vpngate.data.remote.OkHttpFetcher

/**
 * BLOCK v1.0.69 (overnight consolidation) — end-to-end proof of B-1.
 *
 * [HttpFetcherCancellationTest] proves that cancelling the *caller's* coroutine
 * cancels the underlying OkHttp `Call`. This test proves the property the
 * project actually documents: the outer `MAIN_TIMEOUT_MS` budget on
 * [VpnServerRepository.refresh] is a real end-to-end deadline, not merely a
 * cooperative wrapper that a blocking socket read can outlive.
 *
 * Pure JVM: the interceptor never opens a socket.
 */
class ServerListRefreshDeadlineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `the outer refresh deadline cuts a blocking HTTP call end to end`() = runBlocking {
        val interceptorEntered = CountDownLatch(1)
        val cancellationObserved = CountDownLatch(1)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain: Interceptor.Chain ->
                // Hold the request until the repository's own timeout cancels
                // its child coroutine and therefore the underlying OkHttp Call.
                val call = chain.call()
                interceptorEntered.countDown()
                val limitNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (!call.isCanceled() && System.nanoTime() < limitNanos) {
                    Thread.sleep(10)
                }
                if (call.isCanceled()) cancellationObserved.countDown()
                throw IOException(
                    if (call.isCanceled()) "cancelled by the refresh deadline" else "interceptor timeout",
                )
            }
            .build()
        // Every request must travel through the blocking interceptor above.
        // The raw-socket cleartext-mirror path bypasses the client entirely, so
        // leaving it on would let a mirror answer around the very call this test
        // is holding open.
        val fetcher = OkHttpFetcher(client = client, allowCleartextMirrors = false)
        val fetchLog = CopyOnWriteArrayList<String>()

        val repo = VpnServerRepository(
            fetcher = fetcher,
            cacheDir = tempFolder.newFolder("deadline"),
            // No VPN: the ladder is PHYSICAL -> EMERGENCY, so the very first
            // source goes straight to this blocking fetcher.
            routing = { CollectorRoutingInput(tunnelUsable = false, hasBoundNetwork = false) },
            boundFetcherFactory = { null },
            pathLog = {},
            fetchLog = { fetchLog.add(it) },
            // Leave enough startup headroom for loaded CI runners while still
            // keeping this a short deterministic deadline regression test.
            mainTimeoutMs = 2_000L,
        )

        val startedAt = System.currentTimeMillis()
        val refresh = async(Dispatchers.Default) { repo.refresh() }
        assertTrue(
            "the HTTP interceptor must be reached before the refresh deadline",
            interceptorEntered.await(1_500, TimeUnit.MILLISECONDS),
        )
        val result = withTimeoutOrNull(5_000) { refresh.await() }
        val elapsedMs = System.currentTimeMillis() - startedAt

        assertNull("a refresh that hit its deadline must return null", result)
        assertTrue(
            "the repository must report its own deadline, not the test's: $fetchLog",
            fetchLog.any { it.contains("deadline exceeded") },
        )
        assertTrue(
            "the 2 s deadline must fire promptly; took ${elapsedMs}ms",
            elapsedMs < 4_500,
        )
        assertTrue(
            "the underlying OkHttp call must observe the repository timeout",
            cancellationObserved.await(1, TimeUnit.SECONDS),
        )
    }
}
