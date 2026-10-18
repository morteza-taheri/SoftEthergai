# FIX-L01-VERIFY — Verification Report

**Phase:** FIX-L01-VERIFY — Verify Test-Library Exclusion in Release Build
**Date:** 2026-10-12
**HEAD:** `071462816b1eef7e7f7459483fcd69909938cd11` (verified before and after; unchanged)
**Mode:** Rebuild-and-inspect only. No file modified. No device. No CI. No commit/tag/push.

---

## 1. Executive Verdict

**VERIFICATION PARTIAL — SOME CHECKS NOT POSSIBLE**

The substantive question of this phase — *does the FIX remove `libsoftether_test.so`
from the release build while keeping it in debug?* — is **PROVEN YES**, from freshly
rebuilt native artifacts:

- Fresh **release** native build (2026-10-12 19:04): `libsoftether_test.so` is **ABSENT**
  in all 4 ABIs; `libsoftether.so` present in all 4.
- Fresh **debug** native build (2026-10-12 19:06): `libsoftether_test.so` is **PRESENT**
  in all 4 ABIs.
- CMake caches confirm the mechanism: `BUILD_TESTING:BOOL=OFF` (release, all 4 ABIs),
  `BUILD_TESTING:BOOL=ON` (debug, all 4 ABIs).

The phase is **PARTIAL** rather than **PASS** because the end-to-end *release APK*
(V2/V3 on the packaged artifact) could not be produced on this machine: the app's
pre-existing **fail-closed release signing guard** (`app/build.gradle:93–116`) blocks
every `assemble*Release` / `bundle*Release` / `package*Release` task because the release
keystore (`keyStore/release.jks`) is absent locally (only the encrypted
`keyStore/release.jks.gpg` exists) and `SIGN_KEY_PASSWORD` is unset. That guard is
deliberate, pre-existing, and **unrelated to the FIX**; bypassing it would require
editing `app/build.gradle`, which this phase's rules forbid.

No check failed. No fix is indicated.

---

## 2. Pre-Verification Confirmation (P2)

### P1 — HEAD
```
$ git rev-parse HEAD
071462816b1eef7e7f7459483fcd69909938cd11
```
Matches the required commit. (Re-confirmed unchanged at end of phase.)

### P3 — Gradle / CI requirement
```
$ grep distributionUrl gradle/wrapper/gradle-wrapper.properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.7.0-bin.zip
```
`app/build.gradle:117–123` — debug variants are disabled unless `CI` is set:
```groovy
androidComponents {
    if (System.getenv("CI") == null) {
        beforeVariants(selector().withBuildType("debug")) { variant ->
            variant.enabled = false
        }
    }
```
`CI=1` was exported for every build in this phase.

### P2 — `SoftEtherClient/src/main/cpp/CMakeLists.txt` (exact guard lines)

```
10:option(BUILD_TESTING "Build the native test library" OFF)
68:add_library(softether SHARED ${SOFTETHER_SOURCES})
134:if(BUILD_TESTING)
136:    add_library(softether_test SHARED ${TEST_SOURCES})
165:endif()
```

- Line 10: `option(BUILD_TESTING "Build the native test library" OFF)` — present near the top, default **OFF**. ✅
- Line 136: `add_library(softether_test SHARED ${TEST_SOURCES})` — **inside** `if(BUILD_TESTING)` (134) … `endif()` (165). ✅
- Line 68: `add_library(softether SHARED ${SOFTETHER_SOURCES})` — **outside** any `BUILD_TESTING` guard (the only `if(BUILD_TESTING)` begins at 134, after it). ✅

### P2 — `SoftEtherClient/build.gradle` (exact guard lines)

```groovy
38:        release {
47:                    arguments "-DBUILD_TESTING=OFF"
51:        debug {
63:                    arguments "-DBUILD_TESTING=ON"
```

- `release` buildType pins `-DBUILD_TESTING=OFF` (line 47). ✅
- `debug` buildType pins `-DBUILD_TESTING=ON` (line 63). ✅

**P2 result: FULLY CONFIRMED — all six conditions as specified.**

---

## 3. V1 — Release Build

**Task used:** `:app:assembleProRelease` (confirmed correct by `:app:tasks --all`).

**Result: BUILD FAILED — blocked by the pre-existing fail-closed signing guard.**

```
$ CI=1 ./gradlew :app:assembleProRelease
* Where:
Build file 'D:\PMC\Documents\Android\SoftEthergai\app\build.gradle' line: 109

* What went wrong:
Release build blocked: release signing configuration is incomplete.
Missing: release keystore file (keyStore/release.jks),
         SIGN_KEY_PASSWORD (env var or signKeyPassword in local.properties).

BUILD FAILED in 36s
```

Root cause (environment, not the FIX):
```
$ ls keyStore/
release.jks.gpg          # encrypted blob only — no plaintext release.jks
$ echo "${SIGN_KEY_PASSWORD:-UNSET}"
UNSET
$ cat local.properties
sdk.dir=C\:\\Users\\PMC\\AppData\\Local\\Android\\Sdk   # no signKeyPassword
```
`app/build.gradle:43` → `hasReleaseSigningConfig = false` → the guard at `:93–116`
throws. A `--dry-run` reproduces it identically, so the block is deterministic.

**Fallback used (substitute for the blocked app-level release build):**
```
$ CI=1 ./gradlew :SoftEtherClient:externalNativeBuildRelease
BUILD SUCCESSFUL in 55s
9 actionable tasks: 9 executed
```
This compiles the exact same CMake targets with the exact same `-DBUILD_TESTING=OFF`
argument, so it answers V3's question at the native-artifact level.

---

## 4. V2 — Release APK

**Not producible on this machine** — no release APK was built this phase (see §3).

For reference only, the **stale pre-fix** APK still on disk (NOT produced by this phase,
NOT evidence for or against the fix):
```
Path:   app/build/outputs/apk/pro/release/app-pro-release.apk
Size:   35,320,335 bytes
SHA256: c434935c93bee03054bef927665ba54e589859d872cca07b5e3ac6fad14e6013
Mtime:  2026-10-05 18:38:34  (pre-fix)
```

---

## 5. V3 — Native Library Listing (release)

### 5a. FRESH release native output — the authoritative result

```
$ CI=1 ./gradlew :SoftEtherClient:externalNativeBuildRelease
> Task :SoftEtherClient:buildCMakeRelWithDebInfo[armeabi-v7a]
> Task :SoftEtherClient:buildCMakeRelWithDebInfo[arm64-v8a]
> Task :SoftEtherClient:buildCMakeRelWithDebInfo[x86]
> Task :SoftEtherClient:buildCMakeRelWithDebInfo[x86_64]
BUILD SUCCESSFUL in 55s
```

Fresh output directory `SoftEtherClient/.cxx/RelWithDebInfo/6l4g4t3t/` (2026-10-12 19:04):

| ABI | `libsoftether.so` | `libsoftether_test.so` |
|---|---|---|
| armeabi-v7a | ✅ present (6,163,480 B) | ❌ **ABSENT** |
| arm64-v8a | ✅ present (7,166,488 B) | ❌ **ABSENT** |
| x86 | ✅ present (6,256,800 B) | ❌ **ABSENT** |
| x86_64 | ✅ present (8,216,272 B) | ❌ **ABSENT** |

**`libsoftether_test.so` ABSENT for all 4 ABIs. `libsoftether.so` PRESENT for all 4.**
Expected outcome confirmed at the native-artifact level.

### 5b. CMake cache — mechanism confirmation

```
$ grep BUILD_TESTING:BOOL SoftEtherClient/.cxx/RelWithDebInfo/6l4g4t3t/*/CMakeCache.txt
.../6l4g4t3t/arm64-v8a/CMakeCache.txt:30:BUILD_TESTING:BOOL=OFF
.../6l4g4t3t/armeabi-v7a/CMakeCache.txt:30:BUILD_TESTING:BOOL=OFF
.../6l4g4t3t/x86/CMakeCache.txt:30:BUILD_TESTING:BOOL=OFF
.../6l4g4t3t/x86_64/CMakeCache.txt:30:BUILD_TESTING:BOOL=OFF

$ grep -n . .../6l4g4t3t/arm64-v8a/metadata_generation_command.txt | grep BUILD_TESTING
20:-DBUILD_TESTING=OFF
```
The Gradle `release` buildType argument reached CMake verbatim for every ABI.

### 5c. Note on stale directories (avoid misreading)

Two output directories coexist under `RelWithDebInfo/`:
- `o4u1ulf4/` — **OLD**, mtime 2026-09-28/10-09, still contains `libsoftether_test.so`. Pre-fix.
- `6l4g4t3t/` — **NEW**, mtime 2026-10-12 19:04, contains **no** test library. Post-fix.

The old directory's `CMakeCache.txt` has **no** `BUILD_TESTING` entry at all, confirming it
predates the option. Likewise `app/build/intermediates/merged_native_libs/proRelease/`
is still the stale 2026-09-28 copy (the app-level release build never ran) — **not**
evidence about the fix.

---

## 6. V4 — Investigation (only if test lib was present)

**N/A.** The test library was **not** present in the fresh release output, so no
investigation into a failure was required.

(For completeness: the only place a release-path `libsoftether_test.so` still exists on
disk is inside the stale pre-fix directories listed in §5c. Their timestamps predate the
FIX by 3–14 days.)

---

## 7. V5 — Debug APK

```
$ CI=1 ./gradlew :app:assembleProDebug
BUILD SUCCESSFUL in 1m 13s
114 actionable tasks: 13 executed, 101 up-to-date

Path:   app/build/outputs/apk/pro/debug/app-pro-debug.apk
Size:   73,559,959 bytes
Mtime:  2026-10-12 19:08:01
SHA256: eff5b9333323c4889d6e7b230b88071b0e1ac20a0fc10b4ec98ee8df5e497584
```

```
$ unzip -l app/build/outputs/apk/pro/debug/app-pro-debug.apk | grep -E "libsoftether(_test)?\.so"
  5765784  1981-01-01 01:01   lib/arm64-v8a/libsoftether.so
  5831504  1981-01-01 01:01   lib/arm64-v8a/libsoftether_test.so
  4594344  1981-01-01 01:01   lib/armeabi-v7a/libsoftether.so
  4635124  1981-01-01 01:01   lib/armeabi-v7a/libsoftether_test.so
  5333016  1981-01-01 01:01   lib/x86/libsoftether.so
  5413604  1981-01-01 01:01   lib/x86/libsoftether_test.so
  7003168  1981-01-01 01:01   lib/x86_64/libsoftether.so
  7074432  1981-01-01 01:01   lib/x86_64/libsoftether_test.so
```

**`libsoftether_test.so` PRESENT for all 4 ABIs in the debug APK.** Expected outcome
confirmed. The debug variant still builds and packages the test library.

Debug native cache confirms the mechanism:
```
$ grep BUILD_TESTING:BOOL SoftEtherClient/.cxx/Debug/5v273202/*/CMakeCache.txt
.../5v273202/arm64-v8a/CMakeCache.txt:30:BUILD_TESTING:BOOL=ON
.../5v273202/armeabi-v7a/CMakeCache.txt:30:BUILD_TESTING:BOOL=ON
.../5v273202/x86/CMakeCache.txt:30:BUILD_TESTING:BOOL=ON
.../5v273202/x86_64/CMakeCache.txt:30:BUILD_TESTING:BOOL=ON
```

---

## 8. V6 — androidTest Sanity

**1. `NativeConnectionTest.kt` still loads the test library — CONFIRMED.**
```
$ grep -n "loadLibrary" SoftEtherClient/src/androidTest/java/vn/unlimit/softether/test/NativeConnectionTest.kt
47:                System.loadLibrary("softether")
48:                System.loadLibrary("softether_test")
```
Line 48 unchanged. ✅

**2. The debug CMake build compiles the `softether_test` target — CONFIRMED.**
The fresh debug build (`Debug/5v273202/`, 2026-10-12 19:06) emitted
`libsoftether_test.so` into the `obj/` directory for all four ABIs, and
`BUILD_TESTING:BOOL=ON` in every per-ABI `CMakeCache.txt`. The `add_library(softether_test …)`
block at CMakeLists.txt:136 therefore executed. ✅

**3. `connectedDebugAndroidTest` — NOT run**, as required by this phase (needs a device).

Supporting fact (confirms the debug variant is the right host for androidTest):
```
$ grep -rn "testBuildType" app/build.gradle SoftEtherClient/build.gradle build.gradle
SoftEtherClient/build.gradle:58:  # ... only hit is the FIX's own explanatory comment
```
No `testBuildType` is configured anywhere, so library-module `androidTest` targets the
**debug** variant by default — which is why `-DBUILD_TESTING=ON` on `debug` is the
correct and sufficient placement.

---

## 9. V7 — Size Comparison

### Release
| | Size | Notes |
|---|---|---|
| Pre-fix release APK (stale on disk, 2026-10-05) | **35,320,335 B** | contained `libsoftether_test.so` × 4 ABIs |
| Post-fix release APK | **not produced** | blocked by signing guard (§3) |

Test-library payload removed from the release path (uncompressed, from the pre-fix APK):

| ABI | `libsoftether_test.so` |
|---|---|
| arm64-v8a | 5,727,904 B |
| armeabi-v7a | 4,590,640 B |
| x86 | 5,283,892 B |
| x86_64 | 6,969,584 B |
| **Total** | **23,472,020 B (~22.4 MB)** |

So the fix removes **~22.4 MB** of uncompressed native payload (≈21 MB after ZIP
compression at the observed ratio), matching the ~21 MB estimate in the FIX report.
The *packaged* reduction could not be measured this phase because the release APK
could not be built.

### Debug
| | Size | Notes |
|---|---|---|
| Debug APK (fresh, 2026-10-12 19:08) | **73,559,959 B** | still contains `libsoftether_test.so` × 4 ABIs |

Debug size is as expected — the test library is intentionally retained, so no shrink
is expected or observed. (No prior debug APK size is available for a delta at this
precision; the debug APK legitimately includes test libs, extra debug symbols, and
non-minified code, so a comparison against release is not meaningful.)

---

## 10. Evidence Taxonomy

**BUILD ARTIFACT (strongest — freshly produced this phase)**
- `SolidEtherClient/.cxx/RelWithDebInfo/6l4g4t3t/**` — release native build, 2026-10-12 19:04; no test library.
- `SoftEtherClient/.cxx/Debug/5v273202/**` — debug native build, 2026-10-12 19:06; test library present.
- `app/build/outputs/apk/pro/debug/app-pro-debug.apk` — 73,559,959 B, SHA256 `eff5b933…`, test library present × 4 ABIs.
- `CMakeCache.txt` (8 files) — `BUILD_TESTING` OFF/ON per variant, verbatim Gradle argument in `metadata_generation_command.txt`.
- Gradle console output — "BUILD SUCCESSFUL" for `externalNativeBuildRelease` and `assembleProDebug`.

**SOURCE CODE EVIDENCE**
- `SoftEtherClient/src/main/cpp/CMakeLists.txt:10,68,134,136,165` — option + guard structure.
- `SoftEtherClient/build.gradle:38,47,51,63` — release OFF / debug ON.
- `app/build.gradle:43,93–116,117–123` — signing guard + CI gate (explains the V1 block).
- `SoftEtherClient/src/androidTest/.../NativeConnectionTest.kt:48` — `loadLibrary("softether_test")`.

**INFERENCE**
- The ~21 MB packaged reduction: derived from the 23,472,020 B uncompressed test-lib
  payload in the pre-fix APK, not from a newly built release APK.
- `androidTest` resolves the library from the debug variant: inferred from the absence of
  any `testBuildType` in the project (strongly supported, but not exercised on a device).

**UNKNOWN**
- The exact post-fix release APK size and its SHA-256 — the release APK could not be built
  locally (signing guard, §3).
- That `NativeConnectionTest` actually *passes* at runtime — requires a device
  (`connectedDebugAndroidTest`), explicitly out of scope.

---

## 11. Explicit No-Change Confirmation

| Item | Result |
|---|---|
| Source (.kt/.java/.c/.h/.cpp) | **NONE** modified |
| Test sources | **NONE** modified |
| CMakeLists.txt | **NONE** modified |
| build.gradle | **NONE** modified |
| `app/build.gradle` | **NONE** modified (signing guard left intact — build blocked rather than bypassed) |
| Device | **NONE** (no adb install/uninstall/clear; device not touched) |
| CI | **NONE** (no workflow triggered) |
| Commit / tag / push / release | **NONE** |
| HEAD | `071462816b1eef7e7f7459483fcd69909938cd11` — unchanged |
| Dirty-file count | 166 before and after (unchanged; the two FIX files remain the only ones this workstream owns) |

`find . -newermt "-30 minutes"` over all `.kt/.java/.c/.h/.cpp/.xml/CMakeLists.txt/build.gradle`
outside `build/` returned **no results** — no source file was touched.

**Files created by this phase:** build outputs only —
- `SoftEtherClient/.cxx/{RelWithDebInfo/6l4g4t3t,Debug/5v273202}/**` (native build artifacts)
- `app/build/outputs/apk/pro/debug/app-pro-debug.apk`
- This report: `FIX_L01_VERIFY_REPORT.md`
- (No new release APK was produced.)

**Local inspection artifact only — the debug APK is NOT for distribution.**

---

## 12. Final Recommendation

**WAIT FOR EXPLICIT USER DECISION**

Justification:

- The fix's *mechanism* is verified end-to-end at the native-artifact and CMake-config
  level, for both variants. There is no sign of a defect and no audit is indicated —
  so `FIX NEEDS AUDIT` is not warranted.
- The last verification gap is purely environmental: the release APK cannot be packaged
  on this machine without the release keystore and `SIGN_KEY_PASSWORD`. Closing it
  requires either (a) supplying the credentials locally, or (b) authorizing a CI run,
  both of which are outside this phase's rules and need a user decision.

Suggested options for the user to choose from:
1. **Close the gap via CI** — let the existing workflow build the signed release APK and
   inspect it for `libsoftether_test.so`. This is the definitive end-to-end check.
2. **Close the gap locally** — restore `keyStore/release.jks` (decrypt `release.jks.gpg`)
   and set `SIGN_KEY_PASSWORD`, then re-run V1–V3.
3. **Accept the native-artifact evidence as sufficient** and proceed to
   `FIX-NATIVE-P0-BOUNDS (N-01..N-04, N-08)` — the P0 blockers that remain open.

---

## Appendix — Commands Run (reproducible)

```bash
export MSYS_NO_PATHCONV=1
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # JDK 25; AGP 9.1.1 needs >=17
export GRADLE_OPTS="-Dorg.gradle.internal.launcher.localhost=127.0.0.1"
export CI=1

git rev-parse HEAD
grep -n -E "option\(BUILD_TESTING|add_library\(softether" SoftEtherClient/src/main/cpp/CMakeLists.txt
grep -n -E "BUILD_TESTING" SoftEtherClient/build.gradle

# V1 (app-level, BLOCKED by signing guard)
./gradlew :app:assembleProRelease --console=plain

# V1 fallback (native, SUCCEEDED)
./gradlew :SoftEtherClient:externalNativeBuildRelease --console=plain

# V5
./gradlew :app:assembleProDebug --console=plain
unzip -l app/build/outputs/apk/pro/debug/app-pro-debug.apk | grep -E "libsoftether(_test)?\.so"
sha256sum app/build/outputs/apk/pro/debug/app-pro-debug.apk

# Evidence
grep -rn "BUILD_TESTING:BOOL" SoftEtherClient/.cxx/RelWithDebInfo/6l4g4t3t/*/CMakeCache.txt
grep -rn "BUILD_TESTING:BOOL" SoftEtherClient/.cxx/Debug/5v273202/*/CMakeCache.txt
```
