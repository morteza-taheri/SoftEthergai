# FIX-SEC01-TOFU-PINNING — Report

**Phase:** FIX-SEC01-TOFU-PINNING-IMPL
**Repository:** `C:\Dev\SoftEthergai` (canonical name `SoftEthergai`)
**Branch:** `main`
**Base HEAD at phase start:** `df94a8178409a13f82b230ba9680e675918fba1b` (= `origin/main`)
**Spec version:** 1.0.15 (unchanged — **no** spec edit in this phase; a separate DOCS-UPDATE phase records it)
**Date:** 2026-10-15
**Device:** `5585fecc` (Xiaomi 2201117TG, Android 13 / API 33) — **used** for PART F verification
**Scope:** implement SEC-01 (native TLS verification disabled) as **Design B — TOFU certificate-fingerprint
pinning** from `AUDIT_SECURITY_AND_RISK_B_v1.0.14.md` §2; build, test, device-verify, commit + push.

Evidence labels: **SOURCE CODE** / **TEST** / **PHYSICAL DEVICE** / **INFERENCE** / **UNKNOWN** / **AUTOMATED-UI**.

---

## 1. Executive verdict

**PASS.** SEC-01 is implemented end-to-end and verified on the physical device. The native tunnel now
exposes the peer certificate's SHA-256 fingerprint; a pure-Kotlin TOFU store pins it per endpoint and
**blocks** a post-first-use mismatch. The native verify policy (`SSL_VERIFY_NONE`) is **unchanged**, exactly
as the audit specifies — the trust decision is Kotlin-side, after the handshake.

Both JVM suites are green (library `171/0/0/0`, app `647/0/0/1` — the same pre-existing legitimate skip).
The APK builds for all four ABIs. On device, five AutoConnect cycles all reached **CONNECTED**, producing
`[TOFU] first-use stored` ×1 and `[TOFU] match` ×4 with **0 mismatch** and **0 crashes**; the pin was
observed persisted in `tls_server_fingerprints.xml`.

| Part | Deliverable | Outcome | Evidence |
|------|-------------|---------|----------|
| **A** | Read + confirm the design | **PASS** — Design B, unambiguous after the brief's explicit resolutions | §3 |
| **B** | Native fingerprint extraction | **DONE** — `ssl_get_peer_cert_fingerprint()` + JNI + Kotlin binding | §4 |
| **C** | Kotlin TOFU store (pure + Android) | **DONE** — `ServerFingerprintStore` / `…Android` | §5 |
| **D** | Wire into the connect path | **DONE** — `performConnectInner`, empty store still connects | §6 |
| **E** | JVM tests | **DONE** — 11 new tests, library suite 171/0/0/0 | §7 |
| **F** | Build + device verify | **DONE** — APK SHA-256 `583214b0…`; 5/5 cycles CONNECTED; 0 crash | §8 |
| **G** | Report + commits + push | **DONE** — 3 commits pushed | §9 |

---

## 2. Preconditions

| Check | Result |
|-------|--------|
| `HEAD` == `origin/main` | `df94a8178409a13f82b230ba9680e675918fba1b` (both) |
| Local branch ahead/behind | 0 / 0 |
| Tracked tree at start | clean (no staged/unstaged changes) |
| Spec version at start | 1.0.15 |
| Device `5585fecc` | online, awake, keyguard not showing |
| Baseline `firstInstallTime` | `2026-09-28 16:05:05` (user 0), `1970-01-01 03:30:00` (work profile) |
| Fingerprint store at start | **absent** — no `tls_server_fingerprints.xml` (fresh, empty store) |

---

## 3. PART A — Design summary (from `AUDIT_SECURITY_AND_RISK_B_v1.0.14.md` §2)

**Chosen design:** §2.3 **Design B — TOFU certificate-fingerprint pinning** (recommended; §2.4 adopts it as
the baseline). Designs A (public-CA + hostname) and C (SPKI key set) were both **rejected** in §2.3 because
VPN Gate relays are volunteer-operated, present self-signed certificates, and are addressed by **IP literal**
(§2.1/§2.2 — the SNI value is an IP, so an `SSL_set1_host` check would be meaningless).

**Constraints the phase must honour (verbatim from §2):**

| Constraint | Design says | This phase |
|-----------|-------------|-----------|
| **First use** | "A brand-new endpoint is TOFU-accepted **and recorded**" (§2.3) | auto-store + proceed, **no operator approval** |
| **Mismatch policy** | "a **mismatch blocks the connection**" (§2.3) | `MISMATCH` → `onError` + existing teardown |
| **Fingerprint key** | open sub-question: "per IP:port or per relay identity" (§2.4/§6) | resolved by the brief as `ServerKey(hostname, ip, port)` — see §5 |
| **Store location** | "a small persistent store" (§2.3/§6, format open) | SharedPreferences, one key per endpoint — see §5 |
| **Strict mode** | *opt-in*, default-off (§2.4) | **not implemented** (out of scope; default TOFU behaviour only) |
| **Native verify policy** | TOFU is post-handshake; §2 never asks to enable `SSL_VERIFY_PEER` | `SSL_VERIFY_NONE` **unchanged** — confirmed below |

**A3 — ambiguity check.** §2's own open sub-questions (key type, store format, mismatch UX, strict-mode
default) are the *only* ambiguities, and the phase brief resolves each of them explicitly in PART C1/C2/C3.
The brief's resolutions are consistent with §2 (auto-accept on first use; block on mismatch; opt-in strict
mode default-off). **No STOP was required, and no alternative trust model was improvised.**

**A4 — is there an existing way to obtain the peer certificate?** **No.** `aes_wrapper.c` had no accessor for
the peer certificate (negative evidence: §2.1's repo-wide grep found no `SSL_get_verify_result`,
`SSL_set1_host`, or any fingerprint/pin). `ssl_context_t` is opaque and `struct ssl_context { SSL_CTX* ctx;
SSL* ssl; BIO* bio; int connected; }` is file-private to `aes_wrapper.c`. The insertion point is therefore a
new accessor in `aes_wrapper.c`, called **after** `SSL_do_handshake` has returned 1 (`ssl_connect`, `:473`)
— i.e. from the JNI, once `conn->ssl` is live and the handshake is complete. `SSL_VERIFY_NONE` at `:110` was
left byte-identical.

**Verification of the "do not change SSL_VERIFY_NONE" rule:**
`git diff` on `aes_wrapper.c` touches only the `#include` block and the new function; the string
`SSL_CTX_set_verify(c, SSL_VERIFY_NONE, NULL);` is unchanged (SOURCE CODE).

---

## 4. PART B — Native fingerprint extraction

### 4.1 `softether-core/src/crypto/aes_wrapper.c` (+51)

- Added `#include <openssl/x509.h>` (for `X509_digest` / `X509_free`).
- Added `int ssl_get_peer_cert_fingerprint(ssl_context_t* ctx, uint8_t* out_sha256, size_t out_len)`
  (after `ssl_has_pending`, `:659`). It:
  - returns `-1` on a NULL context / NULL output / `out_len < 32`;
  - takes `g_tls_use_lock` (read) to keep the SSL object alive against a concurrent `ssl_destroy`, and
    `g_openssl_lock` to serialize the provider-touching digest (same lock order as every other SSL call);
  - calls `SSL_get_peer_certificate` → `X509_digest(cert, EVP_sha256(), out, &len)` → `X509_free(cert)`;
  - returns `0` only when the digest succeeded and produced exactly 32 bytes; `-1` when the peer presented
    no certificate or the digest failed.

**Chosen JNI return type:** `jbyteArray` (32 raw bytes), matching the existing `nativeGetClientMac`
precedent rather than a hex `jstring`. Hex conversion is done in Kotlin by `ServerFingerprint.toHex`, so the
one canonical encoding lives in one place.

### 4.2 `jni/softether_jni.c` (+41)

- Added `#include "softether_crypto.h"` and a **local** `extern` prototype for
  `ssl_get_peer_cert_fingerprint`. The prototype is declared in the `.c` rather than added to
  `softether_crypto.h` because this phase's authorized file set is limited to `aes_wrapper.c` and
  `softether_jni.c` (the signature is kept byte-for-byte identical to the definition).
- Added `Java_…_nativeGetPeerFingerprint(JNIEnv*, jobject, jlong handle)`: rejects `handle == 0` and a NULL
  `conn->ssl` (handshake not complete), calls the accessor into a 32-byte buffer, and returns a 32-byte
  `jbyteArray` or `NULL`. Read-only; changes no verify policy.

### 4.3 `SoftEtherClient.kt` (+20)

- `external fun nativeGetPeerFingerprint(handle: Long): ByteArray?`
- `fun getPeerFingerprint(handle: Long = nativeHandle): String?` — guards `handle == 0L`, rejects a
  non-32-byte result, and returns `ServerFingerprint.toHex(bytes)` (64 lowercase hex chars) or `null`.
  The controller passes its **own** handle explicitly (this object's private `nativeHandle` is 0 on the
  controller-managed path — the same reason `setTimeout` has an explicit-handle overload).

**B4 honoured:** nothing calls `getPeerFingerprint` at connect time yet — PART D wires it.

---

## 5. PART C — Kotlin TOFU store

### 5.1 `security/ServerFingerprintStore.kt` (new, 201 lines — pure, no Android imports)

- `enum class VerifyResult { MATCH, FIRST_USE, MISMATCH, MISSING }`.
- `enum class TofuDecision { PROCEED, BLOCK }` + `fun VerifyResult.toDecision()` — **the single policy
  hook**: `MATCH` / `FIRST_USE` / `MISSING` → `PROCEED`; `MISMATCH` → `BLOCK`.
- `data class ServerKey(hostname: String, ip: String, port: Int)` with `val canonical = "host|ip|port"`
  (trimmed + lowercased). The port is part of the key, so two endpoints sharing a host never collide.
- `interface ServerFingerprintStore { lookup / store / verify / clear }`.
- `class InMemoryServerFingerprintStore` — the normative semantics, driving the tests.
- `internal fun tofuVerify(existing, presented)` — the shared decision rule used by **both** the in-memory
  and the Android-backed store, so they cannot drift.
- `object ServerFingerprint { toHex(ByteArray); isWellFormed(String) }` — the canonical fingerprint codec.

**Decision table (as implemented):**

| Existing pin | Presented fingerprint | Result | Policy |
|---|---|---|---|
| none | present | `FIRST_USE` (auto-store) | PROCEED |
| none | absent/blank | `MISSING` | PROCEED |
| present | equal (case-insensitive) | `MATCH` | PROCEED |
| present | different | `MISMATCH` | **BLOCK** |
| present | absent/blank | `MISMATCH` (fail-closed) | **BLOCK** |

**Note on the two last rows.** The brief's four cases (C3) are all honoured: `FIRST_USE` auto-stores and
proceeds; `MISMATCH` errors and does not proceed; `MISSING` is treated as `FIRST_USE` (both `PROCEED`);
`MATCH` proceeds. The extra row — *a pinned endpoint that presents no fingerprint* — is resolved
**fail-closed** as `MISMATCH`, not `MISSING`. This is the security-correct reading of §2 ("a mismatch blocks
the connection"): an endpoint that was pinned before and can no longer be re-verified must not silently
proceed. It does not contradict the brief, because `MISSING` now means only "no pin recorded", for which
proceeding is exactly first-use behaviour.

### 5.2 `security/ServerFingerprintStoreAndroid.kt` (new, 54 lines)

- SharedPreferences (`tls_server_fingerprints`), **one key per endpoint** (`canonical → lowercase-hex`).
- Delegates the decision to the same `tofuVerify`; stores on `FIRST_USE`.
- **Plain** `SharedPreferences`, deliberately **not** `EncryptedSharedPreferences`: a certificate
  fingerprint is public data (it is derived from the certificate the server sends in the clear on every
  handshake), and §2 does not require encryption.

---

## 6. PART D — Wiring into the connect path

`ConnectionController.kt` (+53):

- New field `private val fingerprintStore: ServerFingerprintStore = ServerFingerprintStoreAndroid(service)`
  — built from the existing `SoftEtherVpnService` context, so **no service wiring change** was needed
  (`SoftEtherVpnService.kt` untouched).
- In `performConnectInner`, **immediately after** the `nativeConnectWithHub` result check (`result != 0`
  throws at `:650`), the check runs:

```kotlin
val presentedFingerprint = client.getPeerFingerprint(nativeHandle)
val serverKey = ServerKey(config.serverHost, config.serverHost, config.serverPort)
val verifyResult = fingerprintStore.verify(serverKey, presentedFingerprint ?: "")
// log "[TOFU] match|first-use stored|missing|mismatch"
if (verifyResult.toDecision() == TofuDecision.BLOCK) {
    currentState = ConnectionState.ERROR
    isCancelled.set(true)                 // stop connect()'s retry loop (deterministic failure)
    onError("TLS fingerprint mismatch")
    throw Exception("TLS fingerprint mismatch")
}
```

- **D4 compliance:** a mismatch routes to `onError("TLS fingerprint mismatch")` and tears down through the
  **existing** `performConnect` → `teardownNativeConnection()` path. `NativeHandleSlot` is **never** touched
  directly. `isCancelled.set(true)` prevents `connect()`'s retry loop from re-attempting (and re-reporting)
  a deterministic failure — the same idiom the file already uses at `:392`.
- **D5 compliance:** no other part of the connection flow was changed.
- **D6 (empty store must still connect):** verified on device — cycle 1 produced `FIRST_USE → stored →
  CONNECTED` (§8).

**Known scope limitation (stated, not a deviation):** the brief scopes the check to `performConnectInner`
(D1/D5), so the **reconnect** path (`attemptReconnect`, which re-enters `nativeConnectWithHub`) does not
re-run the TOFU comparison. Reconnects target the already-pinned endpoint; wiring the check there is a
candidate for a future phase, not part of this one.

---

## 7. PART E — JVM tests

`src/test/java/vn/unlimit/softether/security/ServerFingerprintStoreTest.kt` (new, 192 lines, JUnit 4, pure
JVM — no Android context). 11 tests:

| # | Test | Asserts |
|---|------|---------|
| 1 | `emptyStoreYieldsFirstUseAndRecords` | FIRST_USE + stored + PROCEED |
| 2 | `repeatConnectWithSameFingerprintMatches` | MATCH + PROCEED |
| 3 | `differentFingerprintAfterFirstUseIsMismatch` | MISMATCH + BLOCK + pin not overwritten |
| 4 | `missingIsTreatedAsFirstUse` | MISSING + same decision as FIRST_USE + nothing stored |
| 5 | `pinnedEndpointWithNoFingerprintFailsClosed` | pin + no fingerprint → MISMATCH + BLOCK |
| 6 | `fingerprintComparisonIsCaseInsensitive` | hex casing not significant |
| 7 | `clearForgetsThePin` | clear() → next connect is FIRST_USE again |
| 8 | `sameHostDifferentPortDoesNotCollide` | key collision handling |
| 9 | `canonicalKeyIncludesPort` | storage key contains the port |
| 10 | `hexEncodingIsCanonical` | 32 bytes → 64 lowercase hex |
| 11 | `wellFormedRejectsBadFingerprints` | length / non-hex rejection |

**Library suite:** `171 / 0 / 0 / 0` (10 XML files) — baseline 160 + 11 new. **App suite:** `647 / 0 / 0 / 1`
— identical to baseline; the single skip is the pre-existing legitimate
`vn.unlimit.vpngate.collector.HtmlParserOracleTest :: softetherUdpOnlyNeverBorrowsOpenvpnTcp`.

---

## 8. PART F — Build + device verification

### 8.1 Build

- `CI=true ./gradlew :app:assembleProDebug --rerun-tasks --no-daemon` → **BUILD SUCCESSFUL** (7m46s).
- CMake compiled the changed native sources for **all four ABIs** (arm64-v8a, armeabi-v7a, x86, x86_64);
  **no warnings** from `aes_wrapper.c` or `softether_jni.c`.
- APK: `app/build/outputs/apk/pro/debug/app-pro-debug.apk`, **63,645,770 bytes**,
  **SHA-256 `583214b0e8baa31b39c97c937a12d3badfa0d4c47ab5a8c2940e04dc98c58b1e`**.

### 8.2 Install

- `adb -s 5585fecc install -r` → **Success**.
- `firstInstallTime` **unchanged**: `2026-09-28 16:05:05` (user 0) / `1970-01-01 03:30:00` (work profile);
  `lastUpdateTime` advanced to `2026-10-15 10:41:45`. Version `2.4.10-dev` / `143`.

### 8.3 Fingerprint store before / after

- **Before:** `shared_prefs/` had **no** `tls_server_fingerprints.xml` (fresh, empty store).
- **After cycle 1:** the file exists (196 bytes, mtime 10:42) with exactly one pin:

```xml
<map>
    <string name="59.138.182.93|59.138.182.93|1378">d4bedc8f499ae6e416311783ad4575086ca5a0b138787cd946931f109d4b2d3f</string>
</map>
```

This is the physical-device proof that the pin was **recorded on first use** and persisted — and it matches
the `[TOFU] first-use stored … fp=d4bedc8f…` log line exactly.

### 8.4 Five AutoConnect cycles (AUTOMATED-UI + PHYSICAL DEVICE)

| Cycle | `[AUTO] Started` | `[TOFU]` | Tunnel | Result |
|---|---|---|---|---|
| 1 | 10:42:42.797 | `first-use stored` 10:42:47.449 | 10:42:48.699 | **CONNECTED** (ip 10.211.1.34) |
| 2 | 10:43:40.789 | `match` 10:43:45.466 | 10:43:46.797 | **CONNECTED** |
| 3 | 10:44:27.849 | `match` 10:44:32.510 | 10:44:34.144 | **CONNECTED** |
| 4 | 10:45:13.801 | `match` 10:45:18.480 | 10:45:19.970 | **CONNECTED** |
| 5 | 10:45:59.719 | `match` 10:46:04.400 | 10:46:06.294 | **CONNECTED** |

AutoConnect re-selected the **same** relay (`59.138.182.93:1378`, `vpn315836095`) on every cycle, so the
run exercised **both** the `FIRST_USE` and the `MATCH` path — and no `MISMATCH` was produced by a legitimate
endpoint, which is the expected negative result.

Exact `[TOFU]` log lines observed (all 5):

```
10-15 10:42:47.449 ConnectionController: [TOFU] first-use stored key=59.138.182.93|59.138.182.93|1378 fp=d4bedc8f499ae6e416311783ad4575086ca5a0b138787cd946931f109d4b2d3f
10-15 10:43:45.466 ConnectionController: [TOFU] match key=59.138.182.93|59.138.182.93|1378
10-15 10:44:32.510 ConnectionController: [TOFU] match key=59.138.182.93|59.138.182.93|1378
10-15 10:45:18.480 ConnectionController: [TOFU] match key=59.138.182.93|59.138.182.93|1378
10-15 10:46:04.400 ConnectionController: [TOFU] match key=59.138.182.93|59.138.182.93|1378
```

### 8.5 Marker tallies

| Marker | Count | Expected |
|--------|-------|----------|
| `[TOFU] first-use stored` | **1** | ≥1 (per new endpoint) ✓ |
| `[TOFU] match` | **4** | ≥1 (repeat) ✓ |
| `[TOFU] mismatch` | **0** | 0 ✓ |
| `[TOFU] missing` | **0** | — |
| `[AUTO] Started` | **5** | 5 ✓ |
| `[AUTO] Connected successfully` | **5** | 5 ✓ |
| `State changed: CONNECTED` | **5** | 5 ✓ |
| `FATAL EXCEPTION` / `SIGSEGV` / `tombstone` / `ANR` | **0 / 0 / 0 / 0** | 0 ✓ |

### 8.6 Crash scan + settings

- `logcat -b crash -d` → **EMPTY (0 lines)**.
- No `AndroidRuntime` line from the app's PID (29259) — all such lines belong to the `uiautomator`/`monkey`
  tooling.
- **No setting changed** — `vpn_setting_data_pro.xml` still shows defaults (timeout 5 s, max connections 8,
  priority `SOFTETHER_TCP,SOFTETHER_UDP,MS_SSTP`, locale `en`); only runtime/state keys
  (`vpn_last_connection`, `AUTO_LAST_SUCCESS_HOST`, `KEY_CACHED_MIRRORS`) were updated by normal app
  operation. **No fingerprint purge** occurred (the store still holds the cycle-1 pin).

---

## 9. PART G — Commits + push

Three logical commits, staged by explicit path only:

```
b652198  feat(native): expose peer certificate fingerprint (SEC-01 part 1)
         aes_wrapper.c, softether_jni.c                          (2 files, +92)

9954c03  feat(security): TOFU fingerprint pinning at connect time (SEC-01 part 2)
         SoftEtherClient.kt, ConnectionController.kt,
         ServerFingerprintStore.kt, ServerFingerprintStoreAndroid.kt,
         ServerFingerprintStoreTest.kt                           (5 files, +520)

<this>   docs: report for FIX-SEC01-TOFU-PINNING
         FIX_SEC01_TOFU_PINNING_REPORT.md
```

Scope gate: each commit contains exactly its authorized file set. Push (fast-forward, no force, no tag):
base `df94a81` → HEAD. Post-push: `HEAD == origin/main`; tags unchanged (5: `p2.4.5`–`p2.4.10`).

---

## 10. Per-part verdict

| Part | Verdict | Key evidence |
|------|---------|--------------|
| A — design read/confirmed | **PASS** | §3; §2.3/§2.4 Design B; no STOP |
| B — native extraction | **PASS** | §4; `ssl_get_peer_cert_fingerprint` + JNI + binding; `SSL_VERIFY_NONE` unchanged |
| C — Kotlin store | **PASS** | §5; pure logic + SharedPreferences; 4-case policy + fail-closed extra row |
| D — wiring | **PASS** | §6; empty store connects (device cycle 1); mismatch → `onError` + existing teardown |
| E — JVM tests | **PASS** | §7; library 171/0/0/0; app 647/0/0/1 |
| F — build + device | **PASS** | §8; APK `583214b0…`; 5/5 CONNECTED; 1 FIRST_USE + 4 MATCH; 0 crash |
| G — report/commit/push | **PASS** | §9 |

---

## 11. Explicit confirmations

- **No forbidden file touched:** `AutoModeController.kt`, `NativeLifetimeRegistry.kt`,
  `NativeHandleSlot.kt`, `NativeAttemptCoordinator.kt`, `SoftEtherVpnService.kt`, `TunTerminal.kt` —
  **none modified**.
- **No build-file change:** `build.gradle`, `settings.gradle`, `CMakeLists.txt` — **untouched**.
- **No manifest/XML change:** `AndroidManifest.xml`, `network_security_config.xml` — **untouched**.
- **`SSL_VERIFY_NONE` unchanged** — the trust decision is Kotlin-side, per the audit's §2 design.
- **No spec change:** `PROJECT_SPECIFICATION.md` **untouched**; §22 L1–L10 and §1.6.2–§1.6.8 untouched.
- **No existing report modified.**
- **No `git add -A` / `git add .`** — every commit staged by explicit path.
- **No `--force`, no tag, no reset / clean / stash.**
- **No uninstall / data clear / device setting change** — `firstInstallTime` proves the in-place update.
- **C5 not executed.** `D:\PMC\Documents\Android\SoftEthergai` not touched.
- Authorized device writes only: `install -r`, `logcat -c`, `input tap`, `uiautomator dump`,
  `am force-stop`, `monkey`, and read-only `run-as cat`/`ls`.

**Remaining:** SEC-01's native verify policy stays `SSL_VERIFY_NONE` by design; the TOFU pin is the
compensating control. The reconnect-path check and the opt-in strict mode are documented, deliberate
non-goals of this phase (§6, §3).
