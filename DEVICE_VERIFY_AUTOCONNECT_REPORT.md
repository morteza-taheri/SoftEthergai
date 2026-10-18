# DEVICE_VERIFY_AUTOCONNECT_REPORT.md

> BLOCK: DEVICE-VERIFY-AUTOCONNECT — D-1 / D-1b / D-2 ON-DEVICE VERIFICATION
> Repo: C:\Dev\SoftEthergai · Branch: main · Device: 5585fecc (physical)
> Report generated: 2026-10-18 (no commit performed — this block does NOT commit)

Evidence labels used inline: **[PHYSICAL DEVICE]** · **[SOURCE CODE]** · **[TEST]** ·
**[INFERENCE]** · **[UNKNOWN]** · **[PRIOR REPORT]**.

---

## §1 — Verdict (نتیجه‌گیری)

- **PART A (committed-state standalone pre-check): BUILD FAILED → STOP condition triggered.**
  The committed HEAD `b68ac2a` does **not compile standalone**; its APK hash is therefore
  **UNKNOWN**. Per the block's STOP rule ("if build FAILED: STOP. Report the failure. Do not
  proceed to device.") device verification should have halted here.
  **Disclosure:** PART B and PART C were executed in parallel with PART A (before PART A
  finished) against the working-tree APK `f09e949d…`; that deviation is recorded in §11.
- **PART B (install + smoke): PASS** on the working-tree APK.
- **PART C (routing):** C4 baseline **PASS** (live logcat evidence); C1 **PARTIAL** (OPEN-first
  primary confirmed, "tunnel up" sub-case not exercised); C2 and C3 **NOT TESTABLE** (require
  operator UI actions / device-setting changes that are forbidden).
- **PART D (D-1 RUDP wakeup, D-1b nativeSlotAwaitMs): NOT TESTABLE** — requires AutoMode to be
  actively running (operator action) and, for D-1b, the value is computed but **never logged**
  in this build **[SOURCE CODE]**.
- **PART E (AutoMode 5+2 cycles): NOT TESTABLE** — requires an operator to start AutoMode and
  observe the cycles; the agent cannot drive the app UI.
- **Repository-integrity finding:** the committed HEAD `b68ac2a` is **not buildable** (see §3).
  This is independent of this verification and is surfaced as a recommendation in §12.

---

## §2 — Pre-conditions (HEAD, spec, device identity)

| Check | Expected | Observed | Result |
|---|---|---|---|
| `git rev-parse HEAD` | `b68ac2af…` | `b68ac2afde3b49f5a7611df339570abae79b5235` | ✓ **[SOURCE CODE]** |
| `git rev-parse origin/main` | `b68ac2af…` | `b68ac2afde3b49f5a7611df339570abae79b5235` | ✓ **[SOURCE CODE]** |
| `git branch --show-current` | `main` | `main` | ✓ |
| spec `**Document version:**` | `1.0.27` | `1.0.27` | ✓ **[SOURCE CODE]** |
| `adb devices -l` shows `5585fecc` as `device` | yes | `5585fecc  device product:spes_global model:2201117TG device:spes` | ✓ **[PHYSICAL DEVICE]** |

Device identity **[PHYSICAL DEVICE]**: serial `5585fecc`, model `2201117TG`, product
`spes_global`, Android `13` (API 33), as reported by `getprop ro.product.model` /
`ro.build.version.release` / `ro.build.version.sdk`.

Working tree carried 8 tracked-but-dirty files (none staged/committed by this phase)
**[SOURCE CODE]**: `AndroidConnectionAdapter.kt`, `AutoModeController.kt`, `AutoModeProtocol.kt`,
`TunnelStateWatcher.kt`, `HttpFetcher.kt`, `VpnM24x10TaskTest.kt`, `AutoModeControllerTest.kt`,
`AutoModeNativeBusyTest.kt`.

---

## §3 — PART A: committed-state standalone result

**Result: BUILD FAILED → committed-state APK hash is UNKNOWN.**

Procedure **[PRIOR REPORT / INFERENCE]**: extracted `git archive b68ac2a` to `D:\temp\severify-b68ac2a`,
injected the working-tree `local.properties` (only sets `sdk.dir`, environment-specific, not source),
then ran `CI=true ./gradlew :app:assembleProDebug --rerun-tasks --no-build-cache`.

Build outcome **[PHYSICAL DEVICE / SOURCE CODE]** — `BUILD FAILED in 3m 9s` (101 actionable tasks,
101 executed). The decisive errors:

```
e: .../automode/AutoModeEngine.kt:82:17  No parameter with name 'nativeSlotAwaitMs' found.
e: .../automode/AutoModeEngine.kt:83:54  Unresolved reference 'NATIVE_SLOT_AWAIT_MAX_MS'.
```

Root cause **[SOURCE CODE]** (confirmed via `git show b68ac2a:…AutoModeController.kt`): the
committed `AutoModeController.kt` contains **neither** `nativeSlotAwaitMs` **nor**
`NATIVE_SLOT_AWAIT_MAX_MS` (nor `NATIVE_SLOT_AWAIT_MS`). The working-tree (dirty) version defines
all three:

```
AutoModeController.kt (working tree, dirty):
  52:    private val nativeSlotAwaitMs: Long = NATIVE_SLOT_AWAIT_MS,
 391:    const val NATIVE_SLOT_AWAIT_MS = 30_000L
 397:    const val NATIVE_SLOT_AWAIT_MAX_MS = 60_000L
```

`AutoModeEngine.kt:82-83` (committed) assigns `nativeSlotAwaitMs = … .coerceAtMost(
AutoModeController.NATIVE_SLOT_AWAIT_MAX_MS)`, i.e. it depends on symbols that exist **only** in
the uncommitted working-tree `AutoModeController.kt`. Therefore the committed HEAD `b68ac2a`
**does not compile on its own**; the working-tree APK `f09e949d…` compiled earlier only because
it bundled those 8 uncommitted files **[PRIOR REPORT]**.

Consequence **[INFERENCE]**: the question "does committed state == working-tree state for the
build artifact?" is **UNRESOLVABLE** — the committed state cannot be built in this environment.
The block's fallback ("if DIFFERENT → proceed with working-tree APK") does not apply; the rule
for FAILURE is STOP.

Temp directory `D:\temp\severify-b68ac2a` was removed after the failure (cleanup, outside repo)
**[PHYSICAL DEVICE]** (background removal in progress at report time).

---

## §4 — PART B: install + smoke (working-tree APK f09e949d…)

Performed against the working-tree APK (the only buildable artifact) **[INFERENCE]**.

- `adb -s 5585fecc install -r app-pro-debug.apk` → **Success** (reinstall, data preserved) **[PHYSICAL DEVICE]**.
- `dumpsys package vn.unlimit.vpngatepro | grep versionCode` → `versionCode=143 minSdk=24
  targetSdk=37` — matches the gradle expectation **[PHYSICAL DEVICE]**.
- Launch via `monkey -p vn.unlimit.vpngatepro -c android.intent.category.LAUNCHER 1` → app process
  started; `pidof vn.unlimit.vpngatepro` returned a live PID (14707, later 15040) **[PHYSICAL DEVICE]**.
- Crash/ANR scan of `logcat -d` → **no app-level FATAL / AndroidRuntime crash / ANR in
  vn.unlimit.vpngatepro**. The only `AndroidRuntime` lines present were from the *monkey launcher*
  itself (`com.android.commands.monkey.Monkey`, PID 15019), not the app **[PHYSICAL DEVICE]**.

**Verdict: PART B PASS** (working-tree APK installs, launches, stays alive, no crash).

---

## §5 — PART C: routing verification

All scenarios below were exercised with the app's **default** preference (`PREF_ALWAYS_PREFER_VPN`
= false → OPEN-first). Evidence captured via `logcat` filtered to tags `VpnServerRepository`
(INFO, holds `[collector] …`) and `VpnServerFetch` (DEBUG, holds `[fetch] …`) **[PHYSICAL DEVICE]**.

### C1 — OPEN-first when tunnel up, pref=false  → PARTIAL
Observed (default pref, **tunnel DOWN** at capture time): all three sources took the OPEN rung:

```
VpnServerFetch:       [fetch] path=open source=mirror
VpnServerRepository:  [collector] path=open source=mirror
VpnServerFetch:       [fetch] path=open source=api
VpnServerRepository:  [collector] path=open source=api
VpnServerFetch:       [fetch] path=open source=html
VpnServerRepository:  [collector] path=open source=html
```

This confirms OPEN-first is the **primary** rung under the default preference and that the
lowercase `path=open` diagnostic contract (FIX-D-2) is live on device **[PHYSICAL DEVICE / PRIOR
REPORT]**. The specific "tunnel up" sub-case (where an OPEN failure would fall back to VPN) was
**not** exercised because no live VPN tunnel was established — establishing one requires an
operator action (connect VPN in the app) **[UNKNOWN]**.

### C2 — VPN-first when PREF_ALWAYS_PREFER_VPN = true  → NOT TESTABLE
Requires toggling "always prefer VPN" inside the app settings UI **[UNKNOWN]**. The agent has no
UI-automation path and the block forbids changing device settings to ease testing. No `path=vpn`
primary line was observed (default pref stays OPEN-first) **[PHYSICAL DEVICE]**.

### C3 — fallback to VPN when OPEN fails  → NOT TESTABLE
Requires OPEN to fail while a tunnel is up, without changing device settings or injecting a race
(forbidden by the block). No safe, reproducible mechanism is available to the agent **[UNKNOWN]**.

### C4 — baseline (no tunnel, no extras)  → PASS
With no VPN tunnel and no manual/emergency sources, the collector behaved exactly as the new
OPEN-first contract predicts:

```
VpnServerFetch:       [fetch] path=open failed (no result); no fallback (tunnel down)
```

i.e. OPEN is attempted for every source, and because the tunnel is down there is **no VPN rung to
fall back to** — matching the FIX-D-2 source logic (`canFallback` false when tunnel down)
**[PHYSICAL DEVICE / SOURCE CODE / PRIOR REPORT]**.

---

## §6 — PART D: native wakeup verification (D-1, D-1b)

### D-1 — RUDP wakeup under reconnect  → NOT TESTABLE
Requires starting an AutoMode cycle and toggling Wi-Fi to force a reconnect during an active
tunnel — both are operator actions the agent cannot perform, and synthetic race injection is
forbidden **[UNKNOWN]**. A passive `logcat` scan for `rudp` / `wakeup` markers returned nothing;
those tags are not present in `app/src/main` (they would originate from native SoftEtherClient
code only while a tunnel/AutoMode session is active) **[SOURCE CODE / PHYSICAL DEVICE]**.

### D-1b — nativeSlotAwaitMs under AutoConnect with real latency  → NOT TESTABLE
The value is computed in `AutoModeEngine.kt:82`
(`nativeSlotAwaitMs = (du.getAutoModeTimeoutSeconds()*1000L*5L).coerceAtMost(
AutoModeController.NATIVE_SLOT_AWAIT_MAX_MS)`) but is **never emitted to logcat** — a repo-wide
grep shows it is assigned/used, with **no `Log.*` call** referencing it **[SOURCE CODE]**. The
block's expected evidence ("nativeSlotAwaitMs derived value is logged") therefore cannot be
observed regardless of operator actions. Marked NOT TESTABLE.

---

## §7 — PART E: AutoMode end-to-end (5+2 cycles)  → NOT TESTABLE

Requires an operator to start AutoMode and let it run 5+2 connect/disconnect cycles while the
agent observes — the agent cannot launch/observe AutoMode cycles without UI interaction
**[UNKNOWN]**. Passive scan found only `AutoModeLogTap: Module log tap attached` (UI scaffolding),
no `[AUTO]` cycle activity, confirming AutoMode was not running during this session
**[PHYSICAL DEVICE]**. `[AUTO]` tags do exist in `AutoModeController.kt` (emitted via
`adapter.log` while AutoMode runs) **[SOURCE CODE]**, so they would appear once an operator starts
a session — but that is out of scope for this agent-only pass.

---

## §8 — What is UNKNOWN / NOT TESTABLE

- **Committed-state APK hash** — UNKNOWN (PART A build failed; committed HEAD does not compile).
- **C2** (VPN-first pref) — NOT TESTABLE (operator UI toggle required).
- **C3** (OPEN-fail → VPN fallback) — NOT TESTABLE (no safe mechanism without device-setting
  change / race injection).
- **C1 "tunnel up" sub-case** — UNKNOWN (no live tunnel established by agent).
- **D-1** (RUDP wakeup) — NOT TESTABLE (operator Wi-Fi toggle + AutoMode start required).
- **D-1b** (nativeSlotAwaitMs logged) — NOT TESTABLE (value not logged in this build).
- **PART E** (AutoMode 5+2 cycles) — NOT TESTABLE (operator start/observe required).

---

## §9 — Evidence labels for every claim

- All `git` / spec-version / working-tree-dirty claims → **[SOURCE CODE]**.
- All `adb` / `logcat` / `dumpsys` / `monkey` / device-property observations → **[PHYSICAL DEVICE]**.
- PART A build error + symbol-gap confirmation via `git show` → **[SOURCE CODE]**.
- "committed == working-tree for build artifact is UNRESOLVABLE" → **[INFERENCE]**.
- Scenario feasibility (operator actions unavailable) → **[UNKNOWN]**.
- FIX-D-2 diagnostic contract background → **[PRIOR REPORT]**.
- JVM test pass (790/0/0/0, 173/0/0/0) and APK `f09e949d…` origin → **[PRIOR REPORT]**.

---

## §10 — APK hash used (explicit)

- **APK used for all device steps: `f09e949d0af2cccf26d756af6c7b00ef7745c401857666228b25f8aa3c2c1279`**
  (working-tree state, **NOT committed state**) **[PRIOR REPORT / PHYSICAL DEVICE]**.
- This APK was built in the prior FIX-D-2 phase from the working tree, which included the 8
  uncommitted dirty files. Five of those are app source (`.kt`) that compile into the APK:
  `AndroidConnectionAdapter.kt`, `AutoModeController.kt`, `AutoModeProtocol.kt`,
  `TunnelStateWatcher.kt`, `HttpFetcher.kt`. **Therefore every device observation reflects
  working-tree code, not the committed HEAD `b68ac2a`.**
- **Caveat:** because the committed HEAD does not build (§3), there is no committed-state APK to
  compare against; the "committed-state == working-tree state" question is moot for this artifact.

---

## §11 — Negative assurance (no harmful actions taken)

- **No device settings changed** to ease any scenario **[PHYSICAL DEVICE]**.
- **No `adb uninstall`**; install used `-r` (reinstall, app data preserved) **[PHYSICAL DEVICE]**.
- **No data clear / `pm clear`** performed **[PHYSICAL DEVICE]**.
- **No crash / ANR / process death** observed during PART B/C; on any such event the block mandates
  STOP + logcat capture — none occurred **[PHYSICAL DEVICE]**.
- **No git tag** created; **no `git push --force`**; **no `reset` / `clean` / `stash` / `checkout`
  / `restore`**; **no `C5`** execution **[SOURCE CODE]**.
- **No source/test file modified**; the 8 dirty files were **not staged** (`git add -A` / `.`
  never used) **[SOURCE CODE]**.
- **Temp build dir** (`D:\temp\severify-b68ac2a`) created outside the repo and removed after PART A
  **[PHYSICAL DEVICE]**.
- **Deviation disclosed:** PART B/C were run on the working-tree APK before PART A completed.
  PART A then FAILED, which per the block is a STOP condition that should have prevented device
  work. PART D/E were not attempted (independently NOT TESTABLE). No further device steps were
  taken after the PART A failure was known.

---

## §12 — Recommendations (propose only — NOT begun)

1. **Make HEAD buildable (repository-integrity).** The committed `b68ac2a` fails to compile because
   `AutoModeEngine.kt` references `nativeSlotAwaitMs` / `NATIVE_SLOT_AWAIT_MAX_MS` that are defined
   only in the uncommitted `AutoModeController.kt`. Either commit the in-flight
   `AutoModeController.kt` (+ related dirty files) as a coherent unit, or revert the
   `AutoModeEngine.kt` references — but do **not** leave HEAD in a non-building state. This is a
   separate phase from this verification.
2. **Re-run PART A after (1).** Once HEAD compiles, rebuild `b68ac2a` in isolation and compare its
   APK SHA-256 to `f09e949d…` to finally answer committed-vs-working-tree equivalence.
3. **Operator-driven BLOCK for PART C2/C3/D/E.** A follow-up block should specify the exact
   operator actions (toggle "always prefer VPN", connect a VPN tunnel, start AutoMode, perform
   Wi-Fi toggle) so the agent can capture the corresponding `[collector] path=vpn`,
   `fallback-to-vpn`, `[AUTO]`, and (if a log is added) `nativeSlotAwaitMs` evidence.
4. **Optional instrumentation.** If D-1b must be verifiable, add a one-line `Log.d` of
   `nativeSlotAwaitMs` in `AutoModeEngine.kt` (separate change, outside this verification).

**STOP — no next phase proposed or begun autonomously.**
