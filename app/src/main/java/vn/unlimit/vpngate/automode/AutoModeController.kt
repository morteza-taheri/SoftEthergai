package vn.unlimit.vpngate.automode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import vn.unlimit.vpngate.state.GlobalVpnTracker

/**
 * Auto Mode orchestration core (Â§27): a thin layer on top of the app's
 * existing connection stack. It never opens tunnels itself â€” the
 * [ConnectionAdapter] drives the real services â€” it only filters and
 * orders candidates, tries them one by one (Â§9), enforces one-attempt-
 * per-server (Â§10), waits for a *verified tunnel* (Â§14) with a
 * timeout (Â§11) and exposes a StateFlow for the UI.
 *
 * Pure JVM: unit-testable without Android.
 */
class AutoModeController(
    private val scope: CoroutineScope,
    private val adapter: ConnectionAdapter,
    /** Â§24 protocol is read from Settings at the moment of start. */
    private val protocolProvider: () -> AutoModeProtocol,

    /** Protocol priority profile (ordered). Falls back to [protocolProvider] when empty. */
    private val protocolPriorityProvider: () -> List<AutoModeProtocol> =
        { listOf(protocolProvider()) },
    /** Candidate list source (the in-app collector repository cache). */
    private val serverProvider: suspend () -> List<AutoModeCandidate>,
    /** Called when a tunnel is verified so the caller can persist it (Â§19). */
    private val onSuccess: (AutoModeCandidate, AutoModeProtocol) -> Unit = { _, _ -> },
    /** Per-attempt timeout in ms (Â§11). */
    private val attemptTimeoutMs: Long = DEFAULT_ATTEMPT_TIMEOUT_MS,
    /** Optional pre-filter to eliminate blocked candidates via fast reachability testing. */
    private val reachabilityFilter: (suspend (List<AutoModeCandidate>, AutoModeProtocol) -> List<AutoModeCandidate>)? = null,
) {
    interface ConnectionAdapter {
        /** Initiate the connection for [candidate] via [protocol]. */
        suspend fun connect(candidate: AutoModeCandidate, protocol: AutoModeProtocol)

        /** Clean up any half-open tunnel (Â§15). */
        suspend fun disconnect()

        /**
         * Wait until the VPN tunnel is genuinely up (Â§14) or the
         * timeout elapses. Returns true only for a verified tunnel.
         * Must be cancellation-cooperative (Â§12).
         */
        suspend fun awaitTunnel(protocol: AutoModeProtocol, timeoutMs: Long): Boolean

        /** Debug logging sink (Â§26). */
        fun log(message: String)
    }

    companion object {
        const val DEFAULT_ATTEMPT_TIMEOUT_MS = 25_000L
        const val ERROR_NO_SERVER = "no_compatible_server"
        const val ERROR_VPN_PERMISSION = "vpn_permission_missing"
    }

    /**
     * Raised by the connection adapter when the OS-level VPN permission
     * is not granted â€” retrying other servers is pointless, the run must
     * stop with a dedicated error (Auto Mode Â§12/Â§18).
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

    /** Â§13 single-job guard. */
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
        val isConnected = current is AutoModeState.Connected
        val isError = current is AutoModeState.Error

        if (!connecting && !isConnected && !isError) {
            adapter.log("[AUTO] Skip requested but disconnected or inactive")
            return
        }

        if (connecting) {
            adapter.log("[AUTO] Skipping current server; trying next")
            skipRequested.set(true)
            adapterSkipSignal?.invoke()
            return
        }

        if (isConnected) {
            val currentIp = (current as? AutoModeState.Connected)?.ip
            adapter.log("[AUTO] Connected server skipped by user; trying next")
            connectedWatcher?.cancel()
            connectedWatcher = null
            TunnelStateWatcher.onTunnelLost = null
            job?.cancel()
            job = null
            scope.launch {
                adapter.disconnect()
                val remaining = remainingCandidates.ifEmpty {
                    val all = (overrideServers ?: serverProvider()).sortedWith(byQuality)
                    if (currentIp != null) all.filterNot { it.ip == currentIp } else all
                }
                if (remaining.isEmpty()) {
                    adapter.log("[AUTO] No more servers to try")
                    setState(AutoModeState.Error(ERROR_NO_SERVER))
                } else {
                    overrideServers = remaining
                    start()
                }
            }
            return
        }

        if (isError) {
            adapter.log("[AUTO] Error state: advancing to next server")
            val remaining = remainingCandidates
            if (remaining.isNotEmpty()) {
                overrideServers = remaining
                start()
            } else {
                adapter.log("[AUTO] No remaining servers; restarting from top")
                start()
            }
        }
    }

    /** Lets the adapter interrupt a blocked connect/await (e.g. cancel the service attempt). */
    private var adapterSkipSignal: (() -> Unit)? = null

    fun setSkipSignal(signal: (() -> Unit)?) {
        adapterSkipSignal = signal
    }

    /**
     * Remaining compatible candidates of the current run, from the server
     * AFTER the currently connected one â€” used when the user skips a
     * CONNECTED server to continue with the next candidates without
     * retrying the ones already attempted.
     */
    @Volatile
    private var remainingCandidates: List<AutoModeCandidate> = emptyList()

    /**
     * While CONNECTED the main run has finished; this watcher monitors for
     * external tunnel disconnects / revocations.
     */
    private fun startConnectedWatcher(protocol: AutoModeProtocol) {
        connectedWatcher?.cancel()
        TunnelStateWatcher.onTunnelLost = {
            if (_state.value is AutoModeState.Connected) {
                adapter.log("[AUTO] Tunnel disconnected externally / revoked by system")
                connectedWatcher?.cancel()
                connectedWatcher = null
                TunnelStateWatcher.onTunnelLost = null
                setState(AutoModeState.Disconnected)
            }
        }
        connectedWatcher = scope.launch {
            while (currentCoroutineContext().isActive) {
                kotlinx.coroutines.delay(1000)
                if (_state.value is AutoModeState.Connected && !GlobalVpnTracker.isConnected) {
                    adapter.log("[AUTO] GlobalVpnTracker confirmed disconnected")
                    connectedWatcher?.cancel()
                    connectedWatcher = null
                    TunnelStateWatcher.onTunnelLost = null
                    setState(AutoModeState.Disconnected)
                    break
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

    /** Â§12: cancel everything; the loop stops at the next suspension point. */
    fun stop() {
        connectedWatcher?.cancel()
        connectedWatcher = null
        TunnelStateWatcher.onTunnelLost = null
        skipRequested.set(false)
        overrideServers = null
        if (!isRunning) return
        adapter.log("[AUTO] User requested stop")
        job?.cancel()
        job = null
        setState(AutoModeState.Disconnected)
    }

    /** Â§3 button semantics across the four states. */
    fun onButtonPressed() {
        when (_state.value) {
            is AutoModeState.Disconnected, is AutoModeState.Error -> {
                overrideServers = null
                start()
            }
            is AutoModeState.Connecting -> stop()
            is AutoModeState.Connected -> disconnectNow()
        }
    }

    /** Â§3/Â§29-Test10: pressing while Connected disconnects via the normal flow. */
    fun disconnectNow() {
        connectedWatcher?.cancel()
        connectedWatcher = null
        TunnelStateWatcher.onTunnelLost = null
        skipRequested.set(false)
        overrideServers = null
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

    /** Â§7 filter + Â§8 ordering. Visible for tests. */
    fun compatibleServers(
        all: List<AutoModeCandidate>,
        protocol: AutoModeProtocol,
    ): List<AutoModeCandidate> = all
        .filter { protocol.supports(it) }
        .sortedWith(byQuality)


    private suspend fun runAutoMode() {
        // Protocol priority profile: ordered list of enabled protocols.
        // Empty profile falls back to the single default protocol.
        val priorities = protocolPriorityProvider().ifEmpty { listOf(protocolProvider()) }
        adapter.log("[AUTO] Started")
        adapter.log("[AUTO] Protocols (priority) = ${priorities.joinToString(" > ") { it.id }}")
        adapter.log("[AUTO] Connection timeout = ${attemptTimeoutMs / 1000} seconds")

        val all = overrideServers ?: serverProvider()
        overrideServers = null
        // Server ordering is independent of protocol ordering (quality DESC).
        val qualitySorted = all.sortedWith(byQuality)
        adapter.log("[AUTO] Servers available = ${all.size}")
        adapter.log("[AUTO] Sorted by quality")

        val servers = if (reachabilityFilter != null && qualitySorted.isNotEmpty() && priorities.isNotEmpty()) {
            adapter.log("[AUTO] Pre-filtering reachable servers via probe...")
            val primaryProtocol = priorities.first()
            val filtered = try {
                reachabilityFilter.invoke(qualitySorted, primaryProtocol)
            } catch (e: Exception) {
                adapter.log("[AUTO] Reachability probe failed: ${e.message}; using original list")
                qualitySorted
            }
            if (filtered.isNotEmpty()) {
                adapter.log("[AUTO] Reachable servers selected: ${filtered.size}")
                filtered
            } else {
                qualitySorted
            }
        } else {
            qualitySorted
        }

        if (servers.isEmpty()) {
            adapter.log("[AUTO] No compatible server found")
            setState(AutoModeState.Error(ERROR_NO_SERVER))
            return
        }

        val attempted = mutableSetOf<String>()
        var attempt = 0

        for (server in servers) {
            if (!currentCoroutineContext().isActive) return

            val key = "${server.ip}|${server.hostname ?: ""}"
            if (!attempted.add(key)) continue

            // Capability check: only protocols this server actually supports,
            // in profile priority order. Unsupported protocols are never attempted.
            val supported = priorities.filter { it.supports(server) }
            if (supported.isEmpty()) {
                adapter.log("[AUTO] Skipping ${server.hostname ?: server.ip}: no supported protocol")
                continue
            }
            attempt++
            adapter.log("[AUTO] Trying #$attempt ${server.hostname ?: server.ip}")
            rememberRemaining(servers, server)
            skipRequested.set(false)

            var connectedProtocol: AutoModeProtocol? = null
            for (protocol in supported) {
                if (!currentCoroutineContext().isActive) return

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
                    adapter.log("[AUTO] VPN permission missing; stopping Auto Mode")
                    adapter.disconnect()
                    setState(AutoModeState.Error(ERROR_VPN_PERMISSION))
                    job = null
                    return
                } catch (e: Exception) {
                    adapter.log("[AUTO] Connect threw ${e.javaClass.simpleName}: ${e.message}")
                    adapter.disconnect()
                    break // transport-level failure: move to the next server
                }
                val connected = adapter.awaitTunnel(protocol, attemptTimeoutMs)

                if (!currentCoroutineContext().isActive) return

                if (skipRequested.get()) {
                    adapter.log("[AUTO] Server skipped by user")
                    skipRequested.set(false)
                    adapter.disconnect()
                    connectedProtocol = null
                    break // user skip: next SERVER (not next protocol)
                }

                if (connected) {
                    connectedProtocol = protocol
                    break
                }

                adapter.log("[AUTO] Protocol failed: ${protocol.id}")
                adapter.disconnect() // cleanup before the next protocol attempt
            }

            if (connectedProtocol != null) {
                adapter.log("[AUTO] Tunnel established")
                adapter.log("[AUTO] Connected successfully")
                onSuccess(server, connectedProtocol)
                rememberRemaining(servers, server)
                setState(
                    AutoModeState.Connected(
                        hostname = server.hostname,
                        ip = server.ip,
                        protocol = connectedProtocol,
                        speed = server.speed,
                        ping = server.ping,
                    )
                )
                startConnectedWatcher(connectedProtocol)
                return
            }

            adapter.log("[AUTO] Connection failed: ${server.hostname ?: server.ip}")
        }

        adapter.log("[AUTO] All compatible servers failed")
        setState(AutoModeState.Error(ERROR_NO_SERVER)) // no auto loop back to #1
        job = null // terminal state: the run is over; next press restarts cleanly
    }

    /** Store the not-yet-attempted tail so user skip continues from the next server. */
    private fun rememberRemaining(servers: List<AutoModeCandidate>, current: AutoModeCandidate) {
        val idx = servers.indexOfFirst { it.ip == current.ip && it.hostname == current.hostname }
        remainingCandidates = if (idx in 0 until servers.lastIndex) {
            servers.subList(idx + 1, servers.size)
        } else {
            emptyList()
        }
    }
    /** Â§8: Speed DESC first, ping/sessions/score/sources as tie-breakers; Â§23 zero-speed demoted. */
    private val byQuality =
        compareByDescending<AutoModeCandidate> { it.speed > 0 }
            .thenByDescending { it.speed }
            .thenBy { it.ping }
            .thenBy { it.sessions }
            .thenByDescending { it.score }
            .thenByDescending { it.sources }

    /** Â§7 capability rules. */
    private fun AutoModeProtocol.supports(c: AutoModeCandidate): Boolean = when (this) {
        AutoModeProtocol.SOFTETHER_TCP -> c.seTcpPort > 0
        AutoModeProtocol.SOFTETHER_UDP -> c.seUdpSupported || c.seUdpPort > 0
        AutoModeProtocol.OPENVPN_TCP -> c.openVpnTcpPort > 0
        AutoModeProtocol.OPENVPN_UDP -> c.openVpnUdpPort > 0
        AutoModeProtocol.L2TP_IPSEC -> c.l2tpSupported
        AutoModeProtocol.MS_SSTP -> c.sstpSupported
    }
}

