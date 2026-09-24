package vn.unlimit.vpngate.repository

import vn.unlimit.vpngate.data.model.VpnProtocolNames
import vn.unlimit.vpngate.data.model.VpnRecords
import vn.unlimit.vpngate.data.model.VpnServerRecord
import vn.unlimit.vpngate.models.VPNGateConnection
import vn.unlimit.vpngate.models.VPNGateConnectionList
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Maps protocol-first collector records into the app's legacy
 * [VPNGateConnection] shape so the existing list/detail/connect UI
 * keeps working. Conventions preserved:
 *  - speed in bits/s (UI divides by 1e6), uptime in ms (UI formats)
 *  - hostName stored WITHOUT the .opengw.net suffix (UI re-appends)
 *  - SoftEther UDP falls back to the TCP listener port (same-port
 *    serving), documented in the former enrichFromHtmlServer.
 */
object VpnConnectionMapper {
    private const val OPGW_SUFFIX = ".opengw.net"

    fun toConnectionList(records: List<VpnServerRecord>): VPNGateConnectionList {
        val list = VPNGateConnectionList()
        for (record in records) {
            toConnection(record)?.let { list.add(it) }
        }
        return list
    }

    fun toConnection(record: VpnServerRecord): VPNGateConnection? {
        val identity = VpnRecords.identity(record)
        val ip = VpnRecords.str(identity["ip"])
        if (ip.isEmpty()) return null

        var hostname = VpnRecords.str(identity["hostname"])
        if (hostname.endsWith(OPGW_SUFFIX)) {
            hostname = hostname.removeSuffix(OPGW_SUFFIX)
        }
        if (hostname.isEmpty()) {
            hostname = ip
        }

        val perf = VpnRecords.performance(record)
        val protocols = VpnRecords.protocols(record)
        val se = VpnRecords.map(protocols[VpnProtocolNames.SOFTETHER])
        val ov = VpnRecords.map(protocols[VpnProtocolNames.OPENVPN])
        val l2 = VpnRecords.map(protocols[VpnProtocolNames.L2TP_IPSEC])
        val sstp = VpnRecords.map(protocols[VpnProtocolNames.SSTP])

        val seTcpPort = VpnRecords.int(VpnRecords.transport(se, "tcp")["port"])
        var seUdpPort = VpnRecords.int(VpnRecords.transport(se, "udp")["port"])
        val seUdpSupported = VpnRecords.bool(VpnRecords.transport(se, "udp")["supported"])
        // §decision: SoftEther serves UDP on the same listener as TCP.
        if (seUdpSupported && seUdpPort <= 0 && seTcpPort > 0) {
            seUdpPort = seTcpPort
        }
        val uptimeDays = VpnRecords.num(perf["uptimeDays"])
        val uptimeMs = min(uptimeDays * 86_400_000.0, Int.MAX_VALUE.toDouble()).toLong()

        val speedMbps = VpnRecords.num(perf["speedMbps"])
        val speedBps = min(speedMbps * 1_000_000.0, Int.MAX_VALUE.toDouble()).toLong()

        val conn = VPNGateConnection()
        conn.hostName = hostname
        conn.ip = ip
        conn.score = VpnRecords.int(perf["score"])
        conn.ping = VpnRecords.num(perf["pingMs"]).roundToLong().toInt()
        conn.speed = speedBps.toInt()
        conn.countryLong = VpnRecords.str(identity["countryLong"])
        conn.countryShort = VpnRecords.str(identity["country"])
        conn.numVpnSession = VpnRecords.int(perf["sessions"])
        conn.uptime = uptimeMs.toInt()
        conn.totalUser = VpnRecords.int(perf["totalUsers"])
        conn.totalTraffic =
            (VpnRecords.num(perf["totalTrafficGB"]) * 1024.0 * 1024.0 * 1024.0).roundToLong()
        conn.logType = VpnRecords.str(VpnRecords.map(record["logging"])["policy"])
        conn.operator = VpnRecords.str(VpnRecords.map(record["operator"])["name"])
        conn.message = VpnRecords.str(VpnRecords.map(record["operator"])["message"])

        // Fully decoded OpenVPN profile (app-specific field).
        conn.openVpnConfigData = VpnRecords.str(ov["rawConfig"]).takeIf { it.isNotEmpty() }

        conn.tcpPort = VpnRecords.int(VpnRecords.transport(ov, "tcp")["port"])
        conn.udpPort = VpnRecords.int(VpnRecords.transport(ov, "udp")["port"])
        conn.isL2TPSupport = if (VpnRecords.bool(l2["supported"])) 1 else 0
        // Supported whenever an SSTP hostname exists, even with unknown
        // port (locked decision: connect on TCP 443 by default).
        conn.isSSTPSupport = if (VpnRecords.bool(sstp["supported"])) 1 else 0
        conn.seTcpPort = seTcpPort
        conn.seUdpPort = seUdpPort
        // UI state (§plan T3.8): UDP offered with no published port.
        conn.seUdpSupported = seUdpSupported

        return conn
    }
}
