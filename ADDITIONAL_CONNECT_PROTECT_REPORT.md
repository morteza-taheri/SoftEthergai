# ADDITIONAL_CONNECT_PROTECT — Phase Report (BLOCK v1.0.51)

**Repository:** `C:\Dev\SoftEthergai` · **Branch:** `main`
**Baseline HEAD at verification:** `6f25df9954888577bba088af892765077dfd4427` (== `origin/main` at STEP 1)
**Spec:** `PROJECT_SPECIFICATION.md` v1.0.51, new `§1.6.43`
**Phase type:** DETACHED (device-free). **Device verification: PENDING (V-VERIFY-3).**
**Evidence labels:** `[SOURCE CODE]` `[TEST]` `[PHYSICAL DEVICE]` `[INFERENCE]` `[UNKNOWN]` `[PRIOR REPORT]`

---

## 1. Verdict

**Hypothesis CONFIRMED.** The additional-connect TCP socket was dialled with no `VpnService.protect()` before `connect()`. Fixed by protecting the fd between `socket_create()` and `socket_connect_timeout()`, driven by a new per-connection native→Kotlin protect hook. Fail-closed, teardown-safe, no duplicate protection logic, primary path and non-VPN hosts unaffected.

| Check | Result |
|---|---|
| Additional connect protects before connect | **YES** (was NO) `[SOURCE CODE]` + `[TEST]` |
| Fail closed on protect failure | **YES** `[SOURCE CODE]` + `[TEST]` |
| JavaVM attach/detach on the background thread | **YES** `[SOURCE CODE]` + `[TEST]` |
| Teardown releases after the thread join (no UAF) | **YES** `[SOURCE CODE]` + `[TEST]` |
| Periodic safety net preserved | **YES** `[TEST]` |
| JVM tests | `:SoftEtherClient` **184/0/0/0** (+7) · `:app` **907/0/0/1** (unchanged) `[TEST]` |
| Native build | SUCCESSFUL, all 4 ABIs; both new symbols exported `[TEST]` |
| `native_test.c` | **NOT RUN** — Android `.so` requiring a device/emulator `[UNKNOWN]` |
| Device behaviour | **UNKNOWN** — V-VERIFY-3 pending |

---

## 2. STEP 1 — Pre-conditions

| Item | Value |
|---|---|
| `git rev-parse HEAD` | `faee69b8…` at STEP 1; `6f25df99…` at commit time (publisher `chore(emergency)` commits advanced `main` during the phase) |
| `git rev-parse origin/main` | equal to HEAD at both points |
| `git branch --show-current` | `main` |
| `git status --porcelain=v1` (tracked) | only `M vpngate_collector.py` — the brief's own named exception ("NOT yours: do not stage or touch") |
| Spec version at STEP 1 | `1.0.49` (≥ 1.0.49 required) |
| `git tag -l \| wc -l` | `5` (`p2.4.5`, `p2.4.7`, `p2.4.8`, `p2.4.9`, `p2.4.10`) |

**Note on the moving tip.** The publisher's `chore(emergency)` commits land on `main` continuously and touch only `emergency-sources/servers.csv`. They change no source, test or spec byte relevant to this phase; the diff-scope check in §6 is taken against the same baseline that was built and tested.

---

## 3. STEP 2 — Read-only discovery (evidence table)

### 3a. Post-TUN socket-creation sites

| # | Site | Function / trigger | tun0 exists? | Protect before connect? |
|---|---|---|---|---|
| 1 | `softether_protocol.c:4029` → `:4036` | `softether_additional_connect()` — background pthread launched by `softether_maintain_links()` (`:3451`, routine `:3980`) | **YES** (`:4009` requires `STATE_CONNECTED`) | **NO — the defect** `[SOURCE CODE]` |
| 2 | `softether_protocol.c:4029` (same function) | also called synchronously by `softether_establish_first_additional()` (`:1565`) during login | NO (pre-TUN) | NO — but harmless; the socket is protected by Kotlin after connect, and the hook now covers it too |
| 3 | `softether_protocol.c:1886` → `:1894` | parallel transport race (UDP-only servers, `softether_connect_parallel_race`) | NO (pre-TUN, inside `softether_connect_with_hub`) | NO — pre-TUN, primary path |
| 4 | `softether_protocol.c:2766` → `:2769` | primary TCP connect (`softether_connect_with_hub`) | NO (pre-TUN) | NO — pre-TUN; Kotlin protects at `ConnectionController.kt:725` |
| 5 | RUDP UDP (`rudp_transport.c:1164`/`:1186`, `softether_nat_t.c:109`, `softether_rudp.c:163`) | created during connect/login | NO (pre-TUN) | NO — pre-TUN; Kotlin protects RUDP/NAT-T at `ConnectionController.kt:737`/`:748` |

**No post-TUN UDP re-open path exists** — a grep for `reopen` / `re_create` / `udp_fd = socket` in `rudp_transport.c` returns only the initial creation sites. So the additional-connect TCP socket (row 1) is the only socket that is created *after* the tunnel is up. `[SOURCE CODE]`

### 3b. Was there a native→Kotlin protect hook?

**No.** `[SOURCE CODE]`
- `softether_jni.c` `JNI_OnLoad` ignored its `vm` argument (no `JavaVM` cached) and exposed no upcall.
- The only fd accessors were queries the host calls *after the fact*: `nativeGetSocketFd`, `nativeGetRudpSocketFd`, `nativeGetNatTUdpSocketFd` (`:512`), `nativeGetAllSocketFds` (`:441` → `softether_get_active_socket_fds`, `softether_protocol.c:4414`).
- `nativeSetOption` had no protect-related option (options 1, 4, 5 only).

### 3c. Threading

| Question | Answer |
|---|---|
| Which thread runs additional connects? | A background pthread created at `softether_protocol.c:3451` (`additional_connect_thread_routine`, `:3980`). Not a JVM thread. `[SOURCE CODE]` |
| How does a JNI upcall get `JNIEnv`? | `GetEnv`; `AttachCurrentThread` when `JNI_EDETACHED`; `DetachCurrentThread` before returning. `[SOURCE CODE]` (implemented) |
| Teardown races? | `softether_destroy()` joins the additional thread before freeing (`:1088`); `softether_disconnect()` joins at `:3171`/`:3930`. The protector struct is freed only in `nativeDestroy`, **after** `softether_destroy()` returns, so no upcall can be in flight. A `released` flag makes any late call fail closed. `[SOURCE CODE]` + `[TEST]` T-PROTECT-05 |

### 3d. `protectedFds` lifecycle and fd recycling

`private val protectedFds = Collections.synchronizedSet(mutableSetOf<Int>())` (`ConnectionController.kt:1747`). It is cleared in **two** places: the `NativeHandleOwner` handles lambda (`:135`) and `claimAndDestroyNativeHandle()` (`:506`) — i.e. on every native teardown, so a recycled fd number cannot suppress `protect()` in a fresh session. Both clears are preserved. `[SOURCE CODE]` + `[TEST]` T-PROTECT-07

### 3e. Existing tests that pin socket/protect behaviour

- `:SoftEtherClient` had **177** JVM tests, none of which touched `protect`/`getAllSocketFds` (grep across `SoftEtherClient/src/test` matched only prose in KDoc). `[SOURCE CODE]`
- `native_test.c` (`SoftEtherClient/src/main/cpp/test/native_test.c`) exists and is built into `libsoftether_test.so` by the CMake `softether_test` target (`CMakeLists.txt:154`). It is an **Android shared library** loaded via `System.loadLibrary("softether_test")` by instrumentation tests — it cannot run in this JVM-only environment. **NOT RUN.** `[UNKNOWN]`

---

## 4. STEP 3 — Fix

### 4.1 Core (`softether_protocol.h` / `softether_protocol.c`)

New per-connection hook on `softether_connection_t` (zeroed by the existing `calloc`, so the default is "no hook"):

```c
int (*protect_socket_fn)(void* ctx, int fd);
void* protect_socket_ctx;
```

Registered through `softether_set_socket_protector(conn, fn, ctx)` and cleared by `softether_clear_socket_protector(conn)`.

`softether_additional_connect()` now does, between create and connect (`softether_protocol.c:4040`):

```c
if (conn->protect_socket_fn != NULL) {
    if (conn->protect_socket_fn(conn->protect_socket_ctx, sock->fd) != 0) {
        LOGE("additional_connect: socket protect failed (fd=%d); aborting before connect", sock->fd);
        socket_destroy(sock);
        conn->additional_failed_count++;
        return -1;                       // fail closed: never dial an unprotected socket
    }
    LOGD("additional_connect: socket fd=%d protected before connect", sock->fd);
} else {
    LOGD("additional_connect: no socket protector registered (fd=%d); connecting unprotected", sock->fd);
}
```

### 4.2 JNI (`softether_jni.c` / `.h`)

- `JNI_OnLoad` now caches the `JavaVM`.
- `jni_socket_protector_t { JavaVM* vm; jobject service; int released; pthread_mutex_t lock; }` holds a `NewGlobalRef` on the host service.
- `jni_protect_upcall()`: `GetEnv` → `AttachCurrentThread` only if `JNI_EDETACHED` → `NewLocalRef` under the lock → `GetMethodID("protect", "(I)Z")` → `CallBooleanMethod` → `ExceptionCheck`/`ExceptionClear` → `DeleteLocalRef` → `DetachCurrentThread` if it attached.
- `nativeSetSocketProtector(handle, service): Boolean` arms the hook; re-registration replaces the ref (old one `DeleteGlobalRef`-ed after the swap).
- `nativeDestroy`: `softether_clear_socket_protector(conn)` → `softether_destroy(conn)` (joins the additional thread) → `jni_socket_protector_release(env, protector)`.

### 4.3 Kotlin

- `SoftEtherClient.setSocketProtector(handle, protector: Any?): Boolean` + `external fun nativeSetSocketProtector(...)`. Typed `Any?` on purpose so the client class keeps no Android framework type and stays JVM-testable.
- `ConnectionController` arms the hook right after `nativeCreate()` on the initial path (`:445`) and on the reconnect path (`:1353`), before any `nativeConnectWithHub`; a `false` return throws, routing through the existing teardown.

### 4.4 What was deliberately **not** changed

MS-SSTP, Auto Mode, the collector ladder, every timeout, `protectAdditionalSockets()` (kept as the safety net), and the `protectedFds` teardown clears.

---

## 5. STEP 4 — Tests

`SoftEtherClient/src/test/java/vn/unlimit/softether/protect/AdditionalConnectProtectTest.kt` — 7 JUnit 4 **source-shape** tests (the defect lives in C and in a JNI upcall that need an Android host and a live `VpnService`; the repo already uses this pattern where a module cannot run in isolation).

| Test | Pins |
|---|---|
| T-PROTECT-01 | The hook is invoked **after** `socket_create` and **before** `socket_connect_timeout` — the ordering *is* the fix |
| T-PROTECT-02 | A failed protect logs, `socket_destroy`s, bumps `additional_failed_count` and returns `-1` **before** the connect |
| T-PROTECT-03 | The hook lives on the connection struct and is clearable (no global state) |
| T-PROTECT-04 | The upcall uses `GetEnv`, attaches/detaches, checks and clears exceptions, takes a `NewLocalRef`, and honours `released`; registration takes/drops global refs and arms the hook |
| T-PROTECT-05 | `nativeDestroy` clears the hook, destroys, and releases the host ref **only after** `softether_destroy()` |
| T-PROTECT-06 | The controller arms the protector **before** every `nativeConnectWithHub`, on both paths (exactly 2 arming sites, 2 connect sites) |
| T-PROTECT-07 | `receiveCount % 50` → `protectAdditionalSockets()` survives, and `protectedFds.clear()` still runs on teardown (≥ 2 sites) |

**Counts**

| Target | Before | After | Delta |
|---|---|---|---|
| `:SoftEtherClient` | 177 / 0 / 0 / 0 | **184 / 0 / 0 / 0** | **+7** |
| `:app` | 907 / 0 / 0 / 1 | **907 / 0 / 0 / 1** | 0 |

`native_test.c` — **NOT RUN** (Android `.so`; see §3e).

---

## 6. STEP 5 — Build and diff scope

`:app:assembleProDebug` under `CI=true --rerun-tasks --no-build-cache`: **BUILD SUCCESSFUL**, 114 tasks (the native sources compiled for all four ABIs).

| Item | Value |
|---|---|
| APK | `app/build/outputs/apk/pro/debug/app-pro-debug.apk` |
| Size | 63,795,058 bytes (was 63,782,158 — +12,900 B for the new code across 4 ABIs) |
| SHA-256 | `36b4e4bb5b38bf0b9c449993d59d2f8d6f55166a02f2e42bfdbfcbd83ec6a521` |
| `versionCode` / `versionName` | `144` / `2.4.11-dev` — **unchanged** |
| New symbols in `arm64-v8a/libsoftether.so` | `Java_vn_unlimit_softether_client_SoftEtherClient_nativeSetSocketProtector`, `softether_set_socket_protector` |

**Tracked diff (exactly the authorised set):**

```
 SoftEtherClient/src/main/cpp/jni/softether_jni.c                      | 182 +++++++++-
 SoftEtherClient/src/main/cpp/jni/softether_jni.h                      |   8 +
 SoftEtherClient/src/main/cpp/softether-core/include/softether_protocol.h | 25 +++
 SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_protocol.c | 53 +++-
 SoftEtherClient/src/main/java/vn/unlimit/softether/client/SoftEtherClient.kt | 25 +++
 SoftEtherClient/src/main/java/vn/unlimit/softether/controller/ConnectionController.kt | 20 +++
```

Plus the new test file and this report (untracked until commit), and `PROJECT_SPECIFICATION.md`. `vpngate_collector.py` remains modified and was **not staged** (the brief's named exception).

---

## 7. STEP 6 — Spec

- Header: `Document version 1.0.51`, `HEAD 6f25df99…`, `Last updated 2026-10-22`.
- New push-state note for v1.0.51.
- New `#### 1.6.43 ADDITIONAL-CONNECT-PROTECT (v1.0.51)` — 16 numbered items.
- §20 changelog row `1.0.51`; §20 verification-status row `§1.6.43`.
- §22 **byte-identical** (`adf12858…020e`, 99 lines, verified against `HEAD:PROJECT_SPECIFICATION.md`).
- Note: the changelog has **no 1.0.50 row** — the brief mandates 1.0.51, so the version jumps from 1.0.49 to 1.0.51 with no 1.0.50 entry invented.

---

## 8. What was NOT testable

| Item | Why | Label |
|---|---|---|
| `native_test.c` | Android shared library loaded by instrumentation tests; needs a device/emulator | `[UNKNOWN]` |
| Whether the tunnel now survives > 20 s on hardware | needs the phone (V-VERIFY-3) | `[UNKNOWN]` |
| Whether `received` climbs instead of freezing | needs the phone | `[UNKNOWN]` |
| Whether other apps keep internet through the tunnel | needs the phone | `[UNKNOWN]` |
| Whether the on-device log shows the pre-connect protect line | needs the phone + Developer Mode | `[UNKNOWN]` |

---

## 9. Negative assurance

- No push, no tag, no `--force`, no reset/clean/stash/checkout/restore.
- No source/spec/test edit outside the authorised set; `vpngate_collector.py` untouched and unstaged.
- No MS-SSTP, Auto Mode, ladder or timeout behaviour changed.
- No new thread created; the existing additional-connect pthread is reused.
- No unbounded wait added: the hook is a single bounded JNI call inside the existing connect budget.
- No leaked global refs: exactly one `NewGlobalRef` per registration, `DeleteGlobalRef` on replace and on `nativeDestroy`.
- The 1 MiB emergency cap, the ladder order, and the v1.0.37 no-terminal contract are untouched.
- `C5` was not executed. `D:\PMC\Documents\Android\SoftEthergai` was not touched.

---

## 10. Closing check for the device phase (V-VERIFY-3)

1. SoftEther **TCP** tunnel stays up **> 20 s** with `additional_connect` active.
2. `nativeGetStats` `received` keeps climbing (no freeze at ~60).
3. Other apps keep internet while connected.
4. Developer-Mode log shows `additional_connect: socket fd=… protected before connect` **before** the connect, and no `socket protect failed`.
5. No `Keepalive missed` teardown in the first 60 s.
