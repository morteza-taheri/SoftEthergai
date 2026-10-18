# COMMIT-C3-FIX-AND-TRIAGE-C5-STALE — Report

**Phase:** `COMMIT-C3-FIX-AND-TRIAGE-C5-STALE`
**Date:** 2026-10-13
**Language:** English only.

---

## 1. Executive Verdict

```
C3 COMMITTED — TRIAGE COMPLETE WITH CLASSIFICATIONS
```

The C3 verdict fix is committed as a single test-only commit (`4c013e8`). The read-only triage resolves both findings:
- **C5 flakiness → (A) PRE-EXISTING TEST DEFECT** (the silent-responder's frame classification is too strict: any non-DISCOVER frame — or a Phase-1 read anomaly — is turned into a fatal `responder_error` that fails the run). The production retry DISCOVER is legitimate and is **not** the cause. Exact triggering frame is (D) UNKNOWN from source-only triage.
- **STALE_DONE 27% → (A) EXPECTED VARIANCE** in the ACK-vs-teardown race timing; not a production defect (deep review rates the DHCP path P3_LOW; F.28AL-U makes a late/stale result safe by construction).

---

## 2. Pre-Conditions

| ID | Check | Result |
|----|-------|--------|
| P1 | Device enumerates `5585fecc` / `2201117TG` | **PASS** — `5585fecc device product:spes_global model:2201117TG` |
| P2 | HEAD is `38d9d33` (test40 budget fix) | **PASS** — `38d9d338c22e589c9467d714c0bd4974dbb1e091` |
| P3 | C3 edit present: `git diff --stat` = 26 insertions / 0 deletions / one file; `git status --short` = `M native_test.c` + untracked reports/logs | **PASS** — exactly as expected |

---

## 3. Commit Made

**I1 — staged exactly one file** (`git diff --cached --name-only`):
```
SoftEtherClient/src/main/cpp/test/native_test.c
```
No `git add -A` / `git add .` was used.

**I2 — the commit** (`git log -1 --stat`):
```
commit 4c013e86c762f4d3a32baea810bec58294799226
Author: morteza.taheri <morteza.taheri@live.com>
Date:   Tue Oct 13 13:06:11 2026 +0330

    test(native): align C3 verdict with C4 "observe the race" semantics

    C3 targets the same REQUEST-vs-ACK race as C4, but its
    stale == 0 clause contradicted C4's own in-source comment
    (:3334-3338) that a stale success IS the genuine observation
    of the ordering. The race cannot be prevented inside
    dhcp_client.c as written (entry-only state check :414;
    time-only wait loop :339). The consumer discards stale results
    at two layers (ConnectionController :1343 / :466-468), so the
    observation is safe.

    Fix: C3's verdict becomes ok = !harness_bad && premise > 0;
    identical in shape to C4. A new STALE_DONE_RATE_HIGH diagnostic
    is appended to the message when staleDone > 25% of iterations
    (informational only; not a pass/fail criterion).

    Device-verified: test27/C3 now PASSES (n=100, staleStart=53,
    staleDone=27, ok=1).

    Test-only; no production code changed.

 SoftEtherClient/src/main/cpp/test/native_test.c | 26 +++++++++++++++++++++++++
 1 file changed, 26 insertions(+)
```

**I3 — verification:**
- `git rev-parse HEAD` → `4c013e86c762f4d3a32baea810bec58294799226`
- `git rev-parse origin/main` → `5c13603c6276c11460982e0633203667d26ef3e7` (**unchanged** — no push)
- Working tree: no tracked modifications remain (only untracked report/log files).

---

## 4. T1 — C5 Responder Error Findings

**Relevant code read:**
- `B3S_CASE_C5` — `native_test.c:2273` (`// genuine timeout control (silent responder) — gated`)
- `b3s_responder_thread` — `native_test.c:2794-2852` (Phase-1 sniff at `:2818-2847`; C5 branch at `:2849-2852`)
- `b3s_drain_until_return` — `native_test.c:2652-2699`
- Responder error enum — `native_test.c:2287-2296`
- Frame reader `b3s_read_message` — `native_test.c:2427-2442`
- Classifier `b3s_parse_client_frame` — `native_test.c:2500-2540`
- DHCP client — `SoftEtherClient/src/main/cpp/softether-core/src/net/dhcp_client.c`

### (a) What frame does the silent responder classify as "unexpected"?
For C5, `responder_error` is set to `B3S_RERR_UNEXPECTED_FRAME` (or a Phase-1 variant) by exactly these sites:

| Site | Trigger | Value |
|------|---------|-------|
| `native_test.c:2678-2680` | a DHCP **REQUEST** frame observed in the drain | `B3S_RERR_UNEXPECTED_FRAME` |
| `native_test.c:2681-2684` | **any other** frame that is not ARP / DISCOVER / REQUEST (i.e. `b3s_parse_client_frame` returned neither 1 nor 3) | `B3S_RERR_UNEXPECTED_FRAME` |
| `native_test.c:2687-2690` | read returned `-4` = **KEEP_ALIVE_MAGIC** (`count == 0xFFFFFFFF`, `:2436`) | `B3S_RERR_UNEXPECTED_FRAME` |
| `native_test.c:2824-2827` | Phase-1 sniff failed (timeout / EOF / keepalive / malformed) | `DISCOVER_TIMEOUT` / `EOF_EARLY` / `UNEXPECTED_FRAME` / `DISCOVER_MALFORMED` |
| `native_test.c:2839` | Phase-1 frame was not a DISCOVER or MAC mismatch | `B3S_RERR_DISCOVER_MALFORMED` |

Critically, **a retry DISCOVER is explicitly EXPECTED for C5** and does **not** set an error (`native_test.c:2671-2675`). ARP frames are also non-fatal (`:2669-2670`).

### (b) Does the DHCP client send a RETRY DISCOVER during the 3×5s timeout?
**YES.** `dhcp_client.c:427-447`:
```c
for (int retry = 0; retry < DHCP_MAX_RETRIES; retry++) {   // :427
    ... build DISCOVER ... softether_send_raw(...)          // :429-440
    int msg_type = wait_dhcp_response(..., DHCP_TIMEOUT_MS); // :442  (5000 ms)
    if (msg_type != DHCP_OFFER) { ...; continue; }           // :444-447
}
```
- `DHCP_MAX_RETRIES = 3` — `dhcp_client.c:60`
- `DHCP_TIMEOUT_MS = 5000` — `dhcp_client.c:59`
- Terminal failure — `dhcp_client.c:486` (`DHCP failed after 3 retries`)
→ 3 DISCOVERs over ≥15 s, matching the harness bound `B3S_C5_MIN_ELAPSED_MS 14000` (`native_test.c:2307`).

### (c) Is the retry legitimate — test defect or production defect?
The retry is **legitimate production behaviour** (standard RFC-2131 DISCOVER retransmission, `dhcp_client.c:427`) and the harness **correctly does not flag it** for C5 (`:2671-2675`). Therefore **neither the retry nor a "wrong classification of the retry" is the cause**. The C5 failure must come from a **non-DISCOVER** frame (or a Phase-1 anomaly).

### (d) Exact set-site and frame type
- **Exact set-sites for C5:** `native_test.c:2678-2680` (REQUEST), `:2681-2684` (other/unparseable), `:2687-2690` (KEEP_ALIVE_MAGIC), plus Phase-1 `:2824-2827` / `:2839`.
- **Exact frame type: UNKNOWN from source-only triage.** The run message (`resp=1`) does not carry the frame identity, and this phase forbids on-device logging/re-runs. Source analysis **narrows** the candidates:
  - a **REQUEST** is improbable (the C5 responder never sends an OFFER, so the client has nothing to REQUEST);
  - a **keepalive** is improbable on this path — the DHCP wait loop uses `softether_receive_raw` (`dhcp_client.c:396`), which does **not** call `softether_maintain_links` (only `softether_receive` `:3117` and `softether_receive_batch` `:3237` do), so no keepalive is emitted during the C5 window;
  - ⇒ the most probable trigger is the **"other/unparseable frame" branch (`:2681-2684`)** or a **Phase-1 read anomaly (`:2824-2827`)**, both of which are harness-side.

**Bottom line:** the failure is entirely inside the **test harness's** `responder_error → harness_bad` path; production behaviour is correct.

---

## 5. T2 — STALE_DONE 27% Findings

### (a) When does a result land in STALE_DONE (vs STALE_STARTED)?
Classification — `native_test.c:3226-3229`:
```c
cat = !ctx.disc_started_at_return ? B3S_CAT_BEFORE
    : (!ctx.disc_done_at_return ? B3S_CAT_STALE_STARTED
                                : B3S_CAT_STALE_DONE);
```
- **BEFORE** — the DHCP success returned before any teardown began (`disc_started_at_return == 0`).
- **STALE_STARTED** — at the moment `softether_do_dhcp` returned, teardown had **started but not completed** (`disc_started == 1, disc_done == 0`): the success landed *inside* the REQUEST-vs-ACK / teardown window.
- **STALE_DONE** — at return, teardown had **already completed** (`disc_done == 1`): the success was published *after* the teardown finished.

**Timing difference:** STALE_STARTED = teardown still in flight at publication; STALE_DONE = teardown already finished at publication (a strictly later landing). Both are the *same* production ordering (success published after a disconnect began); the split is only "how far the teardown had progressed".

### (b) Is 27% deterministic or random?
**Random (a scheduling race), not deterministic.** Cross-run evidence:
- prior C3 run: `staleStart=55 staleDone=16` (n=100) — `COMMIT_TEST40_FIX_AND_TRIAGE_C3_REPORT.md:213`
- this run: `staleStart=53 staleDone=27` (n=100) — `FIX_C3_EXPECTATION_STAGE_A_REPORT.md:152`

Total stale is stable (71 → 80) but the **STALE_STARTED/STALE_DONE split swings (16 ↔ 27)**. The split depends on whether the teardown thread completes before the DHCP thread finishes publishing — i.e. on thread scheduling at the `:463` publication point. That is a **timing race in the observed ordering**, **not** a change in production behaviour: the production code path is fixed (single publication site `dhcp_client.c:463`, entry-only state check `:414`, time-only wait loop `:339`). The variance is in *which side of the race wins*, not in *what the code does*.

### (c) Cross-check with `dhcp_client.c` timing
- For C3 the responder **does** send OFFER+ACK, so `wait_dhcp_response` returns quickly with `DHCP_ACK` and the client publishes `result->success = 1` (`dhcp_client.c:462-463`), sends the gratuitous ARP (`:476`) and returns 0 (`:477`). The REQUEST→publication interval is therefore **short** (an ACK round-trip over the socketpair — microseconds of protocol work).
- The C3 disconnect trigger fires at **REQUEST observation** (`native_test.c:2757-2758`, `B3S_TRIG_ON_REQUEST`). The race is therefore: does `softether_disconnect` (mutex + state transition + fd close) **complete** before the DHCP thread finishes its ACK processing and publishes?
- **Consistency with 27%:** a 27% STALE_DONE rate means teardown wins that race in ~1 of 4 iterations. Given the DHCP thread can be descheduled right after `softether_send_raw` (REQUEST), and the disconnect thread is already running, the teardown occasionally completing first is exactly the expected shape of a tight, unsynchronised race. The failure path (`timeout` at `:339`) is **not** involved in C3 — C3's stale results come from the *success* path, not the 5 s timeout path.

---

## 6. T3 — Prior Decisions on STALE_DONE

Searches performed (case-insensitive across `*.md`, plus targeted `PROJECT_SPECIFICATION.md` / `DEEP_REVIEW_VERIFY_REPORT.md`): "stale", "stale DHCP", "stale success", "STALE_DONE", "dhcp success after disconnect", "teardown completed", `B3s`, `C5`.

| Source | Finding |
|--------|---------|
| `PROJECT_SPECIFICATION.md` | **No entry** mentions the DHCP `STALE_DONE` category or declares it a defect. F.28AL-U is documented as the single-owner handle arbitration that makes a late result safe (`:219`, `:312`). "state publication after a rejected/stale lease" is listed only as an *open question* (`:298`). |
| `DEEP_REVIEW_VERIFY_REPORT.md:87` | Finding **N-17** (DHCP path) = **STILL_VALID (low), P3_LOW**: "the DHCP parser issues are captured under N-07 … **No additional defect beyond N-07**." The stale-publication ordering is **not** classified as a defect. |
| `COMMIT_TEST40_FIX_AND_TRIAGE_C3_REPORT.md:168,280-296` | The stale result is defined as the **premise/goal** of C3/C4 (`premise` = stale count), and the consumer is shown to **discard** a stale DHCP success safely (`ConnectionController.kt:1343` guard; authoritative `installTun` `:466-468`). No prior decision treats stale as a defect. |

**Answer:** STALE_DONE (the phenomenon) is considered **(a) NORMAL / accepted** — an observed ordering the consumer is designed to survive, not a defect. The **25% rate threshold is NEW** (introduced by commit `4c013e8` as a diagnostic only); there was **no prior decision on the rate** — so the *rate* dimension is **(c) undecided**, which is why it is emitted as a diagnostic rather than a failure.

---

## 7. T4 — Classifications

### C5 flakiness → **(A) PRE-EXISTING TEST DEFECT (responder classification too strict)**

| # | Evidence | Anchor |
|---|----------|--------|
| 1 | The failure is entirely within the harness: `responder_error != NONE` → `c6_resp_err` → `cat = B3S_CAT_RESP` → `harness_bad = 1` → the generic `else` verdict `ok = !harness_bad && …` = 0 | `native_test.c:3196`, `:3217-3218`, `:3327-3328`, `:3384` |
| 2 | The only traffic C5 legitimately produces (retry DISCOVERs) is **not** flagged | `native_test.c:2671-2675`; `dhcp_client.c:427-447` |
| 3 | The responder turns **any** non-ARP/non-DISCOVER/non-REQUEST frame (or a Phase-1 read anomaly) into a **fatal** error, making the verdict brittle | `native_test.c:2681-2684`, `:2687-2690`, `:2824-2827` |
| 4 | Same test PASSED in two other runs (`FIX_TEST40_BUDGET_AND_RE_RUN_REPORT.md:152` = PASS 44.2 s; `_c3fix_stageA2_results.log` re-run = PASS 30.4 s) and FAILED in two (`_stageA_results.log` 11:41; `_c3fix_stageA_results.log` 47.1 s) → flaky | cross-run logs |
| 5 | C5 is flagged in-source as **gated / requires separate approval** | `native_test.c:2273`; `NativeConnectionTest.kt:585-589` |

**Not (B):** production defect is ruled out — the retry DISCOVER is correct RFC-2131 behaviour and is not the trigger.
**Not (C):** "flaky by design" is weaker — the flakiness is an artefact of an over-strict classification, which is a (fixable) defect, not an intended probabilistic design.
**Residual (D):** the **exact** triggering frame could not be pinned from source alone (no device logging permitted this phase).

### STALE_DONE 27% → **(A) EXPECTED VARIANCE in race timing — accept and document**

| # | Evidence | Anchor |
|---|----------|--------|
| 1 | Run-to-run variance in the STALE_STARTED/STALE_DONE split (16 ↔ 27) over a stable total (71 ↔ 80) → scheduling-dependent, not code-dependent | `COMMIT_TEST40_FIX_AND_TRIAGE_C3_REPORT.md:213` vs `FIX_C3_EXPECTATION_STAGE_A_REPORT.md:152` |
| 2 | The classification depends only on `disc_done_at_return` at the moment of return — a pure interleaving outcome | `native_test.c:3226-3229`, `:2725` |
| 3 | The production path is fixed and safe; the consumer discards stale results at two layers | `dhcp_client.c:414/463/339`; `ConnectionController.kt:1343`, `:466-468` |
| 4 | No prior decision treats stale as a defect; deep review rates the DHCP path P3_LOW | `DEEP_REVIEW_VERIFY_REPORT.md:87`; `PROJECT_SPECIFICATION.md:219` |

**Not (C):** no production defect — the single publication site is unchanged and the ordering is an accepted observation.
**Not (B):** the 27% is within the natural spread of the same race (16→27 across two runs), so it does not yet evidence a *degrading* pattern; the diagnostic exists precisely to catch a future excursion.

---

## 8. T5 — Blast Radius (for defect classifications)

Only one classification is a defect — **C5 (A), test-only**:

| File | Change | Approx. delta |
|------|--------|---------------|
| `SoftEtherClient/src/main/cpp/test/native_test.c` | Harden the C5 responder classification: (i) record/log the **frame type** when `responder_error` is set (so the exact trigger becomes observable), and/or (ii) for `B3S_CASE_C5` treat a `responder_error` that is not a genuine DHCP anomaly as non-fatal (do not route it into `harness_bad`), keeping the timeout assertion (`timeout == 1`) as the pass criterion. | ~5–15 lines, test-only |

**No production files.** `dhcp_client.c`, `softether_rudp.*`, `softether_protocol.c`, `softether_jni.c`, Gradle/CMake, lifetime components: **no change**.

STALE_DONE (A) requires **no code change** — document only.

---

## 9. Evidence Taxonomy

| Tier | Item |
|------|------|
| **SOURCE CODE** (read directly this phase) | `native_test.c` (drain `:2652-2699`, responder `:2794-2852`, classifier `:2500-2540`, verdict `:3322-3385`, classification `:3226-3239`); `dhcp_client.c` (`:59-60`, `:427-447`, `:462-477`, `:396`); `packet_handler.c` (`:54`, `:443-520`, `:584-601`, `:790-823`); `softether_protocol.c` (`:3107`, `:3117`, `:3180-3219`, `:3228-3237`) |
| **CROSS-RUN LOG / PRIOR REPORT** | `_stageA_results.log` (11:41 C5 FAIL, 40 PASS); `_c3fix_stageA_results.log` (C5 FAIL, C3 PASS n=100 staleStart=53 staleDone=27); `_c3fix_stageA2_results.log` (C5 re-run PASS); `COMMIT_TEST40_FIX_AND_TRIAGE_C3_REPORT.md:213` (staleStart=55 staleDone=16); `FIX_TEST40_BUDGET_AND_RE_RUN_REPORT.md:152` (C5 PASS 44.2 s) |
| **INFERENCE** | C5 trigger branch narrowed to `:2681-2684` / `:2824-2827` (keepalive and REQUEST ruled improbable on the C5 path); STALE_DONE split = scheduling race |
| **UNKNOWN** | The exact frame type the C5 responder saw (`resp=1` carries no frame identity; device logging not permitted this phase) |

---

## 10. Explicit No-Change Confirmation

- **Source modified beyond the commit:** NONE. The only tracked change is commit `4c013e8` (`native_test.c`, 26 insertions). Working tree is clean of tracked modifications.
- **Device / APK / CI:** NONE touched. No install, no test run, no logcat, no settings change.
- **Push:** NONE. **Tag:** NONE. (`origin/main` still `5c13603`.)
- **Gradle / CMake / production C/C++ / lifetime components:** NONE.

---

## 11. Final Recommendation

```
PROCEED TO FIX-C5-TEST-DEFECT
```

C5 is classified **(A) PRE-EXISTING TEST DEFECT** — a brittle harness classification, fixable test-only in `native_test.c` (~5–15 lines) — while STALE_DONE is **(A) EXPECTED VARIANCE** requiring documentation only. A small C5-hardening phase would make Stage A deterministic and unblock Stage B cleanly.

Alternatives if a C5 fix is not desired now:
- **PROCEED TO STAGE-B** — accept C5 as a known-flaky, gated test (its source comment already marks it as requiring separate approval) and enter the T5-SLOT instrumentation phase.
- **PROCEED TO DOCS-UPDATE-v1.0.8** — record the C3 fix (commit `4c013e8`), the C5 test-defect finding, and the STALE_DONE 27% diagnostic.

**Do not** open a production-fix phase for either finding: neither is a production defect.

No push, no tag. Report in English only.
