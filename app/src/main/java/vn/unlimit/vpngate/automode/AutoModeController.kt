package vn.unlimit.vpngate.automode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.unlimit.softether.NativeLifetimeRegistry
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

    /**
     * Protocol priority profile (ordered) — the single source of truth for
     * which protocols Auto Connect tries and in what order.
     *
     * There is no "default protocol" input and no fallback list: a non-empty
     * profile is used verbatim, and an empty one means the user enabled no
     * protocol at all, so the run refuses instead of dialling a protocol the
     * user switched off. The default is therefore empty, not permissive.
     */
    private val protocolPriorityProvider: () -> List<AutoModeProtocol> =
        { emptyList() },
    /** Candidate list source (the in-app collector repository cache). */
    private val serverProvider: suspend () -> List<AutoModeCandidate>,
    /** Called when a tunnel is verified so the caller can persist it (Â§19). */
    private val onSuccess: (AutoModeCandidate, AutoModeProtocol) -> Unit = { _, _ -> },
    /** Per-attempt timeout in ms (Â§11). */
    /**
     * v1.0.42 (§1.6.35 / F1): per-attempt timeout in ms, read through a
     * **provider** instead of being frozen at construction.
     *
     * `AutoModeEngine.ensure()` builds this controller exactly once (the
     * `controller?.let { return it }` singleton), while
     * `AndroidConnectionAdapter` re-reads `SETTING_AUTO_MODE_TIMEOUT_SECONDS`
     * on every call. A frozen field therefore diverged from the adapter the
     * moment the user changed the setting — the run kept logging the old
     * value and only a process restart reconciled them (device-observed:
     * `DEVICE_VERIFY_AUTOCONNECT_BUGS_v1.0.41_REPORT.md` §11). Reading the
     * provider at ATTEMPT time makes a Settings change apply to the next
     * attempt.
     *
     * Default is the documented 12 s, so a caller that does not model the
     * setting keeps the pre-v1.0.42 behaviour exactly.
     */
    private val attemptTimeoutMsProvider: () -> Long = { DEFAULT_ATTEMPT_TIMEOUT_MS },
    /**
     * AUTOCONNECT-AUDIT D-2: how long a NATIVE_BUSY refusal waits for the previous native
     * operation to release the process-wide slot, before the run is declared stuck.
     *
     * Injected so the JVM suite can drive the "genuinely stuck" branch without a
     * real 30 s wait. Production derives it from the per-attempt budget; see
     * [NATIVE_SLOT_AWAIT_MS] for why the factor is five.
     *
     * v1.0.42 (§1.6.35 / B-3): also a **provider**, for the same reason as
     * [attemptTimeoutMsProvider] — both budgets derive from the SAME
     * `SETTING_AUTO_MODE_TIMEOUT_SECONDS` read, so freezing one while reading the
     * other live would have left F1 half-fixed. The production lambda keeps the
     * existing `5 × T` derivation with its v1.0.36 floor and its D-2 cap.
     */
    private val nativeSlotAwaitMsProvider: () -> Long = { NATIVE_SLOT_AWAIT_MS },
    /**
     * Optional pre-filter to eliminate blocked candidates via fast reachability testing.
     *
     * Stage 2F-F.13 (Y4): the argument is a list of protocol/endpoint pairs this
     * controller has already selected, not a server list plus a protocol to
     * interpret. The filter therefore cannot re-decide capability.
     *
     * Stage 2F-F.17 (D1): it returns a [ReachabilityGate] rather than a bare list,
     * so "nothing survived" can be told apart from "everything was definitively
     * unreachable" — the two need opposite handling and a list cannot express it.
     */
    private val reachabilityFilter: (suspend (List<ReachabilityTarget>) -> ReachabilityGate)? = null,
    /**
     * Stage 2F-F.15 (D4 + Y1-b): the measured round-trip time Auto Connect has
     * recorded for one server/protocol endpoint, or null if none.
     *
     * Injected rather than read from `ServerReachabilityTester` directly so this
     * class keeps no dependency on the probing layer — the same separation
     * [reachabilityFilter] already has. The production lambda is bound in
     * `AutoModeEngine` and must read the **AUTO_CONNECT** namespace keyed by IP
     * **and** protocol; an IP-only lookup here would reintroduce exactly the
     * cross-protocol contamination Stage 2F-F.14 removed.
     *
     * The default returns null everywhere: no RTT is better than a fabricated
     * one, and a caller that does not wire a probe simply ranks without the
     * tie-break.
     */
    private val activeRttProvider: (ip: String, protocol: AutoModeProtocol) -> Long? = { _, _ -> null },
    /**
     * VpnM §Phase 1.5 — message shown when the user presses Auto Connect while
     * the server list is empty. Injected so the controller stays free of
     * Android resources and remains JVM-unit-testable.
     */
    private val emptyServerListMessage: () -> String = { "no servers available" },
    /**
     * VpnM Phase 5 — active tunnel liveness signal. The factory builds the
     * probe used while CONNECTED; null disables the watchdog and leaves the
     * previous passive-only behaviour in place.
     *
     * The probe is owned per connection rather than injected as a lambda
     * because it has a lifetime: it must be started when a tunnel is verified
     * and stopped when that tunnel ends, otherwise a stale probe would keep
     * answering for a tunnel that no longer exists. The factory is still
     * injected so tests can supply a scripted probe and the controller keeps
     * no Android dependency.
     */
    private val livenessProbeFactory: ((CoroutineScope) -> TunnelLivenessProbe)? = { TunnelLivenessProbe(it) },
    /**
     * Whether *someone else* already owns a connection attempt.
     *
     * Injected rather than read from [GlobalVpnTracker] directly so this class
     * stays free of Android types and remains JVM-unit-testable: the tracker
     * builds a `Handler(Looper.getMainLooper())` when its object initialiser
     * runs, which has no meaning off-device. Production supplies
     * `{ GlobalVpnTracker.isBusy }`, so the app still has exactly one source of
     * truth for the tunnel state — the controller just does not reach for it
     * itself. The default `false` means "nothing else is connecting".
     */
    private val isTunnelBusy: () -> Boolean = { false },
    /**
     * R8 (§11.13.2, OQ-6): whether the "Auto-Reconnect in Auto Mode" setting is
     * ENABLED.
     *
     * Injected rather than read from [DataUtil] directly for the same reason as
     * [isTunnelBusy]: this class stays free of Android/SharedPreferences types
     * and remains JVM-unit-testable. Production supplies
     * `{ du.getAutoReconnectInAutoMode() }`; the default `true` matches the
     * user-confirmed ENABLED default (§11.13.2), so a caller that does not model
     * the setting gets the documented behaviour rather than a silent DISABLED.
     */
    private val autoReconnectEnabled: () -> Boolean = { true },
    /**
     * v1.0.42 (§1.6.35 / F2): whether ANY network is available at all.
     *
     * The run loop consults this before the server loop and again before every
     * attempt. When it is false the run stops with [ERROR_NO_NETWORK] instead
     * of dialling servers that cannot possibly answer: without the guard, an
     * offline AutoConnect walked the whole list, marked every server
     * `TIMED_OUT`, and handed each one a `WedgeTracker` strike — after two such
     * runs the healthy servers were excluded for the rest of the process.
     *
     * Injected rather than read from `ConnectivityManager` here for the same
     * reason as [isTunnelBusy]: this class stays free of Android types and
     * JVM-testable. Production supplies
     * `{ GlobalVpnTracker.isAnyNetworkAvailable() }`. The default `true`
     * preserves the pre-v1.0.42 behaviour for every caller that does not model
     * connectivity.
     */
    private val isNetworkAvailable: () -> Boolean = { true },
    /**
     * R8 (§11.13.3, OQ-3): delay before the first reconnect attempt, in ms.
     *
     * Injected so the JVM suite drives the reconnect path with a tiny delay
     * instead of the production [RECONNECT_INITIAL_DELAY_MS] 2 s.
     */
    private val reconnectInitialDelayMs: Long = RECONNECT_INITIAL_DELAY_MS,
    /**
     * FIX-3: the per-server wedge ledger Auto Mode consults before dialling.
     *
     * Defaults to the process-wide instance ([WedgeTracker.process]) because
     * "excluded for this session" must survive across runs — a per-run ledger would
     * forget exactly the fact it exists to remember. Injected so a JVM test can supply
     * its own instance and stay isolated from every other test in the suite.
     */
    private val wedgeTracker: WedgeTracker = WedgeTracker.process,
    /**
     * F.29: the persisted per-server history used to break **equal-quality** ties.
     *
     * A **provider**, read once per run, returning one narrow row per server that
     * has history. Two rules follow from that and are enforced by the caller:
     * the ranking never scans the event table, and a failed read is not a
     * failure — an exception here yields an empty map and the run proceeds on
     * the existing quality order (`F.29 §10.4`).
     *
     * Injected rather than reached for directly so this class keeps no Room, no
     * Android and no database dependency, exactly like every other input above.
     * The default returns nothing, which preserves the pre-F.29 ordering for any
     * caller that does not model history.
     */
    private val historyProvider: suspend () -> Map<String, ServerHistoryView> = { emptyMap() },
    /**
     * F.29: where terminal connection outcomes are persisted.
     *
     * `null` disables recording entirely (the default), so every existing test
     * and every caller that does not care keeps its current behaviour. Production
     * supplies [vn.unlimit.vpngate.history.RoomServerHistorySink], which owns the
     * off-main-thread hop and the error swallowing — see [recordHistory].
     */
    private val historySink: ServerHistorySink? = null,
    /**
     * F.29: the **physical** network type at the moment of the call.
     *
     * Injected for the same reason as [isNetworkAvailable]: `ConnectivityManager`
     * has no meaning in a JVM test. The default [NetworkType.UNKNOWN] is the
     * honest answer for a caller that does not model it, and it makes the
     * ordering conservative (tier 0 becomes unreachable) rather than optimistic.
     */
    private val networkTypeProvider: () -> NetworkType = { NetworkType.UNKNOWN },
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

        /**
         * Stage 2F-F.2 (Q-7): whether Auto Connect may drive [protocol]
         * unattended at all.
         *
         * A protocol that cannot be automated is rejected BEFORE any attempt,
         * rather than being entered, logged and then waited out for the full
         * per-attempt timeout on a connection that could never have succeeded.
         *
         * Defaults to `true` so a test double that does not model the policy is
         * not forced to restate it; the real adapter is the only thing that
         * narrows it.
         */
        fun canAutoConnect(protocol: AutoModeProtocol): Boolean = true

        /**
         * Stage 2F-F.28 Fix D: arm the tunnel watcher for [protocol] BEFORE the
         * connect request is issued.
         *
         * The controller calls this, then [connect], then [awaitArmedTunnel], so
         * a CONNECTED can no longer slip through the gap between issuing the
         * connection and starting to listen for it.
         *
         * Default is a no-op so existing test doubles keep compiling; the real
         * adapter overrides it.
         */
        fun armTunnelWatch(protocol: AutoModeProtocol) {}

        /**
         * Stage 2F-F.28 Fix D: wait for the tunnel armed by [armTunnelWatch].
         *
         * Defaults to [awaitTunnel] so an adapter that does not model the split
         * behaves exactly as before.
         */
        suspend fun awaitArmedTunnel(protocol: AutoModeProtocol, timeoutMs: Long): Boolean =
            awaitTunnel(protocol, timeoutMs)

        /**
         * Stage 2F-F.28 Fix C + H: is a VPN **owned by this application**
         * established right now?
         *
         * Used when an attempt's wait has expired: `awaitTunnel` can time out
         * after `Builder.establish()` already succeeded if the CONNECTED
         * notification is late or lost, and classifying that as "Protocol
         * failed" is what left a live tunnel behind while Auto Connect walked on
         * to the next server.
         *
         * The answer must be scoped to this application's own VPN so an
         * unrelated third-party VPN is never adopted as this app's success.
         * Default `false` preserves pre-existing adapter behaviour.
         */
        suspend fun isOwnTunnelEstablished(): Boolean = false

        /**
         * v1.0.32 (AUTOCONNECT-FIX-2): the Global Tunnel State's current status, as a
         * read-only diagnostic string (`DISCONNECTED` / `CONNECTING` / `CONNECTED` /
         * `DISCONNECTING` / `ERROR`).
         *
         * Exists **only** for the slot-non-release instrumentation: when
         * [isOwnTunnelEstablished] disagrees with the tracker, both values have to
         * appear in one log line to place the race. It is never consulted for a
         * decision — ownership deliberately does not come from the tracker, which is
         * itself driven by the same listener chain that can be late
         * (see `AndroidConnectionAdapter.isOwnTunnelEstablished`).
         *
         * Default `"UNKNOWN"` keeps existing test doubles compiling.
         */
        fun tunnelTrackerStatus(): String = "UNKNOWN"

        /**
         * Stage 2F-F.28 Fix E: request teardown *synchronously*.
         *
         * `disconnect()` is a suspending function, so a caller that must not
         * publish a terminal state before teardown has actually been requested
         * cannot guarantee the ordering — the launched coroutine may not run
         * until after the state is already published. This non-suspending
         * request is the primitive: it enqueues the teardown intents before
         * returning, so the caller can then publish safely.
         *
         * [disconnect] remains the awaiting form for callers that want
         * confirmation that teardown completed.
         *
         * Default is a no-op so existing test doubles keep compiling.
         */
        fun requestDisconnect() {}

        /**
         * FIX-2: request a teardown whose ONLY purpose is to release this application's
         * own wedged native operation, so the process-wide TEARDOWN soft-bound
         * (`NativeLifetimeRegistry.NATIVE_SLOT_FORCE_RELEASE_AFTER_MS`) can reclaim the
         * native slot on a later admission attempt.
         *
         * Distinct from [disconnect] and [requestDisconnect] in three ways that matter:
         *
         *  - it is **fire-and-forget** — nothing waits for the completion, because the
         *    whole premise is that the native operation may never complete;
         *  - it is issued only on Auto Mode's **terminal give-up**, after the run has
         *    already decided to stop, so it can never rotate a cursor or adopt a server;
         *  - it must not be silently dropped. The give-up path is the user's last chance
         *    to get unstuck, so an implementation must not gate this behind a
         *    once-per-request idempotency flag that a preceding normal disconnect may
         *    already have consumed.
         *
         * Default is a no-op so existing test doubles keep compiling; the real adapter
         * overrides it.
         */
        fun requestRecoveryTeardown() {}

        /**
         * Stage 2F-F.28AA: [awaitArmedTunnel] plus the reason it ended.
         *
         * Defaults to the boolean projection so an adapter that does not model the native
         * refusal — every existing test double — behaves exactly as before: `true` becomes
         * [AttemptOutcome.TUNNEL_UP] and `false` becomes [AttemptOutcome.TIMED_OUT], which
         * preserves the pre-existing retry policy for genuine failures.
         */
        suspend fun awaitArmedOutcome(
            protocol: AutoModeProtocol,
            timeoutMs: Long,
        ): AttemptOutcome =
            if (awaitArmedTunnel(protocol, timeoutMs)) AttemptOutcome.TUNNEL_UP
            else AttemptOutcome.TIMED_OUT

        /**
         * AUTOCONNECT-AUDIT D-2: wait until the process-wide native slot is free again,
         * or [timeoutMs] expires.
         *
         * A [AttemptOutcome.NATIVE_BUSY] refusal is not a server verdict: it means this
         * application's OWN previous native operation is still running, so the selected
         * server was never contacted. The release of that slot is directly observable, so
         * the controller can wait for the real event instead of paying a fixed number of
         * blind retries and then abandoning the run.
         *
         * `true` means "a new native attempt may now be admitted". `false` means the slot
         * was still held when the budget expired — the only case in which the operation is
         * genuinely stuck and the fail-closed terminal remains correct.
         *
         * Defaults to `true` so a test double that does not model the registry keeps its
         * pre-existing rotation behaviour; the real adapter overrides it with the poll.
         */
        suspend fun awaitNativeSlotRelease(timeoutMs: Long): Boolean = true

        /**
         * AUTOCONNECT-FIX-5 (v1.0.35): did the most recent [disconnect] end in a
         * teardown that a new native attempt may legally follow?
         *
         * This is deliberately narrower than "did the teardown finish". The stack
         * reports four outcomes, and only one of them refuses a later attempt:
         * `NATIVE_CONFIRMED` released the native structures; `NOOP_REENTRANT` published
         * a terminal state for a session that had nothing left to release;
         * `FOREIGN_STACK_TERMINAL` asserts nothing about native cleanup and never
         * refuses. Only `NATIVE_UNRESOLVED` retains ownership and makes the registry
         * refuse every later start.
         *
         * The controller uses this to decide whether a post-slot-release retry can
         * possibly be admitted. Device evidence (DEVICE_VERIFY_AUTOCONNECT_FIX_4_REPORT.md
         * §15) showed 12 such retries in one session: 10 failed instantly with
         * NATIVE_ERROR, 2 with NATIVE_BUSY, and ZERO succeeded — 11 of the 12 were
         * preceded by an unresolved teardown. Retrying into that state only pays the
         * retry's own teardown (~6 s) for a refusal the registry has already decided.
         *
         * Defaults to `true` so a test double that does not model teardown outcomes
         * keeps its pre-existing rotation behaviour exactly; the real adapter overrides
         * it from the outcome it already observes.
         */
        fun lastTeardownWasResolved(): Boolean = true
    }

/**
 * Stage 2F-F.28AA: why an armed attempt ended.
 *
 * F.28Z traced the defect this exists to fix: a start request refused because the
 * process-wide native slot is still owned by an unresolved operation was
 * indistinguishable from a server that never came up. Auto Mode therefore burned
 * the full per-attempt timeout, recorded the *selected server* as unreachable, and
 * rotated — through healthy servers — until it reported `ERROR_NO_SERVER`, when the
 * only thing wrong was this application's own previous native operation.
 *
 * Declared as a nested enum of the controller rather than of [ConnectionAdapter], so it is
 * named `AutoModeController.AttemptOutcome`: the outcome is a property of the *controller's*
 * attempt, not of one adapter method, and `AndroidConnectionAdapter` already has to return it
 * without being nested inside this class.
 */
enum class AttemptOutcome {
    /** A tunnel was verified up. The only outcome that may count as success. */
    TUNNEL_UP,

    /**
     * The start was refused because the native slot is still held. The selected server was
     * not contacted and its health is unknown, so this must never advance a server cursor.
     */
    NATIVE_BUSY,

    /** The wait expired with no tunnel and no refusal. A genuine attempt failure. */
    TIMED_OUT,

    /**
     * AUTOCONNECT-AUDIT D-4: the stack reported a terminal failure for this attempt
     * before the budget expired.
     *
     * The armed wait used to complete only on "tunnel up" or "start refused", so a
     * connect that failed *fast* — a refused TCP connection, a TLS handshake the peer
     * rejected, a server that answers the VPNCONNECT watermark with something that is not
     * a SoftEther Hello — still cost the full per-attempt budget. Auto Mode then paid `T`
     * per wrong server, which is what turned a list of non-SoftEther endpoints into a slow,
     * silent walk.
     *
     * Distinct from [TIMED_OUT] on purpose: this is a verdict the stack delivered, so the
     * attempt is over and the slot is free. It is also the cheap post-watermark failure
     * that AUTOCONNECT-AUDIT D-6 asks to be separated from a genuine attempt.
     */
    NATIVE_ERROR,

    /**
     * v1.0.53 (§1.6.45) TASK B.6: the attempt could not be dialled at all because the
     * data it was selected on does not carry what the protocol needs. Today that is one
     * case: an OpenVPN server whose `.ovpn` profile bytes are absent.
     *
     * ## Why this is its own outcome
     *
     * The adapter used to `return` silently when the profile was missing. The connect
     * call therefore *looked* like it succeeded, `awaitArmedOutcome` waited out the full
     * per-attempt budget, and the attempt was reported as [TIMED_OUT] — which the
     * controller treats as evidence *about the server* (it records a wedge, see
     * AUTOCONNECT-AUDIT D-5). A stripped config is evidence about **our data**, not about
     * the endpoint, so a timeout was the wrong signal in both directions: it cost a full
     * budget and it blamed a server that was never contacted.
     *
     * ## What it must NOT do
     *
     * This is an ordinary attempt failure. It is **not** a cancellation and **not** a
     * terminal state: the run keeps its v1.0.37 no-terminal contract, rotates to the next
     * protocol on the same server, and never records a wedge for it. It also carries no
     * per-attempt timeout semantics of its own — the outcome is known before any wait
     * starts, so the budget is not consumed at all.
     *
     * Since TASK A gates OpenVPN usability on `openVpnConfigAvailable`, a production run
     * should never reach this outcome. It exists as defence in depth: if a future
     * selection path forgets the gate, the failure is immediate and correctly labelled
     * instead of silently expensive.
     */
    MISSING_CONFIG,
}

    companion object {
        /**
         * v1.0.31 (AUTOCONNECT-FIX, D-1): the pure-JVM fallback for
         * [attemptTimeoutMs], aligned with the shipped Settings default —
         * `DataUtil.getAutoModeTimeoutSeconds()` returns **8 s**
         * (`SETTING_AUTO_MODE_TIMEOUT_SECONDS`, clamped to 8..60).
         *
         * v1.0.52 (§1.6.44) TASK B.2: the default moved 12 s -> 8 s, so this
         * constant, the shipped Settings default and the documented default are
         * one number again. It is now also the clamp floor, which means a
         * fresh install gives each protocol attempt exactly 8 s and Auto
         * Connect walks protocols and servers faster.
         *
         * Before v1.0.31 this read `25_000L`, a third figure that agreed with
         * neither the Settings default (12 s) nor the documented default (5 s).
         * Three numbers described one setting, and no test pinned any of them,
         * so the drift was invisible to CI.
         *
         * Production never reaches this value: `AutoModeEngine.ensure()` always
         * injects the user's setting explicitly. It exists for direct
         * construction (tests, library callers).
         */
        const val DEFAULT_ATTEMPT_TIMEOUT_MS = 8_000L

        /**
         * v1.0.32 (AUTOCONNECT-FIX-2, BUG-B): how many `NATIVE_BUSY` refusals on the
         * SAME server, within ONE run, are evidence about that server rather than about
         * our own leftover native operation.
         *
         * Two, matching [WedgeTracker.EXCLUSION_THRESHOLD]'s reasoning: the first
         * refusal is routinely caused by the previous server's still-running native
         * operation, so one strike is not evidence about *this* endpoint. The second
         * refusal on the same server inside the same run cannot be explained that way —
         * the slot was reported released in between — so it is recorded as a wedge.
         *
         * This does NOT change the exclusion threshold; it only makes a refusal
         * countable at all. Before v1.0.32 `NATIVE_BUSY` never reached [recordWedge],
         * so a server that was refused on every protocol could never be excluded and
         * was re-selected indefinitely (DEVICE_VERIFY_AUTOCONNECT_FIX_REPORT.md §7).
         */
        const val NATIVE_BUSY_WEDGE_THRESHOLD = 2

        /**
         * Protocol order used when no provider is supplied at all (library
         * callers / tests that do not model a user profile).
         *
         * This is NOT a runtime fallback: production always injects the user's
         * profile, and an empty *user* profile is a refusal, never this list.
         * It stays for callers that genuinely have no profile to offer.
         */
        val FALLBACK_PRIORITY: List<AutoModeProtocol> = listOf(
            AutoModeProtocol.SOFTETHER_UDP,
            AutoModeProtocol.SOFTETHER_TCP,
            AutoModeProtocol.OPENVPN_UDP,
            AutoModeProtocol.OPENVPN_TCP,
            AutoModeProtocol.MS_SSTP,
            AutoModeProtocol.L2TP_IPSEC,
        )

        const val ERROR_NO_SERVER = "no_compatible_server"
        const val ERROR_VPN_PERMISSION = "vpn_permission_missing"

        /**
         * Stage 2F-F.28AA: Auto Connect stopped because this application's own previous
         * native operation is still unresolved, so no new native attempt could be admitted.
         *
         * Distinct from [ERROR_NO_SERVER] on purpose: no server was found to be
         * unreachable. Reporting that instead is what F.28Z identified as the misleading
         * outcome.
         */
        const val ERROR_NATIVE_UNRESOLVED = "native_operation_unresolved"

        /**
         * R8 (§11.13.3/§11.13.5, OQ-1/OQ-8): Auto Mode stopped reconnecting
         * because its bounded reconnect budget was exhausted.
         *
         * Distinct from [ERROR_NO_SERVER] and [ERROR_NATIVE_UNRESOLVED]: the
         * server list was not proven exhausted, and no native slot was stuck.
         * The run was simply re-fired too many times in too short a window
         * ([RECONNECT_MAX_ATTEMPTS] within [RECONNECT_WINDOW_MS]), which is the
         * only guard preventing an unbounded reconnect loop when a server keeps
         * accepting a connection and then dropping it (§13 failure mode F5).
         */
        const val ERROR_RECONNECT_LIMIT = "ERROR_RECONNECT_LIMIT"

        /**
         * AUTOCONNECT-AUDIT D-2: how long a NATIVE_BUSY refusal waits for the previous
         * native operation to release the process-wide slot before declaring it stuck.
         *
         * The figure is derived from the per-attempt budget rather than guessed: a native
         * connect can still be in flight for several multiples of `conn->timeout_ms`,
         * because every blocking read on that side is bounded by that same figure. A wait
         * shorter than that reports "stuck" on an operation that is still working
         * normally — which is exactly the 2026-10-17 failure, where the run was abandoned
         * 27 ms before the native operation returned on its own.
         *
         * This is a BOUND, not a delay: the wait ends as soon as the slot is released
         * (measured on device: 313 ms after the native exit), so a healthy rotation is not
         * slowed by it. Only an operation that never returns pays the full figure.
         */
        const val NATIVE_SLOT_AWAIT_MS = 30_000L

        /**
         * AUTOCONNECT-AUDIT D-2: ceiling for the derived slot wait, so a very large
         * per-attempt budget cannot make the terminal give-up effectively unreachable.
         */
        const val NATIVE_SLOT_AWAIT_MAX_MS = 60_000L

        /**
         * R8 (§11.13.3, OQ-3): delay before the FIRST reconnect attempt, in
         * milliseconds.
         *
         * A tunnel that just died may still be unwinding at the native layer, so
         * an immediate redial would likely be refused as NATIVE_BUSY and waste a
         * retry cycle. 2 s is the observed typical native teardown time.
         *
         * Injected into the controller so the JVM suite can drive the reconnect
         * path without a real 2 s wait (there is no kotlinx-coroutines-test on
         * the classpath).
         */
        const val RECONNECT_INITIAL_DELAY_MS = 2_000L

        /**
         * R8 (§11.13.3, OQ-1): maximum reconnect attempts within
         * [RECONNECT_WINDOW_MS] before the run stops with
         * [ERROR_RECONNECT_LIMIT].
         *
         * R7 alone has no frequency bound, so a server that accepts and then
         * immediately drops would reconnect forever. This guard is the only
         * thing preventing that unbounded churn.
         */
        const val RECONNECT_MAX_ATTEMPTS = 5

        /**
         * R8 (§11.13.3, OQ-1): rolling window for [RECONNECT_MAX_ATTEMPTS], in
         * milliseconds (10 minutes). Attempts older than this are forgotten, so
         * the bound is on *recent* churn, not lifetime count.
         */
        const val RECONNECT_WINDOW_MS = 10L * 60L * 1000L

        /**
         * The user enabled no protocol in Auto Connect Protocol Priority.
         * Distinct from [ERROR_NO_SERVER]: servers may well be available, there
         * is simply nothing the user allowed us to dial.
         */
        const val ERROR_NO_ENABLED_PROTOCOL = "no_enabled_protocol"

        /**
         * v1.0.42 (§1.6.35 / F2): no network is available at all.
         *
         * A **system-level** terminal, distinct from [ERROR_NO_SERVER]: the
         * servers were never blamed, so no attempt was made and no wedge was
         * recorded. §6.8.4's lead sentence already names "WiFi and data both
         * off" as a stop condition; this is the code path that enforces it.
         *
         * Before v1.0.42 an offline run dialled the whole list, marked every
         * server `TIMED_OUT`, and gave each one a [WedgeTracker] strike — two
         * such runs excluded every healthy server for the rest of the process.
         */
        const val ERROR_NO_NETWORK = "no_network"
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
        // v1.0.54 (§1.6.46) PART 1 / R-1: `skipInFlight` means exactly
        // "a Connected-state skip has been accepted and the machine has not
        // left Connected yet". Publishing any other state is the precise
        // moment that stops being true, so the flag's lifetime is owned here
        // instead of at the call sites.
        //
        // The first implementation cleared it inside the skip continuation,
        // just before launching the replacement run. That left the state
        // reading `Connected` with the flag already `false` for as long as
        // `adapter.disconnect()` and the list resolution took — a second press
        // in that window re-entered the Connected branch, cancelled the
        // continuation and dialled the list again. `duplicateSkipWhileConnected`
        // reproduces it on a real dispatcher and passed only when the timing
        // happened to favour the first press.
        if (state !is AutoModeState.Connected) {
            skipInFlight = false
        }
        _state.value = state
        onStateChange?.invoke(state)
    }

    /** Â§13 single-job guard. */
    var job: Job? = null
        private set

    /**
     * v1.0.52 (§1.6.44) TASK B.3(a): the single-owner transition lock.
     *
     * `start()`, `stop()` and the skip/relaunch path used to be check-then-act:
     * each read `isRunning` (i.e. `job?.isActive`) and then assigned `job`. Two
     * callers arriving together — a double press, a UI press racing an
     * Auto-Reconnect, or a skip racing a press — could both read `false` and
     * both launch a run, which meant two loops dialling the same list and two
     * owners of one tunnel.
     *
     * Every mutation of [job] now happens while holding this monitor, so the
     * read and the write are one atomic transition. It is a plain monitor
     * rather than a `Mutex` on purpose: `start()`/`stop()` are called from the
     * UI thread and must stay non-suspending (the button contract asserts the
     * state immediately after the call returns).
     */
    private val transitionLock = Any()

    val isRunning: Boolean
        get() = job?.isActive == true

    /**
     * v1.0.52: [start]'s body, requiring [transitionLock] to be held.
     *
     * Extracted so the skip path can publish its continuation as the current
     * job in the same critical section — that is what closes the window between
     * `job = null` and the relaunch.
     */
    private fun startLocked() {
        if (isRunning) {
            adapter.log("[AUTO] start ignored: already running")
            return
        }
        job = scope.launch { runAutoMode() }
    }

    /**
     * VpnM Phase 3 "Cursor": 0-based position of the server currently being
     * attempted within the resolved (filtered + sorted) list, plus the size of
     * that list. [totalServerCount] stays 0 until a run resolves a list.
     *
     * Distinct from the per-run attempt counter carried by the Connecting
     * state: that one counts servers actually dialled, this one reports the
     * position in the full ordering, which is what makes "Try next server"
     * from #5 land on #6.
     */
    @Volatile
    var currentServerIndex: Int = -1
        private set

    @Volatile
    var totalServerCount: Int = 0
        private set

    /**
     * v1.0.31 (OP-R6 / D-5), re-based on **identity** in v1.0.42 (§1.6.35 / F6):
     * the server after which the **next** run must start, instead of always
     * restarting at #1.
     *
     * ## What changed, and why this is a decision reversal
     *
     * v1.0.31 stored an integer index (`currentServerIndex + 1`) and accepted,
     * in this very KDoc, that "a re-sorted list can therefore shift the
     * position by one or more places … That is accepted: pinning a server
     * identity instead would silently skip a candidate that has since become
     * better, and the ordering is the contract (§6.6)."
     *
     * v1.0.42 overrides that decision (operator decision, OP-R6). The
     * continuation is now the server's stable identity — `ip|hostname`, the
     * same key [wedgeKey] gives the ledger. The two theories are both
     * defensible; identity wins because the position drift is the more
     * dangerous failure mode (silently continuing from a position that now
     * names a different server), and because the user's mental model is "the
     * next server", not "index N+1".
     *
     * **The trade-off is real and accepted**: a candidate that became *better*
     * in the new sort, and now sorts before the connected server, is skipped
     * by this continuation. It is not skipped for ever — the next fresh press
     * starts from the top.
     *
     * Note on the spec: §11.13.3 says "the **next server in the sorted list**"
     * — it does not require identity. This change is a tightening of that
     * requirement, recorded as an annotation in §11.13.3, not the enforcement
     * of text that was already there.
     *
     * ## Invariant
     *
     * - Set **only** by a button press taken while [AutoModeState.Connected]:
     *   the user pressed "Auto Connect" on a live tunnel, which means "move on
     *   from here", not "start over". The value is the identity of the server
     *   that connected ([connectedCandidate]).
     * - Consumed **exactly once**, at the top of [runAutoMode], and cleared
     *   there. Consuming at read time (rather than at a terminal branch) is
     *   deliberate: an internal restart that the user did not press — the R8
     *   Auto-Reconnect path calls [start] directly — must never inherit a stale
     *   position and silently skip servers.
     * - Cleared on every fresh start (a press from Disconnected/Error).
     * - If the identity is **not found** in the newly resolved list (the server
     *   dropped out of the dataset), the run starts from the top and logs that
     *   fallback — it never restarts silently.
     * - If the identity is the **last** server, the run starts no attempts and
     *   terminates with `ERROR_NO_SERVER`; that is the documented meaning of
     *   pressing the button while connected to the last server.
     */
    @Volatile
    private var resumeFromServerKey: String? = null

    /**
     * User-requested skip of the current server (Try next server button).
     * - While Connecting: aborts the in-flight attempt and moves on.
     * - While Connected (the run is done but the connected watcher is
     *   armed): disconnects the live tunnel and starts a fresh run over
     *   the remaining candidates.
     * Outside a live run it just logs and returns.
     */
    private val skipRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * v1.0.54 (§1.6.46) PART 1 / R-1: the Connected-branch skip is a single
     * atomic transition, and this flag is what makes re-entry visible.
     *
     * ## The defect this closes
     *
     * `skipToNextServer()`'s Connected branch tears the tunnel down and
     * relaunches the run asynchronously, but it deliberately does **not**
     * publish a new state: the state stays [AutoModeState.Connected] until the
     * replacement run reaches its first `Connecting`. Between those two moments
     * a second press of the same button re-enters the same branch, cancels the
     * continuation this branch just published, and launches another one. Two
     * independent runs then walk the same candidate list, and the observable
     * signature is one server dialled several times in one skip burst
     * (`[s2, s1, s1, s1, s1, s1]` in the failing report) plus the two
     * intermittently failing tests.
     *
     * ## The invariant
     *
     * - Set to `true` **inside** [transitionLock], before any asynchronous work
     *   starts, so the window in which the state still reads Connected is
     *   already covered.
     * - A skip arriving while it is `true` is **coalesced** — logged and
     *   dropped. The first skip is already doing exactly what the second one
     *   asks for, so dropping it loses no user intent.
     * - Cleared by [setState] whenever it publishes anything other than
     *   `Connected` — that publication is the exact moment the invariant stops
     *   holding — and explicitly by every path that ends a connection
     *   ([stop], [disconnectNow]).
     * - **Not** cleared when the replacement run starts. v1.0.54 shipped this
     *   KDoc saying the opposite while the code already did the right thing;
     *   clearing it there reopens the window. See [runAutoMode]'s first lines.
     * - Never set by any other path, so a normal run can never be blocked by a
     *   stale value.
     */
    @Volatile
    private var skipInFlight: Boolean = false

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
            // v1.0.42 (§1.6.35 / F6): identity, not IP. The Next-Server
            // continuation uses the `ip|hostname` key, so the skip filter uses
            // the same one — an IP-only filter also drops a *different* server
            // that happens to share an IP (NAT / load balancer). Captured BEFORE
            // `connectedCandidate` is cleared below.
            val connectedState: AutoModeState.Connected? = current
            val currentKey = connectedCandidate?.let { serverKey(it) }
                ?: connectedState?.let { serverKey(it.ip, it.hostname) }
            adapter.log("[AUTO] Connected server skipped by user; trying next")
            synchronized(transitionLock) {
                // v1.0.54 (§1.6.46) PART 1 / R-1: the state is still Connected
                // here, so without this guard a second press would re-enter this
                // branch and cancel the continuation published below. Coalescing
                // is the correct semantic: the first skip already means "move on
                // from this server", and a second press cannot mean anything
                // stronger.
                if (skipInFlight) {
                    adapter.log("[AUTO] Skip already in flight; duplicate request ignored")
                    return
                }
                skipInFlight = true
                connectedWatcher?.cancel()
                connectedWatcher = null
                // R8: disarm BEFORE tearing down — this is a deliberate user move,
                // never an unexpected drop (path F).
                TunnelStateWatcher.onTunnelLost = null
                stopLivenessProbe()
                connectedCandidate = null
                job?.cancel()
                // v1.0.52 (§1.6.44) TASK B.3(a): the continuation is published as
                // the CURRENT job inside this critical section, so `job` is never
                // null while the skip is being prepared. Before this revision the
                // slot sat empty until the coroutine below called start(), which
                // left a window in which a second press could launch its own run —
                // two loops dialling the same list.
                job = scope.launch {
                    adapter.disconnect()
                    val remaining = remainingCandidates.ifEmpty {
                        val all = (overrideServers ?: serverProvider())
                            .withActiveRtt(protocolPriorityProvider())
                            .sortedForAutoConnect()
                        if (currentKey != null) all.filterNot { serverKey(it) == currentKey } else all
                    }
                    if (remaining.isEmpty()) {
                        adapter.log("[AUTO] No more servers to try")
                        // The Error state clears `skipInFlight` in setState.
                        setState(AutoModeState.Error(ERROR_NO_SERVER))
                        synchronized(transitionLock) { job = null }
                    } else {
                        synchronized(transitionLock) {
                            // v1.0.55 (V1054_VERIFY_REPORT G-6): re-check that the
                            // skip is still wanted before publishing the
                            // replacement run.
                            //
                            // `adapter.disconnect()` above is a suspension point,
                            // and `synchronized` is not, so a `stop()` that landed
                            // while the teardown was finishing would have already
                            // run to completion by the time this block is entered —
                            // and this block would then overwrite `job = null`
                            // (set by that stop) with a fresh run, resurrecting
                            // Auto Connect after the user turned it off. The
                            // window is narrow but it is exactly the kind of
                            // window PART 1 exists to close, so it is closed here
                            // too: `stop()` and `disconnectNow()` clear the flag
                            // inside this same monitor, which makes the flag a
                            // reliable "is this skip still live?" answer at this
                            // point.
                            if (!skipInFlight) {
                                adapter.log("[AUTO] Skip superseded by stop; not relaunching")
                                job = null
                            } else {
                                overrideServers = remaining
                                // v1.0.54 (§1.6.46) PART 1 / R-1: `skipInFlight` is
                                // deliberately NOT cleared here. It stays true until
                                // `runAutoMode()` publishes a non-Connected state,
                                // because until then the state still reads Connected
                                // and a press must keep coalescing into this skip.
                                // Clearing it here was the R-1 window itself.
                                // Replace the continuation with the actual run while
                                // still holding the lock: `job` goes from one live job
                                // straight to another, never through null.
                                job = scope.launch { runAutoMode() }
                            }
                        }
                    }
                }
            }
            return
        }

        if (isError) {
            adapter.log("[AUTO] Error state: advancing to next server")
            val remaining = remainingCandidates
            if (remaining.isEmpty()) {
                adapter.log("[AUTO] No remaining servers; restarting from top")
            }
            // v1.0.52 (§1.6.44) TASK B.3(a): override + start are one transition,
            // so a concurrent press cannot consume the override and then let this
            // one start a second run.
            synchronized(transitionLock) {
                overrideServers = if (remaining.isNotEmpty()) remaining else null
                startLocked()
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
     *
     * VpnM Phase 5. The pre-existing checks are passive: the stack's state
     * listener and GlobalVpnTracker both only report a loss when something
     * volunteers one. A tunnel that dies quietly never volunteers, so the UI
     * stays green. The active half is a real round trip through the tunnel
     * (see [TunnelLivenessProbe]): the watchdog fires when no such round trip
     * has completed for a while, which is the case the passive checks miss.
     */
    private fun startConnectedWatcher(protocol: AutoModeProtocol) {
        connectedWatcher?.cancel()
        // A new tunnel invalidates the previous probe entirely.
        stopLivenessProbe()
        TunnelStateWatcher.onTunnelLost = {
            if (_state.value is AutoModeState.Connected) {
                // A-4 (path A): an OS-level revocation is an event, so this is
                // the fastest detector — but it is the only one that must not
                // assume it runs inside the watcher coroutine.
                reportArmedTunnelLoss(
                    "[AUTO] Tunnel disconnected externally / revoked by system",
                    connectedCandidate,
                )
            }
        }
        val probe = livenessProbeFactory?.let { factory ->
            factory(scope).also { it.start(protocol) }
        }
        livenessProbe = probe
        val watchdog = if (probe != null) TunnelLivenessWatchdog() else null
        // The tunnel was just verified, so proof is fresh by definition.
        watchdog?.onProvenAlive(System.currentTimeMillis())
        connectedWatcher = scope.launch {
            while (currentCoroutineContext().isActive) {
                kotlinx.coroutines.delay(1000)
                if (_state.value !is AutoModeState.Connected) break
                if (!GlobalVpnTracker.isConnected) {
                    // A-4 (path B): passive 1 s poll. Deliberately still a poll
                    // — it is the only detector that works when nothing else
                    // volunteers a loss.
                    reportArmedTunnelLoss(
                        "[AUTO] GlobalVpnTracker confirmed disconnected",
                        connectedCandidate,
                    )
                    break
                }
                // Active liveness: only consulted after the passive checks pass,
                // so a genuine OS-level loss is still reported first.
                val wd = watchdog
                val activeProbe = livenessProbe
                if (wd != null && activeProbe != null) {
                    val now = System.currentTimeMillis()
                    if (activeProbe.consumeProof()) {
                        wd.onProvenAlive(now)
                    } else if (wd.onSample(now)) {
                        reportArmedTunnelLoss(
                            "[AUTO] No proof of life for " +
                                "${TunnelLivenessWatchdog.DEFAULT_SILENCE_TIMEOUT_MS}ms; " +
                                "tunnel considered lost",
                            connectedCandidate,
                        )
                        break
                    }
                }
            }
        }
    }

    /**
     * A-4 (§3.4): the one place an armed-tunnel loss becomes an
     * Auto-Reconnect.
     *
     * Before A-4 each of the three armed paths in [startConnectedWatcher]
     * repeated the same five disarm lines inline — and the copies had already
     * drifted: path A nulled `TunnelStateWatcher.onTunnelLost` *before*
     * `stopLivenessProbe()`, path C after it, and paths B/C called
     * [onUnexpectedDisconnect] directly from the watcher coroutine while path A
     * wrapped it in a fresh `scope.launch`. Three copies of a teardown sequence
     * is how a half-disarmed watcher (probe still running, callback still
     * armed) gets shipped.
     *
     * This funnel fixes the order once: cancel the poll, drop the reference,
     * stop the probe, disarm the OS callback, then hand off. The
     * *detection* latency is intentionally NOT unified — path A is an event
     * (immediate), path B a 1 s passive poll, path C the watchdog's silence
     * timeout. Each is the cheapest correct detector for its own failure mode;
     * flattening them (polling at event speed) would delete the passive net
     * that catches a quiet death. What is unified is everything after
     * detection, which is where the replay risk lived.
     *
     * Safe to call from any thread: the OS callback (path A) arrives on a
     * ConnectivityManager thread, so the follow-up is dispatched through
     * [scope] rather than assumed to already be in a coroutine.
     */
    private fun reportArmedTunnelLoss(reason: String, dropped: AutoModeCandidate?) {
        adapter.log(reason)
        connectedWatcher?.cancel()
        connectedWatcher = null
        stopLivenessProbe()
        TunnelStateWatcher.onTunnelLost = null
        scope.launch { onUnexpectedDisconnect(dropped) }
    }

    /**
     * R8 (§11.13.3, D1): the single funnel for an **unexpected** disconnect of a
     * tunnel Auto Mode itself established. Called only from the armed paths in
     * [startConnectedWatcher] (via [reportArmedTunnelLoss]) — never from
     * [stop], [disconnectNow] or [skipToNextServer], which disarm the watcher
     * before tearing down precisely so their deliberate teardown is not
     * mistaken for a loss.
     *
     * Steps, in order:
     *  1. OQ-7 manual precedence — if someone else is connecting, stand down;
     *  2. OQ-6 setting — if DISABLED, stay disconnected;
     *  3. OQ-1 loop guard — bound recent reconnects;
     *  4. publish [AutoModeState.Disconnected] once the follow-up decision is
     *     known, so a consumer that observes Disconnected is guaranteed to be
     *     observing a settled decision rather than the 2 s window before one;
     *  5. OQ-3 settle delay, then seed [pendingReconnectServer] and re-run.
     *
     * @param droppedServer the candidate Auto Mode was connected to (may be null
     *   if the success record was somehow unavailable — then the reconnect simply
     *   runs over [remainingCandidates]).
     */
    private suspend fun onUnexpectedDisconnect(droppedServer: AutoModeCandidate?) {
        // OQ-7: a manual connect that is still coming up owns the tunnel. Do not
        // race it with a reconnect.
        if (isTunnelBusy()) {
            adapter.log("[AUTO] Manual connect in progress; skipping Auto-Reconnect")
            // A-5 (§3.5): Disconnected is published on the way OUT of the
            // decision, never ahead of it — so the OQ-7/OQ-6/OQ-1 follow-up is
            // already visible to any consumer that sees this state.
            setState(AutoModeState.Disconnected)
            return
        }

        // OQ-6 (§11.13.4): the setting governs behaviour AFTER a drop.
        if (!autoReconnectEnabled()) {
            adapter.log("[AUTO] Auto-Reconnect is disabled; staying disconnected")
            setState(AutoModeState.Disconnected)
            return
        }

        // OQ-1: rolling-window loop guard.
        val now = System.currentTimeMillis()
        val attempts: Int
        synchronized(reconnectLock) {
            reconnectTimestamps.removeAll { it < now - RECONNECT_WINDOW_MS }
            if (reconnectTimestamps.size >= RECONNECT_MAX_ATTEMPTS) {
                attempts = -1
            } else {
                reconnectTimestamps.add(now)
                attempts = reconnectTimestamps.size
            }
        }
        if (attempts < 0) {
            adapter.log(
                "[AUTO] Auto-Reconnect limit reached " +
                    "($RECONNECT_MAX_ATTEMPTS in ${RECONNECT_WINDOW_MS / 60_000} min); stopping",
            )
            setState(AutoModeState.Error(ERROR_RECONNECT_LIMIT))
            return
        }

        // A-5 (§3.5): the decision is now final — reconnect. Publishing
        // Disconnected here rather than before the OQ-7/OQ-6/OQ-1 checks is the
        // whole fix: it is still published exactly once per drop (including on
        // the stand-down paths above), but it no longer leads the follow-up by
        // up to [reconnectInitialDelayMs] + log traffic, during which the UI
        // could show "disconnected" next to a log that still says a reconnect
        // is being considered.
        setState(AutoModeState.Disconnected)

        adapter.log(
            "[AUTO] Unexpected disconnect: ${droppedServer?.hostname ?: droppedServer?.ip ?: "unknown"}; " +
                "reconnecting (attempt $attempts/$RECONNECT_MAX_ATTEMPTS)",
        )

        // OQ-3: let the native slot unwind before redialling. Cancellable, so a
        // user stop during the wait ends the coroutine cleanly.
        kotlinx.coroutines.delay(reconnectInitialDelayMs)

        // Someone may have started connecting / stopping during the delay.
        if (isTunnelBusy()) {
            adapter.log("[AUTO] Tunnel owned elsewhere after delay; aborting Auto-Reconnect")
            return
        }

        // OQ-7 second chance: if a fresh run is somehow already active, do not
        // stack a second one — start() would refuse it anyway, but logging the
        // reason is clearer than a silent no-op.
        if (isRunning) {
            adapter.log("[AUTO] A run is already active; not stacking a reconnect")
            return
        }

        // Seed the reconnect: the dropped server first (§11.13.3 step 1), then
        // the un-attempted tail of the original sorted list, exactly as the
        // "Try next server on a Connected server" path does.
        pendingReconnectServer = droppedServer
        val remaining = remainingCandidates
        overrideServers = when {
            droppedServer == null && remaining.isEmpty() -> null
            droppedServer == null -> remaining
            remaining.isEmpty() -> listOf(droppedServer)
            else -> listOf(droppedServer) + remaining.filterNot {
                it.ip == droppedServer.ip && it.hostname == droppedServer.hostname
            }
        }
        start()
    }

    /**
     * R8 — test seam only.
     *
     * The production triggers for an unexpected disconnect are the three armed
     * paths in [startConnectedWatcher], each routed through
     * [reportArmedTunnelLoss]. Two of them (the 1 s `GlobalVpnTracker` poll and
     * the liveness watchdog) and all of the disarm machinery reference
     * `TunnelStateWatcher` / `GlobalVpnTracker` — Kotlin `object`s whose
     * initialisers build an Android `Handler(Looper.getMainLooper())`, so they
     * cannot be class-loaded in a plain JVM unit test. That is the same
     * constraint that made [TunnelStateWatcher.stateProvider] injectable.
     *
     * This method therefore invokes the SAME decision the armed paths invoke
     * ([onUnexpectedDisconnect]) with the same argument they pass
     * ([connectedCandidate]), and dispatches it the same way
     * ([reportArmedTunnelLoss] does), so a JVM test can assert R8's decision
     * without an Android runtime. It changes no production behaviour: nothing
     * in main source calls it.
     */
    @androidx.annotation.VisibleForTesting
    internal fun onUnexpectedDisconnectForTests() {
        val dropped = connectedCandidate
        scope.launch { onUnexpectedDisconnect(dropped) }
    }

    /** When set, the next runAutoMode uses this candidate list instead of the provider. */
    @Volatile
    private var overrideServers: List<AutoModeCandidate>? = null

    /**
     * R8 (§11.13.3, OQ-1): timestamps (ms) of recent reconnect launches, used to
     * enforce [RECONNECT_MAX_ATTEMPTS] within [RECONNECT_WINDOW_MS].
     *
     * Bounded by construction: timestamps older than the window are dropped on
     * every read, and at most [RECONNECT_MAX_ATTEMPTS] survive, so this list can
     * never grow past the cap. Cleared by [resetReconnectBudget] (a user-initiated
     * start), never by a reconnect's own internal [start] — otherwise the guard
     * could never accumulate.
     *
     * Guarded by [reconnectLock]; the helper runs on the controller's
     * single-threaded scope today, but the guard is explicit so a future caller
     * cannot corrupt the window.
     */
    private val reconnectTimestamps = mutableListOf<Long>()
    private val reconnectLock = Any()

    /**
     * R8 (§11.13.3): the server Auto Mode was connected to when the unexpected
     * disconnect happened.
     *
     * Seeded as the FIRST candidate of the reconnect run, so the flow matches
     * §11.13.3 step 1 ("first to the same server"), then the rest of the sorted
     * list follows. Set by [onUnexpectedDisconnect] and consumed (and nulled) at
     * the top of [runAutoMode].
     */
    @Volatile
    private var pendingReconnectServer: AutoModeCandidate? = null

    /**
     * R8 (§11.13.3): the candidate the CURRENT connected tunnel was established
     * with, captured at the success transition.
     *
     * Held so an unexpected disconnect can name the exact server (its full
     * capability record, not just the hostname/ip the `Connected` state carries)
     * and re-seed it as the first reconnect candidate. Nulled by every path that
     * leaves the Connected state.
     */
    @Volatile
    private var connectedCandidate: AutoModeCandidate? = null

    private var connectedWatcher: kotlinx.coroutines.Job? = null

    /**
     * Probe for the currently connected tunnel, or null when the watchdog is
     * disabled or no tunnel is connected. Every path that ends a connection
     * must stop it, so a stopped probe can never keep proving life for a
     * tunnel that is gone.
     */
    private var livenessProbe: TunnelLivenessProbe? = null

    /**
     * Stop and forget the liveness probe. Idempotent, and used by every path
     * that ends a connection so no code path can leave a probe running.
     */
    private fun stopLivenessProbe() {
        livenessProbe?.stop()
        livenessProbe = null
    }

    fun start() {
        // v1.0.52 (§1.6.44) TASK B.3(a): the check and the assignment are one
        // atomic transition — see [transitionLock].
        synchronized(transitionLock) { startLocked() }
    }

    /** Â§12: cancel everything; the loop stops at the next suspension point. */
    fun stop() {
        synchronized(transitionLock) {
            connectedWatcher?.cancel()
            connectedWatcher = null
            TunnelStateWatcher.onTunnelLost = null
            stopLivenessProbe()
            skipRequested.set(false)
            // v1.0.54 (§1.6.46) PART 1 / R-1: a stop ends any skip that was
            // still being prepared, so a later Connected skip is not coalesced
            // against a transition that no longer exists.
            skipInFlight = false
            overrideServers = null
            // R8: a user stop ends the ownership record; no reconnect may name it.
            connectedCandidate = null
            pendingReconnectServer = null
            if (!isRunning) {
                setState(AutoModeState.Disconnected)
                return
            }
            adapter.log("[AUTO] User requested stop")
            job?.cancel()
            job = null
            // Stage 2F-F.28 Fix E: request REAL teardown before publishing the
            // terminal state.
            //
            // stop() previously only cancelled the run coroutine and set
            // Disconnected. It never told any VPN service to tear down, so stopping
            // while Connecting could leave a half-established or fully established
            // tunnel running while the UI claimed Disconnected — exactly the
            // divergence reproduced on device in Stage 2F-F.26-ALT (F.27 RC-2).
            //
            // requestDisconnect() is non-suspending, so the teardown intents are
            // genuinely enqueued before setState runs. The ordering this stage
            // requires (request teardown, then publish Disconnected) therefore
            // holds, while the synchronous-state contract of onButtonPressed() is
            // preserved.
            adapter.requestDisconnect()
            setState(AutoModeState.Disconnected)
        }
    }

    /** Â§3 button semantics across the four states. */
    fun onButtonPressed() {
        // Stop / disconnect must stay synchronous: the caller asserts the state
        // right after the call returns. Only the "start" branch needs the
        // suspendable server-list check, and start() already resolves the list
        // inside the run coroutine, so the guard lives there instead of here.
        onButtonPressedWithoutGuard()
    }

    /**
     * Starts a run, unless the global tunnel state says a connection is already
     * in progress.
     *
     * The guard reads [GlobalVpnTracker], the same single source of truth the
     * services and the UI read, so a manual connection that is still coming up
     * cannot be duplicated by Auto Connect — and vice versa. Without it the
     * two subsystems could each believe they own the tunnel.
     *
     * The VpnM Phase 1.5 empty-list guard lives inside [runAutoMode], where
     * the candidate list is actually resolved, so there is no separate
     * pre-check here.
     *
     * @return true when a run was actually started.
     */
    fun startOrRefuseIfNoServers(): Boolean {
        if (_state.value !is AutoModeState.Disconnected && _state.value !is AutoModeState.Error) {
            // Already connecting or connected: the button means stop/disconnect.
            onButtonPressedWithoutGuard()
            return false
        }
        // Someone else is mid-connection (a manual connect, a service restart).
        // Refuse rather than race it; the tunnel state is not ours to change.
        if (isTunnelBusy()) {
            adapter.log("[AUTO] start refused: a connection is already in progress")
            return false
        }
        overrideServers = null
        resetReconnectBudget()
        start()
        return true
    }

    private fun onButtonPressedWithoutGuard() {
        when (_state.value) {
            is AutoModeState.Disconnected, is AutoModeState.Error -> {
                // v1.0.31 (D-4): a tunnel that this run does not own — a manual
                // connection, or a manual connect that is still coming up — must
                // not be raced. Before v1.0.31 this guard existed only in
                // [startOrRefuseIfNoServers], which has no production caller, so
                // the button dialled a second, competing tunnel while the first
                // was live. Refusing is the fail-closed choice: the user can
                // disconnect and press again. Spec §6.7 R2 / §6.8.
                //
                // v1.0.32 (AUTOCONNECT-FIX-2, BUG-A): this branch was already
                // correct; the predicate it received was not. Production wired
                // [isTunnelBusy] to `GlobalVpnTracker.isBusy`, which is
                // CONNECTING||DISCONNECTING and therefore blind to CONNECTED —
                // i.e. blind to a manual tunnel that had finished connecting,
                // the exact hazard this guard exists for. Device evidence
                // (DEVICE_VERIFY_AUTOCONNECT_FIX_REPORT.md §5) showed the run
                // starting anyway and tearing the manual tunnel down. The
                // engine now supplies `GlobalVpnTracker.isActiveOrBusy`.
                // Note this guard covers ONLY the Disconnected/Error branch:
                // the Connected branch below (OP-R6) is deliberately not gated.
                if (isTunnelBusy()) {
                    adapter.log("[AUTO] start refused: a tunnel is already active")
                    return
                }
                overrideServers = null
                // A fresh start always begins at the top of the list.
                resumeFromServerKey = null
                resetReconnectBudget()
                start()
            }
            is AutoModeState.Connecting -> stop()
            is AutoModeState.Connected -> {
                // v1.0.54 (§1.6.46) PART 1 / R-1: while a Next-Server skip is
                // being prepared, the state still reads Connected — and it can
                // keep reading Connected for as long as the teardown and list
                // resolution take. A press in that window is NOT treated as a
                // second skip (that is the R-1 double-dial), but it is also not
                // swallowed: §3 says the button means "disconnect" whenever the
                // state is Connected, and ignoring a stop press for a whole
                // teardown would break that contract. So it stops, which also
                // cancels the pending continuation.
                if (skipInFlight) {
                    adapter.log("[AUTO] Skip in flight; button press treated as stop")
                    stop()
                    return
                }
                // v1.0.31 (OP-R6 / D-5): pressing while connected means "move
                // on", not "start over". Record the position after the server
                // that connected, then tear down and run again from there.
                //
                // Ordering: [disconnectNow] issues the teardown request
                // synchronously (it is enqueued before the state is published —
                // see its comment), and [runAutoMode] then resolves the server
                // list and probes before its first `connect()`. The DISCONNECT
                // therefore reaches the service ahead of the new run's first
                // ACTION_CONNECT without making this (synchronous) handler
                // suspend.
                resumeFromServerKey = connectedCandidate?.let { serverKey(it) }
                disconnectNow()
                start()
            }
        }
    }

    /**
     * R8 (OQ-1): clear the reconnect loop guard.
     *
     * Called ONLY by the two user-initiated start branches, never by
     * [onUnexpectedDisconnect]'s own internal [start]. A reconnect must keep
     * accumulating against the window or the guard would never trip; a fresh
     * user press ("Reset the counter when the user manually starts Auto Mode
     * again", OQ-1) hands R8 a clean budget.
     */
    private fun resetReconnectBudget() {
        synchronized(reconnectLock) { reconnectTimestamps.clear() }
        pendingReconnectServer = null
    }

    /** Â§3/Â§29-Test10: pressing while Connected disconnects via the normal flow. */
    fun disconnectNow() {
        connectedWatcher?.cancel()
        connectedWatcher = null
        TunnelStateWatcher.onTunnelLost = null
        stopLivenessProbe()
        skipRequested.set(false)
        // v1.0.54 (§1.6.46) PART 1 / R-1: see [stop].
        skipInFlight = false
        overrideServers = null
        // R8: explicit user disconnect ends ownership; a later loss must not
        // reconnect a tunnel the user deliberately closed.
        connectedCandidate = null
        pendingReconnectServer = null
        if (isRunning) {
            // Cancel the active run, then clean up the tunnel.
            job?.cancel()
            job = null
            // Stage 2F-F.28 Fix E: request teardown synchronously so the request
            // is genuinely issued before the terminal state is published, then
            // await confirmation on a background coroutine.
            adapter.requestDisconnect()
            scope.launch { adapter.disconnect() }
        } else {
            adapter.requestDisconnect()
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
        .sortedForAutoConnect()

    /**
     * Stage 2F-F.13 (D2 + D8 + Y4): the protocols Auto Connect may actually
     * attempt for [candidate], in exactly the user's Protocol Priority order.
     *
     * This is the single place that answers "may Auto Connect use this protocol
     * for this server?". Three things must agree:
     *
     * 1. the data model supports it ([supports] — which already encodes the
     *    protocol-specific port validity, so a server that publishes no SoftEther
     *    TCP port, no OpenVPN port, or no resolvable SSTP port is not usable
     *    through that transport, and no port is ever fabricated for it);
     * 2. the app can drive it unattended ([isAutomatable]);
     * 3. it is in the user's priority list at all — that list is an attempt
     *    ORDER, so it contributes ordering, not a global eligibility filter.
     *
     * **D2 in one sentence:** a server is never rejected because its
     * highest-priority protocol is unusable. The highest-priority *usable* one is
     * tried first and the rest follow.
     */
    fun usableProtocols(
        candidate: AutoModeCandidate,
        priorities: List<AutoModeProtocol>,
    ): List<AutoModeProtocol> = priorities.filter { it.isUsableFor(candidate) }

    /** Supported by the model, automatable by the app, and carrying real connection info. */
    private fun AutoModeProtocol.isUsableFor(candidate: AutoModeCandidate): Boolean =
        supports(candidate) && isAutomatable()

    /**
     * Stage 2F-F.13 (D8): L2TP/IPsec is manual-only, so Auto Connect must not
     * select it.
     *
     * The rule is stated here, in the layer that owns Auto Connect eligibility,
     * instead of resting on the Android adapter alone. The adapter's
     * `canAutoConnect` is still consulted — it is where a future protocol's
     * automatability will be known — it is simply no longer the only thing
     * standing between an L2TP entry in the user's priority list and a background
     * attempt that could never succeed. Manual L2TP connections, the L2TP UI and
     * the published capability data are untouched.
     */
    private fun AutoModeProtocol.isAutomatable(): Boolean = when (this) {
        AutoModeProtocol.L2TP_IPSEC -> false
        else -> adapter.canAutoConnect(this)
    }

    /**
     * The endpoint to probe for [candidate] over [protocol], or **0** when this
     * protocol published nothing to probe.
     *
     * A missing endpoint is NOT a capability verdict: the protocol stays usable,
     * it simply cannot be pre-probed. This is why SoftEther UDP stays connectable
     * with `seUdpSupported` set and no published UDP port — the native stack
     * accelerates over the TCP control channel and learns the UDP endpoint at
     * runtime.
     *
     * Stage 2F-F.16 (Y2): such a server is still handed to the probe, which
     * reports UNKNOWN for it. "We had no endpoint to test" must be recorded as *no
     * conclusion* — not as an absent result that reads like success, and not as
     * UNREACHABLE, which would be a claim we cannot support. No port is ever
     * borrowed from another protocol to manufacture a probe.
     */
    private fun probeEndpoint(
        candidate: AutoModeCandidate,
        protocol: AutoModeProtocol,
    ): Int = protocol.endpointPort(candidate).coerceAtLeast(0)

    /**
     * Stage 2F-F.15 (D4 + Y1-b): the candidate's representative active RTT —
     * the **minimum valid** value among the RTTs of the protocols this
     * controller already deemed usable.
     *
     * Every rule the aggregation has to honour falls out of reusing
     * [usableProtocols] rather than inventing a second capability system:
     *
     * - a protocol the model does not support, or whose protocol-specific port is
     *   invalid (MS-SSTP with no resolvable port, a SoftEther server with no TCP
     *   port), is not usable and therefore contributes nothing;
     * - L2TP/IPsec is not automatable, so it never contributes even if a probe
     *   once recorded something for it;
     * - a protocol with no measured RTT contributes nothing — the TCP answer is
     *   never borrowed for UDP, and the Server List's protocol-less answer is
     *   never borrowed for Auto Connect.
     *
     * Only strictly positive values count (`0` and the `-1` that an unreachable
     * probe reports are "no measurement", not a measurement). No average, no
     * maximum, no priority weighting, and null when nothing is valid — never a
     * stand-in number.
     *
     * `internal` rather than `private` so the aggregation can be asserted
     * directly: a missing RTT deliberately does not move a candidate (Y1-a), so
     * the dial order cannot reveal whether an RTT was aggregated, ignored or
     * rejected. The value has to be checked at the source.
     */
    internal fun representativeRtt(
        candidate: AutoModeCandidate,
        priorities: List<AutoModeProtocol>,
    ): Long? = usableProtocols(candidate, priorities)
        .mapNotNull { protocol -> activeRttProvider(candidate.ip ?: return@mapNotNull null, protocol) }
        .filter { it > 0L }
        .minOrNull()

    /** [candidates] carrying their aggregated active RTT, ready for ranking. */
    private fun List<AutoModeCandidate>.withActiveRtt(
        priorities: List<AutoModeProtocol>,
    ): List<AutoModeCandidate> = map { it.copy(activeRttMs = representativeRtt(it, priorities)) }

    /**
     * Stage 2F-F.15 (D4): Auto Connect's ranking — the Phase 1 primary keys
     * first, then active RTT as the final tie-break.
     *
     * RTT is applied as a *pass* over the already-primary-sorted list rather
     * than as one more `thenBy` key, because "missing RTT is neutral" (Y1-a) is
     * not expressible as a comparable key:
     *
     * - `thenBy { it.activeRttMs ?: Long.MAX_VALUE }` is banned by Y1-a and for a
     *   good reason: it would rank every unmeasured server below every measured
     *   one, which is exactly the implicit "missing is worse" penalty the stage
     *   forbids — and a server that simply has not been probed yet would be
     *   demoted for it.
     * - returning `0` from the comparator whenever either side is missing is
     *   neutral in the pairwise sense but is NOT a valid ordering: with
     *   `A(100ms) == B(unknown) == C(50ms)` and `C < A`, the relation is not
     *   transitive. `sortedWith` delegates to TimSort, which detects exactly this
     *   and throws `IllegalArgumentException: Comparison method violates its
     *   general contract!` on lists large enough to merge — i.e. on real server
     *   lists. Shipping that would turn a ranking nicety into a crash.
     *
     * So the tie-break is a stable insertion pass inside each run of
     * equal-primary candidates: candidates with a valid RTT are ordered ascending
     * among themselves, a candidate with no RTT never moves, and it never pushes
     * another candidate either. RTT therefore only ever orders two candidates
     * that both have a measurement — the semantics Y1-a asks for — with input
     * order preserved everywhere else, which keeps the result deterministic
     * without reaching for identity or hash codes.
     */
    internal fun List<AutoModeCandidate>.sortedForAutoConnect(): List<AutoModeCandidate> {
        val ordered = sortedWith(byQuality).toMutableList()
        var runStart = 0
        while (runStart < ordered.size) {
            var runEnd = runStart + 1
            while (runEnd < ordered.size && byQuality.compare(ordered[runStart], ordered[runEnd]) == 0) {
                runEnd++
            }
            applyRttTieBreak(ordered, runStart, runEnd)
            runStart = runEnd
        }
        return ordered
    }

    /**
     * Insertion sort over [from, to) comparing only pairs where BOTH sides have a
     * valid RTT. A candidate without a measured RTT acts as a barrier: it is
     * neither moved nor moved past.
     */
    private fun applyRttTieBreak(
        ordered: MutableList<AutoModeCandidate>,
        from: Int,
        to: Int,
    ) {
        for (i in from + 1 until to) {
            val current = ordered[i]
            val rtt = current.activeRttMs
            if (rtt == null) continue
            var j = i - 1
            while (j >= 0) {
                val previous = ordered[j].activeRttMs ?: break
                if (previous <= rtt) break
                ordered[j + 1] = ordered[j]
                j--
            }
            ordered[j + 1] = current
        }
    }

    /**
     * Stage 2F-F.17 (D1): one probe target per **usable** protocol, per server.
     *
     * Phase 5 emitted only `usableProtocols(...).firstOrNull()` — one endpoint per
     * server. That was enough to keep a server whose first protocol was silent,
     * but it made D1's actual rule unrepresentable: "every usable protocol is
     * definitively UNREACHABLE" cannot be evaluated over one endpoint, and a server
     * whose highest-priority endpoint refused was removed on that evidence alone
     * while a healthy second protocol went untested.
     *
     * So every usable protocol is represented, and Protocol Priority stops being a
     * filter here. It remains what it has always been — the order protocols are
     * *attempted* in, decided by [usableProtocols] at dial time. Measuring a
     * protocol in order to decide whether the server is worth keeping is a
     * different question from choosing which protocol to try first, and
     * collapsing them is how a working UDP server once got deleted because its TCP
     * port was closed.
     */
    private fun probeTargets(
        servers: List<AutoModeCandidate>,
        priorities: List<AutoModeProtocol>,
    ): List<ReachabilityTarget> = servers.flatMap { server ->
        usableProtocols(server, priorities).map { protocol ->
            ReachabilityTarget(server, protocol, probeEndpoint(server, protocol))
        }
    }

    private suspend fun runAutoMode() {
        // v1.0.54 (§1.6.46) PART 1 / R-1: `skipInFlight` is NOT cleared here.
        // This coroutine is the replacement run a Connected-branch skip
        // published, and until its first `setState` the machine still reads
        // Connected — so the coalescing window must stay open until then.
        // `setState` owns the clear; see its comment.
        // Protocol priority profile: the user's ordered list of ENABLED
        // protocols, and the ONLY source of protocol order. There is no
        // default protocol and no implicit fallback — an empty profile means
        // the user switched every protocol off, so there is nothing we are
        // allowed to dial. Refuse before touching a single server.
        val priorities = protocolPriorityProvider()
        adapter.log("[AUTO] Started")
        if (priorities.isEmpty()) {
            adapter.log("[AUTO] No protocol enabled by the user; refusing to start")
            setState(AutoModeState.Error(ERROR_NO_ENABLED_PROTOCOL))
            job = null // terminal state: nothing is running
            return
        }
        // v1.0.42 (§1.6.35 / F2): stop before any attempt when the device has no
        // network at all. Placed here — after the protocol check, before the list
        // is resolved and before the continuation is consumed — so the run blames
        // no server, records no wedge, and does not spend the Next-Server position
        // it was handed. §6.8.4 terminal #8.
        if (!isNetworkAvailable()) {
            adapter.log("[AUTO] No network available; stopping before any attempt")
            setState(AutoModeState.Error(ERROR_NO_NETWORK))
            job = null
            return
        }
        adapter.log("[AUTO] Protocols (priority) = ${priorities.joinToString(" > ") { it.id }}")
        // Stage 2F-F.13 (D8): say once, up front, which enabled protocols Auto
        // Connect will not dial — a priority entry that silently does nothing is
        // the hardest kind of protocol bug for a user to diagnose.
        val notAutomatable = priorities.filterNot { it.isAutomatable() }
        if (notAutomatable.isNotEmpty()) {
            adapter.log(
                "[AUTO] Protocol(s) ${notAutomatable.joinToString { it.id }} cannot be automated; " +
                    "skipping them instead of waiting for a timeout",
            )
        }
        adapter.log("[AUTO] Connection timeout = ${attemptTimeoutMs() / 1000} seconds")

        val all = overrideServers ?: serverProvider()
        overrideServers = null
        // R8 (OQ-7 second chance): a reconnect run must never race a manual
        // connect that began during the settle delay or the list resolution.
        // The seed is consumed here regardless of outcome so it cannot leak into
        // the next run.
        val wasReconnect = pendingReconnectServer != null
        pendingReconnectServer = null
        if (wasReconnect && isTunnelBusy()) {
            adapter.log("[AUTO] Tunnel owned elsewhere; aborting Auto-Reconnect run")
            setState(AutoModeState.Disconnected)
            return
        }
        // VpnM Phase 1.5: refuse an empty list up-front. Without this the run
        // would fall through to the generic ERROR_NO_SERVER, telling the user
        // to retry a connection that can never start.
        if (all.isEmpty()) {
            adapter.log("[AUTO] Server list is empty; refusing to start")
            setState(AutoModeState.Error(emptyServerListMessage()))
            return
        }
        // Server ordering is independent of protocol ordering (quality DESC).
        // Stage 2F-F.15 (D4): each candidate first collects its representative
        // active RTT (minimum valid RTT over its usable protocols), then the
        // whole list is ranked by the primary keys with RTT as the final
        // tie-break.
        //
        // NOTE — this happens BEFORE this run's own probe below, exactly where
        // quality ranking has always happened. So the RTT used here is whatever
        // earlier probes in this process already recorded, never an RTT this run
        // has not measured. A first-ever run therefore ranks without the
        // tie-break (every candidate null) and subsequent runs benefit. Nothing
        // is fabricated and no ordering was deferred to make this work.
        val qualitySorted = all
            .withActiveRtt(priorities)
            .sortedForAutoConnect()
        adapter.log("[AUTO] Servers available = ${all.size}")
        adapter.log("[AUTO] Sorted by quality")

        // Stage 2F-F.17 (D1): the probe is handed one protocol/endpoint pair per
        // **usable protocol**, per server. A server with no usable protocol produces
        // no target at all, so it is never probed and never gated on by D1 — it is
        // still skipped later by the same capability rule that decides the attempt
        // order.
        //
        // The previous code applied `priorities.first()` to the whole list, which
        // turned Protocol Priority into a global eligibility gate: a server that
        // could not speak that one protocol was discarded even when it could
        // perfectly well speak the second one.
        val targets = probeTargets(qualitySorted, priorities)
        val gate = if (reachabilityFilter != null && targets.isNotEmpty()) {
            adapter.log(
                "[AUTO] Pre-filtering reachable servers via probe " +
                    "(${targets.size} endpoint(s) across ${targets.map { it.protocol.id }.distinct().joinToString(" > ")})...",
            )
            try {
                reachabilityFilter.invoke(targets)
            } catch (e: CancellationException) {
                // v1.0.52 (§1.6.44) TASK B.3(b): cancellation is NOT a probe
                // failure. Swallowing it here turned a user stop (or a scope
                // cancel) into "no evidence, keep the full list", so the run
                // carried on dialling after it had been cancelled.
                throw e
            } catch (e: Exception) {
                // A probe that failed to run has measured nothing, which is not a
                // verdict. Null means "no evidence", never "all down".
                adapter.log("[AUTO] Reachability probe failed: ${e.message}; using original list")
                null
            }
        } else {
            null
        }

        // Stage 2F-F.17 (D1 = FILTER_ONLY): three outcomes, deliberately distinct.
        //
        // - the gate kept something -> use it. It is a subsequence of
        //   `qualitySorted`, so the comparator stays the only ranking authority;
        // - the gate removed everything *and says so* -> that is a real
        //   measurement (every usable protocol of every server definitively
        //   UNREACHABLE), so honour it and let the existing empty-list path report
        //   ERROR_NO_SERVER;
        // - nothing measured at all (no filter, no targets, a throwing probe, or a
        //   gate that could not conclude) -> keep the full list.
        //
        // The third case is the pre-existing "a probe must never empty the queue"
        // rule, and it is why an empty `kept` cannot be read on its own: before
        // D1 that read as "keep everything", which silently undid the removal of a
        // genuinely dead network.
        val servers = when {
            gate == null -> qualitySorted
            gate.kept.isNotEmpty() -> {
                adapter.log("[AUTO] Reachable servers selected: ${gate.kept.size}")
                // The result is rebuilt by walking `qualitySorted` and keeping the
                // entries the gate named, never by walking the gate's own list.
                // That is what makes the comparator the sole authority on order: a
                // gate that returned its survivors in any other order still yields
                // a subsequence of the ranked input. Identity, not equality, is the
                // match — two different servers may compare equal by value.
                qualitySorted.filter { candidate -> gate.kept.any { it === candidate } }
            }
            gate.allUnreachable -> {
                adapter.log("[AUTO] Every server's usable protocols are unreachable; none left to try")
                emptyList()
            }
            else -> qualitySorted
        }

        if (servers.isEmpty()) {
            adapter.log("[AUTO] No compatible server found")
            setState(AutoModeState.Error(ERROR_NO_SERVER))
            return
        }

        val attempted = mutableSetOf<String>()
        var attempt = 0

        // v1.0.31 (OP-R6 / D-5), identity-based in v1.0.42 (§1.6.35 / F6): a
        // press taken while Connected asked this run to continue after the server
        // that connected. The value is consumed HERE, once, and cleared
        // immediately — see [resumeFromServerKey] for why consumption happens at
        // read time rather than at a terminal branch (an internal R8 reconnect
        // calls start() directly and must never inherit a stale position).
        //
        // The key is looked up in the list THIS run resolved, so a re-sort cannot
        // shift the start position. If the server has left the dataset, the run
        // starts from the top and says so — it never restarts silently.
        val resumeKey = resumeFromServerKey
        val matchedAt = resumeKey?.let { key ->
            servers.indexOfFirst { serverKey(it) == key }
        } ?: -1
        val startAt = if (matchedAt >= 0) {
            val pos = matchedAt + 1
            adapter.log(
                "[AUTO] Continuing after $resumeKey; starting at index $pos of ${servers.size}",
            )
            pos
        } else {
            if (resumeKey != null) {
                adapter.log("[AUTO] Previous connected server no longer in the list; starting from the top")
            }
            0
        }
        resumeFromServerKey = null

        for (i in startAt until servers.size) {
            val server = servers[i]
            if (!currentCoroutineContext().isActive) return

            // v1.0.42 (§1.6.35 / F2): the network can drop mid-run. Re-checked
            // before every server so a lost connection stops the run instead of
            // marking the rest of the list TIMED_OUT — each of which would earn a
            // WedgeTracker strike for a failure the server did not cause.
            if (!isNetworkAvailable()) {
                adapter.log("[AUTO] Network lost; stopping before the next attempt")
                setState(AutoModeState.Error(ERROR_NO_NETWORK))
                job = null
                return
            }

            val key = wedgeKey(server)
            if (!attempted.add(key)) continue

            // FIX-3: a server that has already wedged this process twice is skipped for the
            // rest of the session. Checked here — after de-duplication and before any dial —
            // so an excluded server consumes no retry budget, no attempt counter and, above
            // all, no native admission.
            if (wedgeTracker.isExcluded(key)) {
                adapter.log(
                    "[AUTO] Skipping ${server.hostname ?: server.ip}: excluded for this " +
                            "session after ${wedgeTracker.countFor(key)} wedges",
                )
                continue
            }

            // VpnM Phase 3 "Cursor": the absolute position of the server being
            // tried, counted over the whole (post-filter) list, so the UI can say
            // "server #5 of 20" rather than only "attempt 1 of 3".
            // v1.0.31: the loop index IS that position, so it is used directly
            // instead of a linear `indexOf` search (which also mis-reported the
            // first of two equal candidates).
            currentServerIndex = i
            totalServerCount = servers.size

            // Stage 2F-F.13 (D2 + D8 + Y4): the protocols to try for THIS server,
            // in the user's Protocol Priority order, via the one capability decision
            // the controller owns. A server stays eligible as long as one protocol is
            // usable — the highest-priority protocol being unusable never disqualifies
            // it. Protocols the app cannot drive unattended (L2TP/IPsec) and protocols
            // with no valid protocol-specific connection information are dropped here,
            // before the attempt, instead of being entered and then waited out.
            val supported = usableProtocols(server, priorities)
            if (supported.isEmpty()) {
                adapter.log("[AUTO] Skipping ${server.hostname ?: server.ip}: no supported protocol")
                continue
            }
            attempt++
            adapter.log("[AUTO] Trying #$attempt ${server.hostname ?: server.ip}")
            rememberRemaining(servers, server)
            skipRequested.set(false)

            // AUTOCONNECT-AUDIT D-2: the per-server cumulative NATIVE_BUSY retry-delay
            // budget that used to be accumulated here is gone. A refusal is no longer
            // answered with a fixed number of blind retries; the controller waits for the
            // observable slot release instead (see the NATIVE_BUSY branch below).

            var connectedProtocol: AutoModeProtocol? = null
            // AUTOCONNECT-AUDIT D-5: at most one wedge per server per run. The ledger
            // counts "runs in which this server produced no tunnel", so trying a second
            // protocol on the same server must not count as a second strike.
            var wedgedThisServer = false
            // v1.0.32 (AUTOCONNECT-FIX-2, BUG-B): how many NATIVE_BUSY refusals this
            // server produced in THIS run. See the NATIVE_BUSY branch for why a refusal
            // is not always innocent, and [NATIVE_BUSY_WEDGE_THRESHOLD].
            var nativeBusyThisServer = 0
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

                // Stage 2F-F.28 Fix D: arm the tunnel watcher BEFORE the connect
                // request. Previously the watcher was armed inside awaitTunnel(),
                // i.e. after connect() had already been issued, so a fast
                // CONNECTED could arrive with nobody listening and be lost.
                adapter.armTunnelWatch(protocol)

                try {
                    adapter.connect(server, protocol)
                } catch (e: VpnPermissionMissingException) {
                    adapter.log("[AUTO] VPN permission missing; stopping Auto Mode")
                    adapter.disconnect()
                    setState(AutoModeState.Error(ERROR_VPN_PERMISSION))
                    job = null
                    return
                } catch (e: CancellationException) {
                    // v1.0.52 (§1.6.44) TASK B.3(b): a cancelled connect is not a
                    // protocol failure. Rethrowing lets the run unwind as
                    // cancelled instead of being reported as "this protocol
                    // threw" and rotating to the next one.
                    throw e
                } catch (e: Exception) {
                    adapter.log("[AUTO] Connect threw ${e.javaClass.simpleName}: ${e.message}")
                    // v1.0.52 TASK B.3(b): teardown must survive the cancellation
                    // that caused this catch — `disconnect()` suspends, and a
                    // cancelled context would abort it before the teardown ran,
                    // leaving the half-established attempt behind.
                    withContext(NonCancellable) { adapter.disconnect() }
                    // v1.0.31 (AUTOCONNECT-FIX, D-2 / OP-R4): a thrown exception
                    // is one (server, protocol) failing, so it advances to the
                    // NEXT PROTOCOL — the same transition as every other
                    // non-timeout outcome. Before v1.0.31 this was `break`,
                    // which skipped the server's remaining protocols and jumped
                    // to the next server: two failures with identical meaning
                    // rotated at different granularity, so a server that threw
                    // on its first protocol never got a chance at its second.
                    continue
                }
                val outcome = adapter.awaitArmedOutcome(protocol, attemptTimeoutMs())

                if (!currentCoroutineContext().isActive) return

                if (skipRequested.get()) {
                    adapter.log("[AUTO] Server skipped by user")
                    skipRequested.set(false)
                    adapter.disconnect()
                    connectedProtocol = null
                    break // user skip: next SERVER (not next protocol)
                }

                if (outcome == AttemptOutcome.TUNNEL_UP) {
                    connectedProtocol = protocol
                    break
                }

                // v1.0.53 (§1.6.45) TASK B.6: the data does not carry what this
                // protocol needs, so nothing was dialled and nothing was waited for.
                //
                // Handled before the NATIVE_BUSY branch because it is a distinct,
                // cheap verdict: there is no slot to wait for (no native attempt was
                // started) and no own-tunnel re-check to do (nothing could have come
                // up). It rotates to the NEXT PROTOCOL on the same server — the
                // server itself may be perfectly reachable over SSTP or SoftEther —
                // and it records NO wedge, because a stripped config says nothing
                // about the endpoint. That is the same transition as every other
                // non-timeout failure (v1.0.31 D-2), so the no-terminal contract of
                // v1.0.37 is untouched.
                if (outcome == AttemptOutcome.MISSING_CONFIG) {
                    adapter.log(
                        "[AUTO] MISSING_CONFIG on ${server.hostname ?: server.ip}/" +
                                "${protocol.id}; no profile bytes for this protocol, " +
                                "moving to the next protocol (no wedge, no wait)",
                    )
                    continue
                }

                // Stage 2F-F.28AA: the start was refused because this application's own
                // previous native operation is still unresolved.
                //
                // The selected server was never contacted, so there is nothing to conclude
                // about it. Advancing the protocol or server cursor here is what made Auto
                // Mode walk a healthy server list and finish with ERROR_NO_SERVER while the
                // only fault was our own stuck native operation.
                //
                // Fail-closed: the registry still holds the slot and nothing here may
                // circumvent that.
                //
                // AUTOCONNECT-AUDIT D-2 (supersedes the F.28AL-Y retry-after-refusal policy):
                // the previous policy spent two fixed 2 s retries and then terminated the
                // whole run. Device evidence on 2026-10-17 showed that premise was wrong —
                // the "previous cleanup completes within ~1.1 s" figure describes a teardown
                // of an operation that has ALREADY finished, not the lifetime of one still
                // inside nativeConnectWithHub, which is bounded by 5 x attemptTimeoutMs and
                // measured 18.9 s. The run therefore died 27 ms before the native operation
                // returned on its own. The slot release is observable, so it is waited for.
                if (outcome == AttemptOutcome.NATIVE_BUSY) {
                    // v1.0.36 (AUTOCONNECT-FIX-6, Fix-1): before treating NATIVE_BUSY as a
                    // failure, check whether our OWN tunnel is actually up.
                    //
                    // awaitArmedOutcome's timeout can fire a few hundred milliseconds before
                    // the CONNECTED notification arrives, which makes isNativeSlotBusy() true
                    // because OUR OWN operation is still running. Entering the wait in that
                    // state keeps the controller holding the slot that the very tunnel it
                    // just established needs, and the terminal then destroys a live tunnel.
                    //
                    // Device evidence (DEVICE_VERIFY_AUTOCONNECT_FIX_4_UNFILTERED_REPORT.md
                    // §5.3): 3 of 4 runs destroyed a WORKING tunnel — `DHCP SUCCESS` and
                    // `SLOT_UNRELEASED_DIAG … tracker=CONNECTED ownTunnel=true` were logged
                    // by the very code that then destroyed it — and 1 run excluded a healthy
                    // server after two false wedges.
                    //
                    // This is the same check the post-attempt branch already performs (the
                    // `ownTunnelUp` block below). It was simply missing from this branch, so a
                    // healthy tunnel racing the timeout took the failure path. Exiting through
                    // `break` reuses the existing success path: the `connectedProtocol != null`
                    // block after the protocol loop sets Connected, calls onSuccess and starts
                    // the connected watcher. No wedge is recorded, because the endpoint did
                    // not fail — our own operation succeeded.
                    if (adapter.isOwnTunnelEstablished()) {
                        adapter.log(
                            "[AUTO] Tunnel came up despite NATIVE_BUSY; treating attempt as " +
                                    "connected",
                        )
                        connectedProtocol = protocol
                        break
                    }
                    // AUTOCONNECT-AUDIT D-2: a refusal is not a server verdict. It means
                    // this application's OWN previous native operation is still running, so
                    // the selected server was never contacted.
                    //
                    // v1.0.32 (AUTOCONNECT-FIX-2, BUG-B): that reasoning holds for the
                    // FIRST refusal on a server, and stops holding when the same server
                    // produces refusal after refusal inside one run. A server whose every
                    // protocol is refused was never contacted by any of them, yet it keeps
                    // winning the re-selection because nothing records it — so the run
                    // spends a full native-slot budget on it and terminates without ever
                    // reaching the next server. Device evidence
                    // (DEVICE_VERIFY_AUTOCONNECT_FIX_REPORT.md §7): vpn131251792 was
                    // selected in four consecutive runs (`attempt=12/13/14/15`), each
                    // ending in `Native slot not released within 25000ms … stopping`, and
                    // no `Skipping …: excluded for this session` line was ever emitted.
                    //
                    // The count is per server per run and is consumed once: a single
                    // refusal still says nothing about the server (a leftover operation
                    // from the previous server is the common cause), so one strike must
                    // not exclude anything. Reaching [NATIVE_BUSY_WEDGE_THRESHOLD] in the
                    // same run is evidence about THIS endpoint, and is recorded through
                    // the same one-wedge-per-server-per-run path as TIMED_OUT.
                    nativeBusyThisServer++
                    if (!wedgedThisServer &&
                        nativeBusyThisServer >= NATIVE_BUSY_WEDGE_THRESHOLD
                    ) {
                        adapter.log(
                            "[AUTO] NATIVE_BUSY x$nativeBusyThisServer on " +
                                    "${server.hostname ?: server.ip}; recording a wedge " +
                                    "(the endpoint never accepted a native attempt)",
                        )
                        recordWedge(server, key)
                        wedgedThisServer = true
                    }
                    //
                    // The release of the process-wide slot is directly observable, so wait
                    // for the real event instead of paying a fixed number of blind retries
                    // and then abandoning the run.
                    //
                    // The old code spent two fixed 2 s retries and then returned from
                    // runAutoMode() with ERROR_NATIVE_UNRESOLVED. On the 2026-10-17 device
                    // run that killed the whole Auto Mode cycle while the native operation
                    // was in fact finishing normally 27 ms later (native exit 18,915 ms at
                    // 16:18:43.191, give-up at 16:18:43.218) and the slot was released
                    // 313 ms after the run had already been abandoned. Rotation (next
                    // protocol, then next server) is the documented recovery and must not
                    // be pre-empted by a wait that can be observed directly.
                    //
                    // Safety: no new native attempt is started during the wait, so the
                    // fail-closed admission policy is not circumvented — we simply wait for
                    // it. The refusal is detected BEFORE
                    // NativeLifetimeRegistry.tryAcquire() allocates an attempt ID, so this
                    // path still consumes no new ID.
                    // v1.0.33 (AUTOCONNECT-FIX-3): timing and the diagnostic line are
                    // additions only; the wait condition and every branch below are
                    // unchanged. The device run of v1.0.32 terminated the whole run from
                    // THIS site three times and never reached the post-attempt site that
                    // carried the diagnostic, so the one log line the v1.0.32 phase needed
                    // was never emitted (DEVICE_VERIFY_AUTOCONNECT_FIX_2_REPORT.md §7).
                    val slotWaitStartedAt = System.currentTimeMillis()
                    // v1.0.36 (AUTOCONNECT-FIX-6, Fix-2): cap the slot wait to the
                    // per-attempt budget T.
                    //
                    // The uncapped 30 s figure pushed one (server, protocol) pair to
                    // 5 s attempt + 30 s wait + 5 s retry = 7×T with T=5 s, so a single
                    // refused endpoint could consume a seventh of a minute before the
                    // rotation even started. Capping to min(nativeSlotAwaitMs, T) bounds the
                    // wait to the same budget the attempt itself already had: at most
                    // 1×T wait, hence at most 3×T for the whole pair (attempt + wait +
                    // retry) instead of 7×T.
                    //
                    // The retry mechanism is deliberately KEPT (Option A). The v1.0.35
                    // `lastTeardownWasResolved` gate below still skips it when the previous
                    // teardown was unresolved, which is the common device case, so most
                    // pairs stay at 2×T — and a pair whose own tunnel came up never reaches
                    // this line at all (Fix-1 above), i.e. 1×T.
                    //
                    // The healthy path is unchanged: awaitNativeSlotRelease returns as soon
                    // as the slot is free, so a normal rotation pays nothing for the cap.
                    // Only an actually-held slot hits the budget, and the cap makes the
                    // "stuck" verdict arrive sooner rather than later.
                    val slotWaitBudget = minOf(nativeSlotAwaitMs(), attemptTimeoutMs())
                    val released = adapter.awaitNativeSlotRelease(slotWaitBudget)
                    if (!released) {
                        // v1.0.37 (AUTOCONNECT-FIX-7, Fix-2): a slot-wait timeout no longer
                        // terminals the run.
                        //
                        // Operator contract (§6.8.4 lead): "AutoConnect never stops for
                        // NATIVE_BUSY." The v1.0.36 device run proved why that matters —
                        // twice the run died on the FIRST server with
                        // `ERROR_NATIVE_UNRESOLVED`, 431 ms after a native attempt had just
                        // started and 7.7 s before the stack produced its own verdict
                        // (DEVICE_VERIFY_AUTOCONNECT_FIX_6_REPORT.md §7.1). The endpoint was
                        // never given the chance the native layer was still working towards.
                        //
                        // No wedge is recorded here. Since Fix-1 a NATIVE_BUSY means the
                        // start was REFUSED because a previous operation owns the slot: this
                        // server was never contacted, so a strike against it would be
                        // evidence about the wrong endpoint. The v1.0.34 terminal wedge is
                        // therefore withdrawn; repeated genuine refusals are still counted
                        // by `nativeBusyThisServer` above, and a server that is actually
                        // unreachable still accumulates strikes through TIMED_OUT.
                        val waitedMs = System.currentTimeMillis() - slotWaitStartedAt
                        adapter.log(
                            "[AUTO] Slot-wait expired on ${protocol.id}; moving on " +
                                    "(no terminal, per §6.8.4)",
                        )
                        adapter.log(
                            "[AUTO] Native slot not released within " +
                                    "${slotWaitBudget}ms " +
                                    "(${NativeLifetimeRegistry.process.describe()}); moving on",
                        )
                        // v1.0.33 diagnostics: same body as the post-attempt site, so the
                        // two sites are comparable line for line. `outcome` is not in scope
                        // in this branch — it can only ever be NATIVE_BUSY here.
                        adapter.log(
                            "[AUTO] SLOT_UNRELEASED_DIAG waited=${waitedMs}ms " +
                                    "budget=${slotWaitBudget}ms " +
                                    "outcome=${AttemptOutcome.NATIVE_BUSY.name} " +
                                    "protocol=${protocol.id} " +
                                    "server=${server.hostname ?: server.ip} " +
                                    "tracker=${adapter.tunnelTrackerStatus()} " +
                                    "ownTunnel=${adapter.isOwnTunnelEstablished()} " +
                                    "registry=${NativeLifetimeRegistry.process.describe()}",
                        )
                        // Ask the still-held operation to stop before the next protocol is
                        // dialled; nothing of ours is connecting in this branch, so this is
                        // a request to the previous operation, not a teardown of a tunnel.
                        adapter.disconnect()
                        // `break` leaves the protocol loop; the outer server loop advances.
                        // If every server ends this way the run reaches the ordinary
                        // "All compatible servers failed" → ERROR_NO_SERVER terminal.
                        break
                    }
                    // v1.0.35 (AUTOCONNECT-FIX-5): skip the retry when the teardown that
                    // preceded this refusal was unresolved.
                    //
                    // Device evidence (DEVICE_VERIFY_AUTOCONNECT_FIX_4_REPORT.md §15): across
                    // one session the controller performed 12 post-slot-release retries —
                    // 10 ended instantly with NATIVE_ERROR (0.1 s), 2 with NATIVE_BUSY
                    // (~5 s), and ZERO reached TUNNEL_UP. In 11 of the 12 the preceding
                    // teardown was NATIVE_UNRESOLVED, which is precisely the state in which
                    // the registry retains ownership and refuses every later start
                    // (see TeardownCompletion.Outcome.NATIVE_UNRESOLVED). The retry could
                    // not have been admitted, yet it cost the retry's own disconnect plus
                    // the slot wait that follows it (~6 s per failed protocol).
                    //
                    // Skipping it advances straight to the next protocol with the slot
                    // already confirmed free (`released` is true here), so the next attempt
                    // is a real one rather than a re-dial of a protocol that just failed.
                    // The wedge ledger is unaffected: the NATIVE_BUSY accounting above has
                    // already run for this refusal.
                    //
                    // `continue` targets the `for (protocol in supported)` loop this branch
                    // sits inside, so the shared post-attempt cleanup is skipped too. That
                    // is safe in this specific state: NATIVE_BUSY means the registry refused
                    // the start before any native attempt was admitted, so there is no
                    // tunnel of ours for the own-tunnel re-check below to adopt.
                    if (!adapter.lastTeardownWasResolved()) {
                        adapter.log(
                            "[AUTO] Skipping retry on ${protocol.id}: previous teardown " +
                                    "was unresolved",
                        )
                        continue
                    }
                    adapter.log(
                        "[AUTO] Native slot released; retrying ${protocol.id} on " +
                                "${server.hostname ?: server.ip}",
                    )
                    adapter.armTunnelWatch(protocol)
                    try {
                        adapter.connect(server, protocol)
                    } catch (e: VpnPermissionMissingException) {
                        adapter.log("[AUTO] VPN permission missing; stopping Auto Mode")
                        adapter.disconnect()
                        setState(AutoModeState.Error(ERROR_VPN_PERMISSION))
                        job = null
                        return
                    } catch (e: CancellationException) {
                        // v1.0.52 (§1.6.44) TASK B.3(b): same rule as the first
                        // dial — cancellation is not a protocol failure.
                        throw e
                    } catch (e: Exception) {
                        adapter.log("[AUTO] Connect threw ${e.javaClass.simpleName}: ${e.message}")
                        // v1.0.52 TASK B.3(b): teardown under NonCancellable, or a
                        // cancelled context would skip it.
                        withContext(NonCancellable) { adapter.disconnect() }
                        // v1.0.31 (D-2): same transition as the first dial above
                        // — the exception belongs to this (server, protocol)
                        // pair, so the next protocol on this server still gets
                        // its turn rather than being skipped.
                        continue
                    }
                    val retryOutcome = adapter.awaitArmedOutcome(protocol, attemptTimeoutMs())
                    if (!currentCoroutineContext().isActive) return
                    if (retryOutcome == AttemptOutcome.TUNNEL_UP) {
                        connectedProtocol = protocol
                        break
                    }
                    // The slot was free and the attempt still did not come up. That IS a
                    // genuine failure of this protocol, so fall through to the normal
                    // failure path and let the rotation continue.
                    adapter.log(
                        "[AUTO] Attempt after the slot was released did not come up " +
                                "(${retryOutcome.name}); moving on",
                    )
                }

                // Stage 2F-F.28 Fix C + H: before declaring failure, check
                // whether this application's OWN VPN is in fact established.
                //
                // awaitTunnel is a notification wait, not proof the tunnel is
                // down: it can expire after Builder.establish() already
                // succeeded if the CONNECTED notification was late or lost.
                // Treating that as "Protocol failed" is what previously caused
                // Auto Connect to report failure and move on while a real,
                // traffic-carrying VPN was live.
                //
                // Ownership is verified by the adapter, so a third-party VPN is
                // never adopted as this app's success.
                // v1.0.32 (AUTOCONNECT-FIX-2, INSTRUMENTATION ONLY — no behaviour
                // change): the ownership re-check stays exactly where it was. What is
                // new is a log line for the case where ownership answers `false` even
                // though the tracker says a tunnel is CONNECTED — the contradiction
                // suspected in the device run of 2026-10-18, where `DHCP success` was
                // reported and yet the attempt was classified as failed
                // (DEVICE_VERIFY_AUTOCONNECT_FIX_REPORT.md §7). Root cause STILL
                // UNKNOWN; these lines exist so the next device run can place it.
                val ownTunnelUp = adapter.isOwnTunnelEstablished()
                if (ownTunnelUp) {
                    adapter.log("[AUTO] Tunnel is established (notification missed); treating attempt as connected")
                    connectedProtocol = protocol
                    break
                }
                if (adapter.tunnelTrackerStatus() == "CONNECTED") {
                    adapter.log(
                        "[AUTO] isOwnTunnelEstablished returned false despite native " +
                                "state CONNECTED (server=${server.hostname ?: server.ip}, " +
                                "protocol=${protocol.id}, outcome=${outcome.name}); " +
                                "investigation instrumentation, see v1.0.32",
                    )
                }

                // Stage 2F-F.28AL-Y-IMPL (§8.1/§8.8): TIMED_OUT is terminal for THIS
                // attempt only — there is deliberately no same-server retry. The
                // rotation below (next protocol, then next server) is its recovery.
                if (outcome == AttemptOutcome.TIMED_OUT) {
                    adapter.log(
                        "[AUTO] TIMED_OUT after ${attemptTimeoutMs()}ms on " +
                                "${server.hostname ?: server.ip}/${protocol.id}; " +
                                "cleanup then next protocol (no same-server retry)",
                    )
                    // AUTOCONNECT-AUDIT D-5: this is the one outcome that implicates the
                    // server — the attempt reached it and nothing came up inside the
                    // budget. A refusal and a fast native failure say nothing about it.
                    if (!wedgedThisServer) {
                        recordWedge(server, key)
                        wedgedThisServer = true
                    }
                }

                // AUTOCONNECT-AUDIT D-4 + D-6: the stack delivered a terminal failure
                // before the budget expired, so this endpoint is not usable over this
                // protocol (a refused port, a rejected handshake, or an answer to the
                // VPNCONNECT watermark that is not a SoftEther Hello). Logged as its own
                // cause because it is a verdict, not a slow connect, and it must not be
                // mistaken for either a timeout or a wedge.
                if (outcome == AttemptOutcome.NATIVE_ERROR) {
                    adapter.log(
                        "[AUTO] NATIVE_ERROR on ${server.hostname ?: server.ip}/" +
                                "${protocol.id}; the stack rejected this endpoint, " +
                                "moving to the next protocol",
                    )
                }

                adapter.log("[AUTO] Protocol failed: ${protocol.id}")
                adapter.disconnect() // cleanup before the next protocol attempt

                // AUTOCONNECT-AUDIT D-3: a failed attempt may have left the previous
                // native operation running, and nothing above waits for it to end. The
                // next protocol would then be refused (NATIVE_BUSY) before it ever reached
                // the server — which is exactly the cascade that made a healthy list look
                // exhausted.
                //
                // The teardown above asked the operation to stop; this waits for the
                // process-wide slot to actually be free. It returns immediately when the
                // slot is already free, so the healthy path pays nothing. An operation
                // that never releases is the one genuine terminal.
                //
                // v1.0.32 (AUTOCONNECT-FIX-2, INSTRUMENTATION ONLY): the loop's
                // control flow here is unchanged. `waitedMs` and the extra diagnostic
                // line exist because the device run of 2026-10-18 left this branch as
                // the whole-run terminal without any record of what the app believed
                // about the tunnel it had just given up on. Root cause STILL UNKNOWN.
                val slotWaitStartedAt = System.currentTimeMillis()
                // v1.0.36 (AUTOCONNECT-FIX-6, Fix-3): same cap as Fix-2, applied to the
                // post-attempt branch. The contract is per (server, protocol) time of at
                // most 2×T here — the attempt plus a wait that cannot outlast it — instead
                // of the 6×T the uncapped 30 s figure allowed with T=5 s.
                //
                // NATIVE_ERROR short-circuits the wait entirely (no slot was ever
                // acquired), so the cap only affects the outcomes that really hold the slot.
                val postWaitBudget = minOf(nativeSlotAwaitMs(), attemptTimeoutMs())
                val slotReleased =
                    outcome == AttemptOutcome.NATIVE_ERROR ||
                            adapter.awaitNativeSlotRelease(postWaitBudget)
                if (!slotReleased) {
                    // v1.0.37 (AUTOCONNECT-FIX-7, Fix-2): the post-attempt slot-wait
                    // timeout no longer terminals the run either. This was the last of the
                    // two sites that could end a whole run on a NATIVE_BUSY-shaped event;
                    // §6.8.4 now holds at both of them.
                    //
                    // The same reasoning as the NATIVE_BUSY branch applies: an operation
                    // that outlives 1×T of extra waiting is a reason to move to the next
                    // (server, protocol) pair, not a reason to abandon the remaining list.
                    // A server that really is unreachable accumulates strikes through
                    // TIMED_OUT and is excluded by the wedge ledger after
                    // EXCLUSION_THRESHOLD consecutive failures; the only terminal that
                    // survives is ERROR_NO_SERVER once every candidate has been tried.
                    val waitedMs = System.currentTimeMillis() - slotWaitStartedAt
                    adapter.log(
                        "[AUTO] Slot-wait expired on ${protocol.id}; moving on " +
                                "(no terminal, per §6.8.4)",
                    )
                    adapter.log(
                        "[AUTO] Native slot not released within ${postWaitBudget}ms " +
                                "after ${outcome.name} on ${protocol.id} " +
                                "(${NativeLifetimeRegistry.process.describe()}); moving on",
                    )
                    // v1.0.32 diagnostics: everything needed to place the race is on
                    // one line, so a single device log is enough to decide whether the
                    // tunnel was in fact up (ownership) while the slot stayed held.
                    adapter.log(
                        "[AUTO] SLOT_UNRELEASED_DIAG waited=${waitedMs}ms " +
                                "budget=${postWaitBudget}ms outcome=${outcome.name} " +
                                "protocol=${protocol.id} " +
                                "server=${server.hostname ?: server.ip} " +
                                "tracker=${adapter.tunnelTrackerStatus()} " +
                                "ownTunnel=${adapter.isOwnTunnelEstablished()} " +
                                "registry=${NativeLifetimeRegistry.process.describe()}",
                    )
                    // The plain `disconnect()` above ran BEFORE this wait; this second
                    // request is addressed to the operation that outlived it. No recovery
                    // teardown and no ERROR_NATIVE_UNRESOLVED: the run continues, and
                    // `giveUpWithUnresolvedNativeOperation()` is now unreferenced (kept
                    // for the contract note in its KDoc, not called).
                    adapter.disconnect()
                    break
                }
            }

            if (connectedProtocol != null) {
                // FIX-3: a verified tunnel proves this server does not wedge, so its record
                // is cleared. Without this, a server that wedged twice early in a session
                // could never be rehabilitated and the ledger would drift from "known bad"
                // to "once bad".
                wedgeTracker.clear(key)
                adapter.log("[AUTO] Tunnel established")
                adapter.log("[AUTO] Connected successfully")
                onSuccess(server, connectedProtocol)
                rememberRemaining(servers, server)
                // R8: remember the exact candidate so an unexpected disconnect can
                // name it and re-seed it as the first reconnect candidate.
                connectedCandidate = server
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
        // v1.0.31 (D-5): the continuation is spent. This is a second, defensive
        // reset — [resumeFromServerKey] is already cleared when it is consumed
        // above; clearing here too means "the list is exhausted" can never leave
        // a position behind for a later run.
        resumeFromServerKey = null
        job = null // terminal state: the run is over; next press restarts cleanly
    }

    /** Store the not-yet-attempted tail so user skip continues from the next server. */
    private fun rememberRemaining(servers: List<AutoModeCandidate>, current: AutoModeCandidate) {
        // v1.0.52 (§1.6.44) TASK B.3(c): use the SAME identity the continuation
        // and the wedge ledger use. The previous comparison was
        // `it.ip == current.ip && it.hostname == current.hostname`, which
        // disagrees with [serverKey] whenever a hostname is null in one source
        // and "" in the other (`null != ""`), so the cursor could silently land
        // on the wrong entry — or on none, making the skip fall back to the
        // whole list and re-try servers already attempted.
        val key = serverKey(current)
        val idx = servers.indexOfFirst { serverKey(it) == key }
        remainingCandidates = if (idx in 0 until servers.lastIndex) {
            servers.subList(idx + 1, servers.size)
        } else {
            emptyList()
        }
    }

    /**
     * v1.0.42 (§1.6.35 / F1): the live per-attempt timeout.
     *
     * Read at attempt time, never cached, so a Settings change applies to the
     * next attempt.
     *
     * The guard is `coerceAtLeast(1L)` — positivity only, deliberately **not** a
     * second floor. Production's provider is
     * `du.getAutoModeTimeoutSeconds().toLong() * 1000`, and that getter already
     * clamps to 5..60 s at the source (`DataUtil.kt:361`), so a corrupt
     * preference cannot reach this class. A hard floor here would therefore be
     * invisible in production while silently overriding every test double that
     * asks for a deliberately short budget (the two `…IsCappedToTheAttemptBudget`
     * tests use 300 ms to keep the cap assertion cheap and exact).
     */
    private fun attemptTimeoutMs(): Long =
        attemptTimeoutMsProvider().coerceAtLeast(1L)

    /**
     * v1.0.42 (§1.6.35 / B-3): the live native-slot wait budget.
     *
     * Floored at 1 ms and capped at [NATIVE_SLOT_AWAIT_MAX_MS]. Production's
     * lambda owns the v1.0.36 floor ([NATIVE_SLOT_AWAIT_MS], 30 s) and the D-2
     * cap, so this helper only has to reject a non-positive value: a second
     * hard floor here would be invisible in production yet would override every
     * test double that deliberately asks for a short wait (for example
     * `AutoModeNativeBusyTest`, whose 200 ms slot wait exists to keep the
     * "genuinely stuck" cases inside their 15 s bounds). The cap stays because
     * a provider that ignores it could make the terminal give-up unreachable.
     */
    private fun nativeSlotAwaitMs(): Long =
        nativeSlotAwaitMsProvider().coerceIn(1L, NATIVE_SLOT_AWAIT_MAX_MS)

    /**
     * FIX-3: the ledger key for a candidate.
     *
     * Same shape as the run's de-duplication key (`ip|hostname`), defined once so the ledger
     * and the attempt loop can never disagree about what "this server" means.
     */
    private fun wedgeKey(server: AutoModeCandidate): String =
        serverKey(server.ip, server.hostname)

    /**
     * v1.0.42 (§1.6.35 / F6): the stable identity used by the Next-Server
     * continuation and by the skip filter.
     *
     * Delegates to the same formatter as [wedgeKey] so the continuation, the
     * skip filter and the wedge ledger can never disagree about what "this
     * server" means. See [resumeFromServerKey] for why the continuation moved
     * from an index to an identity.
     */
    private fun serverKey(candidate: AutoModeCandidate): String =
        serverKey(candidate.ip, candidate.hostname)

    /** The one place the `ip|hostname` identity string is formatted. */
    private fun serverKey(ip: String?, hostname: String?): String = "$ip|${hostname ?: ""}"

    /**
     * FIX-3 test seam: the wedge count this controller's ledger holds for [candidate].
     * Nothing in main source calls it.
     */
    @androidx.annotation.VisibleForTesting
    internal fun wedgeCountFor(candidate: AutoModeCandidate): Int =
        wedgeTracker.countFor(wedgeKey(candidate))

    /**
     * FIX-2: the single funnel for Auto Mode's **terminal give-up** caused by this
     * application's own unresolved native operation.
     *
     * ## FIX-2 — release our own wedged native operation
     *
     * Device evidence (V3): Auto Mode gave up while the wedged attempt was still holding the
     * process-wide native slot, and left the service's `connectionJob` set. Nothing was ever
     * asked to tear down, so the TEARDOWN soft-bound — verified working on device
     * (`[REGISTRY] FORCED_RELEASE_AFTER_TIMEOUT: attempt=2 held for 106041 ms`) — had no
     * TEARDOWN holder to reclaim. Issuing the teardown here is what moves the slot into
     * [NativeLifetimeRegistry.Phase.TEARDOWN] with an expired deadline, which is exactly the
     * state that bound exists to recover.
     *
     * Ordering mirrors the Stage 2F-F.28 Fix E contract already used by [stop] and
     * [disconnectNow]: request the teardown first, publish the terminal state second. The
     * request is fire-and-forget, so it cannot delay the terminal state.
     *
     * ## FIX-3 — remember which server did this (superseded by AUTOCONNECT-AUDIT D-5)
     *
     * This used to record a wedge against [server]. That was wrong and contradicted the
     * message logged one branch above it: the give-up happens because *our own* previous
     * native operation never resolved, so the server was never contacted and its health is
     * unknown. Two such give-ups were enough to exclude a perfectly good server from the
     * session for the rest of the process.
     *
     * The ledger is now written where a server is genuinely implicated — an attempt that
     * reached it and produced no tunnel within the budget ([recordWedge]) — and a give-up
     * caused by our own unresolved operation records nothing.
     *
     * ## v1.0.34 — the D-5 rule is narrowed, not reverted
     *
     * Device evidence (DEVICE_VERIFY_AUTOCONNECT_FIX_3_REPORT.md §7.4) showed that D-5 was
     * right about *why* it was written and wrong as an absolute: the NATIVE_BUSY terminal
     * can be caused by the selected endpoint itself (TCP handshake fine, VPNCONNECT
     * watermark rejected, RUDP fallback handshake unbounded), and because that terminal
     * ends the run on the FIRST refusal, the per-run counter can never reach
     * [NATIVE_BUSY_WEDGE_THRESHOLD]. The endpoint was therefore re-selected in 5/5 runs.
     *
     * So the caller at the NATIVE_BUSY terminal now records the wedge BEFORE invoking this
     * function. This function itself still records nothing: it is also reached from the
     * post-attempt site, where the "our own operation, server never contacted" reasoning
     * still holds exactly as D-5 described.
     */
    private fun giveUpWithUnresolvedNativeOperation() {
        adapter.log(
            "[AUTO] Requesting recovery teardown so the unresolved native operation can be " +
                    "released and a later attempt admitted",
        )
        adapter.requestRecoveryTeardown()
    }

    /**
     * FIX-3 (rewritten by AUTOCONNECT-AUDIT D-5; extended by v1.0.32 BUG-B): record a
     * wedge against [server] and, at [WedgeTracker.EXCLUSION_THRESHOLD], exclude it from
     * the rest of the session.
     *
     * Called for an attempt that actually reached the server and did not come up
     * ([AttemptOutcome.TIMED_OUT]), and — since v1.0.32 — for a server that refused
     * [NATIVE_BUSY_WEDGE_THRESHOLD] times in the same run. A single refusal
     * (`NATIVE_BUSY`, the server was never contacted) and a fast native failure
     * (`NATIVE_ERROR`, the endpoint answered but is not usable) remain deliberately
     * not wedges: the first says nothing about the server on its own, and the second is
     * already cheap to recover from.
     *
     * v1.0.34 adds a third caller: the NATIVE_BUSY **terminal** (the slot wait expired
     * without a release). The per-run counter described above cannot fire there, because
     * that terminal ends the run on its first refusal; the process-scoped ledger is the
     * only place the strike can survive. See DEVICE_VERIFY_AUTOCONNECT_FIX_3_REPORT.md
     * §7.4 and the call site for the full rationale.
     */
    private fun recordWedge(server: AutoModeCandidate, key: String) {
        val wedges = wedgeTracker.record(key)
        if (wedges >= WedgeTracker.EXCLUSION_THRESHOLD) {
            adapter.log(
                "[AUTO] Excluding ${server.hostname ?: server.ip} from this session " +
                        "after $wedges wedges.",
            )
        }
    }
    /**
     * Â§8: Speed DESC first, ping/sessions/score as tie-breakers; Â§23 zero-speed demoted.
     *
     * Stage 2F-F.11 (D3): `ping == 0` is UNKNOWN, not "the fastest". Sorting it by raw
     * value first would let every unknown-ping candidate outrank every candidate that
     * actually reported a measurement, purely because absence is cheaper than a
     * positive number. The known/unknown split is therefore its own key, evaluated
     * before the raw ping value, so a measured ping always wins and unknown ping
     * sorts last. Among unknowns the raw `ping` key still applies (all zero in
     * practice), so the tie-breakers below decide deterministically.
     *
     * Stage 2F-F.11 (D5): `sources` is excluded from production ranking. It is not a
     * quality signal (the controller hardcodes it to 1 from an adapter payload that
     * carries no real value), so ranking on it only added noise that could not change
     * any decision. It remains a field on the candidate for diagnostics.
     */
    private val byQuality =
        compareByDescending<AutoModeCandidate> { it.speed > 0 }
            .thenByDescending { it.speed }
            .thenByDescending<AutoModeCandidate> { it.ping > 0 }
            .thenBy { it.ping }
            .thenBy { it.sessions }
            .thenByDescending { it.score }

    /** Â§7 capability rules. */
    private fun AutoModeProtocol.supports(c: AutoModeCandidate): Boolean = when (this) {
        AutoModeProtocol.SOFTETHER_TCP -> c.seTcpPort > 0
        // Stage 2F-F.28AL-AUTOCONNECT-BUG-FIX (BUG 1): a server with
        // SETcpPort = 0 ("UDP-only") must NOT be selected for SOFTETHER_UDP.
        //
        // The R-UDP/NAT-T transport is broken for SETcpPort = 0 servers: the
        // UDP path still needs a TCP control channel, so registration fails and
        // the server is unreachable (RUDP_IMPLEMENTATION_PLAN.md — 0% success
        // across 30+ UDP-only servers; SERVER_SOURCE_ARCHITECTURE.md §3.5
        // mandates "do not surface SoftEther-UDP as a viable protocol" when
        // seTcpPort = 0). Trusting seUdpSupported alone therefore dialled a
        // transport that could never succeed.
        //
        // This does not make the server unusable: it remains selectable via
        // OPENVPN_UDP when it carries a valid OpenVPN config, and its UDP
        // capability data is untouched. See PROJECT_SPECIFICATION.md §6.7
        // R4/R6.
        AutoModeProtocol.SOFTETHER_UDP ->
            (c.seUdpSupported || c.seUdpPort > 0) && c.seTcpPort > 0
        // v1.0.53 (§1.6.45) TASK A: a port is not a transport. OpenVPN needs
        // the `.ovpn` profile bytes, and a server whose config was stripped
        // (the emergency publisher drops configs to stay under the 1 MiB body
        // cap — the great majority of published rows: 166 of 229, 72.5%, in the
        // snapshot this was audited against) advertises a port it cannot dial.
        // The adapter then had nothing to build a profile from and returned
        // silently, so the attempt burned a full per-attempt timeout and was
        // recorded as TIMED_OUT — a dead-server signal for a data problem.
        //
        // This mirrors the rule the manual path has always used
        // (`DetailActivity.protocolOptions`: `openVpnConfigData != null &&
        // tcpPort > 0`). Port facts stay in the record; only usability is
        // gated. A `do_openvpn` link is NOT a config: nothing downloads it.
        AutoModeProtocol.OPENVPN_TCP -> c.openVpnConfigAvailable && c.openVpnTcpPort > 0
        AutoModeProtocol.OPENVPN_UDP -> c.openVpnConfigAvailable && c.openVpnUdpPort > 0
        AutoModeProtocol.L2TP_IPSEC -> c.l2tpSupported
        // Stage 2F-F.2 (Q-6): capability alone is not enough. A server that
        // confirms SSTP but publishes no port, and carries no DEFAULT
        // provenance to fall back on, has nothing to dial — sstpConnectPort is
        // 0 exactly in that state, because it is resolved from the connection's
        // existing provenance rule rather than re-derived here.
        //
        // A DEFAULT-port server is still supported: 443 is a real, dialable
        // port and is not the same claim as "the port is unknown".
        AutoModeProtocol.MS_SSTP -> c.sstpSupported && c.sstpConnectPort > 0
    }
}

/**
 * FIX-3: a process-scoped ledger of servers whose attempt left this application's own
 * native operation unresolved — a "wedge".
 *
 * ## The defect this exists to close
 *
 * Device evidence (V4) showed Auto Mode re-dialling the same server
 * (`vpn333205800`) on every run and wedging the native slot on every run. The existing
 * guards bound one *run*: the per-server retry budget and the NATIVE_BUSY retry count both
 * stop the run. Nothing carried that knowledge into the *next* run, so a fresh press
 * re-entered the same trap. With FIX-2 in place the slot is reclaimed, but the *server* is
 * still re-selected — Auto Mode ranks by quality, and the wedging server is often the
 * best-ranked one — so the user simply wedges again on the next press.
 *
 * ## Scope and lifetime
 *
 * Process-scoped by design: [process] is the instance production uses, so an exclusion
 * survives every run within the session. It is deliberately **not** persisted — a restart
 * is a clean slate, and a server that wedged before a network change deserves a fresh
 * chance.
 *
 * ## What a wedge is, and what it is not
 *
 * A wedge is recorded only on Auto Mode's **terminal give-up** for a server: the run
 * stopped with [AutoModeController.ERROR_NATIVE_UNRESOLVED] because this application's own
 * native operation never resolved. It is **not** recorded for a TIMED_OUT rotation — the
 * ordinary "the server did not come up" outcome, which already has its own recovery
 * (rotate to the next candidate) and whose health genuinely is in question. It is cleared
 * by a **verified tunnel**, because a tunnel proves the server does not wedge.
 *
 * ## Known limitation (stated, not hidden)
 *
 * The first NATIVE_BUSY of a run can be caused by a leftover operation belonging to an
 * *earlier* server, so the first wedge can in principle be attributed to the wrong
 * candidate. The ledger is therefore deliberately conservative — two strikes,
 * session-scoped, cleared on success — rather than a permanent ban.
 *
 * Declared at file scope rather than nested in [AutoModeController] because it is a
 * *collaborator* the controller is handed, not part of the controller's own contract: the
 * controller takes it as a constructor parameter, production passes [process], and a test
 * passes its own instance.
 *
 * Public, not `internal`, on purpose: it appears in [AutoModeController]'s constructor
 * signature, and every other type in that signature (`AutoModeState`, `TunnelLivenessProbe`,
 * `ReachabilityGate`, …) is public. This is an application module, so there is no ABI to
 * protect.
 */
class WedgeTracker {
    private val wedges =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    /** Record one wedge for [key]; returns the new total. */
    fun record(key: String): Int = wedges
        .computeIfAbsent(key) { java.util.concurrent.atomic.AtomicInteger(0) }
        .incrementAndGet()

    /** How many wedges [key] has accumulated (0 when it has never wedged). */
    fun countFor(key: String): Int = wedges[key]?.get() ?: 0

    /** True once [key] has reached [EXCLUSION_THRESHOLD] wedges. */
    fun isExcluded(key: String): Boolean = countFor(key) >= EXCLUSION_THRESHOLD

    /** Forget [key]'s record — a verified tunnel proves it does not wedge. */
    fun clear(key: String) {
        wedges.remove(key)
    }

    /** Drop every record. Test seam only; production never calls this. */
    @androidx.annotation.VisibleForTesting
    fun reset() {
        wedges.clear()
    }

    companion object {
        /**
         * FIX-3: wedges before a server is excluded for the rest of the process.
         *
         * Two, not one: a single wedge can be caused by a leftover operation from a
         * previous server (see the class note above), so one strike is not evidence about
         * *this* server. Two wedges on the same server is.
         */
        const val EXCLUSION_THRESHOLD = 2

        /**
         * The process-wide ledger production uses.
         *
         * "Excluded for this session" has to outlive one run — that is the whole point —
         * so the default is shared, and a test injects its own instance to stay isolated.
         */
        val process: WedgeTracker = WedgeTracker()
    }
}
