# IMPLEMENT_AND_VERIFY_P1_REMAINING — Report

| Field | Value |
|---|---|
| Phase | `IMPLEMENT-AND-VERIFY-P1-REMAINING` |
| Base commit | `71edd67935371450b82e5eb162a0d8bf05da13d6` (`71edd67`) |
| HEAD at report time | `71edd67935371450b82e5eb162a0d8bf05da13d6` (**unchanged — no commits made**) |
| origin/main at report time | `71edd67935371450b82e5eb162a0d8bf05da13d6` (**unchanged — nothing pushed**) |
| Device | `5585fecc` (Xiaomi 2201117TG, Android 13) — CONNECTED |
| **Overall status** | **PARTIAL** |
| Date | 2026-10-15 |

## Evidence labels

`SOURCE CODE` · `TEST` · `PHYSICAL DEVICE` · `INFERENCE` · `UNKNOWN` ·
`PRIOR REPORT` · `NOT REPRODUCED` · `AUTOMATED-UI`

---

## 1. Summary and per-PART verdict

| PART | Target | Verdict | Basis |
|---|---|---|---|
| **A** | Axis A — tunnel state / UI sync (A-2…A-5) | **IMPLEMENTED** (code + tests green) | `SOURCE CODE`, `TEST` |
| **B** | Axis B (R4c) — `isTunnelUsable()` in `productionRouting()` | **NO-OP — already landed** by `a9e16fd` (R4b) | `SOURCE CODE` |
| **C** | Axis C — TUN EIO branch / bounded read loop / dead `MAX_PACKET_SIZE` | **NO-OP — already landed** by `607c651` (R4a/R5/R6) | `SOURCE CODE` |
| **D** | RISK-A — fail-closed release signing | **NO-OP — already landed** by `c572050`; guard **verified firing** | `SOURCE CODE`, `TEST` (build probe) |

**Why PARTIAL, not COMPLETE:** the mandated device smoke test (5 AutoConnect cycles
+ 2 stress cycles) could not be finished. One **ANR** was observed on the first
device cycle, and one **process death** followed it. Both are named STOP
conditions in the phase brief. Device work was stopped, the evidence was
forensically analysed, and a **baseline control experiment** plus a
**reproducibility re-test** were run to establish attribution (§9).

The repo was left with the working tree holding the PART A implementation but
**uncommitted**, so the invariant `HEAD == origin/main` is preserved and no
unverified code was published. Nothing was committed and nothing was pushed.

---

## 2. Pre-conditions (STEP 1)

| Check | Result | Label |
|---|---|---|
| `HEAD` | `71edd67935371450b82e5eb162a0d8bf05da13d6` | `TEST` |
| `origin/main` | `71edd67935371450b82e5eb162a0d8bf05da13d6` | `TEST` |
| Branch | `main` | `TEST` |
| Tracked tree | clean at start | `TEST` |
| Device | `5585fecc` reported as `device` | `PHYSICAL DEVICE` |
| `firstInstallTime` **before** install | `2026-09-28 16:05:05` | `PHYSICAL DEVICE` |

All pre-conditions **PASS**.

---

## 3. PART A — Axis A (tunnel state / UI sync)

**Verdict: IMPLEMENTED.** Four defects fixed in two files; 24 new JVM tests, all
green. No production behaviour was changed beyond the audit items.

### 3.1 A-2 — `GlobalVpnTracker.onLost` re-check budget

*Defect.* `onLost` re-checked "is any VPN transport still alive?" after a hard-coded
**500 ms**. A genuinely lost system VPN therefore stayed published as `CONNECTED`
for half a second — long enough for the UI to look healthy and for Auto Mode's 1 s
poll (A-4, path B) to read a stale "connected" and skip its own loss handling.

*Fix.* Extracted a documented file-scope constant,

```kotlin
internal const val VPN_LOST_RECHECK_MS = 50L
```

and replaced the inline literal (`}, 500)` → `}, VPN_LOST_RECHECK_MS)`).

*Rationale for reducing rather than removing (as the §3.2 design decision allows).*
The delay is load-bearing in **both** directions, so both sides are now pinned by
tests:

- **Ceiling** — the old 500 ms is the defect; 50 ms shrinks the stale-`CONNECTED`
  window 10×.
- **Floor** — it must not be 0. During a network handover (Wi-Fi → mobile),
  `onLost` for the *old* network fires **before** `onAvailable` for the new one, so
  a zero-delay check would read "no VPN transport at all" and issue a spurious
  `ACTION_DISCONNECT` that tears down a tunnel that is merely being re-parented.

`internal const` (not `private`) so a JVM test can pin the bound without
class-loading `GlobalVpnTracker`: a `const val` is inlined by the compiler, so
referencing it emits no reference to the Android-dependent object.

### 3.2 A-3 — protocol reconciliation instead of protocol egating

*Defect.* `GlobalVpnState` is a single slot, but two stacks publish into it. Every
terminal branch therefore guarded itself with `if (_vpnState.value.protocol ==
"<me>")`, i.e. it **dropped** the event whenever some other protocol happened to own
the slot. Dropping a stale teardown from the loser is fine; dropping the teardown
of the stack that *is* published is not, and there was no answer at all when two
stacks were legitimately live.

*Fix.* Extracted the decision into a pure, stateless fold over plain data
(no Android types), and made `updateState` delegate to it:

```kotlin
internal object VpnStackLedger {
    fun reconcile(
        live: List<VpnStackReport>,      // in: current ledger  (also returned)
        current: GlobalVpnState,         // in: currently published state
        event: VpnStackEvent,            // in: one stack's transition
    ): VpnStackReconciliation            // out: new ledger + state to publish (null = leave alone)
}
```

Rules:

- `CONNECTED` for X → X becomes (or refreshes) a live stack and takes the slot
  (last writer wins, exactly as before). Identity is merged across that stack's own
  events, so a refresh that does not repeat a field does not lose it.
- `CONNECTING` / `DISCONNECTING` → pass through verbatim; the stack stays live.
- `DISCONNECTED` / `ERROR` for X → X leaves the ledger, then:
  - if a survivor Y is still live → **republish Y as `CONNECTED` from Y's own
    recorded identity**, never a teardown and never X's identity ("do not clobber
    Y" — the audit's explicit requirement);
  - else if X is the published stack → publish the teardown, identity preserved for
    `ERROR` and blanked for `DISCONNECTED` — byte-for-byte the pre-A-3 result for
    the single-stack case;
  - else → the event is stale by construction: the bookkeeping is applied (X leaves
    the ledger) and the **publication** is suppressed.

All four egating branches were converted to explicit attribution
(`protocol = "SoftEther"` / `protocol = "OpenVPN"`), so the tag is always available
and no longer depends on who currently owns the slot.

*Scope note.* A **fifth** gate exists in the `sstpListener` (`:280`). It was
deliberately left alone: its condition already requires SSTP to own the published
`CONNECTED` slot, which is precisely the case in which the ledger publishes the same
teardown, and in every other case it agrees with the ledger's "publish nothing".
A test (`terminalBranchesNoLongerDropWrongProtocolEvents`) pins that exactly **one**
`if (_vpnState.value.protocol == …)` guard may remain.

*Why the ledger is outside the `object`.* `GlobalVpnTracker`'s initialiser needs
`Handler(Looper.getMainLooper())`, so it cannot be class-loaded in a plain JVM test
— the same constraint that shaped R4a/R4b. Modelling the decision as a pure function
makes the whole §3.3 contract unit-testable; the tracker owns only the live-stack
list (a `CopyOnWriteArrayList`) and applies the verdict.

### 3.3 A-4 — one loss funnel for the three armed paths

*Defect.* `startConnectedWatcher` had three armed loss paths (OS event, 1 s
passive poll, watchdog silence timeout). Each repeated the same five disarm lines
inline — and the copies had already **drifted**: path A nulled
`TunnelStateWatcher.onTunnelLost` *before* `stopLivenessProbe()`, path C *after* it,
and paths B/C called `onUnexpectedDisconnect` directly from the watcher coroutine
while path A wrapped it in a fresh `scope.launch`.

*Fix.* Extracted a single funnel:

```kotlin
private fun reportArmedTunnelLoss(reason: String, dropped: AutoModeCandidate?) {
    adapter.log(reason)
    connectedWatcher?.cancel()
    connectedWatcher = null
    stopLivenessProbe()
    TunnelStateWatcher.onTunnelLost = null
    scope.launch { onUnexpectedDisconnect(dropped) }
}
```

All three detectors now call it, and the disarm order is fixed once in one place.
The funnel is safe from any thread (path A arrives on a ConnectivityManager
thread), which is why the follow-up is dispatched through `scope`.

*What was deliberately NOT unified.* The **detection latencies**. Path A is an event
(immediate), path B a 1 s passive poll, path C the watchdog's silence timeout. Each
is the cheapest correct detector for its own failure mode; flattening them (e.g.
polling at event speed) would delete the passive safety net that catches a quiet
death. A test (`a4_detectionLatenciesAreNotFlattened`) pins that the 1 s poll, the
watchdog timeout and all three distinct reason strings survive.

### 3.4 A-5 — publish `Disconnected` *after* the follow-up decision

*Defect.* `onUnexpectedDisconnect` published `AutoModeState.Disconnected` as its
very first statement, so a consumer observing that state could not tell whether the
run had decided to reconnect (2 s settle delay plus a fresh dial still ahead) or to
stand down. The publication led the OQ-7 / OQ-6 / OQ-1 follow-up.

*Fix.* `Disconnected` is now published **in each terminal branch**, once the decision
is known:

- OQ-7 stand-down (manual connect in flight) → log, then publish `Disconnected`.
- OQ-6 stand-down (setting disabled) → log, then publish `Disconnected`.
- OQ-1 limit → publish `Error(ERROR_RECONNECT_LIMIT)` (strictly more specific than
  `Disconnected`; publishing both would flash a stale state).
- Otherwise → the decision "reconnect" is final; publish `Disconnected`
  **immediately before** the "Unexpected disconnect: …; reconnecting" log, i.e. still
  exactly once per drop and never omitted, but after the decision rather than before
  it.

This keeps `AutoModeReconnectTest` T5 green — T5 asserts
`controller.state.value is AutoModeState.Disconnected` on the OQ-7 stand-down path,
and the publication is still made there (verified: T1–T7 all pass).

---

## 4. PART B — Axis B (R4c): `isTunnelUsable()` in Auto Mode routing

**Verdict: NO-OP — already landed. No change made.**

`VpnServerRepository.productionRouting()` (`:89`) already returns all three inputs
(`preferVpn`, `tunnelUsable`, `hasBoundNetwork`) and `refresh()` (`:163`) already
calls `CollectorRouting.choose(routing())`. `CollectorRouting.choose()`
(`HttpFetcher.kt:319–333`) is the **AND** of all three, including `tunnelUsable`,
which is sourced from `GlobalVpnTracker.isTunnelUsable()`.

Provenance: `git log --oneline -1 -- app/src/main/java/vn/unlimit/vpngate/repository/VpnServerRepository.kt`
→ `a9e16fd` (R4b). `SOURCE CODE`.

**Known honest limitation carried forward, unchanged:** nothing calls
`noteRxAdvance()` from the data-forwarding path (that would touch the frozen
`ConnectionController` / `SoftEtherClient`), so `isTunnelUsable()` can only ever
report **false** today. That is the safe direction, and it is already documented in
`GlobalVpnTracker.kt` (`:144–150`) and covered by `GlobalVpnTrackerTest` /
`RxAdvanceWiringTest`.

---

## 5. PART C — Axis C: TUN EIO classification, bounded read loop, dead constant

**Verdict: NO-OP — already landed. No change made.**

`TunTerminal.kt` already contains all three items, landed by `607c651`
("add RX/EIO counters + classify TUN EIO + drop dead MAX_PACKET_SIZE (R4a, R5, R6)"):

- **C-2** — an explicit EIO branch calls `TunnelHealth.noteTunEio()`, clears
  `isRunning`, logs and returns (`:155–174`), matched by the pure
  `isTunEio(message)` helper (`:41–42`).
- **C-3** — the read loop is already bounded: `start()` uses
  `isRunning.getAndSet(true)` (`:59`) so no second loop can be created, and no
  self-restart exists to bound.
- **C-4** — `MAX_PACKET_SIZE` is already deleted; only
  `internal const val BUFFER_SIZE = 8192` with its R6 explanatory comment remains
  (`:24–33`).

`TunTerminalTest.kt` already exists with **6** passing tests covering `isTunEio`.
`SOURCE CODE` + `TEST`.

---

## 6. PART D — RISK-A: fail-closed release signing

**Verdict: NO-OP for implementation — already landed by `c572050` (Shape 1).
Guard verified firing.** No change made.

`app/build.gradle:88–116` computes `hasReleaseSigningConfig =
releaseKeystoreFile.exists() && releaseSignPassword != null` and, when it is false,
registers a `gradle.taskGraph.whenReady` hook that throws a `GradleException` for any
release `assemble|bundle|package…Release` task. `assembleProDebug` is not matched by
that regex.

Provenance: `git log -S "GradleException" -- app/build.gradle` → `c572050`, i.e. the
guard has existed since v1.0.7. `SOURCE CODE`.

### 6.1 Verification (build probe, exit 1 expected)

```
$ ./gradlew :app:assembleProRelease --no-daemon --console=plain
RELEASE_EXIT=1
Build file 'C:/Dev/SoftEthergai/app/build.gradle' line: 109
* What went wrong:
Release build blocked: release signing configuration is incomplete.
Missing: release keystore file (keyStore/release.jks), SIGN_KEY_PASSWORD (env var or signKeyPassword in local.properties).
Hint: Set SIGN_KEY_PASSWORD env var or signKeyPassword in local.properties, and ensure the release keystore is present (via blackbox_postdeploy on CI) before building a release variant.
```

The guard fires with the intended message at the intended line, and
`:app:assembleProDebug` still succeeds in the same run. `TEST`.

---

## 7. JVM test evidence

Both suites run with `CI=true ./gradlew <task> --rerun-tasks --no-build-cache --no-daemon --console=plain`
(full forced rerun, no cache).

| Module | Task | Tests | Failures | Errors | Skipped | Baseline |
|---|---|---|---|---|---|---|
| `:app` | `testProDebugUnitTest` | **671** | **0** | **0** | 1 | 647 / 0 / 0 / 1 |
| `:SoftEtherClient` | `test` | **173** | **0** | **0** | 0 | ≥173 / 0 / 0 / 0 |

Both `BUILD SUCCESSFUL`. **Delta = +24 tests, +0 failures.** `TEST`

### 7.1 New tests (24)

`app/src/test/java/vn/unlimit/vpngate/state/VpnStackLedgerTest.kt` (new, 13) —
behavioural tests for the pure A-3 decision: single-stack parity (teardown clears
the slot; `ERROR` keeps identity; pass-throughs keep the stack live; a reconnect
refreshes rather than duplicates), the A-3 headline case (a survivor is republished
as `CONNECTED` with **its own** identity), last-writer-wins survivor selection,
stale-event suppression, unattributable events, and ledger purity (inputs untouched).

`GlobalVpnTrackerTest.kt` (extended, +5) — A-2 bound from both sides
(`VPN_LOST_RECHECK_MS` < 500 ms and ≥ 10 ms), the named-constant-not-literal and
documentation source-shape guards, and the A-3 guards: exactly one surviving
protocol egate, the SoftEther/OpenVPN egates gone, and `updateState` delegating to
`VpnStackLedger`.

`AutoModeControllerTest.kt` (extended, +6) — three **behavioural** A-5 tests driven
through the existing `onUnexpectedDisconnectForTests()` seam, asserting on a shared
ordered trace of `adapter.log` + `onStateChange` that the OQ-7 / OQ-6 decision log
**precedes** the `Disconnected` publication (and that it is published exactly once),
plus the reconnect path publishing `Disconnected` together with its decision; and
three A-4 source-shape tests pinning the single funnel, its canonical disarm order,
the `scope.launch` dispatch, and that the detection latencies were **not** flattened.

### 7.2 Test-harness note

`AutoModeControllerTest`'s fake `ConnectionAdapter` gained an optional
`var trace: MutableList<String>?` (null by default, so every pre-existing test is
untouched) that mirrors log lines into a caller-supplied list. The A-5 ordering
assertions are deterministic because everything inside `onUnexpectedDisconnect` runs
sequentially in a single coroutine, even though the controller dispatches across
threads.

### 7.3 One real defect found in my own first draft

The initial run failed **3** of my new source-shape assertions (671 completed,
3 failed). All three were assertion bugs in the tests, not product bugs:
(1) the funnel regex counted the definition as a 4th call site;
(2) the egate regex matched a default-argument expression and a KDoc quotation, not
just `if (…)` guards; (3) the documentation check read *after* the declaration
instead of before it. All three were corrected and the full suite re-run clean.
Recorded here because the failed intermediate state is part of the evidence trail.

---

## 8. Build evidence

| Step | Task | Result | Label |
|---|---|---|---|
| Release guard probe | `:app:assembleProRelease` | exit 1, intended `GradleException` (§6.1) | `TEST` |
| Debug build | `:app:assembleProDebug` | **BUILD SUCCESSFUL in 51s** | `TEST` |
| APK | `app/build/outputs/apk/pro/debug/app-pro-debug.apk` | **SHA-256 `713b1014e3785f35bbc69e74e05726ce10fb3e87c9a57ad66072734091186c97`** | `TEST` |
| Build reproducibility | rebuild after revert/restore cycle | identical SHA-256 `713b1014…` | `TEST` |
| Baseline control build | `:app:assembleProDebug` at `71edd679` | BUILD SUCCESSFUL, SHA-256 `4541e7f82f0a8e65676264400c4010b1d124b01903ba8a46e57b9e14265012ac` | `TEST` |

`assembleProDebug` still succeeds, so PART D's guard does not leak into debug builds. `TEST`

---

## 9. Device evidence — INCLUDES A STOP-CONDITION EVENT

Capture: `adb logcat -v time`, one managed background capture covering the whole
session, `_p12_device.log` (60,544 lines, 15:57:51 → 16:41:46). `PHYSICAL DEVICE`

### 9.1 Install and data integrity

| Item | Value |
|---|---|
| Install command | `adb install -r app-pro-debug.apk` (no uninstall, no data clear) |
| Result | `Success` |
| `firstInstallTime` **before** | `2026-09-28 16:05:05` |
| `firstInstallTime` **after** | `2026-09-28 16:05:05` — **UNCHANGED** |
| `lastUpdateTime` | advanced as expected for `-r` |

No device setting was changed, no uninstall, no data clear. `PHYSICAL DEVICE`

### 9.2 Cycles actually completed

| # | Build | Time | Outcome |
|---|---|---|---|
| 1 | PART A `713b1014…` | 15:58:11 | `[AUTO] Connected successfully`; `[TOFU] match key=59.138.182.93\|59.138.182.93\|1378` |
| — | control baseline `4541e7f8…` | 16:24:03 | `[AUTO] Connected successfully`; `[TOFU] match` |
| 2 | PART A `713b1014…` (re-test) | 16:30:41 | `[AUTO] Connected successfully`; `[TOFU] match` |
| 3 | PART A `713b1014…` (re-test reconnect) | 16:35:03 | `[AUTO] Connected successfully` (tap while disconnected → new dial) |

**The mandated 5 AutoConnect + 2 stress cycles were NOT completed** (1 of 5, plus the
control and re-test cycles). `PHYSICAL DEVICE`

### 9.3 The STOP-condition event: ANR + process death (16:06)

```
16:05:56.311  MotionEvent(action=DOWN) delivered to MainActivity
16:06:01.327  InputDispatcher: ... is not responding. Waited 5001ms for MotionEvent(action=DOWN)
16:06:01.327  Canceling events for ... because it is unresponsive
16:06:01.500  MIUIScout ANR: oldest main-thread message is
              SharedPreferencesImpl$EditorImpl$$ExternalSyntheticLambda0, pending 1m24.957s
              (also queued: AndroidPrefetchScheduler, Choreographer frame callback, barrier=2352)
16:06:01.999  Signal Catcher: reacting to signal 3
16:06:02.210  Wrote stack traces to tombstoned
16:06:06.882  SurfaceFlinger: frontWin=... Application Not Responding: vn.unlimit.vpngatepro
16:06:52.892  ConnectivityService: binderDied(uid/pid:10340/23115) — process gone
```

Main-thread degradation began at **15:58:56** and escalated monotonically:
`Skipped 35 frames` → `41` → `43` → `54` → `85` → `1311` (15:59:35) → `1821`
(16:00:34) → … → ANR at 16:06:01, then process death (pid `23115` → `25247`).

While the UI thread was blocked, the **tunnel itself stayed healthy**:
`Stats: sent=131988 bytes (572 pkts), received=1253218 bytes (3814 pkts)` and
keepalives every ~100 ms right up to 16:06:01. So this was a UI-thread stall, not a
network or native failure.

`NOT REPRODUCED` on the second run (see §9.5).

### 9.4 Forensic attribution — the changed code is NOT on the failing path

`GlobalVpnTracker` logged **exactly one line** for pid `23115` in the whole run:

```
15:58:10.410  D/GlobalVpnTracker(23115): System VPN network available: 139
```

That is `onAvailable`. **`onLost` never fired for that process**, therefore:

- **A-2 is exonerated.** Its `postDelayed(VPN_LOST_RECHECK_MS)` block never executed,
  so the 50 ms budget cannot have caused the stall. (No
  "All system VPN transports lost" warning was ever logged either, confirming the
  re-check path was never taken.)
- **A-3 is exonerated.** The service published no further state changes between
  `CONNECTED` (15:58:10.346) and the process restart (16:07), so `updateState` — and
  therefore `VpnStackLedger.reconcile` — was not invoked during the stall window.
- **A-4 / A-5 are exonerated.** No `[AUTO]` line other than the initial connect
  sequence was logged: `reportArmedTunnelLoss` and `onUnexpectedDisconnect` never
  ran.

The thread dump went to `/data/anr/` and tombstoned, both unreadable without root
(`adb pull` → `Permission denied`; no `su` on this retail device), so the exact
blocked frame is **`UNKNOWN`**. What *is* established is that none of the 4 changed
code paths executed.

### 9.5 Attribution experiments (decisive)

Two experiments were run to separate "my change" from "pre-existing / environment".

**(a) Baseline control at `71edd679`** (PART A temporarily reverted via file backup,
not stash; SHA-256 `4541e7f8…`). Same device, same Wi-Fi (`PARS-M`, RSSI -50), same
server (`vpn315836095`, `59.138.182.93:1378`), same interaction sequence:
- Connected at 16:24:03, `[TOFU] match`.
- Tap-while-connected → clean teardown: `VPN DISCONNECTED`, `State changed:
  DISCONNECTED`, `[TEARDOWN-REENTRY]` at 16:25:03.
- **Zero `Choreographer` skipped-frame warnings.**

**(b) Reproducibility re-test of the PART A build** (identical SHA-256 `713b1014…`).
- Connected at 16:30:41, `[TOFU] match`.
- Tap-while-connected → clean teardown at 16:31:26 (`MotionEvent` DOWN 16:31:26.408 →
  `onStartCommand: DISCONNECT` 16:31:26.873 → `Stopping VPN` / `State changed:
  DISCONNECTED` 16:31:26.876), then a tap-while-disconnected → a fresh dial →
  `Connected successfully` at 16:35:03.
- **Zero `Choreographer` skipped-frame warnings. No ANR. No process death.**

So: the PART A build behaves **identically to the baseline** on both connect and
disconnect, and the ANR **did not reproduce** on the same build, same device, same
server, same network.

### 9.6 Most likely cause of the one-off ANR

The first run had a pre-existing race that neither the control nor the re-test had:

```
15:58:09.488  [AUTO] Native slot busy; retrying same server in 2s (attempt 1/2) (holder=attempt=3 phase=CONNECTING)
15:58:11.522  [AUTO] Tunnel is established (notification missed); treating attempt as connected
15:58:11.522  [AUTO] Connected successfully
15:58:11.524  onStartCommand: action=vn.unlimit.softether.CONNECT     ← deferred retry fires a DUPLICATE connect
15:58:11.528  W/SoftEtherVpnService: Start refused: a connected session already owns the service
```

A stale `NATIVE_BUSY` slot holder (left over from the pre-install session in that
long-lived process) made Auto Mode schedule a 2 s retry; the tunnel actually came up
meanwhile (notification "missed"), so the retry issued a **second** `CONNECT`, which
the service correctly **refused**. That duplicate-connect race is a *pre-existing*
defect untouched by this phase, and it did not occur in either control run (fresh
process → no stale slot holder).

`INFERENCE`: the ANR is most consistent with a pre-existing UI-thread performance
defect (a growing Compose `LazyColumn` log panel plus live per-second stats — the
ANR scout's oldest queued message was a `SharedPreferencesImpl$EditorImpl` apply
behind a Compose `AndroidPrefetchScheduler` and a sync barrier), possibly perturbed
by that duplicate-connect sequence, and **not** by PART A. The exact blocked frame
remains `UNKNOWN` without root access to `/data/anr`.

### 9.7 Log-count summary for the session

| Marker | Count | Note |
|---|---|---|
| `[AUTO] Connected successfully` | 4 (2 PART A, 1 control, 1 re-test reconnect) | all cycles that were run |
| `[TOFU] match` | 3 | 0 mismatches |
| `[TOFU] mismatch` | 0 | |
| `[AUTO] GlobalVpnTracker confirmed disconnected` | 0 | A-4 path B never fired |
| `No proof of life for` | 0 | A-4 path C never fired |
| `Tunnel disconnected externally / revoked by system` | 0 | A-4 path A never fired |
| TUN EIO (`TunTerminal` EIO branch) | 0 | no TUN revocation coincided with a run |
| `FATAL EXCEPTION` / `SIGSEGV` for our pid | 0 | no crash of the app |
| `tombstone` for our pid | 0 | the 16:06 tombstones belong to `gle.android.gms` |
| `Application Not Responding: vn.unlimit.vpngatepro` | 1 | 16:06, analysed in §9.3–§9.6 |

---

## 10. Negative assurance (explicit)

| Assertion | Status |
|---|---|
| No forbidden file touched (`aes_wrapper.c`, `softether_jni.c`, `SoftEtherClient.kt`, `NativeLifetimeRegistry.kt`, `NativeHandleSlot.kt`, `NativeAttemptCoordinator.kt`, `ServerFingerprintStore.kt`, `ServerFingerprintStoreAndroid.kt`, `ConnectionController.kt`) | **HOLD** |
| `PROJECT_SPECIFICATION.md` untouched | **HOLD** |
| No existing report modified or deleted | **HOLD** |
| §22 L1–L10 and §1.6.2–§1.6.9 untouched | **HOLD** |
| `AndroidManifest.xml` / `network_security_config.xml` untouched | **HOLD** |
| `CMakeLists.txt` / `settings.gradle` untouched | **HOLD** |
| No uninstall, no data clear, no device setting change | **HOLD** (`adb install -r` only) |
| `firstInstallTime` unchanged | **HOLD** (`2026-09-28 16:05:05` before and after) |
| No `git add -A` / `git add .` | **HOLD** (nothing staged at all) |
| No `git push --force` | **HOLD** |
| No tag created, moved or deleted (5 tags, 0 at HEAD) | **HOLD** |
| No `git reset` / `clean` / `stash` | **HOLD** (the baseline control was done with `cp` backups, not stash) |
| C5 not executed | **HOLD** |
| No build-config change beyond PART D (and PART D turned out to need none) | **HOLD** (`app/build.gradle` unmodified) |
| `D:/PMC/Documents/Android/SoftEthergai` untouched | **HOLD** |
| Only authorized files modified | **HOLD** — see §11.1 |

### 10.1 Files modified (all within the authorized list)

```
 M app/src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt
 M app/src/main/java/vn/unlimit/vpngate/state/GlobalVpnTracker.kt
 M app/src/test/java/vn/unlimit/vpngate/automode/AutoModeControllerTest.kt
 M app/src/test/java/vn/unlimit/vpngate/state/GlobalVpnTrackerTest.kt
?? app/src/test/java/vn/unlimit/vpngate/state/VpnStackLedgerTest.kt   (new)
```

`AutoModeController.kt` was touched **only** in the A-4 (`startConnectedWatcher` /
`reportArmedTunnelLoss`) and A-5 (`onUnexpectedDisconnect`), plus the
`onUnexpectedDisconnectForTests` KDoc and its `isTunnelBusy` /
`reconnectInitialDelayMs` constructor plumbing used by the new tests. Nothing else in
that previously-frozen file was altered.

`git diff --stat` (uncommitted):

```
 .../vn/unlimit/vpngate/automode/AutoModeController.kt   | 138 +++++----
 .../vn/unlimit/vpngate/state/GlobalVpnTracker.kt       | 286 +++++++++++++++--
 .../vn/unlimit/vpngate/automode/AutoModeControllerTest.kt | 269 +++++++++++++++
 .../vn/unlimit/vpngate/state/GlobalVpnTrackerTest.kt   | 116 ++++++
 4 files changed, 742 insertions(+), 67 deletions(-)
```

---

## 11. Commits and push

**No commits were made and nothing was pushed.**

Rationale: the phase's mandated sequence is *JVM tests → build → **device smoke test
(5 AutoConnect + 2 stress cycles)** → report → commits → push*. The device smoke test
aborted on a STOP condition (§9), so the code has **no complete device evidence
trail**. Committing it without that evidence — or worse, pushing it — would publish a
change whose on-device behaviour is only partially characterised, and would break the
`HEAD == origin/main` invariant the phase treats as a pre-condition.

| Item | Value |
|---|---|
| Commits created | **0** |
| HEAD after phase | `71edd67935371450b82e5eb162a0d8bf05da13d6` (unchanged) |
| origin/main after phase | `71edd67935371450b82e5eb162a0d8bf05da13d6` (unchanged) |
| Pushes | **0** |
| Tags | unchanged (5 total, 0 at HEAD) |
| Working tree | 4 modified tracked files + 1 new test, **uncommitted** |

The intended commit plan (for the follow-up phase, if the device work is completed)
is unchanged from the brief:

1. `fix(automode): reduce state-owner mismatches in tunnel/UI sync (Axis A)` —
   `GlobalVpnTracker.kt`, `AutoModeController.kt`, `GlobalVpnTrackerTest.kt`,
   `AutoModeControllerTest.kt`, `VpnStackLedgerTest.kt`
2. *(only if a future change makes them non-no-op)* Axis C, Axis B, RISK-A commits
3. `docs: report for IMPLEMENT-AND-VERIFY-P1-REMAINING`

---

## 12. Status: PARTIAL — and recommended next steps

**What is DONE and trustworthy:**
- PART A (A-2, A-3, A-4, A-5) implemented, with **24 new JVM tests, all green**
  (671/0/0/1 app, 173/0/0/0 client, full forced reruns).
- PARTs B, C and D proven to be **already landed**, with provenance (`a9e16fd`,
  `607c651`, `c572050`) and, for D, a **live probe** showing the fail-closed guard
  firing with the intended message at `app/build.gradle:109`.
- Reproducible debug build; APK SHA-256 `713b1014…`.
- Device: install with `-r`, `firstInstallTime` unchanged, TOFU matches, no crash,
  no tombstone for the app, and a **baseline-controlled, reproducing-clean** pairing
  showing the PART A build behaves the same as the base build on connect and
  disconnect.

**What is NOT done:**
- The 5 AutoConnect + 2 stress-cycle smoke test (1 of 5 done).
- Commits and push (deliberately withheld, §11).

**Recommended next phase (needs your go-ahead — not proposed autonomously):**
1. Re-run the device smoke test **on the current uncommitted tree** for the full
   5 + 2 cycles, with a longer per-cycle observation window, to complete the
   device evidence. No code change is required for this.
2. Separately investigate the two **pre-existing** defects surfaced by this phase's
   forensics, neither of which is in scope here:
   - the duplicate-`CONNECT` race (`NATIVE_BUSY` retry firing after the tunnel is
     already up — visible at 15:58:11.524, benignly refused today); and
   - the main-thread degradation behind the 16:06 ANR (UI-thread profiling of the
     live log panel; the `/data/anr` trace is unreadable without root).
3. Then commit the five files of PART A plus this report and push.
