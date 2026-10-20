package vn.unlimit.vpngate.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.automode.AutoModeEngine
import vn.unlimit.vpngate.automode.AutoModeProtocol
import vn.unlimit.vpngate.automode.AutoModeState
import vn.unlimit.vpngate.state.GlobalVpnTracker
import vn.unlimit.vpngate.state.VpnConnectionStatus
import vn.unlimit.vpngate.utils.DataUtil

/**
 * AUTOCONNECT-DEVICE-BUGS-FIX (spec §1.6.32): the state the Auto Mode hero
 * button renders from.
 *
 * The button used to render from [AutoModeState] alone. That state is written
 * only by `AutoModeController.setState`, so a tunnel established outside an
 * Auto Mode run — a manual connect, or a service-level reconnect — left the
 * button showing "Disconnected" while the tunnel was up, and the tap then hit
 * the D-4 refusal (`start refused: a connection is already in progress`).
 *
 * This type combines the Auto Mode state with the app-wide
 * [GlobalVpnTracker] snapshot, mirroring the reconciliation
 * `HomeScreen.activeServerOf()` already performs for the list highlight.
 */
data class AutoModeDisplayState(
    val modeState: AutoModeState,
    val tunnelStatus: VpnConnectionStatus,
    /** The state the hero button renders from. */
    val display: AutoModeState,
    /** Whether a tap should disconnect rather than start. */
    val tapMeansDisconnect: Boolean,
    /** True when the tunnel is up but no Auto Mode run owns it. */
    val isExternal: Boolean,
)

/**
 * The combining rule, extracted as a pure function so it is JVM-testable
 * without Android. Mirrors `HomeScreen.activeServerOf()`:
 *
 *  - an Auto Mode run owns the display while it is [AutoModeState.Connected]
 *    or [AutoModeState.Connecting], even if the tracker has not caught up;
 *  - a live tunnel is the truth even after an [AutoModeState.Error];
 *  - otherwise a live external tunnel is surfaced as Connected/Connecting with
 *    [AutoModeDisplayState.isExternal] set.
 *
 * The synthesized external states carry `protocol = SOFTETHER_TCP` as a
 * display-only placeholder; [AutoModeDisplayState.isExternal] is the truthful
 * marker and the screen renders "Connected (manual)" for it.
 */
internal fun combineAutoModeState(
    modeState: AutoModeState,
    tunnelStatus: VpnConnectionStatus,
): AutoModeDisplayState {
    if (modeState is AutoModeState.Connected) {
        return AutoModeDisplayState(modeState, tunnelStatus, modeState, true, false)
    }
    if (modeState is AutoModeState.Connecting) {
        return AutoModeDisplayState(modeState, tunnelStatus, modeState, true, false)
    }

    val external = when (tunnelStatus) {
        VpnConnectionStatus.CONNECTED -> AutoModeState.Connected(
            hostname = null,
            ip = null,
            protocol = AutoModeProtocol.SOFTETHER_TCP,
            speed = 0L,
            ping = 0,
        )
        VpnConnectionStatus.CONNECTING -> AutoModeState.Connecting(
            hostname = null,
            ip = null,
            protocol = AutoModeProtocol.SOFTETHER_TCP,
            speed = 0L,
            ping = 0,
            attempt = 0,
            total = 0,
        )
        else -> null
    }
    if (external != null) {
        return AutoModeDisplayState(modeState, tunnelStatus, external, true, true)
    }

    return AutoModeDisplayState(modeState, tunnelStatus, modeState, false, false)
}

/**
 * Thin bridge between [AutoModeEngine] and the Auto Mode screen.
 * The engine is a process-wide singleton so the run survives
 * fragment navigation.
 */
class AutoModeViewModel(application: Application) : AndroidViewModel(application) {

    val dataUtil: DataUtil = App.instance!!.dataUtil!!

    val state = MutableLiveData<AutoModeState>(AutoModeState.Disconnected)

    private val _displayState = MutableLiveData(
        combineAutoModeState(AutoModeState.Disconnected, VpnConnectionStatus.DISCONNECTED),
    )

    /**
     * AUTOCONNECT-DEVICE-BUGS-FIX (BUG-1): the combined state the hero button
     * renders from — the Auto Mode run when one owns the tunnel, otherwise the
     * app-wide tracker's truth.
     */
    val displayState: LiveData<AutoModeDisplayState> = _displayState

    init {
        viewModelScope.launch {
            AutoModeEngine.state().collectLatest { state.value = it }
        }
        viewModelScope.launch {
            combine(
                AutoModeEngine.stateFlow,
                GlobalVpnTracker.vpnState,
            ) { modeState, global -> combineAutoModeState(modeState, global.status) }
                .collectLatest { _displayState.value = it }
        }
    }

    fun onButtonPressed() = AutoModeEngine.onButtonPressed()

    /** Try next server button: skip the in-flight attempt. */
    fun tryNextServer() = AutoModeEngine.skipToNextServer()

    /**
     * AUTOCONNECT-DEVICE-BUGS-FIX (BUG-1): tear down a tunnel that no Auto Mode
     * run owns.
     *
     * [onButtonPressed] cannot do this: with the run in Disconnected/Error its
     * D-4 guard sees a busy tunnel and refuses to act, which is correct for a
     * start but leaves the user no way to disconnect from this screen.
     * `stopAndDisconnect` issues the teardown unconditionally.
     */
    fun disconnectExternalTunnel() = AutoModeEngine.stopAndDisconnect()

    /**
     * AUTOCONNECT-DEVICE-BUGS-FIX (BUG-3): remember that the battery prompt has
     * been shown, so it does not repeat on every press.
     */
    fun onBatteryDialogDismissed() = dataUtil.setAskedBatteryOptimization()

    /** Whether the battery prompt has already been shown once. */
    fun hasAskedBatteryOptimization(): Boolean = dataUtil.hasAskedBatteryOptimization()
}
