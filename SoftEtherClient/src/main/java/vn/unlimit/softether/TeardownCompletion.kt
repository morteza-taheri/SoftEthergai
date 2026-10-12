package vn.unlimit.softether

/**
 * Stage 2F-F.28AA — what actually happened when a teardown finished.
 *
 * ## Why this type exists
 *
 * F.28Z traced four events that the previous signal conflated into one
 * argument-less callback:
 *
 * ```
 *  1. the teardown wait ended (or timed out)
 *  2. native cleanup was genuinely confirmed
 *  3. the process-wide native lease was released
 *  4. a callback claiming "teardown complete" was emitted
 * ```
 *
 * `SoftEtherVpnService.stopVpn()` emitted that callback unconditionally, 500 ms + 2 000 ms
 * after teardown began, whether or not any of 2 or 3 had happened. Auto Mode's
 * `disconnect()` therefore logged "Teardown confirmed complete" — and treated itself as
 * free to start the next server — while the previous `nativeConnectWithHub` was still
 * executing and the lease was still held. The next start was then refused by the registry,
 * and that refusal was indistinguishable from an unreachable server.
 *
 * ## The identity problem this also solves
 *
 * A session generation is **not** sufficient to identify a teardown. Within one session,
 * `stopVpn` can run repeatedly: the first call performs teardown, and later calls take the
 * re-entrant branch. Two waiters therefore exist inside one session, and a stale
 * completion from the first can otherwise satisfy a waiter armed for the second.
 *
 * [attemptId] is a strictly increasing, **process-wide** counter allocated per `stopVpn`
 * call, including the re-entrant ones. It lives in the service's companion object for the
 * same reason the listener registry does: both must stay monotonic across service
 * recreation, or a fresh service instance's id `1` would look older than a stale one the
 * watcher already saw.
 *
 * ## How a consumer uses it
 *
 * Accept only completions strictly newer than the last one accepted, then branch on
 * [outcome]. `TunnelStateWatcher` does exactly that.
 */
class TeardownCompletion private constructor(
    /** Strictly increasing per `stopVpn` call, for the life of the process. */
    val attemptId: Int,
    /** Session generation that was being torn down. For diagnostics and logging. */
    val sessionId: Int,
    val outcome: Outcome,
) {
    enum class Outcome {
        /**
         * Native cleanup ran and finished: the structure was freed and the lease was
         * released. The only outcome that may be read as "this session is gone".
         */
        NATIVE_CONFIRMED,

        /**
         * A connect flow is still executing, or a native destroy failed. **Ownership is
         * retained** and every later start is refused by the registry.
         *
         * This must never be reported to a consumer as permission to begin another native
         * operation. It exists so a waiter does not hang forever on an unbounded native
         * call — bounded waiting is required, reporting honestly is also required.
         */
        NATIVE_UNRESOLVED,

        /**
         * `stopVpn` was re-entered and started nothing, because teardown had already been
         * performed for this session and nothing was left to release.
         *
         * Reported instead of a fabricated success. A consumer may treat it as "terminal
         * state published" but it carries **no** native-confirmation claim of its own.
         */
        NOOP_REENTRANT,

        /**
         * A non-SoftEther stack (currently only the ics-openvpn one) reported a terminal
         * state. That stack exposes no post-destroy signal at all, so this outcome asserts
         * nothing about native cleanup and is never filtered by [attemptId] — see
         * [foreignStackTerminal].
         */
        FOREIGN_STACK_TERMINAL,
    }

    /**
     * True only for [Outcome.NATIVE_CONFIRMED].
     *
     * Deliberately narrow. A consumer must not infer "safe to start a new native
     * operation" from anything else.
     */
    val nativeCleanupConfirmed: Boolean get() = outcome == Outcome.NATIVE_CONFIRMED

    fun describe(): String = "teardown#${attemptId}(session=$sessionId, $outcome)"

    override fun toString(): String = describe()

    companion object {
        /** Builds a result for the SoftEther stack. */
        fun of(attemptId: Int, sessionId: Int, outcome: Outcome): TeardownCompletion =
            TeardownCompletion(attemptId, sessionId, outcome)

        /**
         * A terminal report from a stack with no teardown-identity signal.
         *
         * [attemptId] is `0` because it is not part of the SoftEther monotonic sequence;
         * consumers of the SoftEther sequence must filter this out rather than compare it.
         */
        fun foreignStackTerminal(sessionId: Int = 0): TeardownCompletion =
            TeardownCompletion(0, sessionId, Outcome.FOREIGN_STACK_TERMINAL)

        /** True when [attemptId] belongs to the filtered SoftEther teardown sequence. */
        fun isSequenced(result: TeardownCompletion): Boolean =
            result.outcome != Outcome.FOREIGN_STACK_TERMINAL
    }
}