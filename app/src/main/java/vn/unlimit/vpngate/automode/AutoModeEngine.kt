package vn.unlimit.vpngate.automode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import vn.unlimit.vpngate.models.VPNGateConnection
import vn.unlimit.vpngate.models.VPNGateConnectionList
import vn.unlimit.vpngate.utils.DataUtil

/**
 * App-scoped Auto Mode engine: a single [AutoModeController] instance
 * whose state survives fragment navigation. Candidate list comes from
 * the in-app collector's cache (§22) — no new collector.
 */
object AutoModeEngine {

    @Volatile
    private var controller: AutoModeController? = null

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
                protocolProvider = {
                    AutoModeProtocol.fromId(
                        du.getStringSetting(DataUtil.SETTING_DEFAULT_VPN_PROTOCOL, AutoModeProtocol.DEFAULT_ID)
                    )
                },
                serverProvider = { serversProvider?.invoke() ?: defaultServers(du) },
                onSuccess = { candidate, protocol ->
                    du.setStringSetting(DataUtil.AUTO_LAST_SUCCESS_HOST, candidate.hostname ?: candidate.ip ?: "")
                    du.setStringSetting(DataUtil.AUTO_LAST_SUCCESS_PROTOCOL, protocol.id)
                    AutoModeRun.lastSuccess = candidate
                },
                // §3 §4 §5 §14 Read timeout from Settings per attempt (per-server, not total)
                attemptTimeoutMs = du.getAutoModeTimeoutSeconds().toLong() * 1000,
            )
            controller = created
            // Try next server button: interrupt the in-flight attempt via
            // the adapter (fail the tunnel wait + tear down the service).
            created.setSkipSignal { adapter.skipCurrent() }
            // Mirror the Auto Mode state into the status notification.
            val appContext = vn.unlimit.vpngate.App.instance!!.applicationContext
            created.onStateChange = { state ->
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

    /** §22: the collector cache is the single source of servers. */
    suspend fun defaultServers(dataUtil: DataUtil): List<AutoModeCandidate> {
        val list: VPNGateConnectionList = dataUtil.connectionsCache ?: return emptyList()
        return (0 until list.size()).mapNotNull { list.get(it)?.toCandidate() }
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
        payload = this,
    )
}

/** Holder for the last successful Auto Mode server (§19). */
internal object AutoModeRun {
    @Volatile
    var lastSuccess: AutoModeCandidate? = null
}
