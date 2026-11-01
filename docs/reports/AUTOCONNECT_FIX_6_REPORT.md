# AUTOCONNECT-FIX-6 — NATIVE_BUSY Race, Capped Waits (v1.0.36)

**Repository:** `C:\Dev\SoftEthergai` · **Branch:** `main` · **Device:** DETACHED (device-free)
**Baseline:** `d884062aaf8be20cc87a9cb19bf3600b415ef4db` (spec v1.0.35)
**Result:** three surgical changes in `AutoModeController.kt`, three new tests, spec v1.0.36.

---

## §1 Verdict

**PASS.** The defect the unfiltered device run exposed is closed at its cause, not at its symptom:
the `NATIVE_BUSY` branch now checks whether **our own** tunnel is up before it waits for a slot that
its own tunnel is holding. The two slot waits are capped to the per-attempt budget, so a refused
endpoint costs `3T` instead of `7T`. No existing test was modified or deleted; all **805**
pre-existing tests still pass and the three new ones bring `:app` to **808/0/0/1**.

Option B — removing the slot wait and the retry entirely to reach a strict `2T` — was **not**
implemented. It breaks 9 documented tests that pin the v1.0.33/34/35 design, and the operator chose
Option A explicitly before this phase began.

---

## §2 Pre-conditions

| Check | Expected | Observed | Verdict |
|---|---|---|---|
| `HEAD` | `d884062…` | `d884062aaf8be20cc87a9cb19bf3600b415ef4db` | PASS |
| `origin/main` | `d884062…` | `d884062aaf8be20cc87a9cb19bf3600b415ef4db` | PASS |
| Branch | `main` | `main` | PASS |
| Spec version | `1.0.35` | `1.0.35` | PASS |
| Tracked modifications | none | none | PASS |
| Tag count | 5 | 5 (`p2.4.5`, `p2.4.7`, `p2.4.8`, `p2.4.9`, `p2.4.10`) | PASS |

STEP 2 (read-only discovery) confirmed every site at the location the brief predicted:

| Site | Line (pre-edit) | Present |
|---|---|---|
| `if (outcome == AttemptOutcome.NATIVE_BUSY) {` | `1660` | yes |
| `adapter.awaitNativeSlotRelease(nativeSlotAwaitMs)` in that branch | `1721` | yes |
| `adapter.isOwnTunnelEstablished()` — post-attempt only | `1877` | yes |
| `adapter.isOwnTunnelEstablished()` — DIAG payload only | `1781` | yes |
| post-attempt `adapter.awaitNativeSlotRelease(nativeSlotAwaitMs)` | `1946` | yes |
| v1.0.35 `if (!adapter.lastTeardownWasResolved())` | `1813` | yes |

No site had moved. Evidence: SOURCE CODE.

---

## §3 The device evidence and the observed bug

`DEVICE_VERIFY_AUTOCONNECT_FIX_4_UNFILTERED_REPORT.md` §5.3, four Auto Mode runs on an **unfiltered**
WiFi network with the v1.0.34 build:

- **3 of 4 runs destroyed a WORKING tunnel.** Each log carried `DHCP SUCCESS`, then
  `SLOT_UNRELEASED_DIAG … tracker=CONNECTED ownTunnel=true`, then
  `Destroying connection in state: CONNECTED`.
- **1 run excluded a healthy server** after two false wedges
  (`Excluding vpn611507027 from this session after 2 wedges`).
- **Fix-A was UNTESTABLE** in that session: every login succeeded over TCP, so there were zero
  `NAT-T rendezvous` and zero `[TLS] handshake entry (RUDP)` lines.

The bug is a race, and the code that hit it logged the contradiction and then acted against it:
`ownTunnel=true` was printed by the very path that decided the tunnel was gone.

**Mechanism.** `awaitArmedOutcome` waits `T` for a notification. The `CONNECTED` notification can
arrive a few hundred milliseconds **after** the budget expires. At the moment the timeout fires,
`isNativeSlotBusy()` correctly answers `true` — the process-wide slot *is* held — because **our own**
native operation is still finishing. The attempt is therefore classified `NATIVE_BUSY`, and the
branch waited for the slot to be released by the operation that had just succeeded. When the wait
expired, the terminal `giveUpWithUnresolvedNativeOperation()` + `ERROR_NATIVE_UNRESOLVED` tore down a
live, traffic-carrying tunnel and excluded a healthy endpoint.

The post-attempt path has always re-checked ownership before declaring failure
(`val ownTunnelUp = adapter.isOwnTunnelEstablished()`). The `NATIVE_BUSY` branch never did. That
asymmetry is the whole defect. Evidence: PHYSICAL DEVICE + SOURCE CODE.

---

## §4 Fix-1: own-tunnel check (primary)

**Where:** `AutoModeController.kt`, immediately after `if (outcome == AttemptOutcome.NATIVE_BUSY) {`,
before `nativeBusyThisServer++` and before any wait.

**Before:**

```kotlin
if (outcome == AttemptOutcome.NATIVE_BUSY) {
    // AUTOCONNECT-AUDIT D-2: a refusal is not a server verdict. It means
    // this application's OWN previous native operation is still running, so
    nativeBusyThisServer++
    ...
    val slotWaitStartedAt = System.currentTimeMillis()
    val released = adapter.awaitNativeSlotRelease(nativeSlotAwaitMs)
```

**After:**

```kotlin
if (outcome == AttemptOutcome.NATIVE_BUSY) {
    // v1.0.36 (AUTOCONNECT-FIX-6, Fix-1): before treating NATIVE_BUSY as a
    // failure, check whether our OWN tunnel is actually up.
    ...
    if (adapter.isOwnTunnelEstablished()) {                 // :1683
        adapter.log(
            "[AUTO] Tunnel came up despite NATIVE_BUSY; treating attempt as " +   // :1685
                    "connected",
        )
        connectedProtocol = protocol
        break
    }
    // AUTOCONNECT-AUDIT D-2: a refusal is not a server verdict. It means
    nativeBusyThisServer++
```

`break` exits the `for (protocol in supported)` loop; the existing
`if (connectedProtocol != null)` block after it sets `AutoModeState.Connected`, calls `onSuccess`,
clears the wedge ledger, remembers the candidate and starts the connected watcher. **No new success
path was introduced** — the early exit reuses the one that already exists.

Three things are deliberately *not* done on this path: the counter is not incremented, no wedge is
recorded, and no retry is issued. The endpoint did not fail. Evidence: SOURCE CODE.

---

## §5 Fix-2: NATIVE_BUSY slot-wait cap

**Where:** the same branch, at the wait.

**Before:**

```kotlin
val slotWaitStartedAt = System.currentTimeMillis()
val released = adapter.awaitNativeSlotRelease(nativeSlotAwaitMs)
```

**After:**

```kotlin
val slotWaitStartedAt = System.currentTimeMillis()
...
val slotWaitBudget = minOf(nativeSlotAwaitMs, attemptTimeoutMs)   // :1772
val released = adapter.awaitNativeSlotRelease(slotWaitBudget)
```

The two log lines in the branch that reported the budget now report the capped value:

| Line | Before | After |
|---|---|---|
| `:1820` | `${nativeSlotAwaitMs}ms` | `${slotWaitBudget}ms` |
| `:1828` | `budget=${nativeSlotAwaitMs}ms` | `budget=${slotWaitBudget}ms` |

The field **name** `budget` is unchanged; only the value it carries is capped, so existing log
parsers keep working.

The retry itself is untouched. The v1.0.35 gate at `:1865` still skips it when the preceding teardown
was `NATIVE_UNRESOLVED`. Evidence: SOURCE CODE.

---

## §6 Fix-3: post-attempt slot-wait cap

**Where:** after `adapter.disconnect()` on the failure path.

**Before:**

```kotlin
val slotWaitStartedAt = System.currentTimeMillis()
val slotReleased =
    outcome == AttemptOutcome.NATIVE_ERROR ||
            adapter.awaitNativeSlotRelease(nativeSlotAwaitMs)
```

**After:**

```kotlin
val slotWaitStartedAt = System.currentTimeMillis()
...
val postWaitBudget = minOf(nativeSlotAwaitMs, attemptTimeoutMs)   // :2003
val slotReleased =
    outcome == AttemptOutcome.NATIVE_ERROR ||
            adapter.awaitNativeSlotRelease(postWaitBudget)
```

Log lines updated: `:2010` (`${postWaitBudget}ms`) and `:2019` (`budget=${postWaitBudget}ms`).

`NATIVE_ERROR` still short-circuits the wait, because that outcome means no slot was ever acquired.
Evidence: SOURCE CODE.

---

## §7 Why Option A, not B

The phase as first briefed required **removing** the slot wait and the retry so that one
(server, protocol) pair could never exceed `2×T`. That is arithmetically the only way to reach `2T`:

```
attempt T + wait W + retry T <= 2T   =>   W <= 0
```

The prior phase stopped at STEP 2 **without editing a single file**, because the removal breaks nine
existing tests and the same brief forbids modifying them:

| # | Test | Line | Why it breaks |
|---|---|---|---|
| 1 | `nativeBusyRetrySucceedsOnSecondAttempt` | 1994 | its entire premise is success on the second attempt |
| 2 | `secondRefusalAfterReleaseRotatesToTheNextProtocol` | 2028 | pins the `release → refusal → rotate` sequence |
| 3 | `aSlotThatNeverReleasesEndsTheRun` | 2066 | expects `ERROR_NATIVE_UNRESOLVED` |
| 4 | `nativeBusyCancellationDuringRetryIsClean` | 2101 | cancels inside the retry window; no window, no suspension point |
| 5 | `releasedSlotRetryFailureMovesOnToTheNextProtocol` | 2169 | pins `retry → failure → rotation` |
| 6 | `timedOutThenNativeBusyOnNextProtocolWaitsAndRetries` | 2268 | name and assertions both say "waits and retries" |
| 7 | `repeatedNativeBusyRecordsAWedgeAndRotates` | 2914 | asserts `[TCP,TCP,UDP,UDP,good]`; without the retry the wedge threshold is never reached |
| 8 | `unresolvedTeardownSkipsThePostReleaseRetry` | 3161 | a v1.0.35 test whose premise is that a retry exists |
| 9 | `resolvedTeardownKeepsThePostReleaseRetry` | 3214 | asserts the retry happens on a resolved teardown |

These are not incidental assertions. Six encode the mechanism the v1.0.33/34 phases deliberately
built; two encode v1.0.35's narrowing of it. Removing the retry is a **reversal of a documented
design decision**, not a bug fix layered on top of one.

The operator chose **Option A**: keep the retry, cap the waits, and add the own-tunnel check that
addresses the observed failure. That is defensible on its own terms — the device evidence is about a
**live tunnel being destroyed**, and Fix-1 stops that directly. The 30 s wait was a contributing
cost, not the cause.

Option B remains available in a future phase **with explicit test-rewrite authorization**. It was
not attempted here, and no retry code was removed. Evidence: SOURCE CODE + PRIOR REPORT.

---

## §8 Net timing

| Case | Before v1.0.36 | After v1.0.36 | Mechanism |
|---|---|---|---|
| Own tunnel came up | `7T` (the branch never checked) | **`1T`** | Fix-1 exits before any wait |
| Previous teardown unresolved | `7T` | **`2T`** | v1.0.35 gate skips the retry; capped wait |
| Previous teardown resolved, retry fires | `7T` | **`3T`** | capped wait + retry |

With the device's `T = 5 s`: `35 s` → `5 s` / `10 s` / `15 s` per (server, protocol).

The cap costs nothing on the healthy path: `awaitNativeSlotRelease` returns as soon as the slot is
free, so a normal rotation never approaches either budget. Only an actually-held slot pays, and the
cap makes the "stuck" verdict arrive **sooner**, which is the correct direction for a fail-closed
terminal.

The strict `2T` bound is Option B and is **not** achieved by this revision. Evidence: SOURCE CODE.

---

## §9 Tests added

All three are additive. No existing test function was modified or deleted.

| Test | Line | Pins |
|---|---|---|
| `nativeBusyWithOwnTunnelUpIsTreatedAsConnected` | 3285 | Fix-1: `Connected`, the recovery log, **no** wedge log, `slotWaits == 0`, ledger `0`, exactly one dial |
| `nativeBusySlotWaitIsCappedToTheAttemptBudget` | 3334 | Fix-2: the **argument** received equals `min(NATIVE_SLOT_AWAIT_MS, T)` and is strictly below the constant; the retry still fires; no skip line |
| `postAttemptSlotWaitIsCappedToTheAttemptBudget` | 3374 | Fix-3: the same assertion after a `TIMED_OUT` |

Two additive members were added to `FakeAdapter`, both with behaviour-preserving defaults:

- `ownTunnelEstablished = false` (`:116`) — the interface default, so the new check is inert for
  every pre-existing test, including the nine that pin the retry.
- `slotWaitTimeouts` (`:92`) — records the budget passed to each wait. Recording the **argument**
  rather than only counting calls is what makes the cap provable rather than merely present.

A note on the ledger assertion in T-NEW-15: `countFor == 0` is corroborating, not decisive, because
the connected path clears the ledger (`wedgeTracker.clear(key)`). The decisive assertions are
`slotWaits == 0` and the absence of a wedge log — together they prove the branch was never entered.
Evidence: TEST.

---

## §10 JVM tests

| Suite | Baseline | After | Delta |
|---|---|---|---|
| `:app:testProDebugUnitTest` | 805 / 0 / 0 / 1 | **808 / 0 / 0 / 1** | +3 tests, skip unchanged |
| `:SoftEtherClient:test` | 173 / 0 / 0 / 0 | **173 / 0 / 0 / 0** | unchanged |

`AutoModeControllerTest` alone: **100** cases, **0** failures, **0** skipped. All **805**
pre-existing `:app` tests still pass. Both suites ran under `CI=true` with `--rerun-tasks
--no-build-cache`. Evidence: TEST.

---

## §11 Build result

```
CI=true ./gradlew :app:assembleProDebug --rerun-tasks --no-build-cache
BUILD SUCCESSFUL in 3m 4s
114 actionable tasks: 114 executed
```

**APK:** `app/build/outputs/apk/pro/debug/app-pro-debug.apk`
**SHA-256:** `BE345075A1F2E38D7ADD818B37072E27D0ED70CB0A00A4129035545AE4ECF249`
**Size:** 63,753,806 bytes

It differs from v1.0.35's `8FB20696…92FD801` as expected: the APK embeds the compiled Kotlin, and no
native source was touched.

**Environment fault, disclosed.** The first `assembleProDebug` attempt failed:

```
Execution failed for task ':vpnLib:copyDebugJniLibsProjectOnly'.
> ... libopenvpn.so: There is not enough space on the disk
```

`fsutil volume diskfree C:` reported **-27,381,760** bytes free — the volume was genuinely out of
space, not a source or configuration defect. Resolved by deleting **regenerable** artifacts only:

- project build outputs (`app/build`, `vpnLib/build`, `SoftEtherClient/build`, `sstpClient/build`,
  `build`) — ~1.2 GB, all rebuilt by `--rerun-tasks` anyway;
- four stale entries in `%LOCALAPPDATA%\Temp` (`ofoqlzru`, `workbuddy-update-x64`,
  `vscode-stable-system-x64`, `installed.apk`) — ~1.8 GB.

No source, no test, no cache and no user document was deleted. After freeing space the identical
sources built green. This is recorded as an environment fault and is **not** attributed to the code.
Evidence: TEST.

---

## §12 Diff-scope

```
 PROJECT_SPECIFICATION.md                           | 128 ++++++++++++++++-
 app/src/main/java/.../automode/AutoModeController.kt |  72 +++++++++-
 app/src/test/java/.../automode/AutoModeControllerTest.kt | 153 +++++++++++++++++++++
 3 files changed, 342 insertions(+), 11 deletions(-)
```

| File | Status | Authorized |
|---|---|---|
| `AutoModeController.kt` | modified | yes |
| `AutoModeControllerTest.kt` | modified (additive) | yes |
| `PROJECT_SPECIFICATION.md` | modified | yes |
| `DEVICE_VERIFY_AUTOCONNECT_FIX_4_UNFILTERED_REPORT.md` | new (committed here) | yes |
| `AUTOCONNECT_FIX_6_REPORT.md` | new | yes |

Untouched, as required: `AndroidConnectionAdapter.kt`, every native source, `build.gradle`, the
manifests, the `NATIVE_SLOT_AWAIT_MS` / `NATIVE_SLOT_AWAIT_MAX_MS` constants, and every
already-tracked report. `git add -A` was never used; each commit staged its files by name.
Evidence: SOURCE CODE.

---

## §13 §22 byte-identity

| Range | Working tree | `HEAD` | Verdict |
|---|---|---|---|
| §22 | 99 lines / 6,504 bytes | 99 lines / 6,504 bytes | **IDENTICAL** |
| §1.6.2 – §1.6.27 | 1,807 lines | 1,807 lines | **IDENTICAL** |
| §12.5.1 | 22 lines | 22 lines | **IDENTICAL** |
| §12.5.2 | 45 lines | 45 lines | **IDENTICAL** |

Evidence: SOURCE CODE.

---

## §14 Negative assurance

- No existing test was modified or deleted; the three additions are the only changes to the test
  file, plus two additive `FakeAdapter` members whose defaults preserve prior behaviour.
- All **805** pre-existing `:app` tests still pass; `:SoftEtherClient` is unchanged at 173/0/0/0.
- The retry mechanism is **still present** — this is Option A, not B. `awaitNativeSlotRelease` is
  still called, `armTunnelWatch` + `connect` still re-dial, and the v1.0.35 gate still skips on an
  unresolved teardown.
- No device command, no `adb`, no install, no logcat capture.
- No tag was created, moved or deleted; the tag set is unchanged at 5.
- No `--force`, no `reset`, no `clean`, no `stash`, no `checkout`, no `restore`.
- No `git add -A`, no `git add .`.
- No file outside the authorized set was touched; `D:\PMC\Documents\Android\SoftEthergai` was not
  accessed.
- Every build and test ran with `CI=true`.

---

## §15 What remains UNVERIFIED, and the Option B path

**Fix-A (native RUDP handshake bound) is still UNVERIFIED.** `softether_protocol.c:2935` remains
untouched. The unfiltered run produced zero NAT-T rendezvous and zero `[TLS] handshake entry (RUDP)`
lines, so the site was never reached: every login succeeded over TCP. The fix is neither confirmed
nor refuted.

**The three fixes in this revision are not device-verified.** They are SOURCE CODE + TEST. A device
run on an unfiltered network is the next evidence layer, and the specific thing to look for is the
absence of `Destroying connection in state: CONNECTED` after `ownTunnel=true`, plus at least one
`Tunnel came up despite NATIVE_BUSY` line.

**Option B is not implemented.** A strict `2T` requires removing the retry, which requires rewriting
the nine tests listed in §7. That is a design decision, not a mechanical change, and it needs
explicit authorization for test rewrites. It is **proposed only**; nothing in this revision prepares
or pre-commits it.

**The strict bound's trade-off, stated plainly.** Option A accepts `3T` in the rare case (own tunnel
down, previous teardown resolved) in exchange for not deleting a mechanism the codebase documents and
tests. If device evidence later shows the retry never succeeds — v1.0.35's measurement was 12 retries,
0 successes, 11 of 12 after an unresolved teardown, which is why the gate exists — then Option B
becomes the evidence-backed choice and the nine tests should be rewritten to match. That judgment
needs a device run of this revision first.
