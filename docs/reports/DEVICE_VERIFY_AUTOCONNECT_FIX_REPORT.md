# DEVICE-VERIFY-AUTOCONNECT-FIX — Device Verification Report (v1.0.31)

**Phase:** DEVICE-VERIFY-AUTOCONNECT-FIX-v1.0.32
**Date:** 2026-10-18
**Repo:** C:\Dev\SoftEthergai
**HEAD:** 9d9e43effa5b8adabd006520393aaa2dfedd7a0a
**origin/main:** 9d9e43effa5b8adabd006520393aaa2dfedd7a0a
**Spec:** 1.0.31
**Status:** UNTRACKED — NOT COMMITTED

---

## §1 Verdict

**PARTIAL — 2 of 5 device-testable items PASS; 2 GAPS found; 1 UNTESTABLE.**

| # | Item | Verdict |
|---|------|---------|
| S1 | D-1: UI shows 12 s default | **DEVIATION (not a code defect)** — device shows 5 s from a stored preference |
| S2 | D-5 / OP-R6: Connected press → next server | **PASS** |
| S3 | D-4: manual tunnel active → refuse | **GAP — FAIL** |
| S4 | D-2: exception → next protocol | **PASS (incidental evidence)** |
| S5 | R3: per-server duration | **GAP — run does not rotate at all** |
| S6 | D-5 edge: last-server press → ERROR_NO_SERVER | **UNTESTABLE** |

Two independent defects were observed on the physical device, both in the
AutoConnect rotation path. Neither is an exception in `connect()`, and neither
is caused by the D-4 test setup.

---

## §2 Pre-conditions

| Check | Expected | Actual | Result |
|-------|----------|--------|--------|
| HEAD | == origin/main | 9d9e43e (both) | PASS |
| Branch | main | main | PASS |
| Tracked modifications | none | none | PASS |
| Spec version | 1.0.31 | 1.0.31 | PASS |
| Device | 5585fecc `device` | 5585fecc `device` | PASS |
| APK SHA-256 | 1CCFC62E…3F1D | `1ccfc62ea723e4696005c6f6f7ff07f544d657df6a5f738233b8e07986a03f1d` | PASS |
| versionCode | 143 | 143 | PASS |

**Device identity** (PHYSICAL DEVICE, `getprop` / `dumpsys`):
- Serial: `5585fecc`
- Model: `2201117TG` (product `spes_global`, device `spes`) — Redmi Note 11
- Android: `wm size` 1080×2400, density 440
- Install: `adb install -r` → `Success` (no uninstall, no data clear)

**Smoke** (PHYSICAL DEVICE): `monkey -c LAUNCHER 1` → launched; `pidof` = 4684;
no `FATAL EXCEPTION`, no `ANR in`, no tombstone/backtrace. Process 4684 stayed
alive for the entire session (never restarted).

---

## §3 Scenario 1 — D-1: UI shows 12 s default

**Result: DEVIATION — no code defect. Label: PHYSICAL DEVICE + SOURCE CODE.**

Settings → *Auto Mode Connection Timeout* rendered **"5 s"**, not "12 s".

Root cause is a **stored user preference**, not the code default:

- SOURCE CODE — `DataUtil.getAutoModeTimeoutSeconds()`
  (`app/src/main/java/vn/unlimit/vpngate/utils/DataUtil.kt:332-333`):
  `getIntSetting(SETTING_AUTO_MODE_TIMEOUT_SECONDS, 12).coerceIn(5, 60)` → default **12**.
- PHYSICAL DEVICE — the persisted value on this device is 5:
  `shared_prefs/vpn_setting_data_pro.xml` contains
  `<int name="SETTING_AUTO_MODE_TIMEOUT_SECONDS" value="5" />`.
- PHYSICAL DEVICE — the runtime log confirms the stored value is what is used:
  `[AUTO] Connection timeout = 5 seconds`.

Because a pre-existing user value is present, a fresh-install default could not
be observed non-destructively (clearing app data is forbidden by this phase).
The slider was **not changed** — not by the operator, and not by the assistant.

**Conclusion:** D-1's code default (12 s) is consistent with the source, but the
12 s default **was not demonstrable on this device**. Recorded as DEVIATION,
not as PASS.

---

## §4 Scenario 2 — D-5 / OP-R6: Connected press → next server

**Result: PASS. Label: PHYSICAL DEVICE.**

Setup: AutoConnect reached `Connected` on `vpn275348493`, which was server **#3**
of 88 (`Trying #3 vpn275348493` preceded it). The button was then pressed once
while Connected.

Log (PHYSICAL DEVICE, `logcat -s AutoMode:V`, 16:11:27–16:11:33):

```
16:11:27.540 [AUTO] Started
16:11:27.541 [AUTO] Protocols (priority) = SOFTETHER_TCP > SOFTETHER_UDP
16:11:27.541 [AUTO] Connection timeout = 5 seconds
16:11:27.567 [AUTO] Servers available = 109
16:11:29.162 [AUTO] Reachable servers selected: 88
16:11:29.163 [AUTO] Continuing from server #4 of 88      <-- OP-R6 in effect
16:11:29.163 [AUTO] Trying #1 vpn636797683
16:11:33.082 [AUTO] Tunnel established
16:11:33.082 [AUTO] Connected successfully
```

The press resumed at **#4** (= #3 + 1), not #1 — the pre-v1.0.31 behaviour. The
teardown of #3 and the new run's first dial are ordered correctly. The run then
connected to `vpn636797683`.

A second Connected press (16:12:53) produced `Continuing from server #5 of 88` —
the progression #4 → #5 is consistent.

**Note on the two counters (SOURCE CODE, not a defect).**
`Trying #1` appears even though the run continued at #4. These are two different
numbers: `[AUTO] Continuing from server #N` reports the **absolute list position**
(`AutoModeController.kt:1449`), while `[AUTO] Trying #n` is the **per-run attempt
counter** (`attempt++`, `AutoModeController.kt:1492-1493`). Both are as designed;
`currentServerIndex` remains absolute (`currentServerIndex = i`, line ~1479).

---

## §5 Scenario 3 — D-4: manual tunnel active → refuse

**Result: GAP — FAIL. Label: PHYSICAL DEVICE + SOURCE CODE.**

Setup (PHYSICAL DEVICE): a manual, non-Auto-Mode tunnel was established to
`public-vpn-199.opengw.net` (219.100.37.196) over OpenVPN-TCP. The app's own log
recorded `Connected: SUCCESS,10.245.217.133,219.100.37.196,443,...` and
`Initialization Sequence Completed`. The Auto Mode tab showed `Disconnected`.

Press #1 (16:23:20) — Auto Mode Disconnected, manual tunnel up:

```
16:23:22.005 [AUTO] Started
16:23:22.006 [AUTO] Connection timeout = 5 seconds
16:23:23.955 [AUTO] Trying #1 vpn131251792
```

Expected `[AUTO] start refused: a tunnel is already active` — **not emitted**; a
full run started instead. Press #2 (16:24:24) reproduced it identically.

**Root cause (SOURCE CODE).** The guard is wired, but its predicate cannot see
an established tunnel:

- `AutoModeController.kt:1015-1024` — the v1.0.31 D-4 guard is present in
  `onButtonPressedWithoutGuard()` → `if (isTunnelBusy()) { log("start refused"); return }`.
- `AutoModeEngine.kt:116` — `isTunnelBusy = { GlobalVpnTracker.isBusy }`.
- `GlobalVpnTracker.kt:47` — `val isBusy get() = this == CONNECTING || this == DISCONNECTING`.

`VpnConnectionStatus.CONNECTED` is **not** part of `isBusy`. So for a manual
tunnel that has finished connecting — the exact case D-4 targets — the guard
evaluates to `false` and the run proceeds. The guard can only ever fire while
the manual tunnel is still *coming up*.

**Observed side effect (PHYSICAL DEVICE):** the manual OpenVPN tunnel was torn
down by the AutoConnect run. Afterwards `ip addr` showed no `tun*` interface,
`OpenVPNService` was still `startRequested=true` but `lastActivity=-1m25s`, and
no VPN interface existed. i.e. the double-ownership this fix was meant to
prevent still occurs, and the user's manual session is destroyed.

---

## §6 Scenario 4 — D-2: exception → next protocol

**Result: PASS (incidental, observed during Scenario 2's earlier run).
Label: PHYSICAL DEVICE.**

No exception in `connect()` could be forced without changing device settings
(forbidden), so this was **not** reproducibly driven. However, an earlier run in
this session produced direct evidence of the D-2 transition shape — the
same-server retry onto a sibling protocol after the first protocol failed:

```
16:08:41.232 [AUTO] Native slot released; retrying SOFTETHER_UDP on vpn131251792
16:08:41.331 [AUTO] Attempt after the slot was released did not come up (NATIVE_ERROR); moving on
16:08:41.332 [AUTO] Protocol failed: SOFTETHER_UDP
16:08:46.955 [AUTO] Connection failed: vpn131251792
16:08:46.955 [AUTO] Trying #2 vpn752839599
```

and, on server #2:

```
16:09:00.025 [AUTO] Native slot released; retrying SOFTETHER_TCP on vpn752839599
16:09:00.110 [AUTO] Protocol failed: SOFTETHER_TCP
16:09:25.375 [AUTO] Teardown confirmed complete (teardown#8(session=1, NATIVE_CONFIRMED))
16:09:25.375 [AUTO] Connection failed: vpn752839599
16:09:25.375 [AUTO] Trying #3 vpn275348493
```

Protocol rotation within a server, then server rotation — the correct nesting.
This is *not* a `connect()` exception, so D-2's specific `catch`→`continue` path
is **not** device-verified; the JVM test (T-NEW-5) remains the only direct
evidence for it.

---

## §7 Scenario 5 — R3: real per-server duration

**Result: GAP — rotation does not occur; measurement not meaningful.
Label: PHYSICAL DEVICE.**

Three clean runs (no manual tunnel, Auto Mode started from `Disconnected` /
`Error`) all ended identically, each within ~30 s, on the **same first server**:

```
16:31:42.157 [AUTO] Started   (timeout = 5 seconds)
16:31:44.178 [AUTO] Trying #1 vpn131251792
16:32:14.531 [AUTO] Native slot not released within 25000ms (attempt=14 phase=CONNECTED); stopping
16:32:14.531 [AUTO] Requesting recovery teardown so the unresolved native operation can be released...
```

(a repeat at 16:36:28 shows the same shape with `attempt=15`).

The stack **did** bring the tunnel up in the native layer — `DHCP success:
IP=10.211.1.130/16 GW=10.211.254.254` at 16:31:51 — yet the controller never
logged `Tunnel established` and instead blocked on the process-wide native slot
for the full `nativeSlotAwaitMs` (5 × T = 25 000 ms), then terminated the whole
run with `ERROR_NATIVE_UNRESOLVED`.

Measured: **≈30 s per run, 0 server rotations.** The expected nominal
(2 × 5 = 10 s per server, then `Trying #2`) was **not** observed. Per-server
duration is therefore **not measurable** in this build on this device.

**Root cause (SOURCE CODE).**
`AutoModeController.kt:1713-1732` — after any non-`NATIVE_ERROR` outcome, the
loop waits `awaitNativeSlotRelease(nativeSlotAwaitMs)` and, on expiry, calls
`giveUpWithUnresolvedNativeOperation()` + `Error(ERROR_NATIVE_UNRESOLVED)` +
`return` — a **terminal for the whole run**, not a rotation. The commented intent
("Rotation (next protocol, then next server) is the documented recovery", line
~1600) is contradicted by this branch: the run dies instead of rotating.

**Second defect exposed by the same evidence (SOURCE CODE).**
`wedgedThisServer` / `recordWedge` is only invoked on `AttemptOutcome.TIMED_OUT`
(line ~1691). A server that repeatedly returns `NATIVE_BUSY` and then exhausts
the slot budget is **never recorded as wedged**. Across this session
`vpn131251792` produced `attempt=12`, `13`, `14`, `15` and was never excluded —
the log contains no `Skipping …: excluded for this session` line. The run
therefore keeps re-selecting the same hostile endpoint and can never reach #2.
This is consistent with the `ERROR_NO_SERVER`-style dead end the v1.0.30 audit
(Report 1/2) identified, and it is **not** resolved by v1.0.31.

---

## §8 Scenario 6 — D-5 edge: last-server press → ERROR_NO_SERVER

**Result: UNTESTABLE. Label: PHYSICAL DEVICE (blocked by §7).**

The edge case requires reaching `Connected` on the **last** server of the 88-item
filtered list. Two obstacles made this unattainable without operator-controlled
device changes:

1. After the first successful connection (Scenario 2), every subsequent run stalled
   on `vpn131251792` (§7) and never rotated, so no connected state could be reached
   again.
2. Driving through 88 servers to the last one is not feasible in a bounded session.

Consequently the documented behaviour — `resumeFromIndex = lastIndex + 1` ⇒
`startAt >= servers.size` ⇒ immediate `Error(ERROR_NO_SERVER)` with no attempt on
index 0 — was **not** observed on the device. It remains supported only by
SOURCE CODE (`AutoModeController.kt:1448-1453`: `coerceIn(0, servers.size)` then
`for (i in startAt until servers.size)`) and by the JVM tests T-NEW-4 / T-NEW-6.

The related **reset** semantics did get partial device support: after
`Error` (16:32:14), a press from `Error` started a fresh run at `Trying #1` with
**no** `Continuing from server #` line — i.e. `resumeFromIndex` was 0
(`AutoModeController.kt:1023`, `resumeFromIndex = 0` on the fresh-start branch).

---

## §9 Crash / ANR scan

**Result: CLEAN. Label: PHYSICAL DEVICE.**

`logcat -d` over the whole session (~2 467 lines retained):

- `FATAL EXCEPTION` — **0**
- `ANR in` — **0**
- native tombstone / `backtrace:` / `SIGSEGV` — **0**
- `pidof vn.unlimit.vpngatepro` = **4684** at start and at end — no process restart

No scenario produced a crash, ANR, or process death. The `Error` states in §5 and
§7 are **handled** error paths, not failures of the app process.

---

## §10 What was NOT testable and why

| Item | Reason |
|------|--------|
| D-1 fresh-install default (12 s) | Device already had `SETTING_AUTO_MODE_TIMEOUT_SECONDS=5`; clearing app data is forbidden by the phase brief. Not changed. |
| D-2 `connect()` exception path | Cannot be induced without altering device/network settings (forbidden). Incidental protocol-rotation evidence only (§6). |
| S6 last-server edge | Requires connected state on the last of 88 servers; blocked by the §7 rotation defect. |
| R3 exact per-server duration | Rotation never happens (§7); only a single ~30 s stall is measurable. |

No device setting was changed to make any scenario reproducible.

---

## §11 Evidence labels per claim

| Claim | Label |
|-------|-------|
| Device model/serial/Android/versionCode 143 | PHYSICAL DEVICE (`getprop`, `dumpsys package`) |
| APK SHA-256 = 1CCFC62E… | PHYSICAL DEVICE + local `sha256sum` |
| UI shows "5 s" | PHYSICAL DEVICE (screenshot `s1_settings.png`) |
| Stored pref value = 5 | PHYSICAL DEVICE (`run-as … cat shared_prefs`) |
| Code default = 12, clamp [5,60] | SOURCE CODE (`DataUtil.kt:332-333`) |
| OP-R6 resumes at #4 then #5 | PHYSICAL DEVICE (`logcat -s AutoMode:V`) |
| Absolute index vs per-run counter | SOURCE CODE (`AutoModeController.kt:1449`, `1492`, `~1479`) |
| D-4 refusal not logged; run started | PHYSICAL DEVICE |
| `isBusy` excludes CONNECTED | SOURCE CODE (`GlobalVpnTracker.kt:47`, `AutoModeEngine.kt:116`, `AutoModeController.kt:1015`) |
| Manual tunnel torn down by AutoConnect | PHYSICAL DEVICE (`ip addr`, `dumpsys activity services`) |
| Native slot wait → whole-run terminal | SOURCE CODE (`AutoModeController.kt:1713-1732`) |
| Wedge never recorded for NATIVE_BUSY | SOURCE CODE (`AutoModeController.kt:1691`) + PHYSICAL DEVICE (no exclusion log across 4 runs) |
| No crash/ANR/restart | PHYSICAL DEVICE (`logcat`) |
| Rotation "should" be the recovery | SOURCE CODE comment (`AutoModeController.kt` ~1600) — intent, not behaviour |
| Root cause of slot non-release | **UNKNOWN** — the native layer reported `DHCP success` yet the slot was not released within 25 s; not diagnosed in this phase (no source edits allowed). |

---

## §12 Negative assurance

- No `uninstall`, no `pm clear`, no data wipe — only `install -r`.
- No device setting changed (timeout stayed at 5 s; protocol priority untouched;
  no Wi-Fi/VPN/proxy/captive-portal change).
- No synthetic race injection, no thread manipulation, no `run-as` writes
  (`run-as` used for **reading** the prefs XML only).
- No source, test, or spec file modified (`git status --porcelain` shows no
  tracked modification).
- No commit, no push, no tag, no `--force`, no `reset`/`clean`/`stash`/
  `checkout`/`restore`.
- No `git add -A` / `git add .`.
- Two audit reports (`AUTOCONNECT_AUDIT_REPORT.md`,
  `AUTOCONNECT_AUDIT_REPORT_v2.md`) remain **untracked and unmodified**.
- C5 was **not** executed.
- `D:\PMC\Documents\Android\SoftEthergai` was **not** touched.
- Temporary UI-dump files created during the session were removed; working tree
  has no new tracked or untracked artifacts other than this report.

---

## §13 Recommendations (proposed only — NOT executed)

1. **D-4 predicate.** Either extend `GlobalVpnTracker` with a
   `connectedOrBusy` accessor (or have `AutoModeEngine` pass
   `{ GlobalVpnTracker.isBusy || status == CONNECTED }`), or read
   `currentStatus == CONNECTED` in the guard. Without this, D-4 cannot fire for
   the case it documents. Unit test T-NEW-2 injects a fake `isTunnelBusy`, which
   is why it passed while the device failed — the test validates the guard's
   *wiring*, not its *production predicate*. Worth an explicit test of the
   predicate.
2. **Slot-wait branch.** Decide whether slot-budget exhaustion should be a
   whole-run terminal (current code) or a per-protocol/per-server rotation, and
   make code and the §6.8 text agree. The current state is: the comment promises
   rotation, the code terminates.
3. **Wedge accounting.** Record a wedge when a server produces repeated
   `NATIVE_BUSY` on the same server, so a hostile endpoint can be excluded and
   the run can reach #2. Otherwise the run re-selects the same server every time.
4. **Native slot non-release.** Needs its own investigation: DHCP completed but
   the process-wide slot stayed held for >25 s. This is the single largest
   practical blocker observed.
5. **Re-run S1 / S6** after (1) and (3) are fixed, on a device where the timeout
   pref is genuinely at its 12 s default.

STOP.
