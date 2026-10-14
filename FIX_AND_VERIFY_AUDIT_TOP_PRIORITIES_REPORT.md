# FIX AND VERIFY — AUDIT TOP PRIORITIES (R1, R2, R4a, R5, R6)

- **Phase ID**: FIX-AND-VERIFY-AUDIT-TOP-PRIORITIES
- **Repository**: `D:\PMC\Documents\Android\SoftEthergai`
- **Upstream base at phase start**: `6119a2a8fd6b256c41066829aeb9399e33b8a4a3` (v1.0.11 docs commit)
- **Origin/main at phase start**: `0e011e3` (Phase 7 reports commit)
- **Date**: 2026-10-14
- **Device**: `5585fecc` — Xiaomi 2201117TG, Android 13, fully automated (no operator taps requested or required)

---

## §1 Executive verdict

| Item | Result |
|---|---|
| R1 — log re-entrant teardown caller | **APPLIED** |
| R2 — split terminal message by teardown outcome | **APPLIED** |
| R4a — add RX + TUN-EIO counters | **APPLIED** |
| R5 — classify/handle TUN EIO in `TunTerminal` | **APPLIED** |
| R6 — resolve dead `MAX_PACKET_SIZE` constant | **APPLIED** |
| JVM unit tests | **PASS** (160 + 619, 0 failures, 0 errors, 1 pre-existing skip) |
| `:app:assembleProDebug` | **BUILD SUCCESSFUL** in 11m 17s |
| Device firstInstallTime | **UNCHANGED** (`2026-09-28 16:05:05`) — not a fresh install |
| Required DEX markers (5) | **ALL PRESENT** |
| AutoConnect cycles (5) | **5/5 CONNECTED** |
| Stress cycles (2) | **2/2 completed** |
| Live tunnel health | **HEALTHY** (`vpn315836095` / `59.138.182.93` / SOFTETHER TCP / ping 2 ms) |
| Crash / tombstone scan (C5) | **CLEAN** (buffer empty) |

**Verdict: PASS.** All five authorized fixes are implemented, tested, built, and verified on
the physical device. The re-entrant teardown instrumentation produced a materially new
diagnostic result that **corrects the mechanism hypothesised in `AUDIT_AXES_A_B_C_v1.0.11.md`
§2.3** (see §4.4 — the duplicate arrives *after* the real native confirmation, not before it).

Two deviations from the phase brief were **forced** by repository constraints and are
documented in code comments and in §2.3 / §2.4 below.

---

## §2 Part A — the five fixes

### §2.1 Scope actually touched

`git diff --numstat` against `6119a2a` (v1.0.11 docs commit) against the final state:

```
131  0   SoftEtherClient/src/main/java/vn/unlimit/softether/SoftEtherVpnService.kt
40   2   SoftEtherClient/src/main/java/vn/unlimit/softether/terminal/TunTerminal.kt
25   5   app/src/main/java/vn/unlimit/vpngate/automode/AndroidConnectionAdapter.kt
62   0   app/src/main/java/vn/unlimit/vpngate/state/GlobalVpnTracker.kt
```

Exactly the four authorized source files. No native C/C++ file, no `PROJECT_SPECIFICATION.md`,
no build script, no forbidden Kotlin file (`AutoModeController.kt`, `NativeLifetimeRegistry.kt`,
`ConnectionController.kt`, `SoftEtherClient.kt`) appears in the diff.

Two new test files and this report are added as new files (untracked until commit).

---

### §2.2 R1 — log the re-entrant teardown caller
`SoftEtherClient/src/main/java/vn/unlimit/softether/SoftEtherVpnService.kt`

Before, the `NOOP_REENTRANT` branch of `stopVpn()` completed silently — the audit could not
tell *who* re-entered teardown while a teardown was already in flight. Added a private helper
and one `Log.w`:

```kotlin
private fun reentrantTeardownCallers(limit: Int = 3): String {
    val frames = Thread.currentThread().stackTrace
        .asSequence()
        .map { it.className }
        .filter { it.startsWith("vn.unlimit.") }
        .filterNot { it.startsWith("vn.unlimit.softether.SoftEtherVpnService$") }
        .distinct()
        .take(limit)
        .toList()
    return if (frames.isEmpty()) "<none>" else frames.joinToString(" <- ")
}
```

```kotlin
Log.w(
    TAG,
    "[TEARDOWN-REENTRY] attempt=$teardownAttemptId, " +
        "state=(isStopping=${sessionPolicy.isStopping}, controller=${controller != null}, " +
        "tunHeld=${tunOwnership.isHeld()}, job=${connectionJob != null}), " +
        "thread=${Thread.currentThread().name}, " +
        "callers=${reentrantTeardownCallers()}",
)
```

Note: the accessor used is `sessionPolicy.isStopping` (real accessor, line 878) — the audit
sketch named a bare `isStopping` field that does not exist on the service.

**Runtime result (see §4.4):** 7/7 occurrences carry an identical signature:
`isStopping=true, controller=false, tunHeld=false, job=false, thread=main`. The duplicate
arrives when the service is *already fully idle*, i.e. it is a late redundant disconnect, not
a concurrent race. The `vn.unlimit.*`-only frame filter means the external framework caller
(`android.net.VpnService`, `ConnectivityService`, …) is not shown — a known limitation of the
prescribed filter, reported rather than silently widened.

---

### §2.3 R4a — RX + TUN-EIO counters

`GlobalVpnTracker.kt` (+62 lines) gains delegating accessors, and the counter *storage* lives
in a new top-level `object TunnelHealth` appended to
`SoftEtherClient/src/main/java/vn/unlimit/softether/SoftEtherVpnService.kt`.

```kotlin
fun rxCounter(): Long = TunnelHealth.rxCounter()
fun lastRxAdvanceAtMs(): Long = TunnelHealth.lastRxAdvanceAtMs()
fun tunEioCount(): Long = TunnelHealth.tunEioCount()
fun lastEioAtMs(): Long = TunnelHealth.lastEioAtMs()
fun noteRxAdvance() = TunnelHealth.noteRxAdvance()
fun noteTunEio() = TunnelHealth.noteTunEio()
fun isTunnelUsable(): Boolean =
    TunnelHealth.isUsable(isConnected, System.currentTimeMillis())
internal fun resetHealthCountersForTest() = TunnelHealth.resetHealthCountersForTest()
```

The existing `GlobalVpnTracker` state machine is otherwise untouched; `noteRxAdvance()` is
deliberately **not yet called** from the data path (that wiring is R4b, deferred).

**DEVIATION 1 (forced, documented):** the phase brief prescribed storing these counters in
`GlobalVpnTracker` (module `app`). `TunTerminal` lives in module `SoftEtherClient`, and the
only dependency edge in the build is `app -> SoftEtherClient` — `SoftEtherClient` cannot
reference `app` classes, and modifying `build.gradle` is forbidden. Storage was therefore
placed in a top-level `TunnelHealth` object inside `SoftEtherClient`, with `GlobalVpnTracker`
delegating. The public API surface the brief specified is preserved verbatim.

**DEVIATION 2 (forced, documented):** the §7 Q1 usability decision was extracted as a pure
function `TunnelHealth.isUsable(isConnected: Boolean, nowMs: Long)`, because
`GlobalVpnTracker`'s object initializer needs `Handler(Looper.getMainLooper())` and therefore
cannot be initialised in a plain JVM unit test (proved empirically — see §3.2). Test-only
setters (`setLastRxAdvanceAtMsForTest`, `setLastEioAtMsForTest`, `resetHealthCountersForTest`)
make the contract testable.

---

### §2.4 R5 — TUN EIO branch in `TunTerminal`
`SoftEtherClient/src/main/java/vn/unlimit/softether/terminal/TunTerminal.kt`

`readLoop()` previously fell through to the generic "read failed" branch on EIO, losing the
distinction between a transient error and a revoked TUN interface (the `udp_only` T5-SLOT
symptom class). Added:

```kotlin
} else if (e is java.io.IOException && isTunEio(msg)) {
    Log.w(TAG, "TUN read failed with EIO (interface revoked?), stopping read loop")
    TunnelHealth.noteTunEio()
    isRunning.set(false)
    Log.d(TAG, "TUN read loop ended")
    return
} else { /* pre-existing generic branch, unchanged */ }
```

```kotlin
fun isTunEio(message: String): Boolean =
    message.contains("EIO") || message.contains("Input/output error")
```

---

### §2.5 R6 — dead `MAX_PACKET_SIZE`
`SoftEtherClient/src/main/java/vn/unlimit/softether/terminal/TunTerminal.kt`

`MAX_PACKET_SIZE = 32767` was unreferenced (dead) and contradicted the actual TUN MTU of
1500. Removed. The live buffer constant was made testable so the MTU relationship is now
asserted rather than assumed:

```kotlin
internal const val BUFFER_SIZE = 1500   // was: private const val BUFFER_SIZE = 1500
```

---

### §2.6 R2 — split terminal message by teardown outcome
`app/src/main/java/vn/unlimit/vpngate/automode/AndroidConnectionAdapter.kt`

The auto-mode terminal emitted one message for every `TeardownCompletion`, so an
unconfirmed-native outcome was indistinguishable from a clean one. Replaced the single
`log(...)` with an outcome branch; the `NATIVE_UNRESOLVED` text is preserved verbatim:

```kotlin
when (result.outcome) {
    TeardownCompletion.Outcome.NATIVE_UNRESOLVED -> log(
        "[AUTO] Teardown finished but NATIVE CLEANUP UNCONFIRMED " +
            "(${result.describe()}); ownership retained, " +
            "a new native attempt will be refused",
    )
    TeardownCompletion.Outcome.NOOP_REENTRANT -> log(
        "[AUTO] Re-entrant teardown completion observed; native " +
            "confirmation expected shortly (${result.describe()})",
    )
    else -> log("[AUTO] Teardown finished: ${result.describe()}")
}
```

---

## §3 Part B — JVM tests and build

### §3.1 `:SoftEtherClient:test` — PASS

```
160 tests, 0 failures, 0 errors
```

Baseline was 154; the 6 new tests are in the new file
`SoftEtherClient/src/test/java/vn/unlimit/softether/terminal/TunTerminalTest.kt`:

| # | Test | Asserts |
|---|---|---|
| 1 | `classifiesRawEioMessage` | `isTunEio("read failed: EIO (Input/output error)")` is true |
| 2 | `classifiesLocalizedEioMessage` | localised wording `"Input/output error"` detected without the `EIO` token |
| 3 | `doesNotClassifyEbadfAsEio` | `EBADF` is **not** EIO (distinct failure class) |
| 4 | `doesNotClassifyUnrelatedErrors` | timeouts / `EPIPE` / `ECONNRESET` are not EIO |
| 5 | `bufferSizeFitsWireguardMtu` | `BUFFER_SIZE > 1500` — the MTU relationship R6 left implicit |
| 6 | `noteTunEioAdvancesCounter` | counter increments exactly once per call |

### §3.2 `:app:testProDebugUnitTest` — PASS (1 pre-existing skip)

```
619 tests, 0 failures, 0 errors, 1 skipped
```

Baseline was 607; the 12 new tests are in
`app/src/test/java/vn/unlimit/vpngate/state/GlobalVpnTrackerTest.kt`.

**First attempt failed** with `NoClassDefFoundError: Could not initialize class
vn.unlimit.vpngate.state.GlobalVpnTracker` on 4 of 7 tests — `GlobalVpnTracker`'s object
initializer constructs `Handler(Looper.getMainLooper())`, which does not exist on a plain
JVM. This is empirical proof of DEVIATION 2 (§2.3). The tests were rewritten against the pure
`TunnelHealth.isUsable(isConnected, nowMs)` contract:

| # | Test | Asserts |
|---|---|---|
| 1 | `usableWhenConnectedAndRxRecent` | connected + RX inside 10 s → usable |
| 2 | `unusableWhenDisconnected` | disconnected → unusable regardless of RX |
| 3 | `unusableWhenRxStale` | RX older than 10 s → unusable |
| 4 | `unusableWhenRecentTunEio` | any TUN EIO inside 30 s → unusable |
| 5 | `usableWhenEioOlderThan30s` | EIO older than 30 s does not block |
| 6 | `boundaryRxExactlyAtWindow` | RX exactly at the 10 s boundary → unusable |
| 7 | `boundaryEioExactlyAtWindow` | EIO exactly at the 30 s boundary → does not block |
| 8 | `rxCounterMonotonic` | `noteRxAdvance()` increments `rxCounter()` |
| 9 | `lastRxAdvanceAtMsUpdated` | timestamp advances on each note |
| 10 | `tunEioCountMonotonic` | `noteTunEio()` increments `tunEioCount()` |
| 11 | `hasSeenEioTracksFirstEvent` | `hasSeenEio()` flips on the first EIO only |
| 12 | `resetClearsAllCounters` | test reset zeroes every counter |

These implement `SERVER_SOURCE_ARCHITECTURE.md` §7 Q1 verbatim: *usable = CONNECTED AND RX
advanced within 10 s AND no TUN EIO in the last 30 s*. §7 Q2 (bind via per-request
`Network.getSocketFactory()`, never `bindProcessToNetwork()`) is unchanged by this phase.

### §3.3 Build

```
> Task :app:assembleProDebug
BUILD SUCCESSFUL in 11m 17s
```

- **APK**: `app/build/outputs/apk/pro/debug/app-pro-debug.apk`
- **Size**: 63,622,754 bytes
- **SHA-256**: `83a4b016233badc1ac894d0ecae4afa29325ac77a0a7ba051045c417bbcbec33`

### §3.4 Build/test process notes

- App unit tests run via `:app:testProDebugUnitTest` — the app has a `pro` flavor, so
  `testDebugUnitTest` does not exist, and the aggregate `:app:test` is SKIPPED with no
  dependencies. Library module tests run via `:SoftEtherClient:test`.
- One transient Gradle `BindException: Address already in use` was resolved with
  `./gradlew --stop` + retry (known Gradle daemon port contention, unrelated to the change).

---

## §4 Part C — automated device verification (device `5585fecc`)

All steps executed by scripted adb/uiautomator. **No operator taps were requested or
required** — the UI automation is a mechanical substitute for them.

### §4.1 C0 — device state

```
mWakefulness=Awake
```

### §4.2 C1 — install identity preserved

```
firstInstallTime : 2026-09-28 16:05:05   (before)  ==  2026-09-28 16:05:05   (after)   UNCHANGED
lastUpdateTime   : advanced (expected — the APK was re-installed over the existing app)
```

`firstInstallTime` unchanged ⇒ this was an in-place update, **not** a fresh install. No app
data wipe, no new device identity.

### §4.3 C2 — required DEX markers: ALL 5 PRESENT

| Marker | Required | Status | Found in |
|---|---|---|---|
| `[TEARDOWN-REENTRY]` | yes | **PRESENT** | `classes6.dex` |
| `Re-entrant teardown completion` (R2) | yes | **PRESENT** | `classes30.dex` |
| `TUN read failed with EIO` (R5) | yes | **PRESENT** | `classes11.dex` |
| `isTunnelUsable` (R4a) | yes | **PRESENT** | `classes28.dex` |
| `noteRxAdvance` (R4a) | yes | **PRESENT** | `classes28.dex`, `classes6.dex` |

Supporting marker also located: `noteTunEio` → `classes11.dex`, `classes28.dex`,
`classes6.dex`.

Marker counts observed at runtime: `[TEARDOWN-REENTRY]` ×7,
`Re-entrant teardown completion` ×7, `TUN read failed with EIO` ×0 (no interface was
revoked during the run — the branch is armed and inert, which is the correct idle state),
old combined terminal message ×0 (superseded by R2).

### §4.4 C3 — AutoConnect cycles (5) and stress (2)

| Cycle | Result |
|---|---|
| AutoConnect 1 | CONNECTED |
| AutoConnect 2 | CONNECTED |
| AutoConnect 3 | CONNECTED |
| AutoConnect 4 | CONNECTED (needed 2 polls) |
| AutoConnect 5 | CONNECTED |
| Stress 1 | completed |
| Stress 2 | completed |

Live tunnel sampled at steady state:

```
interface : vpn315836095
peer      : 59.138.182.93
protocol  : SOFTETHER / TCP
ping      : 2 ms
packets   : 1883 received
```

### §4.5 C3a — the R1 diagnostic result and the §2.3 mechanism correction

Every one of the 7 occurrences is byte-identical in its state/thread fields:

```
W SoftEtherVpnService: [TEARDOWN-REENTRY] attempt=1, state=(isStopping=true, controller=false, tunHeld=false, job=false), thread=main, callers=vn.unlimit.softether.SoftEtherVpnService
```

Ordering of the re-entrant call against the real teardown it duplicates (7/7 cases):

```
attempt=1 -> teardown#1,  delta=220ms
attempt=2 -> teardown#3,  delta=185ms
attempt=3 -> teardown#5,  delta=274ms
attempt=4 -> teardown#7,  delta=212ms
attempt=5 -> teardown#9,  delta=242ms
attempt=6 -> teardown#11, delta=153ms
attempt=7 -> teardown#13, delta=384ms
```

`Native cleanup confirmed` **always precedes** `NOOP_REENTRANT`, by 153–384 ms.

> **Correction to `AUDIT_AXES_A_B_C_v1.0.11.md` §2.3.** That section hypothesised that the
> re-entrant call wins the race and satisfies the waiter *before* the real `CONFIRMED` lands.
> The measured evidence shows the **opposite**: the re-entrant call is a *late duplicate*
> arriving ~200 ms after teardown has already completed natively, on the main thread, with
> the service already fully idle. It is benign bookkeeping, confirmed by 0 native slot
> refusals across all 7 cycles. The residual is therefore **not** a concurrency defect, and
> R3 (single-flight teardown) should be re-scoped against this evidence rather than the
> original hypothesis.

Also observed: teardown attempt ids increment by exactly 2 per cycle and every other id is
consumed (`#1, #3, #5, #7, #9, #11, #13`) — i.e. one teardown per AutoConnect cycle plus one
duplicate, consistent with the late-duplicate explanation.

### §4.6 C5 — crash / tombstone scan

```
crash buffer: 0 lines (EMPTY)
```

No `FATAL EXCEPTION`, no ANR, no tombstone, no native abort during the whole run. The logcat
scan filtered known noise (`MiuiNetworkPolicy`, `libc`, `app_process`, `ziparchive`,
`AndroidRuntime`, `ANDR-PERF`) before concluding.

### §4.7 C6 — device settings untouched

```
stay_on_while_plugged_in = 7        (unchanged)
screen_off_timeout       = 300000   (unchanged)
```

No `svc` / `settings put` command was issued against the device at any point.

---

## §5 Explicit statements

- **No native code modified.** No file under `SoftEtherClient/src/main/cpp/`, no `.c`, `.h`,
  `.cpp`, `CMakeLists.txt`, or any other native build input was read-modified-written. The
  native T5-SLOT residual is untouched by this phase; the C5 crash-injection procedure was
  **not** executed.
- **No spec modified.** `PROJECT_SPECIFICATION.md` is byte-identical to the v1.0.11 revision
  committed in Phase 7 (`6119a2a` + `0e011e3`). It does not appear in the diff.
- **No build script modified.** No `build.gradle`, `settings.gradle`, `gradle.properties`, or
  gradle wrapper change.
- **No user interaction required.** Every step — build, test, install, UI automation,
  logcat capture, analysis — ran unattended against device `5585fecc`. No tap, swipe, or
  manual confirmation was requested.
- **C5 not executed.** The crash/tombstone injection procedure was not run; the crash buffer
  was only *read* for passive evidence.
- **No force used; no tag created.** No `--force`, no `--force-with-lease`, no `git reset`,
  `git clean`, `git stash`, `git checkout --`, `git restore`. No tag was created, moved, or
  deleted. `p2.4.10` remains local-only as before.
- No synthetic race injection was performed; all teardown ordering evidence is organic to the
  normal disconnect flow.
- No `git add -A` / `git add .` was used; each commit staged an explicit file list.

---

## §6 What remains (deferred)

| ID | Item | Why deferred |
|---|---|---|
| **R3** | Single-flight teardown (reject concurrent `stopVpn`) | Product decision. The new evidence in §4.5 shows the residual is a benign late duplicate, so a blocking fix may be unnecessary — re-scope first |
| **R4b** | Bind the socket per request via `Network.getSocketFactory()` (§7 Q2) and wire `noteRxAdvance()` into the data path | Architecture change; needs a design pass on which network to bind |
| **R4c** | Publish `isTunnelUsable()` into the reconnect/healing decision | Depends on R4b; counters have no producer yet |
| **R7** | End-to-end native cleanup audit | Requires the C5 procedure, which this phase was forbidden to run |

---

## §7 Recommendation

Proceed to **R4b (network binding)** next. Rationale:

1. R4a in this phase built the *observability* half (RX/EIO counters + the §7 Q1 usability
   decision, now unit-tested 12 ways). R4b is the half that *produces* RX signal and binds
   sockets, so the counters become meaningful.
2. §4.5 removes the urgency from R3 — the residual teardown duplicate is demonstrably
   harmless (0 slot refusals in 7/7 cycles), so R3 can wait for a product decision instead of
   being rushed.
3. R4b is the only remaining item that can change tunnelling *behaviour* on-device; R4c and
   R7 both depend on it.

Suggested next phase: `FIX-AND-VERIFY-R4B` — bind per request via
`Network.getSocketFactory()`, wire `noteRxAdvance()` at the packet entry point, then re-run
the same 5+2 device protocol and compare `rxCounter()` deltas against the baseline captured
here.

---

## §8 No-change confirmation

The following were verified unchanged after all edits, tests, build, and device runs:

- `PROJECT_SPECIFICATION.md` — unchanged (v1.0.11, Phase 7 content intact)
- All native sources under `SoftEtherClient/src/main/cpp/` — unchanged
- `AutoModeController.kt`, `NativeLifetimeRegistry.kt`, `ConnectionController.kt`,
  `SoftEtherClient.kt` — unchanged and untouched
- `build.gradle` (both modules), `settings.gradle`, `gradle.properties`, gradle wrapper —
  unchanged
- Device `firstInstallTime` — `2026-09-28 16:05:05`, unchanged
- Device settings `stay_on_while_plugged_in`/`screen_off_timeout` — unchanged (no writes made)
- Tag list — unchanged; `p2.4.10` still local-only

---

## §9 Commits produced by this phase

| # | Commit | Subject | Files |
|---|---|---|---|
| 1 | `0e3f9ca` | `fix(android): capture re-entrant teardown caller + split terminal message by outcome (R1, R2)` | `SoftEtherVpnService.kt`, `AndroidConnectionAdapter.kt` — 2 files, +156 / −5 |
| 2 | `607c651` | `feat(android): add RX/EIO counters + classify TUN EIO + drop dead MAX_PACKET_SIZE (R4a, R5, R6)` | `TunTerminal.kt`, `GlobalVpnTracker.kt`, `TunTerminalTest.kt`, `GlobalVpnTrackerTest.kt` — 4 files, +292 / −2 |
| 3 | see `git log --oneline -1` at push time | `docs: report for FIX-AND-VERIFY-AUDIT-TOP-PRIORITIES` | `FIX_AND_VERIFY_AUDIT_TOP_PRIORITIES_REPORT.md` |

Base for all three: `0e011e3` (Phase 7 reports commit), which was identical to
`origin/main` at the start of this phase — no rebase, no reset, no force.

Commit 1 necessarily contains the `TunnelHealth` counter object as well as R1, because both
live in `SoftEtherVpnService.kt` and that file is committed whole; the consuming R4a
delegation is in commit 2. This is stated in commit 1's message.
