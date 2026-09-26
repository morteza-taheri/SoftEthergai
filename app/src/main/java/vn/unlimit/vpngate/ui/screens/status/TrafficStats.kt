package vn.unlimit.vpngate.ui.screens.status

/**
 * Pure helpers for the Status screen's traffic numbers.
 *
 * Extracted from [StatusViewModel] so the arithmetic is unit-testable without
 * Android (no Context/Resources, no SharedPreferences).
 */
internal object TrafficStats {

    /**
     * Per-second transfer rate, in **bytes**, from a delta over an interval.
     *
     * The interval is measured, never assumed, so a late or coalesced tick does
     * not inflate the rate. A non-positive interval (clock going backwards, or a
     * snapshot published twice) yields 0 rather than a bogus spike.
     */
    fun bytesPerSecond(deltaBytes: Long, intervalMs: Long): Long {
        if (deltaBytes <= 0L || intervalMs <= 0L) return 0L
        // Guard the multiply: a >~230 TB/s delta would otherwise overflow.
        if (deltaBytes > Long.MAX_VALUE / 1000L) return Long.MAX_VALUE
        return deltaBytes * 1000L / intervalMs
    }

    /**
     * The all-zero speed label, using the same unit the formatter produces
     * (`humanReadableByteCount(0, speed = true, ...)` returns "0 bit/s").
     * Kept here so the default state of [StatusUiState] cannot drift to "kbps".
     */
    fun zeroSpeed(): String = "0 bit/s"

    /** The all-zero volume label, matching `humanReadableByteCount(0, false, ...)`. */
    fun zeroVolume(): String = "0 B"
}
