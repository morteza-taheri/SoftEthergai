package vn.unlimit.vpngate.automode

import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.VpnStatus
import kittoku.osc.preference.OscPrefKey
import vn.unlimit.softether.SoftEtherVpnService

/**
 * Bridges the three existing VPN stacks' "tunnel is genuinely up"
 * signals (§14) into a single suspend wait used by Auto Mode:
 * - SoftEther: [SoftEtherVpnService.StateListener] STATE_CONNECTED
 * - OpenVPN:   [VpnStatus.StateListener] LEVEL_CONNECTED
 * - SSTP:      kittoku OSC ROOT_STATE pref flip
 *
 * Register/unregister with [attach]/[detach] from an app-scoped
 * context (not an Activity).
 */
object TunnelStateWatcher : SoftEtherVpnService.StateListener, VpnStatus.StateListener {

    @Volatile
    private var watching: AutoModeProtocol? = null

    @Volatile
    private var onTunnelUp: ((AutoModeProtocol) -> Unit)? = null

    private var prefs: SharedPreferences? = null
    private val sstpListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (watching == AutoModeProtocol.MS_SSTP &&
                key == OscPrefKey.ROOT_STATE.toString() &&
                prefs?.getBoolean(OscPrefKey.ROOT_STATE.toString(), false) == true
            ) {
                onTunnelUp?.invoke(AutoModeProtocol.MS_SSTP)
            }
        }

    fun attach(context: android.content.Context) {
        if (prefs != null) return
        prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        prefs?.registerOnSharedPreferenceChangeListener(sstpListener)
        SoftEtherVpnService.addStateListener(this)
        VpnStatus.addStateListener(this)
    }

    fun detach() {
        prefs?.unregisterOnSharedPreferenceChangeListener(sstpListener)
        prefs = null
        SoftEtherVpnService.removeStateListener(this)
        VpnStatus.removeStateListener(this)
    }

    /** Arm the watcher for one attempt of [protocol]. */
    fun beginAttempt(protocol: AutoModeProtocol, onUp: (AutoModeProtocol) -> Unit) {
        watching = protocol
        onTunnelUp = onUp
    }

    fun endAttempt() {
        watching = null
        onTunnelUp = null
    }

    override fun onSoftEtherStateChanged(state: String, assignedIp: String) {
        if (watching == AutoModeProtocol.SOFTETHER_TCP || watching == AutoModeProtocol.SOFTETHER_UDP) {
            if (state == SoftEtherVpnService.STATE_CONNECTED) {
                onTunnelUp?.invoke(watching!!)
            }
        }
    }

    override fun setConnectedVPN(uuid: String?) {
        // Not used
    }

    override fun updateState(
        state: String,
        logmessage: String,
        localizedResId: Int,
        status: ConnectionStatus,
        intent: android.content.Intent?,
    ) {
        if (watching == AutoModeProtocol.OPENVPN_TCP || watching == AutoModeProtocol.OPENVPN_UDP) {
            if (status == ConnectionStatus.LEVEL_CONNECTED) {
                onTunnelUp?.invoke(watching!!)
            }
        }
    }
}
