# EMERGENCY_DEFAULT_READER_REPORT

**Block:** `v1.0.46 — EMERGENCY-DEFAULT-URL-READER + TIMEOUT-RANGE-TIGHTENING` (device-free)
**Repo:** `C:\Dev\SoftEthergai` · **Branch:** `main`
**Baseline commit:** `34389a482f712de61c6bcf13043843017462e561`
**Spec:** `PROJECT_SPECIFICATION.md` **1.0.45 → 1.0.46** (§1.6.39 added)
**Date:** 2026-10-21
**Device runs:** **none** (this block is explicitly device-free)

Every claim is labelled: `[SOURCE CODE]`, `[TEST]`, `[INFERENCE]`, `[UNKNOWN]`, `[git]`.

---

## 1. Verdict

**PASS at SOURCE CODE + TEST level. NOT device-verified.**

| # | Item | Result |
|---|------|--------|
| CHANGE A | `R.string.default_emergency_source_url` had **no reader** → fresh install shipped an empty `emergency_source_config` | **CONFIRMED** — fixed |
| CHANGE A | Seed is once-only / non-destructive / idempotent / non-fatal | **DONE** |
| CHANGE B | Timeout floor 5 → 8 s (default 12 and max 60 unchanged) | **DONE** |
| Tests | T-NEW-67..72 + guards, 13 cases, all green | **DONE** |
| Spec | 1.0.45 → 1.0.46, §1.6.39 (9 items), §6.7 R5, §6.8.1, §11.9.1 annotation, §20 rows | **DONE** |
| §22 | Byte-identical | **VERIFIED** (SHA-256 match) |
| Build | `:app:assembleProDebug` under `CI=true`, clean `--rerun-tasks --no-build-cache` | **SUCCESSFUL** |
| Device | Fresh-install seed check + picker range check | **NOT RUN — PENDING** |
| Scope | `EMERGENCY` ladder rung wiring | **OUT OF SCOPE — still inert** |

The two changes are independent of each other and of the collector ladder. Neither touches
`AutoModeController`, `VpnServerRepository`, `GlobalVpnTracker` or `HttpFetcher`.

---

## 2. Pre-conditions

- **Repo state at STEP 1** `[git]`:
  - `HEAD == origin/main == 34389a482f712de61c6bcf13043843017462e561`
  - branch `main`, 5 tags, no detached state
  - only `vpngate_collector.py` dirty — that is the **background publisher's** file, left untouched
    by this block (it writes `emergency-sources/servers.csv` and `emergency_defaults.xml` on its own
    schedule and has produced unrelated `chore(emergency)` commits, e.g. `34389a4`)
  - `app/src/main/res/values/emergency_defaults.xml` **present** and containing
    `default_emergency_source_url`
- **Actual spec version at start:** **`1.0.45`** `[SOURCE CODE]` — confirmed by reading the header
  block of `PROJECT_SPECIFICATION.md` before any edit. (Not assumed from the previous block's report.)
- **Actual `HEAD` field in the spec header:** `f8d314869051775ac113ffdbdd02440ed8c4b833` — the
  v1.0.45 baseline. The publisher had since advanced the real HEAD to `34389a4` without touching the
  spec, so this revision corrects the field to `34389a4` `[git]`.
- **Discovery results** that shaped the implementation `[SOURCE CODE]`:
  - `EmergencySourceConfigEntity` — `id`, `url`, `label`, `enabled` (default `1`), `createdAt`,
    `updatedAt`. **No schema change was needed or made.**
  - `EmergencySourceConfigDao` is an **interface** with `observeAll(): Flow<…>`, `listEnabled()`,
    `getByUrl`, `upsert`, `deleteByUrl` → a hand-written fake is a valid test double without
    Robolectric or Room.
  - `App.kt` had **no** coroutine scope of any kind → one had to be added.
  - `SettingsScreen` picker was literally `(5..60).toList()`.
  - `SourceUrl.parse(url, kind)` already existed (`SourceIdentity.kt:202`) and already handled
    `SourceKind.EMERGENCY_CSV` — reused rather than reimplemented.

---

## 3. CHANGE A — the seeder

### 3.1 Before

`emergency_csv_publisher.py` writes the published CSV's raw-GitHub URL into
`app/src/main/res/values/emergency_defaults.xml:3` as `default_emergency_source_url`
(`[SOURCE CODE]`, publisher at `:710` and `:873`).

A repository-wide search for that string found **only** the resource declaration and the publisher.
**No Kotlin code read it.** Consequence, by construction:

1. fresh install → `emergency_source_config` table is empty;
2. `ServerListUpdates.emergencyDataset()` (`:146–160`) calls `dao.listEnabled()` → empty list;
3. `EmergencySourceFetcher` therefore has no source to fetch;
4. the emergency path is dead on every new install, no matter how fresh the published CSV is.

### 3.2 After

`App.kt` — new process-lifetime scope, new seeder, and the `onCreate` invocation:

```kotlin
// App.kt:48
private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

// App.kt:92–97 (inside onCreate)
appScope.launch {
    runCatching {
        val url = getString(R.string.default_emergency_source_url)
        seedEmergencyDefaultIfNeeded(dataUtil!!, emergencySourceConfigDao, url)
    }.onFailure { logW("Default emergency source seed failed", it) }
}

// App.kt:224–272 (companion object)
internal suspend fun seedEmergencyDefaultIfNeeded(
    dataUtil: DataUtil,
    dao: EmergencySourceConfigDao,
    url: String,
    now: Long = System.currentTimeMillis(),
): Boolean = try {
    if (dataUtil.hasSeededEmergencyDefault()) {
        false
    } else {
        val candidate = url.trim()
        when {
            candidate.isEmpty() -> {
                false
            }
            SourceUrl.parse(candidate, SourceKind.EMERGENCY_CSV) == null -> {
                logW("Default emergency URL is not valid; skipping seed")
                false
            }
            else -> {
                val existing = dao.observeAll().first()
                if (existing.isNotEmpty()) {
                    dataUtil.markEmergencyDefaultSeeded()
                    logD("Emergency source already configured; default not seeded")
                    false
                } else {
                    dao.upsert(
                        EmergencySourceConfigEntity(
                            url = candidate,
                            label = DEFAULT_EMERGENCY_LABEL,
                            enabled = true,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                    dataUtil.markEmergencyDefaultSeeded()
                    logD("Seeded default emergency source")
                    true
                }
            }
        }
    }
} catch (e: Exception) {
    logW("Default emergency source seed failed", e)
    false
}

// App.kt:274
internal const val DEFAULT_EMERGENCY_LABEL: String = "Default"

// App.kt:281–287 — android.util.Log is a throwing stub on this JVM test classpath
private fun logD(message: String) { runCatching { Log.d(TAG, message) } }
private fun logW(message: String, error: Throwable? = null) {
    runCatching { if (error != null) Log.w(TAG, message, error) else Log.w(TAG, message) }
}
```

`DataUtil.kt` — the once-only flag:

```kotlin
// DataUtil.kt:401–406
fun hasSeededEmergencyDefault(): Boolean =
    getBooleanSetting(SETTING_EMERGENCY_DEFAULT_SEEDED, false)

fun markEmergencyDefaultSeeded() =
    setBooleanSetting(SETTING_EMERGENCY_DEFAULT_SEEDED, true)

// DataUtil.kt:657–658
internal const val SETTING_EMERGENCY_DEFAULT_SEEDED: String =
    "SETTING_EMERGENCY_DEFAULT_SEEDED"
```

### 3.3 The four guarantees, and why each holds

| Guarantee | Mechanism | Failure it prevents |
|---|---|---|
| **Once only** | `SETTING_EMERGENCY_DEFAULT_SEEDED` flag, checked first | Resurrecting a row the user deliberately deleted |
| **Non-destructive** | `dao.observeAll().first()` then early-return if `isNotEmpty()` | Overwriting or duplicating a user's own source — **including a disabled one** |
| **Idempotent** | Same flag short-circuits a second call | Duplicate rows across repeated `onCreate`s |
| **Non-fatal** | `try/catch` in the seeder **and** `runCatching` in `onCreate` | A seed failure blocking app start; a failed seed leaves the flag **unset** so the next start retries |

URL validity is delegated to the **existing** `SourceUrl.parse(candidate, SourceKind.EMERGENCY_CSV)`
— the same parser and the same kind `EmergencySourceFetcher` uses (`:53`). An invalid or blank
resource therefore seeds nothing **and does not burn the flag** `[SOURCE CODE]`.

### 3.4 Exact diff

```diff
--- a/app/src/main/java/vn/unlimit/vpngate/App.kt
+++ b/app/src/main/java/vn/unlimit/vpngate/App.kt
@@ -8,10 +8,18 @@ import android.util.Log
 import androidx.appcompat.app.AppCompatDelegate
 import androidx.room.Room
 import de.blinkt.openvpn.core.OpenVPNService
+import kotlinx.coroutines.CoroutineScope
+import kotlinx.coroutines.Dispatchers
+import kotlinx.coroutines.SupervisorJob
+import kotlinx.coroutines.flow.first
+import kotlinx.coroutines.launch
 import vn.unlimit.vpngate.activities.DetailActivity
 import vn.unlimit.vpngate.activities.MainActivity
 import vn.unlimit.vpngate.data.remote.EmergencySourceConfigDao
+import vn.unlimit.vpngate.data.remote.EmergencySourceConfigEntity
 import vn.unlimit.vpngate.data.remote.ManualServerDao
+import vn.unlimit.vpngate.data.remote.SourceKind
+import vn.unlimit.vpngate.data.remote.SourceUrl
 import vn.unlimit.vpngate.db.AppDatabase
 import vn.unlimit.vpngate.db.ExcludedAppDao
 import vn.unlimit.vpngate.db.VPNGateItemDao
@@ -30,6 +38,15 @@ class App : Application() {
     // Z1-IMPL-D (Roadmap §12.5.2 item D): manually entered servers.
     lateinit var manualServerDao: ManualServerDao
 
+    /**
+     * v1.0.46 (§1.6.39): process-lifetime scope for one-shot startup work.
+     *
+     * Same shape as `ServerListUpdateScheduler`'s private scope
+     * (`CoroutineScope(SupervisorJob() + Dispatchers.IO)`). Used only for the
+     * emergency-default seed, which must never block [onCreate].
+     */
+    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
+
     override fun onCreate() {
         super.onCreate()
         appDatabase = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "vpn_gate_connector")
@@ -66,6 +83,19 @@ class App : Application() {
         dataUtil = DataUtil(this)
         isImportToOpenVPN = AppConfig.getBoolean("vpn_import_open_vpn")
 
+        // v1.0.46 (§1.6.39) CHANGE A: seed the default emergency source from
+        // R.string.default_emergency_source_url (written by
+        // emergency_csv_publisher.py). Fire-and-forget on IO so startup is
+        // never blocked, and wrapped so a seed failure cannot prevent the app
+        // from starting. The "once only" decision lives inside
+        // [seedEmergencyDefaultIfNeeded]; this block only supplies the inputs.
+        appScope.launch {
+            runCatching {
+                val url = getString(R.string.default_emergency_source_url)
+                seedEmergencyDefaultIfNeeded(dataUtil!!, emergencySourceConfigDao, url)
+            }.onFailure { logW("Default emergency source seed failed", it) }
+        }
+
         // Apply the user-selected appearance (System / Light / Dark) before any
         // activity is created so the whole app follows Theme.Material3.DayNight.
         // Default theme is Dark (2).
@@ -161,6 +191,100 @@ class App : Application() {
         fun getResourceString(resId: Int): String {
             return instance!!.getString(resId)
         }
+
+        /**
+         * v1.0.46 (§1.6.39) — seed the default emergency source exactly once.
+         *
+         * The publisher writes the URL into
+         * `R.string.default_emergency_source_url`, but before this phase no
+         * Kotlin code read it, so a fresh install had an empty
+         * `emergency_source_config` table and `ServerListUpdates`'s
+         * `emergencyDataset()` (which calls `dao.listEnabled()`) never had a
+         * source to fetch.
+         *
+         * Contract:
+         *  - **Once only.** Guarded by
+         *    [DataUtil.hasSeededEmergencyDefault]. A user who deletes the
+         *    seeded row has made an explicit choice, so an empty table after
+         *    the flag is set is NOT re-seeded.
+         *  - **Non-destructive.** If the table already holds any row (enabled
+         *    or not — a user may have disabled their own source), nothing is
+         *    inserted and only the flag is set.
+         *  - **Idempotent.** Re-running with the flag set is a no-op, so a
+         *    duplicate row can never be created.
+         *  - **Non-fatal.** Never throws; a failure leaves the flag unset so
+         *    the next start retries.
+         *
+         * Deliberately takes its three collaborators as parameters so a plain
+         * JVM test can drive it with a fake DAO and the in-memory preference
+         * seam, without an `Application`, a `Context` or Room.
+         *
+         * @return true only when this call inserted the row.
+         */
+        internal suspend fun seedEmergencyDefaultIfNeeded(
+            dataUtil: DataUtil,
+            dao: EmergencySourceConfigDao,
+            url: String,
+            now: Long = System.currentTimeMillis(),
+        ): Boolean = try {
+            if (dataUtil.hasSeededEmergencyDefault()) {
+                false
+            } else {
+                val candidate = url.trim()
+                when {
+                    candidate.isEmpty() -> {
+                        // Publisher has not run yet, or the resource was cleared.
+                        false
+                    }
+                    SourceUrl.parse(candidate, SourceKind.EMERGENCY_CSV) == null -> {
+                        logW("Default emergency URL is not valid; skipping seed")
+                        false
+                    }
+                    else -> {
+                        val existing = dao.observeAll().first()
+                        if (existing.isNotEmpty()) {
+                            // The user already has a source; never overwrite it.
+                            dataUtil.markEmergencyDefaultSeeded()
+                            logD("Emergency source already configured; default not seeded")
+                            false
+                        } else {
+                            dao.upsert(
+                                EmergencySourceConfigEntity(
+                                    url = candidate,
+                                    label = DEFAULT_EMERGENCY_LABEL,
+                                    enabled = true,
+                                    createdAt = now,
+                                    updatedAt = now,
+                                ),
+                            )
+                            dataUtil.markEmergencyDefaultSeeded()
+                            logD("Seeded default emergency source")
+                            true
+                        }
+                    }
+                }
+            }
+        } catch (e: Exception) {
+            // Never let a seed failure stop the app from starting.
+            logW("Default emergency source seed failed", e)
+            false
+        }
+
+        /** Label written on the seeded row, so the UI can tell it from user rows. */
+        internal const val DEFAULT_EMERGENCY_LABEL: String = "Default"
+
+        /**
+         * `android.util.Log` is a throwing stub on a plain JVM (this module's
+         * unit tests have no `returnDefaultValues`), so every log call in the
+         * seeder is wrapped: a diagnostic must never change the outcome.
+         */
+        private fun logD(message: String) {
+            runCatching { Log.d(TAG, message) }
+        }
+
+        private fun logW(message: String, error: Throwable? = null) {
+            runCatching { if (error != null) Log.w(TAG, message, error) else Log.w(TAG, message) }
+        }
         // Default VpnProfileCompat mode for openvpn2.4.x compatibility with softether vpn server
         const val VPN_PROFILE_COMPAT_MODE_24X = 20400
     }
```

```diff
--- a/app/src/main/java/vn/unlimit/vpngate/utils/DataUtil.kt
+++ b/app/src/main/java/vn/unlimit/vpngate/utils/DataUtil.kt
@@ -379,12 +379,31 @@ class DataUtil(context: Context?) {
     fun setAskedBatteryOptimization() = setBooleanSetting(SETTING_BATTERY_OPT_ASKED, true)
 
-    // §3 §4 §5 Auto Mode connection timeout per-server (5–60 seconds, default 12)
+    // §3 §4 §5 Auto Mode connection timeout per-server (8–60 seconds, default 12)
+    //
+    // v1.0.46: minimum raised from 5 to 8 seconds (operator requirement);
+    // default (12) and max (60) unchanged. Both the reader and the writer clamp,
+    // so a value stored by an older build at 5 or 6 or 7 reads back as 8.
     fun getAutoModeTimeoutSeconds(): Int =
-        getIntSetting(SETTING_AUTO_MODE_TIMEOUT_SECONDS, 12).coerceIn(5, 60)
+        getIntSetting(SETTING_AUTO_MODE_TIMEOUT_SECONDS, 12).coerceIn(8, 60)
 
     fun setAutoModeTimeoutSeconds(seconds: Int) {
-        setIntSetting(SETTING_AUTO_MODE_TIMEOUT_SECONDS, seconds.coerceIn(5, 60))
+        setIntSetting(SETTING_AUTO_MODE_TIMEOUT_SECONDS, seconds.coerceIn(8, 60))
     }
 
+    /**
+     * v1.0.46 (§1.6.39) — true once the default emergency source has been
+     * seeded into `emergency_source_config`.
+     *
+     * This flag, not the table contents, is what makes the seed **once only**.
+     * A user who deletes the seeded row has made an explicit choice, so an
+     * empty table after the flag is set must NOT be re-seeded.
+     */
+    fun hasSeededEmergencyDefault(): Boolean =
+        getBooleanSetting(SETTING_EMERGENCY_DEFAULT_SEEDED, false)
+
+    /** Marks the default emergency source as seeded; see [hasSeededEmergencyDefault]. */
+    fun markEmergencyDefaultSeeded() =
+        setBooleanSetting(SETTING_EMERGENCY_DEFAULT_SEEDED, true)
+
     /**
      * R8 (§11.13): whether Auto Mode automatically re-establishes a tunnel
@@ -631,4 +650,12 @@ class DataUtil(context: Context?) {
         const val SETTING_AUTO_MODE_TIMEOUT_SECONDS: String = "SETTING_AUTO_MODE_TIMEOUT_SECONDS"
 
+        /**
+         * v1.0.46 (§1.6.39): set once the default emergency source URL from
+         * `R.string.default_emergency_source_url` has been written into
+         * `emergency_source_config`. See [hasSeededEmergencyDefault].
+         */
+        internal const val SETTING_EMERGENCY_DEFAULT_SEEDED: String =
+            "SETTING_EMERGENCY_DEFAULT_SEEDED"
+
         /**
          * R8 (§11.13): Auto-Reconnect in Auto Mode. Default ENABLED — see
```

---

## 4. CHANGE B — the timeout range

### 4.1 Before

- `DataUtil.getAutoModeTimeoutSeconds()` — `coerceIn(5, 60)`, default `12`
- `DataUtil.setAutoModeTimeoutSeconds()` — `coerceIn(5, 60)`
- `SettingsScreen` picker — `val values = (5..60).toList()`
- Spec §6.7 R5 — "clamped to **5–60 s**"; §6.8.1 table — `Range | **5–60 s**`,
  `Settings UI | single-choice picker, values (5..60)`
- `AutoModeControllerTest` — asserted the source contains `.coerceIn(5, 60)`

### 4.2 After

- floor **8 s**; **default 12 s unchanged**; **max 60 s unchanged**
- both the reader **and** the writer clamp → an older build's stored 5/6/7 reads back as **8**
  with **no migration** (no preference rewrite, no DB change)
- picker offers `(8..60)`; its `indexOf` still resolves because `autoTimeout` already came through
  the read clamp `[SOURCE CODE]`

### 4.3 Exact diff

```diff
--- a/app/src/main/java/vn/unlimit/vpngate/ui/screens/settings/SettingsScreen.kt
+++ b/app/src/main/java/vn/unlimit/vpngate/ui/screens/settings/SettingsScreen.kt
@@ -1033,5 +1033,10 @@ fun SettingsScreen(
 
     if (showAutoTimeoutPicker) {
-        val values = (5..60).toList()
+        // v1.0.46 (§1.6.39) CHANGE B: floor raised 5 -> 8 (operator
+        // requirement). Default (12) and max (60) are unchanged. `autoTimeout`
+        // comes from DataUtil.getAutoModeTimeoutSeconds(), which clamps on
+        // read, so a value stored by an older build at 5..7 arrives here as 8
+        // and `indexOf` always finds it.
+        val values = (8..60).toList()
         SingleChoiceDialog(
             title = stringResource(R.string.setting_auto_timeout_label),
```

The `DataUtil` half of this change is inside the diff already shown in §3.4 (`:387`, `:390`).

---

## 5. Tests added (T-NEW-67..72, 13 cases)

### 5.1 `app/src/test/java/vn/unlimit/vpngate/db/EmergencyDefaultSeedTest.kt` — NEW, 8 cases

| Test | Pins |
|---|---|
| **T-NEW-67** `fresh install seeds exactly one default row from the resource URL` | exactly one row; `url` = resource URL; `label` = `Default`; `enabled = true`; flag set; returns `true` |
| **T-NEW-68** `an existing user source is never overwritten and the flag is set` | user row untouched; `upsertCount == 0`; flag still set; returns `false` |
| **T-NEW-68b** `a disabled user row also blocks the seed` | a **disabled** user row blocks the insert too — the non-destructive rule is not about `enabled` |
| **T-NEW-69** `flag set and table empty means the user deleted it - no re-seed` | flag + empty table → no insert (the deletion is respected) |
| `a blank resource URL seeds nothing and leaves the flag unset` | publisher has not run yet → no row, flag unset, next start retries |
| `an invalid URL seeds nothing and leaves the flag unset` | `SourceUrl.parse` rejection → no row, flag unset |
| `a DAO failure is swallowed and leaves the flag unset so the next start retries` | `failOnRead` fake → returns `false`, never throws, flag unset |
| `running the seeder twice inserts only one row` | idempotence: `upsertCount == 1` after two calls |

Doubles: `FakeEmergencySourceDao` (implements the DAO **interface**; tracks `upsertCount`, can be
told to `failOnRead`) and `SeedFakeSharedPreferences` / `SeedFakeEditor`. JUnit 4 only — no
Robolectric, no in-memory Room, no `Context`. `[TEST]`

### 5.2 `app/src/test/java/vn/unlimit/vpngate/utils/DataUtilTimeoutRangeTest.kt` — NEW, 5 cases

| Test | Pins |
|---|---|
| **T-NEW-70** `the default timeout with no stored value is 12 seconds` | default is **still 12** — the floor change did not move the default |
| **T-NEW-71** `setting 5 seconds is clamped up to 8 on read` | 5 → 8 |
| **T-NEW-71b** `a value stored by an older build at 7 reads back as 8` | the migration story: a raw stored 7 reads back 8, no rewrite |
| **T-NEW-72** `setting 100 seconds is clamped down to 60 and 8 is stored as 8` | ceiling 60 preserved; floor 8 is inclusive |
| **T-NEW-72b** `in-range values pass through untouched` | e.g. 20 → 20; no over-clamping |

Doubles: `TimeoutFakeSharedPreferences` / `TimeoutFakeEditor`. `[TEST]`

---

## 6. The existing test update

One assertion changed, in `AutoModeControllerTest.kt` — the only place in the suite that pinned the
old floor:

```diff
--- a/app/src/test/java/vn/unlimit/vpngate/automode/AutoModeControllerTest.kt
+++ b/app/src/test/java/vn/unlimit/vpngate/automode/AutoModeControllerTest.kt
@@ -2663,5 +2663,6 @@ val sstp = AutoModeCandidate(
      * nothing pinned any of them, so the drift was invisible to CI. This pins
      * the two that are reachable from the JVM; the spec figure is asserted by
-     * §6.7 R5 / §6.8 themselves.
+     * §6.7 R5 / §6.8 themselves. (v1.0.46: the *range floor* moved 5 → 8; the
+     * default asserted below is still 12.)
      */
     @Test
@@ -2678,6 +2679,7 @@ val sstp = AutoModeCandidate(
         )
         assertTrue(
-            "the timeout must stay clamped to the documented 5..60 range",
-            dataUtil.contains(".coerceIn(5, 60)"),
+            "the timeout must stay clamped to the documented 8..60 range " +
+                "(v1.0.46 raised the floor from 5 to 8)",
+            dataUtil.contains(".coerceIn(8, 60)"),
         )
     }
```

This was explicitly authorized by the brief ("any test pinning the old 5 s floor"). The
**default = 12** assertion in the same test is **untouched** — only the range assertion moved.
No test was deleted, disabled or skipped `[SOURCE CODE]`.

---

## 7. JVM tests (before → after)

| Module | Before | After | Delta |
|---|---|---|---|
| `:app` | **887 / 0 / 0 / 1** | **900 / 0 / 0 / 1** | **+13** (8 + 5 new cases) |
| `:SoftEtherClient` | **173 / 0 / 0 / 0** | **173 / 0 / 0 / 0** | 0 — module untouched |

Format: `tests / failures / errors / skipped`. The single `:app` skip is pre-existing and unchanged
by this block. Both runs used `CI=true` `[TEST]`.

---

## 8. Build + APK

- Command: `CI=true gradlew.bat :app:assembleProDebug --rerun-tasks --no-build-cache`
- Result: **SUCCESSFUL**
- Artifact: `app/build/outputs/apk/pro/debug/app-pro-debug.apk`
- Size: **63,779,506 bytes**
- SHA-256: **`88b4a4fe326faad4fbe716fe22ffa3bba9f20cd428ab05346d8c8936903b6591`**
- `aapt2 dump badging` → `versionCode='144'` `versionName='2.4.11-dev'` (**unchanged** — this block
  does not bump the app version), 4 ABIs, 28 `.so` entries
- `aapt2 dump resources` → **`default_emergency_source_url` is packaged** with the published URL,
  so `getString()` returns it at runtime rather than throwing `Resources$NotFoundException` `[TEST]`

> Note on the `:app` test count vs. the v1.0.45 report: v1.0.45 recorded `887/0/0/1` after its own
> 21 new tests. Nothing regressed; the +13 here is additive.

---

## 9. Diff-scope

**Modified (5):**

| File | Why |
|---|---|
| `app/src/main/java/vn/unlimit/vpngate/App.kt` | CHANGE A: scope + seeder + `onCreate` invocation |
| `app/src/main/java/vn/unlimit/vpngate/utils/DataUtil.kt` | CHANGE B clamps + the seeded flag |
| `app/src/main/java/vn/unlimit/vpngate/ui/screens/settings/SettingsScreen.kt` | picker `(5..60)` → `(8..60)` only |
| `app/src/test/java/vn/unlimit/vpngate/automode/AutoModeControllerTest.kt` | the one assertion in §6 |
| `PROJECT_SPECIFICATION.md` | E1–E7 (§2 below) |

**New (3):**

| File | Why |
|---|---|
| `app/src/test/java/vn/unlimit/vpngate/db/EmergencyDefaultSeedTest.kt` | 8 seeder cases |
| `app/src/test/java/vn/unlimit/vpngate/utils/DataUtilTimeoutRangeTest.kt` | 5 range cases |
| `EMERGENCY_DEFAULT_READER_REPORT.md` | this report |

**Present but NOT staged, NOT modified by this block:** `vpngate_collector.py`,
`emergency_csv_publisher.py`, `emergency/`, `scripts/`, `test-evidence/`,
`test_emergency_csv_publisher.py`, the four untracked `DEVICE_VERIFY_*` / `TRIAGE_*` reports,
`.vscode/`, `.workbuddy-ai/`. The publisher's files belong to a background process that also
produced `34389a4`; touching them is outside this block `[git]`.

**Spec edits applied (E1–E7):**

| ID | Location | Change |
|---|---|---|
| E1 | header | `HEAD` → `34389a4…`, `Document version` → `1.0.46`, date `2026-10-21` |
| E2 | §1 intro | appended the `> **Push state (v1.0.46).**` paragraph after the v1.0.45 one |
| E3 | before §2 | NEW `#### 1.6.39 EMERGENCY-DEFAULT-READER + TIMEOUT-RANGE (v1.0.46)`, 9 numbered items |
| E4 | §6.7 R5 + §6.8.1 table | `5–60 s` → `8–60 s`; `Range` and `Settings UI` rows |
| E5 | §11.9.1 | annotation block before `### 11.9.2` describing the seeded default source |
| E6 | §20 changelog | `1.0.46` row |
| E7 | §20 verification | row for §1.6.39 marked HISTORICAL |

---

## 10. §22 byte-identity

Extracted with `awk '/^## 22\./{flag=1} flag{print}'` from the working tree:

- **99 lines**
- **6,450 bytes**
- **SHA-256 `adf12858b72ed13742252c512a8728e9141e415cc55d09c6409af4e8e622020e`**

Identical to the value recorded at v1.0.44 and v1.0.45 → **§22 is byte-identical** `[TEST]`.
Nothing in this block produced a §22 entry, so no methodology lesson was added (spec §1.6.39 item 9).

---

## 11. Negative assurance

Confirmed **not** done, in line with the block's forbidden list:

- No `adb` / device command of any kind was issued. No device was connected or queried.
- No build or test ran without `CI=true`.
- No `git add -A`, `git add .`, `git reset`, `git clean`, `git stash`, `git checkout`,
  `git restore`, `--force`, or any tag operation.
- No test was deleted, disabled or skipped.
- `EmergencySourceConfigEntity`'s schema was **not** modified — no column, no migration, no version bump.
- The seed does **not** run when the table is non-empty (and does not run at all once the flag is set).
- No "sync from remote" behaviour was added.
- **Default (12 s) and max (60 s) were not changed** — only the floor.
- Native slot wait constants were not touched.
- Emergency sources were **not** wired into the collector ladder.
- C5 was not executed.
- `D:\PMC\Documents\Android\SoftEthergai` was not touched.
- Frozen files untouched: `AutoModeController.kt`, `AutoModeEngine.kt`, `AutoModeScreen.kt`,
  `AutoModeViewModel.kt`, `HttpFetcher.kt`, `VpnServerRepository.kt`, `GlobalVpnTracker.kt`,
  `EmergencySourceConfigEntity.kt`, `EmergencySourceConfigDao.kt`, `EmergencyCsvParser.kt`,
  `EmergencySourceFetcher.kt`, `emergency_defaults.xml` (read via `R.string` only),
  build files, manifests, schemas, `emergency-sources/*`, §1.6.2–§1.6.38, §12.5.1, §12.5.2,
  and every already-tracked report.

---

## 12. What remains

1. **Device verification of CHANGE A — PENDING `[UNKNOWN]`.** On a fresh install (no rows, flag
   unset): after first launch `emergency_source_config` must hold exactly **one** row carrying the
   published URL with label `Default` and `enabled = true`, and `SETTING_EMERGENCY_DEFAULT_SEEDED`
   must be `true`. A **second** launch must add nothing. Then: delete the row, relaunch, and confirm
   it is **not** resurrected (the once-only guarantee, which only a device can prove).
2. **Device verification of CHANGE B — PENDING `[UNKNOWN]`.** Settings must offer **8..60** with
   **12** selected. On a device carrying a pre-v1.0.46 value of 5, the picker must show **8**.
3. **The `EMERGENCY` ladder rung is still inert `[SOURCE CODE]`.**
   `VpnServerRepository.hasEmergencySources()` remains a stub returning `false`, so the ladder logs
   `[ladder] skip=EMERGENCY (none configured)` and never reaches the seeded row. The seeded row *is*
   consumed by the update pipeline via `ServerListUpdates.emergencyDataset()` — a **different** path.
   Wiring the ladder is a separate future phase and was explicitly out of scope here.
4. **P3 from v1.0.45 remains UNKNOWN.** The device `EPERM` on `path=vpn` is still unexplained; the
   v1.0.45 diagnostics make it attributable but do not fix it. `SelfExclusion` is still `UNKNOWN` in
   this build.
5. **The v1.0.45 post-push gate was not clean** — one unrelated, pre-existing racy test
   (`AutoModeControllerTest.pressWhileConnectedDisconnects`) failed 2 of 3 forced runs in a clean
   clone. It samples `c.state.value` synchronously while the D-5 continuation may already have
   published `Error`. Zero coupling to this block's diff; **not fixed**, because the file pins the
   v1.0.31 D-5 contract and needs a separate authorization.

---

## 13. Recommendation

**Ship it, then verify on a device.**

- Both changes are small, additive, and independently revertable. Neither touches the collector
  ladder, the Auto Mode controller, or any routing decision, so the blast radius is a single startup
  coroutine and two clamp constants.
- **CHANGE B is safe to land without a device run** — it is pure arithmetic, fully pinned by
  `DataUtilTimeoutRangeTest`, and clamp-on-read removes the migration question entirely.
- **CHANGE A is correct but unproven at runtime.** The seeder's logic is pinned by 8 tests, and
  `aapt2` proves the resource is packaged, so the remaining risk is narrow: does `onCreate` reach
  the seeder before anything reads the table, and does the flag persist across a real process death?
  A single fresh-install run answers both.
- **Do not read this block as "emergency sources work now".** It makes the *configuration* exist on a
  fresh install. The ladder rung that would actually *use* an emergency source is still a stub —
  that wiring is the next phase, and it is the one that will make the operator's
  "server list must always update" requirement true end-to-end.
