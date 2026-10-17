# Z1-IMPL-G REPORT — Dataset-Integrity Outcomes + MANUAL Provenance Fix (v1.0.23)

**Roadmap §12.5.2 item G** — the provenance half of it in full, the outcomes half as a
classification. **Repository:** `C:\Dev\SoftEthergai` · **Branch:** `main` · **Device:** DETACHED
(device-free phase)

---

## 1. Verdict

Item G landed in the two parts the operator approved: (i) the MANUAL provenance debt recorded as
limitation (c) of the Z1-IMPL-D report is **paid**, and (ii) the three `DatasetIntegrity` outcomes are
implemented as a **classification layered on the existing verdict** — `ACCEPT_NEW`,
`MERGE_WITH_EXISTING`, `REJECT_DEGRADED` — with `evaluate`'s signature and all seven thresholds
unchanged, so **no acceptance or rejection decision moved**.

Two corrections to the phase brief are recorded rather than applied silently, because the brief's own
stop condition demanded it:

1. **The citation was wrong.** The brief named `SERVER_SOURCE_ARCHITECTURE.md` **§3.12** as the home of
   the three outcomes. §3.12 does not define them; the vocabulary lives in **§9.6** and **§10.8**, and
   §9.1 is what fixes what the first two *mean*.
2. **The decision table contradicted the arch doc on two rows.** The brief classified a **strict
   subset** and a **disjoint** candidate as `REJECT_DEGRADED`; §9.1 says a shrunken response is
   `MERGE_WITH_EXISTING`, "**never** as a signal to prune previously-seen healthy records", and §9.6's
   closing sentence says the outcomes "layer on top of the existing `DatasetIntegrity` threshold logic
   **rather than replacing it**". The operator chose the arch-doc reading.

`SourceIdentity.sourceTag`'s `when` is now exhaustive with no `else`, so `SourceKind.MANUAL` yields
`MANUAL_TAG = "manual"` instead of `mirror_unnumbered`. This was not a cosmetic fix — see §4.

Three commits, one fast-forward push. No device verification of any kind.

---

## 2. Pre-conditions (all PASS)

| Check | Required | Observed |
|---|---|---|
| `HEAD` == `origin/main` | `40decde35c2fc0213785662a5f899f2996f0587c` | `40decde…` both — PASS |
| Document version | 1.0.22 | 1.0.22 — PASS |
| `:app:testProDebugUnitTest` baseline | 747 / 0 / 0 / 1 | 747 / 0 / 0 / 1 — PASS |
| `:SoftEtherClient:test` baseline | 173 / 0 / 0 / 0 | 173 / 0 / 0 / 0 — PASS |
| APK baseline | SHA-256 `f1d768c9…` | 65,978,641 bytes — PASS |
| Tracked modifications at start | zero | zero — PASS |
| Tags | 5 local / 5 remote | 5 / 5 — PASS |
| Chain length | `0714628^..HEAD` | 72 — PASS |
| `build.gradle` | frozen | untouched — PASS |

Environment note carried over from v1.0.20: `app/build.gradle`'s `androidComponents` block disables all
debug variants unless `CI` is set, so **every** Gradle invocation in this revision ran with `CI=true`.
`JAVA_HOME` is the Android Studio JBR (JBR 25); the PATH `java` is 1.8 and cannot compile this project.

---

## 3. §11.11 mapping

§11.11's principles and what satisfies each of them in this revision:

| §11.11 principle | Status in this revision |
|---|---|
| "Manual servers must survive ordinary online server-list updates" | Unchanged from item D — `withManualRows` in `ServerListUpdates.replaceAll`. No edit here. |
| "Emergency-source records must not be accidentally treated as built-in-source records" | Unchanged (item C's `EMERGENCY_TAG`). |
| **"Preserve source provenance … must carry distinct, retrievable provenance"** | **Satisfied end-to-end for manual by the fix in §4**: a manual record is now grouped `manual`, not an unidentified mirror. |
| "Deduplicate records without discarding … source history" | Unchanged; the `sources` list still accumulates `api` + `manual` for a colliding server (tested). |
| "A record missing from one partial source response must not automatically be deleted" | Unchanged. Note the outcome names do **not** change this: `MERGE_WITH_EXISTING` is a *classification*, and the add-only behaviour it names is Retention (§9.1), which is still not implemented — recorded in §14 limitation 1. |
| "A failed fetch … must never trigger destructive replacement" | Unchanged; `evaluate`'s thresholds are byte-for-byte identical, so the refusal behaviour is exactly what it was. |
| "Do not weaken existing protections for healthy historical servers" | Strongest single line of evidence for this revision: all 747 pre-existing tests pass unchanged, including every `ServerListUpdater` case, and `evaluate`'s signature is untouched. |

---

## 4. The MANUAL provenance fix — and why a mirror tag was an authority defect

### 4.1 What was wrong

`SourceIdentity.sourceTag` reached the mirror kinds through an `else` branch:

```kotlin
// before (two revisions, v1.0.20 → v1.0.22)
when (kind) {
    SourceKind.CANONICAL_HTML -> HTML_TAG
    SourceKind.CANONICAL_API -> API_TAG
    SourceKind.EMERGENCY_CSV -> EMERGENCY_TAG
    else -> mirrorId ?: UNNUMBERED_MIRROR_TAG     // MANUAL landed here
}
```

`SourceKind.MANUAL` has no mirror id, so the only reachable value was `mirror_unnumbered`.

### 4.2 Why that mattered

`Provenance.sourceGroup` is the function that decides which provenance group a tag belongs to, and
`VpnServerMerger.sourcePriority` resolves a *group*:

```kotlin
private val MIRROR_GROUP_RE = Regex("^mirror_\\d+$", RegexOption.IGNORE_CASE)

fun sourceGroup(source: String): String {
    val name = source.trim().lowercase()
    return if (MIRROR_GROUP_RE.matches(name)) "mirror" else name
}
// → "mirror_7"  ⇒ "mirror"          (absent from SOURCE_PRIORITY ⇒ default 20)
// → "mirror_unnumbered" ⇒ itself    (absent from SOURCE_PRIORITY ⇒ default 20)
```

Neither spelling is a key of `VpnServerMerger.SOURCE_PRIORITY` (`api` 0, `html` 10, `manual` 30), so
both fall through to the default **20**. A manual record therefore carried *mirror-level* authority —
ranked **above** the manual level 30 that item D had just landed and documented as "inert until a
manual record reaches this object". It never reached it: no record labelled `manual` was ever
produced, so the priority table entry was **dead code**, not a live policy.

The provenance was also hidden. `SERVER_SOURCE_ARCHITECTURE.md` §3.3 requires manual to have a
distinct, non-canonical tag, and §4.2 requires it be "never promoted above canonical" — a mirror tag
does not break the second constraint, but it does make the first unverifiable and puts a user's own
entry in the mirror group, where an operator debugging a wrong value cannot tell it from a crawl.

### 4.3 The fix

```kotlin
// after
val sourceTag: String
    get() = when (kind) {
        SourceKind.CANONICAL_HTML -> HTML_TAG
        SourceKind.CANONICAL_API -> API_TAG
        SourceKind.EMERGENCY_CSV -> EMERGENCY_TAG
        SourceKind.MANUAL -> MANUAL_TAG
        SourceKind.BOOTSTRAP_MIRROR,
        SourceKind.DISCOVERED_MIRROR,
        SourceKind.CACHED_MIRROR,
        -> mirrorId ?: UNNUMBERED_MIRROR_TAG
    }
```

plus `const val MANUAL_TAG = "manual"` in the companion. The **absence of an `else`** is the part worth
stating: before this, adding a `SourceKind` without giving it a tag compiled clean and mislabelled
records at runtime — exactly how `MANUAL` read as a mirror for two whole revisions. Now it fails to
build instead. That is a compile-time property, not an observed one, and is labelled **INFERENCE**.

### 4.4 What the tests pin

`ManualProvenanceTest` (10 tests, all passing):

1. `sourceTag(MANUAL) == "manual"`, and never `mirror_unnumbered`, and never a `mirror_`-prefixed tag.
2. The baseline tag of **every other kind** is asserted — `html`, `api`, `emergency`, `mirror_7` and
   `mirror_unnumbered` for each of `BOOTSTRAP_MIRROR` / `DISCOVERED_MIRROR` / `CACHED_MIRROR` — so
   adding the manual branch cannot silently re-route one of them.
3. `Provenance.sourceGroup("manual") == "manual"` while both mirror spellings stay outside the group
   that is in the priority table, and `sourcePriority("manual") == 30 > 20`.
4. `MIRROR_TAG_PREFIX + (index + 1) != MANUAL_TAG` for indices 1..25 — ids are prefixed at the point
   of assignment (`VpnGateSourceCandidates.MIRROR_TAG_PREFIX`), so a mirror can never share the manual
   provenance group.
5. A manual identity carrying a **stray** mirror id (reachable through the public `withMirrorId` copy)
   still reads `manual` — the manual branch owns the decision regardless of state it should not hold.
6. Record-layer consequences: a manual record merged against a canonical one keeps `manual` in
   `sources` (and gains `api`, order `["api","manual"]` because `sourceAdd` sorts); one with no
   collected counterpart has `manual` as its only source and no `conflicts`; and the provenance
   survives three consecutive refresh cycles.

---

## 5. The three outcomes as a classification

### 5.1 What landed

```kotlin
enum class DatasetOutcome { ACCEPT_NEW, MERGE_WITH_EXISTING, REJECT_DEGRADED }

private fun hasComparableBaseline(stored: List<VPNGateItem>): Boolean =
    stored.size >= MIN_ACCEPTABLE_DATASET_SIZE

fun outcome(verdict: DatasetVerdict, stored: List<VPNGateItem>): DatasetOutcome = when {
    !verdict.accepted -> DatasetOutcome.REJECT_DEGRADED
    !hasComparableBaseline(stored) -> DatasetOutcome.ACCEPT_NEW
    else -> DatasetOutcome.MERGE_WITH_EXISTING
}
```

`evaluate`'s signature and all seven thresholds (`MIN_ACCEPTABLE_DATASET_SIZE` 10,
`MIN_RELATIVE_DATASET_RATIO` 0.5, `MIN_PROTOCOL_COVERAGE_RATIO` 0.5 /
`MIN_PROTOCOL_COVERAGE_BASELINE` 20, `MIN_PROFILE_COVERAGE_RATIO` 0.5 /
`MIN_PROFILE_COVERAGE_BASELINE` 20, and `DatasetDefect`) are **byte-for-byte unchanged**. The rule is
a pure function of data `evaluate` already produced, which is what makes layering it safe: an outcome
can never contradict its verdict, and that is itself a test.

### 5.2 Which outcomes are real and which are names

- **`REJECT_DEGRADED` is implemented in substance.** It is exactly `verdict.accepted == false`, the
  condition `ServerListUpdater` already acted on as `Preserved(DEGRADED_DATASET)`.
- **`ACCEPT_NEW` and `MERGE_WITH_EXISTING` are names only in this phase.** Arch doc §9.1 defines them
  as cumulative **addition** — "the database **grows** across updates rather than being **replaced**"
  — and Cumulative Server Retention is not implemented and is outside this phase's authorized file
  set. An update still replaces. Giving the names a behaviour they do not have would be a claim the
  code does not make, so the KDoc says so plainly and §11.11 keeps the gap on the record.

### 5.3 The two rows the brief got wrong

| Brief's table | Arch doc | Landed |
|---|---|---|
| strict subset of a healthy stored set → `REJECT_DEGRADED` | §9.1: a shrunken response is `MERGE_WITH_EXISTING`, "never a signal to prune previously-seen healthy records" | `MERGE_WITH_EXISTING` (25 of 40, above the 0.5 floor, is accepted by the **unchanged** thresholds) |
| disjoint candidate → `REJECT_DEGRADED` | §9.1/§9.6; whether its records are *added* is Retention's question, not this class's | `MERGE_WITH_EXISTING` |

What remains `REJECT_DEGRADED` is the dataset that collapses below the floor — `SIZE_COLLAPSE`
(10 of 40) or `TOO_SMALL` (3 rows) or `PROTOCOL_COLLAPSE` — exactly as before.

### 5.4 The wire-in records; it does not branch

```kotlin
override fun replaceAll(items: List<VPNGateItem>) {
    val stored = currentDataset()
    val verdict = DatasetIntegrity.evaluate(items, stored, capability.value)
    CollectorLog.d(
        "Dataset outcome ${DatasetIntegrity.outcome(verdict, stored)}: " +
            "${items.size} incoming vs ${stored.size} stored (${verdict.detail})",
    )
    dao?.replaceAll(withManualRows(items, manual.rows))
}
```

`ServerListUpdater.kt`, the component that *owns* the accept/reject decision, is **not** in this
phase's authorized file set. Re-running `evaluate` inside the write boundary is safe because it is
pure and deterministic — it reproduces the updater's verdict — and logging rather than branching means
any future divergence between the two becomes visible instead of silently changing behaviour. The
classified value is never persisted and never read back.

---

## 6. Tests

`DatasetIntegrityOutcomeTest` — 11 tests, all passing:

| Case | Outcome |
|---|---|
| empty store, viable candidate | `ACCEPT_NEW` |
| 9 stored rows (below the floor), viable candidate | `ACCEPT_NEW` |
| 40 over 30 | `MERGE_WITH_EXISTING` |
| 30 over 30 | `MERGE_WITH_EXISTING` |
| **25 over 40 (the brief's REJECT case)** | `MERGE_WITH_EXISTING` |
| **disjoint 30/30 (the brief's REJECT case)** | `MERGE_WITH_EXISTING` |
| 10 over 40 | `SIZE_COLLAPSE` → `REJECT_DEGRADED` |
| 3 rows | `TOO_SMALL` → `REJECT_DEGRADED` |
| protocol collapse (30 stored with SSTP, candidate without) | `PROTOCOL_COLLAPSE` → `REJECT_DEGRADED` |
| an outcome never contradicts its verdict (7 dataset pairs) | rule |
| `ACCEPT_NEW` is exactly the no-comparable-baseline case (9 vs 10 stored rows) | boundary |

Two tests pin the *rule* rather than individual cases, so a future threshold change cannot move the
names without a failure here.

---

## 7. Build and test evidence

| Suite | Baseline | This revision | Delta |
|---|---|---|---|
| `:app:testProDebugUnitTest` (forced, `--rerun-tasks --no-build-cache`) | 747 / 0 / 0 / 1 | **768 / 0 / 0 / 1** | +21 (10 `ManualProvenanceTest` + 11 `DatasetIntegrityOutcomeTest`) |
| `:SoftEtherClient:test` (forced, `--rerun-tasks --no-build-cache`) | 173 / 0 / 0 / 0 | **173 / 0 / 0 / 0** | 0 |
| `:app:assembleProDebug` (forced, `--rerun-tasks --no-build-cache`) | SUCCESSFUL, `f1d768c9…`, 65,978,641 B | **SUCCESSFUL, `f42f78e8…`, 63,738,546 B** | — |

APK SHA-256 `f42f78e87f3dce51f7c800a9b109077330e5f08adbd713f7762c3433729e4cba`. The size and hash
differ from item D's as expected for changed code; a byte-identical APK would have been evidence the
new sources were not compiled in.

No dependency, no Gradle change, no migration, no schema change, no Room version bump (still 7, no
`app/schemas` change). Two JVM failures were found and fixed during development and are recorded for
honesty: a wrong object name in a test (`VpnGateSources` → `VpnGateSourceCandidates`), and a test that
asserted `mirror_unnumbered` groups into `mirror` — it does not, because `MIRROR_GROUP_RE` is
`^mirror_\d+$`, and that fact is itself part of §4.2's finding, so the assertion was corrected and the
explanation moved into the test.

---

## 8. Diff-scope

`git diff -U0` — all hunks, mapped against the authorized file set:

```
SourceIdentity.kt        +4 / +13 / +19 / +12            (authorized)
DatasetIntegrity.kt      +55 / +1 / +29                  (authorized)
ServerListUpdates.kt     +7 / +1 / +4 / +1 / +24         (authorized)
PROJECT_SPECIFICATION.md @@ -7,3 +7,3                    header (E1)
                        @@ -273,0 +274,14               §1.6.1 push note (E2)
                        @@ -1163,0 +1178,104            new §1.6.16 (E3)
                        @@ -2283,0 +2402,18             §11.11 annotation (E4)
                        @@ -2877,0 +3014                §20 verification row (E7)
                        @@ -2924,0 +3062                §20 changelog row (E6)
```

New files: `app/src/test/java/vn/unlimit/vpngate/data/remote/ManualProvenanceTest.kt`,
`app/src/test/java/vn/unlimit/vpngate/repository/DatasetIntegrityOutcomeTest.kt`,
`Z1_IMPL_G_REPORT.md`.

`VpnServerMerger.kt` and `ConnectionListViewModel.kt` — both "POSSIBLY" in the brief — needed **no
change**; the merger's `manual` 30 entry became live through the tag fix alone, with no edit at all.
Zero hunks outside the authorized set.

---

## 9. Spec edits (E1–E8)

| # | Edit | Result |
|---|---|---|
| E1 | Header | HEAD `40decde…`, version 1.0.23, 2026-10-17 |
| E2 | §1.6.1 push note | "Push state (v1.0.23)" appended, three commits named by subject |
| E3 | New **§1.6.16** | 10 evidence-labelled bullets (0–9) |
| E4 | §11.11 annotation | provenance closure + classification-only nature + citation correction |
| E5 | §12.5.1 / §12.5.2 | **NO changes** — verified byte-identical (53 lines / 17,187 bytes) |
| E6 | §20 changelog row | 1.0.23 row |
| E7 | §20 verification row | §1.6.16 row |
| E8 | §22 | **untouched** — verified byte-identical (100 lines / 6,450 bytes; 99 / 6,504 by the v1.0.22 report's awk method) |

---

## 10. Frozen-section identity

| Region | Old (1.0.22) | New (1.0.23) | Verdict |
|---|---|---|---|
| §1.6.2 – §1.6.14 | 793 lines / 62,477 bytes | 793 / 62,477 | **IDENTICAL** |
| §1.6.15 body | 98 lines / 7,623 bytes | 98 / 7,623 | **IDENTICAL** |
| §12.5.1 + §12.5.2 | 53 lines / 17,187 bytes | 53 / 17,187 | **IDENTICAL** |
| §22 | 100 lines / 6,450 bytes | 100 / 6,450 | **IDENTICAL** |
| §20 | 75 lines | 77 lines | DIFFERS — E6 + E7, authorized |
| §11.11 | 35 lines / 3,485 bytes | 41 lines / 5,065 bytes | DIFFERS — E4, authorized |
| §1.6.1 | 182 lines / 15,149 bytes | 196 / 16,362 | DIFFERS — E2, authorized |
| Header | 92 lines | 92 (same length) | DIFFERS — E1, authorized |

---

## 11. Commit sequence

| # | Subject | Files |
|---|---|---|
| 1 | `feat(sources): MANUAL provenance tag in SourceIdentity + provenance tests` | `SourceIdentity.kt`, `ManualProvenanceTest.kt` |
| 2 | `feat(repository): DatasetOutcome classification + outcome log + tests` | `DatasetIntegrity.kt`, `ServerListUpdates.kt`, `DatasetIntegrityOutcomeTest.kt` |
| 3 | `docs: spec v1.0.23 — Z1-IMPL-G (dataset-integrity outcomes + MANUAL provenance fix)` | `PROJECT_SPECIFICATION.md`, `Z1_IMPL_G_REPORT.md` |

---

## 12. Push and post-push verification

One fast-forward push, `40decde..<head>` → `origin/main`; 0 ahead / 0 behind afterwards; `git
rev-list --count origin/main..HEAD` → 0; no force, no reset, no tag operation (5 local / 5 remote
unchanged). Chain `0714628^..HEAD` = **75** after this revision's three commits (72 at the start),
against a §1.6.1 table that still holds 60 rows — the delta is 15 deferred rows, exactly as the
one-own-commit convention prescribes and as §1.6.16 bullet 9 records.

---

## 13. Negative assurance

No device or `adb` command (device DETACHED). No tag operation of any kind. No force, no reset, no
clean, no stash, no checkout, no restore. No `git add -A` / `git add .`. No `build.gradle` change, no
new dependency, no migration, no schema change. C5 not executed.
`D:\PMC\Documents\Android\SoftEthergai` untouched. No pre-existing report file modified.
`SERVER_SOURCE_ARCHITECTURE.md` untouched. `ManualServer*.kt`, `ManualServerStore.kt`,
`VpnServerMerger.kt`, `ServerListUpdater.kt`, `ConnectionListViewModel.kt` and `AppDatabase.kt`
untouched. Every Gradle invocation ran with `CI=true`.

---

## 14. Limitations and negative assurance

**Five limitations are recorded rather than hidden:**

1. **`ACCEPT_NEW` / `MERGE_WITH_EXISTING` carry no add-only behaviour.** Their arch-doc meaning is
   cumulative addition (§9.1), which is Cumulative Server Retention, which is not implemented and is
   outside this phase's authorized files. `REJECT_DEGRADED` is real; the other two are names. Anyone
   reading the log line will see a classification, not a new decision.
2. **The classification is not the decision.** `ServerListUpdates.replaceAll` logs an outcome
   computed from a re-run of `evaluate`; `ServerListUpdater.kt` still decides. The two are kept
   consistent by `evaluate` being pure, and any divergence will be visible in the log — but they are
   two call sites of the same pure function, not one.
3. **Two manual entries on one IPv4** still collapse to one stored row (item-D limitation (d))
   because `VPNGateItem`'s primary key is the host name alone. Unchanged by this revision.
4. **An OpenVPN manual entry** still stores a port but no profile, so it is not connectable (item-D
   limitation (e)). Unchanged.
5. **A manual address must be an IPv4 literal** (item-D limitation), refused at the `add` boundary.
   **INFERENCE** from the two rejection points, not a verified device behaviour.

---

## 15. Recommendations (not executed)

1. **Cumulative Server Retention (arch doc §9)** remains the structural gap and the single change that
   would give `ACCEPT_NEW` / `MERGE_WITH_EXISTING` their stated meaning. It needs the record-level
   merge at the stored-dataset boundary, which means `ServerListUpdater.kt` and probably a schema
   decision for historical rows — a phase of its own, not a follow-up hunk.
2. **Wire the classification into the decision** once Retention lands: at that point
   `ACCEPT_NEW`/`MERGE_WITH_EXISTING` stop being names and `ServerListUpdates` can branch on them
   instead of only logging.
3. **Device phase** (unchanged from the item-D report): add a manual server, run a live refresh,
   confirm the row survives it, and confirm a real 6→7 database upgrade. All **UNKNOWN**.
4. **A row-level policy for two manual entries on one IPv4** — either a deterministic winner the user
   controls, or a UI that surfaces the collision. Still proposed-only.
5. **Items E/F** (text/file/share import) would also close limitation 4.

---

*Evidence labels used in this report: **SOURCE CODE** (direct inspection at the stated path),
**TEST** (automated JVM run, never proof of production behaviour on a device), **PHYSICAL DEVICE**
(none — device DETACHED), **INFERENCE**, **PRIOR REPORT** (operator decision).*
