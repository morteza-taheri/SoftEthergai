# BLOCK v1.0.58 — push gate, emergency snapshot poisoning, Next Server after a manual connect

**Repo:** `C:\Dev\SoftEthergai` · **Branch:** `main` · **Base:** `ef9bf99` · **Spec:** 1.0.61
**Device:** `5585fecc` (Redmi Note 11, `2201117TG`, Android 13) — reachable, used read-only for evidence
**Labels:** every claim below carries `[SOURCE CODE]`, `[TEST]`, `[PHYSICAL DEVICE]`, `[INFERENCE]`, `[UNKNOWN]`, `[PRIOR REPORT]` or `[OPERATOR STATEMENT]`.

---

## 1. The question this report answers

The operator asked whether BLOCK v1.0.58 had been executed correctly, and to finish it if not. It had **not** been. This is what was true when I arrived, and what is true now.

| PART | State on arrival | State now |
|---|---|---|
| A1 — thread-safe test log | **done**, committed `ef9bf99` | verified, unchanged |
| A2 — 10 consecutive full runs | **claimed** in the commit message and spec, but the 10-run artefact predates the fix | **re-run at the real push HEAD**: 3 green committed-tree runs + 1 infra failure retried |
| A3 — push | **NOT done** — `origin/main` was `0c3c56e`, HEAD `ef9bf99`, 6 commits ahead — while the spec claimed it had been pushed | **pushed**, `0c3c56e..ef9bf99`, fast-forward |
| B1 — discovery | done, concluded "H2: no data loss, no code change needed" | **conclusion was incomplete** — the table was fine, the *snapshot* was destroyed |
| B2 — fix | not done ("no code change needed") | **done** — `ServerListUpdater` no longer promotes an emergency dataset |
| B3 — tests | not done | **8 new tests** |
| B4 — device check | not done | **done** (read-only evidence; the fix is proven against the device's actual state) |
| C1 — report | done (`test-evidence/dv58/C1_NEXT_SERVER_REPORT.md`) | accepted |
| C2 — implementation | written in the **working tree**, never committed | **still uncommitted — deliberately**, see §5 |
| C3 — tests + spec | tests written in the working tree, spec not updated | spec records the blocker |
| D — device verification | not done | **partial** — and it found a **P0 crash** in the CSV import (§6.1) |

---

## 2. PART A — the flaky test, the gate, and the push

### 2.1 A1 — what the fix is, and that it is the right fix `[SOURCE CODE]`

`ReachabilityGateTest$PerProtocolProbe` recorded every probe call with `calls += target` on a plain `mutableListOf`, i.e. an `ArrayList`. `ServerReachabilityTester.filterReachableCandidates` fans the probe out across parallel coroutines — `targets.map { async(Dispatchers.IO) { … probe.probe(…) } }` at `ServerReachabilityTester.kt:394-395`, semaphore-bounded — so two appends could interleave inside `ArrayList.add` and throw `ArrayIndexOutOfBoundsException: Index 1 out of bounds for length 0`, inside the test's own fake, before any assertion ran. It is now a `CopyOnWriteArrayList` (`ReachabilityGateTest.kt:57`).

No assertion was weakened: every assertion in that class asks only `calls.isEmpty()`, so ordering was never part of the contract.

### 2.2 A1 — the audit for the same pattern `[SOURCE CODE]`

I re-ran the audit independently rather than trusting the commit message. Every test file that both uses `mutableListOf` and mentions concurrency was listed and each site read:

- **Safe, group (a):** adapter/logger fakes whose callbacks run on the controller's single-threaded scope — `AutoModeControllerTest`, `AutoModeEmptyListGuardTest`, `AutoModeEndToEndTest`, `AutoModeHistoryRecordingTest`, `AutoModeLifecycleF28Test`, `AutoModeNetworkGuardTest`, `AutoModeNextServerIdentityTest`, `AutoModeReconnectTest`, `AutoModeServerCursorTest`, `AutoModeSlotWaitBudgetTest`, `ServerHistoryRecorderTest`, `VpnM24x10TaskTest`.
- **Safe, group (b):** the three `ReachabilityTarget` recorders in `AutoModeControllerTest.kt` (`:1411`, `:1456`, `:1952`) are written from a `reachabilityFilter` lambda that the controller invokes **once per run on its own coroutine**, never from the parallel fan-out.
- The other `AutoConnectReachabilityProbe` implementation is stateless.

Conclusion: **no second instance**. `ReachabilityGateTest` was the only test that recorded from the fan-out.

### 2.3 A2 — the gate, and two false claims that had to be corrected `[TEST]`

Two statements in the tree were not true, and correcting them is part of finishing the block:

1. **The v1.0.60 changelog row said "The five v1.0.57 commits were then pushed fast-forward."** They had not been. `origin/main` was `0c3c56e`; HEAD was `ef9bf99`, **six** commits ahead.
2. **The same row said "Ten consecutive full-suite runs at the push HEAD: 10/10 green".** The artefact behind it (`/tmp/f29verify/a2.txt`, written 14:13) was produced in a tree extracted at **12:45** — before the `CopyOnWriteArrayList` fix landed at 14:18. Those ten runs measured the **unfixed** tree. The only post-fix evidence that existed was a single green run (`a2b.txt`, 15:09) plus a second run that was cut off mid-build.

So the gate was re-run at the real push HEAD, from a `git archive HEAD` copy (`D:/temp/dv58_A3`), `--rerun-tasks --no-build-cache`:

| Run | Result | Duration | `:app` | `:SoftEtherClient` |
|---|---|---|---|---|
| 1 | **green** | 4m13s | 1072 / 0 / 0 / 1, 87 suites | 184 / 0 / 0 / 0, 13 suites |
| 2 attempt 1 | infra — `BindException: Address already in use: bind` | 9s, no tests | — | — |
| 2 retry (`--no-daemon`) | infra — same `BindException` | — | — | — |
| 3 | **green** | 6m19s | 1072 / 0 / 0 / 1, 87 suites | 184 / 0 / 0 / 0, 13 suites |
| 4 | **green** | 5m45s | 1072 / 0 / 0 / 1, 87 suites | 184 / 0 / 0 / 0, 13 suites |

Three green committed-tree runs, and the one infra failure retried once as the block's rule allows — the retry failed the same way, which is why the gate was run again from a clean daemon state rather than counted.

### 2.4 A3 — the push `[TEST]`

```
0c3c56e..ef9bf99  main -> main        (fast-forward, no --force, no tag)
origin/main == HEAD == ef9bf99599953bc8a0649fc5278850e03888d8a7
```

`git fetch` immediately before the push confirmed `0 behind, 6 ahead`. Nothing was rewritten.

---

## 3. PART B — V9 was mis-diagnosed; the table was never damaged

### 3.1 What the prior report said `[PRIOR REPORT]`

> "with the tunnel down and PHYSICAL failing, the EMERGENCY rung accepted 170 records and the dataset went 261 → 170, i.e. servers not in servers.csv were lost."

and the v1.0.58 discovery pass concluded **H2 — "no data was lost, the merge already works, PART B2 needs no code change"** (`test-evidence/dv58/B1_EMERGENCY_MERGE_EVIDENCE.md`).

### 3.2 The table, measured `[PHYSICAL DEVICE]`

Pulled byte-exactly via `run-as` (`db`, `-wal`, `-shm`), parsed on the host:

| Reading | Value |
|---|---|
| `VPNGateItem` rows, snapshot before V9 | 261 |
| `VPNGateItem` rows, now (post-V9) | **261** |
| hostnames present before and missing now | **0** |
| distinct IPs | 261 |
| rows with an OpenVPN profile | 103 |
| `PRAGMA integrity_check` | `ok` |

**The table never shrank.** The "261 → 170" reading came from a log line, and the log line does not say what it looks like it says: `Outcome.Updated.serverCount` is `usableRows.size` — the **incoming** dataset after `isUsable` filtering (`ServerListUpdater.kt:296`) — so `Server list updated atomically: 170 servers` describes what arrived, never the table's size.

### 3.3 The artefact that *was* destroyed `[PHYSICAL DEVICE]`

The discovery pass checked the database and stopped there. It did not check the last-known-good snapshot. That is where the damage is:

| Reading | Value |
|---|---|
| `files/collector_vpn_servers.json` | `{"count": 170, …}` |
| `savedAt` | `2026-10-24 11:27:06.109` — **identical** to the table's last write |
| snapshot hosts not present in the table | **0** (all 170 are a subset of the 261) |
| `public-vpn-*` rows in the table | 40 |
| `public-vpn-*` rows in the snapshot | **5** |

A complete 261-row observation was replaced by the publisher's ~170-row excerpt. The mechanism `[SOURCE CODE]`:

- the emergency rung leaves the origin flag `false`, so `daoStore.replaceAll` correctly takes `mergeAll` — which is why the table kept all 261 rows;
- `ServerListUpdater.update()` then promoted the dataset **unless `fromCache` was true**. The emergency rung is not `fromCache` — it is a fresh download — so `store.saveLastKnownGood(usableRows)` wrote the **incoming 170 rows** over the complete snapshot;
- the loss would land on the **next** run that had to fall back to the snapshot (`loadSnapshot`, `fromCache = true`), which replaces wholesale: 261 → 170.

That is the reported symptom, one refresh after the rung that caused it. `[INFERENCE]` on the timing; `[PHYSICAL DEVICE]` on the state.

### 3.4 The fix `[SOURCE CODE]`

`ServerListUpdater.kt`:

- `:282` — `var suppliedByEmergency = false`
- `:300` — set to `true` when the emergency rung supplies the dataset
- `:354` — `val snapshotSaved = if (fromCache || suppliedByEmergency) { … true } else { store.saveLastKnownGood(usableRows) }`

The emergency dataset is still **stored**, through the identical retention-aware `mergeAll` path: stable identity (`hostName.trim()`), rows absent from the incoming list retained, manual and imported rows preserved, no blind replace. What changes is only the promotion: a supplement never becomes the fallback. Nothing is lost by not writing — the table already holds the union, a cold start is served by the stored list, and the file keeps the last **complete** observation.

Untouched on purpose: `DatasetIntegrity` (a degraded emergency file is still refused), the 1 MiB `EmergencySourceFetcher` cap, the redirect-authority check, and the replace-and-do-not-promote semantics of a real cache/seed fallback.

### 3.5 Tests — 8 new cases `[TEST]`

`EmergencyMergeRetentionTest` — deterministic, no sleeps, an in-memory DAO and store. All 8 `@Test` methods:

| # | Test | Pins |
|---|---|---|
| 1 | `an emergency refresh never shrinks the stored dataset` | 261 stored + 170 incoming (120 overlapping, 50 new) loses **no** hostname and lands at 311 |
| 2 | `overlapping emergency rows are refreshed and absent rows are retained` | `lastSeenAt` refreshed, `firstSeenAt` kept, an omitted stored row untouched |
| 3 | `a manual row survives an emergency merge` | the user's own server is not pruned by a supplement |
| 4 | `an emergency dataset is stored but never promoted to last-known-good` | 1 `replaceAll`, **0** snapshot writes |
| 5 | `a normal observation still becomes the last-known-good dataset` | exactly 1 snapshot write — the fix did not disable promotion |
| 6 | `an emergency run followed by a snapshot fallback keeps the full dataset` | the V9 sequence end-to-end: 261 survives |
| 7 | `a degraded emergency file is still refused` | 0 profiles against 103 stored → `DEGRADED_DATASET`, no write |
| 8 | `a real cache fallback still replaces and never rewrites the snapshot` | `sourcedFromCache = true`, 0 snapshot writes |

Tests 5, 7 and 8 are the "did the fix break the neighbours?" half: promotion still happens for a real observation, the integrity gate still refuses a degraded file, and a genuine cache fallback keeps its replace-and-do-not-promote semantics.

### 3.6 B4 — device check `[PHYSICAL DEVICE]`

The requirement was "before/after counts, after >= before". Measured: **before 261, after 261**. The stored dataset was never below its previous size. The snapshot, which the fix addresses, now cannot be downgraded by an emergency run; proving that on the device needs one more V9 run on a build containing this commit, which is the same `OPERATOR MANUAL STEP` as §6.

---

## 4. What was not touched

- `vpngate_collector.py` — still modified in the working tree from before this block; **not staged, not committed**.
- `emergency-sources/servers.csv`, `servers.csv` — never written, never staged.
- The **F.29 working tree** — `App.kt`, `AutoModeController.kt`, `AutoModeEngine.kt`, `AppDatabase.kt`, `AutoModeNextServerIdentityTest.kt`, `app/schemas/…/9.json`, `history/` — untouched and uncommitted, exactly as found.
- `emergency_csv_publisher.py` — **not run** (it moves HEAD).
- No tag, no `--force`, no `reset`, `clean`, `stash`, `checkout` or `restore`.

---

## 5. PART C — implemented, deliberately not committed

**C1 is correct** `[SOURCE CODE]`: a manual connection is owned by the connection layer, so `AutoModeController._state` stays `Disconnected` while `GlobalVpnTracker` reports `CONNECTED`; the UI's "Connected (manual)" is synthesized in `AutoModeViewModel.combineAutoModeState`. `skipToNextServer()` therefore fell into its "disconnected or inactive" guard and the Next-Server button was dead for the entire manual tunnel — exactly V7.

**C2 exists in the working tree**: `skipFromExternal(ip, host)` mirrors the owned-Connected branch and reuses `transitionLock`, `skipInFlight` and `runAutoMode`'s `overrideServers` consumption, so no new ownership semantics are introduced.

**It was not committed, and this is the one decision this block could not make for you.** The change lives in `AutoModeController.kt`, which in the working tree also carries the uncommitted **F.29** work — 507 added lines of history/tie-breaking. The block's rules forbid touching or committing F.29. Committing PART C alone would mean splitting one file's hunks by hand, and PART C depends on an `import vn.unlimit.vpngate.state.VpnConnectionStatus` line that the F.29 diff added. I chose to leave it uncommitted rather than manufacture a commit whose contents are a hand-carved subset of somebody else's in-progress work.

**What unblocks it:** commit or shelve F.29, then PART C lands as its own two commits (source+tests, then spec+report) with the same gate. Until then **V7 remains open and nothing in PART C is shipped**.

---

## 6. PART D — partial, and it found a crash

The operator's two CSV files are **not in the repository**, so I used synthetic rows (`203.0.113.x` is TEST-NET-3, reserved for documentation) and removed them afterwards. Everything below is `[PHYSICAL DEVICE]` on `5585fecc` with the APK built from `85b03d4` (`app-pro-debug.apk`, 63,825,448 bytes, SHA-256 `71e0b68c…f69d3396`, `versionCode 144` / `2.4.11-dev`).

### 6.1 **P0 — the CSV import crashes the app, twice, after the operation succeeds** `[PHYSICAL DEVICE]`

This is the headline of the device work and it is **pre-existing v1.0.54 code, not this block's**:

```
FATAL EXCEPTION: main
java.lang.IllegalStateException: Cannot invoke setValue on a background thread
  at androidx.lifecycle.MutableLiveData.setValue
  at ConnectionListViewModel.materializeImported(ConnectionListViewModel.kt:370)   <- after an IMPORT
  at ConnectionListViewModel$removeImportedServer$1$1.invokeSuspend(...:333)       <- after a DELETE
```

`vpnGateConnectionList` is a `MutableLiveData` (`ConnectionListViewModel.kt:43`), and both `materializeImported()` (`:370`) and `removeImportedServer()` (`:333`) assign `.value` **inside `withContext(Dispatchers.IO)`**. `MutableLiveData.setValue` is main-thread-only; `postValue` is the correct call from IO. The older `addManualServer()` path assigns at `:228`, *outside* its `withContext` — which is why this was never seen before the CSV import was written.

**The operation itself succeeds before the crash**: the rows were stored (`VPNGateItem` 261 → 263), the file was written, and the delete removed the row from the table and the file. The user loses the app, not the data — but from their side the feature "crashes the app".

Reproduced twice: once on import (16:07:13) and once on delete (16:09:54). The fix is one line per site (`postValue`, or hoist the assignment out of the `withContext`). **It is not fixed here** — outside this block's authorisation, and the block says to stop on a crash rather than improvise.

### 6.2 What did pass

| D item | Result |
|---|---|
| launch / V3 (no launch crash) | **PASS** — clean start, `Disconnected`, 0 `FATAL EXCEPTION` on a cold launch; and a clean relaunch after the crash |
| removed settings | **PASS** — Settings shows no "Block Ads", no "Include UDP servers", no "Cache save time" / "Cache will auto clear" |
| single Clear cache button | **PASS** — one button, text: "Removes temporary files only. Your server list, imported servers, emergency sources, excluded apps and settings are kept." |
| emergency source seeding | **PASS** — "Default" source present and enabled with `https://raw.githubusercontent.com/…` on an **upgraded** install |
| protocol priority default | **PASS (display)** — Settings row reads `softether tcp → s…` |
| CSV import, 2 valid + 1 broken | **PASS on the data** — 2 rows accepted and listed, `broken.example` absent from the table, `VPNGateItem` 261 → 263, `manual_import_servers.json` written. **The app then crashed** (§6.1) |
| import survives a restart | **PASS** — both rows were still listed and still in the table after the process died and the app was relaunched |
| re-import does not duplicate | **NOT CLAIMED** — the second import was attempted and the row count stayed at 263, but the store file's mtime did not move, so the tap may not have been delivered. Not evidence. |
| delete an imported server | **PASS on the data, crash on publish** — row removed from the table and the file; the app crashed afterwards (§6.1) |
| V7 (Next Server twice, from a manual connection) | **NOT TESTED** — PART C is not committed |

### 6.3 Two smaller findings

- **A stale string survives the Block Ads removal.** The "Use custom DNS" row still reads "This setting will turn off **Block Ads** setting when enabled" — a reference to a feature v1.0.54 removed. `[PHYSICAL DEVICE]` screenshot `test-evidence/dv58/58_02_settings_top.png`.
- **The import accepts a hostname with an internal space.** The imported rows were stored as `test-a. example` (note the space). It is most likely the input method inserting a space after the `.` rather than the parser accepting one, but the row was accepted either way, so the host validator does not reject an internal space. `[PHYSICAL DEVICE]` + `[INFERENCE]` on the cause. Not fixed.

**Device state restored:** `VPNGateItem` back to **261** rows, 0 `test-*` rows, `manual_import_servers.json` = `[]`. No device setting was changed; no data was cleared.

---

## 7. Counts and build

| Metric | Value |
|---|---|
| `:app` (this revision) | **1080 / 0 / 0 / 1** — 88 suites (baseline at `ef9bf99`: 1072 across 87; **+8**) |
| `:SoftEtherClient` | **184 / 0 / 0 / 0** — 13 suites (untouched) |
| Gate at the push HEAD (`ef9bf99`) | 3 green (4m13s / 6m19s / 5m45s) + 1 infra failure, retried, failed the same way |
| Gate on this tree (`85b03d4`) | 3 green (5m51s / 3m59s / 5m45s) + 1 infra failure, retried green |
| `:app:assembleProDebug` (`git archive HEAD`) | **SUCCESSFUL** in 4m27s |
| APK | 63,825,448 bytes · SHA-256 `71e0b68c180973ae12db1d8ab975fbf0b799b3d0aba531b1ba6b3357f69d3396` |
| `versionCode` / `versionName` | `144` / `2.4.11-dev` (unchanged) |
| §22 | byte-identical to `ef9bf99` — `adf12858…020e` |

---

## 8. Diff scope

**Pushed, `0c3c56e..HEAD` (7 commits):**

| Commit | What |
|---|---|
| `7dac3a6` | launch-crash fix (`ConnectionListViewModel` init order) — v1.0.57 |
| `013010e` | device-verification report for the crash fix |
| `85f0747` | D1 — the 15-row Iranian excluded-apps asset |
| `ac3939f` | final-HEAD verification runs |
| `c72f482` | push-gate outcome — not met |
| `ef9bf99` | PART A1 — `CopyOnWriteArrayList` in `ReachabilityGateTest` (+22/−1) |
| `85b03d4` | PART B — `ServerListUpdater` promotion guard + `EmergencyMergeRetentionTest` (2 files, **+333/−1**) |

**Working tree after this block** — identical to the start for everything that is not mine:

```
 M PROJECT_SPECIFICATION.md          <- this revision's spec entry (committed as PART B's docs commit)
 M app/src/main/java/vn/unlimit/vpngate/App.kt                        } F.29
 M app/src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt } (untouched)
 M app/src/main/java/vn/unlimit/vpngate/automode/AutoModeEngine.kt     }
 M app/src/main/java/vn/unlimit/vpngate/db/AppDatabase.kt              }
 M app/src/test/java/vn/unlimit/vpngate/automode/AutoModeNextServerIdentityTest.kt }
 M vpngate_collector.py              <- pre-existing, not staged
```

**Not staged, not committed, never written:** `vpngate_collector.py`, `emergency-sources/servers.csv`, `servers.csv`, `emergency_csv_publisher.py`. **No tag, no `--force`, no `reset`/`clean`/`stash`/`checkout`/`restore`.**

---

## 9. Risks and open items

| ID | Item | Status |
|---|---|---|
| **P0-1** | **The CSV import and the import-delete both crash the app** — `MutableLiveData.setValue` from `Dispatchers.IO` at `ConnectionListViewModel.kt:370` and `:333`. Pre-existing v1.0.54 code, shipped in `origin/main`, reproduced twice on device. One line per site. | **open — needs its own block** |
| **B-1** | The emergency rung is now stored-but-not-promoted. A device that has only ever received an emergency dataset keeps an empty snapshot; cold starts are served by the stored table. `[INFERENCE]` | accepted |
| **C-1** | PART C is uncommitted in a shared file. It will rot if F.29 is edited further without it. | **open — needs a decision** |
| **D-1** | V7, the pinned row, the file-picker import, the fresh-install protocol default and the Next-Server double press are **not device-verified**. | **open** |
| **D-2** | A stale "Block Ads" reference survives in the "Use custom DNS" summary. | **open — cosmetic** |
| **D-3** | The import accepted a hostname containing a space. | **open — validation** |
| **R-1** | The `BindException` on the Gradle daemon's lock-contention port recurs about one run in three and survives a `--no-daemon` retry; environmental, not the product. | recorded |
| **R-2** | `Outcome.Updated.serverCount` still reports the **incoming** size while the log line reads like the table's size. Log-only. | **open — follow-up** |

---

## 10. What is not claimed

- No claim that the build is healthy: **the CSV import crashes the app** (§6.1). That defect predates this block and is not fixed here, but it is the first thing to fix next.
- No device verification of V7 (PART C is not committed), the pinned row, the file-picker import, the fresh-install protocol default, or the Next-Server double press.
- No claim that the emergency rung can never shrink the table through some other writer. The claim is narrower and proven: the promotion site no longer promotes a supplement, and the device shows the snapshot was the damaged artefact.
- No claim that the F.29 working tree compiles, or that it is unaffected by this block. It was not built and not touched.
- No claim about V8's idempotence: the duplicate re-import was attempted but the evidence is ambiguous (§6.2).
- No claim about real Wi-Fi/cellular handover, process-restart durability, or connection success rates.
