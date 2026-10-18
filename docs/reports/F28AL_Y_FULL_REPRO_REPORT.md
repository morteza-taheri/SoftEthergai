# F.28AL-Y-FULL-REPRO REPORT (corrected — F.28AL-Y-FULL-REPRO-DOC)

> **Document status:** This revision supersedes the original verdict wording.
> The original report stated "NOT REPRODUCED" without distinguishing what that
> outcome does and does not prove. This corrected version records the result
> honestly as **DIAGNOSIS INCONCLUSIVE** and clarifies the relationship to
> F.28AL-Y-AUTOCONNECT-FIX. Documentation-only update; no test or device
> interaction was performed for this revision.

## Objective (original)

Reproduce the F.28Z fail-closed "Start refused: native slot" error (Mode B) by deliberately creating a stuck teardown state via:
1. Manual Connect to a server
2. Immediate Disconnect
3. Immediate AutoConnect

## Device Info (original)

- Device: 5585fecc (Xiaomi 2201117TG)
- App: F.28AL-U debug build 2.4.10-dev
- Target: Create race condition where native slot is being destroyed while attempting to create a new slot

---

### 1. Executive Verdict (corrected)

**DIAGNOSIS INCONCLUSIVE — NOT REPRODUCED IN THIS ENVIRONMENT.**

- **Mode B (slot refusal, "Start refused: native slot") was NOT reproduced.** The fail-closed refusal pattern did not appear in any of the 3 test attempts.
- **Mode A (network failure) was the observed outcome.** In R2 the AutoConnect attempt failed with a TCP connection error — a network-layer failure, not a slot refusal. The test session therefore exercised a different failure mode than the one being sought.
- The absence of Mode B in this environment proves nothing about its existence: the test environment did not contain the timing/internal-state preconditions, so the diagnosis remains **inconclusive**, not "resolved" or "cannot occur".

### 2. What Was Proven / Not Proven

| Proven | Not proven |
|---|---|
| Device runs the F.28AL-U debug build (2.4.10-dev). | The exact conditions that trigger Mode B (slot refusal). |
| Manual Connect works without incident. | Whether the retry fix (F.28AL-Y-AUTOCONNECT-FIX) is sufficient. |
| Normal teardown releases the native slot cleanly (log markers observed). | Whether the pattern is deterministic, intermittent, or environment-specific. |
| No refusal pattern appeared in 3 test attempts. | — |

Supporting observations from the original session (slot ownership timeline), retained as context only:
- Manual Connect creates the native slot with atomic ownership.
- Disconnect/cleanup properly released the process-wide slot (R1 logs showed "Released process-wide native slot for this attempt" and "Native cleanup confirmed: structure destroyed and slot released").
- The slot remained free until the next AutoConnect attempt; no teardown/create overlap was observed in this environment.

### 3. R3 Execution Issue (honest record)

**R3, as executed, did NOT match the intended procedure.**

- Intended procedure: rapid-fire Manual Connect/Disconnect — i.e., Disconnect → short delay → a **second Manual Connect** to force overlapping teardown/create.
- Executed procedure: Disconnect (800,1965) → 1s delay → tap **"Server List" (802,792)** → 5s delay. The agent tapped "Server List" instead of performing a second Manual Connect, so R3 never issued the connection attempt that the scenario required.
- Consequently R3's "no refusal observed" result is **not evidence** about the race condition; it only shows that navigating to the Server List produces no slot refusal (which was never in doubt).
- **Deferred item:** the correct R3 procedure (second Manual Connect immediately after Disconnect) should be re-attempted only in a future phase, and only if new evidence makes further reproduction worthwhile (see §6 — reproduction is currently stopped).

### 4. Missing Evidence

The original session did not capture the following; the previous report did not call this out:

- **No log excerpts were provided per attempt.** Only summarized observations exist; the underlying logcat capture per attempt (R1/R2/R3) was not archived with timestamps.
- **No marker search results with timestamps.** The searches for "Start refused: native slot" / "Native slot still owned" markers were reported as negative, but the actual search output (query, time window, result count) was not attached.
- **No post-attempt device state snapshot.** No record of service state, slot ownership state, or process liveness was taken after each attempt.

Because of these gaps, even the Mode A observation in R2 (TCP connection error) cannot be root-caused further from the available record.

### 5. Independence from AUTOCONNECT-FIX

**This REPRO result must NOT be interpreted as validation of F.28AL-Y-AUTOCONNECT-FIX.** Neither the fix's code nor its behavior was exercised, measured, or observed during any repro attempt; the attempts targeted the original refusal pattern and failed to trigger it at all. The retry fix must be evaluated **independently**, on its own JVM unit tests and design review, per its own approved plan. A "not reproduced" outcome in an environment where the trigger conditions were absent says nothing whatsoever about whether the retry logic is correct, sufficient, or safe. Absence of a bug's trigger is not evidence that a fix for that bug works.

### 6. Recommendation

1. **STOP reproduction attempts.** Three attempts in this environment yielded no Mode B instance; further blind attempts consume device time without information value.
2. **Continue with F.28AL-Y-AUTOCONNECT-FIX independently** — its design review is already approved. Evaluate it on its own JVM tests and design review, not on repro outcomes.
3. **Defer any further reproduction** until new evidence appears (e.g., a field log or diagnostic capture containing actual "Start refused: native slot" markers with timestamps and preceding teardown logs). If such evidence arrives, the corrected R3 procedure (§3) may be re-attempted as a dedicated, properly instrumented phase with per-attempt log excerpts, marker searches, and device-state snapshots.

### 7. Integrity Check

| Area | Result |
|---|---|
| Source | **NONE** |
| Test | **NONE** (no test was re-run for this documentation phase) |
| Gradle | **NONE** |
| Device | **NONE** (no device interaction) |
| CI | **NONE** |
| Commit | **NONE** (no commit, tag, push, or release) |

This phase (F.28AL-Y-FULL-REPRO-DOC) is documentation-only: it edited this report file and nothing else. No existing user work was deleted.

---

## Files

- F28AL_Y_FULL_REPRO_REPORT.md (this file — updated in place by F.28AL-Y-FULL-REPRO-DOC)
- (original session test logs "available upon request" per the original report; not archived — see §4)
