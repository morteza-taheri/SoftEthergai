# PHASE: FIX-CONNECTING-THRESHOLD-AND-AUTOMODE-RETRY — Report

**Code-only phase. No device command, no test run, no build run, no commit, no push, no tag was executed.**

---

## §1 Scope and authorization

Three coordinated code changes, all inside the authorized file set:

| Fix | Change | File(s) |
|-----|--------|---------|
| **FIX-1** | CONNECTING soft-bound threshold `120_000L` → `30_000L` | `NativeLifetimeRegistry.kt`, `NativeLifetimeRegistryTest.kt` |
| **FIX-2** | Auto Mode's terminal give-up now issues a teardown (ACTION_DISCONNECT) | `AutoModeController.kt`, `AndroidConnectionAdapter.kt`, `AutoModeNativeBusyTest.kt` |
| **FIX-3** | Per-server wedge ledger, N = 2 → exclude the server for the process | `AutoModeController.kt`, `AutoModeNativeBusyTest.kt` |

Authorized files touched — **5**:

1. `SoftEtherClient/src/main/java/vn/unlimit/softether/NativeLifetimeRegistry.kt`
2. `SoftEtherClient/src/test/java/vn/unlimit/softether/NativeLifetimeRegistryTest.kt`
3. `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt`
4. `app/src/main/java/vn/unlimit/vpngate/automode/AndroidConnectionAdapter.kt`
5. `app/src/test/java/vn/unlimit/vpngate/automode/AutoModeNativeBusyTest.kt`

Plus this report (new, untracked).

Authorized files **deliberately not touched**: `SoftEtherVpnService.kt`, `ConnectionController.kt` (both still carry only their pre-phase changes — see §8).

---

## §2 Pre-conditions (STEP 1) — all PASS

| Check | Expected | Observed |
|-------|----------|----------|
| `git rev-parse HEAD` | `fa8a53d20d4bc8ba1e233d473c1c7723cbadb71f` | `fa8a53d20d4bc8ba1e233d473c1c7723cbadb71f` ✅ |
| branch | `main` | `main` ✅ |
| `git rev-parse origin/main` | `fa8a53d…` | `fa8a53d…` ✅ |
| modified tracked files | exactly 4 | exactly 4 ✅ |
| `git stash list` | empty | empty ✅ |

The 4 pre-existing modifications were `NativeLifetimeRegistry.kt`, `SoftEtherVpnService.kt`, `ConnectionController.kt`, `NativeLifetimeRegistryTest.kt` — the accumulated PART 1 + FIX-B + FIX-C + `[DIAG]` work from the two prior phases.

---

## §3 STEP 0 — read findings

### 3.1 `NativeLifetimeRegistry.kt`

- `NATIVE_SLOT_FORCE_RELEASE_AFTER_MS = 90_000L` (line 479) — the TEARDOWN bound.
- `NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS = 120_000L` (line 495) — the CONNECTING bound.
- Both are consumed **only** inside `tryAcquire()`, via
  `forcedReleaseMessage = forceReleaseIfTimedOutLocked() ?: forceReleaseIfConnectingStuckLocked()`.
  The `?:` short-circuits, so exactly one can ever fire for a given holder, and the TEARDOWN
  function is always evaluated first and left untouched.
- `forceReleaseIfTimedOutLocked()` requires `deadlineExpired && phase == TEARDOWN && teardownRequestedAtMs != 0 && heldMs >= 90_000`.
- `forceReleaseIfConnectingStuckLocked()` requires `phase == CONNECTING && connectingEnteredAtMs != 0 && heldMs >= <CONNECTING bound>`. It deliberately does **not** consult `deadlineExpired`.
- Neither function is reachable except through `tryAcquire()` — i.e. exactly when a new attempt asks for admission.

**FIX-1 propagation was verified by reading, not assumed:** the only other consumers of the
CONNECTING symbol are `SoftEtherVpnService.kt:587` (FIX-C's Kotlin-side cleanup gate) and the
registry test file. Both reference `NativeLifetimeRegistry.NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS`
**symbolically**, so lowering the constant lowers FIX-C's gate too, with no second edit and no
risk of the two drifting apart.

### 3.2 `SoftEtherVpnService.kt` (read-only)

- `abandonWedgedConnectingAttemptIfAny()` (lines 575–604) returns early unless
  `attemptConnectingEnteredAtMs != 0 && connectionJob != null && !sessionPolicy.isRunning && heldMs >= NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS`.
- Called at the top of `startVpn()` before the `sessionResourcesLive` / `decideStart` gate.
- **Not modified this phase.** Its behaviour changes only because FIX-1 changes the constant it reads.

### 3.3 `AutoModeController.kt` — the give-up surface

Two — and only two — terminal give-up sites, both reached from inside a concrete server's
attempt loop:

1. **Per-server retry budget exhausted** (was ~line 1425): logs
   `"[AUTO] Per-server retry budget exceeded (10s); stopping without blaming this server"`,
   then `setState(Error(ERROR_NATIVE_UNRESOLVED)); job = null; return`.
2. **NATIVE_BUSY retry count exhausted** (was ~line 1474): logs
   `"[AUTO] Native slot still owned by an unresolved operation (<describe()>); stopping without blaming this server"`,
   then the same terminal sequence.

Before this phase **neither site issued any teardown at all** — the exact V3 observation. The run
stopped, published `ERROR_NATIVE_UNRESOLVED`, and left the wedged native operation holding the
slot with nobody having asked it to stop.

- NATIVE_BUSY accounting: `NATIVE_BUSY_MAX_RETRIES = 2`, `NATIVE_BUSY_RETRY_DELAY_MS = 2_000`,
  `PER_SERVER_RETRY_DELAY_BUDGET_MS = 10_000` (nominal accounting: one 2 000 ms charge per
  scheduled delay). The server cursor is never advanced on a refusal.
- The success transition is inside the same outer loop, so the run's de-duplication key
  (`"${server.ip}|${server.hostname ?: ""}"`) is in scope there — used by FIX-3 to clear the record.

### 3.4 `AndroidConnectionAdapter.kt` — the teardown primitives available

- `requestDisconnect()` — non-suspending; gated by a `teardownDispatched` `AtomicBoolean`
  once-per-request idempotency flag; dispatches `dispatchDisconnectIntents(0)`.
- `disconnect()` — suspending; allocates a teardown id via `SoftEtherVpnService.nextTeardownAttemptId()`,
  arms a waiter, and awaits up to `TEARDOWN_TIMEOUT_MS = 5_000`.
- `dispatchDisconnectIntents(teardownAttemptId: Int = 0)` — the single dispatch point; stamps the
  id via `EXTRA_TEARDOWN_ATTEMPT_ID` only when `> 0` (the Stage 2F-F.28AD-B convention).

**Conclusion that drove the FIX-2 design:** neither existing primitive is correct for the
recovery case. `disconnect()` blocks up to 5 s on a completion that by definition may never
arrive, and `requestDisconnect()`'s idempotency flag can already have been consumed by a normal
disconnect earlier in the same attempt — silently dropping the user's last chance to get unstuck.

### 3.5 Whether a session wedge tracker fits naturally

Yes, and the loop already has the seam: `attempted.add(key)` + `continue` at the top of the
server loop. A per-server ledger checked at exactly that point consumes no retry budget, no
attempt counter and no native admission for an excluded server.

---

## §4 FIX-1 — CONNECTING threshold 120 s → 30 s

**`NativeLifetimeRegistry.kt`**

```kotlin
const val NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS = 30_000L   // was 120_000L
```

The KDoc was rewritten. It now records:

- **Why 30 s.** 30 s *is* the native UDP parallel-race timeout (`conn->timeout_ms = 30000`,
  `softether_protocol.c`) and 6× the observed healthy connect window (<5 s). A holder still in
  CONNECTING at that point has blown its own hard budget six times over.
- **Why lowering it is safe.** The bound is only evaluated inside `tryAcquire()`, so it changes
  only how long the *user* waits before a replacement attempt can be admitted — never what
  happens while the stuck attempt is running.
- **The device motivation.** Auto Mode gives up on a wedged server ~19 s after the wedge, so a
  120 s bound could never be reached inside a run; the user waited two minutes with no way back
  except an app force-stop.
- **The inverted relationship.** The CONNECTING bound (30 s) is now deliberately **smaller** than
  the TEARDOWN bound (90 s) — the reverse of the pre-FIX-1 arrangement (120 s > 90 s). A
  CONNECTING holder owns a native call with its own hard budget, so it is reclaimed as soon as
  that budget is demonstrably blown; a TEARDOWN holder has already been asked to clean up and its
  `nativeDestroy` blocks on the same `connect_mutex`, so asking again would not help and it is
  given the longer bound.
- The two bounds still key off **mutually exclusive phases**, so "exactly one can fire" holds
  regardless of which number is larger.

**Unchanged and verified on disk:**

```
479:  const val NATIVE_SLOT_FORCE_RELEASE_AFTER_MS = 90_000L
519:  const val NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS = 30_000L
```

**FIX-C propagation (verified by reading):** `SoftEtherVpnService.kt:587`
`if (heldMs < NativeLifetimeRegistry.NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS) return` — the
same symbol, so the Kotlin-side `abandonWedgedConnectingAttemptIfAny()` gate now also fires at
30 s. No edit to that file was needed or made.

### 4.1 Registry test re-analysis (the breakage FIX-1 causes)

`teardownBoundDoesNotFireForAConnectingHolder` advanced the clock by `100_000L` and asserted
`tryAcquire() == null`, on the stated premise *"the elapsed sits ABOVE the TEARDOWN bound (90 s)
and BELOW the CONNECTING bound (120 s)"*. **FIX-1 destroys that window** — no elapsed can be
above 90 s and below 30 s. At 100 s a CONNECTING holder is now reclaimed, by the CONNECTING bound,
so `assertNull` would fail.

The test was re-expressed around the invariant it has always existed to protect and which FIX-1
does not change: **the TEARDOWN bound must never be the bound that claims a CONNECTING holder.**
The holder still carries `markDeadlineExpired` (the TEARDOWN bound's other precondition) so that
only its *phase* stands between it and `forceReleaseIfTimedOutLocked`; the test now asserts the
release is labelled `FORCED_RELEASE_CONNECTING_STUCK` and that no
`FORCED_RELEASE_AFTER_TIMEOUT` line was emitted.

`connectingBoundDoesNotFireForATeardownHolder` keeps its assertions (they still hold — the
TEARDOWN function is evaluated first and the `?:` short-circuits) but its comment, which claimed
the elapsed was "above the TEARDOWN bound, below the CONNECTING bound", was corrected to "above
BOTH bounds: what must decide is the PHASE".

---

## §5 FIX-2 — Auto Mode's give-up now issues a teardown

### 5.1 New adapter primitive

`AutoModeController.ConnectionAdapter` gains one method with a **default no-op body**, so every
existing test double keeps compiling:

```kotlin
fun requestRecoveryTeardown() {}
```

It is deliberately distinct from `disconnect()` and `requestDisconnect()`:

- **fire-and-forget** — nothing waits, because the premise is that the native operation may never
  complete;
- **terminal-give-up only** — it can never rotate a cursor or adopt a server;
- **must not be silently dropped** — an implementation must not gate it behind a
  once-per-request idempotency flag.

`AndroidConnectionAdapter` implements it (production path):

```kotlin
override fun requestRecoveryTeardown() {
    GlobalVpnTracker.reportDisconnecting()
    val teardownAttemptId = SoftEtherVpnService.nextTeardownAttemptId()
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        dispatchDisconnectIntents(teardownAttemptId)
    }
}
```

- It dispatches the **same** `ACTION_DISCONNECT` to `SoftEtherVpnService` that every other teardown
  path uses, plus the OpenVPN/SSTP disconnects (harmless when they are not running).
- It **bypasses `teardownDispatched`** on purpose: that flag is per-request idempotency for the
  normal disconnect path, and a dropped recovery request would leave the user with no way out.
- It **allocates its own teardown identity** via `nextTeardownAttemptId()` and stamps it through
  the existing Stage 2F-F.28AD-B convention, so the teardown this path causes is explicit in the
  service's own accounting rather than allocated by the service. **No waiter is armed for that
  id** — arming a waiter that could never be satisfied is pointless, and a later real `disconnect()`
  allocates a strictly greater id, so the two can never be confused.

### 5.2 Controller side — one funnel for both give-up sites

Both give-up branches now call a single helper immediately after their existing log line and
before `setState`:

```kotlin
giveUpWithUnresolvedNativeOperation(server, key)
setState(AutoModeState.Error(ERROR_NATIVE_UNRESOLVED))
job = null
return
```

The helper:

```kotlin
private fun giveUpWithUnresolvedNativeOperation(server: AutoModeCandidate, key: String) {
    adapter.log("[AUTO] Requesting recovery teardown so the unresolved native operation can be released and a later attempt admitted")
    adapter.requestRecoveryTeardown()
    val wedges = wedgeTracker.record(key)
    if (wedges >= WedgeTracker.EXCLUSION_THRESHOLD) {
        adapter.log("[AUTO] Excluding ${server.hostname ?: server.ip} from this session after $wedges wedges.")
    }
}
```

**Why this makes the 90 s TEARDOWN bound able to recover the slot.** The bound fires only for a
holder in `Phase.TEARDOWN` with `deadlineExpired`. `ACTION_DISCONNECT` runs the service's
`stopVpn()` → controller teardown → `markTeardownRequested()` (stamps the phase and the request
time) + `markDeadlineExpired()`. That is exactly the state the already-device-verified
`FORCED_RELEASE_AFTER_TIMEOUT` path reclaims. Before FIX-2 nothing ever asked, so there was no
TEARDOWN holder to reclaim.

**Ordering** mirrors the Stage 2F-F.28 Fix E contract already used by `stop()` and
`disconnectNow()`: *request the teardown first, publish the terminal state second.* The request is
non-suspending, so it cannot delay the terminal state.

**Success and rotation paths are untouched.** The helper is reachable only from the two
`ERROR_NATIVE_UNRESOLVED` give-ups. `disconnect()` is still the only rotation primitive, and
neither give-up site calls it.

**Preserved byte-for-byte:** the terminal log lines
`"[AUTO] Native slot still owned by an unresolved operation …"` and
`"[AUTO] Per-server retry budget exceeded …"` are unchanged, and the new lines do not contain the
substring `"Native slot still owned"` — so `AutoModeControllerTest`'s T3 assertion on
`logs.lastOrNull { it.contains("Native slot still owned") }` still matches exactly.

---

## §6 FIX-3 — per-server wedge ledger (N = 2)

### 6.1 The ledger

A new **file-scope `class WedgeTracker`** in `AutoModeController.kt` (declared after the
controller, because it is a *collaborator the controller is handed*, not part of the controller's
own contract):

```kotlin
class WedgeTracker {
    private val wedges = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
    fun record(key: String): Int          // increments and returns the new total
    fun countFor(key: String): Int        // 0 when never wedged
    fun isExcluded(key: String): Boolean  // countFor(key) >= EXCLUSION_THRESHOLD
    fun clear(key: String)                // a verified tunnel forgets the record
    @VisibleForTesting fun reset()
    companion object {
        const val EXCLUSION_THRESHOLD = 2
        val process: WedgeTracker = WedgeTracker()   // process-scoped
    }
}
```

**Visibility note.** It is `public`, not `internal`. It appears in `AutoModeController`'s
constructor signature, and every other type in that signature (`AutoModeState`,
`TunnelLivenessProbe`, `ReachabilityGate`, `AutoModeCandidate`, …) is public — there is no
precedent in this class for an `internal`-typed constructor parameter, and no build could be run in
this phase to settle the question. `internal` was therefore not worth the risk of a compile error
in the next phase's build; this is an application module, so there is no ABI to protect.

- **Process-scoped** via `WedgeTracker.process`, wired as a new controller constructor parameter
  `wedgeTracker: WedgeTracker = WedgeTracker.process` added **last**, so no positional construction
  anywhere breaks. The default is shared precisely because "excluded for this session" must outlive
  one run; a per-run ledger would forget the fact it exists to remember. Injection exists so a JVM
  test can supply its own instance and stay isolated.
- **`ConcurrentHashMap<String, AtomicInteger>`** as required — keyed by the same
  `"${ip}|${hostname}"` shape the attempt loop de-duplicates on, now defined once in
  `wedgeKey(server)` so the ledger and the loop cannot disagree.
- **Not persisted.** A process restart is a clean slate.

### 6.2 Increment, exclude, reset

| Trigger | Effect |
|---------|--------|
| Terminal give-up (`ERROR_NATIVE_UNRESOLVED`, either branch) | `record(key)` — one wedge |
| Count reaches `EXCLUSION_THRESHOLD` (2) | logs `"[AUTO] Excluding <host> from this session after 2 wedges."` |
| Server loop reaches an excluded server | logs `"[AUTO] Skipping <host>: excluded for this session after N wedges"` and `continue`s **before** any dial |
| **Verified tunnel** (`connectedProtocol != null`) | `wedgeTracker.clear(key)` |

The exclusion check sits immediately after `attempted.add(key)` and before the cursor update, so an
excluded server consumes no retry budget, no `attempt` counter increment and no native admission.

### 6.3 What a wedge is — and what it is not

A wedge is recorded **only** on the terminal give-up, i.e. only when the run stopped because this
application's own native operation never resolved. It is **not** recorded for a `TIMED_OUT`
rotation (the ordinary "the server did not come up" outcome, which already has its own recovery
and whose health genuinely is in question).

### 6.4 Stated limitation (documented in the code, not hidden)

The first `NATIVE_BUSY` of a run can be caused by a leftover operation belonging to an **earlier**
server, so the first wedge can in principle be attributed to the wrong candidate. The ledger is
therefore deliberately conservative — two strikes, session-scoped, cleared on success — rather
than a permanent ban.

### 6.5 Log level note

The requirement said "log at INFO". `AutoModeController`'s only logging channel is
`adapter.log(...)`, and `AndroidConnectionAdapter.log()` writes at `Log.d` for every message in the
app; changing that would alter the level of the entire Auto Mode log stream. Both new lines are
therefore emitted through `adapter.log(...)`, consistent with every other Auto Mode diagnostic.
Flagged here in case the operator wants a level change in a later phase.

---

## §7 Tests (written, **NOT executed**)

### 7.1 `NativeLifetimeRegistryTest.kt` — changed / added

| Test | Status | What it pins |
|------|--------|--------------|
| `teardownBoundDoesNotFireForAConnectingHolder` | **re-expressed** | a CONNECTING holder is claimed by the CONNECTING bound and never carries the TEARDOWN label |
| `connectingBoundDoesNotFireForATeardownHolder` | comment corrected | a TEARDOWN holder above both bounds is claimed by the TEARDOWN bound (first-evaluated, `?:` short-circuit) |
| `connectingBoundDoesNotReleaseATeardownHolderBelowTheTeardownBound` | **new** | the *new* window created by FIX-1 — a TEARDOWN holder at 40 s is past the CONNECTING bound but inside its own, so it keeps the slot |
| `softBoundValuesAreTheDesignedOnes` | **new** | pins `90_000L` and `30_000L` and asserts the inverted relationship — every other test is symbolic and would pass for any value |

All other registry tests (`softBound*`, `connectingBound*`, `aForcedRelease*`) were re-checked by
reading and remain valid; they reference the constants symbolically.

### 7.2 `AutoModeNativeBusyTest.kt` — changed / added

| Test | Status | What it pins |
|------|--------|--------------|
| `native-busy refusal does not advance through the protocols of one server` | **updated** | still `disconnects == 0` (no rotation) **plus** `recoveryTeardowns == 1` (FIX-2) — the two assertions together are the point: the give-up asks for a teardown, but never through the rotation primitive |
| `a give-up issues a recovery teardown so the wedged slot can be reclaimed` | **new** | exactly one recovery teardown, zero `disconnect()`, the request is logged, and the server is still not blamed |
| `the budget give-up also issues the recovery teardown` | **new** | FIX-2 covers **both** give-up sites — this one drives the 3 000 ms budget branch |
| `a server that wedges twice is excluded for the rest of the session` | **new** | three runs: wedge 1 → wedge 2 + the exclusion log → run 3 dials nothing and ends `ERROR_NO_SERVER` |
| `a single wedge does not exclude the server` | **new** | one strike is below the threshold; no exclusion log; a second run still dials |
| `a verified tunnel clears the wedge record` | **new** | the same server, wedged once, then verified → count returns to 0 |

Test-double changes: `BusyAwareAdapter` gains a `recoveryTeardowns` counter, a
`requestRecoveryTeardown()` override and a `tunnelUp` flag; the `controller(...)` helper gains
`retryDelayMs` and `wedgeTracker` parameters (both defaulted, so existing call sites are
unchanged) and now injects a **fresh** `WedgeTracker()` per controller so no test depends on — or
disturbs — the process-wide instance. A `runToCompletion()` helper was added because
`runUntilTerminal()` polls the *state* and would return immediately on a second run.

**No test, build, install or device command was run in this phase.**

---

## §8 STEP 6 — diff-scope verification

`git status --porcelain` (tracked) and `git diff --numstat`:

| File | This phase | Cumulative | In authorized set? |
|------|-----------|-----------|--------------------|
| `NativeLifetimeRegistry.kt` | +24 / −0 | +267 / −13 | ✅ |
| `NativeLifetimeRegistryTest.kt` | +85 / −0 | +285 / −0 | ✅ |
| `AutoModeController.kt` | +210 / −1 | +210 / −1 | ✅ |
| `AndroidConnectionAdapter.kt` | +32 / −0 | +32 / −0 | ✅ |
| `AutoModeNativeBusyTest.kt` | +244 / −5 | +244 / −5 | ✅ |
| `SoftEtherVpnService.kt` | **+0 / −0** | +106 / −0 | pre-existing FIX-C, **untouched** |
| `ConnectionController.kt` | **+0 / −0** | +39 / −2 | pre-existing PART 1 `[DIAG]`, **untouched** |

The last two numstat rows are byte-identical to their pre-phase state (`+106/−0` and `+39/−2`), which
is the evidence that neither was modified.

**Confirmed NOT modified:** `PROJECT_SPECIFICATION.md`, `build.gradle` / `app/build.gradle`, any
C/C++ source, any pre-existing report, `C5`. Brace/paren balance was re-checked on all five touched
files (all balanced).

---

## §9 Explicitly NOT done

- No device command of any kind (`adb`, install, logcat, dumpsys, am, kill).
- No test execution (`gradlew test`, no JVM suite, no instrumentation).
- No build (`gradlew assemble*`, no `CI=true` run).
- No commit, no push, no tag, no staging.
- No change to `SoftEtherVpnService.kt`, `ConnectionController.kt`, `PROJECT_SPECIFICATION.md`,
  `build.gradle`, C/C++, existing reports, or C5.
- No proposed next phase.

---

## §10 What the next (device) phase must verify

FIX-1, FIX-2 and FIX-3 are **source-level only**. Nothing here is evidence about a physical device.

1. **FIX-1 / FIX-C fire at 30 s.** After a wedge, a new attempt at ≥30 s since CONNECTING entry
   should produce `[REGISTRY] FORCED_RELEASE_CONNECTING_STUCK: attempt=N held in CONNECTING for
   ~30000 ms` **and**, on the Kotlin side,
   `[SERVICE] Kotlin attempt abandoned after ~30000 ms in CONNECTING; clearing connectionJob/controller/tunOwnership…`.
   Expect the user-visible recovery window to fall from ~2 minutes to ~30 s.
2. **FIX-2 fires on give-up.** `[AUTO] Native slot still owned by an unresolved operation …`
   (or the budget variant) must now be immediately followed by
   `[AUTO] Requesting recovery teardown so the unresolved native operation can be released…`, and
   then — on the next admission attempt — `FORCED_RELEASE_AFTER_TIMEOUT` (90 s TEARDOWN path) if the
   teardown moved the holder to TEARDOWN and it stayed wedged.
3. **FIX-3 exclusion reaches 2.** Two consecutive wedges on the same server must emit
   `[AUTO] Excluding <host> from this session after 2 wedges.`, and the next run must emit
   `[AUTO] Skipping <host>: excluded for this session after 2 wedges` with **no dial** for that host.
4. **Control (must still hold).** The TEARDOWN control
   (`FORCED_RELEASE_AFTER_TIMEOUT: attempt=2 held for … ms past deadline`) and the unchanged
   terminal strings for T3/T6 must still appear exactly as before.
5. **Not addressed by this phase (carried forward).** V5 — `Starting TLS handshake` with no
   `SSL handshake successful`, i.e. native OpenSSL `SSL_connect` apparently not honouring the 5 s
   socket timeout. That is the native root cause candidate and is **not** fixed here; FIX-1/FIX-2/FIX-3
   only make its *consequences* recoverable faster and bounded.

### §10.1 Measured baseline for item 1 (negative evidence)

A log watcher left running from the previous phase (`_fix2_watch2_result.txt`) polled
`_fix2_device.log` every 2 s for a full ~13-minute window looking for exactly
`Kotlin attempt abandoned` **or** `FORCED_RELEASE_CONNECTING_STUCK`. It terminated with
`WATCHER2_TIMEOUT` — **neither string ever appeared**.

Epistemic status: *negative evidence over a bounded window* on the pre-FIX-1 build. It does not
prove absence in general, but it is consistent with, and independently corroborates, the
previous phase's V2 finding: with the threshold at 120 s and Auto Mode giving up ~19 s after the
wedge, the CONNECTING bound and FIX-C's Kotlin cleanup had no opportunity to fire. That is the
baseline the 30 s threshold is expected to move in item 1, and it is why item 1 must be checked
for a **positive** appearance of both strings, not merely for the absence of a crash.

No device command was issued to obtain this: the watcher had already self-terminated, and only its
already-written local result file was read.

---

## Addendum — Correction to §5 (added 2026-10-13 by REPAIR-STALE-T3-ASSERTION)

§5 of this report stated that the T3 assertion in `AutoModeControllerTest.kt` "still matches exactly." That claim is incorrect. The production log line at `AutoModeController.kt:1477` was modified by commit `5e51e37` (the T5-SLOT instrumentation) to include `"(${NativeLifetimeRegistry.process.describe()})"`; the T3 assertion, set earlier by commit `c572050`, expects the message WITHOUT that interpolation. The assertion has been stale since `5e51e37` landed and was not exercised because no intervening phase ran the `:app:testDebugUnitTest` suite. The assertion is corrected in the REPAIR-STALE-T3-ASSERTION phase. No production code is changed.
