# BLOCK v1.0.69 PART 1 — Final Regression Validation & Evidence Report (Checkpoint 9)

> Read-only validation. **No source/test/Gradle/cache/DB/device change. No commit/push/tag/release.**
> Evidence labels: SOURCE CODE / AUTOMATED TEST / DEVICE / INFERENCE.

## 1. Executive Verdict

| Area | Status |
|---|---|
| A — quality-rejection producer | **PASS** (source) — implemented; controller-wiring **test NOT added** (coverage gap) |
| B — atomic refresh markers | **PASS** (source) — one real Room transaction; **real-SQLite rollback test NOT TESTABLE** |
| C — OD-9 Connected retries | **PASS** (source) — 3 bounded attempts, backoff, final log |
| Full `:app` suite | **PASS** — 1291 tests, 0 failures, 0 errors, 1 skipped |
| Full `:SoftEtherClient` suite | **PASS** — 184 tests, 0 failures, 0 errors |
| Migration 9→10 real-SQLite validation | **NOT TESTABLE** (recording-proxy + schema parity only) |
| Physical device | **DEVICE_UNAVAILABLE** (not performed) |

## 2. Baseline and Change Control

- HEAD `f9b3b9d`; branch `main`.
- 9 tracked files modified, **+300/−19**; `git diff --check` **OK**.
- Untracked PART 1 additions preserved: `QualityRejectWindow.kt`, `QualityRejectWindowTest.kt`, `Migration9to10Test.kt`, `10.json`, reports.
- No divergence from the checkpoint; nothing reset/cleaned.

## 3. A — Quality-Rejection Review [SOURCE CODE]

- **Producer call sites:** `skipToNextServer()` Connected branch (`recordQualityRejectIfEligible(connectedCandidate)`) and `disconnectNow()` (captured before `connectedCandidate` is cleared).
- **Window:** `connectedAt` stamped when `Connected` is published; reset to `0` in `setState` on any non-Connected state.
- **Key:** server `hostName`; `QualityRejectWindow.shouldRecord` (10 s, synchronized, pruned) makes Try Next + disconnect for the same server count **once**; a different server is never suppressed.
- **Counters:** emits `HistoryEventType.QUALITY_REJECTED` (`NEUTRAL`) → `HistoryCounters` increments **only** `qualityRejectionCount`; failure/timeout/consecutive counters untouched.
- **DatasetIntegrity** rejection never reaches this path (it is a dataset outcome in `ServerListUpdater`, not a connection-quality event).
- **Connecting case:** NOT recorded — no attempt-server identity field exists (only `currentServerIndex`). Documented limitation.
- **Risk:** dedup by `hostName` alone could suppress a second legitimate rejection of the same server within 10 s; that is the intended "equivalent rejection" semantics (Try Next + disconnect = one event).

## 4. B — Atomic Refresh Marker Review [SOURCE CODE]

- **Marker:** `refreshMarker = System.currentTimeMillis()` captured once at the top of `ServerListUpdater.update()`.
- **Gating:** `replaceAllAndMarkSeen(usableRows, refreshMarker)` is called **only** for a fresh network observation (`!fromCache && !suppliedByEmergency`); fallback/emergency keep `replaceAll`.
- **Transaction:** `Store.replaceAllAndMarkSeen` → `daoStore` → `db.runInTransaction { replaceAll(items); historyDao.markSeen(hostNames, marker) }` — one real `RoomDatabase` transaction; `replaceAll`'s inner `@Transaction` joins it.
- **`firstListedAt`:** `markSeen` stamps it only when null; `lastRefreshSeenAt` advances; no counter touched; new hosts get a zero-attempt row (OD-6).
- **Snapshot:** `saveLastKnownGood` (filesystem) runs **outside** the Room transaction — accurately a non-atomic filesystem write, not claimed atomic.
- **Early returns:** a rejected/failed/empty refresh returns before the write, so markers cannot be stamped for an unaccepted dataset.
- **Limitation:** the real cross-table **rollback** is not exercised by a test (no real-SQLite fixture) → **NOT TESTABLE**.

## 5. C — OD-9 Review [SOURCE CODE]

- `Connected` published at `AutoModeController.kt:2816` **before** the history write.
- `recordHistory` for `CONNECTED` uses `maxAttempts = HISTORY_MAX_ATTEMPTS (3)`; linear backoff `HISTORY_RETRY_BACKOFF_MS (300)`; logs each failure and a **final** failure.
- Same idempotency token reused across attempts → no duplicate `CONNECTED` (`ServerHistoryDao.countToken`).
- `stop()` cancels `job` and does not await the write; the write runs in `NonCancellable`.
- Other events keep `maxAttempts = 1` (unchanged).
- No process-death durability claimed.

## 6. Focused Test Results [AUTOMATED TEST]

| Class | tests | fail | err |
|---|---|---|---|
| Migration5to6 / 6to7 / 7to8 / 8to9 / 9to10 | 7 / 7 / 5 / 9 / 6 | 0 | 0 |
| SstpMigration4to5 | 14 | 0 | 0 |
| QualityRejectWindow | 9 | 0 | 0 |

## 7. Full Test-Suite Results [AUTOMATED TEST]

Command (C:, `GRADLE_USER_HOME` unset): `./gradlew --no-daemon :app:testProDebugUnitTest :SoftEtherClient:testDebugUnitTest --console=plain`
- **BUILD SUCCESSFUL in 52s** (`:app:testProDebugUnitTest` executed; `:SoftEtherClient:testDebugUnitTest` UP-TO-DATE — inputs unchanged).
- **`:app`: 103 classes, 1291 tests, 1 skipped, 0 failures, 0 errors.**
- **`:SoftEtherClient`: 13 classes, 184 tests, 0 skipped, 0 failures, 0 errors.**
- Total: **1475 tests, 1 skipped, 0 failures, 0 errors.**

## 8. Migration 9→10 Evidence and Limitations

- `MIGRATION_9_10` is additive: 5 `ALTER TABLE … ADD COLUMN` (3 `NOT NULL DEFAULT 0`, 2 nullable INTEGER). `10.json` (21,656 B) matches the delta; `9.json` unchanged.
- **`Migration9to10Test` is a recording-proxy SQL-contract + schema-parity test** — it does **not** open a real v9 SQLite DB, does not migrate rows, and does not run Room's own validator. Not represented otherwise.

## 9. Unverified Behaviors

- A controller-wiring test (real Try Next/disconnect → one `QUALITY_REJECTED`) — **not added** (private method; no existing controller harness). The dedup primitive is covered; the wiring is source-verified only.
- B real-SQLite cross-table rollback — **NOT TESTABLE** (no SQLite test dependency).
- Migration 9→10 on a genuine v9 DB — **NOT TESTABLE**.
- All device behaviour — **DEVICE_UNAVAILABLE**.

## 10. Files Changed

Tracked (uncommitted): `App.kt`, `automode/AutoModeController.kt`, `db/AppDatabase.kt`, `history/HistoryCounters.kt`, `history/ServerHistoryDao.kt`, `history/ServerHistoryEntities.kt`, `history/ServerHistoryTypes.kt`, `repository/ServerListUpdater.kt`, `repository/ServerListUpdates.kt`.
New (untracked): `history/QualityRejectWindow.kt`, `test/…/history/QualityRejectWindowTest.kt`, `test/…/db/Migration9to10Test.kt`, `app/schemas/…/10.json`, reports.

## 11. Final Git Status and Diff Summary

- `git status --short`: 9 modified tracked files; untracked PART 1 additions/evidence.
- `git diff --stat`: 9 files, **+300/−19**.
- `git diff --check`: clean.

## 12. Final Recommendation

**`DEFINE TARGETED IMPLEMENTATION PHASE`** — A/B/C are implemented and the full 1475-test suite is green, but the remaining gaps are **test-infrastructure** only: a controller-harness test for A's wiring, and a real-SQLite rollback/migration test for B and the 9→10 migration. Those need either an approved test dependency or an instrumented androidTest (device currently unavailable).

**Change-control confirmation:** No commit · No tag · No push · No release · No dependency/Gradle change · No cache copy/deletion · No Security Center bypass · No app-data/manual-DB change · No unrelated cleanup.

---

# Checkpoint 10 — Targeted Gap Closure

## 1. Executive Verdict

| Gap | Result |
|---|---|
| B — atomic-write **gating** (fresh vs fallback) | **PASS** — test added and green |
| A — controller-wiring test | **NOT ADDED** — needs a history-sink fake wired into the controller harness (larger than a small isolated test) |
| B — real-SQLite rollback | **NOT TESTABLE** (no SQLite test dependency) |
| Migration 9→10 real SQLite | **NOT TESTABLE** (no SQLite test dependency) |

## 2. Initial Working-Tree State

HEAD `f9b3b9d`; 9 tracked files modified; untracked PART 1 additions/reports preserved; `git diff --check` clean.

## 3. A — Controller-Wiring Gap

`AutoModeControllerTest` has a `controller(...)` harness (`:268`) that builds a real `AutoModeController`. A faithful A test would need a `historySink` fake wired into that controller plus driving `Connected` → `skipToNextServer`/`disconnectNow` and asserting one `QUALITY_REJECTED` with only `qualityRejectionCount` changed. That is a **harness extension**, not a small isolated test — **documented as a coverage gap**; the dedup primitive remains covered by `QualityRejectWindowTest` (9 tests).

## 4. B — Refresh Marker and Transaction Gap

- **Closed the gating gap:** added `ServerListUpdaterTest > a fresh network observation uses the combined atomic write and a fallback does not`. It uses a recording `Store` and asserts `replaceAllAndMarkSeen` is called for a fresh fetch and **not** for `fetchedFromCache = true`. `ServerListUpdaterTest` = **25 tests, 0 failures, 0 errors** [AUTOMATED TEST].
- The **real cross-table rollback** still cannot be exercised (no real-SQLite fixture) → **NOT TESTABLE**.

## 5. Migration 9→10 Gap

`Migration9to10Test` remains recording-proxy + schema-parity. A genuine v9 SQLite migration is **NOT TESTABLE** without a SQLite test dependency (not authorized).

## 6. Changes Made in Checkpoint 10

- `app/src/test/java/vn/unlimit/vpngate/repository/ServerListUpdaterTest.kt` — **+1 test only** (test-only change; no production change).

## 7. Tests Run and Exact Results

- `./gradlew --no-daemon :app:testProDebugUnitTest --tests "vn.unlimit.vpngate.repository.ServerListUpdaterTest"` → **BUILD SUCCESSFUL (52s)**; **25 tests, 0 failures, 0 errors, 0 skipped** [AUTOMATED TEST].
- (Checkpoint 9 full suite remains green: `:app` 1291/0/0/1, `:SoftEtherClient` 184/0/0/0.)

## 8. Remaining Limitations

- A wiring test (harness gap), B real-SQLite rollback, migration real-DB — all infrastructure-blocked.
- Device `DEVICE_UNAVAILABLE`.

## 9. Recommended Next Development Task

**PART 2 — Smart AutoConnect (score + one exploration).** Objective: replace the tie-break-only history influence with a bounded quality-window ranking (named weights/half-life/Laplace) + exactly one exploration attempt per run, preserving VPN-first routing, the 8 s timeout, Next Server identity, and one-candidate-per-IPv4.
- Files: `automode/AutoModeController.kt` (`sortedForAutoConnect`), `history/ServerHistoryTypes.kt` (view fields), new pure `AutoConnectScorer.kt` + tests.
- Acceptance: deterministic scoring; no-history = neutral (OD-6); exactly one exploration; existing routing matrix stays green; fresh install bit-identical.
- Tests: scorer unit tests (network-aware, recency, penalties), exploration-once, ordering-preserved.
- Risks: must not disturb the 1475-test green baseline; needs a build+test cycle.

## 10. Final Git Status and Diff

HEAD `f9b3b9d`; 9 tracked source files (+ the test file now also modified) — `git diff --stat` still small; `git diff --check` clean. No commit.

## 11. Change-Control Confirmation

No commit · No tag · No push · No release · No dependency/Gradle change · No cache copy/deletion · No Security Center bypass · No app-data/manual-DB change · No unrelated cleanup.
