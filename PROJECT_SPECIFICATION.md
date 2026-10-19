# PROJECT_SPECIFICATION — SoftEther VPN Client for Android

**Project:** SoftEthergai — Native SoftEther VPN Client for Android
**Repository:** `D:\PMC\Documents\Android\SoftEthergai`
> **Canonical repository name:** `SoftEthergai` (see §2.6 for local mirrors)
**Branch:** `main`
**HEAD:** `86ade9f3a2a260eb30054a70897d8f894680cf35`
**Document version:** 1.0.37
**Last updated:** 2026-10-19

> **Document status:** This is a living specification created from the read-only audit completed at lifecycle stage F.28AD-B. It captures the verified repository state, the architecture that was audited, the F.28-family defect history, the current native-lifetime and teardown-completion design, and the open requirements (including F.28AA, which is completed). All source files were inspected at HEAD; no code or configuration was modified in producing this document.

> **Documentation map (added v1.0.29):** this file is the canonical **requirements and project-status** document (baseline, constraints, implementation status, verification evidence, roadmap, risks). [`README.md`](README.md) is the public overview (features, build, license). [`SERVER_SOURCE_ARCHITECTURE.md`](SERVER_SOURCE_ARCHITECTURE.md) is the canonical **design-only** document for server sources/paths (retrieval-path design, emergency CSV schema §10, manual import, cumulative retention §9). Ownership does not overlap: requirements/status live here; source/path design lives in the architecture document; user-facing summaries live in the README.

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

Only one flavor exists: `pro` (`flavorDimensions = ["tier"]`, `app/build.gradle`). There is **no** `free` flavor — the tasks `assembleFreeDebug` and `bundleFreeRelease` do not exist (SOURCE VERIFIED at HEAD `df7b49c`; corrected in v1.0.29 — this table previously listed Free variants).

| Variant | Description |
|---------|-------------|
| `CI=true ./gradlew assembleProDebug` | Pro flavor, debug build. Debug variants are disabled on machines without the `CI` env var (`app/build.gradle`, `androidComponents.beforeVariants`), so local builds must set `CI=true`. |
| `./gradlew bundleProRelease` | Pro flavor, release AAB. Requires the release signing configuration (see below); otherwise the build **fails closed**. |

The release keystore (`keyStore/release.jks`) is blackbox-encrypted and only deployed on CI. Passwords resolve from the `SIGN_KEY_PASSWORD` environment variable (CI) or `signKeyPassword` in `local.properties` (developer machines, never committed). When the keystore or password is absent, the **debug-keystore fallback applies to debug variants only**; release assembly/bundle/package tasks are blocked by a fail-closed task-graph guard (`app/build.gradle`) that throws `GradleException` naming the missing pieces. (Corrected in v1.0.29 — this paragraph previously claimed all builds fall back to the debug keystore, which was true before the fail-closed guard landed in v1.0.16, §12.5.1 row 6.)

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

Fifty-three commits constitute the chain now on `origin/main`, forming the contiguous
range `0714628` → `e6b9eef` (oldest first). (The §1.6 preamble's "17 local commits" records the
v1.0.8-era push snapshot; the chain has since grown by the v1.0.8/v1.0.9 docs commits, the
v1.0.10 AutoConnect bundle, the v1.0.10/v1.0.11 spec-update commits, the v1.0.11 native-hardening
bundle, the R1–R6 / R4a / R4b source work, the R4b report, the FIX-TEST-FLAKINESS-AND-DOCS-v1.0.12
test fixes and spec update, the FIX-HANGING-SKIP-TEST report commit, the v1.0.13 docs-hygiene
commit, the v1.0.14 security audit + spec commits, the FIX-NATIVE-SECURITY-SMALL native/security
fixes and report commit, the v1.0.15 spec commit, and the SEC-01 TOFU-pinning implementation
(native peer-fingerprint exposure, the Kotlin store, and the reconnect-path fix) plus its two phase
reports — see the appended rows below.) The count is the size of the **inclusive** range
`0714628` → `e6b9eef`; the range-exclusive form `git log --oneline 0714628..HEAD | wc -l` reports
**52**, because it omits the range's own first commit, `0714628`.

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
| `d61d3f8` | docs: update PROJECT_SPECIFICATION to v1.0.8 (post-v1.0.7 additions) |
| `fa8a53d` | docs: update PROJECT_SPECIFICATION to v1.0.9 (close N-15/N-16; add T5-SLOT driver row) |
| `eb21e56` | fix(native): bound the TLS handshake socket (P1) + bounded race join (P5b) |
| `4df7e79` | fix(android): bound stuck native slots and add Auto Mode recovery (T5-SLOT) |
| `a87c523` | test(androidTest): repair stale T3 assertion against describe() interpolation |
| `5ee4e65` | docs: add AutoConnect audit (root-cause: unbounded TLS handshake socket) |
| `5773c60` | docs: update PROJECT_SPECIFICATION to v1.0.10 (AutoConnect root-cause resolution) |
| `fd4fcf9` | fix(native/jni): guard nativeSend array, snapshot NAT-T fd under connect_mutex, validate option ranges |
| `c8acb5d` | fix(native/rudp): refcount re-check after CAS (N-01), ICMP port-0 (N-04), fd-leak on nonblocking fail (N-10), NAT-T backoff exponent (N-05) |
| `1a4818a` | fix(native/race): heap race control, shared winner, absolute deadline model, defensive budget guard (AC-01..AC-04, N-03, D-1) |
| `6119a2a` | docs: device-verify hardening bundle on 5585fecc (all goals VERIFIED) |
| `68758ae` | docs: update PROJECT_SPECIFICATION to v1.0.11 (native hardening bundle + axes audit) |
| `0e011e3` | docs: add Axes A/B/C audit and v1.0.11 docs-update reports |
| `0e3f9ca` | fix(android): capture re-entrant teardown caller + split terminal message by outcome (R1, R2) |
| `607c651` | feat(android): add RX/EIO counters + classify TUN EIO + drop dead MAX_PACKET_SIZE (R4a, R5, R6) |
| `2841dc5` | docs: report for FIX-AND-VERIFY-AUDIT-TOP-PRIORITIES |
| `7b4adba` | docs(audit): append §10 addendum correcting §2.3 mechanism (R1 evidence) |
| `a9e16fd` | feat(collector): per-request Network.getSocketFactory binding + RX wiring (R4b) |
| `3c656a4` | test(automode): bound awaitNextTerminalAfterChange to prevent hang |
| `3dfb0f3` | docs: report for FIX-AND-VERIFY-R4B (R4b + skip-test fix) |
| `6360228` | test: synchronize collector-log collection and bound awaitTerminal |
| `203addf` | docs: update PROJECT_SPECIFICATION to v1.0.12 (R1-R6 + R4b + test fixes) |
| `81163e4` | docs: report for FIX-TEST-FLAKINESS-AND-DOCS-v1.0.12 |
| `b38efa7` | docs: commit FIX-HANGING-SKIP-TEST report (was left untracked) |
| `6cffd1e` | docs: update PROJECT_SPECIFICATION to v1.0.13 (canonical repo path + §1.6.1 sanity) |
| `65b25f8` | docs(audit): security (SEC-01/SEC-02/N-09/N-11) + RISK-B design |
| `a60681f` | docs: update PROJECT_SPECIFICATION to v1.0.14 (security design) |
| `f66f464` | fix(native): use RAND_bytes for RUDP keys/IVs/cookies (N-09); strict Content-Length parse (N-11) |
| `1bbf8a6` | fix(security): remove dead SoftEtherClient.disconnect(); scope cleartext to explicit hosts (RISK-B, SEC-02) |
| `23816cb` | docs: report for FIX-NATIVE-SECURITY-SMALL |
| `df94a81` | docs: update PROJECT_SPECIFICATION to v1.0.15 (record N-09, N-11, RISK-B, SEC-02 as fixed + device-verified) |
| `b652198` | feat(native): expose peer certificate fingerprint (SEC-01 part 1) |
| `9954c03` | feat(security): TOFU fingerprint pinning at connect time (SEC-01 part 2) |
| `e03f45d` | docs: report for FIX-SEC01-TOFU-PINNING |
| `ff78116` | fix(security): run TOFU fingerprint check on the reconnect path (SEC-01 follow-up) |
| `e6b9eef` | docs: report for FIX-SEC01-RECONNECT |
| `8edaee8` | docs: update PROJECT_SPECIFICATION to v1.0.16 (SEC-01 TOFU pinning — partial-verification caveat) |
| `71edd67` | docs: clarify the §1.6.1 chain-count convention in the v1.0.16 report |
| `067d4a3` | fix(automode): reduce tunnel/UI state-owner mismatches (Axis A) |
| `a7c449f` | docs: PART A commit report with ANR attribution (unattributed, not reproduced) |
| `a3a6434` | docs: update PROJECT_SPECIFICATION to v1.0.17 (Axis A + already-landed B/C/D + ANR finding) |
| `94e02b7` | docs: report for COMMIT-PART-A-AND-DOCS-v1.0.17 (spec 1.0.17 update + tag decision) |
| `0c5754e` | docs: commit Phase 12 IMPLEMENT_P1_REMAINING report (was untracked) |

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
>
> **Push state (v1.0.12).** This revision (FIX-TEST-FLAKINESS-AND-DOCS-v1.0.12) contributes three
> further commits — `test: synchronize collector-log collection and bound awaitTerminal`,
> `docs: update PROJECT_SPECIFICATION to v1.0.12 (R1-R6 + R4b + test fixes)`, and
> `docs: report for FIX-TEST-FLAKINESS-AND-DOCS-v1.0.12` — bringing the chain to **40** commits
> (39 range-exclusive). Per this document's convention, a revision's own commits are listed by hash
> only once they exist, so the three are named by subject here.
>
> **Push state (v1.0.13).** The three commits named in the v1.0.12 note above are now listed by hash
> in the table (`6360228`, `203addf`, `81163e4`), which is why the chain reads 40 through
> `81163e4`. This revision adds `b38efa7` (the FIX-HANGING-SKIP-TEST report commit, previously
> untracked) as the 41st row; its own commit will be recorded by hash in v1.0.14, per the same
> convention.
>
> **Push state (v1.0.14).** The commit promised in the v1.0.13 note above (`6cffd1e`) is now listed by
> hash in the table (row 42), which is why the chain reads **42** through `6cffd1e`. This revision
> (AUDIT-DESIGN-SECURITY-AND-RISK-B) contributes two further commits — `docs(audit): security
> (SEC-01/SEC-02/N-09/N-11) + RISK-B design` and `docs: update PROJECT_SPECIFICATION to v1.0.14
> (security design)` — bringing the chain to **44** commits (43 range-exclusive). Per this document's
> convention, a revision's own commits are listed by hash only once they exist, so the two are named
> by subject here.
>
> **Push state (v1.0.15).** The two commits named in the v1.0.14 note above (`65b25f8`, `a60681f`) and
> the three FIX-NATIVE-SECURITY-SMALL commits (`f66f464`, `1bbf8a6`, `23816cb`) are now listed by hash
> in the table, which is why the chain reads **47** through `23816cb` (46 range-exclusive). This
> revision's own commit will be recorded by hash in a future revision, per the same convention.
>
> **Push state (v1.0.16).** The commit promised in the v1.0.15 note above (`df94a81`) is now listed by
> hash in the table (row 48), which is why the chain reads **48** through `df94a81`. The five SEC-01
> TOFU-pinning commits follow — `b652198`, `9954c03`, `e03f45d`, `ff78116`, `e6b9eef` — bringing the
> chain to **53** through `e6b9eef` (52 range-exclusive). This revision's own commit will be recorded by
> hash in a future revision, per the same convention. (The v1.0.15 note's "47 through `23816cb`" was
> correct for that revision; it listed 47 rows and had not yet listed its own commit, `df94a81`.)
>
> **Push state (v1.0.17).** Four commits are now listed by hash in the table — `8edaee8` and `71edd67` (the
> v1.0.16 spec commit and its chain-count-clarification report commit, both previously deferred by the
> v1.0.16 note), plus `067d4a3` (PART A — the first Axis A source change) and `a7c449f` (the PART A commit
> report) — bringing the chain to **57** through `a7c449f` (**56** range-exclusive). Verified
> `git log --oneline 0714628^..HEAD | wc -l` = **57** against a table of **57** rows, **delta 0**, measured
> at `HEAD = a7c449f`, i.e. before this revision's own commits are made. This revision's own two commits —
> the spec update to v1.0.17 and the `DOCS-UPDATE-v1.0.17` report — are named by subject here and will be
> listed by hash in a future revision, per the same convention. No push was performed while this text was
> written, so the remote remains at `71edd67` until the push step completes; after the push the four commits
> above plus this revision's own two form one fast-forward, with no force and no tag.
>
> **Push state (v1.0.18).** The two commits named by subject in the v1.0.17 note above (`a3a6434` — the spec
> update to v1.0.17 — and `94e02b7` — the `DOCS-UPDATE-v1.0.17` report) are now listed by hash in the table,
> which is why the chain reads **59** through `94e02b7`. This revision (CLOSE-OUT-AND-SPEC-HYGIENE-v1.0.18)
> opens with one documentation commit — `0c5754e` (`docs: commit Phase 12 IMPLEMENT_P1_REMAINING report (was
> untracked)`) — bringing the chain to **60** through `0c5754e` (**59** range-exclusive). Verified
> `git log --oneline 0714628^..HEAD | wc -l` = **60** against a table of **60** rows, **delta 0**, measured
> at `HEAD = 0c5754e`, i.e. before this revision's own spec and report commits are made. This revision's own
> two commits — the spec update to v1.0.18 and the `CLOSE-OUT-v1.0.18` report — are named by subject here and
> will be listed by hash in a future revision, per the same convention. At the time this text was written the
> remote is at `94e02b7`; after the push the three commits above plus this revision's own two form one
> fast-forward, with no force and **no tag** — `p2.4.10` deliberately remains local-only (see §1.6.11 bullet 2).

> **Push state (v1.0.19).** p2.4.10 tag decision executed — **PUSHED**. Remote unique tag set is now
> **5** (`p2.4.5`/`p2.4.7`/`p2.4.8`/`p2.4.9`/`p2.4.10`); local unchanged at 5. HEAD == origin/main ==
> `abc8bd1f84bb7dcf08edd082f3d7e65dcae0183d`. Chain arithmetic per §1.6.12 bullet 2 (delta 1,
> one-own-commit convention). No tag operation other than the authorized push was performed.

> **Push state (v1.0.20).** Z1-IMPL-B (emergency source foundation) landed — Roadmap §12.5.2 item B.
> Three commits make up this revision: `feat(sources)` (source), `test(sources)` (tests) and `docs`
> (this spec update plus `Z1_IMPL_B_REPORT.md`). Items C/D and Cumulative Server Retention (§9 of
> `SERVER_SOURCE_ARCHITECTURE.md`) remain future work. The **HEAD** field above is the
> source-and-tests commit at the time this text was written — a spec file cannot name its own
> commit — so this revision's own docs commit is named by subject here and is listed by hash in a
> future revision, per the one-own-commit convention. Chain arithmetic per §1.6.13 bullet 9. No tag
> operation of any kind was performed.

> **Push state (v1.0.21).** Z1-IMPL-C (emergency CSV parser + wire-in) landed — Roadmap §12.5.2
> **item C**. Three commits make up this revision: `feat(emergency)` (parser + fetcher + tests),
> `feat(emergency)` (opt-in wire-in) and `docs` (this spec update plus `Z1_IMPL_C_REPORT.md`). Items
> D (manual server entry), E/F/G/H and Cumulative Server Retention (§9 of
> `SERVER_SOURCE_ARCHITECTURE.md`) remain future work. The **HEAD** field above is the last
> source-and-tests commit at the time this text was written — a spec file cannot name its own
> commit — so this revision's own docs commit is named by subject here and is listed by hash in a
> future revision, per the one-own-commit convention. Chain arithmetic per §1.6.14 bullet 9. No tag
> operation of any kind was performed.

> **Push state (v1.0.22).** Z1-IMPL-D (manual server entry + MANUAL-preservation merge) landed — Roadmap
> §12.5.2 **item D**. Four commits make up this revision: `feat(sources)` (model + Room entity/DAO +
> migration 6→7 + `App.kt`), `feat(sources)` (the MANUAL-preservation invariant in `VpnServerMerger` and
> the write-boundary merge in `ServerListUpdates`), `feat(ui)` (Settings section + ViewModel + tests) and
> `docs` (this spec update plus `Z1_IMPL_D_REPORT.md`). Item D is complete and the preservation part of
> item **G** is satisfied; items **E/F/H**, the full `DatasetIntegrity` outcomes and Cumulative Server
> Retention (§9 of `SERVER_SOURCE_ARCHITECTURE.md`) remain future work. The **HEAD** field above is the
> last source-and-tests commit at the time this text was written — a spec file cannot name its own
> commit — so this revision's own docs commit is named by subject here and is listed by hash in a
> future revision, per the one-own-commit convention. Chain arithmetic per §1.6.15 bullet 9. No tag
> operation of any kind was performed.

> **Push state (v1.0.23).** Z1-IMPL-G landed — Roadmap §12.5.2 **item G**, in the two parts the
> operator approved: the MANUAL provenance fix (the debt recorded as limitation (c) of §1.6.15
> bullet 9), and the three `DatasetIntegrity` outcomes as a **classification layered on the existing
> verdict** rather than as a new decision. Three commits make up this revision: `feat(sources)` (the
> `SourceIdentity.kt` provenance fix and its tests), `feat(repository)` (the outcome names, the
> outcome log in `ServerListUpdates.replaceAll`, and the outcome tests) and `docs` (this spec update
> plus `Z1_IMPL_G_REPORT.md`). Items **E/F/H** and Cumulative Server Retention (§9 of
> `SERVER_SOURCE_ARCHITECTURE.md`) remain future work — and Retention is the structural reason the
> outcome names do not change any behaviour this revision, see §1.6.16 bullet 6. The **HEAD** field
> above is the last code commit at the time this text was written — a spec file cannot name its own
> commit — so this revision's own docs commit is named by subject here and is listed by hash in a
> future revision, per the one-own-commit convention. Chain arithmetic per §1.6.16 bullet 9. No tag
> operation of any kind was performed.
>
> **Push state (v1.0.24).** `emergency-sources/servers.csv` + `README.md` landed (a real,
> URL-accessible emergency source for end-to-end testing), and the device migration 5 → 6 → 7 that
> was verified on `5585fecc` in a prior phase is now formally recorded — see §1.6.17. The header
> **HEAD** field is corrected from `40decde` (stale since v1.0.23, which named the pre-docs-commit
> hash) to `02ce791`. Two commits make up this revision: `data(sources)` (the folder) and `docs`
> (this spec update plus `EMERGENCY_SOURCES_CSV_REPORT.md`). Chain arithmetic per §1.6.17 bullet 6.
> No tag operation, no build, no test run and no device command was performed.
>
> **Push state (v1.0.25).** The manual-server protocol picker no longer clips its trailing chip: the
> `Row` is now a `FlowRow` and the four protocols are a named, test-pinned constant — see §1.6.18.
> This is the first revision since v1.0.17 to touch **Compose UI source**, and the first in the
> v1.0.20–v1.0.25 run to be **device-verified**: the fix was confirmed on `5585fecc` by UI-hierarchy
> dump, not by inspection. The manual-row visibility half of the same operator report was
> **deliberately deferred** and its mechanism is now *proven* rather than hypothesised — see §1.6.18
> bullets 5–7. Two commits make up this revision: `fix(ui)` (the picker fix plus its regression
> test) and `docs` (this spec update plus `MANUAL_UI_FIX_REPORT.md`). Chain arithmetic per §1.6.18
> bullet 9. No tag operation was performed.
>
> **Push state (v1.0.26).** A manual server now materialises into `VPNGateItem` the moment it is
> added, so it is visible in the main list without waiting for an accepted refresh; a refresh that
> is rejected as degraded is now surfaced to the user instead of being silent; and
> `manualProtocolLabel` no longer falls back to `MS-SSTP` for an unknown key — see §1.6.19. This is
> the first revision to close items from a previous revision's own "recorded but not fixed" list, and
> the first since v1.0.24 to be **device-verified on both halves** (the add-to-visible path and the
> unchanged picker). One requirement in the brief proved **not literally implementable** and was
> resolved by operator decision rather than by guessing — the compile-time exhaustiveness guard for
> the protocol label, because Kotlin has no exhaustive `when` over a `String` and the protocol keys
> are `const val` Strings (§1.6.19 bullet 3). Two commits make up this revision: `fix(manual)` (the
> three fixes plus their regression tests) and `docs` (this spec update plus
> `MANUAL_IMMEDIATE_VISIBILITY_REPORT.md`). Chain arithmetic per §1.6.19 bullet 8. No tag operation
> was performed.

> **Push state (v1.0.28).** RECONCILE-DIRTY-FILES landed — restoring HEAD buildability by committing the
> AUTOCONNECT-AUDIT D-1b closure that had been left in the working tree at v1.0.27. Two commits make up
> this revision: `fix(automode)` (the 7-file D-1b closure — `AutoModeController.kt`,
> `AndroidConnectionAdapter.kt`, `AutoModeProtocol.kt`, `TunnelStateWatcher.kt`, and the three test
> companions `AutoModeControllerTest.kt` / `AutoModeNativeBusyTest.kt` / `VpnM24x10TaskTest.kt`) and `docs`
> (`0f5f307`, carrying `DEVICE_VERIFY_AUTOCONNECT_REPORT.md` only; the v1.0.28 spec text and
> `RECONCILE_REPORT.md` landed in the follow-up docs commit `82cb64f`). *(Reconciled in v1.0.29: at
> the moment the note was written, only `0f5f307` existed, so the claim about "this spec update plus
> `RECONCILE_REPORT.md`" was anticipatory; commit `82cb64f` later made it true. `git show 0f5f307
> --stat` contains only the device-verify report; `git show 82cb64f --stat` contains
> `PROJECT_SPECIFICATION.md` + `RECONCILE_REPORT.md`. SOURCE VERIFIED.)* The header
> **HEAD** field is the source commit `df7b49cf1b91078622cc72774dd1b4fbba73b20a` — the pre-docs-commit
> hash, per the one-own-commit convention. The device-verify report documents that HEAD `b68ac2a` did
> **not** compile standalone (PART A discovery), which the D-1b commit fixes. `HttpFetcher.kt` remains
> deliberately dirty (doc-comment-only OPEN→VPN routing wording, category-(c) UNRELATED-IN-FLIGHT,
> outside the closure). Chain arithmetic per this revision. No tag operation of any kind was performed.
> **Push confirmed (reconciled in v1.0.29):** `origin/main` = `82cb64f` (`docs: PROJECT_SPECIFICATION
> v1.0.28 + RECONCILE_REPORT`), i.e. **3 commits ahead** of `b68ac2a` (`df7b49c` source + `0f5f307`
> device report + `82cb64f` v1.0.28 docs), fast-forward, no force, no tag. *(The original sentence
> said "2 ahead (`df7b49c` + `0f5f307`)" — true only before `82cb64f` landed.)*

> **Push state (v1.0.30).** SPEC-INTEGRITY-AUDIT landed — a read-only reference audit plus a path-repair
> revision. **19 `*.md` path references** were updated to `docs/reports/…` for the files relocated by
> commit `a76bdc6` (phase reports archived under `docs/reports/`); the updates are confined to the
> authorized regions (§1.6.2–§1.6.21: 14; §12.5.1: 5). The remaining **46** bare occurrences of
> moved-only filenames — in §1.6.1's existing notes, §3.1, §6.7, §12.4, the existing §20 rows, and one
> in this subsection's own prose — are **recorded** in §1.6.22 rather than edited, because
> those regions are outside this phase's edit scope; all of them still resolve via the documented
> location. The concurrent-worktree contamination event (`82cb64f` + `82476bf`) is recorded as a process
> lesson in §1.6.22. The pending `HttpFetcher.kt` doc-comment change was **committed** in this revision
> (`9d569c3`) — comment-only, no API change — removing the last tracked dirty file. The single skipped
> `:app` test is `HtmlParserOracleTest.softetherUdpOnlyNeverBorrowsOpenvpnTcp`, skipped by an
> `AssumptionViolatedException` ("no SE-UDP-only + OVPN-TCP row in current snapshot") — data-dependent,
> not a failure. Two commits make up this revision: `chore(sources)` (`9d569c3`) and `docs` (this spec
> update plus `SPEC_INTEGRITY_AUDIT_v1.0.30_REPORT.md`). The header **HEAD** field is the pre-docs
> commit `9d569c37ddba1a4c26db688b398c4fcb3d660112`. No tag operation of any kind was performed.
> **Push state (v1.0.31).** AUTOCONNECT-FIX. Four gaps closed: G-2 (default timeout unified to 12 s), G-17 (isTunnelBusy guard wired to production path), R4 (uniform non-timeout transition), and OP-R6 (Connected press continues from next server). New §6.8.

> **Push state (v1.0.32).** AUTOCONNECT-FIX-2. Closed **BUG-A** (the D-4 predicate now includes
> `CONNECTED`, via `GlobalVpnTracker.isActiveOrBusy`) and **BUG-B** (repeated `NATIVE_BUSY` on the
> same server now records a wedge). Added **slot-non-release instrumentation** — logging only, no
> control-flow change — for the next device investigation phase. Both bugs were found on the
> physical device in DEVICE-VERIFY-AUTOCONNECT-FIX (`DEVICE_VERIFY_AUTOCONNECT_FIX_REPORT.md` §5 and
> §7) after the v1.0.31 JVM suite had gone green; §1.6.24 records the reconciliation. The header
> **HEAD** field is the pre-docs commit `9d9e43effa5b8adabd006520393aaa2dfedd7a0a`. No tag operation
> of any kind was performed.

> **Push state (v1.0.33).** AUTOCONNECT-FIX-3. Instrumented the **NATIVE_BUSY slot-wait site** with
> the same `SLOT_UNRELEASED_DIAG` body as the post-attempt site, which the device run of v1.0.32
> proved was the wrong one of the two (it never fired; the NATIVE_BUSY site terminated the run three
> times). Raised the slot-wait budget **floor to 30 s** — with the device's 5 s preference the
> derived budget was 25 s, leaving only a 4.1 s margin over the largest observed release latency
> (20.9 s), and one release landed 292 ms after that cutoff. Device re-verification of `SLOT_DIAG`
> is **PENDING**. The header **HEAD** field is the pre-docs commit
> `248d06dd67c2dd75038da249d474e368413a21ac`. No tag operation of any kind was performed.

> **Push state (v1.0.34).** AUTOCONNECT-FIX-4. Closed the unbounded RUDP fallback handshake
> (`softether_protocol.c:2908` applied `socket_apply_timeout` the way the TCP path at `:2839` does,
> plus the `[TLS] handshake entry` discriminator). Also closed the wedge counter's structural gap:
> a `NATIVE_BUSY` terminal now records a wedge, so the same broken endpoint is excluded after
> `EXCLUSION_THRESHOLD` runs instead of being re-selected forever. Device re-verify **PENDING**.
> The header **HEAD** field is the baseline commit `518c9b767cff168acf74dfb313cca0487193b5e5`, the
> state this revision was verified against; a commit cannot carry its own hash. No tag operation of
> any kind was performed.

> **Push state (v1.0.35).** AUTOCONNECT-FIX-5. Skips the post-slot-release retry when the previous
> teardown was unresolved. Device evidence from v1.0.34 (`§15` of
> `DEVICE_VERIFY_AUTOCONNECT_FIX_4_REPORT.md`) showed 12 retries in one session: 10 failed instantly
> with `NATIVE_ERROR`, 2 with `NATIVE_BUSY`, **ZERO** succeeded, and 11 of the 12 were preceded by an
> unresolved teardown. The retry could not be admitted in that state, so it only paid the retry's own
> teardown (~6 s per failed protocol). This revision also commits the two prior-phase reports
> (`DEVICE_VERIFY_AUTOCONNECT_FIX_4_REPORT.md`, `SLOT_RACE_INVESTIGATION_PREP_REPORT.md`). Both
> v1.0.34 fixes — Fix-A and Fix-B — remain **UNVERIFIED** on device and are untouched by this
> revision. The header **HEAD** field is the baseline commit
> `c69d2a0bdf3ebf64b2eae554a369757b57d31c9f`, the state this revision was verified against; a commit
> cannot carry its own hash. No tag operation of any kind was performed.

> **Push state (v1.0.36).** AUTOCONNECT-FIX-6, **Option A**. Fix-1: the `NATIVE_BUSY` branch now
> checks `isOwnTunnelEstablished()` **before any wait** — a live tunnel is never destroyed and a
> healthy server is never wedged. Fix-2: the `NATIVE_BUSY` slot wait is capped to
> `min(nativeSlotAwaitMs, T)`. Fix-3: the post-attempt slot wait carries the same cap. Net
> per-protocol time is now `1T` common / `2T`–`3T` rare, down from `7T`; the observed device bug is
> closed. **Option B** (remove the retry entirely, reaching a strict `2T`) was deferred — it breaks
> **9** documented tests. This revision also commits the prior-phase report
> `DEVICE_VERIFY_AUTOCONNECT_FIX_4_UNFILTERED_REPORT.md`. Fix-A (native RUDP bound) remains
> **UNVERIFIED** and untouched. The header **HEAD** field is the baseline commit
> `d884062aaf8be20cc87a9cb19bf3600b415ef4db`, the state this revision was verified against; a commit
> cannot carry its own hash. No tag operation of any kind was performed.

> **Push state (v1.0.37).** AUTOCONNECT-FIX-7 (STOP **Option A**: all eight affected tests rewritten
> under explicit operator authorization). Fixes the **v1.0.36 regression**: 2 of 5 device runs
> terminated `ERROR_NATIVE_UNRESOLVED` on the **first** server. Fix-1 (adapter):
> `lastArmedBusyRefused` distinguishes an own-operation timeout (→ `TIMED_OUT`) from a genuine
> start-refusal (→ `NATIVE_BUSY`). Fix-2 (controller): a slot-wait timeout no longer terminals —
> both branches log `Slot-wait expired … moving on`, issue one `disconnect()` and advance. Fix-3
> (contract): `ERROR_NATIVE_UNRESOLVED` is removed as a terminal; `ERROR_NO_SERVER` is the only
> exhausted-list terminal, which honours the operator contract already stated in §6.8.4. **Eight**
> tests rewritten (2 in `AutoModeControllerTest`, 6 in `AutoModeNativeBusyTest`), none deleted; the
> six in the latter file had been pinning the **opposite** of §6.8.4 since v1.0.28 (§1.6.29 bullet 8).
> OP-R6 (try-next) is re-pinned by T-NEW-21. Fix-A (native RUDP bound) remains **UNVERIFIED** and
> untouched. The header **HEAD** field is the code commit
> `86ade9f3a2a260eb30054a70897d8f894680cf35`, the state this revision was verified against; a commit
> cannot carry its own hash. No tag operation of any kind was performed.

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

#### 1.6.4 AutoConnect Root-Cause Resolution (v1.0.10)

This subsection records the v1.0.10 AutoConnect work: a read-only audit that isolated the root
cause of the Auto Mode connection wedge, the native fix that landed against it, the Kotlin
soft-bound bundle that landed alongside it, the test repair required to re-green the suite, and the
device evidence and residual finding from the combined verification run. Each bullet carries its
evidence label (see the evidence taxonomy in §20).

- **Root cause identified (audit).** The read-only audit `docs/reports/AUDIT_AUTOCONNECT_LOGIC.md` isolated the
  single root cause of the Auto Mode connection wedge: the OpenSSL TLS handshake runs on a
  **blocking** socket with **no effective `SO_RCVTIMEO`/`SO_SNDTIMEO`**, so `SSL_do_handshake()` can
  block **indefinitely** while holding **both** `g_openssl_lock` and `g_tls_use_lock`. Because those
  locks are held across the stalled handshake, every later attempt is refused — the observable
  "native slot still held" wedge. On a blocking socket, the socket timeouts are the **only**
  mechanism that can interrupt a stalled `recv()`/`send()`; the kernel default is `0` (infinite).
  The audit also confirmed the pre-existing `socket_set_timeout` helper in `tcp_socket.c` had
  **zero callers** (dead code). Evidence: SOURCE CODE (audit, read-only).
- **Native fix landed (commit `eb21e56`).** Three parts, all inside `softether-core`:
  - **P1 — bound the handshake socket.** A new `socket_apply_timeout(fd, timeout_ms)` sets
    `SO_RCVTIMEO` **and** `SO_SNDTIMEO` on the handshake fd, applied at **both** TCP handshake
    sites before `SSL_do_handshake()`. The existing dead `socket_set_timeout` was **refactored to
    delegate** to the new helper (single implementation), which also closed its original
    silent-failure hole (both `setsockopt` returns are now checked). A timed-out `recv()`/`send()`
    returns `EAGAIN`, which OpenSSL surfaces as `SSL_ERROR_WANT_READ`/`WANT_WRITE` — exactly the
    condition the existing `max_attempts = 100` retry loop already handles, so `aes_wrapper.c`
    needed no change. Evidence: SOURCE CODE.
  - **P5a — permanent discriminator log.** Exactly **one** `LOGD` at handshake entry
    (`[TLS] handshake entry: conn->timeout_ms=…, socket_fd=…, SO_RCVTIMEO applied=yes|no`), outside
    any loop, so a future wedge can be attributed without re-instrumentation. Evidence: SOURCE CODE.
  - **P5b — bounded parallel-race join.** The transport-thread race (`transport_thread_tcp` /
    `_rudp_direct` / `_natt` / `_dns` / `_icmp`) previously ended in an **unbounded**
    `pthread_join`. Because Android Bionic provides **neither `pthread_timedjoin_np` nor
    `pthread_tryjoin_np`** (verified across NDK 25.1–28.2 sysroots), the bound was implemented as an
    **exit-flag trampoline**: each transport runs behind a wrapper that publishes an `exited` flag
    before returning; the parent polls to a deadline and, if a thread has not exited, logs the exact
    `[RACE] transport thread N did not exit within … ms … detaching` WARN and calls
    **`pthread_detach`** (deliberately **not** `pthread_cancel`, which could strand
    `g_openssl_lock`). The transport-context array was moved from the stack to a `calloc`'d heap and
    is freed only when `detached_count == 0` (winner fields snapshotted first), closing the
    detached-thread use-after-free risk. Evidence: SOURCE CODE.
- **Kotlin bundle landed (commit `4df7e79`).** The soft-bound / Auto-Mode-recovery bundle:
  - **FIX-1** — lowered the CONNECTING soft-bound
    `NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS` from `120_000L` to `30_000L`.
  - **FIX-2** — Auto Mode now **requests a recovery teardown** so an unresolved native operation can
    be released and a later attempt admitted (the `[AUTO] Requesting recovery teardown …` path).
  - **FIX-3** — the retry/terminal logging path that names the holding `attempt=N phase=<PHASE>`.
  - **FIX-C** — the Kotlin-side `Kotlin attempt abandoned after …` guard for an abandoned attempt.
  - **PART-1/2** — the two-part threshold work keyed off the mutually exclusive phases (TEARDOWN
    `90_000L`; CONNECTING `30_000L`), both evaluated **only inside `tryAcquire()`**.
  Evidence: SOURCE CODE.
- **Test repair landed (commit `a87c523`).** `AutoModeControllerTest.nativeBusyAllRetriesFailTerminal`
  used a **strict equality** assertion on the full terminal log line, which had gone **stale** since
  commit `5e51e37` appended the `(${describe()})` interpolation. The assertion was relaxed to a
  **two-part `contains`** (mirroring `AutoModeNativeBusyTest`), with a comment citing T5-SLOT /
  `5e51e37`. Git archaeology confirmed the bundle did **not** cause the failure: the production line
  is a **context line** in `git diff`, and `c572050` set the expectation before `5e51e37` added the
  interpolation — it had simply never been exercised by a post-`5e51e37` `:app:testDebugUnitTest`
  run. Only this one assertion changed. Evidence: SOURCE CODE.
- **Device evidence (PHYSICAL DEVICE — from the FINALIZE phase log).** The combined verification run
  on device `5585fecc` (Xiaomi 2201117TG, Android 13 / API 33) confirmed the native fix in effect:
  `[TLS] handshake entry:` fired **2×**, both with `SO_RCVTIMEO applied=yes` (**0× `applied=no`**);
  `SSL handshake successful` **3×**; `SSL handshake failed` **2×** (one
  `error:0A000126:SSL routines::unexpected eof while reading`); `Starting TLS handshake` **2×**;
  `VPN connection established` **2×**; `State changed: CONNECTED ip=10.211.1.111`; and a clean
  `Native cleanup confirmed (attempt=1, session=1)`. Crash scan over the captured window returned
  **0** crash indicators. All six build-verification needles were **PRESENT** in the pulled APK
  (`[TLS] handshake entry:`, `socket_apply_timeout`, `[RACE] transport thread` in
  `lib/*/libsoftether.so`; `requestRecoveryTeardown`, `WedgeTracker` in `classes30.dex`;
  `Kotlin attempt abandoned after` in `classes6.dex`). Evidence: PHYSICAL DEVICE.
- **Residual finding (OPEN — UNKNOWN).** During the same run the device logged
  `[AUTO] Native slot still owned by an unresolved operation (attempt=2 phase=TEARDOWN, reporting
  deadline expired (still blocked)); stopping without blaming this server`, and later attempts were
  refused with `Start refused: native slot still held` for roughly a one-minute window (17:36:20 →
  17:37:31). FIX-2 fired **3×**, but **no `FORCED_RELEASE_AFTER_TIMEOUT`** fired. Whether this is a
  pure timing artefact (the 90 s TEARDOWN bound not yet reached before Auto Mode stopped) or a
  genuine bound gap is **UNKNOWN**. Tracked as **FINDING T5-SLOT-TEARDOWN residual** in §12.5.1
  (priority P2_MEDIUM, opportunistic). Evidence: PHYSICAL DEVICE + SOURCE CODE.
- **Soft-bounds are now safety nets, not the primary fix.** With P1 bounding the handshake socket
  itself, the Kotlin soft-bounds (FIX-1 CONNECTING `30_000L`, TEARDOWN `90_000L`) are re-characterized
  in v1.0.10 as **safety nets** for a still-unresolved operation rather than the mechanism that
  breaks the wedge. Evidence: SOURCE CODE.
- **Methodology lessons:** No new methodology lessons in v1.0.10.

#### 1.6.5 Native Hardening Bundle (v1.0.11)

This subsection records the v1.0.11 work: the characterization of an uncommitted native patch, the
one-line compile repair it required, the defensive race-budget guard added before device
verification, the automated device verification of the whole bundle, and the narrowing of the
T5-SLOT-TEARDOWN residual. Each bullet carries its evidence label (see the evidence taxonomy in
§20).

- **The unknown native patch was characterized as a coherent hardening patch, and the verdict was
  FIX.** Five native files were found modified and uncommitted. A strictly read-only investigation
  (`docs/reports/INVESTIGATE_UNKNOWN_NATIVE_CHANGES_REPORT.md`) matched every hunk to a named audit finding
  (AC-01..AC-04 parallel-race lifetime/deadline/TLS bounds; N-01 refcount re-check; N-04 ICMP
  port-0; N-10 fd-leak on non-blocking connect failure; N-05 NAT-T backoff exponent), confirmed the
  oddity scan was clean (0 `printf`, 0 `exit`, 0 `TODO`), and confirmed each audit section is inline
  Doxygen. The patch was coherent but **did not compile**. Evidence: SOURCE CODE (read-only audit).
- **Compile repair landed (commit `c8acb5d`).** `rudp_ref_release` (defined at `softether_rudp.c:111`)
  was called inside `rudp_ref_acquire` (`:100`) with no forward declaration. The minimal fix — one
  forward declaration adjacent to the existing `rudp_destroy_final` declaration, plus a two-line
  comment — restored the build. Evidence: SOURCE CODE.
- **Pre-device defensive guard landed (commit `1a4818a`).** A `budget == 0` guard was added to all
  five transport workers in `softether_protocol.c` immediately after
  `uint32_t budget = race_remaining_budget_ms(ctx->ctrl);`: each worker now logs
  `race budget exhausted before start; aborting` and returns instead of entering the race with a
  zero remaining budget. `docs/reports/PRE_DEVICE_BUDGET_GUARD_REPORT.md` records the edit; the file's numstat
  moved 257/112 → 287/112. Evidence: SOURCE CODE.
- **The hardened bundle was device-verified with zero operator taps (commit `6119a2a`).** On device
  `5585fecc` (Xiaomi 2201117TG, Android 13 / API 33), with UI automation substituting for operator
  input: `[TLS] handshake entry:` fired **12×**, all with `applied=yes` and **0× `applied=no`**;
  the parallel race ran once to `Parallel race: all transports failed` at exactly **5000 ms** under
  the deadline model, with **0** `[RACE] transport thread … detaching` events and **0**
  `race budget exhausted`; **0** crashes/tombstones/SIGSEGV; thread count went **54 → 35** (no
  monotonic leak); `nativeDestroy completed`, `Released process-wide native slot` and
  `Connection destroyed` each fired **11×**. `docs/reports/DEVICE_VERIFY_HARDENING_AUTO_REPORT.md` records all
  eight goals VERIFIED (goal F PARTIAL only because `/proc/<pid>/fd` is Permission-denied, which
  makes the fd-leak measurement INCONCLUSIVE — thread count was used as the leak proxy).
  Evidence: PHYSICAL DEVICE.
- **The T5-SLOT-TEARDOWN residual is narrowed, not closed.** The residual fired **7×** as
  `[AUTO] Teardown finished but NATIVE CLEANUP UNCONFIRMED (teardown#N, NOOP_REENTRANT); ownership
  retained, a new native attempt will be refused`, and in **every** case the
  `ConnectionController` confirmed cleanup within ~1 s and the next attempt proceeded normally.
  The mechanism is now understood from source (a `NOOP_REENTRANT` completion for the same attempt
  id satisfies `TunnelStateWatcher`'s exact-attempt-id wait before the real `CONFIRMED` arrives —
  see `AndroidConnectionAdapter.kt:226`, `SoftEtherVpnService.kt:1081–1106`,
  `ConnectionController.kt:937`); the **upstream trigger** for the re-entrant teardown call remains
  **UNKNOWN**, and the ~1 s vs ~1 minute window variance between the v1.0.10 and v1.0.11 runs is
  unexplained. §12.5.1 row 9 therefore stays **OPEN** with narrowed scope. Full classification in
  `docs/reports/AUDIT_AXES_A_B_C_v1.0.11.md` §2. Evidence: PHYSICAL DEVICE + SOURCE CODE.
- **Three read-only axes were audited alongside the spec update** (tunnel state / UI sync; server
  list network binding; SoftEther UDP / TUN `EIO`), each with its findings register and
  recommendations, in `docs/reports/AUDIT_AXES_A_B_C_v1.0.11.md`. No final value, timeout, algorithm, ordering,
  or network-binding implementation was invented by that audit; already-decided requirements
  (`SERVER_SOURCE_ARCHITECTURE.md` §7 Q1–Q3, each a **USER-DIRECTED REQUIREMENT**) were cited, not
  re-decided. Evidence: SOURCE CODE (read-only) + PRIOR REPORT.
- **Push state (v1.0.11).** The five appended commits (`5773c60`, `fd4fcf9`, `c8acb5d`, `1a4818a`,
  `6119a2a`) were pushed with a clean fast-forward (`5773c60..6119a2a`). No force, no tag.
  Evidence: SOURCE CODE (`git`).
- **Methodology lessons:** No new methodology lessons in v1.0.11.

#### 1.6.6 Test Flakiness and Docs Bundle (v1.0.12)

This subsection records the v1.0.12 work: the two open test-suite defects carried over from the
FIX-HANGING-SKIP-TEST phase (**OPEN-1**, **OPEN-2**), their fixes, the regression test added for
OPEN-1, and the two full green JVM runs that verify them. No production code, no build artifact and
no device were touched in this phase. Each bullet carries its evidence label (see the evidence
taxonomy in §20).

- **OPEN-1 root cause confirmed (test-only defect).** `VpnServerRepositoryR4bTest` collected its
  `[collector] path=...` lines into a plain `ArrayList` through the injected `pathLog` lambda.
  `VpnServerRepository.refresh` (§8.1) runs the api and html fetches as two concurrent `async`
  coroutines under `Dispatchers.IO` (the mirror rung is awaited first, then `apiDeferred` and
  `htmlDeferred` are launched together), so two threads call `add` on the same list with no
  synchronisation. A lost `add` shortens the list — exactly the intermittently-missing
  `[collector] path=vpn source=html` line that `vpn path is chosen when every input holds` asserted.
  Evidence: SOURCE CODE.
- **OPEN-1 fix landed (test-only).** The collector is now a `CopyOnWriteArrayList`. No assertion
  logic was changed; the element-wise `List.equals` comparisons the tests already used
  (`assertEquals(listOf(...), logs)`) are unaffected. Evidence: SOURCE CODE.
- **OPEN-1 regression test added.** `vpn-path-logs-are-never-lost-under-concurrent-fetch` drives
  `refresh()` 50 times on the VPN rung and asserts both the exact total (250 lines) and the exact
  per-line count (50) for each of the five expected lines, so a dropped write shows up as a short
  count rather than a rare suite failure. It ran in **0.783 s** (run 1) / **0.755 s** (run 2),
  comfortably inside the <2 s budget, and is deterministic under the fix. Evidence: TEST.
- **OPEN-2 root cause confirmed (test-only defect).** `AutoModeControllerTest.awaitTerminal()` — the
  helper used by 40 call sites — looped `while (true)` until a terminal state with `!isRunning`,
  with no bound. A controller that never settled would stall the whole `:app:testProDebugUnitTest`
  suite instead of failing one test — the same failure mode already fixed in
  `awaitNextTerminalAfterChange` (commit `3c656a4`). The success predicate itself was correct.
  Evidence: SOURCE CODE.
- **OPEN-2 fix landed (test-only).** The wait is now wrapped in `withTimeoutOrNull(10_000)` with the
  same 25 ms poll; on timeout it fails loudly, naming the last state, `isRunning` and the last twelve
  adapter log lines. The success predicate is unchanged and the timeout is a defaulted parameter, so
  every existing call site is untouched. Evidence: SOURCE CODE.
- **JVM verification — library module.** `CI=true ./gradlew :SoftEtherClient:test --rerun-tasks
  --no-build-cache --no-daemon` → **BUILD SUCCESSFUL** (1 m 11 s), **160 / 0 / 0 / 0**
  (tests / failures / errors / skipped) across 9 result files. Evidence: TEST.
- **JVM verification — app module, run twice.** `CI=true ./gradlew :app:testProDebugUnitTest
  --rerun-tasks --no-build-cache --no-daemon` was executed **twice**; both runs were **BUILD
  SUCCESSFUL** (5 m 48 s and 5 m 40 s) with **647 / 0 / 0 / 1** across 48 result files. The single
  skip is the pre-existing, legitimate `AssumptionViolatedException` in
  `HtmlParserOracleTest.softetherUdpOnlyNeverBorrowsOpenvpnTcp` ("no SE-UDP-only + OVPN-TCP row in
  current snapshot"). The count is 647 — one higher than the pre-phase 646 — because of the new
  OPEN-1 regression test. Evidence: TEST.
- **No build artifact and no device were touched.** This phase did **not** run
  `assembleProDebug`/`assembleFreeDebug` and performed **no** `adb`/device interaction; the
  verification is JVM-only, as scoped. Evidence: SOURCE CODE (the phase's own command log).
- **§12.5.1 rows 11 and 12 added and marked CLOSED** for OPEN-1 and OPEN-2 respectively, each
  pointing back to this subsection. Evidence: SOURCE CODE.
- **Methodology lessons:** No new methodology lessons in v1.0.12.

#### 1.6.7 Security Audit and RISK-B Closure (v1.0.14)

This subsection records the read-only, design-only **AUDIT-DESIGN-SECURITY-AND-RISK-B** phase: the
characterisation of four security findings (SEC-01, SEC-02/APP-1, N-09, N-11), the exhaustive
call-graph audit that closes RISK-B, and the candidate designs. **No source, test, native, build,
manifest or XML file was modified, and no build/test/device command was run.** The full audit and the
designs live in `docs/reports/AUDIT_SECURITY_AND_RISK_B_v1.0.14.md`. Each bullet carries its evidence label.

- **SEC-01 (== §12.5.1 row 4, N-06) — native TLS verification disabled.** The single production
  verification decision is `SSL_CTX_set_verify(c, SSL_VERIFY_NONE, NULL)` at `aes_wrapper.c:110`,
  inside `ssl_configure_client_ctx` (`:101`), installed by `ssl_create_client` (`:355`/`:376`); the
  protocol window is TLS 1.0–1.2 (`:103`/`:104`). There is no trust store, no `SSL_set1_host`, no
  `SSL_get_verify_result` and no pin; SNI is set to the resolved **IP** (`:424–428`). **Open.**
  Three candidate designs were compared (public-CA + hostname; **TOFU fingerprint pinning**; SPKI key
  set) and TOFU fingerprint pinning is recommended. Implementation **deferred**. Evidence: SOURCE CODE.
- **SEC-02 / APP-1 — cleartext HTTP permitted globally.** `AndroidManifest.xml:28`
  (`usesCleartextTraffic="true"`) and the blanket `network_security_config.xml:3`
  (`<base-config cleartextTrafficPermitted="true"/>`); HTTP canonical/fallback URLs and HTTP bootstrap
  mirrors exist in `VpnGateSources.kt`. HTTPS is tried first and `ResilientDns` defends against DNS
  poisoning. Migration design: scope cleartext to named hosts. **Deferred.** Evidence: SOURCE CODE.
- **N-09 — non-cryptographic `rand()` for key/IV/cookie material.** Actual sites are
  `softether_rudp.c:251–268` (RC4 keys / `next_iv` / cookies; plus `:838`, `:1018`, `:1048` for
  jitter/pad) and `rudp_transport.c:316,370,402,731,760,777,840,850,1074,1151,1152,1183,1225`, with
  `srand()` at `:1026` (the §12.5.1 row-5 line refs were stale and are corrected there). The
  `generate_random_bytes()` wrapper (`aes_wrapper.c:337`, `RAND_bytes` under `g_openssl_lock`) already
  exists and is already used at `rudp_transport.c:217,319,736,751,1070,1216,1221`. Migration design:
  route key/IV/cookie sites through it and drop `srand()`. **Deferred.** Evidence: SOURCE CODE.
- **N-11 — `atoi()` parses `Content-Length`.** Three sites: `softether_protocol.c:818, 4075, 4202`,
  each `(uint32_t)atoi(...)`. A negative/malformed value casts to `0xFFFFFFFF`, but every site is
  bounded downstream (`:838–839`, `:4078`, `:4207`), so this is robustness/defence-in-depth, not a
  demonstrated exploit. Design: strict bounded parse. **Deferred.** Evidence: SOURCE CODE + INFERENCE.
- **RISK-B — CLOSED (dead code).** The legacy `SoftEtherClient.disconnect()` (`SoftEtherClient.kt:82–92`)
  can neither be reached nor run: its guard requires `isConnected == true` (`:14`, never set true) and
  `nativeHandle != 0` (`:13`, never assigned non-zero); it has **zero** production callers (the sole
  production instantiation, `ConnectionController.kt:83`, tears down via `client.nativeDisconnect` /
  `nativeDestroy` at `:510–511`), **zero** test callers (`ThroughputBenchmarkTest.kt:114,234`), no
  reflection/`Class.forName`/method reference, and the `KeepAliveManager(client)` holder never uses it.
  Recommendation: **delete the method** (nothing pins it — `SoftEtherClientHandleGuardTest` does not
  assert it). Removal **deferred**. Evidence: SOURCE CODE + TEST.
- **OkHttp HTTPS path verifies.** The collector/HTTP path uses OkHttp with the Android/system trust
  store, so SEC-01 is confined to the native tunnel; the metadata-fetch path is unaffected.
  Evidence: SOURCE CODE.
- **Methodology lessons:** No new methodology lessons in v1.0.14.

#### 1.6.8 Security Fixes Landed + Device-Verified (v1.0.15)

This subsection records the **FIX-NATIVE-SECURITY-SMALL** implementation phase (three commits:
`f66f464`, `1bbf8a6`, `23816cb`) and the **DEVICE-VERIFY-SECURITY-SMALL** physical-device verification
of the four low-risk security fixes designed in v1.0.14 (§1.6.7). No source, test, native, build,
manifest or XML file was modified in *this* documentation revision. Each bullet carries its evidence
label.

1. **N-09 FIXED — CSPRNG for RUDP key/IV/cookie material.** Every `rand()` site cited in §1.6.7 was
   migrated to the existing `generate_random_bytes()` / `RAND_bytes` wrapper (`aes_wrapper.c:337`) in
   `softether_rudp.c` and `rudp_transport.c`. The key/IV generation in `rudp_create` now issues one
   `generate_random_bytes()` call per key/IV, and cookies / jitter / pad / ICMP-DNS ids use a 32-bit
   CSPRNG helper (`rudp_rand_u32` / `rt_rand_u32`). `srand()` (old `rudp_transport.c:1026`) was
   removed. Commit `f66f464`. Evidence: SOURCE CODE + TEST.

2. **N-11 FIXED — strict `Content-Length` parse.** `atoi()` was replaced at three sites
   (`softether_protocol.c:854`, `:4116`, `:4252`) by a static `parse_content_length()` that returns
   `0` for an empty value, `-1` for a malformed value (leading sign, non-digit, trailing junk, or
   overflow) and the parsed value otherwise, bounded by `N11_MAX_CONTENT_LENGTH` (**64 MiB**). Callers
   treat `-1` as a parse failure, reject the response, and (at the two `additional_connect` sites)
   clean up the SSL/fd state before returning. The value can never wrap to `0xFFFFFFFF`. Commit
   `f66f464`. Evidence: SOURCE CODE + TEST.

3. **RISK-B CLOSED — dead `SoftEtherClient.disconnect()` deleted.** The legacy method was removed in
   full (no stub, no `@Deprecated`, no commented block). It was already unreachable (its guard
   required `isConnected == true`, never set, and `nativeHandle != 0`, never assigned) and had zero
   production/test callers (§1.6.7). Commit `1bbf8a6`. Evidence: SOURCE CODE.

4. **SEC-02 FIXED — cleartext scoping.** `AndroidManifest.xml` now sets
   `android:usesCleartextTraffic="false"`; `network_security_config.xml` sets
   `<base-config cleartextTrafficPermitted="false"/>` plus a single
   `<domain-config cleartextTrafficPermitted="true">` listing **exactly the six** VPN Gate bootstrap
   IP mirrors (`150.40.105.19`, `150.40.105.6`, `150.40.105.11`, `150.40.105.23`, `194.156.89.134`,
   `121.186.186.97`); and `VpnGateSources.kt` dropped the HTTP canonical URLs (`MAIN_HTTP_URL`,
   `API_HTTP_URL`, `MIRRORS_HTTP_URL`) so the canonical HTML/API/directory lists are HTTPS-only.
   Commit `1bbf8a6`. Evidence: SOURCE CODE.

5. **SEC-02 DEVICE-VERIFIED — active enforcement observed.** On device `5585fecc` Android logged
   **36×** `CLEARTEXT communication to <ip> not permitted by network security policy` for **10
   distinct** non-allow-listed hosts, while the six allow-listed bootstrap hosts were permitted — five
   observed succeeding over HTTP (`Accepted mirror_…` for `150.40.105.6`, `.11`, `.19`, `.23`,
   `121.186.186.97`). `intersection(blocked, allow-list) = ∅`. The packaged manifest reports
   `usesCleartextTraffic=false` and `networkSecurityConfig=@0x7f14000a`. Evidence: PHYSICAL DEVICE.

6. **Collector still works over HTTPS.** A fresh Refresh on device emitted all three
   `[collector] path=open source=mirror|api|html` lines; `100` servers were collected
   (`HTML servers: 100`, `Collected 100 servers`) and the existing list of `104` was preserved
   (`DEGRADED_DATASET` — graceful; no empty list, no crash). Evidence: PHYSICAL DEVICE.

7. **AutoConnect still works end-to-end.** 3 + 1 AutoConnect cycles all reached
   `[AUTO] Connected successfully` with real tunnels (`Japan[59.138.182.93][TCP:1378]`, `tun0`); the
   stress cycle's second tap was absorbed by the re-entrancy guard
   (`[AUTO] start ignored: already running`). Observed: 4× `[AUTO] Started`, 4×
   `[AUTO] Connected successfully`, 3× `[TEARDOWN-REENTRY]`. Evidence: PHYSICAL DEVICE.

8. **No crash / tombstone / process death.** The device run produced an **EMPTY** crash buffer and
   **0** FATAL / SIGSEGV / tombstone / ANR / process-death events (the 29 `AndroidRuntime` lines were
   all `uiautomator` / `monkey` tooling, not the app process). Evidence: PHYSICAL DEVICE.

9. **In-place update confirmed.** `firstInstallTime` was unchanged across `adb install -r`
   (`2026-09-28 16:05:05`), and app settings remained at their defaults (Auto-Mode timeout 5 s, max
   connections 8, protocol priority `SOFTETHER_TCP,SOFTETHER_UDP,MS_SSTP`, locale `en`).
   Evidence: PHYSICAL DEVICE.

10. **SEC-01 remains OPEN.** Native TLS certificate verification is still disabled
    (`SSL_CTX_set_verify(..., SSL_VERIFY_NONE, ...)` at `aes_wrapper.c:110`); the TOFU fingerprint
    pinning design from §1.6.7 is **deferred** to its own phase. Evidence: SOURCE CODE.

- **Methodology lessons:** No new methodology lessons in v1.0.15.

#### 1.6.9 SEC-01 TOFU Fingerprint Pinning (v1.0.16)

This subsection records the **FIX-SEC01-TOFU-PINNING-IMPL** implementation phase (commits `b652198`,
`9954c03`, `e03f45d`) and its **FIX-SEC01-RECONNECT** follow-up (commits `ff78116`, `e6b9eef`), which
together close **SEC-01** (== §12.5.1 row 4 / N-06) — with an explicit **PARTIAL** caveat on the
reconnect-path verification (bullet 6). No source, test, native, build, manifest or XML file was modified
in *this* documentation revision. Each bullet carries its evidence label.

1. **Design adopted — Design B (TOFU certificate-fingerprint pinning).** Chosen from the three designs
   compared in `docs/reports/AUDIT_SECURITY_AND_RISK_B_v1.0.14.md` §2. **Design A** (a CA bundle / trust store) is
   incompatible with VPN Gate relays, which are self-signed and addressed by IP — there is no usable CA
   chain and no DNS name to bind; **Design C** (public-key pinning with a user-visible fingerprint prompt)
   was rejected for UX complexity. Design B pins the leaf certificate's SHA-256 per endpoint on first use
   and rejects a later mismatch: fail-closed, no user interaction, no trust store.
   Evidence: SOURCE CODE + PRIOR REPORT.

2. **Native side landed.** `aes_wrapper.c` adds `ssl_get_peer_cert_fingerprint(ssl_context_t*, uint8_t*,
   size_t)` (`:674`), which takes the peer certificate with `SSL_get_peer_certificate` and computes
   `X509_digest(cert, EVP_sha256(), out, &len)` (`:694`), returning 0 on success with a 32-byte digest.
   `softether_jni.c` exposes it as the JNI entry `nativeGetPeerFingerprint` (`:690`). The native verify
   policy is **unchanged**: `SSL_CTX_set_verify(c, SSL_VERIFY_NONE, NULL)` still stands
   (`aes_wrapper.c:111`; the `:110` citations in §1.6.7/§1.6.8 predate commit `b652198`, whose added
   include shifted the line by one). Per Design B the rejection is a Kotlin-side decision made *after*
   the handshake. Commit `b652198`. Evidence: SOURCE CODE.

3. **Kotlin side landed.** `security/ServerFingerprintStore.kt` holds the pure TOFU rule
   (`VerifyResult { MATCH, FIRST_USE, MISMATCH, MISSING }`, `TofuDecision { PROCEED, BLOCK }`,
   `ServerKey(hostname, ip, port)` with a canonical `host|ip|port` key), and `ServerFingerprintStoreAndroid`
   persists one lowercase-hex SHA-256 per endpoint in the private `tls_server_fingerprints`
   SharedPreferences (plain, not encrypted — a certificate fingerprint is public data). The pure rule is
   covered by **11** pure-JVM tests. Commit `9954c03`. Evidence: SOURCE CODE + TEST.

4. **Wiring at the primary handshake.** `ConnectionController.performConnectInner()` runs the TOFU check
   immediately after a successful `nativeConnectWithHub`: `FIRST_USE` → store the pin and proceed;
   `MATCH` → proceed; `MISMATCH` → fail-closed through the existing error channel (`currentState = ERROR`,
   `isCancelled`, `onError("TLS fingerprint mismatch")`). `MISSING` is treated as first-use only when no
   pin exists — a *pinned* endpoint that presents no fingerprint returns `MISMATCH` (fail-closed).
   Commit `9954c03`. Evidence: SOURCE CODE.

5. **RECONNECT-PATH FIX (v1.0.16).** The Phase-9 wiring above covered only the primary handshake. A
   follow-up audit (FIX-SEC01-RECONNECT) found that `ConnectionController.attemptReconnect()` completes a
   **new** TLS handshake — it claims/destroys the previous handle, runs a fresh `nativeCreate()`, then a
   fresh `nativeConnectWithHub()` — so the reconnect path was **unchecked**: a latent Design-B bypass in
   which a relay substituted after first use would be accepted on every reconnect. Fixed by extracting
   `private fun verifyTofuAfterHandshake(handle: Long)` and calling it from **both** handshake-completing
   paths; on `MISMATCH` the reconnect path sets `ERROR`, tears down through the existing single-owner
   `claimAndDestroyNativeHandle()`, reports `onError("TLS fingerprint mismatch")` and returns (deliberately
   not `throw`, to avoid the catch block's duplicate error and double teardown). Commit `ff78116`.
   Evidence: SOURCE CODE.

6. **Verification status of SEC-01 — PARTIAL (recorded explicitly).**
   - *Primary handshake:* **DEVICE-VERIFIED** (Phase 9 + Phase 10, device `5585fecc`). Three AutoConnect
     cycles produced `[TOFU] match` × 3 and **0 mismatch**; the pin store persisted through
     install/upgrade (md5 unchanged); the crash buffer was empty.
   - *Reconnect handshake:* **NOT device-reproduced.** Reaching `attemptReconnect()` requires a mid-session
     network interruption (send / TUN / receive error, or a keepalive timeout), which this project's rules
     forbid inducing. Its coverage is therefore proven by **SOURCE CODE** (the shared helper is invoked at
     both call sites) plus **2 source-shape tests** (bullet 7) — **not** by a device trace. Tracked
     opportunistically as **§12.5.1 row 15**.
   - Evidence: PHYSICAL DEVICE (primary handshake) + SOURCE CODE (reconnect) + **UNKNOWN** (reconnect not
     observed live).

7. **Source-shape regression tests.** `Sec01ReconnectTofuCoverageTest` adds **2** pure-JVM tests that read
   `ConnectionController.kt` from the module test CWD and assert that the TOFU check is present in
   `attemptReconnect` and invoked from exactly the two handshake-completing paths — the same technique
   `SoftEtherClientHandleGuardTest` uses, because `ConnectionController` cannot be instantiated in a JVM
   test. The library suite is **173/0/0/0** (was 171); the app suite is unchanged at **647/0/0/1**.
   Commit `ff78116`. Evidence: TEST.

8. **Honest caveat.** SEC-01 is **not** marked FIXED-with-full-evidence. It is recorded as **CLOSED
   (v1.0.16)** with an explicit **PARTIAL** caveat, consistent with this document's rule (§20 legend) that
   UNKNOWN / NOT TESTABLE / NOT REPRODUCED must be recorded explicitly rather than fabricated.
   Evidence: SOURCE CODE + INFERENCE.

- **Methodology lessons:** No new methodology lessons in v1.0.16.

#### 1.6.10 PART A (Axis A) Landed, with an Explicit ANR Caveat (v1.0.17)

This subsection records the **COMMIT-PART-A-AND-DOCS-v1.0.17** revision. **PART A** — the Axis A
(tunnel state / UI sync) work registered at **§12.5.1 row 10** — is committed for the first time here
(`067d4a3`), together with its rationale record (`a7c449f`). The revision is **documentation plus a
commit**: it ran **no build, no test and no device command**, and the device (5585fecc) was DETACHED
throughout. PARTs B, C and D were found **already landed** and were **not** re-landed (bullet 8). Each
bullet carries its evidence label.

1. **PART A — A-2, the `onLost` re-check budget (500 ms → 50 ms).** `GlobalVpnTracker.onLost` delayed
   its re-check by a hard-coded `500` ms; it is now `VPN_LOST_RECHECK_MS`, a new file-scope constant
   (`internal const val VPN_LOST_RECHECK_MS = 50L`) with a documenting comment. The budget was **reduced,
   not removed**: `onLost` is delivered *before* `onAvailable`, so the re-check is what absorbs a network
   handover — a handover arriving inside the window is re-observed rather than published as a disconnect.
   Removing it outright would re-introduce the handover flap that the budget exists to prevent. Evidence:
   SOURCE CODE.

2. **PART A — A-3, four protocol-name "egates" replaced by a stateless fold.** The four
   `_vpnState.value.protocol == <literal>` checks in `GlobalVpnTracker` silently dropped or mis-published
   state: two swallowed a disconnect belonging to the non-current protocol, and two could publish a
   teardown for protocol X while protocol Y was still the live tunnel. They are replaced by a pure,
   stateless fold — `object VpnStackLedger { fun reconcile(live, current, event): VpnStackReconciliation }`
   — backed by a `CopyOnWriteArrayList<VpnStackReport>`. On losing X with a surviving protocol Y, Y is
   republished as CONNECTED **from Y's own identity** (Y's protocol, session id and timestamps), never
   clobbered by X's teardown; if no survivor exists, X's teardown is published only when
   `current.protocol == X`, otherwise nothing is published. `updateState(...)` now folds through the ledger
   and applies `verdict.live` plus `verdict.publish?.let { _vpnState.value = it }`. The `sstpListener:280`
   egate is **deliberately left in place**: it is provably equivalent to the ledger's outcome and is pinned
   by a test, so removing it would add risk with no behavioural gain. Evidence: SOURCE CODE + TEST
   (`VpnStackLedgerTest`, 13 pure behavioural tests).

3. **PART A — A-4, a single armed-loss funnel.** The three armed-tunnel loss paths in
   `AutoModeController.startConnectedWatcher` each carried its own copy of the teardown sequence (log,
   cancel watcher, stop liveness probe, clear `onTunnelLost`, dispatch `onUnexpectedDisconnect`). All three
   now call one `private fun reportArmedTunnelLoss(reason: String, dropped: AutoModeCandidate?)`. The
   **detection latencies are deliberately NOT unified**: each path's measured latency is part of its
   behaviour, and collapsing them is a separate, riskier change. Only the reporting side is funnelled.
   Evidence: SOURCE CODE + TEST.

4. **PART A — A-5, `Disconnected` published after the decision.** `onUnexpectedDisconnect` published
   `AutoModeState.Disconnected` as its **first** statement, before the OQ-7 / OQ-6 / OQ-1 decision, so a UI
   observer could see a stale `Disconnected` racing the controller's own reasoning. The publish now happens
   **inside** the decision: in the OQ-7 stand-down branch, in the OQ-6 branch, and once before the OQ-3
   delay; the OQ-1 limit publishes `Error(ERROR_RECONNECT_LIMIT)` only. `onUnexpectedDisconnectForTests`'s
   KDoc was updated to match. Evidence: SOURCE CODE + TEST (3 behavioural ordered-trace tests).

5. **Tests added.** `VpnStackLedgerTest.kt` (new, 13 pure tests) covers the A-3 fold, including the
   previously-closed clobber-Y case and stale-protocol silence. `GlobalVpnTrackerTest` gains 5 tests: the
   A-2 bounds (`VPN_LOST_RECHECK_MS < 500L`, `>= 10L`) and named-constant source guard, plus A-3 guards
   (exactly one remaining `if (_vpnState.value.protocol ==` guard, the SoftEther/OpenVPN egates gone,
   `updateState` delegates to the ledger). `AutoModeControllerTest` gains 6 tests: 3 behavioural A-5
   ordered-trace tests over a `trace` recorder on `FakeAdapter`, and 3 A-4 source-shape tests, with
   `isTunnelBusy` / `reconnectInitialDelayMs` defaulted onto the `controller(...)` factory. Evidence: TEST.

6. **PART A verification status — PARTIAL, recorded explicitly.** JVM and build evidence is green and is
   carried forward from the implementation phase as **PRIOR REPORT**: `:app:testProDebugUnitTest`
   **671/0/0/1** (baseline 647, i.e. **+24** new tests); `:SoftEtherClient:test` **173/0/0/0**;
   `:app:assembleProDebug` SUCCESSFUL; APK SHA-256
   `713b1014e3785f35bbc69e74e05726ce10fb3e87c9a57ad66072734091186c97` reproduced. **Device verification is
   PARTIAL, not clean**: one ANR was observed during live testing and is **NOT REPRODUCED** (bullet 7).
   This revision re-ran none of it. Evidence: PRIOR REPORT + TEST.

7. **ANR finding — unattributed, NOT REPRODUCED, carried as an open item.** One ANR at **16:06:01**,
   followed by process death (pid 23115 → 25247), with main-thread degradation from 15:58:56 (Skipped
   35 → 1311 → 1821 frames) and the tunnel **staying healthy** throughout. The **negative assurance** —
   which is the load-bearing part of the attribution argument — is that the Axis A paths did not run:
   `GlobalVpnTracker` logged only "network available", so A-2's `onLost` never executed; no service
   state-change event was observed, so A-3's `updateState` fold never executed; and no `[AUTO]` lines were
   emitted, so A-4/A-5 never executed. Controls: a baseline-control run of the pre-change build was clean,
   and a re-test of the same PART A APK was clean. `/data/anr` is unreadable without root, so the blocked
   frame is **UNKNOWN**. The most probable remaining explanation is labelled **INFERENCE**: a pre-existing
   duplicate-`CONNECT` race combined with UI log-panel degradation. The finding is registered as
   **§12.5.1 row 16**; it is **not** a regression of PART A. Evidence: PHYSICAL DEVICE (logcat) +
   INFERENCE + **UNKNOWN** (blocked frame) + **NOT REPRODUCED**.

8. **PARTs B, C and D — already landed, no change made.** **PART B** (Axis B, `isTunnelUsable()` in Auto
   Mode routing) is a **NO-OP**: `VpnServerRepository.productionRouting()` already returns `preferVpn`,
   `tunnelUsable` and `hasBoundNetwork`, and `refresh()` already calls `CollectorRouting.choose()`, which
   ANDs all three — landed by `a9e16fd`. Its honest limitation is carried forward unchanged: nothing calls
   `noteRxAdvance()` from the data-forwarding path, so `isTunnelUsable()` can only ever report **false**
   today, which is the safe direction. **PART C** (Axis C: TUN `EIO` classification, bounded read loop, dead
   `MAX_PACKET_SIZE`) is a **NO-OP**: all three items are already in `TunTerminal.kt` with 6 passing
   `TunTerminalTest` tests — landed by `607c651`. **PART D** (RISK-A fail-closed release signing) is a
   **NO-OP**: `app/build.gradle:88–116` already registers a `gradle.taskGraph.whenReady` hook that throws a
   `GradleException` for any release `assemble|bundle|package…Release` task when the signing material is
   absent — landed by `c572050` (v1.0.7), and **the guard was verified firing**: `:app:assembleProRelease`
   exits **1** at `app/build.gradle:109` with "Release build blocked: release signing configuration is
   incomplete", while `:app:assembleProDebug` still succeeds. No file in PARTs B/C/D was touched in this
   revision. Evidence: SOURCE CODE + TEST + PRIOR REPORT.

9. **Discipline of this revision.** No `git add -A` / `git add .` — the five PART A files were staged by
   exact path, then the report, then the spec. No `--force`, no tag, no reset, no clean, no stash. No build,
   test or device command was run. No `D:\PMC\Documents\Android\SoftEthergai` path was touched. C5 was not
   executed. §22 is byte-unchanged. Evidence: SOURCE CODE (git history and command log).

- **Methodology lessons:** No new methodology lessons in v1.0.17.

#### 1.6.11 Line-Reference Corrections and Tag Decision (v1.0.18)

This subsection records the **CLOSE-OUT-AND-SPEC-HYGIENE-v1.0.18** revision — a documentation-only,
**device-free** phase that closes out two loose ends and makes one explicit decision. It ran **no build, no
test and no device command**, and performed **no tag operation**. The frozen sections §1.6.2–§1.6.10 are
**not modified**; the corrections below are recorded *here* precisely so that their text can stay frozen.
Each bullet carries its evidence label.

1. **Line-reference correction — `SSL_CTX_set_verify` is at `aes_wrapper.c:111`, not `:110`.**
   - *Historical citations:* §1.6.7 and §1.6.8 both cite `aes_wrapper.c:110` for the
     `SSL_CTX_set_verify(c, SSL_VERIFY_NONE, NULL)` call (the §1.6.7 citation and the §1.6.8 bullet on
     N-06/SEC-01).
   - *Current location:* the call is at `aes_wrapper.c:111`. Verified by direct inspection at HEAD: the
     `SSL_CTX_set_verify` occurrence is on line 111, and the surrounding context (the
     `SSL_MODE_AUTO_RETRY` line and the "Disable verification for VPN connections" comment) confirms the
     identity of the site. The drift is one line, caused by commit `b652198` (SEC-01 part 1, native
     peer-fingerprint exposure) adding one `#include` near the top of the file.
   - *Why it is not fixed in place:* §1.6.7 and §1.6.8 are frozen sections under this document's rule that
     historical session summaries are not retro-edited. §1.6.9 already cites `:111` and already notes that
     "the `:110` citations in §1.6.7/§1.6.8 predate commit `b652198`" — this bullet is the durable,
     non-invasive record of that correction, so a future reader who follows a `:110` citation from §1.6.7
     or §1.6.8 is not misled.
   - Evidence: SOURCE CODE.

2. **Tag `p2.4.10` — decision: leave it local-only.**
   - *State:* the tag exists **only locally** and has **never** been pushed. The remote knows exactly four
     tags (`p2.4.5`, `p2.4.7`, `p2.4.8`, `p2.4.9`); `p2.4.10` is absent from
     `git ls-remote --tags origin`, confirmed by a targeted query and by the full remote tag list.
   - *What it points at:* `071462816b1eef7e7f7459483fcd69909938cd11` — the first commit of the pushed
     chain, whose subject is `feat(ui): add DNS presets, app exception icons and Auto Mode cleanup`. That
     is a UI/cleanup commit, **not** a release cut.
   - *Decision:* **leave it local-only.** Rationale: it carries no semantic release value (it does not mark
     a release, and pushing it would add a remote tag with no clear meaning); the remote tag set is
     already complete and consistent without it; and keeping it local costs nothing.
   - *Operation performed:* **none.** No tag was created, moved, deleted or pushed in this phase.
   - Evidence: SOURCE CODE.

3. **`docs/reports/IMPLEMENT_P1_REMAINING_REPORT.md` (Phase 12) — committed.** The Phase 12 implementation report had
   been left untracked when that phase ended with a STOP condition. It is committed unmodified in this
   revision as a single commit (`0c5754e`); verified unmodified at 606 lines / 30,431 bytes, matching the
   Phase 12 record. Evidence: SOURCE CODE.

4. **Methodology lessons:** No new methodology lessons in v1.0.18.

#### 1.6.12 Documentation Corrections (v1.0.19)

This subsection records the **SPEC-HYGIENE-v1.0.19** revision — a documentation-only, device-free
phase that corrects three documentation defects surfaced by BLOCK 1 forensics and records the
corrected `p2.4.10` rationale established by BLOCK 2. It ran **no build, no test, and no device
command**, and it touched **no source, test, native, build, manifest or XML file**. The corrections
below are recorded *here* precisely so that the text of §1.6.2–§1.6.11 can stay frozen.

1. **§1.6.1 preamble is a v1.0.16-era snapshot — superseded.** The preamble still reads *"Fifty-three
   commits constitute the chain … `0714628` → `e6b9eef`"*. The authoritative count is the latest
   push-state note in §1.6.1 — **60 rows** at v1.0.18, and the v1.0.19 note appended above. No content
   change is made to §1.6.2–§1.6.11; this bullet marks the preamble as superseded rather than
   retro-editing it. Evidence: SOURCE CODE.

2. **The v1.0.18 push-state note's "two own commits" is wrong — there was one.** The note states:
   *"This revision's own two commits — the spec update to v1.0.18 and the `CLOSE_OUT-v1.0.18` report —
   are named by subject here."* In fact Phase 14 landed the spec update **and** the
   `docs/reports/CLOSE_OUT_v1.0.18_REPORT.md` report in a **single** commit — `abc8bd1`, two files
   (`PROJECT_SPECIFICATION.md` + `docs/reports/CLOSE_OUT_v1.0.18_REPORT.md`). Chain arithmetic is **unaffected**:
   the delta of 1 is explained by **one** own-commit, not two. The original note remains frozen; the
   wording correction lives here. Evidence: SOURCE CODE.

3. **`p2.4.10` rationale correction — it IS a release cut.** Earlier spec text (the §1.6.3 note and
   §1.6.11 bullet 2) and `docs/reports/DOCS_UPDATE_v1.0.17_REPORT.md` §7 all asserted that `p2.4.10` is *"not a
   release cut — a UI/cleanup commit"*. Forensic review of the **tag body** (not only the target
   commit's subject) refuted this. `p2.4.10` is a **genuine 2.4.10 release**:

   - Target commit `0714628` carries `versionCode 143` / `versionName "2.4.10"` in
     `app/build.gradle` (lines 64–65).
   - Its predecessor `p2.4.9^{commit}` (`5c13603`) carries `versionCode 142` / `versionName "2.4.9"`.
   - `git rev-list --count p2.4.9^{commit}..p2.4.10^{commit}` = **1** — the tag marks exactly the
     commit that bumped the version, which is textbook release-cut behaviour.
   - The annotated tag body (`c101380672e353321cd2c2bf820a49e8e71cba6a`) contains full 2.4.10 release
     notes and the explicit line **"Version: Bump to 2.4.10 (versionCode 143)."**, plus a validation
     line (`assembleProRelease`, `checkProReleaseAarMetadata`, `testProDebugUnitTest` — 111 tests).

   The commit *subject* (`feat(ui): add DNS presets, app exception icons and Auto Mode cleanup`) is
   simply the last feature commit before the cut; the release provenance lives on the tag, not in the
   commit message. Both §1.6.3 and §1.6.11 are frozen; this bullet records the superseding evidence.
   Evidence: SOURCE CODE.

   **Tag action taken this revision (v1.0.19): PUSHED to origin.** Remote unique tag set is now
   **5** (`p2.4.5`/`p2.4.7`/`p2.4.8`/`p2.4.9`/`p2.4.10`); local unchanged at 5. The published tag
   object is byte-identical to the local one. See the §1.6.1 v1.0.19 push-state note above.

4. **Note (no edit possible) — `docs/reports/DOCS_UPDATE_v1.0.17_REPORT.md` §7 stands.** That report is a committed
   historical artifact and **MUST NOT** be retro-edited under this document's frozen-report rule. Its
   incorrect `p2.4.10` rationale therefore stands as historical record; the correction lives in this
   §1.6.12 bullet 3. Evidence: SOURCE CODE + INFERENCE.

5. **Forward note — HEAD and `p2.4.10` share a version identity.** At the time of writing, HEAD
   (`abc8bd1`) carries `versionName "2.4.10"` / `versionCode 143` — the **same** identity as tag
   `p2.4.10` — while sitting **60 commits** ahead of it. A future release phase **SHOULD** bump
   `versionCode`/`versionName` past 2.4.10 before the next cut, so that the tag and HEAD stop sharing
   an identity. Related and unchanged: the 2.4.10 release artifact was distributed **debug-signed**
   (RISK-A, §12.4), because the fail-closed release-signing guard (`c572050`, v1.0.7) did not yet
   exist at `0714628`. Publishing the tag does not publish an APK. Evidence: SOURCE CODE.

6. **Methodology lessons:** No new methodology lessons in v1.0.19.

---

#### 1.6.13 Z1-IMPL-B — Emergency Source Foundation (v1.0.20)

This subsection records the **Z1-IMPL-B** revision — Roadmap §12.5.2 **item B only**. It is a real
implementation phase (source + test + build) and it is **device-free**: no device command was run and
no device verification was performed. What landed, what deliberately did not, and the evidence for
each claim are listed below.

1. **Roadmap §12.5.2 item B landed.** Scope: the emergency-source configuration model, the Room
   entity + DAO, the 5 → 6 migration, and a Settings entry point. New files:
   `data/remote/EmergencySourceConfigEntity.kt` (`@Entity(tableName = "emergency_source_config")`,
   autoGenerate id; fields url, label, enabled, createdAt, updatedAt; `enabled` carries
   `@ColumnInfo(defaultValue = "1")` so the SQL default matches the migration DDL),
   `data/remote/EmergencySourceConfigDao.kt` (observeAll / listEnabled / getByUrl / upsert /
   deleteByUrl) and `data/remote/EmergencySourceConfig.kt` (domain model with `fromEntity` /
   `toEntity`). `SourceKind` gained `EMERGENCY_CSV` and `MANUAL` (appended at the end, ordinals
   preserved, no existing entry touched). `AppDatabase` is at **version 6** with the entity
   registered and `emergencySourceConfigDao()` declared. `App.kt` registers `MIGRATION_5_6` and
   exposes the new DAO next to the two existing ones — both changes there were **authorized after
   discovery** showed that the Room builder and the DAO registry live in that file and nowhere else.
   `ConnectionListViewModel` exposes `emergencySources: StateFlow<List<EmergencySourceConfig>>` plus
   `addEmergencySource` / `removeEmergencySource` / `toggleEmergencySource`.
   `SettingsScreen` gained an "Emergency Server Sources" section: URL + optional label inputs, a
   per-row enable switch and remove affordance, and a validity gate that reuses `SourceUrl.parse`
   so a malformed URL can never reach the database. **Retrieval is not wired — nothing fetches.**
   Evidence: SOURCE CODE.

2. **Roadmap §12.5.2 item A was already implemented — NO-OP this phase.** The dynamic network-path
   selection for server-list retrieval landed in PART B (`a9e16fd`) through
   `VpnServerRepository`'s three-input decision and `HttpFetcher`'s
   `boundToSocketFactory`/`boundToNetwork`. No file was changed for it here; the entry is recorded so
   item A is not double-counted. Evidence: SOURCE CODE + PRIOR REPORT.

3. **§11.9.3 updated — arch doc §10 approved as the design basis.** The historical
   "DEFERRED / NOT YET DEFINED" wording is retained verbatim and annotated as superseded; the
   approval note names the CSV parser as a later phase (item C). §11.9.1 and §11.9.2 are untouched.
   Evidence: SOURCE CODE.

4. **Everything else in §12.5.2 remains NOT IMPLEMENTED.** Items C (CSV parser), D (manual server
   entry), E (server-list UI entry point), F (text/file/share import), G (safe merge and
   persistence) and H (tests), plus Cumulative Server Retention (arch doc §9). `SourceKind.MANUAL`
   exists as a type only — no UI, parser or merge consumes it. Evidence: SOURCE CODE.

5. **JVM tests.** `:app:testProDebugUnitTest` = **678 / 0 / 0 / 1** (baseline 671 / 0 / 0 / 1;
   **+7**, all from the new `Migration5to6Test`, zero failures and errors, ignored count unchanged).
   `:SoftEtherClient:test` = **173 / 0 / 0 / 0** — identical to baseline. Evidence: TEST.

6. **Build.** `CI=true ./gradlew :app:assembleProDebug --rerun-tasks --no-build-cache` SUCCESSFUL
   (114 tasks); APK SHA-256
   `9ba16f9ffddb17b8d7e46c36e0e647ca8e2ebf6f08bbd45d14031b7621a5094c` (63,678,518 bytes). The prior
   `713b1014…` differs, as expected for new code. Evidence: TEST.

7. **The Room migration is verified on the JVM only.** `Migration5to6Test` exercises `MIGRATION_5_6`
   against a recording `SupportSQLiteDatabase` (the established `SstpMigration4To5Test` pattern) and
   compares the DDL with the KSP-generated `app/schemas/vn.unlimit.vpngate.db.AppDatabase/6.json`;
   a real 5 → 6 upgrade on a physical device remains **UNKNOWN** until a device reconnects.
   Evidence: TEST + UNKNOWN.

8. **Device verification: NONE.** The device is DETACHED; no `adb` command was issued at any point.
   Evidence: PHYSICAL DEVICE (not performed).

9. **Chain arithmetic and methodology lessons.** `git log --oneline 0714628^..HEAD | wc -l` = **62**
   at the start of this revision against a §1.6.1 table of **60** rows (delta 2: the v1.0.18 docs
   commit `abc8bd1` and the v1.0.19 spec commit `8ab9381`, both still unlisted); this revision adds
   three commits, so the chain reads **65** through its docs commit while the table stays at 60 until
   a future revision lists them. No new methodology lessons. Evidence: SOURCE CODE.


#### 1.6.14 Z1-IMPL-C — Emergency CSV Parser + Wire-In (v1.0.21)

This subsection records the **Z1-IMPL-C** revision — Roadmap §12.5.2 **item C**. It is a real
implementation phase (source + test + build) and it is **device-free**: no device command was run and
no device verification was performed. What landed, what deliberately did not, and the evidence for
each claim are listed below.

1. **Roadmap §12.5.2 item C landed.** Scope: the emergency CSV parser, the HTTP boundary for
   user-configured sources, and an opt-in wire-in into the updater. New files:
   `parser/EmergencyCsvParser.kt` (the §10 schema, 29 columns) and
   `data/remote/EmergencySourceFetcher.kt` (fetch-only boundary), plus two JVM test files.
   `SourceIdentity.kt` gained `EMERGENCY_TAG = "emergency"` and now routes `SourceKind.EMERGENCY_CSV`
   to it instead of the mirror branch — before this an emergency source was reported as
   `mirror_unnumbered` and sat in the mirror provenance group. `ServerListUpdater.kt` gained one
   injected parameter, `emergencyFetch`, defaulted to `{ null }`. `ServerListUpdates.kt` builds the
   production lambda from the enabled emergency sources. Evidence: SOURCE CODE.

2. **The parser implements `SERVER_SOURCE_ARCHITECTURE.md` §10 as approved.** Records are ordinary
   `VpnServerRecord` values built with the existing `VpnRecords` helpers — there is no parallel
   record type (§3.12). Parsing is **header-driven rather than positional**, because §10.9 Q2 leaves
   column order open, and only the four required columns (`HostName`, `IP`, `schema_version`,
   `SourceTag`) are demanded. `schema_version` is fail-closed: any value other than `1.0` rejects the
   whole body, and **every** row is checked, so a single non-conforming row cannot be parsed on a
   guess. §18 unit normalisation is byte-identical to the canonical parser (bps→Mbps, ms→days,
   bytes→GB). Evidence: SOURCE CODE + TEST.

3. **Partial import is the normal good outcome, and the two §10.4 outcomes are kept distinct.** A
   record failing a *required* rule (empty `HostName`, invalid IPv4) is rejected and counted; a record
   failing an *optional-decode* rule is retained with the affected capability marked unsupported
   (Base64 that does not decode keeps the row and drops OpenVPN). The result type carries
   `rejectedCount` and a per-issue reason map (§3.11). A file with a valid header and zero surviving
   rows is **Malformed**, not an empty import — §3.10 requires at least one record to pass
   required-field validation. Evidence: SOURCE CODE + TEST.

4. **`SE_UDP_Port` is never turned into a transport on its own.** The RUDP warning at §10.3 (verbatim
   from `RUDP_IMPLEMENTATION_PLAN.md`) is honoured literally: SoftEther UDP support is read only from
   `SE_UDP_Supported`, and a published `SE_UDP_Port` without it is recorded as a note and written
   nowhere. `0` is accepted as the documented "not present" token and is never reported as a defect.
   No port is ever fabricated; `OpenVPN_UDP_Port = 0` leaves the UDP port `null` rather than guessing
   1194. Evidence: SOURCE CODE + TEST.

5. **Identity and dedup are the live path's, not a second implementation.** The parser delegates to
   `VpnServerMerger` (§10.5: IPv4 `IP` primary key, normalised `HostName` secondary key,
   more-complete-record-wins merge), so `servers.csv` collapses duplicates exactly as the collector
   does. The one rule the record model has no opinion about — a merged record's file-level
   `SourceTag` becoming `merged` — is applied after the merge and is carried on the record under an
   `emergency` provenance map, so the §33 debug dump can show it. Evidence: SOURCE CODE + TEST.

6. **The fetcher reuses the one HTTP stack and introduces no second one.** `EmergencySourceFetcher`
   injects the same `HttpFetcher`/`collectorClientBuilder()` the canonical collector uses, so the
   `ResilientDns` provider, the 10 s connect / 15 s read pair and manual per-hop redirect handling are
   inherited rather than duplicated (§2.6: "Emergency: existing pair; retry each enabled source
   once"). It follows no redirect outside the source's own authority, re-checks the reported final URL
   against that authority, refuses a body over 1 MiB **rather than truncating it** (a silently cut CSV
   would parse as a valid, smaller dataset), and never parses and never persists. Known limitation,
   recorded: `HttpFetcher` returns a fully decoded `String`, so the 1 MiB cap bounds parsing and
   retention, not the bytes on the wire; the download itself is bounded by the shared client's read
   timeout. Evidence: SOURCE CODE.

7. **The wire-in is opt-in and provably cannot change zero-source behaviour.** `emergencyFetch`
   defaults to `{ null }`, is tried **only** when the primary fetch returned `null` (that fetch
   already covers the open and VPN rungs and the repository's snapshot / stored / bundled-seed
   fallbacks, so this really is §2.5 path 3), and a `null` or empty result falls through to the
   byte-identical `Outcome.Preserved(NETWORK_FAILURE, …)` that existed before. A dataset it does
   supply goes through the **identical** acceptance sequence — `isUsable`, Room-identity dedup, the
   OpenVPN-profile guard, `DatasetIntegrity` and the single atomic replace — so nothing is written
   outside the existing `Store` boundary. `DatasetIntegrity` is what refuses an emergency file too
   small to be a server list when a healthy list is already stored (§2.7), which is the reason an
   emergency dataset is not given a special write path. Evidence: SOURCE CODE + TEST.

8. **JVM tests.** `:app:testProDebugUnitTest` = **724 / 0 / 0 / 1** (baseline 678 / 0 / 0 / 1;
   **+46** from the two new test files, zero failures and errors, ignored count unchanged). The 678
   pre-existing tests — including every `ServerListUpdater` case — pass unchanged, which is the
   regression evidence that the wire-in changes nothing when no emergency source is configured.
   `:SoftEtherClient:test` = **173 / 0 / 0 / 0** — identical to baseline. Evidence: TEST.

9. **Build and device state.** `CI=true ./gradlew :app:assembleProDebug` SUCCESSFUL; APK SHA-256
   `b85a7aba4ecfdea4a237b2ffae77be27241638a62d92e8313d6e6c292d53b6fd` (63,821,179 bytes), which
   differs from the prior `9ba16f9f…` as expected for new code. **No device verification was
   performed** — the device is DETACHED, so the full path (a configured URL, a live fetch, a stored
   dataset, migration 5→6 on a real database) remains **UNKNOWN**. `git log --oneline
   0714628^..HEAD | wc -l` = **65** at the start of this revision; this revision adds three commits,
   so the chain reads **68** through its docs commit while the §1.6.1 table stays at 60. No new
   methodology lessons. Evidence: TEST + PHYSICAL DEVICE (not performed) + SOURCE CODE.


#### 1.6.15 Z1-IMPL-D — Manual Server Entry + MANUAL-Preservation (v1.0.22)

This subsection records the **Z1-IMPL-D** revision — Roadmap §12.5.2 **item D**. It is a real
implementation phase (source + test + build) and it is **device-free**: no device command was run and
no device verification was performed.

0. **The field set comes from the requirement, not from invention.** Spec §11.11 defines the
   *rules* for manual servers and explicitly declines to define a schema ("Do not design or
   implement a new database schema here"); `SERVER_SOURCE_ARCHITECTURE.md` §4.1 fixes the minimum
   as "a single server record (host/IP + at least one protocol+port)". The landed model is exactly
   that plus what the feature needs to be usable — an address, one protocol with its port, a label,
   notes, and an enable switch. Nothing else was added. Evidence: SOURCE CODE + PRIOR REPORT.

1. **Model, entity, DAO and migration 6→7 landed.** New `data/remote/ManualServer.kt` (domain),
   `ManualServerEntity.kt` (`@Entity(tableName = "manual_server")` with a **unique** index on
   `(host, port, protocol)`), `ManualServerDao.kt` (`observeAll` / `listAll` / `listEnabled` /
   `findByKey` / `upsert` / `deleteById`, mirroring `EmergencySourceConfigDao`), and
   `repository/ManualServerStore.kt` (the injected-DAO wrapper; no network). `AppDatabase.kt`
   registers the entity, moves to **version 7** and adds `MIGRATION_6_7`, which is two additive
   statements — the table and that unique index — with no `DROP`/`DELETE`/`TRUNCATE` and no
   recreation of `VPNGateItem`, `excluded_apps` or `emergency_source_config`. `App.kt` registers
   the migration and exposes `manualServerDao`. `app/schemas/…/7.json` is the KSP export and is
   compared against the migration DDL by `Migration6to7Test`. Evidence: SOURCE CODE + TEST.

2. **MANUAL is the lowest merge authority, and that is the frozen policy rather than a new one.**
   `VpnServerMerger.SOURCE_PRIORITY` gains `"manual" to 30`, completing the table as
   api 0 > html 10 > (mirror 20) > manual 30 — `SERVER_SOURCE_ARCHITECTURE.md` §3.3 ("canonical
   (api/html) always wins over emergency over manual"), §4.2 ("never promoted above canonical"),
   spec §11.11. Preserved as authoritative, with the reasoning recorded in the code. Evidence:
   SOURCE CODE.

3. **Preservation and precedence are different promises, and both are tested.**
   *Preservation* is absolute: a manual record is never deleted. `mergeRecords` dedupes by identity
   and merges and has no path that drops a record for being low-priority, and the production write
   boundary re-emits the user's rows on every accepted write, so a manual server the feeds do not
   mention survives untouched and keeps every one of its fields. *Precedence* applies only to a
   value both sides claim: when a manual entry and a collected record describe the same server and
   disagree on a scalar, the collected value wins and the disagreement is written into the record's
   `conflicts` list (§38) — a manual record is never *silently* overwritten (§11.11). Evidence:
   SOURCE CODE + TEST.

4. **The merge happens at the single write, which is why it holds for every rung.**
   `ServerListUpdates.withManualRows` runs inside `Store.replaceAll`, so the manual rows are
   re-emitted whichever rung supplied the network dataset — the primary fetch, the repository's
   snapshot/stored/bundled-seed fallbacks, or the emergency rung added in item C. An empty manual
   list returns the caller's own list **by identity**, so a caller with no manual servers observes
   byte-identical behaviour. When a host is already served by the network the manual row is not
   appended as a duplicate (canonical outranks manual); the entry itself is untouched in
   `manual_server` and is re-emitted on the next refresh in which the feeds stop listing that host.
   Evidence: SOURCE CODE + TEST.

5. **UI and ViewModel landed.** `ConnectionListViewModel` exposes
   `manualServers: StateFlow<List<ManualServer>>` plus `addManualServer` / `removeManualServer` /
   `toggleManualServer`; `SettingsScreen` gains a "Manual Servers" section (list with enable toggle
   and remove, plus an add form: IPv4 address, port, protocol picker, optional label and notes)
   reusing the existing components and the `manual_server_` string prefix. Evidence: SOURCE CODE.

6. **A manual address must be an IPv4 literal, and that is a boundary rule, not an oversight.**
   The record layer keys a server on a valid IPv4 — `VpnServerMerger.normalizeServer` rejects
   anything else and `VpnConnectionMapper.toConnection` returns null without one — so a bare DNS
   name would be accepted by the form and then silently dropped by the merge. Refusing it at the
   `ManualServerStore.add` boundary keeps the Settings UI honest; DNS resolution for manual entries
   is a later phase's problem. Evidence: INFERENCE, from the two rejection points above.

7. **JVM tests.** `:app` = **747 / 0 / 0 / 1** against a baseline of **724 / 0 / 0 / 1**
   (**+23**: 16 in `ManualServerPreservationTest`, 7 in `Migration6to7Test`), zero failures and
   errors, ignored count unchanged. All 724 pre-existing tests pass unchanged, which is the
   regression evidence that the merge changes nothing when no manual server is configured.
   `:SoftEtherClient:test` = **173 / 0 / 0 / 0** — identical to baseline. Evidence: TEST.

8. **Build and device state.** `CI=true ./gradlew :app:assembleProDebug` SUCCESSFUL; APK SHA-256
   `f1d768c9aa1b56fb29ddaea0d773e0f8dfdf0841790dd8bf0e4bd0f8c19a4e33` (65,978,641 bytes), which
   differs from the prior `b85a7aba…` as expected for new code. **No device verification was
   performed** — the device is DETACHED, so the full path (adding a manual server, a live refresh
   that must not drop it, migration 6→7 on a real database) remains **UNKNOWN**. `git log
   --oneline 0714628^..HEAD | wc -l` = **68** at the start of this revision; this revision adds
   four commits, so the chain reads **72** through its docs commit while the §1.6.1 table stays at
   60. No new methodology lessons. Evidence: TEST + PHYSICAL DEVICE (not performed) + SOURCE CODE.

9. **What did NOT land, and the two limitations that were accepted deliberately.**
   (a) The full `DatasetIntegrity` outcomes (`ACCEPT_NEW` / `MERGE_WITH_EXISTING` /
   `REJECT_DEGRADED`) are **not** implemented — item G remaining. Evidence: SOURCE CODE.
   (b) Cumulative Server Retention (arch doc §9) is **not** implemented: an update still replaces
   rather than merges. Evidence: SOURCE CODE. (c) `SourceIdentity.kt` is **not** in this phase's
   authorized list, so `SourceKind.MANUAL` still falls through to `mirror_unnumbered` for its
   `sourceTag`; the merger's manual authority is keyed on the literal `"manual"` instead, which is
   correct for every record the merger can actually see in this phase. Evidence: SOURCE CODE.
   (d) Two manual entries on the *same* IPv4 (different ports or protocols) both persist, but the
   stored table's primary key is the host name alone, so `VPNGateItem.dedupeForStorage` collapses
   them to one row by its existing Stage 2F-A "most connectable, then first inserted" order. Both
   entries remain in `manual_server` and in Settings; only one can occupy a row. Modifying that
   ordering is out of this phase's authorized files. Evidence: SOURCE CODE. (e) An OpenVPN manual
   entry stores its port but no profile, and the app dials OpenVPN from `openVpnConfigData`, so
   such a row is not connectable until a profile is supplied — which is items E/F. Evidence:
   INFERENCE, from `VPNGateItem.connectOptions`.


#### 1.6.16 Z1-IMPL-G — Dataset-Integrity Outcomes + MANUAL Provenance Fix (v1.0.23)

This subsection records the **Z1-IMPL-G** revision — Roadmap §12.5.2 **item G**. It is a real
implementation phase (source + test + build) and it is **device-free**: no device command was run and
no device verification was performed.

0. **The citation correction, recorded before anything else.** The phase brief named
   `SERVER_SOURCE_ARCHITECTURE.md` **§3.12** as the home of the three outcomes
   (`ACCEPT_NEW` / `MERGE_WITH_EXISTING` / `REJECT_DEGRADED`). §3.12 does not define them; the
   vocabulary lives in **§9.6** and **§10.8**, and §9.1 is the section that fixes what the first two
   *mean*. The brief's own stop condition ("arch doc §3.12 conflicts with the STEP 4 decision logic →
   STOP; do NOT choose silently") was triggered by two further contradictions (bullets 5 and 6
   below), so the whole reading was put to the operator instead of being resolved by assumption.
   Evidence: SOURCE CODE (arch-doc read) + PRIOR REPORT (operator decision).

1. **The MANUAL provenance debt is paid.** `SourceIdentity.sourceTag` reached the mirror kinds
   through an `else -> mirrorId ?: UNNUMBERED_MIRROR_TAG` branch, and `SourceKind.MANUAL` has no
   mirror id, so a user's own entry was reported as `mirror_unnumbered` for two revisions — recorded
   as limitation (c) of §1.6.15 bullet 9, because that file was outside item D's authorized set. The
   `when` is now **exhaustive with no `else`**, listing every `SourceKind` by name, and
   `MANUAL_TAG = "manual"` is a companion constant. The absence of an `else` is a compile-time
   guard, not a style choice: adding a kind to the enum without giving it a tag used to compile
   clean and mislabel records at runtime, and now fails to build instead. Evidence: SOURCE CODE +
   TEST (the former defect), INFERENCE (the future-kind guarantee is a compile-time property, not an
   observed one).

2. **A mirror tag was a real authority defect, not a cosmetic one.** `Provenance.sourceGroup`
   collapses `mirror_<n>` into the single group `mirror`, which is **not** in
   `VpnServerMerger.SOURCE_PRIORITY` and therefore falls through to the default **20**;
   `mirror_unnumbered` does not match `^mirror_\d+$`, so it stays a group of its own — also absent
   from the table, also **20**. Either spelling handed a manual record mirror-level authority and
   hid its provenance, which is why `VpnServerMerger`'s `"manual" to 30` entry (landed in item D and
   documented as "inert until a manual record reaches this object") was dead code rather than a live
   policy: no record labelled `manual` was ever produced. With the fix, a manual record is grouped
   as `manual` and resolves to **30**. Evidence: SOURCE CODE + TEST.

3. **Every other kind's tag is pinned, and the two groups are provably disjoint.**
   `ManualProvenanceTest` asserts the baseline tag of all seven other kinds (`html`, `api`,
   `emergency`, `mirror_7` and `mirror_unnumbered` for each of `BOOTSTRAP_MIRROR`,
   `DISCOVERED_MIRROR` and `CACHED_MIRROR`) so that adding the manual branch cannot silently
   re-route one of them, and asserts `MIRROR_TAG_PREFIX + (index + 1)` never equals `MANUAL_TAG` for
   a range of indices — ids are prefixed at the point of assignment
   (`VpnGateSourceCandidates.MIRROR_TAG_PREFIX`), so a mirror can never share the manual provenance
   group. A manual identity carrying a *stray* mirror id (reachable through the public
   `withMirrorId` copy) still reads `manual`. Evidence: SOURCE CODE + TEST.

4. **The three outcomes landed as a classification, not as a decision.** `DatasetOutcome`
   (`ACCEPT_NEW` / `MERGE_WITH_EXISTING` / `REJECT_DEGRADED`) and
   `outcome(verdict, stored): DatasetOutcome` are new; `DatasetIntegrity.evaluate`'s **signature and
   all seven thresholds are byte-for-byte unchanged**, so no decision moved. The rule is a pure
   function of data `evaluate` already produced: a refused verdict is `REJECT_DEGRADED`; an accepted
   one is `ACCEPT_NEW` when `stored.size < MIN_ACCEPTABLE_DATASET_SIZE` (nothing to compare
   against) and `MERGE_WITH_EXISTING` otherwise. Evidence: SOURCE CODE + TEST.

5. **`REJECT_DEGRADED` is implemented in substance; `ACCEPT_NEW` and `MERGE_WITH_EXISTING` are
   names only in this phase.** The first is exactly `verdict.accepted == false`, which is what
   `ServerListUpdater` already acted on. The other two have no add-only behaviour behind them,
   because arch doc §9.1 defines them as cumulative **addition** — "the database **grows** across
   updates rather than being **replaced**" — and Cumulative Server Retention is **not** implemented
   and is outside this phase's authorized file set. Saying otherwise would be a claim the code does
   not make, so the KDoc states the limitation plainly and §11.11 keeps recording the gap. Evidence:
   SOURCE CODE.

6. **The brief's decision table contradicts the arch doc on two rows and was not adopted.** The
   brief classifies a **strict subset** and a **disjoint** candidate as `REJECT_DEGRADED`. Arch doc
   §9.1 says the opposite for a shrunken response: "Do not lose healthy servers when a source
   returns a smaller set. A shrunken response is treated as `MERGE_WITH_EXISTING` (§9.6), **never**
   as a signal to prune previously-seen healthy records", and §9.6's own closing sentence is that
   the outcomes "layer on top of the existing `DatasetIntegrity` threshold logic **rather than
   replacing it**". The operator chose the arch-doc reading, so a viable-but-smaller dataset (25 of
   40, above the 0.5 size floor) and a viable-but-disjoint one both classify
   `MERGE_WITH_EXISTING`, and the existing thresholds decide acceptance exactly as before — a
   dataset that collapses to half the stored size or below the ten-row floor is still
   `REJECT_DEGRADED`. Evidence: PRIOR REPORT (operator decision) + SOURCE CODE + TEST.

7. **The wire-in records the classification; it does not branch on it.** `ServerListUpdates`'
   `replaceAll` re-runs `DatasetIntegrity.evaluate` (pure and deterministic, so it reproduces the
   updater's verdict) and logs the outcome, both sizes and `verdict.detail`, then performs the write
   it always performed. The component that *owns* the decision, `ServerListUpdater.kt`, is **not**
   in this phase's authorized file set, which is precisely why the write boundary logs rather than
   branches: any future divergence between the two verdicts becomes visible in the log instead of
   silently changing behaviour, and the classified value is never persisted. Evidence: SOURCE CODE.

8. **JVM tests.** `:app` = **768 / 0 / 0 / 1** against a baseline of **747 / 0 / 0 / 1**
   (**+21**: 11 in `DatasetIntegrityOutcomeTest`, 10 in `ManualProvenanceTest`), zero failures and
   errors, ignored count unchanged. All 747 pre-existing tests pass unchanged — including every
   `ServerListUpdater` case, which is the regression evidence that the write path is untouched.
   `:SoftEtherClient:test` = **173 / 0 / 0 / 0**, identical to baseline. Two tests pin the
   classification rule itself rather than individual cases: an outcome never contradicts its verdict,
   and `ACCEPT_NEW` is exactly the no-comparable-baseline case at the 9/10 stored-row boundary.
   Evidence: TEST.

9. **Build, device state, and what did NOT land.** `CI=true ./gradlew :app:assembleProDebug`
   SUCCESSFUL; APK SHA-256 `f42f78e87f3dce51f7c800a9b109077330e5f08adbd713f7762c3433729e4cba`
   (63,738,546 bytes), which differs from item D's `f1d768c9…` (65,978,641 bytes) as expected for
   changed code. **No device verification was performed** — the device is DETACHED. Not landed:
   (a) Cumulative Server Retention (arch doc §9), still the structural gap and the reason the
   outcome names carry no add-only behaviour; (b) any change in `ServerListUpdater.kt`, the
   component that decides; (c) the two item-D limitations that interact with this one — two manual
   entries on one IPv4 collapse to one stored row, and an OpenVPN manual entry is not connectable
   without a profile; (d) a row-level policy for those two entries, which remains proposed-only.
   Evidence: SOURCE CODE + TEST (build) + PHYSICAL DEVICE (not performed).


#### 1.6.17 Emergency-Sources Folder + Device Migration Verified (v1.0.24)

This subsection records the **EMERGENCY-SOURCES-CSV-v1.0.24** revision. It is **device-free and
build-free**: no device command, no build and no test run was performed (see bullet 5 for the one
brief-internal contradiction this created and how it was resolved).

1. **The folder and its purpose.** `emergency-sources/` is added to the public repository, hosting
   `servers.csv` (arch doc §10 schema) and `README.md`. The purpose is narrow: give the
   emergency-source path (§11.9, roadmap §12.5.2 item C, landed v1.0.21) a **real, URL-accessible
   target** so it can be exercised end-to-end. Every earlier attempt pointed at
   `https://example.invalid/test.csv`, which does not resolve, so the fetch could never succeed and
   the feature was never actually proven — the app was not at fault for refusing it. Evidence:
   SOURCE CODE + PRIOR REPORT.

2. **The CSV content is real, not invented — a deliberate correction to the brief.** The brief
   described "3 example rows with historical VPN Gate IPs" and "port data EXAMPLE/plausible, not
   fetched live". Writing plausible-but-unverified ports would have contradicted arch doc §10.3
   ("a port is never fabricated"), and labelling fetched data as "not fetched live" would have put a
   false statement in the README. The rows were therefore captured on **2026-10-17** from the two
   public sources the design document names: `https://www.vpngate.net/api/iphone/` for the base 15
   columns and `https://www.vpngate.net/en/` for the extended columns. All 29 §10.2 columns are
   populated, in the documented order, `schema_version="1.0"` on every row. Because each row
   combines both sources, every row carries `SourceTag="merged"` — the §10.6 meaning of that token.
   Where the HTML states support but publishes no port number (`SE_UDP_Port`) or publishes only an
   SSTP hostname (`SSTP_Port`), the value is `0`, exactly as §10.3 requires. Evidence: SOURCE CODE
   (the file) + the live capture it was generated from.

3. **Device migration 5 → 6 → 7 VERIFIED on `5585fecc`.** Prior state: `user_version=5`, 103
   `VPNGateItem` rows, 2 `excluded_apps` rows, `manual_server` and `emergency_source_config`
   **absent**. A backup was taken base64-first (SHA-256 `8bf693b6…`, 1,048,576 bytes exact) and the
   `MIGRATION_5_6` / `MIGRATION_6_7` DDL was pre-flighted host-side against a copy of it, matching
   the KSP schema exports `6.json` / `7.json`. After installing the v1.0.23 APK over it:
   `user_version=7`, every pre-existing table preserved **byte-for-byte** (per-table row-for-row
   SHA-256 identity for `VPNGateItem`, `excluded_apps`, `android_metadata`), both new tables present
   with 0 rows, `integrity_check=ok`. `room_master_table.identity_hash` changed
   `32a05726…` → `1ff61fa3…` as required — it is the schema marker, not user data. A rollback APK
   was built from a genuinely pre-B commit (`a3a6434`) and was **not needed**. Device model
   `2201117TG` / `spes_global`; the Android release level was not re-read in this phase and remains
   PRIOR REPORT. Evidence: PHYSICAL DEVICE + TEST (host-side pre-flight).

4. **The emergency-source and manual-server UI tests are NOT YET confirmed end-to-end.** A read-only
   verification block found `manual_server` and `emergency_source_config` both at **0 rows**, and
   `sqlite_sequence` **empty**. That last fact is decisive rather than suggestive: both tables are
   `INTEGER PRIMARY KEY AUTOINCREMENT`, so the first insert — even one later deleted — leaves a
   permanent watermark row. An empty `sqlite_sequence` therefore proves the insert code **never
   executed**, which also rules out "inserted then deleted" and "inserted then rolled back". The
   database was byte-identical (SHA-256 `fc2c5537…`) to its post-migration capture across a full
   app session, SharedPreferences held nothing related, and the app — launched programmatically and
   idle on its home screen — had logged a single line since start. **No product defect is implied:**
   the whole path was verified in source (`SettingsScreen.kt` → `ConnectionListViewModel` →
   `ManualServerStore` → DAO, both DAOs assigned in `App.onCreate`, `NavRoutes.SETTINGS` reachable,
   no build-flag gating), and every validation gate accepts the intended inputs
   (`validIp("1.1.1.1")`, `sstp` ∈ `VpnProtocolNames.ALL`, `SourceUrl.parse` on a well-formed URL),
   so a write **would** have succeeded. The test simply did not complete. **UNKNOWN** whether the
   operator's taps reached the app; it is to be re-tested against the new `raw.githubusercontent.com`
   URL. Evidence: PHYSICAL DEVICE + SOURCE CODE + UNKNOWN.

5. **No JVM test run this revision — and why that is the recorded truth, not an omission.** The
   brief's SCOPE line and its FORBIDDEN list both forbid "any build/test run", while its STEP 5
   mandates `:app:testProDebugUnitTest` and `:SoftEtherClient:test`. The contradiction was put to
   the operator, who chose to honour the prohibition. This revision touches **no** code, test,
   manifest or XML — only a new untracked data folder and markdown — so the baseline cannot have
   moved. The figures are therefore carried forward as **PRIOR REPORT**, not asserted as TEST:
   `:app` = **768 / 0 / 0 / 1** and `:SoftEtherClient:test` = **173 / 0 / 0 / 0** at v1.0.23
   (§1.6.16 bullet 8). Evidence: PRIOR REPORT (explicitly **not** re-run).

6. **No new methodology lessons.** Chain arithmetic: this revision adds two commits — `data(sources)`
   and `docs` — so the §1.6.1 chain grows by 2. As always, a spec file cannot name its own commit;
   the header HEAD field points at the last pre-docs commit, `02ce791`, and this revision's own docs
   commit is named by subject here and listed by hash in a future revision. §22 is untouched and
   byte-identical (99 lines / 6,504 bytes). No tag operation of any kind was performed.


#### 1.6.18 Manual-Server Protocol Picker Overflow Fixed; Manual-Row Visibility Mechanism Proven (v1.0.25)

This subsection records the **MANUAL-UI-INVESTIGATE-AND-FIX-v1.0.25** revision. Two operator-reported
symptoms were investigated; **one was fixed and device-verified, the other was deferred by explicit
operator decision while its mechanism was established as fact**. No build configuration, manifest or
resource file was touched; `strings.xml` was read but not modified.

1. **Defect A (SSTP "missing" from the picker) — CONFIRMED as a layout overflow, not a missing
   option.** The protocol picker in `SettingsScreen.kt` was a plain
   `Row(horizontalArrangement = Arrangement.spacedBy(8.dp))` containing four `FilterChip`s, with no
   wrapping and no horizontal scroll. At this device's width (1080 px at 2.75 density = 392.7 dp) the
   four chips cannot fit: the measured chip widths are **265 / 264 / 298 / 261 px** with **22 px**
   gaps, totalling **1154 px** against a content width of 1080 − 2×88 = **904 px**. The fourth chip
   was therefore laid out from x≈981 to x≈1242 — starting inside the viewport and ending **162 px
   past the right edge** — and was clipped and unreachable. SSTP was never absent from the list; it
   was the only chip that could not be seen. Evidence: SOURCE CODE (the `Row`) + PHYSICAL DEVICE
   (measured chip bounds) + INFERENCE (the pre-fix coordinates are arithmetic from the measured
   widths, since the pre-fix build was not re-installed to observe them directly).

2. **The default selection made the bug read as "SSTP does not exist".** `manualProtocolInput` is
   initialised to `VpnProtocolNames.SSTP`, so the *selected* chip was the invisible one: the user saw
   three unselected protocols and no indication of the current value. This is what turned a clipped
   control into a report of a missing protocol, and it is the reason the symptom was phrased as
   absence rather than as a cut-off edge. Evidence: SOURCE CODE.

3. **The fix is deliberately minimal — three hunks in one authorized file.** (i) the `Row` becomes
   `FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp))`; (ii) the inline `listOf(...)` of
   four protocols is hoisted to a named `internal val MANUAL_SERVER_PROTOCOL_CHOICES`; (iii) one
   import is added. `FlowRow`'s non-`overflow` overload is **stable** in compose
   foundation-layout 1.12.0 — only its internal implementation carries `@ExperimentalLayoutApi` — so
   **no `@OptIn` was required at the call site**; this was verified per-declaration in `FlowLayout.kt`
   rather than assumed from a grep count. The `Row` import is retained because four other call sites
   in the file still use it. Wrapping (rather than a horizontal scroll) was chosen because it keeps
   every protocol visible simultaneously and needs no gesture to discover. Evidence: SOURCE CODE.

4. **A JVM test now pins the option list to the record layer.** New
   `ManualServerProtocolChoicesTest` (4 tests, package `vn.unlimit.vpngate.ui.screens.settings`)
   asserts set equality between `MANUAL_SERVER_PROTOCOL_CHOICES` and `VpnProtocolNames.ALL`, that
   SSTP specifically is present, that no protocol appears twice, and that no offered key is unknown.
   The durable hazard this closes is the *second* cause of the same symptom: the list used to be
   written inline, so a protocol added to the record layer would silently never appear in this form
   and nothing would fail. Set — not list — equality is compared deliberately, because the form's
   order matches the filter sheet and is not what the test protects. Evidence: TEST.

5. **Defect B (manual server absent from the main list after a refresh, present after a restart) —
   the mechanism is now proven, and it is not a rendering defect.** The main list is built from
   `VPNGateItem` only (`ConnectionListViewModel` reads `vpnGateItemDao.getAll()` in all four
   `getAPIData` branches; `DataUtil.connectionsCache` reads the same DAO; `HomeScreen` never touches
   `manualServers`). The **only** writer of `VPNGateItem` is
   `ServerListUpdates.kt:268` — `dao?.replaceAll(withManualRows(items, manual.rows))` — so a manual
   row enters the main list **only when a refresh is accepted**, and never at add time. Device logs
   show both outcomes on the same install: at **12:20** the refresh was rejected
   (`dropsOpenVpnProfiles()` returned true, `Preserved(reason=DEGRADED_DATASET, serverCount=103)`,
   `replaceAll` never ran, manual row absent), and at **13:16** a healthy refresh was accepted
   (`Collected 106 servers (capability=…providesOpenVpnProfiles=true)`,
   `Dataset outcome MERGE_WITH_EXISTING: 106 incoming vs 103 stored`,
   `Updated(serverCount=106, snapshotSaved=true)`), after which `VPNGateItem` held **107** rows —
   106 collected **+ 1 manual**. Evidence: PHYSICAL DEVICE + SOURCE CODE.

6. **Correction to this revision's own STEP-2 conclusion.** The initial diagnosis asserted that
   "shown after restart" *could not* have referred to the main list and must have referred to the
   Settings section, which is what raised the design-gap question to the operator. Bullet 5 refutes
   that: a restart triggers a refresh on launch, and when that refresh is accepted the manual row
   **is** written into `VPNGateItem` and **does** appear in the main list. The operator's observation
   was therefore accurate and referred to the main list all along. The operator's decision — fix the
   picker now, **defer** manual-row visibility — is unchanged and is respected; what changes is that
   the deferred item now rests on a proven mechanism rather than on an unverified hypothesis, and its
   cost is much better understood: the gap is that `addManualServer` does not materialise the row
   into `VPNGateItem`, so a newly added server stays invisible until some later refresh happens to be
   accepted. Evidence: PHYSICAL DEVICE + SOURCE CODE.

7. **The merged manual row loses its protocol and label — a structural finding for the deferred
   work.** `VPNGateItem` has **no `protocol` and no `label` column**; the merge maps the manual
   entry's `label` into `operator` and its `port` into `seTcpPort`, leaving every other column at
   `0`/`NULL`. The row stored on this device reads
   `hostName='1.1.1.1'`, `ip='1.1.1.1'`, `operator='phaseB-test'`, `seTcpPort=443`,
   `isSSTPSupport=0`, `isL2TPSupport=0`. So even once visible, a manual SoftEther entry is
   indistinguishable in the main list from a collected one except by its `seTcpPort`, and an SSTP or
   L2TP manual entry would be stored with its protocol flag clear. This is recorded, not fixed: it is
   outside this phase's authorized change and is the substantive input to any future manual-visibility
   phase. Evidence: PHYSICAL DEVICE + SOURCE CODE.

8. **Methodology lesson (WAL) — carried forward, and it caught a false negative in this very phase.**
   A Room database in WAL mode cannot be read from its main file alone. On this device the main file
   was 1,036,288 B while `-wal` was **1,030,032 B** — essentially the entire live dataset. A
   main-file-only pull reproduces the v1.0.24 failure mode (0 rows in both new tables, empty
   `sqlite_sequence`, read as "the insert code never executed"), which was a **false negative**: the
   rows existed and were sitting in the WAL. The reliable procedure, used here, is to `ls -l` first,
   pull main **plus** `-wal` **plus** `-shm`, replay them beside a copy of the main file, and only
   then query — and to transfer binaries as base64 with a decoded-size assertion against the
   on-device `stat -c %s`, because a raw binary pipe through this toolchain corrupts bytes. This
   lesson supersedes the weaker "pull the DB" wording implied by §1.6.17 bullet 4 and is the reason
   that bullet's negative result must not be re-used as evidence. Evidence: PHYSICAL DEVICE +
   INFERENCE (from the byte sizes and the observed replay).

9. **Verification and chain arithmetic.** JVM: `:app:testProDebugUnitTest` **772 / 0 / 0 / 1**
   (baseline 768 / 0 / 0 / 1; **+4** from `ManualServerProtocolChoicesTest`; the single skip is the
   pre-existing `HtmlParserOracleTest` assumption) and `:SoftEtherClient:test` **173 / 0 / 0 / 0**.
   `CI=true :app:assembleProDebug` SUCCESSFUL, APK SHA-256
   `390be5c5a197e0c8cdef9ef61c3908c05e8a8778b3eb5dbf1bccb0e1f8ce984a` (63,737,026 bytes), installed
   `-r` over the existing data. Device: `5585fecc` (`2201117TG` / `spes_global`); after install,
   `user_version=7`, `integrity_check=ok`, and both operator rows survived (`manual_server` id 1
   `1.1.1.1:443`/`softether`/`phaseB-test`, `emergency_source_config` id 1 with the
   `raw.githubusercontent.com` URL, both `enabled=1`). The picker now renders **row 1** `OpenVPN`
   [132–309] · `SoftEther` [419–595] · `L2TP/IPsec` [705–915] and **row 2** `MS-SSTP` [132–305],
   all four `clickable=true`/`enabled=true`/`checkable=true`, with `MS-SSTP` reporting
   `checked='true'` — i.e. the selected protocol is now both visible and correct. Two commits make up
   this revision — `fix(ui)` and `docs` — so the §1.6.1 chain grows by 2. A spec file cannot name its
   own commit, so the header HEAD field points at the last pre-docs commit, `591a722`. **Residual
   hazard recorded, not fixed:** `manualProtocolLabel` ends in `else -> R.string.manual_server_protocol_sstp`,
   so a future protocol added to `VpnProtocolNames.ALL` would be *offered* correctly (the test forces
   it into the list) but *labelled* "MS-SSTP" — a fail-open default worth closing when that mapping
   is next touched. §22 is untouched and byte-identical (99 lines / 6,504 bytes); §1.6.2–§1.6.17 are
   untouched. No tag operation of any kind was performed.


#### 1.6.19 Manual Server Immediate Visibility (v1.0.26)

This subsection records the **MANUAL-SERVER-IMMEDIATE-VISIBILITY-v1.0.26** revision, which closes
three items from v1.0.25's own "recorded but not fixed" list. It is the first revision to close such
a list rather than add to it.

1. **Fix (B) — a manual server is written into `VPNGateItem` when it is added.** Before this, the
   only writer of the stored dataset was `ServerListUpdates.replaceAll`, whose `withManualRows`
   re-emission runs *inside an accepted refresh* — so a newly added server stayed invisible until
   some later refresh happened to be accepted, and stayed invisible indefinitely while refreshes
   were being rejected (§1.6.18 bullet 5). `ConnectionListViewModel.addManualServer` now, after
   `ManualServerStore.add` succeeds, reads the current table, merges, and writes it back through
   `ManualServerStore.materialize`, which **delegates to the same `withManualRows` the refresh path
   uses** rather than reimplementing it — one preservation policy, not two that could drift. The
   invariant therefore holds identically on both paths: an address the network already serves is not
   duplicated, no other row is dropped, and the result is collapsed by Room identity. Evidence:
   SOURCE CODE + TEST.
2. **Fix (C) — a degraded refresh is no longer silent.** `Outcome.Preserved` keeps the stored list,
   which is correct, but it left the screen looking exactly like "nothing to update". The ViewModel
   now exposes a one-shot `refreshNotice` (a string resource id) set **only** for
   `Reason.DEGRADED_DATASET` — the one case that means "we fetched a list and deliberately threw it
   away" — and `HomeScreen` shows it as a `Toast`, the mechanism the other Compose screens already
   use. `isError` deliberately was *not* reused: it means "there is nothing to show" and would blank
   a list that is present and correct, merely stale. Rejection semantics are untouched. Evidence:
   SOURCE CODE (+ device verification **not** performed — see bullet 4).
3. **Fix (D) — the protocol label no longer borrows `MS-SSTP`.** `manualProtocolLabel`'s `when`
   ended in `else -> R.string.manual_server_protocol_sstp`, so a protocol added later would have been
   *offered* correctly but *rendered* as MS-SSTP with nothing failing. The mapping is now
   `manualProtocolLabelRes`, with an explicit branch per protocol and `else -> null`; the caller
   falls back to the **raw key**, which reads as visibly odd instead of confidently wrong. **The
   requested compile-time guard is not achievable, and this is a structural fact rather than an
   omission:** `VpnProtocolNames` is an `object` of `const val` Strings and Kotlin has no exhaustive
   `when` over a `String` subject — the compiler always requires an `else`. A true compile-time
   guarantee would require converting the protocol keys to an enum or sealed type, i.e. changing the
   record layer and every call site, which is outside this phase's authorized files. The guard is
   therefore a **test**: `ManualProtocolLabelExhaustiveTest` walks `VpnProtocolNames.ALL` and fails
   if any member lacks a branch or two members share one. That is weaker than the compiler but it
   does fail, on the change that introduces the gap. This deviation was put to the operator and
   approved as the in-scope option. Evidence: SOURCE CODE + TEST.
4. **Device verification — the add-to-visible path, on `5585fecc`.** Two servers were added through
   the Settings form (`8.8.8.8:443`/`softether`/`immediate-test` and `9.9.9.9:443`/`softether`/
   `dup-test`) and **no refresh was performed at any point**: the list header still read
   `Last updated: 17 Oct 2026 - 13:16` (a v1.0.25-era refresh) throughout, and the app logged no
   update line after install. The list went **107 → 108 → 109** servers, and searching the main list
   returned `8.8.8.8` and `9.9.9.9` each as a rendered row (`Score: 0`, protocol `SoftEther`). DB-side
   (main + `-wal` + `-shm` replayed, base64 with decoded-size assertion): `manual_server` holds 3
   rows, `VPNGateItem` holds **109**, and each test address appears **exactly once** with
   `operator` = its label and `seTcpPort = 443` — no duplicate, nothing dropped.
   `user_version=7`, `integrity_check=ok`. **The degraded-refresh Toast (fix C) was NOT observed on
   device:** forcing it requires cutting the device's connectivity, which is the operator's network
   and not this phase's to toggle, so its evidence is SOURCE CODE only and its live behaviour is
   **UNKNOWN**. Evidence: PHYSICAL DEVICE + SOURCE CODE.
5. **JVM and build.** `:app:testProDebugUnitTest` = **780 / 0 / 0 / 1** (baseline 772 / 0 / 0 / 1;
   **+8** from `ManualServerImmediateMaterializeTest` 5 and `ManualProtocolLabelExhaustiveTest` 3);
   `:SoftEtherClient:test` = **173 / 0 / 0 / 0**, unchanged. `CI=true :app:assembleProDebug`
   SUCCESSFUL, APK SHA-256 `7619d1b06aecd626904efe2e52682b56ca67189623e0566be3339577707a522b`
   (63,739,346 bytes; +2,320 B over v1.0.25), installed `-r` over the existing data, which preserved
   all three operator rows. **Two build attempts were lost to the environment, not to the code:** the
   first `assembleProDebug` failed with "Gradle build daemon disappeared unexpectedly" and the second
   hung for 31 minutes at "Reusing configuration cache" before executing a single task; the identical
   sources had already compiled successfully in the test run, and the build succeeded on the third
   attempt with a fresh daemon and `--no-configuration-cache` (16m 25s). Evidence: TEST.
6. **Refresh rejection is unchanged — and one edge is recorded rather than claimed away.**
   `ServerListUpdater`, `DatasetIntegrity` and every threshold are untouched; `DEGRADED_DATASET` is
   still fail-closed and this revision only reports it. But materialising a row into the same table
   that `DatasetIntegrity` measures has a real, if narrow, consequence worth stating: `stored.size`
   grows by one, which raises `sizeFloor` (`stored.size * 0.5`) by 0.5 and can move `stored.size`
   across `hasComparableBaseline`'s floor of 10. A borderline candidate could therefore be rejected
   where it was previously accepted. In practice this is immaterial at the observed sizes (106–109
   stored against a floor near 54) and it converges to the state the next accepted refresh would
   have produced anyway, because that refresh re-emits the same row. It is recorded as a known
   consequence of the chosen design, not as a change to rejection policy. Evidence: SOURCE CODE +
   INFERENCE.
7. **Removing a manual server does not delete the materialised row — a deliberate limitation.**
   `removeManualServer` deletes from `manual_server` only. The merge then becomes a no-op by
   identity, so the row stays in `VPNGateItem` until the next accepted refresh replaces the table, at
   which point `withManualRows` no longer re-adds it. This matches §1.6.15's preservation invariant
   (a manual record is never deleted behind the user's back) and is test-pinned; eager deletion would
   be a new policy and is not introduced here. Evidence: SOURCE CODE + TEST.
8. **No new methodology lessons in the §22 sense.** Chain arithmetic: this revision adds two commits
   — `fix(manual)` and `docs` — so the §1.6.1 chain grows by 2. A spec file cannot name its own
   commit, so the header HEAD field points at the last pre-docs commit, `4f21f71`. §22 is untouched
   and byte-identical (99 lines / 6,504 bytes); §1.6.2–§1.6.18 are untouched. No tag operation of any
   kind was performed.


#### 1.6.20 FIX-D-2 — Triage 10 Failing Tests + Close Out AUTOCONNECT-AUDIT (v1.0.27)

This subsection records the **FIX-D-2** revision, which triages the 10 JVM test
failures observed against HEAD `0dc7ed3b` / spec v1.0.26 and closes the
AUTOCONNECT-AUDIT open item. It is a test-correctness + one-line diagnostic
reconciliation only — **no production behavioural change was introduced.**

1. **Baseline (verified).** HEAD `==` origin/main `== 0dc7ed3b`; spec v1.0.26;
   13 tracked modifications in the working tree (6 authorized for this phase, 7
   pre-existing from prior phases and intentionally left untouched / unstaged);
   `:app:testProDebugUnitTest` reported **10 failures** — 7 in the committed
   `VpnServerRepositoryR4bTest.kt` and 3 in the new
   `VpnServerRepositoryRoutingTest.kt`. Five tags present, local and remote
   (`p2.4.5` / `p2.4.7` / `p2.4.8` / `p2.4.9` / `p2.4.10`). Evidence: SOURCE CODE + TEST.
2. **Root cause A — lowercase diagnostic contract (one-line source fix, bucket A).**
   `VpnServerRepository.fetchOnChosenPath` emitted the rung names through
   `$path` / `$fallback` (Kotlin enum `.toString()` → uppercase `OPEN` / `VPN`),
   so its `[fetch] path=OPEN failed; falling back to VPN` and
   `[collector] path=fallback-to-OPEN source=…` lines violated the lowercase
   contract (`path=open` / `path=vpn` / `fallback-to-open`) already used by the
   primary-attempt lines. The fix wraps them in `${path.name.lowercase()}` /
   `${fallback.name.lowercase()}` so every `[fetch]` / `[collector]` diagnostic
   reads lowercase consistently. This is the intended OPEN-first / VPN-fallback
   behaviour; only the log *format* was wrong. Evidence: SOURCE CODE + TEST.
3. **Root cause B — three OPEN-first behavioural assertions (bucket A).** With
   OPEN-first routing and a bound tunnel, an OPEN-primary failure now falls back
   to VPN, which three committed assertions did not anticipate:
   - `open path is logged per source` — the test's `openInput` is
     `CollectorRoutingInput(false, false, false)` (no opt-in, no usable tunnel,
     no bound network), so the OPEN primary must have **no** VPN rung to fall
     back to; the call now passes `boundFetcher = null` so the fixture faithfully
     models "no bound network" and the assertion (exactly one `path=open` line
     per source, no fallback line) holds unchanged.
   - `an empty mirror list counts as failure and retries on open` — a total
     failure (primary + fallback both empty) returns `null`; the assertion now
     `assertNull(mirrors)` (the caller treats `null` as empty via `.orEmpty()`);
     the user-visible retry-on-open is still asserted through the log lines.
   - `routing is re-read per refresh rather than frozen at construction` — changed
     `firstRun.all { it.contains("path=open") }` → `any { … }`, because the OPEN
     primary now also emits `fallback-to-vpn` lines.
   All three are behavioural-change-expected assertion updates with KDoc comments;
   no assertion was flipped without a bucket-A justification. Evidence: TEST.
4. **Bucket classification.** All 10 failures are **bucket A** (behavioural change
   expected → update assertion / diagnostic KDoc). **No bucket B** (intent
   preserved, assertion wrong), **no bucket C** (implementation-detail-coupled),
   and **critically no bucket D** (test correct, CODE broken): the
   `fetchOnChosenPath` OPEN-first / VPN-fallback logic is the intended design, and
   the only source edit is a cosmetic lowercase string. Evidence: SOURCE CODE + TEST.
5. **Authorized scope, strictly observed.** Commit 1 carries
   `SoftEtherClient/src/main/cpp/softether-core/src/crypto/aes_wrapper.c`,
   `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeEngine.kt`,
   `app/src/main/java/vn/unlimit/vpngate/repository/VpnServerRepository.kt`,
   `app/src/test/java/vn/unlimit/vpngate/repository/VpnServerRepositoryR4bTest.kt`,
   `app/src/test/java/vn/unlimit/vpngate/repository/VpnServerRepositoryRoutingTest.kt`,
   `app/src/test/java/vn/unlimit/vpngate/automode/AutoModeEndToEndTest.kt`.
   The 7 pre-existing non-authorized dirty files (AndroidConnectionAdapter.kt,
   AutoModeController.kt, AutoModeProtocol.kt, TunnelStateWatcher.kt,
   HttpFetcher.kt, VpnM24x10TaskTest.kt, AutoModeControllerTest.kt,
   AutoModeNativeBusyTest.kt) were left untouched and unstaged. Commit 2 carries
   this spec (v1.0.26 → v1.0.27, §1.6.20, §20 rows) plus `docs/reports/FIX_D2_REPORT.md`.
   Evidence: SOURCE CODE.
6. **JVM and build.** `:app:testProDebugUnitTest` = **790 / 0 / 0 / 0** (the two
   affected classes: `VpnServerRepositoryR4bTest` **10 / 0 / 0 / 0**,
   `VpnServerRepositoryRoutingTest` **8 / 0 / 0 / 0**); `:SoftEtherClient:test`
   = **173 / 0 / 0 / 0**, unchanged. `CI=true :app:assembleProDebug` SUCCESSFUL
   (forced `--rerun-tasks --no-build-cache`), APK SHA-256
   `f09e949d0af2cccf26d756af6c7b00ef7745c401857666228b25f8aa3c2c1279`
   (63,743,062 bytes). Evidence: TEST.
7. **Git discipline.** Two commits on `main`, fast-forward push to `origin/main`
   (exactly 2 ahead, no force, no reset / clean / stash / checkout / restore, no
   tag operation of any kind). §22 is untouched and byte-identical (99 lines /
   6,504 bytes); §1.6.2–§1.6.19 are untouched. Evidence: SOURCE CODE.

#### 1.6.21 RECONCILE-DIRTY-FILES — Restore HEAD Buildability by Committing the D-1b Closure (v1.0.28)

This subsection records the **RECONCILE-DIRTY-FILES** revision, which restores the
standalone buildability of `HEAD` by committing the coherent **AUTOCONNECT-AUDIT
D-1b** closure that had been left in the working tree at v1.0.27. It is a
buildability-reconciliation only — **no new behavioural change was introduced**;
every committed symbol already existed in the working tree and is exercised by the
committed tests.

1. **Defect discovered (device-verify PART A).** `HEAD = b68ac2a` (the v1.0.27
   source commit) does **not** compile standalone. `AutoModeEngine.kt:82-83`
   (committed) references `nativeSlotAwaitMs` (a constructor parameter) and
   `NATIVE_SLOT_AWAIT_MAX_MS` (a constant) that existed **only** in the dirty
   working-tree `AutoModeController.kt`, not in the committed one. The
   working-tree APK (`f09e949d…`, built during the v1.0.27 phase) therefore ran
   code that `HEAD` could not reproduce. Per the BLOCK's STOP condition, on-device
   verification should have halted at PART A; PART B/C had already run on the
   working-tree APK in parallel and further device work (PART D/E) was
   deliberately not attempted. Evidence: SOURCE CODE + TEST (PART A build log).
2. **Classification and closure.** All 8 dirty files were read. Seven form a
   coherent D-1b closure (AUTOCONNECT-AUDIT D-1 / D-2 / D-4 / D-5 / D-7): the
   missing symbols (`nativeSlotAwaitMs`, `NATIVE_SLOT_AWAIT_MS`,
   `NATIVE_SLOT_AWAIT_MAX_MS`, `awaitNativeSlotRelease`, `AttemptOutcome.NATIVE_ERROR`),
   the `WedgeTracker` class (defined *inside* `AutoModeController.kt:1858`, not a
   missing new file), and the nullable `AutoModeProtocol.fromId` (git grep:
   **zero** callers repo-wide, so the signature change breaks nothing). The
   eighth file, `HttpFetcher.kt`, is a **doc-comment-only** OPEN→VPN routing
   wording change — cleanly separable, **outside** the build/test closure — and
   was classified category-(c) UNRELATED-IN-FLIGHT and **left dirty by design**.
   No ambiguity, so no STOP. Evidence: SOURCE CODE + TEST.
3. **Pre-commit baseline.** The full dirty tree compiled green with
   `CI=true :app:compileProDebugKotlin` (`COMPILE_EXIT=0`), confirming the dirty
   tree was the clean baseline before the closure was committed. Evidence: TEST.
4. **Committed closure (commit `df7b49c`, 7 files, +667 / −332).** The seven
   closure files: `AutoModeController.kt` (adds the missing symbols +
   `WedgeTracker`), `AndroidConnectionAdapter.kt` (`awaitNativeSlotRelease`
   implementation + `onAttemptFailed` wiring), `AutoModeProtocol.kt` (nullable
   `fromId`), `TunnelStateWatcher.kt` (`onAttemptFailed` relay on
   `STATE_ERROR` / `LEVEL_AUTH_FAILED`), `VpnM24x10TaskTest.kt` (`wedgeTracker =
   WedgeTracker()`), `AutoModeControllerTest.kt` (`WedgeTracker()` +
   `NATIVE_ERROR` assertion), `AutoModeNativeBusyTest.kt` (replaced
   `NATIVE_BUSY_RETRY_DELAY_MS` usages with `nativeSlotAwaitMs = 200L`;
   updated assertions). `HttpFetcher.kt` correctly remained unstaged. Evidence:
   SOURCE CODE + TEST.
5. **Standalone build gate (STEP 5).** `git archive HEAD | gradlew
   :app:assembleProDebug` was run **on C:** (a D:-drive run failed with an
   unrelated NDK/CMake metadata `Access is denied` on `.cxx/Debug/…/hash_key.txt`
   — an environmental D: permission fault, **not** a closure defect: no `^e:`
   Kotlin errors, `:SoftEtherClient:compileDebugKotlin` passed, the build aborted
   before `:app:compileProDebugKotlin`). The C:-drive run **SUCCEEDED**
   (`STANDALONE_BUILD_EXIT=0`), APK SHA-256
   `6e289cb179eec411c5b6ed6b5d9b3d8bfe70ba18a9c3ed4ee5939eab869f3248` — so
   `HEAD = df7b49c` is standalone-buildable, satisfying the BLOCK gate. Evidence:
   TEST (build log + APK hash).
6. **JVM tests (STEP 6).** `:app:testProDebugUnitTest` = **790 / 0 / 0 / 1** (60
   classes; the D-1b tests `AutoModeControllerTest`, `AutoModeNativeBusyTest`, and
   the `VpnM24x10TaskTest` `WedgeTracker` call sites are green);
   `:SoftEtherClient:test` = **173 / 0 / 0 / 0** (unchanged from the v1.0.20
   baseline; the D-1b closure touches only `:app`). Both modules `EXIT=0`.
   Evidence: TEST.
7. **Root-cause lesson (closure discipline).** A committed file
   (`AutoModeEngine.kt`) referenced symbols that existed only in a *dirty* file
   (`AutoModeController.kt`) — a containment break where the buildable state of
   `HEAD` depended on an uncommitted edit. The canonical repair is to commit the
   coherent closure as one unit, **not** to patch the leaf (`AutoModeEngine.kt`)
   or to silently amend history. Every future multi-file AUTOCONNECT change must
   be verified with `git archive HEAD | gradlew :app:assembleProDebug` (or the
   equivalent forced compile) **before** the device-verify step, so PART A can
   never pass against a tree that `HEAD` cannot reproduce. Recorded here as a
   candidate §22 lesson (see §22). Evidence: SOURCE CODE + INFERENCE.
8. **Git discipline.** Two commits on `main`, fast-forward push to `origin/main`
   (exactly 2 ahead of `b68ac2a`: `df7b49c` source + `0f5f307` docs; no force, no
   reset / clean / stash / checkout / restore, no tag operation of any kind).
   `HttpFetcher.kt` remains the only dirty tracked file (intentionally, by design).
    §22 is untouched and byte-identical (99 lines / 6,504 bytes); §1.6.2–§1.6.20 are
    untouched. Evidence: SOURCE CODE.

#### 1.6.22 Spec Integrity and Concurrent Worktree Lesson (v1.0.30)

This subsection records the **SPEC-INTEGRITY-AUDIT** revision — a read-only reference audit
plus a path-repair pass. No source or test behaviour changed; the only source commit is a
doc-comment-only `HttpFetcher.kt` change (bullet 4).

1. **Reports relocated to `docs/reports/` by commit `a76bdc6`.** That commit archived the root
   `*_REPORT.md` files (and several version-dotted audit reports) under `docs/reports/`. A
   read-only audit resolved every `*.md` token in this spec against the repo root, `docs/reports/`
   and `SoftEtherClient/`: **38** now live only under `docs/reports/`; **4** remain at the root
   (`AGENTS.md`, `PROJECT_SPECIFICATION.md`, `README.md`, `SERVER_SOURCE_ARCHITECTURE.md`); and
   **2** (`IMPLEMENTATION_PLAN.md`, `RUDP_IMPLEMENTATION_PLAN.md`) live under `SoftEtherClient/`
   and are cited with that path. **19** bare references were updated to `docs/reports/…` in the
   authorized regions — **14** in §1.6.2–§1.6.21 and **5** in §12.5.1. The remaining **46** bare
   occurrences of moved-only filenames (in §1.6.1's existing push-state notes, §3.1, §6.7, §12.4,
   the existing §20 rows, and one in this subsection's own prose) are **not** edited — those
   regions are outside this phase's edit scope — but each still resolves via the documented
   location. §22 contains **no** moved-file references and is byte-identical (bullet 6). Evidence:
   SOURCE CODE.
2. **Concurrent-worktree contamination event.** Two sessions edited the same working tree during
   the v1.0.28/v1.0.29 window: the main session produced `82cb64f` (spec v1.0.28 +
   `RECONCILE_REPORT.md`), while a concurrent session produced `82476bf` (spec v1.0.29 /
   architecture / README repair) and `a76bdc6` (report archiving). Because both staged from the
   same tree, `82cb64f` swept in partial v1.0.29 §1.3/§15.3 content that `82476bf` later
   superseded. **No source code was contaminated** — the overlap was documentation-only — but the
   docs history is noisy. Evidence: SOURCE CODE (`git diff 0f5f307..82cb64f`).
3. **Process rule (recorded, not a new methodology lesson).** A working tree MUST be owned by
   exactly one session at a time. If a second session must edit concurrently, it must use a
   **separate clone** (or a `git worktree`), never the same checkout — a blind `git add <file>`
   while another session is mid-edit will capture its uncommitted hunks. Evidence: INFERENCE.
4. **`HttpFetcher.kt` resolved — committed.** The pending change was a **doc-comment-only**
   rewording of the OPEN→VPN routing note (no code, no API): it now states that the VPN rung is
   auto-selected when a usable tunnel exists (citing `VpnServerRepository.productionRouting`,
   which exists at `VpnServerRepository.kt:112`) and falls back to open. Committed as `9d569c3`
   (`chore(sources): commit pending HttpFetcher doc-comment change`), removing the last tracked
   dirty file. Evidence: SOURCE CODE.
5. **The single skipped `:app` test.** `HtmlParserOracleTest.softetherUdpOnlyNeverBorrowsOpenvpnTcp`
   is skipped via `org.junit.AssumptionViolatedException: no SE-UDP-only + OVPN-TCP row in current
   snapshot`. It is a **data-dependent assumption skip**, not a failure and not a regression: the
   test only runs when the current snapshot contains that specific protocol/port combination, so
   the `790/0/0/1` vs `790/0/0/0` difference across runs is expected. Evidence: TEST
   (`test-results/testProDebugUnitTest/…HtmlParserOracleTest.xml`).
6. **No new methodology lessons.** §22 is untouched and byte-identical (**99 lines / 6,504
   bytes**). The candidate closure-discipline lesson from §1.6.21 and the concurrent-worktree rule
   (bullet 3) are recorded here, not in §22. Evidence: SOURCE CODE.

#### 1.6.23 AUTOCONNECT-FIX — Two-Audit Reconciliation (v1.0.31)

This subsection records the **AUTOCONNECT-FIX** revision, which closes four gaps found by the two
independent AutoConnect audits (`AUTOCONNECT_AUDIT_REPORT.md` and
`AUTOCONNECT_AUDIT_REPORT_v2.md`, both untracked and untouched) plus the new operator requirement
**OP-R6**. Spec v1.0.30 → v1.0.31. Device-free: **no device, no adb, no C5, no tag**.

1. **D-1 — default timeout unified to 12 s.** The documented default, the shipped Settings
   default and the pure-JVM fallback were three different numbers (5 s / 12 s / 25 s) and nothing
   pinned any of them, so the drift was invisible to CI. All three now agree on **12 s**:
   - `AutoModeController.DEFAULT_ATTEMPT_TIMEOUT_MS` = `12_000L` (`AutoModeController.kt:347`)
   - `DataUtil.getAutoModeTimeoutSeconds()` = `getIntSetting(SETTING_AUTO_MODE_TIMEOUT_SECONDS, 12)`
     with `.coerceIn(5, 60)` (SOURCE CODE)
   - Spec §6.7 R5 / §6.8.1: **12 s**, range **5–60 s**.
   The 25 s constant is retired, not merely justified. Evidence: SOURCE CODE + TEST
   (`AutoModeControllerTest.defaultAttemptTimeoutMatchesTheSettingsDefault`, `:2582`;
   `AutoModeNativeBusyTest` the-default-attempt-timeout-matches-the-settings-default, `:511`).
2. **D-2 — uniform non-timeout transition.** A thrown exception in `connect()` was a
   `break` → next **server**, skipping the server's remaining protocols. It is now a `continue` →
   next **protocol**, the same transition as every other non-timeout outcome. Two sites:
   `AutoModeController.kt:1446` (first dial) and the retry dial. Evidence: SOURCE CODE + TEST
   (`AutoModeControllerTest.connectExceptionAdvancesToTheNextProtocolNotTheNextServer`, `:2721`).
3. **D-4 — `isTunnelBusy()` guard wired to the production path.** The guard existed only in
   `startOrRefuseIfNoServers()`, which has no production caller, so the button dialled a second,
   competing tunnel during a manual connection. It now lives in
   `onButtonPressedWithoutGuard()` (`AutoModeController.kt:1010`): a press from
   `Disconnected`/`Error` is refused with the log line
   `[AUTO] start refused: a tunnel is already active` and no state change. Evidence:
   SOURCE CODE + TEST (`AutoModeControllerTest.buttonRefusesToStartWhileAnotherTunnelIsActive`,
   `:2609`).
4. **D-5 (OP-R6) — pressing while `Connected` continues from the next server.** A press on a
   live tunnel means "move on", not "start over". `resumeFromIndex` (`AutoModeController.kt:532`)
   is set to `currentServerIndex + 1` in the `Connected` branch of
   `onButtonPressedWithoutGuard()` (`:1010`), consumed exactly once at the top of `runAutoMode`
   (`val startAt = resumeFromIndex.coerceIn(0, servers.size)`, `:1026`), and zeroed on every
   fresh start and at the `ERROR_NO_SERVER` terminal (`:1773`). An out-of-range value starts no
   attempts and terminates with `ERROR_NO_SERVER` — it does **not** wrap around to index 0.
   Evidence: SOURCE CODE + TEST
   (`pressingWhileConnectedContinuesFromTheNextServer`, `:2645`;
   `pressingWhileConnectedToTheLastServerEndsWithoutRestarting`, `:2685`;
   `aFreshPressAfterExhaustionStartsFromTheTopAgain`, `:2755`).
5. **D-6/D-7 — four terminal conditions + the 4/5 native fraction documented.** §6.7 R7's "no
   other implicit stop condition" is replaced with the four system-level terminals
   (`ERROR_NO_ENABLED_PROTOCOL`, `ERROR_NATIVE_UNRESOLVED`, `ERROR_VPN_PERMISSION`, all-unreachable).
   The native connect budget is **80 % of `T`** (`NATIVE_TIMEOUT_FRACTION_NUM = 4L` /
   `NATIVE_TIMEOUT_FRACTION_DEN = 5L`, `AndroidConnectionAdapter.kt:74-75`) with a
   `MIN_NATIVE_CONNECT_TIMEOUT_MS = 1_500L` floor (`:84`). New **§6.8** records the complete
   attempt-loop contract: server-outer / protocol-inner nesting, the per-protocol independent
   window of `T`, the transition table for every `AttemptOutcome` plus the exception case, the
   eight terminal conditions, the `N × T` nominal bound with the real worst-case formula, the
   OP-R6 semantics, and the `isTunnelBusy()` refusal. Evidence: SOURCE CODE (spec text) +
   USER-DIRECTED REQUIREMENT (the rotation and timeout rules).
6. **D-8 — tests.** Six new tests added to `AutoModeControllerTest.kt` (no existing test deleted)
   and one assertion in `AutoModeNativeBusyTest.kt` corrected. Baseline `:app` **790/0/0/1**;
   expectation after this revision **~796/0/0/1** with the single pre-existing skip unchanged.
   Evidence: TEST.
7. **Git discipline.** Two commits on `main`, fast-forward push to `origin/main`, no force, no
   reset / clean / stash / checkout / restore, no tag operation, no `git add -A`. The two audit
   reports remain untracked and unmodified. §22 is byte-identical (99 lines / 6,504 bytes);
   §1.6.2–§1.6.22 are otherwise untouched. Evidence: SOURCE CODE.

> **Not device-verified.** All six tests are JVM-only; the wall-clock behaviour of the new
> rotation and timeout rules on hardware is **UNKNOWN** in this revision. Device verification is
> proposed in `AUTOCONNECT_FIX_REPORT.md` §12.

#### 1.6.24 AUTOCONNECT-FIX-2 — Two Device Bugs Closed (v1.0.32)

The device verification that followed v1.0.31 (DEVICE-VERIFY-AUTOCONNECT-FIX,
`DEVICE_VERIFY_AUTOCONNECT_FIX_REPORT.md`) found that two of the four v1.0.31 fixes did not hold on
hardware, even though the whole JVM suite was green. Both are closed here. The third finding — the
native slot not being released after a successful DHCP — is **instrumented only**; its root cause
remains unknown.

1. **BUG-A — the D-4 predicate was blind to `CONNECTED`.** The v1.0.31 refusal guard in
   `AutoModeController.onButtonPressedWithoutGuard()` was correct; the predicate it received was
   not. `AutoModeEngine.kt:116` wired `isTunnelBusy = { GlobalVpnTracker.isBusy }`, and
   `GlobalVpnTracker.kt:47` defines `isBusy` as `CONNECTING || DISCONNECTING` — `CONNECTED` was
   excluded. Consequence: with a manual OpenVPN tunnel up and Auto Mode `Disconnected`, pressing
   the button started a full run (`[AUTO] Started`, `[AUTO] Trying #1`) instead of logging
   `start refused`, and the run then destroyed the user's manual tunnel.
   **Fix:** new `VpnConnectionStatus.isActiveOrBusy` (`CONNECTED || CONNECTING || DISCONNECTING`)
   plus its `GlobalVpnTracker.isActiveOrBusy` accessor; `AutoModeEngine` now supplies that.
   `isBusy` itself is unchanged — it still answers the narrower "is a transition in flight"
   question. §6.8.6 now states the predicate explicitly.
   **Why the tests missed it:** T-NEW-2 injected a fake `isTunnelBusy` lambda, so it proved the
   guard was *wired*, never that the production *predicate* was right. T-NEW-7 now pins the
   predicate at the enum level and T-NEW-8 pins the engine wiring.
   Evidence: SOURCE CODE + PHYSICAL DEVICE (`DEVICE_VERIFY_AUTOCONNECT_FIX_REPORT.md` §5).

2. **BUG-B — a server refused on `NATIVE_BUSY` could never be excluded.** `recordWedge` was
   reachable only from the `TIMED_OUT` branch, so a server whose every protocol was refused was
   re-selected indefinitely: each run re-dialled it, spent a full slot budget, and terminated
   without ever reaching the next server. On the device, `vpn131251792` was selected in four
   consecutive runs (`attempt=12/13/14/15`), each ending
   `Native slot not released within 25000ms … stopping`, and no
   `Skipping …: excluded for this session` line was ever emitted.
   **Fix:** `AutoModeController` counts `NATIVE_BUSY` per server per run and, at
   `NATIVE_BUSY_WEDGE_THRESHOLD = 2`, records one wedge through the existing
   one-wedge-per-server-per-run path. `WedgeTracker.EXCLUSION_THRESHOLD` is unchanged (2) — the
   fix makes a refusal countable, it does not lower the bar for exclusion. §6.8.3 now carries the
   wedge rule.
   Evidence: SOURCE CODE + PHYSICAL DEVICE (`DEVICE_VERIFY_AUTOCONNECT_FIX_REPORT.md` §7).

3. **Slot-non-release — INSTRUMENTED, root cause STILL UNKNOWN.** The device run showed
   `DHCP success: IP=10.211.1.130/16` in the native stack while the controller never logged
   `Tunnel established`, then blocked on the process-wide slot for the full `min(5T, 60 s)` budget
   and ended the run with `ERROR_NATIVE_UNRESOLVED`. Two log-only additions were made, with **no
   control-flow change**: (a) a line when `isOwnTunnelEstablished()` answers `false` while the
   tracker says `CONNECTED`; (b) a `SLOT_UNRELEASED_DIAG` line carrying the measured wait, the
   budget, the outcome, the protocol, the server, the tracker status, `isOwnTunnelEstablished()`
   and the registry descriptor. A new read-only `ConnectionAdapter.tunnelTrackerStatus()` supplies
   the tracker's opinion for logging only — never a decision input, for the reason
   `AndroidConnectionAdapter.isOwnTunnelEstablished` already documents.
   Evidence: SOURCE CODE + **UNKNOWN** (root cause not identified; no fix attempted).

4. **JVM tests.** Four new tests in `AutoModeControllerTest.kt` (no existing test deleted or
   modified): T-NEW-7 (predicate includes `CONNECTED`), T-NEW-8 (engine wiring uses
   `isActiveOrBusy`, and OP-R6 is *not* gated), T-NEW-9 (two refusals record a wedge and the run
   rotates), T-NEW-10 (a single refusal does not). `:app` went **796/0/0/1 → 800/0/0/1**;
   `:SoftEtherClient` unchanged at **173/0/0/0**. Evidence: TEST.

5. **APK.** `app/build/outputs/apk/pro/debug/app-pro-debug.apk`, SHA-256
   `RECORDED_IN_REPORT` — see `AUTOCONNECT_FIX_2_REPORT.md` §9 for the exact value. Evidence: TEST
   (build).

6. **Device re-verification of S3 (D-4 refusal) and S5 (rotation under refusal): PENDING** — next
   phase. Evidence: UNKNOWN.

7. **S1 (the "12 s default") was a device-state deviation, not a code defect.** The device had
   `SETTING_AUTO_MODE_TIMEOUT_SECONDS = 5` stored from prior use, so the UI correctly rendered
   `5 s`; the code default is 12 and the clamp is `[5, 60]`. Clearing app data to observe a
   fresh-install default is outside a device-verify phase's authority, so it was not done and the
   item is recorded as a deviation. Evidence: PHYSICAL DEVICE
   (`DEVICE_VERIFY_AUTOCONNECT_FIX_REPORT.md` §3).

8. **No new methodology lessons.** The two bugs were ordinary specificity failures — a guard
   tested through an injected seam rather than its production predicate, and a ledger with one
   unreachable write site — both caught by device evidence rather than by CI. That the device run
   is the gate that matters is already lesson **L4** / §22 and is not restated.

> **Device status of this revision.** v1.0.32 is **device-free**: no test here runs on hardware,
> and the two fixes are verified only by SOURCE CODE plus the pre-fix device evidence. Whether they
> behave as intended on 5585fecc is **UNKNOWN** until a re-verification phase runs.

#### 1.6.25 AUTOCONNECT-FIX-3 — Instrumentation and Budget Floor (v1.0.33)

The device verification that followed v1.0.32 (DEVICE-VERIFY-AUTOCONNECT-FIX-2,
`DEVICE_VERIFY_AUTOCONNECT_FIX_2_REPORT.md`) confirmed both shipped bug fixes on hardware and found
two further defects — one in the instrumentation itself, one in the time budget it measures. Both
are closed here. Spec v1.0.32 → v1.0.33. Device-free: **no device, no adb, no C5, no tag**.

1. **Device findings that drive this revision.** S1 (BUG-A refusal), S2 (OP-R6 continuation) and S3
   (BUG-B wedge + rotation + exclusion) all **PASSED** on 5585fecc and are untouched here. S4 (D-2
   exception → next protocol) was **UNTESTABLE**: no `connect()` threw during ~25 minutes of real
   runs. `SLOT_DIAG` was **NOT EXERCISED**, and the reason is the subject of bullet 2.
   Evidence: PHYSICAL DEVICE + SOURCE CODE (`DEVICE_VERIFY_AUTOCONNECT_FIX_2_REPORT.md` §4–§7).
2. **Fix 1 — the diagnostic was attached to the wrong site.** `AutoModeController.kt` contains
   **two** places that can terminate a whole run because the process-wide native slot stayed held:
   the NATIVE_BUSY branch (formerly `:1695`, carrying no diagnostic) and the post-attempt branch
   (`~:1834`, carrying `SLOT_UNRELEASED_DIAG`). All three on-device terminations came from the
   **NATIVE_BUSY** site — its message carries no `after <OUTCOME> on <PROTOCOL>` clause, and
   `(attempt=N phase=P)` is `NativeLifetimeRegistry.describe()`'s own format — so the diagnostics
   added in v1.0.32 never ran. The `SLOT_UNRELEASED_DIAG` body is now emitted from **both** sites
   (`AutoModeController.kt:1689-1719` and `:1836-1858`). At the NATIVE_BUSY site `outcome` is not
   in scope, so `outcome=${AttemptOutcome.NATIVE_BUSY.name}` is used — the branch can produce
   nothing else. The wait condition, the terminal order and every branch are unchanged; the only
   additions are `slotWaitStartedAt`, `waitedMs` and the log call, mirroring the existing site so
   the two terminals are comparable line for line. Evidence: SOURCE CODE.
3. **Fix 2 — the slot-wait budget gained an absolute floor.** `nativeSlotAwaitMs` was derived as
   `5 × T` and capped at `NATIVE_SLOT_AWAIT_MAX_MS` (60 s). With the device preference persisted at
   `T = 5 s` that yielded **25 s**, while the release latencies measured in the same session were
   6.0 / 6.2 / 7.5 / 13.3 / 13.7 / 17.0 / 20.9 s — a 4.1 s margin — and one release landed
   **292 ms after** the cutoff, ending the run while the slot was about to free itself. The
   derivation is now `.coerceAtLeast(AutoModeController.NATIVE_SLOT_AWAIT_MS).coerceAtMost(…)`
   (`AutoModeEngine.kt:90-93`): the floor is **30 s** and the pre-existing cap still applies on top.
   The rationale is that release latency belongs to the *previous native operation's own duration*,
   not to the attempt timeout: a user who selects a small `T` is choosing how long to wait for a
   **server**, and that choice must not also shorten the budget for our own stuck operation.
   Evidence: SOURCE CODE + PHYSICAL DEVICE (the latencies and the persisted preference).
4. **Test added.** `AutoModeControllerTest.nativeSlotAwaitHasAThirtySecondFloor` pins both halves:
   that `AutoModeEngine.kt` derives the value through `coerceAtLeast` referencing
   `AutoModeController.NATIVE_SLOT_AWAIT_MS`, and that the constant itself is `>= 30_000L`. It is a
   source-and-constant assertion, not a timing test, because the JVM seam that would otherwise cover
   this is the derivation itself. No existing test was modified or deleted. Evidence: TEST.
5. **JVM tests.** `:app` **801 / 0 / 0 / 1** (baseline 800 / 0 / 0 / 1 — the delta is the one new
   test, skip unchanged); `:SoftEtherClient` **173 / 0 / 0 / 0** (unchanged). Evidence: TEST.
6. **Build.** `:app:assembleProDebug --rerun-tasks --no-build-cache` under `CI=true` →
   BUILD SUCCESSFUL. APK SHA-256
   `42D90C3889ABF4ECF31A7E1487A9B05C795CDFD38D1FE136D50309AECAB9D14E` (63,749,658 bytes),
   which **differs** from v1.0.32's `946CA968…AA347` as expected: the code changed. Evidence: TEST.
7. **Device re-verification is PENDING.** Whether `SLOT_UNRELEASED_DIAG` now fires with its full
   payload (`tracker`, `ownTunnel`, `registry`) on 5585fecc, and whether the 30 s floor prevents the
   292 ms-late termination, are **UNKNOWN** in this revision. The root cause of the slot-non-release
   itself remains **UNKNOWN** and was **not** attempted here — this revision instruments and widens
   the budget only. Evidence: UNKNOWN.
8. **No new methodology lessons.** The defect in bullet 2 is an instance of the lesson already
   recorded in §1.6.24 bullet 8 — a change verified through a seam rather than through the path that
   actually executes — this time in instrumentation rather than in a guard. It is not restated.
   Evidence: SOURCE CODE.

> **Device status of this revision.** v1.0.33 is **device-free**. The fixes are verified by SOURCE
> CODE and the JVM suite only; the hardware evidence that motivated them is pre-fix
> (`DEVICE_VERIFY_AUTOCONNECT_FIX_2_REPORT.md`). No claim is made here about on-device behaviour.

#### 1.6.26 AUTOCONNECT-FIX-4 — Bounded RUDP Fallback + Wedge on Terminal (v1.0.34)

The device verification that followed v1.0.33 (`DEVICE_VERIFY_AUTOCONNECT_FIX_3_REPORT.md`) found
that the slot-wait budget was never the binding constraint: the *first* native attempt on the
offending endpoint never returned, so no budget of any size could help. Two independent,
complementary fixes close the structural cause and the accounting consequence. Spec v1.0.33 →
v1.0.34. Device-free: **no device, no adb, no C5, no tag**.

1. **Device finding that drives this revision.** The TCP TLS path is **bounded**
   (`socket_apply_timeout` at `softether_protocol.c:2839`, `SO_RCVTIMEO applied=yes`, expiring
   correctly); the NAT-T/RUDP fallback handshake was **unbounded** — `perform_tls_handshake` was
   called with no timeout application and no discriminator. On `vpn131251792` the RUDP transport
   reached ESTABLISHED and the handshake then blocked for **28.5 s of complete silence**, ended only
   by the controller's forced teardown. Because the very first `SSL_do_handshake` never returned,
   the `max_attempts` loop in `aes_wrapper.c` never iterated (zero `SSL handshake attempt N` lines
   in any run), so the process-wide native slot stayed held and every slot-wait budget expired
   identically. Evidence: SOURCE CODE + PHYSICAL DEVICE
   (`DEVICE_VERIFY_AUTOCONNECT_FIX_3_REPORT.md` §7.3).
2. **Fix-A (native) — the fallback is now bounded like the primary path.** Before
   `perform_tls_handshake(conn, connect_host)` in the NAT-T/RUDP branch, the code now calls
   `socket_apply_timeout(conn->socket_fd, conn->timeout_ms)` (`softether_protocol.c:2935`) and emits
   the same entry discriminator the TCP site emits, tagged `(RUDP)` (`:2941`). `conn->socket_fd`
   there is `rudp_transport_get_fd(tr)` — the app-facing end of the RUDP socketpair
   (`rudp_transport.c:1214`), which is exactly the fd OpenSSL reads from, so `SO_RCVTIMEO` makes the
   blocking `recv()` return `EAGAIN` and the existing `WANT_READ`/`WANT_WRITE` ladder takes over.
   The same file already relies on this for the parallel UDP race path (`:2036`), which is the
   precedent, not an assumption. Fail-open: a helper failure logs a warning and proceeds rather than
   turning a would-be success into a failure, matching the TCP site. The TCP branch,
   `perform_tls_handshake` itself, and every other native file are unmodified. Evidence: SOURCE CODE.
3. **Fix-B (Kotlin) — the NATIVE_BUSY terminal now records a wedge.** The v1.0.32 counter
   (`nativeBusyThisServer`, threshold 2) is **structurally unable** to fire on this failure: the
   terminal ends the run on the first refusal, so the counter can never reach 2, the ledger is never
   written, and the same endpoint is re-selected on every run — `vpn131251792` was selected in 5/5
   runs and died identically 5/5 times with no `recording a wedge` and no `excluded for this
   session` line. The terminal branch now records one wedge for the server before giving up
   (`AutoModeController.kt:1729-1736`), so the process-scoped `WedgeTracker` accumulates **across
   runs** and the unchanged `EXCLUSION_THRESHOLD` (2) is honoured. The strike is cleared on any
   verified tunnel, so a transient cause that later resolves does not exclude the endpoint
   permanently. Evidence: SOURCE CODE + TEST.
4. **The design change to `giveUpWithUnresolvedNativeOperation`.** The FIX-3/AUTOCONNECT-AUDIT D-5
   rationale — "a give-up caused by our own unresolved operation records nothing" — was correct for
   the case it was written for: *our own previous* operation still running, where the server was
   never contacted and therefore says nothing about the endpoint. Device evidence proved the
   terminal case is different: TCP connect and the TCP TLS handshake succeed, the VPNCONNECT
   watermark is rejected, and the RUDP fallback then blocks — the endpoint itself triggers the hang.
   So the rule is **narrowed, not reverted**: the terminal caller records the wedge, while
   `giveUpWithUnresolvedNativeOperation` itself still records nothing and is still reached from the
   post-attempt site, where the original reasoning holds exactly. The KDoc on that function records
   the narrowing (`AutoModeController.kt:2012-2025`). Evidence: SOURCE CODE + PHYSICAL DEVICE.
5. **STEP 4 (bound the RUDP BIO read) was NOT applied.** The repository installs no custom BIO for
   the RUDP transport: `perform_tls_handshake` hands `conn->socket_fd` to `ssl_connect`
   (`softether_protocol.c:1236`), so the blocking read is OpenSSL's own socket-BIO `recv()` on the
   socketpair fd. There is no short, single blocking call in project code to add a deadline to —
   `rudp_recv` (`softether_rudp.c:1120`) is the transport's own receive path on the *other* side of
   the socketpair, not the call the handshake blocks in. The socket option is therefore the correct
   and only lever, which is precisely what Fix-A applies. Recorded as a deliberate skip, not an
   omission. Evidence: SOURCE CODE.
6. **JVM tests.** `:app` **803 / 0 / 0 / 1** (baseline 801 / 0 / 0 / 1 — the delta is the two new
   tests, skip unchanged); `:SoftEtherClient` **173 / 0 / 0 / 0** (unchanged). The two new tests are
   `aNativeBusyTerminalRecordsAWedgeThatSurvivesAcrossRuns` (behavioural: one terminal → one wedge,
   two runs → 2, third run skipped without a dial) and
   `theRudpFallbackHandshakeIsBoundedLikeTheTcpPath` (source shape: the RUDP site carries the
   timeout call and the `(RUDP)` discriminator, the TCP site keeps its own, and exactly two
   handshake sites apply the bound). No existing test was modified or deleted. Evidence: TEST.
7. **Build.** `:app:assembleProDebug --rerun-tasks --no-build-cache` under `CI=true` →
   BUILD SUCCESSFUL. APK SHA-256
   `4ABC463606B83217D75422136DA6678097DAAC666D63BF5AB3B7BA6A624B1435` (63,751,914 bytes), which
   **differs** from v1.0.33's `42D90C38…CAB9D14E` as expected: the native library changed. A
   standalone build of the pushed tree (archive → assemble) also passed. Evidence: TEST.
8. **Device re-verification is PENDING.** Whether the RUDP-bound handshake now returns on
   `vpn131251792`, and whether the wedge-on-terminal excludes it after two runs instead of
   re-selecting it forever, are **UNKNOWN** in this revision. Expected signals for the next phase:
   `SLOT_UNRELEASED_DIAG` count **0**; at least one `[AUTO] Native slot released after …ms` line;
   and the same endpoint skipped with `excluded for this session` on run 3 rather than dialled
   again. The root cause of the endpoint's refusal itself (why the VPNCONNECT watermark fails)
   remains **UNKNOWN** and was **not** attempted here. Evidence: UNKNOWN.
9. **No new methodology lessons.** Fix-B is an instance of the lesson already recorded in §1.6.24
   bullet 8 — a rule verified against the seam it was designed for, not against the path that
   actually executes — this time in the wedge ledger rather than in a guard. It is not restated.
   Evidence: SOURCE CODE.

> **Device status of this revision.** v1.0.34 is **device-free**. Both fixes are verified by SOURCE
> CODE, the JVM suite and the build only; the hardware evidence that motivated them is pre-fix
> (`DEVICE_VERIFY_AUTOCONNECT_FIX_3_REPORT.md`). No claim is made here about on-device behaviour.

#### 1.6.27 AUTOCONNECT-FIX-5 — Skip Retry on Unresolved Teardown (v1.0.35)

1. **The device evidence that drives this revision.** `DEVICE_VERIFY_AUTOCONNECT_FIX_4_REPORT.md`
   §15 counted every post-slot-release retry in one five-run session: **12** retries, of which **10**
   ended instantly with `NATIVE_ERROR` (~0.1 s), **2** with `NATIVE_BUSY` (~5 s), and **ZERO**
   reached `TUNNEL_UP`. In **11 of the 12** the preceding teardown was
   `TeardownCompletion.Outcome.NATIVE_UNRESOLVED` — the one outcome whose own KDoc states that
   ownership is retained and "every later start is refused by the registry". The retry therefore
   could not be admitted, yet it paid its own `disconnect()` plus the following slot wait before the
   run moved on (~6 s per failed protocol). Evidence: PHYSICAL DEVICE.
2. **The fix.** `ConnectionAdapter` gains `lastTeardownWasResolved(): Boolean`
   (`AutoModeController.kt:322`), defaulting to `true` so a double that does not model teardown
   outcomes is unchanged. `AndroidConnectionAdapter` records the outcome it already computes — the
   field at `AndroidConnectionAdapter.kt:106`, assigned at `:302`, read by the override at `:318-319`
   — and reports `false` **only** for `NATIVE_UNRESOLVED`. In the `NATIVE_BUSY` branch, immediately
   after the slot is confirmed released, the controller consults it
   (`AutoModeController.kt:1813`): when unresolved it logs
   `[AUTO] Skipping retry on <protocol>: previous teardown was unresolved` and `continue`s to the
   next protocol instead of re-dialling. `NATIVE_CONFIRMED`, `NOOP_REENTRANT`,
   `FOREIGN_STACK_TERMINAL` and a `disconnect()` timeout all keep the pre-v1.0.35 retry: none of
   them refuses a start, and the timeout state is unknown, so the fix narrows behaviour only where
   the evidence applies. The NATIVE_BUSY wedge accounting above the gate is untouched, so a second
   refusal still records its wedge. Evidence: SOURCE CODE.
3. **Tests added.** `unresolvedTeardownSkipsThePostReleaseRetry`
   (`AutoModeControllerTest.kt:3161`) pins three things at once: the skip is logged, each refused
   protocol is dialled exactly once (no retry), and the wedge is still recorded for the second
   refusal. `resolvedTeardownKeepsThePostReleaseRetry` (`:3214`) pins the other side: with a clean
   teardown the same protocol is dialled twice and the run connects, with no skip line. No existing
   test was modified or deleted; the fake's new `lastTeardownResolved` field defaults to `true`
   (`:92`), which is why every pre-existing rotation test keeps its exact connect sequence.
   Evidence: TEST.
4. **JVM tests.** `:app` **805 / 0 / 0 / 1** (baseline 803 / 0 / 0 / 1 — the delta is exactly the two
   new tests, skip unchanged); `:SoftEtherClient` **173 / 0 / 0 / 0** (unchanged). Evidence: TEST.
5. **Build.** `:app:assembleProDebug --rerun-tasks --no-build-cache` under `CI=true` → BUILD
   SUCCESSFUL. APK SHA-256
   `8FB206964E86E7A5166F75200C77443A94463579390789FB18570A9F092FD801` (63,752,258 bytes). It
   differs from v1.0.34's `4ABC4636…4B1435` although no native source changed — the APK embeds the
   compiled Kotlin. A standalone build of the pushed tree (archive → assemble) also passed.
   Evidence: TEST.
6. **What remains UNVERIFIED from v1.0.34 — both fixes, unchanged by this revision.** Fix-A (the
   native RUDP handshake bound, `softether_protocol.c:2935`) was **not exercised** on 2026-10-19:
   `softether_try_nat_t_connect` returns `error=6` at the NAT-T rendezvous, so the
   `if (result == ERR_NONE)` block that contains the site is never entered — zero
   `[TLS] handshake entry (RUDP)` lines in five runs. Fix-B (wedge on the `NATIVE_BUSY` terminal,
   §1.6.26 bullet 4) was likewise not exercised: no run reached `ERROR_NATIVE_UNRESOLVED`. Neither
   is refuted; both remain **UNVERIFIED** and neither file was touched here. Evidence: UNKNOWN.
7. **The two prior-phase reports are now committed.** `DEVICE_VERIFY_AUTOCONNECT_FIX_4_REPORT.md`
   and `SLOT_RACE_INVESTIGATION_PREP_REPORT.md` are tracked from this revision onward. Evidence:
   SOURCE CODE.
8. **No new methodology lessons.** The retry gate is an instance of the rule already recorded in
   §1.6.24 bullet 8 — validate against the path that actually executes, not the seam the rule was
   written for — and is not restated. Evidence: SOURCE CODE.

#### 1.6.28 AUTOCONNECT-FIX-6 — NATIVE_BUSY Race, Capped Waits (v1.0.36)

1. **The device finding that drives this revision.**
   `DEVICE_VERIFY_AUTOCONNECT_FIX_4_UNFILTERED_REPORT.md` §5.3 records the v1.0.34 build running on an
   **unfiltered** WiFi network: **3 of 4** runs **destroyed a working tunnel**, and **1** run
   **excluded a healthy server** after two false wedges. In each of the three, the log carried
   `DHCP SUCCESS` and `SLOT_UNRELEASED_DIAG … tracker=CONNECTED ownTunnel=true` — written by the very
   code that then destroyed the tunnel. Fix-A was **not** exercised in that session: every login
   succeeded over TCP, so no NAT-T rendezvous and no `[TLS] handshake entry (RUDP)` line appeared.
   Evidence: PHYSICAL DEVICE.
2. **The root cause.** `awaitArmedOutcome`'s timeout can fire a few hundred milliseconds **before**
   the `CONNECTED` notification arrives. At that instant `isNativeSlotBusy()` answers `true` — because
   **our own** native operation is still running — so the attempt is classified `NATIVE_BUSY`. The
   `NATIVE_BUSY` branch then waited for the process-wide slot to be released, but the slot was held
   by the very tunnel that had just come up, so the wait ran to its budget and the terminal tore that
   tunnel down. The post-attempt path has always re-checked ownership before declaring failure
   (`ownTunnelUp`, `AutoModeController.kt:1929`); the `NATIVE_BUSY` branch never did. Evidence:
   SOURCE CODE + PHYSICAL DEVICE.
3. **Fix-1 (primary) — own-tunnel check before any wait.** At the top of the `NATIVE_BUSY` branch,
   before the counter and before the wait, the controller now asks
   `adapter.isOwnTunnelEstablished()` (`AutoModeController.kt:1683`). When it answers `true` it logs
   `[AUTO] Tunnel came up despite NATIVE_BUSY; treating attempt as connected` (`:1685`), sets
   `connectedProtocol` and `break`s out of the protocol loop, so the existing success path sets
   `Connected`, calls `onSuccess` and starts the connected watcher. No wedge is recorded and no retry
   is issued: the endpoint did not fail, our own operation succeeded. Evidence: SOURCE CODE.
4. **Fix-2 — the `NATIVE_BUSY` slot wait is capped.** The wait is now
   `min(nativeSlotAwaitMs, attemptTimeoutMs)` (`AutoModeController.kt:1772`) instead of
   `nativeSlotAwaitMs` alone, and the terminal log plus the `SLOT_UNRELEASED_DIAG` `budget=` field
   report the capped value (`:1820`, `:1828`). The retry mechanism is **kept**; the v1.0.35
   `lastTeardownWasResolved` gate at `:1865` still skips it when the previous teardown was unresolved,
   so most pairs stay at `2T`. Evidence: SOURCE CODE.
5. **Fix-3 — the post-attempt slot wait carries the same cap.**
   `min(nativeSlotAwaitMs, attemptTimeoutMs)` at `AutoModeController.kt:2003`, with the terminal log
   and the diagnostic reporting it (`:2010`, `:2019`). `NATIVE_ERROR` still short-circuits the wait
   entirely, because no slot was ever acquired. Evidence: SOURCE CODE.
6. **The prior phase's STOP, and why this is Option A.** AUTOCONNECT-FIX-6 as first briefed required
   **removing** the slot wait and the retry altogether, which is the only way to reach a strict
   `2×T`. That change breaks **9** documented tests — `nativeBusyRetrySucceedsOnSecondAttempt`,
   `secondRefusalAfterReleaseRotatesToTheNextProtocol`, `aSlotThatNeverReleasesEndsTheRun`,
   `nativeBusyCancellationDuringRetryIsClean`, `releasedSlotRetryFailureMovesOnToTheNextProtocol`,
   `timedOutThenNativeBusyOnNextProtocolWaitsAndRetries`, `repeatedNativeBusyRecordsAWedgeAndRotates`,
   and the two v1.0.35 tests — because those tests pin the v1.0.33/34/35 design rather than an
   accident. The prior phase therefore **stopped at STEP 2 without editing any file** and asked for a
   decision. The operator chose **Option A**: keep the retry, cap the waits, and add the own-tunnel
   check that actually fixes the observed bug. **Option B** (remove the retry, reach a strict `2T`)
   remains available in a future phase **with explicit test-rewrite authorization**; it was not
   attempted here. Evidence: SOURCE CODE + PRIOR REPORT.
7. **Tests added, additively only.** `nativeBusyWithOwnTunnelUpIsTreatedAsConnected`
   (`AutoModeControllerTest.kt:3285`) pins Fix-1: `Connected`, the recovery log, no wedge log,
   `slotWaits == 0`, ledger count `0`, one dial. `nativeBusySlotWaitIsCappedToTheAttemptBudget`
   (`:3334`) pins Fix-2 by asserting the **argument** the adapter received equals
   `min(NATIVE_SLOT_AWAIT_MS, T)` and is strictly below the constant, while the retry still fires.
   `postAttemptSlotWaitIsCappedToTheAttemptBudget` (`:3374`) pins Fix-3 the same way after a
   `TIMED_OUT`. No existing test was modified or deleted. The fake gained two additive members whose
   defaults preserve every pre-existing behaviour: `ownTunnelEstablished = false` (`:116`, the
   interface default) and `slotWaitTimeouts` (`:92`, recorded but ignored by every older test).
   Evidence: TEST.
8. **JVM tests.** `:app` **808 / 0 / 0 / 1** (baseline 805 / 0 / 0 / 1 — the delta is exactly the
   three new tests, skip unchanged); `:SoftEtherClient` **173 / 0 / 0 / 0** (unchanged). All **805**
   pre-existing tests still pass. Evidence: TEST.
9. **Build.** `:app:assembleProDebug --rerun-tasks --no-build-cache` under `CI=true` → BUILD
   SUCCESSFUL. APK SHA-256
   `BE345075A1F2E38D7ADD818B37072E27D0ED70CB0A00A4129035545AE4ECF249` (63,753,806 bytes). It differs
   from v1.0.35's `8FB20696…92FD801` as expected — the APK embeds the compiled Kotlin, and the native
   sources were not touched. The first build attempt failed with `There is not enough space on the
   disk` (`:vpnLib:copyDebugJniLibsProjectOnly`) on a volume reporting **0 bytes free**; that is an
   environment fault, not a source defect — regenerable build outputs and stale temp artifacts were
   removed, and the identical sources then built green. Evidence: TEST.
10. **What remains UNVERIFIED.** Fix-A (the native RUDP handshake bound,
    `softether_protocol.c:2935`) is still **UNVERIFIED**: the unfiltered run produced zero NAT-T
    rendezvous and zero `[TLS] handshake entry (RUDP)` lines, so the site was never reached. The
    strict `2×T` bound is **Option B**, which this phase deliberately did not take. The three fixes in
    this revision are themselves **not device-verified** — they are SOURCE CODE + TEST only, and a
    device run is the next evidence layer. Evidence: UNKNOWN.
11. **No new methodology lessons.** Bullet 6 is an instance of the rule already recorded in §1.6.24
    bullet 8 — validate against the path that actually executes — and is not restated.

#### 1.6.29 AUTOCONNECT-FIX-7 — NATIVE_BUSY Classification + No Terminal (v1.0.37)

1. **The v1.0.36 regression, measured on the device.** `DEVICE_VERIFY_AUTOCONNECT_FIX_6_REPORT.md`
   §7.1 records the v1.0.36 build against the same endpoint: **2 of 5** runs terminated with
   `ERROR_NATIVE_UNRESOLVED` on the **first** server. The controller gave up **431 ms** after a native
   attempt had started and **7.7 s before** the stack produced its own verdict
   (`VPNCONNECT watermark failed — server may not be SoftEther`). The endpoint was still being worked
   on; the terminal ended the run first. Evidence: PHYSICAL DEVICE.
2. **The root cause.** `awaitArmedOutcome` could not tell two situations apart. `isNativeSlotBusy()`
   answers `true` both when the start was **refused** because a *previous* operation owns the slot
   (case 2 — the server was never contacted) and when **our own** attempt has timed out and its
   native operation is still unwinding (case 1 — the server *was* contacted). Every case-1 timeout
   was therefore classified `NATIVE_BUSY`, and the `NATIVE_BUSY` branch's slot-wait expiry was a run
   terminal. Evidence: SOURCE CODE + PHYSICAL DEVICE.
3. **Fix-1 (adapter) — classify on the refusal, not on the slot.** `AndroidConnectionAdapter` gains
   `lastArmedBusyRefused`, a per-attempt `@Volatile Boolean` reset in `armTunnelWatch`
   (`AndroidConnectionAdapter.kt:349`) and captured in `awaitArmedTunnel`'s `finally` (`:406`) from
   `armedNativeBusy?.isCompleted`. That deferred completes **only** through
   `TunnelStateWatcher.onSoftEtherNativeBusy`, which the service fires **only** when it refuses a
   start, so its completion is an exact discriminator. `awaitArmedOutcome` (`:497`) returns
   `NATIVE_BUSY` iff the refusal fired and `TIMED_OUT` otherwise. `isNativeSlotBusy()` is kept — it
   still serves the admission checks — but no longer decides the outcome. Evidence: SOURCE CODE.
4. **Fix-2 (controller) — a slot-wait expiry moves on.** In **both** the `NATIVE_BUSY` branch
   (`AutoModeController.kt:1774`) and the post-attempt branch (`:1991`) the terminal
   (`giveUpWithUnresolvedNativeOperation()` + `Error(ERROR_NATIVE_UNRESOLVED)` + `job = null` +
   `return`) is replaced by the move-on log, the pre-existing diagnostics, `adapter.disconnect()` and
   `break`. The run therefore advances to the next server and pays at most `1T` for the expiry.
   `NATIVE_SLOT_AWAIT_MS`, `NATIVE_SLOT_AWAIT_MAX_MS` and the v1.0.36 caps are unchanged. Evidence:
   SOURCE CODE.
5. **Fix-3 (contract) — `ERROR_NATIVE_UNRESOLVED` is no longer a terminal.** The constant is kept (the
   adapter's KDoc and §1.6.24–§1.6.28 reference it) but no path publishes it. The only exhausted-list
   terminal is `ERROR_NO_SERVER` (§6.8.4), which is what the user is told once every candidate has
   been tried. `giveUpWithUnresolvedNativeOperation()` is kept, now unreferenced, so the device
   evidence it encodes (v1.0.32 §V3) is not silently deleted. Evidence: SOURCE CODE.
6. **Eight tests rewritten, none deleted, under explicit operator authorization.**
   `AutoModeControllerTest`: `aSlotThatNeverReleasesMovesOnToTheNextServer` and
   `aTimedOutWedgeAccumulatesAcrossRunsAndExcludesTheServer`. `AutoModeNativeBusyTest`:
   `native-busy refusal does not stop the run and does not record a false wedge`,
   `a slot that never releases does rotate to the next server`,
   `native-busy refusal advances past the refused protocol instead of retrying it`,
   `a single native-busy refusal does not immediately report ERROR_NO_SERVER`,
   `a busy refusal on the last server is reported as list exhaustion`,
   `a slot-wait timeout does not issue a recovery teardown`. Each keeps its original intent (the
   refusal must not become a verdict about the server) and pins the new contract; none is a trivial
   pass. One naming deviation is recorded: the brief proposed `…MovesOnToTheNextProtocol`, but the
   controller's `break` leaves the **protocol** loop, so the rotation continues at the next
   **server** — the test is named for what the code does. Evidence: TEST.
7. **Four new tests.** `anOwnOperationTimeoutIsClassifiedAsTimedOutNotBusy` (T-NEW-18: a timeout
   walks the protocols, records one wedge, never enters the refusal path),
   `a slot-wait timeout moves on to the next server instead of terminating` (T-NEW-19: pins the exact
   move-on line), `every refusal is absorbed and the list ends with ERROR_NO_SERVER` (T-NEW-20:
   twelve candidates, one terminal), and
   `skipWhileConnectedDialsTheNextServerAndNeverRestartsFromTheTop` (T-NEW-21: OP-R6, asserts the
   **order** of dials, not only the counts). Evidence: TEST.
8. **The hidden contradiction, resolved.** §6.8.4 has said "AutoConnect does not stop for
   `NATIVE_BUSY`" since v1.0.31, while `AutoModeNativeBusyTest.kt` pinned the **opposite** since
   v1.0.28 — a refusal terminal, no rotation, exactly one recovery teardown, zero disconnects. Both
   passed because the code implemented the tests, not the contract. v1.0.37 makes the code and the
   tests agree with §6.8.4. Evidence: SOURCE CODE + TEST.
9. **JVM tests and build.** `:app` **812 / 0 / 0 / 1** (baseline 808 / 0 / 0 / 1 — the delta is
   exactly the four new tests; the eight rewrites change no count and the skip is unchanged);
   `:SoftEtherClient` **173 / 0 / 0 / 0** (unchanged). `:app:assembleProDebug --rerun-tasks
   --no-build-cache` under `CI=true` → BUILD SUCCESSFUL. APK SHA-256
   `4FE4417469C7C7829DED3C7B723705DC77678B28FAA14345E057108BE9EB6E68` (63,755,062 bytes). Evidence:
   TEST.
10. **What remains UNVERIFIED.** Fix-A (the native RUDP handshake bound) is still **UNVERIFIED** and
    untouched. The three fixes here are SOURCE CODE + TEST only — no device run was performed in this
    phase (the brief is device-free), so the corrected classification and the no-terminal contract
    have not yet been observed on hardware. A device re-run of the v1.0.36 §7.1 scenario is the next
    evidence layer. Evidence: UNKNOWN.
11. **No new methodology lessons.** Bullet 8 is an instance of the rule already recorded in §1.6.24
    bullet 8 — a passing test proves only that the code matches the test — and is not restated.

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

### 2.6 Local Mirrors / Working Copies

The canonical repository identity is `SoftEthergai` (a Gradle multi-module Android project). It is a
single logical repository with a single `origin/main`; local filesystem paths are machine-specific
and are not part of the repository's identity.

**Current working copies in active use:**

| Path | Purpose | Status |
|------|---------|--------|
| `D:\PMC\Documents\Android\SoftEthergai` | Original path (historical). Suffers severe I/O slowness on that machine — `gradlew help` measured ~22 minutes vs ~7 seconds on a healthy SSD. | Read-only backup preferred; not used for active builds. |
| `C:\Dev\SoftEthergai` | Current working copy for development and builds. Moved on 2026-10-14 after the D:-drive I/O problem was isolated. | Active. |

**Rule:** documentation and commit messages refer to the canonical repository name and to
`origin/main` — never to a machine-specific filesystem path — unless the machine-specific path is
itself the subject of a report.

**Reason for the move (historical record):** the D: volume showed pathological I/O latency. A cold
machine restart did not fix it. A bounded diagnostic isolated the failure to AAPT2/Kotlin daemon
exits during build execution. Moving the working copy to C: resolved it: the same commands that hung
on D: for 20–45 minutes completed on C: in seconds to minutes. The D: copy is retained as a fallback
backup.

Evidence class: PHYSICAL DEVICE (measurements) + SOURCE CODE (paths).

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
- `NATIVE_TIMEOUT_FRACTION_NUM = 4L` / `NATIVE_TIMEOUT_FRACTION_DEN = 5L` (`AndroidConnectionAdapter.kt:74-75`) — the native connect budget is **80 % of the per-attempt budget `T`**.
- `MIN_NATIVE_CONNECT_TIMEOUT_MS = 1_500L` (`AndroidConnectionAdapter.kt:84`) — floor under that fraction, so a very small `T` still gives the native stack a usable window.
- `NATIVE_SLOT_AWAIT_MS = 30_000L` / `NATIVE_SLOT_AWAIT_MAX_MS = 60_000L` (`AutoModeController.kt`) — how long a NATIVE_BUSY refusal waits for the process-wide slot. Production derives the wait as `5 × T`, capped at the max.

> **Two-layer timeout (v1.0.31).** The same preference drives two independent bounds: the coroutine-level armed `select` wait is **100 % of `T`** (`AndroidConnectionAdapter.awaitArmedTunnel`), and the native connection race is **80 % of `T`** (`ConnectionConfig.connectTimeoutMs` → `ConnectionController.client.setTimeout`). The split is deliberate: the native verdict must arrive *before* the controller stops waiting, so an unresponsive server is reported as a native failure and the process-wide slot is released before rotation runs into NATIVE_BUSY. An attempt that cannot finish its handshake in 80 % of the window could not have produced a verified tunnel inside the full window either. Before v1.0.31 both layers used the identical figure, so the two deadlines coincided and the outcome was decided by whichever timer fired first.

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
  - **Default: 12 seconds per protocol attempt** (v1.0.31). The value lives in the `SETTING_AUTO_MODE_TIMEOUT_SECONDS` preference, read by `DataUtil.getAutoModeTimeoutSeconds()` and clamped to **5–60 s**; the Settings picker offers exactly that range. `AutoModeController.DEFAULT_ATTEMPT_TIMEOUT_MS` is the same 12 s, so the documented default, the shipped default and the pure-JVM fallback are one number (they were three before v1.0.31 — see §1.6.23).
  - If the user has enabled multiple protocols, each protocol attempt has its **own independent** timeout window (a server that fails on protocol A within `T` does not consume protocol B's `T` budget).
- **Stop condition:** If the armed tunnel wait (`awaitArmedTunnel`, §6.4) does not complete within `attemptTimeoutMs`, the attempt is declared failed and the next (server, protocol) pair is tried.
- **Fallback path / selection:** UDP-only servers (`seTcpPort=0`, `seUdpSupported=true`) must **NOT** be selected for `SOFTETHER_UDP` — per the §6.2 endpoint rule and `SERVER_SOURCE_ARCHITECTURE.md` §3.5. Selection filtering (not timeout) handles them. (This is the BUG 1 fix point.)
- **Testable:** With default Settings, each protocol attempt is bounded by ~12 s (`T`); the documented implementation must apply `attemptTimeoutMs` to **both** the armed `select` wait (`awaitArmedTunnel`, 100 % of `T`) **and** the native race (`ConnectionConfig.connectTimeoutMs`, **80 % of `T`** — see §6.8), not merely log it.

> **Cross-reference:** `DEFAULT_ATTEMPT_TIMEOUT_MS` (§10.1, §17) is the per-attempt bound. **v1.0.31 supersedes the earlier 5 s figure:** the shipped default is **12 s**, and the constant — which read `25_000L` and agreed with neither figure — was corrected to `12_000L`. The timeout is plumbed **per-protocol**. The BUG 3 deviation (timeout logged but not applied to the armed `select`, and not passed to the native race) was closed in **F.28AL-AUTOCONNECT-BUG-FIX**; §6.8 records the resulting two-layer enforcement and the real per-server time bound.

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
- **Stop condition:** AutoConnect does not stop on a single server failure, on a NATIVE_BUSY refusal (§6.4 — it continues without rotating), or on any timeout short of exhausting the list. It **does** stop when one of the following **system-level terminals** is reached — none of them is a server verdict, and each is recorded in §6.8:
  - **(c)** No protocol is enabled — `ERROR_NO_ENABLED_PROTOCOL`, refused before any attempt is armed.
  - **(d)** The process-wide native slot was never released — `ERROR_NATIVE_UNRESOLVED`. This is the fail-closed terminal: the application's own previous native operation never finished, so continuing could only stack refusals.
  - **(e)** VPN permission is missing — `ERROR_VPN_PERMISSION`.
  - **(f)** The reachability pre-pass (R3) definitively found every candidate unreachable — reported as `ERROR_NO_SERVER`.
  Before v1.0.31 this clause asserted there was **no** other implicit stop condition. That was false: the four above existed in code and were undocumented, so a reader of this contract would have expected a run to continue in cases where it terminates.
- **Fallback path:** On stop-condition (a), AutoConnect returns to idle with no active tunnel. On (b), the in-flight attempt is cancelled and the tunnel torn down.
- **Testable:** Given a list of N unreachable servers, AutoConnect attempts all N (in sort order) and only then reports `ERROR_NO_SERVER`; a second button press during the run cancels it. The elapsed time is **nominal** `N × T`, not a hard bound — §6.8 gives the real formula.

> **Cross-reference:** `endRun(ERROR_NO_SERVER)` (§6.3). This requirement adds the **second stop condition (user re-press)**, which was previously undocumented.

#### R8. Automatic Reconnection in Auto Mode (New Setting)

- **Full settings requirement:** see **§11.13** — finalized name `Auto-Reconnect in Auto Mode` («اتصال همیشکی اتوکانکت»), default **ENABLED (فعال)**, and enabled/disabled behavior.

---

### 6.8 Per-Attempt Timeout and Rotation Contract

> **Scope:** §6.7 documents the **button** contract (what a press does, what the user sees). This section documents the **attempt loop** underneath it: how long one attempt may take, what happens on each outcome, when rotation advances a protocol versus a server, and when the run terminates. Together they are the complete normative description of AutoConnect behaviour.
>
> **Evidence class:** **USER-DIRECTED REQUIREMENT** for the timeout and rotation semantics (operator rules R1–R5, restated here as a single table); **SOURCE CODE** for the enumerated outcomes and constants, which are read from HEAD `c5fa436` + the v1.0.31 fixes. Every clause below is either asserted by a JVM test or explicitly marked as device-dependent.

#### 6.8.1 The timeout `T`

`T` is the per-attempt budget for **one (server, protocol) pair**.

| Property | Value |
|----------|-------|
| Preference key | `SETTING_AUTO_MODE_TIMEOUT_SECONDS` |
| Reader | `DataUtil.getAutoModeTimeoutSeconds()` |
| Default | **12 s** |
| Range | **5–60 s**, clamped on both read and write |
| Settings UI | single-choice picker, values `(5..60)` |
| Pure-JVM fallback | `AutoModeController.DEFAULT_ATTEMPT_TIMEOUT_MS = 12_000L` |

All three figures are the same number. They were **not** before v1.0.31 (25 s / 12 s / 5 s); §1.6.23 records the reconciliation.

#### 6.8.2 Loop nesting

The loop is **server-outer, protocol-inner** — a nested `for`, not a flat product:

```
for (server in servers[resumeFromIndex …])          ← outer: the sorted candidate list
    for (protocol in usableProtocols(server, priorities))   ← inner: user priority order
```

Consequences, all normative:

1. Each protocol of a server gets its **own independent** window of `T`. A timeout on protocol A does not consume protocol B's budget.
2. A server's remaining protocols are tried **before** the next server is considered.
3. The maximum number of attempts is `N × M` where `N` is the candidate count and `M` the user's enabled-protocol count — but only the eligible subset is dialled (`usableProtocols` = `supports` ∧ `isAutomatable`), so the real count is usually lower.

#### 6.8.3 Transition table

Every way an attempt can end, and where it goes next. "Next protocol" means the next entry of `usableProtocols` for the **same** server; "next server" means the next entry of the outer list.

| Outcome | Meaning | Transition |
|---------|---------|-----------|
| `TUNNEL_UP` | A verified tunnel is up. | **Success** — the run ends `Connected`; no further attempts. |
| `TIMED_OUT` | `T` expired with no tunnel, no refusal, no terminal failure. **Includes case 1 (v1.0.37):** the attempt *did* reach the server and its own native operation was still unwinding when `T` expired — a timeout, not a refusal (§1.6.29 bullet 2). | **Next protocol.** Never retried on the same server. This outcome records a "wedge" against the server (once per server per run). |
| `NATIVE_ERROR` | The stack reported a terminal failure **before** `T` expired (refused port, rejected handshake, a non-SoftEther answer to the VPNCONNECT watermark). | **Next protocol.** The slot is already free, so the post-failure slot wait is skipped. |
| `NATIVE_BUSY` | The start was **refused** because this application's own previous native operation still holds the process-wide slot. The server was **never contacted**. **Classification (v1.0.37):** only a genuine refusal is `NATIVE_BUSY`. An attempt whose **own** operation is still unwinding when `T` expires is `TIMED_OUT` — see that row and §1.6.29. | **Own-tunnel check (v1.0.36):** if `isOwnTunnelEstablished()` is true the attempt is **Connected** — no wait, no retry, no wedge. Otherwise: wait for the slot to be released (up to `min(nativeSlotAwaitMs, T)`), then **retry the same protocol once** — skipped when the previous teardown was `NATIVE_UNRESOLVED` (§1.6.27). If the wait **expires** (v1.0.37): log `Slot-wait expired on <protocol>; moving on (no terminal, per §6.8.4)`, issue one `disconnect()` and **advance to the next server**. A refusal is never a run terminal. **Repeated** refusals on the same server in the same run record a wedge — see below. |
| **Thrown exception** in `connect()` | The adapter could not even dispatch the attempt. | **Next protocol** (v1.0.31). Before v1.0.31 this jumped to the next **server**, so a server that threw on its first protocol never got its second — the same failure rotated at two different granularities depending on where it was raised. |
| `VpnPermissionMissingException` | The OS refused the VPN permission. | **Terminal** `ERROR_VPN_PERMISSION`. |
| User skip | "Try next server" while connecting. | **Next server** (deliberately skips the current server's remaining protocols — the user asked for a different server, not a different protocol). |

Two asymmetries are intentional and are part of this contract: a user skip advances a **server**, and a `NATIVE_BUSY` refusal advances **nothing** until either the retry has been given its chance or the slot wait expires — and when it expires (v1.0.37) it advances a **server**, never the run.

**The post-release retry gate (added v1.0.35).** The "retry the same protocol once" transition above is now conditional on the teardown that preceded the refusal. The stack reports four teardown outcomes and only `NATIVE_UNRESOLVED` retains ownership and makes the registry refuse every later start; `NATIVE_CONFIRMED` released the structures, `NOOP_REENTRANT` published a terminal state for a session with nothing left to release, and `FOREIGN_STACK_TERMINAL` asserts nothing about native cleanup. The controller therefore consults `ConnectionAdapter.lastTeardownWasResolved()` — false only for `NATIVE_UNRESOLVED` — and, when it is false, logs `Skipping retry on <protocol>: previous teardown was unresolved` and advances directly to the next protocol. A timeout in `disconnect()` (unknown state) keeps the pre-v1.0.35 retry. Rationale and device evidence: §1.6.27.

**The own-tunnel check (added v1.0.36).** Before the wait, the branch asks
`isOwnTunnelEstablished()`. If it answers `true` the attempt is treated as **Connected** — the
controller does not wait, does not retry and records no wedge. This closes the race in which
`awaitArmedOutcome`'s timeout fires a few hundred milliseconds **before** the `CONNECTED`
notification, so `isNativeSlotBusy()` is still true because *our own* operation is still running:
the controller would then hold a slot belonging to the very tunnel it had just established, and the
terminal would destroy a live tunnel. The same check has always existed on the post-attempt path;
it was simply missing from this branch. Device evidence and the full fix: §1.6.28.

**The wedge rule (added v1.0.32, BUG-B).** A wedge is one strike against a server in the
session ledger (`WedgeTracker`); `WedgeTracker.EXCLUSION_THRESHOLD` strikes exclude it for the rest
of the process. Two outcomes write a strike, at most one per server per run:

1. `TIMED_OUT` — the attempt reached the server and nothing came up inside `T`. (Unchanged since
   the AUTOCONNECT-AUDIT D-5 revision.)
2. **Repeated `NATIVE_BUSY` on the same server in the same run — threshold 2**
   (`AutoModeController.NATIVE_BUSY_WEDGE_THRESHOLD`). One refusal is routinely caused by the
   *previous* server's still-running native operation, so a single strike would be evidence about
   the wrong endpoint; the second refusal on the same server inside one run — after the slot was
   reported released in between — cannot be explained that way and is evidence about this
   endpoint. Before v1.0.32 no refusal path reached the ledger at all, so a server that was
   refused on every protocol could never be excluded and was re-selected on every subsequent run
   (§1.6.24 bullet 2).
3. ~~**Terminal wedge (v1.0.34).**~~ **Withdrawn in v1.0.37.** There is no `NATIVE_BUSY` terminal any
   more, so nothing can be wedged on one. The strike existed on the premise that a stuck slot is
   evidence about the **endpoint**; the v1.0.36 device run disproved that premise — the endpoint was
   being contacted and the stack reached its own verdict 7.7 s after the controller gave up
   (§1.6.29 bullet 1). A server that is genuinely unreachable now accumulates strikes through
   `TIMED_OUT`, the outcome that does implicate it, so cross-run exclusion still works and is pinned
   by `aTimedOutWedgeAccumulatesAcrossRunsAndExcludesTheServer`. Evidence: SOURCE CODE + TEST.

A `NATIVE_ERROR` never records a strike (the endpoint answered and is cheaply recoverable). The
**exclusion** threshold is unchanged at 2 — v1.0.32 only made a refusal *countable*, it did not
make exclusion easier to reach.

**The native timeout invariant (added v1.0.34).** Both TLS handshake paths are bounded: the TCP
path at `softether_protocol.c:2839` and the NAT-T/RUDP fallback at `:2908` both call
`socket_apply_timeout()` before `perform_tls_handshake()`. Before v1.0.34 only the TCP path was
bounded; the RUDP BIO read could block indefinitely, producing an unbounded stall that no
slot-wait budget could recover from, because the first `SSL_do_handshake` never returned and the
`max_attempts` loop therefore never iterated. Evidence: SOURCE CODE
(`DEVICE_VERIFY_AUTOCONNECT_FIX_3_REPORT.md` §7.3 for the observed asymmetry).

#### 6.8.4 Terminal conditions

A run ends — and the button stops meaning "stop" — in exactly these cases:

**AutoConnect does not stop for `NATIVE_BUSY`** (v1.0.37). A refusal, or a slot wait that expires
after one, moves to the next server; it is never a run terminal. The only exhausted-list terminal is
case 1. `ERROR_NATIVE_UNRESOLVED` is therefore no longer published by any path — the constant is kept
because the adapter's documentation and the v1.0.32–v1.0.36 history reference it (§1.6.29 bullet 5).

| # | Terminal | State |
|---|----------|-------|
| 1 | Candidate list exhausted | `Error(ERROR_NO_SERVER)` |
| 2 | Empty candidate list, before any attempt | `Error(<"update the server list first" message>)` |
| 3 | No protocol enabled by the user | `Error(ERROR_NO_ENABLED_PROTOCOL)` |
| 4 | Reachability pre-pass found every candidate unreachable | `Error(ERROR_NO_SERVER)` |
| 5 | VPN permission missing | `Error(ERROR_VPN_PERMISSION)` |
| 6 | User presses the button while `Connecting` | `Disconnected` (cancel + teardown) |
| 7 | User presses the button while `Connected` | see §6.8.5 |

Cases 1–5 are the "system-level terminals" referenced by §6.7 R7. None of them is a server verdict.

#### 6.8.5 Pressing while `Connected` — continue from the next server (OP-R6)

A press on a **live tunnel** means "move on", not "start over". The controller records the position after the server that connected and starts a fresh run from there:

```
resumeFromIndex = currentServerIndex + 1   // index into the previous run's resolved list
disconnectNow()                            // teardown requested synchronously
start()                                    // next run consumes resumeFromIndex once
```

Normative details:

- The continuation is **consumed exactly once**, at the top of the run, and zeroed there. An internal restart the user did not press — the §11.13 Auto-Reconnect path calls `start()` directly — must never inherit a stale index and silently skip servers.
- A fresh press from `Disconnected`/`Error` zeroes the position, so a later run always starts at the top again.
- If the position is `>= servers.size` (the user pressed while connected to the **last** server), the run starts no attempt and terminates with `ERROR_NO_SERVER`. It does **not** wrap around to index 0.
- The index refers to the **previous** run's resolved (filtered + sorted) list — the same list `currentServerIndex` reports. A re-sorted list can therefore shift the position by one or more places. This is accepted: pinning a server identity instead would silently skip a candidate that has since become better, and the ordering is the contract (§6.6).
- Teardown ordering: `disconnectNow()` enqueues the teardown request synchronously before publishing `Disconnected`, and the new run resolves the server list and runs the pre-pass probe before its first `connect()`. The DISCONNECT therefore reaches the service ahead of the new run's first `ACTION_CONNECT` without making the (synchronous) button handler suspend.

#### 6.8.6 The `isTunnelBusy()` guard

A press taken from `Disconnected`/`Error` is **refused** — with a log line, and with no state change — when a tunnel that this run does not own is active or still coming up:

```
[AUTO] start refused: a tunnel is already active
```

This covers a manual connection and a manual connect that is still in flight. It is the fail-closed choice: AutoConnect must never dial a second, competing tunnel. Before v1.0.31 the guard existed only in `startOrRefuseIfNoServers()`, which has no production caller, so the production button path dialled regardless.

**The predicate (clarified v1.0.32, BUG-A).** "Busy" here means *this process already owns a
tunnel, or is acquiring/releasing one*. All three of the following count:

| Status | Counts as busy? | Why |
|--------|-----------------|-----|
| `CONNECTED` | **yes** | an externally established tunnel — the single most common shape of the hazard |
| `CONNECTING` | yes | a manual connect still coming up |
| `DISCONNECTING` | yes | a half-closed tunnel is not free to take over; a new native attempt would race the teardown |
| `DISCONNECTED`, `ERROR` | no | nothing is owned |

The reader is `GlobalVpnTracker.isActiveOrBusy`. The narrower `GlobalVpnTracker.isBusy` —
`CONNECTING || DISCONNECTING` — is **not** sufficient and must not be used for this guard: it was,
in v1.0.31, and the consequence was that a cleanly established manual tunnel was invisible to the
guard, the run started anyway, and it tore the user's manual session down (§1.6.24 bullet 1).

**Scope of the guard.** It applies to the `Disconnected`/`Error` branch only. A press taken while
Auto Mode is itself `Connected` is the OP-R6 path (§6.8.5) and is **not** gated by this predicate —
otherwise the "move on" button would refuse to move on.

> §6.7 R2's "disconnect the current tunnel first, **then** proceed" describes the **Auto Mode-owned** tunnel case, which §6.8.5 now implements. The manual-tunnel case is a **refusal**, not a takeover: AutoConnect does not tear down a connection the user started outside it.

#### 6.8.7 Real time bound per server

`N × T` is the **nominal** bound, not a hard one. The per-attempt worst case is:

```
T                                  the armed wait (100 % of T)
+ min(5T, 60 s)                    the post-failure slot wait, when the native slot is held
```

and a `NATIVE_BUSY` refusal can additionally spend up to `min(5T, 60 s)` waiting **before** its single retry, so one (server, protocol) pair can cost up to `6T` in the pathological case. With the 12 s default:

| Case | Per protocol | Per server, 2 protocols |
|------|--------------|--------------------------|
| Healthy / slot free | `T` = 12 s | 24 s |
| Failed, slot held | `T + T` = 24 s | 48 s |
| NATIVE_BUSY then retry | `3T` = 36 s | 72 s |

*(The two "slot held" rows show the v1.0.36 capped figures; before v1.0.36 they read `T + 60 s` = 72 s and `6T` = 72 s respectively. See the cap note below.)*

The slot wait returns **immediately** when the slot is already free, so the healthy path pays nothing and the nominal bound holds in every normal run. §6.7 R7's "attempts all N" is about **count**, not elapsed time.

> **Not device-verified.** The wall-clock behaviour of these bounds on hardware is **UNKNOWN** in this revision: the JVM adapter doubles return an outcome immediately, so no test measures elapsed time. Device verification is proposed in `AUTOCONNECT_FIX_REPORT.md` §12.

**Slot-wait cap (v1.0.36).** Both slot waits — the one inside the `NATIVE_BUSY` branch and the
post-attempt one — are now bounded by `min(nativeSlotAwaitMs, attemptTimeoutMs)` instead of by
`nativeSlotAwaitMs` alone. With the device's `T = 5 s` and the 30 s floor, the old figure turned one
refused (server, protocol) pair into `5 s attempt + 30 s wait + 5 s retry` = **7×T** before the
rotation even started; the cap brings that to **3×T**. Per-protocol time after v1.0.36:

| Case | Bound | Note |
|------|-------|------|
| Own tunnel came up (Fix-1) | **`1T`** | No wait, no retry, no wedge. |
| Previous teardown unresolved (v1.0.35 gate) | **`2T`** | Attempt + capped wait; the retry is skipped. |
| Previous teardown resolved, retry fires | **`3T`** | Attempt + capped wait + retry. |

Before v1.0.36 the same three cases were `1T`, `7T` and `7T`. The first was **reachable** but the
`NATIVE_BUSY` branch never actually checked it — which is exactly the defect §1.6.28 records.
Evidence: SOURCE CODE + PHYSICAL DEVICE.

**Slot-wait budget floor (v1.0.33).** `nativeSlotAwaitMs` is derived as `max(5 × T, 30 s)` and then
capped at 60 s. The floor exists because the release latency of a previous native operation is a
function of *that operation's own duration*, not of the user-selected attempt timeout; with
`T = 5 s` the derived value was 25 s, while device data
(`DEVICE_VERIFY_AUTOCONNECT_FIX_2_REPORT.md` §7) recorded release latencies up to **20.9 s** — a
4.1 s margin — and one release arrived **292 ms after** the cutoff, terminating the run while the
slot was about to free itself. The floor therefore changes the `min(5T, 60 s)` term above to
`min(max(5T, 30 s), 60 s)`; for every `T >= 6 s` the figure is identical to before (`5T`), so the
table above is unchanged at the 12 s default. Evidence: SOURCE CODE + PHYSICAL DEVICE.

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

**File:** `app/src/main/java/vn/unlimit/vpngate/models/VPNGateItem.kt`

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

> **Currency note (added v1.0.29):** The pipeline below describes the fetch/merge core and is still accurate, but it is no longer the whole update chain. Since this section was written, the chain gained (a) OPEN-first / VPN-fallback path selection with an opt-in "always prefer VPN" (`productionRouting()` / `resolvePrimary()` / `fetchOnChosenPath()`; §11.8.2, §1.6.20), (b) user-configured emergency CSV sources as the last automatic rung (§11.9, §1.6.14), and (c) manual server records merged into the stored dataset (§11.10, §1.6.19). The fallback chain and snapshot discipline below are unchanged.

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

        /**
         * The protocol [id] names, or null when it names none.
         *
         * v1.0.31: corrected in this document to match HEAD. Before v1.0.31 this
         * block showed a **non-nullable** `fromId` that fell back to
         * `SOFTETHER_UDP`, which is not what the code does and not what it
         * should do: an unknown or absent id must read as "nothing selected",
         * not as a protocol the user may not have enabled. The default belongs
         * to the setting, never to the parser.
         */
        fun fromId(id: String?): AutoModeProtocol? =
            entries.firstOrNull { it.id == id }
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

### 11.8.2 Implementation status

> **HISTORICAL — superseded (annotated in v1.0.29).** The observation below was true when written (pre-v1.0.12). It no longer describes the current code: per-request VPN-network binding landed in `a9e16fd` (PART B, recorded v1.0.12–v1.0.13, §1.6.10 bullet 8, §1.6.13 bullet 2), and the OPEN-first / VPN-fallback routing order landed in `c4c0311` (v1.0.27, §1.6.20) on top of the dynamic path selection recorded in §1.6.13. Current state at HEAD `df7b49c`: `VpnServerRepository.productionRouting()` / `resolvePrimary()` choose the primary rung (OPEN by default; VPN only when the `vpn_always_prefer_vpn` opt-in is set), `fetchOnChosenPath()` falls back to the other rung when reachable, and `HttpFetcher.kt` binds per-request via `Network.getSocketFactory()` (`boundToSocketFactory`) — never `bindProcessToNetwork()`. The original observation is preserved verbatim below for traceability.

*Original observation (historical):* Source audit of the existing server-list HTTP path (`HttpFetcher.kt`, `VpnServerRepository.kt`) found no explicit VPN-network binding — no `ConnectivityManager.bindProcessToNetwork`, no `VpnService.protect()` in the update path, and no explicit `Network` selection on the OkHttp client. The `ResilientDns` class (`HttpFetcher.kt:53`) provides DNS-resilience fallbacks for the vpngate.net domain common in censored environments, but does not bind the HTTP client to the VPN tunnel.

### 11.8.3 Existing path statements in this document

The existing server-collector description in §8 (especially §8.1) documents the original behavior where collection requests ran on the default Android network path; §8.1 is annotated accordingly (v1.0.29). No statement in this document currently asserts that server-list requests must permanently use only the direct path or only the VPN tunnel. Any such future observation must be preserved as current behavior and reconciled with the dynamic-path requirement rather than removed.

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

> **Safety-set status (v1.0.21 — Z1-IMPL-C):** the five items above now have an implementation for
> the emergency path specifically, and the closing sentence is enforced rather than intended. Invalid
> URLs are refused by `SourceUrl.parse` before any request is made; failed downloads and unsupported
> formats are mapped to a fall-through that leaves the stored list untouched; malformed data is the
> parser's `Malformed` / `Partial` split; duplicate records are collapsed by `VpnServerMerger`. The
> no-erasure guarantee is carried by `ServerListUpdater`'s single write path and by `DatasetIntegrity`,
> which refuses a dataset that regresses against the stored one. This note records the status of the
> requirements above; it does not alter them. Items D–H of §12.5.2 extend the same rules to manual
> entry and remain future work. Evidence: SOURCE CODE + TEST. See §1.6.14 bullets 3 and 7.
>
> **Test URL available (v1.0.24):** a real, publicly hosted `servers.csv` now exists at
> `https://raw.githubusercontent.com/morteza-taheri/SoftEthergai/main/emergency-sources/servers.csv`,
> providing a reproducible end-to-end test target for the parser and fetcher. It is a **3-row,
> point-in-time snapshot** captured from the live VPN Gate API + HTML, not a production list, and it
> will go stale as those relays come and go; `emergency-sources/README.md` documents how to
> regenerate it. See §1.6.17.

### 11.9.3 Format rules

Do not assume every URL points to CSV; format support must be explicit and validated. The initial planned emergency-source format is a standardized project CSV format, but its exact schema has **NOT** been defined yet.

**CSV schema status: DEFERRED / NOT YET DEFINED.** *(Superseded (v1.0.20): the schema is now defined by `SERVER_SOURCE_ARCHITECTURE.md` §10 and approved as the design basis — see the approval note at the end of this section. The wording above is retained verbatim as historical record.)*

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
> **Schema approval (v1.0.20 — Z1-IMPL-B):** the emergency CSV schema of **`SERVER_SOURCE_ARCHITECTURE.md` §10** is **APPROVED as the design basis** for the emergency-source format. This supersedes the "DEFERRED / NOT YET DEFINED" status above and, in substance, the "Explicitly NOT YET DEFINED" description of §12.5.2 item C — that description is left verbatim as historical text and is not edited by this revision. The CSV parser itself remains a **later phase** (roadmap item C); §12.5.2 item B landed configuration-only in v1.0.20 and fetches nothing. Evidence: SOURCE CODE. See §1.6.13 bullet 3.


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

> **Implementation status (v1.0.22 — Z1-IMPL-D):** manual server entry has now **landed**, so the
> "Database schema: do not design or implement a new database schema here" note above is
> superseded for the `manual_server` table only. Manual servers persist in their own table (migration
> 6→7, unique on `(host, port, protocol)`) and are **re-emitted on every accepted write** of the
> server list, which is what makes the first bullet above true — an update replaces the whole list,
> so without that re-emission a manual record would be deleted by the very refresh that succeeded.
> Provenance is distinct and retrievable: the record layer keys a manual record's authority under the
> `manual` tag, which is the **lowest** of all (`api > html > mirror > manual`, arch doc §3.3/§4.2).
> A manual record is never deleted, and a value both a manual entry and a collected record claim goes
> to the collected source with the disagreement recorded in the record's `conflicts` list rather than
> discarded (§38). Two limits of this phase are recorded in §1.6.15 bullet 9: `SourceIdentity.kt` was
> outside the authorized file set so `SourceKind.MANUAL`'s `sourceTag` still reads
> `mirror_unnumbered`, and two manual entries on one IPv4 collapse to one stored row because the
> table's primary key is the host name alone. See §1.6.15 for the full evidence set.
>
> **Implementation status (v1.0.23 — Z1-IMPL-G):** the first of those two limits is now **closed**.
> `SourceIdentity.sourceTag`'s `when` is exhaustive with no `else` branch, so `SourceKind.MANUAL`
> yields `MANUAL_TAG = "manual"` instead of falling through to `mirror_unnumbered` — and adding a
> future `SourceKind` without a tag now fails to compile rather than mislabelling records at
> runtime. That is what makes the `"manual" to 30` priority entry a live policy rather than dead
> code, and it satisfies this section's "Preserve source provenance" and "must carry distinct,
> retrievable provenance" bullets for the manual case end-to-end. The same revision adds the three
> `DatasetIntegrity` outcomes (`ACCEPT_NEW` / `MERGE_WITH_EXISTING` / `REJECT_DEGRADED`) as a
> **classification layered on the existing verdict**, with `evaluate`'s signature and every
> threshold unchanged — so *no acceptance or rejection decision moved in this revision*. The two
> cumulative outcomes are **names only** until Cumulative Server Retention (arch doc §9.1) is
> implemented, because that section requires the database to **grow** across updates while an update
> still replaces. Two limits remain from §1.6.15 bullet 9, both unchanged: two manual entries on one
> IPv4 collapse to one stored row, and an OpenVPN manual entry is not connectable without a profile.
> See §1.6.16 for the full evidence set, including the citation correction (the outcomes are defined
> in arch doc §9.6/§10.8, **not** §3.12) and the operator decision that overrode the phase brief's
> decision table.
>
> **Implementation status (v1.0.25 — MANUAL-UI):** the first bullet above ("manual servers must
> survive ordinary online server-list updates") is **true as written and now demonstrated on a
> device**, but it is weaker than it looks and the gap was mistaken for a rendering bug. Re-emission
> happens inside `replaceAll`, so a manual row is preserved *by the same write that would otherwise
> delete it* — which means a manual row is written into `VPNGateItem` **only when a refresh is
> accepted**. A newly added server is therefore **invisible in the main list until some later refresh
> is accepted**, and stays invisible indefinitely while refreshes are being rejected as degraded
> (`Preserved(reason=DEGRADED_DATASET)`), which is exactly what happened on this device at 12:20
> before a healthy refresh at 13:16 merged 106 + 1 = 107 rows. This is **not** a violation of any
> bullet here — nothing was deleted — but "survives an update" and "appears without an update" are
> different guarantees, and only the first is currently provided. Two further facts constrain any
> future fix: `VPNGateItem` has **no `protocol` and no `label` column**, so the merge writes the
> manual `label` into `operator` and the `port` into `seTcpPort` and leaves the protocol flags clear
> (§1.6.18 bullet 7); and a display-time merge of `manualServers` into the list would create a
> visible-but-not-stored state, which is why the operator deferred rather than patched it. Manual-row
> visibility is **deferred** and is the substantive input to a future phase. See §1.6.18 bullets 5–7
> for the evidence, including the correction to this revision's own initial diagnosis.
>
> **Implementation status (v1.0.26 — MANUAL-IMMEDIATE):** the gap described in the note above is now
> **closed**. `addManualServer` materialises the new row into `VPNGateItem` immediately, through
> `ManualServerStore.materialize` — which is the **same** `withManualRows` the refresh path uses, so
> the two paths cannot drift into different preservation policies. Both guarantees named above now
> hold: a manual server **survives an update** *and* **appears without one**. The preservation
> invariant is unchanged in substance (an address the network already serves is not duplicated, no
> other row is dropped, and the result is collapsed by Room identity); what changed is *when* the
> row is written, not *whether* it is protected. A rejected refresh is also no longer silent: a
> `DEGRADED_DATASET` outcome now raises a one-shot user-facing notice, while rejection itself stays
> fail-closed and every threshold is untouched. Two limits remain, both recorded rather than fixed:
> removing a manual server does not eagerly delete its materialised row (it goes at the next accepted
> refresh, §1.6.19 bullet 7), and because `VPNGateItem` still has no `protocol`/`label` column the
> merge still writes the label into `operator` and the port into `seTcpPort` (§1.6.18 bullet 7).
> Adding one row to the measured table also nudges `DatasetIntegrity`'s `sizeFloor` by 0.5, which is
> documented as a consequence rather than a policy change (§1.6.19 bullet 6). See §1.6.19 for the
> full evidence set, including the one brief requirement that proved not literally implementable.

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
| 1 | **FIX-NATIVE-LIFETIME-SYNC (N-15 + N-16)** | N-15: `rudp_destroy` (`rudp_destroy:181`) destroys `ctx->lock` and frees `ctx` with no guarantee a concurrent `rudp_poll` (`rudp_poll:693`) has returned — a poll in flight during destroy is a use-after-free. N-16: `nativeConnect` (`softether_jni.c:54`) is **not** serialised by `conn->connect_mutex` while `nativeConnectWithHub` (`softether_jni.c:139–145`) is — two concurrent native connects on the same `conn` can interleave. | **P1_HIGH** | **CLOSED for N-15 + N-16 — device-verified** (tests 37–41 PASS; commits `3a2f442`, `b0418cc`). See §1.6.1 for the commits. The **FINDING T5-SLOT** driver (§12.4) remains OPEN as a separate item (row 8) |
| 2 | **DEEP-REVIEW P0 set** | JNI bounds (N-01…N-05, N-08), RUDP lock release, reconnect terminal routing (G-1), teardown log identity (G-2) | — | **CLOSED — device-verified.** RESUME-BATCH-DEVICE-VERIFY: 6/6 native tests PASS, T2–T8 clean. Evidence: PHYSICAL DEVICE EVIDENCE |
| 3 | `proRelease` build/test path | The release variant has never been built or tested locally (only `proDebug`). Blocked by the RISK-A signing fallback (§12.4) and the absence of local release signing material. | Priority to be assessed | OPEN — see RISK-A |
| 4 | **N-06 / SEC-01** TLS certificate verification | `aes_wrapper.c:110` `ssl_configure_client_ctx` sets `SSL_CTX_set_verify(c, SSL_VERIFY_NONE, NULL)`; no trust store, no hostname binding, no pin; SNI is the resolved **IP** (`:424–428`). Deliberate for self-signed VPNGate certs, but a genuine MITM exposure. | P2_MEDIUM | **CLOSED (v1.0.16) — TOFU fingerprint pinning landed (commits `b652198`, `9954c03`, `ff78116`). Primary handshake DEVICE-VERIFIED (Phase 9/10); reconnect path fixed and covered by SOURCE CODE + 2 source-shape tests but NOT device-reproduced. See §1.6.9 for the explicit caveat.** (Supersedes the v1.0.14 "OPEN — design proposed" status; see §1.6.7, §1.6.8 bullet 10 and `docs/reports/AUDIT_SECURITY_AND_RISK_B_v1.0.14.md`.) Evidence: SOURCE CODE + PHYSICAL DEVICE + TEST |
| 5 | **N-09** non-cryptographic `rand()` | `softether_rudp.c:251–268` derives RC4 keys / `next_iv` / cookies from `rand()` (also `:838`, `:1018`, `:1048` for jitter/pad); `rudp_transport.c:316,370,402,731,760,777,840,850,1074,1151,1152,1183,1225` derive IV positioning / pad / ICMP-DNS ids / jitter, with `srand()` at `:1026`. The generator is not cryptographically secure. (The pre-v1.0.14 line refs were stale and are corrected here.) | P2_MEDIUM | **FIXED (v1.0.15) — device-non-observable; see §1.6.8 bullet 1. Commit `f66f464`.** Evidence: SOURCE CODE + TEST |
| 6 | **RISK-A** conditional release signing | See §12.4. | Priority to be assessed | **PARTIALLY MITIGATED (v1.0.17)** — the fail-closed decision described in §12.4 was taken and landed by `c572050` (v1.0.7): `app/build.gradle:88–116` now throws a `GradleException` for any release `assemble|bundle|package…Release` task when the signing material is absent, so a `proRelease` build can no longer silently fall back to debug signing. Verified firing in the IMPLEMENT-AND-VERIFY-P1-REMAINING phase (PART D): `:app:assembleProRelease` exits **1** with "Release build blocked: release signing configuration is incomplete" at `app/build.gradle:109`, while `:app:assembleProDebug` still succeeds. Still **OPEN** as a *build-availability* item: the local release path remains blocked because the keystore and `SIGN_KEY_PASSWORD` are absent (§12.4 F.28AL-X). The "CONFIRMED BY SOURCE AND OBSERVED IN PRACTICE" status for the *pre-fix* fallback is superseded by this note. Evidence: SOURCE CODE + TEST |
| 7 | **RISK-B** legacy `SoftEtherClient.disconnect()` bypass | See §12.4. | — | **CLOSED (v1.0.15)** — dead code deleted (v1.0.14 call-graph audit: unreachable body — `isConnected` never set true, `nativeHandle` never assigned non-zero — plus zero production/test callers and no reflection); see §1.6.8 bullet 3. Commit `1bbf8a6`. Evidence: SOURCE CODE + TEST |
| 8 | **FINDING T5-SLOT (holder-identity capture)** | Instrumentation landed in commit `5e51e37`; awaiting the next contention occurrence to capture the holder identity. | P2_MEDIUM (opportunistic; not blocking) | OPEN — instrumentation armed on device; holder **UNKNOWN** |
| 9 | **FINDING T5-SLOT-TEARDOWN residual (attempt=N stuck in TEARDOWN after success)** | Observed in the v1.0.10 device run: a holder logged `attempt=2 phase=TEARDOWN, reporting deadline expired (still blocked)` and blocked later attempts (`Start refused: native slot still held`) for ~1 min; FIX-2 fired 3× but no `FORCED_RELEASE_AFTER_TIMEOUT` fired. Capture the exact timing window in a future device run; confirm whether the 90 s TEARDOWN bound is reached before Auto Mode stops. See §1.6.4. | P2_MEDIUM (opportunistic) | **OPEN — scope narrowed by v1.0.11** (§1.6.5): the residual fired **7×** in the hardening device run and **self-healed in 7/7** (`Native cleanup confirmed … structure destroyed and slot released` within ~1 s, next attempt proceeded normally). The mechanism is now understood from source (a `NOOP_REENTRANT` completion for the same attempt id satisfies `TunnelStateWatcher`'s exact-attempt-id wait before the real `CONFIRMED` arrives — `AndroidConnectionAdapter.kt:226`, `SoftEtherVpnService.kt:1081–1106`, `ConnectionController.kt:937`). Still **UNKNOWN**: the upstream trigger for the re-entrant teardown call, and why the same log line corresponded to a ~1 s window in v1.0.11 but a ~1 min window in v1.0.10. Classification in `docs/reports/AUDIT_AXES_A_B_C_v1.0.11.md` §2 |
| 10 | **Axis A / B / C audit findings (v1.0.11, read-only)** | Three read-only axes audited; no source change authorised. **Axis A** (tunnel state / UI sync): two concurrent state models with no designated UI owner — `GlobalVpnTracker` vs `AutoModeController._state`/`AutoModeEngine.stateFlow`, both consumed in one composable (`HomeScreen.kt:125–126`); the 500 ms `onLost` delayed re-check; protocol egating in `GlobalVpnTracker.updateState` (a disconnect of one protocol cannot clear another protocol's state); three loss paths at different latencies in `startConnectedWatcher`. **Axis B** (server list network binding): no binding in the retrieval path (`HttpFetcher.kt` plain `OkHttpClient`; single instance at `VpnServerRepository.kt:40`); the `Network` captured in `GlobalVpnTracker.onAvailable` is discarded; and `isTunnelUsable()` is **not implementable** because `GlobalVpnTracker` has **no RX counter and no TUN-EIO counter** — a hard blocker on the already-decided bind design (`SERVER_SOURCE_ARCHITECTURE.md` §7 Q1–Q3, USER-DIRECTED REQUIREMENT: per-request `Network.getSocketFactory()`, never `bindProcessToNetwork()`; open → VPN fallback → emergency → manual). **Axis C** (SoftEther UDP / TUN `EIO`): `udp_only` servers run a parallel race with a structurally ~0% success rate per `RUDP_IMPLEMENTATION_PLAN.md` (measured: full 5000 ms deadline consumed; OpenVPN fallback is Open Item #1, unstarted); TUN `EIO` has **no branch** in `TunTerminal.readLoop()` (only `EBADF` is special-cased) so a revoked interface funnels into `triggerReconnect()`; `MAX_PACKET_SIZE = 32767` is a dead constant while the read buffer is 8192 (not a live defect — TUN MTU is 1500). | P2_MEDIUM (opportunistic; not blocking) | **PARTIALLY CLOSED (v1.0.17)** — the four **Axis A** items are landed by commit `067d4a3` (A-2 `onLost` re-check budget 500 ms → 50 ms; A-3 protocol egates replaced by `VpnStackLedger.reconcile`; A-4 single `reportArmedTunnelLoss()` funnel; A-5 `Disconnected` published after the OQ decision), each with JVM test coverage; see **§1.6.10**. Axis A's device verification is **PARTIAL** — one ANR was observed and is **NOT REPRODUCED**, registered separately as **row 16**. **Axis B** is closed by `a9e16fd` (PART B, §1.6.10 bullet 8) with the honest limitation that nothing calls `noteRxAdvance()` from the data-forwarding path, so `isTunnelUsable()` can only ever report `false`. **Axis C** is closed by `607c651` (PART C, §1.6.10 bullet 8). The full register (A-1..C-4, cross-axis matrix, dependency-ordered recommendations) remains in `docs/reports/AUDIT_AXES_A_B_C_v1.0.11.md` §7–§8 |
| 11 | **OPEN-1 — `VpnServerRepositoryR4bTest.logs` unsynchronized under concurrent fetch** | The test's `pathLog` collector was a plain `ArrayList`, written by **two concurrent `async` coroutines** (`VpnServerRepository.refresh` runs the api and html fetches in parallel, §8.1). A concurrent `add` can drop a line, so the `vpn path is chosen when every input holds` assertion could see a short list and fail intermittently (the observed symptom was a missing `[collector] path=vpn source=html`). **Test-only defect** — no production code was involved. | P2_MEDIUM | **CLOSED (v1.0.12)** — collector changed to `CopyOnWriteArrayList` (assertion logic unchanged); deterministic regression test `vpn-path-logs-are-never-lost-under-concurrent-fetch` added (50 concurrent runs, exact per-line counts). See §1.6.6. Evidence: SOURCE CODE + TEST |
| 12 | **OPEN-2 — `AutoModeControllerTest.awaitTerminal()` unbounded wait** | The helper looped `while (true)` until a terminal state with `!isRunning`, with no bound. A controller that never settled stalled the whole `:app:testProDebugUnitTest` suite rather than failing one test — the same failure mode already fixed in `awaitNextTerminalAfterChange` (commit `3c656a4`). The success predicate was correct; only the wait was unbounded. | P2_MEDIUM | **CLOSED (v1.0.12)** — bounded with `withTimeoutOrNull(10_000)` and a 25 ms poll; on timeout it fails loudly, naming the last state, `isRunning`, and the last 12 adapter log lines. Predicate unchanged; all call sites unchanged (timeout defaulted). See §1.6.6. Evidence: SOURCE CODE + TEST |
| 13 | **SEC-02 / APP-1** cleartext HTTP permitted globally | `AndroidManifest.xml:28` `usesCleartextTraffic="true"` plus the blanket `network_security_config.xml:3` (`<base-config cleartextTrafficPermitted="true"/>`); HTTP fallback/bootstrap URLs in `VpnGateSources.kt`. HTTPS-first ordering and `ResilientDns` are existing mitigations, but the policy permits cleartext to any host. | P2_MEDIUM | **FIXED (v1.0.15) — device-verified active enforcement; see §1.6.8 bullets 4–5. Commit `1bbf8a6`.** Evidence: SOURCE CODE + PHYSICAL DEVICE |
| 14 | **N-11** `atoi()` parses `Content-Length` | `softether_protocol.c:818, 4075, 4202` cast `atoi()` to `uint32_t`; a negative/malformed value becomes `0xFFFFFFFF`, but every site is bounded downstream (`:838–839`, `:4078`, `:4207`), so this is robustness/defence-in-depth rather than a demonstrated exploit. | P3_LOW | **FIXED (v1.0.15) — see §1.6.8 bullet 2. Commit `f66f464`.** Evidence: SOURCE CODE + TEST |
| 15 | **SEC-01 reconnect-path coverage (partial)** | The reconnect handshake is TOFU-checked — `verifyTofuAfterHandshake()` is called from `attemptReconnect()` as well as `performConnectInner()` (commit `ff78116`) — but was **not** device-reproduced: reaching it needs a mid-session network interruption, which the project's rules forbid inducing. Coverage rests on SOURCE CODE + the 2 source-shape tests in `Sec01ReconnectTofuCoverageTest`. Re-capture opportunistically if a reconnect occurs naturally during a future device run. See §1.6.9 bullet 6. | P3_LOW (opportunistic) | **OPEN — UNKNOWN (no live trace).** Not a known bypass: the check is present at both call sites; only the device-level confirmation is outstanding. Evidence: SOURCE CODE + UNKNOWN |
| 16 | **FINDING ANR-2026-10-15 — unattributed ANR during the Axis A (PART A) device run** | One ANR observed while exercising the PART A build on the physical device: main-thread degradation from 15:58:56 (Skipped 35 → 1311 → 1821 frames), ANR at 16:06:01, process death pid 23115 → 25247, **tunnel stayed healthy throughout**. The changed Axis A paths demonstrably did **not** run: `GlobalVpnTracker` logged only "network available" (A-2's `onLost` never executed), no service state change was observed (A-3's `updateState` fold never executed), and no `[AUTO]` lines were emitted (A-4/A-5 never executed). Controls: a baseline-control run of the pre-change build was clean, and a re-test of the same PART A APK was clean. `/data/anr` is unreadable without root, so the **blocked frame is UNKNOWN**. Most probable remaining explanation, labelled **INFERENCE**: a pre-existing duplicate-`CONNECT` race combined with UI log-panel degradation. Forensics in `docs/reports/IMPLEMENT_P1_REMAINING_REPORT.md` and `docs/reports/COMMIT_PART_A_REPORT.md` §4; the commit body of `067d4a3` carries the same caveat. **Carried forward from Phase 13: opportunistic re-capture when a future AutoConnect run hits the same stall.** | P2_MEDIUM (unattributed; not blocking PART A, which is otherwise green on JVM + build) | **OPEN — UNKNOWN, NOT REPRODUCED.** Not attributed to PART A: the negative assurance above shows none of the three Axis A paths was in flight. To close: read `/data/anr` on a rooted or userdebug device, or reproduce with `Debug.` systrace / a `StrictMode` dump attached; and instrument the CONNECT path to determine whether the suspected duplicate-`CONNECT` race exists. **Re-verify on device before the next feature revision** so the PARTIAL status is either upgraded to PASS by a clean multi-route run or escalated with a trace. Evidence: PHYSICAL DEVICE (logcat) + INFERENCE + **UNKNOWN** + **NOT REPRODUCED** |
| 17 | **PART A device verification (partial)** | The four Axis A changes (A-2 `onLost` re-check budget 500 ms → 50 ms; A-3 `VpnStackLedger.reconcile` replacing four protocol egates; A-4 the single `reportArmedTunnelLoss()` funnel; A-5 `Disconnected` published after the OQ-7/OQ-6/OQ-1 decision) are committed (`067d4a3`) and JVM-tested: `:app:testProDebugUnitTest` **671/0/0/1** (+24 new over the 647 baseline), `:SoftEtherClient:test` **173/0/0/0**, `:app:assembleProDebug` SUCCESSFUL with a reproducible APK SHA-256. Device verification was **PARTIAL**: one ANR during cycle 1 (unattributed, NOT REPRODUCED — see row 16). Re-verify on a future device run to convert PARTIAL to FULL. See §1.6.10. | P3_LOW (opportunistic) | **OPEN — PARTIAL (no complete device trace).** The JVM and build layers are green and reproducible; only the device layer is incomplete, and the single ANR is recorded separately as row 16 rather than counted against PART A. Evidence: SOURCE CODE + TEST + PRIOR REPORT + PHYSICAL DEVICE (the PARTIAL run) |

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

> **Item status (v1.0.21 — Z1-IMPL-C):** item **C has landed**. The emergency CSV parser
> (`parser/EmergencyCsvParser.kt`) and its HTTP boundary
> (`data/remote/EmergencySourceFetcher.kt`) are implemented against
> `SERVER_SOURCE_ARCHITECTURE.md` §10, and an opt-in wire-in runs them as the last rung of
> `ServerListUpdater` when at least one enabled emergency source is configured. Row C's description
> above is retained verbatim as historical text, exactly as the v1.0.20 approval note in §11.9.3
> directed; it is superseded in substance by that note and by this one, and is not edited here.
> Item **C's tests** land with it: 46 new JVM tests in `EmergencyCsvParserTest` and
> `EmergencySourceFetcherTest`. Items **D**, **E**, **F**, **G** and the test item **H** — which
> also requires Android runtime and device coverage — remain **NOT IMPLEMENTED**, as does Cumulative
> Server Retention (`SERVER_SOURCE_ARCHITECTURE.md §9`), which would change the update from
> replace-to-merge. Evidence: SOURCE CODE + TEST. See §1.6.14.

> **Item status (v1.0.29 — documentation reconciliation):** current status of every item,
> reconciled with the §1.6 revision history and verified against source at HEAD `df7b49c`:
>
> | Item | Status (v1.0.29) | Evidence |
> |---|---|---|
> | A — Dynamic network-path selection | **LANDED** (v1.0.12–v1.0.13, commit `a9e16fd`; OPEN-first / VPN-fallback ordering fixed in v1.0.27, commit `c4c0311`, §1.6.20). Recorded limitation: `GlobalVpnTracker.isTunnelUsable()` exists but is **not** consumed by production routing (`productionRouting()` uses `isConnected && currentVpnNetwork() != null`). | SOURCE CODE + TEST; §1.6.13 bullet 2, §1.6.20 |
> | B — Emergency Server Sources settings | **LANDED** (v1.0.20, Z1-IMPL-B): config model, Room v6 + MIGRATION_5_6, Settings UI; fetch wiring came with item C. | SOURCE CODE + TEST; §1.6.13 |
> | C — Canonical emergency CSV schema | **APPROVED** (v1.0.20: arch doc §10 as design basis, §11.9.3) and **LANDED** (v1.0.21, Z1-IMPL-C: `EmergencyCsvParser.kt` + `EmergencySourceFetcher.kt` + opt-in last-rung wire-in). A real data asset exists at `emergency-sources/servers.csv` (v1.0.24). | SOURCE CODE + TEST; §1.6.14 |
> | D — Manual server entry | **LANDED** (v1.0.22, Z1-IMPL-D; immediate-visibility fix v1.0.26, §1.6.19). | SOURCE CODE + TEST + PHYSICAL DEVICE (v1.0.26) |
> | E — Server-list UI entry point | **OPEN** — placement not decided; manual entry currently lives in Settings (item D's UI), no server-list row/FAB. | §11.10.2 |
> | F — Text/file/share import | **OPEN — NOT IMPLEMENTED.** | §11.10.3 |
> | G — Safe merge and persistence | **LANDED** (v1.0.23, Z1-IMPL-G, two parts: MANUAL provenance fix + `DatasetOutcome` classification). Recorded limitation: `ACCEPT_NEW`/`MERGE_WITH_EXISTING` are **names only** — an update still *replaces*; cumulative retention (arch doc §9) is **NOT IMPLEMENTED**. | SOURCE CODE + TEST; §1.6.16 |
> | H — Tests | **PARTIAL** — JVM coverage landed with items B/C/D/G (v1.0.20–v1.0.23) and the routing tests of v1.0.27; Android-runtime and device coverage remain open (partial device evidence: v1.0.24 migration, v1.0.25/v1.0.26 manual UI, v1.0.28 C4 baseline). | TEST + PHYSICAL DEVICE (partial) |
>
> Cumulative Server Retention (arch doc §9) remains **APPROVED AS REQUIREMENT, NOT IMPLEMENTED** —
> `VPNGateItem` carries no `firstSeenAt`/`lastSeenAt`/`healthScore` fields at HEAD `df7b49c`
> (SOURCE VERIFIED). This note updates but does not edit the item descriptions above, which stay
> verbatim per the v1.0.20 direction.

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
6. ~~The emergency CSV format remains explicitly undefined until a separate schema decision is approved.~~ **SUPERSEDED (v1.0.20, annotated v1.0.29):** the schema decision was taken — `SERVER_SOURCE_ARCHITECTURE.md` §10 is approved as the design basis (§11.9.3), and the parser landed in v1.0.21 (item C, §1.6.14).
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
- **Build:** Debug variant (`CI=true ./gradlew assembleProDebug`; the only flavor is `pro` — see §1.3/§15.3)
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

Only the `pro` flavor exists (`flavorDimensions = ["tier"]`, `app/build.gradle`) — there is no `free` flavor (SOURCE VERIFIED at HEAD `df7b49c`; corrected in v1.0.29).

| Variant | Build command | Notes |
|---------|---------------|-------|
| Pro Debug | `CI=true ./gradlew assembleProDebug` | Debug variants are disabled unless the `CI` env var is set (`androidComponents.beforeVariants`) |
| Pro Release | `./gradlew bundleProRelease` | AAB format; fails closed without release signing (§15.4) |

### 15.4 Release Keystore

- Production keystore (`keyStore/release.jks`) is blackbox-encrypted, deployed only on CI
- `SIGN_KEY_PASSWORD` env var (CI) or `signKeyPassword` in `local.properties` (developer machines)
- When the release keystore or password is absent, the debug-keystore fallback applies to **debug variants only**; release assembly/bundle/package tasks are blocked by a fail-closed task-graph guard in `app/build.gradle` (landed v1.0.16, §12.5.1 row 6). (Corrected in v1.0.29 — this section previously claimed a blanket debug-keystore fallback.)

### 15.5 AGP / Library Upgrade Notes

- Upgrading AGP requires updating the Gradle wrapper version and the Kotlin plugin version together
- Compose compiler is pinned to the Kotlin version (`$kotlin_version`)
- KSP **2.3.12** is applied at the root level for Room schema generation (`build.gradle`, `id "com.google.devtools.ksp" version '2.3.12'`, pinned to match Kotlin 2.4.0 per §3.1 FIX-U36-KSP-ALIGN; corrected in v1.0.29 — this line previously read "KSP 2.3.2")

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
| `MAIN_TIMEOUT_MS` | 45_000L | `VpnServerRepository.kt:81` | Collector main source timeout — **declared but never referenced** (dead constant; effective per-source deadlines live in `VpnGateSources.kt:171/:188/:215`) |
| `MIRROR_TIMEOUT_MS` | 30_000L | `VpnServerRepository.kt:82` | Collector mirror timeout — **declared but never referenced** (dead constant, same note) |
| `RAW_CAPTURE_LIMIT` | 512 * 1024 | `VpnServerRepository.kt:83` | Debug panel raw capture limit |
| `MAX_MIRRORS` | 12 | `VpnGateSources.kt:25` | Max mirrors to use per collection |
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
| `VPNGateConnection` | `app/src/main/java/vn/unlimit/vpngate/models/VPNGateConnection.kt` |
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
| 1.0.9 | 2026-10-13 | DOCS-UPDATE: corrected §12.5.1 row 1 (FIX-NATIVE-LIFETIME-SYNC — N-15/N-16 both CLOSED, device-verified; commits `3a2f442`, `b0418cc`) and added a separate row (8) for the still-open T5-SLOT driver finding. Refreshed the header HEAD field to `d61d3f8`. No new methodology lessons in v1.0.9. No source/test/build/device change; documentation only. |
| 1.0.10 | 2026-10-13 | DOCS-UPDATE (row backfilled in v1.0.29 from `DOCS_UPDATE_v1.0.10_REPORT.md`; the row was missing from this table) | Recorded the AutoConnect root-cause resolution: a read-only audit that isolated the root cause, the native fix that landed against it, the Kotlin soft-bound bundle, the test repair, the combined device-verify evidence, and the residual finding. Extended the §1.6.1 commit chain from 17 to 23 commits (6 new rows), added a new **§1.6.4**, added **§12.5.1 row 9** for the residual, and updated the §20 changelog/verification tables. HEAD `5ee4e65538218e774031cc2abd994d5a2ff852b5`. Documentation only — no source/test/build/device change. |
| 1.0.11 | 2026-10-14 | DOCS-UPDATE + READ-ONLY AUDIT | recorded the **native hardening bundle** in a new **§1.6.5** — the read-only characterization of the five uncommitted native files (verdict FIX: coherent, matched to AC-01..AC-04/N-01/N-04/N-05/N-10, but non-compiling), the one-line compile repair (`c8acb5d`, forward declaration of `rudp_ref_release`), the pre-device defensive `budget == 0` guard in all five transport workers (`1a4818a`), and the automated device verification of the whole bundle on `5585fecc` (`6119a2a` — `[TLS] handshake entry:` 12× with `applied=yes` 12× / `applied=no` 0×; the parallel race to `all transports failed` at exactly 5000 ms with 0 detaching threads and 0 budget-exhausted events; 0 crashes; threads 54 → 35; lib/slot/destroy counts 11× each; fd-leak INCONCLUSIVE because `/proc/<pid>/fd` is Permission-denied). Extended **§1.6.1** to the 28-commit chain (`0714628` → `6119a2a`, 5 new rows including the previously unlisted `5773c60`). Updated **§12.5.1 row 9** (T5-SLOT-TEARDOWN narrowed: 7/7 device occurrences self-healed within ~1 s; mechanism understood; upstream trigger and the ~1 s vs ~1 min window variance remain UNKNOWN) and added **§12.5.1 row 10** (Axis A / B / C read-only audit register, P2_MEDIUM, opportunistic). New artifacts: `AUDIT_AXES_A_B_C_v1.0.11.md`, `DOCS_UPDATE_v1.0.11_REPORT.md`, plus the three earlier-phase reports they cite. Refreshed the header HEAD field to `6119a2a`. No new methodology lessons in v1.0.11. **This revision itself made no source/test/build/device change** (documentation + read-only audit only); it records the native hardening commits `fd4fcf9`, `c8acb5d` and `1a4818a` that were made in earlier phases of the same chain |
| 1.0.12 | 2026-10-14 | DOCS-UPDATE + TEST FIX | closed the two open test-suite defects carried over from FIX-HANGING-SKIP-TEST in a new **§1.6.6**. **OPEN-1** — `VpnServerRepositoryR4bTest`'s `[collector] path=...` collector was a plain `ArrayList` written by two concurrent `async` coroutines in `VpnServerRepository.refresh`, so a lost `add` could drop the `path=vpn source=html` line; the collector is now a `CopyOnWriteArrayList` (assertions unchanged) and a deterministic regression test `vpn-path-logs-are-never-lost-under-concurrent-fetch` (50 concurrent runs, exact per-line counts; 0.78 s / 0.76 s) was added. **OPEN-2** — `AutoModeControllerTest.awaitTerminal()` was an unbounded `while (true)`; it is now bounded with `withTimeoutOrNull(10_000)` (25 ms poll) and fails loudly, naming the last state, `isRunning` and the last 12 adapter logs; the success predicate and all call sites are unchanged. Extended **§1.6.1** to the 37-commit chain (`0714628` → `3dfb0f3`, 9 new rows: `68758ae`, `0e011e3`, `0e3f9ca`, `607c651`, `2841dc5`, `7b4adba`, `a9e16fd`, `3c656a4`, `3dfb0f3`). Added **§12.5.1 rows 11 and 12** (OPEN-1 / OPEN-2, both CLOSED). Verified JVM-only: `:SoftEtherClient:test` **160/0/0/0**; `:app:testProDebugUnitTest` run twice, **647/0/0/1** both times (the single skip is the pre-existing legitimate `AssumptionViolatedException` in `HtmlParserOracleTest`). **No APK was built and no device was touched.** Refreshed the header HEAD field to `3dfb0f3`. No new methodology lessons in v1.0.12. **This revision made no production-code change**; the only non-documentation edits are the two test files |
| 1.0.13 | 2026-10-15 | DOCS-HYGIENE | header path clarified — the canonical `**Repository:**` line is **retained unchanged** and a new **§2.6 Local Mirrors / Working Copies** documents the D: → C: move, its cause (D: I/O slowness: `gradlew help` ~22 min vs ~7 s on a healthy SSD) and the rule that filesystem paths are machine-specific while the repository identity (`SoftEthergai`) is not; a one-line canonical-name note was added under the header. Committed the previously-untracked `FIX_HANGING_SKIP_TEST_REPORT.md` **unchanged** (commit `b38efa7`). Verified **§1.6.1** chain sanity: `git log --oneline 0714628^..HEAD` = **41** and the table = **41 rows** (delta 0) after adding the four missing rows (`6360228`, `203addf`, `81163e4`, `b38efa7`); the phase-start delta was **3**. Refreshed the header HEAD field to `b38efa7` and `Last updated` to 2026-10-15. **No source / test / native / build / device change.** §22 untouched. No new methodology lessons in v1.0.13 |
| 1.0.14 | 2026-10-15 | AUDIT-DESIGN-SECURITY-AND-RISK-B (read-only + design) | records the security audit and the RISK-B closure in a new **§1.6.7**. **SEC-01** (== §12.5.1 row 4 / N-06): the single production verification site `aes_wrapper.c:110` sets `SSL_VERIFY_NONE`, with no trust store, no hostname binding and no pin, and SNI set to the resolved IP; three candidate designs were compared and **TOFU fingerprint pinning** is recommended, implementation deferred. **SEC-02 / APP-1**: global `usesCleartextTraffic` + blanket `network_security_config.xml`; migration design (scope cleartext to named hosts), deferred. **N-09**: `rand()` feeds RC4 keys/IV/cookies in `softether_rudp.c:251–268` and 13 `rudp_transport.c` sites (with `srand()` at `:1026`); migration onto the existing `generate_random_bytes()` / `RAND_bytes` wrapper designed, deferred; §12.5.1 row 5's stale line refs corrected. **N-11**: `atoi()` parses `Content-Length` at `softether_protocol.c:818,4075,4202` (bounded downstream); strict-parse design, deferred. **RISK-B CLOSED** — an exhaustive call-graph audit proves the legacy `SoftEtherClient.disconnect()` is dead code (unreachable body + zero production/test callers + no reflection); deletion recommended, deferred. Extended **§1.6.1** to the 42-commit chain (`0714628` → `6cffd1e`, adding the v1.0.13 spec commit) and added **§12.5.1 rows 13/14**. New artifact `AUDIT_SECURITY_AND_RISK_B_v1.0.14.md`. Refreshed the header HEAD field to `6cffd1e`. No new methodology lessons in v1.0.14. **This revision made no source / test / native / build / manifest / XML change and ran no build, test, or device command** |
| 1.0.15 | 2026-10-15 | DOCS-UPDATE: recorded the four implemented + device-verified security fixes (N-09, N-11, RISK-B, SEC-02) in §1.6.8; updated §12.5.1 rows 4/5/7/13/14 to reflect their fixed/closed status; advanced the §1.6.1 chain through `23816cb`. SEC-01 remains OPEN (design deferred). No source/test/build/device change. |
| 1.0.16 | 2026-10-15 | DOCS-UPDATE: recorded SEC-01 TOFU fingerprint pinning in a new **§1.6.9** — Design B landed (`b652198` native peer-fingerprint exposure, `9954c03` Kotlin store + primary-handshake wiring + 11 pure-JVM tests), primary handshake **device-verified** (Phase 9/10 on `5585fecc`: `[TOFU] match` × 3, 0 mismatch, pin store persisted through install/upgrade), and the **reconnect path fixed** (`ff78116`) so `verifyTofuAfterHandshake()` runs at both handshake-completing sites — with an explicit **PARTIAL** caveat (reconnect not device-reproduced; covered by SOURCE CODE + 2 source-shape tests; library suite **173/0/0/0**, app suite **647/0/0/1**). Updated **§12.5.1 row 4** to **CLOSED (v1.0.16)** and added **row 15** for the opportunistic reconnect re-capture. Advanced **§1.6.1** to **53** commits through `e6b9eef` (adding the previously deferred `df94a81` plus the five SEC-01 commits; inclusive-count vs table-row delta 0). Refreshed the header HEAD field to `e6b9eef`. **No source / test / native / build / device change**; §22 untouched. No new methodology lessons in v1.0.16. |
| 1.0.17 | 2026-10-15 | DOCS-UPDATE + PART A COMMIT | records the landing of **PART A** (Axis A: tunnel state / UI sync) in a new **§1.6.10** — A-2 the `onLost` re-check budget 500 ms → 50 ms via the named constant `VPN_LOST_RECHECK_MS` (**reduced, not removed**, because `onLost` precedes `onAvailable` and the re-check is what absorbs a network handover); A-3 four protocol-name "egates" in `GlobalVpnTracker` replaced by the pure stateless fold `VpnStackLedger.reconcile(live, current, event)` (on losing X with a surviving protocol Y, Y is republished as CONNECTED **from Y's own identity** and is never clobbered by X's teardown; the `sstpListener:280` egate is deliberately left in place as provably equivalent and test-pinned); A-4 the three armed-tunnel loss paths in `startConnectedWatcher` funnelled through one `reportArmedTunnelLoss()` with the detection latencies deliberately **not** unified; A-5 `AutoModeState.Disconnected` published only **after** the OQ-7/OQ-6/OQ-1 decision rather than as the first statement of `onUnexpectedDisconnect`. Adds 24 JVM tests (13 new `VpnStackLedgerTest`, +5 `GlobalVpnTrackerTest`, +6 `AutoModeControllerTest`). Records the verification status as **PARTIAL** with an explicit **ANR caveat**: one ANR at 16:06:01 (pid 23115 → 25247, Skipped 35 → 1311 → 1821 frames, tunnel healthy) that is **UNATTRIBUTED** and **NOT REPRODUCED**, with the negative assurance that the Axis A paths did not run (`GlobalVpnTracker` logged only "network available"; no service state change; no `[AUTO]` lines) and two clean controls (baseline-control run, re-test of the same APK); the blocked frame is **UNKNOWN** because `/data/anr` is unreadable without root. Records **PARTs B, C and D as already landed** (`a9e16fd`, `607c651`, `c572050`) and **not re-landed**, including PART D's live probe (`:app:assembleProRelease` exits 1 with the intended `GradleException` at `app/build.gradle:109`, so the RISK-A fail-closed guard fires and `assembleProDebug` still succeeds). Advances **§1.6.1** to **57** commits through `a7c449f` (delta 0 at `HEAD = a7c449f`, adding `8edaee8`, `71edd67`, `067d4a3`, `a7c449f`). Adds **§12.5.1 row 16** (the ANR finding, OPEN — UNKNOWN) and notes rows 6 (RISK-A partially mitigated, guard verified firing) and 10 (Axis A/B/C PARTIALLY CLOSED). New artifact `COMMIT_PART_A_REPORT.md` (commit `a7c449f`). Refreshes the header HEAD field to `a7c449f`. **This revision made no source, test, native, build, manifest or XML change and ran no build, test, or device command**; §22 untouched. No new methodology lessons in v1.0.17. |
| 1.0.18 | 2026-10-15 | DOCS-HYGIENE + CLOSE-OUT | a **documentation-only, device-free** close-out. Commits the previously-untracked **`IMPLEMENT_P1_REMAINING_REPORT.md`** (the Phase 12 implementation report, left untracked when that phase ended on a STOP condition) unmodified — 606 lines / 30,431 bytes, verified. Adds a new **§1.6.11** — "Line-Reference Corrections and Tag Decision (v1.0.18)" — with four evidence-labelled bullets: (1) the `SSL_CTX_set_verify(c, SSL_VERIFY_NONE, NULL)` call is at **`aes_wrapper.c:111`**, not the `:110` cited by §1.6.7 and §1.6.8 (one-line drift from commit `b652198`'s added include); those frozen sections are **not** retro-edited, so the correction is recorded here, and §1.6.9 already cites `:111`; (2) the **tag `p2.4.10` decision — leave it local-only**: it exists only locally, has never been pushed, resolves to `0714628` (a UI/cleanup commit, not a release cut), carries no semantic release value, and keeping it local costs nothing — **no tag operation was performed**; (3) the Phase 12 report commit; (4) no new methodology lessons. Updates **§12.5.1**: row 16 (the ANR) gains a "**Carried forward from Phase 13** — opportunistic re-capture when a future AutoConnect run hits the same stall" note, and a new **row 17** records **PART A device verification as OPEN — PARTIAL** (JVM/build green and reproducible, device layer incomplete, the ANR tracked separately as row 16 rather than counted against PART A). Advances **§1.6.1** to **60** commits through `0c5754e` (delta 0 at `HEAD = 0c5754e`, adding `a3a6434`, `94e02b7`, `0c5754e`). Refreshes the header HEAD field to `0c5754e`. **No source / test / native / build / manifest / XML change; no build, test, or device command; no tag operation**; §22 untouched. No new methodology lessons in v1.0.18 |
| 1.0.19 | 2026-10-16 | SPEC-HYGIENE | a **documentation-only, device-free** hygiene revision responding to BLOCK 1 / BLOCK 2 forensics. Adds a new **§1.6.12** — "Documentation Corrections (v1.0.19)" — with six evidence-labelled bullets: (1) the §1.6.1 preamble's *"Fifty-three commits … `0714628` → `e6b9eef`"* is marked **superseded** (a v1.0.16-era snapshot; the authoritative count is the latest §1.6.1 push-state note, 60 rows at v1.0.18); (2) the v1.0.18 push-state note's *"this revision's own **two** commits"* is corrected — Phase 14 landed the spec update **and** `CLOSE_OUT_v1.0.18_REPORT.md` in a **single** commit (`abc8bd1`, 2 files), so the chain delta of 1 is explained by one own-commit, and chain arithmetic is unaffected; (3) the **`p2.4.10` rationale correction** — forensic review of the **tag body** refuted the earlier "not a release cut" finding: target `0714628` carries `versionCode 143` / `versionName "2.4.10"` in `app/build.gradle`, `p2.4.9^{commit}` carries 142/"2.4.9", `rev-list --count p2.4.9..p2.4.10` = 1, and the annotated tag body contains 2.4.10 release notes ending **"Version: Bump to 2.4.10 (versionCode 143)."** — the earlier §1.6.3 note, §1.6.11 bullet 2 and `DOCS_UPDATE_v1.0.17_REPORT.md` §7 remain frozen as historical record; (4) an explicit note that `DOCS_UPDATE_v1.0.17_REPORT.md` §7 cannot be retro-edited; (5) a forward note that HEAD (`abc8bd1`) **shares** `versionName "2.4.10"` / `versionCode 143` with tag `p2.4.10` while sitting 60 commits ahead, so a future release phase should bump the version past 2.4.10; (6) no new methodology lessons. **Records the p2.4.10 tag decision as executed — PUSHED** (operator-authorized in BLOCK 3): remote unique tag set is now **5** (`p2.4.5`/`p2.4.7`/`p2.4.8`/`p2.4.9`/`p2.4.10`), local unchanged at 5, published tag object `c1013806…` byte-identical to the local one; a **§1.6.1 v1.0.19 push-state note** records the same. **§12.5.1 unchanged** (no row touched). Refreshes the header HEAD field to `abc8bd1`. **No source / test / native / build / manifest / XML change; no build, test, or device command; the only tag operation was the operator-authorized push of `p2.4.10` — no other tag was created, deleted, moved or force-pushed**; §22 untouched. No new methodology lessons in v1.0.19 |
| 1.0.20 | 2026-10-16 | Z1-IMPL-B (implementation, device-free) | **Roadmap §12.5.2 item B landed** — the emergency server sources foundation. New `SourceKind.EMERGENCY_CSV` and `SourceKind.MANUAL` (appended, ordinals preserved); new `data/remote/EmergencySourceConfigEntity.kt` (`@Entity(tableName = "emergency_source_config")`: autoGenerate id, url/label/enabled/createdAt/updatedAt, `enabled` with `@ColumnInfo(defaultValue = "1")`), `EmergencySourceConfigDao.kt` (observeAll / listEnabled / getByUrl / upsert / deleteByUrl) and `EmergencySourceConfig.kt` (domain model with `fromEntity`/`toEntity`); `AppDatabase` registered at **version 6** with the entity and `emergencySourceConfigDao()`, plus **MIGRATION_5_6** (single additive `CREATE TABLE`, no foreign keys, non-destructive) registered in `App.kt` together with the new DAO accessor — both `App.kt` changes were authorized after discovery showed the Room builder and the DAO registry live there; `ConnectionListViewModel` exposes `emergencySources: StateFlow<…>` plus add/remove/toggle; `SettingsScreen` gained an "Emergency Server Sources" section (URL + optional label, per-row enable switch and remove, validity gate reusing `SourceUrl.parse`); KSP schema export `6.json` committed. Retrieval is **not** wired (item C) and nothing fetches. Records item B in a new **§1.6.13** (9 evidence-labelled bullets); **§11.9.3** updated — `SERVER_SOURCE_ARCHITECTURE.md` §10 **approved as the design basis** for the emergency CSV schema, historical DEFERRED wording retained verbatim and annotated superseded, parser deferred to item C; item A confirmed already implemented (PART B / `a9e16fd`), NO-OP this phase; items C–H and retention (§9) remain open. JVM: `:app` **678/0/0/1** (baseline 671/0/0/1; +7 from `Migration5to6Test`), `:SoftEtherClient` **173/0/0/0**; `CI=true :app:assembleProDebug` SUCCESSFUL, APK SHA-256 `9ba16f9f…`. Device verification: **NONE** (DETACHED). Environment note: the build disables all debug variants unless the `CI` environment variable is set, so every Gradle invocation in this revision ran with `CI=true`. §1.6.2–§1.6.12 and §22 untouched. No tag operation. No new methodology lessons in v1.0.20 |
| 1.0.21 | 2026-10-16 | Z1-IMPL-C (implementation, device-free) | **Roadmap §12.5.2 item C landed** — the emergency CSV parser and its opt-in wire-in. New `parser/EmergencyCsvParser.kt` implements `SERVER_SOURCE_ARCHITECTURE.md` §10 (29 columns; required `HostName`/`IP`/`schema_version`/`SourceTag`; header-driven so column order stays open per §10.9 Q2; `schema_version` fail-closed on anything but `1.0`, checked on **every** row; RFC-4180 quoting; BOM and non-UTF-8 bodies rejected; §18 unit normalisation identical to the canonical parser). Records are ordinary `VpnServerRecord`s built with the existing `VpnRecords` helpers — no parallel record type (§3.12). Required-field failures reject the row and are counted; optional-decode failures keep the row and mark the capability unsupported (Base64 that will not decode keeps the record and drops OpenVPN); a valid header with zero surviving rows is **Malformed**, not an empty import (§3.10). `SE_UDP_Port` is never turned into a transport on its own — the RUDP warning at §10.3 is honoured literally, so SoftEther UDP support comes only from `SE_UDP_Supported`; `0` is accepted as the documented "not present" token and no port is ever fabricated. Identity and dedup are delegated to `VpnServerMerger` (§10.5), and a merged record's file-level `SourceTag` becomes `merged`, carried on the record in an `emergency` provenance map. New `data/remote/EmergencySourceFetcher.kt` is fetch-only and reuses the project's one `HttpFetcher`/`collectorClientBuilder()` (10 s connect / 15 s read, `ResilientDns`, manual per-hop redirects) so no second HTTP stack is introduced; it follows no redirect outside the source's own authority, re-checks the reported final URL, refuses a body over 1 MiB rather than truncating it, retries each source once under a 40 s deadline (§2.6), and never parses or persists. A recorded limitation: `HttpFetcher` returns a decoded `String`, so the cap bounds parsing/retention rather than bytes on the wire. `SourceIdentity.kt` routes `SourceKind.EMERGENCY_CSV` to a new `EMERGENCY_TAG = "emergency"` instead of the mirror branch, so an emergency record forms its own provenance group and can never speak with canonical authority. The wire-in adds one injected parameter, `ServerListUpdater.emergencyFetch`, defaulted to `{ null }` and tried **only** when the primary fetch returned `null` — that fetch already covers the open and VPN rungs plus the repository's snapshot/stored/bundled-seed fallbacks, so this is §2.5 path 3. `ServerListUpdates.kt` builds the production lambda from the enabled sources and runs the live pipeline unchanged (fetch → parse → merge → validate → score → map); it returns `null` rather than an empty list on every failure, so a refused, oversized, undecodable or empty source falls through and leaves the stored list untouched. Zero behaviour change when no source is configured is evidenced by all 678 pre-existing tests — including every `ServerListUpdater` case — passing unchanged. Records item C in a new **§1.6.14** (9 evidence-labelled bullets); **§11.9.2** annotated with the safety-set status (historical text retained); **§12.5.1** unchanged (no row tracks item C); a status note added under the **§12.5.2** table (row C's description left verbatim, per the v1.0.20 direction). JVM: `:app` **724/0/0/1** (baseline 678/0/0/1; **+46** from `EmergencyCsvParserTest` and `EmergencySourceFetcherTest`), `:SoftEtherClient` **173/0/0/0**; `CI=true :app:assembleProDebug` SUCCESSFUL, APK SHA-256 `b85a7aba…`. Device verification: **NONE** (DETACHED). Environment note: every Gradle invocation ran with `CI=true`, as in v1.0.20. §1.6.1–§1.6.13 and §22 untouched. No tag operation. No new methodology lessons in v1.0.21 |
| 1.0.22 | 2026-10-16 | Z1-IMPL-D (implementation, device-free) | **Roadmap §12.5.2 item D landed** — manual server entry plus the MANUAL-preservation merge, which is the preservation half of item G. The field set is the one the requirement asks for and nothing more: spec §11.11 defines the *rules* and explicitly declines to define a schema ("Do not design or implement a new database schema here"), and `SERVER_SOURCE_ARCHITECTURE.md` §4.1 fixes the minimum as "a single server record (host/IP + at least one protocol+port)". New `data/remote/ManualServer.kt` (domain), `ManualServerEntity.kt` (`@Entity(tableName = "manual_server")` with a **unique** index on `(host, port, protocol)` so re-entering a server updates the row instead of adding a duplicate the user then has to find), `ManualServerDao.kt` (`observeAll`/`listAll`/`listEnabled`/`findByKey`/`upsert`/`deleteById`, mirroring `EmergencySourceConfigDao`), `repository/ManualServerStore.kt` (injected-DAO wrapper, no network) and `repository/ManualServerStore.toItem`, which maps each protocol to only the columns that protocol owns — never another protocol's port — and writes **no** telemetry for a server that was never crawled. `AppDatabase.kt` registers the entity, moves to **version 7** and adds `MIGRATION_6_7`: two additive statements (the table, then that unique index), no `DROP`/`DELETE`/`TRUNCATE`, and no recreation of `VPNGateItem`, `excluded_apps` or `emergency_source_config`; `App.kt` registers the migration and exposes `manualServerDao`; `app/schemas/…/7.json` is the KSP export and is compared against the migration DDL by `Migration6to7Test`. **The merge policy decision.** `VpnServerMerger.SOURCE_PRIORITY` gains `"manual" to 30`, completing the frozen table as api 0 > html 10 > mirror 20 > manual 30 — `SERVER_SOURCE_ARCHITECTURE.md` §3.3 ("canonical (api/html) always wins over emergency over manual"), §4.2 ("never promoted above canonical") and §11.11. Preservation and precedence are kept as two separate promises: preservation is **absolute** (a manual record is never deleted, and `mergeRecords` has no path that drops one for being low-priority), while precedence applies only to a value **both** sides claim, where the collected source wins and the disagreement is written into the record's `conflicts` list (§38) so a manual record is never *silently* overwritten (§11.11). This was put to the operator as an explicit choice because the phase brief's wording ("MANUAL wins on collision") contradicts the frozen arch doc; the arch-doc reading was confirmed. **Where the merge runs.** `ServerListUpdates.withManualRows` executes inside `Store.replaceAll`, so the user's rows are re-emitted whichever rung supplied the network dataset — the primary fetch, the repository's snapshot/stored/bundled-seed fallbacks, or the emergency rung from item C — and an empty manual list returns the caller's own list **by identity**, so a caller with no manual servers observes byte-identical behaviour. A host the network already serves does not get a duplicate manual row; the entry itself is untouched and is re-emitted the next time the feeds stop listing that host (§4.3, "re-emitted as long as they remain valid"). **A manual address must be an IPv4 literal**, because the record layer keys a server on one (`normalizeServer` rejects anything else and `toConnection` returns null without one) — a bare DNS name would be accepted by the form and then silently dropped, so it is refused at the `ManualServerStore.add` boundary instead. UI: `ConnectionListViewModel` exposes `manualServers` plus add/remove/toggle, and `SettingsScreen` gains a "Manual Servers" section reusing existing components and the `manual_server_` string prefix; the stale `emergency_source_summary` (which still said nothing was fetched) was corrected in the same authorized file. Zero behaviour change when no manual server is configured is evidenced by all 724 pre-existing tests — including every `ServerListUpdater` case — passing unchanged. Records item D in a new **§1.6.15**; **§11.11** annotated with the implementation status (historical text retained, including its own "do not implement a schema here" note, now superseded for this table only); **§12.5.1** unchanged (no row tracks item D) and **§12.5.2** untouched, since this phase's permitted spec hunks are §11.11, §1.6.1, §1.6.15 and §20 only. Three limitations are recorded rather than hidden: `SourceIdentity.kt` was outside the authorized file set, so `SourceKind.MANUAL`'s `sourceTag` still reads `mirror_unnumbered`; two manual entries on one IPv4 collapse to a single stored row because the table's primary key is the host name alone; and an OpenVPN manual entry stores a port but no profile, so it is not connectable until items E/F supply one. JVM: `:app` **747/0/0/1** (baseline 724/0/0/1; **+23** from `ManualServerPreservationTest` and `Migration6to7Test`), `:SoftEtherClient` **173/0/0/0**; `CI=true :app:assembleProDebug` SUCCESSFUL, APK SHA-256 `f1d768c9…` (65,978,641 bytes). Device verification: **NONE** (DETACHED). Environment note: every Gradle invocation ran with `CI=true`, as in v1.0.20 and v1.0.21. §1.6.2–§1.6.14 and §22 untouched. No tag operation. No new methodology lessons in v1.0.22 |
| 1.0.23 | 2026-10-17 | Z1-IMPL-G (implementation, device-free) | **Roadmap §12.5.2 item G landed** in the two parts the operator approved. **Citation correction first:** the phase brief named `SERVER_SOURCE_ARCHITECTURE.md` **§3.12** as the home of the three outcomes, but §3.12 does not define them — the vocabulary lives in **§9.6**/**§10.8** and §9.1 fixes what the first two *mean*. That correction, plus two further conflicts between the brief's decision table and §9.1/§9.6, were surfaced to the operator rather than resolved silently; the arch-doc reading was confirmed. **Part (i) — the MANUAL provenance fix.** `SourceIdentity.sourceTag` reached the mirror kinds through `else -> mirrorId ?: UNNUMBERED_MIRROR_TAG`, and `SourceKind.MANUAL` has no mirror id, so a user's own entry was reported as an unidentified mirror for two revisions (limitation (c) of §1.6.15 bullet 9). The `when` is now **exhaustive with no `else`**, `MANUAL_TAG = "manual"` is a companion constant, and adding a future `SourceKind` without a tag now fails to compile instead of mislabelling records at runtime. This was not cosmetic: `Provenance.sourceGroup` maps `mirror_<n>` to the group `mirror` and leaves `mirror_unnumbered` as its own group, and **neither** is in `VpnServerMerger.SOURCE_PRIORITY` — so both fall through to the default 20, meaning a manual record silently carried mirror-level authority and the `"manual" to 30` entry landed in item D was **dead code**, never reached by any record. A manual identity carrying a stray mirror id (reachable through `withMirrorId`) still reads `manual`, and real mirror ids (`mirror_<n>`, prefixed at assignment) can never collide with the manual tag, so the two provenance groups are provably disjoint. **Part (ii) — the three outcomes as a classification, not a decision.** New `DatasetOutcome` (`ACCEPT_NEW` / `MERGE_WITH_EXISTING` / `REJECT_DEGRADED`) and `outcome(verdict, stored)`; `DatasetIntegrity.evaluate`'s **signature and all seven thresholds are unchanged**, so no acceptance or rejection decision moved. `REJECT_DEGRADED` is implemented in substance (it is `verdict.accepted == false`); `ACCEPT_NEW` and `MERGE_WITH_EXISTING` are **names only** in this phase, because arch doc §9.1 defines them as cumulative **addition** ("the database **grows** across updates rather than being **replaced**"), and Cumulative Server Retention is not implemented and is outside this phase's authorized files — an update still replaces. The brief's decision table classified a strict subset and a disjoint candidate as `REJECT_DEGRADED`; §9.1 says a shrunken response is `MERGE_WITH_EXISTING`, "**never** as a signal to prune previously-seen healthy records", and §9.6's closing sentence says the outcomes "layer on top of the existing `DatasetIntegrity` threshold logic **rather than replacing it**", so the operator-confirmed reading keeps a viable-but-smaller (25 of 40) and a viable-but-disjoint dataset as `MERGE_WITH_EXISTING` while the existing thresholds still decide acceptance exactly as before. **The wire-in records rather than branches.** `ServerListUpdates.replaceAll` re-runs `evaluate` (pure and deterministic, so it reproduces the updater's verdict) and logs the outcome, both sizes and `verdict.detail` before the unchanged write; `ServerListUpdater.kt`, which *owns* the decision, is not an authorized file this phase, which is exactly why the boundary logs instead of deciding — any future divergence becomes visible rather than silent, and the classified value is never persisted. **No `build.gradle` change, no new dependency, no migration, no schema change and no Room version bump.** Records item G in a new **§1.6.16** (10 evidence-labelled bullets); **§11.11** annotated with the provenance closure and the classification-only nature of the outcomes; **§12.5.1** unchanged (no row tracks item G) and **§12.5.2** untouched, since this phase's permitted spec hunks are §11.11, §1.6.1, §1.6.16 and §20 only. JVM: `:app` **768/0/0/1** (baseline 747/0/0/1; **+21** from `ManualProvenanceTest` 10 and `DatasetIntegrityOutcomeTest` 11), `:SoftEtherClient` **173/0/0/0**; `CI=true :app:assembleProDebug` SUCCESSFUL, APK SHA-256 `f42f78e8…` (63,738,546 bytes). Device verification: **NONE** (DETACHED). Environment note: every Gradle invocation ran with `CI=true`, as in v1.0.20–v1.0.22. §1.6.2–§1.6.15 and §22 untouched. No tag operation. No new methodology lessons in v1.0.23 |
| 1.0.24 | 2026-10-17 | EMERGENCY-SOURCES-CSV (data asset + docs; **device-free and build-free**) | **A real, URL-accessible emergency source now exists**, and the device migration is formally recorded. New `emergency-sources/` folder: `servers.csv` (all **29** columns of `SERVER_SOURCE_ARCHITECTURE.md` §10.2, in documented order, `schema_version="1.0"` on every row) plus `README.md`. Purpose is narrow — the emergency-source path (§11.9, roadmap item C) had only ever been pointed at `https://example.invalid/test.csv`, which does not resolve, so the fetch could never succeed and the feature was never actually exercised; the app was not at fault for refusing it. **Content is real, and that is a deliberate correction to the brief.** The brief asked for "3 example rows with historical VPN Gate IPs" whose "port data is EXAMPLE/plausible, not fetched live"; writing plausible-but-unverified ports would contradict arch doc §10.3 ("a port is never fabricated"), and describing fetched data as "not fetched live" would have put a false statement in the README. The three rows were therefore captured on 2026-10-17 from the two public sources the design doc names — `https://www.vpngate.net/api/iphone/` for the base 15 columns and `https://www.vpngate.net/en/` for the extended columns — so every row legitimately carries `SourceTag="merged"` (§10.6). Where the HTML states support but publishes no port number (`SE_UDP_Port`) or publishes only an SSTP hostname (`SSTP_Port`), the value is `0`, exactly as §10.3 requires. The file is a **3-row point-in-time snapshot**, labelled as such in the README, not a production list; it will go stale and the README documents manual regeneration. Host-side validation confirmed 29 columns, 3 rows, the four required columns conforming, every IP a public IPv4, every port in `0..65535`, every boolean literal `true`/`false`, every `OpenVPN_ConfigData_Base64` decoding, and UTF-8/no-BOM/LF per §10.7. **The device migration 5 → 6 → 7 is now formally recorded** in a new **§1.6.17**: prior state `user_version=5` with 103 `VPNGateItem` and 2 `excluded_apps` rows and both new tables absent; after installing v1.0.23, `user_version=7`, every pre-existing table preserved **byte-for-byte**, both new tables present with 0 rows, `integrity_check=ok`, and `identity_hash` changed `32a05726…` → `1ff61fa3…` as required; the rollback APK built from pre-B `a3a6434` was **not needed**. **The emergency-source and manual-server UI tests are still NOT confirmed end-to-end** — a read-only block found both tables at 0 rows and `sqlite_sequence` **empty**, which is decisive: both tables are `INTEGER PRIMARY KEY AUTOINCREMENT`, so the first insert (even one later deleted) leaves a permanent watermark, so the insert code never executed; the database was byte-identical to its post-migration capture across a full session and the app had logged one line since start. No product defect is implied — the whole path and every validation gate were verified in source and would have accepted the inputs — so this is an **UNKNOWN** (whether the operator's taps reached the app), to be re-tested against the new raw URL. **No JVM run this revision, and the figures are labelled accordingly:** the brief's SCOPE and FORBIDDEN lists both forbid any build/test run while its STEP 5 mandates one; the contradiction was put to the operator, who chose to honour the prohibition. No code, test, manifest or XML is touched, so `:app` **768/0/0/1** and `:SoftEtherClient` **173/0/0/0** are carried forward as **PRIOR REPORT**, explicitly **not** re-run. **Header correction:** the **HEAD** field was stale at `40decde` since v1.0.23 and is now `02ce791`. **§11.9.2** annotated with the test URL; **§12.5.1** and **§12.5.2** untouched; **§1.6.2–§1.6.16** untouched; **§22** untouched and byte-identical (99 lines / 6,504 bytes). No tag operation, no device command, no build and no test run. No new methodology lessons in v1.0.24 |
| 1.0.25 | 2026-10-17 | MANUAL-UI-INVESTIGATE-AND-FIX (UI fix + device verification) | **The manual-server protocol picker no longer clips its trailing chip**, and the second reported symptom's mechanism is now **proven** rather than hypothesised. **Defect A — "SSTP is missing from the protocol picker" — was a layout overflow, not a missing option.** The picker was a plain `Row(horizontalArrangement = Arrangement.spacedBy(8.dp))` of four `FilterChip`s with no wrap and no horizontal scroll; at 1080 px / 2.75 density (392.7 dp) the measured chip widths are **265 / 264 / 298 / 261 px** with **22 px** gaps = **1154 px** against **904 px** of content width, so the fourth chip was laid out from x≈981 to x≈1242 and ended **162 px past the right edge**, clipped and unreachable. `manualProtocolInput` defaults to `VpnProtocolNames.SSTP`, so the *selected* chip was the invisible one — which is why the symptom read as absence rather than as a cut-off edge. The fix is three hunks in one authorized file: `Row` → `FlowRow`, the inline `listOf(...)` hoisted to `internal val MANUAL_SERVER_PROTOCOL_CHOICES`, one import added. `FlowRow`'s non-`overflow` overload is **stable** in foundation-layout 1.12.0 (only its internals carry `@ExperimentalLayoutApi`), verified per-declaration rather than by grep count, so **no `@OptIn` was needed**; wrapping was chosen over a horizontal scroll because it keeps every protocol visible at once with no gesture to discover. New `ManualServerProtocolChoicesTest` (**4 tests**) pins the constant to `VpnProtocolNames.ALL` by set equality, so the *durable* hazard — an inline list that silently omits a protocol added to the record layer — cannot recur; set rather than list equality, because the form's order matches the filter sheet and is not what is being protected. **Defect B — "manual server not in the main list after a refresh, present after a restart" — is not a rendering defect.** The main list is built from `VPNGateItem` only (`ConnectionListViewModel`'s four `getAPIData` branches, `DataUtil.connectionsCache`, and `HomeScreen` which never reads `manualServers`), and the sole writer is `ServerListUpdates.kt:268` `replaceAll(withManualRows(items, manual.rows))` — so a manual row enters the list **only when a refresh is accepted**, never at add time. Both outcomes are visible in one install's logs: at **12:20** `dropsOpenVpnProfiles()` returned true and the update was `Preserved(reason=DEGRADED_DATASET, serverCount=103)` with `replaceAll` never running; at **13:16** a healthy refresh was accepted (`Collected 106 servers (capability=…providesOpenVpnProfiles=true)`, `Dataset outcome MERGE_WITH_EXISTING: 106 incoming vs 103 stored`, `Updated(serverCount=106)`) and `VPNGateItem` became **107** = 106 + 1 manual. **This corrects the revision's own STEP-2 conclusion:** the initial diagnosis held that "shown after restart" could not have meant the main list, which is what raised the design-gap question; a restart triggers a refresh, and an accepted refresh *does* materialise the manual row, so the operator's observation was accurate and referred to the main list throughout. The operator's decision — **fix the picker now, defer manual-row visibility** — is unchanged and respected; the deferred item simply now rests on a proven mechanism, with the gap localised to `addManualServer` not materialising the row. **Structural finding recorded for that future work:** `VPNGateItem` has **no `protocol` and no `label` column**, so the merge writes the manual `label` into `operator` and the `port` into `seTcpPort` and leaves the protocol flags clear (observed row: `hostName='1.1.1.1'`, `operator='phaseB-test'`, `seTcpPort=443`, `isSSTPSupport=0`, `isL2TPPSupport=0`). **Residual hazard recorded, not fixed:** `manualProtocolLabel` ends in `else -> R.string.manual_server_protocol_sstp`, so a future protocol would be offered correctly but *labelled* "MS-SSTP". **Methodology lesson (WAL), which caught a false negative in this phase:** a WAL-mode Room DB cannot be read from its main file alone — here the main file was 1,036,288 B while `-wal` was **1,030,032 B**, essentially the whole live dataset, so a main-only pull reproduces v1.0.24's "0 rows, empty `sqlite_sequence`" reading, which was a **false negative**; the reliable procedure is `ls -l` first, pull main **+ `-wal` + `-shm`**, replay beside a copy, and transfer as base64 with a decoded-size assertion against `stat -c %s`. JVM: `:app` **772/0/0/1** (baseline 768/0/0/1; **+4** from the new test class), `:SoftEtherClient` **173/0/0/0**; `CI=true :app:assembleProDebug` SUCCESSFUL, APK SHA-256 `390be5c5…` (63,737,026 bytes), installed `-r`. Device `5585fecc`: `user_version=7`, `integrity_check=ok`, both operator rows survived, and the picker renders **row 1** `OpenVPN` [132–309] · `SoftEther` [419–595] · `L2TP/IPsec` [705–915] with **row 2** `MS-SSTP` [132–305], all four `clickable`/`enabled`/`checkable` and `MS-SSTP` `checked='true'`. New **§1.6.18** (9 bullets); **§11.11** annotated with the "survives an update ≠ appears without one" distinction; **§1.6.1** push-state note added; **§1.6.2–§1.6.17** untouched; **§12.5.1/§12.5.2** untouched; **§22** untouched and byte-identical (99 lines / 6,504 bytes). No tag operation |
| 1.0.26 | 2026-10-17 | MANUAL-SERVER-IMMEDIATE-VISIBILITY (implementation + device verification) | **Three items from v1.0.25's own "recorded but not fixed" list are closed**, and this is the first revision to close such a list rather than add to it. **(B) A manual server is visible the moment it is added.** The only writer of `VPNGateItem` was `ServerListUpdates.replaceAll`, whose `withManualRows` re-emission runs *inside an accepted refresh*, so a newly added server stayed invisible until some later refresh was accepted — indefinitely, while refreshes were being rejected. `ConnectionListViewModel.addManualServer` now reads the table, merges, and writes it back through `ManualServerStore.materialize`, which **delegates to the same `withManualRows`** rather than reimplementing it: one preservation policy, not two that could drift. **(C) A degraded refresh is no longer silent.** `Outcome.Preserved` correctly keeps the stored list but left the screen identical to "nothing to update"; a one-shot `refreshNotice` is now set **only** for `Reason.DEGRADED_DATASET` and shown as a `Toast` by `HomeScreen`, the mechanism the other Compose screens already use. `isError` was deliberately not reused — it means "nothing to show" and would blank a list that is present and correct, merely stale. Rejection semantics are untouched. **(D) The protocol label no longer borrows `MS-SSTP`.** `manualProtocolLabel`'s `else -> R.string.manual_server_protocol_sstp` would have rendered a future protocol as MS-SSTP with nothing failing; it is now `manualProtocolLabelRes` with an explicit branch per protocol and `else -> null`, the caller falling back to the **raw key** (visibly odd rather than confidently wrong). **One brief requirement proved not literally implementable and was resolved by operator decision:** the requested compile-time guard is impossible because `VpnProtocolNames` is an `object` of `const val` Strings and Kotlin has no exhaustive `when` over `String` — a true compile-time guarantee needs an enum/sealed conversion of the record layer, outside the authorized files. The approved in-scope substitute is a **test** (`ManualProtocolLabelExhaustiveTest`) that walks `VpnProtocolNames.ALL` and fails on a missing or duplicated branch. **Device-verified on `5585fecc`:** `8.8.8.8:443` and `9.9.9.9:443` were added via the form and **no refresh was performed at any point** — the header stayed `Last updated: 17 Oct 2026 - 13:16` and the app logged no update line after install — yet the list went **107 → 108 → 109** and searching returned both as rendered rows (`Score: 0`, `SoftEther`). DB-side (main + `-wal` + `-shm` replayed, base64 with decoded-size assertion): `manual_server` 3 rows, `VPNGateItem` **109**, each test address exactly **once** with `operator` = its label and `seTcpPort = 443`, `user_version=7`, `integrity_check=ok`. **Fix (C) was NOT observed on device** — forcing a degraded fetch requires cutting the device's connectivity, which is the operator's network and not this phase's to toggle, so its evidence is SOURCE CODE and its live behaviour is **UNKNOWN**. **JVM:** `:app` **780/0/0/1** (baseline 772; **+8** from `ManualServerImmediateMaterializeTest` 5 + `ManualProtocolLabelExhaustiveTest` 3), `:SoftEtherClient` **173/0/0/0**; `CI=true :app:assembleProDebug` SUCCESSFUL, APK SHA-256 `7619d1b0…` (63,739,346 B, +2,320 B), installed `-r` preserving all three operator rows. **Two build attempts were lost to the environment, not the code:** one "Gradle build daemon disappeared unexpectedly" and one 31-minute hang at "Reusing configuration cache" before executing any task, while the identical sources had already compiled green in the test run; the third attempt succeeded with a fresh daemon and `--no-configuration-cache` (16m 25s). **Two consequences recorded rather than claimed away:** materialising a row into the table `DatasetIntegrity` measures raises `sizeFloor` by 0.5 and can cross `hasComparableBaseline`'s floor of 10, so a borderline candidate could now be rejected where it was accepted — immaterial at the observed sizes (106–109 against a floor near 54) and convergent with what the next accepted refresh would produce anyway; and removing a manual server does **not** eagerly delete its materialised row (it goes at the next accepted refresh), matching §1.6.15's preservation invariant. New **§1.6.19** (8 bullets); **§11.11** annotated with the closure and the two remaining limits; **§1.6.1** push-state note added; **§1.6.2–§1.6.18** untouched; **§12.5.1/§12.5.2** untouched; **§22** untouched and byte-identical (99 lines / 6,504 bytes). No tag operation |

| 1.0.27 | 2026-10-18 | FIX-D-2 — TRIAGE 10 FAILING TESTS + CLOSE OUT AUTOCONNECT-AUDIT (test + one-line diagnostic fix) | **Ten JVM failures reconciled (7 in VpnServerRepositoryR4bTest, 3 in VpnServerRepositoryRoutingTest) — all bucket A.** One-line source fix in `VpnServerRepository.fetchOnChosenPath` lowercases the `[fetch]` / `[collector]` rung diagnostics (`OPEN`/`VPN` → `open`/`vpn`) to match the OPEN-first contract; three OPEN-first behavioural assertions updated in `VpnServerRepositoryR4bTest` (open path now passes `boundFetcher = null`, empty-mirror asserts `null`, routing-re-read uses `any`). `:app:testProDebugUnitTest` **790/0/0/0**; `:SoftEtherClient:test` **173/0/0/0**; `CI=true :app:assembleProDebug` SUCCESSFUL (APK `f09e949d…`). Two commits, fast-forward push; §22 byte-identical; §1.6.2–§1.6.19 untouched. No source / test / build / device *behaviour* change. |
| 1.0.28 | 2026-10-18 | RECONCILE-DIRTY-FILES — RESTORE HEAD BUILDABILITY BY COMMITTING THE D-1b CLOSURE (build reconciliation) | **`HEAD = b68ac2a` did not compile standalone (device-verify PART A): `AutoModeEngine.kt:82-83` referenced `nativeSlotAwaitMs` / `NATIVE_SLOT_AWAIT_MAX_MS` that existed only in the dirty working-tree `AutoModeController.kt`.** The 7-file AUTOCONNECT-AUDIT D-1b closure was committed (`df7b49c`, +667/−332): `AutoModeController.kt` (adds the missing symbols + `WedgeTracker`), `AndroidConnectionAdapter.kt` (`awaitNativeSlotRelease` + `onAttemptFailed`), `AutoModeProtocol.kt` (nullable `fromId`, zero callers), `TunnelStateWatcher.kt` (`onAttemptFailed` relay), and the three test companions `AutoModeControllerTest` / `AutoModeNativeBusyTest` / `VpnM24x10TaskTest`. `HttpFetcher.kt` left dirty by design (doc-comment-only OPEN→VPN routing wording, outside the closure). Standalone `:app:assembleProDebug` SUCCEEDED on C: (APK `6e289cb1…`); `:app:testProDebugUnitTest` **790/0/0/1**, `:SoftEtherClient:test` **173/0/0/0** (both EXIT=0). Two commits (`df7b49c` source + `0f5f307` device report) plus the v1.0.28 docs commit `82cb64f` (spec + `RECONCILE_REPORT.md`); push confirmed, `origin/main` = `82cb64f` (3 ahead of `b68ac2a`) *(chain arithmetic reconciled in v1.0.29 — see the §1.6.1 push-state note)*; §22 byte-identical; §1.6.2–§1.6.20 untouched. No new behavioural change. |
| 1.0.29 | 2026-10-18 | DOCS-REPAIR (documentation only — no source/test/build/device change) | Maintainer-directed documentation repair across the three owned documents (decisions recorded in-session with Morteza Taheri). **This file:** §1.3 and §15.3 flavor tables corrected — only the `pro` flavor exists; `assembleFreeDebug`/`bundleFreeRelease` do not (SOURCE VERIFIED, `app/build.gradle`) — plus the `CI=true` requirement for local debug builds; §1.3/§15.4 signing text corrected to the fail-closed guard reality (debug-keystore fallback is debug-only; release tasks fail closed); §15.5 KSP 2.3.2 → **2.3.12** (SOURCE VERIFIED, root `build.gradle`); filename typos corrected (`VPNGiteItem.kt` §7.3, `VPNGatConnection.kt` §18.2/§20 → `VPNGateItem.kt`/`VPNGateConnection.kt`); §11.8.2 rewritten as **HISTORICAL — superseded** with the current routing state (binding landed `a9e16fd`; OPEN-first/VPN-fallback landed `c4c0311`); §8.1 currency note added (routing + emergency + manual rungs); §12.5.2 gained a v1.0.29 item-status table (A/B/C/D/G LANDED, E/F OPEN, H PARTIAL, retention §9 APPROVED-but-NOT-IMPLEMENTED); §12.7 criterion 6 marked SUPERSEDED (schema approved v1.0.20); changelog row 1.0.10 backfilled; the v1.0.28 push-state note reconciled with the actual chain (`0f5f307` carried only the device report; `82cb64f` carried the v1.0.28 spec + `RECONCILE_REPORT.md`; `origin/main` = `82cb64f`, 3 ahead of `b68ac2a`); Documentation map added to the header. **`SERVER_SOURCE_ARCHITECTURE.md`:** Section 1 rewritten to current HEAD state (old text preserved as a labelled historical appendix); §3 schema sketch annotated superseded by §10; §7 Q2 "API 21" corrected to the project minSdk API 24; broken §2.2 cross-reference fixed; §2/§8 annotated with landed-status. **`README.md`:** implemented features updated (emergency sources, manual servers), a clearly-labelled Planned section added, build commands corrected to the real flavor/CI/signing facts, attribution extended to the full §1.4 dependency list, documentation map added. No code, test, build, schema, or manifest file was modified. |
| 1.0.30 | 2026-10-18 | SPEC-INTEGRITY-AUDIT (read-only audit + path repair; one doc-comment-only source commit) | **Restored spec integrity after the `docs/reports/` reorganization (`a76bdc6`).** A read-only audit resolved all `*.md` tokens: 38 moved-only under `docs/reports/`, 4 at root, 2 under `SoftEtherClient/`. **19 bare path references updated** to `docs/reports/…` in the authorized regions (§1.6.2–§1.6.21: 14; §12.5.1: 5); the other 46 bare occurrences (in §1.6.1's existing notes, §3.1, §6.7, §12.4, the existing §20 rows, and one `RUDP_IMPLEMENTATION_PLAN.md` in §12.5.1) are recorded in §1.6.22 rather than edited (out of scope) and still resolve via their documented location. Recorded the **concurrent-worktree contamination** event (`82cb64f` + `82476bf`) as a process lesson in §1.6.22 (a working tree must be single-owner; a second session needs a separate clone). **`HttpFetcher.kt` doc-comment change committed** (`9d569c3`) — comment-only, no API change — removing the last tracked dirty file. **The 1 skipped `:app` test identified:** `HtmlParserOracleTest.softetherUdpOnlyNeverBorrowsOpenvpnTcp`, an `AssumptionViolatedException` (data-dependent, not a failure). `:app` **790/0/0/1**, `:SoftEtherClient` **173/0/0/0**. §22 byte-identical (99 lines / 6,504 bytes); §1.6.2–§1.6.21 otherwise untouched. |
| 1.0.31 | 2026-10-18 | AUTOCONNECT-FIX (device-free; two AutoConnect audits closed) | **Closes four audit gaps plus OP-R6.** D-1: default attempt timeout unified to **12 s** (Settings key `SETTING_AUTO_MODE_TIMEOUT_SECONDS`, clamped **5–60 s**; `AutoModeController.DEFAULT_ATTEMPT_TIMEOUT_MS` `25_000L` → `12_000L`, spec §6.7 R5 / §6.8). D-2: exception in `connect()` advances to the **next protocol** (not next server) — `break` → `continue` at `AutoModeController.kt:1446` (D-2 / OP-R4). D-4: `isTunnelBusy()` guard wired to the production button path — `Disconnected`/`Error` press refused with `[AUTO] start refused: a tunnel is already active` at `AutoModeController.kt:1010`. D-5 (OP-R6): pressing while `Connected` continues from the next server via `resumeFromIndex = currentServerIndex + 1` (`AutoModeController.kt:532`, consumed at `:1026`, reset at `:1773`). D-6: four terminal conditions documented in §6.7 R7; native connect budget is **80 % of `T`** (`NATIVE_TIMEOUT_FRACTION_NUM/DEN = 4/5`, `AndroidConnectionAdapter.kt:74-75`; `MIN_NATIVE_CONNECT_TIMEOUT_MS = 1_500L` at `:84`). D-7: new **§6.8** Per-Attempt Timeout and Rotation Contract. D-8: six new tests in `AutoModeControllerTest.kt` (T-NEW-1..T-NEW-6) plus the `DEFAULT_ATTEMPT_TIMEOUT_MS` assertion corrected to `12_000L` in `AutoModeNativeBusyTest.kt:511`. JVM: `:app` **~796/0/0/1** (one skip = `HtmlParserOracleTest`, unchanged), `:SoftEtherClient` **173/0/0/0**. Standalone `:app:assembleProDebug` build gate passed under `CI=true`; APK SHA-256 recorded in `AUTOCONNECT_FIX_REPORT.md`. §22 byte-identical; §1.6.2–§1.6.22 untouched. No device, no tag, no force, no `git add -A`. |
| 1.0.32 | 2026-10-18 | AUTOCONNECT-FIX-2 (device-free; two bugs found on hardware) | **Closes the two defects the v1.0.31 device verification found, and instruments a third.** BUG-A: the D-4 refusal guard was blind to `CONNECTED` — v1.0.31 wired `isTunnelBusy` to `GlobalVpnTracker.isBusy` (`CONNECTING \|\| DISCONNECTING`), so a cleanly established manual tunnel was invisible, the run started anyway and tore the user's manual session down (`DEVICE_VERIFY_AUTOCONNECT_FIX_REPORT.md` §5). New `VpnConnectionStatus.isActiveOrBusy` (`CONNECTED \|\| CONNECTING \|\| DISCONNECTING`) + `GlobalVpnTracker.isActiveOrBusy`; `AutoModeEngine.kt` now supplies it. `isBusy` semantics unchanged. BUG-B: `recordWedge` was reachable only from `TIMED_OUT`, so a server refused on every protocol could never be excluded — `vpn131251792` was re-selected in four consecutive runs and each ended `Native slot not released within 25000ms … stopping` (§7). `AutoModeController` now counts `NATIVE_BUSY` per server per run and records one wedge at `NATIVE_BUSY_WEDGE_THRESHOLD = 2`; `WedgeTracker.EXCLUSION_THRESHOLD` unchanged. Slot-non-release **instrumented only** (log-only, no control-flow change): a line when `isOwnTunnelEstablished()` answers `false` while the tracker says `CONNECTED`, and a `SLOT_UNRELEASED_DIAG` line carrying the measured wait, outcome, protocol, server, tracker status, ownership answer and registry descriptor — fed by a new read-only `ConnectionAdapter.tunnelTrackerStatus()`. Root cause **UNKNOWN**; no fix attempted. S1 recorded as a **device-state deviation** (`SETTING_AUTO_MODE_TIMEOUT_SECONDS = 5` stored on 5585fecc), not a code defect. Four new tests (T-NEW-7..T-NEW-10); `:app` **800/0/0/1**, `:SoftEtherClient` **173/0/0/0**. §6.8.3/§6.8.6 updated (wedge rule; predicate table). §22 byte-identical (99 lines / 6,504 bytes); §1.6.2–§1.6.23 untouched. Device re-verification of S3/S5 **PENDING**. No device command, no tag, no force, no `git add -A`. |
| 1.0.33 | 2026-10-18 | AUTOCONNECT-FIX-3 (device-free; two defects found by the v1.0.32 device run) | **Closes the instrumentation defect and the budget defect that the v1.0.32 device verification exposed, and nothing else.** Fix 1 — the diagnostic was on the wrong site: `AutoModeController.kt` has **two** whole-run terminals for a held native slot, and every on-device termination came from the NATIVE_BUSY branch (formerly `:1695`, no diagnostic) rather than the post-attempt branch (`~:1834`, which carried `SLOT_UNRELEASED_DIAG`), so `SLOT_DIAG` was **NOT EXERCISED** despite three slot-based stops (`DEVICE_VERIFY_AUTOCONNECT_FIX_2_REPORT.md` §7). Both sites now emit the same body; at the NATIVE_BUSY site `outcome=${AttemptOutcome.NATIVE_BUSY.name}` is hard-coded because `outcome` is not in scope and the branch can produce nothing else. Wait condition, terminal order and all branches unchanged — additions are `slotWaitStartedAt`, `waitedMs` and the log call only. Fix 2 — budget floor: `nativeSlotAwaitMs` was `5 × T` capped at 60 s, which the device's persisted `T = 5 s` turned into **25 s** against measured release latencies of 6.0/6.2/7.5/13.3/13.7/17.0/**20.9 s**, one release landing **292 ms after** the cutoff; the derivation is now `.coerceAtLeast(AutoModeController.NATIVE_SLOT_AWAIT_MS)` (**30 s**) beneath the unchanged `.coerceAtMost(NATIVE_SLOT_AWAIT_MAX_MS)`. Rationale: release latency belongs to the previous operation's own duration, not to the user's server-attempt timeout. One new test (`nativeSlotAwaitHasAThirtySecondFloor`) pins the constant and the derivation seam; no existing test modified or deleted. `:app` **801/0/0/1** (+1, skip unchanged), `:SoftEtherClient` **173/0/0/0**. APK SHA-256 `42D90C38…CAB9D14E` (differs from v1.0.32 as expected). §6.8.7 updated (floor term). New §1.6.25. §22 byte-identical (99 lines / 6,504 bytes); §1.6.2–§1.6.24, §12.5.1, §12.5.2 untouched. Device re-verification of `SLOT_DIAG` and of the floor's efficacy **PENDING**; slot-non-release root cause still **UNKNOWN** and deliberately not attempted. No device command, no tag, no force, no reset/clean/stash, no `git add -A`. |
| 1.0.34 | 2026-10-18 | AUTOCONNECT-FIX-4 (device-free; driven by the v1.0.33 device run) | **Closes the unbounded RUDP fallback handshake and the wedge counter's structural gap — the two reasons every timeout budget failed identically on the same endpoint.** Fix-A (native): the NAT-T/RUDP branch called `perform_tls_handshake` with no timeout application while the TCP branch applied one at `softether_protocol.c:2839`, so the RUDP BIO read could block indefinitely (device: 28.5 s of silence on `vpn131251792`, `DEVICE_VERIFY_AUTOCONNECT_FIX_3_REPORT.md` §7.3) and the first `SSL_do_handshake` never returned, leaving the `max_attempts` loop with nothing to iterate; the fallback now calls `socket_apply_timeout(conn->socket_fd, conn->timeout_ms)` at `:2935` and emits the same `[TLS] handshake entry` discriminator tagged `(RUDP)` at `:2941`, fail-open like the TCP site. `conn->socket_fd` is the RUDP socketpair's app-facing fd, i.e. exactly what OpenSSL reads from — the same lever the parallel UDP race path already uses at `:2036`. Fix-B (Kotlin): `nativeBusyThisServer` can never reach `NATIVE_BUSY_WEDGE_THRESHOLD` on a first-attempt terminal, so the ledger was never written and the endpoint was re-selected in 5/5 runs (§7.4); the terminal now records one wedge before giving up (`AutoModeController.kt:1729-1736`), so the process-scoped `WedgeTracker` accumulates across runs and the unchanged `EXCLUSION_THRESHOLD` (2) applies. The AUTOCONNECT-AUDIT D-5 rule is **narrowed, not reverted**: `giveUpWithUnresolvedNativeOperation` still records nothing and still serves the post-attempt site. STEP 4 (bound the RUDP BIO read in project code) was deliberately **not** applied — no custom BIO exists, the blocking call is OpenSSL's socket-BIO `recv()`. Two new tests (`aNativeBusyTerminalRecordsAWedgeThatSurvivesAcrossRuns`, `theRudpFallbackHandshakeIsBoundedLikeTheTcpPath`); no existing test modified or deleted. `:app` **803/0/0/1** (+2, skip unchanged), `:SoftEtherClient` **173/0/0/0**. APK SHA-256 `4ABC4636…624B1435` (differs from v1.0.33 as expected — the native library changed). §6.8.3 updated (third wedge sub-rule; native timeout invariant). New §1.6.26. §22 byte-identical (99 lines / 6,504 bytes); §1.6.2–§1.6.25, §12.5.1, §12.5.2 untouched. Device re-verification **PENDING**; the endpoint's own refusal cause remains **UNKNOWN** and deliberately not attempted. No device command, no tag, no force, no reset/clean/stash, no `git add -A`. |
| 1.0.35 | 2026-10-19 | AUTOCONNECT-FIX-5 (device-free; driven by the v1.0.34 device run) | **Stops paying ~6 s per failed protocol for a retry the registry has already decided to refuse.** The v1.0.34 device run (`DEVICE_VERIFY_AUTOCONNECT_FIX_4_REPORT.md` §15) counted 12 post-slot-release retries in one five-run session: 10 ended instantly with `NATIVE_ERROR` (~0.1 s), 2 with `NATIVE_BUSY` (~5 s), **zero** reached `TUNNEL_UP`, and **11 of 12** were preceded by a `NATIVE_UNRESOLVED` teardown — the one outcome whose own KDoc says ownership is retained and every later start is refused. `ConnectionAdapter` gains `lastTeardownWasResolved()` (`AutoModeController.kt:322`, default `true`); `AndroidConnectionAdapter` retains the outcome it already computes (`:106`, assigned `:302`, override `:318-319`) and reports `false` only for `NATIVE_UNRESOLVED`; the `NATIVE_BUSY` branch consults it after the slot is confirmed free (`AutoModeController.kt:1813`) and, when unresolved, logs `Skipping retry on <protocol>: previous teardown was unresolved` and advances straight to the next protocol. `NATIVE_CONFIRMED`, `NOOP_REENTRANT`, `FOREIGN_STACK_TERMINAL` and a `disconnect()` timeout all keep the pre-v1.0.35 retry, so the change is confined to the state the evidence covers; the NATIVE_BUSY wedge accounting above the gate is untouched. Two new tests (`unresolvedTeardownSkipsThePostReleaseRetry` at `:3161`, `resolvedTeardownKeepsThePostReleaseRetry` at `:3214`); no existing test modified or deleted. `:app` **805/0/0/1** (+2, skip unchanged), `:SoftEtherClient` **173/0/0/0**. APK SHA-256 `8FB20696…92FD801` (differs from v1.0.34 although no native source changed — the APK embeds the compiled Kotlin). §6.8.3 updated (post-release retry gate); new §1.6.27. Both prior-phase reports (`DEVICE_VERIFY_AUTOCONNECT_FIX_4_REPORT.md`, `SLOT_RACE_INVESTIGATION_PREP_REPORT.md`) are committed here. **Fix-A and Fix-B remain UNVERIFIED** — Fix-A's RUDP site is unreachable while `softether_try_nat_t_connect` returns `error=6`, and no run reached the `NATIVE_BUSY` terminal — and neither file was touched. §22 byte-identical (99 lines / 6,504 bytes); §1.6.2–§1.6.26, §12.5.1, §12.5.2 untouched. No device command, no tag, no force, no reset/clean/stash, no `git add -A`. |
| 1.0.36 | 2026-10-19 | AUTOCONNECT-FIX-6, Option A (device-free; driven by the unfiltered-network device run) | **Stops destroying live tunnels and wedging healthy servers, and cuts the per-protocol worst case from `7T` to `3T`.** The v1.0.34 build was re-run on an **unfiltered** WiFi network (`DEVICE_VERIFY_AUTOCONNECT_FIX_4_UNFILTERED_REPORT.md` §5.3): **3 of 4** runs destroyed a **working** tunnel (`DHCP SUCCESS` + `SLOT_UNRELEASED_DIAG … tracker=CONNECTED ownTunnel=true`, written by the code that then destroyed it) and **1** excluded a healthy server after two false wedges. Root cause: `awaitArmedOutcome`'s timeout fires a few hundred ms before `CONNECTED`, so `isNativeSlotBusy()` is true because **our own** operation is still running; the `NATIVE_BUSY` branch then waited for a slot held by the tunnel it had just established, and the terminal tore it down. The post-attempt path already re-checked ownership; this branch never did. Fix-1: `adapter.isOwnTunnelEstablished()` at the top of the branch (`AutoModeController.kt:1683`) — a live tunnel is treated as `Connected` (`:1685`), with no wait, no retry and no wedge. Fix-2: the `NATIVE_BUSY` slot wait is capped to `min(nativeSlotAwaitMs, attemptTimeoutMs)` (`:1772`), with the terminal log and `SLOT_UNRELEASED_DIAG` `budget=` reporting the capped value (`:1820`, `:1828`). Fix-3: the same cap post-attempt (`:2003`, `:2010`, `:2019`). The retry mechanism is **kept** (Option A) and the v1.0.35 `lastTeardownWasResolved` gate still skips it when the preceding teardown was unresolved (`:1865`), so the three cases are `1T` (own tunnel up), `2T` (unresolved teardown) and `3T` (resolved teardown, retry fires) — previously `1T`, `7T`, `7T`, with the first reachable but never checked. **Option B** (remove the retry for a strict `2T`) was **deferred**: it breaks **9** documented tests, so the prior phase stopped at STEP 2 without editing any file and the operator chose Option A. Three new tests (`nativeBusyWithOwnTunnelUpIsTreatedAsConnected` `:3285`, `nativeBusySlotWaitIsCappedToTheAttemptBudget` `:3334`, `postAttemptSlotWaitIsCappedToTheAttemptBudget` `:3374`); no existing test modified or deleted; the fake gained `ownTunnelEstablished = false` (`:116`) and `slotWaitTimeouts` (`:92`), both additive with behaviour-preserving defaults. `:app` **808/0/0/1** (+3, skip unchanged), `:SoftEtherClient` **173/0/0/0**. APK SHA-256 `BE345075…4ECF249` (differs from v1.0.35 as expected; no native source changed). The first build failed with `There is not enough space on the disk` on a volume reporting 0 bytes free — an environment fault, resolved by deleting regenerable build outputs and stale temp artifacts, after which identical sources built green. §6.8.3 updated (own-tunnel row + sub-note); §6.8.7 updated (cap + the three-case table); new §1.6.28. `DEVICE_VERIFY_AUTOCONNECT_FIX_4_UNFILTERED_REPORT.md` committed here. **Fix-A remains UNVERIFIED** (zero NAT-T rendezvous, zero `[TLS] handshake entry (RUDP)`) and untouched. §22 byte-identical (99 lines / 6,504 bytes); §1.6.2–§1.6.27, §12.5.1, §12.5.2 untouched. No device command, no tag, no force, no reset/clean/stash, no `git add -A`. |
| 1.0.37 | 2026-10-19 | AUTOCONNECT-FIX-7 (device-free; driven by the v1.0.36 device run) | **Undoes the v1.0.36 regression and makes the code obey the contract §6.8.4 has stated since v1.0.31.** `DEVICE_VERIFY_AUTOCONNECT_FIX_6_REPORT.md` §7.1 measured v1.0.36 on hardware: **2 of 5** runs terminated `ERROR_NATIVE_UNRESOLVED` on the **first** server, the controller giving up **431 ms** after a native attempt started and **7.7 s before** the stack reached its own verdict (`VPNCONNECT watermark failed — server may not be SoftEther`). Root cause: `awaitArmedOutcome` re-checked `isNativeSlotBusy()`, which answers "busy" both for a **refusal** (a previous operation owns the slot; the server was never contacted) and for **our own** attempt still unwinding after its timeout — so every case-1 timeout was mis-labelled `NATIVE_BUSY` and its slot-wait expiry was a run terminal. Fix-1 (adapter): new `@Volatile lastArmedBusyRefused`, reset in `armTunnelWatch` (`AndroidConnectionAdapter.kt:349`) and captured in `awaitArmedTunnel`'s `finally` (`:406`) from `armedNativeBusy?.isCompleted` — a deferred that completes **only** through `TunnelStateWatcher.onSoftEtherNativeBusy`, i.e. only on a refused start. `awaitArmedOutcome` (`:497`) now returns `NATIVE_BUSY` iff the refusal fired, `TIMED_OUT` otherwise; `isNativeSlotBusy()` is kept for the admission checks. Fix-2 (controller): the slot-wait terminal is gone from **both** sites — the `NATIVE_BUSY` branch (`AutoModeController.kt:1774`) and the post-attempt branch (`:1991`) now log `Slot-wait expired on <protocol>; moving on (no terminal, per §6.8.4)`, keep their existing `SLOT_UNRELEASED_DIAG` line, issue one `disconnect()` and `break`. Fix-3 (contract): no path publishes `ERROR_NATIVE_UNRESOLVED`; `ERROR_NO_SERVER` is the only exhausted-list terminal (§6.8.4 row removed and table renumbered); the constant and the now-unreferenced `giveUpWithUnresolvedNativeOperation()` are kept so the device evidence they encode is not silently deleted, and the v1.0.34 terminal wedge is **withdrawn** (a refusal is not evidence about the endpoint — `TIMED_OUT` carries that role, and cross-run exclusion is re-pinned through it). **Eight tests rewritten under explicit operator authorization, none deleted** (2 in `AutoModeControllerTest`, 6 in `AutoModeNativeBusyTest` — the latter had pinned the *opposite* of §6.8.4 since v1.0.28, which is the hidden contradiction §1.6.29 bullet 8 records) plus **four new** (T-NEW-18..21; T-NEW-21 pins OP-R6 try-next **ordering**). `:app` **812/0/0/1** (+4, skip unchanged), `:SoftEtherClient` **173/0/0/0**. `CI=true :app:assembleProDebug` SUCCESSFUL, APK SHA-256 `4FE44174…9EB6E68` (63,755,062 bytes). New §1.6.29; §6.8.3 NATIVE_BUSY and TIMED_OUT rows and the wedge rule updated; §6.8.4 terminal list updated. §22 byte-identical (99 lines / 6,504 bytes); §1.6.2–§1.6.28, §12.5.1, §12.5.2 untouched. **Fix-A remains UNVERIFIED**; the three fixes here are SOURCE CODE + TEST only — no device run in this phase. No tag, no force, no `git add -A`. | **Stops destroying live tunnels and wedging healthy servers, and cuts the per-protocol worst case from `7T` to `3T`.** The v1.0.34 build was re-run on an **unfiltered** WiFi network (`DEVICE_VERIFY_AUTOCONNECT_FIX_4_UNFILTERED_REPORT.md` §5.3): **3 of 4** runs destroyed a **working** tunnel (`DHCP SUCCESS` + `SLOT_UNRELEASED_DIAG … tracker=CONNECTED ownTunnel=true`, written by the code that then destroyed it) and **1** excluded a healthy server after two false wedges. Root cause: `awaitArmedOutcome`'s timeout fires a few hundred ms before `CONNECTED`, so `isNativeSlotBusy()` is true because **our own** operation is still running; the `NATIVE_BUSY` branch then waited for a slot held by the tunnel it had just established, and the terminal tore it down. The post-attempt path already re-checked ownership; this branch never did. Fix-1: `adapter.isOwnTunnelEstablished()` at the top of the branch (`AutoModeController.kt:1683`) — a live tunnel is treated as `Connected` (`:1685`), with no wait, no retry and no wedge. Fix-2: the `NATIVE_BUSY` slot wait is capped to `min(nativeSlotAwaitMs, attemptTimeoutMs)` (`:1772`), with the terminal log and `SLOT_UNRELEASED_DIAG` `budget=` reporting the capped value (`:1820`, `:1828`). Fix-3: the same cap post-attempt (`:2003`, `:2010`, `:2019`). The retry mechanism is **kept** (Option A) and the v1.0.35 `lastTeardownWasResolved` gate still skips it when the preceding teardown was unresolved (`:1865`), so the three cases are `1T` (own tunnel up), `2T` (unresolved teardown) and `3T` (resolved teardown, retry fires) — previously `1T`, `7T`, `7T`, with the first reachable but never checked. **Option B** (remove the retry for a strict `2T`) was **deferred**: it breaks **9** documented tests, so the prior phase stopped at STEP 2 without editing any file and the operator chose Option A. Three new tests (`nativeBusyWithOwnTunnelUpIsTreatedAsConnected` `:3285`, `nativeBusySlotWaitIsCappedToTheAttemptBudget` `:3334`, `postAttemptSlotWaitIsCappedToTheAttemptBudget` `:3374`); no existing test modified or deleted; the fake gained `ownTunnelEstablished = false` (`:116`) and `slotWaitTimeouts` (`:92`), both additive with behaviour-preserving defaults. `:app` **808/0/0/1** (+3, skip unchanged), `:SoftEtherClient` **173/0/0/0**. APK SHA-256 `BE345075…4ECF249` (differs from v1.0.35 as expected; no native source changed). The first build failed with `There is not enough space on the disk` on a volume reporting 0 bytes free — an environment fault, resolved by deleting regenerable build outputs and stale temp artifacts, after which identical sources built green. §6.8.3 updated (own-tunnel row + sub-note); §6.8.7 updated (cap + the three-case table); new §1.6.28. `DEVICE_VERIFY_AUTOCONNECT_FIX_4_UNFILTERED_REPORT.md` committed here. **Fix-A remains UNVERIFIED** (zero NAT-T rendezvous, zero `[TLS] handshake entry (RUDP)`) and untouched. §22 byte-identical (99 lines / 6,504 bytes); §1.6.2–§1.6.27, §12.5.1, §12.5.2 untouched. No device command, no tag, no force, no reset/clean/stash, no `git add -A`. |

### Verification Status

| Section | Verification |
|---------|-------------|
| Project Baseline (§1) | VERIFIED — `build.gradle`, `gradle-wrapper.properties`, module structure |
| §1.6.29 — AUTOCONNECT-FIX-7 (v1.0.37) | VERIFIED at SOURCE CODE + TEST level — adapter `lastArmedBusyRefused` and both controller branches inspected; `:app` **812/0/0/1**, `:SoftEtherClient` **173/0/0/0**; 8 tests rewritten and 4 added. **NOT device-verified** — no device run in this phase; Fix-A remains UNVERIFIED |
| Architecture Overview (§2) | VERIFIED — source file inspection, now includes product requirement for dynamic server-source access per §11.8 |
| Local Mirrors / Working Copies (§2.6) | HISTORICAL — attributed to this phase (DOCS-HYGIENE-v1.0.13); the D: I/O measurements are the PHYSICAL DEVICE evidence recorded by RESUME-R4B-FROM-C-DRIVE (PRIOR REPORT), not re-measured here |
| F.28 History (§3) | HISTORICAL — compiled from source comments (`Stage 2F-F.28X` markers) and audit notes; §3.3 F.28AL-U/V/W status is attributed to those phases, not independently re-verified here |
| Current Native Lifetime Model (§4) | VERIFIED — `NativeLifetimeRegistry.kt`, `NativeAttemptCoordinator.kt`, `softether_protocol.c`, `ConnectionController.kt` |
| Teardown Completion Identity (§5) | VERIFIED — `TeardownCompletion.kt`, `SoftEtherVpnService.kt` (lines 100–139, 434–807) |
| Auto Connect / Auto Mode (§6) | VERIFIED — `AutoModeController.kt`, `AndroidConnectionAdapter.kt`, `AutoModeProtocol.kt`, `TunnelStateWatcher.kt`, `AutoModeModels.kt` |
| Server Data Model (§7) | VERIFIED — `VpnModels.kt`, `VPNGateItem.kt`, `VPNGateConnection.kt` |
| Server Collector (§8) | VERIFIED — `VpnServerRepository.kt`, `VpnGateSources.kt`, `VpnServerMerger.kt`, `ServerQualityCalculator.kt` |
| Connection Flow (§9) | VERIFIED — `SoftEtherVpnService.kt` (full start/teardown/destroy paths) |
| Auto Mode Engine (§10) | VERIFIED — source inspection |
| Binding Design Decisions (§11) | VERIFIED — native code contract confirmed at `softether_protocol.c:1098` |
| Dynamic Server-Source Access (§11.8) | DESIGN — product requirement stated; current implementation observation preserved separately |
| Emergency Server Sources (§11.9) | DESIGN — settings requirement stated; CSV schema explicitly deferred *(v1.0.20: **superseded — §11.9.3 now records arch doc §10 as the approved design basis**; parser deferred to roadmap item C; see §1.6.13 bullet 3; v1.0.21: **§11.9.2 annotated with the implemented safety set — parser and fetch boundary landed, items D–H and retention still open**; see §1.6.14 bullets 3 and 7)* |
| Manual Server Entry (§11.10) | DESIGN — product requirement stated; input formats not decided |
| Persistence/Merge/Retention (§11.11) | DESIGN — principles stated; database schema not designed |
| Future Extensibility (§11.12) | DESIGN — goal stated; no interfaces mandated |
| Remaining Work (§12) | PARTIAL — F.28AD-A scope pending definition; test gaps identified; roadmap and acceptance criteria added |
| Active Fix Queue (§12.5.1) | HISTORICAL — row 1 (FIX-NATIVE-LIFETIME-SYNC) corrected to CLOSED for N-15/N-16 (device-verified; commits `3a2f442`, `b0418cc`); rows 8/9/10 track the still-open T5-SLOT driver, the T5-SLOT-TEARDOWN residual (narrowed in v1.0.11, cause still UNKNOWN), and the Axis A/B/C audit register. Row 9's narrowing and row 10 are attributed to the v1.0.11 device-verify report and the read-only axes audit respectively, not independently re-verified here. In v1.0.14, row 7 (RISK-B) was changed to **CLOSED** by the call-graph audit (§1.6.7), row 4 (N-06/SEC-01) and row 5 (N-09) were refined (row 5's line refs corrected), and rows 13 (SEC-02/APP-1) and 14 (N-11) were added |
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
| AutoConnect Root-Cause Resolution (§1.6.4) | HISTORICAL — attributed to the v1.0.10 docs phase (this phase). The audit, native fix (`eb21e56`), Kotlin bundle (`4df7e79`), test repair (`a87c523`), device evidence, and residual finding are compiled from the phase reports and the FINALIZE device log; not independently re-verified here. The residual T5-SLOT-TEARDOWN finding is recorded OPEN — UNKNOWN |
| Native Hardening Bundle (§1.6.5) | HISTORICAL — the unknown-patch characterization (verdict FIX), the compile repair (`c8acb5d`), the defensive budget guard (`1a4818a`), and the device evidence are each attributed to their own phase report (`INVESTIGATE_UNKNOWN_NATIVE_CHANGES_REPORT.md`, `PRE_DEVICE_BUDGET_GUARD_REPORT.md`, `DEVICE_VERIFY_HARDENING_AUTO_REPORT.md`) rather than re-verified in this revision; the device statements are PHYSICAL DEVICE evidence for that phase. Goal F of the device verification is PARTIAL because `/proc/<pid>/fd` is Permission-denied, so the fd-leak check is INCONCLUSIVE (thread count used as the proxy). The T5-SLOT-TEARDOWN narrowing is sourced from the same device-verify report; the upstream trigger of the re-entrant teardown remains UNKNOWN |
| Build and Verification Methodology (§22) | HISTORICAL — each lesson attributed to the incident that produced it; §21 intentionally unassigned |
| Test Flakiness and Docs Bundle (§1.6.6) | HISTORICAL + TEST — the OPEN-1 / OPEN-2 root causes are attributed to direct source inspection of `VpnServerRepository.refresh` (§8.1) and `AutoModeControllerTest`; the fixes and the regression test are TEST evidence, as are the three green JVM runs (`:SoftEtherClient:test` 160/0/0/0; `:app:testProDebugUnitTest` 647/0/0/1 twice). The "no APK / no device" statement is verified from the phase's own command log. Not independently re-verified here beyond the recorded runs |
| Security Audit and RISK-B Closure (§1.6.7) | HISTORICAL + SOURCE CODE — the SEC-01 / SEC-02 / N-09 / N-11 characterisations are direct source inspection at the stated file/line; the RISK-B closure is SOURCE CODE (exhaustive call-graph) plus TEST (no test pins the method). All designs are DEFERRED and no fix was applied; the findings are not independently re-verified here |
| Security Fixes Landed + Device-Verified (§1.6.8) | HISTORICAL — the four fixes are attributed to the FIX-NATIVE-SECURITY-SMALL implementation phase (commits `f66f464`, `1bbf8a6`; SOURCE CODE + TEST) and the device statements to DEVICE-VERIFY-SECURITY-SMALL (PHYSICAL DEVICE), not re-verified in this revision. SEC-01 remains OPEN — design deferred |
| SEC-01 TOFU Fingerprint Pinning (§1.6.9) | HISTORICAL + PARTIAL — attributed to the FIX-SEC01-TOFU-PINNING-IMPL phase (commits `b652198`, `9954c03`; SOURCE CODE + TEST) and its FIX-SEC01-RECONNECT follow-up (`ff78116`; SOURCE CODE + TEST); the **primary-handshake** device statements are PHYSICAL DEVICE evidence from Phase 9 (FIX-SEC01-TOFU-PINNING) and Phase 10 (VERIFY-AND-FIX-SEC01-RECONNECT), not re-verified in this revision. The **reconnect** handshake is recorded explicitly as **UNKNOWN — not device-reproduced** (coverage is SOURCE CODE + 2 source-shape tests only). This revision ran no build, test, or device command |
| PART A (Axis A) Landed, with an Explicit ANR Caveat (§1.6.10) | HISTORICAL + PARTIAL — the four Axis A changes are attributed to commit `067d4a3` (SOURCE CODE) and to the 24 added JVM tests (TEST); PARTs B/C/D are attributed to their already-landed commits (`a9e16fd`, `607c651`, `c572050`) and PART D's guard probe to the IMPLEMENT-AND-VERIFY-P1-REMAINING phase (TEST). The JVM/build figures are carried forward as **PRIOR REPORT** — this revision re-ran nothing. The **ANR finding** is PHYSICAL DEVICE evidence (logcat) for the device run in which it occurred, with an explicit **negative assurance** (the Axis A paths did not run), two clean controls, **NOT REPRODUCED**, and the blocked frame recorded as **UNKNOWN** because `/data/anr` is unreadable without root; the most probable remaining explanation is labelled **INFERENCE**, not fact. This revision ran no build, test, or device command |
| Line-Reference Corrections and Tag Decision (§1.6.11) | HISTORICAL — the `SSL_CTX_set_verify` location is **SOURCE CODE** (verified at `aes_wrapper.c:111` by direct inspection at HEAD; the `:110` citations in the frozen §1.6.7/§1.6.8 are left unmodified and the drift is attributed to commit `b652198`'s added include); the `p2.4.10` tag state is **SOURCE CODE** (local `git tag -l` lists it; `git ls-remote --tags origin` does not, and it resolves to `0714628`), with the decision and its rationale recorded and **no tag operation performed**; the Phase 12 report commit is **SOURCE CODE** (git). This revision ran no build, test, or device command |
| Documentation Corrections (§1.6.12) | HISTORICAL — all six bullets are **SOURCE CODE** (git history and direct inspection of `app/build.gradle` at `0714628` and `5c13603`, the annotated tag body of `p2.4.10` object `c1013806…`, and `git rev-list --count p2.4.9..p2.4.10` = 1); bullet 4 additionally carries **INFERENCE** for the judgment that a committed report must not be retro-edited. The `p2.4.10` push is **SOURCE CODE** (`git ls-remote --tags origin` now lists it; remote unique tag set 4 → 5; tag object byte-identical to the local one). The superseded v1.0.18-era texts (the §1.6.1 preamble, the v1.0.18 push-state note's "two own commits", and the §1.6.3 / §1.6.11 / `DOCS_UPDATE_v1.0.17_REPORT.md` §7 `p2.4.10` rationale) are explicitly **not** re-verified and are recorded as frozen historical artifacts. This revision ran no build, test, or device command |
| Z1-IMPL-B — Emergency Source Foundation (§1.6.13) | HISTORICAL — the item B landing is **SOURCE CODE** (the files listed in §1.6.13 bullet 1, inspected at this revision's commits); the JVM counts and the successful build are **TEST**; the migration verification is JVM-only (**TEST**) and a real device 5→6 upgrade is **UNKNOWN**; device verification is **PHYSICAL DEVICE (not performed)**; item A's already-implemented status is **SOURCE CODE + PRIOR REPORT** (§1.6.10 bullet 8, commit `a9e16fd`) |
| Z1-IMPL-C — Emergency CSV Parser + Wire-In (§1.6.14) | HISTORICAL — the item C landing is **SOURCE CODE** (the parser, the fetcher, the `SourceIdentity` tag route, the `emergencyFetch` parameter and the `ServerListUpdates` lambda, inspected at this revision's commits); the JVM counts, the successful build and the 46 new test cases are **TEST**; the zero-behaviour-change invariant is **TEST** (all 678 pre-existing tests pass unchanged, including every `ServerListUpdater` case); the §10 column mapping, the fail-closed schema check and the RUDP rule are **SOURCE CODE + TEST**; device verification is **PHYSICAL DEVICE (not performed)**, so a configured URL → live fetch → stored dataset → migration 5→6 on a real database is **UNKNOWN**; the 1 MiB cap bounding parsing rather than bytes on the wire is a recorded **SOURCE CODE** limitation, not a verified bound |

| Z1-IMPL-D — Manual Server Entry + MANUAL-Preservation (§1.6.15) | HISTORICAL — the item D landing is **SOURCE CODE** (the model, entity, DAO, store, migration 6→7, the `manual` priority entry, the `withManualRows` merge, the ViewModel surface and the Settings section, inspected at this revision's commits); the JVM counts, the successful build and the 23 new test cases are **TEST**; the zero-behaviour-change invariant is **TEST** two ways — `withManualRows` returns the caller's own list **by identity** for an empty manual set, and all 724 pre-existing tests pass unchanged including every `ServerListUpdater` case; the preserved-versus-precedence split (a manual record is never deleted, but a value both sides claim goes to the collected source with the disagreement written into `conflicts`) is **SOURCE CODE + TEST** and its policy is **PRIOR REPORT** — `SERVER_SOURCE_ARCHITECTURE.md` §3.3/§4.2, confirmed by the operator against the phase brief's opposite wording; the IPv4-only manual address is **INFERENCE** from the two rejection points (`normalizeServer`, `toConnection`), not a verified device behaviour; device verification is **PHYSICAL DEVICE (not performed)**, so adding a manual server → a live refresh that must keep it → migration 6→7 on a real database is **UNKNOWN**; the three recorded limits (the untouched `SourceIdentity.kt` `sourceTag` fall-through, the same-IPv4 row collapse, the connectable-less OpenVPN entry) are recorded **SOURCE CODE** limitations, not verified bounds |
| Z1-IMPL-G — Dataset-Integrity Outcomes + MANUAL Provenance Fix (§1.6.16) | HISTORICAL + PARTIAL — the provenance fix is **SOURCE CODE + TEST** (the exhaustive `sourceTag` `when` with no `else`, `MANUAL_TAG`, the pinned baseline tags of the seven other kinds, the mirror-id-disjointness check and the stray-mirror-id isolation, inspected at this revision's commits); the "mirror tag granted mirror-level authority (20, not 30) and left item D's priority entry dead" claim is **SOURCE CODE + TEST** (`Provenance.sourceGroup` leaves both mirror spellings outside `SOURCE_PRIORITY`); the **compile-time** benefit for a *future* `SourceKind` is **INFERENCE** — it is a language property, not an observed one; the JVM counts and the successful build are **TEST**; the **classification-only** nature of the outcomes is **SOURCE CODE + TEST** (`evaluate`'s signature and all seven thresholds unchanged, `outcome` a pure function of the existing verdict plus `stored.size >= MIN_ACCEPTABLE_DATASET_SIZE`, and two tests pinning "an outcome never contradicts its verdict" and the 9/10 stored-row boundary); that `ACCEPT_NEW` / `MERGE_WITH_EXISTING` carry **no add-only behaviour** is **SOURCE CODE**, because Cumulative Server Retention (arch doc §9.1) is not implemented and is outside this phase's authorized file set; the operator decision that kept a viable-but-smaller and a viable-but-disjoint dataset as `MERGE_WITH_EXISTING` (against the phase brief's `REJECT_DEGRADED`) is **PRIOR REPORT**; the §3.12 → §9.6/§10.8 citation correction is **SOURCE CODE** (arch-doc read); device verification is **PHYSICAL DEVICE (not performed)**, so a live outcome classification over a real refresh is **UNKNOWN** |
| Emergency-Sources Folder + Device Migration Verified (§1.6.17) | HISTORICAL + PARTIAL — the folder, the 29-column header and the README are **SOURCE CODE** (files inspected at this revision's commit); the CSV's conformance to §10.2/§10.3/§10.7 is **SOURCE CODE** (host-side validation: 29 columns, 3 rows, required columns conforming, public IPv4s only, ports in `0..65535`, literal booleans, every `OpenVPN_ConfigData_Base64` decoding, UTF-8/no-BOM/LF); the **provenance of the data** is the live capture of `vpngate.net/api/iphone/` + `vpngate.net/en/` on 2026-10-17, so `SourceTag="merged"` is **SOURCE CODE** in the §10.6 sense — but the file is a **point-in-time snapshot**, so its rows being reachable *today* is **UNKNOWN** and is expected to decay; the device migration 5 → 6 → 7 is **PHYSICAL DEVICE + TEST** (host-side DDL pre-flight against a base64-verified backup, post-migration `user_version=7`, per-table row-for-row SHA-256 identity, `identity_hash` change), recorded here from the prior phase rather than re-performed; the Android release level of `5585fecc` was not re-read this revision and stays **PRIOR REPORT**; the STEP-7 persistence result is **PHYSICAL DEVICE** (0 rows in both tables, empty `sqlite_sequence`, database byte-identical across a full session, one log line since launch) with the conclusion that the insert code never executed — the **absence of a product defect** is **SOURCE CODE** (full path and all validation gates verified, both DAOs assigned, Settings route reachable, no build-flag gating) and whether the operator's taps reached the app is **UNKNOWN**; the JVM figures in bullet 5 are **PRIOR REPORT**, explicitly **not** re-run this revision (no build and no test was executed) |
| Manual-Server Protocol Picker Overflow Fixed; Manual-Row Visibility Mechanism Proven (§1.6.18) | VERIFIED (Defect A) + PROVEN-MECHANISM (Defect B) — the pre-fix `Row` and the post-fix `FlowRow` are **SOURCE CODE** (diff inspected: 3 hunks, one file); the overflow itself is **INFERENCE** from **PHYSICAL DEVICE** measurements — chip widths 265/264/298/261 px and 22 px gaps were read from a live UI-hierarchy dump and the pre-fix off-screen coordinates are arithmetic from them, because the pre-fix build was not re-installed to observe the clipping directly; the fix being effective is **PHYSICAL DEVICE** (all four chips present, `clickable`/`enabled`/`checkable`, `MS-SSTP` wrapped to `[132–305]` and `checked='true'`); that `FlowRow`'s non-`overflow` overload is stable at foundation-layout 1.12.0 is **SOURCE CODE** (per-declaration annotation inspection of `FlowLayout.kt`), and "no `@OptIn` needed" is therefore a compile-verified fact rather than an inference, since the build succeeded without one; the 4 new tests and the 772/173 counts are **TEST**; the APK hash and the `-r` install are **PHYSICAL DEVICE**; the `user_version=7` / `integrity_check=ok` / both-rows-survived readings are **PHYSICAL DEVICE** (base64 transfer with decoded-size assertion, main + `-wal` + `-shm` replayed — a main-only read is a known **false negative** here, per §1.6.18 bullet 8); **Defect B's mechanism** is **PHYSICAL DEVICE + SOURCE CODE** (both the 12:20 `Preserved(DEGRADED_DATASET)` and the 13:16 `MERGE_WITH_EXISTING` / 107-row outcome, matched to the single `replaceAll` writer at `ServerListUpdates.kt:268`); the mapping of manual `label`→`operator` and `port`→`seTcpPort` is **PHYSICAL DEVICE** (the stored row was read); that a manual row therefore loses its protocol identity is **SOURCE CODE** (`VPNGateItem` has no `protocol`/`label` column); the correction of this revision's own STEP-2 claim is **PRIOR REPORT superseded by PHYSICAL DEVICE**; the `manualProtocolLabel` `else`-branch hazard is **SOURCE CODE** and **not fixed**; manual-row visibility remains **DEFERRED** by operator decision, so its end-state is **UNKNOWN** |
| Manual Server Immediate Visibility (§1.6.19) | VERIFIED (B) + PARTIAL (C) + TEST-GUARDED (D) — the write-through is **SOURCE CODE** (`addManualServer` → `ManualServerStore.materialize` → the same `withManualRows`), its preservation behaviour is **TEST** (5 tests: immediate presence, empty-table case, collision without duplication, two adds in sequence, removal not deleting), and its effect on a device is **PHYSICAL DEVICE** (two adds produced 107 → 108 → 109 with `Last updated` unchanged at 13:16 and no update log line after install, and both addresses rendered in a main-list search with `Score: 0` / `SoftEther`); the DB-side claim that each address appears exactly once with `operator` = label and `seTcpPort = 443` is **PHYSICAL DEVICE** (main + `-wal` + `-shm` replayed, base64 with decoded-size assertion against `stat -c %s` — a main-only read is a known **false negative** here); that no refresh occurred is **PHYSICAL DEVICE** (unchanged header timestamp + absence of update log lines) and, for the "no update line after install" part, **INFERENCE** from an empty filtered logcat rather than a positive assertion; **fix (C) is SOURCE CODE only** — the one-shot `refreshNotice`, the `DEGRADED_DATASET` guard and the `Toast` wiring are inspected and compile, but the notice was **never observed on a device** because forcing a degraded fetch requires cutting connectivity, so its live behaviour is **UNKNOWN**; **fix (D)** is **SOURCE CODE + TEST** (explicit branch per protocol, `else -> null`, raw-key fallback, and 3 tests walking `VpnProtocolNames.ALL`), and the claim that a compile-time guard is impossible is **SOURCE CODE** — a language property of `when` over `String`, not an observation, which is why the substitute guard is a test; the JVM counts, the APK hash and the successful third build are **TEST**, and the two earlier failed attempts are **PHYSICAL DEVICE** environment faults explicitly **not** attributed to the code (identical sources compiled green in the test run); the `DatasetIntegrity` `sizeFloor`/`hasComparableBaseline` edge is **SOURCE CODE + INFERENCE** (thresholds read, effect computed, not reproduced with a borderline dataset); that removing a manual server leaves its materialised row until the next accepted refresh is **SOURCE CODE + TEST** |
| FIX-D-2 — Triage 10 Failing Tests (§1.6.20) | VERIFIED (test reconciliation) + SOURCE CODE (one-line diagnostic fix) — the 10 failures are attributed to a lowercase log-format string in `fetchOnChosenPath` (inspected at VpnServerRepository.kt:362) and three OPEN-first behavioural assertions in `VpnServerRepositoryR4bTest.kt`, all classified bucket A; the `:app` suite is **TEST** 790/0/0/0 (R4bTest 10/0/0/0, RoutingTest 8/0/0/0), `:SoftEtherClient:test` **TEST** 173/0/0/0, and the APK SHA-256 is **TEST** (`f09e949d0af2cccf26d756af6c7b00ef7745c401857666228b25f8aa3c2c1279`); no bucket D (code bug) was found, so no production logic changed; §22 is BYTE-IDENTICAL (99 lines / 6,504 bytes) and §1.6.2–§1.6.19 are untouched |
| RECONCILE-DIRTY-FILES — Restore HEAD Buildability (§1.6.21) | VERIFIED (build reconciliation) + SOURCE CODE + TEST — the PART A defect is **SOURCE CODE** (`AutoModeEngine.kt:82-83` referencing `nativeSlotAwaitMs` / `NATIVE_SLOT_AWAIT_MAX_MS` absent from the committed `AutoModeController.kt`); the 7-file D-1b closure is **SOURCE CODE** (committed as `df7b49c`, +667/−332, `HttpFetcher.kt` deliberately excluded as category-(c) UNRELATED-IN-FLIGHT); the standalone build gate is **TEST** (`git archive HEAD | gradlew :app:assembleProDebug` SUCCEEDED on C:, APK SHA-256 `6e289cb179eec411c5b6ed6b5d9b3d8bfe70ba18a9c3ed4ee5939eab869f3248`; the D:-drive failure was an unrelated NDK/CMake `.cxx` metadata `Access is denied`, no `^e:` Kotlin errors); the JVM suites are **TEST** — `:app` **790/0/0/1** (60 classes), `:SoftEtherClient` **173/0/0/0**, both EXIT=0; the root-cause lesson (commit the coherent closure, never patch the leaf) is **INFERENCE** and is recorded as a candidate §22 lesson; §22 is BYTE-IDENTICAL (99 lines / 6,504 bytes) and §1.6.2–§1.6.20 are untouched |
| Spec Integrity + Concurrent Worktree Lesson (§1.6.22) | HISTORICAL (audit) + SOURCE CODE (path repairs) + TEST (skip identity) — the `docs/reports/` relocation is **SOURCE CODE** (commit `a76bdc6`; 38 moved-only, 4 root, 2 under `SoftEtherClient/`); the 19 path updates are **SOURCE CODE** (`git diff --stat` = 17/17, confined to §1.6.2–§1.6.21 + §12.5.1); the 46 remaining bare occurrences are **SOURCE CODE** (recorded, not edited, all resolvable); the concurrent-worktree contamination (`82cb64f` sweeping partial v1.0.29 content later superseded by `82476bf`) is **SOURCE CODE** (`git diff 0f5f307..82cb64f`), documentation-only; the single-owner working-tree rule is **INFERENCE**; the `HttpFetcher.kt` commit (`9d569c3`, comment-only) is **SOURCE CODE**; the skip identity is **TEST** (`HtmlParserOracleTest.softetherUdpOnlyNeverBorrowsOpenvpnTcp`, `AssumptionViolatedException`); §22 is BYTE-IDENTICAL (99 lines / 6,504 bytes) and §1.6.2–§1.6.21 are otherwise untouched |
| AutoConnect Audit Reconciliation (§1.6.23) | HISTORICAL — records the AUTOCONNECT-FIX revision (D-1..D-8, OP-R6) that closes four gaps from the two independent AutoConnect audits plus OP-R6; all claims attributed to their phase report and to the direct source/test inspection recorded in §1.6.23. §22 is BYTE-IDENTICAL (99 lines / 6,504 bytes) |
| AutoConnect Fix 2 — Two Device Bugs (§1.6.24) | HISTORICAL — records the AUTOCONNECT-FIX-2 revision closing BUG-A (D-4 predicate now includes `CONNECTED` via `GlobalVpnTracker.isActiveOrBusy`) and BUG-B (repeated `NATIVE_BUSY` on the same server records a wedge at threshold 2), plus slot-non-release instrumentation. The bugs are **PHYSICAL DEVICE** findings attributed to `DEVICE_VERIFY_AUTOCONNECT_FIX_REPORT.md` §5 and §7; the fixes are **SOURCE CODE** (`GlobalVpnTracker.kt`, `AutoModeEngine.kt`, `AutoModeController.kt`); the four new tests are **TEST**; the slot-non-release root cause is **UNKNOWN** (instrumented only, no fix attempted); device re-verification is **PENDING**. §22 is BYTE-IDENTICAL (99 lines / 6,504 bytes) |
| AutoConnect Fix 3 — Instrumentation + Budget Floor (§1.6.25) | HISTORICAL — records the AUTOCONNECT-FIX-3 revision closing the two defects the v1.0.32 **device** verification produced: the `SLOT_UNRELEASED_DIAG` line sat on the post-attempt slot-wait site while every on-device terminal came from the NATIVE_BUSY site, so it never fired; and the `5 × T` slot-wait budget fell to 25 s under the device's persisted 5 s preference against observed release latencies up to 20.9 s, with one release 292 ms late. Both sites now emit the diagnostic, and the derivation is floored at `NATIVE_SLOT_AWAIT_MS` (30 s) beneath the existing 60 s cap. Diagnostic-absence and latencies are **PHYSICAL DEVICE** (`DEVICE_VERIFY_AUTOCONNECT_FIX_2_REPORT.md` §7); both fixes are **SOURCE CODE** (`AutoModeController.kt:1689-1719`, `AutoModeEngine.kt:90-93`); the floor test is **TEST** (`nativeSlotAwaitHasAThirtySecondFloor`); device re-verification and the slot-non-release root cause remain **UNKNOWN**/**PENDING**. §22 is BYTE-IDENTICAL (99 lines / 6,504 bytes) |
| AutoConnect Fix 4 — Bounded RUDP Fallback + Wedge on Terminal (§1.6.26) | HISTORICAL — records the AUTOCONNECT-FIX-4 revision closing the two structural causes the v1.0.33 **device** run exposed. The unbounded RUDP fallback (28.5 s of silence, no `SSL handshake attempt N` lines) is **PHYSICAL DEVICE** (`DEVICE_VERIFY_AUTOCONNECT_FIX_3_REPORT.md` §7.3); the 5/5 re-selection of the same endpoint with no wedge ever recorded is **PHYSICAL DEVICE** (§7.4); Fix-A is **SOURCE CODE** (`softether_protocol.c:2935` timeout, `:2941` discriminator); Fix-B is **SOURCE CODE** (`AutoModeController.kt:1729-1736`) + **TEST**; the D-5 narrowing is **SOURCE CODE** (`AutoModeController.kt:2012-2025`); STEP 4's deliberate skip is **SOURCE CODE** (no custom BIO; `ssl_connect` at `:1236` reads the socketpair fd directly). The two new tests are **TEST**, as are the `:app` 803/0/0/1 and `:SoftEtherClient` 173/0/0/0 counts, the APK SHA-256 and the standalone build gate. Device re-verification of the bound and of the wedge-on-terminal is **PENDING**, and the endpoint's own refusal cause is **UNKNOWN**. §22 is BYTE-IDENTICAL (99 lines / 6,504 bytes) |
| AutoConnect Fix 5 — Skip Retry on Unresolved Teardown (§1.6.27) | HISTORICAL — records the AUTOCONNECT-FIX-5 revision removing the post-slot-release retry when the preceding teardown was unresolved. The driving measurement — 12 retries, 10 `NATIVE_ERROR`, 2 `NATIVE_BUSY`, **zero** `TUNNEL_UP`, 11/12 after a `NATIVE_UNRESOLVED` teardown — is **PHYSICAL DEVICE** (`DEVICE_VERIFY_AUTOCONNECT_FIX_4_REPORT.md` §15). The gate is **SOURCE CODE** (`AutoModeController.kt:322` interface default, `:1813` skip site; `AndroidConnectionAdapter.kt:106`, `:302`, `:318-319`), and its confinement to `NATIVE_UNRESOLVED` alone is **SOURCE CODE** (`TeardownCompletion.Outcome` semantics: only that outcome refuses a later start). The two tests are **TEST**, as are the `:app` 805/0/0/1 and `:SoftEtherClient` 173/0/0/0 counts, the APK SHA-256 and the standalone build gate. Fix-A (RUDP handshake bound) and Fix-B (wedge on terminal) remain **UNVERIFIED** — the first is unreachable while NAT-T rendezvous returns `error=6`, the second because no run reached the terminal — and are **UNKNOWN** on device, unchanged by this revision. §22 is BYTE-IDENTICAL (99 lines / 6,504 bytes) |
| AutoConnect Fix 6 — NATIVE_BUSY Race + Capped Waits (§1.6.28) | HISTORICAL — records the AUTOCONNECT-FIX-6 revision (Option A) closing the race that destroyed live tunnels. The driving finding — 3/4 runs destroyed a **working** tunnel, 1 excluded a healthy server, both preceded by `tracker=CONNECTED ownTunnel=true` — is **PHYSICAL DEVICE** (`DEVICE_VERIFY_AUTOCONNECT_FIX_4_UNFILTERED_REPORT.md` §5.3). The root cause (timeout fires before `CONNECTED`, so `isNativeSlotBusy()` is true because our own operation is running) is **SOURCE CODE + PHYSICAL DEVICE**. Fix-1 is **SOURCE CODE** (`AutoModeController.kt:1683`, `:1685`); Fix-2 is **SOURCE CODE** (`:1772`, `:1820`, `:1828`); Fix-3 is **SOURCE CODE** (`:2003`, `:2010`, `:2019`). The prior phase's STOP and the operator's Option A decision are **PRIOR REPORT + SOURCE CODE**; the 9 tests that block Option B are **SOURCE CODE** (inspected, unmodified). The three new tests are **TEST**, as are the `:app` 808/0/0/1 and `:SoftEtherClient` 173/0/0/0 counts, the APK SHA-256 and the standalone build gate. The zero-free-space build failure is an **environment fault**, recorded and not attributed to the source. The three fixes themselves are **not device-verified**; Fix-A remains **UNVERIFIED** (zero NAT-T rendezvous, zero `[TLS] handshake entry (RUDP)`), and the strict `2T` bound is **Option B**, deliberately not taken. §22 is BYTE-IDENTICAL (99 lines / 6,504 bytes) |

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
