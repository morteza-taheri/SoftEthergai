# SPEC_HYGIENE_v1.0.19_REPORT — Documentation corrections and the p2.4.10 rationale fix

**Document version:** 1.0.0
**Date:** 2026-10-16
**Phase:** SPEC-HYGIENE-v1.0.19
**Spec before:** v1.0.18, HEAD `0c5754e6e9a2c86e7df3b2e7bd966848f68a3210`
**Spec after:** v1.0.19, HEAD `abc8bd1f84bb7dcf08edd082f3d7e65dcae0183d`
**Tag decision outcome (BLOCK 3):** **PUSHED**
**Phase type:** documentation only. **Device-free** — no `adb` invocation; device 5585fecc DETACHED.
**Files changed:** `PROJECT_SPECIFICATION.md` (edited) and this report (new). Nothing else.

---

## 1. Executive verdict

The specification is advanced from **1.0.18** to **1.0.19**. The substantive content is a new
**§1.6.12 "Documentation Corrections (v1.0.19)"** that fixes three documentation defects surfaced by
BLOCK 1 forensics and records the corrected `p2.4.10` rationale established by BLOCK 2. The operator-
authorized `p2.4.10` tag push from BLOCK 3 is recorded as executed.

The governing principle of this revision is, again, **non-invasiveness**: §1.6.2–§1.6.11 stay frozen,
so every superseded text is *recorded* in §1.6.12 rather than retro-edited. The one place where
correction is genuinely impossible — `DOCS_UPDATE_v1.0.17_REPORT.md` §7 — is called out explicitly as
a permanent historical artifact rather than being quietly ignored.

| Check | Verdict |
|-------|---------|
| Pre-conditions (STEP 1) | **PASS** — 6/6 |
| Hunk scope (STEP 3) | **PASS** — all 5 hunks in header / §1.6.1 / §1.6.12 / §20 |
| §22 byte-identity (E7) | **PASS** — IDENTICAL, 99 lines / 6,504 bytes |
| §1.6.2–§1.6.11 frozen | **PASS** — 0 deletions inside that range |
| §12.5.1 | **PASS** — unchanged, 17 rows |
| Changelog rows | **PASS** — 19 (1.0.0–1.0.9, 1.0.11–1.0.19) |
| §1.6.x subsections | **PASS** — 12 (1.6.1 … 1.6.12) |
| Tag operation | **ONE** — the operator-authorized push in BLOCK 3; none in this phase |
| Device / build / test | **NONE** — no command of any kind |

---

## 2. Pre-conditions

All checked before the first edit. All PASS.

| # | Check | Result |
|---|-------|--------|
| 2.1 | `HEAD` == `origin/main` | PASS — both `abc8bd1f84bb7dcf08edd082f3d7e65dcae0183d` |
| 2.2 | Branch is `main` | PASS |
| 2.3 | No tracked modification | PASS — `git status --porcelain=v1 --untracked-files=no` empty |
| 2.4 | Spec `Document version` is 1.0.18 | PASS — line 8, before edit |
| 2.5 | §1.6.2 – §1.6.11 all present | PASS — 10 subsections, lines 238 … 772 |
| 2.6 | §22 heading present | PASS — line 2551, `## 22. Build and Verification Methodology (Lessons)` |
| 2.7 | Tag state reflects PUSHED | PASS — 5 local / **5 unique remote** incl. `p2.4.10` |

---

## 3. Edits summary (E1–E6)

| ID | Edit | Location | Evidence |
|----|------|----------|----------|
| E1 | `Document version` 1.0.18 → 1.0.19; `HEAD` → `abc8bd1f84bb7dcf08edd082f3d7e65dcae0183d`; `Last updated` → 2026-10-16 | header | git |
| E2 | New **"Push state (v1.0.19)"** note (5 lines) appended after the v1.0.18 note, recording the executed PUSHED decision, the 5/5 tag sets, and the delta-1 chain arithmetic | §1.6.1 | git |
| E3 | New **§1.6.12 "Documentation Corrections (v1.0.19)"** — 6 evidence-labelled bullets | §1.6 | SOURCE CODE |
| E4 | §12.5.1 — **no row changed** | §12.5.1 | — |
| E5 | §20 changelog — new **1.0.19** row | §20 | — |
| E6 | §20 verification status — new **§1.6.12** row (HISTORICAL) | §20 | — |
| E7 | §22 untouched | §22 | **byte-identical** |

Only 3 lines were deleted in the entire diff, all three in the header (E1). E2 was performed as a pure
append, so none of the nine existing push-state notes was modified.

---

## 4. §1.6.12 content recap

1. **§1.6.1 preamble marked superseded** — the *"Fifty-three commits … `0714628` → `e6b9eef`"* text is
   a v1.0.16-era snapshot; the authoritative count is the latest §1.6.1 push-state note (60 rows at
   v1.0.18, plus the v1.0.19 note). Evidence: SOURCE CODE.
2. **The v1.0.18 note's "two own commits" corrected to one** — Phase 14 landed the spec update *and*
   `CLOSE_OUT_v1.0.18_REPORT.md` in a single commit, `abc8bd1` (2 files). Chain arithmetic is
   unaffected: delta 1 = one own-commit. Evidence: SOURCE CODE.
3. **`p2.4.10` rationale corrected to "a genuine 2.4.10 release cut"** — with the four-point evidence
   chain (§4.1 below) and the note that the tag action taken in BLOCK 3 was **PUSHED**; remote unique
   tag set is now 5, local unchanged at 5, published tag object byte-identical to the local one.
   Evidence: SOURCE CODE.
4. **`DOCS_UPDATE_v1.0.17_REPORT.md` §7 cannot be retro-edited** — it stands as historical record; the
   correction lives here. Evidence: SOURCE CODE + INFERENCE.
5. **Forward note** — HEAD (`abc8bd1`) shares `versionName "2.4.10"` / `versionCode 143` with tag
   `p2.4.10` while sitting 60 commits ahead; a future release phase should bump the version past 2.4.10.
   Also records that the 2.4.10 artifact was debug-signed (RISK-A) because the fail-closed guard
   (`c572050`) did not yet exist at `0714628`. Evidence: SOURCE CODE.
6. **No new methodology lessons.**

### 4.1 The `p2.4.10` evidence chain (from BLOCK 2, re-verified as SOURCE CODE)

```
git show 0714628:app/build.gradle   → versionCode 143 / versionName "2.4.10"   (lines 64–65)
git show 5c13603:app/build.gradle   → versionCode 142 / versionName "2.4.9"
p2.4.9^{commit}                     = 5c13603c6276c11460982e0633203667d26ef3e7
git rev-list --count p2.4.9..p2.4.10 = 1
tag object c101380672e353321cd2c2bf820a49e8e71cba6a → annotated, body ends
    "Version: Bump to 2.4.10 (versionCode 143)."
```

---

## 5. Diff-scope confirmation

Five hunks, all in allowed regions. Anchors are line numbers in the **pre-edit** file (`HEAD`).

| Hunk | Old anchor | Region | Content |
|------|-----------|--------|---------|
| `@@ -7,3 +7,3 @@` | 7–9 | header (E1) | HEAD / version / last-updated |
| `@@ -237,0 +238,5 @@` | 237 | §1.6.1 (E2) | new push-state note, inserted before old 238 (`#### 1.6.2`) |
| `@@ -815,0 +821,60 @@` | 815 | §1.6.12 (E3) | 60-line insertion after §1.6.11's blank trailing line, before the `---` at old 816 |
| `@@ -2488,0 +2554 @@` | 2488 | §20 changelog (E5) | 1.0.19 row |
| `@@ -2531,0 +2598 @@` | 2531 | §20 verification status (E6) | §1.6.12 row |

**Total: 70 insertions, 3 deletions.** The 3 deletions are exactly the three header lines of E1:

```
-**HEAD:** `0c5754e6e9a2c86e7df3b2e7bd966848f68a3210`
-**Document version:** 1.0.18
-**Last updated:** 2026-10-15
```

There is **no hunk inside the frozen range §1.6.2–§1.6.11** (old lines 238–815) and **no hunk inside
§22**. The hunk anchored at 815 is a pure 60-line insertion with **0 deletions**, so no line of
§1.6.2–§1.6.11 was deleted or replaced.

---

## 6. §22 byte-identity result (E7)

```
awk '/^## 22\./{flag=1} flag{print}' PROJECT_SPECIFICATION.md      → 99 lines, 6,504 bytes
git show HEAD:PROJECT_SPECIFICATION.md | awk '…'                   → 99 lines, 6,504 bytes
diff -q                                                            → IDENTICAL
```

§22 L1–L10 are untouched. No new methodology lessons were added in v1.0.19.

---

## 7. Explicit negative assurance

- **No source, test, native, build, manifest or XML file was modified.** The only edits are the five
  spec hunks in §5, none of which touch a source file.
- **No existing report was modified**, including `DOCS_UPDATE_v1.0.17_REPORT.md`,
  `CLOSE_OUT_v1.0.18_REPORT.md`, `COMMIT_PART_A_REPORT.md` and `IMPLEMENT_P1_REMAINING_REPORT.md`.
- **No modification to §22**, and **no modification to §1.6.2–§1.6.11** (0 deletions in that range).
- **No modification to any §12.5.1 row** — 17 rows before and after.
- **No device command.** No `adb` invocation; device 5585fecc is DETACHED.
- **No build or test run.** Every figure in this report is either read directly from the repository
  or carried forward from BLOCK 2 as **SOURCE CODE** / **PRIOR REPORT** evidence.
- **No tag operation in this phase.** The single tag operation of the whole sequence is the
  operator-authorized push of `p2.4.10` executed in BLOCK 3, which this phase only *records*. No tag
  was created, deleted, moved, or force-pushed.
- **No `git add -A` / `git add .`** — both files staged by exact path.
- **No `--force`, no `reset`, no `clean`, no `stash`, no `checkout`, no `restore`.**
- **C5 was not executed.**
- **`D:/PMC/Documents/Android/SoftEthergai` was not touched.**

---

## 8. Recommendation

Proposed only; not begun.

1. **A future release phase should bump `versionCode`/`versionName` past 2.4.10** before the next cut,
   so that HEAD and the `p2.4.10` tag stop sharing an identity (§1.6.12 bullet 5). This is the one
   item from this phase with a real downstream consequence.
2. **Optionally, a later revision may append a note under §1.6.12** confirming the version bump once
   it happens, rather than editing any frozen section.
3. The remaining standing items from earlier phases are unchanged: Axis A device re-verification
   (§12.5.1 row 17, PARTIAL → FULL) and the ANR re-capture (§12.5.1 row 16, OPEN — UNKNOWN), both
   awaiting a device.
4. **No further documentation-hygiene work is outstanding** for the v1.0.19 corrections — all three
   BLOCK 1 defects (stale §1.6.1 preamble, the "two own commits" wording, the `p2.4.10` rationale) and
   the BLOCK 2 correction are now recorded in §1.6.12 and in the §1.6.1 v1.0.19 push-state note.

---

*End of report.*
