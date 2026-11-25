package vn.unlimit.vpngate.utils

import de.blinkt.openvpn.VpnProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.88 — [OpenVpnAuthDefaults], the repair that turns a VPN Gate
 * certificate-only OpenVPN profile into a user/password one.
 *
 * ## What this pins, and why it matters
 *
 * All 63 OpenVPN profiles stored on device 5585fecc (dumped 2026-11-25) carry
 * `#auth-user-pass` **commented out** and none carries an inline
 * `<auth-user-pass>` block. `ConfigParser` therefore produces
 * [VpnProfile.TYPE_CERTIFICATES] for every one of them, the core never emits
 * `>PASSWORD:Need 'Auth'`, `OpenVpnManagementThread` never sends credentials,
 * and the server answers `AUTH_FAILED` before `tun0` exists.
 *
 * A tunnel that never exists cannot carry a server-list refresh, which is the
 * mechanism behind the operator's report that the list would not update under
 * OpenVPN.
 *
 * These cases pin the promotion rules, including the ones that must **not**
 * fire, because over-promoting would emit `auth-user-pass` for a profile whose
 * certificate is the only credential and break it.
 */
class OpenVpnAuthDefaultsTest {

    private fun certOnlyProfile(): VpnProfile = VpnProfile("public-vpn-118").apply {
        mAuthenticationType = VpnProfile.TYPE_CERTIFICATES
        mClientCertFilename = "<cert>\n-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n</cert>"
        mClientKeyFilename = "<key>\n-----BEGIN RSA PRIVATE KEY-----\nMIIB\n-----END RSA PRIVATE KEY-----\n</key>"
    }

    @Test
    fun `certificate-only profile is promoted to userpass-certificates`() {
        val profile = certOnlyProfile()

        assertTrue(OpenVpnAuthDefaults.applyIfMissing(profile))

        assertEquals(VpnProfile.TYPE_USERPASS_CERTIFICATES, profile.mAuthenticationType)
        assertEquals("vpn", profile.mUsername)
        assertEquals("vpn", profile.mPassword)
    }

    @Test
    fun `promotion keeps the embedded certificate and key`() {
        val profile = certOnlyProfile()
        val cert = profile.mClientCertFilename
        val key = profile.mClientKeyFilename

        OpenVpnAuthDefaults.applyIfMissing(profile)

        assertEquals(cert, profile.mClientCertFilename)
        assertEquals(key, profile.mClientKeyFilename)
    }

    @Test
    fun `profile that already carries a username is left alone`() {
        val profile = certOnlyProfile()
        profile.mUsername = "someone"
        profile.mPassword = "secret"

        assertFalse(OpenVpnAuthDefaults.applyIfMissing(profile))

        assertEquals(VpnProfile.TYPE_CERTIFICATES, profile.mAuthenticationType)
        assertEquals("someone", profile.mUsername)
        assertEquals("secret", profile.mPassword)
    }

    @Test
    fun `certificate profile without an embedded certificate is left alone`() {
        val profile = certOnlyProfile()
        profile.mClientCertFilename = null

        assertFalse(OpenVpnAuthDefaults.applyIfMissing(profile))

        assertEquals(VpnProfile.TYPE_CERTIFICATES, profile.mAuthenticationType)
        assertEquals("", profile.mUsername)
    }

    @Test
    fun `certificate profile without an embedded key is left alone`() {
        val profile = certOnlyProfile()
        profile.mClientKeyFilename = ""

        assertFalse(OpenVpnAuthDefaults.applyIfMissing(profile))

        assertEquals(VpnProfile.TYPE_CERTIFICATES, profile.mAuthenticationType)
    }

    @Test
    fun `userpass-certificates profile without a username is not promoted twice`() {
        val profile = certOnlyProfile()
        profile.mAuthenticationType = VpnProfile.TYPE_USERPASS_CERTIFICATES

        assertFalse(OpenVpnAuthDefaults.applyIfMissing(profile))

        // Still empty: this helper only fills the certificate-only case, so a
        // caller that produced a USERPASS profile with no username keeps
        // whatever it decided.
        assertEquals("", profile.mUsername)
    }

    @Test
    fun `static key profile is left alone`() {
        val profile = VpnProfile("static").apply {
            mAuthenticationType = VpnProfile.TYPE_STATICKEYS
            mClientCertFilename = "cert"
            mClientKeyFilename = "key"
        }

        assertFalse(OpenVpnAuthDefaults.applyIfMissing(profile))

        assertEquals(VpnProfile.TYPE_STATICKEYS, profile.mAuthenticationType)
        assertEquals("", profile.mUsername)
    }

    @Test
    fun `the hub account is the one VPN Gate publishes`() {
        assertEquals("vpn", OpenVpnAuthDefaults.VPN_GATE_USERNAME)
        assertEquals("vpn", OpenVpnAuthDefaults.VPN_GATE_PASSWORD)
    }
}
