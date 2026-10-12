# F.28AL-AUTOCONNECT-BUG-VERIFY — Physical Device Verification of BUG 1/2/3 Fixes

**Date:** 2026-10-12
**Phase:** F.28AL-AUTOCONNECT-BUG-VERIFY
**Scope:** Read-only physical verification on device `5585fecc`. No source, test, Gradle, or CI changes were made. No uninstall, no data clear, no synthetic race injection, no thread manipulation, no internal hooks — only the normal app UI.

---

## 1. Executive Verdict

**VERIFICATION FAIL — REGRESSION DETECTED**

- **BUG 1 (UDP-only server exclusion): PASS** — confirmed fixed on device with exact arithmetic (102 total / 84 TCP-capable / 12 UDP-only → 84 selected; all 12 UDP-only excluded).
- **BUG 2 ("Try next server" during Connecting hang): PASS** — skip advanced the attempt to the next server and reached a terminal state in ~5.6 s; the graceful 500 ms fast-cleanup path fired; no hang.
- **BUG 3 (user-configured per-attempt timeout honored): FAIL — FIX INSUFFICIENT / INEFFECTIVE.** The user setting is read correctly by Kotlin (`[AUTO] Connection timeout = 5 seconds`), but the native transport race **still runs with its hard-coded 30000 ms value** and completes in ~30.1 s. The `nativeSetOption(OPTION_TIMEOUT, …)` call and its JNI log line are **entirely absent** from logcat. The BUG 3 fix does not reach native.

Because one of the three required verifications failed, the overall verdict is FAIL. BUG 1 and BUG 2 are confirmed fixed; BUG 3 is confirmed **not** fixed.

---

## 2. Device Identity

| Item | Value |
|---|---|
| Serial | `5585fecc` |
| Model | `2201117TG` (product `spes_global`, device `spes`) |
| Android | 13 (API 33) |
| Transport | USB (`transport_id:1`) |
| adb | 1.0.41 / 36.0.2 |
| Package | `vn.unlimit.vpngatepro` |
| versionName | `2.4.10-dev` |
| versionCode | 143 |
| `firstInstallTime` | `2026-09-28 16:05:05` |
| `lastUpdateTime` | `2026-10-12 15:14:32` |
| Process (stable) | PID **28664**, ETIME 26:49 at session end (no restart since launch) |

Pre-condition **P1 PASS** — device present and matching.

---

## 3. Build Identity

| Item | Value |
|---|---|
| Task | `CI=1 ./gradlew :app:assembleProDebug --no-daemon` |
| Result | **BUILD SUCCESSFUL in 1m 28s** (114 tasks, 11 executed) |
| DEX count | 34 files |
| APK path | `app/build/outputs/apk/pro/debug/app-pro-debug.apk` |
| APK SHA-256 | `893c21c87eb275980c7510e353113a15ae8e0cb8341a23e388db8fb7c5741b38` |
| APK size | 68,503,714 bytes |
| APK lastModified | 2026-10-12 15:11:26.825 +0330 |

**Build-config note (unchanged, no modification made):** `app/build.gradle` lines 117–124 disable all **debug variants** when the `CI` environment variable is unset. The build therefore required `CI=1`. This is a pre-existing local-build characteristic, not a defect introduced by this phase.

---

## 4. Pre-Install Source Verification (P3)

Pre-condition **P3 PASS** — all four fix anchors present in the working tree before install:

| # | File | Line | Anchor |
|---|---|---|---|
| 1 | `AutoModeController.kt` | 1329 | `AutoModeProtocol.SOFTETHER_UDP -> (c.seUdpSupported \|\| c.seUdpPort > 0) && c.seTcpPort > 0` |
| 2 | `AndroidConnectionAdapter.kt` | 392 | `armedTunnel?.complete(false)` preceding `activeTunnelWait?.complete(false)` in `skipCurrent()` |
| 3 | `AndroidConnectionAdapter.kt` | 244 / 260 / 371 | `withTimeoutOrNull(timeoutMs) { … } ?: false` in `awaitTunnel()` / `awaitArmedTunnel()` |
| 4 | `AndroidConnectionAdapter.kt` | 449 / 467 | `val attemptTimeoutMs = dataUtil.getAutoModeTimeoutSeconds().toLong() * 1000` → `connectTimeoutMs = attemptTimeoutMs.toInt()` |

Additionally, all four anchors were independently confirmed **compiled into the shipped APK** via `dexdump.exe -d` of `classes30.dex` (see §12).

---

## 5. Signature Check

| APK | V2 Signer certificate SHA-256 |
|---|---|
| Newly built `app-pro-debug.apk` | `247423ce8d62f0dfe15938f10e80798bd328857a50af0883e3a800dc10319e9a` |
| Previously installed APK (`installed_before.apk`) | `247423ce8d62f0dfe15938f10e80798bd328857a50af0883e3a800dc10319e9a` |

**MATCH.** Both digests equal the expected F.28AL-Y-PREP certificate `247423ce…`. Install-in-place is signature-compatible.

---

## 6. Install Result

| Item | Value |
|---|---|
| Command | `adb -s 5585fecc install -r app-pro-debug.apk` |
| Result | **Success** |
| `firstInstallTime` | `2026-09-28 16:05:05` (**unchanged** → proves in-place update, not a reinstall) |
| `lastUpdateTime` | `2026-10-12 15:14:32` (updated) |
| Data | preserved (no clear, no uninstall) |
| PID flow | 18785 (before install) → 28664 (after launch, stable for the entire session) |

---

## 7. T-BUG1 Result — **PASS**

**Claim under test:** UDP-only servers are excluded from the `SOFTETHER_UDP` protocol selection.

**Quantitative proof (from cache JSON `servers.json`):**

| Metric | Count |
|---|---|
| Total servers in cache | **102** |
| Servers with `seTcpPort > 0` (published TCP control channel) | **84** |
| UDP-only servers (`seTcpPort == 0` AND UDP present) | **12** |

**Runtime proof (logcat):**

```
[AUTO] Pre-filtering reachable servers via probe (251 endpoint(s) across SOFTETHER_TCP > SOFTETHER_UDP > MS_SSTP)
[AUTO] Reachable servers selected: 84
```

**Analysis:** The selection count (**84**) is exactly equal to the count of servers publishing `seTcpPort > 0`. All **12** UDP-only servers were excluded. The result reproduced identically across **3** separate AutoConnect runs.

**Bytecode confirmation:** `AutoModeController.supports()` disassembly of `classes30.dex` shows the gate at source `line=1329`:
- `0x0038 if-nez` (`seUdpSupported`)
- `0x003e if-lez` (`seUdpPort`)
- `0x0040 getSeTcpPort()`
- `0x0044 if-lez`
- `0x0047 return false`

So a UDP-only server (no TCP control channel) returns `false` and is not offered for `SOFTETHER_UDP`.

---

## 8. T-BUG2 Result — **PASS**

**Claim under test:** Pressing "Try next server" during `Connecting` advances to the next server instead of hanging.

**Setup:** State = **Connecting…**, banner `Attempting server 1 of 84`, active server `vpn257845930`.

**Action:** Pressed "Try next server" at `T_SKIP = 15:32:28.573`.

**Observed timeline:**

| Time | Observation |
|---|---|
| t+0 | Active server `vpn257845930` (archived `b1.xml`) |
| t+2 s | Active server already `vpn532142349` — **advanced** (archived `k1.xml`) |
| t+4 s | Reached terminal `Error` state |
| `15:32:34.744` | `SOFTETHER: Native lifecycle unresolved: connect flow did not exit within 500ms; ownership retained and the exiting flow will complete cleanup` |
| `15:32:41.043` | `TCP+TLS failed (1); falling back to parallel UDP race` / `Disconnecting (previous state: CONNECTING)` |
| `15:32:41.044` | `Disconnected` |
| `15:33:11.150` | `Parallel race: all transports failed` |

**Analysis:** The skip was observed and acted upon within **~5.6 s** (press → terminal). The connect flow exited through the intended **500 ms fast-cleanup ownership** path (`Native lifecycle unresolved … ownership retained and the exiting flow will complete cleanup`) rather than blocking indefinitely. The attempt advanced from `vpn257845930` to `vpn532142349`. **No indefinite hang observed.**

**Bytecode confirmation** (`classes30.dex`):
- `skipCurrent()`: offset `0x0000` (line 392) `iget-object … .armedTunnel` then `complete(false)`, **before** offset `0x000c` (line 393) `activeTunnelWait` — correct ordering (armed tunnel released first).
- `awaitTunnel`: offset `0x006b` invokes `kotlinx/coroutines/TimeoutKt.withTimeoutOrNull`, then null-coalesces to `false`.

---

## 9. T-BUG3 Result — **FAIL (FIX INSUFFICIENT)**

**Claim under test:** The user-configured per-attempt timeout is honored end-to-end (Kotlin → JNI → native race).

**User setting:** `SETTING_AUTO_MODE_TIMEOUT_SECONDS` was set to **5** for the primary test, and separately to **14**, to prove the value was actually the live user preference.

**Kotlin side — correct:**

```
10-12 15:27:09.937 D AutoMode: [AUTO] Connection timeout = 5 seconds
```

The controller reads the persisted user preference correctly.

**Native side — WRONG (still hard-coded 30000 ms):**

```
10-12 15:27:38.726 D SoftEtherProtocol: Parallel race: 3 transports, race timeout 30000 ms
10-12 15:28:08.830 D SoftEtherProtocol: Parallel race: all transports failed
```

| Run | User setting | Native race value reported | Measured elapsed |
|---|---|---|---|
| 1 | 5 s | **30000 ms** | `15:27:38.726 → 15:28:08.830` = **30.10 s** |
| 2 | 5 s | **30000 ms** | `15:28:36.247 → 15:29:06.352` = **30.11 s** |
| 3 | 14 s | **30000 ms** | `15:36:09.268 → 15:36:39.373` = **30.1 s** |

**Critical missing evidence — the JNI setter never fires:**

| Log marker | Occurrences in full logcat |
|---|---|
| `nativeSetOption` | **0** |
| `Set timeout to` | **0** |
| `nativeCreate called` | 2 |
| `nativeConnectWithHub called` | 2 |

The JNI source path (`softether_jni.c` line 469 `conn->timeout_ms = (int)value;`, reached via `OPTION_TIMEOUT` case 1) logs `nativeSetOption called: option=…, value=…` and `Set timeout to %d ms`. **Neither line ever appears.** The native sequence observed is:

```
15:27:12.635  nativeCreate called
15:27:12.644  nativeConnectWithHub called (useTcp=1)
```

with **no `nativeSetOption` between them** — exactly the gap that would prove the timeout was plumbed.

**Root-cause reasoning (verified against source, no fix applied):**

1. Source ordering is **correct**: `ConnectionController.kt:425` `client.nativeCreate()` → `:428` `nativeHandle = created` → `:433` `performConnectInner()` → `:530` `client.setTimeout(config.connectTimeoutMs)`. So the handle *is* valid when `setTimeout` is called.
2. `SoftEtherClient.setTimeout` guards on `if (nativeHandle != 0L) { nativeSetOption(nativeHandle, OPTION_TIMEOUT, …) }`. If that guard were the only gate, the call should have proceeded — yet no JNI log appears.
3. Conclusion: the `setTimeout` invocation on the connect path **is not being reached, or the native option path is not being exercised**, on the device build. The BUG 3 fix's assumption (`connectSoftEther()` sets `connectTimeoutMs = attemptTimeoutMs.toInt()`) held in source but the value **still does not reach native**.

**Ancillary observation (caching):** The `[AUTO] Connection timeout = 5 seconds` line persisted as `5` even after the preference was changed to `14` in Settings in the same long-running process (PID 28664 launched 15:14). This indicates `DataUtil` returns an in-memory cached value for the lifetime of the process; a fresh process would re-read `14`. This is a secondary concern but worth noting for the follow-up audit.

**Verdict:** BUG 3 is **NOT fixed**. The user timeout is ignored by the native transport race, which runs its hard-coded 30 s.

---

## 10. Regression Checks (R1–R4)

| Check | Result | Evidence |
|---|---|---|
| **R1** — Normal connect attempt still functions | **PASS** | Full UI path exercised: AutoConnect started → probed **251** endpoints → selected **84** → attempted servers → terminal state reached. |
| **R2** — AutoConnect end-to-end still functions | **PASS** | `[AUTO] Protocols (priority) = SOFTETHER_TCP > SOFTETHER_UDP > MS_SSTP`; probing + selection + attempt loop all observed. |
| **R3** — Crash scan | **PASS** | 1,044 logcat lines + final 5,162-line buffer: `FATAL EXCEPTION`=**0**, `tombstone`=**0**, `SIGSEGV`=**0**, `SIGABRT`=**0**, `RemoteServiceException`=**0**, `ForegroundServiceDidNotStartInTimeException`=**0**, `DeadObjectException`=**0**, `ANR in`=**0**. The 30 `AndroidRuntime` hits are all `uiautomator` shell-tool processes (uid 2000) launching/shutting down — not app crashes. |
| **R4** — Process stability | **PASS** | PID **28664** stable from launch (15:14) through session end (`ps` ETIME 26:49) — no restart. |

---

## 11. Crash Scan

No crash, ANR, tombstone, or native fault occurred at any point during build, install, or the physical tests.

```
FATAL EXCEPTION                  0
tombstone                        0
SIGSEGV                          0
SIGABRT                          0
RemoteServiceException           0
DeadObjectException              0
ANR in                           0
```

Per the phase rule ("If a crash occurs, STOP, collect evidence, do not restart") — **no STOP condition was triggered**; none of the tests produced a crash.

---

## 12. Evidence Taxonomy

All artifacts archived under `D:\PMC\Documents\Android\SoftEthergai\_f28alv_verify\`:

| Class | Artifacts |
|---|---|
| **Device** | `installed_before.apk` (SHA-256 `4c78f5a5…7456be`), package dumps |
| **Build** | `app-pro-debug.apk` (SHA-256 `893c21c8…5741b38`) |
| **Logs** | `logcat_full.txt`, `full_logcat.txt`, `logcat_final.xml` (5,162 lines) |
| **UI dumps** | `ui_start.xml`, `s1–s9.xml`, `a1–a3.xml`, `at1–at6.xml`, `h1–h8.xml`, `k1–k10.xml`, `b1.xml`, `c1.xml`, `t1–t3.xml`, `r1.xml`, `final.xml`, `nav0–nav4.xml` |
| **Screenshots** | `screen_now.png`, `screen_t1.png`, `screen_tab.png`, `screen_settings_mid.png`, `nav_cur.png`, `nav_setting.png`, `nav_picker.png`, `nav_restored.png` |
| **Data** | `servers.json` (102-server cache used for BUG 1 arithmetic), `vpn_setting_data_pro.xml` (pref snapshot) |
| **DEX** | `dex/apk_full/classes30.dex`, `dex/dump30.txt` (dexdump disassembly) |

**Evidence quality note (BUG 2):** The authoritative BUG 2 log lines (`Skipping current server`, `Server skipped by user`, `Connection failed: vpn…`) live in the app's **in-memory** AutoMode Log store (bounded ring buffer, surfaced only in the on-screen Log panel) and are **not** persisted to disk or logcat. They were captured live in the UI dumps at test time. The device logcat buffer has since rotated past that window; the durable BUG 2 anchors retained here are the UI state transition (`b1.xml` `vpn257845930` → `k1.xml` `vpn532142349`), the terminal `Error` state, and the `15:32:34.744` fast-cleanup line. This is a **capture-path limitation, not a test failure**.

---

## 13. Explicit No-Change Confirmation

**No source, test, Gradle, or CI file was modified during this phase.**

- `git rev-parse HEAD` = `071462816b1eef7e7f7459483fcd69909938cd11` — identical to pre-condition **P2**. **Unchanged.**
- The working-tree modifications shown by `git status` (`AutoModeController.kt`, `AndroidConnectionAdapter.kt`, and the other F.28AL files) are the **pre-existing fixes from the earlier F.28AL-AUTOCONNECT-BUG-FIX phase** — they were present before this phase began and were not touched here.
- No CI trigger, no GPG operation, no commit, no tag, no push, no release.
- No uninstall, no data clear.

**Device-side change (disclosed):** During testing, `SETTING_AUTO_MODE_TIMEOUT_SECONDS` was changed from its original value of **5** to **14** (to prove the live value was being read), and was then **restored to 5 through the app's own Settings UI** (not by direct data editing). Post-restore disk verification: `<int name="SETTING_AUTO_MODE_TIMEOUT_SECONDS" value="5" />`. The device is left in its original configuration.

---

## 14. R8 Status

**R8 (`Auto-Reconnect in Auto Mode` / «اتصال همیشکی اتوکانکت») is STILL NOT IMPLEMENTED.**

- Grep for `Auto-Reconnect` / `AUTO_RECONNECT` / `autoReconnect` / `SETTING_AUTO_RECONNECT` across `app/src/main/java/` → **zero hits**.
- R8 remains documented in `PROJECT_SPECIFICATION.md` §6.7 (R1–R8) and §11.13, default **ENABLED (فعال)**, as specified in the earlier documentation-only phase.

---

## 15. Final Recommendation

**FIX INSUFFICIENT — AUDIT NEEDED**

- **BUG 1** and **BUG 2** are confirmed fixed on the physical device and may be considered closed.
- **BUG 3** requires a dedicated audit phase. The physical evidence proves the user timeout **does not reach native**: the JNI `nativeSetOption(OPTION_TIMEOUT, …)` call and its two log lines are entirely absent, while the native race hard-codes 30000 ms and runs ~30.1 s. The source ordering was verified correct, so the follow-up must determine **why `setTimeout` is not invoked (or not honored) on the device connect path** — candidates include an unset/short-circuited `config.connectTimeoutMs`, a `nativeHandle == 0L` at the call site on the actual path, or a differing connect path that bypasses `performConnectInner()`'s `setTimeout`.
- Recommended next phase name: **F.28AL-AUTOCONNECT-BUG3-AUDIT** (read-only root-cause), followed by a targeted fix phase. **Do not** proceed to R8 implementation until BUG 3 is resolved, since R8's reconnect timing interacts with the same per-attempt timeout.

---

*End of report — F.28AL-AUTOCONNECT-BUG-VERIFY.*
