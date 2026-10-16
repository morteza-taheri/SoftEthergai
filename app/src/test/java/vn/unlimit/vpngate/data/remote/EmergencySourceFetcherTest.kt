package vn.unlimit.vpngate.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `EmergencySourceFetcher` — the HTTP boundary of the emergency rung.
 *
 * Pure JVM: [HttpFetcher] is an interface, so every case below drives a fake
 * and no socket is opened. The point of these tests is the *decision* —
 * which responses become a usable body, which become a failure, and how many
 * attempts a source is allowed — not the transport, which is already covered
 * by `HttpFetcherR4bTest`.
 */
class EmergencySourceFetcherTest {

    private val url = "https://example.com/servers.csv"

    /** Returns each response in order, then repeats the last one. */
    private class FakeFetcher(private vararg val responses: HttpResponse?) : HttpFetcher {
        val urls = mutableListOf<String>()
        val policies = mutableListOf<RedirectPolicy?>()
        private var index = 0

        override suspend fun get(url: String, redirectPolicy: RedirectPolicy?): HttpResponse? {
            urls.add(url)
            policies.add(redirectPolicy)
            val response = responses.getOrNull(index) ?: responses.last()
            index++
            return response
        }
    }

    private fun response(
        status: Int = 200,
        body: String? = "HostName,IP\n",
        finalUrl: String = url,
        redirectRejected: Boolean = false,
        rejectionReason: String? = null,
    ) = HttpResponse(
        requestedUrl = url,
        finalUrl = finalUrl,
        status = status,
        contentType = null,
        body = body,
        redirectRejected = redirectRejected,
        redirectRejectionReason = rejectionReason,
    )

    private fun fetcherFor(vararg responses: HttpResponse?) =
        EmergencySourceFetcher(FakeFetcher(*responses))

    // ── acceptance ────────────────────────────────────────────────────────

    @Test
    fun `a 200 body inside the cap is a success`() = runBlocking {
        val fetcher = fetcherFor(response(body = "HostName,IP\n1.2.3.4,h\n"))

        val result = fetcher.fetch(url)

        assertTrue("expected Success, got $result", result is EmergencySourceFetcher.FetchResult.Success)
        assertEquals(
            "HostName,IP\n1.2.3.4,h\n",
            (result as EmergencySourceFetcher.FetchResult.Success).body,
        )
    }

    @Test
    fun `the source's own redirect policy is applied`() = runBlocking {
        val fake = FakeFetcher(response())
        EmergencySourceFetcher(fake).fetch(url)

        assertEquals(1, fake.urls.size)
        assertEquals(url, fake.urls.first())
        // Confinement to the configured authority, so an emergency URL can
        // never be walked onto a foreign host.
        assertEquals(setOf("example.com"), fake.policies.first()?.allowedAuthorities)
    }

    @Test
    fun `a redirect that left the source is refused`() = runBlocking {
        val fetcher = fetcherFor(response(finalUrl = "https://other.example.com/servers.csv"))

        val result = fetcher.fetch(url)

        assertTrue("expected NetworkError, got $result", result is EmergencySourceFetcher.FetchResult.NetworkError)
        assertTrue(
            (result as EmergencySourceFetcher.FetchResult.NetworkError).reason.contains("left the source"),
        )
    }

    @Test
    fun `a rejected redirect hop is reported, not followed`() = runBlocking {
        val fetcher = fetcherFor(
            response(
                status = 302,
                body = null,
                redirectRejected = true,
                rejectionReason = "host not in source allowlist",
            ),
        )

        val result = fetcher.fetch(url)

        assertTrue(result is EmergencySourceFetcher.FetchResult.NetworkError)
        assertTrue(
            (result as EmergencySourceFetcher.FetchResult.NetworkError)
                .reason.contains("host not in source allowlist"),
        )
    }

    @Test
    fun `an empty body is a network failure, not a dataset`() = runBlocking {
        val fetcher = fetcherFor(response(body = "   "))

        assertTrue(fetcher.fetch(url) is EmergencySourceFetcher.FetchResult.NetworkError)
    }

    // ── failure shapes ────────────────────────────────────────────────────

    @Test
    fun `a 404 becomes an http error after one retry`() = runBlocking {
        val fake = FakeFetcher(response(status = 404, body = null))
        val fetcher = EmergencySourceFetcher(fake)

        val result = fetcher.fetch(url)

        assertEquals(EmergencySourceFetcher.FetchResult.HttpError(404), result)
        // §2.6: "retry each enabled source once".
        assertEquals(EmergencySourceFetcher.ATTEMPTS, fake.urls.size)
    }

    @Test
    fun `no response becomes a network error after one retry`() = runBlocking {
        val fake = FakeFetcher(null)
        val fetcher = EmergencySourceFetcher(fake)

        val result = fetcher.fetch(url)

        assertTrue(result is EmergencySourceFetcher.FetchResult.NetworkError)
        assertEquals(EmergencySourceFetcher.ATTEMPTS, fake.urls.size)
    }

    @Test
    fun `a throwing fetcher cannot abort the run`() = runBlocking {
        val throwing = object : HttpFetcher {
            override suspend fun get(url: String, redirectPolicy: RedirectPolicy?): HttpResponse? {
                throw IllegalStateException("boom")
            }
        }

        val result = EmergencySourceFetcher(throwing).fetch(url)

        assertTrue(result is EmergencySourceFetcher.FetchResult.NetworkError)
        assertTrue(
            (result as EmergencySourceFetcher.FetchResult.NetworkError)
                .reason.contains("IllegalStateException"),
        )
    }

    @Test
    fun `an unusable source url never reaches the transport`() = runBlocking {
        val fake = FakeFetcher(response())

        val result = EmergencySourceFetcher(fake).fetch("not-a-url")

        assertTrue(result is EmergencySourceFetcher.FetchResult.NetworkError)
        assertEquals(emptyList<String>(), fake.urls)
    }

    @Test
    fun `a body over the cap is refused rather than truncated`() = runBlocking {
        val oversized = "a".repeat(EmergencySourceFetcher.MAX_BODY_CHARS + 1)
        val fetcher = fetcherFor(response(body = oversized))

        val result = fetcher.fetch(url)

        assertEquals(EmergencySourceFetcher.FetchResult.TooLarge(oversized.length), result)
    }

    @Test
    fun `a body exactly at the cap is accepted`() = runBlocking {
        val atCap = "a".repeat(EmergencySourceFetcher.MAX_BODY_CHARS)
        val fetcher = fetcherFor(response(body = atCap))

        assertTrue(fetcher.fetch(url) is EmergencySourceFetcher.FetchResult.Success)
    }

    // ── retry ─────────────────────────────────────────────────────────────

    @Test
    fun `a source that fails once then answers is a success`() = runBlocking {
        val fake = FakeFetcher(
            response(status = 503, body = null),
            response(body = "HostName,IP\n"),
        )

        val result = EmergencySourceFetcher(fake).fetch(url)

        assertTrue("expected Success, got $result", result is EmergencySourceFetcher.FetchResult.Success)
        assertEquals(2, fake.urls.size)
    }

    @Test
    fun `the second failure shape is the one reported`() = runBlocking {
        // A source that first has no network and then answers 404 reports the
        // 404: it is the answer that describes the source's real state.
        val fake = FakeFetcher(null, response(status = 404, body = null))

        val result = EmergencySourceFetcher(fake).fetch(url)

        assertEquals(EmergencySourceFetcher.FetchResult.HttpError(404), result)
    }
}
