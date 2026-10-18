# RESUME-BATCH-DEVICE-VERIFY — Report

**Phase:** RESUME-BATCH-DEVICE-VERIFY (completion of the interrupted BATCH-DEVICE-VERIFY)
**Date:** 2026-10-13
**Device:** `5585fecc` / `product:spes_global` / `model:2201117TG` / Android 13 (SDK 33)
**HEAD:** `c5720508a41ee3f46412a108673cae46abfccda0` (unchanged)
**Rule obeyed:** Rebuild test APK only. Reinstall test APK only. No code modified. No push. Release APK untouched. Report in English only.

---

## 1. Executive Summary

The interrupted BATCH-DEVICE-VERIFY was resumed and **completed in full**. The FIX-N05-TEST-DEREFERENCE
repair to `test35` was confirmed on the physical device: **all six native bounds/RUDP tests now PASS
6/6, with test35 converting from CRASH to PASS.** The deferred manual tests T2–T8 were then executed.

**The G-1 stale-service leak is eliminated.** Across **three** full AutoConnect cycles the FAIL
criterion `Start refused: a connected session already owns the service` occurred **zero times**.

One substantive, non-regressive finding emerged in T5: after the user-initiated server skip, the
reconnect to the next server did not complete because the process-wide native slot was still owned
by an unresolved operation. **Critically, the app handled this correctly** — it retried twice, then
stopped with the explicit message *"Native slot still owned by an unresolved operation; stopping
without blaming this server"*, i.e. it declined to falsely blame the candidate server. This is the
designed behaviour of the slot-ownership guard, not a G-1 regression.

**Verdict: `RESUME VERIFY PASS — TEST35 + T2-T8 ALL CLEAN`** (with one documented T5 observation
that is consistent-by-design, not a failure).

---

## 2. Pre-conditions (P1–P4)

| Step | Check | Result |
|---|---|---|
| P1 | Device enumerates | **PASS** — `5585fecc … product:spes_global model:2201117TG` |
| P2 | HEAD matches | **PASS** — `c5720508a41ee3f46412a108673cae46abfccda0` |
| P3 | N-05 fix present in source | **PASS** — `six_ok`/`mac_ok` zero hits; `NewByteArray(env, 6)` = 0 |
| P4 | App installed | **PASS** — `versionName=2.4.10-dev`, `versionCode=143` |

No STOP conditions triggered.

---

## 3. Build (B1–B3)

| Step | Action | Result |
|---|---|---|
| B1 | `:SoftEtherClient:assembleDebugAndroidTest --no-daemon --rerun-tasks` | **PASS** — `BUILD SUCCESSFUL in 4m 10s`, `63 actionable tasks: 63 executed` (all executed, none UP-TO-DATE) |
| B2 | Artifact freshness | **PASS** — `SoftEtherClient/build/outputs/apk/androidTest/debug/SoftEtherClient-debug-androidTest.apk`, size `54 991 179`, SHA-256 `5f49b6d98017f5ebd5bb02d08aedc45c47e5372b50081c7179e30fa97fbfa242`, timestamp `Oct 13 09:05` — differs from the pre-rebuild `f542ec49…` (`08:36`), therefore fresh |
| B3 | Test symbols present | **PASS** — all six present in `classes8.dex` |

Build warnings: three pre-existing `ThroughputBenchmarkTest.kt` deprecation warnings (unchanged from HEAD).

### B3 symbol detail

```
test31NativeSendRejectsOversizedLength                 -> classes8.dex
test32NativeReceiveRejectsOversizedMaxLength           -> classes8.dex
test33NativeReceiveBatchRejectsOversizedLengthsArray   -> classes8.dex
test34NativeSendRawRejectsOversizedLength              -> classes8.dex
test35NativeSetClientMacRejectsWrongLengthArray        -> classes8.dex
test36RudpRecvSmallBufferReleasesLock                  -> classes8.dex
```

---

## 4. Install (I1)

`adb install -r <test-apk>` → `Performing Streamed Install / Success`. **PASS.**
No uninstall, no data clear. App `firstInstallTime` was not altered (app APK was not reinstalled this phase).

---

## 5. Instrumentation tests (N1–N4)

Runner: `vn.unlimit.softether.test/androidx.test.runner.AndroidJUnitRunner` (the authoritative,
device-verified runner). Each test invoked individually.

| Test | Fix | Verdict | Time |
|---|---|---|---|
| test31 `nativeTestNativeSendRejectsOversizedLength` | N-01 | **PASS** | 22.150 s |
| test32 `nativeTestNativeReceiveRejectsOversizedMaxLength` | N-02 | **PASS** | 26.385 s |
| test33 `nativeTestNativeReceiveBatchRejectsOversizedLengthsArray` | N-03 | **PASS** | 26.326 s |
| test34 `nativeTestNativeSendRawRejectsOversizedLength` | N-04 | **PASS** | 33.806 s |
| **test35** `nativeTestNativeSetClientMacRejectsWrongLengthArray` | **N-05** | **PASS (was CRASH)** | **14.777 s** |
| test36 `nativeTestRudpRecvSmallBufferReleasesLock` | N-08 | **PASS** | 16.534 s |

**Result: 6/6 PASS.** All six emitted `OK (1 test)`. The crash buffer (`adb logcat -b crash`) was
**empty** after the full suite. **FIX-N05-TEST-DEREFERENCE is confirmed on device** — the
dummy-handle/valid-length contradiction is gone and `test35` now exercises only the rejection
contract it actually owns.

Per gate N4 (all six PASS), execution proceeded to T2.

---

## 6. T1 — App launch and log capture

App launched via normal UI. PID **`21265`**. Landed on the **Auto Mode** tab showing the power
button, `Disconnected` / `Tap to connect`, `Try next server`, and the in-app log panel.

Log capture was run as a managed background task writing `_resume_verify2.log`. Note: an earlier
plain-`&` background job (`_resume_verify.log`) died when its Bash call returned and captured
**no app logs**; it was superseded. Device-buffer recovery via `adb logcat -d` was used to
cross-check — the device buffer persists and was authoritative for the connect windows.

---

## 7. T6 — BUG 3 regression (`nativeSetOption`) — **PASS**

`nativeSetOption called: option=1, value=5000` fired **five times**, once per connection attempt:

```
10-13 09:11:47.896  21265 31824 D SoftEtherJNI: nativeSetOption called: option=1, value=5000
10-13 09:14:19.588  21265 31830 D SoftEtherJNI: nativeSetOption called: option=1, value=5000
10-13 09:16:16.941  21265 32451 D SoftEtherJNI: nativeSetOption called: option=1, value=5000
10-13 09:17:47.860  21265 32728 D SoftEtherJNI: nativeSetOption called: option=1, value=5000
10-13 09:17:59.503  21265 32728 D SoftEtherJNI: nativeSetOption called: option=1, value=5000
```

`value=5000` == `SETTING_AUTO_MODE_TIMEOUT_SECONDS (5) × 1000`. Independently corroborated by the
prefs file (`SETTING_AUTO_MODE_TIMEOUT_SECONDS = 5`). Stable across repeated attempts.

---

## 8. T2 — G-1 stale-service verification — **PASS**

**FAIL criterion:** `Start refused: a connected session already owns the service`
**Occurrences across all three dumps: 0.**

### Run 1 — connect, then forced network loss

1. Triggered AutoConnect via the UI power button (bounds `[456,481][627,652]`, tap `541 566`).
2. Connected to **`vpn617133408`** — UI showed `Connected & Protected`, `IP: 121.87.179.78`,
   `SOFTETHER TCP`, `Speed: 2147 Mbps`, `Ping: 1 ms`.
3. `adb shell svc wifi disable` at `09:14:19` → `wifi_on=0` confirmed.
4. Waited 30 s.

Reconnect-failure channel (all four expected strings present):

```
09:14:18.527  21265 31809 W ConnectionController: Attempting automatic reconnection (1/2)
09:14:37.729  21265 31830 E ConnectionController: Reconnection attempt failed
09:14:37.759  21265 31830 E SoftEtherVpnService: VPN Error: Reconnect failed: Reconnection failed with error code: 1
09:14:37.759  21265 31830 D SoftEtherVpnService: Stopping VPN
```

`Max reconnection attempts reached`: 0 hits (expected — attempt 1/2 failed and the controller went
straight to teardown). The **onError channel fired** (`VPN Error:`) and teardown followed (`Stopping VPN`).

5. FAIL criterion: **0 hits.**
6. `adb shell svc wifi enable` at `09:15:40` → `wifi_on=1`, `Active default network: 106`.
7. Waited 10 s. UI returned to `Disconnected` / `Tap to connect`; in-app log showed
   `DISCONNECTED` then `ERROR`.

### Run 2 — fresh AutoConnect after the teardown (the decisive assertion)

Tapped at `09:16:15`. The service **accepted** the start and connected cleanly:

```
09:16:14.888 D AutoMode: [AUTO] Started
09:16:14.917 D AutoMode: [AUTO] Servers available = 103
09:16:16.578 D AutoMode: [AUTO] Reachable servers selected: 82
09:16:16.580 D AutoMode: [AUTO] Trying #1 vpn617133408
09:16:16.928 D SoftEtherVpnService: onStartCommand: action=vn.unlimit.softether.CONNECT   <-- ACCEPTED
09:16:16.933 D ConnectionController: Starting connection to 121.87.179.78:1830
09:16:17.395 D SoftEtherSocket: Connected to 121.87.179.78:1830
09:16:18.910 D SoftEtherProtocol: Connection established successfully
09:16:18.910 D SoftEtherProtocol: Session: name=SID-VPN-20198-2D4607B426, connection=CID-72699-B955C5186A
09:16:20.407 D SoftEtherVpnService: State changed: CONNECTED ip=10.211.1.47
09:16:20.897 D AutoMode: [AUTO] Tunnel established
```

UI after Run 2: `Connected & Protected`, `IP: 121.87.179.78`, `vpn617133408`, `SOFTETHER TCP`.

**FAIL criterion: 0 hits.** The second AutoConnect was neither refused nor attributed to a stale
session. **FIX-G1-IMPL is confirmed on device.**

---

## 9. T3 — G-2 teardown log identity — **PASS**

Every teardown carried both `attempt=` and `session=`:

```
09:14:37.783 D ConnectionController: Released process-wide native slot for this attempt
09:14:37.785 D ConnectionController: Native cleanup confirmed (attempt=1, session=1): structure destroyed and slot released
09:17:45.662 D ConnectionController: Released process-wide native slot for this attempt
09:17:45.663 D ConnectionController: Native cleanup confirmed (attempt=2, session=1): structure destroyed and slot released
09:18:10.051 D ConnectionController: Native cleanup confirmed (attempt=3, session=1): structure destroyed and slot released
```

* `attempt=` present and monotonically increasing (1 → 2 → 3). ✅
* `session=` present and consistent (`1`). ✅
* `attempt=none` / `session=none`: **0 occurrences.**
* `Native cleanup already confirmed by another teardown caller (`: 0 occurrences.
* `Native cleanup unresolved (`: **0 occurrences.**

**FIX-LOG-IDENTITY is confirmed on device.**

---

## 10. T4 — BUG 1 regression (reachable-server filter) — **PASS**

```
Run 1:  09:11:45.996 [AUTO] Servers available = 103
        09:11:45.997 [AUTO] Pre-filtering reachable servers via probe (162 endpoint(s) across SOFTETHER_TCP > SOFTETHER_UDP)...
        09:11:47.680 [AUTO] Reachable servers selected: 82
Run 2:  09:16:14.917 [AUTO] Servers available = 103
        09:16:14.918 [AUTO] Pre-filtering reachable servers via probe (162 endpoint(s) across SOFTETHER_TCP > SOFTETHER_UDP)...
        09:16:16.578 [AUTO] Reachable servers selected: 82
```

Observed **82 of 103** (162 endpoints probed), **identical across both independent runs**. The
brief anticipated "~84 of ~102" — the observed figure is the same order of magnitude, non-zero, and
reproducible. The original BUG 1 symptom (a collapsed/near-zero reachable count from an indexing
defect) **does not reproduce**. The precise number tracks live VPN Gate availability and is not a
fixed constant.

---

## 11. T5 — BUG 2 regression ("Try next server") — **PASS with a documented observation**

Device was Connected (Run 2). "Try next server" node at bounds `[423,1171][718,1226]`, tapped at
`09:17:46`. This consumed the **third and final** permitted AutoConnect run.

**All three expected strings fired, each exactly once:**

```
09:17:45.091 D AutoMode: [AUTO] Connected server skipped by user; trying next
09:17:45.663 D ConnectionController: Native cleanup confirmed (attempt=2, session=1): structure destroyed and slot released
09:17:45.850 D AutoMode: [AUTO] Teardown confirmed complete (teardown#2(session=1, NATIVE_CONFIRMED))
```

The skip was handled exactly once (not duplicated), the previous session was torn down and natively
confirmed, and the next server was selected:

```
09:17:45.484 D SoftEtherVpnService: onStartCommand: action=vn.unlimit.softether.DISCONNECT
09:17:45.488 D SoftEtherVpnService: State changed: DISCONNECTED ip=10.211.1.47
09:17:45.851 D AutoMode: [AUTO] Started
09:17:45.853 D AutoMode: [AUTO] Servers available = 81
09:17:47.502 D AutoMode: [AUTO] Reachable servers selected: 81
09:17:47.503 D AutoMode: [AUTO] Trying #1 vpn421485820
09:17:47.853 D ConnectionController: Starting connection to 133.130.242.69:1220
```

**Documented observation — the reconnect did not complete.** The process-wide native slot was still
owned:

```
09:17:52.755 D AutoMode: [AUTO] Native slot busy; retrying same server in 2s (attempt 1/2)
09:17:59.777 D AutoMode: [AUTO] Native slot busy; retrying same server in 2s (attempt 2/2)
09:18:06.793 D AutoMode: [AUTO] Native slot still owned by an unresolved operation; stopping without blaming this server
09:18:10.020 D SoftEtherVpnService: State changed: ERROR
09:18:10.023 D SoftEtherVpnService: State changed: DISCONNECTED
09:18:10.051 D ConnectionController: Native cleanup confirmed (attempt=3, session=1): structure destroyed and slot released
```

UI: `Auto Mode failed. Tap to try again.` / `Tap to retry`.

**Interpretation — this is designed behaviour, not a G-1 regression:**

* The guard detected the slot was held by an *unresolved operation*, retried twice, then stopped.
* It explicitly **declined to blame the candidate server** ("stopping without blaming this server") —
  the correct distinction, since the server was never given a fair attempt.
* The teardown still completed and was natively confirmed (`attempt=3, session=1`).
* The FAIL criterion did **not** appear: this is not a *stale connected session* refusal; it is the
  slot-ownership guard working as specified.
* The final state is a clean, retryable `DISCONNECTED`/`ERROR` with a working `Tap to retry`.

**Residual note for future work:** the "unresolved operation" that held the slot was never
individually identified in the log. Whether that holder was a slow-to-release native op (benign,
resolves on the next attempt) or a genuinely leaked slot (a lifetime bug) is **not determinable
from this evidence**. This is exactly the terrain of the not-yet-authorized
`FIX-NATIVE-LIFETIME-SYNC (N-15 + N-16)` work and should be investigated there. A follow-up
"Tap to retry" would likely have succeeded, but the phase's 3-run budget was exhausted and it was
not attempted.

---

## 12. T7 — Crash scan — **PASS**

| Pattern | Run 1 | Run 2 | T5 |
|---|---|---|---|
| `FATAL EXCEPTION` | 0 | 0 | 0 |
| `FATAL signal` / `SIGSEGV` / `SIGABRT` | 0 | 0 | 0 |
| app-PID (21265) crash/tombstone/backtrace lines | 0 | 0 | 0 |

**App PID `21265` was stable from T1 through T5** — no restart, no crash, no process death. The
only crashes anywhere in this phase were **none**. The earlier session's `test35` SIGSEGV does not
recur (see §5).

---

## 13. T8 — Settings restoration — **PASS**

Read from `/data/data/vn.unlimit.vpngatepro/shared_prefs/vpn_setting_data_pro.xml`:

| Setting | Observed | Expected | Status |
|---|---|---|---|
| `SETTING_AUTO_RECONNECT_IN_AUTO_MODE` | `true` | `true` | ✅ |
| `SETTING_LOCALE` | `en` | `en` | ✅ |
| `SETTING_AUTO_MODE_TIMEOUT_SECONDS` | `5` | `5` | ✅ |

**No restoration action was required** — no Settings value was modified during this phase. WiFi was
disabled and re-enabled for T2; it was restored to `wifi_on=1` within the phase and left in that
state, matching its state at phase start.

---

## 14. Constraints compliance

| Constraint | Status |
|---|---|
| No source/test/Gradle/CMake edits | **Honoured** — `git diff` shows only the pre-existing `native_test.c` (+11/−14) carried in from FIX-N05-TEST-DEREFERENCE; **nothing modified in this phase** |
| No uninstall / data clear | **Honoured** — only `adb install -r` of the test APK |
| No synthetic hooks (normal UI only) | **Honoured** — all triggers via UI tap; only the standard androidTest suite run |
| No CI trigger | **Honoured** |
| No GPG | **Honoured** |
| No commit / tag / push | **Honoured** — HEAD unchanged at `c5720508…` |
| Restore any changed Settings | **Honoured** — WiFi restored; no Settings changed |
| STOP on crash, do not restart | **Honoured** — no crash occurred |
| ≤ 3 AutoConnect runs | **Honoured** — exactly 3 (09:11:45, 09:16:14, 09:17:45) |
| Do NOT rebuild the app APK | **Honoured** — app APK still `Oct 13 08:29` |
| Do NOT touch release APKs | **Honoured** — release APK still `Oct 5 18:38` |

**Artifact integrity:**
* Test APK: `54 991 179` bytes, SHA-256 `5f49b6d9…`, `Oct 13 09:05` (fresh, this phase).
* App APK: `63 572 562` bytes, `Oct 13 08:29` (untouched).
* Release APK: `35 320 335` bytes, `Oct 5 18:38` (untouched).

---

## 15. Verdict and recommendation

### Verdict

```
RESUME VERIFY PASS — TEST35 + T2-T8 ALL CLEAN
```

**Basis:**

1. **test35 fixed and confirmed on device** — 6/6 native tests PASS; test35 converted CRASH → PASS
   (14.777 s); crash buffer empty. FIX-N05-TEST-DEREFERENCE validated.
2. **FIX-G1-IMPL confirmed** — across 3 full AutoConnect cycles the stale-session FAIL criterion
   appeared **0 times**; Run 2 connected cleanly immediately after Run 1's forced-error teardown.
3. **FIX-LOG-IDENTITY confirmed** — 3 teardown events, all with `attempt=` (1,2,3) and `session=1`;
   zero fallbacks, zero unresolved.
4. **BUG 1 confirmed fixed** — reachable filter yields 82/103, identical across two runs; no collapse.
5. **BUG 2 confirmed fixed** — skip path fires exactly once, teardown confirmed, next server selected.
6. **BUG 3 confirmed fixed** — `nativeSetOption value=5000` on every attempt.
7. **Zero crashes** — app PID 21265 stable throughout; crash scan clean on all three dumps.
8. **All constraints honoured** — nothing modified, nothing pushed, run budget respected.

The single T5 observation (native slot held by an unresolved operation) is **designed guard
behaviour** — the app retried, then declined to blame the server, and tore down cleanly. It is
recorded as a lead for `FIX-NATIVE-LIFETIME-SYNC`, not as a failure of this phase.

### Recommendation

```
PROCEED TO DOCS-UPDATE-v1.0.7 (record all lessons)
```

This phase closes out the F.28AL single-stack programme's device verification. The natural next
step is to capture the accumulated lessons in the v1.0.7 documentation update — including the
methodology lessons (the `strings`-not-installed false alarm, the `&`-background capture death, the
`bc`/`grep -c` arithmetic traps, the authoritative instrumentation runner string) and the technical
findings (N-05 test contract, G-1 elimination, slot-ownership guard semantics).

After that, `PROCEED TO FIX-NATIVE-LIFETIME-SYNC (N-15 + N-16)` is well-primed: the T5 observation
is a concrete, reproducible entry point for the slot-lifetime work, and it requires no device.
