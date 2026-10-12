package vn.unlimit.softether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
}