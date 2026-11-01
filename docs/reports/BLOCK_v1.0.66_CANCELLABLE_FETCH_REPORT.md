# BLOCK v1.0.66 — SAFE CHANGE CHECKPOINT AND CANCELLABLE NETWORK FETCH

> **Status: PART A COMPLETE — STOPPED FOR OPERATOR APPROVAL.**
> Part B's cause is identified from source (not from a final log line), and a narrow fix design is
> proposed, but **no source was changed, nothing was committed, tagged, pushed or released.**
> Sections 7–23 require the Part B implementation and Part C verification, which are gated on your
> approval of the inventory and commit plan below.

- Repository: `https://github.com/morteza-taheri/SoftEthergai.git`, branch `main`
- Expected device: `5585fecc` — **present** (`model:2201117TG product:spes_global`)
- Package: `vn.unlimit.vpngatepro`
- Evidence root: `test-evidence/v1066/` (`00_preservation_snapshot.txt`, `01_source_diffs.txt`)

---

## 1. Executive Verdict

| Part | Verdict | Basis |
|---|---|---|
| A — Working-tree inventory & commit plan | **PASS** (delivered; awaiting approval) | full snapshot + diffs saved under `test-evidence/v1066/` |
| A — Clean separation into 4 historical commits | **PARTIAL / NOT SAFELY POSSIBLE** | two files mix phases; shared `mergeFallback`; v1.0.65 tests reference v1.0.63 code |
| B — Cause of the 45 s → ~173 s overrun | **IDENTIFIED (SOURCE CODE)**; exact blocked stage = **INFERENCE, pending log analysis** | `OkHttpFetcher.execute()` is a blocking `Call.execute()` with no cancellation bridge and no `callTimeout` |
| B — Narrow fix | **DESIGNED, NOT IMPLEMENTED** | gated on approval |
| C — Automated / device verification | **NOT STARTED** | gated on approval |

**One-line verdict.** The working tree is preserved and fully inventoried. The uncommitted work is
**not** four cleanly separable commits — `ServerListUpdates.kt` fuses v1.0.63 with G-F29-1 through a
single shared `mergeFallback`, and `VpnServerRepository.kt` fuses v1.0.64 with v1.0.65. A conservative
**two-commit** plan is proposed. Separately, the deadline overrun is explained by a blocking OkHttp
call that coroutine cancellation cannot interrupt, so `withTimeoutOrNull(45_000)` only "fires" once the
socket/DNS work unblocks.

---

## 2. Initial Repository State (verified independently)

| Item | Value |
|---|---|
| HEAD | `7bebf8d91bb4000c61dd0db2127bb1ee6f40a4c9` |
| Branch | `main` |
| `origin/main` | `7bebf8d…` (left-right `0 0` — in sync) |
| Tags | 5 (none created by this block) |
| Device | `5585fecc` state `device`, `model:2201117TG`, `product:spes_global`, `transport_id:6` |

`git status --short` (verbatim, also in `test-evidence/v1066/00_preservation_snapshot.txt`):

```
 M F29_PHASE2_IMPLEMENTATION_REPORT.md
 M PROJECT_SPECIFICATION.md
 M app/src/main/java/vn/unlimit/vpngate/parser/EmergencyCsvParser.kt
 M app/src/main/java/vn/unlimit/vpngate/repository/ServerListUpdates.kt
 M app/src/main/java/vn/unlimit/vpngate/repository/VpnServerRepository.kt
 M app/src/test/java/vn/unlimit/vpngate/collector/EmergencyCsvParserTest.kt
 M app/src/test/java/vn/unlimit/vpngate/repository/ServerListRetentionTest.kt
 M app/src/test/java/vn/unlimit/vpngate/repository/VpnServerRepositoryLadderTest.kt
 M app/src/test/java/vn/unlimit/vpngate/repository/VpnServerRepositoryR4bTest.kt
 M app/src/test/java/vn/unlimit/vpngate/repository/VpnServerRepositoryRoutingDynamicTest.kt
 M app/src/test/java/vn/unlimit/vpngate/repository/VpnServerRepositoryRoutingTest.kt
?? .dv6/  ?? .dv7/  ?? .vscode/  ?? .workbuddy-ai/
?? BLOCK_v1.0.64_DYNAMIC_VPN_AWARE_SERVER_LIST_UPDATER_REPORT.md
?? BLOCK_v1.0.65_DYNAMIC_VPN_FIRST_GUARDS_REPORT.md
?? COMPREHENSIVE_AUDIT_v1.0.63_REPORT.md
?? D1_CANDIDATES.md
?? DEVICE_VERIFY_AUTOCONNECT_BUGS_v1.0.41_REPORT.md
?? DEVICE_VERIFY_CLUSTER_v1.0.43_REPORT.md
?? DEVICE_VERIFY_SERVER_LIST_UPDATE_v1.0.44_REPORT.md
?? DEVICE_VERIFY_TUNNEL_TRANSPORT_v1.0.47_REPORT.md
?? DEVICE_VERIFY_v1.0.46_REPORT.md
?? DEVICE_VERIFY_v1.0.48_v1.0.49_REPORT.md
?? DEVICE_VERIFY_v1.0.53_REPORT.md
?? TRIAGE_AUTOCONNECT_DEVICE_BUGS_REPORT.md
?? app/src/test/java/vn/unlimit/vpngate/repository/EmergencyMergeFallbackTest.kt
?? app/src/test/java/vn/unlimit/vpngate/repository/EmergencyMergePolicyTest.kt
?? app/src/test/java/vn/unlimit/vpngate/repository/VpnServerRepositoryDynamicVpnPathTest.kt
?? app/src/test/java/vn/unlimit/vpngate/repository/VpnServerRepositoryVpnFirstGuardsTest.kt
?? build_test_baseline.log
?? emergency/
?? lint_v1064.log
?? scripts/
?? test-evidence/
```

`git diff --stat`: **11 files changed, 825 insertions(+), 115 deletions(-)**. Notably `vpngate_collector.py`
is **no longer modified** at this HEAD (it is absent from `git status`), so it is not part of this
block's inventory.

---

## 3. Working-Tree Inventory

**Legend:** T=tracked, U=untracked; group = G-F29-1 / v1.0.63 / v1.0.64 / v1.0.65 / none; Mixed =
the file contains more than one phase.

### 3.1 Tracked modifications

| # | Path | T | Key hunks / symbols | Group | Evidence | Intended treatment | Mixed |
|---|---|---|---|---|---|---|---|
| 1 | `app/…/parser/EmergencyCsvParser.kt` | T | sets `protocols.softether.udp.supported=true` unconditionally when `udpDeclared==true`; `applyPort` now only writes the port | v1.0.63 (A) | diff `@424` | commit with v1.0.63 | No |
| 2 | `app/…/repository/ServerListUpdates.kt` | T | `origin.value=false` reset at fetch start; `origin.value=true` before `emergencyDataset()`; `fetchedFromCache()` branch now calls `mergeFallback(...)`; **new `mergeFallback()`** (openvpn-config + retention + quality preservation) | **v1.0.63 (B) + G-F29-1** | diff `@88,@128,@322,+556` | commit 1 (inseparable) | **YES** |
| 3 | `app/…/repository/VpnServerRepository.kt` | T | `mainTimeoutMs` injectable (default `MAIN_TIMEOUT_MS`); `routeLadder` → deterministic `TUNNEL→PHYSICAL→EMERGENCY`; `withTimeoutOrNull(mainTimeoutMs)`; **`TunnelTransportProbe`**; `probedBoundFetcher`; `apiHtmlPrimary` short-circuit | **v1.0.64 + v1.0.65** | diff `@87,@177,@268,@281,@412,@467,@486,@736` | commit 2 (inseparable) | **YES** |
| 4 | `app/…/collector/EmergencyCsvParserTest.kt` | T (+159) | UDP-support-without-port acceptance; port-only-when-published | v1.0.63 | diff stat | commit 1 | No |
| 5 | `app/…/repository/ServerListRetentionTest.kt` | T (+110) | retention (`firstSeenAt`/`lastSeenAt`/`healthScore`) preserved across fallback | G-F29-1 | diff stat | commit 1 | No |
| 6 | `app/…/repository/VpnServerRepositoryLadderTest.kt` | T (38) | VPN-first ladder order assertions | v1.0.64 | diff stat | commit 2 | No |
| 7 | `app/…/repository/VpnServerRepositoryR4bTest.kt` | T (60) | routing / binding expectations updated for VPN-first | v1.0.64/65 | diff stat | commit 2 | No |
| 8 | `app/…/repository/VpnServerRepositoryRoutingDynamicTest.kt` | T (25) | dynamic routing | v1.0.64/65 | diff stat | commit 2 | No |
| 9 | `app/…/repository/VpnServerRepositoryRoutingTest.kt` | T (80) | routing matrix | v1.0.64/65 | diff stat | commit 2 | No |
| 10 | `F29_PHASE2_IMPLEMENTATION_REPORT.md` | T (+155) | G-F29-1 report body | G-F29-1 | diff stat | commit 1 (doc) | No |
| 11 | `PROJECT_SPECIFICATION.md` | T (+20) | spec entries spanning v1.0.63/64/65 | **spans all** | diff stat | commit 2 tail **or** separate `[skip ci]` docs commit | **YES** |

### 3.2 Untracked — source/tests belonging to the work

| # | Path | U | Key content | Group | Intended treatment |
|---|---|---|---|---|---|
| 12 | `…/repository/EmergencyMergeFallbackTest.kt` | U | `mergeFallback` profile/retention/new-host behaviour | v1.0.63/G-F29-1 | commit 1 |
| 13 | `…/repository/EmergencyMergePolicyTest.kt` | U | `mergeFallback`/`mergeAll` field policy (udp/sstp/l2tp/score/ping) | v1.0.63 | commit 1 |
| 14 | `…/repository/VpnServerRepositoryDynamicVpnPathTest.kt` | U | `routeLadder`, no `VpnService.protect`, `withTimeoutOrNull(mainTimeoutMs)` | v1.0.65 | commit 2 |
| 15 | `…/repository/VpnServerRepositoryVpnFirstGuardsTest.kt` | U | §6 matrix A–J; `mainTimeoutMs=400L` deadline test; **references `mergeFallback`** | v1.0.65 (depends on v1.0.63) | commit 2 |

### 3.3 Untracked — reports belonging to the work

| # | Path | U | Group | Intended treatment |
|---|---|---|---|---|
| 16 | `COMPREHENSIVE_AUDIT_v1.0.63_REPORT.md` | U | v1.0.63 | commit 1 (doc) |
| 17 | `BLOCK_v1.0.64_DYNAMIC_VPN_AWARE_SERVER_LIST_UPDATER_REPORT.md` | U | v1.0.64 | commit 2 (doc) |
| 18 | `BLOCK_v1.0.65_DYNAMIC_VPN_FIRST_GUARDS_REPORT.md` | U | v1.0.65 | commit 2 (doc) |

### 3.4 Untracked — must **not** be staged (env / evidence / logs / historical)

| # | Path(s) | Reason to leave untracked |
|---|---|---|
| 19 | `.dv6/`, `.dv7/`, `.vscode/`, `.workbuddy-ai/` | tooling/environment; not source |
| 20 | `D1_CANDIDATES.md` | scratch/notes; tracking status unestablished |
| 21 | `DEVICE_VERIFY_*` (v1.0.41/43/44/46/47/48_49/53), `TRIAGE_AUTOCONNECT_DEVICE_BUGS_REPORT.md` | older-block reports; not this phase's deliverable |
| 22 | `build_test_baseline.log`, `lint_v1064.log` | build/lint logs; not source |
| 23 | `emergency/`, `scripts/`, `test-evidence/` | evidence/tooling; the brief explicitly says not to stage `test-evidence/` |

---

## 4. Proposed Commit Dependency Plan

### 4.1 Honest dependency finding (the brief's warning is confirmed)

The four historical groups **do not** map to four independent commits:

1. **`ServerListUpdates.kt` fuses v1.0.63 and G-F29-1.** A single new function `mergeFallback()` serves
   **both** the v1.0.63 emergency rung (`origin.value = true`) **and** the G-F29-1 cache branch
   (`if (fetchedFromCache()) mergeFallback(...)`). Splitting them would require duplicating or
   conditionally re-implementing `mergeFallback` in an intermediate commit — a misleading history.
2. **`VpnServerRepository.kt` fuses v1.0.64 and v1.0.65.** `TunnelTransportProbe` (v1.0.65) reads
   `primary`, which is produced by the v1.0.64 `routeLadder`. In addition,
   `VpnServerRepositoryVpnFirstGuardsTest` (v1.0.65) explicitly references `mergeFallback` (v1.0.63),
   so **v1.0.65's tests require v1.0.63 to be present**.

Therefore I do **not** manufacture a four-commit sequence. I propose **two commits** (plus a
docs-placement choice), ordered so each depends only on its predecessor.

### 4.2 Proposed sequence

**Commit 1 — `v1.0.63 + G-F29-1: emergency CSV merge, UDP-support flag, fallback retention`**
- Staged exactly: `EmergencyCsvParser.kt`, `ServerListUpdates.kt`, `EmergencyCsvParserTest.kt`,
  `ServerListRetentionTest.kt`, `EmergencyMergeFallbackTest.kt`, `EmergencyMergePolicyTest.kt`,
  `COMPREHENSIVE_AUDIT_v1.0.63_REPORT.md`, `F29_PHASE2_IMPLEMENTATION_REPORT.md`
- Dependencies: none (self-contained).
- Build/test method: from a **clean extraction of `HEAD` with only this staged set applied**
  (`git archive HEAD | tar -x` into a scratch dir, apply the staged diff), run
  `CI=true ./gradlew :app:testProDebugUnitTest --tests "*Emergency*" --tests "*ServerListRetention*"`
  then the full `:app:testProDebugUnitTest`. A commit is valid only if the **scratch tree** compiles
  and is green; the working tree is not used as the build source.
- Inseparable within this commit: `ServerListUpdates.kt` (v1.0.63 + G-F29-1).

**Commit 2 — `v1.0.64 + v1.0.65: deterministic VPN-first ladder, per-refresh tunnel probe, injectable deadline`**
- Staged exactly: `VpnServerRepository.kt`, `VpnServerRepositoryLadderTest.kt`,
  `VpnServerRepositoryR4bTest.kt`, `VpnServerRepositoryRoutingDynamicTest.kt`,
  `VpnServerRepositoryRoutingTest.kt`, `VpnServerRepositoryDynamicVpnPathTest.kt`,
  `VpnServerRepositoryVpnFirstGuardsTest.kt`, `BLOCK_v1.0.64_…_REPORT.md`,
  `BLOCK_v1.0.65_…_REPORT.md`
- Dependencies: **depends on Commit 1** (v1.0.65 tests reference `mergeFallback`).
- Build/test method: clean extraction of Commit 1's result, apply this staged set, run full
  `CI=true ./gradlew :app:testProDebugUnitTest :SoftEtherClient:testDebugUnitTest`.

**Spec placement — `PROJECT_SPECIFICATION.md`** (spans all three phases). Two acceptable options:
- (a) fold into Commit 2's tail (it then stops being a pure docs commit — no `[skip ci]`), or
- (b) a **third docs-only** commit `[skip ci] docs(v1.0.63–65): spec …` per `AGENTS.md` (doc-only
  commits in the parent repo must carry `[skip ci]`).

### 4.3 What cannot be separated, and what stays untracked

- **Cannot separate:** G-F29-1 from v1.0.63 (shared `mergeFallback`); v1.0.64 from v1.0.65 (shared
  refresh path + test coupling).
- **Stays untracked:** rows 19–23 (env dirs, scratch notes, older reports, logs, `emergency/`,
  `scripts/`, `test-evidence/`) — none are this phase's source deliverable; the brief forbids staging
  `test-evidence/`.
- **Verification is not yet done:** per the brief, a proposed sequence is *not* evidence it is valid.
  Each commit will be built and tested from a clean `git archive HEAD` scratch tree **before** any
  commit is created, and the staged + unstaged diffs will be inspected. This verification is gated on
  your approval.

---

## 5. Exact Cause of the Deadline Overrun (source-grounded; blocked stage pending log)

**The mechanism (SOURCE CODE EVIDENCE).**

- `VpnServerRepository.refresh()` wraps the whole run as
  `refreshMutex.withLock { withTimeoutOrNull(mainTimeoutMs /* 45_000 */) { refreshLocked() } }`
  (`VpnServerRepository.kt:412–419`).
- Every source is fetched through `HttpFetcher.get()` → `OkHttpFetcher.get()` =
  `withContext(Dispatchers.IO) { execute(url, redirectPolicy) }` (`HttpFetcher.kt:197–202`).
- `execute()` performs a **blocking** `client.newCall(request).execute().use { … }`
  (`HttpFetcher.kt:231`). There is **no** `suspendCancellableCoroutine`, **no**
  `invokeOnCancellation { call.cancel() }`, and **no `callTimeout`** on the client
  (`collectorClientBuilder()` sets only `connectTimeout(10s)` and `readTimeout(15s)`,
  `HttpFetcher.kt:106–113`).
- DNS is a **blocking** `Dns.SYSTEM.lookup(hostname)` inside `ResilientDns.lookup`
  (`HttpFetcher.kt:69–93`), also not cancellation-aware.

**Consequence.** A coroutine timeout cannot preempt a blocking `execute()`. `withTimeoutOrNull(45_000)`
cancels the coroutine, but the IO thread stays inside the socket read/connect until OkHttp's own
connect/read timeouts elapse (or the peer responds). Because the blocking call is not interrupted, the
`withTimeoutOrNull` body cannot complete cancellation until that call returns; the `refreshMutex` is
held for the entire duration. The observed "45 s deadline fired ~128 s late" is therefore the deadline
taking effect only **after** the blocked network work unblocked — the timeout bounded the *coroutine's
waiting*, not the *underlying operation*, exactly as the brief's success criterion warns.

**Blocked stage.** This is narrower than a pure guess. The recorded v1.0.65 device run (Test 4,
`test-evidence/v1065/device_test4_disconnect_during_refresh.logcat`) observed that the **HTML physical
fallback returned 100 rows at ~+17 s while the API physical fallback hung past the 45 s deadline**, and
the run's own backlog note (B-1) attributes the late deadline to "OkHttp blocking calls are not
cancellable." [PRIOR REPORT] That isolates the blocked *operation* to the **API source on the physical
path**, not the tunnel (the tunnel primaries failed ~37 ms after the disconnect) and not the HTML rung
(which completed). What the prior evidence does **not** isolate is the exact syscall inside that call —
**connect vs TLS handshake vs response-headers vs body-read vs DNS lookup** — and I will not assert
which one from the final log line alone. Pinning the syscall requires timestamped parsing of the
`GET`/`HTTP`/`Request failed`/`Redirect` lines around the disconnect, which is part of Part B and is
gated on approval. Classification: **mechanism = SOURCE CODE EVIDENCE; blocked operation (API/physical)
= PRIOR REPORT; exact syscall stage = INFERENCE (pending).**

---

## 6. Cancellation and Mutex Analysis (partial)

- **Cancellation reaches the coroutine, not the call.** `withTimeoutOrNull` + `withContext(IO)` +
  blocking `execute()` means the `Call` is never cancelled; there is no `Call.cancel()` bridge.
- **Mutex is held across the overrun.** `refreshMutex.withLock { … }` releases only after
  `refreshLocked()` returns; with a non-interruptible blocking call, that is after the socket work
  ends. A second refresh therefore queues behind the stale operation (the brief's requirement #7/#8).
- **Late response risk.** Because the call is not cancelled, a late body could still be produced after
  the timeout returned; whether it is persisted depends on whether the write happens inside the
  cancelled scope (it does, via `mergeAll`/`mergeFallback` after `awaitAll`) — needs test confirmation.
- Full cancellation-semantics table (brief items 1–9) is completed in Part B after implementation.

---

## Sections 7–23 — PENDING (gated on approval of this plan)

7 Timeout Budget · 8 Implementation Changes · 9 Behavioral Test Matrix · 10 Focused Test Results ·
11 Full Application Test Results · 12 SoftEther Client Test Results · 13 Ten-Run Stability Results ·
14 Lint Results · 15 VPN-Disconnected Device Test · 16 VPN-Connected Device Test ·
17 Mid-Refresh Disconnect Test · 18 Follow-up Refresh Test · 19 Dataset Integrity and Persistence ·
20 Remaining Risks · 21 Evidence Index · 22 Final Recommendation · 23 Change-Control Confirmation

**Proposed Part B narrow fix (design only — NOT implemented):**
1. Bridge coroutine cancellation to the OkHttp `Call` (`suspendCancellableCoroutine` + `call.enqueue`
   with `invokeOnCancellation { call.cancel() }`, closing the body in `finally`) — validating body
   close and resource lifecycle, not mechanically.
2. Add a finite `callTimeout` to `collectorClientBuilder()` (≤ the refresh budget) so even a
   non-cancellable stage is bounded; preserve physical-network timeout behaviour.
3. Bound or explicitly document DNS: `ResilientDns.lookup` is blocking; test the DNS path separately
   (a finite call timeout does not prove DNS is cancellable).
4. Consider a shorter **tunnel-only** budget (e.g. 4 s connect / 8 s call) **only if** source+tests
   justify it (not adopted blindly).
5. Prove with deterministic tests (fake/instrumented call + resolver) that cancellation bounds the
   operation, the mutex releases, a late response cannot overwrite accepted state, and last-known-good
   is preserved when all routes fail.

**No commit, tag, push, or release has been made. Awaiting operator approval of the inventory and the
two-commit plan before doing anything further.**
