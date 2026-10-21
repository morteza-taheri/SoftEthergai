package vn.unlimit.vpngate.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.data.remote.CollectorPath
import vn.unlimit.vpngate.data.remote.CollectorRoutingInput

/**
 * v1.0.45 (§1.6.38) — T-NEW-55..57: the automatic route ladder.
 *
 * [VpnServerRepository.routeLadder] is a pure function of the three routing
 * inputs, so the whole ladder can be pinned on a plain JVM without a tracker,
 * a network or a socket. These tests are the specification of the ladder:
 *
 *  - **T-NEW-55** — no opt-in: PHYSICAL is first, TUNNEL is only a fallback.
 *  - **T-NEW-56** — opt-in: TUNNEL is promoted to the front, PHYSICAL becomes
 *    the fallback. The opt-in *reorders*; it never adds or removes a rung.
 *  - **T-NEW-57** — invariants that must hold for every combination.
 *
 * EMERGENCY is always last and is never promoted: it carries no network and is
 * only ever a source switch, handled by the caller.
 */
class VpnServerRepositoryLadderTest {

    private fun ladder(preferVpn: Boolean, tunnelUsable: Boolean, hasBoundNetwork: Boolean) =
        VpnServerRepository.routeLadder(
            CollectorRoutingInput(preferVpn, tunnelUsable, hasBoundNetwork),
        )

    // ── T-NEW-55: no opt-in keeps PHYSICAL first ───────────────────────────

    @Test
    fun `T-NEW-55a no opt-in and no usable tunnel gives PHYSICAL then EMERGENCY`() {
        assertEquals(
            listOf(CollectorPath.PHYSICAL, CollectorPath.EMERGENCY),
            ladder(preferVpn = false, tunnelUsable = false, hasBoundNetwork = false),
        )
    }

    @Test
    fun `T-NEW-55b no opt-in with a usable bound tunnel gives PHYSICAL, TUNNEL, EMERGENCY`() {
        assertEquals(
            listOf(CollectorPath.PHYSICAL, CollectorPath.TUNNEL, CollectorPath.EMERGENCY),
            ladder(preferVpn = false, tunnelUsable = true, hasBoundNetwork = true),
        )
    }

    @Test
    fun `T-NEW-55c a usable-looking tunnel with no bound network adds no rung`() {
        // `tunnelUsable` alone is not enough: without a Network handle there is
        // nothing to bind the request to, so the rung must not be advertised.
        assertEquals(
            listOf(CollectorPath.PHYSICAL, CollectorPath.EMERGENCY),
            ladder(preferVpn = false, tunnelUsable = true, hasBoundNetwork = false),
        )
    }

    // ── T-NEW-56: the opt-in only reorders ─────────────────────────────────

    @Test
    fun `T-NEW-56a opt-in with a usable bound tunnel promotes TUNNEL to the front`() {
        assertEquals(
            listOf(CollectorPath.TUNNEL, CollectorPath.PHYSICAL, CollectorPath.EMERGENCY),
            ladder(preferVpn = true, tunnelUsable = true, hasBoundNetwork = true),
        )
    }

    @Test
    fun `T-NEW-56b opt-in with a dead tunnel leaves PHYSICAL first`() {
        // The v1.0.41 lesson: an opted-in user with a dead tunnel must not pay a
        // timeout on a rung that cannot work.
        assertEquals(
            listOf(CollectorPath.PHYSICAL, CollectorPath.EMERGENCY),
            ladder(preferVpn = true, tunnelUsable = false, hasBoundNetwork = true),
        )
    }

    @Test
    fun `T-NEW-56c opt-in with no bound network leaves PHYSICAL first`() {
        assertEquals(
            listOf(CollectorPath.PHYSICAL, CollectorPath.EMERGENCY),
            ladder(preferVpn = true, tunnelUsable = true, hasBoundNetwork = false),
        )
    }

    // ── T-NEW-57: invariants across every combination ──────────────────────

    @Test
    fun `T-NEW-57 every ladder is PHYSICAL-rooted, EMERGENCY-terminated and duplicate-free`() {
        val all = listOf(true, false)
        for (p in all) for (u in all) for (n in all) {
            val ladder = ladder(p, u, n)
            val label = "ladder(preferVpn=$p, tunnelUsable=$u, hasBoundNetwork=$n)=$ladder"

            assertTrue("$label must contain PHYSICAL", ladder.contains(CollectorPath.PHYSICAL))
            assertTrue(
                "$label must end with EMERGENCY — it is the last rung, never promoted",
                ladder.last() == CollectorPath.EMERGENCY,
            )
            assertTrue(
                "$label must list EMERGENCY exactly once",
                ladder.count { it == CollectorPath.EMERGENCY } == 1,
            )
            assertEquals("$label must not repeat a rung", ladder.size, ladder.toSet().size)
            assertTrue("$label must have 2 or 3 rungs", ladder.size in 2..3)
            assertTrue(
                "$label: TUNNEL may appear only when the tunnel is usable and bound",
                ladder.contains(CollectorPath.TUNNEL) == (u && n),
            )
            assertEquals(
                "$label: resolvePrimary must be the first rung",
                ladder.first(),
                VpnServerRepository.resolvePrimary(CollectorRoutingInput(p, u, n)),
            )
        }
    }

    @Test
    fun `T-NEW-57b the ladder never promotes EMERGENCY above a network rung`() {
        val all = listOf(true, false)
        for (p in all) for (u in all) for (n in all) {
            val ladder = ladder(p, u, n)
            assertEquals(
                "EMERGENCY must be last for (preferVpn=$p, tunnelUsable=$u, hasBoundNetwork=$n)",
                CollectorPath.EMERGENCY,
                ladder.last(),
            )
        }
    }
}
