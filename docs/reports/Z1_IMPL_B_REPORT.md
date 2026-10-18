# Z1-IMPL-B REPORT — Emergency Server Sources Foundation (spec v1.0.20)

**Phase:** Z1-IMPL-B — Roadmap §12.5.2 **item B only**
**Repository:** `C:\Dev\SoftEthergai` · **Branch:** `main` · **Device:** DETACHED — device-free phase
**Date:** 2026-10-16 · **Commits:** `7aa4e8c` (source), `631f38f` (tests), plus this revision's docs commit (spec + this report)
**Evidence labels used:** SOURCE CODE / TEST / PHYSICAL DEVICE / INFERENCE / UNKNOWN / PRIOR REPORT

---

## §1 Verdict

**Roadmap §12.5.2 item B LANDED.** Emergency-source configuration model, Room entity + DAO,
migration 5 → 6, database version 6 registration, ViewModel state exposure, and a Settings entry
point are implemented, JVM-tested (no regression, +7 tests) and build-green (APK produced).
**No device verification** was performed or possible (device DETACHED). §22 of the specification is
byte-identical and §1.6.2–§1.6.12 are untouched. Retrieval is deliberately not wired — items C/D and
retention remain future work. One fast-forward push, no force, no tag operation.

## §2 Pre-conditions and discovery findings

**Pre-conditions (all PASS):** HEAD == origin/main == `8ab938195878a9563471a112024722556206b1b8`;
branch `main`; **no tracked modification** (the working tree carried only pre-existing untracked
files from earlier phases, all left untouched); spec version **1.0.19**; `#### 1.6.12` at line 821;
`## 22.` at line 2618; spec stored with LF endings (`core.autocrlf=true` noted, diff remained
surgical). Evidence: SOURCE CODE.

**Finding 1 — `App.kt` was required and was not in the authorized list (operator-approved).**
Discovery showed the *only* handle on Room is `App.kt`: a private `appDatabase` field, the
`.addMigrations(...)` chain (lines 29–45) and the two exposed DAO accessors — every other consumer
reaches a DAO through `App.instance?.xxxDao`. Two changes were therefore unavoidable:
`.addMigrations(AppDatabase.MIGRATION_5_6)` and `lateinit var emergencySourceConfigDao` +
`emergencySourceConfigDao = appDatabase.emergencySourceConfigDao()`. The operator approved adding
`App.kt` to the authorized set with those two changes only. Migration DDL still follows the repo
convention (`MIGRATION_2_3/3_4/4_5` companion objects in `AppDatabase.kt`) — a manual migration, not
an auto-migration. Evidence: SOURCE CODE + OPERATOR DECISION.

**Finding 2 — the build disables debug variants unless `CI` is set.** `app/build.gradle`'s
`androidComponents` block contains `if (System.getenv("CI") == null) { beforeVariants(
selector().withBuildType("debug")) { variant.enabled = false } }`, so **every** Gradle invocation in
this revision ran with `CI=true`. The first `:app:testProDebugUnitTest` attempt failed in 32 s with
`Selection failed — Cannot locate tasks that match ':app:testProDebugUnitTest' … task … not found`
(a task-selection failure, not a code failure); `CI=true ./gradlew :app:compileProDebugKotlin
--rerun-tasks --no-build-cache` then compiled cleanly (BUILD SUCCESSFUL in 4m57s, 40 tasks). The
prior baselines (671/0/0/1) were recorded with `CI=true` as well. Evidence: SOURCE CODE + TEST.

**Finding 3 — `EmergencySourceConfigDaoTest.kt` is NOT TESTABLE (per the brief's own fallback).**
The project has no Robolectric and no `room-testing` dependency, and `build.gradle` is frozen, so an
in-memory Room DAO test cannot run on the JVM (androidTest requires a device — DETACHED). The file
was **not created** (a test referencing a missing runtime would not compile). Recorded **UNKNOWN**;
DAO behaviour is covered only at the DDL/schema level by `Migration5to6Test`. Evidence: SOURCE CODE
+ UNKNOWN.

**Finding 4 — Room annotations are CLASS-retention.** `javap` on `room-common-jvm-2.7.1.jar`
confirmed `@Database`, `@Entity`, `@PrimaryKey` and `@ColumnInfo` are all
`@Retention(RetentionPolicy.CLASS)` → invisible to reflection at runtime. The first draft of
`Migration5to6Test` read `@Database.version` and `@ColumnInfo.defaultValue` reflectively and would
have NPE'd at test time; it was replaced by runtime-observable artifacts (compiled abstract methods
and the KSP-generated `6.json`) before any test run. Evidence: SOURCE CODE.

## §3 Changes per file

| File | Change | Evidence |
|---|---|---|
| `app/src/main/java/vn/unlimit/vpngate/data/remote/EmergencySourceConfigEntity.kt` | **NEW** (29 lines). `@Entity(tableName = "emergency_source_config")`: `id` autoGenerate PK, `url`, `label` (nullable), `enabled` with `@ColumnInfo(defaultValue = "1")`, `createdAt`, `updatedAt`. No foreign keys, self-contained. Field order and types match the brief's DDL exactly. | SOURCE CODE |
| `app/src/main/java/vn/unlimit/vpngate/data/remote/EmergencySourceConfigDao.kt` | **NEW** (39 lines). `observeAll(): Flow<List<…>>` ordered `createdAt, id`; `listEnabled()`; `getByUrl(url)`; `upsert` with `OnConflictStrategy.REPLACE`; `deleteByUrl(url)`. Suspend + Flow follows the KSP/Room style used elsewhere. | SOURCE CODE |
| `app/src/main/java/vn/unlimit/vpngate/data/remote/EmergencySourceConfig.kt` | **NEW** (36 lines). Domain model with `fromEntity` / `toEntity`. | SOURCE CODE |
| `app/src/main/java/vn/unlimit/vpngate/data/remote/SourceIdentity.kt` | **MODIFIED** — `EMERGENCY_CSV` and `MANUAL` appended at the end of `SourceKind` with KDoc (ordinals preserved, nothing renamed). Existing exhaustive use (`sourceTag`'s `when`) has an `else` branch, so nothing else had to change; no consumer constructs these kinds yet. | SOURCE CODE |
| `app/src/main/java/vn/unlimit/vpngate/db/AppDatabase.kt` | **MODIFIED** — `version = 5 → 6`, `EmergencySourceConfigEntity` registered in `@Database(entities=...)`, `abstract fun emergencySourceConfigDao()`, and `MIGRATION_5_6` (single additive `CREATE TABLE IF NOT EXISTS emergency_source_config (…)` — exactly the brief's DDL, `enabled INTEGER NOT NULL DEFAULT 1` included) in the companion. No other migration touched. | SOURCE CODE |
| `app/src/main/java/vn/unlimit/vpngate/App.kt` | **MODIFIED** (operator-approved, Finding 1) — import + `lateinit var emergencySourceConfigDao` + `.addMigrations(AppDatabase.MIGRATION_5_6)` + `emergencySourceConfigDao = appDatabase.emergencySourceConfigDao()`. | SOURCE CODE + OPERATOR DECISION |
| `app/src/main/java/vn/unlimit/vpngate/viewmodels/ConnectionListViewModel.kt` | **MODIFIED** — `emergencySources: StateFlow<List<EmergencySourceConfig>>` (`stateIn(viewModelScope, WhileSubscribed(5_000))`), `addEmergencySource(url, label): Boolean` (validity gate reuses `SourceUrl.parse(url, SourceKind.EMERGENCY_CSV)`; duplicate URL refreshes the existing row via `getByUrl`), `removeEmergencySource(url)`, `toggleEmergencySource(url, enabled)`. All DB work on `Dispatchers.IO` inside `viewModelScope`, matching the file's existing coroutine style. | SOURCE CODE |
| `app/src/main/java/vn/unlimit/vpngate/ui/screens/settings/SettingsScreen.kt` | **MODIFIED** — new "Emergency Server Sources" `SettingsSection` after the Cache section: summary + empty-state text, one row per source (label/url, `Switch`, remove `IconButton`), and an add block (`OutlinedTextField` URL + optional label + `Button`). Reuses existing components and design tokens; `viewModel()` provides `ConnectionListViewModel`; invalid URL shows the existing snackbar. | SOURCE CODE |
| `app/src/main/res/values/strings.xml` | **MODIFIED** — 8 new strings under the `emergency_source_` prefix. | SOURCE CODE |
| `app/src/test/java/vn/unlimit/vpngate/db/Migration5to6Test.kt` | **NEW** (177 lines, 7 tests) — see §4. | TEST |
| `app/schemas/vn.unlimit.vpngate.db.AppDatabase/6.json` | **NEW** — KSP-generated Room schema export for version 6 (committed per brief STEP 3e; `exportSchema` is enabled in this project). | SOURCE CODE + TEST |
| `EmergencySourceConfigDaoTest.kt` | **NOT CREATED** — NOT TESTABLE (Finding 3). | SOURCE CODE + UNKNOWN |

**Design decisions recorded (INFERENCE where noted):** the entity/DAO live in `data/remote` per the
brief's authorized list (the repo's existing entities live in `models/` and its DAOs in `db/`)
— INFERENCE: the operator's explicit paths take precedence. Duplicate-URL protection is in the
ViewModel rather than a unique index, so the table matches the brief's DDL literally — INFERENCE.
`StateFlow` is used as the brief requires even though this ViewModel's existing state is LiveData —
INFERENCE. `addEmergencySource` returns before the asynchronous write lands; the published flow is
the confirmation — INFERENCE.

## §4 Test results (JVM, device-free)

Command: `CI=true ./gradlew :app:testProDebugUnitTest :SoftEtherClient:test --rerun-tasks --no-build-cache`
→ **BUILD SUCCESSFUL in 8m52s** (86 tasks executed). Evidence: TEST.

- `:app:testProDebugUnitTest` = **678 / 0 / 0 / 1** (tests / failures / errors / skipped), 50 result
  files. Baseline was **671 / 0 / 0 / 1** → **+7 tests, zero failures, zero errors, ignored count
  unchanged**. All 7 additions are the new `Migration5to6Test` (0.031 s):
  `the migration is bounded to 5 to 6`, `the migration creates the emergency source table with the
  expected columns`, `the migration is a single additive statement`,
  `the migration never drops deletes or recreates an existing table`,
  `the database exposes the emergency source dao accessor`,
  `the generated version 6 schema matches the migration DDL`,
  `a freshly configured source is enabled and unlabelled by default`.
- `:SoftEtherClient:test` = **173 / 0 / 0 / 0** — identical to baseline, no regression.
- DAO-level behaviour: **NOT TESTABLE / UNKNOWN** (Finding 3).

## §5 Build result (device-free)

Command: `CI=true ./gradlew :app:assembleProDebug --rerun-tasks --no-build-cache` → **BUILD
SUCCESSFUL in 12m33s** (114 tasks executed). Evidence: TEST.

- Artifact: `app/build/outputs/apk/pro/debug/app-pro-debug.apk` — 63,678,518 bytes.
- SHA-256: `9ba16f9ffddb17b8d7e46c36e0e647ca8e2ebf6f08bbd45d14031b7621a5094c`
  (prior `713b1014…` differs — expected for new code).

## §6 Diff-scope confirmation

**PASS.** Modified (6): `App.kt`, `SourceIdentity.kt`, `AppDatabase.kt`, `SettingsScreen.kt`,
`ConnectionListViewModel.kt`, `strings.xml`. New (5): the three `data/remote/EmergencySourceConfig*`
files, `Migration5to6Test.kt`, `6.json`. No other file was staged at any point; every staging
command listed exact paths (no `git add -A` / `git add .`). `App.kt` and `6.json` are the two
documented additions to the authorized list (Finding 1; brief STEP 3e). Commit 1 = 10 files
(+604/−1), commit 2 = 1 file (+177).

## §7 Spec edits (v1.0.19 → v1.0.20)

- **E1 header** — `Document version: 1.0.19 → 1.0.20`; `HEAD` → `631f38fbf3dbb0be886396ca26d55678ab4516b3`
  (the source-and-tests commit at the time of writing — a spec file cannot name its own commit);
  `Last updated` unchanged (2026-10-16).
- **E2 §1.6.1 push-state note** — new "Push state (v1.0.20)" note appended after the v1.0.19 note
  (no existing note modified); records the three commits, the remaining items, the HEAD-field
  convention and "no tag operation".
- **E3 §11.9.3** — the historical "DEFERRED / NOT YET DEFINED" line is **retained verbatim** and
  annotated *superseded*; an approval note at the end of the section records
  `SERVER_SOURCE_ARCHITECTURE.md` §10 as the **approved design basis**, with the parser explicitly
  deferred to roadmap item C. §11.9.1 and §11.9.2 untouched.
- **E4 new §1.6.13** "Z1-IMPL-B — Emergency Source Foundation (v1.0.20)" — 9 evidence-labelled
  bullets (item B landed; item A already implemented / NO-OP; §11.9.3 approval; items C–H and
  retention not implemented; JVM counts; build + APK SHA-256; JVM-only migration verification;
  device verification none; chain arithmetic + no new lessons), inserted after §1.6.12's closing
  separator and before `## 2.`.
- **E5 §12.5.1** — **NO-OP**: no roadmap-item rows exist there (17 rows, all fix-queue items);
  §12.5.2 remains the canonical roadmap tracker. §12.5.1 otherwise unchanged.
- **E6 §20 Changelog** — new 1.0.20 row.
- **E7 §20 Verification Status** — new §1.6.13 row (HISTORICAL) and the §11.9 row annotated as
  superseded (both inside §20, inside the allowed hunk set).
- **E8 §22** — DO NOT TOUCH: verified identical (§8).
- **Hunk scope** — 8 hunks: header, §1.6.1, §1.6.13 insertion, §11.9.3 ×2, §20 ×3. **Zero hunks
  inside §1.6.2–§1.6.12** (the only deletions in the whole spec diff are the two header lines and the
  two lines rewritten by the §11.9.3 / §20 annotations, whose historical wording is preserved inside
  the replacements). 83 insertions / 4 deletions.

## §8 §22 byte-identity

**IDENTICAL** — working tree vs HEAD (`8ab9381`, pre-phase): **99 lines / 6,504 bytes** on both
sides, `diff -q` clean. The same check holds after all spec edits. Evidence: SOURCE CODE.

## §9 No-change / negative assurance

- **No device command** — no `adb` of any kind; the device is DETACHED. Evidence: PHYSICAL DEVICE
  (not performed).
- **No tag operation** — local 5 / remote 5 unique, unchanged; `p2.4.10` untouched.
- No `--force`, `reset`, `clean`, `stash`, `checkout`, `restore`; no `git add -A` / `git add .`; one
  fast-forward push only.
- **C5 not executed.** `D:\PMC\Documents\Android\SoftEthergai` untouched.
- No change to `build.gradle`, manifests, native code, `SERVER_SOURCE_ARCHITECTURE.md` or any
  existing report file; the Room/KSP plugins were already registered (nothing to flag).
- §1.6.2–§1.6.12 frozen text byte-unchanged (0 deletions inside them).
- Pre-existing untracked files/dirs from earlier phases were left untouched and unstaged.

## §10 Recommendations (proposed only — no action taken)

1. **Roadmap item C** — emergency CSV parser + integrity gate, wired into `ServerListUpdater` /
   `VpnServerMerger`; §11.9.3's approval is now recorded, so the design basis exists.
2. **Roadmap item D** — manual entry UI + merge (`SourceKind.MANUAL` already exists as a type).
3. **Retention (arch doc §9)** — decide explicitly in/out before or alongside item C.
4. **Device phase** — exercise the 5 → 6 migration on a real install, and re-verify PART A
   (§12.5.1 rows 16 and 17 remain OPEN).
5. If duplicate protection should live in the schema, add a unique index on
   `emergency_source_config(url)` in a later phase (kept out here to match the brief's DDL literally).
6. If DAO-level tests are wanted, add Robolectric (or use `room-testing`) — requires a
   `build.gradle` change, which is frozen for now.
7. **Version bump** — a future release phase should bump `versionCode`/`versionName` past
   2.4.10/143 (carried forward from §1.6.12 bullet 5).

---

*This report and the spec update are the final commit of this revision; the push and the post-push
verification (HEAD == origin/main, tag set, working-tree state) are recorded in the operator's final
report for the phase.*
