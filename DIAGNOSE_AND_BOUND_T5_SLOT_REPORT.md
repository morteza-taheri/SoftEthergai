# DIAGNOSE-AND-BOUND-T5-SLOT — Report (PART 1)

**Phase:** `DIAGNOSE-AND-BOUND-T5-SLOT` — **PART 1 (DIAGNOSE) only**
**Date:** 2026-10-13
**HEAD:** `fa8a53d20d4bc8ba1e233d473c1c7723cbadb71f`
**origin/main:** `fa8a53d20d4bc8ba1e233d473c1c7723cbadb71f`
**Branch:** `main`
**Device (for PART 2, not touched here):** `5585fecc` / Xiaomi 2201117TG / Android 13 (API 33) — installed `vn.unlimit.vpngatepro` 2.4.10-dev / 143
**Language:** English only.

> **Scope of this document:** PART 1 produced **code + analysis only**. PART 2 (forced build, install, reproduction, thread dump) is **NOT** authorized yet and was **NOT** executed. See §8.

---

## 1. Executive Verdict

```
DIAGNOSIS COMPLETE — CODE-ONLY. T5-SLOT CONFIRMED AS A P0 SLOT-LEAK PATH; SOFT-BOUND IMPLEMENTED (NOT YET BUILT/DEVICE-VERIFIED).
```

The T5-SLOT instrumentation landed in commit `5e51e37` **worked exactly as designed**: on 2026-10-13 it captured the holder identity as `attempt=6 phase=TEARDOWN, reporting deadline expired (still blocked)`. That converts the earlier UNKNOWN (§12.4 FINDING T5-SLOT) into a **confirmed** condition: the process-wide native slot was retained by an attempt whose native connect flow had not returned, for **≥15 s across two consecutive AutoConnect runs**, with no "connect flow ended" line. The user could not connect at all without force-stopping the app.

PART 1 delivers two things:

1. **Targeted diagnostic logging** (log-only) around both `nativeConnectWithHub` call sites and the teardown-exceeded branch, so the next occurrence yields an entry/exit timestamp pair that distinguishes "never returned" from "returned late" (STEP 1.2).
2. **A fail-safe soft-bound** in `NativeLifetimeRegistry.tryAcquire()` that force-releases a slot stuck in `TEARDOWN` past **90 s** past its reporting deadline, carrying the distinct label `FORCED_RELEASE_AFTER_TIMEOUT` (STEP 1.3), with unit tests (STEP 1.4).

**No behavior change when the bound is not exceeded** — the fail-closed contract is preserved verbatim for every non-timeout case. The build and the device reproduction are deferred to PART 2.

Evidence labels used throughout: **SOURCE CODE / TEST / PHYSICAL DEVICE / INFERENCE / UNKNOWN**.

---

## 2. Physical-Device Evidence Re-Summary

Provided by the operator (2026-10-13), quoted verbatim:

> - AutoConnect refused because "native slot still held by an unresolved attempt (attempt=6 phase=TEARDOWN, reporting deadline expired (still blocked))".
> - The holder (attempt=6) remained stuck for ≥15 seconds across two consecutive user-initiated AutoConnect runs.
> - No "connect flow ended" log line appeared for attempt=6 within the captured window.
> - Prior attempt=5 in the same session eventually released after ~15s via a CancellationException path (30-second UDP race timeout).
> - This is a REAL leaked slot, NOT the benign slow release hypothesized in SKIP_C5_AND_EXECUTE_STAGE_B_REPORT.md §7.
> - The T5-SLOT instrumentation (commit 5e51e37) WORKED — the holder identity was captured exactly as designed.

**Reconciliation with the registry source (SOURCE CODE):**

- The refusal string `phase=TEARDOWN, reporting deadline expired (still blocked)` is produced by `NativeLifetimeRegistry.describe()` — the `deadlineExpired` suffix (`NativeLifetimeRegistry.kt:334-335`, pre-edit) — and is emitted by the two T5-SLOT log sites added in `5e51e37` (`AutoModeController.kt:1441`, `:1477`). So the observed line is consistent with the instrumentation, and `deadlineExpired == true` with `activePhase == TEARDOWN` means `markDeadlineExpired()` **was** called for attempt=6 — i.e. `destroyResources()` reached its `UNRESOLVED` branch (`ConnectionController.kt:926-936`).
- `markDeadlineExpired()` is **advisory only**: it sets a flag and **never** releases ownership (`NativeLifetimeRegistry.kt:174`, `:254`). Therefore a holder that never returns blocks admission **indefinitely** — the fail-closed design is working as specified, and that is precisely the P0 user-visible failure.

Evidence class: **PHYSICAL DEVICE** (operator log) + **SOURCE CODE** (registry/controller reconciliation).

---

## 3. Root-Cause Hypothesis

### 3.1 The mechanism (SOURCE CODE — confirmed)

The slot is released in exactly two ways: a genuine `confirmTeardown()` (one-shot, attempt-scoped) or the new soft-bound. `confirmTeardown()` is reached only when the native teardown is **confirmed**:

```
ConnectionController.destroyResources()
  → nativeLifecycle.requestTeardown(NATIVE_TEARDOWN_TIMEOUT_MS = 500 ms)      (:893)
  → CONFIRMED          → releaseLease → NativeLifetimeRegistry.confirmTeardown(nativeLease)  (:131)
  → UNRESOLVED         → markDeadlineExpired(nativeLease)  (slot retained)     (:930)
```

So **the slot is held for as long as the native connect flow does not return.** `nativeConnectWithHub` is blocking JNI with **no cancellation channel** (`NativeLifetimeRegistry.kt:50-53`): the native `cancel_flag` is function-local and unreachable from any JNI entry point. A reporting deadline is explicitly *not* treated as evidence that the call returned (`:53`, `:132-134`).

### 3.2 Why the call can run far past the reporting deadline

Three source facts explain a long/hung connect call:

1. **A 30 s "parallel race" inside the connect.** `softether_connect_with_hub` runs a multi-transport race whose timeout is `race_timeout_ms = conn->timeout_ms` (`softether_protocol.c:2194`), floored at 5000 ms (`:2195`) and defaulting to **30000 ms** (`:962` `conn->timeout_ms = 30000`). The race has a hard `deadline = softether_tick_ms() + race_timeout_ms` (`:2283`) and logs `"Parallel race: %d transports, race timeout %u ms"` (`:2263`). A full race plus pre-race setup (DNS, TCP connect, NAT-T rendezvous) can therefore occupy **tens of seconds** before the call returns. This matches the operator's note that attempt=5 released after ~15 s "via a CancellationException path (30-second UDP race timeout)".
2. **The teardown "unblock" is a `shutdown()`, not a `close()`.** The teardown path calls `softether_force_close_socket(conn)` (`softether_jni.c:587`), which only calls `shutdown(fd, SHUT_RDWR)` on the primary TCP fd and the NAT-T UDP fd (`softether_protocol.c:1098-1124`). Its own comment states it **intentionally does not `close()` the fd or clear `conn->socket_fd`** (`:1103-1109`). `shutdown()` unblocks a thread that is *currently blocked in a read/write on that fd*; it does **nothing** for a thread that is between operations, blocked in `poll`/`select` on a different or not-yet-registered fd, in DNS resolution, or in a NAT-T loop that does not observe the shutdown. So the "force close" is not a guarantee that the connect thread will unwind promptly.
3. **The registry is fail-closed by design.** Given (1) and (2), a holder whose call has not returned keeps the slot — by construction (`:45-48`, `:132-134`).

### 3.3 Ranked hypothesis

| # | Hypothesis | Support | Evidence class |
|---|-----------|---------|----------------|
| **H1** | The native connect call was **still legitimately running** and simply exceeded the user's patience (30 s race + setup). The registry correctly refused until it returned. | attempt=5 released after ~15 s via the 30 s race timeout; `race_timeout_ms = 30000` (`softether_protocol.c:962/2194`) | INFERENCE (well-supported) |
| **H2** | The native call is **genuinely wedged** — blocked in a path `softether_force_close_socket` cannot interrupt — so it never returns → a true slot leak. | attempt=6 held ≥15 s across two runs with **no** exit line; `shutdown()`-only unblock (`:1103-1109`) | INFERENCE + PHYSICAL DEVICE |

**The operator has already determined this is H2 (a real leak), not the benign H1 slow-release that `SKIP_C5_AND_EXECUTE_STAGE_B_REPORT.md` §7 hypothesised.** PART 1 does not overturn that determination — it explains the mechanism by which either can occur and adds the instrumentation that will settle it on the next occurrence (see §4). The two hypotheses are **not** mutually exclusive: a call can be slow (H1) and then wedged (H2) if the shutdown fails to interrupt it.

---

## 4. What Remains UNKNOWN

Everything below is **UNKNOWN** and is exactly what PART 2's thread dump (`kill -3`, `debuggerd -b`) plus the new `[DIAG]` logs are designed to resolve:

1. **Which call site hung** — the initial connect (`performConnectInner`, `:588`) or the reconnect path (`attemptReconnect`, `:1266`). PART 1 instruments **both** (the reconnect path is tagged `path=reconnect`).
2. **Whether the call ever returned** — the decisive signal: an `[DIAG] nativeConnectWithHub entry` with **no** matching `exit` means it never returned (H2); an `exit` with a large `elapsed` means it returned late (H1).
3. **The native thread state at the stall** — where the connect thread is blocked (SSL read/write, `poll`, DNS, NAT-T loop, mutex).
4. **Whether `softether_force_close_socket` was actually invoked for attempt=6** and whether it reached the NAT-T branch (`conn->nat_t_transport != NULL`, `:1117`).
5. **Why attempt=6 never released while attempt=5 did** — same session, different outcome. Was a different transport/handle involved?
6. **The exact elapsed-since-teardown for attempt=6** — the captured window shows ≥15 s, but the true value (and whether it would have cleared the 30 s race) is unmeasured.
7. **Whether the stuck struct is ever freed** — a soft-bound release unblocks *admission*; it does **not** free the stuck native structure. Whether that struct is reclaimed when (if) the call finally returns is unverified.

---

## 5. The Soft-Bound Design and the 90-Second Choice

### 5.1 Design (SOURCE CODE — implemented)

Added to `NativeLifetimeRegistry`:

- **`const val NATIVE_SLOT_FORCE_RELEASE_AFTER_MS = 90_000L`** — the bound.
- **`enum class ReleaseOutcome { NORMAL, FORCED_RELEASE_AFTER_TIMEOUT }`** — the release label, declared beside `Phase`.
- **`teardownRequestedAtMs`** — stamped by `markTeardownRequested()` (additive state; that method's ownership contract is unchanged).
- **The bound is evaluated only in `tryAcquire()`** (the admission path — "the same code path that currently handles `markDeadlineExpired`", i.e. the moment a new attempt is refused). `forceReleaseIfTimedOutLocked()` releases the slot **iff all** of: `deadlineExpired` **and** `activePhase == TEARDOWN` **and** `teardownRequestedAtMs != 0` **and** `now - teardownRequestedAtMs >= NATIVE_SLOT_FORCE_RELEASE_AFTER_MS`. Otherwise it returns `null` and the slot stays held (fail-closed preserved).
- On force-release it logs: `"[REGISTRY] FORCED_RELEASE_AFTER_TIMEOUT: attempt=<id> held for <ms> ms past deadline; native operation may still be running. Releasing slot to unblock new attempt."` and sets `lastReleaseOutcome = FORCED_RELEASE_AFTER_TIMEOUT`.

**Constraints honoured:**
- **No behavior change when not triggered** — the only new branch is the timeout path; every non-timeout case returns `null` exactly as before.
- **`confirmTeardown()` / `confirmTeardownWithoutLease()` / `markDeadlineExpired()` untouched** (their return contracts and admission behavior are unchanged).
- **No blocking call and no logging under `lock`** — `forceReleaseIfTimedOutLocked()` is pure state + a returned string; the caller emits the string **after** releasing the lock (`tryAcquire`).
- **Evaluated on admission, not on a timer thread** — the registry is a ledger with no background thread; firing only when a new attempt actually asks keeps it consistent with that design and avoids a self-releasing slot.

### 5.2 Why 90 s (and why not other values)

- The observed stall is **≥15 s**; the native **parallel-race timeout is 30 s** (`softether_protocol.c:962/2194`); pre-race setup adds more.
- **90 s = 3× the 30 s race timeout**, so a merely-slow-but-progressing connect (H1) is **not** cut off before its own hard deadline can fire, while a genuinely wedged slot (H2) is reclaimed within ~1.5 minutes instead of requiring an app force-stop.
- **Alternatives considered:** 60 s (2×) — rejected as too little headroom over a 30 s race plus setup; 120 s (4×) — rejected as slower user recovery for no added diagnostic value. **No alternative is silently adopted; 90 s is the implemented value and is stated here explicitly.**

### 5.3 Explicit trade-off (must be recorded, not hidden)

A force-released holder's native operation **may still be running**. The soft-bound therefore trades *"the user cannot connect at all"* for *"the user can connect while a previous native operation may still be unwinding"*. This is judged acceptable because (a) each attempt owns a **distinct** `softether_connection_t` (a new attempt calls `nativeCreate()`; the stuck call holds a **different** `conn` and its `connect_mutex`), so the new attempt does not contend with the stuck one's mutex; and (b) the alternative (indefinite lockout) is strictly worse for the user. The trade-off and its residual risk (the stuck struct is not freed by the soft-bound) are recorded here for the PART 2 review.

### 5.4 Why an injectable log sink + clock

`SoftEtherClient`'s JVM unit tests run against the mockable `android.jar` with **no** `returnDefaultValues` (verified: no such setting anywhere in the Gradle files, no Robolectric/Mockito dependency), where `android.util.Log` **throws** `"not mocked"`. The registry therefore takes `logSink` (default `android.util.Log`) and `nowMs` (default `System.currentTimeMillis()`) as **constructor parameters with defaults**, so production behavior is unchanged, the existing call sites (`NativeLifetimeRegistry()`, `process`) keep working verbatim, and the soft-bound is testable deterministically without touching `build.gradle` (which this phase is forbidden to modify).

---

## 6. Change Inventory (PART 1)

| File | Change | Lines |
|------|--------|-------|
| `SoftEtherClient/src/main/java/vn/unlimit/softether/NativeLifetimeRegistry.kt` | `ReleaseOutcome` enum; `NATIVE_SLOT_FORCE_RELEASE_AFTER_MS`; `teardownRequestedAtMs`; `lastReleaseOutcome`; soft-bound in `tryAcquire()`; `forceReleaseIfTimedOutLocked()`; `lastReleaseOutcome()` accessor; injectable `logSink`/`nowMs` | +153 / −11 |
| `SoftEtherClient/src/main/java/vn/unlimit/softether/controller/ConnectionController.kt` | `[DIAG]` entry/exit timestamps at **both** `nativeConnectWithHub` sites + teardown-exceeded log (log-only) | +41 / −2 |
| `SoftEtherClient/src/test/java/vn/unlimit/softether/NativeLifetimeRegistryTest.kt` | 4 soft-bound tests + `assertNotNull` import | +106 / −0 |

Total: **3 files changed, 287 insertions(+), 13 deletions(-).** Evidence: SOURCE CODE.

### 6.1 Tests added (STEP 1.4)

| Test | Asserts |
|------|---------|
| `softBoundLeavesTheSlotHeldWhileElapsedIsBelowTheBound` | (a) below the bound → `tryAcquire()` returns `null`; label stays `NORMAL` |
| `softBoundForceReleasesASlotStuckInTeardownPastTheBound` | (b) at the bound → `tryAcquire()` grants a new lease; label is `FORCED_RELEASE_AFTER_TIMEOUT`; the audit line is emitted |
| `aForcedReleaseDoesNotLetTheObsoleteLeaseDisturbTheNewOne` | (c) a later `confirmTeardown(oldLease)` returns `false` and does not disturb the new lease |
| `softBoundDoesNotFireForAConnectingHolderThatIsNotInTeardown` | the bound targets `TEARDOWN`-phase leaks only; a `CONNECTING` holder keeps the slot |

Tests use an injected fake clock (no sleeps) and an injected log sink (no `android.util.Log`). **Not executed in PART 1** (no test run authorized).

---

## 7. Explicit No-Change Confirmation (PART 1)

- **No device command executed in PART 1.** No `adb`, no install, no logcat.
- **No test executed in PART 1.** The 4 new tests are written but not run.
- **C5 not executed.**
- **`AutoModeController.kt` unchanged** (`5e51e37` instrumentation frozen).
- **`PROJECT_SPECIFICATION.md` unchanged** (v1.0.9 frozen).
- **No native/C++/CMake/Gradle change.**
- **No commit / push / tag.**
- Changed on disk: only the three authorized source files (see §6). Untracked evidence files remain untracked.

---

## 8. PART 2 Readiness (NOT executed)

PART 2 requires **separate operator authorization**. When authorized, the sequence is: forced build `:app:assembleProDebug --rerun-tasks` → `adb install -r` → reproduce the stuck state → capture `kill -3` (Java threads) + `debuggerd -b` (native threads) while stuck → logcat filtered for `[DIAG]`/`[REGISTRY]` → crash scan → restore settings.

**Review gate:** the operator should first accept (i) the §5.3 trade-off, (ii) the 90 s value, and (iii) the injectable-sink/clock design in §5.4 — any of which may be redirected before PART 2.

No push, no tag. Report in English only.
