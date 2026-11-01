# SOFTETHERGAI — OVERNIGHT ENGINEERING CONSOLIDATION — FINAL REPORT

- **Block:** v1.0.69 overnight consolidation (host-side only)
- **Date:** 2026-10-26 (evening) · device unavailable until tomorrow morning
- **Branch:** `main` · **HEAD:** `08c6abe48be766be76830d249af41e625b6aa3ff`
- **Remote:** `origin/main` = `08c6abe…` (verified via `git ls-remote`) — local and remote agree
- **No commit, amend, tag, push, or release was performed.**

---

## 1. Executive Verdict

**YELLOW.**

Host-side work is complete and green **for the changed scope**: the uncommitted B-1 cancellation fix is now validated end-to-end, a proven-dead constant was removed, and three concrete coverage gaps were closed with tests that actually ran (app **1303 / 1 skipped / 0 failed / 0 errors**; `:SoftEtherClient` **184 / 0 / 0 / 0**; lint unchanged at 104/178/9).

It is **not GREEN** because every physical-device claim remains outstanding (`DEVICE_UNAVAILABLE`), and two roadmap items are **BLOCKED by unresolved design questions** rather than by effort: retention cap/prune (architecture doc §9.7 Q1/Q2/Q4) and the Server-List import entry point (placement explicitly "to be decided"). No critical regression or data-integrity defect was found.

---

## 2. Repository Baseline

| Item | Value |
|---|---|
| `git rev-parse HEAD` | `08c6abe48be766be76830d249af41e625b6aa3ff` — matches the reported HEAD |
| `git log -1` | `feat(automode): add history-aware exploration and refresh tracking` |
| Branch | `main` (tracks `origin/main`) |
| `git diff --cached --stat` | empty (nothing staged) |
| `git diff --stat` (start) | `HttpFetcher.kt` +48/−1 (uncommitted prior B-1 work) |
| Untracked (preserved) | v1.0.66/v1.0.69 reports, `SoftEthergai-v1.0.67-pro-debug.apk`, `test-evidence/`, `scripts/`, `emergency/`, `lint_v1064.log`, `build_test_baseline.log`, `.workbuddy-ai/` |
| Device | `adb devices -l` → **empty** (`DEVICE_UNAVAILABLE`) |

The uncommitted `HttpFetcher.kt` change is **prior user work** and was preserved, audited, and validated — not reverted or rewritten.

---

## 3. Recently Landed Changes Audit (`08c6abe`)

| Feature | Implementation | Persistent state | Lifecycle / concurrency | Tests | Coverage |
|---|---|---|---|---|---|
| History-aware exploration (PART 2) | `withExplorationSecond` (AutoModeHistory.kt:139), called at AutoModeController.kt:2148 | none | pure list reorder before the attempt loop; at most one move; never the first pick; only tried if the first fails | `AutoModeHistoryOrderingTest` (4 dedicated cases: exactly-one move, none eligible ⇒ unchanged, first pick never chosen, later `startAt`) | **Directly tested** (pure helper); controller call site indirectly |
| Refresh markers (PART 1B) | `ServerListUpdater.replaceAllAndMarkSeen` (:145, called :361), `ServerListUpdates` real transaction (:352), `ServerHistoryDao.markSeen` (:139) | `server_history_summary.lastRefreshSeenAt`, `firstListedAt` | one marker captured at `update()` start; only fresh (non-cache, non-emergency) accepted datasets; `db.runInTransaction { replaceAll; markSeen }` | `ServerListUpdaterTest` "a fresh network observation uses the combined atomic write and a fallback does not" | **Directly tested at the Store seam**; the real Room transaction is not executed by a JVM test |
| Schema 9 → 10 | `AppDatabase.MIGRATION_9_10` (:281): five additive `ALTER TABLE`s (3 × `NOT NULL DEFAULT 0`, 2 nullable) | 5 columns | additive only; no backfill, no recreate | `Migration9to10Test` | **SQL-contract + schema-parity only** — a recording proxy, **not** a real SQLite migration (see §13) |
| Quality-reject producer (PART 1D) | `QualityRejectWindow` (10 s, pruned, `@Synchronized`); `recordQualityRejectIfEligible` (AutoModeController.kt:3025) called from Try Next (:919) and `disconnectNow` (:1640) | `qualityRejectionCount` only | requires `connectedAt > 0` and within 10 s; dedup per host; `connectedAt` reset whenever state leaves Connected (:720); write dispatched on controller scope | `QualityRejectWindowTest`; `HistoryCountersTest` (counter) | **Gap closed this session**: controller producer had no test → added and passed (§6) |
| OD-9 durable Connected | `recordHistory(maxAttempts=3)` + 300 ms linear backoff (AutoModeController.kt:504–507, 3074–3101); Connected published before the awaited `NonCancellable` write (:2888) | event + summary | awaited but `NonCancellable`; failure never changes the connection result | previously none | **Gap closed this session**: two transient failures ⇒ exactly 3 attempts (§6) |

**Regression scenarios checked, no defect found:** duplicate exploration (impossible — single `removeAt`/`add`); repeated quality-reject accounting (window + `connectedAt` reset + `connectedCandidate = null` in `disconnectNow`); marker advancing before acceptance (markers are written only inside the accepted store path); interrupted refresh (markers are in the same transaction as the rows); candidate ordering (tier ordinals unchanged; exploration only permutes one element); failed attempt recorded as success (CONNECTED is written only on the verified-tunnel branch); ineligible reconnect candidate (unchanged pre-existing logic, not touched by this commit).

---

## 4. Roadmap Status

| Item | Status | Evidence / reason |
|---|---|---|
| A — Dynamic network-path selection | **COMPLETE** | `routeLadder` + per-request `Network.socketFactory`; v1.0.64/65 verified |
| B — Emergency source settings | **COMPLETE** | config + Room + UI + fetch |
| C — Emergency CSV schema | **COMPLETE** | `EmergencyCsvParser` + fetcher |
| D — Manual server entry | **COMPLETE** | Settings UI; immediate materialisation (v1.0.26) |
| E — Server-List UI entry point | **BLOCKED (decision)** | spec §12.5.2: *"Add Server row, floating action button, or another appropriate placement **to be decided**"* — implementing would be guessing placement |
| F — Text/file/share import | **PARTIAL** | paste + capped file picker exist in Settings (`ManualCsvImport`, `ManualImportStore`, `readCappedUtf8`); **Sharesheet/`ACTION_SEND` is OPEN** (no manifest intent-filter). Roadmap table's "OPEN — NOT IMPLEMENTED" is stale for the paste/file half |
| G — Safe merge & persistence | **PARTIAL** | phase 1 cumulative merge is **COMPLETE** (`mergeAll`, `firstSeenAt`/`lastSeenAt`/`healthScore`); **cap + retention-priority prune are BLOCKED** by arch §9.7 Q1 (health formula), Q2 (protocol weighting), Q4 (cap-vs-staleness order) |
| H — Tests | **PARTIAL** | JVM strong (1303); Android-runtime/device coverage open |
| B-1 — OkHttp cancellation | **FIXED (uncommitted)** | `callWithCancel`; now validated end-to-end (§10) |
| B-2 — `MIRROR_TIMEOUT_MS` | **RESOLVED this session** | was defined-only (private, no reflection/Gradle use) → removed |
| B-4 — lint backlog | **UNCHANGED** | 104/178/9, bit-identical to v1.0.64/v1.0.65 |

---

## 5. Implementation Scope and Rationale

Selected: **Priority 4 (fetcher cancellation & deadlines)** plus **closing concrete test gaps in Priority 1/3**.

Why not the higher-numbered roadmap items:
- **G cap/prune** would require inventing the `healthScore` formula and protocol weighting — the directive forbids speculative health scoring and the architecture document itself marks them `UNKNOWN`.
- **E** is explicitly undecided; the directive says not to guess data/UI decisions.
- **F Sharesheet** is implementable but is UI/device-verification heavy, and its confirmation flow is unspecified; a blind implementation could not be validated without the device.

Why this tranche instead: the B-1 patch was already in the tree **unverified**, its stated purpose ("the 45 s deadline is real") had never been tested end-to-end, and one of its tests could not even run (it hung). Validating it, fixing the test fakes, deleting the proven-dead constant, and pinning three uncovered invariants is genuine, low-risk progress that directly de-risks tomorrow's device work.

---

## 6. Code Changes

| File | Change | Behaviour affected |
|---|---|---|
| `app/src/main/java/vn/unlimit/vpngate/repository/VpnServerRepository.kt` | removed `private const val MIRROR_TIMEOUT_MS = 30_000L` (was defined, never referenced) | none (dead code) |
| `app/src/test/java/vn/unlimit/vpngate/repository/ServerListRefreshDeadlineTest.kt` | **new**: 300 ms outer deadline vs. a real `OkHttpFetcher` whose interceptor never completes on its own | proves the deadline is end-to-end |
| `app/src/test/java/vn/unlimit/vpngate/data/remote/HttpFetcherCancellationTest.kt` | replaced interrupt-based blocking fake with a `call.isCanceled()` poll | makes the B-1 test runnable; it previously blocked the JVM |
| `app/src/test/java/vn/unlimit/vpngate/ServerListUpdaterTest.kt` | **+1** test: identical dataset ⇒ `Updated(12)`, one write, same identities | pins "unchanged ≠ rejected/skipped" |
| `app/src/test/java/vn/unlimit/vpngate/automode/AutoModeHistoryRecordingTest.kt` | **+2** tests: quality-reject producer; OD-9 three-attempt retry | closes the two PART 1 coverage gaps |
| `app/src/main/java/vn/unlimit/vpngate/data/remote/HttpFetcher.kt` | **unchanged by me** — prior uncommitted B-1 patch preserved and validated | cancellation-cooperative fetch |

---

## 7. Server Data Merge and Retention

Verified against source and existing tests:

- **Partial dataset never deletes stored rows** — `mergeAll` retains every stored host absent from the incoming set (`ServerListRetentionTest` T-NEW-27, T-GF291-E). PASS.
- **Degraded dataset never replaces a healthier one** — `dropsOpenVpnProfiles` + `DatasetIntegrity.evaluate` run before any write; rejection returns `Preserved(DEGRADED_DATASET)` (`ServerListUpdaterTest`). PASS.
- **Manual/imported rows survive** — `withManualRows` runs before the merge on both paths (T-NEW-26). PASS.
- **Deterministic duplicates** — `VPNGateItem.dedupeForStorage` by Room identity (`hostName`); last occurrence wins on import. PASS.
- **History fields** — `firstSeenAt` preserved, `lastSeenAt` refreshed, `healthScore` recomputed for observed rows (phase-1 formula), sentinel 0 reserved for never-refreshed rows. PASS.
- **Identical dataset** — new test: still a successful observation (write happens, markers advance). PASS.
- **Persistence failure** — `store failure keeps the current list` → `Preserved(STORE_FAILURE)`, never `Updated`. PASS.
- **Cap/prune** — **not implemented** and **blocked** (§4). No speculative pruning added.

---

## 8. Server Import and UI

- Implemented and hardened: `ManualCsvImport` (1 MiB UTF-8 cap, 2000-row cap, dialect detection, wrapped-line repair, inner-whitespace/control rejection, sanitised report strings, no logging of content), `ManualImportStore` (atomic temp+rename JSON), capped file reader `readCappedUtf8(input, MAX_BYTES)` in Settings. Empty/malformed/oversized inputs return typed failures; invalid imports leave the DB untouched; provenance tag is `manual` (never canonical).
- **Open:** a Server-List entry point (E — placement undecided) and Sharesheet/`ACTION_SEND` (F remainder). Not implemented to avoid guessing UX/placement.

---

## 9. AutoMode and Unexpected Disconnect

- Intentional disconnect vs. loss: `disconnectNow()` clears ownership and records a **quality rejection** (neutral) only when the tunnel had just connected; unexpected loss goes through `recordLoss`/`reportArmedTunnelLoss` (`CONNECTION_LOST`). No retry after explicit user disconnect.
- Retry bounds: `AutoModeReconnectTest` loop guard; OD-9 history retry is separate and bounded to 3.
- **New this session:** the quality-reject producer and the 3-attempt CONNECTED retry are now directly tested through the controller harness (20/20 tests pass).
- Not device-verified: the real lifecycle timing of these callbacks (device plan P8/P9).

---

## 10. Fetcher Cancellation and Timeout

- `OkHttpFetcher.get` runs on `Dispatchers.IO` (HttpFetcher.kt:202) and now executes through `callWithCancel` (:347), which registers `invokeOnCancellation { call.cancel() }`.
- **New end-to-end test** (`ServerListRefreshDeadlineTest`): a 300 ms `mainTimeoutMs` against an interceptor that would otherwise block for 10 s → `refresh()` returns null, logs `deadline exceeded`, in < 4 s. This is the first test that proves the *repository* deadline is real, not merely cooperative.
- Timeout inventory (unchanged): connect 10 s / read 15 s (HttpFetcher.kt:110–111), outer 45 s (VpnServerRepository.kt:132/415), one-shot retry 20 s. `MIRROR_TIMEOUT_MS` removed (B-2).
- Residual (not fixed, documented): the `cont.isActive` check in `callWithCancel` leaves a tiny `Response`-leak window if cancellation lands between the check and `resumeWith`; the idiomatic `resume(raw) { _, r, _ -> r.close() }` would close it. Not observed; changing it was judged speculative for this block.

---

## 11. Network-Binding Architecture (source-level only)

| Question | Answer | Evidence |
|---|---|---|
| Explicit Android `Network` selected? | **YES** | `boundToNetwork` / `boundToPhysicalNetwork` hand `network.socketFactory` to the client (HttpFetcher.kt:157–181); chosen in `productionTunnelFetcher` / `productionBoundFetcher` (VpnServerRepository.kt:340–357) |
| Ordinary default-network resolution? | **NO for bound rungs**; **YES for the injected fallback** `fetcher` when no physical `Network` exists (JVM/airplane) | `productionBoundFetcher()` returns null → `fetcher` |
| Process-wide binding? | **NO** | `bindProcessToNetwork` appears only in comments (HttpFetcher.kt:126,150; VpnServerRepository.kt:339,681) |
| Explicit VPN `Network`? | **YES when present** | `GlobalVpnTracker.currentVpnNetwork()` (VpnServerRepository.kt:354) + routing input (:191) |
| `VpnService.protect()`? | **NO** | no occurrence in the routing path |
| Raw sockets / other stack? | **NO** | OkHttp via `collectorClientBuilder` only |
| DNS / timeouts / retries / cancellation | `ResilientDns` (HttpFetcher.kt:57,109); 10 s/15 s; per-source `raceFetch` budgets (12 s API/HTML, 8 s directory); 45 s outer; cancellation now cooperative | see §10 |
| VPN drops mid-update | routing re-read per refresh; the captured bound network's sockets fail; per-source fallback to PHYSICAL is explicit | `fetchOnChosenPath` |
| Request can outlive its network? | The bound fetcher is per-refresh; a dead VPN network fails the request rather than silently switching | code-level |
| Packet path observable from source alone? | **NOT OBSERVABLE** | requires device (`dumpsys connectivity` + per-source `path=` logs) |

No claim is made that traffic traverses the tunnel merely because a VPN is connected.

---

## 12. Automated Regression Results

Environment: `CI=true`, JDK 21, default `GRADLE_USER_HOME` (unset), no cache relocation.

```
./gradlew :app:testProDebugUnitTest --rerun            → BUILD SUCCESSFUL
./gradlew :SoftEtherClient:testDebugUnitTest --rerun   → BUILD SUCCESSFUL
```

| Suite | Tests | Skipped | Failures | Errors | Executed (not UP-TO-DATE) |
|---|---|---|---|---|---|
| `:app:testProDebugUnitTest` | **1303** | 1 | 0 | 0 | yes (`> Task :app:testProDebugUnitTest`) |
| `:SoftEtherClient:testDebugUnitTest` | **184** | 0 | 0 | 0 | yes |

Focused runs (all green, counts from JUnit XML): `ServerListRefreshDeadlineTest` 1/0/0/0 · `HttpFetcherCancellationTest` 3/0/0/0 · `AutoModeHistoryRecordingTest` 20/0/0/0 · `ServerListUpdaterTest` 26/0/0/0.

Lint: `Lint found 104 errors, 178 warnings and 9 hints` — **bit-identical to v1.0.64 and v1.0.65**; the build aborts on that pre-existing backlog (unchanged policy). No new issue references a file changed this session.

---

## 13. Database and Migration Coverage — honesty statement

`Migration9to10Test` is a **SQL-contract + `9.json`/`10.json` parity test against a recording `SupportSQLiteDatabase` proxy**. It does **not** open a real v9 database, does **not** execute the migration against rows, and does **not** run Room's schema validation. The unit-test source set declares only JUnit (no `room-testing`, `sqlite-jdbc`, or Robolectric), so a real migration test would require a new dependency and separate authorization. This limitation is documented in the test itself. **No claim of real SQLite migration safety is made.**

Likewise, `replaceAllAndMarkSeen`'s atomicity is verified at the `Store` interface boundary with a fake; the real `RoomDatabase.runInTransaction` path is source-verified but not executed by a JVM test.

---

## 14. Physical Device Status

**`DEVICE_UNAVAILABLE`** — `adb devices -l` returned an empty list. No installation, no launch, no VPN connection, and no device test was performed or claimed. Expected device for tomorrow: serial `5585fecc`, model `2201117TG`, API 33, package `vn.unlimit.vpngatepro` (confirm live; do not substitute another device without authorization).

---

## 15. PHYSICAL DEVICE TEST PLAN — EXECUTE WHEN DEVICE RETURNS

Preconditions: build from the current tree; keep `adb install -r` (never uninstall/clear data); record everything.

**P0 — identity & baseline**
```
adb devices -l
adb -s <serial> shell getprop ro.product.model
adb -s <serial> shell getprop ro.build.version.sdk
adb -s <serial> shell getprop ro.product.cpu.abi
adb -s <serial> shell dumpsys package vn.unlimit.vpngatepro | findstr /C:"versionCode" /C:"versionName"
adb -s <serial> shell dumpsys connectivity > baseline_connectivity.txt
```
Expected: `5585fecc`/`2201117TG`/33/arm64-v8a; note versionCode/Name and whether a VPN network exists.

**P1 — install (only if P0 matches)**
```
adb -s <serial> install -r app/build/outputs/apk/pro/debug/app-pro-debug.apk
```
Verify `lastUpdateTime` advanced; app data preserved.

**P2 — launch & persistence** — cold start, no crash; Server List row count and a few identities; process death + relaunch → same list.

**P3 — import (paste + file)** — paste a 3-row CSV (2 valid, 1 malformed) → exactly 2 added, 1 rejected with a reason; re-import → no duplicates; pick a file via the system picker → same counts; import a >1 MiB file → `TOO_LARGE`, DB unchanged.

**P4 — VPN connect** — connect; record `dumpsys connectivity` (`tun0`, `Transports: … VPN`, underlying network) and session id; **do not** infer routing from the UI alone.

**P5 — update with VPN up** — trigger Server List refresh; expect `[ladder] order=TUNNEL,PHYSICAL,EMERGENCY`, `[fetch] path=tunnel …` for the sources, and **no** `path=physical` when the tunnel delivers.

**P6 — update with VPN down** — disconnect; refresh; expect `order=PHYSICAL,EMERGENCY`, all sources `path=physical`, zero `path=tunnel`.

**P7 — disconnect mid-refresh** — start a refresh, disconnect ~3 s in; expect the in-flight TUNNEL requests to fail fast and the fallback to complete; the stored list must not shrink.

**P8 — unexpected disconnect** — reproduce only if safe (e.g., server-side drop); verify reconnect ordering and that history records `CONNECTION_LOST`, not a success.

**P9 — history/DB read-back (read-only)** — `run-as` pull `databases/*` + `-wal`/`-shm`, replay beside a copy; verify `user_version=10`, `integrity_check=ok`, and that `lastRefreshSeenAt`/`firstListedAt` advanced on an accepted refresh. If the build does not expose `run-as`, mark **NOT OBSERVABLE**.

**P10 — crash/lifecycle** — logcat for `FATAL`, ANR, `VpnService` lifecycle; confirm exactly one connection attempt per action.

Each test records: ID, preconditions, action, evidence file, expected outcome, failure classification, root requirement (only P9 may need `run-as`), and safety on the existing install.

---

## 16. Remaining Defects and Risks (ranked)

| # | Severity | Item | Evidence |
|---|---|---|---|
| R1 | **Medium** | All device-dependent behaviour unverified (routing, import UX, lifecycle) | `adb devices` empty |
| R2 | **Low–Medium** | No real SQLite migration test for 9→10; rollback unsupported (downgrade ⇒ data loss) | test is a recording proxy; Room has no down-migration |
| R3 | **Low** | OD-9 retry could duplicate a CONNECTED event if a write commits after the 2 s timeout but before cancellation lands (no idempotency key) | code reasoning; not observed |
| R4 | **Low** | Tiny `Response` leak window in `callWithCancel` if cancellation lands between `isActive` and `resumeWith` | code reasoning; fix suggested in §10 |
| R5 | **Info** | Roadmap E (placement) and G cap/prune (Q1/Q2/Q4) are blocked on decisions, not code | spec/arch doc |
| R6 | **Info** | Roadmap table still says F "NOT IMPLEMENTED" while paste/file import exists — documentation drift | spec §12.5.2 vs `ManualCsvImport` |

No P0/P1 defect found in the landed commit.

---

## 17. Preserved Existing Work

All pre-existing modifications and untracked artifacts were preserved: the uncommitted `HttpFetcher.kt` B-1 patch, every report (v1.0.66/v1.0.69 series, device-verify reports), `SoftEthergai-v1.0.67-pro-debug.apk`, `test-evidence/`, `scripts/`, `emergency/`, `lint_v1064.log`, `build_test_baseline.log`, `.workbuddy-ai/`, `.dv6/`, `.dv7/`, `.vscode/`. No destructive command was run; no artifact was overwritten.

---

## 18. Git and Release Status

- Commit: **NONE**
- Amend: **NONE**
- Tag: **NONE**
- Push: **NONE**
- Release: **NONE**

Working tree after the session: `HttpFetcher.kt` (prior, uncommitted) and `VpnServerRepository.kt` (−1 dead constant) modified; new test files and this report untracked. HEAD is still `08c6abe…`.

---

## 19. Final Recommendation

**RUN PHYSICAL DEVICE VERIFICATION.**

The host-side scope is green and the highest-value unverified risk is now exclusively device-side. Execute §15 P0–P10 when `5585fecc` returns, then decide on the blocked roadmap items (E placement; G cap/prune Q1/Q2/Q4) with real evidence in hand.
