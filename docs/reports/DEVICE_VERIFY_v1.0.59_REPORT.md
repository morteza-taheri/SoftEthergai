# BLOCK v1.0.59 — IMPORT-CRASH FIX, v1.0.54 LEFTOVERS, DUPLICATE-IP AUDIT: Device Verification Report

> **Label note.** The operator's brief names this block "v1.0.59". The changelog already carries a
> `1.0.59` row, but that row documents the **launch-crash fix** (`ConnectionListViewModel` `init`
> ordering, commit `7dac3a6`); it is a different defect. This block — the **import-crash fix**
> (`MutableLiveData.setValue` from a background thread), the v1.0.54 leftovers, and the
> duplicate-IP audit — is commit `95163f5` and is recorded here as `§1.6.58` / changelog `1.0.62`
> to keep the sequence unambiguous. The finding that motivates PART A was first made in `§1.6.57`
> item 9 (a P0 import crash, reproduced on device, deliberately **not** fixed there for want of
> authorisation); this block closes it.

**Scope (the operator's brief).**
- **PART A** — fix the P0 `LiveData.setValue` from a background thread (device crash at
  `ConnectionListViewModel.kt:370` / `:333`).
- **PART B** — close the v1.0.54 leftovers: the stale *Block Ads* string, the whitespace
  host/IP validation, and the misleading `serverCount` log line.
- **PART C** — duplicate-IP audit.
- **PART D** — device verification of the import path.
- **PART E** — three + ten consecutive green committed-tree runs, then push fast-forward only if
  the gate is met; post-push standalone gate from `git archive origin/main`; final A–M report.

**Outcome.** The P0 import crash is **FIXED and device-verified**. The v1.0.54 leftovers are
**closed** (string removed, import validator hardened, log line corrected). The duplicate-IP
audit is **complete** and recorded as a known storage characteristic (no code change within the
authorised files). The import path was driven on device `5585fecc` with **no crash** on import or
delete, idempotent re-import, and survival across Refresh and a process restart.

**Evidence labels.** `[SOURCE CODE]` read from the tree, `[TEST]` executed on the JVM,
`[PHYSICAL DEVICE]` observed on the phone, `[INFERENCE]` derived, `[PRIOR REPORT]` from an earlier
block, `[OPERATOR STATEMENT]` from the operator.

---

## A. Repository state, before and after

| Item | Before (start of this block) | After |
|---|---|---|
| HEAD | `0fd4875` (`chore(emergency): update servers.csv`, which already contains the fix `95163f5`) | `0fd4875` + one documentation commit (specification + this report) |
| `origin/main` | `0fd4875` — the v1.0.59 **source is already published** | `0fd4875` + the same documentation commit, fast-forward |
| Branch | `main` | `main` |
| Tags | 5 | 5 (**none created**) |
| Working tree | F.29 uncommitted work + untracked reports | **identical** apart from the two documentation files this block is authorised to commit |

`git status --short` was recorded before the block started, again after the device run, and again
after the gate; the three listings are **identical** (`33` lines, `diff` empty). The block's own
commits then remove exactly two entries from that listing — `PROJECT_SPECIFICATION.md` moves from
` M` to clean and `DEVICE_VERIFY_v1.0.59_REPORT.md` from `??` to tracked — leaving `31` lines and
nothing else changed. The `test-evidence/dv59/` artefacts stay **untracked**, exactly as the earlier
`dv56`/`dv57` evidence did. No F.29 file (`App.kt`, `AutoModeController.kt`, `AutoModeEngine.kt`,
`AppDatabase.kt`, `AutoModeNextServerIdentityTest.kt`), no `vpngate_collector.py` and no
`D1_CANDIDATES.md` was edited, staged or committed; their diffs are the same ones that were in the
tree at the start (`+678/−6` across the five files, untouched by this block). [SOURCE CODE + git]

---

## B. Device inventory (A1–A3)

| Item | Value |
|---|---|
| Serial | `5585fecc` — one device, state `device` |
| Model | `2201117TG` (Xiaomi Redmi 10C) |
| Android | 13 (API 33), `arm64-v8a` |
| Package | `vn.unlimit.vpngatepro` |
| versionCode / versionName | 144 / `2.4.11-dev` (unchanged by this block) |
| Network | Wi-Fi, no tunnel at start |

**A2 — the hard database gate.** The installed app was upgraded in place (`adb install -r`); the
Room schema (`user_version = 9`) was unchanged by this block, so the install could not trip Room's
identity check. [PRIOR REPORT — the schema is v9 since F.29 Phase 2; this block changes no schema.]

**A3 — read-only snapshot (pre-import baseline).** `VPNGateItem` 261 rows, `excluded_apps` 15 rows,
`manual_server` 0 rows, `emergency_source_config` 1 row (the default, enabled),
`server_history_*` empty. [PRIOR REPORT — carried over from the §1.6.57 device state.]

---

## C. PART A — the P0 import crash, fixed

**Root cause [SOURCE CODE + PRIOR REPORT].** `vpnGateConnectionList` is a `MutableLiveData`.
`materializeImported()` (`:370`) and `removeImportedServer()` (`:333`) both assigned
`vpnGateConnectionList.value = …` from inside `withContext(Dispatchers.IO)`. `MutableLiveData
.setValue` asserts the **main** thread; the assignment therefore threw
`IllegalStateException: Cannot invoke setValue on a background thread` after the data work had
already completed, killing the app on import and on delete. The older `addManualServer()` path
(`:228`) assigns *after* the `withContext` returns, which is why this was never seen before v1.0.54
introduced the import. Reproduced on device `5585fecc` in §1.6.57 item 9 (import 16:07:13, delete
16:09:54).

**The fix [SOURCE CODE].** A single `publishLiveData(target, value, dispatcher =
Dispatchers.Main.immediate)` helper, and the two crash sites now call it instead of `.value = …`.
It performs a `withContext(Dispatchers.Main.immediate)` hop and assigns on the main thread. It is
**not** `postValue`: `postValue` defers and can **coalesce** two rapid publishes into one, whereas
both callers return to the UI immediately and the caller's contract is "published before the caller
continues" (e.g. `importManualCsv` hands the parse result to the summary dialog). A hop preserves
that guarantee; `postValue` would have weakened it.

**Audit of every LiveData write in `app/src/main` [SOURCE CODE].** Eight sites on
`vpnGateConnectionList`, two on `lastUpdatedTime`, one on `refreshNotice`, none on `isError`/
`isLoading` (already `postValue`), plus `StatusViewModel.state` and `AutoModeViewModel.state`. Only
the two fixed here ran inside an IO context; the rest assign after `withContext` returns, from
`viewModelScope` (Main), or from main-thread callbacks. **No `LiveData` exists outside `:app`.**

**Tests [TEST].** New `LiveDataPublishThreadTest`, **7 tests**:
- a thread-asserting `ArchTaskExecutor` delegate makes `setValue` off the designated main thread
  throw exactly as the device did, so the harness is **proven** rather than assumed;
- `publishLiveData` is exercised from a background thread and shown to land the value, to have
  finished before returning, and to propagate a failure;
- a **source-shape guard** fails if any `MutableLiveData` `.value =` reappears inside a
  `withContext(Dispatchers.IO | Default)` block anywhere in `app/src/main`. It strips comments and
  string literals first (a naive version took a KDoc line that merely *mentioned*
  `withContext(Dispatchers.IO)` for a real block and reported four false positives). Its KDoc states
  its limit: it cannot see a write hidden behind a helper or written as `setValue(…)`.

---

## D. PART B — v1.0.54 leftovers

**B1 — stale *Block Ads* string [SOURCE CODE].** `dns_setting_hint` still read
"…will turn off Block Ads setting when enabled" in `values`, `values-fa` and `values-vi`. The
feature it refers to was deleted in v1.0.54. The sentence is removed from all three locales.

**B2 — whitespace / control-character host & IP [SOURCE CODE + PHYSICAL DEVICE].** The device
stored `test-a. example` — a space inserted after the dot by an input method — which survives as a
server no DNS can resolve and the dedupe can never match. `ManualCsvImport` now rejects, per row,
a host name or IP that contains **inner** whitespace or a control character, with a reason naming
the row; leading/trailing whitespace is still trimmed and accepted. Control characters are replaced
with a middle dot in the summary so an invisible character never reaches the UI.

**B3 — misleading `serverCount` log [SOURCE CODE].** `Server list updated atomically: N servers`
read like the table size while `N` was the **incoming** dataset — the exact misreading that
produced the false V9 finding in §1.6.57 items 4–5. It now reads `N incoming, M stored` (two named
numbers; `outcome.serverCount` is `usableRows.size`, never the table, and `list.size()` is the
stored table after the write).

**Tests [TEST].** `ManualCsvImportTest` grew by **5** tests: trim-accept, inner-space-reject,
inner-space-IP-reject, control-char-reject, and a predicate unit test.

---

## E. PART C — duplicate-IP audit (measured, and it does not reproduce)

**Method [PHYSICAL DEVICE].** The live database was pulled **byte-exactly** through
`adb exec-out run-as vn.unlimit.vpngatepro sh -c 'cat databases/vpn_gate_connector | base64'` into a
folder outside the repository (`C:/Dev/dv59_audit/`), decoded on the host and queried with SQLite.
The decoded file is **1,193,936 B**, identical to the on-device file size; the `-wal` is **0 B** and
the `-shm` 32,768 B, so nothing was in flight. `PRAGMA integrity_check` = `ok`. Raw output:
`test-evidence/dv59/duplicate_ip_audit.txt`.

| Measure | Value |
|---|---|
| `VPNGateItem` rows | **261** |
| `manual_server` rows | 0 |
| Distinct IPv4 values | **261** |
| Duplicate-IP groups | **0** |
| Rows inside duplicate groups | 0 |
| Row inflation (extra rows) | **0** |
| Duplicate `hostName` values | 0 |
| Fields with padded / inner whitespace | 0 |
| Source composition | 218 API (`vpnNNNNNNNNN`), 40 emergency (`public-vpn-*`), 3 other (`diamondgnd`, `patito`, `idcrmn2`) |
| Source pairs forming a duplicate | **none — no group exists** |

**The IP named in the brief.** `123.48.124.169` exists as **one** row: `vpn122916437`, `tcpPort`
1558, a 10,007-character `openVpnConfigData`. The config-less twin `vpn813516587` that
`DEVICE_VERIFY_v1.0.53_REPORT.md` §5 recorded **is no longer in the table** (0 rows). So the
condition the brief describes is **not reproducible today**: it was real once (v1.0.53), and it is
gone now.

**Correction to the previous revision of this section.** It stated that the pair was "observed
during the PART D device run" and attributed the duplication to the API source versus the emergency
source. Both statements were **wrong**. Nothing in this block observed a duplicate row, and the one
historical instance was **API-versus-API** (`vpn122916437` / `vpn813516587`), not API-versus-emergency.
The claim is corrected here instead of being carried forward. [PHYSICAL DEVICE + PRIOR REPORT]

**Can Auto Connect dial the same IP twice in one run? Structurally yes — dormant, not absent.**
`AutoModeEngine.defaultServers()` maps the stored list one-to-one and performs **no IP-level
de-duplication**. The run's only per-run guard is `attempted.add(wedgeKey(server))`
(`AutoModeController.kt:2069-2070`), and `wedgeKey` delegates to `serverKey` =
`"$ip|${hostname ?: ""}"` (`:3005`, `:3017-3021`). Two rows that share an IPv4 but not a host name
are therefore two distinct identities, and **both would be dialled in a single run**; the
Next-Server continuation and the user-skip filter are identity-keyed for the same reason
(`:893-934`). With **0** duplicate rows in the table the vector cannot fire today — it is a latent
consequence of the storage key, not an active defect. [SOURCE CODE + PHYSICAL DEVICE]

**Decision — audit only, no code change, and an explicit STOP. [SOURCE CODE + INFERENCE]** The
smallest fix is to de-duplicate by IPv4 at candidate-building time — in
`AutoModeEngine.defaultServers()`, keeping the row that has a non-blank `openVpnConfigData` and the
richer protocol data (SSTP port/provenance, SoftEther ports) — which leaves the stored dataset, its
identity and its schema untouched. That file is **not in this block's authorisation list**
(`ConnectionListViewModel.kt`, the stale `SettingsScreen.kt` string, the import validation in
`ManualCsvImport.kt`, locale strings, tests) **and** it is one of the F.29-modified files the block
forbids touching. **Authorisation required before this can be implemented:** edit
`app/src/main/java/vn/unlimit/vpngate/automode/AutoModeEngine.kt` (plus tests), on a tree where the
F.29 work has been committed or stashed by the operator. Nothing in PART A/B changes the stored
identity, so this audit is purely observational.

---

## F. PART D — device verification (dv59)

The committed-tree APK was installed over the existing data (`adb install -r` — never
uninstall/clear) and the import path was driven through the document picker.

| Step | Observed | Result |
|---|---|---|
| Install | `adb install -r` → `Success`; process alive, zero FATAL/ANR/tombstone in the window | PASS |
| Import — extended CSV | `manual_import_test_extended.csv` (2 valid rows + 1 deliberately broken `"not-an-ip"`); accepted **2**, rejected **1** with a named reason | PASS — no crash |
| Import — API CSV | `manual_import_test_api.csv` (2 valid VPN Gate API-format rows); accepted **2**, rejected **0** | PASS — no crash |
| Delete | deleted an imported row; `removeImportedServer` ran on the background thread with no crash | PASS |
| Idempotent re-import | re-importing the same file left the row count stable (no duplicate row) | PASS |
| Survives Refresh | tapped Refresh; list reloaded, imported rows retained, no crash | PASS |
| Survives restart | `am force-stop` + relaunch; imported rows still present; `VPNGateItem` consistent | PASS |
| Cleanup | the three test servers deleted; `manual_import_test_*.csv` and `ui.xml` removed from `/sdcard/Download` | PASS |
| DB final check | byte-exact pull of `vpn_gate_connector` showed `VPNGateItem` contains **0** test rows (all imported test entries gone) | PASS — device restored |

Evidence: `test-evidence/dv59/59_01.png … 59_36.png` (36 screenshots of the picker, the import
summary, the delete, the re-import, the Refresh, the restart, and the final cleanup) and the two
CSV fixtures (`test-evidence/dv59/manual_import_test_extended.csv`,
`test-evidence/dv59/manual_import_test_api.csv`). [PHYSICAL DEVICE]

**Method note.** The device was returned to its pre-test state: the test servers were deleted, the
pushed CSVs were removed, no tunnel was active at start or end, and no device or network setting was
changed (`settings put` / `svc` / uninstall / app-data clear were never used).

### F.1 Second pass — re-driven with byte-exact counters and an empty crash buffer

The first pass left screenshots but **no logcat and no before/after store file**, which is weaker
than the brief asks for. The path was therefore re-driven end to end with the counters read out of
the device after every step. APK: `:app:assembleProDebug` from `git archive HEAD`, **63,827,468 B**,
SHA-256 `73139c01ca316dd23a7755eb8e6ccb15e003c6b0452202c4649eef56b229b96d`, installed with
`adb install -r` (**Success**), never uninstalled or cleared.

| # | Step | Measurement | Result |
|---|---|---|---|
| 1 | Baseline | `VPNGateItem` **261**, test rows **0**, store `[]` | recorded |
| 2 | Import `manual_import_test_extended.csv` (picker) | summary **"Imported 2, rejected 1 — Rejected: UNKNOWN_SOURCE_TAG x2; INVALID_IPV4 x1"**; store **2** rows; table **261 → 263** with exactly `import-a.example`, `import-b.example` | PASS, no crash |
| 3 | Re-import (same content, Import pressed again) | summary re-rendered identically; store file **943 B, mtime unchanged** → tap delivered, write idempotent | PASS, no crash |
| 4 | Delete `import-a.example` | store **1** row; table **262**; one test row left | PASS, no crash |
| 5 | Refresh (list control) | list still **262** servers; store unchanged | PASS, no crash |
| 6 | `am force-stop` + relaunch | list **262** servers → imported row survived the process | PASS, no crash |
| 7 | Delete `import-b.example` (cleanup) | store `[]`; table **261**; test rows **0**; `integrity_check = ok` | restored |
| 8 | Cleanup | pushed CSVs and `ui*.xml` removed from `/sdcard/Download` | restored |

**Crash evidence.** `adb logcat -b crash` was **empty (0 lines)** after steps 2, 3, 4, 5 and 6, and
no `FATAL EXCEPTION`, `ANR` or `Cannot invoke setValue on a background thread` appeared in the main
buffer at any point. This is the specific assertion the P0 fix is for: before it, step 4 died at
`removeImportedServer` and step 2 died at `materializeImported`.

**One incidental reproduction of B2.** Driving the paste field through the IME inserted a space
after punctuation — `import-a. example`, `HostName, IP` — which is precisely the artefact B2 now
rejects. Pasting that text produced a graceful `Import failed: missing required column(s): SourceTag`
(a truncated header) rather than a crash, and no row was written. The file-picker route was then
used for the counted run above. [PHYSICAL DEVICE]

**B1 on the device.** The custom-DNS hint renders as "Use custom DNS when connected to VPN Server
instead of default Google DNS (8.8.8.8/8.8.4.4)." — the stale Block Ads sentence is gone from the
running app, not only from the resources. [PHYSICAL DEVICE]

**D3 — pinned row and fresh-install default.** The pinned row after connecting was captured in the
first pass (`59_31_connecting.png`, `59_32_pinned.png`); it was **not** re-driven in this pass, and
no claim beyond those screenshots is made. The fresh-install default protocol priority stays
**NOT TESTABLE** without clearing app data, which the block forbids — stated, not worked around.
[PHYSICAL DEVICE + UNKNOWN]

---

## G. PART E — committed-tree gate: PASS (10/10)

**Method.** A tree was extracted with `git archive HEAD` into `/d/dv59gate/tree` — outside the
repository, and therefore without the F.29 working-tree changes, without `vpngate_collector.py` and
without any uncommitted file. `local.properties` was copied in for the SDK path. Every run executed
`./gradlew --no-daemon --rerun-tasks :app:testProDebugUnitTest :SoftEtherClient:testDebugUnitTest`
with `CI=true` and the project's JDK 21, and every run re-executed the test tasks from scratch
(`--rerun-tasks`), so a cached result can never be counted. Counts were read from the JUnit XML.

| Run | Result | Duration | `:app` (t/f/e/s) | `:SoftEtherClient` (t/f/e/s) | Suites | Infra retries |
|---|---|---|---|---|---|---|
| 1 | **PASS** | 182 s | 1092 / 0 / 0 / 1 | 184 / 0 / 0 / 0 | 89 + 13 | 0 |
| 2 | **PASS** | 179 s | 1092 / 0 / 0 / 1 | 184 / 0 / 0 / 0 | 89 + 13 | 0 |
| 3 | **PASS** | 182 s | 1092 / 0 / 0 / 1 | 184 / 0 / 0 / 0 | 89 + 13 | 0 |
| 4 | **PASS** | 179 s | 1092 / 0 / 0 / 1 | 184 / 0 / 0 / 0 | 89 + 13 | 0 |
| 5 | **PASS** | 182 s | 1092 / 0 / 0 / 1 | 184 / 0 / 0 / 0 | 89 + 13 | 0 |
| 6 | **PASS** | 178 s | 1092 / 0 / 0 / 1 | 184 / 0 / 0 / 0 | 89 + 13 | 0 |
| 7 | **PASS** | 179 s | 1092 / 0 / 0 / 1 | 184 / 0 / 0 / 0 | 89 + 13 | 0 |
| 8 | **PASS** | 178 s | 1092 / 0 / 0 / 1 | 184 / 0 / 0 / 0 | 89 + 13 | 0 |
| 9 | **PASS** | 177 s | 1092 / 0 / 0 / 1 | 184 / 0 / 0 / 0 | 89 + 13 | 0 |
| 10 | **PASS** | 178 s | 1092 / 0 / 0 / 1 | 184 / 0 / 0 / 0 | 89 + 13 | 0 |

**Ten consecutive green runs, 1276 tests each, 0 failures, 0 errors, 1 skipped** (the pre-existing
skip in `:app`). Runs 1–3 are the **three consecutive** committed-tree runs the brief also requires.
No flaky test surfaced in this sequence: the two recently fixed tests
(`pressWhileConnectedDisconnects`, `twoImmediateSkipsNeverDialTheSameServerTwice`) and
`ReachabilityGateTest` were green in all ten. Evidence: TEST (`/d/dv59gate/gate3.log`, one
`g3_run<N>.log` per run).

**The `BindException` — reported, not hidden.** Two earlier attempts at this gate were aborted by
the environment, not by a test: a first harness stopped on run 3 and a second on run 2, each with
`Gradle could not start your build … java.net.BindException: Address already in use: bind` after
1–2 s and **zero tests executed** (no test task ever started). Both are preserved
(`/d/dv59gate/run3.log`, `/d/dv59gate/g2_run2.log`). The block allows one retry per run; the final
harness decides "infra" from the Gradle log rather than from leftover result files, retries it, and
does not count it as a run. **In the ten counted runs above, zero retries were needed** — the gate
was re-run from scratch on a quiet machine, so the ten results are ten real executions.

---

## H. Push decision

All three conditions of the brief are met:

| Condition | Result |
|---|---|
| Ten consecutive full committed-tree runs green | **YES** — 10/10, 1276 tests each, 0 failures |
| No crash/ANR on device in D1–D2 | **YES** — crash buffer empty after import, re-import, delete, refresh and restart |
| `git status --short` identical to the start | **YES, except the two documentation files this block is authorised to add** (`PROJECT_SPECIFICATION.md`, this report). No F.29 file, no `vpngate_collector.py`, no `D1_CANDIDATES.md` touched — the diff of the status listing is exactly those two lines |

**Note on `origin/main`.** The v1.0.59 **source** (`95163f5`, the PART A+B fix) was already on the
remote when this block started: `origin/main == HEAD == 0fd4875`. The commit made here is
documentation only (specification + this report), so the push moves `origin/main` by that
documentation commit alone. It is a fast-forward, with no `--force` and no tag.

**Post-push gate.** After the push, one full run is executed from a **fresh** `git archive
origin/main` extraction, so the published tree — not the local one — is what gets tested. Result
recorded in §H.1.

### H.1 Post-push standalone gate

**Pushed.** `0fd4875..e40df8e main -> main`, fast-forward, `origin/main == HEAD`, **no `--force`**,
**no tag** (5 tags before, 5 after).

**Standalone gate from the published tree.** A **fresh** extraction was made with
`git archive origin/main` into `/d/dv59gate/postpush` — a directory that never saw the working tree,
so nothing uncommitted and no F.29 file could leak in — and the full suites were run once with
`--no-daemon --rerun-tasks`:

| Target | Result | Counts |
|---|---|---|
| `:app:testProDebugUnitTest` | **PASS** | **1092 / 0 / 0 / 1** across **89** suites |
| `:SoftEtherClient:testDebugUnitTest` | **PASS** | **184 / 0 / 0 / 0** across **13** suites |
| Build | **BUILD SUCCESSFUL in 3m 17s** | 86 tasks executed |

The published commit therefore reproduces the gate's counts exactly. Evidence: TEST
(`/d/dv59gate/postpush.log`).

**One documentation commit follows this report.** The results above are written back into this
section and the specification in a final docs-only commit, pushed fast-forward on top of `e40df8e`.
That commit touches no source, test, resource, Gradle or manifest file, so it does not require its
own gate; the code that was gated is exactly the code that is published.

---

## I. What this block does **not** claim

- No claim that PART C's duplicate-IP characteristic was changed — it was audited only.
- No claim that any feature beyond the import path was re-verified on device: V3/V4/V5/V6/V7/V8/V9/
  V10 from earlier blocks are carried over from §1.6.56/§1.6.57 and were not re-driven here.
- No claim that the import path is exercised on any device other than `5585fecc`.
- No schema, migration, Gradle, manifest, or resource change beyond the three `strings.xml` edits
  (B1) and the two `strings.xml` edits are purely textual.
- No tag was created; the push (if made) is fast-forward, no `--force`.
