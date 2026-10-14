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
import vn.unlimit.softether.TunnelHealth
import vn.unlimit.vpngate.automode.TunnelStateWatcher

/**
 * High-level connection states across all VPN protocols.
 *
 * This is the app-wide single source of truth for "is there a tunnel right
 * now". Every producer (VPN services, Auto Connect, the UI) reports into
 * [GlobalVpnTracker] and every consumer reads from it, so the service, Auto
 * Mode and the UI can never disagree about the connection.
 */
enum class VpnConnectionStatus {
    DISCONNECTED,
    CONNECTING,

    /** A tunnel was verified as up. Never set merely because Connect was pressed. */
    CONNECTED,

    /** A teardown is in progress; the tunnel is not usable yet but not gone either. */
    DISCONNECTING,
    ERROR;

    /**
     * True while a connection attempt is in flight. Auto Connect uses this to
     * refuse a duplicate connection instead of dialling a second server.
     */
    val isBusy: Boolean get() = this == CONNECTING || this == DISCONNECTING
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

    /**
     * R4b: the system VPN [Network] the watcher last saw, or null when there
     * is none.
     *
     * Before this field existed, `onAvailable`'s `network` argument was used
     * only for a log line and then dropped — the v1.0.11 audit recorded that
     * as the Axis B defect "the captured Network is discarded". It is now
     * retained purely so a collector request can be bound to it via
     * `network.socketFactory` (§7 Q2). Nothing else consumes it.
     *
     * Volatile because it is written from a ConnectivityManager callback
     * thread and read from the collector's IO dispatchers.
     */
    @Volatile
    private var systemVpnNetwork: Network? = null

    private var appContext: Context? = null

    /**
     * Lazily built, NOT eagerly: this object's initializer runs on any first
     * reference, and `Looper.getMainLooper()` does not exist on a plain JVM.
     * Building it eagerly made *any* reference to `GlobalVpnTracker` from a
     * unit test fail class initialization — which is exactly what happened
     * when R4b's `VpnServerRepository` default routing started reading this
     * object, breaking 19 pre-existing repository tests. Nothing here is read
     * before `registerSystemVpnWatcher`, so laziness is free.
     */
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    // ─────────────────────────────────────────────────────────────────────
    // R4a (v1.0.11 audit §4.4 / §5.4): tunnel health counters.
    //
    // The audit established that the app had NO RX counter and NO TUN-EIO
    // counter, which made `isTunnelUsable()` — already decided in
    // SERVER_SOURCE_ARCHITECTURE.md §7 Q1 (USER-DIRECTED REQUIREMENT) —
    // NOT IMPLEMENTABLE. This lifts that blocker.
    //
    // Storage lives in the top-level `TunnelHealth` object (SoftEtherClient
    // module), NOT here: the TUN read loop that observes EIO lives in
    // SoftEtherClient, and `app → SoftEtherClient` is the only module
    // dependency direction (SoftEtherClient cannot see `app`). This class
    // delegates and keeps the public API.
    //
    // IMPORTANT SCOPE: the counters are NOT yet wired to real traffic in
    // this phase. Nothing calls [noteRxAdvance]; the RX advance must come
    // from the data-forwarding path (a separate phase that touches
    // ConnectionController/SoftEtherClient — neither authorized here).
    // [noteTunEio] IS called — from TunTerminal's new EIO branch (R5).
    // Until RX is wired, [isTunnelUsable] reports false because no RX
    // advance has ever been observed, which is the safe direction.
    // ─────────────────────────────────────────────────────────────────────

    /** Process-wide monotonic RX counter (read-only snapshot). */
    fun rxCounter(): Long = TunnelHealth.rxCounter()

    /** Wall-clock ms of the most recent RX advance; 0 if never observed. */
    fun lastRxAdvanceAtMs(): Long = TunnelHealth.lastRxAdvanceAtMs()

    /** Process-wide monotonic TUN-EIO counter. */
    fun tunEioCount(): Long = TunnelHealth.tunEioCount()

    /** Wall-clock ms of the most recent TUN EIO; 0 if never observed. */
    fun lastEioAtMs(): Long = TunnelHealth.lastEioAtMs()

    /** Record one RX advance (increments the counter and stamps the clock). */
    fun noteRxAdvance() = TunnelHealth.noteRxAdvance()

    /** Record one TUN EIO read failure. */
    fun noteTunEio() = TunnelHealth.noteTunEio()

    /**
     * Whether the tunnel is *usable* (not merely CONNECTED).
     *
     * Implements `SERVER_SOURCE_ARCHITECTURE.md` §7 Q1 exactly: all three
     * of (a) state == CONNECTED, (b) an RX advance within
     * [TunnelHealth.RX_WINDOW_MS], (c) no TUN EIO within
     * [TunnelHealth.EIO_WINDOW_MS]. Any missing evidence
     * fails closed — an unobserved RX advance and a never-observed EIO both
     * read as "not usable", never as a false positive.
     *
     * The decision itself lives in [TunnelHealth.isUsable] as a pure function so
     * it can be unit-tested on the JVM; this method only supplies the two live
     * inputs (the tracked connection state and the current wall clock).
     */
    fun isTunnelUsable(): Boolean =
        TunnelHealth.isUsable(isConnected, System.currentTimeMillis())

    /** Exposed for the unit tests written for R4a. */
    internal fun resetHealthCountersForTest() = TunnelHealth.resetHealthCountersForTest()

    // ─────────────────────────────────────────────────────────────────────
    // R4b — collector transport binding inputs.
    //
    // Two accessors, both read-only views of state this class already owns.
    // Neither changes any state-machine behaviour: the first reports a
    // reference the watcher already captured, the second reports the user's
    // opt-in. They exist so `VpnServerRepository` can make its open-vs-VPN
    // decision without this class knowing the collector exists.
    // ─────────────────────────────────────────────────────────────────────

    /**
     * The system VPN [Network] the watcher last observed, or null.
     *
     * A caller uses `network.socketFactory` on it to bind one request
     * (`SERVER_SOURCE_ARCHITECTURE.md` §7 Q2). `bindProcessToNetwork()` is
     * never used: binding the whole process would make every other socket
     * in the app — including the tunnel's own control channel — depend on a
     * network that can disappear underneath it.
     */
    fun currentVpnNetwork(): Network? = systemVpnNetwork

    /**
     * The R4b user opt-in: send collector requests over the VPN when one is
     * usable. **Default false**, which keeps open as the default transport
     * exactly as it was before R4b.
     *
     * Read as a raw preference rather than through `AppConfig` so it can be
     * flipped without shipping a config value, and read defensively so a JVM
     * unit test — where [prefs] is null — simply reports "not opted in".
     */
    fun prefersVpnForCollector(): Boolean =
        runCatching { prefs?.getBoolean(PREF_ALWAYS_PREFER_VPN, false) == true }
            .getOrDefault(false)

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
                    // R4b: retain the reference so a collector request can be
                    // bound to it. See [systemVpnNetwork].
                    systemVpnNetwork = network
                }

                override fun onLost(network: Network) {
                    Log.d("GlobalVpnTracker", "System VPN network lost: $network")
                    if (systemVpnNetwork == network) systemVpnNetwork = null
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

    /**
     * Publishes a connection *attempt*.
     *
     * Pressing Connect is not the same as being connected: this sets CONNECTING
     * and leaves the state there until a stack actually reports an established
     * tunnel, at which point the normal listeners move it to CONNECTED. That is
     * what stops the UI from claiming a working VPN the moment the button is
     * tapped.
     */
    fun reportConnecting(protocol: String, serverHost: String? = null, serverIp: String? = null) {
        // A tunnel that is already up is not "connecting" — a second attempt
        // must not overwrite the truth with a stale intermediate state.
        if (currentStatus == VpnConnectionStatus.CONNECTED) return
        updateState(
            status = VpnConnectionStatus.CONNECTING,
            protocol = protocol,
            serverHost = serverHost,
            serverIp = serverIp,
        )
    }

    /**
     * Publishes a teardown, so a consumer that asks "can I start connecting?"
     * during the gap gets "no, wait" instead of racing a half-closed tunnel.
     */
    fun reportDisconnecting() {
        updateState(VpnConnectionStatus.DISCONNECTING)
    }

    /** Convenience for callers that only need the boolean. */
    val isBusy: Boolean get() = currentStatus.isBusy

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

/**
 * R4b preference key: always prefer VPN for collector requests.
 *
 * Absent or false means open, which is the pre-R4b default. Declared at file
 * scope rather than in a companion because `GlobalVpnTracker` is a standalone
 * `object` and cannot carry one.
 */
internal const val PREF_ALWAYS_PREFER_VPN = "vpn_always_prefer_vpn"
