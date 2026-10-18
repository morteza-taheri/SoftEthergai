# SKIP-C5-AND-EXECUTE-STAGE-B — Report

**Phase:** `SKIP-C5-AND-EXECUTE-STAGE-B`
**Date:** 2026-10-13
**Device:** `5585fecc` / `2201117TG` (spes_global)
**Language:** English only.

---

## 1. Executive Verdict

```
STAGE-B EXECUTED — T5 NOT REPRODUCED
```

C5 is formally gated (skipped, not failed). Stage B was executed end-to-end: the Kotlin instrumentation (`describe()` at both T5 log sites) is applied, compiled, and installed. However, the single permitted T5 reproduction run **did not reproduce the slot contention** — the "Try next server" skip produced a **clean, natively-confirmed teardown** (`Native cleanup confirmed (attempt=1, session=1)`) *before* the next connect, so neither `"Native slot busy; retrying"` nor `"Native slot still owned"` fired, and **no holder identity could be captured**.

---

## 2. Pre-Conditions (P1–P5)

| ID | Check | Result |
|----|-------|--------|
| **P1** | Device enumerates `5585fecc` / `2201117TG` | **PASS** — `5585fecc device product:spes_global model:2201117TG` |
| **P2** | HEAD is `4c013e86c762f4d3a32baea810bec58294799226` | **PASS** — exact match |
| **P3** | C5 gate documented in source | **PASS (wording note)** — see §3. `native_test.c:2273` marks C5 `— gated`; the literal string `"NOT executed in this phase"` lives in `NativeConnectionTest.kt:589` (not `native_test.c`). Both files document the gate; the phase's P3a grep string simply does not appear in the `.c` file. |
| **P4** | Two Stage B log sites present | **PASS** — `Native slot busy; retrying` @ `:1438`; `Native slot still owned` @ `:1474` |
| **P5** | F.28AL-U slot already device-verified (no re-run) | **PASS** — see below |

**P5 evidence (no re-run performed):**
- `FIX_NATIVE_LIFETIME_SYNC_VERIFY_REPORT.md:117-121` — N-15: test37 PASS, test38 PASS, test39 PASS; N-16: test40 **FAIL** (test-budget defect, later fixed by commit `38d9d33`), test41 PASS.
- `FIX_TEST40_BUDGET_AND_RE_RUN_REPORT.md:152` — test40 PASS (44.2 s) after the budget fix.
- This project's own two independent Stage A runs (`_stageA_results.log`, `_c3fix_stageA_results.log`) — test37/38/39/40/41 **all PASS**.
- `RESUME_BATCH_DEVICE_VERIFY_REPORT.md` §11 — T5 reproduced on device (documented observation; slot held by an unidentified "unresolved operation").

---

## 3. C5 Formal Gating

**`SoftEtherClient/src/main/cpp/test/native_test.c:2273`**
```c
#define B3S_CASE_C5 5  // genuine timeout control (silent responder) — gated
```

**`SoftEtherClient/src/androidTest/java/vn/unlimit/softether/test/NativeConnectionTest.kt:585-589`**
```kotlin
    // F.28AL-E — expose the existing native C5 (genuine no-response timeout)
    // as a discoverable instrumentation test. Silent responder: no OFFER/ACK is
    // provided and teardown is deferred until after DHCP returns, so the run
    // traverses the real 3x5s production timeout. Single iteration, no retries.
    // NOT executed in this phase — physical execution requires separate approval.
```

C5 is therefore treated as **GATED — SKIPPED, not FAILED** in this report. Its defect is deferred to a future `C5-TRIAGE-DEVICE` phase with on-device logging.

---

## 4. Kotlin Edit Applied

**File:** `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt` (the ONLY source change)

```diff
@@ -7,6 +7,7 @@ import kotlinx.coroutines.flow.MutableStateFlow
 import kotlinx.coroutines.flow.StateFlow
 import kotlinx.coroutines.isActive
 import kotlinx.coroutines.launch
+import vn.unlimit.softether.NativeLifetimeRegistry
 import vn.unlimit.vpngate.state.GlobalVpnTracker
@@ -1436,7 +1437,8 @@ enum class AttemptOutcome {
                         accumulatedRetryDelayMs += NATIVE_BUSY_RETRY_DELAY_MS
                         adapter.log(
                             "[AUTO] Native slot busy; retrying same server in " +
-                                    "${nativeBusyRetryDelayMs / 1000}s (attempt $retry/$retryCount)",
+                                    "${nativeBusyRetryDelayMs / 1000}s (attempt $retry/$retryCount)" +
+                                    " (holder=${NativeLifetimeRegistry.process.describe()})",
                         )
@@ -1471,7 +1473,8 @@ enum class AttemptOutcome {
                     } else {
                         adapter.log(
-                            "[AUTO] Native slot still owned by an unresolved operation; " +
+                            "[AUTO] Native slot still owned by an unresolved operation " +
+                                    "(${NativeLifetimeRegistry.process.describe()}); " +
                                     "stopping without blaming this server",
                         )
```

**No logic change:** only the log string arguments (and the import). No control flow, no `setState`, no Error code, no `describe()` invocation outside the two `adapter.log(...)` argument expressions. **B2 verification:** `grep -n "NativeLifetimeRegistry.process.describe()"` → **2 matches** (`:1441`, `:1477`).

---

## 5. Build Result

| Item | Value |
|------|-------|
| Command | `JAVA_HOME="…/Android Studio/jbr" CI=1 ./gradlew :app:assembleProDebug --no-daemon --rerun-tasks` |
| Result | **BUILD SUCCESSFUL in 11m 19s**, 114 actionable tasks executed |
| Kotlin compile | `:app:compileProDebugKotlin` **succeeded** (no missing import / unknown symbol) |
| Output | `app/build/outputs/apk/pro/debug/app-pro-debug.apk` (63,587,790 bytes, `13:26:04`) |
| **SHA-256** | `158ab71ca634cf7e23bbbcce1668f944dae6e4fca7f3e1e9804645dc27ac2beb` |

No `BUILD_FAILED` condition occurred.

---

## 6. Install Result

`adb install -r app/build/outputs/apk/pro/debug/app-pro-debug.apk` → **Success**.

| Field | Before | After |
|-------|--------|-------|
| `firstInstallTime` | `2026-09-28 16:05:05` | **`2026-09-28 16:05:05`** (unchanged) |
| `lastUpdateTime` | `2026-10-13 10:12:36` | `2026-10-13 13:27:24` |

In-place upgrade confirmed — `firstInstallTime` unchanged, only `lastUpdateTime` advanced.

---

## 7. T5 Reproduction Timeline

Capture: `_t5_instrumented.log` (`adb logcat -v threadtime`, 9,312 lines, 1,026,138 bytes). Setting `SETTING_AUTO_RECONNECT_IN_AUTO_MODE` = default **true** (not present in `vn.unlimit.vpngatepro_preferences.xml`; `DataUtil.kt:350` default). App PID **2758**.

```
13:28:55.195  2758  2871  D AutoMode: [AUTO] Started
13:28:55.226  2758  2871  D AutoMode: [AUTO] Servers available = 105
13:28:55.228  2758  2871  D AutoMode: [AUTO] Pre-filtering reachable servers via probe ...
13:28:56.889  2758  2942  D AutoMode: [AUTO] Reachable servers selected: 76
13:28:56.893  2758  2942  D AutoMode: [AUTO] Trying #1 vpn333205800
13:29:00.321  2758  2928  D AutoMode: [AUTO] Tunnel established
13:29:00.322  2758  2928  D AutoMode: [AUTO] Connected successfully          <-- Connected (vpn333205800, IP 218.115.40.234)
   --- UI: power button tapped at 13:28:56; Connected confirmed at 13:29:00 ---
13:29:35.957  2758  2758  D AutoMode: [AUTO] Connected server skipped by user; trying next   <-- "Try next server" tapped
13:29:36.195  2758  2882  D ConnectionController: Native cleanup confirmed (attempt=1, session=1): structure destroyed and slot released
13:29:36.280  2758  2882  D AutoMode: [AUTO] Teardown confirmed complete (teardown#1(session=1, NATIVE_CONFIRMED))
13:29:36.280  2758  2882  D AutoMode: [AUTO] Started
13:29:36.282  2758  2882  D AutoMode: [AUTO] Servers available = 75
13:29:37.871  2758  2949  D AutoMode: [AUTO] Reachable servers selected: 75
13:29:37.872  2758  2949  D AutoMode: [AUTO] Trying #1 public-vpn-165
13:29:41.653  2758  2882  D AutoMode: [AUTO] Tunnel established
13:29:41.653  2758  2882  D AutoMode: [AUTO] Connected successfully          <-- Connected (public-vpn-165, IP 219.100.37.127)
```

**Search result:** `"Native slot busy; retrying"` → **0 occurrences**; `"Native slot still owned"` → **0 occurrences** (verified in both the live buffer and `_t5_instrumented.log`). The skip→reconnect completed in ~5.7 s with a fully confirmed teardown; the process-wide native slot was released (`attempt=1`) before the next `adapter.connect`, so the retry/refusal path was never entered.

---

## 8. Holder Identity Found

**None — NOT REPRODUCED.** No `"Native slot busy; retrying (holder=…)"` and no `"Native slot still owned (…)"` line appeared, so no `attempt=<id> phase=<PHASE>` / `idle` string could be extracted. The instrumentation is installed and compiled but was **not exercised** this run.

---

## 9. T5-SLOT Classification

**NOT REPRODUCED.** The prior reproduction (`RESUME_BATCH_DEVICE_VERIFY_REPORT.md` §11, device log `09:17:52`–`09:18:10`) remains the **only known occurrence**: there the slot was still owned at reconnect (`attempt 1/2`, `attempt 2/2`, then the terminal refusal), with the holder never individually identified. This run shows the healthy path — teardown confirmed before the next connect — so the holder question stays open.

- Evidence class: **PHYSICAL DEVICE** (this run) + **PRIOR REPORT** (the single earlier occurrence).

---

## 10. Crash Scan

| Check | Result |
|-------|--------|
| `adb logcat -b crash -d` | **EMPTY** |
| `FATAL EXCEPTION` / `Fatal signal` / `SIGSEGV` / `SIGABRT` / `tombstone` (app) | **0** (the only `AndroidRuntime` lines are the `uiautomator` tool's own VM shutdown, uid 2000 — not the app) |
| App process | `vn.unlimit.vpngatepro` PID **2758** — **alive** throughout |

**No crash observed.**

---

## 11. Files Modified

| File | Change |
|------|--------|
| `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt` | import + `describe()` at the two T5 log sites (only source change) |

Untracked evidence files at the repo root: `_stageb_build.out/.err`, `_t5_instrumented.log`, `_ui_*_stageb.xml`, this report.

---

## 12. Evidence Taxonomy

| Class | Items |
|-------|-------|
| **SOURCE CODE** | `AutoModeController.kt:10`, `:1437-1441`, `:1473-1477` (edit); `native_test.c:2273` and `NativeConnectionTest.kt:585-589` (C5 gate) |
| **TEST / BUILD** | Forced `:app:assembleProDebug` log (114 tasks, BUILD SUCCESSFUL); APK SHA-256 `158ab71c…ac2beb`; `:app:compileProDebugKotlin` succeeded |
| **PHYSICAL DEVICE** | `adb install -r` Success; `firstInstallTime` unchanged; logcat timeline `13:28:55`–`13:29:41`; empty crash buffer; app PID 2758 alive |
| **PRIOR REPORT** | `RESUME_BATCH_DEVICE_VERIFY_REPORT.md` §11 (sole prior T5 occurrence); `FIX_NATIVE_LIFETIME_SYNC_VERIFY_REPORT.md:117-121`; `FIX_TEST40_BUDGET_AND_RE_RUN_REPORT.md:152` |
| **INFERENCE** | The T5 retry path is skipped when teardown is natively confirmed before the next connect (observed this run) |
| **UNKNOWN** | The identity of the "unresolved operation" holder — not exercised this run, still open |

---

## 13. Explicit No-Change Confirmation

- **Production C/C++:** NONE.
- **`native_test.c`:** NONE (C5 gating honored; no further edits — `git diff -- SoftEtherClient/src/main/cpp/` is **empty**).
- **Gradle / CMake:** NONE.
- **Lifetime components** (`NativeLifetimeRegistry`, `NativeHandleSlot`): NONE (only read via the existing public `process.describe()`).
- **Push:** NONE. **Tag:** NONE. (HEAD still `4c013e8`; no commit created this phase.)
- **Uninstall / data clear:** NONE.
- **Settings changed via app UI:** NONE (no setting was modified; `SETTING_AUTO_RECONNECT_IN_AUTO_MODE` left at its default `true`).
- **AutoConnect runs:** 1 of 1 budget used.
- **Device end state:** left Connected (Auto Mode, `public-vpn-165`, IP 219.100.37.127) — no teardown was requested by the phase.

---

## 14. Final Recommendation

```
PROCEED TO COMMIT-T5-INSTRUMENTATION
```

The Kotlin instrumentation is applied, compiles, and is installed; it is a pure logging change with no behavioural impact, so it is safe to commit on its own. T5 was **not** reproduced this run, so the holder identity remains unobserved — the instrumentation now in place will capture `attempt=<id> phase=<PHASE>` **whenever** the contention next occurs (and the earlier `RESUME` occurrence suggests it is intermittent, not deterministic).

Recommended sequencing:
1. **PROCEED TO COMMIT-T5-INSTRUMENTATION** — commit `AutoModeController.kt` (one file, logging-only).
2. **PROCEED TO DOCS-UPDATE-v1.0.8** — record: C3 fix (`4c013e8`), C5 formally gated (defect deferred to `C5-TRIAGE-DEVICE`), T5 instrumentation landed, T5 not reproduced this run.

Do **not** open a production-fix phase: neither the C5 finding (test defect) nor the T5 observation (a designed guard, benign on the observed path) is a confirmed production defect. A future `C5-TRIAGE-DEVICE` and an opportunistic T5 re-capture (when contention next occurs) are the natural follow-ups.

No push, no tag. Report in English only.
