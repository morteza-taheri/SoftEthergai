# AUDIT — AutoConnect Logic (READ-ONLY)

**Phase:** AUDIT-AUTOCONNECT-LOGIC
**Repository:** `D:\PMC\Documents\Android\SoftEthergai`
**Branch:** `main`
**HEAD at audit time:** `fa8a53d20d4bc8ba1e233d473c1c7723cbadb71f`
**`origin/main`:** `fa8a53d20d4bc8ba1e233d473c1c7723cbadb71f` (`git rev-list --left-right --count origin/main...HEAD` = `0	0`)
**Device:** `5585fecc` — Xiaomi 2201117TG (`spes`), Android 13 / API 33
**Installed package:** `vn.unlimit.vpngatepro`, `versionName=2.4.10-dev`, `versionCode=143`, `lastUpdateTime=2026-10-13 15:28:30`

### Evidence labels used throughout

| Label | Meaning |
|---|---|
| **[SOURCE CODE]** | Read directly from a repository file at a cited `file:line`. |
| **[TEST]** | Read from a unit-test file, or asserted by a test's expectation. |
| **[PHYSICAL DEVICE]** | Captured from the connected device during this audit or a prior authorized phase (cited artifact). |
| **[INFERENCE]** | A conclusion derived by combining the above; not directly observed. |
| **[UNKNOWN]** | Could not be determined with the evidence available in this phase. |

No label is used loosely: a claim is only **[SOURCE CODE]** if the cited line was read in this phase.

---

## §1 — Executive summary

### 1.1 The shape of the system

AutoConnect is **two admission gates in series guarding one process-wide native slot**:

1. **Kotlin gate** — `SoftEtherSessionPolicy.decideStart(resourcesLive)` → `ACCEPT` / `REFUSE_TEARDOWN` / `REFUSE_CONNECTED` / `REFUSE_CONNECTING` **[SOURCE CODE]** (`SoftEtherSessionPolicy.kt:186-191`).
2. **Native-ownership gate** — `NativeLifetimeRegistry.process.tryAcquire()` → `Lease?` **[SOURCE CODE]** (`NativeLifetimeRegistry.kt:227-260`).

Both must pass before a native call is entered. The registry is **fail-closed**: it refuses while any earlier attempt's native operation is unresolved. Its only two escapes are two **soft-bounds** that are evaluated *only inside `tryAcquire()`*, i.e. only at the moment a **new** attempt asks for admission:

```kotlin
forcedReleaseMessage = forceReleaseIfTimedOutLocked()
    ?: forceReleaseIfConnectingStuckLocked()
```
**[SOURCE CODE]** — `NativeLifetimeRegistry.kt:248-249`

### 1.2 The single root cause

**[INFERENCE, grounded in [SOURCE CODE]]** The stack has **one** root cause, and every Kotlin-side bound added so far treats a *consequence* of it:

> The native OpenSSL TLS handshake runs on a **blocking** socket whose only possible bound is `SO_RCVTIMEO`/`SO_SNDTIMEO` — and in the common IPv4 path those are **never applied at all**. `SSL_do_handshake` can therefore block indefinitely while holding two process-wide locks. Every Kotlin bound (30 s, 90 s, 120 s, 500 ms, 2 s, 5 s) is a *downstream* recovery from that block, never a prevention of it.

Three source facts establish this:

**(a) The socket is deliberately switched to blocking after connect.**
```c
if (try_connect_addr(sock, ai, timeout_ms, last_ip, sizeof(last_ip)) == 0) {
    freeaddrinfo(res);
    // Set back to blocking mode
    int flags = fcntl(sock->fd, F_GETFL, 0);
    fcntl(sock->fd, F_SETFL, flags & ~O_NONBLOCK);
    sock->connected = 1;
    sock->timeout_ms = timeout_ms;
```
**[SOURCE CODE]** — `tcp_socket.c:305-311` (identical at `:321-327`)

**(b) For the default IPv4 path, `SO_RCVTIMEO`/`SO_SNDTIMEO` are never set on that fd.** `socket_create` makes an `AF_INET` fd and records `sock->family = AF_INET` **[SOURCE CODE]** (`tcp_socket.c:27,35`). `socket_recreate_for_family` — the only place the receive/send timeouts are applied — **early-returns when the family already matches**:
```c
if (sock->fd >= 0 && sock->family == family) {
    return 0;
}
```
**[SOURCE CODE]** — `tcp_socket.c:172-174`. So for an IPv4 server no `setsockopt(SO_RCVTIMEO)` runs; the fd keeps the kernel default, which is **0 = no timeout**. `sock->timeout_ms = timeout_ms` at `:311`/`:327` is only a *struct field* — it is not pushed to the socket.

**(c) `conn->timeout_ms` never reaches the handshake.** `nativeSetOption(handle, OPTION_TIMEOUT=1, value)` writes exactly one field:
```c
case 1: // OPTION_TIMEOUT
    conn->timeout_ms = (int)value;
```
**[SOURCE CODE]** — `softether_jni.c:522-525`. That field is read in only three places: the TCP connect (`softether_protocol.c:2439`), the parallel-race budget (`:2194-2195`), and the NAT-T rendezvous/RUDP config (`:1639`, `:1664`). It is **not** passed to `ssl_connect`, and no `setsockopt` on `conn->socket_fd` occurs between the TCP connect and the handshake. **Answer to Part D.2: it applies to the connect/race path, not to the OpenSSL handshake path.**

The handshake's own loop is bounded only by `max_attempts = 100` counting *non-blocking* retries:
```c
int max_attempts = 100;  // Prevent infinite loop
...
while (attempt < max_attempts) {
    pthread_mutex_lock(&g_openssl_lock);
    result = SSL_do_handshake(ctx->ssl);
    pthread_mutex_unlock(&g_openssl_lock);
    ...
    if (ssl_error == SSL_ERROR_WANT_READ || ssl_error == SSL_ERROR_WANT_WRITE) {
        attempt++;
        usleep(10000);  // 10ms
```
**[SOURCE CODE]** — `aes_wrapper.c:453-487`. On a **blocking** socket there is no `WANT_READ`; the loop is a single unbounded `SSL_do_handshake` call and `max_attempts` is dead code.

### 1.3 Why the block becomes a process-wide wedge

While blocked, the handshake thread holds:

| Lock | Where acquired | Held during |
|---|---|---|
| `g_tls_use_lock` (read) | `aes_wrapper.c:450` | the whole handshake |
| `g_openssl_lock` | `aes_wrapper.c:469-471` | around `SSL_do_handshake` |

**[SOURCE CODE]** — `aes_wrapper.c:450,469-471`

Three consequences follow directly **[INFERENCE]**:

1. `ssl_destroy` needs the **write** lock and therefore blocks before it can free anything:
   ```c
   void ssl_destroy(ssl_context_t* ctx) {
       ...
       pthread_rwlock_wrlock(&g_tls_use_lock);   // <-- blocks behind the wedged handshake
   ```
   **[SOURCE CODE]** — `aes_wrapper.c:385-390`
2. `softether_destroy` calls `softether_disconnect` when the state is not `DISCONNECTED`, and `softether_disconnect` calls `ssl_shutdown` + `ssl_destroy` **[SOURCE CODE]** (`softether_protocol.c:1048-1051`, `:2824-2825`). So `nativeDestroy` — the Kotlin teardown's last step — **blocks**. The registry has already been marked `TEARDOWN` by `destroyResources()` **[SOURCE CODE]** (`ConnectionController.kt:851`), so the slot is now held in `TEARDOWN` **forever**. That is precisely the T5-SLOT leak.
3. Every other `ssl_connect` needs the write lock (`aes_wrapper.c:416`) and every `ssl_read`/`ssl_write` needs `g_openssl_lock` (`:571`, `:621`), so one wedged handshake freezes the whole TLS subsystem, not just one connection.

### 1.4 What is *not* broken

The two soft-bounds are correct as written and their **phase exclusivity is sound**: `forceReleaseIfTimedOutLocked` returns `null` unless `activePhase == Phase.TEARDOWN` **[SOURCE CODE]** (`NativeLifetimeRegistry.kt:398`), and `forceReleaseIfConnectingStuckLocked` returns `null` unless `activePhase == Phase.CONNECTING` **[SOURCE CODE]** (`:436`). Exactly one can match a given holder, so the `?:` ordering cannot pick the wrong one. The 90 s TEARDOWN bound **has been observed firing on device** (`FORCED_RELEASE_AFTER_TIMEOUT: attempt=2 held for 106041 ms`) **[PHYSICAL DEVICE]** — `_t5_part2_run.log`.

### 1.5 The gap that remains

**[PHYSICAL DEVICE]** On this device `SETTING_AUTO_MODE_TIMEOUT_SECONDS = 5` (`vpn_setting_data_pro.xml`), so Auto Mode's per-attempt budget is **5 s**, while the installed build's CONNECTING bound is **120 000 ms**. Auto Mode gives up long before the bound can fire, and the installed build contains **neither FIX-2 nor FIX-3** (proved by DEX scan in §8.2). So on the installed build the user's only recovery from a wedged CONNECTING holder is an app force-stop.

### 1.6 Recommendation headline

**[INFERENCE]** The single highest-impact change is **native**: bound the handshake at the socket level (apply `SO_RCVTIMEO`/`SO_SNDTIMEO` from `conn->timeout_ms` to the fd that carries the handshake, or drop `max_attempts` to a wall-clock deadline and/or make the handshake socket non-blocking), and make the parallel-race transport fds reachable by `softether_force_close_socket`. Everything else in §9 is either a consequence-bound (already in place) or a diagnostics improvement. Full reasoning and the ranked list are in §7 and §9.

---

## §2 — Part A: complete call graph, button tap → verified tunnel → idle

Every arrow below cites file + method + line range. **[SOURCE CODE]** throughout unless marked.

### A.1 Manual connect (the baseline)

```
DetailActivity (button)
  └─ DetailActivity.kt:~1280-1359  connectSoftEther-ish block
       ├─ builds ConnectionConfig(useTcp, udpPort, udpOnly, …)         :1304-1327
       └─ Intent(this, SoftEtherVpnService::class.java)
            action = ACTION_CONNECT; putExtra(EXTRA_CONFIG, config)    :1333-1336
          startForegroundService(intent) / startService(intent)        :1338-1342
```

```
SoftEtherVpnService.onStartCommand(intent, flags, startId)             :434-476
  ├─ startForeground(...)                                              :440-446
  └─ ACTION_CONNECT -> startVpn(config)                                :449-454
```

```
SoftEtherVpnService.startVpn(config)                                   :606-…
  ├─ 1. abandonWedgedConnectingAttemptIfAny()                          :633
  │      guards: enteredAt==0 / connectionJob==null / isRunning /
  │              heldMs < NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS (:587)
  ├─ 2. sessionResourcesLive = controller != null ||
  │        tunOwnership.isHeld() || connectionJob != null              :~636-640
  ├─ 3. sessionPolicy.decideStart(sessionResourcesLive)                :~641
  │      REFUSE_TEARDOWN  -> log "Start refused: teardown is in progress…"   :645
  │      REFUSE_CONNECTED -> log "Start refused: a connected session…"       :643
  │      REFUSE_CONNECTING-> log "Start refused: an initial connection…"     :641
  ├─ 4. NativeLifetimeRegistry.process.tryAcquire()                    :671
  │      null -> log "Start refused: native slot still held by an
  │              unresolved attempt (...)"                             :678-682
  │              notifyNativeBusy(sessionPolicy.sessionGeneration,
  │                               describe())                          :695
  ├─ 5. sessionPolicy.onSessionStart()                                 :720
  ├─ 6. tunOwnership.claim(session)                                    :730
  ├─ 7. attemptConnectingEnteredAtMs = System.currentTimeMillis()      :745
  ├─ 8. connectionJob = serviceScope.launch { … }                      :746
  │      └─ ConnectionController(…, nativeLease, sessionId = session,
  │           onStateChange, onError, onTrafficUpdate)                 :770-824
  │         ├─ controller?.connect()                                   :838
  │         └─ on completion:
  │              sessionPolicy.isCurrentSession(session) -> {
  │                  sessionPolicy.onRunning()
  │                  attemptConnectingEnteredAtMs = 0L                 :~860
  │                  if (!isStopping) Log.d("VPN connection established") }
  │                                                                    :849-880
  └─ catch (CancellationException) :885   catch (Exception) :969
```

```
ConnectionController.connect()                                         :233-294
  └─ while (reconnectAttempts.get() < MAX_RECONNECT_ATTEMPTS=2)         :261
       └─ performConnect()                                              :362-446
            ├─ currentState = CONNECTING                                :364
            ├─ isCancelled.set(false)                                   :365
            ├─ nativeLifecycle.isDestroyed() -> CancellationException   :384-393
            ├─ flow = nativeLifecycle.beginFlow()                       :398
            ├─ NativeLifetimeRegistry.process.markNativeEntered(lease)   :409   ← CONNECTING phase stamped
            ├─ connectInFlight.set(true)                                :422
            ├─ client.nativeCreate()                                    :424
            ├─ flow.publishHandle(created); nativeHandle = created       :427-428
            └─ performConnectInner()                                    :433
```

```
ConnectionController.performConnectInner()                              :522-735
  ├─ client.setTimeout(nativeHandle, config.connectTimeoutMs)           :535   ← OPTION_TIMEOUT
  ├─ client.setClientMac(...)                                           :548
  ├─ if (config.udpPort > 0) nativeSetOption(OPTION_UDP_PORT)           :553-556
  ├─ if (config.udpOnly)     nativeSetOption(OPTION_UDP_ONLY)           :559-562
  ├─ currentState = TLS_HANDSHAKE                                       :564
  ├─ startNativeStateMonitor()                                          :579
  ├─ client.setHalfConnection(...)                                      :583
  ├─ Log.d("[DIAG] nativeConnectWithHub entry (attempt=…, ts=…)")       :595
  ├─ client.nativeConnectWithHub(…)                     ◄── BLOCKING JNI :596-617
  ├─ finally: Log.d("[DIAG] nativeConnectWithHub exit (… elapsed=… )")  :618-627
  ├─ if (result != 0) throw Exception("Connection failed: …")            :635-638
  ├─ currentState = SESSION_SETUP                                       :642-644
  ├─ protect(socketFd) / protect(rudpFd) / protect(natTUdpFd)            :657-687
  ├─ client.doDhcp(nativeHandle)                     ◄── BLOCKING JNI    :691
  ├─ installTun(dhcpConfig) -> service.establishVpnInterface(...)        :716 / :466-469
  ├─ NativeLifetimeRegistry.process.markConnected(nativeLease)           :726   ← CONNECTED phase
  ├─ currentState = CONNECTED                                           :727
  ├─ startDataForwarding()                                              :731
  └─ startStatisticsLogging()                                           :734
```

```
ConnectionController.startDataForwarding()                             :1011-1123
  ├─ TunTerminal(tunInterface, scope); terminal.start(onPacket, onError) :1016-1060
  ├─ scope.launch { receive loop: client.receiveBatch(...) }            :1063-1119
  └─ startKeepalive(keepAlive)                                          :1122
```

```
SoftEtherVpnService.onStateChange -> STATE_CONNECTED
  └─ TunnelStateWatcher.onSoftEtherStateChanged / updateState           :313 / :330
       └─ deliver(…) -> armedTunnel.complete(true)
            └─ AndroidConnectionAdapter.awaitArmedOutcome -> TUNNEL_UP  :329-341
                 └─ AutoModeController.runAutoMode: connectedProtocol = protocol; break :1411-1414
                      ├─ wedgeTracker.clear(key)                        :1575
                      ├─ log "[AUTO] Tunnel established"                :1576
                      ├─ log "[AUTO] Connected successfully"            :1577
                      ├─ onSuccess(server, connectedProtocol)           :1578
                      ├─ setState(AutoModeState.Connected(…))           :1583-1591
                      ├─ startConnectedWatcher(connectedProtocol)       :1592
                      └─ return                                          :1593
```

### A.2 Return to idle

```
DetailActivity disconnect button / notification action
  ├─ DetailActivity.kt:1213-1214  Intent(ACTION_DISCONNECT)            [SOURCE CODE]
  └─ SoftEtherVpnService.createNotification:1478-1491 ACTION_DISCONNECT PendingIntent

SoftEtherVpnService.onStartCommand -> ACTION_DISCONNECT                  :460-468
  └─ supplied = intent.getIntExtra(EXTRA_TEARDOWN_ATTEMPT_ID, 0)         :466
     stopVpn(suppliedAttemptId = supplied)                              :467
```

```
SoftEtherVpnService.stopVpn(disconnectByError, suppliedAttemptId)       :1026-…
  ├─ teardownAttemptId = supplied ?: nextTeardownAttemptId()            :1037-1042
  ├─ tunOwnership.invalidate(tearingDown)                               :1067
  ├─ teardownComplete = controller == null && !tunOwnership.isHeld()
  │                    && connectionJob == null                         :1079
  ├─ re-entrant branch -> emit NOOP_REENTRANT                           :1098-1104
  ├─ Log.d("Stopping VPN")                                              :1108
  ├─ connectionJob?.cancel(); connectionJob = null                      :1115-1116
  ├─ attemptConnectingEnteredAtMs = 0L                                  :1119
  ├─ tunOwnership.release(tearingDown)                                  :1141
  └─ serviceScope.launch(NonCancellable) {
        currentController?.destroyResources()                           :~1155
        awaitNativeCleanup(NATIVE_CLEANUP_CONFIRM_TIMEOUT_MS = 2000)    :~1171
        notifyTeardownComplete(… NATIVE_CONFIRMED | NATIVE_UNRESOLVED)  :~1180
        if (mayStopServiceFor(...)) stopSelf()                          :~1200
     }                                                                  :1147-1206
```

```
ConnectionController.destroyResources()                                 :839-973
  ├─ NativeLifetimeRegistry.process.markTeardownRequested(nativeLease)  :851   ← TEARDOWN phase
  ├─ scope.cancel(); tunTerminal.stop(); vpnInterface.close()            :866-887
  ├─ nativeLifecycle.requestTeardown(NATIVE_TEARDOWN_TIMEOUT_MS=500)    :909
  │     └─ NativeAttemptCoordinator.requestTeardown(timeoutMs)          :200-240
  │          ├─ destroyed = true; teardownRequested = true              :203-204
  │          ├─ interruptNative(handle) -> client.nativeForceCloseSocket :210-219
  │          │     -> softether_force_close_socket -> shutdown(fd, SHUT_RDWR)
  │          ├─ flow.awaitExit(500) else UNRESOLVED                     :221-227
  │          ├─ claimCleanupLocked(TEARDOWN)                            :229
  │          └─ runCleanup() -> destroyNative(handle) -> nativeDestroy  :238 / :271-306
  ├─ on UNRESOLVED: markDeadlineExpired(nativeLease)                    :946
  │     + Log.d("[DIAG] teardown wait exceeded; flow still running …")  :950-954
  └─ returns NATIVE_CONFIRMED | NATIVE_UNRESOLVED                       :965-972
```

**Return to idle is complete only when `notifyTeardownComplete` reports `NATIVE_CONFIRMED`** — which requires `confirmTeardown(lease)` to have succeeded **[SOURCE CODE]** (`ConnectionController.kt:130-134`). `NATIVE_UNRESOLVED` leaves the registry holding the slot, and the next `tryAcquire()` is refused until a soft-bound fires.

### A.3 Enumeration (a) — points that can block **indefinitely** (no bound at all)

| # | Site | Why it is unbounded | User symptom | Corresponding log line |
|---|---|---|---|---|
| A3-1 | `SSL_do_handshake` — `aes_wrapper.c:470`, reached from `perform_tls_handshake` `softether_protocol.c:1186` and every `transport_thread_*` (`:1788,1875,1978,2061,2144`) | Blocking socket, no `SO_RCVTIMEO` on the IPv4 path; `max_attempts` counts only `WANT_READ` and never decrements on a blocking `recv` | Spinner stays on "Connecting / TLS handshake" indefinitely; UI has no timeout of its own | `SoftEtherProtocol: Starting TLS handshake with <ip>` with **no** following `TLS handshake successful`. `SSL handshake attempt N: need more data` appears **zero** times when blocked in `recv` **[SOURCE CODE]** `aes_wrapper.c:482` |
| A3-2 | `pthread_join` in `softether_connect_parallel_race` — `softether_protocol.c:2301-2303` | The race `deadline` (`:2283-2295`) only ends the *poll loop*; the `join` that follows is unconditional and has no timeout. A transport thread wedged in A3-1 is not joined-able | Auto Mode stalls with no per-attempt timeout observed at all; `awaitArmedOutcome` expires (5 s here) but the native call never returns | `SoftEtherProtocol: Parallel race: N transports, race timeout X ms` with **no** `Parallel race: <transport> won` and **no** `Parallel race: all transports failed` |
| A3-3 | `ssl_destroy`'s `pthread_rwlock_wrlock(&g_tls_use_lock)` — `aes_wrapper.c:390`, reached from `softether_destroy` → `softether_disconnect` → `ssl_destroy` (`softether_protocol.c:1050,2825`) | Writer lock cannot be taken while A3-1 holds the read lock. `nativeDestroy` therefore blocks **after** the registry has been stamped `TEARDOWN` | "Disconnect" appears to do nothing; VPN key icon may persist; every later connect is refused with "native slot still held" | `ConnectionController: Destroying resources (fast cleanup)` then `ConnectionController: Native cleanup unresolved (attempt=…, …)` and `[DIAG] teardown wait exceeded; flow still running (attempt=…, waitedMs=500)` |
| A3-4 | `ssl_read`/`ssl_write` on the same blocking socket — `aes_wrapper.c:572`, `:622`; reached from `read_http_response` `softether_protocol.c:784,838` and `send_vpnconnect_watermark` `:859-882` | Same missing `SO_RCVTIMEO`; `ssl_read`'s `max_retries = 5` only counts `WANT_READ`/`EINTR`/`EAGAIN`, never a blocking `recv` | Stalls in "Authenticating" / "Protocol handshake" instead of "TLS handshake" | `SoftEtherProtocol: Starting PACK-based HTTP authentication for user: …` with no completion, or `read_http_response: failed reading headers at byte N` never appearing |

### A.4 Enumeration (b) — every timeout / delay / deadline (detail in §4)

The complete table is §4. Summary of the *chain* a single Auto Mode attempt passes through:

`attemptTimeoutMs` (5 000 ms on this device) is the **only** bound that Auto Mode itself controls, and it bounds **Kotlin waiting**, not native execution **[SOURCE CODE]** (`AndroidConnectionAdapter.kt:292-297`, `AutoModeController.kt:1399`).

### A.5 Enumeration (c) — every refusal point

| # | Refusal | Site | Trigger | Log line | Clearing condition |
|---|---|---|---|---|---|
| A5-1 | `ERROR_NO_ENABLED_PROTOCOL` | `AutoModeController.kt:1188-1193` | `protocolPriorityProvider()` empty | `[AUTO] No protocol enabled by the user; refusing to start` | User enables a protocol |
| A5-2 | empty server list | `:1223-1227` | `serverProvider()` empty | `[AUTO] Server list is empty; refusing to start` | Non-empty list |
| A5-3 | reconnect-race abort | `:1215-1219` | `wasReconnect && isTunnelBusy()` | `[AUTO] Tunnel owned elsewhere; aborting Auto-Reconnect run` | Tunnel not owned elsewhere |
| A5-4 | no compatible server | `:1308-1312` | gate emptied the list | `[AUTO] No compatible server found` | Reachable server present |
| A5-5 | per-server: no usable protocol | `:1349-1352` | `usableProtocols(...)` empty | `[AUTO] Skipping <host>: no supported protocol` | Protocol capability present |
| A5-6 | **Kotlin gate** `REFUSE_TEARDOWN` | `SoftEtherSessionPolicy.kt:186-191`; logged `SoftEtherVpnService.kt:645` | `isStopping` | `Start refused: teardown is in progress and still owns resources` | `beginTeardown` completes → `isStopping` clears |
| A5-7 | **Kotlin gate** `REFUSE_CONNECTED` | `:186-191`; logged `:643` | `isRunning` | `Start refused: a connected session already owns the service` | Session stops |
| A5-8 | **Kotlin gate** `REFUSE_CONNECTING` | `:186-191`; logged `:641` | `resourcesLive && !isStopping && !isRunning` | `Start refused: an initial connection is still in flight` | `connectionJob`/`controller`/`tunOwnership` all clear |
| A5-9 | **Native gate** `tryAcquire() == null` | `NativeLifetimeRegistry.kt:227-260`; logged `SoftEtherVpnService.kt:678-682` | `activeAttemptId != NO_ATTEMPT` and neither soft-bound fired | `Start refused: native slot still held by an unresolved attempt (<describe()>)` | `confirmTeardown`, or a soft-bound |
| A5-10 | Auto Mode terminal give-up (budget) | `AutoModeController.kt:1468-1481` | cumulative retry delay would exceed 10 s | `[AUTO] Per-server retry budget exceeded (10s); stopping without blaming this server` | Run ends |
| A5-11 | Auto Mode terminal give-up (count) | `:1520-1532` | 2 NATIVE_BUSY retries exhausted | `[AUTO] Native slot still owned by an unresolved operation (…); stopping without blaming this server` | Run ends |
| A5-12 | VPN permission missing | `:1388-1393`, `:1494-1499` | `VpnService.prepare() != null` | `[AUTO] VPN permission missing; stopping Auto Mode` | User grants permission |
| A5-13 | FIX-3 session exclusion | `:1327-1333` | `wedgeTracker.isExcluded(key)` | `[AUTO] Skipping <host>: excluded for this session after N wedges` | Process restart |
| A5-14 | FIX-3 exclusion recorded | `:1673-1678` | `wedges >= EXCLUSION_THRESHOLD (2)` | `[AUTO] Excluding <host> from this session after N wedges.` | `wedgeTracker.clear(key)` on verified tunnel |

### A.6 Enumeration (d) — points that need an **external** action to clear

| # | Blocked state | Who can clear it | Why nothing in-app can |
|---|---|---|---|
| A6-1 | Wedged `SSL_do_handshake` on the **parallel-race** fd (A3-2) | The remote server, or process death | `softether_force_close_socket` shuts down only `conn->socket_fd` **[SOURCE CODE]** (`softether_protocol.c:1110-1113`). During the race `conn->socket_fd` is `-1` (cleared at `:2503` before the race at `:2504`), so the transport thread's local fd is unreachable. `pthread_join` cannot be timed out |
| A6-2 | `nativeDestroy` blocked on `g_tls_use_lock` (A3-3) | The wedged handshake returning, i.e. the remote server or `shutdown()` | `interruptNative` is called *before* `runCleanup` **[SOURCE CODE]** (`NativeAttemptCoordinator.kt:210-219`), so it is not a missing call — but if the wedged fd is not `conn->socket_fd` (A6-1), the `shutdown` targets the wrong fd |
| A6-3 | Slot held in `CONNECTING` past the CONNECTING bound, installed build | **App force-stop** | The bound is only evaluated inside `tryAcquire()`, and the installed build has no FIX-2, so nothing ever issues the teardown that would move the slot to `TEARDOWN` where the *verified* 90 s bound applies **[PHYSICAL DEVICE]** (§8.2) |
| A6-4 | L2TP/IPsec attempt | The user, in OS Settings | `[AUTO] L2TP connect is not automatable in background` **[SOURCE CODE]** (`AndroidConnectionAdapter.kt:101-104`) |

---

## §3 — Part B: the two gates and how they interact

### B.1 `decideStart()` input → `StartDecision` mapping

```kotlin
// SoftEtherSessionPolicy.kt:186-191
fun decideStart(resourcesLive: Boolean): StartDecision = when {
    !resourcesLive -> StartDecision.ACCEPT
    isStopping     -> StartDecision.REFUSE_TEARDOWN
    isRunning      -> StartDecision.REFUSE_CONNECTED
    else           -> StartDecision.REFUSE_CONNECTING
}
```
**[SOURCE CODE]** — `SoftEtherSessionPolicy.kt:186-191`; `StartDecision` enum at `:133-148`.

| `resourcesLive` | `isStopping` | `isRunning` | Decision | Notes |
|---|---|---|---|---|
| `false` | any | any | `ACCEPT` | No Kotlin-side resource exists; the native gate is now the only obstacle |
| `true` | `true` | any | `REFUSE_TEARDOWN` | `isStopping` wins over `isRunning` — teardown is the more restrictive state |
| `true` | `false` | `true` | `REFUSE_CONNECTED` | |
| `true` | `false` | `false` | `REFUSE_CONNECTING` | The catch-all: an initial attempt is in flight |

`resourcesLive` is computed in `startVpn()` as `controller != null || tunOwnership.isHeld() || connectionJob != null` **[SOURCE CODE]** (`SoftEtherVpnService.kt:~636-640`) — a **three-way OR over three independently-mutated fields**.

### B.2 `tryAcquire()` refusals → exact Phase + flag combinations

```kotlin
// NativeLifetimeRegistry.kt:230-249
synchronized(lock) {
    if (activeAttemptId != NO_ATTEMPT) {
        forcedReleaseMessage = forceReleaseIfTimedOutLocked()
            ?: forceReleaseIfConnectingStuckLocked()
```
**[SOURCE CODE]** — `NativeLifetimeRegistry.kt:230-249`

| `activePhase` | `deadlineExpired` | `teardownRequestedAtMs` | `connectingEnteredAtMs` | Elapsed | Result |
|---|---|---|---|---|---|
| `RESERVED` | any | any | any | any | **Refuse** — both bounds return `null` (`:398`, `:436`) |
| `CONNECTING` | any | any | `0` | any | **Refuse** — `connectingEnteredAtMs == 0` guard (`:437`) |
| `CONNECTING` | any | any | set | `< 30 000` | **Refuse** (`:439`) |
| `CONNECTING` | any | any | set | `>= 30 000` | **Force-release** `FORCED_RELEASE_CONNECTING_STUCK` (`:435-451`), then grant |
| `CONNECTED` | any | any | any | any | **Refuse** — no bound covers `CONNECTED` (by design: a live tunnel legitimately owns the slot) |
| `TEARDOWN` | `false` | any | any | any | **Refuse** (`:397`) |
| `TEARDOWN` | `true` | `0` | any | any | **Refuse** (`:399`) |
| `TEARDOWN` | `true` | set | any | `< 90 000` | **Refuse** (`:401`) |
| `TEARDOWN` | `true` | set | any | `>= 90 000` | **Force-release** `FORCED_RELEASE_AFTER_TIMEOUT` (`:396-413`), then grant |

**[SOURCE CODE]** for every row. Two rows are worth naming explicitly as **uncovered**:

- **`Phase.CONNECTED` has no bound at all.** If a session reaches `CONNECTED` and the tunnel then dies without a teardown request, the slot is held until `stopVpn` runs. This is intentional (a live tunnel must not be pre-empted) but means a `CONNECTED`-phase leak is unrecoverable in-app **[INFERENCE]**.
- **`Phase.RESERVED` has no bound.** `RESERVED` is entered by `tryAcquire()` itself and exited by `markNativeEntered` **[SOURCE CODE]** (`ConnectionController.kt:409`). The window is short (`:671` → `:838`), but if the coroutine is cancelled between the two, the slot stays `RESERVED` with no bound **[INFERENCE]**.

### B.3 Pairs where **both gates refuse and neither can clear the other**

This is the structurally important section. Both gates are **independent** and **neither reads the other**:

- The Kotlin gate reads `isStopping`, `isRunning`, and the three service fields **[SOURCE CODE]** (`SoftEtherSessionPolicy.kt:186-191`, `SoftEtherVpnService.kt:~636-640`).
- The native gate reads only `NativeLifetimeRegistry`'s own `activeAttemptId` / `activePhase` **[SOURCE CODE]** (`NativeLifetimeRegistry.kt:231`).

**No code path exists in which satisfying one gate clears the other.** **[SOURCE CODE — negative result]** The only clearing mechanisms are:

| Gate | Cleared by | Where |
|---|---|---|
| Kotlin | `beginTeardown(resourcesLive)` completing → `isStopping = false` | `SoftEtherSessionPolicy.kt:231-236` |
| Kotlin | `connectionJob`/`controller`/`tunOwnership` all becoming null | `SoftEtherVpnService.kt:1115-1116`, `:1141`; `abandonWedgedConnectingAttemptIfAny` `:575-604` |
| Native | `confirmTeardown(lease)` | `NativeLifetimeRegistry.kt` `confirmTeardown`; called from `ConnectionController.kt:131` |
| Native | either soft-bound, **inside `tryAcquire()` only** | `:248-249` |

**The deadlock pairs:**

**B3-1 — `REFUSE_TEARDOWN` ∧ `TEARDOWN` held, both stuck on the same native call.**
`stopVpn` sets `isStopping` and stamps the registry `TEARDOWN` **[SOURCE CODE]** (`SoftEtherVpnService.kt:~1037-1141`, `ConnectionController.kt:851`). The registry can only be cleared by `confirmTeardown`, which requires `runCleanup()` → `destroyNative` to return **[SOURCE CODE]** (`NativeAttemptCoordinator.kt:271-306`). `destroyNative` blocks on `g_tls_use_lock` (A3-3). `isStopping` clears only when the teardown coroutine finishes **[SOURCE CODE]** (`SoftEtherVpnService.kt:1147-1206`).
**Neither gate can clear the other. Only the 90 s TEARDOWN soft-bound escapes this — and it fires only when a *new* attempt calls `tryAcquire()`, i.e. the user must press Connect again.** **[INFERENCE]** This is exactly the observed device behaviour: the bound fired at `held for 106041 ms` only when a new attempt asked for admission **[PHYSICAL DEVICE]** (`_t5_part2_run.log`).

**B3-2 — `REFUSE_CONNECTING` ∧ `CONNECTING` held.**
`abandonWedgedConnectingAttemptIfAny()` is the *only* Kotlin-side clearer, and it is guarded by the **same constant** the native bound uses:
```kotlin
if (heldMs < NativeLifetimeRegistry.NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS) return
```
**[SOURCE CODE]** — `SoftEtherVpnService.kt:587`
So FIX-C lowers the Kotlin gate automatically when FIX-1 lowers the constant — **verified statically** **[SOURCE CODE]**. But note the ordering inside `startVpn`: `abandonWedgedConnectingAttemptIfAny()` runs at `:633`, **before** `decideStart` at `:~641`. So the Kotlin gate is cleared first, then the native gate is cleared by the soft-bound inside `tryAcquire()` at `:671`. Both are needed and both are gated on the same elapsed time **[INFERENCE]**.

**B3-3 — Kotlin gate clear, native gate still set.**
`abandonWedgedConnectingAttemptIfAny()` clears `connectionJob`/`controller`/`tunOwnership` but **deliberately does not dispatch a native destroy** **[SOURCE CODE]** (`SoftEtherVpnService.kt:575-604`). So after it runs, `resourcesLive == false` → `ACCEPT` → but `tryAcquire()` still refuses until the CONNECTING bound fires. This is the **designed** split: Kotlin resources and native ownership are separate authorities. Consequence: for up to `NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS` after the Kotlin side is ready, the user still sees `Start refused: native slot still held by an unresolved attempt (...)` **[INFERENCE]**.

**B3-4 — native gate clear, Kotlin gate still set.**
The reverse is possible via the soft-bounds: a forced release frees the slot while `connectionJob != null` and `isStopping` are still true. The forced release does **not** touch any Kotlin field **[SOURCE CODE]** (`:396-451` mutate only registry state). So `decideStart` still returns `REFUSE_TEARDOWN`/`REFUSE_CONNECTING` and the new attempt is refused **by the Kotlin gate** — the forced release is wasted, and the registry is now `RESERVED` with `NO_ATTEMPT` while the Kotlin side believes an attempt is live **[INFERENCE]**. This is the one interaction worth a test; it is **not** covered by any existing test **[TEST — negative result]**: `NativeLifetimeRegistryTest.kt` exercises the registry in isolation and never drives `SoftEtherSessionPolicy` alongside it.

**B3-5 — both gates refuse with no bound on the native side.** The `CONNECTED` and `RESERVED` rows of B.2. `decideStart` returns `REFUSE_CONNECTED` (if `isRunning`) and `tryAcquire` refuses with no bound. Unrecoverable in-app **[INFERENCE]**.

---

## §4 — Part C: complete timeout / delay / deadline / budget table

### C.1 Kotlin — `AutoModeController.kt`

| Symbol | Value | Units | Declared at | Consumed at | Expiry behaviour |
|---|---|---|---|---|---|
| `DEFAULT_ATTEMPT_TIMEOUT_MS` | `25_000` | ms | `:308` | default of the `attemptTimeoutMs` ctor param | Only a default; production overrides it (see next row) |
| `attemptTimeoutMs` (ctor) | **`5_000` on this device** | ms | param; `AutoModeEngine.kt:75` = `du.getAutoModeTimeoutSeconds() * 1000` | `adapter.awaitArmedOutcome(protocol, attemptTimeoutMs)` `:1399`, `:1505`; log `:1205`, `:1560` | `awaitArmedOutcome` returns `TIMED_OUT`; loop advances to the next protocol |
| `NATIVE_BUSY_RETRY_DELAY_MS` | `2_000` | ms | `:361` | `delay(nativeBusyRetryDelayMs)` `:1489`; budget accounting `:1483` | Cancellable `delay`; a user cancel ends the coroutine cleanly |
| `NATIVE_BUSY_MAX_RETRIES` | `2` | count | `:370` | `val retryCount = NATIVE_BUSY_MAX_RETRIES` `:1450` | On exhaustion → `giveUpWithUnresolvedNativeOperation` `:1528` |
| `PER_SERVER_RETRY_DELAY_BUDGET_MS` | `10_000` | ms | `:391` | gate `:1468-1470` | On exhaustion → `giveUpWithUnresolvedNativeOperation` `:1478` |

**[SOURCE CODE]** for every row. Note the accounting is **nominal** (one `NATIVE_BUSY_RETRY_DELAY_MS` per scheduled delay), so with `NATIVE_BUSY_MAX_RETRIES = 2` the budget gate (`2 × 2000 = 4000 <= 10000`) **can never fire before count exhaustion** — the budget branch at `:1468-1481` is therefore **unreachable in production** with the current constants **[INFERENCE]**. It is reachable only if `NATIVE_BUSY_MAX_RETRIES` is raised.

### C.2 Kotlin — `SoftEtherVpnService.kt`

| Symbol | Value | Units | Declared at | Consumed at | Expiry behaviour |
|---|---|---|---|---|---|
| `NATIVE_CLEANUP_CONFIRM_TIMEOUT_MS` | `2_000` | ms | `:112` | `awaitNativeCleanup(...)` `:~1171` | Reports `NATIVE_UNRESOLVED`; the slot stays held |
| `NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS` (read) | `30_000` (post-FIX-1) / `120_000` (installed) | ms | `NativeLifetimeRegistry.kt:519` | `SoftEtherVpnService.kt:587` | Below it, the Kotlin attempt is left alone |
| `teardownAttemptSeq` | monotonic | count | `:193` `nextTeardownAttemptId()` | `stopVpn` `:1037-1042` | Identity only; never expires |

### C.3 Kotlin — `ConnectionController.kt`

| Symbol | Value | Units | Declared at | Consumed at | Expiry behaviour |
|---|---|---|---|---|---|
| `MAX_RECONNECT_ATTEMPTS` | `2` | count | `:74` | `:261`, `:1157` | On exhaustion → `currentState = ERROR` + `onError(...)` `:1161-1183` |
| `RECONNECT_DELAY_MS` | `1_000` | ms | `:75` | `:282`, `:750`, `:1229` | Cancellable `delay` |
| `STATS_INTERVAL_MS` | `1_000` | ms | `:76` | `:1510`, `:1536`, `:1566` | Loop only |
| `NATIVE_TEARDOWN_TIMEOUT_MS` | `500` | ms | `:78` | `nativeLifecycle.requestTeardown(...)` `:909` | `UNRESOLVED` → `markDeadlineExpired` `:946` + `[DIAG]` `:950-954` |
| keepalive timeout | `10_000` | ms | `:1473` (`setTimeout(10000L)`) | `KeepAliveManager.isConnectionDead()` `:1486` | Triggers `triggerReconnect()` `:1488` |

### C.4 Kotlin — `NativeLifetimeRegistry.kt`

| Symbol | Value | Units | Declared at | Consumed at | Expiry behaviour |
|---|---|---|---|---|---|
| `NATIVE_SLOT_FORCE_RELEASE_AFTER_MS` | `90_000` | ms | `:479` | `forceReleaseIfTimedOutLocked` `:401` | Force-release labelled `FORCED_RELEASE_AFTER_TIMEOUT` `:409-412` |
| `NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS` | `30_000` (post-FIX-1); `120_000` in the installed build | ms | `:519` | `forceReleaseIfConnectingStuckLocked` `:439` | Force-release labelled `FORCED_RELEASE_CONNECTING_STUCK` `:447-450` |
| `NO_ATTEMPT` | `0` | sentinel | `:469` | throughout | n/a |

### C.5 Kotlin — `AndroidConnectionAdapter.kt`

| Symbol | Value | Units | Declared at | Consumed at | Expiry behaviour |
|---|---|---|---|---|---|
| `TEARDOWN_TIMEOUT_MS` | `5_000` | ms | `:56` | `withTimeoutOrNull(TEARDOWN_TIMEOUT_MS) { teardown.await() }` `:207` | Logs `[AUTO] Teardown NOT confirmed within 5000ms` `:214`; Auto Mode proceeds anyway |

### C.6 Native

| Symbol | Value | Units | Declared at | Consumed at | Expiry behaviour |
|---|---|---|---|---|---|
| `conn->timeout_ms` default | `30000` | ms | `softether_protocol.c:962` | TCP connect `:2439`; race budget `:2194-2195`; NAT-T `:1639`, `:1664` | Per-call; **never reaches the handshake** |
| `OPTION_TIMEOUT` write | caller-supplied | ms | `softether_jni.c:522-525` | same as above | Same |
| `SOCKET_TIMEOUT_MS` | `30000` | ms | `softether_socket.h:18` | `socket_create` `tcp_socket.c:37`; `socket_connect` `:339` | Applied to the fd **only** via `socket_recreate_for_family` `:193-199` |
| TCP connect bound | `timeout_ms` | ms | `tcp_socket.c:236` | `poll(&pfd, 1, timeout_ms)` | Bounded ✔ |
| `race_timeout_ms` | `conn->timeout_ms`, floored at `5000` | ms | `softether_protocol.c:2194-2195` | per-transport `t->timeout_ms` `:2204-2252`; race `deadline` `:2283` | Bounds the **poll loop only**; the following `pthread_join` `:2302` is unbounded |
| `data_timeout_ms` | `5000` | ms | `:2643` and `:3654` | `setsockopt(SO_RCVTIMEO)` `:2647`, `:3658` | Applied **after** login, to the data path only |
| handshake attempt cap | `max_attempts = 100` | count | `aes_wrapper.c:453` | loop `:457` | Counts only `WANT_READ`/`WANT_WRITE` — **inert on a blocking socket** |
| handshake inter-attempt sleep | `10000` | µs | `aes_wrapper.c:485` | `usleep(10000)` | Only on the `WANT_READ` branch |
| `ssl_read` retry cap | `max_retries = 5` | count | `aes_wrapper.c:552` | loop `:592` | Counts only `WANT_READ`/`EINTR`/`EAGAIN` — inert on a blocking socket |
| NAT-T rendezvous | `conn->timeout_ms` | ms | `softether_protocol.c:1639` | `nat_t_connect_ex(...)` | Bounded ✔ |
| RUDP transport connect | `conn->timeout_ms` | ms | `softether_protocol.c:1664` | `cfg.connect_timeout_ms` → `rudp_transport_connect` `:1666` | Bounded ✔; race threads also get `cancel_flag` `:1859` etc. |
| `NAT_T_DEFAULT_TIMEOUT_MS` | (see file) | ms | `softether_nat_t.c:213` | rendezvous internals | Bounded ✔ |
| receive poll | `100` | ms | `packet_handler.c:1014-1015` | idle poll | Bounded ✔ |
| `softether_receive_data` poll | `200` | ms | `softether_protocol.c:3460` | data path | Bounded ✔ |
| `reachabilityFilter` probe | `1200` | ms | `AutoModeEngine.kt:78` | probe invocation | Bounded ✔ |

### C.7 Inconsistencies, classified

| # | Inconsistency | Class | Reasoning |
|---|---|---|---|
| C7-1 | `conn->timeout_ms` is documented and used as "the connect timeout", but the handshake — the longest phase of a connect — is not bounded by it | **wedge** | Directly produces A3-1 |
| C7-2 | `SO_RCVTIMEO`/`SO_SNDTIMEO` are applied only in `socket_recreate_for_family`, which early-returns for the already-created `AF_INET` fd; `sock->timeout_ms` is then updated without re-applying | **wedge** | The IPv4 fd keeps `SO_RCVTIMEO = 0` |
| C7-3 | The handshake is bounded by a **count** (`max_attempts`) that only advances on non-blocking returns, while the socket is put into **blocking** mode | **wedge** | The two designs are mutually exclusive; on a blocking socket the cap is dead code |
| C7-4 | `race_timeout_ms` bounds the race's poll loop but `pthread_join` after it has no timeout | **wedge** | A transport thread wedged in the handshake makes `pthread_join` permanent |
| C7-5 | `softether_force_close_socket` shuts down only `conn->socket_fd`; race transport fds are local to their threads and never published | **wedge** | The one designed interrupt cannot reach the wedged fd |
| C7-6 | The installed build's CONNECTING bound (120 s) is 24× the device's own per-attempt budget (5 s) | **slow-but-eventual recovery** | The bound is correct but unreachable within one run; the user waits ~2 min |
| C7-7 | `PER_SERVER_RETRY_DELAY_BUDGET_MS = 10_000` is unreachable with `NATIVE_BUSY_MAX_RETRIES = 2` × `NATIVE_BUSY_RETRY_DELAY_MS = 2_000` | **not a wedge** | Dead branch; the budget log line at `:1471-1475` cannot be emitted in production |
| C7-8 | `TEARDOWN_TIMEOUT_MS` (5 s, adapter) > `NATIVE_TEARDOWN_TIMEOUT_MS` (500 ms, controller) > `NATIVE_CLEANUP_CONFIRM_TIMEOUT_MS` (2 s, service) | **not a wedge** | Layered by design; but 500 ms is very short for a native `destroy` that may need to join a receive thread. Classified as **premature give-up** risk |
| C7-9 | `Phase.CONNECTED` and `Phase.RESERVED` have no soft-bound | **slow-but-eventual recovery → none** | Not a wedge today; but a leak in either phase is unrecoverable in-app |
| C7-10 | `data_timeout_ms = 5000` **is** applied to the fd after login (`:2647`) — proving the mechanism exists and is simply not used for the handshake | **wedge (root)** | The strongest single piece of evidence that C7-1 is an omission, not a design choice |

---

## §5 — Part D: the native wedge (root-cause candidates)

### D.1 — every blocking call in `softether_connect_with_hub` and whether it is bounded

Entry: `softether_connect_with_hub` (`softether_protocol.c:2709-2730`) takes `connect_mutex` (`:2723` body call), then `softether_connect_with_hub_nolock` (`:2332-2665`).

| Step | Call site | Bounded? | By what |
|---|---|---|---|
| `resolve_hostname(host, ...)` | `:2410` | **No** | `getaddrinfo` has no timeout in this wrapper **[SOURCE CODE]** `tcp_socket.c:60-111` |
| TCP `socket_connect_timeout(sock, connect_host, port, conn->timeout_ms)` | `:2439`, retry `:2448` | **Yes** | `poll(&pfd, 1, timeout_ms)` `tcp_socket.c:236` |
| `connect()` on the TCP socket | `tcp_socket.c:229` | **Yes** | Non-blocking fd → `EINPROGRESS` → `poll` `:231-244` |
| **`perform_tls_handshake` → `ssl_connect` → `SSL_do_handshake`** | `:2489` → `aes_wrapper.c:1186` → `:470` | **NO** | Blocking socket (`tcp_socket.c:307-309`), no `SO_RCVTIMEO` on the IPv4 path; `max_attempts` inert. **This is A3-1** |
| `ssl_write` (watermark POST) | `:882` region → `aes_wrapper.c:622` | **NO** | Single blocking `SSL_write`, no loop, no timeout |
| `read_http_response` → `ssl_read` loop | `:784`, `:838` → `aes_wrapper.c:572` | **NO** | `max_retries = 5` inert on a blocking socket |
| `perform_authentication_http` login POST/response | `:1208-1330` | **NO** | Same read/write path |
| NAT-T rendezvous `nat_t_connect_ex(..., conn->timeout_ms, ...)` | `:1639` | **Yes** | `conn->timeout_ms` |
| `rudp_transport_connect(tr, &cfg, NULL)` | `:1666` | **Yes** | `cfg.connect_timeout_ms = conn->timeout_ms` `:1664` |
| `softether_connect_parallel_race` poll loop | `:2285-2295` | **Yes** | `deadline = tick + race_timeout_ms` `:2283` |
| **`pthread_join` in the race** | `:2301-2303` | **NO** | Unconditional join after the deadline. **This is A3-2** |
| race transport `ssl_connect` | `:1788`, `:1875`, `:1978`, `:2061`, `:2144` | **NO** | Same as A3-1, on fds that `softether_force_close_socket` cannot reach |
| `ssl_has_pending` | `:2637` | Yes | Non-blocking query |
| `softether_establish_first_additional` | `:2659` | **Partly** | `ssl_connect` at `:3641` is unbounded; `data_timeout_ms` is set at `:3654-3657` |
| `rudp_poll` | `:2585`, `:2882` | Yes | Non-blocking tick |

### D.2 — does `nativeSetOption(1, 5000)` apply to the handshake or only the data path?

**Only the connect/race/data paths. Not the OpenSSL handshake.** **[SOURCE CODE]**

- The write: `conn->timeout_ms = (int)value;` — `softether_jni.c:522-525`. The Kotlin caller is `SoftEtherClient.setTimeout(handle, timeoutMs)` → `nativeSetOption(handle, OPTION_TIMEOUT, timeoutMs.toLong())` **[SOURCE CODE]** (`SoftEtherClient.kt:217-219`, `OPTION_TIMEOUT = 1` at `:315`), invoked from `ConnectionController.performConnectInner` `:535` with `config.connectTimeoutMs`.
- The reads: `softether_protocol.c:2439` (TCP connect), `:2194-2195` (race budget), `:1639` (NAT-T), `:1664` (RUDP). **Four sites, none of them `ssl_connect`.**
- The handshake path is `perform_tls_handshake(conn, hostname)` → `ssl_connect(ssl_ctx, conn->socket_fd, hostname)` **[SOURCE CODE]** (`softether_protocol.c:1168-1190`). `ssl_connect`'s signature is `(ssl_context_t*, int socket_fd, const char* hostname)` **[SOURCE CODE]** (`aes_wrapper.c:410`) — **it does not receive a timeout at all**. It has no way to learn `conn->timeout_ms`.
- The socket-level timeouts that *could* have bounded it are set in only two places: `socket_recreate_for_family` (`tcp_socket.c:193-199`) and `socket_set_timeout` (`:443-455`). Neither is called between the TCP connect and the handshake for the default IPv4 path.
- **Proof that the omission is an omission, not a design choice:** the *same* mechanism **is** used for the data path immediately after login — `data_timeout_ms = 5000; setsockopt(conn->socket_fd, SOL_SOCKET, SO_RCVTIMEO, &tv, ...)` **[SOURCE CODE]** (`softether_protocol.c:2643-2648`). The code knows how to bound an fd; it just does not do it before the handshake.

### D.3 — ranking the wedge mechanisms against the >78 s evidence

| Rank | Mechanism | Mechanism summary | Fit with the evidence |
|---|---|---|---|
| **1** | **Unbounded blocking `SSL_do_handshake`** (A3-1) | TCP connects, ClientHello sent, server never answers or answers partially; `recv` blocks with `SO_RCVTIMEO = 0` while `g_openssl_lock` + `g_tls_use_lock(read)` are held | **Best fit.** Explains `Starting TLS handshake` with no `SSL handshake successful`; explains the state sticking at `STATE_TLS_HANDSHAKE` (set at `:1174` by `perform_tls_handshake`, which the race threads never set); explains why the duration exceeds every Kotlin bound; explains the downstream `nativeDestroy` block (A3-3) that produces the `TEARDOWN` leak |
| **2** | **Unbounded `pthread_join` after the race deadline** (A3-2) | One transport thread wedges in mechanism 1 on a **local** fd; the race's poll loop ends on the deadline but `pthread_join` then blocks permanently, and `shutdown(conn->socket_fd)` cannot reach that fd | **Also fits**, and is *worse* (uninterruptible). Distinguishing evidence needed: whether `Parallel race: N transports` was logged before the stall |
| **3** | `ssl_destroy` blocked on `g_tls_use_lock` (A3-3) | Not an independent cause — a **consequence** of 1 or 2 | Explains the TEARDOWN-phase leak and why the 90 s bound is needed |
| **4** | Unbounded login-phase read (A3-4) | Same missing timeout on `ssl_read` in `read_http_response` | Would present as a stall in AUTHENTICATING rather than TLS_HANDSHAKE; the observed state was TLS_HANDSHAKE, so this is ranked below 1 |
| **5** | `getaddrinfo` with no timeout (`:2410`) | DNS resolution stall | Would present as a stall in CONNECTING with no TLS log at all; not consistent with the observed TLS_HANDSHAKE state |

### D.4 — evidence that would distinguish them (stated, **not performed**)

1. **Logcat discriminator (cheap, decisive).** `aes_wrapper.c:482` emits `SSL handshake attempt %d: need more data (error=%d)` on **every** `WANT_READ`. If the handshake is blocked in a blocking `recv`, that line appears **zero** times. If the socket were non-blocking, up to 100 such lines would appear within ~1 s. So: `grep -c "need more data"` against a captured stall window separates "blocked in recv" from "spinning". **[SOURCE CODE]** for the line; the test itself is not run in this phase.
2. **Race discriminator.** `grep -c "Parallel race: .* transports"` vs `grep -c "Parallel race: .* won"` / `"all transports failed"` in the same window. A logged race start with neither terminal line localises the wedge to mechanism 2.
3. **Native thread dump.** A readable native backtrace showing `SSL_do_handshake` / `recv` vs `pthread_join` vs `pthread_rwlock_wrlock` would be conclusive. **Currently unavailable:** `kill -3` on this device produced a tombstone in `/data/tombstones` that could not be read (permission denied) — trace content **UNKNOWN**.
4. **Lock-holder inference.** If a *second*, unrelated connection's `ssl_read` also stalls while the first handshake is wedged, mechanism 1 is confirmed (only `g_openssl_lock` produces that cross-connection coupling).

---

## §6 — Part E: manual vs AutoMode

### E.1 Step-by-step comparison

| Step | Manual (`DetailActivity`) | Auto Mode (`AutoModeController`) |
|---|---|---|
| Entry | `DetailActivity.kt:1333-1342` `ACTION_CONNECT` + `EXTRA_CONFIG` | `AndroidConnectionAdapter.connectSoftEther` `:445-513` |
| Who builds the config | `DetailActivity.kt:1304-1327` | `AndroidConnectionAdapter.kt:483-506` |
| `connectTimeoutMs` | **not set** → `ConnectionConfig` default | `attemptTimeoutMs.toInt()` = `getAutoModeTimeoutSeconds() * 1000` `:481`, `:499` |
| Per-attempt wait | **none** — the UI waits on the service's state broadcasts indefinitely (`DetailActivity.kt:113-260`) | `awaitArmedOutcome(protocol, attemptTimeoutMs)` `AutoModeController.kt:1399`, `:1505` |
| Teardown trigger | user presses Disconnect / notification action (`DetailActivity.kt:1213-1214`, `SoftEtherVpnService.kt:1478-1491`) | `adapter.disconnect()` `:1567`, `:1406`; `skipCurrent()` `:427-432`; **FIX-2** `requestRecoveryTeardown()` `:163-169` |
| Teardown wait | **none** — fire and forget | `withTimeoutOrNull(5000) { teardown.await() }` `AndroidConnectionAdapter.kt:207` |
| On stall | UI shows "Connecting" forever; the user can press Disconnect, which *does* call `destroyResources()` → `interruptNative` → `shutdown()` | the 5 s wait expires, Auto Mode advances; **nothing issues a teardown on the give-up path in the installed build** |

### E.2 Why the manual flow never accumulates a non-null `connectionJob` past a stop

**[SOURCE CODE + INFERENCE]** Three properties, all absent from the Auto Mode flow:

1. **Manual disconnect is unconditional and singular.** `ACTION_DISCONNECT` → `stopVpn(suppliedAttemptId = 0)` **[SOURCE CODE]** (`SoftEtherVpnService.kt:460-468`). `stopVpn` nulls `connectionJob` at `:1115-1116` **unconditionally**, before launching the teardown coroutine at `:1147-1206`. There is no path in `stopVpn` that leaves `connectionJob` set.
2. **Manual has exactly one teardown issuer.** The UI button and the notification action are the only sources **[SOURCE CODE]** (`DetailActivity.kt:1213`, `SoftEtherVpnService.kt:1479-1481`). Auto Mode has **four** issuers (`disconnect()`, `skipCurrent()`, the FIX-2 recovery path, and `giveUpWithUnresolvedNativeOperation`) plus the shared `teardownDispatched` idempotency flag at `AndroidConnectionAdapter.kt:118` — a flag that a *normal* disconnect earlier in the same attempt can still hold.
3. **The manual flow has no give-up path that ends the run without a teardown.** Auto Mode's terminal give-ups at `:1468-1481` and `:1520-1532` set `job = null` and `setState(Error(...))` and `return` — they end the **coroutine**, which is precisely what leaves `connectionJob` set in the service, because `connectionJob` is the service's field, not the controller's **[SOURCE CODE]** (`SoftEtherVpnService.kt:746`). Before FIX-2, no teardown was requested at that point, so `stopVpn` never ran, so `connectionJob` was never nulled, so `decideStart` kept returning `REFUSE_CONNECTING` **and** the registry kept the slot in `CONNECTING` with no bound able to fire (nothing was calling `tryAcquire()` because nothing was trying to start).

**That last sentence is the precise mechanism of the reported defect.** **[INFERENCE]** Auto Mode could wedge the app while the manual flow could not, and the difference is structural: Auto Mode's terminal state is "the run ended", not "the session ended".

### E.3 What Auto Mode would need for the same recovery guarantee

**[INFERENCE]** Stated as a requirement, not a proposal:

- **R1** — every terminal give-up must issue a teardown, so the slot reaches `TEARDOWN` (where the verified 90 s bound applies) and the service fields are nulled. *This is exactly what FIX-2 does* **[SOURCE CODE]** (`AutoModeController.kt:1663-1679` → `AndroidConnectionAdapter.kt:163-169`).
- **R2** — the give-up teardown must not be suppressible by `teardownDispatched`; FIX-2 already bypasses it deliberately **[SOURCE CODE]** (`AndroidConnectionAdapter.kt:149-154`).
- **R3** — the per-attempt budget and the CONNECTING bound must be commensurate. On this device they are 5 s and 120 s; FIX-1 brings the bound to 30 s, which is still 6× the budget but no longer unreachable within a run **[SOURCE CODE]** (`NativeLifetimeRegistry.kt:485-500`).
- **R4** — the manual flow's *unconditional* `connectionJob = null` has no Auto Mode equivalent; Auto Mode relies on `stopVpn` being reached. R1 is the way to reach it.

---

## §7 — Part F: gap analysis of the prior fixes

### F.1 Per-fix analysis

#### F1 — TEARDOWN soft-bound (`NATIVE_SLOT_FORCE_RELEASE_AFTER_MS = 90_000`)

| Aspect | Finding |
|---|---|
| What it addressed | A slot held in `Phase.TEARDOWN` past its reporting deadline with no exit — the T5-SLOT leak |
| What it actually solved on device | **Verified.** `[REGISTRY] FORCED_RELEASE_AFTER_TIMEOUT: attempt=2 held for 106041 ms` **[PHYSICAL DEVICE]** (`_t5_part2_run.log`); a new attempt was admitted; crash buffer empty |
| What remains untouched | It fires **only inside `tryAcquire()`**, i.e. only when a *new* attempt asks. It cannot self-heal. It does not address the `CONNECTED`/`RESERVED` rows of B.2. It does not unblock the wedged native call — the call keeps running and keeps holding `g_openssl_lock` **[SOURCE CODE]** (`NativeLifetimeRegistry.kt:396-413`) |
| Class | Consequence-bound ✔ correct, but not a prevention |

#### F2 — CONNECTING soft-bound (`NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS`, 120 000 → 30 000)

| Aspect | Finding |
|---|---|
| What it addressed | A holder that entered `CONNECTING` and never returned, with **no** teardown request to measure from — the case the TEARDOWN bound structurally cannot cover |
| What it actually solved on device | **Not observed firing.** §10.1's negative baseline: during the ~13-minute device run neither `Kotlin attempt abandoned` nor `FORCED_RELEASE_CONNECTING_STUCK` appeared; the watcher ended `WATCHER2_TIMEOUT` **[PHYSICAL DEVICE]** (`_fix2_watch2_result.txt`) |
| What remains untouched | It is still `tryAcquire()`-only. It does not interrupt the wedged native call — the CONNECTING phase is *cleared* while the native handshake keeps holding `g_openssl_lock`, so a subsequent attempt can be admitted but will still be serialised behind that lock for any OpenSSL work **[INFERENCE]** |
| Class | Consequence-bound ✔ structurally necessary; **fires too late** on the installed build (120 s vs a 5 s budget) |

#### F3 — FIX-C (`abandonWedgedConnectingAttemptIfAny()`)

| Aspect | Finding |
|---|---|
| What it addressed | The **Kotlin** half of the CONNECTING leak: `connectionJob`/`controller`/`tunOwnership` left set so `decideStart` returned `REFUSE_CONNECTING` forever |
| What it actually solved on device | **Not observed firing** (same negative baseline as F2). Statically verified: it consumes the same constant as F2 **[SOURCE CODE]** (`SoftEtherVpnService.kt:587`), so FIX-1's lowering propagates with no second edit |
| What remains untouched | It deliberately does **not** dispatch a native destroy **[SOURCE CODE]** (`SoftEtherVpnService.kt:575-604`). So it clears the Kotlin gate and leaves the native gate set — B3-3. The user then sees `Start refused: native slot still held by an unresolved attempt (...)` instead of the previous message |
| Class | Necessary complement to F2; creates the B3-3 message swap |

#### F4 — FIX-1 (threshold 120 s → 30 s)

| Aspect | Finding |
|---|---|
| What it addressed | The bound was unreachable within one run: Auto Mode gives up ~19 s after a wedge, so a 120 s bound never fired |
| What it actually solved on device | **Not verified on device** — the phase was code-only (device commands explicitly forbidden). Statically verified: the constant is read by both the registry and FIX-C **[SOURCE CODE]** (`NativeLifetimeRegistry.kt:519`, `SoftEtherVpnService.kt:587`) |
| What remains untouched | 30 s is still > 5 s (this device's budget), so Auto Mode still gives up **before** the bound; the user must press Connect again to trigger the `tryAcquire()` evaluation. The bound remains reactive, never proactive |
| Class | Threshold tuning; correct direction, still not commensurate with the device's budget |

#### F5 — FIX-2 (`giveUpWithUnresolvedNativeOperation` issues a recovery teardown)

| Aspect | Finding |
|---|---|
| What it addressed | The core defect from E.2: Auto Mode's terminal give-up ended the *run* without ending the *session*, so nothing ever moved the slot to `TEARDOWN` and the verified 90 s bound had nothing to reclaim |
| What it actually solved on device | **Not installed** — DEX scan confirms `requestRecoveryTeardown` and the log string are ABSENT from the installed APK **[PHYSICAL DEVICE]** (§8.2) |
| What remains untouched | The teardown it issues is fire-and-forget with no waiter **[SOURCE CODE]** (`AndroidConnectionAdapter.kt:159-161`). It relies on the 90 s TEARDOWN bound to actually reclaim the slot — so a user who gives up and immediately retries still waits up to 90 s. It does not fix the native wedge |
| Class | **Highest-value Kotlin fix** — it is the only change that connects the Auto Mode give-up to the already-verified TEARDOWN bound |

#### F6 — FIX-3 (`WedgeTracker`, N = 2, process scope)

| Aspect | Finding |
|---|---|
| What it addressed | Repeatedly re-dialling a server that reliably wedges, burning the session's budget |
| What it actually solved on device | **Not installed** — `WedgeTracker` and the exclusion log string are ABSENT from the installed APK **[PHYSICAL DEVICE]** (§8.2) |
| What remains untouched | It records the wedge **at give-up**, not at the moment the wedge occurs, so a server that wedges but is never given up on is not recorded **[SOURCE CODE]** (`AutoModeController.kt:1672`). It is process-scoped, so it resets on every app start. It is a mitigation, not a fix |
| Class | Mitigation; correct as scoped, explicitly self-limiting |

### F.2 Structural commonality — one root cause or several?

**One root cause.** **[INFERENCE, grounded in the [SOURCE CODE] of §5]**

Every fix above is a **bound on a consequence** of the same event: `SSL_do_handshake` blocking on a socket that has no effective timeout, while holding two process-wide locks. The evidence for "one cause, not several":

- All observed stalls present at the **same** phase (`STATE_TLS_HANDSHAKE`) and the same missing log line (`SSL handshake successful`) **[PHYSICAL DEVICE]**.
- The downstream effects that look like independent bugs — the `TEARDOWN` leak, the `CONNECTING` leak, the `connectionJob` leak — are all **derivable** from that one block via the lock analysis in §1.3 and the flow analysis in E.2. There is no second mechanism required to explain them.
- Every Kotlin bound is of the form "give up after N ms", i.e. all of them are timeouts on *waiting*, none on *executing*. A stack of five such bounds is the signature of a single unbounded call, not of five defects.

### F.3 Prioritised fix list

**P1 — the single highest-impact fix (native, one change):**

> **Give the handshake socket a real timeout.** Either (a) apply `SO_RCVTIMEO`/`SO_SNDTIMEO` from `conn->timeout_ms` to `conn->socket_fd` immediately after the TCP connect succeeds and before `perform_tls_handshake`, or (b) convert `ssl_connect`'s `max_attempts` loop into a wall-clock deadline, or (c) make the handshake socket non-blocking so the existing `WANT_READ` loop and `max_attempts` cap become effective. The existing code at `softether_protocol.c:2643-2648` is the template for (a) — it already does exactly this for the data path.
> **Why P1:** it is the only change that removes the wedge rather than bounding it. It makes every downstream bound (30 s, 90 s, 5 s) a safety net instead of the primary defence, and it removes the cross-connection lock coupling in §1.3.

**P2 — bundleable with P1 (native, same subsystem):**

- **P2a** — publish the parallel-race transport fds so `softether_force_close_socket` can `shutdown()` them, and/or replace the unconditional `pthread_join` at `softether_protocol.c:2301-2303` with a bounded join plus a detach. Without this, a race-phase wedge remains uninterruptible (A6-1).
- **P2b** — add a bound to `resolve_hostname` at `:2410` (the only other unbounded pre-handshake call).

**P3 — Kotlin, must land with or after P1 (already implemented, unverified on device):**

- **P3a** — FIX-2 (§F5). This is the change that closes the structural gap in E.2. It should be the first Kotlin change verified once P1 lands.
- **P3b** — FIX-1 (§F4) and FIX-C (§F3). Both already written; FIX-C is statically coupled to FIX-1.
- **P3c** — reconcile the per-attempt budget with the CONNECTING bound. With `SETTING_AUTO_MODE_TIMEOUT_SECONDS = 5` on this device **[PHYSICAL DEVICE]**, a 30 s bound is still 6× the budget. Consider deriving the bound from the budget rather than hard-coding it, or documenting the deliberate gap.

**P4 — native-only, must be scheduled separately:**

- **P4a** — the `Phase.CONNECTED` and `Phase.RESERVED` coverage gaps (B2). Needs a design decision, not a constant change.
- **P4b** — the dead budget branch (C7-7): either raise `NATIVE_BUSY_MAX_RETRIES` or remove `PER_SERVER_RETRY_DELAY_BUDGET_MS`; leaving both is misleading.
- **P4c** — `NATIVE_TEARDOWN_TIMEOUT_MS = 500` (C7-8) is likely too short for a `nativeDestroy` that may join a receive thread. Needs measurement before change.

**P5 — diagnostics (low risk, high value):**

- **P5a** — log the `conn->timeout_ms` value and whether `SO_RCVTIMEO` was actually applied, at handshake entry. This turns D.4's discriminator into a single grep.
- **P5b** — log the race's terminal outcome unconditionally so A3-2 is distinguishable from A3-1 in one logcat capture.

---

## §8 — Part G: read-only device inspection

### G.1 — installed package identity

```
versionCode=143 minSdk=24 targetSdk=37
versionName=2.4.10-dev
lastUpdateTime=2026-10-13 15:28:30
  firstInstallTime=2026-09-28 16:05:05
  firstInstallTime=1970-01-01 03:30:00
package:/data/app/~~WuMJq8BaPFwAkQtAyXsgqw==/vn.unlimit.vpngatepro-cNDpNO5EA1N0_xPwd4ZgcA==/base.apk
```
**[PHYSICAL DEVICE]** — `adb shell dumpsys package vn.unlimit.vpngatepro`, `adb shell pm path vn.unlimit.vpngatepro`

**Interpretation [INFERENCE]:** `lastUpdateTime = 2026-10-13 15:28:30` matches the FIX-C device-verify install. The build therefore contains the CONNECTING soft-bound at its **original 120 000 ms** and FIX-C, and **not** FIX-1/2/3.

### G.2 — proof the installed APK does not contain FIX-1/2/3

Method: streamed the installed APK with `adb exec-out cat <path>` into a temp file outside the repository, opened it as a ZIP, concatenated all 34 `classes*.dex` entries (80 958 500 bytes), and searched the raw bytes for each needle. Positive controls were included so a negative result cannot be an artifact of the method.

| Needle | Class | Result |
|---|---|---|
| `Requesting recovery teardown so the unresolved native operation can be released` | **FIX-2** log string | **ABSENT** |
| `requestRecoveryTeardown` | **FIX-2** symbol | **ABSENT** |
| `from this session after` | **FIX-3** exclusion log string | **ABSENT** |
| `WedgeTracker` | **FIX-3** type name | **ABSENT** |
| `FORCED_RELEASE_CONNECTING_STUCK` | FIX-B / FIX-1 label | FOUND |
| `Kotlin attempt abandoned after` | FIX-C log string | FOUND |
| `Native slot busy; retrying same server in` | **positive control** (pre-existing) | FOUND |
| `[DIAG] nativeConnectWithHub entry` | **positive control** (PART-1 `[DIAG]`) | FOUND |
| `Start refused: native slot still held by an unresolved attempt` | **positive control** (pre-existing) | FOUND |
| `FORCED_RELEASE_AFTER_TIMEOUT` | **positive control** (pre-existing) | FOUND |

**Conclusion [PHYSICAL DEVICE]:** all four positive controls were found, so the search method works. The four FIX-2/FIX-3 needles are absent, so **the installed build contains neither FIX-2 nor FIX-3**. The two FIX-B/FIX-C needles are present, so the installed build is the FIX-C build. This independently corroborates §1.5 and the F5/F6 rows of §7.

### G.3 — installed preferences (the configuration the audit's numbers must be read against)

| Key | Value |
|---|---|
| `SETTING_AUTO_MODE_TIMEOUT_SECONDS` | `5` |
| `SETTING_AUTO_PROTOCOL_PRIORITY` | `SOFTETHER_TCP,SOFTETHER_UDP,MS_SSTP` |
| `SETTING_SOFTETHER_MAX_CONNECTIONS` | `8` |
| `LAST_CONNECT_METHOD` | `softether` |
| `LAST_CONNECT_SOFTETHER_USE_UDP` | `false` |
| `LAST_CONNECT_USE_UDP` | `false` |
| `AUTO_LAST_SUCCESS_PROTOCOL` | `SOFTETHER_TCP` |
| `AUTO_LAST_SUCCESS_HOST` | `vpn333205800` |
| `SETTING_DEVELOPER_MODE` | `true` |
| `SETTING_LOCALE` | `en` |
| `USER_ALLOWED_VPN` | `true` |

**[PHYSICAL DEVICE]** — `adb shell run-as vn.unlimit.vpngatepro cat /data/data/vn.unlimit.vpngatepro/shared_prefs/vpn_setting_data_pro.xml`

**Two findings [INFERENCE]:**
1. `SETTING_AUTO_MODE_TIMEOUT_SECONDS = 5` → `attemptTimeoutMs = 5 000` **[SOURCE CODE]** (`AutoModeEngine.kt:75`, `AndroidConnectionAdapter.kt:481`). Every "Auto Mode gives up in N seconds" statement in this report uses 5 s for this device, not the 25 s code default.
2. `SETTING_AUTO_PROTOCOL_PRIORITY` is **persisted on the device**, not supplied by code. This is consistent with the earlier FIX-A premise finding: `getAutoModeProtocolPriority()` returns empty and there is no default list in the codebase, so the priority list is user state. **The frozen spec (`PROJECT_SPECIFICATION.md` §6.7 R6 / §16.4 rule 4) is therefore not contradicted by anything observed here.**

### G.4 — device build properties

```
ro.build.fingerprint : Redmi/spesn_global/spesn:13/TKQ1.221114.001/V14.0.2.0.TGKMIXM:user/release-keys
ro.build.version.release / sdk : 13 / 33
ro.build.version.security_patch : 2023-05-01
ro.build.type : user
ro.build.tags : release-keys
ro.debuggable : 0
ro.product.model : 2201117TG
```
**[PHYSICAL DEVICE]** — `adb shell getprop`

**Relevance [INFERENCE]:** `ro.debuggable = 0` and `ro.build.type = user` mean `run-as`-style debugging is limited and native thread dumps are not obtainable through the usual `debuggerd` path — this is the mechanical reason §D.4 item 3 is currently **UNKNOWN**. It is not a code finding.

### G.5 — read-only discipline observed

All Part G commands were inspection-only: `dumpsys package`, `pm path`, `getprop`, `run-as … cat` on a preferences file, and `exec-out cat` on the APK. **No device setting was changed, nothing was installed or uninstalled, no data was cleared, and no instrumentation was listed or run.** One temporary file was created **outside the repository** (`/tmp/installed_base.apk`, i.e. `C:\Users\PMC\AppData\Local\Temp\installed_base.apk`) to hold the streamed APK for the DEX scan; it was deleted immediately after the scan (`rm -f`, verified absent). No repository file was created by Part G.

---

## §9 — Recommendations and suggested phase plan

Recommendations are ordered by impact and are **not** a proposal to start the next phase. Per the phase's POST-PASS rule, this section states options and waits for the operator to choose.

### 9.1 Ranked recommendations

| Rank | Recommendation | Layer | Risk | Rationale |
|---|---|---|---|---|
| **1** | Give the handshake socket a real timeout (apply `SO_RCVTIMEO`/`SO_SNDTIMEO` from `conn->timeout_ms` before `perform_tls_handshake`, and/or make the handshake socket non-blocking so `max_attempts` becomes effective, and/or convert `max_attempts` to a wall-clock deadline) | native | medium | Removes the root cause instead of bounding it. Template already exists at `softether_protocol.c:2643-2648` |
| **2** | Make the parallel-race transport fds reachable by `softether_force_close_socket`, and replace the unbounded `pthread_join` (`softether_protocol.c:2301-2303`) with a bounded join + detach | native | medium | Closes the one uninterruptible variant (A6-1) |
| **3** | Bound `resolve_hostname` (`softether_protocol.c:2410`) | native | low | The only other unbounded pre-handshake call |
| **4** | Ship + device-verify **FIX-2** | Kotlin | low | Already written and tested at unit level; closes the structural gap in E.2; connects the Auto Mode give-up to the already-verified 90 s TEARDOWN bound |
| **5** | Ship + device-verify **FIX-1** + **FIX-C** together | Kotlin | low | Already written; statically coupled through one constant; FIX-1 makes the bound reachable within a run |
| **6** | Reconcile `attemptTimeoutMs` with the CONNECTING bound (derive one from the other, or document the deliberate gap) | Kotlin | low | On this device 5 s vs 30 s still leaves Auto Mode giving up first |
| **7** | Add handshake-entry diagnostics: log `conn->timeout_ms` and whether `SO_RCVTIMEO` was applied | native | very low | Turns D.4's discriminator into a single grep; makes the next device run conclusive |
| **8** | Address the `CONNECTED` / `RESERVED` bound gaps | Kotlin + native | medium | Needs a design decision; not a constant change |
| **9** | Resolve the dead budget branch (C7-7) and re-examine `NATIVE_TEARDOWN_TIMEOUT_MS = 500` (C7-8) | Kotlin | low | Correctness of the safety net; measure before changing 500 ms |

### 9.2 Suggested phase plan (for operator selection, not initiated)

- **Option A — root cause first.** One native phase implementing recommendation 1 (and optionally 2/3/7), then a device-verification phase with the logcat discriminator from D.4. This is the only option that can *eliminate* the wedge. It carries the highest change risk because it touches the TLS transport, and it cannot be validated by unit tests alone.
- **Option B — verification first, root cause second.** Ship the already-written Kotlin bundle (recommendations 4 + 5) and verify it on device, which would demonstrate that the give-up → teardown → 90 s bound chain works end to end. This produces a measurable user-visible improvement while the native work is scheduled separately. It does **not** remove the wedge.
- **Option C — diagnostics first.** Implement recommendation 7 only, run one device session, and settle D.3's ranking (mechanism 1 vs mechanism 2) with evidence before touching any transport code. Lowest risk, slowest to a fix.
- **Option D — a combination.** Diagnostics (7) + the Kotlin bundle (4, 5) in one phase, then the native fix (1, 2, 3) in a following phase informed by the new diagnostics.

**Trade-off summary [INFERENCE]:** A is the only option that fixes the cause; B is the fastest route to a user-visible improvement; C is the only option that makes the native work evidence-driven rather than inferred; D costs one extra phase but removes the main risk from A.

### 9.3 Carried-forward open item for operator decision

The previous phase added a third log line to `giveUpWithUnresolvedNativeOperation` (`AutoModeController.kt:1667-1670`). The operator did not specify it. It is **kept** in the current working tree. This audit takes no position on whether it should remain; it is flagged here only so the decision is not lost. **[SOURCE CODE]** — `AutoModeController.kt:1663-1679`.

---

## §10 — Confirmation of read-only conduct

I confirm, for PHASE: AUDIT-AUTOCONNECT-LOGIC:

1. **NO source file was modified.** The seven tracked files reported by `git status --porcelain` are exactly the ones left by the preceding FIX-CONNECTING-THRESHOLD-AND-AUTOMODE-RETRY phase, and they were **not touched in this phase**:
   - `SoftEtherClient/src/main/java/vn/unlimit/softether/NativeLifetimeRegistry.kt`
   - `SoftEtherClient/src/main/java/vn/unlimit/softether/SoftEtherVpnService.kt`
   - `SoftEtherClient/src/main/java/vn/unlimit/softether/controller/ConnectionController.kt`
   - `SoftEtherClient/src/test/java/vn/unlimit/softether/NativeLifetimeRegistryTest.kt`
   - `app/src/main/java/vn/unlimit/vpngate/automode/AndroidConnectionAdapter.kt`
   - `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt`
   - `app/src/test/java/vn/unlimit/vpngate/automode/AutoModeNativeBusyTest.kt`
2. **NO test file was modified**, and **no build file** (Gradle, CMake, manifest, or any other) was modified.
3. **NO build was run.** No Gradle invocation of any kind was issued.
4. **NO test was run.** No unit test, instrumentation test, or Gradle test task was issued.
5. **NO install or uninstall was performed**, and **no app data was cleared**.
6. **NO device setting was changed.** Every device command was read-only: `adb devices`, `dumpsys package`, `pm path`, `getprop`, `run-as … cat` (a preferences file), and `exec-out cat` (the installed APK). No `settings put`, no `pm grant/revoke`, no instrumentation was listed or run.
7. **NO commit, push, or tag was performed.** `HEAD` and `origin/main` are both `fa8a53d20d4bc8ba1e233d473c1c7723cbadb71f` and `git rev-list --left-right --count origin/main...HEAD` is `0	0`.
8. **Only ONE file was created in the repository:** `AUDIT_AUTOCONNECT_LOGIC.md` (this file). No other repository file was created, renamed, or deleted.
9. **One temporary file was created outside the repository** to perform the Part G DEX scan — `/tmp/installed_base.apk` (`C:\Users\PMC\AppData\Local\Temp\installed_base.apk`) — and it was **deleted immediately** after the scan, with the deletion verified (`ls` reported "No such file or directory").
10. **No code-change proposal was made outside §9**, and §9 is explicitly framed as options for the operator to select, not as an authorized change.
11. **No next phase was proposed autonomously.** Per the phase's POST-PASS rule, this report is handed back for the operator's decision on which recommendations to prioritise.

### Evidence base used in this phase (all read-only)

- **Source read:** `NativeLifetimeRegistry.kt`, `SoftEtherVpnService.kt`, `SoftEtherSessionPolicy.kt`, `ConnectionController.kt`, `NativeAttemptCoordinator.kt`, `AutoModeController.kt`, `AndroidConnectionAdapter.kt`, `AutoModeEngine.kt`, `TunnelStateWatcher.kt`, `DetailActivity.kt`, `MainActivity.kt`, `SoftEtherClient.kt`
- **Native read:** `softether_protocol.c`, `tcp_socket.c`, `aes_wrapper.c`, `softether_jni.c`, `softether_socket.h`
- **Tests read:** `NativeLifetimeRegistryTest.kt`, `AutoModeNativeBusyTest.kt`
- **Device read:** package identity, installed preferences, build properties, installed APK DEX (see §8)
- **Prior evidence artifacts cited:** `_t5_part2_run.log`, `_fix2_watch2_result.txt`, `_t5p2_settings.xml`, `_fix2_device.log`

*End of audit.*
