# AUTOCONNECT LOGIC AUDIT

**Phase:** read-only audit requested by the operator after the AutoConnect "turns red and stops" report.
**Repo:** `C:\Dev\SoftEthergai` · **HEAD:** `0dc7ed3b7c75243d03fa1b7481457081195e8162` (spec v1.0.26)
**Device:** `5585fecc` — Xiaomi `2201117TG` / `spes_global`, Android 13 (API 33), arm64-v8a
**Installed APK:** `vn.unlimit.vpngatepro` v143 / `2.4.10-dev`, installed 2026-10-17 15:31:18
**Date:** 2026-10-17
**Nature:** **NO source change, NO build, NO test, NO settings change.** Source reading + one device log.

---

## Evidence labels

| label | meaning |
|---|---|
| `SOURCE CODE` | read from the file at the cited line |
| `DEVICE LOG` | observed in `C:\Dev\autoconnect_passive.txt` (16:15:10–16:22:45) or the concurrent capture |
| `INFERENCE` | a conclusion drawn from the two above, not directly observed |
| `UNKNOWN` | not determinable from available evidence |

---

## §1 Executive summary

AutoConnect's **intended** rotation policy is exactly what the operator described: per-attempt timeout →
next protocol → next server → stop. The code implements that policy literally
(`AutoModeController.kt:1361`, `:1409`, `:1443`, `:1611`, `:1640`, `:1643`).

The policy is nevertheless **pre-empted by a second, conflicting policy**: when a start is refused
because the process-wide native slot is still held (`NATIVE_BUSY`), the controller retries twice and
then **terminates the entire run** with `ERROR_NATIVE_UNRESOLVED` instead of rotating. Any native
connect that outlives the per-attempt timeout therefore converts a normal "this server is bad" into
"the whole run dies" — which is the reported red-and-stops symptom.

The reason a native connect outlives the timeout is a **clock-coupling defect**: the same setting value
is used both as the controller's wait budget *and* as the native per-read socket timeout, and the native
layer multiplies it by five.

**Verdict: the operator's mental model is correct and is the documented design; the implementation
violates it in the NATIVE_BUSY branch.** Seven findings are registered in §8.

---

## §2 The state machine

`AutoModeState` (`AutoModeModels.kt:152`) — four states, no others:

| state | payload | UI |
|---|---|---|
| `Disconnected` | — | idle |
| `Connecting` | hostname, ip, protocol, speed, ping, attempt, total | spinner + "server #n of N" |
| `Connected` | hostname, ip, protocol, speed, ping | green |
| `Error(message)` | one of six constants | **red** |

`Error` constants (`AutoModeController.kt:327–432`):

| constant | meaning | terminal? |
|---|---|---|
| `ERROR_NO_SERVER` | every compatible server failed | yes |
| `ERROR_VPN_PERMISSION` | OS VPN permission missing | yes |
| `ERROR_NATIVE_UNRESOLVED` | **our own native slot was still held** | yes |
| `ERROR_RECONNECT_LIMIT` | 5 reconnects in 10 min | yes |
| `ERROR_NO_ENABLED_PROTOCOL` | user disabled every protocol | yes |
| `no servers available` (injected) | server list empty | yes |

Every `Error` is terminal: the run sets `job = null` and returns. There is no automatic retry after an
`Error` — the operator must press the button again (`AutoModeController.kt:1524`, `:1574`, `:1645`).

---

## §3 The run pipeline, step by step

Entry: `runAutoMode()` (`AutoModeController.kt:1224`). One run = one coroutine job (`§13` single-job guard).

| # | step | code | log line |
|---|---|---|---|
| 1 | read protocol priority from Settings | `:1230` | `[AUTO] Protocols (priority) = …` |
| 2 | refuse if the profile is empty | `:1232` | `No protocol enabled by the user` |
| 3 | log the per-attempt budget | `:1249` | `Connection timeout = N seconds` |
| 4 | resolve the candidate list | `:1251` | `Servers available = N` |
| 5 | rank by quality (RTT is only a tie-break) | `:1284` | `Sorted by quality` |
| 6 | build one probe target per usable protocol | `:1300` | `Pre-filtering reachable servers via probe (N endpoint(s)…)` |
| 7 | apply the three-way reachability gate | `:1333` | `Reachable servers selected: N` |
| 8 | iterate servers | `:1361` | — |
| 8a | de-duplicate by `ip|hostname` | `:1365` | — |
| 8b | skip session-excluded servers | `:1371` | `Skipping X: excluded for this session after N wedges` |
| 8c | compute usable protocols | `:1392` | `Skipping X: no supported protocol` |
| 8d | log the attempt | `:1398` | `Trying #n X` |
| 9 | iterate protocols | `:1409` | — |
| 9a | arm the tunnel watcher **before** connecting | `:1428` | — |
| 9b | issue the connect | `:1431` | — |
| 9c | wait for the armed outcome | `:1443` | — |
| 9d | on `TUNNEL_UP` → break to success | `:1455` | — |
| 9e | on `NATIVE_BUSY` → retry loop (§5) | `:1493` | `Native slot busy; retrying…` |
| 9f | on `TIMED_OUT` → log and next protocol | `:1602`, `:1610` | `TIMED_OUT after Nms on X/P` then `Protocol failed: P` |
| 10 | after all protocols, log the server failure | `:1640` | `Connection failed: X` |
| 11 | after all servers, stop | `:1643` | `All compatible servers failed` → `Error(ERROR_NO_SERVER)` |
| 12 | on success, persist + watch the tunnel | `:1621–1636` | `Tunnel established` / `Connected successfully` |

**This table is the operator's model, implemented exactly.** Steps 9f → 10 → 11 are the rotation.

---

## §4 The three budgets

| budget | value | source | bounds |
|---|---|---|---|
| `attemptTimeoutMs` | **user setting × 1000** (5 s in this session; picker 5..60, default 12) | `AutoModeEngine.kt:75`, `DataUtil.kt:333` | one protocol attempt on one server |
| `DEFAULT_ATTEMPT_TIMEOUT_MS` | `25_000` | `AutoModeController.kt:308` | **dead in production** — always overridden by step 1 of §3 |
| `NATIVE_BUSY_RETRY_DELAY_MS` | `2_000` | `:361` | delay between refusal retries |
| `NATIVE_BUSY_MAX_RETRIES` | `2` | `:370` | retry count (3 attempts total) |
| `PER_SERVER_RETRY_DELAY_BUDGET_MS` | `10_000` | `:397` | cumulative retry delay per server |
| `RECONNECT_INITIAL_DELAY_MS` | `2_000` | `:407` | settle before an auto-reconnect |
| `RECONNECT_MAX_ATTEMPTS` | `5` | `:418` | reconnects per window |
| `RECONNECT_WINDOW_MS` | `600_000` | `:425` | the rolling window |
| `WedgeTracker.EXCLUSION_THRESHOLD` | `2` | `:1864` | wedges before a session exclusion |
| `TEARDOWN_TIMEOUT_MS` | `5_000` | `AndroidConnectionAdapter.kt:56` | bound on observing a teardown |

### §4.1 Derived give-up time

With `T` = `attemptTimeoutMs`, the run reaches its terminal give-up after:

```
give-up(T) = T                    first attempt times out
           + 2 × (2 s + T)        two NATIVE_BUSY retries, each re-waiting T
           = 3T + 4 s
```

`T = 5 s` → **19 s**. Measured on device: 16:18:23.915 → 16:18:43.218 = **19.303 s**. `SOURCE CODE` + `DEVICE LOG`.

---

## §5 The NATIVE_BUSY branch (the conflicting policy)

`AutoModeController.kt:1493–1576`. Two nested limits, both terminal:

1. **count** — `NATIVE_BUSY_MAX_RETRIES = 2` (`:1495`), then `:1565` → give up;
2. **budget** — `accumulatedRetryDelayMs + 2000 > 10000` (`:1512`), then `:1515` → give up.

Both funnel into the same three statements (`:1572–1575`):

```kotlin
giveUpWithUnresolvedNativeOperation(server, key)
setState(AutoModeState.Error(ERROR_NATIVE_UNRESOLVED))
job = null
return
```

**`return` exits `runAutoMode()`.** No protocol rotation, no server rotation. This is the red state.

The code is explicit that this is deliberate — `:1468–1471`: *"Fail-closed: the registry still holds the
slot and nothing here may circumvent that. Stopping the run with its own diagnosable state is the
specified behaviour."* `SOURCE CODE`.

The justification rests on one measured number, repeated in three comments (`:356`, `:379`, `:1479`):
*"the previous cleanup typically completes within ~1.1s (F.28AL-Y-FULL logs)"*. That figure is the
**teardown** time of an already-finished operation. It is not the lifetime of an operation that is
still **inside** `nativeConnectWithHub`.

---

## §6 The clock-coupling defect

`AndroidConnectionAdapter.kt:501` and `:519`:

```kotlin
val attemptTimeoutMs = dataUtil.getAutoModeTimeoutSeconds().toLong() * 1000
...
connectTimeoutMs = attemptTimeoutMs.toInt(),
```

`connectTimeoutMs` → `client.setTimeout(...)` → `nativeSetOption(option=1, value=T)`
(`softether_jni.c:551` `conn->timeout_ms = (int)value;`) → applied as `SO_RCVTIMEO`
(`softether_protocol.c:3017`). `SOURCE CODE`.

Then `aes_wrapper.c:553`:

```c
const int max_retries = 5;
```

and the read loop retries while `SSL_ERROR_WANT_READ` (`:579–583`). So one stalled read costs `5 × T`.

**The native verdict latency is therefore up to `5T`, while the controller waits `T`.**

### §6.1 The comment claims the opposite

`AndroidConnectionAdapter.kt:498–500` states the fix is two layers on one clock:

```
//   layer 1 (here)      — native race timeout (connection-level)
//   layer 2 (FIX 2)     — armed select timeout (coroutine-level)
```

`SOURCE CODE`. **This is not what the native layer does.** `T` is not a connection-level deadline; it is
a per-`recv()` deadline that the ladder multiplies by five. The two layers are `T` and `5T`, not `T` and `T`.

### §6.2 Raising `T` does not fix the ratio

| `T` | give-up `3T+4` | native worst case `5T` | outcome |
|---|---|---|---|
| 5 s | 19 s | 25 s | give-up wins (observed, by 100 ms after the server closed at 18.9 s) |
| 12 s (default) | 40 s | 60 s | give-up wins |
| 25 s | 79 s | 125 s | give-up wins |
| 60 s | 184 s | 300 s | give-up wins |

Both sides scale linearly in `T`; the crossover is at `T = 2 s`. For every selectable value the
controller gives up **before** the native layer can produce a verdict. `INFERENCE` from `SOURCE CODE`.

> **Correction to earlier advice.** Raising the Auto Mode timeout to 30 s was previously suggested as a
> no-code workaround. That advice is **withdrawn**: it lengthens the native ladder to 150 s and the
> give-up to 94 s, so the run still dies and each attempt now stalls far longer. It can appear to help
> only on servers that close the socket early (as this one does at ~17.7 s), which is not a property to
> rely on.

---

## §7 Device evidence (the reported failure)

Source: `C:\Dev\autoconnect_passive.txt` — **UTF-16LE with BOM**, 13,080 lines, 16:15:10–16:22:45.
(Note: `grep "[AUTO]"` on the raw file returns 0 because of the encoding; decode as UTF-16 first.)

### §7.1 One attempt, end to end

```
16:18:23.915  [AUTO] Trying #1 vpn131251792
16:18:24.276  [DIAG] nativeConnectWithHub entry (attempt=4)
16:18:24.597  Connected to 112.69.69.79:1446
16:18:25.154  TLS handshake successful (TLSv1.2 / AES128-SHA)
16:18:25.155  VPNCONNECT watermark sent, waiting for server Hello response...
16:18:30.239  SSL read retry 1/5 (ssl_error=2)          [+5.08 s]
16:18:35.358  SSL read retry 2/5                        [+5.12 s]
16:18:40.478  SSL read retry 3/5                        [+5.12 s]
16:18:42.827  SSL read returned 0 … unexpected eof while reading
16:18:42.828  VPNCONNECT watermark failed — server may not be SoftEther
16:18:42.828  Direct login failed (2); retrying via NAT-T + RUDP fallback
16:18:43.189  NAT-T rendezvous failed: error=6
16:18:43.190  Authentication failed: 1 (TCP connection failed)
16:18:43.191  [DIAG] nativeConnectWithHub exit (attempt=4, elapsed=18915ms, result=1)
16:18:43.191  State changed: ERROR
```

Meanwhile the controller (`DEVICE LOG`):

```
16:18:29.164  Native slot busy; retrying same server in 2s (attempt 1/2) (holder=attempt=4 phase=CONNECTING)
16:18:36.184  Native slot busy; retrying same server in 2s (attempt 2/2) (holder=attempt=4 phase=CONNECTING)
16:18:43.218  Native slot still owned by an unresolved operation (attempt=4 phase=CONNECTING);
              stopping without blaming this server
16:18:43.219  Requesting recovery teardown …
16:18:43.531  Native cleanup confirmed (attempt=4, session=1): structure destroyed and slot released
```

**27 ms** separate the native verdict (43.191) from the controller's give-up (43.218). The slot was
released **313 ms too late** to save the run. `DEVICE LOG`.

### §7.2 All three attempts

| attempt | server | native elapsed | outcome |
|---|---|---|---|
| 4 | `vpn131251792` | 18,915 ms | `ERROR_NATIVE_UNRESOLVED`, server recorded as wedge #1 |
| 5 | `vpn131251792` | 19,608 ms | `ERROR_NATIVE_UNRESOLVED`, wedge #2 → excluded for the session |
| 6 | `vpn752839599` | 8,125 ms + 7,439 ms (internal retry) | `ERROR_NATIVE_UNRESOLVED` |

Successful connections in the whole window: **0**. `DEVICE LOG`.

---

## §8 Findings

### D-1 — CRITICAL — clock coupling: `T` is both the wait budget and the native per-read timeout

`AndroidConnectionAdapter.kt:501,519` + `aes_wrapper.c:553`. Native verdict latency up to `5T`, controller
waits `T`. The "two-layer" comment at `:498–500` describes a property the code does not have.
**Effect:** every slow server looks like a stuck native operation.

### D-2 — CRITICAL — `NATIVE_BUSY` exhaustion terminates the run instead of rotating

`AutoModeController.kt:1572–1575`. The documented rotation (next protocol → next server) is bypassed.
**Effect:** the exact reported symptom — red, stopped, operator must press again.

### D-3 — HIGH — `TIMED_OUT` does not cancel the in-flight native connect

`:1611` calls `adapter.disconnect()`, which waits for a teardown *completion signal*
(`AndroidConnectionAdapter.kt:199–210`). The in-flight `nativeConnectWithHub` keeps running to its own
conclusion — measured `elapsed=18915 ms` against a 5,000 ms budget. **Effect:** the slot stays held for
the remainder of the native ladder, which is what feeds D-2.

### D-4 — MEDIUM — a native `ERROR` does not complete the armed wait

`AndroidConnectionAdapter.kt:300–305` documents it: the stack's DISCONNECTED/ERROR fires
`TunnelStateWatcher.onTunnelLost`, which is **not** wired to `armedTunnel`. **Effect:** a genuine fast
failure still costs the full `T`; the controller learns nothing until the timeout.

### D-5 — MEDIUM — the log says "without blaming this server" but the ledger blames it

`:1568` logs *"stopping without blaming this server"*, then `:1572` calls
`giveUpWithUnresolvedNativeOperation`, which unconditionally does `wedgeTracker.record(key)` (`:1716`).
After `EXCLUSION_THRESHOLD = 2` the server is excluded for the session — observed at 16:22:14.968
`Excluding vpn131251792 from this session after 2 wedges`. **Effect:** a server is excluded for our own
slot bookkeeping, and the log text contradicts the action.

### D-6 — LOW — the reachability probe cannot detect an invalid SoftEther server

`AutoConnectReachability.kt:167–181`: the probe is deliberately protocol-neutral — *"no SoftEther or
OpenVPN packet is emulated"*. `Reachable servers selected: 88` of 172 therefore means 88 TCP/UDP-reachable
endpoints, not 88 valid SoftEther servers. **Effect:** dial #1 can be a server that will always fail at
the watermark, which is how this failure starts.

### D-7 — LOW — `AutoModeProtocol.fromId` silently defaults to `SOFTETHER_UDP`

`AutoModeProtocol.kt:22–23`: `?: SOFTETHER_UDP`. An unknown id becomes a real protocol with no
diagnostic — the same class of silent fallback that v1.0.26 fix (D) removed from `manualProtocolLabel`.
Reachable only from persisted settings.

---

## §9 What the operator's model gets right, and where it breaks

| operator's statement | verdict |
|---|---|
| "the timeout is the budget for each protocol on each server" | **correct** — `awaitArmedOutcome(protocol, attemptTimeoutMs)` is inside both loops |
| "if exceeded, go to the next protocol" | **correct as designed** (`:1602`→`:1610`), but **blocked in practice** by D-2 |
| "if no protocol is left, go to the next server" | **correct as designed** (`:1640`), **never reached** when D-2 fires |
| "if no server is left, stop" | **correct** (`:1643`→`:1644` `Error(ERROR_NO_SERVER)`) |

The single break is **D-2**: the fail-closed native-slot policy is evaluated *inside* the protocol loop
and returns from the whole run, so the two outer rotations never execute.

---

## §10 Recommendations (not implemented — no source change in this phase)

Ordered by benefit-to-risk. All are for a future authorised phase.

1. **Make the give-up non-terminal (fixes D-2 and the symptom).** On NATIVE_BUSY exhaustion, issue the
   recovery teardown (already correct), then **await the slot release** (observed: 313 ms) and
   `continue` to the next protocol/server instead of `return`. This preserves the fail-closed admission
   policy — nothing is circumvented, we simply wait — and restores the documented rotation.
2. **Decouple the two clocks (fixes D-1).** Do not feed `attemptTimeoutMs` into `connectTimeoutMs`.
   Either give the native layer its own budget, or make `max_retries` derive from the caller's budget so
   `max_retries × timeout ≤ T`.
3. **Wire native failure into the armed wait (fixes D-4).** Let `onTunnelLost` complete `armedTunnel`
   negatively so a real failure is observed in milliseconds rather than at the timeout.
4. **Align the log with the ledger (fixes D-5).** Either do not record a wedge on a NATIVE_BUSY give-up,
   or change the message.
5. **Separate reachability from protocol validity (fixes D-6)** — a cheap post-watermark failure should
   not consume the same budget as a genuine connect attempt.

---

## §11 Negative assurance

- No source, test, Gradle, manifest or resource file was modified.
- No build, no test, no install, no `pm clear`, no settings change, no airplane-mode toggle.
- The device was read only (`logcat`, `dumpsys`, `run-as … cat/ls`, `uiautomator dump`).
- The operator's log file was read, never written; the UTF-8 working copy lives outside the repo at
  `C:\Dev\_zb_migration\axisA\passive_utf8.log`.
- `PROJECT_SPECIFICATION.md` is untouched; this document is new and standalone.
