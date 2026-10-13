package vn.unlimit.softether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stage 2F-F.28W — behavioural tests for process-wide native-operation ownership.
 *
 * ## What these tests exercise
 *
 * [NativeLifetimeRegistry] in isolation. The registry is the production admission
 * mechanism that replaces the purely logical `resourcesLive` read in
 * `SoftEtherVpnService.startVpn`, so these tests drive the real protocol rather than a
 * re-implementation of it.
 *
 * The property under test is the one the old gate could not express: **a replacement
 * attempt is refused while any earlier native operation is unresolved**, and a reporting
 * timeout is not evidence that a blocking JNI call returned.
 *
 * ## What they deliberately do NOT prove
 *
 *  1. That the real call sites honour the registry. Nothing here shows that
 *     `SoftEtherVpnService` claims before constructing its controller, that
 *     `ConnectionController` releases only from `destroyResources()`, or that the two
 *     hold the same lease. Those are source-level facts, not behavioural ones.
 *  2. That `nativeConnectWithHub` / `nativeDestroy` are actually reached and returned.
 *     Both are blocking JNI with no cancellation channel, so a hung call can only be
 *     simulated here, never reproduced.
 *  3. Anything about a physical device. No test in this file is evidence about an
 *     on-device tunnel.
 *
 * Each test builds its own registry instance, so no test depends on — or disturbs —
 * `NativeLifetimeRegistry.process`.
 */
class NativeLifetimeRegistryTest {

    private fun registry(): NativeLifetimeRegistry = NativeLifetimeRegistry()

    /** A non-null lease, failing the test if acquisition was refused. */
    private fun NativeLifetimeRegistry.acquire(): NativeLifetimeRegistry.Lease {
        val lease = tryAcquire()
        assertTrue("expected acquisition to succeed but the registry reported ${describe()}", lease != null)
        return lease!!
    }

    // ----------------------------------------------------------------- admission

    @Test
    fun freshRegistryIsAdmissibleAndReportsNoOwner() {
        val registry = registry()
        assertTrue(registry.isAdmissible())
        assertNull(registry.activePhase())
        assertEquals(NativeLifetimeRegistry.NO_ATTEMPT, registry.activeAttemptId())
        assertEquals("idle", registry.describe())
    }

    @Test
    fun acquisitionClosesAdmissionForEveryLaterAttempt() {
        val registry = registry()
        val lease = registry.acquire()

        assertFalse("a held slot must not be admissible", registry.isAdmissible())
        assertNull("a second attempt must not be granted", registry.tryAcquire())
        assertNull("a third attempt must not be granted either", registry.tryAcquire())
        assertEquals(lease.attemptId, registry.activeAttemptId())
    }

    @Test
    fun confirmedTeardownReopensAdmissionWithANewAttemptId() {
        val registry = registry()
        val first = registry.acquire()

        assertTrue(registry.confirmTeardown(first))
        assertTrue(registry.isAdmissible())

        val second = registry.acquire()
        assertNotEquals("attempt ids must never be reused", first.attemptId, second.attemptId)
        assertTrue(second.attemptId > first.attemptId)
    }

    @Test
    fun releaseIsOneShotSoRepeatedTeardownIsSafe() {
        val registry = registry()
        val lease = registry.acquire()

        assertTrue("the first release must win", registry.confirmTeardown(lease))
        assertFalse("a second release must report no-op", registry.confirmTeardown(lease))
        assertFalse("a third release must still report no-op", registry.confirmTeardown(lease))
        assertTrue(registry.isAdmissible())
    }

    // -------------------------------------------------------------------- phases

    @Test
    fun phasesAdvanceInOrderAndAreReadableWhileHeld() {
        val registry = registry()
        val lease = registry.acquire()
        assertEquals(NativeLifetimeRegistry.Phase.RESERVED, registry.activePhase())

        registry.markNativeEntered(lease)
        assertEquals(NativeLifetimeRegistry.Phase.CONNECTING, registry.activePhase())

        registry.markConnected(lease)
        assertEquals(NativeLifetimeRegistry.Phase.CONNECTED, registry.activePhase())

        registry.markTeardownRequested(lease)
        assertEquals(NativeLifetimeRegistry.Phase.TEARDOWN, registry.activePhase())

        assertTrue(registry.confirmTeardown(lease))
        assertNull("a released registry has no phase", registry.activePhase())
    }

    @Test
    fun reconnectStyleRecommitOfConnectedPhaseKeepsTheSameAttempt() {
        val registry = registry()
        val lease = registry.acquire()

        registry.markNativeEntered(lease)
        registry.markConnected(lease)
        // attemptReconnect() replaces the handle inside the attempt that already owns
        // the slot, so it re-commits CONNECTED without ever re-acquiring.
        registry.markConnected(lease)

        assertEquals(lease.attemptId, registry.activeAttemptId())
        assertEquals(NativeLifetimeRegistry.Phase.CONNECTED, registry.activePhase())
        assertTrue(registry.isHeldBy(lease))
    }

    @Test
    fun ownershipIsReportedForTheHolderAndDeniedForEveryoneElse() {
        val registry = registry()
        val lease = registry.acquire()

        assertTrue(registry.isHeldBy(lease))
        assertFalse("a null lease never holds the slot", registry.isHeldBy(null))

        registry.confirmTeardown(lease)
        assertFalse(registry.isHeldBy(lease))

        // A later attempt holds the slot; the previous lease must not appear to hold it.
        val next = registry.acquire()
        assertTrue(registry.isHeldBy(next))
        assertFalse(registry.isHeldBy(lease))
    }

    // --------------------------------------------------------- timeout semantics

    @Test
    fun expiredDeadlineNeverReleasesOwnership() {
        val registry = registry()
        val lease = registry.acquire()

        registry.markNativeEntered(lease)
        registry.markDeadlineExpired(lease)

        assertFalse(
            "an expired reporting deadline is not evidence that the native call returned, " +
                    "so it must not reopen admission",
            registry.isAdmissible(),
        )
        assertNull("no replacement attempt may be granted after a deadline expiry", registry.tryAcquire())
        assertTrue(registry.isBlocked())
        assertTrue(registry.describe().contains("reporting deadline expired"))

        // Only a confirmed teardown reopens admission.
        assertTrue(registry.confirmTeardown(lease))
        assertTrue(registry.isAdmissible())
        assertFalse(registry.isBlocked())
    }

    @Test
    fun freshAcquisitionIsNotBlocked() {
        val registry = registry()
        assertFalse(registry.isBlocked())
        registry.acquire()
        assertFalse("merely being connected is not a blocked condition", registry.isBlocked())
    }

    @Test
    fun blockedStateClearsWhenANewAttemptStartsAfterRelease() {
        val registry = registry()
        val blocked = registry.acquire()
        registry.markDeadlineExpired(blocked)
        assertTrue(registry.isBlocked())

        registry.confirmTeardown(blocked)
        val next = registry.acquire()

        assertFalse("the stale deadline flag must not follow the next attempt", registry.isBlocked())
        assertEquals(next.attemptId, registry.activeAttemptId())
        assertFalse(registry.describe().contains("expired"))
    }

    // --------------------------------------------------- attempt-scoped isolation

    @Test
    fun aStaleLeaseCannotMutateTheAttemptThatReplacedIt() {
        val registry = registry()
        val stale = registry.acquire()
        registry.confirmTeardown(stale)
        val current = registry.acquire()

        registry.markNativeEntered(stale)
        registry.markConnected(stale)
        registry.markTeardownRequested(stale)
        registry.markDeadlineExpired(stale)

        assertEquals(
            "a late completion from an obsolete attempt must be dropped",
            NativeLifetimeRegistry.Phase.RESERVED,
            registry.activePhase(),
        )
        assertFalse(registry.isBlocked())
        assertTrue("the live attempt must keep its slot", registry.isHeldBy(current))
    }

    @Test
    fun aStaleLeaseCannotReleaseALiveAttempt() {
        val registry = registry()
        val stale = registry.acquire()
        registry.confirmTeardown(stale)
        val current = registry.acquire()

        assertFalse(
            "an obsolete teardown must not free the slot a newer attempt owns",
            registry.confirmTeardown(stale),
        )
        assertFalse(registry.isAdmissible())
        assertTrue(registry.isHeldBy(current))
    }

    @Test
    fun releaseWithoutLeaseClearsOwnershipButOnlyWhenSomethingIsHeld() {
        val registry = registry()
        assertFalse("releasing an idle registry must report no-op", registry.confirmTeardownWithoutLease())

        registry.acquire()
        assertTrue(registry.confirmTeardownWithoutLease())
        assertTrue(registry.isAdmissible())
        assertFalse("a second release must report no-op", registry.confirmTeardownWithoutLease())
    }

    @Test
    fun releaseWithANullLeaseIsAlwaysRefused() {
        val registry = registry()
        val lease = registry.acquire()

        assertFalse(registry.confirmTeardown(null))
        assertTrue("a null lease must never release anyone's ownership", registry.isHeldBy(lease))
        assertFalse(registry.isAdmissible())
    }

    @Test
    fun nullLeaseMutationsAreIgnored() {
        val registry = registry()
        val lease = registry.acquire()

        registry.markNativeEntered(null)
        registry.markConnected(null)
        registry.markTeardownRequested(null)
        registry.markDeadlineExpired(null)

        assertEquals(NativeLifetimeRegistry.Phase.RESERVED, registry.activePhase())
        assertFalse(registry.isBlocked())
        assertTrue(registry.isHeldBy(lease))
    }

    // ----------------------------------------------------------- real concurrency

    @Test
    fun exactlyOneOfManyRacingAttemptsWins() {
        val registry = registry()
        val racers = 16
        val ready = CountDownLatch(racers)
        val go = CountDownLatch(1)
        val winners = AtomicInteger(0)

        val threads = (0 until racers).map { worker ->
            Thread {
                ready.countDown()
                go.await()
                if (registry.tryAcquire() != null) winners.incrementAndGet()
            }.apply { name = "f28w-racer-$worker" }
        }
        threads.forEach { it.start() }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        go.countDown()
        threads.forEach { it.join(5_000) }

        assertEquals(
            "admission must be atomic: only one attempt may claim the native slot",
            1,
            winners.get(),
        )
        assertFalse(registry.isAdmissible())
    }

    @Test
    fun aBlockedNativeOperationKeepsRacingAttemptsOutUntilItReleases() {
        val registry = registry()
        val lease = registry.acquire()

        // Stand-in for a thread stuck in nativeConnectWithHub / nativeDestroy: the
        // operation has entered, its reporting deadline has expired, and it has not
        // returned.
        val nativeCallReturned = CountDownLatch(1)
        val phaseRecorded = CountDownLatch(1)
        val observedPhases = java.util.Collections.synchronizedList(mutableListOf<NativeLifetimeRegistry.Phase?>())

        val stuck = Thread {
            registry.markNativeEntered(lease)
            registry.markConnected(lease)
            registry.markTeardownRequested(lease)
            registry.markDeadlineExpired(lease)
            observedPhases.add(registry.activePhase())
            phaseRecorded.countDown()
            nativeCallReturned.await()
            registry.confirmTeardown(lease)
        }.apply { name = "f28w-blocked-native" }
        stuck.start()
        assertTrue(phaseRecorded.await(5, TimeUnit.SECONDS))

        // Every attempt made while the native call is unresolved must be refused, and
        // the phase the blocked operation left behind must be TEARDOWN.
        repeat(8) {
            assertNull("a replacement attempt must be refused while native work is unresolved", registry.tryAcquire())
            assertTrue(registry.isBlocked())
            assertFalse(registry.isAdmissible())
        }
        assertEquals(NativeLifetimeRegistry.Phase.TEARDOWN, observedPhases.single())

        nativeCallReturned.countDown()
        stuck.join(5_000)

        assertTrue("admission must reopen once the native call is confirmed returned", registry.isAdmissible())
        assertTrue(registry.tryAcquire() != null)
    }

    @Test
    fun concurrentTeardownOfOneAttemptReleasesExactlyOnce() {
        val registry = registry()
        val lease = registry.acquire()
        registry.markTeardownRequested(lease)

        val racers = 8
        val ready = CountDownLatch(racers)
        val go = CountDownLatch(1)
        val releases = AtomicInteger(0)

        val threads = (0 until racers).map { worker ->
            Thread {
                ready.countDown()
                go.await()
                if (registry.confirmTeardown(lease)) releases.incrementAndGet()
            }.apply { name = "f28w-teardown-$worker" }
        }
        threads.forEach { it.start() }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        go.countDown()
        threads.forEach { it.join(5_000) }

        assertEquals(
            "release must be one-shot even when teardown is invoked concurrently",
            1,
            releases.get(),
        )
        assertTrue(registry.isAdmissible())
    }

    @Test
    fun releaseRacingAReplacementAttemptCannotFreeTheNewOwnersSlot() {
        val registry = registry()
        val old = registry.acquire()
        registry.markTeardownRequested(old)

        val releaseDone = CountDownLatch(1)
        val releaser = Thread {
            releaseDone.await()
            registry.confirmTeardown(old)
        }.apply { name = "f28w-late-release" }
        releaser.start()

        // The replacement attempt is granted first; the obsolete release then lands.
        registry.confirmTeardown(old)
        val fresh = registry.acquire()

        releaseDone.countDown()
        releaser.join(5_000)

        assertTrue("the late release must not clear the new owner's claim", registry.isHeldBy(fresh))
        assertFalse(registry.isAdmissible())
    }

    // ------------------------------------------------------------- process scope

    @Test
    fun processInstanceIsSharedAndIdleBetweenTests() {
        // The shared instance is the point of the design — admission has to be visible
        // across every component that can start a connection — so assert the sharing
        // rather than asserting on its mutable state.
        assertTrue(
            NativeLifetimeRegistry.process === NativeLifetimeRegistry.process,
        )
    }

    @Test
    fun describeNamesTheAttemptAndPhaseWhileHeld() {
        val registry = registry()
        val lease = registry.acquire()
        registry.markNativeEntered(lease)

        val text = registry.describe()
        assertTrue("describe() must name the attempt, was: $text", text.contains("attempt=${lease.attemptId}"))
        assertTrue("describe() must name the phase, was: $text", text.contains("phase=CONNECTING"))
    }

    @Test
    fun leaseToStringExposesItsAttempt() {
        val registry = registry()
        val lease = registry.acquire()
        assertTrue(lease.toString().contains("${lease.attemptId}"))
        assertEquals("Lease(attempt=${lease.attemptId})", lease.toString())
    }

    // ----------------------------------------------------------- T5-SLOT soft-bound

    /**
     * The soft-bound releases a slot **only** when it is genuinely stuck: TEARDOWN phase,
     * an expired reporting deadline, and elapsed-since-teardown at or beyond
     * [NativeLifetimeRegistry.NATIVE_SLOT_FORCE_RELEASE_AFTER_MS]. Everything else stays
     * fail-closed. The clock is injected so the bound is exercised without sleeping, and
     * the log sink is injected so these tests never touch `android.util.Log` (which throws
     * under the mockable `android.jar` used by this module's JVM unit tests).
     */
    private class FakeClock(var now: Long)

    private fun softBoundRegistry(
        clock: FakeClock,
        messages: MutableList<String> = mutableListOf(),
    ): NativeLifetimeRegistry =
        NativeLifetimeRegistry(logSink = { messages.add(it) }, nowMs = { clock.now })

    @Test
    fun softBoundLeavesTheSlotHeldWhileElapsedIsBelowTheBound() {
        val clock = FakeClock(1_000_000L)
        val registry = softBoundRegistry(clock)
        val lease = registry.acquire()

        registry.markNativeEntered(lease)
        registry.markTeardownRequested(lease)
        registry.markDeadlineExpired(lease)

        // Just short of the bound.
        clock.now += NativeLifetimeRegistry.NATIVE_SLOT_FORCE_RELEASE_AFTER_MS - 1
        assertNull(
            "the soft-bound must not release a slot that is still within its window",
            registry.tryAcquire(),
        )
        assertFalse(registry.isAdmissible())
        assertEquals(NativeLifetimeRegistry.ReleaseOutcome.NORMAL, registry.lastReleaseOutcome())
        assertTrue(registry.isHeldBy(lease))
    }

    @Test
    fun softBoundForceReleasesASlotStuckInTeardownPastTheBound() {
        val clock = FakeClock(1_000_000L)
        val messages = mutableListOf<String>()
        val registry = softBoundRegistry(clock, messages)
        val stuck = registry.acquire()

        registry.markNativeEntered(stuck)
        registry.markTeardownRequested(stuck)
        registry.markDeadlineExpired(stuck)

        clock.now += NativeLifetimeRegistry.NATIVE_SLOT_FORCE_RELEASE_AFTER_MS
        val fresh = registry.tryAcquire()

        assertNotNull("the soft-bound must admit a new attempt once the holder is stuck", fresh)
        assertNotEquals(stuck.attemptId, fresh!!.attemptId)
        assertEquals(
            NativeLifetimeRegistry.ReleaseOutcome.FORCED_RELEASE_AFTER_TIMEOUT,
            registry.lastReleaseOutcome(),
        )
        assertTrue(
            "the forced release must be logged with its distinct label, was: $messages",
            messages.any {
                it.contains("FORCED_RELEASE_AFTER_TIMEOUT") && it.contains("attempt=${stuck.attemptId}")
            },
        )
    }

    @Test
    fun aForcedReleaseDoesNotLetTheObsoleteLeaseDisturbTheNewOne() {
        val clock = FakeClock(1_000_000L)
        val registry = softBoundRegistry(clock)
        val stuck = registry.acquire()

        registry.markTeardownRequested(stuck)
        registry.markDeadlineExpired(stuck)
        clock.now += NativeLifetimeRegistry.NATIVE_SLOT_FORCE_RELEASE_AFTER_MS
        val fresh = registry.acquire()

        // The old attempt's late teardown must be a no-op against the new lease.
        assertFalse(
            "an obsolete confirmTeardown must not release the slot a forced-released successor owns",
            registry.confirmTeardown(stuck),
        )
        assertTrue(registry.isHeldBy(fresh))
        assertFalse(registry.isAdmissible())
    }

    /**
     * FIX-1 re-analysis of the PART 1 test `softBoundDoesNotFireForAConnectingHolderThatIsNotInTeardown`.
     *
     * PART 1 advanced the clock by 10× the TEARDOWN bound and asserted the slot stayed held,
     * because no CONNECTING bound existed at all. FIX-B added one at 120 s, so the test was
     * re-expressed as "the TEARDOWN bound must not reach a CONNECTING holder" with an elapsed
     * above the TEARDOWN bound (90 s) and below the CONNECTING bound (120 s).
     *
     * FIX-1 lowers the CONNECTING bound to 30 s, which **destroys that window**: no elapsed
     * can now be simultaneously above 90 s and below 30 s. At 100 s a CONNECTING holder IS
     * reclaimed — by the CONNECTING bound. So the old `assertNull` is no longer true and has
     * been replaced by the invariant this test has always existed to protect, which FIX-1
     * does not change: **the TEARDOWN bound must never be the bound that claims a CONNECTING
     * holder.**
     *
     * The holder deliberately carries an expired reporting deadline — the TEARDOWN bound's
     * other precondition — so that only its *phase* stands between it and
     * [NativeLifetimeRegistry.forceReleaseIfTimedOutLocked]. The release it does get must be
     * labelled [NativeLifetimeRegistry.ReleaseOutcome.FORCED_RELEASE_CONNECTING_STUCK], never
     * [NativeLifetimeRegistry.ReleaseOutcome.FORCED_RELEASE_AFTER_TIMEOUT].
     */
    @Test
    fun teardownBoundDoesNotFireForAConnectingHolder() {
        val clock = FakeClock(1_000_000L)
        val messages = mutableListOf<String>()
        val registry = softBoundRegistry(clock, messages)
        val lease = registry.acquire()

        registry.markNativeEntered(lease)
        registry.markDeadlineExpired(lease)
        // No teardown was ever requested: the holder is CONNECTING, not TEARDOWN.

        // Above the TEARDOWN bound (90 s): if that bound wrongly reached a CONNECTING holder,
        // this elapsed is what would expose it.
        clock.now += 100_000L
        val fresh = registry.tryAcquire()

        assertNotNull("the CONNECTING bound reclaims a CONNECTING holder", fresh)
        assertNotEquals(lease.attemptId, fresh!!.attemptId)
        assertEquals(
            "a CONNECTING holder must be claimed by the CONNECTING bound, never the TEARDOWN one",
            NativeLifetimeRegistry.ReleaseOutcome.FORCED_RELEASE_CONNECTING_STUCK,
            registry.lastReleaseOutcome(),
        )
        assertTrue(
            "the TEARDOWN label must never appear for a CONNECTING holder, was: $messages",
            messages.none { it.contains("FORCED_RELEASE_AFTER_TIMEOUT") },
        )
    }

    // --------------------------------------------- CONNECTING soft-bound (FIX-B)

    @Test
    fun connectingBoundLeavesTheSlotHeldWhileElapsedIsBelowTheBound() {
        val clock = FakeClock(1_000_000L)
        val registry = softBoundRegistry(clock)
        val lease = registry.acquire()

        registry.markNativeEntered(lease)
        // Never reaches CONNECTED or TEARDOWN: it is stuck in CONNECTING.

        clock.now += NativeLifetimeRegistry.NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS - 1
        assertNull(
            "the CONNECTING bound must not release a slot that is still within its window",
            registry.tryAcquire(),
        )
        assertFalse(registry.isAdmissible())
        assertEquals(NativeLifetimeRegistry.ReleaseOutcome.NORMAL, registry.lastReleaseOutcome())
        assertTrue(registry.isHeldBy(lease))
    }

    @Test
    fun connectingBoundForceReleasesASlotStuckInConnectingPastTheBound() {
        val clock = FakeClock(1_000_000L)
        val messages = mutableListOf<String>()
        val registry = softBoundRegistry(clock, messages)
        val stuck = registry.acquire()

        registry.markNativeEntered(stuck)
        // No deadline expiry and no teardown request: a pure CONNECTING leak, exactly the
        // device signature (attempt=3 stuck in the TLS handshake, no exit log).

        clock.now += NativeLifetimeRegistry.NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS
        val fresh = registry.tryAcquire()

        assertNotNull("the CONNECTING bound must admit a new attempt once the holder is stuck", fresh)
        assertNotEquals(stuck.attemptId, fresh!!.attemptId)
        assertEquals(
            NativeLifetimeRegistry.ReleaseOutcome.FORCED_RELEASE_CONNECTING_STUCK,
            registry.lastReleaseOutcome(),
        )
        assertTrue(
            "the CONNECTING release must be logged with its DISTINCT label, was: $messages",
            messages.any {
                it.contains("FORCED_RELEASE_CONNECTING_STUCK") &&
                        !it.contains("FORCED_RELEASE_AFTER_TIMEOUT") &&
                        it.contains("attempt=${stuck.attemptId}")
            },
        )
    }

    @Test
    fun connectingBoundDoesNotFireForATeardownHolder() {
        val clock = FakeClock(1_000_000L)
        val messages = mutableListOf<String>()
        val registry = softBoundRegistry(clock, messages)
        val lease = registry.acquire()

        registry.markNativeEntered(lease) // entered CONNECTING first (stamps the CONNECTING time)
        registry.markTeardownRequested(lease) // then moved to TEARDOWN
        registry.markDeadlineExpired(lease)

        // Above BOTH bounds (30 s and 90 s): what must decide is the PHASE, not the elapsed.
        // [tryAcquire] evaluates the TEARDOWN bound first, and its `?:` short-circuits, so the
        // CONNECTING function is never even consulted for a TEARDOWN holder.
        clock.now += 100_000L
        val fresh = registry.tryAcquire()

        // The TEARDOWN bound releases it — and must carry the TEARDOWN label. The CONNECTING
        // bound must not be the one that fired, so the outcome is never the CONNECTING label.
        assertNotNull("the TEARDOWN bound still releases a TEARDOWN holder", fresh)
        assertEquals(
            NativeLifetimeRegistry.ReleaseOutcome.FORCED_RELEASE_AFTER_TIMEOUT,
            registry.lastReleaseOutcome(),
        )
        assertTrue(
            "the CONNECTING bound must not claim a TEARDOWN holder, was: $messages",
            messages.any { it.contains("FORCED_RELEASE_AFTER_TIMEOUT") } &&
                    messages.none { it.contains("FORCED_RELEASE_CONNECTING_STUCK") },
        )
    }

    /**
     * FIX-1 consequence: the CONNECTING bound (30 s) now sits BELOW the TEARDOWN bound
     * (90 s), which creates a window that could not exist before — elapsed above the
     * CONNECTING bound but below the TEARDOWN bound. A TEARDOWN holder inside that window
     * must keep the slot: the CONNECTING bound must not leak into the TEARDOWN phase just
     * because its number is now the smaller one.
     *
     * This is the mirror image of [teardownBoundDoesNotFireForAConnectingHolder]: together
     * they pin "phase decides, not magnitude" from both sides.
     */
    @Test
    fun connectingBoundDoesNotReleaseATeardownHolderBelowTheTeardownBound() {
        val clock = FakeClock(1_000_000L)
        val messages = mutableListOf<String>()
        val registry = softBoundRegistry(clock, messages)
        val lease = registry.acquire()

        registry.markNativeEntered(lease) // stamps the CONNECTING entry time
        registry.markTeardownRequested(lease) // then moves the holder to TEARDOWN
        registry.markDeadlineExpired(lease)

        // Above the CONNECTING bound (30 s), below the TEARDOWN bound (90 s).
        clock.now += 40_000L
        assertNull(
            "a TEARDOWN holder inside its own window must keep the slot, even though it is " +
                    "already well past the CONNECTING bound",
            registry.tryAcquire(),
        )
        assertEquals(NativeLifetimeRegistry.ReleaseOutcome.NORMAL, registry.lastReleaseOutcome())
        assertTrue(
            "no soft-bound may fire for a TEARDOWN holder that is still inside its window, " +
                    "was: $messages",
            messages.isEmpty(),
        )
        assertTrue(registry.isHeldBy(lease))
    }

    /**
     * FIX-1: pin the two soft-bound values themselves.
     *
     * Every other test in this section references the bounds symbolically, so they would
     * pass for any value. This test is what actually holds the design numbers, and it
     * asserts the TEARDOWN bound explicitly because FIX-1 was required to change ONLY the
     * CONNECTING bound.
     */
    @Test
    fun softBoundValuesAreTheDesignedOnes() {
        assertEquals(
            "the TEARDOWN soft-bound must remain 90 s — 3x the native UDP race timeout",
            90_000L,
            NativeLifetimeRegistry.NATIVE_SLOT_FORCE_RELEASE_AFTER_MS,
        )
        assertEquals(
            "FIX-1: the CONNECTING soft-bound is 30 s — the native UDP race timeout itself",
            30_000L,
            NativeLifetimeRegistry.NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS,
        )
        assertTrue(
            "FIX-1 inverts the relationship: a CONNECTING holder is reclaimed sooner than a " +
                    "TEARDOWN holder, which has already been asked to clean up",
            NativeLifetimeRegistry.NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS <
                    NativeLifetimeRegistry.NATIVE_SLOT_FORCE_RELEASE_AFTER_MS,
        )
    }
}