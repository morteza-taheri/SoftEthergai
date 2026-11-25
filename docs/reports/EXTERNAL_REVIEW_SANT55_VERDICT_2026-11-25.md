# External review (SANT 5.5) — verdict and remediation

Date: 2026-11-25
Repo: `C:\Dev\SoftEthergai` (fork of `morteza-taheri/SoftEthergai`)
Scope: the five sections of the external review, checked line by line against the
working tree. Nothing was accepted on the review's word alone; every claim below
is marked **confirmed**, **refuted**, **stale** or **partly** with the evidence
that decided it.

No commit, no push, no release, no version bump.

---

## 1. Auto Connect / unstable server

| Claim | Verdict | Evidence |
|---|---|---|
| One failed probe (~17 s) declares the tunnel lost | **stale** | `TunnelLivenessWatchdog.DEFAULT_SILENCE_TIMEOUT_MS = 30_000`, `lossThreshold = 3`, sampled at 1 Hz → loss at ≈33 s. A failed probe records no proof but is never a verdict by itself (`TunnelLivenessProbe` only sets `lastProofAtMs` on success) and is retried after `PROBE_RETRY_MS = 2_000`. The 17 s figure belongs to the pre-v1.0.81 15 s window. |
| `AutoModeRecoveryPolicy` only pauses, never terminates | **confirmed (by design)** | `recordFailure()` returns `paused = true` after `pauseAfter`, then restarts the backoff. The terminal `ERROR_RECONNECT_LIMIT` was removed in the previous pass; `AutoModeControllerTest.allServersFailYieldsError` now only expects an error for a user-started run. |
| The candidate list is exhausted because the unstable server is retried every cycle | **partly confirmed** | `onUnexpectedDisconnect` rotates away only from the **second** consecutive loss (`rotateAwayFromDropped = consecutiveFailures > 1`), and when the tail is empty it clears `sessionTriedKeys` and re-walks the full provider list. A high-quality unstable server therefore returns to the front every cycle. |
| `FAILURE_COOLDOWN_MS` does not cover post-connect failures | **confirmed** | It is an *attempt*-failure ledger keyed in `AutoModeHistory`; a server that reaches CONNECTED never records one, and `recoveryPolicy.recordSuccess()` resets the counter on every connect. |

**Fix — `ServerQuarantine`** (`app/.../automode/ServerQuarantine.kt`, new)

- records tunnel **uptime** at the loss funnel (`noteTunnelUptime`), which is the
  only signal that separates "dropped us after 12 s" from "three-hour session,
  then the phone roamed";
- a drop inside `RAPID_DROP_MS = 60 s` is a strike; `STRIKES_TO_QUARANTINE = 2`
  consecutive strikes start a cooldown of 5 / 10 / 20 / 30 min (capped);
- a tunnel that survives `STABLE_UPTIME_MS = 2 min` is forgiven immediately;
- bounded to 128 entries, in memory only, no hostnames persisted;
- `runAutoMode` filters the candidate list (including an override list, which is
  how the recovery path re-seeds the server that just dropped); if **every**
  candidate is cooling down the unfiltered list is used — the run must never
  turn a transient condition into `ERROR_NO_SERVER`;
- an explicit disconnect clears the ledger (the user asking for a fresh attempt).

Tests: `ServerQuarantineTest` (10), `AutoModeQuarantineTest` (2 — the second
drives the real controller: alpha flaps twice, the next run dials beta).

---

## 2. Server-list refresh

| Claim | Verdict | Evidence |
|---|---|---|
| The app's own tunnel is not used / a foreign VPN is bypassed | **confirmed** | `routeLadder` offered only `TUNNEL` (own, usable) or `PHYSICAL`, and `productionBoundFetcher()` binds PHYSICAL to a **NOT_VPN** network. With a third-party VPN up, every refresh left over the censored Wi-Fi/cellular. |
| Timeouts are fixed and short | **partly** | The *outer* deadline is configurable (Settings, 10–180 s, default 45). The **per-source race timeout was hard-coded at 12 s**, so raising the setting changed nothing — the run still aborted at 12 s. That is a real bug. |
| Parallel download of every candidate | **true, mitigated** | CSV is fetched only when HTML yields nothing and has no mirror candidates; HTML races the canonical page plus bootstrap/cached mirrors. No hedging was added (see "not done"). |
| `Accept-Encoding: gzip` set by hand | **refuted** | No manual header; OkHttp negotiates and decompresses gzip (measured: 218 KB raw → 35 KB gzip). |

**Fixes**

1. **`CollectorPath.FOREIGN_VPN`** (`HttpFetcher.kt`, `VpnServerRepository.kt`,
   `GlobalVpnTracker.currentForeignVpnNetwork()`).
   - A validated (`INTERNET` + `VALIDATED`) VPN that this app does **not** own is
     now its own rung, between TUNNEL and PHYSICAL.
   - Ownership is decided by `NetworkCapabilities.getOwnerUid()` through the
     existing `VpnOwnerUidReader`/`OwnedVpnDecision` pair (API 30+); below that
     it falls back to "a VPN network exists and this app is neither connected nor
     connecting", the same conservative rule Auto Mode uses.
   - No PHYSICAL fallback behind it — a user who turned a VPN on did not ask the
     app to route around it. Same rule as REQ-ROUTE-001 for the own tunnel.
   - Its fetcher binds to that network per request (never
     `bindProcessToNetwork`), and the log says `path=foreign-vpn`, so a request
     that rode a tunnel is never reported as physical.

2. **Per-source timeout derived from the budget** — `sourceTimeoutMs = 45 % of
   mainTimeoutMs` (12–90 s), `directoryTimeoutMs = mainTimeoutMs / 6` (8–20 s).
   The 45 % leaves room for the mirror retry inside the same deadline.

3. **Mirror discovery no longer gates the page** — it used to be awaited *before*
   the HTML fetch, so every refresh paid `directory + page` serially (up to
   8 + 12 s). It now runs concurrently, and the first HTML race includes the
   bootstrap + cached mirrors, which need no network to know about. On a censored
   canonical host the mirror answers while the canonical request is still
   hanging; on a healthy one the canonical page wins the race as before.

4. **The 8 s TUNNEL rebind wait is gated on a stale network existing** — this was
   the largest remaining latency bug and the review did not find it. The rebind
   retry polls `currentVpnNetwork()` for up to 8 s **per source** even when there
   is no VPN network at all, i.e. 24 s added to every failed refresh on a dead
   tunnel. It now only waits when a network object still exists (the handover
   case it was written for). Side effect: `VpnServerRepositoryR4bTest`'s
   50-iteration concurrency test dropped from ~20 min to ~2 s.

---

## 3. TCP/UDP swap in import/export

| Claim | Verdict | Evidence |
|---|---|---|
| `forUdp` assumes the stored profile is TCP | **confirmed** | Literal `replace("proto tcp", "proto udp")` + `remote $host $tcpPort`. With a UDP profile both replacements found nothing, so the "TCP" option returned a UDP profile and the "UDP" option was a no-op by accident. |
| The TCP path hands over the raw profile | **confirmed** | `AndroidConnectionAdapter.connectOpenVpn` used `conn.openVpnConfigData` for TCP (skipping even the domain transform); `DetailActivity.handleImport`/`loadVpnProfile` had the same shape. |
| The parser reads `tcp=`/`udp=` as ports | **true for the current page, wrong for the download form** | Live page (2026-11-25): `do_openvpn.aspx?...&tcp=443&udp=1195` / `udp=0` = *ports*, 0 = unsupported. The download form the operator quoted (`openvpn_download.aspx?...&udp=1&port=1195`) uses flags, and would have stored port **1**. |
| Export covers one server only | **confirmed (product limit, not a bug)** | `ServerActions.toCsv` writes one 29-column row. Both transports round-trip through the explicit port columns. |

**Fixes**

- `OpenVpnConfigTransform.forTransport(config, wantUdp, tcpPort, udpPort, ip)`:
  parses the profile's real `proto` line (including `tcp-client`, `udp6`) and the
  matching `remote` line, and rewrites only those. Everything else is preserved
  byte for byte. `forUdp` delegates to it and keeps its documented "no TCP port /
  no IP → unchanged" contract.
- `VPNGateConnection.getOpenVpnConfigDataString(useUdp)` — one transport-aware
  getter; `openVpnConfigDataUdp` now uses the same transform.
- `AndroidConnectionAdapter.connectOpenVpn` and both `DetailActivity` call sites
  use it, so connect and export always match the chosen transport.
- Parser: a `port=` parameter makes `tcp`/`udp` flags instead of ports, and
  `openvpn_download` links are recognised alongside `do_openvpn` (so `sid`/`hid`
  are still captured if the page ever publishes only the download form).

Tests: 7 new cases in `OpenVpnConfigTransformTest` (UDP→TCP, TCP→TCP, UDP→UDP,
`tcp-client`, multi-`remote`, domain form, no published port).

---

## 4. SSTP

| Claim | Verdict | Evidence |
|---|---|---|
| `connectSstp` always uses `$hostName.opengw.net` and ignores `SSTP_Hostname` | **refuted** | `VpnConnectionMapper` stores `hostName` **without** the `.opengw.net` suffix (`OPGW_SUFFIX` is stripped on the way in), and the page's `SSTP Hostname` is exactly `<hostName>.opengw.net`. `calculateHostName` re-appends the suffix, which is correct. |
| DNS does not use the resilient resolver | **confirmed** | `SSLTerminal` creates its socket with `InetSocketAddress(sslHostname, port)` → the system resolver. `ResilientDns` only covers OkHttp, and only has fallback addresses for `vpngate.net`. |

**Fix — `SstpHostResolver`** (`app/.../network/SstpHostResolver.kt`, new)

- resolves the hostname off the main thread: system DNS first (accepting only a
  **global** IPv4, so a poisoned private/loopback answer becomes "no answer"),
  then DNS-over-HTTPS against `1.1.1.1` and `dns.google`;
- the address is written into the SSTP stack's own preference
  (`OscPrefKey.HOME_HOSTNAME_IP`, appended at the end of the enum) on **every**
  connect — including the empty value, so a stale address from a previous server
  can never be dialled;
- `SSLTerminal` uses it only as the **socket target**; `sslHostname` still drives
  SNI and certificate verification, so TLS identity is unchanged;
- an empty preference preserves the old behaviour exactly. The JSON parsing and
  the address filter are pure and unit-tested (`SstpHostResolverTest`, 7).

---

## 5. Submodules

- All three are **vendored** (no `.git`, tracked as regular files: 89 / 102 / 266
  files), so the review's "N files behind upstream" cannot be reproduced from
  this tree. Not disputed, not verified.
- **No wholesale update applied.** The trees carry substantial local work
  (SoftEther JNI + protocol, `OpenVPNService`, `SstpVpnService`), and the FGS
  change the review itself flags as conflicting with this project's service
  management cannot be validated without a device.
- The upstream "fail when DHCP assigns no usable IP" commit is a real gap:
  `SoftEtherClient.doDhcp` accepts any non-zero assigned IP, and
  `ConnectionController` falls back to a hard-coded config when DHCP fails. It was
  **not** applied: that fallback may be load-bearing for hubs without DHCP, and
  changing connection establishment in a vendored module is exactly the kind of
  unverifiable edit this pass avoids. The new quarantine now breaks the resulting
  flap loop at the Auto Mode layer, which is the correct layer for it.

---

## Verification

- `:app` 1520 tests, `:SoftEtherClient` 191, `:sstpClient` 3 → **1714 tests,
  0 failures, 0 errors, 1 skipped** (+32 versus the previous pass).
- `:app:assembleProDebug` — see the build log for the APK path.
- Live page check on 2026-11-25 confirmed `do_openvpn?tcp=443&udp=1195` and
  `udp=0` (no UDP) shapes, 218 KB raw HTML.

## Deliberately not done

- Hedged/staggered source starts: with HTML-first tiering the only multi-candidate
  race is 1 canonical + 6 bootstrap/cached mirrors, all of them small; splitting
  that race adds state for no measured gain.
- Whole-list CSV export (a product feature, not a defect).
- Upstream submodule merges (see §5).
- "Connect with Auto Mode first, then refresh through the tunnel": exists as the
  one-shot retry only when a tunnel becomes usable during the same refresh; a
  connect-then-refresh flow is a product change.

---

# Part 6 — Device verification (2026-11-25, 08:11–08:40)

Device: `5585fecc`, Xiaomi `2201117TG` (`spes`), Android 13, package
`vn.unlimit.vpngatepro`, installed with `adb install -r` over existing data
(`firstInstallTime` unchanged, `versionCode=154`, `versionName=2.4.20-dev`).

Developer Mode was off (it ships off), so `CollectorLog` and `AutoModeLogStore`
were silent. It was enabled for the session through the app's own preference
(`SETTING_DEVELOPER_MODE`), which is the switch the Settings screen writes.

## 6.1 Two defects found ON DEVICE that no test or review had caught

### D-1 — the cleartext registry was fed after the race, not before

First refresh with logging on (08:15:43) produced **16 `CLEARTEXT-BLOCKED`
lines**:

```
GET http://150.40.105.5:32536/en/                     -> answered
CLEARTEXT-BLOCKED host=122.133.207.17 ...             -> refused locally
CLEARTEXT-BLOCKED host=14.7.148.12   ...             -> refused locally
... 8 distinct hosts ...
Mirrors available: 12 (discovered=5, cached=12, bootstrap=6)
```

Eight of the twelve mirrors in that run were cached addresses the app had
stored itself on an earlier refresh. `CleartextMirrorPolicy.registerAll` was
only called from `VpnGateSourceCandidates.numberedMirrors`, which runs *after*
`VpnGateMirrorSource.discoverMirrors` has already fired requests at the cached
set — so the app paid for a 12-way fan-out and could use 4 of the 12 slots.

Fix: `VpnGateSourceCandidates.registerForCleartext(...)` is now called before
any mirror is fetched, in both `discoverMirrors` and `VpnGateHtmlSource.fetch`.
Registration is still keyed on identities that already passed
`SourceUrl.forMirror`, so it cannot widen cleartext access to an arbitrary host.

After the fix (08:19:51), same device, same network:

```
CLEARTEXT-BLOCKED lines: 0
Fastest valid response from: http://122.133.207.17:47201/en/sites.aspx
Fastest valid response from: http://122.133.207.17:47201/en/
```

The mirror that was refused an hour earlier won both races.

### D-2 — the Settings timeout never reached the Update button

With `SETTING_SERVER_LIST_UPDATE_TIMEOUT_SECONDS = 120`, the run logged:

```
[budget] total=45000ms source=20250ms directory=8000ms
```

45 s is the hard-coded default, not the setting. `ServerListUpdates` passed the
setting into the repositories it builds, but `ConnectionListViewModel` built its
own as `VpnServerRepository(cacheDir = application.filesDir)` — and that is the
instance behind the Update button, i.e. the only path a user presses. The
scheduler honoured the setting; the button did not.

Fix: `mainTimeoutProvider: (() -> Long)?` on the repository, read per refresh,
wired in the ViewModel to `App.instance?.dataUtil?.getServerListUpdateTimeoutSeconds()`.
A provider rather than a value means a Settings change applies to the next
refresh without restarting or rebuilding the ViewModel.

After the fix (08:26:26, same setting):

```
[budget] total=120000ms source=54000ms directory=20000ms
```

A `[budget]` line is now emitted on every refresh so the setting is verifiable
from a log instead of by waiting out a slow network.

## 6.2 The five checks

| # | Check | Result | Evidence |
|---|-------|--------|----------|
| 1 | 90 s healthy tunnel, no false loss | **PASS** | MS-SSTP connected in 457 ms; 140 s observed with **0** `No proof of life` lines; `tun0` held `10.211.1.51/32` throughout |
| 2 | Auto Mode on an unstable server | **PASS** | forced drop → `vpn559198341 dropped after 3s; quarantined for 300s` → `Quarantine: skipping 1 unstable server(s) this run` → `Trying #1 vpn382152879` → connected. Auto Mode never stopped |
| 3 | Refresh through a tunnel | **PASS (own tunnel)** | `[ladder] order=TUNNEL,EMERGENCY`, `[bind] ok network=107`, 94 records collected, and a **new** mirror (`126.34.180.161:40210`) discovered through the tunnel |
| 4 | 120 s timeout is honoured | **PASS** | `total=120000ms source=54000ms directory=20000ms`; before the fix the same setting produced 45000 ms |
| 5 | SSTP | **PASS** | MS-SSTP is the Auto Mode priority protocol and carried real traffic: the whole refresh in check 3 went through `tun0` |

Check 3 was verified for the app's **own** tunnel. The `FOREIGN_VPN` rung (a
validated VPN owned by another app) could not be exercised because no other VPN
client is installed on this device; its unit coverage stands
(`VpnServerRepositoryForeignVpnTest`).

Check 5's TCP/UDP profile transform is covered by
`OpenVpnConfigTransformTest` (8 transport cases) rather than by a device export,
because export is a multi-step UI flow; the transform is pure string work and
the tests pin `proto tcp-client`, multi-`remote`, domain-form and no-port cases.

## 6.3 Observations worth recording

- **The HTML tier yields the same 99 records as the CSV** but in ~35 KB gzip
  instead of 1.33 MB, and it is the only source that publishes protocol
  columns. `HTML produced 99 records; CSV feed skipped` appears on every run.
- **The database holds 448 rows against 99 fresh records**
  (`Dataset outcome MERGE_WITH_EXISTING: 99 incoming vs 448 stored`). That is
  the documented merge policy, not a defect, but it means the list a user sees
  is much larger than one feed snapshot — worth knowing before reading "445
  servers" as a feed size.
- **Airplane-mode flapping** (used to simulate an unstable server) produced a
  chain of `Unexpected disconnect: X; reconnecting (attempt 1/5, delay N ms)`
  with the candidate pool shrinking 21 → 19 → 16 → 15 → 14. No terminal stop,
  no `ERROR_NO_SERVER`, and the walk restarts from the top once the pool is
  exhausted — which is the intended behaviour after v1.0.87.
- A drop that happens before `connectedAt` is stamped records no quarantine
  strike (`noteTunnelUptime` returns early when `startedAt <= 0`). Harmless —
  such a drop means the attempt never reached CONNECTED — but it is why some
  `Unexpected disconnect` lines carry no `dropped after Ns` companion.

# Part 7 — "OpenVPN cannot update the server list" (2026-11-25)

Operator report: *when the app uses the OpenVPN protocol it cannot update the
server list.* Three independent defects produced that symptom. Two were found
earlier in this document (D-1 self-exclusion, D-2 timeout setting); this part
records the third, which is the one specific to OpenVPN, and the end-to-end
device proof that all three are gone.

## 7.1 D-3: every VPN Gate profile is certificate-only

A VPN Gate `.ovpn` profile is the template generated by the SoftEther Server
Manager. It carries a **dummy** client certificate/key pair and leaves the
authentication directive commented out:

```
<cert>
-----BEGIN CERTIFICATE-----        (shared dummy pair, CN=VPNGateClientCert)
...
#auth-user-pass                    <-- commented out
```

Dump of the device database (`VPNGateItem.openVpnConfigData`, 2026-11-25):

| OpenVPN profiles stored | `auth-user-pass` active | `auth-user-pass` commented | inline `<auth-user-pass>` |
|---|---|---|---|
| 63 | **0** | **63** | **0** |

So `ConfigParser` derived `TYPE_CERTIFICATES` for **100 %** of servers, the core
never emitted `>PASSWORD:Need 'Auth'`, `OpenVpnManagementThread` never delivered
`mUsername` / `getPasswordAuth()`, and no credential was ever sent. The tunnel
therefore could not survive: relays that accept the anonymous certificate
re-authenticate later and answer `AUTH: Received control message: AUTH_FAILED`,
which is exactly the observed "tunnel dies 11–71 s after establishment".

Before the fix, on `public-vpn-118` — note the absence of any credential line:

```
09:25:24.685 GET_CONFIG
09:25:24.736 SENT CONTROL [opengw.net]: 'PUSH_REQUEST' (status=1)
09:25:29.157 SENT CONTROL [opengw.net]: 'PUSH_REQUEST' (status=1)
09:25:30.589 AUTH: Received control message: AUTH_FAILED
09:25:30.653 SIGTERM[soft,auth-failure] received, process exiting
```

Fix: `OpenVpnAuthDefaults.applyIfMissing(profile)` promotes a certificate-only
profile to `TYPE_USERPASS_CERTIFICATES` and fills in the hub account
`vpn` / `vpn`. It is deliberately narrow — it does nothing when a username is
already present, when the type is not `TYPE_CERTIFICATES`, or when the embedded
certificate/key did not survive parsing (promoting without them would emit no
client certificate at all). It is wired into **both** connect paths, the manual
one (`DetailActivity.loadVpnProfile`) and Auto Mode
(`AndroidConnectionAdapter.connectOpenVpn`), because both built the profile the
same way and both were affected.

The helper contains no `android.util.Log` so it stays a plain JVM unit under
`:app:testProDebugUnitTest`; the call sites log the result. Eight cases in
`OpenVpnAuthDefaultsTest` pin the promotion and the four non-promotion rules.

After the fix, same server, same failure point reached with credentials present:

```
09:38:29.207 MANAGEMENT: CMD 'username 'Auth' vpn'
09:38:29.208 MANAGEMENT: CMD 'password [...]'
09:38:31.036 WARNING: this configuration may cache passwords in memory
```

`auth_user_pass_file = 'stdin'` also appears in the option dump. Those lines are
emitted only when `auth-user-pass` is in the generated config, so they are a
deterministic before/after signal that does not depend on the network.

## 7.2 End-to-end device proof

`public-vpn-118` over **OpenVPN over TCP**, device 5585fecc, 2026-11-25:

| Time | Evidence |
|------|----------|
| `09:54:13` | connect issued |
| `09:54:18` | `tun0` up — `inet 10.238.55.1/30` (**5 s**) |
| `09:54:2x` | `sessionId=Japan[219.100.37.87][TCP:443] - 10.238.55.1/30`; `TransportInfo` UIDs contain **`10344-20036`**, i.e. the app's own UID is inside its own tunnel |
| `09:55:08` | `[ladder] order=TUNNEL,EMERGENCY` |
| `09:55:08` | `[bind] ok network=120` |
| `09:55:08` | `[fetch] path=tunnel source=mirror` / `[fetch] path=tunnel source=html` |
| `09:55:18` | `failed to connect to /194.156.89.134 (port 47774) from /10.238.55.1 (port 44354)` — the socket really is bound to the tunnel address |
| `09:55:26` | `HTML servers: 100`, `Fastest valid response from: http://150.40.105.6:11803/en/` |
| `09:55:35` | `HTML produced 100 records; CSV feed skipped` |
| `09:55:35` | `Dataset outcome MERGE_WITH_EXISTING: 100 incoming vs 598 stored` |
| `09:55:35` | `Server list updated atomically: 100 incoming, 670 stored (snapshot saved)` |
| `09:55` | UI: **Last updated 25 Nov 2026 - 09:55**, **670 servers** (was 09:07 / 598), row shows **Connected** |
| `09:59:29` | still up — **5 min 11 s** after `tun0` appeared, with **zero** `AUTH_FAILED` / `auth-failure` / `SIGTERM` lines in the buffer |

The last row is the one that closes the loop on D-3. Before the fix the same
protocol on the same kind of relay died 11–71 s after establishment; after the
fix the tunnel held for over five minutes, i.e. roughly 4× the worst previous
case, and was still carrying the refresh traffic at `09:55`.

The `from /10.238.55.1` in the socket-timeout line is the strongest single piece
of evidence: the collector's request was not merely *classified* as tunnel-bound,
it was physically issued from the tunnel's local address.

## 7.3 Notes and limits

- **The relays are the variable, not the app.** During this pass the Wi-Fi
  oscillated between 0 % and 50 % packet loss at ~310 ms RTT, and several
  attempts stalled at `TLS: Initial packet` (server never answered the
  ClientHello) or were cancelled mid-flight. `public-vpn-219` never completed a
  handshake in any attempt; `public-vpn-118` did, and carried the whole refresh.
  This is why the pass took several attempts and why a failure at `AUTH_FAILED`
  should not be read as an app defect without checking which server produced it.
- **`AUTH_FAILED` is still possible on an individual relay** even with correct
  credentials — VPN Gate relays apply per-IP session and abuse limits. What the
  fix guarantees is that the app now *offers* credentials instead of connecting
  anonymously; it cannot guarantee a given relay accepts them.
- **Leaving the detail screen does not tear an OpenVPN tunnel down.**
  `OpenVPNService` does not override `onUnbind`, and `endVpnService()` only runs
  on an explicit stop, so the Back press used in 7.2 to reach the list and press
  Refresh was safe. An earlier note in this document attributed an
  `agentDisconnect` teardown to the OpenVPN service; that line belongs to the
  SoftEther user-disconnect path and the attribution was wrong.
- **Test environment.** Gradle test workers fail with
  `Could not find or load main class Files\WorkBuddyAI\...` because the ambient
  `PATH` contains a malformed entry (`;"C;...\Program Files (x86)\PKWARE\pkzipc";`)
  that corrupts the `-Djava.library.path` Gradle derives from it. Running with a
  sanitised `PATH` and `--no-daemon` is required; the code under test is not
  involved.

Suite after D-3: **1738 tests, 0 failures, 1 skipped**
(app 1544 / SoftEther 191 / sstp 3), `:app:assembleProDebug` BUILD SUCCESSFUL.

# Part 8 — Per-server attempt budget (operator request, 2026-11-25)

Operator: *"monitor the app on an unstable server, it keeps connecting and
reconnecting; I want the number of attempts on one server to be configurable in
Settings, and after that number Auto Mode should move to the next server."*

## 8.1 What the monitoring showed

Auto Mode started at `10:23:07` on `public-vpn-102` over OpenVPN/TCP and
connected in 5.4 s. It died 12 s later:

```
10:23:12.676  Vpn: setting state=CONNECTED, reason=agentConnect
10:23:24.704  [AUTO] GlobalVpnTracker confirmed disconnected
10:23:24.714  [AUTO] Unexpected disconnect: public-vpn-102; reconnecting (attempt 1/5, delay 2204ms)
10:23:26.921  [AUTO] Started
10:23:26.924  [AUTO] Tunnel owned elsewhere; aborting Auto-Reconnect run
10:23:35.259  Vpn: setting state=DISCONNECTED, reason=agentDisconnect
```

Auto Mode then stopped for good — nothing further for 90 s. Two separate defects
are visible in those five lines.

### D-4: the reconnect aborted on our own dying tunnel

The OS took **10.5 s** to remove the VPN network after the tunnel died, so
`GlobalVpnTracker.isActiveOrBusy` still reported `true` when the reconnect run
started 2 s in. The guard at the top of `runAutoMode` used that broad predicate,
aborted, and published `Disconnected`.

`isConnectingToAnotherServer(dropped)` already existed for exactly this question
— it blocks only when a *different* server is being dialled, and the loss
handler above the guard already used it. The reconnect guard was simply never
moved over to it. Fixed by passing the seeded candidate:

```kotlin
val reconnectTarget = pendingReconnectServer
if (wasReconnect && isConnectingToAnotherServer(reconnectTarget)) { … abort … }
```

### D-5: the attempt budget was session-wide, and the rotation was a no-op

`RECONNECT_MAX_ATTEMPTS = 5` was a constant counted **across the whole
session**, and `rotateAwayFromDropped = consecutiveFailures > 1` meant the first
loss *always* re-dialled the same host, whatever the user wanted.

Worse, "put the dropped server last" could never work: `runAutoMode` re-sorts
the override list with `sortedForAutoConnect`, and an unstable server is usually
still one of the fastest, so it returned to position #1. Device log with the new
budget set to 1, before the ordering fix:

```
10:40:46  public-vpn-102 reached 1 attempt(s) on this server; moving to the next one
10:40:59  Trying #1 public-vpn-102        <-- rotation undone by the sort
```

## 8.2 The change

**Setting.** `SETTING_AUTO_MAX_ATTEMPTS_PER_SERVER`, default **2** (which
reproduces the old behaviour — re-dial once, then rotate), range **1..10**,
clamped in both the reader and the writer. `1` is meaningful and is not
normalised away: "never re-dial a server that just dropped". Settings →
Attempts per Server, directly under the connection timeout, because
`attempts × timeout` is the worst case one bad server can cost.

**Controller.** A per-server counter (`attemptsPerServer`, keyed by
`serverKey`) replaces the session-wide rule:

- incremented once per loss (guarded by a compare-and-set, `lossReported`, so
  the OS callback, the 1 s poll and the liveness watchdog cannot each spend an
  attempt for one drop);
- cleared for a server the moment it produces a verified tunnel, and wholesale
  on every user start/stop;
- once a server reaches the limit it is **deferred to the tail of the ranked
  list** — after the quality sort, so the sort cannot undo it. It is never
  removed: removing it would turn "move on" into `ERROR_NO_SERVER` when it is
  the only candidate.

## 8.3 Device verification (limit = 1)

Setting changed to **Up to 1** in Settings, then Auto Mode started:

```
10:45:30.895  Fast path: known-good public-vpn-102 via OPENVPN_UDP
10:45:35.976  CONNECTED outcome=TUNNEL_UP
10:45:55.019  GlobalVpnTracker confirmed disconnected          (19 s uptime)
10:45:55.024  Unexpected disconnect: public-vpn-102; reconnecting (attempt 1/5,
              delay 2397ms, server attempt 1/1)
10:45:55.024  public-vpn-102 reached 1 attempt(s) on this server; moving to the next one
10:45:57.425  Started                                          <-- no abort
10:45:57.487  Deferring 1 server(s) that used their 1 attempt(s): public-vpn-102
10:46:08.738  Trying #1 vpn102800483                            <-- different server
10:46:43.365  Trying #2 vpn559198341
10:47:07.460  Trying #3 vpn693017146
10:47:33.355  Trying #4 vpn720447102
10:47:45.425  Trying #5 vpn705816574
```

Every requirement is visible in that trace: the per-server count is reported,
the limit is honoured, the run no longer aborts, the dropped server is deferred
rather than re-dialled, and the walk continues to distinct servers. The UI
showed `Attempting server 5 of 343` throughout.

Suite after D-4/D-5: **1748 tests, 0 failures, 1 skipped**
(app 1554 / SoftEther 191 / sstp 3) — +10 cases
(`AutoModeReconnectTest` t14–t16, `DataUtilMaxAttemptsTest` ×7).

## 8.4 Not fixed, recorded only

While monitoring, a **zombie `OpenVPNService`** was found: the process still
held a `ServiceRecord` with a stale CONNECTED state while `tun0` was gone, and
that made the Auto Mode button refuse to start with
`start refused: a tunnel is already active` — while the UI showed
`Disconnected`. A force-stop cleared it. This is a separate defect from the
operator's report and was not changed in this pass; it is recorded here because
it can make Auto Mode look "stuck" and is worth its own investigation.

