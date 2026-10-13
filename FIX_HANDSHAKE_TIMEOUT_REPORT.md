# FIX_HANDSHAKE_TIMEOUT_REPORT

**Phase:** NATIVE-FIX-HANDSHAKE-TIMEOUT
**Repository:** `D:\PMC\Documents\Android\SoftEthergai`
**Branch:** `main`
**HEAD:** `fa8a53d20d4bc8ba1e233d473c1c7723cbadb71f`
**origin/main:** `fa8a53d20d4bc8ba1e233d473c1c7723cbadb71f` (in sync, local ahead 0)
**Date:** 2026-10-13
**Nature:** CODE-ONLY native fix. No build, no test, no device command, no commit, no push, no tag.

---

## §1 Executive verdict

The single root cause identified by the read-only audit `AUDIT_AUTOCONNECT_LOGIC.md` — an OpenSSL TLS handshake running on a **blocking** socket with **no effective `SO_RCVTIMEO`/`SO_SNDTIMEO`**, allowing `SSL_do_handshake()` to block indefinitely while holding the process-wide `g_openssl_lock` and `g_tls_use_lock` — has been addressed at the source, in the two authorized native files.

Three changes were made:

- **P1 — give the handshake socket a real timeout.** A single reusable helper `socket_apply_timeout(int fd, int timeout_ms)` now exists in `tcp_socket.c`, and it is called **before** every `perform_tls_handshake`/`ssl_connect` that can carry a TCP socket: once on the primary connect path (`conn->socket_fd`) and once inside `transport_thread_tcp` on that transport's own local fd. With a timeout applied, a stalled `recv()`/`send()` returns `EAGAIN`, which OpenSSL surfaces as `SSL_ERROR_WANT_READ`/`WANT_WRITE` — precisely the condition the **existing** `max_attempts` retry loop in `aes_wrapper.c` already handles. The loop becomes effective **without modifying `aes_wrapper.c`**.
- **P5a — a permanent discriminator log line.** Exactly one `LOGD` at handshake entry, outside any loop, reporting `conn->timeout_ms`, `conn->socket_fd`, and `SO_RCVTIMEO applied=yes|no`, plus a `LOGW` fail-open signal when the bound was not established.
- **P5b — bound the parallel-race `pthread_join`.** The unbounded join that followed the race deadline is now a bounded join with its own fresh `race_timeout_ms` budget; on timeout the thread is `pthread_detach`ed with a WARN, and the connect is **not** aborted.

**Verdict:** The code is complete and internally consistent (lexically verified; see §7). **Device verification is DEFERRED.** Whether P1 fully **removes** the wedge or merely **bounds** it, and whether the bounded join's detached-thread path can still interact with a subsequent connect, are **UNKNOWN** without a physical-device run. This report therefore claims a *code-level* fix, not a *field-verified* fix.

**Evidence basis:** the changes below are `SOURCE CODE`; all statements about runtime behaviour on the device are `INFERENCE` unless explicitly labelled `PHYSICAL DEVICE` (none in this phase) or `UNKNOWN`.

---

## §2 The audit's root-cause chain, restated with source anchors

The audit reduced a large space of observed Auto-Connect hangs to one mechanism. Restated in causal order, with the exact source anchors the audit cited (all re-confirmed by reading the files this phase):

1. **A socket is deliberately left in BLOCKING mode after connect.**
   `tcp_socket.c:305-311` — `socket_connect_timeout()` switches the fd back to blocking mode once the connect completes. *(SOURCE CODE)*

2. **The `SO_RCVTIMEO` application path early-returns on the default family.**
   `tcp_socket.c:172-174` — `socket_recreate_for_family()` returns early when the requested family already matches, so on the default `AF_INET` path the timeout is **never** applied to the fd. *(SOURCE CODE)*

3. **The JNI timeout option never reaches the handshake socket.**
   `softether_jni.c:522-525` — `nativeSetOption(1, N)` writes only `conn->timeout_ms`. It never calls into the socket layer for the handshake fd. *(SOURCE CODE)*

4. **The TLS handshake helper takes no timeout.**
   `aes_wrapper.c:410` — `ssl_connect(...)` has **no** timeout parameter. It cannot bound itself even if it wanted to. *(SOURCE CODE)*

5. **The retry loop that would handle a timeout is dead code on a blocking socket.**
   `aes_wrapper.c:453-487` — `max_attempts = 100` counts only `SSL_ERROR_WANT_READ`. On a blocking socket with no `SO_RCVTIMEO`, `recv()` never returns `EAGAIN`, so the loop never iterates; the first `SSL_do_handshake()` simply blocks. *(SOURCE CODE)*

6. **The mechanism provably exists and is merely unused before the handshake.**
   `softether_protocol.c:2643-2648` — the **data** path **does** apply `SO_RCVTIMEO = 5000` to the fd. The same primitive is available and working; it is simply not applied on the handshake path. *(SOURCE CODE)*

7. **A second, uninterruptible wedge variant: the unbounded join.**
   `softether_protocol.c:2301-2303` — after the parallel-race deadline, an unbounded `pthread_join()` waits for every transport thread. A thread still inside `SSL_do_handshake()` never exits, so the join blocks forever. `softether_force_close_socket()` cannot help: it shuts down only `conn->socket_fd`, while each transport thread owns a **local** fd. *(SOURCE CODE)*

**Consequence (audit §1.2 / §5):** the handshake can hold `g_openssl_lock` + `g_tls_use_lock` (read) indefinitely; a subsequent `ssl_destroy()` (`aes_wrapper.c:385-390`) takes the same lock **write** and blocks behind the wedge, so the whole native slot stays occupied past the Kotlin soft-release windows.

---

## §3 P1 implementation — a real timeout before the handshake

### 3.1 The helper (authorized file #2: `tcp_socket.c`)

A pre-existing function `socket_set_timeout(softether_socket_t*, int)` existed at `tcp_socket.c:443-455`. Exhaustive grep across `**/*.c` showed it had **ZERO callers** (dead code). Per STEP 2's explicit instruction ("**If a similar helper already exists … REUSE it rather than adding a new one. State explicitly in the report which path was chosen.**"), the REUSE-by-refactor path was chosen:

- The raw-fd logic was extracted into a new `int socket_apply_timeout(int fd, int timeout_ms)`.
- `socket_set_timeout()` was rewritten to delegate to it (keeping its `sock->timeout_ms` bookkeeping).
- The refactor additionally **fixes a silent-failure hole**: the original `socket_set_timeout` performed two bare `setsockopt` calls with both return values unchecked and no logging. Both call sites of the timeout mechanism now go through one checking, logging implementation.

Helper contract (as implemented):

| Input | Behaviour |
|---|---|
| `fd < 0` | return `-1` |
| `timeout_ms <= 0` | return `0` — deliberate no-op; kernel default left in place |
| otherwise | `struct timeval tv` built from `timeout_ms`; `setsockopt` on **both** `SO_RCVTIMEO` and `SO_SNDTIMEO`; `LOGD` on success, `LOGE` with `strerror(errno)` on failure; return `0`/`-1` |

### 3.2 Call site A — primary TCP connect path (authorized file #1: `softether_protocol.c`)

Immediately after the successful TCP connect (`tcp_ok`) and **before** `perform_tls_handshake`, the helper is called with `conn->timeout_ms`:

```c
int so_timeout_applied =
    (socket_apply_timeout(conn->socket_fd, conn->timeout_ms) == 0);
```

**Fail-open is deliberate:** if the helper returns non-zero, the code logs a `LOGW` but **does not abort**. A regression in the helper must never turn a connect that would otherwise succeed into a failure.

### 3.3 Call site B — `transport_thread_tcp` local fd

The parallel race starts a TCP transport thread whose fd is **local** to that thread and therefore **not** reachable by `softether_force_close_socket()` (audit §A6-1). That thread runs its own `ssl_connect()` (exactly one call site, `softether_protocol.c:1848`). The helper is now called on that local fd, before `ssl_create_client()`:

```c
if (socket_apply_timeout(fd, (int)ctx->timeout_ms) != 0) {
    LOGD("[%s] socket timeout not applied (fd=%d, timeout_ms=%u); handshake "
         "may block until the race detaches this thread",
         transport_name(TRANSPORT_TCP), fd, ctx->timeout_ms);
}
```

This call is the **enabler** for P5b: without it, the bounded join could only ever detach a thread still holding `g_openssl_lock`; with it, the thread can actually finish.

**Scope note (deliberate):** the other three race transports that call `ssl_connect` — `transport_thread_rudp_direct` (`:1875`), `transport_thread_natt` (`:1978`), `transport_thread_dns` (`:2061`), `transport_thread_icmp` (`:2144`) — sit on **RUDP transport** fds, not TCP sockets. `SO_RCVTIMEO` is not the appropriate bound for those, and their underlying transport already carries `cfg.connect_timeout_ms = conn->timeout_ms`. They were left unchanged.

### 3.4 Why this makes the existing `aes_wrapper.c` retry loop effective

`SO_RCVTIMEO`/`SO_SNDTIMEO` are the **only** mechanism that can interrupt a stalled `recv()`/`send()` on a blocking socket (kernel default `0` = infinite). Once set, a stalled call returns `EAGAIN`; OpenSSL maps that to `SSL_ERROR_WANT_READ`/`WANT_WRITE`. That is exactly the branch the existing loop at `aes_wrapper.c:453-487` already handles via `max_attempts = 100` with `usleep(10000)` between attempts. **No change to `aes_wrapper.c` was required or made** — the loop was correct all along; it was simply never reached because the socket never produced `EAGAIN`. Effective bound ≈ `max_attempts × per-attempt timeout` (≈ `100 × conn->timeout_ms`), which is finite.

**What was NOT changed (per STEP 2):** the existing blocking-mode switch in `tcp_socket.c`; `ssl_connect`'s signature; the data-path timeout at `softether_protocol.c:2643-2648`; any existing socket creation or DNS path.

---

## §4 P5a implementation — the discriminator log line

Exactly **one** permanent `LOGD`, emitted at handshake entry and **deliberately outside** the per-attempt retry loop (one line per handshake entry, never per attempt — no log flooding):

```c
LOGD("[TLS] handshake entry: conn->timeout_ms=%d, socket_fd=%d, "
     "SO_RCVTIMEO applied=%s",
     conn->timeout_ms, conn->socket_fd,
     so_timeout_applied ? "yes" : "no");
```

Immediately after, the fail-open signal:

```c
if (!so_timeout_applied) {
    LOGW("TLS handshake proceeding WITHOUT a socket timeout "
         "(fd=%d, timeout_ms=%d); a stalled handshake can still "
         "block indefinitely",
         conn->socket_fd, conn->timeout_ms);
}
```

**Why permanent (not developer-mode gated):** the audit's §D.4 item 1 requires a discriminator that is present in the field. If a handshake later stalls, this single line answers the first question any future investigation asks: *was the socket timeout applied at all?*

**How it becomes the discriminator in the next device run:**
- `SO_RCVTIMEO applied=yes` + handshake still stalls past the expected budget → P1 is in force and the stall is bounded; the residual problem is elsewhere (or the per-attempt budget is simply large).
- `SO_RCVTIMEO applied=no` → the bound was **not** established; the `LOGW` fires and the handshake is still unbounded. This is the exact regression signature to look for.
- The line also prints `conn->timeout_ms` and `socket_fd`, so the actual budget and the actual fd can be correlated against the race deadline and the data-path `SO_RCVTIMEO = 5000` line.

---

## §5 P5b implementation — the bounded join, and the Bionic API gap

### 5.1 The finding that forced a design decision

STEP 4 named two *preferred* mechanisms: `pthread_timedjoin_np` (with an absolute deadline), falling back to a short polling loop around `pthread_tryjoin_np`.

**Neither exists in Android Bionic.** This was verified before writing any P5b code:
- `pthread.h` across the NDK **25.1.8937393, 25.2.9519653, 26.1.10909125, 27.0.12077973, 28.2.13676358** sysroots declares **only** `pthread_join()`.
- A site-wide `grep -ln` across all NDK sysroot headers for the `_np` names returned **empty**.
- The project does not pin `ndkVersion`; `SoftEtherClient/build.gradle:26` sets `abiFilters 'armeabi-v7a','arm64-v8a','x86','x86_64'`.

*(SOURCE CODE / toolchain evidence.)*

### 5.2 The implemented design — an exit-flag trampoline

Because neither named primitive is available, a trampoline was implemented that satisfies **STEP 4's intent** (bounded join, the exact WARN text, `pthread_detach`, no abort) using only available primitives:

- `transport_thread_ctx_t` gains `volatile int exited;` (`softether_protocol.c:1709-1723`).
- A `race_trampoline_t { func, arg, exited }` and `static void* race_thread_trampoline(void*)` are added after the struct (`:1725-1750`). The trampoline calls the real thread body, then publishes `__sync_lock_test_and_set(exited, 1)` **before** returning, so the race can observe exit without waiting for the kernel to reap the thread.
- Every transport thread is launched through the trampoline.
- The join loop uses a **second, fresh budget** `join_deadline = softether_tick_ms() + race_timeout_ms` (the poll loop above already spent the first `race_timeout_ms`). Each thread is polled at 10 ms; if it published exit it is `pthread_join`ed, otherwise:

```c
LOGW("[RACE] transport thread %d did not exit within %u ms after race "
     "deadline; detaching to avoid an unbounded join",
     i, race_timeout_ms);
pthread_detach(thread_ids[i]);
detached_count++;
```

The connect is **not** aborted on this path — it proceeds with whichever transports did exit.

### 5.3 Why `pthread_detach` and not `pthread_cancel`

`pthread_cancel()` could kill a thread **while it holds `g_openssl_lock`**, leaving that mutex locked forever — strictly worse than the leak it would fix. `pthread_detach` lets the thread run to completion and release its locks naturally. This rationale is recorded verbatim in the code comment.

### 5.4 Memory-safety consequences handled

Moving to a detached thread means a thread may still read its context after the function returns, so:
- `transport_thread_ctx_t threads[…]` was moved from the **stack** to `calloc`'d **heap** (`:2247-2256`); the `num_transports == 0` early return now `free(threads)` first.
- The context array is freed **only** when `detached_count == 0`; otherwise it is deliberately retained (bounded, one-off leak on an already-failing connect path) with a `LOGW`.
- The winner's five fields (`socket_fd`, `ssl_ctx`, `ssl`, `transport`, `transport_type`) are snapshotted into locals **before** the conditional free, so promotion no longer dereferences `winner` (which may point into freed or live-detached memory).

**Deviation disclosure:** the implemented mechanism is an exit-flag trampoline, **not** `pthread_timedjoin_np`/`pthread_tryjoin_np`, because neither exists on this toolchain. It preserves the mandated observable behaviour.

---

## §6 What remains UNKNOWN

Labelled `UNKNOWN` — resolvable only with a physical-device run:

1. **Does P1 fully REMOVE the wedge, or merely BOUND it?** *(UNKNOWN)* The mechanism makes a stalled handshake return `EAGAIN` and lets the retry loop exhaust `max_attempts`. Whether the resulting finite budget is short enough to stay inside the Kotlin soft-release windows (`NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS = 30_000L`, TEARDOWN `= 90_000L`) in every real stall scenario is not proven here.
2. **Can the bounded join's detached thread still hold `g_openssl_lock` long enough to affect a subsequent connect?** *(UNKNOWN)* On the detach path the thread may still be inside `SSL_do_handshake` holding `g_openssl_lock` + `g_tls_use_lock`. Whether a follow-up `ssl_destroy()` (write-lock) can still be delayed by such a detached thread — within the same slot or a reused slot — is not established without device evidence. The P1 call in `transport_thread_tcp` is intended to make this path terminate quickly, but that is `INFERENCE`, not measurement.
3. **`resolve_hostname` (`softether_protocol.c:2410`) remains unbounded.** *(UNKNOWN)* DNS resolution is not covered by `SO_RCVTIMEO`; it was deferred per the phase's §6 wording and may still be a (smaller) wedge source.
4. **Behaviour under real network conditions** (loss, captive portals, IPv6-only, MTU blackholes) — untested. *(UNKNOWN)*
5. **Interaction with the frozen Kotlin bundle (FIX-1/2/3/C)** — the native fix and the Kotlin soft-release windows are designed to compose, but this has not been exercised together on a device. *(UNKNOWN)*

Accepted trade-offs already recorded in code: the retained-contexts path is a deliberate bounded one-off leak on an already-failing connect; detach-not-cancel is chosen to protect `g_openssl_lock`.

---

## §7 Change inventory

| File | Role | Line range (working tree) | Δ |
|---|---|---|---|
| `SoftEtherClient/src/main/cpp/softether-core/src/socket/tcp_socket.c` | authorized #2 — P1 helper | `socket_apply_timeout` + `socket_set_timeout` refactor, `:443-506` | **+54 / −8** |
| `SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_protocol.c` | authorized #1 — P1 + P5a + P5b | see hunks below | **+178 / −13** |

`softether_protocol.c` hunks (`git diff`):
- `@@ -22,6 +22,15 @@` — `socket_apply_timeout` prototype at file scope (declared locally because `softether_socket.h` is outside the authorized file list).
- `@@ -1700,8 +1709,45 @@` — `volatile int exited` field + `race_trampoline_t` + `race_thread_trampoline`.
- `@@ -1776,6 +1822,20 @@` — P1 call on `transport_thread_tcp`'s local fd.
- `@@ -2187,8 +2247,16 @@` — stack array → `calloc`'d heap array.
- `@@ -2257,13 +2325,17 @@` — trampoline launch.
- `@@ -2297,9 +2372,60 @@` — bounded join + winner snapshot + conditional free.
- `@@ -2308,13 +2434,12 @@` — promotion via snapshot locals.
- `@@ -2486,6 +2611,46 @@` — primary-path P1 call + P5a diagnostic + fail-open `LOGW`.

**Lexical self-verification (no build, as forbidden):** braces balanced (535/535 protocol, 84/84 socket); parens balanced (1877/1877, 243/243); brackets balanced (232/232, 7/7); `transport_thread_ctx_t threads[` → 0 occurrences (stack array fully removed); regex `for\s*\([^)]*\)\s*\{\s*pthread_join` → **no match** (no unbounded join remains); `free(threads)` ×2; `pthread_detach` ×1; `pthread_join` call sites are only the bounded one plus four pre-existing `additional_thread` joins. New symbols resolve: `softether_tick_ms` (7 uses), `usleep` (3 uses, `<unistd.h>` at `:9`), `pthread_detach` (`<pthread.h>` transitively via `softether_protocol.h:7`), `strerror`/`errno` (`<errno.h>` at `tcp_socket.c:7`). Compile flags are `-Wall` with **no `-Werror`** (`CMakeLists.txt:86-87`).

---

## §8 Explicit no-change confirmation

- **All 7 Kotlin files unchanged this phase.** `git diff --numstat` Kotlin rows are byte-identical to the pre-edit baseline / the audit's §8 table:
  - `267 13 NativeLifetimeRegistry.kt`
  - `106 0 SoftEtherVpnService.kt`
  - `39 2 ConnectionController.kt`
  - `285 0 NativeLifetimeRegistryTest.kt`
  - `32 0 AndroidConnectionAdapter.kt`
  - `215 1 AutoModeController.kt`
  - `244 5 AutoModeNativeBusyTest.kt`
- **`PROJECT_SPECIFICATION.md` unchanged.**
- **No test file modified.**
- **`softether_jni.c`, `aes_wrapper.c`, and every other C file unchanged.**
- **`CMakeLists.txt` and every Gradle file unchanged.**
- **No existing report modified.** `AUDIT_AUTOCONNECT_LOGIC.md` was read in full (STEP 1(e)) and not written to.
- **Diff scope (STEP 5 PASS):** `git diff --name-only` lists exactly the 2 authorized native files plus the 7 pre-existing Kotlin files — nothing else.
- **No build executed. No test executed. No device command executed. No commit / push / tag. C5 not executed.**

---

## §9 Recommendation for the next phase

**Recommendation: device-verify the frozen Kotlin bundle (FIX-1/2/3/C) + this native fix in ONE combined run.**

Rationale: the native fix and the Kotlin soft-release windows are designed to compose (the native bound keeps a handshake from exceeding the window; the window reclaims the slot if it ever does). Verifying them separately would consume two device cycles and leave their interaction unproven. A single combined run should:

1. Build the debug variant (`CI=true`) and install `vn.unlimit.vpngatepro` on device `5585fecc` (Xiaomi 2201117TG, Android 13 / API 33).
2. Reproduce the Auto-Connect hang scenario used by the prior phases.
3. Capture `logcat` and confirm the new `[TLS] handshake entry: … SO_RCVTIMEO applied=…` line appears on the handshake path and reads `applied=yes`.
4. Confirm the connect returns within the expected bounded budget (no indefinite hold of the native slot), and that no `[RACE] transport thread … did not exit …` WARN fires in the happy path.
5. If a `[RACE] … detaching` WARN does fire, capture whether a subsequent connect is affected — this is the open question in §6 item 2.
6. Confirm the Kotlin soft-release windows (CONNECTING 30 s / TEARDOWN 90 s) are not reached, i.e. the native fix prevented the wedge before the Kotlin fallback was needed.

**This recommendation is advisory. No next phase is proposed autonomously; the operator decides whether and when to authorize it.**

---

*End of report.*
