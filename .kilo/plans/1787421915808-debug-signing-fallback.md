# VPN Gate Collector — Python Oracle Hardening + Kotlin In-App Collector (Mode B)

> Recovery (commit `b9972d06`) is DONE and audited. User decisions locked in:
> - The app must collect server data ITSELF from VPN Gate main HTML + official API + mirrors (§23 Mode B). **No GitHub-repo JSON dependency** — the `vpn_html_servers_json` → raw.githubusercontent enrichment path is to be replaced/removed.
> - SSTP: show support when host exists even with unknown port; connect via SSTP's protocol-standard TCP 443 labeled "default" (§9-compliant: nothing invented in the database, standard default only at connect time).
> - SoftEther UDP: extraction correctness needs deeper work (user observed incomplete ports/protocols in real testing) — prioritize in the diagnostics task.
> - Add protocol-type filters to the server list UI.
>
> ## Progress
> - **T1 DONE** — report path, `^by\s+` fix, dead-code removal, .gitignore complete, provenance in report (`678452d`+).
> - **T2 DONE** — diagnostics mode, nested-table/stray-`</td>` DOM fix, real fixtures, live run §35 green, spot checks passed (`5b49859`).
> - **T3 DONE** — Kotlin collector port: models/parsers/merger/ranking with oracle-parity unit tests on the SAME fixtures (`edeaafc`); repository + ViewModel wiring, GitHub-JSON enrichment removed, SoftEther filter, SSTP 443-default (`ca5d0fe`); SoftEther UDP "port unknown" UI state with Room migration 3→4 (`2d000e5`). Kotlin: 32 unit tests green (1 skip identical to Python); `assembleProRelease` green.
> - **T4 DONE** — developer diagnostics: Python `--debug-ip` per-server provenance dump + in-app debug panel (provenance, conflicts, confidence, raw HTML/API toggle) with identical layouts; tests on both sides (`79e7eb7`).
> - **T5 BLOCKED** — connectivity tester (needs T2+T3 on-device validation first).

## Verified state (audit of `b9972d06`)

- Python reference (`vpngate_collector.py`, 3301 lines) has the V5 contract: DOM/cell-based `parse_html` (flag-image ISO country, `do_openvpn.aspx` tcp/udp params authoritative, SoftEther strictly from its own cell, SSTP `host[:port]` else null, L2TP→1701), `build_column_map` header-derived, conflict-aware merge (api>html>mirror), mirror grouping, §15 confidence (0.6/0.8/1.0), `parse_uptime_days` days/hours/mins.
- Tests: 14 Python tests (7 html + 1 api + 6 merge) per claim "14 passed, 1 skip"; Kotlin `HtmlJsonEnrichmentTest` moved to `app/src/test` (AGP 9 disables androidTest locally; run with `CI=1 :app:testProDebugUnitTest`), 5 tests green.
- App currently fetches **only** `/api/iphone/` CSV (`ConnectionListViewModel:82`); enrichment matched by hostname/IP with fallback semantics verified; `enrichFromHtmlServer` no-override semantics tested.

### Confirmed defects to fix
1. **BLOCKING:** `main()` crashes at `save_json(OUT_REPORT, [report])` — the report dict is routed through `_export_server` which expects `identity` → `KeyError`; `collection_report.json` never written.
2. Double-escaped regex `r"^by\\s+"` (line ~1618) never strips the operator "By " prefix.
3. Dead legacy row-regex code: `parse_html_protocols` (897-1122) + `extract_country_from_row` (1129-1165), zero call sites — delete.
4. `.gitignore` lists only 3 of 13 outputs (+report missing).
5. `compute_confidence` / conflicts never surface in outputs; `configs[]` (API-decoded OpenVPN configs, §8) dropped in export.
6. Enrichment URL in `AppConfig` points at a gitignored, never-committed file (404 in practice) — superseded by Mode B anyway.
7. `enrichFromHtmlServer` hides SSTP when port unknown (fix per decision above); `seUdpPort = se.tcp` rule must be documented as SoftEther's same-port behavior (UDP-only servers with unknown port need explicit handling in UI: "UDP supported, port unknown").

## Ordered tasks

### T1 — Python oracle fixes (test-locked, no behavior regressions)
1. Write `collection_report.json` via plain `json.dump` (never through `save_json`/`_export_server`); add a smoke test for the report path.
2. Fix `r"^by\s+"`; delete the dead legacy parser functions.
3. `build_report`: add provenance summary — conflict count + top conflicting fields, per-protocol confidence distribution, independent-group stats (§14/§15 visibility).
4. Complete `.gitignore` (all 13 outputs + `collection_report.json`).
5. `python -m pytest tests/ -v` stays 14 passed / 1 skip.

### T2 — Real-world extraction diagnostics (user-reported gaps, SoftEther UDP focus)
1. Add a diagnostics mode/script: for EVERY row of a live HTML fetch, print cell-facts vs parsed fields (softether tcp/udp, openvpn tcp/udp from link params vs cell text, sstp host/port, l2tp, country) and aggregate mismatch counts.
2. Run live; investigate every mismatch class (DOM variants: `UDP: Supported` vs numeric UDP, cells with missing link, mirror layout drift, rows with fewer cells).
3. Fix parser gaps found; add regression fixtures (trimmed real rows) to `tests/fixtures/` + tests before/with fixes. Never invent ports — null stays null (§6/§9/§38); fix EXTRACTION, not data policy.
4. Then full end-to-end `python vpngate_collector.py`: §35 criteria (HTML>0, API>0, SoftEther/OpenVPN/SSTP/L2TP >0, no invented ports), spot-check ≥3 servers against live page/API (`public-vpn-206`, an SSTP-`:port` server, an OpenVPN-UDP-only server).

### T3 — Kotlin in-app collector (Mode B port of the oracle)
Package layout per §24 under `vn.unlimit.vpngate`:
1. `data/model/`: protocol-first models mirroring the Python internal schema (nullable ports!), `SourceInfo`, provenance map, `Conflict`.
2. `data/remote/`: `VpnGateHtmlSource` (main site), `VpnGateMirrorSource` (mirror discovery from `/en/sites.aspx`, IP:port/opengw hosts only, cap 10), `VpnGateApiSource` (`/api/iphone/` CSV with the real header; Speed bps→Mbps, Uptime ms→days, TotalTraffic bytes→GB).
3. `parser/`: `VpnGateHtmlParser` with Jsoup — port the Python logic 1:1 (hosts-table selection among duplicate ids, header-derived column map, flag-image ISO country, per-cell protocol parsing, `do_openvpn` query params, `SSTP Hostname : host[:port]`, uptime days/hours/mins); `VpnGateApiParser` + `OpenVpnConfigParser` (base64 → `proto`/`remote` directives; global-proto inheritance).
4. `merger/`: dedupe by IP→hostname; priority api>html>mirror; fill-gaps-without-conflict; record conflicts at root; collapse `mirror_N` → one group; confidence 0.6/0.8/1.0 (§15/§16).
5. `ranking/ServerQualityCalculator`: port the quality formula; multi-source bonus uses INDEPENDENT groups, not raw source count.
6. `repository/VpnServerRepository`: coroutines (`Dispatchers.IO`, structured concurrency, per-request timeouts, rate-limited parallel mirrors), cache file with timestamp, cache-first/network-first modes, manual+auto refresh, last-known-good fallback (§25–§28). NO `runBlocking`/`Thread.sleep`/network on Main.
7. Replace the GitHub-JSON enrichment: wire the repository into `ConnectionListViewModel`, then remove `enrichWithHtmlJson`/`getJsonString`/`vpn_html_servers_json` (keep until the Kotlin collector passes its tests).
8. UI: protocol-type filter on the server list (All / SoftEther / OpenVPN / L2TP/IPsec / MS-SSTP) driven by parsed protocol facts; SSTP rows show with 443-default note; SoftEther UDP-only rows show "port unknown" state.
9. Unit tests in `app/src/test` using the SAME fixtures as Python (`vpngate_table_sample.html` + an API CSV fixture) mirroring the 14 Python tests — identical expectations (one behavioral oracle). Run: `CI=1 ./gradlew :app:testProDebugUnitTest`.

### T4 — Developer diagnostics (§33)
- In-app debug panel: per-server per-source field dump, conflicts, confidence, raw HTML/API toggle.
- Python side: `--debug-ip <ip>` CLI dump with the same layout.

### T5 — Connectivity tester (§30) — BLOCKED until T2+T3 validated
Separate component; staged per-protocol checks (TCP reachability → handshake/TLS), never treat open port as working.

## Risks / guardrails
- The file-corruption incident shows uncommitted work can vanish: commit each task's increment; never leave the oracle only in the working tree.
- Kotlin port must not "improve" parsing behavior — match the Python oracle exactly; divergences = bugs.
- AGP 9 disables androidTest locally — keep collector tests in `test` (unit) with `CI=1`.
- No `[skip ci]` on code commits; doc-only submodule bumps may use it (repo convention).

## Validation
- Python: `pytest` green + live run meets §35 + diagnostics report shows zero unexplained mismatches for SoftEther/OpenVPN/SSTP/L2TP facts.
- Kotlin: unit tests green on shared fixtures; app builds (`compileProReleaseKotlin`); list refresh shows protocol-complete servers without any GitHub dependency; protocol filters work.
