# Audit — Axes A / B / C + T5-SLOT-TEARDOWN Classification (v1.0.11)

- **Phase:** DOCS-AND-AXES-AUDIT-v1.0.11 (read-only axis audits, consolidated with the spec update)
- **Repository / branch:** `D:\PMC\Documents\Android\SoftEthergai` / `main`
- **Reads performed at HEAD:** `6119a2a` (device-verify hardening bundle) — noted explicitly because
  the four hardening commits (`fd4fcf9`, `c8acb5d`, `1a4818a`, `6119a2a`) are *included* in the tree
  that was read. Every file/line citation below was read from that tree.
- **Method:** READ-ONLY source inspection plus prior-phase report/device-log reconciliation.
  **No file was read-modify-written. No build, test, adb, or device interaction was performed in this
  phase.** The only writes are this report and the companion
  `DOCS_UPDATE_v1.0.11_REPORT.md`.
- **Evidence taxonomy used:** `SOURCE CODE EVIDENCE` (direct file/line inspection),
  `PHYSICAL DEVICE EVIDENCE` (observed on device `5585fecc`, attributed to the prior
  device-verify phase whose report is the source), `PRIOR REPORT` (attributed to an earlier phase
  report rather than re-verified here), `INFERENCE` (reasoning, explicitly labelled), `UNKNOWN`
  (not determinable from available evidence).

> **Standing constraint honoured throughout:** the exact path-selection algorithm, ordering,
> concurrency model, timeouts, retry policy, and Android network-binding *implementation* remain
> design/implementation work unless already explicitly decided elsewhere. Where a decision already
> exists (notably `SERVER_SOURCE_ARCHITECTURE.md` §7), it is cited as a
> **USER-DIRECTED REQUIREMENT** and is *not* re-invented here. Where it does not exist, this report
> records the gap and a recommendation — never a final value.

---

## 1. Scope

Three axes plus one residual classification, as defined by the phase brief:

| Item | Axis | Question |
|---|---|---|
| Part B | — | Residual `T5-SLOT-TEARDOWN` finding: what is it, and is it closed? |
| Part C | **Axis A** | Tunnel State / UI Sync — which state source does the UI actually follow, and where can they disagree? |
| Part D | **Axis B** | Server List Network Binding — is the server-list fetch bound to a Network, and what happens while the app's own VPN is up? |
| Part E | **Axis C** | SoftEther UDP (`seUdpPort` / TUN `EIO`) — how are UDP-only servers and TUN I/O errors actually handled? |

**Out of scope (explicitly):** any change to `§1.6.2` / `§1.6.3` / `§1.6.4` of
`PROJECT_SPECIFICATION.md`, and any change to `§22 L1–L10`. Both were left untouched, as required.

---

## 2. Part B — Residual `T5-SLOT-TEARDOWN` classification

### 2.1 The finding as recorded

`PROJECT_SPECIFICATION.md` §12.5.1 row 9 and §1.6.4 record a residual observed in the v1.0.10 device
run: a holder logged `attempt=2 phase=TEARDOWN, reporting deadline expired (still blocked)` and
blocked later attempts (`Start refused: native slot still held`) for roughly one minute
(17:36:20 → 17:37:31). FIX-2 fired 3× but no `FORCED_RELEASE_AFTER_TIMEOUT` fired. Status was
**OPEN — UNKNOWN**.

### 2.2 The three code sites that produce the residual line

The residual log line is emitted with `TeardownOutcome.NOOP_REENTRANT` in its
`describe()` payload. Tracing that value to its source gives three sites and a precise mechanism.

| # | Site | Role |
|---|---|---|
| 1 | `app/src/main/java/vn/unlimit/vpngate/automode/AndroidConnectionAdapter.kt:226` | **Publisher of the residual line.** The `else` branch (i.e. `!nativeCleanupConfirmed`) after `withTimeoutOrNull(TEARDOWN_TIMEOUT_MS) { teardown.await() }` emits `"[AUTO] Teardown finished but NATIVE CLEANUP UNCONFIRMED (${result.describe()}); ownership retained, a new native attempt will be refused"`. Evidence: SOURCE CODE. |
| 2 | `SoftEtherClient/src/main/java/vn/unlimit/softether/SoftEtherVpnService.kt:1081–1106` | **Origin of the `NOOP_REENTRANT` outcome.** The re-entrant teardown path: when `!sessionPolicy.beginTeardown(resourcesLive = …)` the service logs `Already torn down, skipping re-entrant call`, broadcasts `STATE_DISCONNECTED`, and calls `notifyTeardownComplete(TeardownCompletion.of(attemptId = teardownAttemptId, sessionId = tearingDown, outcome = TeardownCompletion.Outcome.NOOP_REENTRANT))`, then returns. Evidence: SOURCE CODE. |
| 3 | `SoftEtherClient/src/main/java/vn/unlimit/softether/controller/ConnectionController.kt:937` | **Emitter of the genuine confirmation.** `Log.d(TAG, "Native cleanup confirmed ($teardownIdentity): structure destroyed and slot released")` for `TeardownOutcome.CONFIRMED` (`ALREADY_CONFIRMED` logs a separate line; `UNRESOLVED` routes to `NativeLifetimeRegistry.process.markDeadlineExpired(nativeLease)`). Evidence: SOURCE CODE. |

### 2.3 The mechanism (established)

`TunnelStateWatcher`'s teardown waiter matches on the **exact teardown attempt id**. Therefore a
`NOOP_REENTRANT` completion carrying the **same** attempt id satisfies the adapter's wait *before*
the real `CONFIRMED` for that attempt arrives from `ConnectionController`. The two completions are
not mutually exclusive — they are two different answers to the same attempt id, and the waiter
accepts whichever arrives first.

That is the whole mechanism: the residual line is an **ordering artifact between two legitimate
completions of one attempt**, not evidence that the native structure is still alive. Evidence:
SOURCE CODE (the three sites above).

### 2.4 The device evidence

In the hardening-bundle device verification run (device `5585fecc`, Xiaomi 2201117TG,
Android 13 / API 33), the residual fired **7 times**, each time in the form
`[AUTO] Teardown finished but NATIVE CLEANUP UNCONFIRMED (teardown#N, NOOP_REENTRANT); ownership
retained, a new native attempt will be refused`. In **every one of the 7 cases** the
`ConnectionController` confirmed the native cleanup within ~1 s (`structure destroyed and slot
released`), the retained ownership then cleared, and the next AutoConnect attempt proceeded
normally — including through the explicit `Native slot busy; retrying same server in 2s` path.
Zero crashes, zero tombstones, no fd or thread leak. Evidence: **PHYSICAL DEVICE** (attributed to
`DEVICE_VERIFY_HARDENING_AUTO_REPORT.md` §15; **PRIOR REPORT** relative to this phase).

### 2.5 Classification

Two layers, because the evidence supports exactly two layers:

- **Symptom class — B (minor, transient, self-healing).** For all 7 device occurrences the
  condition resolved within ~1 s with no user-visible failure, no leak, and no crash. The
  *mechanics* of the residual are fully understood (§2.3). This is a bookkeeping lag between the
  AutoMode-side adapter and the `ConnectionController`, i.e. a UX/observability defect rather than
  a correctness or lifetime defect. Evidence: PHYSICAL DEVICE + SOURCE CODE.
- **Cause class — UNKNOWN.** What is *not* established is **why a second, re-entrant teardown call
  for an already-tearing-down attempt id is issued at all.** No instrumentation in the tree
  captures the identity of the caller that produces the re-entrant call. The two candidates below
  are both consistent with the observed evidence and neither is confirmed; recording either as fact
  would be invention:
  1. two independent callers (a UI disconnect and an AutoMode stop/reconnect) racing on the same
     attempt id — INFERENCE only;
  2. a retry/recovery path (the FIX-2 `requestRecoveryTeardown` path from `4df7e79`) re-delivering
     a teardown for an attempt already in TEARDOWN — INFERENCE only.

  This is the same reason the finding stays OPEN at the cause level.

- **Scoped severity note (important, and the reason the finding is *not* closed).** The hardening
  run showed a ~1 s window 7/7 times. The v1.0.10 run showed the *same log line* associated with a
  ~1-minute blocked window (`Start refused: native slot still held`, FIX-2 ×3, no
  `FORCED_RELEASE_AFTER_TIMEOUT`). The hardening evidence therefore **narrows** row 9 — it proves
  the symptom is normally benign — but it does **not** explain the variance between a ~1 s and a
  ~1 minute window for an identical log line. Until the cause is captured, §12.5.1 row 9 remains
  **OPEN**, with the scope narrowed from "unknown wedge" to "unknown upstream trigger, benign
  symptom in 7/7 observed hardening-run occurrences".

- **One concrete, non-cosmetic sub-defect is established by SOURCE CODE.** The terminal message
  `… ownership retained, a new native attempt will be refused` is emitted for **both** a genuinely
  unresolved native operation (`UNRESOLVED`) and a `NOOP_REENTRANT` that will confirm ~1 s later.
  A log line that promises a refused attempt is being fired on a condition that never refuses
  anything. This is a message-accuracy defect, independent of the still-unknown cause.

**Verdict: classified as B (minor, transient, self-healing) at the symptom level; OPEN — UNKNOWN
at the cause level; §12.5.1 row 9 remains open with narrowed scope.** No source change is required
for the symptom itself; the recommendation in §8 (R1/R2) is logging-only instrumentation plus a
message-accuracy split, mirroring the logging-only precedent of commit `5e51e37`.

---

## 3. Axis A — Tunnel State / UI Sync

### 3.1 The structural problem: two state sources, no designated owner

The app maintains **two** concurrent models of "is the VPN up", and the UI reads **both at the same
time** in at least one screen.

| Source | Declaration | What it actually is | Evidence |
|---|---|---|---|
| `GlobalVpnTracker` (`app/src/main/java/vn/unlimit/vpngate/state/GlobalVpnTracker.kt`) | Declared the "single source of truth" | Implements `SoftEtherVpnService.StateListener` + `VpnStatus.StateListener`, holds `MutableStateFlow<GlobalVpnState>`, and additionally watches the *system* VPN transport via `registerSystemVpnWatcher` (`ConnectivityManager.NetworkCallback` on `TRANSPORT_VPN`) | SOURCE CODE |
| `AutoModeController._state` (`AutoModeController.kt:442`) / `AutoModeEngine.stateFlow` | Auto Mode's own machine | `MutableStateFlow<AutoModeState>(AutoModeState.Disconnected)`, `setState` at `:456`; this is what the Auto Mode screens actually render | SOURCE CODE |

`HomeScreen.kt:125–126` collects **both** in the same composable:
`val autoState by AutoModeEngine.stateFlow.collectAsState()` **and**
`val globalVpnState by GlobalVpnTracker.vpnState.collectAsState()`. A third observation path exists
for the Auto Mode screen (`AutoModeViewModel.kt:27` bridges `AutoModeEngine.state()` into a LiveData
that `AutoModeScreen.kt:139–140` observes), and `StatusViewModel.kt:200–202` collects
`GlobalVpnTracker.vpnState`. `MainActivity` / `DetailActivity` were checked and hold **no** direct
binding to either flow, which confines the inconsistency to `HomeScreen` and the status surfaces.
Evidence: SOURCE CODE.

The consequence is not theoretical: the two machines can be made to disagree for a bounded window by
any of the concrete mismatch sites below, and during that window the UI renders a state that is
neither source's truth.

### 3.2 Mismatch sites (each independently verified)

| ID | Site | Defect | Window | Evidence |
|---|---|---|---|---|
| A-2 | `GlobalVpnTracker.onLost` (via `registerSystemVpnWatcher`) | `onLost` posts a **500 ms delayed re-check** (`postDelayed`) and only then, if no VPN transport remains while the tracker is `CONNECTED`, invokes `TunnelStateWatcher.onTunnelLost`, `updateState(DISCONNECTED)` and `ACTION_DISCONNECT` | ~500 ms of "system says no VPN transport, UI says CONNECTED" | SOURCE CODE |
| A-3 | `GlobalVpnTracker.onSoftEtherStateChanged` / `updateState` protocol egating | `STATE_DISCONNECTED` / `ERROR` are gated on the *currently recorded protocol*, so a **SoftEther disconnect cannot clear an OpenVPN-labelled state** and vice versa; the wrong-protocol disconnect is dropped | Unbounded — it does not self-clear until a same-protocol event occurs | SOURCE CODE |
| A-4 | `AutoModeController.startConnectedWatcher(protocol)` (~:585) | Three independent loss paths with different latencies: path A `TunnelStateWatcher.onTunnelLost` (guarded by `_state.value is Connected`), path B a **1-second polling** loop on `!GlobalVpnTracker.isConnected` (logs `[AUTO] GlobalVpnTracker confirmed disconnected`), path C `TunnelLivenessWatchdog` silence | Up to ~1 s on path B; path C is silence-bounded | SOURCE CODE |
| A-5 | `AutoModeController.onUnexpectedDisconnect` (`:668`) | `setState(AutoModeState.Disconnected)` **first**, then OQ-7 manual precedence / OQ-6 reconnect / OQ-1 loop-guard logic runs — so Auto Mode state *leads* `GlobalVpnTracker` and the two are observably inconsistent for the duration of that follow-up work | Duration of the follow-up work | SOURCE CODE |
| A-6 | Reachability of the disagreement | Two flows consumed in one composable (`HomeScreen.kt:125–126`) with no reconciliation point; `MainActivity`/`DetailActivity` have no direct binding (so the blast radius is limited to `HomeScreen`/status) | — | SOURCE CODE |

### 3.3 Why this matters beyond cosmetics

The interaction is with the native slot. During the A-2/A-3 window the UI shows a connected tunnel
that the OS no longer has. A user who disables the tunnel and immediately taps connect is issuing a
connect while `AutoModeController` may still hold `isTunnelBusy() == true` and the native
`NativeLifetimeRegistry` slot may still be owned by the in-flight teardown — which is precisely the
precondition family that produces `T5-SLOT` (§2) and `Start refused: native slot still held`.
Evidence: INFERENCE (the causal chain is assembled from SOURCE CODE at each link; the chain itself
was not measured as a single event in this phase).

### 3.4 Axis A verdict

**OPEN — architectural inconsistency, medium severity, no source change authorised by this phase.**
The defect is a *missing designation* (no single state owner for UI rendering) rather than a bug at
any one line. Fixing it is a design decision (which machine owns the UI truth, and what the other
one is reduced to), and per the phase constraint no final value is invented here.

---

## 4. Axis B — Server List Network Binding

### 4.1 Current implementation state (confirmed)

The server-list HTTP path is **not bound to any Network**. Evidence: SOURCE CODE.

- `app/src/main/java/vn/unlimit/vpngate/data/remote/HttpFetcher.kt` — `OkHttpFetcher` builds a plain
  `OkHttpClient.Builder().dns(ResilientDns()).connectTimeout(10 s).readTimeout(15 s)
  .followRedirects(false).followSslRedirects(false).build()`. There is **no**
  `ConnectivityManager.bindProcessToNetwork`, **no** `Network`, **no** per-socket
  `bindSocketToNetwork`, and **no** `VpnService.protect()` on the retrieval path.
- `app/src/main/java/vn/unlimit/vpngate/repository/VpnServerRepository.kt:40` — a **single**
  `private val fetcher: HttpFetcher = OkHttpFetcher()` instance is shared by all three sources
  (`VpnGateHtmlSource`, `VpnGateApiSource`, `VpnGateMirrorSource`) via `suspend fun refresh()`.
- `ResilientDns` (`HttpFetcher.kt:53`) is DNS-only: it filters site-local/loopback/any-local
  addresses and falls back to 8 hard-coded VPNGate IPs for `vpngate.net` / `www.vpngate.net`.
  It does not select a Network.
- `GlobalVpnTracker.registerSystemVpnWatcher` **already obtains** the system VPN `Network` in its
  `onAvailable(network: Network)` callback, but that object is used for state tracking only and is
  **not surfaced** to the fetcher. So the object needed for binding exists and is discarded.
- The **opposite** direction is implemented correctly and should not be confused with this gap:
  the tunnel sockets *are* protected — `ConnectionController.kt:659, :671, :682, :1333, :1343, :1353,
  :1648, :1657` all call `service.protect(...)` on the TCP, R-UDP and NAT-T fds. That keeps tunnel
  traffic **out of** the TUN. It says nothing about which Network the *server-list fetch* uses.

`PROJECT_SPECIFICATION.md` §11.8.2 already records this observation verbatim and correctly, and
§12.5.2 row A carries the roadmap item. **The specification is accurate; the gap is a real product
gap, not a documentation error.**

### 4.2 The three-state gap (enumerated)

A server-list update can be issued in exactly three network contexts. The behaviour differs in
each, and nothing in the code distinguishes them today.

| State | What the OS default route is | What happens today | Classification |
|---|---|---|---|
| **S1 — no VPN active** | Wi-Fi / cellular (the only proven-working state) | The fetch uses the default network and works. This is the state all existing behaviour has been validated in. | Working path — the only one |
| **S2 — this app's own VPN is active** | When the app is itself the `VpnService` provider, the default route for its sockets becomes its own TUN | The fetch enters the TUN with **no** binding and **no** usability check. Two sub-outcomes, indistinguishable today: (a) the tunnel forwards it, so the fetch leaves via the VPN gateway — a routing side-effect that was never chosen; (b) the tunnel is half-open/broken, so the fetch hangs until the 10 s connect / 15 s read timeout and then fails. With no per-path timeout ladder and no usability signal, the caller cannot tell (a) from (b), and neither is logged. | Gap — unbounded decision quality |
| **S3 — transition (connecting / disconnecting)** | Changing while the decision is made | TOCTOU: the decision is made against a state that is actively changing. `GlobalVpnTracker` publishes CONNECTED from `onAvailable`-driven updates and clears it via the 500 ms delayed re-check (A-2). There is no epoch/version guard, so a fetch can be issued against — or skip binding based on — a stale reading. | Gap — race window |

### 4.3 What is already decided (do not re-decide)

`SERVER_SOURCE_ARCHITECTURE.md` §7 records **eight resolved open questions**, each a
**USER-DIRECTED REQUIREMENT**. The two that govern this axis:

- **Q2 (bind mechanism):** use **per-request `Network.getSocketFactory()` on a dedicated
  `OkHttpClient` for the VPN path**. **Do NOT** use `bindProcessToNetwork()` — explicitly rejected
  as too broad and process-wide (it would route unrelated app traffic through the VPN as a side
  effect). Minimum SDK API 21; target device API 33.
- **Q3 (default routing order):** **open network first → VPN as fallback → emergency → manual.**
  A user preference may invert this to "always prefer VPN", but that is an **opt-in setting, not the
  default**.
- **Q1 (usability signal):** `isTunnelUsable()` requires **all three** of (a) VPN service state ==
  `CONNECTED`, (b) an **RX counter advanced within the last 10 s**, (c) **no TUN `EIO` in the last
  30 s** — requiring a **new RX/EIO counter in `GlobalVpnTracker`**.

**§4.4 The blocker: the Q1 data source does not exist.** Verified read-only against the tree:
`GlobalVpnTracker.kt` contains **no RX counter and no EIO counter** — a search for `rx` / `Rx` /
`RX` / `counter` / `bytes` in that file returns **zero matches**. The native side does maintain
overflow counters (Phase 13F: `SO_RCVBUF`/`SO_SNDBUF` 2 MB plus overflow counters, per
`RUDP_IMPLEMENTATION_PLAN.md` §13F), but they are not surfaced through the JNI bridge to Kotlin.
Therefore `isTunnelUsable()` is **not implementable today** without new instrumentation, and Axis
B's fallback decision cannot be made correctly. This is a hard cross-axis dependency on Axis C
(§5.4). Evidence: SOURCE CODE.

### 4.5 Axis B verdict

**OPEN — product gap with an already-decided design, blocked on a missing signal.** The design
decisions exist (Q1–Q3); the retrieval path has no binding at all; and the one input the design
depends on (the RX/EIO counter) does not exist in `GlobalVpnTracker`. Recommended sequence is in
§8 (R4): instrument the counters first, then implement the per-request `SocketFactory` bind — never
`bindProcessToNetwork()`.

---

## 5. Axis C — SoftEther UDP / TUN `EIO`

### 5.1 `seUdpPort` is independent, and `udp_only` selects a structurally unwinning path

**Data model (SOURCE CODE).** `softether_protocol.h` defines the two advertised-port fields as
independent: `udp_port` is the "advertised SoftEther UDP port (seUdpPort), 0 if none; when >0 a
direct R-UDP attempt precedes the NAT-T relay", and `udp_only` is "1 if the server advertises no
TCP port (seTcpPort <= 0)". They are separate fields; neither derives from the other.

**Code path (SOURCE CODE).** In `softether_protocol.c`, `softether_connect_parallel_race` (~:2719)
branches on `conn->udp_only`: when set it logs
`UDP-only server (seTcpPort=0) - parallel race: R-UDP direct + NAT-T` and runs the 5-transport
parallel race (R-UDP direct delay 0 ms, NAT-T relay 30 ms, DNS tunnel 100 ms, ICMP 200 ms requiring
root). When `seTcpPort > 0` the sequential TCP-first flow runs and the race is only a fallback.

**The contradiction (SOURCE CODE EVIDENCE from the project's own authoritative plan).**
`SoftEtherClient/RUDP_IMPLEMENTATION_PLAN.md` states — at lines 7, 26 and 45–51 — that for
`SETcpPort = 0` servers the R-UDP/NAT-T transport is **broken**: across 3 sweeps of different CSV
snapshots covering 30+ unique servers, this client achieved **0%** success and the official SoftEther
client v4.44 also achieved **0%**, while OpenVPN **worked** on the same port. The NAT-T relay
returns `error=6` (`NOT_FOUND`) for UDP-only servers, and the "UDP: Supported" banner on
vpngate.net refers to R-UDP-via-NAT-T, which fails for exactly these servers. The plan's own
conclusion (line 51): **"The only viable path for UDP-only servers is OpenVPN fallback using
`OpenVPN_ConfigData_Base64` (column 14)"**, and that fallback is still listed as **Open Item #1 —
not started**.

So for every UDP-only server the client runs a race whose expected outcome is failure. Measured
cost from the hardening device run: `Parallel race: 3 transports, race timeout 5000 ms (deadline
model)` → `Parallel race: all transports failed` at exactly 5 s. Evidence: PHYSICAL DEVICE.

**Important scoping (do not over-claim).** This is **not** a native-code regression. The RUDP plan's
parity audit table (2026-09-08) explicitly lists "Connect flow: UDP-only → parallel race;
TCP-available → sequential TCP + TLS → race fallback" as **verified identical** to the official
client (`Cedar/Protocol.c:7577` PortUDP split; `Network.c:16287`), mapping to
`softether_protocol.c:2396-2479`, and closes with "All 6 parity gaps implemented; no remaining
recommended fixes." The native transport behaves as designed and as the official client does. The
finding is a **product-level** one: the design guarantees a failed ~5 s attempt for UDP-only
servers, and the only fix is the OpenVPN fallback that remains unstarted.

**Product interaction (INFERENCE — flagged, not decided).** The spec's §6.7 contract R5 sets a
per-server/per-protocol timeout of **5 s** in Auto Mode, and the race deadline is **5000 ms**. The
race therefore consumes the *entire* per-server budget, which implies an OpenVPN fallback would have
to run as a **separate attempt** rather than as a post-race fallback under the current R5 budget.
Whether that is acceptable, or whether R5 needs to be revisited for UDP-only servers, is a product
decision that has not been taken. No final value is proposed here.

### 5.2 TUN `EIO` is not explicitly handled

`TunTerminal.kt` wraps the TUN `ParcelFileDescriptor` in a `FileInputStream` / `FileOutputStream`
(`:27–28`), `MAX_PACKET_SIZE = 32767` (`:23`), `BUFFER_SIZE = 8192` (`:24`), and `start()`
(`:40–54`) launches `readLoop()` on `Dispatchers.IO`.

`readLoop()` (`:111–148`) classifies read errors as follows:
- an `IOException` whose message contains `EBADF` or `Bad file descriptor` → benign, logs
  `TUN interface closed (EBADF), stopping read loop` (`:135–136`);
- **any other** exception → `onError?.invoke(e)` (`:139`), which routes into the
  `ConnectionController` reconnect path (`triggerReconnect()`).

**Gap C-2:** `EIO` has **no branch**. A search for `EIO|EBADF` across all `*.kt` in the tree
returns **only** `TunTerminal.kt:133–136` — there is no `EIO` handling anywhere in the Kotlin tree,
and `softether_protocol.c:3556` carries only the historic `EINVAL` note. On several Android
versions a TUN read issued after the interface is revoked surfaces as an `IOException` whose text is
`EIO`-based rather than `EBADF`, so the revoked case falls into the generic branch and is treated
as an I/O error requiring a reconnect — where a clean teardown would be the correct response. The
exact exception text on the target device was **not captured** (no `EIO` appears in the device log
for the verification run), so the *likelihood* is INFERENCE while the *absence of a branch* is
SOURCE CODE.

### 5.3 `readLoop()` terminates on any read error; no self-restart

On **any** exception the loop `break`s (`:142`) and `isRunning.set(false)` (`:146`), logging
`TUN read loop ended`. The loop has no recovery of its own and no retry/backoff inside it.

Note the accurate boundary of this finding: because `readLoop()` resets `isRunning` to false, a
**subsequent external `start()` call would re-arm the loop** (`start()` only early-returns when
`getAndSet(true)` returns `true`, i.e. when the loop is already running). So the defect is the
**absence of any self-restart inside `TunTerminal`**, not a permanent one-way latch. Whether the
owner ever re-invokes `start()` after a read error was **not verified in this phase** and is
therefore **UNKNOWN** here — recording either answer without reading the owner's call sites would
be invention. Combined with §5.2, one unclassified read error silences the TUN read side for that
connection until and unless the caller re-arms it. Evidence: SOURCE CODE (loop behaviour and
`start()` guard) + UNKNOWN (owner re-arm behaviour).

A separate, smaller asymmetry is worth recording: the **write** path got an explicit, documented
errno mitigation (the Phase 13D fix at `softether_protocol.c:3553–3556` strips Ethernet headers,
auto-replies ARP and skips non-IP frames because writing raw Ethernet frames to the TUN fd
"failed with EINVAL … which killed all connectivity"), whereas the **read** path has only the
single `EBADF` special case. The precedent is that a TUN error class was resolved by an explicit
pre-check rather than by errno classification; the read side has not received equivalent treatment.
Evidence: SOURCE CODE.

### 5.4 No counters exist for the signal Axis B needs

Cross-reference to §4.4: the RX counter and the TUN-`EIO` counter required by
`SERVER_SOURCE_ARCHITECTURE.md` §7 Q1 do not exist in `GlobalVpnTracker.kt` (zero matches for
`rx`/`RX`/`counter`/`bytes`), and no `EIO` counter exists anywhere in the Kotlin tree (§5.2). The
native Phase-13F overflow counters are not surfaced through JNI. **Axis C's instrumentation gap is
the blocker for Axis B's usability signal.**

### 5.5 Dead constant / buffer inconsistency (code hygiene)

`MAX_PACKET_SIZE = 32767` is declared at `TunTerminal.kt:23` with the comment "Max VPN packet
size" and is **never referenced again anywhere in the tree** (a whole-tree search for
`MAX_PACKET_SIZE` returns only this declaration; the similarly-named `RUDP_T_MAX_PACKET_SIZE` and
`MAX_BLOCK_SIZE` symbols in the native core are unrelated and are used). Meanwhile the actual read
allocation is `ByteArray(BUFFER_SIZE)` = **8192** bytes (`:24`, `:112`).

`FileInputStream.read(buffer)` on a TUN fd returns at most one packet per call, and a packet larger
than the buffer is **truncated with the remainder discarded** — returned as an ordinary successful
`read()`, so the truncation is undetectable at the call site. In practice this is not a live
functional defect: the TUN MTU is 1500 (`SoftEtherVpnService.kt:1322` `.setMtu(config.mtu)` with
`ConnectionConfig.kt:30` `val mtu: Int = 1500`, also mirrored at `AndroidConnectionAdapter.kt:495`,
`StatusViewModel.kt:945`, `DetailActivity.kt:1316`), so an 8 KB buffer is comfortably sufficient.
But the declared maximum (32767) and the allocated buffer (8192) disagree by 4×, and no code
documents why. Evidence: SOURCE CODE (declaration/dead-symbol/buffer/MTU) + INFERENCE (truncation
semantics).

### 5.6 Axis C verdict

**OPEN — one product-level design consequence (5.1, known and documented), two implementation gaps
(5.2, 5.3), one hygiene item (5.5), and one cross-axis blocker (5.4).** None of these is a native
transport correctness defect; the native code matches the official client by the project's own
parity audit.

---

## 6. Cross-axis interaction matrix

These are the couplings that make the three axes one problem rather than three. Each row states the
coupling, its evidence, and whether it is *measured* or *inferred*.

| # | Coupling | Mechanism | Evidence class |
|---|---|---|---|
| X-1 | **Axis A → T5-SLOT residual** | The A-2 500 ms `postDelayed` window plus the A-4 up-to-1 s poll means the UI can show CONNECTED while the OS VPN transport is gone. A user who disables + immediately re-enables issues a connect while `isTunnelBusy()` may still be true and the native slot may still be owned by the in-flight teardown — the precondition family of §2's `Start refused: native slot still held`. | INFERENCE (each link is SOURCE CODE; the composite chain was not measured as one event) |
| X-2 | **Axis C → Axis B (hard blocker)** | `SERVER_SOURCE_ARCHITECTURE.md` §7 Q1 requires an RX counter advanced within 10 s and no TUN `EIO` in 30 s. `GlobalVpnTracker` has neither (§4.4, §5.4). Until C's counters exist, B's VPN-fallback decision cannot be made and must default to "skip the VPN path". | SOURCE CODE (absence verified by search) |
| X-3 | **Axis A → Axis B** | B's routing decision reads `GlobalVpnTracker.isConnected` — the same dual-source state Axis A flags. The A-3 protocol-egating bug means a stale or wrong-protocol CONNECTED is possible, so B could attempt a VPN path that is not in fact usable (or skip an open path it could have used). | SOURCE CODE (dependency) + INFERENCE (mis-decision) |
| X-4 | **Axis C → Axis A** | A TUN `EIO` that falls into the generic branch (§5.2) funnels into `triggerReconnect()` and therefore produces exactly the transient disconnect that Axis A must render — so C's unclassified error class is one of the *generators* of A's disagreement window. | SOURCE CODE (paths) + INFERENCE (as a generator) |
| X-5 | **Axis C → Auto Mode cost** | Every UDP-only server consumes the full 5 s race deadline (§5.1) before failing, and R5's per-server budget is also 5 s, so UDP-only servers dominate Auto Mode's time-on-server. The OpenVPN fallback (RUDP plan Open Item #1) would change this but is unstarted. | PHYSICAL DEVICE (5 s measured) + SOURCE CODE (R5 budget) + INFERENCE (interaction) |

---

## 7. Findings register

All findings below are **OPEN**. None was closed by this phase; the phase is read-only by design.
Priority labels follow the document's existing convention and use "Priority to be assessed" where
no priority has been established — no new priority commitments are invented here.

| ID | Finding | Axis | Severity / Priority | Status | Evidence |
|---|---|---|---|---|---|
| R-T5-1 | `T5-SLOT-TEARDOWN` residual: symptom is B-class (transient ~1 s, self-healing, 7/7 occurrences), **cause is UNKNOWN** — the upstream trigger that issues a re-entrant teardown for an already-tearing-down attempt id was never captured. Does **not** explain the ~1 s vs ~1 min variance between runs. | — | Low (symptom) / P2_MEDIUM (cause) | **OPEN — scope narrowed** | PHYSICAL DEVICE + SOURCE CODE |
| R-T5-2 | Message-accuracy defect: `AndroidConnectionAdapter.kt:226` emits the terminal `ownership retained … will be refused` line for **both** `UNRESOLVED` and `NOOP_REENTRANT`, i.e. it promises a refusal on a condition that never refuses. | — | Low | **OPEN** | SOURCE CODE |
| A-1 | Two concurrent state models (`GlobalVpnTracker` vs `AutoModeController._state`/`AutoModeEngine.stateFlow`) with **no designated owner for UI truth**, and at least one screen consuming both (`HomeScreen.kt:125–126`). | A | Medium | **OPEN** | SOURCE CODE |
| A-2 | `GlobalVpnTracker.onLost` posts a 500 ms delayed re-check before declaring the tunnel lost — a window in which the system reports no VPN transport while the UI shows CONNECTED. | A | Low–Medium | **OPEN** | SOURCE CODE |
| A-3 | `GlobalVpnTracker.updateState` gates `STATE_DISCONNECTED`/`ERROR` on the recorded protocol, so a disconnect of one protocol cannot clear another protocol's state (drops the event rather than reconciling). | A | Medium | **OPEN** | SOURCE CODE |
| A-4 | `startConnectedWatcher` has three loss paths with different latencies (A-2 driven / 1 s poll / watchdog silence) — up to ~1 s before `onUnexpectedDisconnect`. | A | Low | **OPEN** | SOURCE CODE |
| A-5 | `onUnexpectedDisconnect` sets `Disconnected` first and then runs OQ-7/OQ-6/OQ-1 follow-up, so Auto Mode state leads `GlobalVpnTracker`. | A | Low | **OPEN** | SOURCE CODE |
| B-1 | Server-list HTTP path has **no** Network binding (`HttpFetcher.kt` plain `OkHttpClient`; single instance at `VpnServerRepository.kt:40`); already documented at §11.8.2 and roadmap §12.5.2 row A. | B | Medium | **OPEN** (product gap) | SOURCE CODE |
| B-2 | The `Network` needed for binding is already obtained by `GlobalVpnTracker.registerSystemVpnWatcher.onAvailable` but is discarded instead of being surfaced to the fetcher. | B | Medium | **OPEN** | SOURCE CODE |
| B-3 | Three-state gap: S1 no-VPN is the only validated path; S2 own-VPN-active routes the fetch into the TUN with no usability check; S3 transition is a TOCTOU window with no epoch guard. | B | Medium | **OPEN** | SOURCE CODE + INFERENCE |
| B-4 | `isTunnelUsable()` (§7 Q1) is **not implementable**: `GlobalVpnTracker` has no RX counter and no EIO counter. | B | Medium — **blocks B-1's correct decision** | **OPEN** | SOURCE CODE (absence verified) |
| B-5 | No per-path timeout/retry ladder (open vs VPN vs emergency); one 10 s/15 s pair applies to whichever fetch runs. | B | Low–Medium | **OPEN** | SOURCE CODE + INFERENCE |
| C-1 | `udp_only` servers run a parallel race with a structurally ~0% success rate per the project's own `RUDP_IMPLEMENTATION_PLAN.md`; the only viable path (OpenVPN fallback) is Open Item #1, unstarted. Measured cost: the full 5000 ms deadline. **Not** a native-parity regression. | C | Medium (product) | **OPEN — documented, not new** | SOURCE CODE + PHYSICAL DEVICE |
| C-2 | TUN `EIO` has **no branch** in `TunTerminal.readLoop()`; only `EBADF` is special-cased, so a revoked-interface read falls into the generic `onError` → `triggerReconnect()` path. | C | Medium | **OPEN** | SOURCE CODE + INFERENCE |
| C-3 | `readLoop()` terminates on any read error with no self-restart; whether the owner re-arms it is **UNKNOWN**. Write-path errno has a documented mitigation; read-path does not. | C | Low–Medium | **OPEN** | SOURCE CODE + UNKNOWN |
| C-4 | `MAX_PACKET_SIZE = 32767` is a **dead constant** (zero references tree-wide) while the actual read buffer is `BUFFER_SIZE = 8192`; the 4× disagreement is undocumented. Not a live functional defect (TUN MTU is 1500). | C | Low (hygiene) | **OPEN** | SOURCE CODE + INFERENCE |

---

## 8. Recommendations

Ordered by dependency, not by severity. **None of these is a final value, an algorithm, or a
commitment** — each is a *decision or instrumentation step* to be taken in a future phase, consistent
with the phase constraint that implementation work is not to be invented here. Where a design
decision already exists in `SERVER_SOURCE_ARCHITECTURE.md`, the recommendation says "implement the
already-decided design" and cites it, rather than re-deciding it.

1. **R1 (P2_MEDIUM, no control-flow change) — instrument the T5-SLOT-TEARDOWN upstream trigger.**
   Add the caller identity to the re-entrant teardown path (`SoftEtherVpnService.kt:1081–1106`) so
   the next occurrence records *who* requested the second teardown for the same attempt id. This is
   the only step that can convert R-T5-1's cause from UNKNOWN to a classified cause, and it follows
   the logging-only precedent of commit `5e51e37` (which armed the holder-identity instrumentation).
   Expected to also explain the ~1 s vs ~1 min variance.
2. **R2 (Low) — split the terminal message by outcome.**
   `AndroidConnectionAdapter.kt:226` should distinguish `UNRESOLVED` from `NOOP_REENTRANT` so the
   "ownership retained … will be refused" promise is only made when a refusal is actually intended
   (R-T5-2). One log line; no state change.
3. **R3 (Medium, Axis A) — designate a single UI state owner before any refactor.**
   Decide, in a decision record, whether `GlobalVpnTracker` or `AutoModeController._state` is the
   truth the UI renders, and reduce the other to a derived or advisory role. Fixing A-2/A-3/A-4/A-5
   *before* that designation risks encoding the inconsistency permanently. Note `HomeScreen.kt:125–126`
   is the one confirmed place both are consumed.
4. **R4 (Medium, Axis B, dependency-ordered) — implement the already-decided design, blockers first:
   (a) add the RX counter and the TUN-`EIO` counter to `GlobalVpnTracker` (B-4, the blocker);
   (b) then implement the per-request `Network.getSocketFactory()` bind exactly as
   `SERVER_SOURCE_ARCHITECTURE.md` §7 Q2 directs — **per-request SocketFactory on a dedicated
   `OkHttpClient`, never `bindProcessToNetwork()`** — following the §7 Q3 default order (open → VPN
   fallback → emergency → manual, with "always prefer VPN" as an opt-in setting only); (c) surface
   the `Network` already captured in `onAvailable` (B-2) instead of discarding it.**
5. **R5 (Low–Medium, Axis C) — classify the TUN read error explicitly.**
   Add the revoked-interface case alongside the existing `EBADF` branch in `TunTerminal.readLoop()`
   so a revoked TUN is not funnelled into `triggerReconnect()` (C-2), and decide — after reading the
   owner's call sites, which this phase did not do — whether the read loop should be re-armed
   in-place rather than left to an external `start()` (C-3).
6. **R6 (Low, hygiene) — resolve the dead constant / buffer mismatch.**
   Either delete `MAX_PACKET_SIZE` (`TunTerminal.kt:23`) or size the read buffer to the declared
   maximum, with a comment recording why the two differ. One-line decision (C-4).
7. **R7 (Medium, product) — schedule the OpenVPN fallback for UDP-only servers.**
   `RUDP_IMPLEMENTATION_PLAN.md` Open Item #1 remains the only viable path for `SETcpPort = 0`
   servers (C-1). When it is scheduled, resolve the interaction flagged in §5.1: the race consumes
   the full 5000 ms deadline while §6.7 R5 budgets 5 s per server, so the fallback must either be a
   separate attempt or R5 must be revisited for UDP-only servers. That is a product decision and is
   deliberately **not** decided here.

**Explicit non-recommendation:** this report does **not** recommend changing
`softether_connect_parallel_race` for `udp_only` servers. The native behaviour matches the official
client by the project's own parity audit; the defect is product-level (C-1) and the remedy is the
OpenVPN fallback, not a transport change.

---

## 9. Deliverables and no-change confirmation

**Deliverables of this phase:** this file (`AUDIT_AXES_A_B_C_v1.0.11.md`) and
`DOCS_UPDATE_v1.0.11_REPORT.md`, plus the `PROJECT_SPECIFICATION.md` v1.0.11 revision they
accompany.

**No-change confirmation (read-only audit):**
- **No source file was read-modify-written in producing this audit.** Every citation above is a
  read at HEAD `6119a2a`.
- **No build, test, adb, or device interaction was performed in this phase.** All device evidence
  is attributed to prior phases through their reports.
- **No commit, push, tag, force-push, reset, stash, checkout, or restore was performed.**
- **No final value, timeout, algorithm, ordering, or network-binding implementation was invented.**
  Already-decided requirements were cited, not re-decided; undecided items were left open.
- `PROJECT_SPECIFICATION.md` §1.6.2 / §1.6.3 / §1.6.4 and §22 L1–L10 were **not** modified.





---

## 10. Addendum — Correction to §2.3 (2026-10-14)

**Provenance:** PHASE `FIX-AND-VERIFY-AUDIT-TOP-PRIORITIES`, commit `0e3f9ca` (R1
instrumentation), device `5585fecc`, 7 observed occurrences. This section is appended as an
**addendum**: it corrects §2.3, but **§2.3 itself is preserved unmodified as a historical
artifact** of what was hypothesised before the instrumentation existed.

### §10.1 Evidence

The R1 log line (`[TEARDOWN-REENTRY]`, `SoftEtherVpnService.stopVpn()`, `NOOP_REENTRANT` branch)
fired 7/7 times with an **identical signature**:

```
W SoftEtherVpnService: [TEARDOWN-REENTRY] attempt=1,
  state=(isStopping=true, controller=false, tunHeld=false, job=false),
  thread=main, callers=vn.unlimit.softether.SoftEtherVpnService
```

Ordering of the re-entrant call against the real teardown it duplicates, for every occurrence:

```
attempt=1  -> teardown#1,   delta=220ms
attempt=2  -> teardown#3,   delta=185ms
attempt=3  -> teardown#5,   delta=274ms
attempt=4  -> teardown#7,   delta=212ms
attempt=5  -> teardown#9,   delta=242ms
attempt=6  -> teardown#11,  delta=153ms
attempt=7  -> teardown#13,  delta=384ms
```

### §10.2 The corrected mechanism

§2.3 hypothesised that the `NOOP_REENTRANT` completion satisfies
`TunnelStateWatcher.TeardownWaiter`'s exact-attempt-id wait **before** the real `CONFIRMED`
arrives — i.e. a race that the duplicate *wins*. The measured evidence shows the **opposite
ordering**: `Native cleanup confirmed` **precedes** `NOOP_REENTRANT` in 7/7 cases, by
**153–384 ms**.

The corrected mechanism is therefore:

> The re-entrant call is a **LATE DUPLICATE** arriving **after** teardown has already completed
> natively. It lands on the **main thread** while the service is **already fully idle**
> (`isStopping=true`, `controller=null`, TUN ownership released, connection job null). The
> `NOOP_REENTRANT` outcome it produces is **benign bookkeeping**, not a concurrency defect.

The most probable upstream trigger — consistent with a post-teardown callback on the main
thread — is an Android framework `VpnService` revocation/`onRevoke()` notification delivered
after the service has already torn itself down.

Corroborating evidence: teardown attempt ids step by exactly 2 per AutoConnect cycle
(`#1, #3, #5, #7, #9, #11, #13`), i.e. one real teardown plus one duplicate per cycle, and
**0 native lifetime-slot refusals** were observed across all 7 cycles.

### §10.3 Consequence for R3

The case for **R3 (single-flight teardown lock)** is **weakened** by this evidence. A
single-flight gate would suppress a duplicate that arrives when nothing is in flight, so it
would buy little; it would also add a failure mode of its own if a legitimate second
`stopVpn()` were rejected while state was still settling. **R3 should be re-scoped against the
corrected mechanism** rather than against §2.3's race hypothesis. If it is retained at all, it
should be framed as *duplicate suppression / log hygiene*, not as race prevention.

### §10.4 Known limitation (still UNKNOWN)

The R1 caller filter is deliberately restricted to `vn.unlimit.*` frames, per the phase brief.
That filter **hides the external framework caller** (`android.net.VpnService`,
`ConnectivityService`, …), so the **exact identity of the upstream trigger remains UNKNOWN**.
The corrected mechanism in §10.2 is an inference from thread/state/timing, not a directly
observed call site. Confirming it would require widening the frame filter to include
`android.*` — a change deliberately not made in this phase because the filter was prescribed.

### §10.5 Status

- §2.3: **preserved unmodified** (historical artifact).
- Residual `NOOP_REENTRANT` log line: **B-class, self-healing, benign** — unchanged
  classification, corrected cause.
- R3: **re-scope recommended** (see §10.3).
- Upstream trigger identity: **UNKNOWN** (see §10.4).
