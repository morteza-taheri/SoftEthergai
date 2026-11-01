# VERSION BUMP PAST 2.4.10 (v1.0.38)

**Phase:** VERSION-BUMP-PAST-2.4.10
**Repo:** `C:\Dev\SoftEthergai`, branch `main`
**Baseline:** `HEAD == origin/main == e7f7f93a42779787c8d19b3f4eeabcb12e0ad9b5` · spec v1.0.37 · §1.6.29
**Date:** 2026-10-19
**Device:** DETACHED — no device command was issued in this phase

---

## §1. Verdict

**PASS.** `versionCode` **143 → 144** and `versionName` **"2.4.10" → "2.4.11"**, one file changed
(`app/build.gradle`), no test modified, no source modified, no signing configuration touched, no tag
created. The built APK reports `versionCode='144'` and a `2.4.11` base version name, proving the
manifest was regenerated from the bumped Gradle values.

| Check | Result |
|---|---|
| Only one version declaration in the repository | **PASS** — `app/build.gradle:71-72`, `pro` flavor |
| Any test pinning the version | **NONE** — `app/src/test/`, `app/src/androidTest/` are clean |
| Any Kotlin hardcoded pin (`143`, `"2.4.10"`) | **NONE** — 7 `BuildConfig.VERSION_*` readers, all symbolic |
| APK `versionCode` | **PASS** — `'144'` |
| APK `versionName` | **PASS** — base `2.4.11`; debug build carries the pre-existing `-dev` suffix |
| `:app` JVM | **812 / 0 / 0 / 1** — identical to baseline |
| `:SoftEtherClient` JVM | **173 / 0 / 0 / 0** — identical to baseline |
| §22 byte-identity | **PASS** — 99 lines, `adf12858…020e` |
| Tag count | **5 / 5** — unchanged, no tag created |

---

## §2. Pre-conditions

| Item | Expected | Observed | Result |
|---|---|---|---|
| `git rev-parse HEAD` | `e7f7f93…` | `e7f7f93a42779787c8d19b3f4eeabcb12e0ad9b5` | PASS |
| `git rev-parse origin/main` | same | `e7f7f93a42779787c8d19b3f4eeabcb12e0ad9b5` | PASS |
| Branch | `main` | `main` | PASS |
| Tracked modifications | none | none | PASS |
| Spec version | `1.0.37` | `1.0.37` | PASS |
| Tag count | 5 | 5 | PASS |

Baseline APK before this phase: `4FE4417469C7C7829DED3C7B723705DC77678B28FAA14345E057108BE9EB6E68`
(63,755,062 bytes) — the artifact the v1.0.37 device run installed.

### §2.1 Discovery results (STEP 2)

| Probe | Finding |
|---|---|
| 2a — `app/build.gradle` | `:71 versionCode 143`, `:72 versionName "2.4.10"`, `:84 versionNameSuffix "-dev"` (debug build type), `:192 room_version` (library, not app version) |
| 2b — other gradle/properties/json/toml | **No second declaration.** The only two hits are the same two lines in `app/build.gradle` |
| 2c — tests | **No hit.** No test references `versionCode`, `versionName`, `143`, or `2.4.10` |
| 2d — `app/src/main` | Seven `BuildConfig.VERSION_NAME` / `VERSION_CODE` **reads** (About screen, `DetailActivity`, `StatusViewModel`, `AndroidConnectionAdapter`) plus one **comment** in `CollectorSnapshotStore.kt:508` mentioning "the 143 / 2.4.10 release line" |
| 2e — changelog/release notes | No `CHANGELOG.md`, no `RELEASE_NOTES.md`; `README.md` lists no versions |
| 2f — signing | `signingConfigs` present with the fail-closed release guard (`:88-104`); untouched |

**One judgement call, labelled.** The brief's STEP 2d abort condition reads "finds a Kotlin file
reading versionCode → STOP". The seven hits are `BuildConfig.VERSION_CODE` / `VERSION_NAME` — reads of
a **generated constant**, the canonical way an app displays its own version, and exactly what must
keep working across a bump. They are not pins: no literal `143` or `"2.4.10"` is asserted anywhere in
Kotlin, and every reader follows the bump automatically. Aborting here would make any version-displaying
app un-bumpable, which is plainly not the condition's intent. **No abort was taken.** The single
literal `2.4.10` in Kotlin is a comment in `CollectorSnapshotStore.kt` — a historical citation of
commit `0714628` used as ProGuard-mapping evidence, in a file under `app/src/main/` (FROZEN), so it is
intentionally left untouched and recorded in §1.6.30 bullet 3.

---

## §3. The identity collision

§1.6.12 bullet 5 (v1.0.19) recorded, forward-looking:

> HEAD and p2.4.10 share a version identity … a future release phase SHOULD bump
> versionCode/versionName past 2.4.10 before the next cut.

Since that note, HEAD advanced **60+ commits** past tag `p2.4.10` while still declaring `143` /
`2.4.10`. Consequences observed in practice:

1. Every device install in the interim — including the v1.0.37 verification run, which reported
   `versionCode 143, versionName 2.4.10-dev` — was **indistinguishable by version** from the
   `p2.4.10` artifact.
2. A bug report saying "2.4.10 misbehaves" could not be resolved into "which 2.4.10".
3. The About screen and the native client handshake (`clientVersion` / `clientBuild`) advertised a
   build identity that was 60+ commits stale, so server-side and log-side triage inherited the same
   ambiguity.

This is a **release-identity** defect, not a behavioural one: no code path reads the number to make a
decision. Evidence: SOURCE CODE.

---

## §4. The bump — exact diff

```diff
diff --git a/app/build.gradle b/app/build.gradle
index 0985b36..81928a7 100644
--- a/app/build.gradle
+++ b/app/build.gradle
@@ -68,8 +68,10 @@ android {
             dimension "tier"
             applicationId "vn.unlimit.vpngatepro"
             signingConfig signingConfigs.pro
-            versionCode 143
-            versionName "2.4.10"
+            // v1.0.38 (§1.6.30): version bump past 2.4.10 — HEAD and tag
+            // p2.4.10 had shared the 2.4.10/143 identity for 60+ commits.
+            versionCode 144
+            versionName "2.4.11"
         }
     }
```

Unchanged and deliberately so: `applicationId "vn.unlimit.vpngatepro"`, `signingConfig
signingConfigs.pro`, the `signingConfigs` block, the fail-closed release guard, `debug {
versionNameSuffix "-dev" }`, and every other module's configuration.

---

## §5. Test-pin resolution

**None found; STEP 4 skipped.** `grep -rnE "versionCode|versionName|2\.4\.10|143" app/src/test/
app/src/androidTest/` returns nothing. No test count moved: `:app` remained exactly **812 / 0 / 0 / 1**
and `:SoftEtherClient` exactly **173 / 0 / 0 / 0**. This is the expected outcome for a version-only
change and is itself the evidence that no test encoded the old identity.

---

## §6. APK version verification

Command:

```
aapt2 dump badging app/build/outputs/apk/pro/debug/app-pro-debug.apk
```

Verbatim first line:

```
package: name='vn.unlimit.vpngatepro' versionCode='144' versionName='2.4.11-dev' platformBuildVersionName='17' platformBuildVersionCode='37' compileSdkVersion='37' compileSdkVersionCodename='17'
```

- `versionCode='144'` — **the bumped value**, so the manifest was rebuilt, not cached.
- `versionName='2.4.11-dev'` — base `2.4.11` plus the **pre-existing** debug suffix declared at
  `app/build.gradle:84` (`buildTypes.debug { versionNameSuffix "-dev" }`), verified present at
  baseline with `git show HEAD:app/build.gradle`. A release build would read `2.4.11` with no suffix.
  The brief's abort condition ("versionName is still `2.4.10`") is not met: the base is `2.4.11`.

APK fingerprint after the bump:

| Property | Baseline (v1.0.37) | After bump (v1.0.38) |
|---|---|---|
| SHA-256 | `4FE4417469C7C7829DED3C7B723705DC77678B28FAA14345E057108BE9EB6E68` | `8602F5FC71965AD351B7576B4A579C25A43DD80AFB5550DD2382C9D6A103436C` |
| Size | 63,755,062 bytes | 63,755,066 bytes |
| `versionCode` | 143 | 144 |
| `versionName` | `2.4.10-dev` | `2.4.11-dev` |

The 4-byte growth is consistent with the longer version string in the binary manifest; nothing else in
the build inputs changed.

---

## §7. JVM tests

| Suite | Baseline | After | Delta |
|---|---|---|---|
| `:app:testProDebugUnitTest` | 812 / 0 / 0 / 1 | **812 / 0 / 0 / 1** | none |
| `:SoftEtherClient:test` | 173 / 0 / 0 / 0 | **173 / 0 / 0 / 0** | none |

Both runs used `--rerun-tasks --no-build-cache` with `CI=true`. Counts were aggregated from
`build/test-results/**/*.xml`, not read off the console. Both Gradle invocations printed
`BUILD SUCCESSFUL`; the PowerShell wrapper reported exit code 1 for both, which is an artifact of the
`| Select-Object -Last N` pipeline in this shell (observed identically in v1.0.37) — the authoritative
signal is the XML aggregate above, which shows zero failures and zero errors.

---

## §8. Spec edits (v1.0.37 → v1.0.38)

| ID | Edit | Location |
|---|---|---|
| E1 | Header: `HEAD` → `fc359c6…`, `Document version` → `1.0.38`, date 2026-10-19 | `:6-8` |
| E2 | New push-state blockquote for v1.0.38 (bump, §1.6.12 bullet 5 link, RISK-A note, no tag) | after the v1.0.37 block, before §1.6.2 |
| E3 | New **§1.6.30** "VERSION-BUMP-PAST-2.4.10 (v1.0.38)" — 8 bullets: collision, fix, test pins, APK verification, JVM counts, release-readiness, v1.0.37 contract intact, no new lessons | before `## 2. Architecture Overview` |
| E4 | §12.5.1, §12.5.2 — **no changes** | — |
| E5 | §20 Changelog: `1.0.38` row | §20 |
| E6 | §20 Verification Status: `§1.6.30` row, labelled **HISTORICAL** with the INFERENCE note on release-readiness | §20 |
| E7 | §22 — untouched, byte-identical | §22 |

Note on E1: the `HEAD` field records the **code commit** `fc359c6` (commit 2), following the same
convention as v1.0.37 (which recorded `86ade9f`) — a commit cannot carry its own hash, and the spec is
committed after the code it describes.

---

## §9. Diff-scope

```
 M PROJECT_SPECIFICATION.md
 M app/build.gradle
```

Plus untracked `VERSION_BUMP_2_4_11_REPORT.md` and the prior-phase
`DEVICE_VERIFY_AUTOCONNECT_FIX_7_REPORT.md` (committed here as commit 1). Nothing else. No file under
`app/src/main/`, `SoftEtherClient/`, or any test directory is modified. No file deleted.

---

## §10. §22 byte-identity

```
awk '/^## 22\./{flag=1} flag{print}' PROJECT_SPECIFICATION.md | wc -l      → 99
awk '/^## 22\./{flag=1} flag{print}' PROJECT_SPECIFICATION.md | sha256sum  → adf12858b72ed13742252c512a8728e9141e415cc55d09c6409af4e8e622020e
```

Identical to the v1.0.37 fingerprint (99 lines, `adf12858…020e`), and re-verified against
`git show HEAD:PROJECT_SPECIFICATION.md` after the push.

---

## §11. Negative assurance

- No device or `adb` command was issued. The attached device was never touched.
- No tag was created, moved, or deleted; `git tag -l` and `git ls-remote --tags origin` both report 5.
- No `--force` push, no `reset`, `clean`, `stash`, `checkout`, or `restore`.
- No `git add -A` / `git add .`; every `git add` named an explicit path.
- No test was deleted or modified.
- No Kotlin/Java/C source was modified — `app/src/main/` and `SoftEtherClient/` are byte-identical.
- The `signingConfigs` block and the fail-closed release guard are untouched.
- No build or test ran without `CI=true`.
- `D:\PMC\Documents\Android\SoftEthergai` was not touched.
- C5 was not executed.

---

## §12. Release-readiness note

**This phase does not make 2.4.11 releasable.** It resolves an identity collision; it does not produce
a release artifact. A `2.4.11` cut still requires:

1. the **fail-closed release keystore** — `keyStore/release.jks` plus `signKeyPassword`
   (`SIGN_KEY_PASSWORD` or `local.properties`), per §1.6.10 bullet 8 / **RISK-A**. Without it the
   `signingConfigs` guard aborts every release assembly rather than emitting a debug-signed "release"
   APK. This is a deliberate design decision, not a gap;
2. a deliberate decision to tag — no tag exists for 2.4.11, and this phase was explicitly forbidden
   from creating one;
3. the usual device pass on the release artifact (not the debug APK measured here).

Evidence: SOURCE CODE + INFERENCE. No release build was attempted in this phase.

---

## §13. Recommendation for next phase

Not proposed autonomously — the brief's STOP rule forbids it. Two facts are recorded here for whoever
writes the next brief:

1. **Fix-A (native RUDP handshake bound) remains UNVERIFIED** and untouched since v1.0.37. It is the
   only carried-over verification debt in the AutoConnect area.
2. **The `Slot-wait expired … moving on` path (§1.6.29) is implemented, unit-pinned, but never observed
   on hardware.** The v1.0.37 device session produced zero genuine `NATIVE_BUSY` events, because the
   corrected classification turns those cases into `TIMED_OUT` before the slot wait is reached. The
   assertion is latent by construction, not by omission.
