package vn.unlimit.vpngate.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Contract for [OpenVpnConfigTransform].
 *
 * VPN Gate publishes one OpenVPN profile per server, over TCP; the UDP and
 * "connect by domain" variants are derived from it. The derivations used to
 * dereference the profile with `!!`, so a server that carries a port but no
 * profile — the state a partially collected list produces — crashed the connect
 * path. These tests pin down that every combination of missing input is handled,
 * and that the transformations themselves are unchanged.
 */
class OpenVpnConfigTransformTest {

    private val tcpProfile = """
        client
        dev tun
        proto tcp
        remote 219.126.84.5 443
        cipher AES-256-CBC
    """.trimIndent()

    // ------------------------------------------------------------ forUdp

    @Test
    fun `the udp profile switches the protocol and the port`() {
        val udp = OpenVpnConfigTransform.forUdp(
            config = tcpProfile,
            tcpPort = 443,
            udpPort = 1195,
            ip = "219.126.84.5",
        )

        assertEquals(
            tcpProfile
                .replace("proto tcp", "proto udp")
                .replace("remote 219.126.84.5 443", "remote 219.126.84.5 1195"),
            udp,
        )
    }

    @Test
    fun `a missing profile yields no udp profile`() {
        assertNull(OpenVpnConfigTransform.forUdp(null, 443, 1195, "219.126.84.5"))
    }

    @Test
    fun `a server with a port but no profile does not crash the udp derivation`() {
        // The exact shape that used to throw: tcpPort > 0, openVpnConfigData
        // null. A transformation must report "no profile", not blow up.
        assertNull(OpenVpnConfigTransform.forUdp(null, 443, 1195, "219.126.84.5"))
    }

    @Test
    fun `a server without a tcp port keeps the stored profile unchanged`() {
        val result = OpenVpnConfigTransform.forUdp(
            config = tcpProfile,
            tcpPort = 0,
            udpPort = 1195,
            ip = "219.126.84.5",
        )

        assertEquals(tcpProfile, result)
    }

    @Test
    fun `a server without an ip keeps the stored profile unchanged`() {
        val result = OpenVpnConfigTransform.forUdp(
            config = tcpProfile,
            tcpPort = 443,
            udpPort = 1195,
            ip = null,
        )

        assertEquals(tcpProfile, result)
    }

    // --------------------------------------------------------- forDomain

    @Test
    fun `the domain profile replaces the ip with the opengw hostname`() {
        val result = OpenVpnConfigTransform.forDomain(
            config = tcpProfile,
            ip = "219.126.84.5",
            hostName = "Public-1",
            useDomain = true,
        )

        assertEquals(tcpProfile.replace("219.126.84.5", "Public-1.opengw.net"), result)
    }

    @Test
    fun `without the domain setting the profile is untouched`() {
        val result = OpenVpnConfigTransform.forDomain(
            config = tcpProfile,
            ip = "219.126.84.5",
            hostName = "Public-1",
            useDomain = false,
        )

        assertEquals(tcpProfile, result)
    }

    @Test
    fun `a missing profile yields no domain profile`() {
        assertNull(
            OpenVpnConfigTransform.forDomain(null, "219.126.84.5", "Public-1", useDomain = true),
        )
    }

    @Test
    fun `a missing ip or hostname keeps the profile instead of throwing`() {
        assertEquals(
            tcpProfile,
            OpenVpnConfigTransform.forDomain(tcpProfile, ip = null, hostName = "Public-1", useDomain = true),
        )
        assertEquals(
            tcpProfile,
            OpenVpnConfigTransform.forDomain(tcpProfile, ip = "219.126.84.5", hostName = null, useDomain = true),
        )
    }

    @Test
    fun `blank ip and hostname values are treated as missing`() {
        val result = OpenVpnConfigTransform.forDomain(
            config = tcpProfile,
            ip = "  ",
            hostName = "",
            useDomain = true,
        )

        assertEquals(tcpProfile, result)
    }

    // ---------------------------------------------------------- combined

    @Test
    fun `udp and domain combine in the order the connect path applies them`() {
        val udp = OpenVpnConfigTransform.forUdp(tcpProfile, 443, 1195, "219.126.84.5")!!
        val combined = OpenVpnConfigTransform.forDomain(udp, "219.126.84.5", "Public-1", useDomain = true)!!

        assertEquals(
            """
                client
                dev tun
                proto udp
                remote Public-1.opengw.net 1195
                cipher AES-256-CBC
            """.trimIndent(),
            combined,
        )
    }
}
