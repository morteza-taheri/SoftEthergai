package vn.unlimit.vpngate.automode

/**
 * Session-scoped memory of servers that **connect and then drop quickly**.
 *
 * ## The failure this exists for
 *
 * [AutoModeRecoveryPolicy] handles attempts that never produced a tunnel: it
 * backs off and rotates. It deliberately does NOT cover the other half of the
 * reported "connects and disconnects in a loop" behaviour — a server that
 * *does* reach CONNECTED and then dies seconds later. Every one of those
 * cycles looks like a success followed by a loss, so the recovery policy's
 * failure counter is reset on each connect and the server is never blamed.
 * With a high quality score it is also the first candidate the next run picks,
 * and the run's own `rotateAwayFromDropped` is defeated whenever the candidate
 * tail is empty (a one-server override list, or a full re-walk after
 * `sessionTriedKeys` is cleared).
 *
 * The result on an unstable server is a stable, repeatable loop: connect, die
 * after N seconds, reconnect to the same server, die again. It is not a
 * terminal error — the pass-5 fix removed that — but it is still unusable.
 *
 * ## What this class does
 *
 * It records how long each tunnel actually survived. A drop that happens
 * inside [rapidDropMs] counts as a strike; [strikesToQuarantine] consecutive
 * strikes put the server on a cooldown, and a server that survives
 * [stableUptimeMs] is rehabilitated immediately. The cooldown grows
 * exponentially per strike so a persistently unstable server is retried less
 * and less often instead of never (a permanent ban would be wrong: the same
 * server is often fine an hour later).
 *
 * ## Bounds
 *
 * At most [MAX_ENTRIES] servers are remembered; the oldest entry is evicted
 * first. Entries are `(strikes, timestamps)` only — no hostnames beyond the
 * caller's opaque key, no traffic, nothing persisted. A process restart clears
 * everything, which is the right lifetime for a transient network condition.
 *
 * Pure Kotlin with an injectable clock so the policy is JVM-testable without
 * sleeping.
 */
class ServerQuarantine(
    /** A tunnel that dies sooner than this after CONNECTED counts as a flap. */
    private val rapidDropMs: Long = RAPID_DROP_MS,
    /** Consecutive flaps before the server is taken out of rotation. */
    private val strikesToQuarantine: Int = STRIKES_TO_QUARANTINE,
    /** First cooldown; doubled per extra strike up to [maxCooldownMs]. */
    private val baseCooldownMs: Long = BASE_COOLDOWN_MS,
    private val maxCooldownMs: Long = MAX_COOLDOWN_MS,
    /** A tunnel that survives this long is considered stable and is forgiven. */
    private val stableUptimeMs: Long = STABLE_UPTIME_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private data class Entry(
        var strikes: Int,
        var quarantinedUntil: Long,
        var lastEventAt: Long,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    /**
     * Record how long the tunnel to [key] lived before it was lost.
     *
     * A stable session forgives the server entirely. A rapid one adds a strike
     * and, from [strikesToQuarantine] onward, starts (or extends) the cooldown.
     *
     * @return the cooldown in ms that now applies to [key], or 0 when it is
     *   still usable. Returned so the caller can put it in the log without
     *   re-reading the map.
     */
    fun onTunnelLost(key: String, uptimeMs: Long): Long {
        if (key.isBlank()) return 0L
        val now = clock()
        synchronized(lock) {
            // Bound the map before inserting a NEW key, so the entry being
            // written can never be the one evicted. A long session against many
            // servers that each fail once would otherwise grow the map without
            // ever reaching the quarantine branch.
            if (!entries.containsKey(key)) evictIfNeeded()
            val entry = entries.getOrPut(key) { Entry(0, 0L, now) }
            entry.lastEventAt = now
            if (uptimeMs >= stableUptimeMs) {
                entries.remove(key)
                return 0L
            }
            entry.strikes++
            if (entry.strikes < strikesToQuarantine) return 0L
            val extra = (entry.strikes - strikesToQuarantine).coerceAtMost(MAX_DOUBLINGS)
            val cooldown = (baseCooldownMs shl extra).coerceAtMost(maxCooldownMs)
            entry.quarantinedUntil = now + cooldown
            return cooldown
        }
    }

    /** True while [key] is inside a cooldown. */
    fun isQuarantined(key: String): Boolean {
        if (key.isBlank()) return false
        synchronized(lock) {
            val entry = entries[key] ?: return false
            if (entry.quarantinedUntil <= clock()) {
                // Cooldown expired: the server is eligible again, but it keeps
                // its strike count so one more flap re-quarantines it at the
                // next (longer) step instead of starting from zero.
                entry.quarantinedUntil = 0L
                return false
            }
            return true
        }
    }

    /** Remaining cooldown for [key], or 0. Diagnostics and tests. */
    fun remainingCooldownMs(key: String): Long {
        synchronized(lock) {
            val until = entries[key]?.quarantinedUntil ?: return 0L
            return (until - clock()).coerceAtLeast(0L)
        }
    }

    /** Strikes recorded for [key]. Diagnostics and tests. */
    fun strikesFor(key: String): Int = synchronized(lock) { entries[key]?.strikes ?: 0 }

    /** Forget everything (user stop, or a test teardown). */
    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    /** Number of remembered servers. Bounded by [MAX_ENTRIES]. */
    val size: Int get() = synchronized(lock) { entries.size }

    /**
     * Evict the oldest entries until one more can be inserted without exceeding
     * [MAX_ENTRIES]. Called *before* the insertion so the entry being written
     * is never the one removed.
     */
    private fun evictIfNeeded() {
        while (entries.size >= MAX_ENTRIES) {
            val oldest = entries.entries.minByOrNull { it.value.lastEventAt } ?: return
            entries.remove(oldest.key)
        }
    }

    companion object {
        /** A tunnel alive for less than a minute is a flap, not a session. */
        const val RAPID_DROP_MS = 60_000L

        /**
         * Two consecutive flaps. One is tolerated on purpose: a single drop can
         * be the phone's own radio, and banning a good server for that would
         * cost the user the best candidate for minutes.
         */
        const val STRIKES_TO_QUARANTINE = 2

        const val BASE_COOLDOWN_MS = 5 * 60_000L
        const val MAX_COOLDOWN_MS = 30 * 60_000L

        /**
         * A tunnel that carried traffic for two minutes was not flapping. It is
         * forgiven before the cooldown arithmetic, so a server that had a bad
         * minute an hour ago is not penalised forever.
         */
        const val STABLE_UPTIME_MS = 2 * 60_000L

        private const val MAX_DOUBLINGS = 3
        private const val MAX_ENTRIES = 128
    }
}
