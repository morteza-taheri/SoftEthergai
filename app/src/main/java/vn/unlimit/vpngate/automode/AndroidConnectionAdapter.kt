package vn.unlimit.vpngate.automode

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.service.SstpVpnService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import vn.unlimit.softether.SoftEtherVpnService
import vn.unlimit.softether.model.ConnectionConfig
import vn.unlimit.softether.model.Route
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.BuildConfig
import vn.unlimit.vpngate.models.VPNGateConnection
import vn.unlimit.vpngate.utils.AppConfig
import vn.unlimit.vpngate.utils.DataUtil
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

/**
 * Real [AutoModeController.ConnectionAdapter] driving the app's
 * existing VPN services (Â§27): SoftEtherVpnService, SstpVpnService
 * and the ics-openvpn stack. Tunnel-up detection is delegated to
 * [TunnelStateWatcher]; the wait is fully suspend/non-blocking (Â§11).
 */
class AndroidConnectionAdapter(
    private val context: Context,
    private val dataUtil: DataUtil,
) : AutoModeController.ConnectionAdapter {

    companion object {
        private const val TAG = "AutoMode"
        private const val ACTION_VPN_CONNECT = "vn.unlimit.vpngate.VPN_CONNECT"
        private const val ACTION_VPN_DISCONNECT = "vn.unlimit.vpngate.VPN_DISCONNECT"
    }

    @Volatile
    private var currentProtocol: AutoModeProtocol? = null

    override suspend fun connect(candidate: AutoModeCandidate, protocol: AutoModeProtocol) {
        currentProtocol = protocol
        val conn = candidate.payload as? VPNGateConnection ?: return
        withContext(Dispatchers.Main) {
            when (protocol) {
                AutoModeProtocol.SOFTETHER_TCP -> connectSoftEther(conn, useTcp = true)
                AutoModeProtocol.SOFTETHER_UDP -> connectSoftEther(conn, useTcp = false)
                AutoModeProtocol.OPENVPN_TCP -> connectOpenVpn(conn, useUdp = false)
                AutoModeProtocol.OPENVPN_UDP -> connectOpenVpn(conn, useUdp = true)
                AutoModeProtocol.MS_SSTP -> connectSstp(conn)
                AutoModeProtocol.L2TP_IPSEC -> {
                    // L2TP/IPsec needs the OS Settings UI; not automatable in background.
                    Log.w(TAG, "L2TP connect is not automatable in background")
                }
            }
        }
    }

    override suspend fun disconnect() {
        val protocol = currentProtocol
        withContext(Dispatchers.Main) {
            when (protocol) {
                AutoModeProtocol.SOFTETHER_TCP, AutoModeProtocol.SOFTETHER_UDP ->
                    startService(SoftEtherVpnService.ACTION_DISCONNECT, SoftEtherVpnService::class.java)
                AutoModeProtocol.OPENVPN_TCP, AutoModeProtocol.OPENVPN_UDP -> {
                    ProfileManager.setConntectedVpnProfileDisconnected(context)
                    startService(OpenVPNService.DISCONNECT_VPN, OpenVPNService::class.java)
                }
                AutoModeProtocol.MS_SSTP ->
                    startService(ACTION_VPN_DISCONNECT, SstpVpnService::class.java)
                AutoModeProtocol.L2TP_IPSEC, null -> Unit
            }
        }
        currentProtocol = null
    }

    override suspend fun awaitTunnel(protocol: AutoModeProtocol, timeoutMs: Long): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        activeTunnelWait = deferred
        TunnelStateWatcher.beginAttempt(protocol) { deferred.complete(true) }
        try {
            return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        } finally {
            activeTunnelWait = null
            TunnelStateWatcher.endAttempt()
        }
    }

    /**
     * Interrupt the in-flight attempt for the Try next server button:
     * complete the tunnel wait negatively and tear down the half-open
     * service connection so the controller can move on immediately.
     */
    fun skipCurrent() {
        activeTunnelWait?.complete(false)
        val protocol = currentProtocol
        if (protocol == null) return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            when (protocol) {
                AutoModeProtocol.SOFTETHER_TCP, AutoModeProtocol.SOFTETHER_UDP ->
                    startService(SoftEtherVpnService.ACTION_DISCONNECT, SoftEtherVpnService::class.java)
                AutoModeProtocol.OPENVPN_TCP, AutoModeProtocol.OPENVPN_UDP -> {
                    ProfileManager.setConntectedVpnProfileDisconnected(context)
                    startService(OpenVPNService.DISCONNECT_VPN, OpenVPNService::class.java)
                }
                AutoModeProtocol.MS_SSTP ->
                    startService(ACTION_VPN_DISCONNECT, SstpVpnService::class.java)
                AutoModeProtocol.L2TP_IPSEC, null -> Unit
            }
        }
    }

    @Volatile
    private var activeTunnelWait: CompletableDeferred<Boolean>? = null

    override fun log(message: String) {
        Log.d(TAG, message)
        AutoModeLogStore.add(AutoModeLogStore.Source.AUTO, message)
    }

    // ---- Protocol connectors (mirror the existing manual flows) ----

    private fun connectSoftEther(conn: VPNGateConnection, useTcp: Boolean) {
        val vpnIntent = VpnService.prepare(context)
        if (vpnIntent != null) {
            Log.w(TAG, "VPN permission not granted; cannot connect in background")
            throw AutoModeController.VpnPermissionMissingException()
        }

        val isUdpOnly = conn.isUdpOnly
        val serverPort = when {
            isUdpOnly -> conn.seUdpPort
            useTcp -> conn.seTcpPort
            else -> conn.seUdpPort
        }
        val serverName = conn.getName(!useTcp, true)
        val useDomain = dataUtil.getBooleanSetting(DataUtil.USE_DOMAIN_TO_CONNECT, false)
        val serverHost = if (useDomain) conn.calculateHostName else conn.ip ?: return

        val config = ConnectionConfig(
            serverHost = serverHost,
            serverPort = serverPort,
            username = "vpn",
            password = "vpn",
            virtualHub = "vpngate",
            sessionName = serverName,
            localAddress = "10.21.0.2",
            prefixLength = 19,
            dnsServer = resolvePrimaryDns(),
            secondaryDnsServer = resolveSecondaryDns(),
            routes = listOf(Route("0.0.0.0", 0)),
            mtu = 1500,
            useTcp = useTcp,
            udpPort = conn.seUdpPort,
            udpOnly = isUdpOnly,
            clientProductName = "VPN Gate Connector Pro",
            clientVersion = BuildConfig.VERSION_NAME,
            clientBuild = BuildConfig.VERSION_CODE,
            maxConnections = dataUtil.getSoftEtherMaxConnections(),
        )
        SoftEtherVpnService.notificationTargetActivity =
            vn.unlimit.vpngate.activities.MainActivity::class.java
        val intent = Intent(context, SoftEtherVpnService::class.java).apply {
            action = SoftEtherVpnService.ACTION_CONNECT
            putExtra(SoftEtherVpnService.EXTRA_CONFIG, config)
        }
        startForegroundCompatible(intent, SoftEtherVpnService::class.java)

        dataUtil.setBooleanSetting(DataUtil.LAST_CONNECT_USE_UDP, !useTcp)
        dataUtil.setBooleanSetting(DataUtil.LAST_CONNECT_SOFTETHER_USE_UDP, !useTcp)
        dataUtil.setStringSetting(DataUtil.LAST_CONNECT_METHOD, "softether")
        dataUtil.lastVPNConnection = conn
    }

    private fun connectOpenVpn(conn: VPNGateConnection, useUdp: Boolean) {
        if (VpnService.prepare(context) != null) {
            Log.w(TAG, "VPN permission not granted; cannot connect in background")
            throw AutoModeController.VpnPermissionMissingException()
        }
        val data = if (useUdp) conn.openVpnConfigDataUdp else conn.openVpnConfigData
        if (data.isNullOrEmpty()) {
            Log.w(TAG, "No OpenVPN config payload for ${conn.hostName}")
            return
        }
        try {
            val cp = ConfigParser()
            cp.parseConfig(InputStreamReader(ByteArrayInputStream(data.toByteArray())))
            val profile = cp.convertProfile()
            profile.mName = conn.getName(useUdp)
            profile.mCompatMode = App.VPN_PROFILE_COMPAT_MODE_24X
            if (dataUtil.getBooleanSetting(DataUtil.SETTING_BLOCK_ADS, false) ||
                dataUtil.getBooleanSetting(DataUtil.USE_CUSTOM_DNS, false)
            ) {
                profile.mOverrideDNS = true
                profile.mDNS1 = resolvePrimaryDns()
                profile.mDNS2 = resolveSecondaryDns()
            }
            ProfileManager.setTemporaryProfile(context, profile)
            VPNLaunchHelper.startOpenVpn(profile, context, null, true)
            dataUtil.setBooleanSetting(DataUtil.LAST_CONNECT_USE_UDP, useUdp)
            dataUtil.setStringSetting(DataUtil.LAST_CONNECT_METHOD, "openvpn")
            dataUtil.lastVPNConnection = conn
        } catch (e: Exception) {
            Log.e(TAG, "OpenVPN profile build failed", e)
        }
    }

    private fun connectSstp(conn: VPNGateConnection) {
        if (VpnService.prepare(context) != null) {
            Log.w(TAG, "VPN permission not granted; cannot connect in background")
            throw AutoModeController.VpnPermissionMissingException()
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit()
            .putString(OscPrefKey.HOME_HOSTNAME.toString(), conn.calculateHostName)
            .putString(OscPrefKey.HOME_COUNTRY.toString(), conn.countryShort?.uppercase() ?: "")
            .putString(OscPrefKey.HOME_USERNAME.toString(), "vpn")
            .putString(OscPrefKey.HOME_PASSWORD.toString(), "vpn")
            .putString(OscPrefKey.SSL_PORT.toString(), conn.sstpConnectPort.toString())
            .putStringSet(OscPrefKey.ROUTE_EXCLUDED_APPS.toString(), emptySet())
            .apply()
        SstpVpnService.notificationTargetActivity =
            vn.unlimit.vpngate.activities.MainActivity::class.java
        SstpVpnService.mDisplaySpeed =
            dataUtil.getBooleanSetting(DataUtil.SETTING_NOTIFY_SPEED, true)
        val intent = Intent(context, SstpVpnService::class.java).setAction(ACTION_VPN_CONNECT)
        startForegroundCompatible(intent, SstpVpnService::class.java)
        dataUtil.setStringSetting(DataUtil.LAST_CONNECT_METHOD, "sstp")
        dataUtil.lastVPNConnection = conn
    }

    private fun startService(action: String, service: Class<*>) {
        val intent = Intent(context, service).setAction(action)
        startForegroundCompatible(intent, service)
    }

    private fun startForegroundCompatible(intent: Intent, service: Class<*>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun resolvePrimaryDns(): String = when {
        dataUtil.getBooleanSetting(DataUtil.SETTING_BLOCK_ADS, false) ->
            AppConfig.getString("vpn_dns_block_ads_primary").ifEmpty { "8.8.8.8" }
        dataUtil.getBooleanSetting(DataUtil.USE_CUSTOM_DNS, false) ->
            dataUtil.getStringSetting(DataUtil.CUSTOM_DNS_IP_1, "8.8.8.8") ?: "8.8.8.8"
        else -> "8.8.8.8"
    }

    private fun resolveSecondaryDns(): String = when {
        dataUtil.getBooleanSetting(DataUtil.SETTING_BLOCK_ADS, false) ->
            AppConfig.getString("vpn_dns_block_ads_alternative").ifEmpty { "8.8.4.4" }
        dataUtil.getBooleanSetting(DataUtil.USE_CUSTOM_DNS, false) ->
            dataUtil.getStringSetting(DataUtil.CUSTOM_DNS_IP_2, "8.8.4.4")?.takeIf { it.isNotEmpty() }
                ?: "8.8.4.4"
        else -> "8.8.4.4"
    }
}
