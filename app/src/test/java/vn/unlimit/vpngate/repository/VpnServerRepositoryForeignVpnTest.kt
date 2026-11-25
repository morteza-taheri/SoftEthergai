package vn.unlimit.vpngate.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import vn.unlimit.vpngate.data.remote.CollectorPath
import vn.unlimit.vpngate.data.remote.CollectorRoutingInput
import vn.unlimit.vpngate.data.remote.HttpFetcher
import vn.unlimit.vpngate.data.remote.RedirectPolicy
import java.util.concurrent.CopyOnWriteArrayList

/**
 * v1.0.87 — the collector must be able to use a VPN owned by **another app**.
 *
 * Before this rung existed the ladder only ever chose PHYSICAL (bound to a
 * NOT_VPN network) or the app's own TUNNEL, so a third-party VPN was bypassed
 * by construction and the list failed to update on exactly the networks the
 * user had already routed around.
 */
class VpnServerRepositoryForeignVpnTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val fetchLogs = CopyOnWriteArrayList<String>()

    private class TokenFetcher : HttpFetcher {
        override suspend fun get(url: String, redirectPolicy: RedirectPolicy?): Nothing? = null
    }

    private fun ladder(input: CollectorRoutingInput) =
        VpnServerRepository.routeLadder(input)

    @Test
    fun `a foreign vpn becomes the network rung when the own tunnel is not usable`() {
        val ladder = ladder(
            CollectorRoutingInput(
                tunnelUsable = false,
                hasBoundNetwork = false,
                foreignVpnAvailable = true,
            ),
        )
        assertEquals(listOf(CollectorPath.FOREIGN_VPN, CollectorPath.EMERGENCY), ladder)
    }

    @Test
    fun `the own tunnel still wins over a foreign one`() {
        val ladder = ladder(
            CollectorRoutingInput(
                tunnelUsable = true,
                hasBoundNetwork = true,
                foreignVpnAvailable = true,
            ),
        )
        assertEquals(listOf(CollectorPath.TUNNEL, CollectorPath.EMERGENCY), ladder)
    }

    @Test
    fun `no vpn at all keeps the physical rung`() {
        val ladder = ladder(
            CollectorRoutingInput(
                tunnelUsable = false,
                hasBoundNetwork = false,
                foreignVpnAvailable = false,
            ),
        )
        assertEquals(listOf(CollectorPath.PHYSICAL, CollectorPath.EMERGENCY), ladder)
    }

    @Test
    fun `a foreign vpn has no physical fallback behind it`() {
        // Same policy as REQ-ROUTE-001 for the app's own tunnel: a user who
        // turned a VPN on did not ask for the request to leave outside it.
        val ladder = ladder(
            CollectorRoutingInput(
                tunnelUsable = false,
                hasBoundNetwork = false,
                foreignVpnAvailable = true,
            ),
        )
        assertTrue(CollectorPath.PHYSICAL !in ladder)
    }

    @Test
    fun `the foreign rung reports unavailable instead of falling back`() = runBlocking {
        val repository = VpnServerRepository(
            fetcher = TokenFetcher(),
            cacheDir = tempFolder.newFolder(),
            fetchLog = { fetchLogs.add(it) },
        )
        val result = repository.fetchOnChosenPath<String>(
            sourceTag = "html",
            path = CollectorPath.FOREIGN_VPN,
            boundFetcher = null,
            succeeded = { it.isNotEmpty() },
        ) { "should never be fetched" }

        assertNull(result)
        assertTrue(
            "expected an explicit 'no fallback' line; logs=$fetchLogs",
            fetchLogs.any { it.contains("foreign-vpn unavailable") },
        )
    }
}
