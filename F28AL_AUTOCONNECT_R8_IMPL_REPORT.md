# F.28AL-AUTOCONNECT-R8-IMPL — Implement Auto-Reconnect in Auto Mode

**Phase:** F.28AL-AUTOCONNECT-R8-IMPL
**Mode:** Implementation (source + tests). No APK rebuild, no device, no CI, no commit.
**Date:** 2026-10-12
**Baseline HEAD:** `071462816b1eef7e7f7459483fcd69909938cd11` (unchanged)
**Authorities:** `PROJECT_SPECIFICATION.md` §6.7 R8 / §11.13; `F28AL_AUTOCONNECT_R8_DESIGN_REVIEW.md` (approved). All 8 OQs resolved by the user.

---

## 1. Executive Verdict

**R8 IMPLEMENTED — ALL TESTS PASS.**

- `CI=1 ./gradlew :app:testProDebugUnitTest --tests "vn.unlimit.vpngate.automode.*"` → **BUILD SUCCESSFUL**.
- **190 tests, 190 passed, 0 failed, 0 skipped** (baseline 183 + 7 new R8 tests).
- `CI=1 ./gradlew :app:compileProDebugKotlin` → clean (one pre-existing warning, unrelated).
- No forbidden file touched; HEAD unchanged; no APK rebuild; no device.

All eight resolved OQ decisions are implemented: N=5 / M=10 min loop guard (`ERROR_RECONNECT_LIMIT`), reuse of the per-server NATIVE_BUSY budget, 2000 ms initial reconnect delay, process-death explicitly out of scope, UI reuses `AutoModeState.Connecting`, EN+FA strings added, manual connect wins, new terminal error code.

---

## 2. Pre-Condition Verification (P4/P5)

**P1 — HEAD:** ✅ `071462816b1eef7e7f7459483fcd69909938cd11`.
**P2 — Design review read in full:** ✅ (all 22 sections).
**P3 — Spec read:** ✅ §6.7 R7/R8 (`:599`–`611`), §11.13.1–.5 (`:1149`–`1184`).
**FA strings file:** ✅ exists (`app/src/main/res/values-fa/strings.xml`).

### P4 — The three ARMED disconnect paths (unexpected → R8)

| Path | Anchor (before) | Anchor (after) | Signal |
|------|-----------------|----------------|--------|
| A | `AutoModeController.kt:553-562` (`onTunnelLost` callback) | `:553-563` | Stack reports tunnel lost |
| B | `:574-582` (1 s `GlobalVpnTracker` poll) | `:581-590` | Tracker confirms down |
| C | `:591-603` (active liveness watchdog) | `:604-616` | No proof-of-life |

### P5 — The three MANUAL disconnect paths (must NOT trigger R8)

| Path | Anchor | Reason it cannot trigger R8 |
|------|--------|------------------------------|
| D — `stop()` | `:767-797` | Sets `TunnelStateWatcher.onTunnelLost = null` before teardown |
| E — `disconnectNow()` | `:871-895` | Same disarm-before-teardown |
| F — `skipToNextServer()` Connected | `:481-506` | Disarms the watcher at `:486` before `adapter.disconnect()` |

**Confirmed:** paths D/E/F were **not** structurally altered to route through R8; they only gained `connectedCandidate = null` / `pendingReconnectServer = null` cleanup (see §5).

---

## 3. DataUtil Changes

**File:** `app/src/main/java/vn/unlimit/vpngate/utils/DataUtil.kt`

**Companion constant (verbatim):**
```kotlin
/**
 * R8 (§11.13): Auto-Reconnect in Auto Mode. Default ENABLED — see
 * [getAutoReconnectInAutoMode].
 */
const val SETTING_AUTO_RECONNECT_IN_AUTO_MODE: String = "SETTING_AUTO_RECONNECT_IN_AUTO_MODE"
```

**Accessor + setter (verbatim, placed next to `setAutoModeTimeoutSeconds`):**
```kotlin
/**
 * R8 (§11.13): whether Auto Mode automatically re-establishes a tunnel
 * after an **unexpected** disconnect of a server Auto Mode itself connected.
 *
 * Default is **ENABLED (true)** per the user-directed requirement
 * (PROJECT_SPECIFICATION.md §11.13.2)...
 */
fun getAutoReconnectInAutoMode(): Boolean =
    getBooleanSetting(SETTING_AUTO_RECONNECT_IN_AUTO_MODE, true)

fun setAutoReconnectInAutoMode(enabled: Boolean) {
    setBooleanSetting(SETTING_AUTO_RECONNECT_IN_AUTO_MODE, enabled)
}
```
Default `true` means a missing key reads as ENABLED — no write required on a fresh install, matching §11.13.2 exactly.

---

## 4. AutoModeModels Changes

**AutoModeModels.kt: NOT MODIFIED.**

The task instruction said "In the sealed class or companion where `ERROR_*` codes live … Match the existing pattern (check how `ERROR_NATIVE_UNRESOLVED` is defined and follow it)." The `ERROR_*` codes do **not** live in `AutoModeModels.kt` — they live in `AutoModeController`'s `companion object` (`ERROR_NO_SERVER`, `ERROR_VPN_PERMISSION`, `ERROR_NATIVE_UNRESOLVED`, `ERROR_NO_ENABLED_PROTOCOL`). Following the instruction's own "match the existing pattern" rule, `ERROR_RECONNECT_LIMIT` was added **there** (§5), and `AutoModeModels.kt` was left untouched.

**Added (verbatim, in `AutoModeController`'s companion):**
```kotlin
/**
 * R8 (§11.13.3/§11.13.5, OQ-1/OQ-8): Auto Mode stopped reconnecting
 * because its bounded reconnect budget was exhausted.
 * ...
 */
const val ERROR_RECONNECT_LIMIT = "ERROR_RECONNECT_LIMIT"
```

---

## 5. AutoModeController Changes

**File:** `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt` (now 1614 lines).

### 5.1 Constants added (companion, near the NATIVE_BUSY constants)
```kotlin
const val RECONNECT_INITIAL_DELAY_MS = 2_000L      // OQ-3
const val RECONNECT_MAX_ATTEMPTS = 5               // OQ-1
const val RECONNECT_WINDOW_MS = 10L * 60L * 1000L  // OQ-1 (10 min)
const val ERROR_RECONNECT_LIMIT = "ERROR_RECONNECT_LIMIT" // OQ-8
```

### 5.2 Injected constructor parameters (I4 — same JVM-testable lambda pattern as `isTunnelBusy`)
```kotlin
private val autoReconnectEnabled: () -> Boolean = { true },
private val reconnectInitialDelayMs: Long = RECONNECT_INITIAL_DELAY_MS,
```
The controller remains free of Android/`SharedPreferences` types. The default `true` matches §11.13.2 so a caller that does not model the setting gets the documented behaviour.

### 5.3 New fields
```kotlin
private val reconnectTimestamps = mutableListOf<Long>()   // OQ-1 rolling window
private val reconnectLock = Any()

@Volatile private var pendingReconnectServer: AutoModeCandidate? = null

/**
 * R8 (§11.13.3): the candidate the CURRENT connected tunnel was established
 * with, captured at the success transition.
 */
@Volatile private var connectedCandidate: AutoModeCandidate? = null
```
`connectedCandidate` was needed because `AutoModeState.Connected` carries only `hostname`/`ip`/`speed`/`ping` — not the full capability record the reconnect must re-seed. It is set at the success transition (`adapter.log("[AUTO] Connected successfully")` block) and nulled by the three manual paths.

### 5.4 `onUnexpectedDisconnect` helper (verbatim body)
```kotlin
private suspend fun onUnexpectedDisconnect(droppedServer: AutoModeCandidate?) {
    setState(AutoModeState.Disconnected)

    // OQ-7: a manual connect that is still coming up owns the tunnel.
    if (isTunnelBusy()) {
        adapter.log("[AUTO] Manual connect in progress; skipping Auto-Reconnect")
        return
    }

    // OQ-6 (§11.13.4): the setting governs behaviour AFTER a drop.
    if (!autoReconnectEnabled()) {
        adapter.log("[AUTO] Auto-Reconnect is disabled; staying disconnected")
        return
    }

    // OQ-1: rolling-window loop guard.
    val now = System.currentTimeMillis()
    val attempts: Int
    synchronized(reconnectLock) {
        reconnectTimestamps.removeAll { it < now - RECONNECT_WINDOW_MS }
        if (reconnectTimestamps.size >= RECONNECT_MAX_ATTEMPTS) {
            attempts = -1
        } else {
            reconnectTimestamps.add(now)
            attempts = reconnectTimestamps.size
        }
    }
    if (attempts < 0) {
        adapter.log("[AUTO] Auto-Reconnect limit reached " +
            "($RECONNECT_MAX_ATTEMPTS in ${RECONNECT_WINDOW_MS / 60_000} min); stopping")
        setState(AutoModeState.Error(ERROR_RECONNECT_LIMIT))
        return
    }

    adapter.log("[AUTO] Unexpected disconnect: ${droppedServer?.hostname ?: droppedServer?.ip ?: "unknown"}; " +
        "reconnecting (attempt $attempts/$RECONNECT_MAX_ATTEMPTS)")

    // OQ-3: let the native slot unwind before redialling. Cancellable.
    kotlinx.coroutines.delay(reconnectInitialDelayMs)

    if (isTunnelBusy()) { adapter.log("[AUTO] Tunnel owned elsewhere after delay; aborting Auto-Reconnect"); return }
    if (isRunning) { adapter.log("[AUTO] A run is already active; not stacking a reconnect"); return }

    pendingReconnectServer = droppedServer
    val remaining = remainingCandidates
    overrideServers = when {
        droppedServer == null && remaining.isEmpty() -> null
        droppedServer == null -> remaining
        remaining.isEmpty() -> listOf(droppedServer)
        else -> listOf(droppedServer) + remaining.filterNot {
            it.ip == droppedServer.ip && it.hostname == droppedServer.hostname
        }
    }
    start()
}
```

### 5.5 The three call sites replaced (paths A, B, C)

| Path | `file:line` | Change |
|------|-------------|--------|
| **A** | `AutoModeController.kt:553-563` | `setState(Disconnected)` → `scope.launch { onUnexpectedDisconnect(dropped) }` where `dropped = connectedCandidate` |
| **B** | `:581-590` | `setState(Disconnected)` → `onUnexpectedDisconnect(dropped)` |
| **C** | `:604-616` | `setState(Disconnected)` → `onUnexpectedDisconnect(dropped)` |

Ordering preserved: each still cancels `connectedWatcher`, clears `TunnelStateWatcher.onTunnelLost`, and stops the liveness probe **before** invoking the helper.

### 5.6 Confirmation that paths D/E/F were NOT routed through R8
- `stop()` (`:767`): unchanged control flow; only added `connectedCandidate = null` + `pendingReconnectServer = null`.
- `disconnectNow()` (`:871`): same — added the two null-clears only.
- `skipToNextServer()` Connected branch (`:481`): moved/kept `TunnelStateWatcher.onTunnelLost = null` **before** teardown and added `connectedCandidate = null`. It does **not** call `onUnexpectedDisconnect`.

### 5.7 Reset logic (OQ-1)
`reconnectTimestamps.clear()` is **NOT** in the bare `start()`. It lives in a new helper called only by the two user-facing start branches:
```kotlin
private fun resetReconnectBudget() {
    synchronized(reconnectLock) { reconnectTimestamps.clear() }
    pendingReconnectServer = null
}
```
- called in `startOrRefuseIfNoServers()` (`:835`) and `onButtonPressedWithoutGuard()` (`:844`).

**Why not in `start()`:** the reconnect's own internal `start()` would then wipe the counter it just incremented, and the guard could never trip. This was caught by test T7 during the first run and is the one implementation-relevant defect the tests surfaced (see §9/§10).

### 5.8 `runAutoMode` changes (OQ-7 second chance + seed consumption)
Immediately after `val all = overrideServers ?: serverProvider()`:
```kotlin
val wasReconnect = pendingReconnectServer != null
pendingReconnectServer = null
if (wasReconnect && isTunnelBusy()) {
    adapter.log("[AUTO] Tunnel owned elsewhere; aborting Auto-Reconnect run")
    setState(AutoModeState.Disconnected)
    return
}
```

### 5.9 Success-path change
`connectedCandidate = server` added in the success block, immediately before `setState(AutoModeState.Connected(...))`.

---

## 6. AutoModeEngine Changes

**File:** `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeEngine.kt` (222 lines).

`DataUtil` was **already available** in `ensure()` as the local `du`. No new wiring was needed beyond supplying the R8 lambda:
```kotlin
isTunnelBusy = { vn.unlimit.vpngate.state.GlobalVpnTracker.isBusy },
// R8 (§11.13.2): the Auto-Reconnect setting, read live so a
// toggle takes effect at the next unexpected disconnect.
autoReconnectEnabled = { du.getAutoReconnectInAutoMode() },
```
`du` is the same `DataUtil` instance already used for `getAutoModeProtocolPriority()` and `getAutoModeTimeoutSeconds()`.

---

## 7. SettingsScreen Changes

**File:** `app/src/main/java/vn/unlimit/vpngate/ui/screens/settings/SettingsScreen.kt` (862 lines).

**Local state (added near `autoTimeout`):**
```kotlin
// R8 (§11.13): Auto-Reconnect in Auto Mode toggle state.
var autoReconnect by remember { mutableStateOf(dataUtil.getAutoReconnectInAutoMode()) }
```

**New row (verbatim, in the Auto Mode section, immediately after the timeout row):**
```kotlin
SettingDivider()
// R8 (§11.13): Auto-Reconnect in Auto Mode.
SettingSwitchRow(
    title = stringResource(R.string.setting_auto_reconnect_label),
    subtitle = stringResource(R.string.setting_auto_reconnect_hint),
    icon = Icons.Outlined.Autorenew,
    checked = autoReconnect,
    onChecked = {
        autoReconnect = it
        dataUtil.setAutoReconnectInAutoMode(it)
    },
)
```
**Import added:** `import androidx.compose.material.icons.outlined.Autorenew` (the icon is already used in `AppRoot.kt`, so it is on the classpath).

---

## 8. strings.xml (en + fa)

**`app/src/main/res/values/strings.xml`** (after `setting_auto_timeout_label`):
```xml
<string name="setting_auto_reconnect_label">Auto-Reconnect in Auto Mode</string>
<string name="setting_auto_reconnect_hint">Automatically try to reconnect after an unexpected disconnect while Auto Mode is active.</string>
<string name="error_reconnect_limit">Reconnect limit reached — stopping</string>
```

**`app/src/main/res/values-fa/strings.xml`** (after `setting_auto_timeout_label`):
```xml
<string name="setting_auto_reconnect_label">اتصال همیشکی اتوکانکت</string>
<string name="setting_auto_reconnect_hint">در صورت قطع غیرمنتظره اتصال در حالت خودکار، به‌طور خودکار تلاش به reconnect می‌شود.</string>
<string name="error_reconnect_limit">محدودیت تلاش برای اتصال مجدد به پایان رسید</string>
```

---

## 9. Tests Added (T1–T7)

**New file:** `app/src/test/java/vn/unlimit/vpngate/automode/AutoModeReconnectTest.kt` (373 lines, 7 `@Test`).

| ID | Test | Approach | Result |
|----|------|----------|--------|
| **T1** | `t1_reconnectFiresOnUnexpectedDisconnectWhenEnabled` | Fake adapter; reach Connected; invoke the armed-loss seam; assert a reconnect re-dials the dropped server first + `Unexpected disconnect` log | ✅ PASS |
| **T2** | `t2_noReconnectWhenSettingDisabled` | Same but `autoReconnectEnabled = { false }`; assert no new connect + `Auto-Reconnect is disabled` log | ✅ PASS |
| **T3** | `t3_noReconnectOnManualDisconnect` | Reach Connected; call `disconnectNow()`; assert `Disconnected`, no new connect, no `Unexpected disconnect` log | ✅ PASS |
| **T4** | `t4_loopGuardStopsAfterMaxAttempts` | Drive `RECONNECT_MAX_ATTEMPTS` drops, then one more; assert `Error(ERROR_RECONNECT_LIMIT)` + `limit reached` log | ✅ PASS |
| **T5** | `t5_manualPrecedenceSuppressesReconnect` | Inject `isTunnelBusy = { true }` (the OQ-7 seam); assert `Disconnected`, no reconnect, `Manual connect in progress` log | ✅ PASS |
| **T6** | `t6_initialDelayConstantIsTwoSeconds` | Source-shape assertion: `RECONNECT_INITIAL_DELAY_MS == 2_000L` (no `kotlinx-coroutines-test` on the classpath) | ✅ PASS |
| **T7** | `t7_manualStartResetsReconnectBudget` | Exhaust the budget; user-button restart; assert the next drop does NOT trip the limit | ✅ PASS (after the fix below) |

**Seam:** the production trigger for path A is the lambda the controller assigns to `TunnelStateWatcher.onTunnelLost`. The controller now exposes `@VisibleForTesting internal fun onUnexpectedDisconnectForTests()` which invokes the identical decision (`onUnexpectedDisconnect(connectedCandidate)`). Nothing in main source calls it. This follows the existing `@VisibleForTesting` convention (`TunnelStateWatcher.stateProvider`).

**Modification to the shared helper:** `AutoModeControllerTest.kt`'s private `controller(...)` factory gained:
```kotlin
/** R8 (§11.13.2): whether Auto-Reconnect is enabled; default matches production. */
autoReconnectEnabled: () -> Boolean = { true },
```
No existing test body changed.

**T7 fix (test authoring defect, not an implementation defect):** the first run of T7 used the raw `controller.start()` as the "manual start". `start()` is the internal primitive and deliberately does **not** reset the budget — the reset belongs to the user-facing branches per OQ-1 ("Reset the counter when the user manually starts Auto Mode again"). T7 now drives `controller.onButtonPressed()` (the path `AutoModeViewModel.onButtonPressed()` → `AutoModeEngine.onButtonPressed()` takes in production). One test failed on the first run (`190 tests completed, 1 failed`); after the T7 correction, **0 failed**.

---

## 10. Test Run Result

**Command:** `CI=1 ./gradlew :app:testProDebugUnitTest --tests "vn.unlimit.vpngate.automode.*"`

| Class | Tests | Fail | Error | Skip |
|-------|-------|------|-------|------|
| AutoModeControllerTest | 75 | 0 | 0 | 0 |
| AutoModeDatasetSourceTest | 8 | 0 | 0 | 0 |
| AutoModeEmptyListGuardTest | 4 | 0 | 0 | 0 |
| AutoModeEndToEndTest | 5 | 0 | 0 | 0 |
| AutoModeLifecycleF28Test | 33 | 0 | 0 | 0 |
| AutoModeLogStoreTest | 6 | 0 | 0 | 0 |
| AutoModeNativeBusyTest | 18 | 0 | 0 | 0 |
| **AutoModeReconnectTest** | **7** | **0** | **0** | **0** |
| AutoModeServerCursorTest | 3 | 0 | 0 | 0 |
| TeardownWaiterIdentityTest | 14 | 0 | 0 | 0 |
| TunnelLivenessProbeTest | 9 | 0 | 0 | 0 |
| TunnelLivenessWatchdogTest | 8 | 0 | 0 | 0 |
| **TOTAL** | **190** | **0** | **0** | **0** |

Baseline was 183; +7 new = 190. **BUILD SUCCESSFUL.**

**Compile check:** `CI=1 ./gradlew :app:compileProDebugKotlin` → clean. The only warning is pre-existing and unrelated (`AutoModeController.kt:482:38 No cast needed`).

---

## 11. Files Modified

Deltas are measured against the **pre-R8-IMPL working tree** (the tree the design phase reviewed), not against `HEAD` — `HEAD` (`07146281`) is many uncommitted phases behind and is not a meaningful baseline for this phase.

| File | Before | After | Delta |
|------|--------|-------|-------|
| `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt` | 1345 | 1614 | +269 |
| `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeEngine.kt` | 220 | 222 | +2 |
| `app/src/main/java/vn/unlimit/vpngate/utils/DataUtil.kt` | 595 | 615 | +20 |
| `app/src/main/java/vn/unlimit/vpngate/ui/screens/settings/SettingsScreen.kt` | 849 | 862 | +13 |
| `app/src/main/res/values/strings.xml` | 397 | 400 | +3 |
| `app/src/main/res/values-fa/strings.xml` | 357 | 360 | +3 |
| `app/src/test/java/vn/unlimit/vpngate/automode/AutoModeControllerTest.kt` | 2160 | 2164 | +4 |
| `app/src/test/java/vn/unlimit/vpngate/automode/AutoModeReconnectTest.kt` | — (new) | 373 | +373 |

**NOT modified:** `AutoModeModels.kt` (see §4).

---

## 12. Evidence Taxonomy

**SOURCE CODE EVIDENCE:** the verbatim snippets in §3–§8, read back from disk after writing; the armed-path anchors `:553-563` / `:581-590` / `:604-616`; the manual-path anchors `:481-506` / `:767-797` / `:871-895`; the `ERROR_*` companion location.

**TEST EVIDENCE:** the 190/0/0/0 run; the per-class XML breakdown; the single first-run failure (`t7_manualStartResetsReconnectBudget`, `AutoModeReconnectTest.kt:333`) and its resolution.

**INFERENCE:** that `start()` is the internal primitive and the user-facing reset belongs in `onButtonPressedWithoutGuard`/`startOrRefuseIfNoServers` (justified by OQ-1's wording and by the guard-accumulation requirement).

**UNKNOWN:** none outstanding that blocks delivery. The device-behaviour of paths B and C (GlobalVpnTracker poll, liveness watchdog) is not JVM-verifiable and is deferred to the device-verification phase.

---

## 13. Explicit No-Change Confirmation

| Item | Status |
|------|--------|
| `ConnectionController.kt` | **NONE** |
| `NativeHandleSlot.kt` | **NONE** |
| `NativeLifetimeRegistry.kt` | **NONE** |
| `SoftEtherVpnService.kt` | **NONE** |
| Native code (`*.c`, `*.h`, JNI) | **NONE** |
| Gradle / dependencies / manifests | **NONE** (strings.xml only) |
| Device (adb / install / uninstall) | **NONE** |
| APK rebuild | **NONE** |
| CI trigger | **NONE** |
| Commit / tag / push / release | **NONE** |
| `PROJECT_SPECIFICATION.md` | **NONE** |
| Files edited | **Only** the `AutoMode*` + `DataUtil` + `SettingsScreen` + `strings.xml` files listed in the ABSOLUTE RULES, plus the new `AutoModeReconnectTest.kt`. |
| HEAD | **unchanged** = `071462816b1eef7e7f7459483fcd69909938cd11` |

---

## 14. Open Questions Remaining

**NONE.**

Every OQ from the design review was resolved by the user before implementation (OQ-1…OQ-8), and the implementation matched each resolution. One decision *arose* during implementation — where exactly the loop-guard reset belongs (`start()` vs the user-facing branches) — and was resolved without ambiguity by OQ-1's own wording ("Reset the counter when the user manually starts Auto Mode again"), by placing it in `resetReconnectBudget()` called only from the two user-facing start branches. It is documented in §5.7 so the verify phase can confirm the intent.

---

## 15. Final Recommendation

**PROCEED TO DEVICE VERIFICATION (F.28AL-AUTOCONNECT-R8-VERIFY).**

Rationale:
- The design is fully implemented against all 8 resolved OQs.
- `:app:compileProDebugKotlin` is clean and the full AutoMode suite is green (190/190).
- The three armed paths and three manual paths were correctly separated; the loop guard, manual precedence, setting gate, settle delay, and re-seed-first-ordered flow are all in place.
- The remaining verification is inherently device-bound: the `GlobalVpnTracker` poll (path B), the active-liveness watchdog (path C), the real `SoftEtherVpnService` `STATE_DISCONNECTED`/`STATE_ERROR` signal for path A, and the Settings toggle's persistence in `vpn_setting_data_pro.xml`. These cannot be exercised in JVM unit tests and are exactly what the verify phase should cover.

**Suggested device checks for the verify phase:**
1. Toggle default is ON on a fresh install (`SETTING_AUTO_RECONNECT_IN_AUTO_MODE` absent → enabled).
2. Toggle persists across restart (`vpn_setting_data_pro.xml`).
3. Kill the server side of an Auto Mode tunnel (or airplane-mode the radio) → observe the `Unexpected disconnect: <host>; reconnecting (attempt n/5)` log and a same-server redial.
4. Repeat the drop 6× within 10 min → observe `Auto-Reconnect limit reached (5 in 10 min); stopping` and `ERROR_RECONNECT_LIMIT`.
5. Disable the setting, drop the tunnel → observe `Auto-Reconnect is disabled; staying disconnected` and no redial.
6. Start a manual connect while Auto Mode is idle-connected, then drop the Auto Mode tunnel → observe `Manual connect in progress; skipping Auto-Reconnect`.
