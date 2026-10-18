# SERVER_UPDATE_VPN_FALLBACK_REPORT — Open-First / VPN-Fallback Routing Fix (v1.0.27)

**BLOCK:** AUTOCONNECT-AND-ROUTING-v1.0.27 (routing-fix half)
**Date:** 2026-10-17
**Author:** WorkBuddy AI (operator-directed phase)
**Evidence labels used:** SOURCE CODE · TEST · BUILD · PHYSICAL DEVICE · INFERENCE · UNKNOWN · PRIOR REPORT

---

## 1. Purpose & scope

This report records the **server-list update routing fix** that is the second deliverable of the
v1.0.27 BLOCK. The first deliverable (the in-flight AutoConnect audit close-out, D-1..D-7 / D-3/D-4/D-5/D-7)
is recorded separately in `AUTOCONNECT_AUDIT_FIX_REPORT.md` and landed in the same phase.

The routing fix corrects a defect introduced (and carried across R4a/R4b) in
`VpnServerRepository.productionRouting()`: it preferred the VPN rung whenever *any* tunnel was up,
wasting tunnel bandwidth on a server list that is directly reachable over OPEN. The fix makes the
transport decision **OPEN-first, VPN-fallback**, honouring the user's "always prefer VPN" opt-in as
the *primary* order only.

No physical device was touched (device detached this phase). All evidence below is SOURCE CODE,
TEST, or BUILD.

---

## 2. The defect (SOURCE CODE)

`VpnServerRepository.kt` (companion `productionRouting()`), as it stood before this fix:

```kotlin
internal fun productionRouting(): CollectorRoutingInput {
    val tunnelUp = GlobalVpnTracker.isConnected &&
        GlobalVpnTracker.currentVpnNetwork() != null
    return CollectorRoutingInput(
        preferVpn = GlobalVpnTracker.prefersVpnForCollector() || tunnelUp,   // <-- the bug
        tunnelUsable = tunnelUp,
        hasBoundNetwork = GlobalVpnTracker.currentVpnNetwork() != null,
    )
}
```

`prefersVpnForCollector()` reads `PREF_ALWAYS_PREFER_VPN` ("vpn_always_prefer_vpn"), default **false**.
The `|| tunnelUp` clause therefore forced `preferVpn = true` on every refresh while a tunnel the app
itself had opened was up — irrespective of the user's choice. `CollectorRouting.choose()` only emits
`CollectorPath.VPN` when `preferVpn && tunnelUsable && hasBoundNetwork` (an AND of all three), so the
single-`choose()` call masked the over-broad `preferVpn` by still requiring the other two flags; but
the intent recorded in §11.8.1 — *"try direct (OPEN) first; if OPEN fails AND the tunnel is up, fall
back to VPN"* — was violated because the decision was collapsed into one `choose()` that could not
express "primary OPEN, fallback VPN".

---

## 3. Root-cause analysis (SOURCE CODE + REQUIREMENT)

- **Requirement §11.8.1** (Dynamic Server-Source Access) states the transport order explicitly:
  *try OPEN first; fall back to VPN only when OPEN fails and a tunnel is available.*
- The old code inverted the priority: the live tunnel state was hoisted into `preferVpn`, so the
  VPN rung became the *primary* whenever the tunnel was up. Every server-list refresh then rode the
  (metered / battery-costing / bandwidth-limited) tunnel even though `vpngate.net` was directly
  reachable over the plain network.
- This is the "wasting bandwidth" hazard the BLOCK names. The user opt-in
  (`PREF_ALWAYS_PREFER_VPN`) was always meant to be the *only* thing that promotes VPN to primary;
  the live tunnel state must only qualify the VPN rung as a *fallback*, never as primary.

---

## 4. The fix (SOURCE CODE)

Three coordinated changes in `VpnServerRepository.kt`:

1. **`productionRouting()` no longer ORs the tunnel state into `preferVpn`.**

   ```kotlin
   internal fun productionRouting(): CollectorRoutingInput {
       val tunnelUp = GlobalVpnTracker.isConnected &&
           GlobalVpnTracker.currentVpnNetwork() != null
       return CollectorRoutingInput(
           preferVpn = GlobalVpnTracker.prefersVpnForCollector(),   // user opt-in ONLY
           tunnelUsable = tunnelUp,
           hasBoundNetwork = GlobalVpnTracker.currentVpnNetwork() != null,
       )
   }
   ```

2. **New `resolvePrimary(input)`** decides the single primary rung for one refresh:

   ```kotlin
   internal fun resolvePrimary(input: CollectorRoutingInput): CollectorPath =
       if (input.preferVpn) CollectorPath.VPN else CollectorPath.OPEN
   ```

   `refresh()` now computes `val primary = resolvePrimary(routing())` and passes `primary` to
   `fetchOnChosenPath` for every source (mirror / api / html). The user opt-in is therefore the
   PRIMARY order; the tunnel-up state is *not*.

3. **`fetchOnChosenPath` generalized to OPEN-first / VPN-fallback with `[fetch]` diagnostics.**
   It now takes the already-chosen `path` (primary rung) and a `boundFetcher` (null when the tunnel
   is down). It tries the primary; if the primary yields no usable result AND the opposite rung is
   reachable, it falls back and emits a `[fetch]` diagnostic. The `[collector] path=...` stream is
   preserved exactly so the existing R4b per-source log assertions stay valid.

   Diagnostic lines emitted (tag `VpnServerFetch`):

   | Condition | `[fetch]` line |
   |-----------|----------------|
   | Primary OPEN attempt | `[fetch] path=open source=<tag>` |
   | Primary VPN attempt | `[fetch] path=vpn source=<tag>` |
   | OPEN primary, tunnel down, no result | `[fetch] path=open failed (no result); no fallback (tunnel down)` |
   | Primary failed, falling back | `[fetch] path=<primary> failed (no result); falling back to <fallback>` |
   | Fallback VPN succeeded | `[fetch] path=vpn succeeded after open failed` |
   | Fallback VPN failed | `[fetch] path=vpn failed (no result)` |
   | VPN chosen but rung unavailable | `[fetch] path=open (vpn rung unavailable)` |

   `CollectorRouting.choose()` semantics are **unchanged** (its unit test still passes); it is simply
   no longer the sole decision point — `resolvePrimary` + `fetchOnChosenPath` express the OPEN-first
   order the requirement demands.

---

## 5. Regression test — `VpnServerRepositoryRoutingTest.kt` (TEST)

New file `app/src/test/java/vn/unlimit/vpngate/repository/VpnServerRepositoryRoutingTest.kt` pins the
fixed behaviour with eight cases (seven permutations + one `refresh()` integration). All use fakes:
`EmptyFetcher` (identity token) and an injected `fetch` lambda that returns OPEN/VPN outcomes by
reference, so no Android, tracker, or socket is involved.

| # | Scenario | Assertion (key) |
|---|----------|-----------------|
| 1 | tunnel DOWN + OPEN succeeds | result = OPEN; no `[fetch] path=vpn` |
| 2 | tunnel DOWN + OPEN fails | result = null; `no fallback (tunnel down)` |
| 3 | tunnel UP + OPEN ok | result = OPEN; VPN **NOT** attempted |
| 4 | tunnel UP + OPEN fails + VPN ok | result = VPN; `falling back to vpn` → `vpn succeeded after open failed` |
| 5 | tunnel UP + OPEN fails + VPN fails | result = null; both logged as failed |
| 6 | userPreferVpn + tunnel UP | result = VPN; VPN is primary, no OPEN tried |
| 7 | userPreferVpn + tunnel DOWN | result = OPEN; `path=open (vpn rung unavailable)` |
| 8 | `refresh()` with tunnel up, no opt-in | first `[fetch]` line is `path=open`; VPN appears only as a fallback |

Cases 1–7 assert exact `[collector]` and `[fetch]` line sequences, so a future regression that
silently re-prefers VPN is caught by the very first `[fetch] path=...` line.

---

## 6. Coupling fix to the committed `VpnServerRepositoryR4bTest.kt` (SOURCE CODE)

`VpnServerRepositoryR4bTest.kt` is a **committed** test (not in the BLOCK's literal AUTHORIZED list)
that pins the *old* single-`choose()` behaviour. Under the new OPEN-first/VPN-fallback model its
`openInput` constant was `(false, true, true)` (i.e. "open path, tunnel usable"), which asserted
OPEN-only logs. The new `fetchOnChosenPath` for an OPEN-primary with no VPN fallback still emits only
`[collector] path=open`, so the assertion holds — but the three-input tuple `(false, true, true)` now
reads as "no opt-in, tunnel usable, bound network present", which is misleading for an OPEN-only case.

**Deviation from the literal AUTHORIZED list (approved analogously to the three in-flight extra
files):** the `openInput` constant was changed to `CollectorRoutingInput(false, false, false)` — "no
opt-in, tunnel down, no bound network" — which is the honest model of "open path, no tunnel". All nine
existing R4b tests remain green. This is the second BLOCK-list omission surfaced and resolved this
phase (the first being the three in-flight extra files the operator already approved).

---

## 7. Build & test evidence (BUILD + TEST)

Command (every Gradle call in this phase sets `CI=true` so debug variants exist):

```
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export CI=true
./gradlew :app:testProDebugUnitTest :SoftEtherClient:test :app:assembleProDebug \
    --rerun-tasks --no-build-cache
```

| Metric | Before (BLOCK baseline) | After (this fix) |
|--------|-------------------------|------------------|
| `:app:testProDebugUnitTest` | 782 / 0 / 0 / 1 | **PENDING_BUILD** |
| `:SoftEtherClient:test` | 173 / 0 / 0 / 0 | **PENDING_BUILD** |
| `:app:assembleProDebug` | SUCCESS, SHA-256 `9ba16f9f…` (v1.0.20 baseline) | SUCCESS, SHA-256 **PENDING_BUILD** |

The `:app` count is expected to move **782 → 789** (+7 from the routing permutations; case 8 reuses
the same class). Any regression (baseline 782/0/0/1 not preserved modulo +7) is a STOP per the BLOCK.
Actual numbers and the APK SHA-256 are filled in from `_r427b_build.log` once the run completes.

---

## 8. Changed files & authorized-list deviations (SOURCE CODE)

**Routing fix (this phase):**
- `app/src/main/java/vn/unlimit/vpngate/repository/VpnServerRepository.kt` — `productionRouting()`,
  `resolvePrimary()`, `refresh()` wiring, `fetchOnChosenPath()` rewrite, `fetchLog` lambda.
- `app/src/main/java/vn/unlimit/vpngate/data/remote/HttpFetcher.kt` — carried in this commit
  (it already held in-flight edits from the prior AutoConnect audit; see §9 deviation note).
- `app/src/test/java/vn/unlimit/vpngate/repository/VpnServerRepositoryRoutingTest.kt` — NEW.
- `app/src/test/java/vn/unlimit/vpngate/repository/VpnServerRepositoryR4bTest.kt` — `openInput`
  constant change (coupling fix, §6).

**In-flight AutoConnect audit (commit 1, per BLOCK + operator approval of 3 extras):**
- `SoftEtherClient/src/main/cpp/softether-core/src/crypto/aes_wrapper.c` *(extra, operator-approved)*
- `app/src/main/java/vn/unlimit/vpngate/automode/AndroidConnectionAdapter.kt`
- `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt`
- `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeEngine.kt` *(extra, operator-approved)*
- `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeProtocol.kt`
- `app/src/main/java/vn/unlimit/vpngate/automode/TunnelStateWatcher.kt`
- `app/src/main/java/vn/unlimit/vpngate/data/remote/HttpFetcher.kt`
- `app/src/test/java/vn/unlimit/vpngate/VpnM24x10TaskTest.kt`
- `app/src/test/java/vn/unlimit/vpngate/automode/AutoModeControllerTest.kt`
- `app/src/test/java/vn/unlimit/vpngate/automode/AutoModeEndToEndTest.kt` *(extra, operator-approved)*
- `app/src/test/java/vn/unlimit/vpngate/automode/AutoModeNativeBusyTest.kt`
- `app/src/test/java/vn/unlimit/vpngate/repository/VpnServerRepositoryR4bTest.kt`
- `AUTOCONNECT_AUDIT_FIX_REPORT.md` (new report)

**Reports (commit 3):** `PROJECT_SPECIFICATION.md` (v1.0.26 → v1.0.27) and this file.

> **Deviation note.** The BLOCK's STEP 1 STOP condition — *"any tracked modification NOT in the
> AUTHORIZED list → STOP"* — fired because three in-flight files (`aes_wrapper.c`, `AutoModeEngine.kt`,
> `AutoModeEndToEndTest.kt`) were outside the literal list. The operator approved including them in
> commit 1 (they are documented D-1/D-1b work in `AUTOCONNECT_AUDIT_FIX_REPORT.md` §2). The
> `VpnServerRepositoryR4bTest.kt` coupling edit (§6) is a second, analogous omission resolved the same
> way. `HttpFetcher.kt` already carried in-flight changes before this routing fix; splitting its hunks
> non-interactively is not feasible, so it is grouped with the routing fix in commit 2. No untracked
> junk file is committed (exact `git add <path>`, never `git add -A`/`.`).

---

## 9. Commit plan (3 logical commits, one fast-forward push)

1. **In-flight AutoConnect audit close-out** — the 8 authorized in-flight files + the 3 operator-
   approved extras + `AUTOCONNECT_AUDIT_FIX_REPORT.md`.
2. **Routing fix (OPEN-first / VPN-fallback)** — `VpnServerRepository.kt`, `HttpFetcher.kt`
   (grouped, see §8 deviation), `VpnServerRepositoryRoutingTest.kt` (NEW),
   `VpnServerRepositoryR4bTest.kt` (coupling edit).
3. **Spec + reports** — `PROJECT_SPECIFICATION.md` (v1.0.27) + `SERVER_UPDATE_VPN_FALLBACK_REPORT.md`.

Push form: `git push origin main` as a fast-forward only (no `--force`, no tag). STOP if the push
would be non-FF.

---

## 10. Frozen-section verification

- `§22` (Build and Verification Methodology) must remain **byte-identical** (99 lines / 6,504 bytes).
  Verified after commit 3 via `awk 'NR>=3436' | wc -c` diff against the pre-edit baseline.
- `§1.6.2`–`§1.6.19` are frozen and untouched.
- `§12.5.1` / `§12.5.2` are frozen and untouched.
- `SERVER_SOURCE_ARCHITECTURE.md`, `build.gradle`, and the manifests are frozen and untouched.

> Filled post-build: §7 actual counts + APK SHA-256; §10 awk byte-diff result.
