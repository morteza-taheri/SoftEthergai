# AUTO_SETTINGS_CLEANUP_REPORT — v1.0.54

**Block:** SETTINGS-CLEANUP, MANUAL-IMPORT, LIST-PIN, DEFAULTS, SKIP-RACE
**Spec sections:** §1.6.46 – §1.6.50, §11.10 (rewritten), §11.9.1 (annotation), §11.14 (new)
**Base commit:** `d4888ee66bf86d8ff77a8e483be25e169a119dc7`
**Date:** 2026-10-23
**Device:** **not available.** Every item that needs the phone is listed in §L and was deliberately not attempted.

---

## A. Authority and what "done" means here

The operator granted full authority to execute this block *and* the whole of the previous instruction, with one hard constraint: **the phone is unavailable until further notice**, so anything needing the device is deferred. Every change must be recorded in `PROJECT_SPECIFICATION.md`.

That constraint shapes the whole report:

- Everything device-free was **implemented, tested and specified**.
- The one requirement whose *data* lives on the phone — the list of Iranian apps to pre-exclude — was built **end to end except for the data**. The parser, the installed check, the one-shot flag, the manifest visibility declaration and the tests all ship; the asset ships with its format documented and no entries. That is D1.
- Nothing was simulated to look finished. Where a result is "compiles and is tested but no human has seen it on a screen", it says so.

---

## B. The starting state, verified before any edit

Before writing code I checked what was actually true at `d4888ee`, rather than trusting the previous block's description:

| Check | Result |
|---|---|
| `HEAD` == `origin/main` | yes, `d4888ee` |
| `PROJECT_SPECIFICATION.md` version | 1.0.53 (so v1.0.54 had not started) |
| `AUTO_SETTINGS_CLEANUP_REPORT.md` | did not exist |
| `skipInFlight` in `AutoModeController.kt` | absent |
| "Include UDP server" / "Block Ads" settings | **still live** (switch + preference + consumers) |
| Cache save time / auto-clear | **still live** |
| CSV import UI | absent |
| `assets/default_excluded_apps` | absent |

So the block began from a clean base, not from a half-finished state.

---

## C. PART 1 — the R-1 duplicate dial (§1.6.46)

### The defect

`skipToNextServer()`'s Connected branch tore the tunnel down asynchronously while the state still read `Connected`. A second press in that window re-entered the same branch, cancelled the continuation the first press had published (`job?.cancel()`) and launched its own. The v1.0.53 report recorded this as `[s2, s1, s1, s1, s1, s1]` for one double press and could not fix it, because the block that produced it did not authorise touching that path.

### The fix

1. `skipInFlight: Boolean` (`@Volatile`) is set **inside `transitionLock`** at the top of the Connected branch, before any asynchronous work. A second entry logs `[AUTO] Skip already in flight; duplicate request ignored` and returns. Setting it inside the monitor that already owns the transition — rather than relying on dispatcher ordering — is what makes it deterministic.
2. **The flag's lifetime is owned by `setState`**: publishing any state other than `Connected` clears it.
3. A button press inside the window is a **stop**, not a swallowed press.

### The part worth reading: my first fix was wrong, and the test suite caught it

The first implementation cleared the flag in two places — inside the skip continuation just before launching the replacement run, and on `runAutoMode()`'s first line. Both happen *before* the replacement run publishes a state, so the machine still read `Connected` with the flag already `false` for the whole teardown plus list resolution. That is the same window, reopened.

It passed in isolation. It failed in the full suite, with the log showing two `Connected server skipped by user` lines and **no** `duplicate request ignored`:

```
duplicateSkipWhileConnectedIsCoalescedToTheFirstSkip FAILED
  the coalesced press must be logged: [... Connected server skipped by user; trying next,
   disconnect, Connected server skipped by user; trying next, Started, ...]
```

That failure is the finding, not noise: a flag whose lifetime is scattered across call sites will always have a window somebody forgot. Moving the clear into `setState` makes the flag mean exactly one thing — *"a Connected-state skip was accepted and the machine has not left Connected yet"* — in the single place that can observe it.

### Making the tests honest

The original tests raced the window. On a fast run the continuation had not started; on a slow one it had. So `FakeAdapter.disconnect()` gained an optional `disconnectGate: CompletableDeferred<Unit>`: a test parks the skip inside its teardown, presses, then releases. All three tests now assert the rule instead of the schedule.

This also exposed a second, subtler point: **an ungated burst test asserts the wrong thing.** Without the gate, the continuation can publish `Connecting` between two presses, and a later press then legitimately takes the *Connecting* branch (`skipRequested`) — a different, correct skip. That is not a coalescing failure, and a test that calls it one would push a future maintainer to "fix" correct behaviour.

### Evidence

- `AutoModeControllerTest`: `duplicateSkipWhileConnectedIsCoalescedToTheFirstSkip`, `buttonPressDuringAnInFlightSkipStopsInsteadOfStartingASecondRun`, `fiveRapidSkipsCollapseIntoASingleContinuation`.
- Three consecutive `--rerun-tasks` runs of `AutoModeControllerTest` after the fix: **0 failures each**.
- Before the fix, the same class failed under full-suite load while passing in isolation.

---

## D. PART 2 — Settings cleanup (§1.6.47)

Three controls removed **with their code**, one default changed, one recovery path added. Every removal deletes the preference key and every consumer; a dead preference that no longer renders is a second source of truth waiting to be re-read.

| Removed | What went with it | Why the product is not poorer |
|---|---|---|
| **Include UDP server** | the switch, `DataUtil.INCLUDE_UDP_SERVER`, every `isIncludeUdp` consumer (`VPNGateConnection.getName()`, `VPNGateConnection.fromCsv()`, `ServerCard`, `DetailActivity`, `StatusViewModel`, `AndroidConnectionAdapter`) | UDP-capable servers are in the list by construction; the TCP/UDP badges are now rendered from the ports themselves, which is the only truthful source |
| **Block Ads (Pro only)** | `SETTING_BLOCK_ADS`, the switch and its `prefs` DNS writes, `AppConfig`'s `vpn_dns_block_ads_primary` / `_alternative`, and three `resolvePrimaryDns()` / `resolveSecondaryDns()` branches | Custom-DNS-only is byte-for-byte the behaviour the switch produced when OFF. The only thing lost is the ability to switch the alternate DNS pair on |
| **Cache save time / Cache will auto clear** | `models/Cache.kt` (deleted), `getCacheSaveTimeMinutes()`, `clearConnectionCache()`, `markConnectionsCacheFresh()`, `connectionCacheExpires`, `setConnectionCacheExpire()`, the `connectionsCache` custom setter, `SETTING_CACHE_TIME_KEY`, `DEFAULT_CACHE_TIME_INDEX`, the `setting_cache_time` array | One manual **Clear cache** action remains, plus the update-interval row. `DataUtil.clearTransientCache()` deletes `cacheDir` children and the legacy marker, and nothing else |

**Protocol-priority default.** `DataUtil.DEFAULT_AUTO_PROTOCOL_PRIORITY = [SOFTETHER_TCP, SOFTETHER_UDP]`, returned when the stored key is **absent** (`null`). An **explicitly empty** stored profile stays empty — a user who switched every protocol off must not be silently overridden. Unknown ids read from storage are dropped. Parsing is extracted into `parseAutoModeProtocolPriority()` so all three cases are testable without SharedPreferences.

**Emergency source.** Already pre-filled since v1.0.46 (`emergency_defaults.xml` → the GitHub-hosted `emergency-sources/servers.csv`, seeded once in `App.onCreate`). v1.0.54 adds recovery: when no row carries the default URL, Settings shows **Restore default source**, so a user who deleted the row gets it back without reinstalling or retyping a URL. A deliberate deletion is still respected — the seed does not resurrect it.

**Strings.** Removed from `values`, `values-fa`, `values-vi`: `udp_setting_label`/`_hint`, `block_ads_setting_label`/`_hint`, `setting_cache_subtitle`, `setting_cache_auto_clear_label`, the `setting_cache_time` array, all `cache_time_*`, and the whole old manual-form set (`manual_server_host_label`, `_port_label`, `_protocol_label`, `_label_label`, `_notes_label`, `_add`, `_invalid`, `manual_server_protocol_*`). Added in English and Persian: `setting_cache_clear_label`/`_subtitle`, `emergency_source_restore_default`, `emergency_source_default_label`, `server_pinned_badge`, and the CSV-import set.

**A stale preference is inert.** `SETTING_CACHE_TIME_KEY` left in an existing install is never read; `DataUtilDefaultsV1054Test` pins that it is ignored rather than honoured.

---

## E. PART 3 — server-list pinning (§1.6.48)

The server the app is on — or is dialling — is rendered at row 1.

- **Identity, not IP and not index.** `ServerListPinning.identity(ip, hostName)` returns `"ip|host"` — the same key `resumeFromServerKey` and the wedge ledger already use. An IP alone collides under NAT or a load balancer; an index drifts the moment the list re-sorts.
- **A view transform, not a reorder.** `pin(items, keyOf, pinnedKey)` is pure, O(n), applied **after** filter and sort, and returns the caller's own list instance when there is nothing to do. The stored dataset is untouched, so a refresh cannot persist an accidental ordering and `firstSeenAt` / `lastSeenAt` / `healthScore` are never rewritten by a display concern.
- **Both live states pin.** `pinnedKey` comes from the controller's active server, so a `Connecting` candidate pins exactly like a `Connected` one.
- **Visible marker.** `ServerCard` gained `pinned: Boolean = false` and renders `server_pinned_badge` above the existing active-server badge, so a row that has moved is explained rather than merely displaced.
- **Tests.** `ServerListPinningTest`, 11 cases: identity shape, connected pin, connecting pin, candidate switching, same-IP-different-host, absent server, empty list, null pin, pin filtered out, no duplication, 1000-row single pass.

---

## F. PART 4 — manual CSV import (§1.6.49)

The per-field add-server form is **gone**. In its place: a paste box with an **Import** button, and an **Import CSV file** button that opens the system document picker.

### Formats

Two dialects, both already owned by an existing parser — this phase deliberately did not write a third:

- the §10 extended CSV (`EmergencyCsvParser`), required columns `HostName`, `IP`, `schema_version`, `SourceTag`;
- the VPN Gate `/api/iphone/` CSV (`VpnGateApiParser`), `#HostName,IP,...` with 15 canonical columns.

`ManualCsvImport` does only what neither parser should do for a *pasted* file: detect the dialect, tolerate transport artefacts, enforce the caps, and hand the text to the owner. It never re-implements column semantics — a second definition of the format would drift from the first.

### Transport repair

BOM stripped, CRLF/CR unified, trailing horizontal whitespace trimmed, hard-wrapped rows re-joined. The re-join is conservative: a line is appended only when **both** the buffer and the line have fewer fields than the header. A complete row therefore always starts a new buffer, and a genuinely malformed short line is left standing so the parser can reject it with a real reason instead of being glued to the next server.

### A real defect found while writing the tests

The API body opens with a `*vpn_servers` section marker, so "the first non-blank line is the header" classified **every real API file** as `UNKNOWN` and refused it. Detection and the wrapped-row repair now both locate the header through `VpnGateApiParser.findApiHeader`, and the body is sliced from that line before it reaches either parser. Pinned by `detect sees past the api section marker` and `an api file with its section marker parses instead of being refused`.

A second test premise was wrong and was corrected rather than forced: `0.0.0.0` is *syntactically valid* IPv4 and the record layer accepts it, so it imports as a row. An **empty** IP is the case that actually loses a row. The test now says so, and the distinction is recorded in the test itself.

### Limits

1 MiB of UTF-8 input and 2000 data rows, both hard. Over either, the import is **refused whole** — a truncated server list is worse than a clear error. `readCappedUtf8()` enforces the byte cap at the *stream* layer, before any decoding: the picker hands back a stream of unknown length, and a 40 MB attachment would otherwise be decoded into a ~80 MB `String` before the parser's own check could run.

### Storage

`ManualImportStore` — a JSON file (`manual_import_servers.json`) keyed by `hostName`, atomic temp-file + rename writes, a corrupt file reading as empty (and left untouched), no Room and no network.

Why not a table: `manual_server`'s unique index is `(host, port, protocol)` while the server list merges by host name, so a multi-protocol CSV row would collapse into a single protocol — and widening the schema is a Room migration this phase was not authorised to make. **Legacy `manual_server` rows are still listed in Settings** with their enable switch and delete button, so the rewrite loses no user data.

### Preservation and provenance

`ServerListUpdates.manualServerRows()` now emits imported rows too, and `ConnectionListViewModel.materializeImported()` writes them back through the same `withManualRows` the refresh path uses — one preservation policy, not two. Imported rows carry the tag `manual`, never `api`/`html`, so a user-supplied file can never outrank a canonical source.

### Deleted with the surface they pinned

`ManualServerProtocolChoicesTest` and `ManualProtocolLabelExhaustiveTest` were removed. A test that asserts the shape of a form that no longer exists is worse than no test — it converts a deliberate deletion into a permanent compile-time hostage.

---

## G. PART 5 — Excluded Apps default seed (§1.6.50)

### What ships

- `app/src/main/assets/default_excluded_apps` — one package name per line, `#` comments, blank lines ignored, duplicates collapsed, BOM stripped. **Currently: format documentation only, no entries.**
- `DefaultExcludedApps` — pure `parse()`, plus `load()` that returns empty on any failure (a packaging mistake must not stop the app starting).
- `ExcludedAppsSeeder.seedOnce()` — four rules: installed-only; once per install (`SETTING_EXCLUDED_APPS_SEEDED`); never overwrite an existing row; **no candidates means no flag**.
- `App.onCreate` wiring on the existing `appScope` (IO), `runCatching`, same shape as the v1.0.46 emergency seed.
- Manifest `<queries>` for `MAIN` / `LAUNCHER`.

### Why rule 4 exists

The asset ships empty because the real package names are on the operator's phone. Setting the flag on an empty list would permanently disable the seed for the very build that finally carries the names. Pinned by `an empty seed list does not burn the one-shot flag`.

### The manifest change, and a bug it also fixes

Since API 30, `getInstalledApplications` returns only packages the app is allowed to see — and the failure is **silent**. The app had no `<queries>` element at all, which means the Excluded Apps sheet itself was very likely showing almost nothing on any modern device. The declaration fixes both the sheet and this seed.

It is scoped to launchable apps on purpose. `QUERY_ALL_PACKAGES` requires a separate Play Console justification and buys nothing for the actual use case (an app a user wants to exclude is, by definition, one they launch). **Known limitation, accepted and recorded:** a background service with no launcher activity is not listed.

---

## H. Files

### Added — source

| File | Purpose |
|---|---|
| `repository/ManualCsvImport.kt` | dialect detection, normalisation, caps, wrapped-row repair, delegation to the two existing parsers |
| `repository/ManualImportStore.kt` | JSON persistence for imported rows |
| `ui/screens/home/ServerListPinning.kt` | pure identity + pin transform |
| `utils/DefaultExcludedApps.kt` | asset parsing and loading |
| `utils/ExcludedAppsSeeder.kt` | the one-shot seed, with a device-free core |
| `assets/default_excluded_apps` | seed list (format only) |

### Added — tests

| File | Tests |
|---|---|
| `repository/ManualCsvImportTest.kt` | 36 |
| `repository/ManualImportStoreTest.kt` | 20 |
| `ui/screens/settings/ReadCappedUtf8Test.kt` | 9 |
| `utils/ExcludedAppsSeederTest.kt` | 13 |
| `utils/DefaultExcludedAppsTest.kt` | 12 |
| `ui/screens/home/ServerListPinningTest.kt` | 11 |
| `utils/DataUtilDefaultsV1054Test.kt` | 7 |

### Deleted

| File | Reason |
|---|---|
| `models/Cache.kt` | the cache-expiry model went with the schedule it existed for |
| `ui/screens/settings/ManualServerProtocolChoicesTest.kt` | the form it pinned is removed |
| `ui/screens/settings/ManualProtocolLabelExhaustiveTest.kt` | the labels it pinned are removed |

### Modified (principal)

`AutoModeController.kt` (flag + lifetime + stop semantics) · `AutoModeControllerTest.kt` (gate + 3 tests) · `DataUtil.kt` (5 accessors removed, `clearTransientCache`, default protocol priority) · `SettingsScreen.kt` (three controls removed, cache row, restore-default, CSV import section, `readCappedUtf8`) · `ServerCard.kt` · `HomeScreen.kt` · `VPNGateConnection.kt` · `DetailActivity.kt` · `StatusViewModel.kt` · `AndroidConnectionAdapter.kt` · `AppConfig.kt` · `ConnectionListViewModel.kt` · `ServerListUpdates.kt` · `App.kt` · `AndroidManifest.xml` · `strings.xml` (en/fa/vi) · `DeveloperModeLogGateTest.kt`.

---

## I. Test evidence

| Target | Result |
|---|---|
| `:app:testProDebugUnitTest` | **1044 tests, 0 failures, 0 errors, 1 skipped** (baseline 941 → **+103**) |
| `:SoftEtherClient:test` | **184 tests, 0 failures, 0 errors, 0 skipped** (unchanged) |
| `AutoModeControllerTest` stability | 3 consecutive `--rerun-tasks` runs, 0 failures each |

The one skipped test is pre-existing and unrelated to this block.

**What the tests actually prove, and what they do not.** They prove the parsing rules, the caps at their exact boundaries, the failure taxonomy, the store's durability under 8 concurrent writers, the pin transform, the seeder's four rules, and the skip coalescing under a gated teardown. They do **not** prove that any of it looks or feels right on a screen — see D2.

---

## J. Build evidence

```
CI=true ./gradlew :app:assembleProDebug   → BUILD SUCCESSFUL
app/build/outputs/apk/pro/debug/app-pro-debug.apk
size   66,101,207 bytes
sha256 23a064efacee07ac767eab552e228ccce3a475bc868ef1a3bdede86b611a2872
```

`assets/default_excluded_apps` verified present inside the APK (1,651 bytes) — the asset is actually packaged, not merely present in the source tree.

---

## K. Specification changes

- Header: version **1.0.53 → 1.0.54**, `HEAD` → `d4888ee`, date → 2026-10-23.
- **§1.6.46 – §1.6.50** appended, one per part, in the established format.
- **§11.10 rewritten** from "planned / do not decide the UI or the format" to normative: the decided entry point, the two accepted dialects, the tolerated transport artefacts, the hard limits, the entry requirements and the storage decision.
- **§11.9.1 annotated** with the *Restore default source* behaviour and an explicit statement that the no-resurrection rule is unchanged.
- **§11.14 added** — removed controls (table), protocol-priority default, pinning, and the seed's four rules with the manifest rationale.
- Changelog row `1.0.54` and a Verification Status row added.
- **§22 is byte-identical.** The only pre-existing lines removed anywhere in the file are the three header lines and the superseded §11.10 body.

---

## L. DEFERRED — the items that need the phone

| ID | Item | Why deferred | What is ready |
|---|---|---|---|
| **D1** | The package names for `assets/default_excluded_apps` | They were entered manually on the phone; the authoritative list is there | Parser, installed check, one-shot flag, manifest visibility and tests all ship. Adding the names is a **data edit to one asset file** — no code, schema or migration change |
| **D2** | Device verification of: the pinned row; CSV import by paste and by file; the four removed Settings controls being absent; the default protocol priority on a fresh install; the single cache button; a Next-Server double press | No device | All device-free evidence is in §I |
| **D3** | V-VERIFY-3 / V-VERIFY-4 carried over from earlier phases | No device | Unchanged from v1.0.53 |

**D1 procedure when the phone returns.** Read the package names from the phone's Excluded Apps list, append them to `app/src/main/assets/default_excluded_apps` one per line, update `DefaultExcludedAppsTest.the shipped asset carries no entries yet` to the new expected list, rebuild. The seeder will then run once on the next launch of an existing install, because the empty asset deliberately did not burn the flag.

---

## M. Residual risks and what a reviewer should check first

1. **The `<queries>` scope is a judgement call.** Launchable-only keeps the app out of Play policy review; it also means a launcher-less service cannot be excluded. If the operator needs those, `QUERY_ALL_PACKAGES` plus a Play declaration is the change — it was not made unilaterally because it carries a store-review cost.
2. **`ManualImportStore` is a second store for user-owned rows.** Two stores (Room `manual_server` for legacy rows, JSON for imports) is one more than ideal. It is the consequence of not being authorised to migrate the schema; a future block that owns a migration should fold them together.
3. **The imported rows are materialised into the Room dataset on every list build.** That is the existing `withManualRows` policy, so it is consistent — but it means a large import makes every build touch more rows. 2000 rows is the cap and is acceptable; the number to watch if the cap is ever raised.
4. **The skip-coalescing tests depend on a test-only gate.** That is deliberate — the gate is the only way to make the assertion about the rule rather than the scheduler — but it does mean the *racing* form of the bug is no longer directly exercised. The three-run stability evidence in §I is the compensating control.
5. **Nothing here was seen on a screen.** The Settings screen was rewritten in two places and the server list gained a transform; both compile and both are covered at the unit level, and neither has been looked at by a human. That is the largest single gap in this block and it is D2.
