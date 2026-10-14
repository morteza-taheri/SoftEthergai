# FIX-TEST-FLAKINESS-AND-DOCS-v1.0.12 — Report

**Phase:** FIX-TEST-FLAKINESS-AND-DOCS-v1.0.12
**Repository:** `C:\Dev\SoftEthergai`
**Branch:** `main`
**Base HEAD at phase start:** `3dfb0f3126e2f2af267784180bdf2a0fc3b66b24` (= `origin/main`)
**Date:** 2026-10-14
**Scope:** device-free — JVM tests only. **No APK was built. No device was touched.**

Evidence labels used below: **SOURCE CODE** / **TEST** / **PHYSICAL DEVICE** / **INFERENCE** /
**AUTOMATED-UI** / **PRIOR REPORT**.

---

## 1. Executive verdict

**PASS.** Both open test-suite defects carried over from the FIX-HANGING-SKIP-TEST phase were
root-caused, fixed, and verified, and the specification was updated to v1.0.12.

| Part | Item | Outcome |
|------|------|---------|
| A | **OPEN-1** — unsynchronized `ArrayList` collector in `VpnServerRepositoryR4bTest` | **FIXED** (`CopyOnWriteArrayList`), deterministic regression test added and green |
| B | **OPEN-2** — unbounded `while (true)` in `AutoModeControllerTest.awaitTerminal()` | **FIXED** (bounded `withTimeoutOrNull(10_000)`), predicate and call sites unchanged |
| C | `PROJECT_SPECIFICATION.md` 1.0.11 → 1.0.12 | **DONE** — surgical edits in header / §1.6.1 / new §1.6.6 / §12.5.1 / §20 only |
| D | JVM verification | **GREEN** — library `160/0/0/0`; app suite run twice, `647/0/0/1` both times |
| E | Report + 3 commits + push | this document |

Both defects were **test-only**. **No production code was changed** (§6).

### 1.1 One deviation from the phase brief — flagged

The brief asked to update the §1.6.1 commit-chain count **"from 28 to 32 (or 34), verified via
`git log --oneline 0714628..HEAD | wc -l`."** That estimate does not match the repository. The
brief enumerated only four commits to append (`7b4adba`, `a9e16fd`, `3c656a4`, `3dfb0f3`), but
**nine** commits sit between the table's last row (`6119a2a`) and `3dfb0f3`:

```
68758ae  docs: update PROJECT_SPECIFICATION to v1.0.11 (native hardening bundle + axes audit)
0e011e3  docs: add Axes A/B/C audit and v1.0.11 docs-update reports
0e3f9ca  fix(android): capture re-entrant teardown caller + split terminal message by outcome (R1, R2)
607c651  feat(android): add RX/EIO counters + classify TUN EIO + drop dead MAX_PACKET_SIZE (R4a, R5, R6)
2841dc5  docs: report for FIX-AND-VERIFY-AUDIT-TOP-PRIORITIES
7b4adba  docs(audit): append §10 addendum correcting §2.3 mechanism (R1 evidence)
a9e16fd  feat(collector): per-request Network.getSocketFactory binding + RX wiring (R4b)
3c656a4  test(automode): bound awaitNextTerminalAfterChange to prevent hang
3dfb0f3  docs: report for FIX-AND-VERIFY-R4B (R4b + skip-test fix)
```

The document's own convention counts the **inclusive** range (`0714628` → last row), which is why
"28" matches `0714628^..6119a2a` = 28 rows. Following that convention, and appending **all** missing
commits so the documented "contiguous range" invariant holds, the new count is **37** (through
`3dfb0f3`), rising to **40** once this revision's three commits land. The range-**exclusive** form the
brief named, `git log --oneline 0714628..HEAD | wc -l`, reports **36** today (and 39 after this
phase) — it omits the range's own first commit, `0714628`.

**I wrote the accurate values and stated both forms explicitly in §1.6.1 rather than the brief's
32/34.** The four commits the brief named were appended, plus the five it omitted. See §4.

### 1.2 One pre-existing stale field — flagged, not changed

The header's `**Repository:**` line still reads `D:\PMC\Documents\Android\SoftEthergai`. The project
was relocated to `C:\Dev\SoftEthergai` in the previous phase, so this field is stale. The brief's
PART C enumerated only the **version** and **HEAD** fields ("surgical edits only"), so the path was
**left unchanged** to respect the scope gate. Recommend correcting it in a future revision.

---

## 2. PART A — OPEN-1: synchronize the collector log (test-only)

### 2.1 Root cause — SOURCE CODE

`VpnServerRepository.refresh()` (`app/src/main/java/vn/unlimit/vpngate/repository/VpnServerRepository.kt:156`)
runs inside `withContext(Dispatchers.IO)` and uses a `coroutineScope { … }`:

- the **mirror** rung is `async`-launched and then `await`ed **first** (`:172`–`:177`);
- the **api** fetch (`:186`) and the **html** fetch (`:194`) are then launched as **two concurrent
  `async` coroutines**.

Both the api and html coroutines call `fetchOnChosenPath`, which writes its `[collector] path=…`
line through the injected `pathLog` lambda (`:328`, `:332`, `:340`). In the test that lambda is
`{ logs.add(it) }`, so **two threads call `add` on the same list with no synchronisation**. A plain
`ArrayList` is not thread-safe; a lost `add` shortens the list, which is exactly the
intermittently-missing `[collector] path=vpn source=html` line that
`vpn path is chosen when every input holds` asserts. Class passed on immediate re-run → intermittent,
matching the reported symptom.

### 2.2 Fix — SOURCE CODE

`app/src/test/java/vn/unlimit/vpngate/repository/VpnServerRepositoryR4bTest.kt`

```diff
+import java.util.concurrent.CopyOnWriteArrayList
 import kotlinx.coroutines.runBlocking
 import org.junit.Assert.assertEquals
@@
-    private val logs = mutableListOf<String>()
+    /**
+     * OPEN-1: [VpnServerRepository.refresh] writes this from two concurrent
+     * `async` coroutines (the api and html fetches), so a plain `ArrayList`
+     * could silently drop a line under a concurrent `add`. A
+     * `CopyOnWriteArrayList` makes every write visible; the assertions below
+     * are unchanged.
+     */
+    private val logs = CopyOnWriteArrayList<String>()
```

No assertion logic changed. The `assertEquals(listOf(...), logs)` comparisons already relied on
element-wise `List.equals`, which `CopyOnWriteArrayList` inherits unchanged.

### 2.3 Regression test — TEST

Added to the same file: **`vpn-path-logs-are-never-lost-under-concurrent-fetch`**.

```kotlin
@Test
fun `vpn-path-logs-are-never-lost-under-concurrent-fetch`() = runBlocking {
    val iterations = 50
    val repo = repository(vpnInput, EmptyFetcher())

    logs.clear()
    repeat(iterations) { repo.refresh() }

    val perRun = listOf(
        "[collector] path=vpn source=mirror",
        "[collector] path=vpn source=api",
        "[collector] path=vpn source=html",
        "[collector] path=fallback-to-open source=api",
        "[collector] path=fallback-to-open source=html",
    )

    assertEquals(
        "expected ${perRun.size * iterations} lines from $iterations runs, got ${logs.size}: $logs",
        perRun.size * iterations,
        logs.size,
    )
    for (line in perRun) {
        assertEquals(
            "lost writes for '$line' across $iterations concurrent runs: $logs",
            iterations,
            logs.count { it == line },
        )
    }
}
```

- **N = 50** concurrent runs (≥50 required), each exercising the api+html concurrency.
- Each run emits exactly **5** lines on the VPN rung with a failing bound fetcher (mirror: 1;
  api: 2; html: 2) → **250** total, and **50** of each line. A dropped write shows up as a short
  count instead of a rare suite failure.
- **Deterministic under the fix.** Observed runtime **0.783 s** (run 1) / **0.755 s** (run 2) — well
  inside the <2 s budget.
- The `VpnServerRepositoryR4bTest` class now has **10** tests (was 9), all green.

---

## 3. PART B — OPEN-2: bound `awaitTerminal()` (test-only)

`app/src/test/java/vn/unlimit/vpngate/automode/AutoModeControllerTest.kt` — the helper is used by
**40 call sites** (all of which remain valid, since the new parameter is defaulted).

```diff
-    private suspend fun AutoModeController.awaitTerminal(): AutoModeState {
-        while (true) {
-            val s = state.value
-            // Wait until we hit a terminal state AND the run has fully completed
-            // (job == null or job not active), so a subsequent start() launches a fresh run.
-            if ((s is AutoModeState.Connected || s is AutoModeState.Error) && !isRunning) {
-                return s
+    /**
+     * Waits (bounded) for the run to settle into a terminal state.
+     *
+     * OPEN-2: this used to be an unbounded `while (true)`. A controller that
+     * never settled would stall the entire suite instead of failing one test —
+     * the same failure mode already fixed in [awaitNextTerminalAfterChange]. The
+     * success predicate is unchanged (terminal state AND `!isRunning`, so a
+     * later `start()` launches a fresh run); only the wait is bounded, and a
+     * timeout now names the state it was stuck in and what the controller logged
+     * while it waited.
+     */
+    private suspend fun AutoModeController.awaitTerminal(timeoutMs: Long = 10_000): AutoModeState {
+        val reached = withTimeoutOrNull(timeoutMs) {
+            var result: AutoModeState? = null
+            while (result == null) {
+                val s = state.value
+                // Wait until we hit a terminal state AND the run has fully completed
+                // (job == null or job not active), so a subsequent start() launches a fresh run.
+                if ((s is AutoModeState.Connected || s is AutoModeState.Error) && !isRunning) {
+                    result = s
+                } else {
+                    kotlinx.coroutines.delay(25)
+                }
             }
-            kotlinx.coroutines.delay(25)
+            result
         }
+        return reached ?: error(
+            "AutoModeController did not reach a terminal state within ${timeoutMs}ms; " +
+                "last=${state.value}, isRunning=$isRunning, recentLogs=${adapter.logs.takeLast(12)}",
+        )
     }
```

Compliance with the brief:

- Bound = `withTimeoutOrNull(10_000)`, poll = 25 ms — same pattern as the already-fixed
  `awaitNextTerminalAfterChange`.
- On timeout it `error(...)`s, naming **last state**, **`isRunning`**, and **`adapter.logs.takeLast(12)`**.
- **Success predicate unchanged.**
- **No `before` parameter added.** All call sites untouched (timeout defaulted).

---

## 4. PART C — specification diff summary (`PROJECT_SPECIFICATION.md`, 1.0.11 → 1.0.12)

### 4.1 Preconditions (C1) — PASS

| Check | Required | Observed |
|-------|----------|----------|
| Document version | `1.0.11` | `1.0.11` ✓ |
| Git HEAD | `3dfb0f3` | `3dfb0f3126e2f2af267784180bdf2a0fc3b66b24` ✓ |

(The spec's own `**HEAD:**` field was stale at `6119a2a…`, which is exactly what C2.2 corrects.)

### 4.2 Edits made

| # | Region | Change |
|---|--------|--------|
| C2.1 | Header | `Document version` `1.0.11` → `1.0.12`; `HEAD` `6119a2a8…` → `3dfb0f3126e2f2af267784180bdf2a0fc3b66b24` |
| C2.2 | §1.6.1 count sentence | "Twenty-eight commits … `0714628` → `6119a2a`" → **"Thirty-seven commits … `0714628` → `3dfb0f3`"**, with the range-exclusive `git log` figure (**36**) stated explicitly, and the note that this revision adds three commits → **40** |
| C2.3 | §1.6.1 table | Appended **9** rows: `68758ae`, `0e011e3`, `0e3f9ca`, `607c651`, `2841dc5`, `7b4adba`, `a9e16fd`, `3c656a4`, `3dfb0f3` |
| C2.4 | §1.6.1 note blockquote | New **"Push state (v1.0.12)"** note naming this revision's three commits by subject |
| C2.5 | **New §1.6.6** | "Test Flakiness and Docs Bundle (v1.0.12)" — **10** evidence-labelled bullets (OPEN-1 cause/fix/regression, OPEN-2 cause/fix, library run, app run ×2, no-APK/no-device, §12.5.1 rows, methodology lessons) |
| C2.6 | §12.5.1 | **Row 11** (OPEN-1) and **Row 12** (OPEN-2), both **CLOSED (v1.0.12)**, each cross-referencing §1.6.6 |
| C2.7 | §20 changelog | New **1.0.12** row |
| C2.8 | §20 verification status | New row for **§1.6.6** (`HISTORICAL + TEST`) |

### 4.3 Scope gate (C3) — PASS

`git diff -U0` hunk headers on the spec:

```
@@ -6,2 +6,2 @@        header
@@ -93,2 +93,2 @@       §1.6.1 count
@@ -96,2 +96,6 @@       §1.6.1 count
@@ -128,0 +133,9 @@     §1.6.1 table rows
@@ -140,0 +154,7 @@     §1.6.1 push-state note
@@ -337,0 +358,51 @@    new §1.6.6 (inserted after §1.6.5, before the `---`)
@@ -1638,0 +1710,2 @@   §12.5.1 rows 11/12
@@ -1971,0 +2045 @@     §20 changelog
@@ -2007,0 +2082 @@     §20 verification status
```

**No hunk falls inside §1.6.2, §1.6.3, §1.6.4, §1.6.5, or §22.** The `+154,7` insertion lands
before §1.6.2 (which begins at new line 162), and the `+358,51` insertion begins exactly at the new
§1.6.6 heading, after §1.6.5 ends (new line 357). Total additions: **81** lines, all inside allowed
regions.

### 4.4 Count deviation (restated from §1.1)

Brief expected **32 (or 34)**; the repository supports **37** rows through `3dfb0f3` (**36** by the
brief's range-exclusive command), rising to **40** once this phase's three commits land. The brief's
enumeration omitted five real commits (`68758ae`, `0e011e3`, `0e3f9ca`, `607c651`, `2841dc5`). The
accurate values were written, with both count forms stated in the document. **This is the one place
where the deliverable intentionally differs from the brief's literal numbers.**

---

## 5. PART D — JVM test counts

All three runs used forced, cache-disabled invocations:
`CI=true ./gradlew <task> --rerun-tasks --no-build-cache --no-daemon --console=plain`.

### D1 — library suite

```
CI=true ./gradlew :SoftEtherClient:test --rerun-tasks --no-build-cache --no-daemon
→ BUILD SUCCESSFUL in 1m 11s   (17 actionable tasks: 17 executed)

tests = 160   failures = 0   errors = 0   skipped = 0   (9 result files)
```

**160 / 0 / 0 / 0 — matches the expected baseline.** Evidence: TEST.

### D2 — full app suite, run twice

| Run | Task | Result | tests | failures | errors | skipped | files | Duration |
|-----|------|--------|-------|----------|--------|---------|-------|----------|
| 1 | `:app:testProDebugUnitTest` | BUILD SUCCESSFUL | **647** | 0 | 0 | **1** | 48 | 5m 48s |
| 2 | `:app:testProDebugUnitTest` | BUILD SUCCESSFUL | **647** | 0 | 0 | **1** | 48 | 5m 40s |

- **Both runs green**, identical counts. 647 = the pre-phase 646 **+ 1** (the new OPEN-1 regression
  test).
- The single skip is the **pre-existing, legitimate** `AssumptionViolatedException` in
  `HtmlParserOracleTest.softetherUdpOnlyNeverBorrowsOpenvpnTcp`:
  `"no SE-UDP-only + OVPN-TCP row in current snapshot"` — a data-dependent assumption, not a
  message-less in-flight kill.
- The new regression test appears in **both** runs (`time="0.783"` and `time="0.755"`), so the fix
  and its regression test are both exercised, twice.
- `VpnServerRepositoryR4bTest`: **10 / 0 / 0 / 0** in both runs.

Evidence: TEST.

### 5.1 What was NOT run

- **No APK.** `assembleProDebug` / `assembleFreeDebug` were **not** executed.
- **No device interaction.** No `adb` invocation of any kind.

Evidence: SOURCE CODE (the phase's own command log).

---

## 6. Explicit no-change statements

- **No production code was changed.** The only files edited in this phase are:
  - `app/src/test/java/vn/unlimit/vpngate/repository/VpnServerRepositoryR4bTest.kt` (test)
  - `app/src/test/java/vn/unlimit/vpngate/automode/AutoModeControllerTest.kt` (test)
  - `PROJECT_SPECIFICATION.md` (documentation)
  - `FIX_TEST_FLAKINESS_AND_DOCS_v1.0.12_REPORT.md` (this report)
  In particular `VpnServerRepository.kt` and `AutoModeController.kt` — the production files behind
  both defects — are **untouched**. Both defects were test-only.
- **No assertion logic was changed** in either test file. The only test-file changes are the
  collector's type, the new regression test, and the bounded wait.
- **No `before` parameter** was added to `awaitTerminal()`; **no call sites** were modified.
- **No build artifact, no device, no emulator, no settings** were touched.
- **No `git add -A` / `git add .`** was used; no `--force`, tag, reset, clean, or stash.

---

## 7. Recommendation (NOT executed)

Per the phase brief, the following is a recommendation only and was **not** carried out:

1. **Correct the header `Repository:` field** (`D:\PMC\Documents\Android\SoftEthergai` →
   `C:\Dev\SoftEthergai`). It is stale since the previous phase's relocation; it was out of the
   enumerated PART C scope, so it was left for a future revision to change deliberately.
2. **Next-revision commit listing.** Per the document's own convention, v1.0.13 should list this
   revision's three commits **by hash** in §1.6.1 (they are named by subject only today, since their
   hashes did not exist when the spec was written), which will move the chain count from 37 → 40.
3. **Consider hardening the OPEN-1 regression test** so it also *fails* reliably on the old
   `ArrayList` (e.g. by raising N or adding a barrier). It is deterministic under the fix — which is
   what the brief required — but its power to catch a *regression back* to `ArrayList` depends on
   scheduler timing.
4. **OPEN-2 residual.** With the wait now bounded, a genuine production hang in the Auto Mode
   controller would surface as a single loud test failure naming the stuck state rather than a suite
   stall — worth watching in the next full-suite run for any newly-failing test.
