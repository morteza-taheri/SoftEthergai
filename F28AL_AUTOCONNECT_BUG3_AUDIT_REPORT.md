# F.28AL-AUTOCONNECT-BUG3-AUDIT — Root-Cause Audit of Timeout Plumb Failure

**Date:** 2026-10-12
**Phase:** F.28AL-AUTOCONNECT-BUG3-AUDIT
**Mode:** READ-ONLY. No source/test/Gradle/CI changes, no rebuild, no device interaction, no commit.
**Trigger:** F.28AL-AUTOCONNECT-BUG-VERIFY §9 established that the user timeout never reaches the native race (`race timeout 30000 ms` despite a 5 s / 14 s setting; `nativeSetOption` absent from logcat).

---

## 1. Executive Verdict

**ROOT CAUSE IDENTIFIED — HYPOTHESIS BACKED BY SOURCE**

`SoftEtherClient.setTimeout()` guards on the client's **own private `nativeHandle` field**, which is **never assigned a non-zero value anywhere in the codebase**. The caller (`ConnectionController`) publishes its handle to a **different field** — `SoftEtherClient.externalHandle` — and only *after* `nativeConnectWithHub()` returns, i.e. after `setTimeout()` has already been called. At the moment `setTimeout()` runs, `SoftEtherClient.nativeHandle == 0L`, so the guard is false and the method **silently returns without calling `nativeSetOption`**. Hence:

- No `nativeSetOption called: …` log line, no `Set timeout to …` log line (JNI never entered).
- `conn->timeout_ms` keeps its init default of `30000` (`softether_protocol.c:962`).
- The parallel race reads that untouched default (`softether_protocol.c:2194`) and reports `30000 ms`.

This is a **client-state** root cause, not a JNI/protocol/ordering defect. The BUG 3 fix in `AndroidConnectionAdapter.connectSoftEther()` is correct in itself (the config field carries the right value), but the value is dropped at the `SoftEtherClient.setTimeout()` guard.

The same latent defect **also affects `setHalfConnection()`**, which uses the identical bare-`nativeHandle` guard and is called at `ConnectionController.kt:578` and `:1209`.

---

## 2. A1 — Full Call Chain

Normal AutoMode SoftEther connect path (verified against source; each hop anchored):

| # | Transition | Anchor |
|---|---|---|
| 1 | `AutoModeController.runAutoMode()` → `adapter.connect(candidate, protocol)` | `AndroidConnectionAdapter.kt:82` |
| 2 | `connect()` → `connectSoftEther(conn, useTcp)` | `AndroidConnectionAdapter.kt:96-97` (inside `withContext(Dispatchers.Main)` at :94) |
| 3 | read setting: `val attemptTimeoutMs = dataUtil.getAutoModeTimeoutSeconds().toLong() * 1000` | `AndroidConnectionAdapter.kt:449` |
| 4 | `val config = ConnectionConfig(… connectTimeoutMs = attemptTimeoutMs.toInt() …)` | `AndroidConnectionAdapter.kt:451-474` (**field set at :467**) |
| 5 | `startForegroundCompatible(intent, …)` with `ACTION_CONNECT` + `EXTRA_CONFIG` | `AndroidConnectionAdapter.kt:477-481` |
| 6 | `SoftEtherVpnService` receives `ACTION_CONNECT` → reads `EXTRA_CONFIG` → `sessionPolicy.decideStart()` → `NativeLifetimeRegistry.tryAcquire()` → constructs `ConnectionController` | `SoftEtherVpnService.kt` (start path) |
| 7 | `controller.connect()` → `performConnect()` | `ConnectionController.kt:~370-380` (prelude), body from :384 |
| 8 | `val created = client.nativeCreate()` | `ConnectionController.kt:424` |
| 9 | `flow.publishHandle(created)` | `ConnectionController.kt:427` |
| 10 | **controller** `nativeHandle = created` | `ConnectionController.kt:428` |
| 11 | `performConnectInner()` | `ConnectionController.kt:433` |
| 12 | **`client.setTimeout(config.connectTimeoutMs)`** ← **the fix site** | `ConnectionController.kt:530` |
| 13 | `client.nativeConnectWithHub(nativeHandle, …)` | `ConnectionController.kt:583-603` |
| 14 | *(after connect returns)* `client.externalHandle = nativeHandle` | `ConnectionController.kt:626` |

**Critical observation at step 7/12:** `client` is a *separate object* from the controller — `private val client = SoftEtherClient()` at `ConnectionController.kt:82`, and it is the **only** `SoftEtherClient` instantiation in the codebase (verified by grep). Step 10 sets the **controller's** `nativeHandle` (declared `private var nativeHandle: Long` at `ConnectionController.kt:179`); step 12's `setTimeout` reads the **client's** `nativeHandle` (`SoftEtherClient.kt:13`). These are two different fields on two different objects.

No bypass of `performConnectInner()` was found: `performConnect()` calls it unconditionally at :433 after `nativeCreate()`.

---

## 3. A2 — `SoftEtherClient.setTimeout()` Body

Verbatim (`SoftEtherClient.kt:136-145`):

```kotlin
    /**
     * Set connection timeout
     *
     * @param timeoutMs Timeout in milliseconds
     */
    fun setTimeout(timeoutMs: Int) {
        if (nativeHandle != 0L) {
            nativeSetOption(nativeHandle, OPTION_TIMEOUT, timeoutMs.toLong())
        }
    }
```

Answers to the specific questions:

- **Does it guard on `nativeHandle != 0L`?** **YES.** The `if (nativeHandle != 0L)` guard is the outermost statement.
- **Is the guard reachable with a valid handle on the normal path?** **NO.** On the normal AutoMode path the client's private `nativeHandle` is **0L** at call time (see A3), so the body is skipped.
- **What is passed to `nativeSetOption`?** `option = OPTION_TIMEOUT` (constant `1`, `SoftEtherClient.kt:239`), `value = timeoutMs.toLong()` (a `Long`). Signature `external fun nativeSetOption(handle: Long, option: Int, value: Long)` at `SoftEtherClient.kt:185` — correctly matched by the JNI symbol `Java_vn_unlimit_softether_client_SoftEtherClient_nativeSetOption(JNIEnv*, jobject, jlong, jint, jlong)` at `softether_jni.c:456`.
- **Is this `nativeHandle` the same field as `ConnectionController.nativeHandle`?** **NO — this is the highest-priority finding.** `SoftEtherClient.nativeHandle` (`SoftEtherClient.kt:13`, `private var nativeHandle: Long = 0`) is a **private field of the client object**. `ConnectionController.nativeHandle` (`ConnectionController.kt:179`, `private var nativeHandle: Long`) is a **private field of the controller object**. The controller sets only its own field (line 428 / 1187). The client's own field is **never** set to a handle.

---

## 4. A3 — `SoftEtherClient.nativeHandle` Analysis

`SoftEtherClient` declares **two** handle-like fields:

| Field | Declaration | Visibility | Purpose |
|---|---|---|---|
| `nativeHandle` | `SoftEtherClient.kt:13` — `private var nativeHandle: Long = 0` | **private** | Client-owned legacy handle |
| `externalHandle` | `SoftEtherClient.kt:18` — `@Volatile var externalHandle: Long = 0` | public | *"External handle set by ConnectionController when it manages the native connection directly"* |

**Where is the private `nativeHandle` set?** Grep for `nativeHandle\s*=` across the whole `SoftEtherClient/src/main/java` tree returns exactly one assignment **inside the client**:
- `SoftEtherClient.kt:54` — `nativeHandle = 0` (inside `disconnect()`), a **reset to zero**.

There is **no** `nativeHandle = <created>` anywhere in `SoftEtherClient.kt`. `nativeCreate()` is declared `external fun nativeCreate(): Long` (`SoftEtherClient.kt:148`) and **just returns the handle to the caller** — it does not assign it to `nativeHandle`. So the client's private field **stays 0 for the lifetime of the object** on the AutoMode path (and on every path that goes through `ConnectionController`).

Assignments at `ConnectionController.kt:428` and `:1187` (`nativeHandle = created`) target the **controller's** field (`:179`), not the client's.

**Does `performConnectInner` depend on the client's field?** `performConnectInner` calls `client.setTimeout(config.connectTimeoutMs)` (`:530`) — which depends on the client's private field and therefore no-ops. Note the surrounding code passes `nativeHandle` (the *controller's*) explicitly to every other native call (`client.setClientMac(nativeHandle, mac)` at :543, `client.nativeSetOption(nativeHandle, …)` at :549/:555, `client.nativeConnectWithHub(nativeHandle, …)` at :583) — precisely because the controller *knows* the client can't infer it. The two calls that rely on the client's implicit field are `setTimeout` and `setHalfConnection`.

**Methods that correctly fall back to `externalHandle`** (`val handle = externalHandle.takeIf { it != 0L } ?: nativeHandle`): `send` (:65), `sendSlice` (:77), `receive` (:89), `receiveBatch` (:103), `getStats` (:114). These work post-connect because `externalHandle` is published at `ConnectionController.kt:626`.

**Methods that do NOT fall back** (bare `nativeHandle`): `setTimeout` (:142), `setHalfConnection` (:30), `getAllSocketFds` (:39), `disconnect` (:47). Of these, `setTimeout` and `setHalfConnection` are **called on the connect path** and both silently no-op.

This confirms the "silent no-op" mechanism precisely: `setTimeout` guards on `client.nativeHandle`, which is 0 because the controller set the *controller's* field and the *external* field — but never the client's private field.

---

## 5. A4 — Native Slot Busy Path Analysis

**Not a contributing cause of the observed 30000 ms.** The evidence chain is decisive and points elsewhere:

1. `SoftEtherVpnService` short-circuits **before** creating a `ConnectionController` when `NativeLifetimeRegistry.tryAcquire()` returns null (documented by the `NATIVE_BUSY` outcome machinery, `AndroidConnectionAdapter.kt:282-318`). In that case **no** `ConnectionController` exists, so no `setTimeout` is called and no `nativeConnectWithHub` is called.
2. However, the logcat evidence in §9 of the VERIFY report shows `nativeCreate called` and `nativeConnectWithHub called (useTcp=1)` **did** occur, and the race line `Parallel race: 3 transports …` was emitted — meaning a native connect genuinely ran on the connection in question.
3. Therefore the observed race **does** belong to a live, controller-driven connection, whose `setTimeout` was called at `ConnectionController.kt:530` and silently no-op'd.

So the busy path is a real but **orthogonal** behaviour: it can refuse a start, but it cannot explain a race that actually executed with 30000 ms while a controller-managed `setTimeout` had run.

---

## 6. A5 — Second setTimeout Site (`ConnectionController.kt:1192`)

- **Path:** the **reconnect** flow (`performReconnect`/inner reconnect), which re-runs `nativeCreate()` at `:1185`, assigns the **controller's** handle at `:1187`, then calls `client.setTimeout(config.connectTimeoutMs)` at `:1192`.
- **Same value?** YES — it passes the same `config.connectTimeoutMs` field emitted by the adapter at `AndroidConnectionAdapter.kt:467`.
- **Same defect?** YES — `client.setTimeout` consults `SoftEtherClient.nativeHandle` (still 0), so this site no-ops identically. It is the *same* bug, not a second one.
- **Does it explain the observed behaviour?** Not as an independent cause: the observed race occurred on an **initial** attempt (the adapter's `connectSoftEther` path, `ConnectionController.kt:433 → :530`), not necessarily a reconnect. But the two sites are symmetric and both require the same fix. Note `client.setHalfConnection(!reconnectFullDuplex)` at `:1209` is likewise a no-op.

**Native ordering confirmation (no override):** grep for `timeout_ms\s*=` across all native C/H shows the ONLY assignments to `conn->timeout_ms` are:
- `softether_protocol.c:962` — `conn->timeout_ms = 30000;` (init default)
- `softether_jni.c:469` — `conn->timeout_ms = (int)value;` (JNI OPTION_TIMEOUT)

No native code sets `conn->timeout_ms` to 30000 **after** the JNI setter would have run (hypothesis H5 excluded). The race reads `conn->timeout_ms` at `softether_protocol.c:2194` (`uint32_t race_timeout_ms = conn->timeout_ms;`), floors at 5000 (`:2195`), and logs it at `:2263`.

---

## 7. A6 — `nativeSetOption` Log Verification

Verbatim (`softether_jni.c:456-484`):

```c
JNIEXPORT void JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeSetOption(
    JNIEnv *env, jobject thiz, jlong handle, jint option, jlong value) {
    LOGD("nativeSetOption called: option=%d, value=%ld", option, (long)value);

    if (handle == 0) {
        LOGE("Invalid handle");
        return;
    }

    softether_connection_t* conn = (softether_connection_t*)handle;

    switch (option) {
        case 1: // OPTION_TIMEOUT
            conn->timeout_ms = (int)value;
            LOGD("Set timeout to %d ms", conn->timeout_ms);
            break;
        …
    }
}
```

Conclusions:

- **Unconditional?** **YES.** `LOGD(...)` at line 458 is the **first executable statement**, before the `if (handle == 0)` early return. There is **no** `#ifdef DEBUG`/`#ifndef NDEBUG` around it.
- **Tag:** `TAG` = `"SoftEtherJNI"` (`softether_jni.c:8`), macro `#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)` (`:9`) — the same tag used throughout the JNI file, and `ANDROID_LOG_DEBUG` is emitted by default in this build (the VERIFY phase observed other `SoftEtherJNI`/`SoftEtherProtocol` DEBUG lines).
- **Early return before the log?** **NO.** The only early return (`handle == 0`) sits *after* the log line.
- **Secondary log:** `LOGD("Set timeout to %d ms", conn->timeout_ms)` at `:470` inside `case 1` is likewise unconditional.

**Therefore the observation "`nativeSetOption` appears 0 times" is diagnostic, not an artifact: the function was never entered.** This is exactly what the `setTimeout` guard predicts (A2/A3).

---

## 8. A7 — Root-Cause Hypothesis

**Chosen candidate: H2 (invoked on a different handle field than the connection's).**

Precisely: `SoftEtherClient.setTimeout()` reads `SoftEtherClient.nativeHandle` (private, permanently 0), while `ConnectionController` publishes the live handle to `SoftEtherClient.externalHandle` (and only after `nativeConnectWithHub` returns). The call is therefore a **silent no-op at the client guard**, and `conn->timeout_ms` is never set.

Supporting evidence (SOURCE CODE EVIDENCE):

| Fact | Anchor |
|---|---|
| `SoftEtherClient` has two fields: private `nativeHandle` and public `externalHandle` | `SoftEtherClient.kt:13`, `:18` |
| `setTimeout` guards on the **private** `nativeHandle` only | `SoftEtherClient.kt:142` |
| The private `nativeHandle` is assigned **only** `= 0` (in `disconnect`) — never a real handle | `SoftEtherClient.kt:54` (sole in-file assignment; grep-verified) |
| `nativeCreate()` merely returns; it does not store into `nativeHandle` | `SoftEtherClient.kt:148` |
| The controller has its **own** `nativeHandle` field | `ConnectionController.kt:179` |
| The controller assigns its own field, not the client's | `ConnectionController.kt:428`, `:1187` |
| The controller publishes `externalHandle` only **after** connect returns | `ConnectionController.kt:626` |
| `setTimeout` is called **before** connect, at :530 | `ConnectionController.kt:530` |
| JNI setter logs unconditionally at entry → its absence proves non-entry | `softether_jni.c:458` |
| Native default `conn->timeout_ms = 30000` is the only value left | `softether_protocol.c:962` |
| Race reads that field verbatim and logs it | `softether_protocol.c:2194`, `:2263` |

**Rejected alternatives:**
- **H1 (not invoked on the normal path)** — partially true in *effect*, but the invocation *does* occur (`ConnectionController.kt:530`); the failure is inside the callee's guard. Recorded as H2 for precision rather than H1.
- **H3 (native-slot-busy bypass)** — rejected: the race in question actually executed on a controller-driven connection (§5).
- **H4 (JNI case mismatch)** — rejected: the symbol and signature match (`softether_jni.c:456` vs `SoftEtherClient.kt:185`), and `case 1` handles `OPTION_TIMEOUT`; the entry log would fire regardless.
- **H5 (later override to 30000)** — rejected: only two writers of `conn->timeout_ms` exist; neither is a post-setTimeout override (§6).

**Confidence: HIGH.** The defect is fully determined from source alone; no device evidence is required to establish it (the VERIFY-phase logcat independently corroborates it: `nativeSetOption` = 0 occurrences while a native race ran).

---

## 9. A8 — Fix Direction (design only, no code)

The smallest correct design is to make `setTimeout` (and `setHalfConnection`) use the **same handle-resolution rule** the sibling methods already use. Two viable designs:

- **Option D1 (minimal, recommended): unify the handle source inside `SoftEtherClient`.**
  Change the two offending methods to resolve the handle the same way `send`/`receive`/`getStats` do:
  `val handle = externalHandle.takeIf { it != 0L } ?: nativeHandle` (or, equivalently, add an explicit `handle: Long` parameter and have `ConnectionController` pass its own `nativeHandle`, matching how every other native call in `performConnectInner` already passes it explicitly).
  This is a **1-file** change (`SoftEtherClient.kt`) and needs no signature ripple.

- **Option D2 (explicit, larger): give `setTimeout`/`setHalfConnection` a handle parameter.**
  `fun setTimeout(handle: Long, timeoutMs: Int)` and pass `nativeHandle` from the controller at both call sites (`:530`, `:1192`; `:578`, `:1209`). More explicit, but touches `ConnectionController` (see A9 for the constraint).

- **Do NOT** rely on moving the `setTimeout` call later (e.g. after `externalHandle` is set at :626): that is **incorrect** — the timeout must be applied *before* `nativeConnectWithHub` initiates the race, so it cannot be deferred past :583.

A regression test should assert that after `setTimeout` is invoked on a client with a published handle, `nativeSetOption(OPTION_TIMEOUT, …)` is reached (or, at minimum, a source/behavioural test that `setTimeout` does not no-op when only `externalHandle` is set).

---

## 10. A9 — Ripple Effect Assessment

If the root cause is H2, the smallest-scope fix is **Option D1**:

| Aspect | Assessment |
|---|---|
| **Files that must change (D1)** | `SoftEtherClient/src/main/java/vn/unlimit/softether/client/SoftEtherClient.kt` (only). |
| **Files that must change (D2)** | `SoftEtherClient.kt` **and** `ConnectionController.kt` (2 call sites for `setTimeout`, 2 for `setHalfConnection`). |
| **Tests to update** | Any unit test asserting `SoftEtherClient.setTimeout` behaviour or the `connectTimeoutMs` plumbing source-shape test (`AutoModeEndToEndTest.connectSoftEtherPlumbsAttemptTimeoutMs`). A new test for "setTimeout reaches native when only `externalHandle` is set" should be added. |
| **Forbidden files touched?** | D1 touches **none** of the forbidden set. D2 would touch **`ConnectionController.kt`**, which is on the forbidden list (NativeHandleSlot, NativeLifetimeRegistry, SoftEtherVpnService, ConnectionController, native code). **Prefer D1.** |
| **Native code** | **Untouched** under both designs — the JNI setter and protocol code are already correct. |
| **Blast radius** | Very low: `externalHandle` is only non-zero after a successful connect, so the unified resolver is a strict superset of the current behaviour and cannot regress the legacy own-handle path. |

---

## 11. Evidence Taxonomy

**SOURCE CODE EVIDENCE (directly read this phase):**
- `SoftEtherClient.kt:13,18,30,39,47,54,65,77,89,103,114,141-145,148,185,239`
- `ConnectionController.kt:82,118,179,424-433,476,522-530,543-583,626,748,822,1185-1192,1209,1273`
- `AndroidConnectionAdapter.kt:82-107,413-487` (esp. `:449`, `:451-474`, `:467`, `:477-481`)
- `softether_jni.c:8-9,456-484`, and grep of all `timeout_ms =` assignments
- `softether_protocol.c:962,1550,1664,1857,1960,2042,2125,2194-2195,2204-2252,2263,2283`
- `ConnectionConfig.kt:42,83,125`
- Grep results: `SoftEtherClient()` instantiated once; `nativeHandle =` assignments enumerated.

**INFERENCE:**
- The 30000 ms observed on device is the untouched `softether_protocol.c:962` default (inferred from the two-writer proof plus the §9 logcat absence of the JNI setter).
- `setTimeout` was invoked (at `:530`) but no-oped at the guard (inferred from source flow; corroborated by the VERIFY logcat showing `nativeCreate`→`nativeConnectWithHub` with nothing between).

**UNKNOWN:**
- None material to the root cause. (The only device-side unknown — whether a *different* build had a differently-guarded `setTimeout` — is excluded: HEAD is unchanged at `07146281…` and no source was edited.)

---

## 12. Explicit No-Change Confirmation

- **Source:** NONE
- **Test:** NONE
- **Gradle:** NONE
- **CI:** NONE
- **Device:** NONE
- **Rebuild:** NONE
- **Commit:** NONE
- **Files edited:** NONE

`git rev-parse HEAD` = `071462816b1eef7e7f7459483fcd69909938cd11` (unchanged from P2). `find` over `SoftEtherClient/src` and `app/src` for `.kt` files modified after 15:40 returned **no results**. All work this phase was read-only inspection.

---

## 13. Final Recommendation

**PROCEED TO FIX PHASE (with the identified root cause).**

- **Root cause:** `SoftEtherClient.setTimeout()` guards on the client's private `nativeHandle`, which is never assigned a real handle; the controller publishes the live handle to `externalHandle` (and only post-connect). Result: a silent no-op; `conn->timeout_ms` stays at the 30000 ms default.
- **Recommended fix design:** Option **D1** (unify handle resolution inside `SoftEtherClient` to `externalHandle.takeIf { it != 0L } ?: nativeHandle`, or pass the handle explicitly) — 1 file, no forbidden-file involvement, no native changes.
- **Suggested phase name:** `F.28AL-AUTOCONNECT-BUG3-FIX`.
- **Note for the fix phase:** the same defect silently disables **duplex-mode selection** (`setHalfConnection` at `:578`/`:1209`), so the fix should cover both methods and a regression test should cover both.

---

*End of report — F.28AL-AUTOCONNECT-BUG3-AUDIT.*
