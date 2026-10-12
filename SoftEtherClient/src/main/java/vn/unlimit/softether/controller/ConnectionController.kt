package vn.unlimit.softether.controller

import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import vn.unlimit.softether.NativeLifetimeRegistry
import vn.unlimit.softether.SoftEtherVpnService
import vn.unlimit.softether.TeardownCompletion
import vn.unlimit.softether.SoftEtherTrafficSnapshot
import vn.unlimit.softether.client.SoftEtherClient
import vn.unlimit.softether.client.protocol.KeepAliveManager
import vn.unlimit.softether.model.ClientInfo
import vn.unlimit.softether.model.ConnectionConfig
import vn.unlimit.softether.model.ConnectionState
import vn.unlimit.softether.model.SoftEtherError
import vn.unlimit.softether.terminal.TunTerminal
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ConnectionController - Manages the SoftEther VPN connection lifecycle
 * Implements complete data forwarding between TUN interface and SoftEther connection
 */
class ConnectionController(
    private val service: SoftEtherVpnService,
    private val config: ConnectionConfig,
    /**
     * Stage 2F-F.28J: the identity of the session that created this controller.
     *
     * Captured once, at construction, and never re-read. TUN installation presents
     * this value to [SoftEtherVpnService.establishVpnInterface], so a controller that
     * outlives its session — an `attemptReconnect()` still blocked in native code when
     * teardown completed — is refused at the establishment boundary instead of
     * replacing the live session's interface. Reading the *current* generation at
     * install time would defeat this: the stale controller would look current.
     */
    private val sessionId: Int,
    /**
     * Stage 2F-F.28W: this controller's exclusive claim on the process-wide native
     * operation slot.
     *
     * Acquired by the service **before** this controller exists, so admission is atomic
     * with the start gate and there is no window in which native work has started while
     * the registry believes the process is idle. The controller holds it for its whole
     * life — including across `attemptReconnect()` replacing its handle — and releases
     * it only from [destroyResources], after every native call it makes has returned.
     *
     * `null` only for a controller built outside a reserved session (there is none in
     * production; the parameter is nullable so the registry, not the caller, stays the
     * authority). A `null` lease can never release anyone's ownership.
     */
    private val nativeLease: NativeLifetimeRegistry.Lease? = null,
    private val onStateChange: (ConnectionState) -> Unit,
    private val onError: (String) -> Unit,
    private val onTrafficUpdate: (SoftEtherTrafficSnapshot) -> Unit
) {
    companion object {
        private const val TAG = "ConnectionController"
        private const val MAX_RECONNECT_ATTEMPTS = 2
        private const val RECONNECT_DELAY_MS = 1000L
        private const val STATS_INTERVAL_MS = 1000L
        private const val RX_BATCH_MAX_PACKETS = 32  // Phase 13D: frames per receiveBatch call
        private const val NATIVE_TEARDOWN_TIMEOUT_MS = 500L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = SoftEtherClient()
    private val isCancelled = AtomicBoolean(false)
    private val isReconnecting = AtomicBoolean(false)
    @Volatile
    private var statisticsJob: Job? = null
    @Volatile
    private var reconnectJob: Job? = null
    private var keepAliveManager: KeepAliveManager? = null
    // True while a connect flow owns the native handle (performConnect alive).
    // Deliberately NOT derived from currentState: disconnect() resets the state
    // to DISCONNECTED immediately, but the blocked nativeConnectWithHub JNI
    // call may still hold connect_mutex long after that.
    private val connectInFlight = AtomicBoolean(false)

    /**
     * Stage 2F-F.28Y: the single owner of this attempt's native connection lifecycle.
     *
     * Replaces the old per-flow `nativeTeardownGate` latch. That latch was reassigned by
     * each flow, so teardown could await a latch belonging to a different flow, and a zero
     * handle made teardown skip synchronisation entirely. This coordinator keys ownership
     * to the *active flow object*, forbids destruction while any flow can still be
     * executing, and hands cleanup to the exiting flow when teardown times out.
     *
     * It also owns the terminal `isDestroyed` flag, so "teardown has begun" and "a flow is
     * registered" are decided under one lock and cannot disagree.
     */
    /**
     * Stage 2F-F.28AL-U: the single atomic ownership-transfer point for this
     * attempt's native handle.
     *
     * Both cleanup families claim through it — the coordinator's cleanup via the
     * [NativeHandleOwner] wiring below, and this class's inline teardowns via
     * [claimAndDestroyNativeHandle] — so a given handle can be handed to at most
     * one destroyer, and a claim taken while another owner's destroy is unresolved
     * or still in flight reports Lost instead of an empty slot.
     */
    private val nativeHandleSlot = NativeHandleSlot()

    private val nativeLifecycle = NativeAttemptCoordinator(
        handles = NativeHandleOwner {
            protectedFds.clear()
            // Stage 2F-F.28AC/F.28AL-U: a zero handle alone must never be read as
            // "no native resource remains" while a previous owner's destroy is
            // unproven or still in flight — the slot reports Lost for both, which
            // is what stops runCleanup() from reading an unproven cleanup as a
            // confirmed one and releasing the process-wide lease.
            nativeHandleSlot.claimHandle()
        },
        releaseLease = {
            if (NativeLifetimeRegistry.process.confirmTeardown(nativeLease)) {
                Log.d(TAG, "Released process-wide native slot for this attempt")
            }
        },
        interruptNative = { handle -> client.nativeForceCloseSocket(handle) },
        destroyNative = { handle ->
            try {
                client.nativeDestroy(handle)
            } catch (e: Throwable) {
                // The free is unproven: record the loss on the slot so no later
                // claim can read the emptied handle as a completed cleanup, then
                // rethrow so runCleanup applies its own fail-closed lease policy.
                markNativeHandleLost(handle, e)
                throw e
            }
            nativeHandleSlot.settleDestroyed()
        },
        onUnresolved = { reason -> Log.w(TAG, "Native lifecycle unresolved: $reason") },
    )
    private val reconnectAttempts = AtomicInteger(0)
    private val connectionMutex = Mutex()
    private var stateMonitorJob: Job? = null

    // Statistics
    private val bytesSent = AtomicLong(0)
    private val bytesReceived = AtomicLong(0)
    private val packetsSent = AtomicLong(0)
    private val packetsReceived = AtomicLong(0)
    private var lastPublishedSentBytes = 0L
    private var lastPublishedReceivedBytes = 0L
    private var lastPublishedAtMs = 0L

    private var isNetworkAvailable = true
    
    private var currentState: ConnectionState = ConnectionState.DISCONNECTED
        set(value) {
            field = value
            onStateChange(value)
        }

    /**
     * The native handle currently published for this attempt, or 0.
     *
     * Stage 2F-F.28AL-U: reads are plain reads for live connection use, exactly as
     * before; every cleanup-relevant transfer of ownership goes through
     * [NativeHandleSlot.claimHandle]'s atomic claim rather than through this
     * accessor, so no compound read-then-zero decision is made here.
     */
    private var nativeHandle: Long
        get() = nativeHandleSlot.current
        set(value) {
            nativeHandleSlot.publish(value)
        }

    /**
     * Stage 2F-F.28AC/F.28AL-U: record that [handle] was taken for destruction but was
     * not freed.
     *
     * Called from every site that destroys a claimed handle, so the loss is never
     * silent. It settles the slot's in-flight claim as unproven and marks it terminal
     * (see [NativeHandleSlot.settleLost]): the structure's fate is unknown from here
     * on — it must not be freed a second time, and the process-wide lease must not be
     * released. Fail-closed.
     */
    private fun markNativeHandleLost(handle: Long, cause: Throwable) {
        nativeHandleSlot.settleLost()
        Log.e(
            TAG,
            "Native handle $handle was claimed for destruction but its cleanup is unproven " +
                    "(${cause.javaClass.simpleName}: ${cause.message}); the native lifetime " +
                    "lease is retained because no native attempt may be admitted against an " +
                    "unaccounted connection",
            cause,
        )
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunTerminal: TunTerminal? = null

    /**
     * Handle network connectivity changes.
     * On network loss, do NOT destroy the connection — the data forwarding loops will
     * detect send/receive errors and trigger attemptReconnect() automatically.
     * Calling disconnect() or onError() here kills the scope permanently and makes
     * reconnect impossible.
     */
    fun onNetworkChanged(isConnected: Boolean) {
        Log.d(TAG, "Network state changed: connected=$isConnected")
        isNetworkAvailable = isConnected
        
        if (!isConnected) {
            Log.w(TAG, "Network lost — data loops will detect and attempt reconnect")
        }
    }

    /** DHCP-assigned local IP address (available after successful connect) */
    var assignedLocalIp: String? = null
        private set

    /**
     * Initiates VPN connection with automatic retry logic
     */
    suspend fun connect() {
        // Check if connection is already active before acquiring lock to avoid waiting
        if (currentState != ConnectionState.DISCONNECTED && currentState != ConnectionState.ERROR) {
            Log.w(TAG, "Connection already in progress or established")
            return
        }

        connectionMutex.withLock {
            if (isCancelled.get()) {
                Log.w(TAG, "Connection cancelled, not starting")
                return
            }
            
            // Check network availability
            if (!isNetworkAvailable) {
                Log.e(TAG, "Network unavailable, cannot connect")
                onError("Network unavailable")
                return
            }
            
            if (currentState != ConnectionState.DISCONNECTED && currentState != ConnectionState.ERROR) {
                Log.w(TAG, "Connection already in progress or established")
                return
            }

            reconnectAttempts.set(0)
            
            // Iterative connection loop
            while (reconnectAttempts.get() < MAX_RECONNECT_ATTEMPTS) {
                try {
                    performConnect()
                    // If performConnect returns, it means success (currentState == CONNECTED)
                    return
                } catch (e: Exception) {
                    if (isCancelled.get()) {
                        Log.d(TAG, "Connection cancelled, not retrying")
                        return
                    }
                    
                    // If network is lost during attempt, fail immediately
                    if (!isNetworkAvailable) {
                        Log.e(TAG, "Connection aborted due to network loss")
                        currentState = ConnectionState.DISCONNECTED
                        onError("Network connection lost")
                        return
                    }
                    
                    if (reconnectAttempts.incrementAndGet() < MAX_RECONNECT_ATTEMPTS) {
                        Log.w(TAG, "Connection failed, attempting retry ${reconnectAttempts.get()}/$MAX_RECONNECT_ATTEMPTS")
                        delay(RECONNECT_DELAY_MS)
                        // Loop continues to next attempt
                    } else {
                        Log.e(TAG, "Connection failed after ${reconnectAttempts.get()} attempts", e)
                        currentState = ConnectionState.DISCONNECTED
                        onError(e.message ?: "Unknown error")
                        // Stop loop
                        return
                    }
                }
            }
        }
    }

    /**
     * Build client info for reporting to server
     */
    private fun buildClientInfo(rudpPort: Int): ClientInfo {
        // Get local non-loopback IP
        var clientIp = "0.0.0.0"
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (!addr.isLoopbackAddress &&
                            !addr.isLinkLocalAddress &&
                            (addr is java.net.Inet4Address || addr is java.net.Inet6Address)
                        ) {
                            clientIp = addr.hostAddress ?: "0.0.0.0"
                            break
                        }
                    }
                    if (clientIp != "0.0.0.0") break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get local IP", e)
        }
        
        // Get hostname
        var hostName = ""
        try {
            hostName = java.net.InetAddress.getLocalHost().hostName
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get hostname", e)
        }
        
        // Resolve server IP
        var serverIp = "0.0.0.0"
        try {
            serverIp = java.net.InetAddress.getByName(config.serverHost).hostAddress ?: "0.0.0.0"
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve server IP for ${config.serverHost}", e)
        }
        
        Log.d(TAG, "Client IP: $clientIp, Server IP: $serverIp, Port: ${config.serverPort}")
        
        return ClientInfo(
            productName = config.clientProductName,
            productVersion = config.clientVersion,
            productBuild = config.clientBuild,
            osName = "Android",
            osVersion = Build.VERSION.RELEASE,
            osProductId = Build.FINGERPRINT,
            hostName = hostName,
            clientIpAddress = clientIp,
            clientPort = rudpPort,
            serverHostName = config.serverHost,
            serverIpAddress = serverIp,
            serverPort = config.serverPort
        )
    }

    /**
     * Perform actual connection
     */
    private suspend fun performConnect() {
        Log.d(TAG, "Starting connection to ${config.serverHost}:${config.serverPort}")
        currentState = ConnectionState.CONNECTING
        isCancelled.set(false)

        // Stage 2F-F.28W. This guard is the reason the registry alone would not be
        // enough. `isCancelled` is deliberately cleared just above (so a reused
        // controller can retry), and there is no suspension point between here and
        // `nativeCreate()` — so `connectionJob.cancel()` cannot stop this coroutine
        // here. Without an `isDestroyed` check a cancelled attempt would still create a
        // native handle and enter the blocking JNI call, becoming the very zombie
        // connect the registry is meant to make visible, while `destroyResources()`
        // had already released the lease as "no native work outstanding".
        //
        // `nativeLifecycle.isDestroyed()` is set by destroyResources() and never cleared,
        // so it is the one signal that survives the cancel-flag reset.
        //
        // Stage 2F-F.28Y: this check is now only a fast path. The authoritative one runs
        // inside `beginFlow()` below, under the same lock that teardown uses to publish
        // its terminal flag. F.28X found that a plain flag read followed by native work
        // left a window in which teardown decided "no flow registered" and skipped
        // synchronisation while this thread went on to call `nativeCreate`.
        if (nativeLifecycle.isDestroyed()) {
            Log.w(TAG, "Controller destroyed before native work began; not creating a native connection")
            // Re-assert cancellation before throwing. `isCancelled` was cleared at the
            // top of this function precisely so a reused controller can retry, so
            // without this line connect()'s retry loop would read the exception as a
            // genuine connection failure — retrying against a destroyed controller and
            // finally reporting a spurious onError, which reaches stopVpn().
            isCancelled.set(true)
            throw CancellationException("Controller destroyed before native connect")
        }

        // Register this attempt's flow with the lifecycle coordinator. From this point
        // teardown knows a connect may be executing, so it can no longer skip
        // synchronisation on the grounds that "no handle is published yet".
        val flow = nativeLifecycle.beginFlow()
        if (flow == null) {
            // Teardown won the lock between the fast-path check and here.
            Log.w(TAG, "Teardown began before the native flow was registered; not connecting")
            isCancelled.set(true)
            throw CancellationException("Teardown began before native connect")
        }

        // Record that this attempt owns the process-wide native slot. The lease was
        // acquired by the service before this controller existed, so this only advances
        // the recorded phase.
        NativeLifetimeRegistry.process.markNativeEntered(nativeLease)

        // Single-owner teardown: while this connect flow is alive, ONLY this
        // function destroys the native handle. disconnect()/destroyResources()
        // defer to it (see connectInFlight) so a user cancel during the
        // blocking nativeConnectWithHub can never free the connection out
        // from under the connect thread (zombie connect + stuck connect_mutex).
        //
        // Raise connectInFlight BEFORE nativeCreate(): if a cancel lands in
        // the window between handle creation and the flag being set, teardown
        // paths see nativeHandle != 0 && !connectInFlight and destroy the
        // fresh handle under the connect thread (use-after-free crash inside
        // softether_protocol_login / ssl_write).
        connectInFlight.set(true)
        try {
            val created = client.nativeCreate()
            // Publish to the flow first: teardown uses this to shut the socket down even
            // if the assignment to `nativeHandle` below is still in flight.
            flow.publishHandle(created)
            nativeHandle = created
            if (created == 0L) {
                throw Exception("Failed to create native connection")
            }

            performConnectInner()
        } catch (e: Throwable) {
            Log.d(TAG, "Connect flow ended (${e.javaClass.simpleName}), tearing down native handle")
            teardownNativeConnection()
            throw e
        } finally {
            // Order matters. `connectInFlight` clears first so any code still reading it
            // sees the flow as finished; `flow.exit()` then counts the exit latch down and
            // — if a teardown is already waiting on this flow — takes cleanup ownership
            // HERE, on the one thread that knows no further native call will be made.
            connectInFlight.set(false)
            flow.exit()
        }
    }

    private fun isConnectInFlight(): Boolean = connectInFlight.get()

    /**
     * Stage 2F-F.28J: the single TUN installation path for this controller.
     *
     * Both the initial connect and the reconnect funnel through here so they cannot
     * drift apart again — that drift is exactly what produced the F.28I defect, where
     * the reconnect path checked for cancellation only *after* installing while the
     * initial path checked before.
     *
     * [establishVpnInterface] performs its own ownership test inside its critical
     * section, so this call is refused outright when this controller's session no longer
     * owns TUN establishment. A refusal creates no descriptor, so there is nothing to
     * close here, and [vpnInterface] is left untouched.
     *
     * @throws Exception when the descriptor could not be established. Callers already
     *   treat a failed establishment as a failed connection attempt.
     */
    private fun installTun(config: ConnectionConfig): ParcelFileDescriptor {
        return service.establishVpnInterface(config, sessionId)
            ?: throw Exception("Failed to establish VPN interface")
    }

    /**
     * Gracefully disconnect and free the native handle (idempotent).
     * Only called from the connect flow itself or when no connect is in flight.
     */
    private fun teardownNativeConnection() {
        client.externalHandle = 0
        claimAndDestroyNativeHandle()
    }

    /**
     * Stage 2F-F.28AL-U: claim the published handle and destroy it only if this
     * caller won the claim.
     *
     * This is the single inline cleanup shape in this class. The coordinator's
     * cleanup (`runCleanup()`) claims through the same [NativeHandleSlot], so for
     * any given handle at most one of the competing paths ever receives it and
     * `nativeDisconnect`/`nativeDestroy` run at most once. A claim that returns
     * Lost (another owner's destroy is unproven or still in flight) or
     * NothingOutstanding destroys nothing and — deliberately — releases nothing:
     * the lease decision belongs to the coordinator, never to an inline path.
     */
    private fun claimAndDestroyNativeHandle() {
        protectedFds.clear()
        val claim = nativeHandleSlot.claimHandle()
        if (claim is NativeHandleClaim.Claimed) {
            destroyClaimedNativeHandle(claim.handle)
        }
    }

    /**
     * Destroy a handle this caller claimed. The slot's claim is settled exactly
     * once: [NativeHandleSlot.settleDestroyed] when both native calls returned,
     * [markNativeHandleLost] when either threw — `Throwable`, not `Exception`, so
     * an escaping `UnsatisfiedLinkError` cannot read as a confirmed cleanup.
     */
    private fun destroyClaimedNativeHandle(handle: Long) {
        try {
            Log.d(TAG, "Tearing down native handle $handle")
            client.nativeDisconnect(handle)
            client.nativeDestroy(handle)
            Log.d(TAG, "Native handle $handle torn down")
            nativeHandleSlot.settleDestroyed()
        } catch (e: Throwable) {
            // Swallowing is deliberate and unchanged in effect — the caller's
            // `catch (e: Throwable)` still rethrows the original failure — but the
            // loss must be recorded or the emptied slot reads as "nothing native
            // outstanding" to every later claim.
            markNativeHandleLost(handle, e)
        }
    }

    private suspend fun performConnectInner() {
        // Check if already cancelled before proceeding
        if (isCancelled.get()) {
            Log.d(TAG, "Connection cancelled before starting")
            throw CancellationException("Connection cancelled by user")
        }

        // Set timeout
        // F.28AL-AUTOCONNECT-BUG3-FIX: pass this controller's own `nativeHandle`
        // explicitly. The legacy timeoutMs-only overload guarded on the *client's*
        // private handle — which is 0 here — so the call silently no-op'd and the
        // native race kept its 30000 ms default. `nativeHandle` is the same value
        // already handed to `nativeConnectWithHub` below.
        client.setTimeout(nativeHandle, config.connectTimeoutMs)

        // Stable client MAC derived from the server identity. On local-bridge
        // servers a random MAC per reconnect makes the upstream router's ARP
        // entry flap between zombie and live sessions holding the same DHCP
        // IP ("connected but no network" after heavy use).
        runCatching {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest("vpngate-mac:${config.serverHost}:${config.serverPort}".toByteArray())
            val mac = ByteArray(6)
            mac[0] = 0x5E  // SoftEther-style locally administered unicast prefix
            for (i in 1..5) mac[i] = digest[i]
            mac[0] = (mac[0].toInt() and 0xFE).toByte()  // clear multicast bit
            client.setClientMac(nativeHandle, mac)
            Log.d(TAG, "Stable client MAC: ${mac.joinToString(":") { String.format("%02X", it) }}")
        }

        // Enable the direct R-UDP stage (UDP-only servers reachable without relay)
        if (config.udpPort > 0) {
            client.nativeSetOption(nativeHandle, SoftEtherClient.OPTION_UDP_PORT, config.udpPort.toLong())
            Log.d(TAG, "Direct R-UDP stage enabled (udpPort=${config.udpPort})")
        }

        // UDP-only servers have no TCP port — skip the doomed direct-TCP attempt
        if (config.udpOnly) {
            client.nativeSetOption(nativeHandle, SoftEtherClient.OPTION_UDP_ONLY, 1L)
            Log.d(TAG, "UDP-only server: skipping direct TCP attempt")
        }

        currentState = ConnectionState.TLS_HANDSHAKE

        // Connect to server with hub name (includes TLS handshake, protocol handshake, auth, session setup)
        // Use virtualHub from config, default to "VPN" if not set
        val hubName = config.virtualHub.ifEmpty { "VPN" }
        Log.d(TAG, "Connecting with hub: $hubName")
        if (config.authMethod != vn.unlimit.softether.model.AuthMethod.AUTO) {
            val authTypeInt = when (config.authMethod) {
                vn.unlimit.softether.model.AuthMethod.ANONYMOUS -> 0
                vn.unlimit.softether.model.AuthMethod.PASSWORD -> 1
                vn.unlimit.softether.model.AuthMethod.PLAIN_PASSWORD -> 2
                vn.unlimit.softether.model.AuthMethod.AUTO -> 0
            }
            client.nativeSetAuthType(nativeHandle, authTypeInt)
        }
        startNativeStateMonitor()
        // Phase 17: choose half/full-duplex by device tier (or manual override) and
        // apply before connect. fullDuplex=false -> half-duplex (current 2TX/2RX split).
        val fullDuplex = DuplexModeSelector.resolve(service, config)
        client.setHalfConnection(nativeHandle, !fullDuplex)
        Log.d(TAG, "Duplex mode: ${if (fullDuplex) "FULL" else "HALF"} (4x BOTH / directional split)")
        // Build client info (rudpPort will be filled in by native code during RUDP init)
        val clientInfo = buildClientInfo(0)
        val result = try {
            client.nativeConnectWithHub(
                nativeHandle,
                config.serverHost,
                config.serverPort,
                config.username,
                config.password,
                hubName,
                config.useTcp,
                clientInfo.productName,
                clientInfo.productVersion,
                clientInfo.productBuild,
                clientInfo.osName,
                clientInfo.osVersion,
                clientInfo.osProductId,
                clientInfo.hostName,
                clientInfo.clientIpAddress,
                clientInfo.clientPort,
                clientInfo.serverHostName,
                clientInfo.serverIpAddress,
                clientInfo.serverPort
            )
        } finally {
            stopNativeStateMonitor()
        }

        // Check if cancelled during connection
        if (isCancelled.get()) {
            Log.d(TAG, "Connection was cancelled during connect")
            throw CancellationException("Connection cancelled by user")
        }

        if (result != 0) {
            currentState = ConnectionState.ERROR
            throw Exception("Connection failed: ${SoftEtherError.getErrorString(result)} ($result)")
        }

        // Connection established at protocol level — run DHCP before announcing CONNECTED
        // Keep internal state as SESSION_SETUP (DHCP phase) until we have an IP
        if (currentState != ConnectionState.CONNECTED) {
            currentState = ConnectionState.SESSION_SETUP
        }
        reconnectAttempts.set(0) // Reset on successful connection
        // Set external handle so send/receive work through ConnectionController
        client.externalHandle = nativeHandle
        Log.d(TAG, "VPN connection established successfully")

        // Check if cancelled before establishing VPN interface
        if (isCancelled.get()) {
            Log.d(TAG, "Connection cancelled after successful connect, tearing down")
            throw CancellationException("Connection cancelled by user")
        }

        // Protect VPN socket from routing through TUN (prevents routing loop)
        val socketFd = client.nativeGetSocketFd(nativeHandle)
        if (socketFd >= 0) {
            if (!service.protect(socketFd)) {
                Log.e(TAG, "Failed to protect VPN socket fd=$socketFd")
            } else {
                Log.d(TAG, "VPN socket fd=$socketFd protected from TUN routing")
            }
        } else {
            Log.e(TAG, "Invalid socket fd, cannot protect")
        }

        // Also protect RUDP UDP socket from routing through TUN (prevents RUDP routing loop)
        val rudpFd = client.nativeGetRudpSocketFd(nativeHandle)
        if (rudpFd >= 0) {
            if (!service.protect(rudpFd)) {
                Log.e(TAG, "Failed to protect RUDP socket fd=$rudpFd")
            } else {
                Log.d(TAG, "RUDP socket fd=$rudpFd protected from TUN routing")
            }
        }

        // Protect NAT-T RUDP transport UDP socket (used when the primary
        // connection runs over the NAT-T fallback; -1 otherwise)
        val natTUdpFd = client.nativeGetNatTUdpSocketFd(nativeHandle)
        if (natTUdpFd >= 0) {
            if (!service.protect(natTUdpFd)) {
                Log.e(TAG, "Failed to protect NAT-T UDP socket fd=$natTUdpFd")
            } else {
                Log.d(TAG, "NAT-T UDP socket fd=$natTUdpFd protected from TUN routing")
            }
        }

        // Perform DHCP over the SoftEther tunnel to get IP configuration
        Log.d(TAG, "Starting DHCP over SoftEther tunnel...")
        val dhcpResult = client.doDhcp(nativeHandle)

        // Re-check after DHCP: if the user cancelled while doDhcp() was
        // blocking, stop here — never establish the system VPN interface
        // for a cancelled session (that is what left the VPN key icon up).
        if (isCancelled.get()) {
            Log.d(TAG, "Connection cancelled during DHCP, tearing down")
            throw CancellationException("Connection cancelled by user")
        }

        val dhcpConfig: ConnectionConfig?
        if (dhcpResult != null) {
            Log.d(TAG, "DHCP success: IP=${dhcpResult.assignedIp}/${dhcpResult.prefixLength} " +
                    "GW=${dhcpResult.gateway} DNS=${dhcpResult.dnsServer} DNS2=${dhcpResult.dnsServer2}")
            assignedLocalIp = dhcpResult.assignedIp
            // Update config with DHCP-assigned values.
            // DNS: keep the app-configured public resolvers (8.8.8.8 / 8.8.4.4)
            // instead of adopting the VPN Gate SecureNAT internal relay
            // (e.g. 10.240.254.254). Those relays are frequently broken or
            // blocked, which manifests as "tunnel connected but no internet".
            // The DHCP IP/prefix is still honoured for the TUN interface.
            dhcpConfig = config.copy(
                localAddress = dhcpResult.assignedIp,
                prefixLength = dhcpResult.prefixLength,
            )
            vpnInterface = installTun(dhcpConfig)
        } else {
            Log.w(TAG, "DHCP failed, falling back to hardcoded IP config")
            assignedLocalIp = config.localAddress
            vpnInterface = installTun(config)
        }

        // Now that we have an IP and VPN interface, transition to CONNECTED
        // Stage 2F-F.28W: from here this attempt owns a live native handle, so the
        // registry must keep refusing replacement attempts until it is destroyed.
        NativeLifetimeRegistry.process.markConnected(nativeLease)
        currentState = ConnectionState.CONNECTED
        resetTrafficPublishing(publishSnapshot = true)

        // Start data forwarding loops
        startDataForwarding()

        // Start statistics logging
        startStatisticsLogging()
    }

    /**
     * Attempt to reconnect using stored credentials.
     * Fully disconnects, waits, then performs a fresh connect() with full lifecycle.
     */
    suspend fun reconnect(): Boolean {
        if (isReconnecting.getAndSet(true)) {
            Log.w(TAG, "Reconnection already in progress")
            return false
        }

        return try {
            Log.d(TAG, "Attempting to reconnect...")
            disconnect()
            delay(RECONNECT_DELAY_MS)
            // Reset isCancelled so connect() doesn't bail out immediately
            isCancelled.set(false)
            connect()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Reconnection failed", e)
            false
        } finally {
            isReconnecting.set(false)
        }
    }

    /**
     * Disconnect VPN gracefully
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting VPN")
        isCancelled.set(true)
        client.externalHandle = 0

        // Use mutex to prevent race with connect(); tryLock keeps disconnect()
        // non-blocking so it stays responsive on the calling thread.
        if (connectionMutex.tryLock()) {
            try {
                // Update state
                if (currentState == ConnectionState.CONNECTED || currentState == ConnectionState.CONNECTING) {
                    currentState = ConnectionState.DISCONNECTING
                }

                // Disconnect native connection (this will interrupt any blocking operations).
                // If a connect flow is in flight, DEFER native teardown to it: the
                // connect coroutine is blocked inside nativeConnectWithHub and owns
                // the handle. Freeing here would either deadlock on connect_mutex or
                // free the connection out from under the connect thread (zombie
                // session + poisoned state for every later connect attempt).
                if (isConnectInFlight()) {
                    Log.w(TAG, "Connect in flight - deferring native teardown to connect flow")
                } else if (nativeHandle != 0L) {
                    teardownNativeConnection()
                }
            } finally {
                connectionMutex.unlock()
            }
        } else {
            // connect() holds the mutex; native teardown stays owned by the
            // connect flow via its connectInFlight guarantee.
            Log.w(TAG, "Connect in progress, skipping mutex-protected native teardown")
        }

        // Stop keepalive
        try {
            keepAliveManager?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping KeepAliveManager", e)
        }
        keepAliveManager = null

        statisticsJob?.cancel()
        statisticsJob = null
        reconnectJob?.cancel()
        reconnectJob = null

        // Stop TunTerminal first to avoid reading from closed interface
        try {
            tunTerminal?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TunTerminal", e)
        }
        tunTerminal = null
        
        // Close VPN interface
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null

        currentState = ConnectionState.DISCONNECTED
        Log.d(TAG, "VPN disconnected. Stats: sent=${bytesSent.get()} bytes (${packetsSent.get()} pkts), " +
                "received=${bytesReceived.get()} bytes (${packetsReceived.get()} pkts)")
    }

    /**
     * Quickly destroy native resources without graceful disconnect (non-blocking).
     * Sets isCancelled, stops TunTerminal, closes fd, and frees the native handle.
     * Does NOT send state change callbacks — caller is responsible for state updates.
     */
    fun destroyResources(): TeardownCompletion.Outcome {
        Log.d(TAG, "Destroying resources (fast cleanup)")
        isCancelled.set(true)
        currentState = ConnectionState.DISCONNECTED
        client.externalHandle = 0

        // Stage 2F-F.28W: teardown has been requested, not completed. Recording the
        // phase now — before any of the cleanup below — is what makes the registry
        // refuse a replacement attempt while this controller's native call may still
        // be running. It is the *last* release point in the process, so it must be
        // reached before the first potentially blocking native call, which is
        // `nativeDestroy` at the bottom of this method.
        NativeLifetimeRegistry.process.markTeardownRequested(nativeLease)

        statisticsJob?.cancel()
        statisticsJob = null
        reconnectJob?.cancel()
        reconnectJob = null

        // Stop keepalive manager
        try {
            keepAliveManager?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping KeepAliveManager", e)
        }
        keepAliveManager = null

        // Permanently cancel this controller's coroutine scope so background loops and timers die
        try {
            scope.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling scope", e)
        }

        // Stop TunTerminal first to avoid reading from closed interface
        try {
            tunTerminal?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TunTerminal", e)
        }
        tunTerminal = null

        // Close VPN interface
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null

        // Stage 2F-F.28Y: hand the native structure and the lease to the coordinator.
        //
        // F.28X found two ways this method could get it wrong, both of which are
        // structural rather than incidental:
        //
        //  - It destroyed the handle after a *bounded wait* expired. `softether_destroy`
        //    takes no lock the connect holds (`connect_mutex` is per-connection and wraps
        //    only `softether_connect_with_hub`), so on timeout it freed a structure a live
        //    connect was still using — and then released the lease, admitting a second
        //    native operation. "The deadline expired" is not "the connect returned".
        //  - It skipped that whole block when `nativeHandle == 0` and released anyway.
        //    A zero handle does not mean "no native operation": a flow that has called
        //    `nativeCreate` but not yet published its handle looks identical.
        //
        // The coordinator removes both: destruction requires that no registered flow can
        // still be executing, and on timeout ownership passes to the exiting flow, which
        // is the only thread that can know completion as a fact.
        // Stage 2F-F.28AA: return the outcome instead of only logging it. F.28Z found the
        // caller could not tell a confirmed teardown from one that gave up while a connect
        // was still running, because the outcome stopped here.
        val outcome = nativeLifecycle.requestTeardown(NATIVE_TEARDOWN_TIMEOUT_MS)
        // FIX-LOG-IDENTITY (G-2). Diagnosability only — no behavior change.
        //
        // The two "cleanup confirmed" lines below used to be emitted with no
        // identity, while the service's NOOP_REENTRANT line
        // (SoftEtherVpnService.kt:995-1001) and AndroidConnectionAdapter's
        // "NATIVE CLEANUP UNCONFIRMED" line both name the attempt and session.
        // A device log therefore read as a contradiction:
        //
        //   ConnectionController: Native cleanup confirmed: structure destroyed…
        //   [AUTO]                Teardown finished but NATIVE CLEANUP UNCONFIRMED
        //                         (teardown#7(session=1, NOOP_REENTRANT))…
        //
        // They describe two DIFFERENT teardowns (this attempt's own destroy vs a
        // second, re-entrant stopVpn for a session already torn down), so naming
        // the identity here is what lets a reader correlate them instead of
        // concluding the code disagrees with itself.
        //
        // `session` is the session this controller was created for (constructor
        // param, :52). `attempt` is this attempt's own native-lifetime lease id
        // (NativeLifetimeRegistry.Lease.attemptId, :115); it is null only for a
        // controller built outside a reserved session, which has no production
        // caller. Note this id is the native-lease namespace and is NOT the same
        // counter as TeardownCompletion.attemptId — the service's teardown-attempt
        // sequence. Both are named in the log so the two can be told apart.
        val teardownIdentity = "attempt=${nativeLease?.attemptId ?: "none"}, session=$sessionId"
        when (outcome) {
            NativeAttemptCoordinator.TeardownOutcome.CONFIRMED ->
                Log.d(TAG, "Native cleanup confirmed ($teardownIdentity): structure destroyed and slot released")

            NativeAttemptCoordinator.TeardownOutcome.ALREADY_CONFIRMED ->
                Log.d(TAG, "Native cleanup already confirmed by another teardown caller ($teardownIdentity)")

            NativeAttemptCoordinator.TeardownOutcome.UNRESOLVED -> {
                // Stage 2F-F.28W: report the unresolved state WITHOUT releasing. A later
                // attempt must stay refused; the exiting connect flow completes cleanup
                // when it actually returns, and only then is the slot freed.
                NativeLifetimeRegistry.process.markDeadlineExpired(nativeLease)
                Log.w(
                    TAG,
                    "Native cleanup unresolved ($teardownIdentity, ${nativeLifecycle.describe()}); " +
                            "ownership retained until the connect flow exits",
                )
            }
        }
        // Stage 2F-F.28AA: report the outcome in the same vocabulary the teardown-completion
        // signal uses, so a caller cannot invent a second, differently-shaped answer to
        // "did the native side actually finish?".
        return when (outcome) {
            NativeAttemptCoordinator.TeardownOutcome.CONFIRMED,
            NativeAttemptCoordinator.TeardownOutcome.ALREADY_CONFIRMED ->
                TeardownCompletion.Outcome.NATIVE_CONFIRMED

            NativeAttemptCoordinator.TeardownOutcome.UNRESOLVED ->
                TeardownCompletion.Outcome.NATIVE_UNRESOLVED
        }
    }

    /**
     * Whether this attempt's native cleanup has been confirmed complete.
     *
     * `false` means ownership is still held. Callers must treat it as "not finished"
     * rather than "failed" — the connect flow may yet complete cleanup on its own exit.
     */
    suspend fun awaitNativeCleanup(timeoutMs: Long): Boolean =
        nativeLifecycle.awaitCleanupConfirmed(timeoutMs)

    /**
     * Get current connection state
     */
    fun getState(): ConnectionState = currentState

    /**
     * Check if currently connected
     */
    fun isConnected(): Boolean = currentState == ConnectionState.CONNECTED

    /**
     * Get connection statistics
     */
    fun getStatistics(): ConnectionStatistics {
        return ConnectionStatistics(
            bytesSent = bytesSent.get(),
            bytesReceived = bytesReceived.get(),
            packetsSent = packetsSent.get(),
            packetsReceived = packetsReceived.get(),
            reconnectAttempts = reconnectAttempts.get()
        )
    }

    /**
     * Start data forwarding between TUN interface and SoftEther connection
     * This is the core data tunnel implementation
     */
    private fun startDataForwarding() {
        val tunInterface = vpnInterface
            ?: throw IllegalStateException("VPN interface not established")

        // Store reference to tunTerminal so we can stop it cleanly
        this.tunTerminal = TunTerminal(tunInterface, scope)
        val terminal = this.tunTerminal!!

        val keepAlive = KeepAliveManager(client)
        this.keepAliveManager = keepAlive

        // Start TUN interface reading.
        // Phase 13E: packets are sent directly from the TUN read loop as
        // (buffer, offset, length) slices — no per-packet copy, no queue,
        // no dedicated send coroutine. A slow VPN link now applies natural
        // backpressure to the TUN read instead of growing a queue. Native
        // softether_send serializes concurrent writers via its write mutex,
        // so sending here alongside keepalives is safe.
        terminal.start(
            onPacket = { buffer, offset, length ->
                // Packet from TUN (local system) -> send to VPN
                try {
                    val result = client.send(buffer, offset, length)
                    if (result > 0) {
                        bytesSent.addAndGet(result.toLong())
                        packetsSent.incrementAndGet()
                        maybePublishTrafficSnapshot()
                    } else if (result < 0 && isConnected() && !isCancelled.get()) {
                        Log.w(TAG, "Send failed: $result")
                        triggerReconnect()
                    }
                } catch (e: Exception) {
                    if (!isCancelled.get()) {
                        Log.e(TAG, "Send error", e)
                        if (isConnected()) {
                            triggerReconnect()
                        }
                    }
                }
            },
            onError = { error ->
                Log.e(TAG, "TUN interface error", error)
                if (!isCancelled.get()) {
                    // Don't call onError() here — it triggers stopVpn() in VpnService
                    // which destroys everything. Instead, let attemptReconnect handle
                    // the full lifecycle (tear down + reconnect + new TUN).
                    triggerReconnect()
                }
            }
        )

        // Receive loop: VPN -> TUN
        scope.launch {
            val receiveBuffer = ByteArray(65535)
            val batchLengths = IntArray(RX_BATCH_MAX_PACKETS)
            var receiveCount = 0
            while (isConnected() && !isCancelled.get()) {
                try {
                    // Phase 13D: drain multiple frames per JNI crossing
                    val total = client.receiveBatch(receiveBuffer, batchLengths)
                    when {
                        total > 0 -> {
                            // Valid data received — write frames back-to-back
                            var offset = 0
                            var written = 0
                            for (len in batchLengths) {
                                if (len == 0) break
                                val writeResult = terminal.write(receiveBuffer, offset, len)
                                if (writeResult > 0) written++
                                offset += len
                            }
                            if (written > 0) {
                                bytesReceived.addAndGet(total.toLong())
                                packetsReceived.addAndGet(written.toLong())
                                maybePublishTrafficSnapshot()
                            }
                            // Periodically protect additional sockets (multi-connection)
                            receiveCount++
                            if (receiveCount % 50 == 0) {
                                protectAdditionalSockets()
                            }
                        }
                        total == 0 -> {
                            // Keepalive or no data. Phase 13E: no Kotlin-side
                            // delay — native receive blocks up to 100ms in
                            // poll() when idle, so this loop only wakes ~10x/s
                            // at idle and instantly when data arrives.
                            keepAlive.recordReceived()
                        }
                        else -> {
                            // Error receiving
                            Log.e(TAG, "Receive error: $total")
                            if (isConnected() && !isCancelled.get()) {
                                triggerReconnect()
                            }
                            break
                        }
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Receive loop error", e)
                    if (isConnected() && !isCancelled.get()) {
                        triggerReconnect()
                    }
                    break
                }
            }
        }

        // Start keepalive
        startKeepalive(keepAlive)
    }

    private fun triggerReconnect() {
        if (isCancelled.get() || nativeLifecycle.isDestroyed() || currentState == ConnectionState.DISCONNECTED) {
            Log.w(TAG, "triggerReconnect skipped: cancelled=${isCancelled.get()}, destroyed=${nativeLifecycle.isDestroyed()}, state=$currentState")
            return
        }
        synchronized(this) {
            if (reconnectJob?.isActive == true) return
            reconnectJob = scope.launch {
                try {
                    attemptReconnect()
                } finally {
                    isReconnecting.set(false)
                }
            }
        }
    }

    /**
     * Attempt automatic reconnection with full lifecycle:
     * tear down old TUN/native → reconnect → DHCP → establish VPN interface → restart data forwarding
     */
    private suspend fun attemptReconnect() {
        if (isCancelled.get() || nativeLifecycle.isDestroyed() || currentState == ConnectionState.DISCONNECTED || isReconnecting.getAndSet(true)) {
            Log.w(TAG, "Reconnection skipped: cancelled=${isCancelled.get()}, destroyed=${nativeLifecycle.isDestroyed()}, state=$currentState")
            return
        }

        // Declared before the try because several branches below return before any flow is
        // registered, and the finally must still be able to run `exit()` on whichever
        // flow — if any — this attempt actually created.
        var flow: NativeAttemptCoordinator.Flow? = null
        try {
            if (reconnectAttempts.incrementAndGet() >= MAX_RECONNECT_ATTEMPTS) {
                Log.e(TAG, "Max reconnection attempts reached")
                // Surface as ERROR (not silent DISCONNECTED) so the UI shows the
                // disconnect-by-error notification and STATE_ERROR broadcast.
                currentState = ConnectionState.ERROR
                // FIX-G1-IMPL (Option A). Reset BEFORE onError so the counter is
                // clean for any new session that starts as a result of the teardown
                // this onError triggers. `reconnectAttempts` is shared with
                // connect()'s retry loop (see :258/:261), so a controller that
                // survived one exhausted reconnect would otherwise hand a
                // pre-exhausted counter to the next connect.
                reconnectAttempts.set(0)
                // FIX-G1-IMPL (Option A). Route through the single onError channel.
                // The service maps this to stopVpn(disconnectByError = true)
                // (SoftEtherVpnService.kt:703-725, dispatch at :724), which releases
                // controller / tunOwnership / connectionJob and hands the native
                // handle to destroyResources() — the only place the process-wide
                // lease can legitimately be released.
                //
                // The local disconnect() that used to sit here is deliberately
                // GONE. disconnect() is a controller-local teardown: it touches
                // none of the three service fields the start gate reads
                // (SoftEtherVpnService.kt:541-543), so it could never clear
                // sessionResourcesLive, and running it alongside onError recreated
                // exactly the dual-channel teardown the F.28 lifecycle work exists
                // to prevent. onError is now the sole channel.
                onError("Connection lost - max reconnection attempts reached")
                return
            }

            Log.w(TAG, "Attempting automatic reconnection (${reconnectAttempts.get()}/$MAX_RECONNECT_ATTEMPTS)")

            if (isCancelled.get() || nativeLifecycle.isDestroyed() || currentState == ConnectionState.DISCONNECTED) {
                Log.w(TAG, "Reconnection aborted: destroyed or disconnected")
                return
            }

            // Reset isCancelled so loops and subsequent operations can proceed
            isCancelled.set(false)

            // Tear down old data forwarding (stop TunTerminal to avoid reading from stale fd)
            try {
                tunTerminal?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping TunTerminal during reconnect", e)
            }
            tunTerminal = null

            // Close old VPN interface
            try {
                vpnInterface?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing VPN interface during reconnect", e)
            }
            vpnInterface = null

// Disconnect old native handle.
            // Stage 2F-F.28AL-U: through the shared atomic claim, so this teardown
            // can never race the coordinator's cleanup (or disconnect()'s) for the
            // same handle — exactly one of them destroys it.
            claimAndDestroyNativeHandle()


            if (isCancelled.get() || nativeLifecycle.isDestroyed() || currentState == ConnectionState.DISCONNECTED) {
                Log.w(TAG, "Reconnection aborted after teardown")
                return
            }

            currentState = ConnectionState.CONNECTING
            onStateChange(ConnectionState.CONNECTING)

            // Wait before reconnecting
            delay(RECONNECT_DELAY_MS)

            if (isCancelled.get() || nativeLifecycle.isDestroyed() || currentState == ConnectionState.DISCONNECTED) {
                Log.w(TAG, "Reconnection aborted after delay")
                return
            }

            // Raise connectInFlight BEFORE creating the new handle: this flow
            // now owns the native lifecycle and blocks inside
            // nativeConnectWithHub. Without the flag, disconnect()/destroyResources()
            // see !isConnectInFlight() + nativeHandle != 0 and destroy the
            // connection under the blocked reconnect thread (use-after-free
            // crash inside softether_protocol_login / ssl_write). Cleared in
            // the outer finally below.
            //
            // Stage 2F-F.28Y: the reconnect flow participates in the same lifecycle
            // protocol as the initial connect. A reconnect replaces the handle inside an
            // attempt that already owns the lease, so it must be registered as a flow
            // like any other — otherwise teardown could not tell "no flow running" from
            // "a reconnect is mid-nativeCreate", which is the zero-handle hole F.28X
            // found in exactly this function.
            val registered = nativeLifecycle.beginFlow()
            if (registered == null) {
                Log.w(TAG, "Teardown began before the reconnect flow was registered; aborting")
                return
            }
            flow = registered
            connectInFlight.set(true)

            // Create new native connection
            val created = client.nativeCreate()
            registered.publishHandle(created)
            nativeHandle = created
            if (created == 0L) {
                throw Exception("Failed to create native connection for reconnect")
            }

            client.setTimeout(nativeHandle, config.connectTimeoutMs)

            // Set auth type
            val hubName = config.virtualHub.ifEmpty { "VPN" }
            if (config.authMethod != vn.unlimit.softether.model.AuthMethod.AUTO) {
                val authTypeInt = when (config.authMethod) {
                    vn.unlimit.softether.model.AuthMethod.ANONYMOUS -> 0
                    vn.unlimit.softether.model.AuthMethod.PASSWORD -> 1
                    vn.unlimit.softether.model.AuthMethod.PLAIN_PASSWORD -> 2
                    vn.unlimit.softether.model.AuthMethod.AUTO -> 0
                }
                client.nativeSetAuthType(nativeHandle, authTypeInt)
            }

            // Phase 17: re-apply duplex mode to the fresh reconnect handle
            // (nativeCreate resets half_connection to 0, i.e. full-duplex).
            val reconnectFullDuplex = DuplexModeSelector.resolve(service, config)
            client.setHalfConnection(nativeHandle, !reconnectFullDuplex)
            Log.d(TAG, "Reconnect duplex mode: ${if (reconnectFullDuplex) "FULL" else "HALF"}")

            // Connect to server (TLS + protocol + auth + session)
            startNativeStateMonitor()
            val reconnectClientInfo = buildClientInfo(0)
            val result = try {
                client.nativeConnectWithHub(
                    nativeHandle,
                    config.serverHost,
                    config.serverPort,
                    config.username,
                    config.password,
                    hubName,
                    config.useTcp,
                    reconnectClientInfo.productName, reconnectClientInfo.productVersion, reconnectClientInfo.productBuild,
                    reconnectClientInfo.osName, reconnectClientInfo.osVersion, reconnectClientInfo.osProductId,
                    reconnectClientInfo.hostName, reconnectClientInfo.clientIpAddress, reconnectClientInfo.clientPort,
                    reconnectClientInfo.serverHostName, reconnectClientInfo.serverIpAddress, reconnectClientInfo.serverPort
                )
            } finally {
                stopNativeStateMonitor()
            }

            if (result != 0) {
                throw Exception("Reconnection failed with error code: $result")
            }

            if (isCancelled.get() || nativeLifecycle.isDestroyed() || currentState == ConnectionState.DISCONNECTED) {
                // Stage 2F-F.28AL-U: single-owner claim, as every cleanup path uses.
                claimAndDestroyNativeHandle()
                return
            }

            // Protect VPN socket from routing through TUN
            val socketFd = client.nativeGetSocketFd(nativeHandle)
            if (socketFd >= 0) {
                if (!service.protect(socketFd)) {
                    Log.e(TAG, "Failed to protect VPN socket fd=$socketFd during reconnect")
                } else {
                    Log.d(TAG, "VPN socket fd=$socketFd protected during reconnect")
                }
            }

            // Protect RUDP UDP socket
            val rudpFd = client.nativeGetRudpSocketFd(nativeHandle)
            if (rudpFd >= 0) {
                if (!service.protect(rudpFd)) {
                    Log.e(TAG, "Failed to protect RUDP socket fd=$rudpFd during reconnect")
                } else {
                    Log.d(TAG, "RUDP socket fd=$rudpFd protected during reconnect")
                }
            }

            // Protect NAT-T RUDP transport UDP socket during reconnect
            val natTUdpFd = client.nativeGetNatTUdpSocketFd(nativeHandle)
            if (natTUdpFd >= 0) {
                if (!service.protect(natTUdpFd)) {
                    Log.e(TAG, "Failed to protect NAT-T UDP socket fd=$natTUdpFd during reconnect")
                } else {
                    Log.d(TAG, "NAT-T UDP socket fd=$natTUdpFd protected during reconnect")
                }
            }

            client.externalHandle = nativeHandle

            // Perform DHCP over the new tunnel
            Log.d(TAG, "Starting DHCP over reconnected tunnel...")
            val dhcpResult = client.doDhcp(nativeHandle)
            if (dhcpResult != null) {
                Log.d(TAG, "DHCP success on reconnect: IP=${dhcpResult.assignedIp}/${dhcpResult.prefixLength}")
                assignedLocalIp = dhcpResult.assignedIp
                // DNS: keep the app-configured public resolvers (see primary connect path).
                // Stage 2F-F.28J: re-check after DHCP, BEFORE establishing.
                //
                // This mirrors the initial-connect guard and closes the window the
                // post-install check used to leave open: destroyResources() only *requests*
                // cancellation, so the blocking native connect and doDhcp() above can
                // outlive it and reach the establishment with isCancelled already set.
                //
                // This check is defence in depth, not the ownership boundary. Cancellation
                // can still arrive immediately after it, so installTun() below remains the
                // authoritative guard — it re-tests ownership inside the same critical
                // section in which the descriptor is created.
                if (isCancelled.get() || nativeLifecycle.isDestroyed() || currentState == ConnectionState.DISCONNECTED) {
                    Log.d(TAG, "Reconnection cancelled during DHCP, not establishing the interface")
                // Stage 2F-F.28AL-U: single-owner claim, as every cleanup path uses.
                claimAndDestroyNativeHandle()
                return
            }

                val dhcpConfig = config.copy(
                    localAddress = dhcpResult.assignedIp,
                    prefixLength = dhcpResult.prefixLength,
                )
                vpnInterface = installTun(dhcpConfig)
            } else {
                Log.w(TAG, "DHCP failed on reconnect, falling back to hardcoded config")
                assignedLocalIp = config.localAddress
                vpnInterface = installTun(config)
            }

            if (isCancelled.get() || nativeLifecycle.isDestroyed() || currentState == ConnectionState.DISCONNECTED) {
                Log.d(TAG, "Reconnection aborted after establishVpnInterface")
                // Stage 2F-F.28J: release through the service, not by closing the local
                // reference. The service's ownership record must be cleared in the same
                // critical section that closes the descriptor, otherwise the service
                // keeps registering a closed descriptor and the start gate would refuse
                // every later connection — the exact latch reported in F.28I.
                vpnInterface = null
                service.releaseVpnInterface(sessionId)
                // Stage 2F-F.28AL-U: single-owner claim, as every cleanup path uses.
                claimAndDestroyNativeHandle()
                return
            }

            // Transition to CONNECTED and restart data forwarding
            // Stage 2F-F.28W: reconnect keeps the same lease — it replaces the handle
            // inside an attempt that already owns the slot, so it must not re-acquire.
            NativeLifetimeRegistry.process.markConnected(nativeLease)
            currentState = ConnectionState.CONNECTED
            resetTrafficPublishing(publishSnapshot = true)
            startDataForwarding()
            startStatisticsLogging()

            reconnectAttempts.set(0)
            Log.d(TAG, "Reconnection successful — data forwarding restarted")

        } catch (e: Exception) {
            Log.e(TAG, "Reconnection attempt failed", e)
            // Clean up partial native state left by failed reconnect.
            // Stage 2F-F.28AL-U: single-owner claim, as every cleanup path uses.
            claimAndDestroyNativeHandle()
            // Transition to DISCONNECTED so the activity learns the connection is gone
            currentState = ConnectionState.DISCONNECTED
            onStateChange(ConnectionState.DISCONNECTED)
            // FIX-G1-IMPL (Option A). Reset the counter so a later session is not
            // born pre-exhausted — same rationale as the budget branch above.
            reconnectAttempts.set(0)
            // FIX-G1-IMPL (Option A). This branch previously had NO onError call at
            // all, so a reconnect that failed by *exception* (rather than by budget)
            // never reached the service and stranded the session with controller /
            // tunOwnership / connectionJob still held — the harsher half of G-1.
            //
            // Ordering choice: onError runs AFTER the local cleanup above, matching
            // performConnect()'s terminal convention (:434-437: tear the native
            // handle down, then propagate). The reverse order was rejected on
            // evidence: onError reaches stopVpn(SoftEtherVpnService.kt:724), which
            // synchronously snapshots and nulls the service's `controller` and then
            // dispatches destroyResources() on a background coroutine (:1041-1054).
            // Calling onError first would therefore have this method's
            // claimAndDestroyNativeHandle() racing the service's destroyResources()
            // for the same handle — precisely the dual-teardown shape the single-owner
            // claim and the F.28 lifecycle work exist to eliminate. Local cleanup
            // first keeps exactly one owner for the handle; when
            // claimAndDestroyNativeHandle() has already claimed it, the coordinator
            // reads the settled slot as NothingOutstanding, so the service's own
            // teardown then reports NATIVE_CONFIRMED honestly rather than fabricating
            // it.
            onError("Reconnect failed: ${e.message}")
        } finally {
            // Stage 2F-F.28Y: same contract as performConnect — this is the last thing
            // the reconnect flow does, and it is where a teardown that timed out waiting
            // for this flow gets its cleanup completed. `flow` is null when the attempt
            // bailed out before registering one, in which case there is nothing to exit
            // and no native call was made.
            connectInFlight.set(false)
            flow?.exit()
            isReconnecting.set(false)
        }
    }

    /**
     * Start keepalive monitoring
     */
    private fun startKeepalive(keepAliveManager: KeepAliveManager) {
        keepAliveManager.setInterval(config.keepAliveIntervalMs.toLong())
        keepAliveManager.setTimeout(10000L) // 10 second timeout
        keepAliveManager.start()

        scope.launch {
            while (isConnected() && !isCancelled.get()) {
                try {
                    delay(1000) // Check every second

                    if (keepAliveManager.shouldSendKeepAlive()) {
                        // Keepalive is handled in native layer
                        keepAliveManager.recordSent()
                    }

                    if (keepAliveManager.isConnectionDead()) {
                        Log.e(TAG, "Connection appears dead (keepalive timeout)")
                        triggerReconnect()
                        break
                    }
                } catch (e: CancellationException) {
                    break
                }
            }
            keepAliveManager.stop()
        }
    }

    /**
     * Start periodic statistics logging
     */
    private fun startStatisticsLogging() {
        statisticsJob?.cancel()
        statisticsJob = scope.launch {
            while (!isCancelled.get() &&
                !nativeLifecycle.isDestroyed() &&
                currentState == ConnectionState.CONNECTED
            ) {
                try {
                    delay(STATS_INTERVAL_MS)
                    if (isConnected() && !isCancelled.get() && !nativeLifecycle.isDestroyed()) {
                        publishTrafficSnapshot()
                        Log.d(TAG, "Stats: sent=${bytesSent.get()} bytes (${packetsSent.get()} pkts), " +
                                "received=${bytesReceived.get()} bytes (${packetsReceived.get()} pkts)")
                    }
                } catch (e: CancellationException) {
                    break
                }
            }
        }
    }

    private fun resetTrafficPublishing(publishSnapshot: Boolean) {
        lastPublishedSentBytes = bytesSent.get()
        lastPublishedReceivedBytes = bytesReceived.get()
        lastPublishedAtMs = System.currentTimeMillis()
        if (publishSnapshot) {
            onTrafficUpdate(
                SoftEtherTrafficSnapshot(
                    inBytes = lastPublishedReceivedBytes,
                    outBytes = lastPublishedSentBytes,
                    diffInBytes = 0L,
                    diffOutBytes = 0L,
                    packetsIn = packetsReceived.get(),
                    packetsOut = packetsSent.get(),
                    intervalMs = STATS_INTERVAL_MS,
                    timestampMs = lastPublishedAtMs
                )
            )
        }
    }

    private fun publishTrafficSnapshot() {
        val now = System.currentTimeMillis()
        val currentSentBytes = bytesSent.get()
        val currentReceivedBytes = bytesReceived.get()
        val interval = (now - lastPublishedAtMs).coerceAtLeast(1L)
        val snapshot = SoftEtherTrafficSnapshot(
            inBytes = currentReceivedBytes,
            outBytes = currentSentBytes,
            diffInBytes = (currentReceivedBytes - lastPublishedReceivedBytes).coerceAtLeast(0L),
            diffOutBytes = (currentSentBytes - lastPublishedSentBytes).coerceAtLeast(0L),
            packetsIn = packetsReceived.get(),
            packetsOut = packetsSent.get(),
            intervalMs = interval,
            timestampMs = now
        )
        lastPublishedSentBytes = currentSentBytes
        lastPublishedReceivedBytes = currentReceivedBytes
        lastPublishedAtMs = now
        onTrafficUpdate(snapshot)
    }

    private fun maybePublishTrafficSnapshot() {
        val now = System.currentTimeMillis()
        if (now - lastPublishedAtMs >= STATS_INTERVAL_MS) {
            publishTrafficSnapshot()
            // Phase 13G: surface native health counters alongside the
            // traffic snapshot (same 1 s throttle, debug-level only)
            client.getStats()?.let { st ->
                Log.d(TAG,
                    "stats: tx=${st.txPackets}p/${st.txBytes}B rx=${st.rxPackets}p/${st.rxBytes}B " +
                    "skipped=${st.rxSkippedBlocks} rudp(rx=${st.rudpRxPackets} ovf=${st.rudpOverflowCount} " +
                    "gaps=${st.rudpTickGaps} susp=${st.rudpDataSuspended})")
            }
        }
    }

    private fun startNativeStateMonitor() {
        stateMonitorJob?.cancel()
        stateMonitorJob = scope.launch {
            var lastBroadcastTime = 0L
            while (!isCancelled.get() && nativeHandle != 0L) {
                try {
                    val mapped = mapNativeState(client.nativeGetState(nativeHandle))
                    if (mapped != null &&
                        mapped != ConnectionState.DISCONNECTED &&
                        mapped != ConnectionState.CONNECTED &&
                        mapped != currentState
                    ) {
                        val now = System.currentTimeMillis()
                        // Ensure minimum 100ms between state updates
                        if (now - lastBroadcastTime >= 100) {
                            currentState = mapped
                            lastBroadcastTime = now
                        }
                    }

                    if (currentState == ConnectionState.CONNECTED ||
                        currentState == ConnectionState.DISCONNECTING ||
                        currentState == ConnectionState.DISCONNECTED
                    ) {
                        break
                    }
                    delay(50) // Reduced delay for better responsiveness
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private suspend fun stopNativeStateMonitor() {
        stateMonitorJob?.cancel()
        stateMonitorJob = null
    }

    private fun mapNativeState(nativeState: Int): ConnectionState? {
        return when (nativeState) {
            0 -> ConnectionState.DISCONNECTED
            1 -> ConnectionState.CONNECTING
            2 -> ConnectionState.TLS_HANDSHAKE
            3 -> ConnectionState.PROTOCOL_HANDSHAKE
            4 -> ConnectionState.AUTHENTICATING
            5 -> ConnectionState.SESSION_SETUP
            6 -> ConnectionState.CONNECTED
            7 -> ConnectionState.DISCONNECTING
            else -> null
        }
    }

    // Track FDs we've already protected to avoid redundant protect() calls.
    // Synchronized because teardown (connect/disconnect/reconnect threads) clears
    // it while the receive loop may be calling contain/add concurrently.
    // Cleared on every native teardown so stale FDs from a previous session can
    // never suppress protect() for a recycled fd number in a fresh connection.
    private val protectedFds = Collections.synchronizedSet(mutableSetOf<Int>())

    /**
     * Protect any new additional TCP sockets from routing through TUN.
     * Called periodically from the receive loop after additional connections are established.
     */
    private fun protectAdditionalSockets() {
        val allFds = client.getAllSocketFds() ?: return
        val natTUdpFd = client.nativeGetNatTUdpSocketFd(nativeHandle)
        for (fd in allFds) {
            if (fd >= 0 && fd !in protectedFds) {
                if (service.protect(fd)) {
                    protectedFds.add(fd)
                    Log.d(TAG, "Additional socket fd=$fd protected from TUN routing")
                } else {
                    Log.e(TAG, "Failed to protect additional socket fd=$fd")
                }
            }
        }
        if (natTUdpFd >= 0 && natTUdpFd !in protectedFds) {
            if (service.protect(natTUdpFd)) {
                protectedFds.add(natTUdpFd)
                Log.d(TAG, "NAT-T UDP socket fd=$natTUdpFd protected from TUN routing")
            } else {
                Log.e(TAG, "Failed to protect NAT-T UDP socket fd=$natTUdpFd")
            }
        }
    }
}

/**
 * Connection statistics data class
 */
data class ConnectionStatistics(
    val bytesSent: Long,
    val bytesReceived: Long,
    val packetsSent: Long,
    val packetsReceived: Long,
    val reconnectAttempts: Int
) {
    fun getTotalBytes(): Long = bytesSent + bytesReceived
    fun getTotalPackets(): Long = packetsSent + packetsReceived
}
