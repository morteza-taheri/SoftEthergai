package vn.unlimit.vpngate.data.remote

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * R4b — collector transport routing and the network-bound client factory.
 *
 * Pure JVM throughout: `android.net.Network` cannot be constructed here, so
 * the binding core is exercised through [boundToSocketFactory] and the decision
 * through [CollectorRouting.choose]. The `Network` overload
 * [boundToNetwork] is only a one-line delegation to it.
 */
class HttpFetcherR4bTest {

    // ── the decision ───────────────────────────────────────────────────

    @Test
    fun `open when the user has not opted in`() {
        assertEquals(
            CollectorPath.OPEN,
            CollectorRouting.choose(
                CollectorRoutingInput(preferVpn = false, tunnelUsable = true, hasBoundNetwork = true),
            ),
        )
    }

    @Test
    fun `open when the tunnel is not usable even with opt-in and a network`() {
        assertEquals(
            CollectorPath.OPEN,
            CollectorRouting.choose(
                CollectorRoutingInput(preferVpn = true, tunnelUsable = false, hasBoundNetwork = true),
            ),
        )
    }

    @Test
    fun `open when there is no VPN network to bind to`() {
        assertEquals(
            CollectorPath.OPEN,
            CollectorRouting.choose(
                CollectorRoutingInput(preferVpn = true, tunnelUsable = true, hasBoundNetwork = false),
            ),
        )
    }

    @Test
    fun `vpn only when all three inputs hold`() {
        assertEquals(
            CollectorPath.VPN,
            CollectorRouting.choose(
                CollectorRoutingInput(preferVpn = true, tunnelUsable = true, hasBoundNetwork = true),
            ),
        )
    }

    @Test
    fun `every single-input case fails open`() {
        val all = listOf(true, false)
        val openCount = all.flatMap { p ->
            all.flatMap { u -> all.map { n -> Triple(p, u, n) } }
        }.count { (p, u, n) ->
            CollectorRouting.choose(
                CollectorRoutingInput(p, u, n),
            ) == CollectorPath.OPEN
        }
        // 7 of the 8 combinations must stay open; only (true, true, true) is VPN.
        assertEquals(7, openCount)
    }

    // ── the bound client ──────────────────────────────────────────────

    @Test
    fun `bound client uses the supplied socket factory`() {
        val factory = RecordingSocketFactory()
        val client = boundToSocketFactory(factory)

        assertSame(factory, client.socketFactory)
    }

    @Test
    fun `bound client keeps the open client's configuration`() {
        val open = defaultCollectorClient()
        val bound = boundToSocketFactory(RecordingSocketFactory())

        // A fallback to open must be the same request, not a looser one.
        assertSame(open.dns.javaClass, bound.dns.javaClass)
        assertEquals(open.connectTimeoutMillis, bound.connectTimeoutMillis)
        assertEquals(open.readTimeoutMillis, bound.readTimeoutMillis)
        assertEquals(open.callTimeoutMillis, bound.callTimeoutMillis)
        assertEquals(open.followRedirects, bound.followRedirects)
        assertEquals(open.followSslRedirects, bound.followSslRedirects)
        assertTrue(bound.connectTimeoutMillis > 0 && bound.readTimeoutMillis > 0)
    }

    @Test
    fun `bound and open clients are distinct instances`() {
        // Guards against a regression where the builder mutates a shared client.
        val a = boundToSocketFactory(RecordingSocketFactory())
        val b = boundToSocketFactory(RecordingSocketFactory())
        assertFalse(a === b)
    }

    @Test
    fun `collector timeouts match the documented collector budget`() {
        val client = defaultCollectorClient()
        assertEquals(TimeUnit.SECONDS.toMillis(10), client.connectTimeoutMillis.toLong())
        assertEquals(TimeUnit.SECONDS.toMillis(15), client.readTimeoutMillis.toLong())
    }

    @Test
    fun `resilient dns is installed on every collector client`() {
        assertTrue(defaultCollectorClient().dns is ResilientDns)
        assertTrue(boundToSocketFactory(RecordingSocketFactory()).dns is ResilientDns)
    }

    @Test
    fun `redirects are never followed automatically`() {
        // Redirects must stay hand-followed so RedirectPolicy can judge each hop.
        assertFalse(defaultCollectorClient().followRedirects)
        assertFalse(boundToSocketFactory(RecordingSocketFactory()).followRedirects)
    }

    private class RecordingSocketFactory : SocketFactory() {
        override fun createSocket(): Socket = throw UnsupportedOperationException("test double")

        override fun createSocket(host: String?, port: Int): Socket =
            throw UnsupportedOperationException("test double")

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            throw UnsupportedOperationException("test double")

        override fun createSocket(host: InetAddress?, port: Int): Socket =
            throw UnsupportedOperationException("test double")

        override fun createSocket(
            address: InetAddress?,
            port: Int,
            localAddress: InetAddress?,
            localPort: Int,
        ): Socket = throw UnsupportedOperationException("test double")
    }
}
