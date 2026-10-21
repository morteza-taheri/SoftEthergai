package vn.unlimit.softether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.48 (§1.6.41) — FIX-3: the fail-closed guarantee and the new recovery, pinned.
 *
 * ## The defect these tests exist for
 *
 * The T5-SLOT-TEARDOWN residual (§12.5.1 row 9, known since v1.0.10) left the
 * process-wide native slot held in [NativeLifetimeRegistry.Phase.TEARDOWN] after a
 * failed connect. The next attempt was refused with
 *
 * ```
 * Start refused: native slot still held by an unresolved attempt
 *     (attempt=1 phase=TEARDOWN, reporting deadline expired (still blocked))
 * ```
 *
 * The soft-bound that was supposed to break that deadlock already existed
 * ([NativeLifetimeRegistry.NATIVE_SLOT_FORCE_RELEASE_AFTER_MS], 90 s) but measured
 * elapsed time from `teardownRequestedAtMs`, which [NativeLifetimeRegistry.markTeardownRequested]
 * **re-stamped on every call**. `destroyResources()` is idempotent by design and Auto
 * Mode calls `adapter.disconnect()` after every NATIVE_BUSY refusal, so each retry
 * restarted the clock. The bound was therefore unreachable: the recovery loop pushed the
 * release further away every time it ran.
 *
 * FIX-1 stamps only the **first** teardown request per attempt. These four tests pin
 * both halves of the resulting contract:
 *
 *  - **T-NEW-74** a normal, confirmed teardown still releases immediately — the new
 *    stamping introduces no false positive and no extra latency on the happy path;
 *  - **T-NEW-75** a holder still inside its window keeps the slot refused, and the new
 *    public entry point refuses too — the fail-closed guarantee is intact;
 *  - **T-NEW-76** the device retry loop (repeated teardown requests) can no longer
 *    restart the clock, and the bound fires at the documented deadline;
 *  - **T-NEW-77** the attempt-id guard still prevents the stale timer from clearing a
 *    slot that a newer attempt already owns.
 *
 * ## What these tests do NOT prove
 *
 * They do not prove that the real call sites reach [NativeLifetimeRegistry] in the
 * order assumed here, and they cannot prove the native `nativeDestroy` actually returns.
 * The registry is a Kotlin ledger: a forced release asserts only that the *bound*
 * elapsed, never that the native operation finished. Device verification of the recovery
 * on the exact deadlock the v1.0.47 run reproduced is still pending.
 *
 * The clock is injected, so no test sleeps.
 */
class NativeSlotRecoveryTest {

    /** Injected wall clock — the production default is `System.currentTimeMillis`. */
    private class FakeClock(var now: Long)

    private fun registry(
        clock: FakeClock,
        messages: MutableList<String> = mutableListOf(),
    ): NativeLifetimeRegistry =
        NativeLifetimeRegistry(logSink = { messages.add(it) }, nowMs = { clock.now })

    /** A non-null lease, failing the test if acquisition was refused. */
    private fun NativeLifetimeRegistry.acquire(): NativeLifetimeRegistry.Lease {
        val lease = tryAcquire()
        assertTrue(
            "expected acquisition to succeed but the registry reported ${describe()}",
            lease != null,
        )
        return lease!!
    }

    private val bound = NativeLifetimeRegistry.NATIVE_SLOT_FORCE_RELEASE_AFTER_MS

    // ------------------------------------------------------------------ T-NEW-74

    /**
     * T-NEW-74 — a normal teardown that is confirmed still releases the slot at once.
     *
     * The new first-stamp rule only affects holders that stay in TEARDOWN. A holder whose
     * native cleanup genuinely returns must be released by `confirmTeardown` exactly as
     * before, with no forced-release label and no dependence on the clock.
     */
    @Test
    fun aConfirmedTeardownStillReleasesImmediately() {
        val clock = FakeClock(1_000_000L)
        val messages = mutableListOf<String>()
        val registry = registry(clock, messages)

        val first = registry.acquire()
        registry.markNativeEntered(first)
        registry.markConnected(first)
        registry.markTeardownRequested(first)

        assertTrue(
            "confirmTeardown must release a genuinely finished attempt",
            registry.confirmTeardown(first),
        )
        assertTrue(registry.isAdmissible())
        assertEquals(0L, registry.teardownHeldMs())

        val second = registry.tryAcquire()
        assertNotNull("the next attempt must be admitted immediately", second)
        assertNotEquals(first.attemptId, second!!.attemptId)
        assertEquals(
            "a normal teardown must never be labelled as a forced release",
            NativeLifetimeRegistry.ReleaseOutcome.NORMAL,
            registry.lastReleaseOutcome(),
        )
        assertTrue(
            "no forced-release line may be emitted on the happy path, was: $messages",
            messages.none { it.contains("FORCED_RELEASE") },
        )
    }

    // ------------------------------------------------------------------ T-NEW-75

    /**
     * T-NEW-75 — inside the window the slot stays refused. This is the fail-closed
     * guarantee the brief forbids weakening.
     */
    @Test
    fun aHolderStillInsideItsWindowKeepsTheSlotRefused() {
        val clock = FakeClock(1_000_000L)
        val messages = mutableListOf<String>()
        val registry = registry(clock, messages)

        val stuck = registry.acquire()
        registry.markNativeEntered(stuck)
        registry.markTeardownRequested(stuck)
        registry.markDeadlineExpired(stuck)

        clock.now += bound - 1

        assertEquals(bound - 1, registry.teardownHeldMs())
        assertNull(
            "the soft-bound must not release a slot that is still within its window",
            registry.tryAcquire(),
        )
        assertFalse(registry.isAdmissible())
        assertTrue(registry.isHeldBy(stuck))
        assertEquals(
            NativeLifetimeRegistry.ReleaseOutcome.NORMAL,
            registry.lastReleaseOutcome(),
        )
        assertFalse(
            "the public recovery entry point must respect the same bound",
            registry.forceReleaseStaleTeardown(),
        )
        assertTrue(registry.isHeldBy(stuck))
        assertTrue(
            "nothing may be logged before the bound, was: $messages",
            messages.none { it.contains("FORCED_RELEASE") },
        )
    }

    // ------------------------------------------------------------------ T-NEW-76

    /**
     * T-NEW-76 — the device retry loop cannot restart the clock.
     *
     * This is the regression test for the actual defect. Each iteration models one
     * NATIVE_BUSY refusal followed by `adapter.disconnect()`, which re-enters
     * `destroyResources()` and therefore calls `markTeardownRequested` again for the same
     * attempt. Under the pre-fix re-stamping rule the elapsed time was always "since the
     * last request" and the bound was never reached; with the first-stamp rule it is
     * measured from the first request and fires on time.
     */
    @Test
    fun repeatedTeardownRequestsCannotRestartTheClock() {
        val clock = FakeClock(1_000_000L)
        val messages = mutableListOf<String>()
        val registry = registry(clock, messages)

        val stuck = registry.acquire()
        registry.markNativeEntered(stuck)
        registry.markTeardownRequested(stuck) // t0 — the only stamp that may count
        registry.markDeadlineExpired(stuck)

        // Four refused retries, each followed by a fresh teardown request, as Auto Mode
        // does on every NATIVE_BUSY.
        repeat(4) {
            clock.now += 20_000L
            assertNull(
                "iteration $it is still inside the window and must stay refused",
                registry.tryAcquire(),
            )
            registry.markTeardownRequested(stuck)
            assertEquals(
                "a repeated teardown request must not move the stamp",
                clock.now - 1_000_000L,
                registry.teardownHeldMs(),
            )
        }

        // 80 s of wall clock have elapsed and five teardown requests were recorded.
        clock.now += 10_000L // 90 s since t0

        val fresh = registry.tryAcquire()
        assertNotNull(
            "the bound must fire 90 s after the FIRST teardown request, not the last",
            fresh,
        )
        assertNotEquals(stuck.attemptId, fresh!!.attemptId)
        assertEquals(
            NativeLifetimeRegistry.ReleaseOutcome.FORCED_RELEASE_AFTER_TIMEOUT,
            registry.lastReleaseOutcome(),
        )
        assertTrue(
            "the forced release must name the stale attempt, was: $messages",
            messages.any {
                it.contains("FORCED_RELEASE_AFTER_TIMEOUT") &&
                        it.contains("attempt=${stuck.attemptId}")
            },
        )
    }

    // ------------------------------------------------------------------ T-NEW-77

    /**
     * T-NEW-77 — the attempt-id guard: a stale timer never clears a newer slot.
     *
     * Once a replacement attempt owns the slot, the old holder's late teardown request
     * must be a no-op, and the public recovery entry point must refuse to release the
     * new owner even long after the old bound would have elapsed.
     */
    @Test
    fun theStaleTimerNeverClearsASlotANewerAttemptOwns() {
        val clock = FakeClock(1_000_000L)
        val registry = registry(clock)

        val stuck = registry.acquire()
        registry.markNativeEntered(stuck)
        registry.markTeardownRequested(stuck)
        registry.markDeadlineExpired(stuck)

        clock.now += bound
        val fresh = registry.tryAcquire()
        assertNotNull("the bound must reclaim the stale holder", fresh)
        assertEquals(
            NativeLifetimeRegistry.ReleaseOutcome.FORCED_RELEASE_AFTER_TIMEOUT,
            registry.lastReleaseOutcome(),
        )

        // The obsolete attempt's teardown arrives late.
        registry.markTeardownRequested(stuck)
        registry.markDeadlineExpired(stuck)

        assertEquals(
            "the obsolete lease must not have moved the new holder into TEARDOWN",
            NativeLifetimeRegistry.Phase.RESERVED,
            registry.activePhase(),
        )
        assertEquals(0L, registry.teardownHeldMs())
        assertTrue(registry.isHeldBy(fresh))

        // Long past the bound, the stale timer must still leave the new holder alone:
        // it never requested a teardown, so the TEARDOWN bound cannot claim it.
        clock.now += bound * 3
        assertFalse(
            "the TEARDOWN bound must never release a slot a newer attempt owns",
            registry.forceReleaseStaleTeardown(),
        )
        assertTrue(registry.isHeldBy(fresh))
        assertNull(registry.tryAcquire())
        assertFalse(registry.isAdmissible())
    }
}
