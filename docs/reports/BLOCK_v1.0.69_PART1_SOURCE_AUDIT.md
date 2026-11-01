# BLOCK v1.0.69 PART 1 — Focused Source-Path Audit (READ-ONLY)

> **No code, test, Gradle, schema, cache, DB or evidence was modified. No commit/push.**
> Evidence labels: `[SOURCE EVIDENCE]` (file:line), `[INFERENCE]`, `[UNKNOWN]`.

## 1. Baseline and working-tree integrity

- Repo root `/c/Dev/SoftEthergai`; branch `main`; HEAD `f9b3b9d` `[SOURCE EVIDENCE]`.
- 6 PART 1 source files modified/uncommitted (`App.kt`, `db/AppDatabase.kt`, `history/HistoryCounters.kt`, `history/ServerHistoryDao.kt`, `history/ServerHistoryEntities.kt`, `history/ServerHistoryTypes.kt`), +112/−2.
- `QualityRejectWindow.kt`, `QualityRejectWindowTest.kt`, `Migration9to10Test.kt`, and both PART 1 reports are present.
- Room `version = 10` (`AppDatabase.kt:30`); `MIGRATION_9_10` registered in `App.kt`.
- No divergence from the prior checkpoint. Tree untouched.

## 2. Quality-Rejection Producer — actual call chain

- **Dataset-level rejection** (NOT per-server quality): `ServerListUpdater.update()` → `DatasetIntegrity.evaluate(usableRows, store.currentDataset(), capability)` (`ServerListUpdater.kt:340`); on `!verdict.accepted` it returns `Outcome.Preserved(DEGRADED_DATASET)` (`:341–347`). `DatasetIntegrity` yields `REJECT_DEGRADED` (`DatasetIntegrity.kt:148`, `:355`), judged by `sizeFloor` (`:278`) / `hasComparableBaseline` (`:336`). `[SOURCE EVIDENCE]` This path **never touches history**.
- **Per-server `QUALITY_REJECTED`**: declared (`ServerHistoryTypes.kt:93–94`) with **no producer** ("Reserved: no producer exists", `:46–50`). `qualityRejectionCount` is incremented only by `HistoryCounters.nextSummary` on a `QUALITY_REJECTED` record (`HistoryCounters.kt:107–110,124`). **No caller emits that record.** `[SOURCE EVIDENCE]`
- So the "quality rejection" the design means (user taps Try Next / disconnects during Connecting or within a post-connect window) is **not implemented anywhere**. `[SOURCE EVIDENCE]`
- History write path: `AutoModeController.recordHistory` (`:2958`) → `sink.record` (`:2983`, `withTimeoutOrNull(HISTORY_WRITE_TIMEOUT_MS=2_000)`) → `RoomServerHistorySink.record` (IO) → `ServerHistoryDao.record` (one `@Transaction`, `ServerHistoryDao.kt:107`). `[SOURCE EVIDENCE]`
- An unsuccessful network request is **not** a quality rejection: network outcomes are `SERVER_ABANDONED`/`PROTOCOL_ATTEMPT` (`:2863`), a separate event. `[SOURCE EVIDENCE]`

## 3. Quality-Rejection Deduplication — key and concurrency

- Available identity at the producer: the controller's current candidate `hostName`/`ip` and the attempt token `run|server|protocol` (`AutoModeController.kt:2970–2981`). `[SOURCE EVIDENCE]`
- Proposed key: `hostName` + the attempt/refresh context (the token already encodes run+server+protocol). This makes independent servers and independent runs distinct, so `QualityRejectWindow` never suppresses an unrelated rejection. `[INFERENCE]`
- `QualityRejectWindow.shouldRecord(key)` is `@Synchronized` (concurrency-safe) and uses an injectable clock; `QUALITY_REJECT_WINDOW_MS = 10_000L`. Keys are pruned only by overwrite (unbounded map) — **a bounded/pruning design is needed before production wiring**. `[SOURCE EVIDENCE]` (the class exists, unwired).
- **Missing information check:** the model *does* provide a stable identity + token, so equivalence is definable without broad suppression. No blocker here. `[INFERENCE]`

## 4. Refresh Pipeline — actual call chain

`ServerListUpdater.update()` (`:250`) `[SOURCE EVIDENCE]`:
1. `fetch()` (`:257`) — download+parse; on throw → `Preserved` (`:259`).
2. `fetchedFromCache()` (`:265`).
3. emergency rung (`:289–300`); empty → `Preserved(EMPTY_RESPONSE)` (`:302`).
4. `isUsable` filter (`:308`); empty → `Preserved(INVALID_DATASET)` (`:310`).
5. `dedupeForStorage` (`:320`).
6. `dropsOpenVpnProfiles` → `Preserved(DEGRADED_DATASET)` (`:327–329`).
7. `DatasetIntegrity.evaluate` (`:340`); `!accepted` → `Preserved(DEGRADED_DATASET)` (`:341–347`).
8. **`store.replaceAll(usableRows)` (`:350`)** — the only persistence point, reached **only after acceptance**.
9. snapshot save (`:354+`).

## 5. Refresh Marker — safe persistence point

- **Capture one marker** at the top of `update()` (before `fetch()`, ~`:255`). All `usableRows` share it. `[INFERENCE]` (the method has a single entry and a single accepted write).
- **Safe update point: immediately after `store.replaceAll(usableRows)` (`:350`)**, iterating exactly `usableRows` (the rows observed in the accepted dataset). Then `lastRefreshSeenAt = marker`; `firstListedAt = marker` **only if null**. `[SOURCE EVIDENCE + INFERENCE]`
- Absent servers are not in `usableRows` → untouched. Rejected/failed refreshes return before `:350` → never reach the update. `[SOURCE EVIDENCE]`
- **Atomicity gap:** the server table write (`replaceAll` → `mergeAll`) and the `server_history_summary` write are **different DAOs/tables**, so they are not in one transaction today. A single cross-DAO `@Transaction` (or `RoomDatabase.withTransaction`) would be needed to avoid a "table updated, markers not" window. `[SOURCE EVIDENCE]`
- New DAO needed: an idempotent `markSeen(hostNames, marker)` that sets `lastRefreshSeenAt` and `firstListedAt` (only when null). `[INFERENCE]`

## 6. OD-9 — Connected publication and persistence call chain

- **Connected is published FIRST**: `setState(AutoModeState.Connected(...))` at `AutoModeController.kt:2816–2824`. `[SOURCE EVIDENCE]`
- **Then** the success is recorded: `withContext(NonCancellable) { recordHistory(... CONNECTED ...) }` at `:2840–2851`. `[SOURCE EVIDENCE]`
- `recordHistory` is `suspend`, bounded by `withTimeoutOrNull(2_000)` (`:2983`); failures are swallowed+logged (`:2985–2992`). `[SOURCE EVIDENCE]`
- `stop()` (`:1445`) cancels `job` (`:1465`), calls `adapter.requestDisconnect()` (`:1481`), sets `Disconnected` (`:1482`) — **none of it suspends or waits for history**. `[SOURCE EVIDENCE]`
- Because the record runs in `NonCancellable`, a concurrent `stop()` **cannot cancel it**, and `stop()` **does not wait** for it. `[SOURCE EVIDENCE + INFERENCE]`

## 7. OD-9 — lifecycle and retry risks

- **Already satisfied:** publish-first ✓; survives `stop()` ✓ (`NonCancellable`); `stop()` not delayed ✓; final failure logged ✓; duplicate-safe ✓ (success token idempotency, `ServerHistoryDao.kt:109–114`). `[SOURCE EVIDENCE]`
- **Gap:** only **one** bounded attempt — OD-9 requires **up to three attempts with backoff**. `[SOURCE EVIDENCE]`
- **Lifecycle nuance:** the write is `NonCancellable` on the *run* coroutine, not an independently-owned job. It survives `stop()` because `NonCancellable`, but it is still tied to the run coroutine's dispatcher; a process death loses it. `[INFERENCE]` A truly independent owner (e.g., an application-scoped job) would be the strict OD-9 reading; the current `NonCancellable` is a pragmatic equivalent for the stop() path.
- Retrying cannot duplicate the count: the token check (`countToken`) short-circuits a second `CONNECTED` (`ServerHistoryDao.kt:109–114`). `[SOURCE EVIDENCE]`

## 8. Focused test plan (authorable without new deps)

- **Counters/OD-6** (pure `HistoryCounters`): per-network consecutive increment/reset; UNKNOWN distinct; `hasHistory = attempts>0`. — *authorable, not compiled/run.*
- **QualityRejectWindow** (pure): first/dedup/boundary/independent/concurrency. — *authored (`QualityRejectWindowTest`), not compiled/run.*
- **Refresh markers**: needs a fake store + a controllable marker; the `ServerListUpdater` has a `Store` seam (`:138 replaceAll`). — *authorable with a fake Store; not compiled/run.*
- **OD-9**: needs a fake sink that blocks then releases, plus a `stop()` call; the controller is heavy — likely needs an existing controller test harness. — *partially authorable; not compiled/run.*
- Cannot claim compiled/run/passed — the Gradle environment is blocked (§9).

## 9. Build/test environment limitations

- `D:\.gradle-v1069` exists but `./gradlew --version` **hangs (>240 s, SIGTERM)**; `D:\.gradle-v1068`'s journal is **Security-Center-blocked**. No build/test can run → `NOT TESTABLE`. No bypass attempted.
- `Migration9to10Test` remains a **recording-proxy SQL-contract + schema-parity** test, not a real SQLite migration test.

## 10. Exact proposed file change set (for the later implementation, NOT done)

- `history/ServerHistoryDao.kt` — add `markSeen(hostNames, marker)` (idempotent; `firstListedAt` only when null).
- `repository/ServerListUpdater.kt` — capture marker at `update()` start; call `markSeen` for `usableRows` right after `store.replaceAll` (`:350`), ideally in one cross-DAO transaction.
- `history/QualityRejectWindow.kt` — wire it in the controller's Try-Next/disconnect path; emit `QUALITY_REJECTED` when `shouldRecord` is true.
- `automode/AutoModeController.kt` — add the quality-reject producer; wrap `recordHistory(CONNECTED)` in a 3-attempt backoff loop (OD-9).
- Tests: `HistoryCounters*Test`, `QualityRejectWindowTest` (done), a refresh-marker test with a fake `Store`, an OD-9 retry test.

## 11. Unresolved questions and risks

- Cross-DAO transaction for the refresh-marker write (server table + history table) — needs a `withTransaction` seam; otherwise a partial update window exists. `[UNKNOWN]`
- `QualityRejectWindow` key: exact equivalence (hostName alone vs hostName+token) — token is the precise attempt context. `[INFERENCE]`
- OD-9 "independent job" vs `NonCancellable`: acceptable for stop() but not for process death. `[INFERENCE]`
- `QualityRejectWindow`'s map is unbounded — needs pruning before production. `[SOURCE EVIDENCE]`

## 12. Final recommendation

**`READY FOR IMPLEMENTATION PLAN`** — all three call chains are traced to concrete, safe insertion points; the only genuine gap in OD-9 is the 3-attempt backoff, and refresh markers have a clear safe point. Implementation is blocked on the Gradle environment for verification, not on design ambiguity.
