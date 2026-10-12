# FIX-G1-DESIGN — Design Review for Stale SoftEther Service Leak

**Phase:** FIX-G1-DESIGN
**Date:** 2026-10-12
**HEAD:** `071462816b1eef7e7f7459483fcd69909938cd11`
**Mode:** READ-ONLY. Design only. No code modified, no build, no device, no CI, no commit.

---

## 1. Executive Verdict

**DESIGN COMPLETE — READY FOR IMPLEMENTATION**

All four terminal states, the exact stranding point, four fix options with a recommendation,
cancellation/idempotency, lease consistency, the G-2 question, testability, limitations and
migration surface are all resolvable from source. No source is unavailable.

Two decisions genuinely require user input before implementation (§12 items 1–3). Neither
blocks the design from being complete; both are **scope** decisions that change the size of
the diff, not the recommended direction.

---

## 2. Terminal-State Matrix (A1)

Scenario: a SoftEther session is `CONNECTED` and owns the service; the network is lost.
`onLost` → `onNetworkChanged(false)` (no teardown) → the data loops
(`startDataForwarding`'s `onPacket`/`onError`, and the receive loop) hit a failure and call
`triggerReconnect()` → `attemptReconnect()`.

| # | Scenario | Service state | `controller` | `tunOwnership` | `connectionJob` | Native lease | Correct terminal state |
|---|---|---|---|---|---|---|---|
| a | Reconnect succeeds within retry budget | Foreground, "Connected" | non-null | held | non-null, active | held, phase `CONNECTED` (`markConnected` `:1333`) | **Unchanged.** This is already correct. |
| b | Reconnect fails; budget exhausted (`:1108`) | **Foreground, stale notification** | non-null | held | non-null | held, phase `CONNECTED` | Service stopped, references cleared, `DISCONNECTED`+`STATE_ERROR` published. **This is G-1.** |
| c | Reconnect in flight when user presses AutoConnect | Foreground | non-null (state `CONNECTING` at `:1156`) | held | non-null | held, phase `CONNECTED` | Either admitted, or refused with a reason Auto Mode can act on — never a stall indistinguishable from an unreachable server. |
| d | Process killed during reconnect | — (process gone) | — | — | — | — | Kernel reclaims sockets/TUN; `NativeLifetimeRegistry.process` is in-memory and recreated empty → **no cross-process latch**. Nothing to fix; see §10. |

---

## 3. The Gap (A2)

Two coordinated defects. Both are required for the 4/4 reproduction.

### Gap 1 — `reconnectAttempts` is never reset on the reconnect-failure path

`reconnectAttempts` (`ConnectionController.kt:150`) is the **same** counter used by the
initial-connect retry loop (`connect()` `:258` `reconnectAttempts.set(0)`, `:261` loop
condition) and by `attemptReconnect` (`:1108` increment, `:1339` reset on success). On the
budget-exhaustion branch it is incremented and never returned to `0`:

```kotlin
:1108  if (reconnectAttempts.incrementAndGet() >= MAX_RECONNECT_ATTEMPTS) {
:1112      currentState = ConnectionState.ERROR
:1113      onError("Connection lost - max reconnection attempts reached")
:1114      disconnect()
:1115      return
:1116  }
```

`onError` (`:1113`) is the controller callback the service maps to
`stopVpn(disconnectByError = true)` (`SoftEtherVpnService.kt:703–725`, dispatch at `:724`).
So the budget branch *does* reach a correct teardown — but `disconnect()` on `:1114` is a
**different method** from `stopVpn`, and `:1126` `isCancelled.set(false)` earlier in the same
function means cooperative cancellation of `connectionJob` (`stopVpn:1012`) is ignored by a
reconnect that is mid-`nativeConnectWithHub`.

### Gap 2 — `disconnect()` cannot clear the service's references

`ConnectionController.disconnect()` (`:750–816`) sets `isCancelled`, stops
keepalive/statistics/TunTerminal, closes `vpnInterface`, sets `currentState = DISCONNECTED`
— but it **never** touches `sessionPolicy`, `tunOwnership`, `connectionJob`, or the native
lease. Only the *service's* `stopVpn` does that (`:1012`, `:1023–1024`, `:1035`, `:1054`).

### Gap 3 (more severe) — the generic catch never reaches `stopVpn` at all

```kotlin
:1342  } catch (e: Exception) {
:1343      Log.e(TAG, "Reconnection attempt failed", e)
:1346      claimAndDestroyNativeHandle()
:1348      currentState = ConnectionState.DISCONNECTED
:1349      onStateChange(ConnectionState.DISCONNECTED)
:1350  } finally {
```

There is **no `onError` call here**, so this path never reaches `stopVpn` under any
circumstance. A reconnect that fails by *exception* (rather than by budget) strands the
session harder than the budget branch. `nativeCreate` returning `0` throws at `:1194`, and
`result != 0` throws at `:1239` — both land here.

### The stranding point

```kotlin
SoftEtherVpnService.kt:541-543
    val sessionResourcesLive =
        controller != null || tunOwnership.isHeld() || connectionJob != null
    val decision = sessionPolicy.decideStart(resourcesLive = sessionResourcesLive)
```

All three are true. `decideStart` (`SoftEtherSessionPolicy.kt:186–191`):

```kotlin
!resourcesLive  -> ACCEPT
isStopping      -> REFUSE_TEARDOWN
isRunning       -> REFUSE_CONNECTED     <-- reached
else            -> REFUSE_CONNECTING
```

`isRunning` was set by the successful original connect (`SoftEtherVpnService.kt:752`
`onRunning()`) and is cleared only by `beginTeardown` (`SoftEtherSessionPolicy.kt:234`) or
`onSessionStart` (`:83`). Neither ran to completion → `REFUSE_CONNECTED` at
`SoftEtherVpnService.kt:546–547`, verbatim the device log.

**Key insight — the real injury is consumerlessness, not the refusal.** A retained lease
reaches two different refusals depending on the entry path:

| Entry path | Code | Auto Mode consumer? |
|---|---|---|
| Stranded session, resources read as live | `:546–547` `REFUSE_CONNECTED` | **None.** No listener, no error code, no rotation. |
| Fresh `startVpn`, lease still held | `:577–603` `tryAcquire()==null` → `notifyNativeBusy` (`:601`) | **Yes** → `TunnelStateWatcher:300–304` → `AutoModeController.ERROR_NATIVE_UNRESOLVED` (`:1432`/`:1477`) |

So the fix must convert the first into the second. That requires no change to the
fail-closed lease policy.

---

## 4. Options Analysis (A3)

| Criterion | **A** — reconnect failure → full `stopVpn` teardown | **B** — new `ERROR_NETWORK_LOST` terminal state, service stays alive | **C** — relax `decideStart` for "live but native-dead" | **D** — `onNetworkChanged` forces synchronous teardown |
|---|---|---|---|---|
| Mechanism | At `:1108` and `:1342`, reach the service's `stopVpn` instead of controller-local `disconnect()`. | Add a session phase; clear controller/TUN/lease; keep foreground + distinct notification. | Add `ACCEPT_REPLACING` when lease held but phase != `CONNECTED` and `!isConnected()`. | In `networkCallback.onLost` (`:346–349`), call `stopVpn()` instead of `onNetworkChanged(false)`. |
| User-visible change | Notification → "Disconnected (error)"; service stops. Consistent with any failed connect. | Notification persists with a "Network lost" message; next connect instant. Best UX. | None. But silently converts a fail-closed refusal into an admission. | Immediate teardown on every blip, including transient ones. |
| R8 interaction | R8 already reacts to `STATE_ERROR`; stopping the service publishes exactly that. **Clean.** | R8 sees a live service; needs new plumbing or it will not know a loss occurred. | `ERROR_NATIVE_UNRESOLVED` becomes unreachable for this cause → changes R8 semantics. | R8 reconnects after a full teardown; loses in-place reconnect. |
| AutoMode interaction | `disconnectNow()` (`:912`), connected watcher (`:552–590`) already handle service-stop. No change. | Requires `GlobalVpnTracker` status + connected-watcher change. Moderate. | The native-busy refusal stops being the signal for this case. | `adapter.disconnect()` on every blip; more `NOOP_REENTRANT` completions. |
| Complexity | **Low** — call-site change + counter reset. | **High** — enum, strings ×3 locales, tracker status, tests. | **Medium** — new predicate combining identity, lease phase, liveness. | **Low** code, **high** semantic risk. |
| Blast radius | Controller failure paths + service teardown. Well-covered by existing tests. | Policy + tracker + 3 string files + watcher + Auto Mode. | The central admission gate for **every** connect. | Every network transition. |
| Fail-closed property | **Preserved.** | **Preserved.** | **Weakened** — re-opens F.28U/F.28W. | Preserved but over-applied. |

---

## 5. Recommended Option

**Option A**, plus the root-cause change that makes it sufficient: **reset
`reconnectAttempts` to 0** on the terminal reconnect-failure path, and route that failure
through the service's `stopVpn` rather than the controller-local `disconnect()`.

Rationale:

1. **Fixes the defect rather than masking it.** G-1 is a stranded-ownership problem. Option A
   drives the session into the one path (`stopVpn`) that already, correctly and idempotently,
   releases all three Kotlin references and hands the native handle to `destroyResources()` —
   the only place the lease can legitimately be released (`ConnectionController.kt:130–134`).
2. **Does not touch the fail-closed lease policy.** When `requestTeardown` returns
   `UNRESOLVED` (`NativeAttemptCoordinator.kt:226`) the lease is still retained, correctly —
   but the next `startVpn` then emits `notifyNativeBusy` (`:601`), which Auto Mode already
   understands as "our own previous operation is still finishing, do not blame the server".
   That is the actionable refusal.
3. **Option C disqualified** — it relaxes the gate F.28U/F.28W exist to hold shut, and would
   admit a replacement while an obsolete native structure may still be live.
4. **Option D disqualified** — destroys the legitimate in-place reconnect, which is the entire
   purpose of `attemptReconnect`, and performs a blocking teardown on the ConnectivityManager
   callback thread.
5. **Option B is the best long-term UX** and should be recorded as a follow-on: strictly more
   work (new state, new strings in `values/`, `values-fa/`, `values-vi/`, new tracker
   plumbing) and not required to close the dead-end.

---

## 6. Cancellation and Idempotency (A4)

**Concurrent user AutoConnect must not race the teardown.** The gate (`:541`) and `stopVpn`
(`:467`, `:724`, `:905`) all run on the main thread today. Under Option A the controller's
terminal branch calls in from its `Dispatchers.IO` scope, making the teardown cross-thread.
The implementation must route that call through the same `mainHandler` the existing teardown
path uses, so the gate read and the reference-clearing remain serialized on main. This is a
**hard requirement**, not an assumption.

**Idempotency.** Already present and must be preserved. `stopVpn:966–1003` is resource-aware:
`beginTeardown(resourcesLive = !teardownComplete)` (`:977`) returns `false` only when
`isStopping && !resourcesLive`, and the re-entrant branch emits `NOOP_REENTRANT` (`:999`) and
returns without touching native state. `NativeLifetimeRegistry.confirmTeardown`
(`:187–194`) is one-shot per attempt id; `confirmTeardownWithoutLease` (`:205–211`) covers the
no-controller case. **Do not add a second release path.**

**`attemptReconnect` cancellation must propagate.** `nativeConnectWithHub` and `doDhcp` are
blocking JNI calls with no cancellation channel (documented `NativeLifetimeRegistry.kt:50–53`,
`ConnectionController.kt:1174`). Cancellation is cooperative, checked at `:1120`, `:1151`,
`:1162`, `:1242`, `:1298`, `:1316`. The fix must **not** add another `isCancelled.set(false)`
on the terminal path — `:1126` is the single place that clears it, and re-clearing after a
teardown decision re-arms a zombie flow (`performConnect` carries the same hazard, `:365`).
Any new teardown call must set `isCancelled(true)` **before** it, never after.

---

## 7. Lease Consistency (A5)

The registry is fail-closed by design and must not change
(`NativeLifetimeRegistry.kt:44–53`: *"there is deliberately no 'unconfirmed' state; a
reporting deadline that expires does not change the phase and does not release ownership"*).

The lease is released in **exactly one** place — the coordinator's `releaseLease` callback
(`ConnectionController.kt:130–134`), reachable only from `runCleanup()` when `confirmed ==
true` (`NativeAttemptCoordinator.kt:304–306`). `runCleanup` sets `confirmed = true` for
`NothingOutstanding` (`:274`) and for a successful `Claimed` destroy (`:279`), and `false`
for `Lost` (`:297`). Mapping:

| Native outcome | `claimHandle()` | `confirmed` | Lease | Consistent? |
|---|---|---|---|---|
| Handle destroyed cleanly via `claimAndDestroyNativeHandle` → `settleDestroyed()` (`:512`) | `NothingOutstanding` | true | released | Yes |
| `nativeDestroy` threw → `markNativeHandleLost` (`:195`) → `settleLost()` | `Lost` | false | retained | Yes — deliberately |

The R8-VERIFY log pair (`Native cleanup confirmed` at `ConnectionController.kt:896` vs
`NATIVE CLEANUP UNCONFIRMED … NOOP_REENTRANT` at `AndroidConnectionAdapter.kt:193–197`) is
**not** a lease inconsistency — it is two different teardowns (§8).

**Design decision:** the fix must **not** release the lease directly. The only thing missing
after a stranded teardown is that nothing re-drives `destroyResources()` so a *recoverable*
unresolved state becomes released. The existing bounded confirmation at
`SoftEtherVpnService.kt:1063–1066` (`awaitNativeCleanup(NATIVE_CLEANUP_CONFIRM_TIMEOUT_MS)`)
already covers the common case; if it too is unresolved, the next `startVpn` produces the
actionable `notifyNativeBusy`. **No new lease API. No force-release.**

---

## 8. G-2 Interaction (A6)

**Independent. The G-1 fix should not attempt to normalize the vocabulary.**

Source evidence that the two signals describe two different teardowns:

- `ConnectionController.kt:894–899` logs `Native cleanup confirmed: …` from the coordinator's
  `CONFIRMED`/`ALREADY_CONFIRMED` outcome — *this controller's* `destroyResources()` ran and
  its own native structure was freed.
- `SoftEtherVpnService.kt:977` → `:995–1001` emits `NOOP_REENTRANT` when `beginTeardown`
  returned `false` — a *second* `stopVpn` for a session whose teardown already ran.
- `AndroidConnectionAdapter.kt:187–197` correctly interprets the second as "not a native
  confirmation".

The pair is not contradictory; it is under-identified. `TeardownCompletion` already carries
`attemptId`/`sessionId`, and `describe()` renders both — the controller's `Log.d` at `:896`
and `:899` simply omits them. That is the DEEP-REVIEW-VERIFY §10 recommendation, tracked
separately as **FIX-LOG-IDENTITY (G-2)**, P2_MEDIUM.

**Recommendation:** sequence separately. They touch disjoint lines
(`ConnectionController.kt:896`/`:899` vs `:1108–1116`/`:1342–1349`) and cannot conflict, so
they may run in parallel — but folding a diagnostic change into a behavioural teardown fix
would make the G-1 diff harder to verify on device.

---

## 9. Testability (A7)

**JVM-testable (no device):** `SoftEtherSessionPolicy`, `NativeLifetimeRegistry`,
`NativeAttemptCoordinator`, `NativeHandleSlot`, `TunOwnershipCoordinator` are plain Kotlin —
proven by `SoftEtherSessionPolicyTest.kt`, `NativeLifetimeRegistryTest.kt`,
`NativeAttemptCoordinatorTest.kt`, `NativeHandleSingleOwnerTest.kt`,
`TunOwnershipCoordinatorTest.kt`. The Auto Mode side is testable through the injectable
adapter (`AutoModeController.kt:115–119`) with fakes.

**Device-required:** the real `stopVpn` on the main thread, `startForeground`/
`stopForeground`, the `ConnectivityManager` callback, `VpnService.Builder.establish()`, and
the blocking JNI calls — plus any test depending on `onError` actually reaching `stopVpn`.

| # | Test | Kind | Setup | Expected |
|---|---|---|---|---|
| T1 | `reconnectBudgetExhaustion_resetsCounter` | JVM | Drive `attemptReconnect` past `MAX_RECONNECT_ATTEMPTS`; assert `getStatistics().reconnectAttempts`. | Counter is `0` afterwards — a later `connect()` is not born pre-exhausted. |
| T2 | `reconnectFailure_routesThroughServiceTeardown` | JVM (fake service) | Fake collaborator records teardown; force the `:1108` branch. | Exactly one teardown request with `disconnectByError = true`; idempotent on a second call. |
| T3 | `reconnectThrownException_alsoReachesTeardown` | JVM (fake service) | Make `nativeCreate` return `0` inside `attemptReconnect` (the `:1194` throw). | Teardown requested; `connectionJob`/`tunOwnership`/lease not left held; no double-claim of the same handle. |
| T4 | `networkLossThenStart_isNotRefusedConnected` | JVM (policy) | Reconstruct post-G-1 state: `onSessionStart`, `onRunning`, then a full `beginTeardown` + `confirmTeardown`. | `decideStart(resourcesLive = false) == ACCEPT`; with the lease still held, `tryAcquire() == null` → native-busy path, **not** `REFUSE_CONNECTED`. |
| T5 | `nativeBusyRefusal_reachesAutoModeAsActionable` | JVM (AutoMode fakes) | Feed `onSoftEtherNativeBusy(sessionId, owner)` while a SoftEther attempt is armed. | `TunnelStateWatcher.onNativeBusy` fires; Auto Mode reports `ERROR_NATIVE_UNRESOLVED`, **not** `ERROR_NO_SERVER`. |
| T6 | `reconnectUnresolvedLease_retainedNotReleased` | JVM (coordinator) | `requestTeardown` with a flow that never exits; then a second `destroyResources()`. | First returns `UNRESOLVED` and the lease stays held (`isAdmissible() == false`); the second never fabricates `NATIVE_CONFIRMED`. |

**Regression must-haves (already present, must stay green):** `AutoModeLifecycleF28Test.kt`
(incl. `NATIVE_UNRESOLVED must not be readable as a confirmed cleanup`, `:764`),
`TeardownWaiterIdentityTest.kt`, `AutoModeNativeBusyTest.kt`, `AutoModeReconnectTest.kt`.

---

## 10. Failure Modes and Limitations (A8)

1. **Process death during reconnect — NOT fixable, and not needed.** `NativeLifetimeRegistry.process`
   (`:274`) is in-memory; a kill loses the ledger, and the kernel reclaims sockets and TUN.
   There is no cross-process latch. The service is `START_NOT_STICKY` (`:475`), so recovery is
   by a fresh user-visible intent.
2. **A genuinely wedged native call.** If `nativeConnectWithHub` never returns,
   `requestTeardown` times out (`NativeAttemptCoordinator.kt:221–227`) and the lease is
   correctly retained. The app can then only report `ERROR_NATIVE_UNRESOLVED`. Not fixable in
   Kotlin — the native calls have no cancellation channel. The design's contribution is only
   that the app now says so instead of appearing to be `REFUSE_CONNECTED` forever.
3. **`synchronized(this)` + `reconnectJob`** (`ConnectionController.kt:1081–1090`) is not made
   atomic with the registry by this design. `isReconnecting.getAndSet(true)` (`:1098`) makes a
   concurrent second call a safe no-op, but the ordering is not made *provable* here.
4. **`onTrafficUpdate` remains deliberately session-unscoped** (`SoftEtherVpnService.kt:726–735`).
   Out of scope; unchanged.
5. **The device reproduction cannot be confirmed by this design.** 4/4 → 0/4 requires a device.
6. **Telephony-transition false positives.** `onNetworkChanged` is wired to a default network
   callback (`:1569`), so a Wi-Fi↔cellular handover can momentarily report lost. Option A
   routes failures only after the retry budget is exhausted, so this is bounded — but a
   handover during a reconnect-in-flight window (scenario c) can still produce an error
   notification. Worth watching in device verification.

---

## 11. Migration and Rollout (A9)

Paths that must be re-verified:

1. **Start gate** — `SoftEtherVpnService.kt:541–555` (`sessionResourcesLive`, all four
   `StartDecision` branches, log strings `:547`, `:549`, `:551`).
2. **Native-slot claim** — `:577–603`, incl. `notifyNativeBusy` at `:601`.
3. **`onSessionStart` call site** — `:626` (sole clearer of `isRunning`/`wasConnected`/`isStopping`).
4. **`stopVpn` in full** — `:923–1101`, esp. the re-entrant branch `:975–1003` and
   `mayStopServiceFor(tearingDown)` `:1095`.
5. **Connect coroutine terminal branches** — cancellation `:782–865`, generic catch `:866–905`,
   `onError` `:703–725`, obsolete notices `:778–780`.
6. **`onDestroy`** — `:482–515` (unconditional `tunOwnership.releaseAll()` + `NonCancellable`
   `destroyResources()`).
7. **`attemptReconnect` cancellation checkpoints** — `:1120`, `:1151`, `:1162`, `:1242`,
   `:1298`, `:1316`, plus `finally` `:1350–1359`.
8. **`disconnect()`** — `:750–816`, if its terminal-path role changes.
9. **Auto Mode consumers** — `AndroidConnectionAdapter.disconnect()` `:150–200`;
   `TunnelStateWatcher.onSoftEtherStateChanged` `:313–324` and `onSoftEtherNativeBusy`
   `:300–304`; `AutoModeController.startConnectedWatcher` `:552–590` and the
   `ERROR_NATIVE_UNRESOLVED` branches `:1432`, `:1477`.
10. **`GlobalVpnTracker`** — `:126–147` (`onLost` → `TunnelStateWatcher.onTunnelLost` +
    `ACTION_DISCONNECT`), `:206–234` (`onSoftEtherStateChanged`).

Tests to re-run: `SoftEtherSessionPolicyTest`, `NativeLifetimeRegistryTest`,
`NativeAttemptCoordinatorTest`, `NativeHandleSingleOwnerTest`, `TunOwnershipCoordinatorTest`,
`AutoModeLifecycleF28Test`, `AutoModeNativeBusyTest`, `AutoModeReconnectTest`,
`TeardownWaiterIdentityTest`.

---

## 12. Open Questions

1. **Which half of G-1 is in scope?** Budget branch (`:1108`) only, or also the generic catch
   (`:1342`)? On source evidence the catch is **more** severe — it never reaches `stopVpn` at all.
2. **Include `reconnectAttempts.set(0)` on the terminal path?** It is shared with `connect()`'s
   retry loop (`:258`, `:261`), so a surviving controller is permanently pre-exhausted. Fixing
   it changes `getStatistics().reconnectAttempts` semantics (`:954`). Include or defer?
3. **Does the controller call the service directly, or keep routing via `onError` (`:724`)?**
   This is the one architectural choice with real blast radius; it determines whether
   `disconnect()` at `:1114` is removed or retained.
4. **Is Option B (a distinct `ERROR_NETWORK_LOST` state) in scope now, or a follow-on?** Better
   UX; needs new strings in `values/`, `values-fa/`, `values-vi/`.
5. **Is a device available for follow-up verification?** G-1 was 4/4 reproducible; the design
   cannot be validated as fixed without one. Every prior phase forbade device access.
6. **Should FIX-LOG-IDENTITY (G-2) run before, after, or in parallel with FIX-G1-IMPL?** §8
   argues independent and parallel-safe.

---

## 13. Evidence Taxonomy

### SOURCE CODE EVIDENCE

- `SoftEtherVpnService.kt` — `:309` `controller`; `:323` `tunOwnership`; `:340–350`
  `networkCallback` (`onLost` → `onNetworkChanged(false)`); `:482–515` `onDestroy`; `:518`
  `connectionJob`; `:520` `startVpn`; `:541–543` `sessionResourcesLive`; `:544–555` decision +
  refusals; `:577–603` `tryAcquire`/`notifyNativeBusy`; `:605–607` stale-running clear; `:626`
  `onSessionStart`; `:636` `tunOwnership.claim`; `:686–725` closures; `:751–752` `onRunning`;
  `:775–780`; `:782–865`; `:866–905`; `:923–1001`; `:1012–1013`; `:1023–1035`; `:1041–1099`;
  `:1448–1483`; `:1472–1475`; `:1569` default-network registration.
- `ConnectionController.kt` — `:74` `MAX_RECONNECT_ATTEMPTS = 2`; `:83` `isCancelled`; `:94`
  `connectInFlight`; `:118` `nativeHandleSlot`; `:120–149` coordinator wiring (`releaseLease`
  `:130–134`, `destroyNative` `:136–147`); `:150` `reconnectAttempts`; `:195–205`
  `markNativeHandleLost`; `:217–224` `onNetworkChanged`; `:233–294` `connect()` (incl. `:275–277`);
  `:362–446` `performConnect`; `:475–520` teardown/claim/destroy; `:600–624`; `:725–745`
  `reconnect()`; `:750–816` `disconnect()`; `:823–924` `destroyResources()` (`:835`, `:893–912`,
  `:916–923`); `:932–933` `awaitNativeCleanup`; `:1076–1091` `triggerReconnect`; `:1097–1360`
  `attemptReconnect` (`:1108–1116`, `:1126`, `:1148`, `:1181–1195`, `:1242–1246`, `:1298–1303`,
  `:1316–1328`, `:1333`, `:1342–1349`, `:1350–1359`).
- `NativeLifetimeRegistry.kt` — `:44–53` fail-closed doctrine; `:91–106` `Phase`; `:148–157`
  `tryAcquire`; `:187–194` `confirmTeardown`; `:205–211` `confirmTeardownWithoutLease`; `:214`
  `isAdmissible`; `:220–222` `activePhase`; `:240–247` `describe`; `:274` `process`.
- `SoftEtherSessionPolicy.kt` — `:41–49` flags; `:81–86` `onSessionStart`; `:99`
  `isCurrentSession`; `:109` `mayStopServiceFor`; `:133–148` `StartDecision`; `:186–191`
  `decideStart`; `:217` `shouldTearDownOnError`; `:231–236` `beginTeardown`; `:267` `shouldDeliver`.
- `GlobalVpnTracker.kt` — `:31–47` status + `isBusy`; `:113–154` watcher (`onLost` at
  `:126–147`); `:204` `isBusy`; `:206–234` `onSoftEtherStateChanged`.
- `NativeAttemptCoordinator.kt` — `:89–101` `TeardownOutcome`; `:134` `isDestroyed`;
  `:188–191` `beginFlow`; `:200–240` `requestTeardown`; `:248–251` `awaitCleanupConfirmed`;
  `:271–312` `runCleanup`; `:315–325` `describe`.
- `TeardownCompletion.kt` — `Outcome` enum; `nativeCleanupConfirmed`; `of`; `foreignStackTerminal`;
  `isSequenced`.
- `TunnelStateWatcher.kt` — `:250–267` `deliver`; `:288–290` `onSoftEtherTeardownComplete`;
  `:300–311` `onSoftEtherNativeBusy`; `:313–324` `onSoftEtherStateChanged`.
- `AndroidConnectionAdapter.kt` — `:150–200` teardown (`NATIVE CLEANUP UNCONFIRMED` `:193–197`);
  `:209–225` `armTunnelWatch`.
- `AutoModeController.kt` — `:149` `disconnect()`; `:306` `ERROR_NATIVE_UNRESOLVED`; `:552–590`
  `startConnectedWatcher`; `:579–580` tracker confirmation; `:912–937` `disconnectNow`;
  `:1432`, `:1477`.
- `DEEP_REVIEW_VERIFY_REPORT.md` — §10 G-1 (`:206`), G-2 (`:210–220`), §11 V5/V6 (`:230–231`),
  §13 follow-ons (`:256`, `:258`).

### INFERENCE

- That `onError` at `:1113` is what carries the budget branch to the service's `stopVpn`
  (`:724`) — read off the callback closure at `:703–725`; no log line states it.
- That the shared `reconnectAttempts` field produces the "born pre-exhausted" consequence —
  derived from `:150`, `:258`, `:261`, `:1108`, `:1339`.
- That a retained-lease `REFUSE_CONNECTED` differs from the `tryAcquire == null` refusal only
  in consumerlessness — derived by comparing `:546–547` with `:601` and
  `TunnelStateWatcher:300`.
- The relative severity of the `:1342` catch vs the `:1108` budget branch — derived from the
  absence of an `onError` call in the catch.

### UNKNOWN

- Whether the device 4/4 took the budget branch or the exception branch. The quoted logs do
  not distinguish them.
- Whether `nativeConnectWithHub` returns non-zero for a network-down condition rather than
  blocking to timeout — determines which branch dominates in practice.
- Whether the `synchronized(this)` guard at `:1081` is sufficient in all interleavings; no
  lock-order analysis across `connectionMutex`, `scope` and the coordinator's `lock` exists.
- The exact timing interaction between `GlobalVpnTracker`'s 500 ms delayed `onLost` handler
  and `attemptReconnect`'s `RECONNECT_DELAY_MS = 1000L`.

---

## 14. Explicit No-Change Confirmation

| Target | Changed |
|---|---|
| Source (`.kt`/`.c`/`.h`/`.cpp`) | **NONE** |
| Test | **NONE** (none created, none modified, none run) |
| Gradle | **NONE** |
| CMake | **NONE** |
| Device | **NONE** (no `adb`, no install, no launch, no interaction) |
| Build | **NONE** (no Gradle invocation of any kind) |
| Commit / tag / push / release | **NONE** |

HEAD remains `071462816b1eef7e7f7459483fcd69909938cd11`.
Only `Read` and read-only shell inspection (`ls`, `wc`, `grep`, `sed -n`, `tail`) were used.

---

## 15. Final Recommendation

**DESIGN NEEDS USER REVIEW** — specifically §12 items **1, 2, 3, 4, 5**.

The design is complete and implementable. It is not `DESIGN PARTIAL` because the core
recommendation (Option A + counter reset) does not depend on the answers. Three open
questions are **scope** decisions that would materially change the diff:

1. Scope: budget branch only, or also the `:1342` catch — which I believe is the more severe half?
2. Whether `reconnectAttempts.set(0)` is included, given it is adjacent but arguably separate.
3. Whether the controller calls the service directly, or keeps routing through `onError` —
   the one architectural choice with real blast radius.

Once 1–3 are answered, this converts to **PROCEED TO IMPLEMENTATION (FIX-G1-IMPL)** for
Option A. Items 4–6 can be answered in parallel and do not block.
