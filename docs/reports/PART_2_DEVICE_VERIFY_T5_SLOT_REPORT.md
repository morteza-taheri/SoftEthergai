# PART-2-DEVICE-VERIFY-T5-SLOT — Report

**Phase:** `PART-2-DEVICE-VERIFY-T5-SLOT`
**Date:** 2026-10-13
**HEAD:** `fa8a53d20d4bc8ba1e233d473c1c7723cbadb71f`
**origin/main:** `fa8a53d20d4bc8ba1e233d473c1c7723cbadb71f`
**Branch:** `main`
**Device:** `5585fecc` (Xiaomi 2201117TG, Android 13 / API 33)
**Package:** `vn.unlimit.vpngatepro` 2.4.10-dev (143, debug-signed)
**Language:** English only.

---

## 1. Executive Verdict

```
SOFT-BOUND CONFIRMED ON DEVICE. T5-SLOT TEARDOWN LEAK FORCE-RELEASED AFTER 90 s BOUND; SLOT LOCKOUT RESOLVED AND A NEW ATTEMPT ADMITTED. NO CRASH. JVM TESTS PASS.
```

The new soft-bound fired **exactly once**, on a genuine TEARDOWN-stuck holder, carrying the correct label and fields, and the very next admission was granted — converting the previously permanent "cannot connect without force-stop" lockout into a bounded ~1.5-minute recovery.

| Sub-goal | Verdict |
|---|---|
| (A) Soft-bound fired after ~90 s of a TEARDOWN-stuck slot | **YES** — fired at 14:50:46.123 for `attempt=2` (held 211,904 ms past deadline) |
| (B) User can AutoConnect again after the force-release | **YES** — `attempt=3` admitted immediately (no slot refusal); see §9 nuance |
| (C) `[DIAG]` / `[REGISTRY]` logs emitted with correct fields | **VERIFIED** |
| (D) 4 new JVM unit tests pass | **PASS** |
| (E) No crash / tombstone / process death | **VERIFIED** |

**Additional finding (outside the soft-bound's scope, reported for completeness):** the *newly admitted* `attempt=3` subsequently stalled in `phase=CONNECTING` (TLS handshake) and never returned within the capture window. The soft-bound deliberately does **not** cover a CONNECTING-phase holder (see §5.1 of `DIAGNOSE_AND_BOUND_T5_SLOT_REPORT.md` and the `softBoundDoesNotFireForAConnectingHolderThatIsNotInTeardown` test), so it did not — and must not — fire for it. This is the same native-hang class in a different phase; it is **not** a regression of the TEARDOWN fix.

Evidence labels: **SOURCE CODE / TEST / PHYSICAL DEVICE / INFERENCE / UNKNOWN / NOT REPRODUCED**.

---

## 2. STEP 1 — Pre-conditions (PASS)

- `git rev-parse HEAD` = `fa8a53d20d4bc8ba1e233d473c1c7723cbadb71f` ✔
- `git rev-parse origin/main` = `fa8a53d…` ✔ (in sync)
- `git status --short` tracked delta = exactly the 3 authorized files; `?? DIAGNOSE_AND_BOUND_T5_SLOT_REPORT.md` untracked ✔
- `adb devices -l` → `5585fecc device` ✔
- `dumpsys package` → `versionName=2.4.10-dev`, `lastUpdateTime=2026-10-13 13:27:24`, `firstInstallTime=2026-09-28 16:05:05` ✔

---

## 3. STEP 2 — JVM tests (PASS)

**Command used (full module run, not the targeted fallback):**
```
./gradlew :SoftEtherClient:test --rerun-tasks --no-build-cache
```
- `BUILD SUCCESSFUL in 1m 45s` — **149 tests, 0 failures, 0 errors, 0 skipped** (all 8 test suites).
- `NativeLifetimeRegistryTest`: 26 tests, 0 failures.

**The 4 new tests, by name:**

| Test | Result |
|---|---|
| `softBoundLeavesTheSlotHeldWhileElapsedIsBelowTheBound` | **PASS** |
| `softBoundForceReleasesASlotStuckInTeardownPastTheBound` | **PASS** |
| `aForcedReleaseDoesNotLetTheObsoleteLeaseDisturbTheNewOne` | **PASS** |
| `softBoundDoesNotFireForAConnectingHolderThatIsNotInTeardown` | **PASS** |

Evidence: TEST (JVM, `testDebugUnitTest` XML).

---

## 4. STEP 3 — Build (PASS)

```
CI=true ./gradlew :app:assembleProDebug --rerun-tasks
```
- `BUILD SUCCESSFUL in 12m 38s` — 114 actionable tasks executed.
- **APK:** `app/build/outputs/apk/pro/debug/app-pro-debug.apk`
- **SHA-256:** `008121d1454ea0726fd09773662b426a02dec8a4e419234a8505b4119981e2d4`
- **Size:** 63,591,170 bytes (~60.6 MiB)

> **Deviation note (required, environment-only):** the brief's literal command `./gradlew :app:assembleProDebug --rerun-tasks` failed first with *"task 'assembleProDebug' not found in project ':app'"*. Root cause is in `app/build.gradle:117-124`: when the `CI` environment variable is **null**, the module's `androidComponents { beforeVariants(...withBuildType("debug")) { it.enabled = false } }` block **disables the debug variant**. Setting `CI=true` for the invocation re-enables it. **No Gradle/CMake/native/source file was modified** — this is an invocation-environment requirement, not a scope change.

Evidence: SOURCE CODE (build.gradle gating) + PHYSICAL DEVICE (artifact).

---

## 5. STEP 4 — Install in place (PASS)

```
adb -s 5585fecc install -r app/build/outputs/apk/pro/debug/app-pro-debug.apk
→ Performing Streamed Install
→ Success
```

| Field | Before | After | Verdict |
|---|---|---|---|
| `firstInstallTime` | 2026-09-28 16:05:05 | 2026-09-28 16:05:05 | **UNCHANGED** ✔ (no unintended uninstall) |
| `lastUpdateTime` | 2026-10-13 13:27:24 | 2026-10-13 14:46:00 | advanced ✔ |

No uninstall, no data clear. Evidence: PHYSICAL DEVICE.

---

## 6. STEP 5 — Logcat capture (managed background task)

- Managed background task (not a plain shell `&`): `adb -s 5585fecc logcat -v threadtime > _t5_part2_run.log`
- App PID: **10062** (stable across the whole session; no restart).
- Buffer cleared (`logcat -c`) immediately before the capture started.

---

## 7. STEP 6 — Reproduction (REPRODUCED)

The user drove the phone; the stuck state occurred naturally within the session.

**First refusal (deadline not yet expired), verbatim:**
```
10-13 14:47:14.383 10062 10062 W SoftEtherVpnService: Start refused: native slot still held by an unresolved attempt (attempt=2 phase=TEARDOWN)
```
**AutoMode refusal, verbatim (the target line):**
```
10-13 14:47:14.669 10062 10250 D AutoMode: [AUTO] Native slot busy; retrying same server in 2s (attempt 1/2) (holder=attempt=2 phase=TEARDOWN)
```
**From 14:47:16.694 the refusal gained the deadline-expired suffix** (proving `deadlineExpired == true` for the holder):
```
10-13 14:47:16.694 10062 10062 W SoftEtherVpnService: Start refused: native slot still held by an unresolved attempt (attempt=2 phase=TEARDOWN, reporting deadline expired (still blocked))
```

- Timestamp of first refusal: **14:47:14.383** (AutoMode line 14:47:14.669)
- Holder attempt id: **attempt=2**
- Holder phase: **TEARDOWN** ✔
- Stuck continuously 14:47:14 → 14:50:46 (≈3 min 32 s), across ≥13 "Native slot busy" refusals.

**`[DIAG]` evidence that the holder's native call never returned:**
```
10-13 14:47:12.880 10062 10237 D ConnectionController: [DIAG] nativeConnectWithHub entry (attempt=2, ts=1791890232880)
10-13 14:47:14.720 10062 10255 D ConnectionController: [DIAG] teardown wait exceeded; flow still running (attempt=2, waitedMs=500)
```
→ **entry with NO matching exit for attempt=2** — H2 (genuine wedge) confirmed.

Evidence: PHYSICAL DEVICE + SOURCE CODE.

---

## 8. STEP 7 — Thread dumps

- `kill -3` from the shell user was **not permitted** (`kill: 10062: Operation not permitted`); re-issued via the app's own uid: `run-as vn.unlimit.vpngatepro kill -3 10062` → **exit 0**.
- Java thread dump confirmed requested: `14:47:46.377 … Thread[…,"Signal Catcher"]: reacting to signal 3`.
- **The dump body went to tombstoned, not logcat** (Android 13 routing):
  ```
  14:47:46.756   623   623 I tombstoned: received crash request for pid 10062
  14:47:46.768 10062 10070 I imit.vpngatepro: Wrote stack traces to tombstoned
  14:47:46.769   623   623 E tombstoned: Traces for pid 10062 written to: trace_00
  ```
- `adb shell debuggerd -b 10062` → **`debuggerd: root is required`** (exit 1). `/data/tombstones/` → **Permission denied** (both `shell` and `run-as`). Device is not rooted, so the trace **content is not retrievable** without a `bugreport` or root.
- Capture wall-clock: kill -3 at **14:47:46**, debuggerd attempt at **14:47:47**.

> **Limitation recorded:** the Java stack body and the native backtrace are **UNKNOWN** on this non-rooted device. The decisive question ("did the call return?") is nevertheless answered by the `[DIAG]` entry-without-exit pair (§7), which is stronger than a stack snapshot. Note the "received crash request" line is tombstoned's generic wording for a SIGQUIT trace — **it is not a crash** (crash buffer is empty, §11).

Evidence: PHYSICAL DEVICE (delivery) + UNKNOWN (trace content).

---

## 9. STEP 8 — Soft-bound fired (PASS)

```
10-13 14:50:46.123 10062 10062 W NativeLifetimeRegistry: [REGISTRY] FORCED_RELEASE_AFTER_TIMEOUT: attempt=2 held for 211904 ms past deadline; native operation may still be running. Releasing slot to unblock new attempt.
```

- Timestamp: **14:50:46.123**
- Attempt id: **attempt=2** — matches the STEP 6 holder ✔
- Elapsed: **211,904 ms** (≈211.9 s) ≥ **90,000 ms** ✔
  - (The elapsed exceeds 90 s because the bound is evaluated only on the admission path; the user next tapped at 14:50:46, ≈211.9 s after the teardown request at ≈14:47:14.2 — exactly as designed.)
- Exactly **one** `[REGISTRY]` line in the whole capture (count = 1); exactly **one** `FORCED_RELEASE_AFTER_TIMEOUT` (count = 1).

Evidence: PHYSICAL DEVICE + SOURCE CODE.

---

## 10. STEP 9 — User recovery (B) — PASS

Immediately after the forced release, the new attempt was **admitted** (no "slot busy" refusal):
```
10-13 14:50:46.119 10062 10062 D SoftEtherVpnService: onStartCommand: action=vn.unlimit.softether.CONNECT
10-13 14:50:46.123 10062 10062 W NativeLifetimeRegistry: [REGISTRY] FORCED_RELEASE_AFTER_TIMEOUT: attempt=2 …
10-13 14:50:46.123 10062 10062 D SoftEtherVpnService: Starting VPN with config: 218.115.40.234:1795
10-13 14:50:46.124 10062 10578 D ConnectionController: Starting connection to 218.115.40.234:1795
10-13 14:50:46.129 10062 10578 D SoftEtherVpnService: State changed: CONNECTING
10-13 14:50:46.135 10062 10578 D ConnectionController: [DIAG] nativeConnectWithHub entry (attempt=3, ts=1791890446135)
10-13 14:50:46.757 10062 10578 D SoftEtherSocket: Connected to 218.115.40.234:1795
10-13 14:50:46.795 10062 10589 D SoftEtherVpnService: State changed: TLS_HANDSHAKE
```
The wedged `attempt=2 phase=TEARDOWN` slot was cleared and a fresh `attempt=3` proceeded into a real connect (socket connected, TLS handshake started). **Slot lockout resolved → user recovered: YES.**

**Nuance (honest reporting):** `attempt=3` then itself stalled in `phase=CONNECTING` (TLS handshake) with **no** `[DIAG]` exit line (exit-count for attempt=3 = 0), and subsequent taps were refused with `holder=attempt=3 phase=CONNECTING` (no deadline suffix):
```
10-13 14:50:51.082 10062 10547 D AutoMode: [AUTO] Native slot busy; retrying same server in 2s (attempt 1/2) (holder=attempt=3 phase=CONNECTING)
10-13 14:51:05.141 10062 10547 D AutoMode: [AUTO] Native slot still owned by an unresolved operation (attempt=3 phase=CONNECTING); stopping without blaming this server
```
This refusal is the **correct fail-closed behavior for an in-flight connect** and is precisely the case the soft-bound must not touch (mirrors the `softBoundDoesNotFireForAConnectingHolderThatIsNotInTeardown` test). It is the same native-hang class in the CONNECTING phase — a **separate** issue, outside the TEARDOWN-only soft-bound's scope. See §14.

---

## 11. STEP 10 & 11 — Exit stuck / crash scan (PASS)

- User asked to stop further attempts; ~30 s allowed for unwind.
- **Crash buffer: EMPTY.**
  ```
  adb -s 5585fecc logcat -b crash -d   →   (no output)
  ```
- Scan of the main capture for `FATAL EXCEPTION` / `AndroidRuntime` / `Fatal signal`: **none**. The only tombstone-related lines are (a) the SIGQUIT trace I deliberately triggered at 14:47:46, and (b) SELinux denials from my own `ls`/`cat` probes of `/data/tombstones`.
- App PID **10062 remained alive** throughout — **no process death**.
- **No crash / tombstone / process death: VERIFIED.**

Evidence: PHYSICAL DEVICE.

---

## 12. STEP 12 — Settings (no user setting changed)

The brief's named file `vn.unlimit.vpngatepro_preferences.xml` holds only runtime counters:
```xml
<long name="downloaded_data" value="2413254" />
<boolean name="ROOT_STATE" value="false" />
<boolean name="HOME_CONNECTOR" value="false" />
<long name="uploaded_data" value="400619" />
<string name="HOME_CONNECTED_IP"></string>
```
The real settings live in `vpn_setting_data_pro.xml`. Current values:
```
SETTING_DEVELOPER_MODE          = true
SETTING_SOFTETHER_MAX_CONNECTIONS = 8
SETTING_LOCALE_ANSWERED         = true
SETTING_AUTO_MODE_TIMEOUT_SECONDS = 5
USER_ALLOWED_VPN                = true
LAST_CONNECT_USE_UDP            = false
LAST_CONNECT_SOFTETHER_USE_UDP  = false
```
- **This phase executed no command that writes app settings.** No setting was changed by the operator or by this phase.
- The keys that changed during the session (`vpn_last_connection`, `LAST_CONNECT_*`, `AUTO_LAST_SUCCESS_*`, cache keys) are the app's **own runtime bookkeeping**, updated by normal connect activity (the user's AutoConnect taps), not by this phase.
- No device setting was altered to ease reproduction.

Evidence: PHYSICAL DEVICE.

---

## 13. STEP 13 — Capture stopped

- Managed logcat background task stopped (runtime 6m 35s).
- **`_t5_part2_run.log`** — path: `D:\PMC\Documents\Android\SoftEthergai\_t5_part2_run.log`; **size: 1,161,249 bytes**.

---

## 14. Change Inventory / No-Modification Confirmation

- **No source file modified during PART 2.** `git diff --name-only` is still exactly the 3 frozen PART 1 files; diffstat unchanged at **3 files, 287 insertions(+), 13 deletions(-)**.
- **HEAD unchanged** at `fa8a53d…`; `origin/main` unchanged.
- **No commit / push / tag created.**
- No uninstall / data clear; no synthetic race injection; **C5 not executed**; no Gradle/CMake/native change; no device-settings change.
- Artifacts written (all untracked, evidence only): `_t5_part2_run.log`, `_t5p2_stuck_window_excerpt.log`, `_t5p2_watch_result.txt`, `_t5p2_forced_result.txt`, `_t5p2_native_dump.txt`, `_t5p2_settings.xml`, `_t5p2_tests.log`, `_t5p2_build.log`, this report.

---

## 15. Deliverable Index

| # | Item | Where |
|---|---|---|
| 1 | STEP 2: command, counts, 4 tests by name | §3 |
| 2 | STEP 3: BUILD SUCCESSFUL, APK SHA-256 | §4 |
| 3 | STEP 4: firstInstallTime before/after, lastUpdateTime | §5 |
| 4 | STEP 6: holder log line verbatim + timestamp | §7 |
| 5 | STEP 7: thread-dump timestamps + routing | §8 |
| 6 | STEP 8: `[REGISTRY] FORCED_RELEASE_AFTER_TIMEOUT` verbatim | §9 |
| 7 | STEP 9: recovery log line verbatim | §10 |
| 8 | STEP 11: crash buffer | §11 |
| 9 | STEP 12: settings | §12 |
| 10 | Stuck-window logcat excerpt | `_t5p2_stuck_window_excerpt.log` |
| 11 | Capture file path + size | §13 |
| 12 | Per-sub-goal verdicts (A)–(E) | §1 |
| 13 | No commit / push / tag | §14 |
| 14 | No source file modified during PART 2 | §14 |

---

## 16. Recommendation

1. **Accept** the TEARDOWN soft-bound as device-verified: it fired once, correctly labelled, and unblocked a genuine leak.
2. **Open a follow-up driver** for the **CONNECTING-phase (TLS-handshake) stall** observed on `attempt=3` — same native-hang class, not covered by the current TEARDOWN-only bound. Decide whether the soft-bound should be extended to a CONNECTING holder with an expired deadline, or whether the native TLS-handshake path needs its own unblock.
3. Consider making the `[DIAG]` lines permanent (they were decisive here) or gating them behind `SETTING_DEVELOPER_MODE`.
4. If stack content is needed for the CONNECTING stall, capture via `adb bugreport` (root-free) or a rooted session, since `kill -3` routes to tombstoned on this device.

No push, no tag. Report in English only.
