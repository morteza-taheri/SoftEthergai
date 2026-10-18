# DEVICE-VERIFY-SECURITY-SMALL — Report

**Phase:** DEVICE-VERIFY-SECURITY-SMALL
**Repository:** `C:\Dev\SoftEthergai` (canonical name `SoftEthergai`)
**Branch:** `main`
**HEAD at phase start == end:** `23816cb85c7fc68fcadfccbbd01fb9e38ca2bb2a` (= `origin/main`)
**Device:** `5585fecc` — Xiaomi 2201117TG (`spes_global`), Android 13 / API 33
**APK under test:** `app/build/outputs/apk/pro/debug/app-pro-debug.apk`
(SHA-256 `9350ccf3c1a791ebd586c99bbefe94b9dfb891d62f88e1d008781491e69a1a2e`, 63,633,510 bytes)
**Date:** 2026-10-15
**Scope:** physical-device verification of **SEC-02** (cleartext scoping) + a smoke test that the app
still works end-to-end after the RISK-B deletion and the native N-09/N-11 changes.
**Read-only for the repo.** No source / test / build file modification; no commit / push / tag /
reset / clean / stash; no device setting change; no uninstall / data clear; no D: touch; no C5.

Evidence labels: **SOURCE CODE** / **TEST** / **PHYSICAL DEVICE** / **INFERENCE** / **UNKNOWN** /
**NOT REPRODUCED** / **AUTOMATED-UI**.

---

## 1. Executive verdict

**PASS.** The APK installs in place (firstInstallTime unchanged), the merged manifest enforces
`usesCleartextTraffic="false"` with a scoped allow-list, the server-list collector still succeeds over
HTTPS + allow-listed cleartext (all three sources: mirror, api, html), AutoConnect completes 3 + 1
cycles with zero crashes, and the crash buffer is empty. **Device-level enforcement of SEC-02 was
observed directly**: Android blocked cleartext to every non-allow-listed mirror IP while the six
allow-listed bootstrap IPs were permitted.

| Goal | Verdict |
|------|---------|
| (A) Install & launch | **PASS** |
| (B) Cleartext false in merged manifest | **VERIFIED** |
| (C) Collector works over HTTPS | **PASS** |
| (D) AutoConnect works end-to-end | **PASS** |
| (E) No crash / tombstone / process death | **VERIFIED** |

---

## 2. Pre-conditions (STEP 0) — PASS

| Check | Expected | Observed | Result |
|-------|----------|----------|--------|
| 0a `git rev-parse HEAD` | `23816cb…` | `23816cb85c7fc68fcadfccbbd01fb9e38ca2bb2a` | PASS |
| 0b `git rev-parse origin/main` | `23816cb…` | `23816cb85c7fc68fcadfccbbd01fb9e38ca2bb2a` | PASS |
| 0c `git status --short` (tracked) | clean | empty (no ` M` / `M `) | PASS |
| 0d `adb devices -l` | `5585fecc` online | `5585fecc device product:spes_global model:2201117TG` | PASS |
| 0e APK present + SHA-256 | `9350ccf3…` | exists; `9350ccf3c1a791ebd586c99bbefe94b9dfb891d62f88e1d008781491e69a1a2e` | PASS |
| 0f baseline firstInstallTime | record | `2026-09-28 16:05:05` (user 0); `1970-01-01 03:30:00` (work profile) | recorded |

`dumpsys package` also reported `versionCode=143`, `versionName=2.4.10-dev`,
`lastUpdateTime=2026-10-14 22:51:04` before install. Two users exist: `0:morteza taheri` and
`10:Work profile`; the package is installed for both.

---

## 3. Install (STEP 1) — PASS

Command: `adb -s 5585fecc install -r app-pro-debug.apk` → **`Success`** (streamed install).

| Field | Before | After |
|-------|--------|-------|
| `firstInstallTime` (user 0) | `2026-09-28 16:05:05` | `2026-09-28 16:05:05` (**unchanged**) |
| `firstInstallTime` (work profile) | `1970-01-01 03:30:00` | `1970-01-01 03:30:00` (**unchanged**) |
| `lastUpdateTime` | `2026-10-14 22:51:04` | `2026-10-15 08:56:11` (advanced — expected for `-r`) |
| `versionName` / `versionCode` | `2.4.10-dev` / `143` | `2.4.10-dev` / `143` |

**firstInstallTime is unchanged → in-place update confirmed; STOP condition not triggered.**

---

## 4. Merged manifest (STEP 2) — VERIFIED

**2a — packaged APK** (`aapt2 dump xmltree --file AndroidManifest.xml`):
```
A: http://schemas.android.com/apk/res/android:usesCleartextTraffic(0x010104ec)=false
A: http://schemas.android.com/apk/res/android:networkSecurityConfig(0x01010527)=@0x7f14000a
```
`usesCleartextTraffic=false`; `networkSecurityConfig` is a **resource reference** (`@0x7f14000a`), not a
path. **PHYSICAL DEVICE** (APK artifact) + **SOURCE CODE** (matches the v1.0.14 fix).

Packaged `res/xml/network_security_config.xml` (aapt2 dump):
```
E: network-security-config
    E: base-config
      A: cleartextTrafficPermitted=false
    E: domain-config
      A: cleartextTrafficPermitted=true
        E: domain  (x6, includeSubdomains=false)
```
Base is **false**; the scoped `domain-config` is **true** with exactly **6** `<domain>` entries.

**2b — device `dumpsys package vn.unlimit.vpngatepro | grep -iE "cleartext|networkSecurity"`:** empty
(Android's `dumpsys` does not always print the merged manifest — acceptable per the brief; the 2a
APK-level check is authoritative).

---

## 5. Collector smoke test (STEP 3) — PASS

Launched via `am force-stop` + `monkey -p … -c android.intent.category.LAUNCHER 1`; `MainActivity`
focused. Navigated to **Server List** (bottom tab 2) and tapped **Refresh** (`content-desc="Refresh"`,
center ≈ (607, 274)).

**Exact `[collector] path=open source=` lines observed** (tag `VpnServerRepository`), two full
collection rounds (initial load + manual Refresh):
```
10-15 08:57:20.498 I VpnServerRepository: [collector] path=open source=mirror
10-15 08:57:30.602 I VpnServerRepository: [collector] path=open source=api
10-15 08:57:30.602 I VpnServerRepository: [collector] path=open source=html
10-15 08:58:23.578 I VpnServerRepository: [collector] path=open source=mirror   <- manual Refresh
10-15 08:58:33.642 I VpnServerRepository: [collector] path=open source=api      <- manual Refresh
10-15 08:58:33.643 I VpnServerRepository: [collector] path=open source=html     <- manual Refresh
```
**All three sources appeared (mirror + api + html) → the SEC-02 change did NOT break the collector.**

Supporting collector evidence (tag `VpnGateCollector`):
```
HTML servers: 100
Accepted mirror_unnumbered from http://121.186.186.97:40587/en/sites.aspx (5 mirror link(s))
Mirrors available: 12 (discovered=5, cached=12, bootstrap=6)
Collected 100 servers (capability=SourceCapability(providesProtocolColumns=true, providesOpenVpnProfiles=false))
VPNGateViewModel: Update kept the current list (DEGRADED_DATASET); preserved 104 servers
```

**Server list state after refresh: NON-EMPTY.**
- UI dump: `text="104 servers"` (list rows populated).
- Log: `HTML servers: 100`, `Collected 100 servers`, and `DataUtil: Retrieved 104 servers from internal database`.
- The refresh result (100) was smaller than the cached set (104), so the ViewModel kept the larger list
  (`DEGRADED_DATASET; preserved 104 servers`) — **graceful, no empty list, no crash**.

### 5.1 Direct SEC-02 enforcement evidence (bonus — PHYSICAL DEVICE)

Android **actively blocked** cleartext to every non-allow-listed mirror IP. 36 occurrences:
```
D VpnGateCollector: Request failed: CLEARTEXT communication to <ip> not permitted by network security policy
```
**Distinct blocked hosts (10):** `121.130.171.159`, `149.102.146.143`, `150.40.105.17`,
`150.40.105.4`, `150.40.105.5`, `160.251.62.107`, `180.196.214.126`, `211.197.16.167`,
`219.63.61.144`, `59.12.238.246`.

**Allow-listed hosts that succeeded over cleartext (`Accepted mirror_`):** `150.40.105.6`,
`150.40.105.11`, `150.40.105.19`, `150.40.105.23`, `121.186.186.97`.

**Intersection(blocked, allow-list) = ∅.** The six allow-listed bootstrap IPs
(`150.40.105.19`, `150.40.105.6`, `150.40.105.11`, `150.40.105.23`, `194.156.89.134`,
`121.186.186.97`) were all permitted; every other host was blocked by policy. This is the strongest
possible device-level confirmation that SEC-02 is enforced as designed.

---

## 6. AutoConnect cycles (STEP 4) — PASS

Toggle: big central circle, `content-desc` toggles Disconnected ↔ Connected, center **(541, 566)**
(`bounds [343,368][739,764]`) — matches the brief; re-confirmed via `uiautomator dump`.

| Cycle | Started | Connected | Disconnected |
|-------|---------|-----------|--------------|
| 1 | 08:59:29 | 08:59:35 (`[AUTO] Connected successfully`) | 08:59:55 (teardown#1) |
| 2 | 09:00:12 | 09:00:18 | 09:00:37 (teardown#3) |
| 3 | 09:00:49 | 09:00:56 | 09:01:14 (teardown#5) |
| Stress (tap↔tap ≈0.8 s) | 09:01:26 | 09:01:33 | (final disconnect 09:02:14) |

Every connect established a real tunnel: `SoftEtherVpnService: State changed: CONNECTED ip=10.211.1.8`,
`Japan[59.138.182.93][TCP:1378]`, `tun0` network agent registered.

**Stress cycle behaviour:** the second tap arrived while the connect was still in flight and was
handled by the re-entrancy guard — `AutoMode: [AUTO] start ignored: already running` (09:01:28) — then
the connection completed normally at 09:01:33. **No wedge, no double-connect, no crash** (the guard
behaved as designed). Each disconnect produced a clean
`[TEARDOWN-REENTRY] attempt=N, …, NOOP_REENTRANT`.

**4d log counts (whole capture, 12,594 lines):**

| Marker | Count | Expectation |
|--------|-------|-------------|
| `[AUTO] Started` | **4** | ≥ 3 ✅ |
| `[AUTO] Connected successfully` | **4** | ≥ 1 ✅ |
| `[TEARDOWN-REENTRY]` | **3** | informational |
| `FATAL EXCEPTION` | **0** | 0 ✅ |
| `SIGSEGV` | **0** | 0 ✅ |
| `tombstone` | **0** | 0 ✅ |
| `ANR in` / Application Not Responding | **0** | 0 ✅ |
| Process death (`Killing …vn.unlimit` / `Process vn.unlimit…died`) | **0** | 0 ✅ |
| `AndroidRuntime` | 29 | all from `uiautomator` / `monkey` tooling (uid 2000) — **none** from PID 20692 |

No cycle wedged; no STOP condition triggered.

---

## 7. Crash scan (STEP 5) — EMPTY

`adb -s 5585fecc logcat -b crash -d` → **0 lines (EMPTY)**. No app crash, no native crash, no
tombstone. **VERIFIED.**

---

## 8. Settings re-check (STEP 5) — unchanged (read-only)

`run-as vn.unlimit.vpngatepro cat …/shared_prefs/vpn_setting_data_pro.xml` (**read-only, no writes**).
User-configurable settings remain at defaults:

| Key | Value |
|-----|-------|
| `SETTING_AUTO_MODE_TIMEOUT_SECONDS` | `5` |
| `SETTING_SOFTETHER_MAX_CONNECTIONS` | `8` |
| `SETTING_AUTO_PROTOCOL_PRIORITY` | `SOFTETHER_TCP,SOFTETHER_UDP,MS_SSTP` |
| `SETTING_LOCALE` | `en` |
| `USER_ALLOWED_VPN` | `true` |
| `SETTING_DEVELOPER_MODE` | `true` (pre-existing) |

Runtime/state keys (`vpn_last_connection`, `AUTO_LAST_SUCCESS_HOST=vpn315836095`,
`AUTO_LAST_SUCCESS_PROTOCOL=SOFTETHER_TCP`, `KEY_CACHED_MIRRORS`, `vpn_cache_time`) were updated by the
app's own normal operation during the test (connect + collect) — **not** by any adb write. No device
setting was changed.

---

## 9. Per-goal verdict table

| Goal | Verdict | Key evidence |
|------|---------|--------------|
| (A) Install & launch | **PASS** | `install -r` → Success; firstInstallTime unchanged; MainActivity focused |
| (B) Cleartext false in merged manifest | **VERIFIED** | `usesCleartextTraffic=false`; `base-config false` + 6-domain allow-list in packaged config |
| (C) Collector works over HTTPS | **PASS** | all 3 `[collector]` sources; 100 HTML servers; 104-server list preserved |
| (D) AutoConnect works end-to-end | **PASS** | 4× `[AUTO] Started`, 4× `[AUTO] Connected successfully`; real tunnels; clean teardowns |
| (E) No crash / tombstone / process death | **VERIFIED** | crash buffer EMPTY; 0 FATAL/SIGSEGV/tombstone/ANR; 0 app PID deaths |

---

## 10. Explicit statements

- **No source / test / build file was modified.** `git status --short` (tracked) is empty before and
  after; `HEAD` is unchanged at `23816cb…`.
- **No commit, no push, no tag, no reset, no clean, no stash** was performed.
- **No device setting was changed via adb.** The only device writes were `install -r`, `logcat -c`,
  `input tap`, `uiautomator dump` (to `/sdcard`), and `am force-stop`/`monkey` — all within the
  authorized action set.
- **No uninstall, no data clear.** `firstInstallTime` unchanged confirms an in-place update.
- **C5 was not executed.** No synthetic race injection.
- **`D:\PMC\Documents\Android\SoftEthergai` was not touched.**
- **No rebuild and no test re-run** occurred (APK and JVM suites were already green in the prior phase).

**Posture:** SEC-02 is verified on-device (both manifest-level and enforcement-level); the app remains
fully functional (collector + AutoConnect) after the RISK-B deletion and the native N-09/N-11 changes.
N-09, N-11 and RISK-B remain non-observable on-device, as stated in the prior phase report.
