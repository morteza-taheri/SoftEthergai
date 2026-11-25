package vn.unlimit.vpngate.data.remote

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The cleartext rule that replaced the static `network_security_config`
 * allowlist. Two things must stay true at once:
 *
 *  - a mirror the official directory published **today** must be reachable,
 *    even though it is not in any built-in list;
 *  - nothing else may be fetched over cleartext, and `vpngate.net` in
 *    particular must never be.
 */
class CleartextMirrorPolicyTest {

    @Before
    fun setUp() = CleartextMirrorPolicy.resetToBootstrap()

    @After
    fun tearDown() = CleartextMirrorPolicy.resetToBootstrap()

    @Test
    fun `canonical site is never cleartext`() {
        assertFalse(CleartextMirrorPolicy.isCleartextPermitted("www.vpngate.net"))
        assertFalse(CleartextMirrorPolicy.isCleartextPermitted("vpngate.net"))
        assertFalse(CleartextMirrorPolicy.isCleartextPermitted("foo.vpngate.net"))
        assertFalse(CleartextMirrorPolicy.isCleartextPermitted("www.tsukuba.ac.jp"))
    }

    @Test
    fun `bootstrap mirrors are permitted from a cold start`() {
        assertTrue(CleartextMirrorPolicy.isCleartextPermitted("150.40.105.23"))
        assertTrue(CleartextMirrorPolicy.isCleartextPermitted("121.186.186.97"))
    }

    @Test
    fun `a mirror discovered today is permitted even though it is in no built-in list`() {
        // The addresses the directory actually published on 2026-11-24.
        val live = listOf(
            "http://150.40.105.20:16086/en/",
            "http://150.40.105.5:32536/en/",
            "http://133.18.161.231:45457/en/",
        )
        // Before registration only the ones inside a known hosting range pass.
        assertTrue(CleartextMirrorPolicy.isCleartextPermitted("150.40.105.20"))
        assertTrue(CleartextMirrorPolicy.isCleartextPermitted("150.40.105.5"))
        assertFalse(CleartextMirrorPolicy.isCleartextPermitted("133.18.161.231"))

        CleartextMirrorPolicy.registerAll(live)

        assertTrue(CleartextMirrorPolicy.isCleartextPermitted("150.40.105.20"))
        assertTrue(CleartextMirrorPolicy.isCleartextPermitted("133.18.161.231"))
    }

    @Test
    fun `a new address inside a mirror hosting range works before discovery`() {
        assertTrue(CleartextMirrorPolicy.isCleartextPermitted("150.40.105.77"))
        assertTrue(CleartextMirrorPolicy.isCleartextPermitted("14.40.125.1"))
    }

    @Test
    fun `an address outside the mirror ranges is refused`() {
        assertFalse(CleartextMirrorPolicy.isCleartextPermitted("150.40.106.5"))
        assertFalse(CleartextMirrorPolicy.isCleartextPermitted("8.8.8.8"))
        assertFalse(CleartextMirrorPolicy.isCleartextPermitted("example.com"))
    }

    @Test
    fun `non-global addresses are refused even inside a mirror range`() {
        assertFalse(CleartextMirrorPolicy.isCleartextPermitted("127.0.0.1"))
        assertFalse(CleartextMirrorPolicy.isCleartextPermitted("10.0.0.1"))
        assertFalse(CleartextMirrorPolicy.isCleartextPermitted("192.168.1.1"))
        assertFalse(CleartextMirrorPolicy.isCleartextPermitted("169.254.1.1"))
    }

    @Test
    fun `blank and malformed hosts are refused`() {
        assertFalse(CleartextMirrorPolicy.isCleartextPermitted(""))
        assertFalse(CleartextMirrorPolicy.isCleartextPermitted("   "))
        assertFalse(CleartextMirrorPolicy.isCleartextPermitted("010.0.0.1"))
    }

    @Test
    fun `registration accepts urls, authorities and bare hosts`() {
        CleartextMirrorPolicy.register("http://203.0.113.9:8080/en/")
        CleartextMirrorPolicy.register("198.51.100.4:9999")
        CleartextMirrorPolicy.register("8.8.4.4")

        // All three are outside every mirror range, so only the registry can
        // have made them permitted.
        assertTrue(CleartextMirrorPolicy.isCleartextPermitted("203.0.113.9"))
        assertTrue(CleartextMirrorPolicy.isCleartextPermitted("198.51.100.4"))
        assertTrue(CleartextMirrorPolicy.isCleartextPermitted("8.8.4.4"))
    }

    @Test
    fun `registering the canonical site does not open cleartext for it`() {
        CleartextMirrorPolicy.register("http://www.vpngate.net/en/")
        assertFalse(CleartextMirrorPolicy.isCleartextPermitted("www.vpngate.net"))
    }

    @Test
    fun `unregistered mirrors are forgotten on reset`() {
        CleartextMirrorPolicy.register("8.8.4.4")
        assertTrue(CleartextMirrorPolicy.isCleartextPermitted("8.8.4.4"))
        CleartextMirrorPolicy.resetToBootstrap()
        assertFalse(CleartextMirrorPolicy.isCleartextPermitted("8.8.4.4"))
        assertTrue(CleartextMirrorPolicy.isCleartextPermitted("150.40.105.23"))
    }

    // ---------------------------------------------------------------
    // v1.0.88 — ordering, not membership.
    //
    // Device evidence (2026-11-25): eight of twelve mirrors in one refresh
    // were cached addresses the app had stored itself, and every one of them
    // was refused with CLEARTEXT-BLOCKED, because the registry was fed only
    // after the directory race had already fired. The mirror pool therefore
    // had four usable slots out of twelve. These tests pin the order: a
    // mirror set becomes fetchable the moment it is handed to the source
    // layer, not later.
    // ---------------------------------------------------------------

    @Test
    fun `a cached mirror outside the known ranges is blocked until registered`() {
        // 122.133.207.17 is a real address from the device's mirror cache and
        // is deliberately outside both hosting /24s.
        assertFalse(CleartextMirrorPolicy.isCleartextPermitted("122.133.207.17"))

        val cached = VpnGateSourceCandidates.cachedMirrors(
            listOf("http://122.133.207.17:47201"),
        )
        assertEquals(1, cached.size)

        VpnGateSourceCandidates.registerForCleartext(cached)

        assertTrue(CleartextMirrorPolicy.isCleartextPermitted("122.133.207.17"))
    }

    @Test
    fun `registerForCleartext accepts the whole cached set in one call`() {
        val urls = listOf(
            "http://122.133.207.17:47201",
            "http://14.7.148.12:37147",
            "http://133.18.161.231:45457",
            "http://153.218.21.241:49818",
        )
        val cached = VpnGateSourceCandidates.cachedMirrors(urls)
        assertEquals(4, cached.size)

        VpnGateSourceCandidates.registerForCleartext(cached)

        urls.forEach { url ->
            val host = java.net.URI(url).host
            assertTrue("$host must be fetchable after registration", CleartextMirrorPolicy.isCleartextPermitted(host))
        }
    }

    @Test
    fun `registerForCleartext never widens access to the canonical site`() {
        val mirrors = VpnGateSourceCandidates.bootstrapMirrors() +
            VpnGateSourceCandidates.cachedMirrors(listOf("http://122.133.207.17:47201"))
        VpnGateSourceCandidates.registerForCleartext(mirrors)
        assertFalse(CleartextMirrorPolicy.isCleartextPermitted("www.vpngate.net"))
    }

    @Test
    fun `junk in the cache is dropped rather than registered`() {
        val cached = VpnGateSourceCandidates.cachedMirrors(
            listOf("not a url", "http://", "http://10.0.0.5:8080", "http://127.0.0.1:9000"),
        )
        assertTrue("private and malformed entries must not survive: $cached", cached.isEmpty())
        VpnGateSourceCandidates.registerForCleartext(cached)
        assertFalse(CleartextMirrorPolicy.isCleartextPermitted("10.0.0.5"))
        assertFalse(CleartextMirrorPolicy.isCleartextPermitted("127.0.0.1"))
    }
}
