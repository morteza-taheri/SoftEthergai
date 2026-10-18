# AUDIT-SECURITY-AND-RISK-B — Combined Design Document (v1.0.14)

**Phase:** AUDIT-DESIGN-SECURITY-AND-RISK-B
**Repository:** `C:\Dev\SoftEthergai` (canonical name `SoftEthergai`)
**Branch:** `main`
**Base HEAD at phase start:** `6cffd1eb63220b1bc5aaf7fb321b7e701205bfc3` (= `origin/main`)
**Spec version:** 1.0.13 → 1.0.14
**Date:** 2026-10-15
**Scope:** **read-only audit + design only.** No production Kotlin/Java/C/C++ change, no test change,
no Gradle/CMake/manifest/XML change. **No build, no test run, no APK, no device command.**

Evidence labels: **SOURCE CODE** / **TEST** / **PHYSICAL DEVICE** / **INFERENCE** / **UNKNOWN**.

> **This document contains no applied fix.** Every remediation below is a *design* with a
> **deferred** implementation. Findings SEC-01, SEC-02/APP-1, N-09 and N-11 remain **open**;
> RISK-B is assessed **CLOSE** (dead code) and also has no applied change here.

---

## 1. Executive verdict

**AUDIT COMPLETE.** Five findings were characterised against the current HEAD. No fix was applied
by design. One finding (RISK-B) resolves to dead code and can be closed by deletion; the four
security findings each have a concrete, costed remediation design and are carried forward as
deferred work.

| Finding | Class | Severity | Verdict | Remediation (deferred) |
|---------|-------|----------|---------|------------------------|
| **SEC-01** | Native TLS verification disabled | **HIGH** | OPEN | Design **B** — TOFU certificate-fingerprint pinning (recommended) |
| **SEC-02 / APP-1** | Cleartext HTTP permitted globally | **MEDIUM** | OPEN | Scope cleartext to named VPNGate hosts; HTTPS-first |
| **N-09** | Non-crypto `rand()` seeds RUDP key/IV/cookie material | **MEDIUM** | OPEN | Migrate key/IV/cookie sites to `generate_random_bytes()` |
| **N-11** | `atoi()` parses `Content-Length` | **LOW** | OPEN | Strict bounded parse; reject negative/non-numeric/overflow |
| **RISK-B** | Legacy `SoftEtherClient.disconnect()` may bypass `NativeHandleSlot` | **N/A (dead code)** | **CLOSE** | Delete the method (preferred) or `@Deprecated` it |

### 1.1 What this phase did

- Read the single production TLS-verification site and its whole configuration path (**SOURCE CODE**).
- Read the manifest and network-security-config, and the URL/bootstrap constants (**SOURCE CODE**).
- Enumerated every `rand()`/`srand()` and `atoi()` call in the native protocol layer (**SOURCE CODE**).
- Ran an exhaustive call-graph audit of the legacy `SoftEtherClient.disconnect()` — all
  instantiations, all call sites, reflection, and DI (**SOURCE CODE** + **TEST**).
- Produced three candidate designs for SEC-01 with a single recommendation, and migration plans
  for SEC-02/APP-1, N-09 and N-11.

### 1.2 What this phase deliberately did **not** do

No source, test, native, build, manifest or XML file was modified. No Gradle task ran. No APK was
built. No device command was issued. The only files this phase writes are **this document**, the
`DOCS_AUDIT_SECURITY_v1.0.14_REPORT.md` report, and the `PROJECT_SPECIFICATION.md` documentation update.

---

## 2. SEC-01 — Native TLS certificate verification is disabled

### 2.1 Audit — SOURCE CODE

There is exactly **one** certificate-verification decision in the whole production native tree,
and it turns verification **off**.

| Location | Code | Effect |
|----------|------|--------|
| `SoftEtherClient/src/main/cpp/softether-core/src/crypto/aes_wrapper.c:110` | `SSL_CTX_set_verify(c, SSL_VERIFY_NONE, NULL);` | **All** server certificates accepted; no verification callback |
| `…/crypto/aes_wrapper.c:101` | `static void ssl_configure_client_ctx(SSL_CTX* c)` | The function that owns the decision |
| `…/crypto/aes_wrapper.c:103` | `SSL_CTX_set_min_proto_version(c, TLS1_VERSION);` | Floor = TLS 1.0 |
| `…/crypto/aes_wrapper.c:104` | `SSL_CTX_set_max_proto_version(c, TLS1_2_VERSION);` | Ceiling = TLS 1.2 |
| `…/crypto/aes_wrapper.c:355` → `:376` | `ssl_create_client()` → `ssl_configure_client_ctx(c)` | The client-context factory that installs the policy |
| `…/crypto/aes_wrapper.c:410`, `:428` | `ssl_connect(…, const char* hostname)` → `SSL_set_tlsext_host_name(ctx->ssl, hostname)` | SNI is sent, but see below |

**Negative evidence (grepped across the tree, all absent from the client path):**
`SSL_CTX_load_verify_locations`, `SSL_CTX_set_default_verify_paths`, `SSL_set1_host`,
`X509_VERIFY_PARAM_set1_host`, `SSL_get_verify_result`, `SSL_CTX_set_cert_verify_callback`,
and any certificate fingerprint/SPKI pin. There is **no** trust anchor and **no** hostname binding.

**SNI detail (SOURCE CODE, `aes_wrapper.c:424–428`):** the source comment states
*"hostname is always a resolved IP address at this point … OpenSSL sends SNI with the IP string;
VPNGate servers accept this."* So even if verification were switched on, the value available to bind
a hostname check to is an **IP literal**, not a DNS name — which would make an off-the-shelf
`SSL_set1_host` check both meaningless and un-satisfiable for these endpoints.

### 2.2 What the certificate actually is today

VPN Gate relays are volunteer-operated endpoints. They commonly present **self-signed or
otherwise non-public-CA** certificates, and the client connects by **IP**. Therefore the
conventional PKI model (trust a public CA + match the DNS name) is a poor fit: turning on
`SSL_VERIFY_PEER` with the system store would break the majority of relays (**INFERENCE**,
consistent with the source comment at `aes_wrapper.c:424–426`). This constraint is what drives the
design choice below.

### 2.3 Candidate designs

**Design A — Public-CA verification + hostname match.**
Load a trust store (`SSL_CTX_load_verify_locations` / `SSL_CTX_set_default_verify_paths`), set
`SSL_VERIFY_PEER`, and bind the expected name (`SSL_set1_host`).
*Pros:* strongest, standards-compliant.
*Cons:* incompatible with VPN Gate's self-signed/IP-only endpoints — would reject most relays and
contradicts the IP-SNI design; requires either real names or an IP-SAN PKI that does not exist here.
**Rejected.**

**Design B — TOFU certificate-fingerprint pinning (recommended).**
On the **first** successful TLS handshake to a given endpoint, compute the SHA-256 of the leaf
certificate and persist `(serverId → fingerprint)`. On every subsequent connect, recompute and
compare: a **mismatch blocks the connection** (or raises a user-visible warning and requires
explicit re-trust). A brand-new endpoint is TOFU-accepted and recorded.
*Pros:* needs no CA and no DNS name; directly detects MITM/relay-substitution after first use;
matches the volunteer-relay trust reality; small, local change surface.
*Cons:* first connection is still unauthenticated (inherent to TOFU); needs a small persistent
store and a decision about where the fingerprint is keyed (per IP:port or per relay identity).
**Recommended.**

**Design C — SPKI / public-key pinning against a fixed key set.**
Pin a pre-distributed set of SubjectPublicKeyInfo hashes.
*Pros:* strongest against key-substitution when the key set is trustworthy.
*Cons:* requires a stable, curated key set for a **dynamic volunteer pool** — none exists, and
maintaining one is operationally infeasible.
**Rejected.**

| Criterion | A (CA+hostname) | **B (TOFU pin)** | C (SPKI set) |
|-----------|-----------------|------------------|--------------|
| Works with self-signed VPN Gate certs | ✗ | **✓** | ✓ (if key known) |
| Needs DNS name (only IP available) | ✗ | **✓ (no)** | ✓ (no) |
| Detects post-first-use MITM | ✓ | **✓** | ✓ |
| Operationally maintainable here | ✗ | **✓** | ✗ |
| Change surface | large | **small** | medium |

### 2.4 Recommendation (implementation DEFERRED)

Adopt **Design B (TOFU fingerprint pinning)** as the baseline, optionally layered with an
**opt-in strict mode** (pin must exist before connect is allowed) for users who want it. Implementation
is **out of scope for this phase** (it requires a C change, which this phase forbids) and is deferred
to a dedicated remediation phase. The design's open sub-questions — fingerprint key (IP:port vs relay
identity), store location, and the mismatch UX — are listed in §6.

### 2.5 A4 — Does the OkHttp HTTPS path verify certificates?

**Yes (SOURCE CODE).** The HTTP/collector path (`data/remote/HttpFetcher.kt`,
`collectorClientBuilder()`) uses **OkHttp with the default Android/system trust store**, so HTTPS
requests are certificate- and hostname-verified. `ResilientDns` additionally filters poisoned IPs and
falls back to `VPNGATE_FALLBACK_IPS`. **Consequence:** SEC-01 is confined to the **native SoftEther
tunnel**; the metadata-fetch path is not affected by this finding.

---

## 3. SEC-02 / APP-1 — Cleartext HTTP is permitted globally

### 3.1 Audit — SOURCE CODE

| Location | Code | Effect |
|----------|------|--------|
| `app/src/main/AndroidManifest.xml:28` | `android:usesCleartextTraffic="true"` | App-wide cleartext allowed |
| `app/src/main/AndroidManifest.xml:27` | `android:networkSecurityConfig="@xml/network_security_config"` | Points at the config below |
| `app/src/main/res/xml/network_security_config.xml:3` | `<base-config cleartextTrafficPermitted="true" />` | **Blanket** cleartext for every domain |
| `app/src/main/java/vn/unlimit/vpngate/data/remote/VpnGateSources.kt` | `VpnGateUrls`: `MAIN_HTTP_URL`, `API_HTTP_URL`, `MIRRORS_HTTP_URL`, plus `BOOTSTRAP_MIRRORS` (6 HTTP IP URLs) | HTTP endpoints exist as canonical **and** bootstrap values |

**Existing mitigations (SOURCE CODE):** HTTPS candidates are attempted **before** their HTTP
fallbacks, and `ResilientDns` defends against DNS poisoning. So cleartext is a **fallback path**, not
the default — but the *policy* still permits cleartext to *any* host, which is broader than the
functional need.

### 3.2 Migration plan (design, DEFERRED)

1. Flip the default: `base-config cleartextTrafficPermitted="false"`.
2. Add a **scoped** `domain-config` with `cleartextTrafficPermitted="true"` listing **only** the
   VPN Gate hosts / bootstrap IPs that genuinely need an HTTP fallback.
3. Keep the HTTPS-first ordering in `VpnGateSources`; treat HTTP strictly as the last resort for the
   enumerated hosts.
4. Re-evaluate `android:usesCleartextTraffic="true"` — once the scoped `domain-config` covers the
   fallback hosts, the app-wide flag can be dropped (the config file then governs).

**Open questions** (see §6): the exact host/IP allow-list, whether IP-literal bootstrap mirrors can be
expressed in `domain-config` (they can — `<domain>` accepts IPs), and whether removing the app-wide
flag affects any other module. Implementation is **deferred** (manifest/XML change is forbidden here).

---

## 4. N-09 + N-11 — Native code-read audit (no fix applied)

### 4.1 N-09 — non-cryptographic `rand()` feeds key/IV/cookie material

`rand()` (libc PRNG, no cryptographic guarantees) is used in the RUDP layer. Sites (**SOURCE CODE**):

**`…/proto/softether_rudp.c`** — key / IV / cookie material:
`:251 :253 :255 :257 :259 :261` (RC4 key bytes + `next_iv`), `:264 :268` (my/your cookie).
Non-security uses: `:838` (keepalive jitter), `:1018 :1048` (pad size).

**`…/proto/rudp_transport.c`** — `srand((unsigned int)(rt_tick64() ^ (uintptr_t)t));` at `:1026`,
and `rand()` at `:316 :370 :402 :731 :760 :777 :840 :850 :1074 :1151 :1152 :1183 :1225`
(mix of IV positioning, pad length, ICMP/DNS IDs, jitter).

**The wrapper already exists (SOURCE CODE):** `int generate_random_bytes(uint8_t* buffer, size_t len)`
at `aes_wrapper.c:337` returns `0` on success, calls `RAND_bytes` under `g_openssl_lock`
(`:343–345`), and is **already used** in `rudp_transport.c` at `:217 :319 :736 :751 :1070 :1216 :1221`.
This makes the migration low-risk: the target API is in-tree, thread-safe, and already exercised.

**Migration plan (design, DEFERRED):** replace the key/IV/cookie `rand()` sites with
`generate_random_bytes()`; keep or convert the non-security sites at the author's discretion
(consistency is cheap). Remove `srand()` (`rudp_transport.c:1026`) once no `rand()` remains.
Note the **semantic change**: `srand` seeds a per-process stream; `RAND_bytes` is per-call entropy —
i.e. the current "single-seed acceptance" assumption is replaced by per-call entropy, which is the
point of the fix. Entropy needs: 16/32-byte keys → one `generate_random_bytes` call each; cookies
(4 bytes) and IDs (2 bytes) → direct small calls.

### 4.2 N-11 — `atoi()` parses `Content-Length`

Three sites (**SOURCE CODE**), all identical in shape:

- `…/proto/softether_protocol.c:818` (`read_http_response`)
- `…/proto/softether_protocol.c:4075` (`additional_connect` connect.cgi)
- `…/proto/softether_protocol.c:4202` (`additional_connect` auth)

`content_length = (uint32_t)atoi(cl_str + 16);`

**Behaviour:** `atoi` is unguarded — a negative or malformed value casts to a huge `uint32_t`
(e.g. `-1` → `0xFFFFFFFF`). **However, every site is guarded downstream (SOURCE CODE):**
`:838–839` (`content_length > 0` **and** `hdr_len + content_length > buf_size` → return `-1`),
`:4078` and `:4207` (`content_length > 0 && hdr_len + (int)content_length < (int)sizeof(resp)`).
So a malformed value is currently rejected by the bounds check rather than causing an over-read —
the finding is a **robustness/defence-in-depth** issue, not a demonstrated exploit (**INFERENCE**).

**Migration design (DEFERRED):** replace `atoi` with a strict bounded parser (e.g. `strtol` with
`errno`/end-pointer checks) that accepts only non-negative decimal values within `buf_size`, and
treats anything else as `0`. This makes the intent explicit and removes reliance on the downstream
guard. **No code change in this phase.**

---

## 5. RISK-B — call-graph verdict

### 5.1 Verdict: **CLOSE** (dead code — unreachable body, zero callers)

**RISK-B** was the concern that the legacy `SoftEtherClient.disconnect()` might tear down a native
handle **outside** `NativeHandleSlot`, breaking the single-owner invariant. The audit shows the
method can neither be reached **nor** run its body.

**Evidence (SOURCE CODE unless noted):**

1. **Unreachable body.** `SoftEtherClient.kt:82–92`:
   `if (!isConnected.getAndSet(false) || nativeHandle == 0L) { return }`.
   - `isConnected` (`AtomicBoolean`, declared `:14`) is **never** set to `true` anywhere in the tree
     (no `.set(true)`; the only write is `getAndSet(false)` at `:83`). → `!false == true` → **returns
     immediately**.
   - `nativeHandle` (`:13`) is **never** assigned a non-zero value (only `= 0` at `:13` and `:90`).
   Either condition alone short-circuits the body; **both** hold.

2. **Zero production callers.** The only production instantiation is
   `ConnectionController.kt:83` (`private val client = SoftEtherClient()`), and the controller
   **never** calls `client.disconnect()`. Its teardown calls the native entry points directly:
   `client.nativeDisconnect(handle)` + `client.nativeDestroy(handle)` (`ConnectionController.kt:510–511`),
   inside `destroyClaimedNativeHandle`, which then `settleDestroyed()`s the slot — i.e. teardown is
   correctly slot-mediated, and the legacy method is bypassed entirely.

3. **Zero test callers.** `ThroughputBenchmarkTest.kt:114` and `:234` instantiate `SoftEtherClient()`
   (androidTest) but never call `.disconnect()` — they drive `nativeCreate`/`externalHandle`/
   `nativeConnectWithHub`/`doDhcp` etc. (**TEST**).

4. **No dynamic dispatch.** No `getDeclaredMethod`, `getMethod(`, `Class.forName`, or `::disconnect`
   anywhere in the tree (**SOURCE CODE**, repo-wide grep).

5. **No holder calls it.** `KeepAliveManager(client)` (`ConnectionController.kt:1020`) stores the
   client but its body **never references it** — no `.disconnect()`.

6. **Not pinned by any test.** `SoftEtherClientHandleGuardTest` asserts the `setTimeout`/`setHalfConnection`
   overloads and controller call sites only — it does **not** assert `disconnect()` exists (**TEST**).
   (`ReconnectTerminalPathTest` T4c pins `fun disconnect()` but that is **`ConnectionController`**'s
   own method, a different class.)

7. **The only other `.disconnect()` calls are different types.** `AutoModeController` →
   `adapter.disconnect()` (`AndroidConnectionAdapter`); `SstpVpnService.kt:295` → `controller?.disconnect()`
   (kittoku `Controller`); `VpngateServerProvider.kt:83` → `connection.disconnect()` (`HttpURLConnection`,
   androidTest). None is a `SoftEtherClient`.

**Instantiation inventory:**

| Site | Receiver | `.disconnect()` called? |
|------|----------|--------------------------|
| `ConnectionController.kt:83` | `SoftEtherClient` | **No** (uses `nativeDisconnect`/`nativeDestroy` at `:510–511`) |
| `ThroughputBenchmarkTest.kt:114` (androidTest) | `SoftEtherClient` | **No** |
| `ThroughputBenchmarkTest.kt:234` (androidTest) | `SoftEtherClient` | **No** |

### 5.2 Recommendation (DEFERRED — no source change in this phase)

**Delete `SoftEtherClient.disconnect()`.** Nothing calls it, nothing pins it, and its body is
unreachable; deletion is safe and removes a latent footgun that could re-introduce a
`NativeHandleSlot`-bypassing teardown if a future caller were wired to it. If a softer step is
preferred, mark it `@Deprecated` with a message pointing at the slot-mediated
`ConnectionController` teardown — but deletion is the cleaner end state. (A follow-on could also drop
the now-orphaned `isConnected` field/`isConnected()` getter, which likewise has no caller — flagged
as a separate, wider change.)

---

## 6. Consolidated remediation roadmap (prioritised, all DEFERRED)

| Priority | Finding | Proposed action | Change surface | Suggested test strategy |
|----------|---------|-----------------|----------------|-------------------------|
| P1 | SEC-01 | TOFU fingerprint pinning (Design B) | native `aes_wrapper.c` + a small persistent store | unit: fingerprint record/compare; on-device: first-connect accept, mismatch block |
| P2 | N-09 | `rand()` → `generate_random_bytes()` for key/IV/cookie; drop `srand()` | native `softether_rudp.c`, `rudp_transport.c` | build + native tests; entropy-length assertions |
| P2 | SEC-02 / APP-1 | Scope cleartext to named hosts; HTTPS-first | manifest + `network_security_config.xml` | policy assertion; connectivity smoke |
| P3 | N-11 | Strict bounded `Content-Length` parse | native `softether_protocol.c` (3 sites) | unit: negative/overflow/non-numeric → 0 |
| P3 | RISK-B | Delete legacy `SoftEtherClient.disconnect()` | `SoftEtherClient.kt` | confirm no callers; compile |

**Open design questions carried forward:**
SEC-01 — fingerprint key (IP:port vs relay identity), store location/format, mismatch UX, strict-mode
default. SEC-02 — exact host/IP allow-list; whether to drop the app-wide cleartext flag. N-09 — which
non-security sites to convert. RISK-B — delete vs `@Deprecated`; whether to also remove the orphaned
`isConnected` members.

---

## 7. Evidence index

| Finding | File:line | Label |
|---------|-----------|-------|
| SEC-01 | `aes_wrapper.c:110`, `:101`, `:103`, `:104`, `:355`, `:376`, `:410`, `:424–428` | SOURCE CODE |
| SEC-01 (absent CA/pin) | repo-wide grep (no matches) | SOURCE CODE |
| SEC-01 (OkHttp verifies) | `data/remote/HttpFetcher.kt` (`collectorClientBuilder`) | SOURCE CODE |
| SEC-02 / APP-1 | `AndroidManifest.xml:27–28`; `network_security_config.xml:3`; `VpnGateSources.kt` | SOURCE CODE |
| N-09 | `softether_rudp.c:251…268, 838, 1018, 1048`; `rudp_transport.c:316…1225`, `:1026` | SOURCE CODE |
| N-09 (wrapper) | `aes_wrapper.c:337, 344` | SOURCE CODE |
| N-11 | `softether_protocol.c:818, 4075, 4202` (+ guards `:838–839, 4078, 4207`) | SOURCE CODE |
| RISK-B | `SoftEtherClient.kt:13–14, 82–92, 170`; `ConnectionController.kt:83, 510–511, 1020` | SOURCE CODE |
| RISK-B | `ThroughputBenchmarkTest.kt:114, 234`; `SoftEtherClientHandleGuardTest` | TEST |

---

## 8. Scope & non-goals

**In scope:** read-only audit of the five findings; design of their remediations; this document.

**Out of scope / not done (per the phase brief):** any modification to production Kotlin/Java/C/C++,
tests, Gradle, CMake, manifest, or XML; the §22 L1–L10 invariants; §1.6.2–§1.6.6; any existing report;
any device command; any build or test run. Implementation of every remediation above is **deferred**.

**Posture:** no remediation was applied. The four security findings remain **open**; RISK-B is
assessed **CLOSE** and its removal is also deferred.
