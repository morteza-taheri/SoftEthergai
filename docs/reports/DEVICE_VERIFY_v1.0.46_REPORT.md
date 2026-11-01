# DEVICE VERIFY — SERVER-LIST PATH + SEED — v1.0.46 — REPORT

**Block:** `DEVICE-VERIFY-SERVER-LIST-PATH-AND-SEED-v1.0.46`
**Repo:** `C:\Dev\SoftEthergai` · **Branch:** `main`
**Baseline:** `HEAD == origin/main == 157445dee31a43b9cdbb0316376ddb5fb73d34db`, spec **1.0.46**
**Device:** `5585fecc` — `spes_global` / Redmi Note 11 (`2201117TG`), Android 13, not rooted
**Date:** 2026-10-21, 11:41–12:10 (+03:30)
**Deliverable:** untracked — **not committed**

Every claim is labelled: `[PHYSICAL DEVICE]`, `[SOURCE CODE]`, `[TEST]`, `[INFERENCE]`, `[UNKNOWN]`.

---

## 1. Verdict

| Test | Expectation | Result | Basis |
|---|---|---|---|
| **V1** | fresh install seeds exactly one default emergency row | **PASS** | `[PHYSICAL DEVICE]` + DB read |
| **V1b** | second launch does not re-seed | **PASS** | `[PHYSICAL DEVICE]` + DB read |
| **V2** | user-deleted row does not resurrect | **PASS** | `[PHYSICAL DEVICE]` + DB read |
| **V3** | picker offers 8..60, 12 selected | **PASS** | `[PHYSICAL DEVICE]` + screenshot |
| **V4a** | tunnel up + toggle OFF → PHYSICAL primary | **PASS** | `[PHYSICAL DEVICE]` |
| **V4b** | tunnel up + toggle ON → TUNNEL primary (v1.0.44 T6b retry) | **PASS — defect fixed** | `[PHYSICAL DEVICE]` |
| **V4c** | tunnel up + PHYSICAL blocked → fallback named | **NOT FORCED** (organic fallback observed both ways) | `[PHYSICAL DEVICE]` |
| **Restore** | toggle back to OFF | **DONE** (`checked=false`, both stores) | `[PHYSICAL DEVICE]` |
| **Crash/ANR** | none | **PASS** (0 FATAL, 0 ANR, PID stable) | `[PHYSICAL DEVICE]` |

**Headline:** all four pending verifications close. The v1.0.46 seed is correct and — critically — **once-only across real process death**. The v1.0.46 timeout range is correct. The v1.0.44 T6b failure is **fixed**: with the toggle ON the ladder now genuinely reorders to `TUNNEL,PHYSICAL,EMERGENCY` and the primary attempt is `path=tunnel`.

**But the tunnel rung still does not deliver data.** In V4b every tunnel attempt returned "no result" and the update only completed because the ladder automatically fell back to `path=physical`. So the operator's "route through the app's own tunnel when the physical path is blocked" requirement is **mechanically wired and demonstrably firing**, but **not yet proven to work as a transport** — because the tunnel rung fails on this device for a reason this run could not attribute (§7.4).

---

## 2. Pre-conditions and APK identity

| Item | Value | Basis |
|---|---|---|
| `git rev-parse HEAD` | `157445dee31a43b9cdbb0316376ddb5fb73d34db` | `[PHYSICAL DEVICE]`/git |
| `git rev-parse origin/main` | `157445dee31a43b9cdbb0316376ddb5fb73d34db` — **match** | git |
| `Document version` | **`1.0.46`** | `[SOURCE CODE]` |
| `adb devices -l` | `5585fecc device product:spes_global …` | `[PHYSICAL DEVICE]` |
| Pre-install package | `vn.unlimit.vpngatepro`, `versionCode=144`, `2.4.11-dev`, installed APK SHA-256 `339f344c…6ba6` = **the v1.0.44 build** | `[PHYSICAL DEVICE]` |

### 2.1 APK selection — a documented deviation from STEP 1

STEP 1 specified `app\build\outputs\apk\pro\debug\app-pro-debug.apk` **and** required its mtime to be after the v1.0.46 commit. The in-repo APK **fails that rule**:

| Candidate | Size | mtime | vs commit (11:31:40) | SHA-256 |
|---|---|---|---|---|
| In-repo `app/build/outputs/...` | 63,779,506 | **11:09:49** | **22 min BEFORE** ❌ | `88b4a4fe…b6591` |
| Gate clone `D:\temp\emergency-reader-verify-157445d\...` | 63,779,518 | **11:36:45** | **5 min AFTER** ✅ | `a8469a50…016b` |

The in-repo APK was built from the working tree that *became* commit `d9359d7` (content-identical to HEAD, and `aapt2` had already confirmed `default_emergency_source_url` packaged), but the literal freshness rule rejected it. The gate APK was built by `git archive origin/main` — i.e. **exactly commit `157445d`** — and post-dates the commit.

**Operator decision (asked and confirmed): use the gate APK.** No new build was performed, as required by the FORBIDDEN list.

**Installed and verified** `[PHYSICAL DEVICE]`:

```
installed APK SHA-256 : a8469a503316d98dd412f33d6c42864e804ecaa77d8ecd280b9106c0927b016b
gate APK SHA-256      : a8469a503316d98dd412f33d6c42864e804ecaa77d8ecd280b9106c0927b016b   ← identical
versionCode=144  versionName=2.4.11-dev
flags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP ]
```

> The app is **debuggable** in this build, so `run-as` works and the database and preferences were read directly. This is a material improvement over the v1.0.44 run, where `run-as` was refused and the toggle state had to be inferred from the UI.

### 2.2 Destructive step — operator-confirmed

`adb uninstall vn.unlimit.vpngatepro` → `Success`; `adb install -r <gate apk>` → `Success`.
Confirmed clean by the data directory listing: only `cache/` and `code_cache/` exist — **no `databases/`, no `shared_prefs/`** — and the app was assigned a **new UID `u0_a341`** (previously `u0_a340`) `[PHYSICAL DEVICE]`.

---

## 3. V1 — fresh-install seed

**PASS.**

### 3.1 The seed fired exactly once

```
10-21 11:46:26.397  16764 16846 D VpnGateApp: Seeded default emergency source
```

One occurrence in the whole launch-1 buffer (`v1_launch1.log`, 1,207 lines). Thread **16846** is an IO worker — consistent with `appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` `[SOURCE CODE]`. Timestamp is **26 s after install**, i.e. after the first-run language picker, not during a cold `onCreate` race.

No other seeder diagnostic appeared (no "already configured", no "not valid", no "seed failed").

### 3.2 The flag was set

`shared_prefs/vpn_setting_data_pro.xml`:

```xml
<boolean name="SETTING_EMERGENCY_DEFAULT_SEEDED" value="true" />
```

### 3.3 The row is correct — verified via direct DB read

The device has **no `sqlite3` binary** (`/system/bin/sh: sqlite3: inaccessible or not found`), so `databases/vpn_gate_connector` plus its `-wal` and `-shm` were pulled with `run-as cat` and queried on the host:

```
CREATE TABLE `emergency_source_config` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  `url` TEXT NOT NULL, `label` TEXT, `enabled` INTEGER NOT NULL DEFAULT 1,
  `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)

row count: 1
{'id': 1,
 'url': 'https://raw.githubusercontent.com/morteza-taheri/SoftEthergai/main/emergency-sources/servers.csv',
 'label': 'Default',
 'enabled': 1,
 'createdAt': 1792570586314,
 'updatedAt': 1792570586314}
```

All four assertions hold: **exactly one** row; URL is the published location; label `Default`; `enabled = 1`. `createdAt` decodes to **11:46:26.314**, 83 ms *before* the log line — the row was committed first, then logged, matching the code order `dao.upsert(...)` → `logD("Seeded …")` `[SOURCE CODE]`.

The URL matches `R.string.default_emergency_source_url` as packaged in the APK (`aapt2 dump resources` on the same artifact) `[TEST]`.

### 3.4 The row is visible in the UI

The Settings → emergency-source section rendered the row with label **`Default`**, the full URL, and a **Remove source** control at `[2120,362][2177,415]` — i.e. the seeded row is an ordinary user-editable row, as designed.

---

## 4. V1b — second launch does not re-seed

**PASS.**

- `am force-stop` → `pidof` empty (real process death)
- relaunch → new PID **17008**; `ActivityTaskManager: Displayed …SplashActivity: +2s206ms`
- **no** seeder line anywhere in the 489-line buffer
- `emergency_source_config` still **1** row — same `id=1`, same `createdAt`, **no duplicate**
- flag still `true`

---

## 5. V2 — user-deleted row does not resurrect

**PASS — and this is the decisive one.**

### 5.1 Deletion through the real UI (not a DB poke)

Tapped **Remove source** on the `Default` row. No confirmation dialog appeared. The row disappeared from the dump and the layout collapsed upward (the `Source URL` field moved from y≈571 to y≈460).

DB immediately after: **0 rows**.

### 5.2 Restart

- `am force-stop` → `pidof` empty
- relaunch → new PID **17595**; `Displayed …SplashActivity: +2s161ms`, then `Displayed …MainActivity: +743ms`
- **no** seeder line in the 489-line buffer
- `emergency_source_config` = **0 rows**
- `SETTING_EMERGENCY_DEFAULT_SEEDED` still `true`

**Interpretation:** the once-only guarantee is enforced by the flag, not by table emptiness, and it survives real process death. This is precisely the contract `DataUtil.hasSeededEmergencyDefault()` documents ("a user who deletes the seeded row has made an explicit choice") `[SOURCE CODE]` + `[PHYSICAL DEVICE]`.

> **State left behind:** the table is empty at the end of this run. This is the *correct* end state of V2, not a regression — but the device now has **no** emergency source, which is why the `EMERGENCY` rung could not be exercised in V4 (§7.5).

---

## 6. V3 — timeout picker range

**PASS.**

### 6.1 The stored/displayed value

Settings → **Auto Mode Connection Timeout** rendered **`12 s`** on a fresh install. `SETTING_AUTO_MODE_TIMEOUT_SECONDS` is **absent** from `vpn_setting_data_pro.xml` — nothing has written it yet, so the getter's `12` default applies. (The brief anticipated `value="12"`; the actual state is "absent → 12", which is the same observable behaviour and is correct.)

### 6.2 The range

Opening the picker produced a continuous list. First option:

```
'8 seconds'  [342,416][562,482]
'9 seconds'  [342,592][561,658]
'10 seconds' [342,768][581,834]
'11 seconds' [342,944][570,1010]
'12 seconds' [342,1120][578,1186]
…
```

**`8 seconds` is the first option — `5 seconds` does not exist.** Scrolled to the bottom:

```
'51 seconds' … '59 seconds'
'60 seconds' [342,1885][590,1951]
```

**`60 seconds` is the last option.** The range is exactly **8..60**, 53 entries, matching `(8..60).toList()` and `coerceIn(8, 60)` `[SOURCE CODE]`.

### 6.3 Selection state

The option rows are plain `TextView`s with `checkable="false"`, so the accessibility tree does not carry the selection. Verified visually instead: **12 seconds** renders with a **filled radio button and accent-coloured label**, every neighbour an empty outline. `Cancel` left the value at `12 s` — the dialog is not destructive.

---

## 7. V4 — dynamic path end-to-end

### 7.1 Setup

- Tunnel established: `tun0 inet 10.234.192.174/32`, `Connected MS-SSTP with IP: 10.234.192.174`, live up/down counters `[PHYSICAL DEVICE]`
- Toggle OFF: `vpn_always_prefer_vpn` **absent** → getter default `false`
- No manual servers ("No manual servers added")
- Server list populated: 99 servers from the fresh-install run

### 7.2 V4a — tunnel up, toggle OFF → PHYSICAL primary — **PASS**

```
[ladder] order=PHYSICAL,TUNNEL,EMERGENCY
[fetch]    path=physical source=mirror
[collector] path=physical source=mirror      ← delivered
[fetch]    path=physical source=api
[fetch]    path=physical source=html
[fetch]    path=physical failed (no result); falling back to tunnel
[collector] path=fallback-to-tunnel source=api
[fetch]    path=tunnel failed (no result)
```

Result: **101 servers**, "Last updated: 21 Oct 2026 - 12:05" (was 99 @ 11:46). The mirror source on PHYSICAL succeeded; api and html failed on both rungs but did not matter because records were non-empty, so `[ladder] all rungs failed` was correctly **not** emitted `[SOURCE CODE]`.

> **Note vs the brief's expectation.** The brief predicted `order=PHYSICAL,EMERGENCY`. The actual order is `PHYSICAL,TUNNEL,EMERGENCY`. This is **correct, not a deviation**: `routeLadder()` inserts the TUNNEL rung whenever `tunnelUsable && hasBoundNetwork`, and here the tunnel *is* up — so TUNNEL is eligible and simply ordered after PHYSICAL because the opt-in is off. With the tunnel down (fresh-install run at 11:46) the same code produced exactly `[ladder] order=PHYSICAL,EMERGENCY` `[PHYSICAL DEVICE]` + `[SOURCE CODE]`.

### 7.3 V4b — tunnel up, toggle ON → TUNNEL primary — **PASS (v1.0.44 T6b fixed)**

Toggle verified `checked=true`, and **both** preference stores carry the key — the FIX-1 two-store write proven on-device:

```
vpn_setting_data_pro.xml                    → vpn_always_prefer_vpn = true
vn.unlimit.vpngatepro_preferences.xml       → vpn_always_prefer_vpn = true
```

The second file is the store `GlobalVpnTracker.prefersVpnForCollector()` actually reads. Before v1.0.45 it never received the key, which is exactly why T6b failed. Now it does.

```
[ladder] order=TUNNEL,PHYSICAL,EMERGENCY
[fetch]    path=tunnel source=mirror
[collector] path=tunnel source=mirror        ← TUNNEL is the PRIMARY attempt
[fetch]    path=tunnel source=api
[fetch]    path=tunnel source=html
[fetch]    path=tunnel failed (no result); falling back to physical
[collector] path=fallback-to-physical source=api
[collector] path=fallback-to-physical source=html
[fetch]    path=physical failed (no result)
[fetch]    path=physical succeeded after tunnel failed
```

Result: **107 servers**, "Last updated: 21 Oct 2026 - 12:08".

**This closes the v1.0.44 T6b failure.** The toggle now reaches the reader, the ladder reorders, and the primary attempt is `path=tunnel`. The **STOP condition** ("V4b shows path=physical FIRST with toggle ON") did **not** trigger.

**It also demonstrates the recovery requirement.** The physical path was *not* blocked, yet the ladder still recovered automatically — tunnel failed, physical was tried second, the update completed, and **the DB was never wiped** (99 → 101 → 107 servers, never emptied). No manual toggle was needed to recover.

### 7.4 The tunnel rung still does not carry data — and this run cannot say why

Every tunnel attempt in V4b returned **"no result"** within milliseconds. **No `EPERM` was logged anywhere** in either scenario — so v1.0.44's P3 signature (`Binding socket to network N failed: EPERM`) **did not reproduce** in this build.

Two findings from the preference dump bear on this:

1. **`SelfExclusion` is `EXCLUDED`, not `UNKNOWN`.** The default store contains:
   ```xml
   <set name="ROUTE_EXCLUDED_APPS">
       <string>vn.unlimit.vpngatepro</string>
       <string>com.google.android.projection.gearhead</string>
   </set>
   ```
   The app is in its **own** exclusion list, so its traffic is deliberately kept off `tun0`. A socket explicitly bound to the VPN `Network` has no usable route in that configuration — a plausible mechanism for "no result" `[INFERENCE]`.

2. **The v1.0.45 bind diagnostics are invisible in a normal logcat.** `[bind-physical] ok/FAILED … errno=` and `[bind] …` are emitted through `CollectorLog.d`, and `CollectorLog.enabled` defaults to **`false`**; it is set from `dataUtil.getDeveloperMode()` in `App.onCreate` and toggled from Settings `[SOURCE CODE]`. Only `fetchLog`/`pathLog` (tag `VpnServerFetch`) are unconditional — which is why `[ladder]`/`[fetch]`/`[collector]` appear and the bind lines do not.

   **Consequence:** FIX-3 cannot attribute the tunnel failure unless **Developer Mode is enabled before the run**. That is a concrete gap for the next device pass, and it is why P3 remains `[UNKNOWN]` here.

### 7.5 V4c — PHYSICAL blocked → fallback — **NOT FORCED**

Deliberately blocking the physical path (e.g. dropping Wi-Fi while cellular carries the tunnel) was **not attempted**: it requires changing device network settings beyond the toggle/Wi-Fi allowance in the FORBIDDEN list, and is explicitly deferrable in the brief.

What *was* observed organically is the fallback **in both directions**:

| Scenario | Primary | Outcome | Fallback |
|---|---|---|---|
| V4a (toggle OFF) | PHYSICAL | physical failed | → **tunnel**, also failed |
| V4b (toggle ON) | TUNNEL | tunnel failed | → **physical**, **succeeded** |

So the "name the second rung and try it" machinery is real and exercised. The specific case "physical is *the only* blocked path and the tunnel is the *only* way out" remains **`[UNKNOWN]`** — and it cannot succeed today anyway, because the tunnel rung delivers no data (§7.4).

### 7.6 The `EMERGENCY` rung was not exercised

It could not be: V2 deliberately deleted the only configured source, and `VpnServerRepository.hasEmergencySources()` is a stub returning `false` `[SOURCE CODE]`, so the ladder logs `[ladder] skip=EMERGENCY (none configured)` and never reaches it. Consistent with §1.6.39 bullet 8 of the spec — the seeded **config row** is consumed by the update pipeline, not by the ladder.

---

## 8. Crash / ANR

**PASS.**

- 38,848 log lines scanned across **all** buffers (`logcat -b all`)
- `FATAL EXCEPTION` — **0**
- `ANR in` — **0**
- `Force finishing` — **0**
- App process PID **17595**, started **11:52:24**, still alive at **12:10** — it survived V3, V4a, V4b and the restore **without a single restart**
- No process death at any point after the two deliberate `force-stop`s in V1b/V2

---

## 9. What was NOT testable / not tested

| Item | Status | Why |
|---|---|---|
| V4c (physical blocked) | **NOT FORCED** | Needs network manipulation outside the allowed set; deferrable per brief |
| `EMERGENCY` rung behaviour | **NOT EXERCISED** | No source configured (V2 deleted it) **and** `hasEmergencySources()` is a stub → rung skipped by design |
| Bind/EPERM attribution | **`[UNKNOWN]`** | `CollectorLog` is off unless Developer Mode is enabled (§7.4) |
| Tunnel as a working transport | **`[UNKNOWN]`** | Every tunnel attempt returned "no result"; app is self-excluded from its own tunnel |
| Seed on a *second* fresh install | Not repeated | V1 covered it once; V1b/V2 covered the no-reseed paths |
| `:app` / `:SoftEtherClient` suites | Not run | FORBIDDEN (no build/test); already recorded 900/0/0/1 and 173/0/0/0 at the baseline |

---

## 10. Evidence labels

| Claim | Label |
|---|---|
| APK hash, versionCode/Name, debuggable flag | `[PHYSICAL DEVICE]` |
| Uninstall/install success, new UID, clean data dir | `[PHYSICAL DEVICE]` |
| Seed log line, timestamps, thread id | `[PHYSICAL DEVICE]` |
| DB row contents (url/label/enabled/createdAt) | `[PHYSICAL DEVICE]` — pulled DB, host-side `sqlite3` |
| Flag `SETTING_EMERGENCY_DEFAULT_SEEDED` | `[PHYSICAL DEVICE]` |
| Picker options 8..60, 12 selected | `[PHYSICAL DEVICE]` — uiautomator dump + screenshot |
| Two-store write | `[PHYSICAL DEVICE]` — both XML files read |
| Ladder order / primary path / fallback | `[PHYSICAL DEVICE]` — logcat tag `VpnServerFetch` |
| Server counts 99 → 101 → 107 | `[PHYSICAL DEVICE]` — UI screenshots |
| `SelfExclusion = EXCLUDED` | `[PHYSICAL DEVICE]` — `ROUTE_EXCLUDED_APPS` |
| `CollectorLog` gated by Developer Mode | `[SOURCE CODE]` — `App.kt:112–113`, `VpnModels.kt:85–94` |
| Why the tunnel rung fails | `[INFERENCE]` — not proven |
| V4c / EMERGENCY rung | `[UNKNOWN]` |

Raw evidence: `test-evidence/dv46/` — 56 files (`.log` logcat captures, `.xml` uiautomator dumps, `.png` screenshots). Screenshots and dumps were kept alongside the logs because UI selection state and picker bounds cannot be proven from text alone.

---

## 11. Negative assurance

Confirmed **not** done:

- No build of any kind was executed; the operator's gate APK was installed as-is.
- No commit, push, or tag was created. `HEAD` and `origin/main` remain `157445d`.
- No source, spec, or test file was edited.
- No device setting was changed except the in-app toggle and reading Wi-Fi/cellular state. No system network settings were touched.
- The **only** destructive action was the operator-confirmed uninstall required by V1; it is documented in §2.2. Nothing else was cleared.
- C5 was not executed.
- `D:\PMC\Documents\Android\SoftEthergai` was not touched.
- The `EMERGENCY` rung was not wired, the timeout default (12) and maximum (60) were not altered, and no native slot constants were changed.

**One documented deviation from the authorized file list:** evidence was written as `.xml` and `.png` in addition to `.log`, all inside the authorized `test-evidence/dv46/` directory, because a text log cannot evidence radio-button selection or exact picker bounds. No file was written outside `test-evidence/dv46/` and `DEVICE_VERIFY_v1.0.46_REPORT.md`.
