package vn.unlimit.vpngate.data.remote

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stage G-5: the validator cache that makes an unchanged feed cost two headers
 * instead of a megabyte.
 *
 * The contract under test is deliberately narrow, because that is the whole
 * safety argument for the feature: a body is only ever *remembered* with the
 * validators the server gave it, and it is only ever *returned* when the
 * server itself answers 304. Nothing here can invent content.
 */
class HttpCacheTest {

    @Before
    fun setUp() = HttpCache.clear()

    @After
    fun tearDown() = HttpCache.clear()

    @Test
    fun `a body with an etag is remembered with its validators`() {
        HttpCache.put(
            url = "https://www.vpngate.net/en/",
            body = "<html>feed</html>",
            etag = "\"abc123\"",
            lastModified = null,
            contentType = "text/html",
        )

        val entry = HttpCache.get("https://www.vpngate.net/en/")
        assertNotNull(entry)
        assertEquals("<html>feed</html>", entry!!.body)
        assertEquals("\"abc123\"", entry.etag)
        assertNull(entry.lastModified)
        assertEquals("text/html", entry.contentType)
    }

    @Test
    fun `last-modified alone is enough to revalidate with`() {
        HttpCache.put(
            url = "https://www.vpngate.net/api/iphone/",
            body = "HostName,IP\n",
            etag = null,
            lastModified = "Tue, 24 Nov 2026 10:00:00 GMT",
            contentType = "text/csv",
        )

        val entry = HttpCache.get("https://www.vpngate.net/api/iphone/")
        assertNotNull(entry)
        assertEquals("Tue, 24 Nov 2026 10:00:00 GMT", entry!!.lastModified)
        assertNull(entry.etag)
    }

    @Test
    fun `a body with no validators is never remembered`() {
        HttpCache.put(
            url = "https://www.vpngate.net/en/",
            body = "<html>feed</html>",
            etag = null,
            lastModified = null,
            contentType = "text/html",
        )

        assertNull(
            "without a validator there is nothing to revalidate with",
            HttpCache.get("https://www.vpngate.net/en/"),
        )
        assertEquals(0, HttpCache.sizeForTest())
    }

    @Test
    fun `an unknown url has no entry`() {
        assertNull(HttpCache.get("https://www.vpngate.net/never-fetched"))
    }

    @Test
    fun `an oversized body is not retained`() {
        val huge = "x".repeat(HttpCache.MAX_ENTRY_CHARS + 1)
        HttpCache.put("https://www.vpngate.net/huge", huge, "\"e\"", null, "text/plain")

        assertNull(HttpCache.get("https://www.vpngate.net/huge"))
    }

    @Test
    fun `the cache is bounded and evicts the least recently used url`() {
        // One more than the entry cap, so at least one eviction must happen.
        repeat(HttpCache.MAX_ENTRIES + 2) { i ->
            HttpCache.put(
                url = "https://www.vpngate.net/page-$i",
                body = "body-$i",
                etag = "\"e$i\"",
                lastModified = null,
                contentType = "text/plain",
            )
        }

        assertTrue(
            "size must stay within the cap, was ${HttpCache.sizeForTest()}",
            HttpCache.sizeForTest() <= HttpCache.MAX_ENTRIES,
        )
        // The newest entries survive; the oldest is gone.
        assertNull(HttpCache.get("https://www.vpngate.net/page-0"))
        assertNotNull(HttpCache.get("https://www.vpngate.net/page-${HttpCache.MAX_ENTRIES + 1}"))
    }

    @Test
    fun `clear drops every entry`() {
        HttpCache.put("https://www.vpngate.net/en/", "body", "\"e\"", null, "text/html")
        assertEquals(1, HttpCache.sizeForTest())

        HttpCache.clear()

        assertEquals(0, HttpCache.sizeForTest())
        assertEquals(0, HttpCache.charsForTest())
        assertNull(HttpCache.get("https://www.vpngate.net/en/"))
    }

    @Test
    fun `a stored body round-trips byte for byte`() {
        // The CSV feed is the case that matters: the body must come back
        // exactly as validated, not normalised or re-encoded.
        val csv = "\uFEFFHostName,IP,Score\r\npublic-vpn-219.opengw.net,219.100.37.206,100\r\n"
        HttpCache.put("https://www.vpngate.net/api/iphone/", csv, "\"v1\"", null, "text/csv")

        assertEquals(csv, HttpCache.get("https://www.vpngate.net/api/iphone/")!!.body)
    }
}
