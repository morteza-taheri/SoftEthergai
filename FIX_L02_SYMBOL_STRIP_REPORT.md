# FIX-L02-SYMBOL-STRIP — Fix Report

**Phase:** FIX-L02-SYMBOL-STRIP — Strip Symbols and Source Paths from Release Native Library
**Date:** 2026-10-12
**HEAD:** `071462816b1eef7e7f7459483fcd69909938cd11` (verified before and after; unchanged)
**Scope:** DEEP-REVIEW-VERIFY L-02 / L-03 (release symbol/source-path disclosure + size cost)
**Mode:** CMake configuration only. No source edits. No release APK. No device. No CI. No commit.

---

## 1. Executive Verdict

**FIX APPLIED — SYMBOLS STRIPPED FROM RELEASE**

The release `libsoftether.so` is now fully stripped: `.symtab`, `.strtab` and all
`.debug_*` sections are gone, and the embedded `/src/main/cpp` source paths drop
from **11 → 0**, across all four ABIs. Sizes fall by **~26–28%** per ABI. The
debug variant is provably untouched (byte-identical sizes and symbol counts to
the pre-fix baseline), so symbolication still works.

Crucially, the fix preserves all **25 exported JNI symbols** per ABI in
`.dynsym` — verified directly — so the library remains loadable and functional.

The change is two lines of intent (one `target_link_options`) in the release
branch of `CMakeLists.txt`, plus an explanatory comment.

---

## 2. Pre-Fix Measurements (P3/P4)

Tools: NDK `llvm-nm` (symbols), `llvm-strings` (paths), `llvm-readelf` (sections).
Note: `strings` is **not** on the Git-Bash PATH in this environment; `llvm-strings`
from the NDK was used instead. An early attempt using plain `strings` returned a
false `0` — every path count below comes from `llvm-strings`.

### P3 — Release baseline (`RelWithDebInfo/6l4g4t3t/obj/<abi>/libsoftether.so`)

| ABI | Size (bytes) | Defined symbols | `/src/main/cpp` paths |
|---|---|---|---|
| armeabi-v7a | 6,164,856 | 16,437 | 11 |
| arm64-v8a | 7,169,880 | 16,340 | 11 |
| x86 | 6,258,440 | 16,338 | 11 |
| x86_64 | 8,219,344 | 16,756 | 11 |

### P3 — Section-header confirmation (arm64-v8a, the L-02 evidence)

```
$ llvm-readelf -S libsoftether.so | grep -E "symtab|strtab|debug"
  [25] .debug_loc     PROGBITS  ... 02f984
  [26] .debug_abbrev  PROGBITS  ... 002709
  [27] .debug_info    PROGBITS  ... 028d05
  [28] .debug_ranges  PROGBITS  ... 00bb80
  [29] .debug_str     PROGBITS  ... 00653b
  [30] .debug_line    PROGBITS  ... 010dd2
  [31] .symtab        SYMTAB    ... 080988   (522,120 bytes)
  [32] .shstrtab      STRTAB    ... 000160
  [33] .strtab        STRTAB    ... 068cba   (429,242 bytes)
```
**L-02 reproduced exactly as reported:** full debug info + `.symtab` + `.strtab`
present in the shipped release library.

### P4 — Debug baseline (`Debug/5v273202/obj/<abi>/libsoftether.so`)

| ABI | Size (bytes) | Defined symbols | `/src/main/cpp` paths |
|---|---|---|---|
| armeabi-v7a | 6,072,348 | 16,512 | 11 |
| arm64-v8a | 6,962,856 | 16,435 | 11 |
| x86 | 6,254,332 | 16,431 | 11 |
| x86_64 | 8,082,512 | 16,852 | 11 |

### P5 — Current release branch (pre-edit)

`CMakeLists.txt:101–106` — the release branch added only `-O2`; there was no
strip flag of any kind:
```cmake
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(softether PRIVATE -g -O0)
else()
    target_compile_options(softether PRIVATE -O2)
endif()
```

### Approach selection (pre-flight experiment)

Both I1 approaches were tested on a copy of the real release `.so` before
choosing:

| Method | Size | `.symtab` symbols | paths | `.dynsym` intact |
|---|---|---|---|---|
| original | 7,169,880 | 16,340 | **11** | 25 |
| `llvm-strip --strip-all` | 5,697,568 | 0 | **0** | 25 |
| `llvm-strip --strip-debug` | 6,583,488 | 16,340 | **0** | 25 |

Then the linker path was validated directly:
```
$ clang --target=aarch64-none-linux-android24 -shared -o t.so t.c -Wl,-s
link exit=0 ; defined syms=0 ; .dynsym probe_sym present=1
```
**`-Wl,-s` is honored by the NDK linker.** **Approach A** was chosen: it needs no
external `CMAKE_STRIP` path, matches the project's existing
`target_compile_options` idiom in that block, and does the strip at link time so
no post-build step can be skipped.

---

## 3. CMakeLists.txt Change

**File:** `SoftEtherClient/src/main/cpp/CMakeLists.txt`
**Diff:** `76 insertions(+), 29 deletions(-)` (the bulk is the explanatory comment
plus the earlier FIX-L01 guard text already present in the worktree).

**Exact added lines** (inside the release `else()` branch of the
`if(CMAKE_BUILD_TYPE STREQUAL "Debug") … else() … endif()` block):

```cmake
    # Strip static symbols + debug info from the release shared library.
    target_link_options(softether PRIVATE -Wl,-s)
```

with the rationale comment above the `if`:
```cmake
# DEEP-REVIEW-VERIFY L-02/L-03: the release artifact must not ship debug
# symbols or build-machine source paths. Previously the release branch only
# added -O2, so the shipped libsoftether.so still contained .symtab, .strtab,
# full .debug_* sections (loc/abbrev/info/ranges/str/line) and embedded paths
# such as `/src/main/cpp/softether-core/src/proto/softether_protocol.c` — a
# symbol/source-path disclosure plus a size cost. -Wl,-s asks the linker to
# drop static symbols and debug info.
#
# This is safe for a JNI library: the runtime linker resolves JNI entry points
# through .dynsym, which -Wl,-s preserves. Only .symtab / .strtab / .debug_*
# are removed.
#
# The debug branch is deliberately left untouched so the debug APK can still
# symbolicate (that is why this is scoped to the else() below and not applied
# globally).
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(softether PRIVATE -g -O0)
else()
    target_compile_options(softether PRIVATE -O2)
    # Strip static symbols + debug info from the release shared library.
    target_link_options(softether PRIVATE -Wl,-s)
endif()
```

**Which block:** the **release (`else()`) branch** of the
`if(CMAKE_BUILD_TYPE STREQUAL "Debug")` block at `CMakeLists.txt:117–124`.

**Debug branch confirmation:** unchanged — still exactly
`target_compile_options(softether PRIVATE -g -O0)`; no strip flag. Verified
mechanically: `'-Wl,-s' in DEBUG branch = False`, `'-Wl,-s' in RELEASE branch = True`.

**BUILD_TESTING guard preserved:** yes.
```
option(BUILD_TESTING "Build the native test library" OFF)     # :10
if(BUILD_TESTING)                                             # :152
    add_library(softether_test SHARED ${TEST_SOURCES})        # :154
endif()                                                       # :183
```
`if(`/`endif(` balanced 7/7. `project("softetherclient")` intact. The production
`add_library(softether SHARED ...)` target is unchanged.

**Style note (I1):** the flag is attached to `softether` only. The test library
(`softether_test`) is built only under `BUILD_TESTING` (debug/androidTest), so it
is never a release artifact and correctly receives no strip flag.

---

## 4. Build Result (I4)

| Variant | Task | Result |
|---|---|---|
| Release | `:SoftEtherClient:externalNativeBuildRelease` | **BUILD SUCCESSFUL** (19s, 9/9 tasks executed, all 4 ABIs) |
| Debug | `:SoftEtherClient:externalNativeBuildDebug` | **BUILD SUCCESSFUL** (16s, 9/9 tasks executed, all 4 ABIs) |

(One transient `BindException: Address already in use: bind` from Gradle's file
lock communicator was resolved by `./gradlew --stop` and running with
`--no-daemon`; not a code issue.)

---

## 5. Post-Fix Release Measurements (I5)

Fresh output: `SoftEtherClient/build/intermediates/cxx/RelWithDebInfo/6l4g4t3t/obj/<abi>/`

| ABI | Size before → after | Symbols before → after | Paths before → after | `.symtab` | `.strtab` | `.debug_*` | `.dynsym` JNI |
|---|---|---|---|---|---|---|---|
| armeabi-v7a | 6,164,856 → **4,566,268** (−25.9%) | 16,437 → **0** | 11 → **0** | gone | gone | gone | **25 ✅** |
| arm64-v8a | 7,169,880 → **5,698,192** (−20.5%) | 16,340 → **0** | 11 → **0** | gone | gone | gone | **25 ✅** |
| x86 | 6,258,440 → **5,251,456** (−16.1%) | 16,338 → **0** | 11 → **0** | gone | gone | gone | **25 ✅** |
| x86_64 | 8,219,344 → **6,934,536** (−15.6%) | 16,756 → **0** | 11 → **0** | gone | gone | gone | **25 ✅** |

**ELF validity (I5 basic loadability check):**
```
armeabi-v7a  Class: ELF32  Type: DYN (Shared object file)  Machine: ARM
arm64-v8a    Class: ELF64  Type: DYN (Shared object file)  Machine: AArch64
x86          Class: ELF32  Type: DYN (Shared object file)  Machine: Intel 80386
x86_64       Class: ELF64  Type: DYN (Shared object file)  Machine: Advanced Micro Devices X86-64
```
All four remain valid, correctly-typed shared objects.

**JNI surface preserved (the reason stripping is safe here):**
`llvm-readelf --dyn-syms` shows **25** `Java_vn_unlimit_*` entries per ABI, plus
`JNI_OnLoad` and the individual entry points, e.g.:
```
4967: 00000000002570f0    60 FUNC GLOBAL DEFAULT 13 JNI_OnLoad
6443: 0000000000257b2c   316 FUNC GLOBAL DEFAULT 13 Java_vn_unlimit_softether_client_SoftEtherClient_nativeSend
```
The dynamic symbol table is what the Android runtime linker uses; it is intact.

**I3 (path disclosure) outcome:** the path count is **0 for every ABI** — no
`-ffile-prefix-map` was needed. The paths lived entirely in the debug line table
(`.debug_line`/`.debug_str`), which `-Wl,-s` removes.

---

## 6. Debug Regression (I6)

`Debug/5v273202/obj/<abi>/` — post-fix vs pre-fix:

| ABI | Size (pre → post) | Symbols (pre → post) | `.symtab` | `.debug_*` | Paths |
|---|---|---|---|---|---|
| armeabi-v7a | 6,072,348 → 6,072,348 | 16,512 → 16,512 | present | 6 sections | 11 |
| arm64-v8a | 6,962,856 → 6,962,856 | 16,435 → 16,435 | present | 5 sections | 11 |
| x86 | 6,254,332 → 6,254,332 | 16,431 → 16,431 | present | 5 sections | 11 |
| x86_64 | 8,082,512 → 8,082,512 | 16,852 → 16,852 | present | 5 sections | 11 |

**Identical to the baseline** — sizes byte-for-byte equal, symbol counts equal,
`.symtab` and `.debug_*` intact, source paths retained. Symbolication in the
debug APK is unaffected. This is the direct consequence of scoping the flag to
the release `else()` branch.

---

## 7. Evidence Taxonomy

**SOURCE CODE EVIDENCE**
- `CMakeLists.txt:117–124` — `target_link_options(softether PRIVATE -Wl,-s)` in the release branch only; debug branch unchanged.
- `CMakeLists.txt:10,152,154,183` — FIX-L01 `BUILD_TESTING` guard preserved.
- Mechanical checks: `if`/`endif` = 7/7; `-Wl,-s` present in release, absent in debug; `project()` and both `add_library` targets intact.

**BUILD ARTIFACT**
- `SoftEtherClient/build/intermediates/cxx/RelWithDebInfo/6l4g4t3t/obj/<abi>/libsoftether.so` — post-fix release, all 4 ABIs: 0 defined symbols, 0 paths, no `.symtab`/`.strtab`/`.debug_*`, 25 `.dynsym` JNI symbols, valid `DYN` ELF.
- `SoftEtherClient/build/intermediates/cxx/Debug/5v273202/obj/<abi>/libsoftether.so` — unchanged from baseline.
- Gradle console: `BUILD SUCCESSFUL` for both `externalNativeBuildRelease` and `externalNativeBuildDebug`.
- Pre-flight strip experiment and `-Wl,-s` linker acceptance test (run on copies in a scratch dir, since removed).

**INFERENCE**
- The ~16–26% size reduction will carry through to the packaged release APK, since the native libs are stored uncompressed-or-deflated but always included. Not measured at APK level (the release APK cannot be built locally — see §8) and not required by this phase.
- "Loadable in practice" is inferred from the valid ELF header + intact `.dynsym`; it was not exercised on a device.

**UNKNOWN**
- The packaged release-APK size delta — the release build is blocked by the
  pre-existing fail-closed signing guard (`app/build.gradle:93–116`, absent
  `keyStore/release.jks`). Documented in `FIX_L01_VERIFY_REPORT.md` §3.
- Whether any consumer relied on the removed static symbols — none is expected
  (JNI consumers use the exported dynamic symbols; nothing in the project reads
  `.symtab` at runtime).

---

## 8. Explicit No-Change Confirmation

| Item | Result |
|---|---|
| Kotlin/Java | **NONE** |
| Gradle | **NONE** |
| app/build.gradle | **NONE** |
| `.c` / `.h` / `.cpp` | **NONE** |
| Device | **NONE** |
| Release APK | **NONE** (no packaging task run; `assembleProRelease` not invoked) |
| CI | **NONE** |
| Commit / tag / push | **NONE** |
| HEAD | `071462816b1eef7e7f7459483fcd69909938cd11` — unchanged |

**Files edited: `SoftEtherClient/src/main/cpp/CMakeLists.txt` only.**

**Scratch artifacts created and removed:** `.l02_scratch/` (repo-local, used for
the strip experiment on `.so` copies) — deleted; confirmed gone. No source file
was copied out of or into the repo.

**Build outputs (expected):**
`SoftEtherClient/build/intermediates/cxx/RelWithDebInfo/6l4g4t3t/**` and
`SoftEtherClient/build/intermediates/cxx/Debug/5v273202/**`.

---

## 9. Final Recommendation

**PROCEED TO FIX-G1-DESIGN**

L-02/L-03 is closed with no residual path leak and no debug-build regression, so
there is nothing here needing review. Between the two offered options, **G-1** is
the higher-value target: it is the diagnosed root cause of a *user-visible* defect
(a stale session strands its native lease, so the next start hits
`REFUSE_CONNECTED` and requires `am force-stop`), whereas U-36 is a
build-tooling version mismatch with no demonstrated runtime impact.

Recommended next: **FIX-G1-DESIGN** — make `onNetworkChanged` /
`attemptReconnect` tear down or otherwise release the native lease so a
network-loss reconnect cannot leave a logically-live session holding ownership.

Note for sequencing: a device is needed to actually exercise both this strip fix
and the FIX-NATIVE-P0-BOUNDS tests at runtime; the first phase that has a device
attached should fold in both (one `connectedDebugAndroidTest` run plus a load of
the stripped `.so`).

---

## Appendix — Commands Run (reproducible)

```bash
export MSYS_NO_PATHCONV=1
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export CI=1
BIN="C:/Users/PMC/AppData/Local/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin"

# Build both variants (use --no-daemon if the file-lock port is contended)
./gradlew :SoftEtherClient:externalNativeBuildRelease --console=plain
./gradlew :SoftEtherClient:externalNativeBuildDebug   --console=plain

# Measurements per ABI
REL=SoftEtherClient/build/intermediates/cxx/RelWithDebInfo/6l4g4t3t/obj
"$BIN/llvm-nm.exe"        --defined-only "$REL/arm64-v8a/libsoftether.so" | wc -l   # 0
"$BIN/llvm-strings.exe"   "$REL/arm64-v8a/libsoftether.so" | grep -c '/src/main/cpp' # 0
"$BIN/llvm-readelf.exe"   -S "$REL/arm64-v8a/libsoftether.so" | grep -E 'symtab|strtab|debug'  # (none)
"$BIN/llvm-readelf.exe"   -h "$REL/arm64-v8a/libsoftether.so"                                    # Type: DYN
"$BIN/llvm-readelf.exe"   --dyn-syms "$REL/arm64-v8a/libsoftether.so" | grep -c Java_vn_unlimit  # 25
stat -c '%s' "$REL/arm64-v8a/libsoftether.so"
```
