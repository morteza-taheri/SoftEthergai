# BLOCK v1.0.69 — PART 0 Status Audit, Assumptions, Design (read-only)

> **Status: PART 0 complete — STOPPED for operator approval before ANY code change.**
> Evidence labels: `[SOURCE CODE]` (file:line), `[TEST]`, `[PHYSICAL DEVICE]`, `[INFERENCE]`, `[UNKNOWN]`.
> Device: **DEVICE_UNAVAILABLE** (no device checks performed).

## State at start

| Item | Value |
|---|---|
| HEAD | `0782542` (real; the brief's `4ba0121` is one commit behind) |
| Uncommitted (tracked) | **v1.0.68 PART A** (3 files: `AutoModeScreen.kt`, `AutoModeViewModel.kt`, `AutoModeDisplayStateTest.kt`) — preserved, **not** part of this audit |
| Untracked | 20 files (reports/APK/evidence/env) |
| Room version | **9** (`AppDatabase.kt:30`) |
| Free space | **C: 12.0 GB** (at the 12 GB threshold), **D: 227 GB** |
| Proposed `GRADLE_USER_HOME` | `D:\.gradle-v1069` (safer than C: at the threshold); one `git archive` copy at a time, deleted after (path listed before deletion) |

---

## 0.1 The six operator requirements — real state [SOURCE CODE]

| # | Requirement | Where it lives | Verdict |
|---|---|---|---|
| 1 | Past successful connection (yes/no) | `server_history_summary.successCount` (`ServerHistoryEntities.kt:60`); incremented on `CONNECTED` (`HistoryCounters.kt:41–54`) | **PRESENT** |
| 2 | New vs seen before | `firstObservedAt` stamped once (`HistoryCounters.kt:36,39`); **no `lastRefreshSeenAt` / refresh marker** (grep: absent) | **PARTIAL** — only "any history vs none" |
| 3 | Which network the successes came from | `lastSuccessNetwork` (`ServerHistoryEntities.kt:72`), set on `CONNECTED` (`HistoryCounters.kt:49`) | **PRESENT** |
| 4 | Success counts per network | `wifiSuccessCount` / `cellularSuccessCount` / `unknownNetworkSuccessCount` (`ServerHistoryEntities.kt:75–79`; `HistoryCounters.kt:50–53`; read via `successesOn` `ServerHistoryTypes.kt:217–226`) | **PRESENT** |
| 5 | Rejected for poor quality and how many times | `qualityRejectionCount` column (`ServerHistoryEntities.kt:87`); counter branch (`HistoryCounters.kt:107–110`); **no producer** (`ServerHistoryTypes.kt:46–50,94`) | **ABSENT** — always 0 |
| 6 | Which protocol connected | `lastSuccessProtocol` (`ServerHistoryEntities.kt:71`; `HistoryCounters.kt:48`) + per-protocol `server_history_protocol` (`ServerHistoryEntities.kt:114–127`; `HistoryCounters.kt:125–131`) | **PRESENT** |

### Additional required items

- **SERVER_FIRST_SEEN / firstObservedAt producer.** `firstObservedAt` is stamped by the **first event of any type** (`HistoryCounters.kt:36`). `SERVER_FIRST_SEEN` has **no producer** (`ServerHistoryTypes.kt:51–54,87–91`). → firstObservedAt PRESENT; dedicated event ABSENT by design.
- **QUALITY_REJECTED producer.** **None** (`ServerHistoryTypes.kt:46–50,94`; `HistoryCounters.kt:104–106` "no producer exists"). → gap.
- **Success record awaited? what is dropped on expiry?** `recordHistory` is `suspend`, awaited at `withTimeoutOrNull(HISTORY_WRITE_TIMEOUT_MS = 2_000L)` (`AutoModeController.kt:496,2958,2982–2984`). On expiry the write is **dropped** and logged; the connection result is unchanged. Loss is **fire-and-forget** (`recordLoss`, `AutoModeController.kt:3004–3015`). → awaited + 2 s bound, **no retry** (OD-9 gap).
- **Failure/timeout classification (server vs local vs network).** Timeout → `TIMEOUT_UNATTRIBUTED`, `timeoutCount` only, never `failureCount` (`HistoryCounters.kt:56–63`; `ServerHistoryEntities.kt:65–66`). Server failure → **only** `SERVER_ABANDONED`+`FAILURE`, marked `FAILURE` only when a protocol was rejected (`SERVER_REJECTED`) (`HistoryCounters.kt:74–87`; `ServerHistoryTypes.kt:72–79`). Local/network reasons (`NATIVE_SLOT_BUSY`, `DATA_MISSING_CONFIG`, `PERMISSION_DENIED`, `USER_CANCELLED`, `NETWORK_UNAVAILABLE`, `CONNECT_THREW`) never count (`HistoryCounters.kt:64–72,88–93`; `ServerHistoryTypes.kt:133–152`). Decided in the controller (which event/outcome/reason it emits). → **PRESENT**.
- **History survives a full list replace? hostName change?** Survives replace: **YES** — separate tables, no FK (`ServerHistoryEntities.kt:10–29`). hostName change: **NO** — identity is the `hostName` PK (`ServerHistoryEntities.kt:43`), so a rename is a new identity (OD-2).
- **Event-table growth per connection attempt.** 1 `CONNECTED` per success; 1 `PROTOCOL_ATTEMPT` per failed protocol; 1 `SERVER_ABANDONED` per abandoned server; 1 `CONNECTION_LOST` per loss; 1 `ATTEMPT_CANCELLED` per cancel; **no event per list refresh** (OD-7). Retention **unbounded** (OD-5). → **PRESENT**.

---

## 0.2 Assumptions and remaining risks

1. **Identity** = `hostName.trim()`; a rename does not migrate history (OD-2). [SOURCE CODE]
2. **Clock** = `System.currentTimeMillis()` at the call site (`AutoModeController.kt:2969`); tests must inject a clock. [SOURCE CODE]
3. **Concurrency**: one Room transaction per record (`ServerHistoryDao.kt:106–122`); a concurrent refresh can lock the DB and the 2 s bound **drops** the success (OD-9 wants retry-once).
4. **Migration 9→10** must be additive; a downgrade to v9 would fail Room's identity check unless handled — out of scope, documented.
5. **Unlimited event retention** (OD-5) grows without bound — storage risk over time; no pruning.
6. **Fire-and-forget loss** may lose a loss event; acceptable per OD-9 (only success must be durable).
7. **Routing interaction**: the scorer must not disturb VPN-first, the 8 s per-attempt timeout, the one-candidate-per-IPv4 rule, or the stable-identity Next Server continuation.
8. **Fresh install (no history) must be bit-identical to today** (OD-6 neutral).

---

## 0.3 Proposed design (to implement after approval)

**Schema delta — Room 9→10, additive only** (no destructive fallback):
- `server_history_summary` += `lastRefreshSeenAt INTEGER` (nullable), `wifiConsecutiveFailures INTEGER NOT NULL DEFAULT 0`, `cellularConsecutiveFailures`, `unknownConsecutiveFailures`.
- `Migration(9,10)` = `ALTER TABLE … ADD COLUMN` ×4; `Migration9to10Test` with a real v9 fixture, existing rows intact, rollback test.
- Extend `ServerHistoryView` + `historyViews()` with the fields the scorer needs (`qualityRejectionCount`, the three per-network consecutive counters, `lastRefreshSeenAt`).

**Counters (PART 1):**
- **Quality-reject producer**: named constant `QUALITY_REJECT_WINDOW_MS = 10_000L`; emit `QUALITY_REJECTED` when the user taps Try Next **or** disconnects while Connecting **or** within 10 s of a `CONNECTED`; increments **only** `qualityRejectionCount`; never a failure/timeout counter; once per event; atomic.
- **Per-network consecutive failures**: on `SERVER_ABANDONED`+`FAILURE`, increment the counter for `record.networkType`; a success on that network resets it.
- **New detection**: on each **accepted** refresh, upsert `lastRefreshSeenAt = marker` for each host in the dataset (one summary upsert per host, **no event per host**, OD-7); "new" = `lastRefreshSeenAt` older than the current marker.
- **Durable success (OD-9)**: awaited, bounded, **retried once** before giving up.

**Smart AutoConnect (PART 2):**
- Deterministic, injectable clock. `score = w1·networkAwareSuccessRate(current network, Laplace) + w2·recencyDecay(lastSuccessAt) + w3·protocolSuccess(enabled protocols) − p1·qualityRejectionCount − p2·sameNetworkConsecutiveFailures`.
- No-history servers → **neutral** score (OD-6). Protocol success is **recorded, not reordered** (OD-4). Preserve VPN-first + one-per-IPv4 + the stable-identity Next Server continuation.
- **Exploration**: exactly **one** attempt per run — least-recently-tried previously-failed/uncertain server, placed **after** a clearly healthy first pick (never displaces it).

**Test plan (PART 3):** recording per event type; per-network counters; quality-reject window edges; timeout separate; local errors not counted; new detection; history survives list replace; `Migration9to10`; scoring order; exploration exactly once; Try Next score order; no double connect. Then **TEN forced full-suite runs** (`:app` + `:SoftEtherClient`) and lint totals.

---

**STOP — awaiting operator approval of this audit and design before any code change.**
