package vn.unlimit.vpngate.automode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for [ServerQuarantine].
 *
 * The reported Auto Mode symptom is a stable loop: connect, drop after a few
 * seconds, reconnect to the same server, drop again. `AutoModeRecoveryPolicy`
 * cannot see it because every cycle contains a successful connection, which
 * resets its counter. These tests pin down the policy that does see it, with a
 * fake clock so nothing sleeps.
 */
class ServerQuarantineTest {

    private var now = 1_000_000L
    private fun quarantine() = ServerQuarantine(clock = { now })

    @Test
    fun `one rapid drop is tolerated`() {
        val q = quarantine()
        assertEquals(0L, q.onTunnelLost("a", uptimeMs = 10_000))
        assertFalse(q.isQuarantined("a"))
        assertEquals(1, q.strikesFor("a"))
    }

    @Test
    fun `two rapid drops quarantine the server`() {
        val q = quarantine()
        q.onTunnelLost("a", 10_000)
        val cooldown = q.onTunnelLost("a", 12_000)
        assertEquals(ServerQuarantine.BASE_COOLDOWN_MS, cooldown)
        assertTrue(q.isQuarantined("a"))
    }

    @Test
    fun `a stable session forgives earlier strikes`() {
        val q = quarantine()
        q.onTunnelLost("a", 5_000)
        assertEquals(0L, q.onTunnelLost("a", ServerQuarantine.STABLE_UPTIME_MS))
        assertFalse(q.isQuarantined("a"))
        assertEquals(0, q.strikesFor("a"))
    }

    @Test
    fun `the cooldown expires and the server becomes eligible again`() {
        val q = quarantine()
        q.onTunnelLost("a", 1_000)
        q.onTunnelLost("a", 1_000)
        assertTrue(q.isQuarantined("a"))
        now += ServerQuarantine.BASE_COOLDOWN_MS + 1
        assertFalse(q.isQuarantined("a"))
    }

    @Test
    fun `strikes survive the cooldown so the next flap costs longer`() {
        val q = quarantine()
        q.onTunnelLost("a", 1_000)
        q.onTunnelLost("a", 1_000)
        now += ServerQuarantine.BASE_COOLDOWN_MS + 1
        assertFalse(q.isQuarantined("a"))

        val second = q.onTunnelLost("a", 1_000)
        assertEquals(ServerQuarantine.BASE_COOLDOWN_MS * 2, second)
        assertTrue(q.isQuarantined("a"))
    }

    @Test
    fun `the cooldown is capped`() {
        val q = quarantine()
        var last = 0L
        repeat(12) {
            last = q.onTunnelLost("a", 1_000)
            now += last + 1
        }
        assertEquals(ServerQuarantine.MAX_COOLDOWN_MS, last)
    }

    @Test
    fun `servers are independent`() {
        val q = quarantine()
        q.onTunnelLost("a", 1_000)
        q.onTunnelLost("a", 1_000)
        assertTrue(q.isQuarantined("a"))
        assertFalse(q.isQuarantined("b"))
        assertEquals(0, q.strikesFor("b"))
    }

    @Test
    fun `a blank key is ignored`() {
        val q = quarantine()
        assertEquals(0L, q.onTunnelLost("", 1_000))
        assertFalse(q.isQuarantined(""))
        assertEquals(0, q.size)
    }

    @Test
    fun `memory is bounded`() {
        val q = quarantine()
        repeat(500) { i ->
            q.onTunnelLost("server-$i", 1_000)
            now += 1
        }
        assertTrue("size=${q.size}", q.size <= 128)
    }

    @Test
    fun `clear forgets everything`() {
        val q = quarantine()
        q.onTunnelLost("a", 1_000)
        q.onTunnelLost("a", 1_000)
        q.clear()
        assertFalse(q.isQuarantined("a"))
        assertEquals(0, q.size)
    }
}
