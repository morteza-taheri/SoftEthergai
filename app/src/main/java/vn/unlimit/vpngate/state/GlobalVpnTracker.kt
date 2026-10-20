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
     *
     * Deliberately EXCLUDES [CONNECTED]: this property answers "is a
     * transition in progress", which is a narrower question than "is a tunnel
     * up". Callers that must also refuse an already-established tunnel need
     * [isActiveOrBusy]. See v1.0.32 BUG-A below.
     */
    val isBusy: Boolean get() = this == CONNECTING || this == DISCONNECTING

    /**
     * True when a tunnel is up **or** a transition is in flight — i.e. this
     * process already owns (or is acquiring/releasing) a VPN session.
     *
     * v1.0.32 (AUTOCONNECT-FIX-2, BUG-A): [isBusy] answers only the second
     * half of that question. The v1.0.31 D-4 refusal guard was wired to
     * [isBusy], so a manual tunnel that had *finished* connecting —
     * status [CONNECTED], the single most common shape of the hazard it was
     * written for — was invisible to it. Device evidence
     * (DEVICE_VERIFY_AUTOCONNECT_FIX_REPORT.md §5): with a manual OpenVPN
     * tunnel up and Auto Mode `Disconnected`, pressing the button started a
     * full run (`[AUTO] Started`, `[AUTO] Trying #1`) instead of logging
     * `start refused`, and the run then tore the manual tunnel down.
     *
     * [DISCONNECTING] is included because a half-closed tunnel is not free to
     * take over either: a new native attempt would race the teardown.
     * [ERROR] and [DISCONNECTED] are excluded — nothing is owned in either.
     */
    val isActiveOrBusy: Boolean
        get() = this == CONNECTED || this == CONNECTING || this == DISCONNECTING
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

// ─────────────────────────────────────────────────────────────────────
// A-3 (v1.0.11 audit §3.3): protocol reconciliation.
//
// PROBLEM. `GlobalVpnState` is a single slot, but two stacks publish into it.
// Every terminal branch therefore guarded itself with
// `if (_vpnState.value.protocol == "<me>")`, i.e. it *dropped* the event
// whenever some other protocol happened to own the slot. Dropping is only half
// right: it does suppress a stale teardown from the loser, but it also means a
// teardown from the stack that IS published can be thrown away after a second
// stack overwrote the slot, and it gives no answer at all when two stacks are
// legitimately live. The audit's requirement is to reconcile instead:
//
//   * a DISCONNECTED/ERROR for stack X clears any stale CONNECTED from X;
//   * it must not touch stack Y — in particular it must not publish a
//     DISCONNECTED that says "no tunnel" while Y is still up ("do not clobber
//     Y").
//
// DESIGN. The decision is a pure function over plain data
// ([VpnStackReport] / [GlobalVpnState] carry no Android types), so it is
// unit-testable on the JVM. `GlobalVpnTracker` itself cannot be class-loaded
// in a plain JVM test — its initializer needs `Handler(Looper.getMainLooper())`
// — so the state machine is deliberately NOT inside the `object`. The tracker
// owns the live-stack list and only applies the verdict.
//
// A stack is keyed by its protocol name ("SoftEther", "OpenVPN", "SSTP"):
// Android permits only one app VPN at a time, so the protocol *is* the stack's
// identity. The reconciliation never invents a protocol: an event that arrives
// without one cannot be attributed and is left to the caller's own default.
// ─────────────────────────────────────────────────────────────────────

/**
 * One live stack as seen by the reconciliation ledger.
 *
 * Identity fields are merged from the events that stack published, so a
 * survivor republished after another stack's teardown carries **its own**
 * last-known server/assigned address, not whatever the other stack happened
 * to leave in the shared slot.
 */
internal data class VpnStackReport(
    val protocol: String,
    val serverHost: String? = null,
    val serverIp: String? = null,
    val assignedIp: String? = null,
)

/** A stack's state transition as handed to [VpnStackLedger.reconcile]. */
internal data class VpnStackEvent(
    val status: VpnConnectionStatus,
    val protocol: String?,
    val serverHost: String? = null,
    val serverIp: String? = null,
    val assignedIp: String? = null,
    val errorMessage: String? = null,
)

/**
 * Verdict of one [VpnStackLedger.reconcile] call.
 *
 * @param live the ledger after folding the event in — the caller replaces its
 *   copy with this.
 * @param publish the state to publish, or `null` to leave the currently
 *   published state untouched (a stale event from a stack that owns nothing).
 */
internal data class VpnStackReconciliation(
    val live: List<VpnStackReport>,
    val publish: GlobalVpnState?,
)

/**
 * The A-3 decision, as a pure fold: `(ledger, current, event) -> verdict`.
 *
 * Stateless on purpose — the ledger is passed in and returned, so the same
 * call is trivially reproducible in a test and cannot leak between tests.
 */
internal object VpnStackLedger {

    fun reconcile(
        live: List<VpnStackReport>,
        current: GlobalVpnState,
        event: VpnStackEvent,
    ): VpnStackReconciliation {
        val key = event.protocol
            // Unattributable (the caller passed no protocol and the published
            // state carries none): nothing to reconcile against.
            ?: return VpnStackReconciliation(live, null)

        return when (event.status) {
            VpnConnectionStatus.CONNECTED -> {
                // A verified tunnel becomes (or refreshes) a live stack and
                // takes the slot — last writer wins, exactly as before A-3.
                val previous = live.lastOrNull { it.protocol == key }
                val refreshed = VpnStackReport(
                    protocol = key,
                    serverHost = event.serverHost ?: previous?.serverHost,
                    serverIp = event.serverIp ?: previous?.serverIp,
                    assignedIp = event.assignedIp ?: previous?.assignedIp,
                )
                VpnStackReconciliation(
                    live = live.filterNot { it.protocol == key } + refreshed,
                    publish = GlobalVpnState(
                        status = VpnConnectionStatus.CONNECTED,
                        protocol = key,
                        serverHost = refreshed.serverHost,
                        serverIp = refreshed.serverIp,
                        assignedIp = refreshed.assignedIp,
                        errorMessage = null,
                    ),
                )
            }

            // Not stack-lifetime events: an attempt is in flight or being torn
            // down, so the stack stays live and the caller's identity is
            // published verbatim — the pre-A-3 behaviour, unchanged.
            VpnConnectionStatus.CONNECTING,
            VpnConnectionStatus.DISCONNECTING -> VpnStackReconciliation(
                live = live,
                publish = GlobalVpnState(
                    status = event.status,
                    protocol = key,
                    serverHost = event.serverHost ?: current.serverHost,
                    serverIp = event.serverIp ?: current.serverIp,
                    assignedIp = event.assignedIp ?: current.assignedIp,
                    errorMessage = event.errorMessage,
                ),
            )

            VpnConnectionStatus.DISCONNECTED,
            VpnConnectionStatus.ERROR -> {
                val nextLive = live.filterNot { it.protocol == key }
                val survivor = nextLive.lastOrNull()
                if (survivor != null) {
                    // X is gone but Y is still up. Publishing X's teardown here
                    // would assert "no tunnel" while a working one keeps
                    // running — the "do not clobber Y" the audit forbids — so Y
                    // is republished from its OWN recorded identity.
                    VpnStackReconciliation(
                        live = nextLive,
                        publish = GlobalVpnState(
                            status = VpnConnectionStatus.CONNECTED,
                            protocol = survivor.protocol,
                            serverHost = survivor.serverHost,
                            serverIp = survivor.serverIp,
                            assignedIp = survivor.assignedIp,
                            errorMessage = null,
                        ),
                    )
                } else if (current.protocol == key) {
                    // Nothing else is live and the stack that went away is the
                    // one the UI is showing, so the teardown is published:
                    // identity preserved for ERROR, blanked for DISCONNECTED —
                    // exactly the pre-A-3 result for the single-stack case.
                    val blank = event.status == VpnConnectionStatus.DISCONNECTED
                    VpnStackReconciliation(
                        live = nextLive,
                        publish = GlobalVpnState(
                            status = event.status,
                            protocol = if (blank) null else current.protocol,
                            serverHost = if (blank) null else current.serverHost,
                            serverIp = if (blank) null else current.serverIp,
                            assignedIp = if (blank) null else current.assignedIp,
                            errorMessage = event.errorMessage,
                        ),
                    )
                } else {
                    // A teardown from a stack that neither owns the published
                    // state nor left a survivor is stale by construction: the
                    // event is accounted for (X leaves the ledger) and the
                    // published state is left alone. Dropping the *publication*
                    // — not the bookkeeping — is the reconciliation.
                    VpnStackReconciliation(nextLive, null)
                }
            }
        }
    }
}

/**
 * App-wide centralized tracker for VPN connection states across
 * SoftEther, OpenVPN, and SSTP protocols.
 */
object GlobalVpnTracker : SoftEtherVpnService.StateListener, VpnStatus.StateListener {

    private val _vpnState = MutableStateFlow(GlobalVpnState())
    val vpnState: StateFlow<GlobalVpnState> = _vpnState.asStateFlow()

    /**
     * A-3: the stacks currently believed to be up, oldest first.
     *
     * Written only from [updateState], which reads every producer's event on
     * whichever thread that producer runs on (service callbacks, OpenVPN's
     * listener, the SSTP preference listener). CopyOnWriteArrayList keeps the
     * list itself consistent across those threads without a lock on the hot
     * path; the read-modify-write window is the same as the pre-A-3 code's.
     */
    private val liveStacks = java.util.concurrent.CopyOnWriteArrayList<VpnStackReport>()

    val currentStatus: VpnConnectionStatus
        get() = _vpnState.value.status

    val isConnected: Boolean
        get() = _vpnState.value.status == VpnConnectionStatus.CONNECTED

    val isConnecting: Boolean
        get() = _vpnState.value.status == VpnConnectionStatus.CONNECTING

    /**
     * v1.0.41 (§1.6.34 / operator authorisation A2): wall-clock ms of the
     * transition **into** [VpnConnectionStatus.CONNECTED]; `0` while no
     * tunnel is up.
     *
     * Stamped only on the transition, not on every republish of a
     * CONNECTED state: the ledger republishes a surviving stack when the
     * other stack tears down, and re-stamping there would silently move
     * the anchor forward.
     *
     * **Not consumed by the routing decision.** [isTunnelUsableForRouting]
     * derives its grace window from "no RX observed yet and no recent
     * TUN EIO", which needs no start time. This field exists because it is
     * the correct anchor for a future *time-bounded* grace period and
     * because a device log wants to show how long a tunnel had been up
     * when the collector declined to use it. See §1.6.34 bullet 3.
     */
    @Volatile
    private var connectedAtMsValue: Long = 0L

    /**
     * v1.0.41 (§1.6.34 / A2): when the current CONNECTED state began.
     *
     * `internal` rather than `private` because the diagnostic consumer
     * lives in `VpnServerRepository`, the same module. A `0` return means
     * "no tunnel is currently connected", which is also what a JVM test
     * sees without any Android runtime.
     */
    internal fun connectedAtMs(): Long = connectedAtMsValue

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
    // v1.0.41 (§1.6.34): the RX advance IS wired. R4b added
    // TunnelHealth.noteRxAdvance() at ConnectionController.kt:1157 — one
    // call per successfully-received batch — so [isTunnelUsable] reflects
    // real traffic and no longer reports false unconditionally. The note
    // that stood here from v1.0.11 to v1.0.40 ("Nothing calls
    // [noteRxAdvance]") was stale and is corrected in this revision.
    // [noteTunEio] is called from TunTerminal's EIO branch (R5).
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

    /**
     * v1.0.41 (§1.6.34): the traffic-path routing signal.
     *
     * [isTunnelUsable] requires an RX advance inside
     * [TunnelHealth.RX_WINDOW_MS]. Immediately after CONNECTED no frame has
     * been received yet, so the strict check reports `false` for a tunnel
     * that is in fact healthy — and routing a user who explicitly opted
     * into `PREF_ALWAYS_PREFER_VPN` to OPEN in that window is a visible
     * regression. This wrapper therefore applies the strict check once
     * traffic has been observed, and a grace period before that.
     *
     * Decision order (fail-closed at every branch that lacks evidence):
     *   1. not connected → false
     *   2. no OS-bound VPN network → false
     *   3. [isTunnelUsable] → true (real traffic, no recent EIO)
     *   4. `rxCounter > 0` → false (traffic was observed, then stopped)
     *   5. a TUN EIO inside [TunnelHealth.EIO_WINDOW_MS] → false
     *   6. otherwise → true (grace: nothing observed yet, no EIO)
     *
     * Step 4 is what keeps the grace period from masking a tunnel that
     * came up, carried traffic, and then died: once the counter has moved
     * the grace branch is no longer reachable, so the only way back to
     * `true` is a fresh RX advance.
     *
     * The decision itself lives in the file-scope pure function
     * [routingUsableFromSignals] so it is unit-testable on the JVM without
     * class-loading this Android-dependent object.
     */
    fun isTunnelUsableForRouting(): Boolean = routingUsableFromSignals(
        isConnected = isConnected,
        hasBoundNetwork = currentVpnNetwork() != null,
        isUsable = isTunnelUsable(),
        rxCounter = TunnelHealth.rxCounter(),
        lastEioAtMs = TunnelHealth.lastEioAtMs(),
        nowMs = System.currentTimeMillis(),
    )

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
                    // A-2 (§3.2): this re-check used to wait 500 ms, which meant a
                    // lost system VPN stayed published as CONNECTED for half a
                    // second — long enough for the UI to look healthy and for
                    // Auto Mode's 1 s poll (Axis A-4, path B) to read a stale
                    // "connected" and skip its own loss handling.
                    //
                    // The delay is deliberately NOT removed: during a handover
                    // (Wi-Fi → mobile) `onLost` for the old network fires before
                    // `onAvailable` for the new one, so an immediate check would
                    // see no VPN transport at all and issue a spurious
                    // ACTION_DISCONNECT that tears down a tunnel that is merely
                    // being re-parented. A short grace period keeps that case
                    // safe while shrinking the stale-CONNECTED window 10x.
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
                    }, VPN_LOST_RECHECK_MS)
                }
            }
            vpnNetworkCallback = callback
            cm.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Log.e("GlobalVpnTracker", "Failed to register VPN NetworkCallback", e)
        }
    }

    /**
     * Publishes a state transition from any producer.
     *
     * A-3 (§3.3): instead of letting each terminal caller guard itself with
     * "is the published protocol mine?", every event is folded into the
     * live-stack ledger by [VpnStackLedger.reconcile], which decides both the
     * new ledger and what (if anything) should be published. A stack that goes
     * away no longer depends on still owning the shared slot to be recorded,
     * and a surviving stack is republished instead of being clobbered by the
     * other stack's teardown.
     */
    fun updateState(
        status: VpnConnectionStatus,
        protocol: String? = _vpnState.value.protocol,
        serverHost: String? = _vpnState.value.serverHost,
        serverIp: String? = _vpnState.value.serverIp,
        assignedIp: String? = _vpnState.value.assignedIp,
        errorMessage: String? = null
    ) {
        val verdict = VpnStackLedger.reconcile(
            live = liveStacks.toList(),
            current = _vpnState.value,
            event = VpnStackEvent(
                status = status,
                protocol = protocol,
                serverHost = serverHost,
                serverIp = serverIp,
                assignedIp = assignedIp,
                errorMessage = errorMessage,
            ),
        )
        liveStacks.clear()
        liveStacks.addAll(verdict.live)
        verdict.publish?.let {
            _vpnState.value = it
            // v1.0.41 (§1.6.34 / A2): anchor the CONNECTED transition. Only a
            // *transition* stamps it — the A-3 ledger republishes a surviving
            // stack when the other one tears down, and re-stamping there would
            // silently move the anchor forward. Only the timestamp is touched
            // here; what gets published is still decided entirely by the ledger
            // above, so A-3's behaviour is unchanged.
            when (it.status) {
                VpnConnectionStatus.CONNECTED ->
                    if (connectedAtMsValue == 0L) {
                        connectedAtMsValue = System.currentTimeMillis()
                    }
                VpnConnectionStatus.DISCONNECTED,
                VpnConnectionStatus.ERROR -> connectedAtMsValue = 0L
                else -> Unit
            }
        }
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

    /**
     * v1.0.32 (BUG-A): "this process owns a tunnel, or is acquiring/releasing
     * one". This is the predicate the Auto Connect D-4 refusal guard needs.
     */
    val isActiveOrBusy: Boolean get() = currentStatus.isActiveOrBusy

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
                // A-3: attributed to the SoftEther stack rather than dropped
                // when another protocol happens to own the published slot. The
                // ledger decides whether that clears the slot, republishes a
                // surviving stack, or is a stale no-op.
                updateState(
                    VpnConnectionStatus.DISCONNECTED,
                    protocol = "SoftEther",
                )
            }
            SoftEtherVpnService.STATE_ERROR -> {
                updateState(
                    VpnConnectionStatus.ERROR,
                    protocol = "SoftEther",
                    errorMessage = "SoftEther error",
                )
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
                // A-3: same attribution fix as the SoftEther branch above.
                updateState(
                    VpnConnectionStatus.DISCONNECTED,
                    protocol = "OpenVPN",
                )
            }
            ConnectionStatus.LEVEL_AUTH_FAILED -> {
                updateState(
                    VpnConnectionStatus.ERROR,
                    protocol = "OpenVPN",
                    errorMessage = "Auth failed",
                )
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

/**
 * A-2 (§3.2): grace period before `GlobalVpnTracker`'s system-VPN watcher
 * re-checks whether any VPN transport survives.
 *
 * Was `500` ms, which kept a dead tunnel published as CONNECTED for half a
 * second. Now 50 ms: long enough to absorb a network handover (the new
 * network's `onAvailable` lands in that window), short enough that a genuine
 * loss is published inside a single frame budget. See the comment on the
 * `onLost` callback for why the delay is reduced rather than removed.
 *
 * `internal const` (not `private`) so unit tests can pin the bound without
 * class-loading the Android-dependent `GlobalVpnTracker` object: a `const val`
 * is inlined at the call site by the Kotlin compiler, so referencing it from a
 * JVM test costs nothing and initializes nothing.
 */
internal const val VPN_LOST_RECHECK_MS = 50L

/**
 * v1.0.41 (§1.6.34): the pure decision behind
 * [GlobalVpnTracker.isTunnelUsableForRouting].
 *
 * File-scope rather than a member of the object for the same reason
 * [VPN_LOST_RECHECK_MS] is file-scope: `GlobalVpnTracker`'s initializer needs
 * the Android runtime (`Handler(Looper.getMainLooper())`), so a JVM test
 * cannot call a member without stubbing it. Every input here is a parameter;
 * nothing is read from global state.
 *
 * @param isConnected the tracked connection state is CONNECTED.
 * @param hasBoundNetwork a VPN `Network` is available for the VPN rung.
 * @param isUsable [TunnelHealth.isUsable] already answered `true`.
 * @param rxCounter process-wide RX advance count (`0` = nothing observed yet).
 * @param lastEioAtMs wall-clock ms of the most recent TUN EIO (`0` = never).
 * @param nowMs the clock the EIO window is measured against.
 *
 * Fail-closed: every branch that lacks evidence returns `false`, except the
 * explicit grace branch (no RX ever observed *and* no recent EIO), which is
 * the entire point of the wrapper. The EIO comparison uses `<=` where
 * [TunnelHealth.isUsable] uses `<`, so at the exact boundary this function is
 * the stricter of the two — the safe direction.
 */
internal fun routingUsableFromSignals(
    isConnected: Boolean,
    hasBoundNetwork: Boolean,
    isUsable: Boolean,
    rxCounter: Long,
    lastEioAtMs: Long,
    nowMs: Long,
): Boolean {
    if (!isConnected) return false
    if (!hasBoundNetwork) return false
    if (isUsable) return true
    if (rxCounter > 0L) return false
    if (lastEioAtMs > 0L && nowMs - lastEioAtMs <= TunnelHealth.EIO_WINDOW_MS) return false
    return true
}
