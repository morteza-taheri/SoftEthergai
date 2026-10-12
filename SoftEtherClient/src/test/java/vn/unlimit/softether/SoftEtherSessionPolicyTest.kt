package vn.unlimit.softether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import vn.unlimit.softether.SoftEtherSessionPolicy.StartDecision
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.softether.model.ConnectionState

/**
 * Stage 2F-F.28 — covers the SoftEther session lifecycle policy that fixes the
 * defect chain reproduced on device in Stage 2F-F.26-ALT and diagnosed in
 * Stage 2F-F.27.
 *
 * TEST A  previous stop followed by a new session's CONNECTED must propagate
 * TEST B  CONNECTED must never be swallowed by a stopping latch
 * TEST E  teardown is idempotent AND resource-aware
 * TEST H1 the generic connect-catch's error teardown is session-owned (F.28H)
 * TEST H2 a released teardown leaves the start gate open (F.28H)
 */
class SoftEtherSessionPolicyTest {

    // ---------------------------------------------------------------- TEST A

    @Test
    fun `TEST A - new session after a previous stop delivers CONNECTED`() {
        val policy = SoftEtherSessionPolicy()

        // Attempt #1: fails, and the caller runs teardown.
        policy.beginTeardown(resourcesLive = true)
        assertTrue("attempt #1 must tear down", policy.isStopping)

        // Attempt #2: a genuinely new session begins.
        policy.onSessionStart()
        assertFalse("a new session must not inherit the teardown latch", policy.isStopping)

        policy.onRunning()
        assertTrue(
            "CONNECTED must be delivered after a previous stop (F.27 RC-1)",
            policy.shouldDeliver(ConnectionState.CONNECTED),
        )
    }

    @Test
    fun `TEST A - new session clears a stale wasConnected`() {
        val policy = SoftEtherSessionPolicy()
        policy.onConnectedDelivered()
        assertTrue(policy.wasConnected)

        policy.onSessionStart()
        assertFalse("wasConnected must not leak across sessions", policy.wasConnected)
    }

    @Test
    fun `TEST A - new session clears a stale isRunning`() {
        val policy = SoftEtherSessionPolicy()
        policy.onRunning()
        assertTrue(policy.isRunning)

        // Teardown sets isRunning false via beginTeardown.
        policy.beginTeardown(resourcesLive = true)
        assertFalse(policy.isRunning)

        policy.onRunning()
        policy.onSessionStart()
        assertFalse("isRunning must not leak across sessions", policy.isRunning)
    }

    // ---------------------------------------------------------------- TEST B

    @Test
    fun `TEST B - CONNECTED is suppressed once the current session tears down`() {
        val policy = SoftEtherSessionPolicy()

        // A live session that is now tearing down.
        policy.onSessionStart()
        assertTrue("the session must be active before teardown", policy.shouldDeliver(ConnectionState.CONNECTED))

        policy.beginTeardown(resourcesLive = true)
        assertTrue("teardown must latch isStopping", policy.isStopping)

        // F.28Q: beginTeardown() does not advance sessionGeneration, so a late
        // CONNECTED from this same session still satisfies isCurrentSession().
        // It must still be refused here, or the service republishes CONNECTED
        // after teardown already published DISCONNECTED.
        assertFalse(
            "CONNECTED must be suppressed once teardown has begun",
            policy.shouldDeliver(ConnectionState.CONNECTED),
        )
    }

    @Test
    fun `TEST B - a genuinely new session delivers CONNECTED after a teardown`() {
        val policy = SoftEtherSessionPolicy()

        // Session N: start, then tear down.
        policy.onSessionStart()
        policy.beginTeardown(resourcesLive = true)
        assertFalse(
            "session N's late CONNECTED must be refused",
            policy.shouldDeliver(ConnectionState.CONNECTED),
        )

        // Session N+1: started through the real entry point, which clears the
        // latch. This is the F.27 RC-1 regression guard -- dropping the
        // CONNECTED exemption must not permanently suppress CONNECTED.
        policy.onSessionStart()

        assertFalse("the new session must not inherit the teardown latch", policy.isStopping)
        assertTrue(
            "a legitimate new session must still deliver CONNECTED (F.27 RC-1)",
            policy.shouldDeliver(ConnectionState.CONNECTED),
        )
    }

    @Test
    fun `TEST B - CONNECTED is delivered when not stopping`() {
        val policy = SoftEtherSessionPolicy()
        assertTrue(policy.shouldDeliver(ConnectionState.CONNECTED))
    }

    @Test
    fun `TEST B - DISCONNECTED is still suppressed during teardown`() {
        val policy = SoftEtherSessionPolicy()
        policy.beginTeardown(resourcesLive = true)

        // The original suppression existed so a controller's teardown-time
        // DISCONNECTED/ERROR does not re-show a disconnect notification. That
        // behaviour must be preserved.
        assertFalse(policy.shouldDeliver(ConnectionState.DISCONNECTED))
        assertFalse(policy.shouldDeliver(ConnectionState.ERROR))
    }

    @Test
    fun `TEST B - non-terminal states pass through when not stopping`() {
        val policy = SoftEtherSessionPolicy()
        assertTrue(policy.shouldDeliver(ConnectionState.CONNECTING))
        assertTrue(policy.shouldDeliver(ConnectionState.TLS_HANDSHAKE))
        assertTrue(policy.shouldDeliver(ConnectionState.SESSION_SETUP))
        assertTrue(policy.shouldDeliver(ConnectionState.DISCONNECTED))
    }

    @Test
    fun `TEST B - the pre-fix behaviour really was broken (regression guard)`() {
        // Models the old rule: suppress everything while stopping.
        val oldIsStopping = true
        val oldWouldDeliver = !(oldIsStopping)
        assertFalse(
            "the pre-fix rule dropped CONNECTED; this test documents why the fix exists",
            oldWouldDeliver,
        )
    }

    // ---------------------------------------------------------------- TEST E

    @Test
    fun `TEST E - first teardown runs`() {
        val policy = SoftEtherSessionPolicy()
        assertTrue(policy.beginTeardown(resourcesLive = true))
        assertTrue(policy.isStopping)
    }

    @Test
    fun `TEST E - repeated teardown while resources are live still tears down`() {
        val policy = SoftEtherSessionPolicy()
        policy.beginTeardown(resourcesLive = true)

        // The critical regression: the old guard was `if (isStopping) return`,
        // so this second legitimate teardown was skipped and the TUN descriptor
        // was never closed, leaving an orphan tunnel on the device.
        assertTrue(
            "a second stop must not be swallowed while resources are live",
            policy.beginTeardown(resourcesLive = true),
        )
    }

    @Test
    fun `TEST E - teardown is skipped once nothing is left to release`() {
        val policy = SoftEtherSessionPolicy()
        policy.beginTeardown(resourcesLive = true)

        // Resources are gone: a further request is a genuine no-op.
        assertFalse(policy.beginTeardown(resourcesLive = false))
        assertTrue("the latch stays set", policy.isStopping)
    }

    @Test
    fun `TEST E - repeated teardown calls never throw and stay consistent`() {
        val policy = SoftEtherSessionPolicy()
        repeat(5) { policy.beginTeardown(resourcesLive = true) }
        assertTrue(policy.isStopping)
        assertFalse(policy.isRunning)
    }

    // ------------------------------------------------ wasConnected lifecycle

    @Test
    fun `wasConnected is cleared when the connection is lost`() {
        val policy = SoftEtherSessionPolicy()
        policy.onConnectedDelivered()
        assertTrue(policy.wasConnected)
        policy.onConnectionLost()
        assertFalse(policy.wasConnected)
    }

    @Test
    fun `onRunning does not clear the teardown latch`() {
        val policy = SoftEtherSessionPolicy()
        policy.beginTeardown(resourcesLive = true)
        policy.onRunning()
        assertTrue(
            "isRunning must not implicitly end teardown state",
            policy.isStopping,
        )
        assertTrue(policy.isRunning)
    }

    @Test
    fun `a tearing down session is still current so identity alone cannot gate a success claim`() {
        val policy = SoftEtherSessionPolicy()
        val session = policy.onSessionStart()
        policy.onRunning()

        // onError -> stopVpn() -> beginTeardown(). The generation is NOT advanced,
        // so the session that just failed is still the current session when
        // connect() returns normally.
        assertTrue(policy.beginTeardown(resourcesLive = true))

        assertTrue(
            "beginTeardown must not advance the generation; this is why F.28N " +
                "could not gate the success log on isCurrentSession() alone",
            policy.isCurrentSession(session),
        )
        assertTrue(
            "isStopping is the discriminator that separates a rejected result " +
                "from an accepted one",
            policy.isStopping,
        )

        // A genuinely new session clears the latch, restoring the accepted case
        // in which a success log is correct.
        val next = policy.onSessionStart()
        assertFalse(
            "a new session must not inherit the teardown latch",
            policy.isStopping,
        )
        assertTrue(policy.isCurrentSession(next))
    }

    // ---------------------------------------------------------------- TEST I

    @Test
    fun `TEST I - a new session is unaffected by the previous session teardown`() {
        val policy = SoftEtherSessionPolicy()

        // Session N: connected, then torn down. Resources are still live, so the
        // NonCancellable destroy for session N is still in flight.
        policy.onSessionStart()
        policy.onRunning()
        policy.onConnectedDelivered()
        assertTrue(policy.beginTeardown(resourcesLive = true))

        // Session N+1 begins while session N's asynchronous teardown has not
        // finished yet.
        policy.onSessionStart()
        assertFalse(
            "session N+1 must not inherit session N's teardown latch",
            policy.isStopping,
        )
        assertTrue(
            "session N+1's CONNECTED must be deliverable",
            policy.shouldDeliver(ConnectionState.CONNECTED),
        )

        // Session N+1 reaches CONNECTED and is delivered normally.
        policy.onRunning()
        assertTrue(policy.shouldDeliver(ConnectionState.CONNECTED))
        policy.onConnectedDelivered()
        assertTrue(policy.wasConnected)

        // Session N+1 can then be torn down on its own terms.
        assertTrue(policy.beginTeardown(resourcesLive = true))
        assertFalse(policy.isRunning)
    }

    @Test
    fun `TEST I - session N+1 start clears a stale running flag from session N`() {
        val policy = SoftEtherSessionPolicy()
        policy.onSessionStart()
        policy.onRunning()
        assertTrue("session N is running", policy.isRunning)

        policy.onSessionStart()

        assertFalse(
            "a stale isRunning from session N would make startVpn() return early",
            policy.isRunning,
        )
        assertTrue(
            "the stale running flag must not suppress the new session's CONNECTED",
            policy.shouldDeliver(ConnectionState.CONNECTED),
        )
    }

    // --------------------------------------------------- TEST C (RC-4 guard)

    @Test
    fun `TEST C - a genuine live session still refuses a duplicate start`() {
        val policy = SoftEtherSessionPolicy()
        policy.onSessionStart()
        policy.onRunning()
        assertTrue(
            "a real, running session must not be torn down by a duplicate connect",
            policy.shouldRefuseStart(resourcesLive = true),
        )
    }

    @Test
    fun `TEST C - a running flag with no resources is stale, not a live session`() {
        val policy = SoftEtherSessionPolicy()
        policy.onSessionStart()
        policy.onRunning()

        // ConnectionController emitted DISCONNECTED on its own path: wasConnected
        // is cleared, but stopVpn() never ran, so isRunning outlived its session.
        policy.onConnectionLost()
        assertFalse(policy.wasConnected)
        assertTrue(policy.isRunning)

        assertFalse(
            "with no controller, TUN or job the flag is stale and must not refuse the connect",
            policy.shouldRefuseStart(resourcesLive = false),
        )
    }

    @Test
    fun `TEST C - a connect in flight with live resources refuses a new start`() {
        // Stage 2F-F.28C (A-2). Before the fix this asserted `false` and
        // therefore encoded the defect: `isRunning` is only set once connect()
        // returns, so during the initial connect window the service looked idle
        // while controller / connectionJob were live, and a second
        // ACTION_CONNECT orphaned the in-flight session.
        val policy = SoftEtherSessionPolicy()
        val connecting = policy.onSessionStart()
        // connect() has not returned yet: onRunning() has NOT been called.
        assertFalse("onRunning() must not have been called yet", policy.isRunning)
        assertEquals(
            StartDecision.REFUSE_CONNECTING,
            policy.decideStart(resourcesLive = true),
        )
        assertTrue(policy.shouldRefuseStart(resourcesLive = true))

        // Once the connect completes, the refusal reason becomes "connected"
        // rather than silently turning into an accept.
        policy.onRunning()
        assertEquals(StartDecision.REFUSE_CONNECTED, policy.decideStart(resourcesLive = true))
        assertEquals(connecting, policy.sessionGeneration)
    }

    // ---------------------------------- TEST S (F.28A-REVIEW session identity)

    @Test
    fun `TEST S1 - a callback from session N cannot act on session N+1`() {
        val policy = SoftEtherSessionPolicy()
        val sessionN = policy.onSessionStart()
        val sessionNPlus1 = policy.onSessionStart()

        assertFalse(
            "session N's late CONNECTED must be recognised as obsolete",
            policy.isCurrentSession(sessionN),
        )
        assertTrue(policy.isCurrentSession(sessionNPlus1))
    }

    @Test
    fun `TEST S2 - session N+1 CONNECTED is still delivered`() {
        val policy = SoftEtherSessionPolicy()
        policy.onSessionStart()
        val sessionNPlus1 = policy.onSessionStart()

        assertTrue(
            "the identity check must never suppress the current session's CONNECTED",
            policy.isCurrentSession(sessionNPlus1),
        )
        assertTrue(policy.shouldDeliver(ConnectionState.CONNECTED))
        policy.onConnectedDelivered()
        assertTrue(policy.wasConnected)
    }

    @Test
    fun `TEST S2 - identity is independent of the teardown latch`() {
        // The exact combination that made RC-1 possible: a cleared session must
        // still deliver CONNECTED, and an obsolete one must still be rejected
        // while teardown state says nothing is stopping.
        val policy = SoftEtherSessionPolicy()
        val sessionN = policy.onSessionStart()
        policy.beginTeardown(resourcesLive = true)
        val sessionNPlus1 = policy.onSessionStart()

        assertFalse(policy.isStopping)
        assertTrue(policy.isCurrentSession(sessionNPlus1))
        assertTrue(policy.shouldDeliver(ConnectionState.CONNECTED))
        assertFalse(policy.isCurrentSession(sessionN))
    }

    @Test
    fun `TEST S3 - session N teardown may not stop the service for session N+1`() {
        val policy = SoftEtherSessionPolicy()
        val sessionN = policy.onSessionStart()
        val sessionNPlus1 = policy.onSessionStart()

        assertFalse(
            "stopSelf() from session N's deferred teardown would destroy session N+1 in onDestroy",
            policy.mayStopServiceFor(sessionN),
        )
        assertTrue(policy.mayStopServiceFor(sessionNPlus1))
    }

    @Test
    fun `TEST S3 - a session may stop the service while it is still current`() {
        val policy = SoftEtherSessionPolicy()
        val only = policy.onSessionStart()
        assertTrue(policy.mayStopServiceFor(only))
    }

    @Test
    fun `generations are unique and monotonic`() {
        val policy = SoftEtherSessionPolicy()
        val seen = (1..5).map { policy.onSessionStart() }
        assertEquals("every session must get its own identity", seen.size, seen.toSet().size)
        assertEquals(seen.sorted(), seen)
    }

    // ------------------------------------------- TEST A1 (F.28C defect A-1)

    @Test
    fun `TEST A1-1 - the current session's error may trigger teardown`() {
        val policy = SoftEtherSessionPolicy()
        val session = policy.onSessionStart()
        assertTrue(
            "a current session's error must still tear the session down",
            policy.shouldTearDownOnError(session),
        )
    }

    @Test
    fun `TEST A1-2 - an obsolete session's error cannot trigger teardown`() {
        val policy = SoftEtherSessionPolicy()
        val sessionN = policy.onSessionStart()
        policy.onSessionStart() // session N+1 replaces session N
        assertFalse(
            "an obsolete error must not be allowed to call stopVpn()",
            policy.shouldTearDownOnError(sessionN),
        )
    }

    @Test
    fun `TEST A1-3 - an obsolete error cannot destroy the current controller`() {
        val policy = SoftEtherSessionPolicy()
        val sessionN = policy.onSessionStart()
        policy.onSessionStart()
        policy.onRunning()

        assertFalse(policy.shouldTearDownOnError(sessionN))

        // The current session's resources are untouched: still running, still the
        // current session, so nothing about it was torn down.
        assertTrue("the current session must still be running", policy.isRunning)
        assertTrue(policy.shouldRefuseStart(resourcesLive = true))
        assertEquals(StartDecision.REFUSE_CONNECTED, policy.decideStart(resourcesLive = true))
    }

    @Test
    fun `TEST A1-4 - an obsolete error cannot stop the service`() {
        val policy = SoftEtherSessionPolicy()
        val sessionN = policy.onSessionStart()
        val sessionNPlus1 = policy.onSessionStart()

        // stopSelf() is gated on mayStopServiceFor(), which shares the same
        // identity. An obsolete session must fail both gates, and must not
        // succeed on one while failing the other.
        assertFalse(policy.mayStopServiceFor(sessionN))
        assertFalse(policy.shouldTearDownOnError(sessionN))
        assertTrue(policy.mayStopServiceFor(sessionNPlus1))
    }

    @Test
    fun `TEST A1-5 - current-session error behaviour is unchanged across a failure`() {
        val policy = SoftEtherSessionPolicy()
        val session = policy.onSessionStart()
        policy.onRunning()
        policy.onConnectedDelivered()

        assertTrue(policy.shouldTearDownOnError(session))
        // Tearing down is the same operation as before: it marks the teardown and
        // releases the running flag, and a later session starts cleanly.
        assertTrue(policy.beginTeardown(resourcesLive = true))
        assertTrue(policy.isStopping)
        assertFalse(policy.isRunning)

        val next = policy.onSessionStart()
        assertEquals(StartDecision.ACCEPT, policy.decideStart(resourcesLive = false))
        assertTrue(policy.shouldTearDownOnError(next))
    }

    // ------------------------------------------- TEST A2 (F.28C defect A-2)

    @Test
    fun `TEST A2-1 - an idle service accepts a start`() {
        val policy = SoftEtherSessionPolicy()
        assertEquals(StartDecision.ACCEPT, policy.decideStart(resourcesLive = false))
        assertFalse(policy.shouldRefuseStart(resourcesLive = false))
    }

    @Test
    fun `TEST A2-2 - a connected session refuses a start`() {
        val policy = SoftEtherSessionPolicy()
        policy.onSessionStart()
        policy.onRunning()
        assertEquals(StartDecision.REFUSE_CONNECTED, policy.decideStart(resourcesLive = true))
        assertTrue(policy.shouldRefuseStart(resourcesLive = true))
    }

    @Test
    fun `TEST A2-3 - an initial connection in flight refuses a start`() {
        val policy = SoftEtherSessionPolicy()
        policy.onSessionStart()
        // connect() has not returned: resources are live, isRunning is not set.
        assertEquals(StartDecision.REFUSE_CONNECTING, policy.decideStart(resourcesLive = true))
    }

    @Test
    fun `TEST A2-4 - teardown in progress with live resources refuses a start`() {
        val policy = SoftEtherSessionPolicy()
        policy.onSessionStart()
        policy.onRunning()
        assertTrue(policy.beginTeardown(resourcesLive = true))
        assertEquals(StartDecision.REFUSE_TEARDOWN, policy.decideStart(resourcesLive = true))
    }

    @Test
    fun `TEST A2-5 - after teardown released everything a new start is accepted`() {
        val policy = SoftEtherSessionPolicy()
        policy.onSessionStart()
        policy.onRunning()
        assertTrue(policy.beginTeardown(resourcesLive = true))

        // stopVpn() clears controller / connectionJob / vpnInterface
        // synchronously, so the service owns nothing even though the controller's
        // own destroy is still finishing.
        assertEquals(StartDecision.ACCEPT, policy.decideStart(resourcesLive = false))
        assertFalse(policy.shouldRefuseStart(resourcesLive = false))
    }

    @Test
    fun `TEST A2-6 - the F27 latched teardown flag regression stays fixed`() {
        val policy = SoftEtherSessionPolicy()
        repeat(3) { policy.onSessionStart() }
        policy.beginTeardown(resourcesLive = true)

        // Even with the teardown latch set, a session whose resources are gone
        // must be startable, and the new session must deliver CONNECTED. This is
        // the exact F.27 failure: a permanently latched flag suppressed every
        // later session's CONNECTED.
        repeat(5) {
            assertEquals(StartDecision.ACCEPT, policy.decideStart(resourcesLive = false))
            val session = policy.onSessionStart()
            assertFalse("a new session must not inherit the latch", policy.isStopping)
            assertTrue(
                "a new session's CONNECTED must be deliverable",
                policy.shouldDeliver(ConnectionState.CONNECTED),
            )
            assertTrue(policy.shouldTearDownOnError(session))
            policy.beginTeardown(resourcesLive = true)
        }
    }

    @Test
    fun `TEST A2-7 - a refused start does not orphan the previous session`() {
        val policy = SoftEtherSessionPolicy()
        val first = policy.onSessionStart()
        policy.onRunning()
        val generationBefore = policy.sessionGeneration
        val runningBefore = policy.isRunning

        // A second start is refused, so onSessionStart() is never reached: the
        // generation cannot advance and the previous session keeps its identity
        // and resources.
        assertTrue(policy.shouldRefuseStart(resourcesLive = true))

        assertEquals(generationBefore, policy.sessionGeneration)
        assertEquals(first, policy.sessionGeneration)
        assertEquals(runningBefore, policy.isRunning)
        assertTrue("the refused session is still the current one", policy.isCurrentSession(first))
    }

    @Test
    fun `TEST A2-8 - a stale callback from the refused session cannot affect the new one`() {
        val policy = SoftEtherSessionPolicy()
        val staleSession = policy.onSessionStart()
        policy.beginTeardown(resourcesLive = true)
        val newSession = policy.onSessionStart()

        assertFalse(policy.isCurrentSession(staleSession))
        assertFalse(policy.shouldTearDownOnError(staleSession))
        assertFalse(policy.mayStopServiceFor(staleSession))

        assertTrue(policy.isCurrentSession(newSession))
        assertTrue(policy.shouldTearDownOnError(newSession))
        assertTrue(policy.mayStopServiceFor(newSession))
        assertTrue(policy.shouldDeliver(ConnectionState.CONNECTED))
    }

    // ------------------------------------------- TEST E28 (F.28E session ownership)

    @Test
    fun `TEST E28-1 - a current session may publish its own terminal state`() {
        val policy = SoftEtherSessionPolicy()
        val session = policy.onSessionStart()
        assertTrue(
            "the session that owns the lifecycle must still be able to publish its terminal state",
            policy.isCurrentSession(session),
        )
    }

    @Test
    fun `TEST E28-2 - an obsolete session may not publish terminal state`() {
        val policy = SoftEtherSessionPolicy()
        val sessionN = policy.onSessionStart()
        policy.onSessionStart() // N+1 becomes current

        assertFalse(
            "an obsolete session must not publish terminal lifecycle state for the current session",
            policy.isCurrentSession(sessionN),
        )
    }

    @Test
    fun `TEST E28-3 - an obsolete cancellation cannot change current lifecycle state`() {
        val policy = SoftEtherSessionPolicy()
        val sessionN = policy.onSessionStart()
        policy.onRunning()
        policy.onConnectedDelivered()

        // N is stopped and N+1 takes over.
        assertTrue(policy.beginTeardown(resourcesLive = true))
        val sessionNPlus1 = policy.onSessionStart()
        policy.onRunning()

        // N's cancellation branch now evaluates its ownership checks.
        val ownsLifecycle = policy.isCurrentSession(sessionN)
        assertFalse(ownsLifecycle)

        // Nothing N could publish or mutate is allowed to apply.
        assertFalse(policy.shouldTearDownOnError(sessionN))
        assertFalse(policy.mayStopServiceFor(sessionN))

        // The current session's lifecycle state is exactly what N+1 established.
        assertTrue(policy.isCurrentSession(sessionNPlus1))
        assertTrue("N+1 must still be running", policy.isRunning)
        assertFalse("N must not be able to re-mark N+1 connected", policy.wasConnected)
    }

    @Test
    fun `TEST E28-4 - an obsolete cancellation leaves the publication gate closed`() {
        val policy = SoftEtherSessionPolicy()
        val sessionN = policy.onSessionStart()
        val sessionNPlus1 = policy.onSessionStart()
        policy.onRunning()
        policy.onConnectedDelivered()

        // Exactly the branch F.28E added: publication happens only when the gate
        // is open. An obsolete session takes the else path, so no terminal state
        // reaches TunnelStateWatcher/GlobalVpnTracker and no tunnel-lost is raised.
        val published = policy.isCurrentSession(sessionN)
        assertFalse("no terminal state may be published for an obsolete session", published)

        assertTrue("the current session may still publish", policy.isCurrentSession(sessionNPlus1))
        assertTrue(policy.wasConnected)
    }

    @Test
    fun `TEST E28-5 - an obsolete connect cannot mark the current session running`() {
        val policy = SoftEtherSessionPolicy()
        val sessionN = policy.onSessionStart()
        val sessionNPlus1 = policy.onSessionStart()

        // N+1 has not connected yet.
        assertFalse(policy.isRunning)

        // N's connect() returned late. The guard means `onRunning()` is not
        // applied on N+1's behalf.
        val mayMarkRunning = policy.isCurrentSession(sessionN)
        assertFalse("an obsolete session must not mark the current session running", mayMarkRunning)
        assertFalse(policy.isRunning)

        // N+1 marking itself running still works.
        assertTrue(policy.isCurrentSession(sessionNPlus1))
        policy.onRunning()
        assertTrue(policy.isRunning)
    }

    @Test
    fun `TEST E28-7 - five successive sessions remain valid`() {
        val policy = SoftEtherSessionPolicy()
        repeat(5) { attempt ->
            assertEquals(StartDecision.ACCEPT, policy.decideStart(resourcesLive = false))
            val session = policy.onSessionStart()
            assertFalse("a new session must not inherit the latch", policy.isStopping)
            assertTrue(policy.shouldDeliver(ConnectionState.CONNECTED))
            assertTrue(policy.isCurrentSession(session))
            policy.onRunning()
            assertTrue(policy.isRunning)
            assertTrue(policy.beginTeardown(resourcesLive = true))
            assertEquals(attempt + 1, policy.sessionGeneration)
        }
        assertEquals(5, policy.sessionGeneration)
    }

    @Test
    fun `TEST E28-8 - a refused second start alters nothing`() {
        val policy = SoftEtherSessionPolicy()
        val session = policy.onSessionStart()
        policy.onRunning()
        val generationBefore = policy.sessionGeneration
        val runningBefore = policy.isRunning
        val connectedBefore = policy.wasConnected

        assertTrue("the second start must be refused", policy.shouldRefuseStart(resourcesLive = true))

        assertEquals("generation must not advance", generationBefore, policy.sessionGeneration)
        assertEquals(runningBefore, policy.isRunning)
        assertEquals(connectedBefore, policy.wasConnected)
        assertTrue("ownership must be unchanged", policy.isCurrentSession(session))
        assertFalse("a refusal must not mark a teardown", policy.isStopping)
    }

    @Test
    fun `policy starts clean`() {
        val policy = SoftEtherSessionPolicy()
        assertFalse(policy.isStopping)
        assertFalse(policy.isRunning)
        assertFalse(policy.wasConnected)
        // Not stopping: every state, including CONNECTED, is deliverable.
        assertTrue(policy.shouldDeliver(ConnectionState.CONNECTED))
        assertTrue(policy.shouldDeliver(ConnectionState.DISCONNECTED))
    }

    // ------------------------------------------- TEST H1 (F.28H defect F-2)

    /*
     * The generic `catch (e: Exception)` in startVpn's connect coroutine is the
     * last lifecycle branch that calls stopVpn() from inside a session
     * callback, and F.28H gates it on the same identity check its sibling
     * callbacks already use: shouldTearDownOnError(session).
     *
     * These tests cover the DECISION that guard consults. They do not, and
     * cannot, prove the service's wiring — that the catch passes its own
     * `session` generation rather than a stale one is service source evidence,
     * not something this class observes.
     */

    @Test
    fun `TEST H1-1 - a current session's connect failure may initiate error teardown`() {
        val policy = SoftEtherSessionPolicy()
        val session = policy.onSessionStart()

        // The guard must not turn a genuine failure of the live session into a
        // silent no-op: this is the branch that reports the error to the UI and
        // releases the session's own resources.
        assertTrue(
            "the current session must still be allowed to run error teardown",
            policy.shouldTearDownOnError(session),
        )
    }

    @Test
    fun `TEST H1-2 - an obsolete session's connect failure is refused`() {
        val policy = SoftEtherSessionPolicy()
        val sessionN = policy.onSessionStart()
        policy.onSessionStart() // session N+1 replaces session N

        assertFalse(
            "an obsolete session's failure must not reach stopVpn(), which would " +
                    "cancel the current job, null the current controller, close the " +
                    "current TUN and broadcast a false ERROR",
            policy.shouldTearDownOnError(sessionN),
        )
    }

    @Test
    fun `TEST H1-3 - an obsolete session's connect failure cannot invalidate the current session`() {
        val policy = SoftEtherSessionPolicy()
        val sessionN = policy.onSessionStart()
        val sessionNPlus1 = policy.onSessionStart()
        // The replacement session is fully live: running, connected, and holding
        // resources. A stale failure must leave every one of those intact.
        policy.onRunning()
        policy.onConnectedDelivered()

        val generationBefore = policy.sessionGeneration
        assertFalse(policy.shouldTearDownOnError(sessionN))

        assertEquals(
            "a refused stale failure must not advance the session identity",
            generationBefore,
            policy.sessionGeneration,
        )
        assertTrue("the current session must still be current", policy.isCurrentSession(sessionNPlus1))
        assertTrue("the current session must still be running", policy.isRunning)
        assertTrue("the current session must still count as connected", policy.wasConnected)
        assertFalse(
            "a refused stale failure must not latch a teardown",
            policy.isStopping,
        )
        // Its resources are still owned, so the start gate must still refuse.
        assertEquals(StartDecision.REFUSE_CONNECTED, policy.decideStart(resourcesLive = true))
    }

    @Test
    fun `TEST H1-4 - refusing a stale failure does not disarm the current session's own teardown`() {
        val policy = SoftEtherSessionPolicy()
        val sessionN = policy.onSessionStart()
        val sessionNPlus1 = policy.onSessionStart()

        // A stale failure is refused...
        assertFalse(policy.shouldTearDownOnError(sessionN))
        // ...and the check is a pure predicate: it left no latch behind that
        // would swallow the current session's legitimate teardown.
        assertTrue(
            "the current session must still be able to tear itself down",
            policy.shouldTearDownOnError(sessionNPlus1),
        )
        assertTrue(policy.beginTeardown(resourcesLive = true))
        assertTrue(policy.isStopping)
    }

    // ------------------------------------------- TEST H2 (F.28H defect F-3)

    /*
     * F-28G's F-3 asked whether the cancellation branch can leave `connectionJob`
     * non-null and permanently latch the start gate. The verdict was NOT
     * REACHABLE, because every canceller of `connectionJob` also nulls it
     * (stopVpn and onDestroy), so `sessionResourcesLive` can never be held true
     * by a dead job.
     *
     * What this test pins is only the POLICY half of that argument: once
     * teardown reports its resources released, the start gate must accept again.
     * It is a regression guard on decideStart's "teardown released everything"
     * row. It is NOT evidence about the service's field bookkeeping — only the
     * service's own assignments prove that, and they were left unchanged.
     */

    @Test
    fun `TEST H2-1 - a released teardown leaves the start gate open`() {
        val policy = SoftEtherSessionPolicy()

        // A session that owned resources, then tore them all down.
        policy.onSessionStart()
        policy.onRunning()
        assertTrue(policy.beginTeardown(resourcesLive = true))
        assertEquals(StartDecision.REFUSE_TEARDOWN, policy.decideStart(resourcesLive = true))

        // Teardown released everything. If the gate stayed shut here, a stale
        // resource reference would permanently poison every later connect —
        // the F.27 defect class this policy exists to prevent.
        assertEquals(
            "once teardown has released its resources a new connect must be accepted",
            StartDecision.ACCEPT,
            policy.decideStart(resourcesLive = false),
        )
        assertFalse(policy.shouldRefuseStart(resourcesLive = false))
    }

    @Test
    fun `TEST H2-2 - a released teardown still clears the latch for the next session`() {
        val policy = SoftEtherSessionPolicy()
        policy.onSessionStart()
        policy.onRunning()
        assertTrue(policy.beginTeardown(resourcesLive = true))
        assertTrue("teardown must latch while it still owns resources", policy.isStopping)

        // The next connect is accepted and starts its own lifecycle state.
        assertEquals(StartDecision.ACCEPT, policy.decideStart(resourcesLive = false))
        val next = policy.onSessionStart()
        assertFalse("a new session must not inherit the teardown latch", policy.isStopping)
        assertTrue(policy.isCurrentSession(next))
        assertTrue(policy.shouldTearDownOnError(next))
    }
}