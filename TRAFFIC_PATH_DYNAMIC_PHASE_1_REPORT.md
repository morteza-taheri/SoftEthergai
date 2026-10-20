# TRAFFIC-PATH-DYNAMIC — Phase 1 Report (spec v1.0.41)

**Repository:** `C:\Dev\SoftEthergai` · **Branch:** `main`
**Baseline (pre-phase):** `02765a6f2ec0273124e5286dce02c09fd91129dd` — the pre-phase HEAD, identical to
`origin/main` when the phase started.
**Phase:** TRAFFIC-PATH-DYNAMIC-PHASE-1-v1.0.41 (Option B, operator-authorised) · **Date:** 2026-10-20
**Scope:** the collector's VPN rung now requires a *usable* tunnel, not merely a CONNECTED one.
**Labels used throughout:** `SOURCE CODE` / `TEST` / `PHYSICAL DEVICE` / `INFERENCE` / `UNKNOWN` /
`PRIOR REPORT`.

---

## §1 Verdict

The traffic-path decision now consumes tunnel health. One behavioural change, one new signal, two
comment corrections, and **two of the brief's three fixes were already implemented at the baseline**.

| Item | Brief's premise | State at `02765a6` | Action taken | Evidence |
|------|-----------------|--------------------|--------------|----------|
| **FIX-1** — wire `noteRxAdvance()` from the RX path | "NOTHING calls it, so `isTunnelUsable()` can only ever return false" | **Already done** at `ConnectionController.kt:1157` (one call per received batch, R4b), covered by `RxAdvanceWiringTest` | **Not changed** — reported, not re-done | SOURCE CODE + TEST |
| **FIX-2** — a health signal for routing | `productionRouting()` feeds the weaker `isConnected` | True | `isTunnelUsableForRouting()` + `routingUsableFromSignals()` added; `productionRouting()` feeds it | SOURCE CODE + TEST |
| **FIX-3** — `resolvePrimary()` must gate on `tunnelUsable` | `resolvePrimary()` reads `preferVpn` alone | True | Now requires `preferVpn && tunnelUsable` (operator authorisation A1) | SOURCE CODE + TEST |
| **Fallback** — `fetchOnChosenPath` | "verify it exists" | **Already present** (`:390–395`) | Not changed | SOURCE CODE |

`:app` **846 / 0 / 0 / 1** (was 837 / 0 / 0 / 1 — +9, exactly the new tests); `:SoftEtherClient`
**173 / 0 / 0 / 0** (unchanged); `:app:assembleProDebug` **BUILD SUCCESSFUL** under `CI=true`; APK
SHA-256 `1340fed9771828d3b9655c4b05c0d2778ed737e9bd18b6363b16c44d2902159f`; version **unchanged** at
`versionCode 144` / `versionName 2.4.11-dev`. `PROJECT_SPECIFICATION.md` v1.0.40 → v1.0.41; §22
byte-identical (99 lines / 6504 bytes / `adf12858…020e`); no tag.

---

## §2 Pre-conditions

| Check | Value | Verdict | Label |
|-------|-------|---------|-------|
| `git rev-parse HEAD` | `02765a6f2ec0273124e5286dce02c09fd91129dd` | OK | SOURCE CODE |
| `git rev-parse origin/main` | same | OK (fast-forward possible) | SOURCE CODE |
| `git branch --show-current` | `main` | OK | SOURCE CODE |
| tracked modifications | none (`--porcelain` showed only `??`) | OK | SOURCE CODE |
| spec version | `1.0.40` | OK | SOURCE CODE |
| tag count | 5 | OK | SOURCE CODE |

---

## §3 The prior-stop findings, accepted as the starting state

These were established in the previous phase's STEP 2 (read-only) and re-confirmed here before any
edit. All five still held at `02765a6`.

- **F-1** — `TunnelHealth` is **not** a separate file. `object TunnelHealth {` is at
  `SoftEtherClient/src/main/java/vn/unlimit/softether/SoftEtherVpnService.kt:1740`.
- **F-2** — `ConnectionController.kt:1157` already calls `TunnelHealth.noteRxAdvance()`, once per
  successfully-received batch, with KDoc explaining why the call sits at the batch boundary.
- **F-3** — `RxAdvanceWiringTest.kt` already exists with 7 tests. Untouched.
- **F-4** — `fetchOnChosenPath()` already implements the primary/fallback structure
  (`VpnServerRepository.kt:390–395`). Untouched.
- **F-5** — `resolvePrimary()` read only `input.preferVpn`, and `CollectorRouting.choose()` has no
  production caller, so `tunnelUsable` had **no production consumer** at the baseline. This is the
  finding that turned the brief from three fixes into one.

`2f` check: no existing test asserted `preferVpn = true → path=vpn` regardless of `tunnelUsable`. The
only `(preferVpn = true, tunnelUsable = false)` input in the tree is `HttpFetcherR4bTest.kt:41`, which
tests `CollectorRouting.choose()` — untouched and still green. Evidence: SOURCE CODE.

---

## §4 FIX-1 — not changed (already implemented)

The brief's premise does not hold at the baseline, so no edit was made to the RX path and no second
`noteRxAdvance()` call was added. The producer is:

```
ConnectionController.kt:1138   val total = client.receiveBatch(receiveBuffer, batchLengths)
ConnectionController.kt:1140   total > 0 -> {
ConnectionController.kt:1157       TunnelHealth.noteRxAdvance()
```

One call per batch, not per packet, with the rationale in the KDoc at `:1141–1156`. Evidence: SOURCE
CODE + TEST (`RxAdvanceWiringTest`, 7 tests, still green).

---

## §5 FIX-2 — `isTunnelUsableForRouting()` and the five design decisions

Added at `GlobalVpnTracker.kt:459–467`, with its pure core `routingUsableFromSignals(...)` at file
scope (`:797`).

**Decision 1 — a wrapper, not a change to `isTunnelUsable()`.** The R4a contract
(`isConnected && RX within 10 s && no EIO within 30 s`, fail-closed) is preserved verbatim; the new
function is additive. Evidence: SOURCE CODE.

**Decision 2 — the grace period is evidence-based, not time-based.** The rule is: connected, OS-bound,
and *either* the strict check passes *or* no RX advance has ever been observed and no TUN EIO sits
inside `EIO_WINDOW_MS`. A freshly-connected tunnel therefore routes to VPN immediately instead of
being demoted to OPEN for the first seconds of its life. Evidence: SOURCE CODE.

**Decision 3 — the grace period cannot mask a dead tunnel.** The grace branch requires
`rxCounter == 0`. Once any frame has been received the branch is unreachable, so the only way back to
`true` is a fresh RX advance inside `RX_WINDOW_MS`. A tunnel that came up, carried traffic and then
died returns `false`. This is pinned by T-NEW-34. Evidence: SOURCE CODE + TEST.

**Decision 4 — `connectedAtMs` is added but not consumed.** The operator authorised it (A2) as the
correct anchor for a future *time-bounded* grace period, and as a diagnostic for a device log ("the
tunnel had been up N ms when routing declined it"). It is stamped only on the **transition** into
CONNECTED (`:617–625`), never on a republish of an already-CONNECTED state, and reset on
DISCONNECTED/ERROR; getter at `:321`. The current decision does not read it, and the report says so
rather than implying otherwise. Evidence: SOURCE CODE.

**Decision 5 — `<=` on the EIO boundary.** `TunnelHealth.isUsable` uses `<`; the wrapper uses `<=`, so
at the exact boundary the wrapper is the stricter of the two. Deliberate: the grace branch is the one
place that returns `true` without fresh evidence, so it must be the conservative side. Pinned by
T-NEW-35. Evidence: SOURCE CODE + TEST.

**Decision 6 (not in the brief, recorded for completeness) — the pure core is file-scope.** A member of
`GlobalVpnTracker` cannot be exercised from a JVM test because the object's initializer needs
`Handler(Looper.getMainLooper())` (this module has JUnit 4 only — no Robolectric). File-scope costs
nothing and keeps the decision testable, following the `VPN_LOST_RECHECK_MS` precedent in the same
file. Evidence: SOURCE CODE.

---

## §6 FIX-3 — `resolvePrimary()` gating and the KDoc contract rewrite (A1)

**Before** (`VpnServerRepository.kt:133–134` at `02765a6`):

```kotlin
internal fun resolvePrimary(input: CollectorRoutingInput): CollectorPath =
    if (input.preferVpn) CollectorPath.VPN else CollectorPath.OPEN
```

**After** (`:149–151`):

```kotlin
internal fun resolvePrimary(input: CollectorRoutingInput): CollectorPath =
    if (input.preferVpn && input.tunnelUsable) CollectorPath.VPN
    else CollectorPath.OPEN
```

`productionRouting()` (`:119–132`) now supplies `GlobalVpnTracker.isTunnelUsableForRouting()` and
keeps `preferVpn` as the user's intent only — the v1.0.27 FIX-3a property (no `preferVpn ||=
tunnelUp`) is preserved and pinned by T-NEW-36.

The KDoc at `:91–118` is rewritten: `preferVpn` is the opt-in, `tunnelUsable` is the health gate, the
VPN rung requires both, and the rung is still never forced by tunnel state alone. The old sentence
that described `preferVpn` as the sole decision is replaced rather than left to contradict the code.
Evidence: SOURCE CODE.

**Behaviour matrix.**

| `preferVpn` | `tunnelUsable` | Primary rung | Changed? |
|---|---|---|---|
| `false` | `true` | OPEN | No (default user) |
| `false` | `false` | OPEN | No |
| `true` | `true` | VPN | No |
| `true` | `false` | **OPEN** | **Yes** — was VPN, then fell back after a timeout |

---

## §7 The two stale comments corrected (A3)

| File | Lines | Was | Now |
|---|---|---|---|
| `SoftEtherClient/.../SoftEtherVpnService.kt` | `1735–1740` | "`noteRxAdvance` is **not** called by any data-forwarding path … deferred to R4b" | names `ConnectionController.kt:1157` and §1.6.34 |
| `app/.../state/GlobalVpnTracker.kt` | `357–363` | "the counters are NOT yet wired … Nothing calls `[noteRxAdvance]`" | states that R4b wired it and that §1.6.34 consumes it |

Comment-only edits. No code in either file changed; the `TunnelHealth` counters and `isUsable` are
untouched. Evidence: SOURCE CODE.

---

## §8 §12.5.1 frozen row 10 — annotated, not edited

§12.5.1 is FROZEN. Its row 10 still says *"nothing calls `noteRxAdvance()` from the data-forwarding
path, so `isTunnelUsable()` can only ever report `false`"*, which has been false since R4b.
`PROJECT_SPECIFICATION.md` §1.6.34 bullet 1 records the discrepancy and states that the frozen row is
superseded in substance. `git diff` on the spec contains **no hunk inside §12.5.1**. Evidence: SOURCE
CODE.

One residual inconsistency is **out of scope and unchanged**: `HttpFetcher.kt:308`'s KDoc still names
`GlobalVpnTracker.isTunnelUsable()` as the input to `CollectorRouting.choose()`. That file is not in
the authorized list for this phase, so it was not touched. It is a doc nit, not a behaviour claim, and
`choose()` remains unused in production. Evidence: SOURCE CODE.

---

## §9 Tests added

No existing test was modified or deleted.

**`app/src/test/java/vn/unlimit/vpngate/state/GlobalVpnTrackerRoutingTest.kt` (NEW, 6 tests,
T-NEW-30…35)** — drives `routingUsableFromSignals` directly, no Android runtime:

| Test | Case | Expected |
|---|---|---|
| T-NEW-30 | disconnected (even with `isUsable = true`) | `false` |
| T-NEW-31 | connected, no OS-bound network | `false` |
| T-NEW-32 | passing traffic | `true` |
| T-NEW-33 | freshly connected, no RX ever, no EIO | `true` (grace) |
| T-NEW-34 | traffic observed then stopped | `false` |
| T-NEW-35 | EIO inside / exactly on / outside the window | `false` / `false` / `true` |

T-NEW-35 covers two of the brief's cases (inside and outside the EIO window) as the two sides of one
boundary, which is why 7 listed cases map to 6 tests and the phase total is 9, not 10.

**`app/src/test/java/vn/unlimit/vpngate/repository/VpnServerRepositoryRoutingDynamicTest.kt` (NEW, 3
tests, T-NEW-36…38)**:

| Test | Kind | Asserts |
|---|---|---|
| T-NEW-36 | source-shape | `productionRouting()` contains `isTunnelUsableForRouting()`, passes `tunnelUsable = usable`, contains no `isConnected`, no `\|\|` |
| T-NEW-37 | behavioural | all five `resolvePrimary` permutations; `(true,false,*)` → OPEN |
| T-NEW-38 | source-shape | `fetchOnChosenPath`'s `canFallback` rung intact; `CollectorRouting.choose` still the three-way AND |

**Confirmation that the pre-existing suites pass unchanged:** `VpnServerRepositoryR4bTest` (10, 0
failures), `VpnServerRepositoryRoutingTest` (8, 0 failures), `GlobalVpnTrackerTest` (17, 0 failures),
`RxAdvanceWiringTest` (7, 0 failures), `HttpFetcherR4bTest` (11, 0 failures). Evidence: TEST.

---

## §10 JVM results

| Suite | Before | After | Delta |
|---|---|---|---|
| `:app:testProDebugUnitTest` | 837 / 0 / 0 / 1 | **846 / 0 / 0 / 1** | +9 (6 + 3) |
| `:SoftEtherClient:test` | 173 / 0 / 0 / 0 | **173 / 0 / 0 / 0** | 0 |

The single skip is unchanged (`HtmlParserOracleTest`). Both tasks ran with `--rerun-tasks
--no-build-cache` and `CI=true` (a real environment variable — `-PCI=true` does not enable the `debug`
variant, `app/build.gradle:122`). Evidence: TEST.

---

## §11 Build and APK

`:app:assembleProDebug --rerun-tasks --no-build-cache` under `CI=true` → **BUILD SUCCESSFUL** (6m 28s,
114 tasks). APK `app/build/outputs/apk/pro/debug/app-pro-debug.apk`, 63,763,162 bytes.

**APK SHA-256:** `1340fed9771828d3b9655c4b05c0d2778ed737e9bd18b6363b16c44d2902159f`

`versionCode 144` / `versionName 2.4.11` (+ `-dev` for the debug variant) — unchanged; no build or
manifest file was touched. Evidence: TEST.

Free space on `C:` before the build: 7,913,689,088 bytes (7.4 GB). Evidence: TEST.

---

## §12 Diff scope

| File | Status |
|---|---|
| `app/src/main/java/vn/unlimit/vpngate/state/GlobalVpnTracker.kt` | modified (authorized) |
| `app/src/main/java/vn/unlimit/vpngate/repository/VpnServerRepository.kt` | modified (authorized) |
| `SoftEtherClient/src/main/java/vn/unlimit/softether/SoftEtherVpnService.kt` | modified — comment only (authorized) |
| `app/src/test/java/vn/unlimit/vpngate/state/GlobalVpnTrackerRoutingTest.kt` | new (authorized) |
| `app/src/test/java/vn/unlimit/vpngate/repository/VpnServerRepositoryRoutingDynamicTest.kt` | new (authorized) |
| `PROJECT_SPECIFICATION.md` | modified (authorized) |
| `TRAFFIC_PATH_DYNAMIC_PHASE_1_REPORT.md` | new (authorized) |

Nothing else. `ConnectionController.kt`, `TunnelHealth` (the object), `TunTerminal.kt`,
`HttpFetcher.kt`, `fetchOnChosenPath`, the four `AutoMode*` files, `AndroidConnectionAdapter.kt`,
`RxAdvanceWiringTest.kt`, `VpnServerRepositoryR4bTest.kt`, `VpnServerRepositoryRoutingTest.kt`, the
build files, the manifests and the schemas are all untouched. Evidence: SOURCE CODE (`git status
--short`, `git diff --stat`).

---

## §13 §22 byte-identity

```
awk '/^## 22\./{flag=1} flag{print}' PROJECT_SPECIFICATION.md
→ 99 lines / 6504 bytes / adf12858b72ed13742252c512a8728e9141e415cc55d09c6409af4e8e622020e
```

Identical to the v1.0.40 value. Evidence: TEST.

---

## §14 Negative assurance

- No device was touched: no `adb`, no install, no logcat, no screenshot, no `run-as`. The phase was
  device-free by instruction. Evidence: absence of any device command in the session.
- No build or test ran without `CI=true` in the environment.
- `git add -A` / `git add .` were not used; files were staged by explicit path.
- No `--force`, `reset`, `clean`, `stash`, `checkout` or `restore` was used.
- No tag operation of any kind was performed.
- No test was deleted or modified.
- No second `noteRxAdvance()` call was added, and `fetchOnChosenPath` was not edited.
- `CollectorRouting.choose()` semantics are unchanged (pinned by T-NEW-38).
- `isTunnelUsable()` semantics are unchanged; the new wrapper is additive.
- §12.5.1 was not modified; §12.5.2 received one appended status note and no cell edit.
- `D:\PMC\Documents\Android\SoftEthergai` was not touched.
- C5 was not executed.

---

## §15 What remains

**Phase 2 — per-source path memory.** §11.8 asks the system to retain useful source/path outcome
information. Today the transport is chosen once per run (`refresh()` → `routing()` →
`resolvePrimary()`) and applied to every source, so a source that only answers on OPEN is retried on
VPN every run. That is unchanged here and remains the next traffic-path item. Evidence: SOURCE CODE.

**Device verification — PENDING, UNKNOWN.** The check that would close this phase:

1. enable `vpn_always_prefer_vpn`;
2. bring a tunnel up and confirm the collector logs `[collector] path=vpn`;
3. break the tunnel without tearing it down (Wi-Fi toggle) and wait past `RX_WINDOW_MS`;
4. refresh: the **first** `[fetch] path=` line must be `path=open`, with no preceding VPN attempt and
   no `falling back to vpn`/`failed (no result)` line attributable to a doomed VPN try.

No device ran in this phase, so the routing change is SOURCE CODE + TEST only. Evidence: UNKNOWN.

**Commit and gate.** The phase's two commits are made after this file is written (source + tests, then
spec + this report), and the post-push standalone build gate runs against the pushed tree. Neither the
commit hashes nor the gate result can appear in this file: it is committed before both exist. They are
reported in the phase's chat report instead.
