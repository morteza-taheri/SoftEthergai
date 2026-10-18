# AutoConnect State Machine Overhaul — Design

**Phase:** `AUTOCONNECT-STATE-MACHINE-OVERHAUL` — PART 1 (DESIGN)
**Repository:** `D:\PMC\Documents\Android\SoftEthergai`
**Branch:** `main` (HEAD `5773c603603ef2e0b8cba2dda1f1852e50766368`)
**Author:** Agent (read-only design pass; no native files modified)
**Scope:** Kotlin/Android AutoMode layer only. The 5 PHASE-1 native files are frozen (except the D-1 check, which proved safe — see PART 0).

---

## PART 0 — D-1 Verification (read-only, native)

**Question:** Is `race_control_t` (`ctrl`) freed on the path where a worker was detached
(`softether_connect_parallel_race`, `SoftEtherClient/.../softether_protocol.c`)? If so, the
detached worker later touches a freed `ctrl` → residual UAF (D-1).

**Evidence** (lines ~2546–2563 of `softether_protocol.c`):

```c
free(ctrl);                 // :2554  inside  if (detached_count == 0) {  (:2550)
// else branch (:2556-2563):
LOGW("Parallel race: %d transport thread(s) detached; retaining transport "
     "contexts and control block to avoid a use-after-free", detached_count);
```

`ctrl` (the heap `race_control_t*` owning `cancel_flag`, `winner_claimed`, `winner_index`,
`race_deadline_ms`, `thread_ids`, `trampolines`, `created`) is freed **only** when
`detached_count == 0`. When one or more workers are detached, **both** the per-worker
`threads[]` contexts and `ctrl` are intentionally retained.

**Conclusion: D-1 is SAFE.** The one permitted native exception this phase does **not**
trigger — no native fix is required, and the 5 PHASE-1 native files remain unmodified.

---

## 0. Operator Symptom — Root Cause (AC-03 / AC-11)

> *"AutoConnect reports DISCONNECTED while a tunnel created by AutoConnect is still up."*

### 0.1 Where the false DISCONNECTED is born

`AutoModeController.onUnexpectedDisconnect(droppedServer)` is the single funnel for *loss*
events. Its very first statement (line 670) is:

```kotlin
private suspend fun onUnexpectedDisconnect(droppedServer: AutoModeCandidate?) {
    setState(AutoModeState.Disconnected)   // :670 — published UNCONDITIONALLY & IMMEDIATELY
    if (isTunnelBusy()) { ... return }     // :674 — only checked AFTER the publish
    ...
    start()                                // :742 — reconnect
}
```

It is invoked from `startConnectedWatcher(protocol)`'s `TunnelStateWatcher.onTunnelLost`
callback. `onTunnelLost` can fire from **any** of these, none of which are identity-scoped
to the AutoConnect attempt currently in `Connected`:

1. `TunnelStateWatcher.onSoftEtherStateChanged` (loss path) — fires `onTunnelLost` on
   `DISCONNECTED`/`ERROR` **without** protocol/session scoping (the CONNECTED/"up" arm is
   scoped to `isSoftEtherProtocol(watching)`, but the loss arm is not).
2. `GlobalVpnTracker` global `onTunnelLost` callback (≈ line 136) — fires
   `TunnelStateWatcher.onTunnelLost?.invoke()` **regardless of protocol**.
3. A stale `SoftEtherSessionPolicy` session whose `sessionGeneration` is not the one we
   armed — the watcher does not validate generation before invoking `onTunnelUp`/`onTunnelLost`.

### 0.2 The failure chain that produces the symptom

1. AutoConnect reaches `Connected` (real SoftEther tunnel up).
2. A **spurious or cross-protocol** `DISCONNECTED`/`onTunnelLost` arrives (foreign session,
   SSTP/OpenVPN teardown notification, or a stale SoftEther session end).
3. `onUnexpectedDisconnect` runs and publishes `Disconnected` **immediately** (line 670),
   even though `adapter.isOwnTunnelEstablished()` would still report the AutoConnect tunnel as
   up (the real tunnel never dropped).
4. The reconnect `start()` → `runAutoMode()` reaches `wasReconnect && isTunnelBusy()`
   (line 1215), sees the **still-up** tunnel, and aborts with `Disconnected`.
5. Net result: UI shows **DISCONNECTED**, but the AutoConnect-created tunnel is live. Symptom
   reproduced.

### 0.3 Fix strategy (detailed in §C, §D, §E)

- **(A) Identity-scoping (§C):** every tunnel-up / loss / teardown event carries an identity
  tuple `(attemptId, protocol, serverKey, sessionGeneration)`. Delivery is refused unless it
  matches the currently-armed/connected attempt. This makes the spurious event in step 2
  simply never reach `onUnexpectedDisconnect`.
- **(B) Loss verification gate (§E):** even after an *authorized* loss event, the controller
  must **confirm the tunnel is actually gone** (`isOwnTunnelEstablished()` is false *and/or*
  teardown confirmation received) **before** publishing `Disconnected`. If the tunnel is still
  up, the loss is treated as spurious: stay `Connected`, re-arm the watcher. This is the direct
  kill for the operator's symptom.
- **(C) Teardown-confirmed terminal state (§E):** intentional stop/disconnect publishes a new
  `Disconnecting` transitional state, waits for the identity-scoped `TeardownWaiter`
  confirmation, *then* publishes `Disconnected` — closing the "stop publishes DISCONNECTED
  without teardown" half of AC-11.

---

## §A State Inventory

`sealed class AutoModeState` (currently in `AutoModeModels.kt:152`). The overhaul adds one
state and clarifies terminal semantics.

| State | Kind | Meaning | Terminal? | Added/Changed |
|---|---|---|---|---|
| `Disconnected` | `object` | No AutoConnect tunnel; stable idle. | Yes (stable) | — |
| `Connecting(hostname, ip, protocol, speed, ping, attempt, total)` | `data` | An attempt is in flight for one protocol of one server. | No | — |
| `Connected(hostname, ip, protocol, speed, ping)` | `data` | A tunnel AutoConnect owns is up. Carries the **exact** connected candidate identity. | No | — |
| `Disconnecting` | `object` (or `data` w/ protocol) | Transitional: teardown requested, awaiting teardown confirmation. UI shows "disconnecting". | No (→ `Disconnected`) | **NEW** |
| `Error(message)` | `data` | Terminal failure (no enabled protocol, empty list, native unresolved, reconnect limit, all servers failed). | Yes (until user re-press) | — |

**Invariant additions:**
- `Disconnected` is published **only** when (a) an authorized, identity-matched loss was
  confirmed real (tunnel gone), or (b) an intentional stop/disconnect completed teardown
  confirmation, or (c) a fresh user re-press resolved the list. It is **never** published on a
  stale/cross-protocol/spurious event.
- `Disconnecting` is the only pre-`Disconnected` transitional state for teardown; it MUST be
  exited by a teardown confirmation, never by a timeout that races the tunnel still being up.
- `Connected` records the candidate identity (`connectedCandidate`) so an unexpected disconnect
  names the exact server and the loss verification can compare against the live tunnel.

---

## §B Transition Table

Guards evaluated in order. `isTunnelBusy()` = `GlobalVpnTracker.isBusy` (CONNECTING/DISCONNECTING)
— the app-wide ownership signal.

| # | From | To | Trigger / Event | Guard / Condition | Notes / AC |
|---|---|---|---|---|---|
| T1 | `Disconnected` / `Error` | `Connecting` | user start (button) | `state ∈ {Disconnected,Error}` **and** `!isTunnelBusy()` **and** `priorities.isNotEmpty()` **and** `serverList.isNotEmpty()` | AC-09/AC-10 guard; AC-19 default priority |
| T2 | `Connecting` | `Connected` | `onTunnelUp` | event identity `(attemptId,protocol,serverKey,sessionGeneration)` matches armed attempt **and** `!isTunnelBusy()`-admissible | AC-08 identity; reuse existing `TeardownWaiter`/`armedTunnel` identity machinery |
| T3 | `Connecting` | `Disconnecting` | user stop / skip / `connect()` exception / permission missing | — | AC-06 watcher disarm on connect exception; AC-07 cancellation |
| T4 | `Connecting` | `Error` | no enabled protocol / empty list / `VpnPermissionMissing` / `NATIVE_BUSY` give-up / `RECONNECT_LIMIT` / all servers failed / provider exception | — | AC-18 provider exception; AC-05/AC-13 native give-up |
| T5 | `Connected` | `Disconnecting` | user disconnect / stop | — | AC-11 teardown-confirmed |
| T6 | `Connected` | `Connected` *(stay)* | **spurious / cross-protocol loss** | event identity does **not** match connected attempt **or** `isOwnTunnelEstablished()` still true | **NEW** — kills operator symptom (AC-03/AC-11/AC-12) |
| T7 | `Connected` | `Disconnected` | **authorized loss confirmed real** | identity matches **and** tunnel confirmed gone (teardown confirm `or` `isOwnTunnelEstablished()`==false) | AC-11 core fix |
| T8 | `Disconnecting` | `Disconnected` | teardown confirmation received (identity-scoped `TeardownWaiter`) | — | AC-11 "stop without teardown" half |
| T9 | `Disconnecting` | `Connecting` | (not used) reconnect always restarts from `Disconnected` | — | — |
| T10 | `Error` | `Connecting` | user re-press (reset budget) | T1 guards | — |
| T11 | `any` | `Disconnected` | hard cancel of run coroutine | cancellation handled via AC-07; never leaves `Connecting` dangling | AC-07 |

**Reconnect sub-flow (T7 → T1 restart):** an authorized drop seeds `pendingReconnectServer`
and `overrideServers`, then `start()` re-enters `runAutoMode` which prepends the dropped server
(**AC-17 fix**) and re-applies T1 guards.

---

## §C Event Ownership Model

Every signal that can move the state machine carries an **identity tuple** and is delivered
only if it matches the attempt currently armed/connected. This is the first line of defense for
the operator's symptom and resolves AC-08 and AC-12.

```
EventIdentity = (attemptId: Int, protocol: AutoModeProtocol,
                 serverKey: String /* hostname|ip */,
                 sessionGeneration: Long)
```

- **Source of truth for generation:** `SoftEtherSessionPolicy.sessionGeneration`
  (monotonic, incremented in `onSessionStart()`). The watcher must capture the generation at
  `armTunnelWatch` time and reject any `onTunnelUp`/`onTunnelLost` whose generation ≠ armed
  generation (closes AC-08 — a stale session's CONNECTED can no longer be adopted).
- **Arming carries identity:** `adapter.armTunnelWatch(protocol, attemptId, sessionGeneration,
  serverKey)` replaces the current `armTunnelWatch(protocol)`. The watcher stores the armed
  identity and validates every callback against it *before* invoking `onTunnelUp` /
  `onTunnelLost`.
- **Loss path scoping (AC-12):** `TunnelStateWatcher.onSoftEtherStateChanged` and the
  `GlobalVpnTracker` global `onTunnelLost` must both route through the same identity check. A
  loss whose `(protocol, serverKey, sessionGeneration)` ≠ armed identity is **dropped**, never
  forwarded to `onUnexpectedDisconnect`. Cross-protocol events (SSTP/OpenVPN notifications
  while watching SoftEther, or vice-versa) are inherently mismatched and thus ignored.
- **Teardown identity (already exists):** `TeardownWaiter(token, expectedAttemptId,
  onResult)` + `armTeardown`/`disarmTeardown`/`deliver` already enforce exact
  `attemptId == expectedAttemptId`. Reused as-is for §E teardown confirmation (extends
  `TeardownWaiterIdentityTest`).
- **Default-deny:** if no attempt is armed (state ≠ `Connecting`/`Connected`), any tunnel-up /
  loss event is ignored. The watcher is disarmed on every terminal transition.

---

## §D Watcher Lifecycle Rules

- **Pairing invariant:** every `armTunnelWatch(...)` is paired with a `disarmTunnelWatch()`
  (or overwritten by the next `armTunnelWatch`, which must reset the armed identity). The
  watcher is a singleton (`TunnelStateWatcher`); leaving a stale armed identity is what lets a
  later server's events leak into the current attempt.
- **AC-06 — connect() exception:** in `runAutoMode`, the `catch (e: Exception)` block that
  handles a throwing `adapter.connect()` (line 1394–1398) currently calls
  `adapter.disconnect()` and `break` **without** disarming the watcher armed at line 1384.
  Fix: call `adapter.disarmTunnelWatch()` in that handler before `break` (and before the
  protocol loop continues), so a stale armed identity cannot deliver a late `onTunnelUp` /
  `onTunnelLost` for the *next* server.
- **AC-07 — cancellation:** the `catch (e: Exception)` at line 1394 also swallows
  `CancellationException`, which masks coroutine cancellation and lets the loop continue past a
  user stop. Fix: add `catch (e: CancellationException) { throw e }` **before** the generic
  `Exception` catch. Cleanup (disconnect + disarm) is still performed by the generic catch for
  real transport errors; cancellation propagates so `currentCoroutineContext().isActive`
  checks and run termination behave correctly.
- **Connected-watcher scoping:** `startConnectedWatcher(protocol)` installs
  `onTunnelLost` scoped to the connected identity. On a *spurious* event (§B T6) it must
  **re-arm** (stay `Connected`), not tear down.
- **Test seams preserved:** `TunnelStateWatcher.stateProvider` and
  `rewindTeardownSequenceForTests()` remain the isolation mechanism; the new identity fields
  are set through the same arming API so tests can drive them deterministically.

---

## §E Recovery / Terminal Semantics

### E.1 Loss verification gate (operator symptom fix — AC-03/AC-11)
Rewrite `onUnexpectedDisconnect` to **verify before publish**:

1. (Identity already ensured by §C — only authorized, matching loss reaches here.)
2. **Confirm the tunnel is actually gone.** Two acceptable confirmations:
   - `adapter.isOwnTunnelEstablished()` returns `false` (our tunnel really dropped), **or**
   - a teardown completion for the connected `attemptId` is received via the identity-scoped
     `TeardownWaiter` (this is the authoritative native-cleanup signal).
3. If the tunnel is **still established** (`isOwnTunnelEstablished()` == true) → treat as
   spurious: log, **do not** publish `Disconnected`, re-arm the connected watcher, return
   (stay `Connected`). This is T6 and directly prevents the symptom.
4. Only when confirmed gone → publish `Disconnected` (T7), then apply OQ-7 / OQ-6 / OQ-1 /
   OQ-3 as today (manual precedence, setting, loop guard, settle delay), then seed reconnect.

### E.2 Teardown-confirmed stop/disconnect (AC-11 "without teardown" half)
`stop()` and `disconnectNow()` currently call `adapter.requestDisconnect()` then synchronously
`publish Disconnected`. Make them transitional:
1. Publish `Disconnecting` (new state, §A).
2. `adapter.requestDisconnect()` (fire-and-forget, non-suspending) as today.
3. `armTeardown(connectedAttemptId){ ... }` and `await` the identity-scoped completion on a
   background coroutine; on completion → publish `Disconnected`. If the completion never
   arrives within a bounded wait, publish `Disconnected` anyway (fail-safe, never hang the UI)
   but log the missing confirmation. This guarantees teardown completed (or was waited for)
   before the terminal DISCONNECTED, and gives the UI a correct transitional state.

### E.3 Reconnect same-server-first — actually implement it (AC-17)
`pendingReconnectServer` is seeded at line 732 but **nulled at line 1214 without ever being
prepended** to the candidate list — the "same server first" behavior is currently a no-op
(claimed in comments only). Fix in `runAutoMode`:
```kotlin
val reconnectSeed = pendingReconnectServer   // captured before nulling
pendingReconnectServer = null
val all = (overrideServers ?: serverProvider()).toMutableList()
if (reconnectSeed != null) {
    // prepend the dropped server (§11.13.3 step 1), de-dup against the rest
    all.add(0, reconnectSeed)
    // de-dup by serverKey so the seed isn't also attempted later
}
```
Preserve identity so the same `AutoModeCandidate` object is used (the code already filters by
`ip|hostname`). Add a regression test (§H).

### E.4 Provider exceptions leave no stale state (AC-18)
`serverProvider()` (line 1207) is called unguarded. If it throws, the run coroutine fails and
`_state` can be left at `Connecting`. Fix: wrap the provider call in `try/catch`; on exception
publish `Error(providerErrorMessage)` (never a dangling `Connecting`), and ensure
`job = null` so the run is terminal.

### E.5 Native unresolved give-up — proactive recovery (AC-13)
`giveUpWithUnresolvedNativeOperation` already requests `requestRecoveryTeardown()`. Preserve
this; the design's requirement is that recovery teardown is **always requested on give-up**
(rather than relying solely on demand-triggered release at the next acquire). No change beyond
preserving the existing call; documented as an invariant.

### E.6 Reconnect loop guard & manual precedence
OQ-1 (rolling window), OQ-6 (setting), OQ-7 (manual precedence via `isTunnelBusy()`) are
preserved exactly as today. The only change is *where* `Disconnected` is published (after
verification) and the `Disconnecting` transitional for intentional teardown.

---

## §F Timeout Result Typing

- **Single typing authority:** `enum AttemptOutcome { TUNNEL_UP, NATIVE_BUSY, TIMED_OUT }`
  (in `AutoModeController`). The classifier is `AndroidConnectionAdapter.awaitArmedOutcome`,
  which, when the armed wait does **not** resolve to a tunnel, re-checks `isNativeSlotBusy()`
  (`NativeLifetimeRegistry.process.isAdmissible()/isBlocked()`) to decide `NATIVE_BUSY` vs
  `TIMED_OUT` (AC-05). This rule is made the **sole** classifier; no caller may infer
  NATIVE_BUSY/TIMED_OUT from anything else.
- **AC-05 edge:** ensure the `NATIVE_BUSY` vs `TIMED_OUT` decision cannot be flipped by a
  late-delivered CONNECTED (the "notification missed" path at line 1547 already checks
  `isOwnTunnelEstablished()` and upgrades to `TUNNEL_UP`). Add an explicit test that a
  non-up wait while the slot is busy yields `NATIVE_BUSY` and a non-up wait while the slot is
  free yields `TIMED_OUT`.
- **AC-14 — single timeout source:** `controller.attemptTimeoutMs` (set from
  `DataUtil.getAutoModeTimeoutSeconds()` at `AutoModeEngine.ensure()`) is the **only** timeout
  value. `AndroidConnectionAdapter.connectSoftEther` currently **re-reads** `DataUtil` for the
  timeout and sets `ConnectionConfig.connectTimeoutMs`. Fix: pass the authoritative
  `attemptTimeoutMs` into the adapter (constructor/setter captured at `ensure()`), so the
  Kotlin-level awaiter and the native connect timeout can never diverge. The adapter must not
  call `DataUtil.getAutoModeTimeoutSeconds()` itself.
- **AC-15 — missing OpenVPN profile wastes full timeout:** add a pre-dial capability check
  `hasConnectableProfile(protocol)` consulted inside `usableProtocols`/`isUsableFor` (or
  immediately before `adapter.connect()`). If the protocol has no connectable profile (e.g.
  OpenVPN app not installed / no profile), **skip the protocol without arming/connecting** —
  log and advance to the next protocol — instead of burning the full `attemptTimeoutMs`. This
  prevents a silent full-timeout wait on an unconnectable protocol.

---

## §G Cross-Cutting Rules

- **AC-09 / AC-10 — global ownership on every start entry point:**
  - `AutoModeEngine.onButtonPressed()` currently routes to `controller.onButtonPressed()` →
    `onButtonPressedWithoutGuard()` → `start()` (unguarded, bypasses `isTunnelBusy()`).
  - `AutoModeEngine.start()` = `ensure().start()` (unguarded).
  - Fix: route **both** through the guarded `startOrRefuseIfNoServers()` (which already
    consults `isTunnelBusy()` and refuses a duplicate/racing start, then calls
    `onButtonPressedWithoutGuard()` for the running/connected cases). The raw `controller.start()`
    remains for the internal reconnect path where `runAutoMode` re-checks `wasReconnect &&
    isTunnelBusy()`.
- **AC-19 — fresh-install priority empty:** `DataUtil.getAutoModeProtocolPriority()` returns
  `.orEmpty()` when unset → fresh install refuses with `ERROR_NO_ENABLED_PROTOCOL`. Fix:
  introduce `DEFAULT_AUTO_MODE_PROTOCOL_PRIORITY` (e.g. SoftEther-TCP > OpenVPN-TCP > MS-SSTP)
  and return it when the stored value is null/blank. To keep "user disabled all" meaningful,
  store a sentinel (`SETTING_AUTO_PROTOCOL_PRIORITY = ""` with a separate
  `hasConfiguredProtocolPriority` boolean) so an explicit empty user choice still refuses while
  an unset install gets the default. The `ProtocolPriorityDialog` must initialize `enabledIds`
  from the default when nothing is saved.
- **AC-20 — settings edit reorders saved priority:** `ProtocolPriorityDialog` initializes
  `ordered = AutoModeProtocol.entries.toList()` (enum order) regardless of saved order, so
  re-saving discards the user's priority. Fix: initialize `ordered` from
  `getAutoModeProtocolPriority()` with any newly-added protocols appended; persist preserves
  the user's order.
- **AC-16 — reachability gate must not drop no-verdict candidates:** `ServerReachabilityTester
  .filterReachableCandidates` already keeps `UNKNOWN`/`REACHABLE` and only drops a candidate
  whose **every** measured verdict is `UNREACHABLE`; `allUnreachable` is true only when
  something was genuinely measured and all-down. `runAutoMode` keeps the full list when
  `gate == null` or `!gate.allUnreachable`. **Preserve** this exactly; add/extend a test to
  lock it (a candidate with only `UNKNOWN` verdicts must survive).
- **AC-21 — UDP-only exclusion policy conflict:** `SOFTETHER_UDP.supports` requires
  `seTcpPort > 0` (line 1722–1723) — the single, authoritative exclusion for the broken
  R-UDP-over-TCP-control-channel case. Route **all** protocol eligibility through
  `usableProtocols`/`supports` so no other path dials `SOFTETHER_UDP` for a `seTcpPort == 0`
  server. Remove any divergent eligibility check. (Document-only change if no divergent path
  exists; verify during PART 2.)
- **AC-12 — cross-protocol loss (also in §C):** enforced by identity-scoping; no separate code
  beyond §C.
- **ViewModel surface (AC-09):** `AutoModeViewModel.state` already collects
  `AutoModeEngine.state()`; if `Disconnecting` is added, it flows automatically. No VM change
  required unless a new user action is needed; verify during PART 2.

---

## §H Test Plan

All new tests are **JVM** (`app/src/test`). They use the existing `FakeAdapter` (implements
`ConnectionAdapter`, scripts outcomes via `nativeBusyQueues`/`protocolOutcomeQueues`) and the
real `TunnelStateWatcher` with `rewindTeardownSequenceForTests()` for identity/teardown tests.
No device/APK.

| # | Test file | Covers | Assertion |
|---|---|---|---|
| H1 | `AutoModeStateMachineTest.kt` **(new)** | §A/§B/§E.1 operator symptom | Spurious/cross-protocol loss → state stays `Connected`; authorized+confirmed loss → `Disconnected`. |
| H2 | `AutoModeEventOwnershipTest.kt` **(new)** | §C (AC-08/AC-12) | Tunnel-up/loss with mismatched `protocol`/`serverKey`/`sessionGeneration` is ignored; matching is delivered. |
| H3 | `AutoModeControllerTest.kt` **(extend)** | AC-06 | `connect()` throws → watcher disarmed (next server's events don't leak). |
| H4 | `AutoModeControllerTest.kt` **(extend)** | AC-07 | `CancellationException` propagates (run terminates), not swallowed. |
| H5 | `AutoModeControllerTest.kt` **(extend)** | AC-17 | Reconnect list has dropped server **first** (prepend actually happens). |
| H6 | `AutoModeControllerTest.kt` **(extend)** | AC-18 | `serverProvider()` throws → `Error`, never dangling `Connecting`. |
| H7 | `AutoModeNativeBusyTest.kt` **(extend)** | AC-05 | Non-up wait + slot busy → `NATIVE_BUSY`; non-up wait + slot free → `TIMED_OUT`. |
| H8 | `TeardownWaiterIdentityTest.kt` **(extend)** | §E.2 (AC-11) | `stop()`/`disconnectNow()` only reach `Disconnected` after identity-scoped teardown confirm; `Disconnecting` observed in between. |
| H9 | `ReachabilityGateTest.kt` / `ServerReachabilityTesterTest.kt` **(extend)** | AC-16 | Candidate with only `UNKNOWN` verdicts survives the gate. |
| H10 | `DataUtil` test **(new, small)** | AC-19 | Unset priority → default list returned; explicit-empty sentinel → empty. |
| H11 | `SettingsScreen` test **(new, optional JVM w/ mock)** | AC-20 | Re-save preserves user priority order; new protocols appended, not reordered. |
| H12 | `AutoModeEngine` test **(new, small)** | AC-09/AC-10 | `start()` / `onButtonPressed()` consult `isTunnelBusy()` (refuse when busy). |
| H13 | `AutoModeControllerTest.kt` **(extend)** | AC-15 | Protocol with no connectable profile skipped without consuming full timeout. |

Both modules must pass:
- `./gradlew :SoftEtherClient:test` (adapter/native-binding unit tests, unaffected but run for safety)
- `./gradlew :app:testDebugUnitTest` (all JVM tests above)

---

## Implementation File Map (PART 2 preview — not executed yet)

| File | ACs touched |
|---|---|
| `AutoModeController.kt` | AC-05, AC-06, AC-07, AC-08, AC-11, AC-12, AC-17, AC-18 (+ new `Disconnecting` handling, loss gate, reconnect prepend) |
| `AutoModeModels.kt` | add `Disconnecting` state (AC-11) |
| `AndroidConnectionAdapter.kt` | AC-05 (classifier retained), AC-14 (timeout from param, not DataUtil), AC-15 (profile pre-check seam) |
| `TunnelStateWatcher.kt` | AC-08, AC-12 (identity-scoped up/loss) |
| `GlobalVpnTracker.kt` | AC-12 (global `onTunnelLost` routed through identity check) |
| `AutoModeEngine.kt` | AC-09, AC-10 (route start/onButtonPressed through guarded path) |
| `DataUtil.kt` | AC-19 (default priority + sentinel) |
| `SettingsScreen.kt` | AC-20 (preserve saved order) |
| `AutoModeViewModel.kt` | AC-09 (verify `Disconnecting` surfaces; no change expected) |
| `ServerReachabilityTester.kt` | AC-16 (preserve; test only) |
| Tests (3 existing extended + 2 new + small DataUtil/Engine tests) | per §H |
| `AUTOCONNECT_STATE_MACHINE_OVERHAUL_REPORT.md` | PART 2 deliverable |

**Frozen (not touched this phase):** the 5 PHASE-1 native files, `PROJECT_SPECIFICATION.md`,
build/settings Gradle, Room entities/migrations, SSTP/OpenVPN/L2TP source, existing reports,
`git add -A`, push/tag, adb/device, APK build.

---

## STOP CONDITION

This is the **mandatory PAUSE (STEP 1.3)**. PART 2 (implementation, tests, report, diff scope)
will **not** begin until the operator confirms this design. No PHASE 3 will be proposed
autonomously.
