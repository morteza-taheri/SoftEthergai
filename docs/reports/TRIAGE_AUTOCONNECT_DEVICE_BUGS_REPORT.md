# TRIAGE — AutoConnect Device Bugs (read-only discovery)

**Phase:** TRIAGE-AUTOCONNECT-DEVICE-BUGS
**Repository:** `SoftEthergai` · **Branch:** `main`
**Baseline:** HEAD == origin/main == `6229a769a8a98397b5acf2e55717b3a248a9a097` · spec v1.0.39
**Mode:** READ-ONLY. No source/spec/report/schema edit, no build, no test, no device command, no commit.
**Rule:** every claim is labelled `SOURCE CODE` / `TEST` / `PHYSICAL DEVICE` / `INFERENCE` / `UNKNOWN` / `PRIOR REPORT`.

---

## §1 Verdict

- **BUG-1 (button state does not follow tunnel state):** **needs a fix** — a structural UI-state
  aggregation gap. The Auto Mode hero button renders from `AutoModeState` **only**; that state is
  written **only** by `AutoModeController.setState`, i.e. only during an Auto Mode run. A tunnel
  established outside an Auto Mode run (manual connect, or a service-level reconnect) leaves
  `AutoModeState` at `Disconnected` while `GlobalVpnTracker` is `CONNECTED`, so the button shows
  "Disconnected / Tap to connect" and the D-4 guard then refuses the tap. **Evidence:** SOURCE CODE.
- **BUG-2 (MS_SSTP never attempted):** **most probably a data gap, not a filter bug** — the filter
  chain is correct; MS_SSTP is offered in Settings and `canAutoConnect` returns `true`. It is
  attempted only for a candidate with `sstpSupported == true && sstpConnectPort > 0`. The bundled
  seed has **no SSTP column**, the API source has **no SSTP column**, and only the HTML source
  supplies protocol columns. Whether the user's live dataset contains any SSTP-capable server is
  **UNKNOWN** (device data needed). **Evidence:** SOURCE CODE + INFERENCE + UNKNOWN.
- **BUG-3 (battery-optimization dialog repeats):** **needs a fix** — the dialog trigger reads the
  **OS state** (`isIgnoringBatteryOptimizations`) on every press and there is **no persisted
  "asked once" flag**, so pressing "Later" (or a manufacturer that does not persist the exemption)
  re-shows the dialog on the next press. **Evidence:** SOURCE CODE + PHYSICAL DEVICE (the reported
  repetition).

---

## §2 Pre-conditions

| Check | Value | Result |
|---|---|---|
| HEAD | `6229a769a8a98397b5acf2e55717b3a248a9a097` | — |
| origin/main | `6229a769a8a98397b5acf2e55717b3a248a9a097` | HEAD == origin/main ✔ |
| Branch | `main` | ✔ |
| Spec version | 1.0.39 | ✔ |
| Tracked modifications | none | ✔ |
| Tags | 5 | ✔ |

**Evidence:** SOURCE CODE (git).

---

## §3 BUG-1 — the button-state mechanism

### §3.1 The exact code path from tap to render

1. **Render.** `AutoModeScreen` reads `val state by viewModel.state.observeAsState(AutoModeState.Disconnected)`
   (`AutoModeScreen.kt:140`) and passes it to the hero button:
   `AutoConnectHeroButton(state = state, onClick = { startAutoModeWithAllChecks() })`
   (`AutoModeScreen.kt:360–363`).
2. **Label.** `when (state)` maps `AutoModeState.Disconnected` to
   `titleRes = auto_mode_state_disconnected` ("Disconnected") and
   `subtitleRes = auto_mode_tap_to_connect` ("Tap to connect") (`AutoModeScreen.kt:590–597`;
   strings `strings.xml:272,302`).
3. **Tap.** `startAutoModeWithAllChecks()` reads `val current = viewModel.state.value` and treats
   `Disconnected`/`Error` as "start", otherwise "toggle/disconnect" (`AutoModeScreen.kt:229–235`).
   With a stale `Disconnected` it proceeds to `proceedWithPermissionsAndConnect()` → `onButtonPressed()`.
4. **ViewModel.** `AutoModeViewModel.state` is a `MutableLiveData` fed **only** by
   `AutoModeEngine.state().collectLatest { state.value = it }` (`AutoModeViewModel.kt:23–28`).
5. **Engine.** `AutoModeEngine._state` is a `MutableStateFlow(Disconnected)` (`AutoModeEngine.kt:25`),
   initialised from the controller (`:143`) and updated **only** by `created.onStateChange = { state -> _state.value = state }`
   (`:149–150`).
6. **Controller.** `AutoModeController.setState()` is the sole writer of `_state`
   (`AutoModeController.kt:534–536`), and `AutoModeState.Connected` is published only at
   `AutoModeController.kt:2050` — inside the attempt loop of a run.

### §3.2 Which state source the button observes

`AutoModeState` **only**. `AutoModeScreen.kt` does not import or observe `GlobalVpnTracker` /
`GlobalVpnState` (the `ui/` grep finds `GlobalVpnTracker` only in `HomeScreen.kt` and
`StatusViewModel.kt`). **Evidence:** SOURCE CODE.

### §3.3 Which state source can be stale

`AutoModeState` can be stale whenever the tunnel is up but no Auto Mode run is driving it:

- a **manual connect** (server list / detail) — `DetailActivity` starts `SoftEtherVpnService`
  directly (`DetailActivity.kt:318`) and never touches `AutoModeController`;
- a **service-level reconnect** / restore of an existing tunnel;
- any tunnel established while the Auto Mode controller was not the initiator.

In all these cases `GlobalVpnTracker` is the true source (`vpnState`, `GlobalVpnTracker.kt:271`;
`isActiveOrBusy`, `:581`) and reports `CONNECTED`, while `AutoModeState` stays `Disconnected`.
**Evidence:** SOURCE CODE.

### §3.4 Root cause (file:line) or the exact device evidence

**Root cause (SOURCE CODE, definitive):** the Auto Mode hero button is bound exclusively to
`AutoModeState` (`AutoModeScreen.kt:140,360–363,590–597`), whose only writers are
`AutoModeController.setState` (`AutoModeController.kt:534–536`, `Connected` only at `:2050`) →
`AutoModeEngine._state` (`AutoModeEngine.kt:149–150`). A manual tunnel never invokes any of these,
so the button cannot reflect it. The D-4 guard, by contrast, reads the true state
(`isTunnelBusy = { GlobalVpnTracker.isActiveOrBusy }`, `AutoModeEngine.kt:137`), which is why the
tap is refused at `AutoModeController.kt:1059–1060`
(`"[AUTO] start refused: a connection is already in progress"`).

The intended reconciliation already exists elsewhere: `HomeScreen` observes **both** sources
(`HomeScreen.kt:125–128`) and `activeServerOf()` falls back to `GlobalVpnState.status`
(`CONNECTING`/`CONNECTED`) when `AutoModeState` is not Connecting/Connected
(`HomeScreen.kt:693–711`). The Auto Mode screen simply does not use that pattern.

**Device signal that would confirm it (if any doubt):** a logcat capture showing
`GlobalVpnTracker` = `CONNECTED` (or the status screen showing Connected) while the Auto Mode hero
button reads "Disconnected", immediately followed by
`[AUTO] start refused: a connection is already in progress` on tap. **Evidence:** SOURCE CODE
(definitive); the symptom itself is PHYSICAL DEVICE (user report).

### §3.5 Proposed fix shape (NOT implemented)

Derive the hero button's display state from **both** sources, the way `activeServerOf` already does:
if `AutoModeState` is `Connecting`/`Connected`/`Error`, use it; otherwise, if
`GlobalVpnTracker.vpnState.status` is `CONNECTING`/`CONNECTED`, render Connecting/Connected
(and treat the tap as "disconnect" rather than "start"). This is a UI-state aggregation change in
`AutoModeScreen`/`AutoModeViewModel`; it does not require touching the controller's state machine.
One paragraph only — **no code changed in this phase.**

---

## §4 BUG-2 — MS_SSTP never attempted

### §4.1 Is MS_SSTP in the priority list at runtime?

- The Settings editor offers **all six** protocols: `ordered = AutoModeProtocol.entries.toList()`
  (`SettingsScreen.kt:1173`), including `MS_SSTP` (`AutoModeProtocol.kt:16`).
- `persist()` writes `ordered.filter { it.id in enabledIds }` (`SettingsScreen.kt:1179–1183`), so a
  user who ticks SOFTETHER_UDP + MS_SSTP stores exactly those ids (in entries order:
  `SOFTETHER_UDP, MS_SSTP`).
- `DataUtil.getAutoModeProtocolPriority()` parses that stored string; an **unset** setting yields an
  empty list (`DataUtil.kt:365–371`). There is no hidden default here.
- `canAutoConnect(MS_SSTP)` returns **true** (`AndroidConnectionAdapter.kt:123–126`); `isAutomatable()`
  excludes only L2TP (`AutoModeController.kt:1211–1214`).

**Conclusion:** if the user enabled it, MS_SSTP **is** in the runtime priority list and is not
blocked by the adapter. **Evidence:** SOURCE CODE.

### §4.2 Does the current dataset contain any SSTP-capable server?

The gate is `MS_SSTP -> c.sstpSupported && c.sstpConnectPort > 0`
(`AutoModeController.kt:2236`), with:

- `sstpSupported = isSSTPSupport == 1` (`AutoModeEngine.kt:234`);
- `sstpConnectPort` = `0` if unsupported, else the published port if `> 0`, else `443` only when the
  port provenance is `DEFAULT`, else `0` (`VPNGateConnection.kt:92–106`).

**Bundled seed:** `app/src/main/assets/seed_vpn_servers.csv` uses the 15-column VPNGate **API**
format (`*vpn_servers`, header `#HostName,IP,Score,…,OpenVPN_ConfigData_Base64`) and has **no SSTP
column** (99 data rows). A seed-sourced dataset therefore has **zero** SSTP-capable servers.
**Evidence:** SOURCE CODE.

**Emergency CSV:** `emergency-sources/servers.csv` has 3 rows, all `SSTP_Supported=true`; the ports
are carried as `:port` suffixes in `SSTP_Hostname` (`…:995`, `…:1956`) or default to 443. This source
is used only when the user configures it. **Evidence:** SOURCE CODE.

**Live sources:** both the API (`VpnGateUrls.API_URL`) and the HTML (`VpnGateUrls.MAIN_URL`) are
fetched and merged (`VpnServerRepository.kt:230–242,287`), but
`providesProtocolColumns = htmlFetched != null` (`VpnServerRepository.kt:252–255`) — **only the HTML
source carries SSTP/L2TP columns.** The HTML parser detects an `SSTP`/`MS-SSTP` column
(`VpnGateHtmlParser.kt:33–43`) and parses `SSTP Hostname : host[:port]` (`:141–153,530–543`). The
merger does **not** drop SSTP; it strengthens it (`supported || hostname non-empty`, and `DEFAULT`
port when unknown) (`VpnServerMerger.kt:330–331,347–350`).

**Whether the user's stored dataset contains any SSTP-capable server is UNKNOWN** — it depends on
whether the HTML source was accepted in the run that produced the dataset and whether that page
listed SSTP for any host. **Evidence:** SOURCE CODE + UNKNOWN.

### §4.3 If yes, where is MS_SSTP dropped?

If at least one candidate has `sstpSupported && sstpConnectPort > 0`, source shows no drop:
`usableProtocols()` = `priorities.filter { supports && isAutomatable }`
(`AutoModeController.kt:1190–1197`), and MS_SSTP passes `supports` (`:2236`) and `isAutomatable`
(`:1211–1214`). So a capable candidate would be attempted on MS_SSTP. **Evidence:** SOURCE CODE.

### §4.4 If no, this is a data gap

Given the seed has no SSTP column, the API has no SSTP column, and only the HTML source supplies it,
the most probable mechanism is: **the dataset the user has contains no SSTP-capable server**, so
`supports(MS_SSTP)` is `false` for every candidate and the protocol is correctly filtered. In that
case the app is not "dropping" MS_SSTP — it is honestly finding nothing to dial. The UX gap is that
Settings lets the user enable MS_SSTP with no indication that zero servers currently support it.
**Evidence:** SOURCE CODE + INFERENCE.

### §4.5 Proposed fix shape or the exact device evidence needed

**Device evidence to resolve the UNKNOWN (needed first):**
1. the collector log line `Sources: html=<tag> api=<tag> mirrors=<n>` (`VpnServerRepository.kt:247–250`)
   — if `html=null`, no protocol columns exist and MS_SSTP cannot appear;
2. the per-server protocol dump (`CollectorDebugDump.kt:49`, `sstp=<…>`) for the stored dataset;
3. absence/presence of `protocol=MS_SSTP` in the `[AUTO]` attempt lines for a candidate that the
   dump shows as `sstp=true`.

**Proposed fix shape (NOT implemented):** if the dump shows `sstp=true` for some servers but they are
not attempted, investigate the merge/priority path; if it shows `sstp=false`/`html=null` for all,
treat it as a data gap — either enrich the seed with SSTP data or surface "no server supports
MS_SSTP" in the Protocol Priority editor so the user is not misled. One paragraph only.

---

## §5 BUG-3 — battery-optimization dialog repetition

### §5.1 The exact trigger condition

`proceedWithPermissionsAndConnect()` checks, on every press:

```kotlin
val isIgnoringBattery = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
if (!isIgnoringBattery) { showBatteryDialog = true; return }
```
(`AutoModeScreen.kt:201–209`). `showBatteryDialog` is a **transient** Compose flag:
`var showBatteryDialog by remember { mutableStateOf(false) }` (`AutoModeScreen.kt:142`).

### §5.2 What state it reads (OS vs preference)

It reads the **OS state** (`PowerManager.isIgnoringBatteryOptimizations`), **not** a persisted flag.
There is **no** "asked before" preference anywhere: a repo-wide grep for
`battery.*(asked|prompt|shown|once)|hasAsked|PREF.*BATTERY` finds nothing, and `DataUtil` has no
battery key (contrast `SETTING_LOCALE_ANSWERED`, `DataUtil.kt:534`, which is the existing
"asked once" precedent). **Evidence:** SOURCE CODE.

### §5.3 Why it repeats

Because the trigger is "the OS currently says not exempt", and `showBatteryDialog` is reset to
`false` on dismiss and is not persisted:

- pressing **"Later"** (`AutoModeScreen.kt:333–340`) only sets `showBatteryDialog = false`; the next
  press re-checks the OS, still finds "not exempt", and re-shows the dialog;
- pressing **"Configure"** (`:315–331`) launches `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, but if
  the user backs out, or the manufacturer does not persist the exemption (MIUI and similar are known
  to reset it), the OS still reports "not exempt" and the dialog repeats.

**Evidence:** SOURCE CODE + PHYSICAL DEVICE (the reported repetition).

### §5.4 Proposed fix shape (NOT implemented)

Add a persisted "asked once" flag (e.g. `SETTING_BATTERY_OPT_ASKED`, following the
`SETTING_LOCALE_ANSWERED` pattern) and show the dialog only when
`!isIgnoringBatteryOptimizations && !askedOnce`; set the flag when the dialog is shown (or when the
user picks "Later"). Optionally re-check the OS state and only re-prompt after an explicit user
action. One paragraph only — **no code changed in this phase.**

---

## §6 Cross-cutting observations

- **BUG-1** is the only one that is a true *state-source fragmentation* defect: the Auto Mode screen
  reads one of the app's two tunnel-state sources. `HomeScreen` already demonstrates the correct
  reconciliation (`HomeScreen.kt:125–128,693–711`); the fix is to reuse it.
- **BUG-2** and **BUG-3** are *trigger-condition* issues, not state-graph issues: BUG-2's filter is
  correct and the gap is data; BUG-3's trigger reads the OS on every press with no persisted memo.
- A common theme: the app has good single-source-of-truth primitives (`GlobalVpnTracker`,
  `DataUtil` settings) but individual screens sometimes bypass them (AutoMode screen ignores the
  tracker; battery prompt ignores a persisted flag). No shared root cause beyond that.

**Evidence:** SOURCE CODE.

---

## §7 Evidence labels per claim

| Claim | Label |
|---|---|
| Hero button renders from `AutoModeState` only | SOURCE CODE |
| `AutoModeState` written only by `AutoModeController.setState`; `Connected` only at `:2050` | SOURCE CODE |
| Manual connect bypasses `AutoModeController` (`DetailActivity.kt:318`) | SOURCE CODE |
| D-4 guard reads `GlobalVpnTracker.isActiveOrBusy` (`AutoModeEngine.kt:137`) | SOURCE CODE |
| `HomeScreen` reconciles both sources; AutoMode screen does not | SOURCE CODE |
| MS_SSTP offered in Settings; `canAutoConnect` true | SOURCE CODE |
| MS_SSTP gate = `sstpSupported && sstpConnectPort > 0` | SOURCE CODE |
| Seed has no SSTP column; API has no SSTP column; only HTML supplies it | SOURCE CODE |
| Emergency CSV has 3 SSTP-capable rows | SOURCE CODE |
| Merger strengthens, never drops, SSTP | SOURCE CODE |
| The user's live dataset has no SSTP-capable server | **UNKNOWN** (data-dependent) / INFERENCE |
| Battery dialog reads OS state on every press; no persisted flag | SOURCE CODE |
| Dialog repeats after "Later"/non-persisting OS | SOURCE CODE + PHYSICAL DEVICE |

---

## §8 Negative assurance

- **No source, test, spec, report, or schema file was edited.** The only file created is this report.
- **No build, test, `adb`/device command, commit, push, or tag** was run.
- **§22 was not touched** (no spec edit at all).
- **`D:\PMC\Documents\Android\SoftEthergai` was not touched.**
- The working tree's only new path is this untracked report; every pre-existing file is unchanged.

**Evidence:** SOURCE CODE (git status / command log).

---

## §9 Recommended fix order (proposed only, NOT begun)

1. **BUG-1** — highest user impact (wrong button state + confusing refusal) and a clear, contained
   UI-aggregation fix; it reuses the `activeServerOf` pattern.
2. **BUG-3** — trivial and low-risk: add one persisted flag; no state-machine change.
3. **BUG-2** — needs device data first (the `Sources:` line and the per-server `sstp=` dump); only
   then decide between a data enrichment and a Settings-UX note. Do **not** change the filter before
   the data question is answered.

**No fix was implemented in this phase.** **STOP.**
