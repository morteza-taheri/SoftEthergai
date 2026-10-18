# FIX-NATIVE-LIFETIME-SYNC-IMPL — Report

**Phase:** FIX-NATIVE-LIFETIME-SYNC-IMPL
**Date:** 2026-10-13
**Base HEAD (pre-phase):** `dc8e113c36b6c9595fe3a768089d29d980a80407`
**Resulting HEAD:** `b0418cc2a06b2785387c66f35fd7c7085d10b24e`
**Verdict:** `FIXES APPLIED — COMPILE VERIFIED`
**Recommendation:** `PROCEED TO FIX-NATIVE-LIFETIME-SYNC-VERIFY (device)`

---

## 1. Scope and Objective

Implement the two DEEP-REVIEW P1_HIGH native lifetime defects identified in
`FIX_NATIVE_LIFETIME_SYNC_DESIGN_REPORT.md` as **two ordered, independently
revertible local commits**:

* **Commit 1 (N-15)** — atomic reference count on `rudp_context_t` to close the
  destroy-vs-poll use-after-free.
* **Commit 2 (N-16)** — relocate `conn->connect_mutex` into
  `softether_connect_with_hub` itself so no caller can bypass it.

No push, no tag, no Kotlin/Java beyond the strict test-declaration minimum, no
device, no CI trigger. Forced native compile at the end.

---

## 2. Pre-conditions (P1–P4) — all PASS

| ID | Check | Expected | Observed | Verdict |
|----|-------|----------|----------|---------|
| P1 | `git rev-parse HEAD` | `dc8e113c…` | `dc8e113c36b6c9595fe3a768089d29d980a80407` | PASS |
| P2 | Read design report in full | done | read (759 lines) | PASS |
| P3a | `pthread_mutex_destroy(&ctx->lock)` anchor | `:183` | `183:    pthread_mutex_destroy(&ctx->lock);` | PASS |
| P3b | JNI `connect_mutex` lock/unlock | `:139` / `:145` | `139: lock`, `145: unlock` | PASS |
| P4 | `rudp_lock_probe_thread` reusable pattern | near `:3594` | struct ends `:3592`; thread fn `:3594` | PASS |

Tracked working tree was clean before the first edit, so the two commits are
the only changes in the phase.

---

## 3. User Decisions Honoured (Q1–Q7)

| Q | Decision | How implemented |
|---|----------|-----------------|
| Q1 | Stale-fd hazard at `softether_receive_data:3417` **out of scope** | receive path untouched; recorded as a future finding in the commit-1 message |
| Q2 | Use `__sync_*` builtins, **not** C11 `<stdatomic.h>` | `__sync_bool_compare_and_swap`, `__sync_sub_and_fetch`, `__sync_synchronize` only |
| Q3 | `rudp_destroy` semantic change ("release reference") **accepted** | `rudp_destroy` now sets `destroying` + releases; documented at the header decl |
| Q4 | N-16 **wrapper** approach, not single-exit refactor | body renamed to `_nolock` verbatim; new locking public wrapper added |
| Q5 | N-16-A test hook: use `volatile int`/`#ifdef`/weak; fall back to `HOOK_DEFERRED` | **No hook needed** — the test proves lock ownership directly via the visible `conn->connect_mutex` struct field. Test-only hook not added; `HOOK_DEFERRED` not applicable |
| Q6 | T5-SLOT instrumentation **deferred** to VERIFY | no instrumentation added in this phase |
| Q7 | `additional_connect_thread_routine` **out of scope** | not touched |

---

## 4. Commit 1 — N-15: `rudp_context_t` reference counting

**Commit:** `3a2f442365334f5103a719def63a3fdb93fac959`
**Message subject:** `fix(native): refcount rudp_context_t to close destroy-vs-poll use-after-free`
**Files:** 5 changed, +347 / −8

### 4.1 `softether_rudp.h`
* Added to `rudp_context_t` at the top, in a comment block stating the lifetime
  contract and the "reference before lock, release after unlock" ordering rule:
  * `volatile int refcount;` — outstanding references (1 = creator only)
  * `volatile int destroying;` — set before the final release; refuses new acquires
* Documented the new `rudp_destroy()` semantics at the API declaration.

### 4.2 `softether_rudp.c`
* Three static helpers added after `calc_key` (defined before every use):
  * `rudp_destroy_final(ctx)` — the **original** body of `rudp_destroy`
    (`pthread_mutex_destroy`, EVP frees, `close(udp_fd)`, `free`).
  * `rudp_ref_acquire(ctx)` — CAS loop; returns 0 on `NULL`; returns 0 if
    `destroying`; `__sync_bool_compare_and_swap(refcount, old, old+1)`; retries on
    CAS failure. Fail-closed.
  * `rudp_ref_release(ctx)` — `__sync_sub_and_fetch(refcount, 1) == 0` →
    `rudp_destroy_final(ctx)`.
* `rudp_create`: sets `refcount = 1`, `destroying = 0` immediately after
  `calloc` (explicit, so the invariant is visible at construction).
* `rudp_destroy`: now `if (ctx == NULL) return; ctx->destroying = 1;
  __sync_synchronize(); rudp_ref_release(ctx);` — no lock, no `mutex_destroy`,
  no `free`.
* All four entry points guarded (`acquire` at top, `release` before return):
  * `rudp_is_send_ready` (`:551`) — single exit, balanced.
  * `rudp_poll` (`:778`) — single exit, balanced. **This is the races window.**
  * `rudp_send` (`:906`) — single exit, balanced.
  * `rudp_recv` (`:1097`) — single `out:` exit, balanced.
* Each entry point carries a comment documenting the ordering contract.

### 4.3 Nesting verification (I2f)
`rudp_poll` → `rudp_poll_nolock` → `rudp_send_keepalive` → `rudp_send` takes
**two** references on one thread (one for `rudp_poll`, one for the nested
`rudp_send`) and releases both on their own returns. The count is per-call, not
per-thread. No double-release path exists: every `acquire` has exactly one
matching `release` on the same return path (verified by the grep audit in §7).

### 4.4 Tests (commit 1)
`native_test.c`, `native_test.h`, `test_jni_bridge.c` — all hermetic (no server):
* **N-15-A** `test_rudp_destroy_during_poll` — a worker loops in `rudp_poll`
  while the main thread calls `rudp_destroy` mid-poll; asserts the worker
  survives and keeps polling (a use-after-free would abort the process).
* **N-15-B** `test_rudp_destroy_is_idempotent` — asserts `refcount == 1` and
  `destroying == 0` at birth and that a single `rudp_destroy` returns cleanly.
* **N-15-C** `test_rudp_ref_balance` — 200 rounds of all four entry points;
  asserts `refcount == 1` afterwards (no leak, no early free).

---

## 5. Commit 2 — N-16: `connect_mutex` moved into the protocol layer

**Commit:** `b0418cc2a06b2785387c66f35fd7c7085d10b24e`
**Message subject:** `fix(native): hold connect_mutex in softether_connect_with_hub itself (N-16)`
**Files:** 6 changed, +308 / −7

### 5.1 `softether_protocol.c` (I5a–I5c)
* The existing `softether_connect_with_hub` body was renamed to
  **`static int softether_connect_with_hub_nolock(...)`** — signature and the
  leading comment changed only; all 365 lines of control flow preserved.
* A **new public `softether_connect_with_hub`** was added immediately before
  `softether_disconnect`:
  * keeps the original `if (conn == NULL) return ERR_UNKNOWN;` guard (moved to
    the wrapper, since the lock dereferences `conn`),
  * `pthread_mutex_lock(&conn->connect_mutex)`,
  * calls `softether_connect_with_hub_nolock(...)`,
  * `pthread_mutex_unlock(&conn->connect_mutex)`, returns the result.
* A comment documents why the lock lives here, the recursive-mutex rationale,
  and the `connect → write` lock order.

### 5.2 `softether_jni.c` (I6a/I6b)
* Removed the redundant `pthread_mutex_lock/unlock(&conn->connect_mutex)` around
  the call in `nativeConnectWithHub`, replaced with the comment
  *"connect_mutex is now held by softether_connect_with_hub itself; see N-16."*
* `nativeConnect` (`:54`/`:77`) **not modified** — it calls `softether_connect`,
  which now reaches the lock through the public wrapper.

### 5.3 Coverage achieved
| Path into the connect code | Before | After |
|---|---|---|
| JNI `nativeConnectWithHub` | locked (JNI) | locked (protocol) |
| `softether_connect` (from `nativeConnect`) | **unlocked** | locked |
| `softether_reconnect` | **unlocked** | locked |

### 5.4 Tests (commit 2)
* **N-16-A** `test_softether_connect_with_hub_takes_lock` — holds
  `conn->connect_mutex` on the test thread, then calls the public
  `softether_connect_with_hub` from a worker and asserts it does **not**
  complete while the lock is held but does complete after release. This proves
  the public entry point itself acquires the lock, with no production hook.
* **N-16-B** `test_reconnect_no_info_returns` — `softether_reconnect` on a
  connection with no stored server info returns `ERR_UNKNOWN` without
  deadlocking.
* Kotlin `NativeConnectionTest.kt` — 5 new `external` declarations (3 for N-15,
  2 for N-16) and 5 new `@Test` methods (tests 37–41). **This is the minimum
  required to register the new hermetic tests**, not a behavioural change.

---

## 6. Post-build Verification

### V1 — forced four-ABI native compile — **PASS**

Command:
```
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" CI=1 \
GRADLE_OPTS="-Dorg.gradle.internal.launcher.localhost=127.0.0.1 \
             -Dorg.gradle.internal.remote.internal.inet.tcp.port=0" \
./gradlew :SoftEtherClient:externalNativeBuildDebug --no-daemon --rerun-tasks
```

Result:
```
BUILD SUCCESSFUL in 28s
9 actionable tasks: 9 executed
```

* All 9 tasks **executed** (not UP-TO-DATE) — the cache was genuinely defeated.
* Rebuilt artifacts (all four ABIs, both libraries):
  `libsoftether.so` and `libsoftether_test.so` under
  `SoftEtherClient/build/intermediates/cxx/Debug/5v273202/obj/{arm64-v8a,
  armeabi-v7a, x86, x86_64}/`.
* **Zero** warnings or errors from `softether_rudp.c`, `softether_protocol.c`,
  or `softether_jni.c`.
* The only diagnostics are three **pre-existing** harness warnings in
  `native_test.c` (`stress_fd_generation` unused, `stress_unused_rt_view`
  unused, `suspicious` set-but-unused at `:1874`) — all long-standing, none
  from this phase's additions.

### V2 — native unit tests — **NOT APPLICABLE (androidTest-only)**

`SoftEtherClient/src/test` exists, but no JVM test loads the native library
(the single `System.loadLibrary` mention in `SoftEtherClientHandleGuardTest.kt`
is inside a comment). The native tests are **androidTest-only** and therefore
must run on a device. Per the brief: noted, and verification stops here.

---

## 7. Correctness Audit

Reference-balance audit (commit-1 state):

| Entry point | `acquire` | `release` | `lock` | `unlock` | Balanced |
|---|---|---|---|---|---|
| `rudp_is_send_ready` | 1 (`:554`) | 1 (`:558`) | 1 (`:555`) | 1 (`:557`) | yes |
| `rudp_poll` | 1 (`:784`) | 1 (`:788`) | 1 (`:785`) | 1 (`:787`) | yes |
| `rudp_send` | 1 (`:906`) | 1 (`:910`) | 1 (`:907`) | 1 (`:909`) | yes |
| `rudp_recv` | 1 (`:1097`) | 1 (`:1101`) | 1 (`:1107`) | 1 (`:1129`) | yes |

* `rudp_destroy` never holds `ctx->lock` — it only flips a flag and decrements
  an atomic, so it cannot deadlock against the recursive mutex.
* `rudp_destroy_final` runs only at count zero, i.e. after every in-flight
  entry point has returned, so it needs no lock (asserted in its comment).
* Brace balance: all touched C files delta = 0. `NativeConnectionTest.kt`
  `@Test` count = 41 (36 pre-existing + 5 new).
* Function definitions: exactly one `softether_connect_with_hub_nolock`
  (static, `:2332`) and one public `softether_connect_with_hub` (`:2709`).
* The only `connect_mutex` token left in `softether_jni.c` is inside the N-16
  comment.

---

## 8. Repository State

| Item | Value |
|---|---|
| HEAD | `b0418cc2a06b2785387c66f35fd7c7085d10b24e` |
| Parent | `3a2f442` (commit 1) |
| Commit 1 | `3a2f442365334f5103a719def63a3fdb93fac959` |
| Base (pre-phase) | `dc8e113c36b6c9595fe3a768089d29d980a80407` |
| Tracked working tree | clean |
| `origin/main` | `5c13603c6276c11460982e0633203667d26ef3e7` (unchanged) |
| New tags | none |
| Reflog | exactly the two `commit:` entries; no push |
| F.28AL-U single-owner slot | untouched |
| `NativeLifetimeRegistry` / Kotlin lifetime components | untouched |
| `CMakeLists.txt` / `*.gradle` | untouched |

---

## 9. Blast Radius (as implemented)

| File | Commit | Nature |
|---|---|---|
| `softether-core/include/softether_rudp.h` | 1 | 2 fields + comments |
| `softether-core/src/proto/softether_rudp.c` | 1 | helpers + 4 guards |
| `test/native_test.c` | 1 & 2 | N-15-A/B/C, N-16-A/B |
| `test/native_test.h` | 1 & 2 | declarations |
| `test/test_jni_bridge.c` | 1 & 2 | bridge exports |
| `softether-core/src/proto/softether_protocol.c` | 2 | rename + wrapper |
| `jni/softether_jni.c` | 2 | remove redundant lock |
| `androidTest/.../NativeConnectionTest.kt` | 2 | 5 decls + 5 tests |

No file outside `SoftEtherClient/src/main/cpp/**` (plus the mandatory
androidTest Kotlin registration) was touched. **The F.28AL single-owner
invariant is unaffected**: neither fix alters the registry, the handle slot, or
the acquire/confirm-teardown protocol; they only change the internal lifetime
of a RUDP context and the ownership of an already-existing mutex.

---

## 10. Interaction with the F.28AL Single-Owner Protocol (§S4)

* **N-15** makes `rudp_destroy` a deferred release. It does not change when
  `softether_disconnect` is called nor when teardown is *confirmed* to the
  registry — the registry still sees the same teardown sequence, just with the
  final `free` happening after the last in-flight poll rather than during it.
  Fail-closed policy is preserved and strengthened (a racing caller is now
  explicitly refused).
* **N-16** makes the connect path hold `connect_mutex` for its full duration.
  Because the mutex is recursive and was already held by the JNI caller for the
  same duration, the observable window is unchanged for `nativeConnectWithHub`;
  it merely extends the same protection to the two previously-unguarded paths.
  The registry's single-owner acquisition happens *outside* the native connect
  call, so it is unaffected.

---

## 11. Open Items / Follow-ups (not in this phase)

* **Q1 recorded:** stale-fd window at `softether_receive_data:3417` — `conn->rudp`
  is read at `:3404`, blocks in `poll()` at `:3417`, and is re-dereferenced at
  `:3422`/`:3424`. Separate finding; do not change the receive path here.
* **Q6 deferred:** T5-SLOT instrumentation belongs to
  `FIX-NATIVE-LIFETIME-SYNC-VERIFY`.
* **Q7 recorded:** `additional_connect_thread_routine` (`softether_protocol.c:2993`)
  is a second intra-connection concurrency source, out of scope for N-16.
* **Device verification:** N-15-A/B/C and N-16-A/B have only been
  **compile-verified** here. They must run on the device in the VERIFY phase.

---

## 12. Constraints Compliance

| Constraint | Status |
|---|---|
| Two ordered commits | yes (`3a2f442` → `b0418cc`) |
| Forced compile at end | yes (BUILD SUCCESSFUL, 9/9 executed) |
| No push | yes (`origin/main` unchanged) |
| No tag | yes |
| No Kotlin/Java beyond the strict test-declaration minimum | yes (5 decls + 5 tests only) |
| No device | yes |
| No CI trigger | yes |
| No `CMakeLists.txt` / `*.gradle` change | yes |
| Fail-closed policy not weakened | yes (strengthened) |
| `NativeLifetimeRegistry` / F.28AL-U slot untouched | yes |

---

## 13. Verdict and Recommendation

**Verdict:** `FIXES APPLIED — COMPILE VERIFIED`

Both P1_HIGH native lifetime defects are implemented as two ordered,
independently-revertible local commits, and the forced four-ABI native compile
succeeds with all tasks executed. N-15-A/B/C and N-16-A/B are written and
registered but have run only through the compiler — they require a device.

**Recommendation:** `PROCEED TO FIX-NATIVE-LIFETIME-SYNC-VERIFY (device)`

That phase should: install the rebuilt app + androidTest APKs, run the five new
hermetic tests (37–41) plus the existing RUDP/lifetime tests, and add the
deferred T5-SLOT instrumentation (Q6). The F.28AL-U slot and the registry
remain untouched and must still be validated by the device run.
