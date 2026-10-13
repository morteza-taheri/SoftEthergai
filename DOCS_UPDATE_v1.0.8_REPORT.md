# DOCS-UPDATE-v1.0.8 — Report

**Phase:** `DOCS-UPDATE-v1.0.8`
**Date:** 2026-10-13
**HEAD:** `5e51e37f83bba3db8e3f859886bf045bb5ea6255`
**origin/main:** `5e51e37f83bba3db8e3f859886bf045bb5ea6255`
**Branch:** `main`
**Language:** English only.

---

## 1. Executive Verdict

```
DOCS UPDATED — v1.0.8
```

`PROJECT_SPECIFICATION.md` was revised in place from **v1.0.7** to **v1.0.8**, capturing the
post-v1.0.7 state: the C3 test-rule fix, C5 formal gating, the T5-SLOT instrumentation landing, the
`test40` budget widening, and the corrected **17-commit** chain that was pushed to `origin/main`
(including the `0714628` off-by-one correction). Documentation only — no source/test/build/device
change. No commit, no push, no tag, no device command.

Diff size: `PROJECT_SPECIFICATION.md | 78 insertions(+), 17 deletions(-)` (one file).

---

## 2. Pre-Conditions (P1–P6)

| ID | Check | Required | Result |
|----|-------|----------|--------|
| **P1** | `git rev-parse HEAD` | `5e51e37f83bba3db8e3f859886bf045bb5ea6255` | **PASS** — exact match |
| **P2** | `git rev-parse origin/main` | `5e51e37f83bba3db8e3f859886bf045bb5ea6255` | **PASS** — exact match (in sync with HEAD) |
| **P3** | `git branch --show-current` | `main` | **PASS** |
| **P4** | `git status --short` — no tracked-file modification (` M` / `M ` prefix) | none | **PASS** — no tracked modifications; only untracked evidence files present (expected) |
| **P5** | `PROJECT_SPECIFICATION.md` header is v1.0.7 | `1.0.7` | **PASS** — header read at STEP 1 confirmed `**Document version:** 1.0.7` (HEAD field was stale at `c572050…`) |
| **P6** | `git rev-list --count origin/main..HEAD` | `0` | **PASS** — 0 ahead |

No stop condition was triggered. Evidence: SOURCE CODE (`git`).

---

## 3. Summary of Changes to `PROJECT_SPECIFICATION.md`

Surgical edits only; the document was **not** rewritten.

| # | Location | Change |
|---|----------|--------|
| E1 | Header (lines 6–7) | `**HEAD:**` refreshed `c5720508…` → `5e51e37f…`; `**Document version:** 1.0.7` → `1.0.8` (date already `2026-10-13`). |
| E2 | §1.6 intro bullets | Commit count `9` → `17`; range `b689cfe`→`c572050` → `0714628`→`5e51e37`; push status changed from "committed locally, NOT pushed, 9 ahead" to "pushed, in sync, 0 ahead / 0 behind". |
| E3 | §1.6.1 Commit chain | Rewritten to the **17-commit** table (oldest first), now headed by `0714628`; added the **`0714628` off-by-one correction note** and a **push-state note** (`5c13603..5e51e37`, fast-forward, no force/tag). |
| E4 | §1.6.3 (new) | New subsection **"Post-v1.0.7 Additions"** — 8 bullets, each with an evidence label (see §4 below). |
| E5 | §20 Changelog | Added the `1.0.8` row. |
| E6 | §20 Verification Status | Added a `Post-v1.0.7 Additions (§1.6.3)` row (HISTORICAL). |

**Not touched:** §22 L1–L10 wording (verified — the diff contains **no** `L1 —`…`L10 —` or `Incident:`
line changes); §12.4 FINDING T5-SLOT; §12.5 roadmap; all other sections; all non-authorized files.
Evidence: SOURCE CODE (`git diff`).

### E4 — the eight bullets added in §1.6.3

1. C3 test-rule fix landed (`4c013e8`) — device-verified via `test27`. *(SOURCE CODE + PHYSICAL DEVICE)*
2. C5 formally GATED (skipped, not failed); deferred to `C5-TRIAGE-DEVICE`. *(SOURCE CODE + PRIOR REPORT)*
3. T5-SLOT instrumentation landed (`5e51e37`) — logging-only change to `AutoModeController.kt`. *(SOURCE CODE)*
4. T5-SLOT **NOT reproduced**; holder identity **UNKNOWN**; instrumentation installed and armed. *(PHYSICAL DEVICE + PRIOR REPORT; holder = UNKNOWN)*
5. `test40` budget widened to 90 s (`38d9d33`); re-run **PASS** (68.5 s standalone / 51.8 s in suite). *(SOURCE CODE + PHYSICAL DEVICE)*
6. All 17 commits pushed to `origin/main` on 2026-10-13 (`5c13603..5e51e37`, fast-forward; no force, no tag). *(SOURCE CODE)*
7. Pre-existing local tag `p2.4.10` points at `0714628`; **not** pushed; open decision. *(SOURCE CODE)*
8. **No new methodology lessons in v1.0.8.** *(—)*

### Correction carried into the spec (not in the brief, flagged here)

The brief's bullet for `test40` quoted a "PASS at 44.2 s". Reading the source report
(`FIX_TEST40_BUDGET_AND_RE_RUN_REPORT.md`) shows **44.2 s is the runtime of `test30B3sCaseC5`**, not
`test40`. `test40`'s PASS times are **68.5 s standalone** (§4 of that report, `Time: 68.504`) and
**51.8 s in suite** (§5 table row 40). The spec records the accurate figures and notes the
mis-attribution inline. The same mis-attribution appears in `SKIP_C5_AND_EXECUTE_STAGE_B_REPORT.md`
§2 (line 38), which cites "`FIX_TEST40_BUDGET_AND_RE_RUN_REPORT.md:152` — test40 PASS (44.2 s)";
line 152 of that report is in fact `test30B3sCaseC5`. Evidence: SOURCE CODE + PRIOR REPORT.

---

## 4. Findings-Status Snapshot After v1.0.8

| Finding | Status | Notes | Evidence |
|---------|--------|-------|----------|
| **T5-SLOT** | **OPEN** — instrumentation landed, holder **UNKNOWN** | Logging-only instrumentation (`5e51e37`) is installed on device and will capture `attempt=<id> phase=<PHASE>` whenever contention recurs. Not reproduced in the Stage B run; sole known occurrence remains the 2026-10-13 RESUME T5 window (§12.4). | PHYSICAL DEVICE + PRIOR REPORT; holder = UNKNOWN |
| **C5-TEST-DEFECT** | **OPEN** — formally **GATED** | Triaged to a pre-existing **test-harness** defect (silent-responder frame classification too strict); production retry DISCOVER is legitimate. Not executed; deferred to `C5-TRIAGE-DEVICE`. | SOURCE CODE + PRIOR REPORT |
| **RISK-A** | **OPEN** — observed | Conditional release signing falls back to debug signing; `CONFIRMED BY SOURCE AND OBSERVED IN PRACTICE` (installed package debug-signed). Unchanged by this phase. | SOURCE CODE + PHYSICAL DEVICE (prior) |
| **RISK-B** | **OPEN** — partial | Legacy `SoftEtherClient.disconnect()` may bypass `NativeHandleSlot`; `PARTIALLY CONFIRMED — REQUIRES DEEPER ANALYSIS`. Unchanged by this phase. | SOURCE CODE (prior) |

**Observation (flagged, not edited):** §12.5.1 row 1 still reads
**FIX-NATIVE-LIFETIME-SYNC (N-15 + N-16) = OPEN**, although its two sub-fixes landed as commits
`3a2f442` (N-15) and `b0418cc` (N-16) and were device-verified (tests 37–41 PASS). That row was
**outside the enumerated edit set** of this phase, so it was left unchanged; it is flagged here for
the operator to resolve in a future docs revision (the row conflates the now-fixed N-15/N-16 code
work with the still-open T5-SLOT driver). Evidence: SOURCE CODE + PRIOR REPORT.

**Observation (flagged, not edited):** the §1.6.3 bullet list was specified by the phase and does
**not** enumerate the N-15/N-16 fixes or the `test35` repair (`dc8e113`) or the v1.0.7 docs commit
(`6375818`), even though all three are post-v1.0.7 commits and appear in the 17-commit table
(§1.6.1). Recorded as specified; noted here for completeness.

---

## 5. Commit Chain (17 commits)

### Oldest-first (as recorded in §1.6.1)

```
0714628  feat(ui): add DNS presets, app exception icons and Auto Mode cleanup
b689cfe  fix(native): validate JNI array lengths and release RUDP lock (P0 bounds)
49f579b  fix(android): route reconnect failure through service onError; name teardown identity
e1132ad  build(native): exclude test library from release build
ac2764a  build(ksp): align KSP version with Kotlin
0688b51  test(androidTest): expose native bounds tests to the Kotlin bridge
8424afa  docs(reports): session audit and verification trail
ab82ce1  fix(native): commit the missing F28AL-U classes (repairs 49f579b)
98a6462  feat(softether): add session policy and TUN ownership coordinators
c572050  feat: land the F.28AL native lifetime stack
6375818  docs: update PROJECT_SPECIFICATION to v1.0.7
dc8e113  test(native): repair test35 dummy-handle contradiction
3a2f442  fix(native): refcount rudp_context_t to close destroy-vs-poll use-after-free (N-15)
b0418cc  fix(native): hold connect_mutex in softether_connect_with_hub itself (N-16)
38d9d33  test(native): widen N-16-A post-release budget to 90s
4c013e8  test(native): align C3 verdict with C4 "observe the race" semantics
5e51e37  test(androidTest): instrument T5-SLOT holder identity in AutoModeController
```

### Newest-first (`git log --oneline -17`)

```
5e51e37  test(androidTest): instrument T5-SLOT holder identity in AutoModeController
4c013e8  test(native): align C3 verdict with C4 "observe the race" semantics
38d9d33  test(native): widen N-16-A post-release budget to 90s
b0418cc  fix(native): hold connect_mutex in softether_connect_with_hub itself (N-16)
3a2f442  fix(native): refcount rudp_context_t to close destroy-vs-poll use-after-free (N-15)
dc8e113  test(native): repair test35 dummy-handle contradiction
6375818  docs: update PROJECT_SPECIFICATION to v1.0.7
c572050  feat: land the F.28AL native lifetime stack
98a6462  feat(softether): add session policy and TUN ownership coordinators
ab82ce1  fix(native): commit the missing F28AL-U classes (repairs 49f579b)
8424afa  docs(reports): session audit and verification trail
0688b51  test(androidTest): expose native bounds tests to the Kotlin bridge
ac2764a  build(ksp): align KSP version with Kotlin
e1132ad  build(native): exclude test library from release build
49f579b  fix(android): route reconnect failure through service onError; name teardown identity
b689cfe  fix(native): validate JNI array lengths and release RUDP lock (P0 bounds)
0714628  feat(ui): add DNS presets, app exception icons and Auto Mode cleanup
```

State: `origin/main` == `HEAD` == `5e51e37`; local ahead = **0**; behind = **0**.
Evidence: SOURCE CODE (`git log`, `git rev-parse`).

---

## 6. The Corrected Base: `0714628` Is the First Unpushed Commit, Not "Base"

- The v1.0.7 text and the push handoff described `0714628` as the **base of the chain**. Ground truth:
  - `0714628^` = `5c13603` = the **old `origin/main`** (the true base).
  - `0714628` was **not** an ancestor of the pre-push `origin/main` → it is the **first unpushed
    commit**.
- Therefore the pushed range is **17 commits** (`0714628` → `5e51e37`), not 16, and not "9 + base".
- Corrected in §1.6.1 with an explicit note. Evidence: SOURCE CODE (`git merge-base --is-ancestor`,
  `git rev-parse`).

---

## 7. Open Decision: the `p2.4.10` Tag

- `git tag` lists **`p2.4.10`** in addition to `p2.4.5`, `p2.4.7`, `p2.4.8`, `p2.4.9`.
- `p2.4.10` is a **pre-existing local annotated tag** (`git cat-file -t` → `tag`; tagger date
  **2026-09-28 17:16:07 +0330**) whose dereferenced commit is **`0714628`** — the first of the 17
  pushed commits.
- It was **not created** and **not pushed** by any recent phase (no `git tag` command was run; the
  push used neither `--tags` nor `--follow-tags`). The commit it points at is now reachable from
  `origin/main`, but the **tag ref itself was never published**.
- **Decision required in a future phase** (options, not yet chosen):
  1. **PUSH-TAG** — publish `p2.4.10` (and possibly the other local tags) to the remote.
  2. **DELETE-TAG** — remove the local tag if it is stale/superseded.
  3. **LEAVE LOCAL** — take no action; keep it as a local-only marker.
- Recorded in §1.6.3 as an open decision. Evidence: SOURCE CODE (`git for-each-ref refs/tags`).

---

## 8. Evidence Taxonomy

| Class | Items |
|-------|-------|
| **SOURCE CODE** | `PROJECT_SPECIFICATION.md` edits (E1–E6); `AutoModeController.kt` instrumentation diff (`_t5_commit_staged.diff`); `native_test.c:2273` (C5 gate); `NativeConnectionTest.kt:585-589` (C5 gate); `git` state (HEAD/origin/branch/tags/chain) |
| **TEST / BUILD** | `FIX_TEST40_BUDGET_AND_RE_RUN_REPORT.md` test40 PASS 68.5 s / 51.8 s; `SKIP_C5_AND_EXECUTE_STAGE_B_REPORT.md` forced `:app:assembleProDebug` (114 tasks, APK SHA `158ab71c…`); `COMMIT_C3_FIX_AND_TRIAGE_C5_STALE_REPORT.md` commit `4c013e8` |
| **PHYSICAL DEVICE** | `test27`/C3 PASS (`n=100 … ok=1`) — device instrumentation; Stage B T5 run (clean teardown, 0 retry/refusal strings) |
| **PRIOR REPORT** | `RESUME_BATCH_DEVICE_VERIFY_REPORT.md` §11 (sole prior T5 occurrence); `FIX_TEST40_BUDGET_AND_RE_RUN_REPORT.md`; `SKIP_C5_AND_EXECUTE_STAGE_B_REPORT.md`; `COMMIT_C3_FIX_AND_TRIAGE_C5_STALE_REPORT.md` |
| **INFERENCE** | The 17-commit range and the `0714628` off-by-one are reasoned from `git` ancestry checks |
| **UNKNOWN** | The identity of the T5-SLOT "unresolved operation" holder — still not observed |

---

## 9. Explicit No-Change Confirmation

- **No production code change** — Kotlin/C/C++ sources untouched.
- **No native / Gradle / CMake change** — `native_test.c`, `CMakeLists.txt`, `build.gradle`,
  `settings.gradle` untouched.
- **No test run** — no Gradle task, no instrumentation test executed.
- **No device command executed** — no `adb`, no install, no logcat, no AutoConnect.
- **No push / tag** — remote untouched by this phase (`origin/main` remains `5e51e37`).
- **No commit created this phase** — the docs phase does **not** commit (see STEP 6).
- **C5 not executed** — C5 remains formally gated.
- Only files changed on disk: `PROJECT_SPECIFICATION.md` (modified) and this report (new, untracked).

---

## 10. Recommendation

```
PROCEED TO COMMIT-DOCS-V1.0.8
```

The v1.0.8 documentation update is written and verified. A **separate** phase should stage exactly
the two authorized files and commit them (the operator controls staging and the commit message).
Open items to carry forward (not blocking the commit): the `p2.4.10` tag decision (§7), the stale
§12.5.1 FIX-NATIVE-LIFETIME-SYNC status (§4), and an opportunistic T5 re-capture when contention
next occurs.

No push, no tag. Report in English only.
