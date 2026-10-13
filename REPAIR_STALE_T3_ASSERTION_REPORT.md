# REPAIR_STALE_T3_ASSERTION_REPORT

**Phase:** REPAIR-STALE-T3-ASSERTION
**Repository:** `D:\PMC\Documents\Android\SoftEthergai`
**Branch:** `main`
**HEAD:** `fa8a53d20d4bc8ba1e233d473c1c7723cbadb71f`
**origin/main:** `fa8a53d20d4bc8ba1e233d473c1c7723cbadb71f` (in sync, ahead 0)
**Date:** 2026-10-13
**Nature:** Test-only repair + one report addendum. No production code change, no build of the APK, no device command, no commit.

---

## §1 Executive verdict

The single JVM failure that stopped `DEVICE-VERIFY-COMBINED-FIX` at STEP 2 was a **stale test assertion**, not a defect in the pending bundle. The test `AutoModeControllerTest.nativeBusyAllRetriesFailTerminal` asserted a **strict string equality** against a production log line that commit `5e51e37` had **intentionally** enriched with a dynamic `(<NativeLifetimeRegistry.process.describe()>)` segment. The test was written earlier (commit `c572050`) and was never updated.

This phase **relaxed only that one assertion** to a two-part `contains` check that mirrors the pattern the frozen bundle's own `AutoModeNativeBusyTest` already uses. **No production code was touched** — in particular, `5e51e37`'s instrumentation is preserved exactly as authored; the test was the stale artifact.

**Result: the JVM suite is green again.**
- `:SoftEtherClient:test` → **154 tests, 0 failures, 0 errors, 0 skipped** *(TEST)*
- `:app:testDebugUnitTest` → **607 tests, 0 failures, 0 errors, 1 skipped** *(TEST)*
- `AutoModeControllerTest` → **75 tests, 0 failures** *(TEST)*

The corrective note required by the phase was appended to `FIX_CONNECTING_THRESHOLD_AND_AUTOMODE_RETRY_REPORT.md` as a clearly-marked end-of-file addendum; §5 itself was left untouched as a historical artifact.

**Evidence basis:** the assertion change and the addendum are `SOURCE CODE`; the git-archaeology trail is `SOURCE CODE` (git history); the test results are `TEST`. The claim that the failure was pre-existing (not bundle-caused) is `INFERENCE` drawn from the git history plus the fact that the affected log line is a **context line** in `git diff`.

---

## §2 The pre-existing defect and its git-archaeology trail

### 2.1 The two log lines that `5e51e37` instrumented

Commit `5e51e37` ("test(androidTest): instrument T5-SLOT holder identity in AutoModeController") changed exactly **5 insertions / 2 deletions** in `AutoModeController.kt`, adding the dynamic `describe()` output to **two** production log lines: *(SOURCE CODE — `git show 5e51e37`)*

| # | Log line (stable substrings) | What `5e51e37` added |
|---|---|---|
| A | `[AUTO] Native slot busy; retrying same server in Xs (attempt N/M) …` | ` (holder=${NativeLifetimeRegistry.process.describe()})` |
| B | `[AUTO] Native slot still owned by an unresolved operation …; stopping without blaming this server` | `(${NativeLifetimeRegistry.process.describe()}); ` (before `stopping`) |

### 2.2 Why line B broke exactly one test

- Commit **`c572050`** ("feat: land the F.28AL native lifetime stack") set the T3 assertion in `AutoModeControllerTest.kt` to the message **without** the interpolation:
  `"[AUTO] Native slot still owned by an unresolved operation; stopping without blaming this server"`.
- Commit **`5e51e37`** then inserted `(${describe()})` into that production line.
- `5e51e37` is a **descendant** of `c572050`, and both are ancestors of HEAD `fa8a53d`. → the assertion has been stale **in the committed baseline since `5e51e37` landed**. *(SOURCE CODE — `git log -S`, `git show HEAD:…`)*
- When the registry is idle, `describe()` returns `"idle"` (`NativeLifetimeRegistry.kt:376`), so the emitted line is
  `…unresolved operation (idle); stopping without blaming this server`
  while the test expects `…unresolved operation; stopping without blaming this server`. A strict equality can **never** match — the failure is deterministic, not flaky.

### 2.3 Why the failure was never observed before

No phase after `5e51e37` ran the **app** unit-test suite:
- `_fix2_tests.log` (2026-10-13 15:16) and `_t5p2_tests.log` (2026-10-13 14:31) ran **only** `:SoftEtherClient:test`.
- `f28alu_app_tests.log` (2026-10-10) predates both commits entirely.

The first full `:app:testDebugUnitTest` run after `5e51e37` was the one in `DEVICE-VERIFY-COMBINED-FIX`, which is where the failure surfaced (1 of 607). *(SOURCE CODE — log task inventories)*

### 2.4 Confirmation the bundle did not cause it

In `git diff`, the line-B production log statement appears as **context** (unchanged), i.e. the pending bundle did not touch it. The bundle's own new test `AutoModeNativeBusyTest` uses a **loose** `contains` assertion, which is why it passed while the strict T3 failed. *(SOURCE CODE)*

---

## §3 The fix — diff of the one assertion

File: `app/src/test/java/vn/unlimit/vpngate/automode/AutoModeControllerTest.kt` (`+14 / −5`)

```diff
@@ -1940,11 +1940,20 @@ val sstp = AutoModeCandidate(
             AutoModeController.ERROR_NATIVE_UNRESOLVED,
             (s as AutoModeState.Error).message,
         )
-        assertEquals(
-            "terminal log line must be unchanged",
-            "[AUTO] Native slot still owned by an unresolved operation; " +
-                    "stopping without blaming this server",
-            adapter.logs.lastOrNull { it.contains("Native slot still owned") },
+        // T5-SLOT (commit 5e51e37) appended the dynamic
+        // "(<NativeLifetimeRegistry.process.describe()>)" segment to this
+        // terminal line. describe() is state-dependent ("idle", or
+        // "attempt=N phase=TEARDOWN, ..."), so a strict equality on the full
+        // line is brittle: it breaks whenever the registry reports a different
+        // holder. Assert the two stable substrings that are the real invariant
+        // — the same pattern AutoModeNativeBusyTest already uses. The dynamic
+        // describe() portion is deliberately NOT part of the invariant.
+        assertTrue(
+            "terminal log line must contain the unresolved-operation marker",
+            adapter.logs.any {
+                it.contains("Native slot still owned by an unresolved operation") &&
+                        it.contains("stopping without blaming this server")
+            },
         )
         assertEquals(
             "expected exactly 3 dial attempts (1 initial + 2 retries)",
```

**Approach chosen:** the phase's recommended looser two-part `contains` form. **No deviation.**

**Rationale (recorded in the code comment and here):**
- `describe()` is **state-dependent** — it returns `"idle"` or `"attempt=N phase=TEARDOWN, …"` depending on the registry. A strict equality on `(idle)` would be brittle and would break again the next time the test ran with a different holder.
- The looser form captures the test's **actual intent**: the terminal message the user sees in logs, with its two stable substrings unchanged. The dynamic `describe()` portion is deliberately **not** part of the invariant.
- It **mirrors the pattern already adopted** by the frozen bundle's `AutoModeNativeBusyTest` (`it.contains("Native slot still owned") && it.contains("without blaming")`), so the two tests stay consistent.
- The assertion message was updated, and a comment citing **T5-SLOT / commit `5e51e37`** was placed immediately above it so a future reader understands *why* the equality was relaxed.

**Scope discipline:** only this one assertion and its immediately-preceding comment changed. No other test method, and no production code, was touched.

---

## §4 The corrected record — contents of the addendum

Appended verbatim to the **end** of `FIX_CONNECTING_THRESHOLD_AND_AUTOMODE_RETRY_REPORT.md` (file grew from 25,582 bytes; §5 and every other section left untouched):

```markdown
---

## Addendum — Correction to §5 (added 2026-10-13 by REPAIR-STALE-T3-ASSERTION)

§5 of this report stated that the T3 assertion in `AutoModeControllerTest.kt` "still matches exactly." That claim is incorrect. The production log line at `AutoModeController.kt:1477` was modified by commit `5e51e37` (the T5-SLOT instrumentation) to include `"(${NativeLifetimeRegistry.process.describe()})"`; the T3 assertion, set earlier by commit `c572050`, expects the message WITHOUT that interpolation. The assertion has been stale since `5e51e37` landed and was not exercised because no intervening phase ran the `:app:testDebugUnitTest` suite. The assertion is corrected in the REPAIR-STALE-T3-ASSERTION phase. No production code is changed.
```

*(The original §5 claim — lines 259-263 — elided the middle of the log string with `…` and therefore missed the `(describe())` interpolation. It remains in place as a historical artifact; this addendum supersedes it.)*

---

## §5 JVM test run results

Both suites run with `--rerun-tasks --no-build-cache` (methodology lessons L1/L2). *(TEST)*

### `:SoftEtherClient:test` → **BUILD SUCCESSFUL in 1m 43s**

| Metric | Value |
|---|---|
| Tests | **154** |
| Failures | **0** |
| Errors | **0** |
| Skipped | **0** |

### `:app:testDebugUnitTest` → **BUILD SUCCESSFUL in 8m 11s**

| Metric | Value |
|---|---|
| Tests | **607** |
| Failures | **0** |
| Errors | **0** |
| Skipped | **1** |

- `AutoModeControllerTest` → **75 tests, 0 failures** (the previously-failing suite) ✅
- `AutoModeNativeBusyTest` → **23 tests, 0 failures** ✅
- The 1 skip is the pre-existing `HtmlParserOracleTest` skip (present in the pre-fix run too).

**Transient infrastructure note.** The first chained app run aborted with Gradle's `BindException: Address already in use: Cannot bind` — the same daemon-port contention seen in the prior phase, **not** a test failure. Per STEP 5b, daemons were stopped (`./gradlew --stop`) and the app suite re-run cleanly (8m 11s). The client suite was unaffected.

**Comparison to the pre-fix baseline:** `:SoftEtherClient:test` 154/0 → 154/0 (unchanged); `:app:testDebugUnitTest` **607 / 1 failed** → **607 / 0 failed** (the single failure is now green).

---

## §6 Confirmation that no other test was similarly broken by `5e51e37`

STEP 2d required listing every test site that could be affected by the `describe()` / `holder=` instrumentation. Every log assertion in `AutoModeControllerTest.kt` was inspected: *(SOURCE CODE)*

| Line | Assertion form | Touches line A or B? | Affected? |
|---|---|---|---|
| 1894 | `any { it.contains("Native slot busy; retrying") }` | A (loose) | No |
| **1945-1947** | **strict `assertEquals` on the full line B** | **B** | **Yes — fixed** |
| 1953 | `filter { it.contains("Native slot busy; retrying") }` | A (loose) | No |
| 2012 | `any { it.contains("Native slot busy; retrying") }` | A (loose) | No |
| 2046 | `any { it.contains("Per-server retry budget exceeded") }` | neither | No |
| 2050 | `any { it.contains("Native slot still owned") }` | B (loose) | No |
| 2102 | `any { contains("TIMED_OUT after") && … }` | neither | No |
| 2106 | `any { it.contains("Native slot busy; retrying") }` | A (loose) | No |

Only the T3 strict equality was affected; **every other site already used loose substring matching** on stable text and was therefore immune to the appended dynamic segment. This is independently corroborated by the pre-fix run itself: **1 failure out of 607** — the only red test in the entire suite was `nativeBusyAllRetriesFailTerminal`.

The one other `assertEquals` on logs-adjacent state in the file (line 1633) asserts **reachability targets**, not a log string, and is unrelated.

**Conclusion:** `5e51e37` broke exactly one test, and this phase repaired exactly that one. *(INFERENCE, corroborated by TEST)*

---

## §7 Change inventory

| File | Change | Δ |
|---|---|---|
| `app/src/test/java/vn/unlimit/vpngate/automode/AutoModeControllerTest.kt` | T3 assertion: strict equality → two-part `contains`; comment citing `5e51e37` | **+14 / −5** |
| `FIX_CONNECTING_THRESHOLD_AND_AUTOMODE_RETRY_REPORT.md` | One appended "Addendum — Correction to §5" block at EOF | +5 lines |
| `REPAIR_STALE_T3_ASSERTION_REPORT.md` | New phase report (this file) | new |

`git diff --name-only` after the phase lists exactly **10** tracked files: the **9** pending-bundle files (unchanged) **plus** `AutoModeControllerTest.kt`. *(SOURCE CODE)*

---

## §8 Explicit no-change confirmation for the pending bundle

All 9 pending-bundle files are **byte-identical** to their pre-phase state — `git diff --numstat` rows match the pre-phase baseline exactly: *(SOURCE CODE)*

| File | Pre-phase | Post-phase | Same? |
|---|---|---|---|
| `softether_protocol.c` | 178 / 13 | 178 / 13 | ✅ |
| `tcp_socket.c` | 54 / 8 | 54 / 8 | ✅ |
| `NativeLifetimeRegistry.kt` | 267 / 13 | 267 / 13 | ✅ |
| `SoftEtherVpnService.kt` | 106 / 0 | 106 / 0 | ✅ |
| `ConnectionController.kt` | 39 / 2 | 39 / 2 | ✅ |
| `NativeLifetimeRegistryTest.kt` | 285 / 0 | 285 / 0 | ✅ |
| `AndroidConnectionAdapter.kt` | 32 / 0 | 32 / 0 | ✅ |
| `AutoModeController.kt` | 215 / 1 | 215 / 1 | ✅ |
| `AutoModeNativeBusyTest.kt` | 244 / 5 | 244 / 5 | ✅ |

Also unchanged: `PROJECT_SPECIFICATION.md`, all other test files, all other existing reports (§5 of `FIX_CONNECTING…` is untouched — only an EOF addendum was appended), and all build files (Gradle, CMake).

**`5e51e37`'s production change was NOT reverted** — the production log line at `AutoModeController.kt:1521-1525` is intact, including the `(${NativeLifetimeRegistry.process.describe()})` interpolation. The T5-SLOT instrumentation is preserved exactly as authored.

---

## §9 Recommendation

**Recommendation: re-authorize `DEVICE-VERIFY-COMBINED-FIX`.** *(INFERENCE)*

That phase stopped solely on this now-repaired assertion. With `:app:testDebugUnitTest` green at **607 / 0**, the bundle is once again eligible for the combined device-verification run (native P1/P5a/P5b + Kotlin FIX-1/2/3/C). Its STEP 2 gate should now pass and the run can proceed to build → install → DEX scan → reproduction.

Two small carry-forward notes for that re-run:
1. The app suite takes ~8–10 minutes; expect the daemon `BindException` if suites are chained back-to-back — run `./gradlew --stop` between them.
2. The relaxed T3 assertion no longer pins the exact holder text; if the operator ever wants the holder string verified, that belongs in a dedicated assertion, not in the T3 terminal-state test.

**This recommendation is advisory. No next phase is proposed autonomously; the operator decides whether and when to authorize the re-run.**

---

*End of report.*
