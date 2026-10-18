# COMMIT-BATCH-P0 — Pre-Flight Report

**Phase:** COMMIT-BATCH-P0 (commit P0 fixes into local commits; no push, no tag)
**Date:** 2026-10-12
**Mode:** Git-only. No source/test/Gradle/native edits. No build. No device. No CI. No push. No tag.
**Verdict:** **COMMITS BLOCKED — CATEGORY G (UNCERTAIN) IS NON-EMPTY; REPORTED AND WAITING (rule E1)**

---

## 1. Executive Summary

The read-only pre-flight (P1–P5) is **complete**. It surfaced a fact the phase brief did not
anticipate: the working tree does **not** contain "this session's P0 fixes" only. It contains
**a large body of accumulated work from many prior sessions** — 57 tracked-modified files and
~120 untracked entries, of which the six P0 fix files from this session are a small minority.

The brief anticipated this ("Those entries span MANY sources…") and responded with a rule:

> **P4.** … "If any file in A does NOT contain the expected marker, move it to **G (uncertain)**"
> **E1.** … "STOP for a final go/no-go from the user **IF P2–P5 revealed any UNCERTAIN (G) files
> that cannot be confidently classified.** In that case report and wait."

P4 was run against the brief's own marker list. **The majority of the pre-existing modified files
carry no marker from that list** — they carry *different* markers (`Stage 2C-A`, `Stage 2F-A`,
`Stage 2F-F.11`…`2F-F.16`, `Stage 2F-F.28AA`, `BUG 1/2/3`), i.e. they belong to earlier phases
whose completed/failed status this phase has no authority to determine.

Per rule E1, **no commit was executed**. Zero `git add`, zero `git commit`. The tree is exactly as
it was found (178 dirty entries, HEAD `0714628`).

This is the correct outcome. Committing ~50 files whose completion status cannot be verified would
convert a protective measure into a **false record** — it would assert "these are the audited P0
fixes" about code that no phase in this session audited.

---

## 2. Baseline (P1) — CONFIRMED, UNCHANGED

| Item | Value |
|---|---|
| `git rev-parse HEAD` | `071462816b1eef7e7f7459483fcd69909938cd11` |
| `git branch --show-current` | `main` |
| `git status --short \| wc -l` | **178** |
| `git remote -v` | `origin  https://github.com/morteza-taheri/SoftEtherGai.git` (fetch **and push**) |
| `git diff HEAD --shortstat` | **57 files changed, 10 688 insertions(+), 949 deletions(-)** |
| `main` ref | `0714628` |
| Local-only branches | `backup-local` (`392fc42`), `integrate` (`d893153`) |

**`origin` is configured with a push URL.** A push is possible and is therefore the single
highest-consequence hazard in this phase. No push was performed and none will be without explicit
authorization.

Note on the count: the brief stated **177** dirty entries; the tree currently has **178**. The
difference is `FIX_U36_FULL_BUILD_VERIFY_REPORT.md`, written after the brief was drafted. This is
expected drift, not a discrepancy.

---

## 3. Categorization (P2/P3)

The brief's categories: **A** source code · **B** build/config · **C** canonical docs ·
**D** session reports · **E** evidence/artifacts · **F** memory/agent · **G** uncertain.

### 3.1 Category A / B / C — the six commits' actual payload

**A — SOURCE (marker-verified)**

| File | Marker found (P4) |
|---|---|
| `SoftEtherClient/src/main/cpp/jni/softether_jni.c` | `+77` — N-01 `:230`, N-02 `:301`, N-03 `:357`/`:363`, N-04 `:633`, N-05 `:600` ✓ |
| `SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_rudp.c` | `+17` — `goto out;` at `:1010`, `:1017` ✓ |
| `SoftEtherClient/src/main/cpp/test/native_test.c` | `+2114` — bounds-test section ✓ |
| `SoftEtherClient/src/main/cpp/test/native_test.h` | `+36` — six declarations ✓ |
| `SoftEtherClient/src/main/cpp/test/test_jni_bridge.c` | `+97` — six JNI wrappers ✓ |
| `SoftEtherClient/src/main/java/vn/unlimit/softether/controller/ConnectionController.kt` | G-1 (`reconnectAttempts.set(0)` ×9) + G-2 (`teardownIdentity` ×4) ✓ |
| `SoftEtherClient/src/androidTest/java/vn/unlimit/softether/test/NativeConnectionTest.kt` | `nativeTestNativeSendRejectsOversizedLength` `:137`/`:614`; `nativeTestRudpRecvSmallBufferReleasesLock` `:142`/`:700` ✓ |

**B — BUILD/CONFIG (marker-verified)**

| File | Marker found (P4) |
|---|---|
| `SoftEtherClient/src/main/cpp/CMakeLists.txt` | `option(BUILD_TESTING …)` `:10`; `if(BUILD_TESTING)` `:152` ✓ |
| `SoftEtherClient/build.gradle` | `-DBUILD_TESTING=OFF` `:47` / `=ON` `:63` ✓ |
| `build.gradle` | `id "com.google.devtools.ksp" version '2.3.12'` `:18` ✓ |

**C — CANONICAL DOCS:** `PROJECT_SPECIFICATION.md`, `SERVER_SOURCE_ARCHITECTURE.md` (untracked, authored as project documents).

**That is the complete, marker-verified payload — 12 files. Everything else is G.**

### 3.2 Category G — UNCERTAIN (the blocking finding)

#### G-1. Tracked-modified files with **no marker from the brief's P4 list** (44 files)

These carry other phases' markers and this session never audited them:

`app/src/main/java/vn/unlimit/vpngate/` — `App.kt` (+7/−0, no marker), `activities/DetailActivity.kt` (`Stage 2F-A`), `automode/AndroidConnectionAdapter.kt` (+339), `AutoModeController.kt` (+1077/−41, `BUG 1`, `Stage 2F-F.11`…`2F-F.16`), `AutoModeEngine.kt` (`Stage 2F-F.2/13/15/16`), `AutoModeModels.kt` (+120), `AutoModeProtocol.kt` (+87), `TunnelStateWatcher.kt` (+295), `data/model/VpnModels.kt`, `data/remote/HttpFetcher.kt`, `data/remote/VpnGateSources.kt` (+165/−95), `db/AppDatabase.kt` (`Stage 2F-A`), `db/VPNGateItemDao.kt`, `merger/VpnServerMerger.kt`, `models/ExcludedApp.kt`, `models/VPNGateConnection.kt`, `models/VPNGateConnectionList.kt`, `models/VPNGateItem.kt`, `network/ServerReachabilityTester.kt` (+332/−47), `parser/VpnGateApiParser.kt`, `parser/VpnGateHtmlParser.kt` (`Stage 2C-A`), `repository/VpnConnectionMapper.kt`, `repository/VpnServerRepository.kt` (+147/−69), `state/GlobalVpnTracker.kt`, `ui/screens/automode/AutoModeScreen.kt`, `ui/screens/home/HomeScreen.kt`, `ui/screens/settings/ExcludedAppsSheet.kt`, `ui/screens/settings/SettingsComponents.kt`, `ui/screens/settings/SettingsScreen.kt`, `ui/screens/status/StatusViewModel.kt`, `utils/DataUtil.kt`, `viewmodels/ConnectionListViewModel.kt`

`app/src/test/java/vn/unlimit/vpngate/` — `automode/AutoModeControllerTest.kt` (+1798/−10), `AutoModeEmptyListGuardTest.kt`, `AutoModeEndToEndTest.kt`, `AutoModeServerCursorTest.kt`, `collector/HtmlParserOracleTest.kt`, `collector/RepositoryTest.kt`, `network/ServerReachabilityTesterTest.kt` (+452/−18)

Other — `SoftEtherClient/src/main/java/vn/unlimit/softether/SoftEtherVpnService.kt` (+755/−46), `SoftEtherClient/src/main/java/vn/unlimit/softether/client/SoftEtherClient.kt` (+78/−2), `app/build.gradle` (+54/−17), `vpnLib/build.gradle` (+9/−2), `.github/workflows/android-ci.yml` (+8/−1), `app/src/main/res/values/strings.xml`, `values-fa/strings.xml`, `values-vi/strings.xml`

#### G-2. Untracked source files (73 files) — no marker list applies

The brief's P4 marker list addresses **only** this session's seven fixes. It provides **no
expected marker** for:

* `SoftEtherClient/src/main/java/…/NativeLifetimeRegistry.kt`, `SoftEtherSessionPolicy.kt`, `TeardownCompletion.kt`, `TunOwnershipCoordinator.kt`, `controller/NativeAttemptCoordinator.kt`, `controller/NativeHandleSlot.kt` — production classes whose supporting tests this session *did* run as part of the 145-test baseline, but whose own provenance was never audited here.
* `SoftEtherClient/src/test/java/…` — 8 test files (`NativeLifetimeRegistryTest.kt`, `SoftEtherSessionPolicyTest.kt`, `TunOwnershipCoordinatorTest.kt`, `client/SoftEtherClientHandleGuardTest.kt`, `controller/NativeAttemptCoordinatorTest.kt`, `NativeHandleSingleOwnerTest.kt`, **`ReconnectTerminalPathTest.kt`**, **`TeardownLogIdentityTest.kt`**).
  The last two **are** this session's work (PHASE B / PHASE C) and could be argued into A — but the brief's P4 marker list does not name them, so by the brief's own rule they land in G.
* `app/src/main/java/…` — 12 new production files (`automode/OwnedVpnDecision.kt`, `data/persistence/CollectorSnapshotStore.kt`, `VpnLastConnectionStore.kt`, `data/remote/RedirectPolicy.kt`, `SourceIdentity.kt`, `SourceValidation.kt`, `models/OpenVpnConfigTransform.kt`, `network/AutoConnectReachability.kt`, `repository/DatasetIntegrity.kt`, `ServerListScreenPolicy.kt`, `ServerListUpdateScheduler.kt`, `ServerListUpdater.kt`, `ServerListUpdates.kt`, `ui/screens/home/ServerListSearchState.kt`).
* `app/src/test/java/…` — 21 new test files.
* `app/schemas/vn.unlimit.vpngate.db.AppDatabase/5.json` — a Room schema-export JSON. The brief forbids editing `app/schemas/**`; it does not say whether a new schema may be **committed**. Ambiguous by construction → G.

**G total: 117 files (44 tracked-modified + 73 untracked).**

---

## 4. Why These Cannot Be Confidently Classified

Classifying a file into A/B/C is not a file-type judgment — it is an assertion that **the file's
current contents are the audited, intended end-state of a completed change.** I can verify that for
12 files and no more:

1. **The brief supplied markers for seven fixes only.** For the other ~105 files there is no
   expected content to compare against, so "does this file contain the expected marker?" has no
   defined answer. The brief's rule resolves that case explicitly: move to G.
2. **Large uncommitted deltas suggest in-flight work, not finished work.** `AutoModeControllerTest.kt` (+1798) and `AutoModeController.kt` (+1077) are enormous. A 1 798-line test delta is characteristic of active development, not a frozen deliverable.
3. **A commit is a durable claim.** Committing these files asserts "reviewed and intended". Nothing in this session reviewed them. If any is a half-finished refactor, the commit launders it into history where a later `git bisect` will trust it.
4. **The phase's own purpose argues for restraint.** The brief's rationale is "This protects the fixes from accidental worktree damage." Committing 129 unverified files does not protect the P0 fixes — it buries them.

---

## 5. P4 Results — Verbatim Marker Evidence

```
N-01..N-05  softether_jni.c
  :230  LOGE("Send length out of bounds: length=%d array=%d", length, array_len);
  :301  LOGE("Receive maxLength out of bounds: maxLength=%d buffer=%d",
  :357  LOGE("receiveBatch maxLength out of bounds: maxLength=%d buffer=%d",
  :363  LOGE("receiveBatch maxPackets out of bounds: maxPackets=%d lengths=%d",
  :600  LOGE("setClientMac requires a 6-byte array, got %d", mac_len);
  :633  LOGE("SendRaw length out of bounds: length=%d array=%d", length, array_len);

N-08        softether_rudp.c
  :1010  goto out;
  :1017  goto out;

L-01        CMakeLists.txt    :10 option(BUILD_TESTING … OFF); :152 if(BUILD_TESTING)
            SoftEtherClient/build.gradle  :47 -DBUILD_TESTING=OFF ; :63 -DBUILD_TESTING=ON

U-36        build.gradle      :18 id "com.google.devtools.ksp" version '2.3.12' apply false

G-1         ConnectionController.kt   FIX-G1-IMPL / reconnectAttempts.set(0)  → 9 hits
G-2         ConnectionController.kt   teardownIdentity                        → 4 hits

Kotlin      NativeConnectionTest.kt
  :137/:614  nativeTestNativeSendRejectsOversizedLength
  :142/:700  nativeTestRudpRecvSmallBufferReleasesLock
```

All 12 marker checks **PASS**. Each file in §3.1 provably contains its expected fix.

---

## 6. P5 Sanity Check — PASS

No file in A/B/C/D matches `*.log, *.png, *.jpg, *.apk, *.so, *.a, *.class, *.jar`. Result: **NONE**.
No file needs to be demoted to E on this basis.

## 7. Categories D / E / F — NOT COMMIT CANDIDATES

* **D (session reports)** — 26 root `*.md` files (`F28AL_*` ×15, `FIX_*` ×9, `DEEP_REVIEW_VERIFY_REPORT.md`, `_REPORT` set). The brief lists D as a *category to inventory*, and its commit plan does include a docs commit. These are this project's own audit trail and are safe, **but they are not P0 fixes.** Recommend a **separate, later** docs commit under its own authorization.
* **E (evidence)** — `_f28al_repro/`, `_f28alv/`, `_f28alv_bug3_verify/`, `_f28alv_verify/`, `_f28aly_backup/`, `_f28aly_diag/`, `_f28aly_full/`, `_staging/`, `test-evidence/`, `emergency/`, `build2.txt`, `_*.txt`, `_*.csv`, `_*.log`, `_*.err`, `_*.pid`, `_html_probe.bin`, `p18_*.txt`, `ui.xml`, `ui_now.xml`, `_v[123]_*.csv|txt`, `f28al*.log`. **EXCLUDED** — confirmed: `ui.xml`/`ui_now.xml` are uiautomator hierarchy dumps.
* **F (memory/agent)** — `.workbuddy-ai/`, `.kilo/plans/`. **EXCLUDED** per the brief.
* **`scripts/`** (untracked, contains `_fixtures/sample_cache.json`) — utility scripts. Unclear intent → **G-adjacent; excluded.**

---

## 8. The Commit Plan I Would Execute (on authorization)

Six commits, in order. **Only the first five are ready now** — each touches files I have marker-verified.

| # | Message | Files |
|---|---|---|
| 1 | `fix(native): validate JNI array lengths and release RUDP lock (P0 bounds)` | `softether_jni.c`, `softether_rudp.c`, `native_test.c`, `native_test.h`, `test_jni_bridge.c` |
| 2 | `fix(android): route reconnect failure through service onError; name teardown identity` | `ConnectionController.kt` |
| 3 | `build(native): exclude test library from release build` | `CMakeLists.txt`, `SoftEtherClient/build.gradle` |
| 4 | `build(ksp): align KSP version with Kotlin` | `build.gradle` |
| 5 | `test(androidTest): expose native bounds tests to the Kotlin bridge` | `NativeConnectionTest.kt` |
| 6 | *(held)* docs: session reports | 26 root `*.md` files — **needs its own go-ahead** |

Plus, **pending your ruling**, two files that are arguably this session's and could join commit 2
or gain their own commit:

* `SoftEtherClient/src/test/java/vn/unlimit/softether/controller/ReconnectTerminalPathTest.kt` (PHASE B)
* `SoftEtherClient/src/test/java/vn/unlimit/softether/controller/TeardownLogIdentityTest.kt` (PHASE C)

I recommend including these two — they are the regression tests for commits 2's fixes, and leaving
the fix committed while its test stays untracked defeats the purpose. But the brief's marker list
does not name them, so I am asking rather than assuming.

---

## 9. What I Need From You

**Decision 1 — the core question.**
The 117 G files span many prior sessions. Which of these do you want?

* **(a) Commit only the 12 marker-verified files** (commits 1–5, optionally +2 tests). Safest. Nothing unverified enters history. ← **my recommendation**
* **(b) Commit 12 verified + all 44 tracked-modified app/`SoftEtherClient` source files**, treating "already modified before this phase" as sufficient evidence of intent. Faster, but asserts an audit that never happened.
* **(c) Commit everything source-like** (12 + 44 + 73 untracked). Highest risk.
* **(d) Wait.** Hold all commits until a phase actually audits the remaining files.

**Decision 2 —** the 26 root `*.md` session reports: separate docs commit (yes/no)?

**Decision 3 —** `app/schemas/…/5.json` (new Room schema export): commit or leave untracked?

**Decision 4 —** the two PHASE B/C test files (`ReconnectTerminalPathTest.kt`, `TeardownLogIdentityTest.kt`): include with commit 2, or leave?

---

## 10. Phase Ledger

| Step | Status |
|---|---|
| P1 — record baseline | **DONE** — HEAD `0714628`, branch `main`, 178 dirty, `origin` push URL present |
| P2 — categorize into A–G | **DONE** — A=7, B=3, C=2, D=26, E/F excluded, **G=117** |
| P3 — list exact paths per category | **DONE** — §3 |
| P4 — marker cross-check | **DONE** — 12 PASS, 44 marker-less → G, 73 no-applicable-marker → G |
| P5 — extension sanity check | **DONE** — NONE in violation |
| E1 — STOP if G non-empty | **TRIGGERED** — reported, waiting |
| E2 — execute commits | **NOT STARTED** (blocked by E1) |
| E3–E5 | **NOT REACHED** |

## 11. Compliance Attestation

| Rule | Status |
|---|---|
| No push | ✅ none |
| No tag | ✅ none |
| No `git reset`/`clean`/`stash`/`checkout`/`restore` | ✅ none |
| No file deletion | ✅ none |
| No `git add -A` / `git add .` | ✅ no `git add` at all |
| No build outputs / evidence / memory / logs / binaries committed | ✅ none staged |
| No file modified in this phase | ✅ tree byte-identical (178 dirty) |
| `git add <explicit-path>` only | ✅ trivially satisfied (no adds) |
| Explicit-path adds, report-and-wait on uncertainty | ✅ E1 applied verbatim |

**Nothing in the repository was changed by this phase.** HEAD is still
`071462816b1eef7e7f7459483fcd69909938cd11`; the dirty count is still **178** (57 modified / 121
dir-or-file untracked, expanding to 416 with `--untracked-files=all`).

---

**RECOMMENDATION:** `COMMITS PARTIAL — HELD FOR USER CONFIRMATION ON UNCERTAIN FILES`

**NEXT:** Answer Decision 1 (a/b/c/d) — I recommend **(a)**. On "a", I execute commits 1–5 using
explicit-path `git add` per file, run `git log -1 --stat` after each, and re-report. Follow-ups
pending explicit decision either way: `PROCEED TO FIX-NATIVE-LIFETIME-SYNC (N-15 + N-16)`,
`PROCEED TO DEVICE VERIFICATION` (blocked — device `5585fecc` not enumerating),
`PROCEED TO CONTINUE DEEP REVIEW FIXES`, `PROCEED TO Z1-IMPL`, or
`WAIT FOR EXPLICIT USER DECISION`.

**Report language:** English only, as required.
