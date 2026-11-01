# AUTOCONNECT-FIX-4 (v1.0.34) — Bounded RUDP Fallback + Wedge on Terminal

**Phase:** AUTOCONNECT-FIX-4-v1.0.34
**Date:** 2026-10-18
**Repo:** `C:\Dev\SoftEthergai` · branch `main`
**Nature:** device-free. No adb command was issued; the attached device was not used.

---

## §1 — Verdict

**COMPLETE**, with one pre-condition deviation disclosed in §2.

The two structural causes the v1.0.33 device run exposed are closed:

1. **Fix-A (native).** The NAT-T/RUDP fallback handshake is now bounded the same way the TCP
   handshake is, and carries the same `[TLS] handshake entry` discriminator tagged `(RUDP)`.
   Before this revision the RUDP BIO read could block indefinitely, which is why *every* slot-wait
   budget failed identically on the same endpoint.
2. **Fix-B (Kotlin).** A `NATIVE_BUSY` terminal now records a wedge, so the process-scoped ledger
   accumulates across runs and the unchanged `EXCLUSION_THRESHOLD` (2) actually applies. The
   v1.0.32 per-run counter is structurally unable to reach its threshold on a first-attempt
   terminal.

Tests pass with the exact expected delta (`:app` +2, `:SoftEtherClient` unchanged); the build
succeeds; the standalone build gate of the pushed tree succeeds. The endpoint's own refusal cause
remains **UNKNOWN** and was not attempted.

---

## §2 — Pre-conditions (STEP 1)

| Check | Value | Label |
|---|---|---|
| `git rev-parse HEAD` | `518c9b767cff168acf74dfb313cca0487193b5e5` | SOURCE CODE |
| `git rev-parse origin/main` | `518c9b767cff168acf74dfb313cca0487193b5e5` | SOURCE CODE |
| HEAD == origin/main | yes | SOURCE CODE |
| Branch | `main` | SOURCE CODE |
| Spec version before edits | `1.0.33` (line 8) | SOURCE CODE |
| Tag count | **5** (`p2.4.5`, `p2.4.7`, `p2.4.8`, `p2.4.9`, `p2.4.10`) | SOURCE CODE |

### ⚠ Disclosed deviation — the working tree was NOT clean

`git status --porcelain` reported **two tracked modifications** at the moment of the check:

```
 M SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_protocol.c
 M app/src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt
```

The brief's STEP 1 says to abort on *any* tracked modification. The two files are, however,
**exactly two of the four authorized files**, and their uncommitted content is **exactly FIX-A and
FIX-B as specified in STEP 3 and STEP 5** — an interrupted prior application of this same block
(no test, spec, or report work had been done). Every line was verified against the brief before
proceeding:

* the native hunk adds `socket_apply_timeout(conn->socket_fd, conn->timeout_ms)` before
  `perform_tls_handshake` in the fallback branch, the `(RUDP)` discriminator, and a fail-open
  warning identical in style to the TCP site — SOURCE CODE;
* the Kotlin hunk adds the wedge-before-terminal block, the `giveUpWithUnresolvedNativeOperation`
  KDoc narrowing, and the `recordWedge` KDoc note — SOURCE CODE;
* no other tracked file was dirty, so no unrelated work could be contaminated.

The judgement to continue rather than abort is **INFERENCE** (the intent of the abort rule is to
detect foreign or unrelated work, which this was not). It is recorded here rather than silently
absorbed; if the operator prefers a hard abort, the correct action is to discard the two hunks and
re-run the block.

All other ABORT conditions were clear.

---

## §3 — Device findings that drive this phase (summary)

Source: `DEVICE_VERIFY_AUTOCONNECT_FIX_3_REPORT.md` §7.3 / §7.4 — **PHYSICAL DEVICE**.

| # | Finding | Label |
|---|---|---|
| 1 | The TCP TLS path is bounded: `socket_apply_timeout` at `softether_protocol.c:2839`, `SO_RCVTIMEO applied=yes`, expiring correctly. | PHYSICAL DEVICE + SOURCE CODE |
| 2 | The NAT-T/RUDP fallback calls `perform_tls_handshake` with **no** timeout application and **no** discriminator. | SOURCE CODE |
| 3 | On `vpn131251792` the RUDP transport reached ESTABLISHED and the handshake then blocked for **28.5 s of complete silence**, ended only by forced teardown. | PHYSICAL DEVICE |
| 4 | The `max_attempts=100` guard never engaged: zero `SSL handshake attempt N` and zero `did not complete after 100 attempts` lines in any run, because the first `SSL_do_handshake` never returned. | PHYSICAL DEVICE |
| 5 | Therefore any timeout budget fails identically on this endpoint, and the process-wide native slot stays held. | PHYSICAL DEVICE + INFERENCE |
| 6 | `nativeBusyThisServer` never reaches `NATIVE_BUSY_WEDGE_THRESHOLD` in one run: the first `NATIVE_BUSY` terminal ends the run, so the ledger is never written and the same endpoint is re-selected — **5/5 runs**. | PHYSICAL DEVICE |

---

## §4 — Fix-A (native): before / after

**File:** `SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_protocol.c`

### Before

```c
        result = softether_try_nat_t_connect(conn, host, resolved_ip, port);
        if (result == ERR_NONE) {
            result = perform_tls_handshake(conn, connect_host);   // :2908 — unbounded
```

### After

```c
        result = softether_try_nat_t_connect(conn, host, resolved_ip, port);
        if (result == ERR_NONE) {
            int so_timeout_applied =
                (socket_apply_timeout(conn->socket_fd, conn->timeout_ms) == 0);   // :2935

            LOGD("[TLS] handshake entry (RUDP): conn->timeout_ms=%d, "
                 "socket_fd=%d, SO_RCVTIMEO applied=%s",                          // :2941
                 conn->timeout_ms, conn->socket_fd,
                 so_timeout_applied ? "yes" : "no");

            if (!so_timeout_applied) {
                LOGW("TLS handshake over RUDP proceeding WITHOUT a socket "
                     "timeout (fd=%d, timeout_ms=%d); a stalled handshake can "
                     "still block indefinitely",
                     conn->socket_fd, conn->timeout_ms);
            }

            result = perform_tls_handshake(conn, connect_host);                   // :2953
```

Plus the explanatory comment block at `:2908-2934` recording the device evidence and the fd
identity.

**Why this is the right lever, not a hopeful one — SOURCE CODE:**

* `conn->socket_fd` on this path is `rudp_transport_get_fd(tr)` (`softether_protocol.c:1724`), the
  app-facing end of the RUDP socketpair created at `rudp_transport.c:1214`
  (`socketpair(AF_UNIX, SOCK_STREAM, 0, fds)`).
* `perform_tls_handshake` passes that fd straight to `ssl_connect`
  (`softether_protocol.c:1236`), so it *is* the fd OpenSSL reads from.
* `socket_apply_timeout` (`tcp_socket.c:469`) sets `SO_RCVTIMEO` **and** `SO_SNDTIMEO`, so the
  blocking `recv()` returns `EAGAIN` and the existing `WANT_READ`/`WANT_WRITE` ladder in
  `aes_wrapper.c` takes over.
* The same file already relies on exactly this for the parallel UDP race path
  (`softether_protocol.c:2036`), on the same kind of fd. That is the precedent.

**Fail-open, matching the TCP site:** a helper failure logs `LOGW` and proceeds, so a regression in
the helper can never turn a would-be successful connect into a failure.

**Not changed:** the TCP branch, `perform_tls_handshake` itself, and every other native source file.

### STEP 4 — NOT applied (deliberate)

STEP 4 (add a deadline check inside the RUDP BIO read) was **not** applied. Reason, SOURCE CODE:

* The repository installs **no custom BIO** for the RUDP transport. There is no `BIO_read` /
  `BIO_write` implementation anywhere under `src/crypto/` or `src/rudp/` (the `src/rudp/` directory
  does not exist; the RUDP code lives in `src/proto/softether_rudp.c` and `src/proto/rudp_transport.c`).
* The blocking read is therefore OpenSSL's own **socket BIO** `recv()` on the socketpair fd — code
  we do not own and must not fork.
* `rudp_recv` (`softether_rudp.c:1120`) is the transport's receive path on the *other* side of the
  socketpair. It is not the call the handshake blocks in; adding a deadline there would not bound
  the handshake.

There is no "simple, short function with a single blocking call" to instrument, so the brief's
SKIP branch applies. The socket option is the correct and only lever, and Fix-A applies it. This is
a deliberate skip, not an omission.

---

## §5 — Fix-B (Kotlin): before / after

**File:** `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt`

### Before

```kotlin
                    val released = adapter.awaitNativeSlotRelease(nativeSlotAwaitMs)
                    if (!released) {
                        val waitedMs = System.currentTimeMillis() - slotWaitStartedAt
                        // ... SLOT_UNRELEASED_DIAG, giveUp, Error(ERROR_NATIVE_UNRESOLVED), return
                    }
```

The terminal recorded **nothing** against the server. That was correct for the D-5 case it was
written for (our own previous operation still running; the server was never contacted), but on this
endpoint the terminal is the *only* reachable outcome, so the ledger was never written and
`WedgeTracker.isExcluded` never became true.

### After

```kotlin
                    if (!released) {
                        if (!wedgedThisServer) {
                            adapter.log(
                                "[AUTO] Recording a wedge on the NATIVE_BUSY terminal for " +
                                        "${server.hostname ?: server.ip} (the endpoint itself " +
                                        "never accepted the native attempt)",
                            )
                            recordWedge(server, key)
                            wedgedThisServer = true
                        }
                        val waitedMs = System.currentTimeMillis() - slotWaitStartedAt
                        // ... unchanged: SLOT_UNRELEASED_DIAG, giveUp, Error, return
                    }
```

### The design-change rationale

The AUTOCONNECT-AUDIT **D-5** rule ("a give-up caused by our own unresolved operation records
nothing") is **narrowed, not reverted**:

* It stays true for the case it was written for — a give-up at the **post-attempt** site, where our
  own previous operation was still running and the server was never contacted. That site still
  records nothing, and `giveUpWithUnresolvedNativeOperation` itself still records nothing.
* It was wrong as an *absolute* for the **terminal** case, because device evidence proved the
  endpoint itself produces the hang: TCP connect and the TCP TLS handshake succeed, the VPNCONNECT
  watermark is rejected, and the NAT-T/RUDP fallback handshake then blocks (unbounded before
  v1.0.34). A `NATIVE_BUSY` terminal here **is** evidence about this endpoint, not merely about our
  own bookkeeping.
* The strike is cleared on any verified tunnel (the existing `wedgeTracker.clear` on a connected
  run), so a transient cause that later resolves does not exclude the endpoint permanently.

The KDoc on `giveUpWithUnresolvedNativeOperation` (`:2012-2025`) and on `recordWedge` records the
narrowing explicitly, so a future reader cannot mistake it for a revert of D-5.

---

## §6 — Tests added

`app/src/test/java/vn/unlimit/vpngate/automode/AutoModeControllerTest.kt`. Two tests added; **no
existing test modified or deleted**. The only change outside the two new tests is an optional
`wedgeTracker` parameter on the private `controller(...)` helper, defaulting to a fresh
`WedgeTracker()` — behaviourally identical for every pre-existing test, and required because the
whole point of Fix-B is that the ledger survives *across* runs.

| Test | What it pins | Label |
|---|---|---|
| `aNativeBusyTerminalRecordsAWedgeThatSurvivesAcrossRuns` (T-NEW-11) | Run 1 (slot never releases) ends `ERROR_NATIVE_UNRESOLVED`, logs `Recording a wedge on the NATIVE_BUSY terminal`, `wedgeCountFor == 1`. Run 2 on a **fresh controller with the same ledger** reaches `== 2`. Run 3 is skipped — `ERROR_NO_SERVER`, `excluded for this session` logged, and **zero dials**. Before v1.0.34 all three runs would have shown `0` and run 3 would have dialled again. | TEST |
| `theRudpFallbackHandshakeIsBoundedLikeTheTcpPath` (T-NEW-12) | Source shape of `softether_protocol.c`: the fallback block contains `socket_apply_timeout(conn->socket_fd, conn->timeout_ms)`; `[TLS] handshake entry (RUDP)` exists; the TCP discriminator survives; and **exactly two** handshake sites apply the bound (TCP + NAT-T/RUDP), so the fix cannot be paid for by weakening the path that was already correct. | TEST |

T-NEW-12 is a source-shape guard for the same reason the other source-shape tests in this file are:
the C transport cannot be executed from a JVM unit test.

---

## §7 — Spec edits E1–E9

| Edit | Where | Result |
|---|---|---|
| E1 | Header | version `1.0.34`; HEAD `518c9b7…`; date unchanged at 2026-10-18. HEAD is the **baseline** commit because a commit cannot carry its own hash; the push-state note says so explicitly. |
| E2 | §1.6.1 (appended blockquote) | v1.0.34 push-state note added; existing notes untouched. |
| E3 | §6.8.3 | Third wedge sub-rule ("Terminal wedge (v1.0.34)") added, citing `DEVICE_VERIFY_AUTOCONNECT_FIX_3_REPORT.md` §7.4. The lead-in "Two outcomes write a strike" was corrected to "Three" so the list and its own summary cannot disagree. |
| E4 | §6.8.3 | "The native timeout invariant (added v1.0.34)" paragraph added, naming both bounded sites (`:2839`, `:2908`/post-edit `:2935`). |
| E5 | New §1.6.26 | Nine bullets exactly as briefed, including the STEP-4 skip decision and the D-5 narrowing. |
| E6 | §12.5.1, §12.5.2 | **No changes** (verified: no diff hunk in that range). |
| E7 | §20 Changelog | `1.0.34` row added. |
| E8 | §20 Verification Status | §1.6.26 row added as **HISTORICAL**, with per-claim evidence labels. |
| E9 | §22 | **Untouched** — see §11. |

---

## §8 — JVM tests (before / after)

| Suite | Baseline (v1.0.33) | This revision | Delta |
|---|---|---|---|
| `:app:testProDebugUnitTest` | 801 / 0 / 0 / 1 | **803 / 0 / 0 / 1** | +2 tests, skip unchanged |
| `:SoftEtherClient:test` | 173 / 0 / 0 / 0 | **173 / 0 / 0 / 0** | unchanged |

Both run with `CI=true`, `--rerun-tasks --no-build-cache`, `BUILD SUCCESSFUL`. Counts read from the
JUnit XML under `build/test-results/`, not from console output. Label: TEST.

---

## §9 — Build result + APK SHA-256

`:app:assembleProDebug --rerun-tasks --no-build-cache` under `CI=true` → **BUILD SUCCESSFUL**
(114 tasks executed; the native library was rebuilt because `softether_protocol.c` changed).

| | v1.0.33 | v1.0.34 |
|---|---|---|
| APK SHA-256 | `42D90C3889ABF4ECF31A7E1487A9B05C795CDFD38D1FE136D50309AECAB9D14E` | **`4ABC463606B83217D75422136DA6678097DAAC666D63BF5AB3B7BA6A624B1435`** |
| Size (bytes) | 63,749,658 | 63,751,914 |

The hash **differs** as expected: the `.so` content changed (+2,256 bytes). Label: TEST.

---

## §10 — Diff-scope

```
 M SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_protocol.c
 M app/src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt
 M app/src/test/java/vn/unlimit/vpngate/automode/AutoModeControllerTest.kt
 M PROJECT_SPECIFICATION.md
?? AUTOCONNECT_FIX_4_REPORT.md
```

Exactly the four authorized files plus the one authorized new file. No build file, no manifest, no
other test, no other native source. Untracked pre-existing artifacts (`emergency/`, `scripts/`,
`test-evidence/`, the older reports, `.workbuddy-ai/`) were left alone and were **not** staged.
Label: SOURCE CODE.

---

## §11 — §22 byte-identity

```
§22 IDENTICAL
lines/bytes now:   99 6504
lines/bytes head:  99 6504
```

The §1.6.2–§1.6.25 range is likewise byte-identical to HEAD (1,669 lines compared with
`diff -q` → identical), and §12.5.1/§12.5.2 show no diff hunk. Label: SOURCE CODE.

---

## §12 — Negative assurance

| Claim | Status |
|---|---|
| Device / adb command issued | **no** |
| Build or test run without `CI=true` | **no** |
| `git add -A` / `git add .` | **no** |
| `git push --force`, `reset`, `clean`, `stash`, `checkout`, `restore` | **no** |
| Tag operation | **no** |
| Test deleted | **no** |
| Existing report modified | **no** |
| `NATIVE_SLOT_AWAIT_MS` / `NATIVE_SLOT_AWAIT_MAX_MS` changed | **no** |
| C5 executed | **no** |
| `D:\PMC\Documents\Android\SoftEthergai` touched | **no** |
| `GlobalVpnTracker.kt` / `AndroidConnectionAdapter.kt` touched | **no** |
| Any native file other than `softether_protocol.c` touched | **no** |

---

## §13 — Device re-verification plan (proposed only, NOT begun)

Nothing below was executed. This is the plan for the next phase.

1. Install the v1.0.34 debug APK and run Auto Mode against the same endpoint set that produced the
   v1.0.33 evidence.
2. **Bound check.** In logcat, expect `[TLS] handshake entry (RUDP): … SO_RCVTIMEO applied=yes` on
   the fallback path. If the endpoint still stalls, the next lines must be
   `SSL read: SO_RCVTIMEO expired` followed by an `SSL handshake attempt N` sequence — i.e. the
   `max_attempts` loop becomes reachable, which it never was before.
3. **Slot check.** Expect `SLOT_UNRELEASED_DIAG` count **0** and at least one
   `[AUTO] Native slot released after …ms` line.
4. **Wedge check.** Expect `Recording a wedge on the NATIVE_BUSY terminal` on run 1, again on run 2,
   and on run 3 `Skipping …: excluded for this session after 2 wedges` with **no dial** to that
   endpoint.
5. If the endpoint's own refusal persists but the bound holds, the run must now rotate to a healthy
   server instead of dying — that is the success criterion, not the endpoint becoming usable.
