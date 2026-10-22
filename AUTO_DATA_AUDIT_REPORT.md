# AUTO_DATA_AUDIT_REPORT — SERVER-DATA-AUDIT-AND-OPENVPN-CONFIG-GATE (v1.0.53)

**Block:** v1.0.53 — SERVER-DATA-AUDIT-AND-OPENVPN-CONFIG-GATE
**Revision:** spec §1.6.45, document version 1.0.53
**Nature:** device-free (DETACHED). No device ran in this phase.
**Branch:** `main`. No push, no tag.

Every claim below carries a label:

| Label | Meaning |
|---|---|
| `[SOURCE CODE]` | Read from the shipped sources in this working tree. |
| `[TEST]` | Produced by an executed test (JVM unit test or Python test). |
| `[LIVE]` | Fetched from the live VPN Gate endpoint during this phase. |
| `[PHYSICAL DEVICE]` | Observed on a real Android device. **None in this phase.** |
| `[INFERENCE]` | A conclusion drawn from the above, stated as such. |
| `[UNKNOWN]` | Not determinable from the evidence available. |
| `[PRIOR DATA]` | Figures carried from an earlier snapshot or report. |

---

## 1. Snapshot identity — and why it moved three times during the phase

The emergency dataset is refreshed by an **external process**. It advanced four times while this block was in progress. Nothing in this block wrote to `emergency-sources/servers.csv`, `vpngate_collector.py` or `servers.csv`; none of them was staged, and the working tree copy always matched its own HEAD.

| Commit | Subject | Rows | With config | Without config | Port conflicts |
|---|---|---|---|---|---|
| `a5cb153` | update servers.csv (280 servers) | 280 | 62 | 218 (**77.0 %**) | 1 |
| `0387d37` | update servers.csv (283 servers) | 283 | 62 | 221 (**78.1 %**) | 1 |
| `b5e0f7e` | update servers.csv (271 servers) | 271 | 62 | 209 (**77.1 %**) | 1 |
| `4afa7e3` | update servers.csv (229 servers) | 229 | 63 | 166 (**72.5 %**) | **2** |

`[SOURCE CODE]` (git history) + `[TEST]` (the read-only audit script, run against each snapshot).

### 1.1 Reconciliation with the operator's figures

The operator's figures — **280 rows, 218 without a config (77 %), 1 port conflict (`123.48.124.169`, 1534 vs 1558)** — are **exactly right for `a5cb153`**, the HEAD at STEP 1. `[TEST]` verified on that snapshot.

They are no longer right for the latest snapshot, and the reason is data churn, not a different measurement:

- **The 77 % is a range, not a constant.** 77.0 % → 78.1 % → 77.1 % → 72.5 %. The absolute count of config-less rows swings with the server population; the order of magnitude does not.
- **The conflict count is not constant either.** `123.48.124.169` (1534 vs 1558) is present in **all four** snapshots. A **second** conflict appears only at `4afa7e3` (§4.2).

**All figures below are for `4afa7e3` (229 rows) unless stated otherwise**, and every claim that depends on a count is written so that a further refresh cannot make it false.

### 1.2 `153.180.162.195` — the UNKNOWN is now superseded

The authorisation said: *"153.180.162.195 is not reproducible in the current snapshot: record it as UNKNOWN, do not claim it."*

`[TEST]` That was correct for `a5cb153`: the row's `OpenVPN_ConfigData_Base64` was empty, so no config-vs-column disagreement existed to measure.

`[TEST]` It is **reproducible at `4afa7e3`**, and with different numbers than the brief suggested:

```
ip=153.180.162.195  host=vpn927671187  SourceTag=merged
column   : OpenVPN_TCP_Port = 1820
config   : proto tcp ; remote 153.180.162.195 1759
b64len   : 13 344
```

So the record is **UNKNOWN for `a5cb153`** and **confirmed for `4afa7e3`** — recorded here as both, rather than as one claim that is wrong half the time. The fix does not depend on which: the rule is generic and both rows are corrected by the same code.


---

## 2. TASK A — the OpenVPN config gate (confirmed defect)

### 2.1 The defect

`[SOURCE CODE]` `AutoModeController.usableProtocols()` decided OpenVPN usability from a port alone:

```kotlin
AutoModeProtocol.OPENVPN_TCP -> c.openVpnTcpPort > 0      // before v1.0.53
AutoModeProtocol.OPENVPN_UDP -> c.openVpnUdpPort > 0      // before v1.0.53
```

A port is not a transport. OpenVPN is the one protocol whose dial needs **bytes**: `AndroidConnectionAdapter.connectOpenVpn()` builds the profile from `VPNGateConnection.openVpnConfigData`. When that is null the adapter did this:

```kotlin
if (data.isNullOrEmpty()) {
    Log.w(TAG, "No OpenVPN config payload for ${conn.hostName}")
    return            // before v1.0.53: silent
}
```

`[SOURCE CODE]` The `return` made the connect look *issued*. `awaitArmedOutcome()` then waited out the **full per-attempt budget** and reported `TIMED_OUT` — the one outcome the controller treats as evidence *about the server*, so it recorded a wedge (`AUTOCONNECT-AUDIT D-5`). A data defect was billed as a dead endpoint, once per selected server, at 8 s each.

`[SOURCE CODE]` `EmergencyCsvParser` produced exactly that shape: it set `protocols.openvpn.*.supported` and `.port` from the CSV columns **independently** of the `OpenVPN_ConfigData_Base64` branch. The publisher deliberately strips configs to stay under `EmergencySourceFetcher.MAX_BODY_CHARS = 1 MiB` (`emergency_csv_publisher.py fit_budget()`), so most published rows have a port and no profile.

`[SOURCE CODE]` The manual path had always required both — `DetailActivity.protocolOptions` gates on `openVpnConfigData != null && tcpPort > 0`. Auto Connect did not. That within-code inconsistency is the bug.

### 2.2 The fix

1. `[SOURCE CODE]` **A new candidate fact.** `AutoModeCandidate.openVpnConfigAvailable: Boolean = false` — deliberately defaulting to `false` so a forgotten construction site *loses* a protocol instead of burning a timeout (fail closed).
2. `[SOURCE CODE]` **The fact is derived from bytes, not from a claim.** `AutoModeEngine.toCandidate()` sets `openVpnConfigAvailable = !openVpnConfigData.isNullOrBlank()`. One flag covers both transports because `OpenVpnConfigTransform.forUdp` returns the same profile rewritten and is null iff its input is null — so UDP is dialable exactly when TCP's bytes exist. **`VPNGateConnection.kt` was therefore not needed** (see §7).
3. `[SOURCE CODE]` **The gate.**
   ```kotlin
   AutoModeProtocol.OPENVPN_TCP -> c.openVpnConfigAvailable && c.openVpnTcpPort > 0
   AutoModeProtocol.OPENVPN_UDP -> c.openVpnConfigAvailable && c.openVpnUdpPort > 0
   ```
4. `[SOURCE CODE]` **Port facts are untouched.** `openVpnTcpPort` / `openVpnUdpPort` are still carried verbatim from the record; only usability is gated. The parser still writes the port column (see §5.2).
5. `[SOURCE CODE]` **The parser no longer claims usability without bytes.** `EmergencyCsvParser.applyOpenVpnColumnPort()` writes `port` + `portSource` always, but sets `supported = true` only when `configAvailable`. Two new non-rejecting issues make it visible: `OPENVPN_PORT_WITHOUT_CONFIG`, `OPENVPN_PORT_CONFLICT_CONFIG_WINS`.

### 2.3 Tests

| Case | Test | Result |
|---|---|---|
| Config present → usable | `AutoModeControllerTest.protocolFilterOpenVpnUdp` (updated: now declares the fact), `theGateDoesNotEraseThePortFacts` | `[TEST]` pass |
| Config absent → not usable, **no connect issued** | `configLessOpenVpnServerIsNeverSelected` | `[TEST]` pass |
| HTML link only → not usable | `htmlLinkOnlyServerIsNotUsableForOpenVpn` (built through `toCandidate()`) | `[TEST]` pass |
| Merged API + emergency rows | `EmergencyCsvParserTest.a merged api and emergency row is usable for auto connect` | `[TEST]` pass |
| Parser keeps the port, drops the claim | `an openvpn port without a config keeps the port but claims no support` | `[TEST]` pass |
| User protocol priorities untouched | the six pre-existing priority/RTT/probe tests still pass unchanged in intent | `[TEST]` pass |

`[SOURCE CODE]` **User protocol priorities were not touched.** No priority list, ordering rule or profile was edited; the only change is that a protocol with no data is no longer *offered*, which is what the priority list always assumed.

---

## 3. TASK B.2 / B.7 — field-by-field audit

All four parsers plus the Python oracle were compared on the same real data. Sources: the shipped CSV (`b5e0f7e`), the embedded profiles decoded from it, and the live page (`[LIVE]`, 216 651 bytes fetched during this phase).

| Field | API (`VpnGateApiParser`) | HTML (`VpnGateHtmlParser`) | Emergency CSV (`EmergencyCsvParser`) | Oracle (`vpngate_collector.parse_html`) | Verdict |
|---|---|---|---|---|---|
| HostName | column 1 | table cell | column | `identity.hostname` | **agree** |
| IP | column 2 | table cell | column | `identity.ip` | **agree** |
| Speed | bps → `speedMbps` (÷1e6) | cell → `speedMbps` | bps → `speedMbps` (÷1e6) | `speedMbps` | **agree** (one target unit) |
| Ping | ms → `pingMs` | cell | ms | `pingMs` | **agree** |
| Uptime | ms → `uptimeDays` (÷86 400 000) | `"93 days"`/`"11 hours"` → days | ms → `uptimeDays` | `uptimeDays` | **agree** |
| TotalTraffic | bytes → `totalTrafficGB` | cell | bytes → `totalTrafficGB` | `totalTrafficGB` | **agree** |
| Unquoted commas in `Operator`/`Message` | Python `csv` reader | n/a | Kotlin CSV reader | n/a | **agree**; 0 malformed rows of 229, 29 columns every row |
| DDNS vs API HostName | `*.opengw.net` when published | same | same | same | **agree**; 5 DDNS / 224 plain / 0 empty |
| SoftEther TCP port | n/a | `TCP: <n>` | column | same | **agree**; 0 rows with port 0 |
| SoftEther UDP | n/a | `UDP: Supported`, **no port** | `SE_UDP_Supported` + `SE_UDP_Port` | same | **correct, not a defect** — see §5.1 |
| OpenVPN TCP/UDP port | from the **profile's** `remote` | from the **`do_openvpn` URL params** | from the column | from the `do_openvpn` params | **defect** — §4 |
| SSTP `Hostname[:port]` | n/a | `SSTP Hostname: host[:port]` | column, split by `normalizeHost` | `parse_sstp` regex splits | **defect on the publisher side** — §5.2 |
| L2TP | n/a | `L2TP` cell | column | same | **agree**; 32 true / 197 false |
| `Has_IPv6` | n/a | **absent from the page** | column, always `false` | absent | **unobservable** — §5.3 |
| Duplicate IPs | dedupe by IP | dedupe | dedupe | — | **0 duplicates** in the snapshot |
| HTML table/column selection | — | `cell(...)` by column class | — | `tds` index | **agree**; both find the same 63 OpenVPN rows |

`[LIVE]` `[SOURCE CODE]` `[TEST]`

`[SOURCE CODE]` Every embedded profile in the snapshot is `proto tcp` — all 63 of 63. No profile declares UDP. So `OpenVPN_UDP_Port` is **always** link-sourced and can never be config-sourced from the embedded bytes; the app still offers UDP because `OpenVpnConfigTransform.forUdp` rewrites the TCP profile. This is worth stating plainly because it means the config-authority rule can only ever *correct* a TCP port from an embedded profile, never a UDP one.

---

## 4. TASK B.3 — source conflict: the config is authoritative

### 4.1 The rule

> The endpoint dialled by the embedded config is authoritative. The HTML `do_openvpn` URL parameter (and the CSV `OpenVPN_*_Port` column) is used **only** when no config is present. Every disagreement is logged, never resolved silently.

### 4.2 The reproduced conflicts

`[SOURCE CODE]` `[TEST]`

```
ip=123.48.124.169  host=vpn122916437   SourceTag=merged   b64len=13 344
column / link : OpenVPN_TCP_Port = 1534
embedded config: proto tcp ; remote 123.48.124.169 1558
```
Present in **all four** snapshots examined.

```
ip=153.180.162.195 host=vpn927671187   SourceTag=merged   b64len=13 344
column / link : OpenVPN_TCP_Port = 1820
embedded config: proto tcp ; remote 153.180.162.195 1759
```
Present at `4afa7e3` only (§1.2).

Both rows advertise a port their own profile does not dial. `[TEST]` The profiles decode to 13 344 Base64 characters each and `config_transport_ports()` returns `{'tcp': 1558}` and `{'tcp': 1759}` respectively; `enforce_config_ports()` corrects both.

### 4.3 Where the rule is applied

| Layer | Change | Test |
|---|---|---|
| `EmergencyCsvParser` | `applyOpenVpnColumnPort()` — config port wins; a differing column raises `OPENVPN_PORT_CONFLICT_CONFIG_WINS`; the column is used only when the config declares no port for that transport | `an openvpn column that disagrees with the config loses to the config` `[TEST]` |
| `VpnServerMerger.normalizeServer()` | `enforceConfigAuthoritativePorts()` — a record-wide invariant: if the record carries config remotes, the port for the declared transport must equal the config's. Runs **before** port validation so the authoritative value is the one validated. A correction records a §38 conflict. | `normalizeServerMakesTheConfigPortAuthoritative`, `mergedApiAndHtmlRowsKeepTheConfigPort` `[TEST]` |
| `emergency_csv_publisher.py` | `config_transport_ports()` + `enforce_config_ports()`, applied after `merge_with_previous()` and before prune/budget. Logs every correction. | `ConfigAuthoritativePorts` (9 cases) `[TEST]` |

`[SOURCE CODE]` **No §10 CSV columns were added.** The schema is unchanged: 29 columns, same names, same order.

`[SOURCE CODE]` Cross-source precedence already produced this result before the change: `SOURCE_PRIORITY = mapOf("api" to 0, "html" to 10, "manual" to 30)`, and the API parser writes the config's port at `api` authority. The merger function is the **invariant that makes it checkable in one place**, so a future parser cannot reintroduce the split. That is stated in its KDoc rather than implied.

---

## 5. TASK B.4 / B.5 — flags and the `host:port` shape

### 5.1 `SE_UDP_Port` all-zero with `SE_UDP_Supported` true — **not a defect**

`[LIVE]` The live page renders SoftEther UDP as `TCP: 443<BR>UDP: Supported` — 180 occurrences of `UDP:` and **zero** `UDP: <digits>`. The port is simply never published for SoftEther UDP.

`[TEST]` In the snapshot: 219 of 229 rows declare `SE_UDP_Supported=true`, and `SE_UDP_Port` is `0` in **all 229**.

`[SOURCE CODE]` `EmergencyCsvParser` handles this exactly as §10.3 requires — support is *declared*, never inferred from a port, and a port without declared support raises `UDP_PORT_WITHOUT_DECLARED_SUPPORT` instead of being written. **No change made.** This is the correct reading of the data, and changing it would invent a port.

### 5.2 SSTP `Hostname[:port]` — confirmed, live, and recurring

`[LIVE]` The live page publishes the suffix as a matter of course. Four consecutive samples:

```
SSTP Hostname : public-vpn-251.opengw.net
SSTP Hostname : public-vpn-71.opengw.net
SSTP Hostname : public-vpn-239.opengw.net
SSTP Hostname : vpn799794865.opengw.net:1919      <-- port suffix
```

So this is not a one-off row; it is a shape the page emits whenever the port is not 443.

`[PRIOR DATA]` The row cited in the brief — `123.200.94.11`, `vpn943218848.opengw.net:1956`, `SSTP_Port` 0, `SE_TCP_Port` 1956 — was present at `0387d37` and is **absent from `b5e0f7e`**. It is pinned in the tests from its real values rather than assumed to still be in the file.

| Side | Before | After | Test |
|---|---|---|---|
| `EmergencyCsvParser` | **already correct** — `normalizeHost()` strips a single-colon suffix and `applySstpEndpoint()` recovers the port; the explicit column wins | unchanged; now pinned by test | `an sstp hostname carrying a port is split into host and port`, `an explicit sstp port column wins over the hostname suffix`, `a plain sstp hostname is left alone` `[TEST]` |
| `emergency_csv_publisher.py` | wrote the raw cell, so `SSTP_Hostname` could carry `:1956` **with** `SSTP_Port` 0 | `split_sstp_endpoint()` + `normalize_sstp_rows()`; explicit column wins; a non-numeric suffix is not treated as a port | `SstpHostPortSplit` (7 cases) `[TEST]` |

`[INFERENCE]` Had the publisher's shape reached the app unchanged, the SSTP dial would receive a literal host containing `:1956` and append the port again. The parser's own split means the app was protected on the parse path; the publisher was not, and the CSV is also consumed by other tooling.

### 5.3 `Has_IPv6` — **unobservable, and now labelled as such**

`[LIVE]` The string `IPv6` (case-insensitive) occurs **zero** times in the 216 651-byte live page.

`[TEST]` All 229 rows carry `Has_IPv6=false`.

`[SOURCE CODE]` The publisher writes `False` for every row with the comment *"The collector does not track IPv6 observations; `false` = not observed"*, and `EmergencyCsvParser` lists `Has_IPv6` among the known columns but **never reads it** — no record field, no merge rule, no UI.

**Decision: no code change, recorded as `[UNKNOWN]`.** The §10 schema requires a boolean in that column, the app never consumes it, and no behaviour depends on the `false`. Rewriting it to empty would break the column contract and the parser's `applyBool` (which raises `NON_BOOLEAN_TOKEN` for a non-boolean), for zero behavioural gain. The honest statement is that **IPv6 support is not observable and the value is inert** — which is now what the spec says, instead of implying the servers have no IPv6.

### 5.4 `SSTP_Supported` — true on every row, port 0 on 55 of them

`[TEST]` `SSTP_Supported=true` in 229 of 229 rows; `SSTP_Port=0` in 33 of those.

`[LIVE]` The page publishes an SSTP hostname for essentially every server, and `MS-SSTP` appears 98 times.

`[SOURCE CODE]` `AutoModeController.usableProtocols()` already refuses SSTP when `sstpConnectPort == 0`, and `VpnConnectionMapper` documents the 443 default as the intended behaviour ("connect on TCP 443 by default"). A DEFAULT-port server stays supported; an *unknown*-port server is not dialled. **No change made** — the flag is observable and the consumer is already correct.

---

## 6. TASK B.6 — developer-log classification of every attempt outcome

### 6.1 The new outcome

`[SOURCE CODE]` `AttemptOutcome.MISSING_CONFIG` — a distinct value, not a timeout. `AndroidConnectionAdapter` no longer returns silently: `connectOpenVpn()` sets `lastMissingConfig`, and `awaitArmedOutcome()` returns `MISSING_CONFIG` **before it waits**, so the per-attempt budget is not spent.

`[SOURCE CODE]` The controller handles it as an **ordinary attempt failure**: it logs, rotates to the **next protocol on the same server**, records **no wedge**, and creates no terminal state. The v1.0.37 no-terminal contract and the per-attempt timeout semantics are intact.

`[TEST]` `missingConfigRotatesToTheNextProtocolOnTheSameServer` — the connect order is `(s1, OPENVPN_TCP)` then `(s1, SOFTETHER_TCP)`, and the run ends `Connected`.

### 6.2 The classification table

`[SOURCE CODE]` One line per attempt: `[AUTO] ATTEMPT_CLASS <class> outcome=<name> protocol=<id>`.

| Class | From | Means |
|---|---|---|
| `CONNECTED` | `TUNNEL_UP` | verified tunnel |
| `REFUSED_START` | `NATIVE_BUSY` | **our own** previous native operation held the slot; the server was never contacted |
| `TIMEOUT_NO_VERDICT` | `TIMED_OUT` | no answer inside the budget — a silent/filtered endpoint looks exactly like a dead one |
| `ENDPOINT_REJECTED` | `NATIVE_ERROR` | the stack delivered a terminal failure |
| `MISSING_CONFIG` | `MISSING_CONFIG` | the protocol had no data to dial — a data defect, never the endpoint |

### 6.3 What the log cannot separate, and why that is stated rather than guessed

`[SOURCE CODE]` The brief asks for *dead endpoint / refused / timeout / TLS-or-protocol error / auth / missing-config / wrong-data suspicion*. Four of those collapse into `ENDPOINT_REJECTED`, because `TunnelStateWatcher` maps SoftEther `STATE_ERROR` and OpenVPN `LEVEL_AUTH_FAILED` onto the **same** `onAttemptFailed` callback and does not forward the stack's `logmessage` to this seam. Separating "refused port" from "rejected TLS handshake" from "auth rejection" needs a `TunnelStateWatcher` change, which is **outside this phase's authorised file set**.

`[SOURCE CODE]` `WRONG_DATA_SUSPECT` is deliberately **never emitted** per attempt: it is a property of the *server record* (a port with no config, an SSTP flag with no port), not of a single dial. It is reported by this audit instead of being asserted from one attempt. `[INFERENCE]` Emitting it from a single failed dial would be exactly the kind of invented precision the brief forbids.

`[TEST]` `AutoConnectAttemptClassificationTest` pins all five mappings, that the table is total over the enum, and that `MISSING_CONFIG` is distinguishable from `TIMED_OUT`.

---

## 7. `VPNGateConnection.kt` — authorised conditionally, **not needed**

The authorisation was: *"AUTHORIZED only if `openVpnConfigData != null` is NOT enough to model config availability. State in the report exactly why it was needed."*

`[SOURCE CODE]` It was **not needed**, and the reason is mechanical: `openVpnConfigDataUdp` is a derived property (`OpenVpnConfigTransform.forUdp`), which returns the same profile with the transport rewritten and is null **iff** its input is null. Config availability is therefore a single boolean covering both transports, and one flag on the candidate models it exactly. `VPNGateConnection.kt` was not modified.

---

## 8. Suspected issues that need live data or a device

| # | Issue | Evidence | Status |
|---|---|---|---|
| S-1 | `VpnGateHtmlParser` sets `configAvailable = true` from a `do_openvpn` href, but nothing in the app ever downloads that link. | `[SOURCE CODE]` — only match of `do_openvpn` in `app/src/main` is `VpnGateHtmlParser.kt:437`. | **Not a gate bypass.** The gate keys on real bytes (`openVpnConfigData` ← `rawConfig`), so an HTML-only row is correctly unusable. The flag does inflate `ov["supported"]` and therefore `ServerQualityCalculator`'s OpenVPN points (`[SOURCE CODE]`). Changing it alters ranking and UI badges, which this phase did not authorise. **Reported, not changed.** |
| S-2 | `Has_IPv6` is written `false` for every row while the page never publishes it. | `[LIVE]` 0 occurrences. | Inert field; documented as `[UNKNOWN]` (§5.3). |
| S-3 | `SSTP_Supported` is `true` on 229/229 rows, including 33 with no port. | `[TEST]` | Consumer already requires a port or the documented 443 default. No change. |
| S-4 | `attempts` that reach `MISSING_CONFIG` in production would prove the TASK A gate has a hole. | `[INFERENCE]` | The gate makes it unreachable; the outcome exists as defence in depth. A device log containing `ATTEMPT_CLASS MISSING_CONFIG` would be a defect report in itself. |
| S-5 | The whole block is **not device-verified**. | — | `[UNKNOWN]` See §9. |
| **R-1** | **Two skip/continuation tests are timing-sensitive, and one failure shows a real duplicate dial.** | `[TEST]` `pressWhileConnectedDisconnects` failed once in four full-suite runs; `twoImmediateSkipsNeverDialTheSameServerTwice` failed once, logging `[s2, s1, s1, s1, s1, s1]`. Both pass in isolation and both passed on the recorded green run. | **Reported, not fixed — it is v1.0.52 territory, not this block's.** `skipToNextServer()`'s Connected branch cancels the job and launches a continuation but does **not** publish `Disconnected` first, so a second immediate press still sees `Connected`, re-enters the same branch, cancels the in-flight continuation and relaunches — two continuations can dial. That is the exact class of bug v1.0.52 §1.6.44 TASK B.3(a) set out to close, so the guard is narrower than claimed: it protects `job` from passing through null, but not the branch from being re-entered. **Follow-up block required.** |

---

## 9. Required device tests (PENDING — none run in this phase)

| ID | Check | Pass condition |
|---|---|---|
| V-1 | A config-less OpenVPN server is never selected. | Auto Connect never logs `connect:` for a server whose row has no config; no 8 s stall precedes a protocol rotation. |
| V-2 | The gate does not shrink the usable set. | With a config-carrying dataset, OpenVPN is still offered and still connects. |
| V-3 | `123.48.124.169` dials 1558. | A device log shows the OpenVPN endpoint at 1558, not 1534. |
| V-4 | SSTP with a `host:port` hostname connects. | The SSTP attempt reaches the intended endpoint instead of failing to resolve `host:port:port`. |
| V-5 | `MISSING_CONFIG` never appears. | If `ATTEMPT_CLASS MISSING_CONFIG` appears in a device log, TASK A has a hole — treat as a bug. |

---

## 10. A–M report

**A. What was asked.** Fix OpenVPN being treated as usable without a config (TASK A); audit the server data field by field against all parsers and the Python oracle (TASK B.1–B.7); make the embedded config authoritative over the link/column in the merger and the publisher; split `host:port`; verify the unobservable flags; add an attempt-outcome classification; report; build; commit twice; do not push.

**B. What was done.**
- **TASK A:** `AutoModeCandidate.openVpnConfigAvailable` (fail-closed default), derived from real bytes in `AutoModeEngine.toCandidate()`, enforced in `usableProtocols()`. Parser no longer claims `supported` without bytes.
- **TASK B.3:** config-authoritative ports in `EmergencyCsvParser`, `VpnServerMerger` (record invariant) and `emergency_csv_publisher.py`, with conflicts logged/recorded. No schema change.
- **TASK B.4:** SSTP `host:port` — parser verified correct by test, publisher fixed and tested.
- **TASK B.5:** `SE_UDP_Port` all-zero is correct; `Has_IPv6` is unobservable and inert; `SSTP_Supported` consumer already correct.
- **TASK B.6:** `AttemptOutcome.MISSING_CONFIG` (no silent return, no timeout burn, no wedge, no terminal) plus a per-attempt `ATTEMPT_CLASS` log line and a documented statement of what it cannot separate.
- **TASK B.2/B.7:** the field-by-field table in §3.

**C. What was confirmed.** The OpenVPN-without-config defect is real and reproducible; the port conflict is real, single, and identical across snapshots; the SSTP `host:port` shape is live and recurring; `SE_UDP_Port` all-zero is correct data, not a bug.

**D. What was fixed.** The gate; the parser's false `supported` claim; the parser's silent column override; the merger invariant; the publisher's port authority; the publisher's SSTP split; the silent OpenVPN return.

**E. What was deliberately not changed.** `Has_IPv6` semantics; `SSTP_Supported`; `VpnGateHtmlParser`'s `configAvailable` (ranking/UI impact, not authorised); `TunnelStateWatcher` (not authorised); the §10 CSV schema; `versionCode`/`versionName`.

**F. Evidence labels.** Every claim above is labelled. `[PHYSICAL DEVICE]` appears nowhere because no device ran.

**G. Snapshot.** The dataset advanced four times (`a5cb153` 280 → `0387d37` 283 → `b5e0f7e` 271 → `4afa7e3` 229). Figures are pinned to `4afa7e3`: 229 rows, 166 without config (72.5 %), 2 conflicts. The operator's 280/218/77 % is exactly right for `a5cb153`; the percentage varies by refresh and the `123.48.124.169` conflict is in every snapshot. `153.180.162.195` is UNKNOWN at `a5cb153` and confirmed at `4afa7e3` (1820 vs 1759).

**H. Tests.** See §11 for counts. Python: 40 pass.

**I. Build.** See §11.

**J. Diff scope.** See §12.

**K. Risks.** S-1 (ranking inflation from the HTML `configAvailable` claim) is the only known remaining correctness issue and is out of scope. The gate is defence-in-depth plus primary fix, so a future selection path that forgets the flag still fails safe.

**L. Unresolved.** Device verification (V-1..V-5) is entirely pending. The `123.200.94.11` row is no longer in the snapshot, so the live shape is pinned by test rather than by data. **O-1 (§12.4):** the publisher and its test have never been tracked by git, so the TASK B.3/B.4 Python changes exist only in the working tree; committing them means adding two new files, which needs the operator's word.

**M. Nothing pushed.** No push, no tag, no `--force`, no reset/clean/stash/checkout. `vpngate_collector.py` and `servers.csv` were not staged; `emergency-sources/servers.csv` was neither written nor staged. The publisher's absence from Commit 1 is the deliberate choice documented as O-1, not an oversight.

---

## 11. Verification results

### 11.1 Test counts

| Suite | Tests | Failures | Errors | Skipped | Baseline | Delta |
|---|---|---|---|---|---|---|
| `:app:testProDebugUnitTest` | **941** | 0 | 0 | 1 | 925 | **+16** |
| `:SoftEtherClient:test` | **184** | 0 | 0 | 0 | 184 | 0 (untouched) |
| `test_emergency_csv_publisher.py` | **40** | 0 | — | — | 24 | **+16** |

`[TEST]` The +16 JVM cases: 4 in `AutoModeControllerTest` (config absent, HTML-link-only, port-facts preserved, `MISSING_CONFIG` rotation), 4 in the new `AutoConnectAttemptClassificationTest`, 6 in `EmergencyCsvParserTest` (port conflict, port-without-config, three SSTP-split cases, merged API+emergency), 2 in `MergerOracleTest` (config authority, merged rows). The +16 Python cases: 9 in `ConfigAuthoritativePorts`, 7 in `SstpHostPortSplit`.

### 11.2 Test instability observed during this phase — reported, not hidden

`[TEST]` Across four full-suite runs:

| Run | Result |
|---|---|
| 1 | 6 failed — all the pre-existing OpenVPN candidates that now need the config fact; **fixed** by the `candidateWith` default |
| 2 | 1 failed — `pressWhileConnectedDisconnects` (`expected Disconnected but was Error(no_compatible_server)`) |
| 3 | 1 failed — `twoImmediateSkipsNeverDialTheSameServerTwice` (`[s2, s1, s1, s1, s1, s1]`) |
| 4 | **green** — 941 / 0 / 0 / 1 |

Both failures pass in isolation. Run 3's log is the important one: a server dialled **five times** is a genuine duplicate dial, not a test artefact, and it sits in the v1.0.52 skip/continuation path that this block did not touch. It is recorded as **R-1** in §8 and in spec §1.6.45 item 18. The recorded green run is run 4.

### 11.3 Build

`[TEST]` `:app:assembleProDebug` SUCCESSFUL under `CI=true --rerun-tasks --no-build-cache`. APK size, SHA-256 and `versionCode`/`versionName` are recorded in §12.3.

### 11.4 §22 byte-identity

`[SOURCE CODE]` §22 (Build and Verification Methodology) is **byte-identical to HEAD**: 99 lines, SHA-256 `adf12858b72ed13742252c512a8728e9141e415cc55d09c6409af4e8e622020e` on **both** sides. Method: the section was extracted from `git show HEAD:PROJECT_SPECIFICATION.md` and from the working tree by anchoring on the `## 22. Build and Verification` heading (HEAD line 5345, working tree line 5393 — the offset is exactly the 48 lines this revision added above it), then compared with `diff`; the two extracts are byte-for-byte equal and hash to the same value. **Correction to an earlier figure in this report:** the value `1a05…6d0c` printed while drafting was not the hash of the §22 extract (the extraction offsets were wrong at that point, so it hashed a 51-line fragment); the measured value above is the one produced by the correct extraction and is what the §22 identity claim rests on.

---

## 12. Diff scope

`[SOURCE CODE]` Measured from `git status --porcelain` and `git diff --cached --stat` at commit time, against baseline `4afa7e3cec82a1078a84dcde9ba2d6c876107ce2`.

### 12.1 Commit 1 — source and tests (10 files, **+923 / −41**)

```
 .../vpngate/automode/AndroidConnectionAdapter.kt   | 177 ++++++++++++++++++---
 .../unlimit/vpngate/automode/AutoModeController.kt |  68 +++++++-
 .../vn/unlimit/vpngate/automode/AutoModeEngine.kt  |   8 +
 .../vn/unlimit/vpngate/automode/AutoModeModels.kt  |  21 +++
 .../vn/unlimit/vpngate/merger/VpnServerMerger.kt   |  86 ++++++++++
 .../unlimit/vpngate/parser/EmergencyCsvParser.kt   | 125 ++++++++++++---
 .../AutoConnectAttemptClassificationTest.kt        |  90 +++++++++++      (new file)
 .../vpngate/automode/AutoModeControllerTest.kt     | 153 ++++++++++++++++++
 .../vpngate/collector/EmergencyCsvParserTest.kt    | 162 +++++++++++++++++++
 .../unlimit/vpngate/collector/MergerOracleTest.kt  |  74 +++++++++
 10 files changed, 923 insertions(+), 41 deletions(-)
```

| File | Change | TASK |
|---|---|---|
| `AutoModeModels.kt` | `openVpnConfigAvailable: Boolean = false` on `AutoModeCandidate`, fail-closed default, KDoc explaining why one flag covers both transports | A |
| `AutoModeEngine.kt` | `toCandidate()` derives the flag from the real bytes (`!openVpnConfigData.isNullOrBlank()`) | A |
| `AutoModeController.kt` | both OpenVPN branches of `usableProtocols()` require the flag; `AttemptOutcome.MISSING_CONFIG`; controller `continue`s to the next protocol on that outcome | A, B.6 |
| `AndroidConnectionAdapter.kt` | `connectOpenVpn()` reports `MISSING_CONFIG` instead of returning silently; `lastMissingConfig` cleared per attempt; the check runs **before** `awaitArmedTunnel`; `[AUTO] ATTEMPT_CLASS` line per attempt; `classifyAttempt()` | A, B.6 |
| `EmergencyCsvParser.kt` | `applyOpenVpnColumnPort()` with three cases; `OPENVPN_PORT_CONFLICT_CONFIG_WINS` / `OPENVPN_PORT_WITHOUT_CONFIG`; `supported` no longer claimed without bytes | B.3 |
| `VpnServerMerger.kt` | `enforceConfigAuthoritativePorts()` record invariant before port validation; §38 conflict recorded; `remoteField()` handles both `OpenVpnRemote` and `Map` | B.3 |
| `AutoModeControllerTest.kt` | `candidateWith()` gained `ovpnConfig`; 4 new cases | A |
| `AutoConnectAttemptClassificationTest.kt` | new file, 4 cases incl. the source-shape ordering guard | B.6 |
| `EmergencyCsvParserTest.kt` | 6 cases (port conflict, port without config, three SSTP `host:port`, merged API+emergency) | B.3, B.4 |
| `MergerOracleTest.kt` | 2 cases (config authority; merged rows) | B.3, B.7 |

### 12.2 Commit 2 — documentation (2 files)

| File | Change |
|---|---|
| `PROJECT_SPECIFICATION.md` | header `HEAD` → `4afa7e3…`, version 1.0.53; new §1.6.45 (18 items); v1.0.53 push-state paragraph; §20 changelog row `1.0.53`; §20 verification-status row `§1.6.45`. §22 byte-identical (see §11.4); §1.6.2–§1.6.44 untouched. |
| `AUTO_DATA_AUDIT_REPORT.md` | this report (new file, 12 sections + A–M). |

### 12.3 Build output

`[TEST]` `CI=true ./gradlew :app:assembleProDebug --rerun-tasks --no-build-cache` → **BUILD SUCCESSFUL in 6m 29s**, 114 actionable tasks executed, configuration cache entry reused.

| Artifact | Value |
|---|---|
| APK | `app/build/outputs/apk/pro/debug/app-pro-debug.apk` |
| Size | **63,800,978 bytes** (v1.0.52: 63,798,322 → **+2,656**) |
| SHA-256 | `9d5f6cda1f1ce25ae6fcb08188fed537c948483d5afa3ae834676840c59328eb` |
| `versionCode` | **144** (unchanged) |
| `versionName` | **2.4.11-dev** (unchanged) |

No build script, manifest or resource file was touched, so the version identity is unchanged by construction, not by assertion.

### 12.4 Deliberately **not** committed — and one item that needs a decision

| Path | State | Why |
|---|---|---|
| `vpngate_collector.py` | **modified, not staged** | The operator's instruction was explicit: do not touch it. The working-tree change (`OUT_CSV` `servers.csv` → `collector_servers_full.csv`, +5/−1) **predates this block** and was not made by it. |
| `emergency-sources/servers.csv` | tracked, **clean** | Not written to and not staged, as instructed. |
| `servers.csv` | gitignored | Not produced, not staged. |
| **`emergency_csv_publisher.py`** (1193 lines) | **untracked** | See **O-1** below. |
| **`test_emergency_csv_publisher.py`** (476 lines) | **untracked** | See **O-1** below. |
| `.dv6/`, `.dv7/`, `.vscode/`, `.workbuddy-ai/`, `emergency/`, `scripts/`, `test-evidence/`, `build_test_baseline.log`, and the older `*_REPORT.md` files | untracked, pre-existing | Outside this block's scope; left exactly as found. |

**O-1 — the publisher fix is real but is not in git, and cannot be committed without adding two new files.** `[SOURCE CODE]` `git ls-files --error-unmatch emergency_csv_publisher.py` fails: **neither the publisher nor its test has ever been tracked** (`git log -- emergency_csv_publisher.py` is empty). The tracked Python surface is `vpngate_collector.py` plus `tests/`. So TASK B.3's `enforce_config_ports()` and TASK B.4's `split_sstp_endpoint()` / `normalize_sstp_rows()` — and the 16 new Python cases that cover them — exist only in the working tree.

This block **did not** `git add` them. Editing an authorised file and adding a never-tracked file to the repository are different acts, and the second one changes what the repo contains rather than what a file says; the operator's authorisation named the files to *touch*, not files to *start tracking*. The choice is reversible in one direction only — a `git add` can be undone, an added file in history cannot. **Decision required from the operator:** if the publisher is meant to be tracked, `git add emergency_csv_publisher.py test_emergency_csv_publisher.py` and amend/extend Commit 1 is the whole fix; if it is deliberately local-only (its output `servers.csv` is gitignored, which is consistent with that reading), then nothing is missing and this report is the record of the change.

---
