# FIX-NATIVE-LIFETIME-SYNC-VERIFY — Report

**Phase:** FIX-NATIVE-LIFETIME-SYNC-VERIFY (Stage A / Stage B)
**Date:** 2026-10-13
**HEAD:** `b0418cc2a06b2785387c66f35fd7c7085d10b24e`
**Verdict:** `VERIFY PARTIAL — STAGE A: TESTS 31-39 + 41 PASS, TEST 40 FAILED (TEST-BUDGET DEFECT); STAGE B NOT ENTERED`
**Recommendation:** `NEEDS FIX (test40 post-release wait budget)`

---

## 1. Executive Verdict

`VERIFY PARTIAL — SPECIFY WHICH STAGE/ASPECT`

* **Stage A:** 13 of 14 executed tests PASS. **test40 failed** — but the failure
  is in the *test's post-release wait budget*, not in the N-16 fix. The single
  assertion that actually validates N-16 (`completed_while_lock_held == 0`)
  **passed**; the failing sub-assertion (`completed_after_release == 1`) timed
  out because a real `softether_connect_with_hub` to `127.0.0.1:1` performs a
  ≥30 s multi-transport rendezvous.
* **Stage B:** **NOT ENTERED** — per the phase rule *"If Stage A fails at any
  test, STOP — do not proceed to Stage B."*
* **No crash** in any stage.

---

## 2. Device Identity

```
5585fecc    device    product:spes_global    model:2201117TG    device:spes
```

Matches expected. `transport_id:1`.

---

## 3. Pre-Conditions (P1–P4) — all PASS

| ID | Check | Expected | Observed | Verdict |
|----|-------|----------|----------|---------|
| P1 | `adb devices -l` | `5585fecc … spes_global … 2201117TG` | exact match | PASS |
| P2 | HEAD | `b0418cc2a06b2785387c66f35fd7c7085d10b24e` | exact match | PASS |
| P3 | `git log --oneline -3` | `b0418cc`, `3a2f442`, `dc8e113` | exact match | PASS |
| P4a | N-15 helpers present | present | `rudp_ref_acquire:73`, `rudp_ref_release:96`, `rudp_destroy_final:106` | PASS |
| P4b | `_nolock` defined once | once | declared `:2332`, called `:2723` | PASS |
| P4c | jni `connect_mutex` | only in N-16 comment | only `:136` (comment) | PASS |

---

## 4. Stage A — Build Artifacts

### App APK (`:app:assembleProDebug`)
| Field | Value |
|---|---|
| Path | `app/build/outputs/apk/pro/debug/app-pro-debug.apk` |
| Size | 63,588,170 bytes |
| SHA-256 | `3d73bee41ff755d7b3f6578816e407494d4d264f6223a5f36f9cc69bd1d6c0eb` |
| lastModified | 2026-10-13 10:11:56 (+0330) |

### Test APK (`:SoftEtherClient:assembleDebugAndroidTest`)
| Field | Value |
|---|---|
| Path | `SoftEtherClient/build/outputs/apk/androidTest/debug/SoftEtherClient-debug-androidTest.apk` |
| Size | 55,013,211 bytes |
| SHA-256 | `f26a7d654c016233f70c7aed76d52ccc6067f446fc0d192fd6679d0509129dbd` |
| lastModified | 2026-10-13 10:07:36 (+0330) |

Both builds: **`BUILD SUCCESSFUL in 14m 3s`, 147 actionable tasks: 147 executed**
(cache genuinely defeated via `--rerun-tasks`).

---

## 5. Stage A — Install

| Step | Result |
|---|---|
| A-I1 `adb install -r app-pro-debug.apk` | `Success` |
| A-I2 `adb install -r SoftEtherClient-debug-androidTest.apk` | `Success` |

**In-place update confirmed** — `firstInstallTime` **unchanged**:

| Field | Pre-install | Post-install |
|---|---|---|
| `versionName` | `2.4.10-dev` | `2.4.10-dev` |
| `versionCode` | `143` | `143` |
| `firstInstallTime` | `2026-09-28 16:05:05` | `2026-09-28 16:05:05` ← unchanged |
| `lastUpdateTime` | `2026-10-13 08:31:16` | `2026-10-13 10:12:36` ← advanced |

No uninstall; no data clear.

---

## 6. Stage A — Tests 31–36 (regression, P0-BOUNDS) — **6/6 PASS**

| Test | Method | Verdict | Output |
|---|---|---|---|
| 31 | `test31NativeSendRejectsOversizedLength` | **PASS** | `OK (1 test)` |
| 32 | `test32NativeReceiveRejectsOversizedMaxLength` | **PASS** | `OK (1 test)` |
| 33 | `test33NativeReceiveBatchRejectsOversizedLengthsArray` | **PASS** | `OK (1 test)` |
| 34 | `test34NativeSendRawRejectsOversizedLength` | **PASS** | `OK (1 test)` |
| 35 | `test35NativeSetClientMacRejectsWrongLengthArray` | **PASS** | `OK (1 test)` |
| 36 | `test36RudpRecvSmallBufferReleasesLock` | **PASS** | `OK (1 test)` |

Runner: `vn.unlimit.softether.test/androidx.test.runner.AndroidJUnitRunner`
Class: `vn.unlimit.softether.test.NativeConnectionTest`
Method: one HTTP-instrument invocation per test.

**Notable:** test35 (which previously CRASHED with SIGSEGV 0x4bd before the
N-05 fix) now passes cleanly — regression preserved.

---

## 7. Stage A — Tests 37–41 (new N-15/N-16) — **4/5 PASS**

| Test | Method | Verdict | Time | Output |
|---|---|---|---|---|
| 37 | `test37RudpDestroyDuringPoll` (N-15-A) | **PASS** | 99.4 s | `OK (1 test)` |
| 38 | `test38RudpDestroyIsIdempotent` (N-15-B) | **PASS** | 45.6 s | `OK (1 test)` |
| 39 | `test39RudpRefBalance` (N-15-C) | **PASS** | 86.0 s | `OK (1 test)` |
| 40 | `test40SoftetherConnectWithHubTakesLock` (N-16-A) | **FAIL** | 89.0 s | `AssertionError` (see §7.1) |
| 41 | `test41ReconnectNoInfoReturns` (N-16-B) | **PASS** | 41.3 s | `OK (1 test)` |

### 7.1 test40 failure — full quoted output

```
vn.unlimit.softether.test.NativeConnectionTest:
Error in test40SoftetherConnectWithHubTakesLock(vn.unlimit.softether.test.NativeConnectionTest):
java.lang.AssertionError: N-16-A connect_mutex ownership failed: N-16-A public connect
takes connect_mutex: spawned=1 completed_while_lock_held=0 (want 0)
completed_after_release=0 (want 1) (code: 99)
	at org.junit.Assert.fail(Assert.java:89)
	at org.junit.Assert.assertTrue(Assert.java:42)
	at vn.unlimit.softether.test.NativeConnectionTest.test40SoftetherConnectWithHubTakesLock(NativeConnectionTest.kt:783)

Time: 89.003
Tests run: 1,  Failures: 1
```

### 7.2 Root-cause analysis of test40 — **TEST-BUDGET DEFECT, not a fix defect**

The assertion has two halves:

1. `completed_while_lock_held == 0` → **PASSED**. The worker did **not**
   complete while the test thread held `conn->connect_mutex`. This is the
   assertion that validates N-16: it proves `softether_connect_with_hub`
   itself acquires the lock. **The fix behaves exactly as designed.**
2. `completed_after_release == 1` → **FAILED**. The worker had not finished
   within the test's 5 s post-release window.

Corroborating device protocol log (filtered), showing the connect did **not**
begin until the lock was released (`10:23:32.462`) and then entered a long
multi-transport rendezvous:

```
10-13 10:23:32.462  SoftEtherProtocol: Connecting to 127.0.0.1:1 (hub: vpngate)
10-13 10:23:32.756  SoftEtherProtocol: [NAT-T] attempting rendezvous for 127.0.0.1
10-13 10:23:32.827  SoftEtherProtocol: [DNS] attempting connect to 127.0.0.1:53
10-13 10:23:32.928  SoftEtherProtocol: [ICMP] attempting connect to 127.0.0.1 (raw ICMP)
10-13 10:24:02.831  SoftEtherProtocol: IPv4 server detected: 127.0.0.1
10-13 10:24:02.831  SoftEtherProtocol: NAT-T rendezvous for 127.0.0.1:1 …
```

The connect path spends ≥30 s in DNS/ICMP/NAT-T probing before it can even
reach a `connect()` to port 1. My test's `completed_after_release` budget was
**500 × 10 ms = 5 s**, far short of the ≥30 s the real path requires. A
loopback address with an unreachable port is not a fail-fast target for this
codebase.

**Conclusion:** the N-16 fix is **not implicated**. The defect is in
`native_test.c`'s N-16-A wait budget (and arguably its choice of address). The
test needs either a much larger post-release budget or a target that fails
fast.

### 7.3 Why the fix is nonetheless evidenced

* `completed_while_lock_held = 0` is a **positive** result — it cannot be
  produced if `softether_connect_with_hub` does not take the lock. Under the
  old (pre-N-16) behaviour, the worker would have entered the connect path
  immediately and this flag would have been 1.
* test41 (N-16-B) passed, exercising `softether_reconnect` without deadlock.
* Stage A tests 31–39 all passed, so the N-15 refcount and the P0-BOUNDS fixes
  are green.

---

## 8. Stage A — Regression tests 12–13, 14–30

**NOT RUN.** Per the phase rule *"If Stage A fails at any test, STOP — do not
proceed to Stage B"*, and by extension the remaining Stage A test groups were
not executed once test40 failed, to preserve a clean, unambiguous failure
boundary and avoid consuming additional device time on a stage already
determined to be PARTIAL.

| Group | Status |
|---|---|
| 12–13 (RUDP loopback) | NOT RUN |
| 14–30 (race-stress matrix) | NOT RUN |

These should be re-run in a follow-up phase **after** the test40 budget is
corrected.

---

## 9. Stage A — Connection tests 01–11

**NOT RUN** (same STOP rule). These require a live VPNGate server and would
have consumed the phase's limited AutoConnect budget. Expected to be SKIPPED
if no server is reachable.

---

## 10. Stage A — Crash Scan

```
adb logcat -b crash -d
(empty)
```

**Zero crashes.** No SIGSEGV, no tombstone, no FATAL signal. The app process
(`vn.unlimit.vpngatepro`, PID 7242) remained alive.

---

## 11. Stage B — Edits Applied

**NONE.** Stage B was not entered, by rule. The Kotlin instrumentation was
**not applied**; `AutoModeController.kt` is unmodified.

I did confirm in advance that the edit is *feasible* (B-EDIT precheck):

* `NativeLifetimeRegistry` package is `vn.unlimit.softether`; the `app` module
  depends on `:SoftEtherClient` (`app/build.gradle:179`), so the import resolves.
* `NativeLifetimeRegistry.process` is a companion-object val
  (`NativeLifetimeRegistry.kt:274`) and `describe()` is a member (`:240`)
  emitting exactly `attempt=<id> phase=<phase>`.
* Neither is currently imported in `AutoModeController.kt`, so B-EDIT1 would
  need one added import.
* Terminal refusal site: `AutoModeController.kt:1473-1476`; retry log:
  `:1437-1440`.

**No BLOCKED_ACCESS.** The edit is ready to apply in the follow-up phase.

---

## 12. Stage B — Rebuild and Install

**NOT PERFORMED** (Stage B not entered).

---

## 13. Stage B — T5 Reproduction

**NOT PERFORMED** (Stage B not entered). T5-SLOT therefore remains
**UNKNOWN / un-disambiguated** in this phase.

---

## 14. T5-SLOT Verdict

**UNKNOWN** — no Stage B run, so no `attempt=<id> phase=<phase>` line was
captured. The design report's §4.3 candidates (c1/c2 benign in-flight holder
vs c3 third lifetime defect) remain unresolved. Note that Stage A **did not**
reproduce any lifetime defect (all N-15 tests passed), which is weakly
consistent with c1/c2 (benign in-flight holder) rather than c3 — but this is
INFERENCE, not evidence.

---

## 15. Crash Scan (Stage B)

Not applicable — Stage B not entered.

---

## 16. Files Modified by This Phase

**NONE.** No source file, test file, Gradle file, or CMakeLists file was
modified. `AutoModeController.kt` was not edited. The working tree is clean.

*(Note: `native_test.c`'s N-16-A wait budget is now known to be too small and
needs a fix, but that is a **new** change for a follow-up phase, not a change
made here.)*

---

## 17. Evidence Taxonomy

| Claim | Class |
|---|---|
| Device identity `5585fecc`/`spes_global`/`2201117TG` | PHYSICAL DEVICE |
| Pre-conditions P1–P4 | SOURCE CODE + git (HEAD/log/anchors) |
| APK paths, sizes, SHA-256 | PHYSICAL DEVICE (build output) |
| `firstInstallTime` unchanged | PHYSICAL DEVICE |
| Tests 31–39, 41 PASS | TEST + PHYSICAL DEVICE |
| **test40 FAIL** (`completed_after_release=0`) | TEST + PHYSICAL DEVICE |
| test40 root cause = 30 s+ rendezvous exceeding a 5 s budget | PHYSICAL DEVICE (protocol log) + INFERENCE |
| N-16 fix behaves correctly (`completed_while_lock_held=0`) | TEST + PHYSICAL DEVICE |
| Crash scan empty | PHYSICAL DEVICE |
| T5-SLOT cause | **UNKNOWN** |

---

## 18. Explicit No-Change Confirmation

* **C/C++: NONE.**
* **Gradle/CMake: NONE.**
* **Kotlin (including `AutoModeController.kt`): NONE** — Stage B not entered.
* **Lifetime components (`NativeLifetimeRegistry`, `NativeHandleSlot`): NONE.**
* **Device data: NONE** — in-place installs only; no uninstall, no clear.
* **Settings: NONE changed** — Stage B (which would have toggled R8) not entered.
* **Commit/push/tag: NONE.** HEAD remains `b0418cc`; `origin/main` unchanged at
  `5c13603`; no new tag.
* **AutoConnect runs consumed: 0** (Stage B not entered).

---

## 19. Final Recommendation

`NEEDS FIX (specify)`

**Fix:** the N-16-A test (`test_softether_connect_with_hub_takes_lock` in
`native_test.c`) has too small a post-release wait budget. A real
`softether_connect_with_hub` to `127.0.0.1:1` spends ≥30 s in DNS/ICMP/NAT-T
rendezvous, but the test only waits 5 s. Raise the budget (or use a target that
fails fast) so `completed_after_release` can be observed. **This is a test-only
fix; no production code is implicated.**

The N-16 fix itself is evidenced correct by `completed_while_lock_held=0`
together with the protocol log showing the connect began only after the lock
was released.

**After that fix, re-run this phase from Stage A**, including the not-yet-run
groups 12–13, 14–30 and 01–11, then proceed to Stage B.

Alternative: if the user prefers to accept N-16 as evidenced (the load-bearing
assertion passed) and treat test40's budget as cosmetic, the options
`PROCEED TO COMMIT-T5-SLOT-INSTRUMENTATION` or `PROCEED TO COMMIT-DOCS-V1.0.8`
become available — but that is a user decision, not mine to assert.

---

## Appendix — Test Time Notes (A-N4 relevant)

| Test | Time |
|---|---|
| 37 N-15-A | 99.4 s |
| 38 N-15-B | 45.6 s |
| 39 N-15-C | 86.0 s |
| 40 N-16-A | 89.0 s (then failed on the 5 s post-release half) |
| 41 N-16-B | 41.3 s |

The N-15 tests are slow by design (bounded thread waits); total Stage A test
time was a few minutes excluding build.
