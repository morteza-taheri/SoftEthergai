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
class NativeLifetimeRegistry(
    /**
     * Sink for the registry's own audit lines.
     *
     * Defaults to `android.util.Log` in production. It is a constructor parameter so the
     * JVM unit tests — which run against the mockable `android.jar`, where
     * `android.util.Log` throws "not mocked" — can capture the forced-release line without
     * touching the framework. Only the soft-bound (T5-SLOT) path ever writes to it, so
     * every existing call site keeps its exact prior behaviour.
     */
    private val logSink: (String) -> Unit = { message ->
        android.util.Log.w("NativeLifetimeRegistry", message)
    },
    /**
     * Wall-clock source, injectable so the soft-bound can be exercised deterministically
     * in a unit test without sleeping for [NATIVE_SLOT_FORCE_RELEASE_AFTER_MS].
     */
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

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
     * How a slot reservation ended — the label a release carries.
     *
     * Declared beside [Phase] because it is the *reason a phase ended*, not a phase itself.
     * The distinction exists so a soft-bound release is never read as a genuine
     * [confirmTeardown]: a forced release does **not** assert that the native operation
     * returned.
     */
    enum class ReleaseOutcome {
        /** Released by [confirmTeardown] (or [confirmTeardownWithoutLease]). */
        NORMAL,

        /**
         * Force-released by [tryAcquire] because the holder stayed in [Phase.TEARDOWN] for
         * at least [NATIVE_SLOT_FORCE_RELEASE_AFTER_MS] past its reporting deadline. The
         * native operation may still be running.
         */
        FORCED_RELEASE_AFTER_TIMEOUT,

        /**
         * Force-released by [tryAcquire] because the holder stayed in [Phase.CONNECTING]
         * for at least [NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS] without ever
         * reaching [Phase.CONNECTED] or [Phase.TEARDOWN]. A distinct label from
         * [FORCED_RELEASE_AFTER_TIMEOUT] so a CONNECTING-phase leak is never confused with
         * the TEARDOWN-phase leak: the two bounds are independent and only one can fire for
         * a given release. The native operation may still be running.
         */
        FORCED_RELEASE_CONNECTING_STUCK,
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
     * Wall-clock ms at which teardown was requested for [activeAttemptId], or 0 when no
     * teardown has been requested. Recorded by [markTeardownRequested]; the soft-bound in
     * [tryAcquire] measures "held past deadline" from here.
     */
    private var teardownRequestedAtMs = 0L

    /**
     * Wall-clock ms at which [activeAttemptId] entered [Phase.CONNECTING], or 0 when it
     * never did. Recorded by [markNativeEntered]; the CONNECTING soft-bound in
     * [tryAcquire] measures "stuck in CONNECTING" from here.
     *
     * This is the sibling of [teardownRequestedAtMs] for the CONNECTING-phase leak: a
     * holder that entered the native connect and never returned is stuck in CONNECTING
     * forever, with no teardown request to measure from. It is cleared on every release
     * (grant, both force-releases) so a stale stamp can never be read against a newer
     * attempt.
     */
    private var connectingEnteredAtMs = 0L

    /**
     * Outcome of the most recent soft-bound release.
     *
     * [ReleaseOutcome.NORMAL] until the soft-bound fires; then
     * [ReleaseOutcome.FORCED_RELEASE_AFTER_TIMEOUT]. Deliberately **not** cleared by a
     * later [confirmTeardown] — this change leaves that method untouched — so it reads as
     * a durable "a forced release has occurred" marker for this registry instance (the
     * process-wide instance is long-lived).
     */
    private var lastReleaseOutcome = ReleaseOutcome.NORMAL

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
    fun tryAcquire(): Lease? {
        var forcedReleaseMessage: String? = null
        var granted: Lease? = null
        synchronized(lock) {
            if (activeAttemptId != NO_ATTEMPT) {
                // Fail-closed by default. The soft-bounds are the only exceptions, and they
                // are siblings — exactly one can fire for a given release because they key
                // off mutually exclusive phases:
                //
                //  - a holder stuck in TEARDOWN past NATIVE_SLOT_FORCE_RELEASE_AFTER_MS is
                //    a leaked slot (observed on device — attempt held in TEARDOWN with no
                //    exit log);
                //  - a holder stuck in CONNECTING past
                //    NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS without ever reaching
                //    CONNECTED/TEARDOWN is the sibling leak (observed on device — attempt
                //    held in CONNECTING/TLS-handshake with no exit log).
                //
                // Without a bound the user cannot connect at all until the app is
                // force-stopped. Each function returns null — keeping the slot — for every
                // case other than its own genuine timeout, and the first (TEARDOWN) is
                // evaluated first and left completely unchanged.
                forcedReleaseMessage = forceReleaseIfTimedOutLocked()
                    ?: forceReleaseIfConnectingStuckLocked()
                if (activeAttemptId != NO_ATTEMPT) return@synchronized
            }
            lastAttemptId += 1
            activeAttemptId = lastAttemptId
            activePhase = Phase.RESERVED
            deadlineExpired = false
            teardownRequestedAtMs = 0L
            connectingEnteredAtMs = 0L
            granted = Lease(activeAttemptId)
        }
        // Emitted outside [lock]: the soft-bound path performs no logging and no blocking
        // call while holding the registry lock.
        forcedReleaseMessage?.let(logSink)
        return granted
    }

    /**
     * Record that [lease] is about to enter its first native call.
     *
     * Also stamps the CONNECTING entry time, which the CONNECTING soft-bound in
     * [tryAcquire] measures from. Additive state only: the method's return contract (Unit)
     * and its effect on [Phase] are unchanged.
     */
    fun markNativeEntered(lease: Lease?) = mutate(lease) {
        it.activePhase = Phase.CONNECTING
        it.connectingEnteredAtMs = nowMs()
    }

    /** Record that [lease]'s connection is established and owns a live handle. */
    fun markConnected(lease: Lease?) = mutate(lease) { it.activePhase = Phase.CONNECTED }

    /**
     * Record that teardown was requested for [lease]. Ownership is retained.
     *
     * Also stamps the request time, which the soft-bound in [tryAcquire] measures from.
     * This is additive state only: the method's ownership contract is unchanged.
     */
    fun markTeardownRequested(lease: Lease?) = mutate(lease) {
        it.activePhase = Phase.TEARDOWN
        // v1.0.48 (§1.6.41) FIX-1: stamp only the FIRST teardown request for this
        // attempt.
        //
        // `destroyResources()` is documented as idempotent ("safe even if the controller
        // already tore itself down") and is reached from at least three places —
        // `SoftEtherVpnService.onDestroy`, the cancel path, and `stopVpn` — while Auto
        // Mode calls `adapter.disconnect()` on every NATIVE_BUSY refusal. While this
        // method re-stamped on each of those calls, every one of them restarted the
        // `NATIVE_SLOT_FORCE_RELEASE_AFTER_MS` window, so the bound could never be
        // reached: the retry loop that was trying to recover kept pushing the release
        // further away. That is why the slot read as permanently held on device even
        // though `describe()` reported "reporting deadline expired (still blocked)".
        //
        // Keeping the first stamp makes the bound measure what its KDoc says it
        // measures — "how long has this attempt been in TEARDOWN" — rather than "how
        // long since the most recent teardown request". The fail-closed guarantee is
        // unchanged: before the bound elapses the slot is still refused.
        if (it.teardownRequestedAtMs == 0L) it.teardownRequestedAtMs = nowMs()
    }

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

    /**
     * Outcome of the most recent soft-bound release.
     *
     * [ReleaseOutcome.NORMAL] until the soft-bound fires, then
     * [ReleaseOutcome.FORCED_RELEASE_AFTER_TIMEOUT]. Exposed so an operator, UI, or test
     * can tell a forced release from a genuine confirmed teardown. See [lastReleaseOutcome]
     * for the stickiness note.
     */
    fun lastReleaseOutcome(): ReleaseOutcome = synchronized(lock) { lastReleaseOutcome }

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
     * How long the current holder has been in [Phase.TEARDOWN], or `0` when it is not.
     *
     * v1.0.48 (§1.6.41) FIX-2: read by `SoftEtherVpnService` so a refusal can name the
     * holder's **age**, not just its phase. Purely observational — it mutates no state
     * and takes no decision, so it cannot affect admission.
     */
    fun teardownHeldMs(): Long = synchronized(lock) {
        if (activeAttemptId == NO_ATTEMPT) return@synchronized 0L
        if (activePhase != Phase.TEARDOWN) return@synchronized 0L
        if (teardownRequestedAtMs == 0L) return@synchronized 0L
        nowMs() - teardownRequestedAtMs
    }

    /**
     * Public entry point for the TEARDOWN soft-bound, for callers that are not asking
     * for admission.
     *
     * v1.0.48 (§1.6.41) FIX-2: [tryAcquire] already runs this same check, so a caller
     * that was *just* refused gains no new decision from calling it. The point is to
     * give the service one named place to ask "is the holder stale now?" and to emit the
     * identical audit line the admission path emits, instead of the service inventing a
     * second, differently-shaped release rule.
     *
     * It applies **exactly** the preconditions of [forceReleaseIfTimedOutLocked] — an
     * expired reporting deadline, [Phase.TEARDOWN], a recorded teardown request, and an
     * elapsed time of at least [NATIVE_SLOT_FORCE_RELEASE_AFTER_MS] — and therefore can
     * never release a holder that [tryAcquire] would have kept. The fail-closed
     * guarantee is untouched: this is the *only* release this method can perform, and it
     * happens only after the documented bound.
     *
     * @return true only when this call actually released the slot.
     */
    fun forceReleaseStaleTeardown(): Boolean {
        var message: String? = null
        synchronized(lock) { message = forceReleaseIfTimedOutLocked() }
        message?.let { logSink(it) }
        return message != null
    }

    /**
     * Soft-bound (T5-SLOT). Force-release the active slot **iff** its holder has been stuck
     * in [Phase.TEARDOWN] with an expired reporting deadline for at least
     * [NATIVE_SLOT_FORCE_RELEASE_AFTER_MS].
     *
     * Called only from [tryAcquire], so it can never fire spontaneously — it runs exactly
     * when a new attempt is asking for admission, which is the moment the leak actually
     * harms the user. Every other condition (no deadline expiry, not in TEARDOWN, no
     * recorded teardown request, or elapsed < bound) leaves the slot held, preserving the
     * fail-closed contract. It performs no logging and no blocking call under [lock].
     *
     * @return the audit line to emit, or `null` when the slot was left held.
     */
    private fun forceReleaseIfTimedOutLocked(): String? {
        if (!deadlineExpired) return null
        if (activePhase != Phase.TEARDOWN) return null
        if (teardownRequestedAtMs == 0L) return null
        val heldMs = nowMs() - teardownRequestedAtMs
        if (heldMs < NATIVE_SLOT_FORCE_RELEASE_AFTER_MS) return null

        val oldAttemptId = activeAttemptId
        activeAttemptId = NO_ATTEMPT
        activePhase = Phase.RESERVED
        deadlineExpired = false
        teardownRequestedAtMs = 0L
        connectingEnteredAtMs = 0L
        lastReleaseOutcome = ReleaseOutcome.FORCED_RELEASE_AFTER_TIMEOUT
        return "[REGISTRY] FORCED_RELEASE_AFTER_TIMEOUT: attempt=$oldAttemptId held for " +
                "$heldMs ms past deadline; native operation may still be running. " +
                "Releasing slot to unblock new attempt."
    }

    /**
     * Sibling soft-bound (FIX-B). Force-release the active slot **iff** its holder has been
     * stuck in [Phase.CONNECTING] for at least
     * [NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS] without ever reaching
     * [Phase.CONNECTED] or [Phase.TEARDOWN].
     *
     * This is the CONNECTING-phase counterpart of [forceReleaseIfTimedOutLocked] and is
     * deliberately **independent** of it: it does **not** consult [deadlineExpired] (a
     * CONNECTING holder has no teardown deadline to expire — it simply never returned) and
     * it only ever fires while `activePhase == Phase.CONNECTING`, so a TEARDOWN holder can
     * never be picked up here. Conversely [forceReleaseIfTimedOutLocked] only fires while
     * `activePhase == Phase.TEARDOWN`, so a CONNECTING holder can never be picked up there.
     * Exactly one of the two can therefore match a given holder.
     *
     * Called only from [tryAcquire], so it can never fire spontaneously — it runs exactly
     * when a new attempt is asking for admission. It performs no logging and no blocking
     * call under [lock].
     *
     * @return the audit line to emit, or `null` when the slot was left held.
     */
    private fun forceReleaseIfConnectingStuckLocked(): String? {
        if (activePhase != Phase.CONNECTING) return null
        if (connectingEnteredAtMs == 0L) return null
        val heldMs = nowMs() - connectingEnteredAtMs
        if (heldMs < NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS) return null

        val oldAttemptId = activeAttemptId
        activeAttemptId = NO_ATTEMPT
        activePhase = Phase.RESERVED
        deadlineExpired = false
        teardownRequestedAtMs = 0L
        connectingEnteredAtMs = 0L
        lastReleaseOutcome = ReleaseOutcome.FORCED_RELEASE_CONNECTING_STUCK
        return "[REGISTRY] FORCED_RELEASE_CONNECTING_STUCK: attempt=$oldAttemptId held in " +
                "CONNECTING for $heldMs ms; native operation may still be running. " +
                "Releasing slot to unblock new attempt."
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
         * T5-SLOT soft-bound: force-release a slot held in [Phase.TEARDOWN] this long past
         * its reporting deadline.
         *
         * 90 s gives 3× headroom over the native UDP race timeout (30 s) — the path that
         * produced the observed ~15 s stall — while still recovering the user within
         * ~1.5 minutes instead of requiring an app force-stop.
         */
        const val NATIVE_SLOT_FORCE_RELEASE_AFTER_MS = 90_000L

        /**
         * FIX-B/FIX-1 soft-bound: force-release a slot held in [Phase.CONNECTING] this long
         * without ever reaching CONNECTED or TEARDOWN.
         *
         * ## Why 30 s (lowered from 120 s by FIX-1)
         *
         * 30 s is the native UDP parallel-race timeout itself (`conn->timeout_ms = 30000`,
         * `softether_protocol.c`), and 6× the observed healthy connect window (<5 s). A
         * genuinely wedged CONNECTING holder (observed on device — attempt=3 stuck in the
         * TLS handshake for >8 min, never reaching `SSL handshake successful`) has, by the
         * time this bound is reached, already blown its own hard budget six times over.
         *
         * The bound is only ever evaluated inside [tryAcquire], i.e. at the moment a NEW
         * attempt asks for admission. Lowering it therefore does not change what happens
         * while the stuck attempt is running; it changes how long the *user* has to wait
         * before a replacement attempt can be admitted. The device evidence that motivated
         * the reduction: Auto Mode gives up on a wedged server roughly 19 s after the wedge
         * (see [AutoModeController]'s NATIVE_BUSY budget), so a 120 s bound could never be
         * reached inside one run — the user was left waiting two minutes with no way back
         * except an app force-stop.
         *
         * ## Relationship to [NATIVE_SLOT_FORCE_RELEASE_AFTER_MS] (FIX-1 changed it)
         *
         * The CONNECTING bound is now deliberately **smaller** than the TEARDOWN bound
         * (30 s < 90 s), the reverse of the pre-FIX-1 arrangement (120 s > 90 s):
         *
         *  - a CONNECTING holder has a native call that owns its own hard budget and is
         *    expected to return within it, so it is reclaimed as soon as that budget is
         *    demonstrably blown;
         *  - a TEARDOWN holder has *already* been asked to clean up, and its native
         *    `nativeDestroy` blocks on the same `connect_mutex` as the (possibly still
         *    running) connect. It is given the longer bound precisely because asking it to
         *    tear down again would not help — the native side is still serialised.
         *
         * The two bounds still key off **mutually exclusive phases**, so exactly one can
         * match a given holder regardless of which numeric bound is larger; see
         * [tryAcquire] for the evaluation order.
         */
        const val NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS = 30_000L

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