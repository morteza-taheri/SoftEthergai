package vn.unlimit.vpngate.models

import org.junit.Assert.assertEquals
import org.junit.Test
import vn.unlimit.vpngate.data.model.VpnPortSource

/**
 * Stage 2F-A (J-4/L-3): the SSTP port description shown in the detail screen.
 *
 * The screen used to choose between "published port" and "default port" by
 * reading `conn.tcpPort` — the OpenVPN TCP port. That made the SSTP label a
 * function of an unrelated protocol: a server offering OpenVPN over TCP had its
 * SSTP default 443 presented as an explicitly published port, and a server
 * with a genuinely published SSTP port but no OpenVPN had it presented as a
 * default.
 *
 * The decision now lives in [VPNGateConnection.sstpPortKind] and is pure, so it
 * is verified here directly. Each case maps to a string that already exists
 * (`protocol_available_port`, `protocol_available_default_port`,
 * `protocol_supported_port_unknown`), so no new resources were added.
 */
class SstpPortPresentationTest {

    private fun sstp(
        supported: Boolean = true,
        sstpPort: Int = 0,
        sstpPortSource: String = VpnPortSource.UNKNOWN,
        openVpnTcpPort: Int = 0,
    ) = VPNGateConnection().apply {
        hostName = "sstp-host"
        ip = "10.0.0.1"
        isSSTPSupport = if (supported) 1 else 0
        this.sstpPort = sstpPort
        this.sstpPortSource = sstpPortSource
        this.tcpPort = openVpnTcpPort
    }

    // ---- A) EXPLICIT: published port wording ----

    @Test
    fun `an explicit sstp port is presented as a published port`() {
        val conn = sstp(sstpPort = 4431, sstpPortSource = VpnPortSource.EXPLICIT)

        assertEquals(SstpPortKind.EXPLICIT, conn.sstpPortKind)
        assertEquals(4431, conn.sstpConnectPort)
    }

    @Test
    fun `an explicit sstp port stays explicit when openvpn tcp is also present`() {
        // The regression: OpenVPN TCP used to downgrade this label to "default".
        val conn = sstp(
            sstpPort = 4431,
            sstpPortSource = VpnPortSource.EXPLICIT,
            openVpnTcpPort = 1300,
        )

        assertEquals(SstpPortKind.EXPLICIT, conn.sstpPortKind)
    }

    // ---- B) DEFAULT: default-port wording, distinct from published ----

    @Test
    fun `the default sstp port is presented as a default, not as published`() {
        val conn = sstp(sstpPort = 0, sstpPortSource = VpnPortSource.DEFAULT)

        assertEquals(SstpPortKind.DEFAULT, conn.sstpPortKind)
        assertEquals(443, conn.sstpConnectPort)
    }

    @Test
    fun `the default sstp port stays a default when openvpn tcp is present`() {
        // The regression: OpenVPN TCP used to present 443 as a published port.
        val conn = sstp(sstpPort = 0, sstpPortSource = VpnPortSource.DEFAULT, openVpnTcpPort = 443)

        assertEquals(SstpPortKind.DEFAULT, conn.sstpPortKind)
        assertEquals(443, conn.sstpConnectPort)
    }

    @Test
    fun `a stored default port of 443 is still described as a default`() {
        val conn = sstp(sstpPort = 443, sstpPortSource = VpnPortSource.DEFAULT)

        assertEquals(SstpPortKind.DEFAULT, conn.sstpPortKind)
    }

    // ---- C) UNKNOWN: existing unknown wording ----

    @Test
    fun `an unknown sstp port is presented as unknown`() {
        val conn = sstp(sstpPort = 0, sstpPortSource = VpnPortSource.UNKNOWN)

        assertEquals(SstpPortKind.UNKNOWN, conn.sstpPortKind)
        // No port may be invented from UNKNOWN.
        assertEquals(0, conn.sstpConnectPort)
    }

    @Test
    fun `an empty legacy provenance is presented as unknown`() {
        val conn = sstp(sstpPort = 0, sstpPortSource = "")

        assertEquals(SstpPortKind.UNKNOWN, conn.sstpPortKind)
        assertEquals(0, conn.sstpConnectPort)
    }

    @Test
    fun `an unrecognised provenance is presented as unknown rather than trusted`() {
        val conn = sstp(sstpPort = 1194, sstpPortSource = "SOMETHING_ELSE")

        assertEquals(SstpPortKind.UNKNOWN, conn.sstpPortKind)
    }

    @Test
    fun `explicit provenance without a port is not presented as a published port`() {
        // Inconsistent state: the safest description is "unknown", never a
        // number that was never established.
        val conn = sstp(sstpPort = 0, sstpPortSource = VpnPortSource.EXPLICIT)

        assertEquals(SstpPortKind.UNKNOWN, conn.sstpPortKind)
        assertEquals(0, conn.sstpConnectPort)
    }

    // ---- SSTP not supported at all ----

    @Test
    fun `a server without sstp support never reports a port kind that implies a port`() {
        val conn = sstp(supported = false, sstpPort = 0, sstpPortSource = VpnPortSource.DEFAULT)

        assertEquals(SstpPortKind.UNKNOWN, conn.sstpPortKind)
        assertEquals(0, conn.sstpConnectPort)
    }

    // ---- The decision must not depend on any other protocol ----

    @Test
    fun `the sstp description is identical whatever openvpn tcp reports`() {
        for (tcp in listOf(0, 443, 1300, 65535)) {
            for (source in listOf(
                VpnPortSource.EXPLICIT,
                VpnPortSource.DEFAULT,
                VpnPortSource.UNKNOWN,
            )) {
                val a = sstp(sstpPort = 995, sstpPortSource = source, openVpnTcpPort = tcp)
                val b = sstp(sstpPort = 995, sstpPortSource = source, openVpnTcpPort = 0)

                assertEquals(
                    "source=$source tcp=$tcp",
                    a.sstpPortKind,
                    b.sstpPortKind,
                )
                assertEquals("source=$source tcp=$tcp", a.sstpConnectPort, b.sstpConnectPort)
            }
        }
    }

    @Test
    fun `the sstp port is never taken from openvpn tcp or softether tcp`() {
        val conn = sstp(
            sstpPort = 0,
            sstpPortSource = VpnPortSource.UNKNOWN,
            openVpnTcpPort = 1300,
        ).apply { seTcpPort = 443 }

        assertEquals(SstpPortKind.UNKNOWN, conn.sstpPortKind)
        assertEquals(0, conn.sstpConnectPort)
    }
}
