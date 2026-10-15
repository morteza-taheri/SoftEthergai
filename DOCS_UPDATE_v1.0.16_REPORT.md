# DOCS-UPDATE-v1.0.16 — Report

**Phase:** DOCS-UPDATE-v1.0.16
**Repository:** `C:\Dev\SoftEthergai` (canonical name `SoftEthergai`)
**Branch:** `main`
**HEAD at phase start and end of the spec edit:** `e6b9eef371a0190156021c8ea51e18e6ec3ee4a3` (= `origin/main`)
**Spec version:** 1.0.15 → **1.0.16**
**Date:** 2026-10-15
**Device:** not used — **this phase is device-free by design**
**Scope:** record SEC-01 (TOFU certificate-fingerprint pinning) in the specification as CLOSED with an explicit
**PARTIAL** caveat on the reconnect-path verification.

Evidence labels: **SOURCE CODE** / **TEST** / **PHYSICAL DEVICE** / **INFERENCE** / **UNKNOWN** / **PRIOR REPORT**.

---

## 1. Executive verdict

**PASS — the spec advanced to v1.0.16 with surgical, in-scope edits only.** A new **§1.6.9** records the
SEC-01 TOFU-pinning design landing (Design B), the reconnect-path fix, and the two source-shape tests;
**§12.5.1 row 4** moved from `OPEN — design proposed` to `CLOSED (v1.0.16)` and a new **row 15** records the
opportunistic reconnect re-capture; the **§1.6.1** chain was advanced to **53** commits through `e6b9eef`
with the inclusive-count vs table-row **delta = 0**; and the **§20** changelog + verification-status tables
gained their v1.0.16 rows.

The two protected regions were proven untouched: **§22 (L1–L10)** and **§1.6.2–§1.6.8** are **byte-identical**
to HEAD. All 10 diff hunks fall inside the header, §1.6.1, §1.6.9, §12.5.1 or §20.

| Item | Outcome |
|------|---------|
| Pre-conditions | **PASS** (P1–P6; one hash-string typo in the brief flagged, not a drift — §2) |
| Edits E1–E6 | **Applied** (§3) |
| §1.6.1 reconciliation | N = 53, M = 53, **delta 0**, endpoint `e6b9eef` (§4) |
| §12.5.1 row 4 | `OPEN` → **CLOSED (v1.0.16)**; row 15 added (§5) |
| §22 / §1.6.2–§1.6.8 byte-identity | **IDENTICAL** (§6) |
| Diff scope | 1 file, **+103 / −9**, 10 hunks, all in-scope (§3) |
| No source/test/build/device change | **Confirmed** (§7) |

---

## 2. Pre-conditions (P1–P6)

| # | Check | Expected | Observed | Result |
|---|-------|----------|----------|--------|
| P1 | `git rev-parse HEAD` | `e6b9eef…` | `e6b9eef371a0190156021c8ea51e18e6ec3ee4a3` | **PASS** (with the typo note below) |
| P2 | `git rev-parse origin/main` | == HEAD | `e6b9eef371a0190156021c8ea51e18e6ec3ee4a3` | **PASS** |
| P3 | `git branch --show-current` | `main` | `main` | **PASS** |
| P4 | `git status --short` (tracked) | no ` M` / `M ` | empty | **PASS** |
| P5 | Spec header version | `1.0.15` | `1.0.15` | **PASS** |
| P6 | §1.6.8 read + §12.5.1 row 4 | row 4 still OPEN | `**OPEN — design proposed (TOFU fingerprint pinning), implementation deferred.**` | **PASS** |

### 2.1 Hash note (brief typo, not a drift) — flagged for the record

The brief's STEP 1a states the expected hash as `e6b9eef38b5c86cf29cea870a612a4e4b4da8e7d`. **That object does
not exist in the repository** (`git cat-file -t` → `fatal: could not get object info`). It is the correct
short hash `e6b9eef` concatenated with the tail of `e03f45d38b5c86cf29cea870a612a4e4b4da8e7d` (the previous
phase's report commit). The **real** full hash of the `e6b9eef` commit is
`e6b9eef371a0190156021c8ea51e18e6ec3ee4a3`, and HEAD == `origin/main` == that commit.

This is **not** the "HEAD/origin mismatch" STOP condition: the substantive condition — HEAD equals
`origin/main` and equals the commit the brief names by its short hash — holds, so there is **no drift** since
the brief was written. Per E1 ("set to the actual HEAD at write time"), the header records the **actual**
full hash. Recorded here so the discrepancy is not silently lost.

---

## 3. Summary of edits (E1–E6)

Diffstat: `PROJECT_SPECIFICATION.md | 112 ++++--` → **1 file changed, 103 insertions(+), 9 deletions(−)**,
in **10 hunks**, every one inside an authorized region:

| # | Hunk (`-U0`, new-file range) | Region | Edit |
|---|------------------------------|--------|------|
| 1 | `@@ -7,2 +7,2 @@` | header | **E1** — HEAD → `e6b9eef…3ee4a3`; version 1.0.15 → **1.0.16** (`Last updated` unchanged, 2026-10-15) |
| 2 | `@@ -94,2 +94,2 @@` | §1.6.1 | **E2** — "Forty-seven … `23816cb`" → "Fifty-three … `e6b9eef`" |
| 3 | `@@ -100,4 +100,6 @@` | §1.6.1 | **E2** — growth narrative extended (v1.0.15 spec commit + SEC-01 TOFU work); range-exclusive `46` → **`52`** |
| 4 | `@@ -153,0 +156,6 @@` | §1.6.1 | **E2** — **6** new rows: `df94a81`, `b652198`, `9954c03`, `e03f45d`, `ff78116`, `e6b9eef` |
| 5 | `@@ -191,0 +200,7 @@` | §1.6.1 | **E2** — new "Push state (v1.0.16)" note |
| 6 | `@@ -553,0 +569,76 @@` | §1.6.9 | **E3** — new section, 8 evidence-labelled bullets |
| 7 | `@@ -1873 +1964 @@` | §12.5.1 | **E4** — row 4 status `OPEN` → `CLOSED (v1.0.16)` |
| 8 | `@@ -1883,0 +1975 @@` | §12.5.1 | **E4** — new row 15 |
| 9 | `@@ -2220,0 +2313 @@` | §20 Changelog | **E5** — new `1.0.16` row |
| 10 | `@@ -2260,0 +2354 @@` | §20 Verification Status | **E6** — new §1.6.9 row |

No hunk falls in §1.6.2–§1.6.8 (new lines 208–568) or §22 (new lines 2374–EOF). **E7 honoured: §22 L1–L10 not
touched.**

**Deviation from the brief's E2 list (6 rows, not 5) — deliberate and required.** The brief named "the 5 that
landed since v1.0.15" (`b652198`, `9954c03`, `e03f45d`, `ff78116`, `e6b9eef`). `git log --oneline 23816cb..HEAD`
returns **6** commits: those five **plus `df94a81`** (`docs: update PROJECT_SPECIFICATION to v1.0.15`). The
v1.0.15 push-state note explicitly deferred its own commit to "a future revision" — i.e. **this** one — so
`df94a81` must be listed by hash here. Adding only 5 rows would have left the table at 52 against an
inclusive count of 53 (**delta 1**), violating the brief's own CRITICAL delta-0 requirement. All 6 were added.

**§1.6.9 content** (new, 76 lines): 8 bullets — (1) Design B adopted vs rejected Designs A/C
(SOURCE CODE + PRIOR REPORT); (2) native side — `ssl_get_peer_cert_fingerprint` `aes_wrapper.c:674`,
`X509_digest` `:694`, JNI `nativeGetPeerFingerprint` `softether_jni.c:690`, `SSL_VERIFY_NONE` unchanged at
`aes_wrapper.c:111` (commit `b652198`); (3) Kotlin store + 11 pure-JVM tests (commit `9954c03`);
(4) primary-handshake wiring, `MISSING`-fail-closed rule (commit `9954c03`); (5) **reconnect-path fix** via
`verifyTofuAfterHandshake()` at both sites (commit `ff78116`); (6) **verification status — PARTIAL**
(primary DEVICE-VERIFIED; reconnect **NOT device-reproduced**, SOURCE CODE + 2 source-shape tests, tracked as
row 15); (7) the 2 source-shape tests, library suite 173/0/0/0, app suite 647/0/0/1; (8) honest caveat —
CLOSED with a PARTIAL caveat, not FIXED-with-full-evidence. Plus the "No new methodology lessons in v1.0.16"
line.

**One documentation-accuracy note surfaced during the edit (not a spec change to §1.6.7/§1.6.8).** The
`SSL_VERIFY_NONE` site is now at `aes_wrapper.c:111`; §1.6.7/§1.6.8 cite `:110`. Commit `b652198` added one
line near the top of the file (`@@ -4,6 +4,7 @@`, an include), shifting the line by one. §1.6.9 records the
current line `:111` **and** explains the `:110` citations, so a reader is not misled. The historical sections
themselves were **not** modified (they are in the protected §1.6.2–§1.6.8 range).

---

## 4. §1.6.1 chain reconciliation

Command: `git log --oneline 0714628^..HEAD | wc -l` (the **inclusive** range, which includes `0714628`).

| Quantity | Value |
|----------|-------|
| **N** = `git log --oneline 0714628^..HEAD \| wc -l` | **53** |
| **M** = table rows in §1.6.1 | **53** |
| **delta** = M − N | **0** |
| Range endpoint (last table row) | `e6b9eef` — `docs: report for FIX-SEC01-RECONNECT` |
| Range-exclusive `git log --oneline 0714628..HEAD \| wc -l` | **52** (text updated to match) |

Phase-start state: the table held **47** rows and the text read "47 through `23816cb`"; `git` reported 47
inclusive. The 6 added rows bring both to 53. **Delta 0 as required.**

---

## 5. §12.5.1 row 4 — before / after

**Before (HEAD, line 1873):**

> | 4 | **N-06 / SEC-01** TLS certificate verification | `aes_wrapper.c:110` `ssl_configure_client_ctx` sets
> `SSL_CTX_set_verify(c, SSL_VERIFY_NONE, NULL)`; no trust store, no hostname binding, no pin; SNI is the
> resolved **IP** (`:424–428`). Deliberate for self-signed VPNGate certs, but a genuine MITM exposure. |
> P2_MEDIUM | **OPEN — design proposed (TOFU fingerprint pinning), implementation deferred.** See §1.6.7,
> §1.6.8 (bullet 10) and `AUDIT_SECURITY_AND_RISK_B_v1.0.14.md`. Evidence: SOURCE CODE |

**After (working tree, line 1964) — only the Status cell changed:**

> | … | P2_MEDIUM | **CLOSED (v1.0.16) — TOFU fingerprint pinning landed (commits `b652198`, `9954c03`,
> `ff78116`). Primary handshake DEVICE-VERIFIED (Phase 9/10); reconnect path fixed and covered by SOURCE CODE
> + 2 source-shape tests but NOT device-reproduced. See §1.6.9 for the explicit caveat.** (Supersedes the
> v1.0.14 "OPEN — design proposed" status; see §1.6.7, §1.6.8 bullet 10 and
> `AUDIT_SECURITY_AND_RISK_B_v1.0.14.md`.) Evidence: SOURCE CODE + PHYSICAL DEVICE + TEST |

**New row 15 (line 1975):**

> | 15 | **SEC-01 reconnect-path coverage (partial)** | The reconnect handshake is TOFU-checked —
> `verifyTofuAfterHandshake()` is called from `attemptReconnect()` as well as `performConnectInner()` (commit
> `ff78116`) — but was **not** device-reproduced: reaching it needs a mid-session network interruption, which
> the project's rules forbid inducing. Coverage rests on SOURCE CODE + the 2 source-shape tests in
> `Sec01ReconnectTofuCoverageTest`. Re-capture opportunistically if a reconnect occurs naturally during a
> future device run. See §1.6.9 bullet 6. | P3_LOW (opportunistic) | **OPEN — UNKNOWN (no live trace).** Not a
> known bypass: the check is present at both call sites; only the device-level confirmation is outstanding.
> Evidence: SOURCE CODE + UNKNOWN |

---

## 6. Protected-region byte-identity

Both protected regions were extracted from `HEAD:PROJECT_SPECIFICATION.md` and from the working tree
(CR stripped for comparison) and compared with `cmp`.

| Region | Lines | md5 | Result |
|--------|-------|-----|--------|
| **§22 (L1–L10)** — from `## 22. Build and Verification Methodology` to EOF | 99 | `da36f15a92ef2e15aad8e0d68882c82b` | **IDENTICAL** |
| **§1.6.2–§1.6.8** — from `#### 1.6.2 …` through the last §1.6.8 line (`No new methodology lessons in v1.0.15.`) | 360 | `90e2257840db9e64201197b676a1a585` | **IDENTICAL** |

**§22 untouched (E7 honoured); §1.6.2–§1.6.8 untouched.**

---

## 7. Explicit no-change confirmation

- **No source / test / native / build change.** The only modified tracked file is `PROJECT_SPECIFICATION.md`;
  the only new file is this report. No `.kt`, `.c`, `.h`, `.gradle`, `CMakeLists.txt`, `AndroidManifest.xml`
  or `network_security_config.xml` was touched.
- **No device command.** No `adb`, `install`, `logcat`, `dumpsys` or UI interaction. Device-free by design.
- **No build and no test run.** No `gradlew` invocation.
- **No existing report modified.**
- **No `git add -A` / `git add .`** — the two files are staged by explicit path.
- **No `--force`, no tag, no reset / clean / stash.**
- **C5 not executed.** `D:\PMC\Documents\Android\SoftEthergai` not touched.
- **§22 L1–L10 untouched**; **§1.6.2–§1.6.8 untouched** (both proven byte-identical, §6).

---

## 8. Recommendation

1. **SEC-01 is now recorded CLOSED with a PARTIAL caveat** — consistent with the project's rule that
   UNKNOWN / NOT REPRODUCED is stated explicitly. No further docs work is required for it unless a live
   reconnect trace is captured (§12.5.1 row 15).
2. **Opportunistic only:** if a future device run happens to trigger `attemptReconnect()` naturally, the
   `[TOFU] match` line from that path would let row 15 be closed and the §1.6.9 caveat lifted. This should
   **not** be induced (that would require a forbidden network interruption).
3. **Minor doc drift for a future revision (not fixed here — outside this phase's authorized scope):** the
   `:110` citations for `SSL_VERIFY_NONE` in §1.6.7/§1.6.8 are now stale (`:111` after `b652198`). §1.6.9
   already explains it; a future docs revision could align the historical citations if the project wants them
   line-accurate.
4. This phase's own commit (`docs: update PROJECT_SPECIFICATION to v1.0.16 …`) is to be recorded by hash in
   the next docs revision, per the §1.6.1 convention.

---

## Appendix A — Full diff of `PROJECT_SPECIFICATION.md`

```diff
diff --git a/PROJECT_SPECIFICATION.md b/PROJECT_SPECIFICATION.md
index 39d9e3b..72e2a9f 100644
--- a/PROJECT_SPECIFICATION.md
+++ b/PROJECT_SPECIFICATION.md
@@ -4,8 +4,8 @@
 **Repository:** `D:\PMC\Documents\Android\SoftEthergai`
 > **Canonical repository name:** `SoftEthergai` (see §2.6 for local mirrors)
 **Branch:** `main`
-**HEAD:** `23816cb85c7fc68fcadfccbbd01fb9e38ca2bb2a`
-**Document version:** 1.0.15
+**HEAD:** `e6b9eef371a0190156021c8ea51e18e6ec3ee4a3`
+**Document version:** 1.0.16
 **Last updated:** 2026-10-15
 
 > **Document status:** This is a living specification created from the read-only audit completed at lifecycle stage F.28AD-B. It captures the verified repository state, the architecture that was audited, the F.28-family defect history, the current native-lifetime and teardown-completion design, and the open requirements (including F.28AA, which is completed). All source files were inspected at HEAD; no code or configuration was modified in producing this document.
@@ -91,16 +91,18 @@ that stack on the physical device; and then pushed the whole local chain to the
 
 #### 1.6.1 Commit chain
 
-Forty-seven commits constitute the chain now on `origin/main`, forming the contiguous
-range `0714628` → `23816cb` (oldest first). (The §1.6 preamble's "17 local commits" records the
+Fifty-three commits constitute the chain now on `origin/main`, forming the contiguous
+range `0714628` → `e6b9eef` (oldest first). (The §1.6 preamble's "17 local commits" records the
 v1.0.8-era push snapshot; the chain has since grown by the v1.0.8/v1.0.9 docs commits, the
 v1.0.10 AutoConnect bundle, the v1.0.10/v1.0.11 spec-update commits, the v1.0.11 native-hardening
 bundle, the R1–R6 / R4a / R4b source work, the R4b report, the FIX-TEST-FLAKINESS-AND-DOCS-v1.0.12
 test fixes and spec update, the FIX-HANGING-SKIP-TEST report commit, the v1.0.13 docs-hygiene
-commit, the v1.0.14 security audit + spec commits, and the FIX-NATIVE-SECURITY-SMALL native/security
-fixes and report commit — see the appended rows below.) The count is the size of the **inclusive**
-range `0714628` → `23816cb`; the range-exclusive form `git log --oneline 0714628..HEAD | wc -l`
-reports **46**, because it omits the range's own first commit, `0714628`.
+commit, the v1.0.14 security audit + spec commits, the FIX-NATIVE-SECURITY-SMALL native/security
+fixes and report commit, the v1.0.15 spec commit, and the SEC-01 TOFU-pinning implementation
+(native peer-fingerprint exposure, the Kotlin store, and the reconnect-path fix) plus its two phase
+reports — see the appended rows below.) The count is the size of the **inclusive** range
+`0714628` → `e6b9eef`; the range-exclusive form `git log --oneline 0714628..HEAD | wc -l` reports
+**52**, because it omits the range's own first commit, `0714628`.
 
 | Commit | Subject |
 |--------|---------|
@@ -151,6 +153,12 @@ reports **46**, because it omits the range's own first commit, `0714628`.
 | `f66f464` | fix(native): use RAND_bytes for RUDP keys/IVs/cookies (N-09); strict Content-Length parse (N-11) |
 | `1bbf8a6` | fix(security): remove dead SoftEtherClient.disconnect(); scope cleartext to explicit hosts (RISK-B, SEC-02) |
 | `23816cb` | docs: report for FIX-NATIVE-SECURITY-SMALL |
+| `df94a81` | docs: update PROJECT_SPECIFICATION to v1.0.15 (record N-09, N-11, RISK-B, SEC-02 as fixed + device-verified) |
+| `b652198` | feat(native): expose peer certificate fingerprint (SEC-01 part 1) |
+| `9954c03` | feat(security): TOFU fingerprint pinning at connect time (SEC-01 part 2) |
+| `e03f45d` | docs: report for FIX-SEC01-TOFU-PINNING |
+| `ff78116` | fix(security): run TOFU fingerprint check on the reconnect path (SEC-01 follow-up) |
+| `e6b9eef` | docs: report for FIX-SEC01-RECONNECT |
 
 > **Correction (v1.0.8) — the `0714628` off-by-one.** Earlier handoffs (the v1.0.7 text and the push
 > handoff) described `0714628` as the "base of the chain". That was an off-by-one: `0714628^` is
@@ -189,6 +197,13 @@ reports **46**, because it omits the range's own first commit, `0714628`.
 > the three FIX-NATIVE-SECURITY-SMALL commits (`f66f464`, `1bbf8a6`, `23816cb`) are now listed by hash
 > in the table, which is why the chain reads **47** through `23816cb` (46 range-exclusive). This
 > revision's own commit will be recorded by hash in a future revision, per the same convention.
+>
+> **Push state (v1.0.16).** The commit promised in the v1.0.15 note above (`df94a81`) is now listed by
+> hash in the table (row 48), which is why the chain reads **48** through `df94a81`. The five SEC-01
+> TOFU-pinning commits follow — `b652198`, `9954c03`, `e03f45d`, `ff78116`, `e6b9eef` — bringing the
+> chain to **53** through `e6b9eef` (52 range-exclusive). This revision's own commit will be recorded by
+> hash in a future revision, per the same convention. (The v1.0.15 note's "47 through `23816cb`" was
+> correct for that revision; it listed 47 rows and had not yet listed its own commit, `df94a81`.)
 
 #### 1.6.2 Session outcome in one paragraph
 
@@ -551,6 +566,82 @@ label.
 
 - **Methodology lessons:** No new methodology lessons in v1.0.15.
 
+#### 1.6.9 SEC-01 TOFU Fingerprint Pinning (v1.0.16)
+
+This subsection records the **FIX-SEC01-TOFU-PINNING-IMPL** implementation phase (commits `b652198`,
+`9954c03`, `e03f45d`) and its **FIX-SEC01-RECONNECT** follow-up (commits `ff78116`, `e6b9eef`), which
+together close **SEC-01** (== §12.5.1 row 4 / N-06) — with an explicit **PARTIAL** caveat on the
+reconnect-path verification (bullet 6). No source, test, native, build, manifest or XML file was modified
+in *this* documentation revision. Each bullet carries its evidence label.
+
+1. **Design adopted — Design B (TOFU certificate-fingerprint pinning).** Chosen from the three designs
+   compared in `AUDIT_SECURITY_AND_RISK_B_v1.0.14.md` §2. **Design A** (a CA bundle / trust store) is
+   incompatible with VPN Gate relays, which are self-signed and addressed by IP — there is no usable CA
+   chain and no DNS name to bind; **Design C** (public-key pinning with a user-visible fingerprint prompt)
+   was rejected for UX complexity. Design B pins the leaf certificate's SHA-256 per endpoint on first use
+   and rejects a later mismatch: fail-closed, no user interaction, no trust store.
+   Evidence: SOURCE CODE + PRIOR REPORT.
+
+2. **Native side landed.** `aes_wrapper.c` adds `ssl_get_peer_cert_fingerprint(ssl_context_t*, uint8_t*,
+   size_t)` (`:674`), which takes the peer certificate with `SSL_get_peer_certificate` and computes
+   `X509_digest(cert, EVP_sha256(), out, &len)` (`:694`), returning 0 on success with a 32-byte digest.
+   `softether_jni.c` exposes it as the JNI entry `nativeGetPeerFingerprint` (`:690`). The native verify
+   policy is **unchanged**: `SSL_CTX_set_verify(c, SSL_VERIFY_NONE, NULL)` still stands
+   (`aes_wrapper.c:111`; the `:110` citations in §1.6.7/§1.6.8 predate commit `b652198`, whose added
+   include shifted the line by one). Per Design B the rejection is a Kotlin-side decision made *after*
+   the handshake. Commit `b652198`. Evidence: SOURCE CODE.
+
+3. **Kotlin side landed.** `security/ServerFingerprintStore.kt` holds the pure TOFU rule
+   (`VerifyResult { MATCH, FIRST_USE, MISMATCH, MISSING }`, `TofuDecision { PROCEED, BLOCK }`,
+   `ServerKey(hostname, ip, port)` with a canonical `host|ip|port` key), and `ServerFingerprintStoreAndroid`
+   persists one lowercase-hex SHA-256 per endpoint in the private `tls_server_fingerprints`
+   SharedPreferences (plain, not encrypted — a certificate fingerprint is public data). The pure rule is
+   covered by **11** pure-JVM tests. Commit `9954c03`. Evidence: SOURCE CODE + TEST.
+
+4. **Wiring at the primary handshake.** `ConnectionController.performConnectInner()` runs the TOFU check
+   immediately after a successful `nativeConnectWithHub`: `FIRST_USE` → store the pin and proceed;
+   `MATCH` → proceed; `MISMATCH` → fail-closed through the existing error channel (`currentState = ERROR`,
+   `isCancelled`, `onError("TLS fingerprint mismatch")`). `MISSING` is treated as first-use only when no
+   pin exists — a *pinned* endpoint that presents no fingerprint returns `MISMATCH` (fail-closed).
+   Commit `9954c03`. Evidence: SOURCE CODE.
+
+5. **RECONNECT-PATH FIX (v1.0.16).** The Phase-9 wiring above covered only the primary handshake. A
+   follow-up audit (FIX-SEC01-RECONNECT) found that `ConnectionController.attemptReconnect()` completes a
+   **new** TLS handshake — it claims/destroys the previous handle, runs a fresh `nativeCreate()`, then a
+   fresh `nativeConnectWithHub()` — so the reconnect path was **unchecked**: a latent Design-B bypass in
+   which a relay substituted after first use would be accepted on every reconnect. Fixed by extracting
+   `private fun verifyTofuAfterHandshake(handle: Long)` and calling it from **both** handshake-completing
+   paths; on `MISMATCH` the reconnect path sets `ERROR`, tears down through the existing single-owner
+   `claimAndDestroyNativeHandle()`, reports `onError("TLS fingerprint mismatch")` and returns (deliberately
+   not `throw`, to avoid the catch block's duplicate error and double teardown). Commit `ff78116`.
+   Evidence: SOURCE CODE.
+
+6. **Verification status of SEC-01 — PARTIAL (recorded explicitly).**
+   - *Primary handshake:* **DEVICE-VERIFIED** (Phase 9 + Phase 10, device `5585fecc`). Three AutoConnect
+     cycles produced `[TOFU] match` × 3 and **0 mismatch**; the pin store persisted through
+     install/upgrade (md5 unchanged); the crash buffer was empty.
+   - *Reconnect handshake:* **NOT device-reproduced.** Reaching `attemptReconnect()` requires a mid-session
+     network interruption (send / TUN / receive error, or a keepalive timeout), which this project's rules
+     forbid inducing. Its coverage is therefore proven by **SOURCE CODE** (the shared helper is invoked at
+     both call sites) plus **2 source-shape tests** (bullet 7) — **not** by a device trace. Tracked
+     opportunistically as **§12.5.1 row 15**.
+   - Evidence: PHYSICAL DEVICE (primary handshake) + SOURCE CODE (reconnect) + **UNKNOWN** (reconnect not
+     observed live).
+
+7. **Source-shape regression tests.** `Sec01ReconnectTofuCoverageTest` adds **2** pure-JVM tests that read
+   `ConnectionController.kt` from the module test CWD and assert that the TOFU check is present in
+   `attemptReconnect` and invoked from exactly the two handshake-completing paths — the same technique
+   `SoftEtherClientHandleGuardTest` uses, because `ConnectionController` cannot be instantiated in a JVM
+   test. The library suite is **173/0/0/0** (was 171); the app suite is unchanged at **647/0/0/1**.
+   Commit `ff78116`. Evidence: TEST.
+
+8. **Honest caveat.** SEC-01 is **not** marked FIXED-with-full-evidence. It is recorded as **CLOSED
+   (v1.0.16)** with an explicit **PARTIAL** caveat, consistent with this document's rule (§20 legend) that
+   UNKNOWN / NOT TESTABLE / NOT REPRODUCED must be recorded explicitly rather than fabricated.
+   Evidence: SOURCE CODE + INFERENCE.
+
+- **Methodology lessons:** No new methodology lessons in v1.0.16.
+
 ---
 
 ## 2. Architecture Overview
@@ -1870,7 +1961,7 @@ The following items are planned for the source-and-path architecture described i
 | 1 | **FIX-NATIVE-LIFETIME-SYNC (N-15 + N-16)** | N-15: `rudp_destroy` (`rudp_destroy:181`) destroys `ctx->lock` and frees `ctx` with no guarantee a concurrent `rudp_poll` (`rudp_poll:693`) has returned — a poll in flight during destroy is a use-after-free. N-16: `nativeConnect` (`softether_jni.c:54`) is **not** serialised by `conn->connect_mutex` while `nativeConnectWithHub` (`softether_jni.c:139–145`) is — two concurrent native connects on the same `conn` can interleave. | **P1_HIGH** | **CLOSED for N-15 + N-16 — device-verified** (tests 37–41 PASS; commits `3a2f442`, `b0418cc`). See §1.6.1 for the commits. The **FINDING T5-SLOT** driver (§12.4) remains OPEN as a separate item (row 8) |
 | 2 | **DEEP-REVIEW P0 set** | JNI bounds (N-01…N-05, N-08), RUDP lock release, reconnect terminal routing (G-1), teardown log identity (G-2) | — | **CLOSED — device-verified.** RESUME-BATCH-DEVICE-VERIFY: 6/6 native tests PASS, T2–T8 clean. Evidence: PHYSICAL DEVICE EVIDENCE |
 | 3 | `proRelease` build/test path | The release variant has never been built or tested locally (only `proDebug`). Blocked by the RISK-A signing fallback (§12.4) and the absence of local release signing material. | Priority to be assessed | OPEN — see RISK-A |
-| 4 | **N-06 / SEC-01** TLS certificate verification | `aes_wrapper.c:110` `ssl_configure_client_ctx` sets `SSL_CTX_set_verify(c, SSL_VERIFY_NONE, NULL)`; no trust store, no hostname binding, no pin; SNI is the resolved **IP** (`:424–428`). Deliberate for self-signed VPNGate certs, but a genuine MITM exposure. | P2_MEDIUM | **OPEN — design proposed (TOFU fingerprint pinning), implementation deferred.** See §1.6.7, §1.6.8 (bullet 10) and `AUDIT_SECURITY_AND_RISK_B_v1.0.14.md`. Evidence: SOURCE CODE |
+| 4 | **N-06 / SEC-01** TLS certificate verification | `aes_wrapper.c:110` `ssl_configure_client_ctx` sets `SSL_CTX_set_verify(c, SSL_VERIFY_NONE, NULL)`; no trust store, no hostname binding, no pin; SNI is the resolved **IP** (`:424–428`). Deliberate for self-signed VPNGate certs, but a genuine MITM exposure. | P2_MEDIUM | **CLOSED (v1.0.16) — TOFU fingerprint pinning landed (commits `b652198`, `9954c03`, `ff78116`). Primary handshake DEVICE-VERIFIED (Phase 9/10); reconnect path fixed and covered by SOURCE CODE + 2 source-shape tests but NOT device-reproduced. See §1.6.9 for the explicit caveat.** (Supersedes the v1.0.14 "OPEN — design proposed" status; see §1.6.7, §1.6.8 bullet 10 and `AUDIT_SECURITY_AND_RISK_B_v1.0.14.md`.) Evidence: SOURCE CODE + PHYSICAL DEVICE + TEST |
 | 5 | **N-09** non-cryptographic `rand()` | `softether_rudp.c:251–268` derives RC4 keys / `next_iv` / cookies from `rand()` (also `:838`, `:1018`, `:1048` for jitter/pad); `rudp_transport.c:316,370,402,731,760,777,840,850,1074,1151,1152,1183,1225` derive IV positioning / pad / ICMP-DNS ids / jitter, with `srand()` at `:1026`. The generator is not cryptographically secure. (The pre-v1.0.14 line refs were stale and are corrected here.) | P2_MEDIUM | **FIXED (v1.0.15) — device-non-observable; see §1.6.8 bullet 1. Commit `f66f464`.** Evidence: SOURCE CODE + TEST |
 | 6 | **RISK-A** conditional release signing | See §12.4. | Priority to be assessed | OPEN — `CONFIRMED BY SOURCE AND OBSERVED IN PRACTICE` |
 | 7 | **RISK-B** legacy `SoftEtherClient.disconnect()` bypass | See §12.4. | — | **CLOSED (v1.0.15)** — dead code deleted (v1.0.14 call-graph audit: unreachable body — `isConnected` never set true, `nativeHandle` never assigned non-zero — plus zero production/test callers and no reflection); see §1.6.8 bullet 3. Commit `1bbf8a6`. Evidence: SOURCE CODE + TEST |
@@ -1881,6 +1972,7 @@ The following items are planned for the source-and-path architecture described i
 | 12 | **OPEN-2 — `AutoModeControllerTest.awaitTerminal()` unbounded wait** | The helper looped `while (true)` until a terminal state with `!isRunning`, with no bound. A controller that never settled stalled the whole `:app:testProDebugUnitTest` suite rather than failing one test — the same failure mode already fixed in `awaitNextTerminalAfterChange` (commit `3c656a4`). The success predicate was correct; only the wait was unbounded. | P2_MEDIUM | **CLOSED (v1.0.12)** — bounded with `withTimeoutOrNull(10_000)` and a 25 ms poll; on timeout it fails loudly, naming the last state, `isRunning`, and the last 12 adapter log lines. Predicate unchanged; all call sites unchanged (timeout defaulted). See §1.6.6. Evidence: SOURCE CODE + TEST |
 | 13 | **SEC-02 / APP-1** cleartext HTTP permitted globally | `AndroidManifest.xml:28` `usesCleartextTraffic="true"` plus the blanket `network_security_config.xml:3` (`<base-config cleartextTrafficPermitted="true"/>`); HTTP fallback/bootstrap URLs in `VpnGateSources.kt`. HTTPS-first ordering and `ResilientDns` are existing mitigations, but the policy permits cleartext to any host. | P2_MEDIUM | **FIXED (v1.0.15) — device-verified active enforcement; see §1.6.8 bullets 4–5. Commit `1bbf8a6`.** Evidence: SOURCE CODE + PHYSICAL DEVICE |
 | 14 | **N-11** `atoi()` parses `Content-Length` | `softether_protocol.c:818, 4075, 4202` cast `atoi()` to `uint32_t`; a negative/malformed value becomes `0xFFFFFFFF`, but every site is bounded downstream (`:838–839`, `:4078`, `:4207`), so this is robustness/defence-in-depth rather than a demonstrated exploit. | P3_LOW | **FIXED (v1.0.15) — see §1.6.8 bullet 2. Commit `f66f464`.** Evidence: SOURCE CODE + TEST |
+| 15 | **SEC-01 reconnect-path coverage (partial)** | The reconnect handshake is TOFU-checked — `verifyTofuAfterHandshake()` is called from `attemptReconnect()` as well as `performConnectInner()` (commit `ff78116`) — but was **not** device-reproduced: reaching it needs a mid-session network interruption, which the project's rules forbid inducing. Coverage rests on SOURCE CODE + the 2 source-shape tests in `Sec01ReconnectTofuCoverageTest`. Re-capture opportunistically if a reconnect occurs naturally during a future device run. See §1.6.9 bullet 6. | P3_LOW (opportunistic) | **OPEN — UNKNOWN (no live trace).** Not a known bypass: the check is present at both call sites; only the device-level confirmation is outstanding. Evidence: SOURCE CODE + UNKNOWN |
 
 #### 12.5.2 Dynamic Server-Source Access roadmap
 
@@ -2218,6 +2310,7 @@ This project has **no submodules**. The `.gitmodules` file does not exist. (The
 | 1.0.13 | 2026-10-15 | DOCS-HYGIENE | header path clarified — the canonical `**Repository:**` line is **retained unchanged** and a new **§2.6 Local Mirrors / Working Copies** documents the D: → C: move, its cause (D: I/O slowness: `gradlew help` ~22 min vs ~7 s on a healthy SSD) and the rule that filesystem paths are machine-specific while the repository identity (`SoftEthergai`) is not; a one-line canonical-name note was added under the header. Committed the previously-untracked `FIX_HANGING_SKIP_TEST_REPORT.md` **unchanged** (commit `b38efa7`). Verified **§1.6.1** chain sanity: `git log --oneline 0714628^..HEAD` = **41** and the table = **41 rows** (delta 0) after adding the four missing rows (`6360228`, `203addf`, `81163e4`, `b38efa7`); the phase-start delta was **3**. Refreshed the header HEAD field to `b38efa7` and `Last updated` to 2026-10-15. **No source / test / native / build / device change.** §22 untouched. No new methodology lessons in v1.0.13 |
 | 1.0.14 | 2026-10-15 | AUDIT-DESIGN-SECURITY-AND-RISK-B (read-only + design) | records the security audit and the RISK-B closure in a new **§1.6.7**. **SEC-01** (== §12.5.1 row 4 / N-06): the single production verification site `aes_wrapper.c:110` sets `SSL_VERIFY_NONE`, with no trust store, no hostname binding and no pin, and SNI set to the resolved IP; three candidate designs were compared and **TOFU fingerprint pinning** is recommended, implementation deferred. **SEC-02 / APP-1**: global `usesCleartextTraffic` + blanket `network_security_config.xml`; migration design (scope cleartext to named hosts), deferred. **N-09**: `rand()` feeds RC4 keys/IV/cookies in `softether_rudp.c:251–268` and 13 `rudp_transport.c` sites (with `srand()` at `:1026`); migration onto the existing `generate_random_bytes()` / `RAND_bytes` wrapper designed, deferred; §12.5.1 row 5's stale line refs corrected. **N-11**: `atoi()` parses `Content-Length` at `softether_protocol.c:818,4075,4202` (bounded downstream); strict-parse design, deferred. **RISK-B CLOSED** — an exhaustive call-graph audit proves the legacy `SoftEtherClient.disconnect()` is dead code (unreachable body + zero production/test callers + no reflection); deletion recommended, deferred. Extended **§1.6.1** to the 42-commit chain (`0714628` → `6cffd1e`, adding the v1.0.13 spec commit) and added **§12.5.1 rows 13/14**. New artifact `AUDIT_SECURITY_AND_RISK_B_v1.0.14.md`. Refreshed the header HEAD field to `6cffd1e`. No new methodology lessons in v1.0.14. **This revision made no source / test / native / build / manifest / XML change and ran no build, test, or device command** |
 | 1.0.15 | 2026-10-15 | DOCS-UPDATE: recorded the four implemented + device-verified security fixes (N-09, N-11, RISK-B, SEC-02) in §1.6.8; updated §12.5.1 rows 4/5/7/13/14 to reflect their fixed/closed status; advanced the §1.6.1 chain through `23816cb`. SEC-01 remains OPEN (design deferred). No source/test/build/device change. |
+| 1.0.16 | 2026-10-15 | DOCS-UPDATE: recorded SEC-01 TOFU fingerprint pinning in a new **§1.6.9** — Design B landed (`b652198` native peer-fingerprint exposure, `9954c03` Kotlin store + primary-handshake wiring + 11 pure-JVM tests), primary handshake **device-verified** (Phase 9/10 on `5585fecc`: `[TOFU] match` × 3, 0 mismatch, pin store persisted through install/upgrade), and the **reconnect path fixed** (`ff78116`) so `verifyTofuAfterHandshake()` runs at both handshake-completing sites — with an explicit **PARTIAL** caveat (reconnect not device-reproduced; covered by SOURCE CODE + 2 source-shape tests; library suite **173/0/0/0**, app suite **647/0/0/1**). Updated **§12.5.1 row 4** to **CLOSED (v1.0.16)** and added **row 15** for the opportunistic reconnect re-capture. Advanced **§1.6.1** to **53** commits through `e6b9eef` (adding the previously deferred `df94a81` plus the five SEC-01 commits; inclusive-count vs table-row delta 0). Refreshed the header HEAD field to `e6b9eef`. **No source / test / native / build / device change**; §22 untouched. No new methodology lessons in v1.0.16. |
 
 ### Verification Status
 
@@ -2258,6 +2351,7 @@ This project has **no submodules**. The `.gitmodules` file does not exist. (The
 | Test Flakiness and Docs Bundle (§1.6.6) | HISTORICAL + TEST — the OPEN-1 / OPEN-2 root causes are attributed to direct source inspection of `VpnServerRepository.refresh` (§8.1) and `AutoModeControllerTest`; the fixes and the regression test are TEST evidence, as are the three green JVM runs (`:SoftEtherClient:test` 160/0/0/0; `:app:testProDebugUnitTest` 647/0/0/1 twice). The "no APK / no device" statement is verified from the phase's own command log. Not independently re-verified here beyond the recorded runs |
 | Security Audit and RISK-B Closure (§1.6.7) | HISTORICAL + SOURCE CODE — the SEC-01 / SEC-02 / N-09 / N-11 characterisations are direct source inspection at the stated file/line; the RISK-B closure is SOURCE CODE (exhaustive call-graph) plus TEST (no test pins the method). All designs are DEFERRED and no fix was applied; the findings are not independently re-verified here |
 | Security Fixes Landed + Device-Verified (§1.6.8) | HISTORICAL — the four fixes are attributed to the FIX-NATIVE-SECURITY-SMALL implementation phase (commits `f66f464`, `1bbf8a6`; SOURCE CODE + TEST) and the device statements to DEVICE-VERIFY-SECURITY-SMALL (PHYSICAL DEVICE), not re-verified in this revision. SEC-01 remains OPEN — design deferred |
+| SEC-01 TOFU Fingerprint Pinning (§1.6.9) | HISTORICAL + PARTIAL — attributed to the FIX-SEC01-TOFU-PINNING-IMPL phase (commits `b652198`, `9954c03`; SOURCE CODE + TEST) and its FIX-SEC01-RECONNECT follow-up (`ff78116`; SOURCE CODE + TEST); the **primary-handshake** device statements are PHYSICAL DEVICE evidence from Phase 9 (FIX-SEC01-TOFU-PINNING) and Phase 10 (VERIFY-AND-FIX-SEC01-RECONNECT), not re-verified in this revision. The **reconnect** handshake is recorded explicitly as **UNKNOWN — not device-reproduced** (coverage is SOURCE CODE + 2 source-shape tests only). This revision ran no build, test, or device command |
 
 ### Legend
 
```

## Appendix B — Diff hunk list (`git diff -U0`)

```
@@ -7,2 +7,2 @@                      header
@@ -94,2 +94,2 @@                    §1.6.1
@@ -100,4 +100,6 @@                  §1.6.1
@@ -153,0 +156,6 @@                  §1.6.1
@@ -191,0 +200,7 @@                  §1.6.1
@@ -553,0 +569,76 @@                  §1.6.9 (new)
@@ -1873 +1964 @@                    §12.5.1 (row 4)
@@ -1883,0 +1975 @@                  §12.5.1 (row 15)
@@ -2220,0 +2313 @@                  §20 Changelog (1.0.16 row)
@@ -2260,0 +2354 @@                  §20 Verification Status (§1.6.9 row)
```
