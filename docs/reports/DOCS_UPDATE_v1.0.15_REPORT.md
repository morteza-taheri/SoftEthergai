# DOCS-UPDATE-v1.0.15 — Report

**Phase:** DOCS-UPDATE-v1.0.15
**Repository:** `C:\Dev\SoftEthergai` (canonical name `SoftEthergai`)
**Branch:** `main`
**Base HEAD at phase start:** `23816cb85c7fc68fcadfccbbd01fb9e38ca2bb2a` (= `origin/main`)
**Spec version:** 1.0.14 → 1.0.15
**Date:** 2026-10-15
**Scope:** documentation-only. Record the four implemented + device-verified security fixes
(N-09, N-11, RISK-B, SEC-02) in the spec, without touching any source/test/build file.

Evidence labels: **SOURCE CODE** / **TEST** / **PHYSICAL DEVICE** / **INFERENCE** / **PRIOR REPORT**.

---

## 1. Executive verdict

**PASS.** `PROJECT_SPECIFICATION.md` was advanced from v1.0.14 to v1.0.15 with surgical edits to the
header, §1.6.1, a new §1.6.8, §12.5.1 (rows 4/5/7/13/14), and §20 (changelog + verification status).
No hunk falls in §1.6.2–§1.6.7 or §22, and both regions are **byte-identical** to HEAD. The §1.6.1
commit chain reconciles exactly: **N = 47, M = 47, delta = 0**. No source / test / native / build /
manifest / XML file was modified; no build, test, or device command was run.

---

## 2. Pre-conditions (P1–P6)

| # | Check | Expected | Observed | Result |
|---|-------|----------|----------|--------|
| P1 | `git rev-parse HEAD` | `23816cb…` | `23816cb85c7fc68fcadfccbbd01fb9e38ca2bb2a` | PASS |
| P2 | `git rev-parse origin/main` | `23816cb…` | `23816cb85c7fc68fcadfccbbd01fb9e38ca2bb2a` | PASS |
| P3 | `git branch --show-current` | `main` | `main` | PASS |
| P4 | `git status --short` (tracked) | clean | empty | PASS |
| P5 | Spec header `Document version` | `1.0.14` | `1.0.14` | PASS |
| P6 | §1.6.7 present ("Security Audit and RISK-B Closure") | yes | present (line 429) | PASS |

§12.5.1 rows 4, 5, 7, 13, 14 were read and confirmed to hold the v1.0.14 design-only statuses before
editing.

---

## 3. Summary of spec edits (E1–E6)

### E1 — Header

| Field | Before | After |
|-------|--------|-------|
| `**HEAD:**` | `6cffd1eb63220b1bc5aaf7fb321b7e701205bfc3` | `23816cb85c7fc68fcadfccbbd01fb9e38ca2bb2a` |
| `**Document version:**` | `1.0.14` | `1.0.15` |
| `**Last updated:**` | `2026-10-15` | `2026-10-15` (unchanged) |

The canonical `**Repository:**` line was **not** changed (machine-specific paths are governed by §2.6).

### E2 — §1.6.1 commit chain

- **Intro count/range:** "Forty-two commits … range `0714628` → `6cffd1e`" → "**Forty-seven** commits …
  range `0714628` → **`23816cb`**"; the range-exclusive figure `41` → **`46`**. The "grown by" list was
  extended with "the v1.0.14 security audit + spec commits, and the FIX-NATIVE-SECURITY-SMALL
  native/security fixes and report commit".
- **Table:** appended 5 rows (oldest first): `65b25f8`, `a60681f`, `f66f464`, `1bbf8a6`, `23816cb`.
- **Push note:** added a **Push state (v1.0.15)** note naming the five commits by hash and recording the
  chain as **47** through `23816cb` (46 range-exclusive).

### E3 — NEW §1.6.8 "Security Fixes Landed + Device-Verified (v1.0.15)"

Inserted immediately after §1.6.7. Ten numbered bullets (N-09, N-11, RISK-B, SEC-02 source; SEC-02
device enforcement; collector; AutoConnect; no-crash; in-place update; SEC-01 still open), each with an
evidence label, plus a "Methodology lessons: none" line.

### E4 — §12.5.1 Active Fix Queue (rows 4/5/7/13/14)

See §5 below for the before/after status cells. Row 4's **status was not changed** (still OPEN); only a
§1.6.8 cross-reference was appended. Rows 5/7/13/14 were moved to FIXED / CLOSED. No other row was
touched.

### E5 — §20 Changelog

Added row `1.0.15` (2026-10-15) recording the four fixes, the §12.5.1 row updates, the §1.6.1 chain
advance to `23816cb`, and that SEC-01 remains OPEN.

### E6 — §20 Verification Status

Added a row for **§1.6.8** (HISTORICAL — attributed to this phase; the device statements attributed to
DEVICE-VERIFY-SECURITY-SMALL).

### E7 — §22

**Not touched** (verified byte-identical, §5.4 / §6).

---

## 4. §1.6.1 chain reconciliation

| Quantity | Value |
|----------|-------|
| N — `git log --oneline 0714628^..HEAD \| wc -l` (authoritative inclusive) | **47** |
| M — table rows with a 7-hex hash in §1.6.1 | **47** |
| **delta (M − N)** | **0** |
| First row | `0714628` — feat(ui): add DNS presets, app exception icons and Auto Mode cleanup |
| Last row | `23816cb` — docs: report for FIX-NATIVE-SECURITY-SMALL |

The five new rows (`65b25f8`, `a60681f`, `f66f464`, `1bbf8a6`, `23816cb`) bring the table from 42 to 47
rows, matching the authoritative inclusive count.

---

## 5. §12.5.1 — before/after (rows 4, 5, 7, 13, 14)

**Row 4 (N-06 / SEC-01)** — status unchanged (still OPEN); a §1.6.8 reference was appended:
- Before: `**OPEN — design proposed (TOFU fingerprint pinning), implementation deferred.** See §1.6.7 and `AUDIT_SECURITY_AND_RISK_B_v1.0.14.md`. Evidence: SOURCE CODE`
- After:  `**OPEN — design proposed (TOFU fingerprint pinning), implementation deferred.** See §1.6.7, §1.6.8 (bullet 10) and `AUDIT_SECURITY_AND_RISK_B_v1.0.14.md`. Evidence: SOURCE CODE`

**Row 5 (N-09):**
- Before: `**OPEN — migration design (route through `generate_random_bytes()` / `RAND_bytes`), deferred.** See §1.6.7. Evidence: SOURCE CODE`
- After:  `**FIXED (v1.0.15) — device-non-observable; see §1.6.8 bullet 1. Commit `f66f464`.** Evidence: SOURCE CODE + TEST`

**Row 7 (RISK-B):**
- Before: `**CLOSED (v1.0.14)** — exhaustive call-graph audit: dead code (unreachable body — `isConnected` never set true, `nativeHandle` never assigned non-zero — plus zero production/test callers and no reflection); recommend deletion. See §1.6.7. Evidence: SOURCE CODE + TEST`
- After:  `**CLOSED (v1.0.15)** — dead code deleted (v1.0.14 call-graph audit: unreachable body — `isConnected` never set true, `nativeHandle` never assigned non-zero — plus zero production/test callers and no reflection); see §1.6.8 bullet 3. Commit `1bbf8a6`. Evidence: SOURCE CODE + TEST`

**Row 13 (SEC-02 / APP-1):**
- Before: `**OPEN — migration design (scope cleartext to named hosts), deferred.** See §1.6.7 and `AUDIT_SECURITY_AND_RISK_B_v1.0.14.md`. Evidence: SOURCE CODE`
- After:  `**FIXED (v1.0.15) — device-verified active enforcement; see §1.6.8 bullets 4–5. Commit `1bbf8a6`.** Evidence: SOURCE CODE + PHYSICAL DEVICE`

**Row 14 (N-11):**
- Before: `**OPEN — strict bounded-parse design, deferred.** See §1.6.7. Evidence: SOURCE CODE + INFERENCE`
- After:  `**FIXED (v1.0.15) — see §1.6.8 bullet 2. Commit `f66f464`.** Evidence: SOURCE CODE + TEST`

### 5.4 Diff-scope verification

`git diff -U0 PROJECT_SPECIFICATION.md` hunk headers (new-file line ranges):

| Hunk | Region |
|------|--------|
| `@@ -7,2 +7,2 @@` | header block (E1) |
| `@@ -94,2 +94,2 @@`, `@@ -99,4 +99,5 @@` | §1.6.1 intro (E2) |
| `@@ -147,0 +149,5 @@` | §1.6.1 table rows (E2) |
| `@@ -180,0 +187,5 @@` | §1.6.1 push note (E2) |
| `@@ -472,0 +484,70 @@` | §1.6.8 new section (E3) |
| `@@ -1792,2 +1873,2 @@`, `@@ -1795 +1876 @@`, `@@ -1801,2 +1882,2 @@` | §12.5.1 rows 4/5/7/13/14 (E4) |
| `@@ -2138,0 +2220 @@` | §20 changelog (E5) |
| `@@ -2177,0 +2260 @@` | §20 verification status (E6) |

Every hunk falls in the header, §1.6.1, §1.6.8, §12.5.1, or §20. **No hunk in §1.6.2–§1.6.7 or §22.**

Byte-identity checks (HEAD vs working tree):
- **§22** (`## 22.` → EOF): **byte-identical** (99 lines).
- **§1.6.2–§1.6.7** (through the v1.0.14 methodology line): **byte-identical** (290 lines).
- **§1.6.7 alone**: **byte-identical** (43 lines).

(The only structural delta near §1.6.7 is the chapter-separator `---`, which was relocated to follow
the newly inserted §1.6.8 — the §1.6.7 text itself is unchanged.)

Spec diff stat: **1 file changed, 96 insertions(+), 13 deletions(-)**.

---

## 6. Explicit no-change statement

- **No source / test / native / build / manifest / XML file was modified.** The only file changed in
  this phase is `PROJECT_SPECIFICATION.md`.
- **No build and no test were run.** **No device command was issued** (the connected device was not
  touched).
- **No commit, push, tag, reset, clean, or stash** was performed (see §7 — the commit is the phase's
  own final step, executed separately).
- **§22 (L1–L10) is byte-identical** to HEAD; **§1.6.2–§1.6.7 are byte-identical** to HEAD.
- **No existing report was modified.** `DOCS_UPDATE_v1.0.15_REPORT.md` is new.
- **C5 was not executed.** `D:\PMC\Documents\Android\SoftEthergai` was not touched.

---

## 7. Recommendation (propose only — not executed)

1. **SEC-01 next.** The only remaining HIGH finding is native TLS certificate verification. A dedicated
   implementation phase could realise the TOFU fingerprint-pinning design from §1.6.7 (store the first
   observed fingerprint per endpoint, fail closed on change, provide an explicit re-trust path).
2. **Optional follow-on.** Removing the now-orphaned `isConnected` field/getter and `tag` in
   `SoftEtherClient.kt` (flagged in §1.6.7 / §5.2) as a small hygiene change.
3. **Housekeeping.** The many untracked `_*` evidence/log artifacts at the repo root could be
   consolidated or ignored via `.gitignore` in a future hygiene phase.

None of the above is executed here; this phase is documentation-only.
