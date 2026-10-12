package vn.unlimit.softether

import vn.unlimit.softether.model.ConnectionState

/**
 * Stage 2F-F.28 — lifecycle policy for [SoftEtherVpnService], extracted so the
 * decisions can be unit tested without Android framework objects.
 *
 * ## Why this exists
 *
 * Stage 2F-F.26-ALT reproduced a real, traffic-carrying SoftEther VPN existing
 * while the application reported the attempt as failed. The audit in Stage
 * 2F-F.27 traced it to three lifecycle facts living as raw booleans on the
 * service:
 *
 *  1. the teardown latch was written once and never cleared, so one failed
 *     Auto Connect attempt poisoned every later attempt in the same process;
 *  2. state delivery was suppressed unconditionally while that latch was set,
 *     which discarded `CONNECTED` — the only signal that lets
 *     [vn.unlimit.vpngate.automode.TunnelStateWatcher] and Auto Connect observe
 *     reality;
 *  3. teardown short-circuited on the latch rather than on whether resources
 *     were actually gone, so a later legitimate teardown became a no-op and an
 *     orphan TUN outlived the app.
 *
 * This class holds exactly those decisions and nothing else. It performs no I/O
 * and touches no Android API, so the policy is testable on a plain JVM.
 *
 * Not thread-safe by itself; [SoftEtherVpnService] confines it to the main
 * thread plus the connection coroutine, exactly as the booleans were used
 * before extraction.
 */
class SoftEtherSessionPolicy {

    /**
     * True while the *current* session is tearing down.
     *
     * Unlike the pre-F.28 flag this is cleared by [onSessionStart], so it can
     * never be inherited from a previous session.
     */
    var isStopping: Boolean = false
        private set

    /** True once the current session has a live connection and VPN interface. */
    var isRunning: Boolean = false
        private set

    /** True once `CONNECTED` has been delivered for the current session. */
    var wasConnected: Boolean = false
        private set

    /**
     * Monotonic identity of the current session.
     *
     * Stage 2F-F.28A-REVIEW: the pre-F.28A lifecycle had no session identity at
     * all. `handleConnectionState` was reached through a per-session
     * `ConnectionController` callback, but nothing recorded *which* session the
     * callback belonged to, so a controller from session N could publish into
     * session N+1. That is reachable: `ConnectionController.currentState` is a
     * property whose setter invokes `onStateChange` on whatever thread assigns
     * it, and `destroyResources()` assigns `DISCONNECTED` from the deferred
     * teardown coroutine — long after the service dropped its reference.
     *
     * The generation is captured in the callback lambda created in `startVpn`,
     * so rejecting an obsolete callback needs no new plumbing: the closure
     * already exists.
     */
    var sessionGeneration: Int = 0
        private set

    /**
     * A genuinely new connect session begins.
     *
     * This is the fix for the defect chain: a new session must not inherit the
     * previous session's teardown latch, `wasConnected`, or a stale running
     * flag.
     *
     * @return the identity of the new session, to be captured by that session's
     *   callbacks.
     */
    fun onSessionStart(): Int {
        isStopping = false
        isRunning = false
        wasConnected = false
        return ++sessionGeneration
    }

    /**
     * Whether a callback captured by [generation] still belongs to the live
     * session.
     *
     * A callback from an obsolete session is dropped; a callback from the
     * current session passes this identity check, whatever state it carries.
     * This is deliberately *not* a teardown flag: it answers "which session?"
     * only. Whether the state may actually be published is [shouldDeliver]'s
     * decision, and that decision does suppress the current session's
     * `CONNECTED` once teardown has begun.
     */
    fun isCurrentSession(generation: Int): Boolean = generation == sessionGeneration

    /**
     * Whether session [generation]'s deferred teardown may stop the service.
     *
     * `stopVpn` finishes asynchronously. Without this check a slow teardown
     * belonging to session N could call `stopSelf()` after session N+1 had
     * already started, and `onDestroy` would then destroy session N+1's
     * controller and close session N+1's TUN.
     */
    fun mayStopServiceFor(generation: Int): Boolean = generation == sessionGeneration

    /** The connection reached `CONNECTED` and is live. */
    fun onRunning() {
        isRunning = true
    }

    /** `CONNECTED` has been delivered to listeners for this session. */
    fun onConnectedDelivered() {
        wasConnected = true
    }

    /** The session's connection is gone after having been connected. */
    fun onConnectionLost() {
        wasConnected = false
    }

    /**
     * Outcome of a connect request, with the reason it was refused.
     *
     * Stage 2F-F.28C. Kept as a distinct set of outcomes rather than a bare boolean
     * so the service can log *why* a start was refused, and so the start matrix is
     * expressed once, in one testable place.
     */
    enum class StartDecision {
        /** Nothing owns the service: start. */
        ACCEPT,

        /** A live, connected session owns the service: duplicate connect. */
        REFUSE_CONNECTED,

        /**
         * A session's initial connect is still in flight and owns the connection
         * job (and usually the controller), but `isRunning` is not set yet.
         */
        REFUSE_CONNECTING,

    /** Teardown has begun and the previous session still owns resources. */
    REFUSE_TEARDOWN,
    }

    /**
  * Decide whether a connect request may start a new session.
 *
 * Stage 2F-F.28C (defect A-2). `isRunning` alone was not enough, because
 * `onRunning()` only runs after `connect()` returns. For the whole initial
 * connect window the service therefore looked "not running" while
 * `controller`, `connectionJob` and (once DHCP succeeds) the TUN were live, so
 * a second `ACTION_CONNECT` passed the old guard, replaced the `controller` and
 * `connectionJob` fields, and orphaned the in-flight session: its job was never
 * cancelled, its controller never destroyed, and its keepalive / `TunTerminal` /
 * native handle / established interface stayed alive while all of its state
 * callbacks were rejected as obsolete. The result was a live, unowned tunnel —
 * the F.26-ALT symptom.
 *
 * The decision is therefore driven by whether the previous session still owns
 * anything, which is the same resource-aware shape `beginTeardown` already uses:
 *
 * | state                                   | decision           |
 * |-----------------------------------------|--------------------|
 * | idle (nothing live)                     | `ACCEPT`           |
 * | connected (live + running)              | `REFUSE_CONNECTED` |
 * | initial connect in flight (live, !run)  | `REFUSE_CONNECTING`|
 * | teardown begun, resources still live    | `REFUSE_TEARDOWN`  |
 * | teardown released everything            | `ACCEPT`           |
 *
 * The last row is what keeps the F.27 defect fixed. `stopVpn` clears
 * `controller`, `connectionJob` and `vpnInterface` **synchronously**, so a new
 * session is always allowed once teardown has released the service's
 * references; refusing while `isStopping` remained set would reintroduce the
 * permanently latched teardown flag. Callers that must also wait for the
 * previous session's native teardown do so through the teardown-completion
 * signal (`TeardownListener`), not through this decision.
 *
 * @param resourcesLive true while any of the controller, the VPN interface or
 *   the connection job still exists.
 */
fun decideStart(resourcesLive: Boolean): StartDecision = when {
    !resourcesLive -> StartDecision.ACCEPT
    isStopping -> StartDecision.REFUSE_TEARDOWN
    isRunning -> StartDecision.REFUSE_CONNECTED
    else -> StartDecision.REFUSE_CONNECTING
}

/**
 * @return true when a connect request must be refused. See [decideStart] for
 *   the full state matrix.
 */
fun shouldRefuseStart(resourcesLive: Boolean): Boolean =
    decideStart(resourcesLive) != StartDecision.ACCEPT

/**
 * Whether an error reported by [generation]'s controller may tear the session
 * down.
 *
 * Stage 2F-F.28C (defect A-1). `onStateChange` was session-scoped but its
 * sibling `onError` was not, and `onError` is the more destructive of the two:
 * it calls `stopVpn(disconnectByError = true)`, which broadcasts `ERROR`,
 * cancels the **current** `connectionJob`, snapshots and nulls the **current**
 * `controller`, closes the **current** `vpnInterface` and can `stopSelf()`.
 * A late error from an obsolete session — reachable from
 * `ConnectionController.attemptReconnect()`, which runs on the controller's own
 * scope where `isCancelled` is deliberately reset — therefore tore down the
 * live session that had replaced it.
 *
 * Same identity mechanism as [isCurrentSession]: the generation is captured by
 * the callback closure created for that session.
 */
fun shouldTearDownOnError(generation: Int): Boolean = isCurrentSession(generation)

/**
 * Decide whether a teardown request must actually perform teardown.
     *
     * Idempotent *and* resource-aware:
     *  - repeated requests while resources are still live keep running, so a
     *    second legitimate stop can never be swallowed by a stale latch;
     *  - once nothing is left to release, further requests are no-ops.
     *
     * @param resourcesLive true while any of the controller, the connection job
     *   or the VPN interface still exists.
     * @return true when the caller must run teardown.
     */
    fun beginTeardown(resourcesLive: Boolean): Boolean {
        if (isStopping && !resourcesLive) return false
        isStopping = true
        isRunning = false
        return true
    }

    /**
     * Whether a state reported by the current [ConnectionController] must be
     * delivered to listeners.
     *
     * Every state is suppressed while this session is tearing down, `CONNECTED`
     * included.
     *
     * Stage 2F-F.28Q. `CONNECTED` used to be exempted here, because the original
     * unconditional `if (isStopping) return` discarded it and Auto Connect then
     * reported "Protocol failed" for a tunnel that was actually up (F.27 RC-1).
     * That exemption is what allowed the F.28O M4-A4 race: `beginTeardown()` sets
     * `isStopping` but deliberately leaves [sessionGeneration] untouched, so a
     * late `CONNECTED` from the *current* session still passed
     * [isCurrentSession] in the service's `onStateChange` closure and reached this
     * method, where the exemption published it ~12 ms after teardown had already
     * published `DISCONNECTED`.
     *
     * The old comment claimed consumers re-validate ownership before adopting a
     * stale `CONNECTED`. That was not true: `TunnelStateWatcher.onSoftEtherStateChanged`
     * invokes `onTunnelUp` on any `STATE_CONNECTED` without validating session or
     * ownership state, so a stale event was adopted rather than filtered.
     *
     * Dropping the exemption does not resurrect RC-1, because RC-1 was caused by a
     * latch that was written once and never cleared. [onSessionStart] clears
     * `isStopping` before a new session can publish anything, so a legitimate
     * session always observes `isStopping == false` here. `isCurrentSession` cannot
     * substitute for this check: it compares generations and teardown does not
     * advance them.
     */
    fun shouldDeliver(state: ConnectionState): Boolean = !isStopping
}