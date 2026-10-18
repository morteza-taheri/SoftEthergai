# DOCS_UPDATE_v1.0.17_REPORT — PROJECT_SPECIFICATION 1.0.16 → 1.0.17

**Document version:** 1.0.0
**Date:** 2026-10-15
**Phase:** COMMIT-PART-A-AND-DOCS-v1.0.17
**Spec before:** v1.0.16, HEAD `71edd67935371450b82e5eb162a0d8bf05da13d6`
**Spec after:** v1.0.17, HEAD `a7c449fa19455204953bba5d92185b45debdc39a`
**Phase type:** documentation only, after a single source commit. **Device-free.**
**Files changed:** `PROJECT_SPECIFICATION.md` only (plus this report).

---

## 1. Executive verdict

The specification is advanced from **1.0.16** to **1.0.17**. The substantive content of the revision is
the landing of **PART A** (Axis A — tunnel state / UI sync), which was committed at the start of this
phase as `067d4a3` and recorded by `a7c449f`. PARTs B, C and D were found already landed and are
recorded as such, not re-landed.

The single most important editorial decision in this revision is that the **ANR is recorded as an open
item rather than absorbed into a caveat**. It is written into three places (new §1.6.10 bullet 7, new
§12.5.1 row 16, and the §20 changelog/verification-status rows), each with the same evidence labels and
the same negative assurance, so that no reader can encounter PART A's "PARTIAL" status without also
encountering the reason.

| Check | Verdict |
|-------|---------|
| §1.6.1 chain reconciliation | **PASS** — delta 0 at `HEAD = a7c449f` |
| Hunk scope vs §1.6.2–§1.6.9 | **PASS** — no hunk inside the protected range |
| §22 byte-identity | **PASS** — IDENTICAL, 99 lines |
| Header E1 | **PASS** — version 1.0.17, HEAD `a7c449f`, `Last updated` 2026-10-15 |
| §12.5.1 row count | **PASS** — 16 rows (15 → 16) |
| Markdown structure | **PASS** — headings, tables and separators intact |
| Device-free | **PASS** — no `adb` invocation |
| Tag operation | **NONE** — report only (§7) |

---

## 2. Edits performed

| ID | Edit | Location | Evidence |
|----|------|----------|----------|
| E1 | `Document version` 1.0.16 → 1.0.17; `HEAD` `e6b9eef…` → `a7c449fa19455204953bba5d92185b45debdc39a`; `Last updated` refreshed | header | git |
| E2a | Four new rows appended to the §1.6.1 commit-chain table: `8edaee8`, `71edd67`, `067d4a3`, `a7c449f` | §1.6.1 | git |
| E2b | New "Push state (v1.0.17)" note recording the delta-0 verification and naming this revision's own two commits by subject | §1.6.1 | git |
| E3 | New **§1.6.10** — "PART A (Axis A) Landed, with an Explicit ANR Caveat (v1.0.17)", 10 evidence-labelled bullets | §1.6 | SOURCE CODE + git |
| E4a | §12.5.1 **row 6** (RISK-A) — status `OPEN` → **PARTIALLY MITIGATED (v1.0.17)** with PART D's guard probe | §12.5.1 | SOURCE CODE + TEST |
| E4b | §12.5.1 **row 10** (Axis A/B/C audit register) — status `OPEN` → **PARTIALLY CLOSED (v1.0.17)** | §12.5.1 | SOURCE CODE + TEST |
| E4c | §12.5.1 **row 16** (new) — FINDING ANR-2026-10-15, OPEN — UNKNOWN | §12.5.1 | PHYSICAL DEVICE + UNKNOWN |
| E5 | §20 changelog — new **1.0.17** row | §20 | — |
| E6 | §20 verification status — new **§1.6.10** row | §20 | — |
| E7 | §22 untouched | §22 | **byte-identical** |

### 2.1 E4 — why rows 6 and 10, and what "note on row 6" means

The brief specified a new row for the ANR finding plus a note on an existing row. The ANR finding is
**row 16**. The note on **row 6** is the RISK-A row: Phase 12's PART D probe
(`:app:assembleProRelease` → exit 1 with the intended `GradleException` at `app/build.gradle:109`) is
the first *live* confirmation that the fail-closed release-signing guard actually fires, so row 6's
status is upgraded from `OPEN` to `PARTIALLY MITIGATED` while keeping the build-availability half open
(the local keystore and `SIGN_KEY_PASSWORD` are still absent).

Row 10 is additionally annotated because it is the register that PART A partially discharges: Axis A is
now closed by `067d4a3`, Axis B by `a9e16fd`, Axis C by `607c651`. Leaving row 10 reading plain `OPEN`
would have made §1.6.10 and §12.5.1 contradict each other.

---

## 3. §1.6.10 content summary

Ten bullets, each carrying an evidence label:

1. **A-2** — `onLost` re-check budget 500 ms → 50 ms via `VPN_LOST_RECHECK_MS`; **reduced, not removed**,
   because `onLost` precedes `onAvailable` and the re-check is what absorbs a network handover.
2. **A-3** — four protocol-name "egates" replaced by the pure stateless fold `VpnStackLedger.reconcile`;
   on losing X with a survivor Y, Y is republished as CONNECTED **from Y's own identity**; the
   `sstpListener:280` egate deliberately retained as provably equivalent and test-pinned.
3. **A-4** — the three armed loss paths funnelled through `reportArmedTunnelLoss()`; detection latencies
   deliberately **not** unified.
4. **A-5** — `Disconnected` published only after the OQ-7/OQ-6/OQ-1 decision.
5. **Tests added** — 24 (13 `VpnStackLedgerTest`, +5 `GlobalVpnTrackerTest`, +6 `AutoModeControllerTest`).
6. **Verification status — PARTIAL**, with the JVM/build figures labelled **PRIOR REPORT** (this revision
   re-ran nothing).
7. **The ANR finding** — unattributed, **NOT REPRODUCED**, with the negative assurance and two clean
   controls; blocked frame **UNKNOWN**; most probable explanation labelled **INFERENCE**.
8. **PARTs B, C, D already landed** — `a9e16fd`, `607c651`, `c572050`, plus PART D's live probe.
9. **Discipline of this revision** — no `git add -A`, no force, no tag, no build/test/device command, C5
   not executed, §22 byte-unchanged.
10. **Methodology lessons** — none new in v1.0.17.

---

## 4. Verification detail

### 4.1 §1.6.1 chain reconciliation (E2, delta 0)

```
git log --oneline 0714628^..a7c449f | wc -l   =  57
PROJECT_SPECIFICATION.md §1.6.1 table rows     =  57
delta                                         =   0
```

Measured at `HEAD = a7c449f`, i.e. **before** this revision's own two commits exist. The four rows added
are `8edaee8` (v1.0.16 spec commit, previously deferred), `71edd67` (v1.0.16 chain-count clarification,
previously unlisted), `067d4a3` (PART A) and `a7c449f` (PART A report). This revision's own spec commit
and report commit are **named by subject** in the new push-state note and will be listed by hash in a
future revision, per the established convention.

### 4.2 Hunk scope vs §1.6.2–§1.6.9 (E3, E7)

Protected range in the **pre-edit** file: lines **208–644** (from `#### 1.6.2` through the last content
line of §1.6.9). The nine hunks, by old-line anchor:

| Hunk | Old anchor | Inside 208–644? | Edit |
|------|-----------|-----------------|------|
| `-7,2` | 7–8 | no | E1 |
| `-161,0` | 161 | no | E2a |
| `-206,0` | 206 | no | E2b |
| `-644,0` | 644 | no — insertion **after** §1.6.9's final content line | E3 |
| `-1966` | 1966 | no | E4a |
| `-1970` | 1970 | no | E4b |
| `-1975,0` | 1975 | no | E4c |
| `-2313,0` | 2313 | no | E5 |
| `-2354,0` | 2354 | no | E6 |

**Result: PASS.** No hunk deletes or replaces a line in §1.6.2–§1.6.9; the only hunk at the §1.6.9
boundary (`-644,0`) is a pure 97-line insertion, so §1.6.9's own text is untouched.

### 4.3 §22 byte-identity (E7)

```
git show HEAD:PROJECT_SPECIFICATION.md | sed -n '/^## 22\. Build and Verification Methodology/,$p'
  vs
sed -n '/^## 22\. Build and Verification Methodology/,$p' PROJECT_SPECIFICATION.md
→ IDENTICAL (99 lines)
```

---

## 5. Negative assurance

- No **source** file (Kotlin / C / C++ / Java), no **test** file, no **manifest**, no **XML**, no
  **build** file was modified in this revision.
- No **PART B / C / D** file was touched.
- No existing report was modified; `COMMIT_PART_A_REPORT.md` (created earlier in this phase) is
  unchanged by this revision.
- No build, test, or **device** command was run. No `adb` invocation was made; the device was DETACHED.
- No `--force`, no `reset`, no `clean`, no `stash`, no **tag** creation, push, or deletion.
- **C5 was not executed.**
- `D:/PMC/Documents/Android/SoftEthergai` was not touched.
- §22 is byte-identical (see §4.3).

---

## 6. Recommendation

1. **Push the four-commit chain as one fast-forward** (STEP 6) — it is currently local-only and
   `origin/main` is at `71edd67`, 4 behind.
2. **Defer the tag decision** (§7). Nothing in this phase authorises a tag operation, and the one
   plausible candidate points at a commit that is not a release cut.
3. **Carry the ANR as a tracked open item** (§12.5.1 row 16) into the next device-bearing phase; it is
   the only reason PART A's status is not PASS.

---

## 7. Tag decision report — REPORT ONLY, no tag operation performed

Executed read-only. **No tag was created, moved, deleted, or pushed.**

```
git tag -l                      → p2.4.10  p2.4.5  p2.4.7  p2.4.8  p2.4.9
git rev-parse p2.4.10           → c101380672e353321cd2c2bf820a49e8e71cba6a   (annotated tag object)
git log -1 p2.4.10              → 071462816b1eef7e7f7459483fcd69909938cd11
                                  2026-09-28 17:15:36  feat(ui): add DNS presets, app exception
                                  icons and Auto Mode cleanup
git ls-remote --tags origin     → p2.4.5, p2.4.7, p2.4.8, p2.4.9   (each annotated: tag + ^{} )
git ls-remote --tags origin p2.4.10 → empty (retried 3×; full list also 0 matches)
git tag --merged origin/main    → all five local tags (all point at ancestors of origin/main)
```

**Finding: `p2.4.10` is LOCAL-ONLY.** It exists in the local repository and has **never** been pushed;
the remote knows only `p2.4.5`, `p2.4.7`, `p2.4.8` and `p2.4.9`. This has been true since the v1.0.7 era
(the §1.6.3 note records the same observation at `HEAD 5e51e37`), so it is a long-standing condition and
**not** an artifact of this phase.

**Recommendation: DO NOT push `p2.4.10`.** Three reasons, in order of weight:

1. **It points at the wrong commit.** `p2.4.10` resolves to `0714628` — the *first* commit of the
   recorded chain, whose subject is a UI/cleanup change ("feat(ui): add DNS presets, app exception icons
   and Auto Mode cleanup"). A tag named for product version 2.4.10 that annotates an unrelated UI commit
   would be misleading to anyone using tags for release provenance.
2. **Pushing it is outside this phase's authorization.** The brief restricted tags to a *report*.
3. **Consequence if it were pushed:** it would become the newest remote tag and would appear to mark
   `0714628` as the 2.4.10 release point, which the RISK-A record (§12.4) shows is precisely the commit
   range whose installed artifact was **debug-signed** (2.4.10/143 and 2.4.10-dev). Publishing that as a
   release tag would compound a known signing defect.

**If a tag is later authorized:** create an **annotated** tag (matching the four remote tags, all of
which are annotated) at a commit that is a genuine release cut — *not* `0714628` — or explicitly record
`p2.4.10` as historical and leave it unpushed.

---

*End of report.*
