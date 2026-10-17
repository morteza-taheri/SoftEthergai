# MANUAL-UI-INVESTIGATE-AND-FIX — Phase Report (v1.0.25)

**Phase:** MANUAL-UI-INVESTIGATE-AND-FIX-v1.0.25
**Baseline:** `591a722fc2a4cef66baf51afa7f46f7df3dab954` (HEAD at phase start)
**Date:** 2026-10-17
**Device:** `5585fecc` — `2201117TG` / `spes_global` (Redmi Note 11), Android `targetSdk=37`
**Installed build:** `versionCode=143` / `versionName=2.4.10-dev` (`build.gradle` frozen — no bump this phase)

Evidence labels used throughout: **SOURCE CODE**, **TEST**, **PHYSICAL DEVICE**, **INFERENCE**,
**UNKNOWN**, **PRIOR REPORT**.

---

## 1. Verdict

Two operator-reported symptoms were investigated. **One was fixed and device-verified. The other was
deferred by explicit operator decision, but its mechanism was established as fact — and in doing so
the phase's own initial diagnosis was refuted.**

| # | Reported symptom | Verdict | Evidence |
|---|---|---|---|
| **A** | SSTP not visible in the manual-server protocol picker | **FIXED** — layout overflow, not a missing option | SOURCE CODE + PHYSICAL DEVICE + INFERENCE |
| **B** | Manual server absent from the main list after a refresh, present after restart | **DEFERRED** (operator decision) — mechanism **proven**, not a rendering defect | PHYSICAL DEVICE + SOURCE CODE |

The important correction: the initial STEP-2 diagnosis claimed symptom B "cannot have been the main
list" and therefore raised a design-gap question. **That was wrong.** A restart triggers a refresh,
and an accepted refresh *does* materialise the manual row into the main list. The operator's
observation was accurate and referred to the main list all along. See §6.

---

## 2. Pre-conditions

| Check | Result | Evidence |
|---|---|---|
| Working tree clean of stray tracked edits | 1 tracked file modified (`SettingsScreen.kt`), 1 new test dir | SOURCE CODE |
| `build.gradle` / manifests / XML untouched | Confirmed — no diff | SOURCE CODE |
| `strings.xml` | **Read only**, not modified | SOURCE CODE |
| Device attached | `5585fecc`, `spes_global` / `2201117TG` | PHYSICAL DEVICE |
| Gradle invocations | Every one ran with `CI=true` and `JAVA_HOME` = Android Studio JBR | TEST |

`CI=true` is mandatory in this repository: `app/build.gradle`'s `androidComponents` block disables
all debug variants when `CI` is unset, so `*ProDebug*` tasks do not exist and Gradle fails with a
task-selection error that looks like a code error.

---

## 3. STEP 1–2 — Diagnosis (read-only)

### 3.1 Symptom A: the picker

`SettingsScreen.kt` rendered the four protocol chips as:

```kotlin
Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    listOf(
        VpnProtocolNames.OPENVPN,
        VpnProtocolNames.SOFTETHER,
        VpnProtocolNames.L2TP_IPSEC,
        VpnProtocolNames.SSTP,
    ).forEach { protocol -> FilterChip(...) }
}
```

A plain `Row` has **no wrapping and no horizontal scroll**. Anything that does not fit is laid out
past the parent's right edge and clipped — still present in the tree, but unreachable by touch.

The arithmetic, from widths measured on the device (§5.2), closes it:

| Quantity | Value |
|---|---|
| Screen width | 1080 px |
| Density | 2.75 → 392.7 dp |
| LazyColumn horizontal padding | 88 px each side |
| **Content width available** | **904 px** |
| Chip widths (OpenVPN / SoftEther / L2TP-IPsec / MS-SSTP) | 265 / 264 / 298 / 261 px |
| Inter-chip gaps (3 × 22 px) | 66 px |
| **Total required on one line** | **1154 px** |
| **Overflow** | **250 px** |

So the fourth chip was laid out from x≈981 to x≈1242 — beginning inside the viewport, ending
**162 px beyond the right edge**. It was never absent from the list; it was the only chip that could
not be seen.

**The aggravating factor that made this read as "SSTP does not exist":** `manualProtocolInput` is
initialised to `VpnProtocolNames.SSTP`. The *selected* chip was the invisible one, so the user saw
three unselected protocols and no indication of the current value. That is why the report was phrased
as absence rather than as a cut-off edge.

Evidence: SOURCE CODE (the `Row`), PHYSICAL DEVICE (measured widths), INFERENCE (the pre-fix
off-screen coordinates — arithmetic from the measured widths, because the pre-fix build was not
re-installed to observe the clipping directly).

### 3.2 A second, durable cause of the same symptom

The protocol list was written **inline**, not derived from `VpnProtocolNames.ALL`. A protocol added
to the record layer would therefore silently never appear in this form, and nothing would fail. This
is the hazard that would have re-created the symptom after the layout fix, so it was closed too.

### 3.3 Symptom B: the main list

Read-only tracing established:

- `ConnectionListViewModel.vpnGateConnectionList` is initialised from `dataUtil.connectionsCache`.
- All four `getAPIData` branches build the list from `App.instance?.vpnGateItemDao?.getAll()`.
- `DataUtil.connectionsCache`'s getter reads `vpnGateItemDao.getAll()`; the setter no longer writes.
- `HomeScreen` reads `connectionListViewModel.vpnGateConnectionList.value` and **never** touches
  `manualServers`.
- The **only** writer of `VPNGateItem` is `ServerListUpdates.kt:268` —
  `dao?.replaceAll(withManualRows(items, manual.rows))`.

So manual rows reach the main list only via `withManualRows`, i.e. **only inside an accepted
refresh** — never at add time, and there is no display-time merge.

At STEP 2 this was read as: *"therefore 'shown after restart' cannot have been the main list; it must
have been the Settings section"*, which raised the design-gap question put to the operator. **§6
shows that inference was wrong.**

---

## 4. STEP 3 — The fix

Three hunks in one authorized file, plus one new test file.

**`app/src/main/java/vn/unlimit/vpngate/ui/screens/settings/SettingsScreen.kt`** (+34 / −9)

1. `import androidx.compose.foundation.layout.FlowRow`
2. `Row(horizontalArrangement = Arrangement.spacedBy(8.dp))` →
   `FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp))`, with the inline `listOf(...)`
   replaced by `MANUAL_SERVER_PROTOCOL_CHOICES.forEach { … }` and a comment recording *why*.
3. The list hoisted to `internal val MANUAL_SERVER_PROTOCOL_CHOICES` above `manualProtocolLabel`.

**Design decisions, stated rather than implied:**

- **`FlowRow`, not a horizontal scroll.** Wrapping keeps every protocol visible simultaneously and
  requires no gesture to discover. A scroll would have fixed reachability but preserved the symptom's
  cause: an option the user cannot see without knowing to swipe.
- **No `@OptIn` was required, and that was verified rather than assumed.** A grep count for
  `@ExperimentalLayoutApi` in `FlowLayout.kt` is misleading; inspecting annotations per declaration
  shows the **non-`overflow` overload is stable** (only its internals are annotated). The successful
  compile without an opt-in is the confirmation.
- **The `Row` import stays** — four other call sites in the file still use it.
- **Hoisting to a named constant** is what makes the durable hazard testable; an inline list cannot be
  asserted against `VpnProtocolNames.ALL`.

**`app/src/test/java/vn/unlimit/vpngate/ui/screens/settings/ManualServerProtocolChoicesTest.kt`** (new, 4 tests)

| Test | Asserts |
|---|---|
| `the picker offers exactly the protocols the record layer understands` | set equality with `VpnProtocolNames.ALL` |
| `sstp is offered` | SSTP specifically — the reported protocol |
| `no protocol is offered twice` | no duplicate chips |
| `every offered protocol is a known record-layer protocol` | no invented keys (the store would reject them) |

Set — not list — equality is deliberate: the form's order matches the filter sheet and is not what
the test protects.

---

## 5. STEP 4–5 — Verification

### 5.1 JVM tests (TEST)

```
CI=true ./gradlew :app:testProDebugUnitTest :SoftEtherClient:test --rerun-tasks --no-build-cache
BUILD SUCCESSFUL in 10m 12s   (86 actionable tasks: 86 executed)
```

| Target | Baseline (PRIOR REPORT) | This phase | Delta |
|---|---|---|---|
| `:app:testProDebugUnitTest` | 768 / 0 / 0 / 1 | **772 / 0 / 0 / 1** | **+4** |
| `:SoftEtherClient:test` | 173 / 0 / 0 / 0 | **173 / 0 / 0 / 0** | 0 |

The single skip is the pre-existing `HtmlParserOracleTest` assumption, unchanged. `:app` failures = 0,
errors = 0 across all 57 result files. The +4 is exactly `ManualServerProtocolChoicesTest` — no other
suite moved. **No regression.**

### 5.2 Build (TEST)

```
CI=true ./gradlew :app:assembleProDebug --rerun-tasks --no-build-cache
BUILD SUCCESSFUL in 11m 44s   (114 actionable tasks: 114 executed)
```

| Field | Value |
|---|---|
| APK | `app/build/outputs/apk/pro/debug/app-pro-debug.apk` |
| Size | 63,737,026 bytes |
| SHA-256 | `390be5c5a197e0c8cdef9ef61c3908c05e8a8778b3eb5dbf1bccb0e1f8ce984a` |

Installed with `adb install -r` (data preserved), `lastUpdateTime 2026-10-17 13:15:12`.

### 5.3 Device verification of the fix (PHYSICAL DEVICE)

Method: launch via `SplashActivity` (the launcher entry — `MainActivity` is not directly startable and
`am start` on it throws), navigate to the `Setting` tab, scroll to the manual-server form, then read
the **UI hierarchy dump** rather than eyeballing a screenshot. Bounds are objective; a screenshot
would have required a human judgement about a clipped edge.

Picker as rendered after the fix:

| Row | Chip | Bounds | State |
|---|---|---|---|
| 1 | `OpenVPN` | `[88,878][353,1010]` | `checked=false` |
| 1 | `SoftEther` | `[375,878][639,1010]` | `checked=false` |
| 1 | `L2TP/IPsec` | `[661,878][959,1010]` | `checked=false` |
| **2** | **`MS-SSTP`** | **`[88,1010][349,1142]`** | **`checked=true`** |

All four are `clickable=true`, `enabled=true`, `checkable=true`. `MS-SSTP` has wrapped to a second
line and sits entirely within the 1080 px width. **The selected protocol is now both visible and
correct** — which was precisely the failure mode.

Note on the device-side walkthrough: navigation was driven by `adb shell input tap`/`input swipe`
rather than by hand. The rendered layout is identical either way, so the evidence is unaffected; the
method is stated so the reading is not mistaken for a hand-executed tap sequence.

### 5.4 Post-install database state (PHYSICAL DEVICE)

**The `-r` install preserved the operator's data.** Verified by pulling **main + `-wal` + `-shm`**,
replaying them, and querying.

| Item | Value |
|---|---|
| `user_version` | **7** |
| `integrity_check` | **ok** |
| `manual_server` | id 1, `1.1.1.1:443`, `softether`, label `phaseB-test`, `enabled=1` |
| `emergency_source_config` | id 1, the `raw.githubusercontent.com/…/emergency-sources/servers.csv` URL, label `test`, `enabled=1` |
| `sqlite_sequence` | `manual_server`=1, `emergency_source_config`=1 |
| `VPNGateItem` | **107** rows |

**Why the WAL pull was not optional.** The main file was 1,036,288 B while `-wal` was **1,030,032 B**
— essentially the entire live dataset. A main-file-only read reproduces v1.0.24's
"0 rows / empty `sqlite_sequence`" result, which was a **false negative**. The reliable procedure is:
`ls -l` first; pull main **plus** `-wal` **plus** `-shm`; replay beside a copy; and transfer as base64
with a decoded-size assertion against the on-device `stat -c %s` (all three matched exactly:
1036288 / 1030032 / 32768).

---

## 6. Symptom B — mechanism proven, and the phase's own diagnosis corrected

At launch (`13:16`) the network was healthy for the first time in this phase:

```
Accepted mirror_1 from http://150.40.105.6:11803/en/ (mirror_1: 10 columns, 100 rows)
Accepted mirror_3 from http://150.40.105.11:4917/en/ (mirror_3: 10 columns, 100 rows)
Accepted html from https://www.vpngate.net/en/ (html: 10 columns, 100 rows)
Accepted api from https://www.vpngate.net/api/iphone/ (api: 96 rows)
Collected 106 servers (capability=SourceCapability(providesProtocolColumns=true, providesOpenVpnProfiles=true))
Dataset outcome MERGE_WITH_EXISTING: 106 incoming vs 103 stored (candidate 106 server(s) vs stored 103)
Scheduled server list update: Updated(serverCount=106, snapshotSaved=true, sourcedFromCache=false)
```

`VPNGateItem` then held **107** = 106 collected + **1 manual**. `withManualRows` had run, and the
manual row is physically present:

```
hostName='1.1.1.1'  ip='1.1.1.1'  operator='phaseB-test'  seTcpPort=443
isSSTPSupport=0  isL2TPPSupport=0   (all other columns 0/NULL)
```

Contrast with `12:20`, recorded earlier in this phase:

```
Collected 94 servers (capability=…providesOpenVpnProfiles=false)
Scheduled server list update: Preserved(reason=DEGRADED_DATASET, serverCount=103)
```

`dropsOpenVpnProfiles()` returned true (store had 103 rows with profiles; the candidate had none), so
`ServerListUpdater` returned **before** `DatasetIntegrity.evaluate` and `replaceAll` never ran. The
manual row was absent from `VPNGateItem` — and therefore from the main list.

**Conclusion.** Symptom B is not a rendering defect and not a data-loss defect. A manual row is
written into `VPNGateItem` only by an **accepted** refresh. A newly added server is invisible in the
main list until some later refresh happens to be accepted, and stays invisible indefinitely while
refreshes are rejected as degraded. "Shown after restart" is explained: the restart triggered a
refresh, that refresh was accepted, and the row was materialised.

**This refutes the STEP-2 claim** that "shown after restart" could not have been the main list. The
operator's report was accurate. The operator's decision — **fix the picker now, defer manual-row
visibility** — is unchanged and is respected here; what changes is that the deferred item now rests on
a proven mechanism, and its cost is localised: `addManualServer` does not materialise the row.

### 6.1 Structural finding for the future phase

`VPNGateItem` has **no `protocol` and no `label` column**. The merge writes the manual entry's
`label` into `operator` and its `port` into `seTcpPort`, leaving the protocol flags clear. So even
once visible, a manual SoftEther entry is distinguishable from a collected one only by its
`seTcpPort`; a manual SSTP or L2TP entry would be stored with its protocol flag **clear**. This is
recorded, not fixed — it is outside this phase's authorized change and is the substantive input to
any manual-visibility phase.

---

## 7. STEP 6 — Diff-scope verification

`git status --porcelain` at close:

```
 M app/src/main/java/vn/unlimit/vpngate/ui/screens/settings/SettingsScreen.kt
?? app/src/test/java/vn/unlimit/vpngate/ui/screens/settings/
 M PROJECT_SPECIFICATION.md
?? MANUAL_UI_FIX_REPORT.md
```

Tracked diff before the spec/report: `1 file changed, 34 insertions(+), 9 deletions(-)` — the picker
fix only. Everything else untracked in the repository is pre-existing phase junk (~200 files) and was
**never staged**; commits were made by exact path.

Frozen-range proof, extracted from `HEAD` and the working tree and compared by SHA-256:

| Range | HEAD | Working tree | Result |
|---|---|---|---|
| §22 | 100 elements / 6,504 B / `adf12858…` | identical | **IDENTICAL** |
| §1.6.2–§1.6.17 | 1,067 / 84,828 B / `c803cb3b…` | identical | **IDENTICAL** |
| §12.5.1 | 22 / 14,635 B / `ee6d11de…` | identical | **IDENTICAL** |
| §12.5.2 | 26 / 2,357 B / `18cd028c…` | identical | **IDENTICAL** |

§22 is **99 lines / 6,504 bytes** by `wc -l` on the extracted range, matching the spec's own claim.
One correction was needed to reach literal identity: inserting §1.6.18 had consumed one of the two
blank lines that separated §1.6.17 from §2, so the second blank line was restored. Content was
already byte-identical before that (`1065` lines equal after stripping trailing blanks); the fix makes
the frozen-range proof literal rather than "identical modulo a separator".

Spec hunks — six, all in permitted regions:

| Hunk | Region |
|---|---|
| `@@ -7,2 +7,2 @@` | header (`HEAD` → `591a722…`, version → 1.0.25) |
| `@@ -294,0 +295,10 @@` | §1.6.1 push-state note |
| `@@ -1362,0 +1373,116 @@` | new §1.6.18 (9 bullets) |
| `@@ -2507,0 +2634,18 @@` | §11.11 implementation-status annotation |
| `@@ -3103,0 +3248 @@` | §20 changelog row |
| `@@ -3152,0 +3298 @@` | §20 Verification Status row |

---

## 8. Evidence table

| ID | Claim | Label |
|---|---|---|
| E1 | The pre-fix picker was a non-wrapping, non-scrolling `Row` | SOURCE CODE |
| E2 | Four chips require 1154 px against 904 px available | PHYSICAL DEVICE (widths) + INFERENCE (arithmetic) |
| E3 | The pre-fix 4th chip was clipped off-screen | INFERENCE (pre-fix build not re-installed) |
| E4 | `manualProtocolInput` defaults to SSTP | SOURCE CODE |
| E5 | `FlowRow`'s non-`overflow` overload is stable → no `@OptIn` | SOURCE CODE + compile-verified |
| E6 | The list was inline and is now a test-pinned constant | SOURCE CODE + TEST |
| E7 | `:app` 772/0/0/1, `:SoftEtherClient` 173/0/0/0 | TEST |
| E8 | APK builds, SHA-256 `390be5c5…` | TEST |
| E9 | All four chips render, `MS-SSTP` wrapped and `checked=true` | PHYSICAL DEVICE |
| E10 | `-r` install preserved both operator rows; `user_version=7`; integrity ok | PHYSICAL DEVICE |
| E11 | `replaceAll` at `ServerListUpdates.kt:268` is the only `VPNGateItem` writer | SOURCE CODE |
| E12 | An accepted refresh merges 106 + 1 = 107 | PHYSICAL DEVICE |
| E13 | A rejected refresh writes nothing (`DEGRADED_DATASET`) | PHYSICAL DEVICE |
| E14 | Manual `label`→`operator`, `port`→`seTcpPort`; no protocol column | PHYSICAL DEVICE + SOURCE CODE |
| E15 | The STEP-2 "cannot have been the main list" claim was wrong | PRIOR REPORT superseded by PHYSICAL DEVICE |
| E16 | A main-only DB read is a false negative in WAL mode | PHYSICAL DEVICE + INFERENCE |

---

## 9. Negative assurance

- **No** `build.gradle`, manifest, or XML file was modified. `strings.xml` was read only.
- **No** `git add -A`, `git add .`, force-push, reset, clean, stash, checkout, or restore.
- **No** tag operation of any kind.
- **No** C5 execution.
- `D:\PMC\Documents\Android\SoftEthergai` was never touched.
- No raw binary was transferred through adb — all DB pulls were base64 with decoded-size assertions.
- The manual-row visibility fix was **not** attempted: the operator deferred it, and a display-time
  merge would create a visible-but-not-stored state.
- §22 and §1.6.2–§1.6.17 are byte-identical to `HEAD`; §12.5.1 and §12.5.2 are byte-identical.

---

## 10. Recommendations

1. **Close the `manualProtocolLabel` fail-open default.** It ends in
   `else -> R.string.manual_server_protocol_sstp`. Now that the option list is test-pinned to
   `VpnProtocolNames.ALL`, a future protocol would be *offered* correctly but *labelled* "MS-SSTP".
   Make the `when` exhaustive with no `else`, as `SourceIdentity.sourceTag` was made exhaustive in
   v1.0.23 — the compiler then enforces the mapping. Not fixed here because it is a different defect
   from the one reported and the brief called for a minimal fix.
2. **Decide the manual-visibility question with the mechanism now known.** The options are: (a)
   materialise the manual row into `VPNGateItem` at add time (consistent with §11.11, but writes
   outside a refresh); (b) merge `manualServers` at display time (visible-but-not-stored); (c) accept
   the current behaviour and surface it — e.g. a hint in the form that a new server appears after the
   next successful refresh. (c) is the cheapest honest option; (a) is the correct one and needs the
   protocol-identity column question settled first.
3. **`VPNGateItem` cannot represent a manual server's protocol.** Any fix for item 2 must first decide
   where a manual row's protocol lives; writing the label into `operator` is a workaround, not a
   model.
4. **Keep the WAL discipline in every future DB inspection.** The v1.0.24 "insert code never executed"
   reading was a false negative produced by a main-only pull. That conclusion must not be re-used as
   evidence; §1.6.17 bullet 4 carries a superseding note in §1.6.18 bullet 8.
5. **`DEGRADED_DATASET` is invisible to the user.** A rejected refresh silently leaves the list stale,
   and the phase-G `Dataset outcome` line only appears when a write happens — so the diagnostic is
   absent exactly when it is most needed. Consider logging the outcome on the preserved path too.
6. **Consider a UI-level assertion for chip reachability.** The JVM test pins the option *list*; it
   cannot see clipping. The device dump used here (all chip bounds within screen width) is a
   repeatable check worth scripting if picker layout is touched again.
