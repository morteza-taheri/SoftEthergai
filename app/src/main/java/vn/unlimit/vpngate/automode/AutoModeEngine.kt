package vn.unlimit.vpngate.automode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import vn.unlimit.vpngate.data.model.VpnPortSource
import vn.unlimit.vpngate.models.VPNGateConnection
import vn.unlimit.vpngate.models.VPNGateConnectionList
import vn.unlimit.vpngate.utils.DataUtil

/**
 * App-scoped Auto Mode engine: a single [AutoModeController] instance
 * whose state survives fragment navigation. Candidate list comes from the
 * server dataset the app already stores — Auto Connect consumes it and never
 * stores it (see [defaultServers]).
 */
object AutoModeEngine {

    @Volatile
    private var controller: AutoModeController? = null

    private val _state = MutableStateFlow<AutoModeState>(AutoModeState.Disconnected)

    /**
     * Observable Auto Mode state that exists even before the engine is
     * created, so read-only consumers (the server list highlight) can follow
     * the run without triggering [ensure].
     */
    val stateFlow: StateFlow<AutoModeState> = _state

    fun state() = ensure().state

    fun isRunning(): Boolean = ensure().isRunning

    fun start() = ensure().start()

    fun stop() = ensure().stop()

    fun onButtonPressed() = ensure().onButtonPressed()

    fun stopAndDisconnect() {
        ensure().disconnectNow()
    }

    fun skipToNextServer() {
        ensure().skipToNextServer()
    }

    fun ensure(dataUtil: DataUtil? = null, serversProvider: (suspend () -> List<AutoModeCandidate>)? = null): AutoModeController {
        controller?.let { return it }
        synchronized(this) {
            controller?.let { return it }
            val du = dataUtil ?: vn.unlimit.vpngate.App.instance!!.dataUtil!!
            val adapter = AndroidConnectionAdapter(vn.unlimit.vpngate.App.instance!!, du)
            TunnelStateWatcher.attach(vn.unlimit.vpngate.App.instance!!)
            AutoModeModuleLogTap.attach(vn.unlimit.vpngate.App.instance!!)
                        val created = AutoModeController(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                adapter = adapter,
                // §6 Protocol Priority Profile read from Settings at start. This
                // is the single source of truth for which protocol Auto Connect
                // tries and in what order — there is no separate "default
                // protocol" setting anymore.
                protocolPriorityProvider = { du.getAutoModeProtocolPriority() },
                serverProvider = { serversProvider?.invoke() ?: defaultServers() },
                onSuccess = { candidate, protocol ->
                    du.setStringSetting(DataUtil.AUTO_LAST_SUCCESS_HOST, candidate.hostname ?: candidate.ip ?: "")
                    du.setStringSetting(DataUtil.AUTO_LAST_SUCCESS_PROTOCOL, protocol.id)
                    AutoModeRun.lastSuccess = candidate
                },
                // §3 §4 §5 §14 Read timeout from Settings per attempt (per-server, not total)
                attemptTimeoutMs = du.getAutoModeTimeoutSeconds().toLong() * 1000,
                // AUTOCONNECT-AUDIT D-2: a NATIVE_BUSY refusal is answered by waiting for
                // the previous native operation to release the process-wide slot. A native
                // connect can still be in flight for several multiples of the per-attempt
                // budget (each blocking read on that side is bounded by the same figure),
                // so the wait is derived from that budget rather than hard-coded, and is
                // capped so a large setting cannot make the terminal give-up unreachable.
                nativeSlotAwaitMs = (du.getAutoModeTimeoutSeconds().toLong() * 1000L * 5L)
                    .coerceAtMost(AutoModeController.NATIVE_SLOT_AWAIT_MAX_MS),
// Stage 2F-F.13 (Y4): the controller selects the protocol/endpoint
                // pairs and hands them over; the probe only measures them.
                reachabilityFilter = { targets ->
                      vn.unlimit.vpngate.network.ServerReachabilityTester.filterReachableCandidates(
                          targets,
                          timeoutMs = 1200,
                      )
                  },
                // Stage 2F-F.15 (D4 + Y1-b) / Stage 2F-F.16 (Y3): the representative
                  // active RTT for the final ranking tie-break. Reads the
                  // AUTO_CONNECT namespace by IP **and** protocol, and takes the
                  // RTT only from a definitive REACHABLE verdict — UNKNOWN and
                  // UNREACHABLE contribute nothing, so a silent UDP endpoint can
                  // never donate a number (or a guess) to the comparator.
                  // The Server List's protocol-less answer is never read here.
                  activeRttProvider = { ip, protocol ->
                      vn.unlimit.vpngate.network.ServerReachabilityTester
                          .autoConnectResult(ip, protocol)
                          ?.rttIfReachable()
                  },
                  // VpnM Phase 1.5: an empty list must not run the attempt loop.
                emptyServerListMessage = {
                    vn.unlimit.vpngate.App.instance!!
                        .getString(vn.unlimit.vpngate.R.string.update_server_list_first)
                },
                // VpnM Phase 5: active liveness. A real round trip through the
                // tunnel proves it still carries traffic, which the passive
                // stack/OS signals cannot do for a quietly dead tunnel.
                livenessProbeFactory = { probeScope -> TunnelLivenessProbe(probeScope) },
                // Global Tunnel State is the app's single source of truth, so a
                // manual connection that is still coming up must block Auto
                // Connect from dialling a second, competing tunnel.
                isTunnelBusy = { vn.unlimit.vpngate.state.GlobalVpnTracker.isBusy },
                // R8 (§11.13.2): the Auto-Reconnect setting, read live so a
                // toggle takes effect at the next unexpected disconnect.
                autoReconnectEnabled = { du.getAutoReconnectInAutoMode() },
            )
            controller = created
            _state.value = created.state.value
            // Try next server button: interrupt the in-flight attempt via
            // the adapter (fail the tunnel wait + tear down the service).
            created.setSkipSignal { adapter.skipCurrent() }
            // Mirror the Auto Mode state into the status notification.
            val appContext = vn.unlimit.vpngate.App.instance!!.applicationContext
            created.onStateChange = { state ->
                _state.value = state
                when (state) {
                    is AutoModeState.Connecting -> AutoModeNotifier.notifyConnecting(
                        appContext, state.hostname, state.attempt, state.total,
                    )
                    is AutoModeState.Connected -> AutoModeNotifier.notifyConnected(
                        appContext, state.hostname, state.protocol.id,
                    )
                    else -> AutoModeNotifier.clear(appContext)
                }
            }
            return created
        }
    }

    /**
     * Where Auto Connect reads its server list from.
     *
     * Injected so the acquisition path can be exercised without Room or a
     * network. A source returns whatever is safe to use *now*; it is never
     * allowed to decide what is stored — persistence belongs to
     * [vn.unlimit.vpngate.repository.ServerListUpdater] alone.
     */
    fun interface ServerDatasetSource {
        /** The dataset to use, or null/empty when there is none. */
        suspend fun dataset(): VPNGateConnectionList?
    }

    /**
     * The production source: the stored dataset, or — only when the store is
     * empty — one acquired through the ordinary acceptance boundary.
     *
     * The updater is handed in as a factory so this file stays free of Room:
     * Auto Connect never touches the database itself, it only asks for a
     * dataset. [ServerListUpdater.acquire] returns the stored rows without any
     * write when there are any, and otherwise runs the same mutex-guarded
     * update the Update button runs, so a candidate that would have been
     * rejected can neither be persisted nor be handed to a run. It also
     * re-reads the store after waiting for an update that was already running,
     * which is what makes Auto Connect unable to overwrite a dataset accepted
     * while it waited.
     */
    fun gatedSource(
        updaterFactory: () -> vn.unlimit.vpngate.repository.ServerListUpdater,
    ): ServerDatasetSource = ServerDatasetSource {
        val stored = updaterFactory().acquire()
        if (stored.isEmpty()) null else VPNGateConnectionList().fromVPNGateItems(stored)
    }

    private fun productionSource(): ServerDatasetSource {
        val app = vn.unlimit.vpngate.App.instance ?: return ServerDatasetSource { null }
        return gatedSource { vn.unlimit.vpngate.repository.ServerListUpdates.forAcquisition(app) }
    }

    /**
     * Auto Connect's candidate list.
     *
     * The stored dataset is the answer whenever there is one: no refresh, no
     * write, no judgment. An empty store is the only case that acquires
     * anything, and it does so through the acceptance boundary. This function
     * therefore never persists a dataset — it only reads what is already
     * accepted, plus whatever that same boundary accepted during acquisition.
     */
    suspend fun defaultServers(
        source: ServerDatasetSource = productionSource(),
    ): List<AutoModeCandidate> {
        val currentList = source.dataset() ?: return emptyList()
        return (0 until currentList.size()).mapNotNull { currentList.get(it)?.toCandidate() }
    }

    fun VPNGateConnection.toCandidate(): AutoModeCandidate = AutoModeCandidate(
        hostname = hostName,
        ip = ip,
        speed = speed.toLong(),
        ping = ping,
        sessions = numVpnSession,
        score = score,
        sources = 1, // connection list is post-merge; per-server source count lives in the repository
        seTcpPort = seTcpPort,
        seUdpPort = seUdpPort,
        seUdpSupported = seUdpSupported,
        openVpnTcpPort = tcpPort,
        openVpnUdpPort = udpPort,
        l2tpSupported = isL2TPSupport == 1,
        sstpSupported = isSSTPSupport == 1,
        // Stage 2F-F.2 (Q-6): carry SSTP's own port and provenance so Auto
        // Connect can tell a dialable SSTP server from one whose port is
        // simply unknown. sstpConnectPort is the connection's existing derived
        // value, so the EXPLICIT/DEFAULT/UNKNOWN rule is applied here once and
        // nowhere else.
        sstpPort = sstpPort,
        sstpPortSource = VpnPortSource.normalized(sstpPortSource),
        sstpConnectPort = sstpConnectPort,
        payload = this,
    )
}

/** Holder for the last successful Auto Mode server (§19). */
internal object AutoModeRun {
    @Volatile
    var lastSuccess: AutoModeCandidate? = null
}
