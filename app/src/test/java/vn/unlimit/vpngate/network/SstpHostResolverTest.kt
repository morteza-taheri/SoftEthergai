package vn.unlimit.vpngate.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Contract for [SstpHostResolver]'s pure half.
 *
 * The network calls cannot run in a JVM test, but the two decisions that make
 * them safe can: which answers are acceptable (a poisoned answer is usually a
 * private address) and how a DoH JSON body is read. Both are where a bug would
 * silently reintroduce the failure the resolver exists to remove.
 */
class SstpHostResolverTest {

    private fun address(value: String): InetAddress = InetAddress.getByName(value)

    @Test
    fun `a public ipv4 answer is accepted`() {
        assertTrue(SstpHostResolver.isGlobalAddress(address("219.100.37.222")))
    }

    @Test
    fun `poisoned and non-routable answers are rejected`() {
        // The usual shapes of a hijacked answer, plus the ones a resolver can
        // legitimately return for a broken name.
        assertFalse(SstpHostResolver.isGlobalAddress(address("10.10.34.36")))
        assertFalse(SstpHostResolver.isGlobalAddress(address("192.168.1.1")))
        assertFalse(SstpHostResolver.isGlobalAddress(address("172.16.0.1")))
        assertFalse(SstpHostResolver.isGlobalAddress(address("127.0.0.1")))
        assertFalse(SstpHostResolver.isGlobalAddress(address("169.254.1.1")))
        assertFalse(SstpHostResolver.isGlobalAddress(address("0.0.0.0")))
    }

    @Test
    fun `a literal address is never re-resolved`() {
        assertTrue(SstpHostResolver.isIpLiteral("219.100.37.222"))
        assertTrue(SstpHostResolver.isIpLiteral("2001:db8::1"))
        assertFalse(SstpHostResolver.isIpLiteral("public-vpn-217.opengw.net"))
    }

    @Test
    fun `the first a record is read from a doh answer`() {
        val json = """
            {"Status":0,"TC":false,"RD":true,"RA":true,"AD":false,"CD":false,
             "Question":[{"name":"public-vpn-217.opengw.net.","type":1}],
             "Answer":[{"name":"public-vpn-217.opengw.net.","type":1,"TTL":60,"data":"219.100.37.222"}]}
        """.trimIndent()
        assertEquals("219.100.37.222", SstpHostResolver.parseDnsJson(json))
    }

    @Test
    fun `a cname answer is skipped in favour of the address`() {
        val json = """
            {"Answer":[
              {"name":"a.opengw.net.","type":5,"data":"b.opengw.net."},
              {"name":"b.opengw.net.","type":1,"data":"219.100.37.187"}]}
        """.trimIndent()
        assertEquals("219.100.37.187", SstpHostResolver.parseDnsJson(json))
    }

    @Test
    fun `a poisoned doh answer is not used`() {
        val json = """
            {"Answer":[{"name":"a.opengw.net.","type":1,"data":"10.0.0.1"}]}
        """.trimIndent()
        assertNull(SstpHostResolver.parseDnsJson(json))
    }

    @Test
    fun `malformed bodies yield null instead of throwing`() {
        assertNull(SstpHostResolver.parseDnsJson(""))
        assertNull(SstpHostResolver.parseDnsJson("<html>blocked</html>"))
        assertNull(SstpHostResolver.parseDnsJson("{\"Answer\":["))
        assertNull(SstpHostResolver.parseDnsJson("{\"Status\":2}"))
    }
}
