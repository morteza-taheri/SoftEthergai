# BLOCK v1.0.75 — Physical-Device Test: Server-List Update INSIDE and OUTSIDE the Tunnel

Date: 2026-10-27 15:53–15:57. Device `5585fecc` (2201117TG, Android 13). App: versionCode 144 /
2.4.11-dev, built from `git archive d8f5fc8`, installed with `adb install -r` (no uninstall, no data
clear). All quotes below are verbatim `adb logcat` lines (tag/pid shown) or screenshots.

## 1. Context at test time

- Wi-Fi `PARS-M` (192.168.1.198/24), no VPN at start of the *inside* phase → then a live SoftEther
  tunnel: `tun0 10.211.1.28/16`, `VpnTransportInfo{type=1, sessionId=Japan[219.62.60.95][TCP:1835]}`.
- Auto Connect was actively running; the status screen showed **"Connected — Started by Auto
  Connect"** with the real server card (`vpn222647392`, 219.62.60.95, **SOFTETHER TCP**, 863 Mbps,
  3 ms). This is the first live evidence of the D-3 origin label on a real device.

## 2. INSIDE the tunnel — refresh via the Server List button

Action: `logcat -c` → tap the refresh control → wait 25 s.

```
D/VpnServerFetch(23921): [ladder] order=TUNNEL,EMERGENCY
D/VpnServerFetch(23921): [fetch] path=tunnel source=mirror
D/VpnServerFetch(23921): [fetch] path=tunnel source=api
D/VpnServerFetch(23921): [fetch] path=tunnel source=html
D/VpnGateCollector(23921): HTML servers: 98
D/VpnGateCollector(23921): API servers: 96
D/VpnGateCollector(23921): Collected 101 servers (capability=SourceCapability(providesProtocolColumns=true, providesOpenVpnProfiles=true))
D/VpnGateCollector(23921): Dataset rejected (SIZE_COLLAPSE): candidate 101 server(s) vs stored 400 (ratio floor 0.5); keeping 400 stored server(s)
I/VPNGateViewModel(23921): Update kept the current list (DEGRADED_DATASET); preserved 400 servers
```

Verdict **PASS**:
- The ladder is **TUNNEL → EMERGENCY** (no PHYSICAL) exactly as specified for "a usable, bound VPN
  exists"; all three sources were fetched **through the tunnel**.
- The **data-preservation guard** fired: a partial mirror response (101 < 50 % of 400) was rejected and
  the 400 stored servers were kept — the "a partial response never deletes retained servers" invariant,
  observed live.

## 3. OUTSIDE the tunnel — same button, tunnel disconnected first

Tunnel torn down (`tun0` absent, `dumpsys connectivity` reports 0 × VPN CONNECTED), then refresh.

```
D/VPNGateViewModel(23921): Start vpnItem from multi-source collector (userInitiated=true)
D/VpnServerFetch(23921): [ladder] order=PHYSICAL,EMERGENCY
D/VpnGateCollector(23921): [bind-physical] ok network=106
D/VpnServerFetch(23921): [fetch] path=physical source=mirror
D/VpnGateCollector(23921): Collected 99 servers (capability=SourceCapability(providesProtocolColumns=true, providesOpenVpnProfiles=false))
I/VPNGateViewModel(23921): Update kept the current list (DEGRADED_DATASET); preserved 400 servers
```

Verdict **PASS**:
- The ladder is **PHYSICAL → EMERGENCY** (no TUNNEL) and the request is bound to the physical network
  (`network=106`), exactly as specified.
- Same preservation guard: 99 collected → `DEGRADED_DATASET` → 400 preserved.
- Capability differs honestly: `providesOpenVpnProfiles=false` outside the tunnel vs `true` inside it —
  the OpenVPN profile fetch is tunnel-dependent, which is why the outside dataset is smaller.

## 4. Observations (not regressions from this block)

1. `CLEARTEXT communication to 150.40.105.x not permitted by network security policy` — the
   `mirror_unnumbered` HTTP mirrors are blocked by the app's cleartext policy; the HTTPS mirror and the
   API/HTML sources carry the update. Pre-existing behaviour, unchanged by this block.
2. `[TOFU] first-use stored key=118.156.88.5|…` and `Server random stored for authentication` — normal
   first-use trust-on-first-use for the new endpoint.
3. After the manual disconnect, a subsequent tap started a new Auto Connect run which reconnected
   (`Trying #1 vpn365529984` → `NATIVE_BUSY` → `Skipping retry on SOFTETHER_TCP` → `TUNNEL_UP` via
   `SOFTETHER_UDP`). The status again showed "Started by Auto Connect" — the origin label tracks the
   current run, not a stale one.

## 5. Live evidence for the block's own changes (same session)

```
D/AutoMode(23921): [AUTO] Connected server skipped by user; trying next (182.218.189.61|vpn516059434)
D/AutoMode(23921): [AUTO] Connection timeout = 12 seconds
D/AutoMode(23921): [AUTO] Protocols (priority) = SOFTETHER_TCP > SOFTETHER_UDP
D/AutoMode(23921): [AUTO] Pre-filtering reachable servers via probe (608 endpoint(s) across SOFTETHER_TCP > SOFTETHER_UDP)...
D/AutoMode(23921): [AUTO] Reachable servers selected: 372
```
- D-2 skip path executes with the new `ip|hostname` key in the log.
- REQ-TIMEOUT-001 (12 s) and REQ-PROTO-001 (TCP > UDP) confirmed live.
- Reachability probe + sorted walk behave as before (608 endpoints → 372 kept).

## 6. Screenshots (evidence files)

| File | Shows |
|---|---|
| `screen_now.png` | Connected, "Started by Auto Connect", vpn222647392 / SOFTETHER TCP / 863 Mbps / 3 ms |
| `screen_serverlist2.png` | Server List: 400 servers, "Last updated 27 Oct 2026 - 15:48" |
| `screen_after_update_inside.png` | List after the inside-tunnel refresh (400 preserved) |
| `screen_after_update_outside.png` | List after the outside refresh (400 preserved) |
| `screen_disconnected.png` | Reconnected run: vpn365529984 / SOFTETHER UDP / "Started by Auto Connect" |

All under `test-evidence/v1075_device_backup/`.

## 7. Remaining unverifiable on this device

- A refresh whose dataset PASSES the size floor (both mirrors returned ~100 of 400, so the accept path
  with `markSeen`/`incrementMissedRefresh` did not run in these two attempts; the guard path did).
- Fresh-install defaults and the first-run excluded-apps seed (need a data clear, prohibited).

## 8. Operator-reported bug and fix — the green button reconnected instead of disconnecting

**Report (operator, 2026-10-27):** with the tunnel up, pressing the green hero button did not
disconnect permanently — the tunnel came straight back up.

**Root cause (two layers, both confirmed on the device):**
1. `AutoModeScreen`'s hero always calls `startAutoModeWithAllChecks()`; in the Connected state that
   reached the controller's Connected branch, which — after BLOCK v1.0.75 PART B — delegated to
   `skipToNextServer()` ("move on" = start the next candidate). So the tunnel dropped and a new run
   reconnected it. Fix: the Connected branch now means **disconnect only** (`ad08107`, `6e2a93a`);
   "move on" stays exclusively on the dedicated Try Next button.
2. Even with that fixed, `stop()` was the wrong primitive: a successful run has already finished, so
   `isRunning == false` and `stop()` publishes Disconnected **without tearing the tunnel down** — device
   evidence at 16:14: the log said `Disconnect requested from the connected state` while `tun0` was
   still up 12 s later. Fix: call `disconnectNow()` (always issues the teardown, ends ownership, clears
   the session tried-set).

**Physical verification (fixed build, `adb install -r`, 16:22–16:23):**
```
[AUTO] Connected successfully            (16:22:52, SOFTETHER_TCP)
[AUTO] Disconnect requested from the connected state   (16:23:02)
[AUTO] Re-entrant teardown completion observed ... NOOP_REENTRANT   (16:23:03)
```
- `tun0` and `VPN CONNECTED`: **0 at t = 3 s, 6 s, 10 s, 20 s and 30 s** after the press — the tunnel
  stays down.
- **No `[AUTO] Started` / `Trying #` after the press** (grep of all AutoMode lines ≥ 16:23:02: 0 hits) —
  no new run, no Auto-Reconnect.
- The new unit test asserts the adapter actually receives a `disconnect` call, so this class of bug
  (state says Disconnected while the tunnel lives) cannot regress silently.

**Test-suite note:** two timing-sensitive tests (`buttonPressDuringAnInFlightSkipStopsInsteadOfStartingASecondRun`,
`VpnServerRepositoryVpnFirstGuardsTest > D …`) failed once in a full-suite run and pass in isolation and
in two consecutive full runs afterwards — pre-existing flakiness of the same family documented in those
files, not caused by this fix.

## 8b. Follow-up (operator report): the list was NEVER actually updating

The two "preserved" outcomes in §2/§3 hid a deeper bug: **every** refresh was rejected, so the list
had frozen at 400 rows. Three stacked causes, all fixed on the device:

| # | Cause | Fix |
|---|---|---|
| 1 | `SIZE_COLLAPSE` compared the live feed (~98) against the **retention union** (400 rows = 2 weeks of kept servers): `98 < 400 × 50 %` → rejected forever | Baseline = the last ACCEPTED fresh dataset, persisted in DataUtil (`SETTING_LAST_ACCEPTED_DATASET_SIZE`); when unknown, the first response is accepted and recorded (absolute floor still applies) |
| 2 | Even with a baseline, protocol/profile coverage compared **absolute counts** against the inflated union | Coverage compared as a **share** of each dataset (a transport vanishing still trips it; scale differences do not) |
| 3 | A feed fetched without a tunnel carries no OpenVPN profiles → the whole update was dropped by the profile rule, so updates NEVER applied outside a tunnel | `mergeAll` now **preserves the stored profile bytes** when the feed has none (and retains unlisted servers), so accepting cannot lose a profile; the rule stays as a log line |

**Device verification (final build, `git archive 6c854ff+` → APK → `install -r`):**
```
D/VpnGateCollector: Collected 98 servers (capability=... providesOpenVpnProfiles=false)
D/VpnGateCollector: Feed carries no OpenVPN profile; accepted anyway — mergeAll preserves
                    stored profile bytes and retains unlisted servers
I/VPNGateViewModel: Server list updated atomically: 98 incoming, 485 stored (snapshot saved)
```
- `VPNGateItem`: 400 → **485 rows** (new servers added, retention union capped at 500)
- `MAX(lastSeenAt)` within one minute of "now" — rows freshly stamped
- History: all 20 tracked hosts were listed by the feed → `markSeen` reset their
  `missedRefreshCount` (0 aged rows) — the D-8 path exercised live
- `PRAGMA integrity_check: ok`; screenshot `screen_list_updated.png`
