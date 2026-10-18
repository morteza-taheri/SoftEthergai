# BATCH-DEVICE-VERIFY — Report

Phase: verify the accumulated session fixes on the physical device.
Mode: rebuild, install, test only. **No source modification. No push.**
Language: English only.

---

## 1. Executive Verdict

> ## ❌ **BATCH VERIFY FAIL — SPECIFIC TEST FAILED**
> ## also: **CRASH_OBSERVED**

**`test35NativeSetClientMacRejectsWrongLengthArray` (N-05) crashed the test process with SIGSEGV.**

Five of the six native tests passed cleanly (`test31`, `test32`, `test33`, `test34`, `test36`). The sixth crashed, and **per the phase's absolute rule — "If any test fails, STOP and report" / "If a crash occurs, STOP, collect evidence, do NOT restart" — the phase was stopped at N1.** Manual tests T2–T8 were **not** executed.

The crash is in the **newly added test** (N-05), not in production code and not in the app. The production N-05 guard is correct; the test is **unsound** because its own success criterion requires dereferencing a fabricated pointer.

---

## 2. Device Identity (P1/P2)

**P1 — `adb devices -l`:**
```
5585fecc               device product:spes_global model:2201117TG device:spes transport_id:1
```
✅ **The device now enumerates.** The USB enumeration failure that blocked the earlier attempt has cleared. Serial `5585fecc`, model `2201117TG`.

**P2 — device identity:**

| Property | Expected | Actual | Verdict |
|---|---|---|---|
| `ro.product.model` | `2201117TG` | `2201117TG` | ✅ |
| `ro.build.version.sdk` | `33` | `33` | ✅ |
| `ro.build.version.release` | `13` | `13` | ✅ |

(Noted in passing: `ro.product.product.name` is `spes_global`/`spesn_global`; this is expected for the Redmi Note 11 global variant and does not contradict the model.)

## 3. Source Pre-Check (P4)

| # | Anchor | Expected | Actual | Verdict |
|---|---|---|---|---|
| P4.1 | `SoftEtherClient.kt` `setTimeout(handle, timeoutMs)` | present | `:217` `fun setTimeout(handle: Long, timeoutMs: Int)` | ✅ |
| P4.2 | `ConnectionController.kt` `teardownIdentity` in `destroyResources` | present | `:918` `val teardownIdentity = "attempt=${nativeLease?.attemptId ?: "none"}, session=$sessionId"` | ✅ |
| P4.3 | `ConnectionController.kt` `onError("Reconnect failed: ...")` in catch | present | `:1418` `onError("Reconnect failed: ${e.message}")` | ✅ |
| P4.4 | `AutoModeController.kt` `&& c.seTcpPort > 0` in SOFTETHER_UDP arm | present | `:1582` and `:1599` | ✅ |
| P4.5 | `AndroidConnectionAdapter.kt` `armedTunnel?.complete(false)` in `skipCurrent` | present | `:392` | ✅ |
| P4.6 | `AndroidConnectionAdapter.kt` `withTimeoutOrNull(timeoutMs)` in `awaitArmedTunnel` | present | `:244`, `:260`, `:371` | ✅ |
| P4.7 | `NativeConnectionTest.kt` six new bridge declarations | present | `:137–142` six instance-level `external fun`; tests `:611–697` | ✅ |

**P4 PASS — all seven anchors present in current source.**

### 2.1 Correction to the phase brief (recorded for accuracy)

The brief's **N1** gave the instrumentation runner twice, inconsistently:
* `vn.unlimit.vpngatepro.test/androidx.test.runner.AndroidJUnitRunner`
* `vn.unlimit.vpngate.pro.test/androidx.test.runner.AndroidJUnitRunner`

**Both are wrong.** The authoritative value, read from `adb shell pm list instrumentation`:
```
instrumentation:vn.unlimit.softether.test/androidx.test.runner.AndroidJUnitRunner (target=vn.unlimit.softether.test)
```
`NativeConnectionTest` lives in the **SoftEtherClient** module, whose androidTest package is self-targeting `vn.unlimit.softether.test`. Either of the brief's strings would have failed with *"instrumentation not found."*

## 4. Build Identity

**B1 — `:app:assembleProDebug`:**

| Attempt | Command | Result |
|---|---|---|
| 1 | `assembleProDebug --no-daemon` | `BUILD SUCCESSFUL in 28s` — but **8/114 executed**, `packageProDebug UP-TO-DATE` |
| 2 | `assembleProDebug --no-daemon --rerun-tasks` | **`BUILD SUCCESSFUL in 13m 58s` — 114/114 executed** |

Attempt 1 was rejected as insufficient evidence: an `UP-TO-DATE` package step proves nothing about the bytes on disk. The forced rebuild (attempt 2) genuinely recompiled and repackaged everything.

**APK identity (final, post-forced-rebuild):**

| Property | Value |
|---|---|
| Path | `app/build/outputs/apk/pro/debug/app-pro-debug.apk` |
| Size | `63 572 562` bytes |
| SHA-256 | `d71332b13807c6fea517c37062b03029f3ad4c39d3f44570f8585a8be7555e8c` |
| `versionName` | `2.4.10-dev` |
| `versionCode` | `143` |
| `compileSdkVersion` | `37` |

**Note — byte-identical output.** The forced rebuild produced a **byte-identical** APK (same SHA-256 `d71332b1…`). Two fully-forced builds (114/114 executed each) yielding identical bytes is **build determinism**, not staleness. The APK was therefore already built from current sources; the forced rebuild is what *proves* that.

### 4.1 Content verification (and a retracted false alarm)

An initial content check appeared to show the APK contained **zero** F.28AL classes. **That finding was wrong and is retracted.** Root cause: **`strings` is not installed in this environment** (`strings: command not found`), so every `unzip -p … | strings | grep` scan silently returned nothing and was misread as "absent."

A corrected scan (`grep -a` directly on extracted dex binaries) proves the APK **does** contain the F.28AL classes:

| Class | dex files |
|---|---|
| `NativeLifetimeRegistry` | classes6, classes13, classes30 |
| `SoftEtherSessionPolicy` | classes6 |
| `TunOwnershipCoordinator` | classes6 |
| `ServerListUpdater` | classes18, classes29, classes30 |
| `AutoConnectReachability` | classes18, classes30 |
| `CollectorSnapshotStore` | classes18, classes24 |

(Separately corroborated: the `proRelease` R8 `mapping.txt` built during FIX-U36-FULL-BUILD-VERIFY lists `ServerListUpdater` ×192, `CollectorSnapshotStore` ×732, `SourceIdentity` ×74, `AutoConnectReachability` ×39, `DatasetIntegrity` ×13.)

**Test APK — `:SoftEtherClient:assembleDebugAndroidTest --rerun-tasks`** → `BUILD SUCCESSFUL in 4m 10s`, **63/63 executed**.

| Property | Value |
|---|---|
| Path | `SoftEtherClient/build/outputs/apk/androidTest/debug/SoftEtherClient-debug-androidTest.apk` |
| Created | `2026-10-13 08:36` (fresh; the prior APK was from **Oct 9**) |
| SHA-256 | `f542ec4989f0297e9a54f671f6846a91fa1ce337e13c1c938b9b264af24af1da` |

Verified by binary grep that **all six tests 31–36 are present**, each exactly once, in `classes8.dex`. The previous APK (Oct 9) contained none of them, so this rebuild was genuinely necessary.

## 5. Signature Check

**B2 — certificate comparison:**

| APK | SHA-256 cert digest | DN |
|---|---|---|
| Installed (`installed_before.apk`, pulled from device) | `247423ce8d62f0dfe15938f10e80798bd328857a50af0883e3a800dc10319e9a` | `C=US, O=Android, CN=Android Debug` |
| Built (`app-pro-debug.apk`) | `247423ce8d62f0dfe15938f10e80798bd328857a50af0883e3a800dc10319e9a` | `C=US, O=Android, CN=Android Debug` |

**Fingerprints match exactly** (and match the expected debug cert `247423ce…`). Signature-compatible → in-place `-r` install is safe.

Supporting digests (both APKs): SHA-1 `eb45bdd48cc1626a3b2d0d234d9b236c978cd816`, MD5 `3209c5c47536ae8ec849414e6d2dd698`.

## 6. Install Result (I1–I3)

**I1 — `adb install -r`:**
```
Performing Streamed Install
Success
```
✅ In-place install succeeded.

**I2 — package state after install:**

| Property | Baseline (pre-install) | After install | Verdict |
|---|---|---|---|
| `versionName` | `2.4.10-dev` | `2.4.10-dev` | ✅ unchanged |
| `versionCode` | `143` | `143` | ✅ unchanged |
| `firstInstallTime` | `2026-09-28 16:05:05` | **`2026-09-28 16:05:05`** | ✅ **unchanged — no uninstall/data clear** |
| `lastUpdateTime` | `2026-10-12 17:08:41` | `2026-10-13 08:31:16` | ✅ advanced (expected) |

**I3 — launch:**
| Check | Result |
|---|---|
| Launch via LAUNCHER intent | `Events injected: 1` |
| PID | `21265` — **live** |
| Focused activity | `vn.unlimit.vpngatepro/vn.unlimit.vpngate.activities.MainActivity` |

✅ App launched and stayed alive.

## 7. Native Tests 31–36 Result (N1–N3)

Runner used (authoritative): `vn.unlimit.softether.test/androidx.test.runner.AndroidJUnitRunner`

Each test run individually via `-e class vn.unlimit.softether.test.NativeConnectionTest#<name>`.

| Test | Finding | Result | Evidence |
|---|---|---|---|
| **test31** | N-01 `nativeSend` | ✅ **PASS** | `OK (1 test)`, 20.357s |
| **test32** | N-02 `nativeReceive` | ✅ **PASS** | `OK (1 test)`, 20.117s |
| **test33** | N-03 `nativeReceiveBatch` | ✅ **PASS** | `OK (1 test)`, 20.109s |
| **test34** | N-04 `nativeSendRaw` | ✅ **PASS** | `OK (1 test)`, 20.107s |
| **test35** | N-05 `nativeSetClientMac` | ❌ **CRASH** | `INSTRUMENTATION_RESULT: shortMsg=Process crashed.` |
| **test36** | N-08 `rudp_recv` lock | ✅ **PASS** | `OK (1 test)`, 20.121s |

**5 PASS / 1 CRASH.** No test reported `FAIL`; test35 **crashed the process**, which is a stronger failure than an assertion failure.

### 7.1 test35 crash evidence (N3)

**Instrumentation result:**
```
vn.unlimit.softether.test.NativeConnectionTest:INSTRUMENTATION_RESULT: shortMsg=Process crashed.
INSTRUMENTATION_CODE: 0
```

**Crash record (`logcat -b crash`):**
```
Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x4bd in tid 24194 (roidJUnitRunner), pid 24174 (.softether.test)
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000000000004bd
Cause: null pointer dereference
    x0  00000000000004bd  x2  0000000000000006
```
* Process: `vn.unlimit.softether.test` (pid 24174) — the **test** process.
* `x2 = 0x6` is the `memcpy` length (6 bytes).
* `fault addr 0x4bd` = `0x1` (the dummy handle) + a struct field offset.

**Backtrace (app frames only):**
```
#00  __memcpy+124                                                   (libc)
#01  softether_set_client_mac+100                                   (libsoftether_test.so)  <-- FAULT
#02  Java_vn_unlimit_softether_client_SoftEtherClient_nativeSetClientMac+196
#03  test_nativeSetClientMac_rejectsWrongLengthArray+248
#04  Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestNativeSetClientMacRejectsWrongLengthArray+76
#07  vn.unlimit.softether.test.NativeConnectionTest.test35NativeSetClientMacRejectsWrongLengthArray+14
```

**No `NativeTestResult` `errorMessage` was produced** — the process died before the test could return a result object. There is no JUnit failure message, because the runner never completed the test. This is precisely why the crash had to be diagnosed from the tombstone instead.

### 7.2 Root cause — the test is unsound (production guard is correct)

**Production code, `softether_protocol.c:3956–3961`:**
```c
void softether_set_client_mac(softether_connection_t* conn, const uint8_t mac[6]) {
    if (conn == NULL || mac == NULL) return;
    memcpy(conn->client_mac, mac, 6);          // <-- dereferences conn
    ...
}
```

**JNI entry, `softether_jni.c:592–609`:**
```c
if (handle == 0 || mac == NULL) return;        // :594 — handle==0 guard
jsize mac_len = (*env)->GetArrayLength(env, mac);
if (mac_len != 6) { LOGE(...); return; }       // :599 — N-05 length guard (CORRECT)
...
softether_set_client_mac(conn, (const uint8_t*)m);   // :607
```

**The test, `native_test.c:3549–3584`:**
```c
const jsize bad_len = 1;
jbyteArray mac = (*env)->NewByteArray(env, bad_len);
// (A) 1-byte array -> guard at :599 fires -> safe.   <-- correct half
Java_..._nativeSetClientMac(env, NULL, BOUNDS_TEST_DUMMY_HANDLE, mac);

jbyteArray mac_ok = (*env)->NewByteArray(env, 6);
if (six_ok) {
    // (B) 6-byte array -> length guard PASSES -> reaches memcpy on conn = 0x1
    Java_..._nativeSetClientMac(env, NULL, BOUNDS_TEST_DUMMY_HANDLE, mac_ok);   // <-- CRASH
}
...
bool pass = (check == bad_len) && six_ok;      // success REQUIRES the crashing path
```

`BOUNDS_TEST_DUMMY_HANDLE` is `(jlong)0x1` (`native_test.c:3399`).

**The chain:**
1. Part (A) is correct: a 1-byte array trips the `:599` guard and returns safely.
2. Part (B) passes a **valid 6-byte** array with the **same dummy handle `0x1`**. The length guard **passes by design** — that is the point of the sub-test.
3. The `handle == 0` guard at `:594` does **not** fire, because `0x1 != 0`.
4. `softether_set_client_mac(conn = 0x1, m)` is entered. The `conn == NULL` guard at `:3957` does **not** fire, because `0x1 != NULL`.
5. `memcpy(conn->client_mac, mac, 6)` dereferences `0x1 + offsetof(softether_connection_t, client_mac)` → **SIGSEGV at `0x4bd`, length 6.**

**Conclusion: the N-05 test is internally contradictory.** Its stated success condition (`six_ok`, "6-byte accepted=%d") *requires* the production function to dereference a fabricated pointer. That operation cannot succeed against a dummy handle. **The production N-05 guard at `softether_jni.c:599` is correct and did its job; the test asks it to do something impossible.** The other five bounds tests avoid this because they exercise *only* the reject path, which returns before any dereference.

**Fix is test-only.** Two options, neither applied (authorization required):
* **(a) Preferred — narrow the test to its actual contract:** delete the part (B) 6-byte sub-case; assert only that a short array is rejected, the JNI env remains usable, and the array is intact. This matches the other five bounds tests exactly.
* **(b) Give the test a real handle:** obtain a genuine `softether_connection_t*` (e.g. via the connection-create path) so the 6-byte acceptance case has a valid `conn`. Heavier setup; requires the test to own teardown.

No production C file needs to change under either option.

## 8. G-1 Result (T2)

**NOT TESTED.** The phase stopped at N1 per its crash rule. No AutoConnect run was performed; the "Start refused: a connected session already owns the service" criterion was **not** evaluated.

## 9. G-2 Result (T3)

**NOT TESTED.** No teardown occurred during this phase (the app never connected), so no `Native cleanup confirmed (…)` line exists to inspect. The `attempt=` value was **not** observed.

## 10. BUG 1/2/3 Regression (T4–T6)

| Test | Finding | Result |
|---|---|---|
| T4 | BUG 1 — reachable-server selection | **NOT TESTED** |
| T5 | BUG 2 — "Try next server" teardown | **NOT TESTED** |
| T6 | BUG 3 — `nativeSetOption option=1` value | **NOT TESTED** |

None of these paths were exercised: the phase halted before any UI-driven connection attempt. **No regression claim is made in either direction** — they remain carried forward from their original verification.

## 11. Crash Scan (T7)

**Partial.** The session-wide scan could not be completed, but the crash that occurred was fully characterised:

| Signal | App process (`vn.unlimit.vpngatepro`) | Test process (`vn.unlimit.softether.test`) |
|---|---|---|
| `FATAL EXCEPTION` | **0** | n/a (native crash) |
| `FATAL signal 11` | **0** | **1** (SIGSEGV, fault `0x4bd`) |
| `AndroidRuntime` (app) | **0** | — |
| `tombstone` | — | Buffer captured (113 lines) |
| `ANR in vn.unlimit.vpngatepro` | **0** | — |
| `RemoteServiceException` / `ForegroundServiceDidNotStartInTime` / `DeadObjectException` | **0** | — |

**PID stability:** the app PID remained **`21265`** from launch through the end of the phase — it never restarted and never died. **The crash is confined to the instrumentation test process and does not implicate the app.**

## 12. Settings Restored (T8)

**NO-OP / N/A.** The phase stopped before any UI interaction, so **no Settings value was read or changed**. `SETTING_AUTO_RECONNECT_IN_AUTO_MODE`, `SETTING_LOCALE`, and `SETTING_AUTO_MODE_TIMEOUT_SECONDS` are all at their pre-test values by construction — nothing was touched, so nothing required restoration.

## 13. Evidence Taxonomy

**Primary evidence (device-derived):**
| Artifact | Content |
|---|---|
| `_batch_verify_test35_crash.log` | 113 lines — full `logcat -b crash`, signal, registers, backtrace |
| `_batch_verify_t1.log` | logcat capture from phase start |
| `_batch_verify_t1_final.log` | app-side scan basis — 0 FATAL, 0 signal 11 |
| `_ui_dump1.xml` | UI hierarchy dump (main screen) |

**Command evidence (transient, recorded in-line above):**
`adb devices -l`; `getprop ×3`; `dumpsys package` (pre/post); `pm path`; `apksigner verify --print-certs` (×2, matching); `pm list instrumentation`; `am instrument` ×6; `pidof`; `dumpsys package` post-install.

**Build evidence:**
`assembleProDebug` ×2 (28s `UP-TO-DATE`; 13m 58s `114/114 executed`); `assembleDebugAndroidTest` (4m 10s, `63/63 executed`); APK SHA-256 ×2; dex content scans (`grep -a`).

**Source evidence (read-only):**
`softether_jni.c:592–609`; `softether_protocol.c:3956–3961`; `native_test.c:3549–3584` and `:3399`.

**Retracted evidence (kept for transparency):**
The initial "APK contains zero F.28AL classes" reading — invalidated by the absent `strings` binary; superseded by §4.1.

## 14. Explicit No-Change Confirmation

| Category | Status |
|---|---|
| **Source (`.kt`/`.java`/`.c`/`.h`) modification** | **NONE** — `git diff --name-only` is empty |
| **Test modification** | **NONE** |
| **Gradle / CMakeLists modification** | **NONE** |
| **Device write (uninstall / data clear)** | **NONE** — `firstInstallTime` unchanged |
| **Commit** | **NONE** — HEAD still `c5720508a41ee3f46412a108673cae46abfccda0` |
| **Tag** | **NONE** |
| **Push** | **NONE** |
| **CI trigger** | **NONE** |
| **GPG** | **NONE** |
| **Synthetic hooks** | **NONE** — only the standard `androidTest` suite |
| **Release APK rebuild** | **NONE** (one pre-existing `proRelease` APK was only *read*) |
| **Device actions permitted** | Installs (`-r` ×2: app + test APK), `am instrument`, `logcat`, `uiautomator dump`, `monkey` launch |

**Files created by this phase — APK + logcat + UI dump only:**
`_batch_verify_t1.log`, `_batch_verify_t1_final.log`, `_batch_verify_test35_crash.log`, `_ui_dump1.xml`
Plus build outputs under `app/build/` and `SoftEtherClient/build/` (normal Gradle artifacts).

Working-tree dirty count: 140 → 47 (the drop reflects the earlier session's commit, not this phase). `git diff --cached` is empty.

## 15. Final Recommendation

> ## **NEEDS FIX (specify)**

**The required fix is `test35` — and it is test-only.**

**Fix scope:** `SoftEtherClient/src/main/cpp/test/native_test.c`, function `test_nativeSetClientMac_rejectsWrongLengthArray` (`:3549–3584`). Remove the 6-byte acceptance sub-case (part B, `:3563–3569`) and its `six_ok` term in the `pass` expression (`:3575`), leaving a pure rejection-contract assertion consistent with the other five bounds tests. **No production C file changes.**

**Why this is the right fix, not a workaround:** the crash is not a bug in the shipped guard — the guard correctly rejects a 1-byte array and correctly accepts a 6-byte one. The defect is that the *test* paired a valid-length array with an invalid handle, an operation that by construction must dereference `0x1`. Narrowing the test to its actual contract removes the contradiction without weakening any production guarantee.

**Immediately actionable, no device needed.**

### Recommended next phase
**`PROCEED TO FIX-N05-TEST-DEREFERENCE`** — a tightly-scoped, test-only phase:
1. Edit `native_test.c:3549–3584` per option (a) above.
2. Rebuild `:SoftEtherClient:assembleDebugAndroidTest --rerun-tasks`.
3. Re-run `test35` (and `test31–34`, `test36`) on device to confirm 6/6 PASS.
4. **Then** resume the interrupted BATCH-DEVICE-VERIFY scope: T2 (G-1), T3 (G-2), T4–T6 (regressions), T7 (full crash scan), T8 (settings).

### Alternatives
* **`WAIT FOR EXPLICIT USER DECISION`** — if the user prefers to review the root-cause analysis before authorizing a test edit.
* **`PROCEED TO FIX-NATIVE-LIFETIME-SYNC (N-15 + N-16)`** — unrelated to this crash; safe to run in parallel.
* **`PROCEED TO DEVICE VERIFICATION (resume)`** — only viable *after* the N-05 test is fixed; resuming now would re-crash deterministically.

### Blocked / carried forward
* **T2–T8** — not executed; must be re-run in the resumed verification phase.
* **`proRelease` build/test path** — still never validated.
* **Hardware note:** the device enumerated successfully this time, but the earlier phase saw it fail USB enumeration (`Unknown Redmi Note 11` / `Device Descriptor Request Failed`). The cabling is intermittent; expect possible `DEVICE_NOT_ENUMERATING` recurrences.

---

### Final rule compliance
> *"Rebuild, install, test only. Do NOT modify code. Do NOT push. Report in English only."*

* **Rebuild** — ✅ app APK (forced, 114/114) + test APK (forced, 63/63).
* **Install** — ✅ in-place `-r` ×2; `firstInstallTime` unchanged.
* **Test** — ✅ 6 native tests attempted; 5 PASS, 1 crashed the test process.
* **Do NOT modify code** — ✅ zero source/test/Gradle/CMake edits (`git diff` empty).
* **Do NOT push** — ✅ nothing pushed, no tag, no commit.
* **Report in English only** — ✅
* **Stop on crash** — ✅ phase halted at N1; evidence collected; app not restarted.
