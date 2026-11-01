# AUTO-ROUTE-AND-AUTOCONNECT-UX — Phase Report (v1.0.52)

**Block:** `BLOCK v1.0.52 — AUTO-ROUTE-AND-AUTOCONNECT-UX`
**Baseline HEAD:** `0f9c0a66bd9458e7a7546842d59e3419f47ae140`
**Spec section:** §1.6.44 (new), document version 1.0.52
**Mode:** device-free / DETACHED. No device command was run. No tag was created. **Not pushed.**

---

## 1. Why this revision exists

Three operator-requested corrections, in the operator's own framing:

1. **The user should never have to tell the collector how to reach the internet.** The Settings switch "prefer the VPN for the collector" asked the user to diagnose their own ISP. It is removed, and the collector now decides from measurements.
2. **The Auto Connect screen was missing a switch that belongs to it, and its default timeout was too generous.** "Automatic Reconnection" lived in Settings while describing Auto Connect behaviour; the 12 s default made a dead server cost 12 s before the run moved on.
3. **Auto Connect could run two loops at once, and could re-dial the server the user had just skipped.**

---

## 2. TASK A — the manual collector-routing switch is removed

### 2.1 What was deleted, and where

| Symbol | File | Disposition |
|---|---|---|
| `SettingSwitchRow` for the collector switch | `ui/screens/settings/SettingsScreen.kt` | deleted |
| `prefersVpn` / `autoReconnect` state declarations | `SettingsScreen.kt` | deleted (the second moved — see §3) |
| `getPrefersVpnForCollector()` / `setPrefersVpnForCollector()` | `utils/DataUtil.kt` | deleted |
| `defaultPreferences` field + two-argument `internal constructor` | `DataUtil.kt` | deleted — had become write-only |
| `prefersVpnForCollector()` | `state/GlobalVpnTracker.kt` | deleted |
| `PREF_ALWAYS_PREFER_VPN` | `GlobalVpnTracker.kt` | deleted |
| `CollectorRoutingInput.preferVpn` | `data/remote/HttpFetcher.kt` | field deleted (input is now two fields) |
| `object CollectorRouting { fun choose(...) }` | `HttpFetcher.kt` | deleted |
| `setting_prefer_vpn_collector_label` / `_summary` | `res/values/strings.xml` | deleted (no other locale carried them) |

`CollectorRoutingInput` is now exactly:

```kotlin
data class CollectorRoutingInput(
    val tunnelUsable: Boolean,
    val hasBoundNetwork: Boolean,
)
```

Both fields are **measurements**, not preferences. Evidence: SOURCE CODE.

### 2.2 The stale stored value is inert — and that is the whole migration

Installs in the field carry `"vpn_always_prefer_vpn"` in SharedPreferences. Nothing reads it any more, so it is ignored. There is no migration routine, no preference rewrite and no DB touch. `T-REMOVED-05` proves it behaviourally: a store containing the old key produces identical readings from every surviving `DataUtil` accessor. Evidence: TEST.

### 2.3 The replacement: `PathMemory`, bounded by construction

`VpnServerRepository.PathMemory` records which transport last delivered a dataset and which one just failed.

| Property | Value | Why |
|---|---|---|
| Success TTL | `PATH_MEMORY_SUCCESS_TTL_MS = 30 min` | Long enough to keep a blocked-physical session on the tunnel across many refreshes; short enough that a stale success cannot outlive the network it described. |
| Failure cooldown | `PATH_MEMORY_FAILURE_COOLDOWN_MS = 2 min` | Covers a refresh burst (pull-to-refresh, retry, auto-mode connect) without pinning the ladder for the session. |
| Key space | the three-value `CollectorPath` enum | **Bounded by construction**: at most 3 success + 3 failure timestamps exist regardless of uptime. No eviction policy is needed because no unbounded key can be created. |
| Contents | `(enum, epochMillis)` pairs | No credentials, hostnames, IPs, SSIDs or user ids. Nothing is persisted; a process restart clears everything. |
| Clock | injectable `() -> Long` | TTL and cooldown are tested without `Thread.sleep`. |

`routeLadder(input, memory)` keeps the v1.0.45 order — `PHYSICAL → TUNNEL (only when `tunnelUsable && hasBoundNetwork`) → EMERGENCY` — and promotes `TUNNEL` to the front **only** on evidence: `memory.isCoolingDown(PHYSICAL)` or `memory.preferred() == TUNNEL`. No input value can promote it. Evidence: SOURCE CODE + TEST (`T-NEW-56a`).

### 2.4 Exactly one bounded TUNNEL retry

The one case the ladder cannot cover by itself: the run had **no usable tunnel at all** (so `TUNNEL` was never a rung), failed, and by now a tunnel has become usable.

| Guard | Mechanism | What it prevents |
|---|---|---|
| Spend once | `tunnelRetryAvailable.compareAndSet(true, false)` | Two concurrent callers both retrying |
| Single-flight | inside the existing `refreshMutex` | A retry racing a normal refresh |
| Bounded | `withTimeoutOrNull(TUNNEL_RETRY_TIMEOUT_MS)` = 20 s (< `MAIN_TIMEOUT_MS` = 45 s) | A recovery attempt becoming a second full refresh |
| Cannot loop | re-armed **only** by a run that *starts* with a usable tunnel, never by a failure | Infinite retry |
| Triggered only when useful | `result == null && lastRunFailedWithoutTunnel` | A retry after a run that already had the tunnel |

A retry that produces nothing logs `[ladder] one-shot retry produced nothing (20000ms); preserving DB`. Evidence: SOURCE CODE + TEST (`T-LADDER-20/21/22`).

### 2.5 One new production seam

`pathMemory` is now a constructor parameter of `VpnServerRepository` (default `PathMemory()`). This is required, not cosmetic: the R4b tests drive runs that **start** on the tunnel rung, which they previously did by setting the removed `preferVpn` input. They now seed the evidence the real ladder acts on (a recorded `PHYSICAL` failure), which is the production mechanism rather than a bypass of it. `PathMemory` is public because a public constructor cannot expose an `internal` parameter type. Production still gets a fresh instance per repository. Evidence: SOURCE CODE.

---

## 3. TASK B — Auto Connect UX and ownership

### 3.1 B.1 — one switch, one place

The "Automatic Reconnection" `SettingSwitchRow` moved from `SettingsScreen` to `AutoModeScreen` (immediately below the server-info block). Same preference (`SETTING_AUTO_RECONNECT_IN_AUTO_MODE`), same accessors, default still `true` per §11.13.2. Asserted from **both** sides so the row cannot end up in two screens (two switches, one value) or in neither (unreachable setting). Evidence: TEST (`T-REMOVED-04`).

### 3.2 B.2 — the default per-attempt timeout is 8 s

| Site | Before | After |
|---|---|---|
| `DataUtil.getAutoModeTimeoutSeconds()` | `getIntSetting(SETTING_AUTO_MODE_TIMEOUT_SECONDS, 12)` | `…, 8)` — clamp unchanged at `coerceIn(8, 60)` |
| `AutoModeController.DEFAULT_ATTEMPT_TIMEOUT_MS` | `12_000L` | `8_000L` |
| Settings picker fallback | `values.indexOf(12)` | `values.indexOf(8)` |

The default is now **equal to the floor**, so a fresh install and a legacy install holding 5–7 converge on the same figure with no migration. This supersedes the 12 s default established in v1.0.31 and left alone by v1.0.46; the 12 s figures in §1.6.23 / §1.6.31 / §1.6.39 are **historical records and were left as written**, while the normative sites (§6.7 R5, §6.8.1, §6.8.7, §10.1, §17) were updated. Evidence: SOURCE CODE + TEST.

### 3.3 B.3(a) — Auto Connect has a single owner

`AutoModeController` gained `private val transitionLock = Any()` plus a `startLocked()` seam.

**Why a monitor and not a `Mutex`:** `start()` and `stop()` are non-suspending and must stay so for their callers. A `Mutex` would force `suspend` onto both signatures and ripple through every call site.

Every `job` mutation is now inside `synchronized(transitionLock)`:

- `start()` → `synchronized(transitionLock) { startLocked() }`
- the whole body of `stop()`
- the `Error` branch of `skipToNextServer()`: `overrideServers = …; startLocked()` as one transition
- the `Connected` branch of `skipToNextServer()`: the continuation coroutine is published **as the current job**, then swapped for `scope.launch { runAutoMode() }` while still holding the lock

The `Connected` case is the one that mattered: `job` goes from one live job straight to another and **never passes through null**. Before this revision the slot sat empty until the continuation called `start()`, leaving a window in which a second press found no owner, launched its own run, and two loops walked the same list. Evidence: SOURCE CODE + TEST (`twoImmediateSkipsNeverDialTheSameServerTwice`).

### 3.4 B.3(b) — cancellation is cancellation

| Defect | Fix |
|---|---|
| `CancellationException` fell into `catch (e: Exception)`, turning a user skip or a `stop()` into a "failed attempt" that advanced the cursor | `catch (e: CancellationException) { throw e }` on the reachability probe and on both connect paths |
| Teardown could be abandoned mid-way when the enclosing scope was already cancelled | `withContext(NonCancellable) { adapter.disconnect() }` on both connect paths |

Evidence: SOURCE CODE + TEST (`nativeBusyCancellationDuringRetryIsClean` and the existing stop-path tests).

### 3.5 B.3(c) — the Next-Server cursor uses one identity

`rememberRemaining()` compared `it.ip == current.ip && it.hostname == current.hostname`, while the continuation and the wedge ledger both use `serverKey` = `"$ip|${hostname ?: ""}"`.

Those disagree whenever a hostname is `null` on one side and `""` on the other (`null != ""`). The consequence was concrete: the connected server could not be found in its own list, `indexOfFirst` returned `-1`, the "remaining" tail fell back to the **whole** list, and Next Server re-dialled the server the user had just left.

The comparison is now `serverKey(it) == serverKey(current)`. Evidence: SOURCE CODE + TEST (`nextServerCursorMatchesByIdentityWhenHostnameIsNullVersusEmpty`).

---

## 4. Tests — replaced, never silently deleted

The brief's rule is explicit: tests asserting behaviour removed **on purpose** must be replaced by tests of the new behaviour, each listed, never silently deleted.

| File | Disposition | Detail |
|---|---|---|
| `DataUtilPrefersVpnTest.kt` | **replaced** by `DataUtilRoutingPreferenceRemovedTest.kt` | pinned T-NEW-46..49 and T-NEW-54a..d (the deleted accessors). Replacement: `T-REMOVED-01..05` — removal of the symbols, absence from `GlobalVpnTracker`, absence of the Settings row and the two strings, the moved Auto-Reconnect switch, and the behavioural "a stale stored key changes no reading". |
| `HttpFetcherR4bTest.kt` | routing half **replaced**; bound-client half kept | the five `CollectorRouting.choose` permutations became: the input carries only two measurements; `declaredFields` is exactly `{tunnelUsable, hasBoundNetwork}`; `CollectorRouting` no longer resolves via `Class.forName`; the fetcher layer mentions no preference. |
| `VpnServerRepositoryLadderTest.kt` | **replaced** | was the opt-in-reorders ladder; now the automatic ladder (T-NEW-55a..d, 56a..f, 57a..c) plus 8 `PathMemory` cases (T-LADDER-01..08) with a fake clock. |
| `DataUtilTimeoutRangeTest.kt` | **updated** | T-NEW-70 12 → 8; new T-NEW-70b pins the Settings default against `DEFAULT_ATTEMPT_TIMEOUT_MS`; T-NEW-72's message no longer implies a 12 s nudge. |
| `AutoModeControllerTest.kt` | **updated + extended** | `defaultAttemptTimeoutMatchesTheSettingsDefault` 12 → 8 with an added "no stale 12 s default" guard; two new cases (§3.3, §3.5). |
| `AutoModeNativeBusyTest.kt` | **updated** | `assertEquals(8_000L, …)` with the rationale recorded in place. |
| `VpnServerRepositoryRoutingDynamicTest.kt` | **updated + extended** | T-NEW-36/37/38 rewritten for the two-field input; T-NEW-37 now pins that **no input** promotes TUNNEL; new T-LADDER-20..24. |
| `VpnServerRepositoryRoutingTest.kt` | **updated** | KDoc and the integration case moved to the two-field input; two new cases: empty memory ⇒ PHYSICAL first, and a failed run teaches the next run to lead with TUNNEL. |
| `VpnServerRepositoryR4bTest.kt` | **updated** | two-field inputs; a `tunnelRepository()` helper that seeds the evidence, so the four tunnel-rung cases keep their exact original intent. |

**No test was deleted without a replacement.** `git diff --stat` shows `DataUtilPrefersVpnTest.kt` as a pure deletion of 249 lines; its replacements live in the new file listed above.

### 4.1 New cases, by acceptance criterion

| Brief requirement | Pinned by |
|---|---|
| ladder order | T-NEW-55a/b, T-NEW-57a |
| TUNNEL skipped when unusable | T-NEW-55c/d, T-NEW-57a |
| PHYSICAL fail → TUNNEL success | `VpnServerRepositoryRoutingTest` permutation 4 (existing) |
| path memory + cooldown with a fake clock | T-LADDER-01..08 |
| one-shot retry | T-LADDER-20/21/22 |
| single-flight | T-NEW-63, T-LADDER-21, `doublePressSingleJob` (existing) |
| DB preserved | T-NEW-63 (`[ladder] all rungs failed; preserving DB`) |
| protocol timeout → next protocol | `timedOutRotatesCursorWithoutSameServerRetry` (existing) |
| all protocols fail → next server | `aSlotThatNeverReleasesMovesOnToTheNextServer` (existing) |
| list exhausted | `pressingWhileConnectedToTheLastServerEndsWithoutRestarting`, `aFreshPressAfterExhaustionStartsFromTheTopAgain` (existing) |
| user cancel | `stopDuringConnectingStopsEverything` (existing) |
| manual selection | `a5_manualPrecedencePublishesDisconnectedAfterTheDecision` (existing) |
| both networks off | `ERROR_NO_NETWORK` guards (existing, v1.0.42) |
| success before timeout | `firstServerSuccess` (existing) |
| repeated presses | `doublePressSingleJob` (existing) + `twoImmediateSkipsNeverDialTheSameServerTwice` (new) |
| Next Server 5→6→7 | `skipWhileConnectedDialsTheNextServerAndNeverRestartsFromTheTop` (existing) + the null-vs-`""` identity case (new) |

---

## 5. Test counts

| Module | Result | Baseline | Delta |
|---|---|---|---|
| `:app` (`testProDebugUnitTest`) | **925 / 0 / 0 / 1** | 907 / 0 / 0 / 1 | **+18** net |
| `:SoftEtherClient` | **184 / 0 / 0 / 0** | 184 / 0 / 0 / 0 | 0 (untouched) |

The single skip is the pre-existing `HtmlParserOracleTest`. Evidence: TEST.

---

## 6. Build

`:app:assembleProDebug` under `CI=true --rerun-tasks --no-build-cache`.

| Field | Value |
|---|---|
| Result | **BUILD SUCCESSFUL** in 5 m 45 s, 114 tasks executed |
| APK bytes | **63,798,322** |
| APK SHA-256 | `9f9d8a677cc6c88da48c93ff3a5cf6c7763ea837a95af1a7c7e2b31caa824cde` |
| `versionCode` | 144 (unchanged — no build or manifest file was touched) |
| `versionName` | 2.4.11-dev (unchanged) |

---

## 7. Diff scope

Eight main-source files, ten test files, one specification. Nothing else:

```
 PROJECT_SPECIFICATION.md                                     |  73 +++-
 app/src/main/java/.../automode/AutoModeController.kt         | 229 +++++---
 app/src/main/java/.../data/remote/HttpFetcher.kt             |  49 +--
 app/src/main/java/.../repository/VpnServerRepository.kt      | 294 ++++++++---
 app/src/main/java/.../state/GlobalVpnTracker.kt              |  46 ++-
 app/src/main/java/.../ui/screens/automode/AutoModeScreen.kt  |  33 ++
 app/src/main/java/.../ui/screens/settings/SettingsScreen.kt  |  52 +--
 app/src/main/java/.../utils/DataUtil.kt                      |  83 +---
 app/src/main/res/values/strings.xml                          |   2 -
 app/src/test/java/.../automode/AutoModeControllerTest.kt     | 117 +++++-
 app/src/test/java/.../automode/AutoModeNativeBusyTest.kt     |  11 +-
 app/src/test/java/.../data/remote/HttpFetcherR4bTest.kt      | 111 +++---
 app/src/test/java/.../repository/VpnServerRepositoryLadderTest.kt | 426 +++++++--
 app/src/test/java/.../repository/VpnServerRepositoryR4bTest.kt    |  52 ++-
 app/src/test/java/.../repository/VpnServerRepositoryRoutingDynamicTest.kt | 303 ++++++--
 app/src/test/java/.../repository/VpnServerRepositoryRoutingTest.kt | 101 +++--
 app/src/test/java/.../utils/DataUtilPrefersVpnTest.kt        | 249 ----------
 app/src/test/java/.../utils/DataUtilTimeoutRangeTest.kt      |  30 +-
 + app/src/test/java/.../utils/DataUtilRoutingPreferenceRemovedTest.kt (new)
```

**Not staged, deliberately:** `vpngate_collector.py` (tracked-dirty before this block and untouched by it) and `servers.csv`. Evidence: SOURCE CODE (`git status`).

---

## 8. What this revision does NOT claim

- **Not device-verified.** No device command was run. Everything above is SOURCE CODE + TEST.
- **No APK version bump.** `versionCode 144` / `versionName 2.4.11-dev` are unchanged; no build or manifest file was touched.
- **No native change.** `SoftEtherClient` is untouched; its 184 tests are unchanged.
- **No ladder-rung change.** `PHYSICAL → TUNNEL → EMERGENCY` is still the order; only *promotion* changed, from a user preference to measured evidence.
- **The `EPERM` of §1.6.38 P3 is still UNKNOWN.** This revision makes the automatic recovery work; it does not explain that diagnostic.

---

## 9. Device verification — PENDING (V-VERIFY-4)

| # | Check | Expected | Status |
|---|---|---|---|
| 1 | Physical path blocked by the ISP, tunnel up, no user action | Run 1 logs `[ladder] order=PHYSICAL,TUNNEL,EMERGENCY` and fails; run 2 logs `[ladder] order=TUNNEL,PHYSICAL,EMERGENCY` and succeeds | **UNKNOWN** |
| 2 | "Automatic Reconnection" switch | present on the Auto Connect screen, absent from Settings, state survives a restart | **UNKNOWN** |
| 3 | Dead server | abandoned at ~8 s, not ~12 s | **UNKNOWN** |
| 4 | Stale `vpn_always_prefer_vpn` on an upgraded install | ignored; no crash, no migration prompt, routing unaffected | **UNKNOWN** |

No device ran in this phase. Every row above is **UNKNOWN**, not "passing".

---

## 10. Build result (filled after the gate)

```
CI=true ./gradlew :app:assembleProDebug --rerun-tasks --no-build-cache

BUILD SUCCESSFUL in 5m 45s
114 actionable tasks: 114 executed

app/build/outputs/apk/pro/debug/app-pro-debug.apk
63,798,322 bytes
SHA-256 9f9d8a677cc6c88da48c93ff3a5cf6c7763ea837a95af1a7c7e2b31caa824cde
```

The APK is 3,264 bytes larger than v1.0.51's 63,795,058 — consistent with the added `PathMemory` / ladder / controller code and the removed UI, with no version or manifest change.
