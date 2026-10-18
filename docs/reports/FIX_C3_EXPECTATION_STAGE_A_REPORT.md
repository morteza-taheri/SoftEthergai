# FIX-C3-EXPECTATION-AND-COMPLETE-STAGE-A — Report

**Phase:** `FIX-C3-EXPECTATION-AND-COMPLETE-STAGE-A`
**Date:** 2026-10-13
**Device:** `5585fecc` / `2201117TG` (spes_global)
**Language:** English only.

---

## 1. Executive Verdict

```
FIX APPLIED — STAGE A INCOMPLETE
```

The C3 rule fix (test-only) is applied, compiled, and **verified working on device**: `test27B3sCaseC3` now **PASSES** (`ok=1`, `premise=100`).

Stage A did **not** fully pass: `test30B3sCaseC5` **failed** in the main run (`resp=1 timeout=0 premise=0 code 99`). C5 is **flaky** (it PASSED on an immediate re-run) and is **unrelated to the C3 edit** — it uses the unchanged generic `else` verdict branch, and the failure reproduces identically in the prior run (`_stageA_results.log`, 11:41). Per the phase rule ("If ANY test in A1–A4 fails … STOP … Do NOT proceed to Stage B"), **Stage B was NOT entered**.

---

## 2. Pre-Conditions

| ID | Check | Result |
|----|-------|--------|
| P1 | Device enumerates `5585fecc` / `2201117TG` | **PASS** — `5585fecc device product:spes_global model:2201117TG transport_id:1` |
| P2 | HEAD is `38d9d33` (test40-fix commit) | **PASS** — `38d9d33 test(native): widen N-16-A post-release budget to 90s` |
| P3 | C3 verdict clause present (`grep "stale == 0"`) | **PASS (state note)** — see below |
| P4 | C4 rule present (`grep "premise > 0"`) | **PASS** — multiple matches (C4 `:3339`, C6 `:3356`, C3 `:3379`, generic `:3384`) |

**P3 state note (important).** The `stale == 0` clause now lives at `native_test.c:3384` inside the **generic `else`** branch, **not** in C3. The C3-specific branch (with the new rule) was **already present in the working tree** (uncommitted) when this phase began — i.e. a prior execution of this phase had applied E2+E4 but had not completed the build/Stage A. The working-tree edit is **byte-exact** with the spec's AFTER state (verified in §3), so no re-edit was needed. Working tree contained exactly one modified tracked file: `SoftEtherClient/src/main/cpp/test/native_test.c`.

---

## 3. C3 Rule Edit

**Status: present, verified, unchanged by this run.** `git diff` shows **26 insertions, 0 deletions**, one hunk, one file. The edit adds the C3 branch verbatim as specified:

```c
    } else if (case_id == B3S_CASE_C3) {
        // C3 targets the same REQUEST-vs-ACK race as C4. As C4's own
        // comment (:3334-3338) establishes, a stale success IS the
        // genuine observation of that ordering, and ConnectionController
        // discards stale results at two independent layers
        // (defence-in-depth guard :1343; authoritative installTun :466-468).
        // The previous "stale == 0" clause contradicted C4's own
        // semantics and demanded the impossible: the race C3 exercises
        // cannot be prevented inside dhcp_client.c as written
        // (entry-only state check :414; time-only wait loop :339).
        // C3 therefore accepts the observation and asserts that the
        // race occurred (premise > 0). The staleStart / staleDone
        // counters remain reported in the message for diagnostics.
        //
        // DIAGNOSTIC-ONLY METRICS: staleStart and staleDone are NOT
        // pass/fail criteria for C3. They remain in the message so the
        // ordering can be characterised. A staleDone rate above 25% of
        // iterations (STALE_DONE_RATE_HIGH) indicates a different
        // problem worth investigating in its own right — it means a
        // DHCP success was published AFTER the teardown completed,
        // rather than merely inside the REQUEST-vs-ACK window — but it
        // is NOT a C3 failure and does not flip this verdict.
        ok = !harness_bad && tot.premise > 0;
        if (tot.n > 0 && (tot.stale_done * 4) > tot.n)
            snprintf(extra, sizeof(extra), " STALE_DONE_RATE_HIGH(done=%d n=%d)",
                     tot.stale_done, tot.n);
    } else {
        ok = !harness_bad && stale == 0 && tot.premise > 0;
    }
```

**E1–E4 compliance**
- **E1** — full verdict block at `:3322-3385` read. ✅
- **E2** — C3 clause is `ok = !harness_bad && tot.premise > 0;` (the `stale == 0` conjunct removed **for C3 only**). ✅
- **E3** — message format unchanged: `snprintf(msg, …)` at `:3396-3403` still emits `staleStart=%d staleDone=%d`; the new `STALE_DONE_RATE_HIGH` text is appended to the existing `extra` buffer (`%s`). Other case rules unchanged: C4 `:3339`, C6 `:3356`, generic `else` (C1/C2/C5) `:3384` — none touched. B3s harness, dhcp_client.c behaviour, C3 trigger/iteration count (`100`) — none touched. ✅
- **E4** — diagnostic-only comment block present above the C3 branch; STALE_DONE rate recorded when >25% (see §13). ✅

---

## 4. Test APK Rebuild

| Item | Value |
|------|-------|
| Command | `JAVA_HOME="…/Android Studio/jbr" CI=1 ./gradlew :SoftEtherClient:assembleDebugAndroidTest --no-daemon --rerun-tasks` |
| Result | **BUILD SUCCESSFUL in 3m 32s**, 63 actionable tasks executed |
| Native build | `configureCMakeDebug` + `buildCMakeDebug` ran for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |
| Output | `SoftEtherClient/build/outputs/apk/androidTest/debug/SoftEtherClient-debug-androidTest.apk` |
| SHA-256 | `a0a850313ba0624e076775c9d2c24fbfa73c9e0f32cd07111d4d98577b4e8be2` |
| Install | `adb install -r …` → **Success** |

**Fix-compiled-in verification (independent of test results):** the new string literal `STALE_DONE_RATE_HIGH` is present **1×** in the packaged `lib/arm64-v8a/libsoftether_test.so` and **0×** in the production `lib/arm64-v8a/libsoftether.so`. The object file `…/5v273202/arm64-v8a/CMakeFiles/softether_test.dir/test/native_test.c.o` (mtime 11:11, after the 11:08 source edit) also contains it. The rebuild is byte-identical to the pre-existing artifact — the build is deterministic and the fix is in the tested binary.

---

## 5. Stage A — Tests 31–41 (hermetic P0-BOUNDS + N-15/N-16)

**Result: 11/11 PASS.**

| # | Test | Verdict | Time (s) |
|---|------|---------|----------|
| 31 | test31NativeSendRejectsOversizedLength | PASS | 41.664 |
| 32 | test32NativeReceiveRejectsOversizedMaxLength | PASS | 41.330 |
| 33 | test33NativeReceiveBatchRejectsOversizedLengthsArray | PASS | 37.611 |
| 34 | test34NativeSendRawRejectsOversizedLength | PASS | 28.519 |
| 35 | test35NativeSetClientMacRejectsWrongLengthArray | PASS | 14.495 |
| 36 | test36RudpRecvSmallBufferReleasesLock | PASS | 15.451 |
| 37 | test37RudpDestroyDuringPoll | PASS | 46.625 |
| 38 | test38RudpDestroyIsIdempotent | PASS | 35.906 |
| 39 | test39RudpRefBalance | PASS | 34.217 |
| 40 | test40SoftetherConnectWithHubTakesLock | PASS | 46.324 |
| 41 | test41ReconnectNoInfoReturns | PASS | 8.827 |

---

## 6. Stage A — Tests 12–13 (RUDP loopback)

**Result: 2/2 PASS.**

| # | Test | Verdict | Time (s) |
|---|------|---------|----------|
| 12 | test12RudpV2Loopback | PASS | 97.828 |
| 13 | test13RudpTransportLoopback | PASS | 70.697 |

---

## 7. Stage A — Tests 14–30 (race-stress matrix)

**Result: 16/17 PASS — 1 FAIL (`test30B3sCaseC5`, flaky).**

| # | Test | Verdict | Time (s) |
|---|------|---------|----------|
| 14 | test14A3NatTForceCloseVsDestroy | PASS | 40.078 |
| 15 | test15A6NatTStress | PASS | 35.396 |
| 16 | test16B3DhcpVsDisconnect | PASS | 34.830 |
| 17 | test17A3Stage100 | PASS | 18.208 |
| 18 | test18A3Stage1000 | PASS | 17.628 |
| 19 | test19A3Stage10000 | PASS | 49.454 |
| 20 | test20A3Stage100000 | PASS | 311.461 |
| 21 | test21A6Stage100000 | PASS | 322.517 |
| 22 | test22B3Stage100 | PASS | 47.293 |
| 23 | test23B3Stage1000 | PASS | 49.315 |
| 24 | test24B3Stage10000 | PASS | 54.516 |
| 25 | test25B3sCaseC1 | PASS | 40.099 |
| 26 | test26B3sCaseC2 | PASS | 42.503 |
| **27** | **test27B3sCaseC3** | **PASS** | 38.031 |
| 28 | test28B3sCaseC4 | PASS | 35.421 |
| 29 | test29B3sCaseC6 | PASS | 44.037 |
| **30** | **test30B3sCaseC5** | **FAIL** (flaky; re-run PASS) | 47.053 / 30.390 |

**C3 (test27) — the fix target — actual run values** (`adb logcat`, `B3s done (case 3)`):

```
B3s C3 n=100 before=14 discResp=6 staleStart=53 staleDone=27
       invReject=0 timeout=0 setup=0 resp=0 join=0 clean=0
       unexp=0 hang=0 crash=0 retAT=27
       STALE_DONE_RATE_HIGH(done=27 n=100) ok=1
```

`premise` for C3 = `before(14) + discResp(6) + staleStart(53) + staleDone(27) = 100 = n` → `premise > 0` → **`ok=1`**. Under the old rule (`stale == 0`) this run would have **failed** (`staleStart+staleDone = 80`). The fix is what makes C3 pass, and it does so by asserting the race occurred, exactly as C4 does.

**C5 (test30) failure detail** (main run): `B3s C5 n=1 before=0 discResp=0 staleStart=0 staleDone=0 invReject=0 timeout=0 setup=0 resp=1 join=0 clean=0 unexp=0 hang=0 crash=0 retAT=0 (code: 99)`. Root cause: the silent-responder schedule produced a **responder error** (`responder_error != NONE`), which the classifier at `:3217-3218` routes to `B3S_CAT_RESP` → `harness_bad=1` and `premise=0` in the **unchanged generic `else` branch** (`:3384`). Re-run of the identical binary/APK: `B3s C5 … timeout=1 … resp=0 … ok=1` → **PASS**. This is a **timing-flaky** test, not a regression from the C3 edit.

---

## 8. Stage A — Tests 01–11 (connection tests)

**Result: 11/11 PASS. No SKIPs.** A VPNGate/loopback path was reachable; every connection test completed successfully.

| # | Test | Verdict | Time (s) | Note |
|---|------|---------|----------|------|
| 01 | test01TcpConnection | PASS | 35.295 | |
| 02 | test02TlsHandshake | PASS | 44.468 | |
| 03 | test03SoftEtherHandshake | PASS | 44.397 | |
| 04 | test04Authentication | PASS | 30.714 | interrupted (USB drop), resumed |
| 05 | test05SessionEstablishment | PASS | 16.440 | interrupted (USB drop), resumed |
| 06 | test06DataTransmission | PASS | 38.266 | interrupted (USB drop), resumed |
| 07 | test07Keepalive | PASS | 81.540 | interrupted (USB drop), resumed |
| 08 | test08FullConnectionLifecycle | PASS | 31.759 | interrupted (USB drop), resumed |
| 09 | test09MultipleServers | PASS | 64.933 | interrupted (USB drop), resumed |
| 10 | test10Dhcp | PASS | 43.078 | interrupted (USB drop), resumed |
| 11 | test11InternetConnectivity | PASS | 36.520 | interrupted (USB drop), resumed |

**Execution note:** during the first pass the device dropped off USB (`adb: no devices/emulators found`) right at test04, so tests 04–11 produced no output. The device re-enumerated (~1 min) **without rebooting** (`uptime` ≈ 6:58), instrumentation remained registered, and tests 04–11 were then run cleanly (all PASS). No SKIP was reported by any test.

---

## 9. Stage A — Crash Scan

**Result: CLEAN.**

- `adb logcat -b crash -d` → **empty**.
- Main-buffer scan for `FATAL EXCEPTION | Fatal signal | SIGSEGV | SIGABRT | tombstone | libsoftether` → **no matches**.
- The USB drop at test04 was a **transport-level disconnect, not a crash**: the device did not reboot (uptime ≈ 6:58), and no tombstone/FATAL entry was produced.

---

## 10. Stage B — Edits Applied

**NOT ENTERED.** Stage A did not fully pass (`test30/C5` failed in the main run), so per the phase constraint Stage B was not started. **`app/src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt` was NOT modified** (`git status` shows it unchanged).

---

## 11. Stage B — T5 Reproduction

**NOT ENTERED.** No AutoConnect run was performed. (Budget: 0 of 2 runs consumed.)

---

## 12. T5-SLOT Verdict

**NOT APPLICABLE** — Stage B not entered.

---

## 13. STALE_DONE Observation

- **C3 staleDone = 27 / n = 100 → 27%**, which **exceeds the 25% threshold**. The native verdict emitted `STALE_DONE_RATE_HIGH(done=27 n=100)`.
- **Flagged as a concern (diagnostic only, NOT a failure).** Per E4, this is not a C3 pass/fail criterion. The message reads: a DHCP success was published **after** the teardown completed (rather than merely inside the REQUEST-vs-ACK window) in 27% of iterations — worth investigating in its own right, but it does **not** flip the C3 verdict.
- For context, `staleStart = 53%` (in-window) and `discResp/before = 6/14` also occurred; `premise` reached 100/100.

---

## 14. Files Modified

| File | Change | Status |
|------|--------|--------|
| `SoftEtherClient/src/main/cpp/test/native_test.c` | C3 verdict branch (E2) + diagnostic comment (E4); **26 insertions, 0 deletions** | modified (uncommitted) |
| `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt` | — | **NOT modified** (Stage B not entered) |

Untracked evidence/report files written at the repo root (`_c3fix_*` logs, this report). No other tracked file changed.

---

## 15. Evidence Taxonomy

| Tier | Evidence | Weight |
|------|----------|--------|
| **T1 — On-device authoritative** | `am instrument` per-test `OK (1 test)` / `AssertionError` for all 41 tests; `NativeTest` logcat `B3s done (case N)` lines (C3, C5); empty crash buffer | Primary |
| **T2 — Build artifacts** | Forced rebuild log (63 tasks, CMake ×4 ABIs); APK SHA-256; `STALE_DONE_RATE_HIGH` string present in packaged `libsoftether_test.so`, absent in `libsoftether.so`; object-file mtime after source edit | Primary (proves fix compiled in) |
| **T3 — Cross-run corroboration** | Prior `_stageA_results.log` (11:41) shows the **identical** C5 failure and 40 PASS — establishes C5 as pre-existing/flaky | Supporting |
| **T4 — Working-tree / git** | `git diff` (single hunk, test-only), `git status` (only `native_test.c` modified; `AutoModeController.kt` clean) | Supporting |
| **T5 — Device state** | `uptime` ≈ 6:58 (no reboot), `getprop sys.boot_completed=1`, instrumentation still registered after the USB drop | Supporting |

---

## 16. Explicit No-Change Confirmation

- **Production C/C++:** NONE. (Only `native_test.c`, a test file, changed.)
- **`softether_rudp.*` / `softether_protocol.c` / `softether_jni.c`:** NONE.
- **`dhcp_client.c`:** NONE.
- **Gradle / CMakeLists:** NONE.
- **Lifetime components** (`NativeLifetimeRegistry`, `NativeHandleSlot`): NONE.
- **Push:** NONE. **Tag:** NONE.
- **Uninstall / data clear:** NONE.
- **Settings changed via app UI:** NONE (no app UI was driven).
- **AutoConnect runs:** 0 of 2 budget used.

---

## 17. Final Recommendation

```
WAIT FOR EXPLICIT USER DECISION
```

**Rationale.** The C3 expectation fix is applied, compiled, and **verified working** (`test27/C3` now PASSES; §7). However, Stage A did **not** fully pass because `test30B3sCaseC5` **failed** in the main run. C5 is:
1. **Pre-existing** — it fails identically in the prior run's `_stageA_results.log` (11:41).
2. **Unrelated to the C3 edit** — it uses the unchanged generic `else` branch (`:3384`); the C3 edit only touches `B3S_CASE_C3`.
3. **Flaky** — an immediate re-run of the same APK PASSED (`timeout=1, resp=0, ok=1`).
4. **Out of authorized scope** — C5's own source comment states it is "NOT executed in this phase — physical execution requires separate approval"; fixing it would require editing the C5 verdict/harness, which this phase does not authorize.

Because the phase rule mandates stopping and **not** entering Stage B on any A1–A4 failure, Stage B (T5-SLOT) remains deferred. Suggested options for the user:
- **(A)** Accept C5 as a known-flaky, out-of-scope test and authorize **PROCEED TO COMMIT-C3-AND-T5-INSTRUMENTATION** (with C5 explicitly excluded/triaged), then run Stage B.
- **(B)** Authorize a dedicated **C5 triage** phase (investigate the responder-error race in the silent-responder schedule) before any further progress.
- **(C)** **PROCEED TO DOCS-UPDATE-v1.0.8** to record the C3 fix and the C5 flakiness finding.

No push, no tag. Report in English only.
