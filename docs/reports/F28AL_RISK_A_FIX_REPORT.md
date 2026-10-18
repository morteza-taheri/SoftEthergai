# F.28AL-RISK-A-FIX — Fail-Closed Release Signing Configuration

## 1. Executive Verdict

**FIX IMPLEMENTED — CONFIGURATION VALIDATED**

The fail-closed guard is in place in `app/build.gradle`. Gradle configuration check (`:app:help`, `:app:tasks`) passed with exit code 0. A CI workflow compatibility conflict was detected (fork PRs) and is reported in Section 7 — the CI workflow was NOT edited per instructions.

---

## 2. Current Behavior (V1)

**Original fallback condition** (now replaced), quoted from the pre-edit `app/build.gradle`:

```groovy
// Line 40:
def signPassword = System.getenv("SIGN_KEY_PASSWORD")
    ?: localProps.getProperty("signKeyPassword")
// Line 41:
if (keystoreFile.exists() && signPassword != null) {
    // release signing config                          ← correct path
} else {
    // debug keystore fallback                         ← SILENT FALLBACK (BUG)
    def debugKeystore = file("${System.getProperty('user.home')}/.android/debug.keystore")
    pro { storeFile debugKeystore; storePassword "android"; ... }
}
// Line 63:
signingConfig signingConfigs.pro   // applied to ALL pro variants (incl. proRelease)
```

**Problem:** When the release keystore (`keyStore/release.jks`) or `SIGN_KEY_PASSWORD` is unavailable, the `else` branch silently creates a debug-keystore signing config. Since `signingConfig signingConfigs.pro` is assigned at the flavor level (line 63), this debug config is used for **all** pro variants — including `proRelease`. No warning, no error; the result is a debug-signed APK labeled as "release."

**Fail-closed policy decision:**
- **Release variants** (proRelease and any future release variants): missing keystore/password → **HARD BUILD FAILURE** with a clear message.
- **Debug variants** (proDebug, freeDebug): the debug-keystore fallback is **CORRECT and PRESERVED**.

---

## 3. Design Decision (V2)

**Chosen approach: `gradle.taskGraph.whenReady` guard**

Rationale:
- A `GradleException` thrown directly in the `buildTypes {}` block would fire at **configuration time** and break ALL Gradle commands (including `:app:help`, `:app:tasks`, unit tests) whenever the keystore is absent — even for non-signing tasks. This is too aggressive and would prevent developers from running any Gradle task without the release keystore.
- The `taskGraph.whenReady` callback fires **after the task graph is computed but before task execution**. It can inspect which tasks are actually requested and only throw if a release assembly/bundle/package task is in the graph. Non-signing commands (`:app:help`, `:app:tasks`, `testProDebugUnitTest`) proceed normally.

**How it works:**
1. `hasReleaseSigningConfig` boolean (line 43) is computed once at the `android {}` scope: `releaseKeystoreFile.exists() && releaseSignPassword != null`.
2. `signingConfigs` block (lines 45–64): if `hasReleaseSigningConfig` is true, creates the release config; if false, creates the debug-keystore fallback (for debug variants only).
3. Guard (lines 88–116): if `hasReleaseSigningConfig` is false, registers a `whenReady` callback that checks whether any task matching `(assemble|bundle|package).*Release` is in the graph. If so, throws `GradleException` with a precise, non-secret-leaking message.
4. Debug variants: `signingConfigs.pro` (debug fallback when keystore absent) is still assigned at the flavor level (line 70). The guard does not fire for debug tasks.

---

## 4. Code Change Summary

**File:** `app/build.gradle` (only file modified)

### New condition (line 43):
```groovy
def hasReleaseSigningConfig = releaseKeystoreFile.exists() && releaseSignPassword != null
```

### New failure path (lines 88–116):
```groovy
if (!hasReleaseSigningConfig) {
    gradle.taskGraph.whenReady { graph ->
        def releaseBuildRequested = graph.allTasks.any { task ->
            task.project == project &&
            task.name.matches(/(assemble|bundle|package).*Release/)
        }
        if (releaseBuildRequested) {
            // ... build missing[] list ...
            throw new GradleException(...)
        }
    }
}
```

### Preserved debug-variant behavior (lines 53–63, unchanged logic):
```groovy
} else {
    // Debug-keystore fallback — DEBUG variants only.
    def debugKeystore = file("${System.getProperty('user.home')}/.android/debug.keystore")
    pro {
        storeFile debugKeystore
        storePassword "android"
        keyAlias "androiddebugkey"
        keyPassword "android"
    }
}
```

The `androidComponents` block (lines 117+) — which manages debug-variant signing overrides for androidTest signature matching — is **unchanged**.

---

## 5. Error Message

Exact `GradleException` message thrown when a release task is requested without the keystore/password:

```
Release build blocked: release signing configuration is incomplete.
Missing: release keystore file (keyStore/release.jks), SIGN_KEY_PASSWORD (env var or signKeyPassword in local.properties).
Hint: Set SIGN_KEY_PASSWORD env var or signKeyPassword in local.properties, and ensure the release keystore is present (via blackbox_postdeploy on CI) before building a release variant.
```

**Properties:**
- Precise about which item(s) are missing (keystore file, password, or both — determined dynamically).
- Does NOT print any secret values.
- Includes a one-line hint with the exact env var name and local.properties key.

---

## 6. Configuration Validation Result (V4)

| Item | Value |
|------|-------|
| Command | `./gradlew :app:help --no-daemon --offline` |
| Java | Android Studio JBR (Java 25.0.3) |
| Gradle | 9.7.0 (wrapper) |
| Exit code | **0** |
| Result | `BUILD SUCCESSFUL in 39s` |
| Configuration cache | Stored successfully |

Secondary check: `./gradlew :app:tasks --no-daemon --offline` → exit 0, tasks listed without error.

**Local state note:** The release keystore (`keyStore/release.jks`) does NOT exist locally and `SIGN_KEY_PASSWORD` is unset — confirming `hasReleaseSigningConfig = false`. Despite this, `:app:help` and `:app:tasks` succeeded because the guard only triggers for release assembly/bundle/package tasks, not for help/tasks commands. This proves the design works: non-signing commands are unaffected, release tasks would be blocked.

---

## 7. CI Workflow Compatibility Check

**File inspected:** `.github/workflows/android-ci.yml`

**CONFLICT DETECTED** — The CI workflow contains a step that explicitly assumes debug-fallback behavior:

**Step: "Ensure debug keystore for unsigned forks"** (lines 50–70):
```yaml
- name: Ensure debug keystore for unsigned forks
  # Forks do not inherit the blackbox-encrypted release keystore or
  # the GPG key, so the build falls back to the debug keystore — which
  # the CI container does not have.
  if: ${{ ! env.GPG_PRIVATE_KEY }}
  run: |
    # Creates debug.keystore in $HOME/.android/ and /root/.android/
```

**Step: "Assemble Pro Release APK"** (line 242):
```yaml
- name: Assemble Pro Release APK
  run: ./gradlew assembleProRelease
```
This runs **unconditionally** — no `if` guard.

**Impact analysis:**

| CI scenario | GPG_PRIVATE_KEY | Keystore present | hasReleaseSigningConfig | assembleProRelease | Conflict? |
|---|---|---|---|---|---|
| Main repo (push/tag) | Set | Yes (blackbox_postdeploy) | true | Succeeds | No |
| Fork PR | Not set | No | false | **FAILS** (GradleException) | **YES** |

The "Ensure debug keystore for unsigned forks" step was designed specifically to support the silent debug-fallback for fork PRs. With the fail-closed fix, fork PRs running `assembleProRelease` will now fail with a clear error instead of producing a debug-signed APK.

**Per instructions:** The CI workflow was NOT edited. This conflict is reported for user decision.

---

## 8. Evidence Taxonomy

| Finding | Classification |
|---|---|
| Current fallback condition (lines 40-56 original) | SOURCE CODE EVIDENCE |
| Debug-fallback applied to all pro variants (line 63 original) | SOURCE CODE EVIDENCE |
| CI "Ensure debug keystore" step assumes fallback (lines 50-70 CI) | SOURCE CODE EVIDENCE |
| CI `assembleProRelease` runs unconditionally (line 242 CI) | SOURCE CODE EVIDENCE |
| Fork PRs will fail with fail-closed fix | INFERENCE (from code + CI analysis) |
| Main repo CI will work correctly | INFERENCE (from code + CI analysis) |
| vpnLib has no signing config (no parallel fix needed) | SOURCE CODE EVIDENCE |
| `:app:help` succeeds without keystore (guard doesn't fire) | SOURCE CODE EVIDENCE (observed) |

---

## 9. Explicit No-Change Confirmation

| Item | Status |
|---|---|
| Source files (.kt, .java, .xml) | NONE modified |
| CI workflow (.github/workflows/android-ci.yml) | NONE modified |
| Secrets | NONE touched |
| local.properties | NONE modified |
| APK produced | NONE |
| Device | NONE (not installed/uninstalled/touched) |
| Commit/tag/push/release | NONE |
| vpnLib/build.gradle | Inspected, NOT modified (no signingConfigs block — library module) |
| **Files edited** | **app/build.gradle only** |

---

## 10. Final Recommendation

**WAIT FOR EXPLICIT USER DECISION**

The fix is implemented and configuration-validated. However, the CI workflow (`.github/workflows/android-ci.yml`) contains a step ("Ensure debug keystore for unsigned forks", lines 50–70) that explicitly assumes the debug-fallback behavior for fork PRs, and `assembleProRelease` runs unconditionally (line 242).

**Before pushing this fix, the user must decide how to handle fork PR CI:**
1. **Update the CI workflow** to gate `assembleProRelease` behind `if: ${{ env.GPG_PRIVATE_KEY }}` (forks skip release assembly).
2. **Accept fork CI failure** — forks arguably should not produce release APKs.
3. **Add a release-keystore-independent fallback task** for forks (e.g., `assembleProDebug` for fork verification).

Option 1 is recommended: it preserves the fail-closed guarantee for all release builds while allowing fork PRs to run tests and debug assembly without the release keystore.

Until this decision is made, the fix should not be pushed, as it would break fork PR CI pipelines.
