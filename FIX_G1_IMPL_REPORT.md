# FIX-G1-IMPL — Route Reconnect Failure to Service Teardown (Option A)

**Phase:** FIX-G1-IMPL
**Date:** 2026-10-12
**HEAD:** `071462816b1eef7e7f7459483fcd69909938cd11` (unchanged)
**Mode:** Implementation. Source + test edits only.

---

## 1. Executive Verdict

**FIX APPLIED — ALL TESTS PASS**

Both terminal reconnect paths in `ConnectionController.kt` now route through the single
`onError` channel, the reconnect budget is reset on both, and the local `disconnect()`
second channel is removed from the budget branch. `:SoftEtherClient:testDebugUnitTest`
is **140/140 passing** (133 pre-existing + 7 new). `:app:compileProDebugKotlin` succeeds.

---

## 2. Pre-Fix Verification (P3–P4)

**P1 — HEAD confirmed:** `071462816b1eef7e7f7459483fcd69909938cd11`
`feat(ui): add DNS presets, app exception icons and Auto Mode cleanup`

**P2 — design report read:** `FIX_G1_DESIGN_REPORT.md` (28,561 bytes, 15 sections).

**P5 — `REFUSE_CONNECTED` branch confirmed** (`SoftEtherSessionPolicy.kt:186–191`):

```kotlin
fun decideStart(resourcesLive: Boolean): StartDecision = when {
    !resourcesLive -> StartDecision.ACCEPT
    isStopping -> StartDecision.REFUSE_TEARDOWN
    isRunning -> StartDecision.REFUSE_CONNECTED     // <-- the branch a stranded session reaches
    else -> StartDecision.REFUSE_CONNECTING
}
```

**P4 — `onError` channel confirmed** (`SoftEtherVpnService.kt:703–725`, dispatch at `:724`):

```kotlin
onError = { error ->
    if (!sessionPolicy.shouldTearDownOnError(session)) {
        Log.w(TAG, "Ignoring error from obsolete session $session (current ${sessionPolicy.sessionGeneration}): $error")
        return@ConnectionController
    }
    Log.e(TAG, "VPN Error: $error")
    mIsUserDisconnect = false
    stopVpn(disconnectByError = true)        // <-- :724, the single service teardown channel
},
```

### Pre-fix body — budget-exhaustion branch (`ConnectionController.kt:1108–1116`)

```kotlin
            if (reconnectAttempts.incrementAndGet() >= MAX_RECONNECT_ATTEMPTS) {
                Log.e(TAG, "Max reconnection attempts reached")
                // Surface as ERROR (not silent DISCONNECTED) so the UI shows the
                // disconnect-by-error notification and STATE_ERROR broadcast.
                currentState = ConnectionState.ERROR
                onError("Connection lost - max reconnection attempts reached")
                disconnect()
                return
            }
```

### Pre-fix body — generic catch (`ConnectionController.kt:1342–1349`)

```kotlin
        } catch (e: Exception) {
            Log.e(TAG, "Reconnection attempt failed", e)
            // Clean up partial native state left by failed reconnect.
            // Stage 2F-F.28AL-U: single-owner claim, as every cleanup path uses.
            claimAndDestroyNativeHandle()
            // Transition to DISCONNECTED so the activity learns the connection is gone
            currentState = ConnectionState.DISCONNECTED
            onStateChange(ConnectionState.DISCONNECTED)
        } finally {
```

**Confirmed for both:** the budget branch carried a redundant second channel (`disconnect()`);
the catch carried **no service-facing notification at all**. Neither reset the counter.

---

## 3. I1 — Budget Branch Change

**Before** (`:1108–1116`): as quoted in §2.

**After** (`:1108–1136`):

```kotlin
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
```

**`disconnect()` removal — confirmed removed, not retained.** Verification:

- `grep -rn "attemptReconnect\|max reconnection\|disconnect()" SoftEtherClient/src/test/ app/src/test/` → **no test asserts on the call**. Every hit is a comment or an unrelated `AutoModeConnectionAdapter.disconnect()` override.
- `disconnect()` still **exists** as a method (`fun disconnect()` at `:750`) and is still legitimately reached from `reconnect()` at `:733`. Only its use as a *second teardown channel from the budget branch* was removed.

**Ordering:** `reconnectAttempts.set(0)` deliberately precedes `onError(...)`, so the counter is
already clean for any session the resulting teardown starts.

---

## 4. I2 — Catch Branch Change

**Before** (`:1342–1349`): as quoted in §2.

**After** (`:1362–1393`):

```kotlin
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
            // handle down, then propagate). ...
            onError("Reconnect failed: ${e.message}")
        } finally {
```

### Ordering choice and rationale

**Chosen: local cleanup first, `onError` last.**

Three converging reasons:

1. **It matches the existing convention of the other terminal branch.** `performConnect`'s catch
   (`:434–437`) tears the native handle down *and then* rethrows:
   ```kotlin
   } catch (e: Throwable) {
       Log.d(TAG, "Connect flow ended (${e.javaClass.simpleName}), tearing down native handle")
       teardownNativeConnection()
       throw e
   }
   ```
   The `onError` notification is the analogue of the rethrow, so it belongs after cleanup.

2. **The reverse order would create the dual-teardown race the fix is meant to prevent.**
   `onError` reaches `stopVpn(disconnectByError = true)` at `SoftEtherVpnService.kt:724`, which
   *synchronously* snapshots and nulls the service's `controller` (`:1023–1024`) and then
   dispatches `destroyResources()` on a background coroutine (`:1041–1054`). If `onError` ran
   first, this method's own `claimAndDestroyNativeHandle()` on the next line would race the
   service's `destroyResources()` for the same handle — precisely the shape the single-owner
   claim (`NativeHandleSlot`) and the F.28 lifecycle work exist to eliminate.

3. **Correctness is unaffected either way, because the slot is a single-owner claim.**
   `claimAndDestroyNativeHandle()` (`:492–498`) claims atomically and destroys only if it won.
   After the local claim settles, the coordinator's later `claimHandle()` reads
   `NothingOutstanding` (`NativeAttemptCoordinator.kt:274`), `confirmed = true`, and the lease
   is released honestly — the service's teardown reports `NATIVE_CONFIRMED` as a fact rather
   than fabricating it. So ordering affects *which* thread frees the handle, not *whether* the
   lease ends up consistent.

**Rejected alternative (onError first) is recorded here rather than silently dropped.**

---

## 5. Tests Added (T1–T4)

### Test-strategy constraint (important, and it shapes what T1–T4 could become)

`ConnectionController` **cannot be instantiated off-device.** Its constructor takes a concrete
`SoftEtherVpnService` (a final Android `VpnService` subclass) and the class passes that instance
straight to JNI (`service.protect(...)`), plus `ParcelFileDescriptor` for the TUN. This is
already documented by both neighbouring suites:

> `NativeHandleSingleOwnerTest`: *"**No `ConnectionController`.** It cannot be constructed
> off-device (it needs a real `SoftEtherVpnService` and `ParcelFileDescriptor`)."*
> `NativeAttemptCoordinatorTest`: *"…the controller's use of it is a source-level fact."*

`attemptReconnect()` is `private suspend` and `reconnectAttempts` is a private `AtomicInteger`,
so neither is reachable. The phase's authorized-file list permits only `ConnectionController.kt`
and `SoftEtherClient/src/test/**`, which forbids the two ways to make T1–T4 literally
behavioural (extract a service interface, or add a test seam to a non-authorized file).

**Therefore T1–T4 are implemented as source-pinning tests**, following the pattern this
codebase already sanctions for exactly this situation:
`AutoModeLifecycleF28Test.disconnectBodySource()` ("`AndroidConnectionAdapter.disconnect()`
needs a real `Context`, so its body cannot be executed on the JVM. What must be pinned is the
*shape* of the mechanism") and `AutoModeNativeBusyTest` ("…so what is pinned here is the
branch's decision").

New file: **`SoftEtherClient/src/test/java/vn/unlimit/softether/controller/ReconnectTerminalPathTest.kt`**

The harness reads the production source, **strips line/KDoc comments** (so prose can never
satisfy an assertion), slices `attemptReconnect()` between its declaration and the next
top-level `private fun`, and extracts the two terminal branch bodies.

| # | Test name | Approach | Result |
|---|---|---|---|
| T1 | `T1 budget exhaustion resets the reconnect counter` | Asserts `reconnectAttempts.set(0)` is present in the budget branch, and that its index is **strictly before** the `onError(` index. | **PASS** |
| T2 | `T2 budget branch routes through onError and not through local disconnect` | Asserts exactly **one** `onError(` in the branch, carries the terminal message, and that no bare `disconnect()` statement remains (multiline regex). | **PASS** |
| T3 | `T3 generic catch routes through onError and resets the counter` | Asserts the catch contains `onError(`, the `"Reconnect failed:"` message, and `reconnectAttempts.set(0)`. | **PASS** |
| T3b | `T3b catch cleans up before notifying the service` | Asserts `claimAndDestroyNativeHandle()` index is strictly before the `onError(` index — pins the §4 ordering choice. | **PASS** |
| T4 | `T4 both terminal branches are single-channel and never call stopVpn` | Asserts one `onError` per branch, no `stopVpn(` in either, and no `service.stopVpn(`/`service.stopSelf(` anywhere in either. | **PASS** |
| T4b | `T4b terminal branches do not re-clear isCancelled` | Asserts neither branch contains `isCancelled.set(false)` — pins I5. | **PASS** |
| T4c | `T4c local disconnect still exists as an API` | Asserts `fun disconnect()` is still defined — prevents mistaking the call removal for an API deletion. | **PASS** |

**7 new tests, all passing.** (T4 was split into T4/T4b/T4c to cover the three distinct
invariants the single "idempotent" requirement implies; T3b was added to pin the ordering
decision from §4.)

---

## 6. Test Run Result

Command:

```
CI=1 GRADLE_OPTS="-Dorg.gradle.internal.launcher.localhost=127.0.0.1 \
  -Dorg.gradle.internal.remote.internal.inet.tcp.port=0" \
  ./gradlew :SoftEtherClient:testDebugUnitTest --no-daemon
```

`BUILD SUCCESSFUL in 1m 9s`

| Test class | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| `SoftEtherSessionPolicyTest` | 54 | 0 | 0 | 0 |
| `TunOwnershipCoordinatorTest` | 25 | 0 | 0 | 0 |
| `NativeLifetimeRegistryTest` | 22 | 0 | 0 | 0 |
| `NativeAttemptCoordinatorTest` | 20 | 0 | 0 | 0 |
| `NativeHandleSingleOwnerTest` | 8 | 0 | 0 | 0 |
| `SoftEtherClientHandleGuardTest` | 4 | 0 | 0 | 0 |
| **`ReconnectTerminalPathTest` (new)** | **7** | **0** | **0** | **0** |
| **TOTAL** | **140** | **0** | **0** | **0** |

**140 tests / 140 passed / 0 failures / 0 errors / 0 skipped.**

App-module compile check:

```
CI=1 ./gradlew :app:compileProDebugKotlin --no-daemon
BUILD SUCCESSFUL in 52s
```

**Note on the toolchain:** the recurring `java.net.BindException: Address already in use: bind`
(Gradle's file-lock contention port) reappeared and `./gradlew --stop` did **not** clear it,
even with zero `java.exe` processes alive. The reliable workaround used throughout this phase
was `-Dorg.gradle.internal.remote.internal.inet.tcp.port=0` combined with `--no-daemon`.

---

## 7. Regression Result (T5)

All five required `SoftEtherClient` suites pass with the fix in place:

- `SoftEtherSessionPolicyTest` — 54/54 PASS
- `NativeLifetimeRegistryTest` — 22/22 PASS
- `NativeAttemptCoordinatorTest` — 20/20 PASS
- `NativeHandleSingleOwnerTest` — 8/8 PASS
- `TunOwnershipCoordinatorTest` — 25/25 PASS
- (plus `SoftEtherClientHandleGuardTest` — 4/4 PASS; no `ConnectionController`-specific suite
  existed before this phase — the new `ReconnectTerminalPathTest` is the first.)

No pre-existing test was modified.

---

## 8. Evidence Taxonomy

### SOURCE CODE EVIDENCE

- `SoftEtherClient/src/main/java/vn/unlimit/softether/controller/ConnectionController.kt`
  - `:74` `MAX_RECONNECT_ATTEMPTS = 2` (unchanged); `:150` `reconnectAttempts = AtomicInteger(0)` (unchanged).
  - `:258`, `:261` — `reconnectAttempts` shared with `connect()`'s retry loop.
  - `:365`, `:736`, `:1146` — `isCancelled.set(false)`; the `:1146` one is the `attemptReconnect`
    in-progress reset inside the changed function — untouched (I5).
  - `:434–437` — `performConnect`'s catch; the terminal-convention precedent for the ordering choice.
  - `:492–498` `claimAndDestroyNativeHandle` — single-owner atomic claim.
  - `:733` `disconnect()` (inside `reconnect()`); `:750` `fun disconnect()` — both retained.
  - `:1108–1136` — the changed budget branch (was `:1108–1116`).
  - `:1119` — the new counter reset (budget).
  - `:1134` — the retained `onError` (budget).
  - `:1362–1393` — the changed catch (was `:1342–1349`).
  - `:1372` — the new counter reset (catch); `:1393` — the new `onError` (catch).
- `SoftEtherVpnService.kt` (READ ONLY) — `:703–725` `onError` closure, dispatch at `:724`
  `stopVpn(disconnectByError = true)`; `:541–543` `sessionResourcesLive`; `:546–547`
  `REFUSE_CONNECTED`; `:1023–1024` controller snapshot/null; `:1041–1054` async
  `destroyResources` dispatch; `:1063–1066` bounded `awaitNativeCleanup`.
- `SoftEtherSessionPolicy.kt` (READ ONLY) — `:186–191` `decideStart`.
- `NativeAttemptCoordinator.kt` (READ ONLY) — `:274` `NothingOutstanding → confirmed = true`;
  `:304–306` `releaseLease` only when confirmed.
- `SoftEtherClient/src/test/java/vn/unlimit/softether/controller/NativeHandleSingleOwnerTest.kt`
  — the "cannot be constructed off-device" precedent (its header KDoc).
- `app/src/test/java/vn/unlimit/vpngate/automode/AutoModeLifecycleF28Test.kt:789–823`
  — the `disconnectBodySource()` source-pinning precedent.
- `app/src/test/java/vn/unlimit/vpngate/automode/AutoModeNativeBusyTest.kt:408–414`
  — the "pins the branch's decision" precedent.

### TEST EVIDENCE

- `SoftEtherClient/build/test-results/testDebugUnitTest/*.xml` — 7 files, aggregate
  `tests=140 failures=0 errors=0 skipped=0` (parsed from the JUnit XML, not from console text).
- `TEST-vn.unlimit.softether.controller.ReconnectTerminalPathTest.xml` — 7 testcases, all
  without `<failure>`/`<error>` children.
- Gradle console: `BUILD SUCCESSFUL in 1m 9s` (test task), `BUILD SUCCESSFUL in 52s`
  (`:app:compileProDebugKotlin`).
- mtime check: only `ConnectionController.kt` and `ReconnectTerminalPathTest.kt` modified
  after 19:51 today.

### INFERENCE

- That the budget branch previously stranded the session *because* `disconnect()` cannot touch
  `sessionResourcesLive` — derived by comparing `ConnectionController.disconnect()`'s body
  (`:750–816`, which never writes `tunOwnership`/`connectionJob`/`sessionPolicy`) with the gate
  read at `SoftEtherVpnService.kt:541–543`.
- That the counter reset must precede `onError` — derived from the observation that `onError`
  synchronously triggers `stopVpn`, which can start a new session through a fresh
  `onSessionStart`; the ordering is a deliberate design choice, not a compiler requirement.
- That the reverse ordering would race the same handle — derived from the asynchronous
  `destroyResources` dispatch at `:1041–1054` combined with the synchronous controller null at
  `:1023–1024`.
- That `claimAndDestroyNativeHandle()` having already claimed the handle makes the coordinator
  read `NothingOutstanding` — derived from `NativeHandleSlot` + `NativeAttemptCoordinator.kt:274`.

### UNKNOWN

- Whether `onError` actually reaches `stopVpn` at runtime for this path (the
  `shouldTearDownOnError` session-identity gate at `:717` must pass, and no device run was
  performed).
- Whether the 4/4 device reproduction took the budget branch or the exception branch. Both are
  now covered, so the fix is branch-agnostic — but which one dominated in practice remains
  unknown.
- Whether `nativeConnectWithHub` returns non-zero for a network-down condition (budget path) or
  blocks to timeout (exception path). Determines the practical frequency of each branch.
- Whether the exact `REFUSE_CONNECTED` symptom is fully cleared on-device: the fix removes the
  *stranding*, but the residual case is a retained lease producing the actionable
  `notifyNativeBusy` refusal instead — a behaviour change that needs a device to confirm.

---

## 9. Explicit No-Change Confirmation

| Target | Status |
|---|---|
| `SoftEtherVpnService.kt` | **NONE** |
| `NativeLifetimeRegistry.kt` | **NONE** |
| `NativeAttemptCoordinator.kt` | **NONE** |
| `SoftEtherSessionPolicy.kt` | **NONE** |
| `GlobalVpnTracker.kt` | **NONE** |
| `AutoModeController.kt` or any app module file | **NONE** |
| Native code (`SoftEtherClient/src/main/cpp/**`) | **NONE** |
| Gradle / CMake | **NONE** |
| Device | **NONE** (no `adb`, no install, no launch) |
| Release APK | **NONE** (not rebuilt) |
| CI | **NONE** (no workflow dispatched; Gradle run locally only) |
| Commit / tag / push / release | **NONE** |

**Files edited — exactly two:**

1. `SoftEtherClient/src/main/java/vn/unlimit/softether/controller/ConnectionController.kt` (I1 + I2)
2. `SoftEtherClient/src/test/java/vn/unlimit/softether/controller/ReconnectTerminalPathTest.kt` (new)

Verified by `find … -newermt "2026-10-12 19:51"`, which returns only those two paths, and by a
targeted `find` over every forbidden file, which returns nothing. HEAD remains
`071462816b1eef7e7f7459483fcd69909938cd11`.

**Also preserved:** I3 (`stopVpn` untouched), I4 (no new field/constant/type), I5
(`isCancelled.set(false)` untouched), the fail-closed lease policy (no force-release path
added), and no second cleanup channel (no `service.stopVpn`/`stopSelf` call introduced).

---

## 10. Final Recommendation

**PROCEED TO FIX-LOG-IDENTITY (G-2)**

Per user decision Q6, G-2 (`FIX-LOG-IDENTITY`) was deliberately **not** bundled into this phase
and is the natural next step: add `attemptId`/`sessionId` to the controller's
`Log.d(TAG, "Native cleanup confirmed: …")` at `ConnectionController.kt:896`/`:899` so the two
teardown vocabularies (`NATIVE_CONFIRMED` from the controller vs `NOOP_REENTRANT` from
`stopVpn`) correlate in logs. The design report §8 established these as independent, and the
line ranges do not overlap with this phase's edits, so sequencing has no conflict risk.

**Then** device verification. The fix's runtime effect — that `onError` reaches
`stopVpn` and the `REFUSE_CONNECTED` dead-end is gone — is the one thing this phase could not
prove; it is explicitly out of scope (Q5) and remains the outstanding item from the G-1 design.

I am not proceeding to either without an explicit request.
