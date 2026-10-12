package vn.unlimit.softether.controller

import java.util.concurrent.TimeUnit

/**
 * Stage 2F-F.28AL-U — the single atomic ownership-transfer point for one attempt's
 * native handle.
 *
 * ## The defect this closes
 *
 * F.28AL-T found two independent families of cleanup paths touching the same
 * `nativeHandle`: the coordinator's cleanup (`runCleanup()` via
 * [NativeHandleOwner.claimHandle]) and the inline teardowns in
 * [ConnectionController] (`attemptReconnect()`'s pre-flow old-handle teardown, its
 * abort paths, and `teardownNativeConnection()` from `disconnect()`/`performConnect()`).
 * Each did its own non-atomic read-then-zero (`val handle = nativeHandle;
 * nativeHandle = 0`), so two paths could both read the same nonzero handle and both
 * invoke `nativeDisconnect`/`nativeDestroy` on it — a double free. The coordinator's
 * docs assumed `nativeHandle` was mutated exclusively through the claim; the inline
 * sites violated that assumption.
 *
 * ## The protocol
 *
 * Every application-managed cleanup path must acquire the handle through
 * [claimHandle], and only the claimant may destroy it:
 *
 * * [claimHandle] reads the handle and zeroes the slot under one lock — a compound
 *   read-then-transfer that cannot interleave with another claim.
 * * While the claimant's destroy is still executing, a competing claim waits,
 *   bounded, for it to settle, then reports the settled truth: nothing-outstanding
 *   after a confirmed free, [NativeHandleClaim.Lost] after an unproven one. If the
 *   destroy does not settle within the bound, the claim fails closed with
 *   [NativeHandleClaim.Lost] — a slot emptied by a claim whose outcome is unknown is
 *   never mistaken for "nothing native remains".
 * * The claimant reports its outcome with [settleDestroyed] (free confirmed) or
 *   [settleLost] (free unproven — terminal, never cleared). A claim that is never
 *   settled leaves the slot reporting [NativeHandleClaim.Lost] forever, which is the
 *   fail-closed direction.
 * * [publish] installs a fresh handle (initial connect or reconnect replacement),
 *   only after any previous claim has been settled by the same flow.
 *
 * ## Scope of the invariant
 *
 * "At most one claim may destroy a given published handle" holds for every
 * application-managed cleanup path that goes through this slot. Reads of the handle
 * for live connection use (the data path, native calls made by the owning connect
 * flow) are outside this protocol and unchanged. Nothing here can stop the native
 * library itself from being invoked with a stale handle by code that bypasses the
 * slot — that is a source-level discipline, pinned by tests at this seam.
 *
 * ## Thread-safety
 *
 * All state is guarded by one private lock. No native call ever runs while it is
 * held: the destroy happens outside the claim, and settlement re-enters the lock
 * only after the native call returned. The bounded settle-wait releases the lock
 * while waiting (`Object.wait`), so it cannot form a lock cycle with anything.
 */
internal class NativeHandleSlot : NativeHandleOwner {

    // A java.lang.Object monitor, not Any: the bounded settle-wait below needs
    // wait()/notifyAll(), which Kotlin's Any does not expose.
    private val lock = java.lang.Object()

    /** The currently published handle, or 0 when none is published. Guarded by [lock]. */
    private var handle: Long = 0L

    /** True from a successful claim until the claimant settles its outcome. Guarded by [lock]. */
    private var destroyInFlight = false

    /**
     * Terminal: a handle was claimed and its destruction was never confirmed.
     * Guarded by [lock]; never cleared, for the same reason the coordinator's
     * `destroyed` flag is terminal — clearing it would let a later claim read an
     * unproven cleanup as an absent one.
     */
    private var lost = false

    /** The currently published handle, or 0. For live-use reads, not cleanup decisions. */
    val current: Long get() = synchronized(lock) { handle }

    /**
     * Publish a freshly created handle (initial connect or reconnect replacement).
     *
     * Must not run while a previous claim on this slot is still in flight — the
     * flow that claimed it is the same flow that publishes the replacement, so
     * settlement strictly precedes publication by construction.
     */
    fun publish(handle: Long) {
        synchronized(lock) { this.handle = handle }
    }

    /**
     * Atomically claim the published handle for destruction — the only way any
     * cleanup path may obtain it.
     *
     * Never returns [NativeHandleClaim.Claimed] for the same handle twice, and never
     * reports [NativeHandleClaim.NothingOutstanding] while another owner's destroy is
     * unresolved (in flight past the bound, or recorded as lost).
     */
    override fun claimHandle(): NativeHandleClaim {
        // A claim arriving while another owner's destroy is still executing waits,
        // bounded, for it to settle, so the reported outcome is the settled truth
        // rather than an artefact of the interleaving. The bound mirrors the
        // coordinator's teardown timeout; a destroy that never settles simply turns
        // the claim into the fail-closed Lost once it expires.
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DESTROY_SETTLE_WAIT_MS)
        synchronized(lock) {
            while (destroyInFlight) {
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0L) break
                TimeUnit.NANOSECONDS.timedWait(lock, remainingNs)
            }
            val claimed = handle
            if (claimed != 0L) {
                handle = 0
                destroyInFlight = true
                return NativeHandleClaim.Claimed(claimed)
            }
            // The slot is empty. That proves "nothing outstanding" only when no
            // destroy is in flight and no loss is on record; otherwise the
            // structure's fate is unknown and the claim fails closed.
            if (destroyInFlight || lost) return NativeHandleClaim.Lost
            return NativeHandleClaim.NothingOutstanding
        }
    }

    /** The claimant reports that its destroy completed and freed the structure. */
    fun settleDestroyed() {
        synchronized(lock) {
            destroyInFlight = false
            lock.notifyAll()
        }
    }

    /** The claimant reports that its destroy was attempted but is unproven. Terminal. */
    fun settleLost() {
        synchronized(lock) {
            destroyInFlight = false
            lost = true
            lock.notifyAll()
        }
    }

    private companion object {
        /**
         * How long a claim waits for a foreign in-flight destroy to settle. Mirrors
         * `ConnectionController.NATIVE_TEARDOWN_TIMEOUT_MS`: long enough to convert
         * the common slow free into an honest settled outcome, short enough that a
         * hung native call cannot stall a teardown path indefinitely.
         */
        const val DESTROY_SETTLE_WAIT_MS = 500L
    }
}