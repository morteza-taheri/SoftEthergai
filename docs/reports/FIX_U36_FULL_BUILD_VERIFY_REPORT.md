# FIX-U36-FULL-BUILD-VERIFY — Report

**Phase:** FIX-U36-FULL-BUILD-VERIFY
**Goal:** Prove that KSP `2.3.12` genuinely **regenerates** the Room `_Impl` classes under a real build — the previous phase validated configuration only.
**Date:** 2026-10-12
**Base revision:** `071462816b1eef7e7f7459483fcd69909938cd11` (unchanged — nothing committed)

---

## 1. Executive Verdict

**`BUILD VERIFIED — KSP 2.3.12 REGENERATES CORRECTLY`**

The stale KSP output was deleted first, then the debug APK was built. Room's `_Impl` classes were regenerated from scratch at **21:19:24**, inside the build window, with byte-identical sizes and valid Room content. Build result: **BUILD SUCCESSFUL in 20m 39s**, with **zero KSP-related warnings or errors**.

This closes U-36 at the build level, not merely the configuration level.

---

## 2. Pre-Build State (P3)

**P1 — base revision:** `071462816b1eef7e7f7459483fcd69909938cd11` ✓

**P2 — KSP version:** `build.gradle:18`:
```groovy
    id "com.google.devtools.ksp" version '2.3.12' apply false
```
Confirmed `'2.3.12'`, not `'2.3.2'` ✓

**P3 — pre-existing KSP output:**

| File | Size (bytes) | Timestamp |
|---|---:|---|
| `AppDatabase_Impl.kt` | 10 481 | **2026-10-12 16:53:59** |
| `ExcludedAppDao_Impl.kt` | 4 281 | **2026-10-12 16:53:59** |
| `VPNGateItemDao_Impl.kt` | 21 097 | **2026-10-12 16:53:59** |

**Why this matters:** all three carried the timestamp **16:53:59** — generated during an earlier phase of this session, when the project still had the **old KSP 2.3.2**. These were therefore precisely the stale artifacts that had to be destroyed for the test to mean anything. Had I built without deleting them, Gradle's incremental build could have left them in place and "passing" would have proved nothing about 2.3.12.

---

## 3. V1 — Clean

**Command:**
```bash
rm -rf app/build/generated/ksp/proDebug
```

**Confirmed deleted.** `app/build/generated/ksp/` now contained only `proRelease`.

**Scope discipline verified:** only `proDebug` was removed. The adjacent `proRelease` directory — which holds a separate set of `_Impl` files dated 2026-10-01 — was **not** touched:

```
app/build/generated/ksp/proRelease/kotlin/vn/unlimit/vpngate/db/
    AppDatabase_Impl.kt      10481  2026-10-01 09:53
    ExcludedAppDao_Impl.kt    4281  2026-10-01 09:53
    VPNGateItemDao_Impl.kt   21097  2026-10-01 09:53
```

Nothing else under `build/` was deleted.

---

## 4. V2 — Build Result

**Exact command:**
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" \
CI=1 GRADLE_OPTS="-Dorg.gradle.internal.launcher.localhost=127.0.0.1 \
  -Dorg.gradle.internal.remote.internal.inet.tcp.port=0" \
./gradlew :app:assembleProDebug --no-daemon
```

| Field | Value |
|---|---|
| Exit status | **0 — BUILD SUCCESSFUL** |
| Build time | **20m 39s** |
| Wall clock | 21:09:48 → 21:30:39 |
| Tasks | 114 actionable: **110 executed**, 4 from cache |
| KSP-related warnings | **0** |
| KSP-related errors | **0** |

### Warnings observed (all pre-existing, none KSP-related)

Fourteen Kotlin warnings appeared, every one a source-level deprecation or lint note in application code that predates this phase:

- `DetailActivity.kt:545` — parameter naming mismatch with `ReadWriteProperty`
- `AndroidConnectionAdapter.kt:347`, `GlobalVpnTracker.kt:129` — `allNetworks` deprecated (Java)
- `AutoModeController.kt:482` — unnecessary cast
- `AppDatabase.kt:20/35/59` — `Migration` parameter naming (×3)
- `VpnGateHtmlParser.kt:369/391` — unnecessary `!!` (×2)
- `CopyDebugSheets.kt:91` — `LocalClipboardManager` deprecated
- `FilterSheet.kt:248` — `menuAnchor()` deprecated
- `HomeScreen.kt:345`, `SettingsScreen.kt:580` — material icons deprecations
- `StatusScreen.kt:65` — `LocalLifecycleOwner` moved

**None of these mention KSP, KSP processing, or annotation processing.** The KSP task itself produced no output at all — which is the expected, healthy signal.

---

## 5. V3 — Regeneration Confirmation

```
$ ls -la app/build/generated/ksp/proDebug/kotlin/vn/unlimit/vpngate/db/

-rw-r--r-- 10481  2026-10-12 21:19:24.824  AppDatabase_Impl.kt
-rw-r--r--  4281  2026-10-12 21:19:24.754  ExcludedAppDao_Impl.kt
-rw-r--r-- 21097  2026-10-12 21:19:24.704  VPNGateItemDao_Impl.kt
```

| Requirement | Result |
|---|---|
| `AppDatabase_Impl.kt` exists, timestamp **after V1** | ✓ **21:19:24** (V1 delete at 21:09) |
| `VPNGateItemDao_Impl.kt` exists, timestamp **after V1** | ✓ **21:19:24** |
| `ExcludedAppDao_Impl.kt` exists, timestamp **after V1** | ✓ **21:19:24** |

All three regenerated **10 minutes into the build** and **~11 minutes before it finished** — comfortably inside the window, and definitively after the deletion. `KSP_REGENERATION_FAILED` does not apply.

**A corroborating observation:** the regenerated sizes are **byte-identical** to the pre-build ones (10 481 / 4 281 / 21 097). Room emitted the same code under KSP 2.3.12 as it had under 2.3.2 — which is the correct, expected outcome for an alignment fix. Had the sizes differed, that would have warranted investigation into whether the version bump changed processing behaviour.

---

## 6. V4 — Content Spot-Check

First 30 lines of `AppDatabase_Impl.kt` — the substantive declarations:

```kotlin
package vn.unlimit.vpngate.db

import androidx.room.InvalidationTracker
import androidx.room.RoomOpenDelegate
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.room.util.TableInfo
import androidx.room.util.TableInfo.Companion.read
import androidx.room.util.dropFtsSyncTriggers
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import javax.`annotation`.processing.Generated
import kotlin.Lazy
import kotlin.String
import kotlin.Suppress
...

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class AppDatabase_Impl : AppDatabase() {
  private val _vPNGateItemDao: Lazy<VPNGateItemDao> = lazy {
    VPNGateItemDao_Impl(this)
  }

  private val _excludedAppDao: Lazy<ExcludedAppDao> = lazy {
    ExcludedAppDao_Impl(this)
  }
```

**Verdict: valid Room output.** All the markers are present and correct:
- `@Generated(value = ["androidx.room.RoomProcessor"])` — the Room processor's own stamp.
- `@Suppress(names = [...])` — Room's standard suppression set.
- `public class AppDatabase_Impl : AppDatabase()` — the expected subclass of the user's `@Database` type.
- Both DAO delegates (`_vPNGateItemDao`, `_excludedAppDao`) correctly typed and lazily wired to their own `_Impl` classes.

---

## 7. V5 — Debug APK

| Field | Value |
|---|---|
| Path | `app/build/outputs/apk/pro/debug/app-pro-debug.apk` |
| Size | **63 572 562 bytes** (~60.6 MiB) |
| SHA-256 | `d71332b13807c6fea517c37062b03029f3ad4c39d3f44570f8585a8be7555e8c` |
| Built | 2026-10-12 21:30:30 |

This is a **local inspection artifact only**, as the phase specified.

### Bonus cross-check — FIX-L01 debug side

Not required by this phase, but cheap and directly relevant: the debug APK should still carry the native test library (debug pins `-DBUILD_TESTING=ON`). It does — **all four ABIs**:

```
lib/arm64-v8a/libsoftether_test.so     5 840 368
lib/armeabi-v7a/libsoftether_test.so   4 642 724
lib/x86/libsoftether_test.so           5 424 180
lib/x86_64/libsoftether_test.so        7 085 088
```

This independently confirms FIX-L01's debug-side pinning survived both the KSP change and the build.

---

## 8. V6 — Working Tree Unchanged

| Metric | Pre-build baseline | Post-build | Match |
|---|---:|---:|---|
| Total dirty entries | 177 | 177 | ✓ |
| Tracked modified (` M`) | 57 | 57 | ✓ |
| Untracked (`??`) | 120 | 120 | ✓ |
| HEAD | `0714628…` | `0714628…` | ✓ |

The working tree is **byte-identical in composition** to the pre-build baseline. No source file was disturbed by the build, and nothing was committed.

---

## 9. Evidence Taxonomy

**BUILD ARTIFACT:**
- `app-pro-debug.apk` (63 572 562 bytes, SHA-256 `d71332b1…`) — produced by this phase.
- The four `libsoftether_test.so` entries inside it, with sizes and ABI paths.
- The regenerated `_Impl.kt` files under `app/build/generated/ksp/proDebug/…` with timestamps 21:19:24.
- Gradle's `BUILD SUCCESSFUL in 20m 39s`, the task counts, and the full warning list.

**SOURCE CODE EVIDENCE:**
- `build.gradle:18` — KSP `'2.3.12'`.
- The content of the regenerated `AppDatabase_Impl.kt` (first 30 lines + class declaration).
- `git status --short` counts before and after.
- `git rev-parse HEAD`.

**INFERENCE:**
- That the 21:19:24 timestamps prove generation by **2.3.12 specifically** rather than by a cached 2.3.2 — inferred from the deletion in V1 having removed the only prior copy in that directory, plus the fact that the Gradle cache contains 2.3.12 as the resolved version (established in the previous phase). The chain is sound but is an inference from timestamps plus resolution state, not a direct observation of the KSP process identity.
- That `libsoftether_test.so` presence indicates `-DBUILD_TESTING=ON` was honoured — inferred from FIX-L01's documented build-configuration intent, though it is exactly what one expects for a debug build.

**UNKNOWN:**
- Whether the **release** variant's KSP output would regenerate identically. Only `proDebug` was cleaned and rebuilt; `proRelease` still holds its 2026-10-01 artifacts. The release path is untouched by this phase and was not validated here.
- Whether the APK installs and runs correctly on a device. Not tested — out of scope, and the device is not enumerating.
- Whether KSP 2.3.12 differs from 2.3.2 in any behaviour not exercised by this project's annotations. The byte-identical output sizes suggest no difference for Room, but that is one processor, not a general guarantee.

---

## 10. Explicit No-Change Confirmation

| Scope | Status |
|---|---|
| Source `.kt` / `.java` / `.xml` | **NONE** |
| Test files | **NONE** |
| Gradle files | **NONE** |
| CMakeLists / native | **NONE** |
| Device | **NONE** — not touched, not connected |
| CI | **NONE** — not triggered |
| Commit / tag / push / release | **NONE** — HEAD still `071462816b1eef7e7f7459483fcd69909938cd11` |
| Files deleted | **only `app/build/generated/ksp/proDebug`** — and it was regenerated by the build |
| Files created | debug APK + regenerated KSP output (both under `app/build/`, both ignored by git) |

The `proRelease` KSP output, the rest of the `app/build/` tree, and every source file were left untouched.

---

## 11. Final Recommendation

**`PROCEED TO COMMIT-BATCH-P0-PREP`**

U-36 is now closed at both levels: configuration (previous phase) and full build (this phase). The evidence is direct — stale output destroyed, fresh output regenerated inside the build window with valid Room content, build green, zero KSP warnings. There is nothing left to verify about this fix short of a device, which this finding never needed.

Three things to carry forward:

1. **Only the debug path was proven.** `proRelease` KSP output was deliberately left alone (it was outside V1's scope) and still carries 2026-10-01 artifacts. If you want the release path validated too, a `:app:assembleProRelease` with a clean of `app/build/generated/ksp/proRelease` would do it — but note the earlier release-APK build was **cancelled** in an aborted phase, so this would be the first release build of the session and would take a while. Flagging it rather than assuming it is needed, since the KSP plugin resolves identically for both variants.

2. **The APK is unverified on hardware.** It built, but nothing has confirmed it installs or runs. That is `BATCH-DEVICE-VERIFY`'s job, still blocked on the device not enumerating (`Unknown Redmi Note 11` + `Unknown Device (Device Descriptor Request Failed)`).

3. **Six native tests (31–36) still await device execution** from the earlier phases. They are hermetic, so they will run regardless of server reachability once a device is available.

**FINAL RULE compliance:** build only. No source was modified. The device was not touched. Nothing was committed.
