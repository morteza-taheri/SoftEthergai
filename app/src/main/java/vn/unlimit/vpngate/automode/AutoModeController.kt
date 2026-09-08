package vn.unlimit.vpngate.automode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Auto Mode orchestration core:
 * Filters and orders server candidates by quality, executes connection attempts
 * through the user's enabled Protocol Priority Profile, enforces single active attempt,
 * handles Try Next Server without resetting back to #1, and manages transitions cleanly.
 *
 * Pure JVM: unit-testable without Android framework dependencies.
 */
class AutoModeController(
    private val scope: CoroutineScope,
    private val adapter: ConnectionAdapter,
    /** Provider for user-enabled protocols in prioritized order */
    private val protocolPriorityProvider: () -> List<AutoModeProtocol>,
    /** Candidate list source */
    private val serverProvider: suspend () -> List<AutoModeCandidate>,
    /** Called when a tunnel is verified so the caller can persist it */
    private val onSuccess: (AutoModeCandidate, AutoModeProtocol) -> Unit = { _, _ -> },
    /** Per-attempt timeout in ms */
    private val attemptTimeoutMs: Long = DEFAULT_ATTEMPT_TIMEOUT_MS,
) {
    interface ConnectionAdapter {
        suspend fun connect(candidate: AutoModeCandidate, protocol: AutoModeProtocol)
        suspend fun disconnect()
        suspend fun awaitTunnel(protocol: AutoModeProtocol, timeoutMs: Long): Boolean
        fun log(message: String)
    }

    companion object {
        const val DEFAULT_ATTEMPT_TIMEOUT_MS = 25_000L
        const val ERROR_NO_SERVER = "no_compatible_server"
        const val ERROR_VPN_PERMISSION = "vpn_permission_missing"
    }

    class VpnPermissionMissingException : RuntimeException("VPN permission not granted")

    private val _state = MutableStateFlow<AutoModeState>(AutoModeState.Disconnected)
    val state: StateFlow<AutoModeState> = _state

    var onStateChange: ((AutoModeState) -> Unit)? = null

    private fun setState(state: AutoModeState) {
        _state.value = state
        onStateChange?.invoke(state)
    }

    var job: Job? = null
        private set

    val isRunning: Boolean
        get() = job?.isActive == true

    /**
     * Independent state trackers
     */
    @Volatile
    var currentServerIndex: Int = 0
        private set

    @Volatile
    var currentServer: AutoModeCandidate? = null
        private set

    @Volatile
    var currentProtocol: AutoModeProtocol? = null
        private set

    @Volatile
    var sortedCandidates: List<AutoModeCandidate> = emptyList()
        private set

    /** Skip signal to cancel in-flight attempt and proceed to next server */
    private val skipServerRequested = AtomicBoolean(false)
    private var adapterSkipSignal: (() -> Unit)? = null

    fun setSkipSignal(signal: (() -> Unit)?) {
        adapterSkipSignal = signal
    }

    private var connectedWatcher: Job? = null

    /**
     * User-requested skip to the next server (Try Next Server).
     * - Advances currentServerIndex (e.g., #5 -> #6)
     * - Resets protocol priority on the new server to priority #1
     * - Never resets back to #1
     */
    fun skipToNextServer() {
        val current = _state.value
        adapter.log("[AUTO] Try Next Server requested (current server index: $currentServerIndex)")

        val isConnecting = current is AutoModeState.Connecting && isRunning
        val isConnected = current is AutoModeState.Connected
        val isError = current is AutoModeState.Error

        if (!isConnecting && !isConnected && !isError) {
            adapter.log("[AUTO] Try Next Server ignored: Disconnected or inactive")
            return
        }

        if (isConnecting) {
            adapter.log("[AUTO] Skipping current in-flight attempt; advancing to next server")
            skipServerRequested.set(true)
            adapterSkipSignal?.invoke()
            return
        }

        if (isConnected) {
            adapter.log("[AUTO] Disconnecting active tunnel and advancing to next server")
            connectedWatcher?.cancel()
            connectedWatcher = null
            TunnelStateWatcher.onTunnelLost = null
            job?.cancel()
            job = null
            scope.launch {
                adapter.disconnect()
                currentServerIndex++
                if (currentServerIndex >= sortedCandidates.size) {
                    adapter.log("[AUTO] No more servers to try. End of list reached.")
                    setState(AutoModeState.Error(ERROR_NO_SERVER))
                } else {
                    startInternal(resumeFromCurrentIndex = true)
                }
            }
            return
        }

        if (isError) {
            adapter.log("[AUTO] Error state: advancing to next server")
            currentServerIndex++
            if (currentServerIndex < sortedCandidates.size) {
                startInternal(resumeFromCurrentIndex = true)
            } else {
                adapter.log("[AUTO] No more servers remaining")
                setState(AutoModeState.Error(ERROR_NO_SERVER))
            }
        }
    }

    fun start() {
        if (isRunning) {
            adapter.log("[AUTO] start ignored: already running")
            return
        }
        currentServerIndex = 0
        startInternal(resumeFromCurrentIndex = false)
    }

    private fun startInternal(resumeFromCurrentIndex: Boolean) {
        job?.cancel()
        job = scope.launch {
            runAutoMode(resumeFromCurrentIndex)
        }
    }

    fun stop() {
        connectedWatcher?.cancel()
        connectedWatcher = null
        TunnelStateWatcher.onTunnelLost = null
        skipServerRequested.set(false)
        if (!isRunning) return
        adapter.log("[AUTO] User requested stop")
        job?.cancel()
        job = null
        setState(AutoModeState.Disconnected)
    }

    fun onButtonPressed() {
        when (_state.value) {
            is AutoModeState.Disconnected, is AutoModeState.Error -> {
                currentServerIndex = 0
                start()
            }
            is AutoModeState.Connecting -> stop()
            is AutoModeState.Connected -> disconnectNow()
        }
    }

    fun disconnectNow() {
        connectedWatcher?.cancel()
        connectedWatcher = null
        TunnelStateWatcher.onTunnelLost = null
        skipServerRequested.set(false)
        if (isRunning) {
            job?.cancel()
            job = null
        }
        scope.launch { adapter.disconnect() }
        setState(AutoModeState.Disconnected)
    }

    private suspend fun runAutoMode(resumeFromCurrentIndex: Boolean) {
        val protocols = protocolPriorityProvider()
        adapter.log("AUTO_CONNECT_START")
        adapter.log("[AUTO] Enabled Protocols in Priority Order: ${protocols.joinToString { it.id }}")
        adapter.log("[AUTO] Connection timeout: ${attemptTimeoutMs / 1000}s per attempt")

        if (protocols.isEmpty()) {
            adapter.log("[AUTO] No protocols enabled in Protocol Priority Profile")
            setState(AutoModeState.Error(ERROR_NO_SERVER))
            return
        }

        if (!resumeFromCurrentIndex || sortedCandidates.isEmpty()) {
            val all = serverProvider()
            adapter.log("[AUTO] Total Servers Loaded = ${all.size}")
            // Quality sorting
            sortedCandidates = all.sortedWith(byQuality)
            if (!resumeFromCurrentIndex) {
                currentServerIndex = 0
            }
        }

        val servers = sortedCandidates
        adapter.log("[AUTO] Quality-sorted servers available = ${servers.size}")

        if (servers.isEmpty() || currentServerIndex >= servers.size) {
            adapter.log("[AUTO] No servers available to connect")
            setState(AutoModeState.Error(ERROR_NO_SERVER))
            return
        }

        val totalServers = servers.size

        while (currentServerIndex < totalServers) {
            if (!currentCoroutineContext().isActive) return

            val server = servers[currentServerIndex]
            currentServer = server
            val serverDisplay = server.hostname ?: server.ip ?: "Server #${currentServerIndex + 1}"
            adapter.log("[AUTO] === Server #${currentServerIndex + 1}/$totalServers: $serverDisplay ===")

            skipServerRequested.set(false)
            var connectedOnThisServer = false

            // On each server, evaluate protocols strictly in user's Priority Order starting from #1
            for ((protoIdx, protocol) in protocols.withIndex()) {
                if (!currentCoroutineContext().isActive) return
                if (skipServerRequested.get()) {
                    adapter.log("[AUTO] User skipped server $serverDisplay")
                    break
                }

                currentProtocol = protocol

                // Capability check: does this server support this protocol?
                if (!protocol.supports(server)) {
                    adapter.log("[AUTO] Server #${currentServerIndex + 1} | Protocol ${protocol.id}: Unsupported -> Skipping")
                    continue
                }

                adapter.log("[AUTO] Server #${currentServerIndex + 1} | Protocol ${protocol.id} (Priority #${protoIdx + 1}): Attempting...")

                setState(
                    AutoModeState.Connecting(
                        hostname = server.hostname,
                        ip = server.ip,
                        protocol = protocol,
                        speed = server.speed,
                        ping = server.ping,
                        attempt = currentServerIndex + 1,
                        total = totalServers,
                    )
                )

                try {
                    adapter.connect(server, protocol)
                } catch (_: VpnPermissionMissingException) {
                    adapter.log("[AUTO] VPN permission missing; stopping Auto Mode")
                    adapter.disconnect()
                    setState(AutoModeState.Error(ERROR_VPN_PERMISSION))
                    job = null
                    return
                } catch (e: Exception) {
                    adapter.log("[AUTO] Connect error: ${e.message}")
                    adapter.disconnect()
                    continue
                }

                val connected = adapter.awaitTunnel(protocol, attemptTimeoutMs)

                if (!currentCoroutineContext().isActive) return

                if (skipServerRequested.get()) {
                    adapter.log("[AUTO] Attempt interrupted by Try Next Server")
                    adapter.disconnect()
                    break
                }

                if (connected) {
                    adapter.log("[AUTO] Server #${currentServerIndex + 1} | Protocol ${protocol.id}: Connected successfully!")
                    onSuccess(server, protocol)
                    connectedOnThisServer = true
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
                } else {
                    adapter.log("[AUTO] Server #${currentServerIndex + 1} | Protocol ${protocol.id}: Failed (timeout or unreachable)")
                    adapter.disconnect()
                }
            }

            if (connectedOnThisServer) {
                return
            }

            adapter.log("[AUTO] All enabled protocols failed for Server #${currentServerIndex + 1} ($serverDisplay)")
            currentServerIndex++
        }

        adapter.log("[AUTO] No suitable server found. Reached end of candidate list without looping.")
        setState(AutoModeState.Error(ERROR_NO_SERVER))
        job = null
    }

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
                delay(1000)
            }
        }
    }

    /** Quality sorting */
    private val byQuality =
        compareByDescending<AutoModeCandidate> { it.speed > 0 }
            .thenByDescending { it.speed }
            .thenBy { it.ping }
            .thenBy { it.sessions }
            .thenByDescending { it.score }
            .thenByDescending { it.sources }

    /** Protocol capability rules */
    fun AutoModeProtocol.supports(c: AutoModeCandidate): Boolean = when (this) {
        AutoModeProtocol.SOFTETHER_TCP -> c.seTcpPort > 0
        AutoModeProtocol.SOFTETHER_UDP -> c.seUdpSupported || c.seUdpPort > 0
        AutoModeProtocol.OPENVPN_TCP -> c.openVpnTcpPort > 0
        AutoModeProtocol.OPENVPN_UDP -> c.openVpnUdpPort > 0
        AutoModeProtocol.L2TP_IPSEC -> c.l2tpSupported
        AutoModeProtocol.MS_SSTP -> c.sstpSupported
    }
}
