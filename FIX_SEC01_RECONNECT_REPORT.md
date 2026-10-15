# FIX-SEC01-RECONNECT — Report

**Phase:** VERIFY-AND-FIX-SEC01-RECONNECT
**Repository:** `C:\Dev\SoftEthergai` (canonical name `SoftEthergai`)
**Branch:** `main`
**Base HEAD at phase start:** `e03f45d38b5c86cf29cea870a612a4e4b4da8e7d` (= `origin/main`)
**Spec version:** 1.0.15 (unchanged — **no** spec edit in this phase; a separate DOCS-UPDATE phase records it)
**Date:** 2026-10-15
**Device:** `5585fecc` (Xiaomi 2201117TG, Android 13 / API 33) — **used** for PART 4 verification
**Scope:** resolve the flag left by FIX-SEC01-TOFU-PINNING-IMPL ("the reconnect path does not re-run the TOFU
check"): determine whether that is (A) a non-issue or (B) a real gap, and if (B) fix + test + build +
device-verify + push.

Evidence labels: **SOURCE CODE** / **TEST** / **PHYSICAL DEVICE** / **INFERENCE** / **UNKNOWN** / **AUTOMATED-UI**.

---

## 1. Executive verdict

**GAP CLOSED — the flag was (B), a real gap.** `attemptReconnect()` does **not** reuse the previous TLS
session: it claims and destroys the old native handle, runs a fresh `nativeCreate()`, and then a fresh
`nativeConnectWithHub()` — a brand-new TLS handshake on a brand-new SSL context. The TOFU check added in the
previous phase ran only in `performConnectInner()`, so **every reconnect handshake was unchecked**: a relay
substituted after first use would have gone undetected for the lifetime of the connection.

The fix extracts the check into a shared private helper (`verifyTofuAfterHandshake`) and calls it from
**both** handshake-completing paths. Both JVM suites are green (library `173/0/0/0`, app `647/0/0/1`), the
APK builds for all four ABIs, and on device 3/3 AutoConnect cycles connect with `[TOFU] match` ×3, 0
mismatch, and 0 crashes. The reconnect path itself could not be reproduced live — it needs a mid-session
network failure (a device-setting change, out of scope), so it is proven by the source evidence (§2/§3) and
the dedicated regression test (§4.1), not by a device trace.

| Item | Outcome |
|------|---------|
| STEP 1 verdict | **(B) — real gap** (call chain in §2) |
| STEP 2 fix | **Applied** — shared helper, both call sites (§3) |
| STEP 3 tests | `:SoftEtherClient:test` **173/0/0/0**; `:app:testProDebugUnitTest` **647/0/0/1** (§4) |
| STEP 4 build+device | **PASS** — APK SHA-256 `df153430…b962`; 3/3 cycles CONNECTED; 0 mismatch; 0 crash (§5) |

---

## 2. STEP 1 — Source-code evidence (verdict: **B**)

### 2.1 The two paths that complete a TLS handshake

| | Initial connect | Reconnect |
|---|---|---|
| Entry | `connect()` `:246` | `triggerReconnect()` `:1211` → `attemptReconnect()` `:1232` |
| Driver | `performConnect()` `:389` → `performConnectInner()` `:571` | `attemptReconnect()` `:1232` |
| Old handle | (none — first connect) | `claimAndDestroyNativeHandle()` `:1303` → `nativeDisconnect`+`nativeDestroy` `:536-537` |
| New handle | `client.nativeCreate()` `:451` | `client.nativeCreate()` `:1345` |
| Handshake | `client.nativeConnectWithHub(…)` `:624` | `client.nativeConnectWithHub(…)` `:1382` |
| TOFU check (before this phase) | **present** `:696` | **ABSENT** |

### 2.2 Why the reconnect is a *new* handshake (not a reused session)

- `attemptReconnect()` tears the previous connection down at the top: `claimAndDestroyNativeHandle()`
  (`:1303`) → `nativeDisconnect(handle)` + `nativeDestroy(handle)` (`:536-537`). The old `softether_connection_t`
  — and with it the old `ssl_context_t` — is freed.
- It then creates a **brand-new** native connection: `client.nativeCreate()` (`:1345`), publishing the new
  handle (`:1346-1347`).
- It then runs a **full new handshake**: `client.nativeConnectWithHub(…)` (`:1382`), logged as
  `[DIAG] nativeConnectWithHub entry (… path=reconnect)` (`:1380`).
- Between the `result != 0` guard (`:1406-1408`) and the next statement there was **no** TOFU check.

**Conclusion (SOURCE CODE):** the reconnect performs a genuine, new TLS handshake against the same endpoint
(`config.serverHost` / `config.serverPort`) on a new SSL context. The previous phase's pin therefore did not
cover it. This is a **real gap** (option **B**), not a reused-context non-issue (option A).

### 2.3 Impact (INFERENCE)

Design B's guarantee is "detect a MITM / relay substitution **after first use**". Since AutoConnect reconnects
on data-path failure (`triggerReconnect()` from `startDataForwarding` `:1108/:1114/:1125/:1190` and
`startKeepalive` `:1574`), a substituted relay could be accepted indefinitely on the reconnect path while the
initial connect stayed pinned — i.e. the pin would protect only the first handshake of a session.

---

## 3. STEP 2 — The fix (`ConnectionController.kt`, +98/−30)

**Shape 1** from the brief was chosen (extract a helper, call it from both sites) — the check is 20+ lines, so
duplicating it inline would have been the larger and less clear diff.

### 3.1 New shared helper — `verifyTofuAfterHandshake(handle: Long): TofuDecision` (`:550`)

Moved verbatim out of `performConnectInner`: extract the fingerprint, build the `ServerKey`, run
`fingerprintStore.verify(...)`, log the outcome once (`[TOFU] match|first-use stored|missing|mismatch`), and
return `verifyResult.toDecision()` (`PROCEED` for MATCH/FIRST_USE/MISSING, `BLOCK` for MISMATCH). The **caller**
owns the abort, because the two paths have different teardown primitives.

### 3.2 `performConnectInner` (`:696`) — behaviour unchanged

The inline block is replaced by `if (verifyTofuAfterHandshake(nativeHandle) == TofuDecision.BLOCK) { … }`,
keeping the original abort exactly: `currentState = ERROR`, `isCancelled.set(true)`,
`onError("TLS fingerprint mismatch")`, `throw` → `performConnect()`'s existing teardown. Net effect: identical
behaviour, one shared implementation.

### 3.3 `attemptReconnect` (`:1417`) — the fix

```kotlin
if (verifyTofuAfterHandshake(nativeHandle) == TofuDecision.BLOCK) {
    currentState = ConnectionState.ERROR
    isCancelled.set(true)
    claimAndDestroyNativeHandle()
    onError("TLS fingerprint mismatch")
    return
}
```

- **MISMATCH** → `currentState = ERROR`; `isCancelled.set(true)` (every subsequent guard in this function
  reads it, so the attempt stops); teardown via `claimAndDestroyNativeHandle()` — the **same single-owner
  claim** every other early-exit branch in `attemptReconnect` uses (`:1303`, `:1436`, `:1493`, `:1518`,
  `:1538`); `onError("TLS fingerprint mismatch")`; `return`. `NativeHandleSlot` is **never** touched directly.
- **MATCH / FIRST_USE / MISSING** → the `if` is skipped and the reconnect proceeds exactly as before; the
  `[TOFU]` line is logged once inside the helper, at the same level (`Log.i`) as the initial-connect lines.

**Deliberate deviation from the brief's literal wording (documented):** the brief said "throw → existing
teardown". In `attemptReconnect` a plain `throw` is **not** equivalent: the function's catch block
(`:1510-1541`) unconditionally calls `claimAndDestroyNativeHandle()` **and** `onError("Reconnect failed:
${e.message}")`. Throwing would therefore emit a **second, differently worded** `onError` and re-run the
dual-channel teardown that the F.28 lifecycle work in this file exists to prevent (see the long comment at
`:1526-1540`). Returning after the explicit `claimAndDestroyNativeHandle()` reproduces the initial-connect
outcome exactly — one teardown, one `onError("TLS fingerprint mismatch")`, state `ERROR` — using this
function's own established early-exit idiom. The rest of the brief's 2b is honoured verbatim.

### 3.4 Scope

`attemptReconnect` is the only other path that calls `nativeConnectWithHub`. Verified by grep:
`nativeConnectWithHub` appears at `:624` (initial) and `:1382` (reconnect) only — both now guarded.

---

## 4. STEP 3 — JVM tests

### 4.1 New test (`ServerFingerprintStoreTest.kt`, +73)

A **pure-JVM source-shape** regression guard, `Sec01ReconnectTofuCoverageTest` (2 tests), was added to the
authorized test file:

| Test | Asserts |
|------|---------|
| `reconnectPathRunsTheTofuCheck` | the `attemptReconnect` body contains `verifyTofuAfterHandshake(nativeHandle)`, `TofuDecision.BLOCK`, and `onError("TLS fingerprint mismatch")` |
| `bothHandshakePathsUseTheSharedCheck` | `verifyTofuAfterHandshake(nativeHandle)` occurs exactly **2** times (one per handshake-completing path) |

**Why source-shape, not behavioural:** `ConnectionController` cannot be instantiated in a JVM unit test — it
needs a `SoftEtherVpnService` context, `ParcelFileDescriptor`, coroutines, and the native library. The TOFU
*decision* is already covered behaviourally by the 11 existing `ServerFingerprintStoreTest` cases; what the
reconnect gap actually was is a **call-site placement** fact, and the repo already pins that class of fact by
reading the source (`SoftEtherClientHandleGuardTest`, same rationale). This is a real regression guard, not a
fabricated test: removing the check from either path fails the build.

### 4.2 Results

| Suite | Result | Baseline | Delta |
|-------|--------|----------|-------|
| `:SoftEtherClient:test` | **173 / 0 / 0 / 0** (11 XML files) | 171 | +2 (the new coverage class) |
| `:app:testProDebugUnitTest` | **647 / 0 / 0 / 1** (48 XML files) | 647 / 0 / 0 / 1 | 0 — no regression |

The single app skip is the pre-existing legitimate
`vn.unlimit.vpngate.collector.HtmlParserOracleTest :: softetherUdpOnlyNeverBorrowsOpenvpnTcp`.

---

## 5. STEP 4 — Build + device verification

### 5.1 Build

- `CI=true ./gradlew :app:assembleProDebug --rerun-tasks --no-daemon` → **BUILD SUCCESSFUL**.
- CMake compiled for all four ABIs (arm64-v8a, armeabi-v7a, x86, x86_64); no warnings from the changed Kotlin.
- APK: `app/build/outputs/apk/pro/debug/app-pro-debug.apk`, 63,645,818 bytes,
  **SHA-256 `df153430118ed92ea878b25b26a781a64adcb944787d490bb59d919f0889b962`**.

### 5.2 Install

- `adb -s 5585fecc install -r` → **Success**; **`firstInstallTime` unchanged** (`2026-09-28 16:05:05` user 0 /
  `1970-01-01 03:30:00` work profile). Version `2.4.10-dev` / `143`.

### 5.3 AutoConnect cycles (3×) and the reconnect path

**3 AutoConnect cycles, all PASS** (`_p10_cycles.log`): each tap of the Auto Mode hero button reached
`Connected` within 3 s, settled 10 s, then the second tap reached `Disconnected`, settled 10 s.

| Cycle | Connect | Settle | Disconnect | Settle |
|-------|---------|--------|-----------|--------|
| 1 | 11:36:29 → Connected (3 s) | 10 s → Connected | 11:36:56 → Disconnected | 10 s → Disconnected |
| 2 | 11:37:15 → Connected (3 s) | 10 s → Connected | 11:37:43 → Disconnected | 10 s → Disconnected |
| 3 | 11:38:02 → Connected (3 s) | 10 s → Connected | 11:38:33 → Disconnected | 10 s → Disconnected |

**TOFU log counts (whole capture, `_p10_logcat.txt`):**

| Pattern | Count |
|---------|-------|
| `[TOFU] match` | **3** |
| `[TOFU] first-use stored` | 0 |
| `[TOFU] mismatch` | **0** |
| `[TOFU] missing` | 0 |

Exact lines (one per connect, the endpoint pinned in the previous phase):

```
10-15 11:36:33.305 32744  3769 I ConnectionController: [TOFU] match key=59.138.182.93|59.138.182.93|1378
10-15 11:37:19.535 32744  3735 I ConnectionController: [TOFU] match key=59.138.182.93|59.138.182.93|1378
10-15 11:38:06.740 32744  3734 I ConnectionController: [TOFU] match key=59.138.182.93|59.138.182.93|1378
```

`first-use stored = 0` is correct here: the endpoint was already pinned by the previous phase, so every
connect is a MATCH. This also demonstrates the **shared helper is exercised on device** — the refactor in §3
did not change initial-connect behaviour.

**On the reconnect path specifically (the brief's optional item).** No `attemptReconnect()` was triggered
during the run — the three `[DIAG] nativeConnectWithHub entry (attempt=1|2|3)` lines are all initial
connects (no `path=reconnect`), and there are 0 `triggerReconnect` / `attemptReconnect` / `Reconnect failed`
log lines. This is **correct**, not a miss: a *graceful* disconnect sets `isCancelled` / `DISCONNECTED`
before the receive loop unwinds, so the receive-error guard (`ConnectionController.kt:1189`
`if (isConnected() && !isCancelled.get()) triggerReconnect()`) does not fire. The three
`E ConnectionController: Receive error: -1` lines at 11:36:55 / 11:37:42 / 11:38:33 are the expected
teardown artefact and produce no reconnect.

`attemptReconnect()` is the **in-session** recovery path — reached only on a mid-session data-path failure
(send error `:1108`/`:1114`, TUN error `:1125`, receive error `:1190`/`:1200`) or a keepalive timeout
(`:1598`). It is distinct from Auto Mode's session-level reconnect, which starts a **fresh session** via
`SoftEtherVpnService.ACTION_CONNECT` (`AndroidConnectionAdapter.connectSoftEther` `:529-533`) → `connect()`
→ `performConnectInner` — i.e. the already-checked initial path. Inducing a mid-session failure requires
interrupting the device's network (a device-setting change, **out of scope** per this phase's constraints),
so the reconnect path was **not** reproduced on-device. Its TOFU invocation is instead pinned by the source
evidence (§2/§3) and the dedicated pure-JVM regression test (§4.1). This is the brief's explicitly optional
item, and it is the only item not backed by a live-device reproduction.

### 5.4 Crash scan + settings

- **`adb logcat -b crash -d` → EMPTY** (0 lines).
- Main buffer scan over the full capture: **0** `FATAL EXCEPTION`, **0** `SIGSEGV`, **0** tombstone,
  **0** `ANR in`, **0** native fatal-signal / `backtrace` / `DEBUG pid:` markers.
- **No process death:** `vn.unlimit.vpngatepro` pid **32744** is the same process that logged the three
  `[TOFU] match` lines and is still alive at the end of the run — no restart, no kill, no cold start.
- **Fingerprint store intact and not purged:** `shared_prefs/tls_server_fingerprints.xml` md5 is
  **unchanged** from the pre-install baseline (`cc1b3e2d79a4a1da794201db9119d6d6`); the original pin
  `59.138.182.93|59.138.182.93|1378 → d4bedc8f499ae6e416311783ad4575086ca5a0b138787cd946931f109d4b2d3f`
  is present exactly once (not duplicated, not removed).
- **No user-facing setting changed by this phase.** Two prefs files changed md5, both pure runtime
  bookkeeping written by the app itself during the connect cycles: `vpn_setting_data_pro.xml` (the
  `vpn_last_connection` record for the same Japan endpoint) and `vn.unlimit.vpngatepro_preferences.xml`
  (the `uploaded_data` / `downloaded_data` traffic counters). `SETTING_DEVELOPER_MODE`,
  `SETTING_LOCALE`, `SETTING_SOFTETHER_MAX_CONNECTIONS` are unchanged.

---

## 6. Explicit confirmations

- **No forbidden file touched:** `aes_wrapper.c`, `softether_jni.c`, `SoftEtherClient.kt`,
  `AutoModeController.kt`, `NativeLifetimeRegistry.kt`, `NativeHandleSlot.kt`,
  `NativeAttemptCoordinator.kt`, `SoftEtherVpnService.kt`, `TunTerminal.kt` — **none modified**.
- **No build-file change:** `build.gradle`, `settings.gradle`, `CMakeLists.txt` — **untouched**.
- **No manifest/XML change.**
- **No spec change:** `PROJECT_SPECIFICATION.md` **untouched**; §22 L1–L10 and §1.6.2–§1.6.8 untouched.
- **No existing report modified.**
- **No `git add -A` / `git add .`** — commits staged by explicit path.
- **No `--force`, no tag, no reset / clean / stash.**
- **No uninstall / data clear / device setting change.**
- **C5 not executed.** `D:\PMC\Documents\Android\SoftEthergai` not touched.

---

## 7. Recommendation

SEC-01 is now enforced on **every** TLS handshake the controller performs (initial connect + reconnect). Two
items remain for a future phase, neither a bypass:

1. **DOCS-UPDATE** to record SEC-01 as fully fixed (initial + reconnect) — the pending spec update.
2. **Opt-in strict mode** (§2.4 of the audit) is still unimplemented by design (default-off).
