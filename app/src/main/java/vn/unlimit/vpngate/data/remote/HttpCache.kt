package vn.unlimit.vpngate.data.remote

/**
 * Stage G-5 — a tiny, bounded, process-wide HTTP validator cache.
 *
 * Why
 * ---
 * A refresh re-downloads the same two feeds every time: the canonical HTML
 * (~0.5 MB) and the API CSV (~0.9 MB, most of it Base64 profiles). On a
 * filtered or metered link that is the dominant cost of "the list does not
 * update", and it is almost entirely wasted work: VPN Gate's feed changes on
 * the order of minutes, not seconds, and both endpoints publish validators
 * (`ETag` / `Last-Modified`).
 *
 * What this does
 * --------------
 * It remembers, per URL, the last body that was actually downloaded together
 * with the validators the server gave for it. The next request sends
 * `If-None-Match` / `If-Modified-Since`; when the server answers `304 Not
 * Modified` the remembered body IS the current content — that is precisely
 * what 304 asserts — so it is returned as a normal successful response and
 * the transfer costs two headers instead of a megabyte.
 *
 * Deliberate properties
 * ---------------------
 *  - **Process-wide.** The repository builds a fresh fetcher per rung and per
 *    refresh; an instance field would never survive long enough to help.
 *  - **Bounded.** At most [MAX_ENTRIES] bodies and [MAX_TOTAL_CHARS] characters
 *    are retained, evicting least-recently-used first, so a hostile or
 *    misconfigured source cannot grow the heap.
 *  - **Never a substitute for the network.** An entry is only ever *offered*
 *    as a validator. The body is returned solely when the server itself
 *    answers 304. A cache miss, an expired validator or a 200 all behave
 *    exactly as before this class existed.
 *  - **Testable.** [clear] and [sizeForTest] exist so the suite is not
 *    order-dependent.
 */
object HttpCache {

    /** One remembered body plus the validators the server attached to it. */
    data class Entry(
        val body: String,
        val etag: String?,
        val lastModified: String?,
        val contentType: String?,
        val storedAt: Long = System.currentTimeMillis(),
    )

    /** Bodies larger than this are never retained (the feeds are well under). */
    const val MAX_ENTRY_CHARS: Int = 2 * 1024 * 1024

    /** Total characters retained across all entries. */
    const val MAX_TOTAL_CHARS: Int = 8 * 1024 * 1024

    /** At most this many URLs are remembered. */
    const val MAX_ENTRIES: Int = 8

    private val lock = Any()

    /**
     * Access-ordered so the eldest entry is the least recently *used*, which
     * is what LRU eviction needs. Guarded by [lock] for every access.
     */
    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > MAX_ENTRIES
    }

    /** The validators + body remembered for [url], or null when none is usable. */
    fun get(url: String): Entry? = synchronized(lock) {
        entries[url]?.takeIf { it.etag != null || it.lastModified != null }
    }

    /**
     * Remember [body] for [url] along with its validators.
     *
     * A response with no validators at all is not cached: without an `ETag`
     * or `Last-Modified` there is nothing to revalidate with, so keeping the
     * body would only consume memory.
     */
    fun put(
        url: String,
        body: String,
        etag: String?,
        lastModified: String?,
        contentType: String?,
    ) {
        if (etag.isNullOrBlank() && lastModified.isNullOrBlank()) return
        if (body.isEmpty() || body.length > MAX_ENTRY_CHARS) return

        synchronized(lock) {
            entries[url] = Entry(body, etag, lastModified, contentType)
            trimToBudgetLocked()
        }
    }

    /** Drop everything (used by tests and by a "clear cache" action). */
    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    /** Number of remembered URLs (diagnostics/tests). */
    fun sizeForTest(): Int = synchronized(lock) { entries.size }

    /** Total retained characters (diagnostics/tests). */
    fun charsForTest(): Int = synchronized(lock) { entries.values.sumOf { it.body.length } }

    private fun trimToBudgetLocked() {
        var total = entries.values.sumOf { it.body.length }
        while (total > MAX_TOTAL_CHARS && entries.isNotEmpty()) {
            val eldest = entries.entries.iterator()
            if (!eldest.hasNext()) break
            val victim = eldest.next()
            total -= victim.value.body.length
            eldest.remove()
        }
    }
}
