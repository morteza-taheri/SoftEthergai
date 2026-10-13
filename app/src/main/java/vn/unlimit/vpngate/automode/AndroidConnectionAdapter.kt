package vn.unlimit.vpngate.automode

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.service.SstpVpnService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import vn.unlimit.softether.NativeLifetimeRegistry
import vn.unlimit.softether.SoftEtherVpnService
import vn.unlimit.softether.TeardownCompletion
import vn.unlimit.softether.model.ConnectionConfig
import vn.unlimit.softether.model.Route
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.BuildConfig
import vn.unlimit.vpngate.models.VPNGateConnection
import vn.unlimit.vpngate.state.GlobalVpnTracker
import vn.unlimit.vpngate.utils.AppConfig
import vn.unlimit.vpngate.utils.DataUtil
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

/**
 * Real [AutoModeController.ConnectionAdapter] driving the app's
 * existing VPN services (Â§27): SoftEtherVpnService, SstpVpnService
 * and the ics-openvpn stack. Tunnel-up detection is delegated to
 * [TunnelStateWatcher]; the wait is fully suspend/non-blocking (Â§11).
 */
class AndroidConnectionAdapter(
    private val context: Context,
    private val dataUtil: DataUtil,
) : AutoModeController.ConnectionAdapter {

    companion object {
        private const val TAG = "AutoMode"
        private const val ACTION_VPN_CONNECT = "vn.unlimit.vpngate.VPN_CONNECT"
        private const val ACTION_VPN_DISCONNECT = "vn.unlimit.vpngate.VPN_DISCONNECT"

        /**
         * Stage 2F-F.28 Fix G: bounded safety net for teardown observation.
         *
         * This is NOT the synchronisation mechanism — [disconnect] waits on the
         * real DISCONNECTED/ERROR callback and only falls back to this bound so a
         * stack that never reports cannot wedge Auto Connect indefinitely.
         */
        private const val TEARDOWN_TIMEOUT_MS = 5_000L
    }

    @Volatile
    private var currentProtocol: AutoModeProtocol? = null

    /**
     * Stage 2F-F.2 (Q-7): L2TP/IPsec is the one protocol this adapter cannot
     * drive unattended — it needs the OS Settings UI, which no background
     * intent can open.
     *
     * Auto Connect asks this before attempting a protocol, so an L2TP entry in
     * the user's priority list is skipped instead of being entered, logged and
     * then waited out for the full per-attempt timeout on a connection that
     * could never succeed.
     *
     * This is scoped to the Auto Connect background path only. A manually
     * initiated L2TP connection is untouched: the entry remains in the
     * priority settings, the capability data is unchanged, and this says
     * nothing about whether L2TP works when the user starts it by hand.
     */
    override fun canAutoConnect(protocol: AutoModeProtocol): Boolean = when (protocol) {
        AutoModeProtocol.L2TP_IPSEC -> false
        else -> true
    }

    override suspend fun connect(candidate: AutoModeCandidate, protocol: AutoModeProtocol) {
        currentProtocol = protocol
        val conn = candidate.payload as? VPNGateConnection ?: return
        // Publish the attempt into the app-wide tunnel state *before* touching a
        // service. Pressing Connect means CONNECTING, not CONNECTED: only the
        // stack's own "tunnel is up" signal may promote this to CONNECTED, so
        // the UI, the status screen and Auto Mode can never disagree.
        GlobalVpnTracker.reportConnecting(
            protocol = protocol.displayName,
            serverHost = conn.hostName,
            serverIp = conn.ip,
        )
        withContext(Dispatchers.Main) {
            when (protocol) {
                AutoModeProtocol.SOFTETHER_TCP -> connectSoftEther(conn, useTcp = true)
                AutoModeProtocol.SOFTETHER_UDP -> connectSoftEther(conn, useTcp = false)
                AutoModeProtocol.OPENVPN_TCP -> connectOpenVpn(conn, useUdp = false)
                AutoModeProtocol.OPENVPN_UDP -> connectOpenVpn(conn, useUdp = true)
                AutoModeProtocol.MS_SSTP -> connectSstp(conn)
                AutoModeProtocol.L2TP_IPSEC -> {
                    // L2TP/IPsec needs the OS Settings UI; not automatable in background.
                    Log.w(TAG, "L2TP connect is not automatable in background")
                }
            }
        }
    }

    /**
         * Stage 2F-F.28 Fix E: the non-suspending teardown primitive.
         *
         * Enqueues the teardown intents to the main looper and returns, so a
         * caller can guarantee the request was issued before it publishes a
         * terminal UI state. Idempotent per request via [teardownDispatched].
         */
        override fun requestDisconnect() {
            GlobalVpnTracker.reportDisconnecting()
            if (!teardownDispatched.compareAndSet(false, true)) return
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                dispatchDisconnectIntents()
                teardownDispatched.set(false)
            }
        }

        private fun dispatchDisconnectIntents(teardownAttemptId: Int = 0) {
            // Stage 2F-F.28AD-B: a caller that is waiting for one specific teardown passes
            // the id it armed its waiter with; every other caller passes 0 and the service
            // allocates its own, exactly as before.
            startService(
                SoftEtherVpnService.ACTION_DISCONNECT,
                SoftEtherVpnService::class.java,
                teardownAttemptId,
            )
            ProfileManager.setConntectedVpnProfileDisconnected(context)
            startService(OpenVPNService.DISCONNECT_VPN, OpenVPNService::class.java)
            startService(ACTION_VPN_DISCONNECT, SstpVpnService::class.java)
        }

        private val teardownDispatched = java.util.concurrent.atomic.AtomicBoolean(false)

        override suspend fun disconnect() {
            // Mark the teardown first so anything asking "is a connection in
            // flight?" during the gap sees DISCONNECTING rather than a stale
            // CONNECTED. The services' own callbacks finish the job by moving the
            // state to DISCONNECTED.
            GlobalVpnTracker.reportDisconnecting()

            // Stage 2F-F.28 Fix G: replace the fixed `delay(200)` with an actual
            // lifecycle completion signal.
            //
            // The old delay assumed the OS TUN and native sockets were released
            // within 200 ms. Nothing guaranteed that, so callers proceeded to the
            // next server while a live tunnel could still be up. Now the waiter
            // completes when the stack actually reports teardown COMPLETE.
            //
            // Stage 2F-F.28AA: the waiter now carries a [TeardownCompletion] rather than
            // being satisfied by any completion at all, and it is disarmed with its own
            // arm token so this call can never disarm a waiter armed after it.
            //
            // Stage 2F-F.28AD-B: allocate the teardown identity FIRST and arm for exactly
            // that teardown. Ordering matters: the service allocates its own id only once
            // the disconnect intent arrives, which is after this waiter would exist, so an
            // identity has to be reserved here and handed to the service in the intent.
            // Without it the waiter could only be matched by recency, which is how a
            // re-entrant NOOP completion ended this wait early and then made the real
            // completion look stale.
            val teardownAttemptId = SoftEtherVpnService.nextTeardownAttemptId()
            val teardown = CompletableDeferred<TeardownCompletion>()
            val armToken = TunnelStateWatcher.armTeardown(teardownAttemptId) { result ->
                teardown.complete(result)
            }
            var result: TeardownCompletion? = null
            try {
                withContext(Dispatchers.Main) { dispatchDisconnectIntents(teardownAttemptId) }
                result = withTimeoutOrNull(TEARDOWN_TIMEOUT_MS) { teardown.await() }
            } finally {
                TunnelStateWatcher.disarmTeardown(armToken)
            }
            when {
                result == null ->
                    // A timeout means "not confirmed", never "completed".
                    log("[AUTO] Teardown NOT confirmed within ${TEARDOWN_TIMEOUT_MS}ms")

                result.nativeCleanupConfirmed ->
                    log("[AUTO] Teardown confirmed complete (${result.describe()})")

                else ->
                    // Stage 2F-F.28AA: a completion that is not NATIVE_CONFIRMED. The
                    // previous code logged "confirmed" for this case, which is how Auto
                    // Mode came to start a new server while the old native operation was
                    // still running. Naming the real outcome keeps the distinction
                    // visible in the log without changing what this function returns.
                    log(
                        "[AUTO] Teardown finished but NATIVE CLEANUP UNCONFIRMED " +
                                "(${result.describe()}); ownership retained, " +
                                "a new native attempt will be refused",
                    )
            }
            currentProtocol = null
        }

    /**
     * Stage 2F-F.28 Fix D: arm the tunnel watcher BEFORE the connect request.
     *
     * Split out of [awaitTunnel] so `AutoModeController` can order
     * arm -> connect -> wait, closing the window in which a fast CONNECTED
     * arrives before anybody is listening.
     */
    override fun armTunnelWatch(protocol: AutoModeProtocol) {
        // The deferred MUST exist before beginAttempt() registers the callback:
        // beginAttempt replays an already-terminal state synchronously, so a
        // deferred created later would miss that replay.
        val deferred = CompletableDeferred<Boolean>()
        armedTunnel = deferred
        pendingArm = protocol
        TunnelStateWatcher.beginAttempt(protocol) { deferred.complete(true) }
        // Stage 2F-F.28AA: arm a second deferred for the native-busy refusal, so an armed
        // wait can end early with a cause instead of expiring on its timeout. Racing two
        // deferreds keeps `awaitArmedTunnel`'s shape — one completion wins, the other is
        // ignored — and means a refusal costs milliseconds rather than the full
        // per-attempt timeout that made Auto Mode blame the server.
        val busy = CompletableDeferred<Boolean>()
        armedNativeBusy = busy
        TunnelStateWatcher.onNativeBusy = { busy.complete(true) }
    }

    override suspend fun awaitArmedTunnel(protocol: AutoModeProtocol, timeoutMs: Long): Boolean {
        if (pendingArm != protocol) {
            // Not armed for this protocol (e.g. a test double or a caller that
            // skipped the arm): fall back to arming now.
            armTunnelWatch(protocol)
        }
        val deferred = armedTunnel
            ?: return awaitTunnel(protocol, timeoutMs)
        return try {
            // select needs to be raced rather than awaited in sequence: the refusal can
            // arrive before, during or after the tunnel signal, and whichever happens
            // first is the real outcome.
            if (deferred.isCompleted) {
                deferred.await()
            } else {
                val busy = armedNativeBusy
                if (busy == null) {
                    withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
                } else {
                    // Stage 2F-F.28AL-AUTOCONNECT-BUG-FIX (BUG 2 / BUG 3): the
                    // select races two deferreds, and NEITHER may ever complete
                    // for a server that simply never comes up (the stack reports
                    // DISCONNECTED/ERROR, which fires TunnelStateWatcher.onTunnelLost
                    // — a callback that is NOT wired to `armedTunnel`). Without a
                    // bound the select suspends forever and Auto Mode hangs: the
                    // `skipRequested` check in runAutoMode is never reached, and a
                    // user "Try next server" press does nothing.
                    //
                    // The timeout is the SAME user-configured per-attempt budget
                    // (`attemptTimeoutMs`, PROJECT_SPECIFICATION.md §6.7 R5), so an
                    // exhausted window yields `false` and the loop advances. This
                    // is the coroutine-level half of the two-layer fix; the native
                    // race is bounded separately via ConnectionConfig.connectTimeoutMs.
                    withTimeoutOrNull(timeoutMs) {
                        kotlinx.coroutines.selects.select<Boolean> {
                            deferred.onAwait { true }
                            busy.onAwait { false }
                        }
                    } ?: false
                }
            }
        } finally {
            if (pendingArm == protocol) {
                pendingArm = null
                armedTunnel = null
                armedNativeBusy = null
                TunnelStateWatcher.onNativeBusy = null
                TunnelStateWatcher.endAttempt()
            }
        }
    }

    @Volatile
    private var armedTunnel: CompletableDeferred<Boolean>? = null

    /**
     * Stage 2F-F.28AA: armed alongside [armedTunnel]; completes when a start is refused
     * because the process-wide native slot is still held.
     */
    @Volatile
    private var armedNativeBusy: CompletableDeferred<Boolean>? = null

    /**
     * Stage 2F-F.28AA: report *why* an armed attempt ended, so Auto Mode can distinguish
     * "the server did not come up" from "our own previous native operation is still
     * running".
     *
     * Defaults to the boolean projection of [awaitArmedTunnel] so an adapter that does not
     * model the refusal — every existing test double — behaves exactly as before.
     */
    override suspend fun awaitArmedOutcome(
        protocol: AutoModeProtocol,
        timeoutMs: Long,
    ): AutoModeController.AttemptOutcome {
        val up = awaitArmedTunnel(protocol, timeoutMs)
        if (up) return AutoModeController.AttemptOutcome.TUNNEL_UP
        // The tunnel wait has been cleared by `awaitArmedTunnel`, so the busy deferred it
        // raced is no longer authoritative. Re-check the registry directly: this is the
        // same fact the refusal was derived from, and it avoids a race in which a refusal
        // that arrived *after* the timeout is silently downgraded to TIMED_OUT.
        return if (isNativeSlotBusy()) AutoModeController.AttemptOutcome.NATIVE_BUSY
        else AutoModeController.AttemptOutcome.TIMED_OUT
    }

    /**
     * Stage 2F-F.28AA: is the process-wide native slot still owned by an unresolved
     * operation? Read-only; never attempts to acquire or force anything.
     */
    private fun isNativeSlotBusy(): Boolean {
        val registry = runCatching { NativeLifetimeRegistry.process }.getOrNull() ?: return false
        return !registry.isAdmissible() || registry.isBlocked()
    }

    @Volatile
    private var pendingArm: AutoModeProtocol? = null

    /**
     * Stage 2F-F.28 Fix C: is a VPN **owned by this application** established
     * right now?
     *
     * `awaitTunnel()` can time out even after `Builder.establish()` succeeded,
     * purely because the CONNECTED notification was late or lost. Without this
     * check Auto Connect would classify that attempt as "Protocol failed" and
     * move on while a real tunnel was live.
     *
     * Ownership is decided by the system's own VPN owner UID, so an unrelated
     * third-party VPN is never mistaken for this app's tunnel. `minSdk` is 24
     * and `NetworkCapabilities.getOwnerUid()` is API 29+, hence the guard.
     *
     * Deliberately NOT sourced from `GlobalVpnTracker`: that tracker is itself
     * driven by the same listener chain that can be late, so consulting it
     * would not be an independent check.
     */
    override suspend fun isOwnTunnelEstablished(): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
                    ?: return@withContext false
                val myUid = android.os.Process.myUid()
                val sdk = android.os.Build.VERSION.SDK_INT
                for (net in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(net) ?: continue
                    val owned = OwnedVpnDecision.isOwnedByThisApp(
                        hasVpnTransport = caps.hasTransport(
                            android.net.NetworkCapabilities.TRANSPORT_VPN
                        ),
                        ownerUid = caps.ownerUid,
                        sdkInt = sdk,
                        myUid = myUid,
                    )
                    if (owned) return@withContext true
                }
                false
            } catch (e: Exception) {
                Log.w(TAG, "Owned-VPN check failed: ${e.message}")
                false
            }
        }

    override suspend fun awaitTunnel(protocol: AutoModeProtocol, timeoutMs: Long): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        activeTunnelWait = deferred
        TunnelStateWatcher.beginAttempt(protocol) { deferred.complete(true) }
        try {
            return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        } finally {
            activeTunnelWait = null
            TunnelStateWatcher.endAttempt()
        }
    }

    /**
     * Interrupt the in-flight attempt for the Try next server button:
     * complete the tunnel wait negatively and tear down the half-open
     * service connection so the controller can move on immediately.
     *
     * Stage 2F-F.28AL-AUTOCONNECT-BUG-FIX (BUG 2): the armed wait used by
     * Auto Mode is [armedTunnel], NOT [activeTunnelWait] — the latter is the
     * fallback used by [awaitTunnel]. Completing only `activeTunnelWait` left
     * the armed `select` in [awaitArmedTunnel] waiting on a deferred nothing
     * would complete, so a "Try next server" press hung the run. Both are
     * completed here so the skip is observed regardless of which wait path the
     * current attempt took.
     */
    fun skipCurrent() {
        armedTunnel?.complete(false)
        activeTunnelWait?.complete(false)
        currentProtocol = null
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            startService(SoftEtherVpnService.ACTION_DISCONNECT, SoftEtherVpnService::class.java)
            ProfileManager.setConntectedVpnProfileDisconnected(context)
            startService(OpenVPNService.DISCONNECT_VPN, OpenVPNService::class.java)
            startService(ACTION_VPN_DISCONNECT, SstpVpnService::class.java)
        }
    }

    @Volatile
    private var activeTunnelWait: CompletableDeferred<Boolean>? = null

    override fun log(message: String) {
        Log.d(TAG, message)
        AutoModeLogStore.add(AutoModeLogStore.Source.AUTO, message)
    }

    // ---- Protocol connectors (mirror the existing manual flows) ----

    private fun connectSoftEther(conn: VPNGateConnection, useTcp: Boolean) {
        val vpnIntent = VpnService.prepare(context)
        if (vpnIntent != null) {
            Log.w(TAG, "VPN permission not granted; cannot connect in background")
            throw AutoModeController.VpnPermissionMissingException()
        }

        val isUdpOnly = conn.isUdpOnly
        // serverPort is the server's TCP listener port for EVERY transport.
        // The native stack uses it only for the TCP connect and the login
        // PACK (ctx->port is read by transport_thread_tcp alone), and
        // SoftEther UDP accelerates over that same TCP control channel while
        // the UDP endpoint is learned from the server's Welcome PACK or the
        // NAT-T rendezvous. Passing seUdpPort here would put a UDP port — or
        // 0/UNKNOWN — into a TCP field. 0 = no TCP listener published.
        val serverPort = conn.seTcpPort
        val serverName = conn.getName(!useTcp, true)
        val useDomain = dataUtil.getBooleanSetting(DataUtil.USE_DOMAIN_TO_CONNECT, false)
        val serverHost = if (useDomain) conn.calculateHostName else conn.ip ?: return

        // Stage 2F-F.28AL-AUTOCONNECT-BUG-FIX (BUG 3): plumb the user's
        // per-attempt timeout into the native connect so the parallel race is
        // bounded by the SAME budget the controller waits on.
        //
        // AutoModeEngine already reads this exact setting for the controller's
        // `attemptTimeoutMs` (`du.getAutoModeTimeoutSeconds() * 1000`); reading
        // the same Settings source here keeps ONE source of truth (the
        // preference) without widening the ConnectionAdapter.connect()
        // signature — which would ripple into every test double. The native
        // side consumes it via ConnectionController `client.setTimeout(
        // config.connectTimeoutMs)`; without this the race kept its 30000 ms
        // default and ignored the user's setting entirely.
        //
        // Two-layer fix with the Kotlin-side bound in awaitArmedTunnel():
        //   layer 1 (here)      — native race timeout (connection-level)
        //   layer 2 (FIX 2)     — armed select timeout (coroutine-level)
        val attemptTimeoutMs = dataUtil.getAutoModeTimeoutSeconds().toLong() * 1000

        val config = ConnectionConfig(
            serverHost = serverHost,
            serverPort = serverPort,
            username = "vpn",
            password = "vpn",
            virtualHub = "vpngate",
            sessionName = serverName,
            localAddress = "10.21.0.2",
            prefixLength = 19,
            dnsServer = resolvePrimaryDns(),
            secondaryDnsServer = resolveSecondaryDns(),
            routes = listOf(Route("0.0.0.0", 0)),
            mtu = 1500,
            useTcp = useTcp,
            udpPort = conn.seUdpPort,
            udpOnly = isUdpOnly,
            connectTimeoutMs = attemptTimeoutMs.toInt(),
            clientProductName = "VPN Gate Connector Pro",
            clientVersion = BuildConfig.VERSION_NAME,
            clientBuild = BuildConfig.VERSION_CODE,
            maxConnections = dataUtil.getSoftEtherMaxConnections(),
            excludedApps = (App.instance?.excludedAppDao?.getAllExcludedApps() ?: emptyList())
                .map { it.packageName },
        )
        SoftEtherVpnService.notificationTargetActivity =
            vn.unlimit.vpngate.activities.MainActivity::class.java
        val intent = Intent(context, SoftEtherVpnService::class.java).apply {
            action = SoftEtherVpnService.ACTION_CONNECT
            putExtra(SoftEtherVpnService.EXTRA_CONFIG, config)
        }
        startForegroundCompatible(intent, SoftEtherVpnService::class.java)

        dataUtil.setBooleanSetting(DataUtil.LAST_CONNECT_USE_UDP, !useTcp)
        dataUtil.setBooleanSetting(DataUtil.LAST_CONNECT_SOFTETHER_USE_UDP, !useTcp)
        dataUtil.setStringSetting(DataUtil.LAST_CONNECT_METHOD, "softether")
        dataUtil.lastVPNConnection = conn
    }

    private fun connectOpenVpn(conn: VPNGateConnection, useUdp: Boolean) {
        if (VpnService.prepare(context) != null) {
            Log.w(TAG, "VPN permission not granted; cannot connect in background")
            throw AutoModeController.VpnPermissionMissingException()
        }
        val data = if (useUdp) conn.openVpnConfigDataUdp else conn.openVpnConfigData
        if (data.isNullOrEmpty()) {
            Log.w(TAG, "No OpenVPN config payload for ${conn.hostName}")
            return
        }
        try {
            val cp = ConfigParser()
            cp.parseConfig(InputStreamReader(ByteArrayInputStream(data.toByteArray())))
            val profile = cp.convertProfile()
            profile.mName = conn.getName(useUdp)
            profile.mCompatMode = App.VPN_PROFILE_COMPAT_MODE_24X
            vn.unlimit.vpngate.utils.ExcludeAppsManager(context).configureSplitTunneling(profile)
            profile.mOverrideDNS = true
            profile.mDNS1 = resolvePrimaryDns()
            profile.mDNS2 = resolveSecondaryDns()
            ProfileManager.setTemporaryProfile(context, profile)
            VPNLaunchHelper.startOpenVpn(profile, context, null, true)
            dataUtil.setBooleanSetting(DataUtil.LAST_CONNECT_USE_UDP, useUdp)
            dataUtil.setStringSetting(DataUtil.LAST_CONNECT_METHOD, "openvpn")
            dataUtil.lastVPNConnection = conn
        } catch (e: Exception) {
            Log.e(TAG, "OpenVPN profile build failed", e)
        }
    }

    private fun connectSstp(conn: VPNGateConnection) {
        if (VpnService.prepare(context) != null) {
            Log.w(TAG, "VPN permission not granted; cannot connect in background")
            throw AutoModeController.VpnPermissionMissingException()
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit()
            .putString(OscPrefKey.HOME_HOSTNAME.toString(), conn.calculateHostName)
            .putString(OscPrefKey.HOME_COUNTRY.toString(), conn.countryShort?.uppercase() ?: "")
            .putString(OscPrefKey.HOME_USERNAME.toString(), "vpn")
            .putString(OscPrefKey.HOME_PASSWORD.toString(), "vpn")
            .putString(OscPrefKey.SSL_PORT.toString(), conn.sstpConnectPort.toString())
            .putStringSet(OscPrefKey.ROUTE_EXCLUDED_APPS.toString(), emptySet())
            .apply()
        SstpVpnService.notificationTargetActivity =
            vn.unlimit.vpngate.activities.MainActivity::class.java
        SstpVpnService.mDisplaySpeed =
            dataUtil.getBooleanSetting(DataUtil.SETTING_NOTIFY_SPEED, DataUtil.DEFAULT_NOTIFY_SPEED)
        val intent = Intent(context, SstpVpnService::class.java).setAction(ACTION_VPN_CONNECT)
        startForegroundCompatible(intent, SstpVpnService::class.java)
        dataUtil.setStringSetting(DataUtil.LAST_CONNECT_METHOD, "sstp")
        dataUtil.lastVPNConnection = conn
    }

    private fun startService(action: String, service: Class<*>, teardownAttemptId: Int = 0) {
        val intent = Intent(context, service).setAction(action)
        // Stage 2F-F.28AD-B: stamp the teardown identity onto the request, so the service
        // reports the teardown this caller is waiting for rather than allocating a second,
        // different identity. Only a positive id is stamped; other callers are unaffected.
        if (teardownAttemptId > 0) {
            intent.putExtra(
                SoftEtherVpnService.EXTRA_TEARDOWN_ATTEMPT_ID,
                teardownAttemptId,
            )
        }
        // A DISCONNECT intent makes each service stop itself without ever
        // calling startForeground(). Sending that through
        // startForegroundService() on Android O+ therefore trips
        // ForegroundServiceDidNotStartInTimeException and kills the app — and
        // this path runs on every failed server attempt, not only a manual
        // disconnect. Teardown intents are plain background work, so a normal
        // start is both correct and sufficient.
        val isTeardown = action == SoftEtherVpnService.ACTION_DISCONNECT ||
                action == OpenVPNService.DISCONNECT_VPN ||
                action == ACTION_VPN_DISCONNECT
        if (isTeardown || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            context.startService(intent)
        } else {
            startForegroundCompatible(intent, service)
        }
    }

    private fun startForegroundCompatible(intent: Intent, service: Class<*>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun resolvePrimaryDns(): String = when {
        dataUtil.getBooleanSetting(DataUtil.SETTING_BLOCK_ADS, false) ->
            AppConfig.getString("vpn_dns_block_ads_primary").ifEmpty { "8.8.8.8" }
        dataUtil.getBooleanSetting(DataUtil.USE_CUSTOM_DNS, false) ->
            dataUtil.getStringSetting(DataUtil.CUSTOM_DNS_IP_1, "8.8.8.8") ?: "8.8.8.8"
        else -> "8.8.8.8"
    }

    private fun resolveSecondaryDns(): String = when {
        dataUtil.getBooleanSetting(DataUtil.SETTING_BLOCK_ADS, false) ->
            AppConfig.getString("vpn_dns_block_ads_alternative").ifEmpty { "8.8.4.4" }
        dataUtil.getBooleanSetting(DataUtil.USE_CUSTOM_DNS, false) ->
            dataUtil.getStringSetting(DataUtil.CUSTOM_DNS_IP_2, "8.8.4.4")?.takeIf { it.isNotEmpty() }
                ?: "8.8.4.4"
        else -> "8.8.4.4"
    }
}
