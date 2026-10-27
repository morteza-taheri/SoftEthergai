package vn.unlimit.vpngate.repository

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
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
 * Before the B-1 fix, a blocked `execute()` ignored the deadline until OkHttp's
 * own connect(10 s) + read(15 s) budget elapsed per hop — the device runs in
 * v1.0.65 observed 128 s / 139 s instead of the nominal 45 s. With the fix the
 * blocked read is interrupted when the deadline fires, so this test uses a
 * 300 ms deadline against an interceptor that would otherwise block forever.
 *
 * Pure JVM: the interceptor never opens a socket.
 */
class ServerListRefreshDeadlineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `the outer refresh deadline cuts a blocking HTTP call end to end`() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain: Interceptor.Chain ->
                // Simulate a read that never completes on its own. Poll
                // `isCanceled()` rather than sleeping forever: OkHttp's
                // Call.cancel() closes sockets but does not interrupt a thread
                // sleeping inside an interceptor, so an interrupt-based fake
                // would hang the test instead of proving cancellation.
                val call = chain.call()
                val limitNanos = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                while (!call.isCanceled() && System.nanoTime() < limitNanos) {
                    Thread.sleep(10)
                }
                throw IOException(
                    if (call.isCanceled()) "cancelled by the refresh deadline" else "interceptor timeout",
                )
            }
            .build()
        val fetcher = OkHttpFetcher(client = client)
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
            mainTimeoutMs = 300L,
        )

        val startedAt = System.currentTimeMillis()
        val result = withTimeoutOrNull(5_000) { repo.refresh() }
        val elapsedMs = System.currentTimeMillis() - startedAt

        assertNull("a refresh that hit its deadline must return null", result)
        assertTrue(
            "the repository must report its own deadline, not the test's: $fetchLog",
            fetchLog.any { it.contains("deadline exceeded") },
        )
        assertTrue(
            "the 300 ms deadline must fire promptly; took ${elapsedMs}ms",
            elapsedMs < 4_000,
        )
    }
}
