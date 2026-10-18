# AUTOCONNECT-FIX-3 (v1.0.33) — Instrumentation and Budget Floor

**Phase:** AUTOCONNECT-FIX-3-v1.0.33
**Date:** 2026-10-18
**Repo:** `C:\Dev\SoftEthergai` · branch `main`
**Nature:** device-free. No adb command was issued; the attached device was not used.

---

## §1 — Verdict

**COMPLETE.**

The two real defects produced by the v1.0.32 device verification are closed:

1. `SLOT_UNRELEASED_DIAG` is now emitted from **both** sites that can terminate a run for a held
   native slot, including the NATIVE_BUSY site that produced every on-device terminal.
2. The slot-wait budget now has an absolute **30 s floor**, so a small user-selected attempt
   timeout can no longer squeeze it below the observed release latencies.

Tests pass with the expected delta; the build succeeds; a standalone build of the pushed commit
succeeds. The root cause of the slot-non-release itself was **not** attempted — this revision
instruments and widens the budget only, as the brief required.

---

## §2 — Pre-conditions (STEP 1)

| Check | Value | Label |
|---|---|---|
| `git rev-parse HEAD` | `248d06dd67c2dd75038da249d474e368413a21ac` | SOURCE CODE |
| `git rev-parse origin/main` | `248d06dd67c2dd75038da249d474e368413a21ac` | SOURCE CODE |
| HEAD == origin/main | yes | SOURCE CODE |
| Branch | `main` | SOURCE CODE |
| Spec version before edits | `1.0.32` (line 8) | SOURCE CODE |
| Tracked modifications | none | SOURCE CODE |
| Tag count | **5** | SOURCE CODE |

All four ABORT conditions were clear.

---

## §3 — Device findings that drive this phase (summary)

Full evidence in `DEVICE_VERIFY_AUTOCONNECT_FIX_2_REPORT.md` (untracked, untouched).

| Finding | Outcome | Label |
|---|---|---|
| S1 — BUG-A refusal with a manual tunnel up | **PASS** — `[AUTO] start refused: a tunnel is already active`, tunnel survived | PHYSICAL DEVICE |
| S2 — OP-R6 continuation | **PASS** — `Continuing from server #2 of 88` → `Trying #1 vpn752839599` | PHYSICAL DEVICE |
| S3 — BUG-B wedge + rotation | **PASS** — `NATIVE_BUSY x2` ×3 on 2 hosts, `Excluding … after 2 wedges` ×1 | PHYSICAL DEVICE |
| S4 — D-2 exception path | **UNTESTABLE** — zero `Connect threw` in ~25 min | PHYSICAL DEVICE |
| SLOT_DIAG | **NOT EXERCISED** — see §4 | PHYSICAL DEVICE + SOURCE CODE |
| Release latencies | 6.0 / 6.2 / 7.5 / 13.3 / 13.7 / 17.0 / 20.9 s; one release **292 ms after** a 25 s cutoff | PHYSICAL DEVICE |

---

## §4 — Fix 1: instrumentation at the NATIVE_BUSY slot-wait site

### Why it was needed (SOURCE CODE)

`AutoModeController.kt` has **two** structurally similar whole-run terminals for a held native slot:

| Site | Trigger | Message shape | Diagnosed before v1.0.33 |
|---|---|---|---|
| NATIVE_BUSY branch (was `:1695`) | `NATIVE_BUSY` → wait → still held | `within Nms (describe()); stopping` | **no** |
| Post-attempt branch (`~:1834`) | attempt ended → wait → still held | `within Nms after <OUTCOME> on <PROTO> (describe()); stopping` | yes |

All three device terminations matched the **first** shape and carried `describe()`'s own
`attempt=N phase=P` payload, so every one came from the NATIVE_BUSY site. The instrumented site was
never reached, hence `SLOT_UNRELEASED_DIAG` count = 0 despite three slot-based stops
(**PHYSICAL DEVICE**).

### Before (SOURCE CODE)

```kotlin
val released = adapter.awaitNativeSlotRelease(nativeSlotAwaitMs)
if (!released) {
    adapter.log(
        "[AUTO] Native slot not released within " +
                "${nativeSlotAwaitMs}ms " +
                "(${NativeLifetimeRegistry.process.describe()}); stopping",
    )
    giveUpWithUnresolvedNativeOperation()
    setState(AutoModeState.Error(ERROR_NATIVE_UNRESOLVED))
    job = null
    return
}
```

### After (SOURCE CODE) — `AutoModeController.kt:1689-1723`

```kotlin
val slotWaitStartedAt = System.currentTimeMillis()
val released = adapter.awaitNativeSlotRelease(nativeSlotAwaitMs)
if (!released) {
    val waitedMs = System.currentTimeMillis() - slotWaitStartedAt
    adapter.log(
        "[AUTO] Native slot not released within " +
                "${nativeSlotAwaitMs}ms " +
                "(${NativeLifetimeRegistry.process.describe()}); stopping",
    )
    adapter.log(
        "[AUTO] SLOT_UNRELEASED_DIAG waited=${waitedMs}ms " +
                "budget=${nativeSlotAwaitMs}ms " +
                "outcome=${AttemptOutcome.NATIVE_BUSY.name} " +
                "protocol=${protocol.id} " +
                "server=${server.hostname ?: server.ip} " +
                "tracker=${adapter.tunnelTrackerStatus()} " +
                "ownTunnel=${adapter.isOwnTunnelEstablished()} " +
                "registry=${NativeLifetimeRegistry.process.describe()}",
    )
    giveUpWithUnresolvedNativeOperation()
    setState(AutoModeState.Error(ERROR_NATIVE_UNRESOLVED))
    job = null
    return
}
```

### What is added, exactly

- `val slotWaitStartedAt = System.currentTimeMillis()` before the wait
- `val waitedMs = …` after the wait
- one `adapter.log(...)` call carrying the diagnostic
- a 3-line comment block

**Nothing else.** The wait expression, the `if (!released)` condition, the order of
`giveUpWithUnresolvedNativeOperation()` → `setState(...)` → `job = null` → `return`, and every
branch below are byte-identical to before. `nativeSlotAwaitMs` is the constructor parameter, not
the `NATIVE_SLOT_AWAIT_MS` constant, so an injected test value is reflected in the log.
`outcome` is not in scope in this branch, so `AttemptOutcome.NATIVE_BUSY.name` is used — the branch
cannot produce any other outcome.

The existing site at `~:1834` is **kept unchanged**, as the brief required; it may fire in other
scenarios.

---

## §5 — Fix 2: slot-wait budget floor

### Before (SOURCE CODE) — `AutoModeEngine.kt:82-83`

```kotlin
nativeSlotAwaitMs = (du.getAutoModeTimeoutSeconds().toLong() * 1000L * 5L)
    .coerceAtMost(AutoModeController.NATIVE_SLOT_AWAIT_MAX_MS),
```

### After (SOURCE CODE) — `AutoModeEngine.kt:90-93`

```kotlin
nativeSlotAwaitMs = (du.getAutoModeTimeoutSeconds().toLong() * 1000L * 5L)
    .coerceAtLeast(AutoModeController.NATIVE_SLOT_AWAIT_MS)
    .coerceAtMost(AutoModeController.NATIVE_SLOT_AWAIT_MAX_MS),
```

Plus a 12-line KDoc paragraph recording the device latencies, the 4.1 s margin and the 292 ms-late
release, and stating the rationale: release latency belongs to the **previous native operation's own
duration**, not to the attempt timeout, so a user who selects a small `T` must not thereby shorten
the budget for our own stuck operation.

### Effect

| `T` | Before | After |
|---|---|---|
| 5 s (the device's persisted pref) | 25 s | **30 s** |
| 6 s | 30 s | 30 s |
| 12 s (shipped default) | 60 s | 60 s |
| ≥ 12 s | 60 s (capped) | 60 s (capped, unchanged) |

The floor is inert for every `T >= 6 s`; it only rescues the small-`T` case that the device
exhibited. The pre-existing cap (`NATIVE_SLOT_AWAIT_MAX_MS = 60_000L`) still applies on top.

---

## §6 — Test added

`AutoModeControllerTest.nativeSlotAwaitHasAThirtySecondFloor` (TEST).

Pins two things:
1. `AutoModeEngine.kt` derives the value through `coerceAtLeast` referencing
   `AutoModeController.NATIVE_SLOT_AWAIT_MS`.
2. `AutoModeController.NATIVE_SLOT_AWAIT_MS >= 30_000L`.

**Deliberate limitation (stated, not hidden):** this is a source-and-constant assertion. It cannot
observe the *runtime* value, because the derivation is the only seam that produces it and the engine
reads a `DataUtil` preference. Asserting the seam is the strongest available pin under the frozen
`build.gradle` (no new test dependency may be added). A behavioural test would need a preference
double wired into `AutoModeEngine`, which is outside this phase's authorized scope.

Confirmed executed, not skipped:
`testcase name="nativeSlotAwaitHasAThirtySecondFloor" … time="0.004"` with no `skipped` child
(TEST, `app/build/test-results/testProDebugUnitTest/TEST-vn.unlimit.vpngate.automode.AutoModeControllerTest.xml`).

No existing test was modified or deleted.

---

## §7 — Spec edits (E1–E8)

| ID | Edit | Status |
|---|---|---|
| E1 | Header: version `1.0.33`, HEAD `248d06dd…`, date 2026-10-18 | done |
| E2 | §1.6.1: append-only v1.0.33 push-state note | done |
| E3 | §6.8.7: floor note, and the resulting `min(max(5T, 30 s), 60 s)` term | done |
| E4 | New §1.6.25 with 8 numbered bullets | done |
| E5 | §12.5.1 / §12.5.2 — **no change** | confirmed identical |
| E6 | §20 Changelog row `1.0.33` | done |
| E7 | §20 Verification Status row for §1.6.25 (HISTORICAL) | done |
| E8 | §22 — **untouched** | confirmed byte-identical |

Diff hunks in `PROJECT_SPECIFICATION.md` are exactly six, one per intended edit:

```
@@ -7,2 +7,2 @@        header
@@ -369,0 +370,9 @@    §1.6.1 append
@@ -1978,0 +1988,60 @@ §1.6.25 (new)
@@ -2726,0 +2796,10 @@ §6.8.7 floor note
@@ -3967,0 +4047 @@   §20 changelog row
@@ -4024,0 +4105 @@   §20 verification-status row
```

---

## §8 — JVM tests (before → after)

| Suite | Before | After | Delta |
|---|---|---|---|
| `:app:testProDebugUnitTest` | 800 / 0 / 0 / 1 | **801 / 0 / 0 / 1** | +1 test, skip unchanged |
| `:SoftEtherClient:test` | 173 / 0 / 0 / 0 | **173 / 0 / 0 / 0** | none |

Both run with `CI=true`, `--rerun-tasks --no-build-cache`. Results aggregated from the JUnit XML
(`app/build/test-results/testProDebugUnitTest/TEST-*.xml`,
`SoftEtherClient/build/test-results/testDebugUnitTest/TEST-*.xml`). **TEST.**

---

## §9 — Build result and APK SHA-256

| Item | Value | Label |
|---|---|---|
| Command | `CI=true .\gradlew.bat :app:assembleProDebug --rerun-tasks --no-build-cache` | TEST |
| Result | **BUILD SUCCESSFUL in 9 m 38 s**, 114/114 tasks executed | TEST |
| APK | `app/build/outputs/apk/pro/debug/app-pro-debug.apk`, 63,749,658 bytes | TEST |
| SHA-256 | `42D90C3889ABF4ECF31A7E1487A9B05C795CDFD38D1FE136D50309AECAB9D14E` | TEST |
| v1.0.32 SHA-256 (for contrast) | `946CA9689885921EF1EEFAC7907BB9777EB79A73249EFC514BB1B97F8EBAA347` | PRIOR REPORT |

The hash **differs**, as it must: source changed. This build was **not** installed on any device
(device commands forbidden this phase); the on-device binary is still the v1.0.32 one.

---

## §10 — Diff scope

```
 M app/src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt   +21
 M app/src/main/java/vn/unlimit/vpngate/automode/AutoModeEngine.kt       +12
 M app/src/test/java/vn/unlimit/vpngate/automode/AutoModeControllerTest.kt +31
 M PROJECT_SPECIFICATION.md                                             (E1–E7)
```
(source files: `64 insertions(+)`, **0 deletions** — every change is additive. **SOURCE CODE**)

Untracked and untouched, as required: `AUTOCONNECT_AUDIT_REPORT.md`,
`AUTOCONNECT_AUDIT_REPORT_v2.md`, `DEVICE_VERIFY_AUTOCONNECT_FIX_REPORT.md`,
`DEVICE_VERIFY_AUTOCONNECT_FIX_2_REPORT.md`, `.workbuddy-ai/`, `emergency/`, `scripts/`,
`test-evidence/`.

**No unauthorized file was touched.** In particular `GlobalVpnTracker.kt`,
`AndroidConnectionAdapter.kt`, `build.gradle`, the manifests and the native C sources are
unmodified (SOURCE CODE).

---

## §11 — §22 byte-identity

| Range | Before | After | Result |
|---|---|---|---|
| §22 | 99 lines / 6,504 bytes | 99 lines / 6,504 bytes | **IDENTICAL** (`diff -q` clean) |
| §1.6.2–§1.6.24 | 1,609 lines / 127,760 bytes | 1,609 lines / 127,760 bytes | **IDENTICAL** |
| §12.5.1 + §12.5.2 block | 43 lines | 43 lines | **IDENTICAL** |

The extracted regions were non-empty and their sizes asserted before `diff` was trusted — an empty
extraction would otherwise report a vacuous "identical". **SOURCE CODE.**

---

## §12 — Negative assurance

- **No** device or adb command was issued at any point in this phase.
- **No** build or test ran without `CI=true`.
- **No** `git add -A` and **no** `git add .`; every path staged explicitly.
- **No** `--force`, `reset`, `clean`, `stash`, `checkout`, or `restore`.
- **No** tag operation of any kind; tag count stayed 5.
- **No** test deleted; one test added, none modified.
- **No** attempt to fix the slot-non-release root cause — instrumentation and budget only.
- **No** C5 execution.
- `D:\PMC\Documents\Android\SoftEthergai` was never touched.
- The two audit reports and the two device-verify reports were read only, never modified.
- `GlobalVpnTracker.kt` and `AndroidConnectionAdapter.kt` were not opened for writing.

---

## §13 — Device re-verification plan (proposed only; **NOT begun**)

1. Install the v1.0.33 APK (`-r`, no data loss) and confirm the SHA-256 pulled back from
   `/data/app/.../base.apk` matches `42D90C38…CAB9D14E`.
2. Reproduce the NATIVE_BUSY contention path (a run immediately after a Connected→press
   continuation is the reliable trigger observed in the v1.0.32 phase).
3. Expect `SLOT_UNRELEASED_DIAG` with a **full** payload on the NATIVE_BUSY terminal:
   `waited=`, `budget=30000ms`, `outcome=NATIVE_BUSY`, `protocol=`, `server=`, `tracker=`,
   `ownTunnel=`, `registry=`.
4. Read `tracker` vs `ownTunnel` vs `registry` together — that triple is the whole point of the
   line, and it is what decides between the three competing hypotheses (tracker/ownership race,
   never-released registry attempt, post-establishment leak).
5. Confirm the budget now reads **30000 ms** on the same device whose preference is 5 s, and check
   whether that alone removes the 292 ms-late termination.
6. Only after that data exists should a root-cause phase be scoped. Nothing about the root cause is
   claimed here.

---

*End of report.*
