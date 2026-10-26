# BLOCK v1.0.64 — DYNAMIC VPN-AWARE SERVER LIST UPDATER
### Final Implementation & Verification Report

| | |
|---|---|
| **BLOCK ID** | v1.0.64 — Dynamic VPN-Aware Server List Updater |
| **Date** | 2026-10-25 |
| **Author** | Operator-directed implementation (WorkBuddy agent) |
| **Repository** | `morteza-taheri/SoftEthergai` (local `/c/Dev/SoftEthergai`) |
| **Branch / HEAD** | `main` @ `7bebf8d91bb4000c61dd0db2127bb1ee6f40a4c9` (re-verified this session) |
| **Overall verdict** | **PASS** (logic, unit, lint, build). Physical-device run **DEVICE_UNAVAILABLE**. |

> Status label vocabulary used below: **PASS**, **PARTIAL**, **FAIL**, **NOT TESTABLE**, **DEVICE_UNAVAILABLE**.

---

## §1. Mission
Implement a narrowly-scoped, *dynamic network-path policy* for the Server List updater such that:
- (a) When **no usable VPN Network** exists, the request is attempted over the **physical** network.
- (b) When **VPN is active and a usable+bound VPN Network exists**, the **VPN path is genuinely attempted first** (deterministic: VPN with a bounded timeout, then physical fallback), and the selected path is recorded.
- (c) **Both outcomes are recorded independently** (success/failure for each rung).
- (d) No stale-network hang, no duplicate writes, **no process-wide binding** (`bindProcessToNetwork` forbidden), **no `VpnService.protect()` change**, no routing-table / circular-dependency changes.
- (e) Preserve all existing merge / integrity / emergency / SoftEther-UDP semantics and the **G-F29-1 `mergeFallback` dependency**.
- (f) Add an automated routing test matrix **A–Q**.
- (g) Run lint, build, both JVM suites, repeated full-suite runs.
- (h) Physical-device test when available (serial `5585fecc`).

## §2. Re-check of HEAD & Working Tree (directive-mandated)
The directive explicitly required re-verifying the repository rather than assuming it unchanged since v1.0.63.

- `git rev-parse HEAD` → `7bebf8d91bb4000c61dd0db2127bb1ee6f40a4c9` (unchanged from v1.0.63).
- `git status --short` → 6 modified tracked files (`F29_PHASE2_IMPLEMENTATION_REPORT.md`, `PROJECT_SPECIFICATION.md`, `EmergencyCsvParser.kt`, `ServerListUpdates.kt`, `EmergencyCsvParserTest.kt`, `ServerListRetentionTest.kt`) + numerous untracked report/test files. **None of the v1.0.64 changes touch these files' semantics.**
- `adb devices -l` → **List of devices attached** (empty).
- Re-read `VpnServerRepository.kt` (full), `HttpFetcher.kt` (routing-relevant sections), `GlobalVpnTracker.kt` (routing inputs), and all routing test files. **Verdict: PASS** — recheck confirms the v1.0.63 audit baseline is intact and the routing gap described below is real.

## §3. BLOCK v1.0.63 Dependency Audit (re-confirmed)
Re-verified that v1.0.64 does **not** disturb the v1.0.63 guarantees:
- `EmergencyCsvParser.kt:419–451` — UDP independence preserved; no UDP derived from TCP. **PASS.**
- `ServerListUpdates.kt` origin reset (`:101`), emergency origin (`:142`), `mergeAll :516–557`, `mergeFallback :588–664` — **G-F29-1 dependency intact**; `mergeFallback` still invoked exactly when the primary merge yields no usable records. **PASS.**
- SoftEther-UDP eligibility `AutoModeController.kt:3188–3189` (`(seUdpSupported||seUdpPort>0)&&seTcpPort>0`) — untouched. **PASS.**
- `DatasetIntegrity` gating before any DB write — untouched. **PASS.**
- **Verdict: PASS** — no dependency broken.

## §4. Gap Analysis (the actual defect)
`routeLadder(input, memory)` (VpnServerRepository.kt) produced `[PHYSICAL, TUNNEL, EMERGENCY]` (or `[PHYSICAL, EMERGENCY]` when TUNNEL unavailable) and only promoted TUNNEL to the front via `PathMemory` *evidence* (PHYSICAL cooling-down or TUNNEL previously proven). Consequently, **when VPN was active, usable, and bound but no memory evidence existed, the VPN path was NOT genuinely attempted first** — PHYSICAL led. This violates directive §4.2/§4.3. **Verdict: FAIL (prior state) → fixed.**

## §5. Design Decision
Minimal, surgical change: make the **base ladder TUNNEL-first when a usable+bound VPN Network exists**.
- When `tunnelUsable && hasBoundNetwork` → `[TUNNEL, PHYSICAL, EMERGENCY]` (TUNNEL = primary, genuinely attempted; PHYSICAL = fallback; EMERGENCY = last).
- Otherwise → `[PHYSICAL, EMERGENCY]`.
- `fetchOnChosenPath` (unchanged) already runs the primary, and on failure falls back to the opposite rung, recording both outcomes in `PathMemory`. The existing evidence-promotion block is retained (now a no-op when TUNNEL already leads) purely for API/signature stability.
- **No other production code changed.** No new timeout machinery (the fetcher already has `connectTimeout(10s)` / `readTimeout(15s)` and `MAIN_TIMEOUT_MS` / `TUNNEL_RETRY_TIMEOUT_MS` bound the refresh).
- **Verdict: PASS.**

## §6. Implementation Detail (`VpnServerRepository.kt`)
- `routeLadder` (lines ~279–305): base order now `if (tunnelRungAvailable) [TUNNEL, PHYSICAL] else [PHYSICAL]`, then `+= EMERGENCY`.
- KDoc updated on `productionRouting` (~line 170), `routeLadder` (~line 260), and `refreshLocked` (~line 441) to state the deterministic VPN-first order accurately.
- Files changed: `app/src/main/java/vn/unlimit/vpngate/repository/VpnServerRepository.kt` only (production). **Verdict: PASS.**

## §7. Preservation of Existing Semantics
- Merge: `mergeAll` / `mergeFallback` order and G-F29-1 call site unchanged.
- Integrity: `DatasetIntegrity` gate before write unchanged.
- Emergency: `hasEmergencySources()` + `emergencyRecords()` path unchanged; EMERGENCY still last, never promoted, never a fallback rung.
- SoftEther-UDP: eligibility unchanged.
- One-shot TUNNEL retry (`TUNNEL_RETRY_TIMEOUT_MS`, re-armed only by a run that starts with a usable tunnel) unchanged.
- **Verdict: PASS.**

## §8. Forbidden-Operation Audit
Source grep on `VpnServerRepository.kt` and `HttpFetcher.kt`:
- `bindProcessToNetwork` — present **only in comments** (3 locations); **no code call**. **PASS (compliant).**
- `VpnService.protect` — **absent**. **PASS (compliant).**
- `ConnectivityManager.bindProcessToNetwork` — **absent**. **PASS (compliant).**
- No routing-table, no circular-dependency, no `Room`/schema, no protocol-priority, no VPN-lifecycle changes. **PASS.**
- **Verdict: PASS** — zero forbidden operations introduced.

## §9. Genuine VPN Attempt & Bounded Timeout
- When VPN active+usable+bound: `primary = ladder.first() = TUNNEL`; `fetchOnChosenPath` invokes `fetch(boundFetcher)` (the VPN-bound `OkHttpFetcher`) **before** any physical attempt. Test **Q** proves the bound VPN fetcher is actually invoked.
- Bounded by `connectTimeout(10s)` + `readTimeout(15s)` on the bound client (HttpFetcher.kt:108–109) and the `MAIN_TIMEOUT_MS` wrapper around `refresh()`; the one-shot retry is bounded by `TUNNEL_RETRY_TIMEOUT_MS`.
- **Verdict: PASS.**

## §10. Physical-Device Verification
`adb devices -l` returns **no devices**. The test device `5585fecc` (Xiaomi Redmi 10C, Android 13) is **not attached**.
- On-device end-to-end run of the updater over a live VPN/physical split could **not** be executed.
- All on-device-dependent scenarios are therefore classified **DEVICE_UNAVAILABLE** and rely on the JVM evidence below.
- **Verdict: DEVICE_UNAVAILABLE** (no hardware present; not a logic failure).

## §11. Acceptance — VPN-first when VPN active+usable+bound
- Test **B** (unit): `ladder(true,true) == [TUNNEL, PHYSICAL, EMERGENCY]`. **PASS.**
- Test **E** (refresh integration): with `tunnelUsable=true, hasBoundNetwork=true`, the first `[fetch]` line is `path=tunnel`, ladder order logged `TUNNEL,PHYSICAL,EMERGENCY`, every PHYSICAL attempt is a fallback. **PASS.**
- Test **Q**: the TUNNEL primary genuinely invokes the bound VPN fetcher. **PASS.**
- **Verdict: PASS.**

## §12. Acceptance — Physical fallback both directions
- `fetchOnChosenPath` fallback mechanics unchanged and re-pinned by `VpnServerRepositoryRoutingTest` (permutations 1–7) and `VpnServerRepositoryR4bTest` (TUNNEL→PHYSICAL fallback, PHYSICAL→TUNNEL fallback). Both directions verified.
- **Verdict: PASS.**

## §13. Acceptance — Both outcomes recorded independently
- Test **G**: TUNNEL fail + PHYSICAL success → `pathMemory.isCoolingDown(TUNNEL)==true` AND `pathMemory.preferred()==PHYSICAL`.
- Test **H**: both fail → both cooling-down, `preferred()==null`.
- `pathLog` records the chosen path per source; `fetchLog` records the decision.
- **Verdict: PASS.**

## §14. Acceptance — No usable VPN → physical
- Test **A** (`ladder(false,false)==[PHYSICAL,EMERGENCY]`), **C** (usable tunnel, no bound network → no TUNNEL rung), **D** (bound network, dead tunnel → no TUNNEL rung).
- **Verdict: PASS.**

## §15. New Automated Routing Test Matrix A–Q
New file: `app/src/test/java/vn/unlimit/vpngate/repository/VpnServerRepositoryDynamicVpnPathTest.kt` — **17 tests, 0 failures**.
- **A** no-usable-VPN ladder • **B** VPN-first ladder • **C** usable-but-unbound no TUNNEL • **D** bound-but-dead no TUNNEL • **E** refresh TUNNEL-first + PHYSICAL fallback • **F** TUNNEL success recorded, no PHYSICAL touch • **G** TUNNEL-fail + PHYSICAL-success both recorded • **H** both-fail both recorded • **I** EMERGENCY always last/never promoted • **J** no process-wide bind • **K** no `VpnService.protect` • **L** bounded connect/read timeouts + total deadline • **M** no duplicate writes (≤1 attempt/rung) • **N** inputs re-read per refresh (no stale network) • **O** invariants across all inputs+states • **P** `resolvePrimary` VPN-first • **Q** TUNNEL primary genuinely invokes bound VPN fetcher.
- **Verdict: PASS (17/17).**

## §16. Full Test & Build Execution
- `./gradlew :app:testDebugUnitTest` (proDebug unit variant): **1261 tests, 0 failures, 0 errors** across 100 result files.
- Targeted routing run (4 classes): Ladder 21/21, RoutingDynamic 11/11, Routing 9/9, DynamicVpnPath 17/17.
- Three executions performed (filtered, full, and a forced `--rerun-tasks` clean run) — all **BUILD SUCCESSFUL**.
- **Verdict: PASS.**

## §17. Lint (new vs backlog, separated)
- `./gradlew :app:lintProDebug` → 104 errors / 178 warnings **in total**, of which **0 reference `VpnServerRepository.kt`** → **0 NEW findings from this BLOCK.**
- Backlog categories: `MissingTranslation`×70, `UnusedResources`×122, `UseKtx`×14, `PluralsCandidate`×9, `ObsoleteSdkInt`×5, `TypographyEllipsis`/`IconLauncherShape`/`IconLocation`×5 each, `MonochromeLauncherIcon`×2, plus singletons (`ApplySharedPref`, `CommitPrefEdits`, `NewApi`, `UnusedAttribute`, `BatteryLife`, `HardwareIds`, `VPN`, `Untranslatable`).
- **`SuspiciousIndentation` at `ServerListUpdates.kt:540`** is a **pre-existing BLOCK v1.0.63 item** (F29 work), **not** introduced here, and is **left unfixed** (out of narrow scope, per directive). Accurately classified as **backlog**.
- **Verdict: PASS (no new lint); backlog documented, not fixed.**

## §18. Risk Assessment
- **Low risk.** Change is confined to ladder *ordering*; `fetchOnChosenPath`, merge, integrity, emergency, and `GlobalVpnTracker` are untouched.
- Determinism: VPN-first is now input-driven (usable+bound), removing dependence on cooldown memory, which also removes a class of "VPN never retried" regressions.
- No network-path behavioral change for the no-VPN case.
- **Verdict: PASS (low risk).**

## §19. Regression Risk vs prior BLOCKs
- v1.0.63 F29 merge/emergency suite still green (part of the 1261-run suite).
- `VpnServerRepositoryR4bTest` (TUNNEL/PHYSICAL logging + fallback) passes unchanged — its `tunnelRepository` seeds memory, which remains valid under the new default TUNNEL-first order.
- **Verdict: PASS.**

## §20. Observability / Selected-path Recording
- `refreshLocked` logs `[ladder] order=TUNNEL,PHYSICAL,EMERGENCY` before any attempt — the **selected primary path is recorded** in the standard `[ladder]`/`[fetch]`/`[collector]` streams.
- Both rung outcomes persist in `PathMemory` (in-process, bounded, no PII, cleared on process restart).
- **Verdict: PASS.**

## §21. Change Control Confirmation
| Destructive operation | Performed? |
|---|---|
| `git reset` / `git clean` / `git stash` | **NONE** |
| `git commit` / `git tag` / `git push` / `git release` | **NONE** |
| File deletion (user data or source) | **NONE** |
| Room / DB schema migration or wipe | **NONE** |
| `bindProcessToNetwork` / `VpnService.protect` change | **NONE** |
| Force-push / branch overwrite | **NONE** |
| Untracked new files written | Only: this report + `VpnServerRepositoryDynamicVpnPathTest.kt` (new test) + updated 4 routing test files + the production edit to `VpnServerRepository.kt`. |

**Change-control verdict: PASS — all destructive operations = NONE. Working tree preserved exactly; no history altered.**

---
### One-line conclusion
BLOCK v1.0.64 is **implemented and verified**: the Server List updater now genuinely attempts the VPN path first whenever a usable+bound VPN Network exists (bounded, with physical fallback and independent outcome recording), with **zero new lint issues, 1261 unit tests green, and no forbidden operations** — pending only an on-device run that is **DEVICE_UNAVAILABLE** in this environment.
