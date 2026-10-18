package vn.unlimit.vpngate.automode

import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.preference.PreferenceManager
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.VpnStatus
import kittoku.osc.preference.OscPrefKey
import vn.unlimit.softether.SoftEtherVpnService
import vn.unlimit.softether.TeardownCompletion

/**
 * Bridges the three existing VPN stacks' "tunnel is genuinely up"
 * signals (§14) into a single suspend wait used by Auto Mode:
 * - SoftEther: [SoftEtherVpnService.StateListener] STATE_CONNECTED
 * - OpenVPN:   [VpnStatus.StateListener] LEVEL_CONNECTED
 * - SSTP:      kittoku OSC ROOT_STATE pref flip
 *
 * Register/unregister with [attach]/[detach] from an app-scoped
 * context (not an Activity).
 */
object TunnelStateWatcher : SoftEtherVpnService.StateListener, VpnStatus.StateListener,
    SoftEtherVpnService.TeardownListener, SoftEtherVpnService.NativeBusyListener {

    @Volatile
    private var watching: AutoModeProtocol? = null

    @Volatile
    private var onTunnelUp: ((AutoModeProtocol) -> Unit)? = null

    @Volatile
    var onTunnelLost: (() -> Unit)? = null

    private var prefs: SharedPreferences? = null
    private val sstpListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == OscPrefKey.ROOT_STATE.toString()) {
                val isUp = prefs?.getBoolean(OscPrefKey.ROOT_STATE.toString(), false) == true
                if (watching == AutoModeProtocol.MS_SSTP && isUp) {
                    onTunnelUp?.invoke(AutoModeProtocol.MS_SSTP)
                } else if (!isUp) {
                    onTunnelLost?.invoke()
                }
            }
        }

    fun attach(context: android.content.Context) {
        if (prefs != null) return
        prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        prefs?.registerOnSharedPreferenceChangeListener(sstpListener)
        SoftEtherVpnService.addStateListener(this)
        SoftEtherVpnService.addTeardownListener(this)
        SoftEtherVpnService.addNativeBusyListener(this)
        VpnStatus.addStateListener(this)
    }

    fun detach() {
        prefs?.unregisterOnSharedPreferenceChangeListener(sstpListener)
        prefs = null
        SoftEtherVpnService.removeStateListener(this)
        SoftEtherVpnService.removeNativeBusyListener(this)
        SoftEtherVpnService.removeTeardownListener(this)
        VpnStatus.removeStateListener(this)
        onTunnelLost = null
        // Stage 2F-F.28AA: drop any armed waiter. A detach must not leave a completion
        // able to resume a wait whose caller is gone. The high-water mark is cleared too:
        // it is per-watcher state, and nothing may be compared against a stale baseline.
        armedTeardown = null
        lastTeardownAttemptId = 0
    }

    /** Arm the watcher for one attempt of [protocol]. */
    fun beginAttempt(protocol: AutoModeProtocol, onUp: (AutoModeProtocol) -> Unit) {
        watching = protocol
        onTunnelUp = onUp
        // Stage 2F-F.28 Fix D: replay a state that is ALREADY terminal.
        //
        // Auto Connect now arms this watcher BEFORE issuing the connect
        // request, which closes the window in which a fast CONNECTED could
        // arrive with `watching == null` and be dropped forever. Arming early
        // introduces the mirror-image risk: the tunnel may already have been up
        // before the arm. Replaying the last known state here means neither
        // ordering loses the event.
        //
        // Replay is safe to repeat — every consumer completes a
        // CompletableDeferred, which ignores a second completion.
        if (shouldReplayConnectedOnArm(protocol, currentServiceState())) {
            onUp(protocol)
        }
    }

    /**
     * Stage 2F-F.28: indirection over `SoftEtherVpnService.currentState`.
     *
     * Reading that static forces the service class to initialise, which builds a
     * `Handler(Looper.getMainLooper())` and therefore fails in a plain JVM unit
     * test. The indirection lets tests drive the replay decision without an
     * Android runtime, and defaults to the real static in production.
     */
    @VisibleForTesting
    internal var stateProvider: () -> String? = { SoftEtherVpnService.currentState }

    private fun currentServiceState(): String? =
        try {
            stateProvider()
        } catch (e: Throwable) {
            null
        }

    fun endAttempt() {
        watching = null
        onTunnelUp = null
        onNativeBusy = null
        onAttemptFailed = null
    }

    // ---- Teardown completion (Stage 2F-F.28 Fix G) ----

    /**
     * Stage 2F-F.28AD-B: an armed teardown waiter, tagged with the arm that created it and
     * with the one teardown attempt it is waiting for.
     *
     * F.28Z found two ways a waiter could be satisfied by the wrong event:
     *
     *  1. `armTeardown` stored a bare lambda, so a waiter armed for attempt *B* could be
     *     completed by a signal belonging to attempt *A*.
     *  2. `disarmTeardown()` cleared whatever was stored, so a waiter unwinding from *A*
     *     could disarm the waiter that *B* had already armed.
     *
     * [token] closed both: a disarm only clears the waiter it armed, and each waiter is
     * delivered to at most once.
     *
     * Stage 2F-F.28AD-B closes a third, which [token] cannot reach. F.28AD-A proved that a
     * *newer, unrelated* completion still satisfied an older waiter, because the only test
     * applied was monotonic recency: "newer than the last thing I accepted" says nothing
     * about "belongs to this teardown". A re-entrant `stopVpn` emits a `NOOP_REENTRANT`
     * completion with a higher id while the real teardown is still pending, so it won the
     * recency test, satisfied the waiter, and then made the real completion look stale and
     * get discarded — destroying the only authoritative answer the consumer would ever get.
     *
     * [expectedAttemptId] makes the test exact. Recency is no longer evidence of identity
     * and is not used as such anywhere in delivery.
     */
    private class TeardownWaiter(
        val token: Long,
        val expectedAttemptId: Int,
        val onResult: (TeardownCompletion) -> Unit,
    )

    @Volatile
    private var armedTeardown: TeardownWaiter? = null

    /**
     * Highest teardown attempt id already **accepted into a waiter**.
     *
     * Stage 2F-F.28AD-B: this is now a *secondary* defence, not the identity mechanism. It
     * is advanced only when a completion has already been matched to a waiter exactly and is
     * about to be delivered, so a completion that nobody wanted can no longer move it. Before
     * that change it was advanced by every sequenced completion, including ones with no armed
     * waiter at all, which let an unconsumed teardown (e.g. one triggered by a "next server"
     * skip, which arms nothing) make a later legitimate completion look stale.
     *
     * It is never reset while attached, because a reset would re-admit exactly the stale
     * signals the check exists to reject.
     */
    @Volatile
    private var lastTeardownAttemptId = 0

    private val armSeq = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Stage 2F-F.28AA: rewind [lastTeardownAttemptId] to zero. **Test use only.**
     *
     * The high-water mark is a process-global monotonic sequence, which is exactly what makes
     * a stale completion harmless — and exactly what makes it untestable in a shared JVM. A
     * single Gradle test JVM runs every teardown test against the *same* singleton, so once
     * any test has consumed an attempt id, no later test can ever be accepted again: its
     * first delivery is rejected as stale and it fails while asserting something unrelated.
     * A test cannot pick an id guaranteed to exceed every id the JVM has already seen, and
     * `Int.MAX_VALUE` leaves no headroom to do it with.
     *
     * Production has no reason to call this. [attach] is the real lifecycle, [detach] already
     * rewinds the mark for a genuine teardown of watcher state, and rewinding while a waiter
     * is live would re-admit the stale signals the filter exists to reject — so the guard
     * makes a stray production call a no-op rather than a correctness hole.
     */
    fun rewindTeardownSequenceForTests() {
        if (armedTeardown == null) lastTeardownAttemptId = 0
    }

    /**
     * Arm a teardown waiter for one specific teardown attempt. Any previous waiter is
     * replaced.
     *
     * Stage 2F-F.28AD-B: [expectedAttemptId] must be the identity of the teardown this caller
     * is about to request — obtained from `SoftEtherVpnService.nextTeardownAttemptId()` and
     * handed back to the service through `EXTRA_TEARDOWN_ATTEMPT_ID`. It has to exist before
     * this call, because the service allocates its own id only once the disconnect intent
     * arrives, which is strictly after the waiter would be armed.
     *
     * A non-positive id arms nothing. That is deliberate: `0` is what a foreign stack's
     * terminal report carries, so arming for it would let that report — which asserts nothing
     * about native cleanup — satisfy a native teardown waiter. Arming nothing leaves the
     * caller on its existing bounded wait, which reports "not confirmed" rather than
     * inventing an answer.
     *
     * @return the arm token, which must be passed back to [disarmTeardown] so that this
     *   waiter alone can be disarmed.
     */
    fun armTeardown(expectedAttemptId: Int, onResult: (TeardownCompletion) -> Unit): Long {
        val token = armSeq.incrementAndGet()
        // The refusal is deliberately silent on this path. `android.util.Log` is not mocked
        // in a JVM unit test, so logging here would make the identity contract untestable —
        // the same reason [stateProvider] exists. The refusal is still observable: the caller
        // receives no completion and reports its existing "not confirmed" outcome.
        if (expectedAttemptId <= 0) return token
        armedTeardown = TeardownWaiter(token, expectedAttemptId, onResult)
        return token
    }

    /**
     * Disarm the teardown waiter armed with [token].
     *
     * Token-scoped on purpose: a waiter that is unwinding must not disarm a newer waiter
     * that has since been armed.
     */
    fun disarmTeardown(token: Long) {
        val current = armedTeardown
        if (current != null && current.token == token) {
            armedTeardown = null
        }
    }

    /**
     * Deliver a completion to the armed waiter — but only if it is the completion that waiter
     * asked for.
     *
     * Stage 2F-F.28AD-B. The gate is **exact identity**: `completion.attemptId ==
     * waiter.expectedAttemptId`. It is deliberately not `>=`, not `>`, and not "newer than
     * [lastTeardownAttemptId]" — recency is not ownership, and using it here is what allowed a
     * re-entrant `NOOP_REENTRANT` to end a wait for a teardown that had not finished, and then
     * made the real completion get discarded as stale.
     *
     * Because the comparison is against the **currently armed** waiter, a completion queued
     * before the waiter was replaced cannot satisfy the replacement: the queued completion
     * still carries its own immutable id, which no longer matches.
     *
     * Single-shot, unchanged: the waiter is cleared *before* the callback runs, so a callback
     * that re-arms cannot be satisfied twice by the same signal.
     */
    private fun deliver(result: TeardownCompletion) {
        val waiter = armedTeardown ?: return

        // Identity. Everything else below is a secondary filter; this is the gate.
        if (result.attemptId != waiter.expectedAttemptId) return

        // Secondary stale protection, kept because it remains logically valid: a waiter is
        // only ever armed for an id the service has not emitted yet, so this can only reject
        // a replay of something already delivered. Advancing the mark here — and only here —
        // is what stops an unconsumed completion from poisoning a future waiter.
        if (TeardownCompletion.isSequenced(result)) {
            if (result.attemptId <= lastTeardownAttemptId) return
            lastTeardownAttemptId = result.attemptId
        }

        armedTeardown = null
        waiter.onResult(result)
    }

    /**
     * Stage 2F-F.28A-REVIEW: the SoftEther teardown-completion signal.
     *
     * This is the ONLY thing that satisfies a teardown waiter for the SoftEther
     * stack. `STATE_DISCONNECTED` / `STATE_ERROR` are published the moment
     * teardown *starts*, so completing on them made `disconnect()` return while
     * `destroyResources()` was still running.
     *
     * Stage 2F-F.28AA: the result is forwarded verbatim rather than being flattened into a
     * boolean, because the outcomes are not equivalent — `NATIVE_UNRESOLVED` means a connect
     * flow may still be executing, and a caller that cannot tell the difference will start
     * another native operation against a slot the registry is still holding.
     *
     * Stage 2F-F.28AD-B: this no longer filters on recency and no longer advances
     * [lastTeardownAttemptId]. It only forwards, and [deliver] decides: a completion is
     * accepted only for the waiter that named its attempt, so an unrelated one — newer or
     * older — is rejected and that waiter stays armed for the teardown it is actually
     * waiting on.
     */
    override fun onSoftEtherTeardownComplete(result: TeardownCompletion) {
        deliver(result)
    }

    /**
     * Stage 2F-F.28AA: this application's previous native operation is still unresolved,
     * so a start request was refused.
     *
     * Delivered only while a SoftEther attempt is being watched, because it is only then
     * that a refusal changes what the watcher should do: it means "do not blame this
     * server, the slot is ours" rather than "this server did not come up".
     */
    override fun onSoftEtherNativeBusy(sessionId: Int, owner: String) {
        if (watching != null && isSoftEtherProtocol(watching!!)) {
            onNativeBusy?.invoke()
        }
    }

    /**
     * Stage 2F-F.28AA: callback for a start refused because the native slot is busy.
     * Armed by [beginAttempt] and cleared by [endAttempt].
     */
    @Volatile
    var onNativeBusy: (() -> Unit)? = null

    /**
     * AUTOCONNECT-AUDIT D-4: the armed attempt reached a terminal failure.
     *
     * [onTunnelLost] cannot serve this purpose: it is the *connected* session's signal
     * (it is what makes Auto-Reconnect fire) and it is delivered for every
     * `STATE_DISCONNECTED`/`STATE_ERROR` regardless of whether an attempt is armed. What
     * the attempt needs is narrower — "the thing I am waiting for has already failed" —
     * so it can stop waiting instead of burning the rest of its budget on an operation
     * that is over.
     *
     * Armed by [beginAttempt]'s caller, cleared by [endAttempt], and delivered only while
     * an attempt is being watched, so it can never end a wait that belongs to someone else.
     */
    @Volatile
    var onAttemptFailed: (() -> Unit)? = null

    override fun onSoftEtherStateChanged(state: String, assignedIp: String) {
        if (watching != null && isSoftEtherProtocol(watching!!)) {
            if (state == SoftEtherVpnService.STATE_CONNECTED) {
                onTunnelUp?.invoke(watching!!)
            }
            // AUTOCONNECT-AUDIT D-4: a terminal failure of the attempt being watched.
            // SoftEther publishes STATE_ERROR for a connect flow that gave up, which is a
            // verdict the attempt can act on immediately.
            if (state == SoftEtherVpnService.STATE_ERROR) {
                onAttemptFailed?.invoke()
            }
        }
        if (state == SoftEtherVpnService.STATE_DISCONNECTED || state == SoftEtherVpnService.STATE_ERROR) {
            // Tunnel LOST is still a logical state, so it is reported here. Only
            // teardown COMPLETION moved to onSoftEtherTeardownComplete().
            onTunnelLost?.invoke()
        }
    }

    override fun setConnectedVPN(uuid: String?) {
        // Not used
    }

    override fun updateState(
        state: String,
        logmessage: String,
        localizedResId: Int,
        status: ConnectionStatus,
        intent: android.content.Intent?,
    ) {
        if (watching != null && isOpenVpnProtocol(watching!!)) {
            if (status == ConnectionStatus.LEVEL_CONNECTED) {
                onTunnelUp?.invoke(watching!!)
            }
            // AUTOCONNECT-AUDIT D-4: OpenVPN's own terminal failure for this attempt. A
            // rejected profile or a failed handshake is a verdict, not a slow connect, so
            // the armed wait ends here rather than on its timeout.
            if (status == ConnectionStatus.LEVEL_AUTH_FAILED) {
                onAttemptFailed?.invoke()
            }
        }
        if (status == ConnectionStatus.LEVEL_NOTCONNECTED || status == ConnectionStatus.LEVEL_AUTH_FAILED) {
            onTunnelLost?.invoke()
            // Stage 2F-F.28A-REVIEW: for OpenVPN the terminal state is the only
            // teardown-completion signal the stack offers — it exposes no
            // equivalent of SoftEther's post-destroy callback. The distinction
            // that matters for the F.28 lifecycle (logical state vs resources
            // actually released) is therefore only enforced for SoftEther, and
            // an OpenVPN teardown stays bounded by TEARDOWN_TIMEOUT_MS rather
            // than confirmed.
            //
            // Stage 2F-F.28AA: delivered as FOREIGN_STACK_TERMINAL, which is
            // explicitly outside the SoftEther attempt-id sequence and therefore
            // not subject to its staleness check. It asserts nothing about
            // native cleanup, so it can never be mistaken for a confirmed
            // SoftEther teardown.
            // Stage 2F-F.28AD-B: this deliberately still forwards, so the foreign path's
            // behaviour is unchanged — but it can no longer end a *native* teardown wait.
            // A foreign terminal report carries `attemptId = 0` and therefore cannot match
            // any waiter, because a waiter is only ever armed for a positive id
            // (`armTeardown` refuses anything else). The SoftEether completion that the
            // disconnect request always provokes remains the thing that satisfies a
            // SoftEether teardown waiter, which is the correct pairing.
            deliver(TeardownCompletion.foreignStackTerminal())
        }
    }
}

/** Stage 2F-F.28 Fix D: is [protocol] carried by the SoftEther stack? */
internal fun isSoftEtherProtocol(protocol: AutoModeProtocol): Boolean =
    protocol == AutoModeProtocol.SOFTETHER_TCP || protocol == AutoModeProtocol.SOFTETHER_UDP

/** Stage 2F-F.28 Fix D: is [protocol] carried by the OpenVPN stack? */
internal fun isOpenVpnProtocol(protocol: AutoModeProtocol): Boolean =
    protocol == AutoModeProtocol.OPENVPN_TCP || protocol == AutoModeProtocol.OPENVPN_UDP

/**
 * Stage 2F-F.28 Fix D: should arming for [protocol] immediately replay success?
 *
 * Arming the watcher before issuing the connect request closes the window in
 * which a fast `CONNECTED` arrives with nobody listening. It introduces the
 * mirror-image risk that the tunnel was ALREADY up before the arm, so an
 * already-terminal state must be replayed rather than missed.
 *
 * Pure, so it is unit testable without touching Android framework statics.
 *
 * Scope note: replay is implemented for the SoftEther stack, the subject of
 * this stage. OpenVPN replay is deliberately not added.
 */
internal fun shouldReplayConnectedOnArm(
    protocol: AutoModeProtocol,
    currentState: String?,
): Boolean = isSoftEtherProtocol(protocol) &&
    currentState == SoftEtherVpnService.STATE_CONNECTED
