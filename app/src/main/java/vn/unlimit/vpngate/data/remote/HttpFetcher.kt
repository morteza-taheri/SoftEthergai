package vn.unlimit.vpngate.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import vn.unlimit.vpngate.data.model.CollectorLog
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/** Minimal fetch boundary so parsers stay unit-testable offline. */
interface HttpFetcher {
    suspend fun get(url: String): String?
}

class OkHttpFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
    private val requestDelayMs: Long = 700,
) : HttpFetcher {
    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/145.0 Safari/537.36"
    }

    override suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        CollectorLog.d("GET $url")
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                )
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Connection", "keep-alive")
                .build()

            val body = client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()
                } else {
                    CollectorLog.d("HTTP ${response.code} for $url")
                    null
                }
            }
            if (requestDelayMs > 0) delay(requestDelayMs)
            body
        } catch (e: IOException) {
            CollectorLog.d("Request failed: ${e.message}")
            null
        }
    }
}
