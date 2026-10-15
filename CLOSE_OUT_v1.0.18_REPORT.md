# CLOSE_OUT_v1.0.18_REPORT — Documentation close-out and spec hygiene

**Document version:** 1.0.0
**Date:** 2026-10-15
**Phase:** CLOSE-OUT-AND-SPEC-HYGIENE-v1.0.18
**Spec before:** v1.0.17, HEAD `94e02b744bda8d77073b8cd80492f5e485021b9e`
**Spec after:** v1.0.18, HEAD `0c5754e6e9a2c86e7df3b2e7bd966848f68a3210`
**Phase type:** documentation only. **Device-free** — device 5585fecc DETACHED, no `adb` invocation.
**Files changed:** `IMPLEMENT_P1_REMAINING_REPORT.md` (committed), `PROJECT_SPECIFICATION.md`
(edited), `CLOSE_OUT_v1.0.18_REPORT.md` (new, this file).

---

## 1. Executive verdict

The phase closes out device-free loose ends in two commits: one documentation commit that lands the
Phase 12 report, and one spec commit that advances the specification to **v1.0.18** with a new
**§1.6.11**. The single editorial theme is *non-invasiveness*: the frozen sections §1.6.2–§1.6.10 are
untouched, so the one real correction this phase has to make — a stale line reference — is recorded in a
new subsection rather than retro-edited into history.

| Check | Verdict |
|-------|---------|
| Pre-conditions (STEP 1) | **PASS** — 6/6 |
| Phase 12 report integrity | **PASS** — unmodified, 606 lines / 30,431 bytes |
| §1.6.1 chain reconciliation | **PASS** — delta 0 at `HEAD = 0c5754e` |
| Hunk scope (E8) | **PASS** — all 7 hunks in header / §1.6.1 / §1.6.11 / §12.5.1 / §20 |
| §22 byte-identity (E7) | **PASS** — IDENTICAL, 99 lines |
| §12.5.1 row count | **PASS** — 16 → 17 |
| Changelog rows | **PASS** — 18 (1.0.0–1.0.9, 1.0.11–1.0.18) |
| Tag operation | **NONE** — decision documented only (§2) |
| Device / build / test | **NONE** — no command of any kind |

---

## 2. Tag decision documentation — leave `p2.4.10` local-only

**No tag operation was performed in this phase.** The decision was only *documented*, in this report and
in §1.6.11 bullet 2.

```
git tag -l p2.4.10                → p2.4.10            (exists locally)
git ls-remote --tags origin | grep p2.4.10 → (empty)   (absent remotely)
git tag -l                        → p2.4.10 p2.4.5 p2.4.7 p2.4.8 p2.4.9   (5 local)
git ls-remote --tags origin       → p2.4.5 p2.4.7 p2.4.8 p2.4.9          (4 remote)
git rev-parse p2.4.10             → c101380672e353321cd2c2bf820a49e8e71cba6a (annotated tag object)
git log -1 p2.4.10                → 071462816b1eef7e7f7459483fcd69909938cd11
                                     2026-09-28  feat(ui): add DNS presets, app exception icons
                                     and Auto Mode cleanup
```

**Decision: leave it local-only.** Rationale, in order of weight:

1. **It points at the wrong commit.** `p2.4.10` resolves to `0714628` — the *first* commit of the pushed
   chain, whose subject is a UI/cleanup change, not a release cut.
2. **It carries no semantic release value.** It does not mark a release, so pushing it would add a remote
   tag with no clear meaning.
3. **Keeping it local costs nothing.** The remote tag set is already complete and consistent without it.

Evidence: SOURCE CODE.

---

## 3. Phase 12 report committed

`IMPLEMENT_P1_REMAINING_REPORT.md` — the Phase 12 implementation report — had been left untracked when
that phase ended on a STOP condition. It is now committed **unmodified**.

**Integrity check (read-only, before staging):** 606 lines, 30,431 bytes, matching the Phase 12 record
exactly. The header still reads `HEAD at report time = 71edd67 (unchanged — no commits made)` and
`origin/main at report time = 71edd67 (unchanged — nothing pushed)`, which is correct for Phase 12 and is
*not* rewritten here — historical reports are not retro-edited.

| Field | Value |
|-------|-------|
| Commit | `0c5754e6e9a2c86e7df3b2e7bd966848f68a3210` (`0c5754e`) |
| Subject | `docs: commit Phase 12 IMPLEMENT_P1_REMAINING report (was untracked)` |
| Files | 1 (`IMPLEMENT_P1_REMAINING_REPORT.md`, 606 insertions) |
| Staged set verified | exactly one line in `git diff --cached --name-only` |

Evidence: SOURCE CODE.

---

## 4. Spec edits summary (E1–E6)

| ID | Edit | Location | Evidence |
|----|------|----------|----------|
| E1 | `Document version` 1.0.17 → 1.0.18; `HEAD` → `0c5754e6e9a2c86e7df3b2e7bd966848f68a3210`; `Last updated` 2026-10-15 | header | git |
| E2a | Three new rows in the §1.6.1 commit-chain table: `a3a6434`, `94e02b7`, `0c5754e` | §1.6.1 | git |
| E2b | New "Push state (v1.0.18)" note recording the delta-0 verification and naming this revision's own two commits by subject | §1.6.1 | git |
| E3 | New **§1.6.11** — "Line-Reference Corrections and Tag Decision (v1.0.18)", 4 evidence-labelled bullets | §1.6 | SOURCE CODE |
| E4a | §12.5.1 **row 16** (ANR) — status unchanged (OPEN — UNKNOWN); note added: "**Carried forward from Phase 13: opportunistic re-capture when a future AutoConnect run hits the same stall.**" | §12.5.1 | PHYSICAL DEVICE |
| E4b | §12.5.1 **row 17** (new) — PART A device verification, **OPEN — PARTIAL** | §12.5.1 | SOURCE CODE + TEST + PRIOR REPORT |
| E5 | §20 changelog — new **1.0.18** row | §20 | — |
| E6 | §20 verification status — new **§1.6.11** row (HISTORICAL) | §20 | — |
| E7 | §22 L1–L10 untouched | §22 | **byte-identical** |

### 4.1 The line-reference correction, verified

The factual basis for §1.6.11 bullet 1 was confirmed by direct inspection before the note was written:

```
$ grep -n "SSL_CTX_set_verify" SoftEtherClient/src/main/cpp/softether-core/src/crypto/aes_wrapper.c
111:    SSL_CTX_set_verify(c, SSL_VERIFY_NONE, NULL);
```

| Citation site | Line cited | Status |
|---------------|-----------|--------|
| §1.6.7 (line 479) | `aes_wrapper.c:110` | **stale — left unmodified (frozen)** |
| §1.6.8 (line 579) | `aes_wrapper.c:110` | **stale — left unmodified (frozen)** |
| §1.6.9 (line 605) | `aes_wrapper.c:111` | **correct** — and already notes that the `:110` citations predate `b652198` |
| §12.5.1 row 4 (line ~2076) | `aes_wrapper.c:110` | **stale — deliberately NOT modified** (E4 forbids touching other rows; recorded via §1.6.11) |

The drift is exactly one line, caused by commit `b652198` (SEC-01 part 1, native peer-fingerprint
exposure) adding one `#include` near the top of the file. Because §1.6.7 and §1.6.8 are frozen under this
document's rule that historical session summaries are not retro-edited, the correction is recorded in
§1.6.11 instead, so a future reader following a `:110` citation is not misled.

**One deviation from a strict reading of the brief, recorded explicitly:** §12.5.1 row 4 also cites the
stale `:110`. The brief's E4 says "Do NOT modify any other row", so row 4 was left alone. The correction
note in §1.6.11 bullet 1 is the record; if a future revision is authorized to touch row 4, that is where
the `:110` should become `:111`.

---

## 5. §1.6.1 chain reconciliation (N, M, delta)

```
N = git log --oneline 0714628^..0c5754e | wc -l   =  60
M = PROJECT_SPECIFICATION.md §1.6.1 table rows     =  60
delta = N - M                                      =   0
```

Measured at `HEAD = 0c5754e`, which is the HEAD stated in the spec header (E1) — i.e. **before** this
revision's own two commits are made.

Three rows were added: `a3a6434` (the v1.0.17 spec commit) and `94e02b7` (the `DOCS-UPDATE-v1.0.17`
report), both previously deferred by subject in the v1.0.17 note, plus `0c5754e` (this revision's opening
documentation commit). This revision's own spec commit and this report commit are **named by subject** in
the new push-state note and will be listed by hash in a future revision, per the established convention.

---

## 6. §12.5.1 row 16 / 17 — before and after

### Row 16 — before (v1.0.17)

> `| 16 | **FINDING ANR-2026-10-15** … | P2_MEDIUM (unattributed; not blocking PART A, which is otherwise green on JVM + build) | **OPEN — UNKNOWN, NOT REPRODUCED.** …`

### Row 16 — after (v1.0.18)

> `| 16 | **FINDING ANR-2026-10-15** … **Carried forward from Phase 13: opportunistic re-capture when a future AutoConnect run hits the same stall.** | P2_MEDIUM … | **OPEN — UNKNOWN, NOT REPRODUCED.** …`

The **status is unchanged** (OPEN — UNKNOWN, NOT REPRODUCED). Only the scope cell gained the carried-forward
sentence, exactly as the brief specified.

### Row 17 — new (no "before")

> `| 17 | **PART A device verification (partial)** | The four Axis A changes (A-2 … A-5) are committed (`067d4a3`) and JVM-tested: `:app:testProDebugUnitTest` **671/0/0/1** (+24 new over the 647 baseline), `:SoftEtherClient:test` **173/0/0/0**, `:app:assembleProDebug` SUCCESSFUL with a reproducible APK SHA-256. Device verification was **PARTIAL**: one ANR during cycle 1 (unattributed, NOT REPRODUCED — see row 16). Re-verify on a future device run to convert PARTIAL to FULL. See §1.6.10. | P3_LOW (opportunistic) | **OPEN — PARTIAL (no complete device trace).** The JVM and build layers are green and reproducible; only the device layer is incomplete, and the single ANR is recorded separately as row 16 rather than counted against PART A. Evidence: SOURCE CODE + TEST + PRIOR REPORT + PHYSICAL DEVICE (the PARTIAL run) |`

Row count: **16 → 17**. No other row was modified.

---

## 7. §22 byte-identity check (E7)

```
git show HEAD:PROJECT_SPECIFICATION.md | sed -n '/^## 22\. Build and Verification Methodology/,$p'
  vs
sed -n '/^## 22\. Build and Verification Methodology/,$p' PROJECT_SPECIFICATION.md
→ IDENTICAL (99 lines)
```

§22 L1–L10 are untouched. No new methodology lessons were added in v1.0.18.

---

## 8. Explicit negative assurance

- **No source / test / native / build / manifest / XML file was modified.** The only spec hunks are the
  seven verified in E8, none of which touch a source file.
- **No existing report was modified.** `IMPLEMENT_P1_REMAINING_REPORT.md` was committed byte-for-byte as
  it stood after Phase 12; `COMMIT_PART_A_REPORT.md` and `DOCS_UPDATE_v1.0.17_REPORT.md` were not touched.
- **No modification to §22 L1–L10**, and **no modification to §1.6.2–§1.6.10**. The one hunk anchored at
  the §1.6.10 boundary is a pure 44-line insertion with **0 deletions**, so no line of §1.6.10 is changed.
- **No device command.** No `adb` invocation was made; device 5585fecc is DETACHED.
- **No build or test run.** Every figure quoted in this report is carried forward from Phase 12 as
  **PRIOR REPORT** evidence.
- **No tag operation** — not created, moved, deleted or pushed (§2).
- **No `git add -A` / `git add .`** — both commits staged files by exact path.
- **No `--force`, no `reset`, no `clean`, no `stash`.**
- **C5 was not executed.**
- **`D:/PMC/Documents/Android/SoftEthergai` was not touched.**

---

## 9. Recommendation

Proposed only; not begun.

1. **Next device-free phase (when ready):** a feature phase on the source-and-path architecture —
   Emergency Server Sources (§11.9) or Manual Server Entry (§11.10) — per
   `SERVER_SOURCE_ARCHITECTURE.md`. Both are already specified and neither requires the device.
2. **When the device reconnects — Axis A re-verification:** convert §12.5.1 row 17 from
   **OPEN — PARTIAL** to FULL by a clean multi-route AutoConnect run on the PART A build.
3. **When the device reconnects — ANR re-capture:** close row 16 opportunistically if a future
   AutoConnect run reproduces the same stall; otherwise close its UNKNOWNs by reading `/data/anr` on a
   rooted or userdebug device, or by reproducing under `Debug.` systrace / a `StrictMode` dump.
4. **Optional future docs revision:** if §12.5.1 row 4 is ever authorized for editing, correct its
   `aes_wrapper.c:110` to `:111` (§4.1).

---

*End of report.*
