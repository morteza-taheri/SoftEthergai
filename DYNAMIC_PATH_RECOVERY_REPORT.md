# DYNAMIC_PATH_RECOVERY_REPORT

**Block:** `v1.0.45 — DYNAMIC-PATH-RECOVERY` (Phase A, device-free)
**Repo:** `C:\Dev\SoftEthergai` · **Branch:** `main`
**Baseline commit:** `f8d314869051775ac113ffdbdd02440ed8c4b833`
**Spec:** `PROJECT_SPECIFICATION.md` **1.0.44 → 1.0.45** (§1.6.38 added)
**Date:** 2026-10-21
**Device runs:** **none** (Phase A is explicitly device-free; Phase B is out of scope)

---

## 1. Verdict

**PASS at SOURCE CODE + TEST level. NOT device-verified.**

| # | Item | Result |
|---|------|--------|
| P1 | Toggle wrote a different SharedPreferences file than the reader reads | **CONFIRMED (definite)** — fixed |
| P2 | `path=open` was unbound, so it followed the OS default (i.e. the tunnel) | **CONFIRMED (definite)** — fixed |
| P3 | `Binding socket to network N failed: EPERM` | **UNKNOWN** — diagnostics added, **no fix attempted** |
| FIX-1 | Two-store write | **DONE** |
| FIX-2 | Explicit `PHYSICAL` / `TUNNEL` / `EMERGENCY`; unbound no longer a primary | **DONE** |
| FIX-3 | Diagnostics (path, exception class, errno, network handle, self-exclusion) | **DONE** |
| FIX-4 | Automatic ladder `PHYSICAL → TUNNEL (if usable) → EMERGENCY → preserve DB` | **DONE** |
| FIX-5 | Single-flight, total deadline, no retry loop, `CancellationException` propagates | **DONE** |
| Spec | 1.0.44 → 1.0.45, §1.6.38 (13 items), §20 rows, §11.8.2 annotation | **DONE** |
| §22 | Byte-identical | **VERIFIED** (SHA-256 match) |
| Device | Phase B | **NOT RUN — PENDING** |

The operator requirement — *the server list must update dynamically; if the physical path is blocked the update must go through the app's own tunnel automatically, no manual toggle, DB never wiped* — is **implemented and unit-tested**, and **not yet demonstrated on hardware**.

---

## 2. Pre-conditions (STEP 1, PASS)

| Check | Value |
|-------|-------|
| `HEAD` | `f8d314869051775ac113ffdbdd02440ed8c4b833` |
| `origin/main` | `f8d314869051775ac113ffdbdd02440ed8c4b833` (0 ahead / 0 behind) |
| Branch | `main` |
| Spec version at start | `1.0.44` |
| Tags | 5 (unchanged throughout) |
| Tracked modifications at start | none |

Evidence: `git rev-parse`, `git status --porcelain`. **TEST / SOURCE CODE.**

---

## 3. Findings

### 3.1 P1 — the two-store defect (definite)

**Writer:** `DataUtil.setPrefersVpnForCollector()` → `setBooleanSetting("vpn_always_prefer_vpn", enabled)` → the store opened in `DataUtil`'s `init`:

```kotlin
sharedPreferencesSetting = mContext!!.getSharedPreferences(
    "vpn_setting_data_" + BuildConfig.FLAVOR,   // DataUtil.kt:50
    Context.MODE_PRIVATE
)
```

**Reader:** `GlobalVpnTracker.prefersVpnForCollector()` (`GlobalVpnTracker.kt:526–528`) → `prefs?.getBoolean(PREF_ALWAYS_PREFER_VPN, false)`, where `prefs` is `PreferenceManager.getDefaultSharedPreferences(context)`.

**Same key literal. Different files.** The write never reached the read, so the v1.0.41 VPN gate (`preferVpn && tunnelUsable`) could never be satisfied in production, and the toggle was inert.

**Device evidence (v1.0.44 Phase B, T6b — FAIL).** With the in-app switch confirmed `checked=true` on the `checkable="true"` Switch node (the text nodes report `checked=false` even when ON, which is why the first reading looked like "off"), and with a tunnel up, every attempt still logged `path=open` first. Repeating the run with *Android system* always-on VPN made no difference, because that OS setting does not write the in-app preference at all.

Evidence: SOURCE CODE (both sites) + PHYSICAL DEVICE (T6b).

### 3.2 P2 — "open" was never "outside the tunnel" (definite)

`path=open` used `defaultCollectorClient()` — an **unbound** OkHttp client. When the app's own tunnel is up and the app is not excluded from it, the OS default network **is** that tunnel, so an "open" request travelled through the tunnel. Two consequences:

1. The `OPEN` / `VPN` distinction the routing model advertised did not exist on the physical path — both rungs could resolve to the same transport.
2. A genuinely blocked direct path could not be distinguished from a working one by "the open rung failed", because the open rung was not testing the direct path at all.

Operator's statement, recorded as the source for this finding: **the update works through another VPN app but not through this app's own tunnel** — the exact signature of an unbound client following the OS default.

Evidence: SOURCE CODE (client construction) + PHYSICAL DEVICE (operator observation).

### 3.3 P3 — the EPERM mechanism (UNKNOWN)

Device log (v1.0.44 T6d, PASS as a diagnostic):

```
Request failed: [exception=SocketException] Binding socket to network 105/106 failed: EPERM (Operation not permitted)
```

Three hypotheses remain open, none confirmed or excluded:

| Hypothesis | Status | Why it is not settled |
|---|---|---|
| MIUI per-app network policy | OPEN | Requires a MIUI-side setting check on device; no device run in this phase |
| App excluded from its own tunnel | OPEN | `addDisallowedApplication(packageName)` is gated on `config.excludedApps` (`SoftEtherVpnService.kt:1398–1404`), inside a **frozen** module; the routing layer cannot read it, so `SelfExclusion` reports `UNKNOWN` |
| Stale VPN `Network` handle | OPEN | Would need a device run correlating the handle id with the live tunnel lifetime |

**No fix was attempted.** The correct fix depends on which hypothesis the next device run reveals; guessing would have changed behaviour without evidence. FIX-3 therefore adds attribution only.

---

## 4. The fixes

### 4.1 FIX-1 — two-store write (`DataUtil.kt`)

`setPrefersVpnForCollector()` now writes **both** stores:

```kotlin
setBooleanSetting("vpn_always_prefer_vpn", enabled)      // DataUtil store (unchanged)
runCatching {
    val target = defaultPreferences
        ?: mContext?.let { PreferenceManager.getDefaultSharedPreferences(it) }
    target?.edit()?.putBoolean("vpn_always_prefer_vpn", enabled)?.apply()
}.onFailure { /* never crash Settings */ }
```

- Reader store held in `defaultPreferences` (`DataUtil.kt:44`); production resolves it lazily from `mContext`.
- New injected seam `internal constructor(sharedPreferences, defaultPreferences)` (`:78–84`) so the two-store write is assertable on a plain JVM.
- Getter **unchanged** (`:422–423`) — it still reads the DataUtil store, so the switch keeps painting what it always painted.
- Key literal `"vpn_always_prefer_vpn"` **unchanged** (frozen constraint).
- `runCatching` means a missing context can never crash the Settings screen.

### 4.2 FIX-2 — three explicit transports (`HttpFetcher.kt`, `GlobalVpnTracker.kt`, `VpnServerRepository.kt`)

```kotlin
enum class CollectorPath {
    PHYSICAL,   // NOT_VPN + INTERNET + VALIDATED network, bound per request
    TUNNEL,     // the app's own VPN network, bound per request
    EMERGENCY,  // bundled servers.csv; no network binding
}
```

- `boundToPhysicalNetwork(network)` (`HttpFetcher.kt:176–186`) — new; logs `[bind-physical] ok network=` / `[bind-physical] FAILED network= errno=`; reuses `boundToSocketFactory` so its timeouts/DNS cannot drift from the tunnel client; rethrows.
- `GlobalVpnTracker.currentPhysicalNetwork()` (`:538–561`) — read-only; iterates `cm.allNetworks`, keeps NOT_VPN + INTERNET + VALIDATED, prefers WIFI → CELLULAR → first.
- `productionBoundFetcher()` now means **PHYSICAL**; the new `productionTunnelFetcher()` means **TUNNEL**; `boundFetcherFactory`'s default is now `productionTunnelFetcher()`.
- Binding is still **per request** via `Network.getSocketFactory()`. `bindProcessToNetwork()` is still **never** called (architecture §7 Q2).
- `CollectorRouting.choose` keeps its three-way AND and never returns `EMERGENCY`.

### 4.3 FIX-3 — diagnostics

| Diagnostic | Site |
|---|---|
| `[bind-physical] ok network=…` / `[bind-physical] FAILED network=… errno=…` | `HttpFetcher.kt:176–186` |
| `SelfExclusion { UNKNOWN, NOT_EXCLUDED, EXCLUDED }` + `isSelfExcludedFromOwnTunnel()` | `GlobalVpnTracker.kt:564`, `:813` — returns `UNKNOWN` in this build |
| `[ladder] order=PHYSICAL,TUNNEL,EMERGENCY` | `VpnServerRepository.kt:291` |
| `[ladder] <PATH> failed (no result) network=<handle> selfExcluded=<tri-state>` | `:374–378` |
| `[ladder] skip=EMERGENCY (none configured)` | `:372` |
| `[ladder] all rungs failed; preserving DB` | `:379` |
| `[ladder] deadline exceeded (45000ms); preserving DB` | `:270` |

The `[collector] path=…` stream keeps its per-source shape, so the existing per-source log contract still holds.

### 4.4 FIX-4 — the automatic ladder

```
routeLadder(input):
  PHYSICAL                      always the base rung
  + TUNNEL                      only when tunnelUsable && hasBoundNetwork
  + EMERGENCY                   always last
  if (preferVpn && tunnelUsable && hasBoundNetwork)  -> TUNNEL to the front
```

| preferVpn | tunnelUsable | hasBoundNetwork | ladder |
|---|---|---|---|
| F | F | F | PHYSICAL, EMERGENCY |
| F | F | T | PHYSICAL, EMERGENCY |
| F | T | F | PHYSICAL, EMERGENCY |
| F | T | T | PHYSICAL, TUNNEL, EMERGENCY |
| T | F | F | PHYSICAL, EMERGENCY |
| T | F | T | PHYSICAL, EMERGENCY |
| T | T | F | PHYSICAL, EMERGENCY |
| T | T | T | **TUNNEL**, PHYSICAL, EMERGENCY |

Key properties:

- **The opt-in only reorders.** It no longer gates reachability — a blocked physical path recovers through the tunnel with **no user action**.
- **Reachability is unchanged from v1.0.41**: `TUNNEL` appears only under the same three-way AND, so this phase does not silently widen when the tunnel is used.
- `resolvePrimary()` is now `routeLadder(input).first()` — one source of truth.
- `fetchOnChosenPath` falls back to the opposite main rung only when reachable; a `TUNNEL` primary with no bound fetcher degrades to `PHYSICAL` and **says so** (`path=physical (tunnel rung unavailable)`) rather than claiming a fallback it did not perform.
- **DB preservation is explicit**: every-rung-fails logs `[ladder] all rungs failed; preserving DB` and returns the last-known-good snapshot. Nothing truncates or clears the store.
- `EMERGENCY` is modelled but **inert** in this build (`hasEmergencySources() = false`); the skip is logged.

### 4.5 FIX-5 — bounded budgets

- **Single-flight:** `refresh()` body runs under `refreshMutex.withLock` — a second caller waits instead of starting a parallel collection (the old code allowed parallel collection, doubling request volume).
- **One total deadline:** `withTimeoutOrNull(MAIN_TIMEOUT_MS)` (45 s) wraps the whole body, not each request.
- **Null vs timeout:** a `completed` flag distinguishes a genuine `null` result from a timeout, since both return `null`.
- **No retry loop:** the ladder is a single pass. The source-shape test asserts `refresh()` contains no `while (`.
- **`CancellationException`** is not caught anywhere in the new code; it propagates unchanged.

---

## 5. Tests

### 5.1 New (21 tests)

| ID | File | Pins |
|---|---|---|
| T-NEW-54a | `DataUtilPrefersVpnTest` | setting the opt-in writes **both** stores |
| T-NEW-54b | `DataUtilPrefersVpnTest` | clearing the opt-in clears **both** stores |
| T-NEW-54c | `DataUtilPrefersVpnTest` | the getter still reads the DataUtil store |
| T-NEW-54d | `DataUtilPrefersVpnTest` | no default store ⇒ still writes, does not throw |
| T-NEW-55a/b/c | `VpnServerRepositoryLadderTest` | no opt-in: PHYSICAL first; TUNNEL only when usable **and** bound |
| T-NEW-56a/b/c | `VpnServerRepositoryLadderTest` | opt-in reorders to TUNNEL; dead tunnel / no network leaves PHYSICAL first |
| T-NEW-57 / 57b | `VpnServerRepositoryLadderTest` | invariants across all 8 combos: PHYSICAL present, EMERGENCY last & once, no duplicates, size 2–3, TUNNEL iff `u && n`, `resolvePrimary == first` |
| T-NEW-58a/b/c | `HttpFetcherPhysicalBindTest` | helper exists; `[bind-physical] ok` prefix; enum is exactly PHYSICAL/TUNNEL/EMERGENCY |
| T-NEW-59 | `HttpFetcherPhysicalBindTest` | failure line carries `errno=` |
| T-NEW-60 / 60b | `HttpFetcherPhysicalBindTest` | reuses `boundToSocketFactory`, rethrows, no `return null`; both helpers coexist |
| T-NEW-61 | `VpnServerRepositoryRoutingDynamicTest` | `[ladder] order=` is emitted before any source is launched |
| T-NEW-62 | `VpnServerRepositoryRoutingDynamicTest` | `resolvePrimary` delegates to `routeLadder`; the old if/else gate is gone |
| T-NEW-63 | `VpnServerRepositoryRoutingDynamicTest` | `Mutex`, `withTimeoutOrNull(MAIN_TIMEOUT_MS)`, deadline log, preserve-DB log, no `while (` |

### 5.2 Updated under a second authorization (STOP rule honoured)

The Phase A brief's STOP rule: *"If any existing test pins the old 'open-first-then-vpn' behaviour with an assertion that the new ladder would break, STOP and report the exact test + assertion; do NOT modify the test without a second authorization."*

The condition **was hit**. Four files (~31 tests) pinned the retired labels:

| File | Pinned strings / assertions |
|---|---|
| `HttpFetcherR4bTest.kt` | `CollectorPath.OPEN` ×3, `CollectorPath.VPN`, `openCount == 7` |
| `VpnServerRepositoryRoutingTest.kt` | `[fetch] path=open source=html`, `path=open failed (no result); no fallback (tunnel down)`, `falling back to vpn`, `path=vpn succeeded after open failed`, `[collector] path=fallback-to-vpn`, `[fetch] path=vpn source=html`, `[fetch] path=open (vpn rung unavailable)`, `fetchLogs.first().contains("path=open")` |
| `VpnServerRepositoryRoutingDynamicTest.kt` | T-NEW-36 source-shape bound on `resolvePrimary`, T-NEW-37 `== CollectorPath.VPN`, T-NEW-38 `CollectorPath.VPN -> boundFetcher != null` / `CollectorPath.OPEN -> true` |
| `VpnServerRepositoryR4bTest.kt` | `path=open` / `path=vpn` / `fallback-to-open` in 10 exact-list assertions, `CollectorPath.VPN` call args |

The AI **stopped without editing** and reported the enumeration. The operator replied **"الف"** (option A: authorise updating the four files). Only then were they rewritten. **No test was deleted**, and every pinned behaviour was preserved under its new name (primary-first, fallback-only-when-reachable, degrade-without-claiming-a-fallback, re-read-per-refresh).

`HttpFetcherBindDiagnosticTest.kt` was listed in the brief's staging set but needed **no change** — its four assertions (`[bind] ok`, `[bind] FAILED`, `boundToNetwork` exists, `exception=$causeClass`) all still hold, which is itself the evidence that FIX-2 did not disturb the v1.0.43/v1.0.44 diagnostics.

---

## 6. Counts (STEP 7)

| Suite | Result | Baseline | Delta |
|---|---|---|---|
| `:app:testProDebugUnitTest` | **887 / 0 / 0 / 1** | 866 / 0 / 0 / 1 | **+21** |
| `:SoftEtherClient:test` | **173 / 0 / 0 / 0** | 173 / 0 / 0 / 0 | 0 (module untouched) |

Per-class: `VpnServerRepositoryLadderTest` 8, `HttpFetcherPhysicalBindTest` 6, `DataUtilPrefersVpnTest` 8 (4 new), `VpnServerRepositoryRoutingDynamicTest` 6 (3 new), `VpnServerRepositoryRoutingTest` 8, `VpnServerRepositoryR4bTest` 10, `HttpFetcherR4bTest` 11, `HttpFetcherBindDiagnosticTest` 4 — all 0 failures.

Commands (both with `CI=true`):
```
./gradlew :app:testProDebugUnitTest --rerun-tasks --no-build-cache
./gradlew :SoftEtherClient:test        --rerun-tasks --no-build-cache
```
Counts aggregated from `build/test-results/**/*.xml` (`tests`/`failures`/`errors`/`skipped` attributes). Evidence: **TEST**.

---

## 7. Build (STEP 8)

```
CI=true ./gradlew :app:assembleProDebug    →  BUILD SUCCESSFUL
```

| Artifact | Value |
|---|---|
| Path | `app/build/outputs/apk/pro/debug/app-pro-debug.apk` |
| Size | 66,071,781 bytes |
| **SHA-256** | **`3f0009afb0c1a60eca49435c2156a09f41d7de1b468154cd047f6d12310c4963`** |
| `versionCode` / `versionName` | `144` / `2.4.11` — **unchanged** |

No build or manifest file was touched, so the version identity is untouched. Evidence: **TEST**.

> Note: the first Gradle invocation in this environment failed at *service creation* with `java.net.BindException: Address already in use: Cannot bind` (no `java.exe` was running). Re-running with `--no-daemon` succeeded; all subsequent builds used `--no-daemon`. This is an environment artefact, not a repository change — see §1.6.38 bullet 13 (no new methodology lesson was warranted).

---

## 8. Diff scope (STEP 9)

**10 tracked files changed, 2 new test files added.**

Main (5):
```
app/src/main/java/vn/unlimit/vpngate/data/remote/HttpFetcher.kt
app/src/main/java/vn/unlimit/vpngate/repository/VpnServerRepository.kt
app/src/main/java/vn/unlimit/vpngate/state/GlobalVpnTracker.kt
app/src/main/java/vn/unlimit/vpngate/utils/DataUtil.kt
app/src/main/res/values/strings.xml
```
Tests (5 modified):
```
app/src/test/java/vn/unlimit/vpngate/data/remote/HttpFetcherR4bTest.kt
app/src/test/java/vn/unlimit/vpngate/repository/VpnServerRepositoryR4bTest.kt
app/src/test/java/vn/unlimit/vpngate/repository/VpnServerRepositoryRoutingDynamicTest.kt
app/src/test/java/vn/unlimit/vpngate/repository/VpnServerRepositoryRoutingTest.kt
app/src/test/java/vn/unlimit/vpngate/utils/DataUtilPrefersVpnTest.kt
```
Tests (2 new):
```
app/src/test/java/vn/unlimit/vpngate/data/remote/HttpFetcherPhysicalBindTest.kt
app/src/test/java/vn/unlimit/vpngate/repository/VpnServerRepositoryLadderTest.kt
```

`git diff --stat`: 10 files changed, 713 insertions(+), 325 deletions(-) before the spec edits.

### 8.1 Negative assurance — files that were **not** touched

| File / item | Why it matters |
|---|---|
| `ui/screens/settings/SettingsScreen.kt` | FROZEN. Its toggle still calls the same `dataUtil.setPrefersVpnForCollector(it)`; only the *writer's* behaviour changed. |
| `SoftEtherClient/.../SoftEtherVpnService.kt` | FROZEN. `addDisallowedApplication` untouched; hence `SelfExclusion` stays `UNKNOWN`. |
| `bindProcessToNetwork` | **never** called, before or after. Per-request binding only (§7 Q2). |
| `isTunnelUsable()` / `isTunnelUsableForRouting()` | unchanged; the ladder consumes the same signal. |
| `PREF_ALWAYS_PREFER_VPN` literal | unchanged (`"vpn_always_prefer_vpn"`). |
| C5 | untouched. |
| `app/build.gradle`, manifest, signing config | untouched — `versionCode 144` / `versionName 2.4.11`. |
| `D:\PMC\Documents\Android\SoftEthergai` | never accessed. |
| Any device / `adb` command | **none executed in this phase.** |
| Tags | none created, moved, or deleted (still 5). |
| Tests deleted | **none.** |

---

## 9. §22 identity (STEP 10, E7)

`§22. Build and Verification Methodology (Lessons)` is **byte-identical** across this revision.

| Check | Value |
|---|---|
| Section offset (before / after) | 549,099 / changed (earlier sections grew) |
| Length | 6,450 bytes — **unchanged** |
| SHA-256 before | `adf12858b72ed13742252c512a8728e9141e415cc55d09c6409af4e8e622020e` |
| SHA-256 after | `adf12858b72ed13742252c512a8728e9141e415cc55d09c6409af4e8e622020e` |
| Match | **TRUE** |

Computed over everything from `## 22. Build and Verification Methodology` to end of file, before and after the spec edits. Evidence: **TEST** (hash).

Spec edits actually applied:

| Edit | What |
|---|---|
| E1 | Header: `Document version 1.0.45`, `Last updated 2026-10-21`, `HEAD f8d3148…` (the baseline this revision was verified against) |
| E2 | §1.6.1: new `> **Push state (v1.0.45).**` paragraph |
| E3 | §11.8.2: new v1.0.45 update block (explicit PHYSICAL/TUNNEL; the toggle only reorders) |
| E4 | **NEW §1.6.38** "DYNAMIC-PATH-RECOVERY (v1.0.45)", 13 items |
| E5 | §20 changelog row `1.0.45` |
| E6 | §20 verification row `§1.6.38` (plus the §11.8 row moved `DESIGN` → `PARTIAL`) |
| E7 | §22 — **not touched** (verified byte-identical) |

§1.6.2 – §1.6.37 were not modified.

---

## 10. What remains

| Item | Status |
|---|---|
| **Phase B — device verification** | **NOT RUN.** See §11. |
| **P3 root cause** (`EPERM`) | **UNKNOWN.** Diagnostics exist; the answer needs a device log. |
| **App self-exclusion confirmation** | **UNKNOWN.** The exclusion list is inside the frozen `SoftEtherVpnService`; `SelfExclusion` returns `UNKNOWN` until that boundary is authorised to expose it. |
| **Per-source path memory** | **NOT IMPLEMENTED** — the spec's §11.8 "retain useful source/path outcome information" is still phase 2; the ladder is stateless per refresh. |
| **Emergency `servers.csv` publication** | **NOT IMPLEMENTED** — the `EMERGENCY` rung is modelled but has no configured source, so it is inert by construction (and logs the skip). |
| **Retention phases 2–3** | **NOT IMPLEMENTED** (§9.3 retention-priority prune + max-server cap). |
| **Physical path with only an unvalidated network** | **UNTESTED** — `currentPhysicalNetwork()` requires `NET_CAPABILITY_VALIDATED`, so such a device finds no PHYSICAL rung and degrades to the injected fetcher. |

---

## 11. Device verification plan (Phase B — not executed here)

| ID | Setup | Expected |
|---|---|---|
| **B1 — PHYSICAL alone** | Tunnel down; toggle off; Wi-Fi or cellular up | Every source logs `[fetch] path=physical`, `[ladder] order=PHYSICAL,EMERGENCY`, and `network=` is a real handle (not `none`); no `path=tunnel` line. |
| **B2 — TUNNEL alone** | Toggle **on**; usable tunnel up (the T6b retry) | `[ladder] order=TUNNEL,PHYSICAL,EMERGENCY` and `path=tunnel` **first** — this is the check that proves FIX-1 on hardware, and the one that failed in v1.0.44. |
| **B3 — ladder end-to-end** | Toggle off; physical path blocked by the ISP; usable tunnel up | The update completes through the app's own tunnel with **no user action**: `path=physical failed … falling back to tunnel` then `path=tunnel succeeded`. Server list intact — **no wipe**, no manual toggle. |
| **B4 — P3 attribution** | Whatever reproduces the `EPERM` | Capture `[bind-physical]` / `[bind]` lines plus `[ladder] … network=<handle> selfExcluded=<tri-state>`, and record which hypothesis (MIUI policy / self-exclusion / stale handle) the handle id supports. |

Phase B is **not** part of this block and was not attempted. No `adb` command was issued.

---

## 12. Evidence index

| Claim | Evidence |
|---|---|
| P1 writer site | SOURCE CODE — `DataUtil.kt:50`, `:426–440` |
| P1 reader site | SOURCE CODE — `GlobalVpnTracker.kt:526–528` |
| P1 device proof | PHYSICAL DEVICE — v1.0.44 Phase B T6b FAIL (`DEVICE_VERIFY_SERVER_LIST_UPDATE_v1.0.44_REPORT.md`) |
| P2 unbound client | SOURCE CODE — `defaultCollectorClient()` used by the old `OPEN` rung |
| P2 operator observation | PHYSICAL DEVICE — operator statement (update works through another VPN app, not this app's own tunnel) |
| P3 `EPERM` | PHYSICAL DEVICE — v1.0.44 T6d logcat line |
| FIX-1 / FIX-2 / FIX-4 sites | SOURCE CODE — `DataUtil.kt`, `HttpFetcher.kt:176–186`, `:354–363`, `GlobalVpnTracker.kt:538–561`, `VpnServerRepository.kt:167–181`, `:260–291` |
| 21 new tests | TEST — `build/test-results/testProDebugUnitTest/*.xml` |
| Counts 887 / 173 | TEST — same XML aggregate |
| APK SHA-256 | TEST — `sha256sum app/build/outputs/apk/pro/debug/app-pro-debug.apk` |
| §22 identity | TEST — SHA-256 `adf12858…020e`, 6,450 bytes, before and after |
| Nothing device-verified | UNKNOWN — no device run in this phase |

---

**STOP.** Phase A is complete and reported. Phase B (device) is not started.
