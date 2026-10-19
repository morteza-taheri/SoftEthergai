# RETENTION-IMPL-PHASE-1 Report

## §1 Verdict

**PASS.** Cumulative Server Retention phase 1 is implemented in the working tree (HEAD `9951c9c`, commit `feat(retention): phase 1 cumulative server retention foundation`), verified green under `CI=true`, and ready to push to `origin/main`.

| Metric | Value |
| --- | --- |
| Spec version | 1.0.39 (in `PROJECT_SPECIFICATION.md` working tree) |
| JVM :app | 823 / 0 / 0 / 1 (baseline 812 / 0 / 0 / 1; +11 new tests) |
| JVM :SoftEtherClient | 173 / 0 / 0 / 0 (unchanged) |
| APK `:app:assembleProDebug` | SUCCESSFUL under `CI=true` |
| APK SHA-256 | `79C102728581023F77F2542053FCE11131A0263563F2C86C08C9AA70A6DE3C17` |
| Schema export | `app/schemas/vn.unlimit.vpngate.db.AppDatabase/8.json` |
| §22 byte-identity | 99 lines / 6504 bytes — untouched |

---

## §2 Pre-conditions (verified before commit)

- HEAD `9951c9c9622ff94d694b8ee565dd2e3b318b71f1` == origin/main `fed878798a991e92e901064b17f50e42ab1d478e`
- Branch `main`
- Tag count: 5 (unchanged)
- Spec version: 1.0.39
- Tracked modifications: exactly the 5 authorized files (`VPNGateItem.kt`, `AppDatabase.kt`, `App.kt`, `ServerListUpdates.kt`, `PROJECT_SPECIFICATION.md`)
- Untracked new files: `8.json`, `Migration7to8Test.kt`, `ServerListRetentionTest.kt`
- `RETENTION_IMPL_PHASE_1_REPORT.md` did not exist before this phase report was created

---

## §3 The requirement and the gap

`SERVER_SOURCE_ARCHITECTURE.md` §9 defines **Cumulative Server Retention**: "the database grows across updates rather than being replaced." Approved as requirement in §12.5.2 item G, but NOT implemented before this phase. Today every accepted refresh calls `replaceAll()` (`deleteAll` + `insertAll`), discarding any stored server not present in the new dataset. A healthy server that drops off the live feed is lost forever.

---

## §4 The Room fields and the 7→8 migration

### VPNGateItem (field declarations, verbatim)

```kotlin
@ColumnInfo(name = "firstSeenAt", defaultValue = "0")
var firstSeenAt: Long = 0L,
@ColumnInfo(name = "lastSeenAt", defaultValue = "0")
var lastSeenAt: Long = 0L,
@ColumnInfo(name = "healthScore", defaultValue = "0")
var healthScore: Int = 0
```

### AppDatabase migration 7 → 8 (verbatim DDL)

```sql
ALTER TABLE VPNGateItem ADD COLUMN firstSeenAt INTEGER NOT NULL DEFAULT 0
ALTER TABLE VPNGateItem ADD COLUMN lastSeenAt INTEGER NOT NULL DEFAULT 0
ALTER TABLE VPNGateItem ADD COLUMN healthScore INTEGER NOT NULL DEFAULT 0
```

- `AppDatabase` version incremented `7 → 8`
- `App.kt` registers `MIGRATION_7_8` after `MIGRATION_6_7`
- KSP schema export `8.json` created and committed
- Zero DROP / DELETE / TRUNCATE / CREATE TABLE — existing rows survive the upgrade

---

## §5 mergeAll (the merge policy)

`ServerListUpdates.kt` — `mergeAll` is added alongside `replaceAll`. On acceptance of a fresh network observation:

1. `withManualRows` runs first (manual-row overlay, unchanged).
2. For each incoming row matched to a stored row by host name: keep `firstSeenAt`, set `lastSeenAt = now`, recompute `healthScore`.
3. For each incoming row that is NEW: `firstSeenAt = lastSeenAt = now`.
4. Stored rows NOT in the incoming dataset are **retained unchanged** (their `firstSeenAt` / `lastSeenAt` / `healthScore` carry forward).
5. Everything is committed through `replaceAll`'s `@Transaction`, so the table is never half-written.

### Phase-1 health-score formula

```
0   — lastSeenAt == 0 (reserved sentinel; never-refreshed / pre-schema rows)
100 — score > 0 && ping > 0
 50 — score > 0 || ping > 0
 25 — neither axis measured
```

This is deliberately minimal; the §9 retention-priority model is **phase 2**.

Phases 2–3 (max-server cap, retention-priority prune) are explicitly out of scope: phase 1 only **adds** and **refreshes**, never **prunes**.

---

## §6 Why the fallback path still uses replaceAll

In `ServerListUpdates.daoStore.replaceAll`, the accepted-refresh path branches on `fetchedFromCache()`:

- **Fresh network observation** (`fetchedFromCache == false`) → `mergeAll(...)` — preserves quiet servers.
- **Fallback** (snapshot / stored list / bundled seed, `fetchedFromCache == true`) → `replaceAll(withManualRows(items, manual.rows))` — a fallback is a *known-good complete set*, so it genuinely replaces wholesale.

---

## §7 The manual-row overlay (unchanged)

`withManualRows` is shared by both paths. Manual servers survive every write exactly as before; zero behavior change to the v1.0.22–v1.0.26 preservation invariant (regression test `T-NEW-26` passes).

---

## §8 Tests added

| File | Test(s) | Purpose |
| --- | --- | --- |
| `Migration7to8Test.kt` | 5 tests | Bounded migration 7→8; exactly 3 ALTER TABLE; no DROP/DELETE/TRUNCATE/CREATE TABLE; schema `8.json` matches DDL |
| `ServerListRetentionTest.kt` | 6 tests | T-NEW-23 new server retained; T-NEW-24 returning server keeps `firstSeenAt`/refreshes `lastSeenAt`; T-NEW-25 phase-1 health score; T-NEW-25b health sentinel reserved; T-NEW-26 manual overlay survives; T-NEW-27 additive merge, quiet servers never dropped |

---

## §9 JVM tests (before/after)

| Module | Before | After | Delta |
| --- | --- | --- | --- |
| `:app` | 812 / 0 / 0 / 1 | 823 / 0 / 0 / 1 | +11 tests (5 migration + 6 retention) |
| `:SoftEtherClient` | 173 / 0 / 0 / 0 | 173 / 0 / 0 / 0 | unchanged |

The single skip is `HtmlParserOracleTest.softetherUdpOnlyNeverBorrowsOpenvpnTcp` (data-dependent `AssumptionViolatedException`, unchanged).

---

## §10 Build + APK SHA-256

| Artefact | Value |
| --- | --- |
| Task | `:app:assembleProDebug` under `CI=true` |
| Result | SUCCESSFUL |
| APK | `app/build/outputs/apk/pro/debug/app-pro-debug.apk` |
| versionCode | 144 |
| versionName | 2.4.11-dev |
| SHA-256 | `79C102728581023F77F2542053FCE11131A0263563F2C86C08C9AA70A6DE3C17` |

---

## §11 Diff-scope

Only authorized files:

- `app/src/main/java/vn/unlimit/vpngate/models/VPNGateItem.kt`
- `app/src/main/java/vn/unlimit/vpngate/db/AppDatabase.kt`
- `app/src/main/java/vn/unlimit/vpngate/App.kt`
- `app/src/main/java/vn/unlimit/vpngate/repository/ServerListUpdates.kt`
- `app/src/test/java/vn/unlimit/vpngate/db/Migration7to8Test.kt`
- `app/src/test/java/vn/unlimit/vpngate/repository/ServerListRetentionTest.kt`
- `app/schemas/vn.unlimit.vpngate.db.AppDatabase/8.json`
- `PROJECT_SPECIFICATION.md` (and `RETENTION_IMPL_PHASE_1_REPORT.md`)

No other source touched. `AutoModeController.kt`, `AndroidConnectionAdapter.kt`, `SoftEtherClient/` source, `VpnServerMerger.kt`, `ManualServerStore.kt`, `ManualServerDao.kt`, `EmergencySourceFetcher.kt`, `EmergencyCsvParser.kt`, `build.gradle`, manifests all untouched.

---

## §12 §22 byte-identity

§22 ("Build and Verification Methodology") verified at 99 lines / 6504 bytes, unchanged between HEAD and the post-commit state. Not touched.

---

## §13 Negative assurance

- **No device / adb command** — device-free phase
- **No git add -A / git add .** — explicit file paths only
- **No push --force / reset / clean / stash / checkout / restore**
- **No tag operation**
- **No test deleted**
- **`replaceAll` behaviour unchanged** for the fallback path
- **ManualServerStore preservation invariant** unchanged
- **No max-server cap added** (phase 2)
- **No retention priority changed** (phase 2)
- **No Settings UI changed** (phase 3)
- **No signingConfig changed**
- **No C5 execution**
- **No touch of `D:\PMC\Documents\Android\SoftEthergai`**

---

## §14 What remains (phases 2–3)

- Max-server cap + retention-priority prune (§9.3)
- Retention priority formula replacing the phase-1 heuristic
- Settings UI for retention
- DatasetIntegrity `ACCEPT_NEW` / `MERGE_WITH_EXISTING` actual add-only behaviour
- Real-device 7→8 upgrade replay (JVM-verified only here)

---

## §15 CI environment lesson (from v1.0.39 attempt)

`-PCI=true` sets a Gradle property, NOT the environment variable. `System.getenv("CI")` stays null → the debug variant is disabled → `:app:test` becomes a no-op. **Must use `$env:CI="true"`** (or equivalent env var) for every build/test invocation.

---

## §16 Recommendation

The phase-1 implementation is complete and green. Commit 2 (spec + report), push `origin/main` fast-forward, then run the post-push standalone build gate (`:app:assembleProDebug` under `CI=true` in a clean checkout) to confirm the gate passes before declaring STEP 15 complete.
