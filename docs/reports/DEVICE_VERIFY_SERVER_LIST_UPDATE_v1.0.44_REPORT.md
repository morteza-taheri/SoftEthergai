# DEVICE VERIFY — SERVER-LIST UPDATE — v1.0.44 — REPORT

> Phase B of BLOCK v1.0.44 (DIAGNOSTIC PLACEMENT + DEVICE VERIFY SERVER-LIST UPDATE).
> Status: **COMPLETE (with one FAIL and one NOT-DECISIVE)**. Untracked deliverable — not committed.
> Every claim is labelled: `[PHYSICAL DEVICE]`, `[SOURCE CODE]`, `[TEST]`, `[INFERENCE]`, `[PRIOR REPORT]`, `[UNKNOWN]`.

---

## §1 — Verdict

| Test | Expectation | Result | Basis |
|---|---|---|---|
| **T6a** | toggle OFF + tunnel up → `path=open` **first** | **PASS** | `[PHYSICAL DEVICE]` |
| **T6b** | toggle ON + tunnel up → `path=vpn` **primary** | **FAIL** | `[PHYSICAL DEVICE]` + `[SOURCE CODE]` |
| **T6c** | toggle ON + tunnel dead → `path=open` first (v1.0.41 gate) | **OBSERVED-CONSISTENT / NOT DECISIVE** | `[PHYSICAL DEVICE]` + `[INFERENCE]` |
| **T6d** | enriched `Request failed: [exception=…]` on EPERM | **PASS** | `[PHYSICAL DEVICE]` |
| Restore | toggle back to OFF | **DONE** (`checked=false`) | `[PHYSICAL DEVICE]` |
| Crash/ANR | none during the run | **PASS** (0 FATAL/ANR; PID stable) | `[PHYSICAL DEVICE]` |

**Headline:** the v1.0.44 EPERM diagnostic works on-device (T6d). But the **server-list VPN opt-in is still non-functional**: enabling "Route collector traffic via VPN" does **not** move the collector to the VPN rung. Root cause is a **SharedPreferences store mismatch** (§9) — the toggle writes to `vpn_setting_data_<flavor>` while the router reads `<pkg>_preferences`. The v1.0.41 VPN rung therefore remains **unreachable**, exactly the outcome T6b was designed to expose.

---

## §2 — Device & build identity

- Device serial: **`5585fecc`** `[PHYSICAL DEVICE]`
- Installed package: **`vn.unlimit.vpngatepro`** `[PHYSICAL DEVICE]`
- Installed APK SHA-256: **`339F344CB911E90AA25AA012B71C27FC0CAA968CA2E72527B8CDF585A6A90B68`** `[PHYSICAL DEVICE]`
  - Matches the fresh v1.0.44 build verified in Phase B STEP 1 `[PRIOR REPORT]`
- `versionCode=144`, `versionName=2.4.11-dev` `[PHYSICAL DEVICE]`
- App process PID `25491` (stable across the whole run — no restart/crash) `[PHYSICAL DEVICE]`
- Repo: `HEAD == origin/main == f8d314869051775ac113ffdbdd02440ed8c4b833`, 5 tags, **no tracked modifications** `[SOURCE CODE / git]`

> Note: earlier lookups used the wrong package id (`vn.unlimit.vpngate`); the correct installed id is **`vn.unlimit.vpngatepro`**. All final checks use the correct id.

---

## §3 — Pre-conditions

- `tun0` up throughout the active phases: `inet 10.211.1.4/16` (T6a) → `10.211.1.19/16` (T6b toggle-ON run) `[PHYSICAL DEVICE]`
- Collector opt-in default is **false** (pref absent) `[SOURCE CODE]` — confirmed behaviourally in T6a.
- The device is **not rooted** (`su` → `NO_SU`, shell uid 2000) and the app is **not debuggable** (`run-as` refused), so the pref could not be set/inspected directly; the toggle had to be driven through the app UI `[PHYSICAL DEVICE]`.

---

## §4 — T6a: toggle OFF + tunnel up → OPEN first — **PASS**

Evidence: `test-evidence/dv44b/t6a_off_tunnelup.log` `[PHYSICAL DEVICE]`

- First classification of the run is OPEN: `07:52:41.377 [fetch] path=open source=mirror` / `[collector] path=open source=mirror` (lines 2804–2805).
- Across every subsequent cycle, `path=open` is logged **first**; VPN appears only as `path=open failed (no result); falling back to vpn` (e.g. lines 3164–3177), and that fallback then fails on EPERM.
- Interpretation: with the opt-in OFF, `resolvePrimary` returns OPEN (`VpnServerRepository.kt:149–151`) `[SOURCE CODE]`. Matches expectation. **PASS.**

---

## §5 — T6b: toggle ON + tunnel up → VPN primary — **FAIL**

Evidence: `test-evidence/dv44b/t6b_toggled2.log`; UI state `ui2.xml` / `ui5.xml` `[PHYSICAL DEVICE]`

**Setup was verified, not assumed:**
- The in-app switch **"Route collector traffic via VPN"** was confirmed **`checkable=true, checked=true`** via `uiautomator` (Switch node bounds `[849,1336][992,1468]` after scroll; earlier `[849,750][992,882]`). `[PHYSICAL DEVICE]`
- `tun0` was up (`inet 10.211.1.19/16`). `[PHYSICAL DEVICE]`
- A controlled refresh was triggered (Refresh button `[574,241][640,307]`), logcat cleared first. `[PHYSICAL DEVICE]`

**Observed (toggle ON, tunnel up):**
- `08:29:14.190 [fetch] path=open source=mirror` / `[collector] path=open source=mirror` — **OPEN is still primary** (lines 11–12).
- `08:29:24.225` OPEN html+api again (lines 272–275).
- `08:29:26.866 path=open failed (no result); falling back to vpn` → `path=vpn failed (no result)` (lines 371–384).
- No `[collector] path=vpn` primary line anywhere.

**Conclusion:** with the opt-in ON and the tunnel healthy, the primary rung is still OPEN → `preferVpn` is effectively **false** at the router. This is a **FAIL** against the T6b expectation. Root cause in §9.

---

## §6 — T6c: toggle ON + tunnel dead → OPEN first (v1.0.41 gate) — **OBSERVED-CONSISTENT / NOT DECISIVE**

Evidence: `test-evidence/dv44b/t6b_inapp.log` `[PHYSICAL DEVICE]`

- Mid-session the tunnel genuinely dropped (`tun0` absent; `dumpsys connectivity` showed only Wi-Fi network 104 `PARS-M`, no VPN network). `[PHYSICAL DEVICE]`
- During that window: `08:12:35.198 [fetch] path=open failed (no result); no fallback (tunnel down)` (line 8190), with OPEN first at `08:12:21` / `08:12:31`.
- The **fail-closed** behaviour the v1.0.41 gate is meant to guarantee — OPEN chosen directly when the tunnel is dead, no wasted VPN attempt — is therefore **observed**. `[PHYSICAL DEVICE]`
- **However**, because `preferVpn` is permanently false (§9), OPEN-first with a dead tunnel is also the trivial default. The test therefore does **not independently prove** the gate; it is **not decisive** as a v1.0.41 verification until the opt-in actually reaches the router. `[INFERENCE]`

---

## §7 — T6d: enriched EPERM diagnostic — **PASS**

Evidence: `t6a_off_tunnelup.log` line 3175; `t6b_toggled2.log` line 382 `[PHYSICAL DEVICE]`

Observed on-device (new v1.0.44 format, exception class + message):

```
D VpnGateCollector: Request failed: [exception=SocketException] Binding socket to network 105 failed: EPERM (Operation not permitted)
D VpnGateCollector: Request failed: [exception=SocketException] Binding socket to network 106 failed: EPERM (Operation not permitted)
```

- Confirms the STEP-3 placement (the `catch (IOException)` in the fetcher, not `boundToNetwork`) and the enrichment are live in production. `[PHYSICAL DEVICE]`
- The pre-existing `[bind]` lines remain; control flow unchanged. `[SOURCE CODE]`

---

## §8 — Restore & stability

- Toggle restored to **OFF**: `checked=false` after tapping the switch (`ui6.xml`). `[PHYSICAL DEVICE]`
- Crash/ANR scan: **0** `FATAL EXCEPTION` / `ANR in vn.unlimit` across the captured buffers; PID `25491` never changed. `[PHYSICAL DEVICE]`
- VPN connection left as the operator had it (`tun0` up). No repo mutations. `[PHYSICAL DEVICE]`

---

## §9 — Root cause of the T6b failure — SharedPreferences **store mismatch**

`[SOURCE CODE]` — three files, all currently FROZEN:

1. **Writer** — `DataUtil.setPrefersVpnForCollector(enabled)` → `setBooleanSetting("vpn_always_prefer_vpn", enabled)` (DataUtil.kt:404–406, 314–318) writes to:
   ```kotlin
   sharedPreferencesSetting = mContext.getSharedPreferences(
       "vpn_setting_data_" + BuildConfig.FLAVOR, Context.MODE_PRIVATE)   // DataUtil.kt:40–43
   ```
   → file **`vpn_setting_data_<flavor>`**.

2. **Reader** — `GlobalVpnTracker.prefersVpnForCollector()` (GlobalVpnTracker.kt:526–528) reads:
   ```kotlin
   prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)  // :534
   prefs?.getBoolean(PREF_ALWAYS_PREFER_VPN, false)
   ```
   → file **`<packageName>_preferences`**.

3. **Consumer** — `resolvePrimary` returns `CollectorPath.VPN` only when `preferVpn && tunnelUsable` (VpnServerRepository.kt:149–151).

Same **key** (`vpn_always_prefer_vpn`), **different files**. The Settings toggle (`SettingsScreen.kt:559–562` → `dataUtil.setPrefersVpnForCollector`) writes to store #1; the router reads store #2, which is never written → `preferVpn` is always `false` → VPN rung unreachable.

The DataUtil doc comment (DataUtil.kt:388) claims it "reads the SAME preference key" — true about the key, but it overlooks that the **store** differs. T6b caught exactly this.

---

## §10 — Impact & recommended follow-up

- **Impact:** The v1.0.41 VPN routing rung is **still unreachable in production**, despite the v1.0.44 Settings toggle. Users who enable "Route collector traffic via VPN" get no behavioural change. `[INFERENCE from §9 + PHYSICAL DEVICE]`
- **Not fixed here:** `DataUtil.kt`, `GlobalVpnTracker.kt`, `SettingsScreen.kt` are **FROZEN** for this block, so no fix was applied.
- **Recommended fix (next block, minimal unfreeze):** pick ONE:
  - (a) Make `GlobalVpnTracker.prefersVpnForCollector()` read from DataUtil's store, **or**
  - (b) Make `DataUtil.setPrefersVpnForCollector()` also write `PreferenceManager.getDefaultSharedPreferences(...)`, **or**
  - (c) Point `SettingsScreen`'s toggle at the default-prefs writer.
  - Preferred: (b) — single-writer semantics preserved, and the frozen reader needs no change.
- **Re-run T6b/T6c** after the fix to obtain the decisive physical proof.

---

## §11 — Evidence index (`test-evidence/dv44b/`)

| File | Purpose |
|---|---|
| `t6a_off_tunnelup.log` | T6a — toggle OFF, tunnel up (PASS) + EPERM line |
| `t6b_on_tunnelup.log` | first toggle-ON attempt (toggle not yet engaged) |
| `t6b_retry_alwayson.log` | after *system* always-on VPN (not the in-app toggle) |
| `t6b_inapp.log` | in-app toggle attempt + tunnel-drop window (T6c evidence) |
| `t6b_final.log` | pre-toggle-propagated attempt |
| `t6b_toggled2.log` | **decisive T6b** — toggle `checked=true`, tunnel up, OPEN-first |
| `ui2.xml`/`ui5.xml` | Switch `checked=true` snapshots |
| `ui6.xml` | Switch restored `checked=false` |
| `screen_now.png`/`screen2.png`/`screen3.png` | visual state snapshots |
| `device_app.apk` | pulled installed APK (hash-matched) |

---

## §12 — Claim labels used

- `[PHYSICAL DEVICE]` — observed on device `5585fecc` via adb/logcat/uiautomator.
- `[SOURCE CODE]` — read from the repo at `f8d3148`.
- `[PRIOR REPORT]` — carried from Phase B STEP 1 verification.
- `[INFERENCE]` — reasoned conclusion from the above, not directly measured.
- `[UNKNOWN]` — not determinable with available access (e.g., cannot read the pref files: not rooted, app not debuggable).
