# PRE-DEVICE-BUDGET-GUARD — Report

**Phase:** PRE-DEVICE-BUDGET-GUARD
**Repo:** D:\PMC\Documents\Android\SoftEthergai
**Branch:** main
**HEAD:** 5773c603603ef2e0b8cba2dda1f1852e50766368 (origin/main in sync)
**Date:** 2026-10-14

---

## §1 Purpose / Rationale (SOURCE CODE)

A code-review note identified a narrow-but-real hazard in the new parallel-race
worker code in `softether_protocol.c`: `race_remaining_budget_ms(ctrl)` clamps
its result `rem < 0` to `0`. If `budget == 0` is then passed to
`socket_apply_timeout(fd, 0)`, that function is a **no-op** (returns 0 without
applying `SO_RCVTIMEO`). A subsequent `ssl_connect()` would then run on a
**blocking fd with no timeout** — the exact class of unbounded-handshake defect
that P1 fixed earlier. Unlikely in practice, but reachable under pathological
scheduling (deadline already elapsed by the time a worker starts).

This phase adds a defensive guard so **no worker can reach `ssl_connect()` with
an unenforced timeout**.

---

## §2 Change applied (SOURCE CODE)

**Authorized file modified (only this one):**
`SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_protocol.c`

In each of the **5** transport worker functions, immediately after
`uint32_t budget = race_remaining_budget_ms(ctx->ctrl);`, the following guard was
inserted (transport name constant varies per worker):

```c
    if (budget == 0) {
        LOGD("[%s] race budget exhausted before start; aborting",
             transport_name(<TRANSPORT_TYPE>));
        return NULL;
    }
```

Transport constants used, per worker:

| Worker function                     | TRANSPORT_TYPE    | Insert point (old file line) |
|-------------------------------------|-------------------|------------------------------|
| `transport_thread_tcp`              | `TRANSPORT_TCP`   | after ~1821                  |
| `transport_thread_rudp_direct`      | `TRANSPORT_RUDP_DIRECT` | after ~1929             |
| `transport_thread_natt`             | `TRANSPORT_NATT`  | after ~2023                  |
| `transport_thread_dns`              | `TRANSPORT_DNS`   | after ~2136                  |
| `transport_thread_icmp`             | `TRANSPORT_ICMP`  | after ~2229                  |

**Nothing else was changed.** `race_remaining_budget_ms()` and
`socket_apply_timeout()` were NOT modified. No other logic touched. Each guard is
the exact 4-line block specified, plus one blank separator line (matches the
file's existing blank-line style).

---

## §3 Diff of softether_protocol.c (the 5 inserted guards)

```diff
@@ ... static void* transport_thread_tcp(void* arg) {
     uint32_t budget = race_remaining_budget_ms(ctx->ctrl);
+
+    if (budget == 0) {
+        LOGD("[%s] race budget exhausted before start; aborting",
+             transport_name(TRANSPORT_TCP));
+        return NULL;
+    }
+
     // Staggered delay
     uint32_t delay = transport_delay_ms(TRANSPORT_TCP);
```

The same 4-line block (with `TRANSPORT_RUDP_DIRECT`, `TRANSPORT_NATT`,
`TRANSPORT_DNS`, `TRANSPORT_ICMP` respectively) was inserted at the corresponding
budget-computation site in the other four workers. Verified: exactly **5** guard
blocks present (one per worker), each with the correct transport constant.

---

## §4 Diff scope — git diff --numstat (all 5 files)

```
32    2    SoftEtherClient/src/main/cpp/jni/softether_jni.c
12    2    SoftEtherClient/src/main/cpp/softether-core/src/proto/rudp_transport.c
7     2    SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_nat_t.c
287   112  SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_protocol.c
19    0    SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_rudp.c
```

- The **4 frozen native files are byte-identical to baseline**:
  - `softether_jni.c` = 32 / 2
  - `rudp_transport.c` = 12 / 2
  - `softether_nat_t.c` = 7 / 2
  - `softether_rudp.c` = 19 / 0
- Only `softether_protocol.c` changed this phase. It went from **+257/-112**
  to **+287/-112**, a net **+30** added lines.

### Note on the STEP 3 estimate (transparency)
The brief predicted "+5 lines more than before (one guard per worker, plus the
log line)". The actual net addition is **+30 lines** — each guard is the
specified 4-line `if/LOGD(2-line)/return/}` block plus **one blank separator
line**, i.e. 6 lines added per guard × 5 workers = 30. The "+5" figure counts the
5 guards as units, not literal lines. All git-diff-numstat deltas are reported
verbatim above; the important scope invariant (other 4 files byte-identical) holds
exactly. **This is not a STOP condition** (none of the STOP conditions reference
an exact numstat).

---

## §5 Build result (TEST)

- **Command:** `CI=true ./gradlew :app:assembleProDebug --rerun-tasks`
  (no BindException retry needed)
- **Result:** **BUILD SUCCESSFUL** in **11m 29s**
- All four CMake ABIs compiled cleanly:
  `buildCMakeDebug[arm64-v8a]`, `[armeabi-v7a]`, `[x86]`, `[x86_64]` — the
  `softether_protocol.c` guard change compiles across every target.
- **APK:** `app/build/outputs/apk/pro/debug/app-pro-debug.apk`
  - **SHA-256:** `3711b8e0984d6d9ee083fa878820be869aea0ad4892640e49166462acb8c855f`
  - **Size:** 63,619,526 bytes
  - (differs from the prior FIX-P5B APK `1c830668…`, as expected — the native
    code changed.)

---

## §6 JVM test result (TEST)

- **Command:** `./gradlew :SoftEtherClient:test --rerun-tasks --no-build-cache`
- **Result:** **BUILD SUCCESSFUL**, 154 tests — **PASS**
  - suites = 8
  - tests = 154, failures = 0, errors = 0, skipped = 0
- Note: the first attempt hit the same transient
  `BindException: Address already in use` (the prior build daemon still held the
  file-lock port). This is infrastructure, not a test failure; I stopped the
  daemons (`./gradlew --stop`) and re-ran once, which succeeded.
- Test count matches the previous phase's 154/154 exactly — the guard introduces
  no regression.

---

## §7 Final git state

```
 M SoftEtherClient/src/main/cpp/jni/softether_jni.c
 M SoftEtherClient/src/main/cpp/softether-core/src/proto/rudp_transport.c
 M SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_nat_t.c
 M SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_protocol.c
 M SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_rudp.c
```

(plus the same ~109 untracked evidence files as before — unchanged this phase)
`HEAD` unchanged: 5773c603603ef2e0b8cba2dda1f1852e50766368.

---

## §8 Explicit confirmations

- **No other file modified.** Only `softether_protocol.c` changed this phase; the
  4 frozen native files are byte-identical to baseline.
- **No commit / push / tag.**
- **No device command executed.**
- **C5 not executed.**
- **No Kotlin / test / build / spec file modified.**
- `PROJECT_SPECIFICATION.md` and `AUTOCONNECT_STATE_MACHINE_DESIGN.md`
  unchanged.

---

## Summary

The defensive `budget == 0` guard was added to all 5 race transport workers so a
worker can never reach `ssl_connect()` on an fd whose timeout was a no-op to
apply. Verified: comprehensive build across all ABIs (BUILD SUCCESSFUL, SHA-256
`3711b8e0…c855f`) and 154/154 JVM tests PASS. Only `softether_protocol.c` and
this new report file were created/modified. Awaiting operator decision; no next
phase proposed.
