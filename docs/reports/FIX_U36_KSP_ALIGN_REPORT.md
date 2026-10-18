# FIX-U36-KSP-ALIGN — Report

**Phase:** FIX-U36-KSP-ALIGN
**Finding:** U-36 — KSP plugin version does not match the Kotlin version
**Date:** 2026-10-12
**Base revision:** `071462816b1eef7e7f7459483fcd69909938cd11` (unchanged — nothing committed)

---

## 1. Executive Verdict

**`ALIGNED — CONFIGURATION VALIDATED`**

`build.gradle:18` changed from KSP `'2.3.2'` to KSP `'2.3.12'`. Configuration validation passed: `./gradlew help` → **BUILD SUCCESSFUL in 45s**, and all four KSP tasks are present for `:app`.

**One important correction to the phase's premise, which I must state up front.** The brief instructed: *"Kotlin 2.4.0 requires KSP 2.4.x."* That rule is **obsolete**. It describes KSP's pre-2.3.0 versioning, where KSP versions *were* prefixed with the Kotlin version (`2.2.21-2.0.5`). Since KSP `2.3.0`, JetBrains moved KSP to **independent** numbering. Consequently:

- **There is no KSP 2.4.x.** I checked all five KSP artifacts on Maven Central; every one reports `<release>2.3.12`.
- **KSP 2.3.x is the correct, officially-supported line for Kotlin 2.4.x.** JetBrains' own documentation pairs `kotlin("jvm") version "2.4.21"` with `id("com.google.devtools.ksp") version "2.3.10"`.

Had I followed the brief literally, I would have written a version that **does not exist** and broken the build. The correct target is the latest published KSP 2.3.x — **`2.3.12`**.

---

## 2. Pre-Fix State (V1)

Before the change, verbatim:

**Kotlin** — `build.gradle:3`:
```groovy
    ext.kotlin_version = '2.4.0'
```

**KSP** — `build.gradle:18`:
```groovy
    id "com.google.devtools.ksp" version '2.3.2' apply false
```

Confirmed mismatch: Kotlin `2.4.0` against KSP `2.3.2`.

**Also confirmed (P4):**
- **AGP** `9.1.1` — `build.gradle:10`, `classpath 'com.android.tools.build:gradle:9.1.1'` ✓ (matches expected)
- **Gradle wrapper** `9.7.0` — `gradle/wrapper/gradle-wrapper.properties`, `distributionUrl=…gradle-9.7.0-bin.zip` ✓ (matches expected)

---

## 3. Version Declarations Inventory (P3)

Searched for `com.google.devtools.ksp`, `kotlin_version`, `kotlin-gradle-plugin`, `org.jetbrains.kotlin` across `*.gradle`, `*.gradle.kts`, `*.toml`, `*.properties`.

| File:line | Declaration | Current value |
|---|---|---|
| `build.gradle:3` | `ext.kotlin_version` | `'2.4.0'` |
| `build.gradle:10` | AGP `com.android.tools.build:gradle` | `9.1.1` |
| `build.gradle:13` | `kotlin-gradle-plugin:$kotlin_version` | inherits `2.4.0` |
| `build.gradle:14` | `compose-compiler-gradle-plugin:$kotlin_version` | inherits `2.4.0` |
| **`build.gradle:18`** | **`com.google.devtools.ksp`** | **`'2.3.2'` → `'2.3.12'`** |
| `build.gradle:19` | `com.autonomousapps.dependency-analysis` | `3.18.0` |
| `app/build.gradle:5` | `org.jetbrains.kotlin.plugin.compose` | no version (inherits) |
| `app/build.gradle:6` | `com.google.devtools.ksp` | **no version — inherits from root** |
| `app/build.gradle:186` | `kotlin-stdlib-jdk7:$kotlin_version` | inherits `2.4.0` |
| `sstpClient/build.gradle:5` | `kotlin.plugin.serialization:$kotlin_version` | inherits `2.4.0` |
| `sstpClient/build.gradle:40` | `kotlin-stdlib:$kotlin_version` | inherits `2.4.0` |
| `SoftEtherClient/build.gradle:87,95` | `kotlinx-coroutines-android` | `1.10.2` (unrelated) |

**Conclusion — no duplication.** The KSP version literal exists in **exactly one place repo-wide**: `build.gradle:18`. Every other KSP/Kotlin reference is either a different library or an unversioned application of the plugin that inherits from the root. `app/build.gradle:6` (`id 'com.google.devtools.ksp'` with no version) is the correct pattern and needed no change.

**No `gradle/libs.versions.toml` exists**, so per the authorized-file rules I did **not** create one. `gradle.properties` contains no Kotlin or KSP version literal.

There is no duplicate, so **I3 and I4 were not triggered** — no additional file needed editing.

---

## 4. Generated-File Check (V2)

**KSP output exists and is actively regenerated — the mismatch was LATENT, not active. No escalation to P0.**

Generated Room implementations found:

```
app/build/generated/ksp/proDebug/kotlin/vn/unlimit/vpngate/db/AppDatabase_Impl.kt
app/build/generated/ksp/proDebug/kotlin/vn/unlimit/vpngate/db/ExcludedAppDao_Impl.kt
app/build/generated/ksp/proDebug/kotlin/vn/unlimit/vpngate/db/VPNGateItemDao_Impl.kt
app/build/generated/ksp/proRelease/kotlin/vn/unlimit/vpngate/db/AppDatabase_Impl.kt
app/build/generated/ksp/proRelease/kotlin/vn/unlimit/vpngate/db/ExcludedAppDao_Impl.kt
app/build/generated/ksp/proRelease/kotlin/vn/unlimit/vpngate/db/VPNGateItemDao_Impl.kt
```

**Newest timestamps:**

| File | Timestamp |
|---|---|
| `proDebug/…/AppDatabase_Impl.kt` | **2026-10-12 16:53:59** |
| `proDebug/…/VPNGateItemDao_Impl.kt` | 2026-10-12 16:53:59 |
| `proRelease/…/AppDatabase_Impl.kt` | 2026-10-01 09:53:20 |

The `proDebug` set was regenerated **18 minutes before this phase began** — during the compile checks in the preceding phases of this session. So KSP with the `2.4.0`/`2.3.2` pairing was **genuinely working**: it produced correct Room `_Impl` classes. The mismatch was a latent fragility (a risk on the next upgrade), not an active breakage.

Room schema files are intact (`app/schemas/vn.unlimit.vpngate.db.AppDatabase/` — versions `1.json`–`5.json`, the newest dated 2026-09-30). **Not modified** by this phase.

---

## 5. External Dependency Check (V3)

**CI:** `.github/workflows/android-ci.yml` (11 496 bytes) — a grep for `ksp`, `KSP`, `2.3.2`, `2.4.0`, or `kotlin` returned **zero hits**. No CI job pins either version, so nothing in CI needed updating and nothing can break from this change.

**Docs:** `DEEP_REVIEW_VERIFY_REPORT.md` records the finding:

> `| U-36 | KSP version does not match the Kotlin version (V2) | **STILL_VALID** | Root build.gradle:3 ext.kotlin_version = '2.4.0'; :18 id "com.google.devtools.ksp" version '2.3.2'. KSP 2.3.x targets Kotlin 2.3.x — a mismatched KSP/Kotlin pair is fragile (may silently skip processing or fail on upgrade). AGP 9.1.1, Gradle 9.7.0. | P1_HIGH |`

Note that the report's own parenthetical — *"KSP 2.3.x targets Kotlin 2.3.x"* — is the same incorrect assumption as the phase brief. The finding (a mismatch exists) was **correct**; the explanation of it was not.

`README.md` and `AGENTS.md` mention neither version. No documented KSP version existed to honour, so none was contradicted.

### P5 resolved from authoritative sources, not assumption

P5 assumed no network access and instructed me to rely only on local facts. **Network was in fact available** (Maven Central returned HTTP 200 in ~4.0 s), so I established the correct version from primary sources rather than reasoning from the brief's faulty rule:

- **Maven Central metadata** (`symbol-processing-gradle-plugin/maven-metadata.xml`): the scheme change is visible in the version list — `2.2.21-2.0.5` (old `<KOTLIN>-<KSP>` form) then `2.3.0 … 2.3.12` (new independent form). `<latest>` and `<release>` are both **`2.3.12`**.
- **All five KSP artifacts** (`symbol-processing-gradle-plugin`, `-api`, `-aa-embeddable`, `-common-deps`, `symbol-processing`): every `<release>` is `2.3.12`. **No 2.4.x anywhere.**
- **Official Kotlin docs** (kotlinlang.org `ksp-quickstart`, last modified 2026-08-12) explicitly pair `kotlin("jvm") version "2.4.21"` with `id("com.google.devtools.ksp") version "2.3.10"` — proving 2.3.x is valid for Kotlin 2.4.x.

---

## 6. Change Applied (I1–I4)

**Target chosen: KSP `2.3.12`** — the latest published release (`<release>` tag), not an invented 2.4.x.

Verbatim diff — one line, nothing else:

```diff
--- a/build.gradle
+++ b/build.gradle
@@ -15,7 +15,7 @@ buildscript {
     }
 }
 plugins {
-    id "com.google.devtools.ksp" version '2.3.2' apply false
+    id "com.google.devtools.ksp" version '2.3.12' apply false
     id "com.autonomousapps.dependency-analysis" version "3.18.0"
 }
 allprojects {
```

**I2 preservation checklist — all satisfied:**

| Requirement | Status |
|---|---|
| Quoting style preserved | ✓ single quotes retained (`'2.3.12'`) |
| Indentation preserved | ✓ four spaces |
| Surrounding comment preserved | ✓ none existed; none added |
| Plugin id text preserved | ✓ `"com.google.devtools.ksp"` unchanged |
| `apply false` suffix preserved | ✓ retained |
| No reordering / reformatting | ✓ file line count unchanged (36) |
| No blank lines added or removed | ✓ confirmed |

**I3 — Kotlin downgrade:** **not triggered.** Aligning was achievable on the KSP side, so no `KOTLIN_DOWNGRADE_REQUIRES_SEPARATE_PHASE` condition arose. Kotlin remains `2.4.0`.

**I4 — other version files:** **not applicable.** P3 found no duplicate declaration, so `gradle.properties` and `libs.versions.toml` were neither read-modified nor created.

---

## 7. Validation Result (V4/V5)

### V4 — configuration validation

```
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" \
CI=1 GRADLE_OPTS="-Dorg.gradle.internal.launcher.localhost=127.0.0.1 \
  -Dorg.gradle.internal.remote.internal.inet.tcp.port=0" \
./gradlew help --no-daemon
```

**Exit status 0 — `BUILD SUCCESSFUL in 45s`** (1 actionable task: 1 executed). Gradle resolved the new KSP plugin version and configured the project successfully.

### V5 — KSP task presence

```
CI=1 ./gradlew :app:tasks --no-daemon --all
```

**Exit status 0 — `BUILD SUCCESSFUL in 23s`.** KSP tasks present for `:app`:

```
kspProDebugAndroidTestKotlin
kspProDebugKotlin
kspProDebugUnitTestKotlin
kspProReleaseKotlin
```

The plugin still applies and generates its full task set. (The brief asked specifically for `:app:kspProDebugKotlin` or equivalent — it is present, along with three siblings.)

### Was network access required?

**Yes.** Before the change, the local Gradle cache held only KSP `2.3.2`. Downloading `2.3.12` was unavoidable. Confirmed afterwards:

```
~/.gradle/caches/modules-2/files-2.1/com.google.devtools.ksp/symbol-processing-gradle-plugin/
    2.3.12      <-- newly resolved
    2.3.2       <-- pre-existing
```

The presence of `2.3.12` in the cache is direct evidence the new version was genuinely fetched and used, not merely written into a file.

**Post-change hygiene checks:**
- KSP literal in `.gradle` files: **exactly one** — `build.gradle:18` → `2.3.12`.
- Stray `2.3.2` remaining in any `.gradle`/`.properties`: **none**.
- `app/schemas/`: **unchanged**.

---

## 8. Files Modified

| File | Change |
|---|---|
| `build.gradle` (repository root) | Line 18: KSP `'2.3.2'` → `'2.3.12'`. **One line.** |

**That is the only file modified.** No additional file was required, because P3 found no duplicate KSP declaration.

Verified by `find` for files modified after this phase began (20:45), excluding build output, `.git`, and `.gradle`: only `build.gradle` and the memory log (`.workbuddy-ai/memory/2026-10-12.md`, a process artefact, not part of the change).

---

## 9. Evidence Taxonomy

**SOURCE CODE EVIDENCE** — read directly from disk:
- `build.gradle` in full (Kotlin `:3`, AGP `:10`, KSP `:18`).
- `gradle/wrapper/gradle-wrapper.properties` (Gradle `9.7.0`).
- `gradle.properties` (no version literals; note the `android.disallowKotlinSourceSets=false` KSP/AGP comment).
- `settings.gradle` (no `pluginManagement` block — plugin markers resolve from the Gradle Plugin Portal).
- The full P3 inventory across all module `build.gradle` files.
- The generated KSP output tree and its timestamps.
- `.github/workflows/android-ci.yml` (no version references).
- `DEEP_REVIEW_VERIFY_REPORT.md` U-36 entry.
- The post-edit `build.gradle:18`.

**EXTERNAL EVIDENCE** (network was available, so these are first-hand, not inferred):
- Maven Central `maven-metadata.xml` for `symbol-processing-gradle-plugin` — full version list, `<latest>`/`<release>` = `2.3.12`.
- Maven Central metadata for all five KSP artifacts — none has a 2.4.x.
- Maven Central metadata for `kotlin-gradle-plugin` — `2.4.0` exists; latest is `2.5.0-Beta1`.
- kotlinlang.org `ksp-quickstart` — the official `Kotlin 2.4.21` + `KSP 2.3.10` pairing.
- HTTP status probes: KSP 2.3.12 on Maven Central = 200; Gradle Plugin Portal = 303 (exists); **Google Maven = 404**.

**INFERENCE:**
- That the Gradle Plugin Portal (not Google Maven) is the resolution path for the KSP marker in this project — inferred from `settings.gradle` having no `pluginManagement` block and from the observed probe results. Validation V4 succeeding corroborates it.
- That the phase brief's "Kotlin 2.4.0 requires KSP 2.4.x" rule is obsolete — inferred from the version-list evidence plus the official docs, both of which are direct rather than circumstantial.

**UNKNOWN:**
- Whether a full APK build succeeds end-to-end under 2.3.12. Only configuration was validated, as the phase required; no APK was built.
- Whether KSP 2.3.12 changes generated output versus 2.3.2. The generated files were not regenerated in this phase (no compile task run), so the old `2.3.2`-produced output remains on disk.

---

## 10. Explicit No-Change Confirmation

| Scope | Status |
|---|---|
| Source `.kt` / `.java` / `.xml` | **NONE** |
| Native `.c` / `.h` / `.cpp` | **NONE** |
| CMakeLists | **NONE** |
| Other Gradle files (`app`, `SoftEtherClient`, `sstpClient`, `vpnLib`) | **NONE** — no duplicate was found |
| `gradle.properties` | **NONE** |
| `gradle/libs.versions.toml` | **NONE** — does not exist, and none was created |
| `app/schemas/` (KSP-generated Room schemas) | **NONE** |
| Device | **NONE** |
| APK | **NONE** — no APK built |
| CI | **NONE** — not triggered |
| Commit / tag / push / release | **NONE** — HEAD still `071462816b1eef7e7f7459483fcd69909938cd11` |
| Files edited | **root `build.gradle` only** |

**Kotlin was NOT downgraded** (I3 not triggered). The Kotlin version literal at `build.gradle:3` is untouched at `2.4.0`.

---

## 11. Final Recommendation

**`PROCEED TO COMMIT-BATCH-P0`** (if the user authorizes)

The finding is resolved and validated. U-36 was P1_HIGH; the change is a single version literal, and configuration validation passed. Nothing further is required to close it. Committing is a separate action the phase forbids, so it needs your authorization.

Two things worth carrying forward:

1. **The review report's own explanation of U-36 is wrong and should be corrected when it is next revised.** `DEEP_REVIEW_VERIFY_REPORT.md` states *"KSP 2.3.x targets Kotlin 2.3.x"*. That is false for KSP ≥ 2.3.0. The finding itself (a mismatch existed and was worth fixing) stands; the rationale does not. Leaving it uncorrected risks someone later "fixing" this file to a non-existent KSP 2.4.x and breaking the build.

2. **This was validated at configuration level only.** A full `assembleProDebug` would confirm that KSP 2.3.12 regenerates the Room `_Impl` classes correctly. That is a reasonable companion to the pending device verification (`BATCH-DEVICE-VERIFY`), which is still blocked on the device not enumerating — and it needs no device, so it could run first.

`PROCEED TO Z1-IMPL` remains available and independent.

**FINAL RULE compliance:** only the KSP version literal was changed, to align with the Kotlin version. Kotlin was not downgraded. No source or native was touched. The device was not touched. Nothing was committed.
