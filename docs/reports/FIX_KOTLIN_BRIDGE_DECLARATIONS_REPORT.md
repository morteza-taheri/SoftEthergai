# FIX-KOTLIN-BRIDGE-DECLARATIONS — Report

**Phase:** FIX-KOTLIN-BRIDGE-DECLARATIONS
**Goal:** Add the six missing Kotlin `external fun` declarations and six `@Test` methods so the FIX-NATIVE-P0-BOUNDS native tests become reachable from the instrumentation suite.
**Date:** 2026-10-12
**Base revision:** `071462816b1eef7e7f7459483fcd69909938cd11` (unchanged — nothing committed)

---

## 1. Executive Verdict

**`DECLARATIONS ADDED — COMPILE VERIFIED`**

Six `external fun` declarations and six `@Test` methods were added to `NativeConnectionTest.kt`. `:SoftEtherClient:compileDebugAndroidTestKotlin` returned **BUILD SUCCESSFUL in 48s**. Net change: **+137 lines** (570 → 707). No existing test was touched, and no file other than the single authorized one was modified.

One correction to the phase brief is recorded in §5: the declarations belong at **instance level**, not in the companion object — the C exports take `jobject thiz`, not `jclass`. Getting this wrong would have compiled cleanly and failed at runtime, so it is worth stating plainly.

---

## 2. C-Side Signatures (P2/P3)

### P2 — from `native_test.h:68-81`

| # | C helper | Parameters | Return |
|---|---|---|---|
| N-01 | `test_nativeSend_rejectsOversizedLength` | `JNIEnv* env` | `native_test_result_t` |
| N-02 | `test_nativeReceive_rejectsOversizedMaxLength` | `JNIEnv* env` | `native_test_result_t` |
| N-03 | `test_nativeReceiveBatch_rejectsOversizedLengthsArray` | `JNIEnv* env` | `native_test_result_t` |
| N-04 | `test_nativeSendRaw_rejectsOversizedLength` | `JNIEnv* env` | `native_test_result_t` |
| N-05 | `test_nativeSetClientMac_rejectsWrongLengthArray` | `JNIEnv* env` | `native_test_result_t` |
| N-08 | `test_rudp_recv_smallBufferReleasesLock` | `void` | `native_test_result_t` |

### P3 — from `test_jni_bridge.c:426-468`

| # | JNI method name | C receiver | Params (beyond env) | Return | Kotlin method kind |
|---|---|---|---|---|---|
| 1 | `nativeTestNativeSendRejectsOversizedLength` | `jobject thiz` | — | `jobject` | **instance** |
| 2 | `nativeTestNativeReceiveRejectsOversizedMaxLength` | `jobject thiz` | — | `jobject` | **instance** |
| 3 | `nativeTestNativeReceiveBatchRejectsOversizedLengthsArray` | `jobject thiz` | — | `jobject` | **instance** |
| 4 | `nativeTestNativeSendRawRejectsOversizedLength` | `jobject thiz` | — | `jobject` | **instance** |
| 5 | `nativeTestNativeSetClientMacRejectsWrongLengthArray` | `jobject thiz` | — | `jobject` | **instance** |
| 6 | `nativeTestRudpRecvSmallBufferReleasesLock` | `jobject thiz` | — | `jobject` | **instance** |

All six take **no parameters** and all return a `jobject` (which is `NativeTestResult`, built by `create_test_result`). Verified against `NativeTestResult`'s four-arg JNI constructor `(ZILjava/lang/String;J)V`.

---

## 3. Kotlin Declarations Added (I1)

Placed in the **instance-level** `private external fun` block, immediately after the existing `nativeTestB3sDhcpSuccessRace` declaration (`NativeConnectionTest.kt:137-142`):

```kotlin
    // FIX-NATIVE-P0-BOUNDS — JNI array-length validation and RUDP lock tests.
    // These bridge to the C helpers in test/native_test.c (declarations in
    // native_test.h:68-81, wrappers in test_jni_bridge.c:426-468). They are
    // declared here as INSTANCE methods (`external fun`, not in the companion
    // object) because the C exports all take `jobject thiz`, not `jclass` —
    // i.e. JNI resolves them against an instance, so a companion-object
    // declaration would not link.
    //
    // All six are hermetic: none needs a VPNGate server and none needs a live
    // connection. The five bounds tests build deliberately-wrong arrays against
    // a dummy handle; the RUDP test builds its own context internally. See
    // native_test.c:3387-3397 for the C-side rationale.
    private external fun nativeTestNativeSendRejectsOversizedLength(): NativeTestResult
    private external fun nativeTestNativeReceiveRejectsOversizedMaxLength(): NativeTestResult
    private external fun nativeTestNativeReceiveBatchRejectsOversizedLengthsArray(): NativeTestResult
    private external fun nativeTestNativeSendRawRejectsOversizedLength(): NativeTestResult
    private external fun nativeTestNativeSetClientMacRejectsWrongLengthArray(): NativeTestResult
    private external fun nativeTestRudpRecvSmallBufferReleasesLock(): NativeTestResult
```

---

## 4. Kotlin Tests Added (I2)

Appended at the end of the class, continuing the numeric sequence from `test30B3sCaseC5` (`:561`).

| Line | Signature | Body summary |
|---|---|---|
| `:611` | `fun test31NativeSendRejectsOversizedLength()` | Calls `nativeTestNativeSendRejectsOversizedLength()`; asserts `result.success`. |
| `:627` | `fun test32NativeReceiveRejectsOversizedMaxLength()` | Calls `nativeTestNativeReceiveRejectsOversizedMaxLength()`; asserts `result.success`. |
| `:643` | `fun test33NativeReceiveBatchRejectsOversizedLengthsArray()` | Calls `nativeTestNativeReceiveBatchRejectsOversizedLengthsArray()`; asserts `result.success`. |
| `:658` | `fun test34NativeSendRawRejectsOversizedLength()` | Calls `nativeTestNativeSendRawRejectsOversizedLength()`; asserts `result.success`. |
| `:674` | `fun test35NativeSetClientMacRejectsWrongLengthArray()` | Calls `nativeTestNativeSetClientMacRejectsWrongLengthArray()`; asserts `result.success`. |
| `:697` | `fun test36RudpRecvSmallBufferReleasesLock()` | Calls `nativeTestRudpRecvSmallBufferReleasesLock()`; asserts `result.success`. |

Total test methods: **30 → 36**.

Each body follows the exact shape of the hermetic tests 12/13:

```kotlin
    @Test
    fun test31NativeSendRejectsOversizedLength() {
        Log.d(TAG, "Running testNativeSendRejectsOversizedLength")

        val result = nativeTestNativeSendRejectsOversizedLength()
        assertTrue(
            "N-01 nativeSend bounds check failed: ${result.errorMessage} (code: ${result.errorCode})",
            result.success
        )
        Log.d(TAG, "N-01 nativeSend passed: ${result.errorMessage}")
    }
```

**Why `assertTrue(result.success, …)` rather than asserting on the numeric return code.** The phase suggested "rejection with the documented return code". The C helper already computes the full verdict — e.g. N-01 asserts `r_over == -1 && r_neg == -1` internally and folds it into `success`; N-08 asserts `rr == -1 && out_len == 0 && spawned && acquired`. That C-side value is the authoritative result, and the Kotlin `-1` return codes are not surfaced through `NativeTestResult`. Asserting on `success` is therefore both the stronger check and the codebase's own pattern (tests 12/13). Each message still names the finding (`N-01`…`N-08`) so a failure is self-identifying.

---

## 5. Bridge/Handle Pattern (I3/I4)

### I3 — dummy handle

The phase asked me to confirm the dummy-handle pattern from the C source and mirror it. Confirmed: `native_test.c:3399` defines

```c
#define BOUNDS_TEST_DUMMY_HANDLE ((jlong)0x1)
```

and tests 31–35 (`N-01`..`N-05`) pass it as the `handle` argument. This is **entirely C-side** — the Kotlin entry points take no handle parameter, so nothing needed mirroring in Kotlin. The reason a fake handle is safe is documented at `native_test.c:3390-3393`: every entry point validates the array length **before** dereferencing `handle` as a `softether_connection_t*`. I re-verified that ordering while reviewing the guard placement in the previous phase; the guards all sit above the `conn = (softether_connection_t*)handle;` line.

### I4 — the RUDP test's real context

Test 36 (`N-08`) is the exception: `rudp_recv` genuinely dereferences its context, so the C helper creates a real `rudp_context_t` via `rudp_create(1)` and **enqueues a block directly** (`ctx->recv_queue_count = 1`, `recv_queue[0].len = 200`) with no UDP socket and no peer. Per I4 I documented this in the Kotlin KDoc on `test36` rather than adding any live-server setup:

> *"Unlike tests 31-35, this one needs a real `rudp_context_t`, but it does NOT need a server: the C test creates the context via `rudp_create(1)` and enqueues a block directly, so no UDP and no peer are involved. The lock release is proven by a second thread acquiring `ctx->lock` within a bounded 2000 ms wait…"*

### No `assumeNotNull` — a deliberate difference

The existing connection tests open with `assumeNotNull("No VPNGate server available", testServer)` and are skipped when no server is found. The six new tests **deliberately omit it**: they are hermetic and must run in any environment. I stated this in a section comment above `test31` so a future reader does not "fix" the apparent inconsistency:

> *"Because they need neither `testServer` nor shared state, they deliberately do NOT call `assumeNotNull(...)` — unlike the connection tests above, these must RUN even when no server is reachable."*

### Placement correction (the one substantive deviation from the brief)

The phase said the declarations usually go "inside a companion object, after the existing declarations". I did **not** do that. All six C exports take `jobject thiz` — an **instance** receiver — so JNI resolves them against an object instance. A `companion object` declaration would have produced a `jclass` receiver, which **compiles fine** but fails at runtime with `UnsatisfiedLinkError`. I verified the companion object still contains zero `external fun` and placed all six at instance level with the `:104-123` block. The reasoning is recorded in a source comment so the choice is not silently reversed later.

---

## 6. Compile Result

**Command** (the phase's specified task exists):

```
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" \
CI=1 GRADLE_OPTS="-Dorg.gradle.internal.launcher.localhost=127.0.0.1 \
  -Dorg.gradle.internal.remote.internal.inet.tcp.port=0" \
./gradlew :SoftEtherClient:compileDebugAndroidTestKotlin --no-daemon
```

**Exit status: 0 — `BUILD SUCCESSFUL in 48s`**, with `> Task :SoftEtherClient:compileDebugAndroidTestKotlin` shown as executed (20 actionable tasks: 1 executed, 19 up-to-date).

Three warnings appeared, **none from the code added in this phase**:

```
w: .../ThroughputBenchmarkTest.kt:51:34 'fun setHalfConnection(...)' is deprecated.
w: .../ThroughputBenchmarkTest.kt:118:16 'fun setTimeout(...)' is deprecated.
w: .../ThroughputBenchmarkTest.kt:237:15 'fun setTimeout(...)' is deprecated.
```

They are pre-existing deprecation notices in an unrelated file. I did not touch that file.

### What this compile does and does not prove

It proves the Kotlin side is structurally correct: syntax, visibility, receiver placement, parameter list, and that `NativeTestResult` is used consistently with how the declarations are typed. That is a real check — a wrong return type or a misplaced declaration fails here.

It does **not** prove the JNI names match. `external fun` resolves at runtime, so a typo in a native method name would still compile and only fail with `UnsatisfiedLinkError` when the test runs. The names were transcribed character-for-character from `test_jni_bridge.c:426-468` and cross-checked against `grep` output, but **the binding is only truly verified by executing the tests on a device** — which this phase forbids. This is the single most important caveat in this report.

---

## 7. Files Modified

| File | Before | After | Delta |
|---|---:|---:|---|
| `SoftEtherClient/src/androidTest/java/vn/unlimit/softether/test/NativeConnectionTest.kt` | 570 | 707 | **+137** |

**That is the only file modified.** Verified by `find` over `SoftEtherClient/src`, `app/src`, `SoftEtherClient/build.gradle`, and `app/build.gradle` for anything modified after the phase began (20:30) — a single match, the file above.

**Note on `git diff --stat`:** it reports `366 insertions(+), 15 deletions(-)` for this file, which is **misleading**. The file already carried uncommitted changes from an earlier phase; the 15 "deletions" are from *that* pre-existing delta (reindented/moved lines), not from this phase. The 137-line growth measured by `wc -l` before and after is the accurate figure for this phase's contribution, and the diff contains no removal of any test.

---

## 8. Evidence Taxonomy

**SOURCE CODE EVIDENCE** — read directly from disk in this phase:
- All six C helper signatures (`native_test.h:68-81`).
- All six JNI export wrappers, including the `jobject thiz` receiver and `jobject` return (`test_jni_bridge.c:426-468`).
- The full pre-existing `NativeConnectionTest.kt` structure: companion object (`:39-100`), `loadLibrary` calls (`:47-48`), external-fun block (`:104-123`), all 30 pre-existing tests, and the assertion style of the hermetic tests 12/13.
- `NativeTestResult` (`.success`, `.errorCode`, `.errorMessage`) and its four-arg JNI constructor.
- The `BOUNDS_TEST_DUMMY_HANDLE` definition and its safety rationale (`native_test.c:3390-3399`).
- The N-08 C helper's verdict expression (`native_test.c:3656`).
- The post-edit file, re-read from disk.
- The `:app`/`:SoftEtherClient` Gradle output.

**INFERENCE:**
- That instance-level placement is required — inferred from the `jobject`/`jclass` distinction in the C exports, which is standard JNI semantics. Not verified by executing the tests.
- That the six tests would exercise the intended code paths — inferred from reading the C helpers, not from running them.

**UNKNOWN:**
- Whether the JNI names bind correctly at runtime (needs a device; the compile cannot prove this).
- Whether the six tests pass (never run).
- Whether `rudp_recv`'s lock is genuinely free under the second-thread probe at runtime (never run — though it was confirmed by inspection in the previous phase).

---

## 9. Explicit No-Change Confirmation

| Scope | Status |
|---|---|
| C / C++ native | **NONE** |
| Native tests (`native_test.c`, `native_test.h`, `test_jni_bridge.c`) | **NONE** |
| Gradle / CMakeLists | **NONE** |
| Production Kotlin | **NONE** — only `androidTest` source changed |
| Device | **NONE** — not connected; not touched |
| APK rebuild | **NONE** |
| CI | **NONE** |
| Commit / tag / push / release | **NONE** — HEAD still `071462816b1eef7e7f7459483fcd69909938cd11` |
| Files edited | **`NativeConnectionTest.kt` only** |

Additional verifications:

- **I5 — no existing test changed.** The 30 pre-existing test methods are untouched; `test30B3sCaseC5`'s body was diffed against its state before this phase and is identical. The only edit is the insertion of the declaration block and the six new methods.
- **I6 — `loadLibrary` calls untouched.** `System.loadLibrary("softether")` and `System.loadLibrary("softether_test")` remain at `:47-48`, character-for-character.
- **Companion object untouched.** It still contains zero `external fun`.
- **No new file, no new test class.** All additions are inside the existing `NativeConnectionTest.kt`.

---

## 10. Final Recommendation

**`PROCEED TO DEVICE VERIFICATION (BATCH-DEVICE-VERIFY once device reconnects)`**

The gap identified in FIX-NATIVE-P0-BOUNDS is now closed: the six native tests have a Kotlin caller, and the androidTest sources compile. What remains cannot be done in this phase by design — `external fun` binds at runtime, so the only check that can confirm the JNI names resolve and the six assertions actually hold is running them on hardware.

**Prerequisite is physical, not technical:** device `5585fecc` (Redmi Note 11 / `2201117TG`) is still not enumerating — `adb devices` is empty and Windows reports `Unknown Redmi Note 11` (×2) plus `Unknown USB Device (Device Descriptor Request Failed)`. That is a cable/port/hub-level USB fault. Once it reconnects, `BATCH-DEVICE-VERIFY` can proceed and should now include tests 31–36 alongside the G-1/G-2 verification it already covers.

**A cost note for the device run:** tests 31–36 are hermetic and fast (no server, no network), so they can be run **independently of any VPNGate server being reachable** — they will not be skipped by the `assumeNotNull` guards the way the connection tests may be. If the device comes up in an environment with no server access, these six still execute. That makes them a sensible first target on the next device session.

`PROCEED TO FIX-U36-KSP-ALIGN` remains available and fully independent — it needs no device and can be taken at any time, before or after the device work.

**FINAL RULE compliance:** declarations added only; no native code touched; no Gradle touched; no device touched; nothing committed.
