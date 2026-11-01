# DEVICE-VERIFY-AUTOCONNECT-FIX-2 (v1.0.32) — Device Verification Report

**Phase:** DEVICE-VERIFY-AUTOCONNECT-FIX-2-v1.0.32
**Date:** 2026-10-18
**Repo:** `C:\Dev\SoftEthergai` · branch `main`
**Device:** `5585fecc` — Xiaomi Redmi 2201117TG (`spes_global`), Android 13 (SDK 33), MIUI `V14.0.2.0.TGKMIXM`
**Status:** Verification phase only. No source, test, or spec file was modified; nothing was committed.

---

## §1 — Verdict

**PARTIAL PASS.**

Both v1.0.32 fixes were confirmed working **on hardware**, which was the primary purpose:

| Fix | Scenario | Result |
|---|---|---|
| BUG-A (D-4 predicate includes `CONNECTED`) | S1 | **PASS** |
| BUG-B (wedge on repeated `NATIVE_BUSY`) | S3 | **PASS** |
| Instrumentation `SLOT_UNRELEASED_DIAG` | SLOT_DIAG | **NOT EXERCISED — instrumentation gap found (§7)** |
| OP-R6 regression | S2 | **PASS** |
| D-2 exception→next-protocol regression | S4 | **UNTESTABLE** (no natural exception) |

The two shipped bug fixes are real and observable. The one negative finding is about the
**instrumentation itself**, not about behaviour: the diagnostic line was attached to the wrong
one of two structurally identical slot-wait sites, so the exact branch that terminated the run on
this device produced no diagnostic. Detail and evidence in §7.

---

## §2 — Pre-conditions

All STEP 1 / STEP 2 gates passed before anything was run on the device.

| Check | Value | Label |
|---|---|---|
| `git rev-parse HEAD` | `248d06dd67c2dd75038da249d474e368413a21ac` | SOURCE CODE |
| `git rev-parse origin/main` | `248d06dd67c2dd75038da249d474e368413a21ac` | SOURCE CODE |
| HEAD == origin/main | yes (identical) | SOURCE CODE |
| Branch | `main` | SOURCE CODE |
| Tracked modifications | none (`git status --porcelain` returns only `??` entries) | SOURCE CODE |
| Spec version | `**Document version:** 1.0.32` (line 8) | SOURCE CODE |
| `adb devices -l` | `5585fecc  device product:spes_global model:2201117TG device:spes` | PHYSICAL DEVICE |
| Device serial used | `5585fecc` everywhere | PHYSICAL DEVICE |

**APK hash gate (STEP 2):**

| Item | Value | Label |
|---|---|---|
| Working-tree APK | `app/build/outputs/apk/pro/debug/app-pro-debug.apk`, 63,748,214 bytes | SOURCE CODE |
| Working-tree SHA-256 | `946CA9689885921EF1EEFAC7907BB9777EB79A73249EFC514BB1B97F8EBAA347` | SOURCE CODE |
| Report §9 baseline (`AUTOCONNECT_FIX_2_REPORT.md:218`) | `946CA968…EBAA347` | PRIOR REPORT |
| Match | **identical** | SOURCE CODE |
| Installed APK, pulled back from `/data/app/…/base.apk` after install | `946CA9689885921EF1EEFAC7907BB9777EB79A73249EFC514BB1B97F8EBAA347` | PHYSICAL DEVICE |

The installed binary is byte-identical to the audited build — the "am I looking at the right
code?" question is closed by hash, not by assumption.

**Device state note (pre-existing, not created by this phase):**
`SETTING_AUTO_MODE_TIMEOUT_SECONDS = 5` in `shared_prefs/vpn_setting_data_pro.xml`
(**PHYSICAL DEVICE**). The code default is 12 s (§1.6.24). This is the same S1 deviation recorded in
`DEVICE_VERIFY_AUTOCONNECT_FIX_REPORT.md` §3; per the brief's "do NOT modify device settings", it
was **left untouched**. It is not a code defect, but it does change what the run logs: the native
slot budget is derived as `5 × T` (`AutoModeEngine.kt:82`), so every stop line in this report reads
**25000 ms**, not the 60,000 ms a 12 s pref would produce. Handled in §7.

---

## §3 — Install + smoke (STEP 3)

| Item | Value | Label |
|---|---|---|
| `adb install -r` | `Performing Streamed Install` → **Success** | PHYSICAL DEVICE |
| Data preserved | `-r` used; no uninstall, no `pm clear` | PHYSICAL DEVICE |
| `versionCode` | **143** (expected 143) | PHYSICAL DEVICE |
| `versionName` | `2.4.10-dev` | PHYSICAL DEVICE |
| `lastUpdateTime` | `2026-10-18 18:20:23` | PHYSICAL DEVICE |
| Launch | `monkey -p vn.unlimit.vpngatepro -c android.intent.category.LAUNCHER 1` → 1 event injected | PHYSICAL DEVICE |
| Process alive after 10 s | PID **15102** | PHYSICAL DEVICE |
| FATAL / AndroidRuntime / ANR after launch | **none** | PHYSICAL DEVICE |

No STOP condition from STEP 3.

---

## §4 — Scenario S1 (BUG-A / D-4): manual tunnel active → press → refuse

**Result: PASS.**

### Setup (PHYSICAL DEVICE)
1. Disconnected state confirmed: no `tun` interface, WiFi `NOT_VPN`.
2. Opened Server List → tapped the first server (`public-vpn-199.opengw.net` / `219.100.37.196`).
3. In the protocol picker tapped the **radio circle** for `OpenVPN over TCP` (the row text does not
   commit the selection — same finding as the previous phase).
4. Manual connection reached **Connected**:
   - `tun0 inet 10.245.119.125/30 scope global tun0` (PHYSICAL DEVICE)
   - `Vpn: Established by vn.unlimit.vpngatepro on tun0` (PHYSICAL DEVICE)
   - Sheet showed `Connected: SUCCESS,10.245.119.125,…` (PHYSICAL DEVICE)
5. Navigated to **Auto Mode**, which showed **Disconnected / "Tap to connect"** while the manual
   tunnel was live — exactly the state BUG-A was blind to.

### Action
Pressed the hero AutoConnect button. A "Background Battery Optimization" dialog intercepted the
first press (a permission gate: `AutoModeScreen.kt` `proceedWithPermissionsAndConnect()`, no state
change). Dismissed it; the press then reached the guard.

### Observed
```
10-18 18:25:10.505 15102 15102 D AutoMode: [AUTO] start refused: a tunnel is already active
10-18 18:25:20.135 15102 15102 D AutoMode: [AUTO] start refused: a tunnel is already active
```
(PHYSICAL DEVICE — exact expected string.)

Afterwards:
- `tun0 inet 10.245.119.125/30` **still present** (PHYSICAL DEVICE)
- No `Trying #` line, no `Continuing from server`, no `[AUTO] Started` (PHYSICAL DEVICE)
- Auto Mode state remained **Disconnected** (PHYSICAL DEVICE, screenshot `s09_s1_refused.png`)
- The app's own log panel displayed the refusal lines, so the refusal is user-visible, not
  log-only (PHYSICAL DEVICE)

### Interpretation
The production predicate now reaches `GlobalVpnTracker.isActiveOrBusy`, which includes `CONNECTED`
(**SOURCE CODE**: `GlobalVpnTracker.kt`, `AutoModeEngine.kt:125`). The run did not start and the
manual tunnel was **not** torn down — the exact failure mode recorded in
`DEVICE_VERIFY_AUTOCONNECT_FIX_REPORT.md` §5 is gone.

---

## §5 — Scenario S2 (OP-R6 regression): press while Connected

**Result: PASS.**

### Action
With AutoConnect **Connected** to `vpn131251792`, pressed the hero button once (the same button
means stop/disconnect in this state).

### Observed
```
10-18 18:30:14.461 … [AUTO] Re-entrant teardown completion observed; native confirmation expected shortly (teardown#1(session=1, NOOP_REENTRANT))
10-18 18:30:15.684 … [AUTO] Reachable servers selected: 88
10-18 18:30:15.686 … [AUTO] Continuing from server #2 of 88
10-18 18:30:15.686 … [AUTO] Trying #1 vpn752839599
```
(PHYSICAL DEVICE — exact expected strings.)

### Interpretation
`Continuing from server #2 of 88` proves `resumeFromIndex = currentServerIndex + 1 = 1` was consumed
on the next run instead of restarting at index 0 (**SOURCE CODE**: `AutoModeController.kt:1089`,
`:1026` region). `Trying #1` is the per-run attempt counter and is expected to read 1 here — the
two counters measure different things, as established in the previous phase. No `Continuing from
server #1` regressed case was observed. **v1.0.31 OP-R6 behaviour is intact.**

---

## §6 — Scenario S3 (BUG-B): repeated `NATIVE_BUSY` → wedge → rotate

**Result: PASS** — and this scenario **did** occur naturally, twice, in two separate runs.

### Occurrence A (run started 18:30:14, continuation from S2)
```
18:30:34.181 [AUTO] Native slot released after 13333ms
18:30:34.182 [AUTO] Native slot released; retrying SOFTETHER_TCP on vpn752839599
18:30:34.277 [AUTO] Attempt after the slot was released did not come up (NATIVE_ERROR); moving on
18:30:34.279 [AUTO] Protocol failed: SOFTETHER_TCP
18:30:36.958 [AUTO] Teardown finished but NATIVE CLEANUP UNCONFIRMED (teardown#4(session=1, NATIVE_UNRESOLVED)); ownership retained, a new native attempt will be refused
18:30:42.929 [AUTO] Native slot released after 5970ms
18:30:48.071 [AUTO] NATIVE_BUSY x2 on vpn752839599; recording a wedge (the endpoint never accepted a native attempt)
18:31:01.403 [AUTO] Native slot released after 13331ms
18:31:01.404 [AUTO] Native slot released; retrying SOFTETHER_UDP on vpn752839599
18:31:01.572 [AUTO] Attempt after the slot was released did not come up (NATIVE_ERROR); moving on
18:31:01.575 [AUTO] Protocol failed: SOFTETHER_UDP
18:31:10.398 [AUTO] Native slot released after 6070ms
18:31:10.399 [AUTO] Connection failed: vpn752839599
18:31:10.399 [AUTO] Trying #2 vpn275348493
```
(PHYSICAL DEVICE.)

### Occurrence B (run started 18:38:17)
```
18:38:46.034 [AUTO] Native slot released after 20906ms
18:38:46.186 [AUTO] Protocol failed: SOFTETHER_TCP
18:39:01.621 [AUTO] NATIVE_BUSY x2 on vpn131251792; recording a wedge (the endpoint never accepted a native attempt)
…
18:39:28.741 [AUTO] Connection failed: vpn131251792
18:39:28.741 [AUTO] Trying #2 vpn752839599
…
18:40:01.852 [AUTO] NATIVE_BUSY x2 on vpn752839599; recording a wedge (the endpoint never accepted a native attempt)
18:40:01.854 [AUTO] Excluding vpn752839599 from this session after 2 wedges.
…
18:40:28.171 [AUTO] Connection failed: vpn752839599
18:40:28.171 [AUTO] Trying #3 vpn275348493
```
(PHYSICAL DEVICE.)

### Interpretation
- `NATIVE_BUSY x2 … recording a wedge` appears **three times**, on two distinct hosts
  (`vpn752839599`, `vpn131251792`). This line **did not exist before v1.0.32** — it is the new
  BUG-B branch (**SOURCE CODE**: `AutoModeController.kt:1662-1669`,
  `NATIVE_BUSY_WEDGE_THRESHOLD = 2` at `:381`).
- `Excluding vpn752839599 from this session after 2 wedges.` appears **once**. This is the line the
  previous device run **never** produced — the precise symptom recorded in
  `DEVICE_VERIFY_AUTOCONNECT_FIX_REPORT.md` §7 ("`vpn131251792` was re-selected four times with no
  exclusion logged").
- Both occurrences **rotated**: `Connection failed: <host>` → `Trying #N+1 <different host>`.
  In Occurrence B the exclusion happened mid-server and the run still advanced to `vpn275348493`.
- `vpn131251792` — the server that was retried indefinitely in the previous phase — wedged and was
  left behind in Occurrence B.
- The exclusion threshold itself is unchanged (2); a server wedged **once** is deliberately not
  excluded. Observed consequence: `vpn131251792`, wedged once in run A, was legitimately retried as
  `Trying #1` in run B (**PHYSICAL DEVICE**). This is the documented `FIX-3` rehabilitation
  semantics, not a new bug (**SOURCE CODE**: `AutoModeController.kt:1857-1862`,
  `wedgeTracker.clear(key)` on a verified tunnel).

---

## §7 — SLOT_DIAG: the instrumentation did **not** fire — and why

**Result: NOT EXERCISED. Instrumentation gap identified.**

### What the brief expected
That the run terminating with the native slot held would print
`SLOT_UNRELEASED_DIAG waited=… budget=… outcome=… protocol=… server=… tracker=… ownTunnel=… registry=…`
and/or `isOwnTunnelEstablished returned false despite native state CONNECTED`.

### What actually happened
```
grep -c "SLOT_UNRELEASED_DIAG"                          → 0
grep -c "isOwnTunnelEstablished returned false"         → 0
```
(PHYSICAL DEVICE, full buffer, all runs.)

The run **did** terminate on the slot condition, three times, e.g.:
```
18:31:40.626 [AUTO] Native slot not released within 25000ms (attempt=6 phase=CONNECTING); stopping
18:40:58.479 [AUTO] Native slot not released within 25000ms (attempt=15 phase=CONNECTED); stopping
```
(PHYSICAL DEVICE.)

### Root cause of the missing diagnostic (SOURCE CODE)

There are **two** slot-wait/stop sites in `AutoModeController.kt`, and only the second was
instrumented:

| Site | Trigger | Message format | Diagnostic? |
|---|---|---|---|
| **1695** (NATIVE_BUSY branch) | `NATIVE_BUSY` → wait for slot release → still held | `within ${nativeSlotAwaitMs}ms (${describe()}); stopping` | **NO** |
| **1834** (post-attempt branch) | attempt ended, wait for slot release → still held | `within ${nativeSlotAwaitMs}ms after ${outcome.name} on ${protocol.id} (${describe()}); stopping` | yes (`SLOT_UNRELEASED_DIAG` at `:1841`) |

The device lines contain **no** `after <OUTCOME> on <PROTOCOL>` clause, and `(attempt=6
phase=CONNECTING)` is exactly `NativeLifetimeRegistry.describe()`'s own format
(`SoftEtherClient/…/NativeLifetimeRegistry.kt:379`, `"attempt=$activeAttemptId phase=$activePhase"`).
Therefore every observed stop came from **site 1695**, and site 1834 — the instrumented one — was
never reached in these runs.

**Label: SOURCE CODE + PHYSICAL DEVICE.** This is a real defect in the instrumentation, not a
mystery: v1.0.32 added the diagnostic to one of the two places that can terminate a run for this
reason. The brief asked for instrumentation of "the slot-non-release" — it was applied to the
narrower of the two paths.

### What the stopping messages *do* reveal (inference, labelled)

Even without the diagnostic line, the `describe()` suffix carries real information:

- `phase=CONNECTING` (18:31:40) — the registry still believed a native **connect was in flight**.
- `phase=CONNECTED` (18:40:58) — the registry believed the native side had reached **CONNECTED**
  and was now in no teardown phase that could expire. This is the more suspicious of the two: a
  tunnel that reports CONNECTED and still holds the slot after 25 s is exactly the
  "`DHCP success` yet slot never released" pattern of the previous phase.

Corroborating evidence from the same run (**PHYSICAL DEVICE**):
```
18:40:58.479 [AUTO] Native slot not released within 25000ms (attempt=15 phase=CONNECTED); stopping
18:40:58.479 [AUTO] Requesting recovery teardown so the unresolved native operation can be released …
18:40:58.771 ConnectionController: Released process-wide native slot for this attempt
```
The slot was released **292 ms after** the run had already been declared stuck. That is the same
race shape as the 2026-10-17 case documented at `AutoModeController.kt:1676-1682` (abandoned 27 ms
before the native operation returned), only inverted: here the wait budget is 25,000 ms and the
release still landed 292 ms too late. **INFERENCE:** the release is not hung, it is *late* — a
timing/ordering problem between the teardown confirmation and the slot release, occurring while the
registry still reports `CONNECTED`. **Root cause remains UNKNOWN**; the instrumented line that was
designed to discriminate between "tracker CONNECTED while ownership false", "registry holding a
never-released attempt", and "post-establishment leak" never ran, so no definitive assignment is
possible from this data.

### Note on the 25,000 ms figure
`AutoModeEngine.kt:82` computes `nativeSlotAwaitMs = timeoutSeconds × 1000 × 5`. With the device
pref at 5 s this yields 25,000 ms; the code constant `NATIVE_SLOT_AWAIT_MS = 30_000L`
(`:441`) is only the JVM default and is **overridden in production**. No inconsistency
(**SOURCE CODE** + PHYSICAL DEVICE for the pref).

---

## §8 — Scenario S4 (D-2 regression): exception → next protocol

**Result: UNTESTABLE.**

`grep -c "Connect threw"` over every run in this session → **0** (PHYSICAL DEVICE). No `connect()`
call threw, so the D-2 branch (`AutoModeController.kt:1587`, `:1718`) never executed. Forcing it
would require a device-setting change or synthetic fault injection, both forbidden by the brief.
Marked UNTESTABLE, not PASS.

**Adjacent (weaker) evidence that protocol-level progression works** — this is *not* the D-2
exception path, it is the ordinary non-timeout path, and is labelled as such:
```
18:38:46.186 [AUTO] Protocol failed: SOFTETHER_TCP     → same server retried on UDP
18:39:25.683 [AUTO] Native slot released; retrying SOFTETHER_UDP on vpn131251792
18:39:25.784 [AUTO] Protocol failed: SOFTETHER_UDP     → then next server
```
(PHYSICAL DEVICE.) Protocol-then-server ordering held in all observed rotations.

---

## §9 — Crash / ANR scan (STEP 9)

**Result: CLEAN.**

| Search | Full session buffer | Label |
|---|---|---|
| `FATAL EXCEPTION` | 0 | PHYSICAL DEVICE |
| `ANR in ` | 0 | PHYSICAL DEVICE |
| `AndroidRuntime` fatal | 0 | PHYSICAL DEVICE |
| `Process: vn.unlimit…` death | 0 | PHYSICAL DEVICE |
| Layout-inflation / Compose exception | 0 | PHYSICAL DEVICE |
| App PID continuity | **15102** from launch (18:20) through the final check (18:42) — never changed | PHYSICAL DEVICE |

The app survived three whole-run terminals (`ERROR_NATIVE_UNRESOLVED`) plus an exclusion and kept
serving. Terminal state rendered correctly as **Error / "Auto Mode failed. Tap to try again."**
(screenshot `s24_final_state.png`), not as a crash.

**Recovery check (not in the brief, run to establish the UX impact of the terminal):**
pressing the button again at 18:42:41 started a fresh run that reached `Tunnel established` /
`Connected successfully` in **4.2 s**, with `tun0 inet 10.211.1.86/16` afterwards
(PHYSICAL DEVICE). The terminal is recoverable, not a dead end.

No STOP condition was triggered at any point.

---

## §10 — What was NOT testable, and why

| Item | Why |
|---|---|
| S4 / D-2 exception path | No `connect()` threw in ~25 min of real runs; forcing it needs settings changes or fault injection, both forbidden. |
| `SLOT_UNRELEASED_DIAG` payload fields (`tracker`, `ownTunnel`, `registry`) | The instrumented site (§7) was never reached; the site that fired has no diagnostic. |
| `isOwnTunnelEstablished returned false despite native state CONNECTED` | Same reason — this line lives beside the un-instrumented path's sibling, and its precondition (ownership disagreement) was not reached at the instrumented point. |
| BUG-A guard against a `DISCONNECTING` manual tunnel | Requires catching a sub-second teardown window; only a *settled* `CONNECTED` manual tunnel was exercised (the hazard the bug was about). |
| Exclusion threshold semantics for a server that wedges twice in one run *and then* recovers | Would need a server that flaps; not controllable. |
| Behaviour under the shipped 12 s default timeout | The device pref is persisted at 5 s and settings changes are forbidden; therefore all stop budgets read 25,000 ms, not 60,000 ms. |

---

## §11 — Evidence labels per claim

| # | Claim | Label |
|---|---|---|
| 1 | HEAD == origin/main == `248d06dd…`; spec 1.0.32; no tracked modifications | SOURCE CODE |
| 2 | Working-tree and installed APK both `946CA968…EBAA347` | SOURCE CODE + PHYSICAL DEVICE |
| 3 | `install -r` succeeded, data preserved, versionCode 143 | PHYSICAL DEVICE |
| 4 | Manual tunnel established: `tun0 10.245.119.125/30`, `Established by vn.unlimit.vpngatepro on tun0` | PHYSICAL DEVICE |
| 5 | BUG-A: `[AUTO] start refused: a tunnel is already active` emitted; no run started; manual tunnel survived | PHYSICAL DEVICE |
| 6 | The guard predicate now includes `CONNECTED` | SOURCE CODE |
| 7 | OP-R6: `Continuing from server #2 of 88` then `Trying #1 vpn752839599` | PHYSICAL DEVICE |
| 8 | BUG-B: `NATIVE_BUSY x2` ×3 on 2 hosts; `Excluding … after 2 wedges` ×1; rotation followed | PHYSICAL DEVICE |
| 9 | The `NATIVE_BUSY x2` and `Excluding` lines are new in v1.0.32 | SOURCE CODE |
| 10 | Exclusion threshold remains 2; single wedge not excluded | SOURCE CODE + PHYSICAL DEVICE |
| 11 | `SLOT_UNRELEASED_DIAG` never printed despite three slot-based terminals | PHYSICAL DEVICE |
| 12 | Two distinct stop sites exist; only `:1834` is instrumented; observed format matches `:1695` | SOURCE CODE |
| 13 | `describe()` produces `attempt=N phase=P` | SOURCE CODE |
| 14 | Stops observed at `phase=CONNECTING` and `phase=CONNECTED` | PHYSICAL DEVICE |
| 15 | Slot released 292 ms after the `phase=CONNECTED` stop | PHYSICAL DEVICE |
| 16 | Slot-non-release root cause | **UNKNOWN** |
| 17 | The release is late rather than hung, and correlates with `phase=CONNECTED` | **INFERENCE** |
| 18 | Device timeout pref = 5 s → budget 25,000 ms via `×5` | PHYSICAL DEVICE + SOURCE CODE |
| 19 | No crash / ANR / process death; PID 15102 continuous | PHYSICAL DEVICE |
| 20 | Terminal is recoverable (next press connected in 4.2 s) | PHYSICAL DEVICE |
| 21 | D-2 exception path not exercised | PHYSICAL DEVICE (absent) |

---

## §12 — Negative assurance

- **No** source file modified. `git status --porcelain` still returns only `??` entries.
- **No** spec file modified. Spec remains `1.0.32`.
- **No** test file modified, added, or deleted.
- **No** commit, **no** push, **no** tag operation, `git add` not run at all (neither `-A` nor by path).
- **No** `--force` / `reset` / `clean` / `stash` / `checkout` / `restore`.
- **No** uninstall and **no** data clear on the device; `install -r` only.
- **No** device setting changed. The 5 s timeout pref was read and left as found; the
  battery-optimization dialog was dismissed with its "Later" / BACK path, never "Configure".
- **No** synthetic race injection, no thread manipulation, no debugger attach.
- **No** C5 execution.
- `D:\PMC\Documents\Android\SoftEthergai` was never touched.
- Only new artifacts this phase: `DEVICE_VERIFY_AUTOCONNECT_FIX_2_REPORT.md` (this file, untracked,
  not committed) and `test-evidence/dv2/` (screenshots + full logcat, untracked).
- `AUTOCONNECT_AUDIT_REPORT.md`, `AUTOCONNECT_AUDIT_REPORT_v2.md`, and
  `DEVICE_VERIFY_AUTOCONNECT_FIX_REPORT.md` remain untracked and untouched.

### Operational note (transparency)
Two mis-taps occurred while navigating the manual-connect UI and opened the system **App info**
page. No destructive control (Force stop / Uninstall / Clear data) was pressed; the page was left
with BACK. Recording it because it is exactly the screen where an error would cause data loss.

---

## §13 — Recommendations (proposed only; nothing was implemented)

1. **Instrument site 1695 as well** — it is the site that actually terminated the run here, and it
   is the NATIVE_BUSY path, i.e. the *most likely* place for the slot race to bite. The existing
   `SLOT_UNRELEASED_DIAG` body can be reused verbatim; only the call site changes. Until then, the
   single log line this phase most needed cannot be produced by the branch that matters.
2. **Re-run SLOT_DIAG after that change**, expecting `phase=CONNECTED` to appear together with
   `tracker=`, `ownTunnel=`, and `registry=`. The `phase=CONNECTED` + 292 ms-late-release pairing
   observed here is the concrete hypothesis to test: the registry's own phase says the native side
   believes it is up while the controller believes it is stuck.
3. **Decide the `nativeSlotAwaitMs` timing margin against real release latencies.** This phase
   measured releases at 6.0 s, 6.2 s, 7.5 s, 13.3 s, 13.7 s, 17.0 s, 20.9 s — two of seven very
   close to the 25,000 ms budget, and one release 292 ms past it. A budget of `5 × T` with the
   device's 5 s pref is thin.
4. **Re-verify S4 (D-2)** on a run where an exception actually occurs, or accept it as covered by
   JVM tests only.
5. **Consider whether the persisted 5 s pref is the intended shipped behaviour.** It is not a bug,
   but it currently makes device behaviour (25 s budget, 5 s attempts) diverge from the spec
   default (12 s, 60 s budget) in every device measurement taken so far.

---

*End of report. Untracked and deliberately not committed.*
