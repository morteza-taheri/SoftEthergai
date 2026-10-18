# F.28AL-CI-WORKFLOW-FIX — Gate Release Assembly Behind GPG Availability

## 1. Executive Verdict

**WORKFLOW UPDATED — RELEASE STEPS NOW GATED**

The CI workflow (`.github/workflows/android-ci.yml`) has been updated to gate all release-build steps behind `GPG_PRIVATE_KEY` availability. Fork PRs (without secrets) will now skip release assembly instead of failing with the fail-closed GradleException from F.28AL-RISK-A-FIX. Unit tests, debug keystore creation, and all non-release steps remain unconditional. YAML syntax validated.

---

## 2. Steps Identified as Release-Build

| # | Step Name | Line(s) | Task / Command | Was Gated? | Now Gated? |
|---|---|---|---|---|---|
| 1 | Assemble Pro Release APK | 241–247 | `./gradlew assembleProRelease` | No | **Yes** — `if: ${{ env.GPG_PRIVATE_KEY != '' }}` |
| 2 | Bundle Pro Release AAB | 249–253 | `./gradlew :app:bundleProRelease` | Tag-only | **Yes** — `if: ${{ startsWith(github.ref, 'refs/tags/') && env.GPG_PRIVATE_KEY != '' }}` |
| 3 | Upload Pro APK | 255–260 | `actions/upload-artifact@v7` | Tag-gated | Unchanged (W6) |
| 4 | Upload Pro AAB | 262–267 | `actions/upload-artifact@v7` | Tag-gated | Unchanged (W6) |
| 5 | Diagnose android-37 resolution (8b/8c) | 207, 211 | `assembleProRelease --dry-run` | `continue-on-error: true` | **Not gated** — diagnostic step, not a build step. See note below. |

**Note on diagnostic step (5):** The "Diagnose android-37 resolution" step (lines 132–232) has `continue-on-error: true` and runs `:app:assembleProRelease --dry-run` as part of its diagnostic output (sections 8b and 8c). With the fail-closed guard from F.28AL-RISK-A-FIX, these `--dry-run` invocations will produce the GradleException message instead of diagnostic platform-resolution output for fork PRs. However, since the step has `continue-on-error: true`, it cannot fail the build. This is a cosmetic degradation of diagnostic output for fork PRs only — intentionally not gated because it is a read-only diagnostic, not a build or packaging step.

---

## 3. Diff Summary

### Change 1: "Assemble Pro Release APK" (line 246)
```yaml
# BEFORE:
      - name: Assemble Pro Release APK
        run: ./gradlew assembleProRelease

# AFTER:
      - name: Assemble Pro Release APK
        # Gate behind GPG_PRIVATE_KEY: fork PRs do not have the blackbox-
        # encrypted release keystore, and the fail-closed guard in
        # app/build.gradle will throw GradleException if assembleProRelease
        # is attempted without it. Skip for forks; they still run unit tests.
        if: ${{ env.GPG_PRIVATE_KEY != '' }}
        run: ./gradlew assembleProRelease
```

### Change 2: "Bundle Pro Release AAB" (line 252)
```yaml
# BEFORE:
      - name: Bundle Pro Release AAB
        if: startsWith(github.ref, 'refs/tags/')
        run: ./gradlew :app:bundleProRelease

# AFTER:
      - name: Bundle Pro Release AAB
        # Tag-gated (release tags only) AND GPG-gated (main repo with
        # secrets). Fork PRs cannot produce a signed release bundle.
        if: ${{ startsWith(github.ref, 'refs/tags/') && env.GPG_PRIVATE_KEY != '' }}
        run: ./gradlew :app:bundleProRelease
```

### No changes to:
- "Ensure debug keystore for unsigned forks" — `if: ${{ ! env.GPG_PRIVATE_KEY }}` — preserved (W4)
- "Unit tests" — no condition — preserved unconditional (W5)
- "Upload Pro APK" / "Upload Pro AAB" — `if: startsWith(github.ref, 'refs/tags/')` — preserved (W6)
- Trigger conditions (push/tags/workflow_dispatch) — preserved
- Secrets env block — preserved

---

## 4. Fork Path Verification

**Scenario:** Fork PR (no `GPG_PRIVATE_KEY`, no `SIGN_KEY_PASSWORD`, no release keystore)

| Step | Runs? | Notes |
|---|---|---|
| Checkout | Yes | |
| Make gradlew executable | Yes | |
| Apply vpnLib R8 keep-rules patch | Yes | |
| Ensure debug keystore for unsigned forks | **Yes** | `! env.GPG_PRIVATE_KEY` → true; creates debug.keystore |
| Import GPG key and run blackbox_postdeploy | Yes (no-op) | `GPG_PRIVATE_KEY` is empty; the `if [ -n "$GPG_PRIVATE_KEY" ]` guard inside the script skips |
| Java version | Yes | |
| Install Android SDK platform 37 | Yes | |
| Diagnose android-37 resolution | Yes | `continue-on-error: true`; `assembleProRelease --dry-run` will show fail-closed error but won't break build |
| Build OpenSSL headers | Yes | |
| **Unit tests** | **Yes** | `./gradlew :app:testProDebugUnitTest` — unconditional, debug task, not affected by fail-closed guard |
| **Assemble Pro Release APK** | **SKIPPED** | `env.GPG_PRIVATE_KEY != ''` → false |
| **Bundle Pro Release AAB** | **SKIPPED** | tag condition false AND GPG condition false |
| Upload Pro APK | SKIPPED | tag-gated (not a tag push) |
| Upload Pro AAB | SKIPPED | tag-gated (not a tag push) |

**Result:** Fork PRs run checkout, setup, unit tests, and diagnostics. No release build is attempted. The fail-closed guard in `app/build.gradle` is never triggered because no release task enters the task graph. CI exits successfully if unit tests pass.

---

## 5. Main Repo Path Verification

**Scenario:** Main repo push/tag (with `GPG_PRIVATE_KEY` and `SIGN_KEY_PASSWORD` secrets)

| Step | Runs? | Notes |
|---|---|---|
| Checkout | Yes | |
| Make gradlew executable | Yes | |
| Apply vpnLib R8 keep-rules patch | Yes | |
| Ensure debug keystore for unsigned forks | **Skipped** | `! env.GPG_PRIVATE_KEY` → false (secret is set) |
| Import GPG key and run blackbox_postdeploy | **Yes** | Imports GPG key, runs `blackbox_postdeploy` → decrypts `keyStore/release.jks` |
| Java version | Yes | |
| Install Android SDK platform 37 | Yes | |
| Diagnose android-37 resolution | Yes | `continue-on-error: true` |
| Build OpenSSL headers | Yes | |
| Unit tests | **Yes** | `./gradlew :app:testProDebugUnitTest` |
| **Assemble Pro Release APK** | **Yes** | `env.GPG_PRIVATE_KEY != ''` → true; keystore decrypted; `hasReleaseSigningConfig = true`; fail-closed guard does not fire; signed APK produced |
| **Bundle Pro Release AAB** | **Yes** (tag push only) | Both conditions true on tag push; skipped on branch push |
| Upload Pro APK | Yes (tag push only) | |
| Upload Pro AAB | Yes (tag push only) | |

**Result:** Main repo CI is completely unaffected. Release builds proceed normally with the release keystore. The fail-closed guard in `app/build.gradle` passes because `hasReleaseSigningConfig = true` (keystore present + password set).

---

## 6. Evidence Taxonomy

| Finding | Classification |
|---|---|
| "Assemble Pro Release APK" had no `if` condition (original) | SOURCE CODE EVIDENCE |
| "Bundle Pro Release AAB" had only tag condition (original) | SOURCE CODE EVIDENCE |
| "Ensure debug keystore" step condition `! env.GPG_PRIVATE_KEY` (original) | SOURCE CODE EVIDENCE |
| "Unit tests" step has no condition (original) | SOURCE CODE EVIDENCE |
| Upload steps are tag-gated (original) | SOURCE CODE EVIDENCE |
| Fork PRs skip release assembly after change | INFERENCE (from condition logic) |
| Main repo release builds unaffected after change | INFERENCE (from condition logic) |
| `env.GPG_PRIVATE_KEY` is empty string for forks | INFERENCE (GitHub Actions semantics: unset secrets evaluate to empty string) |
| Diagnostic step won't break build despite fail-closed error | SOURCE CODE EVIDENCE (`continue-on-error: true` at line 136) |
| YAML syntax is valid | SOURCE CODE EVIDENCE (`yaml.safe_load` succeeded) |

---

## 7. Explicit No-Change Confirmation

| Item | Status |
|---|---|
| app/build.gradle | NONE modified |
| Source files (.kt, .java, .xml) | NONE modified |
| Test files | NONE modified |
| Gradle files (build.gradle, settings.gradle, etc.) | NONE modified |
| Secrets configuration | NONE modified |
| CI trigger conditions (push/tags/workflow_dispatch) | NONE modified |
| Commit/tag/push | NONE |
| Device | NONE (not touched) |
| "Ensure debug keystore for unsigned forks" step | NONE modified (preserved) |
| "Unit tests" step | NONE modified (preserved unconditional) |
| Upload step conditions | NONE modified (preserved tag-gated) |
| **Files edited** | **.github/workflows/android-ci.yml only** |

---

## 8. Final Recommendation

**PROCEED TO COMMIT + PUSH (with user authorization)**

The workflow fix is complete and YAML-validated. Combined with F.28AL-RISK-A-FIX (app/build.gradle), the two changes form a coherent unit:

1. **app/build.gradle** (F.28AL-RISK-A-FIX): release variants fail-closed if keystore/password is missing.
2. **.github/workflows/android-ci.yml** (F.28AL-CI-WORKFLOW-FIX): fork PRs skip release assembly steps; main repo CI proceeds normally with secrets.

**Before pushing, verify:**
- The two changes should be committed together (or the CI workflow fix first, then the build.gradle fix) to avoid a window where CI breaks for fork PRs.
- After push, monitor the first fork PR CI run to confirm the release steps are correctly skipped.
- Consider updating the "Diagnose android-37 resolution" step in a future cleanup to skip the `assembleProRelease --dry-run` probes for forks (cosmetic only, `continue-on-error: true` prevents build failure).
