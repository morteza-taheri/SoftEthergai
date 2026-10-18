# Z1-IMPL-C — Emergency CSV Parser + Wire-In (v1.0.21)

**Block:** Z1-IMPL-C — Roadmap §12.5.2 item C
**Repository:** `SoftEthergai` on `C:\Dev\SoftEthergai`
**Spec version:** 1.0.21 (`PROJECT_SPECIFICATION.md`)
**Mode:** implementation + JVM test + build, **device-free**
**Result:** COMPLETE — 3 commits pushed fast-forward to `origin/main`

---

## A. What was requested

Roadmap §12.5.2 item C: implement the emergency CSV parser per
`SERVER_SOURCE_ARCHITECTURE.md` §10, a fetch boundary for user-configured
sources, a minimal integrity classification (`VALID` / `PARTIAL` / `MALFORMED`),
and an opt-in wire-in that activates only when at least one enabled emergency
source exists — with provably zero behaviour change otherwise.

## B. Pre-conditions (STEP 1)

| Check | Expected | Observed |
|---|---|---|
| HEAD | `97c2fbce5bb2b23a2bbce79509043d993fad13cd` | identical |
| HEAD == origin/main | yes | yes |
| Branch | `main` | `main` |
| Tracked modifications | none | none (all `??` are pre-existing untracked artifacts) |
| Spec version | 1.0.20 | 1.0.20 |
| Tags | 5 local / 5 remote | 5 / 5 |
| Chain arithmetic | 65 | 65 |

## C. Discovery (STEP 2) — paths confirmed, nothing invented

| Target | Tentative | Confirmed |
|---|---|---|
| `ServerListUpdater` | `repository/` | `app/src/main/java/vn/unlimit/vpngate/repository/ServerListUpdater.kt` (430 lines) |
| `VpnServerMerger` | `merger/` | `app/src/main/java/vn/unlimit/vpngate/merger/VpnServerMerger.kt` (334 lines) |
| Parser dir | `parser/` | exists (`VpnGateApiParser.kt`, `VpnGateHtmlParser.kt`, `OpenVpnConfigParser.kt`) |
| Remote dir | `data/remote/` | exists (`HttpFetcher.kt`, `SourceIdentity.kt`, `RedirectPolicy.kt`, `SourceValidation.kt`) |
| Arch doc §10 | readable | readable (29 columns, schema 1.0, fail-closed) |

Read in full before any edit: `VpnModels.kt` (the `VpnRecords`/`VpnUtil` API),
`VpnGateApiParser.kt` (the model parser and its unit normalisations),
`ServerListUpdater.kt`, `VpnServerMerger.kt`, `HttpFetcher.kt`, `Provenance.kt`,
`VpnServerRepository.kt`, `ServerListUpdates.kt`, `SourceValidation.kt`,
`RedirectPolicy.kt`, `ApiParserOracleTest.kt`, `HttpFetcherR4bTest.kt`,
`CollectorTestFixtures.kt`, and arch doc §2/§3/§10.

### Key API facts absorbed

- `VpnServerRecord = MutableMap<String, Any?>`; built with
  `VpnRecords.newServer/setField/markSupported/sourceAdd`. `setField` **ignores
  falsy values** (empty string, `null`, `false`, `0`, empty list) — so a port of
  `0` is automatically "not present", exactly as §10.3 requires.
- `VpnGateApiParser.splitCsvRecords` is the project's RFC-4180 splitter and
  already ignores `\r`, so CRLF/LF tolerance comes for free.
- `VpnServerMerger.mergeRecords` implements IP→hostname dedup with gap-fill
  merge — i.e. §10.5's rules already exist and must not be re-implemented.
- Unit normalisations to match exactly: Speed `bps / 1e6`, Uptime
  `ms / 86_400_000`, TotalTraffic `bytes / 1024³`.

### One operator decision taken during discovery

The emergency rung's production wiring needed a home that is not in the
tentative authorized list. Two options were presented; the operator authorized
**`ServerListUpdates.kt`** so that `ServerListUpdater` stays pure-JVM (the
lambda is injected, and tests inject fakes) instead of reaching `App.instance`
itself.

## D. Source changes

### New — `parser/EmergencyCsvParser.kt`

Pure function, `parse(text, source) -> Result`. Header-driven (not positional)
because §10.9 Q2 leaves column order open; only the four required columns are
demanded. `schema_version` is fail-closed on any value but `1.0` and is checked
on **every** row, so a single non-conforming row rejects the file (§3.1).
BOM and non-UTF-8 bodies are rejected rather than repaired. Records are ordinary
`VpnServerRecord`s — no parallel record type (§3.12). Identity and dedup are
delegated to `VpnServerMerger`; the one rule the model has no opinion about —
a merged record's file-level `SourceTag` becoming `merged` — is applied after
the merge and carried under an `emergency` provenance map.

Deliberate §10.3/§10.4 decisions, each cited in the code:

- `SE_UDP_Port` without a declared `SE_UDP_Supported` is **not** written as a
  transport (the RUDP warning, verbatim from `RUDP_IMPLEMENTATION_PLAN.md`).
- `0` is the documented "not present" token and is never reported as a defect.
- Base64 that does not decode keeps the row and drops OpenVPN.
- `CountryShort` not two letters is dropped to empty, not rejected.
- `Has_IPv6`, `FirstSeenAt`, `LastSeenAt` have no home in `VpnServerRecord`;
  they are metadata only and are not persisted (recorded, not silently dropped).

### New — `data/remote/EmergencySourceFetcher.kt`

Fetch-only boundary. Injects the same `HttpFetcher` the canonical collector
uses, so `ResilientDns`, the 10 s connect / 15 s read pair and manual per-hop
redirect handling are inherited — §2.6 asks for exactly that and no second HTTP
stack is introduced. Follows no redirect outside the source's own authority,
re-checks the reported final URL against it, refuses a body over 1 MiB
**rather than truncating it**, retries each source once under a 40 s deadline.
No VPN binding (documented rationale: the rung is reached only after the open
and VPN rungs both failed, and binding to a just-failed tunnel replays a
failure). Never parses, never persists.

Recorded limitation: `HttpFetcher` returns a decoded `String`, so the 1 MiB cap
bounds parsing and retention, not bytes on the wire; the download itself is
bounded by the shared client's read timeout.

### Modified — `data/remote/SourceIdentity.kt`

`SourceKind.EMERGENCY_CSV` now routes to a new `EMERGENCY_TAG = "emergency"`
instead of falling through to `mirror_unnumbered`. Required by arch doc §3: an
emergency record must never sit in the mirror provenance group and must never
read as `api` or `html`. Additive; no enum entry reordered or renumbered.

### Modified — `repository/ServerListUpdater.kt`

One injected parameter, `emergencyFetch`, defaulted to `{ null }`, tried only
when the primary fetch returned `null`. Because that fetch already covers the
open and VPN rungs plus the repository's snapshot / stored / bundled-seed
fallbacks, this is genuinely §2.5 path 3. A `null` or empty result falls through
to the byte-identical `Outcome.Preserved(NETWORK_FAILURE, …)` that existed
before. A dataset it supplies goes through the identical acceptance sequence —
`isUsable`, Room-identity dedup, the OpenVPN-profile guard, `DatasetIntegrity`
and the single atomic replace. `DatasetIntegrity` is what refuses an emergency
file too small to be a server list when a healthy list is already stored
(§2.7 / §10.8), which is why no special write path was added. KDoc invariant 6
records this.

### Modified — `repository/ServerListUpdates.kt` (operator-authorized)

Builds the production lambda from `App.instance?.emergencySourceConfigDao
.listEnabled()` and runs the live pipeline unchanged: fetch each source
sequentially → parse → `VpnServerMerger.mergeRecords` → `validateServers` →
`ServerQualityCalculator.scoreAll` → `VpnConnectionMapper.toConnectionList` →
`toVPNGateItems()`. Returns `null` rather than an empty list on every failure
path, so a refused, oversized, undecodable or empty source falls through and
leaves the stored list untouched.

## E. Tests (STEP 5)

Two new pure-JVM files, 46 tests, 0 failures.

`EmergencyCsvParserTest` (30 tests) — full §10.2 mapping incl. unit
conversions; header-order-authoritative; unknown header column; empty body;
header-only; missing required column (all four); BOM; non-UTF-8; unsupported
schema version (5 values); schema token checked on every row; wrong column
count → Partial; invalid IPv4; empty HostName; partial import; out-of-range
port → not-present + note; declared UDP with port; published UDP port without
declared support is not a transport; undecodable Base64 keeps the record;
`CountryShort` not two letters; unknown `SourceTag` → `html_parse`; non-boolean
token; non-numeric optional columns; SSTP `:port` reconciliation both ways;
duplicate identity merged and tagged `merged`; duplicate hostname on a
different IP not merged; hostname `:port` does not defeat dedup; emergency
never outranks canonical in a merge (with the conflict visible); CRLF ≡ LF;
trailing newline; RFC-4180 quoted comma field; parser tag == identity tag.

`EmergencySourceFetcherTest` (16 tests) — 200 → Success; source's own redirect
policy applied; redirect that left the source refused; rejected hop reported
not followed; empty body → NetworkError; 404 → HttpError after one retry; no
response → NetworkError after one retry; a throwing fetcher cannot abort the
run; unusable URL never reaches the transport; over-cap → TooLarge rather than
truncated; exactly-at-cap accepted; fail-then-succeed; second failure shape
reported.

One defect was found and fixed during the first run: `portValue` reported the
documented `0` "not present" token as `INVALID_PORT`. Fixed to treat `0` as
conforming; the test that caught it now asserts exactly 2 notes.

## F. Wire-in verification (STEP 6–7)

- `:app:testProDebugUnitTest` = **724 / 0 / 0 / 1**
  (baseline 678 / 0 / 0 / 1; **+46**; ignored count unchanged). All 678
  pre-existing tests pass unchanged — including every `ServerListUpdater`
  case — which is the regression evidence that the wire-in changes nothing
  when no emergency source is configured. Evidence: TEST.
- `:SoftEtherClient:test` = **173 / 0 / 0 / 0** — identical to baseline.
- `CI=true :app:assembleProDebug` SUCCESSFUL; APK SHA-256
  `b85a7aba4ecfdea4a237b2ffae77be27241638a62d92e8313d6e6c292d53b6fd`
  (63,821,179 bytes), which differs from the prior `9ba16f9f…` as expected.
- Environment: every Gradle invocation ran with `CI=true`, because
  `app/build.gradle` disables all debug variants unless that variable is set
  (learned in Z1-IMPL-B). `JAVA_HOME` is the Android Studio JBR.

## G. Diff scope (STEP 9)

New: `parser/EmergencyCsvParser.kt`,
`data/remote/EmergencySourceFetcher.kt`,
`app/src/test/.../collector/EmergencyCsvParserTest.kt`,
`app/src/test/.../data/remote/EmergencySourceFetcherTest.kt`.
Modified: `data/remote/SourceIdentity.kt` (+16),
`repository/ServerListUpdater.kt` (+45/−2),
`repository/ServerListUpdates.kt` (+101), `PROJECT_SPECIFICATION.md`.
No file outside the authorized set was touched. `app/build.gradle` untouched;
no new dependency.

## H. Specification (STEP 10)

Header → 1.0.21, HEAD `68bbbfb`. §1.6.1 gained the v1.0.21 push-state note.
New **§1.6.14** (9 evidence-labelled bullets). **§11.9.2** annotated with the
implemented safety set, historical text retained. **§12.5.1** unchanged — no row
tracks item C. A status note added under the **§12.5.2** table; row C's
description left verbatim, per the v1.0.20 direction in §11.9.3. §20 changelog
row 1.0.21 added; §20 verification rows for §11.9 and §1.6.14 added.

Frozen-section checks: §1.6.2–§1.6.12 byte-identical (640 lines / 51,264
bytes); §1.6.13 byte-identical (67 lines / 4,767 bytes); **§22 byte-identical
(99 lines / 6,504 bytes)**.

## I. Commits and push (STEP 11–12)

| # | Subject | Role |
|---|---|---|
| `a0b9ec6` | `feat(emergency): emergency CSV parser and source fetcher (roadmap 12.5.2 item C)` | source + tests |
| `68bbbfb` | `feat(emergency): wire the emergency rung into the updater as the last source` | wire-in |
| docs | `docs: spec v1.0.21 — Z1-IMPL-C + §1.6.14 + §11.9.2 safety-set status` | spec + report |

Fast-forward push `97c2fbc..docs`. No force, reset, clean, stash, checkout or
restore. No tag operation of any kind.

## J. Post-push verification (STEP 13)

| Check | Result |
|---|---|
| HEAD == origin/main | yes |
| Tag count | 5 local / 5 remote, unchanged |
| §22 byte-identity | 99 lines / 6,504 bytes — identical |
| Chain arithmetic | 65 at revision start → **68** through the docs commit; §1.6.1 table unchanged |
| Working tree | clean of tracked modifications |

## K. Outstanding, explicitly not claimed

- **Device verification: NONE.** The device is DETACHED; no `adb` command was
  issued. The full path — a configured URL, a live fetch, a stored dataset, and
  a real Room 5 → 6 upgrade — is **UNKNOWN**.
- Cumulative Server Retention (arch doc §9) is **not** implemented: the update
  still replaces the dataset rather than merging cumulatively.
- Roadmap items D, E, F, G, H remain NOT IMPLEMENTED (manual entry, UI entry
  point, import formats, safe merge/persistence, and the device-level test
  matrix).
- The 1 MiB cap bounds parsing and retention, not bytes on the wire.

## L. Evidence labels used

SOURCE CODE (direct inspection of the named file/line), TEST (a command that
was run and its counted output), PHYSICAL DEVICE (device evidence — **none in
this revision**), INFERENCE (a reasoned judgement, labelled as such), UNKNOWN
(not determinable here), PRIOR REPORT (carried from an earlier revision).
