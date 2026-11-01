# DEVICE VERIFICATION — v1.0.53 (SERVER-DATA-AUDIT-AND-OPENVPN-CONFIG-GATE)

**Date:** 2026-10-22
**Build under test:** `app-pro-debug.apk`, 63,800,978 bytes, SHA-256 `9d5f6cda1f1ce25ae6fcb08188fed537c948483d5afa3ae834676840c59328eb`
**Commit under test:** `4fc9490` (source + tests) / `47b1251` (docs), on top of `4afa7e3`
**Purpose:** close the V-1..V-5 rows that `AUTO_DATA_AUDIT_REPORT.md` §9 left PENDING. The block was written device-free; this run supplies the device evidence.

Every claim below is labelled `[PHYSICAL DEVICE]` (observed on the device), `[SOURCE CODE]`, `[TEST]`, or `[INFERENCE]`.

---

## 1. Environment

| Item | Value |
|---|---|
| Device | Redmi Note 11, model `2201117TG`, product `spes_global`, serial `5585fecc` |
| Android | 13 (`ro.build.version.release`), `targetSdk 37`, `minSdk 24` |
| Display | 1080×2400, density 440 |
| Package | `vn.unlimit.vpngatepro`, `versionCode 144`, `versionName 2.4.11-dev` |
| Network | Wi-Fi `PARS-M`, RSSI −52 dBm, `VALIDATED`; baseline RTT to 8.8.8.8 **519 ms** |
| Install | `adb install -r` over the v1.0.52 build (data preserved) |

---

## 2. What was run

| Run | Protocol profile | Window | Outcome |
|---|---|---|---|
| 1 | `SOFTETHER_TCP,SOFTETHER_UDP` (the device's own setting) | 16:08:42 – 16:31 | **CONNECTED** `vpn517690502` / SOFTETHER TCP, ping 3 ms |
| 2 | `OPENVPN_TCP,OPENVPN_UDP` | 16:18:44 – 16:31:19 | 27 servers dialled, **CONNECTED** `public-vpn-75` / OPENVPN TCP |
| 3 | `OPENVPN_TCP,OPENVPN_UDP` | 16:35 – 16:37 | **CONNECTED** `public-vpn-79` / OPENVPN TCP (capture missed the attempt window) |
| 4 | `OPENVPN_TCP,OPENVPN_UDP` (clean, force-stopped first) | 16:40:57 – 16:41:57 | **CONNECTED** `public-vpn-58` / OPENVPN TCP, full log captured |

`[PHYSICAL DEVICE]` Run 1 is the regression check: the SoftEther path is unchanged by this build (connected in ~28 s, `ATTEMPT_CLASS CONNECTED`). Runs 2–4 are the OpenVPN gate.

**On the operator's note that the earlier failures were network:** confirmed. Run 2's first 13 minutes are almost entirely `TIMEOUT_NO_VERDICT` (82 of 83 attempts), then at 16:31:19 the same build connected on the same protocol with no change to the app. Nothing in the app changed between the failures and the success — only the network did. `[PHYSICAL DEVICE]`

---

## 3. V-1..V-5 verdicts

| ID | Check | Verdict | Evidence |
|---|---|---|---|
| **V-1** | A config-less OpenVPN server is never selected | **PASS** | `[PHYSICAL DEVICE]` Run 2 dialled **27 distinct servers, 27 with a config, 0 without**; run 4 dialled **5, 5 with a config, 0 without**. Cross-checked hostname by hostname against the device's own Room database. No `connect:`/`Trying` line named a server whose `openVpnConfigData` is empty. |
| **V-2** | The gate does not shrink the usable set | **PASS** | `[PHYSICAL DEVICE]` OpenVPN is still offered and still **connects**: three separate OpenVPN tunnels came up (`public-vpn-75`, `-79`, `-58`). The reachable set was **64 servers** — exactly the number of rows in the database that carry a config (§4). The gate removed exactly the rows that could not be dialled, and nothing else. |
| **V-3** | `123.48.124.169` dials 1558 | **PASS (port selection)** | `[PHYSICAL DEVICE]` + `[SOURCE CODE]` See §5. The app dialled `vpn122916437`, whose stored profile says `remote 123.48.124.169 1558` and whose `tcpPort` is **1558**; the config-less twin row `vpn813516587` (`tcpPort 1534`, no profile) was **never dialled**. The 1558 dial itself was rejected by the server (`ENDPOINT_REJECTED`), which is a server-side condition, not a port-selection error. |
| **V-4** | SSTP with a `host:port` hostname connects | **NOT RUN** | `[PHYSICAL DEVICE]` No SSTP-enabled server with a `host:port` hostname was in this device's database during the run, and the SSTP protocol was switched off. The parser half is covered by `[TEST]`; the device half stays PENDING. |
| **V-5** | `MISSING_CONFIG` never appears | **PASS** | `[PHYSICAL DEVICE]` **0 occurrences** of `MISSING_CONFIG` across all three captured runs (run 2: 83 attempts; run 4: 8 attempts). The defence-in-depth branch never fired, which is what a working primary gate looks like. |

`[PHYSICAL DEVICE]` The tunnel was verified to carry traffic, not just to report Connected: `tun0` came up at `10.236.128.253/30`, `ping -c 3 8.8.8.8` returned **3/3, 0 % loss, 395 ms avg**, and `dumpsys connectivity` reported `VpnTransportInfo{type=1, sessionId=Japan[219.100.37.49][TCP:443]}`.

---

## 4. The dataset the device actually held

`[PHYSICAL DEVICE]` Pulled from the installed app (`databases/vpn_gate_connector`, 574 rows) and queried directly:

| Measure | Value |
|---|---|
| Rows in `VPNGateItem` | **574** |
| Rows with a non-empty `openVpnConfigData` | **64** |
| Rows without | **510** (88.9 %) |
| Config length | 10,004 – 10,008 chars (the SoftEther-generated `.ovpn` template) |
| Rows with config **and** `tcpPort > 0` | 64 |
| Rows with config **and** `udpPort > 0` | 36 |
| **OpenVPN endpoints the gate should allow** | **100** |

`[PHYSICAL DEVICE]` The app logged `Pre-filtering reachable servers via probe (**100 endpoint(s)** across OPENVPN_TCP > OPENVPN_UDP)`. The number the gate produced and the number the database predicts are **the same 100** — the gate is arithmetic, not a heuristic. The reachable set then settled at **64 servers**, exactly the 64 config-carrying rows.

`[PHYSICAL DEVICE]` `openVpnConfigData` stores the **decoded `.ovpn` text**, not base64 — it begins `########…  # OpenVPN 2.0 Sample Configuration File`. So `!isNullOrBlank()` in `AutoModeEngine.toCandidate()` is testing real profile bytes, as designed.

---

## 5. V-3 in detail — the duplicate-IP pair on the device

`[PHYSICAL DEVICE]` The device's database contains **two rows for `123.48.124.169`**:

| hostName | `openVpnConfigData` | `tcpPort` | `seTcpPort` | Profile's own `remote` line |
|---|---|---|---|---|
| `vpn122916437` | 10,007 chars | **1558** | 1534 | `proto tcp` / **`remote 123.48.124.169 1558`** |
| `vpn813516587` | **0 chars** | 1534 | 1534 | — (no profile) |

`[PHYSICAL DEVICE]` In run 4 the app dialled `Trying #4 vpn122916437` and never `vpn813516587`. That is the whole block in one observation: the row with the profile is used, the row without one is not, and the port that will be dialled is the profile's 1558 rather than the column's 1534.

`[INFERENCE]` The pair exists because the API row (with config, port 1558) and the emergency-CSV row (no config, port 1534) are merged as separate records — the merger de-duplicates by hostname, and these two hostnames differ. The audit's "0 duplicate IPs" figure was measured on the published CSV; the merged device database is a different population and does contain duplicate IPs. **This is not a regression and needs no fix in this block** — the gate already makes the config-less twin harmless — but it is the reason the port conflict looked "single" in the CSV and "double" on the device.

---

## 6. Observations — reported, not hidden

| # | Observation | Assessment |
|---|---|---|
| **O-2** | `[AUTO] Teardown finished but NATIVE CLEANUP UNCONFIRMED (teardown#N(session=0, NATIVE_UNRESOLVED)); ownership retained, a new native attempt will be refused` is emitted after **every** OpenVPN attempt (7 of 7 in run 4, 82 in run 2). | `[PHYSICAL DEVICE]` The predicted refusal **never happens** — the next attempt always proceeds. The message is about the SoftEther native slot, and an OpenVPN attempt never creates one (`session=0`), so the warning is describing a resource this protocol does not use. It is noise in the OpenVPN path, not a blocker. **Not introduced by this block; not fixed here.** |
| **O-3** | Pressing the hero button while **Connected** tears the tunnel down and immediately starts a new run. | `[SOURCE CODE]` **Documented design, not a defect.** `onButtonPressedWithoutGuard()`'s `Connected` branch (`AutoModeController.kt:1279-1290`, v1.0.31 OP-R6) says "pressing while connected means *move on*, not *start over*": it records `resumeFromServerKey`, calls `disconnectNow()` then `start()`. The observed screen ("Connecting… Attempting server 1 of 64") is exactly that. |
| **O-4** | The stale preference `vpn_always_prefer_vpn=true` is still present in `shared_prefs`. | `[PHYSICAL DEVICE]` Confirms v1.0.52 §1.6.44 TASK A's claim that a stale stored value is **inert**: nothing reads it and the app routes normally. |
| **O-5** | `SETTING_AUTO_MODE_TIMEOUT_SECONDS` = `8`, and the Settings row renders "Auto Mode Connection Timeout **8 s**". | `[PHYSICAL DEVICE]` Direct device confirmation of v1.0.52 TASK B.2 (12 s → 8 s), and every `TIMED_OUT` line in the logs reads `after 8000ms`, which is the 8 s bound actually applied. |

---

## 7. What was changed on the device, and what was restored

`[PHYSICAL DEVICE]` The device's protocol priority was **`SOFTETHER_TCP,SOFTETHER_UDP`** — OpenVPN, L2TP and SSTP were all switched off. Testing V-1/V-2 therefore required enabling OpenVPN, which was done by writing `SETTING_AUTO_PROTOCOL_PRIORITY` into `shared_prefs/vpn_setting_data_pro.xml` under `run-as` (the protocol-priority dialog re-sorts its rows on every toggle, which makes it unreliable to drive by tap coordinates).

| Step | Action |
|---|---|
| Before | Backed the file up to `test-evidence/v1.0.53/prefs/original.xml` |
| During | `OPENVPN_TCP,OPENVPN_UDP` (runs 2–4) |
| **After** | **Restored to `SOFTETHER_TCP,SOFTETHER_UDP`**, app force-stopped, file replaced, app relaunched |
| Removed | `/data/local/tmp/prefs_test.xml`, `/data/local/tmp/prefs_restore.xml` |

`[PHYSICAL DEVICE]` Nothing else on the device was modified. No app data was cleared, no server list was refreshed, no database row was edited. To keep OpenVPN enabled, switch it on in **Setting → Auto Connect Protocol Priority**.

---

## 8. Evidence files (`test-evidence/v1.0.53/`)

| File | Contents |
|---|---|
| `logcat-run1.txt` | Run 1, SoftEther (regression check) |
| `logcat-run2-ovpn.txt` | Run 2, OpenVPN, 27 servers, the network outage and the recovery |
| `logcat-run4-ovpn-clean.txt` | Run 4, OpenVPN, clean start to `CONNECTED` |
| `db.sqlite`, `db.sqlite-wal` | The device database as pulled (574 rows) |
| `prefs/original.xml`, `prefs/ovpn_only.xml`, `prefs/sstp_only.xml`, `prefs/se_only.xml` | Preference states used and restored |
| `01`…`17-*.png` | Screenshots: launch, SoftEther connected, Settings, protocol dialog, each OpenVPN connected state, final state |

---

## 9. A–M

**A. What was asked.** Test the app on the device; then re-test OpenVPN because the earlier failure was the network.

**B. What was done.** Installed the v1.0.53 APK over the existing data, ran four Auto Connect sessions (one SoftEther, three OpenVPN), pulled the app database and cross-checked every dialled hostname against it, decoded the stored profiles, verified the tunnel carries traffic, restored the device's setting.

**C. Confirmed.** The OpenVPN config gate works on a real device: 100 endpoints from 574 rows, 64 reachable servers, 32 dials across two runs, **zero** config-less dials, **zero** `MISSING_CONFIG`. OpenVPN connects (`public-vpn-75`, `-79`, `-58`). The port dialled is the profile's (1558), not the column's (1534).

**D. Fixed.** Nothing — this was a verification run, and nothing needed fixing.

**E. Not verified.** **V-4 (SSTP `host:port`) remains PENDING** — no such server was in this device's database and SSTP was switched off. The parser half is covered by `[TEST]`.

**F. Labels.** Every row above is labelled; `[INFERENCE]` is used exactly once (§5) and is marked as such.

**G. Network.** The operator's diagnosis was right: the same build, unchanged, went from 82 consecutive timeouts to a working tunnel once the network recovered.

**H. Tests.** No JVM/Python suite was re-run — no source changed in this session.

**I. Build.** APK identity unchanged from §12.3 of `AUTO_DATA_AUDIT_REPORT.md`; installed and verified as `versionCode 144` / `2.4.11-dev`.

**J. Scope.** Two commits (`4fc9490`, `47b1251`) remain **unpushed**. This device run adds no commit; the evidence lives in `test-evidence/v1.0.53/` (untracked).

**K. Risks.** O-2 (a misleading teardown warning on the OpenVPN path) is the only new observation; it is cosmetic and pre-existing.

**L. Unresolved.** V-4. Also O-1 from `AUTO_DATA_AUDIT_REPORT.md` §12.4 — the publisher is still untracked in git and still needs the operator's decision.

**M. Nothing pushed, no tag, no `--force`, no reset/clean/stash/checkout.** Device setting restored to its pre-test value.
