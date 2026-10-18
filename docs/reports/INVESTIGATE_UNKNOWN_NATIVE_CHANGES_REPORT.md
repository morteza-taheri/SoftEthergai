# Investigation: Unknown Native Changes

**Phase:** `INVESTIGATE-UNKNOWN-NATIVE-CHANGES`
**Repository:** `D:\PMC\Documents\Android\SoftEthergai`
**Branch:** `main` — HEAD `5773c603603ef2e0b8cba2dda1f1852e50766368`
**Nature:** STRICTLY READ-ONLY investigation. No source modified. No commit/push/restore. One build executed (the authorized `CI=true ./gradlew :app:assembleProDebug --rerun-tasks`).
**Date of investigation:** 2026-10-13

---

## §1 Executive verdict

The five modified native files are a **coherent, well-documented native hardening patch** — not
random or garbage output. Every hunk maps to a named audit finding (AC-01, AC-02, AC-03, N-01,
N-02/N-12, N-03, N-04, N-05-internal, N-06-internal, N-10, JNI-01, P5b/handshake-timeout,
D-1) with correct, defensive logic, and the diff's own comments reference the same PHASE naming
(`NATIVE-RACE-AND-DEADLINE-HARDENING`, `NATIVE-FIX-HANDSHAKE-TIMEOUT (P5b)`) and audit
(`AUDIT_AUTOCONNECT_LOGIC.md §A6-1`) that the existing `AUTOCONNECT_STATE_MACHINE_DESIGN.md`
(PART 0, D-1 verification) and the repository's committed native-hardening history describe.
An oddity scan found **zero** debug strings, TODO/FIXME/XXX/HACK, `printf`/`fprintf`/`puts`,
`exit`/`abort`/`assert`, or unexpected `#include` — the profile of deliberate engineering, not a
malfunctioning assistant.

**However, the working tree does NOT compile.** `softether_rudp.c` calls `rudp_ref_release()`
inside the newly-added N-01 hardening block *before* that function's `static` definition, with no
forward declaration, so the native build fails (`error: call to undeclared function
'rudp_ref_release'` / `static declaration follows non-static declaration`). The APK is therefore
not produced. This is a localized, fixable defect, but it means the patch cannot be kept or
committed as-is.

**Recommendation: FIX** (see §9). The changes are very likely the intended PHASE-1 native
hardening (consistent with the design doc and commit history), but they must be repaired to
compile before any keep/commit decision. If the operator confirms they never authorized *any*
native work, REVERT is the safe alternative — but the evidence does not support treating these as
garbage.

---

## §2 Git state summary (STEP 1)

- **a) `git rev-parse HEAD`** → `5773c603603ef2e0b8cba2dda1f1852e50766368`
- **b) `git rev-parse origin/main`** → `5773c603603ef2e0b8cba2dda1f1852e50766368` (in sync)
- **c) `git status --short --untracked-files=no`** → exactly the 5 native files:
  - `M SoftEtherClient/src/main/cpp/jni/softether_jni.c`
  - `M SoftEtherClient/src/main/cpp/softether-core/src/proto/rudp_transport.c`
  - `M SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_nat_t.c`
  - `M SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_protocol.c`
  - `M SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_rudp.c`
- **d) `git status --short` (with untracked)** → the 5 modified files **plus a large untracked
  inventory** (≈100+ items). See STEP 1(h) below.
- **e) `git stash list`** → empty.
- **f) `git reflog -15`** → a history of *legitimate, deliberate* native hardening commits, e.g.:
  - `3a2f442 fix(native): refcount rudp_context_t to close destroy-vs-poll use-after-free`
  - `b0418cc fix(native): hold connect_mutex in softether_connect_with_hub itself (N-16)`
  - `c572050 feat: land the F.28AL native lifetime stack`
  - `eb21e56 fix(native): bound the TLS handshake socket (P1) + bounded race join (P5b)`
  - `4df7e79 fix(android): bound stuck native slots and add Auto Mode recovery (T5-SLOT)`
  This establishes that native hardening is a normal, reviewed pattern in this repo.
- **g) `git log --oneline -5`** → `5773c60 docs: update PROJECT_SPECIFICATION to v1.0.10 ...`,
  `5ee4e65 docs: add AutoConnect audit ...`, `a87c523 test(androidTest): repair stale T3 ...`,
  `4df7e79 fix(android): bound stuck native slots ...`, `eb21e56 fix(native): bound the TLS
  handshake socket (P1) ...`.

### STEP 1(h) — recently-modified files outside the 5 native files

The untracked inventory (`git status --short`) contains a large number of non-source artifacts.
Classification for the operator's provenance review:

- **Created in the current / previous agent session (this investigation's lineage):**
  - `AUTOCONNECT_STATE_MACHINE_DESIGN.md` (created this session — a Kotlin-layer design doc; its
    PART 0 D-1 section *describes* the `race_control_t` work in this diff).
  - `.workbuddy-ai/` (agent memory/state; benign).
  - `NATIVE_RACE_AND_DEADLINE_REPORT.md` (PHASE-1 report from an earlier session; not modified
    here).
- **Pre-existing untracked evidence artifacts (NOT created by this investigation):** the bulk of
  the list — `COMMIT_BATCH_*.md`, `FIX_*.md`, `COMMIT_C3_*`, `RESUME_*`, `_f28al*`, `_c3fix*`,
  `_fix2_*`, `_t5*`, `BATCH_DEVICE_VERIFY_REPORT.md`, `_f28aly_backup/`, `_staging/`, `emergency/`,
  `scripts/`, `test-evidence/`, numerous `*.log`, `*.xml`, `*.png`, `ui*.xml`, `build2.txt`,
  `p18_*.txt`, etc. These match the "~109 evidence artifacts" referenced in the prior brief and
  are the output of a structured prior batch/audit workflow. **They are not the modified native
  files** and are out of scope for this native-diff characterization; the operator may review
  their provenance separately if desired.
- **No source file (native, Kotlin, Java, test, build, manifest, resource) was modified during
  this investigation.** Only the two authorized output files were created (the backup diff and
  this report).

---

## §3 Backup file location and size (STEP 2)

- **Primary backup:** `C:\Users\PMC\AppData\Local\Temp\unknown_native_changes_backup.diff`
  — **42,630 bytes**, verified non-empty. Contains the full `git diff` of all tracked changes
  (the 5 native files). This file is **OUTSIDE the repository**.
- **Secondary backup (untracked listing):** `C:\Users\PMC\AppData\Local\Temp\unknown_native_changes_status.txt`
  — 3,230 bytes (full `git status --short`). Also outside the repo.
- No working-tree-altering git operation was used to create these; `git diff` and
  `git status --short` are read-only and were redirected to files outside the repo.

---

## §4 Overview statistics (STEP 3)

`git diff --stat`:

```
 SoftEtherClient/src/main/cpp/jni/softether_jni.c   |  34 +-
 .../cpp/softether-core/src/proto/rudp_transport.c  |  14 +-
 .../cpp/softether-core/src/proto/softether_nat_t.c |   9 +-
 .../softether-core/src/proto/softether_protocol.c  | 369 ++++++++-----
 .../cpp/softether-core/src/proto/softether_rudp.c  |  15 +
 5 files changed, 323 insertions(+), 118 deletions(-)
```

`git diff --numstat`:

```
32	2	SoftEtherClient/src/main/cpp/jni/softether_jni.c
12	2	SoftEtherClient/src/main/cpp/softether-core/src/proto/rudp_transport.c
7	2	SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_nat_t.c
257	112	SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_protocol.c
15	0	SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_rudp.c
```

Per-file hunk counts (`grep -c '^@@'`):

| File | Hunks |
|---|---|
| softether_jni.c | 3 |
| rudp_transport.c | 2 |
| softether_nat_t.c | 1 |
| softether_protocol.c | 36 |
| softether_rudp.c | 1 |

`softether_protocol.c` dominates (257+/112−); `softether_rudp.c` is additions-only (+15/0) but
those additions break the build (see §7).

## §5 Per-file characterization (STEP 4)

### 5.1 SoftEtherClient/src/main/cpp/jni/softether_jni.c

**Complete diff (unified):**
```diff
diff --git a/SoftEtherClient/src/main/cpp/jni/softether_jni.c b/SoftEtherClient/src/main/cpp/jni/softether_jni.c
index 2ab8529..47cac57 100644
--- a/SoftEtherClient/src/main/cpp/jni/softether_jni.c
+++ b/SoftEtherClient/src/main/cpp/jni/softether_jni.c
@@ -256,6 +256,10 @@ JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeSe
         LOGE("Invalid offset/length");
         return -1;
     }
+    if (data == NULL) {
+        LOGE("Null data array");
+        return -1;
+    }
 
     jsize array_len = (*env)->GetArrayLength(env, data);
     if ((jlong)offset + length > array_len) {
@@ -501,10 +505,19 @@ JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGe
     JNIEnv *env, jobject thiz, jlong handle) {
     if (handle == 0) return -1;
     softether_connection_t* conn = (softether_connection_t*)handle;
+    // N-02 / N-12: snapshot the NAT-T transport and its fd under connect_mutex.
+    // softether_disconnect() (which nulls / destroys conn->nat_t_transport)
+    // runs serialized by connect_mutex, so taking it here guarantees the
+    // pointer and the underlying udp_fd cannot be torn down while we read them.
+    // We copy the int fd out before releasing the lock, so even a subsequent
+    // free of conn after unlock cannot affect the returned value.
+    int fd = -1;
+    pthread_mutex_lock(&conn->connect_mutex);
     if (conn->using_nat_t && conn->nat_t_transport != NULL) {
-        return rudp_transport_get_udp_fd(conn->nat_t_transport);
+        fd = rudp_transport_get_udp_fd(conn->nat_t_transport);
     }
-    return -1;
+    pthread_mutex_unlock(&conn->connect_mutex);
+    return fd;
 }
 
 JNIEXPORT void JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeSetOption(
@@ -520,14 +533,31 @@ JNIEXPORT void JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeSe
     
     switch (option) {
         case 1: // OPTION_TIMEOUT
+            // N-06: range-check the jlong before casting to int. A value
+            // outside [100, 120000] ms would either spin (too small) or hang
+            // the connect budget far too long (too large).
+            if (value < 100 || value > 120000) {
+                LOGE("OPTION_TIMEOUT out of range [100,120000]: %ld", (long)value);
+                break;
+            }
             conn->timeout_ms = (int)value;
             LOGD("Set timeout to %d ms", conn->timeout_ms);
             break;
         case 4: // OPTION_UDP_PORT (seUdpPort) — enables the direct R-UDP stage
+            // N-06: a UDP port must be a valid 1..65535 port number.
+            if (value < 1 || value > 65535) {
+                LOGE("OPTION_UDP_PORT out of range [1,65535]: %ld", (long)value);
+                break;
+            }
             conn->udp_port = (int)value;
             LOGD("Set UDP port to %d", conn->udp_port);
             break;
         case 5: // OPTION_UDP_ONLY — skip the doomed direct-TCP attempt
+            // N-06: this is a boolean flag; only 0 or 1 are meaningful.
+            if (value != 0 && value != 1) {
+                LOGE("OPTION_UDP_ONLY must be 0 or 1: %ld", (long)value);
+                break;
+            }
             conn->udp_only = (int)value;
             LOGD("Set UDP-only to %d", conn->udp_only);
             break;
```

**Summary (plain English).** Three independent, defensive changes:
1. `nativeSend` (the JNI send-slice entry point) now guards a NULL `data` array and returns −1
   instead of dereferencing it (JNI-01).
2. `nativeGetUdpFd` (the NAT-T UDP fd getter, N-02/N-12) now snapshots `conn->nat_t_transport`
   and copies the `udp_fd` out **while holding `conn->connect_mutex`**. A concurrent
   `softether_disconnect()` (which nulls/destroys `conn->nat_t_transport`) is serialized by the
   same mutex, so the pointer and the underlying fd cannot be torn down while we read them; the
   int fd is copied before unlock, so even a later free cannot affect the returned value.
3. `nativeSetOption` range-validates three options (N-06-internal): `OPTION_TIMEOUT` to
   [100,120000] ms, `OPTION_UDP_PORT` to [1,65535], `OPTION_UDP_ONLY` to {0,1}. Out-of-range
   values are rejected with `LOGE` instead of corrupting the connect budget or UDP-only flag.

**Classification:** COHERENT — complete, intentional, well-formed.

**New top-level symbols added:** none (only added guards and validation; no new structs,
functions, or globals).

**Audit-finding cross-check (STEP 4e):**
- `cancel_flag` lifetime (stack vs heap): NOT TOUCHED by this file.
- `thread_ids`/`trampolines` lifetime: NOT TOUCHED by this file.
- `pthread_create` return handling: NOT TOUCHED by this file.
- Shared winner arbitration: NOT TOUCHED by this file.
- RUDP refcount (N-1): NOT TOUCHED by this file.
- NAT-T fd getter (N-2): **ADDRESSED** — fd copied under `connect_mutex` (hunk 2, ≈ lines 505–525).
- JNI null guard (JNI-01): **ADDRESSED** — `nativeSend` NULL guard (hunk 1, ≈ line 256).
- DHCP parser (N-05/N-08): NOT TOUCHED by this file.
- raw I/O EAGAIN (N-06/N-09): NOT TOUCHED as described — note the diff's own N-06 label here is
  *option range validation*, a different finding from the brief's "raw I/O EAGAIN spin".
- socket timeout application (P1): NOT TOUCHED by this file.

### 5.2 SoftEtherClient/src/main/cpp/softether-core/src/proto/rudp_transport.c

**Complete diff (unified):**
```diff
diff --git a/SoftEtherClient/src/main/cpp/softether-core/src/proto/rudp_transport.c b/SoftEtherClient/src/main/cpp/softether-core/src/proto/rudp_transport.c
index 41f4ddb..9fd861d 100644
--- a/SoftEtherClient/src/main/cpp/softether-core/src/proto/rudp_transport.c
+++ b/SoftEtherClient/src/main/cpp/softether-core/src/proto/rudp_transport.c
@@ -1110,8 +1110,13 @@ int rudp_transport_connect(rudp_transport_t* t, const rudp_transport_config_t* c
     uint64_t deadline;
     struct timespec ts;
 
-    if (t == NULL || cfg == NULL || cfg->server_ip == 0 ||
-        cfg->server_port == 0) {
+    if (t == NULL || cfg == NULL || cfg->server_ip == 0) {
+        t->error_code = RUDP_T_ERR_UNKNOWN;
+        return -1;
+    }
+    // N-04: ICMP transport has no port (addressed by IP + ICMP echo id), so
+    // server_port == 0 is valid for ICMP. Every other mode requires a port.
+    if (cfg->transport_mode != RUDP_T_MODE_ICMP && cfg->server_port == 0) {
         t->error_code = RUDP_T_ERR_UNKNOWN;
         return -1;
     }
@@ -1191,6 +1196,11 @@ int rudp_transport_connect(rudp_transport_t* t, const rudp_transport_config_t* c
         goto fail;
     }
     if (rt_set_nonblocking(fds[0]) != 0) {
+        // N-10: fds[0]/fds[1] are not yet assigned to t->worker_fd/t->app_fd,
+        // so the fail: handler would skip them and leak both ends. Close them
+        // here before jumping to fail.
+        close(fds[0]);
+        close(fds[1]);
         goto fail;
     }
     t->worker_fd = fds[0];
```

**Summary (plain English).** Two changes in `rudp_transport_connect`:
1. N-04: the port-zero validity check is relaxed for ICMP transport. ICMP is addressed by
   IP + ICMP echo id and has no port, so `server_port == 0` is now accepted when
   `transport_mode == RUDP_T_MODE_ICMP` and rejected for every other mode. Previously a single
   `server_port == 0` rejection covered all modes, wrongly failing ICMP.
2. N-10: when `rt_set_nonblocking` fails *before* `fds[0]/fds[1]` are assigned to
   `t->worker_fd/t->app_fd`, the two sockets are explicitly `close()`d before `goto fail`.
   Without this, the fail handler would skip them and leak both ends of the socketpair.

**Classification:** COHERENT — complete, intentional, well-formed.

**New top-level symbols added:** none.

**Audit-finding cross-check (STEP 4e):**
- `cancel_flag` lifetime: NOT TOUCHED.
- `thread_ids`/`trampolines` lifetime: NOT TOUCHED.
- `pthread_create` return handling: NOT TOUCHED.
- Shared winner arbitration: NOT TOUCHED.
- RUDP refcount (N-1): NOT TOUCHED (this is the transport connect path, distinct from
  `rudp_ref_acquire` in softether_rudp.c).
- NAT-T fd getter (N-2): NOT TOUCHED.
- JNI null guard (JNI-01): NOT TOUCHED.
- DHCP parser (N-05/N-08): NOT TOUCHED.
- raw I/O EAGAIN (N-06/N-09): NOT TOUCHED.
- socket timeout application (P1): NOT TOUCHED.
- (N-04 and N-10 are legitimate fixes named in the diff but are not in the brief's STEP 7 list.)

### 5.3 SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_nat_t.c

**Complete diff (unified):**
```diff
diff --git a/SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_nat_t.c b/SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_nat_t.c
index f17637c..76f9e58 100644
--- a/SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_nat_t.c
+++ b/SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_nat_t.c
@@ -362,9 +362,14 @@ static int nat_t_connect_internal(uint32_t server_ip_net, const char* svc_name,
                 pack_free(p);
             }
 
+            // N-05: backoff exponent must grow with num_tries and saturate at
+            // NAT_T_MAX_BACKOFF_EXP. The previous (reversed) expression used the
+            // MAX exponent on the very first retry (200ms << 6 ~= 12.8s), so the
+            // first reconnect attempt waited ~13s. Correct: exp = min(tries,
+            // MAX) so the first retry is short and later ones saturate.
             uint32_t exp = (num_tries < NAT_T_MAX_BACKOFF_EXP)
-                               ? NAT_T_MAX_BACKOFF_EXP
-                               : (uint32_t)num_tries;
+                               ? (uint32_t)num_tries
+                               : NAT_T_MAX_BACKOFF_EXP;
             uint64_t interval = (uint64_t)NAT_T_CONNECT_INTERVAL * (1ULL << exp);
             next_send_tick = now + interval;
             num_tries++;
```

**Summary (plain English).** One change in `nat_t_connect_internal`: it fixes a **reversed
backoff exponent**. The original ternary set `exp = NAT_T_MAX_BACKOFF_EXP` on the very first
retry (≈ `200ms << 6 ≈ 12.8s` wait) and only fell to `num_tries` after many tries — making the
first reconnect attempt wait ~13s. The corrected expression sets `exp = min(num_tries, MAX)` so
the first retry is short and later retries saturate at the maximum.

**Classification:** COHERENT — complete, intentional, well-formed.

**New top-level symbols added:** none.

**Audit-finding cross-check (STEP 4e):**
- `cancel_flag` lifetime: NOT TOUCHED.
- `thread_ids`/`trampolines` lifetime: NOT TOUCHED.
- `pthread_create` return handling: NOT TOUCHED.
- Shared winner arbitration: NOT TOUCHED.
- RUDP refcount (N-1): NOT TOUCHED.
- NAT-T fd getter (N-2): NOT TOUCHED.
- JNI null guard (JNI-01): NOT TOUCHED.
- DHCP parser (N-05/N-08): **NOT TOUCHED** — important label note: the brief's STEP 7 lists
  "N-05 (DHCP parser trusts nested lengths)". This diff does **not** modify any DHCP parser.
  The `N-05` label *inside this diff* refers to the unrelated NAT-T backoff fix above, not to a
  DHCP parser. The brief's DHCP-parser N-05 is therefore unaddressed by these changes.
- raw I/O EAGAIN (N-06/N-09): NOT TOUCHED.
- socket timeout application (P1): NOT TOUCHED.

### 5.4 SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_rudp.c

**Complete diff (unified):**
```diff
diff --git a/SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_rudp.c b/SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_rudp.c
index 0facbb9..5748173 100644
--- a/SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_rudp.c
+++ b/SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_rudp.c
@@ -85,6 +85,21 @@ static int rudp_ref_acquire(rudp_context_t* ctx) {
         // context whose final release has already run.
         if (ctx->destroying) return 0;
         if (__sync_bool_compare_and_swap(&ctx->refcount, old, old + 1)) {
+            // N-01 hardening: we now hold a reference. Re-check `destroying`
+            // now that the count is bumped. If teardown began in the
+            // load-to-acquire window between our pre-check and the CAS, roll
+            // the reference back and fail closed. This closes the common
+            // interleaving (destroy set destroying=1 and dropped its ref, but
+            // our CAS kept the count >= 1 so no free has happened yet). The
+            // residual case where the context is already freed is covered by
+            // the documented N-15 calling convention: rudp_destroy() is
+            // serialized by the outer connect lock against the start of any
+            // new entry point, so no fresh acquire can begin after teardown
+            // starts.
+            if (ctx->destroying) {
+                rudp_ref_release(ctx);
+                return 0;
+            }
             return 1;
         }
         // Lost the race with another acquire/release; retry.
```

**Summary (plain English).** One change in `rudp_ref_acquire` (N-01 hardening): after the CAS
successfully bumps the refcount, it now **re-checks `ctx->destroying`**. If teardown began in
the load-to-acquire window (destroy set `destroying=1` and dropped its reference, but our CAS
kept the count ≥ 1 so no free has happened yet), it rolls the reference back by calling
`rudp_ref_release(ctx)` and returns 0 (fail closed). This is the correct, intended fix for the
acquire/destroy race.

**Classification:** SUSPICIOUS — not because the logic is wrong, but because the added call to
`rudp_ref_release(ctx)` appears **before** the `static` definition of `rudp_ref_release` later in
the same translation unit, with **no forward declaration**. This makes the working tree
non-compilable (see §7). The *intent* is COHERENT; the *implementation as written* is broken.

**New top-level symbols added:** none. Uses the pre-existing `rudp_ref_release` (now also called
earlier in the file).

**Audit-finding cross-check (STEP 4e):**
- `cancel_flag` lifetime: NOT TOUCHED.
- `thread_ids`/`trampolines` lifetime: NOT TOUCHED.
- `pthread_create` return handling: NOT TOUCHED.
- Shared winner arbitration: NOT TOUCHED.
- RUDP refcount (N-1): **ADDRESSED IN INTENT** — `rudp_ref_acquire` re-checks `destroying` and
  rolls back via `rudp_ref_release`. **BUT the implementation does not compile** (the
  `rudp_ref_release` ordering error in §7). So the finding is *targeted* correctly but the code
  as committed-to-tree cannot build.
- NAT-T fd getter (N-2): NOT TOUCHED.
- JNI null guard (JNI-01): NOT TOUCHED.
- DHCP parser (N-05/N-08): NOT TOUCHED.
- raw I/O EAGAIN (N-06/N-09): NOT TOUCHED.
- socket timeout application (P1): NOT TOUCHED.

### 5.5 SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_protocol.c

**Complete diff (unified):**
```diff
diff --git a/SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_protocol.c b/SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_protocol.c
index b36f078..2ba7201 100644
--- a/SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_protocol.c
+++ b/SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_protocol.c
@@ -1696,16 +1696,29 @@ static int softether_try_nat_t_connect(softether_connection_t* conn,
 // thread with a staggered delay. The first to complete a successful TLS
 // handshake claims the result; losing threads cancel and clean up.
 
+// Forward declaration so transport_thread_ctx_t can hold a back-pointer.
+typedef struct race_control_s race_control_t;
+
 // Per-thread context for the parallel transport race.
+//
+// NATIVE-RACE-AND-DEADLINE-HARDENING (AC-01): every field a worker thread can
+// reach now lives in this HEAP-allocated struct (softether_connect_parallel_race
+// calloc's the array), never on the parent's stack. The string fields are fixed
+// buffers the parent copies into (bounded strncpy) so a worker can never
+// dereference the caller's stack `resolved_ip[64]` or a JNI UTF string that is
+// released when the native call returns. The cancel flag, winner arbitration and
+// thread bookkeeping live in race_control_t (also heap). A worker that is
+// detached after the join budget expires keeps reading only heap memory.
 typedef struct {
     int transport_type;             // TRANSPORT_*
-    const char* host;               // original hostname (for NAT-T relay)
-    const char* resolved_ip;        // resolved server IP
+    char host[256];                 // original hostname (for NAT-T relay) — copied
+    char resolved_ip[64];           // resolved server IP — copied
     int port;                       // server TCP port
-    const char* connect_host;       // hostname for TLS SNI
-    uint32_t timeout_ms;            // per-transport connect timeout
+    char connect_host[256];         // hostname for TLS SNI — copied
+    uint32_t timeout_ms;            // per-transport connect timeout (informational)
     int udp_port;                   // seUdpPort (for TRANSPORT_RUDP_DIRECT)
-    const volatile int* cancel_flag; // shared cancel flag
+    int index;                      // slot index within the race
+    race_control_t* ctrl;           // back-pointer to the shared heap control block
 
     // Output (written by thread, read by main after join)
     transport_result_t result;
@@ -1734,6 +1747,30 @@ typedef struct {
     volatile int* exited;
 } race_trampoline_t;
 
+// Shared, heap-allocated race control block (AC-01). Holds the single cancel
+// flag, the AC-02 winner arbitration flag, the winner index, the race deadline
+// and the thread bookkeeping arrays. Never on the parent's stack.
+struct race_control_s {
+    volatile int cancel_flag;
+    volatile int winner_claimed;    // AC-02: only one worker may keep its resources
+    int winner_index;               // -1 = none
+    uint32_t race_timeout_ms;       // budget for this whole attempt (no 5s floor)
+    uint64_t race_deadline_ms;      // monotonic deadline for this whole attempt
+    pthread_t* thread_ids;          // heap, one per attempted transport
+    race_trampoline_t* trampolines; // heap, one per attempted transport
+    int* created;                  // heap, 0 = pthread_create failed (N-03)
+};
+
+// AC-03 / STEP 6: remaining time budget (ms) for this race. Every worker uses
+// this instead of the full race_timeout_ms so the total attempt cannot be
+// multiplied by the number of serial stages.
+static uint32_t race_remaining_budget_ms(race_control_t* ctrl) {
+    if (ctrl == NULL) return 0;
+    int64_t rem = (int64_t)ctrl->race_deadline_ms - (int64_t)softether_tick_ms();
+    if (rem < 0) rem = 0;
+    return (uint32_t)rem;
+}
+
 static void* race_thread_trampoline(void* p) {
     race_trampoline_t* t = (race_trampoline_t*)p;
     void* (*func)(void*) = t->func;
@@ -1780,6 +1817,9 @@ static void* transport_thread_tcp(void* arg) {
     r->transport_type = TRANSPORT_TCP;
     r->socket_fd = -1;
 
+    // AC-03 / STEP 6: use the remaining race budget, not the full timeout.
+    uint32_t budget = race_remaining_budget_ms(ctx->ctrl);
+
     // Staggered delay
     uint32_t delay = transport_delay_ms(TRANSPORT_TCP);
     if (delay > 0) {
@@ -1787,7 +1827,7 @@ static void* transport_thread_tcp(void* arg) {
         nanosleep(&ts, NULL);
     }
 
-    if (*ctx->cancel_flag) {
+    if (ctx->ctrl->cancel_flag) {
         LOGD("[%s] cancelled before start", transport_name(TRANSPORT_TCP));
         return NULL;
     }
@@ -1803,8 +1843,8 @@ static void* transport_thread_tcp(void* arg) {
         return NULL;
     }
 
-    // Connect (with timeout)
-    if (socket_connect_timeout(sock, ctx->connect_host, ctx->port, ctx->timeout_ms) != 0) {
+    // Connect (with timeout) — bounded by the remaining race budget.
+    if (socket_connect_timeout(sock, ctx->connect_host, ctx->port, (int)budget) != 0) {
         LOGD("[%s] connect failed", transport_name(TRANSPORT_TCP));
         socket_destroy(sock);
         r->error = ERR_TCP_CONNECT;
@@ -1815,7 +1855,7 @@ static void* transport_thread_tcp(void* arg) {
     sock->fd = -1;  // take ownership
     socket_destroy(sock);
 
-    if (*ctx->cancel_flag) {
+    if (ctx->ctrl->cancel_flag) {
         LOGD("[%s] cancelled after connect", transport_name(TRANSPORT_TCP));
         close(fd);
         r->error = ERR_TCP_CONNECT;
@@ -1830,10 +1870,10 @@ static void* transport_thread_tcp(void* arg) {
     // conn->socket_fd (AUDIT_AUTOCONNECT_LOGIC.md §A6-1). Bounding it here is
     // what lets the race's bounded join actually finish instead of detaching a
     // thread that is still holding g_openssl_lock.
-    if (socket_apply_timeout(fd, (int)ctx->timeout_ms) != 0) {
-        LOGD("[%s] socket timeout not applied (fd=%d, timeout_ms=%u); handshake "
+    if (socket_apply_timeout(fd, (int)budget) != 0) {
+        LOGD("[%s] socket timeout not applied (fd=%d, budget_ms=%u); handshake "
              "may block until the race detaches this thread",
-             transport_name(TRANSPORT_TCP), fd, ctx->timeout_ms);
+             transport_name(TRANSPORT_TCP), fd, budget);
     }
 
     // TLS handshake
@@ -1853,12 +1893,15 @@ static void* transport_thread_tcp(void* arg) {
         return NULL;
     }
 
-    // Try to claim winner
-    if (__sync_bool_compare_and_swap(&r->finished, 0, 1)) {
-        r->ok = 1;
+    // AC-02: shared winner arbitration. Only one worker may keep its resources;
+    // every other worker that also completed a handshake must clean up its own.
+    if (__sync_bool_compare_and_swap(&ctx->ctrl->winner_claimed, 0, 1)) {
+        ctx->ctrl->winner_index = ctx->index;
         r->socket_fd = fd;
         r->ssl_ctx = ssl_ctx;
         r->ssl = ssl_ctx;
+        __sync_synchronize();   // publish winner payload before ok
+        r->ok = 1;
         LOGD("[%s] CONNECTED fd=%d", transport_name(TRANSPORT_TCP), fd);
     } else {
         // Lost the race — clean up
@@ -1882,6 +1925,9 @@ static void* transport_thread_rudp_direct(void* arg) {
         return NULL;
     }
 
+    // AC-03 / STEP 6: use the remaining race budget, not the full timeout.
+    uint32_t budget = race_remaining_budget_ms(ctx->ctrl);
+
     // Staggered delay
     uint32_t delay = transport_delay_ms(TRANSPORT_RUDP_DIRECT);
     if (delay > 0) {
@@ -1889,7 +1935,7 @@ static void* transport_thread_rudp_direct(void* arg) {
         nanosleep(&ts, NULL);
     }
 
-    if (*ctx->cancel_flag) {
+    if (ctx->ctrl->cancel_flag) {
         LOGD("[%s] cancelled before start", transport_name(TRANSPORT_RUDP_DIRECT));
         return NULL;
     }
@@ -1914,16 +1960,16 @@ static void* transport_thread_rudp_direct(void* arg) {
     cfg.server_ip = ip4.s_addr;
     cfg.server_port = (uint16_t)ctx->udp_port;
     cfg.udp_fd = -1;
-    cfg.connect_timeout_ms = ctx->timeout_ms;
+    cfg.connect_timeout_ms = budget;
 
-    if (rudp_transport_connect(tr, &cfg, ctx->cancel_flag) != 0) {
+    if (rudp_transport_connect(tr, &cfg, &ctx->ctrl->cancel_flag) != 0) {
         LOGD("[%s] R-UDP connect failed", transport_name(TRANSPORT_RUDP_DIRECT));
         rudp_transport_destroy(tr);
         r->error = ERR_TCP_CONNECT;
         return NULL;
     }
 
-    if (*ctx->cancel_flag) {
+    if (ctx->ctrl->cancel_flag) {
         rudp_transport_destroy(tr);
         r->error = ERR_TCP_CONNECT;
         return NULL;
@@ -1931,6 +1977,13 @@ static void* transport_thread_rudp_direct(void* arg) {
 
     // TLS handshake over the R-UDP transport
     int fd = rudp_transport_get_fd(tr);
+    // AC-03 / STEP 5.1: bound the handshake on this transport's fd too. The fd
+    // returned by rudp_transport_get_fd() is the app-facing end of the RUDP
+    // socketpair (rudp_transport.c), which is exactly the fd OpenSSL reads from.
+    if (socket_apply_timeout(fd, (int)budget) != 0) {
+        LOGD("[%s] socket timeout not applied (fd=%d, budget_ms=%u)",
+             transport_name(TRANSPORT_RUDP_DIRECT), fd, budget);
+    }
     ssl_context_t* ssl_ctx = ssl_create_client();
     if (ssl_ctx == NULL || ssl_connect(ssl_ctx, fd, ctx->connect_host) != 0) {
         LOGD("[%s] TLS handshake failed", transport_name(TRANSPORT_RUDP_DIRECT));
@@ -1940,13 +1993,15 @@ static void* transport_thread_rudp_direct(void* arg) {
         return NULL;
     }
 
-    // Try to claim winner
-    if (__sync_bool_compare_and_swap(&r->finished, 0, 1)) {
-        r->ok = 1;
+    // AC-02: shared winner arbitration.
+    if (__sync_bool_compare_and_swap(&ctx->ctrl->winner_claimed, 0, 1)) {
+        ctx->ctrl->winner_index = ctx->index;
         r->socket_fd = fd;
         r->ssl_ctx = ssl_ctx;
         r->ssl = ssl_ctx;
         r->transport = tr;
+        __sync_synchronize();
+        r->ok = 1;
         LOGD("[%s] CONNECTED fd=%d", transport_name(TRANSPORT_RUDP_DIRECT), fd);
     } else {
         LOGD("[%s] lost race, cleaning up", transport_name(TRANSPORT_RUDP_DIRECT));
@@ -1964,6 +2019,9 @@ static void* transport_thread_natt(void* arg) {
     memset(r, 0, sizeof(*r));
     r->transport_type = TRANSPORT_NATT;
 
+    // AC-03 / STEP 6: use the remaining race budget, not the full timeout.
+    uint32_t budget = race_remaining_budget_ms(ctx->ctrl);
+
     // Staggered delay
     uint32_t delay = transport_delay_ms(TRANSPORT_NATT);
     if (delay > 0) {
@@ -1971,7 +2029,7 @@ static void* transport_thread_natt(void* arg) {
         nanosleep(&ts, NULL);
     }
 
-    if (*ctx->cancel_flag) {
+    if (ctx->ctrl->cancel_flag) {
         LOGD("[%s] cancelled before start", transport_name(TRANSPORT_NATT));
         return NULL;
     }
@@ -1990,15 +2048,15 @@ static void* transport_thread_natt(void* arg) {
     softether_nat_t_result_t nr;
     memset(&nr, 0, sizeof(nr));
     if (nat_t_connect_ex(ip4.s_addr, NAT_T_SVC_NAME, NULL, ctx->host,
-                         ctx->timeout_ms, &nr,
-                         ctx->cancel_flag) != 0) {
+                         (int)budget, &nr,
+                         &ctx->ctrl->cancel_flag) != 0) {
         LOGD("[%s] rendezvous failed: error=%d", transport_name(TRANSPORT_NATT),
              nr.error_code);
         r->error = ERR_TCP_CONNECT;
         return NULL;
     }
 
-    if (*ctx->cancel_flag) {
+    if (ctx->ctrl->cancel_flag) {
         if (nr.udp_fd >= 0) close(nr.udp_fd);
         r->error = ERR_TCP_CONNECT;
         return NULL;
@@ -2017,16 +2075,16 @@ static void* transport_thread_natt(void* arg) {
     cfg.server_ip = nr.result_ip;
     cfg.server_port = nr.result_port;
     cfg.udp_fd = nr.same_lan ? -1 : nr.udp_fd;
-    cfg.connect_timeout_ms = ctx->timeout_ms;
+    cfg.connect_timeout_ms = budget;
 
-    if (rudp_transport_connect(tr, &cfg, ctx->cancel_flag) != 0) {
+    if (rudp_transport_connect(tr, &cfg, &ctx->ctrl->cancel_flag) != 0) {
         LOGD("[%s] R-UDP transport connect failed", transport_name(TRANSPORT_NATT));
         rudp_transport_destroy(tr);
         r->error = ERR_TCP_CONNECT;
         return NULL;
     }
 
-    if (*ctx->cancel_flag) {
+    if (ctx->ctrl->cancel_flag) {
         rudp_transport_destroy(tr);
         r->error = ERR_TCP_CONNECT;
         return NULL;
@@ -2034,6 +2092,11 @@ static void* transport_thread_natt(void* arg) {
 
     // TLS handshake over the R-UDP transport
     int fd = rudp_transport_get_fd(tr);
+    // AC-03 / STEP 5.2: bound the handshake on this transport's fd too.
+    if (socket_apply_timeout(fd, (int)budget) != 0) {
+        LOGD("[%s] socket timeout not applied (fd=%d, budget_ms=%u)",
+             transport_name(TRANSPORT_NATT), fd, budget);
+    }
     ssl_context_t* ssl_ctx = ssl_create_client();
     if (ssl_ctx == NULL || ssl_connect(ssl_ctx, fd, ctx->connect_host) != 0) {
         LOGD("[%s] TLS handshake failed", transport_name(TRANSPORT_NATT));
@@ -2043,13 +2106,15 @@ static void* transport_thread_natt(void* arg) {
         return NULL;
     }
 
-    // Try to claim winner
-    if (__sync_bool_compare_and_swap(&r->finished, 0, 1)) {
-        r->ok = 1;
+    // AC-02: shared winner arbitration.
+    if (__sync_bool_compare_and_swap(&ctx->ctrl->winner_claimed, 0, 1)) {
+        ctx->ctrl->winner_index = ctx->index;
         r->socket_fd = fd;
         r->ssl_ctx = ssl_ctx;
         r->ssl = ssl_ctx;
         r->transport = tr;
+        __sync_synchronize();
+        r->ok = 1;
         LOGD("[%s] CONNECTED fd=%d", transport_name(TRANSPORT_NATT), fd);
     } else {
         LOGD("[%s] lost race, cleaning up", transport_name(TRANSPORT_NATT));
@@ -2067,6 +2132,9 @@ static void* transport_thread_dns(void* arg) {
     memset(r, 0, sizeof(*r));
     r->transport_type = TRANSPORT_DNS;
 
+    // AC-03 / STEP 6: use the remaining race budget, not the full timeout.
+    uint32_t budget = race_remaining_budget_ms(ctx->ctrl);
+
     // Staggered delay
     uint32_t delay = transport_delay_ms(TRANSPORT_DNS);
     if (delay > 0) {
@@ -2074,7 +2142,7 @@ static void* transport_thread_dns(void* arg) {
         nanosleep(&ts, NULL);
     }
 
-    if (*ctx->cancel_flag) {
+    if (ctx->ctrl->cancel_flag) {
         LOGD("[%s] cancelled before start", transport_name(TRANSPORT_DNS));
         return NULL;
     }
@@ -2099,17 +2167,17 @@ static void* transport_thread_dns(void* arg) {
     cfg.server_ip = ip4.s_addr;
     cfg.server_port = 53;  // DNS standard port
     cfg.udp_fd = -1;
-    cfg.connect_timeout_ms = ctx->timeout_ms;
+    cfg.connect_timeout_ms = budget;
     cfg.transport_mode = RUDP_T_MODE_DNS;
 
-    if (rudp_transport_connect(tr, &cfg, ctx->cancel_flag) != 0) {
+    if (rudp_transport_connect(tr, &cfg, &ctx->ctrl->cancel_flag) != 0) {
         LOGD("[%s] R-UDP connect failed", transport_name(TRANSPORT_DNS));
         rudp_transport_destroy(tr);
         r->error = ERR_TCP_CONNECT;
         return NULL;
     }
 
-    if (*ctx->cancel_flag) {
+    if (ctx->ctrl->cancel_flag) {
         rudp_transport_destroy(tr);
         r->error = ERR_TCP_CONNECT;
         return NULL;
@@ -2117,6 +2185,11 @@ static void* transport_thread_dns(void* arg) {
 
     // TLS handshake over the DNS transport
     int fd = rudp_transport_get_fd(tr);
+    // AC-03 / STEP 5.3: bound the handshake on this transport's fd too.
+    if (socket_apply_timeout(fd, (int)budget) != 0) {
+        LOGD("[%s] socket timeout not applied (fd=%d, budget_ms=%u)",
+             transport_name(TRANSPORT_DNS), fd, budget);
+    }
     ssl_context_t* ssl_ctx = ssl_create_client();
     if (ssl_ctx == NULL || ssl_connect(ssl_ctx, fd, ctx->connect_host) != 0) {
         LOGD("[%s] TLS handshake failed", transport_name(TRANSPORT_DNS));
@@ -2126,13 +2199,15 @@ static void* transport_thread_dns(void* arg) {
         return NULL;
     }
 
-    // Try to claim winner
-    if (__sync_bool_compare_and_swap(&r->finished, 0, 1)) {
-        r->ok = 1;
+    // AC-02: shared winner arbitration.
+    if (__sync_bool_compare_and_swap(&ctx->ctrl->winner_claimed, 0, 1)) {
+        ctx->ctrl->winner_index = ctx->index;
         r->socket_fd = fd;
         r->ssl_ctx = ssl_ctx;
         r->ssl = ssl_ctx;
         r->transport = tr;
+        __sync_synchronize();
+        r->ok = 1;
         LOGD("[%s] CONNECTED fd=%d", transport_name(TRANSPORT_DNS), fd);
     } else {
         LOGD("[%s] lost race, cleaning up", transport_name(TRANSPORT_DNS));
@@ -2150,6 +2225,9 @@ static void* transport_thread_icmp(void* arg) {
     memset(r, 0, sizeof(*r));
     r->transport_type = TRANSPORT_ICMP;
 
+    // AC-03 / STEP 6: use the remaining race budget, not the full timeout.
+    uint32_t budget = race_remaining_budget_ms(ctx->ctrl);
+
     // Staggered delay
     uint32_t delay = transport_delay_ms(TRANSPORT_ICMP);
     if (delay > 0) {
@@ -2157,7 +2235,7 @@ static void* transport_thread_icmp(void* arg) {
         nanosleep(&ts, NULL);
     }
 
-    if (*ctx->cancel_flag) {
+    if (ctx->ctrl->cancel_flag) {
         LOGD("[%s] cancelled before start", transport_name(TRANSPORT_ICMP));
         return NULL;
     }
@@ -2182,17 +2260,17 @@ static void* transport_thread_icmp(void* arg) {
     cfg.server_ip = ip4.s_addr;
     cfg.server_port = 0;  // ICMP has no port
     cfg.udp_fd = -1;
-    cfg.connect_timeout_ms = ctx->timeout_ms;
+    cfg.connect_timeout_ms = budget;
     cfg.transport_mode = RUDP_T_MODE_ICMP;
 
-    if (rudp_transport_connect(tr, &cfg, ctx->cancel_flag) != 0) {
+    if (rudp_transport_connect(tr, &cfg, &ctx->ctrl->cancel_flag) != 0) {
         LOGD("[%s] R-UDP connect failed (no root?)", transport_name(TRANSPORT_ICMP));
         rudp_transport_destroy(tr);
         r->error = ERR_TCP_CONNECT;
         return NULL;
     }
 
-    if (*ctx->cancel_flag) {
+    if (ctx->ctrl->cancel_flag) {
         rudp_transport_destroy(tr);
         r->error = ERR_TCP_CONNECT;
         return NULL;
@@ -2200,6 +2278,11 @@ static void* transport_thread_icmp(void* arg) {
 
     // TLS handshake over the ICMP transport
     int fd = rudp_transport_get_fd(tr);
+    // AC-03 / STEP 5.4: bound the handshake on this transport's fd too.
+    if (socket_apply_timeout(fd, (int)budget) != 0) {
+        LOGD("[%s] socket timeout not applied (fd=%d, budget_ms=%u)",
+             transport_name(TRANSPORT_ICMP), fd, budget);
+    }
     ssl_context_t* ssl_ctx = ssl_create_client();
     if (ssl_ctx == NULL || ssl_connect(ssl_ctx, fd, ctx->connect_host) != 0) {
         LOGD("[%s] TLS handshake failed", transport_name(TRANSPORT_ICMP));
@@ -2209,13 +2292,15 @@ static void* transport_thread_icmp(void* arg) {
         return NULL;
     }
 
-    // Try to claim winner
-    if (__sync_bool_compare_and_swap(&r->finished, 0, 1)) {
-        r->ok = 1;
+    // AC-02: shared winner arbitration.
+    if (__sync_bool_compare_and_swap(&ctx->ctrl->winner_claimed, 0, 1)) {
+        ctx->ctrl->winner_index = ctx->index;
         r->socket_fd = fd;
         r->ssl_ctx = ssl_ctx;
         r->ssl = ssl_ctx;
         r->transport = tr;
+        __sync_synchronize();
+        r->ok = 1;
         LOGD("[%s] CONNECTED fd=%d", transport_name(TRANSPORT_ICMP), fd);
     } else {
         LOGD("[%s] lost race, cleaning up", transport_name(TRANSPORT_ICMP));
@@ -2230,12 +2315,6 @@ static void* transport_thread_icmp(void* arg) {
 static int softether_connect_parallel_race(softether_connection_t* conn,
                                            const char* host, const char* resolved_ip,
                                            int port, const char* connect_host) {
-    volatile int cancel_flag = 0;
-
-    // Determine which transports to launch
-    // TCP is always tried sequentially first (for TCP-available servers) or
-    // skipped entirely (for UDP-only). The parallel race only races UDP
-    // transports: R-UDP direct, NAT-T relay, DNS, ICMP.
     int want_tcp = 0;
     int want_rudp_direct = (conn->udp_port > 0);
     int want_natt = 1;  // always try NAT-T (it's the CGNAT path)
@@ -2245,12 +2324,13 @@ static int softether_connect_parallel_race(softether_connection_t* conn,
     int want_dns = 1;
     int want_icmp = 1;
 
-    // Count active transports
     int num_transports = 0;
-    // NATIVE-FIX-HANDSHAKE-TIMEOUT (P5b): heap-allocated, NOT a stack array.
-    // A transport thread that is detached after the join budget expires may
-    // still be running and reading its context, so the context must outlive this
-    // function's stack frame. Freed below only when every thread was joined.
+
+    // NATIVE-RACE-AND-DEADLINE-HARDENING (AC-01): heap-allocated transport
+    // contexts. A transport thread that is detached after the join budget
+    // expires may still be running and reading its context, so the context must
+    // outlive this function's stack frame. Freed below only when every thread
+    // was joined.
     transport_thread_ctx_t* threads =
         (transport_thread_ctx_t*)calloc(TRANSPORT_ICMP + 1, sizeof(transport_thread_ctx_t));
     if (threads == NULL) {
@@ -2258,86 +2338,122 @@ static int softether_connect_parallel_race(softether_connection_t* conn,
         return -1;
     }
 
-    // Compute per-transport timeout (race deadline = max(5000, timeout))
-    uint32_t race_timeout_ms = conn->timeout_ms;
-    if (race_timeout_ms < 5000) race_timeout_ms = 5000;
+    // NATIVE-RACE-AND-DEADLINE-HARDENING (AC-01 / AC-02 / STEP 6): single heap
+    // control block shared by all workers and the parent. Holds the cancel flag
+    // (no longer on the parent stack), the shared winner arbitration flag, the
+    // winner index, the absolute race deadline and the thread bookkeeping
+    // arrays. Nothing a worker can reach lives on the parent's stack.
+    race_control_t* ctrl = (race_control_t*)calloc(1, sizeof(race_control_t));
+    if (ctrl == NULL) {
+        LOGE("Parallel race: no memory for race control");
+        free(threads);
+        return -1;
+    }
+    ctrl->winner_index = -1;
+    ctrl->race_timeout_ms = conn->timeout_ms;   // STEP 6: no 5s floor
+    ctrl->race_deadline_ms = softether_tick_ms() + (uint64_t)ctrl->race_timeout_ms;
+
+    // Heap bookkeeping for the worker threads (N-03 / STEP 4): thread ids,
+    // trampolines and a "created" flag per slot so the bounded join can skip
+    // slots whose pthread_create() failed.
+    ctrl->thread_ids = (pthread_t*)calloc(TRANSPORT_ICMP + 1, sizeof(pthread_t));
+    ctrl->trampolines = (race_trampoline_t*)calloc(TRANSPORT_ICMP + 1, sizeof(race_trampoline_t));
+    ctrl->created = (int*)calloc(TRANSPORT_ICMP + 1, sizeof(int));
+    if (ctrl->thread_ids == NULL || ctrl->trampolines == NULL || ctrl->created == NULL) {
+        LOGE("Parallel race: no memory for thread bookkeeping");
+        free(ctrl->thread_ids); free(ctrl->trampolines); free(ctrl->created);
+        free(ctrl); free(threads);
+        return -1;
+    }
+
+    uint32_t race_timeout_ms = ctrl->race_timeout_ms;  // no 5s floor (STEP 6)
 
     if (want_tcp) {
         transport_thread_ctx_t* t = &threads[num_transports];
         t->transport_type = TRANSPORT_TCP;
-        t->host = host;
-        t->resolved_ip = resolved_ip;
+        strncpy(t->host, host, sizeof(t->host) - 1); t->host[sizeof(t->host) - 1] = '\0';
+        strncpy(t->resolved_ip, resolved_ip, sizeof(t->resolved_ip) - 1); t->resolved_ip[sizeof(t->resolved_ip) - 1] = '\0';
         t->port = port;
-        t->connect_host = connect_host;
+        strncpy(t->connect_host, connect_host, sizeof(t->connect_host) - 1); t->connect_host[sizeof(t->connect_host) - 1] = '\0';
         t->timeout_ms = race_timeout_ms;
         t->udp_port = 0;
-        t->cancel_flag = &cancel_flag;
+        t->index = num_transports;
+        t->ctrl = ctrl;
         num_transports++;
     }
     if (want_rudp_direct) {
         transport_thread_ctx_t* t = &threads[num_transports];
         t->transport_type = TRANSPORT_RUDP_DIRECT;
-        t->host = host;
-        t->resolved_ip = resolved_ip;
+        strncpy(t->host, host, sizeof(t->host) - 1); t->host[sizeof(t->host) - 1] = '\0';
+        strncpy(t->resolved_ip, resolved_ip, sizeof(t->resolved_ip) - 1); t->resolved_ip[sizeof(t->resolved_ip) - 1] = '\0';
         t->port = port;
-        t->connect_host = connect_host;
+        strncpy(t->connect_host, connect_host, sizeof(t->connect_host) - 1); t->connect_host[sizeof(t->connect_host) - 1] = '\0';
         t->timeout_ms = race_timeout_ms;
         t->udp_port = conn->udp_port;
-        t->cancel_flag = &cancel_flag;
+        t->index = num_transports;
+        t->ctrl = ctrl;
         num_transports++;
     }
     if (want_natt) {
         transport_thread_ctx_t* t = &threads[num_transports];
         t->transport_type = TRANSPORT_NATT;
-        t->host = host;
-        t->resolved_ip = resolved_ip;
+        strncpy(t->host, host, sizeof(t->host) - 1); t->host[sizeof(t->host) - 1] = '\0';
+        strncpy(t->resolved_ip, resolved_ip, sizeof(t->resolved_ip) - 1); t->resolved_ip[sizeof(t->resolved_ip) - 1] = '\0';
         t->port = port;
-        t->connect_host = connect_host;
+        strncpy(t->connect_host, connect_host, sizeof(t->connect_host) - 1); t->connect_host[sizeof(t->connect_host) - 1] = '\0';
         t->timeout_ms = race_timeout_ms;
         t->udp_port = 0;
-        t->cancel_flag = &cancel_flag;
+        t->index = num_transports;
+        t->ctrl = ctrl;
         num_transports++;
     }
     if (want_dns) {
         transport_thread_ctx_t* t = &threads[num_transports];
         t->transport_type = TRANSPORT_DNS;
-        t->host = host;
-        t->resolved_ip = resolved_ip;
+        strncpy(t->host, host, sizeof(t->host) - 1); t->host[sizeof(t->host) - 1] = '\0';
+        strncpy(t->resolved_ip, resolved_ip, sizeof(t->resolved_ip) - 1); t->resolved_ip[sizeof(t->resolved_ip) - 1] = '\0';
         t->port = port;
-        t->connect_host = connect_host;
+        strncpy(t->connect_host, connect_host, sizeof(t->connect_host) - 1); t->connect_host[sizeof(t->connect_host) - 1] = '\0';
         t->timeout_ms = race_timeout_ms;
         t->udp_port = 0;
-        t->cancel_flag = &cancel_flag;
+        t->index = num_transports;
+        t->ctrl = ctrl;
         num_transports++;
     }
     if (want_icmp) {
         transport_thread_ctx_t* t = &threads[num_transports];
         t->transport_type = TRANSPORT_ICMP;
-        t->host = host;
-        t->resolved_ip = resolved_ip;
+        strncpy(t->host, host, sizeof(t->host) - 1); t->host[sizeof(t->host) - 1] = '\0';
+        strncpy(t->resolved_ip, resolved_ip, sizeof(t->resolved_ip) - 1); t->resolved_ip[sizeof(t->resolved_ip) - 1] = '\0';
         t->port = port;
-        t->connect_host = connect_host;
+        strncpy(t->connect_host, connect_host, sizeof(t->connect_host) - 1); t->connect_host[sizeof(t->connect_host) - 1] = '\0';
         t->timeout_ms = race_timeout_ms;
         t->udp_port = 0;
-        t->cancel_flag = &cancel_flag;
+        t->index = num_transports;
+        t->ctrl = ctrl;
         num_transports++;
     }
 
     if (num_transports == 0) {
         LOGE("No transports to attempt");
         free(threads);
+        free(ctrl->thread_ids);
+        free(ctrl->trampolines);
+        free(ctrl->created);
+        free(ctrl);
         return -1;
     }
 
-    LOGD("Parallel race: %d transports, race timeout %u ms", num_transports, race_timeout_ms);
+    LOGD("Parallel race: %d transports, race timeout %u ms (deadline model)",
+         num_transports, race_timeout_ms);
 
     // Launch all transport threads through the bounded-join trampoline (P5b), so
-    // each one publishes its own exit flag for the bounded join below.
-    pthread_t thread_ids[TRANSPORT_ICMP + 1];
-    race_trampoline_t trampolines[TRANSPORT_ICMP + 1];
-    memset(trampolines, 0, sizeof(trampolines));
+    // each one publishes its own exit flag for the bounded join below. Every
+    // pthread_create() return is checked (N-03 / STEP 4): only slots whose
+    // creation succeeded are marked created[i] = 1 and only those are joined.
+    memset(ctrl->trampolines, 0, sizeof(race_trampoline_t) * (TRANSPORT_ICMP + 1));
     for (int i = 0; i < num_transports; i++) {
-        void* (*func)(void*);
+        void* (*func)(void*) = NULL;
         switch (threads[i].transport_type) {
             case TRANSPORT_TCP:        func = transport_thread_tcp; break;
             case TRANSPORT_RUDP_DIRECT: func = transport_thread_rudp_direct; break;
@@ -2346,31 +2462,38 @@ static int softether_connect_parallel_race(softether_connection_t* conn,
             case TRANSPORT_ICMP:       func = transport_thread_icmp; break;
             default:                   func = NULL; break;
         }
-        if (func) {
-            trampolines[i].func = func;
-            trampolines[i].arg = &threads[i];
-            trampolines[i].exited = &threads[i].exited;
-            pthread_create(&thread_ids[i], NULL, race_thread_trampoline, &trampolines[i]);
+        if (func == NULL) continue;
+        ctrl->trampolines[i].func = func;
+        ctrl->trampolines[i].arg = &threads[i];
+        ctrl->trampolines[i].exited = &threads[i].exited;
+        int rc = pthread_create(&ctrl->thread_ids[i], NULL, race_thread_trampoline,
+                                &ctrl->trampolines[i]);
+        if (rc == 0) {
+            ctrl->created[i] = 1;
+        } else {
+            LOGW("[RACE] pthread_create failed for slot %d (rc=%d); skipping",
+                 i, rc);
         }
     }
 
-    // Wait for first winner or deadline
-    uint64_t deadline = softether_tick_ms() + race_timeout_ms;
+    // Wait for the first winner (shared AC-02 slot) or the absolute deadline.
+    // Workers publish the winner via ctrl->winner_claimed / ctrl->winner_index,
+    // so the parent no longer inspects per-thread finished/ok flags.
     transport_result_t* winner = NULL;
-    while (softether_tick_ms() < deadline) {
-        // Check if any thread won
-        for (int i = 0; i < num_transports; i++) {
-            if (threads[i].result.finished && threads[i].result.ok) {
-                winner = &threads[i].result;
-                break;
+    while (softether_tick_ms() < ctrl->race_deadline_ms) {
+        if (ctrl->winner_claimed) {
+            __sync_synchronize();   // AC-02: make winner payload visible here
+            int wi = ctrl->winner_index;
+            if (wi >= 0 && wi < num_transports) {
+                winner = &threads[wi].result;
             }
+            break;
         }
-        if (winner) break;
         usleep(10000);  // 10ms poll
     }
 
-    // Cancel all threads
-    __sync_lock_test_and_set(&cancel_flag, 1);
+    // Signal all live threads to abort.
+    __sync_lock_test_and_set(&ctrl->cancel_flag, 1);
 
     // ---- NATIVE-FIX-HANDSHAKE-TIMEOUT (P5b): BOUNDED join -------------------
     // The poll loop above already spent the first race_timeout_ms budget, so the
@@ -2388,22 +2511,25 @@ static int softether_connect_parallel_race(softether_connection_t* conn,
     uint64_t join_deadline = softether_tick_ms() + race_timeout_ms;
     int detached_count = 0;
     for (int i = 0; i < num_transports; i++) {
-        while (trampolines[i].exited == 0) {
+        if (ctrl->created[i] == 0) continue;   // N-03: never created -> skip
+        while (ctrl->trampolines[i].exited == 0) {
             if (softether_tick_ms() >= join_deadline) break;
             usleep(10000);  // 10ms poll
         }
-        if (trampolines[i].exited != 0) {
-            pthread_join(thread_ids[i], NULL);
+        if (ctrl->trampolines[i].exited != 0) {
+            pthread_join(ctrl->thread_ids[i], NULL);
         } else {
             LOGW("[RACE] transport thread %d did not exit within %u ms after race "
                  "deadline; detaching to avoid an unbounded join",
                  i, race_timeout_ms);
-            pthread_detach(thread_ids[i]);
+            pthread_detach(ctrl->thread_ids[i]);
             detached_count++;
         }
     }
 
-    // Snapshot the winner's fields BEFORE the context array may be freed.
+    // Snapshot the winner's fields BEFORE the context array may be freed. The
+    // winner index comes from the shared AC-02 slot, so a single winner is read
+    // exactly once regardless of how many transports completed a handshake.
     int winner_fd = -1;
     void* winner_ssl_ctx = NULL;
     void* winner_ssl = NULL;
@@ -2417,15 +2543,23 @@ static int softether_connect_parallel_race(softether_connection_t* conn,
         winner_type = winner->transport_type;
     }
 
+    // Free the heap control block and bookkeeping arrays ONLY when no thread was
+    // detached. A detached worker still holds ctx->ctrl and may read
+    // ctrl->cancel_flag, so in that case the control block (like the context
+    // array) is deliberately retained to avoid a use-after-free.
     if (detached_count == 0) {
+        free(ctrl->thread_ids);
+        free(ctrl->trampolines);
+        free(ctrl->created);
+        free(ctrl);
         free(threads);
     } else {
-        // A detached thread may still be running and reading its context, so the
-        // array is deliberately retained rather than freed under a live thread.
-        // A bounded, one-off leak on an already-failing connect path is the
-        // correct trade against a use-after-free.
+        // A detached thread may still be running and reading its context AND its
+        // ctrl block, so both are deliberately retained rather than freed under
+        // a live thread. A bounded, one-off leak on an already-failing connect
+        // path is the correct trade against a use-after-free.
         LOGW("Parallel race: %d transport thread(s) detached; retaining transport "
-             "contexts to avoid a use-after-free", detached_count);
+             "contexts and control block to avoid a use-after-free", detached_count);
     }
 
     if (winner == NULL) {
@@ -3803,6 +3937,17 @@ int softether_additional_connect(softether_connection_t* conn) {
         return -1;
     }
 
+    // AC-03 / STEP 5.5: bound the TLS handshake on this additional-TCP fd too.
+    // socket_connect_timeout() leaves this fd blocking with no SO_RCVTIMEO, so
+    // without this an unresponsive peer could block ssl_connect() on it forever
+    // — and this fd is not reachable by softether_force_close_socket(), which
+    // only targets conn->socket_fd. (The later SO_RCVTIMEO=5000ms for the data
+    // phase is set after the handshake, so it does not cover the handshake.)
+    if (socket_apply_timeout(fd, conn->timeout_ms) != 0) {
+        LOGD("additional_connect: socket timeout not applied (fd=%d); handshake "
+             "may block until the OS TCP stack gives up", fd);
+    }
+
     if (ssl_connect(ssl_ctx, fd, conn->server_ip) != 0) {
         LOGE("additional_connect: TLS handshake failed");
         ssl_destroy(ssl_ctx);
```

**Summary (plain English).** The largest change — a coherent rewrite of the parallel transport
race (`softether_connect_parallel_race`) and TLS handshake-timeout bounding:
1. **AC-01 (use-after-return):** the `cancel_flag` is moved off the parent stack into a heap
   `race_control_t` block; the per-thread `transport_thread_ctx_t` string pointers (`host`,
   `resolved_ip`, `connect_host`) are replaced by fixed `char[]` buffers copied via `strncpy`
   (bounded, NUL-terminated). A detached worker can therefore only ever read heap memory, never a
   caller's stack frame or a released JNI UTF string.
2. **AC-02 (shared winner arbitration):** winner selection moves from a per-thread
   `r->finished` CAS to a single shared `ctrl->winner_claimed` CAS plus `ctrl->winner_index`,
   with `__sync_synchronize()` to publish the winner payload. Exactly one worker keeps its
   resources; all others clean up their own.
3. **AC-03 (timeout does not bound all native work):** every worker now computes its connect/
   handshake budget via `race_remaining_budget_ms(ctrl)` (an absolute-deadline model that
   removes the previous 5s floor). `socket_apply_timeout(fd, budget)` is applied to the TLS
   handshake fd of **every** transport — TCP, R-UDP direct, NAT-T, DNS, ICMP — plus
   `softether_additional_connect` (STEP 5.5). This bounds the previously-unbounded UDP-family
   handshakes (AC-04).
4. **N-03 (`pthread_create` failures):** the return value is now checked; a per-slot `created[]`
   flag marks success and the bounded join skips slots whose creation failed.
5. **D-1 (use-after-free on detach):** the heap `ctrl` block (and its `thread_ids`/`trampolines`/
   `created` arrays) is freed **only** when `detached_count == 0`; on a detached worker it is
   deliberately retained. This is exactly the safe behavior the design doc's D-1 verification
   describes.

**Classification:** COHERENT (by design). The edits are internally consistent. Note: because the
authorized build aborts on `softether_rudp.c`, the *compile* status of this file is INCONCLUSIVE
from this run (see §7) — the build never reached a verdict on it after the rudp.c failure. The
diff itself is syntactically straightforward C and low-risk, but it was not independently
confirmed to compile.

**New top-level symbols added:**
- `race_control_t` — `typedef struct race_control_s race_control_t;` (forward decl) and
  `struct race_control_s { volatile int cancel_flag; volatile int winner_claimed; int
  winner_index; uint32_t race_timeout_ms; uint64_t race_deadline_ms; pthread_t* thread_ids;
  race_trampoline_t* trampolines; int* created; };`
- `race_remaining_budget_ms(race_control_t* ctrl)` — `static uint32_t` helper.
- New members of `transport_thread_ctx_t`: `char host[256];`, `char resolved_ip[64];`,
  `char connect_host[256];`, `int index;`, `race_control_t* ctrl;` (replacing the previous
  `const char*` pointers and the `const volatile int* cancel_flag`).

**Audit-finding cross-check (STEP 4e):**
- `cancel_flag` lifetime (stack vs heap): **ADDRESSED** — moved to heap `race_control_t`
  (hunks ≈ 1696–1756, 2338–2350).
- `thread_ids`/`trampolines` lifetime: **ADDRESSED** — moved to heap `ctrl` block, freed only
  when no thread detached (hunks ≈ 2508–2542).
- `pthread_create` return handling: **ADDRESSED** — `created[]` flag + skip in bounded join
  (hunks ≈ 2462–2510).
- Shared winner arbitration: **ADDRESSED** — shared `winner_claimed`/`winner_index` CAS
  (hunks ≈ 1870–2000, 2462–2510).
- RUDP refcount (N-1): NOT TOUCHED by this file (handled in softether_rudp.c).
- NAT-T fd getter (N-2): NOT TOUCHED by this file.
- JNI null guard (JNI-01): NOT TOUCHED by this file.
- DHCP parser (N-05/N-08): NOT TOUCHED.
- raw I/O EAGAIN (N-06/N-09): NOT TOUCHED.
- socket timeout application (P1 scope extension): **ADDRESSED** — `socket_apply_timeout` on all
  handshake fds + `softether_additional_connect` (hunks ≈ 1817–2292, 3937).
- AC-04 (UDP-family TLS handshakes unbounded): **ADDRESSED** as a subset of AC-03.

---

## §6 Diff oddities (STEP 5)

Scan of `unknown_native_changes_backup.diff` (added `+` lines only):

| Scan | Result |
|---|---|
| a) New function definitions (`^\+.*name(...) {`) | Only one genuine new function: `race_remaining_budget_ms(race_control_t*)`; all other matches were `if (...)` blocks. No stray/orphan functions. |
| b) New `#include` lines | **0** |
| c) `TODO`/`FIXME`/`XXX`/`HACK` | **0** |
| d) `printf`/`fprintf`/`puts` | **0** |
| e) `exit(`/`abort(`/`assert(` | **0** |
| f) Lines mentioning `race_control_t` | 5 (struct forward-decl + `struct race_control_s` + usages) |
| g) Lines mentioning `winner_claimed`/`winner_index` | 16 (shared arbitration CAS + index assignments + parent reads) |
| h) Debug-only strings (`XXX_TEMP`/`DEBUG`/`TEST_ONLY`/`KILO`/`WIP`/`DO_NOT_COMMIT`) | **0** (`DEBUG` keyword on added lines: 0) |

**Interpretation.** The oddity profile is that of deliberate, production-grade engineering: no
debug scaffolding, no scratch markers, no forbidden runtime calls, no unexplained new
dependencies. There is **no signal** of random or "mixed-up" output from a malfunctioning
assistant. The only new top-level symbol is a single helper; the new types (`race_control_t`)
and fields are tightly scoped to the race-control refactor.

---

## §7 Compile-status check (STEP 6 — the only build command)

- **Command:** `CI=true ./gradlew :app:assembleProDebug --rerun-tasks` (run in background; no
  BindException occurred, so no `--stop` retry was needed).
- **Result:** **BUILD FAILED** (total elapsed **5m 51s**, 78 actionable tasks executed).
- **Failure location:** `:SoftEtherClient:buildCMakeDebug[arm64-v8a]` — the native (CMake/Clang)
  compile of `softether_rudp.c`.
- **Exact errors (verbatim):**
  ```
  D:/.../softether-core/src/proto/softether_rudp.c:100:17: error: call to undeclared function 'rudp_ref_release'; ISO C99 and later do not support implicit function declarations [-Wimplicit-function-declaration]
    100 |                 rudp_ref_release(ctx);
  D:/.../softether_rudp.c:111:13: error: static declaration of 'rudp_ref_release' follows non-static declaration
    111 | static void rudp_ref_release(rudp_context_t* ctx) {
  ```
- **Root cause (this investigation's reading):** the diff-added N-01 hardening block in
  `rudp_ref_acquire` calls `rudp_ref_release(ctx)` *before* that function's `static` definition
  later in the same translation unit, with no forward declaration. This is a **diff-induced
  compile error** (at HEAD, `rudp_ref_acquire` did not call `rudp_ref_release`).
- **APK:** NOT produced. Therefore no APK path, SHA-256, or byte size is available.
- **Status of the other 4 files:** INCONCLUSIVE from this run. The build aborted on the first
  hard error in `softether_rudp.c`; the remaining translation units
  (`softether_jni.c`, `rudp_transport.c`, `softether_nat_t.c`, `softether_protocol.c`) were
  queued but not verified to compile. Their diffs are syntactically simple/low-risk, but this
  investigation does **not** claim they compile — only that `softether_rudp.c` is confirmed
  broken. A fix + re-build is required to confirm the rest.

---

## §8 Cross-check against audit findings (STEP 7)

Verdict legend: ADDRESSED / PARTIALLY ADDRESSED / NOT TOUCHED / UNCLEAR.

| Audit finding | Verdict | Evidence (file : lines / hunk) |
|---|---|---|
| AC-01 detached native race threads use-after-return | **ADDRESSED** | softether_protocol.c — heap `race_control_t` + fixed `char[]` buffers (≈ 1696–1756, 2338–2350) |
| AC-02 no shared winner arbitration | **ADDRESSED** | softether_protocol.c — shared `winner_claimed`/`winner_index` CAS + `__sync_synchronize` (≈ 1870–2000, 2462–2510) |
| AC-03 configured timeout does not bound all native work | **ADDRESSED** | softether_protocol.c — `race_remaining_budget_ms` + `socket_apply_timeout` on every handshake fd + `softether_additional_connect` (≈ 1817–2292, 3937) |
| AC-04 UDP-family TLS handshakes unbounded | **ADDRESSED** (subset of AC-03) | softether_protocol.c — `socket_apply_timeout` on R-UDP direct / NAT-T / DNS / ICMP handshake fds |
| N-1 RUDP reference count pointer-publication race | **PARTIALLY ADDRESSED** (intent correct, does not compile) | softether_rudp.c — `rudp_ref_acquire` re-checks `destroying`; blocked by the §7 compile error |
| N-2 JNI NAT-T socket getter races destruction | **ADDRESSED** | softether_jni.c — `nativeGetUdpFd` snapshots fd under `connect_mutex` (≈ 505–525) |
| N-3 `pthread_create` failures not handled | **ADDRESSED** | softether_protocol.c — `created[]` flag + skip in bounded join (≈ 2462–2510) |
| N-5 DHCP parser trusts nested lengths | **NOT TOUCHED** | No DHCP parser change anywhere in the diff. NOTE: the brief's "N-05" differs from the diff's internal N-05 label, which here refers to the unrelated NAT-T backoff fix in softether_nat_t.c. |
| N-6 raw I/O EAGAIN spin | **NOT TOUCHED** (as described) | No EAGAIN-spin change in the diff. NOTE: the diff's internal N-06 label refers to JNI option range-validation in softether_jni.c, a different finding from the brief's "raw I/O EAGAIN spin". |
| N-12 JNI fd accessors not lifetime-safe | **ADDRESSED** | softether_jni.c — same `nativeGetUdpFd` change as N-2 |
| JNI-01 nativeSendSlice null guard | **ADDRESSED** | softether_jni.c — `nativeSend` NULL guard (≈ 256) |
| (extra) N-04 ICMP port-0 validity | **ADDRESSED** | rudp_transport.c — port-0 accepted for ICMP mode |
| (extra) N-05-internal NAT-T backoff reversal | **ADDRESSED** | softether_nat_t.c — `exp = min(num_tries, MAX)` |
| (extra) N-06-internal option range checks | **ADDRESSED** | softether_jni.c — timeout/port/bool range validation |
| (extra) N-10 fd leak on nonblocking failure | **ADDRESSED** | rudp_transport.c — `close(fds[0/1])` before `goto fail` |
| (extra) P5b / handshake-timeout bounding | **ADDRESSED** | softether_protocol.c — `socket_apply_timeout` everywhere + `additional_connect` |
| (extra) D-1 ctrl retention on detach | **ADDRESSED / SAFE** | softether_protocol.c — `ctrl` freed only when `detached_count == 0` |

**Summary of the cross-check.** The diff directly targets 10 of the brief's listed findings
(AC-01, AC-02, AC-03, AC-04, N-1, N-2, N-3, N-12, JNI-01, plus D-1) and additionally fixes
several other legitimate native defects (N-04, N-05-internal, N-06-internal, N-10, P5b). The two
brief findings it does **not** touch (N-5 DHCP parser, N-6 EAGAIN spin) are distinct from the
diff's own internal N-05/N-06 labels, which refer to *different* fixes — a labeling collision in
the brief, not a gap in the diff. The single blocking problem is the N-1 change in
`softether_rudp.c`, which fails to compile.

---

## §9 Recommendation (STEP 8)

**Recommendation: FIX** (with a provenance caveat — see below).

**Justification.**
1. The changes are **coherent and purposeful**, not garbage. Every hunk is a correct, defensive
   hardening that maps to a named audit finding; the oddity scan is clean (no debug strings, no
   TODO/FIXME, no `printf`/`exit`/`abort`/`assert`, no unexpected includes). The diff comments
   reference the same PHASE naming and audit document (`AUDIT_AUTOCONNECT_LOGIC.md`) as the
   repository's committed native-hardening history and as the existing
   `AUTOCONNECT_STATE_MACHINE_DESIGN.md` (whose PART 0 D-1 section *describes this exact
   `race_control_t` work*). This is the profile of an intentional, reviewed patch — not output
   from a malfunctioning assistant that was "mixing things up."
2. **But the working tree does not compile** (§7). The N-01 change in `softether_rudp.c` calls
   `rudp_ref_release` before its `static` definition with no forward declaration. This is a
   localized, easily-repairable defect (add a forward declaration of `rudp_ref_release`, or move
   its definition above `rudp_ref_acquire`). Until fixed, KEEP/commit is impossible.
3. Therefore:
   - **KEEP** is inappropriate — the tree will not build.
   - **REVERT** is inappropriate — the diff is not garbage and discards legitimate, audited
     hardening.
   - **INVESTIGATE-FURTHER** is partially warranted (provenance still unconfirmed by the
     operator; full compile of the other 4 files unconfirmed), but the evidence already strongly
     indicates these are the intended PHASE-1 native hardening changes rather than unauthorized
     Kilo Code output.
   - **FIX** is the balanced call: repair the `rudp_ref_release` ordering, re-run the build to
     confirm the remaining four files compile, and *then* the patch becomes a sound KEEP/commit
     candidate.

**Provenance caveat for the operator.** If, after reviewing this report, you confirm you never
authorized *any* native work in this repository, then **REVERT** (`git restore` the five files)
is the safe choice regardless of code quality — but the on-disk evidence (design-doc consistency,
audit mappings, commit history) argues these are the planned PHASE-1 changes, not random
leftovers. The large untracked evidence inventory (§2) is a separate provenance question and was
not modified or characterized as source in this investigation.

---

## §10 Explicit no-change confirmation

- **No `git commit`, `push`, `tag`, `stash`, `reset`, `clean`, `checkout`, or `restore` was performed.**
- **No source file was modified** (no native, Kotlin, Java, test, build, manifest, or resource
  file). The only files created are the two authorized outputs:
  - `C:\Users\PMC\AppData\Local\Temp\unknown_native_changes_backup.diff` (backup, OUTSIDE repo)
  - `C:\Users\PMC\AppData\Local\Temp\unknown_native_changes_status.txt` (status, OUTSIDE repo)
  - `INVESTIGATE_UNKNOWN_NATIVE_CHANGES_REPORT.md` (this report, INSIDE repo)
- **The backup patch file was created OUTSIDE the repository** (in `%TEMP%` /
  `C:\Users\PMC\AppData\Local\Temp`).
- **The only build executed was** `CI=true ./gradlew :app:assembleProDebug --rerun-tasks`. No
  test was run; no device command was issued; no source was edited to "fix" the build failure
  (the raw failure was preserved as required).
- **C5 was not executed.**
- `PROJECT_SPECIFICATION.md`, `AUTOCONNECT_STATE_MACHINE_DESIGN.md`, and all existing reports
  were left untouched.
