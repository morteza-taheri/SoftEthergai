package vn.unlimit.vpngate.automode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.61 (§1.6.59) PART D — one candidate per IPv4.
 *
 * The table is keyed by host name and the run's per-run guard by `ip|hostname`, so
 * two rows sharing an IPv4 under different names are two identities and both would
 * be dialled in one run. The current device database has no such pair (measured:
 * 261 rows, 261 distinct addresses), so this is defensive — which is exactly why the
 * ordering of preference has to be pinned by tests rather than trusted.
 */
class AutoModeEngineDedupeTest {

    private fun candidate(
        host: String,
        ip: String?,
        score: Int = 1000,
        seTcp: Int = 443,
        seUdp: Int = 0,
        seUdpSupported: Boolean = false,
        sstp: Boolean = false,
        l2tp: Boolean = false,
        hasConfig: Boolean = false,
    ) = AutoModeCandidate(
        hostname = host,
        ip = ip,
        speed = 10_000_000L,
        ping = 50,
        sessions = 100,
        score = score,
        sources = 2,
        seTcpPort = seTcp,
        seUdpPort = seUdp,
        seUdpSupported = seUdpSupported,
        openVpnTcpPort = if (hasConfig) 1194 else 0,
        openVpnUdpPort = 0,
        l2tpSupported = l2tp,
        sstpSupported = sstp,
        openVpnConfigAvailable = hasConfig,
    )

    // ---------------------------------------------------------------- 1

    @Test
    fun `a row with a usable config wins while openvpn is enabled`() {
        // The config-less row is otherwise richer (SSTP + SE/UDP), so only rule 1
        // can put the config row in front.
        val noConfig = candidate("rich.example", "203.0.113.9", seUdp = 500, seUdpSupported = true, sstp = true)
        val withConfig = candidate("plain.example", "203.0.113.9", hasConfig = true)

        val kept = AutoModeEngine.dedupeByIp(listOf(noConfig, withConfig), openVpnEnabled = true)

        assertEquals(1, kept.size)
        assertSame(withConfig, kept.single())
    }

    // ---------------------------------------------------------------- 2

    @Test
    fun `without openvpn the richer row wins`() {
        val noConfig = candidate("rich.example", "203.0.113.9", seUdp = 500, seUdpSupported = true, sstp = true)
        val withConfig = candidate("plain.example", "203.0.113.9", hasConfig = true)

        val kept = AutoModeEngine.dedupeByIp(listOf(noConfig, withConfig), openVpnEnabled = false)

        assertEquals(1, kept.size)
        assertSame(
            "with OpenVPN off, a profile buys nothing and the richer transports win",
            noConfig,
            kept.single(),
        )
    }

    // ---------------------------------------------------------------- 3

    @Test
    fun `equal rows fall back to the score and then to the host name`() {
        val low = candidate("aaa.example", "203.0.113.10", score = 10)
        val high = candidate("zzz.example", "203.0.113.10", score = 20)
        assertEquals(
            "the higher score wins",
            high,
            AutoModeEngine.dedupeByIp(listOf(low, high)).single(),
        )

        val bbb = candidate("bbb.example", "203.0.113.11", score = 10)
        val aaa = candidate("aaa.example", "203.0.113.11", score = 10)
        assertEquals(
            "a full tie goes to the lexicographically smaller host name",
            "aaa.example",
            AutoModeEngine.dedupeByIp(listOf(bbb, aaa)).single().hostname,
        )
    }

    // ---------------------------------------------------------------- 4

    @Test
    fun `three rows for one address keep exactly one`() {
        val a = candidate("c.example", "203.0.113.12", score = 5)
        val b = candidate("b.example", "203.0.113.12", score = 50)
        val c = candidate("a.example", "203.0.113.12", score = 9)

        val kept = AutoModeEngine.dedupeByIp(listOf(a, b, c))

        assertEquals(1, kept.size)
        assertSame(b, kept.single())
    }

    // ---------------------------------------------------------------- 5

    @Test
    fun `a list without duplicates is returned unchanged`() {
        val list = listOf(
            candidate("one.example", "203.0.113.1"),
            candidate("two.example", "203.0.113.2"),
            candidate("three.example", "203.0.113.3"),
        )

        val kept = AutoModeEngine.dedupeByIp(list)

        assertEquals(list.size, kept.size)
        list.forEachIndexed { i, c -> assertSame("position $i must be preserved", c, kept[i]) }
    }

    // ---------------------------------------------------------------- 6

    @Test
    fun `the survivor keeps the position of the first row with that address`() {
        val x = candidate("x.example", "203.0.113.20")
        val first = candidate("first.example", "203.0.113.21", score = 1)
        val y = candidate("y.example", "203.0.113.22")
        val second = candidate("second.example", "203.0.113.21", score = 99)
        val z = candidate("z.example", "203.0.113.23")

        val kept = AutoModeEngine.dedupeByIp(listOf(x, first, y, second, z))

        assertEquals(
            "the duplicated address occupies one slot, where it first appeared",
            listOf("x.example", "second.example", "y.example", "z.example"),
            kept.map { it.hostname },
        )
    }

    // ---------------------------------------------------------------- 7

    @Test
    fun `rows without an address are all kept`() {
        val a = candidate("a.example", null)
        val b = candidate("b.example", "   ")
        val c = candidate("c.example", "203.0.113.30")

        val kept = AutoModeEngine.dedupeByIp(listOf(a, b, c))

        assertEquals("an empty address is not an identity", 3, kept.size)
        assertTrue(kept.containsAll(listOf(a, b, c)))
    }

    // ---------------------------------------------------------------- 8

    @Test
    fun `deduplication is deterministic regardless of input order`() {
        val winner = candidate("zzz.example", "203.0.113.40", score = 500)
        val loser = candidate("aaa.example", "203.0.113.40", score = 1)

        val forward = AutoModeEngine.dedupeByIp(listOf(loser, winner)).single()
        val backward = AutoModeEngine.dedupeByIp(listOf(winner, loser)).single()

        assertSame("the same row must win in both orders", winner, forward)
        assertSame(winner, backward)
    }
}
