# VERIFY-STACK-CLOSURE — Full-Repo Compile Report

**Phase:** VERIFY-STACK-CLOSURE — Full-Repo Compile Before Single-Stack Commit
**Date:** 2026-10-12
**Mode:** READ-ONLY verification. No commit, push, tag, or source modification. Only build output written.
**Verdict:** **STACK COMPILES CLEAN — READY FOR SINGLE-STACK COMMIT**

---

### 1. Executive Verdict

**STACK COMPILES CLEAN — READY FOR SINGLE-STACK COMMIT**

The complete worktree — all 47 tracked-modified files plus all untracked F.28AL additions — compiles
as a unit. A **forced** full-repo compile of `:app:compileProDebugKotlin` executed **40/40 tasks**
and returned `BUILD SUCCESSFUL in 8m 32s`, with **zero compilation errors**. `compileProDebugKotlin`
itself **ran** (not `UP-TO-DATE`).

This is the check that was missing before commit 2's defect: commit 2 committed a file importing
classes that had never been committed, and no phase had compiled the tree as a whole. This phase
did exactly that — and the closure holds.

Every warning in the output is a pre-existing deprecation notice, none introduced by the F.28AL work.

---

### 2. Pre-Verification Confirmation (P1–P3)

| Check | Expected | Actual | Result |
|---|---|---|---|
| **P1** HEAD | `98a6462…` | `98a646219a7d113f976e7d2b9ed2dd742e34e8bf` | ✅ |
| **P2** dirty count | ~137 | **138** | ✅ (extra 1 = `COMMIT_BATCH_F28AL_GROUP2_REPORT.md`, untracked) |
| **P3** task exists | `compileProDebugKotlin` | listed along with `compileProReleaseKotlin` | ✅ |

Tracked-modified count: **47** (the deferred set, unchanged since the audit).

---

### 3. C1 — Forced Compile Result

**Command (exactly as specified):**
```
$ JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" CI=1 \
  GRADLE_OPTS="-Dorg.gradle.internal.launcher.localhost=127.0.0.1 \
    -Dorg.gradle.internal.remote.internal.inet.tcp.port=0" \
  ./gradlew :app:compileProDebugKotlin --no-daemon --rerun-tasks
```

**Result:**
```
> Task :SoftEtherClient:compileDebugKotlin
> Task :vpnLib:compileDebugKotlin
> Task :sstpClient:compileDebugKotlin
> Task :app:kspProDebugKotlin
> Task :app:compileProDebugKotlin

BUILD SUCCESSFUL in 8m 32s
40 actionable tasks: 40 executed
```

**Exit status: 0.**

**Explicit answer to "was `compileProDebugKotlin` UP-TO-DATE or executed?" — EXECUTED.**
The run reports `40 actionable tasks: 40 executed` and **zero** `UP-TO-DATE`, which also satisfies
the brief's stricter requirement ("Do NOT accept UP-TO-DATE for `compileDebugKotlin` or
`compileProDebugKotlin`"). A second forced run in `--console=plain` mode was used to enumerate every
executed task individually; all 40 appear with no cache suffix. The tasks of interest:

| Task | Status |
|---|---|
| `:app:compileProDebugKotlin` | **executed** (no `UP-TO-DATE`) |
| `:app:kspProDebugKotlin` | **executed** — KSP 2.3.12 still generating |
| `:SoftEtherClient:compileDebugKotlin` | **executed** |
| `:vpnLib:compileDebugKotlin` | **executed** |
| `:sstpClient:compileDebugKotlin` | **executed** |

**Zero occurrences of `error:` or `FAILED` in the entire output.** All warnings are pre-existing
deprecation notices (e.g. `EncryptedFile` deprecated in `vpnLib`, `Icons.Filled.Sort` in
`HomeScreen.kt`, `allNetworks` in `AndroidConnectionAdapter.kt` / `GlobalVpnTracker.kt`, named-arg
hints in `DetailActivity.kt` / `AppDatabase.kt`). **None is attributable to the F.28AL work.**

Two informational notes also appear, both benign and pre-existing: `Some input files use or override
a deprecated API` and `Some input files use unchecked or unsafe operations` (both from `vpnLib`'s
Java sources).

---

### 4. C2 — Defect List

**Not applicable — C1 succeeded.** No compile defect exists.

For completeness: no unresolved reference, no missing symbol, and no `error:` line was emitted at
any point. There is no analogue of the commit-2 defect to report.

---

### 5. C3 — Non-Forced Re-Run and SoftEtherClient Compile

**C3a — `:app:compileProDebugKotlin` (no `--rerun-tasks`):**
```
> Task :app:kspProDebugKotlin UP-TO-DATE
> Task :app:compileProDebugKotlin UP-TO-DATE
BUILD SUCCESSFUL in 14s
40 actionable tasks: 40 up-to-date
```
`BUILD SUCCESSFUL`, as expected. **Gradle's incremental state is consistent with the forced result** —
having just executed all 40 tasks, it correctly found nothing to redo. This is the desired
agreement, not a contradiction.

**C3b — `:SoftEtherClient:compileDebugKotlin` (no `--rerun-tasks`):**
```
> Task :SoftEtherClient:compileDebugKotlin UP-TO-DATE
BUILD SUCCESSFUL in 12s
```
`BUILD SUCCESSFUL`, as expected.

---

### 6. C4 — Dependency Closure Report

**Untracked `app/src/main/` source files: 14.** Import closure:

| File | app-internal imports | Unresolved |
|---|---|---|
| `automode/OwnedVpnDecision.kt` | 0 | 0 |
| `data/persistence/CollectorSnapshotStore.kt` | 2 | 0 |
| `data/persistence/VpnLastConnectionStore.kt` | 2 | 0 |
| `data/remote/RedirectPolicy.kt` | 0 | 0 |
| `data/remote/SourceIdentity.kt` | 0 | 0 |
| `data/remote/SourceValidation.kt` | 1 | 0 |
| `models/OpenVpnConfigTransform.kt` | 0 | 0 |
| `network/AutoConnectReachability.kt` | 4 | **0** (see note) |
| `repository/DatasetIntegrity.kt` | 1 | 0 |
| `repository/ServerListScreenPolicy.kt` | 0 | 0 |
| `repository/ServerListUpdateScheduler.kt` | 2 | 0 |
| `repository/ServerListUpdater.kt` | 2 | 0 |
| `repository/ServerListUpdates.kt` | 2 | 0 |
| `ui/screens/home/ServerListSearchState.kt` | 2 | 0 |

**Total: 31 app-internal imports, 0 unresolved.**

**Note on the one scan flag:** a regex-based scan initially flagged
`vn.unlimit.vpngate.automode.probeTransport` (imported by `AutoConnectReachability.kt:6`). This is a
**false positive** of the scan, not a defect. `probeTransport` is a **top-level extension property**:

```
AutoModeProtocol.kt:58:  val AutoModeProtocol.probeTransport: ProbeTransport
AutoConnectReachability.kt:6:   import vn.unlimit.vpngate.automode.probeTransport
AutoConnectReachability.kt:186: when (target.protocol.probeTransport) {
```

The scan looked for declarations named `probeTransport` and missed the `val Type.name` form.
`AutoModeProtocol.kt` is a tracked (modified) file that exists, and the symbol resolves — **proven
by the successful compile**, which would have failed on an unresolved import.

**Any unresolved import is a defect — report it with file:line.** Result: **none. Zero defects.**

---

### 7. S1–S3 Special Checks

All three verified positively by locating each file's compiled output under
`app/build/intermediates/built_in_kotlinc/proDebug/compileProDebugKotlin/classes/` (AGP 9.x's
output path; note the older `build/tmp/kotlin-classes/` path is no longer used).

**S1 — AutoMode cluster: PASS**
```
PASS  AutoModeController.class
PASS  AutoModeEngine.class
PASS  AutoModeProtocol.class
PASS  AndroidConnectionAdapter.class
PASS  TunnelStateWatcher.class
PASS  AutoModeModels.kt   (no class of that name — file declares AutoModeCandidate,
                           AutoModeState, ReachabilityTarget, etc., all present)
```

**S2 — data layer: PASS**
```
PASS  VpnServerRepository.class
PASS  DataUtil.class
PASS  ConnectionListViewModel.class
PASS  HomeScreen.kt   (Compose screen — output is HomeScreenKt$HomeScreen$… , file-facade naming)
```
Additionally, all 14 untracked `app/` production classes present compiled output — including
`AutoConnectReachability`, whose output is `AutoConnectReachabilityKt.class`,
`AutoConnectReachabilityState.class`, `AutoConnectReachabilityResult.class`,
`AutoConnectReachabilityProbe.class`.

**S3 — SoftEtherVpnService.kt: PASS** — the highest-risk file, correctly named in the brief as the
+755 delta referencing all four F28AL-U classes:
```
PASS  SoftEtherVpnService.class      (SoftEtherClient/build/.../built_in_kotlinc/debug/compileDebugKotlin/classes/)
PASS  ConnectionController.class
PASS  SoftEtherClient.class
```
Its only diagnostic is the pre-existing warning at `SoftEtherVpnService.kt:466:38` (unnecessary safe
call) — present since before this session.

---

### 8. Post-Phase State

| Item | Value |
|---|---|
| HEAD | `98a646219a7d113f976e7d2b9ed2dd742e34e8bf` — **unchanged** ✅ |
| Dirty count | **138** — **unchanged** ✅ |
| Tracked-modified | 47 — unchanged |
| Index | clean (0 staged) |
| Build output | `app/build` 876 MB, `SoftEtherClient/build` 1.4 GB |

**Source mtime scan:** `find app/src SoftEtherClient/src -name "*.kt" -newermt "23:25"` → **empty**.
No source file was touched by this phase.

---

### 9. Evidence Taxonomy

**BUILD ARTIFACT (strongest evidence):**
- `BUILD SUCCESSFUL in 8m 32s`, `40 actionable tasks: 40 executed` — the full-repo compile.
- `.class` outputs located for every named S1–S3 file under
  `app/build/intermediates/built_in_kotlinc/proDebug/compileProDebugKotlin/classes/` and
  `SoftEtherClient/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/`.
- Zero `error:` / `FAILED` lines in the complete output.
- Non-forced re-run: `40 actionable tasks: 40 up-to-date` — incremental state agrees.
- Build output sizes: 876 MB (`app`) + 1.4 GB (`SoftEtherClient`).

**SOURCE CODE EVIDENCE:**
- `AutoModeProtocol.kt:58` — `probeTransport` declared as a top-level extension property.
- 31 app-internal imports across 14 untracked files; all resolve.
- `git rev-parse HEAD` / `git status --short` before and after — identical.

**INFERENCE:**
- That the single-stack commit will produce a compiling tree — **inferred**, and strongly: the
  compile ran against the *working tree* (modified + untracked), which is byte-identical to what a
  commit would record. The inference is that `git commit` of those bytes preserves this result.
- That the 47 tracked-modified files are each independently justified — **not** established here.
  This phase proves they compile; it does not audit their intent (the F28AL audit did that).

**UNKNOWN:**
- Whether `app/` **unit tests** pass. Only compilation was run, per the brief. `:app:testProDebugUnitTest`
  has not been executed on the full stack.
- Whether `assembleProDebug` / `proRelease` packaging succeeds (not run).
- Whether the native tests pass on hardware — device still unavailable.
- Whether the `app/schemas/…/5.json` decision (audit §6) has been made; still untracked.

---

### 10. Explicit No-Change Confirmation

| Item | Status |
|---|---|
| Source modified | **NONE** (mtime scan confirms) |
| Tests modified | **NONE** |
| Gradle / native modified | **NONE** |
| Device | **NONE** |
| CI triggered | **NONE** |
| Commit | **NONE** |
| Push | **NONE** |
| Tags | **NONE** |
| Files created | **build outputs only** (`app/build/**`, `SoftEtherClient/build/**`, `build/reports/**`) |

**Rule compliance:** no `git add`/`commit`/`push`/`tag`/`reset`/`clean`/`stash` was run. No source,
test, Gradle, or CMakeLists file was touched. `origin/main` remains `5c13603c6276c11460982e0633203667d26ef3e7`.

---

### 11. Final Recommendation

**PROCEED TO COMMIT-BATCH-F28AL-SINGLE-STACK**

The precondition I set in the GROUP2 report has been met, and met strictly: the ~50-file stack has
now been compiled as a unit, forced, with zero errors. **The audit's §8 "correctness-first" option is
now safe to execute.**

Two points to carry into that phase:

1. **The evidence is about the working tree, not about a future commit.** The compile read the
   modified + untracked files as they exist now. A single-stack commit must capture **exactly those
   bytes** — no file may be modified between now and the commit, or this verification is invalidated.
   If any source edit occurs first, re-run C1.
2. **Scope decision still owed:** the audit flagged `app/schemas/…/5.json` as "should be committed"
   (C4 of that report) and classified it UNKNOWN pending a ruling. It is currently untracked and was
   **not** part of this compile's closure question. The single-stack commit should either include it
   deliberately or explicitly exclude it — but shouldn't leave the decision to chance.

**Alternatives:** `NEEDS USER REVIEW` if you want the `5.json` ruling settled first;
`PROCEED TO FIX-NATIVE-LIFETIME-SYNC (N-15 + N-16)` (no device needed, independent of the commit);
or `WAIT FOR EXPLICIT USER DECISION`.

**Blocked:** device verification — `5585fecc` still not enumerating.

---

**FINAL RULE COMPLIANCE:** Read-only. No commit. No modification. Compile only. English only.
