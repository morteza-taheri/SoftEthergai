# F.28AL-AUTOCONNECT-BUG3-FIX — Fix SoftEtherClient Handle Guard

**Date:** 2026-10-12
**Phase:** F.28AL-AUTOCONNECT-BUG3-FIX
**Scope:** Source + test edits only. No rebuild/install/device/CI/commit. Fixes the handle-guard root cause identified in F.28AL-AUTOCONNECT-BUG3-AUDIT.
**Baseline HEAD:** `071462816b1eef7e7f7459483fcd69909938cd11` (unchanged at end of phase).

---

## 1. Executive Verdict

**FIX APPLIED — ALL TESTS PASS**

The root cause was a **handle-source mismatch at the `SoftEtherClient` guard**: `setTimeout(timeoutMs)` and `setHalfConnection(half)` resolved the handle from the client's own **private `nativeHandle`** field, which is never assigned a real handle on the controller-managed connect path — the controller keeps its handle in its **own** field and publishes it to `externalHandle` only **after** `nativeConnectWithHub` returns, i.e. after `setTimeout` must run. The calls therefore silently no-op'd.

**Correction applied (as directed):** the pure-resolution variant (D1a) was **rejected as insufficient** — at `ConnectionController.kt:530` *both* `client.nativeHandle` **and** `client.externalHandle` are still `0L`, so resolution alone would not fix the timeout. The **explicit-handle variant (D1b)** was implemented: new `setTimeout(handle, timeoutMs)` / `setHalfConnection(handle, half)` overloads, with all four controller call sites passing the controller's own `nativeHandle`.

**Test result:** `:SoftEtherClient:testDebugUnitTest` → **133 tests, 0 failures, 0 errors, 0 skipped**; `:app:testProDebugUnitTest --tests "vn.unlimit.vpngate.automode.*"` → **183 tests, 0 failures, 0 errors, 0 skipped** (matches the pre-fix baseline exactly — no regression).

---

## 2. Pre-Fix Verification (P3)

Bug confirmed present at every anchor before the edit:

| Anchor | Verified state |
|---|---|
| `SoftEtherClient.kt:141` | `fun setTimeout(timeoutMs: Int) { if (nativeHandle != 0L) { nativeSetOption(nativeHandle, OPTION_TIMEOUT, timeoutMs.toLong()) } }` — guards on the private field only |
| `SoftEtherClient.kt:29` | `fun setHalfConnection(halfConnection: Boolean) { if (nativeHandle == 0L) return; nativeSetHalfConnection(nativeHandle, halfConnection) }` — same pattern |
| `ConnectionController.kt:530` | `client.setTimeout(config.connectTimeoutMs)` — legacy single-arg call |
| `ConnectionController.kt:1192` | `client.setTimeout(config.connectTimeoutMs)` — legacy single-arg call |
| `ConnectionController.kt:578` | `client.setHalfConnection(!fullDuplex)` — legacy single-arg call |
| `ConnectionController.kt:1209` | `client.setHalfConnection(!reconnectFullDuplex)` — legacy single-arg call |
| `SoftEtherClient.kt:13` | `private var nativeHandle: Long = 0` — never assigned a real handle |
| `SoftEtherClient.kt:18` | `@Volatile var externalHandle: Long = 0` — published post-connect only |
| `ConnectionController.kt:179` | `private var nativeHandle: Long` — the controller's **own** field |

P1 (`HEAD` = `07146281…`) and P2 (audit read in full) confirmed.

---

## 3. SoftEtherClient.kt Changes

### New overload — timeout

```kotlin
fun setTimeout(handle: Long, timeoutMs: Int) {
    if (handle != 0L) {
        nativeSetOption(handle, OPTION_TIMEOUT, timeoutMs.toLong())
    }
}
```

### New overload — duplex

```kotlin
fun setHalfConnection(handle: Long, halfConnection: Boolean) {
    if (handle == 0L) return
    nativeSetHalfConnection(handle, halfConnection)
}
```

### `@Deprecated` annotation text on the legacy methods

`setTimeout(timeoutMs: Int)`:
```kotlin
@Deprecated(
    message = "Guards on the client's private handle, which is 0 on the " +
        "controller-managed connect path. Use setTimeout(handle, timeoutMs).",
    replaceWith = ReplaceWith("setTimeout(handle, timeoutMs)"),
)
```

`setHalfConnection(halfConnection: Boolean)`:
```kotlin
@Deprecated(
    message = "Guards on the client's private handle, which is 0 on the " +
        "controller-managed connect path. Use setHalfConnection(handle, halfConnection).",
    replaceWith = ReplaceWith("setHalfConnection(handle, halfConnection)"),
)
```

### Old method bodies

- `setHalfConnection(halfConnection: Boolean)` — body **unchanged** (`if (nativeHandle == 0L) return; nativeSetHalfConnection(nativeHandle, halfConnection)`); only the KDoc/@Deprecated anchor was added (F3).
- `setTimeout(timeoutMs: Int)` — body remains the resolution-based lookup for backward compatibility, per F1's explicit instruction: `val h = externalHandle.takeIf { it != 0L } ?: nativeHandle; if (h != 0L) nativeSetOption(h, OPTION_TIMEOUT, timeoutMs.toLong())`. Only the guard expression changed from the bare field to the resolution (as specified); the semantics and method signature are otherwise identical.

### Option code / native binding used

- **`setTimeout` → `nativeSetOption(handle, OPTION_TIMEOUT, …)`**; `OPTION_TIMEOUT = 1` (`SoftEtherClient.kt` companion, unchanged).
- **`setHalfConnection` does NOT use `nativeSetOption`.** Its body calls the dedicated binding `nativeSetHalfConnection(handle, halfConnection: Boolean)` (`external fun nativeSetHalfConnection(handle: Long, halfConnection: Boolean)`). The new overload preserves that exact call; no option code is involved for duplex. (F4 satisfied: `nativeSetOption`'s signature and all native code are untouched.)

---

## 4. ConnectionController.kt Changes

Four call sites updated to the explicit-handle overloads. The controller's own `nativeHandle` field (`ConnectionController.kt:179`) is passed — the same value already handed to `nativeConnectWithHub` at `:588`/`:1221` and to `nativeSetOption` at `:549`/`:555`. **No new handle source was introduced.**

| Site | Before | After |
|---|---|---|
| Initial timeout (~530) | `client.setTimeout(config.connectTimeoutMs)` | `client.setTimeout(nativeHandle, config.connectTimeoutMs)` |
| Initial duplex (~578) | `client.setHalfConnection(!fullDuplex)` | `client.setHalfConnection(nativeHandle, !fullDuplex)` |
| Reconnect timeout (~1192) | `client.setTimeout(config.connectTimeoutMs)` | `client.setTimeout(nativeHandle, config.connectTimeoutMs)` |
| Reconnect duplex (~1209) | `client.setHalfConnection(!reconnectFullDuplex)` | `client.setHalfConnection(nativeHandle, !reconnectFullDuplex)` |

**Ordering preserved (F5):**

- Initial path: `setTimeout(nativeHandle, …)` at **line 535** precedes `nativeConnectWithHub(` at **line 588**.
- Reconnect path: `setTimeout(nativeHandle, …)` at **line 1197** precedes `nativeConnectWithHub(` at **line 1221**.

Grep confirms **no** legacy single-arg `setTimeout`/`setHalfConnection` call remains in `ConnectionController.kt`.

---

## 5. Tests Added

New file: `SoftEtherClient/src/test/java/vn/unlimit/softether/client/SoftEtherClientHandleGuardTest.kt` (4 tests).

**Approach: source-shape assertions** (the established repo pattern, e.g. `AutoModeEndToEndTest.connectSoftEtherPlumbsAttemptTimeoutMs`). A mock of `nativeSetOption` is not available: `SoftEtherClient`'s `init` block calls `System.loadLibrary("softether")`, so the type cannot be instantiated in a JVM unit test, and its native methods are `external fun` bindings with no injectable seam. The fix is a source-level handle-arity/guard change, so pinning the shape is sufficient and precise.

| Test | Covers | Assertion | Result |
|---|---|---|---|
| `explicitHandleSetTimeoutReachesNative` (T1) | new timeout overload | declares `fun setTimeout(handle: Long, timeoutMs: Int)`; guards `if (handle != 0L)`; forwards `nativeSetOption(handle, OPTION_TIMEOUT, timeoutMs.toLong())`; `OPTION_TIMEOUT = 1` | **PASS** |
| `explicitHandleSetHalfConnectionReachesNative` (T2) | new duplex overload | declares `fun setHalfConnection(handle: Long, halfConnection: Boolean)`; forwards `nativeSetHalfConnection(handle, halfConnection)`; legacy body preserved | **PASS** |
| `deprecatedLegacyOverloadsRetainedAndExternalHandleAware` (T3) | backward compat | both legacy methods still declared; both `@Deprecated` with the exact pointers; legacy `setTimeout` resolves `externalHandle` first | **PASS** |
| `controllerPassesOwnNativeHandleAtEveryCallSite` (T4) | fix completeness | all four controller call sites pass `nativeHandle`; `setTimeout` precedes `nativeConnectWithHub`; no legacy single-arg call remains | **PASS** |

T3 additionally guards the **`ThroughputBenchmarkTest`** consumer (an androidTest file outside this phase's edit scope) which still calls the single-argument forms: retaining the deprecated overloads keeps it compiling.

---

## 6. Test Run Result

| Task | Scope | Tests | Passed | Failed | Errors | Skipped |
|---|---|---|---|---|---|---|
| `:SoftEtherClient:testDebugUnitTest` | whole module | **133** | **133** | **0** | **0** | **0** |
| `:app:testProDebugUnitTest --tests "vn.unlimit.vpngate.automode.*"` | AutoMode suite | **183** | **183** | **0** | **0** | **0** |

`:SoftEtherClient:testDebugUnitTest` breakdown: `SoftEtherClientHandleGuardTest`=4 (new), `NativeAttemptCoordinatorTest`=20, `NativeHandleSingleOwnerTest`=8, `NativeLifetimeRegistryTest`=22, `SoftEtherSessionPolicyTest`=54, `TunOwnershipCoordinatorTest`=25.

The app run (183) **matches the pre-fix baseline exactly** — the signature additions and call-site updates introduced no regression (T4/T5).

**Compilation:** `:SoftEtherClient:compileDebugUnitTestKotlin` → BUILD SUCCESSFUL. No `allWarningsAsErrors`/`-Werror` is configured in this project, so the `@Deprecated` usage warnings from `ThroughputBenchmarkTest` do not fail the build.

---

## 7. Files Modified

| File | Before (lines) | After (lines) | Δ |
|---|---|---|---|
| `SoftEtherClient/src/main/java/vn/unlimit/softether/client/SoftEtherClient.kt` | 287 | 362 | +75 |
| `SoftEtherClient/src/main/java/vn/unlimit/softether/controller/ConnectionController.kt` | 1567 | 1573 | +6 |
| `SoftEtherClient/src/test/java/vn/unlimit/softether/client/SoftEtherClientHandleGuardTest.kt` | *(new)* | 212 | +212 |

`git diff --numstat`: `SoftEtherClient.kt` `78 insertions, 2 deletions`; `ConnectionController.kt` `391 insertions, 109 deletions` *(numstat is against a much older committed baseline, so it reflects all prior F.28AL work; this phase's delta is the +6 shown above).*

`find … -newermt "2026-10-12 15:48"` over `SoftEtherClient/src` and `app/src` returned **exactly these three files** — nothing else was touched this phase.

---

## 8. Evidence Taxonomy

**SOURCE CODE EVIDENCE:**
- `SoftEtherClient.kt` — new `setTimeout(handle, timeoutMs)` (:217-221), new `setHalfConnection(handle, halfConnection)` (:65-68), `@Deprecated` legacy methods (timeout :184-194; duplex :35-43).
- `ConnectionController.kt` — four updated call sites (535, 583, 1197, 1214).
- `SoftEtherClient.kt` — `OPTION_TIMEOUT = 1` (companion); `nativeSetHalfConnection` binding.

**TEST EVIDENCE:**
- `SoftEtherClientHandleGuardTest` — 4/4 PASS (T1, T2, T3, T4).
- `:SoftEtherClient:testDebugUnitTest` — 133/133 PASS.
- `:app:testProDebugUnitTest` (automode) — 183/183 PASS (baseline parity).

**INFERENCE:**
- The fix **will** now cause `nativeSetOption(OPTION_TIMEOUT, …)` to be reached at `ConnectionController.kt:535`/`:1197` at runtime (inferred from the source shape: the guard now takes a non-zero `nativeHandle` on the normal path). This is precisely what the F.28AL-AUTOCONNECT-BUG-VERIFY logcat lacked; device confirmation is deferred to the verification phase.

**UNKNOWN:**
- Whether the native race now reports the user-configured timeout end-to-end is a **device-runtime** fact and is **not** established by this phase — it is the explicit subject of the follow-up verification phase.

---

## 9. Explicit No-Change Confirmation

- **NativeHandleSlot.kt:** NONE
- **NativeLifetimeRegistry.kt:** NONE
- **SoftEtherVpnService.kt:** NONE
- **Native code (C/C++):** NONE
- **Gradle/dependencies/manifests/resources:** NONE
- **Device:** NONE
- **APK rebuild:** NONE
- **CI trigger:** NONE
- **Commit/tag/push/release:** NONE
- **Files edited:** `SoftEtherClient.kt`, `ConnectionController.kt`, and `SoftEtherClientHandleGuardTest.kt` (new test) only.

`HEAD` unchanged at `071462816b1eef7e7f7459483fcd69909938cd11`. `git status --porcelain` entries under `SoftEtherClient/src/main/cpp/` and `androidTest/` are **pre-existing modifications from earlier F.28AL phases**, not this one — the mtime check (§7) proves this phase touched only the three authorized files.

---

## 10. R8 Status

**R8 is still NOT implemented** — confirmed as expected. Grep for `Auto-Reconnect` / `AUTO_RECONNECT` / `autoReconnect` / `SETTING_AUTO_RECONNECT` across `app/src/main/java` and `SoftEtherClient/src/main/java` → **zero hits**.

---

## 11. Final Recommendation

**PROCEED TO DEVICE VERIFICATION OF BUG 3 FIX (F.28AL-AUTOCONNECT-BUG3-VERIFY)**

The fix is applied and all unit tests pass (133 + 183, zero failures). The remaining open question is purely a device-runtime one that only physical verification can answer: whether `nativeSetOption` now appears in logcat and the native race reports the user-configured timeout (e.g. `race timeout 5000 ms`), instead of the previous hard-coded `30000 ms`.

*End of report — F.28AL-AUTOCONNECT-BUG3-FIX.*
