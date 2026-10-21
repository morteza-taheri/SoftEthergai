# EMERGENCY-LADDER-WIRING — Phase Report (v1.0.49)

**BLOCK ID:** v1.0.49 — EMERGENCY-LADDER-WIRING
**Document version:** 1.0.49
**Date:** 2026-10-21
**Repo:** `C:\Dev\SoftEthergai`, branch `main`
**Baseline HEAD (verified-against commit):** `d370f8d7cf6815dcc84d6691cefa4515bb653f98`
**Mode:** DETACHED (device-free) — no physical device, no adb, no live network fetch exercised.
**Operator requirement (original):** "if the physical path is blocked, update through the app's own tunnel; if the tunnel also fails, use servers.csv."

---

## 1. Summary

The `EMERGENCY` rung of `VpnServerRepository.refreshLocked()`'s collector ladder
(`PHYSICAL -> TUNNEL -> EMERGENCY`, defined in v1.0.45) was **inert**. On every
refresh it logged `[ladder] skip=EMERGENCY (none configured)` and never attempted
the emergency fetch — even though the v1.0.21 emergency fetch path
(`ServerListUpdates.emergencyDataset` / `EmergencySourceFetcher` /
`EmergencyCsvParser`) and the v1.0.46 seeded default source in
`emergency_source_config` both exist.

The rung was inert for **two independent reasons**, both now fixed:

1. **FIX-1 — the gate was a lie.** `hasEmergencySources()` was a stub returning
   `false` (introduced in v1.0.45). It never consulted `emergency_source_config`,
   so even when the v1.0.46 seeded default URL was present in the DAO, the ladder
   always logged `skip=EMERGENCY (none configured)`.
2. **FIX-2 — the rung was unreachable anyway.** Even had the gate returned `true`,
   `refreshLocked()` returned `loadSnapshot()` (the last-known-good dataset,
   non-null) on primary failure. That non-null snapshot intercepted the fallthrough
   that `ServerListUpdater.emergencyFetch` (the *only* other caller of
   `emergencyDataset()`) depends on — `emergencyFetch` only fires when the primary
   fetch returns `null`. So the emergency path was almost never reached.

This revision makes `refreshLocked()` attempt the `EMERGENCY` rung **itself**, and
folds the emergency records into the **same** `collected` pipeline as PHYSICAL/TUNNEL.
No emergency fetch logic was duplicated: the existing v1.0.21 path is reused through
a new `internal suspend fun emergencyRecords()` seam that `emergencyDataset()` now
delegates to. `DatasetIntegrity` still gates the eventual write.

---

## 2. Root-cause evidence (SOURCE CODE)

- `hasEmergencySources()` prior to this revision (v1.0.45): a stub — `return false`
  (or equivalent constant). The DAO `emergencySourceConfigDao.listEnabled()` was
  never queried.
- `refreshLocked()` empty-records branch prior to this revision: on
  `records.isEmpty()` it went **straight** to `loadSnapshot()` without ever testing
  the emergency rung. The snapshot is non-null whenever a prior good dataset exists,
  which is the common case after first successful boot.
- `ServerListUpdater.update()`'s `emergencyFetch` rung (line ~277) only fires when
  the primary fetch returns `null`. Because `refreshLocked()` returned a non-null
  snapshot, the primary result handed to `ServerListUpdater` was effectively
  "something", so the `emergencyFetch` rung was bypassed.

---

## 3. FIX-1 — `hasEmergencySources()` becomes a DAO-backed suspend check
[SOURCE CODE]

`app/src/main/java/vn/unlimit/vpngate/repository/VpnServerRepository.kt` (line ~481):

```kotlin
private suspend fun hasEmergencySources(): Boolean =
    runCatching {
        vn.unlimit.vpngate.App.instance
            ?.emergencySourceConfigDao
            ?.listEnabled()
            ?.isNotEmpty() == true
    }.getOrDefault(false)
```

Rationale: `refreshLocked` is a `suspend` function, so the DAO's `listEnabled()`
is called directly — no `runBlocking`, no dispatcher hop. Returns `false` on any
failure (fail-safe: an unreadable DAO cannot silently claim a source exists).

---

## 4. FIX-2 — `refreshLocked()` attempts the EMERGENCY rung before the snapshot
[SOURCE CODE]

`app/src/main/java/vn/unlimit/vpngate/repository/VpnServerRepository.kt`
(empty-records branch, line ~368):

```kotlin
if (records.isEmpty()) {
    // v1.0.49 (§1.6.42): attempt the EMERGENCY rung before falling
    // back to the last-known-good snapshot. ...
    if (hasEmergencySources()) {
        val emergency = ServerListUpdates.emergencyRecords()
        if (!emergency.isNullOrEmpty()) {
            fetchLog("[ladder] path=emergency source=${emergency.size}")
            records.addAll(emergency)
        } else {
            fetchLog("[ladder] skip=EMERGENCY (fetch produced no usable records)")
        }
    } else {
        fetchLog("[ladder] skip=EMERGENCY (none configured)")
    }
}

if (records.isEmpty()) {
    fetchLog("[ladder] ${primary.name} failed (no result) ...")
    fetchLog("[ladder] all rungs failed; preserving DB")
    return loadSnapshot()
}
```

Key property: emergency records are added to the **same** `records` list that is
already consumed by `VpnServerMerger.mergeRecords(records)` (line ~412), so they
pass through `DatasetIntegrity` on the eventual write exactly like PHYSICAL/TUNNEL
records. No separate write path was introduced.

---

## 5. The reuse seam — `internal suspend fun emergencyRecords()`
[SOURCE CODE]

`app/src/main/java/vn/unlimit/vpngate/repository/ServerListUpdates.kt`
(line ~142):

```kotlin
internal suspend fun emergencyRecords(): List<VpnServerRecord>? {
    val dao = vn.unlimit.vpngate.App.instance?.emergencySourceConfigDao ?: return null
    val sources = dao.listEnabled()
    if (sources.isEmpty()) return null

    val fetcher = EmergencySourceFetcher()
    val collected = mutableListOf<VpnServerRecord>()

    for (config in sources) {
        when (val fetched = fetcher.fetch(config.url)) {
            is EmergencySourceFetcher.FetchResult.Success -> {
                when (val parsed = EmergencyCsvParser.parse(fetched.body, SourceIdentity.EMERGENCY_TAG)) {
                    is EmergencyCsvParser.Result.Valid,
                    is EmergencyCsvParser.Result.Partial,
                    -> collected.addAll(EmergencyCsvParser.recordsOrEmpty(parsed))
                    is EmergencyCsvParser.Result.Malformed -> CollectorLog.d("Emergency source ${config.url} rejected: ${parsed.reason}")
                }
            }
            is EmergencySourceFetcher.FetchResult.HttpError -> CollectorLog.d("Emergency source ${config.url} answered HTTP ${fetched.status}")
            is EmergencySourceFetcher.FetchResult.NetworkError -> CollectorLog.d("Emergency source ${config.url} unreachable: ${fetched.reason}")
            is EmergencySourceFetcher.FetchResult.TooLarge -> CollectorLog.d("Emergency source ${config.url} body of ${fetched.length} chars exceeds ${EmergencySourceFetcher.MAX_BODY_CHARS}")
        }
    }

    if (collected.isEmpty()) return null
    val merged = VpnServerMerger.mergeRecords(collected)
    val valid = VpnServerMerger.validateServers(merged)
    if (valid.isEmpty()) return null
    ServerQualityCalculator.scoreAll(valid)
    return valid
}
```

`private suspend fun emergencyDataset()` (line ~232) now **delegates** to it:

```kotlin
private suspend fun emergencyDataset(): List<VPNGateItem>? {
    val valid = emergencyRecords() ?: return null
    val connectionList = VpnConnectionMapper.toConnectionList(valid)
    if (connectionList.size() == 0) return null
    return connectionList.toVPNGateItems()
}
```

Net result: **exactly one** `EmergencySourceFetcher()` instantiation site remains;
`emergencyDataset()` (the legacy `ServerListUpdater` hook) and
`VpnServerRepository.refreshLocked()` (the ladder) both consume the same validated
records. No fetch logic duplicated.

---

## 6. Safety properties preserved
[SOURCE CODE]

- **1 MiB / `MAX_BODY_CHARS` cap** and the per-hop **redirect-authority check**
  inside `EmergencySourceFetcher` are untouched (line 189–192 references
  `MAX_BODY_CHARS`; the class itself is FROZEN). Fail-closed: any `TooLarge`,
  `Malformed`, `HttpError`, or `NetworkError` contributes nothing.
- **`DatasetIntegrity`** still gates the write — emergency records enter the same
  `records` list fed to `VpnServerMerger` + the integrity check before the DB write.
- **v1.0.45 ladder order** (`PHYSICAL -> TUNNEL -> EMERGENCY`, EMERGENCY always
  last, never promoted) is unchanged and still covered by
  `VpnServerRepositoryLadderTest` T-NEW-55..57 (untouched).
- **v1.0.37 no-terminal contract** and the FROZEN set (`ServerListUpdater.kt`,
  `AutoModeController.kt`, `EmergencyCsvParser.kt`, `EmergencySourceFetcher.kt`,
  `HttpFetcher.kt`, `GlobalVpnTracker.kt`, `AutoModeEngine.kt`, `DataUtil.kt`,
  `SettingsScreen.kt`, `strings.xml`, `IPTerminal.kt`, `SoftEtherVpnService.kt`,
  `NativeLifetimeRegistry.kt`, build files) were not touched.

---

## 7. Tests — `EmergencyLadderWiringTest.kt` (NEW)
[TEST]

Source-shape regression (JUnit 4; reads `VpnServerRepository.kt` and
`ServerListUpdates.kt` as text — a plain-JVM unit test cannot construct the wired
repository or perform the HTTP fetch):

- **T-NEW-78** — No enabled source → branch logs `skip=EMERGENCY (none configured)`
  and does **not** reach `emergencyRecords()`; the skip path is preserved verbatim.
- **T-NEW-79** — With `hasEmergencySources()` true, `refreshLocked()` reaches
  `ServerListUpdates.emergencyRecords()`; the log `[ladder] path=emergency
  source=` is emitted and `internal suspend fun emergencyRecords()` is exposed
  (callable from the ladder).
- **T-NEW-80** — Emergency records are added to the shared `collected`/`records`
  list; exactly **one** `EmergencySourceFetcher()` instantiation exists across the
  module; `emergencyDataset` delegates to `emergencyRecords`.

---

## 8. Build & test evidence
[TEST]

- `:app` unit tests: **907/0/0/1** (passed/ignored/failed/—) — **+3** vs the
  v1.0.48 baseline of 904 (the 3 new source-shape tests).
- `:SoftEtherClient` unit tests: **177/0/0/0** — unchanged.
- `:app:assembleProDebug` SUCCESSFUL under `CI=true`
  (`--rerun-tasks --no-build-cache`):
  - APK size: **63,782,158 bytes**
  - APK SHA-256: `194255e41cc6ad18f60debb889526a49cfd8e129eab6d5dd367b14544a07b657`
  - `versionCode 144` / `versionName 2.4.11-dev` — **unchanged**

---

## 9. Specification updates
[SOURCE CODE + PHASE]

`PROJECT_SPECIFICATION.md` (all six edits applied):
- Header: `**HEAD:**` → `d370f8d7cf6815dcc84d6691cefa4515bb653f98`;
  `**Document version:** 1.0.49`.
- New push-state note for v1.0.49 (after v1.0.48's).
- NEW `#### 1.6.42 EMERGENCY-LADDER-WIRING (v1.0.49)` (12 bullets).
- §11.9.2 annotated: "Ladder now consumes the emergency path (v1.0.49 — §1.6.42)…".
- §20 Changelog: new `| 1.0.49 | 2026-10-21 | EMERGENCY-LADDER-WIRING … |` row.
- §20 Verification Status: new
  `| §1.6.42 — EMERGENCY-LADDER-WIRING (v1.0.49) | HISTORICAL … |` row.
- §22 left **byte-identical** (`adf12858…020e`, 99 lines).

---

## 10. Device verification
[UNKNOWN]

**NOT performed** — this is a DETACHED, device-free revision. The closing
device check (per §1.6.42 item 10) is: with **PHYSICAL and TUNNEL both failing**,
confirm the ladder falls through to EMERGENCY and succeeds against the seeded
`servers.csv`. This remains **PENDING** on a physical device.

---

## 11. Negative assurance (what was intentionally NOT done)

- **No fetch logic duplicated** — `EmergencySourceFetcher` + `EmergencyCsvParser`
  remain the single owner of emergency data; `emergencyRecords()` is the one seam.
- **No 1 MiB cap / authority check removed** — `EmergencySourceFetcher` is FROZEN.
- **No change to the v1.0.45 ladder ordering** — EMERGENCY stays last, never
  promoted.
- **No change to the v1.0.37 no-terminal contract.**
- **`ServerListUpdater.kt` and `AutoModeController.kt` left untouched** — the
  actual defect was in `refreshLocked()`, not in `ServerListUpdater`'s legacy hook;
  the brief's delegation ("stop if the actual seam differs") applies.
- **No tag operation of any kind** (create/delete/push) was performed.
- **No device, adb, or live-network fetch** was exercised (DETACHED mode).
- **No forced/reset/clean/stash/checkout/restore**; fast-forward push only.
- **Diff scope restricted** to the five authorized files; `vpngate_collector.py`
  (pre-existing, unrelated modification) was deliberately excluded.

---

## 12. Commits

1. `feat(routing): wire the EMERGENCY rung to the existing emergency fetch path`
   — `VpnServerRepository.kt` (FIX-1, FIX-2) + `ServerListUpdates.kt`
   (`emergencyRecords()` seam, `emergencyDataset()` delegation) +
   `EmergencyLadderWiringTest.kt` (T-NEW-78..80).
2. `docs: spec v1.0.49 — §1.6.42 emergency ladder wiring`
   — `PROJECT_SPECIFICATION.md` + `EMERGENCY_LADDER_WIRING_REPORT.md`.

Fast-forward push to `origin/main`.
