# FIX-LOG-IDENTITY (G-2) — Implementation Report

**Phase:** FIX-LOG-IDENTITY
**Defect:** G-2 — teardown log lines that read as self-contradictory because only one side carries identity
**Class:** Diagnosability only. No behaviour change.
**Date:** 2026-10-12
**Base revision:** `071462816b1eef7e7f7459483fcd69909938cd11` (unchanged — nothing committed)

---

## 1. Summary

`ConnectionController.destroyResources()` emitted its two success log lines — `"Native cleanup confirmed"` and `"Native cleanup already confirmed by another teardown caller"` — with **no identity at all**. The two signals a reader actually sees side by side in a device log, the service's `NOOP_REENTRANT` line (`SoftEtherVpnService.kt:995-1001` → `TeardownCompletion.describe()`, `TeardownCompletion.kt:93`) and `AndroidConnectionAdapter`'s `"NATIVE CLEANUP UNCONFIRMED"` line (`:194-195`), **both** name their attempt and session. The result is that a perfectly correct pair of log lines reads as the code disagreeing with itself:

```
ConnectionController: Native cleanup confirmed: structure destroyed and slot released
[AUTO]                Teardown finished but NATIVE CLEANUP UNCONFIRMED (teardown#7(session=1, NOOP_REENTRANT)); ownership retained…
```

Those two lines describe **two different teardowns**. They always did. The reader had no way to see that, because only one of them said *which* teardown.

The fix threads a single local identity value, `"attempt=<lease id>, session=<session id>"`, into all three of `destroyResources()`'s teardown log lines, using the same `session=` key the service already uses. Nothing else changed: not the outcome mapping, not the log levels, not the branch structure, not a single field or constant.

**Verdict: `FIX APPLIED — ALL TESTS PASS`**
**Recommendation: `PROCEED TO BATCH-DEVICE-VERIFY`**

---

## 2. Pre-Conditions (P1–P5)

| # | Pre-condition | Result |
|---|---|---|
| P1 | Base revision confirmed | **PASS** — `git rev-parse HEAD` = `071462816b1eef7e7f7459483fcd69909938cd11`, identical to the PHASE-B base. Nothing was committed between phases. |
| P2 | Working tree contained only the PHASE-B delta on the authorized file | **PASS** — `ConnectionController.kt` modified (PHASE B + this phase); the new test directory `SoftEtherClient/src/test/` untracked. No other file in `SoftEtherClient/src` or `app/src` carried a timestamp from this phase. |
| P3 | The two target log lines exist and lack identity | **PASS** — pre-fix `:896` `Log.d(TAG, "Native cleanup confirmed: structure destroyed and slot released")`; `:899` `Log.d(TAG, "Native cleanup already confirmed by another teardown caller")`. Neither interpolated anything. |
| P4 | An identity is reachable from within `destroyResources()` | **PASS** — detailed in §3. `sessionId` is a constructor param; `nativeLease?.attemptId` is reachable without any new field because `Lease.attemptId` is `internal` and both classes live in the same Gradle module. |
| P5 | The counterpart format to conform to is known | **PASS** — `TeardownCompletion.describe()` = `"teardown#${attemptId}(session=$sessionId, $outcome)"` (`TeardownCompletion.kt:93`); consumed at `AndroidConnectionAdapter.kt:185` and `:194-195`. |

---

## 3. The Identity Available (P4 detail)

Three facts had to line up before a fix was possible without adding a field (which I5 forbids).

**`sessionId` is already a constructor param.** `ConnectionController.kt:52` declares `private val sessionId: Int`. It is the session this controller was created for, and it is the *same* quantity the service passes as `tearingDown` when it builds its `TeardownCompletion` (`SoftEtherVpnService.kt:998`). So the two sides of a log pair share a real, comparable value — not a coincidence of formatting.

**`nativeLease?.attemptId` is reachable without a new field.** `ConnectionController.kt:67` already holds `private val nativeLease: NativeLifetimeRegistry.Lease? = null`. `NativeLifetimeRegistry.kt:115` declares `class Lease internal constructor(internal val attemptId: Long)`. Kotlin `internal` visibility is **module-wide**, and `ConnectionController` lives in the same Gradle module (`SoftEtherClient`) as `NativeLifetimeRegistry`. Therefore `nativeLease?.attemptId` reads directly. No accessor, no field, no constant had to be added — I5 is satisfied by construction, not by inspection.

**A null lease has a defined meaning.** `nativeLease` is null only for a controller built outside a reserved session, which has no production caller. The fix renders that case as the literal `"none"` rather than a blank, so an absent lease is *stated*, not silently omitted.

### The namespace distinction (load-bearing correctness point)

`NativeLifetimeRegistry.Lease.attemptId` is a **`Long` in the native-lifetime-lease namespace**. `TeardownCompletion.attemptId` (`TeardownCompletion.kt:45`) is an **`Int` in the service's per-`stopVpn`-call sequence**. These are different counters. The report records this explicitly because a future reader who conflates them will mis-correlate logs — and the comment block in the source at `:911-917` says so too, so the code documents its own trap.

---

## 4. Why This Format, and Not `describe()`

The phase prescribed the format `"attempt=<id>, session=<id>"`. There was a tempting alternative — reuse `TeardownCompletion.describe()` — and it was rejected on three grounds:

1. **The controller does not construct a `TeardownCompletion` at these sites.** `describe()` is an instance method on that type; the controller has the outcome as a `NativeAttemptCoordinator.TeardownOutcome` (`:919`), not as a `TeardownCompletion`. Calling `describe()` would mean fabricating one purely to format a log line.

2. **It would duplicate the service's `teardown#` prefix.** `describe()` renders `teardown#7(session=1, …)`. The controller's number is the *native-lease* id, not the service's teardown-attempt id (§3). Printing `teardown#` from the controller would assert a namespace the controller does not own — actively misleading, and exactly the confusion the fix exists to remove.

3. **It would imply ownership it does not have.** The `attempt=` key makes the namespace explicit and local: *this attempt's native lease*. That is what the controller can truthfully say.

The chosen format keeps the shared vocabulary (`session=`) that lets a reader correlate across modules, while naming its own counter honestly (`attempt=`). Both facts are in the same line, adjacent, which is what makes the correlation possible without a lookup.

---

## 5. Implementation (I1–I6)

All six instructions were carried out. The edit is confined to `SoftEtherClient/src/main/java/vn/unlimit/softether/controller/ConnectionController.kt`, `:894-937`.

**I1 — add the identity value before the `when` block.** Done at `:918`:

```kotlin
val teardownIdentity = "attempt=${nativeLease?.attemptId ?: "none"}, session=$sessionId"
```

**I2 — thread it into the CONFIRMED line** (`:921`):

```kotlin
Log.d(TAG, "Native cleanup confirmed ($teardownIdentity): structure destroyed and slot released")
```

**I3 — thread it into the ALREADY_CONFIRMED line** (`:924`):

```kotlin
Log.d(TAG, "Native cleanup already confirmed by another teardown caller ($teardownIdentity)")
```

**I3b — thread it into the UNRESOLVED line** (`:931-935`):

```kotlin
Log.w(
    TAG,
    "Native cleanup unresolved ($teardownIdentity, ${nativeLifecycle.describe()}); " +
            "ownership retained until the connect flow exits",
)
```

This third line was **not** in the phase's literal two-line instruction. It was included deliberately: if only two of the three teardown outcomes carry identity, then the one outcome a reader most needs to disambiguate — "unresolved" — is the one that stays anonymous, and the fix is half-done. The existing `nativeLifecycle.describe()` detail (`destroyed=… <phase>`) is **kept**, not replaced, so no information was lost.

**I4 — do not change the conditional structure or the outcome mapping.** Verified: `when (outcome)` at `:919` retains its three branches verbatim; `return when (outcome)` at `:941-948` still maps `CONFIRMED`/`ALREADY_CONFIRMED → NATIVE_CONFIRMED` and `UNRESOLVED → NATIVE_UNRESOLVED`. Only the two `Log.d` argument lists changed. `NativeLifetimeRegistry.process.markDeadlineExpired(nativeLease)` at `:930` is untouched.

**I5 — do not add new fields or constants.** Verified: `MAX_RECONNECT_ATTEMPTS` is still at `:74`, `reconnectAttempts` still at `:150`. `teardownIdentity` is a local `val` inside `destroyResources()`. No new member, no new companion constant.

**I6 — do not change the log level.** Verified by inspection and by test L5: the first two lines remain `Log.d`, the unresolved line remains `Log.w`. Severity semantics are unaltered.

An explanatory comment block (`:894-917`) records the original contradiction, names the counterpart lines, explains the two-teardown reading, and states the namespace distinction from §3.

---

## 6. Test Results

Source: `SoftEtherClient/build/test-results/testDebugUnitTest/*.xml`, produced by the post-build command below.

```
TOTAL tests=145  failures=0  errors=0  skipped=0  passed=145
```

Per class:

| Test class | Tests | Fail | Err | Skip |
|---|---:|---:|---:|---:|
| `SoftEtherSessionPolicyTest` | 54 | 0 | 0 | 0 |
| `TunOwnershipCoordinatorTest` | 25 | 0 | 0 | 0 |
| `NativeLifetimeRegistryTest` | 22 | 0 | 0 | 0 |
| `NativeAttemptCoordinatorTest` | 20 | 0 | 0 | 0 |
| `NativeHandleSingleOwnerTest` | 8 | 0 | 0 | 0 |
| `ReconnectTerminalPathTest` (PHASE B) | 7 | 0 | 0 | 0 |
| `TeardownLogIdentityTest` (this phase) | 5 | 0 | 0 | 0 |
| `SoftEtherClientHandleGuardTest` | 4 | 0 | 0 | 0 |
| **Total** | **145** | **0** | **0** | **0** |

Post-build command (`:SoftEtherClient:testDebugUnitTest`) returned **BUILD SUCCESSFUL in 58s**.

### New suite: `TeardownLogIdentityTest`

Five tests, all passing. They assert on the production source rather than on runtime behaviour, for a reason that is structural, not stylistic — see §9.

| Test | What it pins |
|---|---|
| `L1 both cleanup-confirmed log lines carry attempt and session identity` | The two `Log.d` string literals interpolate `$teardownIdentity`. |
| `L2 identity is built from the session param and the native lease attempt id` | `val teardownIdentity =`, `nativeLease?.attemptId`, `session=$sessionId`, and the `?: "none"` fallback all present. Catches a fix that invented a different identity source or silently swallowed a null lease. |
| `L3 controller format matches the service teardown vocabulary` | The controller emits `session=`, the same key `TeardownCompletion.describe()` uses; and `TeardownCompletion.kt` still contains `session=$sessionId`, so the correlation the fix creates cannot be broken from the other side without a test noticing. |
| `L4 the unresolved line carries the same identity` | The third line interpolates `$teardownIdentity` **and** still carries `nativeLifecycle.describe()` — identity added, existing detail retained. |
| `L5 log levels and outcome mapping are unchanged` | Exactly one `Log.d` for each confirmed line, exactly one `Log.w`; both `TeardownCompletion.Outcome.NATIVE_CONFIRMED` and `…NATIVE_UNRESOLVED` still present; and `assertFalse(block.contains("confirmTeardown("))` — no ownership release may sneak in behind a log fix. |

The `teardownLogBlock()` helper strips comments before asserting, so the rationale prose I added cannot satisfy a structural assertion by accident.

### Honest note on the count

The phase's plan anticipated six tests; the suite has **five**. The five cover exactly the five assertions the phase enumerated (L1–L5); there was no sixth distinct claim to pin, and adding a redundant test to reach a number would have been padding. The total therefore rose 140 → 145, not 140 → 146.

---

## 7. Regression Result (T2)

**All pre-existing `SoftEtherClient` tests pass. Zero regressions.**

The eight pre-existing suites (`SoftEtherSessionPolicyTest`, `TunOwnershipCoordinatorTest`, `NativeLifetimeRegistryTest`, `NativeAttemptCoordinatorTest`, `NativeHandleSingleOwnerTest`, `SoftEtherClientHandleGuardTest`, plus the PHASE-B `ReconnectTerminalPathTest`) reported 140 passed / 0 failed / 0 errors / 0 skipped — identical to the PHASE-B baseline. The PHASE-B suite's seven tests in particular still pass, confirming this phase's edit to `destroyResources()` did not disturb the `attemptReconnect` terminal paths fixed in PHASE B.

This is the expected outcome: the edit changed string arguments to `Log`, and nothing else. No test could observe it except the new source-pinning suite.

---

## 8. No-Change Confirmation

The phase's final rule was *"Change ONLY log format. Do NOT change behavior."* Evidence:

| Constraint | Evidence |
|---|---|
| Only log format changed | The sole production edit is at `ConnectionController.kt:894-937`; the only changed expressions are the arguments to two `Log.d` calls and one `Log.w` call, plus one new local `val`. |
| Outcome mapping untouched | `return when (outcome)` at `:941-948` unmodified; pinned by L5. |
| Branch structure untouched | `when (outcome)` at `:919` still has exactly `CONFIRMED` / `ALREADY_CONFIRMED` / `UNRESOLVED`. |
| Log levels untouched | `Log.d`, `Log.d`, `Log.w` — unchanged; pinned by L5. |
| No new field or constant | `teardownIdentity` is a local `val`; `MAX_RECONNECT_ATTEMPTS` `:74` and `reconnectAttempts` `:150` unchanged. |
| No ownership release introduced | `:930` still calls only `markDeadlineExpired`; L5 asserts no `confirmTeardown(` in the block. |
| No second cleanup channel | The block still reports; it does not act. |
| Forbidden files untouched | `SoftEtherVpnService.kt` (mtime 2026-10-05 18:16), `NativeLifetimeRegistry.kt` (10-05 12:21), `NativeAttemptCoordinator.kt` (10-05 17:09), `SoftEtherSessionPolicy.kt` (10-04 20:26), `GlobalVpnTracker.kt` (09-30 07:30) — all carry pre-phase timestamps. `ConnectionController.kt` (2026-10-12 20:06) is the only file this phase touched. |
| Nothing committed | HEAD still `071462816b1eef7e7f7459483fcd69909938cd11`. |
| Device, CI, release APK | Not touched. |

**Files changed by this phase:** `ConnectionController.kt` (production), `TeardownLogIdentityTest.kt` (new test). Nothing else.

---

## 9. Failure Modes and Limitations

**The fix does not prove runtime log output.** These tests assert the *format strings in the source*. That the formatted line reaches logcat with real values — that `nativeLease?.attemptId` is non-null in the production path, that `sessionId` matches what the service logs — requires a device. This is a genuine limitation and the reason the recommendation is device verification rather than a declaration of completion.

**Why the tests are source-pinned rather than behavioural.** `ConnectionController` cannot be constructed off-device. Its constructor takes a concrete `SoftEtherVpnService` — a `final` Android `VpnService` subclass — passed straight to JNI via `service.protect(...)`, plus a `ParcelFileDescriptor` for the TUN. `destroyResources()` is a public non-suspend method, but there is no way to obtain an instance to call it on. This is the same limitation the neighbouring suites document in their own words — `NativeHandleSingleOwnerTest` states flatly *"**No `ConnectionController`.** It cannot be constructed off-device"* — and the codebase's own sanctioned remedy is what this suite uses: read the production source and pin the structural facts. Precedent: `AutoModeLifecycleF28Test.disconnectBodySource()` and `AutoModeNativeBusyTest:408-414`.

**Residual risk this leaves.** A source-pinning test proves the code *says* the right thing; it cannot prove the value is right at runtime. If the production path ever reached `destroyResources()` with `nativeLease == null` and a live session, the line would read `attempt=none, session=N` — honest, but a reader would need to know that `none` is not expected there. The device-verification phase should confirm `attempt=` is populated in the observed teardown.

**Not addressed by this fix.** G-2 was purely *under-identification* — the events were never contradictory, only unreadable. Any *actual* contradiction between controller and service teardown signals would be a different defect and is not in scope here.

---

## 10. Migration and Rollout

**No migration. No rollout risk.**

This is a `Log` argument change. There is no persistence, no wire format, no API, no state, no configuration. Nothing in the app can observe it except a human reading logcat.

- **Consumers:** none. `Log.d`/`Log.w` output is not parsed by any code path; the existing `AndroidConnectionAdapter` correlation reads `TeardownCompletion`, not log text.
- **Backward compatibility:** the log *message prefixes* (`"Native cleanup confirmed"`, `"Native cleanup already confirmed by another teardown caller"`, `"Native cleanup unresolved"`) are preserved verbatim, so any ad-hoc `grep` or documentation that keys on them still matches.
- **R8/ProGuard:** no interaction. No new symbols, no reflection, no class names.
- **Auto Mode:** no interaction. This phase did not touch the adapter or the controller's state machine; the G-2 signal path (`AndroidConnectionAdapter.kt:185/:194`) is unchanged and already carries identity.
- **Rollback:** revert the two files. Zero downstream effect.

One follow-on consideration for the device-verification phase: with both sides now carrying `session=`, a correlation can be done mechanically. If device logs show a `session=` mismatch where there should be none, that would surface a real identity bug that this fix's format now makes *visible* — a side benefit of the identity work.

---

## Verdict

**`FIX APPLIED — ALL TESTS PASS`**

`ConnectionController.destroyResources()`'s three teardown log lines now carry `attempt=<native-lease id>, session=<session id>`, using the same `session=` key as the service and the adapter, so a teardown log can be correlated across modules instead of reading as a contradiction. 145 tests pass with zero failures, errors, or skips. No behaviour changed; no forbidden file was touched; nothing was committed.

## Recommendation

**`PROCEED TO BATCH-DEVICE-VERIFY`**

The fix's correctness rests on two facts that only a device can confirm: that `nativeLease?.attemptId` is populated in the production teardown path (not `none`), and that the `session=` value the controller logs matches the one the service logs for the same teardown. Both are cheap to check with a single repro of the original G-2 log pair, and both are exactly what a source-pinning test cannot prove.

The alternative recommendation, `PROCEED TO FIX-U36-KSP-ALIGN`, remains available and independent — it addresses the KSP 2.3.2 / Kotlin 2.4.0 version mismatch and does not overlap with this work. Either can be taken first; they do not interact.
