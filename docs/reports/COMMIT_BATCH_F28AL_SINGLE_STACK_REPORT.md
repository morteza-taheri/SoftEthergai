# COMMIT-BATCH-F28AL-SINGLE-STACK — Execution Report

Phase: land the entire remaining F.28AL stack as **one** commit.
Mode: git + forced compile + forced cache-disabled unit tests. **No push. No tag.**
Language: English only.

---

## 1. Phase Identity

| Item | Value |
|---|---|
| Phase | `COMMIT-BATCH-F28AL-SINGLE-STACK` |
| Starting HEAD | `98a646219a7d113f976e7d2b9ed2dd742e34e8bf` |
| Starting dirty count | 140 |
| Branch | `main` |
| Authorized actions | Stage explicit paths, one commit, forced compile, forced cache-disabled tests |
| Forbidden | push, tag, source/test/Gradle/CMake edits, `git add -A`/`git add .`, build-output/evidence/memory staging |

## 2. Pre-Conditions (P1–P3)

### P1 — HEAD confirmed (re-run)
```
git rev-parse HEAD  ->  98a646219a7d113f976e7d2b9ed2dd742e34e8bf
git status --short | wc -l  ->  140
branch: main
```
**PASS.**

### P2 — mtime validity scan (the CLOSURE_VOID guard)
```
find app/src SoftEtherClient/src vpnLib/src sstpClient/src -name "*.kt" -newermt "2026-10-12 23:40"
-> (empty)
```
No `.gradle` or `CMakeLists.txt` newer than the threshold either (only `./.gradle`, the cache directory, was touched).

**PASS — the VERIFY-STACK-CLOSURE verification was still valid against the bytes that were about to be committed. No `CLOSURE_VOID`.**

### P3 — Commit-set enumeration
```
git status --short --untracked-files=all > _pre_commit_set.txt
```
**428 raw entries** = 47 tracked-modified (` M`) + 381 untracked dir-or-file entries.

Filtering to `*.kt` and `*/schemas/*.json` yielded **41 files**.

> ### ⚠️ DEVIATION FROM BRIEF EXPECTATION — reported prominently
>
> The brief stated: *"The expected total is ~53 untracked source + 47 tracked-modified = ~100 files."*
>
> **Actual: 41 untracked source files, all under `app/`; ZERO under `SoftEtherClient/`.**
>
> **Reason:** the `SoftEtherClient`-side classes and tests had **already been committed** in two earlier phases of this session:
> * `ab82ce1` — COMMIT-BATCH-F28AL-REPAIR (8 files: `NativeLifetimeRegistry`, `TeardownCompletion`, `NativeAttemptCoordinator`, `NativeHandleSlot` + 4 test files)
> * `98a6462` — COMMIT-BATCH-F28AL-GROUP2 (4 files: `SoftEtherSessionPolicy`, `TunOwnershipCoordinator` + 2 test files)
>
> So there was nothing left to stage from that module. **The real total is 88, not ~100.**

The 41 untracked files (by group):

| Group | Count | Files |
|---|---|---|
| `app/schemas/` | 1 | `vn.unlimit.vpngate.db.AppDatabase/5.json` |
| `app/src/main/java/vn/unlimit/vpngate/` | 14 | `OwnedVpnDecision`, `data/persistence/CollectorSnapshotStore`, `data/persistence/VpnLastConnectionStore`, `data/remote/RedirectPolicy`, `data/remote/SourceIdentity`, `data/remote/SourceValidation`, `models/OpenVpnConfigTransform`, `network/AutoConnectReachability`, `repository/DatasetIntegrity`, `repository/ServerListScreenPolicy`, `repository/ServerListUpdateScheduler`, `repository/ServerListUpdater`, `repository/ServerListUpdates`, `ui/screens/home/ServerListSearchState` |
| `app/src/test/java/vn/unlimit/vpngate/` | 26 | `ServerListUpdaterTest`, `VpnM24x10TaskTest`, `VpnTunnelStateTest`, `automode/{AutoModeDatasetSourceTest, AutoModeLifecycleF28Test, AutoModeNativeBusyTest, AutoModeReconnectTest, TeardownWaiterIdentityTest}`, `collector/{MirrorTestFixtures, MirrorValidationTest}`, `data/{CollectorSnapshotStoreTest, VpnLastConnectionStoreTest}`, `db/SstpMigration4To5Test`, `models/{OpenVpnConfigTransformTest, SstpPersistenceTest, SstpPortPresentationTest}`, `network/{AutoConnectProbeTransportTest, ReachabilityGateTest, ReachabilityNamespaceTest}`, `repository/{DatasetIntegrityTest, DuplicateHostDatasetTest, ServerListAcquisitionTest, ServerListScreenPolicyTest, ServerListUpdaterDatasetTest, VpnConnectionPortProvenanceTest}`, `ui/screens/home/ServerListSearchStateTest` |

**`app/schemas/…/5.json` — inclusion ruled**
The brief's I2 *explicitly* listed `*/schemas/*.json` in the include filter, and the COMMIT-BATCH-F28AL-AUDIT independently concluded it **should** be committed (it matches `@Database(version = 5)`, schemas 1–4 are already committed, and the 17 181-byte `SstpMigration4To5Test.kt` depends on it). **It was staged and committed.** This resolves the open question carried forward from the audit.

## 3. C1 — Closure Re-Check (mandatory BEFORE staging)

```
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" CI=1 \
GRADLE_OPTS="-Dorg.gradle.internal.launcher.localhost=127.0.0.1 -Dorg.gradle.internal.remote.internal.inet.tcp.port=0" \
./gradlew :app:compileProDebugKotlin --no-daemon --rerun-tasks
```

```
BUILD SUCCESSFUL in 8m 1s
40 actionable tasks: 40 executed
Configuration cache entry reused.
```

* **40/40 EXECUTED, zero `UP-TO-DATE`** — the forced compile genuinely re-ran.
* Zero `error:` / `FAILED` lines.
* Only the **pre-existing** warning set appeared (identical to VERIFY-STACK-CLOSURE): `SstpVpnService.kt` nullable-receiver ×6, `DetailActivity.kt` ReadWriteProperty naming, `AndroidConnectionAdapter.kt` deprecated `allNetworks`, `AutoModeController.kt:482:38 No cast needed`, `AppDatabase.kt` Migration naming ×3, `VpnGateHtmlParser.kt` unnecessary `!!` ×2, `GlobalVpnTracker.kt` deprecated `allNetworks`, `CopyDebugSheets.kt` deprecated `LocalClipboardManager`, `FilterSheet.kt` deprecated `menuAnchor`, `HomeScreen.kt` deprecated `Icons.Filled.Sort`, `SettingsScreen.kt` deprecated `Icons.Outlined.Launch`, `StatusScreen.kt` deprecated `LocalLifecycleOwner`.

**PASS — closure confirmed on the exact bytes subsequently committed.**

## 4. I1 — Stage Tracked-Modified (47 files)

```
git diff --name-only | while IFS= read -r f; do git add -- "$f" || echo "FAILED: $f"; done
```
* Exactly **47** files enumerated, **47 staged**, **zero** `FAILED:` lines.
* Whitespace-safe (`IFS= read -r`), one explicit path per `git add`. No `git add -A`, no `git add .`.

The 47 (verbatim):
```
.github/workflows/android-ci.yml
SoftEtherClient/src/main/java/vn/unlimit/softether/SoftEtherVpnService.kt
SoftEtherClient/src/main/java/vn/unlimit/softether/client/SoftEtherClient.kt
app/build.gradle
app/src/main/java/vn/unlimit/vpngate/App.kt
app/src/main/java/vn/unlimit/vpngate/activities/DetailActivity.kt
app/src/main/java/vn/unlimit/vpngate/automode/AndroidConnectionAdapter.kt
app/src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt
app/src/main/java/vn/unlimit/vpngate/automode/AutoModeEngine.kt
app/src/main/java/vn/unlimit/vpngate/automode/AutoModeModels.kt
app/src/main/java/vn/unlimit/vpngate/automode/AutoModeProtocol.kt
app/src/main/java/vn/unlimit/vpngate/automode/TunnelStateWatcher.kt
app/src/main/java/vn/unlimit/vpngate/data/model/VpnModels.kt
app/src/main/java/vn/unlimit/vpngate/data/remote/HttpFetcher.kt
app/src/main/java/vn/unlimit/vpngate/data/remote/VpnGateSources.kt
app/src/main/java/vn/unlimit/vpngate/db/AppDatabase.kt
app/src/main/java/vn/unlimit/vpngate/db/VPNGateItemDao.kt
app/src/main/java/vn/unlimit/vpngate/merger/VpnServerMerger.kt
app/src/main/java/vn/unlimit/vpngate/models/ExcludedApp.kt
app/src/main/java/vn/unlimit/vpngate/models/VPNGateConnection.kt
app/src/main/java/vn/unlimit/vpngate/models/VPNGateConnectionList.kt
app/src/main/java/vn/unlimit/vpngate/models/VPNGateItem.kt
app/src/main/java/vn/unlimit/vpngate/network/ServerReachabilityTester.kt
app/src/main/java/vn/unlimit/vpngate/parser/VpnGateApiParser.kt
app/src/main/java/vn/unlimit/vpngate/parser/VpnGateHtmlParser.kt
app/src/main/java/vn/unlimit/vpngate/repository/VpnConnectionMapper.kt
app/src/main/java/vn/unlimit/vpngate/repository/VpnServerRepository.kt
app/src/main/java/vn/unlimit/vpngate/state/GlobalVpnTracker.kt
app/src/main/java/vn/unlimit/vpngate/ui/screens/automode/AutoModeScreen.kt
app/src/main/java/vn/unlimit/vpngate/ui/screens/home/HomeScreen.kt
app/src/main/java/vn/unlimit/vpngate/ui/screens/settings/ExcludedAppsSheet.kt
app/src/main/java/vn/unlimit/vpngate/ui/screens/settings/SettingsComponents.kt
app/src/main/java/vn/unlimit/vpngate/ui/screens/settings/SettingsScreen.kt
app/src/main/java/vn/unlimit/vpngate/ui/screens/status/StatusViewModel.kt
app/src/main/java/vn/unlimit/vpngate/utils/DataUtil.kt
app/src/main/java/vn/unlimit/vpngate/viewmodels/ConnectionListViewModel.kt
app/src/main/res/values-fa/strings.xml
app/src/main/res/values-vi/strings.xml
app/src/main/res/values/strings.xml
app/src/test/java/vn/unlimit/vpngate/automode/AutoModeControllerTest.kt
app/src/test/java/vn/unlimit/vpngate/automode/AutoModeEmptyListGuardTest.kt
app/src/test/java/vn/unlimit/vpngate/automode/AutoModeEndToEndTest.kt
app/src/test/java/vn/unlimit/vpngate/automode/AutoModeServerCursorTest.kt
app/src/test/java/vn/unlimit/vpngate/collector/HtmlParserOracleTest.kt
app/src/test/java/vn/unlimit/vpngate/collector/RepositoryTest.kt
app/src/test/java/vn/unlimit/vpngate/network/ServerReachabilityTesterTest.kt
vpnLib/build.gradle
```

## 5. I2 — Stage Untracked Source (41 files)

```
git ls-files --others --exclude-standard -z | while IFS= read -r -d '' f; do
  case "$f" in *.kt|*/schemas/*.json) git add -- "$f" || echo "FAILED: $f" ;; esac
done
```
* **41 files staged**, **zero** `FAILED:` lines.
* NUL-safe (`-z` + `read -d ''`) — handles any path.
* Filter admits **only** `*.kt` and `*/schemas/*.json`. Excluded by construction: `.md` reports, `.txt` logs, `_f28alv/`/`_f28aly_*/`/`_f28al_repro/`/`testing/`-style evidence dirs, `build/` outputs, `.apk`/`.so`/`.a`/`.class`/`.jar`, `.workbuddy-ai/`, `.idea/`, `.vscode/`.

Staged totals after I1+I2: **88** = 47 modified + 41 added (`--diff-filter=A` = 41).

## 6. I3 — Staged-Set Verification

**I3a — Exclusion scan (must be empty):**
```
git diff --cached --name-only | grep -Ei '\.md$|\.txt$|\.log$|\.png$|\.jpg$|\.apk$|\.so$|\.a$|\.class$|\.jar$|^_f28|test-evidence/|\.workbuddy-ai/|^build/|/build/|\.idea/|\.vscode/'
-> (none - OK)
```
**PASS — nothing forbidden was staged.**

**I3b — Extension tally:**
```
81 kt
 3 xml      (values/strings.xml, values-fa/strings.xml, values-vi/strings.xml)
 2 gradle   (app/build.gradle, vpnLib/build.gradle)
 1 yml      (.github/workflows/android-ci.yml)
 1 json     (app/schemas/vn.unlimit.vpngate.db.AppDatabase/5.json)
```

**I3c — Top-level directory tally:**
```
84 app
 2 SoftEtherClient
 1 vpnLib
 1 .github
```

**PASS.** The staged set matches the P3 inventory exactly (88 files), with zero entries from any excluded category.

## 7. I4 — The Commit

```
commit c5720508a41ee3f46412a108673cae46abfccda0
Author: morteza.taheri <morteza.taheri@live.com>
Date:   Tue Oct 13 07:24:38 2026 +0330

    feat: land the F.28AL native lifetime stack
```

```
 88 files changed, 20799 insertions(+), 787 deletions(-)
```

Subject and body as specified. Full body:

> Land the deferred F.28AL source web as a single commit. The audit established that these files form one interdependent dependency web that cannot be committed granularly without violating dependency closure (the commit-2 defect), and VERIFY-STACK-CLOSURE proved the web compiles as a unit (40/40 tasks executed, zero errors) on the exact bytes committed here.
>
> Scope (88 files: 47 tracked-modified, 41 new) — app/ (84): AutoModeController + its 2164-line test suite; AutoModeProtocol/Models/Engine/TunnelStateWatcher; AutoConnectReachability + ServerReachabilityTester; ServerListUpdater/Updates/UpdateScheduler/ScreenPolicy/DatasetIntegrity; SourceIdentity/SourceValidation/RedirectPolicy/HttpFetcher/VpnGateSources; OwnedVpnDecision/VpnConnectionMapper/GlobalVpnTracker; CollectorSnapshotStore/VpnLastConnectionStore; OpenVpnConfigTransform; ServerListSearchState; schemas/…/5.json; 26 new unit-test files. SoftEtherClient/ (2): SoftEtherVpnService F.28AL service-side lifetime integration; client/SoftEtherClient handle-guard integration. Plus vpnLib/build.gradle, app/build.gradle, .github/workflows/android-ci.yml.
>
> Verification (pre-commit, forced): `:app:compileProDebugKotlin --rerun-tasks` → BUILD SUCCESSFUL in 8m 1s, 40 actionable tasks: 40 executed, zero errors.
>
> No push. No tag.

Largest deltas: `AutoModeController.kt` +1118, `SoftEtherVpnService.kt` +801, `CollectorSnapshotStore.kt` +699 (new), `ServerListUpdater.kt` +430 (new), `AndroidConnectionAdapter.kt` +352, `VpnLastConnectionStore.kt` +353 (new), `ServerReachabilityTester.kt` +379, `SourceValidation.kt` +370 (new), `AutoModeControllerTest.kt` (+1798 within its 426-line stat bucket), `TunnelStateWatcher.kt` +298, `AutoConnectReachability.kt` +286 (new), `DatasetIntegrity.kt` +275 (new), `VpnGateSources.kt` +260.

## 8. I5 — Post-Commit Verification

```
git log -1 --stat | head -100   ->  88 files listed, matching the staged set
git rev-parse HEAD              ->  c5720508a41ee3f46412a108673cae46abfccda0
parent                          ->  98a6462 (GROUP2)
```
**PASS.**

## 9. V1 — Post-Commit Forced Compile

**Attempt 1 — FAILED to start (environment, not code):**
```
* What went wrong:
Gradle could not start your build.
> Could not create service of type FileLockContentionHandler …
   > java.net.BindException: Address already in use: bind
```
This is the recurring Gradle file-lock contention observed in nearly every phase of this session. `./gradlew --stop` was run and reported — as always — **"No Gradle daemons are running"** while the exception persisted.

**Attempt 2 — after `--stop` + 3 s wait, with the established port-0 incantation:**
```
BUILD SUCCESSFUL in 7m 2s
40 actionable tasks: 40 executed
Configuration cache entry reused.
```
**PASS — the committed tree compiles. 40/40 EXECUTED, zero errors, only the pre-existing warning set.**

## 10. V2 — Post-Commit Forced, Cache-Disabled Unit Tests

```
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" CI=1 \
GRADLE_OPTS="…-Dorg.gradle.internal.remote.internal.inet.tcp.port=0" \
./gradlew :app:testProDebugUnitTest --no-daemon --rerun-tasks --no-build-cache
```

Both forcing flags are required — established earlier in this session (GROUP2): `--rerun-tasks` **alone** left `testDebugUnitTest` `UP-TO-DATE`; `--no-build-cache` is what actually forces it.

| Attempt | Result |
|---|---|
| 1 | `SIGTERM` — terminated at the harness level, no build verdict |
| 2 | `BindException: Address already in use: bind` — environment |
| 3 | **BUILD SUCCESSFUL in 10m 35s — 81 actionable tasks: 81 executed** |

### 10a. V2 Result — PASSED

```
BUILD SUCCESSFUL in 10m 35s
81 actionable tasks: 81 executed
Configuration cache entry reused.
```

Fully forced (`--rerun-tasks --no-build-cache`): **81 actionable tasks, 81 executed — zero `UP-TO-DATE`.** `:app:testProDebugUnitTest` executed. Both forcing flags were necessary, confirming the lesson from GROUP2.

**Exact JUnit XML counts** (`app/build/test-results/testProDebugUnitTest/`, aggregated from the root `<testsuite>` attributes of all **44** `TEST-*.xml` files):

| Metric | Value |
|---|---|
| Tests | **602** |
| Failures | **0** |
| Errors | **0** |
| Skipped | 1 |

`grep -rl '<failure\|<error '` over the whole results directory returned **nothing** — there is no failure or error element in any XML. The single skip is a pre-existing platform-conditional `assumeTrue`/`@Ignore`, not a suppression of a failing test.

The two `w:` warnings emitted by the test compile (`RepositoryTest.kt:125:9` and `:156:9` "Expression is unused") are pre-existing warnings in the *test source itself* in this commit, not errors; the build succeeded regardless.

### 10b. Aggregated V2 Status

**V2 PASSED on the third attempt.** Attempts 1 and 2 failed at the environment level (`SIGTERM`, then Gradle `BindException`) and produced **no** test verdict — neither was a `TEST_FAILED`. Attempt 3 ran the full forced, cache-disabled suite on the committed tree:

**602 tests / 0 failures / 0 errors / 1 skipped, 81/81 tasks executed.**

The pre-commit green runs on `ab82ce1` (145 tests, 7/7) and `98a6462` (145 tests, 17/17) are consistent with this and required no mitigation.

## 11. V3 — Tree Cleanliness

```
dirty count:  140  ->  61
staged count: 0
```

The 61 remaining entries are **all deliberately excluded categories**:
* Session reports: `COMMIT_BATCH_F28AL_AUDIT_REPORT.md`, `COMMIT_BATCH_F28AL_GROUP2_REPORT.md`, `COMMIT_BATCH_F28AL_REPAIR_REPORT.md`, `COMMIT_BATCH_P0_EXECUTION_REPORT.md`, `COMMIT_BATCH_P0_REPORT.md`, `VERIFY_STACK_CLOSURE_REPORT.md`
* Scratch/evidence: `_f28al_repro/`, `_f28alv/`, `_f28alv_bug3_verify/`, `_f28alv_verify/`, `_f28aly_backup/`, `_f28aly_diag/`, `_f28aly_full/`, `_staging/`, plus `_f28_test_out.txt`, `_f28aly_build.*`, `_f28aly_prodebug_*`, `_html_probe.bin`, `_pre_commit_set.txt`, `_staging_*`, `_tasks_out.txt`, `_test_err.txt`
* `?? .workbuddy-ai/` (project memory — explicitly excluded)

**No tracked file remains dirty. Nothing is staged.** **PASS.**

## 12. V4–V6 — Push / Tag / Reflog Verification

### V4 — No push
```
origin/main:  5c13603c6276c11460982e0633203667d26ef3e7   (UNCHANGED)
HEAD:         c5720508a41ee3f46412a108673cae46abfccda0
git rev-list --count origin/main..HEAD  ->  10
```
`origin/main` is byte-identical to its value at the start of this session. All ten commits are local-only. **PASS — nothing was pushed.**

### V5 — No tag
```
git tag -l  ->  p2.4.5 p2.4.7 p2.4.8 p2.4.9 p2.4.10
```
Unchanged from baseline; **no tag was created.** **PASS.**

### V6 — Reflog integrity
```
c572050 HEAD@{0}: commit: feat: land the F.28AL native lifetime stack
98a6462 HEAD@{1}: commit: feat(softether): add session policy and TUN ownership coordinators
ab82ce1 HEAD@{2}: commit: fix(native): commit the missing F28AL-U classes (repairs 49f579b)
8424afa HEAD@{3}: commit: docs(reports): session audit and verification trail
0688b51 HEAD@{4}: commit: test(androidTest): expose native bounds tests to the Kotlin bridge
ac2764a HEAD@{5}: commit: build(ksp): align KSP version with Kotlin
e1132ad HEAD@{6}: commit: build(native): exclude test library from release build
49f579b HEAD@{7}: commit: fix(android): route reconnect failure through service onError; name teardown identity
b689cfe HEAD@{8}: commit: fix(native): validate JNI array lengths and release RUDP lock (P0 bounds)
0714628 HEAD@{9}: commit: feat(ui): add DNS presets, app exception icons and Auto Mode cleanup
5c13603 HEAD@{10}: checkout: moving from main to main
5c13603 HEAD@{11}: commit: chore(release): prepare version 2.4.9
```
Every entry is a `commit:`. **No `reset`, no `rebase`, no `cherry-pick`, no `revert`, no `merge`, no `stash`.** The only non-commit entry is a `checkout` that stayed on `main`. The chain is linear, appended, and exactly as expected. **PASS.**

## 13. Deviations, Anomalies, and Lessons

| # | Item | Detail |
|---|---|---|
| D-1 | **Commit-set size deviation** | Brief predicted ~100 files; actual **88** (47 + 41). Cause: `SoftEtherClient` classes/tests were already committed in `ab82ce1` + `98a6462`. Reported prominently in §2. |
| D-2 | **`app/schemas/.../5.json` inclusion ruled** | Open since the audit. The brief's I2 included `*/schemas/*.json`; the audit concurred. **Committed.** Open question closed. |
| D-3 | **V2 required three attempts** | Attempts 1–2 failed at the environment level (`SIGTERM`, then `BindException`). Attempt 3 succeeded: **602 tests / 0 failures / 0 errors / 1 skipped**, 81/81 tasks executed. Not a test failure at any point. |
| D-4 | **`BindException` recurrence** | Presented in C1 the very first time V1 was attempted post-commit. `--stop` again reported "No Gradle daemons are running" while the exception persisted — the identical, now well-documented behaviour. The `tcp.port=0` incantation resolved it. |
| L-1 | **`--rerun-tasks` is not always sufficient** | Reconfirmed. `--no-build-cache` must accompany it. |
| L-2 | **Forced-execute counts are the only meaningful compile evidence** | `40 actionable tasks: 40 executed` (C1, V1) proves genuine recompilation; `UP-TO-DATE` proves nothing. |

## 14. Verdict, Recommendation, and Final Rule Compliance

### Verdict
**COMMITTED — COMPILE VERIFIED — TESTS VERIFIED.**

The F.28AL stack is landed as a single local commit `c5720508a41ee3f46412a108673cae46abfccda0` (88 files, +20 799/−787). The dependency-closure defect that broke commit 2 is structurally impossible here: the commit contains the complete web, and the forced compile of the committed tree succeeded with 40/40 tasks executed.

### What is proven
* ✅ HEAD, mtime validity, and closure re-check all passed before staging.
* ✅ 88 files staged by explicit path only; zero `git add -A`/`git add .`; exclusion scan empty.
* ✅ Commit created with the specified subject/body.
* ✅ **Committed tree compiles** (V1 forced: 40/40 executed, zero errors).
* ✅ **Committed tree's unit tests pass** (V2 forced, cache-disabled: 81/81 executed; **602 tests / 0 failures / 0 errors / 1 skipped**).
* ✅ Tree clean (61 deliberate exclusions, 0 staged).
* ✅ No push (`origin/main` unchanged), no tag, reflog pure.

### What is not proven
* ❌ **The `proRelease` build path** — only `proDebug` variants have been compiled and tested in this session. The earlier `:app:assembleProRelease` build was cancelled by the user (BATCH-DEVICE-VERIFY). This remains an open verification item, unchanged by this phase.
* ❌ **Physical-device behaviour** — blocked; the device is not enumerating (see below).

### Recommendation
**`PROCEED TO FIX-NATIVE-LIFETIME-SYNC (N-15 + N-16)`** — the highest-value unblocked source fix; needs no device and no further commit authorization.
* **`PROCEED TO CONTINUE DEEP REVIEW FIXES`** — `FIX-S06-API-TRAP`, `FIX-A11Y-U32..U42`, `RUNTIME-UNCLEAR-CLOSURE` (N-18, A-43, A-44).
* **`PROCEED TO DEVICE VERIFICATION`** — still **blocked physically**: device `5585fecc` / `2201117TG` is not enumerating (`Unknown Redmi Note 11` ×2 + `Unknown USB Device (Device Descriptor Request Failed)` ×2; `adb devices` empty). Native tests 31–36 remain unexecuted.
* **`PROCEED TO Z1-IMPL`** — server-source dynamic retrieval.

### Final rule compliance
> *"One commit. Forced compile + forced cache-disabled tests. No push. No tag. Do NOT touch anything outside the F.28AL stack. Report in English only."*

* **One commit** — ✅ exactly one (`c572050`).
* **Forced compile** — ✅ run twice (C1 pre-staging, V1 post-commit), both 40/40 executed.
* **Forced cache-disabled tests** — ✅ run once to completion (V2 attempt 3): 81/81 executed, **602 tests / 0 failures / 0 errors / 1 skipped**.
* **No push** — ✅ verified three ways (§12 V4).
* **No tag** — ✅ verified (§12 V5).
* **Nothing outside the F.28AL stack touched** — ✅ 88 files, all F.28AL; no source/test/Gradle/CMake edit was made in this phase.
* **Report in English only** — ✅
