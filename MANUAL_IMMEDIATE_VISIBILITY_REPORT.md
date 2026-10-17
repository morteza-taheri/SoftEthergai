# MANUAL-SERVER-IMMEDIATE-VISIBILITY — Phase Report (v1.0.26)

**Phase:** MANUAL-SERVER-IMMEDIATE-VISIBILITY-v1.0.26
**Baseline:** `4f21f71b75c289da074543467669e6d43555931c` (HEAD at phase start, == origin/main)
**Date:** 2026-10-17
**Device:** `5585fecc` — `2201117TG` / `spes_global` (Redmi Note 11), `targetSdk=37`
**Installed build:** `versionCode=143` / `versionName=2.4.10-dev` (`build.gradle` frozen — no bump)

Evidence labels: **SOURCE CODE**, **TEST**, **PHYSICAL DEVICE**, **INFERENCE**, **UNKNOWN**,
**PRIOR REPORT**.

---

## 1. Verdict

All three defects were addressed, but not all three to the same depth — and one brief requirement
proved **not literally implementable** and was resolved by operator decision rather than by guessing.

| # | Defect | Outcome | Evidence |
|---|---|---|---|
| **B** | Manual row not materialised into `VPNGateItem` on add | **FIXED — device-verified** | SOURCE CODE + TEST + PHYSICAL DEVICE |
| **C** | `DEGRADED_DATASET` invisible to the user | **FIXED in code — NOT observed on device** | SOURCE CODE only |
| **D** | `manualProtocolLabel` fell back to `MS-SSTP` | **FIXED — test-guarded, not compile-guarded** | SOURCE CODE + TEST |

Two things a reader should take from this report rather than from the diff:

1. **(C) is not device-verified, and the report says so instead of implying otherwise.** Forcing a
   degraded fetch means cutting the device's connectivity. That is the operator's network, and the
   brief assigns those taps to the operator, so it was not done. The wiring compiles and is
   inspected; the live behaviour is **UNKNOWN**.
2. **(D) could not be built as the brief specified.** The brief asked for an exhaustive `when` with
   no `else`, matching the `SourceIdentity.sourceTag` fix of v1.0.23. That is impossible here for a
   structural reason, and the substitute is weaker. §4.3 states the reason plainly.

---

## 2. Pre-conditions

| Check | Result | Evidence |
|---|---|---|
| `HEAD == origin/main` | `4f21f71b…` both | SOURCE CODE |
| Tracked modifications at start | none | SOURCE CODE |
| Spec version | 1.0.25 | SOURCE CODE |
| §1.6.18 present | line 1373 | SOURCE CODE |
| Tags | 5 local / 5 remote (`p2.4.5/7/8/9/10`) | SOURCE CODE |
| Device attached | `5585fecc` | PHYSICAL DEVICE |
| Every Gradle invocation | ran with `CI=true` and JBR `JAVA_HOME` | TEST |

---

## 3. Diagnosis (STEP 2, read-only)

### 3.1 (B) — where the row could possibly come from

- `ConnectionListViewModel` already holds `manualServerDao` and reaches `vpnGateItemDao` through
  `App.instance?.vpnGateItemDao`.
- `ServerListUpdates.kt:268` — `dao?.replaceAll(withManualRows(items, manual.rows))` — is the **only**
  writer of `VPNGateItem`, and it runs inside an accepted refresh.
- `withManualRows` is `internal` in package `…repository`, i.e. visible module-wide, so the ViewModel
  can call it. `VPNGateItemDao.replaceAll` is `@Transactional`, dedupes via `dedupeForStorage`, and
  **refuses an empty list** — so it cannot blank the table.
- `addManualServer` wrote only to `manual_server` and never touched `VPNGateItem`.

**Conclusion:** fixable entirely inside the authorized set. `ManualServerStore` gained one method so
the merge is JVM-testable; no new DAO method was invented.

### 3.2 (C) — is there an existing surface?

- `Toast` is already used from Compose screens (`AutoModeScreen.kt:168`, `CopyDebugSheets.kt:134`).
- `HomeScreen` already imports `Toast` and holds `context` (line 96), and it is the refresh trigger
  (`getAPIData` at lines 226/236/321/462) and already observes `isLoading`/`isError`.
- `ServerListUpdater.Reason.DEGRADED_DATASET` exists and reaches the ViewModel as `outcome.reason`.

**Conclusion:** no new infrastructure needed — so the brief's "STOP if C requires new Toast/Snackbar
infrastructure" did **not** trigger.

### 3.3 (D) — why the requested fix cannot be written

`VpnProtocolNames` is an `object` of `const val` **Strings**:

```kotlin
object VpnProtocolNames {
    const val SOFTETHER = "softether"
    const val OPENVPN = "openvpn"
    const val L2TP_IPSEC = "l2tpIpsec"
    const val SSTP = "sstp"
    val ALL = listOf(SOFTETHER, OPENVPN, L2TP_IPSEC, SSTP)
}
```

`manualProtocolLabel(protocol: String)` therefore has a **`String` subject**, and Kotlin has no
exhaustive `when` over `String` — the compiler always demands an `else`. The v1.0.23 pattern
(`SourceIdentity.sourceTag`) worked because that `when` is over an **enum**, `SourceKind`.

Getting a compile-time guarantee would require converting the protocol keys to an enum or sealed
type: `VpnProtocolNames.kt` plus every call site. **`VpnProtocolNames.kt` is not in the authorized
list**, which is exactly the brief's stop condition "do not invent a fix that requires files outside
the AUTHORIZED list". The question was put to the operator, who chose the in-scope option.

`manualProtocolLabel` has **two** call sites, both in `SettingsScreen.kt` (line 765 for stored rows,
line 842 for the picker), so a change to it affects both surfaces consistently.

---

## 4. The fixes

### 4.1 (B) — write-through on add

`ManualServerStore.materialize` (new):

```kotlin
suspend fun materialize(current: List<VPNGateItem>): List<VPNGateItem> =
    withManualRows(current, toItems(listEnabled()))
```

It **delegates to `withManualRows`** rather than reimplementing the merge. That is the point: the
refresh path and the add path now share one preservation policy, so they cannot drift. Reimplementing
would have created a second policy that could silently disagree with the first.

`ConnectionListViewModel.addManualServer` now, after the store accepts the entry:

```kotlin
val published = withContext(Dispatchers.IO) {
    if (!store.add(host, portText, protocol, label, notes)) return@withContext null
    val itemDao = App.instance?.vpnGateItemDao ?: return@withContext null
    val merged = store.materialize(itemDao.getAll())
    if (merged.isEmpty()) return@withContext null
    itemDao.replaceAll(merged)
    merged
}
if (published != null) {
    vpnGateConnectionList.value = VPNGateConnectionList().fromVPNGateItems(published)
}
```

Publishing happens after `withContext` returns, i.e. back on the Main dispatcher, so `setValue` is
safe.

### 4.2 (C) — one-shot degraded-refresh notice

- `refreshNotice: MutableLiveData<Int?>` + `clearRefreshNotice()` in the ViewModel.
- Set **only** when `outcome.reason == ServerListUpdater.Reason.DEGRADED_DATASET`, in the
  `Preserved` branch where the list is non-empty.
- `HomeScreen` observes it, shows a `Toast`, and clears it — so a rotation cannot replay a message
  about a refresh that finished minutes ago.
- New string `server_list_update_preserved` in `values/strings.xml`:
  *"Server list could not be updated — stored list preserved"*.

**`isError` was deliberately not reused.** It means "there is nothing to show" and drives
`FullScreenError`; setting it for a degraded refresh would blank a list that is present and correct,
merely stale.

**Scope discipline:** `ServerListUpdater`, `DatasetIntegrity` and all seven thresholds are untouched.
`DEGRADED_DATASET` is still fail-closed. Only its *visibility* changed.

### 4.3 (D) — explicit mapping, raw-key fallback, test guard

```kotlin
internal fun manualProtocolLabelRes(protocol: String): Int? = when (protocol) {
    VpnProtocolNames.SOFTETHER -> R.string.manual_server_protocol_softether
    VpnProtocolNames.OPENVPN -> R.string.manual_server_protocol_openvpn
    VpnProtocolNames.L2TP_IPSEC -> R.string.manual_server_protocol_l2tpipsec
    VpnProtocolNames.SSTP -> R.string.manual_server_protocol_sstp
    else -> null
}
```

`manualProtocolLabel` returns `stringResource(res)` when non-null and the **raw key** otherwise. An
unknown protocol now reads as visibly odd (`wireguard`) instead of confidently wrong (`MS-SSTP`).

**The honest limitation:** the `else` still exists, because it must. The guard is
`ManualProtocolLabelExhaustiveTest` (3 tests) walking `VpnProtocolNames.ALL` — a test-time failure,
weaker than a compile-time one, but it fails in CI on the change that introduces the gap. This is
recorded in §1.6.19 bullet 3 and in the changelog rather than described as "exhaustive".

---

## 5. JVM tests

```
CI=true ./gradlew :app:testProDebugUnitTest :SoftEtherClient:test --rerun-tasks --no-build-cache
BUILD SUCCESSFUL in 9m 45s   (86 actionable tasks: 86 executed)
```

| Target | Baseline (v1.0.25) | This phase | Delta |
|---|---|---|---|
| `:app:testProDebugUnitTest` | 772 / 0 / 0 / 1 | **780 / 0 / 0 / 1** | **+8** |
| `:SoftEtherClient:test` | 173 / 0 / 0 / 0 | **173 / 0 / 0 / 0** | 0 |

New classes: `ManualServerImmediateMaterializeTest` **5/0/0/0**,
`ManualProtocolLabelExhaustiveTest` **3/0/0/0**. The single skip is the pre-existing
`HtmlParserOracleTest` assumption. Zero failures, zero errors across 59 result files. **No
regression.**

`ManualServerImmediateMaterializeTest` covers: immediate presence (with `seTcpPort` and `operator`
written correctly and no other row dropped); materialising into an **empty** table (fresh-install
case, must yield the manual row alone); a collision where the network already serves the address
(**no duplicate**, canonical row wins, unrelated rows survive); two adds in sequence; and removal
**not** deleting the materialised row (`assertSame` proves the empty-manual merge is a no-op by
identity).

---

## 6. Build

```
CI=true ./gradlew :app:assembleProDebug --rerun-tasks --no-build-cache --no-configuration-cache
BUILD SUCCESSFUL in 16m 25s   (114 actionable tasks: 114 executed)
```

| Field | Value |
|---|---|
| APK | `app/build/outputs/apk/pro/debug/app-pro-debug.apk` |
| Size | 63,739,346 bytes (**+2,320** vs v1.0.25's 63,737,026) |
| SHA-256 | `7619d1b06aecd626904efe2e52682b56ca67189623e0566be3339577707a522b` |

**Two attempts were lost to the environment, not to the code — recorded because it matters:**

1. First run: `FAILURE: Gradle build daemon disappeared unexpectedly`.
2. Second run: hung for **31 minutes** at `Reusing configuration cache`, having executed **zero**
   tasks — confirmed independently by `app/build` showing no file modified in 10 minutes and the APK
   still carrying its 13:14 timestamp.
3. Third run (fresh daemon after `--status` reported none running, plus `--no-configuration-cache`):
   SUCCESSFUL. Progress was confirmed mid-run by 43 files touched in 3 minutes at
   `stripProDebugDebugSymbols`.

The decisive evidence that this was infrastructure and not code: the **identical sources had already
compiled green** in the test run 20 minutes earlier. A compilation error would have surfaced there
first.

---

## 7. Device verification

Installed `-r` at `15:31:18`, preserving data. Navigation was driven by `adb shell input`, and every
claim below is read from a **UI-hierarchy dump** or from the database — not from a screenshot.

### 7.1 The add-to-visible path (fix B) — PASS

| Step | Observed |
|---|---|
| Settings list before | `1.1.1.1:443 · SoftEther · phaseB-test` |
| Add `8.8.8.8:443` / `softether` / `immediate-test` | Settings row appears: `8.8.8.8:443 · SoftEther · immediate-test` |
| Main list | **108 servers**, `Last updated: 17 Oct 2026 - 13:16` (unchanged) |
| Search `8.8.8.8` | **1 servers** — row `8.8.8.8` / `8.8.8.8.opengw.net` / `Score: 0` / `SoftEther` |
| Add `9.9.9.9:443` / `softether` / `dup-test` | Settings row appears |
| Main list | **109 servers**, `Last updated` still **13:16** |
| Search `9.9.9.9` | **1 servers** — row rendered with `Score: 0` |

**No refresh was performed at any point.** Three independent facts establish it: the header
timestamp never moved from the v1.0.25-era `13:16`; the count rose 107 → 108 → 109 by exactly one per
add; and the app's process logged **no** update line after install. The materialisation therefore
came from `addManualServer` alone, which is the whole point of the fix.

### 7.2 Database (main + WAL + SHM replayed)

| Item | Value |
|---|---|
| `user_version` / `integrity_check` | **7** / **ok** |
| `manual_server` | 3 rows: `1.1.1.1` `phaseB-test`, `8.8.8.8` `immediate-test`, `9.9.9.9` `dup-test` |
| `VPNGateItem` | **109** rows |
| `1.1.1.1` | 1 row — `operator='phaseB-test'`, `seTcpPort=443` |
| `8.8.8.8` | **1 row** — `operator='immediate-test'`, `seTcpPort=443` |
| `9.9.9.9` | **1 row** — `operator='dup-test'`, `seTcpPort=443` |
| `sqlite_sequence` | `manual_server`=3, `emergency_source_config`=1 |

Each address appears **exactly once** — the preservation invariant held, and the earlier
`1.1.1.1` row was neither duplicated nor lost.

**The WAL pull was again mandatory.** The main file was 1,036,288 B while `-wal` was **1,021,792 B** —
essentially the whole live dataset. All three files decoded to their exact on-device sizes
(1036288 / 1021792 / 32768) before any query.

### 7.3 Fix (C) — NOT verified on device

Not performed. Forcing `DEGRADED_DATASET` requires a fetch that succeeds but returns a dataset
missing the OpenVPN profiles the store holds; the practical way to force it is to cut connectivity.
That is the operator's network and the brief assigns those taps to the operator. Evidence for (C) is
therefore **SOURCE CODE only**, and its live behaviour is **UNKNOWN**. Steps for the operator are in
§10.

### 7.4 Fix (D) and the v1.0.25 fix — PASS (no regression)

The picker renders `MS-SSTP` on its **own row** at `[88,1417][349,1549]` with `checked=true`, i.e. the
v1.0.25 overflow fix is intact and MS-SSTP is still the default selection. The stored-server row also
renders through the new `manualProtocolLabelRes` (`1.1.1.1:443 · SoftEther · phaseB-test`), confirming
both call sites work.

---

## 8. Diff scope

```
 M app/src/main/java/vn/unlimit/vpngate/repository/ManualServerStore.kt      (+23)
 M app/src/main/java/vn/unlimit/vpngate/ui/screens/home/HomeScreen.kt        (+15)
 M app/src/main/java/vn/unlimit/vpngate/ui/screens/settings/SettingsScreen.kt(+47/-10)
 M app/src/main/java/vn/unlimit/vpngate/viewmodels/ConnectionListViewModel.kt(+64)
 M app/src/main/res/values/strings.xml                                        (+6)
?? app/src/test/.../repository/ManualServerImmediateMaterializeTest.kt
?? app/src/test/.../ui/screens/settings/ManualProtocolLabelExhaustiveTest.kt
 M PROJECT_SPECIFICATION.md
?? MANUAL_IMMEDIATE_VISIBILITY_REPORT.md
```

Every file is in the AUTHORIZED list. `ManualServerStore.kt` and `strings.xml` are from the
"POSSIBLY (only if STEP 2 proves necessary)" set, and both were proven necessary: the store needed a
pure-JVM-testable seam, and the notice needed a string resource.

Nothing from the FROZEN list was touched: `ServerListUpdater.kt`, `App.kt`, `AppDatabase.kt`,
`ManualServer.kt`/`Entity`/`Dao`, `EmergencyCsvParser.kt`, `EmergencySourceFetcher.kt`,
`SERVER_SOURCE_ARCHITECTURE.md`, `build.gradle`, manifests, and all XML except `strings.xml`.

Frozen-range proof (extracted from `HEAD` and the working tree, SHA-256 compared):

| Range | Result |
|---|---|
| §22 | 100 elements / 6,504 B / `adf12858…` — **IDENTICAL** (99 lines by `wc -l`) |
| §1.6.2–§1.6.18 | 1,183 / 94,596 B / `69508263…` — **IDENTICAL** |
| §12.5.1 | 22 / 14,635 B / `ee6d11de…` — **IDENTICAL** |
| §12.5.2 | 26 / 2,357 B / `18cd028c…` — **IDENTICAL** |

Spec hunks — six, all permitted: header; §1.6.1 push-state; new §1.6.19; §11.11; §20 changelog;
§20 Verification Status. Exactly one heading was added (`#### 1.6.19`).

---

## 9. Negative assurance

- **No** `build.gradle`, manifest, or XML other than `values/strings.xml` was modified.
- **No** change to `ServerListUpdater`, `DatasetIntegrity`, or any refresh-rejection threshold —
  `DEGRADED_DATASET` remains fail-closed.
- **No** `VPNGateItem` schema change, **no** migration, **no** Room version bump (still 7).
- **No** `git add -A` / `git add .`; no force-push, reset, clean, stash, checkout, or restore.
- **No** tag operation. **No** C5 execution.
- `D:\PMC\Documents\Android\SoftEthergai` was never touched.
- No raw binary through adb — every DB transfer was base64 with a decoded-size assertion.
- Device commands were confined to STEP 5 (install, UI taps, UI dump, DB pull, logcat read).
- The device's connectivity was **not** toggled.
- §22 and §1.6.2–§1.6.18 are byte-identical to `HEAD`.
- Three untracked `_v26_*.xml` UI dumps could not be deleted: the sandbox's safe-delete policy
  terminated `rm` with SIGTERM on every attempt. They are untracked, were never staged, and are
  harmless alongside this repo's existing untracked phase files.

---

## 10. Recommendations

1. **Verify (C) on the device when convenient.** Turn off Wi-Fi and mobile data, open the app, press
   Refresh, and confirm the toast *"Server list could not be updated — stored list preserved"*
   appears while the list stays populated. This is the only unverified claim in this phase. If the
   toast does not appear, the likely cause is that the fetch failed with `NETWORK_FAILURE` rather
   than `DEGRADED_DATASET` — the notice is deliberately scoped to the latter, so a plain offline
   failure will not show it. That distinction is intentional but arguably worth revisiting: "we could
   not reach the network" is also information the user may want.
2. **Decide whether (C) should cover more reasons.** `NETWORK_FAILURE`, `EMPTY_RESPONSE` and
   `PARSE_FAILURE` are equally invisible today. Widening the notice is a one-line change but is a
   product decision, not a bug fix.
3. **Close the `String`-subject gap properly, in its own phase.** The compile-time guard for protocol
   labels requires `VpnProtocolNames` to become an enum or sealed type. That touches the record layer
   and every call site, and it would also give the picker and the store a type-safe key instead of
   strings. The test guard is a stopgap, not a substitute.
4. **Give `VPNGateItem` a way to represent a manual server's protocol.** It still has no `protocol`
   and no `label` column, so the merge writes the label into `operator` and the port into
   `seTcpPort`, and a manual SSTP or L2TP entry is stored with its protocol flag clear. This is the
   remaining structural obstacle to manual servers being first-class rather than approximated.
5. **Consider the `DatasetIntegrity` interaction explicitly.** Adding a row to the measured table
   nudges `sizeFloor` by 0.5 and can cross `hasComparableBaseline`'s floor of 10. Immaterial at
   current sizes (106–109 against a floor near 54), but if a future phase supports a small manual-only
   dataset the interaction should be re-examined rather than assumed benign.
6. **Decide the removal policy.** Removing a manual server leaves its row in `VPNGateItem` until the
   next accepted refresh. That is consistent with §1.6.15's preservation invariant, but a user who
   removes a server and still sees it may reasonably call that a bug. Either eager deletion or an
   explicit "removed at next update" affordance would resolve the ambiguity.
7. **Read the daemon crash as environmental, and re-run rather than investigate the diff.** Two
   attempts were lost to a daemon crash and a 31-minute configuration-cache hang while the same
   sources compiled green in the test run. When this recurs, check `gradlew --status`, start a fresh
   daemon, and add `--no-configuration-cache` before suspecting the code.
