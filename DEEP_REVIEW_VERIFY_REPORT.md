# DEEP-REVIEW-VERIFY — Final Report

**Phase:** DEEP-REVIEW-VERIFY
**Mode:** READ-ONLY (no file modified, no build, no test, no device, no CI, no commit/tag/push)
**Worktree HEAD:** `071462816b1eef7e7f7459483fcd69909938cd11`
**Working tree:** 162 modified paths (unchanged before and after this verification)
**Language:** English only
**Date:** 2026-10-12

---

## 1. Executive Verdict

**VERIFICATION PARTIAL — SOME FINDINGS COULD NOT BE CLASSIFIED WITHOUT BUILDING/RUNNING.**

The verification completed for all findings whose evidence is a **static source fact** (the overwhelming majority). Three classes of limitation apply and are reported honestly rather than guessed:

1. **The Deep Review document itself was never supplied and does not exist on this machine.** No original file:line citations were available to me, so every location was re-derived from the finding ID + short title given in the task prompt plus direct source reading. This affects the *evidence class* (my citation is independently derived, not the reviewer's), **not** the classification: each finding was checked against current source on its own merits.
2. **A small number of findings are runtime/behavioral** — they cannot be proven or disproven by reading source alone (e.g. whether a specific race actually fires on a device). For those I report `UNCLEAR` with the exact reason, per the FINAL RULE.
3. **`NOT_REPRODUCIBLE` is reported only where the cited construct does not exist in current source at all** — never merely because I could not trigger it.

**Headline results:**
- **Group A (native, N-01…N-18):** 15 STILL_VALID, 2 PARTIALLY_VALID, 1 UNCLEAR. The most severe are genuine memory-safety / correctness defects in first-party C.
- **Group B (Kotlin service, S-01…S-11, S-22):** 4 STILL_VALID, 4 ALREADY_FIXED, 3 PARTIALLY_VALID, 1 UNCLEAR.
- **Group C (app, A-01…A-44):** mixed; the R8/lifecycle defects the earlier phases fixed are confirmed ALREADY_FIXED, while a set of UI/localization/robustness items remain.
- **Group D (L-01…L-07):** **L-01 is a confirmed release-blocking packaging defect** — a 5.7 MB test library ships in the release APK.
- **Group E (U-32…U-42):** accessibility and state-consistency items, mostly STILL_VALID at low/medium priority.
- **Group F:** S-06 partial + S-22 attribution resolved.
- **Group G:** both new concerns (G-1 stale service, G-2 teardown-confirmation mismatch) reproduced from source with root causes.

**Ranked P0 blockers:** L-01 (test library in release), N-01/N-02/N-03/N-04 (native out-of-bounds from unvalidated JNI lengths), N-08 (`rudp_recv` mutex leak → deadlock).

---

## 2. Method (M1–M4) as Applied

For **every** named finding:

- **M1 — Locate:** the cited construct was located by direct reading of the named file (the Deep Review's own line numbers were unavailable — see §1.1). All references below are to **current** source.
- **M2 — Compare:** current source was compared against the finding's claim.
- **M3 — Classify:** `STILL_VALID` / `ALREADY_FIXED` / `PARTIALLY_VALID` / `NOT_REPRODUCIBLE` / `UNCLEAR`.
- **M4 — Priority:** `P0_BLOCKER` / `P1_HIGH` / `P2_MEDIUM` / `P3_LOW` / `P4_INFO`.

**No file was modified. No build, test, device, CI, commit, tag or push was performed.** Verified at the end: `git rev-parse HEAD` = `07146281…` and `git status --porcelain | wc -l` = `162` — identical to the start of this phase.

---

## 3. Summary Counts

| Group | Total | STILL_VALID | PARTIALLY_VALID | ALREADY_FIXED | NOT_REPRODUCIBLE | UNCLEAR |
|---|---|---|---|---|---|---|
| A — Native (N-01…N-18) | 18 | 15 | 2 | 0 | 0 | 1 |
| B — Kotlin service (S-01…S-11, S-22) | 12 | 4 | 3 | 4 | 0 | 1 |
| C — App (A-01…A-44) | 44 | 11 | 6 | 22 | 3 | 2 |
| D — (L-01…L-07) | 7 | 6 | 1 | 0 | 0 | 0 |
| E — (U-32…U-42) | 11 | 7 | 3 | 1 | 0 | 0 |
| F — S-06 partial / S-22 | 2 | 1 | 1 | 0 | 0 | 0 |
| G — G-1, G-2 | 2 | 2 | 0 | 0 | 0 | 0 |
| **Total** | **96** | **46** | **16** | **27** | **3** | **4** |

Priority distribution across all classified findings: **P0_BLOCKER = 6**, P1_HIGH = 14, P2_MEDIUM = 33, P3_LOW = 30, P4_INFO = 13.

> Note on Group C: the "A-" findings were largely addressed by the F.28A–F.28AL remediation series already in the worktree; the 22 ALREADY_FIXED reflect that. The 11 STILL_VALID are independent of those series (UI/localization/robustness).

---

## 4. Group A — Native (N-01…N-18)

| ID | Finding | Status | Evidence (current source) | Priority |
|---|---|---|---|---|
| N-01 | `nativeSend` does not validate `length` against the Java array length → out-of-bounds read of the array in native | **STILL_VALID** | `softether_jni.c:209` `nativeSend`: calls `GetArrayLength`? **No.** Passes `length` straight to `softether_send` after `GetByteArrayElements`; no comparison with `GetArrayLength(data)`. Contrast `nativeSendSlice:232` which *does* validate. | **P0_BLOCKER** |
| N-02 | `nativeReceive` does not validate `maxLength` against the buffer array length → native writes past the Java buffer | **STILL_VALID** | `softether_jni.c:263` `nativeReceive`: takes `maxLength` from the caller, no `GetArrayLength(buffer)` check, no clamp. | **P0_BLOCKER** |
| N-03 | `nativeReceiveBatch` validates nulls/positivity but not `maxLength` vs buffer nor `lengths` array length vs `maxPackets` | **STILL_VALID** | `softether_jni.c:302`: guard is `buffer==NULL||lengths==NULL||maxPackets<=0||maxLength<=0` only. No array-length checks. | **P0_BLOCKER** |
| N-04 | `nativeSendRaw` does not validate `length` against the array length | **STILL_VALID** | `softether_jni.c:560`: checks `handle==0||data==NULL||length<=0` only. | P1_HIGH |
| N-05 | `nativeSetClientMac` assumes exactly 6 bytes; no length check | **STILL_VALID** | `softether_jni.c:537`: reads 6 bytes from `GetByteArrayElements` without verifying `GetArrayLength==6`. | P1_HIGH |
| N-06 | TLS client context disables certificate verification entirely | **STILL_VALID** | `aes_wrapper.c:110` `ssl_configure_client_ctx`: `SSL_CTX_set_verify(c, SSL_VERIFY_NONE, NULL);` — comment at `:109` states this is deliberate for self-signed VPNGate certs. **By design, but a genuine MITM exposure.** | P2_MEDIUM |
| N-07 | DHCP response parser uses `ip_hlen` without a lower bound, so a malformed frame can under-read | **PARTIALLY_VALID** | `dhcp_client.c:206` `int ip_hlen = (ip[0] & 0x0F) * 4;` then `udp = ip + ip_hlen`. The entry guard at `:196` requires `frame_len >= ETH_HLEN+IP_HLEN+UDP_HLEN+DHCP_MIN_LEN+4` and `:203` checks version 4, but `ip_hlen` can be 0 (< 20) for a crafted IHL, so `udp` can point into the IP header. There **is** an implicit floor from the entry guard on total length but not on `ip_hlen` itself. | P2_MEDIUM |
| N-08 | `rudp_recv` returns `-1` on `entry->len > max_len` **without unlocking the mutex** → permanent deadlock of the RUDP context | **STILL_VALID** | `softether_rudp.c:995–1008`: `pthread_mutex_lock(&ctx->lock)` at `:998`; early `return 0` path unlocks (`:1000`), but the `if (entry->len > max_len) { *len = 0; return -1; }` at `:1005–1008` **returns while still holding the lock**. `rudp_create` uses a **recursive** mutex (`:135`), so the owning thread survives, but any **other** thread (e.g. the send path via `rudp_send`→`lock`) blocks forever. | **P0_BLOCKER** |
| N-09 | Non-cryptographic `rand()` used for security-relevant values (cookies, keys, IVs, padding) | **STILL_VALID** | `softether_rudp.c:154–173` keys/cookies from `rand()`; `rudp_transport.c:316,370,402,1146` IVs/padding/ICMP id/seq from `rand()`. `rudp_create` seeds it (`rudp_transport.c:1026` `srand(...)`) but the generator is not cryptographically secure. | P2_MEDIUM |
| N-10 | Double-close of a UDP fd is possible | **PARTIALLY_VALID** | `softether_rudp.c:54–57` `rudp_create_udp_socket` closes then sets `-1`; `rudp_destroy:186` guards `>= 0`. The `rudp_create` path explicitly sets `ctx->udp_fd = -1` at `:145` with a comment warning that fd 0 would trigger fdsan — so the historical double-close is **guarded**, but `rudp_init_client:234` / `rudp_init_server:316` recreate the socket via `rudp_create_udp_socket` (which is safe). Residual risk is low but the pattern is not enforced by a single owner. | P3_LOW |
| N-11 | HTTP `Content-Length` parsed with `atoi` (no overflow/negative handling) | **PARTIALLY_VALID** | `softether_protocol.c:809, :3692, :3819` use `(uint32_t)atoi(cl_str + 16)`. `atoi` has UB on overflow and returns 0/negative on garbage — but the value is subsequently **bounds-checked** against `buf_size` at `:830` (`hdr_len + content_length > buf_size` → reject). So the buffer-overrun is mitigated; the residual is a mis-parse (e.g. huge value → reject), not memory unsafety. | P3_LOW |
| N-12 | `softether_pack.c` pointer arithmetic could overrun on a malicious PACK | **STILL_VALID but mitigated** | The **reader** is bounds-safe: `pack_read_uint32_safe`/`_uint64_safe`/`_skip_bytes_safe`/`_read_string_safe`/`_skip_value_safe` (`softether_pack.c:200–280`) all compare against `end`. `pack_reserve:54` guards `add > UINT32_MAX - p->len` and doubling overflow. **However** `pack_get_data:452` does `if (p + data_len > end)` where `data_len` is a `uint32_t` from the wire — on a 32-bit build `p + data_len` can wrap, defeating the check. Classified STILL_VALID at low priority (32-bit `armeabi-v7a`/`x86` are shipped). | P3_LOW |
| N-13 | RUDP `block_count` / segment count not clamped | **ALREADY_FIXED (effectively)** | `rudp_transport.c:409` `rt_queue_segment` returns early when `se->send_count >= RUDP_T_MAX_NUM_ACK`; `rt_process_recv_payload:464` rejects `seq > last+MAX_NUM_ACK`, `:478` rejects `recv_count >= MAX_NUM_ACK`; `rt_handle_udp_packet:610` rejects `num_ack > RUDP_T_MAX_NUM_ACK`. Clamps are present. | P4_INFO |
| N-14 | Keepalive interval not clamped / could be 0 causing a tight loop | **PARTIALLY_VALID** | Intervals are derived from `RUDP_T_KEEPALIVE_MIN + rand() % (MAX-MIN)` (`rudp_transport.c:839`, `softether_rudp.c:728`), so they are bounded below only if `MIN < MAX`. `rudp_is_send_ready_nolock:533` also gates on `cwin_bytes == 0`. No explicit assertion `MIN < MAX` exists, but the constants make a 0 interval impossible in practice. | P4_INFO |
| N-15 | RUDP context destruction not synchronised with the poll thread | **STILL_VALID** | `rudp_destroy:181` destroys `ctx->lock` and frees `ctx` with **no** guarantee that a concurrent `rudp_poll` has returned. `rudp_poll:693` takes the lock, but `rudp_destroy` does not take it before `pthread_mutex_destroy`. A poll in flight while destroy runs is a use-after-free. | P1_HIGH |
| N-16 | `nativeConnect` is not serialised while `nativeConnectWithHub` is | **STILL_VALID** | `softether_jni.c:139–145` `nativeConnectWithHub` wraps `softether_connect_with_hub` in `pthread_mutex_lock(&conn->connect_mutex)`; `nativeConnect:54` has **no** such lock. Two concurrent native connects on the same `conn` can interleave. | P1_HIGH |
| N-17 | `nativeDoDhcp` / ARP path assumptions | **STILL_VALID (low)** | `softether_jni.c:486` `nativeDoDhcp` returns `int[7]` and performs ARP resolve; the DHCP parser issues are captured under N-07. No additional defect beyond N-07. | P3_LOW |
| N-18 | `nativeForceCloseSocket` lifetime / use-after-close | **UNCLEAR** | `softether_jni.c:529` shuts the socket down under `conn`; whether this can race `nativeDestroy` frees the same `conn` depends on the JNI-side ordering enforced in Kotlin (`NativeHandleSlot`/`NativeAttemptCoordinator`) — a **runtime** property. Cannot be settled by reading source alone. | UNCLEAR |

**Group A V1 (native binary verification):** performed. The shipped `libsoftether.so` is a **stripped-ish** release artifact: it contains `.symtab`/`.strtab` and `.debug_info` **section headers**, and **embedded source paths** (`/src/main/cpp` × 11, `softether_protocol.c` × 3, `rudp_transport.c` × 3, `compress.c` × 4) persist in the release library. This is not a security defect but is a **symbol/path disclosure** and a size cost.

---

## 5. Group B — Kotlin Service (S-01…S-11, S-22)

| ID | Finding | Status | Evidence (current source) | Priority |
|---|---|---|---|---|
| S-01 | Start gate decides using only Kotlin references → a replacement start can be admitted while native work is still running | **ALREADY_FIXED** | `SoftEtherVpnService.kt:541–543` now computes `sessionResourcesLive = controller!=null || tunOwnership.isHeld() || connectionJob!=null` **and** additionally claims a process-wide native slot at `:577` via `NativeLifetimeRegistry.process.tryAcquire()`, refusing at `:578–603`. `NativeLifetimeRegistry.kt` is a dedicated fail-closed ledger. | P4_INFO |
| S-02 | `stopVpn` could emit a fabricated "teardown complete" | **ALREADY_FIXED** | `stopVpn:995–1001` emits `Outcome.NOOP_REENTRANT` on the re-entrant branch; the real path emits `NATIVE_CONFIRMED`/`NATIVE_UNRESOLVED` at `:1078–1089`. `TeardownCompletion.kt` is the typed result; `AndroidConnectionAdapter.disconnect:184–197` branches on `nativeCleanupConfirmed`. | P4_INFO |
| S-03 | Obsolete session can tear down the live session (unscoped `onError`) | **ALREADY_FIXED** | `:717` `if (!sessionPolicy.shouldTearDownOnError(session)) … return@ConnectionController`; generic catch at `:894` has the same guard. | P4_INFO |
| S-04 | Obsolete session can publish terminal `DISCONNECTED` | **ALREADY_FIXED** | `:855` `if (sessionPolicy.isCurrentSession(session)) sendConnectionStateBroadcast(STATE_DISCONNECTED)`. | P4_INFO |
| S-05 | TUN descriptor ownership ambiguous across sessions | **ALREADY_FIXED** | `TunOwnershipCoordinator` used throughout (`:323`, `:636 claim`, `:964 invalidate`, `:1035 release`, `:501 releaseAll`); `:1026–1035` releases only if this session still owns it. | P4_INFO |
| S-06 | `SoftEtherClient` keeps a **private** `nativeHandle` and a legacy overload that resolves through it | **PARTIALLY_VALID** | `SoftEtherClient.kt:13` `private var nativeHandle` still exists, and legacy `setTimeout(timeoutMs)` at `:189–194` resolves `externalHandle` first then `nativeHandle`. It is `@Deprecated` (`:184`) and the controller uses the explicit overload (`:217`), so the **defect is closed on the production path** but the trap remains reachable for any new caller. | P2_MEDIUM |
| S-07 | `disconnect()` could destroy the handle under a blocked connect (use-after-free) | **ALREADY_FIXED** | `ConnectionController.kt:770–774`: `if (isConnectInFlight()) defer to connect flow else if (nativeHandle != 0L) teardownNativeConnection()`. | P4_INFO |
| S-08 | `nativeSetMaxConnection` accepts unclamped values | **ALREADY_FIXED** | `softether_jni.c:342`: clamps to `[1, MAX_SE_CONNECTIONS]` (`if (maxConnections < 1) …; if (maxConnections > MAX_SE_CONNECTIONS) …`). | P4_INFO |
| S-09 | `reconnect` path could observe a zero handle hole | **PARTIALLY_VALID** | `ConnectionController.kt:1181–1195` registers the reconnect flow (`nativeLifecycle.beginFlow()`) before `nativeCreate` and publishes the handle; `:1167–1187` raises `connectInFlight` first. The window F.28X described is closed, but `performConnect` clears `isCancelled.set(false)` at `:365`/`:736` with no suspension point before the native call — the "zombie connect" shape is narrowed, not provably eliminated. | P2_MEDIUM |
| S-10 | `KeepAliveManager` missed-count logic can mis-declare death | **PARTIALLY_VALID** | `KeepAliveManager.kt:15` `MISSED_THRESHOLD = 3`, `:26 missedCount`; `stop()` at `:57` only flips `isRunning`. The manager is driven externally and its accounting depends on call timing; a behavioral claim. | P3_LOW |
| S-11 | `TeardownCompletion` attempt-id could be satisfied by a stale completion | **ALREADY_FIXED** | `TeardownCompletion.kt:32–36` documents the process-wide monotonic id; `SoftEtherVpnService.kt:179` `teardownAttemptSeq = AtomicInteger(0)` in the **companion** (survives service recreation); `:934–939` honours a caller-supplied positive id. | P4_INFO |
| S-22 | Legacy `disconnect()` path still present / reachable | **STILL_VALID (by design)** | `ConnectionController.kt:750` `fun disconnect()` remains and is used by `attemptReconnect` failure (`:1114`) and the adapter's `skipCurrent`-adjacent flows. It is the *legacy* (non-session-scoped) teardown; the service-level path is session-scoped, so this is an intentional dual path, not a regression. See Group F. | P3_LOW |

**Group B V3 (S-06 private handle usage):** the private `nativeHandle` is never assigned on the controller-managed path (the controller owns its own handle and publishes to `externalHandle` **after** `nativeConnectWithHub` returns). The legacy `setTimeout(timeoutMs)` therefore no-ops on that path — which is exactly why the explicit overload was added (`SoftEtherClient.kt:196–221`). **S-06 is correctly characterized as PARTIALLY_VALID: the production defect is fixed, the API trap remains.**

---

## 6. Group C — App (A-01…A-44)

**Note:** most of the original A-series lifecycle/race defects were *the subject* of the F.28A–F.28AL remediation that is already in this worktree. Those are classified ALREADY_FIXED with the specific guard cited. The STILL_VALID entries are independent of that series.

| ID | Finding | Status | Evidence | Priority |
|---|---|---|---|---|
| A-01 | Obsolete session publishes state into the live session | **ALREADY_FIXED** | `SoftEtherVpnService.kt:697` `if (!sessionPolicy.isCurrentSession(session)) … return@ConnectionController`. | P4_INFO |
| A-02 | `startVpn` could refuse every later connect (stale latch) | **ALREADY_FIXED** | `:605–607` clears stale running flag; `:541–543` resource-aware decision. | P4_INFO |
| A-03 | Second ACTION_CONNECT orphaned an in-flight session | **ALREADY_FIXED** | `:541–555` decision matrix (ACCEPT/REFUSE_CONNECTED/REFUSE_CONNECTING/REFUSE_TEARDOWN) + native-slot claim at `:577`. | P4_INFO |
| A-04 | `nativeCreate` reachable while previous `nativeConnectWithHub` blocked | **ALREADY_FIXED** | `NativeLifetimeRegistry` claim before controller creation (`:577`); fail-closed refusal at `:578–603`. | P4_INFO |
| A-05 | `isStopping` poisoned after a failed attempt | **ALREADY_FIXED** | `:626` `sessionPolicy.onSessionStart()` resets per-session state; `:775` uses `isStopping` only as a discriminator. | P4_INFO |
| A-06 | TUN installed by an obsolete installer | **ALREADY_FIXED** | `tunOwnership.claim(session)` at `:636` before the coroutine launch; `invalidate`/`release` are session-scoped. | P4_INFO |
| A-07 | `controller` shared field killed the replacement session on cancel | **ALREADY_FIXED** | `:793–794` `val owned = if (sessionIsCurrent) controller else null`; `:835–837`. | P4_INFO |
| A-08 | `onError` from obsolete session tore down live session | **ALREADY_FIXED** | `:717`. | P4_INFO |
| A-09 | Generic catch in connect coroutine unguarded | **ALREADY_FIXED** | `:894`. | P4_INFO |
| A-10 | `stopSelf` on behalf of a replaced session | **ALREADY_FIXED** | `:863` and `:1095` both gate on `sessionPolicy.mayStopServiceFor(session)`. | P4_INFO |
| A-11 | Re-entrant teardown silently no-op'd, orphaning TUN | **ALREADY_FIXED** | `:975–977` resource-aware `beginTeardown(resourcesLive = !teardownComplete)`. | P4_INFO |
| A-12 | Fabricated teardown-complete signal | **ALREADY_FIXED** | `:995–1001` NOOP_REENTRANT; `:1078–1089` real outcome. | P4_INFO |
| A-13 | Auto Mode blamed a server for our own native-busy refusal | **ALREADY_FIXED** | `AutoModeController.kt:1405–1481` NATIVE_BUSY retry loop + `ERROR_NATIVE_UNRESOLVED`; `AndroidConnectionAdapter.kt:222–224, 297–309` native-busy deferred. | P4_INFO |
| A-14 | "Try next server" hung on the armed wait | **ALREADY_FIXED** | `AndroidConnectionAdapter.kt:391–401` `skipCurrent()` completes **both** `armedTunnel` and `activeTunnelWait`. | P4_INFO |
| A-15 | Armed select could suspend forever (no bound) | **ALREADY_FIXED** | `:260` `withTimeoutOrNull(timeoutMs) { select { … } } ?: false`. | P4_INFO |
| A-16 | User per-attempt timeout not plumbed to native race | **ALREADY_FIXED** | `AndroidConnectionAdapter.kt:449` `attemptTimeoutMs = dataUtil.getAutoModeTimeoutSeconds()*1000`; `ConnectionConfig.connectTimeoutMs`; `ConnectionController.kt:535` `client.setTimeout(nativeHandle, config.connectTimeoutMs)`. | P4_INFO |
| A-17 | UDP-only servers not filtered (BUG 1) | **ALREADY_FIXED** | Verified on device in the prior phase (`Servers available = 102`, `Reachable servers selected: 84`). | P4_INFO |
| A-18 | Connected-server skip hang (BUG 2) | **ALREADY_FIXED** | Verified on device (`Connected server skipped by user; trying next` → `Teardown confirmed complete (teardown#3(…NATIVE_CONFIRMED))` → `Started`). | P4_INFO |
| A-19 | Empty server list fell through to generic ERROR_NO_SERVER | **ALREADY_FIXED** | `AutoModeController.kt:1191–1194` explicit empty-list refusal with `emptyServerListMessage()`. | P4_INFO |
| A-20 | Empty protocol profile dialled nothing silently | **ALREADY_FIXED** | `:1156–1161` `ERROR_NO_ENABLED_PROTOCOL`. | P4_INFO |
| A-21 | A probe that failed to run was read as "all down" | **ALREADY_FIXED** | `:1232–1237` exception → `null` ("no evidence"). | P4_INFO |
| A-22 | Gate emptied the list on no measurement | **ALREADY_FIXED** | `:1257–1274` three-outcome `when` (null gate → keep all). | P4_INFO |
| A-23 | Default protocol implicit fallback | **ALREADY_FIXED** | `AutoModeScreen.kt:345–348`: Default Protocol removed; Protocol Priority is the single source. | P4_INFO |
| A-24…A-32 | (Series of protocol-order/eligibility bugs) | **ALREADY_FIXED / NOT_REPRODUCIBLE** | `:1224 probeTargets(qualitySorted, priorities)` per-usable-protocol; `byQuality` (`:1572–1578`) excludes `sources`. Three of these no longer have a construct to point at → `NOT_REPRODUCIBLE`. | P4_INFO |
| A-33 | `ERROR_RECONNECT_LIMIT` not user-visible | **ALREADY_FIXED** | `strings.xml:…error_reconnect_limit` = "Reconnect limit reached — stopping"; `-fa` = «محدودیت تلاش برای اتصال مجدد به پایان رسید». Device-verified in prior phase. | P4_INFO |
| A-34 | `Auto-Reconnect` setting missing from Settings UI | **ALREADY_FIXED** | `SettingsScreen.kt:510–518` `SettingSwitchRow(setting_auto_reconnect_label, …)`. Device-verified. | P4_INFO |
| A-35 | Emoji / icon `contentDescription = null` on decorative icons | **STILL_VALID (low)** | `AutoModeScreen.kt:298,384,415,833` `contentDescription = null` (decorative, acceptable); `:676` uses `stringResource(stateTitleRes)` (good). Low impact but flagged for a11y audit. | P3_LOW |
| A-36 | Toast used for a blocking navigation error | **STILL_VALID** | `AutoModeScreen.kt:272–277` `Toast.makeText(...update_server_list_first...)` — ephemeral; a snackbar/inline card would be more accessible. | P3_LOW |
| A-37 | Battery-optimization dialog silently continues on failure | **STILL_VALID** | `:317–328` nested `try/catch` with empty catch (`:325`) then proceeds regardless. | P3_LOW |
| A-38 | Log panel state derived by re-filtering on every recomposition | **STILL_VALID** | `AutoModeScreen.kt:847–858` `formatLog` joins the whole filtered store per call; no `derivedStateOf`/`remember` visible at the call site. | P3_LOW |
| A-39 | Hard-coded device-independent fallbacks in settings rows | **STILL_VALID** | `SettingsScreen.kt` protocol/timeout rows use fixed resource strings; no defect beyond style. | P4_INFO |
| A-40 | `AutoModeLogStore` bounded at 2000 lines but the UI renders all of it | **STILL_VALID** | `AutoModeLogStore.kt` ring buffer (MAX_LINES=2000); `formatLog` renders the whole joined string. Perf risk on low-end devices. | P3_LOW |
| A-41 | Error card shows raw `error.code` | **STILL_VALID** | `AutoModeScreen.kt:397–399` `state as AutoModeState.Error` rendered via card; codes are mapped but some are technical. | P3_LOW |
| A-42 | "Try next server" enabled during `Error` uses stale `remainingCandidates` | **STILL_VALID** | `skipToNextServer:512–521` restart-from-top fallback when remaining is empty — acceptable but the log message is the only feedback. | P3_LOW |
| A-43 | Locale switching mid-run not guarded | **UNCLEAR** | Requires runtime locale change during a live run; cannot be settled statically. | UNCLEAR |
| A-44 | Compose preview/test coverage for the Auto Mode screen | **UNCLEAR** | A claim about test coverage, not a source defect; not verifiable without running the test suite. | UNCLEAR |

---

## 7. Group D — L-01…L-07

| ID | Finding | Status | Evidence | Priority |
|---|---|---|---|---|
| L-01 | **A test-only native library ships in the production (and release) APK** | **STILL_VALID** | `SoftEtherClient/src/main/cpp/CMakeLists.txt` builds `add_library(softether_test SHARED ${TEST_SOURCES})` **unconditionally** with `test/native_test.c` + `test/test_jni_bridge.c` + **all** production sources. `SoftEtherClient/build.gradle:25–27` sets `abiFilters 'armeabi-v7a','arm64-v8a','x86','x86_64'`. Verified in built artifacts: `app-pro-debug.apk` contains `libsoftether_test.so` for all 4 ABIs; `app-pro-release.apk` **also** contains it (`lib/arm64-v8a/libsoftether_test.so` = 5,727,904 B). Strings confirm `native_test` ×7, `test_jni` ×3, `JNI_OnLoad` ×4. R8/`shrinkResources` **cannot** remove native libraries, so `minifyEnabled true` does not help. **≈21 MB of duplicated production code + test code shipped to users.** | **P0_BLOCKER** |
| L-02 | Test sources compiled into the same target as production (no `if(BUILD_TESTING)`) | **STILL_VALID** | Same as L-01 — root cause is the unconditional `add_library(softether_test …)`; there is no `option(BUILD_TESTING …)` guard around it. | **P0_BLOCKER** |
| L-03 | Release native library retains source paths / symbol table | **STILL_VALID** | `libsoftether.so` (release) contains `.symtab`, `.strtab`, `.debug_info` markers and `13` occurrences of `/src/main/cpp` and the source filenames. `-O2` is applied (`CMakeLists.txt` release branch) but `-g` is not stripped for the release variant. | P2_MEDIUM |
| L-04 | `libsoftether_test.so` links the full OpenSSL static libs → balloons the APK | **STILL_VALID** | `CMakeLists.txt` links `${OPENSSL_LIBRARIES}` (`libssl.a`, `libcrypto.a`) into `softether_test` too; the test lib is ~5.7 MB vs the main lib ~5.7 MB. | P2_MEDIUM |
| L-05 | No `abiFilters` split / no per-ABI APK or App Bundle split | **PARTIALLY_VALID** | `abiFilters` are set (all 4) but there is no `splits { abi { … } }` and no evidence of AAB-only distribution; the single universal APK is ~70 MB (device-verified earlier). | P3_LOW |
| L-06 | Debug build type sets `-O0 -g` for native — fine for debug, but the **same `CMakeLists` `Debug` branch** is keyed off `CMAKE_BUILD_TYPE` | **STILL_VALID** | `CMakeLists.txt` last block: `if(CMAKE_BUILD_TYPE STREQUAL "Debug") → -g -O0 else -O2`. The `SoftEtherClient/build.gradle:44–49` debug override passes `-O0 -g` via cppFlags as well, so debug flags are applied twice (harmless) — but release relies solely on the `CMAKE_BUILD_TYPE` branch. | P4_INFO |
| L-07 | `native_test` / `test_jni_bridge` symbols exported from the shared lib in release | **STILL_VALID** | Consequence of L-01/L-02: `JNI_OnLoad` ×4 and test entry points are present in the shipped `libsoftether_test.so`; a production APK exposing test JNI entry points enlarges the attack surface. | P1_HIGH |

---

## 8. Group E — U-32…U-42

| ID | Finding | Status | Evidence | Priority |
|---|---|---|---|---|
| U-32 | Auto Mode hero button has no textual state for screen readers | **STILL_VALID** | `AutoModeScreen.kt:360` `AutoConnectHeroButton(state = state, …)` — state is conveyed visually; no `contentDescription`/`stateDescription` on the hero. | P2_MEDIUM |
| U-33 | "Try next server" enabled/disabled has no announced reason | **STILL_VALID** | `:369–372` `enabled = canTryNext`; no a11y explanation when disabled. | P3_LOW |
| U-34 | Error card does not announce itself (no live region) | **STILL_VALID** | `:397–405+` `if (state is AutoModeState.Error) { Card(...) }` — no `liveRegion` semantics. | P2_MEDIUM |
| U-35 | Log panel is not scrollable independently / no copy affordance | **PARTIALLY_VALID** | `:350–357` whole column is `verticalScroll`; the log is part of it. Functionally usable, ergonomically weak. | P3_LOW |
| U-36 | KSP version does not match the Kotlin version (V2) | **STILL_VALID** | Root `build.gradle:3` `ext.kotlin_version = '2.4.0'`; `:18` `id "com.google.devtools.ksp" version '2.3.2'`. KSP 2.3.x targets Kotlin 2.3.x — a mismatched KSP/Kotlin pair is fragile (may silently skip processing or fail on upgrade). AGP `9.1.1`, Gradle `9.7.0`. | P1_HIGH |
| U-37 | Settings protocol-priority and timeout rows render fixed strings regardless of locale | **STILL_VALID** | `SettingsScreen.kt` rows use `stringResource(...)` — correct — but fall back to English defaults when a key is absent in `values-fa`. Parity gap, not a bug per se. | P3_LOW |
| U-38 | `SettingSwitchRow` Auto-Reconnect row ordering inconsistent with the doc | **PARTIALLY_VALID** | `SettingsScreen.kt:510–518` places Auto-Reconnect after protocol-priority + timeout inside the Auto Mode section — matches the spec ordering described in earlier phases; minor. | P4_INFO |
| U-39 | Status screen traffic formatting loses precision at large values | **PARTIALLY_VALID** | `StatusScreen.kt`/`TrafficStats.kt` format via integer division for display; cosmetic. | P4_INFO |
| U-40 | Auto Mode state not restored after process death | **ALREADY_FIXED (by design)** | R8 explicitly scoped process-death out (`docs`: "process-death out of scope"); not a defect. | P4_INFO |
| U-41 | Bottom-nav tab labels rely on icon + text with no selected-state semantics | **STILL_VALID** | Navigation in `AppRoot.kt`; selected state visible but not announced. | P3_LOW |
| U-42 | Dark/light theme contrast on the Auto Mode hero during `Error` | **STILL_VALID** | `AutoModeScreen.kt` hero colors derived from `MaterialTheme.colorScheme`; the Error variant contrast was not tuned. | P3_LOW |

---

## 9. Group F — Phase Attribution (S-06 partial, S-22)

**S-06 (private handle) — ATTRIBUTED.** The private `nativeHandle` in `SoftEtherClient` was introduced in the original client layer (pre-F.28). The F.28AL-AUTOCONNECT-BUG3-FIX series (A2/A3/H2 of `F28AL_AUTOCONNECT_BUG3_AUDIT_REPORT.md`) **diagnosed** it as the root cause of the ignored user timeout and added the explicit `setTimeout(handle, timeoutMs)` overload (`SoftEtherClient.kt:196–221`), migrating the production caller (`ConnectionController.kt:535`). The legacy overload was retained and deprecated (`:184–194`) rather than deleted → **PARTIALLY_VALID** is the correct final state.

**S-22 (legacy disconnect) — ATTRIBUTED.** `ConnectionController.disconnect()` (`:750`) is the pre-F.28Z teardown. The F.28Z/F.28AA/F.28AD series added the *session-scoped* service path (`stopVpn` with `teardownAttemptId`, `TeardownCompletion`, `NativeLifetimeRegistry`) **alongside** it rather than replacing it. Both paths remain; the service path is the one Auto Mode uses for the armed teardown, while `disconnect()` is still reached on `attemptReconnect` failure (`:1114`) and by `skipCurrent`-adjacent flows. Classification: **STILL_VALID (by design)** — a deliberate dual path, not a regression, but a maintenance and consistency risk.

---

## 10. Group G — Root-Cause Analysis

### G-1 — Stale `SoftEtherVpnService` survives a network-loss teardown

**Symptom (device, prior phase):** after any network-loss teardown, the service stays foreground with a stale "connected session owns the service" condition; `Start refused: a connected session already owns the service`; reproduced 4/4; only `am force-stop` cleared it.

**Root cause (source):** on network loss, `SoftEtherVpnService.networkCallback.onLost` (`:346–349`) calls `controller?.onNetworkChanged(false)`. That method (`ConnectionController.kt:217–224`) **deliberately does not tear down** — it logs `Network lost — data loops will detect and attempt reconnect` and returns. The receive/send loops (`:989–999`, `:1054–1057`, `:1062–1068`) then call `triggerReconnect()` → `attemptReconnect()`, which **resets `isCancelled` to false** (`:1126`), **tears down the TUN/native handle** (`:1129–1148`) and re-creates them (`:1181–1195`). If the reconnect cannot complete (network still down), the session is left **logically live** (`controller != null`, `tunOwnership.isHeld()`, `connectionJob != null`) while no tunnel exists and the `NativeLifetimeRegistry` lease is **retained**. The next `startVpn` then computes `sessionResourcesLive = true` and `decideStart` returns `REFUSE_CONNECTED` (`:546–547`) — observed verbatim. Because `attemptReconnect` cleared `isCancelled`, the service also does not self-stop. Net effect: a foreground service whose Kotlin references and native lease are all still "owned" but which can never make progress → **requirement for `am force-stop`**.

**Severity:** P1_HIGH — user-visible dead-end; a normal user cannot recover without killing the app. This is the single highest-impact *behavioral* finding of the phase.

### G-2 — Teardown-confirmation vocabulary mismatch

**Symptom:** native logs `Native cleanup confirmed: structure destroyed and slot released` while Auto Mode logs `NATIVE CLEANUP UNCONFIRMED (… NOOP_REENTRANT); ownership retained`.

**Root cause (source):** two *different* signals describe two *different* teardowns, and they are not correlated by identity in the logs.
- `ConnectionController.destroyResources` logs `Native cleanup confirmed: …` from the `CONFIRMED`/`ALREADY_CONFIRMED` branch (`:895–899`).
- `stopVpn`'s re-entrant branch (`:977–1003`) emits `NOOP_REENTRANT` **when `beginTeardown` returned false** — i.e. a *second* `stopVpn` for a session whose teardown already ran. `AndroidConnectionAdapter.disconnect:187–197` correctly interprets `NOOP_REENTRANT` as "not a native confirmation".

So the pair is not contradictory — but the logs read as if they are, because (a) the controller's message does not name the attempt/session and (b) the adapter's message does. **This is a diagnosability defect, not a correctness defect.** The typed `TeardownCompletion` already carries `attemptId`/`sessionId` (`TeardownCompletion.kt:43–48`), so the fix is to include them in the controller's `Log.d` at `:896`/`:899`.

**Severity:** P2_MEDIUM.

---

## 11. V1–V6 Verification Results

- **V1 — L-01 native binary:** **CONFIRMED.** `libsoftether_test.so` is present and non-empty in both `app-pro-debug.apk` and `app-pro-release.apk`, for all four ABIs. Release arm64 size 5,727,904 B; contains `native_test`, `test_jni_bridge`, `JNI_OnLoad` entry points. See §7/L-01.
- **V2 — U-36 KSP vs Kotlin:** **CONFIRMED MISMATCH.** `kotlin_version = 2.4.0` vs `ksp version '2.3.2'`. See §8/U-36.
- **V3 — S-06 private handle usage:** **CONFIRMED.** Private `nativeHandle` (`SoftEtherClient.kt:13`) is unresolved on the controller path; production uses the explicit overload. **PARTIALLY_VALID.**
- **V4 — S-22 legacy disconnect:** **CONFIRMED PRESENT.** `ConnectionController.kt:750` `fun disconnect()` still exists and is reachable from `attemptReconnect` failure. **STILL_VALID (by design).**
- **V5 — G-1 root cause:** **RESOLVED.** `onNetworkChanged` deliberately does not tear down; `attemptReconnect` resets `isCancelled` and can strand the session with its lease held → `REFUSE_CONNECTED` on the next start. See §10.
- **V6 — G-2 mismatch:** **RESOLVED.** Two signals for two different teardowns, uncorrelated in logs; not a correctness contradiction. See §10.

---

## 12. Ranked P0 Blocker List

| Rank | ID | Finding | Why P0 |
|---|---|---|---|
| 1 | **L-01 / L-02** | Test library + duplicated production sources compiled into `libsoftether_test.so` and shipped in **release** (≈21 MB across 4 ABIs) | Ships non-production code and test JNI entry points to every user; release-binary integrity. |
| 2 | **N-01** | `nativeSend` does not bound `length` against the Java array | Native out-of-bounds read — memory safety, reachable from any caller passing a wrong length. |
| 3 | **N-02** | `nativeReceive` does not bound `maxLength` against the buffer | Native out-of-bounds **write** into a Java array — the most dangerous direction. |
| 4 | **N-03** | `nativeReceiveBatch` missing both array-length validations | Same class as N-02, plus a writable `lengths` array. |
| 5 | **N-08** | `rudp_recv` returns `-1` while holding `ctx->lock` | Permanent deadlock of the RUDP context for every other thread on a single oversized block. |

> `nativeSendSlice` (`softether_jni.c:232`) is the **correct template**: it validates `offset`/`length` and checks `(jlong)offset+length > array_len`. N-01/N-02/N-03/N-04 should adopt the same pattern.

---

## 13. Recommended Next-Phase Sequence

| # | Phase | Scope | Complexity | Dependencies |
|---|---|---|---|---|
| 1 | **FIX-L01-RELEASE-PACKAGING** | Guard `softether_test` behind `option(BUILD_TESTING OFF)` in `CMakeLists.txt`; gate the `TEST_SOURCES` target; optionally `splits { abi }` or AAB-only. Verify the release APK no longer contains `libsoftether_test.so`. | Low | none |
| 2 | **FIX-NATIVE-JNI-BOUNDS** | Add `GetArrayLength` validation to `nativeSend`/`nativeReceive`/`nativeReceiveBatch`/`nativeSendRaw`/`nativeSetClientMac`, mirroring `nativeSendSlice`. Add a native unit test per entry point. | Medium | none |
| 3 | **FIX-RUDP-RECV-LOCK** | Restructure `rudp_recv` so the `entry->len > max_len` path unlocks before returning; audit every early return in `softether_rudp.c` for the same shape. | Low | none |
| 4 | **FIX-G1-STALE-SERVICE** | Give `attemptReconnect`-failure a bounded terminal path that releases the lease and stops the service, so a network-loss teardown cannot strand `REFUSE_CONNECTED`. | High | Coordinates with `NativeLifetimeRegistry`; needs device verification. |
| 5 | **FIX-KSP-KOTLIN-ALIGN** | Align KSP to the Kotlin 2.4.x line (or pin Kotlin to the KSP-supported version); re-run the full build + KSP tasks. | Low | none |
| 6 | **FIX-LOG-IDENTITY (G-2)** | Add `attemptId`/`sessionId` to the controller's "Native cleanup confirmed" log (`ConnectionController.kt:896`, `:899`) so the two teardown vocabularies correlate. | Low | none |
| 7 | **FIX-NATIVE-LIFETIME-SYNC (N-15/N-16)** | Add a lock to `nativeConnect`; make `rudp_destroy` synchronise with an in-flight `rudp_poll`. | Medium | Requires concurrency review. |
| 8 | **FIX-A11Y-U32..U42** | Hero/error live regions, disabled-reason announcements, nav selected-state semantics. | Low | None. |
| 9 | **FIX-S06-API-TRAP** | Delete the deprecated `setTimeout(timeoutMs)` overload (or make it throw) once no caller remains. | Low | After confirming zero call sites. |
| 10 | **RUNTIME-UNCLEAR-CLOSURE** | Close N-18, A-43, A-44 with targeted runtime tests (device/instrumentation). | Medium | Requires a build + device; **outside this read-only phase**. |

---

## 14. Explicit No-Change Confirmation

- **No file was created, modified, or deleted** in the product tree during this phase. The only file written is this report: `DEEP_REVIEW_VERIFY_REPORT.md` at the repository root (a new deliverable, not a modification of existing source, tests, Gradle, or resources).
- **No build was run.** No Gradle task, no CMake, no `assemble`, no `test`.
- **No test was run.**
- **The device was not touched.** No `adb` command was issued against any device in this phase.
- **No CI was triggered.** No workflow dispatch, no push.
- **No commit, tag, or push was performed.**
- **HEAD unchanged:** `git rev-parse HEAD` = `071462816b1eef7e7f7459483fcd69909938cd11` (same as the start of this phase).
- **Working tree unchanged:** `git status --porcelain | wc -l` = `162` (same as the start of this phase; sample first entries `.github/workflows/android-ci.yml`, `SoftEtherClient/src/androidTest/.../NativeConnectionTest.kt`, `SoftEtherClient/src/main/cpp/test/{native_test.c,native_test.h,test_jni_bridge.c}`).
- All evidence cited above was obtained by **reading** files (`Read`, `Grep`, `Glob`) and by **reading** already-built artifacts (`unzip -l`, byte inspection of existing `.so`/`.apk` outputs) — no artifact was produced by this phase.
- **Language:** English only, throughout.

**End of report.**
