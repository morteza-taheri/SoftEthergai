# BLOCK v1.0.65 — DYNAMIC VPN-FIRST FAILOVER GUARDS — VERIFICATION & IMPLEMENTATION REPORT

- **Block:** v1.0.65
- **Date:** 2026-10-26
- **Base HEAD:** `7bebf8d91bb4000c61dd0db2127bb1ee6f40a4c9` (unchanged for the whole block — no commits made)
- **Branch:** `main` · repo `morteza-taheri/SoftEthergai`
- **Prior block:** v1.0.64 (DYNAMIC VPN-AWARE SERVER LIST UPDATER) — rechecked from actual tree state, not assumed.

---

## 1. Executive Verdict

**PASS (implementation verified) with two honest caveats:**

1. The v1.0.64 same-refresh tunnel-memory downgrade was **unreachable dead code** (§4): MIRROR discovery can never report failure because `numberedMirrors()` always appends the 6 built-in bootstrap mirrors, so `recordFailure(TUNNEL)` was never invoked. BLOCK v1.0.65 **replaced** the trigger with a per-refresh `TunnelTransportProbe` that observes *actual TUNNEL transport responses* (§5). Test D now proves the downgrade behaviorally; it could not pass before the fix.
2. Physical device verification (Tests 1, 2, 4, 5) **confirms the VPN-first policy end-to-end on hardware**. Test 3 (dead tunnel from the very first request) is **NOT REPRODUCED on device** — a connected-but-unroutable tunnel cannot be forced without root — but the exact scenario is pinned by JVM behavioral test D.

A pre-existing finding (cooperative outer deadline can fire late when a blocking OkHttp call hangs, observed 128 s / 139 s vs nominal 45 s) is recorded as **backlog**, not a v1.0.65 regression (§20).

**Final recommendation: DEFINE TARGETED IMPLEMENTATION PHASE** (§22) — for the backlog items only; everything in the v1.0.65 scope is implemented and verified.

---

## 2. Scope & Continuity — baseline recheck (SOURCE evidence)

Captured at block start (and re-verified at the end — identical except new evidence files):

```
git rev-parse HEAD   → 7bebf8d91bb4000c61dd0db2127bb1ee6f40a4c9
git status --short   → 10 modified tracked files (carried from v1.0.63/64 work),
                       untracked: prior reports, new test files, test-evidence/
adb devices -l       → 5585fecc  device  product:spes_global model:2201117TG
```

The physical device **is available** (Xiaomi Redmi 10C `2201117TG`, Android 13 / SDK 33, arm64-v8a).
Installed package `vn.unlimit.vpngatepro`: **versionName=2.4.11-dev, versionCode=144** (the directive's expected `2.4.10-dev/143` was stale; the actual value is recorded here as the baseline of record).

Uncommitted F.29 work (`App.kt`, `AutoModeController.kt`, `AutoModeEngine.kt`, `AppDatabase.kt`, `AutoModeNextServerIdentityTest.kt`, `vpngate_collector.py`) was **not touched** (final `git status` confirms).

---

## 3. Method & Evidence Classes

Every claim below is labeled: **SOURCE** (code read at a line number), **TEST** (an actually-executed JUnit run with an XML result), **DEVICE** (logcat/dumpsys captured from `5585fecc`), or **INFERENCE** (reasoning, explicitly marked). No "passed" claim is made without a captured result. Test freshness is enforced with per-task `--rerun`; any run whose test task reported `UP-TO-DATE` was **discarded** (this caught one invalid 10-run loop — see §9).

---

## 4. Design Finding — the v1.0.64 downgrade was unreachable (SOURCE)

The critical question from the directive — *does `refreshLocked` select `primary` once and reuse it for mirror/API/HTML?* — is answered: **`primary = ladder.first()` is computed once (VpnServerRepository.kt) and reused for all three sources; MIRROR is awaited before API+HTML start.** That part of v1.0.64 was correct.

However, the v1.0.64 same-refresh trigger (`pathMemory.isCoolingDown(TUNNEL)`) could **never** engage:

- `fetchOnChosenPath` for MIRROR uses `succeeded = { it.isNotEmpty() }`.
- `VpnGateSourceCandidates.numberedMirrors()` = `discovered + cached + bootstrapMirrors()` — `bootstrapMirrors()` is a hardcoded 6-entry list (`VpnGateUrls.BOOTSTRAP_MIRRORS`), so `discoverMirrors()` **always returns non-empty**, even when every TUNNEL request returned null (`VpnGateSources.kt:202–230`).
- Therefore MIRROR "succeeds" on a dead tunnel, `recordFailure(TUNNEL)` is never called, `isCoolingDown(TUNNEL)` stays false, and API/HTML always re-attempted the dead tunnel.

This is corroborated by the project's own test documentation — `VpnServerRepositoryR4bTest`:
> *"mirror discovery never reports empty because of the bootstrap list"*.

**Consequence:** requirement (c) of the directive was not met by v1.0.64 as shipped. v1.0.65 fixes the *mechanism*, not just the test.

---

## 5. Implementation (SOURCE)

All changes are inside the Server-List update path. No Room/schema change, no VPN-architecture change, no `AutoMode*` change, no `bindProcessToNetwork`, no `VpnService.protect` change.

**`VpnServerRepository.kt`:**

1. **`TunnelTransportProbe`** (new private class): wraps the per-refresh bound fetcher; counts TUNNEL `get()` calls that return normally; `failedWithoutSuccess()` is true only when ≥1 TUNNEL request was attempted and none returned a response. Cancelled race-losers throw and are not counted. Scope is **one refresh** — a new instance per `refreshLocked()`, so a failure observed here can never suppress the tunnel on a later run (bounded; no permanent suppression, satisfying the directive's PathMemory-safety requirement more strictly than a cross-refresh cooldown would).
2. **`apiHtmlPrimary`** now keys on `tunnelProbe.failedWithoutSuccess()` (plus `probedBoundFetcher != null && primary == TUNNEL`), replacing the unreachable `pathMemory.isCoolingDown(TUNNEL)`. When true, API and HTML begin on PHYSICAL and the log emits `[ladder] tunnelFailedEarly; API/HTML begin on PHYSICAL`. The **primary only** is downgraded: if PHYSICAL then also fails, the pre-existing last-resort fallback to the opposite rung still fires — justified by the PHYSICAL failure, and it protects the case where only the mirror *directory* endpoint is blocked on the tunnel while API/HTML would work through it.
3. **`mainTimeoutMs`** constructor parameter (default `MAIN_TIMEOUT_MS = 45_000`) — behavioral deadline testability; production value unchanged.
4. **Timeout re-verification (SOURCE):** `MAIN_TIMEOUT_MS = 45_000` (VpnServerRepository.kt:130), `TUNNEL_RETRY_TIMEOUT_MS = 20_000` (:155), HttpFetcher `connectTimeout(10s)` / `readTimeout(15s)` (HttpFetcher.kt:108–109). The 4 s/8 s values seen in an earlier draft were **not adopted**. `MIRROR_TIMEOUT_MS` (30 s) remains defined-but-unreferenced — flagged backlog (§20).
5. `PathMemory` and `routeLadder` are **unchanged**; ladder ordering remains `[TUNNEL, PHYSICAL, EMERGENCY]` / `[PHYSICAL, EMERGENCY]`.

---

## 6. Behavioral Test Matrix A–J (TEST)

`VpnServerRepositoryVpnFirstGuardsTest.kt` (new file, genuine behavioral tests over the production wiring — real `VpnServerRepository` + real `ServerListUpdates` + in-memory DAO counting writes):

| ID | Scenario | Verdict |
|----|----------|---------|
| A | VPN absent → PHYSICAL leads, TUNNEL fetcher never invoked | PASS |
| B | TUNNEL success → PHYSICAL never touched | PASS |
| C | TUNNEL fail → physical fallback completes, exactly one write | PASS |
| **D** | **TUNNEL fails on the leading source → API/HTML skip the dead tunnel (`tunnelFailedEarly`; tunnel logs 1/0/0 for mirror/api/html), one authoritative write** | **PASS** |
| E | Both paths fail → `Preserved`, 0 writes | PASS |
| F | 400 ms injected deadline → `Preserved`, 0 writes, deadline logged | PASS |
| G | Bound factory returns null → degrades to PHYSICAL ("tunnel rung unavailable"), no hang | PASS |
| H | EMERGENCY always last, never promoted (all inputs) | PASS |
| I | Each refresh re-evaluates network state (VPN-off honored next refresh) | PASS |
| J | Single-flight under overlap (exactly 1 write) + cancellation writes nothing | PASS |

Focused class result: **tests=11, skipped=0, failures=0, errors=0** (JUnit XML `TEST-vn.unlimit.vpngate.repository.VpnFirstGuardsTest…xml`).

**Test-fix provenance (honesty record):** D initially failed because the test premise (MIRROR failing on TUNNEL) was impossible per §4. The production probe fix was required first; D then passed. G initially asserted a wrong log string (`no bound VPN network`) and was corrected to the real degradation message (`tunnel rung unavailable`, VpnServerRepository.kt fetchOnChosenPath).

---

## 7. Full Suite — first run exposed 5 stale assertions, all updated (TEST)

First full `:app:testProDebugUnitTest --rerun` after the probe change: **1272 tests, 5 failed** — every failure an assertion of the *old* per-source "try TUNNEL then fall back" sequence that v1.0.65 intentionally changes:

- `VpnServerRepositoryDynamicVpnPathTest › E refresh attempts TUNNEL first and falls back to PHYSICAL…`
- `VpnServerRepositoryR4bTest › a failed tunnel fetch falls back to physical and actually retries…`
- `VpnServerRepositoryR4bTest › tunnel path is chosen when every input holds`
- `VpnServerRepositoryR4bTest › tunnel-path-logs-are-never-lost-under-concurrent-fetch`
- `VpnServerRepositoryRoutingTest › refresh tries TUNNEL first when a usable bound tunnel exists…`

All five were updated to pin the **new** policy: MIRROR still leads on TUNNEL; a dead tunnel downgrades API/HTML to a PHYSICAL primary (`tunnelFailedEarly`); the last-resort opposite-rung fallback remains; the concurrency test still proves no lost `pathLog` writes (now 5 lines/run: `tunnel mirror`, `physical api`, `physical html`, `fallback-to-tunnel api`, `fallback-to-tunnel html`). No assertion was weakened into vacuity: the "MIRROR must not lead with PHYSICAL while TUNNEL is offered" invariant was added in both integration tests.

Re-run: **app tests=1272, skipped=1 (pre-existing skip), failures=0, errors=0; SoftEtherClient tests=184, skipped=0, failures=0, errors=0** (aggregated from JUnit XML).

---

## 8. Focused + module suites (TEST)

- Focused `VpnServerRepositoryVpnFirstGuardsTest`: PASS (§6).
- `:app:testProDebugUnitTest` full: PASS (§7).
- `:SoftEtherClient:testDebugUnitTest` full: PASS (184/0/0/0) — unaffected by the change, run alongside every stability iteration.

---

## 9. Ten genuine consecutive full runs (TEST)

- A first 10-run loop was **discarded as invalid**: `--rerun` had bound only to the trailing task, leaving `:app:testProDebugUnitTest` `UP-TO-DATE` — exactly the "no UP-TO-DATE as fresh" violation the directive forbids. Evidence retained (`test-evidence/v1065/stable_run_*.log` were overwritten by the valid loop; the invalid loop's console record is quoted in the session log).
- Valid loop: 10 consecutive iterations, each invoking `:app:testProDebugUnitTest --rerun` and `:SoftEtherClient:testDebugUnitTest --rerun` (separate invocations after run 4 to guarantee per-task re-execution), with an explicit guard that **fails the run** if either test task reports `UP-TO-DATE`.
- Result: **RUN 1–10 ALL PASS.** Per-log verification: `appTestExec=1 clientTestExec=1 buildSuccess≥1 failedLines=0` for every run.
- One transient infrastructure error (Gradle daemon `BindException: Address already in use`, run 5 of the first valid sequence) was retried under a 3-attempt, infra-signature-only retry policy; it is **not** a test failure and the retried run genuinely executed both suites.

---

## 10. Lint — new vs backlog (TEST)

`:app:lintProDebug` → **BUILD FAILED** with `Lint found 104 errors, 178 warnings, 9 hints` — **bit-identical counts to the v1.0.64 run** (`lint_v1064.log:189 "Lint found 104 errors, 178 warnings and 9 hints"`). Zero lint issues reference `VpnServerRepository.kt` (the only production file v1.0.65 touched). Conclusion: **0 new lint issues introduced by v1.0.65; all 104 errors are pre-existing backlog.**

`SuspiciousIndentation @ ServerListUpdates.kt:540` recheck: **still present** (the over-indented `merged.add(` under `val prev = storedByHost[key]`). Pre-existing (ServerListUpdates.kt was modified by v1.0.63/64 work, not by v1.0.65); left untouched as out-of-scope backlog.

---

## 11. Device path-classification method (DEVICE)

Path classification uses only the app's own log streams (not inference from traffic):
- `[ladder] order=…` → which rungs were offered;
- `[fetch] path=tunnel|physical source=…` (+ `VpnServerRepository: [collector] …`) → which transport each source actually used, and fallback/downgrade events;
- `VpnGateCollector: [bind-physical] ok network=…` → the PHYSICAL socket factory binding;
- `dumpsys connectivity` → VPN network existence (`NetworkAgentInfo … tun0 … Transports: WIFI|VPN`) vs app-only LISTEN requests (which are **not** counted as a VPN);
- `shared_prefs/vpn_setting_data_pro.xml` `CONNECTION_CACHE_UPDATED_AT_KEY` (read via `run-as`, **read-only**) → whether an update was accepted.

Device procedure: APK built from the working tree (`:app:assembleProDebug`, versionCode 144 / 2.4.11-dev), installed with `adb install -r` (**no uninstall, no data clear**). Refreshes were triggered through the real UI (Server List tab → top-bar Refresh icon → `getAPIData(isUserInitiated = true)`).

---

## 12. Device baseline (DEVICE)

- Model `2201117TG` (Redmi 10C), Android 13 / SDK 33, arm64-v8a; serial `5585fecc`.
- Network: WIFI `network{104}` (SSID PARS-M, validated), **no VPN network**; active default 104.
- App: `vn.unlimit.vpngatepro` 2.4.11-dev / 144, `lastUpdateTime=2026-10-26 09:32:09` (fresh `-r` install of this block's build).
- Evidence: `dumpsys_connectivity_baseline.txt`, `dumpsys_after_test1.txt`, `ui_dump_*.xml`.

---

## 13. Test 1 — refresh with VPN disconnected (DEVICE) → **PASS**

```
09:43:47.803 [ladder] order=PHYSICAL,EMERGENCY
09:43:47.807 VpnGateCollector: [bind-physical] ok network=104
09:43:47.810 [fetch] path=physical source=mirror   (+ [collector] same)
09:43:57.915 [fetch] path=physical source=api      (+ [collector] same)
09:43:57.915 [fetch] path=physical source=html     (+ [collector] same)
```
`path=tunnel` count: **0**. All mirror discovery requests (canonical + cached + bootstrap) went over the physical binding. Evidence: `device_test1_manual_refresh.logcat`.

## 14. Test 2 — refresh with VPN connected (DEVICE) → **PASS**

Pre-state: `NetworkAgentInfo{network{106} … ni{VPN CONNECTED} … tun0 … TransportInfo: VpnTransportInfo{sessionId=Japan[118.106.229.134][TCP:1405]} … UnderlyingNetworks:[104]}`.

```
09:47:04.080 [ladder] order=TUNNEL,PHYSICAL,EMERGENCY
09:47:04.084 [fetch] path=tunnel source=mirror
09:47:14.142 [fetch] path=tunnel source=html        (+ api)
09:47:09.944 Accepted mirror_unnumbered … (6 mirror link(s))   ← over TUNNEL
09:47:23.496 Accepted mirror_1 from …/en/ (10 columns, 99 rows) ← over TUNNEL
```
`path=physical` count: **0**; `tunnelFailedEarly` count: **0** (correct: the tunnel worked, so the downgrade must not fire — requirement (d) proven). Update **accepted**: `CONNECTION_CACHE_UPDATED_AT_KEY = 1792995541897` → device-local **2026-10-26 09:49:01**, immediately after the tunnel refresh. Evidence: `device_test2_vpn_connected_refresh*.logcat`, `dumpsys_test2_vpn_connected.txt`, `prefs_after_test2.xml`.

## 15. Test 3 — failing tunnel from the start (DEVICE) → **NOT REPRODUCED (NOT TESTABLE without root)**

A connected-but-unroutable tunnel cannot be forced on this device: killing WiFi kills the physical rung too; blackholing the tunnel requires root iptables or a deliberately dead VPN server. No device claim is made. The scenario **is** proven at JVM level by behavioral test D (§6), which drives the exact production wiring (`routeLadder → refreshLocked → probe → apiHtmlPrimary → fetchOnChosenPath`). Evidence class: TEST (JVM) + DEVICE (absence-of-means, documented).

## 16. Test 4 — VPN disconnect during refresh (DEVICE) → **PASS (routing), with pre-existing backlog observed**

Sequence: refresh started with VPN up → VPN disconnected ~3 s in.

```
09:51:43.745 [ladder] order=TUNNEL,PHYSICAL,EMERGENCY
09:51:43.748 [fetch] path=tunnel source=mirror        ← succeeded pre-disconnect (probe: successes>0)
09:51:49.547 [fetch] path=tunnel source=api / html    ← primaries on TUNNEL (justified: tunnel proven seconds earlier)
09:51:49.584 [fetch] path=tunnel failed (no result); falling back to physical   (api + html; dead-network sockets fail in ~37 ms)
09:52:00.428 [fetch] path=physical succeeded after tunnel failed
09:52:00.427 Accepted html from https://www.vpngate.net/en/ (10 columns, 100 rows)
09:53:51.560 [ladder] deadline exceeded (45000ms); preserving DB   ← fired LATE (see below)
09:53:58.271 VPNGateViewModel: Server list updated atomically: 245 incoming, 395 stored (snapshot saved)
```

- Requirement (b): the physical fallback **did complete** (HTML delivered 100 rows at +17 s, far inside the 45 s budget).
- **Backlog (pre-existing, not v1.0.65):** the API source's physical fallback then hung ~2 min inside a blocking OkHttp/DNS call. `withTimeoutOrNull` is cooperative — it can only cancel at a suspension point, so the "45 s" outer deadline fired at +128 s. No corruption: the DB was preserved by the deadline guard and the updater's fallback path then completed with G-F29-1 semantics intact (395 stored preserved).
- The `tunnelFailedEarly` downgrade correctly did **not** fire here (MIRROR's tunnel requests had succeeded just before the disconnect) — the guard only downgrades a tunnel that produced **nothing**.

## 17. Test 5 — repeat refresh after disconnect (DEVICE) → **PASS (routing)**

```
09:57:57.197 [ladder] order=PHYSICAL,EMERGENCY
09:57:57.202 [fetch] path=physical source=mirror
09:58:07.260 [fetch] path=physical source=api / html
```
`path=tunnel` count: **0** — each refresh re-derives the ladder from live state (I). On this operator the physical run was slow; the same cooperative-deadline backlog was observed (deadline log at 10:00:16, then `Server list updated atomically: 245 incoming, 395 stored (snapshot saved)` — the stored list and its OpenVPN profiles were never replaced by a degraded dataset).

## 18. Preserved semantics (SOURCE + TEST + DEVICE)

- **G-F29-1 / `mergeFallback`** (ServerListUpdates.kt) untouched; emergency/`origin`-reset/snapshot semantics exercised by `EmergencyMergeFallbackTest`, `EmergencyMergePolicyTest`, `ServerListRetentionTest` (all green in the 10-run loop).
- **Emergency rung** still last and never promoted (test H; device runs show `order=…,EMERGENCY`).
- **UDP / SoftEther protocol parsing** untouched (no parser changes in this block; `EmergencyCsvParser.kt` carries only the earlier v1.0.63/64 working-tree edits).
- **DatasetIntegrity / last-known-good** unchanged: both device failure paths ended with "395 stored" intact.
- **AutoMode / F.29 files** untouched.

## 19. Requirement mapping (a)–(f)

| Requirement | Verdict | Evidence |
|---|---|---|
| (a) usable VPN → tunnel attempted first | **PASS** | Test B (JVM); Test 2 (DEVICE `order=TUNNEL,PHYSICAL,EMERGENCY`) |
| (b) tunnel stall/failure → physical fallback within deadline | **PASS / PARTIAL** | Test C (JVM); DEVICE Test 4 (html fallback +17 s). PARTIAL: cooperative deadline can fire late under a blocking hang (backlog B-1) |
| (c) after a same-refresh tunnel failure, remaining sources don't re-try the dead tunnel without justification | **PASS** (after §4/§5 fix) | Test D (JVM); Test 4 (DEVICE: the only TUNNEL retries were justified by a proven-working tunnel seconds earlier, and failed in ~37 ms before justified fallback) |
| (d) successful VPN response → no unnecessary physical requests | **PASS** | Test B (JVM); Test 2 (DEVICE: `path=physical` count 0) |
| (e) failed/degraded downloads never replace last-known-good | **PASS** | Tests E/F (JVM); DEVICE Tests 4–5 (395 stored intact; `Preserved` → snapshot path) |
| (f) device evidence distinguishes VPN vs physical requests | **PASS** | Per-source `[fetch]/[collector] path=…` lines captured per test (§13–§17) |

## 20. New findings / backlog (INFERENCE, clearly labeled)

- **B-1 (pre-existing):** the outer deadline is cooperative; a blocking OkHttp/DNS hang inside a source extends the refresh beyond `MAIN_TIMEOUT_MS` (observed 128 s and 139 s). Suggested targeted fix: make `OkHttpFetcher` suspension-cancellable (`suspendCancellableCoroutine` + `call.cancel()`) so `withTimeoutOrNull` can actually cut hung sources.
- **B-2 (pre-existing):** `MIRROR_TIMEOUT_MS` (30 s) is defined but never referenced — dead constant.
- **B-3 (observation):** in the mid-refresh death case the API/HTML rungs still attempt TUNNEL once (justified by MIRROR's earlier success) and fail in ~37 ms; the cost is negligible and the fallback is justified, so no change is recommended.
- **B-4 (backlog, unchanged):** `SuspiciousIndentation @ ServerListUpdates.kt:540` and the 104-error lint backlog.

## 21. Risks

- The probe changes when API/HTML *begin* on PHYSICAL; if the tunnel is alive for API/HTML but the mirror directory is dead **and** physical is blocked, those sources still recover via the last-resort opposite-rung fallback (retained deliberately). Worst case is added latency, never a lost update path.
- No persistence, no schema, no scheduler, no `AutoMode*` behavior changed → upgrade path from 2.4.11-dev/144 is inert.

## 22. Final Recommendation

**DEFINE TARGETED IMPLEMENTATION PHASE** — scoped to backlog items **B-1** (cancellable transport so the 45 s deadline is real) and **B-2** (wire or delete `MIRROR_TIMEOUT_MS`), plus optionally **B-4** lint cleanup. The v1.0.65 scope itself is complete: implemented, behaviorally pinned (A–J), and device-verified.

## 23. Change-Control Confirmation

- **No** `git reset / clean / stash / commit / tag / push / release` performed. HEAD remained `7bebf8d…` throughout and at the end.
- **No** uninstall or clear-data on the device; installs used `adb install -r` only; DB access was read-only via `run-as cat`.
- **No** changes to `AutoMode*`, `App.kt`, `AppDatabase.kt`, protocol-priority code, Room schema, or VPN architecture.
- Files written/modified by this block:
  - `app/src/main/java/vn/unlimit/vpngate/repository/VpnServerRepository.kt` (probe + `apiHtmlPrimary` + `mainTimeoutMs`; inside the already-modified v1.0.63/64 working tree)
  - `app/src/test/java/vn/unlimit/vpngate/repository/VpnServerRepositoryVpnFirstGuardsTest.kt` (new)
  - `app/src/test/java/vn/unlimit/vpngate/repository/VpnServerRepositoryR4bTest.kt`, `VpnServerRepositoryRoutingTest.kt`, `VpnServerRepositoryDynamicVpnPathTest.kt`, `VpnServerRepositoryRoutingDynamicTest.kt` (assertions updated to the new policy; grep fixes L / T-NEW-63)
  - `test-evidence/v1065/*` (device + build + lint evidence), this report.
- Final tree state: 11 modified tracked files (825 insertions / 115 deletions across v1.0.63→65 work), new untracked test/evidence files; identical file set to block start plus this block's additions.

---

### Evidence index (`test-evidence/v1065/`)

| File | Content |
|---|---|
| `stable_run_1..10.log` | 10 genuine full-suite runs (app+SoftEtherClient), guards verified no UP-TO-DATE |
| `lint_v1065.log`, `assemble_proDebug.log` | lint (104/178/9, identical to v1.0.64) and APK build logs |
| `device_test1_manual_refresh.logcat` | Test 1 routing (PHYSICAL-only, 0 tunnel) |
| `device_test2_vpn_connected_refresh*.logcat`, `dumpsys_test2_vpn_connected.txt`, `prefs_after_test2.xml` | Test 2 routing (TUNNEL-only) + accepted write 09:49:01 |
| `device_test4_disconnect_during_refresh.logcat`, `device_test4_late_dump.logcat`, `prefs_after_test4.xml`, `prefs_now.xml` | Test 4 full timeline incl. late deadline + preserved/merged outcome |
| `device_test5_after_disconnect.logcat`, `device_test5_late*.logcat` | Test 5 routing (PHYSICAL-only after disconnect) |
| `dumpsys_connectivity_baseline.txt`, `dumpsys_after_test1.txt`, `dumpsys_after_test4.txt` | VPN presence/absence proof per phase |
| `ui_dump_*.xml` | UI automation provenance (tabs, refresh button, consent state) |

*Report ends. Nothing was pushed; the tree is left uncommitted exactly as required.*
