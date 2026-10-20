package vn.unlimit.softether

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.unlimit.softether.controller.ConnectionController
import vn.unlimit.softether.model.ConnectionConfig
import vn.unlimit.softether.model.ConnectionState

/**
 * SoftEther VPN Service - Main VPN service implementation for Android
 */
class SoftEtherVpnService : VpnService() {
    /** Listener interface — same pattern as OpenVPN's VpnStatus.StateListener */
    interface StateListener {
        fun onSoftEtherStateChanged(state: String, assignedIp: String)
    }

    interface TrafficListener {
        fun onSoftEtherTrafficUpdated(snapshot: SoftEtherTrafficSnapshot)
    }

    /**
     * Stage 2F-F.28A-REVIEW: resource-destruction completion.
     *
     * Deliberately NOT expressed as a [STATE_DISCONNECTED]/[STATE_ERROR] value.
     * Those are published the moment teardown *starts*, so the UI can react
     * immediately, and `stopVpn` even publishes them on the re-entrant no-op
     * path before anything is touched. Treating them as "teardown finished" is
     * what made `AndroidConnectionAdapter.disconnect()` return while
     * `destroyResources()` was still running.
     *
     * Stage 2F-F.28AA: the callback now carries a [TeardownCompletion] instead of
     * carrying nothing. F.28Z found the argument-less form was emitted
     * unconditionally, so a consumer could not distinguish "native cleanup
     * confirmed" from "the wait elapsed while a connect was still running" — and
     * therefore could not tell whether it was safe to start another native
     * operation. The result type makes that distinction explicit and carries the
     * teardown identity a consumer needs in order to reject a stale completion.
     */
    fun interface TeardownListener {
        fun onSoftEtherTeardownComplete(result: TeardownCompletion)
    }

    /**
     * Stage 2F-F.28AA: a start request was refused because the process-wide native
     * slot is still owned by an unresolved native operation.
     *
     * This is deliberately a *separate* channel from [TeardownListener]. It is not a
     * failure of the server that was selected — it is this application's own previous
     * operation still finishing — and F.28Z found that Auto Mode, receiving only a
     * silent refusal, classified it as an unreachable server and rotated through
     * healthy servers until it reported `ERROR_NO_SERVER`.
     *
     * @param sessionId the session whose start was refused.
     * @param owner a human-readable description of the still-unresolved operation, for
     *   diagnostics. Never interpreted by callers.
     */
    fun interface NativeBusyListener {
        fun onSoftEtherNativeBusy(sessionId: Int, owner: String)
    }

    companion object {
        private const val TAG = "SoftEtherVpnService"
        private const val NOTIFICATION_CHANNEL_ID = "SoftEtherVPN"
        private const val NOTIFICATION_CHANNEL_ERROR_ID = "SoftEtherVPN_Error"
        private const val NOTIFICATION_ID = 1001
        private const val TRAFFIC_PREFS_SUFFIX = "_preferences"
        private const val DOWNLOADED_DATA_KEY = "downloaded_data"
        private const val UPLOADED_DATA_KEY = "uploaded_data"
        private const val KEY_ULA_V6 = "ula_v6"

        /**
         * Stage 2F-F.28Y: how long teardown waits for native cleanup to be *confirmed*
         * before it reports teardown complete anyway.
         *
         * Longer than the controller's own 500 ms teardown deadline, because the normal
         * slow path is "the connect flow takes a moment longer than the deadline to come
         * back and then frees its own handle". A wait that outlives that window keeps
         * Auto Mode from rotating servers against a slot that is about to free up. It is
         * still a bound: this runs on `Dispatchers.IO`, never on the main thread, and a
         * timeout is reported rather than treated as success.
         */
        private const val NATIVE_CLEANUP_CONFIRM_TIMEOUT_MS = 2_000L

        // Actions (kept for service start/stop intents)
        const val ACTION_CONNECT = "vn.unlimit.softether.CONNECT"
        const val ACTION_DISCONNECT = "vn.unlimit.softether.DISCONNECT"

        /**
         * Stage 2F-F.28AD-B: the teardown-attempt identity a caller has already allocated
         * for its own `ACTION_DISCONNECT`.
         *
         * ## Why a caller supplies it at all
         *
         * The attempt id is what makes a teardown completion *identifiable*, and Auto Mode
         * needs that identity **before** it dispatches the disconnect — because it must arm a
         * waiter for a specific teardown, and a waiter armed blind could previously be
         * satisfied by any newer completion. The id used to be allocated only here, inside
         * `stopVpn`, which is strictly after the waiter was armed. A caller may therefore
         * hand its own id in so that the completion it eventually receives is the one it is
         * actually waiting for.
         *
         * ## Optional by design
         *
         * Many callers of `ACTION_DISCONNECT` have no waiter at all (a UI disconnect button,
         * the tracker, the notification action). They omit this extra and the service
         * allocates an id itself, exactly as before. A supplied id is used verbatim; the
         * service never reuses or re-interprets one.
         */
        const val EXTRA_TEARDOWN_ATTEMPT_ID = "vn.unlimit.softether.EXTRA_TEARDOWN_ATTEMPT_ID"

        // State string constants
        const val STATE_CONNECTED = "CONNECTED"
        const val STATE_DISCONNECTED = "DISCONNECTED"
        const val STATE_ERROR = "ERROR"
        const val STATE_CONNECTING = "CONNECTING"
        const val STATE_TLS_HANDSHAKE = "TLS_HANDSHAKE"
        const val STATE_PROTOCOL_HANDSHAKE = "PROTOCOL_HANDSHAKE"
        const val STATE_AUTHENTICATING = "AUTHENTICATING"
        const val STATE_SESSION_SETUP = "SESSION_SETUP"
        const val STATE_DISCONNECTING = "DISCONNECTING"

        // Extras
        const val EXTRA_CONFIG = "config"

        // Static state — survives Activity recreation, updated on every state change
        var currentState: String = STATE_DISCONNECTED
            private set
        var currentAssignedIp: String = ""
            private set
        var currentTrafficSnapshot: SoftEtherTrafficSnapshot = SoftEtherTrafficSnapshot.EMPTY
            private set
        var lastTrafficSnapshot: SoftEtherTrafficSnapshot = SoftEtherTrafficSnapshot.EMPTY
            private set
        var mDisplaySpeed: Boolean = true

        private val stateListeners = mutableListOf<StateListener>()
        private val trafficListeners = mutableListOf<TrafficListener>()
        private val teardownListeners = mutableListOf<TeardownListener>()
        private val nativeBusyListeners = mutableListOf<NativeBusyListener>()

        /**
         * Stage 2F-F.28AA: strictly increasing per `stopVpn` call.
         *
         * Deliberately in the companion, not an instance field: the listener registry is
         * also static, and a service instance recreated mid-process restarts instance
         * state. A per-instance counter would hand the new instance id `1`, which every
         * consumer would correctly reject as older than the stale id `N` it already saw.
         */
        private val teardownAttemptSeq = java.util.concurrent.atomic.AtomicInteger(0)

        /**
         * Stage 2F-F.28AD-B: allocate the next teardown-attempt identity.
         *
         * Public so a caller that wants to wait for one *specific* teardown can obtain that
         * teardown's identity up front and pass it back through
         * [EXTRA_TEARDOWN_ATTEMPT_ID]. Without this, a waiter would have to be armed before
         * the identity exists, and could then only be matched by recency — which is what let
         * an unrelated newer completion satisfy it.
         *
         * Every id comes from this one counter, so a supplied id and a self-allocated id are
         * directly comparable and strictly ordered.
         */
        fun nextTeardownAttemptId(): Int = teardownAttemptSeq.incrementAndGet()

        private val mainHandler = Handler(Looper.getMainLooper())

        fun addTeardownListener(listener: TeardownListener) {
            if (!teardownListeners.contains(listener)) {
                teardownListeners.add(listener)
            }
        }

        fun removeTeardownListener(listener: TeardownListener) {
            teardownListeners.remove(listener)
        }

        fun addNativeBusyListener(listener: NativeBusyListener) {
            if (!nativeBusyListeners.contains(listener)) {
                nativeBusyListeners.add(listener)
            }
        }

        fun removeNativeBusyListener(listener: NativeBusyListener) {
            nativeBusyListeners.remove(listener)
        }

        /**
         * Stage 2F-F.28AA: publish what the teardown actually achieved.
         *
         * The [result] is the single source of truth for a consumer. In particular this
         * method no longer decides, by itself, that teardown "completed": it forwards the
         * outcome decided by the coordinator (native cleanup confirmed or not) and by the
         * stop path (real teardown, or a re-entrant no-op).
         */
        private fun notifyTeardownComplete(result: TeardownCompletion) {
            mainHandler.post {
                teardownListeners.toList().forEach { it.onSoftEtherTeardownComplete(result) }
            }
        }

        /**
         * Stage 2F-F.28AA: publish that a start was refused because the native slot is
         * busy. Separate from [notifyTeardownComplete] so a refusal can never be read as
         * a completed teardown, and so a consumer can act on it without cancelling anyone
         * else's teardown waiter.
         */
        private fun notifyNativeBusy(sessionId: Int, owner: String) {
            mainHandler.post {
                nativeBusyListeners.toList().forEach { it.onSoftEtherNativeBusy(sessionId, owner) }
            }
        }

        fun addStateListener(listener: StateListener) {
            if (!stateListeners.contains(listener)) {
                stateListeners.add(listener)
                // Immediately deliver current state (same as OpenVPN's mLaststate replay)
                listener.onSoftEtherStateChanged(currentState, currentAssignedIp)
            }
        }

        fun removeStateListener(listener: StateListener) {
            stateListeners.remove(listener)
        }

        fun addTrafficListener(listener: TrafficListener) {
            if (!trafficListeners.contains(listener)) {
                trafficListeners.add(listener)
                listener.onSoftEtherTrafficUpdated(currentTrafficSnapshot)
            }
        }

        fun removeTrafficListener(listener: TrafficListener) {
            trafficListeners.remove(listener)
        }

        /**
         * Zeroes the persisted lifetime totals. Called by the Status screen's
         * "clear statistics" action: these counters are written by this service
         * independently of de.blinkt.openvpn's TotalTraffic, so the app has to
         * clear them explicitly or they get re-read and re-displayed after the
         * clear.
         *
         * Takes a [Context] rather than reading the running service, because the
         * service instance is not guaranteed to exist while the user is on the
         * Status screen.
         */
        fun clearPersistedTraffic(context: Context) {
            context.applicationContext.getSharedPreferences(
                context.packageName + TRAFFIC_PREFS_SUFFIX,
                Context.MODE_PRIVATE
            ).edit()
                .putLong(DOWNLOADED_DATA_KEY, 0L)
                .putLong(UPLOADED_DATA_KEY, 0L)
                .apply()
        }

        var notificationTargetActivity: Class<*>? = null

        private fun notifyListeners(state: String, assignedIp: String) {
            currentState = state
            currentAssignedIp = assignedIp
            mainHandler.post {
                stateListeners.forEach { it.onSoftEtherStateChanged(state, assignedIp) }
            }
        }

        private fun notifyTrafficListeners(snapshot: SoftEtherTrafficSnapshot) {
            currentTrafficSnapshot = snapshot
            if (snapshot.inBytes > 0L || snapshot.outBytes > 0L || snapshot.diffInBytes > 0L || snapshot.diffOutBytes > 0L) {
                lastTrafficSnapshot = snapshot
            }
            mainHandler.post {
                trafficListeners.forEach { it.onSoftEtherTrafficUpdated(snapshot) }
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var controller: ConnectionController? = null

    /**
     * Stage 2F-F.28J: TUN establishment is owned by a session, not by the service.
     *
     * This replaces a plain service-wide `vpnInterface` field. A plain field could not
     * express *whose* descriptor it held, so a per-session ConnectionController wrote
     * into it and a stale controller could replace a live session's descriptor — or
     * leave the service registering a descriptor it had already closed, which the start
     * gate would then read as a live resource and refuse every later connect.
     *
     * The descriptor itself is closed through [TunOwnershipCoordinator]; see that class
     * for the ownership protocol and the locking argument.
     */
    private val tunOwnership =
        TunOwnershipCoordinator<ParcelFileDescriptor> { descriptor ->
            runCatching { descriptor.close() }
        }
/**
     * Stage 2F-F.28: the session lifecycle decisions live in a testable seam.
     * These read-only accessors keep the original names usable at call sites;
     * every transition goes through [sessionPolicy] so the policy stays the one
     * place that owns the flags.
     */
    private val sessionPolicy = SoftEtherSessionPolicy()
    private val mWasConnected: Boolean get() = sessionPolicy.wasConnected
    private var mIsUserDisconnect = false
    private var lastStateUpdateTime = 0L
    private var pendingStateUpdate: (() -> Unit)? = null
    private var currentSessionName: String? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Network available")
            controller?.onNetworkChanged(true)
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Network lost")
            controller?.onNetworkChanged(false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        registerNetworkReceiver()
    }

    private fun triggerDisconnectNotification() {
        // Cancel the persistent status notification first
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)

        val channelId = NOTIFICATION_CHANNEL_ERROR_ID

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Delete old channel to ensure new settings (sound/vibration) take effect
            // notificationManager.deleteNotificationChannel(channelId) // Use with caution, might be annoying if called often

            val channel = NotificationChannel(
                channelId,
                getString(R.string.softether_channel_name_error),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.softether_channel_description_error)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 250, 250)
                
                // Set sound
                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                setSound(soundUri, AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build())
                
                // Force show lights as well for visibility
                enableLights(true)
                lightColor = android.graphics.Color.RED
            }
            // Create (or update) the channel
            notificationManager.createNotificationChannel(channel)
        }

        // Intent to open DetailActivity when notification is tapped
        val contentIntent = Intent().apply {
            if (notificationTargetActivity != null) {
                setClass(this@SoftEtherVpnService, notificationTargetActivity!!)
                // Dynamically fetch TYPE_START and TYPE_FROM_NOTIFY from target activity
                // to support different activities without hardcoding keys here
                try {
                    val startKey = notificationTargetActivity?.getField("TYPE_START")?.get(null)?.toString()
                    val startValue = notificationTargetActivity?.getField("TYPE_FROM_NOTIFY")?.get(null)?.toString()?.toInt()
                    if (startKey != null && startValue != null) {
                        putExtra(startKey, startValue)
                    }
                } catch (e: Exception) {
                    // Silent this exception
                }
            } else {
                setClassName(this@SoftEtherVpnService, "vn.unlimit.vpngate.activities.DetailActivity")
                // Fallback hardcoded for DetailActivity if not set
                putExtra("vn.unlimit.vpngate.TYPE_START", 1001)
            }
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.softether_channel_name_error))
            .setContentText(getString(R.string.softether_notification_disconnected_error))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(Notification.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 250, 250, 250)) // Explicitly set on builder too for pre-O
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)

        notificationManager.notify(NOTIFICATION_CHANNEL_ERROR_ID.hashCode(), builder.build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")

        // Android requires startForeground() within ~5s of startForegroundService().
        // Use the correct text and omit the disconnect action when disconnecting.
        val isDisconnectAction = intent?.action == ACTION_DISCONNECT
        startForeground(
            NOTIFICATION_ID,
            createNotification(
                if (isDisconnectAction) getString(R.string.softether_disconnecting) else getString(R.string.softether_connecting),
                !isDisconnectAction
            )
        )

        when (intent?.action) {
            ACTION_CONNECT -> {
                mIsUserDisconnect = false
                val config = IntentCompat.getParcelableExtra(intent, EXTRA_CONFIG, ConnectionConfig::class.java)

                if (config != null) {
                    startVpn(config)
                } else {
                    Log.e(TAG, "No configuration provided")
                    stopSelf()
                }
            }
            ACTION_DISCONNECT -> {
                mIsUserDisconnect = true
                // Stage 2F-F.28AD-B: honour an identity the caller allocated for this very
                // disconnect, so the completion it is waiting for is the one that arrives.
                // Absent or malformed (non-positive) means the caller has no waiter and the
                // service allocates an id itself, exactly as before.
                val supplied = intent?.getIntExtra(EXTRA_TEARDOWN_ATTEMPT_ID, 0) ?: 0
                stopVpn(suppliedAttemptId = supplied)
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}")
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        
        // Cancel any ongoing connection attempt
        connectionJob?.cancel()
        connectionJob = null
        
        // Clean up resources OFF the main thread: disconnect/destroy make
        // blocking native calls (nativeDisconnect waits on connect_mutex and
        // can block for the whole connect timeout when a connect is in
        // flight — running that on main caused an ANR in softether_disconnect).
        val currentController = controller
        controller = null
        
        // Stage 2F-F.28J: unconditional release. Service destruction outlives every
        // session, so a session-conditional release would be wrong here — there is no
        // session left whose ownership could legitimately survive. Closing is also
        // unconditional, so nothing can remain registered as a live resource.
        tunOwnership.releaseAll()
        
        unregisterNetworkReceiver()
        notifyTrafficListeners(SoftEtherTrafficSnapshot.EMPTY)
        // NonCancellable: serviceScope is cancelled right below, but this
        // cleanup must still run to free the native connection.
        serviceScope.launch(NonCancellable + Dispatchers.IO) {
            try {
                currentController?.destroyResources()
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying controller on destroy", e)
            }
        }
        serviceScope.cancel()
    }

    @Volatile
    private var connectionJob: kotlinx.coroutines.Job? = null

    /**
     * FIX-C: wall-clock ms at which the current attempt began CONNECTING, or 0 when no
     * attempt is connecting.
     *
     * Stamped when the connect coroutine is launched and cleared the moment the attempt
     * stops being a CONNECTING attempt — on a successful connect (`onRunning()`), on
     * [stopVpn], and on the FIX-C abandon path itself. That is what makes the value mean
     * "this attempt has been *connecting* for this long", so a healthy connected session
     * (which keeps `connectionJob` non-null for its whole life) is never mistaken for a
     * wedged one.
     *
     * It is the service-side sibling of the registry's `connectingEnteredAtMs`: the
     * registry bounds the *native* slot, this bounds the *Kotlin* start gate. Both are
     * cleared together when the attempt is abandoned.
     */
    @Volatile
    private var attemptConnectingEnteredAtMs = 0L

    /**
     * FIX-C — Kotlin-side cleanup of a wedged CONNECTING attempt.
     *
     * ## The problem this closes
     *
     * The start gate in [startVpn] refuses with "an initial connection is still in flight"
     * whenever `connectionJob` is non-null (see [SoftEtherSessionPolicy.decideStart], the
     * REFUSE_CONNECTING branch). When an attempt is genuinely wedged inside the native
     * connect — the blocking JNI call never returning — that `connectionJob` stays non-null
     * forever, so the gate refuses EVERY subsequent start. This refusal is INDEPENDENT of
     * the native slot gate: releasing the native slot (the registry's CONNECTING soft-bound,
     * FIX-B) does not clear a Kotlin-side reference, so FIX-B alone would still leave the
     * user locked out. Device evidence (2026-10-13, 14:58:42 / 14:59:05) shows exactly this
     * refusal in practice, before the native registry is ever consulted.
     *
     * ## What it does
     *
     * When the current attempt has been CONNECTING for at least
     * [NativeLifetimeRegistry.NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS] — the SAME
     * constant the registry's CONNECTING soft-bound uses — and a `connectionJob` still
     * exists, it treats the attempt as abandoned and clears the three Kotlin-side resources
     * the gate reads, using the same primitives [stopVpn] uses: invalidate + release
     * `tunOwnership`, cancel and null `connectionJob`, null `controller`.
     *
     * It deliberately does NOT dispatch a native destroy request: the native operation is
     * already wedged, so calling `destroyResources()` would only add a second blocking JNI
     * call. The native side is handled by the registry soft-bound on the `tryAcquire()`
     * that follows the gate.
     *
     * ## Why "on startVpn" and not a timer
     *
     * Symmetric with FIX-B being evaluated on the `tryAcquire()` path: the check runs only
     * when a new attempt actually asks to start, which is the only moment the wedged
     * attempt harms the user. There is no background timer or scheduled task, and this is a
     * no-op for every attempt that is not wedged past the bound — the normal flow is
     * unchanged.
     */
    private fun abandonWedgedConnectingAttemptIfAny() {
        val enteredAt = attemptConnectingEnteredAtMs
        if (enteredAt == 0L) return
        if (connectionJob == null) return
        // Belt-and-braces: never abandon a session that is already RUNNING (connected). The
        // stamp is cleared on onRunning(), so this is redundant for the normal path — but if
        // a connect ever returned without clearing it, abandoning a live, traffic-carrying
        // session (cancel job + drop controller + release TUN, with no native destroy) would
        // be far worse than waiting, so the guard is explicit. It also makes "still
        // CONNECTING" true by construction rather than by inference.
        if (sessionPolicy.isRunning) return
        val heldMs = System.currentTimeMillis() - enteredAt
        if (heldMs < NativeLifetimeRegistry.NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS) return

        Log.w(
            TAG,
            "[SERVICE] Kotlin attempt abandoned after $heldMs ms in CONNECTING; " +
                    "clearing connectionJob/controller/tunOwnership so a new attempt can be admitted.",
        )
        // Same Kotlin-side cleanup primitives stopVpn uses, and nothing else. The session
        // generation is captured once so the invalidate/release pair cannot straddle a
        // session change (both are already no-ops unless they still own that exact session).
        val abandonedSession = sessionPolicy.sessionGeneration
        tunOwnership.invalidate(abandonedSession)
        connectionJob?.cancel()
        connectionJob = null
        controller = null
        tunOwnership.release(abandonedSession)
        attemptConnectingEnteredAtMs = 0L
    }

private fun startVpn(config: ConnectionConfig) {
        // Stage 2F-F.28 Fix A + Stage 2F-F.28C (A-2): the start guard is
        // RESOURCE-AWARE and total.
        //
        // F.28 Fix A removed the stale-state trap: `if (isRunning) return` could
        // refuse every later connect because ConnectionController can emit
        // DISCONNECTED on its own (keepalive/reconnect and destroyResources),
        // which clears only wasConnected and never runs stopVpn().
        //
        // F.28C closed the opposite hole. `isRunning` is only set once connect()
        // RETURNS, so during the whole initial connect window the service looked
        // idle while controller / connectionJob / TUN were live. A second
        // ACTION_CONNECT then replaced those fields and orphaned the in-flight
        // session: its job was never cancelled, its controller never destroyed,
        // and its keepalive / TunTerminal / native handle / established interface
        // stayed alive while all of its callbacks were rejected as obsolete — a
        // live, unowned tunnel.
        //
        // The decision now distinguishes idle / connected / connecting /
        // teardown-in-progress, and is owned by the policy so it is testable.
        // See SoftEtherSessionPolicy.decideStart for the state matrix.
        //
        // FIX-C: clear a Kotlin-side attempt that has been CONNECTING past any healthy
        // window BEFORE the gate reads it. Without this, the gate below refuses every start
        // with "an initial connection is still in flight" for as long as the wedged
        // attempt's connectionJob is non-null — a refusal that is independent of the native
        // slot gate, so releasing the native slot alone (FIX-B) cannot clear it.
        abandonWedgedConnectingAttemptIfAny()

        val sessionResourcesLive =
            controller != null || tunOwnership.isHeld() || connectionJob != null
        val decision = sessionPolicy.decideStart(resourcesLive = sessionResourcesLive)
        when (decision) {
            SoftEtherSessionPolicy.StartDecision.ACCEPT -> Unit
            SoftEtherSessionPolicy.StartDecision.REFUSE_CONNECTED ->
                Log.w(TAG, "Start refused: a connected session already owns the service")
            SoftEtherSessionPolicy.StartDecision.REFUSE_CONNECTING ->
                Log.w(TAG, "Start refused: an initial connection is still in flight")
            SoftEtherSessionPolicy.StartDecision.REFUSE_TEARDOWN ->
                Log.w(TAG, "Start refused: teardown is in progress and still owns resources")
        }
        if (decision != SoftEtherSessionPolicy.StartDecision.ACCEPT) {
            return
        }

        // Stage 2F-F.28W: claim the process-wide native-operation slot.
        //
        // The decision above is still not sufficient, and the gap it leaves is the
        // reason this claim exists. Every resource the gate reads is Kotlin state that
        // teardown clears *synchronously* (stopVpn: connectionJob = null,
        // controller = null, tunOwnership.release) while dispatching the native
        // cleanup *asynchronously* (stopVpn: serviceScope.launch(NonCancellable) {
        // destroyResources() }). So a replacement attempt can pass the gate and reach
        // nativeCreate() while the previous session's `nativeConnectWithHub` is still
        // blocked in JNI — two live `softether_connection_t` structs, which is what
        // F.28U traced.
        //
        // Claiming here makes admission depend on physical native lifetime instead of
        // logical session state, and it is claimed *before* the controller exists so
        // there is no window in which native work is running while the registry
        // believes the process is idle.
        //
        // Placed after the refusal block above so a refused start cannot claim the
        // slot, and before `onSessionStart()` so a slot cannot be claimed by a session
        // that then fails to start.
        val nativeLease = NativeLifetimeRegistry.process.tryAcquire()
        if (nativeLease == null) {
            // Fail closed. A refusal here is not an error: it is the correct answer
            // while a previous attempt's native operation is unresolved. Nothing is
            // queued and nothing is retried — the next start attempt will be judged
            // again against the same registry, and will succeed once the blocking
            // call returns and its controller releases the slot.
            Log.w(
                TAG,
                "Start refused: native slot still held by an unresolved attempt " +
                        "(${NativeLifetimeRegistry.process.describe()})",
            )
            // Stage 2F-F.28AA: make the refusal observable.
            //
            // F.28Z: this was a silent `return`, so `AndroidConnectionAdapter.connect()`
            // looked like it had dispatched a connection that simply never came up, and
            // Auto Mode burned the full per-attempt timeout, declared the *server*
            // unreachable and rotated — potentially through the entire server list, ending
            // in ERROR_NO_SERVER — while the only thing wrong was our own previous native
            // operation still finishing.
            //
            // A refusal is not a server failure and must never be reported as one. Scoped
            // to the session being started, so a refusal for an obsolete session cannot
            // disturb a newer attempt that is already under way.
            notifyNativeBusy(sessionPolicy.sessionGeneration, NativeLifetimeRegistry.process.describe())
            return
        }

if (sessionPolicy.isRunning) {
            Log.w(TAG, "Clearing stale running flag: no controller, TUN or job is live")
        }

        // Stage 2F-F.28 Fix A: a NEW connection session must not inherit the
        // previous session's teardown latch.
        //
        // Before this reset, `isStopping` was written exactly once (in
        // stopVpn) and never cleared, so the first Auto Connect attempt that
        // failed permanently poisoned the service: handleConnectionState()
        // discarded every later state, including CONNECTED, so
        // TunnelStateWatcher never fired and Auto Connect reported
        // "Protocol failed" while a real, traffic-carrying VPN existed.
        //
        // Reset only the per-session lifecycle state here; see the policy class
        // for why this must happen at the start of every session.
        //
        // Stage 2F-F.28A-REVIEW: the returned generation is this session's
        // identity. It is captured by the onStateChange closure below so a
        // callback from an obsolete controller can be recognised and dropped
        // instead of being published into the session that replaced it.
        val session = sessionPolicy.onSessionStart()
        // Stage 2F-F.28J: this session now owns TUN establishment.
        //
        // Claiming here — before the connect coroutine is launched — is what closes
        // the remaining window in the start gate. The gate read the descriptor a few
        // lines earlier on this same thread, but an obsolete installer publishes on a
        // background thread, so a descriptor can appear in between. Because both this
        // claim and that publication are serialised by the coordinator, the inherited
        // descriptor is either discarded here or completed after this point, at which
        // time the installer no longer owns establishment and is refused.
        tunOwnership.claim(session)
        pendingStateRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingStateRunnable = null
        lastStateUpdateTime = 0

        Log.d(TAG, "Starting VPN with config: ${config.serverHost}:${config.serverPort}")
        lastTrafficSnapshot = SoftEtherTrafficSnapshot.EMPTY
        notifyTrafficListeners(SoftEtherTrafficSnapshot.EMPTY)

        // Store session name for use in notifications
        currentSessionName = config.sessionName

        // FIX-C: this attempt begins CONNECTING now. The stamp is cleared on a successful
        // connect (onRunning, below) or on any teardown, so it only ever describes an
        // attempt that is still trying to connect — never a live, connected session.
        attemptConnectingEnteredAtMs = System.currentTimeMillis()
        connectionJob = serviceScope.launch {
            // Stage 2F-F.28W: ownership of the native slot is handed to the
            // controller at construction. Until that succeeds this coroutine still
            // owns it, and it must release it itself — otherwise a session that
            // fails during controller construction would claim the process-wide slot
            // forever and every later connection would be refused.
            //
            // Deliberately NOT a `finally` block: on the success path connect()
            // returns while the connection is live and the controller still owns the
            // slot, so an unconditional release there would admit a replacement
            // attempt while a live native handle exists.
            var controllerCreated = false
            val releaseLeaseIfUnclaimed = {
                if (!controllerCreated && NativeLifetimeRegistry.process.confirmTeardown(nativeLease)) {
                    Log.w(
                        TAG,
                        "Released native slot for session $session: no controller was created, " +
                                "so no native work could be outstanding",
                    )
                }
                Unit
            }
            try {
                // Initialize connection controller
                controller = ConnectionController(
                    service = this@SoftEtherVpnService,
                    config = config,
                    // Stage 2F-F.28W: hand over the claim reserved above. The
                    // controller holds it for its whole life — including across
                    // attemptReconnect() replacing its handle — and releases it only
                    // from destroyResources(), after its last native call returned.
                    nativeLease = nativeLease,
                    // Stage 2F-F.28J: hand the controller the identity it must present
                    // at TUN installation. It keeps this value for its whole life, so a
                    // controller that outlives its session stays identifiable as
                    // obsolete instead of reading the current generation at install
                    // time and appearing current.
                    sessionId = session,
                    onStateChange = { state ->
                        // Stage 2F-F.28A-REVIEW: reject callbacks from an obsolete
                        // session. The closure captures the generation of the
                        // session that created this controller, so a controller
                        // that outlives its session — destroyResources() assigns
                        // DISCONNECTED from a deferred coroutine, and a native
                        // connect flow can still assign CONNECTED after
                        // cancellation — can no longer publish into the session
                        // that replaced it. This is a session-identity check, not
                        // a teardown flag: the current session's CONNECTED is
                        // always delivered, so F.27 RC-1 stays fixed.
                        if (!sessionPolicy.isCurrentSession(session)) {
                            Log.w(TAG, "Dropping $state from obsolete session $session (current ${sessionPolicy.sessionGeneration})")
                            return@ConnectionController
                        }
                        handleConnectionState(state, config.serverHost)
                    },
                    onError = { error ->
                        // Stage 2F-F.28C (A-1): session-scope the error callback
                        // exactly like onStateChange above.
                        //
                        // onError is the MORE destructive of the two: it calls
                        // stopVpn(disconnectByError = true), which broadcasts
                        // ERROR, cancels the current connectionJob, snapshots and
                        // nulls the current controller, closes the current
                        // vpnInterface and can stopSelf(). Before this guard, a
                        // late error from an obsolete session — reachable from
                        // ConnectionController.attemptReconnect(), which runs on
                        // the controller's own scope where isCancelled is
                        // deliberately reset — tore down the live session that had
                        // replaced it.
                        if (!sessionPolicy.shouldTearDownOnError(session)) {
                            Log.w(TAG, "Ignoring error from obsolete session $session (current ${sessionPolicy.sessionGeneration}): $error")
                            return@ConnectionController
                        }
                        Log.e(TAG, "VPN Error: $error")
                        // updateNotification(getString(R.string.softether_disconnected_by_error))
                        mIsUserDisconnect = false
                        stopVpn(disconnectByError = true)
                    },
                    onTrafficUpdate = { snapshot ->
                        // Stage 2F-F.28C audit: deliberately NOT session-scoped.
                        // handleTrafficUpdate only accumulates persisted counters,
                        // notifies traffic listeners and rewrites notification
                        // text; it cannot cancel a job, destroy a controller or
                        // touch session lifecycle state, so widening it here would
                        // be scope creep for a cosmetic-only misattribution.
                        // Recorded as a lower-severity residual risk instead.
                        handleTrafficUpdate(snapshot)
                    }
                )
                controllerCreated = true

                // Establish connection (fires onStateChange -> STATE_CONNECTED with IP after DHCP)
                controller?.connect()
                // Stage 2F-F.28E: `isRunning` is current-session state, so only the
                // session that owns it may set it.
                //
                // This is reachable for an obsolete session: connect() swallows a
                // CancellationException in its own `catch (e: Exception)` and
                // returns normally when the controller's `isCancelled` is already
                // set (ConnectionController's "cancelled, not retrying" path).
                // Cancellation is cooperative, so this non-suspending line then
                // executes for a session that a later startVpn has replaced,
                // marking the new session running before it has connected.
                if (sessionPolicy.isCurrentSession(session)) {
                    sessionPolicy.onRunning()
                    // FIX-C: this attempt is no longer CONNECTING — it is connected. Clear
                    // the CONNECTING stamp so this live session (whose connectionJob stays
                    // non-null for its whole life) can never be mistaken for a wedged
                    // CONNECTING attempt by a later startVpn().
                    attemptConnectingEnteredAtMs = 0L
                    // Stage 2F-F.28N: the success log belongs INSIDE the
                    // current-session branch, and additionally requires that
                    // teardown has not begun.
                    //
                    // Identity alone is NOT sufficient. `isCurrentSession` is a
                    // pure generation comparison and `beginTeardown()` never
                    // advances the generation, so a session that FAILED and was
                    // torn down by onError -> stopVpn() is still the current
                    // session by the time connect() returns normally. F.28M
                    // observed exactly that on a physical device: "Stopping
                    // VPN" / ERROR, then "VPN connection established" with no
                    // obsolete-session warning, because the identity check
                    // still passed.
                    //
                    // `isStopping` is the missing discriminator: it is set by
                    // beginTeardown() and cleared only by onSessionStart(), so
                    // it is false precisely for a session whose result is still
                    // being accepted.
                    //
                    // onRunning() above is deliberately left in place and
                    // unguarded by this condition: changing it would alter
                    // lifecycle state transitions, which is outside F.28N.
                    if (!sessionPolicy.isStopping) {
                        Log.d(TAG, "VPN connection established")
                    }
                } else {
                    Log.w(TAG, "Connect returned for obsolete session $session (current ${sessionPolicy.sessionGeneration}); not marking running")
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                // Connection was canceled (user pressed cancel)
                Log.d(TAG, "Connection cancelled by user")
                mIsUserDisconnect = true
                // Stage 2F-F.28A-REVIEW: only tear down the controller that
                // belongs to THIS session. `controller` is a single shared
                // field, so by the time this cancellation is processed a newer
                // session may already have installed its own controller here;
                // destroying that would kill the replacement session's tunnel.
                // If this session is no longer current, stopVpn() already
                // captured and is destroying this session's controller itself.
                val sessionIsCurrent = sessionPolicy.isCurrentSession(session)
                val owned = if (sessionIsCurrent) controller else null
                if (owned == null) {
                    // Stage 2F-F.28H (F-4): `owned == null` has two distinct causes
                    // and the old single message reported only one of them, which
                    // actively misdirected debugging of this race. Distinguish:
                    //  - obsolete session identity: a newer session owns the
                    //    service and this coroutine's resources were already
                    //    released by the teardown that replaced it; versus
                    //  - current session identity with no controller: stopVpn() or
                    //    onDestroy() already took it, so this is the expected
                    //    post-teardown resumption of a cancelled coroutine.
                    //
                    // The second case is the common one. It is reached whenever a
                    // user disconnect cancels this job: stopVpn() nulls both
                    // connectionJob and controller synchronously on the caller's
                    // thread before this coroutine can observe the cancellation.
                    Log.w(
                        TAG,
                        if (sessionIsCurrent) {
                            "Cancel path skipping destroy: session $session is current but owns no " +
                                    "controller (already released by teardown); " +
                                    "current generation ${sessionPolicy.sessionGeneration}"
                        } else {
                            "Cancel path skipping destroy: session $session is obsolete; " +
                                    "current generation ${sessionPolicy.sessionGeneration}"
                        },
                    )
                }
                // Clean up whatever the connect flow created (TUN interface,
                // terminal, deferred native handle). Idempotent — safe even if
                // the controller already tore itself down.
                try {
                    owned?.destroyResources()
                } catch (ce: Exception) {
                    Log.e(TAG, "Error destroying resources on cancel", ce)
                }
                // Stage 2F-F.28W: `owned` is null when no controller was ever created
                // for this session, which leaves the native slot claimed by this
                // coroutine. Releasing it here is what keeps a session cancelled
                // before controller construction from refusing every future start.
                releaseLeaseIfUnclaimed()
                if (owned != null) {
                    controller = null
                }
                // Stage 2F-F.28E: this terminal broadcast is LIFECYCLE STATE, and
                // lifecycle state belongs to the session that owns it.
                //
                // The two operations above and below already refuse to act for an
                // obsolete session, but the broadcast was unconditional. So an
                // obsolete session could publish STATE_DISCONNECTED into the
                // session that replaced it: GlobalVpnTracker downgrades to
                // DISCONNECTED with no notion of session identity,
                // TunnelStateWatcher raises onTunnelLost, and AutoModeController's
                // connected watcher treats a live tunnel as lost.
                //
                // Cancellation is cooperative, so this branch can run long after
                // the stop that cancelled it — the controller's connect() can be
                // blocked in a native call for the whole connect timeout before
                // the CancellationException surfaces. The obsolete session still
                // performs its own cleanup above; it simply must not speak for
                // the current session.
                if (sessionPolicy.isCurrentSession(session)) {
                    sendConnectionStateBroadcast(STATE_DISCONNECTED)
                } else {
                    Log.w(TAG, "Cancel path: not broadcasting DISCONNECTED for obsolete session $session (current ${sessionPolicy.sessionGeneration})")
                }
                // stopForeground was already done by stopVpn(); make sure the
                // service itself also stops, or the process lingers with the
                // VPN route alive.
                if (sessionPolicy.mayStopServiceFor(session)) {
                    stopSelf()
                }
            } catch (e: Exception) {
                // Stage 2F-F.28H (F-2): session-scope the generic catch.
                //
                // This was the only lifecycle branch in the connect coroutine
                // with no identity check: onStateChange, onError, onRunning and
                // the cancellation path are all guarded, but this branch called
                // stopVpn(disconnectByError = true) unconditionally. stopVpn
                // cancels the *current* connectionJob, snapshots and nulls the
                // *current* controller, closes the *current* vpnInterface,
                // broadcasts ERROR and can stopSelf() — so a failure reported for
                // a session that has already been replaced would tear down the
                // live session that replaced it and announce a false ERROR for
                // it. That is the same defect shape as F.28C (A-1), which was
                // fixed for onError but left open here.
                //
                // Reachability today is narrow rather than proven-absent, and it
                // is worth being precise about why. ConnectionController.connect()
                // wraps performConnect() in its own `catch (e: Exception)` and
                // swallows the failure (`if (isCancelled) return`, otherwise it
                // retries and then reports through onError), so an ordinary
                // connect failure usually completes connect() normally instead of
                // propagating here. The branch is still reached when the service's
                // own onError lambda throws back out of stopVpn() — onError is
                // invoked from inside connect(), so anything stopVpn() throws
                // unwinds into this catch. The guard is the same identity check
                // the sibling callbacks already use, so it costs nothing and
                // closes the last unguarded caller of stopVpn() from a session
                // callback.
                if (!sessionPolicy.shouldTearDownOnError(session)) {
                    Log.w(
                        TAG,
                        "Ignoring failure from obsolete session $session " +
                                "(current generation ${sessionPolicy.sessionGeneration}): ${e.message}",
                        e,
                    )
                } else {
                    Log.e(TAG, "Failed to start VPN", e)
                    // updateNotification("Connection failed: ${e.message}")
                    mIsUserDisconnect = false
                    stopVpn(disconnectByError = true)
                }
                // Stage 2F-F.28W: the counterpart to the release in the cancellation
                // path above, for a failure thrown before the controller existed.
                //
                // It must come AFTER stopVpn() rather than before: stopVpn dispatches
                // destroyResources() onto its own NonCancellable coroutine, so
                // releasing the slot first could admit a replacement attempt while
                // that teardown had not yet returned. With the flag guard this call is
                // a no-op whenever a controller does exist and has taken ownership,
                // so ordering only affects the controller-absent case — but that
                // ordering is the one that matters, and placing it last is what makes
                // it correct.
                releaseLeaseIfUnclaimed()
            }
        }
    }

    /**
     * R1 (v1.0.11 audit §2.5): caller-identity hint for the re-entrant teardown path.
     *
     * Returns the last few `vn.unlimit.*` frames of the current stack, oldest first,
     * joined by " <- ". Reflection, coroutine-machinery, framework and JDK frames are
     * filtered out so the output names app-level callers only. Used solely by the
     * `[TEARDOWN-REENTRY]` diagnostic log; it never influences control flow.
     */
    private fun reentrantTeardownCallers(limit: Int = 3): String {
        val frames = Thread.currentThread().stackTrace
            .asSequence()
            .map { it.className }
            .filter { it.startsWith("vn.unlimit.") }
            .filterNot { it.startsWith("vn.unlimit.softether.SoftEtherVpnService$") }
            .distinct()
            .take(limit)
            .toList()
        return if (frames.isEmpty()) "<none>" else frames.joinToString(" <- ")
    }

    private fun stopVpn(disconnectByError: Boolean = false, suppliedAttemptId: Int = 0) {
        // Stage 2F-F.28A-REVIEW: the session being torn down. Teardown finishes
        // asynchronously, so this identity is what stops a late completion from
        // acting on a session that has since been replaced.
        val tearingDown = sessionPolicy.sessionGeneration

        // Stage 2F-F.28AA: identify *this teardown attempt*. The session generation above
        // cannot do this job, because one session can be stopped several times — the first
        // call performs the teardown, later ones take the re-entrant branch — so two
        // waiters exist inside a single session. Without a per-attempt id a completion
        // from the first attempt could satisfy a waiter armed for the second.
        val teardownAttemptId = if (suppliedAttemptId > 0) {
            Log.d(TAG, "Using caller-supplied teardown attempt id $suppliedAttemptId")
            suppliedAttemptId
        } else {
            nextTeardownAttemptId()
        }

        // Stage 2F-F.28L: linearize installation invalidation FIRST — before
        // beginTeardown(), and before the broadcast, notification and
        // foreground-service work that used to sit between logical invalidation and
        // release().
        //
        // F.28K measured that gap: beginTeardown() ran here, but the coordinator's
        // permission survived through sendConnectionStateBroadcast(),
        // showDisconnectedNotification() (a NotificationManager Binder call) and
        // ServiceCompat.stopForeground(), and was only dropped at release() far below.
        // A reconnect unblocked from native connect or DHCP inside that interval could
        // therefore still reach Builder.establish() after its session had been torn
        // down — transiently resurrecting the system VPN, and transiently making
        // isHeld() true so the start gate could refuse a legitimate new connect.
        //
        // `tearingDown` is the session being torn down, captured before teardown starts.
        // It is never re-read afterwards, so an obsolete caller cannot present itself as
        // current. invalidate() is additionally a no-op unless the coordinator still
        // records that exact session, so a repeated teardown can never revoke a newer
        // session's installation permission.
        //
        // This deliberately does not close anything: the descriptor this session may
        // already own must stay releasable, and close-plus-unregister must remain one
        // coordinated operation. That is release()'s job, below.
        tunOwnership.invalidate(tearingDown)

        // Stage 2F-F.28 Fix F: teardown is idempotent AND resource-aware.
        //
        // The previous guard was `if (isStopping) return`, where `isStopping`
        // was written once and never cleared. Every teardown after the first
        // was therefore a silent no-op, the TUN descriptor was never closed,
        // and an orphan tunnel outlived the app.
        //
        // The decision now belongs to the policy and is driven by whether
        // resources are actually still present, not by a possibly stale latch.
        val teardownComplete =
            controller == null && !tunOwnership.isHeld() && connectionJob == null
        if (!sessionPolicy.beginTeardown(resourcesLive = !teardownComplete)) {
            // R1 (v1.0.11 audit §2.5): the upstream trigger that issues a second,
            // re-entrant teardown for an attempt already in TEARDOWN was UNKNOWN — no
            // instrumentation named the caller, so the ~1 s vs ~1 min window variance
            // in the T5-SLOT-TEARDOWN residual could not be attributed. Logging-only:
            // capture the caller identity plus the resource state at this instant.
            // No control flow changes.
            Log.w(
                TAG,
                "[TEARDOWN-REENTRY] attempt=$teardownAttemptId, " +
                    "state=(isStopping=${sessionPolicy.isStopping}, controller=${controller != null}, " +
                    "tunHeld=${tunOwnership.isHeld()}, job=${connectionJob != null}), " +
                    "thread=${Thread.currentThread().name}, " +
                    "callers=${reentrantTeardownCallers()}",
            )
            Log.d(TAG, "Already torn down, skipping re-entrant call")
            // Still publish the terminal state so a teardown waiter armed by
            // disconnect() always completes instead of hanging on its timeout.
            sendConnectionStateBroadcast(if (disconnectByError) STATE_ERROR else STATE_DISCONNECTED)
            // Stage 2F-F.28AA: publish NOOP_REENTRANT, not a success.
            //
            // F.28Z: this path used to emit the same argument-less completion the real
            // teardown emitted, so a waiter could read "teardown complete" from a call
            // that released nothing and awaited nothing. Worse, it was the path taken by
            // *every* stopVpn after a refused start, because a refused start never reaches
            // `onSessionStart()` and so never clears the policy's stopping flag — which
            // made the fabricated signal fire repeatedly during exactly the window where
            // native ownership was still held.
            //
            // NOOP_REENTRANT still completes the waiter (a waiter must not hang) but
            // asserts nothing about native cleanup, so a consumer can tell it apart from
            // a confirmed teardown.
            notifyTeardownComplete(
                TeardownCompletion.of(
                    attemptId = teardownAttemptId,
                    sessionId = tearingDown,
                    outcome = TeardownCompletion.Outcome.NOOP_REENTRANT,
                ),
            )
            return
        }

        Log.d(TAG, "Stopping VPN")

        // Send disconnect/error broadcast immediately so the UI reacts right away
        sendConnectionStateBroadcast(if (disconnectByError) STATE_ERROR else STATE_DISCONNECTED)
        notifyTrafficListeners(SoftEtherTrafficSnapshot.EMPTY)

        // Cancel the connection coroutine so it won't interfere
        connectionJob?.cancel()
        connectionJob = null
        // FIX-C: this attempt is no longer CONNECTING — clear the stamp alongside the job so
        // a torn-down attempt can never be read as wedged by a later startVpn().
        attemptConnectingEnteredAtMs = 0L

        // Move UI-facing cleanup (notification, foreground) to happen
        // immediately on the main thread so the user sees "disconnected"
        // right away.
        showDisconnectedNotification()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)

        // Snapshot references and clear them immediately so onDestroy()
        // won't try to free the same resources concurrently.
        val currentController = controller
        controller = null

        // Stage 2F-F.28J: release only if THIS session still owns the descriptor.
        //
        // Releasing conditionally is what keeps a teardown belonging to an obsolete
        // session from destroying a newer session's TUN: if ownership has already moved
        // on, this call is a no-op and the newer session keeps its descriptor. The
        // coordinator also clears its ownership record in the same critical section in
        // which it closes the descriptor, so the service can never be left registering a
        // descriptor that has already been closed — which the start gate would
        // otherwise read as a live resource and refuse every later connection.
        tunOwnership.release(tearingDown)

        // Clean up resources on a background thread WITHOUT calling the
        // blocking graceful disconnect (nativeDisconnect waits for server
        // ACK which can hang).  destroyResources() just frees native memory
        // and closes fds — fast and non-blocking.
        serviceScope.launch(NonCancellable) {
            var nativeConfirmed = false
            withContext(Dispatchers.IO) {
                try {
                    // `currentController` is an immutable local captured before this
                    // coroutine was launched, so reading it twice here cannot observe a
                    // newer session's controller.
                    //
                    // Stage 2F-F.28AA: `destroyResources()` now REPORTS what it achieved.
                    // It no longer guarantees the native structure is gone when it returns:
                    // if a connect flow was still executing, it is forbidden from
                    // destroying anything, so the structure survives until that flow
                    // actually leaves native code and completes cleanup itself.
                    val outcome = currentController?.destroyResources()
                    nativeConfirmed =
                        outcome == TeardownCompletion.Outcome.NATIVE_CONFIRMED

                    // Give that flow a bounded moment to finish, so the common "just
                    // over the deadline" case still reports the truth. Bounded, and on
                    // this same background dispatcher, so the main thread is never
                    // blocked — not even for the length of a stuck JNI call beyond this
                    // bound.
                    if (currentController != null && !nativeConfirmed) {
                        nativeConfirmed =
                            currentController.awaitNativeCleanup(NATIVE_CLEANUP_CONFIRM_TIMEOUT_MS)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error destroying controller resources", e)
                }
            }
            // Stage 2F-F.28AA: emit the outcome, not a bare "complete".
            //
            // F.28Z: this fired unconditionally, so `AndroidConnectionAdapter.disconnect()`
            // logged "Teardown confirmed complete" and treated itself as free to start the
            // next server while the previous native connect was still running and the lease
            // was still held. Reporting NATIVE_UNRESOLVED instead is what lets a consumer
            // refuse to start another native operation instead of blaming the server.
            val completionOutcome = if (nativeConfirmed) {
                TeardownCompletion.Outcome.NATIVE_CONFIRMED
            } else {
                TeardownCompletion.Outcome.NATIVE_UNRESOLVED
            }
            notifyTeardownComplete(
                TeardownCompletion.of(
                    attemptId = teardownAttemptId,
                    sessionId = tearingDown,
                    outcome = completionOutcome,
                ),
            )
            // Stage 2F-F.28A-REVIEW: never stop the service on behalf of a
            // session that has already been replaced. onDestroy() destroys
            // whatever `controller` currently holds and closes the current TUN,
            // so an unconditional stopSelf() here would tear down the NEXT
            // session's resources.
            if (sessionPolicy.mayStopServiceFor(tearingDown)) {
                stopSelf()
            } else {
                Log.w(TAG, "Not stopping service: session $tearingDown was replaced by ${sessionPolicy.sessionGeneration}")
            }
        }
    }

    /**
     * Show disconnected notification (dismissable, no action buttons)
     */
    private fun showDisconnectedNotification() {
        // Intent to open DetailActivity when notification is tapped
        val contentIntent = Intent().apply {
            if (notificationTargetActivity != null) {
                setClass(this@SoftEtherVpnService, notificationTargetActivity!!)
                // Dynamically fetch TYPE_START and TYPE_FROM_NOTIFY from target activity
                // Similar to OpenVPNService.getContentIntent()
                try {
                    val startKey = notificationTargetActivity?.getField("TYPE_START")?.get(null)?.toString()
                    val startValue = notificationTargetActivity?.getField("TYPE_FROM_NOTIFY")?.get(null)?.toString()?.toInt()
                    if (startKey != null && startValue != null) {
                        putExtra(startKey, startValue)
                    }
                } catch (e: Exception) {
                    // Silent this exception
                }
            } else {
                setClassName(this@SoftEtherVpnService, "vn.unlimit.vpngate.activities.DetailActivity")
                putExtra("vn.unlimit.vpngate.TYPE_START", 1001)
            }
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.softether_vpn_service))
            .setContentText(getString(R.string.softether_disconnected))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentPendingIntent)
            .setOngoing(false)  // Dismissable
            .setAutoCancel(true)  // Auto-dismiss when tapped
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Notify listeners of state change and update notification.
     * Uses in-process listener pattern (no broadcasts) — works on all Android versions.
     */
    private fun sendConnectionStateBroadcast(state: String, hostname: String = "") {
        val ip = hostname.ifEmpty { currentAssignedIp }
        notifyListeners(state, if (state == STATE_DISCONNECTED || state == STATE_ERROR) "" else ip)
        Log.d(TAG, if (ip.isNotEmpty()) "State changed: $state ip=$ip" else "State changed: $state")
    }

    /**
     * Establish the VPN tunnel interface on behalf of [session].
     *
     * Stage 2F-F.28J. [session] is the identity captured by the caller when its
     * connection session was created — never a generation read here — so a controller
     * that outlived its session cannot present itself as current.
     *
     * The ownership test and [Builder.establish] happen inside one critical section in
     * [TunOwnershipCoordinator], so an obsolete or cancelled session cannot establish a
     * descriptor, replace the live one, or publish a descriptor the service would
     * later hold after the controller closed it.
     *
     * @return the established descriptor, or null when the session no longer owns TUN
     *   establishment or the platform declined to establish one. Nothing is registered
     *   as a live resource in either case.
     */
    fun establishVpnInterface(config: ConnectionConfig, session: Int): ParcelFileDescriptor? {
        return when (val result = tunOwnership.install(session) { buildTun(config) }) {
            is TunInstallResult.Installed -> result.handle
            // An obsolete session never reached Builder.establish(), so there is no
            // descriptor to close and nothing to unregister.
            TunInstallResult.RefusedOwnership -> {
                Log.w(TAG, "TUN establishment refused: session $session no longer owns it (current owner ${tunOwnership.ownerSession()})")
                null
            }
            TunInstallResult.EstablishFailed -> {
                Log.e(TAG, "Failed to establish VPN interface for session $session")
                null
            }
        }
    }

    /**
     * Release the TUN descriptor if [session] still owns it.
     *
     * Stage 2F-F.28J. A session that installed a descriptor and then discovered it was
     * cancelled must release it *through the service*, so the service's ownership record
     * is cleared in the same critical section. Closing the controller's own reference
     * alone would leave the service still registering a closed descriptor.
     *
     * @return true if this call released the descriptor.
     */
    fun releaseVpnInterface(session: Int): Boolean {
        val released = tunOwnership.release(session)
        if (!released) {
            Log.w(TAG, "TUN release skipped: session $session does not own the descriptor (current owner ${tunOwnership.ownerSession()})")
        }
        return released
    }

    /**
     * Build and establish the platform TUN descriptor for [config].
     *
     * Stage 2F-F.28J: extracted so [establishVpnInterface] can hand a single
     * establishment operation to [TunOwnershipCoordinator], which invokes it inside its
     * critical section.
     */
    private fun buildTun(config: ConnectionConfig): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(config.sessionName)
            .setMtu(config.mtu)
            .addAddress(config.localAddress, config.prefixLength)
            .addDnsServer(config.dnsServer)

        // Add secondary DNS if it differs from primary and is valid
        if (config.secondaryDnsServer.isNotEmpty() && config.secondaryDnsServer != config.dnsServer) {
            builder.addDnsServer(config.secondaryDnsServer)
        }

        // Add routes
        config.routes.forEach { route ->
            builder.addRoute(route.address, route.prefixLength)
        }

        // IPv6 tunnel: unique per-install ULA address, full default route, public DNS
        // Multiple clients must not share the same ULA — derive a stable unique one.
        // Never let IPv6 address setup take down the whole tunnel on a bad value.
        val localV6 = try {
            if (config.localAddressV6.isBlank() || config.localAddressV6 == "fd00::2") {
                deriveUniqueLocalAddressV6()
            } else {
                config.localAddressV6
            }
        } catch (e: Exception) {
            Log.w(TAG, "IPv6 ULA derivation failed, skipping IPv6 address", e)
            ""
        }
        if (localV6.isNotEmpty()) {
            try {
                builder.addAddress(localV6, config.prefixLengthV6)
            } catch (e: Exception) {
                Log.w(TAG, "Invalid IPv6 ULA, skipping: $localV6", e)
            }
        }
        if (config.dnsServerV6.isNotEmpty()) {
            builder.addDnsServer(config.dnsServerV6)
        }
        config.routesV6.forEach { route ->
            builder.addRoute(route.address, route.prefixLength)
        }

        // Exclude apps from VPN tunnel (they will use normal network instead)
        config.excludedApps.forEach { packageName ->
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Excluded app not found, skipping: $packageName")
            }
        }

        // Configure as metered/unmetered
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(config.isMetered)
        }

        // Set underlying networks
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            builder.setUnderlyingNetworks(null)
        }

        // Stage 2F-F.28J: the coordinator — not this function — decides whether this
        // session may establish at all, and it holds the lock across this call, so the
        // descriptor is registered only if [session] still owns TUN establishment when
        // establish() returns. The result is returned to the coordinator, which stores
        // it; this method must not publish it anywhere on its own.
        return builder.establish()
    }

    /**
     * Stable unique ULA (fd00::/8) per install so concurrent clients never
     * share an address. Derived from ANDROID_ID when available, else a random
     * value persisted across sessions.
     */
    private fun deriveUniqueLocalAddressV6(): String {
        val androidId = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        } catch (e: Exception) {
            null
        }
        if (!androidId.isNullOrBlank()) {
            val hex = androidId.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }.lowercase()
            if (hex.length >= 4) {
                return buildUla(hex)
            }
        }
        val prefs = getSharedPreferences("softether_vpn", Context.MODE_PRIVATE)
        prefs.getString(KEY_ULA_V6, null)?.let { return it }
        val generated = buildUla(java.util.UUID.randomUUID().toString().replace("-", "").take(16))
        prefs.edit().putString(KEY_ULA_V6, generated).apply()
        return generated
    }

    private fun buildUla(hex: String): String {
        val groups = hex.chunked(4).joinToString(":")
        return "fd00::$groups"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "SoftEther VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "SoftEther VPN connection status"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String, showDisconnectAction: Boolean = true): Notification {
        // Intent to open DetailActivity when notification is tapped
        val contentIntent = Intent().apply {
            if (notificationTargetActivity != null) {
                setClass(this@SoftEtherVpnService, notificationTargetActivity!!)
                // Dynamically fetch TYPE_START and TYPE_FROM_NOTIFY from target activity
                // to support different activities without hardcoding keys here
                // Similar to OpenVPNService.getContentIntent()
                try {
                    val startKey = notificationTargetActivity?.getField("TYPE_START")?.get(null)?.toString()
                    val startValue = notificationTargetActivity?.getField("TYPE_FROM_NOTIFY")?.get(null)?.toString()?.toInt()
                    if (startKey != null && startValue != null) {
                        putExtra(startKey, startValue)
                    }
                } catch (e: Exception) {
                    // Silent this exception
                }
            } else {
                setClassName(this@SoftEtherVpnService, "vn.unlimit.vpngate.activities.DetailActivity")
                // Fallback hardcoded for DetailActivity if not set
                putExtra("vn.unlimit.vpngate.TYPE_START", 1001)
            }
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationTitle = if (!currentSessionName.isNullOrEmpty()) {
            getString(R.string.softether_notification_title, currentSessionName)
        } else {
            getString(R.string.softether_notification_title_notconnect)
        }

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(notificationTitle)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)

        if (showDisconnectAction) {
            val disconnectIntent = Intent(this, SoftEtherVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            val disconnectPendingIntent = PendingIntent.getService(
                this, 1, disconnectIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.softether_disconnect),
                disconnectPendingIntent
            )
        }

        return builder.build()
    }

    private fun updateNotification(content: String, showDisconnectAction: Boolean = true) {
        val notification = createNotification(content, showDisconnectAction)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun handleTrafficUpdate(snapshot: SoftEtherTrafficSnapshot) {
        accumulatePersistedTraffic(snapshot.diffInBytes, snapshot.diffOutBytes)
        notifyTrafficListeners(snapshot)
        if (currentState == STATE_CONNECTED && mDisplaySpeed) {
            updateNotification(formatTrafficSnapshot(snapshot))
        }
    }

    private fun accumulatePersistedTraffic(downloadedDelta: Long, uploadedDelta: Long) {
        if (downloadedDelta <= 0L && uploadedDelta <= 0L) return
        val prefs = getTrafficPrefs()
        prefs.edit()
            .putLong(DOWNLOADED_DATA_KEY, prefs.getLong(DOWNLOADED_DATA_KEY, 0L) + downloadedDelta)
            .putLong(UPLOADED_DATA_KEY, prefs.getLong(UPLOADED_DATA_KEY, 0L) + uploadedDelta)
            .apply()
    }

    private fun getTrafficPrefs(): SharedPreferences {
        return applicationContext.getSharedPreferences(
            applicationContext.packageName + TRAFFIC_PREFS_SUFFIX,
            Context.MODE_PRIVATE
        )
    }

    private fun formatTrafficSnapshot(snapshot: SoftEtherTrafficSnapshot): String {
        return getString(
            R.string.softether_statusline_bytecount,
            humanReadableByteCount(snapshot.inBytesPerSecond(), true),
            humanReadableByteCount(snapshot.inBytes, false),
            humanReadableByteCount(snapshot.outBytesPerSecond(), true),
            humanReadableByteCount(snapshot.outBytes, false)
        )
    }

    private fun humanReadableByteCount(bytes: Long, speed: Boolean): String {
        val value = if (speed) bytes * 8.0 else bytes.toDouble()
        val unit = if (speed) 1000.0 else 1024.0
        val units = if (speed) {
            arrayOf("bit/s", "kbit/s", "Mbit/s", "Gbit/s")
        } else {
            arrayOf("B", "KB", "MB", "GB")
        }
        if (value <= 0.0) {
            return if (speed) "0 bit/s" else "0 B"
        }
        val exp = kotlin.math.min((kotlin.math.ln(value) / kotlin.math.ln(unit)).toInt(), units.lastIndex)
        val scaled = value / Math.pow(unit, exp.toDouble())
        return String.format(java.util.Locale.getDefault(), "%.1f %s", scaled, units[exp])
    }

    private var pendingStateRunnable: Runnable? = null

    private fun handleConnectionState(state: ConnectionState, hostname: String) {
        // Stage 2F-F.28 Fix B: suppression is now state-specific and owned by the
        // policy.
        //
        // The original guard was an unconditional `if (isStopping) return`. Any
        // prior teardown therefore suppressed *every* later state of *every*
        // later session — including CONNECTED. Losing CONNECTED means
        // TunnelStateWatcher never fires, awaitTunnel() times out, and Auto
        // Connect reports "Protocol failed" for a tunnel that is actually up and
        // carrying traffic. That is the chain reproduced on device in Stage
        // 2F-F.26-ALT and diagnosed in Stage 2F-F.27 as RC-1.
        //
        // See SoftEtherSessionPolicy.shouldDeliver for the full rationale.
        if (!sessionPolicy.shouldDeliver(state)) {
            return
        }

        // Cancel any previously pending delayed state update to prevent stale state
        // from overwriting a newer state (e.g., old AUTH firing after CONNECTED)
        pendingStateRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingStateRunnable = null

        // If we are in ERROR state or DISCONNECTED (and not user disconnect),
        // show error notification but ALWAYS broadcast to activity so it doesn't get stuck
        if ((state == ConnectionState.ERROR || state == ConnectionState.DISCONNECTED) && !mIsUserDisconnect) {
            if (mWasConnected) {
                triggerDisconnectNotification()
                sessionPolicy.onConnectionLost()
            }
            // Still broadcast so the activity learns the connection is gone
            val stateValue = when (state) {
                ConnectionState.ERROR -> STATE_ERROR
                else -> STATE_DISCONNECTED
            }
            sendConnectionStateBroadcast(stateValue, "")
            return
        }

        val message = when (state) {
            ConnectionState.CONNECTING -> getString(R.string.softether_connecting)
            ConnectionState.TLS_HANDSHAKE -> getString(R.string.softether_tls_handshake)
            ConnectionState.PROTOCOL_HANDSHAKE -> getString(R.string.softether_protocol_handshake)
            ConnectionState.AUTHENTICATING -> getString(R.string.softether_authenticating)
            ConnectionState.SESSION_SETUP -> getString(R.string.softether_session_setup)
            ConnectionState.CONNECTED -> {
                val displayIp = controller?.assignedLocalIp ?: hostname
                getString(R.string.softether_connected, displayIp)
            }
            ConnectionState.DISCONNECTING -> getString(R.string.softether_disconnecting)
            ConnectionState.DISCONNECTED -> getString(R.string.softether_disconnected)
            ConnectionState.ERROR -> getString(R.string.softether_disconnected_by_error)
        }

        // Don't show the disconnect action button when we're already disconnecting
        val showDisconnectAction = state != ConnectionState.DISCONNECTING

        if (state == ConnectionState.CONNECTED) {
            sessionPolicy.onConnectedDelivered()
            mIsUserDisconnect = false
        }

        // Terminal states always pass through immediately
        val isTerminalState = state == ConnectionState.CONNECTED ||
                state == ConnectionState.DISCONNECTED ||
                state == ConnectionState.ERROR

        // Rate-limit non-terminal states to avoid flooding the UI
        val now = System.currentTimeMillis()
        if (!isTerminalState && now - lastStateUpdateTime < 200) {
            // Cancel any previously queued pending update first
            pendingStateRunnable?.let { mainHandler.removeCallbacks(it) }
            val stateValue = when (state) {
                ConnectionState.CONNECTING -> STATE_CONNECTING
                ConnectionState.TLS_HANDSHAKE -> STATE_TLS_HANDSHAKE
                ConnectionState.PROTOCOL_HANDSHAKE -> STATE_PROTOCOL_HANDSHAKE
                ConnectionState.AUTHENTICATING -> STATE_AUTHENTICATING
                ConnectionState.SESSION_SETUP -> STATE_SESSION_SETUP
                ConnectionState.CONNECTED -> STATE_CONNECTED
                ConnectionState.DISCONNECTING -> STATE_DISCONNECTING
                ConnectionState.DISCONNECTED -> STATE_DISCONNECTED
                ConnectionState.ERROR -> STATE_ERROR
            }
            pendingStateRunnable = Runnable {
                pendingStateRunnable = null
                updateNotification(message, showDisconnectAction)
                sendConnectionStateBroadcast(stateValue, "")
            }
            mainHandler.postDelayed(pendingStateRunnable!!, 200)
            return
        }

        lastStateUpdateTime = now

        // Terminal states clear any pending update
        if (isTerminalState) {
            pendingStateRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingStateRunnable = null
        }

        updateNotification(message, showDisconnectAction)

        val stateValue = when (state) {
            ConnectionState.CONNECTING -> STATE_CONNECTING
            ConnectionState.TLS_HANDSHAKE -> STATE_TLS_HANDSHAKE
            ConnectionState.PROTOCOL_HANDSHAKE -> STATE_PROTOCOL_HANDSHAKE
            ConnectionState.AUTHENTICATING -> STATE_AUTHENTICATING
            ConnectionState.SESSION_SETUP -> STATE_SESSION_SETUP
            ConnectionState.CONNECTED -> STATE_CONNECTED
            ConnectionState.DISCONNECTING -> STATE_DISCONNECTING
            ConnectionState.DISCONNECTED -> STATE_DISCONNECTED
            ConnectionState.ERROR -> STATE_ERROR
        }
        sendConnectionStateBroadcast(
            stateValue,
            if (state == ConnectionState.CONNECTED) (controller?.assignedLocalIp ?: hostname) else ""
        )
    }

    private fun registerNetworkReceiver() {
        val cm = ContextCompat.getSystemService(this, ConnectivityManager::class.java)
            ?: return
        cm.registerDefaultNetworkCallback(networkCallback)
    }

    private fun unregisterNetworkReceiver() {
        val cm = ContextCompat.getSystemService(this, ConnectivityManager::class.java)
            ?: return
        try {
            cm.unregisterNetworkCallback(networkCallback)
        } catch (e: IllegalArgumentException) {
            // Callback was not registered
        }
    }
}

/**
 * R4a (v1.0.11 audit §4.4 / §5.4): process-wide tunnel-health counters.
 *
 * Deliberately placed in the **`SoftEtherClient` module** rather than in
 * `GlobalVpnTracker` (which lives in `app`): the only module dependency
 * direction available is `app → SoftEtherClient`, and the TUN read loop
 * that observes EIO lives in `SoftEtherClient`. `GlobalVpnTracker` cannot
 * be the owner of a counter that a `SoftEtherClient` file must write.
 * `GlobalVpnTracker` delegates to this object and keeps the public API.
 *
 * These counters are the missing input for the already-decided
 * `isTunnelUsable()` contract (`SERVER_SOURCE_ARCHITECTURE.md` §7 Q1,
 * USER-DIRECTED REQUIREMENT). Scope: the counters exist, `noteTunEio` is
 * wired (TunTerminal R5), and `noteRxAdvance` **is** called — R4b added the
 * producer at `ConnectionController.kt:1157`, once per successfully-received
 * batch. `isTunnelUsable()` therefore reflects real traffic, and v1.0.41
 * (§1.6.34) consumes it through
 * `GlobalVpnTracker.isTunnelUsableForRouting()`. The earlier wording ("not
 * called by any data-forwarding path … deferred to R4b") was accurate when
 * written and is corrected here.
 */
object TunnelHealth {
    private val rxCounter = java.util.concurrent.atomic.AtomicLong(0L)
    private val tunEioCounter = java.util.concurrent.atomic.AtomicLong(0L)
    private val lastRxAdvanceAtMs = java.util.concurrent.atomic.AtomicLong(0L)
    private val lastEioAtMs = java.util.concurrent.atomic.AtomicLong(0L)

    /** RX must have advanced within this window for the tunnel to be usable. */
    const val RX_WINDOW_MS = 10_000L

    /** No TUN EIO may have occurred within this window. */
    const val EIO_WINDOW_MS = 30_000L

    /** Process-wide monotonic RX counter. */
    fun rxCounter(): Long = rxCounter.get()

    /** Wall-clock ms of the most recent RX advance; 0 if never observed. */
    fun lastRxAdvanceAtMs(): Long = lastRxAdvanceAtMs.get()

    /** Process-wide monotonic TUN-EIO counter. */
    fun tunEioCount(): Long = tunEioCounter.get()

    /** Wall-clock ms of the most recent TUN EIO; 0 if never observed. */
    fun lastEioAtMs(): Long = lastEioAtMs.get()

    /** Record one RX advance (increments the counter and stamps the clock). */
    fun noteRxAdvance() {
        rxCounter.incrementAndGet()
        lastRxAdvanceAtMs.set(System.currentTimeMillis())
    }

    /** Record one TUN EIO read failure. */
    fun noteTunEio() {
        tunEioCounter.incrementAndGet()
        lastEioAtMs.set(System.currentTimeMillis())
    }

    /** True once an EIO has ever been recorded (diagnostic). */
    fun hasSeenEio(): Boolean = tunEioCounter.get() > 0L

            /** Reset all four fields. Exposed only for the R4a unit tests. */
            fun resetHealthCountersForTest() {
                rxCounter.set(0L)
                tunEioCounter.set(0L)
                lastRxAdvanceAtMs.set(0L)
                lastEioAtMs.set(0L)
            }

            /**
             * The R4a usability decision, as a pure function.
             *
             * `isConnected` and `nowMs` are parameters rather than reads of
             * global state so the §7 Q1 contract is unit-testable in a plain JVM
             * test — `GlobalVpnTracker`'s object initializer needs the Android
             * runtime (`Handler(Looper.getMainLooper())`), so its
             * `isTunnelUsable()` cannot be exercised directly on the JVM.
             *
             * All three conditions of §7 Q1 must hold: (a) connected,
             * (b) an RX advance within [RX_WINDOW_MS] of [nowMs], (c) no TUN EIO
             * within [EIO_WINDOW_MS] of [nowMs]. Missing evidence fails closed.
             */
            fun isUsable(isConnected: Boolean, nowMs: Long): Boolean {
                if (!isConnected) return false
                val lastRx = lastRxAdvanceAtMs.get()
                if (lastRx == 0L || nowMs - lastRx >= RX_WINDOW_MS) return false
                val lastEio = lastEioAtMs.get()
                if (lastEio != 0L && nowMs - lastEio < EIO_WINDOW_MS) return false
                return true
            }

            /** Test-only: force the RX timestamp so the window can be exercised. */
            fun setLastRxAdvanceAtMsForTest(ms: Long) {
                lastRxAdvanceAtMs.set(ms)
            }

            /** Test-only: force the EIO timestamp so the window can be exercised. */
            fun setLastEioAtMsForTest(ms: Long) {
                lastEioAtMs.set(ms)
            }
        }
