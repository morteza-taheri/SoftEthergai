# FIX-L01-TESTLIB-EXCLUDE — Remove Test Library from Release Build

**Phase:** FIX-L01-TESTLIB-EXCLUDE
**Source finding:** DEEP-REVIEW-VERIFY L-01 / L-02
**Worktree HEAD:** `071462816b1eef7e7f7459483fcd69909938cd11` (unchanged by this phase)
**Language:** English only
**Date:** 2026-10-12

---

## 1. Executive Verdict

**FIX APPLIED — CONFIGURATION VALIDATED**

The `softether_test` native target is now guarded behind `option(BUILD_TESTING ... OFF)` and is built only for the debug variant (which also serves `androidTest`). The release variant explicitly pins `-DBUILD_TESTING=OFF`, so the test library is excluded from release output. Both edited files parse. No other file in the repository was touched. No build of the release APK, no signing, no device, no CI, no commit was performed — a separate verification phase will rebuild and inspect the APK.

---

## 2. Pre-Fix Confirmation (P4)

Pre-fix `SoftEtherClient/src/main/cpp/CMakeLists.txt` built the test target **unconditionally** — there was no `option(BUILD_TESTING ...)` and no enclosing `if()`:

```cmake
# Test library sources
set(TEST_SOURCES
    test/native_test.c
    test/test_jni_bridge.c
    ${SOFTETHER_SOURCES}
)

# Create test shared library
add_library(softether_test SHARED ${TEST_SOURCES})

# Include directories for test library
target_include_directories(softether_test PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/softether-core/include
    ${CMAKE_CURRENT_SOURCE_DIR}/test
)

# Link libraries for test library
target_link_libraries(softether_test
    ${log-lib}
    ${android-lib}
    ${z-lib}
)

# Link prebuilt OpenSSL for test library if available
if(USE_PREBUILT_OPENSSL)
    target_link_libraries(softether_test ${OPENSSL_LIBRARIES})
else()
    target_link_libraries(softether_test ssl crypto)
endif()

# Compiler flags for test library
target_compile_options(softether_test PRIVATE
    -Wall
    -Wno-unused-parameter
    -Wno-unused-variable
    -fPIC
)
```

Pre-fix `grep` confirmation:
- `CMakeLists.txt:101` → `set(TEST_SOURCES`
- `CMakeLists.txt:102` → `test/native_test.c`
- `CMakeLists.txt:103` → `test/test_jni_bridge.c`
- `CMakeLists.txt:108` → `add_library(softether_test SHARED ${TEST_SOURCES})`
- `option(BUILD_TESTING` → **no match** (absent)

Pre-fix `SoftEtherClient/build.gradle` had **no** `arguments "-DBUILD_TESTING=..."` for either build type; `debug` only overrode `cppFlags`/`cFlags`.

---

## 3. CMakeLists.txt Changes

### 3.1 Added `option(...)` line

Inserted immediately after `project("softetherclient")` (top of file, before any `add_library`):

```cmake
# Build the native test library (libsoftether_test.so).
#
# DEEP-REVIEW-VERIFY L-01/L-02: default OFF, so the test library is NOT part of
# a plain release build. It is enabled explicitly by the debug and androidTest
# Gradle configurations via `-DBUILD_TESTING=ON`. See the guarded target near
# the bottom of this file for the full rationale.
option(BUILD_TESTING "Build the native test library" OFF)
```

### 3.2 The `if(BUILD_TESTING)` / `endif()` wrap

`set(TEST_SOURCES ...)` was **kept in place, unmodified and unconditional** (per I2 — the variable may be referenced elsewhere). The target and all four associated blocks were wrapped:

- `add_library(softether_test SHARED ${TEST_SOURCES})`
- `target_include_directories(softether_test PRIVATE ...)`
- `target_link_libraries(softether_test ${log-lib} ${android-lib} ${z-lib})`
- the prebuilt-OpenSSL `if(USE_PREBUILT_OPENSSL)` link block (nested, re-indented)
- `target_compile_options(softether_test PRIVATE ...)`

Structure:

```cmake
if(BUILD_TESTING)
    add_library(softether_test SHARED ${TEST_SOURCES})
    target_include_directories(...)
    target_link_libraries(...)
    if(USE_PREBUILT_OPENSSL)
        target_link_libraries(softether_test ${OPENSSL_LIBRARIES})
    else()
        target_link_libraries(softether_test ssl crypto)
    endif()
    target_compile_options(...)
endif()
```

### 3.3 Production library block — NOT touched

`add_library(softether SHARED ${SOFTETHER_SOURCES})` and its link/compile-option blocks remain **outside** and **before** the guard. Programmatic scope check confirms:

```
softether_test add_library inside guard: True
prod softether add_library inside guard: False
if( count: 7   endif( count: 7   balanced: True
```

---

## 4. build.gradle Changes

### 4.1 Which build types pass `-DBUILD_TESTING=ON`

- **`debug`** → passes `-DBUILD_TESTING=ON`.

### 4.2 Which do NOT

- **`release`** → explicitly passes `-DBUILD_TESTING=OFF` (belt-and-braces: pins the CMake default rather than relying on it).
- `defaultConfig` → unchanged; still passes only `-DANDROID_STL=c++_shared`.

### 4.3 Exact syntax used

```groovy
buildTypes {
    release {
        minifyEnabled false
        proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        externalNativeBuild {
            cmake {
                // DEEP-REVIEW-VERIFY L-01/L-02: the release variant keeps the
                // CMake default for BUILD_TESTING (OFF), so the native test
                // library is excluded from the release artifact. Stated
                // explicitly so a future edit cannot silently re-enable it.
                arguments "-DBUILD_TESTING=OFF"
            }
        }
    }
    debug {
        debuggable true
        externalNativeBuild {
            cmake {
                cppFlags "-O0 -g"
                cFlags "-O0 -g"
                arguments "-DBUILD_TESTING=ON"
            }
        }
    }
}
```

(Comment text elided above for brevity; the on-disk file carries the full rationale comments.)

### 4.4 androidTest still receives the flag (I4)

**Verified by inspection and documented.** This project defines **no `testBuildType`** anywhere (`grep -rn "testBuildType" --include=*.gradle --include=*.kts` → no matches). Under the Android Gradle Plugin, a library module's `androidTest` component therefore targets the **debug** variant by default. `src/androidTest/.../NativeConnectionTest.kt:48` calls `System.loadLibrary("softether_test")`, so it resolves the library produced by the `debug` configuration — which now passes `-DBUILD_TESTING=ON`.

- **Triggering configuration:** the `debug` build type.
- **No separate `androidTest` block is required** (library modules have no `androidTest` build type of their own).
- `.github/workflows/android-ci.yml` calls `./gradlew assembleProRelease` (line 247) and does **not** invoke `connectedAndroidTest`, so CI's release path now correctly excludes the test library while any local/CI debug androidTest run still gets it.

---

## 5. Verification

| Check | Result |
|---|---|
| CMake `if()`/`endif()` balanced | **PASS** — 7 / 7 |
| `option(BUILD_TESTING "Build the native test library" OFF)` present | **PASS** |
| `add_library(softether_test ...)` inside the guard | **PASS** — True |
| Production `add_library(softether ...)` outside the guard | **PASS** — False |
| `TEST_SOURCES` still defined unconditionally | **PASS** |
| build.gradle braces balanced | **PASS** — 20 `{` / 20 `}` |
| release code path passes only `-DBUILD_TESTING=OFF` | **PASS** (comment-stripped check) |
| debug code path passes only `-DBUILD_TESTING=ON` | **PASS** (comment-stripped check) |
| No production file modified besides the two authorized | **PASS** — `find … -newermt "-15 minutes"` → exactly `SoftEtherClient/build.gradle`, `SoftEtherClient/src/main/cpp/CMakeLists.txt` |

**CMake dry-run:** not attempted. A standalone `cmake` configure requires the Android NDK toolchain file and `ANDROID_ABI`, and invoking the project's Gradle configure would risk pulling in a real build task; per the phase rules no build was run. The syntactic validity was instead confirmed statically (balanced `if/endif`, correct nesting, unchanged command arity). The authoritative parse is deferred to the verification phase, which will run a real configure as a side effect of rebuilding.

**Diff summary (not full):**

```
 SoftEtherClient/build.gradle                | 16 ++++++
 SoftEtherClient/src/main/cpp/CMakeLists.txt | 87 +++++++++++++++++++----------
 2 files changed, 74 insertions(+), 29 deletions(-)
```

- `build.gradle`: +16 lines — two `externalNativeBuild { cmake { arguments … } }` blocks (one per build type) plus rationale comments.
- `CMakeLists.txt`: +74/−29 — the `option()` line and its comment; expanded rationale comment above the target; `if(BUILD_TESTING)` / `endif()` wrapper with the five wrapped blocks re-indented. **No production command was altered.**

---

## 6. Evidence Taxonomy

- **SOURCE CODE EVIDENCE:** the pre-fix and post-fix contents of both edited files, read directly from disk; the `grep` results for `testBuildType` (none) and `System.loadLibrary("softether_test")` (`NativeConnectionTest.kt:48`); the CI workflow's release invocation (`android-ci.yml:247`).
- **INFERENCE:** that `androidTest` in this library module targets the **debug** variant. This follows from the AGP convention that the test build type defaults to `debug` and from the confirmed absence of any `testBuildType` override — it is a strong, standard inference, not a directly observed fact (observing it would require running a Gradle task, which this phase forbids).
- **UNKNOWN:** whether the release APK rebuild actually omits `libsoftether_test.so`. This phase deliberately did not rebuild; the packaging outcome is **UNKNOWN here** and is the explicit subject of the next phase.

---

## 7. Explicit No-Change Confirmation

```
Source .kt/.java:      NONE
Source .c/.h/.cpp:     NONE
App build.gradle:      NONE
Root build.gradle:     NONE
Device:                NONE
APK rebuild:           NONE
CI:                    NONE
Commit/tag/push:       NONE
Files edited:          only SoftEtherClient/src/main/cpp/CMakeLists.txt
                       and SoftEtherClient/build.gradle
```

Corroboration:
- `git rev-parse HEAD` → `071462816b1eef7e7f7459483fcd69909938cd11` (identical to P1).
- `find SoftEtherClient app build.gradle .github -newermt "-15 minutes" -type f` (excluding `/build/`) → exactly the two authorized files.
- All other modified/untracked paths in `git status` are the **pre-existing baseline** dirty tree carried into this phase; none of them was touched by this phase.
- The test library target was **not removed** — it remains fully buildable and runnable whenever `-DBUILD_TESTING=ON` is supplied (debug/androidTest).
- `abiFilters` unchanged (`'armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'`).
- Release signing configuration untouched.

---

## 8. Final Recommendation

**PROCEED TO VERIFICATION (rebuild + inspect APK)**

Suggested verification steps for the next phase:
1. `CI=1 ./gradlew :app:assembleProRelease` (with `GRADLE_OPTS="-Dorg.gradle.internal.launcher.localhost=127.0.0.1 -Dorg.gradle.internal.launcher.port=0"` if the port-contention error appears).
2. Confirm `unzip -l app/build/outputs/apk/pro/release/app-pro-release.apk | grep libsoftether_test.so` returns **nothing** for all four ABIs.
3. Confirm the same command against `app-pro-debug.apk` **still lists** `libsoftether_test.so` for the debug variant.
4. Optionally run `connectedAndroidTest` (or `:SoftEtherClient:connectedDebugAndroidTest`) to confirm `NativeConnectionTest` still resolves `softether_test`.
5. Confirm the release APK shrinks by the expected ~21 MB (the four removed ABI copies).
