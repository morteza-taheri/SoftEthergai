package vn.unlimit.softether.controller

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Stage 2F-F.28Y — single-owner coordination of one attempt's native connection.
 *
 * ## The defect this closes
 *
 * F.28X proved two paths could release the process-wide native slot while the primary
 * connect was still executing, and that `nativeDestroy` returning is **not** evidence the
 * connect flow exited. The native side settles that question:
 *
 * ```
 * softether_jni.c:139-145   pthread_mutex_lock(&conn->connect_mutex)   // PER-CONNECTION
 *                          softether_connect_with_hub(...)
 *                          pthread_mutex_unlock(&conn->connect_mutex)
 * softether_protocol.c:1040 if (conn->additional_connecting) pthread_join(...)  // background thread only
 * softether_protocol.c:1050 softether_disconnect(conn); ... free(conn)
 * ```
 *
 * `connect_mutex` is per-connection and wraps only the connect call, so it serialises
 * nothing across connections and `softether_destroy` never waits for the primary connect.
 * The only thread that can *know* the connect has exited is the connect flow itself.
 *
 * The pre-fix `destroyResources()` therefore had two broken shapes:
 *
 *  1. it awaited a 500 ms latch, then destroyed the structure anyway on timeout, then
 *     released the lease — so a still-running connect was freed underneath, and a second
 *     attempt was admitted;
 *  2. when `nativeHandle == 0` it skipped the whole block and released immediately, even
 *     with `connectInFlight == true`. A zero handle does not mean "no native operation":
 *     it also means "a flow has started but not yet published its handle".
 *
 * ## The protocol
 *
 * * **One [Flow] per native connect attempt.** Registered under [lock] *before*
 *   `nativeCreate`, so there is no instant where a flow is running but unregistered. It
 *   publishes its handle as soon as `nativeCreate` returns, and counts down its exit
 *   latch as the very last thing it does — after every native call the flow makes.
 * * **Teardown never frees.** [requestTeardown] sets the terminal destroyed flag and
 *   snapshots the active flow under [lock], then *interrupts* the flow's socket
 *   (`shutdown`, which never frees) and waits, bounded, for the flow to exit. On timeout
 *   it returns [TeardownOutcome.UNRESOLVED] having neither destroyed nor released.
 * * **Exactly one cleanup owner.** Claimed with a compare-and-set under [lock], by
 *   whichever arrives first: the exiting flow (when teardown is already pending) or
 *   teardown (when the flow has already exited). Everyone else returns without touching
 *   the handle or the lease. This is what makes repeated and concurrent
 *   `destroyResources()` safe.
 * * **Destroy, then release.** The owner read-then-zeros the handle through
 *   [NativeHandleOwner] and only then releases the lease — and only if the destroy did
 *   not throw. A throwing destroy leaves ownership held.
 *
 * ## Why destruction is not merely deferred to the caller's thread
 *
 * If teardown times out, ownership passes to the connect flow, which performs cleanup
 * the instant it actually leaves native code. That is not "releasing on a timeout": the
 * release is still conditioned on observed completion, it simply happens at the only
 * moment completion is knowable. If the flow never returns, the slot stays claimed —
 * the fail-closed direction required by invariant 9.
 *
 * ## Thread-safety
 *
 * [lock] guards only the flag, the flow pointer and the claim. No native call and no
 * blocking wait ever runs while it is held. `nativeHandle` itself stays a plain field on
 * the owner, mutated exclusively through [NativeHandleOwner.claimHandle]'s read-then-zero,
 * which is what keeps two destroyers from acting on the same handle.
 *
 * Stage 2F-F.28AC: the read-then-zero used to return a bare `Long`, so "no handle was ever
 * created" and "a handle was taken and its destroy failed" arrived as the same `0L`.
 * [NativeHandleClaim] makes that distinction explicit and unmissable, and
 * [runCleanup]'s release decision is made from it rather than from the zero.
 *
 * @see NativeLifetimeRegistry for the process-wide admission this coordinates with.
 */
internal class NativeAttemptCoordinator(
    private val handles: NativeHandleOwner,
    /** Releases process-wide ownership. Must itself be idempotent. */
    private val releaseLease: () -> Unit,
    /** Socket shutdown only. Must be safe to call while a connect is executing. */
    private val interruptNative: (Long) -> Unit,
    /** Frees the native structure. Must only be called with no flow executing. */
    private val destroyNative: (Long) -> Unit,
    /** Diagnostic sink for unresolved states. Never used to release ownership. */
    private val onUnresolved: (String) -> Unit,
) {

    /** Outcome of a teardown request. Only [CONFIRMED] implies ownership was released. */
    enum class TeardownOutcome {
        /** Cleanup ran and the lease was released by this call. */
        CONFIRMED,

        /** Another caller already owned and completed cleanup. */
        ALREADY_CONFIRMED,

        /**
         * Ownership is retained: a connect flow may still be executing, or the destroy
         * failed. A later attempt stays refused until the flow exits.
         */
        UNRESOLVED,
    }

    /**
     * True when the returned outcome asserts that native cleanup finished.
     *
     * Only these two outcomes mean "the structure was freed and ownership was released".
     * [UNRESOLVED] means exactly the opposite, so a caller that reports completion must
     * branch on this rather than on "the call returned".
     */
    fun TeardownOutcome.isCleanupConfirmed(): Boolean =
        this == TeardownOutcome.CONFIRMED || this == TeardownOutcome.ALREADY_CONFIRMED

    private val lock = Any()

    /** Terminal. Set under [lock] by the first teardown; never cleared. */
    private var destroyed = false

    /** Terminal. Set under [lock] by the first teardown; never cleared. */
    private var teardownRequested = false

    private var activeFlow: Flow? = null

    private var cleanupClaimed = false

    private var cleanupFinished = false

    private var cleanupConfirmed = false

    /** Counted down when a cleanup attempt finishes, successfully or not. */
    private val cleanupDone = CountDownLatch(1)

    /** True once teardown has begun. Never returns to false. */
    fun isDestroyed(): Boolean = synchronized(lock) { destroyed }

    /**
     * One native connect attempt's participation in this protocol.
     *
     * Owned by the flow's own thread. [exit] must be called from that thread as the
     * last action of the flow, after every native call the flow makes has returned.
     */
    inner class Flow internal constructor() {
        @Volatile
        private var handle: Long = 0L

        private val exited = CountDownLatch(1)

        /** The handle this flow published, or `0` before `nativeCreate` returned. */
        internal val currentHandle: Long get() = handle

        /** Publish the handle returned by `nativeCreate`, for interruption purposes. */
        fun publishHandle(handle: Long) {
            this.handle = handle
        }

        internal fun awaitExit(timeoutMs: Long): Boolean =
            exited.await(timeoutMs, TimeUnit.MILLISECONDS)

        /**
         * Leave the protocol: this flow will make no further native call.
         *
         * Counts the exit latch down *first*, so a teardown already waiting on it is
         * released promptly, then — if teardown is already pending — claims cleanup
         * ownership on this thread's behalf, because this is the only place where
         * "native code has exited" is a fact rather than an assumption.
         */
        fun exit() {
            val ownsCleanup = synchronized(lock) {
                exited.countDown()
                if (activeFlow === this) activeFlow = null
                // Ownership is only taken when teardown is already pending. Otherwise the
                // connection is live and the eventual teardown will clean it up.
                teardownRequested && claimCleanupLocked(FLOW_EXIT)
            }
            if (ownsCleanup) runCleanup()
        }
    }

    /**
     * Register a new connect flow, or return `null` if teardown has begun.
     *
     * The destroyed re-check happens **inside** [lock], which is the whole point: flow
     * registration and the terminal flag are mutually exclusive, so there is no window in
     * which a flow starts after teardown decided no flow was running. The pre-fix code
     * read the flag, then created native work, and a teardown in between skipped
     * synchronisation entirely.
     */
    fun beginFlow(): Flow? = synchronized(lock) {
        if (destroyed) return null
        Flow().also { activeFlow = it }
    }

    /**
     * Begin teardown: mark terminal, unblock any executing connect, and take cleanup
     * ownership if — and only if — no flow can still be using the structure.
     *
     * Never blocks longer than `timeoutMs`, and on timeout performs no destruction and no
     * release; the exiting flow takes over instead.
     */
    fun requestTeardown(timeoutMs: Long): TeardownOutcome {
        val flow: Flow?
        synchronized(lock) {
            destroyed = true
            teardownRequested = true
            flow = activeFlow
        }

        // Unblock before waiting. `interruptNative` only shuts the socket down; it never
        // frees anything, so it is safe while the connect is mid-TLS.
        flow?.let { active ->
            val handle = active.currentHandle
            if (handle != 0L) {
                try {
                    interruptNative(handle)
                } catch (e: Exception) {
                    onUnresolved("socket interrupt failed for handle $handle: ${e.message}")
                }
            }
        }

        if (flow != null && !flow.awaitExit(timeoutMs)) {
            onUnresolved(
                "connect flow did not exit within ${timeoutMs}ms; ownership retained and the " +
                        "exiting flow will complete cleanup",
            )
            return TeardownOutcome.UNRESOLVED
        }

        val ownsCleanup = synchronized(lock) { claimCleanupLocked(TEARDOWN) }
        if (!ownsCleanup) {
            // Someone else owns it. Wait for their result rather than racing them, so a
            // caller can still report an honest outcome.
            val finished = cleanupDone.await(timeoutMs, TimeUnit.MILLISECONDS)
            return if (finished && cleanupConfirmed) TeardownOutcome.ALREADY_CONFIRMED
            else TeardownOutcome.UNRESOLVED
        }

        runCleanup()
        return if (cleanupConfirmed) TeardownOutcome.CONFIRMED else TeardownOutcome.UNRESOLVED
    }

    /**
     * Whether a cleanup attempt has finished, and whether it confirmed native completion.
     *
     * Returns as soon as the attempt finishes either way, so a caller is never left
     * waiting on a failure.
     */
    fun awaitCleanupConfirmed(timeoutMs: Long): Boolean {
        cleanupDone.await(timeoutMs, TimeUnit.MILLISECONDS)
        synchronized(lock) { return cleanupConfirmed }
    }

    /** Claim cleanup ownership. Caller must hold [lock]. */
    private fun claimCleanupLocked(reason: String): Boolean {
        if (cleanupClaimed) return false
        cleanupClaimed = true
        cleanupOwnerReason = reason
        return true
    }

    private var cleanupOwnerReason: String = NONE

    /**
     * Destroy the native structure, then release the lease — in that order.
     *
     * Only ever reached by the cleanup owner, and never while a flow can still be
     * executing. The destroy is attempted outside every lock, and a throwing destroy
     * leaves ownership held: reporting teardown complete on a failed free would let a
     * replacement attempt allocate against a connection whose fate is unknown.
     */
    private fun runCleanup() {
        var confirmed = false
        when (val claim = handles.claimHandle()) {
            NativeHandleClaim.NothingOutstanding -> confirmed = true

            is NativeHandleClaim.Claimed -> {
                try {
                    destroyNative(claim.handle)
                    confirmed = true
                } catch (e: Throwable) {
                    // A destroy that threw has freed nothing this call can prove. The lease
                    // is retained rather than released, so the failure stays visible instead
                    // of being converted into a confirmed teardown.
                    confirmed = false
                    onUnresolved(
                        "native destroy of handle ${claim.handle} threw " +
                                "${e.javaClass.simpleName}: ${e.message}",
                    )
                }
            }

            NativeHandleClaim.Lost -> {
                // There is nothing left to free — and nothing that may be released either.
                // A handle that was cleared without a proven destroy leaves the native
                // structure's fate unknown, which is exactly the condition that must never
                // be laundered into "cleanup confirmed".
                confirmed = false
                onUnresolved(
                    "native handle was claimed for destruction but its cleanup was never " +
                            "confirmed; nothing is freed and the lease is retained",
                )
            }
        }
        if (confirmed) {
            releaseLease()
        }
        synchronized(lock) {
            cleanupConfirmed = confirmed
            cleanupFinished = true
        }
        cleanupDone.countDown()
    }

    /** Human-readable owner, for diagnostics. */
    fun describe(): String = synchronized(lock) {
        val phase = when {
            cleanupFinished && cleanupConfirmed -> "cleanup confirmed"
            cleanupFinished -> "cleanup failed"
            cleanupClaimed -> "cleanup in progress (owner=$cleanupOwnerReason)"
            activeFlow != null -> "connect flow active"
            teardownRequested -> "teardown requested"
            else -> "live"
        }
        "destroyed=$destroyed $phase"
    }

    private companion object {
        const val FLOW_EXIT = "connect-flow-exit"
        const val TEARDOWN = "teardown"
        const val NONE = "none"
    }
}

/**
 * Stage 2F-F.28AC: the outcome of claiming this attempt's native handle for destruction.
 *
 * ## Why this type exists
 *
 * F.28AB's audit traced a concrete path on which a *failed* native destruction became
 * indistinguishable from a successful one, and a raw `0L` is what did it. The handle field
 * was cleared **before** `nativeDestroy` ran, so when the destroy threw and the exception was
 * logged rather than propagated, every later reader saw `handle == 0` and read that as proof
 * that nothing native remained. `runCleanup` then released the process-wide lease on the
 * strength of that reading, reporting a confirmed teardown while a `softether_connection_t`
 * was still allocated.
 *
 * Three states have to stay apart, and none of them is expressible as a sentinel `Long`:
 * a handle that was never created, a handle claimed for destruction, and a handle that was
 * claimed and then lost without a proven destroy.
 */
internal sealed interface NativeHandleClaim {

    /**
     * Nothing native is outstanding for this attempt.
     *
     * Either no handle was ever published, or one was published and its destruction was
     * confirmed before it was cleared. The **only** state that may release ownership without
     * making a native call.
     */
    data object NothingOutstanding : NativeHandleClaim

    /** A live handle was claimed. The claimant owns its destruction and must report it. */
    data class Claimed(val handle: Long) : NativeHandleClaim

    /**
     * A handle was published, then cleared without a confirmed destroy.
     *
     * The native structure's fate is unknown: it may have been freed, or it may still be
     * allocated. Nothing may be freed again — that is how a second destruction is avoided —
     * and nothing may be released, because that would admit a second native attempt against
     * a connection whose memory is unaccounted for.
     *
     * This is a fail-closed, report-it state. It is never a success.
     *
     * Residual risk, stated rather than hidden: the unaccounted structure is leaked, and the
     * lease stays held for the rest of the process. That is the deliberate trade — a leak is
     * recoverable by process death, whereas releasing the slot would allow a *new* native
     * operation while the old one's memory is unknown.
     */
    data object Lost : NativeHandleClaim
}

/**
 * Claim the current attempt's native handle, exactly once.
 *
 * Kept as an interface so the coordination can be tested without a `SoftEtherClient`, and so
 * the "exactly one thread gets the handle" property is expressed once instead of being
 * re-derived at each destroy site.
 */
internal fun interface NativeHandleOwner {
    /**
     * Atomically read and clear the current handle, reporting what was found.
     *
     * Implementations must **not** collapse [NativeHandleClaim.Lost] into
     * [NativeHandleClaim.NothingOutstanding]. That collapse is the defect this API exists to
     * make impossible: it is what let a failed native destroy be reported as a completed
     * teardown.
     *
     * A `NothingOutstanding` result is also not proof that no native operation was *ever*
     * running — see [NativeAttemptCoordinator.beginFlow].
     */
    fun claimHandle(): NativeHandleClaim
}