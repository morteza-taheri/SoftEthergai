package vn.unlimit.softether.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.softether.NativeLifetimeRegistry
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stage 2F-F.28AL-U — one native handle, exactly one cleanup owner.
 *
 * ## What these tests exercise
 *
 * The real [NativeHandleSlot] — the atomic ownership-transfer point every
 * application-managed cleanup path in `ConnectionController` now claims through —
 * wired into the real [NativeAttemptCoordinator] on a real [NativeLifetimeRegistry],
 * with the native side replaced by injectable lambdas. The inline paths
 * (`attemptReconnect()`'s teardowns, `teardownNativeConnection()` from
 * `disconnect()`/`performConnect()`) are modelled by [inlineCleanup], which mirrors
 * `ConnectionController.claimAndDestroyNativeHandle()` exactly: claim first, destroy
 * only if the claim was won.
 *
 * Every race below is forced with explicit latches: a destroy gate holds the winner
 * inside its native free so the loser is guaranteed to arrive while the claim is
 * unsettled. A failure therefore means a broken invariant, not an unlucky schedule.
 *
 * ## What is NOT proven
 *
 *  1. **No `ConnectionController`.** It cannot be constructed off-device (it needs a
 *     real `SoftEtherVpnService` and `ParcelFileDescriptor`). That all six inline
 *     teardown sites and the coordinator wiring route through the slot is a
 *     source-level fact; these tests pin the seam they all share.
 *  2. **No native integration.** `nativeDisconnect`/`nativeDestroy` are fakes; that
 *     the real `softether_destroy` frees memory, and that JNI reports a failed free
 *     by throwing at all, require a device.
 *  3. **JVM scheduling, not ART.** Real but not a device model; the latches make the
 *     orderings deterministic.
 */
class NativeHandleSingleOwnerTest {

    // ------------------------------------------------------------------- harness

    /**
     * The native side, shared by the inline path and the coordinator path, so a
     * double destroy shows up as [attempts] > 1 no matter which path issued it.
     */
    private class DestroyProbe {
        /** How many times a destroy was entered, successful or not. */
        val attempts = AtomicInteger(0)

        /** How many times a destroy completed and freed the structure. */
        val completed = AtomicInteger(0)

        val destroyedHandles: MutableList<Long> = Collections.synchronizedList(mutableListOf())

        /** Counted down the instant the first destroy is entered, before any gating. */
        val entered = CountDownLatch(1)

        /** Holds the destroy open, modelling a slow (or hung) native free. */
        @Volatile
        var gate: CountDownLatch? = null

        @Volatile
        var throws: Boolean = false

        fun destroy(handle: Long) {
            attempts.incrementAndGet()
            entered.countDown()
            gate?.await(5, TimeUnit.SECONDS)
            if (throws) throw IllegalStateException("native destroy failed")
            completed.incrementAndGet()
            destroyedHandles.add(handle)
        }
    }

    private val registry = NativeLifetimeRegistry()
    private val unresolved: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val releases = AtomicInteger(0)
    private var registryLease: NativeLifetimeRegistry.Lease? = null
    private val probe = DestroyProbe()

    /**
     * Reserve a slot and build a coordinator bound to it, wired to the REAL slot and
     * to the same claim-settlement protocol `ConnectionController` uses: the
     * destroyNative lambda settles the slot's claim — confirmed on success, lost on
     * a throw that is then rethrown so the coordinator applies its own fail-closed
     * lease policy.
     */
    private fun coordinatorFor(slot: NativeHandleSlot): NativeAttemptCoordinator {
        registryLease = registry.tryAcquire()
        assertNotNull("registry must admit the attempt under test", registryLease)
        return NativeAttemptCoordinator(
            handles = slot,
            releaseLease = {
                if (registry.confirmTeardown(registryLease)) releases.incrementAndGet()
            },
            interruptNative = { },
            destroyNative = { handle ->
                try {
                    probe.destroy(handle)
                } catch (e: Throwable) {
                    slot.settleLost()
                    throw e
                }
                slot.settleDestroyed()
            },
            onUnresolved = { unresolved.add(it) },
        )
    }

    /**
     * The inline-path seam: mirrors `ConnectionController.claimAndDestroyNativeHandle()`
     * — claim the slot, and destroy only if this caller won the claim.
     */
    private fun inlineCleanup(slot: NativeHandleSlot) {
        val claim = slot.claimHandle()
        if (claim is NativeHandleClaim.Claimed) {
            try {
                probe.destroy(claim.handle)
            } catch (e: Throwable) {
                slot.settleLost()
                return
            }
            slot.settleDestroyed()
        }
    }

    /** Runs [body] on a thread and fails the test if it does not finish in time. */
    private fun thread(name: String, body: () -> Unit): Thread =
        Thread(body).apply { this.name = name }

    private fun joinOrFail(thread: Thread, what: String) {
        thread.join(THREAD_TIMEOUT_MS)
        assertFalse("$what did not finish within ${THREAD_TIMEOUT_MS}ms", thread.isAlive)
    }

    // ------------------------------------------------- Test A: competing claims

    /**
     * Test A1 — an inline teardown and the coordinator's cleanup compete for the
     * same nonzero handle, and the winner's destroy completes.
     *
     * The inline path wins the claim first and blocks inside a slow (gated) free;
     * the coordinator's `requestTeardown` (no flow registered — the exact
     * `attemptReconnect()` pre-flow window F.28AL-T identified) then claims through
     * the same slot. The loser must wait for the settled truth rather than reading
     * the zeroed slot as "nothing native remains", and must not destroy again.
     */
    @Test
    fun a1_competingClaims_inlineWinner_destroysOnceAndCoordinatorObservesSettledTruth() {
        val slot = NativeHandleSlot()
        slot.publish(HANDLE)
        val c = coordinatorFor(slot)

        probe.gate = CountDownLatch(1)
        val inline = thread("f28alu-a1-inline") { inlineCleanup(slot) }
        inline.start()
        assertTrue(
            "the inline destroy must be in flight before the second path runs",
            probe.entered.await(5, TimeUnit.SECONDS),
        )

        // Second path: the coordinator's real cleanup. With no flow registered it
        // claims through the slot immediately — and must find the claim unsettled.
        val outcomes = Collections.synchronizedList(mutableListOf<NativeAttemptCoordinator.TeardownOutcome>())
        val teardown = thread("f28alu-a1-teardown") {
            outcomes.add(c.requestTeardown(TEARDOWN_TIMEOUT_MS))
        }
        teardown.start()

        // While the winner's destroy is still in flight, nothing may be released,
        // however long the loser has been waiting on the slot.
        assertEquals("the lease must not be released before cleanup is confirmed", 0, releases.get())
        assertEquals("only the claimant may destroy — no second attempt while in flight", 1, probe.attempts.get())

        probe.gate!!.countDown()
        joinOrFail(inline, "inline cleanup")
        joinOrFail(teardown, "coordinator teardown")

        assertEquals(
            "the loser must observe the settled outcome: nothing outstanding after a confirmed free",
            listOf(NativeAttemptCoordinator.TeardownOutcome.CONFIRMED),
            outcomes.toList(),
        )
        assertTrue(c.awaitCleanupConfirmed(5_000))
        assertEquals("the structure must be destroyed exactly once", 1, probe.attempts.get())
        assertEquals("and freed exactly once", 1, probe.completed.get())
        assertEquals(listOf(HANDLE), probe.destroyedHandles.toList())
        assertEquals("the lease is released exactly once, and only after confirmation", 1, releases.get())
        assertTrue("the registry must be admissible again", registry.isAdmissible())
    }

    /**
     * Test A2 — the same competition, but the winner's destroy never settles within
     * the slot's bound (a hung native free).
     *
     * The losing claim must fail closed: unresolved, no second destroy attempt, no
     * lease release — a zeroed slot whose previous owner's cleanup is still
     * outstanding is never mistaken for proof that nothing native remains.
     */
    @Test
    fun a2_competingClaims_hungInlineDestroy_loserFailsClosedWithoutSecondDestroy() {
        val slot = NativeHandleSlot()
        slot.publish(HANDLE)
        val c = coordinatorFor(slot)

        probe.gate = CountDownLatch(1) // never opened: the free hangs
        val inline = thread("f28alu-a2-inline") { inlineCleanup(slot) }
        inline.start()
        assertTrue(probe.entered.await(5, TimeUnit.SECONDS))

        assertEquals(
            "a claim that cannot confirm the previous owner's free must stay unresolved",
            NativeAttemptCoordinator.TeardownOutcome.UNRESOLVED,
            c.requestTeardown(TEARDOWN_TIMEOUT_MS),
        )
        assertEquals("no second destroy may be attempted", 1, probe.attempts.get())
        assertEquals("the lease must be retained while the free is unaccounted for", 0, releases.get())
        assertFalse("a new native attempt must stay refused", registry.isAdmissible())
        assertNull(registry.tryAcquire())
        assertTrue(
            "the unresolved state must be reported, not swallowed: $unresolved",
            unresolved.any { it.contains("claimed for destruction") },
        )

        // Releasing the gate only lets the original owner finish; it must not
        // create a second destroy or a belated release from the loser.
        probe.gate!!.countDown()
        joinOrFail(inline, "inline cleanup whose free hung")
        assertEquals("the original owner completes its own free exactly once", 1, probe.completed.get())
        assertEquals("the loser must never have destroyed", 1, probe.attempts.get())
    }

    // ------------------------------------- Test B: reconnect vs coordinator teardown

    /**
     * Test B1 — the reconnect-style inline teardown completes first; the coordinator
     * cleanup then finds nothing outstanding and confirms through the normal path.
     */
    @Test
    fun b1_inlineTeardownCompletesFirst_coordinatorConfirmsWithoutSecondDestroy() {
        val slot = NativeHandleSlot()
        slot.publish(HANDLE)
        val c = coordinatorFor(slot)

        inlineCleanup(slot)
        assertEquals("the inline path frees the handle exactly once", 1, probe.attempts.get())

        assertEquals(
            NativeAttemptCoordinator.TeardownOutcome.CONFIRMED,
            c.requestTeardown(TEARDOWN_TIMEOUT_MS),
        )
        assertEquals("the coordinator must not destroy the already-freed handle", 1, probe.attempts.get())
        assertEquals(1, releases.get())
        assertTrue(registry.isAdmissible())
    }

    /**
     * Test B2 — the coordinator cleanup owns the free (gated, slow) while the
     * reconnect-style inline path races it. Whichever instant the inline claim
     * lands at, it must never receive the handle a second time.
     */
    @Test
    fun b2_coordinatorOwnsTheFree_inlineRacesAndNeverDestroysAgain() {
        val slot = NativeHandleSlot()
        slot.publish(HANDLE)
        val c = coordinatorFor(slot)

        probe.gate = CountDownLatch(1)
        val teardown = thread("f28alu-b2-teardown") { c.requestTeardown(TEARDOWN_TIMEOUT_MS) }
        teardown.start()
        assertTrue(
            "the coordinator destroy must be in flight before the inline path runs",
            probe.entered.await(5, TimeUnit.SECONDS),
        )

        val inline = thread("f28alu-b2-inline") { inlineCleanup(slot) }
        inline.start()
        // Let the inline claim collide with the in-flight free, then settle it.
        // The inline claim may land before or after this countDown; the invariant
        // (exactly one destroy, ever) must hold at either instant.
        probe.gate!!.countDown()

        joinOrFail(teardown, "coordinator teardown")
        joinOrFail(inline, "inline cleanup")

        assertTrue(c.awaitCleanupConfirmed(5_000))
        assertEquals("the structure must be destroyed exactly once across both paths", 1, probe.attempts.get())
        assertEquals(listOf(HANDLE), probe.destroyedHandles.toList())
        assertEquals("the lease is released exactly once", 1, releases.get())
    }

    /**
     * Test B3 — the reconnect replacement contract: after a confirmed destroy of the
     * old handle, the same slot must accept a fresh handle and hand it out again.
     * This is what lets `attemptReconnect()` replace its handle inside one attempt.
     */
    @Test
    fun b3_slotAcceptsAReplacementHandleAfterAConfirmedDestroy() {
        val slot = NativeHandleSlot()
        slot.publish(HANDLE)

        val first = slot.claimHandle()
        assertTrue(first is NativeHandleClaim.Claimed)
        slot.settleDestroyed()

        val replacement = 0x5678L
        slot.publish(replacement)
        val second = slot.claimHandle()
        assertTrue("a fresh handle must be claimable inside the same attempt", second is NativeHandleClaim.Claimed)
        assertEquals(replacement, (second as NativeHandleClaim.Claimed).handle)
        slot.settleDestroyed()

        assertTrue(
            "after the replacement is destroyed too, nothing is outstanding",
            slot.claimHandle() is NativeHandleClaim.NothingOutstanding,
        )
    }

    // -------------------------------------------------- Test C: cleanup exception

    /**
     * Test C1 — the coordinator's own destroy throws.
     *
     * Teardown must not be reported as confirmed, the lease must stay retained, and
     * a second cleanup path (the inline seam) must be unable to claim and destroy
     * the same handle again.
     */
    @Test
    fun c1_coordinatorDestroyThrows_isUnresolved_retainsLease_andBlocksSecondClaim() {
        val slot = NativeHandleSlot()
        slot.publish(HANDLE)
        val c = coordinatorFor(slot)
        probe.throws = true

        assertEquals(
            "a throwing destroy must never confirm teardown",
            NativeAttemptCoordinator.TeardownOutcome.UNRESOLVED,
            c.requestTeardown(TEARDOWN_TIMEOUT_MS),
        )
        assertEquals("the destroy must be attempted exactly once", 1, probe.attempts.get())
        assertEquals("nothing was freed", 0, probe.completed.get())
        assertEquals("the lease must be retained", 0, releases.get())
        assertFalse("cleanup must not be reported as confirmed", c.awaitCleanupConfirmed(1_000))
        assertFalse("a second native attempt must stay refused", registry.isAdmissible())
        assertNull(registry.tryAcquire())

        // The losing path: the inline seam arrives after the failed cleanup. The
        // slot is terminal-lost, so it can never hand out the handle again.
        inlineCleanup(slot)
        assertEquals("the second path must not attempt another destroy", 1, probe.attempts.get())
        assertEquals(
            "a repeated teardown must still be unresolved, never success",
            NativeAttemptCoordinator.TeardownOutcome.UNRESOLVED,
            c.requestTeardown(TEARDOWN_TIMEOUT_MS),
        )
        assertEquals(0, releases.get())
        assertTrue(
            "the failure must be reported rather than swallowed: $unresolved",
            unresolved.any { it.contains("native destroy") },
        )
    }

    /**
     * Test C2 — the inline path's own destroy throws (the F.28AC lost-handle case
     * under the new claim protocol). The coordinator cleanup arriving afterwards
     * must see the loss, stay unresolved, retain the lease, and never destroy again.
     */
    @Test
    fun c2_inlineDestroyThrows_coordinatorCleanupSeesLostAndNeverDestroysAgain() {
        val slot = NativeHandleSlot()
        slot.publish(HANDLE)
        val c = coordinatorFor(slot)
        probe.throws = true

        inlineCleanup(slot)
        assertEquals("the inline path attempted the destroy exactly once", 1, probe.attempts.get())
        assertEquals("nothing was freed", 0, probe.completed.get())

        assertEquals(
            "a lost handle must never be laundered into a confirmed teardown",
            NativeAttemptCoordinator.TeardownOutcome.UNRESOLVED,
            c.requestTeardown(TEARDOWN_TIMEOUT_MS),
        )
        assertEquals("the second path must not attempt another destroy", 1, probe.attempts.get())
        assertEquals("the lease must be retained", 0, releases.get())
        assertFalse("a second native attempt must stay refused", registry.isAdmissible())
        assertNull(registry.tryAcquire())
        assertTrue(
            "the loss must be reported as unresolved: $unresolved",
            unresolved.any { it.contains("claimed for destruction") },
        )
    }

    // ------------------------------------------------------- slot-level protocol

    /**
     * The compound read-then-transfer itself: two claims for the same published
     * handle can never both receive it, no matter how they interleave. The first
     * claimant deliberately never settles, so the second must fail closed with Lost
     * after the bounded wait — never with the handle, never with
     * NothingOutstanding.
     */
    @Test
    fun slotClaimIsAtomic_onlyOneClaimantEverReceivesTheHandle() {
        val slot = NativeHandleSlot()
        slot.publish(HANDLE)

        val first = slot.claimHandle()
        assertTrue("the first claim must receive the handle", first is NativeHandleClaim.Claimed)
        assertEquals(HANDLE, (first as NativeHandleClaim.Claimed).handle)

        // The first claimant never settles: its destroy is, from here on, unresolved.
        val second = slot.claimHandle()
        assertTrue(
            "an unsettled claim must never hand the handle out again",
            second is NativeHandleClaim.Lost,
        )
        val third = slot.claimHandle()
        assertTrue("and the state must stay lost until settlement", third is NativeHandleClaim.Lost)

        slot.settleLost()
        assertTrue("settlement as lost must not restore the handle", slot.claimHandle() is NativeHandleClaim.Lost)
    }

    private companion object {
        const val HANDLE = 0xABCDL
        const val TEARDOWN_TIMEOUT_MS = 5_000L
        const val THREAD_TIMEOUT_MS = 15_000L
    }
}