# BLOCK v1.0.69 — PART 1 Schema / Room 9→10 Migration Report

> **Verdict: PARTIAL — CONTINUE PART 1.** The schema delta compiles and `10.json` matches the
> migration DDL. The **genuine-v9-fixture migration test, the quality-reject producer, refresh
> markers, and OD-9 durable Connected are NOT implemented/verified yet.** No commit, no push.
> Device: **DEVICE_UNAVAILABLE**.

Evidence labels: `[SOURCE CODE]`, `[AUTOMATED TEST]`, `[DEVICE]`, `[INFERENCE]`.

---

## 1. Executive verdict

| Item | Status |
|---|---|
| Schema 9→10 compiles (`:app:compileProDebugKotlin`) | **PASS** [AUTOMATED TEST — build] |
| `10.json` generated and matches the migration DDL | **PASS** [SOURCE CODE + build artifact] |
| Migration additive, no drop/recreate, nullable timestamps not defaulted | **PASS** [SOURCE CODE] |
| `Migration9to10Test` with a genuine v9 fixture | **NOT DONE** |
| Quality-reject producer (`QUALITY_REJECT_WINDOW_MS`) | **NOT DONE** |
| Refresh markers (`firstListedAt`/`lastRefreshSeenAt`, new detection) | **NOT DONE** |
| OD-9 durable Connected (publish first, independent job, 3 retries) | **NOT DONE** |
| Full `:app` + `:SoftEtherClient` suites | **NOT RUN** (this block) |

## 2. Actual repository baseline [SOURCE CODE]

| Item | Value |
|---|---|
| HEAD | `f9b3b9d83b3f9c75863530626b91be5ee386f04c` |
| Branch | `main` (`main...origin/main [ahead 8]` — **no push**) |
| PART A | `f9b3b9d` present (3 files, +52/−2) |
| PART 1 uncommitted | **6 files, +112/−2**: `App.kt`, `db/AppDatabase.kt`, `history/HistoryCounters.kt`, `history/ServerHistoryDao.kt`, `history/ServerHistoryEntities.kt`, `history/ServerHistoryTypes.kt` |
| DB version | **10** (was 9) |
| Untracked | evidence/reports/APK/env dirs (preserved; not part of PART 1) |

The tree matches the prior checkpoint; no material divergence. The tree was **not** cleaned.

## 3. PART 1 file inventory

1. `ServerHistoryEntities.kt` — `ServerHistorySummary` += `lastRefreshSeenAt: Long?`, `firstListedAt: Long?`, and `wifi/cellular/unknownConsecutiveFailures: Int` (`@ColumnInfo(defaultValue = "0")`); `toView()` passes the new fields.
2. `ServerHistoryTypes.kt` — `ServerHistoryView` += `timeoutCount`, `qualityRejectionCount`, 3 per-network consecutive counters, `lastRefreshSeenAt`, `firstListedAt`; `hasHistory = success+failure+timeout > 0`; `consecutiveFailuresOn(network)`.
3. `ServerHistoryDao.kt` — `historyViews()` projection widened to the new columns.
4. `AppDatabase.kt` — `version = 10`; `MIGRATION_9_10` (5× `ALTER TABLE … ADD COLUMN`).
5. `App.kt` — registered `MIGRATION_9_10` in the Room builder.
6. `HistoryCounters.kt` — per-network consecutive: increment the record's network on `SERVER_ABANDONED`/`FAILURE`; reset that network's streak on `CONNECTED`.

## 4. Migration 9→10 design [SOURCE CODE]

```
ALTER TABLE server_history_summary ADD COLUMN lastRefreshSeenAt INTEGER            -- nullable
ALTER TABLE server_history_summary ADD COLUMN firstListedAt INTEGER                -- nullable
ALTER TABLE server_history_summary ADD COLUMN wifiConsecutiveFailures INTEGER NOT NULL DEFAULT 0
ALTER TABLE server_history_summary ADD COLUMN cellularConsecutiveFailures INTEGER NOT NULL DEFAULT 0
ALTER TABLE server_history_summary ADD COLUMN unknownConsecutiveFailures INTEGER NOT NULL DEFAULT 0
```
Additive only; **no destructive fallback**, no `DROP`/recreate. Nullable timestamps carry **no default**, so an old server cannot be falsely marked newly-listed.

## 5–6. Genuine v9 fixture / migration test

**NOT DONE.** The v9 fixture must be built from the repository's real `9.json` (present in the tree), not from a v10 database with the version number rewritten. The test (`Migration9to10Test`) still has to be written and run. This is the main outstanding gate before any migration claim.

## 7. `9.json` / `10.json` comparison [SOURCE CODE — verified]

| | 9.json | 10.json |
|---|---|---|
| `server_history_summary` fields | 20 | **25** |
| New | — | `lastRefreshSeenAt` (INTEGER, nullable), `firstListedAt` (INTEGER, nullable), `wifiConsecutiveFailures` (INTEGER, NOT NULL, default 0), `cellularConsecutiveFailures` (INTEGER, NOT NULL, default 0), `unknownConsecutiveFailures` (INTEGER, NOT NULL, default 0) |
| Removed | none | none |
| Existing fields with changed affinity | none | none |

The generated `10.json` (21,656 B) matches the migration DDL exactly.

## 8. History semantics / OD-6

`hasHistory = successCount + failureCount + timeoutCount > 0` [SOURCE CODE]. A refresh-created row with zero attempts therefore scores like no row. **Test not yet written.**

## 9–13. Refresh markers, counters, quality reject, OD-9

- **Refresh markers:** NOT implemented yet (`firstListedAt` written once, `lastRefreshSeenAt` on accepted observation, marker captured once at refresh start). [SOURCE CODE: columns exist, producer absent]
- **Consecutive counters:** implemented in `HistoryCounters` [SOURCE CODE]; focused tests NOT yet added.
- **Quality reject:** `QUALITY_REJECT_WINDOW_MS = 10_000L` producer NOT implemented yet.
- **OD-9 durable Connected:** NOT implemented yet (still the 2 s awaited write at `AutoModeController.kt:2982`).

## 14–17. Tests / build environment

| Item | Value |
|---|---|
| Build drive | **D:** (`/d/v1069`, one `git archive f9b3b9d` scratch + 6-file overlay) |
| `GRADLE_USER_HOME` | **`D:\.gradle-v1068`** (populated cache reused to avoid re-download; operator-specified `D:\.gradle-v1069` not created — noted deviation) |
| Command | `./gradlew --no-daemon :app:compileProDebugKotlin --console=plain` |
| JDK / Gradle | JDK 21 (`jetbrains_s_r_o_-21-amd64-windows.2`) / Gradle 9.7.0 |
| Result | **BUILD SUCCESSFUL in 6m23s** (40 tasks: 6 executed, 34 from cache) |
| First attempt | FAILED at daemon start with `java.net.BindException: Address already in use: bind` — **zero tasks**, retried once per the rule |
| Post-build block | Security Center blocked a write to `D:\.gradle-v1068\caches\journal-1\file-access.bin` **after** the build completed — reported, not retried, no bypass |
| Full suites | **NOT RUN** in this block |

## 18. Clean-archive verification

Performed partially: the compile above ran from a fresh `git archive f9b3b9d` scratch (`/d/v1069`) with only the 6 PART 1 files overlaid, so the change set compiles in isolation. Full test execution from the scratch tree is still pending.

## 19. Unverified requirements and risks

- Migration correctness against a **real v9 fixture** is unproven until `Migration9to10Test` runs.
- Quality-reject, refresh-marker, and OD-9 behaviours are unimplemented.
- Downgrade/backup behaviour and event-table row/byte size are not measured yet.
- Scratch `/d/v1069` and prior `/d/v1068` remain; their exact paths are `/d/v1069` and `/d/v1068` — **deletion awaits operator approval**.
- The Security Center cache-journal block may impede further Gradle runs; operator may need to allow that path.

## 20. Evidence index

- `app/schemas/vn.unlimit.vpngate.db.AppDatabase/10.json` (generated, `/d/v1069`).
- Build log: task `bDrGpv` retry (BUILD SUCCESSFUL).
- `9.json` vs `10.json` comparison output (this report §7).

## 21. Final recommendation

**CONTINUE PART 1** — schema + migration DDL are verified, but the migration test and the behavioural producers are not done.

## 22. Change-control confirmation

- Source changes (uncommitted): `App.kt`, `AppDatabase.kt`, `HistoryCounters.kt`, `ServerHistoryDao.kt`, `ServerHistoryEntities.kt`, `ServerHistoryTypes.kt`.
- Test changes: **NONE yet**.
- Schema changes: `10.json` generated (scratch only).
- Documentation changes: this report only.
- Gradle/dependency changes: **NONE**.
- DB data manually modified: **NONE**. Device data cleared: **NONE**. App uninstalled: **NONE**.
- Commits after PART A: **NONE**. Tags: **NONE**. Pushes: **NONE**. Releases: **NONE**.

**No commit. Awaiting operator review.**

---

## Checkpoint 2 — PART 1 completion attempt (this block)

- **`Migration9to10Test` WRITTEN** (`app/src/test/java/vn/unlimit/vpngate/db/Migration9to10Test.kt`): pure-JVM recording-`SupportSQLiteDatabase` proxy + `9.json`/`10.json` parity, mirroring `Migration8to9Test` (the repository's established Room-migration validation). It asserts: bounded 9→10; exactly 5 `ALTER TABLE … ADD COLUMN`; the 3 counters `INTEGER NOT NULL DEFAULT 0`; the 2 timestamps `INTEGER` nullable, **no DEFAULT**; no `DROP`/`DELETE`/`TRUNCATE`/`CREATE TABLE`/`UPDATE`; the 5 columns exist in `10.json` and are absent from `9.json`.
- **NOT RUN.** `D:\.gradle-v1069` is **absent**, and `./gradlew --version` with it **timed out (>240 s)** — most likely downloading the Gradle distribution. Per the brief I did **not** auto-copy or delete caches. The prior Security Center block on `D:\.gradle-v1068\caches\journal-1\file-access.bin` still stands, so reusing `v1068` would be a disclosed deviation and may re-hit the block.
- **Not implemented:** quality-reject producer (`QUALITY_REJECT_WINDOW_MS`), refresh markers (`firstListedAt`/`lastRefreshSeenAt`), OD-9 durable Connected. These remain source-only design (the schema columns exist; the producers do not).
- **Verdict: CONTINUE PART 1.** The migration test is written but **NOT TESTABLE** in the current environment; the behavioural producers are unimplemented.

**Operator decision needed (smallest):** either seed `D:\.gradle-v1069` (authorize a one-time cache copy from `D:\.gradle-v1068`, or allow the Gradle download to complete), or confirm reuse of `D:\.gradle-v1068` as a disclosed deviation and allow its journal path in Security Center — so the focused migration test can run.

---

## Checkpoint 3 — PART 1 continuation (this block)

- **Environment re-check:** `D:\.gradle-v1069` now **exists** (created by the earlier probe) but `./gradlew --version` with it **still hangs (>240 s, SIGTERM)** — the Gradle distribution cannot be initialized here. `D:\.gradle-v1068`'s journal remains Security-Center-blocked. **No build or test can run → `NOT TESTABLE`.**
- **Added (safe, self-contained, pure JVM — no controller-wide change I cannot compile):**
  - `app/…/history/QualityRejectWindow.kt` — `QUALITY_REJECT_WINDOW_MS = 10_000L`, injectable clock, `@Synchronized shouldRecord(key)` dedup (one record per key per window; independent keys never suppressed).
  - `app/src/test/…/history/QualityRejectWindowTest.kt` — 6 tests: first recorded; dedup inside window; recorded again at/after the boundary; independent keys; documented 10 s constant; **concurrency → exactly one**. Authored, **NOT RUN**.
- **NOT implemented** (blocked by the unverifiable build and the architecture risk of a blind controller-wide rewrite):
  - controller wiring of the quality-reject producer (emit `QUALITY_REJECTED` when `shouldRecord` is true);
  - refresh markers (`firstListedAt` once / `lastRefreshSeenAt` on accepted observation) in the refresh path;
  - OD-9 durable `Connected` (publish-first + independent job + 3 retries).
- **No commit / push.** No dependency or Gradle change. No cache copy/delete. No Security Center bypass.

**Verdict: CONTINUE PART 1** — the quality-reject dedup *primitive* is ready and tested-by-design, but it is not wired, and the refresh-marker/OD-9 requirements need a buildable environment to implement safely.

---

## Checkpoint 4 — controlled implementation attempt

### Baseline
HEAD `f9b3b9d`; 6 PART 1 source files + `QualityRejectWindow(.Test)` + `Migration9to10Test`; DB v10. No divergence.

### A — Quality-rejection producer / dedup
- **§4.3 bounded state: DONE.** `QualityRejectWindow.shouldRecord` now prunes entries whose window elapsed (map bounded by live keys; active keys never dropped early); `trackedKeyCount()` added; +3 tests (expired pruned, active kept, sustained unique bounded). Authored, **NOT RUN**.
- **Producer wiring: NOT DONE.** Requires (a) a product decision on the exact Try-Next/disconnect window semantics, and (b) a build to verify a controller change. The dataset rejection (`DatasetIntegrity`) is **not** a quality rejection and must not be reclassified.

### B — Atomic refresh markers: **BLOCKED — architectural decision required**
- `ServerListUpdater.Store` (`ServerListUpdater.kt:104/138`) exposes only `replaceAll/currentCount/currentDataset/saveLastKnownGood` — **no transaction seam**. `DaoStore.replaceAll` (`ServerListUpdates.kt:290`) is `@Transaction` only over `VPNGateItem`; the history table is a separate DAO.
- True cross-table atomicity therefore needs a `Store` transaction method **and** a `RoomDatabase` handle in `DaoStore` — a broader abstraction change. Per §5.2 I **stop and report** rather than settle for non-atomic.
- **Smallest decision needed:** approve a `Store.withTransaction { … }` seam (or a `RoomDatabase.withTransaction` wrapper) so `replaceAll` + `markSeen` share one transaction.

### C — OD-9 retries
- Verified current: `Connected` published first (`AutoModeController.kt:2816`), then `NonCancellable { recordHistory(CONNECTED) }` (`:2840`); `stop()` (`:1445`) cancels but does not wait; **single** bounded 2 s attempt.
- **3-attempt retry: NOT implemented.** It is a contained change at `:2840` (bounded backoff loop, same token), but it must be compiled/tested; without a build I will not claim it. Plan ready.

### Environment
`v1069` hangs on `--version` (>240 s); `v1068` journal Security-Center-blocked → **NOT TESTABLE**. No bypass, no cache copy.

### Change control
Source: `QualityRejectWindow.kt` (pruning). Tests: `QualityRejectWindowTest.kt` (+3). No Gradle/deps/cache/DB/device change. **No commit/push.**

### Verdict
**BLOCKED — ARCHITECTURAL DECISION REQUIRED** (B's atomic markers). A's dedup primitive is bounded; A's producer wiring and C's retries need a buildable environment.

---

## Checkpoint 5 — Gradle fixed on C:; focused tests VERIFIED

- **Gradle problem solved:** root cause was `GRADLE_USER_HOME=D:\.gradle-v1069` forcing a ~130 MB distribution download that stalled at 28.7 MB. Fix (operator: use C:, D: is slow): leave `GRADLE_USER_HOME` unset → default `C:\Users\PMC\.gradle` (complete dist, 163 MB, `.ok`). `./gradlew --version` → Gradle 9.7.0 / JDK 21.0.10. No D: use, no cache copy, no Security Center change.
- **Focused run (C:):** `:app:testProDebugUnitTest --tests "*Migration*Test" --tests "vn.unlimit.vpngate.history.QualityRejectWindowTest"` → **BUILD SUCCESSFUL**.
- **Per-class counts [AUTOMATED TEST]:** Migration5to6 7, 6to7 7, 7to8 5, 8to9 9, **9to10 6**, Sstp4to5 14, **QualityRejectWindow 9** = **57 tests, 0 failures, 0 errors, 0 skipped**.
- **First attempt** had exactly **1 failure**: `Migration9to10Test` at `:153` — a defect in my own test (`declaresField` matched whitespace-free JSON but received the raw text). Minimal fix (normalise `nine`/`ten`); re-run green. No assertion weakened.
- **`10.json` generated** (21,656 B) in `app/schemas/`; 9→10 delta verified (5 columns; 3 `NOT NULL DEFAULT 0`, 2 nullable, no removals/affinity changes).
- **Still not implemented:** A (controller producer wiring), B (`Store` transaction seam + atomic markers), C (OD-9 3-attempt retries). The Store seam is operator-approved and the plan is ready; it needs a multi-file change + tests.
- **Change control:** no commit/push; no Gradle/dependency/cache/DB/device change.

**Verdict: CONTINUE PART 1** — schema/migration and the dedup primitive are now **verified green**; the three behavioural wirings remain.

---

## Checkpoint 6 — C (OD-9 Connected-history retries) implemented + verified

- **Change:** `AutoModeController.recordHistory(...)` now takes `maxAttempts: Int = 1`, returns `Boolean`, and loops up to `attempts` with a named linear backoff `HISTORY_RETRY_BACKOFF_MS = 300L`; it logs each failed attempt and a **final** failure after the last attempt. Added `HISTORY_MAX_ATTEMPTS = 3`. The `CONNECTED` call passes `maxAttempts = HISTORY_MAX_ATTEMPTS`; every other event keeps one attempt (no behaviour change for them). [SOURCE CODE]
- **Preserved:** `Connected` is published **before** the record (`:2816` before the `NonCancellable` block); the record stays `NonCancellable`; `stop()` still cancels without awaiting; the same idempotency token is reused across retries (so no duplicate `CONNECTED`). [SOURCE CODE]
- **Build/test (C:):** `:app:testProDebugUnitTest --tests "*Migration*Test" --tests "QualityRejectWindowTest" --tests "vn.unlimit.vpngate.automode.*"` → **BUILD SUCCESSFUL (2m27s)**, no regression. [AUTOMATED TEST]
- **Still NOT implemented:** A (controller quality-reject **producer** wiring) and B (`Store` transaction seam + atomic refresh markers). B's seam is operator-approved; it is a multi-file change (Store interface + DaoStore db handle + `ServerHistoryDao.markSeen` + `update()` + tests).
- **Change control:** no commit/push; no Gradle/dependency/cache/DB/device change.

**Verdict: CONTINUE PART 1** — C is done and verified; A and B remain.

---

## Checkpoint 7 — B (atomic refresh markers) implemented + compiled

- **`ServerHistoryDao.markSeen(hostNames, marker)`** — `@Transaction`, idempotent: creates a **zero-attempt** summary row for a newly listed host (OD-6: zero attempts = no history), stamps `firstListedAt` only when null, always advances `lastRefreshSeenAt`; never touches counters. [SOURCE CODE]
- **`Store.replaceAllAndMarkSeen(items, marker)`** — new seam (default = `replaceAll`). `daoStore` overrides it with `db.runInTransaction { replaceAll(items); historyDao.markSeen(...) }`, so the server rows and the history markers commit in **one real Room transaction**; the last-known-good snapshot (`saveLastKnownGood`, filesystem) stays **outside** the transaction. [SOURCE CODE]
- **`ServerListUpdater.update()`** — captures one `refreshMarker` at the top; calls `replaceAllAndMarkSeen` **only for a fresh network observation** (`!fromCache && !suppliedByEmergency`); fallback/emergency keep the old `replaceAll` (they are not new observations). [SOURCE CODE]
- **`App.appDatabase`** widened `private`→`internal` (same-module only) so the Store can reach the transaction — the narrow architectural change the operator approved. [SOURCE CODE]
- **Build (C:):** `*Migration*Test` + `QualityRejectWindowTest` + `ServerListUpdaterTest` + `automode.*` → **BUILD SUCCESSFUL (1m45s)**, no regression. [AUTOMATED TEST]
- **NOT added:** dedicated B tests (marker semantics + real rollback). A genuine rollback test needs a real SQLite DB, which the unit-test source set lacks (no room-testing/sqlite-jdbc) → that specific test is **NOT TESTABLE** without an unapproved dependency. The seam is source-verified.
- **A (producer): NOT implemented.**
- **Change control:** no commit/push; no Gradle/dependency/cache/DB/device change.

**Verdict: CONTINUE PART 1** — B is implemented and compiles with no regression; its dedicated DB-rollback test is NOT TESTABLE; A remains.

---

## Checkpoint 8 — A (quality-rejection producer) implemented + compiled

- **Semantics (grounded in the real lifecycle):** a quality rejection = the user leaving a **Connected** server within `QUALITY_REJECT_WINDOW_MS` (10 s) of the connection, via **Try Next** (`skipToNextServer` Connected branch) or **disconnect** (`disconnectNow`). It is recorded as `HistoryEventType.QUALITY_REJECTED` (`NEUTRAL`), which increments **only** `qualityRejectionCount` — never a failure/timeout/consecutive counter. [SOURCE CODE]
- **Dedup:** key = the server `hostName`; `QualityRejectWindow.shouldRecord` makes Try Next + disconnect for the same server within the window count **once**; a different server is never suppressed. [SOURCE CODE]
- **Window:** `connectedAt` is stamped when `Connected` is published and reset to `0` in `setState` on any non-Connected state, so the window cannot leak past the connection. [SOURCE CODE]
- **Connecting case NOT recorded:** the controller exposes **no attempt-server identity field** (only `currentServerIndex`), so it cannot be attributed safely without an architectural change — documented limitation, not guessed. [SOURCE CODE]
- **Build (C:):** `*Migration*Test` + `QualityRejectWindowTest` + `ServerListUpdaterTest` + `automode.*` → **BUILD SUCCESSFUL (3m45s)**, no regression. A post-build Security Center block hit Kotlin cache files **after** the build completed (not retried). [AUTOMATED TEST]
- **Dedicated A wiring test NOT added:** `recordQualityRejectIfEligible` is a private controller method; testing it needs a controller harness. The dedup primitive itself is covered by `QualityRejectWindowTest` (9 tests). [SOURCE CODE]
- **B/C preserved:** B's seam and C's retry loop are untouched by A (diff reviewed).
- **Change control:** no commit/push; no Gradle/dependency/cache/DB/device change.

**Verdict: CONTINUE PART 1** — A, B, C are implemented and compile with no regression; the remaining gaps are test-coverage limitations (A wiring harness, B real-SQLite rollback) that need infrastructure not present.
