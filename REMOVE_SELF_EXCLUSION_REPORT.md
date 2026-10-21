# REMOVE_SELF_EXCLUSION_REPORT

**Block:** `v1.0.47 — REMOVE-SELF-EXCLUSION-FOR-COLLECTOR` (device-free)
**Repo:** `C:\Dev\SoftEthergai` · **Branch:** `main`
**Baseline commit:** `157445dee31a43b9cdbb0316376ddb5fb73d34db`
**Spec:** `PROJECT_SPECIFICATION.md` **1.0.46 → 1.0.47** (§1.6.40 added)
**Date:** 2026-10-21
**Device runs:** **none** (this block is explicitly device-free)

Every claim is labelled: `[SOURCE CODE]`, `[TEST]`, `[PHYSICAL DEVICE]`, `[INFERENCE]`, `[UNKNOWN]`, `[git]`.

---

## 1. Verdict

**PASS at SOURCE CODE + TEST level. NOT device-verified.**

| # | Item | Result |
|---|------|--------|
| Root cause | The app excludes itself from its own tunnel at **two** independent `VpnService.Builder` sites | **CONFIRMED** — both fixed |
| Fix — SoftEther path | `SoftEtherVpnService.buildTun` skips the app's own package | **DONE** |
| Fix — MS-SSTP path | `IPTerminal.addExcludedAppRules` skips the app's own package | **DONE** (scope extended by operator authorization) |
| User exclusions | `com.google.android.projection.gearhead` still applied | **PRESERVED** |
| Tests | T-NEW-73 a–d, 4 cases, all green | **DONE** |
| Spec | 1.0.46 → 1.0.47, §1.6.40 (8 items), push-state, §20 rows | **DONE** |
| §22 | Byte-identical | **VERIFIED** |
| Build | `:app:assembleProDebug` under `CI=true` | **SUCCESSFUL** |
| Device | `path=tunnel succeeded` with no `EPERM` | **NOT RUN — PENDING** |

---

## 2. Pre-conditions

- `HEAD == origin/main == 157445dee31a43b9cdbb0316376ddb5fb73d34db` `[git]`
- branch `main`, **5** tags, no detached state `[git]`
- spec **`1.0.46`** at start (read from the header, not assumed) `[SOURCE CODE]`
- only tracked modification: `vpngate_collector.py` — the background publisher's file, untouched `[git]`
- `:app` baseline **900 / 0 / 0 / 1**, `:SoftEtherClient` **173 / 0 / 0 / 0** `[TEST]`

### 2.1 STEP 2 discovery — and where the brief's model did not match the source

STEP 2a looked for `addDisallowedApplication(packageName)` as a standalone self-exclusion line. **No such line exists.** The only call in the SoftEther module is inside a loop over the *user's* list:

```kotlin
// SoftEtherVpnService.kt:1397–1405, BEFORE
// Exclude apps from VPN tunnel (they will use normal network instead)
config.excludedApps.forEach { packageName ->
    try {
        builder.addDisallowedApplication(packageName)
    } catch (e: PackageManager.NameNotFoundException) {
        Log.w(TAG, "Excluded app not found, skipping: $packageName")
    }
}
```

Here `packageName` is the **lambda parameter**, shadowing `Context.packageName`. The app therefore excluded itself only because its own package was *in the list*. Tracing where it gets there:

```kotlin
// App.kt:135–149
private fun initializeDefaultExcludedApps() {
    // Always exclude self — hidden from user, cannot be removed
    val selfPackage = ExcludedApp(packageName = packageName, appName = "Self")
    ...
    excludedAppDao.insertExcludedApp(selfPackage)
```

That hidden `Self` row then reaches **two independent application points**:

| Path | Source of the list | Applied at |
|---|---|---|
| **SoftEther SSL-VPN** | `config.excludedApps` ← `excludedAppDao.getAllExcludedApps()` | `SoftEtherVpnService.kt:1398` |
| **MS-SSTP** | `ROUTE_EXCLUDED_APPS` pref ← same DAO (`StatusViewModel:1018–1029`, `DetailActivity:845–858`) | `IPTerminal.kt:148` |

**The device's failing tunnel was MS-SSTP.** So a fix confined to `SoftEtherVpnService.kt` — the block's only authorized source file — would have left the *observed* `EPERM` untouched. This was raised with the operator before any edit, and the operator authorized extending scope to `IPTerminal.kt`. That decision is recorded here and in §1.6.40 bullet 3.

**One more asymmetry explains why the defect hid:** MS-SSTP via **Auto Mode** was never affected, because `AndroidConnectionAdapter.connectSstp` (`:792`) writes `ROUTE_EXCLUDED_APPS = emptySet()`. Only **manual** MS-SSTP connects and **all** SoftEther connects carried the self-exclusion.

STEP 2b confirmed the v1.0.45 per-request binding is present and intact (`productionBoundFetcher` `:193`, `productionTunnelFetcher` `:205`, `boundToPhysicalNetwork` `HttpFetcher.kt:176`, `boundToNetwork` `:155`) — the precondition for safely removing the global exclusion `[SOURCE CODE]`.

---

## 3. The EPERM evidence

`[PHYSICAL DEVICE]`, from `DEVICE_VERIFY_v1.0.46_REPORT.md` §7.4 plus the operator's logcat at 12:21:37 and 12:22:08:

```
Request failed: [exception=SocketException] Binding socket to network 109 failed:
    EPERM (Operation not permitted)
```

while the physical rung succeeded on the same run:

```
[bind-physical] ok network=104
[fetch] path=physical source=mirror
[collector] path=physical source=mirror      ← delivered 101 → 107 servers
```

and the same device's preference dump showed:

```xml
<set name="ROUTE_EXCLUDED_APPS">
    <string>vn.unlimit.vpngatepro</string>
    <string>com.google.android.projection.gearhead</string>
</set>
```

Two independent samples, on two separate refreshes, both `EPERM` on the tunnel and both `ok` on physical. The ladder and its fallback were therefore correct; only the tunnel **transport** was broken. With `PHYSICAL` blocked + `TUNNEL` `EPERM` + `EMERGENCY` a stub, the list could never update — which is exactly the operator's original requirement.

> Note: the app's own package never appears in Settings → Excluded Apps, because the UI filters it out; the device reported "Excluding 1 app(s)" while the underlying set held two entries. The exclusion is applied **by default**, not by user choice.

---

## 4. The self-exclusion finding

Three facts, each verifiable in source:

1. `App.initializeDefaultExcludedApps()` inserts `Self` unconditionally on every start, guarded only by `isAppExcluded(...) == 0` so it is never removed `[SOURCE CODE]`.
2. The row is a normal `excluded_app` row, so every consumer that reads the table — the `ConnectionConfig` builder, `StatusViewModel`, `DetailActivity` — propagates it `[SOURCE CODE]`.
3. Both `VpnService.Builder` sites applied the list verbatim, with no notion of "self" `[SOURCE CODE]`.

Consequence: the app could never route its own traffic through its own tunnel, by construction, on either protocol.

---

## 5. The fix

Both sites now capture the service's own package and filter it out of the user's list. Nothing else changes.

### 5.1 `SoftEtherVpnService.kt` — SoftEther SSL-VPN

```diff
@@ -1395,13 +1395,26 @@ if (sessionPolicy.isRunning) {
         }
 
         // Exclude apps from VPN tunnel (they will use normal network instead)
-        config.excludedApps.forEach { packageName ->
-            try {
-                builder.addDisallowedApplication(packageName)
-            } catch (e: PackageManager.NameNotFoundException) {
-                Log.w(TAG, "Excluded app not found, skipping: $packageName")
+        //
+        // v1.0.47 (§1.6.40): the app's OWN package is skipped. App
+        // `initializeDefaultExcludedApps()` inserts a hidden "Self" row into
+        // `excluded_app`, so `config.excludedApps` always contains this very
+        // package; applying it made every socket the collector bound to the
+        // VPN network fail with `EPERM (Operation not permitted)`, which is
+        // why the TUNNEL rung could never deliver data. Per-request binding
+        // (v1.0.45 productionBoundFetcher / productionTunnelFetcher) already
+        // isolates management traffic, so the app may use its own tunnel.
+        // User-added exclusions are applied unchanged.
+        val selfPackage = packageName
+        config.excludedApps
+            .filterNot { it == selfPackage }
+            .forEach { excludedPackage ->
+                try {
+                    builder.addDisallowedApplication(excludedPackage)
+                } catch (e: PackageManager.NameNotFoundException) {
+                    Log.w(TAG, "Excluded app not found, skipping: $excludedPackage")
+                }
             }
-        }
 
         // Configure as metered/unmetered
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
```

`val selfPackage = packageName` is evaluated **before** the lambda introduces its own `packageName`, so it resolves to `Context.packageName` — the service's own package. The lambda parameter is renamed `excludedPackage` so the shadowing that caused the defect cannot recur silently.

### 5.2 `IPTerminal.kt` — MS-SSTP

```diff
@@ -146,9 +146,17 @@ internal class IPTerminal(private val bridge: SharedBridge) {
     }
 
     private fun addExcludedAppRules() {
-        bridge.excludedApps.forEach {
-            bridge.builder.addDisallowedApplication(it)
-        }
+        // v1.0.47 (§1.6.40): the app's OWN package is skipped. The
+        // `ROUTE_EXCLUDED_APPS` set is written from the `excluded_app` table,
+        // which always contains the hidden "Self" row; applying it made every
+        // socket the collector bound to the VPN network fail with
+        // `EPERM (Operation not permitted)`. Per-request binding (v1.0.45)
+        // already isolates management traffic. User-added exclusions are
+        // applied unchanged.
+        val selfPackage = bridge.service.packageName
+        bridge.excludedApps
+            .filterNot { it == selfPackage }
+            .forEach { bridge.builder.addDisallowedApplication(it) }
     }
 
     private suspend fun addCustomRoutes(): Boolean {
```

`SharedBridge` exposes `internal val service: SstpVpnService` (`SharedBridge.kt:88`), which is a `Service` and therefore a `Context` — so `bridge.service.packageName` is the app's own package without threading a new parameter through the module.

### 5.3 Why this does not leak traffic

The concern with removing a global exclusion is that *all* app traffic would then enter the tunnel. It does not, because v1.0.45 binds **per request**, not per process:

- `productionBoundFetcher()` → `boundToPhysicalNetwork(network)` — `PHYSICAL` rung, explicitly a non-VPN network
- `productionTunnelFetcher()` → `boundToNetwork(network)` — `TUNNEL` rung only
- `bindProcessToNetwork()` is never called (architecture §7 Q2)

So the collector chooses its egress per request; the global exclusion only ever *blocked* one of those choices. `[SOURCE CODE]`

---

## 6. The source-shape test — T-NEW-73 (4 cases)

`app/src/test/java/vn/unlimit/vpngate/security/SelfExclusionRemovedTest.kt` — JUnit 4, no Robolectric. A behavioural test is impossible here: `VpnService.Builder` needs a real `VpnService` and a real `PackageManager`. The contract is pinned textually, following the existing `AutoModeControllerTest.sourceOf(...)` convention (`relativePath`, `../relativePath`, `app/relativePath`, `../../relativePath`).

| Test | Pins |
|---|---|
| **T-NEW-73 (a)** `softether builder does not exclude the app's own package` | `addDisallowedApplication(packageName)` **absent**; `val selfPackage = packageName` present; `filterNot { it == selfPackage }` present |
| **T-NEW-73 (b)** `user exclusions are still applied by the softether builder` | `config.excludedApps` still iterated; `addDisallowedApplication(excludedPackage)` present; `PackageManager.NameNotFoundException` still tolerated |
| **T-NEW-73 (c)** `sstp builder also skips the app's own package` | `bridge.service.packageName` present; same filter; `bridge.builder.addDisallowedApplication(it)` still applied to the rest |
| **T-NEW-73 (d)** `both call sites record the v1_0_47 provenance` | both files contain `v1.0.47` **and** `§1.6.40` |

Case (b) is the guard the brief did not ask for but the STOP list implies ("Removing the call also removes a user exclusion → STOP"): it fails if the fix ever degenerates into "stop applying exclusions", which would silently start leaking a user-excluded app into the VPN.

---

## 7. JVM counts

| Module | Before | After | Delta |
|---|---|---|---|
| `:app` | **900 / 0 / 0 / 1** | **904 / 0 / 0 / 1** | **+4** (T-NEW-73 a–d) |
| `:SoftEtherClient` | **173 / 0 / 0 / 0** | **173 / 0 / 0 / 0** | 0 |

Format `tests / failures / errors / skipped`. The single `:app` skip is pre-existing. Both runs used `CI=true`. No test was deleted, disabled or skipped. `[TEST]`

> The brief expected `901` (one test). The actual delta is `+4` because the fix touches two independent application points and each needs its own assertion — including the "user exclusions preserved" guard.

---

## 8. Build

- `CI=true ./gradlew.bat :app:assembleProDebug --rerun-tasks --no-build-cache` → **BUILD SUCCESSFUL** (114 tasks executed)
- `app-pro-debug.apk` — **63,780,018 bytes**
- SHA-256 — **`0ef89bfefe8e1d7aae58ddde101bcda4642eef4b238eb9d1a91690e04df90583`**
- `aapt2 dump badging` → `versionCode='144'` `versionName='2.4.11-dev'` (**unchanged** — no build or manifest file was touched) `[TEST]`

---

## 9. Diff-scope

**Modified (2 authorized source files + spec):**

| File | Change |
|---|---|
| `SoftEtherClient/src/main/java/vn/unlimit/softether/SoftEtherVpnService.kt` | +25 / −6 — the exclusion loop only |
| `sstpClient/src/main/java/kittoku/osc/terminal/IPTerminal.kt` | +14 / −3 — `addExcludedAppRules` only (added to scope by operator authorization) |
| `PROJECT_SPECIFICATION.md` | E1–E5 |

**New (2):** `app/src/test/java/vn/unlimit/vpngate/security/SelfExclusionRemovedTest.kt`, `REMOVE_SELF_EXCLUSION_REPORT.md`

**Present but NOT staged:** `vpngate_collector.py` (publisher's), `emergency/`, `scripts/`, `test-evidence/`, `emergency_csv_publisher.py`, `test_emergency_csv_publisher.py`, the untracked `DEVICE_VERIFY_*` / `TRIAGE_*` reports, `.vscode/`, `.workbuddy-ai/` `[git]`

**Spec edits applied:**

| ID | Location | Change |
|---|---|---|
| E1 | header | `HEAD` → `157445d…`, `Document version` → `1.0.47` |
| E2 | §1 intro | appended the `> **Push state (v1.0.47).**` paragraph after the v1.0.46 one |
| E3 | before §2 | NEW `#### 1.6.40 REMOVE-SELF-EXCLUSION (v1.0.47)`, 8 numbered items |
| E4 | §20 changelog | `1.0.47` row |
| E5 | §20 verification | row for §1.6.40 marked HISTORICAL |

---

## 10. §22 byte-identity

Extracted with `awk '/^## 22\./{flag=1} flag{print}'`:

- **99 lines** · **6,450 bytes** · SHA-256 **`adf12858b72ed13742252c512a8728e9141e415cc55d09c6409af4e8e622020e`**

Identical to v1.0.44, v1.0.45 and v1.0.46 → **byte-identical** `[TEST]`. Nothing in this block produced a §22 entry (§1.6.40 bullet 8).

---

## 11. Negative assurance

Confirmed **not** done:

- No device or `adb` command of any kind was issued.
- No build or test ran without `CI=true`.
- No `git add -A` / `git add .`; files were staged individually and the staged list printed before each commit.
- No `--force`, `reset`, `clean`, `stash`, `checkout` or `restore`.
- No tag operation; tag count remains 5.
- No test was deleted, disabled or skipped.
- **No user exclusion was removed** — `com.google.android.projection.gearhead` still reaches the builder, pinned by T-NEW-73 (b)/(c).
- Per-request binding was **not** modified (`HttpFetcher.kt`, `VpnServerRepository.kt`, `GlobalVpnTracker.kt` untouched).
- Emergency sources were **not** wired into the collector ladder.
- The timeout default (12), floor (8) and cap (60) were **not** changed.
- C5 was not executed.
- `D:\PMC\Documents\Android\SoftEthergai` was not touched.
- Frozen files untouched: §1.6.2–§1.6.39, §12.5.1, §12.5.2, §22, every already-tracked report, `AutoModeController.kt`, `AutoModeEngine.kt`, `AutoModeScreen.kt`, `AutoModeViewModel.kt`, `DataUtil.kt`, `SettingsScreen.kt`, `strings.xml`, `EmergencySourceConfigEntity.kt`, `EmergencySourceConfigDao.kt`, `EmergencyCsvParser.kt`, `EmergencySourceFetcher.kt`, build files, manifests, schemas.

**One documented scope expansion:** `sstpClient/src/main/java/kittoku/osc/terminal/IPTerminal.kt` was edited, which was not in the block's original authorized list. This was raised with the operator **before** any edit, with the evidence that the device's `EPERM` came from the MS-SSTP path, and the operator explicitly authorized "both application points". Without it the fix would not have addressed the observed failure.

---

## 12. Device verify plan (next phase)

Run on `5585fecc`, with **Developer Mode enabled first** — `[bind-physical]` / `[bind]` diagnostics go through `CollectorLog`, which is gated on `dataUtil.getDeveloperMode()` and is **off by default** (`App.kt:112–113`). Without it the bind lines will be missing from the log even when everything works.

1. **SoftEther SSL-VPN, opt-in ON.** Connect via *SoftEther SSL-VPN over TCP*. Confirm the prefs set still contains the app's package (it will — only the *application* changed), refresh, and expect:
   - `[ladder] order=TUNNEL,PHYSICAL,EMERGENCY`
   - `[fetch] path=tunnel source=…` followed by **`succeeded`**, not `failed (no result)`
   - **no** `EPERM` anywhere
   - `[bind] ok network=<vpn>` (with Developer Mode on)
2. **MS-SSTP manual connect, opt-in ON.** Same expectations. This is the path that produced the original `EPERM`.
3. **User exclusion still honoured.** With `com.google.android.projection.gearhead` present, confirm the Settings count is unchanged and that the tunnel is still built without error (no `NameNotFoundException` for the remaining entries).
4. **Regression.** With opt-in OFF, `path=physical` must still be primary and deliver data.

**What would falsify the fix:** `path=tunnel` still returning `failed (no result)` or still logging `EPERM`. In that case the exclusion is not the only cause and the next suspect is the per-request bind itself (`Network.getSocketFactory()` against a network the UID was disallowed from at builder time — note the disallow list is baked into the `VpnService` when the tun is created, so a stale tunnel from a previous app version would still carry the old list; a reconnect is required after installing this build).
