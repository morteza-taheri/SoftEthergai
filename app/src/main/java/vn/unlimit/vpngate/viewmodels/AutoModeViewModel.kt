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
import vn.unlimit.vpngate.automode.ConnectionOrigin
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
 * The synthesized external states carry the protocol the tracker actually
 * names (never a fabricated placeholder); [AutoModeDisplayState.isExternal] is
 * the truthful marker and the screen renders the manual/auto label from
 * [AutoModeState.Connected.origin].
 */
internal fun combineAutoModeState(
    modeState: AutoModeState,
    tunnelStatus: VpnConnectionStatus,
    externalHost: String? = null,
    externalIp: String? = null,
    externalProtocolId: String? = null,
    /**
     * BLOCK v1.0.77 (REQ-ORIGIN-001): the origin persisted at the last verified
     * connect, and the `ip|hostname` key it belongs to. Both are ignored unless
     * the key matches the tunnel the tracker is actually reporting — a record
     * left behind by a previous session can never mislabel a different tunnel.
     */
    persistedOrigin: ConnectionOrigin? = null,
    persistedServerKey: String? = null,
): AutoModeDisplayState {
    if (modeState is AutoModeState.Connected) {
        return AutoModeDisplayState(modeState, tunnelStatus, modeState, true, false)
    }
    if (modeState is AutoModeState.Connecting) {
        return AutoModeDisplayState(modeState, tunnelStatus, modeState, true, false)
    }

    // BLOCK v1.0.75 (D-3): an external tunnel is displayed with its REAL
    // identity from GlobalVpnTracker — host, IP, and the protocol ONLY when the
    // tracker names one concrete transport (never a fabricated placeholder).
    //
    // BLOCK v1.0.77 (REQ-ORIGIN-001): the origin is normally MANUAL (the app did
    // not start this tunnel), but if the process was restarted while a tunnel
    // the APP established stayed up, the persisted record proves the app did
    // start it — and then the honest label is Auto Connect / Auto Reconnect, not
    // Manual. The record is trusted only for the tunnel it names.
    val resolvedOrigin = persistedOriginFor(
        origin = persistedOrigin,
        serverKey = persistedServerKey,
        ip = externalIp,
        hostname = externalHost,
    )
    val external = when (tunnelStatus) {
        VpnConnectionStatus.CONNECTED -> AutoModeState.Connected(
            hostname = externalHost,
            ip = externalIp,
            protocol = AutoModeProtocol.fromTrackerId(externalProtocolId),
            speed = 0L,
            ping = 0,
            origin = resolvedOrigin,
        )
        VpnConnectionStatus.CONNECTING -> AutoModeState.Connecting(
            hostname = externalHost,
            ip = externalIp,
            protocol = AutoModeProtocol.fromTrackerId(externalProtocolId),
            speed = 0L,
            ping = 0,
            attempt = 0,
            total = 0,
            origin = resolvedOrigin,
        )
        else -> null
    }
    if (external != null) {
        return AutoModeDisplayState(modeState, tunnelStatus, external, true, true)
    }

    return AutoModeDisplayState(modeState, tunnelStatus, modeState, false, false)
}

/**
 * BLOCK v1.0.77 (REQ-ORIGIN-001) — may a persisted origin label the LIVE tunnel?
 *
 * The record is `(origin, "ip|hostname")`, written at the moment a tunnel was
 * verified and cleared on an explicit disconnect. It is trusted only when its
 * key matches the tunnel the tracker is reporting right now: the IP is the
 * stable identity (the tracker's hostname may be absent or differ), with the
 * hostname as the fallback when no IP is available. Anything else — no record,
 * a MANUAL record, an empty key, or a key that names another server — degrades
 * to [ConnectionOrigin.MANUAL], which is exactly today's behaviour and cannot
 * mislabel a tunnel the app did not start.
 */
internal fun persistedOriginFor(
    origin: ConnectionOrigin?,
    serverKey: String?,
    ip: String?,
    hostname: String?,
): ConnectionOrigin {
    if (origin == null || origin == ConnectionOrigin.MANUAL) return ConnectionOrigin.MANUAL
    val recorded = serverKey?.trim().orEmpty()
    if (recorded.isEmpty()) return ConnectionOrigin.MANUAL
    val recordedIp = recorded.substringBefore('|').trim()
    val recordedHost = recorded.substringAfter('|', "").trim()
    val liveIp = ip?.trim().orEmpty()
    val liveHost = hostname?.trim().orEmpty()
    val matches = when {
        recordedIp.isNotEmpty() && liveIp.isNotEmpty() -> recordedIp == liveIp
        recordedHost.isNotEmpty() && liveHost.isNotEmpty() ->
            recordedHost.equals(liveHost, ignoreCase = true)
        else -> false
    }
    return if (matches) origin else ConnectionOrigin.MANUAL
}

/**
 * BLOCK v1.0.68 PART A — whether "Try Next Server" must be enabled for a given
 * *display* state. Derived from the same combined [AutoModeDisplayState.display]
 * the hero button renders from, so an external/manual tunnel (controller
 * `Disconnected`, display `Connected(isExternal=true)`) keeps the button enabled;
 * the controller's `skipToNextServer()` already routes that case through
 * `skipFromExternal` (AutoModeController.kt:890).
 *
 * Enabled for Connecting / Connected / Error; disabled only when the display is
 * genuinely Disconnected.
 */
internal fun tryNextEnabled(display: AutoModeState): Boolean =
    display !is AutoModeState.Disconnected

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
            ) { modeState, global ->
                combineAutoModeState(
                    modeState,
                    global.status,
                    externalHost = global.serverHost,
                    externalIp = global.serverIp,
                    externalProtocolId = global.protocol,
                    // BLOCK v1.0.77 (REQ-ORIGIN-001): read at combine time, so a
                    // process restart with a live tunnel still answers "why is
                    // the tunnel up" with the truth.
                    persistedOrigin = dataUtil.getLastConnectionOrigin(),
                    persistedServerKey = dataUtil.getLastConnectionServerKey(),
                )
            }
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
