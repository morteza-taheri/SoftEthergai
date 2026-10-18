# COMMIT-BATCH-F28AL-REPAIR — Repair Report

**Phase:** COMMIT-BATCH-F28AL-REPAIR — Repair the Broken Commit 2 Dependency
**Date:** 2026-10-12
**Mode:** One commit + compile/test validation. No push, no tag, no source modification.
**Verdict:** **REPAIRED — COMPILE VERIFIED**

---

### 1. Executive Verdict

**REPAIRED — COMPILE VERIFIED**

The defect is closed. Commit `ab82ce1` adds the four missing F28AL-U production classes and their
four test suites (8 files, 2 918 insertions). The only import in the eight files that pointed at
an uncommitted class was `NativeLifetimeRegistry` — which is itself in the eight. A **forced**
(not cached) recompile of `:SoftEtherClient:compileDebugKotlin` succeeded, and the full
`testDebugUnitTest` suite passed 145/145. Every `vn.unlimit.softether.*` import in every committed
`.kt` file now resolves inside HEAD.

---

### 2. Pre-Repair Defect Confirmation (P2/P3)

**P1 — HEAD:** `8424afa2fd77fe8c2f893e3dc8904c8122f64864` ✅

**P2 — the two classes absent from HEAD's tree:**
```
$ git ls-tree HEAD -- SoftEtherClient/src/main/java/vn/unlimit/softether/NativeLifetimeRegistry.kt
(empty)

$ git ls-tree HEAD -- SoftEtherClient/src/main/java/vn/unlimit/softether/TeardownCompletion.kt
(empty)
```
Both empty → neither file was in the tree. **Defect confirmed.**

**P3 — `ConnectionController.kt` (committed) imports and uses them:**
```
$ git show HEAD:.../controller/ConnectionController.kt | grep -nE "NativeLifetimeRegistry|TeardownCompletion"
16:import vn.unlimit.softether.NativeLifetimeRegistry
18:import vn.unlimit.softether.TeardownCompletion
67:    private val nativeLease: NativeLifetimeRegistry.Lease? = null,
131:            if (NativeLifetimeRegistry.process.confirmTeardown(nativeLease)) {
409:        NativeLifetimeRegistry.process.markNativeEntered(nativeLease)
710:        NativeLifetimeRegistry.process.markConnected(nativeLease)
823:    fun destroyResources(): TeardownCompletion.Outcome {
835:        NativeLifetimeRegistry.process.markTeardownRequested(nativeLease)
913:        // (NativeLifetimeRegistry.Lease.attemptId, :115); ...
916:        // counter as TeardownCompletion.attemptId — the service's ...
930:                NativeLifetimeRegistry.process.markDeadlineExpired(nativeLease)
944:                TeardownCompletion.Outcome.NATIVE_CONFIRMED
947:                TeardownCompletion.Outcome.NATIVE_UNRESOLVED
1378:            NativeLifetimeRegistry.process.markConnected(nativeLease)
```
**2 import lines + 12 usage sites.** Brought in by commit 2's (`49f579b`) 571-line delta; the
pre-existing `0714628` version did not reference these classes at all. **A clean checkout of
`8424afa` could not compile the `SoftEtherClient` module.**

**P4 — all eight files untracked (`??`):** confirmed, count = **8** via
`git status --short --untracked-files=all`.

---

### 3. Self-Containment Check (P5)

**Every `vn.unlimit.softether.*` import in the eight files:**

| File | `vn.unlimit.softether.*` imports | Resolves to |
|---|---|---|
| `NativeLifetimeRegistry.kt` | *(none — no intra-module imports)* | — |
| `TeardownCompletion.kt` | *(none)* | — |
| `NativeAttemptCoordinator.kt` | *(none)* | — |
| `NativeHandleSlot.kt` | *(none)* | — |
| `NativeLifetimeRegistryTest.kt` | *(none)* | — |
| `NativeAttemptCoordinatorTest.kt` | `NativeLifetimeRegistry` | **in the 8-file set** ✅ |
| `NativeHandleSingleOwnerTest.kt` | `NativeLifetimeRegistry` | **in the 8-file set** ✅ |
| `SoftEtherClientHandleGuardTest.kt` | *(none)* | — |

**Only two cross-file dependencies exist, both on `NativeLifetimeRegistry`, which is being
committed.** The four production classes import no other `vn.unlimit.softether` class — they are
dependency leaves.

`SoftEtherClientHandleGuardTest.kt` was checked separately: it is a **pure source-shape test**
(reads `SoftEtherClient.kt` from disk with `File(...)` and asserts the handle-guard shape), has
**zero** imports of the F28AL-U cluster, and references only `SoftEtherClient` (tracked in HEAD).

**Explicit statement: no external dependency remains outstanding.** No `BROKEN_DEPENDENCY`.

---

### 4. Commit Made

```
commit ab82ce118ebd1decf978f7c3e4c2086d9c38ad2a
Author: morteza.taheri <morteza.taheri@live.com>
Date:   Mon Oct 12 23:04:07 2026 +0330

    fix(native): commit the missing F28AL-U classes (repairs 49f579b)
```

`git log -1 --stat`:
```
 .../vn/unlimit/softether/NativeLifetimeRegistry.kt | 276 +++++++
 .../vn/unlimit/softether/TeardownCompletion.kt     | 115 +++
 .../controller/NativeAttemptCoordinator.kt         | 403 ++++++++++
 .../softether/controller/NativeHandleSlot.kt       | 153 ++++
 .../softether/NativeLifetimeRegistryTest.kt        | 431 ++++++++++
 .../client/SoftEtherClientHandleGuardTest.kt       | 212 +++++
 .../controller/NativeAttemptCoordinatorTest.kt     | 891 +++++++++++++++++++++
 .../controller/NativeHandleSingleOwnerTest.kt      | 437 ++++++++++
 8 files changed, 2918 insertions(+)
```

**Exact file count: 8.** ✅ Matches the authorized list precisely — no other file staged or
committed. All eight appear as `create mode 100644`.

---

### 5. Compile Result (V1)

**First attempt hit the known Gradle infrastructure failure:**
```
> Could not create service of type FileLockContentionHandler ...
   > java.net.BindException: Address already in use: bind
```
This is the recurring file-lock contention defect, **not** a code error. Resolved by
`./gradlew --stop` (which reported "No Gradle daemons are running" — the known unreliable signal)
followed by the `tcp.port=0` incantation.

**Second attempt — BUILD SUCCESSFUL, but inconclusive:**
```
> Task :SoftEtherClient:compileDebugKotlin UP-TO-DATE
BUILD SUCCESSFUL in 26s
```
`UP-TO-DATE` means Gradle **did not recompile**. A stale cache could have masked the defect, so
this run alone does not prove anything.

**Third attempt — `--rerun-tasks`, conclusive:**
```
$ JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" CI=1 \
  GRADLE_OPTS="-Dorg.gradle.internal.launcher.localhost=127.0.0.1 \
    -Dorg.gradle.internal.remote.internal.inet.tcp.port=0" \
  ./gradlew :SoftEtherClient:compileDebugKotlin --no-daemon --rerun-tasks

> Task :SoftEtherClient:compileDebugKotlin
w: .../SoftEtherVpnService.kt:466:38 Unnecessary safe call on a non-null receiver of type 'Intent'.
w: .../controller/NativeHandleSlot.kt:62:34 This class is not recommended for use in Kotlin. Use 'kotlin.Any' instead.

BUILD SUCCESSFUL in 1m 35s
7 actionable tasks: 7 executed
```

**Exit status: 0. `BUILD SUCCESSFUL`.** All 7 tasks **executed** (none up-to-date) — the Kotlin
compiler genuinely processed every source file with the eight new files present. Two warnings,
both benign and neither related to the repair.

**Conclusion: the defect is closed and the module compiles.**

---

### 6. Unit Test Result (V2)

```
$ JAVA_HOME=... CI=1 GRADLE_OPTS="...tcp.port=0" \
  ./gradlew :SoftEtherClient:testDebugUnitTest --no-daemon

> Task :SoftEtherClient:compileDebugUnitTestKotlin
> Task :SoftEtherClient:testDebugUnitTest
BUILD SUCCESSFUL in 1m 36s
```

**Exit status: 0.** From the JUnit XML reports:

| Test class | Tests | Fail | Err | Skip |
|---|---|---|---|---|
| `vn.unlimit.softether.NativeLifetimeRegistryTest` | 22 | 0 | 0 | 0 |
| `vn.unlimit.softether.SoftEtherSessionPolicyTest` | 54 | 0 | 0 | 0 |
| `vn.unlimit.softether.TunOwnershipCoordinatorTest` | 25 | 0 | 0 | 0 |
| `vn.unlimit.softether.client.SoftEtherClientHandleGuardTest` | 4 | 0 | 0 | 0 |
| `vn.unlimit.softether.controller.NativeAttemptCoordinatorTest` | 20 | 0 | 0 | 0 |
| `vn.unlimit.softether.controller.NativeHandleSingleOwnerTest` | 8 | 0 | 0 | 0 |
| `vn.unlimit.softether.controller.ReconnectTerminalPathTest` | 7 | 0 | 0 | 0 |
| `vn.unlimit.softether.controller.TeardownLogIdentityTest` | 5 | 0 | 0 | 0 |
| **TOTAL** | **145** | **0** | **0** | **0** |

**All four newly-committed test suites ran and passed** (22 + 20 + 8 + 4 = 54 new tests).
Total 145/145 matches the PHASE C baseline exactly — **no regression**.

---

### 7. Defect-Closed Verification (V3)

```
$ git ls-tree HEAD -- .../softether/NativeLifetimeRegistry.kt
100644 blob 261cfc825c5123e32590c3f51e8d79ac95271973  .../NativeLifetimeRegistry.kt

$ git ls-tree HEAD -- .../softether/TeardownCompletion.kt
100644 blob d29f5a9c197e707f9ececa8aac744f5a71389e9b  .../TeardownCompletion.kt
```

Both now return blob hashes (previously empty). The other two classes likewise:
```
100644 blob dd4927ffd496a8ec283f4e88b62cf20bb7f72aac  .../controller/NativeAttemptCoordinator.kt
100644 blob 7119594bf0c829433c1d4f3c7b085bf65656243d  .../controller/NativeHandleSlot.kt
```

**Independent closure proof** — every `vn.unlimit.softether` import in every committed `.kt`:
```
$ git grep -h -E "^import vn\.unlimit\.softether\.[A-Za-z]+$" HEAD -- "*.kt" | sort -u \
    | (check each against git ls-tree HEAD)
  OK      vn.unlimit.softether.NativeLifetimeRegistry
  OK      vn.unlimit.softether.SoftEtherTrafficSnapshot
  OK      vn.unlimit.softether.SoftEtherVpnService
  OK      vn.unlimit.softether.TeardownCompletion
```
**All resolve. Zero missing.** The import closure for the committed `SoftEtherClient` sources is
now complete.

---

### 8. Post-Repair State

| Item | Value |
|---|---|
| New HEAD | `ab82ce118ebd1decf978f7c3e4c2086d9c38ad2a` |
| Previous HEAD | `8424afa` |
| Dirty count | **140** (was 148 before staging; −8 as expected) |
| Tracked-modified remaining | 47 (unchanged — correctly untouched) |
| Index | clean (0 staged) |

```
ab82ce1 fix(native): commit the missing F28AL-U classes (repairs 49f579b)
8424afa docs(reports): session audit and verification trail
0688b51 test(androidTest): expose native bounds tests to the Kotlin bridge
```

---

### 9. Push Verification

**NO PUSH. NO TAG.**

1. `git rev-parse origin/main` → **`5c13603c6276c11460982e0633203667d26ef3e7`** — **unchanged**.
2. `git log origin/main..HEAD --oneline | wc -l` → **8** — all local-only.
3. `git reflog -3` shows **only `commit:` entries** — no push, no tag, no reset.
4. `git tag` → `p2.4.5, p2.4.7, p2.4.8, p2.4.9, p2.4.10` — the same 5 pre-existing tags; **none created**.

---

### 10. Files Modified / Committed

**Committed — exactly the eight authorized files:**
```
SoftEtherClient/src/main/java/vn/unlimit/softether/NativeLifetimeRegistry.kt
SoftEtherClient/src/main/java/vn/unlimit/softether/TeardownCompletion.kt
SoftEtherClient/src/main/java/vn/unlimit/softether/controller/NativeAttemptCoordinator.kt
SoftEtherClient/src/main/java/vn/unlimit/softether/controller/NativeHandleSlot.kt
SoftEtherClient/src/test/java/vn/unlimit/softether/NativeLifetimeRegistryTest.kt
SoftEtherClient/src/test/java/vn/unlimit/softether/controller/NativeAttemptCoordinatorTest.kt
SoftEtherClient/src/test/java/vn/unlimit/softether/controller/NativeHandleSingleOwnerTest.kt
SoftEtherClient/src/test/java/vn/unlimit/softether/client/SoftEtherClientHandleGuardTest.kt
```
**No other file was staged or committed.** No `app/`, no Gradle, no schema, no `CMakeLists.txt`.

---

### 11. Evidence Taxonomy

**SOURCE CODE EVIDENCE (directly observed):**
- `git ls-tree HEAD` before: both files empty; after: blob hashes `261cfc82…` / `d29f5a9c…`.
- `git show HEAD:ConnectionController.kt | grep -n` → 2 imports + 12 usage sites.
- `git log -1 --stat` → exactly 8 files, 2 918 insertions.
- `git grep` closure check → 4/4 `vn.unlimit.softether` imports resolve.
- `--rerun-tasks` compile: `BUILD SUCCESSFUL in 1m 35s`, 7/7 tasks **executed**.
- JUnit XML: 145 tests, 0 failures, 0 errors, 0 skipped across 8 classes.
- `git rev-parse origin/main` = `5c13603…`; `git reflog` = 3 `commit:` entries only.
- Dirty count 148 → 140.

**INFERENCE (reasoned):**
- That the pre-repair tree was genuinely broken until now — **inferred**. The compile was run only
  *after* the commit, so it proves the current state compiles; it does not *directly* demonstrate
  the pre-commit failure. The absence of the two files from `git ls-tree HEAD` plus their presence
  in the committed import lines is what establishes the pre-commit breakage. (The `--rerun-tasks`
  run is what makes the *post*-fix claim strong, since nothing was cached.)
- That no *other* uncommitted dependency lurks outside `vn.unlimit.softether` — inferred from the
  successful compile, which would have failed on any missing symbol.

**UNKNOWN:**
- Whether `app/` compiles. Only `:SoftEtherClient` was compiled, per the phase brief. The 47
  modified + remaining untracked `app/` files still form the deferred web (audit §8).
- Whether the native tests (N-01…N-08) pass on hardware — device still unavailable.
- Whether `assembleProDebug` succeeds (not run; forbidden).

---

### 12. Explicit No-Change Confirmation

| Item | Status |
|---|---|
| Source modified | **NONE** |
| Tests modified | **NONE** |
| Gradle / native modified | **NONE** |
| Device | **NONE** |
| APK built | **NONE** |
| CI triggered | **NONE** |
| Pushed | **NONE** |
| Tags created | **NONE** |
| Files committed | **exactly the eight authorized** |
| Files modified by this phase | **NONE** — the commit records prior working-tree state |

**Rule compliance:** no `git add -A` / `git add .` (eight explicit-path adds); no
`reset`/`clean`/`stash`/`checkout`/`restore`; no push; no tag. The only filesystem writes were
build outputs (`SoftEtherClient/build/**`) and the report below — no source or config file.

---

### 13. Final Recommendation

**PROCEED TO COMMIT-BATCH-F28AL-GROUP2** (SoftEtherSessionPolicy + TunOwnershipCoordinator)

That group is the natural next step: it is 4 files, it is already verified FINISHED by the audit
(54 + 25 tests, zero TODOs), and — unlike Group 1 — it has **no committed file depending on it**
(`git grep HEAD` → 0 refs), so it carries no repair obligation and can be validated the same way
(compile + test).

**Important caveat for every subsequent group:** the audit's §8 caveat still applies. Groups 2–5
are only independently commit-safe if their **dirty tracked peers** travel with them
(`SoftEtherVpnService.kt` for Group 2; `VpnServerRepository.kt`, `DataUtil.kt`, `HomeScreen.kt`,
`ConnectionListViewModel.kt`, `AutoModeEngine.kt` for Groups 3–4). Otherwise the split collapses
into the single `feat: land the F.28AL stack` commit the audit recommended as the correctness-first
option. Each group should be validated with the same **forced** (`--rerun-tasks`) compile this
phase used — a cached `UP-TO-DATE` compile proves nothing.

**Alternatives:** `PROCEED TO COMMIT-BATCH-F28AL` (single large commit per audit §8 caveat 1),
`REPAIR NEEDS AUDIT`, or `WAIT FOR EXPLICIT USER DECISION`.

**Blocked:** device verification — `5585fecc` still not enumerating.

---

**FINAL RULE COMPLIANCE:** Exactly the eight files committed. Validated with compile + tests.
No push. No tag. Nothing else touched. English only.
