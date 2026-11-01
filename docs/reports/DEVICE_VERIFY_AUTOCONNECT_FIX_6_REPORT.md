# DEVICE-VERIFY-AUTOCONNECT-FIX-6 — Report (v1.0.36 on physical device)

**Repository:** `C:\Dev\SoftEthergai` · **Branch:** `main` · **Baseline:** `eacc5fad4fdb8e8b231349e87d0f35af578a3941`
**Device:** `5585fecc` — Redmi Note 11 (`2201117TG`, `spes`), Android 13 (SDK 33)
**Session:** 2026-10-19, 10:23–10:41 local · **Status:** untracked, NOT committed

---

## §1 Verdict

**CLASSIFICATION E — UNEXPECTED (adverse).** Two of the five runs terminated Auto Mode with
`ERROR_NATIVE_UNRESOLVED` on the **first server**, and the second terminal **excluded that server for
the session** on a premise the log itself disproves. The phase's success criterion 5 — *"AutoConnect
never stops for NATIVE_BUSY"* — **FAILS**.

The cap is live and correct as specified (`5000ms`, not `30000ms`, in two independent runs), and
Fix-1's code path was never reached because `ownTunnel=false` at every classification. The signal
pattern therefore matches the brief's **D** ("caps verified, Fix-1 not exercised"), but D's verdict —
*"Fix-2/Fix-3 VERIFIED"* — must **not** be read as a pass: on this device the cap **caused** the
terminal failure it was supposed to make rarer, by expiring the slot wait **8 seconds before the
native stack reached its own verdict**.

The decisive line, run 4 (10:37:23.857):

```
[AUTO] Native slot not released within 5000ms (attempt=4 phase=CONNECTING); stopping
```

and the native layer's own conclusion, 7.7 s **later** (10:37:31.558):

```
SoftEtherProtocol: Sending VPNCONNECT watermark (connect.cgi)...
SoftEtherProtocol: VPNCONNECT watermark failed — server may not be SoftEther
```

The controller gave up while the stack was still working. Evidence: PHYSICAL DEVICE.

---

## §2 Pre-conditions

| Check | Expected | Observed | Verdict |
|---|---|---|---|
| `HEAD` | `eacc5fa…` | `eacc5fad4fdb8e8b231349e87d0f35af578a3941` | PASS |
| `origin/main` | `eacc5fa…` | `eacc5fad4fdb8e8b231349e87d0f35af578a3941` | PASS |
| Tracked modifications | none | none | PASS |
| Spec version | `1.0.36` | `1.0.36` | PASS |
| Tag count | 5 | 5 | PASS |
| Disk free on C: | ≥ 1 GB | 4,097,740,800 B (3.8 GB) | PASS |
| `adb devices` | `5585fecc device` | `5585fecc device product:spes_global model:2201117TG` | PASS |
| `SETTING_AUTO_MODE_TIMEOUT_SECONDS` | `5` | `value="5"` | PASS (not changed) |

Device pref read verbatim from `shared_prefs/vpn_setting_data_pro.xml`:

```xml
<int name="SETTING_AUTO_MODE_TIMEOUT_SECONDS" value="5" />
```

Therefore `T = 5000 ms` and the expected cap is `min(30000, 5000) = 5000 ms`. Evidence: PHYSICAL DEVICE.

---

## §3 Install + smoke

| Step | Result |
|---|---|
| Working-tree APK SHA-256 | `BE345075A1F2E38D7ADD818B37072E27D0ED70CB0A00A4129035545AE4ECF249` (63,753,806 B) — **MATCH** |
| `adb install -r` | `Performing Streamed Install` → `Success` |
| `versionCode` | `143` (expected 143) |
| `versionName` | `2.4.10-devX` |
| Pull-back from `/data/app/…/base.apk` | 63,753,806 B, SHA-256 `BE345075A1F2E38D7ADD818B37072E27D0ED70CB0A00A4129035545AE4ECF249` — **identical to the built APK** |
| Launch (`monkey … LAUNCHER 1`) | `Events injected: 1`, PID **2563** |
| Crash scan after launch | no `FATAL` / `AndroidRuntime` / `ANR in` |

The pulled-back hash matching the built hash proves the binary under test is exactly the v1.0.36
artifact. Evidence: PHYSICAL DEVICE.

---

## §4 Per-run summary

File mapping (the two refused starts are kept separate from the five runs that actually started):

| File | What it is |
|---|---|
| `run01_capture.log`, `run01_live.log` | Run 1 |
| `run02_capture.log` | refused start attempt (10:28:11) |
| `run02b_trynext.log` | Run 2 (started via **Try next server**) |
| `run03_capture.log` | Run 3 |
| `run04_capture.log` | refused start attempt (10:34:11) |
| `run05_capture.log` | Run 4 |
| `run06_capture.log` | Run 5 |
| `crash_scan.log` | `logcat -b all` crash/ANR scan |

| Run | Started | Outcome | NATIVE_BUSY terminal | Fix-1 line | Wedge | Slot line |
|---|---|---|---|---|---|---|
| 1 | 10:24:16 | **FAILED** `ERROR_NATIVE_UNRESOLVED` | **yes** (attempt=1) | no | #1 on `vpn611507027` | `5000ms`, `waited=5042ms` |
| 2 | 10:30:44 | Connected (`vpn805442422`) in 5.6 s | no | no | none | none |
| 3 | 10:31:50 | Connected in 6.3 s → liveness lost 10:32:31 | no | no | none | none |
| 4 | 10:37:11 | **FAILED** `ERROR_NATIVE_UNRESOLVED` | **yes** (attempt=4) | no | #2 → **excluded** | `5000ms`, `waited=5048ms` |
| 5 | 10:39:56 | Connected (`vpn805442422`) in 6.5 s | no | no | skipped the excluded server | none |

Refused starts (not runs): 10:28:11 and 10:34:11, both
`[AUTO] start refused: a tunnel is already active` — and in both cases a `tun0` interface **was**
really up at the time (verified: `tun0 inet 10.211.1.40/16`), so the guard was **correct**; the
Auto Mode screen was the component showing the wrong state.

Evidence: PHYSICAL DEVICE.

---

## §5 Fix-1 evidence

**Zero occurrences.** No run produced:

```
[AUTO] Tunnel came up despite NATIVE_BUSY
```

Verified across all eight capture files. In both terminals the diagnostic recorded
`ownTunnel=false`, so the new early-exit was correctly not taken:

```
SLOT_UNRELEASED_DIAG waited=5042ms budget=5000ms outcome=NATIVE_BUSY protocol=SOFTETHER_TCP
server=vpn611507027 tracker=CONNECTING ownTunnel=false registry=attempt=1 phase=CONNECTING
```

**Fix-1 is UNTESTABLE in this session, and more importantly it is INSUFFICIENT for the failure that
actually occurred.** The observed race is not "the tunnel came up late" but "the attempt timed out
while the *same* attempt's native operation was still running". At classification time the tracker is
`CONNECTING`, not `CONNECTED`, so `isOwnTunnelEstablished()` correctly answers `false` and Fix-1
cannot fire. Evidence: PHYSICAL DEVICE + SOURCE CODE.

---

## §6 The bug: `Destroying connection in state: CONNECTED`

**Absent.** No run produced `Destroying connection in state: CONNECTED`. The v1.0.34 unfiltered
failure (a *live, verified* tunnel torn down) did **not** reproduce in these five runs.

That is not evidence that Fix-1 works — Fix-1 never executed (§5). It means the specific
`ownTunnel=true` race did not occur in this session's timing.

A related but **different** symptom did occur: `Destroying connection in state: DISCONNECTED` appears
in the teardown of both terminals, which is the normal cleanup path, not the bug.

Evidence: PHYSICAL DEVICE.

---

## §7 Fix-2 / Fix-3 caps

**The cap is live and exactly as specified.** Two independent terminals:

```
10-19 10:24:28.509  AutoMode: [AUTO] Native slot not released within 5000ms (attempt=1 phase=CONNECTING); stopping
10-19 10:24:28.511  AutoMode: [AUTO] SLOT_UNRELEASED_DIAG waited=5042ms budget=5000ms outcome=NATIVE_BUSY
                              protocol=SOFTETHER_TCP server=vpn611507027 tracker=CONNECTING ownTunnel=false
                              registry=attempt=1 phase=CONNECTING

10-19 10:37:23.857  AutoMode: [AUTO] Native slot not released within 5000ms (attempt=4 phase=CONNECTING); stopping
10-19 10:37:23.859  AutoMode: [AUTO] SLOT_UNRELEASED_DIAG waited=5048ms budget=5000ms outcome=NATIVE_BUSY
                              protocol=SOFTETHER_TCP server=vpn611507027 tracker=CONNECTING ownTunnel=false
                              registry=attempt=4 phase=CONNECTING
```

`5000ms` — not `30000ms`. Fix-2 and Fix-3 therefore behave as written. Evidence: PHYSICAL DEVICE.

### §7.1 The cap's adverse effect (the finding of this phase)

The cap is correct with respect to the *contract* and wrong with respect to the *native lifetime it
is waiting for*. The slot is held by a native connect ladder whose own duration is several multiples
of the 4000 ms socket timeout, not one `T`. Run 4, verbatim, around the terminal:

```
10:37:13.565  [AUTO] Trying #1 vpn611507027
10:37:13.839  SoftEtherJNI: Set timeout to 4000 ms
10:37:13.847  ConnectionController: [DIAG] nativeConnectWithHub entry (attempt=4, …)
10:37:17.855  SoftEtherProtocol: TCP+TLS failed (1); falling back to parallel UDP race
10:37:23.426  SoftEtherVpnService: State changed: CONNECTING          ← a NEW native connect, 431 ms before the terminal
10:37:23.857  [AUTO] Native slot not released within 5000ms (attempt=4 phase=CONNECTING); stopping
10:37:27.445  SoftEtherSocket: Connection timeout                     ← the stack still working, 3.6 s AFTER the terminal
10:37:27.451  SoftEtherProtocol: Parallel race: 3 transports, race timeout 4000 ms
10:37:31.558  SoftEtherProtocol: VPNCONNECT watermark failed — server may not be SoftEther
```

The controller declared the slot "stuck" 431 ms after a native attempt had *just started*, and 7.7 s
before the stack produced its own verdict about the endpoint. With the pre-v1.0.36 30 s budget the
same run would have waited for the slot to free at ~10:37:31 and then rotated normally.

This is the exact failure mode v1.0.33 documented and fixed with the 30 s floor
(`PROJECT_SPECIFICATION.md` §6.8.7: *"A wait shorter than that reports 'stuck' on an operation that
is still working normally — which is exactly the 2026-10-17 failure, where the run was abandoned
27 ms before the native operation returned on its own."*). Fix-2 removes that floor for every device
whose `T` is below 30 s — which is this device (`T = 5 s`).

Evidence: PHYSICAL DEVICE + SOURCE CODE.

---

## §8 Wedge false-positives

**One confirmed false premise, one exclusion.** The NATIVE_BUSY terminal records a wedge with the
message *"the endpoint itself never accepted the native attempt"*:

```
10-19 10:24:28.509  [AUTO] Recording a wedge on the NATIVE_BUSY terminal for vpn611507027
                            (the endpoint itself never accepted the native attempt)
10-19 10:37:23.856  [AUTO] Recording a wedge on the NATIVE_BUSY terminal for vpn611507027
                            (the endpoint itself never accepted the native attempt)
10-19 10:37:23.857  [AUTO] Excluding vpn611507027 from this session after 2 wedges.
```

The premise is **demonstrably false**: at 10:37:13.847 the app logged
`nativeConnectWithHub called (useTcp=1)` and `Connecting to 219.62.85.155:1309`, i.e. the endpoint
*was* being contacted — the attempt was simply still in flight when the cap expired.

Strictly, the brief's criterion 4 ("no `Recording a wedge on the NATIVE_BUSY terminal` for a server
that reached DHCP SUCCESS") is **not violated**: no DHCP SUCCESS occurred for `vpn611507027` in any
run. But the intent behind criterion 4 — do not wedge a server that was never given a fair chance —
**is** violated, twice, and the second one produced a session-wide exclusion that changed the next
run's server list (`Skipping vpn611507027: excluded for this session after 2 wedges`).

Whether `vpn611507027` is *actually* unhealthy is **UNKNOWN**: the stack's own verdict at
10:37:31.558 was `VPNCONNECT watermark failed — server may not be SoftEther`, which suggests it
probably is a bad endpoint. The defect is not the conclusion but the **route and the timing**: the
correct verdict (`NATIVE_ERROR`, cheap, no wedge) existed and arrived 7.7 s after the controller had
already failed the run and written a strike against the endpoint. Evidence: PHYSICAL DEVICE.

---

## §9 Crash / ANR scan

```
adb logcat -d -b all -v threadtime   →  59,742 lines scanned
FATAL EXCEPTION  : none
ANR in           : none
Force finishing  : none
```

Process PID **2563** at STEP 3 and **2563** at the end of the session — no process death, no restart.
The app was never killed, force-stopped, uninstalled or data-cleared during the session.
Evidence: PHYSICAL DEVICE.

---

## §10 What was NOT testable, and why

1. **Fix-1 (`ownTunnel=true`) — UNTESTABLE this session.** Zero occurrences in five runs. The race it
   targets requires the `CONNECTED` notification to land after the attempt timeout; this session's
   failures were all the *other* branch (`tracker=CONNECTING`, tunnel not yet up). Fix-1 is
   **neither confirmed nor refuted** by this run.
2. **The v1.0.34 bug (`Destroying connection in state: CONNECTED`) — NOT REPRODUCED.** Absent in all
   five runs, so the specific harm Fix-1 was written for did not occur here.
3. **Fix-A (native RUDP bound) — still UNVERIFIED.** The NAT-T rendezvous failed with `error=6`
   (`[NAT-T] rendezvous failed: error=6`) exactly as in the unfiltered v1.0.34 run, so the
   `[TLS] handshake entry (RUDP)` site was never reached.
4. **A 30 s-budget control run was not performed.** The brief forbids settings changes, so the
   pre-v1.0.36 behaviour could not be measured side by side. The comparison in §7.1 is **INFERENCE**
   from the recorded native timeline, not a measured control.

---

## §11 Evidence labels per claim

| Claim | Label |
|---|---|
| APK under test == v1.0.36 build | PHYSICAL DEVICE (pull-back hash identical) |
| Pref `T = 5 s` | PHYSICAL DEVICE (shared_prefs read) |
| Cap emits `5000ms` | PHYSICAL DEVICE (two independent terminals) |
| Fix-1 never executed | PHYSICAL DEVICE (`ownTunnel=false` in both DIAGs) |
| `Destroying connection in state: CONNECTED` absent | PHYSICAL DEVICE |
| Server excluded after 2 wedges | PHYSICAL DEVICE (`Excluding … after 2 wedges`) |
| Wedge premise false (endpoint was contacted) | PHYSICAL DEVICE (`nativeConnectWithHub called`) |
| Stack's own verdict arrived 7.7 s later | PHYSICAL DEVICE (`VPNCONNECT watermark failed`) |
| 30 s budget would have covered the ladder | **INFERENCE** (not measured; no control run allowed) |
| `vpn611507027` is genuinely unhealthy | **UNKNOWN** (stack suggests yes; not independently probed) |
| Fix-1 would or would not have prevented run 1/4 | **UNKNOWN** (it never had the chance to run) |
| NATIVE_BUSY misclassification mechanism | SOURCE CODE (`AndroidConnectionAdapter.awaitArmedOutcome:452-468`) |
| No source/spec/test file changed | SOURCE CODE (`git status` at end of session) |

The misclassification itself is worth stating precisely, because it is the root of the whole phase's
outcome. `awaitArmedOutcome` (`AndroidConnectionAdapter.kt:452`) does:

```kotlin
val up = awaitArmedTunnel(protocol, timeoutMs)      // waits T
if (up) return TUNNEL_UP
if (lastArmedFailure) return NATIVE_ERROR
return if (isNativeSlotBusy()) NATIVE_BUSY else TIMED_OUT
```

The `isNativeSlotBusy()` re-check runs **after** a normal timeout, so it cannot distinguish
(a) *the start was refused because a previous operation holds the slot* from
(b) *our own attempt just timed out and is still unwinding*. Case (b) is what happened twice, and it
is the case in which "wait for the slot" means "wait for ourselves".

---

## §12 Negative assurance

- No uninstall, no data clear, no settings change (`SETTING_AUTO_MODE_TIMEOUT_SECONDS` still `5`).
- No synthetic race injection, no thread manipulation, no forced scenarios.
- No build was run in this phase; the APK was the one produced by the v1.0.36 phase and was
  hash-verified before install and after pull-back.
- No source, test, spec or report file was modified. `git status` shows only the pre-existing
  untracked directories plus `test-evidence/dv6/` and this report.
- No `git add`, no commit, no push, no tag operation, no `--force`/`reset`/`clean`/`stash`.
- `D:\PMC\Documents\Android\SoftEthergai` was not touched.
- The only device interactions were: install, launch, screenshot, `input tap`, `logcat`, `pidof`,
  `ip addr`, and one `pm path` + `pull`.
- The app was left **Connected** (`vpn805442422`) at the end of the session, which is the state the
  user's device was in before the phase.

---

## §13 Recommendation (proposed only — no action taken)

The evidence points at **Fix-2**, not Fix-1, as the change that needs revisiting:

1. **Do not treat the cap as a success.** It is implemented as specified, but on this device
   (`T = 5 s`) it expires the slot wait while the native ladder is still running, converting a
   recoverable slow endpoint into a whole-run `ERROR_NATIVE_UNRESOLVED`.
2. **The cap needs a floor that is independent of `T`** — the v1.0.33 reasoning is unchanged: the
   slot is held for the duration of a *native operation*, which is bounded by several socket
   timeouts (4000 ms each) plus the UDP-race ladder, not by the user's attempt budget. A candidate is
   `max(min(nativeSlotAwaitMs, T), NATIVE_SLOT_AWAIT_MS)` — i.e. no cap at all below the floor — but
   this is a design decision for a future phase, not something to patch now.
3. **Fix-1 is still worth keeping** — it is inert, not wrong — but it does not address the failure
   observed here, because at classification time the tunnel is `CONNECTING`, not `CONNECTED`.
4. **The wedge on a NATIVE_BUSY terminal deserves a second look** in the `tracker=CONNECTING,
   ownTunnel=false` case: the endpoint was actively being contacted, so the recorded rationale
   ("never accepted the native attempt") is false, and two such terminals excluded a server for the
   session.
5. **A device run is still owed for Fix-1's actual target** (the `ownTunnel=true` race). This session
   did not reproduce it.

No next phase is proposed and nothing was changed.
