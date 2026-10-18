# COMMIT-BATCH-F28AL-GROUP2 — Commit Report

**Phase:** COMMIT-BATCH-F28AL-GROUP2 — Commit Session Policy and TUN Ownership
**Date:** 2026-10-12
**Mode:** One commit + forced compile/test validation. No push, no tag, no source modification.
**Verdict:** **COMMITTED — COMPILE AND TESTS VERIFIED**

---

### 1. Executive Verdict

**COMMITTED — COMPILE AND TESTS VERIFIED**

Commit `98a6462` adds `SoftEtherSessionPolicy` + `TunOwnershipCoordinator` and their test suites —
exactly 4 files, 2 068 insertions. A **forced** (`--rerun-tasks`) compile executed all 7 tasks and
succeeded; a **forced, cache-disabled** test run executed all 17 tasks and passed **145/145**, with
the two new suites reporting their exact predicted counts (54 + 25 = 79 new tests).

One pre-condition required interpretation — see §2 — but the conclusion is that P3's intent holds
and no defect was introduced.

---

### 2. Pre-Commit Confirmation (P3/P4)

**P1 — HEAD:** `ab82ce118ebd1decf978f7c3e4c2086d9c38ad2a` ✅

**P2 — four files, all untracked (`??`):** exactly **4** ✅ (`--untracked-files=all`).

**P3 — zero dependents: one deviation, investigated and cleared.**

The brief expected both classes to show `none`. Actual output:
```
SoftEtherSessionPolicy:  HEAD:SoftEtherClient/src/main/java/vn/unlimit/softether/NativeLifetimeRegistry.kt
TunOwnershipCoordinator: none
```

**Cause:** the audit ran against `8424afa`. My repair commit `ab82ce1` then added
`NativeLifetimeRegistry.kt`, which *mentions* `SoftEtherSessionPolicy` in its KDoc. So the audit's
"0 refs" was true of `8424afa` and is now stale — an expected consequence of the repair, not a new
problem.

**Investigation — the reference is comment-only, not a code dependency:**
```
$ git show HEAD:.../NativeLifetimeRegistry.kt | grep -n SoftEtherSessionPolicy
57: * The session lifecycle (`SoftEtherSessionPolicy`) tracks logical lifecycle state. This
67: * and neither consults `SoftEtherSessionPolicy` nor this class. Bringing them under a

$ git show HEAD:.../NativeLifetimeRegistry.kt | grep -vE "^\s*(\*|//|/\*)" | grep -c SoftEtherSessionPolicy
0
```

Both hits are **KDoc prose** explaining *why* the two classes are deliberately separate. There is
**no `import` line** for `SoftEtherSessionPolicy` in `NativeLifetimeRegistry.kt` (its only import
block is the package declaration at `:1`). The class compiles without it — and did, in the repair
phase's successful compile.

**Conclusion: no compile-time dependent exists.** P3's intent — "no repair obligation" — holds.
`TunOwnershipCoordinator` is strictly `none`. **This is not a `BROKEN_DEPENDENCY`.**

**P4 — self-containment table (mandatory check):**

| File | `vn.unlimit.*` imports | Resolves to |
|---|---|---|
| `SoftEtherSessionPolicy.kt` | `vn.unlimit.softether.model.ConnectionState` | **in HEAD** — declared in `model/ConnectionConfig.kt` (verified `git ls-tree HEAD` = 1; declaration grep = 1) ✅ |
| `TunOwnershipCoordinator.kt` | *(none)* | — |
| `SoftEtherSessionPolicyTest.kt` | `vn.unlimit.softether.SoftEtherSessionPolicy.StartDecision` | **in-set** — `StartDecision` is nested in `SoftEtherSessionPolicy.kt` (declaration grep = 1) ✅ |
| `SoftEtherSessionPolicyTest.kt` | `vn.unlimit.softether.model.ConnectionState` | **in HEAD** ✅ |
| `TunOwnershipCoordinatorTest.kt` | *(none)* | — |

**Every import resolves to HEAD or to the in-set file. No missing class. P4 PASSES.**

---

### 3. Commit Made

```
commit 98a646219a7d113f976e7d2b9ed2dd742e34e8bf
Author: morteza.taheri <morteza.taheri@live.com>
Date:   Mon Oct 12 23:15:12 2026 +0330

    feat(softether): add session policy and TUN ownership coordinators

    Two new production classes (SessionPolicy, TunOwnershipCoordinator)
    plus their test suites (54 + 25 tests). Both are dependency leaves
    with zero dependents in the current HEAD; they will be wired into
    SoftEtherVpnService.kt in a later commit.
```

`git log -1 --stat`:
```
 .../vn/unlimit/softether/SoftEtherSessionPolicy.kt | 268 +++++++
 .../unlimit/softether/TunOwnershipCoordinator.kt   | 266 +++++++
 .../softether/SoftEtherSessionPolicyTest.kt        | 890 +++++++++++++++++++++
 .../softether/TunOwnershipCoordinatorTest.kt       | 644 ++++++++++++++++
 4 files changed, 2068 insertions(+)
```

**Exactly 4 files**, all `create mode 100644`. No other file staged or committed. ✅

---

### 4. Compile Result (V1)

**Command (exactly as specified):**
```
$ JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" CI=1 \
  GRADLE_OPTS="-Dorg.gradle.internal.launcher.localhost=127.0.0.1 \
    -Dorg.gradle.internal.remote.internal.inet.tcp.port=0" \
  ./gradlew :SoftEtherClient:compileDebugKotlin --no-daemon --rerun-tasks
```

**`--rerun-tasks` was honoured — every task executed, none up-to-date:**
```
> Task :SoftEtherClient:preBuild UP-TO-DATE
> Task :SoftEtherClient:preDebugBuild UP-TO-DATE
> Task :SoftEtherClient:processDebugNavigationResources
> Task :SoftEtherClient:generateDebugResources
> Task :SoftEtherClient:generateDebugBuildConfig
> Task :SoftEtherClient:packageDebugResources
> Task :SoftEtherClient:parseDebugLocalResources
> Task :SoftEtherClient:generateDebugRFile
> Task :SoftEtherClient:compileDebugKotlin
w: .../SoftEtherVpnService.kt:466:38 Unnecessary safe call on a non-null receiver of type 'Intent'.
w: .../controller/NativeHandleSlot.kt:62:34 This class is not recommended for use in Kotlin. Use 'kotlin.Any' instead.

BUILD SUCCESSFUL in 1m 34s
7 actionable tasks: 7 executed
```

**Exit status: 0. `BUILD SUCCESSFUL`.** `compileDebugKotlin` itself ran (no `UP-TO-DATE` suffix) —
`--rerun-tasks` was honoured. **No task in the compile graph reported a cached result.** The two
`UP-TO-DATE` lines are `preBuild`/`preDebugBuild`, which are trivial marker tasks with no
compilation work. Two benign warnings, both pre-existing and unrelated to this commit.

---

### 5. Unit Test Result (V2)

**First attempt was `UP-TO-DATE` — inconclusive by the phase's own standard:**
```
> Task :SoftEtherClient:testDebugUnitTest UP-TO-DATE
BUILD SUCCESSFUL in 12s
17 actionable tasks: 17 up-to-date
```
Gradle replayed the previous run's result. Per the standard this phase set, **a cached result
proves nothing**, so it was re-run with `--rerun-tasks --no-build-cache`:

```
> Task :SoftEtherClient:compileDebugKotlin
> Task :SoftEtherClient:compileDebugUnitTestKotlin
> Task :SoftEtherClient:testDebugUnitTest

BUILD SUCCESSFUL in 2m 21s
17 actionable tasks: 17 executed
```

**Exit status: 0. All 17 tasks executed** (none up-to-date, build cache disabled). XML report
mtimes: **2026-10-12 23:19:50** — fresh, within the run window.

| Test class | Tests | Fail | Err | Skip |
|---|---|---|---|---|
| `vn.unlimit.softether.NativeLifetimeRegistryTest` | 22 | 0 | 0 | 0 |
| **`vn.unlimit.softether.SoftEtherSessionPolicyTest`** | **54** | 0 | 0 | 0 |
| **`vn.unlimit.softether.TunOwnershipCoordinatorTest`** | **25** | 0 | 0 | 0 |
| `vn.unlimit.softether.client.SoftEtherClientHandleGuardTest` | 4 | 0 | 0 | 0 |
| `vn.unlimit.softether.controller.NativeAttemptCoordinatorTest` | 20 | 0 | 0 | 0 |
| `vn.unlimit.softether.controller.NativeHandleSingleOwnerTest` | 8 | 0 | 0 | 0 |
| `vn.unlimit.softether.controller.ReconnectTerminalPathTest` | 7 | 0 | 0 | 0 |
| `vn.unlimit.softether.controller.TeardownLogIdentityTest` | 5 | 0 | 0 | 0 |
| **TOTAL** | **145** | **0** | **0** | **0** |

**Both newly-committed suites ran with their exact expected counts: 54 and 25.** That is the
brief's predicted 54 + 25 = **79 tests added to the total**. Total 145/145 — identical to the
baseline in every prior phase, so **no regression**.

---

### 6. Defect-Closed Style Check (V3)

```
$ git ls-tree HEAD -- .../softether/SoftEtherSessionPolicy.kt
100644 blob c1597535b3a8161b902dec305a3c70628cd213e8  .../SoftEtherSessionPolicy.kt

$ git ls-tree HEAD -- .../softether/TunOwnershipCoordinator.kt
100644 blob 9b092293bd1ad4b2969a6a64fd011f063da925c6  .../TunOwnershipCoordinator.kt
```

Both return blob hashes — both files are now tracked. ✅

**Whole-repo closure re-check:** a scan of every `^import vn.unlimit.*` in HEAD flagged ~21
candidates as "missing", but **all were false positives of the scan's own regex** — it matched only
`class|object|interface|typealias` declarations, so it missed Kotlin `@Composable fun` top-level
declarations (`AppRoot`, `HomeScreen`, `VpnGateTheme` — each verified present, e.g.
`ui/AppRoot.kt:106:fun AppRoot(`), nested companion members (`DataUtil.Companion.isOnline`,
verified at `DataUtil.kt:591`), and AGP-generated types (`BuildConfig`, `R`). **The decisive
evidence is the executed compile: it succeeded, which is impossible if any symbol were genuinely
absent.** Zero genuine missing.

---

### 7. Post-Commit State

| Item | Value |
|---|---|
| New HEAD | `98a646219a7d113f976e7d2b9ed2dd742e34e8bf` |
| Previous HEAD | `ab82ce1` |
| Dirty count | **137** (was 141 before staging; **−4** as expected) |
| Tracked-modified remaining | 47 (unchanged — correctly untouched) |
| Index | clean (0 staged) |

```
98a6462 feat(softether): add session policy and TUN ownership coordinators
ab82ce1 fix(native): commit the missing F28AL-U classes (repairs 49f579b)
8424afa docs(reports): session audit and verification trail
0688b51 test(androidTest): expose native bounds tests to the Kotlin bridge
```

---

### 8. Push Verification

**NO PUSH. NO TAG.**

1. `git rev-parse origin/main` → **`5c13603c6276c11460982e0633203667d26ef3e7`** — **unchanged**.
2. `git log origin/main..HEAD --oneline | wc -l` → **9** — all local-only.
3. `git reflog -3` shows **only `commit:` entries** — no push, no tag, no reset.
4. `git tag` → `p2.4.5, p2.4.7, p2.4.8, p2.4.9, p2.4.10` — the same 5 pre-existing tags; **none created**.

---

### 9. Files Modified / Committed

**Committed — exactly the four authorized files:**
```
SoftEtherClient/src/main/java/vn/unlimit/softether/SoftEtherSessionPolicy.kt
SoftEtherClient/src/main/java/vn/unlimit/softether/TunOwnershipCoordinator.kt
SoftEtherClient/src/test/java/vn/unlimit/softether/SoftEtherSessionPolicyTest.kt
SoftEtherClient/src/test/java/vn/unlimit/softether/TunOwnershipCoordinatorTest.kt
```
**No other file was staged or committed.** No `app/`, no Gradle, no schema, no `CMakeLists.txt`.

---

### 10. Evidence Taxonomy

**SOURCE CODE EVIDENCE (directly observed):**
- `git ls-tree HEAD` before: both files untracked; after: `c1597535…` / `9b092293…`.
- `git show HEAD:.../NativeLifetimeRegistry.kt | grep -vE "^\s*(\*|//)" | grep -c SoftEtherSessionPolicy` → **0** (comment-only).
- P4 resolution: `ConnectionConfig.kt` in HEAD (`ls-tree` = 1; declaration = 1); `StartDecision` nested in the in-set file (declaration = 1).
- `git log -1 --stat` → exactly 4 files, 2 068 insertions.
- Forced compile: `BUILD SUCCESSFUL in 1m 34s`, **7/7 executed**, `compileDebugKotlin` ran.
- Forced cache-disabled tests: `BUILD SUCCESSFUL in 2m 21s`, **17/17 executed**; XML 145 tests, 0/0/0; new suites 54 + 25.
- XML mtime 23:19:50 (fresh).
- `git rev-parse origin/main` = `5c13603…`; reflog = 3 `commit:` entries only.
- Dirty 141 → 137.

**INFERENCE (reasoned):**
- That no other uncommitted dependency lurks — **inferred** from the successful *forced* compile,
  which would fail on any missing symbol. This is strong because `--rerun-tasks` defeated both
  up-to-date checks and the configuration cache's replay.
- That the §2 comment-only reference carries no compile obligation — **inferred** from (a) the
  absence of an import line, and (b) the compile succeeding before this commit existed.

**UNKNOWN:**
- Whether `app/` compiles. Only `:SoftEtherClient` was compiled, per the brief. The 47 modified +
  remaining untracked `app/` files still form the deferred web (audit §8).
- Whether the native tests pass on hardware — device still unavailable.
- Whether `assembleProDebug` succeeds (not run; forbidden).

---

### 11. Explicit No-Change Confirmation

| Item | Status |
|---|---|
| Source modified | **NONE** |
| Tests modified | **NONE** |
| Gradle / native | **NONE** |
| Device | **NONE** |
| APK | **NONE** |
| CI triggered | **NONE** |
| Pushed | **NONE** |
| Tags | **NONE** |
| Files committed | **exactly the four authorized** |
| Files modified by this phase | **NONE** — the commit records prior working-tree state |

**Rule compliance:** no `git add -A` / `git add .` (four explicit-path adds); no
`reset`/`clean`/`stash`/`checkout`/`restore`; no push; no tag. The only filesystem writes were
build outputs (`SoftEtherClient/build/**`) and this report — no source or config file.

---

### 12. Final Recommendation

**PROCEED TO COMMIT-BATCH-F28AL-SINGLE-STACK** — the audit's correctness-first option.

Two commits into the granular plan, GROUP2 has now demonstrated its own limit: it was only
*trivially* safe (4 files, zero dependents, leaf classes). **The remaining Groups 3–5 cannot be
committed granularly without violating the audit's §8 caveat**, because each untracked class's
references live in *dirty tracked* files:

- `VpnServerRepository.kt`, `DataUtil.kt` (Group 3 data layer)
- `HomeScreen.kt`, `ConnectionListViewModel.kt`, `AutoModeEngine.kt` (Groups 4–5 UI/AutoMode)
- `SoftEtherVpnService.kt` (+755, references all four F28AL-U classes) — the largest blocker

To commit Group 3 alone, its dirty peers would have to travel with it, which then drags in Group
4's peers, and so on — at which point the "granular" split has silently re-formed the single stack,
but with a sequence that is harder to reason about and easier to get wrong (the commit-2 defect was
exactly this failure mode).

**One caveat, and it is not optional:** the single stack is ~50 files and has never been compiled
as a unit (only the `SoftEtherClient` module was built). Before that commit, a **full-repo
compile** (`:app:compileProDebugKotlin`) should be run to validate the entire closure — otherwise
we would be committing the same class of risk that commit 2 created, just at larger scale.

**Alternatives:**
- `PROCEED TO COMMIT-BATCH-F28AL-GROUP3` — **not recommended**; the audit caveat cannot be
  satisfied while keeping the split granular.
- `PROCEED TO FIX-NATIVE-LIFETIME-SYNC (N-15 + N-16)` — no device needed; independent of the
  commit question.
- `WAIT FOR EXPLICIT USER DECISION`.

**Blocked:** device verification — `5585fecc` still not enumerating.

---

**FINAL RULE COMPLIANCE:** Exactly the four files committed. Validated with forced compile + forced
tests. No push. No tag. Nothing else touched. English only.
