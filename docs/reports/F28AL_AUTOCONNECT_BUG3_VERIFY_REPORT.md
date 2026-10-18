# F.28AL-AUTOCONNECT-BUG3-VERIFY — Physical Verification of BUG 3 Fix

**Date:** 2026-10-12
**Phase:** F.28AL-AUTOCONNECT-BUG3-VERIFY
**Scope:** Read-only physical verification on device `5585fecc`. Rebuild + install + device test only. No source/test/Gradle changes, no uninstall/clear, no CI, no commit.
**Baseline:** HEAD `071462816b1eef7e7f7459483fcd69909938cd11` (unchanged at end of phase).

---

## 1. Executive Verdict

**VERIFICATION PASS — BUG 3 CONFIRMED FIXED ON DEVICE**

All three previously-absent artefacts that define the bug are now present, with the user's 5-second setting carried end-to-end:

| Evidence | Previous verify phase | This phase |
|---|---|---|
| `nativeSetOption called` in logcat | **0 occurrences** | **❯ present** — `option=1, value=5000` |
| `Set timeout to` in logcat | **0 occurrences** | **present** — `Set timeout to 5000 ms` |
| `Parallel race … race timeout` | `30000 ms` | **`5000 ms`** (matches the 5 s setting) |
| Measured race elapsed | ~30.1 s | **5.103 s / 5.104 s** |
| `Set half_connection` (duplex) | absent (no-op) | **present** — `Set half_connection to 0` |

Regression checks also pass: BUG 1 (`Reachable servers selected: 84`) and BUG 2 (skip advances in ~2.8 s, no hang) remain fixed. **No crash, no ANR, PID stable.**

---

## 2. Device Identity

| Item | Value |
|---|---|
| Serial | `5585fecc` |
| Model | `2201117TG` (product `spes_global`, device `spes`) |
| Android | 13 / API 33 |
| Package | `vn.unlimit.vpngatepro` |
| versionName | `2.4.10-dev` |
| versionCode | 143 |
| `firstInstallTime` | `2026-09-28 16:05:05` (**unchanged** → in-place update) |
| `lastUpdateTime` | `2026-10-12 16:03:36` |
| Process (stable) | PID **2567** (launched 16:04, ETIME 03:52 at test end — no restart) |

Pre-condition **P1 PASS.**

---

## 3. Build Identity

| Item | Value |
|---|---|
| Task | `CI=1 ./gradlew :app:assembleProDebug --no-daemon` |
| Result | **BUILD SUCCESSFUL in 31s** (114 tasks, 11 executed) |
| APK path | `app/build/outputs/apk/pro/debug/app-pro-debug.apk` |
| APK size | 68,503,714 bytes |
| APK SHA-256 | `0f8b9a4326fd033514394185316d9c2881c2b770c356b8deec4692ef6c7c2f9a` |
| lastModified | 2026-10-12 16:02:33.725 +0330 |
| Archived copy | `_f28alv_bug3_verify/app-pro-debug.apk` |

---

## 4. Pre-Install Source Verification (P3)

All fix anchors present in the working tree before build:

| Anchor | Location | Verified |
|---|---|---|
| `fun setTimeout(handle: Long, timeoutMs: Int)` | `SoftEtherClient.kt:217` | ✅ |
| `fun setHalfConnection(handle: Long, halfConnection: Boolean)` | `SoftEtherClient.kt:65` | ✅ |
| `client.setTimeout(nativeHandle, config.connectTimeoutMs)` | `ConnectionController.kt:535`, `:1197` | ✅ |
| `client.setHalfConnection(nativeHandle, …)` | `ConnectionController.kt:583`, `:1214` | ✅ |
| BUG 1: `(c.seUdpSupported \|\| c.seUdpPort > 0) && c.seTcpPort > 0` | `AutoModeController.kt:1329` | ✅ |
| BUG 2a: `armedTunnel?.complete(false)` | `AndroidConnectionAdapter.kt:392` | ✅ |
| BUG 2c: `withTimeoutOrNull(timeoutMs)` | `AndroidConnectionAdapter.kt:244`, `:260`, `:371` | ✅ |

**P2 PASS** (HEAD `07146281…`). **P4 PASS** (`SETTING_AUTO_MODE_TIMEOUT_SECONDS = 5`).

---

## 5. DEX Verification (B3)

`dexdump -d` on `classes13.dex` (the DEX holding the `ConnectionController` call sites) shows the new overloads being **invoked with the expected descriptors**:

```
00bfea: invoke-virtual {v0, v6, v7, v11}, Lvn/unlimit/softether/client/SoftEtherClient;.setTimeout:(JI)V        // method@0251
00c0be: invoke-virtual {v0, v10, v11, v7}, Lvn/unlimit/softether/client/SoftEtherClient;.setHalfConnection:(JZ)V // method@0250
00d3c2: invoke-virtual {v6, v12, v13, v14}, Lvn/unlimit/softether/client/SoftEtherClient;.setTimeout:(JI)V        // method@0251
00d6dc: invoke-virtual {v6, v12, v13, v14}, Lvn/unlimit/softether/client/SoftEtherClient;.setHalfConnection:(JZ)V // method@0250
```

- **`setTimeout:(JI)V`** = `(Long, Int) → void` — the new explicit-handle overload. ✔
- **`setHalfConnection:(JZ)V`** = `(Long, Boolean) → void` — the new explicit-handle overload. ✔

The `SoftEtherClient` class definition (with both overload names) is in `classes5.dex` (2 class-descriptor refs, `setTimeout` ×4, `setHalfConnection` ×3). Both are compiled and referenced. **B3 PASS.**

---

## 6. Signature Check

| APK | V2 Signer certificate SHA-256 |
|---|---|
| Newly built `app-pro-debug.apk` | `247423ce8d62f0dfe15938f10e80798bd328857a50af0883e3a800dc10319e9a` |
| Installed package (`installed_before.apk`) | `247423ce8d62f0dfe15938f10e80798bd328857a50af0883e3a800dc10319e9a` |

**MATCH** — equals the required `247423ce…319e9a`. Signature-compatible.

---

## 7. Install Result

| Item | Value |
|---|---|
| Command | `adb -s 5585fecc install -r app-pro-debug.apk` |
| Result | **Success** |
| versionName / versionCode | `2.4.10-dev` / `143` |
| `firstInstallTime` | `2026-09-28 16:05:05` (**unchanged** → in-place update, not a reinstall) |
| `lastUpdateTime` | `2026-10-12 16:03:36` (updated) |
| Data | preserved (no clear, no uninstall) |
| PID | pre-install 28664 → post-launch **2567** |

**Launch note (not a crash):** `am start -n …MainActivity` returned `SecurityException: … not exported from uid 10340` — a benign shell-permission restriction (the activity is not `exported`), not an app fault. Relaunched successfully via the LAUNCHER intent (`monkey -p vn.unlimit.vpngatepro -c android.intent.category.LAUNCHER 1`); PID 2567 live, UI rendered on the Auto Mode screen.

---

## 8. T4 — `nativeSetOption` Log

**Now fires.** Exact lines (threadtime), user setting = **5 s**:

```
10-12 16:05:07.885  2567  2862 D SoftEtherJNI: nativeSetOption called: option=1, value=5000
10-12 16:05:07.885  2567  2862 D SoftEtherJNI: Set timeout to 5000 ms
```

Reproduced on the second attempt (skip → new server):

```
10-12 16:06:34.941  2567  3422 D SoftEtherJNI: nativeSetOption called: option=1, value=5000
10-12 16:06:34.941  2567  3422 D SoftEtherJNI: Set timeout to 5000 ms
10-12 16:06:51.236  2567  3422 D SoftEtherJNI: nativeSetOption called: option=1, value=5000
10-12 16:06:51.236  2567  3422 D SoftEtherJNI: Set timeout to 5000 ms
```

- `option=1` = `OPTION_TIMEOUT` ✔
- `value=5000` = the user's 5-second setting ✔ (previous phase: absent entirely)
- One `nativeSetOption`/`Set timeout to` pair per connect attempt. ✔

**T4 PASS.**

---

## 9. T5 — Race Timeout Value

```
10-12 16:06:40.066  2567  3422 D SoftEtherProtocol: Parallel race: 3 transports, race timeout 5000 ms
```

Aggregate across both runs: **`race timeout 5000 ms` ×2** (no `30000 ms` occurrence anywhere).

- User setting: **5 s** → race reports **5000 ms**. ✔
- Previous phase: race reported **30000 ms** with the same setting.

**T5 PASS.**

---

## 10. T6 — Measured Elapsed Time

| Race | Start (`Parallel race: 3 transports`) | End (`all transports failed`) | Elapsed |
|---|---|---|---|
| 1 | `16:06:40.066` | `16:06:45.169` | **5.103 s** |
| 2 | `16:06:56.260` | `16:07:01.364` | **5.104 s** |

Both bounded by the **5 s** user setting (previous phase: ~30.10 s / ~30.11 s). **T6 PASS.**

---

## 11. T7 — Duplex Path

**Now exercised** (previously a silent no-op — the JNI line was absent):

```
10-12 16:05:07.891  2567  2862 D DuplexModeSelector: Duplex auto: cores=8 totalMemGB=5 network=WIFI => fullDuplex=true (capability=true linkStrong=true)
10-12 16:05:07.891  2567  2862 D SoftEtherJNI: Set half_connection to 0
10-12 16:05:07.891  2567  2862 D ConnectionController: Duplex mode: FULL (4x BOTH / directional split)
10-12 16:05:10.201  2567  2862 D SoftEtherProtocol: Server half_connection=0
```

- `setHalfConnection` now reaches native (`Set half_connection to 0`) — the fix's second half is confirmed effective. ✔
- The native duplex decision runs and the server responds (`Server half_connection=0`). ✔
- Reproduced on both subsequent attempts (`16:06:34.944`, `16:06:51.242`).

**T7 PASS** (observable from logcat).

---

## 12. T8 — BUG 1 Regression Check

```
10-12 16:05:05.384  D AutoMode: [AUTO] Pre-filtering reachable servers via probe (251 endpoint(s) across SOFTETHER_TCP > SOFTETHER_UDP > MS_SSTP)...
10-12 16:05:07.660  D AutoMode: [AUTO] Reachable servers selected: 84
```

Run 2 (after a partial refresh + one server already attempted):
```
10-12 16:06:32.601  D AutoMode: [AUTO] Pre-filtering reachable servers via probe (248 endpoint(s) across ...)
10-12 16:06:34.739  D AutoMode: [AUTO] Reachable servers selected: 83
```

- Run 1: **84 selected** — matches the prior phase's value exactly (102 total, 84 TCP-capable, 12 UDP-only excluded).
- Run 2: **83** — consistent (248 endpoint probe because one server was already in flight/consumed); no UDP-only server was ever attempted (`nativeConnectWithHub useTcp=1` only, and the pre-filter protocol list still prioritises `SOFTETHER_TCP`).

**T8 PASS** — BUG 1 fix intact.

---

## 13. T9 — BUG 2 Regression Check

Pressed "Try next server" at `16:06:32`:

```
16:06:32.171  D AutoMode: [AUTO] Connected server skipped by user; trying next
16:06:32.288  D SoftEtherVpnService: State changed: DISCONNECTED ip=10.211.1.15
16:06:34.941  D SoftEtherJNI: nativeCreate called        ← new attempt on a different server
```

- Skip registered → previous tunnel torn down → **next attempt started 2.77 s later**. **No hang.** ✔
- The run then continued through two further complete attempts (`…16:06:50 ERROR`, `…16:06:51 CONNECTING`, `…16:07:06 ERROR`), confirming the skip loop never wedged. ✔
- (This skip was pressed while **Connected**; the prior phase's BUG 2 evidence was the **Connecting** case. Both paths advance.)

**T9 PASS** — BUG 2 fix intact.

---

## 14. T10 — Crash Scan

Full device logcat: **1,486 lines** (`final_logcat.txt`).

| Marker | Count |
|---|---|
| `FATAL EXCEPTION` | **0** |
| `tombstone` | **0** |
| `SIGSEGV` | **0** |
| `SIGABRT` | **0** |
| `RemoteServiceException` | **0** |
| `ForegroundServiceDidNotStartInTimeException` | **0** |
| `DeadObjectException` | **0** |
| `ANR in vn.unlimit.vpngatepro` | **0** |

`AndroidRuntime` lines in the app process (PID 2567): **0**.

**PID stability:** `2567` from launch (16:04) through the end of all tests — `ps` ETIME **03:52**, no restart.

**T10 PASS.** No STOP condition triggered; no crash occurred.

---

## 15. T11 — Settings Restored

| Item | Value |
|---|---|
| `SETTING_AUTO_MODE_TIMEOUT_SECONDS` at P4 | **5** |
| Value used during the test | **5** (never changed) |
| `SETTING_AUTO_MODE_TIMEOUT_SECONDS` at end | **5** (on-disk verified) |

No restore was required — the value was never modified, and it was the very value the fix demonstrably plumbed through (`value=5000`). Device left in its original configuration.

---

## 16. Evidence Taxonomy

All artifacts in `D:\PMC\Documents\Android\SoftEthergai\_f28alv_bug3_verify\`:

| Class | Artifacts |
|---|---|
| **Build** | `app-pro-debug.apk` (SHA-256 `0f8b9a43…c7c2f9a`) |
| **DEX** | `dex/classes*.dex` (34), `dex/dump13.txt` (dexdump), `dex/dump30.txt` |
| **Logcat** | `run1_direct.txt` (1943 lines), `run2_a.txt`, `run2_b.txt`, `final_logcat.txt` (1486 lines), `run1_logcat.txt` |
| **UI** | `r1a_connected.xml`, `r1b.xml`, `final_ui.xml`, `v0_launch.png`, `final_screen.png` |

**Evidence classification:**
- **SOURCE CODE EVIDENCE:** P3 anchors (§4).
- **BINARY EVIDENCE:** DEX invocations `setTimeout:(JI)V` / `setHalfConnection:(JZ)V` (§5); signature digests (§6).
- **DEVICE RUNTIME EVIDENCE (PRIMARY):** `nativeSetOption`/`Set timeout to 5000 ms` (§8), `race timeout 5000 ms` (§9), 5.10 s elapsed (§10), `Set half_connection to 0` (§11), crash scan (§14).
- **INFERENCE:** none material — every claim in this report is directly observed.
- **UNKNOWN:** none.

---

## 17. Explicit No-Change Confirmation

- **Source:** NONE
- **Test:** NONE
- **Gradle:** NONE
- **Version:** NONE (versionCode 143 / versionName 2.4.10-dev unchanged)
- **Data cleared:** NONE
- **Uninstall:** NONE
- **Commit/tag/push:** NONE
- **CI:** NONE
- **Files created:** APK + logcat + UI dumps only (under `_f28alv_bug3_verify/`).

`HEAD` unchanged at `071462816b1eef7e7f7459483fcd69909938cd11`. `find` for `.kt`/`.c`/`.h`/`.gradle` files modified after 16:00 returned **no results**.

---

## 18. R8 Status

**R8 is still NOT implemented** — confirmed as expected. Grep for `Auto-Reconnect` / `AUTO_RECONNECT` / `autoReconnect` / `SETTING_AUTO_RECONNECT` across `app/src/main/java` and `SoftEtherClient/src/main/java` → **zero hits**.

---

## 19. Final Recommendation

**PROCEED TO R8 IMPLEMENTATION (F.28AL-AUTOCONNECT-R8-IMPL)**

BUG 3 is now confirmed fixed on the physical device, with BUG 1 and BUG 2 verified regression-free and no crash. All three F.28AL-AutoConnect bugs are closed on-device. The path is clear to begin R8 (`Auto-Reconnect in Auto Mode`, default **ENABLED**), whose reconnect timing depends on exactly the per-attempt timeout now proven to be honoured.

---

*End of report — F.28AL-AUTOCONNECT-BUG3-VERIFY.*
