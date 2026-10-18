# FIX-NATIVE-LIFETIME-SYNC-DESIGN — Design Review for N-15 + N-16

**Phase:** FIX-NATIVE-LIFETIME-SYNC-DESIGN (design only, read-only)
**Date:** 2026-10-13
**HEAD:** `dc8e113` (verified)
**Rule obeyed:** Read-only. No file modified. No build. No device. No commit/tag/push. Report in English only.

---

## 1. Executive Verdict

```
DESIGN COMPLETE — READY FOR IMPLEMENTATION
```

Both defects are confirmed at source with exact line anchors. A concrete fix shape is selected for
each. T5-SLOT is **attributed with source evidence** to a cause that is *not* N-15 or N-16 — it is a
**logging/observability gap** compounded by an unconventional teardown path, not a new lifetime bug.
Open design decisions requiring user input are listed in §12.

---

## 2. N-15 Source Findings (S1)

### 2.1 The context type and its lock

`rudp_context_t` owns a single lock, `lock`, initialised **recursive** in `rudp_create`:

- **`softether_rudp.c:131–138`** — `PTHREAD_MUTEX_RECURSIVE` via `pthread_mutexattr_settype`.
  The comment at `:131` states the reason: *"recursive lock (rudp_poll -> keepalive -> rudp_send
  nests)"*. This is confirmed by the call chain `rudp_poll:697` → `rudp_poll_nolock:701` →
  `rudp_send_keepalive:718` → `rudp_send:807`, which re-acquires the same lock.

### 2.2 Every acquisition of `ctx->lock`

| Line | Function | Purpose |
|------|----------|---------|
| `471` / `473` | `rudp_is_send_ready` | lock/unlock pair |
| `696` / `698` | `rudp_poll` | lock/unlock pair; calls `rudp_poll_nolock` |
| `811` / `813` | `rudp_send` | lock/unlock pair |
| `1006` / `1028` | `rudp_recv` | lock/unlock pair |

**Total: 4 acquiring functions, 8 acquire/release calls.** There are no other `ctx->lock`
acquisitions in the file.

### 2.3 The destruction site — and the defect

**`softether_rudp.c:181–191`:**

```c
void rudp_destroy(rudp_context_t* ctx) {
    if (ctx == NULL) return;
    pthread_mutex_destroy(&ctx->lock);        // :183  <-- destroys the lock
    if (ctx->evp_encrypt_ctx) EVP_CIPHER_CTX_free(...);
    if (ctx->evp_decrypt_ctx) EVP_CIPHER_CTX_free(...);
    if (ctx->udp_fd >= 0) { close(ctx->udp_fd); ctx->udp_fd = -1; }
    free(ctx);                                 // :190  <-- frees the object
}
```

**`rudp_destroy` never acquires `ctx->lock`.** It destroys the mutex and frees the object while any
of the four functions above may be inside a critical section on another thread.

### 2.4 Every `rudp_destroy` caller

| # | Site | Context | Concurrency exposure |
|---|------|---------|----------------------|
| 1 | `softether_protocol.c:1589` | Inside `softether_connect_with_hub`, immediately after `rudp_create` fails to make an IPv6 socket (`:1587`). `conn->rudp` is then set `NULL` (`:1590`). | **BENIGN** — the context was created microseconds earlier in the same call, on the same thread. No poll can be in flight: `conn->rudp_enabled` is not yet `1`, and no other thread has the pointer. |
| 2 | `softether_protocol.c:2816` | Inside `softether_disconnect`, via `take_rudp_ctx(conn)` (`:2814`). | **THE EXPOSED PATH — see 2.5.** |
| 3 | `native_test.c` (15 sites: `:977–:1092`, `:3668`) | Hermetic harness teardown. | **TEST-ONLY.** Single-threaded per test except the N-08 probe, which joins before destroy. |

### 2.5 The caller path the review highlighted — and the real race window

The review flagged `softether_protocol.c:3347–3365` and `:2803–2817`. Both are confirmed, but the
sharper finding is in the **receive** side, which the review did not name.

**`softether_disconnect` holds `conn->connect_mutex` for its entire body:**
- `softether_protocol.c:2698` — `pthread_mutex_lock(&conn->connect_mutex)`
- `softether_protocol.c:2827` — `pthread_mutex_unlock(&conn->connect_mutex)`

`rudp_destroy` at `:2816` therefore runs **under `conn->connect_mutex`**. But `connect_mutex` is
**not held by any RUDP reader**. It only serialises connect-against-disconnect.

**The production race window (`softether_receive_data`, `softether_protocol.c:3389–3432`):**

```c
if (conn->rudp && conn->rudp_enabled) {          // :3389
    rudp_poll(conn->rudp);                       // :3392  (locks internally, releases)
    ...
    int udp_fd = rudp_get_udp_fd(conn->rudp);    // :3404  (reads conn->rudp->udp_fd)
    ...
    int poll_ret = poll(fds, nfds, 200);         // :3417  <-- BLOCKS UP TO 200 ms
    if (poll_ret > 0) {
        ...
            rudp_poll(conn->rudp);               // :3422  <-- re-dereferences conn->rudp
            r = rudp_recv(conn->rudp, ...);      // :3424  <-- and again
```

Between the read at `:3404` and the re-dereference at `:3422`, the thread blocks in `poll()` for up
to **200 ms**. If another thread enters `softether_disconnect` in that window, `take_rudp_ctx`
(`:48–53`) CASes `conn->rudp` to `NULL` and `rudp_destroy` frees the object. Line `:3422`
then dereferences **freed memory**. Because `conn->rudp` is re-read fresh at `:3422` (it is not a
local cached pointer), the CAS-to-NULL does not save it — the read at `:3422` returns `NULL` **only
if** the CAS has already completed; if the CAS completes *between* `:3422`'s read and the deref
inside `rudp_poll`, the stale non-NULL pointer is used.

**Note a second, subtler issue:** `rudp_get_udp_fd(conn->rudp)` at `:3404` is evaluated into a local
`udp_fd`, and `fds[nfds].fd` is set from it. If destroy closes the underlying fd (`:187`) in that
window, `poll()` at `:3417` polls a **stale, possibly-recycled fd**. This is a *separate* hazard from
the use-after-free and is not fixed by locking `rudp_destroy` alone.

### 2.6 Explicit answer: can this happen concurrently?

**Yes — with two qualifications.**

1. **Mechanically: yes.** `rudp_poll`/`rudp_recv`/`rudp_send`/`rudp_is_send_ready` take `ctx->lock`;
   `rudp_destroy` neither takes it nor waits. A `rudp_poll` in flight on thread A can be running
   `rudp_poll_nolock` while thread B calls `rudp_destroy` and completes `pthread_mutex_destroy` +
   `free`. This is an unconditional use-after-free / UB (destroying a locked mutex is itself UB).

2. **Practically: the window is narrow but real, and it is on the TUN receive path.** The exposure
   requires (a) RUDP enabled, (b) a receive thread inside the 200 ms `poll()` at `:3417`, and (c) a
   concurrent `softether_disconnect`. All three occur during a user "Try next server" while
   connected — **exactly the T5 scenario**.

**Evidence class:** SOURCE CODE EVIDENCE for the code structure and the absence of locking;
INFERENCE for the runtime probability.

---

## 3. N-16 Source Findings (S2)

### 3.1 The two JNI entry points and their asymmetric locking

| JNI function | Line | Calls | Locks `conn->connect_mutex`? |
|---|---|---|---|
| `nativeConnect` | `softether_jni.c:54–84` | `softether_connect(conn, ...)` at **`:77`** | **NO** |
| `nativeConnectWithHub` | `softether_jni.c:86–160` | `softether_connect_with_hub(conn, ...)` at **`:140`** | **YES** — lock `:139`, unlock `:145` |

The comment at `softether_jni.c:136–138` states the intent: *"Serialize connect and disconnect to
prevent freeing SSL while the connect path is using it. Recursive: softether_connect_with_hub calls
softether_disconnect internally on error paths."*

### 3.2 The critical structural refinement — the mutex is at the wrong layer

**`softether_connect` is a thin wrapper that takes no lock:**

```c
// softether_protocol.c:1608–1614
int softether_connect(softether_connection_t* conn, const char* host, int port,
                      const char* username, const char* password) {
    return softether_connect_with_hub(conn, host, port, username, password, "vpngate", 1,
        "", "", 0, "", "", "",
        "", "", 0,
        "", "", 0);
}
```

**`softether_connect_with_hub` also takes no lock internally.** A scan of its full body
(`softether_protocol.c:2325–2690`) finds **zero** `connect_mutex` acquisitions at entry. The only
occurrence in that range (relative offset 374, absolute `:2698`) is inside the **nested**
`softether_disconnect` call on an error path.

**Conclusion: `connect_mutex` is acquired exclusively at the JNI layer, by exactly one of the two
connect entry points.** The protocol-layer function it is supposed to protect is left unguarded for
any other caller. The defect is therefore **not** "`nativeConnect` forgot a lock" but
**"the lock lives in the caller instead of the callee, so it protects one call path and not the
other."**

### 3.3 Every public function that can initiate an outbound connection

| Function | Line | Locks `connect_mutex`? | Production caller |
|---|---|---|---|
| `softether_connect` | `:1608` | **NO** (delegates to `_with_hub`) | `nativeConnect` (`softether_jni.c:77`) |
| `softether_connect_with_hub` | `:2325` | **NO** (internally) | `nativeConnectWithHub` (`:140`, which locks); `softether_reconnect` (`:3989`, which does not) |
| `softether_reconnect` | `:3971` | **NO** | **none found** (public API, latent) |

`softether_reconnect` (`:3971–3994`) is a **third unlockable path**: it calls `softether_disconnect`
at `:3985` (which self-locks) and then `softether_connect_with_hub` at `:3989` **with no mutex held
by the caller**. It is declared in the public header (`softether_protocol.h:359`).

### 3.4 Kotlin/JNI callers of `nativeConnect`

- **Declaration only:** `SoftEtherClient.kt:226` — `external fun nativeConnect(...)`.
- **No Kotlin caller exists anywhere in the tree.** A full-tree search for `.nativeConnect(`
  (excluding `nativeConnectWithHub`) returns only the JNI definition and the Kotlin declaration.
- The production connect path uses **`nativeConnectWithHub`** exclusively:
  `ConnectionController.kt:588` and `:1266` (two call sites).
- Kotlin's `attemptReconnect()` (`ConnectionController.kt:1110`) is a **Kotlin-level** retry that
  re-invokes `nativeConnectWithHub`; it does **not** call the native `softether_reconnect`.

**Severity refinement:** N-16's *practical* exposure today is **latent**, not active — the offending
entry point (`nativeConnect`) has no production caller, and `softether_reconnect` has none either.
This does not make N-16 a non-defect: it is a **latent trap** that fires the moment any of the
following happens — a future caller uses `nativeConnect`, `softether_reconnect` is wired up, or the
concurrent path is reached via `additional_connect_thread_routine` (§3.5).

### 3.5 Shared state a concurrent connect would corrupt

If two connects on the same `conn` interleave, they contend for:

- **`conn->socket_fd`** — set at connect, closed at disconnect (`:2724–2729`). Two connects can
  double-set it, leaking one fd.
- **`conn->state`** — set `STATE_CONNECTING` at `:2350`; an interleaved disconnect sets
  `STATE_DISCONNECTING`/`STATE_DISCONNECTED`.
- **`conn->ssl` + `ssl_lifetime_lock`** — the SSL context is created/destroyed under
  `conn->ssl_lifetime_lock` (a **rdlock/wrlock pair**, `softether_protocol.h:231`). Two connects
  create two SSL contexts; the second overwrites the first pointer → leak, or the first is freed
  under the second's use.
- **`conn->rudp` / `conn->rudp_enabled`** — assigned in `_with_hub` (`:1578`, `:1597`, `:1499`).
- **`conn->additional[]` / `additional_connecting`** — the multi-connection slots. Note
  `softether_maintain_links:2993` already spawns a background thread
  (`additional_connect_thread_routine`) that touches `conn` concurrently with the main path; this is
  a second source of intra-connection concurrency independent of N-16.
- **`conn->server_ip`, `conn->username`, `conn->password`, `conn->hub_name`** — stored during
  connect; read by `softether_reconnect` (`:3976`, `:3989–3990`).

### 3.6 Explicit answer: can two `nativeConnect` calls interleave?

**Yes.** With no mutex on that path, two concurrent calls both enter
`softether_connect_with_hub` and execute its body concurrently on the same `conn`. **Practically,
this is not reachable today** because `nativeConnect` has no caller. The *same* interleaving is
reachable **right now** if a caller combines `nativeConnectWithHub` with `softether_reconnect`, or in
principle via the additional-connect background thread.

**Evidence class:** SOURCE CODE EVIDENCE for the lock asymmetry and the caller inventory;
INFERENCE for reachability.

---

## 4. T5-SLOT Attribution (S3)

### 4.1 The refusal path — exact code

The process-wide slot is `NativeLifetimeRegistry.process`. Admission is:

```kotlin
// NativeLifetimeRegistry.kt:213–214
fun isAdmissible(): Boolean = synchronized(lock) { activeAttemptId == NO_ATTEMPT }
```

The adapter's busy check:

```kotlin
// AndroidConnectionAdapter.kt:315–318
private fun isNativeSlotBusy(): Boolean {
    val registry = runCatching { NativeLifetimeRegistry.process }.getOrNull() ?: return false
    return !registry.isAdmissible() || registry.isBlocked()
}
```

`isNativeSlotBusy()` is read-only; it never acquires or forces anything. It returns `true` when
`activeAttemptId != NO_ATTEMPT` (ownership still held) **or** `isBlocked()`
(`:230–232`: held **and** `deadlineExpired`). Note the first clause alone is sufficient — the
deadline is not required.

The controller's terminal log:

```kotlin
// AutoModeController.kt:1473–1479
adapter.log(
    "[AUTO] Native slot still owned by an unresolved operation; " +
            "stopping without blaming this server",
)
setState(AutoModeState.Error(ERROR_NATIVE_UNRESOLVED))
```

### 4.2 THE ROOT CAUSE OF THE T5 OBSERVABILITY GAP — `describe()` is never called here

`NativeLifetimeRegistry.describe()` **already emits the holder's identity**:

```kotlin
// NativeLifetimeRegistry.kt:239–247
/** Human-readable summary for logs and refusal messages. */
fun describe(): String = synchronized(lock) {
    if (activeAttemptId == NO_ATTEMPT) {
        "idle"
    } else {
        val suffix = if (deadlineExpired) ", reporting deadline expired (still blocked)" else ""
        "attempt=$activeAttemptId phase=$activePhase$suffix"
    }
}
```

`describe()` is already used at several sites — `SoftEtherVpnService.kt:587` and `:601`
(`notifyNativeBusy(sessionPolicy.sessionGeneration, NativeLifetimeRegistry.process.describe())`),
`ConnectionController.kt:933`, `AndroidConnectionAdapter.kt:185`/`:195`.

**But the terminal T5 refusal at `AutoModeController.kt:1474` is a hard-coded string that does not
call `describe()`.** The reason it cannot: the value the controller has in hand at that point is the
bare enum `AttemptOutcome.NATIVE_BUSY` (`AutoModeController.kt:269`), which **carries no payload**.
The identity string does exist in the process — `notifyNativeBusy(sessionId, owner)` at
`SoftEtherVpnService.kt:237–241` receives it — but it is delivered to *listener callbacks*, not into
the `AttemptOutcome` that the controller logs.

**Therefore the T5-SLOT observability gap is a logging/plumbing gap, provably independent of N-15
and N-16.** The registry knew `attempt=<id> phase=<phase>` at the moment of refusal; the refusal log
simply never asked.

### 4.3 Candidate causes, enumerated with evidence class

| # | Candidate | Assessment | Evidence class |
|---|-----------|------------|----------------|
| **a** | **N-15:** a poll thread crashed/hung and the registry still believes the slot is held. | **NOT SUPPORTED.** The registry tracks a *Kotlin-side* lease (`activeAttemptId`), released only by `confirmTeardown` (`:190`) or `confirmTeardownWithoutLease` (`:205`). A crashed native poll thread would not prevent a Kotlin `confirmTeardown` — and T5 *does* show `Native cleanup confirmed (attempt=3, session=1)` at `09:18:10.051`, i.e. cleanup **did** eventually confirm. No SIGSEGV appears in the T5 window; the T7 crash scan for the whole phase was **zero**. | SOURCE + PHYSICAL DEVICE (negative) |
| **b** | **N-16:** a second `nativeConnect` reached native without the mutex, leaving an op in an odd state. | **NOT SUPPORTED IN T5.** T5's reconnect went through `nativeConnectWithHub` (the locked path). `nativeConnect` has no caller (§3.4). No evidence of a second connect on the same `conn`. | SOURCE + PHYSICAL DEVICE (negative) |
| **c1** | **Third cause — the teardown/cleanup of the *previous* attempt (attempt=2) had not confirmed when the *next* attempt tried to acquire.** | **MOST LIKELY.** T5's timeline shows exactly this shape: `09:17:45.663` "Native cleanup confirmed (attempt=2)" — then `09:17:47.853` a new connect starts — then `09:17:52.755` "Native slot busy". Between them, a **new** native operation had been admitted and had not yet confirmed. The holder is therefore most plausibly the **attempt-3 connect itself** (`attempt=3` is the one whose cleanup finally confirms at `09:18:10.051`), i.e. the slot was held by an operation *in progress*, not by a leak. | INFERENCE (from the logged attempt IDs) |
| **c2** | **Third cause — a `nativeConnectWithHub` JNI call that was blocked and never returned within the retry budget.** | **PLAUSIBLE, CONSISTENT WITH c1.** `nativeConnectWithHub` is explicitly documented as *"blocking JNI, not cancellable"* (`NativeLifetimeRegistry.kt:50`). The T5 retry budget is only ~4 s of retry delay. A connect attempt blocked in native beyond that budget leaves the lease outstanding by design — the registry deliberately does **not** clear ownership on a timeout (`:224–232`: *"a timeout is not evidence that a blocking JNI call returned"*). | SOURCE + INFERENCE |
| **c3** | **A genuinely leaked slot** (an operation that never confirms). | **NOT SUPPORTED.** T5 ends with `Native cleanup confirmed (attempt=3, session=1)`, and the design has exactly-once release (`confirmTeardown:190`, one-shot). No unconfirmed lease remains at the end of the window. | SOURCE + PHYSICAL DEVICE (negative) |

### 4.4 Attribution statement

**T5-SLOT is most likely (c1/c2): the slot was held by a legitimately in-flight native operation —
most plausibly the `nativeConnectWithHub` of the very attempt that was being admitted — and the
guard behaved exactly as designed by refusing to admit a second one.** It is **not** attributable to
N-15 or N-16 on the available evidence: both would have left a *crash* signature (N-15) or would
have required the *uncalled* `nativeConnect` path (N-16), and neither signature appears. The reason
T5-SLOT could not be *proven* is §4.2: the refusal log omits `describe()`.

**Per the brief, no root cause is declared.** The strongest statement the source supports is that
the most consistent hypothesis is a benign in-flight holder, and the only reason it is unprovable is
the missing identity in the log.

**Evidence class:** INFERENCE (attribution), grounded in SOURCE CODE EVIDENCE (the registry's
release semantics) and PHYSICAL DEVICE EVIDENCE (the T5 attempt-ID sequence).

---

## 5. F.28AL Interaction (S4)

**Confirmation: neither proposed fix weakens the fail-closed policy.**

- The F.28AL-U single-owner invariant lives **entirely in Kotlin**
  (`NativeLifetimeRegistry` + `NativeAttemptCoordinator` + `NativeHandleSlot`). It governs *when a
  Kotlin attempt may proceed*, via `tryAcquire()` → lease → `confirmTeardown()`.
- **N-15's fix is inside `softether_rudp.c`** — it synchronises the RUDP context's *own* lifetime
  among native threads. It neither reads nor writes any Kotlin state, and it does not change when
  `confirmTeardown` is called.
- **N-16's fix moves an existing mutex acquisition from one JNI caller into the shared callee.** The
  mutex (`conn->connect_mutex`) is already recursive and already held across the whole disconnect
  path (`:2698`–`:2827`). Relocating the lock **completes** the serialisation the F.28AL design
  *assumes*; it cannot weaken it.
- **Fail-closed relationship:** the policy's core rule is "ownership is retained unless native
  cleanup is positively confirmed" (`confirmTeardown` requires a matching lease;
  `confirmTeardownWithoutLease` is reserved for the pre-native case). Neither fix introduces a new
  release path or an early release. **No change to the release semantics is proposed.**

**One caution (INFERENCE):** if N-15's fix is implemented as a *blocking wait* inside
`rudp_destroy`, and `rudp_destroy` is called from `softether_disconnect` **while holding
`conn->connect_mutex`** (`:2816`), then a long wait would extend the `connect_mutex` hold. That does
**not** weaken fail-closed, but it could *increase* the duration for which a lease stays outstanding,
which would make T5-SLOT-style refusals **more** frequent, not less. This is the single most important
design constraint on D1 and is addressed by choosing the refcount shape (§6).

---

## 6. N-15 Fix Proposal (D1)

### 6.1 Chosen shape: **(b) atomic refcount on the context**

**Rejected (a) "add an ownership lock that destroy also takes":** `pthread_mutex_destroy` on a lock
that a reader may be *about to* acquire is inherently racy — the reader can acquire the lock between
the destroyer's `lock` and `destroy` calls. Closing this requires the destroyer to *know* no reader
is coming, which a lock alone cannot express. Also, the object is `free()`d, so a reader that wins
the race still dereferences freed memory to reach the lock.

**Rejected (c) "shutdown flag + signal + join the poll thread":** the RUDP context has **no dedicated
poll thread** in this codebase. `rudp_poll` is called *synchronously from arbitrary callers* — the
JNI receive path, `softether_send_data`, `softether_receive_data`, and `packet_handler.c:876`. There
is no thread handle to join. This option would require inventing a thread ownership model that does
not exist, i.e. a rewrite — explicitly out of scope.

**Chosen (b): atomic refcount**, because it matches the actual usage pattern (many short-lived
callers on arbitrary threads, no owned thread) and it is the only option that keeps `rudp_destroy`
**non-blocking in the common case** while still being sound.

### 6.2 Shape

Add to `rudp_context_t` (in `softether_rudp.h`):

```c
volatile int refcount;        /* guards the context's own lifetime */
volatile int destroying;      /* set before the final release; new entrants bail */
```

- `rudp_create` initialises `refcount = 1` (the creator's reference) and `destroying = 0`.
- Every public entry point (`rudp_poll`, `rudp_recv`, `rudp_send`, `rudp_is_send_ready`) performs a
  **guarded acquire at entry** and a **release at exit**:

  ```c
  /* acquire: must both re-check `destroying` and take a reference, atomically */
  if (!rudp_ref_acquire(ctx)) return;   /* destroying or null */
  ... existing body (including ctx->lock usage) ...
  rudp_ref_release(ctx);                /* drops the ref; frees if last + destroying */
  ```

- `rudp_destroy` becomes: set `destroying = 1`, `__sync_synchronize()`, then
  `if (__sync_sub_and_fetch(&ctx->refcount, 1) == 0) rudp_destroy_final(ctx);`
  where `rudp_destroy_final` does the existing `pthread_mutex_destroy` / EVP free / `close` /
  `free`.

This yields: destroy is **non-blocking** when no reader is active (the overwhelmingly common case,
and the one on the `connect_mutex`-holding disconnect path); when a reader *is* active, the last
reader to release performs the final free, so no thread ever waits and no thread ever touches freed
memory.

### 6.3 Functions that must change

| File | Function | Change |
|---|---|---|
| `softether_rudp.h` | `rudp_context_t` | add `refcount`, `destroying` fields |
| `softether_rudp.c` | `rudp_create` | init `refcount = 1`, `destroying = 0` |
| `softether_rudp.c` | `rudp_destroy` | become ref-dec + conditional final-free; add static `rudp_destroy_final` |
| `softether_rudp.c` | `rudp_poll` (`:693`) | guarded acquire / release |
| `softether_rudp.c` | `rudp_recv` (`:995`) | guarded acquire / release |
| `softether_rudp.c` | `rudp_send` (`:807`) | guarded acquire / release |
| `softether_rudp.c` | `rudp_is_send_ready` (`:471`) | guarded acquire / release |

**Note on the recursive lock and nesting:** `rudp_poll` → `rudp_poll_nolock` → `rudp_send_keepalive`
→ `rudp_send`. If `rudp_send` also takes a reference, the nesting takes **two** references on the
same thread and releases both on unwind — which is correct for a refcount (unlike a plain mutex, a
refcount need not be recursive). **No double-free risk**, because release only frees when the count
reaches zero *and* `destroying` is set.

### 6.4 Ordering contract (deadlock-freedom)

1. **Reference acquisition is lock-free** (atomic ops only) and therefore can never deadlock against
   `ctx->lock`.
2. **`refcount` is acquired *before* `ctx->lock` is taken**, and released *after* `ctx->lock` is
   released. This gives a strict order:
   **`refcount` (atomic) → `ctx->lock` (mutex)**. Never the reverse.
3. `rudp_destroy` takes **no** mutex at all — it only atomically decrements and, if it is the last
   holder, destroys. Because a thread that holds a reference guarantees `ctx->lock` is not destroyed
   while it uses it, `pthread_mutex_destroy` is now provably safe.
4. `conn->connect_mutex` (held by the disconnect caller) is **never acquired** by any RUDP function,
   so the existing lock order `connect_mutex → (rudp internals)` is preserved unchanged.

**The one residual hazard** (from §2.5) is the **stale fd** in `softether_receive_data`: even with a
perfect context refcount, the `poll()` at `:3417` may poll a descriptor that `rudp_destroy` closed.
Fixing that properly requires the *caller* to hold a reference across the `poll()` — i.e. a change in
`softether_protocol.c`, not in `softether_rudp.c`. **This is listed as an open question (§12).**

---

## 7. N-16 Fix Proposal (D2)

### 7.1 Chosen shape: **(c) move the mutex into `softether_connect_with_hub` itself**

**Why (c) over (a):** option (a) ("have `nativeConnect` take the mutex") fixes the *symptom* at one
call site while leaving the identical hole in `softether_reconnect` (`:3989`) and in any future
caller. It encodes a contract that must be remembered rather than enforced.

**Why (c) over (b):** option (b) ("route `nativeConnect` through a wrapper") is a special case of
(a) and inherits the same "remember the wrapper" weakness.

**Chosen (c):** because `softether_connect_with_hub` is the **single funnel** through which *all
three* entry points reach the connection logic:

```
nativeConnect              ─┐
nativeConnectWithHub       ─┼─→ softether_connect_with_hub  (the one true path)
softether_reconnect        ─┘        ↑
softether_connect  ──────────────────┘
```

Moving the lock into the callee closes the window for **every** current and future caller by
construction. It is also the **minimal** change: one lock/unlock pair added, one removed.

### 7.2 Exact mutex and scope

- **Mutex:** `conn->connect_mutex` — already `PTHREAD_MUTEX_RECURSIVE`
  (`softether_protocol.c:988–996`). **Recursivity is essential** and is why this fix is safe:
  `softether_connect_with_hub` calls `softether_disconnect` internally on error paths (`:2496`,
  `:2501`, `:2528`, `:2534`), and `softether_disconnect` **also** locks `connect_mutex`
  (`:2698`). Without recursivity this would self-deadlock; with it, the nested acquire is a no-op.
- **Scope:** lock at the very top of `softether_connect_with_hub` (after the null-argument guard at
  `:2331–2333`), unlock on **every** return path. Because the function has multiple `return`
  statements, the correct implementation is a single-exit refactor or a `goto done` pattern — this is
  the one place where the fix is larger than a two-line insert.
- **JNI side:** `nativeConnectWithHub` (`softether_jni.c:139`/`:145`) should **drop** its now-redundant
  lock/unlock, since the callee holds it for the same span. `nativeConnect` (`:77`) needs no change —
  it becomes safe automatically.

### 7.3 Deadlock analysis — no existing caller can deadlock

| Caller | Order of acquisition | Safe? |
|---|---|---|
| `nativeConnectWithHub` (after fix) | `connect_mutex` only (via callee) | **YES** — strictly less locking than before |
| `nativeConnect` (after fix) | `connect_mutex` (via callee) | **YES** — previously none; no lock held on entry |
| `softether_reconnect` | `softether_disconnect` (locks/unlocks `connect_mutex`) → returns → callee locks it again | **YES** — the two acquisitions are **sequential, not nested** (`:3985` completes before `:3989`) |
| `softether_connect_with_hub` internal disconnect on error | `connect_mutex` (held) → `connect_mutex` (re-acquire) | **YES — requires recursivity**, which is already configured |
| The `additional_connect_thread_routine` background thread | does not call `softether_connect_with_hub` | **YES** — unaffected |

**Lock-order preservation:** the existing order is `connect_mutex → write_mutex / io_mutex /
ssl_lifetime_lock`, documented at `softether_protocol.c:2741`. Moving `connect_mutex` to the
callee's entry keeps `connect_mutex` **strictly outermost** on the connect path, so the established
order is preserved and no cycle is introduced.

---

## 8. T5-SLOT Follow-on (D3)

### 8.1 Attribution

Per §4, T5-SLOT is **most likely a benign in-flight holder** (candidate c1/c2), and is **not**
attributable to N-15 or N-16 on the available evidence. The guard behaved as designed.

### 8.2 What would disambiguate — and the important finding

**The disambiguating data already exists.** `NativeLifetimeRegistry.describe()`
(`NativeLifetimeRegistry.kt:239–247`) already renders
`attempt=<id> phase=<phase>[, reporting deadline expired (still blocked)]`, and the registry already
exposes `isBlocked()` (`:230`), `activeAttemptId()` (`:217`), and `activePhase()` (`:220`).

The gap is **purely that `AutoModeController.kt:1474` logs a constant string**. To disambiguate
future occurrences, the exact minimum instrumentation is:

1. **Include the registry's identity in the terminal refusal log.** At `:1474`, append
   `NativeLifetimeRegistry.process.describe()` — exactly as `SoftEtherVpnService.kt:587`/`:601`
   already do. This alone would have named `attempt=` and `phase=` in the T5 log.
2. **Include the identity in the retry logs too** (`:1437`), so the *first* busy observation already
   carries the holder, not just the terminal one.
3. **Surface the holder through `AttemptOutcome`.** Today `NATIVE_BUSY`
   (`AutoModeController.kt:269`) is a payload-free enum, while `notifyNativeBusy(sessionId, owner)`
   (`SoftEtherVpnService.kt:237`) already carries the owner string to listeners. Carrying the owner
   into the outcome would make the identity available at the log site *structurally*, rather than by
   reaching back into the singleton.
4. **Optionally add elapsed-since-declared.** `describe()` currently emits neither a timestamp nor an
   elapsed duration. Adding `elapsedMs` since `activePhase` was last set would distinguish "started
   200 ms ago" (benign in-flight) from "started 40 s ago" (genuinely stuck).

**Per the brief, no registry code change is prescribed here.** Items 1–3 are *logging/plumbing* and
belong with a future instrumentation phase; item 4 is a registry enhancement. All are recorded as
open questions (§12).

### 8.3 Recommended sequencing for the follow-on

The T5-SLOT instrumentation should be **bundled with the physical-verification phase**
(`FIX-NATIVE-LIFETIME-SYNC-VERIFY`), because the question it answers ("what held the slot?") can only
be answered by reproducing T5 on a device. Adding the log lines in the same phase that re-runs T5
costs one build and gives a definitive answer.

---

## 9. Test Strategy (D4)

### 9.1 N-15 — native tests (`native_test.c`, no live server)

**N-15-A (device-runnable, no server): "destroy vs. poll, concurrent, bounded"**

- Create a `rudp_context_t` via `rudp_create(1)` (as N-08 does at `:3608`).
- Spawn **two** threads with the existing `pthread_create` pattern from
  `rudp_lock_probe_thread:3594`: thread A loops calling `rudp_poll(ctx)` for a fixed number of
  iterations; thread B calls `rudp_destroy(ctx)` after a short delay.
- Instrument the context with a test-only atomic "entered poll" / "exited poll" counter.
- **Success criterion:** no crash, and after B returns, A's `exited` count equals its `entered`
  count (i.e. no poll was cut off inside the critical region). Under the *unfixed* code this reliably
  reproduces a use-after-free on a device; under the fix it is clean.
- **Pre-fix expectation:** this test **must be expected to crash or fail** before the fix — that is
  what makes it a valid regression test. It should therefore be added **with** the fix, in the same
  commit, not before it.

**N-15-B (device-runnable, no server): "destroy is non-blocking when idle"**

- Create a context, do **not** start any poll, call `rudp_destroy(ctx)` and measure elapsed time.
- **Success criterion:** returns in well under a bound (e.g. < 50 ms). This pins the §6.2 property
  that destroy does not block in the common case — the property that keeps the
  `connect_mutex`-holding disconnect path fast.

**N-15-C (device-runnable, no server): "double-release safety"**

- Exercise the nesting `rudp_poll` → `rudp_send_keepalive` → `rudp_send` and verify the refcount
  returns exactly to baseline (expose the counter via a test-only accessor). Pins §6.3's claim that
  nesting is refcount-safe.

### 9.2 N-16 — native tests (`native_test.c`, no live server)

**N-16-A (device-runnable, no server): "concurrent connect serialisation"**

- This is harder to test hermetically because `softether_connect_with_hub` performs real I/O.
  **Recommended shape:** assert the *contract* rather than the behaviour — spawn two threads that
  both call a **stub-injected** connect path (or call `softether_connect_with_hub` against an
  unreachable address so it fails fast), and assert that the two executions do not overlap in time.
  A test-only "in-connect" flag (set on entry, cleared on exit, asserted-not-already-set) would
  **fail deterministically without the fix** and pass with it.
- **Success criterion:** the overlap counter is always zero.
- **Caveat:** this requires a test-only hook inside `softether_connect_with_hub`. If a hook is
  undesirable in production code, this test moves to the device phase (see 9.4).

**N-16-B (device-runnable, no server): "reconnect takes the same lock"**

- Drive `softether_reconnect` on a connection with no stored server info
  (`conn->server_ip[0] == '\0'`) so it returns `ERR_UNKNOWN` at `:3976` **before** the connect call.
  This verifies the early-return path is unchanged and does not deadlock. Limited value; it does not
  prove serialisation.

### 9.3 JVM tests

- **N-15:** **no meaningful JVM test.** The defect is entirely in C concurrency; there is no JVM
  surface. A JVM test would test nothing real. (Per the brief's "if applicable" — it is not.)
- **N-16:** **no meaningful JVM test**, for the same reason. The Kotlin layer already serialises
  connect/disconnect via the F.28AL-U lease; the defect is below that layer.
- **T5-SLOT instrumentation (if implemented):** **yes, a JVM test is applicable.** Assert that the
  terminal refusal log contains `attempt=` and `phase=` — extending the existing
  `AutoModeControllerTest.kt:1945` assertion, which today asserts the exact constant string at
  `:1945–1946`. This test would fail before the instrumentation and pass after.

### 9.4 Tests requiring the physical device (separate phase)

| Test | Why device-only |
|---|---|
| **T5-SLOT reproduction with enriched logging** | Requires the real Auto Mode → native → TUN stack and a real user skip. Verifies the §8 instrumentation actually names the holder. |
| **N-15-A under real timing** | The hermetic version is bounded-iteration; only a device gives realistic ART/NDK thread interleaving. |
| **N-16 end-to-end serialisation** | Requires a real connect against a real server (or a deliberately unreachable one) through `nativeConnectWithHub`, plus a concurrent `softether_reconnect` — only reproducible on-device. |
| **TUN receive path (the §2.5 window)** | The 200 ms `poll()` window in `softether_receive_data` only exists in the live TUN receive loop. |

**Device-phase test budget note:** the RESUME phase showed a 3-AutoConnect-run cap is realistic.
The N-15-A and N-16-A hermetic tests do **not** consume AutoConnect runs (they are direct native
calls), so they can run freely; only the T5-SLOT reproduction consumes a run.

---

## 10. Blast Radius (D5)

| Fix | Files changed | Approx. line delta | Outside `SoftEtherClient/src/main/cpp/**`? |
|---|---|---|---|
| **N-15** | `softether_rudp.h` (+2), `softether_rudp.c` (~+55/−12: refcount helpers, 4 entry points guarded, `rudp_destroy` split) | ~+60/−15 | **NO** — both files are under `SoftEtherClient/src/main/cpp/softether-core/` |
| **N-16** | `softether_protocol.c` (lock/unlock + single-exit refactor of a ~365-line function, ~+15/−8), `jni/softether_jni.c` (remove ~2 lines) | ~+17/−10 | **NO** — both under `SoftEtherClient/src/main/cpp/` |
| **N-15 tests** | `test/native_test.c` (~+120, three tests) | ~+120 | **NO** — under `SoftEtherClient/src/main/cpp/test/` |
| **N-16 tests** | `test/native_test.c` (~+60) | ~+60 | **NO** — same |
| **T5-SLOT instrumentation** *(not prescribed here)* | `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt` (~+6) | ~+6 | **YES** — `app/` module, Kotlin |

**Total N-15 + N-16 + their tests: ~+197/−25, entirely inside `SoftEtherClient/src/main/cpp/**`.**

**F.28AL single-owner invariant impact: NO (reasoned).**
Neither fix touches `NativeLifetimeRegistry`, `NativeAttemptCoordinator`, `NativeHandleSlot`, or any
Kotlin file. N-15 changes only intra-RUDP-context synchronisation; N-16 relocates an existing
recursive mutex acquisition. The invariant's enforcement points (`tryAcquire`, `confirmTeardown`,
`confirmTeardownWithoutLease`) are untouched, and no new release path is introduced (§5).

**Secondary blast radius (INFERENCE):** the `softether_connect_with_hub` single-exit refactor is the
highest-risk part of N-16 — a missed return path would leak `connect_mutex` and wedge every
subsequent connect **and** disconnect on that connection. It needs dedicated review and a test that
exercises at least one early-return/error path.

---

## 11. Sequencing (D6)

**Recommendation: ONE phase, but implemented as two ordered, separately-reviewable commits.**

**Justification:**
- The two defects are **independent in mechanism** (RUDP context lifetime vs. connect serialisation)
  but **share a verification surface**: both are only *conclusively* validated by the same
  device-phase N-15-A/N-16-A concurrency tests, and both touch the same module.
- Splitting into two phases would double the build/verify overhead for no isolation benefit, since
  neither fix's correctness depends on the other.
- However, they should **not** be a single commit: N-16's mutex relocation carries the
  "leaked mutex on a missed return path" risk (§10), which should be independently revertible
  without disturbing the RUDP refcount work.

**Proposed order within the phase: N-15 first, then N-16.** Reason: N-15 is the strictly higher-risk
defect (a real use-after-free on the live TUN receive path, reachable via T5-like flows), whereas
N-16 is currently **latent** (no production caller — §3.4). Fixing the live defect first means that if
the phase is interrupted, the valuable fix has landed.

**Proposed phase names:**

| Purpose | Name |
|---|---|
| Implementation (both fixes + hermetic tests) | **`FIX-NATIVE-LIFETIME-SYNC-IMPL`** |
| Physical verification + T5-SLOT instrumentation | **`FIX-NATIVE-LIFETIME-SYNC-VERIFY`** |

---

## 12. Open Questions (D7)

Each item requires a user decision before implementation.

1. **Does the N-15 fix need to cover the stale-fd hazard at `softether_receive_data:3417`?**
   The context refcount protects the `rudp_context_t` object but **not** the UDP fd that
   `rudp_destroy` closes at `rudp_destroy:187`. Fixing that requires the *caller* to hold a RUDP
   reference across the 200 ms `poll()` — i.e. changes in `softether_protocol.c` beyond the strict
   `softether_rudp.c` scope. **Decision: in scope for N-15, or deferred as a separate defect?**

2. **Is `volatile int` + `__sync_*` the sanctioned atomic style here, or should `C11 <stdatomic.h>`
   be used?** The codebase already uses `__sync_bool_compare_and_swap` (`softether_protocol.c:51`)
   and `__sync_synchronize` (`:2710`), so GCC builtins are precedented. **Confirm** so the new code
   matches house style rather than introducing a second idiom.

3. **Should `rudp_destroy` remain externally callable with the same signature?** Option (b) turns it
   into an unref, which changes its meaning ("release my reference") rather than "destroy now". All
   15 test call sites treat it as "destroy now" and would still work (creator's reference is 1), but
   the semantic change should be **explicitly accepted**.

4. **For N-16, may the `softether_connect_with_hub` body be refactored to single-exit?** This is the
   safest implementation but touches ~365 lines of control flow. **Alternative** (lower blast radius,
   slightly less clean): wrap the existing body in a static inner function and have the public
   function do lock → call → unlock. **Which is preferred?**

5. **May a test-only concurrency hook be added for N-16-A?** §9.2's N-16-A needs an "in-connect" flag
   inside `softether_connect_with_hub` to assert non-overlap. If production hooks are undesirable,
   N-16 verification moves entirely to the device phase. **Decision needed.**

6. **Should the T5-SLOT instrumentation (D3 §8.2 items 1–3) be included in the IMPL phase or the
   VERIFY phase?** Recommended: VERIFY (it can only be validated by reproducing T5), but it is a
   Kotlin change in `app/` — which the brief's "do not propose changes beyond the strict minimum"
   rule may place out of scope for the native phase. **Decision needed.**

7. **Is the `additional_connect_thread_routine` background thread (`softether_maintain_links:2993`)
   in scope?** It is a second source of intra-connection concurrency that `connect_mutex` does **not**
   currently cover (it spawns its own thread and touches `conn`). It is **out of N-16's stated scope**,
   but it deserves a decision: is it covered elsewhere, or does it warrant its own finding?

---

## 13. Evidence Taxonomy

- **SOURCE CODE EVIDENCE** — all line anchors and code quotations in §2, §3, §4.1–4.2, §5, §6.3,
  §7.2; the absence of locking in `rudp_destroy`; the lock asymmetry between the two JNI connect
  entry points; the fact that `nativeConnect`/`softether_reconnect` have no production caller; the
  fact that `describe()` already emits `attempt=`/`phase=`; the recursive-mutex configuration of both
  `ctx->lock` and `conn->connect_mutex`.
- **INFERENCE** — runtime probability of the N-15 window (§2.6); reachability of N-16 (§3.6); the
  T5-SLOT attribution (§4.3–4.4); the risk that a blocking N-15 fix would worsen T5-SLOT frequency
  (§5); the "missed return path leaks the mutex" risk (§10).
- **PHYSICAL DEVICE EVIDENCE** — the T5 attempt-ID sequence and the absence of any crash signature in
  the T5 window (T7 scan, zero crashes) used to **exclude** candidates (a) and (c3).
- **UNKNOWN** — the actual identity of the T5 slot holder (unprovable from the existing log, by
  construction — §4.2); the real interleaving that produced the T5 timing; whether the
  `additional_connect_thread_routine` path contributed to T5.

---

## 14. Explicit No-Change Confirmation

Source: **NONE.** Test: **NONE.** Gradle: **NONE.** CMake: **NONE.** Native: **NONE.** Device:
**NONE.** APK: **NONE.** CI: **NONE.** Commit: **NONE.** Push: **NONE.** Tag: **NONE.**

**Files edited: NONE.** This phase was read-only; all findings are from source inspection at HEAD
`dc8e113`. No build was run.

---

## 15. Final Recommendation

```
PROCEED TO FIX-NATIVE-LIFETIME-SYNC-IMPL (N-15 first)
```

**Rationale:** N-15 is a genuine, reachable use-after-free on the live TUN receive path — the highest
-severity item in the Active Fix Queue — and its fix shape is now fully determined (atomic refcount,
§6). N-16 is confirmed but currently latent (no production caller), so it can follow in the same
phase as a second, independently revertible commit (§11). T5-SLOT is **not** a third lifetime defect:
it is an observability gap whose fix already exists in `describe()` (§4.2, §8.2), and it is best
resolved during the VERIFY phase where T5 can actually be reproduced.

**Two decisions would strengthen this plan before implementation begins:** open questions **#1**
(stale-fd scope) and **#4/#5** (N-16 refactor shape and test hook), since both change the blast
radius materially.
