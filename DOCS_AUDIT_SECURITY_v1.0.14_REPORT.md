# AUDIT-DESIGN-SECURITY-AND-RISK-B — Report

**Phase:** AUDIT-DESIGN-SECURITY-AND-RISK-B
**Repository:** `C:\Dev\SoftEthergai` (canonical name `SoftEthergai`)
**Branch:** `main`
**Base HEAD at phase start:** `6cffd1eb63220b1bc5aaf7fb321b7e701205bfc3` (= `origin/main`)
**Spec version:** 1.0.13 → 1.0.14
**Date:** 2026-10-15
**Scope:** **read-only audit + design only.** No production Kotlin/Java/C/C++ change, no test change, no
Gradle/CMake/manifest/XML change. **No build, no test run, no APK, no device command.**

Evidence labels: **SOURCE CODE** / **TEST** / **PHYSICAL DEVICE** / **INFERENCE** / **UNKNOWN**.

---

## 1. Executive verdict

**PASS.** All five findings were characterised against HEAD `6cffd1e`, the four security findings each
carry a concrete deferred design, and RISK-B is closed as dead code. **No fix was applied** — the phase
is audit + design only, as scoped.

| Finding | Severity | Outcome | Where recorded |
|---------|----------|---------|----------------|
| **SEC-01** (== N-06) | HIGH | OPEN — Design B (TOFU fingerprint pinning) recommended; implementation deferred | §1.6.7; `AUDIT_SECURITY_AND_RISK_B_v1.0.14.md` §2 |
| **SEC-02 / APP-1** | MEDIUM | OPEN — scope-cleartext migration designed; deferred | §1.6.7; design doc §3; §12.5.1 row 13 |
| **N-09** | MEDIUM | OPEN — migrate to `generate_random_bytes()`; deferred | §1.6.7; design doc §4; §12.5.1 row 5 (corrected) |
| **N-11** | LOW | OPEN — strict bounded parse designed; deferred | §1.6.7; design doc §4; §12.5.1 row 14 |
| **RISK-B** | N/A | **CLOSE** — dead code; deletion recommended, deferred | §1.6.7; design doc §5; §12.5.1 row 7 (now CLOSED) |

### 1.1 Deviations from the phase brief — flagged

1. **§1.6.1 row to append.** The brief said to append **`b38efa7` + `6cffd1e`**. In the actual
   repository, `b38efa7` was **already** added as row 41 by the v1.0.13 phase (that was FLAG-3's job).
   Only `6cffd1e` was outstanding, so **only `6cffd1e` was appended** (row 42). Appending `b38efa7`
   again would have duplicated a row. The chain therefore moves **41 → 42**, range-exclusive **40 → 41**
   (both verified by `git log`, §7).

2. **Stale line numbers in the brief for N-09 / N-11.** The brief's `softether_rudp.c:154–173`,
   `rudp_transport.c:316,370,402,1146` and `softether_protocol.c:813–819` do **not** match the current
   files. The real sites were located by grep and are recorded in §4 and in §1.6.7 / §12.5.1 row 5.

3. **Header HEAD field.** Following the v1.0.13 precedent (the field records the HEAD that exists when
   the spec is written), and because the brief orders the spec edit **before** the phase's commits, the
   field was advanced to the **base HEAD** `6cffd1e` — which is also the new last row of §1.6.1 (the two
   coincide, exactly as they did in v1.0.13 where both were `b38efa7`).

---

## 2. PART A — SEC-01 (native TLS verification disabled)

**Audit (SOURCE CODE).** Exactly one production verification decision exists and it disables
verification: `SSL_CTX_set_verify(c, SSL_VERIFY_NONE, NULL)` at `aes_wrapper.c:110`, inside
`ssl_configure_client_ctx` (`:101`), installed by `ssl_create_client` (`:355` → `:376`). Protocol window
is TLS 1.0–1.2 (`:103`/`:104`). **Negative evidence** (repo-wide grep, all absent from the client path):
`SSL_CTX_load_verify_locations`, `SSL_CTX_set_default_verify_paths`, `SSL_set1_host`,
`X509_VERIFY_PARAM_set1_host`, `SSL_get_verify_result`, `SSL_CTX_set_cert_verify_callback`, and any
fingerprint/SPKI pin. SNI is set to the **resolved IP** (`:424–428`, with an explicit source comment
saying so). **Effect:** any server certificate is accepted; MITM is feasible and there is no hostname
binding.

**What the certificate is today (INFERENCE).** VPN Gate relays are volunteer endpoints presenting
self-signed / non-public-CA certificates, reached by IP. Conventional PKI verification would break most
relays — which is exactly why the code disables it.

**Three candidate designs compared:**

| Design | Works w/ self-signed VPN Gate | Needs a DNS name | Detects post-first-use MITM | Maintainable | Verdict |
|--------|:---:|:---:|:---:|:---:|---------|
| A — public-CA + hostname match | ✗ | ✗ | ✓ | ✗ | **Rejected** |
| **B — TOFU fingerprint pinning** | **✓** | **✓ (no)** | **✓** | **✓** | **Recommended** |
| C — SPKI key set | ✓ (if key known) | ✓ (no) | ✓ | ✗ | **Rejected** |

**Recommendation (A3):** adopt **Design B — TOFU certificate-fingerprint pinning** (record the leaf
cert SHA-256 on first successful connect; enforce thereafter; mismatch blocks/ warns), optionally layered
with an opt-in strict mode. **Implementation deferred** (requires a C change, forbidden this phase).

**A4 — does the OkHttp HTTPS path verify? (SOURCE CODE)** **Yes.** `data/remote/HttpFetcher.kt`'s
`collectorClientBuilder()` uses OkHttp with the Android/system trust store, so HTTPS requests are
certificate- and hostname-verified. **SEC-01 is confined to the native tunnel**; the metadata path is
unaffected.

---

## 3. PART B — SEC-02 / APP-1 (cleartext HTTP permitted globally)

**Audit (SOURCE CODE).**

| Location | Code |
|----------|------|
| `app/src/main/AndroidManifest.xml:28` | `android:usesCleartextTraffic="true"` |
| `app/src/main/AndroidManifest.xml:27` | `android:networkSecurityConfig="@xml/network_security_config"` |
| `app/src/main/res/xml/network_security_config.xml:3` | `<base-config cleartextTrafficPermitted="true" />` (**blanket**) |
| `…/data/remote/VpnGateSources.kt` | `VpnGateUrls` HTTP canonical URLs + `BOOTSTRAP_MIRRORS` (6 HTTP IP URLs) |

**Existing mitigations:** HTTPS candidates are tried **before** HTTP fallbacks, and `ResilientDns`
defends against DNS poisoning. Cleartext is a fallback path, not the default — but the *policy* still
permits cleartext to **any** host.

**Migration plan (deferred):** set `base-config cleartextTrafficPermitted="false"`; add a scoped
`domain-config` allowing cleartext only for the named VPN Gate hosts / bootstrap IPs; keep HTTPS-first;
re-evaluate dropping the app-wide `usesCleartextTraffic` flag once the scoped config covers the
fallbacks.

---

## 4. PART C — N-09 + N-11 (code-read audit; no fix)

### 4.1 N-09 — non-crypto `rand()` for key/IV/cookie material (SOURCE CODE)

Actual sites (the brief's refs were stale — see §1.1):

- `…/proto/softether_rudp.c` — `:251 :253 :255 :257 :259 :261` (RC4 key bytes + `next_iv`),
  `:264 :268` (my/your cookie); non-security: `:838` (keepalive jitter), `:1018 :1048` (pad).
- `…/proto/rudp_transport.c` — `srand(...)` at `:1026`; `rand()` at
  `:316 :370 :402 :731 :760 :777 :840 :850 :1074 :1151 :1152 :1183 :1225`.

The crypto wrapper **already exists**: `int generate_random_bytes(uint8_t*, size_t)` at
`aes_wrapper.c:337` (returns `0` on success, `RAND_bytes` under `g_openssl_lock`, `:343–345`), and is
**already used** at `rudp_transport.c:217 :319 :736 :751 :1070 :1216 :1221`.

**Migration design (deferred):** route the key/IV/cookie sites through `generate_random_bytes()`;
remove `srand()` once no `rand()` remains. Semantic change noted: `srand` = per-process stream;
`RAND_bytes` = per-call entropy (the point of the fix).

### 4.2 N-11 — `atoi()` parses `Content-Length` (SOURCE CODE + INFERENCE)

Three sites: `…/proto/softether_protocol.c:818` (`read_http_response`), `:4075` and `:4202`
(`additional_connect`), each `content_length = (uint32_t)atoi(cl_str + 16);`. A negative/malformed
value casts to `0xFFFFFFFF`, **but every site is guarded downstream** — `:838–839` (`content_length > 0`
and `hdr_len + content_length > buf_size` → `return -1`), `:4078`, `:4207` (both
`content_length > 0 && hdr_len + (int)content_length < (int)sizeof(resp)`). So it is
**robustness / defence-in-depth**, not a demonstrated exploit.

**Migration design (deferred):** replace `atoi` with a strict bounded parser (`strtol` + `errno` /
end-pointer), accepting only non-negative decimals within `buf_size`, else `0`. No code change here.

---

## 5. PART D — RISK-B (exhaustive call-graph audit)

**Verdict: CLOSE (dead code — unreachable body, zero callers).**

**Evidence (SOURCE CODE unless noted):**

1. **Unreachable body.** `SoftEtherClient.kt:82–92`: guard
   `if (!isConnected.getAndSet(false) || nativeHandle == 0L) { return }`. `isConnected` (`:14`) is
   **never set true** anywhere; `nativeHandle` (`:13`) is **never assigned non-zero** (only `= 0` at
   `:13`/`:90`). Either condition short-circuits; **both** hold.
2. **Zero production callers.** The sole production instantiation, `ConnectionController.kt:83`, tears
   down via `client.nativeDisconnect(handle)` + `client.nativeDestroy(handle)` (`:510–511`) inside
   `destroyClaimedNativeHandle`, which then `settleDestroyed()`s the slot — teardown is correctly
   slot-mediated; `client.disconnect()` is never called.
3. **Zero test callers.** `ThroughputBenchmarkTest.kt:114`, `:234` (androidTest) instantiate
   `SoftEtherClient()` but never call `.disconnect()` (**TEST**).
4. **No dynamic dispatch.** No `getDeclaredMethod`, `getMethod(`, `Class.forName`, or `::disconnect`
   anywhere (repo-wide grep).
5. **Holder doesn't call it.** `KeepAliveManager(client)` (`ConnectionController.kt:1020`) stores the
   client but its body never references it.
6. **Not pinned by any test.** `SoftEtherClientHandleGuardTest` asserts the `setTimeout` /
   `setHalfConnection` overloads and controller call sites only — **not** `disconnect()` (**TEST**).
   (`ReconnectTerminalPathTest` T4c pins `fun disconnect()` on **`ConnectionController`**, a different
   class.)
7. **Other `.disconnect()` calls are different types.** `AndroidConnectionAdapter.disconnect()`;
   `SstpVpnService.kt:295` → kittoku `Controller.disconnect()`; `VpngateServerProvider.kt:83` →
   `HttpURLConnection.disconnect()` (androidTest). None is a `SoftEtherClient`.

**Recommendation (deferred):** **delete `SoftEtherClient.disconnect()`** (nothing calls it, nothing pins
it, body unreachable); alternatively `@Deprecated`. No source change made.

---

## 6. PART E — combined design document

Created **`AUDIT_SECURITY_AND_RISK_B_v1.0.14.md`** at the repository root: §1 executive verdict; §2
SEC-01 (audit, three designs, recommendation); §3 SEC-02/APP-1 (audit + migration plan); §4 N-09 + N-11
(code-read audit + migration design); §5 RISK-B (call-graph verdict **CLOSE** with full evidence); §6
consolidated deferred remediation roadmap; §7 evidence index; §8 scope & non-goals. All designs are
labelled **deferred** and every claim carries an evidence label.

---

## 7. PART F — specification update (v1.0.13 → v1.0.14)

Edits to `PROJECT_SPECIFICATION.md` (documentation only):

| Location | Change |
|----------|--------|
| Header | `Document version` 1.0.13 → **1.0.14**; `HEAD` → `6cffd1e…`; `Last updated` stays 2026-10-15 |
| §1.6.1 intro | "Forty-one" → **"Forty-two"**; range `0714628` → `b38efa7` becomes `0714628` → `6cffd1e`; range-exclusive **40 → 41** |
| §1.6.1 table | Appended row 42: `6cffd1e` — *docs: update PROJECT_SPECIFICATION to v1.0.13 (canonical repo path + §1.6.1 sanity)* |
| §1.6.1 notes | Added **Push state (v1.0.14)** note (records `6cffd1e` by hash; names this revision's two commits by subject; chain 42 → 44) |
| **NEW §1.6.7** | *Security Audit and RISK-B Closure (v1.0.14)* — placed immediately after §1.6.6, before `## 2` |
| §12.5.1 row 4 | N-06 → **N-06 / SEC-01**; status → OPEN — design proposed (TOFU), deferred |
| §12.5.1 row 5 | N-09 line refs **corrected** to the actual sites; status → migration design, deferred |
| §12.5.1 row 7 | RISK-B → **CLOSED (v1.0.14)** |
| §12.5.1 rows 13/14 | **NEW** — SEC-02/APP-1 (P2) and N-11 (P3) |
| §20 changelog | **NEW row 1.0.14** |
| §20 Verification Status | Updated the §12.5.1 row; added a **§1.6.7** row |

**Not touched (per the brief's forbidden list):** §1.6.2–§1.6.6 (their content is unchanged; only §1.6.7
was added after §1.6.6), §22 L1–L10, and any existing report.

**§1.6.1 chain sanity (SOURCE CODE — `git log`):**

| Measurement | Value |
|-------------|-------|
| `git log --oneline 0714628^..HEAD \| wc -l` (inclusive) | **42** |
| `git log --oneline 0714628..HEAD \| wc -l` (exclusive) | **41** |
| §1.6.1 table data rows after this revision | **42** |
| delta (inclusive N − table M) | **0** |

---

## 8. PART G — commits and push

Two commits (design-doc first, then spec + report):

| # | Commit message | Files |
|---|----------------|-------|
| 1 | `docs(audit): security (SEC-01/SEC-02/N-09/N-11) + RISK-B design` | `AUDIT_SECURITY_AND_RISK_B_v1.0.14.md` |
| 2 | `docs: update PROJECT_SPECIFICATION to v1.0.14 (security design)` | `PROJECT_SPECIFICATION.md`, `DOCS_AUDIT_SECURITY_v1.0.14_REPORT.md` |

**Scope gate:** only the three authorized files are staged (no `git add -A` / `add .`). No production
Kotlin/Java/C/C++, test, Gradle, CMake, manifest or XML file is touched. **No build, no test, no device.**

**Push:** `git push origin main` (fast-forward, no `--force`, no tag). Post-push verification confirms
`origin/main` equals `HEAD` and the local branch is **0 ahead / 0 behind**.

---

## 9. Scope confirmation

- **Files written by this phase:** `AUDIT_SECURITY_AND_RISK_B_v1.0.14.md`,
  `DOCS_AUDIT_SECURITY_v1.0.14_REPORT.md`, and the `PROJECT_SPECIFICATION.md` documentation update.
- **Files forbidden and untouched:** all production Kotlin/Java/C/C++; all tests; Gradle/CMake/manifest/
  XML; §22 L1–L10; §1.6.2–§1.6.6; every pre-existing report.
- **Commands forbidden and not run:** any build, any test run, any APK assembly, any `adb`/device
  command, any `git add -A` / `add .`, any `--force` / tag / reset / clean / stash, the C5 item.

**Posture:** no remediation applied. SEC-01, SEC-02/APP-1, N-09 and N-11 remain **open** with deferred
designs; RISK-B is **CLOSE** with deferred removal.
