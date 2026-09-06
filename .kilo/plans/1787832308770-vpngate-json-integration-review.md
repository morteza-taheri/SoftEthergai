# Review: vpngate_collector JSON integration (commit `b9972d0`)

## Verdict

The reported work is **substantially correct and verified statically**. The JSON contract
between `vpngate_collector.py` and the Kotlin model matches field-for-field, the merge /
provenance / confidence logic implements the stated rules, and the test-count claim
(14 passed, 1 skipped) is consistent with the fixture contents. Four follow-up issues
were found, listed below under "Findings".

Execution note: permission rules blocked running `python -m pytest` and `gradlew`
directly, so verification was by code inspection only. The reported green runs are
plausible and internally consistent (see item V8).

## Claims verified

| # | Claim | Evidence |
|---|-------|----------|
| V1 | Commit `b9972d0` contains all stated files, no `[skip ci]` | `git show --stat b9972d0` (16 files, code change → CI must run, correct) |
| V2 | `vpngate_collector.py` recovered, no longer zeroed | 80,566 bytes, 3,301 lines, valid Python structure |
| V3 | `.gitignore` gained `__pycache__/`, `.pytest_cache/` | `.gitignore:24-25` |
| V4 | Working tree clean except intentionally-left `.kilo/kilo.json` + `.kilo/plans/` | `git status --short` |
| V5 | JSON keys exactly match `VPNGateHtmlServer.kt` | `_export_server` (`vpngate_collector.py:2537-2565`) vs `VPNGateHtmlServer.kt:13-58`: hostname/ip/country/countryLong/sessions/uptime/totalUsers/score/ping/speed, `softEther{tcp:Int,udp:Boolean}`, `openVPN{tcp,udp:Int}`, `l2tp:Boolean`, `sstp{host,port}`, sources/sourceCount/valid/qualityScore. Extra root keys (`schemaVersion`, `generatedAtUtc`) are ignored by Gson — harmless |
| V6 | Android enrichment wired correctly | `ConnectionListViewModel.kt:92,112-143` (fetch → parse → match by hostname+ip, lowercase; failure falls back to CSV-only), `VPNGateConnection.enrichFromHtmlServer` only fills zero-valued fields; test moved to `app/src/test/` (no `HtmlJsonEnrichmentTest` under `androidTest/`) |
| V7 | §15/§16/§38 rewrite | `source_priority`/`recursive_merge` (root-level conflicts, `root_target` provenance), `independent_source_groups`, `confidence_for_groups` (0/0.6/0.8/1.0 policy), mirror regex single-escaped `r"^mirror_\d+$"` (`:2017`) |
| V8 | "14 passed, 1 skipped" is consistent | 15 tests exist (1 api + 8 html + 6 merge). `test_7_softether_udp_only_never_borrows_openvpn_tcp` is the skip: fixture has no row with SE-UDP-only **and** an OpenVPN cell containing `TCP: N` (row `vpn100383739` is SE-UDP-only but its OpenVPN link is `tcp=0&udp=1195`, cell text has no `TCP:`). All other conditional paths resolve against the fixture |
| V9 | Row repair + proto inheritance | `parse_api` repair of unquoted Operator commas (`:506-524`), global `proto` inheritance for bare `remote` lines (`:680-704`), consistent with `test_api_parser` expectations |

## Findings (need action)

### F1 — Bug: double-escaped regex in operator cleanup (high)
`vpngate_collector.py:1617-1619`
```python
operator = re.sub(r"^by\\s+", "", operator, flags=re.I)
```
In a raw string, `\\s` matches a literal backslash followed by `s+`, so the "By …"
prefix is **never** stripped (all fixture operators are `By Daiyuu Nobori, …`).
Same escape-bug class as the ones already fixed. Fix: `r"^by\s+"`.

### F2 — Operational gap: app JSON URL has no published artifact (high)
`AppConfig.kt` → `https://raw.githubusercontent.com/morteza-taheri/SoftEther/master/servers_all.json`
but `servers_all.json` is gitignored, `git ls-files` shows it **untracked**, and there is
no `.github/` workflow to publish it. Until the file is published, the app always gets a
404 and silently degrades to CSV-only data (graceful, but the feature is dead).
Options (pick one):
1. `git add -f servers_all.json` after a collector run + periodic refresh commit; or
2. GitHub Actions cron job: run collector → commit/push `servers_all.json` (note: commit
   must NOT use `[skip ci]` only if Python CI matters; this repo has no Python CI, so a
   data-refresh commit could use `[skip ci]` if Android CI cost is a concern — decide).

### F3 — Dead code left from pre-rewrite version (low)
Never called: `parse_html_protocols` (`:897`), `extract_country_from_row` (`:1129`),
`merge_value` (`:1736`). `parse_html_protocols` is also semantically unsafe (full-row-text
regexes could borrow ports across columns — violates §6). Recommend deleting all three.

### F4 — Enrichment delays list display (medium, UX)
`enrichWithHtmlJson` runs **before** `vpnGateConnectionList.value = connectionList`
(`ConnectionListViewModel.kt:92-93`). If raw.githubusercontent.com is slow/blocked
(likely in target regions), the whole server list waits on Retrofit/OkHttp default
timeouts (~10s each). Mitigations:
- dedicated `OkHttpClient` (or `withTimeoutOrNull`) for `getJsonString`, e.g. 5s, or
- post the list first, enrich in background and re-post / persist enriched flags.

### F5 — Minor
- `VPNGateApiService.kt` / `ConnectionListViewModel.kt` missing trailing newline (cosmetic).
- `SSTP` enrichment requires `port > 0`; host-only SSTP servers are not flagged.
  Consistent with §9/export schema (port=0 when unprinted) — intentional, no action needed.

## Fix plan (for implementation agent)

1. `vpngate_collector.py:1618` — change `r"^by\\s+"` → `r"^by\s+"`.
2. Add a regression test asserting the "By " prefix is stripped from `operator.name`
   (extend `test_html_parser.py` against fixture row `public-vpn-206`).
3. Delete `parse_html_protocols`, `extract_country_from_row`, `merge_value` (verify no
   references remain via grep first).
4. Decide + implement F2 publication path (ask user: force-commit static JSON vs CI cron).
5. Optional (ask user): F4 timeout/async change in `ConnectionListViewModel`.
6. Validate: `python -m pytest tests/ -q` (expect 15 passed, 1 skipped after new test),
   then Android unit tests `gradlew :app:testProDebugUnitTest` (expect 5 passed).

## Out of scope
- `.kilo/kilo.json` modification and `.kilo/plans/` (user intentionally excluded).
- Rebuilding the unrecoverable ~88KB version's exact history (already rewritten and tested).
