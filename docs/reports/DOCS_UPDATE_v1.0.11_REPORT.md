# DOCS UPDATE v1.0.11 — Report

- **Phase:** DOCS-AND-AXES-AUDIT-v1.0.11 (Parts A–G: spec revision to v1.0.11, three read-only
  axis audits, two new report files, two commits, push)
- **Repository / branch:** `D:\PMC\Documents\Android\SoftEthergai` / `main`
- **Pushed chain after this phase:** `5773c60..6119a2a` (already pushed in earlier phases of the
  chain) — this phase adds nothing to it until its own two commits are created
- **Evidence taxonomy:** `SOURCE CODE EVIDENCE` / `PHYSICAL DEVICE EVIDENCE` / `PRIOR REPORT` /
  `INFERENCE` / `UNKNOWN`, as used throughout `PROJECT_SPECIFICATION.md` §20.

**In one paragraph.** The specification was revised from 1.0.10 to 1.0.11 to record the native
hardening bundle and its device verification, and three read-only axis audits (tunnel state / UI
sync; server list network binding; SoftEther UDP / TUN `EIO`) were performed alongside it, together
with the classification of the residual `T5-SLOT-TEARDOWN` finding. The revision is **docs-only in
itself**: every source/test/build/device action it records was performed in an earlier phase of the
same chain and is cited to that phase's report, not re-performed here.

---

## 1. Scope

| Part | Content | Status |
|---|---|---|
| A | `PROJECT_SPECIFICATION.md` → v1.0.11 (six surgical edits, A2.1–A2.6) | **DONE** |
| B | `T5-SLOT-TEARDOWN` residual classification | **DONE** (§3.1) |
| C | Axis A — Tunnel State / UI Sync audit | **DONE** (§3.2) |
| D | Axis B — Server List Network Binding audit + three-state gap enumeration | **DONE** (§3.3) |
| E | Axis C — SoftEther UDP / TUN `EIO` audit + counters list | **DONE** (§3.4) |
| F | `AUDIT_AXES_A_B_C_v1.0.11.md` (§1–§9) | **DONE** |
| G1–G7 | report → diff-scope gate → two commits → verify → fetch → push → post-push verify | **G1–G3 DONE; G4–G7 criteria + results in §6** |

---

## 2. Part A — Spec edits applied (each verified against the file)

| ID | Edit | Verification |
|---|---|---|
| **A2.1** | Header: `HEAD` `5ee4e655…` → `6119a2a8fd6b256c41066829aeb9399e33b8a4a3`; `Document version` 1.0.10 → **1.0.11**; `Last updated` 2026-10-13 → **2026-10-14** | Lines 6–8 read back verbatim |
| **A2.2** | §1.6.1 preamble "Twenty-three commits" → **"Twenty-eight commits"**, range `0714628` → `5ee4e65` → **`0714628` → `6119a2a`**; five rows appended to the commit table (`5773c60`, `fd4fcf9`, `c8acb5d`, `1a4818a`, `6119a2a`) | Table now contains exactly **28** rows (counted); `git rev-list --count 0714628~1..HEAD` → **28** — the table and the actual chain agree |
| **A2.3** | New **§1.6.5 Native Hardening Bundle (v1.0.11)** inserted after §1.6.4 (line 280), 58 lines, every bullet evidence-labelled | `grep -n "^#### 1.6.5"` → line 280 |
| **A2.4** | §12.5.1 row 9 (T5-SLOT-TEARDOWN) status expanded from "OPEN — UNKNOWN" to "OPEN — scope narrowed by v1.0.11"; **row 10 added** for the Axis A/B/C audit register | Rows 9 and 10 read back; row 10 cites `AUDIT_AXES_A_B_C_v1.0.11.md` §7–§8 |
| **A2.5** | §20 changelog: new **1.0.11** row (2026-10-14) | Line 1971 |
| **A2.6** | §20 Verification Status: `Active Fix Queue (§12.5.1)` row extended to rows 8/9/10; new `Native Hardening Bundle (§1.6.5)` row | Lines 1994, 2006 |

**Correction recorded during A2.2 (not an error introduced here):** the v1.0.10 table listed 23
commits ending at `5ee4e65`, but the chain contained a 24th commit — `5773c60` (the v1.0.10 spec
update itself), which was created *after* v1.0.10 recorded its HEAD. The five appended rows restore
the table to the full 28-commit contiguous range. Verified against `git log`, not estimated.

### 2.1 Frozen sections — untouched, as required

`git diff -U0` on `PROJECT_SPECIFICATION.md` yields **8 hunks**, all inside the six edits above
(`@@ -6`, `@@ -93`, `@@ -122`, `@@ -273`, `@@ -1573`, `@@ -1906`, `@@ -1929`, `@@ -1940`). There
are **no hunks touching §1.6.2, §1.6.3, §1.6.4, or §22 L1–L10**, and no hunk anywhere else in the
document. Net numstat **76 insertions / 10 deletions**.

---

## 3. Parts B / C / D / E — Audit results

Full evidence, tables, and the 17-item findings register are in
`AUDIT_AXES_A_B_C_v1.0.11.md`. Summarised here so this report stands alone.

### 3.1 Part B — `T5-SLOT-TEARDOWN` classification

**Verdict: B-class (minor, transient, self-healing) at the symptom level; OPEN — UNKNOWN at the
cause level; §12.5.1 row 9 stays OPEN with narrowed scope.**

- **Mechanism (SOURCE CODE, three sites).** `TunnelStateWatcher`'s teardown waiter matches the
  **exact attempt id**. A `NOOP_REENTRANT` completion emitted by the re-entrant teardown path
  (`SoftEtherVpnService.kt:1081–1106`) therefore satisfies the adapter's
  `withTimeoutOrNull(TEARDOWN_TIMEOUT_MS) { teardown.await() }` **before** the real `CONFIRMED`
  arrives from `ConnectionController.kt:937` — at which point `AndroidConnectionAdapter.kt:226`
  has already printed the residual line. The two completions are two answers to one attempt id,
  not a contradiction.
- **Severity (PHYSICAL DEVICE — PRIOR REPORT for this phase).** 7 occurrences in the hardening
  device run; 7/7 self-healed within ~1 s with `structure destroyed and slot released`, ownership
  cleared, next attempt successful, 0 crashes, no leak.
- **Still UNKNOWN (and why the row stays open).** (i) The upstream trigger that issues a *second,
  re-entrant* teardown for an attempt already in TEARDOWN was never captured — no instrumentation
  names the caller, and the two candidate explanations (two racing callers; the FIX-2
  `requestRecoveryTeardown` retry path) are **INFERENCE only**, neither confirmed. (ii) The same log
  line corresponded to a ~1 s window in the v1.0.11 run but a ~1 min blocked window in the v1.0.10
  run; nothing explains the variance, so the hardening evidence *narrows* the finding rather than
  closing it.
- **One established sub-defect (SOURCE CODE).** `AndroidConnectionAdapter.kt:226` emits the terminal
  `ownership retained … a new native attempt will be refused` for **both** `UNRESOLVED` and
  `NOOP_REENTRANT` — i.e. it promises a refusal on a condition that never refuses. Message-accuracy
  defect, independent of the unknown cause.

### 3.2 Part C — Axis A (Tunnel State / UI Sync): **OPEN**, medium

Two concurrent models of "is the VPN up" with no designated owner for UI truth, and at least one
screen reading both: `GlobalVpnTracker` (declared single source of truth) and
`AutoModeController._state` (`:442`) / `AutoModeEngine.stateFlow`, both collected in
`HomeScreen.kt:125–126`. `MainActivity` / `DetailActivity` hold no direct binding (blast radius
confined to `HomeScreen` and the status surfaces; `AutoModeViewModel.kt:27` and
`StatusViewModel.kt:200–202` add two further distinct observation paths).

Five mismatch sites, each verified: **A-2** the 500 ms `postDelayed` re-check in
`GlobalVpnTracker.onLost`; **A-3** protocol egating in `updateState`, where a disconnect of one
protocol cannot clear another protocol's state (the event is dropped, not reconciled); **A-4** the
three loss paths in `startConnectedWatcher` (~:585) at different latencies, including a 1-second
poll; **A-5** `onUnexpectedDisconnect` (`:668`) publishing `Disconnected` *before* its OQ-7/OQ-6/OQ-1
follow-up, so Auto Mode state leads the tracker; **A-6** the unreconciled dual consumption. The
product consequence (INFERENCE — each link is source, the chain is not measured as one event) is
that the disagreement window invites a reconnect into a slot the native layer may still own, i.e. it
is a *generator* of the T5-SLOT family.

### 3.3 Part D — Axis B (Server List Network Binding): **OPEN**, medium, blocked

**Current state (SOURCE CODE):** the retrieval path is unbound — `HttpFetcher.kt` `OkHttpFetcher`
builds a plain `OkHttpClient` (10 s connect / 15 s read, `ResilientDns`, no `bindProcessToNetwork`,
no `Network`, no per-socket bind, no `protect()`), shared as a single instance from
`VpnServerRepository.kt:40`. The `Network` needed for binding **is already obtained** by
`GlobalVpnTracker.registerSystemVpnWatcher.onAvailable` and **discarded**. The tunnel sockets *are*
protected (`ConnectionController.kt:659/671/682/1333/1343/1353/1648/1657`) — that is the opposite
direction and is correct; it says nothing about the server-list fetch. §11.8.2 already records this
observation, so **the specification is accurate and the roadmap (§12.5.2 row A) is correct**.

**Already decided (cited, not re-decided):** `SERVER_SOURCE_ARCHITECTURE.md` §7 Q2 —
**USER-DIRECTED REQUIREMENT** — per-request `Network.getSocketFactory()` on a dedicated
`OkHttpClient` for the VPN path; **`bindProcessToNetwork()` explicitly rejected** as too broad and
process-wide; min SDK API 21, target API 33. §7 Q3 default order **open → VPN fallback → emergency
→ manual**, with "always prefer VPN" as an opt-in setting only. §7 Q1 defines
`isTunnelUsable()` = CONNECTED **and** an RX counter advanced within 10 s **and** no TUN `EIO` in
30 s.

**Three-state gap enumerated:**

| State | Behaviour today |
|---|---|
| **S1 — no VPN active** | Default network = Wi-Fi/cell; the fetch works. The only validated state. |
| **S2 — the app's own VPN active** | The default route for its sockets becomes its own TUN; the fetch enters the tunnel with no binding and no usability check. Sub-outcome (a) it leaves via the VPN gateway (an unchosen routing side-effect) or (b) it hangs to the 10 s/15 s timeout on a half-open tunnel. The caller cannot distinguish (a) from (b) and neither is logged. |
| **S3 — transition (connecting/disconnecting)** | TOCTOU: the decision is made against a changing state, with no epoch/version guard; CONNECTED is published from `onAvailable`-driven updates and cleared via the 500 ms delayed re-check. |

**The blocker (SOURCE CODE):** `isTunnelUsable()` is **not implementable today** — a search of
`GlobalVpnTracker.kt` for `rx`/`Rx`/`RX`/`counter`/`bytes` returns **zero matches**; there is no RX
counter and no EIO counter, and the native Phase-13F overflow counters are not surfaced through JNI.
Axis C's instrumentation gap therefore hard-blocks Axis B's routing decision.

### 3.4 Part E — Axis C (SoftEther UDP / TUN `EIO`): **OPEN**

- **C-1 `udp_only` runs a structurally unwinning race (product-level, not a native regression).**
  `softether_protocol.h` keeps `udp_port` (seUdpPort, 0 = none) and `udp_only` (1 when
  seTcpPort ≤ 0) as **independent** fields; `softether_connect_parallel_race` (~:2719) branches on
  `udp_only` and logs `UDP-only server (seTcpPort=0) - parallel race: R-UDP direct + NAT-T`, running
  all five transports. The project's own `RUDP_IMPLEMENTATION_PLAN.md` (lines 7, 26, 45–51) records
  **0%** success for this client *and* the official v4.44 client across 30+ UDP-only servers, the
  relay returning `error=6` (NOT_FOUND), and concludes the only viable path is the **OpenVPN
  fallback — Open Item #1, unstarted**. Measured device cost: the full `race timeout 5000 ms`
  consumed before `all transports failed`. Scoping note: the RUDP plan's parity audit lists this
  exact flow as **verified identical** to the official client, so the native code is correct; the
  gap is product-level. Product interaction flagged, **not decided** (INFERENCE): §6.7 R5 budgets
  5 s per server and the race consumes 5000 ms, so an OpenVPN fallback would have to be a separate
  attempt rather than a post-race fallback under the current R5 budget.
- **C-2 TUN `EIO` has no branch.** `TunTerminal.readLoop()` (`:111–148`) special-cases only
  `EBADF` / "Bad file descriptor" (`:135–136`); every other exception — including the `EIO`-text
  Android raises on a revoked TUN — goes to `onError` (`:139`) and into `triggerReconnect()`.
  Whole-tree search for `EIO|EBADF` in `*.kt` returns only `TunTerminal.kt:133–136`. Absence of the
  branch is SOURCE CODE; the likelihood that the revoked case presents as EIO on the target device
  is **INFERENCE** (no EIO appears in the device log).
- **C-3 read-loop termination, accurately bounded.** On any exception the loop `break`s and
  `isRunning.set(false)`; there is no self-restart inside `TunTerminal`. Because the flag is reset,
  an external `start()` **would** re-arm it — so this is "no self-restart", not a one-way latch.
  Whether the owner re-arms it is **UNKNOWN** (its call sites were not read this phase). Also
  recorded: the *write* path has an explicit, documented errno mitigation (the Phase 13D fix at
  `softether_protocol.c:3553–3556`, because raw-Ethernet writes "failed with EINVAL … which killed
  all connectivity") while the read path has only the single `EBADF` case.
- **C-4 dead constant / buffer mismatch (hygiene).** `MAX_PACKET_SIZE = 32767`
  (`TunTerminal.kt:23`) is **never referenced again anywhere in the tree**, while the actual read
  allocation is `BUFFER_SIZE = 8192` (`:24`, `:112`). Not a live functional defect — the TUN MTU is
  1500 (`SoftEtherVpnService.kt:1322` with `ConnectionConfig.kt:30`) — but the 4× disagreement is
  undocumented.
- **C-5 counters that do not exist (the Axis B blocker).** No RX counter and no TUN-`EIO` counter in
  `GlobalVpnTracker.kt`; no `EIO` counter anywhere in the Kotlin tree; native Phase-13F overflow
  counters not surfaced through JNI.

**Cross-axis couplings** (matrix in the audit report §6, all individually verified): A→T5-SLOT
(the disconnect window invites a reconnect into an owned slot); C→B (**hard blocker** — no
counters, so no `isTunnelUsable()`); A→B (B's decision reads the same dual-source state Axis A
flags as inconsistent); C→A (an unclassified EIO *generates* the transient disconnect Axis A must
render); C→Auto Mode cost (5 s per UDP-only server, dominating R7's continuous loop).

---

## 4. Explicit no-change statements (five)

1. **No source code was read-modify-written in this phase.** Every file cited in this report and in
   `AUDIT_AXES_A_B_C_v1.0.11.md` was **read** at HEAD `6119a2a`. The only files written are
   `PROJECT_SPECIFICATION.md` (documentation), `AUDIT_AXES_A_B_C_v1.0.11.md` and this report.
2. **No build, unit test, androidTest, host test, adb command, or device interaction was performed
   in this phase.** All device evidence quoted here is attributed to prior phases through their
   reports (`DEVICE_VERIFY_HARDENING_AUTO_REPORT.md` and the v1.0.10 FINALIZE log) and is labelled
   `PRIOR REPORT` / `PHYSICAL DEVICE` accordingly. Nothing was re-measured, re-tested or re-run.
3. **No git mutation occurred during the audit work.** No commit, push, tag, force-push, reset,
   stash, checkout, restore, or `git add` was executed until the two phase commits of §7, which were
   staged **by exact file name** (never `git add -A` / `git add .`). The pre-existing local tags
   `p2.4.5`, `p2.4.7`, `p2.4.8`, `p2.4.9`, `p2.4.10` were read only and were not pushed, re-created,
   or deleted.
4. **The frozen specification regions are byte-identical.** §1.6.2, §1.6.3, §1.6.4 and §22 (L1–L10)
   received **no** hunk in `git diff` — verified with `git diff -U0` hunk enumeration, not assumed.
5. **No final value was invented.** The exact path-selection algorithm, ordering, concurrency model,
   timeouts, retry policy, and Android network-binding implementation remain design/implementation
   work unless already explicitly decided elsewhere; no new such value, threshold, priority
   commitment, or schema was introduced by this phase. Where a decision already exists
   (`SERVER_SOURCE_ARCHITECTURE.md` §7 Q1–Q3, each a **USER-DIRECTED REQUIREMENT**) it was cited,
   not re-decided. Where a decision does not exist, the item is recorded OPEN with a recommendation
   only.

---

## 5. Deliverables produced (15)

| # | Deliverable | Where |
|---|---|---|
| 1 | Specification header revised to v1.0.11 (HEAD, version, date) | `PROJECT_SPECIFICATION.md` lines 6–8 |
| 2 | §1.6.1 commit chain extended to the true 28-commit range with 5 appended rows | `PROJECT_SPECIFICATION.md` §1.6.1 |
| 3 | New §1.6.5 Native Hardening Bundle, fully evidence-labelled | `PROJECT_SPECIFICATION.md` §1.6.5 |
| 4 | §12.5.1 row 9 narrowed (T5-SLOT-TEARDOWN) | `PROJECT_SPECIFICATION.md` §12.5.1 |
| 5 | §12.5.1 row 10 added (Axis A/B/C audit register) | `PROJECT_SPECIFICATION.md` §12.5.1 |
| 6 | §20 changelog 1.0.11 row | `PROJECT_SPECIFICATION.md` §20 |
| 7 | §20 Verification Status rows (Active Fix Queue, §1.6.5) | `PROJECT_SPECIFICATION.md` §20 |
| 8 | `T5-SLOT-TEARDOWN` classification with mechanism, severity, and the residual UNKNOWN | `AUDIT_AXES_A_B_C_v1.0.11.md` §2 |
| 9 | Axis A audit: state-source table, five mismatch sites, product consequence | `AUDIT_AXES_A_B_C_v1.0.11.md` §3 |
| 10 | Axis B audit: current state, already-decided requirements, **three-state gap**, blocker | `AUDIT_AXES_A_B_C_v1.0.11.md` §4 |
| 11 | Axis C audit: `udp_only` path consequence, EIO gap, read-loop bound, dead constant | `AUDIT_AXES_A_B_C_v1.0.11.md` §5 |
| 12 | Counters list that must exist for `isTunnelUsable()` (RX + TUN-EIO) and their absence | `AUDIT_AXES_A_B_C_v1.0.11.md` §4.4, §5.4 |
| 13 | Cross-axis interaction matrix (five couplings, measured-vs-inferred labelled) | `AUDIT_AXES_A_B_C_v1.0.11.md` §6 |
| 14 | 17-item findings register with severity, status, and evidence per item | `AUDIT_AXES_A_B_C_v1.0.11.md` §7 |
| 15 | Dependency-ordered recommendations R1–R7 plus an explicit non-recommendation | `AUDIT_AXES_A_B_C_v1.0.11.md` §8 |

---

## 6. Part G — verification performed

### 6.1 G2 — diff-scope gate (before staging)

- `git diff --name-only` on the working tree → **`PROJECT_SPECIFICATION.md` only** among **tracked**
  files. No other tracked file was modified. The two new report files were untracked at that point and
  were staged by exact name. **Gate: PASS.**
- `git diff --numstat PROJECT_SPECIFICATION.md` → **76 / 10**; `git diff -U0 … | grep "^@@"` → 8
  hunks at lines 6, 93, 122, 273, 1573, 1906, 1929, 1940 — exactly the six edits A2.1–A2.6. No hunk
  in §1.6.2 / §1.6.3 / §1.6.4 / §22. **Gate: PASS.**
- `git status -sb` → `## main...origin/main`, no ` M` / `M` / ` A` lines other than the intended
  files. The many untracked `_*` / log / report artifacts from earlier phases were left untracked
  and were **not** staged. **Gate: PASS.**

### 6.2 G3 — the specification commit

`git add PROJECT_SPECIFICATION.md` (staged **by exact file name**; the two new report files were
still untracked and were **not** staged). `git status --porcelain | grep -v "^??"` before the commit
showed exactly one line: `M  PROJECT_SPECIFICATION.md`. The commit was created as:

| Field | Value |
|---|---|
| Short SHA | **`68758ae`** |
| Full SHA | `68758ae8824e7c8bafea25563425bb185ff1c4f3` |
| Subject | `docs: update PROJECT_SPECIFICATION to v1.0.11 (native hardening bundle + axes audit)` |
| Numstat | **1 file changed, 76 insertions(+), 10 deletions(-)** — matches the G2 gate exactly |
| Force / amend / reset | none |
| Post-commit tree | `git status --porcelain \| grep -v "^??"` → empty (only untracked files remain) |

### 6.3 G4 — the reports commit, and the ordering constraint it imposes

This report and `AUDIT_AXES_A_B_C_v1.0.11.md` are committed as the **second** commit, staged by
exact file name. Because this file is itself an input to that commit, **this report cannot record
the second commit's SHA or the results of the push**: both are produced *after* this file is
written. That is an ordering property of the phase (G4 → G5 → G6 → G7), not an omission. The
acceptance criteria for the remaining steps are therefore stated here, and the observed results are
reported in the phase's closing summary.

**G4 (reports commit):** `git add AUDIT_AXES_A_B_C_v1.0.11.md DOCS_UPDATE_v1.0.11_REPORT.md` — two
new files, no modification to any tracked file. Acceptance: numstat shows **2 files changed,
insertions only** (both are new files, so 0 deletions); `git status --porcelain | grep -v "^??"`
empty afterwards; `git diff --name-only` empty (nothing left modified among tracked files).

**G5 (verify):** `git log --oneline -3` shows the two new commits on top of `6119a2a`;
`git rev-list --count 0714628~1..HEAD` → **30**; the pre-existing five tags unchanged; no tracked
file modified.

**G6 (fetch + push):** `git fetch origin` then `git push` — **no `--force`, no `--tags`, no tag
argument, no `--set-upstream` rewrite**. Acceptance: `origin/main` still at `6119a2a…` before the
push (so the push is a **fast-forward**, `git merge-base --is-ancestor origin/main HEAD` succeeds);
push output reports a fast-forward.

**G7 (post-push verify):** `git rev-parse origin/main` == local `HEAD`;
`git rev-list --count origin/main..HEAD` → **0**; `git status -sb` → `## main...origin/main` with
**0 ahead, 0 behind**; `git tag` → the same five pre-existing tags (`p2.4.5`, `p2.4.7`, `p2.4.8`,
`p2.4.9`, `p2.4.10`), none pushed and none deleted; `git ls-remote --tags origin` → no new remote
tag.

---

## 7. Next steps (recommendations carried forward, not commitments)

1. **R1 (highest value, logging-only):** instrument the re-entrant-teardown caller identity
   (`SoftEtherVpnService.kt:1081–1106`) so the next `T5-SLOT-TEARDOWN` occurrence records *who*
   issued the second teardown. This is the only step that can convert the residual's cause from
   UNKNOWN to classified, and it follows the `5e51e37` logging-only precedent. It should also
   explain the ~1 s vs ~1 min window variance.
2. **R2:** split `AndroidConnectionAdapter.kt:226`'s terminal message by `TeardownOutcome` so
   `UNRESOLVED` and `NOOP_REENTRANT` are not reported with the same "will be refused" promise.
3. **R3 (Axis A):** take the "single UI state owner" decision in a decision record **before** any
   refactor, otherwise A-2/A-3/A-4/A-5 fixes will encode the inconsistency.
4. **R4 (Axis B, dependency-ordered):** add the RX counter and TUN-`EIO` counter to
   `GlobalVpnTracker` **first** (the hard blocker), then implement the per-request
   `Network.getSocketFactory()` bind exactly as `SERVER_SOURCE_ARCHITECTURE.md` §7 Q2 directs —
   never `bindProcessToNetwork()` — following the §7 Q3 order (open → VPN fallback → emergency →
   manual), and surfacing the `Network` already captured in `onAvailable` instead of discarding it.
5. **R5/R6 (Axis C):** classify the TUN read error explicitly (add the revoked-interface case beside
   `EBADF`), then decide — after reading the owner's call sites, which this phase did not do —
   whether the read loop should self-restart; and resolve the `MAX_PACKET_SIZE` (32767) vs
   `BUFFER_SIZE` (8192) mismatch with a documented decision.
6. **R7 (product):** schedule the OpenVPN fallback for `SETcpPort = 0` servers
   (`RUDP_IMPLEMENTATION_PLAN.md` Open Item #1) and resolve its interaction with §6.7 R5's 5 s
   per-server budget, since the race alone consumes 5000 ms.

**Open decision inherited, unchanged:** the pre-existing local annotated tag `p2.4.10` (tagger date
2026-09-28, points at `0714628`) remains unpushed; push-or-delete is still an open decision for a
future phase, carried forward from §1.6.3.


