# Migration9to10Test — Focused Review (BLOCK v1.0.69 PART 1)

> Review only. **No user data, caches, scratch trees or unrelated changes were touched.**
> No commit / push / tag / release. Verdict: **PARTIAL**.

## 1. Files changed in this review step

- `app/src/test/java/vn/unlimit/vpngate/db/Migration9to10Test.kt` — **KDoc only** (added an explicit
  "LIMITATION" note). No assertion logic changed.
- (The test file itself was authored in the prior step; this step reviews it.)
- This review report (new).

## 2. How the test resolves `9.json` / `10.json` [INFERENCE + SOURCE CODE]

- There is **no** `testOptions { unitTests.all { workingDir = … } }` override in `app/build.gradle`
  (grep: none). Gradle's `Test` task therefore uses its default working directory — the **module
  project dir, `app/`**.
- `File("schemas/vn.unlimit.vpngate.db.AppDatabase/9.json")` → `app/schemas/vn.unlimit.vpngate.db.AppDatabase/9.json`.
  Same for `10.json`. This matches `Migration8to9Test`, which relies on the same relative path.
- **Caveat:** this is inferred from Gradle defaults and the existing test's reliance, not re-verified
  by an actual run (the run is environment-blocked, §8). If the working dir were ever changed, the
  test fails loudly with the "expected the exported schema at …" assertion — it cannot silently pass.

## 3. Is there an established SQLite-backed migration-test facility? [SOURCE CODE]

**No.**

- `testImplementation` declares **only** `junit:junit:4.13.2`. No `androidx.room:room-testing`, no
  Robolectric, no `org.xerial:sqlite-jdbc`, no `androidx.test` in the unit-test source set.
- `app/src/androidTest` contains only `ExampleInstrumentedTest.kt` and `ExcludeAppsTest.kt` — no
  migration harness, and instrumentation needs a device (currently `DEVICE_UNAVAILABLE`).
- **All five** existing migration tests (`Migration5to6Test`, `6to7`, `7to8`, `8to9`, and the new
  `9to10`) use a `SupportSQLiteDatabase` **recording proxy** — pure JVM, no real database.

## 4. The three validation types — what this test actually does

| Validation | Present in `Migration9to10Test`? |
|---|---|
| **SQL-recording / proxy contract** (records the `ALTER TABLE` statements, checks their shape) | **YES** |
| **Actual SQLite execution of `MIGRATION_9_10`** (opens a DB, runs the DDL, migrates rows) | **NO** |
| **Room schema validation** against the exported schema | **PARTIAL** — text-level parity with `10.json`; Room's own validator is not invoked |

## 5. Assertions vs the real `10.json` [SOURCE CODE]

Verified against the actual exported fields:

- `lastRefreshSeenAt` → `{"fieldPath","columnName","affinity":"INTEGER"}` (no `notNull`, no `defaultValue`).
  Test asserts INTEGER + nullable + no default. ✓
- `firstListedAt` → same shape. Test asserts INTEGER + nullable + no default. ✓
- `wifiConsecutiveFailures` → `{"…","affinity":"INTEGER","notNull":true,"defaultValue":"0"}`.
  Test asserts INTEGER + `notNull` + default `0`. ✓ (same for `cellular`/`unknown`.)

So the assertions match the real schema format and would pass if executed with `10.json` present.

## 6. Real SQLite-backed test — the exact blocker

A genuine "open v9 fixture → run `MIGRATION_9_10` → assert version 10 + row/default preservation" test
requires **a new dependency** (`androidx.room:room-testing` `MigrationTestHelper`, or `sqlite-jdbc`, or
Robolectric) **and a Gradle change**. The brief forbids adding dependencies / altering Gradle without
approval, and forbids fabricating a v9 DB by rewriting a v10 DB's version.

**Blocker:** the unit-test source set has no SQLite/Room test harness; adding one is a dependency +
Gradle change that needs separate operator approval.

## 7. Genuine v9 fixture provenance

The repository's genuine historical schema is `app/schemas/vn.unlimit.vpngate.db.AppDatabase/9.json`
(20 `server_history_summary` fields, exported at v9). A real fixture would be built from that export
(create the v9 tables, insert rows, migrate, assert). It was **not** fabricated and **not** implemented,
because no SQLite harness exists.

## 8. Test command and actual result

- **Not run.** `D:\.gradle-v1069` is **absent**; `./gradlew --version` with it **timed out (>240 s)**.
  `D:\.gradle-v1068`'s journal is blocked by **Security Center**. Per the rules I did **not** use an
  unapproved cache workaround and did **not** copy/delete caches.
- Status: **NOT TESTABLE** in the current environment. No pass is claimed.

## 9. Remaining gaps

- Real SQLite execution of `MIGRATION_9_10` with a genuine v9 fixture + row/default preservation: **NOT DONE**.
- Producers (quality-reject `QUALITY_REJECT_WINDOW_MS`, refresh markers, OD-9 durable Connected): **NOT DONE**.
- Full `:app` + `:SoftEtherClient` suites: **NOT RUN**.

## 10. Verdict

**PARTIAL.** Retain `Migration9to10Test` as a **limited SQL-contract + schema-parity** test (consistent
with the repo's convention). It does not execute the migration against a real database. To obtain a real
migration test, one of these must be approved:
1. Add `androidx.room:room-testing` (+ a Gradle change) and build a genuine v9 fixture from `9.json`; **or**
2. Add an instrumented `androidTest` migration test (needs a device — currently `DEVICE_UNAVAILABLE`).

Plus an environment decision for the Gradle cache (`D:\.gradle-v1069` absent / `D:\.gradle-v1068` blocked).
