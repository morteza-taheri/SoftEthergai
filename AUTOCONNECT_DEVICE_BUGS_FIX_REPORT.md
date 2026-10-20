# AUTOCONNECT-DEVICE-BUGS-FIX — Report (spec v1.0.40)

**Repository:** `C:\Dev\SoftEthergai` · **Branch:** `main`
**Baseline (pre-phase):** `6229a769a8a98397b5acf2e55717b3a248a9a097` — the pre-phase HEAD, identical to
`origin/main` when the phase started.
**Phase:** AUTOCONNECT-DEVICE-BUGS-FIX-v1.0.40 · **Date:** 2026-10-20
**Scope:** BUG-1 and BUG-3 fixed; BUG-2 **deferred**.
**Labels used throughout:** `SOURCE CODE` / `TEST` / `PHYSICAL DEVICE` / `INFERENCE` / `UNKNOWN` /
`PRIOR REPORT`.

---

## §1 Verdict

Two of the three device-reported Auto Mode defects are fixed at SOURCE CODE + TEST level, and the third
is deferred with its evidence gap named.

| Bug | Reported symptom | Status | Evidence |
|-----|------------------|--------|----------|
| **BUG-1** | Hero button reads "Disconnected" while the tunnel is up; the tap then does nothing | **FIXED** (source + test; **not** device-verified) | SOURCE CODE + TEST |
| **BUG-3** | Battery-optimisation dialog reopens on every press | **FIXED** (source + test; **not** device-verified) | SOURCE CODE + TEST |
| **BUG-2** | MS-SSTP is never attempted | **DEFERRED** — not fixed, not claimed | SOURCE CODE + UNKNOWN |

`:app` **837 / 0 / 0 / 1** (was 823 / 0 / 0 / 1 — +14, exactly the new tests); `:SoftEtherClient`
**173 / 0 / 0 / 0** (unchanged); `:app:assembleProDebug` **BUILD SUCCESSFUL** under `CI=true`; APK
SHA-256 `5bed6128ceb382e9619567eefd2ee989189c26ac2e36fc2efc566ec3bf60c91a`; version **unchanged** at
`versionCode 144` / `versionName 2.4.11-dev`. `PROJECT_SPECIFICATION.md` v1.0.39 → v1.0.40; §22
byte-identical; no tag.

---

## §2 Pre-conditions (verified before any edit)

- `git rev-parse HEAD` = `6229a769a8a98397b5acf2e55717b3a248a9a097` = `git rev-parse origin/main`.
  Evidence: SOURCE CODE.
- Working tree clean apart from pre-existing untracked entries (`.vscode/`, `.workbuddy-ai/`,
  `emergency/`, `scripts/`, `test-evidence/`, `TRIAGE_AUTOCONNECT_DEVICE_BUGS_REPORT.md`) — none of
  which this phase touched or staged. Evidence: SOURCE CODE.
- Spec header read `**Document version:** 1.0.39`. Evidence: SOURCE CODE.
- Local tag count 5, matching the recorded set. Evidence: SOURCE CODE.
- Test dependencies inspected: the app module has **JUnit 4 only** — no Robolectric, no Mockito, and
  `testOptions.unitTests` is unset. This constrained the design of the persisted-flag test (§7).
  Evidence: SOURCE CODE.

---

## §3 BUG-1 — root cause

The hero button was bound exclusively to `AutoModeState`: `AutoModeScreen.kt:140` observes
`viewModel.state`, `:360–363` passes it into `AutoConnectHeroButton`, and `:590–597` maps it to the
label. That state has exactly one producer chain — `AutoModeController.setState` (`:534–536`, with the
sole `Connected` publication at `:2050`) → `AutoModeEngine._state` (`:149–150`) — and a **manual**
connect (`DetailActivity.kt:318`) or a service-level reconnect never enters it. The button therefore
could not reflect such a tunnel.

The tap then failed for the opposite reason: the D-4 guard reads the *true* app-wide state
(`isTunnelBusy = { GlobalVpnTracker.isActiveOrBusy }`, `AutoModeEngine.kt:137`), so with the run in
`Disconnected` and a live tunnel it took the refuse branch and logged
`[AUTO] start refused: a tunnel is already active` (`AutoModeController.kt:1091–1094`) — no state
change, no user-visible effect. The mismatch was structural: **the button read a different source from
the guard.**

The correct reconciliation already existed one screen over: `HomeScreen` observes both
`AutoModeEngine.stateFlow` and `GlobalVpnTracker.vpnState` (`:125–128`) and `activeServerOf()` falls
back to `GlobalVpnState.status` for `CONNECTING`/`CONNECTED` (`:693–711`). The Auto Mode screen simply
did not use it. Evidence: SOURCE CODE (definitive); the symptom itself is PHYSICAL DEVICE (user
report).

---

## §4 BUG-1 — the fix: a combined display state

`app/src/main/java/vn/unlimit/vpngate/viewmodels/AutoModeViewModel.kt` now carries:

```kotlin
data class AutoModeDisplayState(
    val modeState: AutoModeState,
    val tunnelStatus: VpnConnectionStatus,
    val display: AutoModeState,        // what the hero button renders
    val tapMeansDisconnect: Boolean,
    val isExternal: Boolean,           // tunnel up, no Auto Mode run owns it
)

internal fun combineAutoModeState(
    modeState: AutoModeState,
    tunnelStatus: VpnConnectionStatus,
): AutoModeDisplayState
```

The rule, in order:

1. run is `Connected` → the run wins (even if the tracker has not caught up);
2. run is `Connecting` → the run wins;
3. tracker is `CONNECTED` → synthesized `Connected`, `isExternal = true`;
4. tracker is `CONNECTING` → synthesized `Connecting`, `isExternal = true`;
5. otherwise the run state passes through unchanged.

The ViewModel publishes `displayState: LiveData<AutoModeDisplayState>` from
`combine(AutoModeEngine.stateFlow, GlobalVpnTracker.vpnState)` — the same two sources `HomeScreen`
combines. `state` keeps its previous meaning and its previous consumer set. The synthesized states
carry `hostname = null`, `ip = null`, `protocol = SOFTETHER_TCP`, `speed = 0`, `ping = 0`: a
display-only placeholder, with `isExternal` as the truthful marker and the screen rendering
"Connected (manual)" for it. No server identity is fabricated — pinned by a test. Evidence: SOURCE CODE
+ TEST.

**One deliberate divergence from the triage's proposed shape.** The triage suggested keeping
`AutoModeState.Error`. This phase lets a live tunnel **outrank** `Error` (case 3 before any `Error`
pass-through). Rationale: the whole point of the fix is that the button and the D-4 guard agree. With a
tunnel up the guard refuses to start, so an `Error` button offering "Tap to retry" would re-create the
original mismatch from the other side. The error is preserved in `modeState`, so the error card still
shows. Evidence: SOURCE CODE + TEST + INFERENCE.

---

## §5 BUG-1 — screen wiring, and why the tap needs a new path

`app/src/main/java/vn/unlimit/vpngate/ui/screens/automode/AutoModeScreen.kt`:

- observes `viewModel.displayState` (defaulted through the same pure combiner) and renders
  `AutoConnectHeroButton(state = displayState.display, isExternal = displayState.isExternal, …)`;
- `AutoConnectHeroButton` gains `isExternal: Boolean = false` and selects the subtitle
  `auto_mode_state_connected_external` ("Connected (manual)") for an external Connected;
- `startAutoModeWithAllChecks()` reads `viewModel.displayState.value` **at click time** (not a
  composition snapshot) and branches: `Disconnected`/`Error` → the existing permission path;
  `isExternal` → `viewModel.disconnectExternalTunnel()`; otherwise → the existing
  `viewModel.onButtonPressed()` toggle.

`onButtonPressed()` cannot serve the external case: with the run in `Disconnected`/`Error` its D-4 guard
sees a busy tunnel and refuses (`AutoModeController.kt:1091–1094`), which is correct for a *start* but
leaves a "Connected" button that does nothing. The new
`AutoModeViewModel.disconnectExternalTunnel()` → `AutoModeEngine.stopAndDisconnect()` →
`AutoModeController.disconnectNow()` issues the teardown unconditionally: `disconnectNow()` only takes
its `isRunning` branch when a run is active, and in the `else` branch it still calls
`adapter.requestDisconnect()` + `adapter.disconnect()` and publishes `Disconnected`
(`AutoModeController.kt:1156–1160`). The controller is **frozen** and untouched — the fix only chooses
which of its existing entry points to call. Evidence: SOURCE CODE.

Everything else on the screen keeps reading `state`: the developer log (`formatLog`), the error card,
and the "Try next server" enablement — so an external tunnel does not make "Try next server" look
available when there is no run to skip. Evidence: SOURCE CODE.

---

## §6 BUG-3 — root cause

The battery gate was the live OS state and nothing else:

```kotlin
val isIgnoringBattery = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
if (!isIgnoringBattery) { showBatteryDialog = true; return }
```

(`AutoModeScreen.kt:201–209`), with `showBatteryDialog` a transient `remember` flag (`:142`) reset on
dismiss. There was **no** persisted "asked" preference anywhere in the repository — `DataUtil` had no
battery key at all, in contrast to `SETTING_LOCALE_ANSWERED` (`DataUtil.kt:534`) which is exactly the
"asked once" precedent. After "Later", the OS still reported "not exempt", so the next press re-ran the
same check and re-opened the same dialog; the same happened after "Configure" when the user backed out
or when a manufacturer reset the exemption. Evidence: SOURCE CODE + PHYSICAL DEVICE (the repetition).

---

## §7 BUG-3 — the fix: a persisted one-shot flag

`app/src/main/java/vn/unlimit/vpngate/utils/DataUtil.kt`:

```kotlin
const val SETTING_BATTERY_OPT_ASKED: String = "SETTING_BATTERY_OPT_ASKED"   // companion object

fun hasAskedBatteryOptimization(): Boolean = getBooleanSetting(SETTING_BATTERY_OPT_ASKED, false)
fun setAskedBatteryOptimization() = setBooleanSetting(SETTING_BATTERY_OPT_ASKED, true)
```

placed beside `isFirstRun()` / `markLocaleAnswered()` and following their exact
`getBooleanSetting` / `setBooleanSetting` pattern.

`AutoModeScreen.proceedWithPermissionsAndConnect()` now gates on
`!isIgnoringBattery && !viewModel.hasAskedBatteryOptimization()`, and the flag is written on **all
three** exits of the dialog: Configure (`onBatteryDialogDismissed()` before leaving for system
Settings), Later, and `onDismissRequest` (back / outside tap). Each exit still proceeds to the VPN
permission check exactly as before.

Stated plainly: the prompt is now offered **once per install**, not once per un-exempt episode. A user
who declines and later changes their mind reaches the same screen from system Settings. That is the
intended trade — the reported defect was the repetition, and no OS-level signal distinguishes "declined"
from "not yet asked". Evidence: SOURCE CODE + TEST.

**Test seam.** `DataUtil` gains an `internal constructor(sharedPreferences: SharedPreferences)` so the
flag accessors can be exercised without a `Context`. This module's unit tests are JUnit 4 only (no
Robolectric, no Mockito), and the primary constructor's only Android dependency is
`getSharedPreferences`; everything the flag accessors touch is the preferences store. Production code
path is unchanged. Evidence: SOURCE CODE.

---

## §8 BUG-2 — deferred, and what evidence would settle it

**Not fixed, and not claimed.** The triage could not decide between two explanations, and this phase
found nothing that decides it:

- *data gap* — only the HTML source supplies protocol columns
  (`VpnServerRepository.kt:252–255`, `providesProtocolColumns`); the API carries none and the bundled
  seed carries none, so a dataset built without a successful HTML fetch has no SSTP-capable rows;
- *selection gap* — would require MS_SSTP to be filtered out before selection, but the merger
  *strengthens* SSTP (`AutoModeController.kt:2236`) and the protocol is present in the priority editor
  for all six entries (`SettingsScreen.kt:1173`).

The distinguishing evidence is a **device log of one Auto Mode run with MS_SSTP enabled**, showing
whether any candidate carried `sstpSupported = true` / `sstpConnectPort > 0` before the protocol loop
began. No device run happened in this phase, so the user's dataset remains **UNKNOWN**. Any claim that
BUG-2 is fixed would be unsupported. Evidence: SOURCE CODE + UNKNOWN.

---

## §9 Tests added

14 new JVM tests, two new files, **no existing test modified or deleted**.

`app/src/test/java/vn/unlimit/vpngate/viewmodels/AutoModeDisplayStateTest.kt` (8, T-NEW-28a…h) —
exercises the pure combiner, with no Android and no tracker initialisation (`VpnConnectionStatus` is a
plain enum, so referencing it does not initialise `GlobalVpnTracker`, whose object initialiser needs
`Looper.getMainLooper()`):

| Test | Pins |
|------|------|
| `disconnected run and no tunnel stays Disconnected` | the quiet case is unchanged |
| `external tunnel while the run is Disconnected shows Connected` | **BUG-1 itself**; `isExternal`, `tapMeansDisconnect`, and **no** fabricated host/ip |
| `external tunnel while the run is Disconnected shows Connecting` | a tunnel still coming up is not "idle" |
| `a tunnel being torn down is not shown as Connected` | `DISCONNECTING` is not Connected |
| `run Connected is shown even when the tracker has not caught up` | the run owns the display |
| `run Connecting is shown even when the tracker already says Connected` | same, for Connecting |
| `an external tunnel after a run error still shows Connected` | the divergence of §4, and that the error is preserved |
| `an error with no tunnel stays Error and offers a retry` | Error + tap still means start |

`app/src/test/java/vn/unlimit/vpngate/utils/DataUtilBatteryFlagTest.kt` (6, T-NEW-29a…f) — drives the
flag through `DataUtil` over an in-memory `SharedPreferences`:

| Test | Pins |
|------|------|
| `a fresh install has not been asked` | default `false` |
| `setting the flag makes it read back as asked` | `true` after set |
| `the flag survives a new DataUtil over the same store` | a restart does not bring BUG-3 back |
| `the flag is stored under the documented key` | a typo'd key cannot silently disable the gate |
| `answering the language prompt does not answer the battery prompt` | no cross-talk |
| `answering the battery prompt does not answer the language prompt` | no cross-talk, other direction |

---

## §10 JVM tests (before / after)

| Suite | Before | After | Delta |
|-------|--------|-------|-------|
| `:app` (`testProDebugUnitTest`, 64 XML files) | 823 / 0 / 0 / 1 | **837 / 0 / 0 / 1** | **+14** = 8 + 6 |
| `:SoftEtherClient` (11 XML files) | 173 / 0 / 0 / 0 | **173 / 0 / 0 / 0** | 0 |

The single skip is unchanged (`HtmlParserOracleTest`). Both runs used `--rerun-tasks
--no-build-cache` under `CI=true`. Evidence: TEST.

---

## §11 Build + APK SHA-256

`CI=true ./gradlew :app:assembleProDebug --rerun-tasks --no-build-cache` → **BUILD SUCCESSFUL** in
5 m 39 s (114 tasks executed, no CMake retry needed).

- `aapt2 dump badging` → `package: name='vn.unlimit.vpngatepro' versionCode='144'
  versionName='2.4.11-dev'` — **the version is unchanged**, as required: no `build.gradle` or manifest
  was touched; `-dev` is the debug-variant append.
- APK: `app/build/outputs/apk/pro/debug/app-pro-debug.apk`, 63,762,714 bytes, SHA-256
  `5bed6128ceb382e9619567eefd2ee989189c26ac2e36fc2efc566ec3bf60c91a` (differs from v1.0.39's
  `79c10272…3c17`, as expected for a source change).

Evidence: TEST.

---

## §12 Diff-scope and §22 byte-identity

`git diff --stat` before the commits:

| File | Change |
|------|--------|
| `app/src/main/java/vn/unlimit/vpngate/viewmodels/AutoModeViewModel.kt` | +119 / −0 (display state, combiner, collector, two battery methods, external disconnect) |
| `app/src/main/java/vn/unlimit/vpngate/ui/screens/automode/AutoModeScreen.kt` | +60 / −6 (observation, render, tap branch, gate, three dialog exits, `isExternal`) |
| `app/src/main/java/vn/unlimit/vpngate/utils/DataUtil.kt` | +34 / −0 (key, two accessors, test seam) |
| `app/src/main/res/values/strings.xml` | +4 / −0 (one additive string + comment) |
| `app/src/test/java/vn/unlimit/vpngate/viewmodels/AutoModeDisplayStateTest.kt` | new, 8 tests |
| `app/src/test/java/vn/unlimit/vpngate/utils/DataUtilBatteryFlagTest.kt` | new, 6 tests |
| `PROJECT_SPECIFICATION.md` | +103 / −3 (v1.0.40) |

Nothing else was modified. Specifically untouched: `AutoModeController.kt`, `AutoModeEngine.kt`,
`GlobalVpnTracker.kt`, `HomeScreen.kt`, `AndroidConnectionAdapter.kt`, `app/build.gradle`, both
manifests, and the Room schema directory.

**§22 byte-identity.** `## 22. Build and Verification Methodology (Lessons)` extracted from its
heading to EOF: **6,504 bytes**, SHA-256
`adf12858b72ed13742252c512a8728e9141e415cc55d09c6409af4e8e622020e` — **identical** to the value recorded
at v1.0.39.

**§1.6.2–§1.6.31 untouched.** The spec diff is exactly five hunks — header (3 lines), the §1.6.1
push-state append, the new §1.6.32 (96 lines added), the §20 changelog row, and the §20 verification
row. No line inside §1.6.2–§1.6.31 appears as a deletion. Evidence: SOURCE CODE.

---

## §13 Negative assurance and what remains

**Not done in this phase:** no device, no adb, no logcat, no install, no tag operation, no force-push,
no history rewrite, no `git add -A` / `git add .`, no deletion of any file, no change to
`AutoModeController.kt` / `AutoModeEngine.kt` / `GlobalVpnTracker.kt` / `HomeScreen.kt` /
`AndroidConnectionAdapter.kt`, no change to any build or manifest file, no version bump, no Room schema
change.

**What remains open:**

1. **Device verification of BUG-1 and BUG-3** — both fixes are SOURCE CODE + TEST only. The check is
   short: connect manually from the server list, open Auto Mode, and confirm the button reads
   "Connected" with the "Connected (manual)" subtitle and that one tap disconnects; then press the Auto
   Mode button repeatedly on a non-exempt device and confirm the battery dialog appears exactly once.
2. **BUG-2** — needs the device log described in §8 before any fix can be designed.
3. **The `Error`-outranked-by-live-tunnel rule** (§4) is an INFERENCE about what the D-4 guard will do;
   a device run would confirm that a stale `Error` plus a live tunnel never shows a retry button.

**Recommendation.** Take the device run for item 1 before any further Auto Mode work: it is the only
remaining evidence that separates "the button now agrees with the guard" from "the button now agrees
with the guard on the one path I reasoned about".
