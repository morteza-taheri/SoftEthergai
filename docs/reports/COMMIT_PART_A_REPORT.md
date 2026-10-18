# COMMIT_PART_A_REPORT — PART A tunnel/UI state-owner alignment (Axis A)

**Document version:** 1.0.0
**Date:** 2026-10-15
**Commit:** `067d4a36b282ef3e1f0a8add7e9a1a0e304edbb6`
**Subject:** `fix(automode): reduce tunnel/UI state-owner mismatches (Axis A)`
**Parent:** `71edd67935371450b82e5eb162a0d8bf05da13d6` (v1.0.16)
**Scope:** PART A only. PARTs B, C and D were landed in earlier commits and are re-confirmed, not re-landed, here.
**Phase type:** device-free. No `adb` invocation was made; no device was attached.

---

## 1. Executive verdict

PART A is committed as a single revision containing the four A-axis changes (A-2, A-3, A-4, A-5)
plus 24 new JVM tests. The commit message body records the verification status as **PARTIAL**
and states explicitly that the one observed ANR is **UNATTRIBUTED and NOT REPRODUCED**.

This report exists to put that caveat on the record in a durable, reviewable place, and to
separate three things that a bare commit message cannot:

1. what was actually changed (verified, SOURCE CODE);
2. what was observed but not attributable to those changes (one ANR, PHYSICAL DEVICE + trace
   evidence, negative assurance);
3. what remains UNKNOWN (blocked frame, root cause).

| Area | Verdict |
|------|---------|
| JVM unit tests (PART A) | **PASS** — 671 completed / 0 failed / 0 skipped / 1 ignored, baseline 647, +24 new |
| Native module tests | **PASS** — `:SoftEtherClient:test` 173 / 0 / 0 / 0 |
| Build | **PASS** — `:app:assembleProDebug` SUCCESSFUL |
| APK reproducibility | **PASS** — SHA-256 `713b1014e3785f35bbc69e74e05726ce10fb3e87c9a57ad66072734091186c97` |
| Device verification (PART A) | **PARTIAL** — one ANR observed, **NOT REPRODUCED**, unattributed |
| PARTs B / C / D re-verification | **ALREADY LANDED** in prior commits; not re-executed here |

---

## 2. Pre-conditions

All checked immediately before staging. All PASS.

| # | Check | Result |
|---|-------|--------|
| 2.1 | `HEAD` == `origin/main` | PASS — both `71edd67935371450b82e5eb162a0d8bf05da13d6` |
| 2.2 | Branch is `main` | PASS |
| 2.3 | Tracked modifications are exactly the PART A set | PASS — 4 modified tracked files, listed in §3 |
| 2.4 | No tracked file outside the PART A set is modified | PASS |
| 2.5 | New untracked test file is inside `app/src/test/.../state/` | PASS — `VpnStackLedgerTest.kt` |
| 2.6 | Spec header `Document version` is 1.0.16 | PASS |
| 2.7 | Device-free phase acknowledged | PASS — device 5585fecc DETACHED, no `adb` call made |

Untracked scratch artefacts from Phase 12 (`_p12_backup/`, `_p12_*`, `_f28al*`, earlier reports)
were present in the working tree and were **deliberately not staged**. Staging was by exact path,
never `git add -A` / `git add .`.

---

## 3. PART A changes

### 3.1 File inventory

| File | Δ lines | Evidence label |
|------|---------|----------------|
| `app/src/main/java/vn/unlimit/vpngate/state/GlobalVpnTracker.kt` | +286 / −20 | SOURCE CODE |
| `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt` | +138 / −47 (net in 138-line diffstat) | SOURCE CODE |
| `app/src/test/java/vn/unlimit/vpngate/state/GlobalVpnTrackerTest.kt` | +116 | TEST |
| `app/src/test/java/vn/unlimit/vpngate/automode/AutoModeControllerTest.kt` | +269 | TEST |
| `app/src/test/java/vn/unlimit/vpngate/state/VpnStackLedgerTest.kt` | +341 (new) | TEST |
| **Total** | **1083 insertions, 67 deletions across 5 files** | |

### 3.2 A-2 — onLost re-check budget

`GlobalVpnTracker.onLost` delayed its re-check by a hard-coded `500` ms. It is now
`VPN_LOST_RECHECK_MS`, a new file-scope constant:

```kotlin
internal const val VPN_LOST_RECHECK_MS = 50L
```

The budget was **reduced, not removed**. The re-check still exists to absorb network handover:
`onLost` is delivered *before* `onAvailable`, so a handover that arrives within the window will
be re-observed by the re-check rather than being published as a disconnect. Removing it outright
would re-introduce the handover flap; 50 ms keeps the absorption while cutting the window in
which a stale `onLost` can publish a false disconnect.

### 3.3 A-3 — protocol-name egates replaced by a stateless fold

`GlobalVpnTracker` previously published or suppressed state through four protocol-name
"egates" — checks of the form `_vpnState.value.protocol == <literal>`. Two of them silently
**dropped** events (a disconnect for the non-current protocol vanished), and two could publish a
teardown for protocol X while protocol Y was still the live tunnel, i.e. **clobber Y**.

These are replaced by a pure, stateless fold in a new object:

```kotlin
object VpnStackLedger {
    fun reconcile(live: CopyOnWriteArrayList<VpnStackReport>, current: VpnState, event: VpnStackEvent): VpnStackReconciliation
}
```

Rules:

- CONNECTED adds/refreshes the protocol's stack entry and takes the slot.
- CONNECTING / DISCONNECTING pass through.
- DISCONNECTED / ERROR removes X from the live set, then:
  - if a survivor Y remains, Y is republished as CONNECTED **from Y's own identity** — Y's
    protocol, session id and timestamps, not X's. Y is never clobbered by X's teardown.
  - else if `current.protocol == X`, X's teardown is published.
  - else nothing is published (X was a stale, non-current protocol whose loss the UI must not see).
- The `sstpListener:280` egate is **deliberately left in place**: it is provably equivalent to the
  ledger's outcome and is pinned by a test, so removing it would add risk with no behavioural
  gain.

`updateState(...)` now folds through the ledger and applies `verdict.live` plus
`verdict.publish?.let { _vpnState.value = it }`.

### 3.4 A-4 — single armed-loss funnel

`AutoModeController.startConnectedWatcher` had three separate armed-tunnel loss paths, each with
its own copy of the teardown sequence (log, cancel watcher, stop liveness probe, clear
`onTunnelLost`, dispatch `onUnexpectedDisconnect`). All three now call:

```kotlin
private fun reportArmedTunnelLoss(reason: String, dropped: AutoModeCandidate?)
```

The **detection latencies are deliberately NOT unified**. Each path's observed latency is part of
its measured behaviour, and collapsing them would be a separate, riskier change. Only the
reporting side is funnelled.

### 3.5 A-5 — Disconnected published after the decision

`onUnexpectedDisconnect` previously published `AutoModeState.Disconnected` as its **first
statement**, before evaluating the OQ-7 / OQ-6 / OQ-1 decision. A UI observer could therefore
see `Disconnected` while the controller was still reasoning, and the stale observation could race
the decision.

The publish is moved out of the prologue and into the decision: the OQ-7 stand-down branch, the
OQ-6 branch, and once before the OQ-3 delay. The OQ-1 limit publishes `Error(ERROR_RECONNECT_LIMIT)`
only. `onUnexpectedDisconnectForTests`' KDoc was updated to match.

### 3.6 Tests

- **`VpnStackLedgerTest.kt` (new, 13 tests)** — pure behavioural coverage of `reconcile`: survivor
  republish from Y's own identity, the clobber-Y case now closed, stale-protocol silence, and the
  CONNECTING/DISCONNECTING pass-throughs.
- **`GlobalVpnTrackerTest.kt` (+5)** — A-2 bounds (`VPN_LOST_RECHECK_MS < 500L`, `>= 10L`) and
  the named-constant source guard; A-3 guards: exactly one remaining
  `if (_vpnState.value.protocol ==` guard, the SoftEther/OpenVPN egates gone, `updateState`
  delegates to the ledger.
- **`AutoModeControllerTest.kt` (+6)** — a `trace: MutableList<String>?` recorder on `FakeAdapter`
  and `isTunnelBusy` / `reconnectInitialDelayMs` defaulted params on the `controller(...)` factory;
  3 behavioural ordered-trace A-5 tests and 3 A-4 source-shape tests.

Three of these source-shape tests failed on the first full run and all three were **assertion bugs
in the tests, not product bugs**: the `reportArmedTunnelLoss(` regex counted the definition as a
4th call site (fixed to `3 + 1`); the egate regex also matched the `updateState` default-argument
expression and a KDoc quotation (fixed to match only `if (_vpnState.value.protocol ==` on
non-comment lines); and the documentation check read `substringAfter(...)` past EOF (fixed to
`substringBefore(...).takeLast(900)`). Each fix is recorded because the tests' value is as
guards, and a guard that over-counts is a guard that will be ignored.

---

## 4. ANR finding

### 4.1 What was observed

| Field | Value | Evidence label |
|-------|-------|----------------|
| Timestamp | 16:06:01 (local) | PHYSICAL DEVICE |
| Process death | pid 23115 → 25247 | PHYSICAL DEVICE |
| Main-thread degradation | from 15:58:56; Skipped 35 → 1311 → 1821 frames | PHYSICAL DEVICE (logcat) |
| Tunnel health during ANR | stayed healthy | PHYSICAL DEVICE |
| Blocked frame | **UNKNOWN** — `/data/anr` unreadable without root | UNKNOWN |
| Attribution to PART A | **none established** | INFERENCE from traces |
| Reproduction | **NOT REPRODUCED** | PHYSICAL DEVICE |

### 4.2 Negative assurance — the Axis A paths did not run

This is the load-bearing part of the attribution argument, and it is a *negative* claim, so it is
stated with its evidence rather than as a bare assertion:

| Claim | Basis | Label |
|-------|-------|-------|
| A-2's `onLost` did not execute | `GlobalVpnTracker` logged only "network available"; there is no `onLost` entry for the window | PHYSICAL DEVICE (logcat) |
| A-3's `updateState` fold did not execute | no service state-change event was observed in the window | PHYSICAL DEVICE (logcat) |
| A-4 / A-5 did not execute | no `[AUTO]` lines were emitted in the window | PHYSICAL DEVICE (logcat) |

With none of the three A paths in flight, the changed code cannot be shown to be the blocker.

### 4.3 Controls

1. **Baseline control.** The pre-change build (`4541e7f8…`) was run through the same route. Clean.
2. **Re-test of the same PART A APK.** Clean.
3. One incidental earlier mis-attribution of mine was self-corrected: I first read a 41 s tap delay
   as a disconnect regression, then traced it to tool/sandbox latency (a "sandbox-center cmd
   decisionRecord" error) that made the retest tap land *after* the tap-driven disconnect had
   already completed. The corrected reading shows the PART A build behaves identically to baseline.
   It is recorded here because the corrected conclusion is only as strong as the record of the error.

### 4.4 Residual risk

Most probable remaining explanation, labelled **INFERENCE**: a pre-existing duplicate-`CONNECT`
race combined with UI log-panel degradation. This matches a tunnel that stayed healthy while the
main thread fell behind, and matches the degradation curve (Skipped frames climbing for ~7 minutes
before the ANR) better than a single blocking call would.

---

## 5. UNKNOWNs

| # | Unknown | Why it is unknown | How it could be closed |
|---|---------|-------------------|------------------------|
| 5.1 | The blocked frame | `/data/anr` is not readable without root | Read `/data/anr` on a rooted device or a userdebug build |
| 5.2 | ANR root cause | No trace, no reproduction, no Axis A execution | Reproduce with `Debug.` systrace or a `StrictMode` dump attached |
| 5.3 | Whether the duplicate-`CONNECT` race exists at all | Inferred from the shape of the degradation, not observed | Instrument the CONNECT path to log conn/sec and dedupe attempts |

None of these were closed in this phase, and none of them was attempted by forbidden means (no
root, no flash, no re-image).

---

## 6. PARTs B, C and D — already landed

These are **not** re-landed and **not** re-verified in this phase; they are re-confirmed so that
this report's scope boundary is unambiguous.

| Part | Verification commit | Status |
|------|--------------------|--------|
| PART D | `c572050` | verified live in Phase 12 (`:app:assembleProRelease` exits 1 with the `GradleException` at `app/build.gradle:109` — expected, keystore-gated) |
| PART C | `607c651` | landed |
| PART B | `a9e16fd` | landed |
| **PART A** | **`067d4a3` (this commit)** | **landed here** |

---

## 7. No-change confirmation

Recorded as a positive assertion, because "nothing else changed" is the easiest claim to lose:

- No file outside the five in §3.1 is modified by this commit.
- No build file, manifest, native source, or resource is touched.
- No `PART` B / C / D file is touched.
- §22 of the specification is byte-unchanged in this commit (it is untouched by commit 1).
- No tag was created, pushed, or deleted.
- No `--force`, no `reset`, no `clean`, no `stash`.
- No device command was issued; no build or test was re-run in this phase (the figures in §1 are
  carried forward from Phase 12 as **PRIOR REPORT** evidence).
- C5 was not executed.

---

## 8. Recommendation

1. **Advance the spec to v1.0.17** recording PART A and the ANR finding as an explicit open item,
   rather than as a silent caveat. This is the next step and is the reason this report exists.
2. **Carry the ANR as a tracked open item**, not as a regression of PART A. The evidence supports
   "pre-existing, unattributed" and does not support "caused by A-2..A-5".
3. **Close UNKNOWNs 5.1–5.3 only with an appropriate device** (rooted or userdebug). Do not attempt
   to close them by inference — that is how 4.3's mis-attribution happened in the first instance.
4. **Re-verify on device before the next feature revision**, so that the PARTIAL status is either
   upgraded to PASS by a clean multi-route run or escalated with a trace attached.

---

*End of report.*
