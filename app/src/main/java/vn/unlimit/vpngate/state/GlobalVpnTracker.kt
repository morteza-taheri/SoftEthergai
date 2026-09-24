package vn.unlimit.vpngate.state

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.preference.PreferenceManager
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.VpnStatus
import kittoku.osc.preference.OscPrefKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import vn.unlimit.softether.SoftEtherVpnService
import vn.unlimit.vpngate.automode.TunnelStateWatcher

/**
 * High-level connection states across all VPN protocols.
 */
enum class VpnConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * Global snapshot of the current active VPN connection in the application.
 */
data class GlobalVpnState(
    val status: VpnConnectionStatus = VpnConnectionStatus.DISCONNECTED,
    val protocol: String? = null,
    val serverHost: String? = null,
    val serverIp: String? = null,
    val assignedIp: String? = null,
    val errorMessage: String? = null,
)

/**
 * App-wide centralized tracker for VPN connection states across
 * SoftEther, OpenVPN, and SSTP protocols.
 */
object GlobalVpnTracker : SoftEtherVpnService.StateListener, VpnStatus.StateListener {

    private val _vpnState = MutableStateFlow(GlobalVpnState())
    val vpnState: StateFlow<GlobalVpnState> = _vpnState.asStateFlow()

    val currentStatus: VpnConnectionStatus
        get() = _vpnState.value.status

    val isConnected: Boolean
        get() = _vpnState.value.status == VpnConnectionStatus.CONNECTED

    val isConnecting: Boolean
        get() = _vpnState.value.status == VpnConnectionStatus.CONNECTING

    private var prefs: SharedPreferences? = null
    private var isInitialized = false

    private val sstpListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == OscPrefKey.ROOT_STATE.toString()) {
            val rootState = prefs?.getBoolean(OscPrefKey.ROOT_STATE.toString(), false) == true
            if (rootState) {
                val ip = prefs?.getString(OscPrefKey.HOME_CONNECTED_IP.toString(), "")
                updateState(
                    VpnConnectionStatus.CONNECTED,
                    protocol = "SSTP",
                    serverIp = ip
                )
            } else if (_vpnState.value.protocol == "SSTP" && _vpnState.value.status == VpnConnectionStatus.CONNECTED) {
                updateState(VpnConnectionStatus.DISCONNECTED)
            }
        }
    }

    private var vpnNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var appContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true
        appContext = context.applicationContext
        prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        prefs?.registerOnSharedPreferenceChangeListener(sstpListener)
        SoftEtherVpnService.addStateListener(this)
        VpnStatus.addStateListener(this)
        registerSystemVpnWatcher(context.applicationContext)
    }

    private fun registerSystemVpnWatcher(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d("GlobalVpnTracker", "System VPN network available: $network")
                }

                override fun onLost(network: Network) {
                    Log.d("GlobalVpnTracker", "System VPN network lost: $network")
                    mainHandler.postDelayed({
                        val hasVpnTransport = cm.allNetworks.any { net ->
                            runCatching {
                                cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                            }.getOrDefault(false)
                        }
                        if (!hasVpnTransport && currentStatus == VpnConnectionStatus.CONNECTED) {
                            Log.w("GlobalVpnTracker", "All system VPN transports lost while connected! Triggering fast disconnect.")
                            TunnelStateWatcher.onTunnelLost?.invoke()
                            updateState(VpnConnectionStatus.DISCONNECTED)
                            runCatching {
                                val ctx = appContext ?: return@runCatching
                                val seIntent = Intent(ctx, SoftEtherVpnService::class.java).apply {
                                    action = SoftEtherVpnService.ACTION_DISCONNECT
                                }
                                ctx.startService(seIntent)
                            }
                        }
                    }, 500)
                }
            }
            vpnNetworkCallback = callback
            cm.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Log.e("GlobalVpnTracker", "Failed to register VPN NetworkCallback", e)
        }
    }

    fun updateState(
        status: VpnConnectionStatus,
        protocol: String? = _vpnState.value.protocol,
        serverHost: String? = _vpnState.value.serverHost,
        serverIp: String? = _vpnState.value.serverIp,
        assignedIp: String? = _vpnState.value.assignedIp,
        errorMessage: String? = null
    ) {
        _vpnState.value = GlobalVpnState(
            status = status,
            protocol = if (status == VpnConnectionStatus.DISCONNECTED) null else protocol,
            serverHost = if (status == VpnConnectionStatus.DISCONNECTED) null else serverHost,
            serverIp = if (status == VpnConnectionStatus.DISCONNECTED) null else serverIp,
            assignedIp = if (status == VpnConnectionStatus.DISCONNECTED) null else assignedIp,
            errorMessage = errorMessage
        )
    }

    override fun onSoftEtherStateChanged(state: String, assignedIp: String) {
        when (state) {
            SoftEtherVpnService.STATE_CONNECTED -> {
                updateState(
                    VpnConnectionStatus.CONNECTED,
                    protocol = "SoftEther",
                    assignedIp = assignedIp
                )
            }
            SoftEtherVpnService.STATE_CONNECTING,
            SoftEtherVpnService.STATE_TLS_HANDSHAKE,
            SoftEtherVpnService.STATE_PROTOCOL_HANDSHAKE -> {
                updateState(
                    VpnConnectionStatus.CONNECTING,
                    protocol = "SoftEther"
                )
            }
            SoftEtherVpnService.STATE_DISCONNECTED -> {
                if (_vpnState.value.protocol == "SoftEther") {
                    updateState(VpnConnectionStatus.DISCONNECTED)
                }
            }
            SoftEtherVpnService.STATE_ERROR -> {
                if (_vpnState.value.protocol == "SoftEther") {
                    updateState(VpnConnectionStatus.ERROR, errorMessage = "SoftEther error")
                }
            }
        }
    }

    override fun setConnectedVPN(uuid: String?) {}

    override fun updateState(
        state: String,
        logmessage: String,
        localizedResId: Int,
        status: ConnectionStatus,
        intent: android.content.Intent?,
    ) {
        when (status) {
            ConnectionStatus.LEVEL_CONNECTED -> {
                updateState(
                    VpnConnectionStatus.CONNECTED,
                    protocol = "OpenVPN"
                )
            }
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED,
            ConnectionStatus.LEVEL_START,
            ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT -> {
                updateState(
                    VpnConnectionStatus.CONNECTING,
                    protocol = "OpenVPN"
                )
            }
            ConnectionStatus.LEVEL_NOTCONNECTED -> {
                if (_vpnState.value.protocol == "OpenVPN") {
                    updateState(VpnConnectionStatus.DISCONNECTED)
                }
            }
            ConnectionStatus.LEVEL_AUTH_FAILED -> {
                if (_vpnState.value.protocol == "OpenVPN") {
                    updateState(VpnConnectionStatus.ERROR, errorMessage = "Auth failed")
                }
            }
            else -> Unit
        }
    }
}
