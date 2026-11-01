# BLOCK v1.0.70 — PART 0 Inventory and Hygiene Report

Date: 2026-10-27 (GMT+3:30). Device: NOT AVAILABLE per block header — no adb operations performed.
All claims labelled: SOURCE CODE / TEST / PHYSICAL DEVICE / INFERENCE / UNKNOWN.
`git status --short` recorded at start: **70 lines (28 modified + 42 untracked), 0 staged** — identical at the
end of PART 0 (nothing modified by this part). HEAD = `08c6abe48be766be76830d249af41e625b6aa3ff`, branch `main`.

## 0.1 — Committed vs uncommitted state of the named prior work

| Item | State | Evidence (SOURCE CODE) |
|---|---|---|
| v1.0.68 PART A (`tryNextEnabled`, Try Next for external tunnels) | **COMMITTED** | commit `f9b3b9d` ("enable Try Next whenever any tunnel is connected (external included)"): `AutoModeViewModel.kt:106` (`internal fun tryNextEnabled`), used at `AutoModeScreen.kt:432`, tests `AutoModeDisplayStateTest.kt:156-180` (incl. external-connected/external-connecting assertions) |
| v1.0.69 PART 1 (Room 9→10 + QualityRejectWindow + per-network consecutive failures + durable success writes) | **COMMITTED** | commit `08c6abe`: `AppDatabase.kt:30` (`version = 10`), `app/schemas/...AppDatabase/10.json`, `db/Migration9to10Test.kt`, `history/QualityRejectWindow.kt` + `QualityRejectWindowTest.kt`, `HistoryCounters.kt`, `ServerHistoryDao.kt`, `ServerHistoryEntities.kt`, `ServerHistoryTypes.kt` (per-network fields at `:223-225`), `AutoModeHistory.kt`, `ServerListUpdater.kt` |
| v1.0.69 PART 2 (withExplorationSecond) | **COMMITTED** | `08c6abe`; `AutoModeController.kt:2148` (HEAD) / `:2152` (working tree) calls `servers.withExplorationSecond(...)` |

**Has any installed build used Room version 10? — NO (PHYSICAL DEVICE EVIDENCE, from saved copies):**
The latest saved device-DB copy is `test-evidence/dv61/final.db` (2026-10-25): SQLite header
`user_version = 9`. All other saved copies are ≤ 9 (`.dv6/dump/vpn_gate_connector.db` header uv=0 — different
DB; `test-evidence/dv49_v7/db_v7_*.db` uv=8). No copy at 10 exists anywhere in the tree
(full `find . -name "*.db"` sweep, 6 files total). INFERENCE: the Room-10 schema has never shipped to the
device, so per C6 the `missedRefreshCount` column can be **folded into version 10** (no 10→11 migration, no
new schema JSON beyond updating `10.json` + `Migration9to10Test`). Limitation: the device is unavailable, so
the *currently installed* app's live DB cannot be read right now — UNKNOWN for the live install, but every
saved copy says 9 and the installed build (versionCode 144) predates the Room-10 commit in its APK
(INFERENCE from `dv61/final.db` being the newest pull).

**Inventory of the UNCOMMITTED tree (28 modified + 42 untracked), grouped by theme:**

| Theme | Files (uncommitted) |
|---|---|
| T1 — Timeout baseline (self-labelled **"v1.0.70 (REQ-TIMEOUT-001)"** — see collision note) | `AutoModeController.kt` (`DEFAULT_ATTEMPT_TIMEOUT_MS` 8_000→12_000 at ~:544), `DataUtil.kt` (default 12, clamp 8..30), tests `DataUtilTimeoutRangeTest.kt`, `DataUtilDefaultsV1054Test.kt`, `AutoModeControllerTest.kt`, `AutoModeNativeBusyTest.kt`, `AutoModeHistoryRecordingTest.kt` (exact membership to be confirmed per-file before staging) |
| T2 — Retention/quota (v1.0.71–74) | new `ManualServerAdmission.kt`, `ImportOutcome.kt`; modified `ManualImportStore.kt`, `ManualServerStore.kt`, `ServerListUpdates.kt`, `VpnServerRepository.kt`, `ConnectionListViewModel.kt`, `SettingsScreen.kt`, `strings.xml` (values/-fa/-vi), `VPNGateItem.kt` (`connectOptions` private→internal); tests `ManualServerQuotaTest.kt` (new), `ServerRetentionCapTest.kt` (new), `VpnServerRepository*R4b/Ladder/Routing*/DynamicVpnPath/VpnFirstGuards` test updates |
| T3 — B-1 fetcher cancellation | `HttpFetcher.kt` (`callWithCancel` via `suspendCancellableCoroutine`), new `HttpFetcherCancellationTest.kt` |
| T4 — misc tests | `ServerListUpdaterTest.kt`, new `ServerListRefreshDeadlineTest.kt`, new `CustomDnsDefaultTest.kt` (theme assignment to confirm from content) |
| T5 — docs | `PROJECT_SPECIFICATION.md`, `SERVER_SOURCE_ARCHITECTURE.md` |
| — evidence/reports (not proposed for commit) | 30+ untracked `.md`/`.png`/`.apk`/`.log` evidence files, `.dv6/`, `.dv7/`, `emergency/`, `scripts/`, `test-evidence/` |

**⚠️ LABEL COLLISION (must be decided by the operator):** the uncommitted T1 changes already carry in-code
comments "v1.0.70 (REQ-TIMEOUT-001)". The current block is *also* titled "BLOCK v1.0.70". Spec history shows
v1.0.71–74 labels consumed by the quota phases (V1.0.71–74 reports). Proposal: commit T1 under its existing
in-code label (REQ-TIMEOUT-001) and number THIS block **v1.0.75+** in the spec history, or renumber — the
operator decides; recorded here verbatim as required.

## 0.2 — Proposed commit sequence (each commit must compile alone from `git archive <commit>`)

The exact staged set + build evidence will be shown for EACH commit before it is created; nothing is committed
without the operator's approval (block rule). Build verification: clean `git archive` copy on D:,
`GRADLE_USER_HOME` on D:, `$env:CI="true"` — one copy at a time, paths listed before any deletion.

1. **S1 — B-1 fetcher cancellation** (T3): `fix(fetcher): cancel in-flight HTTP call when the coroutine scope cancels (B-1)`.
   Files: `HttpFetcher.kt`, `HttpFetcherCancellationTest.kt`. Smallest, fully independent.
2. **S2 — timeout baseline** (T1): `feat(automode): restore 12 s default attempt timeout, clamp 8..30 (REQ-TIMEOUT-001)`.
   Files: T1 set above (membership confirmed by diff before staging).
3. **S3 — retention/quota** (T2): `feat(retention): manual-server quota 450/500 enforced by shared admission gate across Room and JSON`.
   Files: T2 set. Largest; the 19-test quota suite guards it.
4. **S4 — misc test updates** (T4): attached to S2/S3 or a separate `test:` commit after content check.
5. **S5 — docs** (T5): `docs(spec): record REQ-TIMEOUT-001 and retention quota phases` (spec §22 append-only entry at this point covers only already-done work; the block's own F2 entry comes after Parts A–F).

v1.0.69 PART 2 exploration (`withExplorationSecond`) is **already in history** (08c6abe) — nothing to "not
commit"; PART C7 of this block will supersede its behaviour with new tests that fail on the current form.

## 0.3 — CONTEXT claims verified (file:line, working tree = what would be committed)

| Claim | Verdict | Evidence (SOURCE CODE) |
|---|---|---|
| (a) history reorders only inside exact byQuality ties | **CONFIRMED** | `AutoModeController.kt:1883-1894` (`applyHistoryTieBreak` reorders only `[from,to)`), called at `:1832` after the byQuality sort; comparator inputs at `AutoModeHistory.kt:93-112`; byQuality itself (`AutoModeController.kt:3308-3315`: speed desc → ping known-first → sessions → score) takes no history. Second tie-break site referenced at ~3308 is the comparator definition region (byQuality at `:3308-3314`, `demoteAfterConsecutiveFailures` at `:3320`) |
| (b) demoteUnhealthy uses GLOBAL consecutiveFailures and successCount==0 | **CONFIRMED** | `AutoModeController.kt:1853-1873`: `isRepeatedlyFailing` → `h.successCount == 0 && h.consecutiveFailures >= demoteAfterConsecutiveFailures` (`:1872`); `ServerHistoryView.consecutiveFailures` is the global counter (`ServerHistoryTypes.kt:215`), NOT the per-network ones (`:223-225`) |
| (c) qualityRejectionCount and per-network consecutive counters never read by ordering | **CONFIRMED** | `historyComparator` (`AutoModeHistory.kt:100-111`) reads only tier (`historyTierOf`), `lastSuccessAt`, global `consecutiveFailures`, `failureCount`. `ServerHistoryView` fields `qualityRejectionCount`, `wifiConsecutiveFailures`, `cellularConsecutiveFailures`, `unknownConsecutiveFailures` (`ServerHistoryTypes.kt:222-225`) appear nowhere in either ordering site |
| (d) user skip while connecting does `break // next SERVER (not next protocol)` | **CONFIRMED** | `AutoModeController.kt:2364` (block said ~2358; line drifted to 2364 in the working tree) — records `HistoryFailureReason.USER_CANCELLED` at `:2359` then breaks |
| (e) Connected has no origin; external tunnel shown with fabricated SOFTETHER_TCP and null host | **CONFIRMED** | `AutoModeViewModel.kt:70-76`: external CONNECTED synthesized as `AutoModeState.Connected(hostname=null, ip=null, protocol=SOFTETHER_TCP, speed=0, ping=0)`; doc `:54-56` admits "display-only placeholder"; `AutoModeState.Connected` signature (hostname, ip, protocol, speed, ping) carries **no origin field**; screen renders "Connected (manual)" only via `isExternal` (`:41, :56`) |
| (f) Next Server while connected continues by list position, not "best untried" | **CONFIRMED** | `AutoModeController.kt:921-976`: Connected branch tears down and continues from `remainingCandidates.ifEmpty { provider().withActiveRtt(...).sortedForAutoConnect() }` filtered only by the current key (`:961-966`) — pure list position; no session-level tried set exists anywhere (INFERENCE: `grep` for a session tried-set finds none) |

## Status

PART 0 complete — read-only; tree untouched. **STOP** for the operator's approval of:
(1) the S1–S5 commit sequence and the label-collision decision (commit T1 under REQ-TIMEOUT-001 and renumber
this block v1.0.75+, or alternative); (2) each individual commit set when presented; (3) then Parts A–F begin.
