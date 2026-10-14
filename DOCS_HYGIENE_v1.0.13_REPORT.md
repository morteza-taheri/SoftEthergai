# DOCS-HYGIENE-v1.0.13 — Report

**Phase:** DOCS-HYGIENE-v1.0.13
**Repository:** `C:\Dev\SoftEthergai` (canonical name `SoftEthergai`)
**Branch:** `main`
**Base HEAD at phase start:** `81163e4e725e771ae532d3f143c848e118333117` (= `origin/main`)
**Spec version:** 1.0.12 → 1.0.13
**Date:** 2026-10-15 (the clock crossed midnight after the previous phase; the header
`Last updated` field was therefore advanced from 2026-10-14 to **2026-10-15**)
**Scope:** documentation only. **No source / test / native / build / device change. No Gradle
invocation. No APK. No device.**

Evidence labels: **SOURCE CODE** / **PHYSICAL DEVICE** / **PRIOR REPORT** / **INFERENCE**.

---

## 1. Executive verdict

**PASS.** The spec was brought into doc-hygiene order with documentation-only edits, the untracked
phase report was committed unchanged, and the §1.6.1 commit table was proven complete and consistent.

| Flag | Item | Outcome |
|------|------|---------|
| FLAG-1 | Stale header path | **ADDRESSED** — canonical `**Repository:**` line kept as-is; one-line canonical-name note added; new **§2.6** documents the D: → C: move |
| FLAG-2 | Untracked `FIX_HANGING_SKIP_TEST_REPORT.md` | **COMMITTED UNCHANGED** (commit `b38efa7`, +593/−0) |
| FLAG-3 | §1.6.1 chain sanity | **VERIFIED** — N = 41, M = 41, delta = 0 → **no further divergence** |
| FLAG-4 | §21 intentionally unassigned | **NO CHANGE NEEDED** — §20 already states "§21 intentionally unassigned", so the status table does **not** imply otherwise |

### 1.1 One deviation from the phase brief — flagged

The brief's STEP A2 said to insert the new section **"immediately after §2.5 (Key Components)"** and
to label it **`### 2.6`**. The document's chapter 2 actually runs **§2.1–§2.4 only**, and
"Key Components" is **§2.2**, not §2.5 — there is no §2.5. The brief's *placement* instruction
("at the end of chapter 2") is unambiguous and was followed exactly; its *number* was taken
literally as **§2.6**, because the brief dictated that exact string in three places (the section
heading, the verbatim header note, and the C4 row).

**Consequence:** §2.5 is now unassigned, giving chapter 2 the sequence §2.1, §2.2, §2.3, §2.4, §2.6.
This is consistent with the document's existing precedent (§21 is likewise intentionally unassigned),
but it is a numbering gap. If a contiguous sequence is preferred, the section should be renumbered
**§2.5** — a one-line change in three places (heading, header note, §20 verification row). Not done
here, to avoid silently rewriting text the brief dictated verbatim.

### 1.2 One brief assumption corrected

The brief's STEP C2 assumed the header `HEAD` field read `81163e4…`. It actually read `3dfb0f3…`
(the v1.0.12 revision recorded the HEAD that existed *when the spec was written*, per the document's
convention, and `81163e4` was that revision's own commit). The field was advanced to the new
HEAD-at-spec-write-time, `b38efa7…`.

---

## 2. §1.6.1 sanity result (FLAG-3 / STEP C1)

Command used: `git log --oneline 0714628^..HEAD` (the **inclusive** range, matching the document's
own counting convention — see §1.6.1's note that the range-exclusive `0714628..HEAD` omits `0714628`).

| Measurement | Value |
|-------------|-------|
| **N** — `git log --oneline 0714628^..HEAD \| wc -l` | **41** |
| **M** — §1.6.1 table data rows | **41** |
| **delta** = N − M | **0** |

**Set comparison** (not just counts) was also run — the hashes in git vs the hashes in the table:

- in git but **not** in the table: *(none)*
- in the table but **not** in git: *(none)*

### 2.1 Action taken

At **phase start** (HEAD = `81163e4`) the delta was **3**: the table held 37 rows but git held 40
commits, because the previous phase's own three commits were not yet rows. Per STEP C1
("any commit not in the table must be added as a row"), the following were added:

| Added row | Subject |
|-----------|---------|
| `6360228` | test: synchronize collector-log collection and bound awaitTerminal |
| `203addf` | docs: update PROJECT_SPECIFICATION to v1.0.12 (R1-R6 + R4b + test fixes) |
| `81163e4` | docs: report for FIX-TEST-FLAKINESS-AND-DOCS-v1.0.12 |
| `b38efa7` | docs: commit FIX-HANGING-SKIP-TEST report (was left untracked) — this phase's report commit |

The count sentence in §1.6.1 was updated from "Thirty-seven … `0714628` → `3dfb0f3` … reports 36" to
**"Forty-one … `0714628` → `b38efa7` … reports 40"**, and a **"Push state (v1.0.13)"** note was added.

### 2.2 Explicit statement

> **No further divergence.** After the four rows were added, the §1.6.1 table and
> `git log --oneline 0714628^..HEAD` agree exactly — 41 rows vs 41 commits, and the hash sets are
> identical in both directions (delta = 0).

*(Note for the next revision: this phase's **own** commit — the spec + report commit — is not a row,
by the document's stated convention that a revision's own commits are listed by hash only in the
following revision. A re-check at the final HEAD will therefore show N = 42 vs M = 41, which is the
documented, intended behaviour, not a divergence.)*

---

## 3. Header path decision (FLAG-1 / STEP A1–A2)

**Decision: the canonical `**Repository:**` line is retained unchanged.**

```
**Repository:** `D:\PMC\Documents\Android\SoftEthergai`
> **Canonical repository name:** `SoftEthergai` (see §2.6 for local mirrors)
```

The path was **not** rewritten to `C:\Dev\SoftEthergai`. `D:\PMC\Documents\Android\SoftEthergai` is
the repository's **historical/canonical name**, not the current machine's working directory; silently
replacing it would erase the record of the move. Instead:

- **STEP A1** — one-line note added directly under `**Repository:**`:
  `> **Canonical repository name:** \`SoftEthergai\` (see §2.6 for local mirrors)`.
- **STEP A2** — new **§2.6 Local Mirrors / Working Copies** inserted at the end of chapter 2,
  documenting: the canonical identity (`SoftEthergai`, single `origin/main`); a table of the two
  working copies (`D:\PMC\…` historical/backup, `C:\Dev\SoftEthergai` active); the rule that paths are
  machine-specific and must not be used as repository identity; and the historical record of the
  move (D: pathological I/O; cold restart did not fix it; bounded diagnostic isolated AAPT2/Kotlin
  daemon exits; C: resolved it — the same commands that hung on D: for 20–45 min completed on C: in
  seconds to minutes; D: retained as fallback backup).

Evidence class recorded in the section: **PHYSICAL DEVICE** (measurements) + **SOURCE CODE** (paths) —
attributed to the RESUME-R4B-FROM-C-DRIVE phase (**PRIOR REPORT**), not re-measured here.

**STEP A3 check:** `git diff` on the spec shows only (a) the header one-line note, (b) the new §2.6,
plus the authorized v1.0.13 header bump / §1.6.1 rows / §20 rows from PART C. Confirmed in §4.

---

## 4. Diff of `PROJECT_SPECIFICATION.md`

```
 PROJECT_SPECIFICATION.md | 58 +++++++++++++++++++++++++++++++++++++++---------
 1 file changed, 48 insertions(+), 10 deletions(-)
```

Hunk map (`git diff -U0`):

| Hunk | Region | Change |
|------|--------|--------|
| `@@ -4,0 +5 @@` | Header | the one-line canonical-name note (A1) |
| `@@ -6,3 +7,3 @@` | Header | version `1.0.12`→`1.0.13`, HEAD `3dfb0f3…`→`b38efa7…`, `Last updated` `2026-10-14`→`2026-10-15` |
| `@@ -93,2 +94,2 @@` | §1.6.1 | count sentence: 37 → 41, range → `b38efa7`, exclusive 36 → 40 |
| `@@ -97,5 +98,5 @@` | §1.6.1 | same sentence (continuation / growth list) |
| `@@ -141,0 +143,4 @@` | §1.6.1 | new "Push state (v1.0.13)" note |
| `@@ -160,0 +166,6 @@` | §1.6.1 | four new commit rows |
| `@@ -461,0 +473,25 @@` | **§2.6** | the new section (A2) |
| `@@ -2045,0 +2082 @@` | §20 | changelog `1.0.13` row |
| `@@ -2052,0 +2090 @@` | §20 | verification-status row for §2.6 |

**Scope check (D1) — PASS.** In the new file, §1.6.2 begins at line 173, §1.6.3 at 186, §1.6.4 at
228, §1.6.5 at 311, §22 at 2140. The two §1.6.1 insertions land at new lines 143–146 and 166–171
(both **before** §1.6.2); the §2.6 insertion lands at 473–497; the §20 rows at 2082 and 2090.

> **No hunk touches §1.6.2, §1.6.3, §1.6.4, §1.6.5, or §22 (L1–L10).** §22 is byte-identical.

`FIX_HANGING_SKIP_TEST_REPORT.md` was committed with **zero content change** (`593 insertions, 0
deletions` — a pure addition of the previously-untracked file).

---

## 5. Explicit no-change statements

- **No source code change.** No Kotlin, C/C++, or Java file was modified.
- **No test change.** No file under any `src/test/` or `src/androidTest/` tree was modified.
- **No build change.** No Gradle, CMake, `settings.gradle`, or wrapper file was modified.
- **No native change.**
- **No device interaction** — no `adb` command of any kind; no device was present or required.
- **No Gradle invocation** at all in this phase (D3). No APK was built.
- **§22 L1–L10 untouched** (STEP C5); **§1.6.2 / §1.6.3 / §1.6.4 / §1.6.5 untouched**.
- **No existing report was modified.** `FIX_HANGING_SKIP_TEST_REPORT.md` was committed **as-is**.
- **No `git add -A` / `git add .`**; no `--force`, no tag, no reset, no clean, no stash. **C5 not
  executed.**

Files changed in this phase (complete list):

| File | Change |
|------|--------|
| `PROJECT_SPECIFICATION.md` | modified (v1.0.12 → v1.0.13, documentation only) |
| `FIX_HANGING_SKIP_TEST_REPORT.md` | committed unchanged (was untracked) |
| `DOCS_HYGIENE_v1.0.13_REPORT.md` | new (this report) |

---

## 6. Recommendation (propose only — NOT executed)

1. **Renumber §2.6 → §2.5** (or explicitly document §2.5 as intentionally unassigned, as §21 is) to
   remove the chapter-2 numbering gap described in §1.1. Three one-line edits.
2. **Next revision:** list this phase's own commit by hash in §1.6.1 (per the stated convention),
   moving the count from 41 → 42 rows.
3. **Consider promoting §2.6's "Rule"** (refer to the canonical name and `origin/main`, never to a
   machine-specific path) into §15 Release/Git Discipline, so it applies to commit messages
   repo-wide rather than only being stated in the mirrors section.
4. **Optional:** the header `**Repository:**` field could gain an explicit "(historical/canonical)"
   marker so a future reader does not mistake it for a stale path — the §2.6 cross-reference already
   covers this, but the marker would make it self-evident.
