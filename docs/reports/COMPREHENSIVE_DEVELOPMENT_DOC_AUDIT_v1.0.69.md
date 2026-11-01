# COMPREHENSIVE DEVELOPMENT DOCUMENT AUDIT — SoftEthergai

**Scope:** requirements coverage, consistency, and implementation traceability
**Mode:** READ-ONLY — no production code, test, or existing document was modified
**Branch:** `main` · **HEAD audited:** `08c6abe48be766be76830d249af41e625b6aa3ff`
**Audit date:** 2026-10-26

---

## 1. Executive Verdict

**DOCUMENTATION GAPS FOUND — PREPARE DOCUMENTATION UPDATE.**

The authoritative specification (`PROJECT_SPECIFICATION.md`, v1.0.63, last updated 2026-10-25, pinned to HEAD `51fe1d2`) **predates the current branch by six revisions** (`08c6abe`, 2026-10-26). It therefore does not describe v1.0.64–v1.0.69 (VPN-first routing guards, cancellable fetch, exclusion/history, schema 9→10, history-aware exploration), and it **contradicts four of the newly agreed decisions**:

1. **Per-protocol timeout** — document: 8 s default, 8–60 s range; requirement: **12 s default, 8–30 s**. Source currently implements the document's 8/60.
2. **Default protocol order** — document: `SOFTETHER_TCP → SOFTETHER_UDP`; requirement: **UDP → TCP → SSTP**.
3. **Server-list routing** — document: a ladder that *falls back* from TUNNEL to PHYSICAL; requirement: when VPN is active, **VPN-only, no silent fallback**.
4. **500-server cap** — **no such requirement exists anywhere in the documentation** (the "500" hits are milliseconds).

Strongly documented areas: excluded-app seeding (§11.14.4, §1.6.50), UDP/TCP endpoint integrity (§1.6.45, §10.3), tri-state reachability and the D1 gate (§11.15.5), the dial order (§6.3, R3/R4), and evidence labelling (SOURCE/DEVICE/INFERENCE is used throughout).

---

## 2. Authoritative Documentation and Repository Revision

| Item | Value |
|---|---|
| Branch | `main` |
| HEAD | `08c6abe48be766be76830d249af41e625b6aa3ff` |
| Spec version | **1.0.63** |
| Spec last updated | **2026-10-25** |
| Spec's stated HEAD | **`51fe1d2`** ("last code commit of this block") |
| Distance from branch | **stale**: `08c6abe` is six commits later and includes v1.0.64–v1.0.69 |

**Authority resolution.** The spec's own documentation map (lines 11–13) declares `PROJECT_SPECIFICATION.md` the canonical **requirements/status** document, `SERVER_SOURCE_ARCHITECTURE.md` the canonical **design-only** document (sources, paths, emergency CSV, cumulative retention), and `README.md` the public overview. `AGENTS.md` governs commit messages only. All other root Markdown files are **reports** (evidence artifacts), including the untracked v1.0.64–v1.0.69 reports. **Reports are not authoritative** and were used here only as pointers to source, never as requirements. `docs/reports/` contains historical audit registers only.

---

## 3. Documentation Inventory

| Document | Role | Authority | Freshness |
|---|---|---|---|
| `PROJECT_SPECIFICATION.md` | requirements + status | **canonical** | **STALE** (v1.0.63 / `51fe1d2`) |
| `SERVER_SOURCE_ARCHITECTURE.md` | source/path design; §9 retention; §10 CSV | **canonical (design)** | partially stale; §9 says "NOT IMPLEMENTED" while phase 1 landed |
| `README.md` | public overview | descriptive | not audited as requirements |
| `AGENTS.md` | commit-message convention | operational | current |
| `BLOCK_v1.0.64…v1.0.69*.md` | block reports | **not authoritative** (untracked) | describe work absent from the spec |
| `docs/reports/*` | historical audit registers | **not authoritative** | historical |
| `D1_CANDIDATES.md`, `*_REPORT.md` | working notes / evidence | not authoritative | — |

No second document claims requirements authority; there is **no duplicated conflicting specification**, only a stale one.

---

## 4. Requirements Coverage Matrix

> Document status: D = DOCUMENTED · P = PARTIALLY DOCUMENTED · M = MISSING · C = CONFLICTING · S = STALE · U = UNVERIFIABLE.
> Implementation status: I = IMPLEMENTED · PI = PARTIALLY IMPLEMENTED · NI = NOT IMPLEMENTED · UK = UNKNOWN.

| ID | Requirement | Doc | Impl | Evidence location | Gap / conflict | Follow-up |
|---|---|---|---|---|---|---|
| A | Authoritative tunnel state + UI sync | **P** | PI | spec §1.6.10 (Axis A), §1.6.32, line 2571 (`Connected (manual)`), line 6019 | Two state models reconciled but no single declared source of truth; mismatch (VPN badge vs UI error) policy not explicit; 6-state set not enumerated | Define authoritative source + state table + mismatch rule |
| B | Try Next + connected-server replacement | **P** | PI | spec §6.3, R3/R4 (lines 4170–4190), §11.13.3 | Protocol loop and one-attempt-per-server documented; **make-before-break / parallel tunnels / preserve-current-until-success absent** | Add replacement-while-connected section |
| C | 500-server cap + retention ranking | **M** | NI | none; arch §9.1 proposes 1000 (INFERENCE) | **No 500 requirement anywhere**; arch default 1000 contradicts | Add normative 500 cap + prune rules |
| D | Default settings (8 items) | **C** | PI | spec line 4196 (8 s / 8–60), line 3168 (TCP→UDP), §11.13 | Timeout 8/60 vs 12/30; protocol order TCP→UDP vs UDP→TCP→SSTP; max connections 4/8 **absent**; developer/custom-DNS defaults not stated as requirements | Rewrite settings table |
| E | First-run excluded apps | **D** | I | spec §11.14.4 (line 5220), §1.6.50 (line 3216) | Desired list lives in `assets/default_excluded_apps`, documented as closed; four normative rules + tests present | None (list location should be cited from the spec) |
| F | Server-list routing (VPN-only) | **C/S** | PI | spec §1.6.38, §1.6.34; ladder text | Documents **fallback** to PHYSICAL; requirement forbids silent fallback when VPN active; v1.0.64/65 absent | Replace ladder policy |
| G | VPN-routed verification plan | **P** | PI | spec device-verification rows; §1.6.38 bullet 11 (line 2916) | No taxonomy of confirmed/likely/not-observable; plan is per-block, not a standing requirement | Add standing plan |
| H | SoftEther UDP/TCP + endpoint integrity | **D** | I | spec §1.6.45 (line 461), §10.3 (lines 1204–1206), line 3118 | `SE_UDP_Port=0` correct, no borrowing, no fabrication — explicit | None |
| I | TUN EIO diagnostics | **P** | PI | spec §1.6.10 (Axis C), §1.6.17 lines 2645–2656, line 5355 | EIO is a liveness signal with a window; **no contract for attempt-id/server/protocol/endpoint correlation** | Add diagnostic record schema |
| J | Tri-state reachability + D1 | **D** | I | spec §11.15.5 (lines 5298, 5350), line 5527 | UNKNOWN keeps the server; only all-UNREACHABLE removes — cases A/B/C covered in substance | Add explicit A/B/C labels |
| K | Final dial order | **D** | I | spec §6.3 (line 4090), R3/R4 (lines 4170–4190) | Order documented; "remove only definitively doomed" is stated as "keep unless every usable endpoint UNREACHABLE" | None material |
| L | SSTP and L2TP | **P** | PI | spec §1.6.45 (SSTP `:port`), lines 3690–3692 (L2TP deprecated), 1606 | SSTP strong; L2TP has support/deprecation notes but **no Auto Connect/reachability requirement or tests** | Add L2TP requirement + NOT TESTABLE rule |
| M | Crash / process death / service lifecycle | **P** | PI | spec lines 3328, 3377 (`FATAL`/`ANR`/tombstone) | Only FATAL/ANR/tombstone; **no `RemoteServiceException`, `DeadObjectException`, `ForegroundServiceDidNotStartInTimeException`**; no "collect evidence before restart" rule | Add procedure + exception list |
| N | Battery / background observation | **M** | UK | only battery-optimisation prompt (§1.6.32) | No read-only Device Idle / power / restriction observation requirement | Add section |
| O | Dataset integrity | **P** | PI | spec §11.11, §1.6.16, DatasetIntegrity text | Integrity gate documented; **500 cap missing**; duplicate-IP rule not stated | Add cap + identity note |
| P | Retention/health data model | **P** | PI | spec §11.15.9–§11.15.10; source `VPNGateItem` vs `server_history_summary` | Requirement names (`lastSuccessfulConnectionAt`) differ from source (`lastSuccessAt`); item table has only 3 fields; history table has 24 | Document field-by-field matrix |
| Q | No unsupported ISP claims | **M** | NI | lines 2916/3090 assert "physical path blocked by the ISP" | **No prohibition exists; the document itself makes the claim** | Add prohibition + classification |
| R | Evidence separation / result classes | **D** | I | spec uses SOURCE CODE / TEST / PHYSICAL DEVICE / UNKNOWN throughout; §11.15 labels | Result classes used but not defined as a normative set | Formalize taxonomy |
| S | Read-only physical-test safety | **P** | PI | block reports (not spec) state no-code-change rules | Not a requirement in the spec | Add normative section |
| T | Timeout + cancellation semantics | **C/P** | PI | spec §1.6.39 (line 449), §1.6.44 (line 4196), §6.3 | Per-attempt semantics documented; **default/range conflict**; cleanup/late-callback rules implicit | Rewrite timeout contract |
| U | Connection origin + reconnect | **P** | PI | spec line 2571, §11.13 (auto-reconnect setting), line 4384 | Manual is synthesized (`Connected (manual)`); auto-reconnect is a setting; **no persisted 3-value origin** | Add origin model |
| V | Verification/test matrix | **P** | PI | spec §11.15.11 (history test plan), §12.6 | No requirement-ID traceability matrix with testability classification | Add matrix |

---

## 5. Tunnel State and UI Synchronization (Requirement A)

The spec records the problem honestly but does not resolve it as a requirement. §1.6.10 and the v1.0.11 register (line 6019) state: *"two concurrent state models with no designated UI owner — `GlobalVpnTracker` vs `AutoModeController._state`/`AutoModeEngine.stateFlow`, both consumed in one composable"*. Mitigations are documented (`VpnStackLedger.reconcile`, single `reportArmedTunnelLoss` funnel, `combineAutoModeState`, line 2571's `Connected (manual)` synthesis), but the document never declares **which layer is authoritative**, never enumerates the six required states, and never states what the UI must show when the OS VPN badge says connected while the app reports an error. The "evidence required before displaying Connected" rule is implicit in `awaitArmedTunnel` (§6.3) but not stated as a UI contract.

**Status: PARTIALLY DOCUMENTED.** Implementation: PARTIALLY IMPLEMENTED (reconciliation exists; single ownership does not).

---

## 6. Try Next Server and Connected-Server Replacement (Requirement B)

Documented precisely for the **disconnected** case: §6.3 attempt lifecycle (arm → connect → `awaitArmedTunnel(timeoutMs)` → `finally endAttempt`), R4 sequential testing, one-attempt-per-server, protocol priority, and §11.13.3 Next-Server continuation by `ip|hostname` identity (deliberate reversal of the v1.0.31 index decision). Stale-callback protection is described through armed-state clearing and the `skipInFlight`/`transitionLock` guards.

**Missing:** any requirement for switching while already connected — whether overlapping tunnels are possible, whether make-before-break is supported, what happens if the replacement fails, and whether the current tunnel is preserved. The word "make-before-break" does not occur in the spec. A source-level answer is possible (`AutoModeController` refuses a second competing tunnel, per line 4399), but it is not documented as the policy.

**Status: PARTIALLY DOCUMENTED.** Implementation: PARTIALLY IMPLEMENTED (no make-before-break).

---

## 7. Server Retention Cap and Ranking (Requirement C)

**No 500-server requirement exists.** `grep -E "500 (servers|records|rows)"` returns nothing; every "500" in the spec is a millisecond value (500 ms `onLost` budget, 5000 ms race). `SERVER_SOURCE_ARCHITECTURE.md` §9.1 instead proposes **1000** as an *INFERENCE* default with a 100–10000 range, and §9.7 leaves the health formula (Q1), protocol weighting (Q2), and cap-vs-staleness interaction (Q4) **UNKNOWN**. The spec documents cumulative retention **phase 1 only** (add-only merge; `firstSeenAt`/`lastSeenAt`/`healthScore`), and explicitly says phases 2–3 (cap + priority prune) are out of scope.

Consequently every sub-requirement — 500, ranking by quality, protocol breadth, success history, deterministic pruning, transactional failure, UI/persisted count consistency — is **undocumented**. Duplicate/identity rules and manual-record protection are documented (§11.11) and implemented.

**Status: MISSING.** Implementation: NOT IMPLEMENTED.

---

## 8. Default Settings (Requirement D)

| Setting | Requirement | Document says | Source does | Verdict |
|---|---|---|---|---|
| Max SoftEther connections | 4 default | **not specified** | `DEFAULT_SOFTETHER_MAX_CONNECTIONS = 4` | **MISSING** |
| Hard cap | 8 | **not specified** | `MAX_SOFTETHER_CONNECTIONS = 8` | **MISSING** |
| Developer Mode | OFF | mentioned only as a device-run state ("Developer Mode ON"), no default requirement | `DEFAULT_DEVELOPER_MODE = false` | **MISSING** (as requirement) |
| Developer logging | OFF | not stated | follows Developer Mode | **MISSING** |
| Custom DNS | OFF | only the v1.0.54 removal note; no default | (not re-verified in this audit) | **MISSING** |
| Protocol order | UDP → TCP → SSTP | `DEFAULT_AUTO_PROTOCOL_PRIORITY = [SOFTETHER_TCP, SOFTETHER_UDP]` (line 3168) | same | **CONFLICTING** |
| Max stored servers | 500 | absent | absent | **MISSING** |
| Per-protocol timeout | 12 s default | **8 s default** (line 4196) | `8` (`DataUtil.kt:309`) | **CONFLICTING** |
| Min timeout | 8 s | 8 s floor | 8 | matches |
| Max timeout | 30 s | **60 s ceiling** | 60 | **CONFLICTING** |

The document *does* distinguish the per-attempt timeout from the total run (the total is bounded by `MAIN_TIMEOUT_MS = 45 s` for the collector; Auto Mode has its own run budget), but it does not define validation/clamping to **8–30** and does not state UI representation, persistence, or legacy-value behaviour for most rows.

**Status: CONFLICTING (timeout, protocol order) + MISSING (connection cap, dev defaults, 500).**

---

## 9. First-Run Excluded Apps (Requirement E)

Well documented and normative. §11.14.4 ("Excluded Apps default seed") and §1.6.50 state four rules: installed-only (never a phantom row), once per install (`SETTING_EXCLUDED_APPS_SEEDED`), never overwrite an existing name, and "no candidates, no flag". Package visibility via a scoped `<queries>` element is explained, including the accepted limitation (no-launcher services are invisible). The desired list is the shipped asset `assets/default_excluded_apps`, documented as closed at v1.0.56 with 12 confirmed package names extracted from device `5585fecc`. Tests: `ExcludedAppsSeederTest` (13) and `DefaultExcludedAppsTest` (16 after v1.0.56).

**Status: DOCUMENTED.** Implementation: IMPLEMENTED. The only weakness is that the requirement text points at "an authoritative list" without naming the asset in the same sentence; the asset is named elsewhere.

---

## 10. Server List Network Routing (Requirement F)

**This is the most consequential conflict.** The document's canonical routing description is the **fallback ladder**: `PHYSICAL → TUNNEL (when usable and bound) → EMERGENCY` (§1.6.38), later changed to VPN-first by v1.0.64 — which the spec does **not** contain. What the spec does contain is a policy in which a TUNNEL failure **falls back to PHYSICAL**. The new requirement is the opposite when the VPN is active: **VPN-only, no silent fallback, report a clear failure**. The spec also does not state "re-evaluate actual VPN state at request time" as a normative rule (it is implemented in `productionRouting()` and `routeLadder`, but v1.0.63 predates both).

Source-level facts (for the later implementation phase, not a documentation claim): per-request `Network.getSocketFactory()` binding (`boundToNetwork`/`boundToPhysicalNetwork`, `HttpFetcher.kt:157–181`), never `bindProcessToNetwork`; `VpnServerRepository.kt:340–357` selects the network; `fetchOnChosenPath` falls back to the opposite rung. **Source ≠ requirement.**

**Status: CONFLICTING + STALE.** Implementation: PARTIALLY IMPLEMENTED (VPN-first exists; VPN-only/no-fallback does not).

---

## 11. VPN-Routed Update Verification (Requirement G)

The spec contains per-block device-verification sections (e.g., §1.6.38 bullet 11, line 2916: physical-alone / tunnel-alone / ladder-end-to-end checks) and uses `NOT OBSERVABLE`/`NOT TESTABLE` honestly (25 and 7 occurrences respectively). It does **not** contain a standing plan with the required five-way classification (confirmed VPN-routed / confirmed non-VPN / likely but unproven / not observable / not testable), nor a rule that "update succeeded while VPN was connected" is insufficient proof. The v1.0.65 report does contain such evidence for one build, but reports are not authoritative.

**Status: PARTIALLY DOCUMENTED.** Implementation: PARTIALLY IMPLEMENTED (evidence exists per block; no standing requirement).

---

## 12. SoftEther UDP/TCP and Endpoint Integrity (Requirement H)

Strong. §10.3 (lines 1204–1206) and §1.6.45 (line 461, line 3118) state: support is declared by `SE_UDP_Supported`, never inferred from a port; `SE_UDP_Port = 0` with support true is **correct data** (the live page publishes `UDP: Supported` and no digits — 180 `UDP:` occurrences, zero `UDP: <digits>`); a port without declared support raises `UDP_PORT_WITHOUT_DECLARED_SUPPORT`; `SSTP_Hostname` may carry `:port`; OpenVPN config is authoritative over port columns. Protocol-specific timeout/error reporting and cleanup are covered by §6.3 and §1.6.45.

**Status: DOCUMENTED.** Implementation: IMPLEMENTED.

---

## 13. TUN EIO and Diagnostics (Requirement I)

EIO is documented as a **liveness** signal, not a quality signal: `TunnelHealth`/`TunnelLivenessWatchdog` watch RX advance and TUN EIO inside `EIO_WINDOW_MS` (§1.6.17, lines 2645–2656; line 5355 explicitly says "there is no post-connect quality rejection"). The spec is careful not to attribute EIO to a cause. However, it does **not** define a diagnostic record that correlates EIO with attempt identifier, server, protocol, endpoint/port, result and timeout. That correlation exists only as scattered log lines.

**Status: PARTIALLY DOCUMENTED.** Implementation: PARTIALLY IMPLEMENTED.

---

## 14. Tri-State Reachability and D1 Gate (Requirement J)

Documented in substance: §11.15.5 (line 5298) — the gate returns a **subsequence**; `UNKNOWN` keeps the server; an empty verdict removes everything only when every endpoint was definitively `UNREACHABLE` (line 5350 restates it). This covers Case A (one unreachable + one reachable ⇒ kept), Case B (all unreachable ⇒ removable) and Case C (UNKNOWN ⇒ kept). RTT availability is discussed in §6.6.

**Status: DOCUMENTED** (the A/B/C cases are implicit in one sentence rather than labelled; a future edit should label them). Implementation: IMPLEMENTED.

---

## 15. Auto Connect Dial Order (Requirement K)

Documented: §6.3 (`for each (server, protocol) in ordered candidates`), R3 (fast reachability probe, sort by probe result), R4 (sequential servers, user protocol priority, one attempt per server). The gate preserves quality order and removes only definitively doomed candidates. The document correctly keeps **server ranking** and **protocol priority** separate (line 3309: "history is a tie-breaker, never a ranker"; "the user's protocol priority is never reordered").

**Status: DOCUMENTED.** Implementation: IMPLEMENTED.

---

## 16. SSTP and L2TP (Requirement L)

SSTP is covered well: `SSTP_Hostname` may carry `:port`; `SSTP_Supported` is observable; `usableProtocols()` refuses SSTP when `sstpConnectPort == 0` while the 443 default is documented as intended (§1.6.45, line 3122). L2TP is only mentioned as a supported protocol and as **deprecated by Android** (line 3690–3692: removed in API 33), plus a manual-entry limitation (line 1606: `isL2TPSupport=0`). There is **no L2TP requirement** for Auto Connect eligibility, endpoint/port provenance, timeout handling, or reachability interaction, and no explicit `NOT TESTABLE` rule for L2TP on API 33.

**Status: PARTIALLY DOCUMENTED.** Implementation: PARTIALLY IMPLEMENTED.

---

## 17. Crash, Process Death, and Service Lifecycle (Requirement M)

The spec documents device runs that checked `FATAL EXCEPTION`, ANR and tombstones (line 3377) and preserves one ANR as `UNATTRIBUTED`/`NOT REPRODUCED` (line 6019). It does **not** define a read-only procedure covering `RemoteServiceException`, `DeadObjectException`, `ForegroundServiceDidNotStartInTimeException`, process death, VPN service death, or state divergence, and it does not state the rule that evidence must be collected **before** restarting.

**Status: PARTIALLY DOCUMENTED.** Implementation: PARTIALLY IMPLEMENTED.

---

## 18. Battery/Background Behavior (Requirement N)

No requirement. The only battery content is the **battery-optimisation prompt** behaviour (§1.6.32, line 440: `SETTING_BATTERY_OPT_ASKED`, once per install). There is no read-only Device Idle / power / restriction / background-VPN observation procedure, and no rule separating observed OS state from app causation.

**Status: MISSING.** Implementation: UNKNOWN.

---

## 19. Dataset Integrity and Persistence (Requirement O)

`DatasetIntegrity` is documented thoroughly (§11.11, §1.6.16): acceptance/rejection thresholds, `ACCEPT_NEW`/`MERGE_WITH_EXISTING`/`REJECT_DEGRADED`, degraded datasets never replacing healthier ones, snapshot promotion rules (§1.6.58, line 4841), and manual-record preservation. Missing: the **500 cap**, an explicit duplicate-IP identity statement (the model keys on `hostName`), and a before/after count-consistency requirement across Room/snapshot/UI after pruning.

**Status: PARTIALLY DOCUMENTED.** Implementation: PARTIALLY IMPLEMENTED.

---

## 20. Retention/Health-History Data Model (Requirement P)

Three different layers exist and the spec does not tabulate them:

| Field named in the requirement | Documented? | Source model | Persisted? |
|---|---|---|---|
| `firstSeenAt` | yes | `VPNGateItem` (line 50) | yes (Room column, default 0) |
| `lastSeenAt` | yes | `VPNGateItem` (line 52) | yes |
| `lastSuccessfulConnectionAt` | **no such name** | `server_history_summary.lastSuccessAt` | yes, but under a different name and in a different table |
| `lastFailureAt` | not by that name in the spec table | `server_history_summary.lastFailureAt` | yes (history table) |
| `successCount` | yes (history) | `server_history_summary.successCount` | yes (history table) |
| `failureCount` | yes (history) | `server_history_summary.failureCount` | yes (history table) |
| `consecutiveFailures` | yes | `server_history_summary.consecutiveFailures` | yes (history table) |
| `healthScore` | yes | `VPNGateItem` (line 54) | yes |
| additional | partially | `timeoutCount`, `qualityRejectionCount`, per-network counters, `lastRefreshSeenAt`, `firstListedAt` | yes (history table) |

The spec's "~19 columns" summary is approximate and does not enumerate the fields; it also conflates the server-list retention fields with the history summary fields. `lastSuccessfulConnectionAt` is **not** a field name in source.

**Status: PARTIALLY DOCUMENTED.** Implementation: PARTIALLY IMPLEMENTED.

---

## 21. Unsupported ISP-Blocking Claims (Requirement Q)

**No prohibition exists.** Worse, the spec itself uses ISP blocking as an established premise in two device-verification descriptions: line 2916 (*"the ladder end-to-end with the physical path blocked by the ISP"*) and line 3090 (*"with the physical path blocked by the ISP and a tunnel up"*). No comparative evidence is cited there. The document does use `UNKNOWN`/`NOT OBSERVABLE` elsewhere, so the intent is honest, but the normative rule is absent and the wording violates it.

**Status: MISSING (and self-violating).** Implementation: N/A.

---

## 22. Evidence and Result Classification (Requirement R)

The spec is unusually disciplined: nearly every claim carries `SOURCE CODE`, `TEST`, `PHYSICAL DEVICE`, `LIVE`, or `UNKNOWN`, and `NOT TESTABLE`/`NOT OBSERVABLE` are used 25/7 times. What is missing is a **defined, closed set** of result classes (PASS / PARTIAL / FAIL / NOT REPRODUCED / NOT TESTABLE / NOT OBSERVABLE / UNKNOWN / DEVICE_UNAVAILABLE) and an explicit rule that source and device evidence must not be merged into one conclusion.

**Status: DOCUMENTED in practice, not as a normative taxonomy.** Implementation: consistent.

---

## 23. Read-Only Physical-Test Safety (Requirement S)

The no-code-change / no-uninstall / no-clear-data / no-DB-edit rules appear in **block reports** (e.g., the v1.0.65 report and device-verify reports), not in the specification. The spec does record "no source change authorised" for audits, but does not define a read-only physical-verification contract.

**Status: PARTIALLY DOCUMENTED (outside the authoritative document).** Implementation: practised.

---

## 24. Timeout and Cancellation Semantics (Requirement T)

Documented: per-attempt timeout is distinct from the total run; `awaitArmedTunnel(protocol, timeoutMs)` is armed before connect; `finally: endAttempt(), clear armed state` (§6.3); `MISSING_CONFIG` is reported before any wait (§1.6.45). The v1.0.46 note (line 449) records the history: default 12 s and max 60 s were unchanged then; v1.0.52 (line 4196) lowered the default to 8 s and kept 8–60.

**Conflicts with the requirement:** default must be **12 s**, max **30 s**. Also missing: an explicit statement that late callbacks from an expired attempt are rejected (implemented via armed-state clearing, not stated as a rule), and per-protocol tests for the timeout **range** (only the clamp tests exist, pinned to 8–60).

**Status: CONFLICTING (values) + PARTIALLY DOCUMENTED (cleanup rules).** Implementation: PARTIALLY IMPLEMENTED.

---

## 25. Connection Origin and Reconnect Semantics (Requirement U)

The spec documents a **synthesised** manual state: `AutoModeViewModel.combineAutoModeState` renders `Connected (manual)` from `GlobalVpnTracker` (line 2571), and Auto-Reconnect is a setting (`SETTING_AUTO_RECONNECT_IN_AUTO_MODE`, default enabled, §11.13; the continuation is consumed once, line 4384). There is **no persisted three-value origin** (Manual / Auto Connect / Auto Reconnect), and no rule for reconstructing origin across process death without letting a stale origin override the tunnel state.

**Status: PARTIALLY DOCUMENTED.** Implementation: PARTIALLY IMPLEMENTED.

---

## 26. Testability Without a Physical Device

| Area | Without device |
|---|---|
| Routing/ladder, probe, merge, DatasetIntegrity, import parsing, history ordering | **unit-testable** (existing JVM suites) |
| VPN-bound packet path, tunnel lifecycle, Sharesheet/file-picker, TUN EIO | **requires physical device** |
| Migration 9→10 | **not real-SQLite-testable** with current JVM deps (recording proxy only) |
| L2TP on API 33 | **not testable** (OS removed the client) |
| ISP filtering | **not testable** without comparative evidence |

The spec contains a detailed history test plan (§11.15.11) but **no repository-wide matrix** mapping requirement → source → unit/integration/device test → evidence → limitation, which Requirement V asks for.

**Status: PARTIALLY DOCUMENTED.**

---

## 27. Conflicts, Stale Requirements, and Missing Decisions

**Stale (S):** the entire spec relative to `08c6abe`; §9 of the architecture doc still says retention is "NOT IMPLEMENTED" though phase 1 landed; §11.11's v1.0.22–v1.0.26 notes are historical but still read as current status.

**Conflicts (C):**
1. Timeout 8/60 (doc + source) vs 12/30 (requirement).
2. Protocol default TCP→UDP (doc + source) vs UDP→TCP→SSTP (requirement).
3. Fallback ladder (doc + source) vs VPN-only/no-fallback (requirement).
4. Architecture §9.1 default 1000 vs requirement 500.
5. Doc line 449 says "default 12 s … unchanged" while line 4196 says default 8 s — internally inconsistent unless read as history.

**Missing decisions (M):** 500-cap ranking weights and prune order; connection cap 4/8; developer-mode/custom-DNS defaults; ISP-claim prohibition; battery/background procedure; crash exception taxonomy; L2TP policy; connection-origin model; 500-cap interaction with manual records.

---

## 28. Recommended Documentation Corrections (not applied)

Priority order; each names the target document and section.

1. **P0 — Refresh the spec revision.** `PROJECT_SPECIFICATION.md` header: bump to v1.0.70, `Last updated` 2026-10-26, `HEAD` `08c6abe`, and add a v1.0.64–v1.0.69 change section covering VPN-first guards, cancellable fetch, exclusion/history, schema 9→10, history-aware exploration.
2. **P0 — Replace the settings table (§11.13 / §1.6.44).** Timeout 12 s default, 8–30 range, clamp rule; protocol order UDP→TCP→SSTP; max connections 4 default / 8 cap; Developer Mode OFF; Developer logging OFF; Custom DNS OFF; 500 servers. State UI, persistence, legacy-value behaviour per row.
3. **P0 — Rewrite the routing policy (§1.6.38 / §11.10).** When VPN is active: VPN-only, no silent fallback, explicit failure; when not active: default network. Define request-time re-evaluation, stale-network rejection, in-flight disconnect behaviour, cancellation. Remove the contradicting fallback text or scope it to "VPN not active".
4. **P0 — Add the 500-server retention requirement (§11.11 and arch §9).** Normative cap, qualification, ranking (quality → protocol breadth → success history → recency), deterministic prune, transactional behaviour, manual-record protection, UI/persisted count consistency. Resolve arch §9.7 Q1/Q2/Q4 first; do not invent weights in the doc without a decision.
5. **P1 — Add Requirement A's authoritative-state section.** Name the single source of truth, enumerate connecting/connected/disconnecting/disconnected/failed/unknown, define mismatch handling and the "evidence before Connected" rule.
6. **P1 — Add the connected-replacement section (Requirement B).** State whether make-before-break is supported; if not, say so and define the preserve/fail/status behaviour.
7. **P1 — Add the ISP-claim prohibition (Requirement Q).** Classify SERVER/NETWORK/DEVICE/APP/UNKNOWN; require comparative evidence; correct lines 2916 and 3090.
8. **P1 — Add crash/lifecycle (M) and battery/background (N) procedures.** Include the three exception names and the evidence-before-restart rule.
9. **P2 — Add the evidence taxonomy and read-only safety contract (R/S)** as normative sections, importing the labels already used.
10. **P2 — Add the connection-origin model (U), TUN EIO correlation schema (I), L2TP policy (L), and the requirement→test matrix (V).**

---

## 29. Final Recommendation

**DOCUMENTATION GAPS FOUND — PREPARE DOCUMENTATION UPDATE.**

The authoritative spec is one revision behind the code and four agreed decisions contradict it. A documentation update is required before any implementation phase that depends on those decisions (500-cap pruning, VPN-only routing, 12 s/30 s timeouts, UDP-first order).

### Final integrity confirmation

**This audit made no change of any kind.** The working tree already contained four modifications from the preceding authorised task — `HttpFetcher.kt`, `VpnServerRepository.kt`, `ServerListUpdaterTest.kt`, `AutoModeHistoryRecordingTest.kt` — and every one of them was left byte-for-byte untouched; HEAD is still `08c6abe`.

- Production source changes (by this audit): **NONE**
- Test changes (by this audit): **NONE**
- Development-document changes: **NONE** (this audit report is a new artifact, not an edit to any existing document)
- Gradle changes: **NONE**
- Dependency changes: **NONE**
- Room/schema changes: **NONE**
- Migration changes: **NONE**
- R8 changes: **NONE**
- Configuration changes: **NONE**
- User-data changes: **NONE**
- Commit: **NONE** · Tag: **NONE** · Push: **NONE** · Release: **NONE**
