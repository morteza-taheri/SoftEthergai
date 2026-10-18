# SPEC-INTEGRITY-AUDIT v1.0.30 — Report

**Project:** SoftEthergai — Native SoftEther VPN Client for Android
**Repository:** `C:\Dev\SoftEthergai` (canonical identity `SoftEthergai`)
**Branch:** `main`
**Date:** 2026-10-18
**Spec version produced:** 1.0.30 (`PROJECT_SPECIFICATION.md` §1.6.22 + §20 rows)
**Device:** DETACHED — **no adb / device command was run in this phase.**

---

## §1. Verdict

**PASS.** The spec's `*.md` references were audited against the `docs/reports/` relocation
(commit `a76bdc6`), **19 stale references were repaired** in the authorized regions
(§1.6.2–§1.6.21 + §12.5.1), the concurrent-worktree contamination event was recorded as a
process lesson in the new **§1.6.22**, the pending `HttpFetcher.kt` doc-comment change was
committed (`9d569c3`), and the single skipped `:app` test was identified
(`HtmlParserOracleTest.softetherUdpOnlyNeverBorrowsOpenvpnTcp`, a data-dependent
`AssumptionViolatedException`). §22 is byte-identical (**99 lines / 6,504 bytes**). JVM suites
match the post-reconcile baseline: `:app` **790/0/0/1**, `:SoftEtherClient` **173/0/0/0**.

No source/test behaviour changed; no reference is unresolvable.

---

## §2. Pre-conditions

| Check | Value | Result |
|-------|-------|--------|
| `git rev-parse HEAD` | `a76bdc6b62921a1289b03e90b6c790b6d4215a29` | — |
| `git rev-parse origin/main` | `a76bdc6b62921a1289b03e90b6c790b6d4215a29` | **HEAD == origin/main** ✓ |
| branch | `main` | ✓ |
| spec version | `1.0.29` | ≥ 1.0.28 ✓ |
| tags local / remote | 5 / 5 (`p2.4.5/7/8/9/10`) | ✓ |
| tracked dirty | `app/.../HttpFetcher.kt` only | ✓ |

All pre-conditions satisfied; no ABORT. Evidence: SOURCE CODE.

---

## §3. Reference Audit Table

A read-only audit extracted every `*.md` token from the spec and resolved it against three
locations: repo root, `docs/reports/`, and `SoftEtherClient/`.

**Summary:** 44 unique filenames → **38** moved-only under `docs/reports/`, **4** at root,
**2** under `SoftEtherClient/` (plus `README.md`, which exists at all three and resolves at
root). **No filename is unresolvable.**

### 3a. Files that live only under `docs/reports/` (38)

`AUDIT_AUTOCONNECT_LOGIC.md`, `AUDIT_AXES_A_B_C_v1.0.11.md`,
`AUDIT_SECURITY_AND_RISK_B_v1.0.14.md`, `BATCH_DEVICE_VERIFY_REPORT.md`,
`CLOSE_OUT_v1.0.18_REPORT.md`, `COMMIT_BATCH_F28AL_AUDIT_REPORT.md`,
`COMMIT_BATCH_F28AL_GROUP2_REPORT.md`, `COMMIT_BATCH_F28AL_REPAIR_REPORT.md`,
`COMMIT_BATCH_F28AL_SINGLE_STACK_REPORT.md`, `COMMIT_PART_A_REPORT.md`,
`DEVICE_VERIFY_AUTOCONNECT_REPORT.md`, `DEVICE_VERIFY_HARDENING_AUTO_REPORT.md`,
`DOCS_UPDATE_v1.0.10_REPORT.md`, `DOCS_UPDATE_v1.0.11_REPORT.md`,
`DOCS_UPDATE_v1.0.17_REPORT.md`, `EMERGENCY_SOURCES_CSV_REPORT.md`,
`F28AL_AUTOCONNECT_BUG_DIAG_REPORT.md`, `FIX_D2_REPORT.md`, `FIX_G1_IMPL_REPORT.md`,
`FIX_HANGING_SKIP_TEST_REPORT.md`, `FIX_KOTLIN_BRIDGE_DECLARATIONS_REPORT.md`,
`FIX_LOG_IDENTITY_REPORT.md`, `FIX_N05_TEST_DEREFERENCE_REPORT.md`,
`FIX_NATIVE_P0_BOUNDS_REPORT.md`, `FIX_U36_FULL_BUILD_VERIFY_REPORT.md`,
`FIX_U36_KSP_ALIGN_REPORT.md`, `IMPLEMENT_P1_REMAINING_REPORT.md`,
`INVESTIGATE_UNKNOWN_NATIVE_CHANGES_REPORT.md`, `MANUAL_IMMEDIATE_VISIBILITY_REPORT.md`,
`MANUAL_UI_FIX_REPORT.md`, `PRE_DEVICE_BUDGET_GUARD_REPORT.md`, `RECONCILE_REPORT.md`,
`RESUME_BATCH_DEVICE_VERIFY_REPORT.md`, `VERIFY_STACK_CLOSURE_REPORT.md`,
`Z1_IMPL_B_REPORT.md`, `Z1_IMPL_C_REPORT.md`, `Z1_IMPL_D_REPORT.md`, `Z1_IMPL_G_REPORT.md`.

### 3b. Files at the repo root (4)

`AGENTS.md`, `PROJECT_SPECIFICATION.md`, `README.md`, `SERVER_SOURCE_ARCHITECTURE.md`.

### 3c. Files under `SoftEtherClient/` (2)

`IMPLEMENTATION_PLAN.md`, `RUDP_IMPLEMENTATION_PLAN.md` (both cited in §19 with the
`SoftEtherClient/` prefix).

### 3d. Tokenizer artifacts (not real references)

`_REPORT.md` — a fragment of the glob `*_REPORT.md` written in §1.6.22 bullet 1 (the `*` is not
a filename character, so the regex captured the tail). `SPEC_INTEGRITY_AUDIT_v1.0.30_REPORT.md`
— this report, created in this phase. Neither is a broken reference.

---

## §4. Path Updates Applied (19)

All updates are **path-only** (`X.md` → `docs/reports/X.md`); no prose, no evidence label, no
other text changed. Confined to the authorized regions.

### §1.6.2–§1.6.21 — 14 updates

| # | Line (new) | Section | Reference |
|---|-----------|---------|-----------|
| 1 | 423 | §1.6.4 | `AUDIT_AUTOCONNECT_LOGIC.md` |
| 2 | 508 | §1.6.5 | `INVESTIGATE_UNKNOWN_NATIVE_CHANGES_REPORT.md` |
| 3 | 521 | §1.6.5 | `PRE_DEVICE_BUDGET_GUARD_REPORT.md` |
| 4 | 530 | §1.6.5 | `DEVICE_VERIFY_HARDENING_AUTO_REPORT.md` |
| 5 | 544 | §1.6.5 | `AUDIT_AXES_A_B_C_v1.0.11.md` |
| 6 | 547 | §1.6.5 | `AUDIT_AXES_A_B_C_v1.0.11.md` |
| 7 | 613 | §1.6.7 | `AUDIT_SECURITY_AND_RISK_B_v1.0.14.md` |
| 8 | 730 | §1.6.9 | `AUDIT_SECURITY_AND_RISK_B_v1.0.14.md` |
| 9 | 931 | §1.6.11 | `IMPLEMENT_P1_REMAINING_REPORT.md` |
| 10 | 955 | §1.6.12 | `CLOSE_OUT_v1.0.18_REPORT.md` |
| 11 | 956 | §1.6.12 | `CLOSE_OUT_v1.0.18_REPORT.md` |
| 12 | 961 | §1.6.12 | `DOCS_UPDATE_v1.0.17_REPORT.md` |
| 13 | 983 | §1.6.12 | `DOCS_UPDATE_v1.0.17_REPORT.md` |
| 14 | 1688 | §1.6.20 | `FIX_D2_REPORT.md` |

### §12.5.1 — 5 updates

| # | Line (new) | Reference |
|---|-----------|-----------|
| 15 | 3230 | `AUDIT_SECURITY_AND_RISK_B_v1.0.14.md` |
| 16 | 3235 | `AUDIT_AXES_A_B_C_v1.0.11.md` |
| 17 | 3236 | `AUDIT_AXES_A_B_C_v1.0.11.md` |
| 18 | 3242 | `COMMIT_PART_A_REPORT.md` |
| 19 | 3242 | `IMPLEMENT_P1_REMAINING_REPORT.md` |

**Verification:** `git diff --stat` = **86 insertions / 22 deletions** (19 path pairs + header
+ §1.6.1 append + §1.6.22 insert + 2 §20 rows); `git diff -U0 | grep '^@@'` shows hunks only at
lines 7, 343, 407/492/505/514/528/531/597/714/915/939/945/967/1672, 1760, 3168/3173/3180 (all
< §22 at 3705). Evidence: SOURCE CODE.

### §4b. Remaining bare references (46) — recorded, not edited

These are outside the authorized edit regions and were **not** touched; each still resolves via
`docs/reports/` (or `SoftEtherClient/`):

| Region | Occurrences | Why not edited |
|--------|:-----------:|----------------|
| §1.6.1 existing push-state notes | 11 | E2 = append only ("do not modify existing") |
| §3.1 (F.28 stage table) | 15 | outside authorized regions |
| §6.7 (AutoConnect contract) | 1 | outside authorized regions |
| §12.4 (FINDING T5-SLOT) | 2 | §12.5.x only is authorized, not §12.4 |
| §20 Changelog rows | 12 | E6 = add row only |
| §20 Verification rows | 4 | E7 = add row only |
| §1.6.22 (own prose) | 1 | explanatory prose, not a reference |
| **Total** | **46** | |

Evidence: SOURCE CODE.

---

## §5. `HttpFetcher.kt` Decision + Diff

**Decision: COMMIT** (the diff is comment-only, harmless, and belongs to the routing work
already covered by §1.6.20 / §8.1).

```diff
@@ -281,8 +281,10 @@ class OkHttpFetcher(
 // SERVER_SOURCE_ARCHITECTURE.md §7 Q2 requires per-request binding via
 // `Network.getSocketFactory()`, never `bindProcessToNetwork()`. §7 Q3's
 // "open → VPN fallback → emergency → manual" ordering is implemented only
-// in its first two rungs here: open stays the default, the VPN rung is an
-// opt-in, and emergency/manual sources are out of scope for this phase.
+// in its first two rungs here: open stays the default, the VPN rung is
+// auto-selected when a usable tunnel exists (see
+// `VpnServerRepository.productionRouting`) and falls back to open, and
+// emergency/manual sources are out of scope for this phase.
```

Every changed line is a `//` comment (verified: no non-comment `+`/`-` line). The cited
`productionRouting` exists at `VpnServerRepository.kt:112`. Committed as **`9d569c3`**
(`chore(sources): commit pending HttpFetcher doc-comment change`), 1 file, +4/−2. The working
tree is now free of tracked modifications. Evidence: SOURCE CODE.

---

## §6. The 1 Skipped `:app` Test

- **Class / method:** `vn.unlimit.vpngate.collector.HtmlParserOracleTest` →
  `softetherUdpOnlyNeverBorrowsOpenvpnTcp`
- **Reason:** `org.junit.AssumptionViolatedException: no SE-UDP-only + OVPN-TCP row in current snapshot`
- **Nature:** a **data-dependent assumption skip**, not a failure and not a regression. The test
  only runs when the current server snapshot contains a SoftEther-UDP-only + OpenVPN-TCP row; the
  `790/0/0/1` (post-reconcile) vs `790/0/0/0` (FIX-D-2) difference is therefore expected and
  snapshot-dependent, not a code change. Evidence: TEST
  (`app/build/test-results/testProDebugUnitTest/TEST-…HtmlParserOracleTest.xml`).

---

## §7. Concurrent-Worktree Incident Summary

Two sessions edited the same working tree during the v1.0.28/v1.0.29 window:

- **Main session** produced `df7b49c` (D-1b closure), `0f5f307` (device report), `82cb64f`
  (spec v1.0.28 + `RECONCILE_REPORT.md`).
- **Concurrent session** produced `82476bf` (spec v1.0.29 / architecture / README repair) and
  `a76bdc6` (archive root reports → `docs/reports/`).

Because both staged from the same tree, `82cb64f` swept in partial v1.0.29 §1.3/§15.3 content
that `82476bf` later superseded. **No source code was contaminated** — the overlap was
documentation-only — but the docs history is noisy. Recorded as a process lesson in §1.6.22
bullet 2 (event) and bullet 3 (rule: one owner per working tree; a second session must use a
separate clone/worktree). Evidence: SOURCE CODE (`git diff 0f5f307..82cb64f`).

---

## §8. Spec Edits E1–E8

| Edit | Applied | Notes |
|------|---------|-------|
| E1 — header | ✅ | version 1.0.29 → **1.0.30**; HEAD → `9d569c37…` (pre-docs commit); date 2026-10-18 |
| E2 — §1.6.1 push-state note | ✅ | appended a "Push state (v1.0.30)" note (19 updates, 46 remaining, skip identity, HttpFetcher commit); existing notes untouched |
| E3 — §1.6.2–§1.6.21 path refs | ✅ | 14 path-only updates; no other text changed |
| E4 — new §1.6.22 | ✅ | 6 bullets: relocation/19 updates, concurrent-worktree event, single-owner rule, HttpFetcher commit, skip identity, §22 untouched |
| E5 — §12.5.1/§12.5.2 path refs | ✅ | 5 path-only updates in §12.5.1; §12.5.2 had none |
| E6 — §20 changelog | ✅ | 1.0.30 row added |
| E7 — §20 verification | ✅ | §1.6.22 row added (HISTORICAL) |
| E8 — §22 | ✅ | **untouched**; byte-identical (99 / 6,504) |

Evidence: SOURCE CODE.

---

## §9. §22 Byte-Identity

`§22` measured from `## 22. Build and Verification Methodology` to EOF:
**99 lines / 6,504 bytes** — identical to the frozen baseline. No hunk reaches §22 (last diff
hunk at line 3621→3685; §22 starts at 3705). Evidence: SOURCE CODE.

---

## §10. JVM Tests

| Module | Task | Classes | Tests | Failures | Errors | Skipped |
|--------|------|:-------:|:-----:|:--------:|:------:|:-------:|
| `:app` | `testProDebugUnitTest` | 60 | **790** | 0 | 0 | **1** |
| `:SoftEtherClient` | `test` | 11 | **173** | 0 | 0 | 0 |

Both `EXIT=0` (`BUILD SUCCESSFUL in 8m 24s`, 86 tasks, `--rerun-tasks --no-build-cache`). This
matches the post-reconcile baseline (790/0/0/1 and 173/0/0/0) — **no regression**. Evidence: TEST.

---

## §11. Diff-Scope Confirmation

`git status --porcelain=v1` (tracked) = `M PROJECT_SPECIFICATION.md` **only**. `git diff --stat`
= `PROJECT_SPECIFICATION.md | 86 +, 22 -`. The new report is untracked until the docs commit.
`HttpFetcher.kt` is already committed (`9d569c3`) and no longer dirty. No source, test, build,
schema, or manifest file changed. Evidence: SOURCE CODE.

---

## §12. Negative Assurance

- **No adb / device command** was run.
- **No tag operation** of any kind.
- **No `--force` / `reset` / `clean` / `stash` / `checkout` / `restore`.**
- **No `git add -A` / `git add .`** (staged by exact path only).
- **No `*.md` file was deleted or moved** (the `docs/reports/` move was the pre-existing
  `a76bdc6`; this phase only edited path strings).
- The cleanup folder `C:\Dev\SoftEthergai_cleanup_2026-10-18` (~1.1 GB) was **not touched**.
- `D:\PMC\Documents\Android\SoftEthergai` was **not touched**.
- **No C5 execution.**
- §22 untouched; §1.6.2–§1.6.21 content unchanged except the 14 authorized path strings.

---

## §13. Recommendations (proposed only — not executed)

1. **Optional path sweep (future phase):** update the 46 remaining bare references (chiefly
   §3.1's 15 and the §20 historical rows' 16) to `docs/reports/…` if the operator wants every
   reference to be a clickable path. This phase deliberately left them (out of scope).
2. **`RUDP_IMPLEMENTATION_PLAN.md` / `IMPLEMENTATION_PLAN.md`:** consider prefixing the bare
   §12.5.1 reference with `SoftEtherClient/` in a future pass (pre-existing imprecision, not a
   `docs/reports/` artifact).
3. **Working-tree ownership:** enforce the single-owner rule from §1.6.22 bullet 3 in future
   concurrent sessions (separate clone or `git worktree`).
4. **Skip stability:** if `HtmlParserOracleTest.softetherUdpOnlyNeverBorrowsOpenvpnTcp` should
   always run, pin its fixture rather than relying on the live snapshot; otherwise the
   data-dependent skip is correct as-is.

Evidence: INFERENCE (recommendations only).
