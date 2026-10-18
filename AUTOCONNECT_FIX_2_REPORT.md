# AUTOCONNECT-FIX-2 — Report (v1.0.32)

**Phase:** AUTOCONNECT-FIX-2-v1.0.32
**Date:** 2026-10-18
**Repo:** C:\Dev\SoftEthergai
**Branch:** main
**Device:** DETACHED (device-free phase; no adb command issued)

---

## §1 Verdict

**BOTH DEVICE BUGS CLOSED; SLOT-NON-RELEASE INSTRUMENTED ONLY.**

| Item | Status |
|------|--------|
| BUG-A — D-4 predicate blind to `CONNECTED` | **CLOSED** (SOURCE CODE + TEST) |
| BUG-B — wedge never recorded on `NATIVE_BUSY` | **CLOSED** (SOURCE CODE + TEST) |
| Slot-non-release root cause | **UNKNOWN** — instrumented, no fix attempted |
| JVM suite | `:app` **800/0/0/1**, `:SoftEtherClient` **173/0/0/0** — no regression |
| Build | `:app:assembleProDebug` SUCCESS under `CI=true` |
| Device re-verification | **PENDING** (next phase) |

---

## §2 Pre-conditions

| Check | Expected | Actual | Result |
|-------|----------|--------|--------|
| HEAD | == origin/main | `9d9e43effa5b8adabd006520393aaa2dfedd7a0a` (both) | PASS |
| Branch | main | main | PASS |
| Tracked modifications | none before edits | none | PASS |
| Spec version | 1.0.31 | 1.0.31 | PASS |
| Tag count | 5 | 5 | PASS |

---

## §3 BUG-A fix — D-4 predicate

### Before (SOURCE CODE, evidence: PHYSICAL DEVICE, device report §5)

```kotlin
// AutoModeEngine.kt:116
isTunnelBusy = { vn.unlimit.vpngate.state.GlobalVpnTracker.isBusy },

// GlobalVpnTracker.kt:47
val isBusy: Boolean get() = this == CONNECTING || this == DISCONNECTING
```

`CONNECTED` is not in `isBusy`. The v1.0.31 guard in
`AutoModeController.onButtonPressedWithoutGuard()` therefore never fired for a manual
tunnel that had finished connecting — the exact hazard D-4 was written for. Device result:
a full run started and destroyed the user's manual OpenVPN session.

### After

```kotlin
// GlobalVpnTracker.kt:72-73 (enum) — new
val isActiveOrBusy: Boolean
    get() = this == CONNECTED || this == CONNECTING || this == DISCONNECTING

// GlobalVpnTracker.kt:581 (tracker facade) — new
val isActiveOrBusy: Boolean get() = currentStatus.isActiveOrBusy

// AutoModeEngine.kt:125 — changed
isTunnelBusy = { vn.unlimit.vpngate.state.GlobalVpnTracker.isActiveOrBusy },
```

`isBusy` is **unchanged** — it still answers the narrower "is a transition in flight"
question, and other callers keep their semantics. `DISCONNECTED` and `ERROR` are excluded
from the new predicate: nothing is owned in either state.

**Guard scope preserved.** The change is in the predicate only. The guard remains inside the
`Disconnected`/`Error` branch; the `Connected` branch (OP-R6) is not gated, so pressing while
connected still means "move on". KDoc added at `AutoModeController.kt:1053-1065`.

**Why CI was green.** T-NEW-2 injected a fake `isTunnelBusy` lambda, so it proved the guard was
*wired* — never that the production predicate was correct. T-NEW-7 now pins the predicate at the
enum level; T-NEW-8 pins the engine wiring and the OP-R6 non-gating.

---

## §4 BUG-B fix — wedge on repeated `NATIVE_BUSY`

### Before (SOURCE CODE, evidence: PHYSICAL DEVICE, device report §7)

`recordWedge` had exactly one call site, inside the `TIMED_OUT` branch
(`AutoModeController.kt:1690-1693` in the pre-change file). No refusal path could ever write to
the ledger, so a server refused on every protocol was re-selected on every run. On device
`vpn131251792` was selected in four consecutive runs (`attempt=12/13/14/15`) and no
`Skipping …: excluded for this session` line was ever emitted.

### After

```kotlin
// AutoModeController.kt:381 — new constant
const val NATIVE_BUSY_WEDGE_THRESHOLD = 2

// AutoModeController.kt:1556 — new per-server counter, reset at the top of each server
var nativeBusyThisServer = 0

// AutoModeController.kt:1658-1668 — inside the NATIVE_BUSY branch
nativeBusyThisServer++
if (!wedgedThisServer && nativeBusyThisServer >= NATIVE_BUSY_WEDGE_THRESHOLD) {
    adapter.log("[AUTO] NATIVE_BUSY x$nativeBusyThisServer on … recording a wedge …")
    recordWedge(server, key)
    wedgedThisServer = true
}
```

Threshold rationale: one refusal is routinely caused by the *previous* server's still-running
native operation, so a single refusal is evidence about the wrong endpoint; the second refusal on
the same server in the same run — after the slot was reported released in between — is not.
The existing `wedgedThisServer` flag keeps the "at most one wedge per server per run" invariant.

`WedgeTracker.EXCLUSION_THRESHOLD` is **unchanged at 2**. The fix makes a refusal *countable*; it
does not lower the bar for exclusion. `recordWedge`'s KDoc (`:1955-1972`) updated accordingly.

**Note (test-driven correction).** T-NEW-9's first draft asserted one dial per protocol; the
actual (correct) sequence is dial → refuse → slot wait → **retry the same protocol** → refuse →
next protocol. The assertion was corrected to the real sequence:
`TCP, TCP, UDP, UDP, good/TCP`. The behaviour was right; the expectation was not.

---

## §5 Instrumentation added (log only, no control flow change)

Two lines, both gated on evidence of the specific contradiction, so a healthy run pays nothing.

1. **Ownership vs tracker disagreement** (`AutoModeController.kt:1767-1775`), emitted after the
   existing `isOwnTunnelEstablished()` re-check returns `false` while the tracker reports
   `CONNECTED`:

   ```
   [AUTO] isOwnTunnelEstablished returned false despite native state CONNECTED
   (server=<host>, protocol=<id>, outcome=<OUTCOME>); investigation instrumentation, see v1.0.32
   ```

2. **Slot-budget exhaustion** (`AutoModeController.kt:1838-1848`), emitted immediately after the
   existing `Native slot not released within …` line:

   ```
   [AUTO] SLOT_UNRELEASED_DIAG waited=<ms>ms budget=<ms>ms outcome=<OUTCOME>
   protocol=<id> server=<host> tracker=<STATUS> ownTunnel=<bool> registry=<describe()>
   ```

   `waitedMs` is measured around the wait (`System.currentTimeMillis()` before/after), so the
   line also proves whether the wait really consumed the whole budget.

3. **New read-only adapter accessor** — `ConnectionAdapter.tunnelTrackerStatus()`
   (`AutoModeController.kt:207-224`, default `"UNKNOWN"`) implemented in
   `AndroidConnectionAdapter.kt:516-527` by reading `GlobalVpnTracker.currentStatus.name` inside
   `runCatching`. Used **only** for these log lines; it is never a decision input, for the reason
   `AndroidConnectionAdapter.isOwnTunnelEstablished` already documents (the tracker is driven by
   the same listener chain that can be late).

**Authorized-file note.** `AndroidConnectionAdapter.kt` is not in the phase brief's authorized
list. STEP 5 of the brief itself instructs: *"if the adapter does not already expose it, add a
read-only getter to the adapter — the adapter is a source file so this is fine"*. The brief's
procedural authority is followed over the list's omission; the change is +12 lines, read-only,
with no existing line modified. Flagged here explicitly for the operator's review.

Control flow is unchanged: the wait expression was hoisted into a `val slotReleased` with the
identical condition (`outcome == NATIVE_ERROR || awaitNativeSlotRelease(...)`), so the branch is
taken exactly as before.

---

## §6 Tests (T-NEW-7 … T-NEW-10)

| Test | Pins |
|------|------|
| **T-NEW-7** `productionBusyPredicateIncludesAConnectedTunnel` | `CONNECTED.isBusy == false` (the old behaviour, asserted so it cannot silently change) **and** `CONNECTED.isActiveOrBusy == true`; CONNECTING/DISCONNECTING busy, DISCONNECTED/ERROR not. Fails if the predicate loses `CONNECTED` or is re-pointed at `isBusy`. |
| **T-NEW-8** `engineUsesTheActiveOrBusyPredicateAndOpR6IsNotGated` | (a) `AutoModeEngine.kt` contains `isTunnelBusy = { … GlobalVpnTracker.isActiveOrBusy }` and does **not** contain the old `…GlobalVpnTracker.isBusy }`; (b) pressing while `Connected` still resumes at the next server and never logs `start refused`. Guards both the regression and an over-correction. |
| **T-NEW-9** `repeatedNativeBusyRecordsAWedgeAndRotates` | Two refusals on one server record a wedge (`NATIVE_BUSY x2 … recording a wedge`) and the run escapes to the next server; exact dial sequence asserted. |
| **T-NEW-10** `aSingleNativeBusyDoesNotRecordAWedge` | One refusal (then success on the retry) records nothing — no false positives, so healthy servers are not excluded. |

No existing test was deleted or modified. The only edit to a pre-existing section of the file is
none — all four are appended.

---

## §7 Spec edits (E1–E8)

| # | Edit | Location |
|---|------|----------|
| E1 | Header: version **1.0.32**, HEAD `9d9e43e…`, date 2026-10-18 | line 8-10 |
| E2 | §1.6.1 append-only note: v1.0.32 push state, BUG-A/BUG-B, instrumentation | after the v1.0.31 note |
| E3 | §6.8.3: `TIMED_OUT` row no longer claims to be "the only" wedge source; `NATIVE_BUSY` row references repeats; **new wedge-rule paragraph** with threshold 2 and the unchanged exclusion threshold | §6.8.3 |
| E4 | §6.8.6: **predicate table** (`CONNECTED`/`CONNECTING`/`DISCONNECTING` count as busy; `DISCONNECTED`/`ERROR` do not), `isActiveOrBusy` named as the reader, `isBusy` explicitly rejected, and the guard's scope (D-4 branch only; OP-R6 not gated) | §6.8.6 |
| E5 | **New §1.6.24** "AUTOCONNECT-FIX-2 — Two Device Bugs Closed (v1.0.32)", 8 numbered bullets (BUG-A, BUG-B, instrumentation, tests, APK, pending device re-verification, S1 deviation, no new lessons) + a device-status note | before §2 |
| E6 | §20 Changelog: `1.0.32` row | §20 Changelog |
| E7 | §20 Verification Status: row for §1.6.24 (HISTORICAL, with per-claim labels) | §20 Verification Status |
| E8 | §22 untouched — verified byte-identical | §22 |

§1.6.2–§1.6.23 verified byte-identical after the edits.

---

## §8 JVM test results

| Suite | Before | After | Delta |
|-------|--------|-------|-------|
| `:app:testProDebugUnitTest` | 796 / 0 fail / 0 err / 1 skip | **800 / 0 fail / 0 err / 1 skip** | +4 tests, skip unchanged (`HtmlParserOracleTest`) |
| `:SoftEtherClient:test` | 173 / 0 / 0 / 0 | **173 / 0 / 0 / 0** | unchanged |

Both run with `CI=true --rerun-tasks --no-build-cache`. Zero regressions.

One intermediate failure occurred and was fixed before the green run: T-NEW-9's dial-sequence
assertion (see §4). No production code was changed to accommodate a test.

---

## §9 Build result

- Task: `:app:assembleProDebug --rerun-tasks --no-build-cache` under `CI=true` → **BUILD SUCCESSFUL** (7 m 31 s, 114 tasks).
- APK: `app/build/outputs/apk/pro/debug/app-pro-debug.apk` (63,748,214 bytes)
- **SHA-256: `946CA9689885921EF1EEFAC7907BB9777EB79A73249EFC514BB1B97F8EBAA347`**
  (previous v1.0.31 APK was `1CCFC62E…3F1D`; the APK is not reproducible byte-for-byte across builds, so a changed hash is expected.)

---

## §10 Diff-scope

```
 M PROJECT_SPECIFICATION.md                                          (+132 / -4)
 M app/src/main/java/vn/unlimit/vpngate/automode/AndroidConnectionAdapter.kt   (+12)
 M app/src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt         (+144 / -? )
 M app/src/main/java/vn/unlimit/vpngate/automode/AutoModeEngine.kt             (+15 / -3)
 M app/src/main/java/vn/unlimit/vpngate/state/GlobalVpnTracker.kt              (+32)
 M app/src/test/java/vn/unlimit/vpngate/automode/AutoModeControllerTest.kt     (+166)
```

No other tracked file modified. `AUTOCONNECT_AUDIT_REPORT.md`,
`AUTOCONNECT_AUDIT_REPORT_v2.md`, `DEVICE_VERIFY_AUTOCONNECT_FIX_REPORT.md` and every other
report remain **untracked and unmodified**. The spec diff hunks are confined to: header,
§1.6.1 (append), new §1.6.24, §6.8.3, §6.8.6, §20 Changelog, §20 Verification Status.

---

## §11 §22 byte-identity

`awk '/^## 22\./{f=1} f{print}'` on the working tree vs `git show HEAD:` —

- **99 lines**, **6,504 bytes**
- `diff -q` → identical — **§22 IDENTICAL** ✅

---

## §12 Negative assurance

- No device command of any kind (no `adb`, no install, no logcat).
- No build or test without `CI=true`.
- No `git add -A` / `git add .` — staging is by exact path.
- No `--force`, no `reset`, `clean`, `stash`, `checkout`, `restore`.
- No tag operation.
- No test deleted or modified (four appended).
- No existing report or audit file touched.
- Slot-non-release **not** fixed — instrumented only, per the brief.
- C5 not executed.
- `D:\PMC\Documents\Android\SoftEthergai` not touched.
- `build.gradle`, manifests and native C code untouched.

---

## §13 Recommendation for the next phase (proposed only — NOT executed)

A device phase `NATIVE-SLOT-INVESTIGATION-v1.0.33` should:

1. Install this build and reproduce the S5 stall, then read the two new lines. The decisive
   signal is `SLOT_UNRELEASED_DIAG`'s `ownTunnel=` and `tracker=` pair:
   - `tracker=CONNECTED, ownTunnel=false` → the ownership check is the fault (network
     capabilities not yet published, or the UID/transport test mis-classifying). Trace
     `OwnedVpnDecision` against `ConnectivityManager.allNetworks` at that instant.
   - `tracker=CONNECTED, ownTunnel=true` → ownership is right and the slot is genuinely held;
     the fault is in the registry's release path (`NativeLifetimeRegistry`), and the
     `registry=` descriptor shows which attempt is holding it.
   - `tracker≠CONNECTED` → the tunnel never actually came up despite the DHCP log; the fault is
     earlier, in the SoftEther session state machine.
2. Re-run S3 (D-4 refusal) and S6 (last-server edge) — BUG-A changes S3's expected result to a
   refusal with `[AUTO] start refused: a tunnel is already active` and an intact manual tunnel.
3. Re-run S1 only if the device preference is reset to its 12 s default by the operator; it is a
   device-state item, not a code item.
4. Do **not** treat the rotation defect as closed until a run is observed to reach `Trying #2`
   after a refused or stalled server — T-NEW-9 proves the ledger write, not the end-to-end escape.

STOP.
