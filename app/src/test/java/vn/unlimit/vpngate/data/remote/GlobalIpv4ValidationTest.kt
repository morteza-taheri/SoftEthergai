package vn.unlimit.vpngate.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.data.remote.SourceUrl.isAcceptableMirrorAuthority
import vn.unlimit.vpngate.data.remote.SourceUrl.isGlobalIpv4

/**
 * SEC-F19 (deep-review refresh, 2026-11-05): a mirror authority that merely
 * *looks* like an IPv4 literal must not be allowed to point the app at a
 * loopback, RFC1918, link-local, CGNAT, multicast or reserved address. The
 * directory response is unauthenticated HTTP, so its contents are untrusted.
 */
class GlobalIpv4ValidationTest {

    @Test
    fun `official bootstrap mirrors remain acceptable`() {
        listOf(
            "150.40.105.19:80",
            "150.40.105.6:443",
            "150.40.105.11:992",
            "150.40.105.23:5555",
            "194.156.89.134:443",
            "121.186.186.97:443",
        ).forEach {
            assertTrue("official bootstrap mirror must stay allowed: $it", isAcceptableMirrorAuthority(it))
        }
    }

    @Test
    fun `private loopback link-local and reserved ranges are rejected`() {
        listOf(
            "127.0.0.1",          // loopback
            "10.0.0.1",           // RFC1918
            "172.16.0.1",         // RFC1918
            "172.31.255.254",     // RFC1918 upper edge
            "192.168.1.1",        // RFC1918
            "169.254.1.1",        // link-local
            "0.0.0.0",            // this host
            "100.64.0.1",         // CGNAT
            "100.127.255.255",    // CGNAT upper edge
            "224.0.0.1",          // multicast
            "240.0.0.1",          // reserved
            "255.255.255.255",    // broadcast
            "192.0.2.1",          // TEST-NET-1
            "198.51.100.1",       // TEST-NET-2
            "203.0.113.1",        // TEST-NET-3
            "192.88.99.1",        // 6to4 relay anycast
            "198.18.0.1",         // benchmarking
            "192.0.0.1",          // IETF protocol assignments
        ).forEach {
            assertFalse("non-global IPv4 must be rejected: $it", isGlobalIpv4(it))
            assertFalse("non-global IPv4 authority must be rejected: $it", isAcceptableMirrorAuthority("$it:443"))
        }
    }

    @Test
    fun `globally routable literals are accepted`() {
        listOf("1.1.1.1", "8.8.8.8", "150.40.105.19", "203.0.114.1", "223.255.255.254")
            .forEach { assertTrue("public IPv4 must be accepted: $it", isGlobalIpv4(it)) }
    }

    @Test
    fun `malformed or ambiguous literals are rejected`() {
        listOf("1.2.3", "1.2.3.4.5", "256.1.1.1", "01.2.3.4", "010.0.0.1", "1.2.3.-1", "1.2.3.a")
            .forEach { assertFalse("malformed IPv4 must be rejected: $it", isGlobalIpv4(it)) }
    }

    @Test
    fun `opengw net hosts are still allowed regardless of the IP rule`() {
        assertTrue(isAcceptableMirrorAuthority("mirror1.opengw.net:443"))
        assertTrue(isAcceptableMirrorAuthority("opengw.net:443"))
        assertFalse(isAcceptableMirrorAuthority("evil.opengw.net.attacker.com:443"))
    }
}
