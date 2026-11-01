# AUTOCONNECT-AUDIT — Comprehensive Read-Only Behavioural Audit

**Date:** 2026-10-18
**HEAD:** `c5fa4362a0c1a39166112fd1f6be552418af17dd` (`c5fa436`)
**Spec version:** 1.0.30
**Mode:** READ-ONLY. No source edit, no spec edit, no commit, no build, no test run, no device.
**Status:** This file is **untracked and NOT committed** (per the phase's authorized-file rule).

**Evidence labels used throughout:** `SOURCE CODE` · `TEST` · `PHYSICAL DEVICE` · `INFERENCE` · `UNKNOWN` · `PRIOR REPORT`.

---

## 1. Verdict

**The current implementation MATCHES the operator's stated rule (R1–R5) on its core mechanics, and does so with a two-layer timeout enforcement that is more precise than the rule requires. It deviates on three specifics, one of which is a genuine contract breach.**

| # | Statement | Verdict |
|---|-----------|---------|
| 1 | The timeout is user-settable, read from Settings, and enforced per (server, protocol) attempt. | **MATCH** |
| 2 | The loop is server-outer / protocol-inner, giving each protocol its own full timeout window. | **MATCH** |
| 3 | A timeout advances to the next protocol, then to the next server, then stops. | **MATCH** |
| 4 | The documented default timeout is 5 s. | **MISMATCH** — the code default is **12 s**. |
| 5 | Max time per server is exactly `N_protocols × T`. | **PARTIAL** — true only while the native slot is free; up to `T + min(5T, 60 s)` per protocol otherwise. |
| 6 | The rule is documented in the spec. | **MATCH** — spec §6.7 R1–R8 exists and is detailed. |
| 7 | Active-tunnel handling: disconnect first, then proceed. | **MISMATCH** — the production path neither disconnects-then-proceeds nor refuses. |

The single most important finding is **#7**, because it is a behavioural divergence rather than a documentation drift: the UI button calls `onButtonPressed()`, which never consults `isTunnelBusy()`. The guard that would refuse a competing tunnel exists only in `startOrRefuseIfNoServers()` — a function with **zero production callers** (§5.6).

The second most important finding is **#4**: spec §6.7 R5 states a 5 s default, `DataUtil` uses 12 s, and `DEFAULT_ATTEMPT_TIMEOUT_MS` is 25 s. Three different numbers describe the same setting, and **no test pins any of them** (§7).

---

## 2. Pre-conditions

| Check | Result | Evidence |
|-------|--------|----------|
| `HEAD` | `c5fa4362a0c1a39166112fd1f6be552418af17dd` | SOURCE CODE |
| `HEAD == origin/main` | yes | SOURCE CODE |
| Branch | `main` | SOURCE CODE |
| Spec document version | `1.0.30` (line 8) | SOURCE CODE |
| Tracked dirty files | **none** (only pre-existing untracked dirs: `.workbuddy-ai/`, `emergency/`, `scripts/`, `test-evidence/`) | SOURCE CODE |
| Build / test / device invoked in this phase | **no** | — |

No ABORT condition was met.

---

## 3. Operator's expected rule (verbatim, as supplied to this audit)

> **R1** — The connection timeout is settable by the user (value `T`).
> **R2** — For each server, and for each of the user's selected protocols in selection order, the app is allowed `T` for that protocol.
> **R3** — If that protocol times out, move to the next protocol.
> **R4** — If no protocols remain, move to the next server.
> **R5** — If no servers remain, stop. Maximum time per server = `N_protocols × T`.
> **Non-timeout failure** — a failure that is not a timeout must also transition (not hang, not abort the run).
> **Documentation** — this rule must be documented in the specification.

**Derived questions:**
> **R6** — Where is the timeout READ from (which Settings key)?
> **R7** — Where is it ENFORCED (Kotlin / native / OkHttp)?
> **R8** — What is the loop nesting: server-outer / protocol-inner, or flat?

### 3.1 Numbering collision — read this before §4

The spec already contains a **§6.7 "AutoConnect Button Contract"** with its own **R1–R8**. Those are *different requirements* from the operator rule above. The mapping is:

| Operator rule | Spec §6.7 clause that governs it |
|---------------|----------------------------------|
| R1 (settable `T`) | **R5** (per-server, per-protocol timeout, configurable) |
| R2 (`T` per protocol) | **R5** + **R4** (sequential server testing) |
| R3 (timeout → next protocol) | **R5** stop condition + **R4** |
| R4 (no protocols → next server) | **R4** |
| R5 (no servers → stop) | **R7** (continuous attempts until stop condition) |
| non-timeout failure transition | **R4** / **R7** (no other implicit stop condition) |
| documentation | **§6.7** exists in full |
| derived R6 (read point) | **R5** (`attemptTimeoutMs` plumbing) + **R6** (Settings) |
| derived R7 (enforcement) | **R5** testable clause ("both the armed `select` wait and the native race") |
| derived R8 (nesting) | **§6.3** attempt lifecycle |

To avoid ambiguity, this report writes **`OP-R1..OP-R5`** for the operator's rule and **`SPEC-R1..SPEC-R8`** for spec §6.7.

---

## 4. Spec contract per requirement (quoted, with line numbers)

All quotes are from `PROJECT_SPECIFICATION.md` at HEAD `c5fa436`.

### OP-R1 — settable timeout → SPEC-R5, lines 2360–2371

> **R5. Per-Server, Per-Protocol Timeout** (L2360)
> "The timeout for an attempt is **per server, per protocol** and is **configurable in Settings** (the `attemptTimeoutMs` plumbing in `AutoModeEngine.ensure()`, §10.1)." (L2364)
> "**Default: 5 seconds per protocol attempt.**" (L2365)
> "If the user has enabled multiple protocols, each protocol attempt has its **own independent** timeout window (a server that fails on protocol A within 5 s does not consume protocol B's 5 s budget)." (L2366)

### OP-R2 / OP-R3 — `T` per protocol, timeout → next protocol → SPEC-R5 + SPEC-R4

> **R5 stop condition** (L2367): "If the armed tunnel wait (`awaitArmedTunnel`, §6.4) does not complete within `attemptTimeoutMs`, the attempt is declared failed and the next (server, protocol) pair is tried."
> **R4. Sequential Server Testing** (L2347): "Attempt servers **one by one** from the sorted list." (L2351) "For each server, try the **user-configured protocols in the user-configured priority order**" (L2352). "One-attempt-per-server (§6.5): if a server fails on its highest-priority usable protocol, the next usable protocol follows, then the next server." (L2353)

### OP-R4 / OP-R5 — next server, then stop → SPEC-R4 + SPEC-R7

> **R7. Continuous Attempts Until Stop Condition** (L2387): "**(a)** The end of the server list is reached … → run ends with `ERROR_NO_SERVER` (§6.3)." (L2391) "**(b)** The user presses the AutoConnect button **again** to stop" (L2392).
> "**Stop condition:** There is **no other implicit stop condition.** AutoConnect does not stop on a single server failure, on a NATIVE_BUSY refusal (§6.4 — it continues without rotating), or on any timeout short of exhausting the list." (L2393)

### Non-timeout failure transition → SPEC-R4 / SPEC-R7

Spec is **not silent** but also **not explicit**: it never enumerates a non-timeout failure class. The nearest clauses are SPEC-R7 L2393 ("does not stop on a single server failure") and SPEC-R4 L2353 (rotation). The `NATIVE_ERROR` outcome — a real code path — has **no spec clause at all** (see §9, gap G-6).

### Documentation requirement

**MATCH.** §6.7 exists at L2309–L2401 and is unusually thorough: each clause carries Trigger / Expected behaviour / Stop condition / Fallback path / Testable. Line 2311 states the evidence class as **USER-DIRECTED REQUIREMENT**.

### OP-R6 (derived) — where the timeout is READ

Spec points at "the `attemptTimeoutMs` plumbing in `AutoModeEngine.ensure()`" (L2364) and `DEFAULT_ATTEMPT_TIMEOUT_MS` (L2656, L2371). It does **not** name the preference key, its default, or its range. See §5.1 for the actual key.

### OP-R7 (derived) — where the timeout is ENFORCED

> SPEC-R5 testable (L2369): "the documented implementation must apply `attemptTimeoutMs` to **both** the armed `select` wait (`awaitArmedTunnel`) **and** the native race (`ConnectionConfig.connectTimeoutMs`), not merely log it."

This is the only place the spec names the enforcement layers. It does not mention the 80 % fraction that the code applies to the native layer.

### OP-R8 (derived) — loop nesting

> §6.3 (L2264): "`for each (server, protocol) in ordered candidates:`" — server-outer, protocol-inner.
> SPEC-R4 L2356: "AutoConnect attempts at most N×M ordered (server, protocol) pairs — in sort order then priority order".

### Spec-internal inconsistency worth recording

SPEC-R2 (L2325–L2333) is **internally contradictory**:
- "Expected behaviour: **Disconnect the current tunnel first** … **Then** proceed with the AutoConnect flow (R3–R7)" (L2329–L2330).
- "Stop condition / ordering: … The `AutoModeController` start guard consults `GlobalVpnTracker` to avoid double-owning the tunnel." (L2331).

The first clause demands *disconnect-then-proceed*; the second endorses *refuse*. The code does neither on the production path (§5.6). This is a spec defect independent of the code.

---

## 5. Implementation behaviour map

### 5.1 Settings source of truth (OP-R6)

| Layer | Location | Value |
|-------|----------|-------|
| Preference key | `DataUtil.kt:537` | `SETTING_AUTO_MODE_TIMEOUT_SECONDS = "SETTING_AUTO_MODE_TIMEOUT_SECONDS"` |
| Getter | `DataUtil.kt:332-333` | `getIntSetting(SETTING_AUTO_MODE_TIMEOUT_SECONDS, 12).coerceIn(5, 60)` |
| Setter | `DataUtil.kt:335-337` | `setIntSetting(..., seconds.coerceIn(5, 60))` |
| Settings UI state | `SettingsScreen.kt:190` | `var autoTimeout by remember { mutableStateOf(dataUtil.getAutoModeTimeoutSeconds()) }` |
| Settings UI write | `SettingsScreen.kt:1013-1024` | `val values = (5..60).toList()` → `dataUtil.setAutoModeTimeoutSeconds(values[index])` |
| Settings UI display | `SettingsScreen.kt:528` | `value = "$autoTimeout s"` |
| Controller injection | `AutoModeEngine.kt:75` | `attemptTimeoutMs = du.getAutoModeTimeoutSeconds().toLong() * 1000` |
| Native-slot wait derivation | `AutoModeEngine.kt:82-83` | `(du.getAutoModeTimeoutSeconds().toLong() * 1000L * 5L).coerceAtMost(NATIVE_SLOT_AWAIT_MAX_MS)` |

**Answer to OP-R6:** the timeout is read from SharedPreferences key `SETTING_AUTO_MODE_TIMEOUT_SECONDS`, default **12**, clamped to **[5, 60]** seconds. It is read **twice independently** — once by `AutoModeEngine` for the controller, once by `AndroidConnectionAdapter.connectSoftEther` (`AndroidConnectionAdapter.kt:600`) for the native layer — deliberately, so that one preference remains the single source of truth without widening the `connect()` signature (comment at L588–L595).

**Three conflicting defaults (SOURCE CODE):**

| Symbol | Value | Role |
|--------|-------|------|
| `DataUtil.getAutoModeTimeoutSeconds()` | **12 s** | The real production default |
| `AutoModeController.DEFAULT_ATTEMPT_TIMEOUT_MS` (`AutoModeController.kt:331`) | **25 s** | Constructor default; never reached in production (engine always passes an explicit value) |
| Spec SPEC-R5 L2365 | **5 s** | Documented contract |

**Verdict: MISMATCH.** The documented default (5 s) is not the shipped default (12 s). The 25 s constant is a third figure that contradicts both and is reachable only from tests.

### 5.2 Loop structure (OP-R8)

```
runAutoMode()                                    AutoModeController.kt:1228
├── priorities = protocolPriorityProvider()      :1232   (empty → ERROR_NO_ENABLED_PROTOCOL, :1235-1240)
├── all = overrideServers ?: serverProvider()    :1255
├── if (all.isEmpty()) → Error(emptyServerListMessage()); return   :1271-1275   [SPEC-R1]
├── qualitySorted = all.withActiveRtt(...).sortedForAutoConnect()  :1288-1290
├── gate = reachabilityFilter(targets)           :~1300  [SPEC-R3 pre-pass]
├── servers = <gate selection>                   :1338-1355
├── if (servers.isEmpty()) → Error(ERROR_NO_SERVER); return        :1356-1360
│
├── for (server in servers)                      :1365   ◄── OUTER
│   ├── if (!isActive) return                    :1366
│   ├── if (!attempted.add(key)) continue        :1368-1369
│   ├── if (wedgeTracker.isExcluded(key)) continue :1375-1381
│   ├── supported = usableProtocols(server, priorities)  :1396
│   ├── if (supported.isEmpty()) continue        :1397-1400
│   ├── attempt++ ; log "Trying #n"              :1401-1402
│   │
│   ├── for (protocol in supported)              :1416   ◄── INNER
│   │   ├── setState(Connecting(...))            :1419-1429
│   │   ├── adapter.armTunnelWatch(protocol)     :1435
│   │   ├── adapter.connect(server, protocol)    :1438
│   │   ├── outcome = adapter.awaitArmedOutcome(protocol, attemptTimeoutMs)  :1450
│   │   ├── if (skipRequested) { disconnect; break }        :1454-1460
│   │   ├── if (TUNNEL_UP) { connectedProtocol = protocol; break }  :1462-1465
│   │   ├── if (NATIVE_BUSY) { await slot ≤5T; retry once } :1486-1555
│   │   ├── if (isOwnTunnelEstablished()) { connected; break }      :1569-1573
│   │   ├── if (TIMED_OUT) { log; recordWedge }  :1578-1591
│   │   ├── if (NATIVE_ERROR) { log }            :1599-1605
│   │   ├── adapter.log("Protocol failed"); adapter.disconnect()    :1607-1608
│   │   └── if (outcome != NATIVE_ERROR && !awaitNativeSlotRelease(5T)) → give up :1620-1632
│   │
│   ├── if (connectedProtocol != null) { onSuccess; setState(Connected); startConnectedWatcher; return }  :1635-1659
│   └── log "Connection failed: <server>"        :1661
│
└── log "All compatible servers failed"; setState(Error(ERROR_NO_SERVER)); job = null   :1664-1666
```

**Answer to OP-R8:** **server-outer / protocol-inner** (nested `for`, `AutoModeController.kt:1365` and `:1416`). Matches spec §6.3 L2264. Evidence: SOURCE CODE.

### 5.3 Timeout enforcement point (OP-R7)

Two layers, both driven by the same preference:

**Layer 1 — native connection-level, 80 % of `T`** (`AndroidConnectionAdapter.kt:583-618`)

```kotlin
val attemptTimeoutMs = dataUtil.getAutoModeTimeoutSeconds().toLong() * 1000        // :600
val nativeConnectTimeoutMs =
    (attemptTimeoutMs * NATIVE_TIMEOUT_FRACTION_NUM / NATIVE_TIMEOUT_FRACTION_DEN)  // 4/5
        .coerceAtLeast(MIN_NATIVE_CONNECT_TIMEOUT_MS)                              // 1_500L
...
connectTimeoutMs = nativeConnectTimeoutMs.toInt(),                                  // :636
```
Constants: `NATIVE_TIMEOUT_FRACTION_NUM = 4L`, `NATIVE_TIMEOUT_FRACTION_DEN = 5L` (`:74-75`), `MIN_NATIVE_CONNECT_TIMEOUT_MS = 1_500L` (`:84`).

Consumed by the native client at `ConnectionController.kt:584` and `:1352` via `client.setTimeout(nativeHandle, config.connectTimeoutMs)`; `ConnectionConfig.connectTimeoutMs` defaults to `30000` (`ConnectionConfig.kt:42`).

**Layer 2 — Kotlin coroutine-level, 100 % of `T`** (`AndroidConnectionAdapter.kt:315-374`)

```kotlin
withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false                    // :333 (no busy/failed deferreds)
withTimeoutOrNull(timeoutMs) {                                                // :349
    kotlinx.coroutines.selects.select<Boolean> {
        deferred.onAwait { true }
        busy.onAwait { false }
        failed.onAwait { false }                                              // :354 NATIVE_ERROR
    }
} ?: false
```

**Answer to OP-R7:** enforced in **Kotlin** (a `withTimeoutOrNull` around a `select` over three deferreds) **and** in the **native SoftEther client** (`ConnectionConfig.connectTimeoutMs` → `client.setTimeout`). **OkHttp is not involved** — it is used only for the server-list fetch path, not for Auto Mode connection attempts.

The 80 % split is deliberate and documented (`:602-615`): the native verdict must arrive *before* the controller stops waiting, so an unresponsive server is reported as a native failure and the process-wide slot is released before rotation. Nothing that could have succeeded is lost, because a handshake that cannot finish in 80 % of the window could not produce a verified tunnel in 100 %.

**Spec alignment:** SPEC-R5 L2369 demands the timeout be applied to *both* the armed select and the native race. The code does exactly that — but with a **0.8 factor** on the native layer that the spec does not mention. That is a deviation in *value*, not in *architecture*; it is intentional and justified in-code.

### 5.4 Transition on timeout

`AttemptOutcome.TIMED_OUT` is produced when `withTimeoutOrNull` expires with no tunnel, no refusal and no terminal failure.

Handling (`AutoModeController.kt:1578-1608`):
1. Log `"[AUTO] TIMED_OUT after ${attemptTimeoutMs}ms on <host>/<proto>; cleanup then next protocol (no same-server retry)"`.
2. `recordWedge(server, key)` — once per server per run (`wedgedThisServer` guard, `:1587-1590`). This is the only outcome that implicates the server (`:1584-1586`).
3. Fall through to the shared failure path: `log("Protocol failed: <proto>")` + `adapter.disconnect()` (`:1607-1608`).
4. `awaitNativeSlotRelease(nativeSlotAwaitMs)` unless the outcome was `NATIVE_ERROR` (`:1620-1622`).
5. The inner `for` advances to the **next protocol**; when exhausted, the outer `for` advances to the **next server**.

**There is no same-server retry on timeout** — explicitly, by design (`:1575-1577`). Evidence: SOURCE CODE.

**Verdict: MATCH** for OP-R3/OP-R4.

### 5.5 Transition on non-timeout failure

Four distinct non-timeout exits, all of which transition rather than hang or abort:

| Cause | Location | Transition |
|-------|----------|-----------|
| `AttemptOutcome.NATIVE_ERROR` (stack reported terminal failure before the budget expired: refused port, rejected handshake, non-SoftEther Hello) | `:1599-1605` | Falls through to "Protocol failed" → **next protocol**. Explicitly **skips** the slot wait (`:1620`), because the stack already delivered a verdict and released the slot. |
| `AttemptOutcome.NATIVE_BUSY` (own previous native operation still running; server never contacted) | `:1486-1555` | Waits for observable slot release (≤ `nativeSlotAwaitMs`), then **retries the same protocol once**. If the retry fails, falls through to next protocol. If the slot never releases → `ERROR_NATIVE_UNRESOLVED`, terminal. |
| `VpnPermissionMissingException` | `:1439-1444`, `:1531-1536` | `disconnect()`; `Error(ERROR_VPN_PERMISSION)`; `return` — **terminal for the whole run**. |
| Generic `Exception` from `connect()` | `:1445-1449`, `:1537-1541` | `disconnect()`; `break` — **skips remaining protocols, advances to next server**. |

`AttemptOutcome` enum (`AutoModeController.kt:299-...`): `TUNNEL_UP`, `NATIVE_BUSY`, `TIMED_OUT`, `NATIVE_ERROR`. Evidence: SOURCE CODE.

**Verdict: MATCH** for "a non-timeout failure must transition". Note the asymmetry: a generic connect exception jumps to the next **server** (skipping sibling protocols), whereas `NATIVE_ERROR` advances to the next **protocol**. That asymmetry is intentional (a transport-level throw means the adapter itself failed, not the protocol) but is undocumented in the spec.

### 5.6 Stop condition

**Terminal stops (whole run ends):**

| Stop | Location | State |
|------|----------|-------|
| Server list exhausted | `:1664-1666` | `Error(ERROR_NO_SERVER)` |
| Empty candidate list (pre-loop) | `:1271-1275` | `Error(emptyServerListMessage())` → "Server list is empty. Please update the server list first." (`strings.xml:254`) |
| No protocol enabled (pre-loop) | `:1235-1240` | `Error(ERROR_NO_ENABLED_PROTOCOL)` |
| All servers filtered out as unreachable | `:1356-1360` | `Error(ERROR_NO_SERVER)` |
| Native slot never released | `:1510-1523`, `:1620-1632` | `Error(ERROR_NATIVE_UNRESOLVED)` |
| VPN permission missing | `:1439-1444`, `:1531-1536` | `Error(ERROR_VPN_PERMISSION)` |
| User re-press while `Connecting` | `onButtonPressedWithoutGuard()` `:965-971` → `stop()` `:888-919` | `Disconnected` |
| User re-press while `Connected` | `:969` → `disconnectNow()` `:982+` | `Disconnected` |

**SPEC-R7 compliance:** L2393 asserts "There is **no other implicit stop condition**" beyond list exhaustion and user re-press. The code has **four more** (`ERROR_NO_ENABLED_PROTOCOL`, `ERROR_NATIVE_UNRESOLVED`, `ERROR_VPN_PERMISSION`, all-unreachable). Three are legitimate and arguably belong in the contract; the spec simply does not list them. **Verdict: PARTIAL** — the spec's absolute claim is false as written.

**Active-tunnel handling (SPEC-R2) — the significant divergence:**

The UI path is:
```
AutoModeScreen.kt:166/184/215/233/293/328/337  →  viewModel.onButtonPressed()
AutoModeViewModel.kt:31                        →  AutoModeEngine.onButtonPressed()
AutoModeEngine.kt:42                           →  ensure().onButtonPressed()
AutoModeController.kt:924-929                  →  onButtonPressedWithoutGuard()
AutoModeController.kt:965-971                  →  Disconnected/Error → start(); Connecting → stop(); Connected → disconnectNow()
```

`onButtonPressed()` never consults `isTunnelBusy()`. `start()` (`:879-885`) guards only on `isRunning`. `runAutoMode()` consults `isTunnelBusy()` **only** on the reconnect path (`:1263`).

The function that *does* contain the tunnel-busy refusal —
```kotlin
fun startOrRefuseIfNoServers(): Boolean {            // :947
    if (_state.value !is Disconnected && !is Error) { onButtonPressedWithoutGuard(); return false }
    if (isTunnelBusy()) { adapter.log("[AUTO] start refused: ..."); return false }   // :955
    ...
}
```
— has **zero production callers**. `grep -rn startOrRefuseIfNoServers app/src` returns only the declaration plus five test call sites (`AutoModeEmptyListGuardTest.kt:77,91,109,143`, `VpnM24x10TaskTest.kt:388,412`).

**Consequences (SOURCE CODE + INFERENCE):**

| Scenario | Spec SPEC-R2 expects | Code actually does |
|----------|----------------------|--------------------|
| Auto Mode is `Connected`, user presses | disconnect, **then proceed** with a new run | `disconnectNow()` → stays `Disconnected`, **no new run** |
| A **manual** tunnel is active, Auto Mode state is `Disconnected`, user presses | disconnect, then proceed | **starts a run immediately** — no disconnect, no refusal |
| A manual connect is still *coming up*, user presses | not double-own the tunnel | **starts a run** — the `isTunnelBusy()` guard is not on this path |

The third row is the one with real risk: `SoftEtherVpnService` could receive a second `ACTION_CONNECT` while a manual connect is in flight. Whether the service itself serialises this is **UNKNOWN** in this audit (the service's own start gate was not in scope) — see §8.

**Verdict: MISMATCH** against SPEC-R2's primary expectation, and the guard the spec's own stop-condition clause cites is not wired to the button.

### 5.7 One-attempt-per-server and capability filtering (SPEC-R4/SPEC-R5 fallback)

`usableProtocols()` (`:1046-1049`) = `priorities.filter { it.isUsableFor(candidate) }` = `supports(candidate) && isAutomatable()`.

`isAutomatable()` (`:1067-1070`): `L2TP_IPSEC → false`, else `adapter.canAutoConnect(this)`.

`supports()` (`:1777-1808`):
```kotlin
SOFTETHER_TCP -> c.seTcpPort > 0
SOFTETHER_UDP -> (c.seUdpSupported || c.seUdpPort > 0) && c.seTcpPort > 0    // :1794-1795
OPENVPN_TCP   -> c.openVpnTcpPort > 0
OPENVPN_UDP   -> c.openVpnUdpPort > 0
L2TP_IPSEC    -> c.l2tpSupported
MS_SSTP       -> c.sstpSupported && c.sstpConnectPort > 0
```

The `&& c.seTcpPort > 0` on `SOFTETHER_UDP` is **BUG 1's fix** (`:1779-1793`), and it is exactly what SPEC-R5's fallback clause (L2368) demands: "UDP-only servers (`seTcpPort=0`, `seUdpSupported=true`) must **NOT** be selected for `SOFTETHER_UDP`". **MATCH.**

`endpointPort()` (`AutoModeProtocol.kt:116-123`) never fabricates or borrows a port: `SOFTETHER_UDP → candidate.seUdpPort`, and MS_SSTP's `UNKNOWN` provenance yields 0 rather than 443. Matches spec §6.2 L2258.

### 5.8 Actual time bound per server (OP-R5)

The operator's rule says max time per server = `N_protocols × T`. The code can exceed it:

- Healthy path: one `T` wait per protocol; `awaitNativeSlotRelease` returns **immediately** when the slot is free (`AndroidConnectionAdapter.kt:455`: `if (!isNativeSlotBusy()) return true`). → `N × T`. **Rule holds.**
- Failed attempt while the slot is still held: `T` + up to `nativeSlotAwaitMs` = `min(5T, 60_000)`.
- `NATIVE_BUSY` path: up to `5T` waiting, then a **retry** with another `T` → up to `6T` for a single (server, protocol) pair.

With `T = 12 s` (the shipped default): worst case per protocol ≈ `12 + 60 = 72 s`; worst case per server with 2 protocols ≈ `144 s`, versus the rule's `24 s`.

**Verdict: PARTIAL.** `N × T` is the *nominal* bound and the *normal* bound, not a hard upper bound. The spec never states a hard bound either, so this is an undocumented behaviour rather than a spec violation — but it does contradict the operator's stated expectation as an absolute.

---

## 6. GAP TABLE

Verdicts: **MATCH** / **PARTIAL** / **MISMATCH** / **UNKNOWN**.

| ID | Requirement | Expected | Actual | Verdict | Evidence |
|----|-------------|----------|--------|---------|----------|
| G-1 | OP-R1 — settable `T` | user-configurable timeout | Settings list `(5..60)`, key `SETTING_AUTO_MODE_TIMEOUT_SECONDS` | **MATCH** | SOURCE CODE (`DataUtil.kt:332-337,537`; `SettingsScreen.kt:1013-1024`) |
| G-2 | OP-R1 — documented default | spec says 5 s | code default **12 s**; `DEFAULT_ATTEMPT_TIMEOUT_MS` = 25 s | **MISMATCH** | SOURCE CODE (`DataUtil.kt:333`; `AutoModeController.kt:331`; spec L2365) |
| G-3 | OP-R2 — `T` per selected protocol | each protocol gets its own full window | inner loop per protocol, each `awaitArmedOutcome(protocol, attemptTimeoutMs)` | **MATCH** | SOURCE CODE (`:1416`, `:1450`) |
| G-4 | OP-R2 — selection order | user priority order | `usableProtocols` preserves `priorities` order via `filter` | **MATCH** | SOURCE CODE (`:1046-1049`) |
| G-5 | OP-R3 — timeout → next protocol | advance protocol | falls through to shared failure path, inner `for` advances | **MATCH** | SOURCE CODE (`:1578-1608`, `:1633`) |
| G-6 | OP-R4 — no protocols → next server | advance server | outer `for` advances; `connectedProtocol == null` → log + continue | **MATCH** | SOURCE CODE (`:1635-1662`) |
| G-7 | OP-R5 — no servers → stop | stop, no loop back | `Error(ERROR_NO_SERVER)`, `job = null` | **MATCH** | SOURCE CODE (`:1664-1666`) |
| G-8 | OP-R5 — max per server = `N × T` | hard bound | `N × T` only while slot free; up to `T + min(5T,60s)` per protocol | **PARTIAL** | SOURCE CODE (`:1620-1622`; `AndroidConnectionAdapter.kt:454-468`) |
| G-9 | Non-timeout failure transitions | must transition | `NATIVE_ERROR` → next protocol; generic throw → next server; `NATIVE_BUSY` → wait+retry | **MATCH** | SOURCE CODE (`:1445-1449`, `:1486-1555`, `:1599-1605`) |
| G-10 | Rule documented in spec | documented | §6.7 R1–R8 exists, detailed, testable | **MATCH** | SOURCE CODE (spec L2309-2401) |
| G-11 | OP-R6 — where `T` is read | identifiable key | `SETTING_AUTO_MODE_TIMEOUT_SECONDS`, default 12, range 5–60 | **MATCH** | SOURCE CODE (`DataUtil.kt:537`) |
| G-12 | OP-R7 — where `T` is enforced | named layer(s) | Kotlin `withTimeoutOrNull` **+** native `connectTimeoutMs` (0.8·T) | **MATCH** | SOURCE CODE (`AndroidConnectionAdapter.kt:315-374`, `:600-636`) |
| G-13 | OP-R7 — spec mentions 0.8 factor | spec silent on fraction | native layer = 80 % of `T` | **PARTIAL** | SOURCE CODE (spec L2369 vs `:616-618`) |
| G-14 | OP-R8 — loop nesting | server-outer / protocol-inner | nested `for` at `:1365` / `:1416` | **MATCH** | SOURCE CODE |
| G-15 | SPEC-R1 — empty list refuses | message, zero `ACTION_CONNECT` | `Error("Server list is empty. Please update the server list first.")`, returns pre-loop | **MATCH** | SOURCE CODE (`:1271-1275`; `strings.xml:254`) |
| G-16 | SPEC-R2 — active tunnel | disconnect **then proceed** | `Connected` → `disconnectNow()`, **no** new run | **MISMATCH** | SOURCE CODE (`:965-971`, `:982`) |
| G-17 | SPEC-R2 — manual tunnel guard | do not double-own | `onButtonPressed` path has **no** `isTunnelBusy()`; guard only in test-only `startOrRefuseIfNoServers()` | **MISMATCH** | SOURCE CODE (`:924-929`, `:947-966`) |
| G-18 | SPEC-R3 — probe before first connect | mandatory pre-pass | `reachabilityFilter` gate applied before the loop | **MATCH** | SOURCE CODE (`:1338-1355`) |
| G-19 | SPEC-R4 — one attempt per server/protocol | no same-server retry | `attempted` set + `wedgedThisServer`; TIMED_OUT never retries | **MATCH** | SOURCE CODE (`:1362-1369`, `:1575-1577`) |
| G-20 | SPEC-R5 — BUG 1 UDP-only exclusion | not selected for SOFTETHER_UDP | `&& c.seTcpPort > 0` | **MATCH** | SOURCE CODE (`:1794-1795`) |
| G-21 | SPEC-R5 — timeout applied to both layers | not merely logged | applied to select (100 %) and native (80 %) | **MATCH** | SOURCE CODE (`:349`, `:636`) |
| G-22 | SPEC-R6 — protocol priority from Settings | ordered list, empty = refuse | `protocolPriorityProvider = { du.getAutoModeProtocolPriority() }`; empty → `ERROR_NO_ENABLED_PROTOCOL` | **MATCH** | SOURCE CODE (`AutoModeEngine.kt:67`; `:1235-1240`) |
| G-23 | SPEC-R7 — no other implicit stop | only list-exhaustion + re-press | four more terminals exist | **PARTIAL** | SOURCE CODE (`:1235`, `:1510`, `:1439`, `:1356`) |
| G-24 | SPEC-R7b — second press stops | cancel + teardown | `Connecting → stop()` (cancel + `requestDisconnect()`) | **MATCH** | SOURCE CODE (`:969`, `:888-919`) |
| G-25 | SPEC-R8 — auto-reconnect setting | default ENABLED | `getAutoReconnectInAutoMode()` default `true` | **MATCH** | SOURCE CODE (`DataUtil.kt:349-350`) |
| G-26 | Spec §10.2 `fromId` signature | non-nullable, falls back to `SOFTETHER_UDP` | HEAD is **nullable**, returns `null` | **MISMATCH** (doc drift) | SOURCE CODE (spec L2673-2674 vs `AutoModeProtocol.kt`) |
| G-27 | Whether the service serialises a second `ACTION_CONNECT` while a manual connect is in flight | unknown | not examined in this audit | **UNKNOWN** | — |
| G-28 | Real-device timing: does a timed-out attempt actually rotate within `T` on hardware? | unknown | JVM doubles return outcomes immediately | **UNKNOWN** | — |

**Summary:** 19 MATCH · 4 PARTIAL · 4 MISMATCH · 2 UNKNOWN.

The four MISMATCHes are G-2 (default 5 s vs 12 s), G-16/G-17 (active-tunnel contract), and G-26 (doc drift in §10.2). Only G-17 carries runtime risk.

---

## 7. What the tests pin

**Test classes touching Auto Mode (20):** `automode/{AutoModeControllerTest (2,570 lines), AutoModeNativeBusyTest (871), AutoModeReconnectTest (373), AutoModeEndToEndTest (248), AutoModeServerCursorTest (116), AutoModeLifecycleF28Test, AutoModeEmptyListGuardTest, AutoModeDatasetSourceTest, AutoModeLogStoreTest, TeardownWaiterIdentityTest, TunnelLivenessProbeTest}`, plus `VpnM24x10TaskTest`, `VpnM24x10TaskTest`, `network/{AutoConnectProbeTransportTest, ReachabilityGateTest, ReachabilityNamespaceTest, ServerReachabilityTesterTest}`, `repository/VpnServerRepositoryRoutingTest`, `collector/MergerOracleTest`, `data/remote/EmergencySourceFetcherTest`, `ServerListUpdaterTest`.

**Behaviours that ARE pinned (TEST):**

| Behaviour | Test | Assertion |
|-----------|------|-----------|
| TIMED_OUT rotates, no same-server retry | `AutoModeControllerTest.timedOutRotatesCursorWithoutSameServerRetry` (`:2187`) | exact `connects` sequence `[(srv,TCP),(srv,UDP)]`; terminal `ERROR_NO_SERVER`; log contains `"TIMED_OUT after"` + `"no same-server retry"` |
| All servers fail → `ERROR_NO_SERVER` | `allServersFailYieldsError` (`:385`) | terminal state |
| Rotation until success | `retriesUntilSuccess` (`:354`) | — |
| Stop during Connecting | `stopDuringConnectingStopsEverything` (`:368`) | — |
| User skip advances server | `skipToNextServerMovesToNextCandidateWithoutWaiting` (`:866`) | — |
| BUG 1 — UDP-only excluded | `softEtherUdpRequiresTcpControlChannel` (`:504`) | `supports()` matrix |
| Non-automatable protocol does not consume timeout | `nonAutomatableProtocolIsNeverAttemptedAndDoesNotConsumeTheTimeout` (`:605`) | L2TP never dialled |
| Protocol priority order | `protocolPriorityDeterminesAttemptOrder` (`:1112`) | — |
| First-priority unsupported falls through | `firstPriorityUnsupportedFallsThroughToSecondProtocol` (`:969`) | — |
| Empty list → no connect + dedicated message | `AutoModeEmptyListGuardTest.emptyListRefusesToStart` (`:71`), `emptyListReportsTheDedicatedMessage` (`:84`) | `adapter.connectCalls.isEmpty()`; message == `EMPTY_MESSAGE`; message != `ERROR_NO_SERVER` |
| Two-layer timeout wiring | `AutoModeEndToEndTest` (`:222-246`) | **source-text** assertions: `contains("connectTimeoutMs = nativeConnectTimeoutMs.toInt()")`, `contains("NATIVE_TIMEOUT_FRACTION_NUM")`, `contains("getAutoModeTimeoutSeconds()")`, `contains("withTimeoutOrNull(timeoutMs)")`, `contains("select<Boolean>")` |

**Behaviours that are NOT pinned — the audit's coverage gaps (TEST):**

1. **The default timeout value.** No test asserts 12 s, 5 s, or the 5–60 range. `grep -rn "AutoModeTimeoutSeconds\|SETTING_AUTO_MODE_TIMEOUT" app/src/test` returns exactly **one** hit — the source-text `contains("getAutoModeTimeoutSeconds()")` in `AutoModeEndToEndTest.kt:239`. The G-2 mismatch is therefore invisible to CI.
2. **Per-protocol budget independence by wall clock.** The adapter doubles (`AutoModeControllerTest:135-155`) return an outcome immediately, so `T` is never actually waited. G-3 is pinned structurally (the value is passed to `awaitArmedOutcome`), not temporally.
3. **The `N × T` bound (G-8).** Nothing measures elapsed time per server.
4. **Active-tunnel handling (G-16/G-17).** No test presses the button with a manual tunnel active. `AutoModeEmptyListGuardTest` exercises `startOrRefuseIfNoServers()` — the *test-only* function — so the guard is tested but the production path is not.
5. **`DEFAULT_ATTEMPT_TIMEOUT_MS = 25_000L`.** Never asserted; never reachable in production.
6. **The four extra stop conditions (G-23).** `ERROR_NO_ENABLED_PROTOCOL` and all-unreachable are covered elsewhere; `ERROR_NATIVE_UNRESOLVED` is covered in `AutoModeNativeBusyTest`; `ERROR_VPN_PERMISSION` in `vpnPermissionMissingStopsRunWithDedicatedError` (`:835`). The *spec* does not list them.

**Methodological note:** `AutoModeEndToEndTest` asserts against **file contents** (`adapterSource.readText()`), not behaviour. It is a guard against a wiring regression, but it will pass on code that reads the setting and then ignores it — which is precisely the BUG 3 shape it was written to prevent. Its value is real but narrower than its name suggests.

---

## 8. What requires a device (UNKNOWN)

| # | Question | Why JVM cannot answer it |
|---|----------|--------------------------|
| U-1 | Does a timed-out attempt on real hardware actually rotate within `T`? | The JVM adapter double completes immediately; real `withTimeoutOrNull` + native JNI timing is untested. |
| U-2 | Does `ConnectionConfig.connectTimeoutMs` (0.8·T) actually bound the native `softether_connect_with_hub` call? | Requires the native stack; the code path is `client.setTimeout` but the native contract is not asserted by any JVM test. |
| U-3 | Does the service accept a second `ACTION_CONNECT` while a manual connect is in flight? | G-17's practical impact. `SoftEtherVpnService`'s start gate was outside this audit's scope. |
| U-4 | With `T = 12 s` and 2 protocols, is the observed worst-case per-server time closer to `24 s` or to `144 s`? | Depends on whether the native slot is actually released promptly on hardware. |
| U-5 | Does the UI show the empty-list message before any attempt on a real device? | `AutoModeScreen` string mapping was not exercised. |
| U-6 | Behaviour when the user presses the button during a *manual* connect that is still coming up (G-17's third row). | Needs `GlobalVpnTracker` + service interaction. |

**Device status:** the physical device is currently **DETACHED** (PRIOR REPORT, §12.5.1). None of U-1..U-6 can be closed in this phase.

---

## 9. Recommendations

Ordered by risk. **None of these was implemented — this phase is read-only.**

### R-1 (highest) — wire the active-tunnel guard to the production path (G-17)

`onButtonPressed()` should consult `isTunnelBusy()` before starting, or the UI should call `startOrRefuseIfNoServers()`. Two viable shapes:

- **Option A (refuse):** make `onButtonPressedWithoutGuard()`'s `Disconnected`/`Error` branch check `isTunnelBusy()` and refuse with a log line, matching the spec's stop-condition clause (L2331) and the existing test-only behaviour.
- **Option B (disconnect-then-proceed):** honour SPEC-R2's primary clause literally — issue teardown, await confirmation, then start.

The spec must pick one first; it currently says both (see §4).

### R-2 — reconcile the three timeout defaults (G-2)

Decide whether the shipped default is 5 s (spec) or 12 s (code), then:
- if 12 s is correct, amend SPEC-R5 L2365;
- if 5 s is correct, change `DataUtil.kt:333` and the `SettingsScreen.kt:1019` fallback index;
- retire or justify `DEFAULT_ATTEMPT_TIMEOUT_MS = 25_000L`, which currently contradicts both.

### R-3 — pin the default in a test

Add an assertion on `getAutoModeTimeoutSeconds()`'s default and clamp range. This is a one-line test that would have caught G-2.

### R-4 — document the four extra stop conditions (G-23)

SPEC-R7 L2393's absolute claim ("no other implicit stop condition") is false. Either list `ERROR_NO_ENABLED_PROTOCOL`, `ERROR_NATIVE_UNRESOLVED`, `ERROR_VPN_PERMISSION` and all-unreachable as explicit terminals, or soften the claim.

### R-5 — document the 80 % native fraction (G-13)

SPEC-R5 L2369 says the timeout must apply to both layers but not that they differ. Add the `NATIVE_TIMEOUT_FRACTION = 4/5` and `MIN_NATIVE_CONNECT_TIMEOUT_MS = 1_500` to §6.4 or §10.5.

### R-6 — correct §10.2's `fromId` signature (G-26)

Spec L2673-2674 still shows the pre-`df7b49c` non-nullable `fromId` returning `SOFTETHER_UDP`. HEAD returns `AutoModeProtocol?` and `null`. Doc-only fix.

### R-7 — state the real per-server bound (G-8)

Replace "Maximum time per server = `N_protocols × T`" with the actual model: `N × T` nominal, plus up to `min(5T, 60 s)` per failed attempt while the native slot is held. Otherwise the contract promises a bound the code does not honour.

### R-8 — replace the source-text assertions with behavioural ones

`AutoModeEndToEndTest:222-246` should drive a fake adapter that records the `timeoutMs` it receives and assert on the recorded value. As written it cannot distinguish "plumbed" from "plumbed and ignored".

### Proposed new spec section

If the operator wants OP-R1..OP-R5 to be the normative contract, it deserves its own subsection rather than being distributed across SPEC-R4/R5/R7. Suggested: **§6.8 "Per-Attempt Timeout and Rotation Contract"**, stating the loop nesting, the per-protocol window, the transition table for all four `AttemptOutcome` values, the four terminal conditions, and the real per-server bound. §6.7 currently covers the button contract; §6.8 would cover the attempt loop.

---

## 10. Negative assurance

The following are explicitly asserted about this phase:

1. **No file was modified** other than creating this report. `git status --porcelain` before and after shows the same four pre-existing untracked directories and no tracked modification.
2. **No file was committed, staged, tagged, pushed, reset, cleaned, stashed or checked out.** `HEAD` remains `c5fa436` throughout.
3. **No build was run.** No Gradle invocation, no `assembleProDebug`, no `testProDebugUnitTest`.
4. **No test was run.** Test-result XMLs were not read either — the assertions quoted in §7 come from reading the **test source**, not from a result file.
5. **No device was touched.** No `adb`, no install, no logcat.
6. **`D:\PMC\Documents\Android\SoftEthergai` was not accessed.**
7. **`PROJECT_SPECIFICATION.md` was read only** — it was not edited, and §22 was not touched.
8. **No source file was edited** — including `AutoModeController.kt`, `AutoModeEngine.kt`, `AndroidConnectionAdapter.kt`, `DataUtil.kt`, `SettingsScreen.kt`.
9. **No `*.md` file was moved or deleted.**
10. This report is **untracked** and **NOT committed**, per the phase's authorized-file rule.

**What this audit did NOT cover** (stated so the gaps are not mistaken for clean bills of health): `SoftEtherVpnService`'s start-gate serialisation (U-3), `TunnelStateWatcher`'s recency rules in detail, the native `softether_connect_with_hub` timeout contract, the Auto-Reconnect loop's own stop conditions (§11.13), and the UI's string mapping for every `Error` code.

---

*End of report.*
