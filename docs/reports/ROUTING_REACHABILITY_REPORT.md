# ROUTING-REACHABILITY-AND-EPERM-DIAGNOSTICS — Report (v1.0.43)

**Phase:** ROUTING-REACHABILITY-AND-EPERM-DIAGNOSTICS
**Spec version after this phase:** 1.0.43
**Date:** 2026-10-20
**Mode:** device-free (no hardware run in this phase)
**Author:** operator-authorised revision of the v1.0.43 device findings
**Baseline HEAD:** `bc5ff0d9d507cf18424dc507cbd1f76274e5c2e9` (== origin/main before this phase)

---

## 0. Claim labelling convention

Every claim below is tagged: **SOURCE CODE** (read from the repository),
**TEST** (asserted by the JVM suite), **PHYSICAL DEVICE** (observed on hardware —
none in this phase), **PRIOR REPORT** (carried from
`DEVICE_VERIFY_CLUSTER_v1.0.43_REPORT.md`), **INFERENCE** (reasoned, not directly
observed), **UNKNOWN** (not settled this phase).

---

## 1. Summary

This phase closes two findings from the v1.0.43 device verification:

- **M1 — the `vpn_always_prefer_vpn` opt-in had no writer.** `GlobalVpnTracker.prefersVpnForCollector()`
  *reads* `PREF_ALWAYS_PREFER_VPN = "vpn_always_prefer_vpn"` (`GlobalVpnTracker.kt:527`/`:780`), but a
  repository-wide read showed **nothing ever wrote it** (SOURCE CODE + PRIOR REPORT). The v1.0.41 VPN routing
  rung — selected only when `preferVpn && tunnelUsable && hasBoundNetwork` — was therefore **unreachable for
  every user**. FIX-1 gives the key a writer (`DataUtil`) and a Settings toggle.
- **M2 — a VPN-rung fetch failed with `EPERM` on the device** with no attribution (PRIOR REPORT, §8). The
  device log line was `Request failed: Binding socket to network 115 failed: EPERM (Operation not permitted)`.
  FIX-2 adds diagnostics-only logging around the binding construction, control flow unchanged.

Both fixes are additive; the frozen routing files (`GlobalVpnTracker.kt`, `VpnServerRepository.kt`,
`AutoModeController.kt`, `AutoModeEngine.kt`, `AutoModeScreen.kt`, `AutoModeViewModel.kt`) are untouched.
This phase ran **no device**, so all behavioural claims are SOURCE CODE + TEST only.

---

## 2. Finding M1 — the opt-in is inert (SOURCE CODE + PRIOR REPORT)

Read-only discovery confirmed:

- `PREF_ALWAYS_PREFER_VPN = "vpn_always_prefer_vpn"` is declared `internal const` at
  `GlobalVpnTracker.kt:780` and read at `:527` inside `prefersVpnForCollector()`. (SOURCE CODE)
- A repository-wide grep for `PREF_ALWAYS_PREFER_VPN | vpn_always_prefer_vpn | prefersVpnForCollector`
  found **three reads** (`VpnServerRepository.kt:96,128` referencing it via `prefersVpnForCollector()`,
  and the reader itself) and **zero writers**. (SOURCE CODE)
- The v1.0.43 device report §8 records T6 as UNTESTABLE precisely because no UI ever wrote the key.
  (PRIOR REPORT)

**Conclusion (INFERENCE):** the v1.0.41 change ("always prefer VPN") shipped a gate with no switch. The
VPN rung could never be taken by a user. This is a product gap, not a crash.

---

## 3. FIX-1 — writer + toggle for the opt-in (SOURCE CODE + TEST)

### 3.1 `DataUtil` getter/setter
`app/src/main/java/vn/unlimit/vpngate/utils/DataUtil.kt` gains:

```kotlin
fun getPrefersVpnForCollector(): Boolean =
    getBooleanSetting("vpn_always_prefer_vpn", false)

fun setPrefersVpnForCollector(enabled: Boolean) {
    setBooleanSetting("vpn_always_prefer_vpn", enabled)
}
```

The key literal `"vpn_always_prefer_vpn"` is **duplicated on purpose** rather than referenced from
`GlobalVpnTracker.PREF_ALWAYS_PREFER_VPN`. Two reasons, both deliberate: (a) it keeps this accessor
independent of a frozen file; (b) it lets a source-shape unit test assert the exact byte string.
`DataUtil`'s existing test seam — the `internal constructor(SharedPreferences)` — is reused, so the JVM
tests inject an in-memory store with no Android runtime. (SOURCE CODE)

### 3.2 Settings toggle
`app/src/main/java/vn/unlimit/vpngate/ui/screens/settings/SettingsScreen.kt` gains a state field
`prefersVpn` (default `dataUtil.getPrefersVpnForCollector()`, i.e. off) and a new `SettingSwitchRow`
inside the existing `auto_mode` `SettingsSection`, mirroring the `autoReconnect` row:

```kotlin
SettingSwitchRow(
    title = stringResource(R.string.setting_prefer_vpn_collector_label),
    subtitle = stringResource(R.string.setting_prefer_vpn_collector_summary),
    icon = Icons.Outlined.VpnLock,
    checked = prefersVpn,
    onChecked = {
        prefersVpn = it
        dataUtil.setPrefersVpnForCollector(it)
    },
)
```

Two strings are added to `app/src/main/res/values/strings.xml`:
`setting_prefer_vpn_collector_label` = "Route collector traffic via VPN" and
`setting_prefer_vpn_collector_summary` = "When a VPN tunnel is active, send server-list and status
requests through it instead of the open network." (SOURCE CODE)

### 3.3 Behavioural note (INFERENCE)
`CollectorRouting.choose()` and `productionRouting()` are **unchanged**, so the VPN rung still requires
`preferVpn && tunnelUsable && hasBoundNetwork`. The only new behaviour is that a user can now set
`preferVpn = true`. Until this phase that branch was dead because nothing wrote the key; now it is a
genuine user choice. Default off preserves the pre-v1.0.43 fail-safe (OPEN) for existing installs.

---

## 4. Finding M2 — EPERM on the VPN binding (PRIOR REPORT + INFERENCE)

The v1.0.43 device run produced, on the VPN rung:

```
[collector] path=fallback-to-vpn source=api
Request failed: Binding socket to network 115 failed: EPERM (Operation not permitted)
```

(quoted from `DEVICE_VERIFY_CLUSTER_v1.0.43_REPORT.md §8`). The OPEN rung worked; the VPN rung failed with
`EPERM` while no tunnel existed. (PRIOR REPORT)

**Important precision (INFERENCE + SOURCE CODE):** the binding in this codebase is narrow and explicit —
`Network.getSocketFactory()`, never `bindProcessToNetwork()` (`SERVER_SOURCE_ARCHITECTURE.md §7 Q2`,
`HttpFetcher.kt:124-127, 282-287`). The `EPERM` string above is produced at **socket-open time**, inside
`OkHttpFetcher.execute()` (`HttpFetcher.kt:261-264`, the existing `Request failed: ${e.message}` log), when
the bound socket factory actually opens a socket onto the VPN `Network`. The construction site
(`boundToNetwork` → `boundToSocketFactory(network.socketFactory)`) does not perform I/O and cannot itself
throw that `EPERM`.

---

## 5. FIX-2 — bind diagnostics, control flow unchanged (SOURCE CODE + TEST)

`app/src/main/java/vn/unlimit/vpngate/data/remote/HttpFetcher.kt`'s `boundToNetwork` is wrapped:

```kotlin
fun boundToNetwork(network: Network): OkHttpClient =
    try {
        val client = boundToSocketFactory(network.socketFactory)
        CollectorLog.d("[bind] ok network=$network")
        client
    } catch (e: Throwable) {
        CollectorLog.d("[bind] FAILED network=$network errno=${e.message}")
        throw e
    }
```

The two log strings `[bind] ok network=` and `[bind] FAILED network=… errno=…` make a bind attempt and its
failure attribute to a network. `throw e` is preserved, so the open → VPN-fallback ordering is untouched.
(SOURCE CODE)

**Scope limit (INFERENCE + UNKNOWN):** this is construction-time visibility only. A stale/revoked `Network`
(whose `socketFactory` getter throws) would now be caught *here*. The genuine kernel `EPERM` on a live
tunnel's network is still surfaced later, by the pre-existing `Request failed` log in
`OkHttpFetcher.execute()`. The root cause of that `EPERM` is **explicitly deferred** — FIX-2 is diagnostics,
not a fix. The next device run is expected to disambiguate the two. (UNKNOWN)

---

## 6. Tests — T-NEW-46..52 (TEST)

Seven new JVM tests in two new files; no existing test deleted or edited.

`app/src/test/java/vn/unlimit/vpngate/utils/DataUtilPrefersVpnTest.kt` (4):
- **T-NEW-46** — a fresh install does not prefer VPN (default off).
- **T-NEW-47** — setting the opt-in makes it read back as preferred (round-trip).
- **T-NEW-48** — the opt-in survives a new `DataUtil` over the same store (restart persistence).
- **T-NEW-49** — the opt-in is stored under the exact key literal `"vpn_always_prefer_vpn"` (the in-memory
  `SharedPreferences` contains that key after a set). This is the source-shape guarantee that the writer and
  the frozen reader share the same bytes.

`app/src/test/java/vn/unlimit/vpngate/data/remote/HttpFetcherBindDiagnosticTest.kt` (3):
- **T-NEW-50** — `[bind] ok network=` is present in the `HttpFetcher.kt` source.
- **T-NEW-51** — `[bind] FAILED network=` is present in the source.
- **T-NEW-52** — `boundToNetwork` is still defined and still calls `boundToSocketFactory`.

The in-memory `SharedPreferences` fake is renamed (`PrefersVpnFake*`) to avoid a top-level private-class
collision with the same-named fake in `DataUtilBatteryFlagTest.kt` (same package). (TEST + SOURCE CODE)

---

## 7. JVM and build gate (TEST)

Run under `CI=true`:

- `:app:testProDebugUnitTest` → `:app` **865 / 0 / 0 / 1** (baseline 858 / 0 / 0 / 1 — the delta is exactly
  the 7 new tests; the one skip, `HtmlParserOracleTest`, is unchanged).
- `:SoftEtherClient:test` → **173 / 0 / 0 / 0** (unchanged).
- `:app:assembleProDebug --no-daemon` under `CI=true` → BUILD SUCCESSFUL.
- APK SHA-256: `ed82fc058796a45698cd41a90d4dcb1fb20ecbc05198b30d57eaecf2117bce19`.
- `versionCode 144` / `versionName 2.4.11` unchanged — no build or manifest file was touched.

The Gradle daemon held a port lock on the first assemble attempt (`java.net.BindException: Address already
in use`); stopping the daemons and re-running with `--no-daemon` produced the build above. (TEST)

---

## 8. Open items / UNKNOWN (UNKNOWN)

1. **M2 root cause.** The genuine `EPERM` on binding to the VPN network is not explained or fixed by this
   phase. Expected next step: reproduce on a device with a usable tunnel and read the new `[bind] FAILED`
   line to attribute it.
2. **M1 end-to-end reachability.** No device run confirmed a collector fetch actually takes the VPN rung
   when the toggle is on and a tunnel is usable. The writer + toggle are verified at SOURCE CODE + TEST only.
3. **`boundToNetwork` is currently unused in production.** `VpnServerRepository.kt:163` calls
   `boundToSocketFactory` directly, not `boundToNetwork`; `GlobalVpnTracker.kt:515` `currentVpnNetwork()` is
   the live `Network`. The new `[bind]` diagnostics fire only if `boundToNetwork` is later wired in (or
   `network.socketFactory` throws). This is recorded so a future routing change does not assume the log is
   live today.

---

## 9. Status

- Pre-conditions (STEP 1): HEAD == origin/main == `bc5ff0d…`; spec 1.0.42; 5 tags; no tracked modification.
  PASS.
- Read-only discovery (STEP 2): the two findings located and pinned. PASS.
- FIX-1 + FIX-2: applied to the four authorized source files only. PASS (diff scope confirmed: DataUtil.kt,
  SettingsScreen.kt, HttpFetcher.kt, strings.xml).
- Tests: 7 new, all green. PASS.
- Build gate: in-tree assemble SUCCESSFUL, APK hashed. PASS.
- Spec: bumped to 1.0.43; §1.6.36 added; §11.8.2 and §12.5.2 item A annotated; changelog + verification
  rows added; §22 untouched (byte-identical). PASS.

**NOT device-verified. No tag created. No force. No `git add -A`.**

> **STOP.** Do not propose or start the next phase (device re-verification of M1/M2, or the M2 root-cause
> fix) autonomously. Re-verification is a separate, device-bearing phase and must be requested explicitly.
