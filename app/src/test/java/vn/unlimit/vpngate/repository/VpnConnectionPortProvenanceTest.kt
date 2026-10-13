package vn.unlimit.vpngate.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.collector.CollectorTestFixtures
import vn.unlimit.vpngate.data.model.VpnPortSource
import vn.unlimit.vpngate.data.model.VpnRecords
import vn.unlimit.vpngate.data.model.VpnServerRecord
import vn.unlimit.vpngate.merger.VpnServerMerger
import vn.unlimit.vpngate.models.VPNGateConnection
import vn.unlimit.vpngate.parser.VpnGateHtmlParser

/**
 * Stage 2A-B: the connection model and mapper must carry the
 * record layer's protocol-specific port semantics, and must never
 * substitute one protocol's port for another's.
 */
class VpnConnectionPortProvenanceTest {

    private fun record(): VpnServerRecord = VpnRecords.newServer()

    private fun withSoftEtherTcp(record: VpnServerRecord, port: Int) {
        VpnRecords.setField(record, "identity.ip", "10.20.30.41", "html")
        VpnRecords.setField(record, "protocols.softether.tcp.supported", true, "html")
        VpnRecords.setField(record, "protocols.softether.tcp.port", port, "html")
        VpnRecords.setField(
            record,
            "protocols.softether.tcp.portSource",
            VpnPortSource.EXPLICIT,
            "html",
        )
    }

    private fun withSoftEtherUdpNoPort(record: VpnServerRecord) {
        VpnRecords.setField(record, "protocols.softether.udp.supported", true, "html")
    }

    private fun withSstp(
        record: VpnServerRecord,
        supported: Boolean,
        port: Int?,
        portSource: String,
    ) {
        VpnRecords.setField(record, "protocols.sstp.supported", supported, "html")
        if (port != null) {
            VpnRecords.setField(record, "protocols.sstp.port", port, "html")
        }
        VpnRecords.setField(record, "protocols.sstp.portSource", portSource, "html")
    }

    // ---- TEST A: SoftEther UDP must not inherit TCP ----

    @Test
    fun softEtherUdpDoesNotInheritTcpPort() {
        val record = record()
        withSoftEtherTcp(record, 995)
        withSoftEtherUdpNoPort(record)
        VpnRecords.sourceAdd(record, "html")

        val conn = VpnConnectionMapper.toConnection(record)!!
        assertEquals(995, conn.seTcpPort)
        assertEquals(true, conn.seUdpSupported)
        assertEquals(0, conn.seUdpPort)
        assertEquals(true, conn.isSeUdpPortUnknown)
        assertNotEquals(conn.seTcpPort, conn.seUdpPort)
    }

    // ---- TEST B: SoftEther UDP with no TCP listener ----

    @Test
    fun softEtherUdpStaysUnknownWithoutTcpPort() {
        val record = record()
        VpnRecords.setField(record, "identity.ip", "10.20.30.42", "html")
        withSoftEtherUdpNoPort(record)
        VpnRecords.sourceAdd(record, "html")

        val conn = VpnConnectionMapper.toConnection(record)!!
        assertEquals(0, conn.seTcpPort)
        assertEquals(0, conn.seUdpPort)
        assertEquals(true, conn.isSeUdpPortUnknown)
    }

    // ---- TEST C: SSTP explicit port ----

    @Test
    fun sstpExplicitPortSurvivesMapping() {
        val record = record()
        VpnRecords.setField(record, "identity.ip", "221.167.26.145", "html")
        // A DIFFERENT OpenVPN TCP port, to prove independence.
        withSoftEtherTcp(record, 995)
        VpnRecords.setField(record, "protocols.openvpn.tcp.supported", true, "html")
        VpnRecords.setField(record, "protocols.openvpn.tcp.port", 1194, "html")
        withSstp(record, supported = true, port = 995, portSource = VpnPortSource.EXPLICIT)
        VpnRecords.sourceAdd(record, "html")

        val conn = VpnConnectionMapper.toConnection(record)!!
        assertEquals(995, conn.sstpPort)
        assertEquals(VpnPortSource.EXPLICIT, conn.sstpPortSource)
        assertEquals(995, conn.sstpConnectPort)
        assertNotEquals(1194, conn.sstpConnectPort)
        assertNotEquals(conn.tcpPort, conn.sstpConnectPort)
    }

    // ---- TEST D: SSTP default port ----

    @Test
    fun sstpDefaultPortYields443WithoutInventingAPort() {
        val record = record()
        VpnRecords.setField(record, "identity.ip", "219.100.37.165", "html")
        withSstp(record, supported = true, port = null, portSource = VpnPortSource.DEFAULT)
        VpnRecords.sourceAdd(record, "html")

        val conn = VpnConnectionMapper.toConnection(record)!!
        assertEquals(0, conn.sstpPort)
        assertEquals(VpnPortSource.DEFAULT, conn.sstpPortSource)
        assertEquals(443, conn.sstpConnectPort)
        assertEquals(VPNGateConnection.SSTP_DEFAULT_PORT, conn.sstpConnectPort)
    }

    // ---- TEST E: unsupported SSTP ----

    @Test
    fun unsupportedSstpHasNoEffectivePort() {
        val record = record()
        VpnRecords.setField(record, "identity.ip", "128.211.249.131", "html")
        withSstp(record, supported = false, port = null, portSource = VpnPortSource.UNKNOWN)
        VpnRecords.sourceAdd(record, "html")

        val conn = VpnConnectionMapper.toConnection(record)!!
        assertEquals(0, conn.sstpPort)
        assertEquals(VpnPortSource.UNKNOWN, conn.sstpPortSource)
        assertEquals(0, conn.sstpConnectPort)
    }

    // ---- TEST F: SSTP must not use OpenVPN TCP ----

    @Test
    fun sstpNeverUsesOpenVpnTcpPort() {
        val record = record()
        VpnRecords.setField(record, "identity.ip", "219.100.37.165", "html")
        VpnRecords.setField(record, "protocols.openvpn.tcp.supported", true, "html")
        VpnRecords.setField(record, "protocols.openvpn.tcp.port", 1194, "html")
        VpnRecords.setField(record, "protocols.openvpn.udp.supported", true, "html")
        VpnRecords.setField(record, "protocols.openvpn.udp.port", 1195, "html")
        withSstp(record, supported = true, port = null, portSource = VpnPortSource.DEFAULT)
        VpnRecords.sourceAdd(record, "html")

        val conn = VpnConnectionMapper.toConnection(record)!!
        assertEquals(1194, conn.tcpPort)
        assertEquals(1195, conn.udpPort)
        assertEquals(443, conn.sstpConnectPort)
        assertNotEquals(1194, conn.sstpConnectPort)
    }

    // ---- TEST G: real hostname+port from the HTML source ----

    @Test
    fun sstpExplicitPortFromHtmlSourceSurvivesMerge() {
        val record = record()
        VpnRecords.setField(
            record,
            "identity.hostname",
            "vpn812677596.opengw.net:995",
            "html",
        )
        VpnRecords.setField(record, "identity.ip", "221.167.26.145", "html")
        withSoftEtherTcp(record, 995)
        withSstp(record, supported = true, port = 995, portSource = VpnPortSource.EXPLICIT)
        VpnRecords.sourceAdd(record, "html")

        assertTrue(VpnServerMerger.normalizeServer(record))
        val conn = VpnConnectionMapper.toConnection(record)!!
        assertEquals(995, conn.sstpPort)
        assertEquals(VpnPortSource.EXPLICIT, conn.sstpPortSource)
        assertEquals(995, conn.sstpConnectPort)
    }

    // ---- TEST H: OpenVPN TCP/UDP stay independent ----

    @Test
    fun openVpnPortsRemainIndependent() {
        val record = record()
        VpnRecords.setField(record, "identity.ip", "219.100.37.165", "html")
        VpnRecords.setField(record, "protocols.openvpn.tcp.supported", true, "html")
        VpnRecords.setField(record, "protocols.openvpn.tcp.port", 443, "html")
        VpnRecords.setField(record, "protocols.openvpn.udp.supported", true, "html")
        VpnRecords.setField(record, "protocols.openvpn.udp.port", 1195, "html")
        VpnRecords.sourceAdd(record, "html")

        val conn = VpnConnectionMapper.toConnection(record)!!
        assertEquals(443, conn.tcpPort)
        assertEquals(1195, conn.udpPort)

        // No SSTP support at all -> the effective port stays 0 regardless
        // of the OpenVPN ports.
        assertEquals(0, conn.sstpConnectPort)
    }

    // ---- Provenance is copied, never inferred ----

    @Test
    fun mapperDoesNotInferProvenance() {
        val unknown = record()
        VpnRecords.setField(unknown, "identity.ip", "10.0.0.1", "html")
        withSstp(unknown, supported = true, port = 443, portSource = VpnPortSource.UNKNOWN)
        VpnRecords.sourceAdd(unknown, "html")
        val a = VpnConnectionMapper.toConnection(unknown)!!
        assertEquals(VpnPortSource.UNKNOWN, a.sstpPortSource)

        val explicit = record()
        VpnRecords.setField(explicit, "identity.ip", "10.0.0.2", "html")
        withSstp(explicit, supported = true, port = 995, portSource = VpnPortSource.EXPLICIT)
        VpnRecords.sourceAdd(explicit, "html")
        val b = VpnConnectionMapper.toConnection(explicit)!!
        assertEquals(VpnPortSource.EXPLICIT, b.sstpPortSource)

        val defaulted = record()
        VpnRecords.setField(defaulted, "identity.ip", "10.0.0.3", "html")
        withSstp(defaulted, supported = true, port = null, portSource = VpnPortSource.DEFAULT)
        VpnRecords.sourceAdd(defaulted, "html")
        val c = VpnConnectionMapper.toConnection(defaulted)!!
        assertEquals(VpnPortSource.DEFAULT, c.sstpPortSource)
    }

    // ---- L2TP: no invented source-published port in the connection ----

    @Test
    fun l2tpSupportIsMappedWithoutAnInventedConnectionPort() {
        val record = record()
        VpnRecords.setField(record, "identity.ip", "219.100.37.165", "html")
        VpnRecords.markSupported(record, "l2tpIpsec", "html")
        VpnRecords.setField(record, "protocols.l2tpIpsec.port", 1701, "html")
        VpnRecords.setField(
            record,
            "protocols.l2tpIpsec.portSource",
            VpnPortSource.DEFAULT,
            "html",
        )
        VpnRecords.sourceAdd(record, "html")

        assertTrue(VpnServerMerger.normalizeServer(record))
        val conn = VpnConnectionMapper.toConnection(record)!!
        assertEquals(1, conn.isL2TPSupport)
        // No L2TP port field is carried by the connection model, and no
        // other protocol's port leaks into SSTP.
        assertEquals(0, conn.sstpConnectPort)
        val l2 = VpnRecords.map(
            VpnRecords.protocols(record)["l2tpIpsec"],
        )
        assertEquals(VpnPortSource.DEFAULT, l2["portSource"])
    }

    // ---- REAL FIXTURES (no fabricated server data) ----

    private fun realConnections(): Map<String, vn.unlimit.vpngate.models.VPNGateConnection> {
        val html = CollectorTestFixtures.load(CollectorTestFixtures.TABLE_SAMPLE)
        return VpnGateHtmlParser.parseHtml(html, "html")
            .filter { VpnServerMerger.normalizeServer(it) }
            .mapNotNull { VpnConnectionMapper.toConnection(it) }
            .associateBy { it.hostName ?: "" }
    }

    @Test
    fun realFixturePublicVpn206() {
        val conn = realConnections()["public-vpn-206"]!!
        assertEquals(443, conn.seTcpPort)
        assertEquals(true, conn.seUdpSupported)
        assertEquals(0, conn.seUdpPort)
        assertEquals(true, conn.isSeUdpPortUnknown)
        assertEquals(true, conn.isSSTPSupport())
        assertEquals(0, conn.sstpPort)
        assertEquals(VpnPortSource.DEFAULT, conn.sstpPortSource)
        assertEquals(443, conn.sstpConnectPort)
    }

    @Test
    fun realFixtureVpn812677596() {
        val conn = realConnections()["vpn812677596"]!!
        assertEquals(995, conn.seTcpPort)
        assertEquals(true, conn.seUdpSupported)
        assertEquals(0, conn.seUdpPort)
        assertEquals(true, conn.isSSTPSupport())
        assertEquals(995, conn.sstpPort)
        assertEquals(VpnPortSource.EXPLICIT, conn.sstpPortSource)
        assertEquals(995, conn.sstpConnectPort)
    }

    @Test
    fun realFixtureVpn100383739() {
        val conn = realConnections()["vpn100383739"]!!
        assertEquals(true, conn.seUdpSupported)
        assertEquals(0, conn.seUdpPort)
        assertEquals(true, conn.isSeUdpPortUnknown)
        assertEquals(false, conn.isSSTPSupport())
        assertEquals(0, conn.sstpPort)
        assertEquals(0, conn.sstpConnectPort)
    }
}
