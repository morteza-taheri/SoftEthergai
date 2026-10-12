package vn.unlimit.softether.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.softether.NativeLifetimeRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stage 2F-F.28Y — lifecycle coordination tests for the F-1/F-2 fixes.
 *
 * ## What these tests exercise
 *
 * The real [NativeAttemptCoordinator], wired to a real [NativeLifetimeRegistry]. These are
 * not registry-only tests: every case below runs the actual coordination protocol that
 * `ConnectionController.destroyResources()`, `performConnect()` and `attemptReconnect()`
 * use, with the native side replaced by injectable lambdas.
 *
 * That substitution is what makes Cases B, C and F testable at all: they depend on a
 * connect call that stays blocked, and there is no way to block a real JNI call from a
 * JVM unit test. See "What is NOT proven" below.
 *
 * ## What is NOT proven
 *
 *  1. **No native integration.** `nativeCreate`, `nativeForceCloseSocket` and `nativeDestroy`
 *     are fakes. Nothing here shows that the real `softether_destroy` frees memory, that
 *     `softether_force_close_socket` actually unblocks a TLS read, or that the JVM
 *     scheduling here resembles ART's. Those require a device.
 *  2. **No `ConnectionController`.** The controller cannot be constructed off-device (it
 *     needs a real `SoftEtherVpnService` and `ParcelFileDescriptor`). The tests therefore
 *     pin the coordinator's protocol, and the controller's use of it is a source-level
 *     fact: `performConnect` registers a flow before `nativeCreate` and calls
 *     `flow.exit()` in its `finally`; `destroyResources` calls `requestTeardown` once.
 *  3. **Concurrency here is JVM scheduling**, which is real but not a model of a device.
 *     Every race uses an explicit latch for the ordering it depends on, so a failure means
 *     a broken invariant rather than an unlucky schedule.
 *
 * ## Stage 2F-F.28AC — the lost-handle cases
 *
 * F.28AB's audit found that `handle == 0` was doing two incompatible jobs: "no native
 * resource was ever created" and "a handle was taken and its destroy failed". Because
 * `ConnectionController` clears its handle field *before* calling `nativeDestroy`, a thrown
 * and logged destroy left the same `0` behind, and the coordinator released the lease on the
 * strength of it.
 *
 * `f28acA`..`f28acD` pin the replacement: [NativeHandleClaim] makes the distinction explicit,
 * and these tests hold each of its three states to its own outcome — `Lost` never releases,
 * `Claimed` never releases when the destroy throws, and `NothingOutstanding` still releases
 * through the ordinary confirmed path.
 *
 * These tests drive the coordinator through [FakeNative], which models the controller's claim
 * and destroy behaviour directly. What they therefore still do **not** prove is that
 * `ConnectionController` calls `markNativeHandleLost` on every one of its six read-then-zero
 * sites, nor that JNI reports a failed free by throwing at all: both remain source-level
 * facts, not execution-level ones. No real `ConnectionController` can be constructed off-device.
 */
class NativeAttemptCoordinatorTest {

    // ------------------------------------------------------------------- harness

    /**
     * A handle source plus the record of what the native side was asked to do.
     *
     * [destroyCount] and [destroyedHandles] are what make the F-1/F-2 properties
     * observable: the invariant under test is fundamentally "the structure is freed at most
     * once, and never while a flow is inside it".
     */
    private class FakeNative(initialHandle: Long) : NativeHandleOwner {
        @Volatile
        private var handle: Long = initialHandle

        val destroyCount = AtomicInteger(0)
        val interruptCount = AtomicInteger(0)
        val destroyedHandles = java.util.Collections.synchronizedList(mutableListOf<Long>())

        /**
         * Stage 2F-F.28AC: how many times a destroy was *attempted*, as opposed to
         * [destroyCount], which only counts frees that actually completed. A failed destroy
         * throws before the free is recorded, so "attempted once, freed zero times" is the
         * only way to assert that no second destroy was tried after the first one failed.
         */
        val destroyAttempts = AtomicInteger(0)

        /** How many times the handle was claimed. Must never exceed one per attempt. */
        val claims = AtomicInteger(0)

        @Volatile
        var destroyThrows: Boolean = false

        /**
         * Stage 2F-F.28AC: model the exact transition that caused F-1 — the handle is
         * cleared and only *then* does the destroy fail, so nothing native was freed but
         * `0` is all a later reader can see.
         */
        @Volatile
        var handleDiscardedWithoutDestroy: Boolean = false

        /** Blocks inside `claimHandle`-then-destroy to model a slow free. */
        @Volatile
        var destroyGate: CountDownLatch? = null

        /** Set from inside the destroy lambda, to assert a flow is not executing. */
        @Volatile
        var flowExecutingDuringDestroy: Boolean = false

        override fun claimHandle(): NativeHandleClaim {
            claims.incrementAndGet()
            val taken = handle
            handle = 0
            // The F-1 distinction, made explicit: `0` means "nothing outstanding" only when
            // no handle was ever taken and abandoned.
            return when {
                taken != 0L -> NativeHandleClaim.Claimed(taken)
                handleDiscardedWithoutDestroy -> NativeHandleClaim.Lost
                else -> NativeHandleClaim.NothingOutstanding
            }
        }

        fun publish(h: Long) {
            handle = h
        }

        /** Take the handle out of reach without freeing it, as a throwing destroy would. */
        fun discardHandleWithoutDestroy() {
            handle = 0
            handleDiscardedWithoutDestroy = true
        }

        fun interrupt(h: Long) {
            interruptCount.incrementAndGet()
        }

        fun destroy(h: Long) {
            destroyAttempts.incrementAndGet()
            destroyGate?.await(5, TimeUnit.SECONDS)
            if (destroyThrows) throw IllegalStateException("native destroy failed")
            destroyCount.incrementAndGet()
            destroyedHandles.add(h)
        }

        /** A flow "executing" flag the tests flip around a simulated blocking connect. */
        val flowExecuting = AtomicBoolean(false)
    }

    private val registry = NativeLifetimeRegistry()
    private val unresolved = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val releases = AtomicInteger(0)
    private var registryLease: NativeLifetimeRegistry.Lease? = null

    /**
     * Reserve a slot and build a coordinator bound to it.
     *
     * Every test starts here so no test depends on — or disturbs — another test's lease.
     * JUnit constructs a fresh instance per test method, so the shared registry is reset
     * for free.
     */
    private fun coordinatorFor(native: FakeNative): NativeAttemptCoordinator {
        registryLease = registry.tryAcquire()
        assertNotNull("registry must admit the attempt under test", registryLease)
        return NativeAttemptCoordinator(
            handles = native,
            releaseLease = {
                if (registry.confirmTeardown(registryLease)) releases.incrementAndGet()
            },
            interruptNative = { native.interrupt(it) },
            destroyNative = {
                // Snapshot inside the destroy itself: the property under test is that the
                // free happens with no flow executing, not what the timeout report saw.
                native.flowExecutingDuringDestroy = native.flowExecuting.get()
                native.destroy(it)
            },
            onUnresolved = { unresolved.add(it) },
        )
    }

    /** Runs [body] on a thread and fails the test if it does not finish in time. */
    private fun thread(name: String, body: () -> Unit): Thread =
        Thread(body).apply { this.name = name }

    private fun joinOrFail(thread: Thread, what: String) {
        thread.join(THREAD_TIMEOUT_MS)
        assertFalse("$what did not finish within ${THREAD_TIMEOUT_MS}ms", thread.isAlive)
    }

    // ------------------------------------------------- Case A: connect then teardown

    @Test
    fun caseA_connectFinishesBeforeTeardown_destroysOnceAndReleasesOnce() {
        val native = FakeNative(initialHandle = 0L)
        val c = coordinatorFor(native)

        val flow = requireNotNull(c.beginFlow()) { "flow registration must be admitted before teardown" }
        native.publish(HANDLE)
        flow.publishHandle(HANDLE)

        // The connect completes normally, before any teardown.
        flow.exit()

        assertFalse("a live connection must not be destroyed by its own flow exit", c.isDestroyed())
        assertEquals(0, native.destroyCount.get())
        assertEquals("the lease must be held while connected", 0, releases.get())
        assertFalse(registry.isAdmissible())

        // Now teardown arrives with no flow running.
        assertEquals(
            NativeAttemptCoordinator.TeardownOutcome.CONFIRMED,
            c.requestTeardown(TEARDOWN_TIMEOUT_MS),
        )

        assertEquals("the structure must be freed exactly once", 1, native.destroyCount.get())
        assertEquals(listOf(HANDLE), native.destroyedHandles.toList())
        assertEquals("the lease must be released exactly once", 1, releases.get())
        assertTrue(registry.isAdmissible())
        assertTrue(c.awaitCleanupConfirmed(1_000))
    }

    @Test
    fun caseA_flowThatNeverConnected_stillReleasesTheReservation() {
        val native = FakeNative(initialHandle = 0L)
        val c = coordinatorFor(native)

        // A controller that reserved a slot and was destroyed before entering native code:
        // no flow was ever registered.
        assertEquals(
            NativeAttemptCoordinator.TeardownOutcome.CONFIRMED,
            c.requestTeardown(TEARDOWN_TIMEOUT_MS),
        )
        assertEquals(0, native.destroyCount.get())
        assertEquals("a reservation with no native work must still be released", 1, releases.get())
    }

    // ------------------------------------------ Case B: teardown while connect blocked

    @Test
    fun caseB_teardownWhileConnectBlocked_neverDestroysAndNeverReleases() {
        val native = FakeNative(initialHandle = 0L)
        val c = coordinatorFor(native)

        val flow = requireNotNull(c.beginFlow()) { "flow registration must be admitted before teardown" }
        native.publish(HANDLE)
        flow.publishHandle(HANDLE)

        val connectEntered = CountDownLatch(1)
        val allowConnectToReturn = CountDownLatch(1)
        native.flowExecuting.set(true)

        val connect = thread("f28y-connect") {
            connectEntered.countDown()
            allowConnectToReturn.await(5, TimeUnit.SECONDS)
            native.flowExecuting.set(false)
            flow.exit()
        }
        connect.start()
        assertTrue(connectEntered.await(5, TimeUnit.SECONDS))

        // Teardown runs while the "connect" is still inside the native call.
        val outcome = c.requestTeardown(SHORT_TIMEOUT_MS)

        assertEquals(
            "a blocked connect must leave teardown unresolved",
            NativeAttemptCoordinator.TeardownOutcome.UNRESOLVED,
            outcome,
        )
        assertEquals(
            "the structure must NOT be freed while the connect may still be using it",
            0,
            native.destroyCount.get(),
        )
        assertEquals("the lease must be retained", 0, releases.get())
        assertFalse("a new attempt must still be refused", registry.isAdmissible())
        assertNull("no new lease may be granted", registry.tryAcquire())

        // The interrupt is the sanctioned unblock mechanism, and it must have been used.
        assertEquals("the blocked connect must have been interrupted", 1, native.interruptCount.get())

        allowConnectToReturn.countDown()
        joinOrFail(connect, "connect flow")

        // Case B's second half: the exiting flow completes cleanup and releases, once.
        assertTrue("cleanup must be confirmed once the flow exits", c.awaitCleanupConfirmed(5_000))
        assertEquals(1, native.destroyCount.get())
        assertEquals(1, releases.get())
        assertTrue("a new attempt must be admitted after genuine completion", registry.isAdmissible())
    }

    @Test
    fun caseB_cleanupByTheExitingFlow_happensWhileNoFlowIsExecuting() {
        val native = FakeNative(initialHandle = 0L)
        val c = coordinatorFor(native)
        val flow = requireNotNull(c.beginFlow()) { "flow registration must be admitted before teardown" }
        native.publish(HANDLE)
        flow.publishHandle(HANDLE)

        val allowReturn = CountDownLatch(1)
        native.flowExecuting.set(true)
        val connect = thread("f28y-connect") {
            allowReturn.await(5, TimeUnit.SECONDS)
            native.flowExecuting.set(false)
            flow.exit()
        }
        connect.start()

        assertEquals(NativeAttemptCoordinator.TeardownOutcome.UNRESOLVED, c.requestTeardown(SHORT_TIMEOUT_MS))
        allowReturn.countDown()
        joinOrFail(connect, "connect flow")
        assertTrue(c.awaitCleanupConfirmed(5_000))

        assertFalse(
            "destruction must be observed with no flow executing",
            native.flowExecutingDuringDestroy,
        )
    }

    // ------------------------------------------------ Case C/D: late return, zero handle

    @Test
    fun caseC_connectReturnsAfterCancellation_publishesNothingAndCleansUpOnce() {
        val native = FakeNative(initialHandle = 0L)
        val c = coordinatorFor(native)
        val flow = requireNotNull(c.beginFlow()) { "flow registration must be admitted before teardown" }
        native.publish(HANDLE)
        flow.publishHandle(HANDLE)

        val allowReturn = CountDownLatch(1)
        val connect = thread("f28y-connect-late") {
            allowReturn.await(5, TimeUnit.SECONDS)
            // The flow publishes nothing to the outside world: `markConnected` and the
            // state callbacks are attempt-scoped and guarded by the terminal flag, and the
            // registry's own phase mutation is a no-op once ownership has moved on. What
            // this test pins is that the *structure* is still freed, exactly once.
            flow.exit()
        }
        connect.start()

        assertEquals(NativeAttemptCoordinator.TeardownOutcome.UNRESOLVED, c.requestTeardown(SHORT_TIMEOUT_MS))
        allowReturn.countDown()
        joinOrFail(connect, "late connect flow")

        assertTrue(c.awaitCleanupConfirmed(5_000))
        assertEquals("the late handle must be cleaned up exactly once", 1, native.destroyCount.get())
        assertEquals(listOf(HANDLE), native.destroyedHandles.toList())
        assertEquals(1, releases.get())
    }

    @Test
    fun caseD_handleIsZeroWhileFlowInFlight_teardownStillWaitsForTheFlow() {
        val native = FakeNative(initialHandle = 0L)
        val c = coordinatorFor(native)

        // The flow has been registered but `nativeCreate` has not returned, so there is no
        // handle to publish. This is the exact state the pre-fix code mistook for
        // "no native operation running".
        val flow = requireNotNull(c.beginFlow()) { "flow registration must be admitted before teardown" }
        assertEquals(0L, flow.currentHandle)

        val allowReturn = CountDownLatch(1)
        val connect = thread("f28y-connect-zero-handle") {
            allowReturn.await(5, TimeUnit.SECONDS)
            native.publish(HANDLE)
            flow.exit()
        }
        connect.start()

        val outcome = c.requestTeardown(SHORT_TIMEOUT_MS)
        assertEquals(
            "a zero handle must not let teardown skip synchronisation",
            NativeAttemptCoordinator.TeardownOutcome.UNRESOLVED,
            outcome,
        )
        assertEquals(0, native.destroyCount.get())
        assertEquals(0, releases.get())
        assertEquals("there is nothing to interrupt yet", 0, native.interruptCount.get())

        allowReturn.countDown()
        joinOrFail(connect, "zero-handle connect flow")

        assertTrue(c.awaitCleanupConfirmed(5_000))
        assertEquals(1, native.destroyCount.get())
        assertEquals(1, releases.get())
    }

    // -------------------------------------------- Case E: repeated / concurrent teardown

    @Test
    fun caseE_repeatedTeardown_destroysAndReleasesExactlyOnce() {
        val native = FakeNative(initialHandle = HANDLE)
        val c = coordinatorFor(native)

        assertEquals(NativeAttemptCoordinator.TeardownOutcome.CONFIRMED, c.requestTeardown(TEARDOWN_TIMEOUT_MS))
        repeat(4) { attempt ->
            val outcome = c.requestTeardown(TEARDOWN_TIMEOUT_MS)
            assertTrue(
                "repeat $attempt must be reported as already confirmed, was $outcome",
                outcome == NativeAttemptCoordinator.TeardownOutcome.CONFIRMED ||
                        outcome == NativeAttemptCoordinator.TeardownOutcome.ALREADY_CONFIRMED,
            )
        }

        assertEquals("repeated teardown must not destroy twice", 1, native.destroyCount.get())
        assertEquals("repeated teardown must not release twice", 1, releases.get())
    }

    @Test
    fun caseE_concurrentTeardownCallers_electExactlyOneOwner() {
        val native = FakeNative(initialHandle = HANDLE)
        val c = coordinatorFor(native)
        // Widen the race: the first owner is slow inside destroy, so the other callers
        // genuinely contend for the claim and then wait on its result.
        native.destroyGate = CountDownLatch(1)

        val callers = 6
        val ready = CountDownLatch(callers)
        val go = CountDownLatch(1)
        val outcomes = java.util.Collections.synchronizedList(
            mutableListOf<NativeAttemptCoordinator.TeardownOutcome>(),
        )
        val threads = (0 until callers).map { i ->
            thread("f28y-teardown-$i") {
                ready.countDown()
                go.await()
                outcomes.add(c.requestTeardown(TEARDOWN_TIMEOUT_MS))
            }
        }
        threads.forEach { it.start() }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        go.countDown()

        // Let the destroy finish so the losers waiting on cleanupDone can complete.
        Thread {
            Thread.sleep(200)
            native.destroyGate?.countDown()
        }.apply { name = "f28y-open-destroy-gate" }.start()

        threads.forEach { joinOrFail(it, "teardown caller") }

        assertEquals("only one caller may free the structure", 1, native.destroyCount.get())
        assertEquals("only one caller may release the lease", 1, releases.get())
        assertEquals(callers, outcomes.size)
        assertTrue(
            "no caller may observe UNRESOLVED when cleanup did complete",
            outcomes.none { it == NativeAttemptCoordinator.TeardownOutcome.UNRESOLVED },
        )
    }

    @Test
    fun caseE_teardownRacingTheExitingFlow_destroysExactlyOnce() {
        val native = FakeNative(initialHandle = HANDLE)
        val c = coordinatorFor(native)
        val flow = requireNotNull(c.beginFlow()) { "flow registration must be admitted before teardown" }
        native.publish(HANDLE)
        flow.publishHandle(HANDLE)

        val allowReturn = CountDownLatch(1)
        val connect = thread("f28y-connect-vs-teardown") {
            allowReturn.await(5, TimeUnit.SECONDS)
            flow.exit()
        }
        connect.start()

        val teardown = thread("f28y-teardown-vs-flow") { c.requestTeardown(TEARDOWN_TIMEOUT_MS) }
        teardown.start()

        allowReturn.countDown()
        joinOrFail(connect, "connect flow")
        joinOrFail(teardown, "teardown caller")

        assertTrue(c.awaitCleanupConfirmed(5_000))
        assertEquals("the structure must be freed exactly once", 1, native.destroyCount.get())
        assertEquals(1, releases.get())
    }

    // ------------------------------------------------- Case F: cleanup fails / hangs

    @Test
    fun caseF_destroyThrows_doesNotReleaseOwnership() {
        val native = FakeNative(initialHandle = HANDLE)
        native.destroyThrows = true
        val c = coordinatorFor(native)

        assertEquals(
            "a failed destroy must not report teardown complete",
            NativeAttemptCoordinator.TeardownOutcome.UNRESOLVED,
            c.requestTeardown(TEARDOWN_TIMEOUT_MS),
        )

        assertEquals("ownership must be retained after a failed destroy", 0, releases.get())
        assertFalse("a new attempt must stay refused", registry.isAdmissible())
        assertNull(registry.tryAcquire())
        assertFalse("cleanup must not be reported as confirmed", c.awaitCleanupConfirmed(1_000))
        assertTrue(
            "the failure must leave diagnostic evidence",
            unresolved.any { it.contains("native destroy") },
        )
    }

    @Test
    fun caseF_connectNeverReturns_ownershipIsRetainedIndefinitely() {
        val native = FakeNative(initialHandle = HANDLE)
        val c = coordinatorFor(native)
        val flow = requireNotNull(c.beginFlow()) { "flow registration must be admitted before teardown" }
        native.publish(HANDLE)
        flow.publishHandle(HANDLE)

        assertEquals(
            NativeAttemptCoordinator.TeardownOutcome.UNRESOLVED,
            c.requestTeardown(SHORT_TIMEOUT_MS),
        )
        // The flow deliberately never exits: ownership must simply stay held.
        assertEquals(0, releases.get())
        assertFalse(registry.isAdmissible())
        assertFalse(c.awaitCleanupConfirmed(200))
        assertEquals(0, native.destroyCount.get())

        // A later attempt is refused, which is the fail-closed requirement.
        assertNull("a busy slot must refuse a new attempt", registry.tryAcquire())
    }

    // ------------------------------------ Stage 2F-F.28AC: a lost handle is not "nothing"

    /**
     * Test A — the handle is claimed, then destruction throws.
     *
     * The case F-1 turned on: a throwing `nativeDestroy` used to leave a `0` behind that the
     * coordinator read as proof nothing native remained, so it released the process-wide
     * lease and reported a confirmed teardown while the structure was still allocated.
     */
    @Test
    fun f28acA_destroyThrowsAfterTheHandleWasClaimed_neverConfirmsAndNeverReleases() {
        val native = FakeNative(initialHandle = HANDLE)
        native.destroyThrows = true
        val c = coordinatorFor(native)
        val flow = requireNotNull(c.beginFlow()) { "flow registration must be admitted before teardown" }
        native.publish(HANDLE)
        flow.publishHandle(HANDLE)

        val allowReturn = CountDownLatch(1)
        native.flowExecuting.set(true)
        val connect = thread("f28ac-connect-throwing-destroy") {
            allowReturn.await(5, TimeUnit.SECONDS)
            native.flowExecuting.set(false)
            flow.exit()
        }
        connect.start()

        // Teardown arrives while the connect is still inside the native call.
        assertEquals(
            "a teardown that cannot wait for the flow must stay unresolved",
            NativeAttemptCoordinator.TeardownOutcome.UNRESOLVED,
            c.requestTeardown(SHORT_TIMEOUT_MS),
        )
        assertEquals(
            "nothing may be freed while a flow may still be using the structure",
            0,
            native.destroyCount.get(),
        )
        assertEquals("the lease must be retained", 0, releases.get())

        // The flow exits and takes cleanup ownership — the only place where "native code has
        // exited" is a fact rather than an assumption. Its destroy then throws.
        allowReturn.countDown()
        joinOrFail(connect, "connect flow whose destroy throws")

        assertFalse(
            "a destroy that threw must never be reported as confirmed cleanup",
            c.awaitCleanupConfirmed(5_000),
        )
        assertEquals("ownership must NOT be released after a failed destroy", 0, releases.get())
        assertFalse("a second native attempt must stay refused", registry.isAdmissible())
        assertNull("the registry must not grant a new lease", registry.tryAcquire())
        assertEquals("the destroy must be attempted exactly once", 1, native.destroyAttempts.get())
        assertEquals("the handle must be claimed exactly once", 1, native.claims.get())
        assertTrue(
            "the failure must be reported rather than swallowed: $unresolved",
            unresolved.any { it.contains("native destroy") && it.contains(HANDLE.toString()) },
        )
    }

    /**
     * Test B — the handle became unavailable without any confirmed destruction.
     *
     * This is F-1 stated directly rather than through a throwing destroy: whatever the cause,
     * the coordinator must be able to tell "a handle is unaccounted for" apart from "no
     * resource was ever created", and only the latter may release the lease.
     */
    @Test
    fun f28acB_handleDiscardedWithoutDestroy_isNotTheSameAsNoResourceEverCreated() {
        val native = FakeNative(initialHandle = HANDLE)
        val c = coordinatorFor(native)
        val flow = requireNotNull(c.beginFlow()) { "flow registration must be admitted before teardown" }
        native.publish(HANDLE)
        flow.publishHandle(HANDLE)

        val allowReturn = CountDownLatch(1)
        val connect = thread("f28ac-connect-lost-handle") {
            allowReturn.await(5, TimeUnit.SECONDS)
            flow.exit()
        }
        connect.start()

        assertEquals(
            NativeAttemptCoordinator.TeardownOutcome.UNRESOLVED,
            c.requestTeardown(SHORT_TIMEOUT_MS),
        )

        // The controller's own teardown path: the handle field is zeroed and only then does
        // the destroy fail, so by the time the coordinator looks, `0` is all that is left.
        native.discardHandleWithoutDestroy()

        allowReturn.countDown()
        joinOrFail(connect, "connect flow whose handle was lost")

        assertFalse(
            "a discarded handle must never be read as a completed cleanup",
            c.awaitCleanupConfirmed(5_000),
        )
        assertEquals(
            "the outcome must stay unresolved, never confirmed",
            NativeAttemptCoordinator.TeardownOutcome.UNRESOLVED,
            c.requestTeardown(TEARDOWN_TIMEOUT_MS),
        )
        assertEquals(
            "a lost handle must not be freed a second time",
            0,
            native.destroyAttempts.get(),
        )
        assertEquals("ownership must be retained", 0, releases.get())
        assertFalse("the registry must stay non-admissible", registry.isAdmissible())
        assertNull("a new native attempt must be rejected", registry.tryAcquire())
        assertTrue(
            "the loss must be reported, not read as 'nothing outstanding': $unresolved",
            unresolved.any { it.contains("claimed for destruction") },
        )
    }

    /**
     * Test C — `nativeCreate` never produced a resource.
     *
     * The control for Test B: an absent handle with no loss on record is the one case that
     * legitimately releases the reservation, and it must reach the release through the normal
     * confirmed path rather than through the exception handling of A or B.
     */
    @Test
    fun f28acC_nativeCreateNeverProducedAResource_isRecognisedAsLegitimate() {
        val native = FakeNative(initialHandle = 0L)
        val c = coordinatorFor(native)
        val flow = requireNotNull(c.beginFlow()) { "flow registration must be admitted before teardown" }
        assertEquals("no handle was ever published", 0L, flow.currentHandle)

        val allowReturn = CountDownLatch(1)
        val connect = thread("f28ac-connect-no-resource") {
            allowReturn.await(5, TimeUnit.SECONDS)
            flow.exit()
        }
        connect.start()

        assertEquals(
            "teardown must still wait for the flow even when there is no resource",
            NativeAttemptCoordinator.TeardownOutcome.UNRESOLVED,
            c.requestTeardown(SHORT_TIMEOUT_MS),
        )

        allowReturn.countDown()
        joinOrFail(connect, "connect flow that created nothing")

        assertTrue(
            "an absent resource must be released through the confirmed path",
            c.awaitCleanupConfirmed(5_000),
        )
        assertEquals(
            "no native call may be made for a resource that never existed",
            0,
            native.destroyAttempts.get(),
        )
        assertEquals("the reservation must be released exactly once", 1, releases.get())
        assertTrue("a new attempt must be admitted again", registry.isAdmissible())
        assertTrue(
            "a legitimate absence must not be reported as a lost handle or a failed destroy: $unresolved",
            unresolved.none {
                it.contains("claimed for destruction") || it.contains("native destroy")
            },
        )
    }

    /**
     * Test D — a stop/exit race in which cleanup fails.
     *
     * Exactly-once ownership is only half the property. The other half is that the *losing*
     * path cannot read the winner's failure as a success, which is how a failed destroy could
     * be laundered into a release from two directions at once.
     */
    @Test
    fun f28acD_stopExitRaceWithAFailingCleanup_isNeverConvertedIntoSuccess() {
        repeat(25) { iteration ->
            val native = FakeNative(initialHandle = 0L)
            native.destroyThrows = true
            // A registry per iteration, because this scenario never releases: a shared one
            // would still be holding the previous iteration's lease, which is the property
            // under test rather than a reason to skip the iteration.
            val iterationRegistry = NativeLifetimeRegistry()
            registryLease = iterationRegistry.tryAcquire()
            assertNotNull("iteration $iteration: the registry must admit the attempt", registryLease)
            releases.set(0)
            val c = NativeAttemptCoordinator(
                handles = native,
                releaseLease = {
                    if (iterationRegistry.confirmTeardown(registryLease)) releases.incrementAndGet()
                },
                interruptNative = { native.interrupt(it) },
                destroyNative = { native.destroy(it) },
                onUnresolved = { unresolved.add(it) },
            )

            val flow = requireNotNull(c.beginFlow()) { "iteration $iteration: flow must register" }
            native.publish(HANDLE)
            flow.publishHandle(HANDLE)

            val outcomes = java.util.Collections.synchronizedList(
                mutableListOf<NativeAttemptCoordinator.TeardownOutcome>(),
            )
            val ready = CountDownLatch(2)
            val go = CountDownLatch(1)

            val exitThread = thread("f28ac-d-exit-$iteration") {
                ready.countDown()
                go.await()
                flow.exit()
                outcomes.add(c.requestTeardown(TEARDOWN_TIMEOUT_MS))
            }
            val teardownThread = thread("f28ac-d-teardown-$iteration") {
                ready.countDown()
                go.await()
                outcomes.add(c.requestTeardown(TEARDOWN_TIMEOUT_MS))
            }
            exitThread.start()
            teardownThread.start()
            assertTrue("iteration $iteration: both threads must be ready", ready.await(5, TimeUnit.SECONDS))
            go.countDown()
            joinOrFail(exitThread, "exit thread at iteration $iteration")
            joinOrFail(teardownThread, "teardown thread at iteration $iteration")

            assertEquals("iteration $iteration: exactly one cleanup owner", 1, native.claims.get())
            assertEquals(
                "iteration $iteration: exactly one destroy attempt",
                1,
                native.destroyAttempts.get(),
            )
            assertEquals(
                "iteration $iteration: a failed destroy frees nothing",
                0,
                native.destroyCount.get(),
            )
            assertEquals("iteration $iteration: no path may release", 0, releases.get())
            assertFalse(
                "iteration $iteration: a failed cleanup must not be reported as confirmed",
                c.awaitCleanupConfirmed(5_000),
            )
            assertTrue(
                "iteration $iteration: neither caller may see success, saw $outcomes",
                outcomes.none {
                    it == NativeAttemptCoordinator.TeardownOutcome.CONFIRMED ||
                            it == NativeAttemptCoordinator.TeardownOutcome.ALREADY_CONFIRMED
                },
            )
            assertFalse(
                "iteration $iteration: the registry must stay held",
                iterationRegistry.isAdmissible(),
            )
            assertNull(
                "iteration $iteration: a new native attempt must stay refused",
                iterationRegistry.tryAcquire(),
            )
        }
    }

    // ------------------------------------------------------- terminal destroyed flag

    @Test
fun noFlowMayBeginAfterTeardownHasStarted() {
        val native = FakeNative(initialHandle = HANDLE)
        val c = coordinatorFor(native)

        assertEquals(
            NativeAttemptCoordinator.TeardownOutcome.CONFIRMED,
            c.requestTeardown(TEARDOWN_TIMEOUT_MS),
        )

        assertTrue("the destroyed flag is terminal", c.isDestroyed())
        assertNull("no flow may start after teardown", c.beginFlow())
    }

    @Test
    fun destroyedFlagIsSetBeforeAnyCleanupAndNeverCleared() {
        val native = FakeNative(initialHandle = HANDLE)
        val c = coordinatorFor(native)

        assertFalse(c.isDestroyed())
        c.requestTeardown(TEARDOWN_TIMEOUT_MS)
        assertTrue(c.isDestroyed())

        // A failed cleanup attempt must not un-terminal the flag.
        repeat(3) { c.requestTeardown(TEARDOWN_TIMEOUT_MS) }
        assertTrue(c.isDestroyed())
    }

    @Test
    fun teardownSnapshotAndFlowRegistrationAreMutuallyExclusive() {
        // The F-2 window: registration must not be able to slip past a teardown that has
        // already decided no flow exists. Both orderings are exercised; each must end with
        // either "no flow registered" or "teardown observed the flow", never "flow
        // registered and teardown unaware of it".
        repeat(50) { iteration ->
            val native = FakeNative(initialHandle = 0L)
            registryLease = registry.tryAcquire() ?: error("registry busy at iteration $iteration")
            releases.set(0)
            val c = NativeAttemptCoordinator(
                handles = native,
                releaseLease = { if (registry.confirmTeardown(registryLease)) releases.incrementAndGet() },
                interruptNative = { native.interrupt(it) },
                destroyNative = { native.destroy(it) },
                onUnresolved = { unresolved.add(it) },
            )

            val registerFirst = iteration % 2 == 0
            var registered: NativeAttemptCoordinator.Flow? = null
            if (registerFirst) registered = c.beginFlow()

            val outcome = c.requestTeardown(SHORT_TIMEOUT_MS)
            if (!registerFirst) registered = c.beginFlow()

            if (registered != null) {
                // The flow exists, so teardown must have refused to claim a free.
                assertEquals(
                    "iteration $iteration: teardown must wait for a registered flow",
                    NativeAttemptCoordinator.TeardownOutcome.UNRESOLVED,
                    outcome,
                )
                assertEquals("iteration $iteration", 0, releases.get())
                registered.exit()
                assertTrue("iteration $iteration", c.awaitCleanupConfirmed(5_000))
            } else {
                assertEquals(
                    "iteration $iteration: with no flow, teardown completes",
                    NativeAttemptCoordinator.TeardownOutcome.CONFIRMED,
                    outcome,
                )
            }
            assertEquals("iteration $iteration: exactly one release", 1, releases.get())
            assertTrue("iteration $iteration: registry free again", registry.isAdmissible())
        }
    }

    // --------------------------------------------------------------- diagnostics

    @Test
    fun describeDistinguishesLiveFromTeardownAndFromConfirmedCleanup() {
        val native = FakeNative(initialHandle = 0L)
        val c = coordinatorFor(native)
        assertTrue(c.describe().contains("live"))

        val flow = requireNotNull(c.beginFlow()) { "flow registration must be admitted before teardown" }
        assertTrue(c.describe().contains("connect flow active"))

        c.requestTeardown(SHORT_TIMEOUT_MS)
        assertTrue(c.describe().contains("destroyed=true"))

        flow.exit()
        assertTrue(c.awaitCleanupConfirmed(5_000))
        assertTrue(c.describe().contains("cleanup confirmed"))
    }

    @Test
    fun cleanupThatRanWhileNoFlowWasActiveReportsNoUnresolvedState() {
        val native = FakeNative(initialHandle = HANDLE)
        val c = coordinatorFor(native)
        c.requestTeardown(TEARDOWN_TIMEOUT_MS)
        assertTrue("a clean teardown must report nothing unresolved", unresolved.none { it.contains("did not exit") })
    }

    private companion object {
        const val HANDLE = 0xABCDL
        const val SHORT_TIMEOUT_MS = 150L
        const val TEARDOWN_TIMEOUT_MS = 5_000L
        const val THREAD_TIMEOUT_MS = 10_000L
    }
}