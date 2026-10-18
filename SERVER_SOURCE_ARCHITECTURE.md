# Server Source Architecture — Design Document

- **Status:** DESIGN ONLY for §2–§10 (design proposals and user-directed requirements; the document itself implements nothing). §1 and Appendix A are read-only audits of the code as it exists/existed. Several designs here have since **landed** — see the status annotations at §2, §8, §9, §10.
- **Phase:** F.28AL-Z-SERVER-SOURCE-DESIGN
- **Scope:** (1) dynamic/intelligent server-list retrieval incl. through the app's own VPN tunnel; (2) the emergency CSV source format design.
- **Canonical spec:** `PROJECT_SPECIFICATION.md` (§11.8, §11.9, §11.10, §12).
- **RUDP reference:** `SoftEtherClient/RUDP_IMPLEMENTATION_PLAN.md` (port-semantics context for §3.5).
- **Evidence labels used:** `SOURCE CODE EVIDENCE` / `INFERENCE` / `UNKNOWN`.
- **Documentation map (added in the 2026-10-18 repair):** this file owns the **server-source/path design** (retrieval paths, emergency CSV schema §10, manual import §4, cumulative retention §9). [`PROJECT_SPECIFICATION.md`](PROJECT_SPECIFICATION.md) owns requirements and project status. [`README.md`](README.md) is the public overview. Do not duplicate the CSV schema tables or migration mechanics into those files.

> **Currency note (2026-10-18 repair):** §1 below was rewritten against source HEAD
> `df7b49cf1b91078622cc72774dd1b4fbba73b20a`. The original §1 snapshot, read at HEAD
> `071462816b1eef7e7f7459483fcd69909938cd11` (branch `main`), is preserved verbatim in
> **Appendix A** and is HISTORICAL. Where a design recommendation is not directly supported by
> source, it is tagged `INFERENCE`.

---

## Section 1 — Current-State Audit (READ-ONLY findings)

> **Rewritten in the 2026-10-18 documentation repair (spec v1.0.29).** The original Section 1 — a
> snapshot taken at HEAD `071462816b1eef7e7f7459483fcd69909938cd11` — is preserved verbatim in
> **Appendix A** and is **HISTORICAL**: it predates the emergency-source landings (v1.0.20/v1.0.21),
> manual-server landing (v1.0.22, visibility fix v1.0.26), the integrity-outcome classification
> (v1.0.23), and the OPEN-first / VPN-fallback routing fix (v1.0.27). Everything in §1 below was
> re-read from the working tree at source HEAD `df7b49cf1b91078622cc72774dd1b4fbba73b20a`
> (branch `main`, 2026-10-18). Evidence class for all of §1: `SOURCE CODE EVIDENCE` unless tagged.

### 1.1 The full server-list update chain as it exists today

`Update button → HomeScreen → ConnectionListViewModel.getAPIData → ServerListUpdater.update (pure-JVM orchestrator) → primary fetch: VpnServerRepository.refresh (mirror/api/html race via HttpFetcher on the chosen network path) → if primary returned null: emergency rung (ServerListUpdates.emergencyDataset → EmergencySourceFetcher → EmergencyCsvParser) → parse (VpnGateApiParser for the canonical feeds) → DatasetIntegrity.evaluate → VpnServerMerger → Room (VPNGateItemDao.replaceAll, with manual rows merged via withManualRows) → CollectorSnapshotStore (snapshot only after acceptance) → UI`

| Component | File : anchor | Role |
|---|---|---|
| Update trigger / ViewModel | `app/src/main/java/vn/unlimit/vpngate/ui/screens/home/HomeScreen.kt:251,336`; `app/src/main/java/vn/unlimit/vpngate/viewmodels/ConnectionListViewModel.kt:266` (`getAPIData`), `:83`–`:86` (`ServerListUpdates.forUpdateTrigger(...)`), `:278` (`updater.update()`) | UI entry point; launches the update and maps the `Outcome` to UI state. |
| Production wiring | `app/src/main/java/vn/unlimit/vpngate/repository/ServerListUpdates.kt:51` (`object ServerListUpdates`), fetch lambda `:89`–`:110`, `daoStore` `:225`–`:275`, `emergencyDataset()` `:146`–`:215` | Wires the concrete `fetch` + `store` + `emergencyFetch` closures into the pure-JVM orchestrator. |
| Orchestrator | `app/src/main/java/vn/unlimit/vpngate/repository/ServerListUpdater.kt:58` (class); mutex gate `:222`,`:253`; emergency rung `:271`–`:287`; `DatasetIntegrity.evaluate` `:326`; single write `store.replaceAll` `:336`; snapshot after acceptance `:340`–`:352` | Pure JVM. Enforces atomic replace, "never replace by a less-connectable dataset", single writer. |
| Routing (path selection) | `app/src/main/java/vn/unlimit/vpngate/repository/VpnServerRepository.kt:197` (`refresh()`), `:112` (`productionRouting()`), `:133` (`resolvePrimary()`), `:362`–`:422` (`fetchOnChosenPath()`), `:143`–`:147` (`productionBoundFetcher()`) | OPEN-first / VPN-fallback (§1.2). |
| Legacy single-call chooser | `app/src/main/java/vn/unlimit/vpngate/data/remote/HttpFetcher.kt:321`–`:336` (`CollectorRouting.choose`) | Still unit-tested; **no longer the production decision point** — `resolvePrimary` + `fetchOnChosenPath` are. |
| HTTP fetcher | `HttpFetcher.kt` (`OkHttpFetcher`); timeouts `:106`–`:113`; per-request VPN binding `boundToSocketFactory` `:132`–`:133`; hand-followed redirects `:167`–`:243` | Single-shot GET returning a provenance-rich `HttpResponse`. |
| Resilient DNS | `HttpFetcher.kt:55` (`ResilientDns`), `:57` (`VPNGATE_FALLBACK_IPS`), `:107` (`.dns(...)` wiring) | Filters poisoned/private/loopback answers; falls back to hard-coded IPs. |
| Source catalog | `app/src/main/java/vn/unlimit/vpngate/data/remote/VpnGateSources.kt:22`–`:25` (`MAIN_URL`, `API_URL`, `MIRRORS_URL`, `MAX_MIRRORS = 12`); per-source race deadlines `:171`,`:188` (12 s html/api), `:215` (8 s mirror discovery) | Canonical URLs are compile-time constants. |
| Source identity | `app/src/main/java/vn/unlimit/vpngate/data/remote/SourceIdentity.kt:12`–`:41` (`SourceKind`), tags `:102`–`:135`, `:152`–`:174` | Seven kinds, including **`EMERGENCY_CSV`** (`:32`, tag `"emergency"`) and **`MANUAL`** (`:40`, tag `"manual"`). The `when` is exhaustive with no `else` (v1.0.23). |
| Canonical CSV parser | `app/src/main/java/vn/unlimit/vpngate/parser/VpnGateApiParser.kt` | Port of the VPN Gate `/api/iphone/` 15-column CSV parse. |
| Emergency CSV parser | `app/src/main/java/vn/unlimit/vpngate/parser/EmergencyCsvParser.kt` — required columns `HostName, IP, schema_version, SourceTag` (`:64`); `schema_version` fail-closed on anything but `"1.0"` (`:51`,`:233`–`:242`); snake-case column names (`:73`–`:80`); IPv4-only identity (`:98`–`:99`,`:318`–`:322`); BOM / non-UTF-8 rejected (`:198`–`:207`); header-driven, not positional | Implements **§10** of this document (landed v1.0.21). |
| Emergency fetcher | `app/src/main/java/vn/unlimit/vpngate/data/remote/EmergencySourceFetcher.kt` — 2 attempts + 40 s deadline (`:141`,`:151`); 1 MiB body cap (`:168`); redirect-confined (`:52`–`:111`) | Open-network only; last automatic rung (§1.2). |
| Integrity gate | `app/src/main/java/vn/unlimit/vpngate/repository/DatasetIntegrity.kt` — `MIN_ACCEPTABLE_DATASET_SIZE = 10` (`:180`), `MIN_RELATIVE_DATASET_RATIO = 0.5` (`:183`), `MIN_PROTOCOL_COVERAGE_RATIO = 0.5` / `BASELINE = 20` (`:186`,`:189`), `MIN_PROFILE_COVERAGE_RATIO = 0.5` / `BASELINE = 20` (`:192`,`:195`); `DatasetOutcome` classification (`ACCEPT_NEW` / `MERGE_WITH_EXISTING` / `REJECT_DEGRADED`) added v1.0.23 — **names/logged only, the update still replaces** (§9 not implemented) | Produces the `DatasetVerdict`; rejects empty/degraded datasets. |
| Merger | `app/src/main/java/vn/unlimit/vpngate/merger/VpnServerMerger.kt:48` (`SOURCE_PRIORITY`: `api` 0 > `html` 10 > default 20 — mirrors **and** `emergency` — > `manual` 30; default at `:62`–`:63`) | Conflict-aware multi-source merge. |
| Manual servers | `app/src/main/java/vn/unlimit/vpngate/data/remote/ManualServerEntity.kt:20`–`:34` (table `manual_server`, unique index host+port+protocol); `app/src/main/java/vn/unlimit/vpngate/repository/ManualServerStore.kt:27`, `toItems` `:122`, `materialize` `:145`–`:146`; merge `withManualRows` `ServerListUpdates.kt:356`–`:369` (invoked inside `daoStore.replaceAll` `:268`); add-time materialize `ConnectionListViewModel.kt:217`–`:228` | User-owned records; preserved across refreshes; visible immediately on add (v1.0.26). |
| Room write | `app/src/main/java/vn/unlimit/vpngate/db/VPNGateItemDao.kt:49`–`:55` (`replaceAll`) | `@Transaction` delete+insert; refuses an empty list; dedupes first. |
| Room schema | `app/src/main/java/vn/unlimit/vpngate/db/AppDatabase.kt:23` (`version = 7`), entities `:16`–`:22` (`VPNGateItem`, `ExcludedApp`, `EmergencySourceConfigEntity`, `ManualServerEntity`); `MIGRATION_5_6` `:97`, `MIGRATION_6_7` `:125` | Device migration 5 → 6 → 7 verified on hardware (v1.0.24, spec §1.6.17). |
| Record model | `app/src/main/java/vn/unlimit/vpngate/models/VPNGateItem.kt:9`–`:41` | **No** `firstSeenAt` / `lastSeenAt` / `healthScore` fields — §9 cumulative retention is **NOT IMPLEMENTED**. |
| Snapshot store | `app/src/main/java/vn/unlimit/vpngate/data/persistence/CollectorSnapshotStore.kt:32`–`:38` (`collector_vpn_servers.json`) | Last-known-good snapshot; written only after an accepted update (`VpnServerRepository.kt:471`–`:495`). |
| Settings UI | `app/src/main/java/vn/unlimit/vpngate/ui/screens/settings/SettingsScreen.kt:598`,`:696` (emergency sources), `:723+` (manual servers) | **No settings UI exists for `vpn_always_prefer_vpn`** — the key lives only in `GlobalVpnTracker.kt:639` (read `:408`–`:410`) and can only be set out-of-band. |

### 1.2 Network binding model today

**Explicit per-request binding exists now.** `SOURCE CODE EVIDENCE` (supersedes the historical "no binding" finding — Appendix A §A.2):

- The VPN `Network` is captured by the system-VPN watcher in `GlobalVpnTracker.kt:432`–`:437` and exposed via `currentVpnNetwork()` (`:397`).
- `productionBoundFetcher()` (`VpnServerRepository.kt:143`–`:147`) builds an OkHttp client on `network.socketFactory` (`boundToSocketFactory`, `HttpFetcher.kt:132`–`:133`). The process-wide `bindProcessToNetwork()` is **never** used (honouring §7 Q2).
- Path order (landed v1.0.27, commit `c4c0311`): `resolvePrimary()` picks the primary rung from the user opt-in **only** (`PREF_ALWAYS_PREFER_VPN`, default `false` → OPEN); `fetchOnChosenPath()` tries the primary and falls back to the other rung only when it is reachable, emitting `[fetch]` diagnostics.
- The emergency rung is **open-network only** and runs **last** — only when the primary fetch returned `null` (`ServerListUpdater.kt:271`–`:287`; `EmergencySourceFetcher`).
- Manual import is user-triggered, never automatic (§4 design; implemented v1.0.22/v1.0.26).

### 1.3 Where a VPN `Network` object is reachable by the downloader, and the usability signal

- **Reachable today:** `productionRouting()` (`VpnServerRepository.kt:112`–`:119`) reads `GlobalVpnTracker.isConnected` and `GlobalVpnTracker.currentVpnNetwork()`. `SOURCE CODE EVIDENCE`.
- **Usability signals exist:** `noteRxAdvance()` (`GlobalVpnTracker.kt:353`) is called from `SoftEtherClient/.../controller/ConnectionController.kt:1157`, and `noteTunEio()` from `SoftEtherClient/.../terminal/TunTerminal.kt:171`; `isTunnelUsable()` (`GlobalVpnTracker.kt:372`–`:373`) is implemented. `SOURCE CODE EVIDENCE`.
- **Gap:** `productionRouting()` does **not** consume `isTunnelUsable()` — the VPN rung's qualification is "connected + bound network present", not measured tunnel health. The freshness/health gating of §2.2/§7 Q1 therefore remains **design, not implemented**. `SOURCE CODE EVIDENCE` (at HEAD `df7b49c`).

### 1.4 Current retry, timeout, and error-handling behavior

| Concern | Current behavior | Anchor |
|---|---|---|
| Connect timeout | 10 s | `HttpFetcher.kt:106`–`:113` |
| Read timeout | 15 s | `HttpFetcher.kt:106`–`:113` |
| Redirect handling | hand-followed, per-hop policy check, `maxHops` via `RedirectPolicy` | `HttpFetcher.kt:167`–`:243` |
| DNS failure | `ResilientDns` fallback IPs | `HttpFetcher.kt:55`–`:107` |
| Per-source race deadlines | 12 s (html/api), 8 s (mirror discovery) | `VpnGateSources.kt:171`,`:188`,`:215` |
| Emergency fetch | 2 attempts per source, 40 s deadline, 1 MiB body cap | `EmergencySourceFetcher.kt:141`,`:151`,`:168` |
| HTTP/parse/integrity failure | `Outcome.Preserved` with a `Reason` (never treated as success) | `ServerListUpdater.kt` (`evaluate` `:326`, write `:336`) |
| Concurrent update | `Outcome.SkippedConcurrent`; a second caller awaits the running one | `ServerListUpdater.kt:174`,`:253` |

- There is still **no per-path open-vs-VPN timeout/retry ladder**; §2.6 remains design. `SOURCE CODE EVIDENCE`.
- **Dead constants:** `MAIN_TIMEOUT_MS` / `MIRROR_TIMEOUT_MS` (`VpnServerRepository.kt:81`–`:82`) are declared but never referenced; the effective deadlines live in `VpnGateSources.kt`. `SOURCE CODE EVIDENCE`.

### 1.5 Current source-list configuration

- Canonical HTML/API URLs are compile-time constants (`VpnGateSources.kt:22`–`:25`); bootstrap mirrors remain hard-coded `http://IP:PORT` entries.
- `SourceKind` now has seven members including **`EMERGENCY_CSV`** and **`MANUAL`** (`SourceIdentity.kt:12`–`:41`) — the historical "no `EMERGENCY_CSV` or `MANUAL` kind yet" finding (Appendix A §A.5) is **superseded**.
- Emergency sources are **user-configured only** (no hardcoded default URL — §7 Q4) and stored in Room: table `emergency_source_config` (`EmergencySourceConfigEntity.kt:21`–`:29`, `MIGRATION_5_6` `AppDatabase.kt:97`–`:109`).
- Manual servers are stored in Room: table `manual_server` (`ManualServerEntity.kt:20`–`:34`, `MIGRATION_6_7` `AppDatabase.kt:125`).
- Merge priority (`VpnServerMerger.kt:48`,`:62`–`:63`): `api` (0) > `html` (10) > **mirror = `emergency`** (default 20) > `manual` (30). Note the nuance against §3.3/§3.8: `canonical > emergency > manual` **holds**, but an emergency record ranks **equal to a mirror**, not above it.

## Section 2 — Retrieval Path Design (PROPOSAL, not implementation)

> **Status annotation (2026-10-18 repair, spec v1.0.29):** the line below is the original proposal
> framing and is now partly stale. **Landed:** the §2.1 rungs 1–3 exist today — OPEN is the default
> primary, VPN is an opt-in primary / reachable fallback (`resolvePrimary` + `fetchOnChosenPath`,
> v1.0.27), and the emergency rung runs last when configured (v1.0.21); manual import (rung 4) is
> user-triggered (v1.0.22/v1.0.26). **Not landed:** the §2.2 usability gating (`isTunnelUsable()`
> is not consumed by production routing — §1.3), the §2.4 loop-risk detection, the §2.6 per-path
> timeout ladder, and the §2.8 `pathKind` provenance.

All of §2 is a design proposal. ~~Nothing here is implemented today; §1 shows the current chain uses default routing with a single timeout pair.~~ *(Struck in the 2026-10-18 repair — see the status annotation above; §1 now shows the landed routing.)*

### 2.1 Target sequence (proposal)

`normal open network → VPN tunnel (only if active AND usable) → emergency sources → manual import`

The orchestrator (`ServerListUpdater`) is pure JVM and already models "try, then fall back, then preserve" via `Outcome.Preserved(Reason…)`. The proposal keeps that contract: each path is an attempt; a path that cannot produce an acceptable dataset yields `Preserved` and the next path is tried. A successful path runs the same parse → `DatasetIntegrity` → atomic replace.

### 2.2 Determining whether the VPN tunnel is active and usable

Reuse `GlobalVpnTracker` (`GlobalVpnTracker.kt:68` `vpnState`, `:73` `isConnected`). "Active" = `isConnected == true`. "Usable" additionally requires a **freshness** signal the tracker does not yet expose:
- the `Network` from `onAvailable` is still the current default/VPN network (not stale after `onLost`), and
- the tunnel is not in a zero-RX / TUN-`EIO` broken state (see §2.9).

`INFERENCE`: `isConnected` alone is **not** sufficient proof of usability; a CONNECTED flag can be published while the tunnel is actually broken (the published-state reliability concern is tracked in the spec's residual register, `PROJECT_SPECIFICATION.md` §12.5.1 row 10, Axis B; the reference previously pointed at "§12.3", which contains no such text — corrected in the 2026-10-18 repair). Usability must be measured, not assumed.

### 2.3 Binding a request explicitly to the VPN `Network` (with compatibility constraints)

- Surface the `Network` observed in `GlobalVpnTracker.onAvailable` (`GlobalVpnTracker.kt:432`–`:437` at HEAD `df7b49c`; was `:122` at the original audit HEAD) into the retrieval layer.
- Bind the fetch socket to that `Network`. For `OkHttp`, the supported, compatibility-safe approach is a custom `SocketFactory`/`OkHttpClient` built on `network.socketFactory` (API 21+), rather than the process-wide `bindProcessToNetwork` (API 23+) or `bindSocket` (API 22+). `INFERENCE` — the exact API level floor should be confirmed against `minSdk` before implementation. *(Landed: implemented via `boundToSocketFactory` (`HttpFetcher.kt:132`–`:133`); at the project's `minSdk = 24` all three APIs are available, so the compatibility argument is moot but the per-request choice stands — §7 Q2.)*
- **Constraint:** binding is per-socket. Do **not** bind the whole process, because that would route unrelated app traffic through the VPN as a side effect and is explicitly out of scope.

### 2.4 Avoiding loops, leaks, or blocked requests when the VPN is up but the destination is unreachable

- Never let the VPN-bound fetch be the *only* attempt: if the VPN path fails/times out, fall back to the open path (which today already exists and works).
- Per-path timeout budget (§2.6) bounds a "stuck through the tunnel" request so it cannot hang the update.
- Do not hold the `Network` reference after `onLost` — release it so a stale `Network` cannot be bound to (avoids a leaked socket bound to a dead tunnel).
- A VPN-bound request to the VPN Gate server that itself is the tunnel's gateway must be detected as a loop risk: if the target host resolves into the tunnel's own subnet, skip the VPN path. `INFERENCE` — detection rule to be refined during implementation.

### 2.5 Fallback ordering with entry/exit conditions per path

| Order | Path | Entry condition | Exit condition |
|---|---|---|---|
| 1 | Open network (today's default fetch) | always eligible | returns an acceptable dataset → done; else fall through |
| 2 | VPN tunnel | `GlobalVpnTracker.isConnected == true` AND usability check (§2.2) passes | acceptable dataset → done; timeout/failure/broken → fall through |
| 3 | Emergency sources | enabled user-configured sources exist | first acceptable dataset → done; none acceptable → fall through |
| 4 | Manual import | user-triggered, not automatic | user adds record(s) |

Rationale for open-first: it is the only path proven to work today and does not depend on tunnel health. `INFERENCE`.

### 2.6 Timeout and retry policy per path

`INFERENCE` (today has one pair: 10 s connect / 15 s read, `HttpFetcher.kt:97`–`:98`):

- Open: keep existing 10 s / 15 s.
- VPN: a *shorter* budget (e.g. 8 s / 10 s) because a broken tunnel fails fast is preferable to a long hang; on failure, do not retry the VPN path twice — fall through immediately.
- Emergency: existing pair; retry each enabled source once.
- A single end-to-end deadline caps the whole update so a cascading fallback cannot run unbounded.

### 2.7 Data-integrity rule — degraded/partial must NOT replace known-good

Preserve the existing invariant exactly (`ServerListUpdater.kt:29`, `DatasetIntegrity.kt:198`): any path that yields an empty, truncated, or below-threshold dataset produces `Outcome.Preserved`, never a replace. Thresholds that must keep holding for **every** path including emergency/manual:
- `MIN_ACCEPTABLE_DATASET_SIZE = 10` (`DatasetIntegrity.kt:125`)
- `MIN_RELATIVE_DATASET_RATIO = 0.5` (`:128`)
- `MIN_PROTOCOL_COVERAGE_RATIO = 0.5` / `BASELINE = 20` (`:131`,`:134`)
- `MIN_PROFILE_COVERAGE_RATIO = 0.5` / `BASELINE = 20` (`:137`,`:140`)

`SOURCE CODE EVIDENCE` for thresholds; `INFERENCE` that they apply unchanged to new paths.

### 2.8 Provenance — each record records which path produced it

Extend the existing `SourceKind`/`sourceTag` model (`SourceIdentity.kt:12`, `:75`) with path provenance, e.g. a `pathKind` of `OPEN_NETWORK` / `VPN_TUNNEL` / `EMERGENCY` / `MANUAL` carried alongside the existing `sourceTag`. Merge policy `api > html > mirror` (`SourceIdentity.kt:41`) must be preserved; new path kinds must never be silently promoted above canonical. `INFERENCE` — additive design.

### 2.9 Requests when the VPN tunnel is up but broken (TUN EIO, zero RX)

- Treat "CONNECTED + zero RX over N seconds" or "TUN write returns `EIO`" as **not usable** and skip the VPN path. This requires a health signal beyond `isConnected`; today `GlobalVpnTracker` does not expose RX/TUN-write health, so this is a **new, minimal** dependency (see §7). `INFERENCE`.
- Never fabricate a success from a broken tunnel: the path must fall through to open/emergency, and if nothing succeeds, the result stays `Preserved` (no false "updated").

## Section 3 — Emergency CSV Source Design (PROPOSAL)

> **Superseded-in-part (2026-10-18 repair, maintainer-confirmed):** the concrete format choices in
> **§3.1 and §3.2** — the `# schema_version=1.0` metadata header row, the `SETcpPort` / `SEUdpPort`
> column names, IPv4/IPv6 acceptance, and "SE ports are required" — are **SUPERSEDED by §10**, which
> the maintainer approved as the design basis in spec v1.0.20 (`PROJECT_SPECIFICATION.md` §11.9.3) and
> which was implemented in v1.0.21 (`EmergencyCsvParser.kt`): pure CSV with a header row (no comment
> lines, §10.7), snake-case columns (`SE_TCP_Port` / `SE_UDP_Port`, §10.2), IPv4-only identity with
> `Has_IPv6` as metadata (§10.4/§10.5), required = `HostName`/`IP`/`schema_version`/`SourceTag` only.
> Wherever §3 and §10 disagree, **§10 wins**. The conceptual content of §3.4–§3.12 (supported-vs-usable
> split, the §3.5 RUDP warning, per-field provenance, fail-closed integrity, partial-import behavior)
> remains valid design and is consistent with §10 and the implementation. Note also that the merge
> priority implemented for emergency records equals mirrors (`VpnServerMerger.kt:48` default 20), so
> §3.3/§3.8's `canonical > emergency > manual` holds but emergency-vs-mirror is a tie (§1.5).

Design target: a compact, self-describing, field-level-provenance emergency CSV that merges cleanly with the existing `VpnRecords`/`SourceIdentity` model and honors the RUDP port semantics. ~~**Design only — no format is approved yet** (consistent with the specification's standing position that the emergency CSV schema is undefined).~~ *(Struck in the 2026-10-18 repair: the §10 format was approved in spec v1.0.20 and implemented in v1.0.21.)*

### 3.1 Schema version

- Format declares an explicit version token `schema_version` in a metadata header row (e.g. `# schema_version=1.0`). Start at **`1.0`**.
- Parsers must reject any row/body whose declared version is greater than the build's supported version (fail-closed) rather than silently mis-parsing.
- `SOURCE CODE EVIDENCE`: today's canonical parser already keys off a `#HostName,IP,...` header line (`VpnGateApiParser.kt:24`), so a `#`-prefixed metadata line is consistent with existing parsing.

### 3.2 Required vs optional columns

**Required** (a row missing any of these is rejected):
- `HostName` — `VpnGateApiParser.kt:140` rejects empty hostnames.
- `IP` — must be a valid IPv4/IPv6 (`VpnUtil.validIp`, `VpnGateApiParser.kt:141,143`).
- `SETcpPort` — SoftEther TCP port (integer; may be `0` for UDP-only).
- `SEUdpPort` — SoftEther UDP port (integer; see §3.5 warning).

**Optional** (absent → field simply not contributed; never fabricated):
- `CountryShort`, `Score`, `Ping`, `NumVpnSessions`, `OpenVPN_ConfigData_Base64`, OpenVPN `UdpPort`/`TcpPort`, `SSTPSupported`, `L2TPSupported`, `SSTPPort`, `L2TPPort`.
- Unknown extra columns are ignored (forward-compat) but recorded for provenance.

### 3.3 Server identity normalization (deduplication key)

- `HostName`: trimmed, lowercased, trailing dot removed (`VpnGateApiParser.kt:147` already lowercases).
- `IP`: canonicalized via `VpnUtil.normalizeIp` (`VpnGateApiParser.kt:141`); IPv6 expanded to a single canonical textual form.
- Canonical dedup key = `(normalizedIP)` when present, else `(normalizedHostName)`. IP is preferred as the stable identity because hostnames can be aliased.
- Dedup preserves the record with the highest-priority source (canonical > emergency > manual) and merges non-conflicting fields.

### 3.4 Protocol semantics — "collector-supported" vs "app-usable"

Two distinct booleans must be kept separate (never conflated):
- `supported` (collector advertises the protocol exists) — maps to `VpnRecords.markSupported(...)` (`VpnGateApiParser.kt:208`).
- `usable` (the app can actually build and establish a connection) — a separate, evidence-gated flag the app sets only after a reachability/usable check, never from CSV alone.

Protocols:
- **SoftEther TCP** (`SETcpPort`): usable only if `SETcpPort > 0`.
- **SoftEther UDP / R-UDP** (`SEUdpPort`): see §3.5 — do NOT mark usable from the column alone.
- **OpenVPN UDP/TCP**: derived from `OpenVPN_ConfigData_Base64` family (`VpnGateApiParser.kt:249-266` sets tcp/udp `supported` + `port` + `portSource`). Independent of SoftEther R-UDP.
- **SSTP / L2TP-IPsec**: separate listeners; mark `supported` if advertised, `usable` only after an app-side test.
- `INFERENCE` on the supported/usable split; `SOURCE CODE EVIDENCE` that the parser currently records `supported` only.

### 3.5 Port semantics — RUDP warning (MANDATORY)

> **CRITICAL (from `SoftEtherClient/RUDP_IMPLEMENTATION_PLAN.md:7,26`):** OpenVPN UDP and SoftEther R-UDP are **separate independent listeners**. The CSV column `SEUdpPort` does **NOT** mean SoftEther R-UDP is available on that port. For **"UDP-only" servers (`SETcpPort = 0`)** the R-UDP/NAT-T transport is **broken** — the only working UDP listener is **OpenVPN UDP** (from `OpenVPN_ConfigData_Base64`). `RUDP_IMPLEMENTATION_PLAN.md:51`: the only viable path for a UDP-only server is the **OpenVPN fallback**.

Rules:
- Treat `SETcpPort` and `SEUdpPort` as **separate** transports, never interchangeable.
- `seTcpPort = 0` **and** `SEUdpPort > 0` ⇒ SoftEther R-UDP is **NOT usable**; do not surface SoftEther-UDP as a viable protocol. OpenVPN UDP (if config present) remains usable.
- Never fabricate or infer a port. If a protocol's port is unknown, leave it `UNKNOWN` (do not default to a well-known port).
- `SOURCE CODE EVIDENCE` (`RUDP_IMPLEMENTATION_PLAN.md:7,16-20,26,51`).

### 3.6 Per-column validation rules

- `HostName`: non-empty, valid DNS label set.
- `IP`: passes `VpnUtil.validIp` (`VpnGateApiParser.kt:143`); row skipped otherwise.
- Ports (`SETcpPort`, `SEUdpPort`, OpenVPN/SSTP/L2TP ports): integer in `0..65535`; `0` is a valid, meaningful value for SE ports (UDP-only), NOT "missing".
- `Score`: non-negative integer; `Ping`: non-negative float (ms); `NumVpnSessions`: non-negative integer.
- `OpenVPN_ConfigData_Base64`: valid base64 that decodes via `OpenVpnConfigParser.decode` (`VpnGateApiParser.kt:211`); if it fails to decode, the record is retained but `openvpn` is left `supported=false` (config unusable) rather than dropping the whole row.
- Rows that fail a **required** field are rejected and reported; rows that fail only an **optional** field are retained with that field omitted.

### 3.7 Source provenance per record

- Every record carries the producing `SourceIdentity` (`SourceIdentity.kt`). Emergency records get a dedicated, non-canonical `sourceTag` (never `"api"`/`"html"`), mirroring the existing mirror model (`SourceIdentity.kt:41-43,71-73`).
- Each contributed field records its source through `VpnRecords.setField(..., source)` (`VpnGateApiParser.kt:147-152`) so field-level provenance survives merges.
- The retrieval **path** provenance (open / VPN / emergency / manual) is layered on per §2.8.

### 3.8 Duplicate handling

- Within one CSV: later rows with the same dedup key are merged; conflicting scalar fields keep the highest-priority path's value; ties keep the first occurrence and log a warning.
- Across sources: canonical (api/html) always wins over emergency over manual (mirrors the `api > html > mirror` policy at `SourceIdentity.kt:41`).
- `INFERENCE` — aligns to existing merge policy, no policy change proposed.

### 3.9 Partial import behavior

- Accept all rows that pass required-field validation; reject only the invalid rows.
- Never abort the whole import because some rows are malformed (this is how the canonical parser already behaves — it `continue`s on bad rows, `VpnGateApiParser.kt:143`).
- Record counts: `accepted`, `rejected`, `rejectedReasons` for the integrity report (§3.10, §3.11).

### 3.10 Integrity checks for acceptance

A parsed emergency CSV is acceptable only if **all** hold:
- `schema_version` ≤ supported version.
- At least one record passed required-field validation.
- The candidate result does not cause the merged dataset to regress against the active `DatasetIntegrity` threshold (§2.7). A degraded/partial result must **not** replace a known-good dataset.
- No structural parse error indicating truncation/corruption (unbalanced header, zero data rows, mid-file encoding break).
`SOURCE CODE EVIDENCE` for the integrity-gate concept (`DatasetIntegrity.kt`, `ServerListUpdater.kt`); exact thresholds carried over, not redesigned here.

### 3.11 Error reporting — format and levels

- Structured result: `status` = `ACCEPTED_FULL` / `ACCEPTED_PARTIAL` / `REJECTED_INTEGRITY` / `REJECTED_PARSE`; plus `acceptedCount`, `rejectedCount`, `rejectedReasons: Map<Reason, Int>`, `declaredSchemaVersion`.
- Levels: `INFO` (row skipped), `WARN` (partial accept, unknown column, schema newer than supported → actually `REJECTED`), `ERROR` (parse/integrity reject).
- Never log raw server payloads beyond non-sensitive field values (no base64 config bodies).

### 3.12 Compatibility with DatasetIntegrity / Room / snapshot / UI

- Parsed records flow through the same `VpnServerMerger` (`app/src/main/java/vn/unlimit/vpngate/merger/VpnServerMerger.kt`) and `ServerListUpdater` write path (`ServerListUpdater.kt`) as canonical data — no separate store.
- Room persistence + `CollectorSnapshotStore` snapshot + `ConnectionListViewModel` exposure are unchanged (`CollectorSnapshotStore.kt`, `ConnectionListViewModel.kt`); emergency records appear in the UI indistinguishably except for an optional provenance marker.
- `INFERENCE` — reuse of the existing merge/persist/observe chain; no schema migration is proposed in this phase.

## Section 4 — Manual Server Import Design (OUTLINE ONLY)

- **4.1 User input:** a single server record (host/IP + at least one protocol+port) OR a small CSV in the §3 emergency format OR a URL. A single-record form is the minimum viable UX.
- **4.2 Validation & provenance:** identical rules to §3.6/§3.7; manual records get the lowest merge priority (`SourceIdentity.kt:41` policy) and a distinct `sourceTag`; never promoted above canonical.
- **4.3 Retention across refreshes:** manual records are user-owned and must survive every `DatasetIntegrity` refresh/merge (§2.7 guarantees known-good data is not destroyed). They are re-emitted on each merge as long as they remain valid.
- **4.4 Shared integrity:** manual import reuses the emergency CSV integrity rules (§3.10) and error reporting (§3.11); no parallel rule set.

## Section 5 — Future Extensibility (OUTLINE ONLY)

- **5.1 Provider abstraction:** a `ServerSourceProvider` interface (fetch → parse → `List<VpnRecord>` + `SourceIdentity`) so canonical / mirror / emergency / manual are interchangeable implementations behind one orchestration seam in `ServerListUpdater`. `INFERENCE` — new abstraction, not implemented here.
- **5.2 Trust model:** **no implicit trust** in any public host (GitHub, mirrors, pastebins). A source is trusted only to the extent it passes §3.10 integrity AND the retrieved data survives the `DatasetIntegrity` gate. Public hosting confers no authority.
- **5.3 Reachability model compatibility:** the schema must not collapse `REACHABLE / UNREACHABLE / UNKNOWN`. `UNKNOWN` is never treated as `UNREACHABLE`; a server with one unreachable protocol but another reachable usable protocol stays eligible (a server is excluded only when **all** measurable usable protocols are definitively unreachable). Ports/protocols with unknown status are marked `UNKNOWN`, not excluded. `SOURCE CODE EVIDENCE`: `ServerReachabilityTester.kt`; `INFERENCE` on the exact gating integration.

## Section 6 — Physical Test Plan (DESIGN-LEVEL)

- **6.1 Three-condition test:** (a) Update **before** VPN; (b) Update **while a real VPN tunnel is confirmed active**; (c) Update **after** normal VPN disconnect. Compare results.
- **6.2 Evidence required to prove VPN routing (not just HTTP success):** capture (i) `tun0` interface state, (ii) the `Network`/`Capabilities` the request actually bound to, (iii) a server-side or server-list echo/version marker showing the response came through the tunnel, (iv) `protect()`/bind call traces if present. A 200 OK alone proves nothing about the path.
- **6.3 Classification per scenario:**
  - `CONFIRMED VPN ROUTED` — routing evidence (6.2) present;
  - `CONFIRMED NOT VPN ROUTED` — evidence shows default/other network used;
  - `LIKELY VPN ROUTED — INSUFFICIENT PROOF` — tunnel active + success but no direct routing proof;
  - `NOT OBSERVABLE` — no reliable path evidence obtainable;
  - `NOT TESTABLE` — no confirmed active tunnel exists to test against.

## Section 7 — Dependencies and Open Questions

> **Status: ALL EIGHT RESOLVED (F.28AL-Z-DESIGN-UPDATE).** Each original open question is retained below and marked **RESOLVED** with the user-directed decision. Evidence class: design decisions are **USER-DIRECTED REQUIREMENT** (policy choices) with implementation-level notes as **INFERENCE**.
>
> **Landed-status annotation (2026-10-18 repair, verified at HEAD `df7b49c`):** Q1 — the RX/EIO counters **now exist** and are fed (`noteRxAdvance` from `ConnectionController.kt:1157`, `noteTunEio` from `TunTerminal.kt:171`) and `isTunnelUsable()` is implemented, **but production routing does not consume it** (§1.3); Q2 — **landed** (`boundToSocketFactory`); Q3 — **landed** (`resolvePrimary` + `fetchOnChosenPath`, v1.0.27); Q4 — **landed** (user-configured only, Room `emergency_source_config`); Q7 — **landed** (fail-closed `schema_version` in `EmergencyCsvParser.kt:51`); Q8 — **landed** (migrations 5→6→7, device-verified v1.0.24). Q5 (a computed `usable` gate) and Q6 (the "Configure Emergency Source" prompt) are **not verified as implemented — treat as OPEN**.

1. **VPN-tunnel usability signal** — ~~RESOLVED~~ → Define `isTunnelUsable()` requiring **all three**: (a) VPN service state == `CONNECTED`, (b) RX counter advanced within the last **10 s**, (c) no TUN `EIO` in the last **30 s**. Requires a **new RX/EIO counter in `GlobalVpnTracker`** — **future code work, not this phase**. *USER-DIRECTED REQUIREMENT (signal definition); INFERENCE (tracker change).*
2. **Explicit bind mechanism** — ~~RESOLVED~~ → Use **per-request `Network.getSocketFactory()` on a dedicated OkHttp client for the VPN path**. Do **NOT** use `bindProcessToNetwork()` (too broad, process-wide). Minimum SDK **API 24** *(corrected in the 2026-10-18 repair — this line previously read "API 21", which is the API-availability floor of `Network.getSocketFactory()`, not the project minimum; `minSdkVersion = 24` in `build.gradle`, spec §1.1)*; target device **API 33**. *USER-DIRECTED REQUIREMENT; SOURCE-compat note is INFERENCE.*
3. **Default routing policy** — ~~RESOLVED~~ → Order: **open network first → VPN as fallback → emergency → manual**. A user preference may invert this to **"always prefer VPN"**, but that is an **opt-in setting, not the default**. *USER-DIRECTED REQUIREMENT.*
4. **Emergency source URL(s) & trust policy** — ~~RESOLVED~~ → **No hardcoded default URL; user-configured only. No implicit trust** in any source. `DatasetIntegrity` applies before **any** replacement (§3.10, §9.6). *USER-DIRECTED REQUIREMENT.*
5. **`usable` vs `supported` computation** — ~~RESOLVED~~ → `supported` = **static / parse-time** (format compatibility). `usable` = **dynamic / dial-time** (protocol/port responsiveness). Aligns with the `REACHABLE / UNREACHABLE / UNKNOWN` model (§3.4, §5.3). *USER-DIRECTED REQUIREMENT.*
6. **Fallback when both open and VPN fail** — ~~RESOLVED~~ → If an **emergency source is configured** → automatic attempt. If **not configured** → show a user message with a **"Configure Emergency Source"** action. **No silent block.** *USER-DIRECTED REQUIREMENT.*
7. **Schema-version negotiation** — ~~RESOLVED~~ → **No negotiation.** `schema_version: 1.0` only. Higher versions → **fail-closed** with a recorded reason (§3.1, §3.11). *USER-DIRECTED REQUIREMENT.*
8. **Room migration** — ~~RESOLVED~~ → **Scope in the implementation phase, not here.** Requires reading the actual Room schema first (§3.12, §9.2, §9.5). *USER-DIRECTED REQUIREMENT.*

## Section 8 — Implementation Phase Outline (NOT implementation)

> **Status annotation (2026-10-18 repair, spec v1.0.29):** the sequencing below is the original
> design-phase outline; implementation has since proceeded **out of this order and without the
> Phase-1 abstraction**. Verified at HEAD `df7b49c`: **Phase 1** (a `ServerSourceProvider` interface
> and `pathKind` provenance) — **NOT LANDED**; **Phase 2** (VPN-aware retrieval) — **PARTIALLY
> LANDED** as explicit per-request binding + OPEN-first/VPN-fallback (`a9e16fd`, `c4c0311`) but
> **without** the §7.1 usability gating and **without** a feature flag; **Phase 3** (emergency CSV
> parser + integrity) — **LANDED** v1.0.21; **Phase 4** (manual import) — **LANDED** as a
> single-record form with refresh-retention (v1.0.22/v1.0.26); CSV/URL import remains open
> (roadmap item F); **Phase 5** (three-condition physical test) — **PARTIAL**: the C4 baseline was
> device-verified in v1.0.28, the "tunnel up" and fallback conditions remain NOT TESTABLE without
> operator actions. Roadmap item statuses: `PROJECT_SPECIFICATION.md` §12.5.2.

Proposed sequencing (each phase independently testable; none started in this design phase):
- **Phase 1 — Provider abstraction + provenance plumbing:** introduce `ServerSourceProvider` and layer `pathKind` onto `SourceIdentity`/`sourceTag` (§4/§5.1, §2.8). No behavior change to existing fetch.
- **Phase 2 — VPN-aware retrieval:** add VPN-usability signal (§7.1) and explicit bind (§7.2); implement §2 path order with fail-closed integrity (§2.7). Gate behind a flag for A/B physical testing (§6).
- **Phase 3 — Emergency CSV parser + integrity:** implement §3 parser, validation, integrity gate, and error reporting; wire into `VpnServerMerger`/`ServerListUpdater`.
- **Phase 4 — Manual import UI + retention:** single-record/CSV/URL import with §4 rules and refresh-retention.
- **Phase 5 — Physical verification:** execute §6 three-condition test; classify per §6.3.
- **Dependencies:** 2 depends on 1; 3 depends on 1; 4 depends on 3 (schema) and 1; 5 depends on 2 and 3.

## Section 9 — Cumulative Server Database Retention (USER-DIRECTED REQUIREMENT)

> **Currency note (2026-10-18 repair):** **still NOT IMPLEMENTED** at HEAD `df7b49c` — verified in source: `VPNGateItem.kt:9`–`:41` carries no `firstSeenAt` / `lastSeenAt` / `lastSuccessfulConnectionAt` / `healthScore` fields, and `DatasetOutcome`'s `ACCEPT_NEW` / `MERGE_WITH_EXISTING` are names/logs only (v1.0.23); the update still **replaces**. The requirement status is unchanged: approved as a user-directed requirement, not scheduled into an implementation phase. §9.7's open questions all remain **UNKNOWN**.

> Evidence class: functional requirements below are **USER-DIRECTED REQUIREMENT**; concrete field types, defaults, and algorithm mechanics are **INFERENCE** (proposed design). No schema, code, or behavior is implemented by this document.

### 9.1 Functional Requirements

- **Cumulative addition only.** An update **adds** new/updated servers to the local database; it must **not** automatically remove existing healthy servers. The database grows across updates rather than being replaced by the latest snapshot.
- **Do not lose healthy servers** when a source returns a smaller set. A shrunken response is treated as `MERGE_WITH_EXISTING` (§9.6), never as a signal to prune previously-seen healthy records.
- **Configurable cap.** A Settings option sets the **maximum server count** in the local database. Suggested default **1000**; allowed range **100–10000**.
- **Removal is permitted only when:**
  1. **Cap overflow** — the count exceeds the configured maximum, and records are dropped per the retention-priority algorithm (§9.3); **OR**
  2. **Staleness** — a record has been absent from every source for **N consecutive days AND has had no successful connection in the same period** (default **N = 30**).
- No other automatic removal is allowed. *USER-DIRECTED REQUIREMENT (rules); INFERENCE (default N=30, range 100–10000).*

### 9.2 Proposed Room Schema Additions

New fields on the `VpnServer` entity (requires a **Room migration**, §9.5 — planned here, not implemented):

| Field | Type | Purpose |
|---|---|---|
| `firstSeenAt` | `Long` | First time this server appeared in any source (epoch ms). |
| `lastSeenAt` | `Long` | Most recent appearance in any source (drives staleness). |
| `lastSuccessfulConnectionAt` | `Long?` | Last confirmed successful connection; null if never. |
| `lastFailureAt` | `Long?` | Last connection failure; null if never. |
| `successCount` | `Int` | Cumulative successful connections. |
| `failureCount` | `Int` | Cumulative connection failures. |
| `consecutiveFailures` | `Int` | Current failure streak (resets on success). |
| `protocolSupportScore` | `Int` | Count/weight of usable protocols the server supports (§9.3 rank 2). |
| `healthScore` | `Int` | Composite health metric (§9.3 rank 1; see §9.7 for definition). |

These fields support the retention priority and the Database Status display (§9.4). *INFERENCE (types/purposes); the need for a migration is USER-DIRECTED via Q8.*

### 9.3 Retention Priority Algorithm

Applied **only when the configured cap is exceeded** (cap-overflow path of §9.1). **Do NOT apply priority-based removal on every update** — normal updates are pure cumulative additions.

When the cap is exceeded, drop the **lowest-ranked** records first, ranking ascending by, in this exact order:

1. **`healthScore`** (lowest health dropped first)
2. **`protocolSupportScore`** (fewer usable protocols dropped first — tie-break)
3. **`lastSuccessfulConnectionAt`** (oldest/never-successful dropped first — final tie-break)

Staleness removal (§9.1 item 2) is independent of this priority order and governed solely by the N-days + no-success rule. *USER-DIRECTED REQUIREMENT (exact order); INFERENCE (ascending-drop mechanics).*

### 9.4 Settings UI Proposal

- **Max Server Count** — a slider/spinner bounded **100–10000**, default **1000** (writes the cap consumed by §9.1/§9.3).
- **Retention Policy summary** — a read-only display of the active retention rules (priority order §9.3 + staleness window N=30 days), so the user can see what will be kept/dropped.
- **Database Status** — a read-only breakdown of the local database: **total / healthy / stale / unsupported** counts (derived from the §9.2 fields).
*USER-DIRECTED REQUIREMENT (controls); INFERENCE (bounds/defaults).*

### 9.5 Room Migration Outline

- Introduce a **new migration N → N+1** in the implementation phase.
- Provide **safe default values for existing records** on migration (e.g. `firstSeenAt = lastSeenAt = migrationTime`, `healthScore/protocolSupportScore = 0`, counters `= 0`, `lastSuccessfulConnectionAt/lastFailureAt = null`).
- **NOTE:** this migration is **planned here, not implemented** (consistent with Q8 — the actual Room schema must be read first). *INFERENCE.*

### 9.6 Interaction with DatasetIntegrity

Extend the integrity gate (§2.7, §3.10) with three new classification outcomes for an incoming dataset:

- **`ACCEPT_NEW`** — a healthy dataset containing new servers: add all new records.
- **`MERGE_WITH_EXISTING`** — a partial but valid dataset (or a smaller set): **add new records only**; never drop existing healthy servers.
- **`REJECT_DEGRADED`** — a degraded/corrupt dataset: **reject; the database is left untouched.**

A degraded dataset must **never** replace or prune a known-good one. These outcomes layer on top of the existing `DatasetIntegrity` threshold logic rather than replacing it. *USER-DIRECTED REQUIREMENT (three outcomes); INFERENCE (layering on existing gate).*

### 9.7 Open Questions (new)

To be resolved before implementation:

1. **`healthScore` definition** — exact formula/inputs (e.g. latency, uptime, session count, recent success ratio) is **UNKNOWN** and must be defined; the current `VpnServer` schema does not carry a health metric today.
2. **`protocolSupportScore` weighting** — whether all usable protocols count equally or weighted by throughput/reliability (cf. `RUDP_IMPLEMENTATION_PLAN.md` throughput ranking) is **UNKNOWN**.
3. **Staleness N** — is 30 days the fixed default, or user-configurable? (Design assumes a default of 30; user-configurability is a UI decision.)
4. **Interaction of cap-overflow vs. staleness** when both fire in the same update — proposed: apply staleness first, then cap-priority only if still over. **UNKNOWN — needs confirmation.**
5. **Room migration number** — the concrete N → N+1 depends on the current schema version (per §7 Q8, requires reading the actual Room schema). **UNKNOWN.**


---

## Section 10 — Extended Server CSV Schema for Emergency Source (`servers.csv`)

> **Implementation status (2026-10-18 repair):** §10 is **approved as the design basis** (spec v1.0.20, §11.9.3) and **implemented** in v1.0.21 (`EmergencyCsvParser.kt`, `EmergencySourceFetcher.kt`, opt-in last-rung wire-in). A real data asset exists at `emergency-sources/servers.csv` (v1.0.24; 3-row point-in-time snapshot, 29 columns). Of §10.9's open questions: **Q2 (column ordering) is settled by the implementation** — the parser is header-driven, so column order is not load-bearing; **Q1, Q3, Q4 and Q5 remain UNKNOWN**. This section remains the canonical CSV contract; where §3 conflicts with it, §10 wins (§3 banner).

> Evidence class: column names and the base-15 are **USER-DIRECTED REQUIREMENT** (supplied and cross-checked against the VPN Gate iPhone API); extended-column semantics are **USER-DIRECTED REQUIREMENT** with validation/encoding choices as **INFERENCE**; the RUDP constraint is **SOURCE CODE EVIDENCE** (`RUDP_IMPLEMENTATION_PLAN.md` lines 7, 26, 51). No CSV file or generator is produced by this phase.

### 10.1 Purpose and Constraints

- **Why it exists:** a static, human-authored `servers.csv` serves as the **emergency fallback** (§2.1) when the live VPN Gate API/HTML is unreachable and no in-app source responds. It is the "known-good dataset" that the fail-closed integrity gate (§2.7, §3.10, §9.6) protects.
- **Where it is hosted:** a **public GitHub repository**, consumed via its **raw URL** (e.g. `raw.githubusercontent.com/.../servers.csv`). Hosting on GitHub confers **no trust** — the file passes through the same `DatasetIntegrity` gate and the no-implicit-trust rule of §5.2/Q4. *USER-DIRECTED REQUIREMENT (hosting) + INFERENCE (no-trust treatment).*
- **How the app consumes it:** exclusively through the **emergency-source path** defined in §2 (open → VPN → **emergency** → manual). The user must explicitly configure the raw URL; there is **no hardcoded default** (Q4). Parsing reuses the emergency-CSV pipeline of §3, extended for the columns in §10.2.

### 10.2 Full Column Specification

All columns. The **base 15 preserve the VPN Gate iPhone API names exactly** (no renaming) so a single parser can consume both the live API feed and `servers.csv` (dual-purpose parsing). *USER-DIRECTED REQUIREMENT.*

| # | Column | Type | Req | Source | Description |
|---|---|---|---|---|---|
| 1 | `HostName` | string | **R** | api_iphone | Gateway hostname (may end in `:port`). Non-empty. |
| 2 | `IP` | string (IPv4) | **R** | api_iphone | Primary identity key (see §10.5). Must be valid IPv4. |
| 3 | `Score` | int | O | api_iphone | VPN Gate quality score. |
| 4 | `Ping` | int (ms) | O | api_iphone | Measured ping. Unknown → empty. |
| 5 | `Speed` | int (bps) | O | api_iphone | Measured throughput. Unknown → empty. |
| 6 | `CountryLong` | string | O | api_iphone | Full country name. |
| 7 | `CountryShort` | string (2-letter) | O | api_iphone | ISO country code; validate 2 letters if present. |
| 8 | `NumVpnSessions` | int | O | api_iphone | Active sessions. |
| 9 | `Uptime` | int (ms) | O | api_iphone | Reported uptime. |
| 10 | `TotalUsers` | int | O | api_iphone | Cumulative users. |
| 11 | `TotalTraffic` | int (bytes) | O | api_iphone | Cumulative traffic. |
| 12 | `LogType` | string | O | api_iphone | Logging policy token. |
| 13 | `Operator` | string | O | api_iphone | Operator name. |
| 14 | `Message` | string | O | api_iphone | Operator banner message. |
| 15 | `OpenVPN_ConfigData_Base64` | base64 | O | api_iphone | Embedded OpenVPN config. Must decode or OpenVPN marked unsupported (§10.4). |
| 16 | `schema_version` | string ("1.0") | **R** | computed | File schema version. Only `1.0` accepted; higher → fail-closed (§7 Q7). |
| 17 | `SE_TCP_Port` | int | O | html_parse | SoftEther SSL-VPN **TCP** port. `0` = not present. |
| 18 | `SE_UDP_Supported` | bool | O | html_parse | SoftEther SSL-VPN **UDP** support. `true` only if HTML shows "UDP: Supported" (§10.3). |
| 19 | `SE_UDP_Port` | int | O | html_parse | SoftEther SSL-VPN **UDP** port. `0` = not present. **See RUDP warning, §3.5.** |
| 20 | `OpenVPN_TCP_Port` | int | O | html_parse | OpenVPN **TCP** port. `0` = not present. |
| 21 | `OpenVPN_UDP_Port` | int | O | html_parse | OpenVPN **UDP** port. `0` = not present. |
| 22 | `L2TP_Supported` | bool | O | html_parse | L2TP/IPsec availability. |
| 23 | `SSTP_Supported` | bool | O | html_parse | SSTP availability. |
| 24 | `SSTP_Hostname` | string | O | html_parse | SSTP host; may carry `:port` suffix if port ≠ 443 (§10.3). |
| 25 | `SSTP_Port` | int | O | html_parse | SSTP port (typically 443). `0` = not present. |
| 26 | `Has_IPv6` | bool | O | html_parse | Whether an IPv6 endpoint was observed. |
| 27 | `FirstSeenAt` | long (epoch ms) | O | computed | First observation time (see §9.2 `firstSeenAt`). |
| 28 | `LastSeenAt` | long (epoch ms) | O | computed | Most recent observation time (see §9.2 `lastSeenAt`). |
| 29 | `SourceTag` | string enum | **R** | computed | One of `api_iphone` \| `html_parse` \| `merged` (§10.6). |

### 10.3 Column Semantics — Detailed Rules

- **Port columns** (`SE_TCP_Port`, `SE_UDP_Port`, `OpenVPN_TCP_Port`, `OpenVPN_UDP_Port`, `SSTP_Port`): **`0` means "not present / not offered"**. A port is **never fabricated**; if the HTML page does not state it, write `0` (not a guessed value). Valid range `0..65535`. TCP and UDP for a given protocol are **independent** listeners — the presence of one says nothing about the other. *USER-DIRECTED REQUIREMENT.*
- **`SE_UDP_Supported`**: set `true` **only** when the HTML SSL-VPN section **explicitly shows "UDP: Supported"**; otherwise `false`. Do **not** infer UDP support from the mere presence of `SE_UDP_Port`. *USER-DIRECTED REQUIREMENT.*
- **`SSTP_Hostname`**: if `SSTP_Port != 443`, the hostname **may include a `:port` suffix**, matching the VPN Gate HTML page rendering (e.g. `vpn123.example.com:8443`). If `SSTP_Port == 443`, no suffix is required. Parsers must tolerate both forms and reconcile the suffix against `SSTP_Port` when both are present. *USER-DIRECTED REQUIREMENT (suffix rule) + INFERENCE (reconciliation).*
- **Unknown-value representation:** optional numeric columns use **empty field** for "unknown" (not `0`, not the string `unknown`), **except port columns which use `0`** for "not present". Booleans are always `true`/`false` (never empty). This keeps "unknown metric" distinct from "explicit zero / not offered". *INFERENCE (encoding choice).*
- **`Has_IPv6`**: `true` only if an IPv6 address was observed for the gateway on the HTML page; the identity key remains IPv4 (`IP`, §10.5). *INFERENCE.*
> **CRITICAL — RUDP PORT WARNING (verbatim, from `RUDP_IMPLEMENTATION_PLAN.md` lines 7, 26, 51 — SOURCE CODE EVIDENCE):**
> **`SE_UDP_Port` does NOT imply SoftEther R-UDP availability on that port.** OpenVPN UDP and SoftEther R-UDP are **separate, independent listeners**. For `SETcpPort=0` ("UDP-only") servers, the **R-UDP/NAT-T transport is broken** while **OpenVPN UDP remains a separate working listener**. Therefore a record may validly carry `SE_UDP_Port>0` yet have **no usable SoftEther UDP/R-UDP path**; never treat `SE_UDP_Port` alone as proof of a usable SoftEther UDP transport, and never collapse `OpenVPN_UDP_Port` into the SoftEther UDP judgment.


### 10.4 Validation Rules Per Column

A record failing a **required** rule is rejected (counted in the `DatasetIntegrity` summary, §10.8); a record failing an **optional-decode** rule is **retained with the affected capability marked unsupported** (never silently dropped).

- **`IP`** — must match **IPv4** dotted-quad format (`0..255` per octet). Invalid IPv4 → **reject record**. (IPv6 is not an identity key here; `Has_IPv6` is metadata only.)
- **Port columns** — integer in `0..65535`; out-of-range or non-numeric → treat as `0`/not-present and mark the capability unsupported (do not reject the whole record).
- **`CountryShort`** — if present, must be exactly **2 letters** (ISO 3166-1 alpha-2); otherwise dropped to empty (optional).
- **`OpenVPN_ConfigData_Base64`** — must **successfully Base64-decode**; if it does not, the record is **retained** but OpenVPN is marked **unsupported** for it. *USER-DIRECTED REQUIREMENT.*
- **`HostName`** — must be **non-empty**; empty → **reject record**.
- **`schema_version`** — must equal `"1.0"`; any other value → **fail-closed** (§7 Q7), reason recorded.
- **`SourceTag`** — must be one of the three enum values (§10.6); unknown → default to `html_parse` for a hand-authored emergency file, reason recorded. *INFERENCE.*

### 10.5 Identity Normalization and Deduplication

- **Primary key:** `IP` (IPv4, normalized form — no leading zeros, canonical dotted-quad).
- **Secondary key:** `HostName` (case-insensitive, `:port` suffix stripped before comparison so it does not defeat dedup).
- **Same `IP`, differing fields:** **merge**, with the **more complete record winning** for each conflicting field (a field is "complete" when it is present and valid rather than empty/`0`). The merged record's `SourceTag` becomes **`merged`** (§10.6). *USER-DIRECTED REQUIREMENT.*
- This mirrors the record identity/dedup rules already used by the in-app collector/merger (§3.3, §3.8) so `servers.csv` deduplicates identically to the live path. *INFERENCE (alignment).*

### 10.6 Provenance and `SourceTag`

- **`api_iphone`** — the record (or its base columns) originated from the official VPN Gate **iPhone API** (`/api/iphone/`).
- **`html_parse`** — the record (or its extended columns) originated from **HTML-page parsing** of the VPN Gate site.
- **`merged`** — the record combines data from **both** sources (§10.5 merge, or a pipeline that unions API + HTML).
- `FirstSeenAt` / `LastSeenAt` are maintained accordingly: `api_iphone`/`html_parse` records carry the observation time of that source; a `merged` record carries the **earliest** `FirstSeenAt` and **latest** `LastSeenAt` across the combined inputs. *USER-DIRECTED REQUIREMENT (enum + semantics); INFERENCE (min/max timestamp merge).*

### 10.7 File-Level Header and Structure

- **First line** contains the **`schema_version` token and the column names** (i.e. the standard CSV header row, whose column set corresponds to §10.2). `schema_version` is also carried as a per-record column for robustness.
- **No comment lines** — unlike the raw VPN Gate API (which prefixes `*`/`#` banner lines), `servers.csv` is a **pure CSV**: header row + data rows only. This removes the banner-stripping special case. *USER-DIRECTED REQUIREMENT.*
- **Encoding:** **UTF-8 without BOM**. *USER-DIRECTED REQUIREMENT.*
- **Line endings:** **LF** (`\n`). *USER-DIRECTED REQUIREMENT.*
- **Field delimiter:** comma; fields containing commas/quotes/newlines must be RFC-4180 quoted. *INFERENCE.*

### 10.8 Compatibility with `DatasetIntegrity`

The three classification outcomes defined in §9.6 apply to `servers.csv` exactly as to any emergency dataset:

- **`ACCEPT_NEW`** — a fully valid `servers.csv` whose records are new: add all new records (cumulative, §9.1).
- **`MERGE_WITH_EXISTING`** — a **partial but valid** `servers.csv` (some rows rejected by §10.4, or a smaller set than stored): **add new records only**; never drop existing healthy servers.
- **`REJECT_DEGRADED`** — a corrupt/empty/undecodable `servers.csv`, or a wrong `schema_version`: **reject; the database is left untouched.**

A **partial `servers.csv` must never replace a known-good dataset** (§2.7, §3.10, §9.6). Record-level `SourceTag` (`api_iphone`/`html_parse`/`merged`) is persisted as per-record provenance and does **not** change the file-level accept/merge/reject decision. *USER-DIRECTED REQUIREMENT (reuse of §9.6) + INFERENCE (provenance persistence).*

### 10.9 Open Questions (new)

To be resolved before the generator script (§10.1) is built:

1. **Generator data sources** — will the generator read the live iPhone API **and** the HTML page (producing `SourceTag="merged"`), or a single source? **UNKNOWN.**
2. **Column ordering** — must the CSV column order be **fixed** (matching §10.2 exactly) or may it vary with the header row authoritative? (Design assumes the §10.2 order; header-driven parsing is an **INFERENCE**.) ~~**UNKNOWN — needs confirmation.**~~ **SETTLED BY IMPLEMENTATION (v1.0.21):** `EmergencyCsvParser.kt` is header-driven, so column order is not load-bearing (SOURCE CODE EVIDENCE; recorded in the 2026-10-18 repair).
3. **Size / row cap** — should the committed `servers.csv` be capped (e.g. top-N by `Score`/health) to keep the repo small, given the §9.1 in-app cap of 1000? **UNKNOWN.**
4. **`Score`/health refresh cadence** — how often is `servers.csv` regenerated/committed, and by what mechanism (manual vs CI)? Out of scope for CI in this phase. **UNKNOWN.**
5. **IPv6 identity** — confirm IPv6 remains **metadata only** (`Has_IPv6`) and never an identity/dedup key (§10.5). Assumed by this design; **needs confirmation.**


---

## Appendix A — Historical Section 1 audit snapshot (SUPERSEDED)

> **HISTORICAL — preserved verbatim from the original document.** This appendix is the
> Section 1 audit exactly as written at HEAD `071462816b1eef7e7f7459483fcd69909938cd11`
> (branch `main`). It was superseded on 2026-10-18 (spec v1.0.29) when Section 1 was rewritten
> against HEAD `df7b49cf1b91078622cc72774dd1b4fbba73b20a`. Do not cite this appendix as current
> behavior: several findings are now false (e.g. "no EMERGENCY_CSV or MANUAL kind yet",
> "no explicit VPN/Network binding", Room `version = 5`). It is kept for traceability only.
> The only adaptation is in headings: the original `## Section 1` heading is replaced by this
> banner, and `### 1.x` subsections are renumbered `### A.x`; the body text is byte-identical.

### A.1 The full server-list update chain as it exists today

`Update button → ConnectionListViewModel → ServerListUpdater → fetch (HttpFetcher) → HTTP/HTTPS → VpnGateApiParser → DatasetIntegrity → VpnServerMerger → Room (VPNGateItemDao.replaceAll) → CollectorSnapshotStore (snapshot) → UI`

| Component | File : anchor | Role |
|---|---|---|
| Update trigger / ViewModel | `app/src/main/java/vn/unlimit/vpngate/viewmodels/ConnectionListViewModel.kt:65` (`getAPIData(isUserInitiated)`), `:75` (`viewModelScope.launch`), `:77` (`updater.update()`) | UI entry point; launches the update and maps the `Outcome` to UI state. |
| Updater builder | `ConnectionListViewModel.kt:46` (`ServerListUpdates.forUpdateTrigger(...)`) | Wires the concrete `fetch` + `store` closures into the pure-JVM orchestrator. |
| Orchestrator | `app/src/main/java/vn/unlimit/vpngate/repository/ServerListUpdater.kt:51` (class), `:223` (`update(): Outcome`) | Pure JVM. Enforces atomic replace, "never replace by a less-connectable dataset", "only writer of the stored dataset" (`:16`,`:29`,`:35`). |
| Persistence boundary | `ServerListUpdater.kt:77` (`interface Store`), `:108` (`saveLastKnownGood`), `:111` (`replaceAll`) | DAO must be atomic; snapshot written only after a successful replace (`:102`–`:105`). |
| HTTP fetcher | `app/src/main/java/vn/unlimit/vpngate/data/remote/HttpFetcher.kt:94` (`OkHttpFetcher`), `:41` (`HttpFetcher.get`) | Single-shot GET returning a provenance-rich `HttpResponse` (`:23`). |
| Response/provenance type | `HttpFetcher.kt:23` (`data class HttpResponse`) | Carries `requestedUrl`, `finalUrl`, `status`, `contentType`, `body`, `redirectChain`, `redirectRejected`, `redirectRejectionReason` (`:24`–`:31`). |
| Resilient DNS | `HttpFetcher.kt:53` (`ResilientDns`), `:55` (`VPNGATE_FALLBACK_IPS`), `:67` (`lookup`) | Filters poisoned/private/loopback redirect IPs; falls back to hard-coded IPs. |
| Redirect policy | `HttpFetcher.kt:130` (`maxHops = redirectPolicy?.maxHops ?: RedirectPolicy.DEFAULT_MAX_HOPS`) | Redirects followed **by hand** (`:101` followRedirects false) so each hop is checked. |
| Source catalog | `app/src/main/java/vn/unlimit/vpngate/data/remote/VpnGateSources.kt:21` (`VpnGateUrls`), `:50` (`VpnGateSourceCandidates`) | Canonical HTML/API URLs (`:22`–`:27`) and bootstrap mirrors (`:35`–`:41`). |
| Source identity | `app/src/main/java/vn/unlimit/vpngate/data/remote/SourceIdentity.kt:45` (`SourceIdentity`), `:75` (`sourceTag`) | Canonical vs mirror provenance; merge policy `api > html > mirror` (`:40`–`:43`). |
| CSV parser | `app/src/main/java/vn/unlimit/vpngate/parser/VpnGateApiParser.kt:81` (`parseApi`), `:35` (`splitCsvRecords`) | Port of Python oracle's `/api/iphone/` CSV parse; `CANONICAL_COLUMNS = 15` (`:16`). |
| Integrity gate | `app/src/main/java/vn/unlimit/vpngate/repository/DatasetIntegrity.kt:122` (`object`), `:198` (`evaluate`) | Produces a `DatasetVerdict` (`:90`) with thresholds below. |
| Merger | `app/src/main/java/vn/unlimit/vpngate/merger/VpnServerMerger.kt` | Combines multi-source records per the merge policy. |
| Room write | `app/src/main/java/vn/unlimit/vpngate/db/VPNGateItemDao.kt:50` (`replaceAll`), `:53` (`deleteAll`) | Atomic clear+insert inside a transaction. |
| Room schema | `app/src/main/java/vn/unlimit/vpngate/db/AppDatabase.kt:12` (`@Database(version = 5)`), entities `VPNGateItem`, `ExcludedApp` | Migrations v2→5 add `seTcpPort`, `seUdpPort`, `seUdpSupported`, `sstpPort`, `sstpPortSource` (`:18`–`:68`). |
| Record model | `app/src/main/java/vn/unlimit/vpngate/models/VPNGateItem.kt:8` (`@Entity`), `:10` (`@PrimaryKey hostName`) | Columns incl. `ip` (`:11`), `seTcpPort` (`:39`), `seUdpPort` (`:40`), `seUdpSupported` (`:41`), `sstpPort` (`:37`), `sstpPortSource` (`:38`). |
| Snapshot store | `app/src/main/java/vn/unlimit/vpngate/data/persistence/CollectorSnapshotStore.kt` | Persists the last-known-good snapshot. |
### A.2 Network binding model today

**Default Android routing. No explicit VPN/Network binding exists in the retrieval chain.** `SOURCE CODE EVIDENCE`.

- The downloader runs on `Dispatchers.IO` (`HttpFetcher.kt:116`) and makes an ordinary `OkHttpClient` request (`:95`–`:101`). It calls **none** of `protect()`, `bindSocket`, `allNetworks`, or `requestNetwork`.
- Every network-binding API in the tree lives in the **VPN-transport / state layer**, not retrieval: `AndroidConnectionAdapter.kt:331`, `GlobalVpnTracker.kt:118` (`TRANSPORT_VPN` request), `DataUtil.kt:574`.
- Therefore a server-list update issued while a VPN tunnel is active is served by whatever the OS default route is at that moment — **not** by the app's own tunnel, and **not** guaranteed to bypass it. `INFERENCE`.

### A.3 Where a VPN `Network` object is reachable by the downloader (if at all)

**Today: not reachable by the downloader.** `SOURCE CODE EVIDENCE` + `INFERENCE`.

- `GlobalVpnTracker` (`state/GlobalVpnTracker.kt:65`) already observes the system VPN network: `registerSystemVpnWatcher` (`:113`) registers a `NetworkRequest` for `TRANSPORT_VPN` (`:116`–`:118`) with an `onAvailable(network: Network)` callback (`:122`). It exposes connection state (`vpnState: StateFlow` `:68`, `isConnected` `:73`, `isConnecting` `:76`).
- The observed `Network` is used only for state tracking, **not** surfaced as a bindable object to `HttpFetcher`. §2 would surface it (hold the `Network` from `onAvailable` and bind the fetch socket to it).

### A.4 Current retry, timeout, and error-handling behavior

| Concern | Current behavior | Anchor |
|---|---|---|
| Connect timeout | 10 s | `HttpFetcher.kt:97` |
| Read timeout | 15 s | `HttpFetcher.kt:98` |
| Inter-request delay | `requestDelayMs` (default 0) | `HttpFetcher.kt:104` |
| Redirect handling | hand-followed, per-hop policy check, `maxHops` = `RedirectPolicy.DEFAULT_MAX_HOPS` | `HttpFetcher.kt:101`, `:130` |
| Redirect rejection | recorded as `redirectRejected`/`redirectRejectionReason`, not silently followed | `HttpFetcher.kt:159`–`:187` |
| DNS failure | `ResilientDns` fallback IPs | `HttpFetcher.kt:53`–`:67` |
| HTTP/parse/integrity failure | `Outcome.Preserved` with a `Reason` (never treated as success) | `ServerListUpdater.kt:151` (`Reason`), `:220`–`:273` |
| Empty / degraded dataset | rejected by `DatasetIntegrity` thresholds | `DatasetIntegrity.kt:203`, `:223`–`:262` |
| Concurrent update | `Outcome.SkippedConcurrent`; a second caller awaits the running one | `ServerListUpdater.kt:147`, `ViewModel.kt:78`–`:84` |

`INFERENCE`: there is **no per-path timeout/retry ladder** (open vs VPN vs emergency); a single timeout pair applies to whichever fetch runs.

### A.5 Current source-list configuration

`SOURCE CODE EVIDENCE`:

- Canonical HTML/API URLs are compile-time constants: `MAIN_URL`, `API_URL`, `MIRRORS_URL` and HTTP fallbacks (`VpnGateSources.kt:22`–`:27`); `MAX_MIRRORS = 12` (`:28`).
- Bootstrap mirrors are hard-coded `http://IP:PORT` entries (`VpnGateSources.kt:35`–`:41`).
- Source *kinds* are the `SourceKind` enum: `CANONICAL_HTML`, `CANONICAL_API`, `BOOTSTRAP_MIRROR`, `DISCOVERED_MIRROR`, `CACHED_MIRROR` (`SourceIdentity.kt:12`–`:26`). **There is no `EMERGENCY_CSV` or `MANUAL` kind yet.**
- `INFERENCE`: there is currently **no user-configurable emergency URL or manual-import plumbing wired into this chain**; §11.9/§11.10 of the spec describe these as requirements, not implemented features. The mirror CSV feed is intentionally refused because its schema is undefined.

---
