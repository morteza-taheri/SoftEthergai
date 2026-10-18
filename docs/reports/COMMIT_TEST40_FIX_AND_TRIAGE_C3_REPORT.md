# COMMIT-TEST40-FIX-AND-TRIAGE-C3 — Report

**Phase:** `COMMIT-TEST40-FIX-AND-TRIAGE-C3`
**Scope:** (1) commit the test40 budget fix as a single commit; (2) READ-ONLY triage of the C3 stale-result failure.
**Verdict:** `TEST40 COMMITTED — C3 TRIAGED AS (B) OUTDATED TEST EXPECTATION`
**Recommendation:** `PROCEED TO ADJUST-C3-EXPECTATION`
**Language:** English only.

---

## SECTION 1 — Pre-conditions (P1–P3)

**P1 — device identity.** PASS.

```
5585fecc        device product:spes_global model:2201117TG transport_id:1
```

The phase was authorized to include the device in the pre-condition check only. **No device command was issued after P1.** The device was not touched further (no install, no `pm`, no `logcat`).

**P2 — HEAD.** PASS.

```
b0418cc2a06b2785387c66f35fd7c7085d10b24e
```

Matches the phase's stated P2 value (`b0418cc` = commit 2 of the N-16 fix; `3a2f442` = commit 1).

**P3 — the working-tree diff is limited to the test40 budget loop.** PASS.

`git status --porcelain` before staging showed exactly one modified tracked file — `SoftEtherClient/src/main/cpp/test/native_test.c` — plus untracked report `.md` files at the repo root. `git diff` on `native_test.c` contained **one hunk only**, in the N-16-A post-release wait loop (`500 → 9000`). No other file was modified. No source, Kotlin, Gradle, or CMake file was dirty.

---

## SECTION 2 — I1 — stage exactly one file

**I1.** PASS.

```
git add SoftEtherClient/src/main/cpp/test/native_test.c
```

Staged set, from `git diff --cached --name-only`: exactly one path. **No** `git add -A` and **no** `git add .` was used, as required. (The untracked `*_REPORT.md` files at the repo root were deliberately left untracked and were not staged.)

---

## SECTION 3 — I2 — the single commit

**I2.** PASS.

```
[main 38d9d33] test(native): widen N-16-A post-release budget to 90s
 1 file changed, 6 insertions(+), 2 deletions(-)
```

Message body, as specified by the phase:

* The N-16-A post-release wait budget was 500 iterations (5 s), which is below the real rendezvous cost of a `softether_connect_with_hub` to `127.0.0.1:1` — the device protocol log showed ~30 s spent in DNS → ICMP → NAT-T detection before `connect()` was even reached (`10:23:32.462 Connecting…` → `10:24:02.831 IPv4 server detected`). The load-bearing assertion (`completed_while_lock_held`) already passed; the post-release half produced a false failure purely from the under-sized budget.
* Budget widened to 9000 iterations (90 s) for a safe margin.
* On-device verification: test40 alone 68.5 s, in suite 51.8 s; tests 31–41 = 11/11 PASS.

Exactly **one** commit was created in this phase. No amend, no second commit, no tag.

---

## SECTION 4 — I3 — post-commit state

**I3.** PASS.

```
$ git log -1 --stat
38d9d33  test(native): widen N-16-A post-release budget to 90s
 SoftEtherClient/src/main/cpp/test/native_test.c | 8 ++++++--
 1 file changed, 6 insertions(+), 2 deletions(-)

$ git rev-parse origin/main
5c13603c6276c11460982e0633203667d26ef3e7
```

`origin/main` is still `5c13603` — unchanged, i.e. **no push occurred** and none was attempted. Local `main` is now 3 commits ahead of `origin/main` (the N-15 fix `3a2f442`, the N-16 fix `b0418cc`, and this test-only fix `38d9d33`).

---

## SECTION 5 — T1 — `dhcp_client.c` success-publication path

**File:** `SoftEtherClient/src/main/cpp/softether-core/src/net/dhcp_client.c` (488 lines).

**T1.1 — the success-publication site.** Exactly one, at **`:463`**:

```c
result->success = 1;
```

It is reachable only from the `if (msg_type == DHCP_ACK)` branch after the **second** `wait_dhcp_response` call at **`:460`**. There is no second write of `result->success` anywhere in the file.

**T1.2 — is there a state check before publishing?** **No.** The file contains exactly one read of `conn->state`, and it is the **entry guard** at **`:414`**:

```c
if (conn->state != STATE_CONNECTED) { LOGE("Cannot do DHCP: not connected"); return -1; }
```

That guard runs once, at entry, before the retry loop. Between `:414` and the `:463` publication there is **no** re-read of `conn->state` and **no** disconnect-flag check of any kind. The publication therefore does not consult connection liveness.

**T1.3 — does `wait_dhcp_response` check `STATE_DISCONNECTING` between `recv()` attempts?** **No.** `wait_dhcp_response` (`:330–409`) is a **purely time-based** loop:

```c
while (elapsed < timeout_ms) { ... }
```

at **`:339`**. Inside the loop it polls (`has_queued`/`poll()` at `:387`), calls `softether_receive_raw` at `:396`, and parses (`parse_dhcp_response` at `:403`). It **never reads `conn->state`** and has **no `STATE_DISCONNECTING` test** at any point.

**T1.4 — does `softether_disconnect`'s state change propagate to the polling condition?** **No.** The polling condition is `elapsed < timeout_ms` — a local clock expression. It cannot observe a state transition. A concurrent `softether_disconnect` setting `STATE_DISCONNECTING` is invisible to the loop; the loop keeps waiting for the ACK until either the ACK arrives or the timeout elapses.

**T1.5 — the exact control-flow path allowing stale success.**

1. `softether_do_dhcp` enters with `conn->state == STATE_CONNECTED` → guard at `:414` passes.
2. The client sends a REQUEST; the second `wait_dhcp_response` (`:460`) begins blocking in its time-only loop (`:339`).
3. Concurrently, on another thread, `softether_disconnect` runs and sets `conn->state = STATE_DISCONNECTING`. The DHCP thread's loop cannot see this (T1.3/T1.4).
4. The server's ACK is already in flight (or arrives during teardown); `softether_receive_raw` (`:396`) returns it, `parse_dhcp_response` (`:403`) yields `DHCP_ACK`.
5. `wait_dhcp_response` returns 0; the caller reaches `:463` and publishes `result->success = 1` **with no liveness re-check** — the result is already stale relative to the disconnect.
6. Publication is followed by `send_gratuitous_arp` (`:476`) and `return 0` (`:477`) — so the caller also observes a **success return code**.

**Conclusion (T1):** `dhcp_client.c` can publish a success after a disconnect has begun. There is a single publication site and a single state check, and the check is entry-only. The stale publication **cannot be prevented inside `dhcp_client.c`** as currently written — it is only bounded by the caller's ownership checks. This is the mechanism the B3s harness observes; the triage question is whether it is a *defect* or an *accepted* ordering, which T2/T3 answer.

---

## SECTION 6 — T2 — C3 vs C4 verdict rules, and the original intent

**T2.1 — the harness and case definitions.**

`test_b3s_dhcp_success_race(case_id, iterations)` in `native_test.c`. Case ids at **`:2265–2271`**:

| id | constant | description (as written in the source) |
|---|---|---|
| 1 | `B3S_CASE_C1` | successful DHCP before disconnect |
| 2 | `B3S_CASE_C2` | disconnect before response |
| 3 | `B3S_CASE_C3` | **simultaneous response/disconnect race (N iterations)** |
| 4 | `B3S_CASE_C4` | **success-publication window observation** |
| 5 | `B3S_CASE_C5` | genuine timeout control |
| 6 | `B3S_CASE_C6` | invalid/wrong-xid control |

Disconnect triggers at **`:2274–2280`**: `B3S_TRIG_ON_REQUEST 2` ("C3: at REQUEST observation (races the ACK)") and `B3S_TRIG_AFTER_SUCCESS 3` ("C4: after `result->success` was observed"). **Both trigger on the same underlying ACK-vs-disconnect race**; C3 fires the disconnect at the REQUEST sighting, C4 fires it after the success is observed.

**T2.2 — stale categorisation.** At **`:3226–3229`**:

```c
cat = !ctx.disc_started_at_return ? B3S_CAT_BEFORE
    : (!ctx.disc_done_at_return ? B3S_CAT_STALE_STARTED : B3S_CAT_STALE_DONE);
```

So a success whose return coincides with a disconnect in progress is `STALE_STARTED`; one whose return follows a completed teardown is `STALE_DONE`.

**T2.3 — `premise` accounting (the pivotal asymmetry).** At **`:3241–3261`**:

```c
case B3S_CAT_STALE_STARTED:
    tot.stale_started++;
    if (case_id == B3S_CASE_C3 || case_id == B3S_CASE_C4)
        tot.premise++;                       // :3254-3255
    break;
case B3S_CAT_STALE_DONE:
    tot.stale_done++;
    if (case_id == B3S_CASE_C3 || case_id == B3S_CASE_C4)
        tot.premise++;                       // :3259-3260
    break;
```

**A stale result itself increments `premise` for BOTH C3 and C4.** For C3 and C4 alike, `premise` is therefore *defined as the stale-success count* — i.e. the harness treats "a stale success occurred" as the **premise/goal** of both cases.

**T2.4 — the verdict rules (the contradiction).**

```c
if (case_id == B3S_CASE_C4) {                     // :3333
    ok = !harness_bad && tot.premise > 0;         // :3339  — stale REQUIRED
    ...
} else {                                          // :3357  (incl. C3)
    ok = !harness_bad && stale == 0 && tot.premise > 0;   // :3358  — stale FORBIDDEN
}
```

with `int stale = tot.stale_started + tot.stale_done;` at **`:3329`**.

The two rules are **mutually contradictory for the same phenomenon**:

| | C4 (`:3339`) | C3 (`:3358`) |
|---|---|---|
| `premise > 0` where `premise` **is** the stale count | required | required |
| explicit stale clause | **none** | **`stale == 0`** |
| net effect on a stale success | **PASS** (it is the observation target) | **FAIL** (it is a defect) |

C3's clause `premise > 0` can, in principle, be satisfied by a stale success — but that same event sets `stale > 0`, so the `stale == 0` conjunct then fails the run. The two conjuncts of the C3 rule cannot both be satisfied by the phenomenon C3 was written to exercise: **the only way C3 passes is if the race it targets does not happen at all.** C3 is therefore not a race-detection test — it is a "the race must not be observable" test, while C4, over the identical race, is a "the race must be observable" test.

The C4 comment at **`:3334–3338`** states this explicitly:

> "…`premise` (stale-success categories) remains the **only genuine observation of that ordering**, so a run with no stale successes reports `WINDOW_NOT_PROVEN` rather than claiming success."

So the harness's own author already concluded that a stale success *is* the correct, genuine observation of this ordering — for C4 — and recorded that reasoning in-source. C3's `stale == 0` clause predates (or contradicts) that conclusion and was never reconciled with it.

**T2.5 — the Kotlin test definitions.**

* `:553` `test27B3sCaseC3()` → `nativeTestB3sDhcpSuccessRace(3, 100)`; assertion at `:556–559` on `result.success`.
* `:564` `test28B3sCaseC4()` → `nativeTestB3sDhcpSuccessRace(4, 10)`; assertion at `:567–570` on `result.success`.

Both are pure pass-throughs of the native verdict — no additional Kotlin-side criterion. The asymmetry lives entirely in `native_test.c`.

**T2.6 — the introducing commit `b689cfe`.**

`-S` searches over `native_test.c` show `b689cfe` is the introducing commit for **both** `B3S_CASE_C3` **and** the C4 window clause (`C4_WINDOW_NOT_REACHED` / `premise`-based C4 verdict). C3 and C4 were written in the **same commit**, by the same author, over the same race. Commit `b689cfe`'s message does not mention B3s. There is therefore **no evidence of a deliberate design decision that C3 must forbid stale while C4 requires it** — the `stale == 0` conjunct for C3 and the `premise > 0` verdict for C4 simply encode opposite expectations of one phenomenon, and the later F.28AL-E work (documented in the C4 comment `:3334–3338`) revised the C4 side but left the C3 side untouched.

**T2.7 — the observed failure.** `test27B3sCaseC3` fails deterministically (3/3 runs in the previous phase):

```
B3s C3 failed: n=100 before=20 discResp=9 staleStart=55 staleDone=16
invReject=0 timeout=0 setup=0 resp=0 join=0 clean=0 unexp=0 hang=0 crash=0 retAT=16
```

Read against the rule: `harness_bad == 0` (all harness counters zero) ✓; `stale = 55 + 16 = 71 ≠ 0` ✗; `premise > 0` ✓. **The only failing conjunct is `stale == 0`.** The harness-level health of the run is perfect. This is precisely the signature of an over-strict expectation, not of a broken test harness.

**Conclusion (T2):** C3 and C4 assert opposite verdicts on the same race; C4's own in-source comment declares the stale success the *genuine* observation of that ordering; both cases were introduced together with no recorded intent that C3 must forbid it; and the failure is caused solely by the `stale == 0` conjunct while every harness-health counter is clean.

---

## SECTION 7 — T3 — how the Kotlin consumer uses the DHCP result (is a stale success safe?)

**File:** `SoftEtherClient/src/main/java/vn/unlimit/softether/controller/ConnectionController.kt`.

**T3.1 — initial-connect path.** `doDhcp` is called at **`:675`** (`val dhcpResult = client.doDhcp(nativeHandle)`), after the protocol-level connect succeeded (`:619`) and after the fds were protected (`:641–671`). The result is consumed at **`:686–705`**.

Immediately after the `doDhcp` call returns, and **before** the result is consumed, there is a cancellation re-check at **`:680–683`**:

```kotlin
if (isCancelled.get()) {
    Log.d(TAG, "Connection cancelled during DHCP, tearing down")
    throw CancellationException("Connection cancelled by user")
}
```

So on the initial-connect path a success that lands after a user cancel is **discarded** — the exception propagates before `dhcpResult` is used. This is the F.28J-era guard. **However**, it tests cancellation, not *teardown of the native session*; a disconnect requested by a path that does not set `isCancelled` would not be caught here.

**T3.2 — the reconnect path (the F.28AL-U slot), which is the decisive one.** `doDhcp` is called at **`:1327`**. The result is consumed at **`:1328–1359`**, and the guard is at **`:1343`**:

```kotlin
if (isCancelled.get() || nativeLifecycle.isDestroyed() ||
        currentState == ConnectionState.DISCONNECTED) {
    Log.d(TAG, "Reconnection cancelled during DHCP, not establishing the interface")
    // Stage 2F-F.28AL-U: single-owner claim, as every cleanup path uses.
    claimAndDestroyNativeHandle()
    return
}
```

Three independent conditions, any of which discards the stale success:

* `isCancelled.get()` — user/system cancellation,
* `nativeLifecycle.isDestroyed()` — the native lifecycle registry reports this attempt's handle already destroyed,
* `currentState == ConnectionState.DISCONNECTED` — the controller itself has already transitioned to disconnected.

The in-source comment at **`:1339–1342`** states the design intent verbatim:

> "This check is **defence in depth, not the ownership boundary**. Cancellation can still arrive immediately after it, so `installTun()` below remains the **authoritative guard** — it re-tests ownership inside the same critical section in which the descriptor is created."

**T3.3 — the authoritative guard, `installTun`.** At **`:466–469`**:

```kotlin
private fun installTun(config: ConnectionConfig): ParcelFileDescriptor {
    return service.establishVpnInterface(config, sessionId)
        ?: throw Exception("Failed to establish VPN interface")
}
```

The TUN descriptor is created by the **service**, keyed by `sessionId`, and the service is the component that holds the F.28AL-U ownership record (per `PROJECT_SPECIFICATION.md:312`, `NativeHandleSlot`: "coordinates ownership transfer, in-flight destroy, and terminal/lost state. Every application-managed cleanup path … claims through it, so a given handle is destroyed at most once and an unproven cleanup fails closed"). If the session was torn down, `establishVpnInterface` does not produce a descriptor and the flow throws rather than installing anything.

**T3.4 — does the controller discard a stale DHCP success?** **Yes**, at two independent layers:

1. **Before consumption** — `:680` (initial connect) / `:1343` (reconnect): the result is not used at all; the flow tears down via `claimAndDestroyNativeHandle()` (`:1346`).
2. **At the ownership boundary** — `:466` `installTun` → `service.establishVpnInterface(config, sessionId)` remains the authoritative single-owner claim, re-tested inside the critical section that creates the descriptor.

Crucially, the **worst case for a stale DHCP success is benign**: if the success is *not* caught by the defence-in-depth check, the flow proceeds to `installTun` — where the ownership test rejects it and throws. A stale success can at most cause a *redundant attempt to install an interface that is then refused*; it cannot install an interface for a torn-down session. The F.28AL-U slot therefore **handles a stale DHCP success safely**.

**T3.5 — the exact control-flow path.** Stale success observed:

```
doDhcp (:1327) returns non-null after a concurrent disconnect began
  → :1343  isCancelled || isDestroyed || DISCONNECTED ?
       true  → claimAndDestroyNativeHandle() ; return            (discarded)
       false → :1350 dhcpConfig = config.copy(...)
               :1354 vpnInterface = installTun(dhcpConfig)
                     → :467 service.establishVpnInterface(config, sessionId)
                          session gone → null → :468 throw "Failed to establish VPN interface"
                          session live → descriptor installed        (correct)
```

On the initial-connect path the same shape applies with the `:680` guard and the `:700` `installTun` call.

**Conclusion (T3):** a stale DHCP success is **discarded** on the reconnect path by the F.28AL-U-aware guard at `:1343`, and any residue is stopped by the authoritative single-owner boundary at `:466–468`. The native stale publication is therefore an *observed ordering*, not a system-integrity failure: the consumer is designed to survive it. This removes the "it must be a defect because the app would break" argument for classification (A).

---

## SECTION 8 — T4 — prior decisions in the specification and review reports

**Searches performed** (case-insensitive, across all `*.md`): "stale DHCP", "dhcp success after disconnect", "stale dhcp", "DHCP after disconnect", "stale-success", "stale success", plus `B3s`/`B3S`/`dhcp_client`/`stale` in `PROJECT_SPECIFICATION.md` and `DEEP_REVIEW_VERIFY_REPORT.md`.

**Findings:**

* **`DEEP_REVIEW_VERIFY_REPORT.md:87`** — finding **N-17** covers the DHCP path:

  > `| N-17 | nativeDoDhcp / ARP path assumptions | STILL_VALID (low) | softether_jni.c:486 nativeDoDhcp returns int[7] and performs ARP resolve; the DHCP parser issues are captured under N-07. No additional defect beyond N-07. | P3_LOW |`

  N-17 explicitly scopes the DHCP concerns to the **JNI return shape and parser issues (N-07)** and states there is "no additional defect beyond N-07". **The stale-publication ordering is NOT classified as a defect** in the deep review.

* **`PROJECT_SPECIFICATION.md:219`** — F.28AL-U is documented as the single-owner handle arbitration ("all inline reconnect teardowns routed through one atomic `claimHandle()` claim + `destroyClaimedNativeHandle()`"), i.e. the mechanism that makes a late/stale result safe by construction.

* **`PROJECT_SPECIFICATION.md:298`** — the open-questions list names "state publication after a rejected/stale lease" as an *open question*, i.e. a topic flagged for observation, not a known defect.

* **`PROJECT_SPECIFICATION.md`** B3s/F.28AL-E search returned the C4 window-clause documentation (F.28AL-E) but **no entry** declaring C3's `stale == 0` clause as an intentional must-not-happen requirement. There is no recorded decision that C3 forbids stale because the app would otherwise break.

* **Only one report-level mention** of the phrase "stale DHCP successes" exists outside the specification, and it is in **this project's own most recent phase report** — `FIX_TEST40_BUDGET_AND_RE_RUN_REPORT.md:192` — i.e. the failure being triaged here, not a prior design decision.

**Conclusion (T4):** there is **no prior decision** anywhere in `PROJECT_SPECIFICATION.md` or `DEEP_REVIEW_VERIFY_REPORT.md` that (a) declares the DHCP stale publication a defect, or (b) requires C3 to forbid it. The deep review classifies the DHCP path as P3_LOW with no defect beyond N-07, and documents F.28AL-U as the safety mechanism for exactly this class of late result. The evidence is consistent with C3's `stale == 0` clause being an **unreconciled, over-strict expectation** rather than an intentional design constraint.

---

## SECTION 9 — T5 — classification

### Verdict: **(B) OUTDATED TEST EXPECTATION**

C3 should be updated to match C4's "observe stale" semantics because the F.28AL-U slot handles a stale DHCP success safely.

**Supporting anchors (consolidated):**

| # | Evidence | Anchor |
|---|---|---|
| 1 | C3 forbids stale (`stale == 0`) while C4 **requires** it (`premise > 0`, where `premise` *is* the stale count) — over the same race | `native_test.c:3358` (C3) vs `:3339` + `:3254-3255`/`:3259-3260` (C4) |
| 2 | The C4 in-source comment declares the stale success "the **only genuine observation** of that ordering" | `native_test.c:3334-3338` |
| 3 | The observed failure is caused **solely** by the `stale == 0` conjunct; every harness-health counter is zero | failure message (`harness_bad == 0`, `stale = 71 ≠ 0`) |
| 4 | The stale publication is structurally **unavoidable** in `dhcp_client.c` as written (entry-only state check; time-only wait loop) — so `stale == 0` demands the impossible-by-construction | `dhcp_client.c:414` (entry guard), `:463` (publication), `:339` (`while (elapsed < timeout_ms)`) |
| 5 | The consumer **discards** the stale success (three-condition guard) and the authoritative single-owner boundary refuses the install | `ConnectionController.kt:1343` (guard), `:466-468` (`installTun` → `establishVpnInterface`) |
| 6 | The design intent is documented as "defence in depth … `installTun()` remains the authoritative guard" | `ConnectionController.kt:1339-1342` |
| 7 | The deep review classifies the DHCP path P3_LOW with "no additional defect beyond N-07" | `DEEP_REVIEW_VERIFY_REPORT.md:87` |
| 8 | F.28AL-U exists precisely to make a late/stale result safe ("fails closed") | `PROJECT_SPECIFICATION.md:312`, `:219` |
| 9 | C3 and C4 were introduced in the same commit (`b689cfe`) with no recorded must-not-happen intent for C3 | `-S` search on `native_test.c` |

**Why not (A) PRE-EXISTING DEFECT.** A defect classification would require that the stale publication harms the system. T3 shows it does not: the consumer discards it before use (`:1343`) and the ownership boundary refuses any residual install (`:466-468`). T4 shows no prior decision treats it as a defect (N-17: "no additional defect beyond N-07"). A classification would also leave C4 unexplained — C4 deliberately *waits for and verifies* the very event (A) calls a defect.

**Why not (C) DESIGN DECISION NEEDED.** (C) would be correct if the project wanted *both* "the race must exist and be observable" (C4) *and* "the race must not exist" (C3), i.e. if it intended to harden `dhcp_client.c` with a liveness re-check. That is a *legitimate future hardening* (see Section 10), but it is not what the current evidence supports as the *classification of this failure*: the current code, comments, and review records describe the stale success as expected and safely handled. Hardening would change the design; the test failure is better explained by C3 never having been reconciled with C4. If the user later decides the hardening *is* wanted as defence-in-depth, the classification would become (C) and the fix would be in `dhcp_client.c`, not (only) in the test.

**Why not (D) UNKNOWN.** The mechanism (T1), the test asymmetry (T2), the consumer behaviour (T3), and the absence of any prior contrary decision (T4) are all established from source with anchors. The evidence is decisive.

**Honest caveat.** (A) and (C) both remain *arguable* on one ground: a reader could say "even if the consumer survives it, a DHCP result published after its session began tearing down is semantically wrong, and hardening `dhcp_client.c` would be the correct fix." That is a **hardening preference**, not a demonstration that the current behaviour is a defect. It is recorded here so the decision is explicit rather than implicit. On the evidence — C4's own comment, the harness-health-clean failure signature, the F.28AL-U discarding guards, and N-17's "no additional defect" — the balance is clearly **(B)**.

---

## SECTION 10 — T6 — blast radius

**T6 applies only if the classification is (A). The classification is (B), so no production-source blast radius exists.** No file other than the test may be touched under this verdict.

For completeness, and because `PROCEED TO ADJUST-C3-EXPECTATION` is the recommendation, the **test-only** change is minimal and bounded to `native_test.c`:

* **File:** `SoftEtherClient/src/main/cpp/test/native_test.c` (test-only; not compiled into any production artifact — `CMakeLists.txt` compiles it solely under `if(BUILD_TESTING)` into `libsoftether_test.so`).
* **Site:** the `else` verdict branch at **`:3357–3359`**. The `stale == 0` conjunct for C3 is the single clause to reconcile.
* **Approximate delta:** 1–6 lines. Two shapes are possible and equally minimal:
  * *Minimal:* separate C3 from the generic branch and give it C4's shape (`ok = !harness_bad && tot.premise > 0;`), keeping the stale counts visible in the message (they already are, at `:3371-3377`).
  * *Preferred:* make the C3 rule require `premise > 0` **and** treat `STALE_DONE` (a success landing *after* teardown completed) as the genuinely wrong outcome while accepting `STALE_STARTED` (the in-window race C3 targets). This preserves C3's discriminating power (it can still fail if a success emerges *after* the teardown completed) without forbidding the race it exists to exercise. This is a ~4-line change (`:3358`).
* **No other file changes.** `dhcp_client.c`, `ConnectionController.kt`, the header, the JNI bridge, and the Kotlin test declarations all remain untouched.

**If the user instead selects (C) DESIGN DECISION NEEDED / hardening**, the blast radius would be production code:

* `SoftEtherClient/src/main/cpp/softether-core/src/net/dhcp_client.c` — add a liveness re-check before the publication at `:463` (read `conn->state` and/or a disconnect generation, and/or thread a `STATE_DISCONNECTING` test into the `wait_dhcp_response` loop at `:339`). Estimated +5 to +15 lines. **Not authorized and not recommended by this triage.**
* No Kotlin change expected: the consumer already discards stale results (T3), so `ConnectionController.kt` needs no adjustment. This is itself a point in favour of (B): the consumer was designed for the behaviour C3 now forbids.

---

## SECTION 11 — Compliance with the phase's DO-NOT list

| Rule | Status | Evidence |
|---|---|---|
| Do NOT modify any source file (only commit = test40 fix) | **HELD** | The only edit was the already-authorized test40 budget loop; committed once as `38d9d33`. Part 2 was read-only (`Read`/`Grep`/`Glob` only). |
| Do NOT modify `native_test.c` beyond the committed change | **HELD** | No further edit to `native_test.c` after `38d9d33`. |
| Do NOT push | **HELD** | `origin/main` still `5c13603` (Section 4). |
| Do NOT tag | **HELD** | No tag command issued; no tag exists for this phase. |
| Do NOT touch the device | **HELD** | Device used for P1 identity only; no install/`pm`/`logcat`/test afterwards. |
| Do NOT run any tests | **HELD** | No test was run in this phase; the C3 failure figures quoted are from the *previous* phase's on-device run. |
| Only one commit | **HELD** | Exactly one commit: `38d9d33`. |
| No `git add -A` / `git add .` | **HELD** | `git add` named the single path explicitly. |
| Report in English only | **HELD** | This document. |

---

## SECTION 12 — Summary and recommendation

**Part 1 (commit).** The test40 budget fix was committed as a single commit, `38d9d33`, 1 file changed (+6/−2), limited to the N-16-A post-release wait loop (`500 → 9000`). `origin/main` remains `5c13603` — nothing was pushed.

**Part 2 (triage).**

* **T1:** `dhcp_client.c` has one success-publication site (`:463`) and one state check, which is entry-only (`:414`); `wait_dhcp_response` loops purely on `elapsed < timeout_ms` (`:339`) and never reads `conn->state`. The stale path is structurally available and cannot be prevented inside that file as written.
* **T2:** C3 (`:3358`, `stale == 0`) and C4 (`:3339`, `premise > 0` where `premise` *is* the stale count) assert **opposite** verdicts on the same race, and C4's own comment (`:3334-3338`) calls the stale success the "only genuine observation" of it. Both were introduced by `b689cfe` with no recorded intent that C3 must forbid it. The observed C3 failure is caused **solely** by the `stale == 0` conjunct; all harness-health counters are zero.
* **T3:** `ConnectionController.kt` **discards** a stale DHCP success at `:1343` (three independent conditions) on the reconnect path and at `:680` on the initial path, and the authoritative single-owner boundary `installTun` → `service.establishVpnInterface` (`:466-468`) refuses any residual install. The F.28AL-U slot handles a stale success safely — the in-source comment at `:1339-1342` says exactly this.
* **T4:** no prior decision in `PROJECT_SPECIFICATION.md` or `DEEP_REVIEW_VERIFY_REPORT.md` treats the stale publication as a defect or requires C3 to forbid it; N-17 rates the DHCP path P3_LOW with "no additional defect beyond N-07".
* **T5:** **(B) OUTDATED TEST EXPECTATION.**
* **T6:** no production blast radius; the test-only change is 1–6 lines, bounded to the C3 verdict branch at `native_test.c:3357-3359`.

**VERDICT:** `TEST40 COMMITTED — C3 TRIAGED AS (B) OUTDATED TEST EXPECTATION`

**RECOMMENDATION:** `PROCEED TO ADJUST-C3-EXPECTATION`

The recommended edit is to reconcile C3's verdict with C4's — prefer the shape that still accepts `STALE_STARTED` (the in-window race C3 targets) but treats `STALE_DONE` (a success landing after teardown completed) as the genuinely wrong outcome, so C3 retains discriminating power. No production source (`dhcp_client.c`, `ConnectionController.kt`) should be modified under this verdict. If the user prefers defence-in-depth hardening of `dhcp_client.c` instead, the classification becomes (C) and the work must be authorized explicitly.
