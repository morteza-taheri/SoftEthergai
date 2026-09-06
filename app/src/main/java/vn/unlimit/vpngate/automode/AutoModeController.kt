package vn.unlimit.vpngate.automode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Auto Mode orchestration core (§27): a thin layer on top of the app's
 * existing connection stack. It never opens tunnels itself — the
 * [ConnectionAdapter] drives the real services — it only filters and
 * orders candidates, tries them one by one (§9), enforces one-attempt-
 * per-server (§10), waits for a *verified tunnel* (§14) with a
 * timeout (§11) and exposes a StateFlow for the UI.
 *
 * Pure JVM: unit-testable without Android.
 */
class AutoModeController(
    private val scope: CoroutineScope,
    private val adapter: ConnectionAdapter,
    /** §24 protocol is read from Settings at the moment of start. */
    private val protocolProvider: () -> AutoModeProtocol,
    /** Candidate list source (the in-app collector repository cache). */
    private val serverProvider: suspend () -> List<AutoModeCandidate>,
    /** Called when a tunnel is verified so the caller can persist it (§19). */
    private val onSuccess: (AutoModeCandidate, AutoModeProtocol) -> Unit = { _, _ -> },
    /** Per-attempt timeout in ms (§11). */
    private val attemptTimeoutMs: Long = DEFAULT_ATTEMPT_TIMEOUT_MS,
) {
    interface ConnectionAdapter {
        /** Initiate the connection for [candidate] via [protocol]. */
        suspend fun connect(candidate: AutoModeCandidate, protocol: AutoModeProtocol)

        /** Clean up any half-open tunnel (§15). */
        suspend fun disconnect()

        /**
         * Wait until the VPN tunnel is genuinely up (§14) or the
         * timeout elapses. Returns true only for a verified tunnel.
         * Must be cancellation-cooperative (§12).
         */
        suspend fun awaitTunnel(protocol: AutoModeProtocol, timeoutMs: Long): Boolean

        /** Debug logging sink (§26). */
        fun log(message: String)
    }

    companion object {
        const val DEFAULT_ATTEMPT_TIMEOUT_MS = 25_000L
        const val ERROR_NO_SERVER = "no_compatible_server"
        const val ERROR_VPN_PERMISSION = "vpn_permission_missing"
    }

    /**
     * Raised by the connection adapter when the OS-level VPN permission
     * is not granted — retrying other servers is pointless, the run must
     * stop with a dedicated error (Auto Mode §12/§18).
     */
    class VpnPermissionMissingException : RuntimeException("VPN permission not granted")

    private val _state = MutableStateFlow<AutoModeState>(AutoModeState.Disconnected)
    val state: StateFlow<AutoModeState> = _state

    /**
     * Optional UI-side hook fired on every state transition (used by the
     * engine to mirror the state into the status notification). Kept as a
     * plain callback so the controller stays JVM-unit-testable.
     */
    var onStateChange: ((AutoModeState) -> Unit)? = null
        set(value) {
            field = value
        }

    private fun setState(state: AutoModeState) {
        _state.value = state
        onStateChange?.invoke(state)
    }

    /** §13 single-job guard. */
    var job: Job? = null
        private set

    val isRunning: Boolean
        get() = job?.isActive == true

    /**
     * User-requested skip of the current server (Try next server button).
     * - While Connecting: aborts the in-flight attempt and moves on.
     * - While Connected (the run is done but the connected watcher is
     *   armed): disconnects the live tunnel and starts a fresh run over
     *   the remaining candidates.
     * Outside a live run it just logs and returns.
     */
    private val skipRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    fun skipToNextServer() {
        val current = _state.value
        val connecting = current is AutoModeState.Connecting && isRunning
        val connectedArmed = current is AutoModeState.Connected && connectedWatcher?.isActive == true
        if (!connecting && !connectedArmed) {
            adapter.log("[AUTO] Skip requested but no attempt in flight")
            return
        }
        adapter.log("[AUTO] Skipping current server; trying next")
        skipRequested.set(true)
        if (connecting) {
            adapterSkipSignal?.invoke()
        }
    }

    /** Lets the adapter interrupt a blocked connect/await (e.g. cancel the service attempt). */
    private var adapterSkipSignal: (() -> Unit)? = null

    fun setSkipSignal(signal: (() -> Unit)?) {
        adapterSkipSignal = signal
    }

    /**
     * Remaining compatible candidates of the current run, from the server
     * AFTER the currently connected one — used when the user skips a
     * CONNECTED server to continue with the next candidates without
     * retrying the ones already attempted.
     */
    @Volatile
    private var remainingCandidates: List<AutoModeCandidate> = emptyList()

    /**
     * While CONNECTED the main run has finished; this light watcher waits
     * for the user to press Try next server (skip flag), then tears down
     * the tunnel and starts a fresh run over the remaining candidates.
     */
    private fun startConnectedWatcher(protocol: AutoModeProtocol) {
        connectedWatcher?.cancel()
        connectedWatcher = scope.launch {
            while (currentCoroutineContext().isActive && !skipRequested.get()) {
                kotlinx.coroutines.delay(300)
            }
            if (!currentCoroutineContext().isActive) return@launch
            if (skipRequested.get()) {
                skipRequested.set(false)
                adapter.log("[AUTO] Connected server skipped by user; trying next")
                adapter.disconnect() // §15 teardown of the live tunnel
                val remaining = remainingCandidates
                // Detach this watcher before starting the next run so the
                // fresh watcher (after the next success) is not self-cancelled.
                connectedWatcher = null
                if (remaining.isEmpty()) {
                    adapter.log("[AUTO] No more servers to try")
                    setState(AutoModeState.Error(ERROR_NO_SERVER))
                    job = null
                } else {
                    // Restart the loop over the remaining candidates only.
                    overrideServers = remaining
                    start()
                }
            }
        }
    }

    /** When set, the next runAutoMode uses this candidate list instead of the provider. */
    @Volatile
    private var overrideServers: List<AutoModeCandidate>? = null

    private var connectedWatcher: kotlinx.coroutines.Job? = null

    fun start() {
        if (isRunning) {
            adapter.log("[AUTO] start ignored: already running")
            return
        }
        job = scope.launch { runAutoMode() }
    }

    /** §12: cancel everything; the loop stops at the next suspension point. */
    fun stop() {
        connectedWatcher?.cancel()
        connectedWatcher = null
        skipRequested.set(false)
        if (!isRunning) return
        adapter.log("[AUTO] User requested stop")
        job?.cancel()
        job = null
        setState(AutoModeState.Disconnected)
    }

    /** §3 button semantics across the four states. */
    fun onButtonPressed() {
        when (_state.value) {
            is AutoModeState.Disconnected, is AutoModeState.Error -> start()
            is AutoModeState.Connecting -> stop()
            is AutoModeState.Connected -> disconnectNow()
        }
    }

        /** §3/§29-Test10: pressing while Connected disconnects via the normal flow. */
    fun disconnectNow() {
        // Kill the connected watcher too — the user chose to end the session,
        // not to move to the next server.
        connectedWatcher?.cancel()
        connectedWatcher = null
        skipRequested.set(false)
        if (isRunning) {
            // Cancel the active run, then clean up the tunnel.
            job?.cancel()
            job = null
            scope.launch { adapter.disconnect() }
        } else {
            scope.launch { adapter.disconnect() }
        }
        setState(AutoModeState.Disconnected)
    }

    /** §7 filter + §8 ordering. Visible for tests. */
    fun compatibleServers(
        all: List<AutoModeCandidate>,
        protocol: AutoModeProtocol,
    ): List<AutoModeCandidate> = all
        .filter { protocol.supports(it) }
        .sortedWith(byQuality)

            private suspend fun runAutoMode() {
        val protocol = protocolProvider()
        adapter.log("[AUTO] Started")
        adapter.log("[AUTO] Protocol = ${protocol.id}")
        adapter.log("[AUTO] Connection timeout = ${attemptTimeoutMs / 1000} seconds")

        val all = overrideServers ?: serverProvider()
        overrideServers = null
        val servers = compatibleServers(all, protocol)
        adapter.log("[AUTO] Servers available = ${all.size}")
        adapter.log("[AUTO] Compatible servers = ${servers.size}")
        adapter.log("[AUTO] Sorted by quality")

        if (servers.isEmpty()) {
            adapter.log("[AUTO] No compatible server found")
            setState(AutoModeState.Error(ERROR_NO_SERVER))
            return
        }

        val attempted = mutableSetOf<String>() // §10
        var attempt = 0

        for (server in servers) {
            if (!currentCoroutineContext().isActive) return // §12 stop

            val key = "${server.ip}|${server.hostname ?: ""}"
            if (!attempted.add(key)) continue
            attempt++

            adapter.log("[AUTO] Trying #$attempt ${server.hostname ?: server.ip}")
            skipRequested.set(false)
            setState(
                AutoModeState.Connecting(
                    hostname = server.hostname,
                    ip = server.ip,
                    protocol = protocol,
                    speed = server.speed,
                    ping = server.ping,
                    attempt = attempt,
                    total = servers.size,
                )
            )

            try {
                adapter.connect(server, protocol)
            } catch (e: VpnPermissionMissingException) {
                // Without the OS VPN permission no server can ever connect.
                adapter.log("[AUTO] VPN permission missing; stopping Auto Mode")
                adapter.disconnect()
                setState(AutoModeState.Error(ERROR_VPN_PERMISSION))
                job = null
                return
            } catch (e: Exception) {
                adapter.log("[AUTO] Connect threw ${e.javaClass.simpleName}: ${e.message}")
                adapter.disconnect() // §15 cleanup before next attempt
                continue
            }
            val connected = adapter.awaitTunnel(protocol, attemptTimeoutMs)

            if (!currentCoroutineContext().isActive) return // §12 stop

            if (skipRequested.get()) {
                // User pressed Try next server: clean up and move on without
                // counting this as a terminal outcome.
                adapter.log("[AUTO] Server skipped by user")
                skipRequested.set(false)
                adapter.disconnect() // §15 cleanup before next attempt
                continue
            }

            if (connected) {
                adapter.log("[AUTO] Tunnel established")
                adapter.log("[AUTO] Connected successfully")
                onSuccess(server, protocol)
                // Remember the remaining (not-yet-attempted) candidates so a
                // user skip while connected continues with the next server.
                remainingCandidates = servers.dropWhile { it !== server }.drop(1)
                setState(
                    AutoModeState.Connected(
                        hostname = server.hostname,
                        ip = server.ip,
                        protocol = protocol,
                        speed = server.speed,
                        ping = server.ping,
                    )
                )
                startConnectedWatcher(protocol)
                return
            }

            adapter.log("[AUTO] Connection failed: ${server.hostname ?: server.ip}")
            adapter.disconnect() // §15 cleanup before next attempt
        }

        adapter.log("[AUTO] All compatible servers failed")
        setState(AutoModeState.Error(ERROR_NO_SERVER)) // §18
        job = null // terminal state: the run is over; next press restarts cleanly
    }

    /** §8: Speed DESC first, ping/sessions/score/sources as tie-breakers; §23 zero-speed demoted. */
    private val byQuality =
        compareByDescending<AutoModeCandidate> { it.speed > 0 }
            .thenByDescending { it.speed }
            .thenBy { it.ping }
            .thenBy { it.sessions }
            .thenByDescending { it.score }
            .thenByDescending { it.sources }

    /** §7 capability rules. */
    private fun AutoModeProtocol.supports(c: AutoModeCandidate): Boolean = when (this) {
        AutoModeProtocol.SOFTETHER_TCP -> c.seTcpPort > 0
        AutoModeProtocol.SOFTETHER_UDP -> c.seUdpSupported || c.seUdpPort > 0
        AutoModeProtocol.OPENVPN_TCP -> c.openVpnTcpPort > 0
        AutoModeProtocol.OPENVPN_UDP -> c.openVpnUdpPort > 0
        AutoModeProtocol.L2TP_IPSEC -> c.l2tpSupported
        AutoModeProtocol.MS_SSTP -> c.sstpSupported
    }
}

