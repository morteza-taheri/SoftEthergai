# AUTOCONNECT-FIX-7 — NATIVE_BUSY Classification + No-Terminal Contract (v1.0.37)

**Phase:** AUTOCONNECT-FIX-7
**Repository:** `C:\Dev\SoftEthergai`
**Branch:** `main`
**Device:** DETACHED (device-free phase — no `adb` command was issued)
**Baseline:** `eacc5fad4fdb8e8b231349e87d0f35af578a3941` (v1.0.36)
**Spec:** v1.0.36 → **v1.0.37**
**Date:** 2026-10-19

---

## §1. Verdict

**PASS — with one labelled naming deviation and one environment fault.**

The v1.0.36 regression is closed at the classification level, and the operator contract that
§6.8.4 has stated since v1.0.31 is now what the code actually does. A `NATIVE_BUSY` no longer ends a
run under any circumstance; a slot wait that expires moves to the next server; the only exhausted-list
terminal is `ERROR_NO_SERVER`.

Evidence labels used throughout: **SOURCE CODE**, **TEST**, **PHYSICAL DEVICE** (cited from the
previous phase's report, not re-measured here), **UNKNOWN**.

| Claim | Status | Evidence |
|---|---|---|
| A refusal is no longer a run terminal | DONE | SOURCE CODE + TEST |
| A slot-wait expiry moves to the next server (both branches) | DONE | SOURCE CODE + TEST |
| `ERROR_NATIVE_UNRESOLVED` is published by no path | DONE | SOURCE CODE + TEST |
| Eight tests rewritten, none deleted, none trivial | DONE | TEST |
| Four new tests added (T-NEW-18..21) | DONE | TEST |
| `:app` 812/0/0/1 · `:SoftEtherClient` 173/0/0/0 | DONE | TEST |
| §22 byte-identical | DONE | 99 lines / `adf12858…020e` |
| Device behaviour of the new contract | **NOT VERIFIED** | UNKNOWN |

---

## §2. Pre-conditions

Checked at phase start (STEP 1):

| Check | Expected | Observed | Result |
|---|---|---|---|
| `git rev-parse HEAD` | `eacc5fad…` | `eacc5fad4fdb8e8b231349e87d0f35af578a3941` | PASS |
| `git rev-parse origin/main` | equal to HEAD | equal | PASS |
| `git branch --show-current` | `main` | `main` | PASS |
| Spec version | `1.0.36` | `1.0.36` | PASS |
| Tag count | 5 | 5 | PASS |
| Tracked modifications | exactly the two dirty source files | `AndroidConnectionAdapter.kt`, `AutoModeController.kt` | PASS |

The working tree carried Fix-1 and Fix-2 from the previous (stopped) attempt. STEP 2 re-verified
those diffs before any further edit:

- the adapter diff contains `lastArmedBusyRefused` and nothing else (53 added lines);
- the controller diff contains `moving on (no terminal, per §6.8.4)` at **both** sites;
- no test file was modified at that point.

**No divergence** from the brief's described mechanism was found. Evidence: SOURCE CODE.

---

## §3. The v1.0.36 regression and its evidence

`DEVICE_VERIFY_AUTOCONNECT_FIX_6_REPORT.md` §7.1 (PHYSICAL DEVICE, previous phase):

1. **2 of 5** runs terminated with `ERROR_NATIVE_UNRESOLVED` on the **first** server.
2. The controller gave up **431 ms** after a native attempt had started, and **7.7 s before** the
   native stack produced its own verdict (`VPNCONNECT watermark failed — server may not be
   SoftEther`).
3. In both runs the diagnostic showed `ownTunnel=false` and `tracker=CONNECTING` — i.e. the endpoint
   was being **actively contacted**, not refused.
4. A false wedge was recorded and `vpn611507027` was excluded for the session, on a server the log
   shows was being worked on.

Mechanism: v1.0.36's cap (`min(nativeSlotAwaitMs, T)`, with `T = 5 s` on that device) made the
slot-wait expiry arrive while the native socket ladder (4000 ms) was still running. The expiry was a
run terminal, so a slow-but-recoverable endpoint became a whole-run failure.

**The classification defect underneath it.** `awaitArmedOutcome` ended with

```kotlin
return if (isNativeSlotBusy()) AttemptOutcome.NATIVE_BUSY else AttemptOutcome.TIMED_OUT
```

`isNativeSlotBusy()` answers "busy" in **both** situations that matter:

| Case | What happened | Server contacted? | Correct outcome |
|---|---|---|---|
| 1 | Our **own** attempt timed out; its native operation is still unwinding | yes | `TIMED_OUT` |
| 2 | The start was **refused** because a *previous* operation owns the slot | no | `NATIVE_BUSY` |

Every case-1 timeout was therefore labelled `NATIVE_BUSY`, and the `NATIVE_BUSY` branch's expiry was
a terminal. Evidence: SOURCE CODE + PHYSICAL DEVICE.

---

## §4. Fix-1 (adapter classification)

`app/src/main/java/vn/unlimit/vpngate/automode/AndroidConnectionAdapter.kt`

A per-attempt verdict is captured from the deferred that can only mean "refused":

```kotlin
@Volatile
private var lastArmedBusyRefused = false
```

- **Reset** in `armTunnelWatch`, immediately after `lastArmedFailure = false` (`:349`), so a stale
  `true` from a previous attempt can never classify this one.
- **Captured** in `awaitArmedTunnel`'s `finally` (`:406`), next to the existing `lastArmedFailure`
  assignment, because the deferreds are dropped there:

  ```kotlin
  lastArmedBusyRefused = armedNativeBusy?.isCompleted == true
  ```

- **Used** in `awaitArmedOutcome` (`:497`):

  ```kotlin
  if (lastArmedBusyRefused) return AutoModeController.AttemptOutcome.NATIVE_BUSY
  return AutoModeController.AttemptOutcome.TIMED_OUT
  ```

**Why the deferred is exact.** `armedNativeBusy` is completed only by
`TunnelStateWatcher.onSoftEtherNativeBusy` (`TunnelStateWatcher.kt:301-305`), which the service fires
only when it **refuses** a start. It cannot fire for our own attempt, so `isCompleted` distinguishes
the two cases that `isNativeSlotBusy()` conflated.

`isNativeSlotBusy()` is **kept** — it still serves the admission checks at `:474`, `:494`, `:498`,
`:506` — but it no longer decides the outcome. Evidence: SOURCE CODE.

---

## §5. Fix-2 (controller — no terminal on a slot-wait timeout)

`app/src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt`

Both sites that could end a run on a slot-wait expiry now move on.

**Site A — `NATIVE_BUSY` branch (`:1774`).** Removed:

```kotlin
giveUpWithUnresolvedNativeOperation()
setState(AutoModeState.Error(ERROR_NATIVE_UNRESOLVED))
job = null
return
```

Replaced with the move-on log, the pre-existing `SLOT_UNRELEASED_DIAG` line, one `disconnect()` and
`break`. The v1.0.34 **terminal wedge** was withdrawn with it, with the reasoning recorded in place:
since Fix-1 a `NATIVE_BUSY` means the start was refused, so the server was never contacted and a
strike would be evidence about the wrong endpoint.

**Site B — post-attempt branch (`:1991`).** The same terminal replaced by the same form. The
`disconnect()` already issued before the wait is not removed; the second call after the expiry is
addressed to the operation that outlived it, and the comment says so.

Both sites now emit, verbatim:

```
[AUTO] Slot-wait expired on <protocol>; moving on (no terminal, per §6.8.4)
```

**Unchanged on purpose:** `NATIVE_SLOT_AWAIT_MS` (`30_000L`), `NATIVE_SLOT_AWAIT_MAX_MS`
(`60_000L`), both v1.0.36 caps, the v1.0.35 `lastTeardownWasResolved` gate, the retry mechanism, and
`isOwnTunnelEstablished()` at the top of the `NATIVE_BUSY` branch. Evidence: SOURCE CODE.

---

## §6. Fix-3 (contract — `ERROR_NATIVE_UNRESOLVED` is not a terminal)

- The constant `ERROR_NATIVE_UNRESOLVED` is **kept** (`AutoModeController.kt:436`); it is referenced
  by the adapter's KDoc and by §1.6.24–§1.6.28.
- **No path publishes it.** Verified by grep: the only remaining occurrences in `app/src/main` are
  the declaration, a KDoc cross-reference, and explanatory comments.
- `giveUpWithUnresolvedNativeOperation()` is **kept but unreferenced**, with a note at the call site
  that it is no longer on any live path. It was not deleted: it encodes the v1.0.32 device evidence
  (a wedged operation must be asked to tear down), and deleting it would erase that record.
- `ERROR_NO_SERVER` is now the **only** exhausted-list terminal; §6.8.4's table row for the removed
  terminal was deleted and the table renumbered 1–7.

Evidence: SOURCE CODE.

---

## §7. The eight rewritten tests — per test

All eight were rewritten **in place**, none deleted, none reduced to a trivial pass, each under the
operator's explicit authorization (STOP Option A). Each keeps its original intent; what changed is the
contract it pins.

### 7.1 `AutoModeControllerTest.kt`

| Old | New | Before → after |
|---|---|---|
| `aSlotThatNeverReleasesEndsTheRun` | `aSlotThatNeverReleasesMovesOnToTheNextServer` | Terminal `ERROR_NATIVE_UNRESOLVED` + exactly 1 dial → terminal `ERROR_NO_SERVER`, the refused protocol left behind, and **both** servers dialled once each (`[always-busy/TCP, also-busy/TCP]`), with the move-on line and `All compatible servers failed` asserted. |
| `aNativeBusyTerminalRecordsAWedgeThatSurvivesAcrossRuns` (T-NEW-11) | `aTimedOutWedgeAccumulatesAcrossRunsAndExcludesTheServer` | Three NATIVE_BUSY-terminal runs → three **TIMED_OUT** runs: wedge 1 → 2 → exclusion. The ledger and threshold are unchanged; only the feeding outcome changed, because the terminal it depended on no longer exists. |

**Naming deviation (labelled).** The brief proposed `…MovesOnToTheNextProtocol`. The controller's
`break` leaves the **protocol** loop, so the rotation continues at the next **server** — the refused
server's second protocol is deliberately never dialled. The test is named for what the code does, and
its assertion pins the absence of that second dial. The operator may rename it if the brief's wording
is preferred over precision.

### 7.2 `AutoModeNativeBusyTest.kt`

| Old | New | Before → after |
|---|---|---|
| `native-busy refusal stops the run instead of blaming the server` | `native-busy refusal does not stop the run and does not record a false wedge` | Asserted the terminal *was* `ERROR_NATIVE_UNRESOLVED`. Now asserts `ERROR_NO_SERVER`, that the removed code is never published, and — the original intent, strengthened — that **no** refused server receives a wedge (`wedgeCountFor == 0` for all three). |
| `a slot that never releases still does not rotate to a second server` | `a slot that never releases does rotate to the next server` | `connects.size == 1` → all three servers dialled once each, in order, and `slotWaits >= 3` proves the wait drives the rotation. |
| `native-busy refusal does not advance through the protocols of one server` | `native-busy refusal advances past the refused protocol instead of retrying it` | `disconnects == 0` and `recoveryTeardowns == 1` → `disconnects >= 1`, no retry of the refused protocol, no UDP dial, move-on line asserted. |
| `native-busy refusal never reports ERROR_NO_SERVER` | `a single native-busy refusal does not immediately report ERROR_NO_SERVER` | The old premise was "never `ERROR_NO_SERVER` because it always ended in the other terminal". Now: one refusal, a healthy second server → `Connected`, and `All compatible servers failed` must be absent. |
| `a busy refusal on the last server is not hidden by a list exhaustion` | `a busy refusal on the last server is reported as list exhaustion` | `ERROR_NATIVE_UNRESOLVED` → `ERROR_NO_SERVER`, with `All compatible servers failed` and the move-on line both asserted. |
| `a give-up issues a recovery teardown so the wedged slot can be reclaimed` | `a slot-wait timeout does not issue a recovery teardown` | `recoveryTeardowns == 1`, `disconnects == 0` → `recoveryTeardowns == 0`, `disconnects >= 1`, no `Requesting recovery teardown` in the log. The intent — the still-held operation must be asked to stop — survives via the rotation primitive. |

**Test-double note.** No member was added to `BusyAwareAdapter`. The one case that needed "the stuck
operation finishes after this wait" (7.2 row 4) uses an anonymous subclass overriding
`awaitNativeSlotRelease`, so every other test in the file keeps the exact double it had. Evidence:
TEST.

---

## §8. The four new tests

| ID | Test | Pins |
|---|---|---|
| T-NEW-18 | `anOwnOperationTimeoutIsClassifiedAsTimedOutNotBusy` (`AutoModeControllerTest`) | A `TIMED_OUT` walks both protocols, records exactly one wedge, and never emits `Slot-wait expired` or any `NATIVE_BUSY` line. This is the controller-visible half of Fix-1; the adapter half needs a `Context` and is not JVM-testable. |
| T-NEW-19 | `a slot-wait timeout moves on to the next server instead of terminating` (`AutoModeNativeBusyTest`) | The **exact** line `[AUTO] Slot-wait expired on SOFTETHER_TCP; moving on (no terminal, per §6.8.4)`, the advance to the second server, and that the run is not ended by the refusal. |
| T-NEW-20 | `every refusal is absorbed and the list ends with ERROR_NO_SERVER` (`AutoModeNativeBusyTest`) | Twelve refused candidates: twelve dials, twelve slot waits, **one** terminal, and no `native_operation_unresolved` anywhere. |
| T-NEW-21 | `skipWhileConnectedDialsTheNextServerAndNeverRestartsFromTheTop` (`AutoModeControllerTest`) | OP-R6 **ordering**: the first dial after the skip is `s2`, `s1` is never dialled again, and the user-intent line is logged. The pre-existing `skipWhileConnectedDisconnectsAndTriesNext` pinned counts only. |

Evidence: TEST.

---

## §9. The hidden contradiction, and its resolution

§6.8.4 has said, since v1.0.31: **"AutoConnect does not stop for `NATIVE_BUSY`."**

`AutoModeNativeBusyTest.kt` has asserted the opposite since v1.0.28: a refusal **is** a terminal, the
run must **not** rotate, exactly **one** recovery teardown must be issued and **zero** disconnects may
be. Six tests encoded that.

Both were green for eight revisions because the code implemented the tests, not the contract. Nothing
in the suite could detect the disagreement: the tests *were* the specification of that path.

v1.0.37 resolves it in the direction §6.8.4 already required, and rewrites the six tests so the suite
now states the same thing as the specification. This is recorded as §1.6.29 bullet 8 and, as a
methodology matter, as an instance of the existing §1.6.24 bullet 8 rule (a passing test proves only
that the code matches the test) — **no new lesson was added**. Evidence: SOURCE CODE + TEST.

---

## §10. JVM tests (before → after)

| Task | Before (v1.0.36) | After (v1.0.37) | Delta |
|---|---|---|---|
| `:app:testProDebugUnitTest` | 808 / 0 / 0 / 1 | **812 / 0 / 0 / 1** | +4 (the four new tests; the eight rewrites change no count; the single skip is the pre-existing `HtmlParserOracleTest` assumption) |
| `:SoftEtherClient:test` | 173 / 0 / 0 / 0 | **173 / 0 / 0 / 0** | 0 |

Intermediate check during the phase: the `automode` package alone went from **221 tests / 8 failures**
(before the rewrites) to **225 / 0**, which isolated the damage to exactly the eight authorized tests
and confirmed no collateral breakage.

Every count above was aggregated from `build/test-results/**/*.xml` rather than read from console
output. Evidence: TEST.

---

## §11. Build and APK

```
CI=true :app:assembleProDebug --rerun-tasks --no-build-cache   →  BUILD SUCCESSFUL (114 tasks)
```

| Field | Value |
|---|---|
| APK | `app/build/outputs/apk/pro/debug/app-pro-debug.apk` |
| Size | 63,755,062 bytes |
| SHA-256 | `4FE4417469C7C7829DED3C7B723705DC77678B28FAA14345E057108BE9EB6E68` |

It differs from v1.0.36's `BE345075…ECF249` as expected: the APK embeds the compiled Kotlin, and no
native source changed. Evidence: TEST.

**Environment faults (not source defects), disclosed:**

1. Two `:app:testProDebugUnitTest` invocations aborted before running anything with
   `java.net.BindException: Address already in use` from Gradle's `FileLockContentionHandler`. No JVM
   process was left behind (`Get-Process java` empty; `gradlew --stop` reported no daemons); the third
   attempt ran normally. Transient environment condition.
2. The background-task wrapper reports `exit code 1` for every Gradle run because Gradle writes
   progress to stderr and PowerShell raises `NativeCommandError`. **The build result line is
   authoritative**, and every count was re-derived from the XML.

---

## §12. Diff-scope

`git status --short` at STEP 7 — exactly the authorized files:

```
 M app/src/main/java/vn/unlimit/vpngate/automode/AndroidConnectionAdapter.kt   (+53 / −5)
 M app/src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt         (+107 / −58)
 M app/src/test/java/vn/unlimit/vpngate/automode/AutoModeControllerTest.kt     (+225 / −40)
 M app/src/test/java/vn/unlimit/vpngate/automode/AutoModeNativeBusyTest.kt     (+329 / −67)
```

- **No test file deleted** (`git diff --diff-filter=D -- app/src/test` is empty).
- One import added to `AutoModeControllerTest.kt` (`org.junit.Assert.assertNotEquals`), required by
  the successor test; no other import changed.
- Untracked directories (`test-evidence/`, `scripts/`, `emergency/`, `.workbuddy-ai/`) were left
  untouched and **not** staged. No `git add -A` / `git add .` was used at any point.

Evidence: SOURCE CODE (git).

---

## §13. §22 byte-identity

| Check | Value |
|---|---|
| Lines | 99 (baseline 99) |
| SHA-256 | `adf12858b72ed13742252c512a8728e9141e415cc55d09c6409af4e8e622020e` (baseline identical) |

§22 was not opened for editing. §1.6.2–§1.6.28, §12.5.1 and §12.5.2 were likewise not modified: the
new content is §1.6.29, the §1.6.1 push-state note, three §6.8.3 passages, the §6.8.4 table, and the
§20 changelog/verification rows. Evidence: SOURCE CODE (byte comparison).

---

## §14. Negative assurance

- **No device command** of any kind was issued; the device was DETACHED throughout.
- **No tag operation** was performed; the tag set is unchanged at **5** locally and 5 on the remote.
- **No `--force`**, no `reset`, no `clean`, no `stash`, no `checkout`, no `restore`.
- **No `git add -A` / `git add .`**; every `git add` named its file.
- **No test deleted.** Eight rewritten, four added, none other touched.
- **No trivial pass** was introduced: every rewritten test asserts at least two independent
  observable facts, and none contains an empty body or `assertTrue(true)`.
- **Fix-A and the native sources were not touched.** `softether_protocol.c` is byte-identical.
- **`build.gradle`, manifests, `NATIVE_SLOT_AWAIT_MS` and `NATIVE_SLOT_AWAIT_MAX_MS` untouched.**
- `D:\PMC\Documents\Android\SoftEthergai` was not touched; **C5 was not executed**.

---

## §15. What remains UNVERIFIED, and the device re-verify plan

**UNVERIFIED — Fix-A.** The native RUDP fallback handshake bound (`softether_protocol.c:2935`) is
still unexercised on hardware: no NAT-T rendezvous has occurred in any captured run. It is untouched
by this phase.

**UNVERIFIED — the contract itself on hardware.** This phase is device-free. What is proven is that
the code and the suite now agree with §6.8.4; what is *not* proven is that a real device now behaves
that way. Specifically unobserved:

1. that a case-1 timeout is classified `TIMED_OUT` on hardware (Fix-1's discriminator depends on
   `onSoftEtherNativeBusy` firing only on refusal — proven by SOURCE CODE, not by a device run);
2. that a slot-wait expiry followed by `break` reaches the next server with a genuinely free slot;
3. that a full list of refusals now ends in `ERROR_NO_SERVER` and not in a stop.

**Proposed device re-verify (next phase, device-attached):**

1. Install the v1.0.37 APK with `-r`; keep `SETTING_AUTO_MODE_TIMEOUT_SECONDS = 5` so the v1.0.36
   conditions are reproduced exactly.
2. Run AutoConnect 5–8 times, capturing `logcat` per run, including the two endpoints that produced
   the §7.1 terminals (`vpn611507027` among them).
3. Assert per run: **zero** `ERROR_NATIVE_UNRESOLVED`; at least one `Slot-wait expired … moving on`
   with a subsequent dial to a different server; no `Recording a wedge on the NATIVE_BUSY terminal`;
   a run that ends in error ends with `All compatible servers failed`.
4. Assert the negative: no server is excluded for the session unless it produced `TIMED_OUT` twice.
5. Classify A–E as in the previous device-verify phases, and record whether Fix-A's RUDP site was
   reached at all (`[TLS] handshake entry (RUDP)`).
