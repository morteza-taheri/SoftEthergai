# DEVICE-VERIFY-AND-CAPTURE-v1.0.41 — Report

**Repository:** `C:\Dev\SoftEthergai` · **Branch:** `main` · **Build under test:** `02765a6` (spec v1.0.40)
**Device:** `5585fecc` — Xiaomi `2201117TG` (Redmi Note 11, `spes`), **Android 13**, 1080×2400
**Installed APK:** `versionCode=144 versionName=2.4.11-dev` (the standalone gate APK built from
`origin/main` in the previous phase; SHA-256 `20387536…9702`)
**Date:** 2026-10-20 · **Phase type:** physical device, read-mostly; **no repository source file changed**

Labels: `SOURCE CODE` / `TEST` / `PHYSICAL DEVICE` / `INFERENCE` / `UNKNOWN` / `PRIOR REPORT`.

---

## §1 Verdict

| Test | Claim under test | Result |
|------|------------------|--------|
| **A** | manual connect → Auto Mode shows "Connected (manual)"; tap disconnects; **no D-4 refusal** | **PASS** — all three parts observed |
| **B** | 3 consecutive AutoConnect presses (no Configure) → battery dialog appears **once** | **PASS** — 1 dialog in 3 presses; flag persisted |
| **C** | capture `Sources:` + per-server `sstp=` → decide data gap vs filter bug | **CAPTURED — and the premise is wrong**: neither a data gap nor a filter bug. MS-SSTP was **disabled** in the priority list; once enabled it **is** attempted, and every attempt **times out** |
| **D** | stale `Error` + live tunnel → no "Tap to retry" | **NOT OBSERVED on device** — the `Error` state could not be produced in reasonable time (see §6). Covered by TEST only |

The most consequential outcome is **C**: BUG-2 as reported ("MS_SSTP never attempted") is explained by
**configuration**, and what remains is a different, narrower problem — MS-SSTP attempts fail by timeout
on the servers Auto Mode reaches, while the same protocol connects manually to at least one server.
Evidence: PHYSICAL DEVICE.

---

## §2 Pre-conditions and environment

| Item | Value | Evidence |
|------|-------|----------|
| Device online | `5585fecc device product:spes_global model:2201117TG` | PHYSICAL DEVICE |
| Android version | 13 | PHYSICAL DEVICE |
| Pre-existing install | `versionCode=143 versionName=2.4.10-dev` (2026-10-19) — **not** the v1.0.40 build | PHYSICAL DEVICE |
| Action | `adb install -r` of the gate APK → `144 / 2.4.11-dev`; app data **preserved** (102 servers kept) | PHYSICAL DEVICE |
| Battery exemption (before) | `user,vn.unlimit.vpngatepro,10340` — **whitelisted**, so `isIgnoringBatteryOptimizations` was `true` | PHYSICAL DEVICE |
| Action for Test B | `dumpsys deviceidle whitelist -vn.unlimit.vpngatepro` → removed; restored at the end | PHYSICAL DEVICE |
| Battery flag (before Test B) | absent from `vpn_setting_data_pro.xml` (`NO_BATTERY_KEY`) | PHYSICAL DEVICE |
| Notification permission | `POST_NOTIFICATION: allow` — so press 1 reaches the battery gate, not the permission prompt | PHYSICAL DEVICE |
| Developer Mode | off at first; enabled for Test C (`SETTING_DEVELOPER_MODE=true`), because `CollectorLog.enabled` is set from it at `App.onCreate` | SOURCE CODE + PHYSICAL DEVICE |

Two pre-conditions had to be corrected before any test was meaningful: the installed build was a
**pre-v1.0.40** APK, and the app was **battery-exempt** (Test B's dialog could never have appeared).
Evidence: PHYSICAL DEVICE.

---

## §3 Test A — BUG-1 (button state vs tunnel state): PASS

Sequence, all on device:

1. **Manual connect** — Server List → `public-vpn-189.opengw.net` (219.100.37.180) → "Connect to this
   server" → protocol picker → **SoftEther SSL-VPN over TCP**. This path never touches
   `AutoModeState`; it is the exact scenario of BUG-1.
   Result: `tun0` carries `inet 10.244.189.2/16`; the detail screen reads
   `Connected SoftEther with IP: 10.244.189.2`.
2. **Open Auto Mode** — the hero button reads:

   | Element | Text |
   |---------|------|
   | title | **Connected** |
   | subtitle | **Connected (manual)** |

   "Try next server" renders **disabled**, because it still reads `AutoModeState` (which is
   `Disconnected`) — the intended split between display state and run state.
3. **Tap the button** — result: `Disconnected` / `Tap to connect`; `tun0` loses its address.
4. **D-4 refusal check** — the filtered logcat capture contains:

   ```
   08:36:22.399 D/GlobalVpnTracker: System VPN network available: 108
   08:37:19.744 D/GlobalVpnTracker: System VPN network lost: 108
   08:37:19.855 D/AutoMode: [AUTO] Re-entrant teardown completion observed;
                native confirmation expected shortly (teardown#1(session=1, NOOP_REENTRANT))
   ```

   `grep -c "start refused"` over the whole capture → **0**.

**Before the fix** this state rendered "Disconnected / Tap to connect" and the tap hit
`[AUTO] start refused: a tunnel is already active`. Both halves are now the opposite. Evidence:
PHYSICAL DEVICE.

---

## §4 Test B — BUG-3 (battery prompt once): PASS

Three consecutive presses of the hero button, **without** ever pressing "Configure":

| Press | Path taken | Battery dialog? | Evidence |
|-------|-----------|-----------------|----------|
| 1 | start path → notification granted → battery gate | **YES** (title "Background Battery Optimization", buttons "Later" / "Configure") | PHYSICAL DEVICE |
| — | tapped **"Later"** | flag written: `SETTING_BATTERY_OPT_ASKED = true` | PHYSICAL DEVICE |
| 2 | run was `Connecting…`, so this press cancelled it → `Disconnected` | **NO** | PHYSICAL DEVICE |
| 3 | start path again — reached `Connecting… / Attempting server 1 of 79` on `vpn340335758` | **NO** | PHYSICAL DEVICE |

Press 3 is the decisive one: it re-entered `proceedWithPermissionsAndConnect()` and reached the connect
stage, so the gate was **evaluated and skipped** — not bypassed because the flow stopped earlier.
1 dialog in 3 presses. Evidence: PHYSICAL DEVICE.

---

## §5 Test C — BUG-2 data capture

### §5.1 The `Sources:` line (captured twice)

```
08:42:00.918 D/VpnGateCollector: Sources: html=mirror_3 api=null mirrors=11
08:43:53.051 D/VpnGateCollector: Sources: html=mirror_3 api=null mirrors=11
```

Supporting lines from the same collection:

```
08:43:40.885 D/VpnGateCollector: Accepted mirror_3 from http://150.40.105.23:64629/en/ (mirror_3: 10 columns, 100 rows)
08:43:40.924 D/VpnGateCollector: No response from api: https://www.vpngate.net/api/iphone/
```

Reading: the **HTML** source succeeds and it is the one carrying the protocol columns
(`sstp` is column 7 of 10); the **API** source fails (`api=null`), so OpenVPN profile data is absent
this run. 11 mirrors are in play. Evidence: PHYSICAL DEVICE.

### §5.2 Per-server `sstp=` — from the database, not the debug sheet

The in-app debug sheet is **unavailable in this build state**: long-press a server → "Debug collector
info" renders *"Provenance unavailable — refresh the server list first"*, before **and** after two
successful collections (§5.1). Cause not established — `VpnServerRepository.findRecord` matches on
`ip` or normalized hostname against `lastRecords` (set at `:298`), and that lookup returned nothing
here. Reported as an observation, **UNKNOWN**.

Because the sheet could not supply it, the same data was read from the app's Room database
(`run-as` + local SQLite), which is stronger evidence — the whole dataset, not one server:

| Metric | Count |
|--------|-------|
| rows | **102** |
| `isSSTPSupport = 1` | **79** |
| `sstpPort > 0` | 62 |
| `sstpPort = 0` | 40 |
| `sstpPortSource`: DEFAULT / EXPLICIT / UNKNOWN | 78 / 1 / 23 |
| **usable for MS-SSTP** (`isSSTPSupport=1` AND (`sstpPort>0` OR source=DEFAULT)) | **79** |

(`sstpConnectPort` maps `DEFAULT → 443`, so a `DEFAULT` row with `sstpPort=0` is still automatable —
`VPNGateConnection.kt:92–106`, `AutoModeController.kt:2236`. SOURCE CODE.)

The database also carries `firstSeenAt` / `lastSeenAt` / `healthScore` — independent confirmation that
the v1.0.39 Room v8 schema is live on device. Evidence: PHYSICAL DEVICE + SOURCE CODE.

### §5.3 Why "MS_SSTP never attempted" — the configuration

The device's Auto Connect Protocol Priority, before any change:

```
SETTING_AUTO_PROTOCOL_PRIORITY = SOFTETHER_TCP,SOFTETHER_UDP
```

and the editor showed **Ms sstp → switch off**. With that setting the run cannot attempt MS-SSTP at
all: `protocolPriorityProvider = { du.getAutoModeProtocolPriority() }` and
`usableProtocols = priorities.filter { … }`. This alone explains the reported symptom. Evidence:
PHYSICAL DEVICE + SOURCE CODE.

### §5.4 With MS-SSTP enabled — it *is* attempted, and it times out

Enabled MS-SSTP, then made it the **only** protocol (`SETTING_AUTO_PROTOCOL_PRIORITY=MS_SSTP`) to force
the question. The run:

```
08:47:13.565 [AUTO] Started
08:47:13.565 [AUTO] Protocols (priority) = MS_SSTP
08:47:13.565 [AUTO] Connection timeout = 12 seconds
08:47:13.595 [AUTO] Servers available = 102
08:47:13.597 [AUTO] Pre-filtering reachable servers via probe (79 endpoint(s) across MS_SSTP)...
08:47:15.141 [AUTO] Reachable servers selected: 75
08:47:15.147 [AUTO] Trying #1 vpn137307590
08:47:27.432 [AUTO] TIMED_OUT after 12000ms on vpn137307590/MS_SSTP; cleanup then next protocol
08:47:27.433 [AUTO] Protocol failed: MS_SSTP
08:47:27.556 [AUTO] Teardown finished but NATIVE CLEANUP UNCONFIRMED (teardown#1(session=0, NATIVE_UNRESOLVED)); ownership retained, a new native attempt will be refused
08:47:27.558 [AUTO] Connection failed: vpn137307590
```

Two numbers line up exactly: the probe reports **79 endpoints across MS_SSTP**, and the database holds
**79** SSTP-capable rows. The selection layer therefore admits every SSTP-capable server — **no filter
bug**.

Every observed attempt failed the same way: `vpn137307590`, `vpn996750759`, `vpn663500256`,
`vpn449898439`, `vpn603218398`, `vpn849621319`, `vpn340335758` — all `TIMED_OUT`, then
`Protocol failed: MS_SSTP`, then `NATIVE_UNRESOLVED` teardown.

Raising the budget did **not** help: after setting the timeout to 60 s **and restarting the app**
(the value is read once at controller construction, `AutoModeEngine.kt:75` — a settings change alone
does not apply to a live controller), the run logged:

```
08:55:05.878 [AUTO] Connection timeout = 60 seconds
08:56:07.694 [AUTO] TIMED_OUT after 60000ms on vpn340335758/MS_SSTP; cleanup then next protocol
```

So the 12 s budget is **not** the cause. Evidence: PHYSICAL DEVICE.

### §5.5 The protocol itself works — manually

| Path | Server | Result |
|------|--------|--------|
| **Manual** MS-SSTP (detail screen → "Connect via MS-SSTP") | `public-vpn-189.opengw.net` (219.100.37.180) | **Connected** — `Connected MS-SSTP with IP: 10.244.189.41`, `tun0 inet 10.244.189.41/32` |
| **Manual** MS-SSTP | `vpn340335758.opengw.net` (112.68.148.47) — a server Auto Mode timed out on | **"Canceled"** — no tunnel |

So SSTP success is **per server**, and the SSTP client is not broken in general. Evidence: PHYSICAL
DEVICE.

### §5.6 Verdict on BUG-2

1. **Not a data gap** — 79 SSTP-capable servers are present and automatable.
2. **Not a filter bug** — the probe and the predicate admit exactly those 79.
3. The reported "never attempted" is **configuration**: MS-SSTP was switched off in the priority list.
4. What remains is a **real, different problem**: once enabled, Auto Mode's MS-SSTP attempts **time out
   at the full budget** on every server it reached, while manual MS-SSTP connects to at least one of the
   same pool. Whether Auto Mode dials a different endpoint than the manual path (host vs IP, port) is
   **UNKNOWN** — the decisive experiment is to let a run continue until it reaches
   `public-vpn-189.opengw.net` (the server manual SSTP is known to work on) and observe whether it
   connects there.

---

## §6 Test D — stale Error + live tunnel: NOT OBSERVED

The intended sequence is: run → `Error`, then bring a tunnel up externally, then open Auto Mode and
confirm it shows **Connected (manual)** with no "Tap to retry".

**Blocker.** `AutoModeState.Error` is published only when a run exhausts its candidate list. With
Wi-Fi disabled the run still iterates every server: on device it advanced server-by-server with
`NATIVE_ERROR` per protocol (`SOFTETHER_TCP` ~10 s, `SOFTETHER_UDP` ~10 s, `MS_SSTP` 12 s). With 78
candidates × 3 protocols that is roughly **40 minutes** of unattended running, so the Error state was
not reached and the test was abandoned rather than faked.

The rule itself is covered at TEST level: `AutoModeDisplayStateTest.an external tunnel after a run
error still shows Connected` (T-NEW-28g) asserts exactly this case — `Error` + `CONNECTED` →
`display is Connected`, `isExternal = true`, and `modeState` still `Error` for the error card.
The device observation that comes closest is Test A: `Disconnected` + live tunnel → "Connected
(manual)", the same aggregation branch. Evidence: TEST + UNKNOWN (device).

---

## §7 Deviations from the brief, stated plainly

1. **Test order.** Test B ran **first**, not second. It requires an unset `SETTING_BATTERY_OPT_ASKED`
   and a non-exempt device; Test A's presses would have consumed the one-shot flag.
2. **Two pre-conditions were corrected before testing**: the installed build was v1.0.40's
   *predecessor* (`143 / 2.4.10-dev`), and the app was battery-exempt. Both would have invalidated the
   tests as written.
3. **Test C did not use the debug sheet for `sstp=`** — the sheet returned "Provenance unavailable"
   twice (§5.2). The database was used instead, which covers all 102 rows rather than one server.
4. **Test C went beyond capture**: because the captured configuration contradicted the bug's premise,
   MS-SSTP was enabled and made the sole protocol to determine whether it is ever attempted. That is an
   active experiment, not a passive capture.
5. **Test D was not completed** (§6).
6. **Device settings were changed and then restored**: battery exemption removed and restored; MS-SSTP
   enabled (left enabled, as the test required); timeout raised to 60 s and returned to **12 s**;
   priority returned to `SOFTETHER_TCP,SOFTETHER_UDP,MS_SSTP`. Developer Mode was left **on**.
7. **No repository source file was modified.** This phase produced this report only.

---

## §8 Device state left behind

| Item | State |
|------|-------|
| Installed app | `144 / 2.4.11-dev` (upgraded from `143 / 2.4.10-dev`, data preserved) |
| Tunnel | none — `Disconnected`, `tun0` has no address |
| Wi-Fi | re-enabled |
| Battery exemption | restored (`user,vn.unlimit.vpngatepro,10340`) |
| `SETTING_BATTERY_OPT_ASKED` | `true` (written by Test B) |
| `SETTING_AUTO_PROTOCOL_PRIORITY` | `SOFTETHER_TCP,SOFTETHER_UDP,MS_SSTP` (MS-SSTP now enabled) |
| `SETTING_AUTO_MODE_TIMEOUT_SECONDS` | `12` (restored) |
| Developer Mode | **on** |

---

## §9 Evidence labels per claim

| Claim | Label |
|-------|-------|
| Installed build was `143 / 2.4.10-dev`; upgraded to `144 / 2.4.11-dev` | PHYSICAL DEVICE |
| App was battery-exempt; exemption removed and restored | PHYSICAL DEVICE |
| Test A: "Connected (manual)" rendered for a manual tunnel | PHYSICAL DEVICE |
| Test A: tap tore the tunnel down, `tun0` lost its address | PHYSICAL DEVICE |
| Test A: zero `start refused` lines in the capture | PHYSICAL DEVICE |
| Test B: exactly one dialog across three presses | PHYSICAL DEVICE |
| Test B: flag persisted as `SETTING_BATTERY_OPT_ASKED=true` | PHYSICAL DEVICE |
| `Sources: html=mirror_3 api=null mirrors=11` | PHYSICAL DEVICE |
| Dataset holds 79 SSTP-capable servers; all 79 automatable | PHYSICAL DEVICE + SOURCE CODE |
| MS-SSTP was disabled in the priority list | PHYSICAL DEVICE |
| With MS-SSTP enabled it is attempted (probe: 79 endpoints; 75–76 selected) | PHYSICAL DEVICE |
| Every MS-SSTP attempt timed out at 12 s and at 60 s | PHYSICAL DEVICE |
| Manual MS-SSTP connects to `public-vpn-189`, fails on `vpn340335758` | PHYSICAL DEVICE |
| The timeout is read once at controller construction | SOURCE CODE (`AutoModeEngine.kt:75`) |
| Debug sheet reports "Provenance unavailable" after a successful collection | PHYSICAL DEVICE; cause UNKNOWN |
| Test D outcome | UNKNOWN (device); TEST for the rule |

---

## §10 Negative assurance

No source file, test, spec, or build file was edited in this phase. No commit was made. No tag was
created. No `git add`, no push, no force, no history rewrite. No app data was cleared — the 102-server
dataset was preserved throughout. No file was deleted. The device was left with Wi-Fi on, no tunnel,
the battery exemption restored, and the timeout back at its original 12 s.

---

## §11 Recommendation

1. **Re-scope BUG-2.** It is not "MS_SSTP is never attempted". The accurate statement is: *MS-SSTP was
   disabled in the user's priority list; once enabled, every Auto Mode MS-SSTP attempt times out at the
   full budget, although the same protocol connects manually to at least one server.*
2. **One decisive follow-up experiment** before any code change: run with MS-SSTP only and let it reach
   `public-vpn-189.opengw.net`. If Auto Mode connects there, the defect is candidate ordering (it spends
   the budget on SSTP-hostile servers first); if it times out there too, the Auto Mode SSTP dial differs
   from the manual one and that difference is the bug. Either way the answer is a device observation,
   not more source reading.
3. **Separate, smaller findings worth their own items**: (a) the debug sheet returns "Provenance
   unavailable" after a successful collection, so the diagnostic it exists for is unusable; (b) the
   Auto Mode timeout is captured at controller construction, so a Settings change silently does not
   apply until the app restarts.
4. **Test D** needs a way to reach `Error` cheaply — a run against a deliberately tiny candidate set, or
   a debug affordance to force the state. Until then it stays TEST-covered only.
