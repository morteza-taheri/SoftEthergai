# FIX-NATIVE-SECURITY-SMALL — Report

**Phase:** FIX-NATIVE-SECURITY-SMALL
**Repository:** `C:\Dev\SoftEthergai` (canonical name `SoftEthergai`)
**Branch:** `main`
**Base HEAD at phase start:** `a60681ff9b788d57fd7d7b5587fd74df3716f8d4` (= `origin/main`)
**Spec version:** 1.0.14 (unchanged — **no** spec edit in this phase)
**Date:** 2026-10-15
**Scope:** apply the four low-risk fixes designed in `AUDIT_SECURITY_AND_RISK_B_v1.0.14.md`
(N-09, N-11, RISK-B, SEC-02), build the APK, run both JVM suites, commit + push.
**No device command.** Device verification is deferred to a separate phase.

Evidence labels: **SOURCE CODE** / **TEST** / **INFERENCE** / **UNKNOWN**.

---

## 1. Executive verdict

**PASS.** All four fixes were applied exactly as designed, both JVM suites are green, the
release-path APK builds cleanly for all four ABIs, and the packaged manifest enforces the
HTTPS-only default. Seven production files changed (+152 / −62); three commits pushed.

| Part | Finding | Outcome | Evidence |
|------|---------|---------|----------|
| **A** | N-09 — non-crypto `rand()` in RUDP key/IV/cookie material | **FIXED** — migrated to `generate_random_bytes()` / `RAND_bytes`; `srand()` removed | §3 |
| **B** | N-11 — `atoi()` parses `Content-Length` | **FIXED** — strict bounded parser at 3 sites | §4 |
| **C** | RISK-B — legacy `SoftEtherClient.disconnect()` | **FIXED** — method deleted; zero callers confirmed | §5 |
| **D** | SEC-02 / APP-1 — global cleartext HTTP | **FIXED** — HTTPS-only default + explicit IP allow-list | §6 |

**Suite results:** library `160 / 0 / 0 / 0`; app `647 / 0 / 0 / 1` (1 pre-existing legitimate skip).
**APK:** BUILD SUCCESSFUL, SHA-256 `9350ccf3c1a791ebd586c99bbefe94b9dfb891d62f88e1d008781491e69a1a2e`.

---

## 2. Preconditions

| Check | Result |
|-------|--------|
| `HEAD` == `origin/main` | `a60681ff9b788d57fd7d7b5587fd74df3716f8d4` (both) |
| Local branch ahead/behind | 0 / 0 |
| Tracked tree at start | clean (no staged/unstaged changes) |
| Device present | **NO** — no device command run |

---

## 3. PART A — N-09 (`rand()` → CSPRNG for RUDP key/IV/cookie material)

**Design source:** `AUDIT_SECURITY_AND_RISK_B_v1.0.14.md` §4.1. The wrapper
`int generate_random_bytes(uint8_t* buffer, size_t len)` already exists (`softether_crypto.h:60`,
defined `aes_wrapper.c:337`, `RAND_bytes` under `g_openssl_lock`) and was already used in
`rudp_transport.c`, so the migration is mechanical and low-risk. `aes_wrapper.c` was **not** touched.

### 3.1 `softether_rudp.c`

- **Added** `static uint32_t rudp_rand_u32(void)` (after `calc_key`, `:47`) — fills 4 bytes from the
  CSPRNG big-endian; returns `0` if the CSPRNG fails (callers keep their existing error handling).
- **Key/IV material (`rudp_create`, `:263`):** the six per-byte `rand()` loops for
  `my_key` / `your_key` / `my_key_v2` / `your_key_v2` / `next_iv` / `next_iv_v2` were replaced by one
  `generate_random_bytes(...)` call each (16-byte keys, 20-byte IVs).
- **Cookies (`:271`, `:275`):** `my_cookie` / `your_cookie` now use `rudp_rand_u32()`; the existing
  non-zero / non-duplicate `do { } while` guards are preserved.
- **Non-security uses converted for consistency:** keepalive jitter (`:847`) and pad size
  (`:1027`, `:1057`) → `rudp_rand_u32()`.
- **Verified:** no `rand()` / `srand()` remains (only explanatory comments).

### 3.2 `rudp_transport.c`

- **Added** `static uint32_t rt_rand_u32(void)` (after `rt_tick64`, `:39`) — identical CSPRNG body.
- **All 13 `rand()` sites converted** to `rt_rand_u32()`
  (`:331, :385, :417, :746, :775, :792, :855, :865, :1171, :1172, :1203, :1245`) — IV positioning,
  pad length, ICMP echo size/ID/seq, DNS transaction ID, and interval jitter.
- **`srand()` removed (`:1041`).** The call `srand((unsigned int)(rt_tick64() ^ (uintptr_t)t));` was
  deleted and replaced with a comment; the libc PRNG is now unused. This is the intended semantic
  change (per-call entropy instead of a single per-process seed).
- **`rt_derive_keys` fallback (`:1087`):** when the CSPRNG is unavailable, the fallback now derives the
  low 32 bits from the session's CSPRNG-generated `key_init` bytes instead of `rand()`. The primary
  path (`generate_random_bytes(rnd, ...)`) is unchanged.
- **Verified:** no `rand()` / `srand()` remains.

**A4 (failure propagation):** `generate_random_bytes` failure semantics are preserved — the key/IV
calls keep their prior best-effort handling, and `rt_rand_u32`/`rudp_rand_u32` return `0` on CSPRNG
failure so callers retain their existing bounds/guards. No new abort path was introduced.

---

## 4. PART B — N-11 (strict `Content-Length` parse)

**Design source:** `AUDIT_SECURITY_AND_RISK_B_v1.0.14.md` §4.2. All three sites had the identical shape
`content_length = (uint32_t)atoi(cl_str + 16);`.

- **Added** a static helper in the same translation unit (`softether_protocol.c:69`):
  - `#define N11_MAX_CONTENT_LENGTH (64u * 1024u * 1024u)` — 64 MiB sane maximum.
  - `static long parse_content_length(const char* s)` — returns **0** for empty/absent; **-1** for a
    malformed value (leading sign, no leading digit, trailing junk, or value `> N11_MAX_CONTENT_LENGTH`);
    otherwise the parsed value. It can never wrap to `0xFFFFFFFF`. Leading spaces/tabs are tolerated
    (atoi-compatible); the value must terminate at end-of-string or CR/LF/space/tab.
- **Call sites updated (3):**
  - `read_http_response` (`:854`) — `-1` → `LOGE(...)`, `return -1`.
  - `additional_connect` connect.cgi (`:4116`) — `-1` → `LOGE(...)`, then explicit cleanup
    (`ssl_shutdown` / `ssl_destroy` / `close(fd)`), `conn->additional_failed_count++`, `return -1`.
  - `additional_connect` auth (`:4252`) — same explicit cleanup and `return -1`.
- **Downstream bounds preserved:** the existing guards at `:838–839`, `:4078`, `:4207`
  (`content_length > 0` + buffer-size check) are untouched.
- **Helper kept static**, same translation unit — no new exported symbol.
- **Verified:** no `atoi(` remains in the file (only a comment referencing it).

---

## 5. PART C — RISK-B (delete dead `SoftEtherClient.disconnect()`)

**Design source:** `AUDIT_SECURITY_AND_RISK_B_v1.0.14.md` §5 (verdict **CLOSE** — dead code).

- **Deleted** the entire `fun disconnect()` block from `SoftEtherClient.kt` (old `:79–92`), including
  its KDoc. No stub, no `@Deprecated`, no commented block. Nothing else in the file changed.
- **C2 — caller audit (repo-wide `\.disconnect\(\)`):** every match is a **different receiver**:
  - `AutoModeController.kt` → `adapter.disconnect()` — `AndroidConnectionAdapter`.
  - `SstpVpnService.kt:295` → `controller?.disconnect()` — kittoku `Controller` (separate module).
  - `VpngateServerProvider.kt:83` → `connection.disconnect()` — `HttpURLConnection` (androidTest).
  - `vpnLib/.../IOpenVPNAPIService.java:218` → generated OpenVPN API (unrelated).
  - Test comments referencing `AndroidConnectionAdapter.disconnect()`.
  - **No** `client.disconnect()` / `this.disconnect()` on a `SoftEtherClient` anywhere. The only
    production instantiation (`ConnectionController.kt:83`) calls only native entry points
    (`nativeDisconnect` / `nativeDestroy`). **No production caller found → no STOP condition.**
- **C3:** no test pins `disconnect()` on `SoftEtherClient` (`SoftEtherClientHandleGuardTest` does not;
  `ReconnectTerminalPathTest` T4c pins `ConnectionController.disconnect()`, a different class). No test
  update required.
- **Residual (accepted):** `tag`, `isConnected` (field + getter) are now unused in the file; they
  compile as warnings only (the module has no `-Werror` / `allWarningsAsErrors`). Removing them is the
  wider follow-on flagged in design §5.2 and is **out of scope** here.

---

## 6. PART D — SEC-02 / APP-1 (scope cleartext, HTTPS-only default)

**Design source:** `AUDIT_SECURITY_AND_RISK_B_v1.0.14.md` §3.

### D1 — manifest
`app/src/main/AndroidManifest.xml:28`: `android:usesCleartextTraffic="true"` → **`"false"`**.
The `android:networkSecurityConfig="@xml/network_security_config"` reference (`:27`) is unchanged.

### D2 — network security config
`app/src/main/res/xml/network_security_config.xml` rewritten:
`<base-config cleartextTrafficPermitted="false" />` plus a single
`<domain-config cleartextTrafficPermitted="true">` listing **only** the six VPN Gate bootstrap IP
mirrors (each `<domain includeSubdomains="false">`):
`150.40.105.19`, `150.40.105.6`, `150.40.105.11`, `150.40.105.23`, `194.156.89.134`,
`121.186.186.97`. A comment block documents the coupling to `VpnGateUrls.BOOTSTRAP_MIRRORS`.

### D3 — source URLs
`VpnGateSources.kt`: removed `MAIN_HTTP_URL`, `API_HTTP_URL`, `MIRRORS_HTTP_URL` and their three uses
in `canonicalHtml()` / `canonicalApi()` / `canonicalDirectory()`. Those canonical lists are now
HTTPS-only (single element). The `BOOTSTRAP_MIRRORS` list (the six `http://<ip>:<port>` entries) is the
only remaining cleartext surface and is exactly what the `domain-config` permits; its KDoc was expanded
to state this.

### D4 — identity tags
No `SourceIdentity` tag / `SourceKind` was changed — only URL strings and the manifest/config were
touched.

**Policy:** the app-wide cleartext flag is now `false` and the config file governs; cleartext is
permitted **only** to the six enumerated bootstrap IPs. `www.vpngate.net` and all other hosts are
HTTPS-only.

---

## 7. PART E — build & tests

Commands run with `CI=true`, `--rerun-tasks`, `--no-build-cache`, `--no-daemon`, `--console=plain`.

| # | Task | Result | Counts (tests/fail/err/skip) |
|---|------|--------|------------------------------|
| E1 | `:SoftEtherClient:test` | **BUILD SUCCESSFUL** (1m28s) | **160 / 0 / 0 / 0** (9 XML files) |
| E2 | `:app:testProDebugUnitTest` | **BUILD SUCCESSFUL** (6m58s) | **647 / 0 / 0 / 1** (48 XML files) |
| E3 | `:app:assembleProDebug` | **BUILD SUCCESSFUL** (8m55s) | APK built for 4 ABIs |

- **E1 note:** the library suite does not compile the native C (no CMake task) — native correctness is
  covered by E3.
- **E2 skip:** the single skip is the pre-existing legitimate
  `vn.unlimit.vpngate.collector.HtmlParserOracleTest :: softetherUdpOnlyNeverBorrowsOpenvpnTcp`.
- **E3 native compile:** CMake compiled the edited C for all four ABIs (`arm64-v8a`, `armeabi-v7a`,
  `x86`, `x86_64`) — **the N-09 / N-11 changes compile and link cleanly.**
- **E3 artifact:** `app/build/outputs/apk/pro/debug/app-pro-debug.apk`, size 63,633,510 bytes,
  **SHA-256 `9350ccf3c1a791ebd586c99bbefe94b9dfb891d62f88e1d008781491e69a1a2e`**.

### E4 — merged-manifest cleartext policy

Both merged manifests
(`app/build/intermediates/merged_manifest/proDebug/processProDebugMainManifest/AndroidManifest.xml`
and `.../merged_manifests/proDebug/...`) contain:
`android:networkSecurityConfig="@xml/network_security_config"` and `android:usesCleartextTraffic="false"`.

Definitive check on the **packaged APK** (`aapt2 dump xmltree --file AndroidManifest.xml`):
```
A: http://schemas.android.com/apk/res/android:usesCleartextTraffic(0x010104ec)=false
A: http://schemas.android.com/apk/res/android:networkSecurityConfig(0x01010527)=@0x7f14000a
```

---

## 8. PART F — commits and push

Three commits (native, then Kotlin+Android, then report):

| # | Commit message | Files |
|---|----------------|-------|
| 1 | `fix(native): use RAND_bytes for RUDP keys/IVs/cookies (N-09); strict Content-Length parse (N-11)` | `softether_rudp.c`, `rudp_transport.c`, `softether_protocol.c` |
| 2 | `fix(security): remove dead SoftEtherClient.disconnect(); scope cleartext to explicit hosts (RISK-B, SEC-02)` | `SoftEtherClient.kt`, `AndroidManifest.xml`, `network_security_config.xml`, `VpnGateSources.kt` |
| 3 | `docs: report for FIX-NATIVE-SECURITY-SMALL` | `FIX_NATIVE_SECURITY_SMALL_REPORT.md` |

**Scope gate:** each commit stages only its authorized file set (explicit paths, never
`git add -A` / `add .`). The overall working tree contains exactly the 7 authorized production files
plus this report — no other tracked file is modified.

**Push:** `git push origin main` (fast-forward, **no** `--force`, **no** tag). Post-push verification
confirms `origin/main` equals `HEAD` and the branch is 0 ahead / 0 behind.

---

## 9. Scope confirmation (negative statements)

- **`aes_wrapper.c`: NOT changed.** SEC-01 (TLS verification) is deferred; the CSPRNG wrapper is used
  as-is.
- **`PROJECT_SPECIFICATION.md`: NOT changed in this phase.** Spec update is a separate DOCS-UPDATE
  phase. §22 L1–L10 and §1.6.2–§1.6.7 are untouched.
- **No forbidden Kotlin/C++ file changed:** `AutoModeController.kt`, `NativeLifetimeRegistry.kt`,
  `NativeHandleSlot.kt`, `NativeAttemptCoordinator.kt` are all untouched.
- **No build-file change:** no `build.gradle` / `settings.gradle` / `CMakeLists.txt` change.
- **No new Kotlin wrapper added;** the N-11 helper is a C `static` function in the same translation
  unit. No new JVM test was required (no pure Kotlin helper was added).
- **No device command** was issued.
- **No `--force`, no tag, no `reset`, no `clean`, no `stash`.** **C5 was not executed.**
- **No pre-existing report was modified.**

**Posture:** all four findings (N-09, N-11, RISK-B, SEC-02) are now **FIXED in source**. SEC-01 remains
**OPEN** (deferred). Device-level verification is deferred to a separate phase.
