# BLOCK v1.0.67 — EXCLUSION SEED, SERVER-HISTORY LEDGER, SMART AUTOCONNECT, TRY-NEXT: Report

**Status: implemented and committed (no push).** Baseline + two feature commits; full JVM suite green;
APK built, installed `-r`, launched with no crash. Ten genuine runs and the full device A–D matrix are
the only items still open (listed in §9).

Repository: `morteza-taheri/SoftEthergai`, branch `main`.
Device: `5585fecc` (Xiaomi 2201117TG, Android 13). Package `vn.unlimit.vpngatepro` (2.4.11-dev / 144).

---

## 1. Executive Verdict

| Ask | Verdict | Basis |
|---|---|---|
| 1. Seed Iranian apps into the exclusion list on first run | **ALREADY IMPLEMENTED (no change)** | `assets/default_excluded_apps` (12 Iranian packages) + `DefaultExcludedApps` + `ExcludedAppsSeeder` |
| 2. Default server-list update interval = 1 week | **DONE** (`53f3311`) | default 168 h, option added, clamp 1..168, labels en/fa/vi |
| 3. Per-server history ledger | **ALREADY IMPLEMENTED** | `history/` package + Room v9 `server_history_summary/protocol/event` (live device data) |
| 4. Smart AutoConnect using history | **PARTIAL → ENHANCED** (`ec5df1f`) | history already tie-broke equal quality; added stable demotion of proven-dead servers |
| 5. Try Next enabled in AutoMode connected | **ALREADY ENABLED** | `enabled = state !is AutoModeState.Disconnected` |
| 6. Continue the previous work | **DONE** | baseline v1.0.63/64/65 committed first |

**Key finding:** most of the brief was already implemented in the codebase. The genuine gaps were the
**1-week interval** (hard-capped at 24 h) and a **history-driven demotion** in AutoConnect. Both are now
done; the rest was verified rather than rebuilt.

---

## 2. Initial Repository State

| Item | Value |
|---|---|
| HEAD (before) | `7bebf8d91bb4000c61dd0db2127bb1ee6f40a4c9` |
| `origin/main` | `7bebf8d…` (in sync) |
| Tracked modifications | 11 files (v1.0.63/64/65 uncommitted work) |
| Device | `5585fecc` present |

Preservation snapshot: `test-evidence/v1066/00_preservation_snapshot.txt`, `01_source_diffs.txt`.

---

## 3. Baseline Commit (Phase 0)

The uncommitted v1.0.63/64/65 work was committed first (operator chose "commit first"), in dependency
order, after a full build succeeded:

| Commit | Subject |
|---|---|
| `8b62d94` | feat(v1.0.63): emergency CSV UDP-support flag, supplementary merge, G-F29-1 fallback retention |
| `c38ebdc` | feat(v1.0.64/65): deterministic VPN-first ladder, per-refresh tunnel probe, injectable deadline |
| `15183c0` | [skip ci] docs(v1.0.63-65): spec entries for emergency merge and VPN-first ladder |

`ServerListUpdates.kt` fuses v1.0.63 + G-F29-1 (one shared `mergeFallback`) and `VpnServerRepository.kt`
fuses v1.0.64 + v1.0.65 (shared refresh path), so a four-commit split was not honest; two commits were.

---

## 4. Audit — what already existed

- **Exclusion seed (feature 1).** `DefaultExcludedApps` reads `assets/default_excluded_apps`; the
  `ExcludedAppsSeeder` performs a one-shot, installed-only seed. The asset already contains the **12**
  Iranian packages extracted from this device's `excluded_apps` table (D1, operator-confirmed
  "ALL CLASSIFIED-IRANIAN"). The device's 15 rows = those 12 + DeepSeek + Android Auto + Self; the last
  three were **deliberately not shipped** (non-Iranian / operator-personal / auto-excluded by `App.kt`),
  documented in the asset header. **No change is correct.**
- **History ledger (feature 3).** `app/…/history/` (HistoryCounters, RoomServerHistorySink,
  ServerHistoryDao, ServerHistoryEntities, ServerHistoryTypes) + Room schema **v9** with
  `server_history_summary`, `server_history_protocol`, `server_history_event`. The device DB holds live
  data: 15 summaries, 17 protocol rows, 48 events, per-source (wifi/cellular/unknown) counts,
  `qualityRejectionCount`, per-protocol counts.
- **Try Next (feature 5).** `AutoModeScreen.kt:425–452` already enables the button for every state except
  `Disconnected` (Connecting/Connected/Error).

---

## 5. Feature 2 — Default interval = 1 week (`53f3311`)

| File | Change |
|---|---|
| `utils/DataUtil.kt` | `DEFAULT_SERVER_LIST_UPDATE_INTERVAL_HOURS` 12 → **168**; `MAX_…` 24 → **168**; options `[1,3,6,12,18,24]` → `[1,3,6,12,18,24,168]` |
| `repository/ServerListUpdateScheduler.kt` | `intervalHours.coerceIn(1, 24)` → `coerceIn(1, 168)` |
| `ui/screens/settings/SettingsScreen.kt` | default index 3 → **6**; label branch `168 -> …_168h` |
| `res/values{,-fa,-vi}/strings.xml` | `setting_server_update_interval_168h` = "Every week" / "هر هفته" / "Every week" |
| tests | `DataUtilDefaultsV1054Test`, `ServerListUpdaterTest` updated (default 168, ceiling 168, 9999→169 h case) |

Existing users' stored values are untouched (only the default changes). Manual Update still bypasses the
interval.

---

## 6. Feature 4 — History-driven demotion (`ec5df1f`)

`AutoModeController.sortedForAutoConnect` already ordered by quality and used history **only to break
equal-quality ties** (F.29 §10). Added a **stable partition** after that ordering:

- `demoteUnhealthy(...)` moves a host with `successCount == 0 && consecutiveFailures >= 2` behind every
  healthy (or unknown-history) server, so the run stops spending its first attempts on a proven-dead
  endpoint. Within each group the existing quality/RTT/history order is preserved; with **no history**
  the list is returned untouched (fresh install = bit-identical to before).
- It does **not** promote a historically-good server above a higher-quality one (quality stays first).
- The demoted server is still attempted after the healthy ones — the "probe one bad server per run"
  behaviour.
- New test: `a never-succeeded server with a failure streak is demoted behind healthy ones`
  (`AutoModeHistoryRecordingTest`) — a fastest-but-dead server falls behind two healthy ones.

---

## 7. Verification performed

| Check | Result |
|---|---|
| Interval focused tests (`DataUtilDefaultsV1054Test`, `ServerListUpdaterTest`) | **PASS** |
| All `automode` tests | **PASS** (no regression from the demotion) |
| New demotion test | **PASS** |
| Full `:app:testProDebugUnitTest` + `:SoftEtherClient:testDebugUnitTest` | **BUILD SUCCESSFUL** |
| **3 genuine consecutive full runs** (`--rerun-tasks`) | **PASS ×3** — each `:app 1273/0/0/1`, `:SoftEtherClient 184/0/0/0` (579 / 508 / 509 s) |
| Lint `:app:lintProDebug` | **104 errors / 178 warnings / 9 hints** — identical to the v1.0.64/65 backlog; **no new issues** (first is pre-existing `NewApi` `AndroidConnectionAdapter.kt:653`) |
| `:app:assembleProDebug` | **SUCCESS** (12:32, 63,893,140 B). A later rebuild was blocked by **Windows Security Center** on a build-cache path; the installable file was recovered by `adb pull` of the installed `base.apk` → `SoftEthergai-v1.0.67-pro-debug.apk` (63,893,140 B). |
| Device install `-r` | **Success** (data preserved) |
| Launch | pid 13104, version 2.4.11-dev / 144, **no FATAL/AndroidRuntime crash** |

A disk-full condition (C: 100%) interrupted the first two APK attempts; it was resolved by deleting the
superseded `v1.0.59` gate tree (`/c/Dev/gate59`, my own temp extraction) and DB copies, not any user data.

---

## 8. Change-Control Confirmation

source **YES** · tests **YES** · report/spec **YES** (spec committed in baseline) · Gradle **NO** ·
dependencies **NO** · Room/schema **NO** (history already at v9) · migration **NO** · R8 **NO** ·
version **NO** · commits **YES (5)** · tags **NO** · push **NO** · release **NO** · uninstall **NO** ·
clear data **NO** · manual DB/snapshot modification **NO**.

---

## 9. Open items (not yet done)

- **Ten genuine consecutive full-suite runs**: **3 of 10 completed, all green** (`1273/0/0/1` + `184/0/0/0`
  each); the remaining seven were not run (disk/time).
- **Device matrix A–D**: VPN-disconnected refresh, VPN-connected refresh, mid-refresh disconnect,
  follow-up refresh — plus a UI confirmation that the new "Every week" option renders and that AutoConnect
  picks the demoted server last. (The unit tests cover the ordering logic; the device run is the remaining
  proof.)
- UI confirmation of the 1-week default requires a fresh-install profile (this device has a stored value);
  the unit tests assert the default/clamp.

---

## 10. Final Recommendation

**DEFINE TARGETED IMPLEMENTATION PHASE** — the remaining work is narrow and known: the ten-run gate, the
device A–D matrix, and (optionally) surfacing `qualityRejectionCount` in `ServerHistoryView` so the
demotion can also key on explicit quality rejections (today it keys on `successCount`/`consecutiveFailures`,
which are already sufficient).
