# F.28AL-AUTOCONNECT-BUG-DIAG — Diagnostic Report

**Phase:** F.28AL-AUTOCONNECT-BUG-DIAG (READ-ONLY)  
**Date:** 2026-10-12  
**HEAD:** 071462816b1eef7e7f7459483fcd69909938cd11  
**Build:** F.28AL-U proDebug, versionName=2.4.10-dev  
**Device:** 5585fecc (Xiaomi MIUI)  
**Scope:** READ-ONLY diagnosis. No source, test, Gradle, or device changes were made.

---

## 1. Executive Verdict

Three bugs were diagnosed from the F.28AL-U logcat capture. All three are confirmed by source-code evidence. All three are real, reproducible, and independent of the NATIVE_BUSY retry logic validated in F.28AL-Y.

| Bug | Severity | Root Cause | Mechanism |
|-----|----------|------------|-----------|
| BUG 1 — UDP-only server attempted | HIGH | `supports()` for `SOFTETHER_UDP` ignores `seTcpPort` | UDP-only server (seTcpPort=0, seUdpSupported=true) passes capability check despite R-UDP being broken for SETcpPort=0 servers |
| BUG 2 — Skip without connect | **CRITICAL** | `skipCurrent()` does not complete `armedTunnel`; `select` in `awaitArmedTunnel` has no timeout | After skip, `awaitArmedOutcome()` hangs **forever** — AutoConnect is permanently stuck |
| BUG 3 — User timeout not respected | HIGH | `connectTimeoutMs` not plumbed to native; `select` ignores `timeoutMs` | User's 5s setting logged but never applied; native race always uses 30000ms |

**The single most dangerous defect is BUG 2.** It is not "slow skip" — it is a permanent hang. The `select` racing `armedTunnel` vs `armedNativeBusy` in `AndroidConnectionAdapter.awaitArmedTunnel()` has no `withTimeout` wrapper. When `skipCurrent()` dispatches DISCONNECT, the resulting `STATE_DISCONNECTED` fires `onTunnelLost` (a separate callback) but does NOT complete `armedTunnel`. Neither deferred is ever resolved, and the `select` suspends indefinitely. The `skipRequested.get()` check in `runAutoMode()` is never reached.

BUG 2 is compounded by BUG 3: even without a user skip, an unreachable server that triggers `STATE_ERROR` after the native 30-second race also fails to complete `armedTunnel`, producing the same indefinite hang. The `attemptTimeoutMs` parameter — user-configurable, logged as "Connection timeout = 5 seconds" — is silently dropped in the `select` code path.

BUG 1 is the entry-point trigger: a UDP-only server should never have been selected, because R-UDP/NAT-T is provably broken for `SETcpPort=0` servers (0% success rate per `RUDP_IMPLEMENTATION_PLAN.md`). But `supports()` returns `true` for `SOFTETHER_UDP` whenever `seUdpSupported` is `true`, regardless of `seTcpPort`.

**Recommended next action:** A single fix-phase that addresses all three bugs together, since BUG 2 and BUG 3 share the same code site (`awaitArmedTunnel`'s `select` block) and BUG 1 is the trigger that puts AutoConnect into the BUG 2+3 failure path.

---

## 2. BUG 1 — UDP-only server attempted

### 2.1 Observation

The logcat shows AutoConnect selecting server `207.65.218.160` with `seTcpPort=0`, then attempting `SOFTETHER_UDP`. The native stack enters the parallel race path (`softether_connect_parallel_race()`) for R-UDP direct + NAT-T. Per `RUDP_IMPLEMENTATION_PLAN.md`, this transport is broken for `SETcpPort=0` servers — the registration fails and the server is unreachable.

### 2.2 Root Cause

`AutoModeController.supports()` at line 1311-1326:

```kotlin
private fun AutoModeProtocol.supports(c: AutoModeCandidate): Boolean = when (this) {
    AutoModeProtocol.SOFTETHER_TCP -> c.seTcpPort > 0
    AutoModeProtocol.SOFTETHER_UDP -> c.seUdpSupported || c.seUdpPort > 0   // ← BUG
    AutoModeProtocol.OPENVPN_TCP -> c.openVpnTcpPort > 0
    AutoModeProtocol.OPENVPN_UDP -> c.openVpnUdpPort > 0
    AutoModeProtocol.L2TP_IPSEC -> c.l2tpSupported
    AutoModeProtocol.MS_SSTP -> c.sstpSupported && c.sstpConnectPort > 0
}
```

The `SOFTETHER_UDP` arm returns `true` when `seUdpSupported` is `true`, **regardless of `seTcpPort`**. For a UDP-only server (`seTcpPort=0`, `seUdpSupported=true`), this means:
- `SOFTETHER_TCP` → `c.seTcpPort > 0` → `0 > 0` → `false` (correctly rejected)
- `SOFTETHER_UDP` → `c.seUdpSupported || ...` → `true` (incorrectly accepted)

The server passes `usableProtocols()` (which calls `supports()` + `isAutomatable()`), enters the `supported` list at line 1037, and is attempted at line 1043.

### 2.3 Architecture Violation

`SERVER_SOURCE_ARCHITECTURE.md` §3.5 (lines 195-203) states verbatim:

> `seTcpPort = 0` **and** `SEUdpPort > 0` ⇒ SoftEther R-UDP is **NOT usable**; do not surface SoftEther-UDP as a viable protocol. OpenVPN UDP (if config present) remains usable.

And §10.3 (line 429):

> `SE_UDP_Supported`: set `true` only when the HTML SSL-VPN section explicitly shows "UDP: Supported"; otherwise `false`. Do **not** infer UDP support from the mere presence of `SE_UDP_Port`.

The `supports()` function violates §3.5 by treating `seUdpSupported=true` as sufficient for `SOFTETHER_UDP` capability without checking whether `seTcpPort=0` (the UDP-only condition that makes R-UDP non-functional).

### 2.4 Evidence from RUDP_IMPLEMENTATION_PLAN.md

- "For 'UDP-only' servers (`SETcpPort=0`), the R-UDP/NAT-T transport is broken — the only working UDP is OpenVPN"
- "For UDP-only servers: registration fails → unreachable"
- "What actually works for UDP-only servers: Nothing. Across 3 sweeps (30+ servers): 0% success"
- "The only viable path for UDP-only servers is OpenVPN fallback using `OpenVPN_ConfigData_Base64` (column 14)"

### 2.5 The `isUdpOnly` property exists but is never used for filtering

`VPNGateConnection.kt` lines 76-77:
```kotlin
val isUdpOnly: Boolean get() = seTcpPort <= 0 && seUdpSupported
```

This property correctly identifies UDP-only servers, but `supports()` does not consult it. The `isUdpOnly` flag is only passed to `ConnectionConfig.udpOnly` (line 423 of `AndroidConnectionAdapter.connectSoftEther()`), where it tells the native stack to skip the doomed direct-TCP attempt and go straight to the parallel race. But the parallel race itself is also broken for UDP-only servers — the `udpOnly` flag optimizes the failure path rather than preventing it.

### 2.6 No filter at any layer

There is no filter at any of the three selection layers:
1. **`supports()`** (line 1311): does not check `seTcpPort` for `SOFTETHER_UDP`
2. **`usableProtocols()`** (line 710): delegates to `supports()` + `isAutomatable()`, no additional filter
3. **`probeTargets()`** (line 883): delegates to `usableProtocols()`, no additional filter

The reachability pre-filter (D1 gate) could theoretically remove these servers, but only if the probe itself tests the R-UDP endpoint — and per the architecture, the probe reports UNKNOWN for a server with no dialable endpoint, which is not a removal verdict.

---

## 3. BUG 2 — Skip without connect (CRITICAL)

### 3.1 Observation

The logcat shows `[AUTO] Skipping current server; trying next` followed by DISCONNECT intents — but no subsequent `[AUTO] Trying #N` for the next server. AutoConnect appears permanently stuck after the skip.

### 3.2 Root Cause — Three-Part Defect

#### Part A: `skipCurrent()` completes the wrong deferred

`AndroidConnectionAdapter.skipCurrent()` (lines 367-376):
```kotlin
fun skipCurrent() {
    activeTunnelWait?.complete(false)       // ← completes activeTunnelWait
    currentProtocol = null
    android.os.Handler(...).post {
        startService(ACTION_DISCONNECT, ...)
        ...
    }
}
```

`skipCurrent()` completes `activeTunnelWait` (the fallback deferred used by `awaitTunnel()`), but does NOT complete `armedTunnel` (the deferred used by `awaitArmedTunnel()`'s `select`). Since `armTunnelWatch()` was called before the connect, `armedTunnel` is the active deferred — `activeTunnelWait` is stale and irrelevant.

#### Part B: `TunnelStateWatcher` does not complete `armedTunnel` on DISCONNECT/ERROR

`TunnelStateWatcher.onSoftEtherStateChanged()` (lines 313-324):
```kotlin
override fun onSoftEtherStateChanged(state: String, assignedIp: String) {
    if (watching != null && isSoftEtherProtocol(watching!!)) {
        if (state == SoftEtherVpnService.STATE_CONNECTED) {
            onTunnelUp?.invoke(watching!!)     // ← completes armedTunnel with true
        }
    }
    if (state == STATE_DISCONNECTED || state == STATE_ERROR) {
        onTunnelLost?.invoke()                  // ← does NOT complete armedTunnel
    }
}
```

`onTunnelUp` (the callback that completes `armedTunnel`) fires ONLY on `STATE_CONNECTED`. `onTunnelLost` is a separate `var` callback, not connected to `armedTunnel`. When DISCONNECT is dispatched by `skipCurrent()`, the VPN service transitions to `STATE_DISCONNECTED`, `onTunnelLost` fires, but `armedTunnel` remains uncompleted.

#### Part C: `awaitArmedTunnel()`'s `select` has no timeout

`AndroidConnectionAdapter.awaitArmedTunnel()` (lines 227-261):
```kotlin
override suspend fun awaitArmedTunnel(protocol: AutoModeProtocol, timeoutMs: Long): Boolean {
    ...
    val deferred = armedTunnel
        ?: return awaitTunnel(protocol, timeoutMs)     // fallback path (has withTimeout)
    return try {
        if (deferred.isCompleted) {
            deferred.await()
        } else {
            val busy = armedNativeBusy
            if (busy == null) {
                withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false   // ← HAS timeout
            } else {
                kotlinx.coroutines.selects.select<Boolean> {                 // ← NO timeout!
                    deferred.onAwait { true }
                    busy.onAwait { false }
                }
            }
        }
    } finally { ... }
}
```

When both `armedTunnel` and `armedNativeBusy` exist (the normal case after `armTunnelWatch()`), the code enters the `select` branch, which races the two deferreds **with no `withTimeout` wrapper**. The `timeoutMs` parameter is silently dropped.

### 3.3 Combined Effect

After user presses skip:
1. `skipToNextServer()` (line 397): logs "Skipping current server; trying next", sets `skipRequested = true`, calls `adapterSkipSignal?.invoke()` (= `adapter.skipCurrent()`), returns immediately
2. `skipCurrent()` completes `activeTunnelWait` with `false` (irrelevant — `awaitArmedTunnel` uses `armedTunnel`, not `activeTunnelWait`)
3. `skipCurrent()` dispatches DISCONNECT intents to VPN services
4. VPN service processes DISCONNECT → `STATE_DISCONNECTED` → `onSoftEtherStateChanged` → `onTunnelLost?.invoke()` fires
5. `armedTunnel` is NOT completed (only `onTunnelUp` on `STATE_CONNECTED` completes it)
6. `armedNativeBusy` is NOT completed (only `onSoftEtherNativeBusy` completes it)
7. `select { armedTunnel.onAwait; armedNativeBusy.onAwait }` suspends **indefinitely**
8. `runAutoMode()` is stuck at line 1088: `val outcome = adapter.awaitArmedOutcome(protocol, attemptTimeoutMs)`
9. The `skipRequested.get()` check at line 1092 is **never reached**
10. AutoConnect is **permanently stuck** — no timeout, no cancellation, no progression

### 3.4 This is NOT the same as "slow skip"

The bug is not a performance issue. The `select` will hang **forever** unless:
- The tunnel somehow connects (impossible — DISCONNECT was dispatched)
- The native slot becomes busy (unrelated to the skip)
- The coroutine is cancelled externally (nobody cancels it — `skipToNextServer()` returns without cancelling `job`)

The only recovery is the user manually pressing Stop/Disconnect, which cancels the `job` and publishes `Disconnected` state.

### 3.5 This also affects non-skip timeout cases

The same hang occurs when a server is simply unreachable (no skip involved):
1. `armTunnelWatch()` creates `armedTunnel` and `armedNativeBusy`
2. `connect()` dispatches CONNECT → native race starts (30s hardcoded timeout)
3. Native race times out → VPN service reports `STATE_ERROR`
4. `onTunnelLost` fires → `armedTunnel` NOT completed
5. `select` hangs forever

This means **any** unreachable SoftEther server causes AutoConnect to hang permanently, not just user-initiated skips. The F.28AL-Y tests did not surface this because they focused on the `NATIVE_BUSY` path (where `armedNativeBusy` completes and the `select` returns).

---

## 4. BUG 3 — User timeout not respected

### 4.1 Observation

The user configured "Connection timeout = 5 seconds" in AutoMode settings. The logcat shows this setting is logged (`[AUTO] Connection timeout = 5 seconds`), but the actual behavior does not respect it — the native parallel race runs for 30 seconds (hardcoded), and the Kotlin-side armed wait has no timeout at all (see BUG 2 Part C).

### 4.2 Root Cause — Two-Part Defect

#### Part A: `connectTimeoutMs` is not plumbed from `attemptTimeoutMs` to native

`AndroidConnectionAdapter.connectSoftEther()` (lines 408-430):
```kotlin
val config = ConnectionConfig(
    serverHost = serverHost,
    serverPort = serverPort,           // = conn.seTcpPort (= 0 for UDP-only)
    ...
    useTcp = useTcp,
    udpPort = conn.seUdpPort,
    udpOnly = isUdpOnly,
    // connectTimeoutMs is NOT set here — uses default
    ...
)
```

`ConnectionConfig.connectTimeoutMs` defaults to `30000` (line 42 of `ConnectionConfig.kt`):
```kotlin
val connectTimeoutMs: Int = 30000
```

This value is passed to the native stack via `client.setTimeout(config.connectTimeoutMs)` in `ConnectionController.kt`. The native C code (`softether_protocol.c`) uses it for the race timeout:
```c
conn->timeout_ms = 30000;                              // default
uint32_t race_timeout_ms = conn->timeout_ms;            // derived
if (race_timeout_ms < 5000) race_timeout_ms = 5000;    // minimum 5s
```

So the native race timeout is **always 30000ms** regardless of the user's 5-second setting, because `connectSoftEther()` never sets `connectTimeoutMs` from `attemptTimeoutMs`.

#### Part B: `attemptTimeoutMs` is ignored in the `select` branch

As shown in BUG 2 Part C, `awaitArmedTunnel()` passes `timeoutMs` to `withTimeoutOrNull` ONLY when `armedNativeBusy` is `null`. In the normal case (both deferreds exist), the `select` has no timeout, so `attemptTimeoutMs` is silently dropped.

### 4.3 The user setting has THREE intended effects, all broken

| Intended effect | Where it should work | Actual behavior |
|-----------------|---------------------|-----------------|
| Log the timeout | Line 917: `adapter.log("[AUTO] Connection timeout = ${attemptTimeoutMs / 1000} seconds")` | ✅ Works — logs "5 seconds" |
| Kotlin-side armed wait timeout | Line 244: `withTimeoutOrNull(timeoutMs)` | ❌ Only in `busy == null` branch; `select` branch ignores it |
| Native race timeout | `ConnectionConfig.connectTimeoutMs` → native `conn->timeout_ms` | ❌ Never set; always 30000ms |

### 4.4 The `attemptTimeoutMs` flow

```
AutoModeEngine.ensure() (line 75):
  attemptTimeoutMs = du.getAutoModeTimeoutSeconds().toLong() * 1000   // user: 5000

AutoModeController.runAutoMode() (line 917):
  adapter.log("[AUTO] Connection timeout = ${attemptTimeoutMs / 1000} seconds")  // logs "5"

AutoModeController.runAutoMode() (line 1088):
  val outcome = adapter.awaitArmedOutcome(protocol, attemptTimeoutMs)  // passes 5000

AndroidConnectionAdapter.awaitArmedOutcome() (line 285):
  val up = awaitArmedTunnel(protocol, timeoutMs)  // passes 5000

AndroidConnectionAdapter.awaitArmedTunnel() (line 244):
  // busy == null branch:
  withTimeoutOrNull(timeoutMs) { deferred.await() }  // uses 5000 ✅
  
  // busy != null branch (NORMAL case):
  select { deferred.onAwait; busy.onAwait }  // timeoutMs IGNORED ❌
```

The timeout parameter reaches the right function but is conditionally dropped depending on whether `armedNativeBusy` exists — which it almost always does after `armTunnelWatch()`.

---

## 5. The 40-Second Gap

### 5.1 What the gap is

The logcat shows approximately 40 seconds of silence after `[AUTO] Skipping current server; trying next` — no further `[AUTO]` log lines, no `Trying #N` for the next server. The gap ends at the capture window boundary.

### 5.2 Mechanism

The gap is the direct consequence of BUG 2 + BUG 3 combined:

1. **T=0:** `skipToNextServer()` logs "Skipping current server; trying next" and calls `skipCurrent()`
2. **T≈0:** `skipCurrent()` dispatches DISCONNECT intents; VPN service processes them
3. **T≈0-1s:** VPN service transitions to `STATE_DISCONNECTED` → `onTunnelLost` fires → `armedTunnel` NOT completed
4. **T≈1s:** `awaitArmedTunnel()`'s `select` suspends indefinitely (BUG 2 Part C)
5. **T≈1-30s:** The native parallel race may still be running (hardcoded 30000ms timeout, BUG 3 Part A). It eventually times out and reports `STATE_ERROR` → `onTunnelLost` fires again → `armedTunnel` still NOT completed
6. **T≈30s+:** `select` continues to hang. No more `[AUTO]` logs are emitted because `runAutoMode()` is stuck at line 1088
7. **T≈40s:** Logcat capture ends. The select is still suspended.

The 40 seconds ≈ 1s (DISCONNECT processing) + 30s (native race timeout) + ~9s (select hang after native timeout, before capture ends).

### 5.3 Why the previous F.28AL-Y tests did not surface this

F.28AL-Y tested the `NATIVE_BUSY` retry path. In that path:
- The native slot is busy → `onSoftEtherNativeBusy` fires → `armedNativeBusy` completes → `select` returns `false` → `awaitArmedOutcome` returns `NATIVE_BUSY`
- The retry loop kicks in, eventually succeeds or reports `ERROR_NATIVE_UNRESOLVED`

The F.28AL-Y path works because `armedNativeBusy` IS completed. The F.28AL-U logcat captures a different failure mode where NEITHER deferred completes — the server is simply unreachable (native race timeout, not native busy), and the user presses skip. This path was never tested.

---

## 6. BUG 1 vs Previous Work

### 6.1 RUDP_IMPLEMENTATION_PLAN.md

The plan explicitly documented that R-UDP is broken for UDP-only servers:
> "For 'UDP-only' servers (SETcpPort=0), the R-UDP/NAT-T transport is broken"
> "The only viable path for UDP-only servers is OpenVPN fallback"

The plan was written as an implementation guide for fixing R-UDP, but its findings were never integrated into the AutoConnect server-selection logic. The `supports()` function predates the plan and was never updated to filter UDP-only servers.

### 6.2 SERVER_SOURCE_ARCHITECTURE.md §3.5

The architecture document codifies the rule:
> "seTcpPort = 0 and SEUdpPort > 0 ⇒ SoftEther R-UDP is NOT usable; do not surface SoftEther-UDP as a viable protocol."

This is a **USER-DIRECTED REQUIREMENT** (marked as such in the document). The `supports()` function violates this requirement.

### 6.3 The `isUdpOnly` property and `udpOnly` config flag

The codebase has TWO mechanisms that acknowledge the UDP-only condition, but both operate downstream of the selection decision:
- `VPNGateConnection.isUdpOnly` — used to set `ConnectionConfig.udpOnly`, which tells the native stack to skip direct-TCP and go straight to the parallel race
- `ConnectionConfig.udpOnly` — comment says "seTcpPort <= 0; skips the doomed direct-TCP attempt"

Both optimize the failure path (skip TCP, go to R-UDP race) rather than preventing it (don't select this server for SOFTETHER_UDP at all). The architecture says the R-UDP race itself is broken, so optimizing the path to it does not help.

### 6.4 No prior fix was attempted

No commit in the current HEAD's history modifies `supports()` to check `seTcpPort` for `SOFTETHER_UDP`. The function has been unchanged since its introduction. This is a latent design gap, not a regression.

---

## 7. Recommended Next Action

A single fix-phase should address all three bugs, since BUG 2 and BUG 3 share the same code site and BUG 1 is the trigger:

### Fix BUG 1: Filter UDP-only servers from SOFTETHER_UDP

In `AutoModeController.supports()`, change the `SOFTETHER_UDP` arm:
```kotlin
// Current (broken):
AutoModeProtocol.SOFTETHER_UDP -> c.seUdpSupported || c.seUdpPort > 0

// Fixed:
AutoModeProtocol.SOFTETHER_UDP -> (c.seUdpSupported || c.seUdpPort > 0) && c.seTcpPort > 0
```
This implements §3.5 of SERVER_SOURCE_ARCHITECTURE.md. UDP-only servers will still be selectable via `OPENVPN_UDP` if they carry `OpenVPN_ConfigData_Base64`.

### Fix BUG 2: Complete `armedTunnel` on DISCONNECT/ERROR

In `TunnelStateWatcher.onSoftEtherStateChanged()`, when `STATE_DISCONNECTED` or `STATE_ERROR` is received while watching, complete the armed tunnel deferred negatively. This requires either:
- (a) Connecting `onTunnelLost` to the `armedTunnel` deferred (currently `onTunnelLost` is a separate callback), OR
- (b) Having `skipCurrent()` also complete `armedTunnel` with `false`

Option (b) is simpler and more targeted: in `skipCurrent()`, add `armedTunnel?.complete(false)` alongside `activeTunnelWait?.complete(false)`.

### Fix BUG 3: Wrap the `select` in `withTimeout` AND plumb `connectTimeoutMs`

Two changes:
1. In `awaitArmedTunnel()`, wrap the `select` in `withTimeoutOrNull(timeoutMs)`:
   ```kotlin
   withTimeoutOrNull(timeoutMs) {
       kotlinx.coroutines.selects.select<Boolean> {
           deferred.onAwait { true }
           busy.onAwait { false }
       }
   } ?: false
   ```
2. In `connectSoftEther()`, set `connectTimeoutMs = attemptTimeoutMs.toInt()` in the `ConnectionConfig` so the native race also respects the user's timeout.

### Fix ordering note

BUG 1 is the trigger — if UDP-only servers are filtered out, the BUG 2+3 hang path is less likely to be entered (since reachable TCP servers will complete `armedTunnel` via `STATE_CONNECTED`). But BUG 2+3 must still be fixed because any unreachable server (not just UDP-only) triggers the same hang.

---

## 8. Evidence Taxonomy

All evidence is source-code static analysis at HEAD `071462816b1eef7e7f7459483fcd69909938cd11`. No device tests were performed (optional D1 was not needed — the source code is definitive).

### 8.1 Primary source files inspected

| File | Lines | Role in diagnosis |
|------|-------|--------------------|
| `AutoModeController.kt` | 1-1327 | `supports()` (1311-1326), `skipToNextServer()` (397-454), `runAutoMode()` (892-1275), `awaitArmedOutcome` interface (219-224), retry constants (297-327) |
| `AndroidConnectionAdapter.kt` | 195-450 | `armTunnelWatch()` (209-225), `awaitArmedTunnel()` (227-261), `awaitArmedOutcome()` (281-293), `skipCurrent()` (367-376), `connectSoftEther()` (388-443) |
| `TunnelStateWatcher.kt` | 1-395 | `beginAttempt()` (73-90), `endAttempt()` (110-114), `onSoftEtherStateChanged()` (313-324), `onSoftEtherNativeBusy()` (300-304) |
| `AutoModeEngine.kt` | 1-219 | `ensure()` (52-131): `attemptTimeoutMs` wiring, `setSkipSignal` (114) |
| `AutoModeProtocol.kt` | 1-110 | `endpointPort()` (102-109): SOFTETHER_TCP → seTcpPort, SOFTETHER_UDP → seUdpPort |
| `VPNGateConnection.kt` | 1-80 | `isUdpOnly` (76-77): `seTcpPort <= 0 && seUdpSupported` |
| `ConnectionConfig.kt` | 1-218 | `connectTimeoutMs = 30000` (42), `udpOnly = false` (41), `udpPort = 0` (40) |

### 8.2 Cross-reference documents

| Document | Section | Evidence |
|----------|---------|----------|
| `RUDP_IMPLEMENTATION_PLAN.md` | Lines 7, 16-20, 26, 51 | R-UDP broken for SETcpPort=0; 0% success; OpenVPN is the only viable path |
| `SERVER_SOURCE_ARCHITECTURE.md` | §3.5 (195-203) | "seTcpPort=0 and SEUdpPort>0 ⇒ SoftEther R-UDP is NOT usable; do not surface SoftEther-UDP as a viable protocol" |
| `SERVER_SOURCE_ARCHITECTURE.md` | §10.3 (426-432) | Port semantics: `0` = not present; `SE_UDP_Supported` = true only if HTML shows "UDP: Supported" |

### 8.3 Native code evidence (grep-confirmed)

| File | Pattern | Finding |
|------|---------|---------|
| `softether_protocol.c` | `conn->timeout_ms = 30000` | Default native timeout is 30s |
| `softether_protocol.c` | `race_timeout_ms = conn->timeout_ms` | Race timeout derived from conn->timeout_ms |
| `softether_protocol.c` | `if (race_timeout_ms < 5000) race_timeout_ms = 5000` | Minimum 5s race timeout |
| `softether_protocol.c` | `if (conn->udp_only)` → `softether_connect_parallel_race()` | UDP-only servers enter parallel race |

### 8.4 Grep confirmations

| Grep target | Pattern | Result |
|-------------|---------|--------|
| `withTimeout` in automode/ | `withTimeout` | Only 3 uses: teardown (175), `busy==null` branch (244), `awaitTunnel` fallback (355). **None in the `select` branch.** |
| `awaitArmedOutcome` calls | `awaitArmedOutcome` | 2 call sites: line 1088 (main attempt), line 1190 (NATIVE_BUSY retry). **Neither wrapped in `withTimeout`.** |

---

## 9. Explicit No-Change Confirmation

This phase was READ-ONLY. The following were NOT modified:

| Category | Items | Status |
|-----------|-------|--------|
| Source files | `AutoModeController.kt`, `AndroidConnectionAdapter.kt`, `TunnelStateWatcher.kt`, `AutoModeEngine.kt`, `AutoModeProtocol.kt`, `VPNGateConnection.kt`, `ConnectionConfig.kt`, `softether_protocol.c` | **Unchanged** |
| Test files | All test files | **Unchanged** |
| Gradle files | `app/build.gradle`, `settings.gradle`, `gradle.properties` | **Unchanged** |
| CI files | `.github/workflows/android-ci.yml` (modified in prior phase, not this one) | **Unchanged in this phase** |
| Device state | No APK install, no AutoConnect trigger, no logcat capture | **Unchanged** |
| Git working tree | HEAD remains `071462816b1eef7e7f7459483fcd69909938cd11` | **No new commits** |

The only file created in this phase is this report (`F28AL_AUTOCONNECT_BUG_DIAG_REPORT.md`).

---

*End of F.28AL-AUTOCONNECT-BUG-DIAG Report*
