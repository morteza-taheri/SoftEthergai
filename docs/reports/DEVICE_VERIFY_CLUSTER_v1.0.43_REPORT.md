# DEVICE-VERIFY-CLUSTER-v1.0.43 — Report

**Repository:** `C:\Dev\SoftEthergai` · **Branch:** `main`
**Baseline:** HEAD == `origin/main` == `bc5ff0d9d507cf18424dc507cbd1f76274e5c2e9` · spec **1.0.42**
**Device:** serial `5585fecc` · `spes_global` · model `2201117TG` (Redmi Note 11) · Android userspace `ps` STIME 12:59:08
**Session window:** 2026-10-20 12:59 → 13:32 (local)
**Labels:** `SOURCE CODE` / `TEST` / `PHYSICAL DEVICE` / `INFERENCE` / `UNKNOWN` / `PRIOR REPORT`
**Committed?** No. This file and `test-evidence/dv43/*` are untracked by design.

---

## §1 Verdict

| # | Test | Result | Decisive evidence |
|---|------|--------|-------------------|
| T1 | BUG-1 — manual tunnel → "Connected (manual)" | **PASS** (both halves) | UI read `Connected` / `Connected (manual)`; tap produced `action=…DISCONNECT`, `tun0` gone |
| T2 | BUG-3 — battery dialog once | **PARTIAL** | Pref already `true` before the test; no dialog on either press — the *transition* could not be observed |
| T3 | F2 — offline → immediate stop, no wedge poisoning | **PASS** | `No network available` ×1, `Recording a wedge` **0**, `TIMED_OUT` **0**, `Trying #` **0**; on-screen message rendered |
| T4 | F1 — timeout change read live | **PASS** | Same PID logged `Connection timeout = 12 seconds`, then `= 10 seconds` after the setting change |
| T5 | F6 — identity continuation | **PASS** | `Continuing after 211.204.124.210\|vpn137307590; starting at index 2 of 79` → next dial `vpn996750759` |
| T6 | traffic-path v1.0.41 (opt-in + dead tunnel) | **UNTESTABLE** | `vpn_always_prefer_vpn` has **no UI writer anywhere in the app** — the opt-in cannot be enabled without an unauthorised data edit |
| T7 | BUG-2 — MS-SSTP data | **DATA CAPTURED** | Priority includes `MS_SSTP`; 236 endpoints across 3 protocols; one `MS_SSTP` attempt observed (`TIMED_OUT after 12000ms`) |
| — | Crash / ANR / process death | **NONE** | No `FATAL EXCEPTION` / `ANR in` / `Force finishing`; PID 32215 stable for the whole session |

**Headline:** the two fixes whose device behaviour was previously `UNKNOWN` — F1 (live timeout) and F2 (offline stop) — are now `PHYSICAL DEVICE` verified. F6 is verified too. T6 exposed a **product gap**: the v1.0.41 routing change is gated behind a preference that no screen can set.

---

## §2 Pre-conditions

| Check | Result | Label |
|-------|--------|-------|
| `git rev-parse HEAD` | `bc5ff0d9d507cf18424dc507cbd1f76274e5c2e9` | SOURCE CODE |
| `git rev-parse origin/main` | identical | SOURCE CODE |
| Spec version | `1.0.42` | SOURCE CODE |
| `adb devices` first attempt | `5585fecc unauthorized` | PHYSICAL DEVICE |
| After `kill-server` / `start-server` | `5585fecc device` | PHYSICAL DEVICE |
| APK SHA-256 | `E8A4BB1400E9046C1C0B4B34218B18DA10C127A1992407EAD8062F5FD5A67ECD` — **exact match** to the recorded value | TEST |
| APK identity | `versionCode='144' versionName='2.4.11-dev'` | TEST |
| Install | `adb install -r` → `Success`, `versionCode=144` | PHYSICAL DEVICE |
| Pref `SETTING_AUTO_MODE_TIMEOUT_SECONDS` at start | **12** (brief expected 5) | PHYSICAL DEVICE |
| Pref `SETTING_BATTERY_OPT_ASKED` at start | **true** (brief assumed unset) | PHYSICAL DEVICE |

**Two baseline deviations, both reported rather than worked around:**

1. **Timeout was 12, not 5.** The brief's F1 script is `5 → 10 → restore 5`. Running `12 → 10` keeps the test decisive (a frozen controller would have logged 12), and the value was restored to **12**, the value actually present — restoring 5 would have permanently changed the user's setting on the strength of a wrong premise.
2. **`SETTING_BATTERY_OPT_ASKED` was already `true`** from an earlier session, and `install -r` preserves data by design. The once-only transition therefore cannot be observed without clearing app data, which the brief forbids. See §4.

---

## §3 T1 — BUG-1 (manual tunnel → "Connected (manual)") — PASS

**Setup performed:** Server List → first row (`public-vpn-189.opengw.net`, `219.100.37.180`, Japan) → *Connect to this server* → protocol chooser → **SoftEther SSL-VPN over TCP** → connected.

```
39: tun0: <POINTOPOINT,UP,LOWER_UP> mtu 1500 ...
    inet 10.244.189.2/16 scope global tun0
```

**Auto Mode tab, first half (v1.0.40 fix):**

```
'Connected'            [370,907][712,995]
'Connected (manual)'   [359,1006][722,1061]
```

Not `Disconnected` / `Tap to connect`. **PASS** — `PHYSICAL DEVICE`.

**Second half (tap must tear the external tunnel down, not hit the D-4 refusal):**

```
SoftEtherVpnService: onStartCommand: action=vn.unlimit.softether.DISCONNECT
SoftEtherVpnService: State changed: DISCONNECTED ip=10.244.189.2
ConnectivityService: [111 WIFI|VPN] ... going from CONNECTED to DISCONNECTED
SoftEtherProtocol: Disconnecting (previous state: CONNECTED)
```

`ip addr show tun0` → `Device "tun0" does not exist.` **PASS** — `PHYSICAL DEVICE`.

**Operational note (not a defect):** the hero button is a 144 dp circle **above** its label, and it exposes **no clickable node** to `uiautomator` (Compose does not publish the semantics for it). Taps on the label text do nothing; the circle centre (541, 682 in this layout) works. This matters for any future scripted run.

---

## §4 T2 — BUG-3 (battery dialog once) — PARTIAL

| Step | Observation |
|------|-------------|
| Pref before | `SETTING_BATTERY_OPT_ASKED = true` |
| Press #1 (from Disconnected) | **No dialog**; run started (`Connecting…`) |
| Press #2 (from Connected) | **No dialog**; identity continuation ran |
| Pref after | `SETTING_BATTERY_OPT_ASKED = true` (unchanged) |

The steady-state half of the claim holds — the dialog does not reappear. The **once-only transition** is not observable, because the flag was already consumed in an earlier session and resetting it requires clearing app data or editing the pref XML, both outside the authorised scope.

**Classification: PARTIAL.** The v1.0.40 flag works as a suppressor; "fires exactly once per install" remains `UNKNOWN` for this session. To close it, the check must be run on a **fresh install** (`adb uninstall` then `install`), which this brief forbids.

---

## §5 T3 — F2 (offline → immediate stop, no wedge poisoning) — PASS

**Setup:** `svc wifi disable` + `svc data disable` → `wifi_on=0`, `ping 8.8.8.8` → `Network is unreachable`, `Active default network: none`.

**Press → full Auto Mode log (nothing filtered):**

```
[AUTO] Started
[AUTO] No network available; stopping before any attempt
```

That is the **entire** run: no `Protocols (priority)` line, no `Servers available`, no probe, no dial.

| Signal | Expected | Actual |
|--------|----------|--------|
| `No network available` | ≥1 | **1** |
| `Recording a wedge` | 0 | **0** |
| `TIMED_OUT` | 0 | **0** |
| `Trying #` (any dial) | 0 | **0** |

**UI:** `Error` / `Tap to retry` / **"No network available. Turn on Wi-Fi or mobile data and try again."** — the B-4 string renders on a real device, so `ERROR_NO_NETWORK` is not falling through to the generic message. **PASS** — `PHYSICAL DEVICE`.

---

## §6 T4 — F1 (timeout change read live) — PASS

| When | Setting | Log line (same PID 32215) |
|------|---------|---------------------------|
| 13:11:39 | 12 s | `[AUTO] Connection timeout = 12 seconds` |
| 13:20:12 | 10 s (changed via Settings → Auto Mode Connection Timeout) | `[AUTO] Connection timeout = 10 seconds` |

The controller was **not** recreated between the two runs: `AutoModeEngine.ensure()` returns the same instance, and the process never restarted (`ps` STIME unchanged). Pre-v1.0.42 this would have logged `12` again.

Pref verified at each step: `12` → `10` → **restored to `12`**. **PASS** — `PHYSICAL DEVICE`.

---

## §7 T5 — F6 (identity continuation) — PASS

State: `Connected & Protected` to `vpn137307590` (`211.204.124.210`), run had resolved 79 candidates.

**Press while Connected:**

```
[AUTO] Continuing after 211.204.124.210|vpn137307590; starting at index 2 of 79
[AUTO] Trying #1 vpn996750759
```

- The continuation key is a real `ip|hostname` identity, not an integer.
- The next dial is a **different** server (`vpn996750759`), not the one we left.
- No `Continuing from server #N` line anywhere in the session — the v1.0.31 index path is gone.

**PASS** — `PHYSICAL DEVICE`.

---

## §8 T6 — traffic-path v1.0.41 (opt-in + dead tunnel) — UNTESTABLE

The brief instructs: *"In Settings, enable 'Always prefer VPN for collector'."* That control does not exist.

```
grep -rn "PREF_ALWAYS_PREFER_VPN" app/src/main/java/
  GlobalVpnTracker.kt:438  (comment)
  GlobalVpnTracker.kt:527  prefs?.getBoolean(PREF_ALWAYS_PREFER_VPN, false)
  GlobalVpnTracker.kt:780  internal const val PREF_ALWAYS_PREFER_VPN = "vpn_always_prefer_vpn"
```

The preference is **read and never written** by any production code path, and no file under `ui/` references it. There is no toggle in Settings, no developer sheet, no intent extra. The Settings screen's Auto Mode section contains exactly: Protocol Priority, Connection Timeout, Auto-Reconnect, Max Connections — no collector-path control.

Consequences:

1. T6 cannot be executed as written. Enabling it would require editing `vpn_always_prefer_vpn` in the prefs XML via `run-as`, which is a device-data change outside the authorised set (only `SETTING_AUTO_MODE_TIMEOUT_SECONDS` is authorised).
2. **This is a product finding, not a test-infrastructure problem**: the v1.0.41 gating change (`resolvePrimary` requires `preferVpn && tunnelUsable`) has **no user-reachable opt-in** in this build. With the default `false`, the new `tunnelUsable` condition can never change a user's routing decision — `preferVpn` is already `false`, so the rung is OPEN either way. The fix is correct and tested, but currently unreachable. `SOURCE CODE`.

**Incidental observation while refreshing the list** (`PHYSICAL DEVICE`, default `preferVpn=false`):

```
[fetch] path=open source=mirror            → succeeded (HTML servers: 99)
[fetch] path=open failed (no result); falling back to vpn
[collector] path=fallback-to-vpn source=api
Request failed: Binding socket to network 115 failed: EPERM (Operation not permitted)
[fetch] path=vpn failed (no result)
```

The OPEN rung worked; the VPN rung failed with `EPERM` while no tunnel existed. That is consistent with the v1.0.41 design intent (a dead/absent tunnel must not be the primary path) and is the first device-side hint about *why* the VPN rung is fragile — but it is **not** the opt-in scenario, so it does not substitute for T6.

**Classification: UNTESTABLE** (blocked by a missing UI affordance).

---

## §9 T7 — BUG-2 data capture

**Protocol priority at test time** (Settings → Auto Connect Protocol Priority): `softether tcp → softether udp → ms sstp` — both SOFTETHER_UDP and MS_SSTP enabled, as the brief requires. No change was needed.

**Captured data:**

```
[AUTO] Protocols (priority) = SOFTETHER_TCP > SOFTETHER_UDP > MS_SSTP
[AUTO] Pre-filtering reachable servers via probe (236 endpoint(s) across SOFTETHER_TCP > SOFTETHER_UDP > MS_SSTP)...
VpnGateCollector: Hosts table columns: {country=0, host=1, sessions=2, perf=3, softether=4, l2tp=5, openvpn=6, sstp=7, operator=8, score=9}
VpnGateCollector: Mirrors available: 12 (discovered=4, cached=11, bootstrap=6)
VpnGateCollector: HTML servers: 99
VpnGateCollector: Accepted mirror_6 from http://150.40.105.11:4917/en/ (mirror_6: 10 columns, 99 rows)
```

**A real MS-SSTP attempt was observed** during the 13:11 run (server `vpn340335758`, after TCP and UDP both failed):

```
[AUTO] TIMED_OUT after 12000ms on vpn340335758/MS_SSTP; cleanup then next protocol (no same-server retry)
```

`PHYSICAL DEVICE` — captured live at 13:12:20. **Caveat:** that exact line was in the buffer read at 13:12:20 but `logcat -c` before T5 removed it before it was written to a file; it is quoted from the live capture, not from a saved artefact. The saved `t7_run.log` proves the protocol is in the candidate set (priority + 236 endpoints) but its run connected on the first server's first protocol, so SSTP was not reached again.

**No per-server `sstp=` value dump exists.** The only `sstp=` occurrence in the whole session is the column-index map above. The brief's expected `sources=html=… api=… mirrors=…` single line does not exist in this build either; the equivalent information is spread across `Mirrors available`, `Accepted mirror_N`, `HTML servers`, and `[collector] path=… source=…`.

**Conclusion for BUG-2:** MS-SSTP is **not** filtered out — it is in the priority list, in the endpoint pre-filter, and was dialled at least once. The v1.0.40 deferral ("no SSTP-capable server in the dataset" vs "filtered out") is resolved in favour of **attempted but slow**: SSTP timed out at the full 12 s budget rather than being skipped. The server list also advertises SSTP for the first server (`SSTP` chip, and "Connect via MS-SSTP" in the manual chooser). `PHYSICAL DEVICE` + `SOURCE CODE`.

---

## §10 Crash / ANR scan

| Check | Result |
|-------|--------|
| `FATAL EXCEPTION` | none |
| `ANR in` | none |
| `Force finishing` | none |
| PID | `32215` from 12:59:08 through 13:32 — no restart, no death |
| Process start time | unchanged (`ps -A -o PID,STIME,NAME`) |

No abort condition was triggered. `PHYSICAL DEVICE`.

---

## §11 What was not testable, and why

| Item | Why | What would close it |
|------|-----|---------------------|
| T2 once-only transition | `SETTING_BATTERY_OPT_ASKED` already `true`; `install -r` keeps data; clearing it is forbidden | fresh install (`uninstall` + `install`) |
| T6 opt-in routing | `vpn_always_prefer_vpn` has no writer in the app | add a Settings toggle, or authorise a pref edit |
| T7 per-server `sstp=` dump | no such log line exists in this build | add the dump, or accept the column map + attempt line |
| F1 slot-wait half (B-3) | no NATIVE_BUSY event occurred in this session's runs | a device state that produces a refused slot |

---

## §12 Evidence labels

| Claim | Label |
|-------|-------|
| T1 UI text + disconnect | PHYSICAL DEVICE |
| T2 pref value + absence of dialog | PHYSICAL DEVICE (transition: UNKNOWN) |
| T3 zero wedges / zero dials | PHYSICAL DEVICE |
| T4 12 → 10 in one process | PHYSICAL DEVICE |
| T5 identity continuation | PHYSICAL DEVICE |
| T6 no UI writer | SOURCE CODE |
| T6 `path=vpn` EPERM | PHYSICAL DEVICE |
| T7 MS-SSTP attempted | PHYSICAL DEVICE (one line live-captured, not file-saved) |
| T7 SSTP in dataset | PHYSICAL DEVICE (SSTP chip in list + manual chooser option) |
| "v1.0.41 fix is unreachable for users" | INFERENCE from SOURCE CODE |

---

## §13 Negative assurance

- **No uninstall, no data clear.** Only `adb install -r`.
- **No device setting changed except `SETTING_AUTO_MODE_TIMEOUT_SECONDS`** (12 → 10 → 12). WiFi and mobile data were toggled **because T3 requires it**, and both were restored (`wifi_on=2`, ping OK) before T4.
- **No source, spec or test file edited.** No commit, no push, no tag. `git status` shows only the two pre-existing untracked directories plus this report and `test-evidence/dv43/`.
- **No build.** The v1.0.42 APK was used, hash-verified.
- **C5 not executed.**
- Device left **Disconnected**, no `tun0`, Auto Mode tab showing "Disconnected / Tap to connect".

**One scope note:** the brief whitelists `test-evidence/dv43/*.log`, but its own STEP 4/5/6/7 commands write `t1_ui.xml`, `t2_ui_1.xml`, `t2_ui_2.xml`, `t3_ui.xml` — so `.xml` UI dumps exist there. The only file I created outside the whitelist was a UI-driving helper script, which was **moved out of the repository** to `%TEMP%\dv43\` as soon as it was noticed.

---

## §14 Recommendations

| Result | Recommendation |
|--------|----------------|
| T1 PASS | None. BUG-1 is closed on device. For future scripted runs, target the hero circle centre (541, 682), not the label. |
| T2 PARTIAL | Run once on a clean install to close it. Cheap and decisive. |
| T3 PASS | F2 and B-4 are closed on device. The new terminal behaves exactly as designed. |
| T4 PASS | F1 is closed on device. B-3's slot-wait half still needs a NATIVE_BUSY event. |
| T5 PASS | F6 is closed on device, including the "different server" property. |
| T6 UNTESTABLE | **Decide whether `vpn_always_prefer_vpn` should be user-reachable.** As shipped, the v1.0.41 change cannot affect any user. Either expose the toggle or record explicitly that the rung is dormant. Also worth investigating the `EPERM` on binding to the VPN network, which is a separate defect from routing. |
| T7 DATA | BUG-2 is **not** a filter bug on this device: SSTP is attempted. If SSTP connectivity matters, the next step is a manual SSTP connect test against a known-good SSTP server, not more Auto Mode logging. |

**STOP.**
