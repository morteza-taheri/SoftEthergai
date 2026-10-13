package vn.unlimit.vpngate.models

import android.content.Context
import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import android.util.Base64
import vn.unlimit.vpngate.App.Companion.instance
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.data.model.VpnPortSource
import vn.unlimit.vpngate.data.model.VpnProtocolDefaults
import vn.unlimit.vpngate.utils.DataUtil
import java.text.DecimalFormat
import kotlin.math.roundToInt

/**
 * Stage 2F-A (J-4/L-3): the three states an MS-SSTP port can be in, as the
 * UI must describe them.
 *
 * Each maps to an existing localized string, so this adds no new resources:
 * [EXPLICIT] and [DEFAULT] both show a port number but only [DEFAULT] says it
 * is the protocol default, and [UNKNOWN] shows the "port unknown" wording.
 */
enum class SstpPortKind {
    /** The source printed this port. */
    EXPLICIT,

    /** The source confirmed SSTP but published no port; 443 is the default. */
    DEFAULT,

    /** No known port: nothing may be inferred. */
    UNKNOWN,
}

/**
 * Created by dongh on 14/01/2018.
 */
class VPNGateConnection : Parcelable {
    //HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,logType,Operator,Message,OpenVPN_ConfigData_Base64
    var hostName: String? = null
    var ip: String? = null
    var score = 0
    var ping = 0
    var speed = 0
    var countryLong: String? = null
    var countryShort: String? = null
    var numVpnSession = 0
    var uptime = 0
    var totalUser = 0
    var totalTraffic: Long = 0
    var logType: String? = null
    var operator: String? = null
    var message: String? = null
    var openVpnConfigData: String? = null
    var tcpPort = 0
    var udpPort = 0
    var isL2TPSupport = 0
    var isSSTPSupport = 0
    // MS-SSTP's OWN port, collected independently (never borrowed from
    // OpenVPN or SoftEther). 0 means the source published no SSTP port.
    var sstpPort = 0
    // Provenance of [sstpPort]: EXPLICIT (printed by the source), DEFAULT
    // (the protocol default applies) or UNKNOWN (nothing published and no
    // confirmed capability). Copied verbatim from the record layer.
    //
    // Stage 2F-A (L-1): UNKNOWN is a real literal again, so every boundary
    // that used to fold "" into UNKNOWN now normalizes instead, which also
    // catches rows written before this constant changed.
    var sstpPortSource: String = VpnPortSource.UNKNOWN
    var seTcpPort = 0
    var seUdpPort = 0
    // SoftEther UDP offered without a published port (§6/§9): the UI
    // shows "supported — port unknown" instead of hiding the option.
    var seUdpSupported = false

    val isUdpOnly: Boolean
        get() = seTcpPort <= 0 && seUdpSupported

    /** SoftEther UDP is offered but no port was published (§6/§9). */
    val isSeUdpPortUnknown: Boolean
        get() = seUdpSupported && seUdpPort <= 0

    /**
     * Port to use for an MS-SSTP connection, derived ONLY from SSTP
     * state. [tcpPort] is OpenVPN TCP and is never used here.
     *
     *  - SSTP unsupported -> 0 (no connection is possible)
     *  - a published SSTP port -> that port
     *  - supported with DEFAULT provenance -> the protocol-standard 443
     *  - anything else -> 0, rather than a guessed port
     */
    val sstpConnectPort: Int
        get() = when {
            !isSSTPSupport() -> 0
            sstpPort > 0 -> sstpPort
            // Stage 2F-A (L-1): compare against the canonical DEFAULT, and
            // normalize first so a legacy empty/garbage value can never be
            // mistaken for a known provenance and used to invent a port.
            VpnPortSource.normalized(sstpPortSource) == VpnPortSource.DEFAULT -> SSTP_DEFAULT_PORT
            else -> 0
        }

    /**
     * Stage 2F-A (J-4/L-3): how the SSTP port must be DESCRIBED in the UI.
     *
     * The detail screen used to pick between "published port" and "default
     * port" by looking at [tcpPort] — the OpenVPN TCP port. A server that
     * offered OpenVPN over TCP therefore had its SSTP default 443 presented
     * as an explicitly published port, and a server without OpenVPN had a
     * genuinely published SSTP port presented as a default. The decision
     * belongs to SSTP's own provenance, which is what this reports.
     *
     * It is deliberately provenance-first rather than port-first: a value
     * whose provenance is UNKNOWN is described as unknown even if a number
     * happens to be stored, because "we do not know where this came from" is
     * the truth the user needs. Pure, so it is unit-testable.
     */
    val sstpPortKind: SstpPortKind
        get() = when {
            !isSSTPSupport() -> SstpPortKind.UNKNOWN
            VpnPortSource.normalized(sstpPortSource) == VpnPortSource.EXPLICIT && sstpPort > 0 ->
                SstpPortKind.EXPLICIT
            VpnPortSource.normalized(sstpPortSource) == VpnPortSource.DEFAULT -> SstpPortKind.DEFAULT
            else -> SstpPortKind.UNKNOWN
        }

    private constructor(`in`: Parcel) {
        hostName = `in`.readString()
        ip = `in`.readString()
        score = `in`.readInt()
        ping = `in`.readInt()
        speed = `in`.readInt()
        countryLong = `in`.readString()
        countryShort = `in`.readString()
        numVpnSession = `in`.readInt()
        uptime = `in`.readInt()
        totalUser = `in`.readInt()
        totalTraffic = `in`.readLong()
        logType = `in`.readString()
        operator = `in`.readString()
        message = `in`.readString()
        openVpnConfigData = `in`.readString()
        tcpPort = `in`.readInt()
        udpPort = `in`.readInt()
        isL2TPSupport = `in`.readInt()
        isSSTPSupport = `in`.readInt()
        sstpPort = `in`.readInt()
        sstpPortSource = VpnPortSource.normalized(`in`.readString())
        seTcpPort = `in`.readInt()
        seUdpPort = `in`.readInt()
        seUdpSupported = `in`.readInt() == 1
    }

    //Empty constructor
    constructor()

    override fun writeToParcel(out: Parcel, flags: Int) {
        out.writeString(hostName)
        out.writeString(ip)
        out.writeInt(score)
        out.writeInt(ping)
        out.writeInt(speed)
        out.writeString(countryLong)
        out.writeString(countryShort)
        out.writeInt(numVpnSession)
        out.writeInt(uptime)
        out.writeInt(totalUser)
        out.writeLong(totalTraffic)
        out.writeString(logType)
        out.writeString(operator)
        out.writeString(message)
        out.writeString(openVpnConfigData)
        out.writeInt(tcpPort)
        out.writeInt(udpPort)
        out.writeInt(isL2TPSupport)
        out.writeInt(isSSTPSupport)
        out.writeInt(sstpPort)
        out.writeString(sstpPortSource)
        out.writeInt(seTcpPort)
        out.writeInt(seUdpPort)
        out.writeInt(if (seUdpSupported) 1 else 0)
    }

    private fun decodeBase64(base64str: String): String? {
        try {
            val plainBytes = Base64.decode(base64str, 1)
            return String(plainBytes)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    val calculateHostName: String
        get() {
            val name = hostName ?: ""
            return "$name.opengw.net"
        }

    val scoreAsString: String
        get() = score.toString()

    val pingAsString: String
        get() = ping.toString() + ""

    val numVpnSessionAsString: String
        get() = numVpnSession.toString() + ""

    fun setOperatorString(operator: String) {
        var loperator = operator
        loperator = loperator.replace("'s owner", "")
        this.operator = loperator
    }

    /**
     * The profile to hand to the OpenVPN core, or null when this server has
     * no profile stored.
     *
     * Every step is null-safe: a server can legitimately carry an OpenVPN
     * port with no profile (a collection that only saw the HTML feed), and
     * that must be reported as "no profile", never as a crash.
     */
    fun getOpenVpnConfigDataString(): String? =
        OpenVpnConfigTransform.forDomain(
            config = openVpnConfigData,
            ip = ip,
            hostName = hostName,
            useDomain = useDomainToConnect(),
        )

    private fun useDomainToConnect(): Boolean =
        instance?.dataUtil?.getBooleanSetting(DataUtil.USE_DOMAIN_TO_CONNECT, false) ?: false

    fun setOpenVpnConfigDataString(openVpnConfigData: String) {
        this.openVpnConfigData = decodeBase64(openVpnConfigData)?.takeIf { it.isNotBlank() }
    }

    /**
     * The public vpngate.net feed carries no per-protocol port columns, only the
     * OpenVPN config blob. Derive what the blob tells us so the protocol
     * selection UI is not empty:
     * - "proto tcp" / "proto udp" selects the transport
     * - "remote <host> <port>" carries the port
     * A SoftEther VPN server serves its native protocol on the same TCP
     * listener as OpenVPN over TCP (protocol auto-detection), so the TCP port
     * from the blob also becomes the SoftEther TCP port.
     * Explicit port columns from an extended feed are never overridden.
     */
    private fun derivePortsFromOpenVpnConfig() {
        val config = openVpnConfigData ?: return
        var proto: String? = null
        var remotePort = 0
        for (rawLine in config.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                continue
            }
            if (proto == null && line.startsWith("proto ")) {
                proto = line.removePrefix("proto ").trim()
            }
            if (remotePort == 0 && line.startsWith("remote ")) {
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 3) {
                    remotePort = parts[2].toIntOrNull() ?: 0
                }
            }
        }
        if (proto == null || remotePort <= 0) {
            return
        }
        if (proto.startsWith("tcp")) {
            if (tcpPort == 0) tcpPort = remotePort
            if (seTcpPort == 0) seTcpPort = remotePort
        } else if (proto.startsWith("udp")) {
            if (udpPort == 0) udpPort = remotePort
        }
    }

    /**
     * The UDP variant of the stored profile, or null when this server has no
     * profile. VPN Gate publishes TCP profiles only, so this is a
     * transformation — and a transformation must never throw just because the
     * server has a TCP port but no profile.
     */
    val openVpnConfigDataUdp: String?
        get() = OpenVpnConfigTransform.forDomain(
            config = OpenVpnConfigTransform.forUdp(
                config = openVpnConfigData,
                tcpPort = tcpPort,
                udpPort = udpPort,
                ip = ip,
            ),
            ip = ip,
            hostName = hostName,
            useDomain = useDomainToConnect(),
        )

    val calculateSpeed: String
        get() = round(speed.toDouble() / (1000 * 1000))

    val calculateTotalTraffic: String
        get() {
            val inMB = totalTraffic.toDouble() / (1000 * 1000)
            if (inMB < 1000) {
                return round(inMB) + " MB"
            }
            val inGB = inMB / 1000
            if (inGB < 1000) {
                return round(inMB / 1000) + " GB"
            }
            return round(inGB / 1000) + " TB"
        }

    fun getCalculateUpTime(context: Context): String {
        //Display as second
        if (uptime < 60000) {
            return round((uptime / 1000).toDouble()) + " " + context.resources.getString(R.string.seconds)
        }
        //Display as minute
        if (uptime < 3600000) {
            return (uptime.toDouble() / 60000).roundToInt()
                .toString() + " " + context.resources.getString(R.string.minutes)
        }
        //Display as hours
        if (uptime < 3600000 * 24) {
            return round(uptime.toDouble() / 3600000) + " " + context.resources.getString(R.string.hours)
        }
        return round(uptime.toDouble() / (24 * 3600000)) + " " + context.resources.getString(R.string.days)
    }

    private fun round(value: Double): String {
        val df = DecimalFormat("####0.###")
        return df.format(value)
    }

    override fun describeContents(): Int {
        return 0
    }

    fun toVPNGateItem(): VPNGateItem {
        return VPNGateItem(
            hostName = this.hostName!!,
            ip = this.ip,
            score = this.score,
            ping = this.ping,
            speed = this.speed,
            countryLong = this.countryLong,
            countryShort = this.countryShort,
            numVpnSession = this.numVpnSession,
            uptime = this.uptime,
            totalUser = this.totalUser,
            totalTraffic = this.totalTraffic,
            logType = this.logType,
            operator = this.operator,
            message = this.message,
            openVpnConfigData = this.openVpnConfigData,
            tcpPort = this.tcpPort,
            udpPort = this.udpPort,
            isL2TPSupport = this.isL2TPSupport(),
            isSSTPSupport = this.isSSTPSupport(),
            sstpPort = this.sstpPort,
            sstpPortSource = this.sstpPortSource,
            seTcpPort = this.seTcpPort,
            seUdpPort = this.seUdpPort,
            seUdpSupported = this.seUdpSupported
        )
    }

    fun fromVPNGateItem(vpnGateItem: VPNGateItem): VPNGateConnection {
        hostName = vpnGateItem.hostName
        ip = vpnGateItem.ip
        score = vpnGateItem.score
        ping = vpnGateItem.ping
        speed = vpnGateItem.speed
        countryLong = vpnGateItem.countryLong
        countryShort = vpnGateItem.countryShort
        numVpnSession = vpnGateItem.numVpnSession
        uptime = vpnGateItem.uptime
        totalUser = vpnGateItem.totalUser
        totalTraffic = vpnGateItem.totalTraffic
        logType = vpnGateItem.logType
        operator = vpnGateItem.operator
        message = vpnGateItem.message
        openVpnConfigData = vpnGateItem.openVpnConfigData
        tcpPort = vpnGateItem.tcpPort
        udpPort = vpnGateItem.udpPort
        isL2TPSupport = if (vpnGateItem.isL2TPSupport) 1 else 0
        isSSTPSupport = if (vpnGateItem.isSSTPSupport) 1 else 0
        // Restored verbatim: an SSTP port is only ever the stored SSTP port
        // with the stored provenance. Nothing is inferred or substituted.
        // Stage 2F-A (L-1): the provenance is normalized, so a row written
        // before UNKNOWN became a literal (an empty column) still reads as
        // UNKNOWN instead of as an unknown third value.
        sstpPort = vpnGateItem.sstpPort
        sstpPortSource = VpnPortSource.normalized(vpnGateItem.sstpPortSource)
        seTcpPort = vpnGateItem.seTcpPort
        seUdpPort = vpnGateItem.seUdpPort
        seUdpSupported = vpnGateItem.seUdpSupported
        return this
    }

    val name: String
        get() = this.getName(false)

    fun getName(useUdp: Boolean, useSoftEther: Boolean = false): String {
        var address = ip
        if (instance!!.dataUtil!!.getBooleanSetting(DataUtil.USE_DOMAIN_TO_CONNECT, false)) {
            address = "$hostName.opengw.net"
        }
        if (instance!!.dataUtil!!.getBooleanSetting(DataUtil.INCLUDE_UDP_SERVER, true)) {
            val tcp = if (useSoftEther && seTcpPort > 0) seTcpPort else tcpPort
            val udp = if (useSoftEther && seUdpPort > 0) seUdpPort else udpPort
            if (tcp == 0 && udp == 0) {
                return String.format("%s[%s]", countryLong, address)
            }
            val portStr = if (useUdp || tcp == 0) {
                "UDP:$udp"
            } else {
                "TCP:$tcp"
            }
            return String.format("%s[%s][%s]", countryLong, address, portStr)
        }
        return String.format("%s[%s]", countryLong, address)
    }

    fun isL2TPSupport(): Boolean {
        return isL2TPSupport == 1 && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
    }

    fun isSSTPSupport(): Boolean {
        return isSSTPSupport == 1
    }

    companion object {
        /** MS-SSTP protocol-standard listener (VpnProtocolDefaults). */
        const val SSTP_DEFAULT_PORT = VpnProtocolDefaults.SSTP_PORT

        @JvmField
        val CREATOR
                : Parcelable.Creator<VPNGateConnection> =
            object : Parcelable.Creator<VPNGateConnection> {
                override fun createFromParcel(`in`: Parcel): VPNGateConnection {
                    return VPNGateConnection(`in`)
                }

                override fun newArray(size: Int): Array<VPNGateConnection?> {
                    return arrayOfNulls(size)
                }
            }

        fun fromCsv(csvLine: String): VPNGateConnection? {
            val properties =
                csvLine.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            try {
                var index = 0
                val vpnGateConnection = VPNGateConnection()
                vpnGateConnection.hostName = properties[index++]
                vpnGateConnection.ip = properties[index++]
                vpnGateConnection.score = properties[index++].toInt()
                vpnGateConnection.ping = properties[index++].toInt()
                vpnGateConnection.speed = properties[index++].toInt()
                vpnGateConnection.countryLong = properties[index++]
                vpnGateConnection.countryShort = properties[index++]
                vpnGateConnection.numVpnSession = properties[index++].toInt()
                vpnGateConnection.uptime = properties[index++].toInt()
                vpnGateConnection.totalUser = properties[index++].toInt()
                vpnGateConnection.totalTraffic = properties[index++].toLong()
                vpnGateConnection.logType = properties[index++]
                vpnGateConnection.setOperatorString(properties[index++])
                vpnGateConnection.message = properties[index++]
                vpnGateConnection.setOpenVpnConfigDataString(properties[index])
                if (instance!!.dataUtil!!.getBooleanSetting(
                        DataUtil.INCLUDE_UDP_SERVER,
                        true
                    ) && properties.size >= index + 2
                ) {
                    vpnGateConnection.tcpPort = properties[++index].toInt()
                    vpnGateConnection.udpPort = properties[++index].toInt()
                    if (properties.size > index + 1) {
                        vpnGateConnection.isL2TPSupport = properties[++index].toInt()
                    }
                    if (properties.size > index + 1) {
                        vpnGateConnection.isSSTPSupport = properties[++index].toInt()
                    }
                    if (properties.size > index + 1) {
                        vpnGateConnection.seTcpPort = properties[++index].toInt()
                    }
                    if (properties.size > index + 1) {
                        vpnGateConnection.seUdpPort = properties[++index].toInt()
                    }
                } else {
                    vpnGateConnection.tcpPort = 0
                    vpnGateConnection.udpPort = 0
                    vpnGateConnection.isL2TPSupport = 0
                    vpnGateConnection.isSSTPSupport = 0
                    vpnGateConnection.seTcpPort = 0
                    vpnGateConnection.seUdpPort = 0
                }
                vpnGateConnection.derivePortsFromOpenVpnConfig()
                // The legacy CSV has no SSTP port column, so a
                // supported SSTP row can only rely on the protocol
                // default — never on the OpenVPN tcp/udp columns.
                if (vpnGateConnection.isSSTPSupport == 1 && vpnGateConnection.sstpPort <= 0) {
                    vpnGateConnection.sstpPortSource = VpnPortSource.DEFAULT
                }
                return vpnGateConnection
            } catch (_: Exception) {
                return null
            }
        }
    }
}
