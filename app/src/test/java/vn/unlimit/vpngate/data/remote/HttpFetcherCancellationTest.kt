package vn.unlimit.vpngate.data.remote

import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        val interceptorEntered = CountDownLatch(1)
        val cancellationObserved = CountDownLatch(1)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain: Interceptor.Chain ->
                // Synchronize with the test so a slow CI worker cannot cancel
                // the job before OkHttp has actually entered this interceptor.
                val call = chain.call()
                interceptorEntered.countDown()
                val limitNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (!call.isCanceled() && System.nanoTime() < limitNanos) {
                    Thread.sleep(10)
                }
                callCancelled.set(call.isCanceled())
                if (call.isCanceled()) cancellationObserved.countDown()
                throw IOException(
                    if (call.isCanceled()) "cancelled by call.cancel()" else "interceptor timeout",
                )
            }
            .build()
        val fetcher = OkHttpFetcher(client = client)

        val job = launch(Dispatchers.Default) { fetcher.get("https://example.invalid/blocking") }
        assertTrue(
            "OkHttp must enter the blocking interceptor before the test cancels",
            interceptorEntered.await(3, TimeUnit.SECONDS),
        )

        job.cancel()

        val joined = withTimeoutOrNull(3_000) { job.join() }
        assertTrue(
            "suspend must return promptly after cancel, not after the 15s read timeout",
            joined != null,
        )
        assertTrue(
            "the interceptor must observe cancellation of its underlying OkHttp Call",
            cancellationObserved.await(1, TimeUnit.SECONDS),
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
