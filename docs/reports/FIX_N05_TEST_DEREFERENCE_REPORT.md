# FIX-N05-TEST-DEREFERENCE — Report

Phase: repair the test35 dummy-handle contradiction found by BATCH-DEVICE-VERIFY.
Mode: **test-only** source edit. No production code, no Gradle, no CMakeLists, no device, no APK rebuild, no commit.
Language: English only.

---

## 1. Executive Verdict

> ## ✅ **FIX APPLIED — READY FOR REBUILD-AND-RETEST**

`test_nativeSetClientMac_rejectsWrongLengthArray` no longer contains any JNI call with a valid-length array. The 6-byte acceptance sub-case — the sole crash path — is removed, and the `pass` expression now depends only on the rejection contract. The native build compiles cleanly for all four ABIs.

---

## 2. Pre-Fix Confirmation (P3)

**P1 — HEAD confirmed:**
```
c5720508a41ee3f46412a108673cae46abfccda0
```
✅ Matches the expected value. The phase is operating on the correct revision.

**P3 — the crash-causing structure was present, exactly as reported.** The three offending pieces, quoted verbatim from the pre-edit file:

**(a) The 6-byte acceptance sub-case (`:3561–3569`) — the crash:**
```c
  // Also exercise the exact-length path so the guard is not rejecting
  // everything: a 6-byte array must be accepted without crashing.
  jbyteArray mac_ok = (*env)->NewByteArray(env, 6);
  bool six_ok = (mac_ok != NULL);
  if (six_ok) {
    Java_vn_unlimit_softether_client_SoftEtherClient_nativeSetClientMac(
        env, NULL, BOUNDS_TEST_DUMMY_HANDLE, mac_ok);
    (*env)->DeleteLocalRef(env, mac_ok);
  }
```

**(b) The `six_ok` term in the `pass` expression (`:3575`):**
```c
  bool pass = (check == bad_len) && six_ok;
```

**(c) The `six_ok` term in the message (`:3578–3581`):**
```c
    snprintf(msg, sizeof(msg),
             "N-05 setClientMac: short array rejected (env healthy=%d), "
             "6-byte accepted=%d",
             (check == bad_len), six_ok);
```

**Why (a) crashes** (cross-referenced against `BATCH_DEVICE_VERIFY_REPORT.md` §7): `BOUNDS_TEST_DUMMY_HANDLE` is `(jlong)0x1` (`:3399`). A 6-byte array **passes** the `mac_len != 6` guard at `softether_jni.c:599` by design, so control reaches `softether_set_client_mac(conn = 0x1, m)`. That function's only guard is `conn == NULL` (`softether_protocol.c:3957`), which `0x1` does not satisfy, so `memcpy(conn->client_mac, mac, 6)` dereferences `0x1 + offsetof(client_mac)`.

Observed on device: `SIGSEGV / SEGV_MAPERR, fault addr 0x4bd, x2 = 0x6`, with `softether_set_client_mac+100` at frame `#01`.

**Also noted for context — a misleading comment.** The section header at `:3390–3393` asserts:
> *"every entry point below validates the array length before it dereferences the handle as a `softether_connection_t*`, so a fake handle is safe and keeps the test hermetic"*

That is **true for N-01…N-04 but false for N-05**: N-05 is dummy-handle-safe *only* when the length guard fires. The pre-fix test relied on this comment holding universally, which is exactly how the contradiction slipped in. (Addressed in §3d.)

---

## 3. Edits Made

**One file:** `SoftEtherClient/src/main/cpp/test/native_test.c`
**Delta:** `+11 / −14` (net −3 lines)

### 3a. REMOVED — the 6-byte acceptance sub-case (the crash path)

```diff
-  // Also exercise the exact-length path so the guard is not rejecting
-  // everything: a 6-byte array must be accepted without crashing.
-  jbyteArray mac_ok = (*env)->NewByteArray(env, 6);
-  bool six_ok = (mac_ok != NULL);
-  if (six_ok) {
-    Java_vn_unlimit_softether_client_SoftEtherClient_nativeSetClientMac(
-        env, NULL, BOUNDS_TEST_DUMMY_HANDLE, mac_ok);
-    (*env)->DeleteLocalRef(env, mac_ok);
-  }
-
```

### 3b. CHANGED — `pass` expression (I1c)

```diff
-  bool pass = (check == bad_len) && six_ok;
+  bool pass = (check == bad_len);
```

### 3c. CHANGED — result message (drops the now-meaningless `6-byte accepted` term)

```diff
     snprintf(msg, sizeof(msg),
-             "N-05 setClientMac: short array rejected (env healthy=%d), "
-             "6-byte accepted=%d",
-             (check == bad_len), six_ok);
+             "N-05 setClientMac: short array rejected (env healthy=%d)",
+             (check == bad_len));
```

### 3d. ADDED — rationale comment (I1d) + correction of the section-header overclaim

```diff
 // touching the array afterwards. (prototype declared at the top of this section)
+//
+// Only the REJECTION path is exercised here. A valid-length (6-byte) array would
+// pass the length guard and reach softether_set_client_mac(), which requires a
+// real handle: it dereferences conn->client_mac via memcpy. Testing the
+// acceptance path therefore requires a live connection and is out of scope for
+// this hermetic test. NOTE: unlike N-01..N-04, this entry point is safe with a
+// dummy handle ONLY because the guard fires first — see the section comment
+// above, which holds here only for the wrong-length case.
```

**Unchanged and preserved:** the part (A) 1-byte rejection sequence (lines `:3561–3567` post-edit), the `GetArrayLength` health check, the `DeleteLocalRef`, the `bounds_result()` return, and the function signature.

**No new function, no new file** — the edit stays inside the existing function, matching the style of test31–test34 (I2).

---

## 4. Confirmation of No Production Change

> **No production `.c` / `.h` / `.cpp` file was touched.**

`softether_jni.c`, `softether_protocol.c`, and every other production translation unit are **byte-identical** to HEAD. The only `.c` file modified is `native_test.c`, which lives under `src/main/cpp/test/` and is compiled exclusively into `libsoftether_test.so` (the test library that `CMakeLists.txt` gates behind `BUILD_TESTING`, and which `SoftEtherClient/build.gradle` excludes from the release build).

The production N-05 guard at `softether_jni.c:599` is untouched and remains correct. This phase changes **only what the test asserts**, never what the code does.

---

## 5. Post-Edit Function Shape

```
native_test_result_t test_nativeSetClientMac_rejectsWrongLengthArray(JNIEnv* env)
```

**Contract asserted:** a **wrong-length (1-byte)** array passed to `nativeSetClientMac` with an arbitrary handle must be **rejected by the length guard** before the handle is ever dereferenced, leaving the JNI environment healthy.

**Sub-cases remaining: exactly one.**
1. Create a 1-byte array (`bad_len = 1`).
2. Call the JNI bridge with `BOUNDS_TEST_DUMMY_HANDLE` and that array.
3. Read the array length back — if the process survived and the JNI env is usable, the guard fired and no over-read occurred.

**Sub-cases removed:** the 6-byte acceptance case (unachievable with a dummy handle).

**Pass expression:**
```c
bool pass = (check == bad_len);
```
A single conjunct: the array must still report its original length, proving the call returned without reading past it.

**Return:** still a fully-formed `native_test_result_t` via `bounds_result(pass, msg, start_time)` (`:3416`), so the JNI bridge contract in `native_test.h` is unaffected.

**Dummy-handle safety:** the function is now safe with `0x1` **by construction** — the only JNI call it makes is guaranteed to be short-circuited by the length guard.

---

## 6. Files Modified

```
SoftEtherClient/src/main/cpp/test/native_test.c    | 25 +++++++++++--------------
1 file changed, 11 insertions(+), 14 deletions(-)
```

**Confirmed unchanged:**
| File | Status |
|---|---|
| `SoftEtherClient/src/main/cpp/test/native_test.h` | unchanged (`git diff --stat` empty) |
| `SoftEtherClient/src/main/cpp/test/test_jni_bridge.c` | unchanged (`git diff --stat` empty) |
| All production `.c`/`.h`/`.cpp` | unchanged |
| All `.gradle` / `CMakeLists.txt` | unchanged |
| All `.kt` / `.java` | unchanged |

`git diff --name-only` returns **exactly one path**. `git diff --cached --name-only` is **empty**.

---

## 7. Compile Check

```
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" CI=1 \
GRADLE_OPTS="-Dorg.gradle.internal.launcher.localhost=127.0.0.1 -Dorg.gradle.internal.remote.internal.inet.tcp.port=0" \
./gradlew :SoftEtherClient:externalNativeBuildDebug --no-daemon --rerun-tasks
```

```
> Task :SoftEtherClient:externalNativeBuildDebug

BUILD SUCCESSFUL in 28s
9 actionable tasks: 9 executed
Configuration cache entry stored.
```

**Exit status: 0. BUILD SUCCESSFUL. All tasks executed (9/9) — no `UP-TO-DATE` masking.**

**All four ABIs built.** Ninja ran in `SoftEtherClient/.cxx/Debug/5v273202/` and produced `libsoftether_test.so` for:
`arm64-v8a` (7 142 264 B), `armeabi-v7a` (6 220 856 B), `x86` (6 443 592 B), `x86_64` (8 265 392 B).

**Three compiler warnings — all pre-existing, none from this edit:**

| Warning | Location |
|---|---|
| `variable 'suspicious' set but not used` | `native_test.c:1874` |
| `unused function 'stress_fd_generation'` | `native_test.c:1644` |
| `unused function 'stress_unused_rt_view'` | `native_test.c:2225` |

Each of these three is present at the **identical line number in `HEAD`** (`git show HEAD:…`), so they antedate this phase. The edited region is `:3546–3581`; the warnings are at `:1644`, `:1874`, `:2225` — **disjoint**, and no warning references the modified function.

**Not run (per the phase rule):** the androidTest APK build. That belongs to the following verify phase.

---

## 8. Evidence Taxonomy

### SOURCE CODE EVIDENCE (directly observed)
* `native_test.c:3543–3581` (post-edit, re-read in full).
* `git diff` of `native_test.c` — the exact `+11/−14` delta (§3).
* `grep` results: `six_ok` → 0 hits; `mac_ok` → 0 hits; JNI call count in function → **1**; that call's array argument → `mac` (the 1-byte array).
* `git diff --name-only` → exactly one path; `git diff --cached` → empty.
* `git show HEAD:…native_test.c` → the three warnings' host lines present at identical numbers.
* `native_test.h:60–81` and `test_jni_bridge.c:421–468` unchanged (production-adjacent contracts intact).
* `softether_jni.c:592–609` and `softether_protocol.c:3956–3961` — read-only, unchanged, establishing the root cause.
* Build output: `BUILD SUCCESSFUL in 28s`, `9 actionable tasks: 9 executed`, four ABI `.so` files with recorded sizes.

### INFERENCE (reasoned, not directly observed)
* **That test35 will now PASS on device.** The reasoning is sound — the crash path is gone and the remaining path is the one that already ran safely inside the crashing test (the 1-byte call executed *before* the fatal 6-byte call, and the process survived to reach it). But this is *inference*, since no device run occurred in this phase. **It must be confirmed empirically in the next phase.**
* **That the section-comment correction prevents recurrence.** It documents the N-05 exception explicitly, but no automated guard enforces it.
* Four-ABI `.so` sizes being identical to the pre-edit `cmake/debug/obj` set suggests deterministic output; noted as a consistency signal, not proof.

### UNKNOWN
* Whether the **acceptance path** (`nativeSetClientMac` with a real 6-byte array) functions correctly end-to-end. It is now **entirely untested** — deliberately, pending a live-connection harness. This is a genuine, acknowledged coverage gap, not an oversight.
* Whether any *other* bounds test has the same latent defect (dummy handle + a valid-length array). Spot-checked in this phase: N-01…N-04 pass only wrong/invalid lengths (oversized, negative, `+48`, `+96`, `+1`, `+100`) and therefore never reach a dereference — so they are sound. Recorded as a targeted check, not an exhaustive audit of the file.
* device-side test outcome (see above).

---

## 9. Explicit No-Change Confirmation

| Category | Status |
|---|---|
| **Production `.c`/`.h`/`.cpp`** | **NONE** |
| **Gradle / CMakeLists** | **NONE** |
| **Other tests** | **NONE** (`native_test.h`, `test_jni_bridge.c`, all other `test_*` functions untouched) |
| **Kotlin / Java** | **NONE** |
| **Device** | **NONE** — no `adb` install, no instrumentation run |
| **APK rebuild** | **NONE** — neither app APK nor androidTest APK built |
| **CI** | **NONE** |
| **Commit / tag / push / release** | **NONE** — HEAD unchanged at `c5720508a41ee3f46412a108673cae46abfccda0` |
| **Files edited** | **`native_test.c` only** |

Build artifacts written to `SoftEtherClient/build/` and `SoftEtherClient/.cxx/` are ordinary Gradle outputs, not source changes.

---

## 10. Final Recommendation

> ## **PROCEED TO REBUILD-AND-RETEST-TEST35**

**Why this and not "review":** the fix is narrow, mechanical, and fully verified — the crash path is provably gone (zero `six_ok`, zero `mac_ok`, one JNI call with a wrong-length array), and the native build compiles for all four ABIs with no new warnings. Nothing about the change is ambiguous or under-specified.

**Next phase should:**
1. `:SoftEtherClient:assembleDebugAndroidTest --rerun-tasks` (forced — a cached `UP-TO-DATE` test APK proves nothing).
2. Reinstall the androidTest APK.
3. Re-run **test35** on the device; confirm `OK (1 test)` and no `Process crashed`.
4. Re-run **test31–test34** and **test36** to confirm the edit did not disturb the other five (expected 6/6 PASS).
5. **Then resume the interrupted BATCH-DEVICE-VERIFY scope:** T2 (G-1 stale-service), T3 (G-2 teardown identity), T4–T6 (BUG 1/2/3 regressions), T7 (full session crash scan), T8 (settings restoration).

**Residual known gap to carry forward:** the `nativeSetClientMac` **acceptance** path is now untested by unit test. If it matters, it requires a live-connection harness — out of scope for the hermetic bounds suite and worth recording as a follow-up rather than silently leaving implicit.

### Alternatives
* **`WAIT FOR EXPLICIT USER DECISION`** — if the user wants to review the diff before any device run.
* **`PROCEED TO FIX-NATIVE-LIFETIME-SYNC (N-15 + N-16)`** — independent; can run in parallel.

---

### Final rule compliance
> *"Test-only edit. Do NOT touch production code. Do NOT touch Gradle or CMakeLists. Do NOT rebuild the APK. Do NOT touch the device. Report in English only."*

* **Test-only edit** — ✅ one file, `native_test.c`, one function.
* **No production code touched** — ✅ verified by `git diff --name-only`.
* **No Gradle / CMakeLists** — ✅ verified.
* **No APK rebuild** — ✅ only the native build task ran, as §7 authorizes.
* **No device** — ✅ no `adb` interaction.
* **Report in English only** — ✅
