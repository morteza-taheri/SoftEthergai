# F.28AL-AUTOCONNECT-R8-DESIGN — Design Review for "Auto-Reconnect in Auto Mode"

**Phase:** F.28AL-AUTOCONNECT-R8-DESIGN
**Mode:** DESIGN ONLY — READ-ONLY
**Date:** 2026-10-12
**Source of truth:** `PROJECT_SPECIFICATION.md` §6.7 (R8, line 609) and §11.13 (line 1149)
**Deliverable:** design specification, open questions, and a go/no-go recommendation. No implementation.

---

## 0. Scope and Method

This report answers the R8 design questions (S1–S7 source inspection, D1–D10 design decisions)
without modifying a single file. Every claim is tagged with an evidence class:

- **[SRC]** — SOURCE CODE EVIDENCE: read directly from the repository, with `file:line`.
- **[SPEC]** — SPECIFICATION EVIDENCE: `PROJECT_SPECIFICATION.md` text, with line numbers.
- **[INF]** — INFERENCE: reasoned conclusion from [SRC]/[SPEC], not stated verbatim anywhere.
- **[UNK]** — UNKNOWN: cannot be resolved from source or spec; requires user decision or a code change to determine.

The three R8 spec anchors:
- **[SPEC]** §6.7 R8 (`PROJECT_SPECIFICATION.md:609`): "Automatic Reconnection in Auto Mode (New Setting)".
- **[SPEC]** §11.13.1 (`:1155`): final English name `Auto-Reconnect in Auto Mode`; Persian «اتصال همیشکی اتوکانکت». CONFIRMED.
- **[SPEC]** §11.13.2 (`:1162`): final default **ENABLED (فعال)**. CONFIRMED, user-overridden from DISABLED.
- **[SPEC]** §11.13.3–11.13.5 (`:1169`–`:1184`): enabled behaviour, disabled behaviour, testable contract.

---

## 1. Executive Verdict

**DESIGN PARTIAL — SOME DECISIONS PENDING USER INPUT.**

The *architecture* is unambiguous and fully resolvable from existing source: a reconnect reuses the
existing `AutoModeController.runAutoMode()` run loop by re-invoking `start()` with an
`overrideServers` list seeded at the dropped server, exactly as the already-shipped "Try next server
on a Connected server" path does (`AutoModeController.kt:415-441`). The disconnect source-of-truth
is fully mapped (`TunnelStateWatcher.onTunnelLost` → `AutoModeState.Disconnected` at
`AutoModeController.kt:494/514/535`), and the "was this AutoMode-connected" state already exists as
`AutoModeState.Connected` plus `AutoModeRun.lastSuccess`.

However **six decisions genuinely require the user's input** and cannot be honestly guessed from the
spec, because the spec does not fix a value and the code offers no precedent:

1. **Rate limiting / loop protection (OQ-1).** The spec's §11.13.3 says reconnection "respects the
   same stop conditions as a fresh AutoConnect run (R7)". R7 has *no* frequency limit. An
   enabled setting + a flapping server = unbounded reconnect churn. R8 needs an explicit
   N-reconnects-in-M-minutes guard, but **the spec does not state N or M**. This is the single most
   consequential open decision.
2. **NATIVE_BUSY retry budget scope (OQ-2).** Whether a reconnect run gets a *fresh* per-server
   budget or *shares* the original run's budget is not stated. Both are defensible; the
   implementations differ.
3. **Reconnect delay before the first attempt (OQ-3).** Spec implies "automatically attempts" with
   no delay. A zero-delay immediate redial against a tunnel that just died can hit the native
   slot while it is still unwinding (NATIVE_BUSY). Whether R8 waits (and how long) is unstated.
4. **Persistence of the "AutoMode-connected" flag across process death (OQ-4).** §11.13 never
   mentions process death; the observer lifecycle question is real.
5. **UI exposure of an active reconnect (OQ-5).** Whether the Auto Mode screen should show a
   distinct "Reconnecting…" state, or reuse `Connecting`, is not specified in §11.13.
6. **The exact new string resource keys and Persian translation (OQ-6).** Only the *label* is
   CONFIRMED; the resource key names are a convention choice, and the Persian string is not in the
   repo.

Everything else (detection rules, tracking field, flow, preference key, cancellation, testability,
failure modes, observability content) is designable now and is specified below.

---

## 2. State Tracking (S1)

### 2.1 The Auto Mode state machine

**[SRC]** `AutoModeModels.kt:151-174` — sealed class `AutoModeState`:
- `object Disconnected`
- `data class Connecting(hostname, ip, protocol, speed, ping, attempt, total)`
- `data class Connected(hostname, ip, protocol, speed, ping)`
- `data class Error(message: String)`

**[SRC]** The single private backing flow: `AutoModeController.kt:344`
`private val _state = MutableStateFlow<AutoModeState>(AutoModeState.Disconnected)`.
Public read: `AutoModeController.kt:345` `val state: StateFlow<AutoModeState> = _state`.

**[SRC]** Every transition is funnelled through one function: `AutoModeController.kt:357-360`
```kotlin
private fun setState(state: AutoModeState) {
    _state.value = state
    onStateChange?.invoke(state)
}
```
This is the **only** writer of `_state` (verified: all 20+ `setState(...)` call sites go through it).
`onStateChange` (`:352`) is the UI/notification mirror hook wired in `AutoModeEngine.kt:117-128`.

### 2.2 Transition INTO Connected

**[SRC]** `AutoModeController.kt:1256-1264` — inside `runAutoMode()`, on the success path, after
`connectedProtocol != null`:
```kotlin
setState(
    AutoModeState.Connected(hostname = server.hostname, ip = server.ip,
        protocol = connectedProtocol, speed = server.speed, ping = server.ping))
startConnectedWatcher(connectedProtocol)   // :1265
return                                     // :1266
```
Preceded at `:1254` by `onSuccess(server, connectedProtocol)` — the engine's success callback sets
`DataUtil.AUTO_LAST_SUCCESS_HOST` / `AUTO_LAST_SUCCESS_PROTOCOL` and `AutoModeRun.lastSuccess`
(`AutoModeEngine.kt:69-73`).

**Report:** transition to `Connected` published at **`AutoModeController.kt:1256`**; the run loop
then returns and the job ends (the run is *done* while Connected).

### 2.3 Transitions OUT of Connected

There are **three** code paths that publish `Disconnected` while the state was `Connected`, all
inside `startConnectedWatcher` (`AutoModeController.kt:483-541`) plus the explicit user paths:

| # | Site | Trigger | Fire condition |
|---|------|---------|----------------|
| A | `:487-496` | `TunnelStateWatcher.onTunnelLost` callback | Any stack reports tunnel lost |
| B | `:504-540` (poll `:508-516`) | `GlobalVpnTracker.isConnected == false` (1 s poll) | Tracker confirms down |
| C | `:519-538` (poll `:525-536`) | Active liveness watchdog `wd.onSample(now)` fires | No proof-of-life for `DEFAULT_SILENCE_TIMEOUT_MS` |
| D | `:575-605` `stop()` | User stops / button re-press | `job?.cancel()` then `setState(Disconnected)` at `:604` |
| E | `:660-681` `disconnectNow()` | User presses button while Connected (`onButtonPressedWithoutGuard` `:655`) | `setState(Disconnected)` at `:680` |
| F | `:415-441` `skipToNextServer()` (Connected branch) | User presses "Try next server" | `job?.cancel()` `:422`, `setState` via `start()`/`Error` |

**Key finding [SRC]:** paths A, B and C (`:494`, `:514`, `:535`) are the **unexpected** disconnect
paths. Paths D, E, F are **user-initiated** and each explicitly calls `TunnelStateWatcher.onTunnelLost = null`
*before* tearing down (`:578`, `:663`, `:420`), so the passive watcher cannot re-fire during a
deliberate teardown. This ordering is the natural hook for R8's "was this unexpected?" test
(see D1).

---

## 3. Disconnect Detection (S2)

### 3.1 All disconnect signals in the codebase

**[SRC]** `TunnelStateWatcher.kt` is the single fan-in point for every stack:

| Signal | Source | Code |
|--------|--------|------|
| SoftEther `STATE_DISCONNECTED` / `STATE_ERROR` | `SoftEtherVpnService.StateListener` | `TunnelStateWatcher.kt:313-324` → `onTunnelLost?.invoke()` at `:322` |
| OpenVPN `LEVEL_NOTCONNECTED` / `LEVEL_AUTH_FAILED` | `VpnStatus.StateListener` | `TunnelStateWatcher.kt:330-366` → `onTunnelLost?.invoke()` at `:343` |
| SSTP `ROOT_STATE == false` | SharedPreferences listener | `TunnelStateWatcher.kt:35-45` → `onTunnelLost?.invoke()` at `:42` |
| System VPN transport lost | `ConnectivityManager.NetworkCallback.onLost` | `GlobalVpnTracker.kt:126-147` → `TunnelStateWatcher.onTunnelLost?.invoke()` at `:136` |
| Passive poll | 1 s loop in the connected watcher | `AutoModeController.kt:508` `if (!GlobalVpnTracker.isConnected)` |
| Active liveness | `TunnelLivenessProbe` + `TunnelLivenessWatchdog` | `AutoModeController.kt:521-537` |
| Teardown completion | `onSoftEtherTeardownComplete` | `TunnelStateWatcher.kt:288-290` (waiter only, **not** a loss signal) |

**[SRC]** `AutoModeController` installs the callback **only while Connected**:
`AutoModeController.kt:487` `TunnelStateWatcher.onTunnelLost = { ... }` and clears it on every exit
(`:420`, `:492`, `:512`, `:533`, `:578`, `:663`).

### 3.2 Path-per-scenario table

| Scenario | Which signal fires | Where | User-initiated? |
|----------|-------------------|-------|-----------------|
| **User presses Disconnect** (Connected) | None of A/B/C. `disconnectNow()` runs: `onTunnelLost = null` `:663`, `job?.cancel()` `:669`, `adapter.requestDisconnect()` `:674`, `setState(Disconnected)` `:680` | D/E path | **YES** |
| **User presses Stop AutoConnect** (Connecting) | None. `stop()`: `onTunnelLost = null` `:578`, `job?.cancel()` `:587`, `requestDisconnect()` `:603`, `setState(Disconnected)` `:604` | D path | **YES** |
| **User presses Try next server** (Connected) | None. `skipToNextServer()` Connected branch: `connectedWatcher?.cancel()` `:418`, `onTunnelLost = null` `:420`, `job?.cancel()` `:422`, then `adapter.disconnect()` `:425` and `start()` `:437` | F path | **YES** |
| **Server kicks / revocation while idle-connected** | SoftEther `STATE_DISCONNECTED` → `onTunnelLost` `:322` → callback `:487` → path A `:494`. OpenVPN/SSTP equivalent → `:343`/`:42` | A path | **NO — UNEXPECTED** |
| **Network lost** (Wi-Fi/cellular down) | `GlobalVpnTracker.onLost` `:136` invokes `onTunnelLost` → A; **and** the 1 s poll `:508` → B; **and** possibly the liveness watchdog → C | A+B+C | **NO — UNEXPECTED** |
| **Quiet tunnel death (no signal)** | Active liveness only: probe stops producing proof → watchdog `:525` → C | C path | **NO — UNEXPECTED** |
| **App process killed** | No Kotlin signal can fire. Process death is discovered by Android, not by this code | none | **NO — but not recoverable in-process** |

### 3.3 Classification

**[SRC]+[INF]** The classification U (unexpected) vs M (manual) maps 1:1 to *which code path* reached
`setState(Disconnected)`:

- **M (manual / non-trigger for R8):** paths D, E, F — the paths that run `onTunnelLost = null`
  *before* the teardown, because a deliberate teardown must not look like a loss.
- **U (unexpected / trigger for R8):** paths A, B, C — the paths reached *from* the watcher or the
  poll while `onTunnelLost` was still armed.
- **N (neither):** process death (no in-process signal).

**This is the decisive design insight:** R8 does not need a new "was it unexpected?" oracle. The
existing code *already* distinguishes the two by construction — the manual paths disarm the watcher
first. R8 only needs to act on the paths that fire *while armed* (D1 below).

---

## 4. AutoModeController Run Loop (S3)

### 4.1 Entry point

**[SRC]** `AutoModeController.kt:892` `private suspend fun runAutoMode()`.
Launched only by `start()`:
```kotlin
// AutoModeController.kt:566-572
fun start() {
    if (isRunning) { adapter.log("[AUTO] start ignored: already running"); return }
    job = scope.launch { runAutoMode() }
}
```

**[SRC]** Public facade: `AutoModeEngine.kt:38` `fun start() = ensure().start()`;
`AutoModeEngine.kt:42` `fun onButtonPressed() = ensure().onButtonPressed()`.
ViewModel bridge: `AutoModeViewModel.kt:31` `fun onButtonPressed() = AutoModeEngine.onButtonPressed()`;
`:34` `fun tryNextServer() = AutoModeEngine.skipToNextServer()`.

### 4.2 Single-job guard and re-invocability

**[SRC]** `AutoModeController.kt:362-367`
```kotlin
var job: Job? = null
    private set
val isRunning: Boolean get() = job?.isActive == true
```
- `start()` **refuses** while `isRunning` (`:567-570`). So a fresh run cannot be started from
  inside a live run.
- **Re-invocability while winding down [SRC]:** every terminal path sets `job = null` explicitly
  (`:903`, `:1081`, `:1166`, `:1211`, `:1274`) *before* returning, so `isRunning` becomes
  **false** as soon as the run coroutine returns. `job?.cancel()` in `stop()`/`disconnectNow()`
  leaves `job = null` (`:588`, `:670`).
- **The critical case (the R8 hook):** on the success path the loop **returns at `:1266`** after
  `setState(Connected)` — so while Connected, **`job` is already null and `isRunning` is false**.
  The only thing keeping "Auto Mode owns a tunnel" true is the state object plus the
  `connectedWatcher` job (`:504`).

**[INF]** Therefore a reconnect is architecturally *identical* to the existing Connected-skip:
`skipToNextServer()`'s Connected branch already does exactly "cancel the watcher, disconnect, seed
`overrideServers` with the remaining candidates, `start()` a fresh run" (`:415-441`). R8 = that
pattern, but triggered by a *lost* tunnel instead of a *button*.

### 4.3 Stop conditions of the current loop

**[SRC]** `runAutoMode()` ends at exactly one of:
1. `ERROR_NO_ENABLED_PROTOCOL` `:902` (empty priority list).
2. `emptyServerListMessage()` `:926` (empty candidate list).
3. `ERROR_NO_SERVER` `:1011` (empty after reachability gate) or `:1273` (every candidate failed).
4. `ERROR_VPN_PERMISSION` `:1080`/`:1182`.
5. `ERROR_NATIVE_UNRESOLVED` `:1165` (budget) / `:1210` (count).
6. **Success** `:1266` (`Connected`).
7. Cancellation — every loop iteration begins `if (!currentCoroutineContext().isActive) return`
   (`:1019`, `:1055`, `:1090`, `:1091`, `:1141`, `:1175`, `:1191`).

**[SRC]** This matches §6.7 R7 [SPEC]: end-of-list → `ERROR_NO_SERVER`; user re-press → cancel.

---

## 5. AutoModeEngine Lifecycle (S4)

**[SRC]** `AutoModeEngine` is a Kotlin `object` (process singleton): `AutoModeEngine.kt:20`.
- Holds `@Volatile private var controller: AutoModeController? = null` (`:22-23`) created lazily in
  `ensure()` under `synchronized(this)` double-checked locking (`:52-131`).
- Also exposes `stateFlow` (`:32`) that exists before creation, so read-only consumers observe
  without constructing the engine.
- The controller's `scope` is `CoroutineScope(SupervisorJob() + Dispatchers.Default)`
  (`:61`) — a **plain app-scoped** coroutine scope, **not** tied to any Activity/Service lifecycle.

**[SRC]** `TunnelStateWatcher.attach(...)` (`:58`) and `AutoModeModuleLogTap.attach(...)` (`:59`) are
called once inside `ensure()`; `TunnelStateWatcher` is itself an `object` (`TunnelStateWatcher.kt:22`).

**Survives service restarts? [INF]** Yes — the controller lives in the application process, not in a
VPN service. A VPN-service restart does not recreate `AutoModeController`; it only produces state
callbacks into the already-attached watcher.

**Survives process death? [SRC]+[INF]** **No.** `AutoModeEngine` is an in-memory singleton and
`AutoModeController._state` is an in-memory `MutableStateFlow` (`:344`). After process death both are
re-created with `AutoModeState.Disconnected` and `AutoModeRun.lastSuccess == null`
(`AutoModeEngine.kt:217`). There is **no** persisted "Auto Mode owns a tunnel" flag today.
`AutoModeRun.lastSuccess` is `@Volatile` in-memory only (`AutoModeEngine.kt:216-219`); the only
persisted artefacts are `AUTO_LAST_SUCCESS_HOST`/`_PROTOCOL` strings
(`AutoModeEngine.kt:70-71`), which are a *record of the last success*, not a live ownership flag.

**Interaction with the controller [SRC]:** the engine owns creation, wires `onSuccess`
(`:69-73`), the reachability filter (`:78-83`), the liveness probe factory (`:104`), the busy check
`isTunnelBusy = { GlobalVpnTracker.isBusy }` (`:108`), the skip signal
`created.setSkipSignal { adapter.skipCurrent() }` (`:114`), and the `onStateChange` notifier mirror
(`:117-128`). **This is the natural place to inject R8's preference reader and its "should we
reconnect?" policy**, because the engine is where all cross-cutting wiring already lives.

---

## 6. Settings Storage and UI (S5)

### 6.1 Where SETTING_* keys live

**[SRC]** `app/src/main/java/vn/unlimit/vpngate/utils/DataUtil.kt`:
- Companion constants: `SETTING_AUTO_PROTOCOL_PRIORITY` `:519`,
  `SETTING_AUTO_MODE_TIMEOUT_SECONDS` `:520`, `AUTO_LAST_SUCCESS_HOST` `:555`,
  `AUTO_LAST_SUCCESS_PROTOCOL` `:556`.
- Backing store: `SharedPreferences` file `vpn_setting_data_pro.xml` (verified on device in prior
  phases).

### 6.2 DataUtil accessor patterns

**[SRC] Int accessor pattern** (`:332-337`):
```kotlin
fun getAutoModeTimeoutSeconds(): Int =
    getIntSetting(SETTING_AUTO_MODE_TIMEOUT_SECONDS, 12).coerceIn(5, 60)
fun setAutoModeTimeoutSeconds(seconds: Int) {
    setIntSetting(SETTING_AUTO_MODE_TIMEOUT_SECONDS, seconds.coerceIn(5, 60))
}
```
**[SRC] Boolean helpers already exist** (`:296`, `:300`):
```kotlin
fun getBooleanSetting(key: String?, defVal: Boolean): Boolean { ... }
fun setBooleanSetting(key: String?, value: Boolean) { ... }
```
**[SRC] Existing boolean-setting usages** (the exact precedent to follow): `SettingsScreen.kt:263`
`dataUtil.setBooleanSetting(DataUtil.SETTING_NOTIFY_SPEED, it)`; `:274`
`DataUtil.INCLUDE_UDP_SERVER`; `:286` `USE_DOMAIN_TO_CONNECT`.

### 6.3 SettingsScreen Auto Mode section

**[SRC]** `SettingsScreen.kt:485-523` — the `SettingsSection(title = stringResource(R.string.auto_mode))`
block. Current rows, in order:
1. `SettingValueRow` — Auto Connect Protocol Priority (`:490-497`).
2. `SettingDivider()` `:498`.
3. `SettingValueRow` — Auto Mode Connection Timeout (`:499-504`), value `"$autoTimeout s"`.
4. `SettingDivider()` `:505`.
5. `SettingValueRow` — SoftEther Max Connections (`:506-514`).
6. `SettingDivider()` `:515`.
7. `SettingActionRow` — Excluded apps (`:516-521`).

**[SRC] The switch component already exists**: `SettingsComponents.kt:82-139` `SettingSwitchRow(title,
subtitle, icon, checked, onChecked, modifier)`. Live usages at `SettingsScreen.kt:255, 267, 279, 291,
331, 584`.

**Pattern to add a new boolean toggle [SRC]:** copy `SettingsScreen.kt:267-277`:
```kotlin
SettingDivider()
SettingSwitchRow(
    title = stringResource(R.string.<new_label_key>),
    subtitle = stringResource(R.string.<new_hint_key>),
    icon = Icons.Outlined.<Icon>,
    checked = <localState>,
    onChecked = { <localState> = it; dataUtil.setBooleanSetting(DataUtil.<NEW_KEY>, it) },
)
```

---

## 7. Existing Reconnect Logic (S6)

**[SRC]** `SoftEtherClient/src/main/java/vn/unlimit/softether/controller/ConnectionController.kt`:
```kotlin
:74   private const val MAX_RECONNECT_ATTEMPTS = 2
:75   private const val RECONNECT_DELAY_MS = 1000L
:261  while (reconnectAttempts.get() < MAX_RECONNECT_ATTEMPTS) { performConnect() ... }
:280  if (reconnectAttempts.incrementAndGet() < MAX_RECONNECT_ATTEMPTS) { ... delay(RECONNECT_DELAY_MS) }
:734  delay(RECONNECT_DELAY_MS)
:1108 if (reconnectAttempts.incrementAndGet() >= MAX_RECONNECT_ATTEMPTS) { ... }
:1118 Log.w(TAG, "Attempting automatic reconnection (${reconnectAttempts.get()}/$MAX_RECONNECT_ATTEMPTS)")
:1160 delay(RECONNECT_DELAY_MS)
```

**[SRC] How consumed:** `reconnectAttempts` is an `AtomicInteger` reset at `:258` at the start of
`connect()`, and the loop at `:261-292` retries `performConnect()` up to twice *inside one connect
call*, with a 1 s delay between. `:1108-1160` is the second consumer — the mid-session reconnect
after an established tunnel drops at the native layer.

**Confirmation that R8 is DIFFERENT [SPEC]** (`PROJECT_SPECIFICATION.md:1153`):
> "Distinct from `ConnectionController`'s per-attempt `MAX_RECONNECT_ATTEMPTS = 2` /
> `RECONNECT_DELAY_MS = 1000L` (§17), which are native retries *within a single connect attempt*,
> not Auto Mode-level server rotation after an unexpected drop."

**[INF]** Therefore R8 must **not** change `MAX_RECONNECT_ATTEMPTS` / `RECONNECT_DELAY_MS` and
must **not** reuse them. They operate at a strictly lower layer (one server × one protocol, inside
the native connect), whereas R8 operates at the Auto Mode layer (server rotation + fresh run). The
two reconnect mechanisms are orthogonal and will legitimately stack: a reconnect attempt may itself
trigger an internal native retry (2× with 1 s delay) before Auto Mode concludes the server failed
and rotates (R8). This stacking is intentional and matches the spec's "distinct" clause; it should
be **documented, not "fixed."**

---

## 8. F.28AL-Y Retry Budget (S7)

**[SRC]** `AutoModeController.kt:299-327` constants:
- `NATIVE_BUSY_MAX_RETRIES = 2` (`:306`) — retries per server+protocol.
- `NATIVE_BUSY_RETRY_DELAY_MS = 2_000L` (`:297`).
- `PER_SERVER_RETRY_DELAY_BUDGET_MS = 10_000L` (`:327`) — cumulative across *all* protocols on one
  server.

**[SRC] How consumed in the loop** (`:1138-1214`): on `AttemptOutcome.NATIVE_BUSY`:
1. `:1157-1168` — if the accumulated nominal delay would exceed the budget → log
   "[AUTO] Per-server retry budget exceeded (10s); stopping without blaming this server" →
   `setState(Error(ERROR_NATIVE_UNRESOLVED))` → `job = null` → **return** (run terminal).
2. `:1169-1174` — else accrue `NATIVE_BUSY_RETRY_DELAY_MS`, log the retry, `delay(...)`, re-arm and
   re-`connect()` the same server+protocol.
3. `:1051` — `var accumulatedRetryDelayMs = 0L` is **reset per server** ("next to
   rememberRemaining"), i.e. each candidate begins with a fresh 10 s budget.

**[SRC] Interaction with the run loop:** the budget is *per-server within one run invocation*. It
is a local variable of the `for (server in servers)` body (`:1051`), so it dies when the run loop
returns. A new `start()` (i.e. a fresh run) always re-initialises it.

**[INF] Does R8 need a separate budget?** The budget protects against a *single server* pinning the
run in native-refusal loops. A reconnect run reuses the same loop, so it inherits a fresh 10 s
budget per server *automatically* — **no new constant is strictly required**. The open question is
whether that is *desired*: a reconnect run that immediately re-hits NATIVE_BUSY on the dropped
server would spend up to 10 s before rotating. That is acceptable and arguably correct (the dropped
server may simply be mid-cleanup). Recommend: **reuse the same budget; no R8-specific budget.**
Justification: the budget's purpose (bound native-refusal churn per server) is identical in both a
fresh and a reconnect run, and a second budget would only add a knob with no distinct failure mode.
This remains OQ-2 because §11.13 does not state it.

---

## 9. D1 — Detection Rules for "Unexpected Disconnect"

**Trigger (R8 fires a reconnect) — all of these must hold:**
1. `AutoModeState` **was** `Connected` — i.e. the tunnel was established by an AutoMode run, not by
   a manual connect. Confirmed by: the watcher is installed only on the Connected path
   (`AutoModeController.kt:1265` → `startConnectedWatcher` `:483`).
2. The disconnect arrives on one of the **armed** paths:
   - A: `TunnelStateWatcher.onTunnelLost` callback (`:487-496`), **or**
   - B: `GlobalVpnTracker.isConnected == false` in the 1 s poll (`:508-516`), **or**
   - C: active liveness watchdog silence (`:525-536`).
3. The setting `Auto-Reconnect in Auto Mode` is ENABLED (§11.13.2 default).

**Non-triggers (R8 must NOT fire), with source-signal evidence:**

| Non-trigger | Evidence that it cannot reach R8 |
|-------------|----------------------------------|
| User presses **Disconnect** | `disconnectNow()` sets `TunnelStateWatcher.onTunnelLost = null` at `:663` **before** tearing down; the watcher cannot fire. `setState(Disconnected)` at `:680` is reached directly. **[SRC]** |
| User presses **Stop AutoConnect** | `stop()` sets `onTunnelLost = null` at `:578` before `job?.cancel()` at `:587`. **[SRC]** |
| User presses **Try next server** | Connected branch sets `onTunnelLost = null` at `:420` and cancels the watcher at `:418` before `adapter.disconnect()`. The subsequent `start()` at `:437` is the *intended* forward move, not a reconnect. **[SRC]** |
| **App process killed** | No in-process callback can fire; `AutoModeEngine`/`_state` are in-memory and reset to `Disconnected` on next launch. **[SRC]+[INF]** |

**[INF] Design consequence:** implement D1 by gating on `_state.value is AutoModeState.Connected`
*inside* the three armed paths (A/B/C) — which is *already* what paths A and B do
(`:488`, and implicitly B/C by `_state.value !is Connected → break` at `:507`). The cleanest
implementation is to replace the three inline `setState(Disconnected)` calls with one helper
`onUnexpectedDisconnect()` that (a) sets `Disconnected`, (b) consults the setting, (c) schedules the
reconnect. This keeps the "who disconnected me?" logic in one place and preserves the existing
guarantee that manual paths never reach it.

---

## 10. D2 — Tracking "AutoMode-Connected"

**[SRC] The state already exists, in two places:**
- `AutoModeState.Connected(...)` (`AutoModeModels.kt:165-171`) — the authoritative UI/engine state.
- `AutoModeRun.lastSuccess: AutoModeCandidate?` (`AutoModeEngine.kt:216-219`) — the last successful
  AutoMode candidate, set in the engine's `onSuccess` (`:72`).

**[SRC] Where it is set:** only in the run loop's success path —
`setState(AutoModeState.Connected(...))` at `AutoModeController.kt:1256` and
`onSuccess(server, connectedProtocol)` at `:1254` (which writes `AutoModeRun.lastSuccess`).

**[SRC] Where it is cleared:**
- On any `setState(Disconnected)` (paths A/B/C/D/E) — the `Connected` object is replaced.
- `stop()`/`disconnectNow()`/`skipToNextServer()` all reset `overrideServers = null` (`:581`,
  `:666`, plus `start` clears it at `:920`).
- `AutoModeRun.lastSuccess` is **never** cleared except by process death (`AutoModeEngine.kt:217`
  initialises it to null). **[SRC]**

**[INF] Proposed field name for the explicit flag (D2 requirement):**
`AutoModeController` currently infers "AutoMode-connected" from `_state.value is
AutoModeState.Connected`. That inference is sufficient and requires **no new field** for the
in-process case, because a manual connect never publishes `AutoModeState.Connected` — the manual
path (`ConnectionController` / `MainActivity`) publishes into `GlobalVpnTracker`, not into
`AutoModeController._state`. So:

- **Recommended:** no new persisted field. Track with the existing `AutoModeState.Connected` and
  gate D1 on it. Name (if a named helper is wanted): `private fun isAutoModeConnected() =
  _state.value is AutoModeState.Connected`.
- **If a distinct named field is preferred** (e.g. for a future "was it ours" query outside the
  controller), name it `autoModeOwnsTunnel: Boolean` on the controller, set `true` at `:1256`,
  `false` in every `setState(Disconnected)` path. Not persisted (OQ-4).

**Manual-connect clearing [SRC]:** a manual connect does not touch `AutoModeController._state`. It
does, however, move `GlobalVpnTracker` to CONNECTING/CONNECTED, and `startOrRefuseIfNoServers()`
already refuses to start Auto Mode while `isTunnelBusy()` (`:639`). **[INF]** R8 should apply the
same asymmetry: a reconnect whose redial collides with a manual connect must stand down (see D7).

---

## 11. D3 — Reconnect Flow

**First attempt = same server, same protocol priority (R6).**

**[SRC]** The existing Connected-skip branch (`:415-441`) builds the reconnect candidate list as:
```kotlin
val remaining = remainingCandidates.ifEmpty {
    val all = (overrideServers ?: serverProvider())
        .withActiveRtt(protocolPriorityProvider())
        .sortedForAutoConnect()
    if (currentIp != null) all.filterNot { it.ip == currentIp } else all
}
```
`remainingCandidates` is maintained by `rememberRemaining(servers, current)` (`:1278-1285`) — the
not-yet-attempted tail of the sorted list — updated at `:1044` (before each attempt) and `:1255`
(on success).

**[SRC] Protocol priority** is re-read per attempt via `protocolPriorityProvider()`
(`:898`, `:942`, `:1037`); the loop iterates `for (protocol in supported)` where
`supported = usableProtocols(server, priorities)` (`:1037`).

**[INF] Proposed R8 flow (reuses ~all existing machinery):**
1. On unexpected disconnect, capture the just-dropped server from `AutoModeState.Connected`
   (`hostname`/`ip`) and the protocol it used.
2. **First attempt: the same server.** Seed `overrideServers = listOf(droppedServer) + remaining`
   (or, more idiomatically, `overrideServers = remainingCandidates` prefixed by the dropped server),
   then call `start()`. Because `rememberRemaining` already excludes the dropped server from
   `remainingCandidates`' tail only if it was the current one, the dropped server must be
   re-prepended explicitly. The run loop then re-tries it with the full protocol priority order
   (`:1037`), which satisfies §11.13.3 step 1 ("first to the same server, trying the configured
   protocols in the user's priority order").
3. **On failure, proceed to the next server** — the run loop already does this: it falls through the
   `for (server in servers)` loop (`:1018-1270`) in sort order. No new rotation code is needed.
4. **Sorted list carriage:** carry it via the existing `overrideServers` field (`:545`), which the
   loop consumes once and nulls (`:919-920`). For R8 the list should be rebuilt from
   `remainingCandidates` (already sorted) so the priority/quality order from the original run is
   preserved. If `remainingCandidates` is empty, re-derive via the same
   `withActiveRtt(...).sortedForAutoConnect()` expression the skip branch uses (`:427-430`).

**Reuse vs augment [INF]:** **Reuse.** The run loop is already re-invocable (`job == null` while
Connected — see §4.2), already supports a caller-supplied list (`overrideServers`), and already
rotates servers + protocols with all stop conditions. R8 adds only (a) the trigger (D1) and (b) the
list seeding. No change to `runAutoMode()`'s core is required; the change is confined to the
disconnect-detection sites and a small helper.

---

## 12. D4 — Settings Preference

**[SPEC]** §11.13.1 (`:1155`): name `Auto-Reconnect in Auto Mode`, Persian «اتصال همیشکی اتوکانکت».
**[SPEC]** §11.13.2 (`:1162`): default **ENABLED (فعال)**.

**Proposed preference (follows the existing convention exactly [SRC]):**

| Item | Proposed value | Precedent |
|------|---------------|-----------|
| Constant | `const val SETTING_AUTO_RECONNECT_IN_AUTO_MODE: String = "SETTING_AUTO_RECONNECT_IN_AUTO_MODE"` | `DataUtil.kt:519-520` |
| Accessor | `fun getAutoReconnectInAutoMode(): Boolean = getBooleanSetting(SETTING_AUTO_RECONNECT_IN_AUTO_MODE, true)` | `DataUtil.kt:296-304` helpers |
| Setter | `fun setAutoReconnectInAutoMode(enabled: Boolean) = setBooleanSetting(SETTING_AUTO_RECONNECT_IN_AUTO_MODE, enabled)` | `DataUtil.kt:300` |
| Default | `true` (ENABLED) | §11.13.2 [SPEC] |
| UI component | `SettingSwitchRow(...)` | `SettingsComponents.kt:82` |
| Placement | new row in the Auto Mode section, `SettingsScreen.kt` after the timeout row (`:504`) | `SettingsScreen.kt:485-523` |
| String keys | `setting_auto_reconnect_label` (title), `setting_auto_reconnect_hint` (subtitle) — **proposed**, see OQ-6 | `strings.xml:261-266` pattern |

**Local state [SRC]:** follow the `autoTimeout` pattern — a `remember { mutableStateOf(dataUtil.getAutoReconnectInAutoMode()) }`
near `SettingsScreen.kt:165`, then `checked = <state>` / `onChecked = { <state> = it; dataUtil.setBooleanSetting(...) }`.

**Note on the default-read trick:** `getBooleanSetting(key, true)` returns `true` for a *missing*
key, so a fresh install is ENABLED with no write required — consistent with §11.13.2 and with how
`isFirstRun()` reads `SETTING_LOCALE_ANSWERED` with `false` (`DataUtil.kt:327`).

---

## 13. D5 — Stop Conditions

**[SPEC] Inherited from R7** (§6.7 R7, `:599-607`), which §11.13.3 explicitly re-applies:
- (a) sorted list exhausted → `ERROR_NO_SERVER`.
- (b) user presses AutoConnect again → cancel.
- No other implicit stop (does not stop on a single server failure, on NATIVE_BUSY, or on any short
  timeout).

**R8-specific additions — REQUIRED, and pending user input:**
- **Loop protection (OQ-1).** R7 alone permits an unbounded cycle: reconnect → server drops again
  immediately → reconnect → … The spec's §11.13 does **not** specify a bound. This design
  **recommends** a guard of the form *"at most `N` reconnect attempts within `M` minutes"*, after
  which the app stops with an existing terminal state (recommend reusing `ERROR_NO_SERVER` or adding
  a new `ERROR_RECONNECT_LIMIT`). **N and M are UNKNOWN and must be chosen by the user.**
  Justification for the guard: without it, a server that accepts a connection and is then repeatedly
  killed will spin the radio and drain the battery indefinitely, and §11.13.2's rationale ("a user
  who started Auto Mode wants to be connected") does not justify infinite churn.

**[INF] Explicitly NOT a stop condition:** a single reconnect failure. Per R7 and §11.13.3 the run
continues to the next server.

---

## 14. D6 — Retry Budget Interaction (F.28AL-Y)

See §8 for the mechanism. **Recommendation: reuse the same per-server budget; do not add an
R8-specific budget.**

Reasons [INF]:
1. The budget's purpose — bound native-refusal churn per candidate — is identical in a fresh run
   and a reconnect run.
2. A reconnect run inherits a fresh 10 s budget per server automatically (the counter is a
   per-server local, `:1051`), so no new state is needed.
3. A separate budget would introduce a second knob with no distinct failure mode, violating the
   existing single-mechanism design.
4. NATIVE_BUSY during a reconnect is *expected* (the dropped tunnel's native slot may still be
   unwinding), so the retry-then-rotate behaviour is exactly right.

**Caveat / OQ-2:** this is a *recommendation*, not a spec mandate. If the user prefers that a
reconnect must not spend 10 s on a server that just dropped, an R8-specific *smaller* budget could
be introduced. Recorded as OQ-2.

---

## 15. D7 — Cancellation Semantics

**[SRC] Existing cancellation primitives:**
- `stop()` (`:575-605`) and `disconnectNow()` (`:660-681`) both `job?.cancel()`, set `job = null`,
  and call `adapter.requestDisconnect()` (`:603`, `:674`), then `setState(Disconnected)`.
- Every loop iteration is cancellation-aware (`:1019`, `:1055`, `:1090`, `:1091`, `:1141`, `:1175`,
  `:1191`).
- The NATIVE_BUSY wait is a cancellable `delay(...)` (`:1174`) — the comment at `:1136-1137` states
  this explicitly.

**Required R8 semantics [INF]:**
1. **Manual reconnect during R8 aborts it.** If the user presses AutoConnect (which becomes
   "stop/disconnect") while a reconnect run is in flight, `onButtonPressed()` /
   `onButtonPressedWithoutGuard()` (`:648-657`) routes to `stop()` (Connecting) or `disconnectNow()`
   (Connected) — both cancel the job. The reconnect run *is* a normal run
   (`job = scope.launch { runAutoMode() }`), so it is cancelled by the same call. **No new
   cancellation path is needed.**
2. **Disabling the setting during R8 must prevent re-fire.** The setting is read at the *moment of
   the disconnect decision* (D1). A `getAutoReconnectInAutoMode()` read inside the
   `onUnexpectedDisconnect()` helper guarantees that toggling the switch off before the next drop
   (or even during an active reconnect, before the next drop) is honoured. **Important:** toggling
   the switch OFF does **not** need to abort an *in-flight* reconnect run; §11.13.4 governs
   behaviour *after* a drop, not a running run. Recommend the simple rule: the setting is consulted
   only at the drop decision point.
3. **Propagation:** cancellation already propagates through the coroutine hierarchy
   (`SupervisorJob` scope + cooperative cancellation checks). No `Thread.interrupt` or global flag
   is required.

**Edge case [INF]:** a reconnect run that has already entered the NATIVE_BUSY `delay` will exit at
the next `isActive` check (`:1175`) — clean, bounded by `NATIVE_BUSY_RETRY_DELAY_MS` (2 s).

---

## 16. D8 — Testability

**[SRC] Precedent:** `AutoModeController` is explicitly kept a "pure JVM unit-test target"
(`AutoModeModels.kt:6-9`, `AutoModeController.kt:350`); tests inject a fake `ConnectionAdapter`
(`AutoModeController.kt:170-225` interface with default method bodies so doubles keep compiling).
`AutoModeEngine.ensure(dataUtil, serversProvider)` accepts injected dependencies (`:52`).

**JVM-testable (no device):**
- **D1 detection logic** — via a fake adapter + driving `setState` through a test seam. The
  classification (manual vs unexpected) is pure logic once the disarm-before-teardown invariant is
  in place.
- **D3 flow / list seeding** — `overrideServers` handling and sort order are pure.
- **D4 accessor** — `DataUtil.getAutoReconnectInAutoMode()` default-true is a pure read (though
  `DataUtil` needs a `SharedPreferences`; existing tests already cover `DataUtil` patterns).
- **D5 loop guard** — pure counter logic (mirrors `NATIVE_BUSY_MAX_RETRIES` /
  `PER_SERVER_RETRY_DELAY_BUDGET_MS`, both JVM-tested today).
- **D7 cancellation** — checking `stop()` during a reconnect run is a coroutine test with the fake
  adapter.

**Device-required:**
- Real `STATE_DISCONNECTED` / `STATE_ERROR` from `SoftEtherVpnService`.
- Real `GlobalVpnTracker` network-lost callback (`GlobalVpnTracker.kt:126-147`).
- Real liveness-watchdog silence path.
- The full attach chain (`TunnelStateWatcher.attach`, `TunnelStateWatcher.kt:47-55`, which needs an
  Android `Context` and static service classes).

**Proposed ≥5 test scenarios:**
1. **T-R8-1 (unexpected drop, ENABLED)** → fake adapter, state `Connected`, fire `onTunnelLost` →
   assert a new run started with the dropped server first in `overrideServers`.
2. **T-R8-2 (unexpected drop, DISABLED)** → same but `getAutoReconnectInAutoMode() == false` →
   assert state becomes `Disconnected` and `isRunning == false` (no run started).
3. **T-R8-3 (same server unreachable → next server)** → fake adapter fails protocol 1, succeeds on
   protocol 2 of the *next* server → assert rotation order `[dropped, next]` in priority order.
4. **T-R8-4 (user Disconnect during reconnect)** → start a reconnect, call `stop()` → assert
   `job == null`, `isRunning == false`, state `Disconnected`.
5. **T-R8-5 (loop guard)** → drive `N+1` consecutive unexpected drops within `M` → assert the run
   terminates with the chosen terminal error and does not start an `(N+1)`-th run.
6. **T-R8-6 (manual connect is not a drop)** → assert that a manual-path `setState(Disconnected)`
   (or a manual `GlobalVpnTracker` CONNECTED while Auto Mode idle) does not schedule a reconnect.
7. **T-R8-7 (source-shape)** → assert the three armed paths call the single
   `onUnexpectedDisconnect()` helper and the three manual paths do not (mirrors the existing
   `SoftEtherClientHandleGuardTest` source-shape style, since `SoftEtherClient` cannot be
   instantiated in JVM tests — `init` calls `System.loadLibrary`).

---

## 17. D9 — Failure Modes and Limitations

| # | Failure mode | Behaviour | Design response |
|---|--------------|-----------|-----------------|
| F1 | Setting ENABLED but server list is **empty** at reconnect time | The reconnect `start()` hits the existing empty-list guard → `emptyServerListMessage()` (`:924-928`). | Correct and inherited: nothing to dial, tell the user to update the list. **[SRC]** |
| F2 | App **process killed** during a tunnel | No in-process signal fires; on next launch `AutoModeEngine` is fresh, `_state == Disconnected`, `AutoModeRun.lastSuccess == null`. **No reconnect occurs.** | **Documented limitation.** R8 is in-process only. A cross-process auto-reconnect would require a persisted ownership flag + a boot/Service-start hook — explicitly **out of scope** unless the user asks (OQ-4). **[SRC]+[INF]** |
| F3 | Disconnect due to **network loss** | `GlobalVpnTracker.onLost` (`:136`) fires `onTunnelLost` **and** the 1 s poll (`:508`) detects `!isConnected`. | Both are unexpected → R8 fires. A redial while the network is still down will fail; the run rotates servers and may exhaust the list → `ERROR_NO_SERVER`. **[INF]** Consideration: should R8 wait for `NetworkCapabilities.NET_CAPABILITY_VALIDATED` before the first attempt? Not specified (OQ-3). |
| F4 | Dropped server's native slot still unwinding | `connect()` returns `NATIVE_BUSY` → the existing retry budget (`:1138-1214`) handles it: wait 2 s, retry, up to the 10 s per-server budget. | Correct and inherited. **[SRC]** |
| F5 | Server that accepts then immediately drops (flapping) | Without a guard, R8 reconnects forever. | **Requires the loop guard of D5 (OQ-1).** |
| F6 | User manually connects while a reconnect run is in flight | `isTunnelBusy()` (`GlobalVpnTracker.isBusy`, `AutoModeEngine.kt:108`) — the Manual connect moves the tracker to CONNECTING/CONNECTED. | R8's redial should stand down; the run loop's existing behaviours apply. Exact desired precedence is **OQ-7** (see §19). **[INF]** |
| F7 | Setting toggled OFF while a reconnect wait is in `delay` | The current run continues to completion (the setting is read at the drop decision, not per retry). | Documented as intended (D7 rule 2). **[INF]** |

---

## 18. D10 — Observability

**[SRC] Log channel:** `adapter.log(...)` writes to **both** logcat (tag `AutoMode` via
`AndroidConnectionAdapter.log`, `:406-409`) and the in-memory `AutoModeLogStore` ring buffer
(MAX_LINES = 2000, surfaced in the on-screen Log panel with Copy/Clear). It is **not** persisted.

**Proposed R8 log lines [INF]** (following the existing `[AUTO]` prefix convention — e.g. `:409`
`"[AUTO] Skipping current server; trying next"`):

1. On unexpected disconnect, before deciding:
   `[AUTO] Unexpected disconnect while Auto Mode owns the tunnel: <host>/<proto>`
2. If the setting is DISABLED:
   `[AUTO] Auto-Reconnect is disabled; staying disconnected`
3. If ENABLED, before re-invoking the run:
   `[AUTO] Auto-Reconnect enabled; reconnecting to <host> (attempt <n>)`
4. On the loop guard tripping:
   `[AUTO] Auto-Reconnect limit reached (<n> in <m> min); stopping`
5. On a reconnect run ending in `ERROR_NO_SERVER`: existing message (`:1010`/`:1272`) suffices.

**How the user knows R8 is active [INF]:** when a reconnect run starts, the normal
`AutoModeState.Connecting(...)` transition fires (`:1057`) and the engine mirrors it into the status
notification via `AutoModeNotifier.notifyConnecting(...)` (`AutoModeEngine.kt:120-122`) and into
`stateFlow` (`:118`). So the **existing** UI/notification already shows "Connecting…" — the user
gets feedback with **zero new UI work**.

**Should the UI show a distinct "Reconnecting…" state? [OQ-5]** §11.13 does not require it. Options:
(a) reuse `Connecting` (zero new UI; recommended default), or (b) add a distinct state/badge. This
is a user-facing product decision, recorded as OQ-5.

---

## 19. Open Questions (OQ)

| ID | Question | Why it is open | What is needed |
|----|----------|----------------|----------------|
| **OQ-1** | **Loop protection:** what is `N` (max reconnects) and `M` (window)? Is a guard required at all? | §11.13 re-applies R7, which has no frequency limit; the spec never states N/M. **[SPEC]+[UNK]** | User decision on N, M, and the terminal state to publish. |
| **OQ-2** | **NATIVE_BUSY budget scope:** fresh per reconnect run, or shared with the original run's budget? | §11.13 silent; code makes it automatic-per-server, but R8-specific intent is unstated. **[SPEC]+[INF]** | User confirmation of the recommendation in §14 (reuse). |
| **OQ-3** | **Delay before the first reconnect attempt:** zero (immediate) or a short wait? Should R8 wait for network validation after a network-loss drop? | §11.13.3 says "automatically attempts" with no timing. **[SPEC]+[UNK]** | User decision; affects F3 behaviour. |
| **OQ-4** | **Process-death persistence:** must R8 survive process death (persisted ownership flag + boot hook)? | §11.13 never mentions process death; the current design is in-process only. **[SPEC]+[UNK]** | User scope decision (recommend: out of scope for R8 v1). |
| **OQ-5** | **UI exposure:** reuse `Connecting` or add a distinct "Reconnecting…" state? | §11.13 does not specify UI. **[SPEC]+[UNK]** | User/product decision (recommend: reuse). |
| **OQ-6** | **String resource keys + Persian text:** confirm `setting_auto_reconnect_label` / `_hint`, and supply the Persian string. | Only the *label* is CONFIRMED; the repo has no Persian string for it. **[UNK]** | User/translation input. |
| **OQ-7** | **Precedence:** if the user manually connects during an R8 reconnect, does the manual connect win outright? | Not addressed by §6.7/§11.13. **[INF]** | User confirmation (recommend: manual connect wins; R8 stands down). |
| **OQ-8** | **Terminal error for the loop guard:** reuse `ERROR_NO_SERVER` or add `ERROR_RECONNECT_LIMIT`? | Depends on OQ-1. **[UNK]** | User decision, contingent on OQ-1. |

No value has been invented above; every unresolved item is recorded as UNKNOWN with the reason.

---

## 20. Evidence Taxonomy

**SOURCE CODE EVIDENCE [SRC]** (read directly; all paths relative to repo root):
- `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeModels.kt:151-174` — `AutoModeState`.
- `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt` — `_state:344`,
  `setState:357`, `job:363`, `skipToNextServer:397-454`, `start:566-572`, `stop:575-605`,
  `onButtonPressed:608-657`, `disconnectNow:660-681`, `startConnectedWatcher:483-541`,
  `runAutoMode:892-1275`, success `:1251-1267`, budget `:299-327`/`:1138-1214`,
  `overrideServers:545`, `remainingCandidates:470`, `rememberRemaining:1278-1285`,
  `usableProtocols:710-717`, `supports:1311-1342`.
- `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeEngine.kt` — `object:20`,
  `controller:22-23`, `ensure:52-131`, `onSuccess:69-73`, `isTunnelBusy:108`,
  `setSkipSignal:114`, `onStateChange:117-128`, `AutoModeRun:216-219`.
- `app/src/main/java/vn/unlimit/vpngate/automode/TunnelStateWatcher.kt` — `object:22`,
  `onTunnelLost:32`, `attach:47-55`, `detach:57-70`, SoftEther `:313-324`, OpenVPN `:330-366`,
  SSTP `:35-45`.
- `app/src/main/java/vn/unlimit/vpngate/state/GlobalVpnTracker.kt` — `object:65`,
  `isConnected:73-74`, `isBusy:204`, `onLost:126-147`, `onSoftEtherStateChanged:206-234`.
- `app/src/main/java/vn/unlimit/vpngate/automode/AndroidConnectionAdapter.kt` —
  `requestDisconnect:116-123`, `disconnect:141-200`, `armTunnelWatch:209-225`,
  `skipCurrent:391-401`, `isOwnTunnelEstablished:340-350`, `log:406-409`.
- `app/src/main/java/vn/unlimit/vpngate/automode/OwnedVpnDecision.kt:22-47` — ownership rule.
- `SoftEtherClient/src/main/java/vn/unlimit/softether/controller/ConnectionController.kt:74-75,
  258-292, 734, 1108-1160` — native reconnect constants/consumption.
- `app/src/main/java/vn/unlimit/vpngate/utils/DataUtil.kt:296-304, 332-337, 348-358, 519-520, 555-556`.
- `app/src/main/java/vn/unlimit/vpngate/ui/screens/settings/SettingsScreen.kt:165, 255-277,
  485-523, 679-690`.
- `app/src/main/java/vn/unlimit/vpngate/ui/screens/settings/SettingsComponents.kt:52, 82-139`.
- `app/src/main/java/vn/unlimit/vpngate/viewmodels/AutoModeViewModel.kt:19, 23, 27, 31, 34`.
- `app/src/main/res/values/strings.xml:261-287`.

**SPECIFICATION EVIDENCE [SPEC]:**
- `PROJECT_SPECIFICATION.md:599-607` (§6.7 R7), `:609-611` (§6.7 R8),
  `:1153` (distinctness), `:1149`–`:1184` (§11.13.1–.5).

**INFERENCE [INF]:** §3.3 classification, §4.2 re-invocability consequence, §5 lifecycle
in-process-only conclusion, §7 orthogonal-mechanisms conclusion, §8 budget-reuse recommendation,
§9 `onUnexpectedDisconnect()` helper shape, §11 flow reuse, §13 guard necessity, §16 test split,
§17 failure-mode responses, §18 log lines.

**UNKNOWN [UNK]:** OQ-1 (N/M), OQ-2 (budget scope confirmation), OQ-3 (first-attempt delay),
OQ-4 (process-death scope), OQ-5 (UI state choice), OQ-6 (Persian string), OQ-7 (precedence),
OQ-8 (terminal error name).

---

## 21. Explicit No-Change Confirmation

| Category | Changed |
|----------|---------|
| **Source** | **NONE** |
| **Test** | **NONE** |
| **Gradle** | **NONE** |
| **CI** | **NONE** |
| **Device** | **NONE** (no adb invocation) |
| **Build** | **NONE** |
| **Commit / tag / push / release** | **NONE** |
| **Files edited** | **NONE** |

This phase executed only read operations (`Read`, `Grep`, `Glob`, and non-mutating `sed`/`grep`/`ls`
via shell). No file in the repository was created, modified, or deleted. The only artefact produced
is this report and the corresponding workspace-memory log entry.

---

## 22. Final Recommendation

**NEEDS USER INPUT ON OPEN QUESTIONS.**

The design is architecturally complete and implementable with high confidence, because R8 is a
thin trigger layered on machinery that already exists and is already exercised in production:

- the run loop is re-invocable while Connected (§4.2);
- the caller-supplied list mechanism (`overrideServers`) already exists (§11);
- the exact "cancel watcher → disconnect → reseed list → start()" pattern is already shipped as the
  Connected "Try next server" branch (`AutoModeController.kt:415-441`);
- the "unexpected vs manual" distinction is already encoded by the disarm-before-teardown invariant
  (§3.3, §9);
- the settings toggle follows an existing, identical pattern (`SettingSwitchRow` + `getBooleanSetting`);
- the F.28AL-Y retry budget is inherited automatically (§14).

**Before implementation (F.28AL-AUTOCONNECT-R8-IMPL) can start, the user must resolve at minimum
OQ-1 (loop guard N/M — the only decision that changes the architecture), and should confirm OQ-2,
OQ-3, OQ-5, OQ-7, and OQ-8. OQ-4 (process death) and OQ-6 (Persian string) can be deferred if the
user accepts the recommended defaults.**

If OQ-1 is answered with "a guard is required" and a value for N and M, the remaining
recommendations in §9–§18 are sufficient to implement R8 without further research.
