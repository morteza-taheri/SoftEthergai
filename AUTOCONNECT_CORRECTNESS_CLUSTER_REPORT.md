# AUTOCONNECT-CORRECTNESS-CLUSTER — Report (spec v1.0.42)

**Repository:** `C:\Dev\SoftEthergai` · **Branch:** `main`
**Baseline (pre-phase):** `c5a7bb5d7b1fea2aaf26d03dedfc8cc21500251c` — the pre-phase HEAD, identical to
`origin/main` when the phase started (0 ahead, 0 behind).
**Phase:** AUTOCONNECT-CORRECTNESS-CLUSTER-v1.0.42 (REVISED — Option A + B-3 + B-4) · **Date:** 2026-10-20
**Scope:** four Auto Mode corrections that share one cause — a value or a position captured once and
then treated as still true.
**Labels used throughout:** `SOURCE CODE` / `TEST` / `PHYSICAL DEVICE` / `INFERENCE` / `UNKNOWN` /
`PRIOR REPORT`.

---

## §1 Verdict

All four items are implemented and green. **F6 is a deliberate reversal of a documented v1.0.31
decision, not a bug fix**, and is reported as such everywhere it appears. The original brief was
stopped at STEP 2 because four of its premises did not hold at the baseline; the operator's revised
brief authorised the work that the premises had blocked.

| Item | Brief's premise | State at `c5a7bb5` | Action taken | Evidence |
|------|-----------------|--------------------|--------------|----------|
| **F1** — live attempt timeout | `attemptTimeoutMs` is a frozen `Long` | True | Constructor parameter → `attemptTimeoutMsProvider: () -> Long`, read per attempt | SOURCE CODE + TEST |
| **B-3** — live slot budget | (added by the revision) `nativeSlotAwaitMs` freezes the *same* setting | True | → `nativeSlotAwaitMsProvider: () -> Long`, production keeps `5 × T` + floor + cap | SOURCE CODE + TEST |
| **F2** — offline runs must stop | No connectivity check anywhere in the loop | True | `isNetworkAvailable` seam, `ERROR_NO_NETWORK`, two guards | SOURCE CODE + TEST |
| **B-4** — user-facing sentence | (added by the revision) the new terminal had no string | True | `auto_mode_error_no_network` + a screen branch | SOURCE CODE + TEST |
| **F6** — identity continuation | Index continuation accepted drift **by decision** | True (documented) | `resumeFromIndex` → `resumeFromServerKey`; **decision reversed** | SOURCE CODE + TEST |
| **Skip filter** — IP → identity | IP-only filter also dropped a different server sharing an IP | True | Same `serverKey` as the continuation | SOURCE CODE + TEST |

`:app` **858 / 0 / 0 / 1** (was 846 / 0 / 0 / 1 — +12, exactly the new tests); `:SoftEtherClient`
**173 / 0 / 0 / 0** (unchanged). `:app:assembleProDebug` under `CI=true` → BUILD SUCCESSFUL, APK
SHA-256 `e8a4bb1400e9046c1c0b4b34218b18da10c127a1992407ead8062f5fd5a67ecd`. **No device was run**
(`UNKNOWN` for every device claim). No tag operation of any kind.

---

## §2 Baseline state (STEP 1)

| Check | Result | Evidence |
|-------|--------|----------|
| `git rev-parse HEAD` | `c5a7bb5d7b1fea2aaf26d03dedfc8cc21500251c` | SOURCE CODE |
| `git rev-parse origin/main` | identical to HEAD | SOURCE CODE |
| `git rev-list --count origin/main..HEAD` | `0` | SOURCE CODE |
| Tracked modifications | none | SOURCE CODE |
| Spec version at baseline | `1.0.41` | SOURCE CODE |
| Remote tags | 5 (`p2.4.5`/`p2.4.7`/`p2.4.8`/`p2.4.9`/`p2.4.10`) | SOURCE CODE |

---

## §3 STEP 2 findings — why the original brief was stopped

The stop was a premise failure, not a code error. Four items:

1. **F1 was source-incompatible as written.** Making `attemptTimeoutMs` a lambda breaks **13 call
   sites** (`attemptTimeoutMs = <Long>`) spread over **six** test files, while the original brief
   authorised exactly **one** test file (`AutoModeControllerTest.kt`). The other six files are
   `AutoModeEndToEndTest` (3), `AutoModeLifecycleF28Test` (2), `AutoModeNativeBusyTest` (3),
   `AutoModeReconnectTest` (1), `AutoModeServerCursorTest` (1), `VpnM24x10TaskTest` (3). Proceeding
   would have required editing files outside the whitelist. `SOURCE CODE`.
2. **F6 was not the enforcement of existing text.** §11.13.3 says "the **next server in the sorted
   list**" — it says nothing about identity. The v1.0.31 KDoc in `AutoModeController` goes further and
   explicitly *rejects* identity: "pinning a server identity instead would silently skip a candidate
   that has since become better, and the ordering is the contract (§6.6)". F6 therefore reverses a
   documented decision and needed an explicit authorisation, not a bug-fix framing. `SOURCE CODE`.
3. **`nativeSlotAwaitMs` froze the same setting.** Fixing F1 alone would have left half the
   preference frozen, since both budgets derive from `SETTING_AUTO_MODE_TIMEOUT_SECONDS`. This became
   B-3 in the revision. `SOURCE CODE`.
4. **`ERROR_NO_NETWORK` had no user-facing string.** The screen's `when` would have fallen through to
   the generic failure text, so the new terminal would have been invisible to the user — the exact
   opposite of its purpose. This became B-4. `SOURCE CODE`.

The revision authorised all four: A (the six mechanical test edits), B-3, B-4, and an explicit
requirement that F6 be reported as a decision revert with its trade-off. `PRIOR REPORT`.

---

## §4 F1 — the per-attempt timeout is read, not frozen

`AutoModeController.kt:60` replaces the constructor `val attemptTimeoutMs: Long` with
`attemptTimeoutMsProvider: () -> Long`, defaulting to `{ DEFAULT_ATTEMPT_TIMEOUT_MS }` so every caller
that does not model the setting keeps the pre-v1.0.42 behaviour exactly.

The defect was structural, not a typo: `AutoModeEngine.ensure()` builds the controller **once**
(`controller?.let { return it }`), while `AndroidConnectionAdapter` re-reads the preference on every
call. A frozen field therefore diverged from the adapter the moment the user changed the setting, and
only a process restart reconciled them — device-observed in
`DEVICE_VERIFY_AUTOCONNECT_BUGS_v1.0.41_REPORT.md` §11 (`PRIOR REPORT`).

`AutoModeController.kt:2222` adds the single read point:

```kotlin
private fun attemptTimeoutMs(): Long = attemptTimeoutMsProvider().coerceAtLeast(1L)
```

Every former field read now calls it: the run-start log line, both `awaitArmedOutcome` calls, both
`minOf` slot budgets, and the `TIMED_OUT` log line. `AutoModeEngine.kt:78` supplies
`{ du.getAutoModeTimeoutSeconds().toLong() * 1000 }`. `SOURCE CODE` + `TEST` (T-NEW-43).

### §4.1 The guard is positivity-only, and that is a correction made during this phase

The first draft floored the value at 1 s. That was wrong on two counts, and both were caught by the
test run:

- **Redundant in production.** `DataUtil.getAutoModeTimeoutSeconds()` already clamps to `5..60`
  seconds at the source (`DataUtil.kt:361`), so a corrupt preference cannot reach the controller.
- **Destructive in tests.** `postAttemptSlotWaitIsCappedToTheAttemptBudget` and
  `nativeBusySlotWaitIsCappedToTheAttemptBudget` deliberately use `T = 300 ms` to make the cap
  assertion exact. The 1 s floor silently raised their budget to 1000 and both failed
  (`expected:<[300]> but was:<[1000]>`).

The floor is now `coerceAtLeast(1L)` — positivity only — with the reasoning recorded in the KDoc.
`TEST` + `SOURCE CODE`.

---

## §5 B-3 — the slot budget moves too

`AutoModeController.kt:75` replaces `nativeSlotAwaitMs: Long` with
`nativeSlotAwaitMsProvider: () -> Long`; `:2237` reads it through
`nativeSlotAwaitMsProvider().coerceIn(1L, NATIVE_SLOT_AWAIT_MAX_MS)`.

`AutoModeEngine.kt:96` keeps the existing derivation verbatim:
`(T × 5).coerceAtLeast(NATIVE_SLOT_AWAIT_MS).coerceAtMost(NATIVE_SLOT_AWAIT_MAX_MS)` — the v1.0.36
30 s floor and the D-2 60 s cap that were previously applied inside the constructor. No behaviour
changes in production; only *when* the value is read changes. `SOURCE CODE`.

### §5.1 The 5 s floor in the first draft would have broken the busy tests

The first draft used `coerceIn(5_000L, NATIVE_SLOT_AWAIT_MAX_MS)`. `AutoModeNativeBusyTest` asks for
`nativeSlotAwaitMsProvider = { 200L }` precisely so its "genuinely stuck" cases reach their terminal
inside a 15 s `withTimeoutOrNull` bound. A 5 s floor would have made every slot wait 25× longer and
pushed those tests against their bounds. The floor is now `1L`, the cap stays (a provider that ignores
it could make the terminal give-up unreachable), and the reason is in the KDoc. `SOURCE CODE` + `TEST`.

---

## §6 F2 — an offline run stops instead of blaming every server

**The finding.** With Wi-Fi and data both off, a press walked the entire list, marked every candidate
`TIMED_OUT`, and gave each one a `WedgeTracker` strike. `EXCLUSION_THRESHOLD` is 2, so **two** such
runs excluded every healthy server for the rest of the process and the next online press had nothing
left to dial. The user-visible symptom was `ERROR_NO_SERVER` — "every server failed" — when in fact
none had been contacted. `SOURCE CODE` + `INFERENCE`.

**The seam.** `AutoModeController.kt:164` adds
`isNetworkAvailable: () -> Boolean = { true }`. The default `true` means every existing double and
every caller that does not model connectivity behaves exactly as before. The controller stays free of
Android types. `SOURCE CODE`.

**The two guards.**

- `:1477` — after the protocol check, **before** the list is resolved and before the continuation is
  consumed: `[AUTO] No network available; stopping before any attempt` → `Error(ERROR_NO_NETWORK)`.
- `:1642` — inside the loop, before each attempt: `[AUTO] Network lost; stopping before the next
  attempt` → same terminal.

Neither site records a wedge. Placing the first guard before the continuation is consumed means an
offline press does not spend the Next-Server position it was handed; the next press from `Error`
clears it anyway. `SOURCE CODE`.

**The terminal.** `AutoModeController.kt:566` adds `const val ERROR_NO_NETWORK = "no_network"` beside
`ERROR_NO_ENABLED_PROTOCOL`. §6.8.4 gains it as terminal #8 and the "system-level terminals" set grows
to six.

**The signal.** `GlobalVpnTracker.kt:486` adds:

```kotlin
fun isAnyNetworkAvailable(): Boolean {
    val ctx = appContext ?: return true
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return true
    return cm.activeNetwork != null
}
```

Both escape hatches fail **open** on purpose: a missing context or service must not manufacture a "no
network" terminal, and `activeNetwork != null` is the weakest question that answers "is there any
network at all". The guard's job is to stop a run that cannot possibly succeed, not to judge whether a
particular server is reachable — that is `awaitTunnel`'s job. `AutoModeEngine.kt:150` supplies it,
fully qualified, so no new import crosses the module boundary. `SOURCE CODE` + `TEST` (T-NEW-39a…d).

---

## §7 B-4 — the terminal has a sentence

`strings.xml:293`:

```xml
<string name="auto_mode_error_no_network">No network available. Turn on Wi-Fi or mobile data and try again.</string>
```

`AutoModeScreen.kt:485–486` adds the branch after the `ERROR_VPN_PERMISSION` case:

```kotlin
error.message == AutoModeController.ERROR_NO_NETWORK ->
    stringResource(R.string.auto_mode_error_no_network)
```

Without it the new terminal would have rendered the generic failure text, which is indistinguishable
from a genuine list exhaustion — the one thing the terminal exists to prevent. `SOURCE CODE` +
`TEST` (T-NEW-45a…c, source-shape: the resource exists and tells the user what to do; the branch
resolves to it; the id stays `"no_network"`).

---

## §8 F6 — the continuation is an identity, and this reverses a decision

**This is not a bug fix.** It is a reversal of a decision the codebase documented at v1.0.31, and it
is recorded as such in four places: the `resumeFromServerKey` KDoc, §6.8.5, §11.13.3 and §1.6.35
bullet 8.

**What changed.** `AutoModeController.kt:670` replaces `resumeFromIndex: Int` with
`resumeFromServerKey: String?`. The key is `ip|hostname`, formatted in exactly one place
(`:2262`) and shared with the wedge ledger (`wedgeKey`, `:2246`), so the continuation, the skip filter
and the ledger can never disagree about what "this server" means. Continuation is
`indexOfFirst { serverKey(it) == key } + 1` (`:1616`).

**Why.** The old index was `currentServerIndex + 1` into the *previous* run's resolved list. If the
list re-sorted between the two runs, index N+1 named a **different** server — the drift the v1.0.31
KDoc accepted. Identity removes the drift.

**The trade-off, stated rather than hidden.** A candidate that became *better* in the new sort, and
now sorts **before** the connected server, is skipped by this continuation. It is not skipped for
ever: the next fresh press starts from the top. This is the exact cost the v1.0.31 KDoc named, and it
is now the accepted side of the trade instead of the rejected one. `INFERENCE` (from the ordering
contract, §6.6) + `SOURCE CODE`.

**The new case.** With an index there was no "not found". With an identity there is: if the connected
server left the dataset, the run starts at index 0 and logs
`[AUTO] Previous connected server no longer in the list; starting from the top` (`:1628`). The
**last** server is still found, so that case keeps its v1.0.31 meaning — no attempts,
`ERROR_NO_SERVER` — which is why the unchanged
`pressingWhileConnectedToTheLastServerEndsWithoutRestarting` still pins it. `SOURCE CODE` + `TEST`
(T-NEW-40, T-NEW-41).

**Write sites.** `:1183` (fresh start → `null`), `:1200` (press while Connected → the connected
server's key), `:1632` (consumed once, at read time), `:2193` (list exhausted → defensive clear).
`SOURCE CODE`.

---

## §9 The skip filter — this one *is* a bug fix

`skipToNextServer()`'s Connected branch filtered by `it.ip == currentIp`. Two different servers can
share one IP (NAT / load-balancer shapes), so the filter dropped **both** and the run could end with
`ERROR_NO_SERVER` while a perfectly good candidate was removed. `:707–708` captures the connected
server's identity (`connectedCandidate` first, then the `Connected` state's `ip`/`hostname`), and
`:725` filters on `serverKey`. The two can now never disagree. `SOURCE CODE` + `TEST` (T-NEW-42).

---

## §10 Tests

**12 new JVM tests in four new files.** No existing test was deleted or rewritten; the only edits to
existing test files are mechanical (13 constructor call sites in 7 files:
`attemptTimeoutMs = x` → `attemptTimeoutMsProvider = { x }`, plus one `nativeSlotAwaitMsProvider`).

| File | Tests | Coverage |
|------|-------|----------|
| `AutoModeNetworkGuardTest` | 4 | offline stops before any attempt; records no wedge; online still dials and connects; mid-run loss stops before the next server |
| `AutoModeNextServerIdentityTest` | 4 | a re-sort does not shift the continuation; a vanished server restarts at the top and logs it; the skip filters by identity not IP; the attempt timeout is read fresh per attempt |
| `AutoModeSlotWaitBudgetTest` | 1 | the slot budget is read fresh per attempt (observed through the `awaitNativeSlotRelease` argument) |
| `AutoModeNoNetworkMessageTest` | 3 | the string resource exists and is actionable; the screen branch maps to it; the id stays `"no_network"` |

**Pre-existing tests that had to stay green, and did:**

| Test | Phase | Result |
|------|-------|--------|
| `pressingWhileConnectedContinuesFromTheNextServer` | v1.0.31 OP-R6 | PASS |
| `pressingWhileConnectedToTheLastServerEndsWithoutRestarting` | v1.0.31 OP-R6 | PASS |
| `aFreshPressAfterExhaustionStartsFromTheTopAgain` | v1.0.31 OP-R6 | PASS |
| `skipWhileConnectedDialsTheNextServerAndNeverRestartsFromTheTop` | v1.0.37 | PASS |
| `postAttemptSlotWaitIsCappedToTheAttemptBudget` | v1.0.36 | PASS |
| `nativeBusySlotWaitIsCappedToTheAttemptBudget` | v1.0.36 | PASS |

**Counts (JUnit XML aggregation).**

| Suite | Result | Baseline | Delta |
|-------|--------|----------|-------|
| `:app` (`testProDebugUnitTest`) | **858 / 0 / 0 / 1** | 846 / 0 / 0 / 1 | +12 = the new tests |
| `:SoftEtherClient` (`test`) | **173 / 0 / 0 / 0** | 173 / 0 / 0 / 0 | 0 |

The single skip is unchanged (`HtmlParserOracleTest`). The revised brief predicted "~855–856"; the
actual figure is 858 because the DELTA's own list (T-NEW-39a…d, 40…43, 44, 45a…c) sums to 12, not
9–10. The brief's acceptance rule ("any count >= 851 is fine as long as all new tests pass") is met.
`TEST`.

### §10.1 Two test defects found and fixed during this phase

- **Trailing-lambda binding.** `AutoModeNextServerIdentityTest`'s `controller()` helper put
  `serverProvider` before `timeoutProvider`, so `controller(scope, adapter) { … }` bound the trailing
  lambda to `timeoutProvider` and failed to compile (`No value passed for parameter 'serverProvider'`).
  The parameter order was changed so the provider is last.
- **Stale terminal state in the skip test.** `skipToNextServer()` launches its restart in a
  coroutine, so immediately afterwards the state is still the previous `Connected` and a
  terminal-state poll returns it at once with no dial. The test now waits for the dial (or for the run
  to fail) before polling, and asserts that the dial happened with a diagnostic message rather than
  letting `first()` throw `NoSuchElementException`.

---

## §11 Build gate (STEP 7)

```
:app:assembleProDebug --rerun-tasks --no-build-cache   (CI=true)
BUILD SUCCESSFUL in 6m 2s
GATE_EXIT=0
```

APK: `app/build/outputs/apk/pro/debug/app-pro-debug.apk`, 63,766,594 bytes,
SHA-256 `e8a4bb1400e9046c1c0b4b34218b18da10c127a1992407ead8062f5fd5a67ecd`.

`versionCode 144` / `versionName 2.4.11` are unchanged — no build or manifest file was touched.
`TEST`.

---

## §12 Spec changes (STEP 9)

| ID | Location | Change |
|----|----------|--------|
| E1 | Header | HEAD → `c5a7bb5…`; document version → `1.0.42` |
| E2 | §1.6.1 | new `Push state (v1.0.42)` note |
| E3 | §6.8.4 | terminal #8 `ERROR_NO_NETWORK`; "Cases 1–5 **and 8**" with the explanation that case 8 used to masquerade as case 1 |
| E4 | new §1.6.35 | 15 bullets: the shared cause, F1, B-3, the guard decision, F2, the terminal, the tracker signal, F6 as a decision revert, the vanished-server case, the skip filter, tests, gate, the STOP/revised-brief record, non-goals, device verification pending |
| E5 | §11.13.3 | annotation under bullet 2: the text says "the next server in the sorted list", not identity; v1.0.42 tightens the implementation to identity |
| E7 | §20 changelog | `1.0.42` row |
| E8 | §20 Verification Status | `§1.6.35` row |

**One addition beyond E1–E8, flagged:** §6.8.5 is the *normative* statement of the very behaviour F6
reverses — it still showed `resumeFromIndex = currentServerIndex + 1`. Leaving it untouched would have
shipped a spec whose normative text contradicts the code, so it now carries a "Superseded in v1.0.42 —
this is a decision reversal, not a correction" note, the code block is preserved (so the change is
visible rather than silently edited away), and the normative bullets are updated in place. This is the
only place the brief's edit list was exceeded, and it was exceeded to avoid a self-inflicted
documentation defect.

§22 is **byte-identical**: 99 lines, 6,504 bytes, SHA-256
`adf12858b72ed13742252c512a8728e9141e415cc55d09c6409af4e8e622020e`. `SOURCE CODE`.

---

## §13 Scope compliance and negative assurance

**Files changed (tracked, 12 before the spec/report):**

| File | Nature |
|------|--------|
| `AutoModeController.kt` | F1, B-3, F2, F6, skip filter |
| `AutoModeEngine.kt` | the three production lambdas |
| `GlobalVpnTracker.kt` | `isAnyNetworkAvailable()` |
| `AutoModeScreen.kt` | one branch |
| `strings.xml` | one string |
| 7 test files | mechanical constructor edits (13 sites) |

Plus 4 new test files, `PROJECT_SPECIFICATION.md`, and this report.

**Not done, deliberately:**

- No device, no `adb`, no install, no log capture. Every device-level claim is `UNKNOWN`.
- No tag created, moved, or deleted; remote tag set remains 5.
- No force, no reset, no clean, no stash, no checkout.
- No `git add -A`; no test deleted.
- `versionCode` / `versionName` / manifest / build files untouched.
- `D:\PMC\…` untouched.
- C5 (the standalone post-push verification gate) is executed **after** the push, not in this phase.

---

## §14 Residual risks

| Risk | Severity | Mitigation / status |
|------|----------|---------------------|
| F6 skips a candidate that became better and now sorts before the connected server | Low | Accepted and documented; the next fresh press starts from the top |
| `activeNetwork != null` is coarser than "the tunnel's path works" | Low | Intentional: the guard exists to stop an impossible run, not to grade reachability |
| A provider that returns a huge slot budget | Low | `NATIVE_SLOT_AWAIT_MAX_MS` cap retained in the helper |
| Behaviour change unverified on a device | Medium | Three concrete device checks listed in §1.6.35 bullet 15 |
| `AutoModeNetworkGuardTest` depends on log substrings | Low | The substrings are the operator-facing contract lines; the state assertions are independent |

---

## §15 Device verification — PENDING (`UNKNOWN`)

No device was used in this phase. The three checks that would close it:

1. **F1 / B-3** — change the Auto Connect timeout in Settings mid-session; the next attempt must log
   the **new** value, and a subsequent slot wait must reflect it.
2. **F2** — press Auto Connect with Wi-Fi and data both off; the run must stop immediately with the
   "No network available" message, emit no `TIMED_OUT` lines, and leave the wedge ledger empty.
3. **F6** — change the sort setting between two presses; the second run must continue after the
   **same server**, not after the same index.

Until those are run, every behavioural statement in this report is `SOURCE CODE` + `TEST` only.

**STOP. No next phase proposed.**
