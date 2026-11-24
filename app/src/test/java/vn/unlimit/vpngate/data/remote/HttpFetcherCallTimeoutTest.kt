package vn.unlimit.vpngate.data.remote

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class HttpFetcherCallTimeoutTest {

    @Test
    fun perCallTimeoutUsesTheCurrentConfiguredDeadline() = runBlocking {
        val observedTimeoutNanos = AtomicLong(0L)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                observedTimeoutNanos.set(chain.call().timeout().timeoutNanos())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("ok".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()

        val response = OkHttpFetcher(
            client = client,
            callTimeoutMsProvider = { 120_000L },
        ).get("https://example.invalid/servers.csv")

        assertNotNull(response)
        assertEquals(200, response?.status)
        assertEquals(TimeUnit.SECONDS.toNanos(120), observedTimeoutNanos.get())
    }
}
