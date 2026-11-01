# V1054_VERIFY_REPORT — read-only audit of v1.0.54, and the fixes it produced

**Date:** 2026-10-23
**Scope:** the v1.0.54 block (commits `e0213e8`, `ec3a73b`, `40ae8d0`), audited read-only, then repaired under operator authorisation.
**Spec:** v1.0.54 → v1.0.55, §1.6.51.
**Device:** none. Everything below is SOURCE CODE / TEST level; D1–D3 are unchanged.

---

## 0. Verdict

The five product changes of v1.0.54 are sound. The **claims around them** were not all true:

| # | Claim | Reality |
|---|-------|---------|
| 1 | "full suite green, 1044/0/0/1" | one true run. Ten forced runs: **7 pass, 3 fail** |
| 2 | §1.6.47(6) "strings removed across values, values-fa and values-vi" | `vi`/`fa` never had the manual-form set; they lost 7 keys each, not 26 |
| 3 | §1.6.49(8) imported-row preservation "SOURCE CODE + TEST" | no such test existed |
| 4 | "Clear cache" contract | documented in a comment, tested nowhere |
| 5 | new UI strings added | added in English and Persian, **none in Vietnamese** → 17 new lint errors |
| 6 | `skipInFlight` KDoc | described the implementation that had been reverted |

Five of the six were defects in the *evidence*, not in the shipped behaviour. That is still a defect: a claim that is not true is worse than no claim, because it stops the next reader from looking.

---

## 1. Ten consecutive full-suite runs (item 1)

Command: `CI=true ./gradlew :app:testProDebugUnitTest :SoftEtherClient:test --rerun-tasks --no-build-cache`

| Run | `:app` | `:SoftEtherClient` | Result |
|---|---|---|---|
| 1 | 1044 / 1F / 1S | 184 / 0 / 0 | **FAIL** `pressWhileConnectedDisconnects` |
| 2 | 1044 / 0 / 1 | 184 / 0 / 0 | pass |
| 3 | 1044 / 0 / 1 | 184 / 0 / 0 | pass |
| 4 | 1044 / 0 / 1 | 184 / 0 / 0 | pass |
| 5 | 1044 / 1F / 1S | 184 / 0 / 0 | **FAIL** `pressWhileConnectedDisconnects` |
| 6 | 1044 / 1F / 1S | 184 / 0 / 0 | **FAIL** `aFreshPressAfterExhaustionStartsFromTheTopAgain` |
| 7 | 1044 / 0 / 1 | 184 / 0 / 0 | pass |
| 8 | 1044 / 0 / 1 | 184 / 0 / 0 | pass |
| 9 | 1044 / 0 / 1 | 184 / 0 / 0 | pass |
| 10 | 1044 / 0 / 1 | 184 / 0 / 0 | pass |

Failure A, runs 1 and 5 — `AutoModeControllerTest.kt:656`, assertion at `:661`:

> `java.lang.AssertionError: expected:<AutoModeState$Disconnected@37911f88> but was:<Error(message=no_compatible_server)>`

Failure B, run 6 — `AutoModeControllerTest.kt:3019`, assertion at `:3041`:

> `AssertionError: expected the restart to connect, got Error(message=no_compatible_server)`

Both tests are untouched by v1.0.54 (`git show ec3a73b -- AutoModeControllerTest.kt` contains neither name). A is a known flake recorded since 2026-10-21 (`AUTO_DATA_AUDIT_REPORT.md:289`); B had no prior record. The single skip is `HtmlParserOracleTest.softetherUdpOnlyNeverBorrowsOpenvpnTcp`, gated by `assumeTrue` at `HtmlParserOracleTest.kt:311` on a fixture row that the current snapshot no longer contains.

---

## 2. Second press inside the `skipInFlight` window (item 2)

**(a) Next-Server press.** `skipToNextServer()` (`AutoModeController.kt:805`) → Connected branch → `synchronized(transitionLock)` at `:833` → guard at `:840-843` logs `[AUTO] Skip already in flight; duplicate request ignored` and returns. Flag set at `:844`, before any async work. **Coalesced — ignored.**

**(b) Auto Connect press.** `onButtonPressed()` (`:1275`) → `onButtonPressedWithoutGuard()` (`:1316`) → Connected branch `:1359-1363` logs `[AUTO] Skip in flight; button press treated as stop` and calls `stop()`, which cancels the pending continuation and publishes `Disconnected`. **Stops Auto Connect.**

So a Next-Server double-tap is ignored and does **not** stop Auto Connect; a press of the other button in the same window does stop it. Intended behaviour is written in §1.6.46 items 2 and 4 (`PROJECT_SPECIFICATION.md:3146`, `:3150`). Tests: `AutoModeControllerTest.kt:3835`, `:3884`, `:3956`.

**Defect found:** the KDoc at `:795-798` still claimed the flag is cleared in `runAutoMode()`'s first line, contradicting `:1636`. Fixed.

**Defect found:** the continuation published its replacement run from `synchronized(transitionLock) { job = scope.launch { runAutoMode() } }` (`:873-884`) without re-reading `skipInFlight`. `adapter.disconnect()` is a suspension point and `synchronized` is not, so a `stop()` that landed during the teardown had already set `job = null` — and the continuation then overwrote it with a fresh run, restarting Auto Connect after the user stopped it. Fixed.

---

## 3. What "Cache save time" actually did (item 3)

**Neither HTTP cache lifetime nor server-list freshness.** `getCacheSaveTimeMinutes()` (`e0213e8:DataUtil.kt:555`) was read only by the `connectionsCache` setter (`:107`) and `markConnectionsCacheFresh()` (`:136`), which stamped `Cache.expires` into `filesDir/CONNECTION_CACHE_KEY` and mirrored it to `vpn_cache_time`. `Cache.isExpires()` (`e0213e8:models/Cache.kt:18`) has **zero call sites**; the `connectionsCache` getter read Room unconditionally; the only reader of the expiry was `SettingsScreen.kt:198`, which displayed it. The setting controlled a displayed date and nothing else.

**Replaced by:** the pre-existing interval policy — `getServerListUpdateIntervalHours()` (`DataUtil.kt:485-495`), 1–24 h, default 12, plus explicit user refresh, plus one manual clear. Documented at `DataUtil.kt:506-515`, §1.6.47(3) and §11.14.1.

**`clearTransientCache()` deletes exactly two things** (`DataUtil.kt:114-133`): every child of `cacheDir`, and `filesDir/CONNECTION_CACHE_KEY`. It contains no DAO, repository or preference call, and `DataUtil` imports no `db`/`repository` type (`:1-20`). `manual_import_servers.json` is a sibling in `filesDir` (`App.kt:54`) and survives, because only the one named file is removed — the directory is never wiped.

**No test existed.** Closed: see §9, G-1.

---

## 4. Manual-server storage and compatibility (item 4)

**Reused, not replaced.** `ManualServerStore` (`repository/ManualServerStore.kt:27`), `ManualServerDao`, `ManualServerEntity` and the `manual_server` table are untouched; Room stays at `version = 8` (`db/AppDatabase.kt:23`) — no migration. `ManualImportStore` is additive (JSON, `App.kt:51-54`) because `manual_server`'s unique index is `(host, port, protocol)` while the list merges by host (`AppDatabase.kt:128-142`).

**Old-UI servers:** still listed with enable switch and delete (`SettingsScreen.kt:828-870`), still re-emitted on every write (`ServerListUpdates.kt:363-378`), still merged by `withManualRows` (`ManualServerStore.kt:145-146`).

**Proven by test:** re-import does not duplicate (`ManualImportStoreTest.kt:92`, `:102`, `:111`; `ManualCsvImportTest.kt:287`); persistence across instances (`ManualImportStoreTest.kt:175`) and under concurrency (`:222`); a manual OpenVPN row carries no config (`ManualServerStore.kt:162`, asserted at `ManualServerPreservationTest.kt:417`); a config-less candidate is never selected (`AutoModeController.kt:2642-2643`, `AutoModeControllerTest.kt:519-556`).

**Not proven at the time:** that an *imported* row survives a refresh merge. `manualServerRows()` was private and read `App.instance`. §1.6.49(8) claimed TEST evidence that did not exist. Closed: see §9, G-2.

---

## 5. Manifest and the bundled seed (item 5)

`AndroidManifest.xml:17-36` — a comment plus:

```xml
<queries>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent>
</queries>
```

**`QUERY_ALL_PACKAGES` is not used.** It appears once, at `:26`, inside the comment explaining why it was rejected.

**The asset contains zero package names.** `app/src/main/assets/default_excluded_apps` is 33 lines of format description and rationale; `grep -vc "^\s*#\|^\s*$"` → `0`. Pinned by `DefaultExcludedAppsTest.kt:66` (`the shipped asset carries no entries yet`) and `:78` (it must document its own format and mention D1). Nothing was invented.

Seeder rules in `ExcludedAppsSeeder.seedOnce()` (`:79-119`): installed-only `:99`, existing row keeps its name `:103`, flag `:117`, and `candidates.isEmpty()` returns **before** the flag is burned (`:87-90`). `runIfNeeded()` fails closed if `getInstalledApplications` throws (`:147-152`).

---

## 6. Pinning versus an active filter (item 6)

**It does not bypass the filter.** Search and the advanced filter run in `refreshView()` (`HomeScreen.kt:148-172`), upstream of `serverItems`; `pin()` runs last, after filter and sort (`HomeScreen.kt:433-448`), and returns the caller's list by identity when the pinned row is absent (`ServerListPinning.kt:71,79`). A connected server filtered out simply stays hidden.

Test: `ServerListPinningTest.kt:87` — `assertSame(filtered, pin(filtered, identity(b)))`.

---

## 7. Locales and lint (item 7)

| Locale | keys | added | removed | values changed |
|---|---|---|---|---|
| `values` | 379 → 369 | 17 | 26 | 4 |
| `values-fa` | 323 → 327 | 20 | 15 | 1 |
| `values-vi` | 259 → 252 | **0** | 7 | 1 |

**Orphans: none** — neither `vi` nor `fa` holds a key absent from default.

**`vi` received none of the 17 new keys.** The CSV-import surface, the pinned badge, the cache-clear label and the restore-default button all fell back to English, and each missing key is a `MissingTranslation` **error** in lint.

`:app:lintProDebug` **failed**: 124 errors / 177 warnings / 9 hints.

| Issue | Count | Notes |
|---|---|---|
| `UnusedResources` | 121 | pre-existing; none is a v1.0.54 key |
| `MissingTranslation` | 90 | **17 of them v1.0.54's**, 3 more from the rewritten `manual_server_*` labels |
| `NewApi` | 1 | `AndroidConnectionAdapter.kt:653`, pre-existing |
| `SuspiciousIndentation` | 1 | `ServerListUpdates.kt:486`, pre-existing (not in the v1.0.54 diff) |

---

## 8. Commit hygiene (item 8)

The brief required two commits per PART; `ec3a73b` carries all five. That was a process shortcut, not a technical necessity: I ran the suite once at the end instead of after each part, then committed once.

Per-PART file list is in §12 so the commit can be split. Four files are shared across parts — `App.kt` (4+5), `ServerCard.kt` (2+3), `SettingsScreen.kt` (2+4), `ConnectionListViewModel.kt` (2+4) and all three `strings.xml` (2+3+4) — so a per-PART revert needs `git revert -n` plus per-hunk staging rather than a clean file-level revert.

---

## 9. Fixes applied (v1.0.55)

| ID | Fix | Evidence |
|----|-----|----------|
| **G-3** | `pressWhileConnectedDisconnects` records every published state through `onStateChange` and asserts the **sequence** (`Disconnected` after `Connected`, then the continuation's own terminal) instead of sampling `state.value` / `isRunning`. | `AutoModeControllerTest.kt:656` |
| **G-4** | `aFreshPressAfterExhaustionStartsFromTheTopAgain`: per-attempt deadline 300 ms → 5 s (the test asserts ordering, not timeouts), and the failure message now prints `connects` plus the last 12 log lines. | `AutoModeControllerTest.kt:3042` |
| **G-1** | `DataUtil.clearTransientCache(cacheDir, filesDir)` extracted as a pure companion function; the instance method delegates and logs once on failure. `ClearTransientCacheTest` (11) proves the deletions **and** that the database, `-wal`/`-shm`, `manual_import_servers.json`, other `filesDir` entries and a sibling of `cacheDir` all survive. | `DataUtil.kt:114`, `:626+`; `ClearTransientCacheTest.kt` |
| **G-2** | `manualServerRows()` takes `importedProvider` / `daoProvider` (production passes the singleton lookups; behaviour unchanged). `ImportedServerEmissionTest` (9) proves imported rows are emitted with no DAO, beside legacy rows, with a disabled legacy row present, survive a wholesale `withManualRows` replace, are not duplicated against the network, and are not lost when either source throws. | `ServerListUpdates.kt:344-390`; `ImportedServerEmissionTest.kt` |
| **G-5** | 20 Vietnamese keys added (the 17 new + `manual_server_title` / `_summary` / `_empty`). | `values-vi/strings.xml` |
| **G-6** | The continuation re-reads `skipInFlight` inside `transitionLock` before publishing the replacement run, and logs `[AUTO] Skip superseded by stop; not relaunching` when a stop won; `skipInFlight`'s KDoc corrected to the real rule. | `AutoModeController.kt:873-900`, `:795-804` |
| **Docs** | §1.6.47(6) and §1.6.49(8) corrected in place; new §1.6.51; changelog and verification rows. | `PROJECT_SPECIFICATION.md` |

---

## 10. Verification after the fixes

Three consecutive `--rerun-tasks --no-build-cache` full-suite runs, all green:

| Run | `:app` | `:SoftEtherClient` |
|---|---|---|
| 1 | 1064 / 0 / 1 | 184 / 0 / 0 |
| 2 | 1064 / 0 / 1 | 184 / 0 / 0 |
| 3 | 1064 / 0 / 1 | 184 / 0 / 0 |

1064 = 1044 + 11 (`ClearTransientCacheTest`) + 9 (`ImportedServerEmissionTest`). The single skip is the pre-existing `assumeTrue` gate.

Lint after the Vietnamese keys: **104 errors / 178 warnings / 9 hints** (was 124 / 177 / 9). The delta is exactly the 20 `MissingTranslation` errors the translations cleared — the 17 new keys plus the three rewritten `manual_server_*` labels, which had never been translated either. No new finding of any kind appeared; `MissingTranslation` is now 70, of which 69 are `vi`.

---

## 11. What remains

- **D1** — the Iranian app package names for `assets/default_excluded_apps`. Still on the operator's phone. Adding them is a one-file data edit plus updating `DefaultExcludedAppsTest.kt:66`.
- **D2** — every on-device check: the pinned row, import by paste and by file, the removed Settings controls, the default protocol priority, the single cache button, a Next-Server double press, and the seed.
- **D3** — V-VERIFY-3/4.
- **Lint** — 104 errors, 178 warnings. 70 are `MissingTranslation` (`vi` lacks 97 default keys, `fa` 42), plus one `NewApi` and one `SuspiciousIndentation`. Lint is not a gate this project currently passes; that is recorded, not hidden.
- **Commit split** — see §12.

---

## 12. Per-PART file list for `ec3a73b`

- **PART 1** (2): `automode/AutoModeController.kt`, `test/.../automode/AutoModeControllerTest.kt`
- **PART 2** (12): `utils/DataUtil.kt`, `models/Cache.kt` (deleted), `models/VPNGateConnection.kt`, `activities/DetailActivity.kt`, `ui/screens/status/StatusViewModel.kt`, `automode/AndroidConnectionAdapter.kt`, `utils/AppConfig.kt`, `ui/components/ServerCard.kt` (2.1 hunk), `ui/screens/settings/SettingsScreen.kt` (cache + emergency hunks), `strings ×3`; tests `DataUtilDefaultsV1054Test.kt`, `DeveloperModeLogGateTest.kt`, `ManualServerProtocolChoicesTest.kt` (del), `ManualProtocolLabelExhaustiveTest.kt` (del)
- **PART 3** (5): `ui/screens/home/ServerListPinning.kt`, `ui/screens/home/HomeScreen.kt`, `ui/components/ServerCard.kt` (pinned hunk), `strings` (badge), `ServerListPinningTest.kt`
- **PART 4** (9): `repository/ManualCsvImport.kt`, `repository/ManualImportStore.kt`, `repository/ServerListUpdates.kt`, `viewmodels/ConnectionListViewModel.kt` (import half), `App.kt` (store half), `SettingsScreen.kt` (import section), `strings`; `ManualCsvImportTest.kt`, `ManualImportStoreTest.kt`, `ReadCappedUtf8Test.kt`
- **PART 5** (7): `assets/default_excluded_apps`, `utils/DefaultExcludedApps.kt`, `utils/ExcludedAppsSeeder.kt`, `App.kt` (seeder half), `AndroidManifest.xml`; `DefaultExcludedAppsTest.kt`, `ExcludedAppsSeederTest.kt`

35 unique files across the five parts.
