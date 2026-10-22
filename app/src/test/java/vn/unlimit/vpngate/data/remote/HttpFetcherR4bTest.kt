package vn.unlimit.vpngate.data.remote

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * R4b — collector transport routing and the network-bound client factory.
 *
 * Pure JVM throughout: `android.net.Network` cannot be constructed here, so
 * the binding core is exercised through [boundToSocketFactory]. The `Network`
 * overload [boundToNetwork] is only a one-line delegation to it.
 *
 * v1.0.52 (§1.6.44) TASK A replaced this file's routing half. It used to pin
 * `CollectorRouting.choose(CollectorRoutingInput(preferVpn, tunnelUsable,
 * hasBoundNetwork))` — a three-way AND whose first term was the user's manual
 * "prefer the VPN for the collector" switch. That switch, its `preferVpn`
 * input and the `CollectorRouting` object were all removed: the ladder is now
 * derived from measurements only, in `VpnServerRepository.routeLadder`, and
 * the in-process `PathMemory` supplies the evidence that used to come from the
 * user. The behavioural ladder is pinned by `VpnServerRepositoryLadderTest`;
 * what is pinned *here* is the shape of the input and the fact that no routing
 * decision is left in the fetcher layer at all.
 */
class HttpFetcherR4bTest {

    // ── the routing input (post-v1.0.52) ───────────────────────────────

    @Test
    fun `the routing input carries only the two measurements`() {
        // Constructed with NAMED arguments and no defaults: if a third field
        // (e.g. `preferVpn`) were reintroduced without a default this would
        // stop compiling, which is the strongest form of this assertion. The
        // two reads below then prove both surviving fields are wired.
        val input = CollectorRoutingInput(tunnelUsable = true, hasBoundNetwork = false)

        assertTrue(input.tunnelUsable)
        assertFalse(input.hasBoundNetwork)
    }

    @Test
    fun `the routing input exposes exactly tunnelUsable and hasBoundNetwork`() {
        // The key space of the routing decision must be measurements only. A
        // preference field here is exactly the regression v1.0.52 removes, and
        // it would be invisible from a value-equality test because every
        // permutation would still compile.
        val names = CollectorRoutingInput::class.java.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .filterNot { it.startsWith("$") }
            .sorted()

        assertEquals(listOf("hasBoundNetwork", "tunnelUsable"), names)
    }

    @Test
    fun `the standalone routing decision object is gone`() {
        // `CollectorRouting` was a pure `object { fun choose(...) }` living in
        // the fetcher layer. Its removal is the point of TASK A: one ladder,
        // in one place, with the path memory available to it.
        val removed = try {
            Class.forName("vn.unlimit.vpngate.data.remote.CollectorRouting")
            false
        } catch (e: ClassNotFoundException) {
            true
        }
        assertTrue(
            "CollectorRouting must not exist any more — routeLadder() owns the decision",
            removed,
        )
    }

    @Test
    fun `the fetcher layer no longer mentions a routing preference`() {
        // Source-shape because the deleted symbols cannot be referenced from a
        // test that must still compile. Normalised to LF: the repo checks out
        // CRLF on Windows and a multi-line marker would never match otherwise.
        val source = File("src/main/java/vn/unlimit/vpngate/data/remote/HttpFetcher.kt")
            .takeIf { it.isFile }
            ?: File("../app/src/main/java/vn/unlimit/vpngate/data/remote/HttpFetcher.kt")
        assertTrue("HttpFetcher.kt must be readable from the test cwd", source.isFile)

        val text = source.readText(Charsets.UTF_8).replace("\r\n", "\n")

        assertFalse(
            "HttpFetcher.kt must not reference the removed preferVpn input",
            text.contains("preferVpn"),
        )
        assertFalse(
            "HttpFetcher.kt must not reference the removed CollectorRouting object",
            text.contains("CollectorRouting.choose") || text.contains("object CollectorRouting"),
        )
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
