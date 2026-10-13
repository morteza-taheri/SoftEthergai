# PROJECT_SPECIFICATION — SoftEther VPN Client for Android

**Project:** SoftEthergai — Native SoftEther VPN Client for Android
**Repository:** `D:\PMC\Documents\Android\SoftEthergai`
**Branch:** `main`
**HEAD:** `5e51e37f83bba3db8e3f859886bf045bb5ea6255`
**Document version:** 1.0.8
**Last updated:** 2026-10-13

> **Document status:** This is a living specification created from the read-only audit completed at lifecycle stage F.28AD-B. It captures the verified repository state, the architecture that was audited, the F.28-family defect history, the current native-lifetime and teardown-completion design, and the open requirements (including F.28AA, which is completed). All source files were inspected at HEAD; no code or configuration was modified in producing this document.

---

## 1. Project Baseline

### 1.1 Identity and Scope

The SoftEthergai project is a multi-protocol Android VPN client built around a **native SoftEther VPN implementation** (no third-party VPN app required for the SoftEther protocol). It also supports OpenVPN (via integrated library), MS-SSTP (Kotlin client), and L2TP/IPsec (via Android OS built-in client).

| Aspect | Value |
|--------|-------|
| Owner / Maintainer | Morteza Taheri |
| License | GPLv3 (derivative works must also be open-sourced under GPLv3) |
| Target platform | Android (mobile and tablet) |
| Min SDK | API 24 (Android 7.0) |
| Target / Compile SDK | API 37 (Android 14 QPR1) |
| Build system | Gradle 9.7.0, AGP 9.1.1 |
| Language | Kotlin 2.4.0 (primary), C/C++ (native SoftEther core), Java (legacy) |
| Native build | CMake 3.22.1, NDK, C++17 |

### 1.2 Module Structure

```
├── app/                  # Main application module (UI, Auto Mode, collector, database)
├── SoftEtherClient/      # Native SoftEther VPN protocol implementation (C/C++ + Kotlin JNI wrapper)
├── sstpClient/           # MS-SSTP protocol client (Kotlin)
├── vpnLib/               # OpenVPN integration library (third-party, schwabe/ics-openvpn)
└── server-setup/         # Server configuration scripts
```

### 1.3 Flavors and Build Targets

| Variant | Description |
|---------|-------------|
| `assembleFreeDebug` | Free flavor, debug build |
| `assembleProDebug` | Pro flavor, debug build |
| `bundleFreeRelease` | Free flavor, release AAB |

The release keystore is blackbox-encrypted and only deployed on CI; builds without it fall back to the debug keystore. Passwords resolve from the `SIGN_KEY_PASSWORD` environment variable (CI) or `local.properties` (developer machines, never committed).

### 1.4 External Dependencies

| Library | License | Purpose |
|---------|---------|---------|
| OpenVPN for Android (schwabe/ics-openvpn) | GPLv2 | OpenVPN client integration |
| Glide | Apache 2.0 | Image loading |
| Open SSTP Client (kittoku) | MIT | MS-SSTP client reference |
| Bouncy Castle | MIT | Cryptographic primitives |
| Jsoup | MIT | HTML parsing (VPN Gate server list) |
| OkHttp | Apache 2.0 | HTTP networking |
| Room | Apache 2.0 | Local database persistence |
| Compose | Apache 2.0 | UI toolkit |

### 1.5 Build Environment

- **JDK:** 17+
- **Android SDK:** compileSdk 37
- **Android NDK:** required for the SoftEther native module (CMake 3.22.1, C++17)
- **Gradle wrapper:** `gradle-9.7.0-bin.zip` (configured in `gradle/wrapper/gradle-wrapper.properties`)
- **Room schema location:** `$projectDir/schemas` (KSP-generated, committed for migrations)

### 1.6 Session Completion Summary (2026-10-12 → 2026-10-13)

Two working sessions (2026-10-12 and 2026-10-13) landed the DEEP-REVIEW P0 fixes, the complete
F.28AL single-owner / native-lifetime stack, and the native-lifetime (N-15/N-16) fixes; verified
that stack on the physical device; and then pushed the whole local chain to the remote.

- **17 local commits** constitute the chain that was pushed, forming the contiguous range `0714628`
  → `5e51e37` (see the table below). The range spans the pre-existing UI/cleanup commit, the
  DEEP-REVIEW P0 fixes, the F.28AL single-stack work, the KSP/tooling alignment, the native bounds
  tests, the session audit trail, the F.28AL native-lifetime fixes (N-15/N-16), the C3 test-rule
  alignment, and the T5-SLOT instrumentation.
- **The whole chain is now pushed.** On 2026-10-13 the local `main` was fast-forwarded onto
  `origin/main` (`5c13603..5e51e37`); local `main` and `origin/main` are now **in sync** (0 ahead,
  0 behind). No force, no tag. See §1.6.1 for the corrected chain and the `0714628` off-by-one note.
- **The F.28AL single-owner / native-lifetime stack is verified on the physical device.** After one
  intermediate failure and its repair (see §1.6.2), the resumed device verification
  (RESUME-BATCH-DEVICE-VERIFY) returned **PASS**: 6/6 native bounds/RUDP tests green and manual
  tests T2–T8 all clean. Evidence class: **PHYSICAL DEVICE EVIDENCE**.

#### 1.6.1 Commit chain

Seventeen commits constitute the chain that was pushed to `origin/main`, forming the contiguous
range `0714628` → `5e51e37` (oldest first).

| Commit | Subject |
|--------|---------|
| `0714628` | feat(ui): add DNS presets, app exception icons and Auto Mode cleanup |
| `b689cfe` | fix(native): validate JNI array lengths and release RUDP lock (P0 bounds) |
| `49f579b` | fix(android): route reconnect failure through service onError; name teardown identity |
| `e1132ad` | build(native): exclude test library from release build |
| `ac2764a` | build(ksp): align KSP version with Kotlin |
| `0688b51` | test(androidTest): expose native bounds tests to the Kotlin bridge |
| `8424afa` | docs(reports): session audit and verification trail |
| `ab82ce1` | fix(native): commit the missing F28AL-U classes (repairs `49f579b`) |
| `98a6462` | feat(softether): add session policy and TUN ownership coordinators |
| `c572050` | feat: land the F.28AL native lifetime stack |
| `6375818` | docs: update PROJECT_SPECIFICATION to v1.0.7 |
| `dc8e113` | test(native): repair test35 dummy-handle contradiction |
| `3a2f442` | fix(native): refcount rudp_context_t to close destroy-vs-poll use-after-free (N-15) |
| `b0418cc` | fix(native): hold connect_mutex in softether_connect_with_hub itself (N-16) |
| `38d9d33` | test(native): widen N-16-A post-release budget to 90s |
| `4c013e8` | test(native): align C3 verdict with C4 "observe the race" semantics |
| `5e51e37` | test(androidTest): instrument T5-SLOT holder identity in AutoModeController |

> **Correction (v1.0.8) — the `0714628` off-by-one.** Earlier handoffs (the v1.0.7 text and the push
> handoff) described `0714628` as the "base of the chain". That was an off-by-one: `0714628^` is
> `5c13603` (the old `origin/main`), and `0714628` is **not** an ancestor of the pre-push
> `origin/main`, so it is the **first unpushed commit**, not the base. The base is `5c13603`.
> Corrected here.
>
> **Push state (v1.0.8).** On 2026-10-13 the chain was pushed with a clean fast-forward
> (`5c13603..5e51e37`). `origin/main` now equals `HEAD` (`5e51e37`); local `main` is **0 ahead,
> 0 behind**. Verified with `git rev-list --count origin/main..HEAD` → **0** and
> `git rev-parse origin/main` → `5e51e37…`. No force, no tag. (The pre-v1.0.8 text here recorded
> **9** commits, unpushed, remote at `5c13603`; that snapshot is superseded by this revision.)

#### 1.6.2 Session outcome in one paragraph

The DEEP-REVIEW P0 set (native JNI bounds, the RUDP lock release, the reconnect-failure routing
through the service `onError` channel, teardown-log identity, and the tooling/version alignment) was
fixed, compiled under forced reruns, and committed. The F.28AL-U single-owner implementation and the
`SoftEtherSessionPolicy` / `TunOwnershipCoordinator` additions were landed on top. One commit
(`ab82ce1`) exists specifically to repair a dependency-closure defect in an earlier commit
(`49f579b`) — see lesson **L4** in §22. Device verification then exposed a **test-only** defect:
`test35` crashed because it passed a valid-length array with a dummy handle, forcing a native
dereference the test could not satisfy. That was repaired in a test-only change (FIX-N05-TEST-DEREFERENCE),
after which the resumed device run passed cleanly. The verification also surfaced **FINDING T5-SLOT**
(§12.4), which drives the next phase.

#### 1.6.3 Post-v1.0.7 Additions

Changes recorded after the v1.0.7 revision (all on 2026-10-13). Each bullet carries its evidence label
(see the evidence taxonomy in §20).

- **C3 test-rule fix landed (commit `4c013e8`).** C3's verdict was aligned with C4's "observe the
  race" semantics: `ok = !harness_bad && premise > 0` (the old `stale == 0` clause was removed for
  C3; a new `STALE_DONE_RATE_HIGH` diagnostic is appended when `staleDone > 25%` of iterations —
  informational only, not a pass/fail criterion). Device-verified via `test27`
  (`B3s C3 n=100 before=14 discResp=6 staleStart=53 staleDone=27 … ok=1`). Test-only; no production
  change. Evidence: SOURCE CODE + PHYSICAL DEVICE.
- **C5 formally GATED (skipped, not failed).** The C5 flakiness was triaged to a **pre-existing
  test-harness defect** (the silent responder's frame classification is too strict); the production
  retry DISCOVER is legitimate RFC-2131 behaviour and is not the cause. **No execution was
  performed**; the defect is deferred to a future `C5-TRIAGE-DEVICE` phase. The gate is documented in
  `native_test.c:2273` (`// genuine timeout control (silent responder) — gated`) and
  `NativeConnectionTest.kt:585-589` ("NOT executed in this phase — physical execution requires
  separate approval"). Evidence: SOURCE CODE + PRIOR REPORT.
- **T5-SLOT instrumentation landed (commit `5e51e37`).** A **logging-only** change to
  `AutoModeController.kt`: one import (`vn.unlimit.softether.NativeLifetimeRegistry`) plus two
  `adapter.log(...)` string interpolations of `NativeLifetimeRegistry.process.describe()` — at the
  retry site (`Native slot busy; retrying …`) and the terminal-refusal site (`Native slot still owned
  by an unresolved operation …`). No control flow, no state transition, no error-code change.
  Evidence: SOURCE CODE.
- **T5-SLOT was NOT reproduced in the Stage B run; holder identity remains UNKNOWN.** The single
  permitted reproduction run produced a clean, natively-confirmed teardown before the next connect
  (`Native cleanup confirmed (attempt=1, session=1)`), so **0** occurrences of `"Native slot busy;
  retrying"` and **0** of `"Native slot still owned"` fired. The instrumentation is installed on the
  device and will capture the holder's `attempt=<id> phase=<PHASE>` **whenever** the contention next
  occurs. The sole known occurrence remains the 2026-10-13 RESUME T5 window (§12.4).
  Evidence: PHYSICAL DEVICE + PRIOR REPORT; holder identity = UNKNOWN.
- **`test40` budget widened to 90 s** (commit `38d9d33`; the post-release wait loop 5 s → 90 s). The
  re-run **PASSES** — 68.5 s standalone, 51.8 s in suite. (Note: the `44.2 s` figure sometimes quoted
  alongside this item is the `test30B3sCaseC5` runtime, not `test40`.) Evidence: SOURCE CODE +
  PHYSICAL DEVICE.
- **All 17 local commits were pushed to `origin/main` on 2026-10-13** (clean fast-forward
  `5c13603..5e51e37`). No force, no tag. Evidence: SOURCE CODE (`git`).
- **Pre-existing local annotated tag `p2.4.10`** (tagger date 2026-09-28) points at `0714628`. It was
  **not** pushed. Its fate is an **open decision** for a future phase (push it or delete it).
  Evidence: SOURCE CODE (`git for-each-ref refs/tags`).
- **Methodology lessons:** No new methodology lessons in v1.0.8.

---

## 2. Architecture Overview

### 2.1 Layering

The application is organized into three logical layers:

1. **Presentation layer** (`app/ui/`) — Jetpack Compose screens (Auto Mode, server list, connection status, settings).
2. **Domain / orchestrator layer** (`app/automode/`, `app/repository/`, `app/data/`) — Auto Mode orchestration engine, server collection, merge/validate/score pipeline, reachability probing, and the Room database.
3. **Platform / protocol layer** (`SoftEtherClient/`) — The native SoftEther VPN protocol implementation (C/C++) wrapped by a Kotlin JNI bridge (`SoftEtherClient.kt`), and the Android `VpnService` subclass (`SoftEtherVpnService.kt`) that runs the tunnel.

### 2.2 Key Components

| Component | Module | File | Role |
|-----------|--------|------|------|
| `SoftEtherVpnService` | SoftEtherClient | `SoftEtherVpnService.kt` | Android `VpnService` subclass; owns the service lifecycle, TUN interface, start gate, teardown completion |
| `ConnectionController` | SoftEtherClient | `ConnectionController.kt` | Per-attempt controller; manages native connect/disconnect/destroy, reconnection, statistics |
| `SoftEtherClient` | SoftEtherClient | `client/SoftEtherClient.kt` | Kotlin JNI bridge wrapper for native SoftEther functions |
| `NativeLifetimeRegistry` | SoftEtherClient | `NativeLifetimeRegistry.kt` | Process-wide fail-closed native-operation admission ledger |
| `NativeAttemptCoordinator` | SoftEtherClient | `controller/NativeAttemptCoordinator.kt` | Single-owner coordination of one attempt's native connection lifecycle |
| `SoftEtherSessionPolicy` | SoftEtherClient | `SoftEtherSessionPolicy.kt` | Lifecycle policy (start/refuse/teardown decisions); testable without Android |
| `TunnelStateWatcher` | app | `automode/TunnelStateWatcher.kt` | Process-global tunnel state observation, waiter registry, NATIVE_BUSY delivery |
| `AndroidConnectionAdapter` | app | `automode/AndroidConnectionAdapter.kt` | Bridge between Auto Mode controller and `SoftEtherVpnService`; arms/races tunnel watchers |
| `AutoModeController` | app | `automode/AutoModeController.kt` | Auto Mode orchestration core; candidate ordering, one-attempt-per-server, verified-tunnel wait |
| `AutoModeEngine` | app | `automode/AutoModeEngine.kt` | App-scoped Auto Mode engine; binds policy, provider, and adapter |
| `VpnServerRepository` | app | `repository/VpnServerRepository.kt` | Multi-source server collection (HTML + API + mirrors + snapshots + seed) |
| `VpnServerMerger` | app | `merger/VpnServerMerger.kt` | Conflict-aware multi-source merge; port of the Python oracle |
| `ServerQualityCalculator` | app | `ranking/ServerQualityCalculator.kt` | Quality scoring; port of the Python oracle |
| `VPNGateItem` | app | `models/VPNGateItem.kt` | Room entity for persisted server records |
| `VPNGateConnection` | app | `models/VPNGateConnection.kt` | Parcelable connection config (legacy UI shape); converted from `VpnServerRecord` |

### 2.3 Protocol Support Matrix

| Protocol | Transport | Free Server | Paid Server |
|----------|-----------|:-----------:|:-----------:|
| SoftEther VPN | TCP | ✓ | ✓ |
| SoftEther VPN | UDP | ✓ | ✓ |
| OpenVPN | TCP | ✓ | ✓ |
| OpenVPN | UDP | ✓ | ✓ |
| MS-SSTP | TCP | ✓ | ✓ |
| L2TP/IPsec | — | ✓ ⚠️ | ✓ ⚠️ |

> ⚠️ L2TP/IPsec was deprecated by Android in Android 12 (API 31) and fully removed in Android 13 (API 33). Only works on Android 12 and below.

### 2.4 SoftEther VPN Details

- **Transport:** SSL/TLS over TCP and UDP (RUDP V1 + V2)
- **UDP V2:** Uses ChaCha20-Poly1305 AEAD encryption with automatic fallback to V1 on unsupported servers
- **Authentication:** Anonymous (free servers), Hashed Password (free servers), Plain Password / RADIUS (paid servers)

> **Note — Dynamic server-source access:** The product intent for dynamic server-source and network-path evaluation is specified in §11.8. In brief, the application must not depend on a single fixed network path for server-list retrieval; see §11.8 for the full requirement.

---

## 3. F.28 Lifecycle History

The F.28 family is a sequence of defect investigations and fixes surrounding the SoftEther native connection lifecycle, teardown completion, and Auto Mode server rotation. Each stage is tagged in source comments as `Stage 2F-F.28X` or `Stage 2F-F.28XY`. This document records the history through the current lifecycle baseline (F.28AD-B).

### 3.1 Summary of F.28 Stages

| Stage | Focus | Key Finding |
|-------|-------|-------------|
| F.28A | Start-guard stale-state trap | `if (isRunning) return` refused all connects after a self-disconnect; `isRunning` never cleared by `DISCONNECTED` |
| F.28B | State-delivery suppression | `isStopping` latch never cleared; discarded `CONNECTED`, breaking tunnel observation |
| F.28C | Obsolete session callbacks | No session identity; obsolete controller callbacks tore down live replacement sessions |
| F.28D | Re-entrant teardown | `stopVpn` re-entered mid-teardown; no-op path needed |
| F.28E | Running flag on obsolete session | `onRunning()` fired for replaced sessions |
| F.28F | Service destroy cleanup | Blocking native calls on main thread caused ANR; moved to `Dispatchers.IO` |
| F.28G | Teardown signal conflation | `DISCONNECTED`/`ERROR` published when teardown *started*, not when it *finished* |
| F.28H | Obsolete controller cancellation | Cancellation path destroyed replacement session's controller |
| F.28I | TUN ownership races | TUN interface could be installed by an obsolete session |
| F.28J | TUN installation identity | TUN install must be claimed against the session that owns it |
| F.28K | Resource descriptor races | TUN descriptor appeared between gate check and claim |
| F.28L | Lease release timing | Lease must not be released while a live native handle exists |
| F.28M | Logging on torn-down session | "VPN connection established" logged for sessions already stopped |
| F.28N | Logging suppression | Logging must check both identity and `isStopping` |
| F.28O | Attempt-scoped teardown | Re-entrant `stopVpn` calls need distinct identities |
| F.28P | Native handle read-then-zero | Two destroyers could act on the same handle |
| F.28Q | Destroy timeout vs. release | Must not release the process-wide slot before confirming native cleanup |
| F.28R | Background thread join | `softether_destroy` had `additional_connecting` background thread path |
| F.28S | Zombie connect | Cancelled connect still reaches `nativeCreate` because no cancellation channel |
| F.28T | Re-attempt admission | Replacement attempt could reach native while previous native call was still running |
| F.28U | Two connection structs | Traced root cause: Kotlin start gate reads "idle" because teardown is async |
| F.28V | Native-busy propagation | `notifyNativeBusy` made a silent return; Auto Mode couldn't distinguish refusal from server failure |
| F.28W | Process-wide native admission | **Completed.** `NativeLifetimeRegistry` closes the F.28U gap |
| F.28X | Slot release timing | Proved `nativeDestroy` returning is not evidence the connect flow exited |
| F.28Y | Teardown confirmation deadline | **Completed.** `NATIVE_CLEANUP_CONFIRM_TIMEOUT_MS = 2_000L` bounds teardown wait |
| F.28Z | Teardown signal honesty | **Completed.** `TeardownCompletion` makes outcomes distinguishable and identity-scoped |
| F.28AA | Observable native-busy refusal | **Completed.** Refusal is now observable to Auto Mode, scoped to session generation |
| F.28AB | Native handle claim distinction | `NativeHandleClaim` distinguishes "no handle" from "handle taken, destroy failed" |
| F.28AC | Lease without attempt ID | `confirmTeardownWithoutLease()` for controllers destroyed before native code ran |
| F.28AD-A | (In progress) | — |
| F.28AD-B | Current baseline | The current, stable lifecycle state with all above stages applied |
| F.28AL-S/T | Native-handle ownership audit (reconnect vs coordinator) | Source-confirmed two competing read-then-zero cleanup paths (inline reconnect + `runCleanup()`/`claimHandle()`); fail-closed lease retention preserved |
| F.28AL-U | **Single-owner native handle** | `NativeHandleSlot` introduced; all inline reconnect teardowns routed through one atomic `claimHandle()` claim + `destroyClaimedNativeHandle()`. Working-tree implementation; JVM-tested (8 slot tests, 129 SoftEtherClient, 583 app). **Not yet physically verified** (see §3.3, §12.2) |
| F.28AL-V | Physical-verification attempt (device audit) | Installed APK (`lastUpdateTime` 2026-10-05) predates F.28AL-U — dex dump showed **0** occurrences of `NativeHandleSlot`/`NativeHandleOwner`/`NativeHandleClaim`/`NativeAttemptCoordinator`/`NativeLifetimeRegistry`; `ConnectionController`/`TunOwnershipCoordinator` present. Invariant reported **NOT OBSERVABLE** |
| F.28AL-W | Install-and-verify attempt | Stopped at the release-signing gate: `keyStore/release.jks` absent (only CI-encrypted `release.jks.gpg` present), no resolvable release signing password → Gradle would fall back to debug signing (certificate-incompatible with installed package). Reported **RELEASE_SIGNING_UNAVAILABLE**; no build/install/device test performed |
| F.28AL-Y-PREP | Debug APK build + signature comparison | Built proDebug APK from current working tree (`app/build/outputs/apk/pro/debug/app-pro-debug.apk`, SHA-256 `CB0764664B7104F1C22A7DC3A057535468CEA3437F16401C97281A9490E0DEC7`); DEX-verified all five F.28AL-U classes present (each exactly once). Signature comparison `SIGNATURE_COMPATIBLE` → material correction: installed package 2.4.10/143 is **DEBUG-SIGNED** (cert SHA-256 `247423ce…39e9a` == local debug keystore), RISK-A observed in practice. No install performed. Evidence: SOURCE + PHYSICAL DEVICE EVIDENCE |
| F.28AL-Y-FULL | Install-and-verify F.28AL-U on installed debug APK | In-place `adb install -r` on `5585fecc` succeeded (no uninstall, no data clear); post-install `versionName=2.4.10-dev` (`-dev` confirms debug build); installed APK SHA-256 == built APK → F.28AL-V `NOT OBSERVABLE` gap closed. Test A (connect/disconnect) PASS, Test B (reconnect) PASS (ownership serialization captured in A, not B), Test C observed organically PASS, Test D NOT REPRODUCED; zero crashes. Natural dual-path overlap during Test A disconnect: two cleanup paths fired, `nativeDestroy` called **exactly once**, competitor serialized `NOOP_REENTRANT` (ownership retained, new native attempt refused), cleanup confirmed and slot released. **Debug-build evidence only; F.28AL-U remains OPEN.** Evidence: PHYSICAL DEVICE EVIDENCE |
| FIX-NATIVE-P0-BOUNDS | JNI bounds + RUDP lock-release hardening (N-01…N-05, N-08) | Six native entry points now validate array lengths before use and `rudp_recv` releases its lock on the small-buffer path. Verdict: **APPLIED — JVM + native tests green.** Evidence: SOURCE + TEST. Ref: `FIX_NATIVE_P0_BOUNDS_REPORT.md` |
| FIX-KOTLIN-BRIDGE-DECLARATIONS | Six native bridges re-declared as instance-level `external fun` | All six JNI exports take `jobject thiz`, so the Kotlin declarations were moved out of `companion object`. Verdict: **APPLIED — bridges resolve at runtime.** Evidence: SOURCE + TEST. Ref: `FIX_KOTLIN_BRIDGE_DECLARATIONS_REPORT.md` |
| FIX-G1-IMPL | Eliminate the stale-service leak (G-1) | A superseded session could leave the service in a state where the next AutoConnect was refused as "a connected session already owns the service". Verdict: **APPLIED — confirmed 0 occurrences across 3 device AutoConnect cycles.** Evidence: PHYSICAL DEVICE EVIDENCE. Ref: `FIX_G1_IMPL_REPORT.md` |
| FIX-LOG-IDENTITY | Teardown logs carry attempt/session identity (G-2) | Teardown completion log lines now emit `attempt=<n>` and `session=<n>`. Verdict: **APPLIED — 3 device teardowns all emitted `(attempt=N, session=1)` with no `attempt=none` fallback.** Evidence: PHYSICAL DEVICE EVIDENCE. Ref: `FIX_LOG_IDENTITY_REPORT.md` |
| FIX-N05-TEST-DEREFERENCE | Repair the unsound `test35` dummy-handle contradiction | `test35` passed a valid 6-byte array with dummy handle `0x1`, bypassing the length guard and forcing `softether_set_client_mac()` to dereference an invalid pointer (SIGSEGV). The test was narrowed to its actual rejection contract. Verdict: **FIX APPLIED — test35 converted CRASH → PASS on device.** Evidence: SOURCE + PHYSICAL DEVICE EVIDENCE. Ref: `FIX_N05_TEST_DEREFERENCE_REPORT.md` |
| BATCH-DEVICE-VERIFY | First full device pass of the accumulated fixes | 5/6 native tests PASSED; `test35` **CRASHED** (SIGSEGV, `fault addr 0x4bd`, backtrace through `memcpy` ← `softether_set_client_mac`). Diagnosed as a **test-logic** defect, not a product defect. Verdict: **BATCH VERIFY FAIL — SPECIFIC TEST FAILED / CRASH_OBSERVED.** Evidence: PHYSICAL DEVICE EVIDENCE. Ref: `BATCH_DEVICE_VERIFY_REPORT.md` |
| RESUME-BATCH-DEVICE-VERIFY | Resumed device pass after the `test35` repair | 6/6 native tests **PASS** (`OK (1 test)` each; crash buffer empty); T2 (G-1) 0 stale-service refusals over 3 AutoConnect cycles; T3 (G-2) 3 teardowns with correct identity; T4 BUG 1 82/103 reachable, reproducible; T5 BUG 2 skip path fires exactly once; T6 BUG 3 `nativeSetOption value=5000` on every attempt; T7 zero crashes (app PID stable); T8 settings unchanged. Verdict: **RESUME VERIFY PASS — TEST35 + T2-T8 ALL CLEAN.** Surfaced FINDING T5-SLOT (§12.4). Evidence: PHYSICAL DEVICE EVIDENCE. Ref: `RESUME_BATCH_DEVICE_VERIFY_REPORT.md` |
| FIX-U36-KSP-ALIGN | Align the KSP plugin version with Kotlin | KSP `2.3.12` pinned to match Kotlin `2.4.0`, removing the version-skew failure that blocked the U-36 build verification. Verdict: **APPLIED.** Evidence: SOURCE. Ref: `FIX_U36_KSP_ALIGN_REPORT.md` |
| FIX-U36-FULL-BUILD-VERIFY | Full forced build verification after the KSP alignment | Full forced toolchain run confirms the tree compiles end to end. Verdict: **VERIFIED.** Evidence: SOURCE + BUILD. Ref: `FIX_U36_FULL_BUILD_VERIFY_REPORT.md` |
| COMMIT-BATCH-F28AL-AUDIT | Pre-commit dependency-closure audit | Found the commit-2 defect: `ConnectionController` imported F.28AL-U classes that had never been committed. Verdict: **DEFECT FOUND — repaired by `ab82ce1`.** Evidence: SOURCE. Ref: `COMMIT_BATCH_F28AL_AUDIT_REPORT.md` |
| COMMIT-BATCH-F28AL-REPAIR | Commit the missing F.28AL-U classes | Committed the omitted `NativeHandleSlot` / `NativeHandleOwner` / `NativeHandleClaim` classes so the import closure resolves. Verdict: **REPAIRED.** Evidence: SOURCE. Ref: `COMMIT_BATCH_F28AL_REPAIR_REPORT.md` |
| COMMIT-BATCH-F28AL-GROUP2 | Second commit group verification | Verdict: **VERIFIED**, with two methodology lessons recorded (forced cache-disabled tests; KDoc references are not dependencies). Evidence: SOURCE + TEST. Ref: `COMMIT_BATCH_F28AL_GROUP2_REPORT.md` |
| COMMIT-BATCH-F28AL-SINGLE-STACK | Single-stack commit and verification | Verdict: **COMMITTED — COMPILE VERIFIED — TESTS VERIFIED.** Evidence: SOURCE + TEST. Ref: `COMMIT_BATCH_F28AL_SINGLE_STACK_REPORT.md` |
| VERIFY-STACK-CLOSURE | Whole-tree forced compile closure | 40/40 tasks executed under `--rerun-tasks`, proving the tree compiles. Evidence: BUILD. Ref: `VERIFY_STACK_CLOSURE_REPORT.md` |

### 3.2 F.28 Defect Chain — Narrative

The F.28 family traces a chain of interlocking defects in the native connection lifecycle:

1. **Stale state (F.28A):** The old `if (isRunning) return` guard refused every connect after a self-disconnect because `ConnectionController` could emit `DISCONNECTED` on its own (keepalive/reconnect/destroy), clearing only `wasConnected` while never running `stopVpn()`.

2. **Suppressed state delivery (F.28B):** The `isStopping` latch was written once and never cleared, so it was inherited across sessions. State delivery was suppressed unconditionally, discarding `CONNECTED` signals that `TunnelStateWatcher` and Auto Mode need to observe the tunnel.

3. **Obsolete callbacks (F.28C):** Without session identity, a `ConnectionController` from session N could publish into session N+1. `onError` (destructive) tore down live replacement sessions.

4. **Native race (F.28U → F.28W):** The service's start gate used three Kotlin references (`controller`, `tunOwnership`, `connectionJob`), all cleared synchronously by teardown, while native cleanup was dispatched asynchronously. A replacement attempt could pass the gate and reach `nativeCreate()` while the previous session's `nativeConnectWithHub` was still blocked in JNI — two live `softether_connection_t` structs.

5. **Uncancelable native calls (F.28S, F.28X):** `nativeConnectWithHub` and `nativeDestroy` have no cancellation channel from Kotlin. `connect_mutex` is per-connection and wraps only the connect call. `nativeDestroy` returning is not evidence the connect flow exited.

6. **Silent refusal (F.28V → F.28AA):** When the process-wide native slot was held, the start gate silently returned, so `AndroidConnectionAdapter.connect()` looked like it had dispatched a connection that simply never came up. Auto Mode burned the full per-attempt timeout, declared the server unreachable, and rotated — potentially through the entire server list, ending in `ERROR_NO_SERVER` — while the only problem was the previous native operation finishing.

### 3.3 F.28AL Native-Handle Single-Owner — Status and Verification Blocker

> Evidence status legend: **[SOURCE CODE EVIDENCE]**, **[TEST EVIDENCE]**, **[PHYSICAL DEVICE EVIDENCE]**, **[INFERENCE]**. These categories are not interchangeable — see §20 Legend. Facts below are attributed to the phase that produced them and are not re-verified in this document.

**F.28AL-U implementation (present in working tree) — [SOURCE CODE EVIDENCE], reported by F.28AL-U/W:**

- New file `SoftEtherClient/src/main/java/vn/unlimit/softether/controller/NativeHandleSlot.kt` coordinates native-handle ownership, in-flight work, and terminal/lost state.
- `ConnectionController.kt` routes every inline reconnect teardown through one atomic `NativeHandleSlot.claimHandle()` claim plus shared `claimAndDestroyNativeHandle()` / `destroyClaimedNativeHandle()` methods, so the inline reconnect cleanup and the coordinator's `runCleanup()` cleanup both claim through the same slot. Competing claims wait for a bounded period of **500 ms** and fail closed (report `Lost`) if ownership remains uncertain; a lost/unresolved handle retains the process-wide lease (no force-release).
- Reported automated results (recorded as reported, **not** rerun here): 8 `NativeHandleSingleOwnerTest` tests, 129 passing `SoftEtherClient` unit tests, 583 passing `app` (Pro) unit tests with 1 pre-existing skip.

**F.28AL-V physical-verification result — [PHYSICAL DEVICE EVIDENCE], reported by F.28AL-V:**

- The installed APK (package `vn.unlimit.vpngatepro`, v2.4.10/143, `lastUpdateTime` 2026-10-05 07:40) **did not contain** the F.28AL-U classes — authoritative dex dump showed 0 occurrences of all five F.28AL-U classes while `ConnectionController` and `TunOwnershipCoordinator` (public, R8-kept) were present. The physical behavior of F.28AL-U was therefore **NOT OBSERVABLE** in F.28AL-V. No behavioral claim was made.

**F.28AL-W install-and-verify result — [INFERENCE] from [SOURCE CODE EVIDENCE] + [FILESYSTEM EVIDENCE], reported by F.28AL-W:**

- **Final status: `RELEASE_SIGNING_UNAVAILABLE`.** The distribution release keystore is CI-only: `keyStore/release.jks` is absent (only the encrypted `release.jks.gpg` is present), and no release signing password was resolvable (`local.properties` key absent, `SIGN_KEY_PASSWORD` env unset). Under those conditions the Gradle `pro` release signing config falls through to the **debug** signing branch (see §15.4), producing an APK whose certificate would not match the installed release-signed package.
- Consequently F.28AL-W **stopped before building, installing, or any further device interaction**. **No APK was produced; physical tests A/B/C/D were not performed.** The installed binary remains the pre-F.28AL-U build.
- **Key distinction:** the F.28AL-U implementation is reported **present in the working tree**, but its **physical verification was not completed** in F.28AL-W. F.28AL-U had **not** passed physical-device verification at the F.28AL-W point.

**F.28AL-Y physical verification (debug build) — [PHYSICAL DEVICE EVIDENCE], reported by F.28AL-Y-PREP / F.28AL-Y-FULL:**

- F.28AL-Y-PREP built a `proDebug` APK from the current working tree and DEX-verified that all five F.28AL-U classes (`NativeHandleSlot`, `NativeHandleOwner`, `NativeHandleClaim`, `NativeAttemptCoordinator`, `NativeLifetimeRegistry`) are present (each exactly once). The signature comparison returned `SIGNATURE_COMPATIBLE`: the certificate SHA-256 of both the built APK and the installed package is `247423ce8d62f0dfe15938f10e80798bd328857a50af0883e3a800dc10319e9a`, proving the installed 2.4.10/143 package was **debug-signed**, not release-signed (see RISK-A, §12.4).
- F.28AL-Y-FULL installed the built APK in place (`adb install -r`) on `5585fecc` — no uninstall, no data clear. Post-install `versionName=2.4.10-dev` (the `-dev` suffix confirms a debug build). The installed APK SHA-256 (`CB0764664B7104F1C22A7DC3A057535468CEA3437F16401C97281A9490E0DEC7`) equals the built APK, so the installed binary now provably contains the F.28AL-U classes — this **closes the F.28AL-V `NOT OBSERVABLE` gap**.
- Test results: **Test A** (normal connect/disconnect) **PASS**; **Test B** (reconnect) **PASS** (caveat: the teardown-ownership serialization was captured in Test A, not independently re-captured in the Test B window); **Test C** (concurrent lifecycle race) **PASS — observed organically**, with no synthetic injection; **Test D** (cleanup failure) **NOT REPRODUCED**. Crash scan: **0 crashes** across all captured logcat windows (the `AndroidRuntime=10` count in an earlier summary was identified as false-positive substring noise, not a real crash).
- **Single-owner invariant observed under a natural dual-path overlap (Test A disconnect):** two independent cleanup paths fired (service `DISCONNECT` intent + inline fast-cleanup); `nativeDestroy` was invoked **exactly once**; the competing path was serialized as `NOOP_REENTRANT` with ownership retained and a new native attempt refused; cleanup was then confirmed and the slot released.

**F.28AL-U status — OPEN (three-of-four evidence layers satisfied):**

- **SOURCE CODE EVIDENCE:** implementation present and unchanged — **satisfied**.
- **TEST EVIDENCE (JVM):** 8 `NativeHandleSingleOwnerTest` tests + 129 `SoftEtherClient` unit tests + 583 `app` (Pro) unit tests with 1 pre-existing skip, as previously reported (not rerun in this documentation phase) — **satisfied**.
- **PHYSICAL DEVICE EVIDENCE (debug build):** single-owner invariant observed in a natural dual-path overlap at F.28AL-Y-FULL — **satisfied (debug build only)**.
- **PHYSICAL DEVICE EVIDENCE (release build):** **NOT YET OBTAINED.** F.28AL-U remains **OPEN** pending release-signed (CI) verification.

**Debug-build caveat (F.28AL-Y-FULL):** the physical evidence above is from a **debug build** (R8/minify disabled). Debug thread scheduling and inlining may differ from release. This does **not** constitute release-level proof of the F.28AL-U invariant. F.28AL-U must **not** be marked closed on this evidence alone.

**Next-step dependency (requires explicit user selection — not authorized by any documentation-only phase):**

1. **CI-based verification** — build + install + run tests A–D on the CI runner where the distribution signing key is available; or
2. **Authorized debug verification** — explicitly authorize a debug-signed `proRelease`/`proDebug` build + install on the authorized device, accepting the stated caveat that debug R8/minify behavior and observability may differ from the release build.

**Legacy cleanup side finding (reported; OUT OF SCOPE):** `SoftEtherClient.disconnect()` (`SoftEtherClient.kt:46–56`) contains a raw `nativeDisconnect` → `nativeDestroy` → `nativeHandle = 0` sequence that bypasses `NativeHandleSlot`. The prior audit reportedly found **no production caller** (all `.disconnect()` call sites resolve to `AndroidConnectionAdapter.disconnect()` → service `ACTION_DISCONNECT`), so it is treated as a **dead/legacy method, not a proven active bypass**. F.28AL-W did not perform its own dedicated sweep. This is recorded as a finding for a separate read-only audit / future hardening phase and is **not** an active single-owner bypass on current wiring.

**Open questions (not resolved by passing JVM tests):** native `softether_destroy()` contract and failure behavior; whether a failed free surfaces as a JVM exception; real ART scheduling/interleavings; recovery from a permanently unresolved native-handle ownership state; complete Kotlin → Service → TUN → teardown/attempt-ID propagation evidence; state publication after a rejected/stale lease.

---

## 4. Current Native Lifetime Model (F.28AD-B)

### 4.1 Overview

The current model uses three cooperating components to enforce process-wide native-operation safety and single-owner native-handle cleanup:

| Component | Scope | Responsibility |
|-----------|-------|----------------|
| `NativeLifetimeRegistry` | Process-wide | Fail-closed admission: a new native operation is only admitted when no previous operation is unresolved. |
| `NativeAttemptCoordinator` | Per-attempt | Single-owner coordination of one attempt's connect/destroy lifecycle. Ensures exactly one owner for cleanup. |
| `NativeHandleSlot` (F.28AL-U) | Per-attempt | Atomic single-owner claim of the native handle: coordinates ownership transfer, in-flight destroy, and terminal/lost state. Every application-managed cleanup path (inline reconnect teardown + coordinator `runCleanup()`) claims through it, so a given handle is destroyed at most once and an unproven cleanup fails closed. Status: working-tree implementation, **not yet physically verified** (see §3.3). |

### 4.2 NativeLifetimeRegistry

**File:** `SoftEtherClient/src/main/java/vn/unlimit/softether/NativeLifetimeRegistry.kt`

**Purpose:** A Kotlin-side ledger that provides process-wide, fail-closed admission for native SoftEther operations. It exists because the service's start gate (based on three Kotlin references) is blind to the async boundary between logical session teardown and physical native cleanup.

**Key properties:**
- **Fail-closed:** When a native operation cannot be shown to have finished, admission is denied. A timeout is a *reporting* deadline only — it never releases ownership.
- **Not a cancellation mechanism:** There is no cancellation channel to native code (`connect_mutex` is per-connection; `cancel_flag` in `softether_protocol.c` is function-local and unreachable from JNI). `tryAcquire()` never blocks and never queues.
- **SoftEther-only:** OpenVPN and SSTP have their own lifecycles and do not consult this class. They are intentionally excluded; unifying them is a separate future change.
- **All state guarded by a single `lock`:** No method holds it across a blocking native call. Reservation is a single short `synchronized` block, making admission atomic with respect to concurrent connect requests.

**Phases (enum `Phase`):**
| Phase | Meaning |
|-------|---------|
| `RESERVED` | Admitted. No native call has been entered yet for this attempt. |
| `CONNECTING` | A native call is executing, or was entered and may still be executing. |
| `CONNECTED` | The connection is established and this attempt owns a live native handle. |
| `TEARDOWN` | Teardown was requested. Native cleanup is executing or blocked. Ownership is retained until `confirmTeardown` succeeds. |

**Key methods:**
| Method | Behavior |
|--------|----------|
| `tryAcquire()` | Claims the exclusive native-operation slot. Returns a `Lease` on success, or `null` when a previous attempt's native operation is unresolved. |
| `markNativeEntered(lease)` | Transitions to `CONNECTING`. No-op if lease is obsolete. |
| `markConnected(lease)` | Transitions to `CONNECTED`. |
| `markTeardownRequested(lease)` | Transitions to `TEARDOWN`. Ownership is retained. |
| `markDeadlineExpired(lease)` | Marks the advisory reporting deadline as expired. Does NOT release ownership. |
| `confirmTeardown(lease)` | Releases ownership, but only for the exact lease that owns the slot. One-shot: second call returns `false`. |
| `confirmTeardownWithoutLease()` | Releases ownership without a lease — for controllers destroyed before native code ran. |
| `isAdmissible()` | True when no native operation is unresolved. |
| `isBlocked()` | True when a reporting deadline expired and the attempt is still unresolved. |
| `describe()` | Human-readable summary for logs and refusal messages. |

**Identity model:**
- `lastAttemptId`: Monotonic, process-wide counter. IDs are never reused.
- `activeAttemptId`: The ID of the owning attempt, or `NO_ATTEMPT` (0L) when idle.
- `Lease`: An opaque token issued by `tryAcquire()` carrying an `attemptId`; all mutating calls are scoped to this identity so an obsolete attempt's completion cannot alter or release a newer attempt's reservation.

**Constants:**
- `NO_ATTEMPT = 0L` — Sentinel for "no attempt owns the native slot."

### 4.3 NativeAttemptCoordinator

**File:** `SoftEtherClient/src/main/java/vn/unlimit/softether/controller/NativeAttemptCoordinator.kt`

**Purpose:** Single-owner coordination of one attempt's native connection lifecycle. Ensures exactly one flow performs cleanup (destroy + lease release), and that cleanup happens only after genuine native completion is observed.

**Protocol:**
1. **One `Flow` per native connect attempt.** Registered under `lock` *before* `nativeCreate`, so there is no instant where a flow is running but unregistered. It publishes its handle as soon as `nativeCreate` returns, and counts down its exit latch as the very last action — after every native call it makes.
2. **Teardown never frees.** `requestTeardown` sets the terminal `isDestroyed` flag and snapshots the active flow under `lock`, then interrupts the flow's socket (`shutdown`, which never frees) and waits, bounded by `NATIVE_TEARDOWN_TIMEOUT_MS = 500L`, for the flow to exit. On timeout, it returns `TeardownOutcome.UNRESOLVED` having neither destroyed nor released.
3. **Exactly one cleanup owner.** Claimed with a compare-and-set under `lock`, by whichever arrives first: the exiting flow (when teardown is already pending) or teardown (when the flow has already exited). Everyone else returns without touching the handle or the lease.
4. **Destroy, then release.** The owner read-zeros the handle through `NativeHandleOwner` and only then releases the lease — and only if the destroy did not throw. A throwing destroy leaves ownership held.

**NativeHandleClaim (F.28AC):** Distinguishes three states where a bare `Long` handle used to conflate two:
| Claim | Meaning |
|-------|---------|
| `NativeHandleClaim.Claimed(handle)` | A handle was taken and the destroy has not yet run. |
| `NativeHandleClaim.Lost` | A handle was taken, but it was never confirmed destroyed. |
| `NativeHandleClaim.NothingOutstanding` | No handle was ever created for this attempt. |

**Constants:**
- `NATIVE_TEARDOWN_TIMEOUT_MS = 500L` — advisory wait for the connect flow to exit before declaring teardown unresolved.

### 4.4 Native Code Contract

**File:** `SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_protocol.c`

**Key functions and their roles:**
- `softether_connect_with_hub()` — The primary connect path. Wrapped by the per-connection `connect_mutex` (see lines 139–145 of `softether_jni.c`). Blocking in JNI; not cancellable from Kotlin.
- `softether_disconnect()` — Called inline by `softether_destroy()` (line ~1050). Does NOT take `connect_mutex`. Cleans up the SSL context, sockets, and NAT-T transport.
- `softether_force_close_socket()` (line 1098) — Interruption path. Calls `shutdown(fd, SHUT_RDWR)` on the primary TCP socket to interrupt blocking `SSL_read`/`SSL_write` in `softether_connect_with_hub`. Critically, it does **not** close the fd or clear `conn->socket_fd` — the connect thread still holds the SSL context referencing that fd, and closing it would create a stale-fd dereference window. The fd is closed properly by `softether_disconnect()` later.
- `softether_destroy()` — Destroys the connection structure. If `additional_connecting` background thread is present, joins it (line 1040) before freeing.

**Thread-safety in native code:**
- `connect_mutex` is per-connection (`softether_connection_t`) and wraps only `softether_connect_with_hub`. It serializes nothing across connections.
- `softether_destroy` does NOT acquire `connect_mutex`, so it cannot wait for a blocked connect to release it.
- The only thread that can *know* the connect has exited is the connect flow itself — which is why `NativeAttemptCoordinator` exists to track flow lifetime and hand cleanup to the exiting flow.

### 4.5 ConnectionController Integration

**File:** `SoftEtherClient/src/main/java/vn/unlimit/softether/controller/ConnectionController.kt`

The `ConnectionController` is the per-attempt orchestrator that bridges Kotlin and native:

```
ConnectionController construction
    │
    ├── receives nativeLease from SoftEtherVpnService
    │
    ├── construct NativeAttemptCoordinator(
            handles = NativeHandleOwner {  // read-then-zero handle idion
                val handle = nativeHandle
                nativeHandle = 0
                protectedFds.clear()
                // F.28AC: distinguish "no handle" from "handle taken, destroy failed"
                when {
                    handle != 0L -> NativeHandleClaim.Claimed(handle)
                    nativeHandleLost -> NativeHandleClaim.Lost
                    else -> NativeHandleClaim.NothingOutstanding
                }
            },
            releaseLease = {  // idempotent; calls NativeLifetimeRegistry.process.confirmTeardown
                if (NativeLifetimeRegistry.process.confirmTeardown(nativeLease)) {
                    Log.d(TAG, "Released process-wide native slot for this attempt")
                }
            },
            interruptNative = { handle -> client.nativeForceCloseSocket(handle) },  // shutdown()
            destroyNative = { handle -> client.nativeDestroy(handle) },
            onUnresolved = { reason -> Log.w(TAG, "Native lifecycle unresolved: $reason") },
    )
    │
    ├── performConnect()
    │       ├── connectInFlight.set(true)
    │       ├── nativeCreate()
    │       ├── markNativeEntered(lease)  →  Phase.CONNECTING
    │       ├── nativeConnectWithHub()  // blocking JNI, NOT cancellable
    │       ├── isCancelled check after return
    │       └── markConnected(lease)  →  Phase.CONNECTED
    │
    └── destroyResources()
            ├── nativeLifecycle.requestTeardown()  // set destroyed flag, snapshot flow, interrupt socket
            ├── wait NATIVE_TEARDOWN_TIMEOUT_MS (500ms) for flow to exit
            ├── if flow exited: owner runs cleanup (destroy + release lease)
            ├── if timeout: returns UNRESOLVED, ownership passes to connect flow
            └── runCleanup() based on NativeHandleClaim:
                    Claimed → destroy + release lease
                    Lost → release lease (without destroy)
                    NothingOutstanding → release lease (without destroy)
```

**Key fields:**
- `connectInFlight` — `AtomicBoolean`; true while a connect flow owns the native handle. Deliberately NOT derived from `currentState` because `disconnect()` resets state to `DISCONNECTED` immediately, but the blocked `nativeConnectWithHub` may still hold `connect_mutex` long after.
- `isCancelled` — `AtomicBoolean`; checked only AFTER `nativeConnectWithHub` returns. Function-local in native code, unreachable from JNI.
- `nativeHandle` — `Long`; managed via read-then-zero idiom through `NativeHandleOwner`.
- `nativeHandleLost` — `Boolean` (F.28AC); true when a handle was taken but its destroy was never confirmed.

**Constants:**
- `NATIVE_TEARDOWN_TIMEOUT_MS = 500L` — advisory wait for connect flow exit during teardown.
- `MAX_RECONNECT_ATTEMPTS = 2`
- `RECONNECT_DELAY_MS = 1000L`
- `STATS_INTERVAL_MS = 1000L`
- `RX_BATCH_MAX_PACKETS = 32`

---

## 5. Teardown Completion Identity (F.28Z, F.28AA, F.28AD-B)

### 5.1 TeardownCompletion

**File:** `SoftEtherClient/src/main/java/vn/unlimit/softether/TeardownCompletion.kt`

**Purpose:** Distinguishes four distinct teardown outcomes, each with a strictly increasing process-wide `attemptId`. Replaces the old argument-less "teardown complete" callback that conflated timeout, native confirmation, and terminal state publication.

**Enum `Outcome`:**
| Outcome | Meaning | Implication |
|---------|---------|-------------|
| `NATIVE_CONFIRMED` | Native cleanup ran and finished: structure freed, lease released. | The session is gone. Safe to start a new native operation. |
| `NATIVE_UNRESOLVED` | A connect flow is still executing, or native destroy failed. **Ownership is retained.** | Every later start is refused by the registry. Must never be reported as "safe to start." |
| `NOOP_REENTRANT` | `stopVpn` was re-entered and started nothing — teardown already performed for this session. | Reported as terminal state published, but carries no native-confirmation claim. |
| `FOREIGN_STACK_TERMINAL` | A non-SoftEther stack (currently only ics-openvpn) reported a terminal state. | Asserts nothing about native cleanup. Excluded from the SoftEther attempt-ID sequence (`attemptId = 0`). |

**Identity model:**
- `attemptId`: Strictly increasing per `stopVpn` call, for the life of the process. Lives in the service's companion object for monotonic continuity across service recreation.
- `sessionId`: The session generation being torn down (diagnostics/logging only).
- `isSequenced`: `true` for all outcomes except `FOREIGN_STACK_TERMINAL`. Foreign-stack completions are filtered out of the SoftEther sequence.
- `nativeCleanupConfirmed`: `true` only for `NATIVE_CONFIRMED`. Deliberately narrow — nothing else may be read as "safe to start a new native operation."

### 5.2 Attempt ID Lifecycle

The `attemptId` is allocated in `SoftEtherVpnService.stopVpn()` (companion object counter) for every non-re-entrant teardown. On the re-entrant path (teardown already performed), the call returns `NOOP_REENTRANT` with the same `attemptId` as the original (identity is not re-advertised, it is already tracked).

**F.28AD-B enhancement:** `ACTION_DISCONNECT` intents may carry `EXTRA_TEARDOWN_ATTEMPT_ID` — an attempt ID that the caller has pre-allocated. This lets Auto Mode arm a waiter for a specific teardown *before* dispatching the disconnect, because the waiter must be armed against an identity it has received, not one allocated later inside `stopVpn`. Callers without a waiter (UI button, tracker, notification action) omit this extra and the service allocates an ID itself.

### 5.3 TunnelStateWatcher

**File:** `app/src/main/java/vn/unlimit/vpngate/automode/TunnelStateWatcher.kt`

**Role:** Process-global observer of tunnel state and the registry for teardown-completion waiters. Accepts only completions strictly newer than the last one accepted, then branches on `outcome`.

**Key behaviors:**
- Maintains a registry of waiters keyed by `attemptId`.
- On a `TeardownCompletion` arrival: if the `attemptId` is newer than the last accepted, publish to the registered waiter; if older, discard as obsolete.
- `FOREIGN_STACK_TERMINAL` results are delivered regardless of attempt ID (they are not part of the sequenced protocol and carry no attempt identity to compare).
- `onNativeBusy` callback: receives the NATIVE_BUSY refusal from `SoftEtherVpnService` (scoped to `sessionPolicy.sessionGeneration`) and signals the armed `armedNativeBusy` deferred in `AndroidConnectionAdapter`.

### 5.4 Rewind for Tests

`rewindTeardownSequenceForTests()` guards on `armedTeardown == null` only — it does not check `isStopping`, `controller`, or `connectionJob`. This is a test-only escape valve to reset the monotonic sequence between tests.

---

## 6. Auto Connect / Auto Mode Architecture

### 6.1 Overview

Auto Mode (called "Auto Connect" in the UI) is a state machine that iterates through VPN Gate server candidates, trying each for a bounded time until a verified tunnel is established.

| Component | Role |
|-----------|------|
| `AutoModeController` | Pure-JVM orchestration core: candidate ordering, one-attempt-per-server, verified-tunnel wait with timeout |
| `AutoModeEngine` | App-scoped engine binding `AutoModeController` to policy, server provider, and `AndroidConnectionAdapter` |
| `AndroidConnectionAdapter` | Bridge between Auto Mode and `SoftEtherVpnService`; arms/races tunnel watchers and native-busy deferreds |
| `TunnelStateWatcher` | Process-global tunnel state observation |
| `AutoModeProtocol` | Protocol enum (SOFTETHER_TCP, SOFTETHER_UDP, OPENVPN_TCP, OPENVPN_UDP, MS_SSTP, L2TP_IPSEC) |
| `AutoModeCandidate` | Protocol-capability + quality snapshot of one server, decoupled from Parcelable `VPNGateConnection` |

### 6.2 Auto Mode Protocol Support

`AutoModeProtocol` defines which protocols Auto Mode can drive. The default is `SOFTETHER_UDP`. Protocol support:

| Protocol | Probe Transport | Connect Port Source |
|----------|----------------|---------------------|
| SOFTETHER_TCP | TCP | Own published TCP port |
| SOFTETHER_UDP | UDP | Own published UDP endpoint |
| OPENVPN_TCP | TCP | Own published TCP port |
| OPENVPN_UDP | UDP | Own published UDP port |
| MS_SSTP | TCP | `sstpConnectPort` (EXPLICIT/DEFAULT/UNKNOWN rule) |
| L2TP_IPSEC | TCP | IANA 1701 (never auto-connected — excluded by `canAutoConnect`) |

**Endpoint resolution rule:** No port is ever fabricated and no protocol's port stands in for another's. SOFTETHER_UDP's UDP port is never borrowed from its TCP listener. MS-SSTP's `UNKNOWN` provenance yields port 0 (cannot be dialed) rather than 443.

### 6.3 Attempt Lifecycle

```
beginRun()
    for each (server, protocol) in ordered candidates:
        beginAttempt(server, protocol)
            ├── armTunnelWatch(protocol)     // arm BEFORE connect — F.28 Fix D
            ├── armNativeBusyWatch()          // arm NATIVE_BUSY deferred — F.28AA
            ├── adapter.connect(server, protocol)
            └── awaitArmedTunnel(protocol, timeoutMs)
                    ├── select { tunnel.await → true, busy.await → false }
                    └── finally: endAttempt(), clear armed state
        if tunnel verified:
            onSuccess(server, protocol); return
        if native-busy:
            // do NOT rotate — the refusal is local, not a server problem
            continue to next (server, protocol)
        else:
            // server genuinely did not come up; try next
            continue
    endRun(ERROR_NO_SERVER)
```

### 6.4 NATIVE_BUSY Handling (F.28AA)

**File:** `app/src/main/java/vn/unlimit/vpngate/automode/AndroidConnectionAdapter.kt` (lines 200–261)

When the process-wide native slot is held, `SoftEtherVpnService.startVpn` (line 601) calls `notifyNativeBusy()`, which is received by `TunnelStateWatcher.onNativeBusy`, which completes the `armedNativeBusy` deferred.

`awaitArmedTunnel` uses `kotlinx.coroutines.select` to race the tunnel deferred against the busy deferred:
- If `armedTunnel` completes first → `true` (verified tunnel)
- If `armedNativeBusy` completes first → `false` (refused, not a server failure)

The refusal is **scoped to `sessionPolicy.sessionGeneration`**, so a refusal for an obsolete session cannot disturb a newer attempt already under way. This prevents Auto Mode from rotating servers when the problem is the previous native operation still finishing.

**Constants:**
- `NATIVE_CLEANUP_CONFIRM_TIMEOUT_MS = 2_000L` (`SoftEtherVpnService.kt:112`) — how long teardown waits for native cleanup confirmation before reporting completion anyway. Longer than the controller's 500 ms teardown deadline.
- `TEARDOWN_TIMEOUT_MS = 5_000L` (`AndroidConnectionAdapter.kt:56`) — overall disconnect timeout.

### 6.5 One-Attempt-Per-Server (§10)

Auto Mode enforces that each server is tried at most once per protocol per run. If a server fails, it is not retried within the same run — the controller moves to the next candidate.

### 6.6 Active RTT Ranking (Stage 2F-F.15)

`AutoModeCandidate.activeRttMs` is the candidate's representative active round-trip time in ms, aggregated as the **minimum valid** RTT across the candidate's usable, automatable protocols. Runtime only — never persisted (no Room column, no snapshot field, no preference). A fresh process starts with `null` (honest "not measured yet," not zero).

---

### 6.7 AutoConnect Button Contract (User-Directed Requirements)

> **Scope:** This section documents the user-facing contract for the **AutoConnect button** — the "Auto Connect" UI control that starts Auto Mode. It is derived from explicit user-directed requirements **R1–R8** gathered at phase **F.28AL-AUTOCONNECT-SPEC**. Each requirement is stated as a *testable* contract: the trigger, the expected behavior, the stop conditions, and the fallback path. Evidence class: **USER-DIRECTED REQUIREMENT** — the behavior below is what the user requires the app to do; the current implementation is verified against this contract in the bug-fix phase **F.28AL-AUTOCONNECT-BUG-FIX**.

> **Cross-reference (diagnosis):** The recently diagnosed bugs — **F.28AL-AUTOCONNECT-BUG-DIAG: BUG 1, BUG 2, BUG 3** — are known deviations from this contract. BUG 1 violates R4/R6 (a UDP-only server with no working SoftEther-UDP transport is still selected); BUG 2 violates R7 (after a user skip, the run hangs instead of advancing or stopping); BUG 3 violates R5 (the user-configured per-protocol timeout is logged but not applied). The implementation will be corrected in **F.28AL-AUTOCONNECT-BUG-FIX** to match this contract. See `F28AL_AUTOCONNECT_BUG_DIAG_REPORT.md` for the full diagnosis.

#### R1. First-Time Behavior — Empty or Missing Server List

- **Trigger:** The AutoConnect button is pressed while the app has **no server list** — the collected/merged candidate set is empty, or the server provider returns an empty list.
- **Expected behavior:**
  - Display a user-visible message: **"Please update the server list first."**
  - Do **NOT** start the AutoConnect flow. No reachability probe, no connection attempt, no server rotation.
- **Stop condition:** The AutoConnect run is refused before any attempt is armed or any `ACTION_CONNECT` is dispatched.
- **Fallback path:** None. The user must populate the server list (normal refresh path §8, or an emergency/manual source §11.9 / §11.10) before AutoConnect can proceed.
- **Testable:** Given an empty candidate set, pressing AutoConnect shows the message and produces **zero** `ACTION_CONNECT` intents.

#### R2. Active Tunnel Handling

- **Trigger:** The AutoConnect button is pressed while a VPN tunnel is **already active** — the app is in `AutoModeState.Connected` or a manually-connected `CONNECTED` state observable through `GlobalVpnTracker`.
- **Expected behavior:**
  - **Disconnect the current tunnel first** (dispatch the same teardown path used by the manual Disconnect control, including any `EXTRA_TEARDOWN_ATTEMPT_ID` arming per §5 / F.28AD-B).
  - **Then** proceed with the AutoConnect flow (R3–R7) against the sorted server list.
- **Stop condition / ordering:** The disconnect must be deterministically in-flight (or confirmed) before the first AutoConnect attempt is armed, so Auto Mode does not observe the pre-existing tunnel's `STATE_DISCONNECTED` as a failure of its own first attempt. The `AutoModeController` start guard consults `GlobalVpnTracker` to avoid double-owning the tunnel.
- **Fallback path:** If the disconnect itself cannot be issued, the AutoConnect run must not begin (the active-tunnel condition still holds).
- **Testable:** Given an active tunnel, pressing AutoConnect emits a `DISCONNECT` (or equivalent teardown) **before** the first `ACTION_CONNECT` of the AutoConnect run.

#### R3. Quick Reachability Probe and Sorting

- **Trigger:** The AutoConnect button is pressed with a non-empty server list and no active tunnel.
- **Expected behavior:**
  - **Immediately** (before any connection attempt) perform a **fast reachability probe** against the server list — one probe target per usable protocol per server, per the D1 probe rule (`probeTargets`, §10.1 / `AutoModeController.kt`).
  - **Sort the candidate list by the probe results** (reachable-before-unreachable, then by representative active RTT §6.6) **before** the first connection attempt begins.
- **Stop condition:** None specific to the probe — it is a pre-pass that orders the list.
- **Fallback path:** If probing is inconclusive for some servers, they are ordered by the best available evidence (last-known RTT, score) rather than dropped; only a server with no usable protocol (`usableProtocols` empty, §10.1) is excluded from the attempt list.
- **Testable:** Given a non-empty list, the first `ACTION_CONNECT` is preceded by probe activity and the attempt order matches the probe-sorted ranking.

> **Cross-reference:** The probe/sort mechanism is the existing `compatibleServers` → `sortedForAutoConnect` path (§10.1) and `probeTargets`. This requirement elevates it to a **mandatory button-press pre-pass** of the AutoConnect contract.

#### R4. Sequential Server Testing

- **Trigger:** After R3 sorting, AutoConnect begins iterating the sorted list.
- **Expected behavior:**
  - Attempt servers **one by one** from the sorted list.
  - For each server, try the **user-configured protocols in the user-configured priority order** (the `protocolPriorityProvider` list, §10.1 / §16.4 rule 4).
  - One-attempt-per-server (§6.5): if a server fails on its highest-priority usable protocol, the next usable protocol follows, then the next server.
- **Stop condition:** A verified tunnel (`STATE_CONNECTED`) ends the run with success; see R7 for exhaustion/stop conditions.
- **Fallback path:** If a server supports no usable protocol (empty `usableProtocols`), it is skipped (§10.1) and never retried within the same run.
- **Testable:** Given a sorted list of N servers and a priority list of M protocols, AutoConnect attempts at most N×M ordered (server, protocol) pairs — in sort order then priority order — without placing a reachable candidate after an unreachable one.

> **Cross-reference:** Sequential iteration is already shown in the §6.3 attempt lifecycle (`for each (server, protocol) in ordered candidates`). This requirement makes **"one by one, in user priority order"** an explicit contract.

#### R5. Per-Server, Per-Protocol Timeout

- **Trigger:** Each (server, protocol) attempt begins (`beginAttempt`).
- **Expected behavior:**
  - The timeout for an attempt is **per server, per protocol** and is **configurable in Settings** (the `attemptTimeoutMs` plumbing in `AutoModeEngine.ensure()`, §10.1).
  - **Default: 5 seconds per protocol attempt.**
  - If the user has enabled multiple protocols, each protocol attempt has its **own independent** timeout window (a server that fails on protocol A within 5 s does not consume protocol B's 5 s budget).
- **Stop condition:** If the armed tunnel wait (`awaitArmedTunnel`, §6.4) does not complete within `attemptTimeoutMs`, the attempt is declared failed and the next (server, protocol) pair is tried.
- **Fallback path / selection:** UDP-only servers (`seTcpPort=0`, `seUdpSupported=true`) must **NOT** be selected for `SOFTETHER_UDP` — per the §6.2 endpoint rule and `SERVER_SOURCE_ARCHITECTURE.md` §3.5. Selection filtering (not timeout) handles them. (This is the BUG 1 fix point.)
- **Testable:** With default Settings, each protocol attempt is bounded by ~5 s; the documented implementation must apply `attemptTimeoutMs` to **both** the armed `select` wait (`awaitArmedTunnel`) **and** the native race (`ConnectionConfig.connectTimeoutMs`), not merely log it.

> **Cross-reference:** `DEFAULT_ATTEMPT_TIMEOUT_MS` (§10.1, §17) is the per-attempt bound. This requirement fixes its default at **5 s** and requires it be plumbed **per-protocol**. NOTE: the bug-fix phase (F.28AL-AUTOCONNECT-BUG-FIX) must correct the current deviation where the user timeout is logged but not applied to the armed `select` (BUG 3) and not passed to the native race (also BUG 3).

#### R6. Protocol List and Priority (Settings)

- **Trigger:** AutoConnect builds its attempt list.
- **Expected behavior:**
  - The **list and priority** of protocols AutoConnect tries on each server is **configurable in Settings** and surfaced as the `protocolPriorityProvider` ordered list (§10.1).
  - This feature is **already implemented**; this requirement records its contract:
    - The default protocol priority is `[SOFTETHER_UDP]` (§6.2 / `DEFAULT_ID`, §17).
    - An **empty** priority list means **refuse**, not default (§16.4 rule 4) — AutoConnect must not dial a protocol the user switched off, and must not invent a fallback protocol.
    - Each protocol in the list is tried in order; only protocols the app can drive unattended (`isAutomatable`, §10.1) and that the server supports (`supports`, §10.x) are eligible for a given server.
- **Stop condition / fallback:** If the user's priority list yields no eligible protocol for a server, that server is skipped (R4 fallback). If the priority list is empty for all servers, AutoConnect refuses (equivalent to R1's "no usable configuration").
- **Testable:** Given a Settings priority list `[OPENVPN_UDP, SOFTETHER_TCP]`, AutoConnect attempts OPENVPN_UDP before SOFTETHER_TCP on each eligible server, and never attempts a protocol absent from the list.

> **Cross-reference:** Protocol support matrix §6.2, protocol priority §10.1, refusal rule §16.4 (4). This requirement documents the **Settings-facing contract** for an already-implemented capability.

#### R7. Continuous Attempts Until Stop Condition

- **Trigger:** AutoConnect is running.
- **Expected behavior:** The app keeps trying servers until one of:
  - **(a)** The end of the server list is reached (all sorted candidates attempted without a verified tunnel) → run ends with `ERROR_NO_SERVER` (§6.3).
  - **(b)** The user presses the AutoConnect button **again** to stop the operation → the run is cancelled and any in-flight armed attempt's tunnel is torn down.
- **Stop condition:** There is **no other implicit stop condition.** AutoConnect does not stop on a single server failure, on a NATIVE_BUSY refusal (§6.4 — it continues without rotating), or on any timeout short of exhausting the list.
- **Fallback path:** On stop-condition (a), AutoConnect returns to idle with no active tunnel. On (b), the in-flight attempt is cancelled and the tunnel torn down.
- **Testable:** Given a list of N unreachable servers, AutoConnect attempts all N (in sort order) and only then reports `ERROR_NO_SERVER`; a second button press during the run cancels it.

> **Cross-reference:** `endRun(ERROR_NO_SERVER)` (§6.3). This requirement adds the **second stop condition (user re-press)**, which was previously undocumented.

#### R8. Automatic Reconnection in Auto Mode (New Setting)

- **Full settings requirement:** see **§11.13** — finalized name `Auto-Reconnect in Auto Mode` («اتصال همیشکی اتوکانکت»), default **ENABLED (فعال)**, and enabled/disabled behavior.

---

## 7. VPN Gate Server Data Model

### 7.1 Canonical Protocol-First Schema

**File:** `app/src/main/java/vn/unlimit/vpngate/data/model/VpnModels.kt`

The server record schema is a **protocol-first** nested map (`MutableMap<String, Any?>`), a mechanical port of the Python reference collector's internal model. Typed accessors live in `VpnRecords`. The schema version is `4.0` (`VpnRecords.SCHEMA_VERSION`).

**Record structure:**
```
VpnServerRecord (MutableMap<String, Any?>)
├── schemaVersion: String ("4.0")
├── identity: Map
│   ├── hostname: String
│   ├── ip: String
│   ├── ispHostname: String
│   ├── country: String
│   └── countryLong: String
├── performance: Map
│   ├── score: Int
│   ├── pingMs: Double
│   ├── speedMbps: Double
│   ├── sessions: Int
│   ├── uptimeDays: Double
│   ├── totalUsers: Int
│   └── totalTrafficGB: Double
├── logging: Map { policy: String }
├── operator: Map { name: String, message: String }
├── protocols: Map
│   ├── softether: { supported, tcp: {supported, port, portSource}, udp: {supported, port, portSource, dynamicPort} }
│   ├── openvpn: { supported, tcp: {...}, udp: {...}, configAvailable, configs: [OpenVpnRemote] }
│   ├── l2tpIpsec: { supported, port, portSource }
│   └── sstp: { supported, hostname, port, portSource }
├── sources: List<String>          // source tags that confirmed this server
├── sourceCount: Int
├── fieldSources: Map<String, List<String>>
├── quality: Map { overall, softether, openvpn, sstp, l2tp }
└── conflicts: List<Conflict>      // §38: visible disagreements recorded during merge
```

### 7.2 Port Provenance (VpnPortSource)

**File:** `app/src/main/java/vn/unlimit/vpngate/data/model/VpnModels.kt`

A port's provenance is one of:
| Constant | Meaning |
|----------|---------|
| `EXPLICIT` | The source printed this port (direct evidence) |
| `DEFAULT` | The protocol default (443 for SSTP, 1701 for L2TP); used only when the source confirms the capability |
| `UNKNOWN` | The source supplied nothing — must never become a value |

**Stage 2F-A (L-1):** `UNKNOWN` is ONE canonical literal (previously the empty string while database defaults and comments spelled it "UNKNOWN"). `VpnPortSource.normalized()` folds any defensive variant (null, empty, unrecognised) onto `UNKNOWN`. The `isUnset` check in `VpnRecords.setField` treats `UNKNOWN` provenance as unset so a stored sentinel can never refuse a later `EXPLICIT`/`DEFAULT` write from a parser.

**Protocol defaults:**
- `VpnProtocolDefaults.SSTP_PORT = 443` — MS-SSTP protocol-standard TCP listener
- `VpnProtocolDefaults.L2TP_PORT = 1701` — L2TP IANA-assigned UDP listener

### 7.3 Room Entity: VPNGateItem

**File:** `app/src/main/java/vn/unlimit/vpngate/models/VPNGiteItem.kt`

Room entity with primary key `hostName` (not IP, unlike the collector's merge key). Fields include all protocol ports and their SSTP port provenance.

**`dedupeForStorage`** (Stage 2F-A E-1): Collapses the dataset to one row per Room identity. Room's primary key is `hostName` while the collector merges on IP, so a single feed can legitimately contain the same hostname on two addresses. The deduplication uses a total order: most connectable first, then best score, then fastest link, then smallest address. This replaces the old `insertAll` that aborted on duplicate keys and failed the whole transaction.

### 7.4 Legacy Parcelable: VPNGateConnection

**File:** `app/src/main/java/vn/unlimit/vpngate/models/VPNGateConnection.kt`

Parcelable connection config used by the existing list/detail/connect UI. Converted from `VpnServerRecord` via `VpnConnectionMapper`. Handles SSTP port description via `SstpPortKind` (EXPLICIT, DEFAULT, UNKNOWN) — provenance-first, never port-first.

---

## 8. Server Collector Pipeline

### 8.1 VpnServerRepository

**File:** `app/src/main/java/vn/unlimit/vpngate/repository/VpnServerRepository.kt`

The in-app multi-source collector (Mode B). Collects server data from the VPN Gate main HTML page, the official API, and official mirrors; then merges, validates, scores, and maps into `VPNGateConnection`.

**Collection pipeline:**
```
refresh()
    ├── discover mirrors (from cached URLs, bootstrap list, canonical directory race)
    ├── race fetch: main HTML + official API in parallel (async)
    ├── parse each body under its OWN source tag ("html"/"api"/"mirror_<n>")
    ├── merge records (VpnServerMerger.mergeRecords)
    ├── validate servers (VpnServerMerger.validateServers → normalizeServer)
    ├── score all (ServerQualityCalculator.scoreAll)
    └── map to VPNGateConnectionList (VpnConnectionMapper)
```

**Fallback chain on total failure:**
1. Last-known-good JSON snapshot (atomic temp-file write, schema-migrated on load)
2. Room database (`vpnGateItemDao.getAll()`)
3. Bundled seed asset (`seed_vpn_servers.csv`)

**Snapshots:** Stage 2C-B — snapshots are NOT written during collection; they are promoted to last-known-good only after `DatasetIntegrity` evaluation passes and the database already holds them. This prevents a degraded dataset from overwriting the valid fallback.

**Constants:**
- `MAIN_TIMEOUT_MS = 45_000L`
- `MIRROR_TIMEOUT_MS = 30_000L`
- `RAW_CAPTURE_LIMIT = 512 * 1024` (512 KB for debug panel)

### 8.2 Source Identity

**File:** `app/src/main/java/vn/unlimit/vpngate/data/remote/VpnGateSources.kt`

**Stage 2C-A:** Every candidate is a `SourceIdentity` from the start. A body is only accepted after HTTP, redirect, content-type, schema, and parse-sanity checks pass. A mirror cannot be reported as the canonical feed.

**Source types:**
| Kind | Tag |
|------|-----|
| `CANONICAL_HTML` | `"html"` |
| `CANONICAL_API` | `"api"` |
| `BOOTSTRAP_MIRROR` | from built-in IP list |
| `CACHED_MIRROR` | from persisted URL cache |
| `DISCOVERED_MIRROR` | from `sites.aspx` directory |

**Bootstrap mirrors (VPN Gate official IP mirrors):**
```
http://150.40.105.19:35399
http://150.40.105.6:11803
http://150.40.105.11:4917
http://150.40.105.23:64629
http://194.156.89.134:47774
http://121.186.186.97:40587
```

**URLs:**
- `MAIN_URL = "https://www.vpngate.net/en/"`
- `API_URL = "https://www.vpngate.net/api/iphone/"`
- `MIRRORS_URL = "https://www.vpngate.net/en/sites.aspx"`
- `MAX_MIRRORS = 12`

**Concurrent race:** `raceFetch` fetches all candidates concurrently, but acceptance requires structural validation (not a keyword test). First valid body wins.

### 8.3 VpnServerMerger

**File:** `app/src/main/java/vn/unlimit/vpngate/merger/VpnServerMerger.kt`

Conflict-aware multi-source merge — mechanical port of the Python oracle:
- §11: Fill gaps without conflict (a falsy value never overwrites a meaningful one)
- §14: `fieldSources` provenance tracked at top level, merged once per root
- §16/§17: Dedup by IP→hostname; merge duplicates
- §17: Source priority — `api > html > mirror_*`
- §38: Conflicts are never silently overwritten; each is recorded as a visible `Conflict(field, values)`

**`recursiveMerge`:** Walks maps/lists/scalar leaves. For scalar leaves with provenance-aware conflict handling: if both old and new are truthy and differ, the higher-authority source wins AND a conflict is recorded. If old is falsy (including `UNKNOWN` provenance), the new value fills the gap.

### 8.4 ServerQualityCalculator

**File:** `app/src/main/java/vn/unlimit/vpngate/ranking/ServerQualityCalculator.kt`

Quality scoring — port of the Python oracle's formula with ONE documented difference (plan T3.5): the multi-source bonus counts **independent provenance groups** instead of raw source tags.

**Score components (max 100):**
- Speed: 0–30
- Ping: 0–25
- Sessions (congestion): 0–15
- Uptime: 0–15
- Multi-source confidence: 0–15 (independent source groups, not raw count)
- Protocol bonuses: +5 SoftEther, +4 OpenVPN, +3 SSTP, +3 L2TP

**Sort:** `quality[key]` descending → `speedMbps` descending → `pingMs` ascending.

---

## 9. Connection Flow

### 9.1 Start Flow (SoftEtherVpnService.startVpn)

```
ACTION_CONNECT intent
    ├── startForeground()
    ├── sessionResourcesLive = controller != null || tunOwnership.isHeld() || connectionJob != null
    ├── decision = sessionPolicy.decideStart(resourcesLive)
    │       REFUSE_CONNECTED / REFUSE_CONNECTING / REFUSE_TEARDOWN → log + return
    ├── nativeLease = NativeLifetimeRegistry.process.tryAcquire()
    │       null → notifyNativeBusy(sessionGeneration, describe()) + return
    ├── session = sessionPolicy.onSessionStart()  // captures new generation
    ├── tunOwnership.claim(session)  // F.28J: claim before connect coroutine
    ├── controller = ConnectionController(…, nativeLease, sessionId=session, …)
    │       ├── ConnectionController constructs NativeAttemptCoordinator
    │       └── controllerCreated = true
    ├── controller.connect()  // performConnect() on IO dispatcher
    │       ├── connectInFlight.set(true)
    │       ├── nativeCreate()
    │       ├── markNativeEntered(lease)
    │       ├── nativeConnectWithHub()  // BLOCKING JNI
    │       ├── isCancelled check
    │       └── markConnected(lease)
    └── if connect fails: stopVpn(disconnectByError = true)
```

**F.28Y:** The `NATIVE_CLEANUP_CONFIRM_TIMEOUT_MS = 2_000L` timeout ensures teardown waits long enough for the normal slow path ("connect takes a moment longer than the 500 ms deadline to return and frees its own handle"). This prevents Auto Mode from rotating servers against a slot about to free.

### 9.2 Teardown Flow (SoftEtherVpnService.stopVpn)

```
ACTION_DISCONNECT intent
    ├── suppliedAttemptId = intent.getIntExtra(EXTRA_TEARDOWN_ATTEMPT_ID, 0)
    ├── stopVpn(suppliedAttemptId)
    │       ├── if isStopping and no new resources → NOOP_REENTRANT(attemptId)
    │       ├── beginTeardown(attemptId): isStopping=true, clear wasConnected, isRunning
    │       ├── publish STATE_DISCONNECTED/ERROR immediately (UI reacts)
    │       ├── connectionJob?.cancel(); connectionJob = null
    │       ├── controller = null
    │       ├── tunOwnership.release(tearingDown)
    │       ├── notifyTeardownCompletion(TeardownCompletion.of(...))
    │       └── serviceScope.launch(NonCancellable) { currentController?.destroyResources() }
    │               ├── nativeLifecycle.requestTeardown()  // snapshot flow, interrupt socket
    │               ├── wait NATIVE_TEARDOWN_TIMEOUT_MS (500ms)
    │               ├── if flow exited → runCleanup() (destroy + release lease)
    │               └── if timeout → UNRESOLVED, ownership passes to connect flow
```

**F.28AD-B:** The caller-supplied `attemptId` allows Auto Mode to arm a teardown-completion waiter *before* dispatching the disconnect intent, so the waiter is never satisfied by an unrelated newer completion.

### 9.3 Service Destruction (onDestroy)

```
onDestroy()
    ├── connectionJob?.cancel()
    ├── currentController = controller; controller = null
    ├── tunOwnership.releaseAll()  // UNCONDITIONAL — F.28J, no session left
    ├── serviceScope.launch(NonCancellable) { currentController?.destroyResources() }
    └── serviceScope.cancel()
```

**F.28J:** Release is unconditional because service destruction outlives every session — there is no session whose ownership could legitimately survive. This runs `destroyResources()` on `Dispatchers.IO` to avoid the ANR from blocking native calls on the main thread.

---

## 10. Auto Mode Engine

### 10.1 AutoModeController

**File:** `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt`

Pure-JVM orchestration core (no Android framework dependencies). Key responsibilities:
- **Candidate ordering:** Ranks by quality score, then speed, then RTT (if available)
- **One-attempt-per-server:** Each server is tried at most once per run per protocol
- **Verified tunnel wait:** Waits for `STATE_CONNECTED` via `TunnelStateWatcher`, with `attemptTimeoutMs` bound
- **Protocol priority:** Uses `protocolPriorityProvider` (ordered, non-empty; empty means refuse, not default)
- **StateFlow:** Exposes UI-observable state

**Constants:**
- `DEFAULT_ATTEMPT_TIMEOUT_MS` — per-attempt timeout (configurable, see `AutoModeEngine`)

### 10.2 AutoModeProtocol

**File:** `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeProtocol.kt`

```kotlin
enum class AutoModeProtocol(val id: String) {
    SOFTETHER_TCP("SOFTETHER_TCP"),
    SOFTETHER_UDP("SOFTETHER_UDP"),
    OPENVPN_TCP("OPENVPN_TCP"),
    OPENVPN_UDP("OPENVPN_UDP"),
    L2TP_IPSEC("L2TP_IPSEC"),
    MS_SSTP("MS_SSTP");

    companion object {
        const val DEFAULT_ID = "SOFTETHER_UDP"
        fun fromId(id: String?): AutoModeProtocol =
            entries.firstOrNull { it.id == id } ?: SOFTETHER_UDP
    }
}
```

**Phase 2F-F.2:** Endpoint resolution is relocated to the automode layer (not re-derived in the probe). `endpointPort()` maps each protocol to the candidate's own published port, never borrowing from another protocol.

### 10.3 AutoModeCandidate

**File:** `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeModels.kt`

Protocol-capability + quality snapshot, decoupled from `VPNGateConnection` so the controller stays a pure JVM unit-test target.

**Key fields:**
- `hostname`, `ip` — server identity
- `speed`, `ping`, `sessions`, `score` — performance metrics (from collector)
- `sources` — count of independent source groups confirming this server
- `seTcpPort`, `seUdpPort`, `seUdpSupported` — SoftEther capabilities
- `openVpnTcpPort`, `openVpnUdpPort` — OpenVPN capabilities
- `l2tpSupported`, `sstpSupported` — capability flags
- `sstpPort`, `sstpPortSource` — MS-SSTP port and its provenance (EXPLICIT/DEFAULT/UNKNOWN)
- `sstpConnectPort` — resolved connect port (0 means cannot be dialed)
- `activeRttMs` — runtime-only active RTT (not persisted)
- `payload` — platform object for the adapter

### 10.4 TunnelStateWatcher

**File:** `app/src/main/java/vn/unlimit/vpngate/automode/TunnelStateWatcher.kt`

Process-global observer. Key roles:
- **Waiter registry:** `beginAttempt(protocol, onVerified)` registers a tunnel-completion callback; `endAttempt()` removes it.
- **State replay:** `beginAttempt` replays an already-terminal state synchronously, so a fast `CONNECTED` is not missed.
- **Nativebusy delivery:** `onNativeBusy` callback receives the NATIVE_BUSY refusal from `SoftEtherVpnService` and signals the armed deferred in `AndroidConnectionAdapter`.
- **Foreign stack terminal:** `FOREIGN_STACK_TERMINAL` completions are delivered regardless of attempt ID (not part of the sequenced protocol).
- Validates recency only (no attempt-ID identity check on tunnel completions — they are scoped by the current session generation in the adapter).

### 10.5 AndroidConnectionAdapter

**File:** `app/src/main/java/vn/unlimit/vpngate/automode/AndroidConnectionAdapter.kt`

Bridge between Auto Mode controller and `SoftEtherVpnService`. Key methods:
- `armTunnelWatch(protocol)` — arms the tunnel deferred BEFORE the connect request (F.28 Fix D: closes the window where a fast CONNECTED arrives before anyone is listening)
- `awaitArmedTunnel(protocol, timeoutMs)` — races the tunnel deferred against the native-busy deferred via `kotlinx.coroutines.select`
- `connect(server, protocol)` — dispatches `ACTION_CONNECT` to the service

**Constants:**
- `TEARDOWN_TIMEOUT_MS = 5_000L` — overall disconnect timeout

---

## 11. Binding Design Decisions

### 11.1 Process-wide vs. Per-attempt Locks

The native SoftEther stack has exactly **one** `connect_mutex` per `softether_connection_t`, and it wraps only `softether_connect_with_hub`. This means:
- Connects to different connections are NOT serialized against each other
- `softether_destroy` does NOT wait for a blocked connect to finish

The `NativeLifetimeRegistry` provides the **process-wide** serialization that the native mutex cannot: a new `nativeCreate()` is only admitted when no previous `nativeConnectWithHub` or `nativeDestroy` is unresolved.

### 11.2 Why `shutdown()` Does Not Close the FD

`softether_force_close_socket` (line 1098) calls `shutdown(fd, SHUT_RDWR)` to interrupt a blocking `SSL_read`/`SSL_write`. It deliberately does NOT `close()` the fd or clear `conn->socket_fd` because:
1. The connect thread still holds the SSL context that references this fd
2. Closing it would create a stale-fd dereference window in BoringSSL
3. Clearing `socket_fd` would prevent `softether_disconnect()` from closing it later (fd leak)
4. The connect thread closes the fd properly via `softether_disconnect()` when it unwinds

### 11.3 Why `NativeLifetimeRegistry` Is SoftEther-Only

OpenVPN (ics-openvpn) and SSTP (sstpClient) have their own lifecycles and neither consults `SoftEtherSessionPolicy` nor `NativeLifetimeRegistry`. Unifying them under a shared gate is a separate future change (noted in `NativeLifetimeRegistry.kt` line 68).

### 11.4 Why Attempt ID Is Process-Wide

`TeardownCompletion.attemptId` and `TunnelStateWatcher`'s monotonic counter live in the service's companion object because both must stay monotonic across service recreation. A fresh service instance's id `1` would otherwise look older than a stale completion from a previous instance the watcher already saw.

### 11.5 Why SSTP Port Cannot Be Borrowed from OpenVPN

The VPN Gate CSV feed carries no per-protocol port columns — only the OpenVPN config blob. `derivePortsFromOpenVpnConfig()` extracts the TCP/UDP port from the blob, and the SoftEther TCP port inherits from OpenVPN TCP (same listener, protocol auto-detection). But SFTP port is NEVER borrowed from another protocol — `sstpConnectPort` returns 0 for `UNKNOWN` provenance rather than guessing 443.

### 11.6 Why Quality Uses Independent Source Groups

`ServerQualityCalculator` counts independent provenance groups (not raw source tags) for the multi-source confidence bonus. Six mirrors from the same operator are NOT six times as trustworthy as one — they share the same failure modes. This is the one documented difference from the Python oracle (plan T3.5).

### 11.7 Why Snapshot Write Defers to Storage Acceptance (Stage 2C-B)

The snapshot is NOT written during collection. It is promoted to last-known-good only after `DatasetIntegrity` evaluation passes and the database already holds the records. Writing during collection is what allowed a degraded dataset to overwrite the valid fallback before anything judged it.

---

## 11.8 Dynamic Server-Source Access (Product Requirement)

This requirement supersedes and supplements the source-collection architecture described in §8. It establishes the product behavior that §8.1 currently implements in a narrower form (direct-only network path).

### 11.8.1 Dynamic network-path selection for server-list retrieval

SoftEthergai must not depend on a single fixed network path to retrieve its VPN server list.

The application is intended to work in network environments where some server sources may be filtered or unreachable over the ordinary Internet connection. In particular, sources that are inaccessible from Iran through the direct network path may still be reachable through an active VPN tunnel or through another source/path combination.

The desired architecture must distinguish two independent concepts:

**A. SERVER SOURCE** — Examples:
- VPN Gate API
- VPN Gate HTML
- Existing mirrors
- User-configured emergency sources
- Future sources such as GitHub
- Other future sources

**B. NETWORK PATH** — Examples:
- Direct — outside the VPN tunnel
- Through the active VPN tunnel
- Future supported network paths

Do not conflate a source with a network path.

The application should dynamically evaluate available source/path combinations rather than permanently assuming that all server-list requests must use either the direct network or the VPN tunnel. Examples of desired behavior:

- VPN Gate API fails over the direct path but succeeds through the active VPN tunnel.
- VPN Gate HTML succeeds directly.
- A GitHub-hosted source is added in the future and can be evaluated using the same source/path architecture.
- A failure through one path must not automatically classify the source itself as permanently broken.
- Failure to reach one source must not prevent trying other eligible sources and paths.

The exact path-selection algorithm, ordering, concurrency model, timeouts, retry policy, and Android network-binding implementation remain design/implementation work unless already explicitly decided elsewhere in this document. Do not invent final values.

The system should retain useful source/path outcome information where appropriate, so future attempts can make informed decisions. Do not assume that one failure proves a source is blocked or permanently unavailable.

### 11.8.2 Current implementation observation

Source audit of the existing server-list HTTP path (`HttpFetcher.kt`, `VpnServerRepository.kt`) found no explicit VPN-network binding — no `ConnectivityManager.bindProcessToNetwork`, no `VpnService.protect()` in the update path, and no explicit `Network` selection on the OkHttp client. The `ResilientDns` class (`HttpFetcher.kt:53`) provides DNS-resilience fallbacks for the vpngate.net domain common in censored environments, but does not bind the HTTP client to the VPN tunnel.

This is recorded as a **current implementation observation**, not as the desired product behavior. The desired product behavior is dynamic evaluation of both direct and VPN-tunnel paths, subject to actual Android platform capabilities and safe implementation.

### 11.8.3 Existing path statements in this document

The existing server-collector description in §8 (especially §8.1) documents the current behavior where collection requests run on the default Android network path. No statement in this document currently asserts that server-list requests must permanently use only the direct path or only the VPN tunnel. Any such future observation must be preserved as current behavior and reconciled with the dynamic-path requirement rather than removed.

---

## 11.9 Emergency Server Sources (Settings Requirement)

A new settings section, provisionally named **Emergency Server Sources**, must let users configure additional server-list sources.

### 11.9.1 User configuration requirements

The user must be able to configure one or more emergency source URLs:
- Add, edit, enable, disable, and remove user-configured emergency source URLs.
- Persist the user's settings.
- Treat these as additional/fallback sources, not as an automatic replacement for the built-in sources (VPN Gate API/HTML and built-in mirrors).
- Allow each configured source to participate in the same dynamic network-path strategy described in §11.8.
- Keep source identity and provenance so the application can distinguish built-in, mirror, emergency, and manual records.

### 11.9.2 Safety requirements

The application must handle the following safely:
- Invalid URLs
- Failed downloads
- Unsupported formats
- Malformed data
- Duplicate records

A failed emergency source must not erase existing server data.

### 11.9.3 Format rules

Do not assume every URL points to CSV; format support must be explicit and validated. The initial planned emergency-source format is a standardized project CSV format, but its exact schema has **NOT** been defined yet.

**CSV schema status: DEFERRED / NOT YET DEFINED.**

Do not invent, in this document or otherwise, any of the following for the emergency CSV schema until a separate design task decides them:
- Column names
- Required or optional fields
- Encoding
- Delimiter
- Header rules
- Protocol representation
- Credential/configuration representation
- Versioning rules
- Validation rules

A future design task must define and document the canonical CSV schema **before** implementing CSV import. The eventual design should consider schema versioning and safe validation, but the exact format is not decided by this documentation task.
> **Design reference (F.28AL-Z):** The deferred CSV-schema design task is now captured in **`SERVER_SOURCE_ARCHITECTURE.md`** (canonical design document). It defines the emergency CSV schema (v1.0 start), validation, provenance, integrity rules, and the mandatory RUDP port-semantics warning, alongside the dynamic/VPN-aware server-list retrieval path design (open network → VPN tunnel → emergency → manual), manual-import design, extensibility/trust model, a three-condition physical test plan, and an implementation-phase outline. It additionally defines **Section 9 — Cumulative Server Database Retention** (cumulative-add updates that never auto-remove healthy servers, a configurable max-server cap defaulting to 1000 with retention priority *health → protocol-completeness → connection-history*, proposed Room retention fields/migration, a Settings UI proposal, and new `DatasetIntegrity` outcomes `ACCEPT_NEW` / `MERGE_WITH_EXISTING` / `REJECT_DEGRADED`). It further defines **Section 10 — Extended Server CSV Schema for the Emergency Source `servers.csv`** (all 15 VPN Gate iPhone-API base columns, unrenamed, plus HTML-derived protocol/port/SSTP columns; per-column semantics and validation; IPv4-`IP` identity with completeness-merge dedup; `api_iphone`/`html_parse`/`merged` provenance; UTF-8-no-BOM/LF pure-CSV structure; `DatasetIntegrity` accept/merge/reject compatibility; and the verbatim RUDP port-semantics warning). This remains a **design document only** — no schema, code, or behavior is implemented by it. §11.9.3 stays DEFERRED for implementation; consult the design doc when that implementation phase is authorized.


---

## 11.10 Manual Server Entry (Product Requirement)

Users must be able to add individual VPN servers manually.

### 11.10.1 Motivating use case

A user receives VPN server details from a friend by SMS, WhatsApp, Telegram, email, Bluetooth, a file, or another communication method and wants to add the server to SoftEthergai.

This is distinct from configuring an emergency URL:
- **Emergency source** = a URL from which the application retrieves a dataset.
- **Manual server** = an individual server/configuration supplied directly by the user.

### 11.10.2 UI entry point

The future UI should provide an obvious "Add Server" entry point. Possible placements include:
- An Add Server row at the end of the server list
- A floating action button
- Another UI placement consistent with the existing design

Do not choose the final UI placement unless the existing specification already decides it.

### 11.10.3 Input methods

Planned input methods should be considered in phases:
- Paste server details or configuration text
- Enter server fields manually
- Import supported files
- Receive shared text/configuration through Android Sharesheet / Intent
- Support data received from messaging and communication applications, subject to Android platform capabilities and supported formats

Do not promise that arbitrary SMS, messaging-app, or file formats will be automatically understood. The app must define and validate supported input formats.

### 11.10.4 Entry requirements

Before adding a manual server:
- Validate the supplied information.
- Explain invalid or incomplete input to the user.
- Avoid duplicate entries.
- Preserve the original input where appropriate for editing or troubleshooting, subject to secure handling.
- Record that the server was added manually.
- Allow the user to edit and remove manually added entries.

Do not invent the final manual-import format or server model changes in this task.

---

## 11.11 Persistence, Merge, and Retention Rules

The following principles govern how built-in, mirror, emergency, and manual sources interact with the persistent server database (Room), and must be preserved when §11.8–§11.10 are implemented:

- **Manual servers must survive ordinary online server-list updates.** A manual record must not be removed when a source refresh runs.
- **Emergency-source records must not be accidentally treated as built-in-source records.** Source provenance must be preserved end-to-end.
- **Preserve source provenance.** Built-in, mirror, emergency, and manual records must carry distinct, retrievable provenance.
- **Deduplicate records without discarding useful protocol capabilities, configurations, source history, or health/reachability information.**
- **A record missing from one partial source response must not automatically be deleted.** Missing records in a partial response are not evidence of removal.
- **A failed fetch, parse error, timeout, or network-path failure must never trigger destructive replacement of the existing dataset.** This reinforces §8.1's fallback chain and §8.1's snapshot-write deferral to `DatasetIntegrity` acceptance.
- **Continue respecting the existing server-retention requirements and established decisions in this document** (notably the merge/validate/score pipeline, snapshot deferral, and the conflict-visibility rules of §8.3).
- **Do not weaken existing protections for healthy historical servers.**
- **Removal/retirement policy must follow the existing documented priorities and evidence requirements; do not invent a new policy that conflicts with them.**

**Source provenance vs. server identity:** source provenance and server identity may need separate representations. The same logical server may be observed through multiple sources; a single manually entered record must remain user-managed and must not be silently overwritten by any source refresh.

**Database schema:** Do not design or implement a new database schema here. Schema and migration work required to support §11.8–§11.10 is a future implementation/design task.

---

## 11.12 Future Extensibility

The source-and-path architecture must allow new source types and network paths to be added without rewriting the whole server-list pipeline:

- GitHub may be added later as a source.
- The user-configured emergency source (§11.9) must use the same general retrieval architecture as built-in sources.
- New sources should not require duplicating the entire fetch/parse/merge pipeline.
- Source adapters, path selection, parsing, validation, provenance, and merging should remain conceptually distinct responsibilities.

Treat this as a design goal, not as a mandate to introduce specific interfaces or classes before an implementation audit.

---

### 11.13 Automatic Reconnection in Auto Mode (Settings Requirement)

A new Settings option controls whether Auto Mode automatically re-establishes a tunnel after an **unexpected** disconnect of a server that Auto Mode itself connected.

> **Evidence class:** USER-DIRECTED REQUIREMENT (R8, F.28AL-AUTOCONNECT-SPEC). Distinct from `ConnectionController`'s per-attempt `MAX_RECONNECT_ATTEMPTS = 2` / `RECONNECT_DELAY_MS = 1000L` (§17), which are native retries *within a single connect attempt*, not Auto Mode-level server rotation after an unexpected drop.

#### 11.13.1 Setting name (USER-DIRECTED REQUIREMENT — CONFIRMED)

- **Final English name:** `Auto-Reconnect in Auto Mode`
- **Final Persian name (نام فارسی):** «اتصال همیشکی اتوکانکت»
- Evidence class: **USER-DIRECTED REQUIREMENT (CONFIRMED)** — the user explicitly chose this name over the alternatives `Reconnect on Disconnect (Auto Mode)` and `Always AutoConnect`.
- _Rejected alternatives (kept for traceability only):_ `Reconnect on Disconnect (Auto Mode)`, `Always AutoConnect`.

#### 11.13.2 Default state (USER-DIRECTED REQUIREMENT — CONFIRMED)

- **Final default: ENABLED (فعال).**
- **Justification:** The user has explicitly chosen **ENABLED** as the default. The rationale is that a user who has started Auto Mode has expressed the intent to be connected; if the connection later drops unexpectedly, silently staying disconnected does not match that intent. Auto Mode is therefore expected to attempt to re-establish connectivity automatically (same server, then next server in the priority order) unless the user has explicitly disabled this option.
- **User-toggleable:** The option remains user-toggleable. Users on metered or censored networks who prefer manual control can disable it in Settings.
- Evidence class: **USER-DIRECTED REQUIREMENT (CONFIRMED)** — the user explicitly overrode the previously proposed DISABLED default.

#### 11.13.3 Behavior when ENABLED

- **Trigger:** The app is connected to a server **in Auto Mode** (the tunnel was established by an AutoConnect run, not a manual connect) and that server **disconnects unexpectedly** (a `STATE_DISCONNECTED` / `STATE_ERROR` not initiated by the user).
- **Expected behavior:**
  1. The app automatically attempts to reconnect — **first to the same server**, trying the configured protocols in the user's priority order (R6).
  2. If the same server cannot be re-established (its protocol attempts exhausted within the per-protocol timeout, R5), the app proceeds to the **next server in the sorted list** (the same sorted list used by the original AutoConnect run, R3/R4).
  3. The reconnection respects the same stop conditions as a fresh AutoConnect run (R7): it continues until a verified tunnel is up, the sorted list is exhausted, or the user presses the AutoConnect button again to stop.
- **Fallback path:** If reconnection to the same server fails and the sorted list is exhausted, Auto Mode ends with `ERROR_NO_SERVER` (R7a) — no further silent retries beyond the documented list.

#### 11.13.4 Behavior when DISABLED

- **Trigger:** Same as 11.13.3 (an Auto Mode tunnel disconnects unexpectedly).
- **Expected behavior:** The app **remains disconnected.** No automatic reconnection attempt is made. The user must manually press AutoConnect (or the relevant control) to reconnect.
- **Relationship to existing flow:** This setting governs behavior **after** an Auto Mode tunnel is established and drops; it does not change the AutoConnect start contract (R1–R7). It is distinct from `ConnectionController`'s per-attempt `MAX_RECONNECT_ATTEMPTS = 2` / `RECONNECT_DELAY_MS = 1000L` (§17), which are within a single connect attempt's native retry, not Auto Mode-level server rotation after an unexpected drop.

#### 11.13.5 Testable contract

- **ENABLED + unexpected drop on server S** → app issues a reconnect to S (same protocols, priority order) before any other server.
- **ENABLED + S unreachable on all protocols** → app proceeds to next sorted server.
- **DISABLED + unexpected drop** → app issues no reconnect; state returns to disconnected/idle.
- **Either state + user re-press of AutoConnect during reconnection** → reconnection cancelled (R7b).

---

## 12. Remaining Work

### 12.1 F.28AD-A (In Progress)

Stage 2F-F.28AD-A is the active development stage. Specific scope: _[Details to be defined by the active development task.]_

### 12.2 Physical Device Verification

The F.28 lifecycle fixes were developed and unit-tested on the JVM, but several races (F.28M, F.28H, F.28I) were originally observed on physical devices. The following scenarios require real-device verification:

1. **F.28M repro:** Start a connect that fails → verify "VPN connection established" is NOT logged for the stopped session
2. **Double-connect during native busy:** Trigger a connect while a previous native operation is unresolved → verify Auto Mode does NOT rotate servers
3. **Service kill during teardown:** Force-stop the app during `destroyResources()` → verify the TUN interface is released and no native handle leaks
4. **Concurrent Action_CONNECT / Action_DISCONNECT:** Rapidly toggle connect/disconnect → verify no orphaned tunnel or zombie connect
5. **ANR regression:** Verify no `softether_disconnect` blocking on main thread during `onDestroy`

> **F.28AL-U physical verification — BLOCKED [INFERENCE]:** F.28AL-V established that the installed APK predates the F.28AL-U single-owner classes (authoritative dex dump: 0 occurrences of `NativeHandleSlot`/`NativeHandleOwner`/`NativeHandleClaim`/`NativeAttemptCoordinator`/`NativeLifetimeRegistry`), and F.28AL-W stopped at the release-signing gate (`RELEASE_SIGNING_UNAVAILABLE`) before any build or install. Physical verification of the F.28AL-U invariant therefore **remains outstanding** and cannot proceed until an authorized, certificate-compatible build is available. See §3.3 for full status and the two-option next-step dependency (CI-based vs. authorized debug-signed verification).

### 12.3 Test Baseline Gaps

| Test target | Status |
|-------------|--------|
| `NativeAttemptCoordinatorTest` | Covers Cases A–F, F.28AC A–D, terminal destroyed flag, 50-iteration mutual-exclusion races, flow-registration races (20 tests) |
| `AutoModeNativeBusyTest` | Covers NATIVE_BUSY stopping attempt loop without server rotation (16 tests) |
| `TeardownWaiterIdentityTest` | Covers exact attempt-ID matching, obsolete completion rejection, foreign-stack bypass (14 tests) |
| `AutoModeLifecycleF28Test` | Covers F.28 lifecycle transitions under Auto Mode |
| `AutoModeEndToEndTest` | End-to-end Auto Mode flow with fake adapter |
| `AutoModeControllerTest` | Core controller logic tests |
| Live-device integration | **Not covered** — requires physical device for ANR/race verification |

### 12.4 Known Residual Risks

1. **TunnelStateWatcher recency-only validation (F.28C audit, lower severity):** Tunnel completions are scoped by session generation in the adapter, not by attempt ID in the watcher. This is a deliberate scope limitation — traffic updates cannot cancel jobs or destroy controllers, so widening tunnel-state filtering would be scope creep for a cosmetic-only risk.
2. **L2TP/IPsec deprecation:** Only functional on Android 12 and below. Should eventually be removed or replaced.

#### RISK-A — Conditional Release Signing May Fall Back to Debug Signing (CONFIRMED BY SOURCE AND OBSERVED IN PRACTICE)

- **Evidence:** SOURCE CODE EVIDENCE. `app/build.gradle` (~line 41) guards the release `pro`
  signing config on `keystoreFile.exists() && signPassword != null`; the `else` branch (~lines
  48-56) silently falls back to the **debug** signing config, and (~line 63) applies
  `signingConfigs.pro` to all `pro` variants including `proRelease`. Same logic referenced by
  F.28AL-W at the same anchors. **Status:** `CONFIRMED BY SOURCE` (no build run to reproduce).
- **Observed in practice (RISK-A escalation, F.28AL-Y-PREP / F.28AL-Y-FULL — PHYSICAL DEVICE EVIDENCE + INFERENCE):**
  1. **F.28AL-Y-PREP:** the signing certificate of the currently installed package (2.4.10/143) is
     byte-identical to the local **debug keystore** certificate (SHA-256
     `247423ce8d62f0dfe15938f10e80798bd328857a50af0883e3a800dc10319e9a`), proving the installed
     distribution package was **debug-signed**, not release-signed.
  2. **F.28AL-Y-FULL:** the post-install `versionName` is `2.4.10-dev`; the `-dev` suffix confirms a
     debug build was installed.
  - RISK-A is therefore **no longer a theoretical concern** — it has **materialized on the
    development device**. The distribution package shipped to that device was debug-signed.
- **Risk (INFERENCE):** when the release keystore/password is unavailable — the current local
  condition — a `proRelease` build would be **debug-signed** without an explicit warning, producing
  an APK whose certificate does not match the distribution package. This is the mechanism behind the
  F.28AL-W blocker and the observed debug-signed install. **No invalid release APK was built or
  published by F.28AL-W/X/Y-PREP** (Y-FULL intentionally installed a debug build for verification).
- **Future decision (separately authorized, NOT done here):** make signing **fail-closed** (reject
  `proRelease` when release signing material is unavailable) versus an explicit, logged policy.
- **Release-build hygiene remediation (applied):** commit `e1132ad` (`build(native): exclude test
  library from release build`) — the FIX-L01 CMake change — stops the native **test** library from
  being packaged into release builds. This does not fix the signing fallback itself; it removes a
  separate release-hygiene defect (shipping test code) that was found during the same audit.

#### RISK-B — Legacy Native Cleanup Method May Bypass NativeHandleSlot

- **Evidence:** SOURCE CODE EVIDENCE. `SoftEtherClient.disconnect()` (`SoftEtherClient.kt` ~line 46)
  performs a direct `nativeDisconnect` then `nativeDestroy` then `nativeHandle = 0` sequence (~lines
  52-54) that does **not** go through `NativeHandleSlot`.
- **Caller status:** read-only caller analysis across F.28AL-V/W/X found **no production caller**;
  current `.disconnect()` call sites resolve to the Android adapter/service disconnect flow, and
  `ConnectionController` reaches native destroy only through the slot claim path.
  **Status:** `PARTIALLY CONFIRMED — REQUIRES DEEPER ANALYSIS`. F.28AL-X clarification checked 14
  `.disconnect()` call sites across the tracked tree; none resolves to `SoftEtherClient.disconnect()`
  and no production caller of that method was found. Receiver-type resolution strongly indicates no
  active caller, but interface dispatch / reflection were not exhaustively analyzed.
- **Why it matters (INFERENCE):** if ever invoked it would be a second, unsynchronized native
  cleanup path bypassing the F.28AL-U single-owner invariant. It is **not** proven to be an active
  production defect and is treated as legacy/dead code pending confirmation.
- **Action:** **No source change made.** Requires a separate read-only call-graph audit or future
  hardening decision (OUT OF SCOPE for F.28AL-W and F.28AL-X).

#### FINDING T5-SLOT — Native slot unresolved after user skip (2026-10-13, RESUME-BATCH-DEVICE-VERIFY T5)

- **Observed (PHYSICAL DEVICE EVIDENCE):** after a user-initiated "Try next server" during an active
  AutoMode connection, the reconnect to the next server was refused twice by
  `[AUTO] Native slot busy; retrying same server in 2s (attempt 1/2)` and
  `(attempt 2/2)`, then stopped with
  `[AUTO] Native slot still owned by an unresolved operation; stopping without blaming this server`.
  Teardown then completed cleanly: `Native cleanup confirmed (attempt=3, session=1): structure
  destroyed and slot released`. The candidate server (`vpn421485820`) was therefore never given a
  fair attempt.
- **Interpretation (INFERENCE):** this is **designed guard behaviour, not a regression.** The
  slot-ownership guard correctly detected that a native operation had not been confirmed resolved and
  refused to admit a new attempt; it also correctly **declined to blame the candidate server**, since
  the server was never actually tried. The G-1 stale-service FAIL criterion did **not** appear in this
  window, so this is not the stale-session leak. The final state was a clean, retryable
  `DISCONNECTED`/`ERROR` with a working `Tap to retry`.
- **Gap (UNKNOWN):** the log does **not** identify **which** operation held the slot. Whether the
  holder was a benign slow release (self-clearing on the next attempt) or a genuinely leaked slot (a
  lifetime bug) is **not determinable from the current logs**. This distinction matters: a benign
  slow release needs no fix, whereas a leaked slot would be a second, undiscovered lifetime defect.
- **Action:** to be investigated in **FIX-NATIVE-LIFETIME-SYNC (N-15 RUDP destroy vs poll
  synchronisation; N-16 `nativeConnect` serialisation)** — see §12.5. Consider enriching
  `NativeLifetimeRegistry.describe()` or the slot guard's log message to include the holder's
  `attemptId`, `phase`, and elapsed-since-declared, so the holder is identifiable in future logs.
- **Evidence class:** PHYSICAL DEVICE EVIDENCE (verbatim log lines in
  `RESUME_BATCH_DEVICE_VERIFY_REPORT.md` §11).

#### OPEN QUESTION — Dual-Path Cleanup Overlap: Designed Behavior or Incidental Race?

- **Question (OPEN — REQUIRES SOURCE REVIEW):** Is the dual-path cleanup overlap (service
  `DISCONNECT` intent + inline fast-cleanup) a **deliberately designed behavior**, or an
  **accidental race** that happens to fire on every disconnect?
- **Context (PHYSICAL DEVICE EVIDENCE, F.28AL-Y-FULL Test A):** the Test A logcat shows both cleanup
  paths firing within ~80 ms during a **normal** disconnect, with `NativeHandleSlot` serializing the
  second path as `NOOP_REENTRANT` (ownership retained, new native attempt refused) and then
  confirming cleanup and releasing the slot. `nativeDestroy` was invoked exactly once.
- **Why it matters (INFERENCE):** if the overlap is *designed* (two legitimate callers that the slot
  is meant to arbitrate), the F.28AL-U debug observation directly demonstrates the single-owner
  invariant working as intended. If the overlap is *incidental* (a redundant second cleanup trigger
  that should not exist), then the observation shows the invariant masking an underlying design
  issue that deserves separate attention. This distinction affects how strongly the debug-build
  evidence supports the F.28AL-U invariant.
- **Status:** OPEN. Requires a read-only source review of the reconnect/teardown call paths to
  determine intent. **No source change made in this phase.**

#### F.28AL-X — Signing Environment Audit Results (documentation only)

- **CI workflow files:** `.github/workflows/android-ci.yml` PRESENT (10974 bytes; last commit 7185edf,
  2026-09-28); `.gitlab-ci.yml` PRESENT at repo root (2652 bytes). See the GitLab-coexistence note below.
- **gh CLI:** PRESENT and AUTHENTICATED (account/host identifiers redacted; no token printed).
- **CI classification:** `CI PATH EXISTS — ACCESS NOT CONFIRMED` (a workflow file and an authenticated
  `gh` exist, but permission to run the release-signing job was not confirmed; no run was initiated).
- **Local release signing:** `RELEASE SIGNING MATERIAL NOT AVAILABLE` — `keyStore/release.jks` ABSENT
  (only `release.jks.gpg`, 8959 bytes, present); `signKeyPassword` ABSENT from `local.properties`,
  root `gradle.properties`, and `~/.gradle/gradle.properties` (NOT ACCESSIBLE / not present); env
  `SIGN_KEY_PASSWORD` and `ORG_GRADLE_PROJECT_signKeyPassword` both NOT SET.
- **GPG:** `GPG TOOL UNAVAILABLE` — `gpg` executable not on PATH, so `release.jks.gpg` cannot be
  decrypted locally (no key-listing or decryption attempted).
- **Evidence class:** SOURCE CODE EVIDENCE (repository file presence + local build-environment +
  CI-configuration inspection). **Not PHYSICAL DEVICE EVIDENCE.** No build, APK, keystore export,
  GPG decrypt, CI run, or device interaction was performed.

**`.gitlab-ci.yml` coexistence (UNKNOWN):** the file exists at the repo root even though the active
remote is GitHub (`https://github.com/morteza-taheri/SoftEthergai.git`, credentials stripped). Its
purpose and whether it is active is **UNKNOWN / NOT VERIFIABLE FROM AVAILABLE EVIDENCE**; no further
investigation was performed in this phase.

### 12.5 Roadmap: Dynamic Server-Source Access

The following items are planned for the source-and-path architecture described in §11.8–§11.12. Do not arbitrarily assign release numbers or mark these items complete. Use the document's existing priority conventions; if priority is unclear, mark it "Priority to be assessed" rather than inventing a commitment.

#### 12.5.1 Active Fix Queue (DEEP-REVIEW + F.28AL follow-up)

| # | Item | Scope | Priority | Status |
|---|------|-------|----------|--------|
| 1 | **FIX-NATIVE-LIFETIME-SYNC (N-15 + N-16)** | N-15: `rudp_destroy` (`rudp_destroy:181`) destroys `ctx->lock` and frees `ctx` with no guarantee a concurrent `rudp_poll` (`rudp_poll:693`) has returned — a poll in flight during destroy is a use-after-free. N-16: `nativeConnect` (`softether_jni.c:54`) is **not** serialised by `conn->connect_mutex` while `nativeConnectWithHub` (`softether_jni.c:139–145`) is — two concurrent native connects on the same `conn` can interleave. | **P1_HIGH** | OPEN — no device required. Driven additionally by **FINDING T5-SLOT** (§12.4). See `DEEP_REVIEW_VERIFY_REPORT.md` rows N-15/N-16 |
| 2 | **DEEP-REVIEW P0 set** | JNI bounds (N-01…N-05, N-08), RUDP lock release, reconnect terminal routing (G-1), teardown log identity (G-2) | — | **CLOSED — device-verified.** RESUME-BATCH-DEVICE-VERIFY: 6/6 native tests PASS, T2–T8 clean. Evidence: PHYSICAL DEVICE EVIDENCE |
| 3 | `proRelease` build/test path | The release variant has never been built or tested locally (only `proDebug`). Blocked by the RISK-A signing fallback (§12.4) and the absence of local release signing material. | Priority to be assessed | OPEN — see RISK-A |
| 4 | **N-06** TLS certificate verification | `aes_wrapper.c:110` `ssl_configure_client_ctx` sets `SSL_CTX_set_verify(c, SSL_VERIFY_NONE, NULL)`. Deliberate for self-signed VPNGate certs, but a genuine MITM exposure. | P2_MEDIUM | OPEN — design decision required |
| 5 | **N-09** non-cryptographic `rand()` | `softether_rudp.c:154–173` derives keys/cookies from `rand()`; `rudp_transport.c:316,370,402,1146` derive IVs/padding/ICMP id/seq from `rand()`. The generator is not cryptographically secure. | P2_MEDIUM | OPEN — migrate to `RAND_bytes` |
| 6 | **RISK-A** conditional release signing | See §12.4. | Priority to be assessed | OPEN — `CONFIRMED BY SOURCE AND OBSERVED IN PRACTICE` |
| 7 | **RISK-B** legacy `SoftEtherClient.disconnect()` bypass | See §12.4. | Priority to be assessed | OPEN — `PARTIALLY CONFIRMED — REQUIRES DEEPER ANALYSIS` |

#### 12.5.2 Dynamic Server-Source Access roadmap

| # | Item | Description |
|---|------|-------------|
| A | **Dynamic network-path selection for server-list retrieval** | Direct path + active VPN path, with future extensibility. Source identity and network path modeled as separate concerns. |
| B | **Emergency Server Sources settings** | User-configurable URLs, persistent configuration, enable/disable, validation. See §11.9. |
| C | **Canonical emergency CSV schema** | A separate design task. Explicitly NOT YET DEFINED. See §11.9.3. |
| D | **Manual server entry** | User-entered individual server records/configurations. See §11.10. |
| E | **Server-list UI entry point** | Add Server row, floating action button, or another appropriate placement to be decided. See §11.10.2. |
| F | **Text/file/share import** | Paste, manual fields, supported file import, Android Sharesheet/Intent, phased by supported formats. See §11.10.3. |
| G | **Safe merge and persistence** | Preserve manual entries, source provenance, deduplication, and existing historical records. See §11.11. |
| H | **Tests** | Unit, integration, and Android runtime tests for source/path combinations, fallback, filtering/unreachable sources, malformed input, duplicate handling, and preservation of manual records. See §12.6. |

### 12.6 Test Requirements for Source/Path Architecture

Tests must cover at minimum:
- Source/path combinations and fallback behavior
- Filtering of unreachable sources
- Malformed input handling
- Duplicate record handling
- Preservation of manual records across source refreshes

Do not mark any of these tests as implemented or verified merely because they have been documented here.

### 12.7 Acceptance Criteria for Source/Path Architecture

The following criteria must be satisfied when the source-and-path architecture (§11.8–§11.12) is implemented:

1. The app is not hard-wired to use only the direct network or only the VPN tunnel for server-list retrieval.
2. Source identity and network path are modeled as separate concerns.
3. A source failing on one path can still be tried on another eligible path.
4. One source/path failure does not delete existing server records.
5. User-configured emergency source URLs can be stored and managed.
6. The emergency CSV format remains explicitly undefined until a separate schema decision is approved.
7. Manual server entry is supported as a distinct feature from emergency-source URLs.
8. Manual entries survive normal source refreshes.
9. Duplicate handling preserves useful data and does not silently discard a user's manual entry.
10. New sources such as GitHub can be added without redesigning the whole pipeline.
11. Unsupported or malformed input is rejected safely with a useful user-facing explanation.
12. Tests cover source/path fallback and data-retention behavior.

Do not mark any of these criteria as satisfied merely because they have been documented here.

---

## 13. Physical Device Verification Plan

### 13.1 Environment

- **Device:** Physical Android device (API 24–37 for coverage)
- **Build:** Debug variant (`assembleFreeDebug` or `assembleProDebug`)
- **Network:** Controlled environment (local SoftEther server with configurable disconnect timing)

### 13.2 Test Scenarios

| # | Scenario | Expected Behavior |
|---|----------|-------------------|
| 1 | Rapid connect/disconnect toggle (F.28M) | "VPN connection established" NOT logged after teardown |
| 2 | Connect while native slot held (F.28AA) | Auto Mode refuses locally, does NOT rotate servers |
| 3 | App kill during `destroyResources()` (F.28F) | No ANR; TUN released; native handle freed on process death |
| 4 | `softether_disconnect` blocking (F.28F) | Runs on `Dispatchers.IO`, never main thread |
| 5 | Obsolete session error callback (F.28C) | Does not tear down live replacement session |

### 13.3 Diagnostic Signals

- **Logcat tag:** `SoftEtherVpnService` — primary lifecycle logging
- **`NativeLifetimeRegistry.describe()`:** Printed in refusal messages (e.g., `"attempt=3 phase=TEARDOWN, reporting deadline expired (still blocked)"`)
- **`TunnelStateWatcher` replay traces:** `beginAttempt`/`endAttempt` boundaries
- **`NativeAttemptCoordinator` logs:** `"Native lifecycle unresolved: ..."` on UNRESOLVED outcomes

---

## 14. Test Baseline

### 14.1 Test Module Split

| Module | Test type | Scope |
|--------|-----------|-------|
| `SoftEtherClient/src/test/` | JVM unit tests | Native lifetime, session policy, teardown completion, TUN ownership |
| `app/src/test/` | JVM unit tests | Auto Mode, collector, merger, quality, reachability, SSTP port, Room migration |
| `SoftEtherClient/src/androidTest/` | Instrumented tests | Live native connection benchmarks (requires device) |

### 14.2 Key Test Files

| Test file | Module | Tests | Coverage |
|-----------|--------|-------|----------|
| `NativeAttemptCoordinatorTest.kt` | SoftEtherClient | 20 | Cases A–F, F.28AC A–D, terminal flag, mutual exclusion races, flow registration |
| `AutoModeNativeBusyTest.kt` | app | 16 | NATIVE_BUSY stops attempt loop without server rotation |
| `TeardownWaiterIdentityTest.kt` | app | 14 | Exact attempt-ID matching, obsolete rejection, foreign-stack bypass |
| `AutoModeLifecycleF28Test.kt` | app | — | F.28 lifecycle transitions under Auto Mode |
| `AutoModeEndToEndTest.kt` | app | — | End-to-end with fake adapter |
| `AutoModeControllerTest.kt` | app | — | Core controller logic |
| `SoftEtherSessionPolicyTest.kt` | SoftEtherClient | — | Start/teardown decision matrix |
| `NativeLifetimeRegistryTest.kt` | SoftEtherClient | — | Registry admission, lease, phase transitions |
| `TunOwnershipCoordinatorTest.kt` | SoftEtherClient | — | TUN ownership claim/release |
| `MergerOracleTest.kt` | app | — | Merge conflict visibility, source priority |
| `ApiParserOracleTest.kt` | app | — | CSV API parsing, port provenance |
| `HtmlParserOracleTest.kt` | app | — | HTML feed parsing |
| `DatasetIntegrityTest.kt` | app | — | Dataset quality evaluation |
| `ServerListUpdaterTest.kt` | app | — | Repository refresh + fallback chain |
| `SstpPortPresentationTest.kt` | app | — | SSTP port description (J-4/L-3) |
| `SstpPersistenceTest.kt` | app | — | SSTP port Room persistence/migration |
| `SstpMigration4To5Test.kt` | app | — | Room 4→5 schema migration |
| `ServerReachabilityTesterTest.kt` | app | — | Reachability gate, probe transport |
| `TunnelLivenessWatchdogTest.kt` | app | — | Tunnel liveness probe |
| `VpnConnectionPortProvenanceTest.kt` | app | — | Port provenance mapping from record to connection |
| `CollectorSnapshotStoreTest.kt` | app | — | Snapshot read/write/migrate |
| `VpnLastConnectionStoreTest.kt` | app | — | Last-connection persistence |

### 14.3 Test Command

**SoftEtherClient unit tests:**
```bash
./gradlew :SoftEtherClient:test
```

**App unit tests:**
```bash
./gradlew :app:test
```

**Note:** Test counts (e.g., SoftEtherClient ~121 tests, App ~583 tests including 1 skipped) are HISTORICAL baselines from the audit. The current working tree has unstaged modifications; exact counts should be re-verified by running the test commands above.

---

## 15. Release / Git Discipline

### 15.1 Branch and Commit Policy

- **Active development branch:** `main`
- **Workflow:** Direct commits to `main` are acceptable for small changes; larger work should use feature branches
- **Commit messages:**
  - Documentation-only changes (plan docs, AGENTS.md, README, etc.) in the parent repo must use `[skip ci]` prefix
  - Doc-only commits inside `SoftEtherClient` may use `[skip ci]` when they would otherwise trigger CI
  - **Code changes must NOT use `[skip ci]`**

### 15.2 Submodules

This project has **no submodules**. The `.gitmodules` file does not exist. (The AGENTS.md references `SoftEtherClient` as a submodule in its examples, but this project uses a direct module dependency, not a submodule.)

### 15.3 Release Variants

| Variant | Build command | Notes |
|---------|---------------|-------|
| Free Debug | `./gradlew assembleFreeDebug` | |
| Pro Debug | `./gradlew assembleProDebug` | |
| Free Release | `./gradlew bundleFreeRelease` | AAB format |

### 15.4 Release Keystore

- Production keystore (`keyStore/release.jks`) is blackbox-encrypted, deployed only on CI
- `SIGN_KEY_PASSWORD` env var (CI) or `signKeyPassword` in `local.properties` (developer machines)
- Falls back to debug keystore when release keystore is absent

### 15.5 AGP / Library Upgrade Notes

- Upgrading AGP requires updating the Gradle wrapper version and the Kotlin plugin version together
- Compose compiler is pinned to the Kotlin version (`$kotlin_version`)
- KSP 2.3.2 is applied at the root level for Room schema generation

---

## 16. Rules for Future AI Agents

### 16.1 Scope Boundaries

1. **No code changes without explicit instruction.** This document is the specification; modifications to source files require a directed task.
2. **No build configuration changes.** Do not modify `build.gradle`, `gradle-wrapper.properties`, dependencies, or version numbers without explicit approval.
3. **No R8/ProGuard changes.** The SoftEther native library is sensitive to symbol renaming and dead-code elimination.
4. **No Room schema changes without migration.** If a `VPNGateItem` field is added/removed/changed, a migration must be written and tested (`SstpMigration4To5Test.kt` shows the pattern).
5. **No UI/resource changes without design review.** String resources, icons, and layout changes affect all protocol flows.

### 16.2 Native Code Rules

1. **`softether_force_close_socket` must never close the fd.** It calls `shutdown()` only. The connect thread closes the fd via `softether_disconnect()`. See line 1098–1114.
2. **`connect_mutex` is per-connection.** Never assume it serializes across connections. Process-wide safety comes from `NativeLifetimeRegistry`.
3. **`nativeDestroy` returning is not evidence the connect flow exited.** Use `NativeAttemptCoordinator` to track flow lifetime.
4. **Never hold `NativeLifetimeRegistry.lock` across a blocking native call.** Admission is a single short `synchronized` block.
5. **A `null` lease can never release ownership.** Use `confirmTeardownWithoutLease()` only for controllers destroyed before native code ran.

### 16.3 Teardown Completion Rules

1. **Only `TeardownCompletion.Outcome.NATIVE_CONFIRMED` permits a new native operation.** `NATIVE_UNRESOLVED` retains ownership and blocks the next start.
2. **`NOOP_REENTRANT` is not a new teardown.** It reports terminal state publication but carries no native-confirmation claim.
3. **`FOREIGN_STACK_TERMINAL` is not sequenced.** Its `attemptId` is `0` and it is filtered out of the SoftEther monotonic sequence by `isSequenced()`.
4. **Accept completions strictly newer than the last accepted.** `TunnelStateWatcher` enforces this.

### 16.4 Auto Mode Rules

1. **A NATIVE_BUSY refusal is NOT a server failure.** It must never trigger server rotation. The refusal is scoped to `sessionPolicy.sessionGeneration`.
2. **Arm the tunnel watcher BEFORE the connect request.** F.28 Fix D requires `armTunnelWatch` → `beginAttempt` → `connect` ordering.
3. **`beginAttempt` must replay an already-terminal state synchronously** to avoid missing a fast `CONNECTED`. The `CompletableDeferred` is created before `beginAttempt` for this reason.
4. **An empty protocol priority list means "refuse", not "default."** The user must have enabled at least one protocol.
5. **Never fabricate an RTT.** `activeRttMs` must be `null` when unmeasured, never `0`.
6. **Never borrow a port from another protocol.** Each protocol uses its own published port or provenance-derived default.

### 16.5 Data Model Rules

1. **`UNKNOWN` is the only unknown provenance.** Canonical literal. `VpnPortSource.normalized()` folds all variants onto it.
2. **A port of `0` with `UNKNOWN` provenance is valid and meaningful** (source confirmed capability but published no port).
3. **Never infer a port from `UNKNOWN` provenance.** `sstpConnectPort` returns `0` for `UNKNOWN`, not `443`.
4. **Never overwrite meaningful data with a falsy value.** `VpnRecords.setField` and `VpnServerMerger.recursiveMerge` both enforce this.
5. **Conflicts are recorded, never silently resolved.** `VpnServerMerger.recordConflict` creates a visible audit trail.

### 16.6 Testing Rules

1. **JVM tests must remain Android-free.** `AutoModeController` and `SoftEtherSessionPolicy` are explicitly designed for pure-JVM testing. Do not introduce Android framework dependencies.
2. **Tests must not depend on global state.** `NativeLifetimeRegistry.process` is a process-wide singleton; tests construct their own instance.
3. **`rewindTeardownSequenceForTests()` is a test-only escape valve.** It guards on `armedTeardown == null` only — do not replicate its logic in production code.

---

## 17. Key Constants Summary

| Constant | Value | Location | Purpose |
|----------|-------|----------|---------|
| `NATIVE_CLEANUP_CONFIRM_TIMEOUT_MS` | 2_000L | `SoftEtherVpnService.kt:112` | Teardown wait for native cleanup confirmation |
| `NATIVE_TEARDOWN_TIMEOUT_MS` | 500L | `ConnectionController.kt:78` | Advisory wait for connect flow exit during teardown |
| `TEARDOWN_TIMEOUT_MS` | 5_000L | `AndroidConnectionAdapter.kt:56` | Overall disconnect timeout |
| `MAX_RECONNECT_ATTEMPTS` | 2 | `ConnectionController.kt:74` | Max reconnection attempts |
| `RECONNECT_DELAY_MS` | 1_000L | `ConnectionController.kt:75` | Delay between reconnect attempts |
| `STATS_INTERVAL_MS` | 1_000L | `ConnectionController.kt:76` | Traffic stats publishing interval |
| `RX_BATCH_MAX_PACKETS` | 32 | `ConnectionController.kt:77` | Max packets per receive batch |
| `DEFAULT_ATTEMPT_TIMEOUT_MS` | — | `AutoModeController.kt` | Auto Mode per-attempt timeout |
| `MAIN_TIMEOUT_MS` | 45_000L | `VpnServerRepository.kt:44` | Collector main source timeout |
| `MIRROR_TIMEOUT_MS` | 30_000L | `VpnServerRepository.kt:45` | Collector mirror timeout |
| `RAW_CAPTURE_LIMIT` | 512 * 1024 | `VpnServerRepository.kt:46` | Debug panel raw capture limit |
| `MAX_MIRRORS` | 12 | `VpnGateSources.kt:28` | Max mirrors to use per collection |
| `SSTP_DEFAULT_PORT` | 443 | `VpnProtocolDefaults` | MS-SSTP protocol-standard port |
| `L2TP_PORT` | 1701 | `VpnProtocolDefaults` | L2TP IANA-assigned port |
| `SCHEMA_VERSION` | "4.0" | `VpnRecords` | Server record schema version |
| `NO_ATTEMPT` | 0L | `NativeLifetimeRegistry` | Sentinel for idle registry |
| `DEFAULT_ID` | "SOFTETHER_UDP" | `AutoModeProtocol` | Default Auto Mode protocol |

---

## 18. File Index (Key Files)

### 18.1 SoftEtherClient Module (Native Protocol)

| File | Path |
|------|------|
| `SoftEtherVpnService` | `SoftEtherClient/src/main/java/vn/unlimit/softether/SoftEtherVpnService.kt` |
| `ConnectionController` | `SoftEtherClient/src/main/java/vn/unlimit/softether/controller/ConnectionController.kt` |
| `SoftEtherClient` (JNI bridge) | `SoftEtherClient/src/main/java/vn/unlimit/softether/client/SoftEtherClient.kt` |
| `NativeLifetimeRegistry` | `SoftEtherClient/src/main/java/vn/unlimit/softether/NativeLifetimeRegistry.kt` |
| `NativeAttemptCoordinator` | `SoftEtherClient/src/main/java/vn/unlimit/softether/controller/NativeAttemptCoordinator.kt` |
| `NativeHandleSlot` (F.28AL-U) | `SoftEtherClient/src/main/java/vn/unlimit/softether/controller/NativeHandleSlot.kt` |
| `TeardownCompletion` | `SoftEtherClient/src/main/java/vn/unlimit/softether/TeardownCompletion.kt` |
| `SoftEtherSessionPolicy` | `SoftEtherClient/src/main/java/vn/unlimit/softether/SoftEtherSessionPolicy.kt` |
| `TunOwnershipCoordinator` | `SoftEtherClient/src/main/java/vn/unlimit/softether/TunOwnershipCoordinator.kt` |
| `TunTerminal` | `SoftEtherClient/src/main/java/vn/unlimit/softether/terminal/TunTerminal.kt` |
| `SoftEtherTrafficSnapshot` | `SoftEtherClient/src/main/java/vn/unlimit/softether/SoftEtherTrafficSnapshot.kt` |
| `KeepAliveManager` | `SoftEtherClient/src/main/java/vn/unlimit/softether/client/protocol/KeepAliveManager.kt` |
| Native protocol C | `SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_protocol.c` |
| JNI bridge C | `SoftEtherClient/src/main/cpp/jni/softether_jni.c` |

### 18.2 App Module (Auto Mode + Collector)

| File | Path |
|------|------|
| `AutoModeController` | `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt` |
| `AutoModeEngine` | `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeEngine.kt` |
| `AndroidConnectionAdapter` | `app/src/main/java/vn/unlimit/vpngate/automode/AndroidConnectionAdapter.kt` |
| `AutoModeProtocol` | `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeProtocol.kt` |
| `AutoModeModels` | `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeModels.kt` |
| `TunnelStateWatcher` | `app/src/main/java/vn/unlimit/vpngate/automode/TunnelStateWatcher.kt` |
| `VpnServerRepository` | `app/src/main/java/vn/unlimit/vpngate/repository/VpnServerRepository.kt` |
| `VpnServerMerger` | `app/src/main/java/vn/unlimit/vpngate/merger/VpnServerMerger.kt` |
| `ServerQualityCalculator` | `app/src/main/java/vn/unlimit/vpngate/ranking/ServerQualityCalculator.kt` |
| `VpnGateSources` | `app/src/main/java/vn/unlimit/vpngate/data/remote/VpnGateSources.kt` |
| `VPNGateItem` (Room) | `app/src/main/java/vn/unlimit/vpngate/models/VPNGateItem.kt` |
| `VPNGateConnection` | `app/src/main/java/vn/unlimit/vpngate/models/VPNGatConnection.kt` |
| `VpnModels` (schema) | `app/src/main/java/vn/unlimit/vpngate/data/model/VpnModels.kt` |
| `VpnConnectionMapper` | `app/src/main/java/vn/unlimit/vpngate/repository/VpnConnectionMapper.kt` |
| `GlobalVpnTracker` | `app/src/main/java/vn/unlimit/vpngate/state/GlobalVpnTracker.kt` |
| `ConnectionConfig` | `SoftEtherClient/src/main/java/vn/unlimit/softether/model/ConnectionConfig.kt` |

### 18.3 Build Configuration

| File | Path |
|------|------|
| Root build | `build.gradle` |
| App build | `app/build.gradle` |
| SoftEtherClient build | `SoftEtherClient/build.gradle` |
| Gradle wrapper | `gradle/wrapper/gradle-wrapper.properties` |

### 18.4 Test Files

| File | Module | Path |
|------|--------|------|
| `NativeAttemptCoordinatorTest` | SoftEtherClient | `SoftEtherClient/src/test/java/vn/unlimit/softether/controller/NativeAttemptCoordinatorTest.kt` |
| `NativeHandleSingleOwnerTest` (F.28AL-U) | SoftEtherClient | `SoftEtherClient/src/test/java/vn/unlimit/softether/controller/NativeHandleSingleOwnerTest.kt` |
| `AutoModeNativeBusyTest` | app | `app/src/test/java/vn/unlimit/vpngate/automode/AutoModeNativeBusyTest.kt` |
| `TeardownWaiterIdentityTest` | app | `app/src/test/java/vn/unlimit/vpngate/automode/TeardownWaiterIdentityTest.kt` |
| `AutoModeLifecycleF28Test` | app | `app/src/test/java/vn/unlimit/vpngate/automode/AutoModeLifecycleF28Test.kt` |
| `AutoModeEndToEndTest` | app | `app/src/test/java/vn/unlimit/vpngate/automode/AutoModeEndToEndTest.kt` |
| `AutoModeControllerTest` | app | `app/src/test/java/vn/unlimit/vpngate/automode/AutoModeControllerTest.kt` |
| `NativeLifetimeRegistryTest` | SoftEtherClient | `SoftEtherClient/src/test/java/vn/unlimit/softether/NativeLifetimeRegistryTest.kt` |
| `SoftEtherSessionPolicyTest` | SoftEtherClient | `SoftEtherClient/src/test/java/vn/unlimit/softether/SoftEtherSessionPolicyTest.kt` |
| `TunOwnershipCoordinatorTest` | SoftEtherClient | `SoftEtherClient/src/test/java/vn/unlimit/softether/TunOwnershipCoordinatorTest.kt` |

---

## 19. References

- **README.md** — Project overview, features, build instructions
- **AGENTS.md** — Commit message conventions (`[skip ci]` for doc-only changes)
- **`.kilo/command/*.md`** — Kilo CLI command definitions
- **`.kilo/agent/*.md`** — Kilo agent configurations
- **`kilo.json`** — Kilo project configuration
- **`SoftEtherClient/IMPLEMENTATION_PLAN.md`** — Tracked (last commit 5e6ad4a, 2026-09-24). Purpose: implementation plan for the SoftEther Android client (phases, R-UDP/TCP transport notes, connection flow). Not previously referenced in this document. *SOURCE CODE EVIDENCE (repository file inspection).*
- **`SoftEtherClient/RUDP_IMPLEMENTATION_PLAN.md`** — Tracked (last commit 68c12d7, 2026-09-12). Purpose: planning for SoftEther R-UDP (UDP) transport. Notable finding (from the file itself): OpenVPN UDP and SoftEther R-UDP are separate independent listeners; the CSV `SEUdpPort` column does not imply R-UDP availability on that port; for `SETcpPort=0` ("UDP-only") servers the R-UDP/NAT-T transport is broken while OpenVPN UDP remains a separate working listener. Not previously referenced here. *SOURCE CODE EVIDENCE (repository file inspection).*

---

## 20. Document Status
### Changelog

| Version | Date | Author | Change |
|---------|------|--------|--------|
| 1.0.0 | 2026-10-06 | Audit | Initial specification created from F.28AD-B read-only audit. All sections populated from verified source inspection. |
| 1.0.1 | 2026-10-08 | Documentation | Added §11.8 Dynamic Server-Source Access (product requirement), §11.9 Emergency Server Sources (settings requirement), §11.10 Manual Server Entry (product requirement), §11.11 Persistence/merge/retention rules, §11.12 Future Extensibility, §12.5 Roadmap, §12.6 Test Requirements, §12.7 Acceptance Criteria. Emergency CSV schema remains explicitly undefined. |
| 1.0.2 | 2026-10-10 | Documentation | Integrated F.28AL lifecycle history: §3.1 rows for F.28AL-S/T/U/V/W; new §3.3 (F.28AL-U single-owner status, F.28AL-V dex finding, F.28AL-W `RELEASE_SIGNING_UNAVAILABLE` blocker, two-option next-step dependency); §4.1 `NativeHandleSlot` component row; §12.2 physical-verification-blocked note; §18.1 and §18.4 index entries; §20 evidence-category governance legend. **Physical verification of F.28AL-U remains outstanding.** No source/test/build/device change. |
| 1.0.3 | 2026-10-10 | F.28AL-X read-only signing-environment audit: CI path documented (access NOT confirmed), GPG tool unavailable locally, F.28AL-W release-signing blocker reconfirmed; registered RISK-A (conditional release-to-debug signing fallback) and RISK-B (legacy SoftEtherClient.disconnect() bypasses NativeHandleSlot, no known production caller). |
| 1.0.4 | 2026-10-11 | F.28AL-Y-DOCS documentation update after F.28AL-Y-PREP and F.28AL-Y-FULL: §3.1 rows for F.28AL-Y-PREP and F.28AL-Y-FULL; §3.3 F.28AL-U status refined to OPEN with three-of-four evidence layers (SOURCE satisfied, JVM TEST satisfied, PHYSICAL-DEBUG satisfied, PHYSICAL-RELEASE NOT YET OBTAINED) plus debug-build caveat; RISK-A escalated to "CONFIRMED BY SOURCE AND OBSERVED IN PRACTICE" (installed 2.4.10/143 proven debug-signed; post-install 2.4.10-dev); new OPEN QUESTION on dual-path cleanup overlap (designed vs incidental race); RISK-B preserved PARTIALLY CONFIRMED; §20 Verification Status updated for §13. No source/test/build/device change; documentation only. |
| 1.0.5 | 2026-10-12 | F.28AL-AUTOCONNECT-SPEC documentation-only update: new **§6.7 AutoConnect Button Contract (User-Directed Requirements)** documenting R1–R8 as testable contracts (empty-list message R1, disconnect-active-tunnel-first R2, probe+sort pre-pass R3, sequential one-by-one R4, per-server/per-protocol 5s timeout R5, Settings protocol priority R6, continuous-until-stop R7); new **§11.13 Automatic Reconnection in Auto Mode (Settings Requirement)** for R8 (proposed name `Reconnect on Disconnect (Auto Mode)`, proposed default DISABLED, enabled/disabled behavior). Cross-reference to F.28AL-AUTOCONNECT-BUG-DIAG (BUG 1/2/3) added in §6.7. No source/test/build/device change; documentation only. |
| 1.0.6 | 2026-10-12 | F.28AL-AUTOCONNECT-SPEC-R8: finalized R8 setting name (`Auto-Reconnect in Auto Mode` / «اتصال همیشکی اتوکانکت») and default (ENABLED); removed pending markers in §11.13.1/§11.13.2; updated §6.7 R8 cross-reference. No source/test/build/device change; documentation only. |
| 1.0.7 | 2026-10-13 | DOCS-UPDATE: consolidated session completion summary (§1.6), methodology lessons (§22), T5-slot finding, F.28AL session rows, roadmap update. No source/test/build/device change; documentation only. |
| 1.0.8 | 2026-10-13 | DOCS-UPDATE: recorded the post-v1.0.7 additions (§1.6.3) — C3 test-rule fix (`4c013e8`), C5 formal gating (deferred to `C5-TRIAGE-DEVICE`), T5-SLOT instrumentation (`5e51e37`, logging-only), T5 not reproduced (holder UNKNOWN), `test40` budget widening, and the 17-commit chain pushed to `origin/main` (`5c13603..5e51e37`, fast-forward, no force/tag). Corrected the `0714628` off-by-one (it is the first unpushed commit, not the chain base) and refreshed the header HEAD field. No new methodology lessons. No source/test/build/device change; documentation only. |

### Verification Status

| Section | Verification |
|---------|-------------|
| Project Baseline (§1) | VERIFIED — `build.gradle`, `gradle-wrapper.properties`, module structure |
| Architecture Overview (§2) | VERIFIED — source file inspection, now includes product requirement for dynamic server-source access per §11.8 |
| F.28 History (§3) | HISTORICAL — compiled from source comments (`Stage 2F-F.28X` markers) and audit notes; §3.3 F.28AL-U/V/W status is attributed to those phases, not independently re-verified here |
| Current Native Lifetime Model (§4) | VERIFIED — `NativeLifetimeRegistry.kt`, `NativeAttemptCoordinator.kt`, `softether_protocol.c`, `ConnectionController.kt` |
| Teardown Completion Identity (§5) | VERIFIED — `TeardownCompletion.kt`, `SoftEtherVpnService.kt` (lines 100–139, 434–807) |
| Auto Connect / Auto Mode (§6) | VERIFIED — `AutoModeController.kt`, `AndroidConnectionAdapter.kt`, `AutoModeProtocol.kt`, `TunnelStateWatcher.kt`, `AutoModeModels.kt` |
| Server Data Model (§7) | VERIFIED — `VpnModels.kt`, `VPNGateItem.kt`, `VPNGatConnection.kt` |
| Server Collector (§8) | VERIFIED — `VpnServerRepository.kt`, `VpnGateSources.kt`, `VpnServerMerger.kt`, `ServerQualityCalculator.kt` |
| Connection Flow (§9) | VERIFIED — `SoftEtherVpnService.kt` (full start/teardown/destroy paths) |
| Auto Mode Engine (§10) | VERIFIED — source inspection |
| Binding Design Decisions (§11) | VERIFIED — native code contract confirmed at `softether_protocol.c:1098` |
| Dynamic Server-Source Access (§11.8) | DESIGN — product requirement stated; current implementation observation preserved separately |
| Emergency Server Sources (§11.9) | DESIGN — settings requirement stated; CSV schema explicitly deferred |
| Manual Server Entry (§11.10) | DESIGN — product requirement stated; input formats not decided |
| Persistence/Merge/Retention (§11.11) | DESIGN — principles stated; database schema not designed |
| Future Extensibility (§11.12) | DESIGN — goal stated; no interfaces mandated |
| Remaining Work (§12) | PARTIAL — F.28AD-A scope pending definition; test gaps identified; roadmap and acceptance criteria added |
| Physical Device Verification (§13) | PARTIAL — F.28AL-U physically exercised on a debug build at F.28AL-Y-FULL (§3.3: Tests A/B PASS, C observed organically, D NOT REPRODUCED, zero crashes); release-signed (CI) physical verification still outstanding |
| Test Baseline (§14) | HISTORICAL — test file inventory verified; exact counts to be re-verified by running `./gradlew :SoftEtherClient:test` and `./gradlew :app:test` |
| Release/Git Discipline (§15) | VERIFIED — `AGENTS.md`, `build.gradle` |
| Rules for Future AI Agents (§16) | POLICY — derived from architecture and constraints |
| Constants Summary (§17) | VERIFIED — line-anchored |
| File Index (§18) | VERIFIED — glob-verified paths |
| References (§19) | VERIFIED — paths and artifact names cross-checked |
| Document Status (§20) | VERIFIED — changelog and verification tables maintained each revision |
| Session Completion Summary (§1.6) | HISTORICAL — commit chain and session outcome compiled from `git log` and the phase reports; the device-verification statement is attributed to RESUME-BATCH-DEVICE-VERIFY (PHYSICAL DEVICE EVIDENCE), not re-verified here |
| Post-v1.0.7 Additions (§1.6.3) | HISTORICAL — each bullet attributed to its commit or phase report (SOURCE CODE / PHYSICAL DEVICE / PRIOR REPORT); the push state and the `p2.4.10` tag observation were re-read from `git` at HEAD `5e51e37` |
| Build and Verification Methodology (§22) | HISTORICAL — each lesson attributed to the incident that produced it; §21 intentionally unassigned |

### Legend

- **VERIFIED** — Fact confirmed by direct source code inspection at the stated file/line
- **HISTORICAL** — Fact compiled from source comments, audit notes, or prior task context; not independently re-verified in this document's production
- **FUTURE WORK** — Requirement or task that has not yet been executed
- **PARTIAL** — Some aspects verified, others pending
- **POLICY** — Design rule derived from the architecture, not a directly inspectable artifact

**Evidence categories (governance — used throughout §3.3 to keep source/test/device/inference evidence distinct):**

- **SOURCE CODE EVIDENCE** — Confirmed by direct inspection of source files at a stated path/line.
- **TEST EVIDENCE** — Produced by an automated JVM/harness test. Never proof of production behavior on a physical Android device.
- **PHYSICAL DEVICE EVIDENCE** — Observed on the authorized physical device (logcat, `dumpsys`, dex dump of the installed binary, etc.).
- **INFERENCE** — A conclusion reasoned from other evidence; never a directly observed fact.
- **UNKNOWN / NOT OBSERVABLE / NOT TESTABLE / NOT REPRODUCED** — The claim could not be established by the required evidence category; recorded explicitly rather than fabricated.

---

## 22. Build and Verification Methodology (Lessons)

> Section number **22** is reserved for this content; **§21 is intentionally unassigned** in this
> revision (no section was removed or renumbered). Each lesson below was **verified by an incident in
> this project**, not adopted as a generic best practice. Each entry states the rule, why it matters,
> and the incident that proved it.

### L1 — Forced compile is mandatory

`--rerun-tasks` defeats `UP-TO-DATE` masking: a Gradle task reported as `UP-TO-DATE` did not run, so
its success proves nothing about the current source tree. A cached compile result is not evidence
that the tree compiles. Any compile claim must come from a run in which the relevant tasks actually
**executed**.
**Incident:** VERIFY-STACK-CLOSURE — *40/40 tasks executed* is what proves the tree compiles; a
40/40 *up-to-date* report would have proven nothing.

### L2 — Forced cache-disabled tests are mandatory

`--rerun-tasks` **alone is not sufficient** for `testDebugUnitTest`: the test task can still be
satisfied from the build cache and replay a stale result. The correct invocation is
`--rerun-tasks --no-build-cache`. Always force **both** flags.
**Incident:** COMMIT-BATCH-F28AL-GROUP2 — the test task replayed from cache under `--rerun-tasks`
alone, producing a green result that reflected old code.

### L3 — KDoc references are not dependencies

A text match inside a comment or KDoc block does **not** imply a compile dependency. Grepping for a
class name and finding a hit in prose tells you nothing about imports. Dependency claims must be
checked at the **import level**, not by substring search.
**Incident:** COMMIT-BATCH-F28AL-GROUP2 P3 — `NativeLifetimeRegistry` mentions
`SoftEtherSessionPolicy` only in prose; the grep hit was not a dependency.

### L4 — Dependency closure before commit is mandatory

Every import in every committed file must resolve **inside the same commit or inside HEAD**. A commit
that references a class it does not itself contain is broken for anyone who checks out that commit,
even if the *working tree* compiles because the file is still present untracked.
**Incident:** COMMIT-BATCH-F28AL-AUDIT §1 — the commit-2 defect: `ConnectionController` imported
F.28AL-U classes that had never been committed. The canonical failure, later repaired by `ab82ce1`.

### L5 — Hermetic tests must not exercise dereference paths with a dummy handle

A hermetic native test may pass a fake handle (`0x1`) **only** when the code path under test is
guaranteed to reject or return before dereferencing it. If the test's success criterion requires the
call to proceed past that guard — i.e. the operation genuinely cannot succeed with a fake handle —
the test is unsound and will crash. Test only the **rejection** contract when the acceptance path
needs a live connection.
**Incident:** `test35` SIGSEGV — the test passed a valid 6-byte array with dummy handle `0x1`, the
length guard passed by design, and `softether_set_client_mac()` dereferenced `0x1` (`fault addr
0x4bd`, backtrace `__memcpy` ← `softether_set_client_mac` ← `nativeSetClientMac`).

### L6 — The instrumentation runner package must be read from the device, not assumed

The authoritative runner identity comes from `adb shell pm list instrumentation`, or from the
`androidTest` APK's own manifest — never from a replica of the module name or a guess. A wrong runner
string fails with "instrumentation not found", which can be misread as a build defect. Related: the
test package is not necessarily the application package.
**Incident:** BATCH-DEVICE-VERIFY N1 — **both** runner packages given in the brief
(`vn.unlimit.vpngatepro.test` and `vn.unlimit.vpngate.pro.test`) were wrong; the real runner is
`vn.unlimit.softether.test/androidx.test.runner.AndroidJUnitRunner`.

### L7 — `strings` may not be installed; use `grep -a` on raw dex

A missing inspection tool produces a **silent empty result**, which is easily misread as a negative
finding. A command that fails because its binary is absent is not evidence of absence. Verify that
the tool exists, or use a method that does not depend on it.
**Incident:** BATCH-DEVICE-VERIFY — the initial "the APK contains **zero** F.28AL classes" conclusion
was a false alarm: `strings` was not installed (`strings: command not found`), so every scan silently
returned nothing. Re-scanning with `grep -a` directly on extracted dex binaries showed all six
F.28AL classes **present**.

### L8 — Long-running capture must be a managed background task, not a plain shell `&`

A capture started with a plain `&` inside a shell invocation dies when that shell exits, so the
output file freezes at the moment the launching command returns — potentially before the event under
test even occurs. Use the harness's managed background mechanism, and cross-check against the
device's persistent log buffer (`adb logcat -d`), which survives independently.
**Incident:** RESUME-BATCH-DEVICE-VERIFY T1 — the first logcat capture stopped at 158 lines /
`09:10:47`, **before** the connect tap, and contained no app logs at all; it had to be restarted as a
managed background task and reconciled against the device buffer.

### L9 — A searched token must match the real identifier

Before concluding that a process died, a symbol is absent, or a package is missing, confirm the exact
identifier being searched. A wrong-but-plausible name yields an empty result indistinguishable from a
true absence.
**Incident:** RESUME-BATCH-DEVICE-VERIFY — `pidof vn.unlimit.vpngate.pro` returned empty and briefly
looked like an app crash; the real package is `vn.unlimit.vpngatepro`, and the app had been alive the
whole time.

### L10 — Shell tooling availability and argument semantics must be confirmed, not assumed

Several standard tools are absent or behave unexpectedly in this environment, and each absence is a
potential silent false negative: `strings` is not installed (see L7); `bc` is not installed, breaking
arithmetic pipelines; `grep -c` against multiple files prefixes each count line with the filename and
therefore corrupts arithmetic accumulation (use `grep -al` to identify containing files); and `grep -F`
and `grep -E` cannot be combined in one invocation (`grep: conflicting matchers specified`).
**Incident:** RESUME-BATCH-DEVICE-VERIFY B3/T2 — a `paste -sd+ | bc` accumulation failed with an
arithmetic error (`bc` missing), and a multi-file `grep -c` produced unusable output.
