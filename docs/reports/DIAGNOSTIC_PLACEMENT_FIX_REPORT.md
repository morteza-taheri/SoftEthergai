# DIAGNOSTIC PLACEMENT FIX — v1.0.44 (ROUTING-REACHABILITY-AND-EPERM-DIAGNOSTICS follow-up)

## 0. Labelling convention

Every claim below carries one of: `SOURCE CODE`, `TEST`, `PHYSICAL DEVICE`, `INFERENCE`, `UNKNOWN`, `PRIOR REPORT`.

## 1. Verdict

Phase A (device-free) is **complete** except the `:app:assembleProDebug` APK, which could **not** be produced in this environment (a sandbox Security Center rule blocked a Gradle cache `.so` read). The source change, the new test, and the spec bump (v1.0.43 → v1.0.44) are committed and pushed; the JVM suite validates compilation and behaviour. Phase B (device verify T6a–T6d) is **not executed** — it needs a device-attached run and an APK that must be built in a non-sandboxed environment.

## 2. Pre-conditions (STEP 1)

- `HEAD == origin/main == 04215ad27704132527e085c4ffc934ebf18b45a9` — `SOURCE CODE` (git rev-parse).
- Branch `main` — `SOURCE CODE`.
- No tracked modifications (`git status --porcelain=v1`, excluding untracked, is empty) — `SOURCE CODE`.
- Tag count = 5 — `SOURCE CODE`.
- Spec header `**Document version:** 1.0.43` (line 8) — `SOURCE CODE`. Per the brief's STEP 7, since the previous phase left the header at 1.0.43 while its final summary called itself "v1.0.44", this phase becomes **v1.0.44** (header bumped). **P-1 (version ambiguity) resolved.**
- No ABORT condition met.

## 3. Discovery (STEP 2) — P-2 (diagnostic in wrong place)

- The EPERM observed on device (v1.0.43 T6, `DEVICE_VERIFY_CLUSTER_v1.0.43_REPORT.md` §8) was produced at socket-open time inside `OkHttpFetcher.execute()`'s `catch (IOException)` (`HttpFetcher.kt:282–285`), the pre-existing `Request failed: ${e.message}` line — **not** at `boundToNetwork` construction. — `SOURCE CODE` + `PRIOR REPORT`.
- `boundToNetwork` (the v1.0.43 M2 diagnostic site) is **unused in production**: `VpnServerRepository.kt:163` builds the bound client via `boundToSocketFactory(socketFactory)` directly; `GlobalVpnTracker.currentVpnNetwork()` is the live `Network`. — `SOURCE CODE`.
- The `OkHttpFetcher` class holds only `client` and `requestDelayMs`; there is **no `network` field**, so `network` is not in scope inside the catch. — `SOURCE CODE`. → Per STEP 3 option (b), attribution uses exception class + message only; the network handle is already carried in `e.message` ("Binding socket to network N failed: EPERM").

## 4. The placement fix (STEP 3)

Before (`HttpFetcher.kt:282–285`):

```kotlin
} catch (e: IOException) {
    CollectorLog.d("Request failed: ${e.message}")
    return null
}
```

After:

```kotlin
} catch (e: IOException) {
    // v1.0.44 (diagnostic placement fix): the EPERM observed on device
    // (DEVICE_VERIFY_CLUSTER_v1.0.43_REPORT.md §8) was produced HERE, at
    // socket-open time — not at boundToNetwork construction. Production
    // never calls boundToNetwork (VpnServerRepository.kt:163 uses
    // boundToSocketFactory directly), so the [bind] diagnostic there never
    // fires on device. The network handle is already carried in e.message
    // ("Binding socket to network N failed: EPERM"); we add only the
    // exception class for attribution. The return below is unchanged.
    val causeClass = e.javaClass.simpleName
    CollectorLog.d("Request failed: [exception=$causeClass] ${e.message}")
    return null
}
```

- Control flow (`return null`) **unchanged**. — `SOURCE CODE`.
- The v1.0.43 `[bind] ok` / `[bind] FAILED` logs in `boundToNetwork` are **preserved** (still useful if `boundToNetwork` is later wired in). — `SOURCE CODE`.

## 5. Tests (STEP 4) — T-NEW-53

- `HttpFetcherBindDiagnosticTest.kt` gains `execute catch logs exception class for attribution` (T-NEW-53). It asserts the new catch logs the exception class (`exception=$causeClass` or `exception=${e.javaClass.simpleName}`). The three pre-existing assertions (T-NEW-50/51/52) are **unchanged**. — `SOURCE CODE`.
- All source-shape tests are hermetic (walk `user.dir` to locate `HttpFetcher.kt`); no runtime binding, no Robolectric/Mockito. — `SOURCE CODE`.

## 6. Counts (STEP 5)

Ran under `CI=true`, `--rerun-tasks --no-build-cache`:

- `:app:testProDebugUnitTest` — **866 passed / 0 failed / 0 error / 1 skipped** (baseline 865 + T-NEW-53; skip unchanged). — `TEST`.
- `:SoftEtherClient:test` — **173 / 0 / 0 / 0** (unchanged). — `TEST`.
- No regression vs baseline. — `TEST`.

## 7. Build (STEP 6) — BLOCKED, environmental

- `:app:assembleProDebug` FAILED at `:app:mergeProDebugNativeLibs` with `Access is denied` on a Gradle cache file:
  `C:\Users\PMC\.gradle\caches\9.7.0\transforms\…\graphics-path-1.0.1\jni\armeabi-v7a\libandroidx.graphics.path.so`. — `TEST`/`SOURCE CODE` (build output).
- The platform returned a **Security Center sandbox rule** blocking that read and instructed: *do not retry or attempt equivalent alternatives*. — `UNKNOWN` (environmental; not a code defect).
- The APK on disk (`ed82fc058796a45698cd41a90d4dcb1fb20ecbc05198b30d57eaecf2117bce19`, timestamp 14:11) is the **stale prior-phase artifact** — the failed build never produced a fresh one, so no new APK hash is recorded. — `SOURCE CODE`.
- Because the `:app` JVM test task (`testProDebugUnitTest`) already compiled every changed file and passed, source compilation is proven. The APK is purely final packaging, blocked on a cached dependency `.so`. — `INFERENCE`.

## 8. Diff-scope (authorized files only)

Committed in Phase A:

- `app/src/main/java/vn/unlimit/vpngate/data/remote/HttpFetcher.kt` (FIX).
- `app/src/test/java/vn/unlimit/vpngate/data/remote/HttpFetcherBindDiagnosticTest.kt` (T-NEW-53).
- `PROJECT_SPECIFICATION.md` (v1.0.44 bump: header, §1.6.1 push-state note, NEW §1.6.37, §20 changelog + verification rows).
- `DIAGNOSTIC_PLACEMENT_FIX_REPORT.md` (this report).

No other file changed. — `SOURCE CODE` (git diff).

## 9. §22 identity (FROZEN)

- §22 block (bytes from `## 22.`): **99 lines, sha256 `adf12858b72ed13742252c512a8728e9141e415cc55d09c6409af4e8e622020e`** — identical to HEAD. — `SOURCE CODE`.

## 10. Negative assurance

- `return null` in `OkHttpFetcher.execute()` unchanged — behaviour identical. — `SOURCE CODE`.
- `[bind]` logs in `boundToNetwork` retained. — `SOURCE CODE`.
- `boundToNetwork` NOT wired into production (explicitly out of scope). — `SOURCE CODE`.
- No `DataUtil` / `SettingsScreen` / `strings.xml` / `GlobalVpnTracker` / `VpnServerRepository` / `AutoMode*` / `build.gradle` / manifests / schemas changed. — `SOURCE CODE`.
- No `git add -A`, no `--force` / `reset` / `clean` / `stash` / `checkout` / `restore`, no tag, no C5, no `D:\PMC\Documents\Android\SoftEthergai` access. — `SOURCE CODE` (git log / absence).
- `boundToNetwork` remains unused, so the v1.0.43 `[bind]` logs still do not fire on device until wired; the NEW attribution line in `execute()` is what will actually attribute the EPERM. — `INFERENCE`.

## 11. Next device-verify plan (Phase B — NOT executed here)

Requires a device-attached run on `5585fecc` and an APK built in a non-sandboxed environment (this environment blocked `mergeProDebugNativeLibs`):

- **STEP 9**: install `-r` the fresh APK; confirm versionCode unchanged (144 / 2.4.11).
- **T6a**: toggle OFF + tunnel up → expect `path=open`.
- **T6b**: toggle ON + tunnel up → expect `path=vpn` attempted; if it fails, the NEW `Request failed: [exception=…] …` line attributes the EPERM.
- **T6c**: toggle ON + tunnel DEAD → expect `path=open` as the FIRST attempt (v1.0.41 gate).
- **T6d**: capture the new diagnostic line if EPERM occurs; record class + message + handle.
- **STEP 14**: restore toggle to OFF; **STEP 15**: crash/ANR scan; **STEP 16**: `DEVICE_VERIFY_ROUTING_v1.0.44_REPORT.md` (untracked).
- ABORT if T6c shows `path=vpn` first (v1.0.41 not working) or any crash/ANR.

## 12. STOP

Phase A is committed and pushed. Phase B is **not executed** (no device + APK build blocked by the sandbox). Per the brief, do **not** start Phase B or any follow-up autonomously.
