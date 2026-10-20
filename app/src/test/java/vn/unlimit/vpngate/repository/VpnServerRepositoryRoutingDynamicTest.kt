package vn.unlimit.vpngate.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.data.remote.CollectorPath
import vn.unlimit.vpngate.data.remote.CollectorRoutingInput
import java.io.File

/**
 * v1.0.41 (§1.6.34) — T-NEW-36..38.
 *
 * Covers the two halves of the routing change:
 *  - **T-NEW-36** is a source-shape test: `productionRouting()` must feed the
 *    health wrapper ([GlobalVpnTracker.isTunnelUsableForRouting]) instead of
 *    the weaker `isConnected && network != null` pair, and must not OR the
 *    live tunnel state back into `preferVpn` (the v1.0.27 FIX-3a regression).
 *  - **T-NEW-37** is behavioural: `resolvePrimary()` is called directly with
 *    all four permutations, because the gate itself is the change under test
 *    and it is a pure function of its input.
 *  - **T-NEW-38** pins the two things this phase must NOT have touched: the
 *    `fetchOnChosenPath` fallback rung (v1.0.27) and the pure
 *    `CollectorRouting.choose` expression (still unused, still three-way AND).
 *
 * Why source-shape for 36 and 38: `productionRouting()` reads process-wide
 * Android state (`GlobalVpnTracker`) and `fetchOnChosenPath` is exercised
 * end-to-end by `VpnServerRepositoryR4bTest` / `VpnServerRepositoryRoutingTest`,
 * which are unchanged. Reading the source pins the wiring without duplicating
 * those suites or class-loading the Android-dependent tracker.
 */
class VpnServerRepositoryRoutingDynamicTest {

    private fun sourceOf(relativePath: String): String {
        for (path in listOf(relativePath, "../$relativePath", "app/$relativePath")) {
            val file = File(path)
            if (file.isFile) return file.readText(Charsets.UTF_8)
        }
        error("source file not found: $relativePath (cwd=${File(".").absolutePath})")
    }

    @Test
    fun `T-NEW-36 productionRouting feeds the usability wrapper, not the raw connected flag`() {
        val source = sourceOf("src/main/java/vn/unlimit/vpngate/repository/VpnServerRepository.kt")
        val start = source.indexOf("internal fun productionRouting(")
        assertTrue("productionRouting() must exist", start >= 0)
        val end = source.indexOf("internal fun resolvePrimary(", start)
        assertTrue("resolvePrimary() must follow productionRouting()", end > start)
        val body = source.substring(start, end)

        assertTrue(
            "tunnelUsable must come from the grace-period wrapper: $body",
            body.contains("GlobalVpnTracker.isTunnelUsableForRouting()"),
        )
        assertTrue(
            "the wrapper's value must be what is passed as tunnelUsable: $body",
            body.contains("tunnelUsable = usable"),
        )
        assertFalse(
            "the weaker isConnected check must no longer feed the routing input: $body",
            body.contains("isConnected"),
        )
        assertFalse(
            "preferVpn must not OR the live tunnel state (v1.0.27 FIX-3a): $body",
            body.contains("||"),
        )
    }

    @Test
    fun `T-NEW-37 resolvePrimary requires both the opt-in and a usable tunnel`() {
        // Opt-in ON, tunnel healthy -> the VPN rung (unchanged).
        assertEquals(
            CollectorPath.VPN,
            VpnServerRepository.resolvePrimary(CollectorRoutingInput(true, true, true)),
        )
        // Opt-in ON, tunnel dead -> OPEN directly. This is the v1.0.41 change:
        // pre-v1.0.41 this returned VPN and paid a timeout before falling back.
        assertEquals(
            CollectorPath.OPEN,
            VpnServerRepository.resolvePrimary(CollectorRoutingInput(true, false, true)),
        )
        assertEquals(
            CollectorPath.OPEN,
            VpnServerRepository.resolvePrimary(CollectorRoutingInput(true, false, false)),
        )
        // Opt-in OFF -> OPEN regardless of the tunnel (unchanged, the default).
        assertEquals(
            CollectorPath.OPEN,
            VpnServerRepository.resolvePrimary(CollectorRoutingInput(false, true, true)),
        )
        assertEquals(
            CollectorPath.OPEN,
            VpnServerRepository.resolvePrimary(CollectorRoutingInput(false, false, false)),
        )
    }

    @Test
    fun `T-NEW-38 the fallback rung and the pure choose decision are unchanged`() {
        val repository =
            sourceOf("src/main/java/vn/unlimit/vpngate/repository/VpnServerRepository.kt")
        assertTrue(
            "fetchOnChosenPath must still compute an opposite rung to fall back to",
            repository.contains("val canFallback = when (fallback)"),
        )
        assertTrue(
            "the VPN fallback must still require a bound fetcher",
            repository.contains("CollectorPath.VPN -> boundFetcher != null"),
        )
        assertTrue(
            "the OPEN fallback must stay unconditional",
            repository.contains("CollectorPath.OPEN -> true"),
        )

        val fetcher = sourceOf("src/main/java/vn/unlimit/vpngate/data/remote/HttpFetcher.kt")
        assertTrue(
            "CollectorRouting.choose must keep its three-way AND",
            fetcher.contains("if (input.preferVpn && input.tunnelUsable && input.hasBoundNetwork)"),
        )
    }
}
