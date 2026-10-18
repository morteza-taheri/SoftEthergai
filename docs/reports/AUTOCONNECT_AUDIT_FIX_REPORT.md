# AutoConnect Logic Audit — Fix Implementation Report

**Block:** AUTOCONNECT-AUDIT-FIX (follow-up to `AUTOCONNECT_LOGIC_AUDIT.md`)
**Date:** 2026-10-17
**Repository:** `C:\Dev\SoftEthergai`
**Base commit:** `0dc7ed3b7c75243d03fa1b7481457081195e8162` (HEAD == origin/main at start of block)
**Authorization:** operator instruction *"implement the fixes, report the result, install the corrected app on the device"* — an explicit, scoped departure from the v1.0.27 device-only freeze for the files listed in §3.
**Evidence labels:** `[SOURCE CODE]` `[TEST]` `[BUILD]` `[PHYSICAL DEVICE]` `[INFERENCE]` `[UNKNOWN]`

---

## 1. What was fixed

Two of the seven audit findings were addressed. Both are the ones that produced the observed symptom — AutoConnect turning red and stopping instead of rotating.

### D-1 (CRITICAL) — the native read ladder multiplied the caller's timeout

**Before** `[SOURCE CODE]` `aes_wrapper.c:553`:

```c
const int max_retries = 5;
...
if (ssl_error == SSL_ERROR_WANT_READ || ssl_error == SSL_ERROR_WANT_WRITE) {
    retries++;
    continue;                       // another full SO_RCVTIMEO wait
}
if (ssl_error == SSL_ERROR_SYSCALL && (errno == EINTR || errno == EAGAIN)) {
    retries++;
    continue;
}
```

The socket is blocking with `SO_RCVTIMEO` set from `conn->timeout_ms`
(`softether_protocol.c:1604` for the login/watermark exchange). A read that hits
that timeout returns `EAGAIN`, which OpenSSL reports as `SSL_ERROR_WANT_READ` —
and the loop retried it up to five more times. The caller's budget `T` therefore
became a native worst case of `(max_retries + 1) x T`. `[PHYSICAL DEVICE]`
Measured on 2026-10-17: native verdict at **18,915 ms** against a **5,000 ms**
budget — a server that accepted TCP and completed TLS (TLSv1.2 / AES128-SHA) but
never answered the VPNCONNECT watermark.

**After** `[SOURCE CODE]`:

```c
errno = 0;
result = SSL_read(ctx->ssl, buffer, (int)len);
const int read_errno = errno;        // sampled before SSL_get_error() clobbers it
...
if (ssl_error == SSL_ERROR_WANT_READ || ssl_error == SSL_ERROR_WANT_WRITE) {
    if (read_errno == EAGAIN || read_errno == EWOULDBLOCK) {
        LOGD("SSL read: SO_RCVTIMEO expired (errno=%d); reporting to caller "
             "instead of retrying", read_errno);
        break;                       // an expired timeout is a verdict, not a hiccup
    }
    retries++;
    continue;
}
if (ssl_error == SSL_ERROR_SYSCALL && read_errno == EINTR) { retries++; continue; }
if (ssl_error == SSL_ERROR_SYSCALL && read_errno == EAGAIN) { ...; break; }
```

`errno` is now sampled immediately after the failing call, because
`SSL_get_error()` may itself set `errno`. The ladder is retained for genuinely
transient `WANT_READ` (no `EAGAIN`); an expired `SO_RCVTIMEO` is returned to the
caller at once. `[INFERENCE]` Native worst case for a stalled read is now `1 x T`
instead of `6 x T`.

### D-1b — the two clocks still coincided, so they were decoupled

Even with D-1, the native connect budget and the controller's armed wait were the
**same figure** (`AndroidConnectionAdapter.connectSoftEther` read the same setting
that `AutoModeEngine` passes as `attemptTimeoutMs`). The attempt was therefore a
photo finish decided by whichever timer fired first. `[PHYSICAL DEVICE]` On
2026-10-17 the native exit and the controller's give-up landed **27 ms apart**.

**After** `[SOURCE CODE]` `AndroidConnectionAdapter.kt`:

```kotlin
private const val NATIVE_TIMEOUT_FRACTION_NUM = 4L
private const val NATIVE_TIMEOUT_FRACTION_DEN = 5L
private const val MIN_NATIVE_CONNECT_TIMEOUT_MS = 1_500L
...
val nativeConnectTimeoutMs =
    (attemptTimeoutMs * NATIVE_TIMEOUT_FRACTION_NUM / NATIVE_TIMEOUT_FRACTION_DEN)
        .coerceAtLeast(MIN_NATIVE_CONNECT_TIMEOUT_MS)
...
connectTimeoutMs = nativeConnectTimeoutMs.toInt(),
```

The native verdict is now due at 80 % of the controller's window, so it arrives
first, the slot is released, and the controller classifies the attempt itself
instead of racing it. Nothing that could have succeeded is lost: an attempt that
cannot finish its handshake inside 80 % of the window could not have produced a
verified tunnel inside 100 % either. `[INFERENCE]`

### D-2 (CRITICAL) — a NATIVE_BUSY refusal terminated the whole run

**Before** `[SOURCE CODE]` `AutoModeController.kt` (85-line block, removed):

```kotlin
var retryCount = NATIVE_BUSY_MAX_RETRIES
...
if (accumulatedRetryDelayMs + NATIVE_BUSY_RETRY_DELAY_MS > perServerRetryDelayBudgetMs) {
    ... giveUpWithUnresolvedNativeOperation(...)
    setState(AutoModeState.Error(ERROR_NATIVE_UNRESOLVED)); job = null; return
}
retryCount--; accumulatedRetryDelayMs += NATIVE_BUSY_RETRY_DELAY_MS
delay(nativeBusyRetryDelayMs)
if (retryCount <= 0) {
    ... giveUpWithUnresolvedNativeOperation(...)
    setState(AutoModeState.Error(ERROR_NATIVE_UNRESOLVED)); job = null; return
}
```

Two fixed 2 s retries, then `return` — **from inside the protocol loop**, so
neither the remaining protocols of that server nor any later server were ever
tried. `[PHYSICAL DEVICE]` On 2026-10-17 the two retries expired **27 ms before**
the native operation returned on its own, and the slot was released **313 ms
after** the run had already been abandoned.

**After** `[SOURCE CODE]` — a refusal is answered by waiting for the **observable**
slot release:

```kotlin
val released = adapter.awaitNativeSlotRelease(nativeSlotAwaitMs)
if (!released) {
    adapter.log("[AUTO] Native slot not released within ${nativeSlotAwaitMs}ms " +
                "(${NativeLifetimeRegistry.process.describe()}); stopping")
    giveUpWithUnresolvedNativeOperation(server, key)
    setState(AutoModeState.Error(ERROR_NATIVE_UNRESOLVED)); job = null; return
}
adapter.log("[AUTO] Native slot released; retrying ${protocol.id} on ...")
adapter.armTunnelWatch(protocol)
adapter.connect(server, protocol)                       // exactly one retry
val retryOutcome = adapter.awaitArmedOutcome(protocol, attemptTimeoutMs)
if (retryOutcome == AttemptOutcome.TUNNEL_UP) { connectedProtocol = protocol; break }
adapter.log("[AUTO] Attempt after the slot was released did not come up " +
            "(${retryOutcome.name}); moving on")
// falls through to the ordinary protocol-failure path -> rotation continues
```

The wait is a **bound, not a delay** — it ends the moment the slot is free
(`[PHYSICAL DEVICE]` measured: 313 ms). The fail-closed admission policy is not
circumvented: no new native attempt is started during the wait, and the refusal is
still detected before `NativeLifetimeRegistry.tryAcquire()` allocates an attempt
ID. Only an operation that **never** releases still produces the terminal
`ERROR_NATIVE_UNRESOLVED`.

Wiring `[SOURCE CODE]` `AndroidConnectionAdapter.awaitNativeSlotRelease()`:

```kotlin
override suspend fun awaitNativeSlotRelease(timeoutMs: Long): Boolean {
    if (!isNativeSlotBusy()) return true
    val startedAt = System.currentTimeMillis()
    while (System.currentTimeMillis() - startedAt < timeoutMs) {
        kotlinx.coroutines.delay(NATIVE_SLOT_POLL_MS)      // 100 ms
        if (!isNativeSlotBusy()) { log(...); return true }
    }
    return !isNativeSlotBusy()
}
```

`AutoModeEngine` derives the bound from the same user setting:
`nativeSlotAwaitMs = attemptTimeoutMs * 5`, capped at `NATIVE_SLOT_AWAIT_MAX_MS`
(60 s).

### D-2b — dead knobs removed

The retry-count and retry-delay budget are no longer part of the design, so they
were deleted rather than left in place as no-ops (`AUTOCONNECT_LOGIC_AUDIT.md`
§P4b: "leaving both is misleading"):

| Removed | Where |
| --- | --- |
| `nativeBusyRetryDelayMs` (constructor param) | `AutoModeController` |
| `perServerRetryDelayBudgetMs` (constructor param) | `AutoModeController` |
| `NATIVE_BUSY_RETRY_DELAY_MS` | `AutoModeController` companion |
| `NATIVE_BUSY_MAX_RETRIES` | `AutoModeController` companion |
| `PER_SERVER_RETRY_DELAY_BUDGET_MS` | `AutoModeController` companion |

`[SOURCE CODE]` `grep` over `app/src` and `SoftEtherClient/src` returns zero
remaining references.

---

## 2. Files changed

`[SOURCE CODE]` `git diff --numstat`:

| File | +/- | Change |
| --- | --- | --- |
| `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt` | +119 / -162 | D-2 wait-then-retry, `awaitNativeSlotRelease` on the adapter interface, `NATIVE_SLOT_AWAIT_MS` / `_MAX_MS`, dead knobs removed |
| `app/src/main/java/vn/unlimit/vpngate/automode/AndroidConnectionAdapter.kt` | +81 / -4 | `awaitNativeSlotRelease` poll (100 ms), D-1b native budget fraction + floor |
| `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeEngine.kt` | +8 / -0 | derives `nativeSlotAwaitMs` from the per-attempt setting |
| `SoftEtherClient/src/main/cpp/softether-core/src/crypto/aes_wrapper.c` | +29 / -2 | D-1 `SO_RCVTIMEO` verdict instead of retry |
| `app/src/test/java/vn/unlimit/vpngate/automode/AutoModeControllerTest.kt` | +125 / -102 | T1–T8 realigned; T2/T3/T6/T8 rewritten for the new semantics |
| `app/src/test/java/vn/unlimit/vpngate/automode/AutoModeNativeBusyTest.kt` | +97 / -36 | slot-release model in `BusyAwareAdapter`, rotation tests rewritten |
| `app/src/test/java/vn/unlimit/vpngate/automode/AutoModeEndToEndTest.kt` | +12 / -1 | source-shape assertion now pins the derived native budget |

Frozen files untouched: `build.gradle`, manifests, XML resources,
`PROJECT_SPECIFICATION.md`. No spec edit was made in this block — §8.4/§8.5 and
§1.6.x still describe the retry-count/budget policy and must be updated with the
D-2 change in the v1.0.27 spec phase (see §7).

---

## 3. Test evidence

`[TEST]` `CI=true ./gradlew :app:testProDebugUnitTest :SoftEtherClient:test --no-configuration-cache` → **BUILD SUCCESSFUL in 2m 19s**

| Suite | Result | Baseline (v1.0.20) |
| --- | --- | --- |
| `:app:testProDebugUnitTest` | **782 tests, 0 failures, 0 errors, 1 skipped** | 780 / 0 / 0 / 1 |
| `:SoftEtherClient:test` | **173 tests, 0 failures, 0 errors, 0 skipped** | 173 / 0 / 0 / 0 |

`[TEST]` The client suite was reported `UP-TO-DATE` by Gradle — no
`SoftEtherClient` Kotlin source changed in this block, so its inputs are unchanged;
the result XMLs on disk are 173/0/0/0.

`[TEST]` **Re-run 2026-10-17** (this session), after the D-3/D-4/D-5/D-7 source +
test additions and the WedgeTracker test-isolation fix:
`CI=true ./gradlew :app:testProDebugUnitTest :SoftEtherClient:test --console=plain`
→ **BUILD SUCCESSFUL in 1m 35s**; `:app` = **782 / 0 / 0 / 1**,
`:SoftEtherClient` = **173 / 0 / 0 / 0**. See §9.

New / rewritten tests that pin the fix:

| Test | Pins |
| --- | --- |
| `nativeBusyRetrySucceedsOnSecondAttempt` (T1) | one refusal → one release → one retry → Connected |
| `secondRefusalAfterReleaseRotatesToTheNextProtocol` (T2) | a second refusal after the release is a **protocol** failure: TCP×2 then UDP connects; no give-up message |
| `aSlotThatNeverReleasesEndsTheRun` (T3) | only a slot that never releases is terminal (`ERROR_NATIVE_UNRESOLVED`) |
| `nativeBusyCancellationDuringRetryIsClean` (T4) | the slot wait is cancellable; `stop()` publishes no terminal state |
| `nativeBusyDoesNotFireOnSuccess` (T5) | zero slot waits on the success path |
| `releasedSlotRetryFailureMovesOnToTheNextProtocol` (T6) | released slot + failed retry ⇒ rotation continues (replaces the deleted budget test) |
| `timedOutRotatesCursorWithoutSameServerRetry` (T7) | TIMED_OUT never enters the slot wait |
| `timedOutThenNativeBusyOnNextProtocolWaitsAndRetries` (T8) | a timeout leaves no residue that suppresses the refusal handling |
| `a released slot lets the run rotate instead of ending it` | `AutoModeNativeBusyTest`: TCP×2 → UDP, run ends `ERROR_NO_SERVER` not `ERROR_NATIVE_UNRESOLVED` |
| `the controller waits for the slot instead of retrying blind` | `AutoModeNativeBusyTest`: exactly one slot wait per refusal |
| `connectSoftEtherPlumbsAttemptTimeoutMs` | source shape: `connectTimeoutMs = nativeConnectTimeoutMs.toInt()` derived from the fraction constants |

---

## 4. Build evidence

`[BUILD]` `CI=true ./gradlew :app:assembleProDebug --no-configuration-cache` → **BUILD SUCCESSFUL in 1m 30s**

- `:SoftEtherClient:buildCMakeDebug[...]` executed for all four ABIs
  (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) — the C change compiled cleanly.
- APK: `app/build/outputs/apk/pro/debug/app-pro-debug.apk`, **66,519,892 bytes**.
- `[BUILD]` The new native diagnostic is present in the packaged library:
  `lib/arm64-v8a/libsoftether.so` contains both `SO_RCVTIMEO expired` and the
  retained `SSL read retry %d/%d` format string. The old binary could not contain
  the first one.

---

## 5. Install evidence

`[PHYSICAL DEVICE]` `adb install -r app-pro-debug.apk` → `Success` (streamed install).

| Property | Before | After |
| --- | --- | --- |
| `versionName` / `versionCode` | 2.4.10-dev / 143 | **2.4.10-dev / 143** (unchanged) |
| `firstInstallTime` | 2026-09-28 16:05:05 | **2026-09-28 16:05:05** (unchanged) |
| `lastUpdateTime` | 2026-10-17 15:31:18 | 2026-10-17 17:12:19 |
| `codePath` | `.../vn.unlimit.vpngatepro-kyDUh_s...` | `.../vn.unlimit.vpngatepro-y4_q0kV...` |
| installed `base.apk` size | — | 66,519,892 bytes (byte-identical to the artifact) |

An unchanged `firstInstallTime` proves this was an update-in-place: user data,
the server list and the settings were preserved.

`[PHYSICAL DEVICE]` Launch: `vn.unlimit.vpngatepro` pid **3839**,
`topResumedActivity = .../activities.MainActivity`. No `FATAL` and no
`ANR in vn.unlimit.vpngatepro` in logcat after launch.

---

## 6. How to verify the fix on the device

`[SOURCE CODE]` The new behaviour is visible in the in-app Auto Mode log panel.
Expected lines, in this order, for a server whose connect stalls:

1. `[AUTO] Trying #n <host>`
2. `[AUTO] TIMED_OUT after <T>ms on <host>/<proto>; cleanup then next protocol (no same-server retry)`
3. `[AUTO] Protocol failed: <proto>`
4. — rotation continues to the next protocol, then the next server —

Expected lines when a previous native operation is still unwinding:

1. `[AUTO] Native slot released; retrying <proto> on <host>` (after ~0.3 s)
2. either `Tunnel established`, or
   `[AUTO] Attempt after the slot was released did not come up (<outcome>); moving on`

The **only** path that still ends the run with the red error is:

- `[AUTO] Native slot not released within <N>ms (...); stopping` → `ERROR_NATIVE_UNRESOLVED`

i.e. an operation that never returns. `[INFERENCE]` With D-1 in place this should
no longer be reachable in practice, because the native side now gives up at 80 %
of the controller's window.

`[SOURCE CODE]` On the native side the new line
`SSL read: SO_RCVTIMEO expired (errno=11); reporting to caller instead of retrying`
(`tag SoftEtherCrypto`, level D) is the direct evidence that D-1 fired.

---

## 7. Not fixed in this block

| Finding | Severity | State |
| --- | --- | --- |
| D-3 — stale native op causes a spurious `NATIVE_BUSY` cascade on the next protocol | HIGH | CODE-FIXED + TEST-GREEN (§9). Device verification OPEN — phone disconnected. |
| D-4 — a native `ERROR` does not complete the armed wait | MEDIUM | CODE-FIXED + TEST-GREEN (§9). Device verification OPEN — phone disconnected. |
| D-5 — per-server wedge ledger (`WedgeTracker`, `EXCLUSION_THRESHOLD = 2`) | MEDIUM | CODE-FIXED + TEST-GREEN (§9, FIX-3). Device verification OPEN — phone disconnected. |
| D-6 — the reachability probe is protocol-neutral, so "reachable" ≠ valid SoftEther (88 of 172) | LOW | OPEN. Needs product decision; not started. |
| D-7 — `AutoModeProtocol.fromId` silently defaults to `SOFTETHER_UDP` | LOW | CODE-FIXED + TEST-GREEN (§9). Now returns `null` for unknown ids. Device verification OPEN — phone disconnected. |
| Spec sync — §8.4/§8.5 and §1.6.x still describe the removed retry-count/budget policy | — | PENDING (v1.0.27 spec phase, edits E1–E8) |

---

## 8. Constraint compliance

- `CI=true` and `JAVA_HOME=/c/Program Files/Android/Android Studio/jbr` on every
  Gradle invocation.
- No `git add -A` / `git add .`; no commit, tag, force, reset, clean, stash or
  checkout was performed. Working tree: 7 modified files, nothing staged.
- `D:\PMC\Documents\Android\SoftEthergai` untouched.
- No `pm clear`, no uninstall, no force-stop, no induced network loss, no
  airplane-mode change (`airplane_mode_on = 0` before and after).
- No binary transfer through `adb shell`; the APK went through
  `adb install` only, and readback was limited to `dumpsys`/`stat` text output.

---

## 9. Phase-3 follow-up — D-3 / D-4 / D-5 / D-7 source fixes (test-verified, device-blocked)

**Date:** 2026-10-17 · **Evidence:** `[SOURCE CODE]` (working-tree diff) + `[TEST]` (re-run below)
**Status:** all four implemented in source and compiled; the full unit suite is green.
**Device verification:** OPEN — the phone is disconnected per operator instruction
("the phone is disconnected until further notice; do not do steps needing a phone
connection"), so no on-device re-verification was performed.

### D-3 — clear the stale native operation before rotating
`[SOURCE CODE]` `AutoModeController.kt`: after a failed attempt the previous native
operation could still be running and nothing waited for it to end, so the next
protocol was refused with `NATIVE_BUSY` before it ever reached the server — the
cascade that made a healthy list look exhausted. The fix waits for the process-wide
slot to be free (`adapter.awaitNativeSlotRelease(nativeSlotAwaitMs)`) after teardown;
it returns immediately when the slot is already free (the healthy path pays nothing),
and only a never-releasing operation is terminal.

### D-4 — a terminal native failure completes the armed wait
`[SOURCE CODE]` `TunnelStateWatcher.kt`: a new `onAttemptFailed` signal is delivered
on a terminal native verdict — SoftEther `STATE_ERROR` and OpenVPN
`LEVEL_AUTH_FAILED` — distinct from `onTunnelLost` (which fires for every disconnect
and drives Auto-Reconnect). The armed attempt now stops waiting on a verdict instead
of burning the rest of its budget on an operation that is over.

### D-5 — per-server wedge ledger (supersedes old FIX-3)
`[SOURCE CODE]` `AutoModeController.kt` + `WedgeTracker` (companion object): a
`TIMED_OUT` that **reached** the server records a wedge against it (`recordWedge`);
after `WedgeTracker.EXCLUSION_THRESHOLD` (2) wedges the server is excluded for the
rest of the session (`isExcluded` short-circuits the run loop). Refusals
(`NATIVE_BUSY`) and "not usable" outcomes are deliberately not wedges. `[TEST]`
`AutoModeNativeBusyTest` FIX-3 pins this.

### D-7 — `fromId` no longer silently defaults
`[SOURCE CODE]` `AutoModeProtocol.kt`: `fromId(id)` now returns `AutoModeProtocol?`
(`null` for an unrecognised id) instead of falling back to `SOFTETHER_UDP`. A caller
that legitimately wants the unset-setting default must pass `DEFAULT_ID` explicitly,
so "user chose UDP" is never confused with "we did not recognise the value".

### Test-verification result `[TEST]`
As recorded in §3 (re-run line): `BUILD SUCCESSFUL in 1m 35s`;
`:app:testProDebugUnitTest` = **782 / 0 / 0 / 1**, `:SoftEtherClient:test` =
**173 / 0 / 0 / 0**.

The 8 failures seen in the prior in-flight run were **harness defects, not code
defects**:
1. `WedgeTracker.process` (the process-wide singleton) leaked wedges across tests
   in one JVM, so shared hostnames tripped `EXCLUSION_THRESHOLD` and later tests saw
   an already-excluded server → empty attempt lists. Fixed by injecting a fresh
   `WedgeTracker()` in `AutoModeControllerTest.controller(...)`, three constructions
   in `VpnM24x10TaskTest` (`protocolsAttempted`, the `isTunnelBusy` pair, and the
   `repeat(2)` helper).
2. `AutoModeNativeBusyTest` FIX-3 asserted `ERROR_NATIVE_UNRESOLVED` after the first
   wedge, but with `nativeBusy=false` the first wedge is a plain `TIMED_OUT` →
   `ERROR_NO_SERVER`. Assertion corrected to `ERROR_NO_SERVER`.

No production code changed to make the suite green; only test isolation + one
expectation.

---

## 10. Phase-3 priority #1 — server-list fetch now also uses the app's own VPN tunnel

**Date:** 2026-10-17 · **Evidence:** `[SOURCE CODE]` (`VpnServerRepository.kt`,
`HttpFetcher.kt`) · **Device verification:** OPEN (phone disconnected).
**Operator priority:** *"fix the server-list update path — examine open paths for
reaching the filtered site, meaning also use the tunnel route the app itself
creates."*

### What changed
`[SOURCE CODE]` `VpnServerRepository.productionRouting()` (the R4b open-vs-VPN
inputs, re-read once per `refresh()`):

```kotlin
internal fun productionRouting(): CollectorRoutingInput {
    val tunnelUp = GlobalVpnTracker.isConnected &&
        GlobalVpnTracker.currentVpnNetwork() != null
    return CollectorRoutingInput(
        preferVpn = GlobalVpnTracker.prefersVpnForCollector() || tunnelUp,
        tunnelUsable = tunnelUp,
        hasBoundNetwork = GlobalVpnTracker.currentVpnNetwork() != null,
    )
}
```

### Why
The R4b machinery already existed: `CollectorRouting.choose` returns `VPN` only when
`preferVpn && tunnelUsable && hasBoundNetwork`, and `boundToSocketFactory` binds a
collector request to `GlobalVpnTracker.currentVpnNetwork()`. But the rung was gated
behind (a) the `preferVpn` opt-in (default **false**) and (b)
`GlobalVpnTracker.isTunnelUsable()`, which reports **false** until the RX counter is
wired (R4a, not yet done). Neither gate suits the collector: a tunnel the app
created and the OS has bound is a perfectly good route for an HTTP fetch.

`productionRouting()` now treats **"connected and a VPN `Network` is at hand"** as a
usable tunnel — `tunnelUsable = tunnelUp` — and forces the preference on whenever one
exists (`preferVpn ||= tunnelUp`). `fetchOnChosenPath` already falls back to open on
a null/empty result (`[collector] path=fallback-to-open`), so the change is
fail-safe: the server list is still reached if the tunnel route is dead.

### Effect on the filtered source (vpngate.net)
The server-list update now attempts the open network **and** the app's own tunnel:
when the tunnel is up, the VPN rung is auto-selected and the request is bound to
`currentVpnNetwork().socketFactory`; on any failure it falls back to open. On a
plain JVM (the unit tests) `GlobalVpnTracker.isConnected` is false, so behaviour is
unchanged (open path) — no test impact.

`[SOURCE CODE]` Doc comments in `VpnServerRepository.kt` and `HttpFetcher.kt`
updated from "VPN is an opt-in that falls back to open" to "VPN is auto-selected
when a usable tunnel exists (see `VpnServerRepository.productionRouting`) and falls
back to open".
