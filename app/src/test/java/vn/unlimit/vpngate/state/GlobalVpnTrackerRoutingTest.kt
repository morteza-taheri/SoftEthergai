package vn.unlimit.vpngate.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.softether.TunnelHealth

/**
 * v1.0.41 (§1.6.34) — T-NEW-30..35.
 *
 * Pins the pure decision behind `GlobalVpnTracker.isTunnelUsableForRouting()`.
 * The function is deliberately **file-scope** rather than a member so this
 * suite runs on a plain JVM: class-loading `GlobalVpnTracker` needs
 * `Handler(Looper.getMainLooper())`, which the unit-test source set does not
 * provide (this module has JUnit 4 only — no Robolectric, no Mockito).
 *
 * Contract under test (brief STEP 4):
 *   1. not connected                          -> false
 *   2. no OS-bound VPN network                -> false
 *   3. real traffic (`isUsable`)              -> true
 *   4. nothing observed yet, no recent EIO    -> true   (the grace period)
 *   5. traffic observed, then stopped         -> false  (grace is not a bypass)
 *   6. TUN EIO inside [TunnelHealth.EIO_WINDOW_MS] -> false
 *   7. TUN EIO outside the window, no RX      -> true
 *
 * Cases 6 and 7 share T-NEW-35 because they are the two sides of one boundary.
 */
class GlobalVpnTrackerRoutingTest {

    private fun decide(
        isConnected: Boolean = true,
        hasBoundNetwork: Boolean = true,
        isUsable: Boolean = false,
        rxCounter: Long = 0L,
        lastEioAtMs: Long = 0L,
        nowMs: Long = 1_000_000L,
    ): Boolean = routingUsableFromSignals(
        isConnected = isConnected,
        hasBoundNetwork = hasBoundNetwork,
        isUsable = isUsable,
        rxCounter = rxCounter,
        lastEioAtMs = lastEioAtMs,
        nowMs = nowMs,
    )

    @Test
    fun `T-NEW-30 a disconnected tunnel is never routing-usable`() {
        // isUsable=true is passed deliberately: the connection flag must win,
        // because a stale health snapshot must not resurrect a dead tunnel.
        assertFalse(decide(isConnected = false, isUsable = true, rxCounter = 9L))
        assertFalse(decide(isConnected = false, isUsable = false, rxCounter = 0L))
    }

    @Test
    fun `T-NEW-31 connected without an OS-bound network is not routing-usable`() {
        // The VPN rung cannot bind without a Network, so this must fail closed
        // even when the health snapshot claims the tunnel is fine.
        assertFalse(decide(hasBoundNetwork = false, isUsable = true, rxCounter = 9L))
        assertFalse(decide(hasBoundNetwork = false, isUsable = false))
    }

    @Test
    fun `T-NEW-32 a tunnel passing traffic right now is routing-usable`() {
        assertTrue(decide(isUsable = true, rxCounter = 12L, lastEioAtMs = 0L))
    }

    @Test
    fun `T-NEW-33 a freshly connected tunnel gets the grace period`() {
        // CONNECTED, bound, no RX observed yet (counter still 0), no EIO ever:
        // this is the window in which the strict check would wrongly say no.
        assertTrue(decide(isUsable = false, rxCounter = 0L, lastEioAtMs = 0L))
    }

    @Test
    fun `T-NEW-34 a tunnel that carried traffic and stopped is not routing-usable`() {
        // The grace branch requires rxCounter == 0, so once any frame has been
        // received the only way back to `true` is a fresh RX advance. This is
        // what stops the grace period from masking a tunnel that died.
        assertFalse(decide(isUsable = false, rxCounter = 7L, lastEioAtMs = 0L))
    }

    @Test
    fun `T-NEW-35 the EIO window decides the grace branch`() {
        val now = 5_000_000L
        // Inside the window (and exactly on its boundary — the comparison is
        // `<=`, the fail-closed side): not usable.
        assertFalse(
            decide(isUsable = false, rxCounter = 0L, lastEioAtMs = now - 1L, nowMs = now),
        )
        assertFalse(
            decide(
                isUsable = false,
                rxCounter = 0L,
                lastEioAtMs = now - TunnelHealth.EIO_WINDOW_MS,
                nowMs = now,
            ),
        )
        // Just outside the window: the grace branch applies again.
        assertTrue(
            decide(
                isUsable = false,
                rxCounter = 0L,
                lastEioAtMs = now - TunnelHealth.EIO_WINDOW_MS - 1L,
                nowMs = now,
            ),
        )
    }
}
