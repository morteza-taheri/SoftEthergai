package vn.unlimit.softether

/**
 * Stage 2F-F.28W — process-wide native-operation ownership and fail-closed admission.
 *
 * ## The defect this exists to close
 *
 * F.28U traced the gap this class closes. The service's start gate decided whether a
 * replacement connection attempt could begin using three *Kotlin* references:
 *
 * ```
 * service:418   val sessionResourcesLive =
 * service:419       controller != null || tunOwnership.isHeld() || connectionJob != null
 * service:420   val decision = sessionPolicy.decideStart(resourcesLive = sessionResourcesLive)
 * ```
 *
 * Teardown clears all three **synchronously** and only then dispatches the native
 * cleanup asynchronously:
 *
 * ```
 * service:771-772   connectionJob?.cancel(); connectionJob = null
 * service:783       controller = null
 * service:794       tunOwnership.release(tearingDown)
 * service:800-803   serviceScope.launch(NonCancellable) { … destroyResources() … }
 * ```
 *
 * So by the time the first native call runs, the gate already reads "idle" and admits
 * the next attempt. Meanwhile the previous session's native operation is still running:
 *
 * ```
 * controller:402-423   client.nativeConnectWithHub(…)   // blocking JNI, not cancellable
 * controller:429       if (isCancelled.get())           // checked only AFTER it returns
 * controller:690-695   nativeTeardownGate.await(500 ms) // advisory only
 * controller:695       client.nativeDestroy(handle)     // blocks on connect_mutex, unbounded
 * ```
 *
 * Two `softether_connection_t` structs can therefore coexist. Note also that
 * `performConnect` clears the cancellation flag itself (`controller:266`
 * `isCancelled.set(false)`) and has no suspension point before `nativeCreate`
 * (`controller:282`), so a cancelled connect can still reach the blocking call — the
 * "zombie connect" the surrounding comments already describe.
 *
 * ## What this class does and does not claim
 *
 * It is a **Kotlin-side ledger**, and it is deliberately fail-closed: when a native
 * operation cannot be shown to have finished, admission is denied. That is the correct
 * default because the alternative — admitting a replacement on a timeout — is what
 * produced the defect.
 *
 * It does **not** invent a cancellation guarantee. There is none: `nativeConnectWithHub`
 * and `nativeDestroy` have no cancellation channel, and the native `cancel_flag`
 * (`softether_protocol.c`) is function-local and unreachable from any JNI entry point.
 * A timeout here is a *reporting* deadline only; it never releases ownership.
 *
 * ## Why this is a ledger and not a state machine on the session
 *
 * The session lifecycle (`SoftEtherSessionPolicy`) tracks logical lifecycle state. This
 * tracks physical native-resource lifetime. They are different facts with different
 * completion evidence: a session can be logically stopped while its native call is
 * still running, and a session can be logically fresh while an obsolete attempt is
 * still blocked in JNI. Folding the second into the first is exactly what made the old
 * gate blind.
 *
 * ## Scope
 *
 * This is intentionally **SoftEther-only**. OpenVPN and SSTP have their own lifecycles
 * and neither consults `SoftEtherSessionPolicy` nor this class. Bringing them under a
 * shared gate is a separate change; see the F.28W report.
 *
 * ## Thread-safety
 *
 * All state is guarded by one [lock], and no method holds it across a blocking native
 * call. Reservation is a single short synchronized block, which is what makes admission
 * atomic with respect to concurrent connect requests without needing a second lock or
 * an atomic-state machine.
 *
 * @see ConnectionController for the per-attempt native memory-safety chain this does
 *   not replace: `connectInFlight`, `nativeTeardownGate`, the read-then-zero handle
 *   idiom, and the native `connect_mutex` ordering.
 */
class NativeLifetimeRegistry {

    /**
     * Physical phase of the single native operation the registry admits at a time.
     *
     * Kept to four values because each one carries a distinct invariant that admission
     * and reporting depend on. There is deliberately **no** "unconfirmed" state: a
     * reporting deadline that expires does not change the phase and does not release
     * ownership — see [markDeadlineExpired].
     */
    enum class Phase {
        /** Admitted. No native call has been entered yet for this attempt. */
        RESERVED,

        /** A native call is executing, or was entered and may still be executing. */
        CONNECTING,

        /** The connection is established and this attempt owns a live native handle. */
        CONNECTED,

        /**
         * Teardown was requested. Native cleanup is executing, or is blocked and may
         * never complete. Ownership is held until [confirmTeardown] succeeds.
         */
        TEARDOWN,
    }

    /**
     * A granted, exclusive claim on the process's single native-operation slot.
     *
     * Issued only by [tryAcquire]. Every mutating call is scoped to this identity, so a
     * completion arriving from an obsolete attempt cannot alter or release a newer
     * attempt's reservation.
     */
    class Lease internal constructor(internal val attemptId: Long) {
        override fun toString(): String = "Lease(attempt=$attemptId)"
    }

    private val lock = Any()

    /** Monotonic attempt counter. Ids are never reused within a process. */
    private var lastAttemptId = NO_ATTEMPT

    /** Id of the owning attempt, or [NO_ATTEMPT] when no operation is unresolved. */
    private var activeAttemptId = NO_ATTEMPT

    private var activePhase = Phase.RESERVED

    /**
     * True once a reporting deadline expired for [activeAttemptId].
     *
     * Purely informational. It never clears [activeAttemptId], because a timeout is not
     * evidence that a blocking JNI call returned.
     */
    private var deadlineExpired = false

    /**
     * Try to claim the exclusive native-operation slot.
     *
     * Fails while any earlier attempt's native operation is unresolved, which is the
     * property the old start gate lacked. Callers must acquire **before** the first
     * native call, so there is no window in which native work is running while the
     * registry believes the process is idle.
     *
     * @return a [Lease] on success, or `null` when a previous attempt has not been
     *   confirmed torn down. Never blocks and never queues.
     */
    fun tryAcquire(): Lease? = synchronized(lock) {
        if (activeAttemptId != NO_ATTEMPT) {
            return@synchronized null
        }
        lastAttemptId += 1
        activeAttemptId = lastAttemptId
        activePhase = Phase.RESERVED
        deadlineExpired = false
        Lease(activeAttemptId)
    }

    /** Record that [lease] is about to enter its first native call. */
    fun markNativeEntered(lease: Lease?) = mutate(lease) { it.activePhase = Phase.CONNECTING }

    /** Record that [lease]'s connection is established and owns a live handle. */
    fun markConnected(lease: Lease?) = mutate(lease) { it.activePhase = Phase.CONNECTED }

    /** Record that teardown was requested for [lease]. Ownership is retained. */
    fun markTeardownRequested(lease: Lease?) = mutate(lease) { it.activePhase = Phase.TEARDOWN }

    /**
     * Record that a reporting deadline expired for [lease].
     *
     * Advisory only. The reservation is deliberately left in place: the caller is
     * saying "I stopped waiting", not "the native call finished".
     */
    fun markDeadlineExpired(lease: Lease?) = mutate(lease) { it.deadlineExpired = true }

    /**
     * Release ownership for [lease], but only once its native cleanup has genuinely
     * returned.
     *
     * Attempt-scoped and one-shot:
     *  - a `null` lease (or one from a superseded attempt) never releases anything;
     *  - a second call for the same attempt returns `false`, which is what makes
     *    repeated or concurrent teardown safe and gives exactly-once release.
     *
     * @return true only for the call that actually released the reservation.
     */
    fun confirmTeardown(lease: Lease?): Boolean = synchronized(lock) {
        val attemptId = lease?.attemptId ?: return@synchronized false
        if (attemptId != activeAttemptId) return@synchronized false
        activeAttemptId = NO_ATTEMPT
        activePhase = Phase.RESERVED
        deadlineExpired = false
        true
    }

    /**
     * Release ownership without a lease.
     *
     * Reserved for the one situation where the attempt identity cannot be carried —
     * a controller that was destroyed before it ever ran native code. A `null` lease is
     * rejected everywhere else so that ownership can never be dropped by accident.
     *
     * @return true only if an unresolved attempt was actually cleared.
     */
    fun confirmTeardownWithoutLease(): Boolean = synchronized(lock) {
        if (activeAttemptId == NO_ATTEMPT) return@synchronized false
        activeAttemptId = NO_ATTEMPT
        activePhase = Phase.RESERVED
        deadlineExpired = false
        true
    }

    /** True when no native operation is unresolved, i.e. a new attempt may be admitted. */
    fun isAdmissible(): Boolean = synchronized(lock) { activeAttemptId == NO_ATTEMPT }

    /** Id of the unresolved attempt, or [NO_ATTEMPT]. */
    fun activeAttemptId(): Long = synchronized(lock) { activeAttemptId }

    /** Phase of the unresolved attempt, or `null` when the registry is idle. */
    fun activePhase(): Phase? = synchronized(lock) {
        if (activeAttemptId == NO_ATTEMPT) null else activePhase
    }

    /**
     * True when a reporting deadline expired and the attempt is still unresolved.
     *
     * This is the "blocked native operation" condition an operator or UI needs to be
     * able to distinguish from "still working".
     */
    fun isBlocked(): Boolean = synchronized(lock) {
        activeAttemptId != NO_ATTEMPT && deadlineExpired
    }

    /** True while [lease] still owns the slot. */
    fun isHeldBy(lease: Lease?): Boolean = synchronized(lock) {
        lease != null && lease.attemptId == activeAttemptId
    }

    /** Human-readable summary for logs and refusal messages. */
    fun describe(): String = synchronized(lock) {
        if (activeAttemptId == NO_ATTEMPT) {
            "idle"
        } else {
            val suffix = if (deadlineExpired) ", reporting deadline expired (still blocked)" else ""
            "attempt=$activeAttemptId phase=$activePhase$suffix"
        }
    }

    /**
     * Apply [change] only while [lease] is still the active attempt.
     *
     * Every phase/deadline mutation funnels through here, so a late completion from an
     * obsolete attempt is silently dropped rather than corrupting current state.
     */
    private inline fun mutate(lease: Lease?, change: (NativeLifetimeRegistry) -> Unit) {
        synchronized(lock) {
            val attemptId = lease?.attemptId ?: return@synchronized
            if (attemptId != activeAttemptId) return@synchronized
            change(this)
        }
    }

    companion object {
        /** Sentinel for "no attempt owns the native slot". */
        const val NO_ATTEMPT = 0L

        /**
         * The process-wide instance used by production code.
         *
         * A single shared instance is the point: ownership of native resources has to
         * be visible across every component that can start one. Tests construct their
         * own instance instead so they never depend on — or disturb — global state.
         */
        val process: NativeLifetimeRegistry = NativeLifetimeRegistry()
    }
}