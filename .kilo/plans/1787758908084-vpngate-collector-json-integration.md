# Integrate vpngate_collector.py output (JSON/CSV) into the Android app

## Background

### The problem
- The live VPN Gate API (`https://www.vpngate.net/api/iphone/`) returns only **15 CSV columns** — no per-protocol port columns (`tcpPort`, `udpPort`, `seTcpPort`, `seUdpPort`, `isL2TPSupport`, `isSSTPSupport`).
- The current Kotlin parser (`VPNGateConnection.fromCsv()` at `app/src/main/java/vn/unlimit/vpngate/models/VPNGateConnection.kt:336`) expects 21+ columns but receives 15, so all port fields are 0.
- A fallback (`derivePortsFromOpenVpnConfig()` at line 150) parses the OpenVPN config blob to extract TCP/UDP ports, but it is incomplete: it cannot fill `seUdpPort`, `isL2TPSupport`, or `isSSTPSupport`.
- The protocol-selection dialog in `DetailActivity.showVpnProtocolSelectionDialog()` (line 1200) hides entries when ports are 0, so most servers show fewer protocol options than they actually support.

### The solution
- The user has created `vpngate_collector.py` (`morteza-taheri/SoftEther` root) that scrapes the **full HTML table** at `https://www.vpngate.net/en/`, which exposes 21 columns with explicit port data for every protocol.
- It merges results from the main HTML, the API CSV, and mirror sites, then exports:
  - `servers_all.json` — all unique servers with full protocol/port data + quality scores
  - `servers_softether.json` — filtered subset: only servers with SoftEther TCP or UDP
  - `servers.csv` — flattened version of the same data

## JSON schema (from `new_server()` + `export_json()`)

```
{
  "generatedAt": "2026-08-26T22:55:02Z",
  "source": "VPN Gate multi-source collector",
  "count": 100,
  "servers": [
    {
      "hostname": "xxxxxxxx",
      "ip": "x.x.x.x",
      "country": "US",
      "countryLong": "United States",
      "sessions": 5,
      "uptime": 93,
      "totalUsers": 1000,
      "score": 7500000,
      "ping": 45,
      "speed": 100000000,
      "softEther": { "tcp": 443, "udp": true },
      "openVPN":  { "tcp": 443, "udp": 443 },
      "l2tp": true,
      "sstp":     { "host": "name.opengw.net", "port": 443 },
      "sources": ["html", "api"],
      "sourceCount": 2,
      "valid": true,
      "qualityScore": 85
    }
  ]
}
```

### Field mapping to existing `VPNGateItem` / `VPNGateConnection`

| JSON field | Kotlin target | Notes |
|---|---|---|
| `softEther.tcp` (int) | `seTcpPort` | Same as OpenVPN TCP port — SoftEther auto-detects on the TCP listener |
| `softEther.udp` (bool) | `seUdpPort` | Set to same value as `softEther.tcp` if true (the HTML source doesn't expose a separate UDP port; non-zero value suffices to show the UDP option in the dialog) |
| `openVPN.tcp` (int) | `tcpPort` | Direct map |
| `openVPN.udp` (int) | `udpPort` | Direct map |
| `l2tp` (bool) | `isL2TPSupport` | Boolean → int (0/1) |
| `sstp.port > 0` (int) | `isSSTPSupport` | Boolean → int (0/1) |
| `score` (int) | `score` | Direct map |
| `ping` (int) | `ping` | Direct map |
| `speed` (int) | `speed` | Direct map |
| `sessions` (int) | `numVpnSession` | Direct map |
| `uptime` (int) | `uptime` | Direct map |
| `totalUsers` (int) | `totalUser` | Direct map |
| `country` (string) | `countryShort` | Direct map |
| `countryLong` (string) | `countryLong` | Direct map |
| `hostname` (string) | `hostName` | Direct map |
| `ip` (string) | `ip` | Direct map |
| `qualityScore` (int) | — | New field (optional) |
| `sources` (array) | — | New field (optional) |

### What the JSON does **not** provide
- `openVpnConfigData` (the base64 OpenVPN config blob) — still needs the CSV/API for this, or it can be fetched per-server from the live API.
- `totalTraffic`, `logType`, `operator`, `message` — CSV-only fields.

## Decision

**Approach: Dynamic JSON fetch with embedded fallback.**

1. The Python script runs periodically (or on-demand) and commits `servers_all.json` + `servers_softether.json` + `servers.csv` to `morteza-taheri/SoftEther` master.
2. The Kotlin app adds an **optional enrichment step**: before or after fetching the CSV, it also fetches the JSON from a GitHub raw URL configured in `AppConfig`.
3. Servers from the JSON are matched to CSV-parsed servers by `hostName`/`ip`, and their port fields are merged in.
4. If the JSON fetch fails (offline, network error), the app falls back to the existing CSV + config-blob derivation, so there is zero regression.
5. The existing CSV feed continues to provide `openVpnConfigData` and metadata that the JSON does not include.

**Alternative considered & rejected:** Embedding the JSON as a raw asset — would provide offline capability but becomes stale immediately and requires app release cycles to refresh. Given that the app already depends on live network data, this adds complexity without proportional benefit for the initial integration.

## Tasks

### 1. Create Kotlin data models for the JSON output
- **File:** `app/src/main/java/vn/unlimit/vpngate/models/VPNGateHtmlServer.kt` (new)
- Data classes matching the JSON schema:
  - `VPNGateHtmlResponse(generatedAt, source, count, servers: List<VPNGateHtmlServer>)`
  - `VPNGateHtmlServer(hostname, ip, country, countryLong, sessions, uptime, totalUsers, score, ping, speed, softEther, openVPN, l2tp, sstp, sources, sourceCount, valid, qualityScore)`
  - `SoftEtherPorts(tcp: Int, udp: Boolean)`
  - `OpenVPNPorts(tcp: Int, udp: Int)`
  - `SstpInfo(host: String, port: Int)`
- Include a `toVPNGateConnection()` or `enrich(VPNGateConnection)` helper that maps fields per the table above (with the UDP→same-TCP-port convention).

### 2. Add JSON endpoint config
- **File:** `app/src/main/java/vn/unlimit/vpngate/utils/AppConfig.kt`
- Add:
  ```kotlin
  "vpn_html_servers_json" -> "https://raw.githubusercontent.com/morteza-taheri/SoftEther/master/servers_all.json"
  ```
- The user can change this to point at `servers_softether.json` if they prefer the filtered set.

### 3. Add JSON fetch + parse capability to `VPNGateApiService`
- **File:** `app/src/main/java/vn/unlimit/vpngate/api/VPNGateApiService.kt`
- Add a new endpoint method:
  ```kotlin
  @GET
  suspend fun getJsonString(@Url url: String, @Query("version") version: String? = null): String
  ```
- Create a parser function in `VPNGateConnection` companion object or a new util:
  ```kotlin
  fun parseHtmlJson(json: String): List<VPNGateHtmlServer>
  ```
  using `Gson().fromJson(json, VPNGateHtmlResponse::class.java)`.

### 4. Enrich servers in `ConnectionListViewModel.getAPIData()`
- **File:** `app/src/main/java/vn/unlimit/vpngate/viewmodels/ConnectionListViewModel.kt`
- After parsing the CSV and building `connectionList`, attempt to fetch the JSON.
- Parse JSON → for each `VPNGateHtmlServer`, find the matching `VPNGateConnection` by `hostName` or `ip` and call `enrichConnection(htmlServer)`.
- The enrichment populates `seTcpPort`, `seUdpPort`, `tcpPort`, `udpPort`, `isL2TPSupport`, `isSSTPSupport` without overriding existing non-zero values (to preserve CSV-derived config-blob ports if the HTML data is missing a port).
- If JSON fetch/parse fails, log and continue with CSV-only data (no behavior change).

### 5. Modify `VPNGateConnection` to accept enrichment
- **File:** `app/src/main/java/vn/unlimit/vpngate/models/VPNGateConnection.kt`
- Add `isL2TPSupport` and `isSSTPSupport` as settable fields (currently private with only constructor access — they're parsed from CSV at lines 365/368).
- Add an `enrichFromHtmlServer(htmlServer: VPNGateHtmlServer)` method:
  ```kotlin
  fun enrichFromHtmlServer(html: VPNGateHtmlServer) {
      if (html.softEther.tcp > 0) seTcpPort = html.softEther.tcp
      if (html.softEther.udp && seUdpPort == 0) seUdpPort = html.softEther.tcp  // reuse TCP port as UDP port
      if (html.openVPN.tcp > 0) tcpPort = html.openVPN.tcp
      if (html.openVPN.udp > 0) udpPort = html.openVPN.udp
      if (html.l2tp) isL2TPSupport = 1
      if (html.sstp.port > 0) isSSTPSupport = 1
  }
  ```

### 6. Update `VPNGateItem` to preserve softEther UDP boolean semantics
- **File:** `app/src/main/java/vn/unlimit/vpngate/models/VPNGateItem.kt`
- No schema change needed — `seUdpPort: Int` already works as a presence indicator (used as `> 0` in dialog logic). The enrichment sets it to the TCP port value when HTML says UDP is available.

### 7. Update Room entities if storing enriched data
- The existing `VPNGateItem` already has all the needed columns (`seTcpPort`, `seUdpPort`, `tcpPort`, `udpPort`, `isL2TPSupport`, `isSSTPSupport`).
- When `connectionList.toVPNGateItems()` is called (line 92 of `ConnectionListViewModel.kt`), the enriched `VPNGateConnection` objects convert to `VPNGateItem` with the new port values.
- No schema migration needed since no columns are added.

### 8. Validation
- **Compile check:** `./gradlew :app:compileProReleaseKotlin` (proRelease is the only enabled variant on non-CI machines).
- **Unit test (if feasible):** Add a test that parses a sample `servers_all.json` fixture and verifies the enrichment logic sets ports correctly on a `VPNGateConnection` with all-zero ports.
- **Manual test:** Run the app, refresh the server list, open a server detail — verify that servers which previously had no protocol dialog now show OpenVPN TCP/UDP, SoftEther TCP/UDP, L2TP, and SSTP options.

## Risks / failure modes
- **GitHub rate limiting / downtime:** The JSON fetch is optional — if it fails, the app falls back to CSV + config-blob derivation (existing behavior, zero regression).
- **Hostname/IP mismatch:** If the JSON and CSV use slightly different hostname casing or the API returns `null` hostnames, the join key may fail to match. Mitigation: match case-insensitively on both `hostName` and `ip`; the `server_key()` logic in the Python script already normalizes to lowercase, so the Kotlin matcher must too.
- **SoftEther UDP port value:** The HTML only says "UDP supported" (boolean), not the port number. Setting `seUdpPort` to the TCP port value is a reasonable convention since SoftEther UDP (RUDP) typically uses the same port. The app's connection logic uses `seUdpPort` primarily as a presence check (`> 0`).
- **Data freshness:** The JSON is a snapshot. If the user wants always-live data, they would need to deploy the collector as a scheduled job. This is explicitly out of scope for this plan.

## Out of scope
- Porting the full HTML scraping logic to Kotlin (the app would then be dependent on parsing VPN Gate's HTML structure at runtime, which is fragile). Using the pre-generated JSON is more robust.
- Embedding the JSON as a static asset (can be a follow-up if offline support is requested).
- Changing the CSV as the primary feed — it still provides `openVpnConfigData` which the JSON does not include.
- Running the Python script itself (the user has already verified it works and will commit the output).
