# BLOCK v1.0.70 (recorded as v1.0.75) — Final Report: Origin Labels, Two-Stage Try Next, History-Driven Fast Connect

Date: 2026-10-27. Device: `5585fecc` AVAILABLE during this block (used only read-only; no install/uninstall).
Labels: SOURCE CODE / TEST / PHYSICAL DEVICE / INFERENCE / UNKNOWN.

## A. Executive verdict

| Part | Verdict |
|---|---|
| PART 0 inventory | **PASS** — all three named prior work items are committed; no installed build ever used Room 10 |
| PART A origin labels (D-3) | **PASS** — SOURCE + TEST |
| PART B two-stage Try Next (D-1/D-2) | **PASS** — SOURCE + TEST |
| PART C history-driven fast connect (D-4..D-8) | **PASS** — SOURCE + TEST (one item, real fast-path timing, is DEVICE-DEPENDENT — see K) |
| PART D verification (D1–D3) | **PASS with one documented conflict** (see J) |
| PART E migration on a copy | **PASS** — real device DB copy, uv 9→10, parity OK |
| PART F gates | F2 docs **DONE**; F1 ten-run gate **see §H** |

No commit in this block introduced a production defect found by the suites; every commit was pushed
fast-forward to `origin/main` (operator granted full commit+push authorization in this session).

## B. PART 0 — inventory (committed vs uncommitted)

- v1.0.68 PART A (`tryNextEnabled`): **committed** `f9b3b9d` — `AutoModeViewModel.kt:106`, tests
  `AutoModeDisplayStateTest.kt:156-180`.
- v1.0.69 PART 1 (Room 9→10, QualityRejectWindow, markSeen, per-network counters, durable success):
  **committed** `08c6abe` — `AppDatabase.kt:30`, `schemas/.../10.json`, `Migration9to10Test`,
  `history/*`.
- v1.0.69 PART 2 (`withExplorationSecond`): **committed** `08c6abe` — `AutoModeController.kt:2148`.
  Superseded by C7 (kept for reference; the controller no longer calls it).
- **Room 10 shipped? CORRECTED — YES.** PART 0 concluded "no" from the newest *saved* copy
  (`dv61/final.db`, 2026-10-25, uv 9). The LIVE device database, pulled read-only on 2026-10-27,
  reports **`user_version = 10`** with 25 summary columns: the installed build already opened the
  Room-10 schema. The C6 columns therefore ship as a real **MIGRATION_10_11** (`d8f5fc8`), NOT as a
  fold into 10 — folding would break Room's identity check on every upgraded device. `10.json` was
  restored to the shipped 25-column schema and `11.json` exported.
- All six CONTEXT claims (0.3 a–f) confirmed with file:line (see `BLOCK_v1.0.70_PART0_INVENTORY.md`).

## C. PART A — connection origin (D-3)

- `ConnectionOrigin` {AUTO_CONNECT, AUTO_RECONNECT, MANUAL}; carried on `Connecting`/`Connected`;
  `protocol` became nullable so a MANUAL tunnel never shows a fabricated transport.
- Controller stamps `runOrigin`: AUTO_RECONNECT only in `onUnexpectedDisconnect`'s restart;
  AUTO_CONNECT on every user start and both Next continuations.
- `combineAutoModeState` now takes the tracker's real host/IP/protocol; `AutoModeProtocol.fromTrackerId`
  maps only concrete ids ("SSTP"→MS_SSTP; "SoftEther"/"OpenVPN" → null).
- Strings `automode_origin_*` in en/fa/vi; the two superseded subtitles were removed (no unused-resource
  lint delta).
- Tests: `AutoModeDisplayStateTest` 11 → 20 (all states × origins + mapping).

## D. PART B — two-stage Try Next (D-1/D-2)

- B1: skip-while-connecting `continue`s to the next protocol of the same server; NEUTRAL
  `ATTEMPT_CANCELLED`/`USER_CANCELLED`; next server only after protocol exhaustion; coalescing kept.
- B2: `sessionTriedKeys` (ip|hostname) filled by the run walk, cleared on disconnect/stop, seeded with
  the manual server; Next selects the FIRST untried candidate in the history-aware ordering, walks the
  untried tail, and reports `ERROR_NO_MORE_UNTRIED` instead of wrapping. Hero press delegates to the
  same path.
- B3: quality reject on leave preserved; manual tunnels never counted.
- Tests: 12 legacy position-continuation tests updated to D-2 semantics (identity keying preserved);
  new `skipDuringProtocolOneTriesProtocolTwoOfTheSameServer` (fake adapter `slowFirstOnly`).

## E. PART C — history-driven fast connect (D-4..D-8)

- C1 known-good first: `KNOWN_GOOD_MAX = 3`, `KNOWN_GOOD_WINDOW_DAYS = 14`, UNKNOWN network → no
  promotion, fresh install bit-identical; reachability gate still runs.
- C2 per-network demotion replaces the global rule; quality-reject penalty (≥2, no success on network);
  stable partition.
- C3 fast path before sort/probe for user-started runs only; logged with elapsed ms; failure marks the
  server attempted and continues.
- C4 remembered protocol first for servers with history; disabled protocols never introduced.
- C5 per-network cooldown 30 min; tail-deferred (never dropped).
- C6 `missedRefreshCount` + per-network `*LastFailureAt` folded into Room 10; `markSeen` resets, one
  UPDATE ages, emergency/cache never ages; demote at ≥2 without a success on the network.
- C7 exactly one exploration candidate, oldest `lastAttemptAt`, never first, second by default,
  `EXPLORATION_FIRST_EVERY_K_RUNS` switch.
- C8 fresh-install invariance: `an empty history map leaves the order untouched` still green.
- Tests: 13 new (ordering, promotion cap, window, failure-cancel, rejection demotion, missed-refresh
  demotion, remembered protocol, cooldown, exploration ×3).

## F. PART D — verification of already-done items

- **D1 defaults**: protocol profile [UDP, TCP, SSTP] and timeout 12 s / 8..30 (see J); Auto-Reconnect ON
  (new test added); server-list interval 168 h (`ServerListUpdaterTest`); emergency default source
  (`EmergencyDefaultSeedTest`). Fresh-install behaviour itself is **NOT TESTABLE** without clearing data.
- **D2 excluded apps**: `assets/default_excluded_apps` holds exactly **12** packages; parser/seeder
  tests exist (`DefaultExcludedAppsTest`, `ExcludedAppsSeederTest`); `<queries>` declared
  (`AndroidManifest.xml:32`); seeder is one-shot via `SETTING_EXCLUDED_APPS_SEEDED`. Device-only:
  actual install-time seeding on a fresh device.
- **D3 dynamic update path**: ladder tests (`EmergencyLadderWiringTest`, merge/retention tests), refresh
  deadline (`ServerListRefreshDeadlineTest`), cancellable fetch (`HttpFetcherCancellationTest`).

## G. PART E — migration verification

**G1 — on a copy (earlier):** `test-evidence/dv61/final.db` (uv 9) copied, the then-current
MIGRATION_9_10 applied: uv → 10, rows unchanged, integrity ok, 29/29 column parity. Superseded by G2.

**G2 — on the REAL device (PHYSICAL DEVICE EVIDENCE):**
1. Read-only backup pulled BEFORE installing: `test-evidence/v1075_device_backup/` (main+wal+shm,
   binary-exact via `exec-out`): **uv = 10, 25 summary columns, rows {VPNGateItem 400, summary 20,
   protocol 27, event 160}**.
2. APK built from `git archive d8f5fc8` (`:app:assembleProDebug`), installed with `adb install -r`
   (no uninstall, no data clear), launched via the launcher intent.
3. After launch: **uv = 11, 29 summary columns** (…, `missedRefreshCount`, `wifiLastFailureAt`,
   `cellularLastFailureAt`, `unknownLastFailureAt`), **row counts identical** {400, 20, 27, 160},
   `integrity_check: ok`, process alive, **zero new crash/ANR entries today**.
4. Screenshot `test-evidence/v1075_device_backup/screen_after_launch.png`: Auto Mode screen renders
   ("Disconnected / Tap to connect", Auto-Reconnect ON, live log) — the new build is healthy.

The origin labels themselves are only visible while a tunnel is up; no VPN connection was attempted
(see K).

## H. PART F — gates

- **F1 (ten consecutive full-suite runs)** — re-run on the FINAL commit `2bc0b8f` from one clean
  `git archive` copy: **RUN_1..RUN_10 all EXIT=0**; APP **1364 tests / 1 skipped / 0 failures /
  0 errors** (includes the 5 new `Migration10to11Test` cases), SEC **184 / 0 / 0 / 0**.
  Final lint on that commit: **102 errors / 178 warnings / 9 hints** — **two errors FEWER than the
  104 baseline and ZERO new findings** (the reduction is the removal of the two dead-string
  MissingTranslation errors; the earlier 103/179/9 reading on `9b83238` is superseded).
  (An earlier ten-run gate on `9b83238` was also 10/10 green; it was superseded by the 10→11 migration fix.)
- **F2**: §12.9 written (origin labels, stages, session tried-set, ordering rule with constants and a
  worked numeric example, fast path, remembered protocol, cooldown, absent-refresh rule, exploration,
  quality-reject semantics, D-1..D-9 verbatim, OD note, six-requirements→schema mapping, deferred
  items, downgrade warning); revision rows 1.0.72–1.0.75 appended.

## I. Evidence index

| # | Evidence | Type |
|---|---|---|
| 1 | Commits `e8f5191..08ed375` (+ docs commit) fast-forward on origin/main | SOURCE |
| 2 | Full regression: APP **1358/1/0/0**, SEC **184/0/0/0** | TEST |
| 3 | `ManualServerQuotaTest` 19/19 (unchanged) | TEST |
| 4 | Device identity/version 144 / 2.4.11-dev | PHYSICAL |
| 5 | Migration parity on DB copy (uv 9→10) | PHYSICAL+TEST |
| 5b | **Real-device migration 10→11**: uv 10/25 cols → uv 11/29 cols, rows identical, integrity ok | PHYSICAL |
| 5c | Device backup before install (`test-evidence/v1075_device_backup/`) + post-launch screenshot | PHYSICAL |
| 6 | 12 excluded packages in asset | SOURCE |
| 7 | Origin/TryNext/history tests listed above | TEST |

## J. Documented conflict — the block's D1 text is stale

The block's D1 says "protocol priority [SOFTETHER_TCP, SOFTETHER_UDP], per-attempt timeout 8 s (floor
8)". The operator-approved later baseline (committed `75e9b97`, REQ-TIMEOUT-001 / REQ-PROTO-001)
is **12 s clamped 8..30** and **UDP → TCP → SSTP**. The audit follows the approved baseline; the block
text is recorded here as superseded, not silently ignored.

## K. Device-dependent follow-up list (NOT verified)

1. Fast-path timing vs the full flow on a real network.
2. Reachability probe duration with the new ordering.
3. Try Next stage-1 (protocol-first) and stage-2 (best untried) on a real connect.
4. Origin labels on the real status screen (AUTO_CONNECT / AUTO_RECONNECT / MANUAL).
5. Fresh-install defaults (needs data clear).
6. First-run excluded-apps seed.
7. Migration on the real device DB (verified on a copy only).

## L. Deferred (D-9)

Network-identity hashing, staged probing, connect-time statistics, extending the Iranian app list, a
rescan button — all need on-device measurements.

## M. Integrity

No tag; no force/reset/clean/stash/checkout; no uninstall; no app-data clear; no manual DB/JSON edits;
working tree returned to zero modified tracked files after each commit; all pre-existing untracked
evidence preserved.
