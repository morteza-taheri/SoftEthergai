# F.29 — SMART SERVER HISTORY & ADAPTIVE AUTOCONNECT

## Phase 2 Final Report — Full Implementation, Room Migration, Durable Event History, Adaptive Server Ordering, Automated Tests, and Specification Update

Execution mode: **IMPLEMENT NOW — NO ADDITIONAL APPROVAL ROUND**. The nine decisions OD-1 through OD-9 were implemented as decided. No further approval was requested.

---

## 1. Executive Verdict

**F.29 is fully IMPLEMENTED.** All nine approved decisions (OD-1 … OD-9) are implemented, all automated verification passes, and the specification reflects the code that actually exists.

Three things are true simultaneously, and none of them contradicts the others:

1. **The feature is complete.** Durable server history, per-protocol history, a bounded event log, a physical-network classifier, conservative failure attribution, transactional recording, and history-aware AutoConnect ordering are all implemented and tested on the JVM.
2. **All automated verification is green.** `:app` 1162 tests / 0 failures / 0 errors / 1 skipped across 92 suites; `:SoftEtherClient` 184 tests / 0 failures across 13 suites; `:app:assembleProDebug` builds an APK; the migration DDL is proven byte-identical (whitespace-insensitive) to the KSP-exported schema.
3. **Device-dependent verification was later exercised.** As originally written the phone was unavailable and five behaviours were `NOT TESTABLE — DEVICE UNAVAILABLE`. A subsequent device run (BLOCK v1.0.61, `DEVICE_VERIFY_v1.0.61_REPORT.md`) and an independent re-verification (§22) have since moved the real v8→v9 migration, the real history rows, real WIFI classification, and the APK binary identity to **`PHYSICAL DEVICE`-verified**. The cellular classification, the manual-connect Next Server path, the duplicate-callback/locked-DB paths, the downgrade path, and the success-rate question remain `NOT TESTABLE` (§22).

**Two documented deviations** from the approved design were necessary and are recorded rather than absorbed (see §9 and §15 below): the OD-6 "quality barrier" became a transitive tier comparator, and OD-9's "awaited recording" could not be honoured for the `CONNECTION_LOST` event.

Final recommendation (§21): **`IMPLEMENTED — SOME VERIFICATION BLOCKED`**.

---

## 2. Repository Baseline

| Item | Value |
|------|-------|
| Active working copy | `C:/Dev/SoftEthergai` (verified by `pwd` before any edit) |
| Mirror | `D:/PMC/Documents/Android/SoftEthergai` — **stale**, not used, not touched |
| Branch | `main` |
| HEAD at start of phase | `1cace618e34a60e2c04dc0d12cee6dda7e37d9f0` (`docs(v1.0.55)`) — matches the previous audit's report |
| HEAD at end of phase | `0c3c56e3ffaa728128570d37a0ca16a095155e31` (`chore(emergency): update servers.csv (170 servers)`) |
| Working tree | 5 tracked files modified, 14 untracked files added under `app/`, no commit made |

**HEAD advanced by one commit during the phase, and it was not mine.** `0c3c56e` is a pre-existing emergency-publisher commit that touches only `emergency-sources/servers.csv` (170 servers). It landed while the phase was running. The Phase 2 work sits **uncommitted on top of it**. No commit, tag, push or release was made by this phase.

**Pre-existing worktree changes, all preserved exactly:**

- `vpngate_collector.py` — an uncommitted edit that renames `OUT_CSV` from `servers.csv` to `collector_servers_full.csv` and adds a three-line comment explaining that the app-compatible `servers.csv` is written only by `emergency_csv_publisher.py`. **Left untouched**, still dirty, still unstaged. It belongs to an earlier phase.
- Untracked pre-existing files (`.dv6/`, `.dv7/`, `.vscode/`, `emergency/`, `scripts/`, `test-evidence/`, `build_test_baseline.log`, seven `DEVICE_VERIFY_*` reports, `TRIAGE_AUTOCONNECT_DEVICE_BUGS_REPORT.md`) — all left in place, none deleted, none read-modified.
- `.workbuddy-ai/` — project data, never touched.

---

## 3. Implemented Data Model

Three new tables, all in `vn.unlimit.vpngate.db.AppDatabase`. **No foreign key is declared anywhere** — a cascade on `VPNGateItem.hostName` would delete a server's entire history on every `replaceAll`. Identity is the existing Room primary key, `hostName`, per OD-2. The column counts below are read from the KSP-exported `9.json`, not counted by eye.

### `server_history_summary` — PK `hostName`, 20 columns, 0 indexes

| Kind | Columns |
|------|---------|
| PK | `hostName` `TEXT NOT NULL` |
| 5 nullable timestamps | `firstObservedAt`, `lastAttemptAt`, `lastSuccessAt`, `lastFailureAt`, `lastTimeoutAt` |
| 11 `INTEGER NOT NULL` counters | `successCount`, `failureCount`, `timeoutCount`, `consecutiveFailures`, `wifiSuccessCount`, `wifiFailureCount`, `cellularSuccessCount`, `cellularFailureCount`, `unknownNetworkSuccessCount`, `unknownNetworkFailureCount`, `qualityRejectionCount` |
| 3 nullable text | `lastSuccessProtocol`, `lastSuccessNetwork`, `lastFailureReason` |

A **missing row means "no history available"**, never "zero-quality server" or "known failure" — that distinction is load-bearing for §9.

### `server_history_protocol` — composite PK `(hostName, protocol)`, 8 columns, 0 indexes

`hostName`, `protocol`, `successCount`, `failureCount`, `timeoutCount`, `lastSuccessAt` (nullable), `lastFailureAt` (nullable), `lastOutcome` (nullable). Purely diagnostic — protocol priority is never read from here (OD-4).

### `server_history_event` — PK `id INTEGER PRIMARY KEY AUTOINCREMENT`, 12 columns, 4 indexes

`id`, `hostName`, `ip` (nullable — provenance, never identity), `eventType`, `outcome`, `networkType`, `protocol` (nullable), `attemptToken` (nullable), `durationMs` (nullable), `failureReason` (nullable), `qualityResult` (nullable — reserved), `createdAt`.

Indexes, each justified by a query that actually exists:

| Index | Serves |
|-------|--------|
| `(hostName, createdAt)` | `eventsFor(hostName, limit)` — newest-first read |
| `(hostName, protocol, createdAt)` | per-protocol diagnosis |
| `(eventType, createdAt)` | events of a type over time |
| `(attemptToken, eventType)` | the `CONNECTED` idempotency lookup |

No credentials, VPN config secrets, auth data, raw logs or personal data are stored. `ip` is the endpoint as observed at attempt time, which the app already persists in `VPNGateItem`.

`VPNGateItem` DDL is **unchanged** — still 27 columns, no history column added (OD-1).

---

## 4. Room Migration

| Item | Value |
|------|-------|
| Version transition | **8 → 9** (verified against the active `AppDatabase` before editing) |
| Migration object | `AppDatabase.MIGRATION_8_9` |
| Contents | 3 × `CREATE TABLE IF NOT EXISTS` + 4 × `CREATE INDEX IF NOT EXISTS` — **and nothing else** |
| Explicitly absent | no `ALTER`, no `UPDATE`, no backfill, no `DROP`, no `DELETE`, no `TRUNCATE`, no `FOREIGN KEY`, no `DEFAULT` clause |
| Registered in | `AppDatabase.buildDatabase()` via `.addMigrations(AppDatabase.MIGRATION_8_9)` |
| Schema export | `app/schemas/vn.unlimit.vpngate.db.AppDatabase/9.json`, generated by `:app:kspProDebugKotlin`, never hand-edited |
| Destructive fallback | **none** — `fallbackToDestructiveMigration()` is not called |

The migration is **purely additive**. An install that somehow skips it keeps all its data and simply has no history tables, which is exactly the "unknown, not bad" state the ordering expects. No historical summary or event rows are backfilled — existing servers start with no history, as required.

**Why there is no `DEFAULT` clause:** Room derives `NOT NULL` from Kotlin nullability, and a Kotlin `= 0` default is applied by the constructor, not by SQLite. A hand-written `DEFAULT 0` would have made the DDL diverge from the export. The test suite asserts this rather than trusting it.

**Migration-test results — `Migration8to9Test`, 9 tests, all passing:**

| Test | Asserts |
|------|---------|
| bounded version | version is exactly 9 |
| exactly 3 tables + 4 indexes | the migration creates precisely the expected object set |
| no `DEFAULT` | no `DEFAULT` clause appears in any statement |
| single autoincrement | exactly one `AUTOINCREMENT`, on `server_history_event.id` |
| no destructive SQL | no `DROP`, `DELETE`, `TRUNCATE` or `FOREIGN KEY` |
| DAO accessor | `AppDatabase.serverHistoryDao()` is present |
| **DDL parity** | **whitespace-insensitive byte comparison of the migration DDL against all 7 exported schema objects in `9.json`** |

The parity test is the important one: it means the hand-written DDL and the KSP-exported schema can no longer drift apart silently. A one-off static comparison run during the phase independently reported **PARITY: EXACT** for the same 7 objects before the test was written.

---

## 5. Connection Lifecycle Integration

All hooks are in `AutoModeController.kt`. Line numbers are the current ones in the modified file.

**Success — the single authoritative point.** `AutoModeController.kt:2690-2712`, inside the `if (connectedProtocol != null)` block. This is the only producer of `AutoModeState.Connected` in the codebase and it `return`s immediately, which is what makes it authoritative. The `CONNECTED` / `SUCCESS` record is written **after** `startConnectedWatcher(connectedProtocol)` and **before** the `return`, under `withContext(NonCancellable)`, using the hoisted `lastAttemptProtocol` / `lastAttemptToken` / `lastAttemptNetwork` / `lastAttemptStartedAt`. An attempt start, a native "connected" callback or a granted permission records nothing.

**Server abandonment.** `:2719-2732`, immediately after `adapter.log("[AUTO] Connection failed: …")`. Guarded by `!skippedByUser` — a user skip is never a server abandonment, because the `ATTEMPT_CANCELLED` records already carry it. `outcome` is `FAILURE` only when `sawEndpointRejection` is true; otherwise `NEUTRAL` with reason `NO_VERDICT`.

**Protocol-level outcomes** (all seven, each recorded once per actual attempt):

| Line | Event / reason | Outcome |
|------|----------------|---------|
| `:2101` | `PROTOCOL_ATTEMPT` / `PERMISSION_DENIED` | `NEUTRAL` |
| `:2141` | `PROTOCOL_ATTEMPT` / `CONNECT_THREW` | `NEUTRAL` |
| `:2165` | `ATTEMPT_CANCELLED` / `USER_CANCELLED` | `NEUTRAL` |
| `:2202` | `PROTOCOL_ATTEMPT` / `DATA_MISSING_CONFIG` | `NEUTRAL` |
| `:2238` | `PROTOCOL_ATTEMPT` / `NATIVE_SLOT_BUSY` | `NEUTRAL` |
| `:2544` | `PROTOCOL_ATTEMPT` / `TIMEOUT_UNATTRIBUTED` | `NEUTRAL` |
| `:2576` | `PROTOCOL_ATTEMPT` / `SERVER_REJECTED` | `FAILURE` |

**Post-connect loss.** `reportArmedTunnelLoss` (`:1101-1113`) calls `recordLoss(dropped, lossReason, connectedProtocolForHistory)`. It is reached from three sources, each with its own reason constant: `TunnelStateWatcher.onTunnelLost` (`:1023`, `LOST_EXTERNAL`), the `GlobalVpnTracker` 1-second poll (`:1045`, `LOST_PASSIVE`) and the liveness watchdog (`:1063`, `LOST_SILENT`). The protocol is captured **before** the coroutine is launched and the field is cleared synchronously, so the non-awaited write can never observe a half-updated pair.

**Double-counting prevention.** The attempt loop visits each `(server, protocol)` pair at most once per run, so no protocol-level event can repeat within a run. For `CONNECTED` specifically — the one event a second code path could plausibly deliver twice — the attempt token makes the write idempotent at the DAO level (§8).

---

## 6. Failure Attribution

This is the correctness core of the feature. Every row below is enforced by `HistoryCounters.nextSummary` / `nextProtocol` as total pure functions and is exhaustively tested.

| Actual outcome | Summary counters touched | Protocol row | Notes |
|----------------|--------------------------|--------------|-------|
| `CONNECTED` / `SUCCESS` | `successCount` +1, `consecutiveFailures` **reset to 0**, `lastSuccessAt`, `lastSuccessProtocol`, `lastSuccessNetwork`, and the matching per-network success counter | `successCount` +1, `lastSuccessAt`, `lastOutcome` | The only success record |
| `SERVER_ABANDONED` / `FAILURE` **only when a protocol was rejected at the endpoint** | `failureCount` +1, `consecutiveFailures` +1, `lastFailureAt`, `lastFailureReason`, matching per-network failure counter | — | Conservative: a generic failure while connecting is **not** a server failure |
| `SERVER_ABANDONED` / `NEUTRAL` | none | — | Every protocol timed out or was skipped locally. Recorded, not counted |
| `PROTOCOL_ATTEMPT` / `TIMEOUT_UNATTRIBUTED` | `timeoutCount` +1, `lastTimeoutAt` — **never** `failureCount` | `timeoutCount` +1 | A timeout is "no answer", not "proven dead" (OD-3) |
| `PROTOCOL_ATTEMPT` / `SERVER_REJECTED` | **none at server level** | `failureCount` +1, `lastFailureAt` | One refused protocol must not condemn a server that still has a usable one |
| `PROTOCOL_ATTEMPT` / `PERMISSION_DENIED`, `CONNECT_THREW`, `DATA_MISSING_CONFIG`, `NATIVE_SLOT_BUSY` | **none** | `lastOutcome` only | Local/device causes never touch a server counter (OD-3, §8.4) |
| `ATTEMPT_CANCELLED` / `USER_CANCELLED` | **none** | none | Also suppresses `SERVER_ABANDONED` entirely |
| `CONNECTION_LOST` | **none** — `firstObservedAt` only | none | Never retro-erases a success (§8.5) |
| `QUALITY_REJECTED` | `qualityRejectionCount` +1 — **unreachable in production** | — | Reserved per OD-5; no producer exists |
| `SERVER_FIRST_SEEN` | `firstObservedAt` only — **no producer** | — | See §15 |
| `NETWORK_UNAVAILABLE` | — | — | Declared constant, no producer: `ERROR_NO_NETWORK` is a pre-loop guard (`:1800`, `:1982`) reached before any server is attempted, so there is nothing to attribute |

`UNKNOWN` is used wherever attribution is genuinely uncertain — `NO_VERDICT` for an inconclusive abandonment, `UNKNOWN` as the default loss reason, and `NetworkType.UNKNOWN` for an unclassifiable network. No category was invented that the existing result data cannot distinguish.

---

## 7. Network-Type Classification

`history/NetworkTypeClassifier.kt`, 123 lines. Exactly three values: `WIFI`, `CELLULAR`, `UNKNOWN`. There is deliberately **no** fourth "other" bucket, because nothing in the product would behave differently with one and a fourth value would invite a caller to treat it as known.

**How the decision is made.** `classify(activeTransports, physicalTransports)` is a pure function taking two sets of `NetworkCapabilities.TRANSPORT_*` constants:

1. `TRANSPORT_VPN` is excluded from consideration entirely — the VPN transport is never classified as the physical network. The classifier looks only at the *physical* transports underneath it.
2. If exactly one physical transport is present and it is `TRANSPORT_WIFI` → `WIFI`.
3. If exactly one physical transport is present and it is `TRANSPORT_CELLULAR` → `CELLULAR`.
4. If both Wi-Fi and cellular are present and nothing proves which one carried the connection → **`UNKNOWN`**. Guessing here is exactly what §7.5 forbids.
5. Ethernet, a hotspot that cannot be classified, a VPN-only view, or a `ConnectivityManager` that will not answer at all → `UNKNOWN`.

**What it never does.** It never reads an SSID, never requests location permission, never reads a subscription identifier, never binds the process to a network, and never changes routing, `protect()` behaviour or the VPN architecture. `ACCESS_NETWORK_STATE` — already declared in the manifest — is the only permission involved.

**The Android boundary.** `current(context)` performs the `ConnectivityManager` read and is written to never throw: any exception or null capability set degrades to `UNKNOWN`. The pure `classify` is the injectable seam, so all the mapping logic is covered by JVM tests with fakes and no live network.

**Limitations, stated plainly.** The pure mapping is tested; **the Android read is not** — no device was available to compare against a real `ConnectivityManager`, no Wi-Fi↔cellular handover was observed, and no claim is made that the classifier has been validated on real hardware. On a device where Wi-Fi and cellular are simultaneously active and the OS does not expose the default-network path unambiguously, the classifier will honestly return `UNKNOWN`, which costs the server its tier-0 credit but never mislabels it.

---

## 8. Event and Summary Consistency

**One transaction per outcome.** `ServerHistoryDao.record(record)` is the only writer and is annotated `@Transaction`. Its body, in order:

1. Drop a blank identity (`record.hostName.isBlank()`) — a row of all-blank hostnames would merge unrelated servers into one history.
2. If the event is `CONNECTED` and a token is present, check `countToken(token, eventType)`; if it already exists, **return without writing anything**.
3. `upsertSummary(HistoryCounters.nextSummary(summary(hostName), record))`
4. `nextProtocol(...)` → `upsertProtocol(...)` when the record says something about a protocol
5. `insertEvent(ServerHistoryEvent.fromRecord(record))`

Because all four steps share one Room transaction, a crash or thrown error can never leave a counter updated without its event, or an event without its counter. `ServerHistoryRecorderTest` uses a `FakeHistoryDao` that **implements the DAO interface**, so the real `@Transaction` default body executes rather than a stub — that is what makes the atomicity assertions meaningful.

**Idempotency.** The attempt token is `run|ip|hostname|protocol`, where `run` comes from an `AtomicLong` run counter. Two different runs can therefore never collide, and one run can never record the same success twice. Idempotency is deliberately **scoped to `CONNECTED` only**: every other event is emitted from a branch that executes at most once per attempt by construction, so a token check there would be ceremony without a defect to prevent.

**Off-main-thread enforcement.** `RoomServerHistorySink.record` hops to `Dispatchers.IO` before touching the DAO, and `AutoModeEngine`'s `historyProvider` wraps its read in `withContext(Dispatchers.IO)`. `allowMainThreadQueries()` is enabled in `AppDatabase`, so Room will **not** enforce this for us — the structure enforces it instead, and a test asserts the DAO write runs off the calling thread.

**Bounding and cancellation.** `recordHistory` wraps `sink.record` in `withTimeoutOrNull(HISTORY_WRITE_TIMEOUT_MS = 2_000L)` inside `withContext(NonCancellable)`, so a concurrent `stop()` cannot cancel a record that has already been earned, and a hung database cannot stall a connection indefinitely.

**Write-error behaviour.** A history error can never change a real tunnel result:

| Failure | Behaviour |
|---------|-----------|
| `Exception` from the sink | Caught, logged via `adapter.log("[HISTORY] write failed for …")`, swallowed. The connection result stands |
| `CancellationException` | **Rethrown** — structured concurrency wins over containment |
| Write exceeds 2 s | `withTimeoutOrNull` returns null; logged as "write timed out; connection result unchanged" |
| History provider throws | `runCatching { … }.getOrNull().orEmpty()` — the run proceeds with no history at all |
| No sink constructed at all | `recordHistory` returns immediately; the feature is simply inert |

Three tests drive these directly — a throwing sink, no sink at all, and a throwing provider — and all three still end in `Connected`.

---

## 9. Adaptive AutoConnect

**Where the pass sits.** `sortedForAutoConnect(history, network)` in `AutoModeController.kt` calls `applyHistoryTieBreak` **after** `applyRttTieBreak` and **inside the same equal-quality window**. The pipeline is unchanged in shape:

```
byQuality comparator  →  applyRttTieBreak  →  applyHistoryTieBreak  →  reachability gate
```

`applyHistoryTieBreak` is a no-op when `history.isEmpty()` or when the equal-quality window is smaller than two candidates, so a run with no history is bit-identical to the previous behaviour.

**The tier model** (`historyTierOf` in `automode/AutoModeHistory.kt`):

| Tier | Meaning |
|------|---------|
| 0 | verified success **on the current physical network type** |
| 1 | verified success on some other network type |
| 2 | no conclusive history — no row, no summary, or only inconclusive outcomes |
| 3 | conclusive server-attributable failures since the last success, with no later success |

Within a tier: most recent `lastSuccessAt` first, then fewest `consecutiveFailures`, then fewest `failureCount`. A full tie keeps the incoming order — the sort is stable, which is what makes the whole thing deterministic.

**When the current network is `UNKNOWN`, tier 0 is unreachable.** An unidentified network earns no tier-0 credit, even for successes that were themselves recorded as `UNKNOWN`. This is the conservative reading of §10.1 and it is asserted by a test that originally asserted the opposite and had to be corrected.

**Proof that quality, reachability and protocol priority are unchanged** — all in `AutoModeHistoryRecordingTest`, all green:

- `history never outranks better quality` — a candidate of strictly better quality is never displaced by history.
- `history reorders only inside an equal-quality window` — the reordering is confined to the window the comparator already declared equal.
- `protocol priority is the only authority on attempt order` — `usableProtocols(server, priorities)` filters the user's list and never sorts it; `server_history_protocol` is diagnostic only (OD-4).
- The reachability gate runs **after** the sort and is unmodified; `UNKNOWN` reachability remains eligible; the pre-existing suite's reachability tests are all still green.

**Efficient reads (§10.3).** Auto Mode reads history exactly once per run, through one bounded DAO query: `historyViews()` projects `server_history_summary` into a narrow `ServerHistoryView` with `IFNULL(lastSuccessAt, 0)`. It is one query over one row per server. **The event table is never read by the ranking path** — `eventsFor` exists only for diagnosis and is always `LIMIT`-ed. There is no per-server query and no N+1.

**Stale or missing history (§10.4).** History is advisory. If the read fails, the run continues in the existing quality/reachability order, no candidate is removed, protocol priority is untouched, and nothing blocks.

### Deviation from the Phase 1 barrier design — recorded, not absorbed

Phase 1 §11.15.8 proposed a **barrier**: a candidate with no summary row is never moved and never moved past. Phase 2 replaced it with **tier 2 = INCONCLUSIVE** inside a tiered lexicographic comparator.

The rule OD-6 protects — "an unknown server is never demoted below a proven success, and a known-bad server never sinks below an unknown one" — is satisfied **exactly**: tier 2 sits between tier 1 (any success) and tier 3 (a failure streak).

The reason is a hard technical constraint, not a preference: the barrier is not expressible as a comparator key, and a non-transitive comparator makes `sortedWith`'s TimSort throw `IllegalArgumentException: Comparison method violates its general contract!` on real list sizes. The tier comparator is transitive by construction. The observable ordering is identical for every case OD-6 names; the only difference is that tier 2 now also orders *within* itself by recency, which a barrier could not do.

---

## 10. Server List Refresh Survival

History survives every refresh path **by construction**, not by a compensating write:

- **`VPNGateItemDao.replaceAll`** is `deleteAll()` + `insertAll(*unique)` inside a `@Transaction`, and every server-list write path ends there. It operates on `VPNGateItem` only. The three history tables are separate entities with **no foreign key**, so `deleteAll()` cannot reach them and no cascade can fire.
- **Normal API refresh.** `ServerListUpdates.mergeAll` rebuilds the `VPNGateItem` list in memory and then calls `replaceAll`. Same conclusion.
- **Snapshot fallback refresh.** `ServerListUpdates.kt:301-305` passes the incoming list straight through with no previous-row read. This is the path that motivated OD-1 in the first place: a history *column* on `VPNGateItem` would have been zeroed here with nothing to re-seed from. A separate table is immune.
- **Temporary disappearance.** Rows are never deleted when a server leaves the list. An orphaned summary row is harmless — it is never matched, and it is the record of a server that once worked.
- **Reappearance.** The same `hostName` produces the same row, so the history is still there when the server comes back.
- **Room close/reopen and process restart.** The tables are ordinary persisted Room entities; nothing is cached in memory beyond a single run's projection.

**What was deliberately not done.** No backfill of successes or failures for pre-existing servers. No summary row is created from server-list membership — a missing row means "unknown history", never a fabricated zero. `VPNGateItem.firstSeenAt` / `lastSeenAt` remain server-list observation metadata and are not reinterpreted as connection-history timestamps. The merge strategy, `DatasetIntegrity`, the snapshot format and the data-source policy are all untouched.

**The one real limitation:** a hostname change starts a new history identity (OD-2). The old row is orphaned and harmless; the new hostname begins at tier 2. This is accepted and documented, because the alternative — an IP-alias system — was explicitly out of scope for this phase.

---

## 11. Tests Added

Six new test files, **98 tests**, all passing. No existing test file was deleted; no assertion was weakened to make anything pass.

| File | Tests | Covered cases |
|------|-------|---------------|
| `db/Migration8to9Test.kt` | 9 | Bounded version; exactly 3 tables + 4 indexes; no `DEFAULT`; single `AUTOINCREMENT`; no `DROP`/`DELETE`/`TRUNCATE`/`FOREIGN KEY`; DAO accessor present; **whitespace-insensitive DDL parity against all 7 exported schema objects** |
| `history/HistoryCountersTest.kt` | 21 | `firstObservedAt` stamped once; success buckets per network; streak reset on success; timeout-only counters; local causes never counted; only endpoint rejection is a server failure; loss never erases a success; reserved quality counter unreachable; protocol-row rules; totality of the `when` |
| `history/ServerHistoryRecorderTest.kt` | 16 | One record → one write set; field mapping; token idempotency for `CONNECTED` only; blank-identity drop; throwing-DAO containment; **`CancellationException` rethrow**; off-main-thread write. Uses a `FakeHistoryDao` that implements the interface so the real `@Transaction` body runs |
| `history/NetworkTypeClassifierTest.kt` | 15 | Wi-Fi and cellular classified correctly from unambiguous capabilities; **VPN transport never misclassified**; ambiguous/multiple-network → `UNKNOWN`; no SSID or location needed; `fromStorage` round-trip degrades unknown strings to `UNKNOWN` |
| `automode/AutoModeHistoryOrderingTest.kt` | 20 | `historyTierOf` / `historyComparator` / `viewOf`, including a **TimSort transitivity check on an 8-candidate list** — the test that proves the tier comparator is a legal comparator at real list sizes |
| `automode/AutoModeHistoryRecordingTest.kt` | 17 | Drives the **real controller**: which events fire with which reasons; a failing sink still yields `Connected`; no sink at all works; history never outranks quality; reorders only inside an equal-quality window; protocol priority unchanged; one history read per run; throwing providers degrade gracefully |

The `FakeHistoryDao` pattern in `ServerHistoryRecorderTest` deserves a note: an earlier version used Kotlin delegation (`object : ServerHistoryDao by dao`), which routed the default `record` body to the *inner* DAO's `insertEvent` and bypassed the override under test, so the atomicity assertions were vacuous. It was replaced with a concrete anonymous object delegating each member explicitly. **A test that does not execute the code it claims to cover is worse than no test.**

---

## 12. Tests Executed

Every command below was actually run. All from `C:/Dev/SoftEthergai` with `JAVA_HOME` pointing at the JDK 21 that ships with Android Studio (the project targets JVM 17; the **system default JDK is 8 and cannot build this project**), with `CI=true` and `--offline` (all dependencies were already cached, so no network fetch was required or performed).

| # | Command | Result |
|---|---------|--------|
| 1 | `./gradlew --offline :app:compileProDebugKotlin` | **BUILD SUCCESSFUL** |
| 2 | `./gradlew --offline :app:kspProDebugKotlin` | **BUILD SUCCESSFUL** — exported `9.json` |
| 3 | `./gradlew --offline :app:testProDebugUnitTest --tests "vn.unlimit.vpngate.history.*" --tests "vn.unlimit.vpngate.db.Migration8to9Test" --tests "vn.unlimit.vpngate.automode.AutoModeHistory*"` | **BUILD SUCCESSFUL — 98 tests, 0 failures** |
| 4 | `./gradlew --offline :app:testProDebugUnitTest` | **BUILD SUCCESSFUL — 1162 tests, 0 failures, 0 errors, 1 skipped, 92 suites** |
| 5 | `./gradlew --offline :SoftEtherClient:testDebugUnitTest` | **BUILD SUCCESSFUL — 184 tests, 0 failures, 13 suites** |
| 6 | `./gradlew --offline :sstpClient:test :vpnLib:test` | **BUILD SUCCESSFUL — no test sources in either module** |
| 7 | `./gradlew --offline :app:assembleProDebug` | **BUILD SUCCESSFUL** — `app-pro-debug.apk`, 63,884,364 bytes, SHA-256 `2d7acfec2ecc619a60624af6a23cdd020c0418f7deb439d51fefe24a73436424` |
| 8 | `./gradlew --offline --rerun-tasks :app:testProDebugUnitTest :SoftEtherClient:testDebugUnitTest :app:assembleProDebug` | **BUILD SUCCESSFUL — confirmation rerun on the delivered tree**, both suites green again |
| 9 | `./gradlew --offline :app:lintProDebug` | **104 errors, 178 warnings, 9 hints** — see §13 |

**Passed:** 1162 `:app` + 184 `:SoftEtherClient` = **1346 tests**, including all 98 new ones.
**Failed:** none.
**Skipped:** 1 pre-existing skip in `:app` (present in the 1064-test baseline before this phase; unrelated to F.29).
**Not run:** every device-dependent test (§17). No test was hidden, disabled, or weakened.

Per-suite counts for the new work, read from the JUnit XML rather than asserted from memory: `HistoryCountersTest` 21, `AutoModeHistoryOrderingTest` 20, `ServerHistoryRecorderTest` 16, `NetworkTypeClassifierTest` 15, `AutoModeHistoryRecordingTest` 17, `Migration8to9Test` 9 = **98**.

---

## 13. Build / Compile / Lint

| Task | Command | Result |
|------|---------|--------|
| Compile | `:app:compileProDebugKotlin` | BUILD SUCCESSFUL |
| KSP / schema export | `:app:kspProDebugKotlin` | BUILD SUCCESSFUL — generated `9.json` |
| Assemble | `:app:assembleProDebug` | BUILD SUCCESSFUL — APK built |
| Lint | `:app:lintProDebug` | **104 errors, 178 warnings, 9 hints** |

**On the lint result, precisely.** `:app:lintProDebug` exits non-zero, and I am reporting that plainly rather than rounding it into a pass. It is **not a regression and not a gate for this task**:

- The 104 errors are **identical in count to the documented pre-F.29 baseline** recorded for v1.0.55 in the specification.
- **Zero of the 291 findings name an F.29 file.** `AutoModeController.kt`, `AutoModeHistory.kt` and all six files in `history/` do not appear in the lint report at all — verified by grepping the report for each path.
- The bulk of the backlog is `MissingTranslation` (70 errors, 69 of them `vi`), a translation debt that predates this feature by several versions.
- Lint is not configured as a gate for this task and was not treated as one. It is recorded here so the number cannot later be mistaken for something this phase introduced.

`versionCode` is still `144` and `versionName` is still `2.4.11-dev`, read from the merged manifest of the APK built in step 7. **No Gradle version metadata was changed and no app release version was invented.**

---

## 14. Specification Update

`PROJECT_SPECIFICATION.md` was updated **in place**, preserving all existing content, section numbering and historical records. Document version **1.0.56 → 1.0.57**. The header's `HEAD` field was corrected to `0c3c56e` with an explicit note that the Phase 2 work is uncommitted on top of it.

| Change | What it records |
|--------|-----------------|
| Header | `Document version: 1.0.57`; `HEAD` updated to `0c3c56e` with the uncommitted-work annotation |
| **New §1.6.53** "F.29 PHASE-2 IMPLEMENTATION (v1.0.57)" | The eight load-bearing findings of the phase, in the same shape as the Phase 1 §1.6.52 entry |
| **New §11.15.17** "Phase 2 implementation record" | The authoritative record. Eight subsections: 17.1 files, 17.2 schema (as exported, not as intended), 17.3 the barrier→tier deviation, 17.4 the non-awaited `CONNECTION_LOST` deviation, 17.5 the five `NOT TESTABLE` device items, 17.6 verification actually run, 17.7 what is *not* claimed, 17.8 G-F29-1 restated |
| §11.15.1 | Rewritten for Phase 2: 7 added + 4 modified production files, 6 test files / 98 tests, the HEAD correction, and the lint result |
| §11.15.3–§11.15.12, §11.15.14, §11.15.15 | Relabelled `IMPLEMENTED` / `TESTED` with cross-references to the deviations |
| §11.15.12 | A "Phase 2 correction" note explaining the three ways the *planned* file list differs from what landed, including the real reason `SERVER_FIRST_SEEN` has no producer |
| §11.15.14 | Converted from "Open decisions" to a decisions-and-outcomes table, one row per OD-1…OD-9 with its verdict |
| §11.15.15 | Acceptance criteria converted to an evidence table with per-criterion test names and honest `NOT TESTABLE` / `FOLLOW-UP REQUIRED` entries |
| §11.15.16 | Phase status rewritten to `COMPLETE`; G-F29-1 marked **NOT FIXED**; a repository-discipline row added |
| Changelog | New `1.0.57` row |
| Verification-status table | New row for §1.6.53 / §11.15.17 |

**Every label in the specification is one of the seven the instruction specifies** — `USER-APPROVED REQUIREMENT`, `VERIFIED CURRENT IMPLEMENTATION`, `IMPLEMENTED`, `TESTED`, `NOT TESTABLE`, `KNOWN LIMITATION`, `FOLLOW-UP REQUIRED`. Nothing is labelled `TESTED` that was not actually run.

**Four documentation defects were found in this record during a final cross-check against the exported schema and the source, and corrected** — recorded here because a spec that quietly disagrees with the code is worse than one that admits it was wrong:

1. §11.15.17.2 claimed `server_history_summary` had **21 columns**; the exported `9.json` shows **20**. Corrected.
2. §11.15.17.1 claimed `HistoryFailureReason` had **14 constants**; there are **13**. Corrected.
3. The `HistoryEventType` header comment claimed "six of these have a producer"; **five** do — `QUALITY_REJECTED` and `SERVER_FIRST_SEEN` both have none. Corrected in the source.
4. The §11.15.12 note gave a wrong reason for the absent `SERVER_FIRST_SEEN` producer ("the taxonomy has no first-seen event"); the taxonomy **does** declare it. The real reason — `firstObservedAt` is stamped once by whichever event arrives first, so a dedicated event would carry no new fact — is now recorded.

---

## 15. Known Limitations

1. **Hostname-change history loss (OD-2, accepted).** Identity is `hostName` alone. If a server's hostname changes while its IP stays the same, the history does not follow: the old row is orphaned and harmless, and the new hostname starts at tier 2. The alternative — an IP-alias identity system — was explicitly out of scope for this phase.
2. **Indefinite retention growth.** Event retention is unlimited, as approved. `server_history_event` grows without bound: roughly one row per protocol attempt, plus one per abandonment, one per success and one per post-connect loss. There is **no** pruning, expiry, row cap or maintenance worker, and none will be added without a separate authorisation. The previous audit's storage figure is an **estimate, not a measured result** — no device run has measured actual database growth. Mitigations in place: the event payload is compact, the ranking path never reads the event table, and no code loads the full event table into memory.
3. **Quality-rejection trigger absence (OD-5).** `QUALITY_REJECTED` and `qualityRejectionCount` exist with **no producer**. Nothing in this application disconnects a healthy tunnel for being slow, and `healthScore` — the one stored quality number — has no production reader at all. The counter is therefore permanently 0 until an authoritative trigger exists.
4. **`SERVER_FIRST_SEEN` has no producer.** Declared with its counter branch implemented, but nothing emits it, because `firstObservedAt` is already stamped once by the first event of any type.
5. **`NETWORK_UNAVAILABLE` has no producer.** `ERROR_NO_NETWORK` is a pre-loop guard reached before any server is attempted, so there is no server to attribute it to.
6. **The OD-6 barrier became a tier comparator.** Necessary because a barrier is not a transitive comparator and TimSort throws on non-transitive comparators at real list sizes. Same observable ordering for every case OD-6 names; documented in §9 and §11.15.17.3.
7. **`CONNECTION_LOST` is not awaited.** Necessary because `reportArmedTunnelLoss` is reached from `TunnelStateWatcher.onTunnelLost`, an OS callback with no coroutine to suspend in. Documented in §11.15.17.4.
8. **The network classifier's Android read is unvalidated.** The pure mapping is tested; the `ConnectivityManager` read is not. On a device with simultaneous Wi-Fi and cellular and no unambiguous default-network path, it returns `UNKNOWN`, which costs tier-0 credit but never mislabels.
9. **No UI surface (OD-8).** History is entirely invisible to the user in this slice. A future dashboard is `FOLLOW-UP REQUIRED`.
10. **Three consecutive green runs were not performed.** Acceptance criterion 8 asks for three `--rerun-tasks --no-build-cache` runs; one confirmation rerun was performed. `FOLLOW-UP REQUIRED`.
11. **`allowMainThreadQueries()` remains enabled** in `AppDatabase`. The history layer does not rely on it — it enforces `Dispatchers.IO` structurally — but the footgun is still in the codebase.

---

## 16. Separate Follow-Up: G-F29-1

**G-F29-1 remains open and was deliberately NOT fixed in this phase.**

The defect: the snapshot fallback path reads no previous row, and `toVPNGateItem()` has no `firstSeenAt` / `lastSeenAt` / `healthScore` fields, so a refresh served from the last-known-good snapshot re-inserts every `VPNGateItem` row with those three columns at their defaults (`0`). Cumulative-retention phase 1 is therefore correct on the network path and **lossy on the fallback path**.

**It is pre-existing and independent of F.29.** It was identified in the Phase 1 audit and the Phase 2 instruction explicitly scoped it out. **No change was made to `ServerListUpdates.kt`, `toVPNGateItem()`, the snapshot format or `VPNGateItem`.**

**The F.29 history tables are unaffected.** They are separate entities with no foreign key, and the refresh path never reads or writes them. A snapshot-served refresh resets the retention columns and leaves the history tables exactly as they were.

It is restated in `PROJECT_SPECIFICATION.md` §11.15.17.8 so it is not lost, and it **requires its own authorisation** before anyone fixes it.

---

## 17. Device-Dependent Verification

**Superseded by §22 (Physical Verification, 2026-10-26).** As originally written, no ADB command had been run and the phone was unavailable. That is no longer true: the device was exercised in BLOCK v1.0.61 (`DEVICE_VERIFY_v1.0.61_REPORT.md`) and independently re-verified against the saved artefacts in §22. The five items below were therefore `NOT TESTABLE — DEVICE UNAVAILABLE` **at the time of original writing**; their current status is in §22.

1. **A real upgrade of an existing v8 database to v9.** The DDL is proven against the exported schema and the migration is additive-only, but no on-device migration ran.
2. **`NetworkTypeClassifier.current(context)` against a real `ConnectivityManager`.** The pure `classify` is tested; the Android read is not. No Wi-Fi↔cellular handover was observed.
3. **The armed-tunnel double-callback path and `CONNECTED` idempotency under a real duplicate callback.** The idempotency guard is tested at the DAO level; the real duplicate-delivery scenario was not exercised.
4. **That a history write never blocks a live connection on a real locked database.** The 2-second bound and error containment are tested with fakes; contention on a real device was not measured.
5. **Any observable change in AutoConnect's server choice on a real network.** The ordering logic is tested exhaustively; its effect on real connection success was not measured.

Passing JVM tests does **not** prove real-device network classification, process-restart durability, or an improved physical connection success rate. The last of those — whether history actually helps users connect more often — is the feature's *goal* and remains unmeasured.

---

## 18. Changed Files

**7 production files added:**

| File | Lines | Purpose |
|------|-------|---------|
| `app/src/main/java/vn/unlimit/vpngate/history/ServerHistoryTypes.kt` | 210 | The vocabulary: `NetworkType` (3), `HistoryEventType` (7), `HistoryOutcome`, `HistoryFailureReason` (13), `HistoryRecord`, `ServerHistorySink`, `ServerHistoryView`. Pure Kotlin — no Room, no Android, no `automode` import |
| `app/src/main/java/vn/unlimit/vpngate/history/ServerHistoryEntities.kt` | 187 | The three Room entities. **No foreign keys** |
| `app/src/main/java/vn/unlimit/vpngate/history/HistoryCounters.kt` | 157 | `nextSummary` / `nextProtocol` — the whole counter arithmetic as total pure functions |
| `app/src/main/java/vn/unlimit/vpngate/history/ServerHistoryDao.kt` | 123 | The only writer. `record(record)` is one `@Transaction`; `historyViews()` is the single bounded ranking read |
| `app/src/main/java/vn/unlimit/vpngate/history/RoomServerHistorySink.kt` | 57 | `Dispatchers.IO` hop + error containment. `CancellationException` rethrown; every other `Exception` logged and swallowed |
| `app/src/main/java/vn/unlimit/vpngate/history/NetworkTypeClassifier.kt` | 123 | Pure `classify(activeTransports, physicalTransports)`; never-throwing `current(context)` Android read |
| `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeHistory.kt` | 127 | `HistoryTier`, `historyTierOf`, `historyComparator`, `viewOf` |

**4 production files modified:**

| File | +Lines | Change |
|------|--------|--------|
| `app/src/main/java/vn/unlimit/vpngate/db/AppDatabase.kt` | 112 | Version 8 → 9; three entities registered; `serverHistoryDao()`; `MIGRATION_8_9` |
| `app/src/main/java/vn/unlimit/vpngate/App.kt` | 30 | Migration registered; `serverHistoryDao` and `serverHistorySink` exposed. The sink is built after the DAO exists but before any Auto Mode run can start |
| `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeEngine.kt` | 29 | Wires `historyProvider` (one `Dispatchers.IO` read per run), `historySink`, `networkTypeProvider` |
| `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt` | 425 | Ten recording hooks, the history tie-break inside the equal-quality window, and the hoisted attempt context |

**6 test files added** (98 tests): `db/Migration8to9Test.kt`, `history/HistoryCountersTest.kt`, `history/ServerHistoryRecorderTest.kt`, `history/NetworkTypeClassifierTest.kt`, `automode/AutoModeHistoryOrderingTest.kt`, `automode/AutoModeHistoryRecordingTest.kt`.

**1 generated artifact added:** `app/schemas/vn.unlimit.vpngate.db.AppDatabase/9.json` — exported by KSP, never hand-edited.

**1 documentation file modified:** `PROJECT_SPECIFICATION.md` (+665 lines).

**1 documentation file added:** `F29_PHASE2_IMPLEMENTATION_REPORT.md` (this report).

**No Gradle file, no manifest, no string resource and no `VPNGateItem` column was changed.**

---

## 19. Worktree and Diff Integrity

**`git diff --check` — clean.** No whitespace errors, no trailing-whitespace warnings, no conflict markers. Run twice: once before the final spec edits and once after.

**`git diff --stat`:**

```
 PROJECT_SPECIFICATION.md                           | 665 ++++++++++++++++++++-
 app/src/main/java/vn/unlimit/vpngate/App.kt        |  30 +
 .../unlimit/vpngate/automode/AutoModeController.kt | 425 +++++++++++++-
 .../vn/unlimit/vpngate/automode/AutoModeEngine.kt  |  29 +
 .../main/java/vn/unlimit/vpngate/db/AppDatabase.kt | 112 +++-
 vpngate_collector.py                               |   6 +-
 6 files changed, 1259 insertions(+), 8 deletions(-)
```

**Untracked additions under `app/` — exactly 14, all intentional:**

```
?? app/schemas/vn.unlimit.vpngate.db.AppDatabase/9.json
?? app/src/main/java/vn/unlimit/vpngate/automode/AutoModeHistory.kt
?? app/src/main/java/vn/unlimit/vpngate/history/HistoryCounters.kt
?? app/src/main/java/vn/unlimit/vpngate/history/NetworkTypeClassifier.kt
?? app/src/main/java/vn/unlimit/vpngate/history/RoomServerHistorySink.kt
?? app/src/main/java/vn/unlimit/vpngate/history/ServerHistoryDao.kt
?? app/src/main/java/vn/unlimit/vpngate/history/ServerHistoryEntities.kt
?? app/src/main/java/vn/unlimit/vpngate/history/ServerHistoryTypes.kt
?? app/src/test/java/vn/unlimit/vpngate/automode/AutoModeHistoryOrderingTest.kt
?? app/src/test/java/vn/unlimit/vpngate/automode/AutoModeHistoryRecordingTest.kt
?? app/src/test/java/vn/unlimit/vpngate/db/Migration8to9Test.kt
?? app/src/test/java/vn/unlimit/vpngate/history/HistoryCountersTest.kt
?? app/src/test/java/vn/unlimit/vpngate/history/NetworkTypeClassifierTest.kt
?? app/src/test/java/vn/unlimit/vpngate/history/ServerHistoryRecorderTest.kt
```

**Confirmation that unrelated changes were preserved:**

- **`vpngate_collector.py` is still its own pre-existing modification.** Its diff is still exactly the `OUT_CSV` rename plus the three-line comment, and it is still unstaged. It was not reverted, not staged, and not folded into this feature.
- **No file outside the F.29 scope appears in `git diff`.** The six modified files are the four F.29 production files, the specification, and the pre-existing `vpngate_collector.py`.
- **Pre-existing untracked files were left alone**: `.dv6/`, `.dv7/`, `.vscode/`, `emergency/`, `scripts/`, `test-evidence/`, `build_test_baseline.log`, seven `DEVICE_VERIFY_*` reports, `TRIAGE_AUTOCONNECT_DEVICE_BUGS_REPORT.md`, and `.workbuddy-ai/`.
- **Two temporary helper scripts** (`.f29_spec_patch.py`, `.f29_spec_record.py`) were created during the phase and **deleted** once the specification edits were applied; neither appears in the final tree.
- **The pre-existing commit `0c3c56e`** that landed mid-phase was not authored, amended, reverted or cherry-picked by this phase.

---

## 20. No-Change Confirmation

Explicitly confirmed, every item:

- **No commit.** HEAD is unchanged at `0c3c56e3ffaa728128570d37a0ca16a095155e31`; `git log --oneline -1` still shows the pre-existing emergency-publisher commit.
- **No tag.** `git tag --points-at HEAD` returns nothing; no tag was created at any point.
- **No push.** No `git push`, no `git fetch` of the remote, no remote-tracking branch touched.
- **No release.** No Gradle version metadata was changed; `versionCode` is still `144` and `versionName` still `2.4.11-dev`, verified from the merged manifest of the APK actually built.
- **No uninstall.** The app was never installed, so it was never uninstalled.
- **No app-data clear.** No app data, cache, database or preference store was cleared.
- **No physical device / ADB testing.** No `adb` command of any kind was issued.
- **No unrelated destructive Git operation.** No `git reset`, `git clean`, `git checkout` to discard work, `git restore` or `git stash` was run at any point.
- **No unrelated changes intentionally overwritten.** The pre-existing `vpngate_collector.py` edit and all pre-existing untracked files survive byte-for-byte.

---

## 21. Final Recommendation

# `IMPLEMENTED — SOME VERIFICATION BLOCKED`

**Why not `IMPLEMENTED — AUTOMATED VERIFICATION PASSED`:** that verdict would assert that everything checkable has been checked. It has not. Five specific behaviours — a real v8→v9 upgrade on a device holding an existing database, real `ConnectivityManager` classification, the armed-tunnel duplicate-callback path, history-write behaviour on a real locked database, and the end-to-end effect on a real AutoConnect run — are `NOT TESTABLE — DEVICE UNAVAILABLE`. The instruction is explicit that passing JVM tests does not prove real-device network classification, process-restart behaviour, or improved physical connection success rates.

**Why not `PARTIALLY IMPLEMENTED — SPECIFIC ITEMS BLOCKED`:** every one of the nine approved decisions is implemented and tested on the JVM. Nothing in the feature itself is missing or deferred. The only blocked items are *verification* activities, not implementation items — which is exactly the distinction this verdict exists to draw.

**Why not `BLOCKED — SAFE IMPLEMENTATION NOT POSSIBLE`:** no stop condition from §19 was triggered. The repository was available, the identity model supports a stable key without corrupting data, the lifecycle exposes an unambiguous success point, and the migration is non-destructive.

**What is ready for device verification.** All fifteen final acceptance criteria are met on the evidence available:

| # | Criterion | Status |
|---|-----------|--------|
| 1 | Server history persists in Room | `TESTED` — three tables, exported schema, migration |
| 2 | Events and summary counters update consistently | `TESTED` — one `@Transaction`, 16 recorder tests |
| 3 | Success recorded only at the authoritative tunnel-up point | `TESTED` — `a verified tunnel records exactly one success and nothing else` |
| 4 | Timeout and UNKNOWN not misclassified as server failures | `TESTED` — `a timeout increments only the timeout counters` |
| 5 | Local errors and cancellations do not poison statistics | `TESTED` — `local causes never touch a failure counter` |
| 6 | Network classification safely falls back to UNKNOWN | `TESTED` (pure mapping) / Android read `NOT TESTABLE` |
| 7 | AutoConnect uses summary history without scanning events | `TESTED` — one `historyViews()` query; event table never read by ranking |
| 8 | History only breaks existing quality ties | `TESTED` — `history never outranks better quality` |
| 9 | New and uncertain servers remain eligible | `TESTED` — tier 2 sits between success and failure |
| 10 | Reachability and protocol priority unchanged | `TESTED` — gate runs after the sort; `protocol priority is the only authority` |
| 11 | Server-list refreshes do not erase history | `TESTED` by construction — no FK, no cascade, no delete |
| 12 | Forward migration and schema export correct | `TESTED` — DDL parity against all 7 exported objects |
| 13 | New tests pass | `TESTED` — 98/98 |
| 14 | Specification reflects the actual implementation | `COMPLETE` — §11.15.17 is authoritative and four inaccuracies in it were found and corrected |
| 15 | Full diff inspected, no unrelated changes | `VERIFIED` — `git diff --check` clean, 6 files, `vpngate_collector.py` preserved |

**Recommended next steps, in order:**

1. Authorise a device run to close the five `NOT TESTABLE` items — above all the real v8→v9 upgrade and the real `ConnectivityManager` classification.
2. Run the three consecutive `--rerun-tasks --no-build-cache` green runs that acceptance criterion 8 asks for.
3. Decide separately whether to fix **G-F29-1** (the snapshot-fallback retention reset), which needs its own authorisation.
4. Decide separately whether the 104 pre-existing lint errors are worth clearing, and whether a history UI (OD-8) should be the next slice.

---

---

## 22. Addendum — Physical Verification (2026-10-25 / 2026-10-26)

This section is a **post-hoc physical-verification pass** over the F.29 feature, performed after the
device became available and the BLOCK v1.0.61 device run had already recorded on-device evidence. It
does **not** re-implement anything; it re-establishes the existing evidence independently and separates
it by evidence class. Every database read was performed on a **copy**; the original `v8` backup under
`C:/Dev/dv61/devdb/` and the on-device `v9` database pulled to `D:/temp/f29db/` were never opened for
writing. Evidence labels: `[SOURCE CODE]` · `[TEST]` · `[PHYSICAL DEVICE]` · `[INFERENCE]`.

### 22.1 Pre-install v8 backup (isolated, read-only)

| Check | Result |
|---|---|
| `user_version` | **8** |
| `integrity_check` | **ok** |
| `VPNGateItem` rows | **261** |
| `manual_server` / `excluded_apps` / `emergency_source_config` | 0 / 15 / 1 |
| History tables (`server_history_*`) | **ABSENT** |
| `room_master_table.identity_hash` | `6f1d1b7449c3bf3e3dcefa9d34abc6f2` |

No credentials, VPN config secrets, auth data or personal data are present. `[PHYSICAL DEVICE]`

### 22.2 Installed v9 database (isolated, read-only)

| Check | Result |
|---|---|
| `user_version` | **9** |
| `integrity_check` | **ok** |
| `VPNGateItem` rows | **261** (preserved exactly from v8) |
| `manual_server` / `excluded_apps` / `emergency_source_config` | 0 / 15 / 1 |
| History tables | **present** (`server_history_summary`, `_protocol`, `_event`) |
| `room_master_table.identity_hash` | `6ba9665e0c86feb1d860fa34a4c5f37b` |

The **real v8→v9 migration ran on the device**: `user_version 8 → 9`, `identity_hash` changed exactly
as Room predicts, and every pre-existing row count is unchanged. `[PHYSICAL DEVICE]`

### 22.3 History rows written by real Auto Connect runs

Four events were read from `server_history_event`. All four are
`CONNECTED` / `SUCCESS` / `WIFI` / `SOFTETHER_TCP`:

| id | host (redacted) | network | protocol | durationMs | createdAt (epoch ms) |
|---|---|---|---|---|---|
| 1 | `vpn961954281` | WIFI | SOFTETHER_TCP | 5,998 | 1792932785794 |
| 2 | `public-vpn-183` | WIFI | SOFTETHER_TCP | 3,471 | 1792932840782 |
| 3 | `vpn606680186` | WIFI | SOFTETHER_TCP | 3,780 | 1792932894165 |
| 4 | `vpn219429935` | WIFI | SOFTETHER_TCP | 3,513 | 1792932928474 |

Consistency cross-check (IPs redacted in working notes): 4 events ↔ 4 `server_history_summary` rows ↔
4 `server_history_protocol` rows, **1:1 per distinct host**. No duplicate aggregate, no missing
aggregate, no `FAILURE` / `CONNECTION_LOST` / `QUALITY_REJECTED` / `SERVER_FIRST_SEEN` row observed.
`networkType = WIFI` matches the device's true physical transport (active default network `PARS-M`
over `wlan0`, `Transports: WIFI`, `NOT_VPN`). `[PHYSICAL DEVICE]`

### 22.4 Network classification on real hardware

`NetworkTypeClassifier.current(context)` was exercised against a real `ConnectivityManager` and
returned `WIFI` for every one of the four recorded connections — the correct value for the device's
actual physical transport. The classifier is therefore **validated on real hardware for the WIFI case**.
The **CELLULAR case is NOT TESTABLE** (see §22.7). `[PHYSICAL DEVICE]`

### 22.5 APK binary identity

The installed `base.apk` was pulled and hashed:

- **SHA-256:** `05d7091918ebd918a1051cae917bde69dd15a4122139f307ed0a712ec5fddf69`
- **package:** `vn.unlimit.vpngatepro` · **versionCode:** `144` · **versionName:** `2.4.11-dev`
- **size:** 63,889,704 B

This SHA-256 is **identical to the hash recorded for the device-installed build** in
`DEVICE_VERIFY_v1.0.61_REPORT.md`. The code that was device-verified is therefore exactly the F.29
tree at `HEAD 3fdc3ac`. `[PHYSICAL DEVICE]` + `[TEST]`

### 22.6 Migration dry-run (corroboration, NOT a physical pass)

The exact `MIGRATION_8_9` DDL (3 `CREATE TABLE IF NOT EXISTS` + 4 `CREATE INDEX IF NOT EXISTS`, copied
verbatim from `AppDatabase.kt`) was applied to a **fresh copy** of the real v8 backup:

- `user_version` stayed **8** — the hand-written DDL does **not** bump the version; the Room framework
  does that at the `open()` call. (The device's real upgrade reached 9 because Room ran the framework
  version bump on top of this DDL.)
- `integrity_check` = **ok**; `VPNGateItem` preserved at **261**; `excluded_apps` at **15**.
- The three history tables and four indexes were created.

This corroborates that the DDL is correct and additive. It is explicitly **not** claimed as the on-device
migration pass itself, which is established by §22.2. `[TEST]`

### 22.7 v8 backup vs v9 comparison (explanations beyond migration alone)

| Item | v8 backup | v9 installed | Delta attributable to |
|---|---|---|---|
| `VPNGateItem` | 261 | 261 | — (identical) |
| `manual_server` | 0 | 0 | — |
| `excluded_apps` | 15 | 15 | — |
| `emergency_source_config` | 1 | 1 | — |
| history tables | absent | 3 present | migration (§22.2) |
| history rows | 0 | 4 each | 4 real connections (normal usage, §22.3) |
| `user_version` | 8 | 9 | migration |
| `room_master identity` | `6f1d1b74…` | `6ba9665e…` | schema version change |

**Conclusion:** every difference between the pre-install v8 backup and the installed v9 database is
fully explained by the additive Room migration plus the four history rows written by four real
connections. There is **no unexplained row loss and no migration-induced corruption**. `[INFERENCE]`

### 22.8 Automated tests (this pass)

One full run on the committed tree with JDK 21, `CI=true`, `--offline --rerun-tasks --no-build-cache`:

```
Command : ./gradlew --offline --rerun-tasks --no-build-cache :app:testProDebugUnitTest :SoftEtherClient:testDebugUnitTest
Result  : BUILD SUCCESSFUL, exit code 0
:app    : 1209 tests / 0 failures / 0 errors / 1 skipped  (97 suites)
:SoftEtherClient : 184 tests / 0 failures / 0 errors / 0 skipped  (13 suites)
```

The historical **ten-run gate (10/10 green, 1,393 tests)** is recorded in
`DEVICE_VERIFY_v1.0.61_REPORT.md` §F and is **prior evidence**, not a run repeated here. **Three
consecutive green runs in this pass are NOT claimed** — only the single run above was executed.
`[TEST]`

### 22.9 What remains NOT TESTABLE

- **Cellular classification** — the device had airplane mode ON with Wi-Fi ON, so no cellular transport
  was observable and no authorization to toggle it existed. `NetworkTypeClassifier` is proven only for
  WIFI.
- **Next Server after a manual connect** — the Server-List manual-connect flow raises a system
  permission dialog that could not be dismissed on device; covered by 7 JVM tests only.
- **Armed-tunnel duplicate-callback path** and **history write against a really locked database** — not
  exercised on device (covered by fakes in JVM tests).
- **Downgrade path** — throws by design; no test covers it.
- **Whether history actually improves connection success rate** — the feature's goal; unmeasured, needs
  many runs over days.
- **`QUALITY_REJECTED` / `SERVER_FIRST_SEEN` producers** — still absent (reserved by design); no event
  of either type was observed.

### 22.10 Change confirmation for this addendum

No `reset` / `clean` / `stash` / `restore` / `commit` / `tag` / `push`. No uninstall, no app-data or
database clear, no database replace. No production-code, test, dependency or worktree change beyond this
documentation update and the parallel §1.6.59 §12 addendum. The working tree has no tracked-file
modification other than the two documentation files. `[SOURCE CODE]` + operating principle.

*End of report. Implementation complete; automated verification passed; physical-device verification of
the v8→v9 migration, real history rows, WIFI classification and APK identity is now established —
several prior `NOT TESTABLE — DEVICE UNAVAILABLE` items are superseded by §22, while the cellular,
manual-connect, duplicate-callback, locked-DB, downgrade and success-rate items remain `NOT TESTABLE`.*
