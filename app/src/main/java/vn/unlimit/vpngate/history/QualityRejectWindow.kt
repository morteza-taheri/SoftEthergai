package vn.unlimit.vpngate.history

/**
 * BLOCK v1.0.69 PART 1D — the deduplication primitive for the quality-rejection
 * producer.
 *
 * Approved rule (OD-5 / PART 1D): a quality rejection is recorded at most **once
 * per equivalent rejection** within [QUALITY_REJECT_WINDOW_MS]. Two rejections
 * are equivalent when they share the same caller-supplied key, derived from the
 * server identity (`hostName`) plus the attempt context — so independent servers
 * (and independent refreshes) are never suppressed together.
 *
 * Why a separate pure class: the controller's rejection path is complex and the
 * build environment is currently blocked, so the *decision* is isolated here,
 * where it is exhaustively testable without a database or a coroutine. The
 * controller wiring (emitting a `QUALITY_REJECTED` record when `shouldRecord`
 * returns true) is a separate, build-verified step.
 *
 * The clock is injectable so tests are deterministic — no real 10 s sleeps.
 * [shouldRecord] is `@Synchronized`, so concurrent callers can never double-count
 * the same window.
 */
class QualityRejectWindow(
    private val windowMs: Long = QUALITY_REJECT_WINDOW_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lastRecordedAt = HashMap<String, Long>()

    /**
     * Returns true when [key] may record a rejection now (and arms its window);
     * false when the same key already recorded within [windowMs].
     *
     * Only the *decision* is here: the caller increments `qualityRejectionCount`
     * (and nothing else) when this returns true.
     *
     * Bounded state (PART 1 §4.3): entries whose window has fully elapsed are
     * pruned on each call, so the map is bounded by the distinct keys seen within
     * one window and cannot grow without bound under sustained unique keys. An
     * **active** key (age `< windowMs`) is never pruned early.
     */
    @Synchronized
    fun shouldRecord(key: String): Boolean {
        val now = clock()
        val iterator = lastRecordedAt.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value >= windowMs) iterator.remove()
        }
        if (lastRecordedAt.containsKey(key)) return false
        lastRecordedAt[key] = now
        return true
    }

    /** Test-only: the number of live (un-pruned) keys. */
    internal fun trackedKeyCount(): Int = lastRecordedAt.size

    companion object {
        /** The approved quality-rejection deduplication window (OD-5). */
        const val QUALITY_REJECT_WINDOW_MS: Long = 10_000L
    }
}
