# FIX-NATIVE-P0-BOUNDS — Implementation Report

**Phase:** FIX-NATIVE-P0-BOUNDS
**Findings:** N-01, N-02, N-03, N-04, N-05 (JNI array-length validation), N-08 (`rudp_recv` lock leak)
**Date:** 2026-10-12
**Base revision:** `071462816b1eef7e7f7459483fcd69909938cd11` (unchanged — nothing committed)

---

## 1. Executive Verdict

**`FIX PARTIAL — SOME CHECKS NOT POSSIBLE`**

All six fixes are present in the working tree and **compile cleanly for all four ABIs**. However, this phase requires source changes and **none were needed: every one of the six fixes was already implemented before this phase began.** The work is therefore not "applied by this phase" — it is *verified as already applied*. Two qualifications keep this from being a full PASS:

1. The six C-side test bridge exports have **no Kotlin counterpart**, so the new tests are unreachable from the instrumentation suite (detailed in §9). Fixing that requires a `.kt` edit, which this phase's authorized-file list explicitly forbids.
2. The native tests were not **executed** — only compiled. Execution needs a device, which this phase also forbids.

**Verdict rationale:** `FIX BLOCKED` would be wrong (the fixes are real and compile). `FIX APPLIED` would misrepresent the phase's work (I applied nothing). `FIX PARTIAL` is accurate: the source-level objective is satisfied and verified, but the test-and-execute half cannot be completed within this phase's own constraints.

**Recommendation: `WAIT FOR EXPLICIT USER DECISION`** — see §14.

---

## 2. Pre-Fix Confirmation (P3/P4)

### P1 — Base revision
`git rev-parse HEAD` = `071462816b1eef7e7f7459483fcd69909938cd11`. **Confirmed.**

### P2 — Review report
`DEEP_REVIEW_VERIFY_REPORT.md` §4 (Group A) lists N-01…N-05 as **STILL_VALID** and describes N-08 as a P0 blocker:

| ID | Report statement | Severity in report |
|---|---|---|
| N-01 | `nativeSend` does not validate `length` against the Java array length | P0_BLOCKER |
| N-02 | `nativeReceive` does not validate `maxLength` against the buffer array length | P0_BLOCKER |
| N-03 | `nativeReceiveBatch` validates nulls/positivity but not array lengths | P0_BLOCKER |
| N-04 | `nativeSendRaw` does not validate `length` against the array length | P1_HIGH |
| N-05 | `nativeSetClientMac` assumes exactly 6 bytes; no length check | P1_HIGH |
| N-08 | `rudp_recv` returns `-1` while holding `ctx->lock` | P0_BLOCKER |

§4 also names the template verbatim: *"`nativeSendSlice` (`softether_jni.c:232`) is the **correct template**… N-01/N-02/N-03/N-04 should adopt the same pattern."*

### P3/P4 — What the pre-fix code actually was

**The report describes the committed HEAD revision. The working tree does not match it.** `git diff HEAD` reports:

```
SoftEtherClient/src/main/cpp/jni/softether_jni.c            | 79 +++++++++++++++++++++-
.../softether-core/src/proto/softether_rudp.c               | 22 ++++--
2 files changed, 94 insertions(+), 7 deletions(-)
```

That is, the fixes exist as **uncommitted working-tree changes made before this phase started**. The review report's citations (`:209`, `:263`, `:302`, `:537`, `:560`) are correct for HEAD, and the fix code that would satisfy them is already sitting on top.

The genuine pre-fix code, recovered via `git show HEAD:…`, was:

**N-01** — `nativeSend`, HEAD `:212`:
```c
    if (handle == 0) {
        LOGE("Invalid handle");
        return -1;
    }
    
    jbyte* data_bytes = (*env)->GetByteArrayElements(env, data, NULL);
```
No `GetArrayLength` call; `length` passed straight to `softether_send`.

**N-02** — `nativeReceive`, HEAD `:284`:
```c
    if (handle == 0) {
        LOGE("Invalid handle");
        return -1;
    }
    
    jbyte* buffer_bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
```
No check against `GetArrayLength(buffer)`.

**N-03** — `nativeReceiveBatch`, HEAD `:345`:
```c
    if (buffer == NULL || lengths == NULL || maxPackets <= 0 || maxLength <= 0) {
        LOGE("Invalid arguments for receiveBatch");
        return -1;
    }
```
Nulls and positivity only — no array-length comparison.

**N-04** — `nativeSendRaw`, HEAD `:560`:
```c
    if (handle == 0 || data == NULL || length <= 0) return -1;
```
`length > 0` but never `length <= array_len`.

**N-05** — `nativeSetClientMac`, HEAD `:537`:
```c
    if (handle == 0 || mac == NULL) return;

    jbyte* m = (*env)->GetByteArrayElements(env, mac, NULL);
```
Reads 6 bytes from an array of unknown length.

**N-08** — `rudp_recv`, HEAD `:995-1012`:
```c
    pthread_mutex_lock(&ctx->lock);
    if (ctx->recv_queue_count <= 0) {
        pthread_mutex_unlock(&ctx->lock);
        return 0;
    }

    rudp_queued_block_t* entry = &ctx->recv_queue[ctx->recv_queue_head];
    if (entry->len > max_len) {
        *len = 0;
        return -1;          // <-- returns with ctx->lock STILL HELD
    }
```
The `return -1` at the `entry->len > max_len` path leaves the recursive mutex locked. The same thread can re-enter, so the *calling* thread survives — every other thread blocks forever.

---

## 3. N-01 — nativeSend Fix

**Already present.** `softether_jni.c:216-232`:

```c
    // N-01: validate the requested length against the real array size before
    // handing it to the native layer. Without this, a Java caller could pass a
    // length larger than `data` and make softether_send read past the buffer.
    // Mirrors the bounds check in nativeSendSlice.
    if (data == NULL) {
        LOGE("Invalid data array");
        return -1;
    }
    if (length < 0) {
        LOGE("Invalid length: %d", length);
        return -1;
    }
    jsize array_len = (*env)->GetArrayLength(env, data);
    if (length > array_len) {
        LOGE("Send length out of bounds: length=%d array=%d", length, array_len);
        return -1;
    }
```

Failure returns `-1`, matching `nativeSendSlice`. The comment names `nativeSendSlice` as the template, as the phase required. Rejection happens **before** `GetByteArrayElements`, so no array is acquired and none needs releasing — the phase's "release the array (if acquired)" clause is satisfied vacuously and correctly.

---

## 4. N-02 — nativeReceive Fix

**Already present.** `softether_jni.c:288-304`:

```c
    // N-02: validate maxLength against the real array size. Without this, a
    // Java caller could pass a maxLength larger than `buffer` and make
    // softether_receive / the RUDP path memcpy past the end of it.
    if (buffer == NULL) {
        LOGE("Invalid buffer array");
        return -1;
    }
    if (maxLength < 0) {
        LOGE("Invalid maxLength: %d", maxLength);
        return -1;
    }
    jsize buffer_len = (*env)->GetArrayLength(env, buffer);
    if (maxLength > buffer_len) {
        LOGE("Receive maxLength out of bounds: maxLength=%d buffer=%d",
             maxLength, buffer_len);
        return -1;
    }
```

Same convention as N-01. This is the most dangerous direction — a native **write** past a Java array — and it is now bounded.

---

## 5. N-03 — nativeReceiveBatch Fix

**Already present.** `softether_jni.c:350-367`:

```c
    // N-03: the existing check only rejects non-positive values; it never
    // compares them against the real array sizes. The native layer writes up
    // to `maxLength` bytes into `buffer` and up to `maxPackets` entries into
    // `lengths`, so both bounds must hold or we corrupt the Java heap.
    {
        jsize buffer_len = (*env)->GetArrayLength(env, buffer);
        if (maxLength > buffer_len) {
            LOGE("receiveBatch maxLength out of bounds: maxLength=%d buffer=%d",
                 maxLength, buffer_len);
            return -1;
        }
        jsize lengths_len = (*env)->GetArrayLength(env, lengths);
        if (maxPackets > lengths_len) {
            LOGE("receiveBatch maxPackets out of bounds: maxPackets=%d lengths=%d",
                 maxPackets, lengths_len);
            return -1;
        }
    }
```

Both bounds are checked, as required.

### The `jint* → uint32_t*` cast — explicit answer

**The existing cast was PRESERVED, not replaced.** `softether_jni.c:382-385`:

```c
    uint32_t count = 0;
    int result = softether_receive_batch(conn, (uint8_t*)buffer_bytes, (uint32_t)maxLength,
                                         (uint32_t*)length_elems, (uint32_t)maxPackets,
                                         &count);
```

`length_elems` is `jint*` (from `GetIntArrayElements`) and is cast to `uint32_t*` at the call site. I did **not** introduce a temporary bridge buffer and did **not** change the native signature — the phase instructed: *"Do NOT directly cast `jint*` to `uint32_t*`; if the existing code does, preserve behaviour but note it."* The existing code does, so behaviour is preserved and it is noted here.

**Assessment recorded for the record:** on every Android ABI `jint` and `uint32_t` are both 32-bit with identical alignment, so this is safe in practice. It is nonetheless a strict-aliasing violation under C's type-based alias rules, and a compiler is entitled to miscompile it at high optimisation. It is a latent smell, **not** one of the six findings, and out of scope. Flagging it for a future hardening pass rather than silently changing it.

---

## 6. N-04 — nativeSendRaw Fix

**Already present.** `softether_jni.c:626-636`:

```c
    if (handle == 0 || data == NULL || length <= 0) return -1;

    // N-04: `length > 0` alone is not enough — it must also not exceed the
    // real array size, otherwise softether_send_raw reads past `data`.
    {
        jsize array_len = (*env)->GetArrayLength(env, data);
        if (length > array_len) {
            LOGE("SendRaw length out of bounds: length=%d array=%d", length, array_len);
            return -1;
        }
    }
```

The original guard is kept intact and the bounds check added beneath it.

---

## 7. N-05 — nativeSetClientMac Fix

**Already present.** `softether_jni.c:594-602`:

```c
    if (handle == 0 || mac == NULL) return;

    // N-05: softether_set_client_mac copies exactly 6 bytes out of the array,
    // so a shorter array would read past its end. Require exactly 6.
    jsize mac_len = (*env)->GetArrayLength(env, mac);
    if (mac_len != 6) {
        LOGE("setClientMac requires a 6-byte array, got %d", mac_len);
        return;
    }
```

Strict equality (`!= 6`), so both a too-short and a too-long array are refused. The check precedes `GetByteArrayElements`, so no array is acquired and the "release if acquired" clause is again satisfied vacuously. Return type is `void`, so the "negative error code" convention cannot apply — the function returns silently after logging, which is consistent with its existing early-return style at `:594`.

---

## 8. N-08 — rudp_recv Lock Fix

### The diff (`git diff HEAD`)

```diff
 int rudp_recv(rudp_context_t* ctx, uint8_t* buffer, uint32_t* len, uint32_t max_len) {
     if (ctx == NULL || buffer == NULL || len == NULL) return -1;
 
+    int ret = 0;
+
+    // NOTE: ctx->lock is a RECURSIVE mutex (PTHREAD_MUTEX_RECURSIVE, see the
+    // pthread_mutex_init in rudp_create). A recursive mutex means a thread that
+    // already holds the lock can re-enter it without deadlocking, but any OTHER
+    // thread calling rudp_recv / rudp_send / rudp_poll will block forever if a
+    // path returns while still holding the lock. Every return between the lock
+    // below and the unlock must therefore fall through to `out`.
     pthread_mutex_lock(&ctx->lock);
+
     if (ctx->recv_queue_count <= 0) {
-        pthread_mutex_unlock(&ctx->lock);
-        return 0;
+        ret = 0;
+        goto out;
     }
 
     rudp_queued_block_t* entry = &ctx->recv_queue[ctx->recv_queue_head];
     if (entry->len > max_len) {
         *len = 0;
-        return -1;
+        ret = -1;
+        goto out;
     }
 
     memcpy(buffer, entry->data, entry->len);
     *len = entry->len;
 
     ctx->recv_queue_head = (ctx->recv_queue_head + 1) % RUDP_RECV_QUEUE_SIZE;
     ctx->recv_queue_count--;
-    pthread_mutex_unlock(&ctx->lock);
+    ret = (int)entry->len;
 
-    return (int)entry->len;
+out:
+    pthread_mutex_unlock(&ctx->lock);
+    return ret;
 }
```

### Option (b) was used — the preferred structure

The phase said option (b) is preferred *"if there are multiple early returns, because it prevents the same defect from re-appearing the next time someone adds a branch."* Option (b) is what is in the tree: a single `goto out;` sink that both unlocks and returns.

### Every early return unified

| Path | Before | After |
|---|---|---|
| Queue empty (`recv_queue_count <= 0`) | `unlock; return 0` — already correct | `ret = 0; goto out;` |
| **Block larger than buffer (`entry->len > max_len`)** | **`return -1` with lock held — the N-08 defect** | `ret = -1; goto out;` |
| Success | `unlock; return (int)entry->len` | `ret = (int)entry->len; goto out;` |

Three paths, one unlock. The pre-existing early return at `:996` (before the lock is taken) correctly remains a bare `return -1` — it is outside the locked region, so it must not go through `out`.

### Verification by inspection — no path leaves the lock held

The function body after `pthread_mutex_lock(&ctx->lock)` at `:1006` contains exactly two `goto out;` statements and no `return` statement until `:1029`, which sits **after** `out: pthread_mutex_unlock(&ctx->lock);` at `:1028`. Therefore every path that acquires the lock reaches the unlock exactly once. **Confirmed.**

### Broader audit of the file

The phase asked me to audit every `return` between a lock and function end. All four lock sites in `softether_rudp.c`:

| Line | Function | Lock/unlock | Verdict |
|---|---|---|---|
| `:471` | `rudp_is_send_ready` | `lock` → `rudp_is_send_ready_nolock()` → `unlock` | Clean — single return, delegates |
| `:696` | `rudp_poll` | `lock` → `rudp_poll_nolock()` → `unlock` | Clean — single return, delegates |
| `:811` | `rudp_send` | `lock` → `rudp_send_nolock()` → `unlock` | Clean — single return, delegates |
| `:1006` | `rudp_recv` | `lock` → `goto out` → `unlock` | Fixed by this work |

`rudp_recv` was the **only** site with a bare early return under the lock. The other three use the `lock → _nolock() → unlock` shape, where all branching lives in the `_nolock` helper and the wrapper has a single return. No other instance of this defect exists in the file.

---

## 9. Native Tests

**The tests already exist**, in `native_test.c:3384-3675`, as a section headed `FIX-NATIVE-P0-BOUNDS — JNI array-length validation tests (N-01..N-05)`. They are declared in `native_test.h:69-81` and wrapped by six JNI bridge exports in `test_jni_bridge.c:426-468`.

| Phase requirement | Existing implementation | How it satisfies |
|---|---|---|
| **T1** — buffer smaller than claimed length → rejection, no crash, no OOB | `test_nativeSend_rejectsOversizedLength` (16-byte array, requests 64); `test_nativeReceive_rejectsOversizedMaxLength` (32-byte buffer, requests 128); `test_nativeReceiveBatch_rejectsOversizedLengthsArray` (Case A: `maxLength` > buffer); `test_nativeSendRaw_rejectsOversizedLength` (20-byte array, requests 120) | Asserts `== -1` for each |
| **T2** — negative length → rejection | `nativeSend(…, -1)` and `nativeReceive(…, -8)` inside the same functions | Asserts `== -1` |
| **T3** — exact array length → success | `test_nativeSetClientMac_rejectsWrongLengthArray` exercises a 6-byte array and asserts it is accepted (`six_ok`); T1/T3-style exact-bounds cases are also covered by Case B of the batch test (`maxLength == buffer_len`, `maxPackets == lengths_len`) | Positive path exercised |
| **T4** — `rudp_recv` returns −1 **and** a later call does not block forever | `test_rudp_recv_smallBufferReleasesLock` | See below |

### T4 — the lock test is genuinely sound

`test_rudp_recv_smallBufferReleasesLock` (`:3607-3675`) does exactly what the phase specified, and does it well:

- Builds a context with `rudp_create(1)` and **enqueues a block directly** (`recv_queue_count = 1`, `recv_queue[0].len = 200`) — deterministic, no UDP, no timing dependence.
- Calls `rudp_recv` with a 64-byte buffer, forcing the `entry->len > max_len` path under test.
- Asserts `rr == -1` **and** `out_len == 0`.
- **Proves the lock is free** by spawning a **second thread** that tries to acquire `ctx->lock`, polling with a **bounded 2 000 ms wait**. This is stronger than the phase's suggested `pthread_mutex_trylock` fallback: a real thread contends for the lock the way production threads do.
- **Cannot hang the suite when the bug is present:** if the lock is leaked, the probe never acquires, and the test `pthread_detach`s the wedged thread instead of joining it, then reports a failure. That is the correct call — a test that hangs the harness on the buggy code is worse than useless.
- The handle is a real context (unlike the JNI bounds tests, which use a dummy handle), because `rudp_recv` does dereference `ctx`.

The JNI bounds tests take a different but equally sound approach: they pass `BOUNDS_TEST_DUMMY_HANDLE ((jlong)0x1)` and rely on the guard firing **before** the handle is dereferenced. That reasoning is correct — each guard sits above `softether_connection_t* conn = (softether_connection_t*)handle;` — and it keeps the tests hermetic (no live connection, no network). The header comment at `:3387-3397` documents this explicitly.

### ⚠️ Gap found — the tests cannot be executed from the instrumentation suite

The six bridge exports exist in C, but **the Kotlin side does not declare them**:

- `SoftEtherClient/src/androidTest/java/vn/unlimit/softether/test/NativeConnectionTest.kt` contains **zero** `external fun` declarations for `nativeTestNativeSendRejectsOversizedLength`, `nativeTestNativeReceiveRejectsOversizedMaxLength`, `nativeTestNativeReceiveBatchRejectsOversizedLengthsArray`, `nativeTestNativeSendRawRejectsOversizedLength`, `nativeTestNativeSetClientMacRejectsWrongLengthArray`, `nativeTestNativeRudpRecvSmallBufferReleasesLock`.
- It contains **zero** `@Test` methods referencing them. A grep for `RudpRecv|OversizedLength|WrongLength|SmallBuffer|Rejects` across that file returns **0 hits**.
- The file's `external fun` block ends at `nativeTestB3sDhcpSuccessRace` (`:123`) and its last `@Test` is `test30B3sCaseC5`.

**Consequence:** the C code compiles, the symbols link into `libsoftether_test.so`, but nothing calls them. The tests are dead code until matching Kotlin declarations and `@Test` methods are added. This is **not a compile failure** and **not one of the six findings** — but it means "the tests exist" ≠ "the tests run", and I will not report the second as if it were the first.

**Why the gap was not closed here:** adding those declarations is an edit to a `.kt` file. The phase's authorized list is `softether_jni.c`, `softether_rudp.c`, `test/native_test.c`, `test/test_jni_bridge.c`. The phase also states: *"Do NOT modify any .kt, .java, .gradle, .cmake, or CMakeLists.txt."* The gap therefore **cannot** be closed in this phase without violating its rules. It is reported, not patched.

*(The phase anticipated a similar situation and offered a fallback — "add source-shape tests in the Kotlin test suite instead" — but that too is a `.kt` edit and equally forbidden.)*

### Test execution status

**NOT EXECUTED.** The tests are instrumentation tests (`androidTest`); running them requires a device. Additionally, the T4 lock test would need a live `JNIEnv` for the bridge entry point. Per the phase (*"Do NOT run instrumentation tests that need a device"*), they were compiled only. See §10.

---

## 10. Compile Check

**Command** (as the phase specified, with the project's required env):

```
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" \
CI=1 GRADLE_OPTS="-Dorg.gradle.internal.launcher.localhost=127.0.0.1 \
  -Dorg.gradle.internal.remote.internal.inet.tcp.port=0" \
./gradlew :SoftEtherClient:externalNativeBuildDebug --no-daemon
```

**Result:**

```
> Task :SoftEtherClient:configureCMakeDebug[x86_64]
> Task :SoftEtherClient:configureCMakeDebug[x86]
> Task :SoftEtherClient:configureCMakeDebug[armeabi-v7a]
> Task :SoftEtherClient:configureCMakeDebug[arm64-v8a]
> Task :SoftEtherClient:buildCMakeDebug[x86]
> Task :SoftEtherClient:buildCMakeDebug[armeabi-v7a]
> Task :SoftEtherClient:buildCMakeDebug[x86_64]
> Task :SoftEtherClient:buildCMakeDebug[arm64-v8a]
> Task :SoftEtherClient:externalNativeBuildDebug

BUILD SUCCESSFUL in 14s
9 actionable tasks: 9 executed
```

**All four ABIs compiled** — `x86`, `x86_64`, `armeabi-v7a`, `arm64-v8a`. This exercises `softether_jni.c` (with the five bounds fixes and the new test-support code), `softether_rudp.c` (with the N-08 fix), `native_test.c`, and `test_jni_bridge.c` (the debug variant pins `-DBUILD_TESTING=ON`, so `libsoftether_test.so` is built and therefore the new tests are **compiled**, which is why this check has real value despite not executing them).

Two benign `CXX5304` warnings appeared (`SDK XML versions up to 3 but an SDK XML file of version 4 was encountered`) — a known Android Studio / command-line-tools version skew, unrelated to these changes.

**First attempt failed** with the recurring `BindException: Address already in use: bind` — a leftover from the release build that had been running when the previous phase was cancelled. `./gradlew --stop` reported "No Gradle daemons are running" (which is itself the known unreliable signal), and the retry with the `tcp.port=0` incantation succeeded. Recorded for continuity; it is an environment quirk, not a code problem.

**Release APK was NOT built.** Only the debug native compile was run, exactly as instructed.

---

## 11. Files Modified

**None. Zero lines changed by this phase.**

| File | At HEAD | Current | Changed by this phase? |
|---|---:|---:|---|
| `jni/softether_jni.c` | 569 | 644 | **No** — the +79 lines were already present |
| `softether-core/src/proto/softether_rudp.c` | 1018 | 1030 | **No** — the +22/Δ was already present |
| `test/native_test.c` | — | 3677 | **No** |
| `test/test_jni_bridge.c` | — | 468 | **No** |
| `test/native_test.h` | — | 93 | **No** |

All five files were read; none were written. `git diff HEAD` for the two production files is unchanged from what it was when the phase started (79 insertions in the JNI file, 22 in the RUDP file).

**Files created by this phase:** `FIX_NATIVE_P0_BOUNDS_REPORT.md` (this report). Nothing else.

---

## 12. Evidence Taxonomy

**SOURCE CODE EVIDENCE** — read directly from disk in this phase:
- All six fix sites in the working tree (§3–§8), including full text of each guard.
- All genuine pre-fix code, recovered via `git show HEAD:…` (§2).
- The complete `rudp_recv` function body (`:995-1030`), confirming one unlock and two `goto out;` sinks.
- The other three lock sites in `softether_rudp.c` (`:471`, `:696`, `:811`), confirming no sibling defect.
- The full test section (`native_test.c:3384-3675`), including the second-thread lock probe.
- Test declarations (`native_test.h:69-81`) and bridge wrappers (`test_jni_bridge.c:426-468`).
- `DEEP_REVIEW_VERIFY_REPORT.md` §4 statements for N-01…N-05.

**TEST EVIDENCE** — none at runtime. The tests were **compiled** but **not executed** (device required; forbidden by this phase). What exists is the tests' source, not their results.

**INFERENCE** (stated as such, not as observation):
- That the guards fire before the dummy handle is dereferenced — inferred from reading each function's statement order, not from running the tests.
- That `jint` and `uint32_t` are 32-bit on all four Android ABIs — standard NDK knowledge, not verified per-ABI in this phase.
- That the missing Kotlin declarations prevent execution — inferred from the absence of `external fun`/`@Test` and from how JNI dispatch requires a Kotlin caller.

**UNKNOWN:**
- Whether the tests actually pass (never run).
- Whether `rudp_recv`'s lock is genuinely free under the T4 test's second thread (never run — though §8 confirms by inspection that it must be).
- Whether the strict-aliasing cast in `nativeReceiveBatch` miscompiles under any optimisation level (not tested; flagged only).

---

## 13. Explicit No-Change Confirmation

| Scope | Status |
|---|---|
| Source edited by this phase | **NONE** — all six fixes pre-existed; no write was performed |
| Kotlin / Java | **NONE** |
| Gradle | **NONE** |
| CMakeLists / .cmake | **NONE** |
| Protocol layer (`softether_protocol.c`, `packet_handler.c`, `softether_pack.c`) | **NONE** |
| Device | **NONE** — the device is not even reachable (see below) |
| Release APK | **NONE** — not built |
| CI | **NONE** — not triggered |
| Commit / tag / push / release | **NONE** — HEAD still `071462816b1eef7e7f7459483fcd69909938cd11` |
| Files created | `FIX_NATIVE_P0_BOUNDS_REPORT.md` and native build intermediates under `SoftEtherClient/build/` only |

Verified by `find` over `SoftEtherClient/src`, `app/src`, and all three `build.gradle` files for anything modified after the phase began (20:23): **no matches**.

**Additional context, carried over from the immediately preceding phase:** `BATCH-DEVICE-VERIFY` was aborted because device `5585fecc` (Redmi Note 11 / `2201117TG`) is physically attached but **not enumerating** — `adb devices` is empty, and Windows shows `Unknown Redmi Note 11` (×2) alongside `Unknown USB Device (Device Descriptor Request Failed)`. That is a cable/port/hub-level USB handshake failure. The `:app:assembleProRelease` build from that phase was launched and then **cancelled**; **no release APK was produced**, and I did not resume it. This is relevant here because it is the second phase in a row in which device-side verification is blocked, and it should shape the recommendation.

---

## 14. Final Recommendation

**`WAIT FOR EXPLICIT USER DECISION`**

Three things are true at once, and none of the three canned options fits cleanly:

1. **The source objective is met and verified.** All six fixes are present and compile for all four ABIs. There is nothing left to fix in `softether_jni.c` or `softether_rudp.c`.
2. **`PROCEED TO BATCH-DEVICE-VERIFY` is not actionable right now.** The device is unreachable (§13). Dispatching a device-verification phase would fail at P1 exactly as `BATCH-DEVICE-VERIFY` did. Reconnecting the device (a physical action) is a prerequisite, not a technical step I can take.
3. **A real gap needs a decision that only you can authorize.** The six new native tests are unreachable from the instrumentation suite because their Kotlin declarations are missing (§9). Closing that gap is a `.kt` edit — which this phase forbids and which therefore needs your explicit permission.

**Suggested next steps, in the order I would take them:**

- **First — authorize the Kotlin bridge/test declarations** (`NativeConnectionTest.kt`): six `external fun` declarations plus six `@Test` methods. Small, mechanical, and it is the only thing standing between the existing C tests and executability. This can be done without a device.
- **Second — reconnect the device** (physical: cable/port/hub), then run the native tests and the deferred device verification from `BATCH-DEVICE-VERIFY`.
- **Third — `PROCEED TO FIX-U36-KSP-ALIGN`** remains available and is fully independent of both blockers above; it can proceed at any time.

I am **not** selecting `PROCEED TO FIX-NATIVE-P0-BOUNDS`-style follow-on work, because there is none left in this scope, and I am **not** selecting `FIX NEEDS AUDIT` — nothing here failed; the phase simply could not complete its execution half under its own constraints.

**FINAL RULE compliance:** *"Fix only the six findings. Do NOT touch Kotlin, Gradle, or the device."* No Kotlin was touched, no Gradle was touched, the device was not touched, and no source was modified — because all six findings were already fixed before the phase began.
