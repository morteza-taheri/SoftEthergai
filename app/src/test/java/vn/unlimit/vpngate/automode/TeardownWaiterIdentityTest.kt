package vn.unlimit.vpngate.automode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vn.unlimit.softether.TeardownCompletion
import vn.unlimit.softether.TeardownCompletion.Outcome

/**
 * Stage 2F-F.28AD-B — a teardown completion may satisfy **only** the waiter for the same
 * teardown attempt.
 *
 * ## The defect
 *
 * F.28AD-A proved the delivery rule was monotonicity, not identity: a waiter was satisfied by
 * *any* completion newer than the last one accepted. It produced a concrete interleaving —
 *
 * ```
 * stopVpn()  -> teardown A (real)                 -> completion A
 * stopVpn()  -> teardown B (NOOP_REENTRANT)       -> completion B   (B > A, arrives first)
 * ```
 *
 * — in which B satisfied the waiter for A, `disconnect()` returned while A's teardown was
 * still pending, and the genuinely authoritative completion A was then discarded as stale.
 * The consumer never learned whether native cleanup had happened.
 *
 * ## Why identity was not already available
 *
 * The waiter is armed *before* the disconnect is dispatched, but the attempt id was allocated
 * inside `stopVpn`, strictly afterwards. So the caller now reserves the id up front and hands
 * it to the service in the Intent, and the waiter names exactly that id.
 *
 * ## What these tests do and do not prove
 *
 * **Proven here:** the delivery rule itself — identity, one-shot, no poisoning by unconsumed
 * completions, and foreign-stack isolation. All of it runs on the real `TunnelStateWatcher`.
 *
 * **Not proven here:** that the id survives the Adapter → Intent → Service → completion
 * plumbing. `AndroidConnectionAdapter.disconnect()` needs a real `Context`, and `stopVpn()`
 * needs a real `Service`, so that hop is a source-level fact only — see `T11`.
 */
class TeardownWaiterIdentityTest {

    private val received = mutableListOf<TeardownCompletion>()

    @Before
    fun setUp() {
        received.clear()
        // The watcher's high-water mark is process-global and monotonic by design. The
        // mechanism is unchanged from F.28AA (F.28AD-B does not touch it), and no test here
        // depends on its magnitude: every test below arms for the id it then delivers.
        TunnelStateWatcher.rewindTeardownSequenceForTests()
    }

    private fun armFor(attemptId: Int): Long =
        TunnelStateWatcher.armTeardown(attemptId) { received += it }

    private fun completion(id: Int, outcome: Outcome = Outcome.NATIVE_CONFIRMED) =
        TeardownCompletion.of(id, sessionId = 1, outcome = outcome)

    private fun completeNow(id: Int, outcome: Outcome = Outcome.NATIVE_CONFIRMED) {
        TunnelStateWatcher.onSoftEtherTeardownComplete(completion(id, outcome))
    }

    // --------------------------------------------------------------- Race 1 / T1

    @Test
    fun `T1 - a newer unrelated completion is rejected and the waiter stays armed`() {
        val token = armFor(100)
        try {
            completeNow(101) // newer, but not the attempt this waiter named

            assertEquals("an unrelated newer completion must not be delivered", 0, received.size)
            // The waiter is still armed: its own attempt has not reported yet.
            completeNow(102)
            assertEquals("an unrelated completion must not consume the waiter", 0, received.size)

            completeNow(100)
            assertEquals("the waiter's own attempt must still be deliverable", 1, received.size)
        } finally {
            TunnelStateWatcher.disarmTeardown(token)
        }
    }

    // --------------------------------------------------------------- Race 2 / T2

    @Test
    fun `T2 - the exact completion is accepted exactly once and the waiter is removed`() {
        val token = armFor(110)
        try {
            completeNow(110)

            assertEquals(1, received.size)
            assertEquals("the exact completion must be forwarded verbatim", 110, received.single().attemptId)
            assertEquals(Outcome.NATIVE_CONFIRMED, received.single().outcome)

            // Waiter removed: nothing else can reach it.
            completeNow(110)
            completeNow(111)
            assertEquals("a delivered waiter must be gone", 1, received.size)
        } finally {
            TunnelStateWatcher.disarmTeardown(token)
        }
    }

    // --------------------------------------------------------------- Race 3 / T3

    @Test
    fun `T3 - an older completion cannot satisfy a newer waiter`() {
        val token = armFor(121)
        try {
            completeNow(120) // older than what this waiter named

            assertEquals("an older completion must not be delivered", 0, received.size)

            completeNow(121)
            assertEquals("the named attempt must still be deliverable", 1, received.size)
        } finally {
            TunnelStateWatcher.disarmTeardown(token)
        }
    }

    // --------------------------------------------------------------- Race 4 / T4

    @Test
    fun `T4 - a reentrant NOOP completion cannot complete the real teardown's waiter`() {
        val token = armFor(130)
        try {
            // The re-entrant stop that F.28AD-A proved would previously win on recency.
            completeNow(131, Outcome.NOOP_REENTRANT)

            assertEquals("NOOP_REENTRANT must not end a real teardown wait", 0, received.size)

            completeNow(130, Outcome.NATIVE_CONFIRMED)

            assertEquals("the real completion must be delivered exactly once", 1, received.size)
            assertEquals(130, received.single().attemptId)
            assertEquals(
                "the authoritative outcome must survive, not be replaced by the no-op",
                Outcome.NATIVE_CONFIRMED,
                received.single().outcome,
            )
        } finally {
            TunnelStateWatcher.disarmTeardown(token)
        }
    }

    // ------------------------------------------------------------------ T5

    @Test
    fun `T5 - the legitimate completion survives a rejected newer completion`() {
        val token = armFor(140)
        try {
            completeNow(141, Outcome.NOOP_REENTRANT)
            assertEquals(0, received.size)

            completeNow(140, Outcome.NATIVE_UNRESOLVED)

            assertEquals("the real result must still arrive", 1, received.size)
            assertEquals(
                "and it must be the real result, not the discarded no-op",
                Outcome.NATIVE_UNRESOLVED,
                received.single().outcome,
            )
        } finally {
            TunnelStateWatcher.disarmTeardown(token)
        }
    }

    // --------------------------------------------------------------- Race 5 / T6

    @Test
    fun `T6 - a completion queued before waiter replacement cannot satisfy the new waiter`() {
        // A queued completion keeps its own immutable id; it is never re-read as "current".
        val queued = completion(150)
        val firstToken = armFor(queued.attemptId)

        val secondToken = armFor(151) // waiter replaced while the completion above was queued

        try {
            // The queued completion now executes, after the replacement.
            TunnelStateWatcher.onSoftEtherTeardownComplete(queued)

            assertEquals(
                "a queued completion must not complete the replacement waiter",
                0,
                received.size,
            )

            completeNow(151)
            assertEquals("the replacement waiter's own completion must still work", 1, received.size)
            assertEquals(151, received.single().attemptId)
        } finally {
            TunnelStateWatcher.disarmTeardown(firstToken)
            TunnelStateWatcher.disarmTeardown(secondToken)
        }
    }

    // --------------------------------------------------------------- Race 6 / T7

    @Test
    fun `T7 - a completion with no waiter cannot poison a future waiter`() {
        // Nothing is armed, so nothing is consumed and no state may move.
        completeNow(160, Outcome.NOOP_REENTRANT)
        completeNow(161)

        val token = armFor(162)
        try {
            completeNow(162)

            assertEquals(
                "an unconsumed completion must not stop a later waiter from being served",
                1,
                received.size,
            )
            assertEquals(162, received.single().attemptId)
        } finally {
            TunnelStateWatcher.disarmTeardown(token)
        }
    }

    // ------------------------------------------------------------ Race 7 & 8 / T8, T9

    @Test
    fun `T8 - a foreign stack terminal report cannot satisfy a native teardown waiter`() {
        val token = armFor(170)
        try {
            // Exactly what the OpenVPN status path forwards.
            TunnelStateWatcher.onSoftEtherTeardownComplete(
                TeardownCompletion.foreignStackTerminal(),
            )

            assertEquals(
                "FOREIGN_STACK_TERMINAL asserts nothing about native cleanup and must not " +
                        "end a native teardown wait",
                0,
                received.size,
            )

            completeNow(169) // still an unrelated attempt
            assertEquals(0, received.size)
        } finally {
            TunnelStateWatcher.disarmTeardown(token)
        }
    }

    @Test
    fun `T9 - a rejected foreign report does not block the real completion`() {
        val token = armFor(180)
        try {
            TunnelStateWatcher.onSoftEtherTeardownComplete(
                TeardownCompletion.foreignStackTerminal(),
            )
            assertEquals(0, received.size)

            completeNow(180)

            assertEquals("the real completion must still be delivered", 1, received.size)
            assertEquals(180, received.single().attemptId)
        } finally {
            TunnelStateWatcher.disarmTeardown(token)
        }
    }

    // ---------------------------------------------------------------- T10

    @Test
    fun `T10 - delivery stays one-shot when the same completion arrives repeatedly`() {
        val token = armFor(190)
        try {
            repeat(4) { completeNow(190) }

            assertEquals("delivery must happen exactly once", 1, received.size)
        } finally {
            TunnelStateWatcher.disarmTeardown(token)
        }
    }

    // ------------------------------------------------- waiter isolation and guards

    @Test
    fun `two independent waiters do not share completion state`() {
        val seenByFirst = mutableListOf<TeardownCompletion>()
        val seenBySecond = mutableListOf<TeardownCompletion>()

        val firstToken = TunnelStateWatcher.armTeardown(200) { seenByFirst += it }
        try {
            // The second arming replaces the first, as it always has.
            val secondToken = TunnelStateWatcher.armTeardown(201) { seenBySecond += it }
            try {
                completeNow(200)
                assertEquals("a replaced waiter must not be served", 0, seenByFirst.size)
                assertEquals("nor may it serve the replacement", 0, seenBySecond.size)

                completeNow(201)
                assertEquals(0, seenByFirst.size)
                assertEquals(1, seenBySecond.size)
            } finally {
                TunnelStateWatcher.disarmTeardown(secondToken)
            }
        } finally {
            TunnelStateWatcher.disarmTeardown(firstToken)
        }
    }

    @Test
    fun `a non-positive attempt id arms nothing, so a foreign report cannot exploit it`() {
        // `0` is what a foreign terminal report carries. Arming for it would let that report
        // end a wait, which is exactly what must be impossible.
        val token = TunnelStateWatcher.armTeardown(0) { received += it }
        try {
            TunnelStateWatcher.onSoftEtherTeardownComplete(
                TeardownCompletion.foreignStackTerminal(),
            )
            assertEquals("no waiter may have been armed for id 0", 0, received.size)

            completeNow(1)
            assertEquals("and nothing may be delivered to it", 0, received.size)
        } finally {
            TunnelStateWatcher.disarmTeardown(token)
        }
    }

    @Test
    fun `an unrelated teardown cannot be mistaken for the awaited one even when adjacent`() {
        // Guards against any future relaxation back to ordering. The two ids differ by one,
        // which is the tightest possible neighbouring case.
        val token = armFor(212)
        try {
            completeNow(211)
            assertEquals(0, received.size)
            completeNow(213)
            assertEquals(0, received.size)
            completeNow(212)
            assertEquals(1, received.size)
            assertNotEquals(211, received.single().attemptId)
            assertNotEquals(213, received.single().attemptId)
            assertTrue(
                "only the named attempt may ever have been delivered",
                received.all { it.attemptId == 212 },
            )
        } finally {
            TunnelStateWatcher.disarmTeardown(token)
        }
    }

    @Test
    fun `an unresolved outcome is forwarded without ever reading as confirmed`() {
        val token = armFor(220)
        try {
            completeNow(220, Outcome.NATIVE_UNRESOLVED)

            assertEquals(1, received.size)
            assertFalse(
                "the watcher must not launder an unresolved teardown into a confirmed one",
                received.single().nativeCleanupConfirmed,
            )
        } finally {
            TunnelStateWatcher.disarmTeardown(token)
        }
    }
}
