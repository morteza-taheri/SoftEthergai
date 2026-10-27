package vn.unlimit.vpngate.data.remote

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v1.0.69 (overnight consolidation) — B-1 regression tests for the
 * cancellation-cooperative [OkHttpFetcher].
 *
 * Pure JVM: no network. A synthetic interceptor either blocks forever (to
 * prove cancellation propagates to the underlying [okhttp3.Call]) or returns a
 * canned response (to prove the success path is unchanged).
 */
class HttpFetcherCancellationTest {

    /**
     * The core B-1 assertion: when the calling coroutine is cancelled, the
     * suspended fetch must return promptly — not after OkHttp's connect(10 s) +
     * read(15 s) budget elapses — and the underlying Call must be cancelled.
     */
    @Test
    fun `a cancelled fetch returns promptly and cancels the underlying call`() = runBlocking {
        val callCancelled = AtomicBoolean(false)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain: Interceptor.Chain ->
                // Simulate a request that never completes on its own. Poll
                // `isCanceled()` rather than sleeping forever: OkHttp's
                // Call.cancel() closes sockets but does not interrupt a thread
                // sleeping inside an interceptor, so an interrupt-based fake
                // would hang the test JVM instead of proving cancellation.
                val call = chain.call()
                val limitNanos = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                while (!call.isCanceled() && System.nanoTime() < limitNanos) {
                    Thread.sleep(10)
                }
                callCancelled.set(call.isCanceled())
                throw IOException(
                    if (call.isCanceled()) "cancelled by call.cancel()" else "interceptor timeout",
                )
            }
            .build()
        val fetcher = OkHttpFetcher(client = client)

        val job = launch { fetcher.get("https://example.invalid/blocking") }
        delay(200)
        assertTrue("the call is still in flight before cancel", !job.isCompleted)

        job.cancel()

        val joined = withTimeoutOrNull(3_000) { job.join() }
        assertTrue(
            "suspend must return promptly after cancel, not after the 15s read timeout",
            joined != null,
        )
        assertTrue("the underlying OkHttp Call must be cancelled", callCancelled.get())
    }

    /**
     * Regression guard: a normal successful exchange still returns the body and
     * status after the B-1 change.
     */
    @Test
    fun `a successful fetch still returns the response body and status`() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain: Interceptor.Chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("hello".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()
        val fetcher = OkHttpFetcher(client = client)

        val response = fetcher.get("https://example.invalid/ok")
        assertEquals(200, response?.status)
        assertEquals("hello", response?.body)
    }

    /**
     * Regression guard: a fetch whose interceptor throws IOException still maps
     * to a null result (the documented "request failed" outcome), unchanged by
     * the B-1 change.
     */
    @Test
    fun `a fetch that fails with IOException still returns null`() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { throw IOException("boom") }
            .build()
        val fetcher = OkHttpFetcher(client = client)

        val response = fetcher.get("https://example.invalid/fail")
        assertNull(response)
    }
}
