# BLOCK v1.0.61 — F.29 SPLIT COMMITS, NEXT-SERVER-AFTER-MANUAL, ONE-CANDIDATE-PER-IPv4

**Device:** `5585fecc` (2201117TG, Android 13) · **Package:** `vn.unlimit.vpngatepro` ·
**APK:** `05d7091918ebd918a1051cae917bde69dd15a4122139f307ed0a712ec5fddf69`

**Evidence labels.** `[SOURCE CODE]` read from the tree · `[TEST]` executed on the JVM ·
`[PHYSICAL DEVICE]` observed on the phone · `[INFERENCE]` derived · `[PRIOR REPORT]` from an
earlier block · `[UNKNOWN]` not established.

---

## A. Outcome

Eight commits carried the F.29 working tree and the two follow-ups the v1.0.60 triage proved
independent. Every behaviour rule the block asked for is implemented and tested, the ten-run gate is
**10/10 green at 1,393 tests**, the Room 8→9 migration **ran on the real device** against a database
holding 261 existing servers, and two real Auto Connect runs **wrote history rows** that the
pre-migration database did not have.

Three things did not go to plan and are recorded rather than smoothed over:

1. **Commit `303baaf` does not compile on its own** (§C). The defect is in the split, not the code.
2. **`acb1a39` was amended once** because its first version restarted the manual continuation from
   the top of the list — the exact behaviour the operator forbade (§E).
3. **The manual-connect device check could not be driven** past a system permission dialog (§I).

---

## B. Repository state

| Item | Start | End |
|---|---|---|
| HEAD | `b66e6d6` | `5ffce2d` + the report commit |
| `origin/main` | `b66e6d6` | unchanged until the push |
| Tags | 5 | **5** (none created) |
| `git status --short` | 31 lines | 17 lines — **only the pre-existing untracked reports and evidence** |

Every commit was staged deterministically (whole paths, or `git hash-object -w` +
`git update-index --cacheinfo` for the partial-file splits); **no `git add -p`**, no
`reset`/`clean`/`stash`/`checkout`/`restore`, no tag. `vpngate_collector.py` was staged only in its
own commit. [SOURCE CODE + git]

---

## C. PART A — the split, and one commit that does not build

The F.29-only controller was built by deleting the three PART C blocks from a **copy** of the dirty
file (lines 23, 867–880, 1006–1072 = 82 lines) and verified arithmetically: `HEAD → F.29-only` is
**+422/−3**, `HEAD → wiring` **+33/−0**, `wiring → F.29-only` **+389/−3**, and the dirty file minus
F.29-only is exactly **+82/−0**. The `\r` at EOF that this file has always carried was preserved
byte-for-byte, so the first attempt's spurious "one line changed at EOF" disappeared.

| Commit | Content | `git archive <commit>` compile |
|---|---|---|
| `049c7e3` | data layer: `history/` (6 files), `AppDatabase.kt`, `9.json` — 1,621 insertions | **PASS** 2m38s |
| `303baaf` | wiring: `App.kt`, `AutoModeEngine.kt`, controller constructor params (+33) | **FAIL** — six `Unresolved reference` |
| `2639a42` | recording + equal-quality ties: controller +389/−3, `AutoModeHistory.kt` | **PASS** 2m32s |
| `4b04cd7` | 98 tests + `F29_PHASE2_IMPLEMENTATION_REPORT.md` — 2,503 insertions | **PASS** 2m36s (main + test sources) |

**Why `303baaf` fails, exactly.** The three constructor parameters name `ServerHistoryView`,
`ServerHistorySink` and `NetworkType`; their imports are in the seven-line import hunk that landed in
`2639a42`. Errors: `AutoModeController.kt:198 Unresolved reference 'ServerHistoryView'`, `:207
'ServerHistorySink'`, `:216 'NetworkType'` (×2), plus two "cannot infer type parameter" follow-ons.
The remedy is to move that import hunk into `303baaf`; doing so here would need a `reset` or
`rebase`, both forbidden by the block, so it is documented instead. [TEST]

**Three full runs on the final F.29 commit `4b04cd7`** (fresh archive on D:): **3/3 green**,
`:app` **1190/0/0/1** across 95 suites, `:SoftEtherClient` **184/0/0/0** across 13 — 7m05s, then two
more after one `BindException` was retried. 1,190 = the 1,092 baseline + the 98 F.29 cases, which
is the arithmetic proof that PART C's two tests were still uncommitted at that point. [TEST]

After the four commits the working tree held exactly the pre-existing untracked files plus the PART
C hunks and the collector — as the block required.

---

## D. PART B — the read is bounded

`loadHistory()` now wraps the provider in `withTimeoutOrNull(HISTORY_READ_TIMEOUT_MS)` (1,500 ms) and
degrades to an empty map, logging once. The bound sits at the **call site**, not inside the
production lambda in `AutoModeEngine`: that is the single choke point every provider passes through,
and the only place a test can exercise it. 1,500 ms is chosen to sit under the 2,000 ms write bound
and far under the 8 s per-attempt budget. The blocking Room query is not interruptible — the
coroutine stops *waiting* at the suspension boundary, so the run proceeds on time even if the query
returns later. [SOURCE CODE]

`HistoryReadBoundTest` — **4/4 pass**: a hanging read is abandoned at the bound (asserts the run
waits ≥1,400 ms and <6,000 ms, then connects), a throwing read leaves the run on the quality order,
a fast read still breaks the equal-quality tie, and `stop()` during the read returns without
dialling. Commit `9d50166`.

---

## E. PART C — Next Server after a manual connect (amended once)

The guard now reads the live identity from `GlobalVpnTracker` when the controller's own state is
`Disconnected`, and the continuation is the server **after** the manual one, carried by
`resumeFromServerKey` (`ip|hostname`) and resolved against the list the skip re-sorts. The skip sorts
**without** history, so the successor is the next stored server rather than whatever the tie-break
would have promoted. Unknown identity, or a server no longer in the list, still starts at the top.

**The first version was wrong and the tests said so.** It filtered the manual server out and
restarted from the top of the remainder; with eight servers and a manual connect to the fifth, the
next dial was `s1`, not `s6`. Four of the five new tests failed with exactly that symptom
(`expected (s6, SOFTETHER_UDP) but was (s1, SOFTETHER_UDP)`), the commit was amended, and all seven
now pass. Commit `acb1a39`.

`AutoModeNextServerIdentityTest` — **11/11 pass**: the 4 pre-existing identity cases, the 2 that
shipped in the v1.0.59 working tree, and the 5 new ones (fifth → sixth; sixth exhausted → seventh;
a re-sort between presses; connected server absent from the list; a history that *would* reorder a
normal run leaving the continuation on `s4`). [TEST]

---

## F. PART D — one candidate per IPv4

`dedupeByIp` keeps one candidate per address by a total order: a usable OpenVPN profile while
OpenVPN is among the enabled protocols, then the richer verified transport set, then the higher
score, then the lexicographically smaller host name. The survivor takes the position of the first
row with that address; rows without an address are all kept. The stored dataset, its identity and
its schema are untouched — only the candidate list is. `openVpnEnabled` is derived from the user's
protocol priority at the call site in `AutoModeEngine`. Commit `afa580b`.

`AutoModeEngineDedupeTest` — **8/8 pass**: config-vs-richer in both OpenVPN states, score then
hostname fallbacks, three rows, a clean list, position, empty addresses, and order-independence.
[TEST]

---

## G. PART E — the collector

`vpngate_collector.py` staged **alone**; the diff was verified as exactly **+4/−2** before staging
(doc line, a two-line comment, and `OUT_CSV = "collector_servers_full.csv"`). Commit `51fe1d2`.

---

## H. PART F2 — the gate and lint

Ten consecutive runs from a fresh `git archive HEAD` (`5ffce2d`) on D:, `--no-daemon --rerun-tasks`,
`CI=true`:

| Run | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
|---|---|---|---|---|---|---|---|---|---|---|
| Result | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS |
| Seconds | 227 | 307 | 292 | 284 | 287 | 290 | 304 | 333 | 386 | 407 |
| Infra retries | 0 | 0 | **2** | 0 | 0 | 0 | 0 | **1** | 0 | 0 |

Every run: `:app` **1209/0/0/1** across **97 suites**, `:SoftEtherClient` **184/0/0/0** across
**13** → **1,393 tests, 0 failures, 0 errors, 1 skip**. 1,209 = 1,092 baseline + 98 F.29 + 2 PART C
+ 5 new PART C + 4 PART B + 8 PART D. Three `BindException` startup failures (4–5 s, **zero tests
executed**) were retried; the retries are counted per run above and none of them is a test failure.
**Lint: 104 errors / 178 warnings / 9 hints — the known backlog, unchanged** (`lintProDebug` aborts
the build on those errors; `assembleProDebug` had already produced the APK). [TEST]

---

## I. PART F3 — device verification

**Backup first.** `db` + `-wal` + `-shm` pulled byte-exactly through `run-as` + base64 to
`C:/Dev/dv61/devdb/` before anything was installed: **1,191,936 / 1,165,992 / 32,768 B**, matching
the on-device sizes. The real database was never opened for writing.

**Install.** `adb install -r` → **Success**; never uninstalled, never cleared. Launch: process alive,
**crash buffer empty**.

| Check | Result |
|---|---|
| Real v8→v9 migration | **PASS** — `user_version` **8 → 9**, `integrity_check = ok`, `room_master_table` identity `6f1d1b74…` → **`6ba9665e…`** |
| Row counts after migration | **PASS** — `VPNGateItem` **261**, `manual_server` 0, `excluded_apps` 15, `emergency_source_config` 1 — all unchanged |
| History tables created | **PASS** — 3 tables, all **0** rows immediately after the upgrade |
| Auto Connect run records history | **PASS** — 4 events from 4 real connections: `CONNECTED / SUCCESS / WIFI / SOFTETHER_TCP`, `durationMs` 5,998 · 3,471 · 3,780 · 3,513; 4 summary rows, 4 protocol rows |
| Second run still works | **PASS** — the run moved to `public-vpn-183`, then two more servers, each recording its own row; no crash |
| `NetworkTypeClassifier` on a real `ConnectivityManager` | **PASS** — every row recorded `WIFI`, the true physical transport |
| No FATAL / ANR | **PASS** — `logcat -b crash` empty after every step; `logcat -b all` contains **0** `FATAL EXCEPTION` and **0** `ANR in vn.unlimit` |
| Device left as found | **PASS** — app force-stopped, **0** tunnels, no setting changed |

**What could not be driven — OPERATOR MANUAL STEP.** The manual connect from the Server List opens a
protocol picker, and after choosing `SoftEther SSL-VPN over TCP` the flow raises a system dialog
("Allow VpnM to open Chrome?"). Denying it did not dismiss it, so the **Next Server from a manually
connected server** path is **not device-verified** in this block; it is covered by 7 JVM tests and
was proven correct only there. The same dialog blocks the pinned-row screenshot for a manual tunnel.
Two further F.29 items stay `NOT TESTABLE` for the same reason: the armed-tunnel duplicate-callback
path, and history-write behaviour against a really locked database. The fifth — "does history
actually help users connect more often" — needs many runs over days and is **UNKNOWN**.

Evidence: `test-evidence/dv61/device_state.txt` (the queries above), `ui_*.xml` dumps, and the APK
hash. [PHYSICAL DEVICE]

---

## J. PART F4 — push

Conditions: ten green runs (**met**), no crash and the migration verified on the device copy
(**met**), `git status --short` differing from the start only by what was committed (**met** — 31
lines at the start, 17 at the end, and the 14 that disappeared are the tracked work this block
committed).

**Pushed.** `b66e6d6..b8a9d3e main -> main`, fast-forward, `origin/main == HEAD`, **no `--force`**,
**no tag** (5 before, 5 after).

**Post-push standalone gate.** A fresh extraction of the published tree — `git archive origin/main`
into `/d/dv61/pp2`, a directory that never saw the working tree — built `:app:assembleProDebug`:
**BUILD SUCCESSFUL in 1m41s**, `app-pro-debug.apk` **63,889,704 B**, SHA-256
`f4eb86716a6f5e0cbbb302c8a93685a697915a1e03c98573bdd11cbe32b7688d`. The APK is not byte-reproducible
against the pre-push build (`05d70919…`, same size): the two trees differ in build timestamps, which
is expected and not a code difference — the two source trees differ only by this report.

Two infrastructure failures were retried on the way: two `BindException` startups, then a
`FileNotFoundException` on `SoftEtherClient/.cxx/.../hash_key.txt` (a CMake artifact left locked in
a reused directory — the fresh directory fixed it). None of them is a code failure. [TEST]

---

## K. What is not claimed

No claim that F.29 improves connection success — that is its goal and it is unmeasured. No claim
that Next Server after a manual connect works on a device: the UI could not be driven past a system
dialog. No claim about the duplicate-callback path or a really locked database. No claim that the
downgrade path works — it throws by design and no test covers it. `303baaf` does not build alone.
No claim that the ordering change is neutral: it is not, by design, once history exists.

## L. Remaining risks

1. **`303baaf` is not bisectable.** A future re-split should move the import hunk.
2. **Downgrade crashes on launch** — safe (no data loss) but hostile, and untested.
3. **`stop()` can wait up to 2 s** for an earned success record (`NonCancellable` + 2 s bound).
4. **The read bound cancels the wait, not the query** — a wedged SQLite call keeps an IO thread busy
   until it returns, though the run proceeds.
5. **A hostname change starts a new history identity** (F.29 OD-2, accepted): the old row is
   orphaned and the new name begins at tier 2.
6. **`QUALITY_REJECTED` / `qualityRejectionCount` have no producer** — permanently 0 until one exists.

## M. Evidence index

`/c/Dev/dv61/`: `compile_c1..c4.log`, `f29run1..3.log`, `gate10.log` + `g10_run1..10.log`,
`lint_apk.log`, `apk.sha256`, `status_start.txt`, `devdb/` (before/after copies), `a1..b6.xml` (UI
dumps). In-repo (untracked): `test-evidence/dv61/device_state.txt`, `test-evidence/dv61/final.db`.
Commits: `049c7e3` `303baaf` `2639a42` `4b04cd7` `9d50166` `acb1a39` `afa580b` `51fe1d2` `5ffce2d`.
