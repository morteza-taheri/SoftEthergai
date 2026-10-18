# FIX-CONNECTING-HANG-AND-PROTOCOL-DEFAULTS — Report

**Phase:** `FIX-CONNECTING-HANG-AND-PROTOCOL-DEFAULTS`
**Date:** 2026-10-13
**HEAD:** `fa8a53d20d4bc8ba1e233d473c1c7723cbadb71f`
**origin/main:** `fa8a53d20d4bc8ba1e233d473c1c7723cbadb71f`
**Branch:** `main`
**Language:** English only.

---

## 1. Executive Verdict

```
CODE-ONLY. FIX-B (CONNECTING soft-bound) and FIX-C (Kotlin-side wedged-attempt cleanup) IMPLEMENTED. FIX-A (protocol default) BLOCKED — PREMISE INVALID; NO CHANGE MADE. DEVICE VERIFICATION DEFERRED.
```

Two of the three sub-fixes are implemented and statically verified; the third is **not implementable as specified** and is reported rather than guessed at (§4).

| Sub-fix | Status |
|---|---|
| **FIX-A** — default protocol priority | **BLOCKED — premise invalid** (§4). No file changed. |
| **FIX-B** — CONNECTING soft-bound | **IMPLEMENTED** (§5). Two distinct constants, two distinct labels, neither bound interferes with the other. |
| **FIX-C** — Kotlin-side wedged-attempt cleanup | **IMPLEMENTED** (§6). Triggers only on the wedged-CONNECTING condition; otherwise a no-op. |

Evidence labels used: **SOURCE CODE / TEST / PHYSICAL DEVICE / INFERENCE / UNKNOWN**.

---

## 2. Operator Evidence Re-Summary

Provided by the operator (quoted verbatim):

> **E1** — Manual mode is HEALTHY. Operator tested manual connect/disconnect with no problem. Native stack is healthy; the bug is specific to the AutoMode / reconnect / concurrent attempts path.
> **E2** — PART 2 (TEARDOWN soft-bound) FIRED correctly at 14:50:46.123 for attempt=2 (held 211,904 ms past deadline), and a new attempt was admitted. TEARDOWN case fixed.
> **E3** — A NEW hang class revealed: attempt=3 stuck in phase=CONNECTING (TLS handshake) since 14:50:46, still stuck at 14:59 — >8 minutes. The TEARDOWN soft-bound does NOT cover CONNECTING holders.

> **New device log (2026-10-13, 14:58:40 → 14:59:24)** — each AutoConnect refused by TWO gates:
> **(a) Kotlin start gate:**
> `14:58:42.163 W SoftEtherVpnService: Start refused: an initial connection is still in flight`
> `14:58:56.186` (retry 2/2) · `14:59:05.923` (second run) · `14:59:19.971` (retry 2/2)
> **(b) Native slot gate (observed by AutoMode retry logic):**
> `14:58:47.128 [AUTO] Native slot busy; retrying same server in 2s (attempt 1/2) (holder=attempt=3 phase=CONNECTING)`
> `14:59:01.182 [AUTO] Native slot still owned by an unresolved operation (attempt=3 phase=CONNECTING); stopping without blaming this server`
> The Kotlin refusal fires BEFORE the native registry is consulted, because `connectionJob` still points at the wedged attempt=3.
> Protocol default STILL WRONG: `[AUTO] Protocols (priority) = SOFTETHER_TCP > SOFTETHER_UDP` (missing MS_SSTP; wrong order).
> Connection timeout = 5 s (correct; preserve). Servers available = 105; Reachable selected = 76.

> **Note:** the brief refers to "E1–E5"; only **E1–E3** were supplied. E4/E5 were not provided and are not summarised here.

Evidence class: **PHYSICAL DEVICE**.

---

## 3. Analysis

### 3.1 The two gates (SOURCE CODE — confirmed)

The observed refusal is produced by **two independent gates**, in this order:

1. **Kotlin start gate** — `SoftEtherVpnService.startVpn()`, lines ~620-640:
   ```
   val sessionResourcesLive =
       controller != null || tunOwnership.isHeld() || connectionJob != null
   val decision = sessionPolicy.decideStart(resourcesLive = sessionResourcesLive)
   ...
   StartDecision.REFUSE_CONNECTING -> Log.w(TAG, "Start refused: an initial connection is still in flight")
   ```
   `decideStart` (`SoftEtherSessionPolicy.kt:186-191`): `!resourcesLive → ACCEPT`; else `isStopping → REFUSE_TEARDOWN`, `isRunning → REFUSE_CONNECTED`, `else → REFUSE_CONNECTING`. So a non-null `connectionJob` alone produces the REFUSE_CONNECTING message — **before the registry is consulted**.

2. **Native slot gate** — `NativeLifetimeRegistry.process.tryAcquire()`, lines ~645-680, reached only if gate (1) passed. Its refusal is `"Start refused: native slot still held by an unresolved attempt (…)"` and is surfaced to AutoMode as the NATIVE_BUSY retry.

### 3.2 Why the TEARDOWN fix alone does not cover CONNECTING

The PART 1 / PART 2 soft-bound (`forceReleaseIfTimedOutLocked`) fires **only** when `deadlineExpired && activePhase == TEARDOWN && teardownRequestedAtMs != 0 && elapsed ≥ 90 s`. A holder wedged in CONNECTING (E3: `attempt=3 phase=CONNECTING`, TLS handshake) satisfies **none** of those: it never reached TEARDOWN, so `markTeardownRequested` was never called and no deadline expired. The TEARDOWN bound therefore correctly declines to fire — and the slot stays held forever.

Worse, even if the native slot *were* released, the user would **still** be refused, because gate (1) reads `connectionJob`, a **Kotlin-side** reference that the native registry does not own and cannot clear. This is the gap FIX-C closes. **Both fixes are required**: FIX-B releases the native slot, FIX-C releases the Kotlin gate — and they are evaluated on the same `startVpn()` call, in that order (Kotlin gate first, then `tryAcquire`).

### 3.3 Why manual mode is healthy (INFERENCE)

Manual connect/disconnect goes through `stopVpn()`, which clears `connectionJob`/`controller`/`tunOwnership` **synchronously** and dispatches native cleanup. The wedged state only arises when a blocking JNI connect never returns **while the Kotlin attempt remains live** — the AutoMode / reconnect / concurrent-attempt path. Manual mode therefore never accumulates a non-null `connectionJob` past a stop, which is consistent with E1.

### 3.4 The protocol-filter concern is NOT the cause of the refusal (explicit)

The operator's observation that the priority list is `SOFTETHER_TCP > SOFTETHER_UDP` is **valid as a design topic**, but it is **NOT the cause of the observed refusal**. The refusal is produced by the Kotlin start gate reading `connectionJob`, which is independent of which protocols are configured. Per the log, the AutoMode run selected server #1 (`vpn333205800`) and the connect was refused at the Kotlin gate **before any protocol check was reached** — the priority list never entered the decision. See §4 for the separate FIX-A finding.

---

## 4. FIX-A — BLOCKED (premise invalid; no change made)

### 4.1 What the brief assumed

STEP 2 directs: *"Identify the exact expression that returns the default priority list when no user setting exists. Introduce a named constant (suggested: `DEFAULT_PROTOCOL_PRIORITY`) … Replace the current default expression with this constant."* Candidate files suggested: `AutoModeEngine.kt` or `AutoModeProtocol.kt`.

### 4.2 What the code actually shows (SOURCE CODE — exhaustive)

- **There is no default protocol priority list.** `DataUtil.getAutoModeProtocolPriority()` (`DataUtil.kt:365-371`) returns `getStringSetting(SETTING_AUTO_PROTOCOL_PRIORITY, null)?.split(',')…orEmpty()` — i.e. an **empty list** when the setting is absent.
- `AutoModeEngine.kt:63-65` states it explicitly: *"This is the single source of truth for which protocol Auto Connect tries and in what order — **there is no separate 'default protocol' setting anymore.**"*
- The production provider is `AutoModeEngine.kt:67: protocolPriorityProvider = { du.getAutoModeProtocolPriority() }`; `AutoModeController`'s own default is `{ emptyList() }` (`AutoModeController.kt:36-37`).
- `grep DEFAULT_PROTOCOL_PRIORITY` across the repo: **no matches.** No literal 3-protocol priority list exists anywhere in production code.

### 4.3 Why the observed log is NOT a default

The device's own settings file (captured in PART 2) contains:
```xml
<string name="SETTING_AUTO_PROTOCOL_PRIORITY">SOFTETHER_TCP,SOFTETHER_UDP</string>
```
So `SOFTETHER_TCP > SOFTETHER_UDP` is a **persisted user setting** (written by `SettingsScreen.kt:794`), not a default. **Changing a default would not alter this device's log at all** — the explicit setting would continue to override it. FIX-A as described would therefore not produce the operator's desired outcome on the observed device.

### 4.4 Why implementing it anyway is unsafe here

Changing the no-setting path to a default would contradict **frozen** documentation this phase is forbidden to update:
- §6.7 R6 (line 714): *"The default protocol priority is `[SOFTETHER_UDP]`."*
- §6.7 R6 (line 715) and §16.4 rule 4 (line 1675): *"An empty protocol priority list means 'refuse', not 'default.' … must not dial a protocol the user switched off, and must not invent a fallback protocol."*

It would flip a documented safety contract, and the brief explicitly says **"do NOT guess."** The premise (a default expression) does not exist, so the instruction cannot be followed as written.

### 4.5 Options for the operator (choose one; none is applied here)

1. **Contract change (recommended if R1 is firm).** Define the default in the production provider (`DataUtil.getAutoModeProtocolPriority`) as `DEFAULT_PROTOCOL_PRIORITY = [SOFTETHER_UDP, SOFTETHER_TCP, MS_SSTP]`, applied **only when the setting is absent** (so an explicit empty list still refuses, preserving §16.4 rule 4). Requires a **separate spec update** (R6/§16.4) the operator must authorise, since the spec is frozen this phase.
2. **Settings-screen seed only.** Pre-seed the setting on first launch; leaves the "empty = refuse" contract intact but only affects fresh installs.
3. **No code change.** Keep empty = refuse; treat the device's list as a user setting to be changed in Settings (it is the actual cause of the observed log).

**A FIX-A test was not added**, because the test file and the constant both depend on the decision above (and, in the current design, the default lives in `DataUtil`, which is Android-dependent and not a pure-JVM unit-test target — another sign the operator's mental model, a pure-Kotlin default in `AutoModeProtocol`/`AutoModeEngine`, does not match the code).

**→ FIX-A requires an explicit operator decision before any code is written.**

---

## 5. FIX-B — CONNECTING soft-bound (IMPLEMENTED)

**File:** `SoftEtherClient/src/main/java/vn/unlimit/softether/NativeLifetimeRegistry.kt`

### 5.1 Design

| Element | Value / behaviour | Line |
|---|---|---|
| New constant | `NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS = 120_000L` | 495 |
| TEARDOWN constant | `NATIVE_SLOT_FORCE_RELEASE_AFTER_MS = 90_000L` — **unchanged** | 479 |
| New label | `ReleaseOutcome.FORCED_RELEASE_CONNECTING_STUCK` (distinct from `FORCED_RELEASE_AFTER_TIMEOUT`) | 153 |
| New state | `private var connectingEnteredAtMs = 0L` | 203 |
| Stamp | `markNativeEntered()` now also sets `connectingEnteredAtMs = nowMs()` (RESERVED→CONNECTING); return contract (Unit) unchanged | 273-276 |
| Sibling bound | `forceReleaseIfConnectingStuckLocked()` | 435-451 |
| Evaluation | `tryAcquire()`: `forceReleaseIfTimedOutLocked() ?: forceReleaseIfConnectingStuckLocked()` | 248-249 |

The CONNECTING bound fires **iff** `activePhase == CONNECTING` **and** `connectingEnteredAtMs != 0` **and** `nowMs() - connectingEnteredAtMs ≥ 120 s`. It does **not** consult `deadlineExpired` (a CONNECTING holder has no teardown deadline). On fire it logs:
```
[REGISTRY] FORCED_RELEASE_CONNECTING_STUCK: attempt=<id> held in CONNECTING for <ms> ms; native operation may still be running. Releasing slot to unblock new attempt.
```
and sets `lastReleaseOutcome = FORCED_RELEASE_CONNECTING_STUCK`.

### 5.2 The 120 s choice — justified

- Native UDP **parallel-race timeout = 30 s** (`softether_protocol.c:962/2194`); pre-race setup (DNS, TCP connect, NAT-T rendezvous) adds ~10-20 s; a healthy connect is **<5 s** (the device log's attempt=1 returned in 2233 ms).
- **120 s = 4× the race timeout** and ≈4× the worst-case healthy window, so a merely slow-but-progressing connect is never cut off before its own hard deadline; a genuinely wedged holder (E3: >8 min) is reclaimed.
- Deliberately **larger** than the TEARDOWN bound (90 s): a CONNECTING holder has produced **no teardown evidence at all**, so it gets more benefit of the doubt than one already asked to tear down.
- **No alternative is silently adopted; 120 s is the implemented value.**

### 5.3 Independence and non-interference (requirement 3.5 / 3.7)

- The two bounds key off **mutually exclusive phases** (`TEARDOWN` vs `CONNECTING`), so **exactly one** can match a given holder. Neither can be reached by the other's holder.
- The TEARDOWN bound is **unchanged** in constant, outcome label, and triggering conditions. The only edit inside it is a sibling-state cleanup (`connectingEnteredAtMs = 0L`), which does not alter its logic.
- Fail-closed preserved: if neither bound is exceeded, `tryAcquire()` returns `null` exactly as before (line 250).
- **No logging and no blocking call under `lock`**: the bound functions are pure state + a returned string; `tryAcquire` emits the string **after** releasing the lock (line 262).

### 5.4 Tests (written, NOT executed)

**File:** `SoftEtherClient/src/test/java/vn/unlimit/softether/NativeLifetimeRegistryTest.kt` (fake clock + injected log sink from PART 1).

| Test | Asserts |
|---|---|
| `connectingBoundLeavesTheSlotHeldWhileElapsedIsBelowTheBound` | CONNECTING at 119 999 ms → `tryAcquire()` = null; label stays `NORMAL` |
| `connectingBoundForceReleasesASlotStuckInConnectingPastTheBound` | CONNECTING at 120 000 ms → new lease granted; label `FORCED_RELEASE_CONNECTING_STUCK`; the **distinct** log line emitted (and never the TEARDOWN label) |
| `connectingBoundDoesNotFireForATeardownHolder` | TEARDOWN holder at 100 s → released by the **TEARDOWN** bound; outcome is `FORCED_RELEASE_AFTER_TIMEOUT`, **never** `FORCED_RELEASE_CONNECTING_STUCK` |
| `teardownBoundDoesNotFireForAConnectingHolder` | CONNECTING holder at 100 s → null; no log; label stays `NORMAL` |

**Requirement 3.8 — the superseded PART 1 test.** The PART 1 test `softBoundDoesNotFireForAConnectingHolderThatIsNotInTeardown` advanced the clock by 10× the TEARDOWN bound and asserted the slot stayed held. With FIX-B that assertion is **no longer true** (a CONNECTING holder *is* released, by the CONNECTING bound). It is therefore **renamed** to `teardownBoundDoesNotFireForAConnectingHolder`, with the elapsed corrected to 100 s (above the TEARDOWN bound, below the CONNECTING bound) so it isolates the TEARDOWN bound precisely. This is stated here explicitly, as required.

---

## 6. FIX-C — Kotlin-side cleanup of a wedged CONNECTING attempt (IMPLEMENTED)

**File:** `SoftEtherClient/src/main/java/vn/unlimit/softether/SoftEtherVpnService.kt`

### 6.1 Problem statement (also in the code comments)

When `connectionJob` is non-null for an attempt genuinely wedged in CONNECTING, the Kotlin start gate refuses **all** subsequent starts with *"an initial connection is still in flight"*. That refusal is **independent** of the native slot gate; releasing the native slot alone (FIX-B) does not clear the Kotlin reference. Device evidence (14:58:42 / 14:59:05) shows this refusal in practice.

### 6.2 Design

| Element | Behaviour | Line |
|---|---|---|
| New state | `@Volatile private var attemptConnectingEnteredAtMs = 0L` | 536 |
| Stamp | Set when the connect coroutine is launched (attempt begins CONNECTING) | 738 |
| Clear (success) | Cleared inside the `isCurrentSession` branch right after `sessionPolicy.onRunning()` — the attempt is now connected | 848 |
| Clear (teardown) | Cleared in `stopVpn()` alongside `connectionJob = null` | 1112 |
| Clear (abandon) | Cleared in the FIX-C path itself | ~600 |
| Cleanup helper | `abandonWedgedConnectingAttemptIfAny()` | 575-604 |
| Call site | At the **top of `startVpn()`**, immediately **before** `decideStart(...)` | 626 |

The helper returns early unless **all** hold: `attemptConnectingEnteredAtMs != 0` **and** `connectionJob != null` **and** `!sessionPolicy.isRunning` **and** `elapsed ≥ NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS` (the **same** constant as FIX-B, imported from `NativeLifetimeRegistry`). It then logs:
```
[SERVICE] Kotlin attempt abandoned after <ms> ms in CONNECTING; clearing connectionJob/controller/tunOwnership so a new attempt can be admitted.
```
and clears the three resources the gate reads, using the **same primitives `stopVpn` uses**: `tunOwnership.invalidate(session)` + `release(session)`, `connectionJob?.cancel(); connectionJob = null`, `controller = null`. It does **NOT** dispatch a native destroy request — the native operation is already wedged, so `destroyResources()` would only add a second blocking JNI call; the native side is handled by FIX-B on the `tryAcquire()` that follows.

### 6.3 Correctness safeguards

- **No background timer / scheduled task.** Evaluated only on the `startVpn()` path, symmetric with FIX-B being evaluated only on the `tryAcquire()` path. It fires exactly when a new attempt asks to start — the only moment the wedged attempt harms the user.
- **Never abandons a live connected session.** Two independent guards: (i) the stamp is cleared at `onRunning()`, and (ii) an explicit `if (sessionPolicy.isRunning) return`. `isRunning` is set on a successful connect, so a connected session is never touched.
- **Same constant as FIX-B.** `NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS` (120 s) — no divergent value.
- **No-op otherwise.** If the condition is not met, the helper returns on the first line; the normal flow is unchanged.
- **`stopVpn()` is unchanged** in behaviour (the only edit is clearing the new stamp alongside `connectionJob`). `AutoModeController.kt` and `ConnectionController.kt` are untouched.

---

## 7. What Remains UNKNOWN

1. **WHY the TLS handshake wedges.** Neither FIX-B nor FIX-C addresses the **root cause** — the native connect returning never, mid-TLS-handshake. That needs a separate native-side diagnostic phase (thread dump while wedged, with root/`bugreport` for the stack). FIX-B/C are recovery bounds, not root-cause fixes. Evidence: **UNKNOWN**.
2. **Whether force-release during CONNECTING can recreate the double-`softether_connection_t` condition.** The original registry existed because two native structs could coexist. A CONNECTING force-release admits a new attempt **while the old native call may still be running**. Mitigations: each attempt owns a **distinct** `softether_connection_t` and its own `connect_mutex`, so the new attempt does not contend with the wedged one's mutex; and the alternative (permanent lockout) is strictly worse. **But this is an assessment, not a proof — it is honestly NOT claimed that coexistence cannot happen.** It must be checked on device (e.g. count live native connections after a CONNECTING force-release). Evidence: **INFERENCE**.
3. **Whether AutoMode's protocol filtering (`usableProtocols` / `supports()`) is exercised correctly for servers lacking the selected protocol.** Flagged as a **separate follow-up**, not fixed here. Related to, but distinct from, FIX-A. Evidence: **UNKNOWN**.

---

## 8. Change Inventory

| File | Change | Numstat (cumulative) |
|---|---|---|
| `SoftEtherClient/.../NativeLifetimeRegistry.kt` | **FIX-B**: `FORCED_RELEASE_CONNECTING_STUCK`; `connectingEnteredAtMs`; stamp in `markNativeEntered`; `forceReleaseIfConnectingStuckLocked()`; sibling evaluation in `tryAcquire`; `NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS`. (Carries PART 1's TEARDOWN bound too.) | **+243 / −13** (PART 1 was +153/−11 → FIX-B ≈ +90/−2) |
| `SoftEtherClient/.../SoftEtherVpnService.kt` | **FIX-C**: `attemptConnectingEnteredAtMs`; `abandonWedgedConnectingAttemptIfAny()`; call before `decideStart`; stamp on launch; clear on `onRunning`, `stopVpn`, abandon. | **+106 / −0** |
| `SoftEtherClient/.../NativeLifetimeRegistryTest.kt` | **FIX-B tests**: 3 new + 1 renamed superseder. (Carries PART 1's TEARDOWN tests.) | **+200 / −0** (PART 1 was +106/−0 → FIX-B tests ≈ +94/−0) |
| `SoftEtherClient/.../controller/ConnectionController.kt` | **PART 1 only — NOT touched this phase** (frozen `[DIAG]` logging). | +39 / −2 (unchanged) |

**FIX-A touched no file** (blocked, §4).

---

## 9. Explicit No-Change Confirmation

- **No device command executed.** No `adb`, install, logcat, instrumentation.
- **No test executed.** The FIX-B tests are written but not run.
- **No build executed.**
- **No commit / push / tag.**
- **C5 not executed.**
- **`AutoModeController.kt` unchanged** (verified: `git diff --quiet` clean).
- **`ConnectionController.kt` unchanged this phase** (verified: still PART 1's +39/−2).
- **`PROJECT_SPECIFICATION.md` unchanged** (verified: `git diff --quiet` clean).
- No C/C++/CMake/Gradle/native change. No `git add -A`/`.`. No reports modified.
- `git diff --name-only` = exactly 4 files, all within the authorized set (3 PART-1 files + `SoftEtherVpnService.kt`); **no file outside it**.

---

## 10. Recommendation

1. **Authorize the device-verification phase** for FIX-B + FIX-C (build, install, reproduce a wedged CONNECTING attempt, confirm the `[REGISTRY] FORCED_RELEASE_CONNECTING_STUCK` line and the `[SERVICE] Kotlin attempt abandoned …` line, and confirm the user can then connect).
2. **Decide FIX-A** (§4.5): contract change (with a separate spec update), Settings seed, or no change. Note that FIX-A will **not** change the currently-installed device's behaviour while its persisted `SETTING_AUTO_PROTOCOL_PRIORITY` remains set.
3. **Schedule the native-side root-cause phase** (§7.1) — FIX-B/C are recovery bounds, not the cause fix.
4. On device verification, **explicitly check §7.2** (live native connection count after a CONNECTING force-release).

**Do NOT propose the next phase autonomously. Await operator decision.**

No push, no tag. Report in English only.
