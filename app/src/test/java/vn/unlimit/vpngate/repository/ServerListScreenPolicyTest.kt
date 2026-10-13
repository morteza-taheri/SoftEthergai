package vn.unlimit.vpngate.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for [ServerListScreenPolicy]: what opening the server list screen
 * is allowed to do to the network.
 *
 * The regression these tests pin down is the cost and the interference of an
 * unconditional load on entry — re-entering the screen downloaded a fresh
 * server list every time, on top of the scheduled update that already owns
 * freshness.
 */
class ServerListScreenPolicyTest {

    @Test
    fun `opening the screen with a stored list does not load`() {
        assertFalse(ServerListScreenPolicy.shouldLoadOnOpen(1))
    }

    @Test
    fun `opening the screen with a full stored list does not load`() {
        assertFalse(ServerListScreenPolicy.shouldLoadOnOpen(700))
    }

    @Test
    fun `opening the screen with nothing stored loads`() {
        // A fresh install, or a list cleared by the user: there is nothing to
        // show, and this is exactly the case the scheduler calls due.
        assertTrue(ServerListScreenPolicy.shouldLoadOnOpen(0))
    }

    @Test
    fun `a never populated list is the same case the scheduler calls due`() {
        // The two owners of freshness must agree, or a first launch would show
        // an empty list and then silently fill it a moment later.
        assertTrue(ServerListScreenPolicy.shouldLoadOnOpen(0))
        assertTrue(ServerListUpdateScheduler.isDue(0L, 12, 1_000L))
    }
}
