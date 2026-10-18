# DOCS-UPDATE-v1.0.9 — Report

**Phase:** `DOCS-UPDATE-v1.0.9`
**Date:** 2026-10-13
**HEAD:** `d61d3f8a1913bf7c7a22c2bd8013828c5111804f`
**origin/main:** `5e51e37f83bba3db8e3f859886bf045bb5ea6255` (local ahead by 1 — the v1.0.8 docs commit, unpushed)
**Branch:** `main`
**Language:** English only.

---

## 1. Executive Verdict

```
DOCS UPDATED — v1.0.9
```

`PROJECT_SPECIFICATION.md` was revised in place from **v1.0.8** to **v1.0.9** to correct the stale
§12.5.1 row 1: **FIX-NATIVE-LIFETIME-SYNC (N-15 + N-16)** is now recorded as **CLOSED —
device-verified** (commits `3a2f442`, `b0418cc`; tests 37–41 PASS), and the still-open
**FINDING T5-SLOT** driver is tracked as a **new row 8**. Documentation only — no
source/test/build/device change. No commit, no push, no tag, no device command.

Diff size: `PROJECT_SPECIFICATION.md | 6 insertions(+), 3 deletions(-)` (one file).

---

## 2. Pre-Conditions (P1–P5)

| ID | Check | Required | Result |
|----|-------|----------|--------|
| **P1** | `git rev-parse HEAD` | `d61d3f8a1913bf7c7a22c2bd8013828c5111804f` | **PASS** — exact match |
| **P2** | `git rev-parse origin/main` | `5e51e37f83bba3db8e3f859886bf045bb5ea6255` | **PASS** — exact match |
| **P3** | `git branch --show-current` | `main` | **PASS** |
| **P4** | `git status --short` — no tracked ` M` / `M ` entries | none | **PASS** — no tracked modifications (untracked evidence files present, expected) |
| **P5** | `PROJECT_SPECIFICATION.md` header = v1.0.8; §12.5.1 row 1 = FIX-NATIVE-LIFETIME-SYNC with status OPEN | both true | **PASS** — header `**Document version:** 1.0.8`; row 1 status read `OPEN — no device required. Driven additionally by **FINDING T5-SLOT** (§12.4). See \`DEEP_REVIEW_VERIFY_REPORT.md\` rows N-15/N-16` (the defect being fixed) |

No stop condition was triggered. Evidence: SOURCE CODE (`git`).

---

## 3. Summary of Edits (A–F)

| # | Location | Change | Evidence |
|---|----------|--------|----------|
| **A** | §12.5.1 row 1 (Status cell) | `OPEN — no device required. Driven additionally by FINDING T5-SLOT (§12.4). …` → `**CLOSED for N-15 + N-16 — device-verified** (tests 37–41 PASS; commits \`3a2f442\`, \`b0418cc\`). See §1.6.1 for the commits. The **FINDING T5-SLOT** driver (§12.4) remains OPEN as a separate item (row 8)` | SOURCE CODE + PHYSICAL DEVICE (prior) |
| **B** | §12.5.1 table (new row) | Appended row **8** — `FINDING T5-SLOT (holder-identity capture)`, scope "instrumentation landed in commit \`5e51e37\`; awaiting the next contention occurrence…", priority `P2_MEDIUM (opportunistic; not blocking)`, status `OPEN — instrumentation armed on device; holder UNKNOWN` | SOURCE CODE + PRIOR REPORT |
| **C** | Header | `**Document version:** 1.0.8` → `1.0.9`; `**HEAD:** 5e51e37…` → `d61d3f8a1913bf7c7a22c2bd8013828c5111804f`; date kept `2026-10-13` (same day) | SOURCE CODE |
| **D** | §20 Changelog | Added the `1.0.9` row (see §4 of this report for exact text). Includes "No new methodology lessons in v1.0.9." | SOURCE CODE |
| **E** | §20 Verification Status | Added a row `Active Fix Queue (§12.5.1)` — HISTORICAL, attributed to the v1.0.9 docs phase | SOURCE CODE |
| **F** | §22 L1–L10 | **Not touched** — no L11+ added; the "no new lessons" statement is carried in the §20 changelog row (edit D) | SOURCE CODE |

### Renumbering
**None required.** Rows 1–7 were preserved verbatim (only row 1's Status cell changed); the new T5-SLOT
row was appended as **#8**, keeping the table sequential. Old→new mapping: rows 1–7 unchanged; row 8 = new.

---

## 4. §12.5.1 Before / After (full row text)

**BEFORE (row 1):**

```
| 1 | **FIX-NATIVE-LIFETIME-SYNC (N-15 + N-16)** | N-15: `rudp_destroy` (`rudp_destroy:181`) destroys `ctx->lock` and frees `ctx` with no guarantee a concurrent `rudp_poll` (`rudp_poll:693`) has returned — a poll in flight during destroy is a use-after-free. N-16: `nativeConnect` (`softether_jni.c:54`) is **not** serialised by `conn->connect_mutex` while `nativeConnectWithHub` (`softether_jni.c:139–145`) is — two concurrent native connects on the same `conn` can interleave. | **P1_HIGH** | OPEN — no device required. Driven additionally by **FINDING T5-SLOT** (§12.4). See `DEEP_REVIEW_VERIFY_REPORT.md` rows N-15/N-16 |
```

**AFTER (row 1):**

```
| 1 | **FIX-NATIVE-LIFETIME-SYNC (N-15 + N-16)** | N-15: `rudp_destroy` (`rudp_destroy:181`) destroys `ctx->lock` and frees `ctx` with no guarantee a concurrent `rudp_poll` (`rudp_poll:693`) has returned — a poll in flight during destroy is a use-after-free. N-16: `nativeConnect` (`softether_jni.c:54`) is **not** serialised by `conn->connect_mutex` while `nativeConnectWithHub` (`softether_jni.c:139–145`) is — two concurrent native connects on the same `conn` can interleave. | **P1_HIGH** | **CLOSED for N-15 + N-16 — device-verified** (tests 37–41 PASS; commits `3a2f442`, `b0418cc`). See §1.6.1 for the commits. The **FINDING T5-SLOT** driver (§12.4) remains OPEN as a separate item (row 8) |
```

**ADDED (row 8):**

```
| 8 | **FINDING T5-SLOT (holder-identity capture)** | Instrumentation landed in commit `5e51e37`; awaiting the next contention occurrence to capture the holder identity. | P2_MEDIUM (opportunistic; not blocking) | OPEN — instrumentation armed on device; holder **UNKNOWN** |
```

**§20 changelog row added (edit D):**

```
| 1.0.9 | 2026-10-13 | DOCS-UPDATE: corrected §12.5.1 row 1 (FIX-NATIVE-LIFETIME-SYNC — N-15/N-16 both CLOSED, device-verified; commits `3a2f442`, `b0418cc`) and added a separate row (8) for the still-open T5-SLOT driver finding. Refreshed the header HEAD field to `d61d3f8`. No new methodology lessons in v1.0.9. No source/test/build/device change; documentation only. |
```

**Diff hunks (authoritative change regions):**

```
@@ -3,8     +3,8      @@  header: HEAD field + version 1.0.8→1.0.9
@@ -1471,13 +1471,14  @@  §12.5.1: row 1 status + new row 8
@@ -1809,6  +1810,7   @@  §20 changelog row 1.0.9
@@ -1831,6  +1833,7   @@  §20 verification-status row §12.5.1
```

---

## 5. Evidence Taxonomy

| Class | Items |
|-------|-------|
| **SOURCE CODE** | `PROJECT_SPECIFICATION.md` edits (A–E); `git` state (HEAD/origin/branch); the corrected §12.5.1 row and new row 8 |
| **TEST** | (referenced) tests 37–41 PASS — the device-verification basis for the N-15/N-16 CLOSED status, per `FIX_NATIVE_LIFETIME_SYNC_VERIFY_REPORT.md` and `FIX_TEST40_BUDGET_AND_RE_RUN_REPORT.md` |
| **PHYSICAL DEVICE** | (referenced, prior) tests 37–41 ran on device `5585fecc`; not re-run this phase |
| **PRIOR REPORT** | `FIX_NATIVE_LIFETIME_SYNC_VERIFY_REPORT.md`; `FIX_TEST40_BUDGET_AND_RE_RUN_REPORT.md`; `SKIP_C5_AND_EXECUTE_STAGE_B_REPORT.md`; `DOCS_UPDATE_v1.0.8_REPORT.md` (§4 flagged this staleness) |
| **INFERENCE** | The §12.5.1 staleness is resolved by aligning the row with the already-recorded commits/verification — no new fact is introduced |
| **UNKNOWN** | T5-SLOT holder identity — still not observed |

---

## 6. Explicit No-Change Confirmation

- **No source/test/build/device change** — no Kotlin/C/C++, no `native_test.c`, no `CMakeLists.txt`, no Gradle.
- **No commit / push / tag** — remote untouched (`origin/main` remains `5e51e37`).
- **§22 L1–L10 unchanged** — verified: the diff contains no `L* —` or `Incident:` line change.
- **§1.6 / §1.6.1 / §1.6.3 unchanged** — verified: no diff hunk falls in the §1.6 range (lines 72–185); the only "§1.6.1" string in the diff is a cross-reference **inside** the new §12.5.1 status text.
- **§12.4 FINDING T5-SLOT body unchanged** — verified: §12.4 sits at line 1399, above the §12.5.1 hunk (line 1471); no change.
- **§12.5.2 roadmap unchanged** — verified: it appears only as an unchanged context line.
- **C5 not executed** — C5 remains formally gated.
- Only files changed on disk: `PROJECT_SPECIFICATION.md` (modified) and this report (new, untracked).

---

## 7. Recommendation

```
PROCEED TO COMMIT-DOCS-V1.0.9
```

The v1.0.9 documentation update is written and verified. A **separate** phase should stage exactly the
two authorized files and commit them (the operator controls staging and the commit message).

No push, no tag. Report in English only.
