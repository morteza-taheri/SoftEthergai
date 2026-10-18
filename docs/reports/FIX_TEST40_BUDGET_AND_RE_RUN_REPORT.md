# FIX-TEST40-BUDGET-AND-RE-RUN — Report

**Phase:** FIX-TEST40-BUDGET-AND-RE-RUN (Stage A / Stage B)
**Date:** 2026-10-13
**HEAD:** `b0418cc2a06b2785387c66f35fd7c7085d10b24e` (unchanged)
**Verdict:** `STAGE_A_INCOMPLETE — test27B3sCaseC3 FAILS DETERMINISTICALLY; STAGE B NOT ENTERED`
**Recommendation:** `NEEDS FIX (specify)`

---

## 1. Executive Verdict

`STAGE_A_INCOMPLETE`

* **test40 is FIXED and now PASSES** (68.5 s alone; 51.8 s in the suite) — the
  budget widening worked exactly as intended, confirming the earlier
  diagnosis that the N-16 fix was always correct.
* **All hermetic groups pass except one:** tests 31–41 **11/11 PASS**, tests
  12–13 **2/2 PASS**, tests 14–30 **16/17 PASS**.
* **`test27B3sCaseC3` FAILS deterministically** (3/3 runs, `stale > 0` each
  time) with `crash=0 hang=0`. This is a **B3s harness accounting failure, not
  a crash**, and **evidence shows my N-15/N-16 commits did not touch the B3s
  harness** (see §5.2).
* Per the rule *"If ANY test in A7-A10 fails (not skips), STOP and report
  STAGE_A_INCOMPLETE. Do NOT proceed to Stage B"* — **Stage B was NOT
  entered.**
* No crash, no SIGSEGV, no tombstone anywhere.

---

## 2. Pre-Conditions

| ID | Check | Expected | Observed | Verdict |
|----|-------|----------|----------|---------|
| P1 | Device | `5585fecc` / `2201117TG` | `5585fecc device product:spes_global model:2201117TG transport_id:1` | PASS |
| P2 | HEAD | `b0418cc2a06b2785387c66f35fd7c7085d10b24e` | exact match | PASS |
| P3 | test40 anchor | present | assertion string at `native_test.c:3944`, function at `:3892` | PASS |

Note: the first P1/P2 invocation returned the transient sandbox error
(`sandbox-center cmd decisionRecord missing actual resource subject`); re-issued
immediately and succeeded. No impact.

---

## 3. Stage A — test40 Budget Fix

### Diff (`SoftEtherClient/src/main/cpp/test/native_test.c`)

```diff
@@ -3925,8 +3925,12 @@ native_test_result_t test_softether_connect_with_hub_takes_lock(void) {
 
   int completed_after_release = 0;
   if (spawned) {
-    for (int i = 0; i < 500 && !s.done; i++) {
-      usleep(10 * 1000);  // up to 5000 ms for the (failing) connect
+    // A real softether_connect_with_hub to 127.0.0.1:1 spends ~30 s in
+    // DNS/ICMP/NAT-T rendezvous before reaching connect(); the previous 5 s
+    // budget was below that and produced a false failure. 90 s gives a safe
+    // margin.
+    for (int i = 0; i < 9000 && !s.done; i++) {
+      usleep(10 * 1000);  // up to 90 s for the (failing) connect
     }
     completed_after_release = s.done ? 1 : 0;
     pthread_join(th, NULL);
```

Only the post-release wait loop changed. A3 respected: **target address
unchanged**; **`completed_while_lock_held` logic unchanged**; no other hunk.

### Build

`./gradlew :SoftEtherClient:assembleDebugAndroidTest --no-daemon --rerun-tasks`
→ **`BUILD SUCCESSFUL in 3m 31s`, 63 actionable tasks: 63 executed.**
No warnings or errors from the edited file.

New test APK SHA-256:
`219ae02338ac5c62902d800e50e8c1eacb2b0e0dfe3be3d68dd5e1610b4f41a9`
(55,013,211 bytes, mtime 2026-10-13 10:32:15 +0330)

### Install

`adb install -r SoftEtherClient-debug-androidTest.apk` → `Success` (in-place).

### test40 alone (A6)

```
vn.unlimit.softether.test.NativeConnectionTest:.
Time: 68.504
OK (1 test)
```

**test40 now PASSES.** The false failure is resolved.

---

## 4. Stage A — Tests 31–41 Re-run — **11/11 PASS**

| Test | Method | Verdict | Time |
|---|---|---|---|
| 31 | `test31NativeSendRejectsOversizedLength` | PASS | — |
| 32 | `test32NativeReceiveRejectsOversizedMaxLength` | PASS | — |
| 33 | `test33NativeReceiveBatchRejectsOversizedLengthsArray` | PASS | — |
| 34 | `test34NativeSendRawRejectsOversizedLength` | PASS | — |
| 35 | `test35NativeSetClientMacRejectsWrongLengthArray` | PASS | — |
| 36 | `test36RudpRecvSmallBufferReleasesLock` | PASS | — |
| 37 | `test37RudpDestroyDuringPoll` (N-15-A) | PASS | 20.4 s |
| 38 | `test38RudpDestroyIsIdempotent` (N-15-B) | PASS | 20.1 s |
| 39 | `test39RudpRefBalance` (N-15-C) | PASS | 20.1 s |
| **40** | **`test40SoftetherConnectWithHubTakesLock` (N-16-A)** | **PASS** | **51.8 s** |
| 41 | `test41ReconnectNoInfoReturns` (N-16-B) | PASS | 20.1 s |

All five N-15/N-16 tests now pass. **Both fixes are device-confirmed.**

---

## 5. Stage A — Tests 12–13, 14–30

### 5.1 Summary

| Group | Tests | Result |
|---|---|---|
| 12–13 (RUDP loopback) | 2 | **2/2 PASS** |
| 14–30 (race-stress matrix) | 17 | **16/17 PASS — test27 FAILED** |

**12–13:**

| Test | Verdict | Time |
|---|---|---|
| `test12RudpV2Loopback` | PASS | 21.1 s |
| `test13RudpTransportLoopback` | PASS | 20.5 s |

**14–30 (17 tests, runtimes shown for slow stages):**

| # | Test | Verdict | Time |
|---|---|---|---|
| 14 | `test14A3NatTForceCloseVsDestroy` | PASS | 20.1 s |
| 15 | `test15A6NatTStress` | PASS | 49.6 s |
| 16 | `test16B3DhcpVsDisconnect` | PASS | 20.1 s |
| 17 | `test17A3Stage100` | PASS | 20.4 s |
| 18 | `test18A3Stage1000` | PASS | 23.0 s |
| 19 | `test19A3Stage10000` | PASS | 50.0 s |
| 20 | `test20A3Stage100000` | PASS | **152.1 s** (slow stage) |
| 21 | `test21A6Stage100000` | PASS | **135.0 s** (slow stage) |
| 22 | `test22B3Stage100` | PASS | 20.4 s |
| 23 | `test23B3Stage1000` | PASS | 23.2 s |
| 24 | `test24B3Stage10000` | PASS | 50.7 s |
| 25 | `test25B3sCaseC1` | PASS | 20.1 s |
| 26 | `test26B3sCaseC2` | PASS | 39.9 s |
| **27** | **`test27B3sCaseC3`** | **FAIL** | 32.5 s |
| 28 | `test28B3sCaseC4` | PASS | 11.1 s |
| 29 | `test29B3sCaseC6` | PASS | 47.2 s |
| 30 | `test30B3sCaseC5` | PASS | 44.2 s |

Slow stages reported as required: `test20A3Stage100000` = **152.1 s PASS**,
`test21A6Stage100000` = **135.0 s PASS**, `test24B3Stage10000` = **50.7 s PASS**.

### 5.2 test27B3sCaseC3 — failure detail

**Exact assertion message (deterministic, 3/3 runs):**

```
java.lang.AssertionError: B3s C3 failed: B3s C3 n=100 before=20 discResp=9
staleStart=55 staleDone=16 invReject=0 timeout=0 setup=0 resp=0 join=0
clean=0 unexp=0 hang=0 crash=0 retAT=16 (code: 99)
	at org.junit.Assert.fail(Assert.java:89)
	at org.junit.Assert.assertTrue(Assert.java:42)
	at vn.unlimit.softether.test.NativeConnectionTest.test27B3sCaseC3(NativeConnectionTest.kt:556)
```

**Three consecutive runs (proof of determinism):**

| Run | n | before | discResp | staleStart | staleDone | crash | hang |
|---|---|---|---|---|---|---|---|
| 1 | 100 | 20 | 9 | 55 | 16 | 0 | 0 |
| 2 | 100 | 14 | 13 | 60 | 13 | 0 | 0 |
| 3 | 100 | 16 | 10 | 55 | 19 | 0 | 0 |

**Failure mechanism (from the harness source):**

`native_test.c:3360` → for C3 (default branch):

```c
ok = !harness_bad && stale == 0 && tot.premise > 0;
```
where `harness_bad = (setup || join || cleanup || resp || unexpected || crash || hang)`
and `stale = stale_started + stale_done`.

Observed: `harness_bad = 0`, but `stale = 71` (run 1) → **`ok = 0` → FAIL**.

C3 (`B3S_CASE_C3`, `native_test.c:2271`) schedules a disconnect at
**REQUEST observation**, racing the ACK write. Its verdict **requires zero
stale DHCP successes**. The run produced 55–60 stale-started and 13–19
stale-done results — i.e. the DHCP client reported success after teardown had
begun. `invReject=0`, `crash=0`, `hang=0`, so this is **not** a crash and
**not** an invalid-frame defect; it is a stale-result ordering outcome.

### 5.3 Is this a regression from my N-15/N-16 commits? — **NO**

Evidence:

1. **My commits did not touch the B3s harness.** `git show 3a2f442` and
   `git show b0418cc` against `native_test.c` are **insertion-only**
   (+179 / +141, zero deletions in that file), and a filtered diff for
   `b3s|dhcp_success|stress_b3s` returns **no matching hunks** in either
   commit.
2. **The harness predates my work**: `test_b3s_dhcp_success_race` and
   `B3S_CASE_C3` were introduced by `b689cfe` (the P0-BOUNDS commit), which is
   `3a2f442`'s parent.
3. **The harness code path is byte-identical** to its pre-N-15 state for this
   test.
4. C3 drives the **DHCP client** (`dhcp_client.c`) and a test-side responder —
   it does not exercise `rudp_*` or `softether_connect_with_hub`, the two
   functions my commits changed.
5. **`harness_bad = 0`** and `crash = 0` — no memory-safety symptom.

**Conclusion:** test27/C3 is a **pre-existing, reproducible, non-crashing
harness-expectation failure**, unrelated to N-15/N-16. It was simply never run
on-device in a phase before this one (the earlier phases ran tests 31–36 only),
so its failing state was not previously surfaced.

**Confidence:** SOURCE CODE + TEST + PHYSICAL DEVICE for the facts above.
The claim "pre-existing" is INFERENCE (strongly supported by the
insertion-only, harness-untouched evidence).

---

## 6. Stage A — Tests 01–11

**NOT RUN.** The phase rule says *"If ANY test in A7-A10 fails (not skips),
STOP and report STAGE_A_INCOMPLETE."* A9 failed, so A10 was not executed — to
preserve a clean failure boundary and avoid consuming the AutoConnect budget.

| Tests | Status |
|---|---|
| test01–test11 (connection tests) | NOT RUN (STOP rule) |

These require a live VPNGate server and remain to be run in the follow-up.

---

## 7. Stage A — Crash Scan

```
adb logcat -b crash -d
(empty)
```

**Zero crashes** across the entire session (tests 31–41, 12–13, 14–30). The
app process (`vn.unlimit.vpngatepro`, PID 7242) stayed alive. No SIGSEGV, no
tombstone, no FATAL signal — including during the three failing C3 runs.

---

## 8. Stage B — Edits Applied

**NONE.** Stage B was not entered (A9 failed). `AutoModeController.kt` is
unmodified. The T5-SLOT instrumentation remains deferred and ready (its
feasibility was confirmed in the prior phase; see that report §11).

---

## 9. Stage B — Rebuild and Install

**NOT PERFORMED.**

---

## 10. Stage B — T5 Reproduction

**NOT PERFORMED.** 0 AutoConnect runs consumed in this phase.

---

## 11. T5-SLOT Verdict

**UNKNOWN** — no Stage B run. Candidates c1/c2 (benign in-flight holder) vs c3
(third lifetime defect) remain unresolved. Weakly, the extensive Stage A
pass — including all N-15/N-16 lifetime tests and the A3/A6/B3 crash-stress
matrix with `crash=0` throughout — continues to give no evidence of a third
lifetime defect, but this is INFERENCE, not evidence.

---

## 12. Crash Scan (Stage B)

Not applicable — Stage B not entered.

---

## 13. Files Modified

| File | Stage | Nature |
|---|---|---|
| `SoftEtherClient/src/main/cpp/test/native_test.c` | A | test40 post-release budget widened (5 s → 90 s) |

**Exactly one file.** `AutoModeController.kt` was **not** modified (Stage B not
entered). No other file was touched.

---

## 14. Evidence Taxonomy

| Claim | Class |
|---|---|
| Pre-conditions P1–P3 | SOURCE CODE + PHYSICAL DEVICE |
| test40 budget diff | SOURCE CODE |
| test APK SHA-256, build success | PHYSICAL DEVICE + TEST |
| Tests 31–41 = 11/11 PASS | TEST + PHYSICAL DEVICE |
| Tests 12–13 = 2/2 PASS | TEST + PHYSICAL DEVICE |
| Tests 14–30 = 16/17 PASS | TEST + PHYSICAL DEVICE |
| test27 C3 FAIL, 3/3 deterministic, `stale>0`, `crash=0` | TEST + PHYSICAL DEVICE |
| C3 verdict formula `ok = !harness_bad && stale==0 && premise>0` | SOURCE CODE |
| My commits are insertion-only in `native_test.c` and never touch B3s | SOURCE CODE (git) |
| "test27 failure is pre-existing / not caused by N-15/N-16" | **INFERENCE** |
| Crash scan empty | PHYSICAL DEVICE |
| T5-SLOT cause | **UNKNOWN** |

---

## 15. Explicit No-Change Confirmation

* **C/C++ production (`softether_rudp.*`, `softether_protocol.c`,
  `softether_jni.c`): NONE.**
* **Gradle/CMake: NONE.**
* **Lifetime components (`NativeLifetimeRegistry`, `NativeHandleSlot`): NONE.**
* **`AutoModeController.kt`: NONE** (Stage B not entered).
* **Device data: NONE** — in-place installs only; no uninstall, no clear.
* **Settings: NONE changed.**
* **Commit/push/tag: NONE.** HEAD remains `b0418cc`; `origin/main` unchanged at
  `5c13603`; no new tag.
* **AutoConnect runs consumed: 0.**

---

## 16. Final Recommendation

`NEEDS FIX (specify)`

**Two separable items:**

1. **DONE, ready to commit — the test40 budget fix.**
   `native_test.c` now waits 90 s for the post-release connect, and test40
   passes. This is a test-only change; N-16 is device-confirmed correct.

2. **NEW DEFECT SURFACED — `test27B3sCaseC3`.**
   Deterministically fails with `staleStart≈55`/`staleDone≈16` against a
   `stale == 0` expectation. Evidence indicates it is **pre-existing**
   (B3s harness untouched by `3a2f442`/`b0418cc`; introduced by `b689cfe`) and
   **non-crashing** (`crash=0`, `hang=0`). It needs triage: either the C3
   expectation is too strict (the DHCP client legitimately may report success
   in the REQUEST/ACK race window) or there is a genuine stale-result ordering
   defect in `dhcp_client.c` teardown. **This is the item requiring a fix
   decision before Stage B can run.**

**Suggested next phase:** `TRIAGE-B3S-C3-STALE-RESULT` — decide whether
`tot.stale_started`/`tot.stale_done` should be admissible for C3 (as they
already are for C4, which has a dedicated window-provenance clause), or whether
`dhcp_client.c` must be hardened. Then re-run Stage A from A9 onward, and
finally enter Stage B.

**Alternatives (user decision):**
* `PROCEED TO COMMIT-TEST40-FIX` — commit the `native_test.c` budget fix now
  (it is verified and independent of the C3 question), then triage C3
  separately.
* `WAIT FOR EXPLICIT USER DECISION` — if the C3 semantics are considered
  already known-good and the failure is accepted as a harness-expectation
  artifact.

---

## Appendix — Session Runtimes

| Phase | Duration |
|---|---|
| Test APK forced build | 3 m 31 s |
| test40 alone | 68.5 s |
| Tests 31–36 (sequential) | 4 m 13 s |
| Tests 37–41 (sequential) | 2 m 32 s |
| Tests 12–13 | 53 s |
| Tests 14–30 (sequential, 17 tests) | 13 m 51 s |

Slowest individual tests: `test20A3Stage100000` 152.1 s,
`test21A6Stage100000` 135.0 s.
