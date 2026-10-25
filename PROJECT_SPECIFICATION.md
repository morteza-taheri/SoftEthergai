# PROJECT_SPECIFICATION — SoftEther VPN Client for Android

**Project:** SoftEthergai — Native SoftEther VPN Client for Android
**Repository:** `D:\PMC\Documents\Android\SoftEthergai`
> **Canonical repository name:** `SoftEthergai` (see §2.6 for local mirrors)
**Branch:** `main`
**HEAD:** `51fe1d2` (`chore(collector): write the analysis CSV to collector_servers_full.csv`), the last code commit of this block. The eight commits this section records sit below it; the documentation commits sit on top, and a commit cannot carry its own hash. The ten-run gate was executed at the code HEAD `51fe1d2`.
**Document version:** 1.0.63
**Last updated:** 2026-10-25

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

> **Push state (v1.0.38).** VERSION-BUMP-PAST-2.4.10. `versionCode` **143 → 144** and `versionName`
> **"2.4.10" → "2.4.11"** in `app/build.gradle` (the `pro` flavor). This resolves the identity
> collision recorded in **§1.6.12 bullet 5** (v1.0.19): HEAD and tag `p2.4.10` had shared the
> `2.4.10` / `143` identity while HEAD moved 60+ commits ahead, so every device install in between
> reported `versionCode 143`. Only one location declares the version; no test pins it; the
> `BuildConfig.VERSION_*` readers are display-only and follow the bump automatically. **Any future
> release cut of 2.4.11 still requires the fail-closed release keystore** (§1.6.10 bullet 8 /
> **RISK-A**) — this phase changes no signing configuration and **creates no tag**. The header
> **HEAD** field is the code commit of this phase, the state this revision was verified against; a
> commit cannot carry its own hash.

> **Push state (v1.0.39).** RETENTION-IMPL-PHASE-1 — Cumulative Server Retention, foundation only (spec §1.6.31). Three authorized source files change: `VPNGateItem.kt` gains `firstSeenAt` / `lastSeenAt` / `healthScore`; `AppDatabase.kt` moves Room `version 7 → 8` with `MIGRATION_7_8` (three additive `ALTER TABLE VPNGateItem ADD COLUMN … INTEGER NOT NULL DEFAULT 0` statements — no DROP / DELETE / TRUNCATE / CREATE TABLE, so existing rows survive the upgrade); `App.kt` registers the migration; `ServerListUpdates.kt` rewires the single writer so a fresh network observation is **merged** (`mergeAll`) instead of replaced, while a fallback still replaces wholesale via the retained `replaceAll`. `ServerListUpdater` is untouched (the `fetchedFromCache` flag is threaded through `daoStore`). 11 new JVM tests (5 migration + 6 retention); `:app` **823/0/0/1**, `:SoftEtherClient` **173/0/0/0**; `:app:assembleProDebug` SUCCESSFUL, APK SHA-256 `79c102728581023f77f2542053fce11131a0263563f2c86c08c9aa70a6de3c17`. Phases 2–3 (the max-server cap and the §9.3 retention-priority prune, plus the Settings UI) are out of scope. The header **HEAD** field is the code commit of this phase, the state this revision was verified against; a commit cannot carry its own hash. No tag operation of any kind was performed.

> **Push state (v1.0.40).** AUTOCONNECT-DEVICE-BUGS-FIX — the two of the three device-reported Auto Mode bugs that source evidence can settle (spec §1.6.32). **BUG-1** (the hero button read "Disconnected" while the tunnel was up, and the tap then hit the D-4 refusal) is closed structurally: `AutoModeViewModel` publishes a combined `AutoModeDisplayState` built by the pure `combineAutoModeState(modeState, tunnelStatus)` — the same reconciliation `HomeScreen.activeServerOf()` already performed for the list highlight — and `AutoModeScreen` renders the hero button from it, branching the tap on `isExternal` so an externally-owned tunnel is torn down (`stopAndDisconnect`) instead of refused. **BUG-3** (the battery-optimisation dialog on every press) is closed by a persisted `SETTING_BATTERY_OPT_ASKED` flag, following the `SETTING_LOCALE_ANSWERED` precedent, written on all three dialog exits. **BUG-2** (MS-SSTP never attempted) is **NOT** fixed and **NOT** claimed: the evidence needed to distinguish "no SSTP-capable server in the user's dataset" from "the protocol is filtered out before selection" is a device log, and none was taken — see `TRIAGE_AUTOCONNECT_DEVICE_BUGS_REPORT.md` §4. Four authorized files change (`AutoModeViewModel.kt`, `AutoModeScreen.kt`, `DataUtil.kt`, and one additive string in `strings.xml`); `AutoModeController.kt`, `AutoModeEngine.kt`, `GlobalVpnTracker.kt`, `HomeScreen.kt`, `AndroidConnectionAdapter.kt` and the build/manifest files are untouched, so `versionCode 144` / `versionName 2.4.11` are unchanged. 14 new JVM tests (8 display-state + 6 persisted-flag); `:app` **837/0/0/1**, `:SoftEtherClient` **173/0/0/0**; `:app:assembleProDebug` SUCCESSFUL, APK SHA-256 `5bed6128ceb382e9619567eefd2ee989189c26ac2e36fc2efc566ec3bf60c91a`. All three fixes are **NOT device-verified** — this phase ran no device. The header **HEAD** field is the baseline commit `6229a769a8a98397b5acf2e55717b3a248a9a097`, the state this revision was verified against; a commit cannot carry its own hash. No tag operation of any kind was performed.

> **Push state (v1.0.41).** TRAFFIC-PATH-DYNAMIC phase 1, Option B (operator-authorised; spec §1.6.34). The traffic-path choice now consumes tunnel health: `resolvePrimary()` returns the VPN rung only when `preferVpn && tunnelUsable` (`VpnServerRepository.kt:149–151`), and `productionRouting()` supplies `GlobalVpnTracker.isTunnelUsableForRouting()` (`:123`, `:129`) instead of the weaker `isConnected && currentVpnNetwork() != null` pair. `isTunnelUsableForRouting()` is a grace-period wrapper over the unchanged `isTunnelUsable()`; its pure core `routingUsableFromSignals(...)` is file-scope so the JVM suite can exercise it (`GlobalVpnTracker.kt:797`). `connectedAtMs` is added to the tracker as a diagnostic anchor and is **not** read by the decision (`:311`, `:321`). **FIX-1 was already done**: `TunnelHealth.noteRxAdvance()` is called at `ConnectionController.kt:1157` (one call per received batch, R4b), and the two comments that claimed otherwise (`SoftEtherVpnService.kt:1735–1740`, `GlobalVpnTracker.kt:357–363`) are corrected — comment-only, no code change. **FIX-3 was already done**: the `fetchOnChosenPath` fallback is untouched. 9 new JVM tests in two new files; `:app` **846/0/0/1**, `:SoftEtherClient` **173/0/0/0**; `:app:assembleProDebug` SUCCESSFUL, APK SHA-256 `1340fed9771828d3b9655c4b05c0d2778ed737e9bd18b6363b16c44d2902159f`. Per-source path memory remains phase 2, and the change is **NOT device-verified** — this phase ran no device. §12.5.1 row 10 is stale but FROZEN and is annotated only in §1.6.34. The header **HEAD** field is the baseline commit `02765a6f2ec0273124e5286dce02c09fd91129dd`, the state this revision was verified against; a commit cannot carry its own hash. No tag operation of any kind was performed.

> **Push state (v1.0.42).** AUTOCONNECT-CORRECTNESS-CLUSTER (spec §1.6.35). Four corrections to the Auto Mode controller, all in one cluster because they share a cause — a value or a position captured once and then treated as if it were still true. **F1**: `attemptTimeoutMs` becomes `attemptTimeoutMsProvider: () -> Long`, read at attempt time, so a Settings change applies to the next attempt instead of to the next process (`AutoModeEngine.ensure()` builds the controller exactly once). **B-3**: `nativeSlotAwaitMs` becomes a provider too, because both budgets derive from the same `SETTING_AUTO_MODE_TIMEOUT_SECONDS` read and fixing one alone would have left the other frozen. **F2**: a new `isNetworkAvailable` seam, `ERROR_NO_NETWORK`, and two guards (before the loop and before each attempt) so an offline run stops instead of marking every server `TIMED_OUT` and giving each a wedge strike; the terminal is mapped to a new `auto_mode_error_no_network` string. **F6**: the Next-Server continuation moves from an integer index to the `ip|hostname` identity — **a deliberate reversal of the v1.0.31 decision**, recorded as such, not as a bug fix (§6.8.5, §11.13.3). 12 new JVM tests in four new files; `:app` **858/0/0/1**, `:SoftEtherClient` **173/0/0/0**; `:app:assembleProDebug` SUCCESSFUL, APK SHA-256 `e8a4bb1400e9046c1c0b4b34218b18da10c127a1992407ead8062f5fd5a67ecd`. `versionCode 144` / `versionName 2.4.11` are unchanged — no build or manifest file was touched. **NOT device-verified** — this phase ran no device. The header **HEAD** field is the baseline commit `c5a7bb5d7b1fea2aaf26d03dedfc8cc21500251c`, the state this revision was verified against; a commit cannot carry its own hash. No tag operation of any kind was performed.

> **Push state (v1.0.44).** DIAGNOSTIC-PLACEMENT-FIX (spec §1.6.37). The v1.0.43 `[bind]` diagnostics landed in `boundToNetwork`, which production never calls — `VpnServerRepository.kt:163` builds the bound client via `boundToSocketFactory` directly — so those logs never fire on device. This revision moves the EPERM attribution to `OkHttpFetcher.execute()`'s `catch (IOException)` (the pre-existing "Request failed" line), where the socket-open-time EPERM actually surfaces, logging the exception class (`Request failed: [exception=<class>] <message>`); the network handle is already carried in `e.message` ("Binding socket to network N failed: EPERM"). Control flow (`return null`) and the v1.0.43 `[bind]` logs are both unchanged. 1 new source-shape test (T-NEW-53); `:app` **866/0/0/1**, `:SoftEtherClient` **173/0/0/0**. The `:app:assembleProDebug` APK could not be produced in this environment (a sandbox Security Center rule blocked a Gradle cache `.so` read at `mergeProDebugNativeLibs`); source compilation and the JVM suite were validated via `:app:testProDebugUnitTest`. **NOT device-verified** — no device run in this phase. §22 byte-identical; §1.6.2–§1.6.36 untouched. No tag operation of any kind was performed.

> **Push state (v1.0.45).** DYNAMIC-PATH-RECOVERY, Phase A (device-free; spec §1.6.38). The v1.0.44 Phase B device run (`DEVICE_VERIFY_SERVER_LIST_UPDATE_v1.0.44_REPORT.md`) produced one decisive failure — **T6b FAIL**, the "prefer VPN" toggle stayed inert — and this revision closes it together with the two defects that made the failure unreadable. **P1 (definite, file:line):** `DataUtil.setPrefersVpnForCollector()` wrote `"vpn_always_prefer_vpn"` into the DataUtil store (`"vpn_setting_data_" + BuildConfig.FLAVOR`) while `GlobalVpnTracker.prefersVpnForCollector()` reads `PreferenceManager.getDefaultSharedPreferences` — same key, **different files**, so the toggle never reached the reader and the v1.0.41 VPN rung was unreachable; the writer now writes **both** stores. **P2 (definite):** `path=open` was *unbound*, so with the app's own tunnel up it silently followed the OS default — which IS the tunnel — meaning "open" never meant "outside the tunnel"; `CollectorPath` is now `PHYSICAL` (bound to a NOT_VPN + INTERNET network via `GlobalVpnTracker.currentPhysicalNetwork()` + `boundToPhysicalNetwork`) / `TUNNEL` / `EMERGENCY`, and the unbound rung no longer exists. **P3 (mechanism UNKNOWN):** the `path=vpn` EPERM is **not** explained; the ladder now logs path, network handle and a `SelfExclusion` tri-state on every failure so the next device run can attribute it. FIX-4 adds the automatic ladder `PHYSICAL → TUNNEL (when usable and bound) → EMERGENCY`, with the opt-in only **reordering** it — a blocked physical path recovers through the app's own tunnel with no manual toggle and no DB wipe (the last-known-good snapshot is preserved). FIX-5 makes `refresh()` single-flight (`Mutex`) under one total deadline (`withTimeoutOrNull(MAIN_TIMEOUT_MS)`), with no retry loop. 21 new JVM tests (8 ladder + 6 physical-bind shape + 4 two-store write + 3 refresh mechanics); 4 existing test files were updated to the new model under a **second explicit operator authorization** (`الف`), because they pinned the retired OPEN/VPN labels. `:app` **887/0/0/1**, `:SoftEtherClient` **173/0/0/0**; `:app:assembleProDebug` SUCCESSFUL under `CI=true`, APK SHA-256 `3f0009afb0c1a60eca49435c2156a09f41d7de1b468154cd047f6d12310c4963`, `versionCode 144` / `versionName 2.4.11` unchanged. **NOT device-verified** — Phase A ran no device; Phase B is explicitly out of scope. The header **HEAD** field is the baseline commit `f8d314869051775ac113ffdbdd02440ed8c4b833`, the state this revision was verified against; a commit cannot carry its own hash. §22 byte-identical; §1.6.2–§1.6.37 untouched. No tag operation of any kind was performed.
> **Push state (v1.0.46).** EMERGENCY-DEFAULT-URL-READER + TIMEOUT-RANGE-TIGHTENING (device-free; spec §1.6.39). Two independent operator requirements, one version bump. **CHANGE A:** `emergency_csv_publisher.py` has been writing `R.string.default_emergency_source_url` into `app/src/main/res/values/emergency_defaults.xml`, but **no Kotlin code read it** — so a fresh install shipped an empty `emergency_source_config` table, `ServerListUpdates.emergencyDataset()` called `dao.listEnabled()` and got nothing, and the emergency path was dead by construction. `App.seedEmergencyDefaultIfNeeded(...)` now seeds exactly one row from that resource on first startup, guarded by the new `SETTING_EMERGENCY_DEFAULT_SEEDED` flag: **once only** (a deleted row is not resurrected), **non-destructive** (any existing row — enabled or disabled — blocks the insert), **idempotent**, and **non-fatal** (a failure leaves the flag unset so the next start retries). It runs on a private `CoroutineScope(SupervisorJob() + Dispatchers.IO)` inside `App`, so startup is never blocked. **CHANGE B:** the Auto Mode attempt timeout floor moves **5 → 8 s**; the default (**12 s**) and maximum (**60 s**) are unchanged, in `DataUtil`'s getter *and* setter (so an old stored 5–7 reads back as 8 with no migration) and in the Settings picker (`(5..60)` → `(8..60)`). 13 new JVM tests (8 seeder cases + 5 range cases, covering T-NEW-67..72 plus guards); 1 existing assertion updated (`AutoModeControllerTest` pinned `.coerceIn(5, 60)`). `:app` **900/0/0/1**, `:SoftEtherClient` **173/0/0/0**; `:app:assembleProDebug` SUCCESSFUL under `CI=true`, APK SHA-256 `88b4a4fe326faad4fbe716fe22ffa3bba9f20cd428ab05346d8c8936903b6591`, `versionCode 144` / `versionName 2.4.11-dev` unchanged. The seeded config row is **not** the collector ladder: `VpnServerRepository.hasEmergencySources()` is still a stub, so the `EMERGENCY` rung remains inert — wiring it is a separate future phase and was explicitly out of scope. **NOT device-verified** — no device ran; a fresh-install seed check is pending. The header **HEAD** field is the baseline commit `34389a482f712de61c6bcf13043843017462e561`, the state this revision was verified against (the publisher advanced it past v1.0.45 with `chore(emergency)` commits); a commit cannot carry its own hash. §22 byte-identical; §1.6.2–§1.6.38 untouched. No tag operation of any kind was performed.

> **Push state (v1.0.47).** REMOVE-SELF-EXCLUSION-FOR-COLLECTOR (device-free; spec §1.6.40). Closes the root cause that the v1.0.46 device run could only name, not fix. `App.initializeDefaultExcludedApps()` inserts a hidden `ExcludedApp(packageName = <self>, appName = "Self")` row into `excluded_app` — "hidden from user, cannot be removed" (`App.kt:135–149`) — and that row reaches **two independent** `VpnService.Builder` application points: the SoftEther path via `config.excludedApps` (`SoftEtherVpnService.buildTun`, `:1398`) and the MS-SSTP path via the `ROUTE_EXCLUDED_APPS` preference (`IPTerminal.addExcludedAppRules`, `:148`). Because the app was therefore disallowed from its own tunnel, every socket the collector bound to the VPN network failed with `Binding socket to network 109 failed: EPERM (Operation not permitted)` while `PHYSICAL` succeeded on `network=104` — so the `TUNNEL` rung could never deliver data, and in a network where the physical path is blocked the list could never update. Both application points now `filterNot { it == <self package> }`, so the app may use its own tunnel; **user-added exclusions are applied unchanged** (the device's `com.google.android.projection.gearhead` still is). Per-request binding from v1.0.45 (`productionBoundFetcher` / `productionTunnelFetcher`) already isolates management traffic, so removing the global self-exclusion does not leak unrelated traffic into the tunnel. The brief's assumed shape ("a hardcoded `builder.addDisallowedApplication(packageName)`") is not what the source contained — the exclusion arrives through the list, so the fix is a filter rather than a deleted line, and the MS-SSTP point (the path the device actually exercised) had to be added to scope under an explicit operator authorization. 4 new source-shape tests (T-NEW-73 a–d); `:app` **904/0/0/1**, `:SoftEtherClient` **173/0/0/0**; `:app:assembleProDebug` SUCCESSFUL under `CI=true`, APK SHA-256 `0ef89bfefe8e1d7aae58ddde101bcda4642eef4b238eb9d1a91690e04df90583`, `versionCode 144` / `versionName 2.4.11-dev` unchanged. **NOT device-verified** — the check that closes this is that the `TUNNEL` rung now returns data (`path=tunnel succeeded`) with no `EPERM`. The header **HEAD** field is the baseline commit `157445dee31a43b9cdbb0316376ddb5fb73d34db`, the state this revision was verified against; a commit cannot carry its own hash. §22 byte-identical; §1.6.2–§1.6.39 untouched. No tag operation of any kind was performed.


> **Push state (v1.0.48).** NATIVE-SLOT-TEARDOWN-RECOVERY (device-free; spec §1.6.41). Closes the last user-facing defect that survived the v1.0.30–v1.0.47 chain: the T5-SLOT-TEARDOWN residual (§12.5.1 row 9), known since v1.0.10 and reproduced on device in the v1.0.47 run. After a failed connect the process-wide native slot stayed in `Phase.TEARDOWN` and every later attempt was refused — `Start refused: native slot still held by an unresolved attempt (attempt=1 phase=TEARDOWN, reporting deadline expired (still blocked))` — with a process restart as the only way out. The 90 s soft-bound (`NATIVE_SLOT_FORCE_RELEASE_AFTER_MS`) that was supposed to break that deadlock **already existed but could never fire**: it measured elapsed time from `teardownRequestedAtMs`, and `NativeLifetimeRegistry.markTeardownRequested` **re-stamped that field on every call**. `destroyResources()` is idempotent by design (three call sites) and Auto Mode calls `adapter.disconnect()` after every `NATIVE_BUSY` refusal, so each retry restarted the clock — the recovery loop pushed the release further away every time it ran. **FIX-1** stamps only the **first** teardown request per attempt (`NativeLifetimeRegistry.kt`), so the bound now measures what its KDoc always claimed. **FIX-2** gives the service an attributed recovery path (`SoftEtherVpnService.acquireNativeSlotOrRecover`) that reports the holder's **age** and asks the registry's own force-release entry point (`forceReleaseStaleTeardown`) for a release instead of refusing silently; it applies exactly the registry's preconditions and cannot bypass them. **FIX-3** pins both halves of the contract: a confirmed teardown still releases at once, a holder inside its window is still refused, the device retry loop can no longer restart the clock, and the attempt-id guard still protects a newer owner. 4 new JVM tests (T-NEW-74..77, `NativeSlotRecoveryTest`); `:app` **904/0/0/1** (unchanged), `:SoftEtherClient` **177/0/0/0** (+4). `:app:assembleProDebug` SUCCESSFUL under `CI=true` (`--rerun-tasks --no-build-cache`), APK SHA-256 `8f7ed99a201488655ab84946908fc74a58df7d32d0c0f813f9a60be60ea494a3`, `versionCode 144` / `versionName 2.4.11-dev` unchanged. The fail-closed guarantee is **preserved**: a genuinely stuck operation still blocks a new attempt for the configured bound, and the release fires only after it. `AutoModeController.kt` was left **untouched** — the brief authorised only the `ERROR_NATIVE_UNRESOLVED` give-up path, which is already unreferenced and deliberately retired by the v1.0.37 no-terminal contract (§6.8.4), so re-introducing a terminal there would have violated that contract. **NOT device-verified** — reproducing the deadlock and confirming the recovery fires within the documented deadline is PENDING. The header **HEAD** field is the baseline commit `966e8d48abd30466052a2bfbd281c3f4d4d16954`, the state this revision was verified against; a commit cannot carry its own hash. §22 byte-identical; §1.6.2–§1.6.40 untouched. No tag operation of any kind was performed.

> **Push state (v1.0.49).** EMERGENCY-LADDER-WIRING (device-free; spec §1.6.42). Completes the operator's original three-path requirement — PHYSICAL -> TUNNEL -> EMERGENCY — by making the `EMERGENCY` rung of `VpnServerRepository.refreshLocked()` actually attempt the existing v1.0.21 emergency fetch path instead of only logging `skip=EMERGENCY (none configured)`. The rung was inert for two independent reasons, both now fixed: (1) `hasEmergencySources()` was a stub returning `false` (v1.0.45), so the log lied even when the v1.0.46 seeded default source existed in `emergency_source_config`; it now consults the DAO (`listEnabled()`). (2) Even when configured, the rung was never reached by `refreshLocked()`: on primary failure it returned the last-known-good snapshot (non-null), which intercepted the fallthrough, so `ServerListUpdater`'s `emergencyFetch` — the only other place that reached `emergencyDataset()` — was almost never invoked. `refreshLocked()` now attempts the rung itself and folds the emergency records into the SAME `collected` pipeline as PHYSICAL/TUNNEL, so `DatasetIntegrity` still gates the eventual write and no fetch logic is duplicated (the v1.0.21 `EmergencySourceFetcher` + `EmergencyCsvParser` are reused via a new `internal suspend fun emergencyRecords()` seam that `emergencyDataset()` now delegates to). 3 new source-shape tests (T-NEW-78..80, `EmergencyLadderWiringTest`); `:app` **907/0/0/1** (+3), `:SoftEtherClient` **177/0/0/0** (unchanged). `:app:assembleProDebug` SUCCESSFUL under `CI=true` (`--rerun-tasks --no-build-cache`), APK SHA-256 `194255e41cc6ad18f60debb889526a49cfd8e129eab6d5dd367b14544a07b657`, `versionCode 144` / `versionName 2.4.11-dev` unchanged. The 1 MiB cap and the redirect-authority check in `EmergencySourceFetcher` are preserved (§11.9.2). **NOT device-verified** — the closing check is that with PHYSICAL and TUNNEL both failing the ladder falls through to EMERGENCY and succeeds against the seeded `servers.csv` (§1.6.42 item 10). The header **HEAD** field is the baseline commit `d370f8d7cf6815dcc84d6691cefa4515bb653f98`, the state this revision was verified against; a commit cannot carry its own hash. §22 byte-identical; §1.6.2–§1.6.41 untouched. No tag operation of any kind was performed.

> **Push state (v1.0.51).** ADDITIONAL-CONNECT-PROTECT (device-free; spec §1.6.43). Closes the tunnel-death defect that the v1.0.47 device run recorded as ISSUE-1: SoftEther TCP tunnels died 10–20 s after establishment while `additional_connect` was running, with the stats counter frozen at `sent=958 / received=60`, a "Keepalive missed" teardown, and the `additional_connect` response arriving 16.4 s late. **Root cause (confirmed, not assumed):** the additional TCP socket is created in native code *after* tun0 exists (`softether_additional_connect`, `softether_protocol.c:4029`) and was dialled at `:4036` with **no `VpnService.protect()` before `connect()`** — Kotlin only protected it later, from the receive loop (`ConnectionController.kt:1175`, every 50 iterations), i.e. after the connect had already been routed into the tunnel it was building. tun0 cannot forward until that very connect completes, so the connect blocked until its timeout and the session died. **FIX:** a per-connection native→Kotlin protect hook (`softether_set_socket_protector`; the connection carries `protect_socket_fn`/`protect_socket_ctx`) invoked between `socket_create()` and `socket_connect_timeout()`, backed by a JNI upcall (`nativeSetSocketProtector`) that calls `VpnService.protect(int)` on the registered service, resolving `JNIEnv` with `GetEnv` and attaching/detaching the `JavaVM` only when the background pthread is detached. **Fail closed:** a failed or missing protect aborts that additional connection *before* `connect()` and never dials an unprotected socket; no protector registered (native test harness / non-VPN hosts) keeps the legacy behaviour so those builds still work. Kotlin arms the hook on both the initial and the reconnect path and aborts the connect if arming fails; the periodic `protectAdditionalSockets()` safety net and the `protectedFds` teardown reset are preserved. No MS-SSTP, Auto Mode, ladder or timeout behaviour was touched. 7 new source-shape JVM tests (T-PROTECT-01..07, `AdditionalConnectProtectTest`); `:SoftEtherClient` **184/0/0/0** (+7), `:app` **907/0/0/1** (unchanged). `:app:assembleProDebug` SUCCESSFUL under `CI=true` (`--rerun-tasks --no-build-cache`), APK 63,795,058 bytes, SHA-256 `36b4e4bb5b38bf0b9c449993d59d2f8d6f55166a02f2e42bfdbfcbd83ec6a521`, `versionCode 144` / `versionName 2.4.11-dev` unchanged; the built `libsoftether.so` exports both `Java_vn_unlimit_softether_client_SoftEtherClient_nativeSetSocketProtector` and `softether_set_socket_protector`. **NOT device-verified** — V-VERIFY-3 (tunnel survives > 20 s, `received` counter climbs, other apps keep internet, and the log shows the additional socket protected *before* connect) is PENDING. The header **HEAD** field is the baseline commit `6f25df9954888577bba088af892765077dfd4427`, the state this revision was verified against; a commit cannot carry its own hash. §22 byte-identical; §1.6.2–§1.6.42 untouched. No tag operation of any kind was performed.

> **Push state (v1.0.52).** AUTO-ROUTE-AND-AUTOCONNECT-UX (device-free; spec §1.6.44). Three operator-requested corrections in one revision. **TASK A — the manual "prefer the VPN for the collector" switch is GONE.** The switch, `DataUtil.getPrefersVpnForCollector()` / `setPrefersVpnForCollector()`, `GlobalVpnTracker.prefersVpnForCollector()`, the `PREF_ALWAYS_PREFER_VPN` constant, the `preferVpn` field of `CollectorRoutingInput` and the whole `CollectorRouting` object were removed; `CollectorRoutingInput` is now two **measurements** (`tunnelUsable`, `hasBoundNetwork`). Routing is fully automatic: `routeLadder(input, memory)` keeps `PHYSICAL → TUNNEL (when usable and bound) → EMERGENCY` and promotes `TUNNEL` only on evidence from the new bounded in-process `VpnServerRepository.PathMemory` (success TTL 30 min, failure cooldown 2 min, injectable clock, key space = the three-value `CollectorPath` enum, no personal data, never persisted). `refresh()` gained exactly one bounded one-shot TUNNEL retry (CAS-guarded `tunnelRetryAvailable`, `TUNNEL_RETRY_TIMEOUT_MS = 20 s`, inside the existing `refreshMutex`, re-armed only by a run that *starts* with a usable tunnel — so it cannot loop). A stale stored `vpn_always_prefer_vpn` is inert: nothing reads it and no migration is needed. **TASK B — Auto Connect UX and ownership.** The "Automatic Reconnection" switch moved from Settings to the Auto Connect screen (one preference, one switch); the default per-attempt timeout moved **12 s → 8 s** (now equal to the 8 s floor) in `DataUtil`, `AutoModeController.DEFAULT_ATTEMPT_TIMEOUT_MS` and the Settings picker fallback; `AutoModeController` gained a `transitionLock` monitor (not a `Mutex`, because `start()`/`stop()` must stay non-suspending) with a `startLocked()` seam, so every `job` mutation is one transition and the skip continuation is published as the current job and swapped for the real run while still holding the lock — `job` never passes through null; `CancellationException` is rethrown (never converted into a failed attempt) on the probe and both connect paths, and `adapter.disconnect()` now runs under `withContext(NonCancellable)` so teardown completes; `rememberRemaining()` compares by `serverKey` (`ip|hostname`, null normalised to "") instead of `it.ip == current.ip && it.hostname == current.hostname`, which disagreed whenever one side had a null hostname and the other "" and could re-dial the server the user had just left. **TASK C** is this spec revision. 18 net new JVM tests: `VpnServerRepositoryLadderTest` rewritten (base order, evidence-only promotion, invariants, plus 8 `PathMemory` cases with a fake clock), `VpnServerRepositoryRoutingDynamicTest` extended with T-LADDER-20..24 (retry CAS/bound/no-loop, retry inside the lock, re-arm rule, path-memory wiring, no surviving preference symbol), `HttpFetcherR4bTest` routing half replaced by removal tests, `DataUtilPrefersVpnTest` **replaced** by `DataUtilRoutingPreferenceRemovedTest` (T-REMOVED-01..05), `DataUtilTimeoutRangeTest` T-NEW-70/72 updated to 8 s plus a new default-equals-controller-fallback case, and two new `AutoModeControllerTest` cases (identity cursor with null-vs-empty hostname; double press never dials a server twice). `:app` **925/0/0/1**, `:SoftEtherClient` **184/0/0/0** (untouched). **NOT device-verified** — V-VERIFY-4 (the collector recovers on its own when the physical path is blocked; the Auto Connect switch is reachable and persistent; a dead server is abandoned after ~8 s) is PENDING. The header **HEAD** field is the baseline commit `0f9c0a66bd9458e7a7546842d59e3419f47ae140`, the state this revision was verified against; a commit cannot carry its own hash. §22 byte-identical; §1.6.2–§1.6.43 untouched. No tag operation of any kind was performed.
> **Push state (v1.0.53).** SERVER-DATA-AUDIT-AND-OPENVPN-CONFIG-GATE (device-free; spec §1.6.45). **TASK A — OpenVPN is no longer offered without a profile.** `AutoModeController.usableProtocols()` gated OpenVPN on the port alone; the adapter returned **silently** when `openVpnConfigData` was null, so the attempt looked issued, waited out the full per-attempt budget and was reported `TIMED_OUT` — which records a wedge against a server that was never contacted. New `AutoModeCandidate.openVpnConfigAvailable` (default `false`, fail closed) derived in `AutoModeEngine.toCandidate()` from the real bytes (`!openVpnConfigData.isNullOrBlank()`); one flag covers both transports because `OpenVpnConfigTransform.forUdp` is null iff its input is null, so `VPNGateConnection.kt` was **not** needed. Port facts are preserved verbatim. **TASK B — the data audit.** The embedded config is now authoritative over an `OpenVPN_*_Port` column or a `do_openvpn` URL parameter in `EmergencyCsvParser.applyOpenVpnColumnPort()`, `VpnServerMerger.enforceConfigAuthoritativePorts()` and `emergency_csv_publisher.enforce_config_ports()`, with every correction logged and §38 conflicts recorded; **no §10 column was added**. `SSTP_Hostname` may carry `:port` — live and recurring (`vpn799794865.opengw.net:1919`); the parser was already correct and is now pinned by test, the publisher was not and now splits it. `SE_UDP_Port` all-zero with `SE_UDP_Supported` true is **correct data** (the page publishes `UDP: Supported` and never a port) — no change. `Has_IPv6` is **unobservable** (0 occurrences on the live page) and inert — recorded UNKNOWN, no change. **TASK B.6** adds `AttemptOutcome.MISSING_CONFIG` (reported **before** any wait, so no budget is spent; ordinary attempt failure, next protocol on the same server, no wedge, no terminal) plus a per-attempt `[AUTO] ATTEMPT_CLASS` developer log, with the one distinction it cannot make (`NATIVE_ERROR` covers refused/TLS/protocol/auth because `TunnelStateWatcher` does not forward the stack message) stated in code rather than guessed. **The dataset advanced four times during the phase** (`a5cb153` 280 rows / `0387d37` 283 / `b5e0f7e` 271 / `4afa7e3` 229), so figures are pinned per commit: the config-less proportion is a range (72.5–78.1 %), `123.48.124.169` (1534 vs 1558) is in every snapshot, and `153.180.162.195` — UNKNOWN for `a5cb153` as the brief said — is **confirmed at `4afa7e3`** (1820 vs 1759). Nothing in this block wrote to `emergency-sources/servers.csv`, `servers.csv` or `vpngate_collector.py`; none was staged. Python `test_emergency_csv_publisher.py` **40 / 0**. The header **HEAD** field is the baseline commit `4afa7e3cec82a1078a84dcde9ba2d6c876107ce2`; a commit cannot carry its own hash. §22 byte-identical; §1.6.2–§1.6.44 untouched. No tag operation of any kind was performed.

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

#### 1.6.30 VERSION-BUMP-PAST-2.4.10 (v1.0.38)

1. **The identity collision.** HEAD declared `versionCode 143` / `versionName "2.4.10"` in
   `app/build.gradle` — the same identity as tag **`p2.4.10`** — while HEAD had moved **60+ commits**
   ahead of that tag. §1.6.12 bullet 5 (v1.0.19) recorded this as a forward-looking note: a future
   release phase *should* bump past `2.4.10` before the next cut. Every device install in the interim,
   including the v1.0.37 verification run, reported `versionCode 143`, so the installed binary could
   not be distinguished by version from the `p2.4.10` artifact. Evidence: SOURCE CODE.
2. **The fix.** `versionCode` **143 → 144**, `versionName` **"2.4.10" → "2.4.11"**, both inside the
   `pro` product flavor — the only place in the repository that declares the application version.
   A two-line comment above the fields cites this section. Nothing else in the version identity
   changed: `applicationId` stays `vn.unlimit.vpngatepro`, the `-dev` suffix on debug variants stays,
   and the `signingConfigs` block is untouched. Evidence: SOURCE CODE.
3. **Test pins.** None exist. No file under `app/src/test/` or `app/src/androidTest/` references
   `versionCode`, `versionName`, `143`, or `2.4.10`, so no test was modified and no count changed.
   The seven `BuildConfig.VERSION_NAME` / `VERSION_CODE` **readers** (About screen, `DetailActivity`,
   `StatusViewModel`, `AndroidConnectionAdapter`) are symbolic reads of a generated constant — they
   follow the bump automatically and are display-only; they are not pins. One **comment** in
   `CollectorSnapshotStore.kt` mentions "the 143 / 2.4.10 release line", but it is a historical
   citation of commit `0714628` used as mapping evidence, not a version declaration; that file is
   under `app/src/main/` and therefore out of scope for this phase. Evidence: SOURCE CODE.
4. **APK verification.** `aapt2 dump badging` on the built APK reports
   `package: name='vn.unlimit.vpngatepro' versionCode='144' versionName='2.4.11'`, confirming the
   manifest was regenerated from the bumped Gradle values rather than from a stale cache. Evidence:
   TEST.
5. **JVM tests.** `:app` **812 / 0 / 0 / 1** and `:SoftEtherClient` **173 / 0 / 0 / 0** — identical to
   the v1.0.37 baseline, as expected for a change that touches no code path. Evidence: TEST.
6. **Release-readiness.** This phase does **not** make 2.4.11 releasable. A release cut still requires
   the **fail-closed release keystore** (§1.6.10 bullet 8 / **RISK-A**): without `keyStore/release.jks`
   and `signKeyPassword`, the `signingConfigs` guard aborts every release assembly rather than
   producing a debug-signed "release" APK. **No tag was created** by this phase, and the tag count
   remains five. Evidence: SOURCE CODE + INFERENCE.
7. **The v1.0.37 contract is untouched.** The AutoConnect no-terminal contract (§1.6.29) — and its
   hardware verification (5/5 runs `Connected`, zero `ERROR_NATIVE_UNRESOLVED`) — is unaffected by a
   version-only change. Evidence: PRIOR REPORT.
8. **No new methodology lessons.**

#### 1.6.31 RETENTION-IMPL-PHASE-1 (v1.0.39)

1. **The requirement.** Cumulative Server Retention (`SERVER_SOURCE_ARCHITECTURE.md` §9.1–§9.2): a
   refresh that no longer lists a server must **not** delete it — rows present in storage but absent
   from the incoming feed are retained. At HEAD `fed8787` (the v1.0.38 docs commit, the code commit
   `fc359c6`), `VPNGateItem` carried no `firstSeenAt` / `lastSeenAt` / `healthScore`, and the single
   writer (`ServerListUpdates.daoStore.replaceAll`) replaced the whole table on every accepted
   refresh; `ACCEPT_NEW` / `MERGE_WITH_EXISTING` were names only (§1.6.16). Evidence: SOURCE CODE.
2. **The fix (phase 1 — foundation only).** Three parts, all within the authorized file set:
   - **(a) Schema.** `VPNGateItem` gains `firstSeenAt: Long`, `lastSeenAt: Long`, `healthScore: Int`,
     each `@ColumnInfo(name = "…", defaultValue = "0")`. Room `version 7 → 8`. `MIGRATION_7_8` runs
     exactly three `ALTER TABLE VPNGateItem ADD COLUMN … INTEGER NOT NULL DEFAULT 0` statements — no
     `DROP` / `DELETE` / `TRUNCATE` / `CREATE TABLE`, so every existing row survives the upgrade.
     `App.kt` registers `MIGRATION_7_8`. The KSP-exported `app/schemas/vn.unlimit.vpngate.db.AppDatabase/8.json`
     carries `version 8` and the three backticked columns; the migration DDL writes the same columns
     without backticks, so Room will not throw "Migration didn't properly handle" on a real upgrade.
     Evidence: SOURCE CODE + TEST.
   - **(b) Write path.** `ServerListUpdates.daoStore.replaceAll` now branches on `fetchedFromCache()`:
     a fallback (last-known-good snapshot / stored list / bundled seed) still replaces wholesale exactly
     as before (the `replaceAll` retention of the fallback path), while a fresh network observation is
     merged via the new `mergeAll(dao, items, manualRows, now)`. `mergeAll` reads `dao.getAll()`,
     matches on `hostName.trim()`, sets `firstSeenAt = prev?.firstSeenAt ?: now` (a new server is
     stamped `now`; a returning server keeps the moment it was first observed), `lastSeenAt = now`,
     recomputes `healthScore` (see bullet 3), retains stored rows the feed omitted, applies
     `withManualRows` first so manual servers survive the overlay, and commits via `dao.replaceAll(merged)`
     inside `VPNGateItemDao.replaceAll`'s `@Transaction` (deleteAll + insertAll) so the table is never
     left half-written. The `ServerListUpdater` class is untouched — the `fetchedFromCache` flag is
     threaded through `daoStore`. Evidence: SOURCE CODE + TEST.
   - **(c) `replaceAll` retained as the fallback writer** (no behaviour change on the cache path), so a
     snapshot / stored / seed dataset — which already represents a complete known-good set — still
     replaces wholesale. Evidence: SOURCE CODE.
3. **Phase-1 health score.** `retentionHealthScore(item, lastSeenAt)`:
   `0` if `lastSeenAt == 0` (a reserved sentinel for a pre-phase-1 row that has never yet been observed
   by a merge — an incoming row is always stamped `now`, so it never lands here);
   `100` if `item.score > 0 && item.ping > 0`;
   `50` if `item.score > 0 || item.ping > 0`;
   `25` otherwise. The score is a property of the latest observation, so it is always recomputed, never
   carried forward. This is the brief's minimal formula; the richer §9.3 retention-priority model is
   phase 2. Evidence: SOURCE CODE + TEST.
4. **Tests.** 11 new JVM tests in two files. `Migration7to8Test` (5): the migration is bounded to 7→8;
   it adds exactly the three retention columns; it is non-destructive (asserts no `DROP` / `DELETE` /
   `TRUNCATE` / `CREATE TABLE`); the DAO accessor is present; the generated v8 schema matches the
   migration DDL. `ServerListRetentionTest` (6): T-NEW-23 (a quiet stored server is retained and a new
   server gets `firstSeenAt == lastSeenAt == now`); T-NEW-24 (a returning server keeps `firstSeenAt`
   and refreshes `lastSeenAt`); T-NEW-25 (the score/ping health ranking — 100 / 50 / 25); T-NEW-25b
   (the `0` sentinel is reserved for never-refreshed rows); T-NEW-26 (a manual server survives the
   merge overlay); T-NEW-27 (the merge is additive, so a quiet server is never dropped). No existing
   test was modified or deleted. Evidence: TEST.
5. **JVM tests.** `:app` **823 / 0 / 0 / 1** (baseline 812 / 0 / 0 / 1 — the delta is exactly the 11
   new tests; the one skip is unchanged, `HtmlParserOracleTest`); `:SoftEtherClient` **173 / 0 / 0 / 0**
   (unchanged). Evidence: TEST.
6. **Build gate.** `:app:assembleProDebug --rerun-tasks --no-build-cache` under `CI=true` → BUILD
   SUCCESSFUL. `aapt2 dump badging` on the APK reports
   `package: name='vn.unlimit.vpngatepro' versionCode='144' versionName='2.4.11-dev'` — the `-dev`
   suffix is the debug-variant append; the base `versionName` is `2.4.11` from v1.0.38. APK SHA-256
   `79c102728581023f77f2542053fce11131a0263563f2c86c08c9aa70a6de3c17`. Evidence: TEST.
7. **Roadmap item G (§12.5.2).** Item G ("Safe merge and persistence") is now **PARTIAL**: the
   merge-to-retention branch is implemented here (manual preservation was already LANDED at v1.0.23;
   the cumulative-retention merge lands in this phase), but the §9.3 retention-priority prune and the
   max-server cap (phases 2–3) are **NOT IMPLEMENTED**, and item H's Android-runtime / device coverage
   remains open. Evidence: SOURCE CODE + TEST.
8. **Out of scope / what remains.** Phases 2–3: the max-server cap and the §9.3 retention-priority
   prune. The phase-1 merge only ever *adds* and *refreshes*, never *prunes*. A real-device 7 → 8
   upgrade is **UNKNOWN** — the migration is JVM-verified only; the KSP export and the recording-proxy
   test prove the DDL is correct, but no device has yet replayed `MIGRATION_7_8` against a populated
   database. Evidence: SOURCE CODE + UNKNOWN.
9. **No new methodology lessons.**

#### 1.6.32 AUTOCONNECT-DEVICE-BUGS-FIX (v1.0.40)

1. **The requirement.** Three Auto Mode defects were reported from hardware
   (`TRIAGE_AUTOCONNECT_DEVICE_BUGS_REPORT.md`, read-only discovery at `6229a76`): **BUG-1** the hero
   button read "Disconnected" while a tunnel was up, and the tap then did nothing; **BUG-2** MS-SSTP
   was never attempted; **BUG-3** the battery-optimisation dialog reappeared on every press. This phase
   fixes **BUG-1** and **BUG-3** and **defers BUG-2** (bullet 7). Evidence: SOURCE CODE + PHYSICAL
   DEVICE (the symptom reports).
2. **BUG-1 — root cause (unchanged from the triage).** The hero button was bound exclusively to
   `AutoModeState` (`AutoModeScreen.kt:140`, `:360–363`, `:590–597`), whose only writers are
   `AutoModeController.setState` (`:534–536`, `Connected` published only at `:2050`) →
   `AutoModeEngine._state` (`:149–150`). A manual connect (`DetailActivity.kt:318`) or a service-level
   reconnect never invokes any of them, so the button could not reflect it. The D-4 guard reads the
   *true* state (`isTunnelBusy = { GlobalVpnTracker.isActiveOrBusy }`, `AutoModeEngine.kt:137`) and
   therefore refused the tap (`AutoModeController.kt:1059–1060`). The reconciliation already existed
   for the list highlight — `HomeScreen` observes both sources (`:125–128`) and `activeServerOf()`
   falls back to `GlobalVpnState.status` (`:693–711`) — but the Auto Mode screen did not use it.
   Evidence: SOURCE CODE.
3. **BUG-1 — the fix (display aggregation, no state-machine change).** `AutoModeViewModel.kt` gains a
   `data class AutoModeDisplayState(modeState, tunnelStatus, display, tapMeansDisconnect, isExternal)`
   and a pure `internal fun combineAutoModeState(modeState, tunnelStatus)`: an Auto Mode run owns the
   display while it is `Connected` or `Connecting` (even if the tracker has not caught up); otherwise a
   `CONNECTED` / `CONNECTING` tracker is surfaced as a synthesized `Connected` / `Connecting` with
   `isExternal = true`; otherwise the run state passes through unchanged. The ViewModel publishes
   `displayState: LiveData<AutoModeDisplayState>` from
   `combine(AutoModeEngine.stateFlow, GlobalVpnTracker.vpnState)` — the same two sources
   `HomeScreen` combines — while `state` keeps its existing meaning. Evidence: SOURCE CODE + TEST.
4. **BUG-1 — one deliberate divergence from the triage's proposed shape.** The triage suggested using
   `AutoModeState` when it is `Error`; this phase lets a live external tunnel **outrank** `Error`, so
   the button shows Connected rather than Error while a tunnel is up (T-NEW-28g). Rationale: the point
   of the fix is that the button and the D-4 guard must agree — the guard sees a busy tunnel and
   refuses to start, so an `Error` button that offers "Tap to retry" would re-create the original
   mismatch in the other direction. The error itself is preserved in `modeState` and still drives the
   error card. Evidence: SOURCE CODE + TEST + INFERENCE (the guard's behaviour is read from source, not
   observed on a device in this phase).
5. **BUG-1 — screen wiring and the external tap.** `AutoModeScreen` renders
   `AutoConnectHeroButton(state = displayState.display, isExternal = displayState.isExternal, …)` and
   `startAutoModeWithAllChecks()` now decides from the **same** combined value it renders (read at click
   time from the LiveData, not from a composition snapshot): `Disconnected`/`Error` → start the normal
   permission path; otherwise, if `isExternal`, call the new
   `AutoModeViewModel.disconnectExternalTunnel()` → `AutoModeEngine.stopAndDisconnect()` →
   `AutoModeController.disconnectNow()`, which issues the teardown unconditionally (its `isRunning`
   branch is not taken, so it still calls `requestDisconnect()` + `disconnect()`); otherwise keep the
   existing `onButtonPressed()` toggle. `onButtonPressed()` alone could not serve the external case —
   its D-4 guard refuses exactly there — so the fix would otherwise have left the user with a
   "Connected" button and no way out. An additive string `auto_mode_state_connected_external`
   ("Connected (manual)") marks the external case; other locales fall back to it. `state` is still
   observed and still drives the developer log, the error card and "Try next server". Evidence: SOURCE
   CODE.
6. **BUG-3 — root cause and fix.** The gate was the live OS state alone —
   `if (!isIgnoringBattery) { showBatteryDialog = true; return }` (`AutoModeScreen.kt:201–209`) — with
   `showBatteryDialog` a transient `remember` flag (`:142`) and **no** persisted "asked" preference
   anywhere in the repository. After "Later" (or after a manufacturer that resets the exemption), every
   press re-read the OS, still found "not exempt", and re-opened the dialog. `DataUtil` now carries
   `SETTING_BATTERY_OPT_ASKED` plus `hasAskedBatteryOptimization()` / `setAskedBatteryOptimization()`,
   mirroring the `SETTING_LOCALE_ANSWERED` precedent (`DataUtil.kt:534`, `:327–329`); the gate is
   `!isIgnoringBattery && !hasAskedBatteryOptimization()`, and the flag is written on **every** dialog
   exit — Configure, Later, and `onDismissRequest` (back / outside tap). Consequence, stated plainly:
   the prompt is offered **once per install**, not once per un-exempt episode; a user who later changes
   their mind reaches the same screen from system Settings. Evidence: SOURCE CODE + TEST.
7. **BUG-2 is deferred, not fixed and not claimed.** The triage could not decide between "the user's
   dataset contains no SSTP-capable server" and "MS_SSTP is filtered out before selection": only the
   HTML source supplies protocol columns (`VpnServerRepository.kt:252–255`), the API and the bundled
   seed carry none, and the merger *strengthens* SSTP rather than dropping it
   (`AutoModeController.kt:2236`). The distinguishing evidence is a device log of one Auto Mode run
   with MS_SSTP enabled; no device run happened in this phase, so the user's dataset remains
   **UNKNOWN**. Evidence: SOURCE CODE + UNKNOWN.
8. **Tests.** 14 new JVM tests in two new files, no existing test modified or deleted.
   `AutoModeDisplayStateTest` (8, T-NEW-28a…h) exercises `combineAutoModeState` directly: quiet case
   unchanged; external `CONNECTED` shows Connected with `isExternal` and `tapMeansDisconnect`; external
   `CONNECTING` shows Connecting; `DISCONNECTING` is **not** shown as Connected; a run's own
   `Connected`/`Connecting` wins over a lagging tracker; a live tunnel outranks `Error`; an `Error`
   with no tunnel stays an `Error` offering a retry. It also pins that the synthesized state carries
   **no** fabricated `hostname`/`ip`. `DataUtilBatteryFlagTest` (6, T-NEW-29a…f) covers the flag
   through `DataUtil`'s preferences accessors: default false; true after set; survives a new `DataUtil`
   over the same store; stored under the documented key; and no cross-talk in either direction with
   `SETTING_LOCALE_ANSWERED`. Because this module's unit tests are **JUnit 4 only** (no Robolectric, no
   Mockito) and `DataUtil`'s primary constructor needs a `Context`, the test injects an in-memory
   `SharedPreferences` through a new `internal constructor(sharedPreferences)` seam; the flag accessors
   touch nothing else, so the seam adds no production behaviour. Evidence: TEST.
9. **JVM and build gate.** `:app` **837 / 0 / 0 / 1** (baseline 823 / 0 / 0 / 1 — the delta is exactly
   the 14 new tests; the one skip is unchanged, `HtmlParserOracleTest`); `:SoftEtherClient`
   **173 / 0 / 0 / 0** (unchanged). `:app:assembleProDebug --rerun-tasks --no-build-cache` under
   `CI=true` → BUILD SUCCESSFUL; `aapt2 dump badging` reports
   `versionCode='144' versionName='2.4.11-dev'` (the version is **unchanged** — no build or manifest
   file was touched; `-dev` is the debug-variant append). APK SHA-256
   `5bed6128ceb382e9619567eefd2ee989189c26ac2e36fc2efc566ec3bf60c91a`. Evidence: TEST.
10. **Scope, and what is not verified.** Only four authorized files changed
    (`AutoModeViewModel.kt`, `AutoModeScreen.kt`, `DataUtil.kt`, one additive `strings.xml` entry) plus
    two new test files. `AutoModeController.kt`, `AutoModeEngine.kt`, `GlobalVpnTracker.kt`,
    `HomeScreen.kt`, `AndroidConnectionAdapter.kt` and every build/manifest file are untouched, so the
    Auto Mode state machine and the D-4 refusal are exactly as verified at `6229a76`. **All three bugs
    remain un-verified on hardware** — this phase ran no device, so BUG-1's fix and BUG-3's one-shot
    prompt are SOURCE CODE + TEST only, and the device symptom reports are the only PHYSICAL DEVICE
    evidence in play. Evidence: SOURCE CODE + TEST + UNKNOWN (device).

#### 1.6.34 TRAFFIC-PATH-DYNAMIC phase 1 (v1.0.41)

1. **The recorded limitation, and what was actually true.** §12.5.2's v1.0.29 note and §1.6.10
   bullet 8 both record that `GlobalVpnTracker.isTunnelUsable()` "exists but is not consumed by
   production routing", with the added limitation that "nothing calls `noteRxAdvance()` from the
   data-forwarding path, so `isTunnelUsable()` can only ever report `false`". The second half of that
   statement stopped being true in R4b: `TunnelHealth.noteRxAdvance()` is called at
   `ConnectionController.kt:1157`, once per successfully-received batch, with KDoc explaining why the
   call sits at the batch boundary rather than per packet. The first half was still true at
   `02765a6`: `productionRouting()` fed `tunnelUsable` from `isConnected && currentVpnNetwork() != null`
   and `resolvePrimary()` read `preferVpn` alone, so the field had no production consumer at all —
   `CollectorRouting.choose()` is its only reader and has no production call site. Both halves are
   addressed here. §12.5.1 row 10 is **FROZEN** and is therefore annotated by this section rather than
   edited; the two stale code comments are corrected (bullet 7). This section is numbered §1.6.34 per
   the phase brief; §1.6.33 is unused in this document. Evidence: SOURCE CODE + TEST + PRIOR REPORT.
2. **FIX-1 — not changed, because it was already done.** The phase brief's premise ("NOTHING calls
   `noteRxAdvance()` from the actual RX path") does not hold at `02765a6`. The producer is
   `TunnelHealth.noteRxAdvance()` at `ConnectionController.kt:1157`, inside the `total > 0 ->` branch
   of the receive loop (`:1140`), one call per `client.receiveBatch(...)` (`:1138`). It is already
   covered by `RxAdvanceWiringTest` (7 tests). No line in `ConnectionController.kt` was touched, and no
   second call was added. Evidence: SOURCE CODE + TEST.
3. **FIX-2 — `isTunnelUsableForRouting()`, and why the grace period looks like this.**
   `GlobalVpnTracker.kt:459–467` adds a wrapper over the **unchanged** `isTunnelUsable()`: not
   connected → `false`; no OS-bound VPN network → `false`; strict health check true → `true`; otherwise
   an RX advance has *never* been observed and no TUN EIO is inside `TunnelHealth.EIO_WINDOW_MS` →
   `true` (the grace period); otherwise `false`. The strict check alone would route a healthy
   just-connected tunnel to OPEN, because no frame has arrived inside `RX_WINDOW_MS` yet, which would
   defeat a user's explicit `vpn_always_prefer_vpn` opt-in for the first seconds of every tunnel. The
   grace branch is gated on `rxCounter == 0`, so it cannot mask a tunnel that came up, carried traffic
   and then died —
   once the counter has moved, only a fresh RX advance returns `true`. The decision lives in the
   file-scope pure function `routingUsableFromSignals(...)` (`:797`) so it is testable on a plain JVM
   without class-loading the Android-dependent object; the EIO comparison uses `<=` where
   `TunnelHealth.isUsable` uses `<`, i.e. the stricter side at the exact boundary.
   **`connectedAtMs` is added but deliberately not consumed**: the grace window is expressed as
   "nothing observed yet and no recent EIO", which needs no start time; the timestamp
   (`:311`, getter `:321`, stamped on the transition into CONNECTED at `:617–625`, reset on
   DISCONNECTED/ERROR) is the correct anchor for a future *time-bounded* grace period and gives a
   device log the tunnel age at the moment routing declined. Evidence: SOURCE CODE + TEST.
4. **FIX-3 — `resolvePrimary()` now gates on `tunnelUsable` (operator authorisation A1).**
   `VpnServerRepository.kt:149–151` returns `VPN` only when `preferVpn && tunnelUsable`, otherwise
   `OPEN`; `productionRouting()` (`:119–132`) supplies the wrapper's value and no longer ORs live
   tunnel state into `preferVpn` (the v1.0.27 FIX-3a property is preserved, and pinned by T-NEW-36).
   The KDoc contract at `:91–118` is rewritten: `preferVpn` is the user's *intent*, `tunnelUsable` is
   the health gate, and the VPN rung is still never forced by tunnel state alone. Evidence: SOURCE
   CODE.
5. **The behaviour difference, stated exactly.** Default user (`prefersVpnForCollector() == false`):
   OPEN regardless of `tunnelUsable` — **unchanged**. Opt-in user with a healthy tunnel: VPN —
   **unchanged**. Opt-in user with a dead tunnel: OPEN **directly** — **changed**; pre-v1.0.41 the
   collector took the VPN rung and recovered only through `fetchOnChosenPath`'s fallback, paying a
   full timeout for an attempt that could not succeed. The fallback itself is unchanged and still
   covers the OPEN-primary case (VPN as a fallback when OPEN fails and a bound fetcher exists).
   Evidence: SOURCE CODE + INFERENCE (the saved timeout is inferred from the code path, not measured on
   a device in this phase).
6. **Tests.** 9 new JVM tests in two new files, no existing test modified or deleted.
   `GlobalVpnTrackerRoutingTest` (6, T-NEW-30…35) drives `routingUsableFromSignals` directly:
   disconnected; connected without a bound network; passing traffic; the grace case; traffic observed
   then stopped; and the EIO window (inside, exactly on the boundary, outside — T-NEW-35 covers both
   sides). `VpnServerRepositoryRoutingDynamicTest` (3, T-NEW-36…38) pins the source shape of
   `productionRouting` (feeds the wrapper, no `isConnected`, no `||`), exercises all five
   `resolvePrimary` permutations behaviourally, and pins that the `fetchOnChosenPath` fallback and
   `CollectorRouting.choose`'s three-way AND are untouched. Evidence: TEST.
7. **The two stale comments corrected (operator authorisation A3).** `SoftEtherVpnService.kt:1735–1740`
   and `GlobalVpnTracker.kt:357–363` both asserted that `noteRxAdvance` has no production caller.
   Both are comment-only edits naming `ConnectionController.kt:1157` and this section; no code in
   either file changed, and the `TunnelHealth` object's counters and `isUsable` are untouched.
   Evidence: SOURCE CODE.
8. **JVM and build gate.** `:app` **846 / 0 / 0 / 1** (baseline 837 / 0 / 0 / 1 — the delta is exactly
   the 9 new tests; the one skip is unchanged, `HtmlParserOracleTest`), with
   `VpnServerRepositoryR4bTest` (10), `VpnServerRepositoryRoutingTest` (8),
   `GlobalVpnTrackerTest` (17) and `RxAdvanceWiringTest` (7) all green; `:SoftEtherClient`
   **173 / 0 / 0 / 0** (unchanged). `:app:assembleProDebug --rerun-tasks --no-build-cache` under
   `CI=true` → BUILD SUCCESSFUL; APK SHA-256
   `1340fed9771828d3b9655c4b05c0d2778ed737e9bd18b6363b16c44d2902159f`. `versionCode 144` /
   `versionName 2.4.11` are unchanged — no build or manifest file was touched. Evidence: TEST.
9. **What this phase deliberately did not do.** Per-source path memory (the §11.8 "retain useful
   source/path outcome information" requirement) remains **phase 2**: the transport is still chosen
   once per run and applied to every source. `CollectorRouting.choose()` keeps its pure semantics and
   is still unused in production. `fetchOnChosenPath` is untouched. Evidence: SOURCE CODE.
10. **Device verification is PENDING — UNKNOWN.** The check that would close this phase is: opt in to
    `vpn_always_prefer_vpn`, bring a tunnel up, break it (Wi-Fi toggle) without tearing it down, then
    refresh — the collector must log `[collector] path=open` from the **first** attempt, not after a
    VPN timeout. This phase ran no device, so the routing change is SOURCE CODE + TEST only.
    Evidence: UNKNOWN.
11. **No new methodology lessons.** The one process observation worth recording is that the phase
   brief's problem statement was derived from a stale comment rather than from the call graph: two of
   its three fixes were already implemented at the baseline. Reading the producer before writing the
   fix is what turned a three-fix phase into a one-fix phase. Evidence: PRIOR REPORT + INFERENCE.

#### 1.6.35 AUTOCONNECT-CORRECTNESS-CLUSTER (v1.0.42)

1. **One cause, four symptoms.** Every item in this cluster is a value or a position that the
   controller captured once and then treated as if it were still true: the per-attempt timeout, the
   native-slot wait derived from the same preference, the assumption that the device has a network,
   and the integer index of "the server after this one". Three of them are correctness fixes; the
   fourth (F6) is a **reversal of a documented decision** and is labelled as such throughout. Evidence:
   SOURCE CODE + TEST.
2. **F1 — the timeout was frozen at construction, and the singleton made that permanent.**
   `AutoModeEngine.ensure()` builds the controller exactly once (`controller?.let { return it }`), while
   `AndroidConnectionAdapter` re-reads `SETTING_AUTO_MODE_TIMEOUT_SECONDS` on every call. The moment a
   user changed the setting, the run kept logging the old value and only a process restart reconciled
   them — device-observed and recorded in `DEVICE_VERIFY_AUTOCONNECT_BUGS_v1.0.41_REPORT.md` §11. The
   constructor parameter is now `attemptTimeoutMsProvider: () -> Long`, read through
   `private fun attemptTimeoutMs(): Long` at every point that needs it (the log line, both
   `awaitArmedOutcome` calls, both `minOf` slot budgets, the `TIMED_OUT` line). The default provider
   returns `DEFAULT_ATTEMPT_TIMEOUT_MS`, so a caller that does not model the setting keeps the
   pre-v1.0.42 behaviour exactly. Evidence: SOURCE CODE + PRIOR REPORT.
3. **B-3 — the slot wait is derived from the same setting, so it had to move too.** Extending F1 to
   `nativeSlotAwaitMs` was authorised as B-3 precisely because the production derivation is
   `5 × T` from the *same* preference: fixing only the attempt budget would have left a frozen value
   in the path that decides how long a wedged native operation may hold the process-wide slot.
   Production now supplies
   `(T × 5).coerceAtLeast(NATIVE_SLOT_AWAIT_MS).coerceAtMost(NATIVE_SLOT_AWAIT_MAX_MS)`, preserving
   the v1.0.36 floor (30 s) and the D-2 cap (60 s) that were previously applied inside the
   constructor. Evidence: SOURCE CODE.
4. **The two guards on the new providers are positivity-only, deliberately.** Both helpers are
   `coerceAtLeast(1L)` (`nativeSlotAwaitMs` keeps its `NATIVE_SLOT_AWAIT_MAX_MS` upper cap so a
   provider that ignores it cannot make the terminal give-up unreachable). They are **not** a second
   floor. The first draft used 1 s and 5 s floors; both were removed during this phase because they
   were redundant in production and destructive in tests: `getAutoModeTimeoutSeconds()` already clamps
   to 5..60 s at the source (`DataUtil.kt:361`), and the floors silently overrode doubles that ask for
   a short budget — the 1 s floor broke the two `…IsCappedToTheAttemptBudget` tests (which use 300 ms
   to make the cap assertion exact), and the 5 s floor would have turned
   `AutoModeNativeBusyTest`'s 200 ms slot wait into 5000 ms, pushing its "genuinely stuck" cases
   against their 15 s bounds. A bound that only tests can see is not a safety property. Evidence:
   SOURCE CODE + TEST.
5. **F2 — an offline run used to blame every server.** Before v1.0.42 a press with Wi-Fi and data both
   off walked the whole list, marked each candidate `TIMED_OUT`, and gave each one a
   `WedgeTracker` strike; after two such runs `EXCLUSION_THRESHOLD` (2) had excluded every healthy
   server for the rest of the process, so the next online press had nothing left to dial. The
   controller now takes `isNetworkAvailable: () -> Boolean` (default `true`, so no existing double
   changes behaviour) and consults it in two places: immediately after the protocol check and **before**
   the list is resolved or the continuation is consumed, and again inside the loop before each
   attempt. Both stop with the new `Error(ERROR_NO_NETWORK)` and record no wedge. Evidence: SOURCE
   CODE + TEST.
6. **`ERROR_NO_NETWORK` is a system-level terminal with its own sentence.** `ERROR_NO_NETWORK =
   "no_network"` sits beside `ERROR_NO_ENABLED_PROTOCOL`; §6.8.4 lists it as terminal #8, and §6.7 R7's
   "system-level terminals" set grows to six. `AutoModeScreen` maps it to a new
   `auto_mode_error_no_network` string ("No network available. Turn on Wi-Fi or mobile data and try
   again.") rather than falling through to the generic failure text — the condition is one the user can
   fix in two taps, which is the reason it is not folded into `ERROR_NO_SERVER`. Evidence: SOURCE CODE.
7. **`GlobalVpnTracker.isAnyNetworkAvailable()` is deliberately coarse.** It reads
   `ConnectivityManager.activeNetwork != null` and returns `true` when `appContext` or the service is
   missing. Both choices fail **open**: a missing context must not manufacture a "no network" terminal,
   and `activeNetwork != null` is the weakest question that answers "is there any network at all" —
   the guard's job is to stop a run that cannot possibly succeed, not to judge whether a particular
   server is reachable (that is `awaitTunnel`'s job). Evidence: SOURCE CODE.
8. **F6 — the Next-Server continuation is now an identity, and this reverses a v1.0.31 decision.**
   `resumeFromIndex: Int` (`currentServerIndex + 1`) becomes `resumeFromServerKey: String?`, holding
   the `ip|hostname` identity of the server that connected — the same key `wedgeKey` gives the wedge
   ledger, formatted in exactly one place (`serverKey(ip, hostname)`). Continuation is
   `indexOfFirst { serverKey(it) == key } + 1`. **This is not a bug fix**: the v1.0.31 KDoc explicitly
   accepted the index drift, on the theory that pinning an identity would "silently skip a candidate
   that has since become better, and the ordering is the contract (§6.6)". v1.0.42 overrides that
   theory (operator decision). The trade-off is real and is recorded in the KDoc rather than hidden: a
   candidate that became better and now sorts *before* the connected server is skipped by this
   continuation; it is not skipped for ever, because the next fresh press starts from the top.
   Identity wins because the drift failure mode is worse — the old index silently continues from a
   position that now names a *different* server, which is the opposite of what the user asked for.
   Evidence: SOURCE CODE + PRIOR REPORT.
9. **The vanished-server case is new, and it is logged.** With an index there was no "not found" case;
   with an identity there is. If the connected server has left the dataset, the run starts at index 0
   and logs `[AUTO] Previous connected server no longer in the list; starting from the top` — it never
   restarts silently. The *last* server is still **found**, so that case keeps its v1.0.31 meaning
   (no attempts, `ERROR_NO_SERVER`), which is why the unchanged
   `pressingWhileConnectedToTheLastServerEndsWithoutRestarting` still pins it. Evidence: SOURCE CODE +
   TEST.
10. **The skip filter now uses the same identity — this one *is* a bug fix.** `skipToNextServer()`'s
    Connected branch filtered by `it.ip == currentIp`, which also drops a **different** server that
    happens to share an IP (a NAT or load-balancer shape) and could end the run with `ERROR_NO_SERVER`
    while a perfectly good candidate was filtered out. It now filters on `serverKey`, i.e. the same
    key the continuation uses, so the two can never disagree about what "this server" means.
    Evidence: SOURCE CODE + TEST.
11. **Tests.** 12 new JVM tests in four new files; no existing test was deleted, and the only edits to
    existing test files are mechanical (13 constructor call sites in 7 files, `attemptTimeoutMs = x` →
    `attemptTimeoutMsProvider = { x }`, plus one `nativeSlotAwaitMsProvider`). `AutoModeNetworkGuardTest`
    (4): offline stops before any attempt, records no wedge, online still dials, and a mid-run loss
    stops before the next server. `AutoModeNextServerIdentityTest` (4): a re-sort does not shift the
    continuation, a vanished server restarts at the top and logs it, the skip filters by identity not
    by IP, and the attempt timeout is read fresh per attempt. `AutoModeSlotWaitBudgetTest` (1): the
    slot budget is read fresh per attempt, observed through the `awaitNativeSlotRelease` argument.
    `AutoModeNoNetworkMessageTest` (3): the string resource exists and tells the user what to do, the
    screen branch maps to it, and the terminal id stays `"no_network"`. The four OP-R6/skip tests from
    v1.0.31/v1.0.37 and both slot-cap tests pass unchanged. Evidence: TEST.
12. **JVM and build gate.** `:app` **858 / 0 / 0 / 1** (baseline 846 / 0 / 0 / 1 — the delta is exactly
    the 12 new tests; the one skip is unchanged, `HtmlParserOracleTest`); `:SoftEtherClient`
    **173 / 0 / 0 / 0** (unchanged). `:app:assembleProDebug --rerun-tasks --no-build-cache` under
    `CI=true` → BUILD SUCCESSFUL; APK SHA-256
    `e8a4bb1400e9046c1c0b4b34218b18da10c127a1992407ead8062f5fd5a67ecd`. `versionCode 144` /
    `versionName 2.4.11` are unchanged — no build or manifest file was touched. Evidence: TEST.
13. **The original brief stopped at STEP 2, and the revision is the record of why.** Three of its
    premises did not hold at `c5a7bb5`: making `attemptTimeoutMs` a lambda was source-incompatible with
    13 call sites living in six test files outside the single authorised test file; §11.13.3 says "the
    next server in the **sorted list**", not "the same identity", so F6 was a new decision rather than
    the enforcement of existing text; and `ERROR_NO_NETWORK` had no user-facing string, so it would
    have rendered as the generic message. The revised brief authorised the six mechanical test edits
    (A), extended F1 to `nativeSlotAwaitMs` (B-3), authorised the UI mapping and string (B-4), and
    required F6 to be reported as a decision revert (this section, §6.8.5, §11.13.3). Evidence: PRIOR
    REPORT.
14. **What this phase deliberately did not do.** No change to the wedge thresholds, the retry budget,
    the R8 Auto-Reconnect path, or the reachability pre-pass. `ERROR_NATIVE_UNRESOLVED` remains
    unpublished (v1.0.37). The per-source path memory of §11.8 remains phase 2 of the traffic-path
    work. Evidence: SOURCE CODE.
15. **Device verification is PENDING — UNKNOWN.** The three checks that would close this phase are:
    (a) change the Auto Connect timeout in Settings mid-session and confirm the next attempt logs the
    new value; (b) press Auto Connect with Wi-Fi and data both off and confirm the run stops at once
    with the "No network available" message and no `TIMED_OUT` lines; (c) re-sort the list (change the
    sort setting) between two presses and confirm the second run continues after the *same server*,
    not after the same index. This phase ran no device, so every claim here is SOURCE CODE + TEST.
    Evidence: UNKNOWN.

#### 1.6.36 ROUTING-REACHABILITY-AND-EPERM-DIAGNOSTICS (v1.0.43)

1. **Two device findings, two additive fixes, no control-flow change.** This phase closes two findings from
   the v1.0.43 device verification (`DEVICE_VERIFY_CLUSTER_v1.0.43_REPORT.md`): **M1** — the
   `vpn_always_prefer_vpn` opt-in had no writer, so the v1.0.41 VPN routing rung was unreachable for every
   user; and **M2** — a VPN-rung fetch failed with `EPERM (Operation not permitted)` on the device with no
   attribution. Both fixes are additive, and the M2 fix is diagnostics-only. Nothing in the frozen routing
   files (`GlobalVpnTracker.kt`, `VpnServerRepository.kt`, `AutoModeController.kt`, `AutoModeEngine.kt`,
   `AutoModeScreen.kt`, `AutoModeViewModel.kt`) is touched. Evidence: SOURCE CODE + TEST.
2. **M1 — the opt-in now has a writer and a UI toggle.** `DataUtil.getPrefersVpnForCollector()` /
   `setPrefersVpnForCollector()` reuse the exact key literal `"vpn_always_prefer_vpn"` (the value of
   `GlobalVpnTracker.PREF_ALWAYS_PREFER_VPN`, read by `prefersVpnForCollector()` at `GlobalVpnTracker.kt:527`).
   The literal is duplicated on purpose so the accessor is independent of the frozen file and a source-shape
   test can assert the exact byte string. `SettingsScreen`'s Auto Mode section gains a `SettingSwitchRow`
   (`setting_prefer_vpn_collector_label` / `setting_prefer_vpn_collector_summary`, icon `VpnLock`) bound to
   `dataUtil.get/setPrefersVpnForCollector()`, defaulting off. Evidence: SOURCE CODE + TEST.
3. **M1 — the toggle is the missing piece, not a behavioural change.** `CollectorRouting.choose()` and
   `productionRouting()` are unchanged, so the VPN rung still requires `preferVpn && tunnelUsable &&
   hasBoundNetwork`. The only new behaviour is that a user can now set `preferVpn = true`; until this phase
   that branch was dead because nothing wrote the key. Evidence: SOURCE CODE + INFERENCE.
4. **M2 — bind diagnostics, no control-flow change.** `HttpFetcher.boundToNetwork(network)` is wrapped in a
   `try/catch (Throwable)`: on success it logs `[bind] ok network=…`; on failure it logs
   `[bind] FAILED network=… errno=…` and re-throws. `throw e` is preserved, so the open → VPN-fallback ordering
   is untouched. The two log strings are pinned by source-shape tests (T-NEW-50/51) so a refactor cannot
   silently drop them. Evidence: SOURCE CODE + TEST.
5. **M2 — the EPERM root cause is explicitly NOT fixed here.** The device line was
   `Request failed: Binding socket to network 115 failed: EPERM (Operation not permitted)`. That string is
   produced in `OkHttpFetcher.execute()` at socket-open time (the bound socket factory is used lazily), which
   is a *different* site from `boundToNetwork`'s construction. This phase adds construction-time visibility
   only; the socket-open EPERM remains attributed by the pre-existing "Request failed" log. The next device
   run is expected to disambiguate a stale/revoked `Network` (caught here) from a genuine kernel EPERM on the
   live tunnel's network (caught later). Evidence: SOURCE CODE + PRIOR REPORT + INFERENCE.
6. **Tests.** 7 new JVM tests in two new files; no existing test was deleted or edited.
   `DataUtilPrefersVpnTest` (4): default off (T-NEW-46), set/get round-trip (T-NEW-47), survives a new
   `DataUtil` over the same store (T-NEW-48), and stored under the exact key literal `"vpn_always_prefer_vpn"`
   (T-NEW-49). `HttpFetcherBindDiagnosticTest` (3): source-shape — `[bind] ok` present (T-NEW-50),
   `[bind] FAILED` present (T-NEW-51), `boundToNetwork` still defined and calls `boundToSocketFactory`
   (T-NEW-52). The in-memory `SharedPreferences` fake is renamed (`PrefersVpnFake*`) to avoid a top-level
   private-class collision with the same-named fake in `DataUtilBatteryFlagTest.kt`. Evidence: TEST.
7. **JVM and build gate.** `:app` **865 / 0 / 0 / 1** (baseline 858 / 0 / 0 / 1 — the delta is exactly the
   7 new tests; the one skip is unchanged, `HtmlParserOracleTest`); `:SoftEtherClient` **173 / 0 / 0 / 0**
   (unchanged). `:app:testProDebugUnitTest` + `:SoftEtherClient:test` under `CI=true` both green.
   `:app:assembleProDebug --no-daemon` under `CI=true` → BUILD SUCCESSFUL; APK SHA-256
   `ed82fc058796a45698cd41a90d4dcb1fb20ecbc05198b30d57eaecf2117bce19`. `versionCode 144` / `versionName 2.4.11`
   are unchanged — no build or manifest file was touched. Evidence: TEST.
8. **Device verification is PENDING — UNKNOWN.** The two checks that would close this phase are: (a) enable the
   new "Route collector traffic via VPN" toggle, bring up a usable tunnel, and confirm a collector fetch takes
   the VPN rung (and is absent when the tunnel is down); (b) reproduce the VPN-rung `EPERM` on a device and
   confirm the new `[bind] FAILED` line attributes it to the network. This phase ran no device, so every
   claim here is SOURCE CODE + TEST. Evidence: UNKNOWN.

#### 1.6.37 DIAGNOSTIC-PLACEMENT-FIX (v1.0.44)

Two device findings from the v1.0.43 cluster run (DEVICE_VERIFY_CLUSTER_v1.0.43_REPORT.md §8) are resolved at the source level in this revision:

- **Diagnostic placement (P-2).** The `[bind]` diagnostics added in v1.0.43 (`HttpFetcher.boundToNetwork`) sit on a code path production does **not** call — `VpnServerRepository.kt:163` obtains the bound client through `boundToSocketFactory` directly, and `GlobalVpnTracker.currentVpnNetwork()` is the live `Network`. Consequently the `EPERM` actually observed on device (produced at socket-open time inside `OkHttpFetcher.execute()`'s `catch (IOException)`, the pre-existing "Request failed" line — not at `boundToNetwork` construction) was never attributed by the new logs. This revision enriches that catch with the exception class: `Request failed: [exception=<class>] <message>`, leaving the `return null` control flow and the v1.0.43 `[bind]` logs both intact. The network handle is already present in `e.message` ("Binding socket to network N failed: EPERM").
- **Version ambiguity (P-1).** The v1.0.43 phase's final summary called itself "v1.0.44" while the spec header read "1.0.43"; this revision resolves the discrepancy by bumping the header to **1.0.44** so the spec and the summary agree. No behavioural change attends the version bump.

Both changes are **diagnostics-only / doc-only**; no routing behaviour, no control flow, and no reachable-by-default state change. §12.5.1 and §22 remain FROZEN; §1.6.2–§1.6.36 are untouched.

#### 1.6.38 DYNAMIC-PATH-RECOVERY (v1.0.45)

Operator requirement, verbatim in intent: the server list must update dynamically; **if the physical path is blocked, the update must go through the app's own tunnel automatically — no manual toggle, and the database is never wiped.** The v1.0.44 Phase B device run (`DEVICE_VERIFY_SERVER_LIST_UPDATE_v1.0.44_REPORT.md`) showed the app could not do that, for three separate reasons. This is Phase A (device-free); Phase B (device) is out of scope and did not run.

1. **P1 — the SharedPreferences mismatch (definite).** The Settings toggle wrote through `DataUtil.setPrefersVpnForCollector()`, which called `setBooleanSetting("vpn_always_prefer_vpn", …)` on the DataUtil store — `context.getSharedPreferences("vpn_setting_data_" + BuildConfig.FLAVOR, MODE_PRIVATE)` (`DataUtil.kt:50`). The reader, `GlobalVpnTracker.prefersVpnForCollector()`, reads `PreferenceManager.getDefaultSharedPreferences` (`GlobalVpnTracker.kt:526–528`). **Same key literal, different files**, so the write never reached the read and the v1.0.41 VPN rung was unreachable for every user. Evidence: SOURCE CODE (both sites inspected) + PHYSICAL DEVICE (v1.0.44 Phase B **T6b FAIL** — with the in-app switch confirmed `checked=true` by `uiautomator` and a tunnel up, every attempt still logged `path=open` first).

2. **FIX-1 — DataUtil two-store write.** `setPrefersVpnForCollector()` keeps the DataUtil write and **adds** a write to the store the reader actually reads, resolved lazily from `mContext` via `PreferenceManager.getDefaultSharedPreferences` and wrapped in `runCatching` so a missing context can never crash the Settings screen (`DataUtil.kt:426–440`). The reader store is held in `defaultPreferences` (`DataUtil.kt:44`) and injected in tests through a new `internal constructor(sharedPreferences, defaultPreferences)` (`DataUtil.kt:78–84`). The getter is deliberately **unchanged** (`DataUtil.kt:422–423`) — it still reads the DataUtil store, so the switch keeps painting the value it always painted. The key literal `"vpn_always_prefer_vpn"` is unchanged (frozen constraint). Evidence: SOURCE CODE + TEST (T-NEW-54a..d).

3. **P2 — "open" is not "outside the tunnel".** `path=open` used `defaultCollectorClient()`, an **unbound** OkHttp client. When the app's own tunnel is up and the app is not self-excluded, the OS default network *is* that tunnel, so an "open" request went through it. The distinction the routing model claimed therefore did not exist on device: both rungs could resolve to the same physical path, and a genuinely blocked direct path could not be detected by observing that one rung failed. Operator's statement, recorded here as the source for this finding: **the server-list update works through another VPN app but not through this app's own tunnel** — which is exactly the signature of an unbound client following the OS default. Evidence: SOURCE CODE (client construction) + PHYSICAL DEVICE (operator observation).

4. **FIX-2 — PHYSICAL and TUNNEL are explicit paths; unbound is no longer a primary.** `CollectorPath` is now `PHYSICAL` / `TUNNEL` / `EMERGENCY`; `OPEN` and `VPN` no longer exist (`HttpFetcher.kt:354–363`). `PHYSICAL` is built by the new `boundToPhysicalNetwork(network)` (`HttpFetcher.kt:176–186`) over `GlobalVpnTracker.currentPhysicalNetwork()` (`GlobalVpnTracker.kt:538–561`) — a NOT_VPN network carrying `NET_CAPABILITY_INTERNET` **and** `NET_CAPABILITY_VALIDATED`, preferring Wi-Fi then cellular. `TUNNEL` is `boundToNetwork(currentVpnNetwork())` (`HttpFetcher.kt:155`). Both bind **per request** through `Network.getSocketFactory()`; `bindProcessToNetwork()` is still never called (`SERVER_SOURCE_ARCHITECTURE.md` §7 Q2). `EMERGENCY` carries no network. `CollectorRouting.choose` keeps its three-way AND and never returns `EMERGENCY` (`HttpFetcher.kt:384–398`); the repository picks the fetchers per rung (`VpnServerRepository.kt:193–201`, `:205–213`). Evidence: SOURCE CODE + TEST (T-NEW-58..60, T-NEW-55..57).

5. **P3 — the `EPERM` mechanism is UNKNOWN.** `path=vpn` failed on device with `Binding socket to network N failed: EPERM (Operation not permitted)`. Three hypotheses remain open and none is confirmed or excluded: **MIUI per-app network policy**, **the app excluding itself from its own tunnel** (`addDisallowedApplication` is gated on `config.excludedApps` at `SoftEtherVpnService.kt:1398–1404`, which is not reachable from `GlobalVpnTracker` — so the routing layer cannot see its own exclusion state), and **a stale VPN `Network` handle**. FIX-3 therefore adds **diagnostics only**, no fix: the `[bind-physical] ok network=…` / `[bind-physical] FAILED network=… errno=…` pair (`HttpFetcher.kt:176–186`), a read-only `SelfExclusion` tri-state with `isSelfExcludedFromOwnTunnel()` (`GlobalVpnTracker.kt:564`, `:813` — returns `UNKNOWN` in this build because the exclusion list lives in the frozen service), and a per-rung failure line `[ladder] <PATH> failed (no result) network=<handle> selfExcluded=<tri-state>`. **No fix was attempted**: the correct fix depends on which hypothesis the diagnostics reveal on the next device run. Evidence: SOURCE CODE + PHYSICAL DEVICE (the `EPERM` line itself); mechanism UNKNOWN.

6. **FIX-4 — the automatic ladder, no opt-in required.** `VpnServerRepository.routeLadder(input)` (`VpnServerRepository.kt:167–178`) replaces the v1.0.27/v1.0.41 single-primary model: `PHYSICAL` is always the base rung, `TUNNEL` is inserted only when the tunnel is usable **and** a VPN network handle exists (the same three-way gate as v1.0.41, so reachability is unchanged), `EMERGENCY` is always last, and the opt-in **reorders** the ladder (`TUNNEL` to the front) rather than gating it. `resolvePrimary()` is now a thin `routeLadder(input).first()` (`:180–181`). The ladder is computed per refresh (`:290`) and `fetchOnChosenPath` falls back to the opposite main rung only when it is reachable; a `TUNNEL` primary with no bound fetcher degrades straight to `PHYSICAL` and says so (`path=physical (tunnel rung unavailable)`). **DB preservation is explicit**: when every rung fails the code logs `[ladder] all rungs failed; preserving DB` (`:379`) and returns the last-known-good snapshot — nothing truncates or clears the store. `EMERGENCY` is currently skipped with `[ladder] skip=EMERGENCY (none configured)` because `hasEmergencySources()` is a stub returning `false` — the ladder does **not** consume the emergency sources that already exist elsewhere in the pipeline (`ServerListUpdates` uses `EmergencySourceFetcher`, and a background publisher refreshes `emergency-sources/servers.csv` / `emergency_defaults.xml`). The rung is modelled in the ladder but has no source behind it **in `VpnServerRepository`**. Evidence: SOURCE CODE + TEST (T-NEW-55..57, T-NEW-61).

7. **FIX-5 — bounded budgets.** `refresh()` runs its body under a `Mutex` (`refreshMutex.withLock`), so a second caller waits instead of starting a parallel collection, and the whole body is bounded by one `withTimeoutOrNull(MAIN_TIMEOUT_MS)` (45 s) (`VpnServerRepository.kt:260–277`). A `completed` flag distinguishes a genuine `null` result from a timeout, and the timeout path logs `[ladder] deadline exceeded (45000ms); preserving DB` (`:270`). There is **no retry loop** — the ladder is a single pass, not a backoff sequence — and `CancellationException` is not caught anywhere in the new code, so it propagates unchanged. Evidence: SOURCE CODE + TEST (T-NEW-63).

8. **Tests — T-NEW-54..63 (21 new JVM tests).** T-NEW-54a..d extend `DataUtilPrefersVpnTest` (two-store write, two-store clear, unchanged getter, no-context safety). T-NEW-55..57 are a new `VpnServerRepositoryLadderTest` (ladder order for both opt-in states, plus four invariants across all eight input combinations). T-NEW-58..60 are a new `HttpFetcherPhysicalBindTest` (helper shape, the `[bind-physical]` log pair, the three-value enum, no drift from `boundToSocketFactory`). T-NEW-61..63 extend `VpnServerRepositoryRoutingDynamicTest` (`[ladder] order=` precedes every attempt, `resolvePrimary` is a projection of the ladder, single-flight + deadline + no retry loop). **Four existing test files (~31 tests) were rewritten** to the new model under a **second explicit operator authorization** (`الف`), because they pinned the retired `OPEN`/`VPN` labels with exact-string assertions (`[fetch] path=open source=html`, `path=open failed (no result); no fallback (tunnel down)`, `[collector] path=fallback-to-vpn`, `CollectorPath.VPN -> boundFetcher != null`, `resolvePrimary(...) == CollectorPath.VPN`, `openCount == 7`); the Phase A STOP rule forbade editing them without that second authorization. No test was deleted, and every pinned behaviour was preserved under its new name. Evidence: TEST.

9. **JVM counts.** `:app` **887 / 0 / 0 / 1** (was 866 / 0 / 0 / 1; +21 = the tests in bullet 8). `:SoftEtherClient` **173 / 0 / 0 / 0** (unchanged; that module was not touched). Evidence: TEST.

10. **APK.** `:app:assembleProDebug` SUCCESSFUL under `CI=true`; `app-pro-debug.apk` 66,071,781 bytes, SHA-256 `3f0009afb0c1a60eca49435c2156a09f41d7de1b468154cd047f6d12310c4963`. `versionCode 144` / `versionName 2.4.11` are unchanged — no build or manifest file was touched. Evidence: TEST.

11. **Device verification is PENDING — UNKNOWN.** The three checks that would close this phase are: (a) **PHYSICAL alone** — tunnel down, toggle off, confirm the collector fetch leaves over the bound Wi-Fi/cellular network and logs `path=physical` with a non-`none` `network=` handle; (b) **TUNNEL alone** — toggle on with a usable tunnel, confirm `path=tunnel` is primary (the T6b retry, now expected to pass because of FIX-1) and capture the `[bind-physical]`/`EPERM` evidence for P3; (c) **ladder end-to-end with the physical path blocked by the ISP and the tunnel up** — confirm the update completes through the app's own tunnel with **no user action** and the server list intact (no wipe, no manual toggle). This phase ran no device, so every claim above is SOURCE CODE + TEST. Evidence: UNKNOWN.

12. **What remains.** (a) **Per-source path memory** — the spec's §11.8 "retain useful source/path outcome information" is still **phase 2**; the ladder is stateless per refresh. (b) **Retention phases 2–3** (the §9.3 retention-priority prune and the max-server cap) are still NOT IMPLEMENTED. (c) **Emergency source wiring into the ladder** — publication and consumption both already exist outside the collector (a background publisher refreshes `emergency-sources/servers.csv` and `emergency_defaults.xml`, and `ServerListUpdates` consumes sources through `EmergencySourceFetcher`), but `VpnServerRepository.hasEmergencySources()` is a stub returning `false`, so the ladder's `EMERGENCY` rung is inert by construction and logs the skip. Wiring it is **NOT IMPLEMENTED** and was out of this block's scope. (d) **App self-exclusion confirmation** — STEP 2b found the exclusion list unreachable from `GlobalVpnTracker` (`addDisallowedApplication` sits in the frozen `SoftEtherVpnService`), so `SelfExclusion` reports `UNKNOWN`; a real answer needs that module boundary to be authorised. (e) **P3's root cause** stays UNKNOWN until bullet 11(b) produces a log. Evidence: SOURCE CODE + UNKNOWN.

13. **No new methodology lessons.** This phase produced no §22 entry; the build/verification lessons already recorded there covered everything encountered, including the sandbox-blocked Gradle bind that made `--no-daemon` necessary. §22 is byte-identical (verified by SHA-256 before and after the edits). Evidence: SOURCE CODE (diff) + TEST (hash).

#### 1.6.39 EMERGENCY-DEFAULT-READER + TIMEOUT-RANGE (v1.0.46)

Two independent operator requirements, shipped together under one version bump. Neither touches the collector ladder or the Auto Mode controller.

1. **CHANGE A — the resource nobody read.** `emergency_csv_publisher.py` has been writing the published emergency CSV's raw-GitHub URL into `app/src/main/res/values/emergency_defaults.xml` as `R.string.default_emergency_source_url` (`emergency_csv_publisher.py:710`, `:873`; the resource itself is `emergency_defaults.xml:3`). Before this revision **no Kotlin code referenced that string** — a repository-wide search found no reader — so a fresh install started with an empty `emergency_source_config` table, and `ServerListUpdates.emergencyDataset()` (`ServerListUpdates.kt:146–160`), which asks `dao.listEnabled()`, had nothing to fetch. The emergency path was therefore dead by construction on every new install, no matter how fresh the published CSV was. This revision seeds the table from that resource on first startup, once, via the new `App.seedEmergencyDefaultIfNeeded(...)` (`App.kt:224–272`) invoked from `onCreate` on a private IO scope (`App.kt:92–97`). Evidence: SOURCE CODE + TEST.

2. **The seed is once-only, non-destructive, idempotent and non-fatal.** (a) **Once-only** — guarded by the new `SETTING_EMERGENCY_DEFAULT_SEEDED` flag (`DataUtil.kt:657–658`, accessors `:401–406`); a user who deletes the seeded row has made an explicit choice, so an empty table *after* the flag is set is **not** re-seeded. (b) **Non-destructive** — the seed reads the whole table through `dao.observeAll().first()` and returns early if **any** row exists, enabled or disabled; a user's own (even disabled) source is never overwritten or duplicated. (c) **Idempotent** — a second call with the flag set is a no-op, so a duplicate row cannot be created. (d) **Non-fatal** — the whole body is wrapped in `try/catch`, and a failure leaves the flag **unset** so the next start retries; `onCreate` additionally wraps the call in `runCatching`, so a seed failure can never prevent the app from starting. The URL is validated with the existing `SourceUrl.parse(url, SourceKind.EMERGENCY_CSV)` before insertion — the same parser and kind `EmergencySourceFetcher` uses (`EmergencySourceFetcher.kt:53`) — so an invalid or blank resource seeds nothing and does **not** burn the flag. Evidence: SOURCE CODE + TEST (T-NEW-67..69).

3. **CHANGE B — the timeout floor moves 5 → 8 s.** The Auto Mode per-attempt timeout is clamped in **both** directions: `DataUtil.getAutoModeTimeoutSeconds()` (`DataUtil.kt:386–387`) and `DataUtil.setAutoModeTimeoutSeconds()` (`:389–391`) now use `coerceIn(8, 60)`. The **default (12 s)** and the **maximum (60 s)** are unchanged — only the floor moved. Clamping on *read* is what makes this safe for an existing install: a value of 5, 6 or 7 written by an older build reads back as 8 with no database or preference migration. The Settings picker now offers `(8..60)` (`SettingsScreen.kt:1040`); its `indexOf` lookup still resolves, because the value it receives already came through the read clamp. Evidence: SOURCE CODE + TEST (T-NEW-70..72).

4. **Tests — T-NEW-67..72, 13 cases.** `EmergencyDefaultSeedTest` (8 cases): T-NEW-67 fresh install seeds exactly one row with the resource URL, label `Default`, enabled, and the flag set; T-NEW-68 an existing user row blocks the seed, is never overwritten, and the flag is still set; T-NEW-68b a **disabled** user row blocks it too; T-NEW-69 flag set + empty table (user deleted the row) is a no-op; plus guards for a blank URL, an invalid URL, a DAO failure, and double invocation. `DataUtilTimeoutRangeTest` (5 cases): T-NEW-70 default is 12; T-NEW-71 a 5 s write reads back as 8; T-NEW-71b a 7 s value stored by an older build reads back as 8; T-NEW-72 a 100 s write clamps to 60 and an 8 s write stays 8; T-NEW-72b in-range values round-trip untouched. The seeder is driven directly — its DAO collaborator is an **interface**, so a hand-written fake replaces Room, which needs an Android `Context` this JUnit-4-only module does not have. Evidence: TEST.

5. **JVM counts.** `:app` **900 / 0 / 0 / 1** (was 887 / 0 / 0 / 1; +13 = the cases in bullet 4). `:SoftEtherClient` **173 / 0 / 0 / 0** (unchanged; that module was not touched). Evidence: TEST.

6. **APK.** `:app:assembleProDebug` SUCCESSFUL under `CI=true` (full clean rebuild, `--rerun-tasks --no-build-cache`); `app-pro-debug.apk` 63,779,506 bytes, SHA-256 `88b4a4fe326faad4fbe716fe22ffa3bba9f20cd428ab05346d8c8936903b6591`. `aapt2 dump badging` confirms `versionCode='144' versionName='2.4.11-dev'` and all four ABIs (28 native libs); `aapt2 dump resources` confirms `default_emergency_source_url` is **packaged** with the published URL, so `getString()` returns it at runtime rather than throwing. Evidence: TEST.

7. **Device verification is PENDING — UNKNOWN.** The check that closes CHANGE A is a fresh install (no prior `emergency_source_config` rows, flag unset): after first launch the table must hold exactly one row carrying the published URL with label `Default`, `SETTING_EMERGENCY_DEFAULT_SEEDED` must be `true`, and a second launch must add nothing. The check that closes CHANGE B is that Settings offers 8..60 with 12 selected, and that a device carrying a pre-v1.0.46 value of 5 shows 8. No device ran in this phase. Evidence: UNKNOWN.

8. **The `EMERGENCY` rung in the collector is still inert.** This phase seeds the **config row**, not the ladder: `VpnServerRepository.hasEmergencySources()` is still a stub returning `false`, so the ladder logs `[ladder] skip=EMERGENCY (none configured)` and never reaches the seeded source (spec §1.6.38 bullet 6). The seeded row *is* consumed by the update pipeline (`ServerListUpdates.emergencyDataset()`), which is a different path. Wiring the ladder is a separate future phase and was explicitly out of scope here. Evidence: SOURCE CODE.

9. **No new methodology lessons.** Nothing in this phase produced a §22 entry. §22 is byte-identical (SHA-256 `adf12858…020e`, 6,450 bytes, verified before and after). Evidence: SOURCE CODE (diff) + TEST (hash).

#### 1.6.40 REMOVE-SELF-EXCLUSION (v1.0.47)

Closes the root cause the v1.0.46 device run could name but not fix. One defect, two application points, one version bump.

1. **The app was excluded from its own tunnel.** `App.initializeDefaultExcludedApps()` inserts a hidden row — `ExcludedApp(packageName = packageName, appName = "Self")`, commented *"Always exclude self — hidden from user, cannot be removed"* (`App.kt:135–149`) — into the `excluded_app` table. Because that row is in the table, it reaches **both** `VpnService.Builder` sites that apply exclusions: the SoftEther path through `config.excludedApps` (`SoftEtherVpnService.buildTun`, `:1398`) and the MS-SSTP path through the `ROUTE_EXCLUDED_APPS` preference, which `StatusViewModel.connectSSTPVPN` (`:1018–1029`) and `DetailActivity` (`:845–858`) write from the same table (`IPTerminal.addExcludedAppRules`, `:148`). The exclusion is therefore applied **by default**, not by user choice — the Settings screen filters the app out of the visible list, and the device reported *"Excluding 1 app(s)"* while the underlying set held two entries. Evidence: SOURCE CODE + PHYSICAL DEVICE (`DEVICE_VERIFY_v1.0.46_REPORT.md` §7.4).

2. **Consequence — the `TUNNEL` rung could never deliver data.** With the app disallowed from its own tunnel, every socket the collector bound to the VPN network failed with `Request failed: [exception=SocketException] Binding socket to network 109 failed: EPERM (Operation not permitted)` on two independent samples (12:21:37 and 12:22:08), while the `PHYSICAL` rung succeeded (`[bind-physical] ok network=104`). The ladder and the fallback mechanics were therefore correct and working; the tunnel **transport** was the only missing piece. In a network where the physical path is blocked — `PHYSICAL` blocked + `TUNNEL` EPERM + `EMERGENCY` a stub — the list could never update, no matter how fresh the published `servers.csv` was. Evidence: PHYSICAL DEVICE.

3. **Fix — skip the app's own package at both application points.** `SoftEtherVpnService.buildTun` captures `val selfPackage = packageName` (where it still resolves to `Context.packageName`, before the lambda parameter shadows it) and applies `config.excludedApps.filterNot { it == selfPackage }`; `IPTerminal.addExcludedAppRules` applies `bridge.excludedApps.filterNot { it == bridge.service.packageName }`. **User-added exclusions are applied unchanged** — the device's `com.google.android.projection.gearhead` entry still reaches the builder. Per-request binding from v1.0.45 (`productionBoundFetcher` for `PHYSICAL`, `productionTunnelFetcher` for `TUNNEL`) already isolates management traffic, so removing the global self-exclusion does **not** leak unrelated traffic into the tunnel. Scope note: the block's brief assumed a hardcoded `builder.addDisallowedApplication(packageName)` line; the source had no such line — the exclusion arrives through the list — so the fix is a **filter**, not a deletion, and the MS-SSTP point (the path the device actually exercised, and the one that produced the EPERM) had to be added to scope under an explicit operator authorization. Evidence: SOURCE CODE.

4. **Tests — T-NEW-73 a–d, 4 cases.** `SelfExclusionRemovedTest` (source-shape, JUnit 4, no Robolectric — `VpnService.Builder` needs a real `VpnService` and `PackageManager`): (a) `SoftEtherVpnService.kt` does **not** contain `addDisallowedApplication(packageName)`, and does contain `val selfPackage = packageName` plus `filterNot { it == selfPackage }`; (b) the user's `config.excludedApps` is still iterated, non-self packages still reach the builder, and `NameNotFoundException` is still tolerated; (c) `IPTerminal.kt` uses `bridge.service.packageName` with the same filter and still calls `bridge.builder.addDisallowedApplication(it)` for the rest; (d) both call sites carry the `v1.0.47` / `§1.6.40` provenance marker. Evidence: TEST.

5. **JVM counts.** `:app` **904 / 0 / 0 / 1** (was 900 / 0 / 0 / 1; +4 = the cases in bullet 4). `:SoftEtherClient` **173 / 0 / 0 / 0** (unchanged). Evidence: TEST.

6. **APK.** `:app:assembleProDebug` SUCCESSFUL under `CI=true` (`--rerun-tasks --no-build-cache`); `app-pro-debug.apk` 63,780,018 bytes, SHA-256 `0ef89bfefe8e1d7aae58ddde101bcda4642eef4b238eb9d1a91690e04df90583`; `aapt2 dump badging` confirms `versionCode='144' versionName='2.4.11-dev'` (**unchanged** — no build or manifest file was touched). Evidence: TEST.

7. **Device verification is PENDING — UNKNOWN.** The check that closes this phase: with a tunnel up and the collector's opt-in ON, the ladder must log `path=tunnel` as the primary **and** the update must succeed on it (`path=tunnel succeeded`), with **no** `EPERM` in the log. Both protocols must be exercised, because the two application points are independent: SoftEther SSL-VPN (always affected) and MS-SSTP via a **manual** connect (affected). MS-SSTP via **Auto Mode** was never affected — `AndroidConnectionAdapter.connectSstp` (`:792`) writes `ROUTE_EXCLUDED_APPS = emptySet()`, so that path already had no self-exclusion; that asymmetry is why the defect could hide. No device ran in this phase. Evidence: UNKNOWN.

8. **No new methodology lessons.** Nothing in this phase produced a §22 entry. §22 is byte-identical (SHA-256 `adf12858…020e`, 6,450 bytes, verified before and after). Evidence: SOURCE CODE (diff) + TEST (hash).

#### 1.6.41 NATIVE-SLOT-TEARDOWN-RECOVERY (v1.0.48)

Closes the T5-SLOT-TEARDOWN residual (§12.5.1 row 9), which had been open since v1.0.10 and was the last defect in the v1.0.30–v1.0.47 chain that a user could still hit. One root cause, three layers of fix, one version bump.

1. **The residual was still live in v1.0.47.** The device run behind `DEVICE_VERIFY_TUNNEL_TRANSPORT_v1.0.47_REPORT.md` §8.4 reproduced it verbatim: after a failed SoftEther connect the next start was refused with `Start refused: native slot still held by an unresolved attempt (attempt=1 phase=TEARDOWN, reporting deadline expired (still blocked))`, and the only recovery was `am force-stop`. The refusal is fail-closed and correct in principle — the slot is genuinely unresolved — but it had no exit. Evidence: PHYSICAL DEVICE (logcat).

2. **Why the existing soft-bound never fired (root cause).** `NativeLifetimeRegistry` already declared `NATIVE_SLOT_FORCE_RELEASE_AFTER_MS = 90_000` and `forceReleaseIfTimedOutLocked()` already applied it, but the bound measured `nowMs() - teardownRequestedAtMs`, and `markTeardownRequested` **re-stamped `teardownRequestedAtMs` on every call** (`NativeLifetimeRegistry.kt:287` before this revision). `destroyResources()` calls it unconditionally at its top (`ConnectionController.kt:919`) and is documented as idempotent — it is reached from `SoftEtherVpnService.onDestroy` (`:509`), the cancel path (`:929`, whose comment reads *"Idempotent — safe even if the controller already tore itself down"*) and `stopVpn`. Worse, Auto Mode calls `adapter.disconnect()` on **every** `NATIVE_BUSY` refusal (`AutoModeController.kt:1941`), so the retry loop that was trying to recover re-entered `destroyResources()` and restarted the 90 s clock each time. The bound was therefore **unreachable by construction**: every retry pushed the release further away. This also explains §12.5.1 row 9's own open question ("confirm whether the 90 s TEARDOWN bound is reached before Auto Mode stops") — it never could be. Evidence: SOURCE CODE.

3. **FIX-1 — the timer now measures what it claims.** `markTeardownRequested` stamps only the **first** teardown request for an attempt (`if (it.teardownRequestedAtMs == 0L) it.teardownRequestedAtMs = nowMs()`, `NativeLifetimeRegistry.kt:306`), so `teardownRequestedAtMs` means "when this attempt entered TEARDOWN" rather than "when the most recent teardown request arrived". The bound, its value (90 s), the phase check, the `deadlineExpired` precondition and the `FORCED_RELEASE_AFTER_TIMEOUT` label are all **unchanged**. Evidence: SOURCE CODE.

4. **FIX-2 — the service has an attributed recovery path.** `SoftEtherVpnService.acquireNativeSlotOrRecover()` (`:632`) replaces the bare `tryAcquire()` at the claim site (`:733`). On refusal it reads `NativeLifetimeRegistry.teardownHeldMs()` (`:407`, new, observational only), asks the registry's own public entry point `forceReleaseStaleTeardown()` (`:433`, new) for a release, and retries `tryAcquire()` once. The entry point delegates to the **same** `forceReleaseIfTimedOutLocked()` (`:453`) and therefore applies exactly the registry's preconditions — it cannot release a holder that `tryAcquire()` would have kept, and it is the only release it can perform. Because `tryAcquire()` already runs that check, the re-acquire branch only fires when the bound is crossed between the two calls; its real value is the **diagnosis**: the refusal now logs `heldFor=${heldMs}ms` alongside the phase (`:746`), and the `notifyNativeBusy` reason carries the same age (`:761`). Before this, a device log could not distinguish "4 s from recovery" from "will never recover". Evidence: SOURCE CODE.

5. **The fail-closed guarantee is preserved, not weakened.** A genuinely stuck operation still blocks a new attempt for the configured bound; the release fires **only after** it. This is pinned by tests, not asserted in prose: T-NEW-75 shows a holder one millisecond inside the window is still refused *and* that the new public entry point refuses too; T-NEW-77 shows the attempt-id guard still prevents the stale timer from clearing a slot a newer attempt owns. `NATIVE_SLOT_FORCE_RELEASE_AFTER_MS` (90 s) and `NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS` (30 s) were **not** changed — no evidence showed either cap to be wrong. Evidence: SOURCE CODE + TEST.

6. **FIX-3 — four tests pin both halves.** `NativeSlotRecoveryTest` (JUnit 4, injected clock, no sleeps): **T-NEW-74** a confirmed teardown still releases immediately, with no `FORCED_RELEASE` line and no dependence on the clock (no false positive on the happy path); **T-NEW-75** a holder inside its window keeps the slot refused and the public entry point respects the same bound; **T-NEW-76** the device retry loop — four refused retries, each followed by a fresh `markTeardownRequested` — cannot restart the clock, and the bound fires 90 s after the **first** request; **T-NEW-77** once a replacement attempt owns the slot, the obsolete lease's late teardown is a no-op and `forceReleaseStaleTeardown()` refuses to release the new owner even three bounds later. Evidence: TEST.

7. **JVM counts.** `:SoftEtherClient` **177 / 0 / 0 / 0** (was 173 / 0 / 0 / 0; +4 = the cases in bullet 6). `:app` **904 / 0 / 0 / 1** (unchanged). Note the block brief predicted `:app` 908; the authorised test path is `SoftEtherClient/src/test/...`, so the +4 lands in that module and `:app` is untouched — no test was added to, moved into, or removed from `:app`. Evidence: TEST.

8. **APK.** `:app:assembleProDebug` SUCCESSFUL under `CI=true` (`--rerun-tasks --no-build-cache`); `app-pro-debug.apk` 63,780,914 bytes, SHA-256 `8f7ed99a201488655ab84946908fc74a58df7d32d0c0f813f9a60be60ea494a3`; `aapt2 dump badging` confirms `versionCode='144' versionName='2.4.11-dev'` (**unchanged** — no build or manifest file was touched). Evidence: TEST.

9. **Device verification is PENDING — UNKNOWN.** The closing check is the exact deadlock the v1.0.47 run produced: force a failed connect (or let one die), then retry, and confirm that (a) the refusal line now carries `heldFor=<N>ms`, and (b) once `N` passes the 90 s bound the next attempt is admitted and logs `FORCED_RELEASE_AFTER_TIMEOUT` / `[REGISTRY-RECOVERY] reclaimed the native slot`. No device ran in this phase. Evidence: UNKNOWN.

10. **`AutoModeController.kt` was deliberately not modified.** The brief authorised only the `ERROR_NATIVE_UNRESOLVED` / give-up path. That path is already unreferenced — `giveUpWithUnresolvedNativeOperation()` is retained but uncalled, and the `NATIVE_BUSY` branches log `no terminal, per §6.8.4` — so it was retired on purpose by the v1.0.37 no-terminal contract. Re-introducing a terminal there to surface the recovery would have violated that contract and was outside the brief's own FROZEN list. The recovery therefore lives entirely below Auto Mode, where it helps every caller. Evidence: SOURCE CODE.

11. **§12.5.1 row 9 is annotated, not deleted.** The row is additive-only; its historical observations (v1.0.10 ~1 min, v1.0.11 self-healed 7/7) remain true and remain in place. Evidence: SOURCE CODE (diff).

12. **No new methodology lessons.** Nothing in this phase produced a §22 entry. §22 is byte-identical (SHA-256 `adf12858…020e`, 99 lines, verified before and after). Evidence: SOURCE CODE (diff) + TEST (hash).

#### 1.6.42 EMERGENCY-LADDER-WIRING (v1.0.49)

Completes the operator's original server-source requirement — "if the physical path is blocked, update through the app's own tunnel; if the tunnel also fails, use servers.csv" — by making the `EMERGENCY` rung of the collector ladder actually attempt the existing v1.0.21 emergency fetch path. One defect class, three layers of fix, one version bump. (The rung was added as inert scaffolding in v1.0.45; v1.0.46 seeded a default source into `emergency_source_config` but the ladder still never asked for it.)

1. **The rung was inert for two independent reasons.** (a) `VpnServerRepository.hasEmergencySources()` was a hardcoded stub returning `false` (`VpnServerRepository.kt:448` before this revision), so `refreshLocked()` logged `[ladder] skip=EMERGENCY (none configured)` on every refresh even when the v1.0.46 startup seed had written an enabled default row. (b) Even had the stub returned true, the rung could never be reached by `refreshLocked()`: on primary failure it returned `loadSnapshot()` (a non-null last-known-good), so `ServerListUpdater.update()` — whose `emergencyFetch` rung (the only other place that reached `emergencyDataset()`) triggers only when the primary fetch returns `null` — almost never fell through to it. The snapshot intercepted the fallthrough. Evidence: SOURCE CODE + the v1.0.46/1.0.47 skip-log observation (§7.6, §8).

2. **FIX-1 — `hasEmergencySources()` now consults the DAO.** It is now a `suspend` function that calls `App.instance?.emergencySourceConfigDao?.listEnabled()?.isNotEmpty()` (`VpnServerRepository.kt:444`), guarded by `runCatching … getOrDefault(false)` so a missing `App`/DAO or a thrown query never makes a refresh throw. The surrounding `refreshLocked` is already `suspend`, so no `runBlocking` is needed. Evidence: SOURCE CODE.

3. **FIX-2 — `refreshLocked()` attempts the rung before the snapshot.** The empty-records branch (`VpnServerRepository.kt:368`) now, when `hasEmergencySources()` is true, calls the existing v1.0.21 fetch seam `ServerListUpdates.emergencyRecords()` and, if it returns records, folds them into the SAME `records` (mutable) list the PHYSICAL/TUNNEL sources populate — so the emergency data flows through the identical `merge → validate → score → map` pipeline and returns a normal `CollectResult`. If the rung yields nothing (or no source is configured) the original last-known-good snapshot fallback is preserved verbatim. Evidence: SOURCE CODE.

4. **No fetch logic duplicated — the v1.0.21 path is reused.** `ServerListUpdates.emergencyDataset()` (the legacy hook consumed by `ServerListUpdater.emergencyFetch`) now delegates to a new `internal suspend fun emergencyRecords(): List<VpnServerRecord>?` (`ServerListUpdates.kt:146`), which is the single owner of the `EmergencySourceFetcher` fetch (1 MiB / `MAX_BODY_CHARS` body cap, redirect-authority check) and the `EmergencyCsvParser` parse, plus the merge/validate/score. Both callers — the legacy hook and the new ladder rung — consume that one implementation. Evidence: SOURCE CODE (the file contains exactly one `EmergencySourceFetcher()` instantiation).

5. **`DatasetIntegrity` still gates the write — no privileged path.** The emergency records enter `records` before `VpnServerMerger.mergeRecords(records)` (`VpnServerRepository.kt:412`); the resulting `CollectResult` is returned to `ServerListUpdater.fetch`, whose single `store.replaceAll` write is judged by `DatasetIntegrity.evaluate` exactly as for any other source. The emergency path gains no write shortcut, so a degraded/regressing emergency dataset is still rejected and the stored list preserved. Evidence: SOURCE CODE.

6. **`routeLadder` and the v1.0.45 ladder order are unchanged.** `PHYSICAL -> TUNNEL -> EMERGENCY` (EMERGENCY always last, never promoted) is untouched; only the rung's *behaviour* changed, not its place in the ladder or the v1.0.45 transport contract. Evidence: SOURCE CODE (diff) + TEST (`VpnServerRepositoryLadderTest` T-NEW-55..57 still pass).

7. **Tests: T-NEW-78..80** (`EmergencyLadderWiringTest`, JUnit 4, source-shape — `refreshLocked` and `emergencyRecords` need a fully wired repository / a real network fetch and cannot be driven on the plain-JVM unit harness). **T-NEW-78** pins that with no enabled source the "none configured" skip is preserved and the fetch is not attempted; **T-NEW-79** pins that with a source the branch reaches `ServerListUpdates.emergencyRecords()` and reports `[ladder] path=emergency`; **T-NEW-80** pins that the emergency records are added to the shared `collected` list (so `DatasetIntegrity` still applies) and that `EmergencySourceFetcher` is instantiated exactly once (no second copy). Evidence: TEST.

8. **JVM counts.** `:app` **907 / 0 / 0 / 1** (baseline 904 / 0 / 0 / 1; **+3** = T-NEW-78..80 in `EmergencyLadderWiringTest`). `:SoftEtherClient` **177 / 0 / 0 / 0** (unchanged). The authorised test path is `app/src/test`, so the +3 lands in `:app`; no test was added to, moved into, or removed from `:SoftEtherClient`. Evidence: TEST.

9. **APK.** `:app:assembleProDebug` SUCCESSFUL under `CI=true` (`--rerun-tasks --no-build-cache`); `app-pro-debug.apk` 63,782,158 bytes, SHA-256 `194255e41cc6ad18f60debb889526a49cfd8e129eab6d5dd367b14544a07b657`; `aapt2 dump badging` confirms `versionCode='144' versionName='2.4.11-dev'` (**unchanged** — no build or manifest file was touched). Evidence: TEST.

10. **Device verification is PENDING — UNKNOWN.** The closing check is the operator's scenario: with PHYSICAL and TUNNEL both failing, the ladder must fall through to EMERGENCY and succeed against the seeded `servers.csv` (`emergency-sources/servers.csv`, 3 rows / 29 columns, `schema_version="1.0"`), producing a non-empty stored list; and `DatasetIntegrity` must still reject a regressing emergency dataset. No device ran in this phase. Evidence: UNKNOWN.

11. **`ServerListUpdater.kt` and `AutoModeController.kt` were left untouched** (the brief authorised `ServerListUpdater.kt` only "if the emergency fetch must be called from a new site" — it did not; `emergencyFetch` still calls `emergencyDataset()` unchanged). `EmergencySourceFetcher.kt` and `EmergencyCsvParser.kt` were REUSED, not modified (v1.0.21 FROZEN). The 1 MiB cap and authority check are therefore preserved. Evidence: SOURCE CODE (diff).

12. **No new methodology lessons.** Nothing in this phase produced a §22 entry. §22 is byte-identical (SHA-256 `adf12858…020e`, 99 lines, verified before and after). Evidence: SOURCE CODE (diff) + TEST (hash).

#### 1.6.43 ADDITIONAL-CONNECT-PROTECT (v1.0.51)

Fixes the tunnel-death defect recorded as ISSUE-1 in the v1.0.47 device run: SoftEther SSL-VPN-over-TCP tunnels died 10–20 s after establishment while `additional_connect` was running, with the traffic counters frozen (`sent=958 / received=60`), a "Keepalive missed" teardown, and the `additional_connect` response arriving 16.4 s late. Since v1.0.47 removed the app's self-exclusion (§1.6.40), the app's own sockets traverse tun0, so a socket that misses `protect()` is routed into the tunnel it is trying to build.

1. **Root cause — confirmed, not assumed.** `softether_additional_connect()` (`softether_protocol.c:4007`) creates its TCP socket at `:4029` (`socket_create(SOCKET_TYPE_TCP)`) and dials it at `:4036` (`socket_connect_timeout`) with **no protect call of any kind in between**. The function only runs once `state == STATE_CONNECTED` (`:4009`), i.e. after tun0 exists, and it is invoked from the background pthread launched by `softether_maintain_links()` (`:3451`, routine at `:3980`) — not from a Kotlin-managed call that could protect first. Evidence: SOURCE CODE.

2. **Why the existing protection never covered it.** `ConnectionController.protectAdditionalSockets()` (`ConnectionController.kt:1753`) is the only code that protects additional fds, and it runs from the receive loop every 50 iterations (`:1175`). It therefore sees an fd only *after* `softether_additional_connect()` has returned — that is, after the connect either succeeded or already blocked on a socket routed through tun0. The window it leaves open is exactly the one that kills the session. Evidence: SOURCE CODE + the v1.0.47 device log (PRIOR REPORT).

3. **No native→Kotlin protect hook existed.** `softether_jni.c` stored no `JavaVM` (`JNI_OnLoad` ignored its `vm` argument) and exposed no upcall; the only fd accessors were `nativeGetAllSocketFds` / `nativeGetSocketFd` / `nativeGetRudpSocketFd` / `nativeGetNatTUdpSocketFd`, all of which are *queries* the host calls after the fact. A native protect hook had to be added. Evidence: SOURCE CODE.

4. **FIX-1 — per-connection hook in the core.** `softether_connection_t` now carries `int (*protect_socket_fn)(void* ctx, int fd)` and `void* protect_socket_ctx` (`softether_protocol.h:243`), set through `softether_set_socket_protector()` / cleared by `softether_clear_socket_protector()`. The fields are zeroed by the existing `calloc` in `softether_create()`, so the default is "no hook" and nothing changes for callers that never register one. Evidence: SOURCE CODE.

5. **FIX-2 — protect strictly between create and connect.** `softether_additional_connect()` now calls the hook immediately after `socket_create()` and before `socket_connect_timeout()` (`softether_protocol.c:4040`). This is the whole fix: the fd is bound to the host's underlying network before the kernel picks a route for it. Evidence: SOURCE CODE + TEST (T-PROTECT-01 pins the ordering).

6. **Fail closed, with a defined escape hatch for non-VPN hosts.** If a hook is registered and it fails, the attempt logs `additional_connect: socket protect failed (fd=…)` , destroys the socket, increments `additional_failed_count` and returns `-1` **without ever dialling it** — an unprotected additional socket is never connected while a protector is armed (T-PROTECT-02). When no hook is registered the call logs `no socket protector registered … connecting unprotected` and proceeds, which keeps the native test harness and any non-VPN host building and running; the primary connection is unaffected in both cases because it is protected by Kotlin before tun0 is installed (`ConnectionController.kt:725`). Evidence: SOURCE CODE + TEST.

7. **FIX-3 — JNI upcall with correct attach/detach.** `nativeSetSocketProtector(handle, service)` takes a `NewGlobalRef` on the host service and arms the hook via `softether_set_socket_protector(conn, jni_protect_socket_cb, p)`. The callback resolves `JNIEnv` with `GetEnv`; because the additional-connect thread is a plain pthread that is **not** attached to the JVM, it calls `AttachCurrentThread` only when `JNI_EDETACHED` and `DetachCurrentThread` before returning (a leaked attachment would keep the thread alive across teardown). Exceptions are checked with `ExceptionCheck`, cleared, and treated as failure. The upcall takes a `NewLocalRef` under the protector's mutex so a concurrent `DeleteGlobalRef` cannot invalidate the object mid-call. Evidence: SOURCE CODE + TEST (T-PROTECT-04).

8. **Teardown cannot use-after-free or leak.** `nativeDestroy` clears the hook, then calls `softether_destroy()` — which joins the background additional-connect thread (`softether_protocol.c:1088`) — and only **after** that join releases the global ref and frees the protector struct (T-PROTECT-05). A `released` flag makes any late call fail closed instead of touching a cleared ref. `nativeDisconnect` deliberately keeps the protector armed, because `attemptReconnect()` reuses the handle. Evidence: SOURCE CODE + TEST.

9. **Kotlin arms the hook on both connect paths, and fails closed if it cannot.** `ConnectionController` calls `client.setSocketProtector(created, service)` immediately after `nativeCreate()` on the initial path (`:445`) and on the reconnect path (`:1353`), before any `nativeConnectWithHub`; a `false` result throws, which routes through the existing teardown. `SoftEtherClient.setSocketProtector` types the service as `Any?` so the client class stays free of Android framework types and remains JVM-testable. Evidence: SOURCE CODE + TEST (T-PROTECT-06).

10. **The safety net and the fd lifecycle are preserved.** The periodic `protectAdditionalSockets()` call and the two `protectedFds.clear()` teardown sites are unchanged (T-PROTECT-07), so the new pre-connect protection is additive: it closes the window without removing the post-hoc sweep that also covers NAT-T/RUDP fds. Evidence: SOURCE CODE + TEST.

11. **Tests: T-PROTECT-01..07** (`AdditionalConnectProtectTest`, JUnit 4, source-shape — the defect lives in C and in a JNI upcall that need an Android host and a live `VpnService`, neither of which a plain-JVM unit test can provide; the repo already uses this pattern where a module cannot be run in isolation). They pin: the protect call sits between create and connect (01); a failed protect aborts before dialling (02); the hook is per-connection and clearable (03); the upcall attaches/detaches and never leaks the service ref (04); `nativeDestroy` releases only after the thread join (05); the controller arms before every connect on both paths (06); the safety net and `protectedFds` reset survive (07). Evidence: TEST.

12. **JVM counts.** `:SoftEtherClient` **184 / 0 / 0 / 0** (baseline 177 / 0 / 0 / 0; **+7** = T-PROTECT-01..07). `:app` **907 / 0 / 0 / 1** (unchanged). No regression. Evidence: TEST.

13. **Build and APK.** `:app:assembleProDebug` SUCCESSFUL under `CI=true` (`--rerun-tasks --no-build-cache`, 114 tasks) — the native sources compiled for all four ABIs. APK 63,795,058 bytes, SHA-256 `36b4e4bb5b38bf0b9c449993d59d2f8d6f55166a02f2e42bfdbfcbd83ec6a521`, `versionCode='144' versionName='2.4.11-dev'` (**unchanged** — no build or manifest file was touched). `llvm-nm` on the built `arm64-v8a/libsoftether.so` shows both new symbols: `Java_vn_unlimit_softether_client_SoftEtherClient_nativeSetSocketProtector` and `softether_set_socket_protector`. Evidence: TEST.

14. **Out of scope, deliberately.** MS-SSTP, Auto Mode, the collector ladder and every timeout are untouched. `native_test.c` was **NOT RUN**: the CMake `softether_test` target is an Android shared library that must be loaded on a device/emulator (`System.loadLibrary("softether_test")`), so it cannot execute in this JVM-only environment — stated rather than silently skipped. Evidence: SOURCE CODE + UNKNOWN.

15. **Device verification is PENDING — UNKNOWN (V-VERIFY-3).** The closing checks are: a SoftEther TCP tunnel survives **> 20 s** with `additional_connect` active; the `received` stats counter climbs instead of freezing; other apps keep internet; and, with Developer Mode ON, the log shows the additional socket protected **before** connect (`additional_connect: socket fd=… protected before connect`). No device ran in this phase. Evidence: UNKNOWN.

16. **No new methodology lessons.** Nothing in this phase produced a §22 entry. §22 is byte-identical (SHA-256 `adf12858…020e`, 99 lines). Evidence: SOURCE CODE (diff) + TEST (hash).

#### 1.6.44 AUTO-ROUTE-AND-AUTOCONNECT-UX (v1.0.52)

Three operator-requested corrections in one device-free revision. TASK A removes a control the user should never have had to operate; TASK B fixes the Auto Connect screen and the controller's ownership model; TASK C is this record.

1. **TASK A — the manual collector-routing switch is removed, not deprecated.** Deleted in one pass: the Settings `SettingSwitchRow`, `DataUtil.getPrefersVpnForCollector()` / `setPrefersVpnForCollector()`, the `DataUtil` two-store seam (`defaultPreferences` field + the two-argument `internal constructor`, which had become write-only), `GlobalVpnTracker.prefersVpnForCollector()`, the `PREF_ALWAYS_PREFER_VPN` constant, the `preferVpn` component of `CollectorRoutingInput`, the `CollectorRouting` object, the `HttpFetcherR4bTest` routing half, and the two `setting_prefer_vpn_collector_*` string resources. `CollectorRoutingInput` is now exactly `(tunnelUsable, hasBoundNetwork)` — two **measurements**, no preference. Evidence: SOURCE CODE.

2. **A stale stored value is inert, and that is the migration.** Millions of installs carry `"vpn_always_prefer_vpn"` in SharedPreferences. Nothing reads it any more, so it is simply ignored; no migration code, no DB touch, no preference rewrite. T-REMOVED-05 pins this by showing that a store containing the old key produces byte-identical readings from every surviving `DataUtil` accessor. Evidence: TEST.

3. **The replacement is measurement, not a hidden default.** New `VpnServerRepository.PathMemory` records which transport last delivered a dataset and which one just failed. `routeLadder(input, memory)` keeps the v1.0.45 order — `PHYSICAL → TUNNEL (when `tunnelUsable && hasBoundNetwork`) → EMERGENCY` — and promotes `TUNNEL` to the front **only** when `memory.isCoolingDown(PHYSICAL)` or `memory.preferred() == TUNNEL`. No input value can promote it: T-NEW-56a pins that a usable bound tunnel still leaves PHYSICAL first. Evidence: SOURCE CODE + TEST.

4. **`PathMemory` is bounded by construction.** The key space is the three-value `CollectorPath` enum, so at most three success and three failure timestamps exist regardless of uptime; no eviction policy is needed because no unbounded key can be created (T-LADDER-06 hammers 10 000 iterations per path and still sees one preferred value). Entries are `(enum, epochMillis)` pairs — no credentials, hostnames, IPs, SSIDs or user ids; nothing is persisted and a process restart clears everything. The clock is injectable so TTL and cooldown are tested without sleeping. Success TTL `PATH_MEMORY_SUCCESS_TTL_MS = 30 min`, failure cooldown `PATH_MEMORY_FAILURE_COOLDOWN_MS = 2 min`. Evidence: SOURCE CODE + TEST.

5. **`refresh()` gained exactly one bounded retry.** Trigger: the run had **no usable tunnel at all** (so `TUNNEL` was never a rung) and produced nothing, and a tunnel has since become usable. The retry is spent once per episode via `tunnelRetryAvailable.compareAndSet(true, false)`, runs inside the existing `refreshMutex` (so it is single-flight against every other caller), is bounded by `TUNNEL_RETRY_TIMEOUT_MS = 20 s` (deliberately smaller than `MAIN_TIMEOUT_MS = 45 s`), and cannot loop because `tunnelRetryAvailable` is re-armed **only** by a run that *starts* with a usable tunnel — never by a failure. A retry that produces nothing logs `[ladder] one-shot retry produced nothing … preserving DB`. Evidence: SOURCE CODE + TEST (T-LADDER-20/21/22).

6. **TASK B.1 — one switch, one place.** The "Automatic Reconnection" `SettingSwitchRow` moved from `SettingsScreen` to `AutoModeScreen` (immediately below the server-info block). Same preference (`SETTING_AUTO_RECONNECT_IN_AUTO_MODE`, read/written through `DataUtil.getAutoReconnectInAutoMode()` / `setAutoReconnectInAutoMode()`), default still `true` (§11.13.2). Asserted from both sides so the row cannot end up in two screens (two switches, one value) or in neither (unreachable setting). Evidence: SOURCE CODE + TEST (T-REMOVED-04).

7. **TASK B.2 — the default per-attempt timeout is 8 s.** `DataUtil.getAutoModeTimeoutSeconds()` now defaults `SETTING_AUTO_MODE_TIMEOUT_SECONDS` to `8` (clamp unchanged at `coerceIn(8, 60)`), `AutoModeController.DEFAULT_ATTEMPT_TIMEOUT_MS` is `8_000L`, and the Settings picker's fallback index is `values.indexOf(8)`. The default is now the floor, so a fresh install and a legacy install holding 5–7 converge on the same figure with no migration. This supersedes the 12 s default established in v1.0.31 and left unchanged by v1.0.46 — the 12 s figures in §1.6.23, §1.6.31, §1.6.39 and their push-state notes are **historical records and are left as written**; the normative sites (§6.7 R5, §6.8.1, §6.8.7, §10.1) were updated. Evidence: SOURCE CODE + TEST (`DataUtilTimeoutRangeTest` T-NEW-70/70b/72, `AutoModeControllerTest#defaultAttemptTimeoutMatchesTheSettingsDefault`, `AutoModeNativeBusyTest`).

8. **TASK B.3(a) — Auto Connect has a single owner.** `AutoModeController` gained `private val transitionLock = Any()` plus a `startLocked()` seam. A plain monitor, not a `Mutex`, because `start()` and `stop()` are non-suspending and must stay so for their callers. Every `job` mutation is now inside `synchronized(transitionLock)`: `start()`, the whole of `stop()`, the `Error`-branch `skipToNextServer()`, and the `Connected`-branch skip — where the continuation coroutine is published **as the current job** and then swapped for `scope.launch { runAutoMode() }` while still holding the lock, so `job` goes from one live job straight to another and never passes through null. Before this revision the slot sat empty until the continuation called `start()`, leaving a window in which a second press launched its own run: two loops dialling the same list. Evidence: SOURCE CODE + TEST (`twoImmediateSkipsNeverDialTheSameServerTwice`).

9. **TASK B.3(b) — cancellation is cancellation.** `CancellationException` is now rethrown (never absorbed into the generic `catch (e: Exception)` that turns an attempt into a failed server) on the reachability probe and on both connect paths, so a user skip or a `stop()` cannot be mistaken for a protocol failure and silently advance the cursor. `adapter.disconnect()` on those paths runs under `withContext(NonCancellable)`, so teardown completes even though the enclosing scope is being cancelled — otherwise a cancelled run could leave a half-torn-down tunnel behind. Evidence: SOURCE CODE + TEST (`nativeBusyCancellationDuringRetryIsClean`, plus the existing stop-path tests).

10. **TASK B.3(c) — the Next-Server cursor uses one identity.** `rememberRemaining()` compared `it.ip == current.ip && it.hostname == current.hostname`, while the continuation and the wedge ledger both use `serverKey` = `"$ip|${hostname ?: ""}"`. Those disagree whenever a hostname is `null` on one side and `""` on the other (`null != ""`), so the connected server could not be found in its own list, the remaining tail fell back to the whole list, and Next Server re-dialled the server the user had just left. The comparison is now `serverKey(it) == serverKey(current)`. Evidence: SOURCE CODE + TEST (`nextServerCursorMatchesByIdentityWhenHostnameIsNullVersusEmpty`).

11. **Tests replaced, not deleted.** Three files pinned behaviour this revision removes on purpose and were **replaced** by tests of the new behaviour: `DataUtilPrefersVpnTest` (T-NEW-46..49, T-NEW-54a..d) → `DataUtilRoutingPreferenceRemovedTest` (T-REMOVED-01..05); the routing half of `HttpFetcherR4bTest` (`CollectorRouting.choose` permutations) → input-shape and removal assertions; `VpnServerRepositoryLadderTest` (opt-in reordering) → the automatic ladder plus 8 `PathMemory` cases. `VpnServerRepositoryRoutingTest` and `VpnServerRepositoryRoutingDynamicTest` were **updated** to the two-field input (T-NEW-37 now pins that no input promotes TUNNEL without evidence). Nothing was silently dropped. Evidence: TEST.

12. **One new production seam.** `pathMemory` became a constructor parameter of `VpnServerRepository` (default `PathMemory()`), so a test can seed the evidence that promotes `TUNNEL` — the R4b tests drive runs that start on the tunnel rung and previously did so through the removed `preferVpn` input. `PathMemory` is public for the same reason: a public constructor cannot expose an `internal` parameter type. Production behaviour is unchanged (a fresh instance per repository). Evidence: SOURCE CODE.

13. **JVM counts.** `:app` **925 / 0 / 0 / 1** (baseline 907 / 0 / 0 / 1; **+18** net after the replacements). `:SoftEtherClient` **184 / 0 / 0 / 0** — untouched by this revision. No regression. Evidence: TEST.

14. **Build and APK.** Recorded in `AUTO_ROUTE_AND_AUTOCONNECT_UX_REPORT.md` §9 and in the §20 changelog row for 1.0.52. Evidence: TEST.

15. **Out of scope, deliberately.** No MS-SSTP, native, ladder-rung or APK-version change; `versionCode 144` / `versionName 2.4.11-dev` are unchanged (no build or manifest file was touched). `vpngate_collector.py` and `servers.csv` were not staged. Evidence: SOURCE CODE (diff scope).

16. **Device verification is PENDING — UNKNOWN (V-VERIFY-4).** The closing checks are: (a) with the physical path blocked by the ISP and a tunnel up, the collector recovers on its own — the first run fails on PHYSICAL, the next logs `[ladder] order=TUNNEL,PHYSICAL,EMERGENCY` with no user action; (b) the Automatic Reconnection switch is present on the Auto Connect screen, absent from Settings, and its state survives a restart; (c) a dead server is abandoned after ~8 s rather than ~12 s. No device ran in this phase. Evidence: UNKNOWN.

17. **No new methodology lessons.** Nothing in this phase produced a §22 entry. §22 is byte-identical. Evidence: SOURCE CODE (diff) + TEST.

#### 1.6.45 SERVER-DATA-AUDIT-AND-OPENVPN-CONFIG-GATE (v1.0.53)

A device-free audit of the server data plus the one defect the audit proved. TASK A fixes OpenVPN being offered without a profile; TASK B audits every field against every parser and the Python oracle, and applies the config-authoritative rule the audit established.

1. **TASK A — a port is not a transport.** `AutoModeController.usableProtocols()` decided OpenVPN usability from `openVpnTcpPort > 0` / `openVpnUdpPort > 0` alone. OpenVPN is the one protocol whose dial needs **bytes**: `AndroidConnectionAdapter.connectOpenVpn()` builds the profile from `VPNGateConnection.openVpnConfigData`, and returned **silently** when that was null. The connect therefore looked issued, `awaitArmedOutcome()` waited out the full per-attempt budget, and the attempt was reported `TIMED_OUT` — the one outcome the controller treats as evidence *about the server*, so it recorded a wedge (AUTOCONNECT-AUDIT D-5). A data defect was billed as a dead endpoint, once per selected server, at 8 s each. The manual path had always required both (`DetailActivity.protocolOptions`: `openVpnConfigData != null && tcpPort > 0`); Auto Connect did not. Evidence: SOURCE CODE.

2. **The gate.** `AutoModeCandidate.openVpnConfigAvailable: Boolean = false`, and `usableProtocols()` now requires it: `OPENVPN_TCP -> c.openVpnConfigAvailable && c.openVpnTcpPort > 0`, likewise for UDP. The default is `false` **deliberately** — fail closed: a candidate built without the fact loses a protocol instead of burning a timeout. Evidence: SOURCE CODE + TEST (`configLessOpenVpnServerIsNeverSelected`, `protocolFilterOpenVpnUdp`).

3. **One flag covers both transports, and that is why `VPNGateConnection.kt` was not touched.** `AutoModeEngine.toCandidate()` sets `openVpnConfigAvailable = !openVpnConfigData.isNullOrBlank()`. `openVpnConfigDataUdp` is derived via `OpenVpnConfigTransform.forUdp`, which returns the same profile rewritten and is null **iff** its input is null, so UDP is dialable exactly when TCP's bytes exist. A second flag would model nothing. Evidence: SOURCE CODE.

4. **Port facts are preserved.** The gate changes *usability*, never the data: `openVpnTcpPort` / `openVpnUdpPort` are still carried verbatim, and `EmergencyCsvParser` still writes the `OpenVPN_*_Port` column. A `do_openvpn` link is **not** a config — nothing in `app/src/main` ever downloads it — so a link-only server is correctly unusable, because the gate keys on real bytes (`rawConfig`), not on the `configAvailable` claim. Evidence: SOURCE CODE + TEST (`theGateDoesNotEraseThePortFacts`, `htmlLinkOnlyServerIsNotUsableForOpenVpn`).

5. **The parser stopped claiming usability without bytes.** `EmergencyCsvParser.applyOpenVpnColumnPort()` writes `port` + `portSource` always, but sets `supported = true` only when the config actually decoded. Two new **non-rejecting** issues make it auditable: `OPENVPN_PORT_WITHOUT_CONFIG` and `OPENVPN_PORT_CONFLICT_CONFIG_WINS`. This also stops `ServerQualityCalculator` from awarding OpenVPN points (`ov["supported"]`) to a row that cannot be dialled. Evidence: SOURCE CODE + TEST (`an openvpn port without a config keeps the port but claims no support`).

6. **TASK B.6 — `AttemptOutcome.MISSING_CONFIG`, and it is not a timeout.** The adapter no longer returns silently: `connectOpenVpn()` sets a per-attempt flag (cleared by every `armTunnelWatch`), and `awaitArmedOutcome()` returns `MISSING_CONFIG` **before it waits**, so the budget is not spent. The controller treats it as an **ordinary attempt failure**: log, rotate to the **next protocol on the same server**, record **no wedge**, create no terminal state. The v1.0.37 no-terminal contract and the per-attempt timeout semantics are intact. A throw was rejected on purpose: it would take the generic `catch` and run `adapter.disconnect()` for a tunnel this attempt never started. Evidence: SOURCE CODE + TEST (`missingConfigRotatesToTheNextProtocolOnTheSameServer`).

7. **TASK B.6 — one developer-log line per attempt.** `[AUTO] ATTEMPT_CLASS <class> outcome=<name> protocol=<id>` with `CONNECTED` / `REFUSED_START` / `TIMEOUT_NO_VERDICT` / `ENDPOINT_REJECTED` / `MISSING_CONFIG`. The mapping is lossy in exactly one place, and the code says so: a refused TCP connect, a rejected TLS handshake, a non-SoftEther reply to the VPNCONNECT watermark and an auth rejection all arrive as `NATIVE_ERROR`, because `TunnelStateWatcher` maps SoftEther `STATE_ERROR` and OpenVPN `LEVEL_AUTH_FAILED` onto the same `onAttemptFailed` callback and does not forward the stack's `logmessage`. Separating them needs a `TunnelStateWatcher` change outside this phase's authorised file set, so the log names the one honest fact instead of inventing the distinction. `WRONG_DATA_SUSPECT` is deliberately never emitted per attempt: it is a property of the server **record**, reported by the audit, not something one dial can prove. Evidence: SOURCE CODE + TEST (`AutoConnectAttemptClassificationTest`).

8. **TASK B.3 — the embedded config is authoritative over the link and the column.** Rule: the endpoint the embedded `.ovpn` dials wins; an `OpenVPN_*_Port` column or a `do_openvpn` URL parameter is used **only** when the config declares no port for that transport. Applied in three places, each with tests: `EmergencyCsvParser.applyOpenVpnColumnPort()` (within a row), `VpnServerMerger.enforceConfigAuthoritativePorts()` (a record-wide invariant, run before port validation), and `emergency_csv_publisher.enforce_config_ports()` (after the merge, before prune/budget). Every correction is logged; the merger records a §38 conflict. **No §10 CSV columns were added.** Cross-source precedence already produced this result (`SOURCE_PRIORITY`: api 0 < html 10 < manual 30, and the API parser writes the config's port at `api` authority); the merger function is the invariant that makes it checkable in one place so a future parser cannot reintroduce the split. Evidence: SOURCE CODE + TEST.

9. **The reproduced conflicts.** Two rows advertise a port their own profile does not dial. `123.48.124.169` (`vpn122916437`) publishes `OpenVPN_TCP_Port = 1534` while its profile dials `remote 123.48.124.169 1558` — present in **all four** snapshots examined. `153.180.162.195` (`vpn927671187`) publishes `1820` while its profile dials `1759` — present at `4afa7e3` only, and the row the brief could not reproduce (see item 15). Both profiles decode to 13 344 Base64 characters and both rows are corrected by the same generic rule. Evidence: TEST.

10. **TASK B.4 — `SSTP_Hostname` may carry `:port`, and it is a live, recurring shape.** The live page publishes `SSTP Hostname : vpn799794865.opengw.net:1919` alongside plain hostnames, whenever the port is not 443. `EmergencyCsvParser` was **already correct** (`normalizeHost()` strips a single-colon suffix, `applySstpEndpoint()` recovers the port, the explicit column wins) and is now pinned by test rather than assumed. `emergency_csv_publisher.py` was **not** correct — it wrote the raw cell, so `SSTP_Hostname` could carry `:1956` with `SSTP_Port` 0 — and now splits via `split_sstp_endpoint()` in `sanitize_row` plus a `normalize_sstp_rows()` pass over the merged set (needed because the merge stage keeps the original row objects). Evidence: LIVE + SOURCE CODE + TEST.

11. **TASK B.5 — `SE_UDP_Port` all-zero is correct data, not a defect.** The live page renders SoftEther UDP as `TCP: 443<BR>UDP: Supported` — 180 occurrences of `UDP:`, **zero** `UDP: <digits>`. The port is simply never published. In the audited snapshot 219 of 229 rows declare `SE_UDP_Supported=true` and `SE_UDP_Port` is 0 in all 229. `EmergencyCsvParser` already implements §10.3 exactly (support is declared, never inferred from a port; a port without declared support raises `UDP_PORT_WITHOUT_DECLARED_SUPPORT`). **No change made** — inventing a port here would be the defect. Evidence: LIVE + SOURCE CODE + TEST.

12. **TASK B.5 — `Has_IPv6` is unobservable, and the value is inert.** The string `IPv6` occurs **zero** times in the 216 651-byte live page; the publisher writes `false` for every row; `EmergencyCsvParser` lists the column but never reads it, and no record field, merge rule or UI consumes it. Per the "leave it unknown instead of false" rule the honest statement is that IPv6 support is **not observable** and the `false` is a non-observation. **No code change:** the §10 schema requires a boolean there, `applyBool` would raise `NON_BOOLEAN_TOKEN` for an empty cell, and nothing depends on the value — so rewriting it would break a column contract for zero behavioural gain. Recorded as UNKNOWN in `AUTO_DATA_AUDIT_REPORT.md` §5.3. Evidence: LIVE + SOURCE CODE.

13. **TASK B.5 — `SSTP_Supported` needs no change.** It is `true` in 229 of 229 rows, with `SSTP_Port = 0` in 33 of them. The flag is observable (the page publishes an SSTP hostname for essentially every server), and the consumer is already correct: `usableProtocols()` refuses SSTP when `sstpConnectPort == 0`, while `VpnConnectionMapper` documents the 443 default as intended ("connect on TCP 443 by default"). A DEFAULT-port server stays supported; an *unknown*-port server is not dialled. Evidence: LIVE + SOURCE CODE + TEST.

14. **TASK B.2 — every unit conversion converges.** All four parsers and the Python oracle target the same three record fields: `performance.speedMbps` (bps ÷ 1e6), `performance.uptimeDays` (API/CSV ms ÷ 86 400 000; HTML parses `"93 days"` / `"11 hours"`), `performance.totalTrafficGB` (bytes ÷ 1 073 741 824). `Operator` / `Message` with unquoted commas parse identically (0 malformed rows of 229, 29 columns on every row). DDNS hostnames are carried verbatim (5 DDNS / 224 plain / 0 empty). HTML table/column selection and the oracle's `tds` indexing find the same 63 OpenVPN rows. The one genuine divergence is the OpenVPN port source (item 8), and it is the only one. The full table is `AUTO_DATA_AUDIT_REPORT.md` §3. Evidence: SOURCE CODE + TEST.

15. **The dataset moved four times during the phase, and the figures are pinned to a commit.** `a5cb153` = 280 rows / 218 without config (77.0 %) / 1 conflict; `0387d37` = 283 / 221 (78.1 %) / 1; `b5e0f7e` = 271 / 209 (77.1 %) / 1; `4afa7e3` = 229 / 166 (72.5 %) / **2**. The operator's 280 / 218 / 77 % / 1 conflict is exactly right for `a5cb153`, the HEAD at STEP 1. The config-less proportion is a **range (72.5–78.1 %)**, not a constant, and the conflict count is not constant either: `123.48.124.169` (1534 vs 1558) is in every snapshot, while `153.180.162.195` (1820 vs 1759) appears only at `4afa7e3`. The brief's instruction to record `153.180.162.195` as UNKNOWN was correct for `a5cb153` — its config was empty there, so there was nothing to disagree — and it is **confirmed at `4afa7e3`**; both readings are recorded rather than one claim that is wrong half the time. Nothing in this block wrote to `emergency-sources/servers.csv`, `servers.csv` or `vpngate_collector.py`, and none of them was staged. The `123.200.94.11` SSTP row cited in the brief was present at `0387d37` and is absent from `b5e0f7e`, so it is pinned in tests from its real values rather than assumed to still exist. Evidence: SOURCE CODE (git history) + TEST.

16. **`VPNGateConnection.kt` was authorised conditionally and was not needed.** The condition was "only if `openVpnConfigData != null` is not enough to model config availability". Item 3 shows one flag models both transports exactly, so the file was not modified. Evidence: SOURCE CODE.

17. **One known remaining correctness issue, reported and not changed.** `VpnGateHtmlParser` sets `protocols.openvpn.configAvailable = true` from a `do_openvpn` href although nothing downloads that link. It does **not** bypass the gate (item 4), but it does inflate `ov["supported"]` and therefore `ServerQualityCalculator`'s OpenVPN points. Correcting it changes ranking and the UI's protocol badges, which this phase did not authorise; it is recorded as S-1 in `AUTO_DATA_AUDIT_REPORT.md` §8. Evidence: SOURCE CODE.

18. **Verification counts and build.** JVM `:app` **941 / 0 / 0 / 1** (baseline 925; **+16** net: 4 in `AutoModeControllerTest`, 4 in the new `AutoConnectAttemptClassificationTest`, 6 in `EmergencyCsvParserTest`, 2 in `MergerOracleTest`); `:SoftEtherClient` **184 / 0 / 0 / 0** (untouched). Python `test_emergency_csv_publisher.py` **40 / 0** (baseline 24; **+16**: 9 config-authority cases, 7 SSTP-split cases). `:app:assembleProDebug` SUCCESSFUL under `CI=true` (`--rerun-tasks --no-build-cache`). **One instability was observed and is reported rather than hidden:** across four full-suite runs, `AutoModeControllerTest.pressWhileConnectedDisconnects` failed once and `twoImmediateSkipsNeverDialTheSameServerTwice` failed once; both pass in isolation and both passed on the recorded green run. The second failure logged `[s2, s1, s1, s1, s1, s1]` — a server dialled five times — which is a **product-level duplicate-dial observation in the v1.0.52 skip/continuation path** (`skipToNextServer()` does not publish a Disconnected state before launching its continuation, so a second immediate press re-enters the Connected branch and cancels-and-relaunches). It is recorded as R-1 in `AUTO_DATA_AUDIT_REPORT.md` §8 for a follow-up block; this block did not modify that path. Evidence: TEST.

19. **Out of scope, deliberately.** No MS-SSTP, native, ladder-rung, §10 schema or APK-version change; `versionCode 144` / `versionName 2.4.11-dev` unchanged (no build or manifest file touched). No new §10 CSV column. `vpngate_collector.py` and `servers.csv` untouched. Evidence: SOURCE CODE (diff scope).

20. **Device verification is PENDING — UNKNOWN.** V-1 a config-less OpenVPN server is never selected; V-2 the gate does not shrink the usable set for config-carrying rows; V-3 `123.48.124.169` dials 1558; V-4 an SSTP `host:port` hostname connects; V-5 `ATTEMPT_CLASS MISSING_CONFIG` never appears (if it does, TASK A has a hole). No device ran in this phase. Evidence: UNKNOWN.

21. **No new methodology lessons.** Nothing in this phase produced a §22 entry. §22 is byte-identical. Evidence: SOURCE CODE (diff) + TEST.

#### 1.6.46 SKIP-RACE-COALESCING (v1.0.54)

Closes **R-1** of `AUTO_DATA_AUDIT_REPORT.md` §8 — the duplicate-dial that the v1.0.53 report could only record as an observation. Device-free; nothing in this part needs the phone.

1. **The defect.** `skipToNextServer()`'s Connected branch tore the tunnel down asynchronously while the state still read `Connected`. A second press in that window re-entered the same branch, cancelled the continuation the first press had just published (`job?.cancel()`), and launched its own — two loops walking one list. The v1.0.53 observation was `[s2, s1, s1, s1, s1, s1]` for one double press. Evidence: SOURCE CODE.

2. **The guard.** `skipInFlight: Boolean` (`@Volatile`) is set **inside** `transitionLock` at the top of the Connected branch, before any asynchronous work, and a second entry logs `[AUTO] Skip already in flight; duplicate request ignored` and returns. Setting it synchronously inside the monitor that already owns the transition — rather than relying on dispatcher ordering — is what makes the coalescing deterministic. Evidence: SOURCE CODE + TEST.

3. **The lifetime, and the first implementation was wrong.** v1.0.54 initially cleared the flag in two places: inside the skip continuation just before launching the replacement run, and on `runAutoMode()`'s first line. Both are *before* the replacement run publishes a state, so the machine still read `Connected` with the flag already `false` for the whole teardown plus list resolution. The full-suite run caught it: `duplicateSkipWhileConnectedIsCoalescedToTheFirstSkip` passed in isolation and failed under load, with the log showing two "Connected server skipped by user" lines and no "duplicate request ignored". **The flag's lifetime is now owned by `setState`**: publishing any state other than `Connected` clears it. That is the definition — "a Connected-state skip was accepted and the machine has not left Connected yet" — expressed in the single place that can observe it. Evidence: TEST.

4. **A press in the window is a stop, not a swallow.** `onButtonPressedWithoutGuard()`'s Connected branch first read the flag as "ignore the press". That breaks §3: while the state is Connected the button means *disconnect*, and the window lasts as long as a teardown. It now logs `[AUTO] Skip in flight; button press treated as stop` and calls `stop()`, which also cancels the pending continuation. Evidence: SOURCE CODE + TEST.

5. **The tests hold the window open instead of racing it.** `FakeAdapter.disconnect()` gained an optional `disconnectGate: CompletableDeferred<Unit>`; a test parks the skip inside its teardown, presses, then releases. Without the gate the continuation can publish `Connecting` between two presses, and a later press then legitimately takes the *Connecting* branch (`skipRequested`) — a different, correct skip that makes an ungated assertion meaningless. Three tests use it: `duplicateSkipWhileConnectedIsCoalescedToTheFirstSkip`, `buttonPressDuringAnInFlightSkipStopsInsteadOfStartingASecondRun`, `fiveRapidSkipsCollapseIntoASingleContinuation`. Evidence: TEST.

6. **Stability evidence.** After the fix, `AutoModeControllerTest` ran repeatedly with `--rerun-tasks` and finished with 0 failures each time; before the fix the same class failed under full-suite load while passing in isolation. The flakiness was the finding, not an accident of the run. Evidence: TEST.

7. **A second window, closed in v1.0.55 (§1.6.51 / G-6).** The replacement run was published from `synchronized(transitionLock) { job = scope.launch { runAutoMode() } }` without re-checking that the skip was still wanted. `adapter.disconnect()` above it is a suspension point and `synchronized` is not, so a `stop()` that completed while the teardown was finishing had already set `job = null` — and this block then overwrote it with a fresh run, restarting Auto Connect after the user stopped it. The block now re-reads `skipInFlight` (which `stop()` and `disconnectNow()` clear inside the same monitor) and, when it is false, logs `[AUTO] Skip superseded by stop; not relaunching` and leaves `job` null. Evidence: SOURCE CODE; the window cannot be forced deterministically from a test, and the reachable half — stop during a gated teardown — is already covered by `buttonPressDuringAnInFlightSkipStopsInsteadOfStartingASecondRun`.

#### 1.6.47 SETTINGS-CLEANUP (v1.0.54)

Three Settings controls removed outright, one default changed, one recovery path added. Every removal deletes the code behind it rather than hiding the switch — a dead preference that no longer renders is a second source of truth waiting to be re-read.

1. **"Include UDP server" — removed, and UDP servers are in the list by construction.** Deleted: the switch, `DataUtil.INCLUDE_UDP_SERVER`, and every `isIncludeUdp` consumer. `VPNGateConnection.getName()` no longer branches on the setting, `VPNGateConnection.fromCsv()` always reads the port columns, `ServerCard` renders the TCP/UDP badges unconditionally from the ports themselves, and `DetailActivity` / `StatusViewModel` / `AndroidConnectionAdapter` lost their local `isIncludeUDP` and the `&&` conditions built on it. Evidence: SOURCE CODE.

2. **"Block Ads (Pro only)" — removed.** Deleted: `DataUtil.SETTING_BLOCK_ADS`, the switch and its `prefs` DNS writes, the `vpn_dns_block_ads_primary` / `vpn_dns_block_ads_alternative` entries in `AppConfig`, and the three `resolvePrimaryDns()` / `resolveSecondaryDns()` branches in `DetailActivity`, `StatusViewModel` and `AndroidConnectionAdapter`. Each now resolves Custom DNS only, which is exactly what the switch produced when it was OFF — so no user-visible behaviour is lost, only the ability to turn the alternate DNS pair on. Evidence: SOURCE CODE.

3. **Cache: one button, no schedule.** `models/Cache.kt` deleted. Removed `getCacheSaveTimeMinutes()`, `clearConnectionCache()`, `markConnectionsCacheFresh()`, `connectionCacheExpires`, `setConnectionCacheExpire()`, the `connectionsCache` custom setter (now a plain `val`), and the `SETTING_CACHE_TIME_KEY` / `DEFAULT_CACHE_TIME_INDEX` constants. Settings keeps a single "Clear cache" row plus the existing update-interval row; `setting_cache_subtitle`, `setting_cache_auto_clear_label` and the whole `setting_cache_time` array are gone from all three locales. `DataUtil.clearTransientCache()` replaces the removed pair: it deletes the children of `cacheDir` and the legacy `CONNECTION_CACHE_KEY` marker, and by documented contract touches nothing else. A `SETTING_CACHE_TIME_KEY` left in an existing install is inert, and a test pins that it is ignored rather than honoured. Evidence: SOURCE CODE + TEST (`DataUtilDefaultsV1054Test`).

4. **Auto Connect protocol priority defaults to SoftEther TCP + SoftEther UDP.** `DataUtil.DEFAULT_AUTO_PROTOCOL_PRIORITY = [SOFTETHER_TCP, SOFTETHER_UDP]`, returned when the key is **absent** (`null`) — not when it is empty. An explicitly stored empty list stays empty, because a user who switched every protocol off must not be silently overridden by a default. Parsing is extracted to `parseAutoModeProtocolPriority()` so the three cases (absent / explicitly empty / unknown ids dropped) are testable without SharedPreferences. Evidence: SOURCE CODE + TEST.

5. **The emergency source is pre-filled, and now restorable.** Already true since v1.0.46: `app/src/main/res/values/emergency_defaults.xml` carries `default_emergency_source_url` = the GitHub-hosted `emergency-sources/servers.csv`, generated by `emergency_csv_publisher.py`, and `App.onCreate` seeds it once. What v1.0.54 adds is recovery — when the row is absent, Settings shows a **Restore default source** button (`emergency_source_restore_default`), so a user who deleted the row gets it back without reinstalling or hand-typing a URL. Evidence: SOURCE CODE.

6. **Strings.** `values` lost 26 keys: `udp_setting_label` / `_hint`, `block_ads_setting_label` / `_hint`, `setting_cache_subtitle`, `setting_cache_auto_clear_label`, the `setting_cache_time` array, every `cache_time_*` entry, and the whole old manual-form set (`manual_server_host_label`, `_port_label`, `_protocol_label`, `_label_label`, `_notes_label`, `_add`, `_invalid`, `manual_server_protocol_*`). **`values-vi` and `values-fa` lost only the seven keys they actually had** — the manual-form set and the `cache_time_*` entries never existed in either translation, so the original wording ("removed across values, values-fa and values-vi") overstated what changed there. Added: 17 keys in English, 20 in Persian (the 17 plus the three rewritten `manual_server_title` / `_summary` / `_empty`), and **none in Vietnamese** — a gap that shipped in v1.0.54 and is corrected in §1.6.51 (G-5). Evidence: SOURCE CODE + TEST (key-set diff per locale).

7. **Out of scope, deliberately.** No Room schema change, no manifest change in this part, no `versionCode` / `versionName` change. Evidence: SOURCE CODE (diff scope).

#### 1.6.48 SERVER-LIST-PIN (v1.0.54)

The server the app is currently on — or is currently dialling — is shown at row 1 of the list, so it can be found without scrolling.

1. **The rule.** The row whose identity matches the active server is moved to index 0 of the *rendered* list. Everything else keeps its position.

2. **Identity, not IP and not index.** `ServerListPinning.identity(ip, hostName)` returns `"ip|host"` — the same key `resumeFromServerKey` (§1.6.35 / OP-R6) and the wedge ledger already use. An IP alone collides under NAT or a load balancer; an index drifts the moment the list re-sorts. One key for one concept. Evidence: SOURCE CODE.

3. **A view transform, not a reorder.** `ServerListPinning.pin(items, keyOf, pinnedKey)` is pure and O(n), applied **after** filter and sort in `HomeScreen`, and returns the caller's own list instance when there is nothing to do. The stored dataset is untouched, so a refresh cannot persist an accidental ordering and `firstSeenAt` / `lastSeenAt` / `healthScore` are never rewritten by a display concern. Evidence: SOURCE CODE + TEST.

4. **Both live states pin.** `pinnedKey` is remembered from the controller's active server, so a `Connecting` candidate pins exactly like a `Connected` one — the row the user is waiting for is the row they are looking for.

5. **Visible marker.** `ServerCard` gained `pinned: Boolean = false` and renders `server_pinned_badge` above the existing active-server badge, so a row that has moved is explained rather than merely displaced.

6. **Tests.** `ServerListPinningTest`, 11 pure cases: identity shape, connected pin, connecting pin, candidate switching, same-IP-different-host, absent server, empty list, null pin, pin filtered out, no duplication, and a 1000-row single pass. Evidence: TEST.

#### 1.6.49 MANUAL-IMPORT (v1.0.54)

The manual add-server form is replaced by CSV import: a box to paste into plus a button to pick a file, with the accepted format explained in the UI.

1. **The old form is gone.** The host / port / protocol / label / notes fields and the `manual_server_protocol_*` labels are removed. Two tests were deleted with the surface they pinned — `ManualServerProtocolChoicesTest` and `ManualProtocolLabelExhaustiveTest` — because a test that asserts the shape of a form that no longer exists is worse than no test. **Legacy `manual_server` rows are still listed in Settings** with their enable switch and delete button, so the rewrite loses no user data. Evidence: SOURCE CODE.

2. **The replacement.** A multiline paste field with an **Import** button, and an **Import CSV file** button that opens the system document picker. The picker accepts `text/csv`, `text/plain` and `*/*` on purpose: messengers label CSV attachments inconsistently, and refusing a file the user can see would be worse than parsing it and reporting "unrecognised".

3. **Two formats, both already owned.** The §10 extended CSV (`EmergencyCsvParser`) and the VPN Gate `/api/iphone/` CSV (`VpnGateApiParser`). `ManualCsvImport` does only what neither parser should do for a pasted file — detect the dialect, tolerate transport artefacts, enforce the caps, and hand the text to the owner. It never re-implements column semantics; a second definition of the format would drift from the first. Evidence: SOURCE CODE.

4. **Transport repair.** BOM stripped, CRLF/CR unified, trailing horizontal whitespace trimmed, and hard-wrapped rows re-joined by `joinWrappedRows()`. The re-join is deliberately conservative: a line is appended to the buffer only when **both** the buffer and the line have fewer fields than the header. A complete row therefore always starts a new buffer, and a genuinely malformed short line is left standing so the owning parser can reject it with a real reason instead of being glued to the next server. **v1.0.59 (§1.6.58 B2) adds a field-level rule on top of this repair:** after the repair, a host name or IP that still contains *inner* whitespace or a control character rejects **that row** with a named reason, while leading and trailing whitespace is still trimmed and accepted. The repair above trims line ends; it never removes a character from inside a field, which is why the two rules are separate — the device showed a host stored as `test-a. example`, where the space sits *between* two label characters and survives every line-level trim. Evidence: SOURCE CODE + TEST + PHYSICAL DEVICE.

5. **A defect found while writing the tests.** The API body opens with a `*vpn_servers` section marker, so "the first non-blank line is the header" misclassified every real API file as `UNKNOWN` and refused it. Detection and the wrapped-row repair now both locate the header through `VpnGateApiParser.findApiHeader`, and the body is sliced from that line before it reaches either parser. Evidence: TEST (`detect sees past the api section marker`, `an api file with its section marker parses instead of being refused`).

6. **Limits, and they are documented in the UI.** 1 MiB of UTF-8 input and 2000 data rows, both hard: over either, the import is refused whole rather than truncated, because a truncated server list is worse than a clear error. `readCappedUtf8()` enforces the byte cap at the *stream* layer, before any decoding — the picker hands back a stream of unknown length, and a 40 MB attachment would otherwise be decoded into a ~80 MB `String` before the parser's own check could run. Evidence: SOURCE CODE + TEST.

7. **Storage.** `ManualImportStore`, a JSON file (`manual_import_servers.json`) keyed by `hostName`, with atomic temp-file + rename writes, a corrupt file reading as empty (and left untouched), and no Room or network access at all. A JSON file rather than a table because `manual_server`'s unique index is `(host, port, protocol)` while the list merges by host name — a multi-protocol CSV row would collapse into one protocol — and widening the schema is a Room migration this phase is not authorised to make. Evidence: SOURCE CODE + TEST.

8. **Preservation, through the existing policy.** `ServerListUpdates.manualServerRows()` now emits the imported rows as well, and `ConnectionListViewModel.materializeImported()` writes them back through the same `withManualRows` the refresh path uses. One preservation rule, not two. Evidence: SOURCE CODE. **The TEST half of this claim was not true when it was written** — the function was private and reached the `App` singleton, so nothing exercised the emission. v1.0.55 makes the two sources injectable and covers the rule with `ImportedServerEmissionTest` (§1.6.51 / G-2).

9. **Provenance.** Imported rows carry the tag `manual` — never `api` or `html` — so a file the user imported can never speak with canonical authority in the merger. Evidence: SOURCE CODE + TEST.

10. **Tests.** `ManualCsvImportTest` (36), `ManualImportStoreTest` (20), `ReadCappedUtf8Test` (9). The CSV tests cover both dialects, the section marker, BOM + CRLF, SMS hard-wrap repair, duplicate collapsing, the byte and row caps at their exact boundaries, the failure taxonomy, binary garbage, and the "`0.0.0.0` is accepted, an empty IP is not" distinction that the first draft of one test got wrong. Evidence: TEST.

#### 1.6.50 EXCLUDED-APPS-SEED (v1.0.54)

A fresh install should already exclude the operator's Iranian services instead of asking for twenty ticks by hand. The mechanism ships complete; the package names are the one part that needs the phone.

1. **The asset.** `app/src/main/assets/default_excluded_apps` — one package name per line, `#` comments, blank lines ignored, duplicates collapsed to the first occurrence, BOM stripped. `DefaultExcludedApps.parse()` is pure (text in, list out); `load()` reads the asset and returns an empty list on any failure, because a packaging mistake must not stop the app from starting.

2. **The four rules of `ExcludedAppsSeeder.seedOnce()`.** (a) **Installed only** — a package that is not on the device is skipped, never inserted as a phantom row that cannot be unticked meaningfully. (b) **Once per install** — `SETTING_EXCLUDED_APPS_SEEDED` is set when the seed runs, so a user who removes a seeded app does not get it back on the next launch. (c) **Never overwrite** — an existing row keeps its stored `appName`. (d) **No candidates, no flag** — see item 4.

3. **Why the core takes lambdas.** `seedOnce()` performs no Android work: "is the flag set", "set the flag", "is this installed" and "insert this row" all arrive as functions. Every rule above is therefore provable in a pure JVM test with no Robolectric, no Room and no device — which matters precisely because the device is unavailable while this ships. Evidence: SOURCE CODE + TEST.

4. **Rule (d) is the one that matters for this release.** The asset ships with its format documented and **no entries**, because the real package names are on the operator's phone and guessing them would produce rows that match nothing while the seeder reported success. Setting the flag on an empty list would permanently disable the seed for the very build that finally carries the names, so an empty candidate list leaves the flag clear. Evidence: TEST (`an empty seed list does not burn the one-shot flag`).

5. **Wiring.** `App.onCreate` launches `ExcludedAppsSeeder.runIfNeeded()` on the existing `appScope` (IO) with `runCatching` — the same fire-and-forget shape as the v1.0.46 emergency-source seed, for the same reason: `getInstalledApplications` on a device with a few hundred packages must not block startup.

6. **Package visibility, which the sheet also needed.** The manifest gained a `<queries>` element for `MAIN` / `LAUNCHER`. Since API 30, `getInstalledApplications` returns only packages the app is allowed to see; without a declaration it returns almost nothing — silently, with no error — so both the Excluded Apps sheet and this seed would have looked broken on any modern device. It is scoped to launchable apps on purpose: `QUERY_ALL_PACKAGES` requires a separate Play Console justification and buys nothing for the actual use case. The trade-off — a background service with no launcher activity is not listed — is recorded here rather than papered over. Evidence: SOURCE CODE.

7. **Tests.** `ExcludedAppsSeederTest` (13) and `DefaultExcludedAppsTest` (12): installed-only, flag set even when nothing matched, second run inert, a user-removed entry not re-added, an already-excluded row keeping its name, seed order preserved, every candidate accounted for exactly once, the empty-list rule, comment/blank/duplicate/BOM/CRLF parsing, and two guards on the shipped asset itself (it must document its format, and it must still be empty until D1 lands). **[v1.0.56 — D1 is now CLOSED; see §1.6.54. The "still be empty" guard was replaced by real-content guards and the suite grew from 12 to 16 tests.]**

8. **DEFERRED (D1) — the package names.** The operator entered those apps manually on the phone; extracting the authoritative list needs the device. Adding them later is a data edit to one asset file: no code change, no schema change, no migration, because the parsing, the installed check, the one-shot flag and the tests are already in place. Evidence: OPERATOR STATEMENT. **[v1.0.56 — CLOSED. The 12 confirmed Iranian package names were extracted from device `5585fecc` on 2026-10-24 and shipped; see §1.6.54.]**

#### 1.6.54 D1 EXTRACTION AND SHIPMENT (v1.0.56)

**D1 is CLOSED.** `assets/default_excluded_apps` now ships **12 package names** instead of an empty entry list, extracted from the operator's device and confirmed by the operator with `CONFIRM D1: ALL CLASSIFIED-IRANIAN`.

1. **Extraction [PHYSICAL DEVICE].** Device `5585fecc` (Xiaomi 2201117TG, Android 13 / API 33), package `vn.unlimit.vpngatepro`. The `excluded_apps` table of `vpn_gate_connector` was read read-only via `run-as` and carried **15 rows**. All 15 were confirmed installed on the device by `pm path`.

2. **Three rows deliberately not shipped.** The list ships to **all** users, so it is not a copy of one operator's phone:
   - `com.deepseek.chat` (DeepSeek) — a **Chinese** AI assistant, a personal install. Not Iranian.
   - `com.google.android.projection.gearhead` (Android Auto) — a Google system app; §1.6.47 records it as the operator's own user-added exclusion, so it belongs to that device's data, not to the shipped default.
   - `vn.unlimit.vpngatepro` (Self) — the app excludes itself automatically in `App.kt`; listing it would be a duplicate.

3. **The 12 shipped entries** (sorted, de-duplicated, each with a trailing comment naming the service): `app.rbmain.a` (Rubika), `cab.snapp.passenger` (Snapp), `com.byagowi.persiancalendar`, `com.farsitel.bazaar` (Cafe Bazaar), `com.myirancell` (MTN Irancell), `com.pmb.mobile` (Bank Mellat), `com.rahand.qbank`, `ir.bmi.bam.nativeweb` (Baam), `ir.easytrader.orbis.m.twa` (EasyTrader Arbis), `ir.eitaa.messenger`, `ir.nasim` (Bale), `org.rajman.neshan.traffic.tehran.navigator` (Neshan).

4. **The asset now records its own provenance** — source database, device, extraction date, the operator's confirmation string, and the reason each of the three excluded rows was left out — so a future reader does not have to reconstruct why the list looks the way it does.

5. **Tests [TEST].** `DefaultExcludedAppsTest` grew from **12 to 16**. The obsolete `the shipped asset carries no entries yet` was replaced by five real-content guards: `the shipped asset carries the extracted D1 entries` (pinned against a single `EXPECTED_D1_ENTRIES` constant), `every shipped entry is a plausible Android package name`, `the shipped asset contains no duplicate entries`, `the shipped entries are sorted and carry no comment leakage`, and `the shipped asset excludes the three non-default packages`. All existing parse-rule tests are unchanged. Suite: **16/0/0/0**.

6. **Full-suite verification [TEST].** `:app:testProDebugUnitTest` **1166/0/0/1** across 92 suites (was 1162 — exactly the +4 net new tests), `:SoftEtherClient:testDebugUnitTest` **184/0/0/0** across 13 suites. One `--rerun-tasks --no-build-cache` run. No existing test was weakened, deleted or modified except the two asset guards named above.

7. **Scope.** One asset file, one test file, and this specification. No code change, no schema change, no migration, no Gradle or manifest change. Evidence: PHYSICAL DEVICE (extraction) + TEST (guards) + OPERATOR STATEMENT (the confirmation).

8. **Still deferred.** **D2** (all on-device verification) and **D3** (V-VERIFY-3/4) remain PENDING. A separate blocker — a launch crash in `ConnectionListViewModel` — was found in the same block and is recorded in §1.6.54; it is **not** fixed here.

#### 1.6.51 VERIFY-AND-CLOSE (v1.0.55)

A read-only audit of v1.0.54 (ten full-suite runs plus `:app:lintProDebug`) found six defects. Five were in the *evidence* rather than in the shipped behaviour — which is the point of auditing: a claim that is not true is a defect even when the code is fine. This section records what was wrong and what closed it. Report: `V1054_VERIFY_REPORT.md`.

1. **The suite was not reliably green.** Ten consecutive `:app:testProDebugUnitTest :SoftEtherClient:test --rerun-tasks --no-build-cache` runs: **7 pass, 3 fail**, always on `AutoModeControllerTest`, always a different single test per run. `:SoftEtherClient` was 184/0/0/0 in all ten. The "1044/0/0/1" figure in §1.6.46–§1.6.50 was one true run, not a stability result. Evidence: TEST.

2. **G-3 — `pressWhileConnectedDisconnects` (2 of 10).** The test read `state.value` and `isRunning` synchronously after `onButtonPressed()`. The OP-R6 continuation runs on `Dispatchers.Default`, so it can publish `Error(no_compatible_server)` before the test thread reads the field. This was a known flake, recorded since 2026-10-21, and it is the same anti-pattern PART 1 fixed on the skip path. The test now records every published state through the production `onStateChange` hook and asserts the **sequence** — `Disconnected` must follow the `Connected` it ends, and the continuation must reach its own terminal — instead of sampling one instant. Evidence: TEST.

3. **G-4 — `aFreshPressAfterExhaustionStartsFromTheTopAgain` (1 of 10, no prior record).** The test set a 300 ms per-attempt deadline. It asserts *ordering*, not timeout behaviour, but on a loaded machine a 300 ms deadline can expire before the fake adapter's instant `awaitTunnel()` is scheduled; both attempts then time out and the run legitimately ends in `ERROR_NO_SERVER` even though it dialled the right server first. The deadline is raised to 5 s and the failure message now prints `connects` and the last twelve log lines. Evidence: TEST.

4. **G-1 — "Clear cache" had no test.** The contract — *delete the transient cache and the legacy marker, and nothing else* — existed only as a KDoc comment: the method needed a `Context` and this module's unit tests are plain JVM with no Robolectric. `DataUtil.clearTransientCache(cacheDir, filesDir)` is now a pure companion function (the instance method delegates to it), so the production code path runs against real directories. `ClearTransientCacheTest` (11) pins both halves: `cacheDir` children are deleted recursively and `cacheDir` itself survives; the legacy marker is deleted; **`manual_import_servers.json`, the database, its `-wal`/`-shm` siblings, other `filesDir` entries and a sibling of `cacheDir` all survive**; missing/null directories are a success; a second run is idempotent. Evidence: SOURCE CODE + TEST.

5. **G-2 — the imported-row emission was untested.** `manualServerRows()` returns `legacyRows + importedRows`, and that concatenation is the only thing keeping an imported server alive across a refresh. It was private and read `App.instance`, so nothing could exercise it. The two sources are now injected providers (production passes the singleton lookups; behaviour unchanged) and `ImportedServerEmissionTest` (9) covers the rule: imported rows are emitted with no DAO, beside legacy rows, and with a disabled legacy row present; a disabled row stays out; a throwing provider costs the user only their imports; an imported row survives a wholesale `replaceAll` via `withManualRows`; a host the network already serves is not duplicated; an empty emission returns the caller's list by identity. Evidence: SOURCE CODE + TEST.

6. **G-5 — Vietnamese was left in English.** v1.0.54 added 17 keys in English and 20 in Persian and **none** in Vietnamese, which both rendered the whole new surface in English for `vi` users and added 17 `MissingTranslation` lint **errors**. The 20 keys are now translated, which clears 20 errors — the 17 plus the three rewritten `manual_server_title` / `_summary` / `_empty` labels, which had never been translated either. The remaining gap — 97 default keys still missing in `vi` (down from 117), 42 in `fa`, and **zero orphans** in either — is a translation backlog, not a defect of this block. Evidence: SOURCE CODE + TEST (lint).

7. **G-6 — a relaunch that could outlive a stop, and a KDoc that lied.** See §1.6.46 item 7 for the guard. Separately, `skipInFlight`'s KDoc still said the flag is "cleared when the replacement run actually starts" — the implementation that was reverted before v1.0.54 shipped — while `runAutoMode()` twenty lines below said it is deliberately not cleared there. The KDoc now states the real rule. Evidence: SOURCE CODE.

8. **Lint status, stated plainly.** `:app:lintProDebug` **fails**, and did before this block: **124 errors / 177 warnings / 9 hints** at v1.0.54, of which 90 were `MissingTranslation`, 1 `NewApi` (`AndroidConnectionAdapter.kt:653`) and 1 `SuspiciousIndentation` (`ServerListUpdates.kt:486`). v1.0.54's own contribution was the translation errors in item 6; v1.0.55 removes 20 of them and the count is now **104 errors / 178 warnings / 9 hints** — 70 `MissingTranslation` (69 of them `vi`), the same `NewApi` and `SuspiciousIndentation`. The rest is the translation backlog above plus the 121 `UnusedResources` warnings, none of which is a v1.0.54 key. Lint is therefore not a gate this project currently passes, and pretending otherwise would be worse than saying so. Evidence: TEST.

9. **Still open.** D2 (all on-device verification) and D3 (V-VERIFY-3/4) are unchanged. Nothing in this section ran on a device. Evidence: UNKNOWN. **[v1.0.58 — D1 is CLOSED; see §1.6.54.]**

#### 1.6.52 F.29 PHASE-1 DESIGN GATE (v1.0.56)

Read-only architecture audit for `F.29 — Smart Server History & Adaptive AutoConnect`, plus the design that audit produced. **No production source, test, Gradle file, Room entity, schema or migration was touched.** The only file modified in this phase is this specification, and the design itself lives in **§11.15**. No device was available and no ADB command was issued.

Five findings are load-bearing — each one changed the design rather than confirming it:

1. **The app has three different server identities, and none of them is history-safe on its own.** Room's primary key is `hostName` alone (`VPNGateItem.kt`); the collector's merger keys on *IP when valid, otherwise hostname* (`VpnServerMerger.kt:258-267`); Auto Mode, the Next-Server cursor and the v1.0.54 list pin all use `"$ip|$hostname"` (`AutoModeController.serverKey`, §11.14.3). History must adopt one of these and document what happens to the other two, not invent a fourth. §11.15.3 does that.
2. **`replaceAll` is destructive, and every write path ends in it.** `VPNGateItemDao.replaceAll` is `deleteAll()` + `insertAll()` inside a `@Transaction`. The network path rebuilds the whole list in memory first (`ServerListUpdates.mergeAll:486-527`) and the fallback path passes the incoming list straight through (`ServerListUpdates.kt:301-305`). A summary column on `VPNGateItem` would therefore have to be re-seeded by hand on both paths — and on the fallback path there is nothing to re-seed from. §11.15.4 puts the summary in its own table for exactly this reason.
3. **The fallback path already resets the v1.0.39 retention fields.** This is **pre-existing and independent of F.29**: `toVPNGateItem()` builds rows from `VPNGateConnection`, whose DTO has no `firstSeenAt` / `lastSeenAt` / `healthScore`, so a refresh served from the last-known-good snapshot re-inserts every row with those columns at their defaults. Retention phase 1 is correct on the network path and lossy on the fallback path. Recorded as **G-F29-1**; not fixed in this phase.
4. **There is exactly one authoritative success point, and it is already funnelled.** `AutoModeController.kt:2408-2432` is reached only when `connectedProtocol != null` after `AttemptOutcome.TUNNEL_UP`, it is the only producer of `AutoModeState.Connected`, and it `return`s immediately after. It is the only correct place to record a success. Symmetrically, `:2434` (`[AUTO] Connection failed: …`) is the single point at which a server is abandoned.
5. **No post-connect quality rejection exists.** Quality in this app is (a) a pre-attempt capability gate (`AutoModeController.supports()`), (b) a dataset-level gate (`DatasetIntegrity`), (c) a ranking comparator (`byQuality`), and (d) a session-scoped wedge exclusion (`WedgeTracker`, `EXCLUSION_THRESHOLD = 2`). Nothing disconnects a healthy tunnel for being slow, and `healthScore` — the one stored quality number — **has no reader in production**; only tests read it. "A successful connection followed by a quality rejection" therefore has no trigger to observe today. That is a gap, and §11.15.6 requests a decision instead of inventing a criterion.

Phase status: audit complete, design complete, **implementation NOT started**, awaiting the explicit approval the instruction's §17 gate requires. Open decisions: OD-1 … OD-9 in §11.15.14.

#### 1.6.53 F.29 PHASE-2 IMPLEMENTATION (v1.0.57)

Implementation of the nine decisions approved at the Phase 1 gate (OD-1 … OD-9, §11.15.14). **No commit, no tag, no push** — the work sits in the working tree, and HEAD itself advanced by one pre-existing commit during this phase (`0c3c56e`, `chore(emergency): update servers.csv`, touching only `emergency-sources/servers.csv`). No device was available and no ADB command was issued, so every device-dependent claim is labelled `NOT TESTABLE — DEVICE UNAVAILABLE`. **G-F29-1 is deliberately still open** and is restated in §11.15.17.8 rather than absorbed into this phase.

Eight things are load-bearing:

1. **Three new tables, no foreign keys.** `ServerHistorySummary` (1:1 with a server by `hostName`), `ServerProtocolSummary` (per `hostName` × protocol) and `ServerHistoryEvent` (bounded event log keyed by an attempt token) are separate tables, not columns on `VPNGateItem`, because `replaceAll` is `deleteAll()` + `insertAll()` and a summary column would have to be re-seeded on both write paths — with nothing to re-seed from on the fallback path (OD-1). All three are keyed by the Room primary key `hostName` alone (OD-2), and **no foreign key is declared anywhere**: a cascade would have deleted a server's entire history on every refresh. Indexes exist only for the four query patterns the code actually issues.

2. **Migration 8 → 9 is additive and parity-proven.** `MIGRATION_8_9` is three `CREATE TABLE IF NOT EXISTS` plus four `CREATE INDEX IF NOT EXISTS` — no `ALTER`, no `UPDATE`, no backfill and no destructive fallback, so an install that somehow skips the migration keeps its data and simply has no history tables. `Migration8to9Test` asserts the bounded version, the exact object set, the absence of `DEFAULT` / `DROP` / `DELETE` / `TRUNCATE` / `FOREIGN KEY`, a single `AUTOINCREMENT`, and **whitespace-insensitive parity against the KSP-exported `9.json` for all seven schema objects**, so the hand-written DDL and the exported schema can no longer drift apart silently.

3. **Failure attribution follows the rules, not convenience.** Success is recorded at exactly one place — the authoritative tunnel-up funnel that produces `AutoModeState.Connected`. A timeout has its own counter and is never a server failure (OD-3); local, permission, cancel, network and busy causes never reach a server-failure counter; a post-connect loss is its own event type and can never erase a recorded success; `consecutiveFailures` resets on success only. Writing the tests found a real bug here: `TIMED_OUT` was recorded with `outcome = FAILURE`, which would have put timeout-only servers on the same counter as genuinely failing ones.

4. **The network classifier is bounded and honest.** `NetworkTypeClassifier` returns exactly `WIFI` / `CELLULAR` / `UNKNOWN`. It never classifies the VPN transport, reads no SSID, no location and no subscription identifier, and treats "both signals present without proof" as `UNKNOWN`, which earns no tier-0 credit. A first-draft test asserted tier-0 credit for an unknown network and was wrong; the implementation was right.

5. **History is a tie-breaker, never a ranker.** `sortedForAutoConnect(history, network)` inserts one `applyHistoryTieBreak` pass inside the equal-quality window left by `applyRttTieBreak`, after the `byQuality` comparator and before the reachability gate, and is a no-op when there is no history or the window is smaller than two. An unknown server is never demoted below a known one (OD-6), and the user's protocol priority is never reordered (OD-4).

6. **Two approved decisions needed a deviation, both recorded rather than absorbed.** OD-6's "quality barrier" is implemented as a **transitive tier comparator** instead, because the barrier as specified is not a valid TimSort comparator and throws `IllegalArgumentException: Comparison method violates its general contract!` at real list sizes; the tier model enforces the same "unknown is never worse" rule and is total. OD-9's "awaited recording" is honoured for every event except `CONNECTION_LOST`, which is delivered from an OS callback with no coroutine in scope; the protocol is captured before the dispatch and the field is cleared synchronously, so the record cannot race. Both are documented in §11.15.17.3 and §11.15.17.4.

7. **A history error can never change a tunnel result.** Recording runs under `withContext(NonCancellable)` with `withTimeoutOrNull(HISTORY_WRITE_TIMEOUT_MS = 2 s)`; the sink contains `Exception` and rethrows `CancellationException`. Writing the cancellation test found a second real bug: `RoomServerHistorySink` caught `Throwable`, so a cancelled write was reported as a success. Three tests drive the failure modes directly — a throwing sink, no sink at all, a throwing history provider — and all three still end in `Connected`.

8. **Verification.** `:app:testProDebugUnitTest` **1162 tests / 0 failures / 0 errors / 1 skipped / 92 suites**; `:SoftEtherClient:testDebugUnitTest` **184 / 0 / 0 / 13 suites**; `:sstpClient:test` and `:vpnLib:test` BUILD SUCCESSFUL with no test sources; `:app:assembleProDebug` BUILD SUCCESSFUL producing `app-pro-debug.apk` (63,884,364 bytes, SHA-256 `2d7acfec2ecc619a60624af6a23cdd020c0418f7deb439d51fefe24a73436424`); a `--rerun-tasks` confirmation run on the delivered tree (after two source-comment corrections) reproduced both green suites and rebuilt the APK; `:app:lintProDebug` **104 errors / 178 warnings / 9 hints — identical to the pre-F.29 baseline, zero findings in any F.29 file**. All under `CI=true` with the Android Studio JBR, because the system default JDK is 1.8 and cannot compile this project. Net new: **6 test files / 98 cases**.

Phase status: **implementation complete, automated verification passed, device verification blocked**. Not claimed: a real process-restart durability check, real `ConnectivityManager` classification on a handset, a real 8 → 9 upgrade on a device holding an existing database, the end-to-end effect of history on a real Auto Mode run, and any app release version. G-F29-1 NOT FIXED. Open follow-ups: the retention sweep, the quality-rejection trigger reserved with no producer (OD-5), and every device-dependent item.

---

#### 1.6.55 LAUNCH CRASH FOUND BY DEVICE VERIFICATION (v1.0.56) — `KNOWN LIMITATION` `FOLLOW-UP REQUIRED`

A **pre-existing crash-on-launch defect** was found on the physical device during the v1.0.56 device-verification block and is **not fixed**. It is recorded here because it blocks all on-device verification (D2, D3, and every V-item of the v1.0.56 block).

**Symptom [PHYSICAL DEVICE].** On device `5585fecc` (Xiaomi 2201117TG, Android 13 / API 33), after installing the committed-tree APK (`app-pro-debug.apk`, SHA-256 `00a3b10b65c47edc001d5ecc5988a18c98d6c04bdc364c4b2f06108233e59670`, built from `git archive HEAD` = `0c3c56e`), launching the app shows `MainActivity` (+945 ms) and then kills the process:

```
FATAL EXCEPTION: main
Process: vn.unlimit.vpngatepro, PID: 32456
java.lang.NullPointerException: Attempt to invoke interface method
  'void kotlinx.coroutines.flow.MutableStateFlow.setValue(java.lang.Object)'
  on a null object reference
    at vn.unlimit.vpngate.viewmodels.ConnectionListViewModel$refreshImportedServers$1.invokeSuspend(ConnectionListViewModel.kt:328)
    at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:100)
    Suppressed: kotlinx.coroutines.internal.DiagnosticCoroutineContextException:
      [StandaloneCoroutine{Cancelling}@b8713fd, Dispatchers.Main.immediate]
```

**Root cause [SOURCE CODE].** In `ConnectionListViewModel.kt` the property `_importedServers` is declared at line **268**, but the `init` block at lines 46–52 calls `refreshImportedServers()` at line **50** — *before* the declaration is reached. Kotlin initialises properties in declaration order, so the backing field is still `null` when `init` runs. `viewModelScope` uses `Dispatchers.Main.immediate`, which executes the coroutine body **synchronously up to the first suspension point**, so `_importedServers.value = …` at line 328 runs before the constructor finishes and hits the null field.

**Attribution [SOURCE CODE].** Introduced by **`ec3a73b`** (`feat(v1.0.54): skip coalescing, settings cleanup, list pin, CSV import, app seed`), the most recent commit touching the file. The identical code exists byte-for-byte in the committed tree and in the F.29 working tree; `git diff` for the file is empty, so the F.29 Phase 2 work is **not** implicated.

**Why the tests never caught it.** No unit test constructs `ConnectionListViewModel`; a `Dispatchers.Main.immediate` constructor-ordering bug is only observable at runtime. The JVM suite is green (1166 `:app`, 184 `:SoftEtherClient`) with zero failures, which is exactly the blind spot.

**Evidence.** `test-evidence/dv56/crash_launch_logcat.txt` (1251 lines) and `test-evidence/dv56/CRASH_FINDING.md`, both untracked.

**Status (v1.0.57). `FIXED` and `TESTED` — see §1.6.56.** The fix moves the `init` block below every property declaration in the class (rather than moving one property above it), which makes the whole class safe by construction rather than safe by remembering. Four regression tests pin the ordering.

**Consequence (superseded by v1.0.57).** While the defect was open, no on-device verification of any feature could proceed: the app could not reach a state where a tunnel, a server list or Auto Mode exists. v1.0.57 fixed it and resumed the v1.0.56 verification list; D2, D3 and V3–V10 were then completed and are recorded in §1.6.56.

---

#### 1.6.56 LAUNCH-CRASH FIX (v1.0.57)

**The launch crash recorded in §1.6.55 is FIXED, TESTED, and device-verified on `5585fecc`.**

1. **Root cause [SOURCE CODE].** `ConnectionListViewModel` declared `_importedServers` at line 268 while its `init` block sat at line 45 and called `refreshImportedServers()`. Kotlin initialises properties in declaration order, so `init` ran between the declarations around it; `viewModelScope` uses `Dispatchers.Main.immediate`, so the launched coroutine body ran synchronously to its first suspension point and wrote a still-null field.

2. **The fix [SOURCE CODE].** The `init` block was **moved below every property declaration** in the class (now line 276, after the last property at line 264) rather than moving one property above it. This is deliberately the structural fix: it makes the *whole class* safe by construction, so the next property added anywhere above `init` is safe too, instead of relying on someone remembering to keep one particular field at the top. Behaviour is unchanged — the same two cache assignments and the same `refreshImportedServers()` call, in the same order. The `init` block now carries a comment explaining why it must stay below the properties.

3. **A class-wide audit was run, not just the one file [SOURCE CODE].** Every `.kt` file under `app/src/main/java` was scanned for a class-level property declared *after* an `init` block. **Zero hits remain** (before the fix there was one: `ConnectionListViewModel`). The other two `init` blocks in the codebase are safe and were left alone: `AutoModeViewModel`'s `init` (line 117) launches two coroutines but declares nothing after it, and `DataUtil`'s `init` (line 35) launches nothing. `StatusViewModel` has no `init` block — it uses an explicit `start()`.

4. **Tests [TEST].** New `ConnectionListViewModelInitOrderTest` (4 tests), all green:
   - `the init block appears after every property declaration` — the ordering invariant itself, scanning class-level declarations only (a first version also matched local `val`s inside function bodies and was corrected before it was allowed to pass).
   - `the init block still loads the imported servers` — the move did not drop the work.
   - `refreshImportedServers still writes the imported flow` — the crashing write still exists.
   - `the crash site carries a comment explaining the ordering` — so the next person to move the block finds out why they cannot.

   **The regression test was proven to fail against the old ordering**: with the file temporarily restored to the `ec3a73b` ordering in a scratch copy, two of the four tests failed (`… appears after every property declaration` and `… carries a comment explaining the ordering`), and both passed again once the fix was restored. The scratch copy was deleted.

5. **Why the guard is a source-shape test and not a construction test.** The JVM suite cannot construct this class: `dataUtil` is `App.instance!!.dataUtil!!` — a non-null assertion on a process singleton with no JVM seam — and `viewModelScope` needs an `AndroidViewModel`. There is no Robolectric and no `kotlinx-coroutines-test` on the classpath. The test's KDoc states this limitation explicitly rather than implying it proves more than it does. A construction-level test is the right follow-up once the class gains a seam.

6. **Full-suite verification [TEST].** Two builds, and they are **not** interchangeable. **(a) Working tree** (carries the uncommitted F.29 Phase 2 work): three `--rerun-tasks --no-build-cache` runs at `:app` **1170/0/0/1** across **93 suites** (was 1166 — exactly the +4 new tests), `:SoftEtherClient` **184/0/0/0** across 13 suites; `app-pro-debug.apk` 64,073,648 bytes, SHA-256 `7a4fc01f166462b7ff390285a6c1435e115d80fbec5ad692038e412632ff38b8`. **(b) Committed tree at `7dac3a6`** (the fix only): three runs at `:app` **1068/0/0/1** across **87 suites** (1064 + 4) and `:SoftEtherClient` **184/0/0/0** across 13 suites; `app-pro-debug.apk` **63,824,336 bytes**, SHA-256 **`9c0328de42bfc3d9a1cd5b0f0390fd23aba01166033b6e1edba615032860bd42`** — this is the APK that was installed on the device. **(c) Final HEAD `85f0747`** (fix + D1, the tree actually pushed): three runs at `:app` **1072/0/0/1** across **87 suites** (1064 + 4 + 4) and `:SoftEtherClient` **184/0/0/0** across 13 suites. No failures, no errors, one pre-existing skip, in any of the three sets. No failures, no errors, one pre-existing skip, in either set. `:app:lintProDebug` **104 errors / 178 warnings / 9 hints** — the unchanged pre-existing backlog, with **zero** findings in `ConnectionListViewModel.kt`. `versionCode 144` / `versionName 2.4.11-dev` in both.

7. **Scope.** One production file (`ConnectionListViewModel.kt`, +21/−7), one new test file (4 tests), and this specification. No schema change, no migration, no Gradle, manifest or resource change, and no behaviour change.

8. **Device verification [PHYSICAL DEVICE].** The **committed-tree** APK (63,824,336 bytes, SHA-256 `9c0328de42bfc3d9a1cd5b0f0390fd23aba01166033b6e1edba615032860bd42`) was installed with `adb install -r` on device `5585fecc` (Xiaomi 2201117TG, Android 13 / API 33), whose database was re-confirmed as Room 8 with identity hash `6f1d1b7449c3bf3e3dcefa9d34abc6f2` — identical to the committed tree's `RoomOpenDelegate(8, …)`, so the install could not trip Room's identity check. The app **launches and stays alive**: pid `18343` constant across 30 s of polling at 5 s intervals, `MainActivity` resumed, **zero** `FATAL EXCEPTION` / `ANR` / `tombstone`, and no Room or migration error. The server list loads with **261 servers**, matching the database. Developer Mode (logging) is **ON**. Settings opens and contains none of the removed controls. Evidence, per-item results and the operator-manual steps: `DEVICE_VERIFY_v1.0.57_REPORT.md` and `test-evidence/dv57/`.

9. **The v1.0.56 verification list, resumed [PHYSICAL DEVICE].** **V3 PASS** — a SoftEther TCP tunnel held for **69 s**: `additional_connect: socket fd=151 protected before connect` precedes the connect, zero "socket protect failed", `tun0` rx_bytes rose 350 → 477, `ConnectionController: Stats: sent=224881 bytes (1008 pkts), received=359426 bytes (1457 pkts)`, zero "Keepalive missed", and `ping 1.1.1.1` / `ping google.com` both returned **0 % loss**. **V5 PASS** — no Include-UDP, Block Ads, cache-time or cache-auto-clear controls; the emergency URL is the expected one; "Auto-Reconnect in Auto Mode" is on the Auto Mode screen and absent from Settings, persisted across `am force-stop` + relaunch. The single Clear-cache action was **NOT OBSERVED** rather than claimed. **V6 PASS** — the connected server is row 1 with a `PINNED` marker and no duplicate row. **V9 reached EMERGENCY** — `[ladder] deadline exceeded (45000ms)`, `servers.csv` accepted (170 records, 0 rejected, no TooLarge), list merged **261 → 170**; it did **not** grow, because the emergency set is smaller than the stored one. **V10 PASS (partial)** — `[AUTO] Protocols (priority) = SOFTETHER_TCP > SOFTETHER_UDP`, 8 s timeout, 261 servers, probe selected 209, `Trying #1 vpn961954281` → `TUNNEL_UP` in **3.535 s**. **V4(a)** captured `[ladder] order=PHYSICAL,EMERGENCY` with the tunnel down. **V7, V4(b) and V8 are `OPERATOR MANUAL STEP`**: the Auto Mode run returns as soon as it connects (the screen reads "Connected (manual)"), so Next Server has no active run to skip and produced no log line; the document picker cannot be driven from adb; and reaching the TUNNEL rung would require forcing a path, which the block forbids.

11. **Push gate — NOT MET [TEST].** At the pushed HEAD (`ac3939f`) three runs executed: two green (`:app` 1072/0/0/1, 87 suites) and one **failed** on `ReachabilityGateTest.unreachableOnEveryUsableProtocolRemovesTheServer` — `ArrayIndexOutOfBoundsException` at `ReachabilityGateTest.kt:52`, where the test's own fake probe does `calls += target` on a plain `ArrayList` while the tester fans probes out across parallel coroutines. Neither that test nor `ServerReachabilityTester.kt` was touched by this block (`git log 0c3c56e..HEAD` over both is empty), so it is a **pre-existing test race**, the same class as the two v1.0.54 flakes recorded in §1.6.51 and repaired in v1.0.55. One further run with `--no-daemon` (the block's mandated infra retry for a `BindException`) was green. **No push was made** — `origin/main` remains `0c3c56e`, HEAD is `ac3939f` with four unpushed commits. The one-line fix (a thread-safe `calls` list) needs its own authorisation. Full detail: `DEVICE_VERIFY_v1.0.57_REPORT.md` §H.

12. **What is not claimed.** No result for V7, V4(b), V8 or the V5 Clear-cache action. No claim that the earlier 10/10 clean-suite run (BLOCK v1.0.56) proves anything about this tree — that run executed on the **F.29 working tree** (1162 tests), whereas the committed-tree evidence here is the three runs at **1068/0/0/1**. No claim about real Wi-Fi/cellular handover behaviour, process-restart durability, or any improvement in physical connection success rate.

#### 1.6.57 PUSH-GATE FIX, EMERGENCY-SNAPSHOT POISONING, NEXT-SERVER-AFTER-MANUAL (v1.0.58)

1. **PART A — the flaky test is fixed.** `ReachabilityGateTest$PerProtocolProbe.calls` was a plain `mutableListOf` (an `ArrayList`) appended to from `ServerReachabilityTester.filterReachableCandidates`'s parallel fan-out (`targets.map { async(Dispatchers.IO) { … probe.probe(…) } }`, `ServerReachabilityTester.kt:394-395`, semaphore-bounded). Two concurrent appends could interleave inside `ArrayList.add` and throw `ArrayIndexOutOfBoundsException: Index 1 out of bounds for length 0` inside the test's own fake, before any assertion ran. It is now a `CopyOnWriteArrayList`. **No assertion was weakened** — every assertion in the class only asks `calls.isEmpty()`, so nothing depended on ordering. Evidence: SOURCE CODE + TEST.
2. **The audit for the same pattern found no second instance.** Every file under `app/src/test` and `SoftEtherClient/src/test` that both uses `mutableListOf` and mentions concurrency was re-scanned for this revision. The remaining `mutableListOf` uses fall into two safe groups: (a) adapter/logger fakes whose callbacks are invoked from the controller's single-threaded scope (`AutoModeControllerTest`, `AutoModeEndToEndTest`, `AutoModeLifecycleF28Test`, `AutoModeReconnectTest`, `AutoModeSlotWaitBudgetTest`, `AutoModeNetworkGuardTest`, `AutoModeEmptyListGuardTest`, `AutoModeServerCursorTest`, `VpnM24x10TaskTest`); (b) `ReachabilityTarget` recorders that are written from a `reachabilityFilter` lambda the controller calls **once per run on its own coroutine**, never from the fan-out (`AutoModeControllerTest.kt:1411`, `:1456`, `:1952`). The other `AutoConnectReachabilityProbe` implementation is stateless. Evidence: SOURCE CODE (grep + reading each site).
3. **The push happened — and the v1.0.60 row was wrong to say it already had.** The `1.0.60` changelog row ended with "The five v1.0.57 commits were then pushed fast-forward", which was **not true** at the time it was written: `origin/main` was still `0c3c56e` with **six** local commits (`0c3c56e..ef9bf99`). The row also attributed a "10/10 green at the push HEAD" to the fix, while the 10-run artefact it rests on (`/tmp/f29verify/a2.txt`, 14:13) executed in a tree extracted at **12:45**, i.e. **before** the `CopyOnWriteArrayList` fix was applied at 14:18 — so those ten runs measured the *unfixed* tree. Both statements are corrected here rather than left standing. This revision ran the gate again at the real push HEAD: **3 green committed-tree runs** (`:app` 1072/0/0/1, 87 suites; `:SoftEtherClient` 184/0/0/0, 13 suites) — 4m13s, 6m19s and 5m45s — plus one run that died in 9 s on a Gradle daemon `BindException` and, per the block's rule, was retried once; the retry hit the same `Address already in use: bind`, which is why the gate was run again from scratch instead of being counted. The push was then made: `0c3c56e..ef9bf99 main -> main`, fast-forward, `origin/main == HEAD`, no force, no tag. Evidence: TEST + git.
4. **PART B — V9 was mis-diagnosed, and the real defect is one step later.** The prior report concluded from a **log line** (`170 incoming vs 261 stored`) that "the dataset went 261 → 170". The database says otherwise, and so does the log: `Outcome.Updated.serverCount` is `usableRows.size` — the **incoming** dataset — so "Server list updated atomically: 170 servers" never described the table. Measured on device `5585fecc`: `VPNGateItem` = **261** rows before and after V9 (261 distinct IPs, 103 with an OpenVPN profile, 0 missing hostnames, `PRAGMA integrity_check = ok`). The merge was already correct: the emergency rung leaves the origin flag `false`, so `daoStore.replaceAll` takes `mergeAll`, which retains every stored row the incoming list does not mention. Evidence: PHYSICAL DEVICE + SOURCE CODE.
5. **What was actually damaged: the last-known-good snapshot.** `files/collector_vpn_servers.json` on the same device holds `{"count": 170, …}` with `savedAt = 2026-10-24 11:27:06.109` — the same instant as the table's last write — and **all 170 snapshot hosts are a subset of the 261 stored rows**, of which only **5 of the table's 40 `public-vpn-*` rows** appear. So the emergency run did not shrink the table; it **overwrote the complete snapshot with its own ~170-row excerpt**, because `ServerListUpdater.update()` promoted the incoming rows unless `fromCache` was true, and the emergency rung is not `fromCache` — it is a fresh download. The loss would have landed on the **next** run that had to fall back to the snapshot (`loadSnapshot`, `fromCache = true`), which replaces wholesale: 261 → 170, exactly the reported symptom, one refresh after the rung that caused it. Evidence: PHYSICAL DEVICE (byte-exact pull via `run-as` + host-side parse) + SOURCE CODE.
6. **The fix.** `ServerListUpdater.kt:282` adds `suppliedByEmergency`, set at `:300` when the emergency rung supplies the dataset, and the promotion branch at `:354` is now `if (fromCache || suppliedByEmergency)`. A dataset supplied by the emergency rung is still **stored** — through the identical `mergeAll` path, with stable identity, absent rows retained, and manual/imported rows preserved — but it is **never promoted to last-known-good**. Nothing is lost by not writing: the table already holds the union, a cold start is served by the stored list, and the file keeps the last *complete* observation, which is the only thing worth falling back to. `DatasetIntegrity` and the 1 MiB `EmergencySourceFetcher` cap are untouched, and a real cache/seed fallback keeps its existing replace-and-do-not-promote semantics. Evidence: SOURCE CODE.
7. **Tests — 8 new cases** (`EmergencyMergeRetentionTest`), all deterministic, no sleeps: a 261-row table + a 170-row emergency excerpt (120 overlapping, 50 new) loses **no** stored hostname and lands at 311 rows; overlapping rows refresh `lastSeenAt` while keeping `firstSeenAt`; a stored row the excerpt omits is retained untouched; a manual row survives; the emergency rung stores but writes **0** snapshots; a normal observation still writes exactly one; the V9 sequence (emergency run → snapshot fallback) keeps the complete 261-row dataset; a degraded emergency file (0 profiles against 103 stored) is still refused with `DEGRADED_DATASET` and writes nothing; and a real cache fallback still replaces while reporting `sourcedFromCache = true` and writing no snapshot. Evidence: TEST.
8. **PART C — implemented in the working tree, deliberately NOT committed, NOT shipped.** The V7 defect is real and the C1 report (`test-evidence/dv58/C1_NEXT_SERVER_REPORT.md`) documents it: a manual connection leaves `AutoModeController._state == Disconnected` (the UI's "Connected (manual)" is synthesized in `AutoModeViewModel.combineAutoModeState` from `GlobalVpnTracker`), so `skipToNextServer()` fell into its "disconnected or inactive" guard and the button was dead for the whole manual tunnel. A working implementation exists — `skipFromExternal(ip, host)` mirrors the owned-Connected branch and reuses `transitionLock`/`skipInFlight`/`overrideServers` — but it lives in **`AutoModeController.kt`, which also carries the uncommitted F.29 history work** (507 added lines), and the block forbids touching or committing F.29. Committing PART C alone would require splitting one file's hunks, including an `import` line that the F.29 diff added and PART C depends on. **It was therefore not committed and is not part of this revision's shipped behaviour.** The decision needed is: commit or shelve F.29 first, then PART C lands as its own two commits. Evidence: SOURCE CODE (working tree) + `git status`.
9. **PART D — partially performed, and it found a P0 crash.** The APK was built from the committed tree (`git archive 85b03d4`, `assembleProDebug` SUCCESSFUL, 63,825,448 bytes, SHA-256 `71e0b68c…f69d3396`, `versionCode 144` / `2.4.11-dev`) and installed over the existing data. **PASS:** launch with no crash; the removed settings are absent (no Block Ads, no Include UDP, no cache time/auto-clear); exactly one Clear cache button with the documented text; the emergency default source present and enabled on an upgraded install; the protocol-priority row reading `softether tcp → …`; a 3-row CSV paste (2 valid, 1 broken) storing exactly 2 rows (`VPNGateItem` 261 → 263) and rejecting the third, with the rows surviving a process restart. **P0 — the CSV import crashes the app:** `java.lang.IllegalStateException: Cannot invoke setValue on a background thread` at `ConnectionListViewModel.kt:370` (`materializeImported`) and at `:333` (`removeImportedServer`), because `vpnGateConnectionList` is a `MutableLiveData` and both assign `.value` inside `withContext(Dispatchers.IO)`. Reproduced twice (import 16:07:13, delete 16:09:54). The data operation completes before the crash, so the user loses the app, not the data. This is **pre-existing v1.0.54 code shipped in `origin/main`, not this block's**, and it is **not fixed here** — outside authorisation, and the block says to stop on a crash. The older `addManualServer()` path assigns outside its `withContext` (`:228`), which is why this was never seen before. Two smaller findings: a stale "…will turn off Block Ads setting when enabled" string survives on the "Use custom DNS" row, and the import accepted a hostname containing an internal space. **NOT device-verified:** V7 (PART C is not committed), the pinned row, the file-picker import, the fresh-install protocol default, the Next-Server double press, and import idempotence (the second import left the row count at 263 but the store file's mtime did not move, so the tap may not have been delivered). Device state restored: 261 rows, 0 test rows, `manual_import_servers.json` = `[]`, no setting changed. Evidence: PHYSICAL DEVICE + TEST.
10. **Counts and build.** `:app` **1080 / 0 / 0 / 1** (88 suites; baseline at `ef9bf99` was 1072 across 87 suites, **+8**), `:SoftEtherClient` **184 / 0 / 0 / 0** (13 suites, untouched). `:app:assembleProDebug` under `CI=true`; `versionCode` / `versionName` unchanged. §22 byte-identical; §1.6.2–§1.6.56 untouched; §11.7 extended (not rewritten) with the promotion rule. No tag was created.
11. **What is not claimed.** No claim that this build is healthy — the CSV import crashes the app (§1.6.57 item 9), a defect that predates this block and is not fixed by it. No device verification of V7, the pinned row, the file-picker import, the fresh-install protocol default, the Next-Server double press, or import idempotence. No claim that the emergency rung *cannot* shrink the table through some other writer: the claim is narrower and proven — the promotion site no longer promotes a supplement, and the device shows the snapshot was the damaged artefact. No claim about V8. No claim that the F.29 working tree compiles or is unaffected: it was not built, and it was not touched.



---

#### 1.6.58 IMPORT-CRASH FIX, v1.0.54 LEFTOVERS, DUPLICATE-IP AUDIT (v1.0.59)

> **Label note.** The operator's brief names this block "v1.0.59". The changelog's `1.0.59` row
> documents a *different* defect (the launch-crash `init` fix, commit `7dac3a6`); this block is the
> **import-crash fix** (commit `95163f5`) and is numbered `1.0.62` in the changelog to keep the
> sequence unambiguous. Full detail: `DEVICE_VERIFY_v1.0.59_REPORT.md`.

1. **PART A — the P0 import crash is fixed.** `vpnGateConnectionList` is a `MutableLiveData`;
   `materializeImported()` (`:370`) and `removeImportedServer()` (`:333`) both assigned
   `vpnGateConnectionList.value = …` from inside `withContext(Dispatchers.IO)`, so
   `MutableLiveData.setValue`'s main-thread assertion threw `IllegalStateException: Cannot invoke
   setValue on a background thread` **after** the data work completed — killing the app on import
   and on delete. This was first found in §1.6.57 item 9 and deliberately not fixed there for want
   of authorisation. It is now fixed with `publishLiveData(target, value, dispatcher =
   Dispatchers.Main.immediate)`, a `withContext(Dispatchers.Main.immediate)` hop that assigns on the
   main thread. It is **not** `postValue`: `postValue` defers and can coalesce two rapid publishes,
   whereas both callers return to the UI immediately and rely on "published before the caller
   continues" (`importManualCsv` hands the parse result to the summary dialog). Evidence: SOURCE CODE
   + TEST + PRIOR REPORT (§1.6.57 item 9).
2. **Audit of every LiveData write in `app/src/main`.** Eight sites on `vpnGateConnectionList`, two
   on `lastUpdatedTime`, one on `refreshNotice`, none on `isError`/`isLoading` (already `postValue`),
   plus `StatusViewModel.state` and `AutoModeViewModel.state`. Only the two fixed here ran inside an
   IO context; the rest assign after `withContext` returns, from `viewModelScope` (Main), or from
   main-thread callbacks. **No `LiveData` exists outside `:app`.** Evidence: SOURCE CODE.
3. **Tests — 7 new cases** (`LiveDataPublishThreadTest`): a thread-asserting `ArchTaskExecutor`
   delegate makes `setValue` off the designated main thread throw exactly as the device did, so the
   harness is proven rather than assumed; `publishLiveData` is then exercised from a background
   thread and shown to land the value, to finish before returning, and to propagate a failure; and a
   source-shape guard fails if any `MutableLiveData` `.value =` reappears inside a
   `withContext(Dispatchers.IO | Default)` block anywhere in `app/src/main` — it strips comments and
   string literals first (a naive version took a KDoc mention of `withContext(Dispatchers.IO)` for a
   real block and reported four false positives), and its KDoc states the limit: it cannot see a
   write hidden behind a helper or written as `setValue(…)`. Evidence: TEST.
4. **PART B — v1.0.54 leftovers closed.** **B1:** `dns_setting_hint` still read "…will turn off
   Block Ads setting when enabled" in `values`, `values-fa` and `values-vi`; the sentence is removed
   from all three (the feature was deleted in v1.0.54). **B2:** `ManualCsvImport` now rejects, per
   row, a host name or IP containing **inner** whitespace or a control character (with a reason
   naming the row); leading/trailing whitespace is still trimmed and accepted; control characters are
   replaced with a middle dot in the summary. This closes the device-found `test-a. example` row (a
   space after the dot from an input method) that no DNS could resolve and the dedupe could never
   match. **B3:** the log line `Server list updated atomically: N servers` — which read like the
   table size while `N` was the *incoming* dataset — is corrected to `N incoming, M stored`
   (`outcome.serverCount` is `usableRows.size`, never the table; `list.size()` is the stored table
   after the write). This is the exact misreading that produced the false V9 finding in §1.6.57
   items 4–5. Evidence: SOURCE CODE + TEST + PHYSICAL DEVICE.
5. **Tests for PART B — 5 new cases** (`ManualCsvImportTest`): trim-accept, inner-space-reject,
   inner-space-IP-reject, control-char-reject, and a predicate unit test. Evidence: TEST.
6. **PART C — duplicate-IP audit: the condition is NOT present in the table, and the double-dial
   vector is dormant, not absent.** The real device database was pulled byte-exactly (`run-as` +
   base64, 1,193,936 B — identical to the on-device size, `-wal` 0 B, `integrity_check = ok`) and
   queried on the host. **261 `VPNGateItem` rows, 261 distinct IPv4 values, 0 duplicate-IP groups,
   0 rows of inflation**, 0 `hostName` duplicates, 0 fields carrying padded or inner whitespace.
   Source composition: 218 API (`vpnNNNNNNNNN`), 40 emergency (`public-vpn-*`), 3 other
   (`diamondgnd`, `patito`, `idcrmn2`). No group exists, so there is **no source pair to report**.
   The IP named in the brief, `123.48.124.169`, has **one** row (`vpn122916437`, `tcpPort` 1558,
   10,007-character profile); the historical config-less twin `vpn813516587`, documented in
   `DEVICE_VERIFY_v1.0.53_REPORT.md` §5, **is no longer in the table**. **A prior revision of this
   item claimed the pair was "observed during the PART D device run" and attributed it to
   API-versus-emergency; both statements were wrong** — nothing in this block observed a duplicate,
   and the single historical instance was API-versus-API. Corrected here rather than left standing.
   **Can Auto Connect dial the same IP twice in one run? Structurally yes, dormant in practice.**
   `AutoModeEngine.defaultServers()` maps the stored list one-to-one with no IP-level
   de-duplication, and the run's only per-run guard is `attempted.add(wedgeKey(server))`, where
   `wedgeKey` → `serverKey` → `"$ip|${hostname ?: ""}"` (`AutoModeController.kt:2069-2070`, `:3005`,
   `:3017-3021`); two rows sharing an IP but not a host name are two identities and both would be
   dialled in one run, and the Next-Server skip filter (`:893-934`) is identity-keyed for the same
   reason. With 0 duplicate rows today the vector cannot fire. **No code change was made:** the
   smallest fix — de-duplicate by IPv4 in `AutoModeEngine.defaultServers()`, keeping the row that
   has a non-blank `openVpnConfigData` and the richer protocol data — lives in `AutoModeEngine.kt`,
   which is outside this block's authorisation and is one of the F.29-modified files. **Stopped and
   reported; authorisation required** (report §E). The stored-dataset identity and schema are
   unchanged. Evidence: PHYSICAL DEVICE + SOURCE CODE.
7. **PART D — device verification PASS, re-driven with logs and byte-exact counters.** The APK was
   built from `git archive HEAD` (`:app:assembleProDebug` SUCCESSFUL, 63,827,468 B, SHA-256
   `73139c01ca316dd23a7755eb8e6ccb15e003c6b0452202c4649eef56b229b96d`) and installed with
   `adb install -r` over the existing data (never uninstall/clear). Baseline: 261 rows, 0 test rows,
   `manual_import_servers.json` = `[]`. The extended CSV (2 valid + 1 `"not-an-ip"`) imported through
   the document picker reported **"Imported 2, rejected 1 — Rejected: UNKNOWN_SOURCE_TAG x2;
   INVALID_IPV4 x1"**; the store gained exactly 2 rows and the table went 261 → **263** holding
   exactly `import-a.example` and `import-b.example`. A second Import press with the same content
   re-rendered the same summary while the store file stayed at 943 B with an **unchanged mtime** —
   the tap was delivered and the write was idempotent, which resolves the v1.0.57 "may not have been
   delivered" ambiguity. Deleting one row left 1 store row and **262** table rows; deleting the
   second returned the store to `[]` and the table to **261**. A list Refresh and a `am force-stop`
   plus relaunch both kept the imported row (list showed 262). **Zero** FATAL, ANR or
   `Cannot invoke setValue on a background thread` in any step; the crash buffer was empty after
   every one. The device was restored: 261 rows, 0 test rows, `integrity_check = ok`, pushed CSVs
   and `ui*.xml` removed. The earlier pass of this block also drove the API-format CSV (2 accepted,
   0 rejected) and captured the tunnel/pinned-row screenshots. Evidence: PHYSICAL DEVICE —
   `test-evidence/dv59/` (screenshots 59_01–59_37, `ui_after_import.xml`, `ui_after_reimport.xml`,
   `ui_after_restart.xml`, `store_after_import.json`, `store_final.json`).
8. **PART E — committed-tree gate PASSED, ten for ten.** A tree extracted with `git archive HEAD`
   into `/d/dv59gate/tree` (outside the repository, so the F.29 working tree and
   `vpngate_collector.py` are absent) ran `--no-daemon --rerun-tasks` over
   `:app:testProDebugUnitTest` and `:SoftEtherClient:testDebugUnitTest` ten consecutive times under
   `CI=true`: **10/10 PASS**, `:app` **1092/0/0/1** (89 suites) and `:SoftEtherClient`
   **184/0/0/0** (13 suites) on every run — **1276 tests, 0 failures, 0 errors, 1 skipped**, 177–182 s
   per run. Runs 1–3 are also the three consecutive runs the brief requires. **Two earlier attempts
   were aborted by the environment, not by a test** — `Gradle could not start your build …
   java.net.BindException: Address already in use: bind` after 1–2 s with **zero tests executed** —
   and both are preserved and reported; the final harness classifies "infra" from the Gradle log
   rather than from leftover result files, retries it once, and does not count it. **In the ten
   counted runs zero retries were needed.** The two recently fixed flaky tests and
   `ReachabilityGateTest` were green in all ten. Evidence: TEST (`/d/dv59gate/gate3.log`).
9. **Counts and build.** `:app` **1092 / 0 / 0 / 1** (89 suites; baseline at `95163f5`'s parent was
   1080 across 88 suites, **+12** = 7 + 5), `:SoftEtherClient` **184 / 0 / 0 / 0** (13 suites,
   untouched by this block). `:app:assembleProDebug` SUCCESSFUL under `CI=true`; `versionCode` /
   `versionName` unchanged. §22 byte-identical; §1.6.2–§1.6.57 untouched; §11.7 unchanged. No tag was
   created.
10. **What is not claimed.** No claim that PART C's duplicate-IP characteristic was changed — it was
    audited only. No claim that any feature beyond the import path was re-verified on device (V3–V10
    are carried over from §1.6.56/§1.6.57). No schema, migration, Gradle, manifest, or resource
    change beyond the three `strings.xml` edits (B1). No claim the import path was exercised on any
    device other than `5585fecc`.

---

#### 1.6.59 F.29 COMMITTED, NEXT-SERVER-AFTER-MANUAL, ONE-CANDIDATE-PER-IPv4 (v1.0.61)

The uncommitted F.29 work is now history in eight reviewable commits, and the two
follow-ups the v1.0.60 triage proved independent were built on top of it. Nothing here
changes what the v1.0.59 rows say; it records what was **committed** and the three
behaviour rules that changed.

1. **The commit map.** `049c7e3` data layer (history package, `AppDatabase`, `9.json`);
   `303baaf` wiring (`App.kt`, `AutoModeEngine.kt`, and the controller's three
   constructor parameters); `2639a42` recording and equal-quality ties; `4b04cd7` the
   98 tests and the phase-2 report; `9d50166` the bounded read; `acb1a39` Next Server
   after a manual connect; `afa580b` one candidate per IPv4; `51fe1d2` the collector
   rename. F.29 is **committed, not released**: no version bump, no tag, and its five
   device behaviours stay `NOT TESTABLE` until a device run says otherwise. Evidence:
   git.

2. **The split is reviewable, and one commit does not build on its own.** `049c7e3`,
   `2639a42` and `4b04cd7` compile from `git archive <commit>` (2m38s, 2m32s, 2m36s).
   **`303baaf` does not**: the controller's new constructor parameters name
   `ServerHistoryView`, `ServerHistorySink` and `NetworkType`, whose imports arrive in
   `2639a42`, so the wiring commit fails with six `Unresolved reference` errors. The
   defect is in this split, not in the code — moving the seven-line import hunk from
   `2639a42` into `303baaf` would make every commit buildable, and that is the fix for
   a future re-split. It was **not** rewritten here because doing so needs a
   `reset`/`rebase`, both forbidden by the block. Evidence: TEST (per-commit compiles).

3. **Room 8 → 9, unchanged and additive.** Three `CREATE TABLE IF NOT EXISTS` and four
   `CREATE INDEX IF NOT EXISTS`, no `ALTER`, no `DROP`, no backfill, no foreign key.
   The four pre-existing tables are byte-identical in `9.json` against `8.json`; only
   `version` (8 → 9) and `identityHash` (`6f1d1b74…` → `6ba9665e…`) move. On a **copy**
   of the device database (261 rows, `-wal` 1,165,992 B recovered) the migration took
   **40.3 ms**, left every row count unchanged and `integrity_check = ok`, and an
   injected mid-way failure rolled back to version 8 with no partial tables. Evidence:
   PRIOR REPORT (§1.6.58, BLOCK v1.0.60) + SOURCE CODE + TEST (`Migration8to9Test`,
   9 cases).

4. **Downgrade is a hard failure, by design and by omission. [NORMATIVE]** The builder
   registers `MIGRATION_8_9` and calls **no** `fallbackToDestructiveMigration()`
   anywhere. An older build opening a v9 database therefore throws
   `IllegalStateException` at open instead of deleting the user's data. That is the
   safe direction, but it means **a downgrade crashes the app on launch**, there is no
   downgrade path, and no test covers it. Recorded so the next version bump inherits
   the warning instead of rediscovering it. Evidence: SOURCE CODE + INFERENCE (Room's
   documented behaviour).

5. **The tie-break rule and, more importantly, its non-effects. [NORMATIVE]**
   `sortedForAutoConnect(history, network)` applies the RTT pass first and the history
   pass **second**, inside the same window of candidates `byQuality` already called
   equal. Consequences that hold by construction: a server with history can never
   outrank one with better current quality; the reachability gate still runs afterwards
   and returns a subsequence, so history can never re-admit a removed endpoint;
   Protocol Priority is untouched because this orders **servers**, not protocols; and an
   **empty history map reproduces the pre-F.29 order exactly**
   (`if (history.isEmpty() || to - from < 2) return`), which is why a fresh install and
   every existing ordering test are unaffected. It is therefore **not** purely
   observational — once history exists it can change which equal-quality server is
   dialled first — but it cannot add, remove or fail a candidate. Evidence: SOURCE CODE
   + TEST (20 ordering cases; the 1,192 pre-existing cases stay green).

6. **The read is bounded at 1500 ms; the success record at 2000 ms. [NORMATIVE]**
   `loadHistory()` wraps the provider in `withTimeoutOrNull(HISTORY_READ_TIMEOUT_MS)`
   and degrades to an empty map, logging once. The bound sits at the call site rather
   than inside the production lambda in `AutoModeEngine` because that is the single
   choke point every provider passes through, and the only place a test can exercise
   it. The blocking Room query is not interruptible: the coroutine stops **waiting** at
   the suspension boundary, so the run proceeds on time even if the query returns
   later. On the other side, the success record runs inside
   `withContext(NonCancellable)` with a 2,000 ms bound, so `stop()` can be delayed by up
   to two seconds waiting for a record that has already been earned. 1500 ms is chosen
   to sit under the 2 s write bound and well under the 8 s per-attempt budget. Evidence:
   SOURCE CODE + TEST (`HistoryReadBoundTest`, 4 cases: hanging, throwing,
   fast-but-used, stop during the read).

7. **Next Server after a manual connect resumes AFTER the manual server. [NORMATIVE]**
   When a tunnel is up that this controller never dialled, its own state is
   `Disconnected`, so `skipToNextServer()` reads the live identity from
   `GlobalVpnTracker` and continues from the **following** server by `ip|hostname`,
   resolved against the list the skip re-sorts. It is explicitly **not** "the list minus
   that server, from the top": that variant re-dialled every server the user had already
   passed, and it is what the first version of `acb1a39` did — four of the five new
   tests caught it, and the commit was amended before it was ever pushed. Unknown
   identity, or a server that has left the list, still starts at the top. The skip sorts
   **without** history, so the successor is the next stored server and not whatever the
   tie-break would have promoted. A second press is coalesced by `skipInFlight`.
   Evidence: SOURCE CODE + TEST (7 cases in `AutoModeNextServerIdentityTest`).

8. **One candidate per IPv4. [NORMATIVE]** `AutoModeEngine.defaultServers()`
   de-duplicates by address before a run sees the list. The stored dataset, its identity
   and its schema are untouched — only the candidate list is. The survivor is chosen by
   a **total** order, so the result does not depend on arrival order: a usable OpenVPN
   profile while OpenVPN is among the enabled protocols, then the richer verified
   transport set, then the higher score, then the lexicographically smaller host name.
   The survivor keeps the **position of the first row** with that address, and rows with
   no address are all kept (an empty address is not an identity). The precondition is
   absent on the current device database — 261 rows, 261 distinct addresses — so this is
   defensive. Evidence: SOURCE CODE + TEST (`AutoModeEngineDedupeTest`, 8 cases) + PRIOR
   REPORT (§1.6.58 audit).

9. **The collector no longer writes the app's emergency file. [NORMATIVE]**
   `vpngate_collector.py`'s `OUT_CSV` is `collector_servers_full.csv`. The
   app-compatible `servers.csv` is produced **only** by `emergency_csv_publisher.py`,
   which owns a different schema and writes UTF-8 without a BOM. Running the collector
   previously overwrote a file the publisher maintains. Evidence: SOURCE CODE (+4/−2).

10. **Counts.** F.29 is 1,593 main lines and 1,954 test lines (98 cases); PART B adds 4
    cases, PART C 5 new ones (7 in that class), PART D 8. `:app` moves from **1092** at
    `b66e6d6` to **1209** expected; `:SoftEtherClient` stays **184**. The ten-run gate
    and its exact totals are in `DEVICE_VERIFY_v1.0.61_REPORT.md` §F. Evidence: TEST.

11. **What this section does not claim.** No device behaviour of F.29 is verified by
    being committed; the ordering change, the migration on a real upgrade, the history
    rows a real Auto Connect run writes, and Next Server from a real manual tunnel are
    claimed only once the device run records them. The downgrade path is untested.
    `303baaf` does not build alone (§2). §22 is byte-identical; §1.6.2–§1.6.58 are
    untouched. No tag was created.
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
  - **Default: 8 seconds per protocol attempt** (v1.0.52 §1.6.44 TASK B.2; the default was 12 s from v1.0.31 to v1.0.51). The value lives in the `SETTING_AUTO_MODE_TIMEOUT_SECONDS` preference, read by `DataUtil.getAutoModeTimeoutSeconds()` and clamped to **8–60 s** (v1.0.46; the floor was 5 s before that — see §1.6.39); the Settings picker offers exactly that range. `AutoModeController.DEFAULT_ATTEMPT_TIMEOUT_MS` is the same 8 s, so the documented default, the shipped default and the pure-JVM fallback are one number (they were three before v1.0.31 — see §1.6.23). 8 s is now both the default **and** the floor: a fresh install and a legacy install that stored 5–7 both read back 8.
  - If the user has enabled multiple protocols, each protocol attempt has its **own independent** timeout window (a server that fails on protocol A within `T` does not consume protocol B's `T` budget).
- **Stop condition:** If the armed tunnel wait (`awaitArmedTunnel`, §6.4) does not complete within `attemptTimeoutMs`, the attempt is declared failed and the next (server, protocol) pair is tried.
- **Fallback path / selection:** UDP-only servers (`seTcpPort=0`, `seUdpSupported=true`) must **NOT** be selected for `SOFTETHER_UDP` — per the §6.2 endpoint rule and `SERVER_SOURCE_ARCHITECTURE.md` §3.5. Selection filtering (not timeout) handles them. (This is the BUG 1 fix point.)
- **Testable:** With default Settings, each protocol attempt is bounded by ~8 s (`T`); the documented implementation must apply `attemptTimeoutMs` to **both** the armed `select` wait (`awaitArmedTunnel`, 100 % of `T`) **and** the native race (`ConnectionConfig.connectTimeoutMs`, **80 % of `T`** — see §6.8), not merely log it.

> **Cross-reference:** `DEFAULT_ATTEMPT_TIMEOUT_MS` (§10.1, §17) is the per-attempt bound. **v1.0.31 superseded the earlier 5 s figure:** the shipped default became **12 s**, and the constant — which read `25_000L` and agreed with neither figure — was corrected to `12_000L`. **v1.0.52 (§1.6.44) then moved that default to 8 s**, so the constant is `8_000L` and the default now equals the floor; the 12 s figure survives only in the historical records of §1.6.23 / §1.6.31 / §1.6.39. The timeout is plumbed **per-protocol**. The BUG 3 deviation (timeout logged but not applied to the armed `select`, and not passed to the native race) was closed in **F.28AL-AUTOCONNECT-BUG-FIX**; §6.8 records the resulting two-layer enforcement and the real per-server time bound.

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
| Default | **8 s** (v1.0.52 §1.6.44; was 12 s) |
| Range | **8–60 s**, clamped on both read and write (v1.0.46; was 5–60) |
| Settings UI | single-choice picker, values `(8..60)`, default selection `8` |
| Pure-JVM fallback | `AutoModeController.DEFAULT_ATTEMPT_TIMEOUT_MS = 8_000L` |

All three figures are the same number. They were **not** before v1.0.31 (25 s / 12 s / 5 s); §1.6.23 records the reconciliation, and §1.6.44 records the v1.0.52 move from 12 s to 8 s.

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
| 8 | No network available — checked before the first attempt and again before every attempt (v1.0.42) | `Error(ERROR_NO_NETWORK)` |

Cases 1–5 and 8 are the "system-level terminals" referenced by §6.7 R7. None of them is a server
verdict. Case 8 is the one that used to be *missing*: an offline run walked the whole list and
produced case 1, which reads as "every server failed" when in fact none was ever contacted
(§1.6.35 bullet 5).

#### 6.8.5 Pressing while `Connected` — continue from the next server (OP-R6)

A press on a **live tunnel** means "move on", not "start over". The controller records the position after the server that connected and starts a fresh run from there:

> **Superseded in v1.0.42 (§1.6.35 bullet 8) — this is a decision reversal, not a correction.**
> The continuation is no longer an integer index into the previous run's list. It is the **identity**
> of the server that connected (`ip|hostname`, the same key the wedge ledger uses), looked up in the
> list the new run resolves. The text below is kept because it is the v1.0.31 contract and the
> trade-off it names is still the trade-off; read `resumeFromServerKey` where it says
> `resumeFromIndex`, and `indexOfFirst { serverKey(it) == key } + 1` where it says
> `currentServerIndex + 1`. The bullets that follow are updated in place; the code block is not,
> so that the change is visible rather than silently edited away.

```
resumeFromIndex = currentServerIndex + 1   // v1.0.31 — superseded, see the note above
disconnectNow()                            // teardown requested synchronously
start()                                    // next run consumes the position once
```

Normative details:

- The continuation is **consumed exactly once**, at the top of the run, and cleared there. An internal restart the user did not press — the §11.13 Auto-Reconnect path calls `start()` directly — must never inherit a stale position and silently skip servers.
- A fresh press from `Disconnected`/`Error` clears the position, so a later run always starts at the top again.
- If the connected server is the **last** candidate, the run starts no attempt and terminates with `ERROR_NO_SERVER`. It does **not** wrap around to index 0. (This case is unchanged in v1.0.42: the last server is still *found* by identity.)
- If the connected server is **not found** in the newly resolved list — it left the dataset between the two runs — the run starts at the top and logs that fallback (v1.0.42). It never restarts silently.
- The position is resolved against the **current** run's resolved (filtered + sorted) list. Since v1.0.42 it is an identity, so a re-sorted list can no longer shift the start position. The v1.0.31 text accepted that drift ("pinning a server identity instead would silently skip a candidate that has since become better, and the ordering is the contract (§6.6)"); v1.0.42 overrides it, and the accepted cost is that a candidate which became better and now sorts *before* the connected server is skipped by this continuation — though not by the next fresh press.
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

and a `NATIVE_BUSY` refusal can additionally spend up to `min(5T, 60 s)` waiting **before** its single retry, so one (server, protocol) pair can cost up to `6T` in the pathological case. With the 8 s default (v1.0.52 §1.6.44; the table read 12 s before that revision):

| Case | Per protocol | Per server, 2 protocols |
|------|--------------|--------------------------|
| Healthy / slot free | `T` = 8 s | 16 s |
| Failed, slot held | `T + T` = 16 s | 32 s |
| NATIVE_BUSY then retry | `3T` = 24 s | 48 s |

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
table above is unchanged at the 8 s default (v1.0.52 §1.6.44; it read 12 s when this paragraph was written). Evidence: SOURCE CODE + PHYSICAL DEVICE.

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

**v1.0.58 (PART B) — only a complete observation may be promoted.** Acceptance and promotion are two different questions, and the emergency rung answered the second one wrongly. `DatasetIntegrity` asks "is this dataset usable?"; promotion asks "is this dataset the best thing to fall back to?". A dataset that arrived from a *fallback* (`fetchedFromCache`) was already excluded from promotion, because it is not news. The **EMERGENCY rung** was not: it is a fresh download, so it passed the `fromCache == false` test, and its ~170-row published excerpt was written over a complete snapshot. The excerpt is a subset of the catalogue by construction (`emergency_csv_publisher.py` fits the 1 MiB body cap), so promoting it silently downgrades the only fallback the app has — and the *next* run that falls back replaces the table with it, which is the 261 → 170 shrink the operator saw one refresh after the rung that caused it. The rule is therefore: **a dataset supplied by the emergency rung is stored (merged through the same retention-aware `mergeAll` as a network observation) but never promoted to last-known-good.** A real cache/seed fallback keeps its existing replace-and-do-not-promote semantics. Evidence: SOURCE CODE + PHYSICAL DEVICE (device `5585fecc`: `VPNGateItem` 261 rows, `files/collector_vpn_servers.json` `count: 170`, `savedAt` identical to the table's last write, all 170 snapshot hosts a subset of the 261, and only 5 of the table's 40 `public-vpn-*` rows present in the snapshot) + TEST (`EmergencyMergeRetentionTest`, 8 cases).

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

> **v1.0.41 update (TRAFFIC-PATH-DYNAMIC phase 1, §1.6.34).** The limitation recorded in the v1.0.29
> annotation above — that `isTunnelUsable()` is not consumed by production routing — is **closed in
> the direction that mattered**: `productionRouting()` supplies
> `GlobalVpnTracker.isTunnelUsableForRouting()` as `CollectorRoutingInput.tunnelUsable`
> (`VpnServerRepository.kt:119–132`), and `resolvePrimary()` now requires `preferVpn && tunnelUsable`
> before choosing the VPN rung (`:149–151`). An opted-in user whose tunnel is dead goes straight to
> OPEN instead of paying a VPN timeout before the fallback. The historical text above is preserved
> verbatim. Per-source path memory (§11.8, "retain useful source/path outcome information") remains
> **phase 2**. Evidence: SOURCE CODE + TEST.

> **v1.0.44 update (ROUTING-REACHABILITY-AND-EPERM-DIAGNOSTICS, §1.6.36).** The v1.0.43 device run surfaced
> two routing gaps, and this phase closes the reachable one. **M1** — the `vpn_always_prefer_vpn` opt-in that
> this section describes as the gate for the VPN rung had **no writer** anywhere in the app (the v1.0.41 change
> read it but nothing set it), so the VPN rung was dead for every user; `DataUtil` now exposes
> `getPrefersVpnForCollector()` / `setPrefersVpnForCollector()` reusing the exact key literal, and
> `SettingsScreen` gains a toggle, so the opt-in is finally user-reachable. **M2** — a VPN-rung fetch failed
> on the device with `EPERM (Operation not permitted)`; the binding site (`HttpFetcher.boundToNetwork`) now
> logs `[bind] ok` / `[bind] FAILED network=… errno=…` around the construction (control flow unchanged, `throw e`
> preserved) so the next device run can attribute the failure. The socket-open-time EPERM itself is still
> surfaced by the pre-existing "Request failed" log in `OkHttpFetcher.execute()`; its root cause is deferred.
> Evidence: SOURCE CODE + TEST.

> **v1.0.45 update (DYNAMIC-PATH-RECOVERY Phase A, §1.6.38).** The v1.0.44 Phase B device run turned the
> §11.8 requirement from a design note into a measured failure, and this phase reworks the two concepts this
> section insists must stay distinct. **Network path** is now three explicit transports — `PHYSICAL` (bound to a
> NOT_VPN + INTERNET + VALIDATED network), `TUNNEL` (bound to the app's own VPN network) and `EMERGENCY`
> (bundled source, no network) — because the previous `OPEN` was **unbound** and therefore followed the OS
> default, which IS the tunnel whenever the tunnel is up; "direct" was never actually direct. **Path selection**
> is now an automatic ladder, `PHYSICAL → TUNNEL (when usable and bound) → EMERGENCY`, with the user opt-in only
> reordering it; a blocked physical path therefore recovers through the tunnel with no manual toggle and no DB
> wipe. The opt-in's writer also moved: it now writes both the DataUtil store and the store the reader actually
> reads (§1.6.38 bullet 2). Per-source path memory (§11.8, "retain useful source/path outcome information")
> remains **phase 2**, and the `EPERM` mechanism itself remains **UNKNOWN** — the ladder's diagnostics exist to
> attribute it, not to fix it. Evidence: SOURCE CODE + TEST; device verification PENDING.

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

> **v1.0.46 annotation (§1.6.39 CHANGE A).** The bullets above describe what the *user* can configure. Since v1.0.46 a **fresh install also starts with one source already present**: on first startup `App.seedEmergencyDefaultIfNeeded(...)` inserts a single row whose URL is `R.string.default_emergency_source_url` — the published `servers.csv` location written by `emergency_csv_publisher.py` — with label `Default` and `enabled = true`. It is a normal row in `emergency_source_config`, so the user can disable or delete it with the same controls; the seed runs **once** (`SETTING_EMERGENCY_DEFAULT_SEEDED`), never overwrites an existing configuration, and a deleted row is not resurrected. Before this revision the resource was written but never read, so the table was empty on every new install and the emergency path was dead by construction. Evidence: SOURCE CODE + TEST.

> **v1.0.54 annotation (§1.6.47).** The default is still seeded exactly as described above, and it is still **not** resurrected once deleted — that rule is unchanged. What v1.0.54 adds is an explicit way back: when no row carries the default URL, the Settings section shows a **Restore default source** button that inserts it again on demand. A deliberate deletion is therefore respected, while a user who deleted the row by accident (or cannot retype a URL) is no longer stuck. Evidence: SOURCE CODE.

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

> **Ladder now consumes the emergency path (v1.0.49 — §1.6.42):** the emergency source fetch is no longer reached only through `ServerListUpdater`'s legacy `emergencyFetch` hook (which fires only when the primary returns `null`). `VpnServerRepository.refreshLocked()` now attempts it directly as the `EMERGENCY` rung of its own ladder and folds the records into the same integrity-gated pipeline as PHYSICAL/TUNNEL. The fetch itself — `EmergencySourceFetcher` (the 1 MiB / `MAX_BODY_CHARS` cap and the per-hop redirect-authority check) and `EmergencyCsvParser` — is unchanged and still the single owner of the emergency data. This note records that the requirement above is now exercised by the ladder, not only by the legacy hook; it does not alter them. Evidence: SOURCE CODE.

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

Users must be able to add servers they receive from other people — by SMS, messenger, email, Bluetooth or a file — without depending on a collector.

This is distinct from configuring an emergency URL:
- **Emergency source** = a URL from which the application retrieves a dataset.
- **Imported servers** = individual records, or a small CSV of records, supplied directly by the user.

> **v1.0.54 (§1.6.49) — this section is now NORMATIVE, not planned.** The original text described an undecided UI and an undecided format ("Do not choose the final UI placement", "Do not invent the final manual-import format"). Both decisions have been taken and implemented: the surface is **CSV import**, and the accepted formats are the two dialects the app already owns. The former per-field form (host / port / protocol / label / notes) is **removed**; rows created by it remain stored, and remain listed, toggleable and removable.

### 11.10.1 Motivating use case

A user receives VPN server details from a friend by SMS, WhatsApp, Telegram, email, Bluetooth, a file, or another communication method and wants to add the server to SoftEthergai. The two realistic transports are a **pasted text block** (SMS, messenger) and a **file attachment** (messenger, email, Bluetooth). Both are supported directly.

### 11.10.2 UI entry point (DECIDED, v1.0.54)

Settings → the servers section contains exactly two actions:
- a multiline **paste field** with an **Import** button;
- an **Import CSV file** button that opens the system document picker (`ActivityResultContracts.OpenDocument`).

Imported rows are listed in the same section with a remove action, above the legacy manual rows. There is no separate "Add Server" screen and no floating action button.

### 11.10.3 Accepted input (DECIDED, v1.0.54)

- **Pasted text** or **a file** chosen through the system picker. The picker's MIME filter is `text/csv`, `text/plain` and `*/*`: messengers label CSV attachments inconsistently, and refusing a file the user can see would be worse than parsing it and reporting *unrecognised*.
- **Two dialects only**, both already owned by an existing parser:
  - the §10 extended CSV (`SERVER_SOURCE_ARCHITECTURE.md` §10) — required columns `HostName`, `IP`, `schema_version`, `SourceTag`;
  - the VPN Gate `/api/iphone/` CSV — `#HostName,IP,...`, 15 canonical columns, optionally preceded by the `*vpn_servers` section marker.
- **Transport artefacts tolerated** (an import is not a feed, so the collector's strictness would reject real user input): UTF-8 BOM, CRLF or CR line endings, trailing horizontal whitespace, the `*vpn_servers` marker, and rows hard-wrapped by SMS or a messenger.
- **Hard limits:** 1 MiB of UTF-8 input and 2000 data rows. Over either limit the import is **refused whole**; it is never truncated, because a truncated server list is worse than a clear error. The byte cap is enforced before decoding.
- **Anything else** is reported as *unrecognised*. The app does not promise to understand arbitrary SMS or messenger formats; the two dialects above are the promise.

### 11.10.4 Entry requirements

- **Validate and explain.** The result line states how many rows were accepted and how many were rejected, plus at most three reject reasons.
- **Never echo or log the content.** A pasted profile can contain a private server address, so it never reaches a log line or the UI.
- **No duplicates.** Rows are keyed by host name; re-importing the same file updates rows instead of duplicating them, and a repeated host inside one file collapses to the last occurrence.
- **Survive refreshes.** Imported rows are re-materialised on every list build through the same `withManualRows` policy as legacy manual rows — one preservation rule, not two.
- **Record provenance.** Imported rows carry the tag `manual`, never `api` or `html`, so a user-supplied file can never outrank a canonical source in the merger.
- **Removable.** The user can delete any imported row.
- **Fail safely.** An unreadable or oversized file produces one clear failure message, never a crash.

### 11.10.5 Storage and out-of-scope

Imported records live in a JSON file store (`ManualImportStore`), **not** a Room table. `manual_server`'s unique index is `(host, port, protocol)` while the server list merges by host name, so a multi-protocol CSV row would collapse into a single protocol; widening the schema is a Room migration, which v1.0.54 was not authorised to make. No schema change, no migration, and no new §10 column result from this requirement.

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
     > **Annotation (v1.0.42, §1.6.35 bullet 8).** The text above is unchanged and remains the requirement: "the next server in the sorted list". It does **not** specify how "the next server" is identified, and v1.0.42 tightens the implementation to resolve it by server **identity** (`ip|hostname`) rather than by list position, so a re-sorted list cannot shift it. This is a tightening of the requirement, recorded here rather than presented as text that was already there.
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

## 11.14 Settings Surface and List Presentation (v1.0.54)

Normative decisions taken in v1.0.54. Each one either removes a control or fixes a default; none of them adds a user-facing option, and that is deliberate — every removed control had exactly one useful setting, so a switch was a way to be wrong.

### 11.14.1 Removed Settings controls

The following are **removed from the product**, not hidden and not deprecated. Their preference keys, consumers and strings are deleted; no code path may read them again.

| Control | Removed with | Consequence |
|---------|--------------|-------------|
| *Include UDP server* | switch, `DataUtil.INCLUDE_UDP_SERVER`, every `isIncludeUdp` consumer | UDP-capable servers are in the list by construction; the badges are rendered from the ports themselves |
| *Block Ads (Pro only)* | switch, `SETTING_BLOCK_ADS`, `AppConfig` alternate-DNS pair, three `resolvePrimaryDns()` / `resolveSecondaryDns()` branches | DNS resolves Custom-DNS-only, which is exactly what the switch produced when OFF |
| *Cache save time* / *Cache will auto clear* | `models/Cache.kt`, five `DataUtil` accessors, `SETTING_CACHE_TIME_KEY`, `DEFAULT_CACHE_TIME_INDEX`, the `setting_cache_time` array | one manual **Clear cache** action remains; `DataUtil.clearTransientCache()` deletes `cacheDir` children and the legacy marker, and nothing else |

A preference value left behind by an older install (for example `SETTING_CACHE_TIME_KEY`) is **inert**: it is never read, and a test pins that it is ignored rather than honoured.

### 11.14.2 Auto Connect protocol-priority default

`DataUtil.DEFAULT_AUTO_PROTOCOL_PRIORITY = [SOFTETHER_TCP, SOFTETHER_UDP]`, used when the stored key is **absent**. An **explicitly empty** stored profile stays empty: a user who switched every protocol off must not be silently overridden by a default. Unknown protocol ids read from storage are dropped.

### 11.14.3 Server-list pinning

The server that is currently `Connected` or `Connecting` is rendered at **index 0**. The identity is `ip|hostname` — the same key the Next-Server continuation and the wedge ledger use, never the IP alone and never the index. The move is a **view transform applied after filter and sort**; the stored dataset is not reordered, so a refresh cannot persist an accidental ordering and the retention fields (`firstSeenAt`, `lastSeenAt`, `healthScore`) are never rewritten by a display concern. A pinned row carries a visible badge.

### 11.14.4 Excluded Apps default seed

On the first run after install, every package listed in `assets/default_excluded_apps` that is **installed** on the device is added to `excluded_apps`, once (`SETTING_EXCLUDED_APPS_SEEDED`). Four rules, all normative:

1. **Installed only** — an entry that is not on the device is skipped, never inserted as a phantom row.
2. **Once per install** — a seeded app the user later removes is not re-added.
3. **Never overwrite** — an existing row keeps its stored name.
4. **No candidates, no flag** — an empty seed list does not set the one-shot flag, so a later build that ships real entries can still seed existing installs.

Package visibility is declared in the manifest as a `<queries>` element for `MAIN` / `LAUNCHER`. Since API 30 an app cannot enumerate packages it has not declared an interest in, and the failure is silent; `QUERY_ALL_PACKAGES` was rejected because it requires a separate Play Console justification and adds nothing for this use case. **Known limitation, accepted:** a background service with no launcher activity is not listed.

> **Status (v1.0.54, updated v1.0.58).** The mechanism, the rules, the tests and the manifest declaration ship complete. The package names were **DEFERRED (D1)** and are now **CLOSED** — the 12 confirmed Iranian package names were extracted from device `5585fecc` on 2026-10-24 and shipped (see §1.6.54).

---

## 11.15 F.29 — Smart Server History & Adaptive AutoConnect (Phase 2: Implemented)

**Phase 2 implemented the feature.** Every item carries exactly one of the labels the instruction requires: `USER-APPROVED REQUIREMENT`, `VERIFIED CURRENT IMPLEMENTATION`, `IMPLEMENTED`, `TESTED`, `NOT TESTABLE`, `KNOWN LIMITATION`, `FOLLOW-UP REQUIRED`. Subsections written during Phase 1 keep their original analysis and now carry the Phase 2 outcome label; §11.15.17 is the Phase 2 implementation record and is authoritative for anything that changed. Where a label is `VERIFIED CURRENT IMPLEMENTATION` it means "inspected at HEAD `1cace61` and true of the code before Phase 2", with the file and symbol named. Nothing in this section is `TESTED` unless the test actually ran — the run is recorded in §11.15.17.6 with its exact command and result.

### 11.15.1 Status and authority

| Item | Value |
|------|-------|
| Phase | 2 — full implementation, migration, tests, specification update |
| Authority | operator instruction `F.29 — SMART SERVER HISTORY & ADAPTIVE AUTOCONNECT`, Phase 2 (execution mode: implement now, no further approval round) |
| Repository state audited (Phase 1) | branch `main`, HEAD `1cace618e34a60e2c04dc0d12cee6dda7e37d9f0` (`docs(v1.0.55)`) |
| Repository state implemented (Phase 2) | branch `main`, HEAD **`0c3c56e3ffaa728128570d37a0ca16a095155e31`** — advanced from `1cace61` by one **pre-existing** commit during this phase (`chore(emergency): update servers.csv (170 servers)`, touching only `emergency-sources/servers.csv`). The Phase 2 changes are **uncommitted working-tree changes** on top of it; no commit/tag/push/release was made here (instruction §2.3) |
| Production source added | 7 files (`history/` × 6, `automode/AutoModeHistory.kt`) |
| Production source modified | 4 files (`AppDatabase.kt`, `App.kt`, `AutoModeEngine.kt`, `AutoModeController.kt`) |
| Test source added | 6 files, 98 tests |
| Schema | `9.json` exported by KSP; version 8 → 9 |
| Device | unavailable — no ADB, no install, no on-device verification |
| Automated verification | `:app:testProDebugUnitTest` **1162 tests, 0 failures**; `:SoftEtherClient:testDebugUnitTest` **184 tests, 0 failures**; `:app:assembleProDebug` **BUILD SUCCESSFUL**; `:app:lintProDebug` **104 errors — unchanged from the pre-F.29 baseline, zero findings in any F.29 file** |
| Files modified by Phase 2 | the 4 production files above, 6 test files, `app/schemas/…/9.json`, and this specification |

`USER-APPROVED REQUIREMENT`: the eight product decisions in the instruction §2 (summary + event table, stable identity derived from the real model, per-network statistics, reuse of existing quality criteria only, unlimited event retention, history-informed ordering that preserves exploration, layered failure attribution, and full implementation after this gate).

### 11.15.2 Verified current implementation (the audit)

#### 11.15.2.1 Server data model — `VERIFIED CURRENT IMPLEMENTATION`

| Fact | Evidence |
|------|----------|
| Entity `VPNGateItem`, table `VPNGateItem`, **primary key `hostName` (String)** | `models/VPNGateItem.kt` `@Entity data class VPNGateItem(@PrimaryKey val hostName: String, …)` |
| `ip` is a nullable **non-key** column | same file |
| Retention phase-1 columns `firstSeenAt`, `lastSeenAt`, `healthScore` exist | `models/VPNGateItem.kt:45-54`; added by `MIGRATION_7_8` (`db/AppDatabase.kt:162`) |
| Room version **8**; four entities (`VPNGateItem`, `ExcludedApp`, `EmergencySourceConfigEntity`, `ManualServerEntity`) | `db/AppDatabase.kt` `@Database(…)` |
| Migrations are hand-written objects `2_3 … 7_8`, wired in `App.onCreate` | `db/AppDatabase.kt` companion; `App.kt` `addMigrations(…)` |
| Migration tests use a JVM `RecordingDatabase` dynamic proxy that captures `execSQL` strings | `Migration7to8Test`, `Migration6to7Test`, `Migration5to6Test`, `SstpMigration4To5Test` |
| Schema exported to `app/schemas/vn.unlimit.vpngate.db.AppDatabase/1.json … 8.json` | directory listing |
| The only persistence boundary is `VPNGateItemDao.replaceAll` | `db/VPNGateItemDao.kt` `@Transaction fun replaceAll(items)` = `deleteAll()` + `insertAll(*unique)`; `ServerListUpdates.kt:242` comment "the one persistence boundary" |
| `replaceAll` refuses an empty list | `VPNGateItemDao.replaceAll` |

`VERIFIED CURRENT IMPLEMENTATION` — the two write paths:

| Path | Condition | What happens |
|------|-----------|--------------|
| **Network observation** | `fetchedFromCache() == false` | `mergeAll(dao, items, manual.rows, now)` (`ServerListUpdates.kt:486`): reads `dao.getAll()`, matches by `hostName.trim()`, copies `firstSeenAt` from the stored row, stamps `lastSeenAt = now`, recomputes `healthScore`, **retains stored rows the feed no longer mentions**, then `dao.replaceAll(merged)`. |
| **Fallback** (snapshot / stored list / bundled seed) | `fetchedFromCache() == true` | `dao.replaceAll(withManualRows(items, manual.rows))` — no stored-row read, no carry-forward of any per-row state. |

`VERIFIED CURRENT IMPLEMENTATION` — identity is **not** one concept in this codebase:

| Layer | Key | Evidence |
|-------|-----|----------|
| Room / merge | `hostName.trim()` | `ServerListUpdates.kt:502-504`, `VPNGateItem.kt` `@PrimaryKey` |
| Collector merger (dedupe) | `if (VpnUtil.validIp(ip)) ip else host` | `merger/VpnServerMerger.kt:258-267` ("Dedupe by IP→hostname and merge duplicates") |
| Auto Mode (cursor, wedge ledger, skip) | `"$ip|${hostname ?: ""}"` | `AutoModeController.serverKey` `:2520` |
| v1.0.54 list pin | `ip|hostname` | §11.14.3 |

**Consequence for F.29** (`PROPOSED DESIGN` input): history must attach to the Room key, because that is the only key that is (a) declared unique by the database, (b) the key `mergeAll` already uses to decide "this is the same server", and (c) the only one that survives a refresh by construction. The other two keys are derived views of it and must not become a second history key.

#### 11.15.2.2 Connection lifecycle — `VERIFIED CURRENT IMPLEMENTATION`

| Step | Symbol / line | Notes |
|------|---------------|-------|
| Candidate build | `AutoModeEngine.ensure` → `toCandidate()` | `AutoModeCandidate` carries `hostname`, `ip`, speed/ping/sessions/score, per-protocol ports + `openVpnConfigAvailable`, `activeRttMs` (**runtime only, never persisted**), `payload` |
| Ranking | `AutoModeController.byQuality` `:2626` | `speed > 0` desc, `speed` desc, `ping > 0` desc, `ping` asc, `sessions` asc, `score` desc |
| RTT tie-break | `sortedForAutoConnect()` `:1594` + `applyRttTieBreak()` `:1613` | a **stable insertion pass inside each run of equal-quality candidates**; a candidate with no RTT is a barrier — never moved, never moved past. Written this way because a non-transitive comparator makes TimSort throw on real lists (`:1580-1592`) |
| Reachability gate | `ServerReachabilityTester.filterReachableCandidates` | `FILTER_ONLY`: returns a **subsequence** of the ranked input; `UNKNOWN` keeps the server; an empty verdict only removes everything when every endpoint was definitively `UNREACHABLE` (`:1791-1808`) |
| Attempt | `for (protocol in supported)` `:1917` | `armTunnelWatch` **before** `connect` (`:1936`) |
| Outcome | `adapter.awaitArmedOutcome(protocol, attemptTimeoutMs())` `:1969` | `AttemptOutcome` = `TUNNEL_UP`, `NATIVE_BUSY`, `TIMED_OUT`, `NATIVE_ERROR`, `MISSING_CONFIG` (`:384`) |
| Success | `if (connectedProtocol != null)` `:2408-2432` | `wedgeTracker.clear(key)`, `onSuccess(server, connectedProtocol)`, `rememberRemaining`, `setState(Connected)`, `startConnectedWatcher`, `return` — **the single authoritative success point** |
| Server abandoned | `[AUTO] Connection failed: …` `:2434` | reached only when the protocol loop ended with `connectedProtocol == null` — the single "gave up on this server" point |
| Run terminal | `[AUTO] All compatible servers failed` → `AutoModeState.Error(ERROR_NO_SERVER)` `:2437` | no automatic loop back to #1 |
| Post-connect loss | `reportArmedTunnelLoss(reason, dropped)` `:1044` | the single funnel for all three detectors: OS revoke (`TunnelStateWatcher.onTunnelLost`), 1 s passive poll (`GlobalVpnTracker.isConnected`), liveness watchdog (`TunnelLivenessWatchdog.DEFAULT_SILENCE_TIMEOUT_MS = 15_000`) |
| Permission failure | `catch (e: VpnPermissionMissingException)` `:1940` | stops the whole run with `AutoModeState.Error(ERROR_VPN_PERMISSION)` — **distinguishable**, so it can be attributed to the local layer rather than to a server |
| Cancellation | `CancellationException` rethrown at `:1946-1951`; `skipRequested` at `:1973`; teardown under `withContext(NonCancellable)` `:1958` | a cancelled connect is explicitly *not* a protocol failure (v1.0.52 comment) |
| Existing telemetry | `AutoModeLogStore` — in-memory ring, `MAX_LINES = 2000`, **not persisted**; `onSuccess` writes `DataUtil.AUTO_LAST_SUCCESS_HOST` / `AUTO_LAST_SUCCESS_PROTOCOL` and `AutoModeRun.lastSuccess` | see below |

`VERIFIED CURRENT IMPLEMENTATION` — **the only persisted connection history today is write-only.** `AUTO_LAST_SUCCESS_HOST` and `AUTO_LAST_SUCCESS_PROTOCOL` are written at `AutoModeEngine.kt:70-71` and read **nowhere** in `app/src/main` or `app/src/test`; `AutoModeRun.lastSuccess` (`AutoModeEngine.kt:266`) is assigned and never read. There are no counters, no timestamps, no network type, no protocol totals, and no reader that could influence ordering. **History-informed selection does not exist today.**

`VERIFIED CURRENT IMPLEMENTATION` — `onSuccess` is a constructor-injected callback (`AutoModeController.kt:44`, default no-op), so a recorder can be wired without changing the controller's testability.

#### 11.15.2.3 Network-type detection — `VERIFIED CURRENT IMPLEMENTATION`

| Component | What it answers | Evidence |
|-----------|-----------------|----------|
| `GlobalVpnTracker.currentPhysicalNetwork()` | the `Network` object of a **NOT_VPN** network with `INTERNET` + `VALIDATED`, preferring `TRANSPORT_WIFI`, then `TRANSPORT_CELLULAR`, then any | `GlobalVpnTracker.kt:540-555` |
| `GlobalVpnTracker.isAnyNetworkAvailable()` | `cm.activeNetwork != null` — "is there any network at all"; fails open when uninitialised | `:490-495` |
| `DataUtil.isOnline(context)` | capability **or** any of WIFI / CELLULAR / ETHERNET / VPN | `utils/DataUtil.kt:677-699` |
| `AndroidConnectionAdapter` own-tunnel probe | enumerates `cm.allNetworks` and compares `NetworkCapabilities.ownerUid` (API 29+ guard; this is the one `NewApi` lint error at `:653`) | `AndroidConnectionAdapter.kt:634-651`; `OwnedVpnDecision.kt:20-25` |
| Manifest | `ACCESS_NETWORK_STATE` is declared | `AndroidManifest.xml:6` |

`VERIFIED CURRENT IMPLEMENTATION`: **no component classifies the transport into a Wi-Fi / cellular label.** Every existing consumer asks a boolean question ("is any network available", "is this a NOT_VPN network", "is this my own tunnel"). There is no SSID access anywhere and no location permission is requested. Transport-level classification is available from `NetworkCapabilities` without any additional permission; SSID would require `ACCESS_FINE_LOCATION` and is explicitly **out of scope**.

#### 11.15.2.4 Failure attribution — `VERIFIED CURRENT IMPLEMENTATION`

The controller already distinguishes five outcomes, and the adapter already publishes a lossy cause mapping for the log:

| `AttemptOutcome` | Controller behaviour | Wedge recorded? | `AutoConnectAttemptClass` (`AndroidConnectionAdapter.kt:954-995`) |
|------------------|---------------------|-----------------|------------------------------------------------------------------|
| `TUNNEL_UP` | success funnel `:2408` | no — ledger cleared | `CONNECTED` |
| `NATIVE_ERROR` | `disconnect()`, **next protocol on the same server** `:2324-2333` | no | `ENDPOINT_REJECTED` (refused port / TLS / protocol / auth — one class, because the watcher does not forward the stack message) |
| `TIMED_OUT` | `:2303` | **yes** | `TIMEOUT_NO_VERDICT` ("no answer", not "proven dead") |
| `NATIVE_BUSY` | own-tunnel rescue, `NATIVE_BUSY_WEDGE_THRESHOLD = 2` `:2026`, `:485` | only on the 2nd refusal | `REFUSED_START` ("says nothing about the endpoint") |
| `MISSING_CONFIG` | rotate to the next protocol, no wedge, no wait `:1998` | no | `MISSING_CONFIG` ("a defect in the data, never in the endpoint") |
| `VpnPermissionMissingException` | terminal `ERROR_VPN_PERMISSION` `:1940` | no | — (thrown, not an outcome) |
| user skip / stop | `ATTEMPT` abandoned `:1973` / cancellation rethrow `:1946` | no | — |
| no network | `ERROR_NO_NETWORK` guard (v1.0.42) | no | — |

`VERIFIED CURRENT IMPLEMENTATION`: attribution exists **for control flow**, not for persistence. Nothing is written down, so a timeout and a rejected endpoint are indistinguishable after the run ends.

#### 11.15.2.5 Quality-rejection logic — `VERIFIED CURRENT IMPLEMENTATION`

This is the exact answer to instruction §2.4/§11 ("first locate and document the current quality-rejection logic and its exact conditions"):

| Mechanism | Exact condition | Where | Rejects what |
|-----------|-----------------|-------|--------------|
| **Protocol capability gate** | `SOFTETHER_TCP`: `seTcpPort > 0`; `SOFTETHER_UDP`: `(seUdpSupported \|\| seUdpPort > 0) && seTcpPort > 0`; `OPENVPN_TCP`/`_UDP`: `openVpnConfigAvailable && port > 0`; `L2TP_IPSEC`: `l2tpSupported` **and** not automatable; `MS_SSTP`: `sstpSupported && sstpConnectPort > 0` | `AutoModeController.supports()` `:2635-2679`, `isUsableFor` `:1484`, `isAutomatable` `:1499` | a **protocol** for a server, before any attempt |
| **Dataset integrity gate** | candidate size ≥ 10; candidate ≥ 50 % of stored size; per-protocol coverage ≥ 50 % when the stored count ≥ 20; profile coverage ≥ 50 % when the stored count ≥ 20 | `repository/DatasetIntegrity.kt:180-195`, `evaluate()` `:253` | a whole **refresh**, before it is written |
| **Reachability gate** | keep the candidate unless **every** usable endpoint was definitively `UNREACHABLE`; `UNKNOWN` is kept | `ServerReachabilityTester`; `AutoModeController.kt:1791-1808` | a **candidate**, for this run only |
| **Ranking** | `byQuality` (`:2626`) then the RTT insertion pass (`:1613`) | same | nothing — it only orders |
| **Wedge exclusion** | `countFor(key) >= EXCLUSION_THRESHOLD` (= 2), process-scoped, in-memory, cleared by a verified tunnel | `WedgeTracker` `:2741-2763` | a **server**, for the rest of the process |
| **`healthScore`** | `0` never-observed / `25` unmeasured / `50` one axis / `100` both `score > 0 && ping > 0` | `ServerListUpdates.retentionHealthScore:545` | nothing — **no production reader** |

`VERIFIED CURRENT IMPLEMENTATION` and the single most important negative finding of this audit: **there is no post-connect quality rejection.** Nothing in the codebase tears down an established tunnel because it is slow, and `TunnelHealth` / `TunnelLivenessWatchdog` are **liveness** signals (rx advancing, TUN EIO, 15 s of silence), not quality measurements. A connection that establishes is never later "rejected for quality". Instruction §11's question — *when can existing quality logic reject an established connection* — therefore has the answer **"never, today"**.

### 11.15.3 Stable server identity — `IMPLEMENTED` `TESTED`

**Rule:** the history identity is the **Room primary key, `hostName.trim()`** — the same normalisation `mergeAll` already uses (`ServerListUpdates.kt:502`). No new key, no hashing, no concatenation.

Rationale: it is the only key the database enforces as unique, the only key `mergeAll` treats as "same server", and the only one that cannot be duplicated by a refresh. `ip` is a **mutable attribute**, not an identity, and `"$ip|$hostname"` is a *derived view* used for in-run bookkeeping — it must not become a second history key, because the same server would then own two histories after a single IP change.

The six cases the instruction requires, answered against this rule:

| Case | Behaviour under `hostName` identity | Status |
|------|-------------------------------------|--------|
| **IP changes, hostname stays** | Same identity. History preserved. The row is the same row; `ip` is overwritten by the next observation. | `PROPOSED DESIGN` |
| **Hostname changes, IP stays** | **New identity. History does not follow.** The old row is retained by `mergeAll` (add-only) with its history intact; the new hostname starts with no history. Accepted limitation — see **OD-2**. | `PROPOSED DESIGN` + `OPEN DECISION` |
| **Same server arrives from another source** | `VpnServerMerger.mergeRecords` collapses by IP→hostname *before* storage, so exactly one row and one identity reach the database. | `VERIFIED CURRENT IMPLEMENTATION` |
| **Disappears, returns later** | The row is retained (`mergeAll` never prunes), so the identity is unchanged and history is intact. If the row was lost (fallback-path replace), the returning hostname is matched against the surviving summary row, so history still resolves. | `PROPOSED DESIGN` |
| **Duplicates merged** | One row, one identity; the merge is by hostname so two duplicate hostnames cannot both survive. | `VERIFIED CURRENT IMPLEMENTATION` |
| **Endpoint shared by several protocol entries** | Identity is the **server**, never the endpoint. The protocol is an *attribute of an event*, and one server can own many protocol-attributed events. | `PROPOSED DESIGN` |

`ASSUMPTION` A1: VPN Gate hostnames are stable in practice (they are operator-chosen and change rarely). If that assumption fails, the cost is a lost history for that server, never a wrong attribution to a different server.

`ASSUMPTION` A2: `hostName` is non-blank for every stored row. A blank identity is treated as "no identity": the recorder skips the write rather than writing a row that all blank hosts would share.

### 11.15.4 Summary model — `IMPLEMENTED` `TESTED`

**Form:** a **separate 1:1 table**, `server_history_summary`, keyed by `hostName`, **not** new columns on `VPNGateItem`.

Why the deviation is proposed (instruction §2.1 says "summary/history fields associated with each server record"; this keeps the association and changes only its storage location): `replaceAll` is `deleteAll` + `insertAll` on **every** write, and the fallback path has no previous row to copy from (§11.15.2.1, G-F29-1). Columns on `VPNGateItem` would be silently zeroed by any snapshot-served refresh — the exact "a refresh must not reset history" rule the instruction forbids breaking. A 1:1 table is immune by construction and keeps `VPNGateItem` — the entity that is fully rewritten on every refresh — narrow. This is **OD-1** and requires approval because it changes the *form* of an approved decision.

| # | Field | 1 Meaning | 2 Update trigger | 3 Kind | 4 Derivable from events? | 5 AutoConnect-critical? | 6 Init for existing rows | 7 Preserved across refresh |
|---|-------|-----------|------------------|--------|--------------------------|-------------------------|--------------------------|-----------------------------|
| 1 | `hostName` | identity (PK) | first write | key | — | yes (lookup) | no row created | n/a — never touched by `replaceAll` |
| 2 | `firstObservedAt` | when this identity was first seen **by history** | `SERVER_FIRST_SEEN` | timestamp | yes (min event ts) | no (display) | `0` = unknown | unaffected |
| 3 | `successCount` | verified tunnels ever established | `CONNECTED` | cumulative | yes | **yes** | `0` | unaffected |
| 4 | `failureCount` | times the **server** was abandoned for a server-attributable reason | `SERVER_ABANDONED` with `SERVER_REJECTED` | cumulative | yes | **yes** | `0` | unaffected |
| 5 | `timeoutCount` | attempts that produced no verdict | `PROTOCOL_ATTEMPT` with `TIMEOUT_UNATTRIBUTED` | cumulative | yes | no (diagnostic; see OD-3) | `0` | unaffected |
| 6 | `consecutiveFailures` | server-attributable failures since the last success | `+1` on #4; `= 0` on `CONNECTED` | derived counter | yes (walk events backwards) | **yes** | `0` | unaffected |
| 7 | `lastSuccessAt` | epoch ms of the last verified tunnel | `CONNECTED` | timestamp | yes | **yes** (recency order) | `0` = never | unaffected |
| 8 | `lastFailureAt` | epoch ms of the last server-attributable failure | #4 | timestamp | yes | no (display) | `0` = never | unaffected |
| 9 | `lastFailureReason` | classification constant of #8 | #4 | enum text | yes | no (display) | `''` | unaffected |
| 10 | `lastSuccessProtocol` | `AutoModeProtocol.id` of the last success | `CONNECTED` | enum text | yes | no (see OD-4) | `''` | unaffected |
| 11 | `lastSuccessNetwork` | `NetworkType` of the last success | `CONNECTED` | enum text | yes | **yes** (tier 0) | `''` | unaffected |
| 12 | `wifiSuccessCount` | verified tunnels established on Wi-Fi | `CONNECTED`, `networkType = WIFI` | cumulative | yes | **yes** (tier 0) | `0` | unaffected |
| 13 | `wifiFailureCount` | server-attributable failures on Wi-Fi | #4 with `WIFI` | cumulative | yes | no | `0` | unaffected |
| 14 | `cellularSuccessCount` | verified tunnels on mobile data | `CONNECTED`, `CELLULAR` | cumulative | yes | **yes** (tier 0) | `0` | unaffected |
| 15 | `cellularFailureCount` | server-attributable failures on mobile data | #4 with `CELLULAR` | cumulative | yes | no | `0` | unaffected |
| 16 | `otherSuccessCount` | verified tunnels on ETHERNET / OTHER / UNKNOWN | `CONNECTED`, anything else | cumulative | yes | **yes** (tier 0 for that bucket) | `0` | unaffected |
| 17 | `otherFailureCount` | server-attributable failures in that bucket | #4 | cumulative | yes | no | `0` | unaffected |
| 18 | `qualityRejectionCount` | established connections later rejected for quality | **no producer exists** (§11.15.2.5) | cumulative | would be | no | `0` | unaffected |
| 19 | `lastAttemptAt` | epoch ms of the last protocol attempt | `PROTOCOL_ATTEMPT` | timestamp | yes | no (display) | `0` | unaffected |

Companion table for the protocol axis — `server_history_protocol` (PK `hostName`, `protocol`): `successCount`, `failureCount`, `lastSuccessAt`. It answers the operator's "how often did each protocol succeed or fail" and "which protocol succeeded" questions **without** widening the summary, and it is **not** read by AutoConnect (Protocol Priority stays the sole authority on protocol order — OD-4).

`PROPOSED DESIGN` — duplicate state is avoided deliberately:
* `firstSeenAt` / `lastSeenAt` stay where they are, on `VPNGateItem`, and are **not** duplicated. The only exception is `firstObservedAt` (#2), which exists precisely because the `VPNGateItem` copy is destroyed by the fallback path (G-F29-1); it is the durable copy, and the list UI keeps reading the column.
* `lastAttemptAt`, `consecutiveFailures` and every counter are derivable from the event table. They are stored anyway, for the one reason the instruction allows: AutoConnect must decide **without loading history** (§14). The summary is the read model; the events are the write model.
* Nothing stores a score, a weight, a decayed value or a synthetic "quality number". `healthScore` is left exactly as it is — unread.

The six operator questions, mapped:

| Operator question | Answered by |
|-------------------|-------------|
| Has this server **ever** connected successfully? | `successCount > 0` |
| Is it newly discovered or previously known? | `firstObservedAt == 0` (unknown) vs `> 0`; and `VPNGateItem.firstSeenAt > 0` for "known before F.29" |
| Which network types produced successes? | `wifiSuccessCount` / `cellularSuccessCount` / `otherSuccessCount` |
| How many successes on Wi-Fi vs mobile data? | #12 vs #14 |
| Which protocol succeeded? | `lastSuccessProtocol`, and `server_history_protocol.lastSuccessAt` per protocol |
| How often was a successful server later rejected for quality? | `qualityRejectionCount` — **reserved, no producer** (OD-5) |

### 11.15.5 Event model — `IMPLEMENTED` `TESTED`

Table `server_history_event`:

| Column | Type | Purpose |
|--------|------|---------|
| `id` | INTEGER PK AUTOINCREMENT | event id |
| `hostName` | TEXT NOT NULL | **identity; deliberately NOT a foreign key** (a FK would cascade-delete history the moment `replaceAll` runs) |
| `ip` | TEXT NULL | endpoint **at event time** — provenance, never identity |
| `eventType` | TEXT NOT NULL | taxonomy below |
| `outcome` | TEXT NOT NULL | `SUCCESS` / `FAILURE` / `NEUTRAL` |
| `networkType` | TEXT NOT NULL | `WIFI` / `CELLULAR` / `ETHERNET` / `OTHER` / `UNKNOWN` |
| `protocol` | TEXT NULL | `AutoModeProtocol.id`, or NULL when the event is not protocol-scoped |
| `attemptId` | TEXT NULL | the run/attempt correlation id when one is safely available |
| `durationMs` | INTEGER NULL | attempt duration, measured in memory |
| `failureReason` | TEXT NULL | classification constant (§11.15.6) |
| `qualityResult` | TEXT NULL | **reserved** — always NULL until OD-5 is decided |
| `createdAt` | INTEGER NOT NULL | epoch ms, stamped by an injectable clock |

Taxonomy — **bounded to six types, four of which have a producer today**:

| Event type | Outcome | Emitted at | Volume |
|------------|---------|------------|--------|
| `SERVER_FIRST_SEEN` | `NEUTRAL` | first observation of an identity (`mergeAll` path, once per identity, ever) | ≤ dataset size, one-off |
| `PROTOCOL_ATTEMPT` | `SUCCESS` / `FAILURE` / `NEUTRAL` | one per protocol attempt, at its terminal (`:1969` outcome, `:1940` permission, `:1973` skip) | ≤ 1 per protocol tried |
| `SERVER_ABANDONED` | `FAILURE` only when the reason is `SERVER_REJECTED`, otherwise `NEUTRAL` | `:2434`, the single "gave up on this server" point | ≤ 1 per server per run |
| `CONNECTION_LOST` | `NEUTRAL` (attribution uncertain — see §11.15.6) | `reportArmedTunnelLoss` `:1044`, one of three detectors | ≤ 1 per established tunnel |
| `QUALITY_REJECTED` | — | **reserved: no producer exists** | 0 |
| `ATTEMPT_CANCELLED` | `NEUTRAL` | stop / skip while an attempt is in flight | ≤ 1 per cancelled attempt |

`PROPOSED DESIGN` — three deliberate omissions, each an `OPEN DECISION` where it deviates from the instruction's candidate list:

1. **No `ATTEMPT_STARTED` event** (OD-6). The terminal event carries `durationMs`, so the start time is `createdAt - durationMs`. Recording both would double the write volume to preserve a number that is already stored.
2. **No `SERVER_OBSERVED` event** (OD-7). A refresh observes every server in the dataset; emitting one event per server per refresh would write ~1 000–2 000 rows every few hours for no diagnostic value. `lastSeenAt` on `VPNGateItem` already records "observed again".
3. **No event per log line.** The existing `AutoModeLogStore` (2 000-line in-memory ring) stays exactly as it is: it is the human-readable trace, not the record of outcomes.

`PROPOSED DESIGN` — consistency and duplicate prevention:
* One event + its summary mutation are written in **one Room `@Transaction`** (`recordAttempt(...)`), so a crash between them is impossible and a counter can never drift from its events.
* Duplicate delivery is prevented by a **per-attempt guard in the controller**, not in the DAO: the success funnel `return`s immediately (`:2431`), and each protocol attempt reaches exactly one terminal branch. The recorder is additionally idempotent on `(eventType, attemptId, protocol)` for the `CONNECTED` event, so a future second call site cannot double-count a success.
* `NATIVE_BUSY`, `MISSING_CONFIG` and `TIMED_OUT` never increment `failureCount`, so the wedge ledger's semantics (`:2584-2596`) are not duplicated into the summary.
* No credentials, no `.ovpn` bytes, no hostnames of user-imported servers beyond the identity itself, no SSIDs, no user identifiers.

### 11.15.6 Failure attribution and quality-rejection recording — `IMPLEMENTED` `TESTED`

| Source evidence | `failureReason` | `outcome` | Increments `failureCount`? | Layer blamed |
|-----------------|-----------------|-----------|----------------------------|--------------|
| `TUNNEL_UP` | — | `SUCCESS` (`CONNECTED`) | no (resets `consecutiveFailures`) | — |
| `NATIVE_ERROR` (per protocol) | `SERVER_REJECTED` | `FAILURE` | no — per-protocol only; only a `SERVER_ABANDONED` with this reason counts | server / protocol |
| `TIMED_OUT` | `TIMEOUT_UNATTRIBUTED` | `FAILURE` | **no** — `timeoutCount` only | **unknown** |
| `NATIVE_BUSY` | `NATIVE_SLOT_BUSY` | `NEUTRAL` | no | local (own unresolved native operation) |
| `MISSING_CONFIG` | `DATA_MISSING_CONFIG` | `NEUTRAL` | no | data |
| `VpnPermissionMissingException` | `PERMISSION_DENIED` | `NEUTRAL` | no | local |
| user skip / `stop()` | `USER_CANCELLED` | `NEUTRAL` | no | local |
| `ERROR_NO_NETWORK` guard | `NETWORK_UNAVAILABLE` | `NEUTRAL` | no | network |
| OS revoke (`onTunnelLost`) | `LOST_EXTERNAL` | `NEUTRAL` | no | **unknown** (could be the network, the OS, or the server) |
| 1 s passive poll | `LOST_PASSIVE` | `NEUTRAL` | no | **unknown** |
| liveness watchdog, 15 s silence | `LOST_SILENT` | `NEUTRAL` | no | **unknown** |
| anything not distinguishable at the seam | `UNKNOWN` | `NEUTRAL` | no | unknown |

Three rules this table enforces, all `USER-APPROVED REQUIREMENT`:

1. **A timeout is not proof about the server.** `TIMED_OUT` is recorded with an explicit unknown attribution and never penalises the server — matching the adapter's own words (`AndroidConnectionAdapter.kt:966-969`: "A silent or filtered endpoint looks exactly like a dead one, so this is 'no answer', not 'proven dead'").
2. **`NATIVE_BUSY` is never the server's fault** — the server was never contacted (`:958-963`).
3. **A post-connect loss is `NEUTRAL`, not a server failure.** The app cannot distinguish "the server died" from "the network changed" from "the OS revoked the tunnel"; inventing certainty here would penalise good servers for the user walking into a lift.

`OPEN DECISION` **OD-5 — quality rejection has no trigger.** The instruction requires recording "successful tunnel followed by quality rejection" and asking how often a successfully connected server was later rejected for quality. In this codebase **no such event can occur** (§11.15.2.5): nothing disconnects a healthy tunnel for being slow. Two options, and the choice belongs to the operator because §2.4 forbids inventing a criterion:

* **(a) Reserve only** (recommended): ship the `QUALITY_REJECTED` event type and the `qualityRejectionCount` column with no producer. Nothing is fabricated, no threshold is invented, and the moment a quality criterion is approved it has a place to land.
* **(b) Define a criterion now**: e.g. "a tunnel whose measured throughput stays below X for Y seconds is rejected". This is a **new quality threshold**, which §2.4 explicitly forbids in this phase, and it would change product behaviour.

`ASSUMPTION` A3: if a future quality criterion is added elsewhere (for example inside the tunnel watchdog), the recorder can attach to the same funnel (`reportArmedTunnelLoss`) without a schema change, because `qualityResult` and the event type already exist.

### 11.15.7 Network classification — `IMPLEMENTED` `TESTED` (`NOT TESTABLE` for the Android read)

**Semantics, decided before any code (instruction §9 requirement):**

* The recorded type is the **transport of the physical network in use when the attempt started**, classified as `WIFI`, `CELLULAR`, `ETHERNET`, `OTHER`, or `UNKNOWN`.
* **`TRANSPORT_VPN` is never the answer.** The VPN transport identifies the tunnel, not the network underneath it. Classification therefore enumerates candidate networks, excludes any carrying `TRANSPORT_VPN`, and reads the transport of the remainder — the same exclusion `GlobalVpnTracker.currentPhysicalNetwork()` already performs (`:544-549`), reused rather than reimplemented.
* Preference order when several physical networks exist: `WIFI` → `ETHERNET` → `CELLULAR` → `OTHER`. Deterministic, and the same order the collector already prefers (`:550-554`).
* **A snapshot, not a subscription.** The type is read once, at attempt start, and stored on the event. A network change *during* an attempt is **not** retro-attributed: the event keeps the type it started on, because that is the network the attempt was actually made over. (A change that kills the attempt is recorded as `CONNECTION_LOST` with unknown attribution, which is the honest description.)
* **Never inferred** from IP address, country, server endpoint, port, or the connection result.
* **`NET_CAPABILITY_VALIDATED` is not required.** A captive portal is still the network the attempt used; requiring validation would silently turn real attempts into `UNKNOWN`. `INTERNET` capability is the floor.
* **`UNKNOWN` when**: no `ConnectivityManager`; no non-VPN network with `INTERNET`; the service is unavailable; or the only visible network is the app's own tunnel. `UNKNOWN` is a first-class bucket with its own counters (#16/#17), never folded into Wi-Fi or cellular.
* **No SSID, ever.** It would require `ACCESS_FINE_LOCATION` and would record a personal identifier. Not in scope.
* Pure core: `classify(transports: Set<Int>): NetworkType` over an injected set, so the mapping is JVM-testable with no Android framework — the same pattern `routingUsableFromSignals` already uses (`GlobalVpnTracker.kt:460-462`).

`NOT TESTABLE` without a device: whether a real `ConnectivityManager` reports the expected transports on a real handset (Wi-Fi, cellular, and a Wi-Fi→cellular handover mid-attempt). The pure mapping is JVM-testable; the wiring is not.

### 11.15.8 Adaptive AutoConnect ordering — `IMPLEMENTED` `TESTED` (deviates from the Phase 1 barrier proposal — see §11.15.17.3)

**Where it goes:** a **second stable insertion pass inside each run of equal-quality candidates**, immediately after `applyRttTieBreak`, inside the existing `sortedForAutoConnect()` loop (`AutoModeController.kt:1594-1606`). Nothing else about the pipeline changes:

```
all → withActiveRtt → sortedWith(byQuality) → [per equal-quality run: RTT pass → HISTORY pass]
    → probeTargets → reachability gate (FILTER_ONLY, subsequence) → servers
```

This placement is not a convenience; it is what makes every constraint in instruction §12 hold **structurally** rather than by discipline:

| Requirement | Why it holds |
|-------------|--------------|
| Existing quality ordering stays authoritative | history only ever permutes candidates the comparator already declared **equal** |
| Reachability gate cannot be bypassed | the gate runs **after** ranking and returns a subsequence — a historical success cannot resurrect a server the probe removed |
| `UNKNOWN` ≠ `UNREACHABLE` | history is never consulted for reachability, and an unknown-history candidate is a barrier (below) |
| Protocol Priority stays authoritative | the history pass orders **servers**, never protocols |
| Deterministic and testable | pure function of (list, summary map, network type); same input → same output |
| No invented numbers | every key is an ordinal comparison or a stored counter; the only predicate is `> 0` |

**The comparator.** History rank is a **tier**, then two stored timestamps/counters as tie-breaks:

| Tier | Condition | Meaning |
|------|-----------|---------|
| 0 | `wifiSuccessCount > 0` (on Wi-Fi), `cellularSuccessCount > 0` (on cellular), `otherSuccessCount > 0` (otherwise) | has worked **on this network type** |
| 1 | `successCount > 0` | has worked, but on a different network type |
| 2 | no success, `consecutiveFailures == 0` | tried, inconclusive (timeouts, cancellations, local failures only) |
| 3 | `consecutiveFailures > 0` | server-attributable failures since the last success |
| — | **no summary row** | **barrier**: never moved, never moved past |

Ordering inside a tier: `lastSuccessAt` **descending** (recency by comparison — no "within N days" cutoff, so no new constant), then `consecutiveFailures` ascending, then `failureCount` ascending. Full tie → the pair is left alone (stable), exactly as `applyRttTieBreak` does.

**Barrier semantics** are copied deliberately from the RTT pass (`:1613-1631`), and for the same reason: "missing is neutral" is not expressible as a total order, and a non-transitive comparator makes TimSort throw on real list sizes (`:1580-1592`). A server with no history therefore keeps its quality rank and remains fully eligible for exploration — a new server is never demoted for being new, and never promoted for it either.

**What this design refuses to do** (each is a `USER-APPROVED REQUIREMENT`):

* no permanent blacklist — tier 3 is a **relative** position inside one equal-quality run, recomputed every run from a counter that resets on the first success;
* no success guarantee — tier 0 says "it worked on this network before", and the reachability gate and the attempt loop still have to succeed *now*;
* no cross-network transfer — Wi-Fi success does not make a server tier 0 on cellular;
* no protocol condemnation — `failureCount` only increments when the **server** is abandoned, so one refused protocol never condemns a server that still has a usable one;
* no dataset-wide penalty — network-level and local failures never increment a per-server failure counter (§11.15.6);
* no replacement for the wedge ledger — `WedgeTracker` remains the session-scoped guard (`EXCLUSION_THRESHOLD = 2`); history is its cross-session analogue, and neither can override the other.

`OPEN DECISION` **OD-6 — the barrier rule and known-bad servers.** Under strict barrier semantics a tier-3 server cannot be pushed **below** an unknown-history server of the same quality, so a repeatedly-failing server can stay ahead of a new one within that run. The alternatives are (i) keep barriers (recommended: no server is ever penalised for being unknown), or (ii) let tier 3 sink past unknown candidates (better ordering, but it implicitly demotes unknown servers, which §2.6 forbids in spirit).

`NOT TESTABLE` without a device: that this ordering actually produces a better first-attempt hit rate on the operator's real dataset. The ordering itself is JVM-testable; its value is an empirical claim and must not be asserted until measured.

### 11.15.9 Room migration and legacy data — `IMPLEMENTED` `TESTED`

| Item | Plan |
|------|------|
| Version | **8 → 9** (`AppDatabase` `@Database(version = 9)`) |
| New tables | `server_history_summary`, `server_history_protocol`, `server_history_event` |
| Changes to existing tables | **none** — no `ALTER TABLE VPNGateItem`, so no `NOT NULL DEFAULT` problem and no risk to the retention columns |
| Indexes | `event(hostName, createdAt)`, `event(createdAt)`, `event(eventType, createdAt)`, `event(hostName, networkType, createdAt)`, `protocol(hostName)` implicit via PK, `summary(hostName)` implicit via PK |
| Foreign keys | **none.** A FK to `VPNGateItem.hostName` would cascade-delete every event the moment `replaceAll` runs `deleteAll()`. Referential integrity is maintained by the application, and an orphaned summary row is harmless (it is simply never matched). |
| Defaults for existing rows | no summary rows are created — see below |
| Known vs new after migration | `summary.firstObservedAt == 0` ⇒ no history; `VPNGateItem.firstSeenAt > 0` ⇒ previously known to the app. Neither is fabricated. |
| Missing history | represented as **absence of a row**, not as a zeroed row. Absence is what the barrier rule reads. |
| Snapshots / imports | `CollectorSnapshotStore` (`collector_vpn_servers.json`, `SCHEMA_VERSION = 1`) is untouched. History is **not** put in the snapshot: a snapshot is a server list, and mixing history into it would make the format's `COUNT_MISMATCH` / `INVALID_IDENTITY` validation mean two things. `ManualImportStore` is untouched; an imported server participates in history like any other identity once it connects. |
| Migration test | `Migration8to9Test` using the existing `RecordingDatabase` proxy pattern: assert the three `CREATE TABLE` statements, every index, and — as `Migration7to8Test` already does — assert the exported `9.json` contains the same columns. |
| Rollback | **not supported.** Room has no down-migration; installing a v1.0.55 APK over a v9 database throws `IllegalStateException` and the only recovery is uninstall (total data loss). This must be stated in the release note for the build that ships v9. |

`USER-APPROVED REQUIREMENT` honoured explicitly: **no existing server is marked "new"** (no summary row is created), **no historical success or failure is fabricated**, and every existing server keeps its current eligibility because absence of history is a barrier, not a penalty.

### 11.15.10 Retention, storage and performance — `IMPLEMENTED` `TESTED`

`USER-APPROVED REQUIREMENT`: **indefinite retention**. No expiry, no pruning, no maximum row count, no periodic deletion. This section documents the cost instead of capping it.

| Question | Assessment |
|----------|------------|
| Row size | ≈ 170–250 B per event including index entries (identity ~20 B, `ip` ~15 B, four short enums, two integers, timestamps) |
| Event volume | ~1–3 events per user-initiated run: one `PROTOCOL_ATTEMPT` per protocol tried, at most one `SERVER_ABANDONED` per server, at most one `CONNECTION_LOST` per tunnel |
| Yearly growth | a heavy user at 30 runs/day ≈ 60 events/day ≈ 22 000 events/year ≈ **5–6 MB/year**; 100 runs/day ≈ **9–10 MB/year** |
| `SERVER_FIRST_SEEN` | bounded by dataset size (one per identity, ever) — a one-off cost of ~2 000 rows, not a per-refresh cost |
| Summary size | ~19 columns × 2 000 rows ≈ **250–400 KB** |
| 5-year worst case | **≈ 50 MB** for a very heavy user — material, but not a correctness problem, and it is the cost of the approved policy |
| AutoConnect query cost | one `SELECT` over `server_history_summary` (≤ dataset size) into a `Map<String, Summary>`; **no event-table scan ever happens on the selection path** |
| Event queries | always index-covered: by identity (`hostName, createdAt`), by recency (`createdAt`), by type (`eventType, createdAt`), by network (`hostName, networkType, createdAt`) |
| UI/DB-thread blocking | `allowMainThreadQueries()` is enabled in `App.kt`; **every history read and write must be dispatched off the main thread by the caller** — a rule the implementation must state in KDoc, because the database will not enforce it |
| Concurrency | events are written from the Auto Mode scope while a refresh writes `VPNGateItem`; the tables are disjoint and there is no FK, so the two writers serialise on SQLite's write lock rather than on `ServerListUpdater`'s mutex. The refresh mutex does **not** cover history writes, and does not need to. |
| Future alternatives, **not implemented** | (a) roll-up of events older than N years into the summary — would break "indefinite"; (b) a separate archive database file — moves the cost, does not remove it; (c) `WITHOUT ROWID` / compression — not applicable to an autoincrement event log. All are recorded as options only. |

`ASSUMPTION` A4: a ~2 000-row summary read per run is acceptable (hundreds of KB, once per run, off-main). If a device run shows otherwise, the fix is a projection query over the handful of columns the comparator needs — a query change, not a schema change.

### 11.15.11 Test plan — `TESTED` (results in §11.15.17.6)

JVM (no device) unless marked otherwise.

| # | Case (instruction §15) | Target | Layer |
|---|------------------------|--------|-------|
| 1 | Fresh server with no history | `HistoryTieBreakTest` — no summary row ⇒ barrier, position unchanged | pure |
| 2 | Previously known server, no connection history | same + `firstObservedAt == 0` ⇒ treated as unknown, eligible | pure |
| 3 | Successful Wi-Fi connection | `ServerHistoryRecorderTest` — `CONNECTED`, `wifiSuccessCount = 1`, `consecutiveFailures = 0` | fake DAO |
| 4 | Successful mobile-data connection | same, `cellularSuccessCount = 1` | fake DAO |
| 5 | Multiple successes per network type | counters accumulate; `lastSuccessAt` monotonic | fake DAO |
| 6 | Success by different protocols | `server_history_protocol` rows per protocol; `lastSuccessProtocol` follows the latest | fake DAO |
| 7 | Server failure vs network failure | `SERVER_REJECTED` increments `failureCount`; `NETWORK_UNAVAILABLE` does not | fake DAO |
| 8 | VPN permission failure | `PERMISSION_DENIED`, `NEUTRAL`, no counter | `AutoModeControllerTest` + fake adapter |
| 9 | User cancellation | `ATTEMPT_CANCELLED`, no counter, `consecutiveFailures` unchanged | controller |
| 10 | `NATIVE_BUSY` | `NEUTRAL`, no counter, no wedge before the threshold | controller |
| 11 | Unknown outcome | `UNKNOWN`, `NEUTRAL`, no counter | fake DAO |
| 12 | Timeout with uncertain attribution | `TIMEOUT_UNATTRIBUTED`; `timeoutCount +1`; `failureCount` unchanged | fake DAO |
| 13 | Successful tunnel followed by quality rejection | **cannot be produced** — pinned as a documentation test: no producer exists (OD-5) | — |
| 14 | Duplicate callback / repeated outcome delivery | `CONNECTED` written once for one `attemptId` + protocol | controller |
| 15 | Refresh retains history | `mergeAll` over a summary-bearing dataset leaves the summary untouched | fake DAO |
| 16 | Duplicate server merge preserves history | two records, one hostname ⇒ one identity, one history | merger + fake DAO |
| 17 | Server disappears and returns | summary row survives the absence; resolves on return | fake DAO |
| 18 | Migration from the current schema | `Migration8to9Test` — DDL, indexes, `9.json` parity | `RecordingDatabase` |
| 19 | Event + summary transaction consistency | one `@Transaction`; a throwing summary update rolls the event back | fake DAO |
| 20 | Deterministic AutoConnect ordering | same input twice ⇒ identical order; history pass is stable | pure |
| 21 | New-server exploration | a no-history candidate keeps its quality rank beside known-good peers | pure |
| 22 | No permanent exclusion from old failures | tier 3 in one run; after one success, tier 0 and `consecutiveFailures = 0` | pure |
| 23 | History across process restart | **device/DB-level**: values read back from a real Room database | `NOT TESTABLE` on plain JVM without Robolectric — plan: assert at the DAO boundary with an in-memory Room if the harness allows, otherwise defer |
| 24 | History across app restart | same as 23 | `NOT TESTABLE` as above |
| 25 | Network classification mapping | `NetworkTypeClassifierTest` — Wi-Fi, cellular, ethernet, VPN-only ⇒ UNKNOWN, empty ⇒ UNKNOWN | pure |
| 26 | Identity rules | `HistoryIdentityTest` — trim, blank ⇒ skip, IP change ⇒ same identity, hostname change ⇒ different identity | pure |

`NOT TESTABLE` without a device: 23, 24, and every claim about a real `ConnectivityManager` (25's wiring half). No physical-device test has been run and none will be claimed.

### 11.15.12 Implementation boundaries and file list — `IMPLEMENTED`

Not authorised in Phase 1. The list is what approval would authorise.

**New files**

| Path | Purpose |
|------|---------|
| `app/src/main/java/vn/unlimit/vpngate/history/ServerHistorySummary.kt` | `@Entity` summary (19 columns) |
| `app/src/main/java/vn/unlimit/vpngate/history/ServerProtocolSummary.kt` | `@Entity` per-protocol counters |
| `app/src/main/java/vn/unlimit/vpngate/history/ServerHistoryEvent.kt` | `@Entity` event + the bounded taxonomy enum |
| `app/src/main/java/vn/unlimit/vpngate/history/ServerHistoryDao.kt` | reads + `@Transaction` write |
| `app/src/main/java/vn/unlimit/vpngate/history/ServerHistoryRecorder.kt` | the only writer; maps outcomes to events; idempotent |
| `app/src/main/java/vn/unlimit/vpngate/history/HistoryIdentity.kt` | pure identity normalisation |
| `app/src/main/java/vn/unlimit/vpngate/history/NetworkTypeClassifier.kt` | pure `classify(transports)` + the `ConnectivityManager` read |
| `app/src/main/java/vn/unlimit/vpngate/history/HistoryTieBreak.kt` | pure tier + insertion pass |
| `app/schemas/vn.unlimit.vpngate.db.AppDatabase/9.json` | generated by the build, committed as `1.json`–`8.json` are |

**Modified files**

| Path | Change |
|------|--------|
| `app/src/main/java/vn/unlimit/vpngate/db/AppDatabase.kt` | `version = 9`, three entities, one DAO, `MIGRATION_8_9` |
| `app/src/main/java/vn/unlimit/vpngate/App.kt` | `addMigrations(… MIGRATION_8_9)` |
| `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt` | two new constructor parameters (a history provider and a recorder, both defaulted so every existing test compiles unchanged); one call in `sortedForAutoConnect`; recording at `:1940`, `:1973`, `:2434`, `:2408`, `:1044` |
| `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeEngine.kt` | wire the real provider/recorder (replacing nothing — `onSuccess`'s existing writes stay) |
| `app/src/main/java/vn/unlimit/vpngate/repository/ServerListUpdates.kt` | emit `SERVER_FIRST_SEEN` from the merge path (inside `mergeAll`'s existing transaction) |
| `app/src/main/res/values*/strings.xml` | only if OD-8 says a UI surface ships |

**Explicitly out of scope for the first implementation slice:** any change to `VPNGateItem`, `VPNGateItemDao.replaceAll`, `ServerListUpdater`, `DatasetIntegrity`, `VpnServerMerger`, the wedge ledger, the reachability tester, or the snapshot format. `G-F29-1` (the fallback-path retention reset) is **not** fixed by this feature and needs its own authorisation.

> **Phase 2 correction — the list above is the plan, not the outcome.** What actually landed differs in three ways, and §11.15.17.1 is authoritative: (1) the three entities are one file, `ServerHistoryEntities.kt`, not three; (2) `HistoryIdentity.kt` and `HistoryTieBreak.kt` were not created as separate files — identity normalisation lives in `ServerHistoryTypes.kt` / `ServerHistoryDao.kt` and the tier model in `automode/AutoModeHistory.kt`; (3) `ServerListUpdates.kt` was **not** modified, so `SERVER_FIRST_SEEN` has **no producer**. This is deliberate and loses nothing: `firstObservedAt` is stamped once by `HistoryCounters.nextSummary` (`base.firstObservedAt ?: record.at`) on the first event of **any** type, so a dedicated first-sighting event would add a row carrying no fact the summary does not already hold — and the instruction qualifies the event with "only when first history observation can be recorded accurately", which the summary timestamp already satisfies. The seven added and four modified production files are listed in §11.15.17.1.

### 11.15.13 Assumptions — `ASSUMPTION`

| # | Assumption | If it is wrong |
|---|------------|----------------|
| A1 | VPN Gate hostnames are stable enough to be an identity | a server loses its history; nothing is mis-attributed |
| A2 | `hostName` is non-blank for every stored row | the recorder skips blank identities instead of merging them |
| A3 | A future quality criterion can attach to `reportArmedTunnelLoss` | the reserved event/column would need a different hook |
| A4 | A ~2 000-row summary read per run is affordable | replace with a projection query; no schema change |
| A5 | SQLite's write lock is sufficient to serialise the refresh writer and the history writer | the recorder would need its own mutex |
| A6 | The success funnel at `:2408` is reached exactly once per established tunnel | duplicate `CONNECTED` events; the idempotency guard is the mitigation, not the assumption |
| A7 | `ACCESS_NETWORK_STATE` is enough for transport classification (no location, no SSID) | the Wi-Fi/cellular split would degrade to `UNKNOWN`, which is recorded honestly |

### 11.15.14 The nine decisions and their outcomes — `USER-APPROVED REQUIREMENT` (all nine decided, none re-opened)

All nine were decided by the operator in the Phase 2 instruction and were implemented as decided. None was re-opened.

| # | Decision | Decided | Phase 2 outcome |
|---|----------|---------|-----------------|
| **OD-1** | Summary as a separate 1:1 table instead of columns on `VPNGateItem` | **(a) separate table** | `IMPLEMENTED` — `server_history_summary`, `server_history_protocol`, `server_history_event`; `VPNGateItem` DDL unchanged. `TESTED` by `Migration8to9Test`. |
| **OD-2** | Hostname change with a stable IP | **(a) accept the limitation** | `IMPLEMENTED` — identity is `hostName` alone. `KNOWN LIMITATION`: a hostname change starts a new history; the old row is orphaned and harmless. |
| **OD-3** | Does a `TIMED_OUT` count as a failure? | **(b) separate `timeoutCount`, never `failureCount`** | `IMPLEMENTED` — `HistoryCounters.nextSummary` routes `TIMEOUT_UNATTRIBUTED` to `timeoutCount` only. `TESTED` by `HistoryCountersTest`. |
| **OD-4** | May a protocol success reorder Protocol Priority? | **(b) no** — record and display only | `IMPLEMENTED` — `usableProtocols(server, priorities)` filters the user's list and never sorts it; `server_history_protocol` is diagnostic. `TESTED` by `AutoModeHistoryRecordingTest`. |
| **OD-5** | Quality rejection has no trigger | **(a) reserve only** | `IMPLEMENTED` — `QUALITY_REJECTED` and `qualityRejectionCount` exist with **no producer**. `TESTED` that the counter is reachable only by its own event and that nothing else increments it. |
| **OD-6** | Can a known-bad server sink below an unknown one? | **(a) keep barriers** | `IMPLEMENTED` with a deviation: the Phase 1 *barrier* was replaced by **tier 2 = INCONCLUSIVE**, which satisfies the same rule — a server with no history is never demoted below a proven success and never sinks below a proven failure — and is transitive, so TimSort cannot throw. See §11.15.17.3. |
| **OD-7** | Omit `SERVER_OBSERVED` (per-refresh) events | **omit** | `IMPLEMENTED` — no per-refresh producer exists. `TESTED` that a clean run emits exactly one event type. |
| **OD-8** | UI surface in the first slice? | **(b) second slice** | `IMPLEMENTED` — no UI, no strings, no new screen. `FOLLOW-UP REQUIRED`. |
| **OD-9** | Event write placement | **(a) awaited, after `setState(Connected)`** | `IMPLEMENTED` — the `CONNECTED` write is awaited under `withContext(NonCancellable)` after `setState` and after the watcher is armed. One documented deviation: `CONNECTION_LOST` cannot be awaited, because `reportArmedTunnelLoss` is reached from an OS callback with no coroutine. See §11.15.17.4. |

### 11.15.15 Acceptance criteria — `TESTED` (evidence in §11.15.17.7)

The feature is acceptable when all of the following are true and **stated with evidence labels**:

| # | Criterion | Result | Evidence |
|---|-----------|--------|----------|
| 1 | A verified tunnel increments exactly one `CONNECTED` event and exactly one `successCount`, once per tunnel | `TESTED` (partially — see note) | `AutoModeHistoryRecordingTest.a verified tunnel records exactly one success and nothing else`; `ServerHistoryRecorderTest.a repeated success with the same token is a no-op`. **The double-callback half is `NOT TESTABLE`**: it needs the real adapter's armed-tunnel path, which needs a device. |
| 2 | No `failureCount` is incremented by `TIMED_OUT`, `NATIVE_BUSY`, `MISSING_CONFIG`, a permission failure, a cancellation, or a post-connect loss | `TESTED` | `HistoryCountersTest.local causes never touch a failure counter`, `a timeout increments only the timeout counters`, `a loss never erases a success`; `AutoModeHistoryRecordingTest.a native busy refusal is recorded as a local event`. |
| 3 | A refresh — network path **and** fallback path — leaves every summary row and every event untouched | `TESTED` by construction; **no test drives a real refresh** | The three history tables are separate entities with no foreign key and are not touched by `replaceAll`; `Migration8to9Test` proves the DDL. A test that runs the real refresh path is `NOT TESTABLE` — `ServerListUpdates` needs Room + a network. |
| 4 | A server with no history keeps its quality position and is still attempted | `TESTED` | `AutoModeHistoryOrderingTest.a known-good server outranks a server with no history`, `a server with no history outranks a server with a failure streak`; `AutoModeHistoryRecordingTest.history never outranks better quality`. |
| 5 | A server with `consecutiveFailures > 0` is never permanently excluded, and returns to tier 0 after one success | `TESTED` | `HistoryCountersTest.a success resets the consecutive failure streak`; `AutoModeHistoryOrderingTest.a failure streak with a later success is not the bottom tier`. Permanent exclusion is impossible by construction: the reachability gate is unchanged and no blacklist exists. |
| 6 | History never changes the order of two candidates of **different** quality, never changes the protocol attempt order, and never re-admits a server the reachability gate removed | `TESTED` | `AutoModeHistoryRecordingTest.history never outranks better quality`, `history reorders only inside an equal-quality window`, `protocol priority is the only authority on attempt order`. The reachability gate runs after the sort and is unchanged — verified by the pre-existing suite's reachability tests, all still green. |
| 7 | The migration 8→9 is proven by a JVM test against the `RecordingDatabase` pattern **and** the exported `9.json`; no `VPNGateItem` DDL changes | `TESTED` | `Migration8to9Test.the generated version 9 schema matches the migration DDL exactly` (whitespace-insensitive comparison of all 7 schema objects), plus bounded-version, no-`DEFAULT`, no-`DROP`, no-`FOREIGN KEY` and registration assertions. |
| 8 | `:app` and `:SoftEtherClient` suites green on three consecutive `--rerun-tasks --no-build-cache` runs | `TESTED` — **one run only** | One clean run of each is recorded in §11.15.17.6. The three-run stability requirement is `FOLLOW-UP REQUIRED`; the Phase 2 budget produced a single verified run. |
| 9 | Every new behaviour labelled SOURCE CODE / TEST; device-only items labelled `NOT TESTABLE` | `TESTED` | §11.15.17.5 lists the five device-dependent items as `NOT TESTABLE`; every other claim cites a test name. |
| 10 | No automatic pruning, expiry, or row cap exists anywhere in the code or the schema | `TESTED` | No `DELETE`, `LIMIT`-based eviction, or retention job exists in `history/`; `eventsFor(hostName, limit)` is a bounded **read**, not a prune. `Migration8to9Test` asserts the migration contains no `DELETE`. |

### 11.15.16 Phase status

| Item | Status |
|------|--------|
| Read-only audit | `COMPLETE` (Phase 1) |
| Specification update (this section) | `COMPLETE` (Phase 1 + Phase 2, §11.15.17) |
| Assumptions and risks report | `COMPLETE` (11.15.13, 11.15.10) |
| The nine decisions | `DECIDED` — OD-1 … OD-9, implemented as decided, with two documented deviations (§11.15.17.3, §11.15.17.4) |
| File list | `COMPLETE` (11.15.12, corrected in 11.15.17.1 to 7 added + 4 modified production files) |
| Migration | `IMPLEMENTED` `TESTED` — 8 → 9, DDL parity proven against `9.json` |
| Automated tests | `TESTED` — 98 new tests, 1162 `:app` tests green, 184 `:SoftEtherClient` tests green |
| Build | `TESTED` — `:app:assembleProDebug` BUILD SUCCESSFUL |
| Repository discipline | `VERIFIED` — HEAD `0c3c56e`; no commit/tag/push/release; the pre-existing `vpngate_collector.py` working-tree modification preserved untouched; no ADB, no app-data clear |
| Device-dependent verification | `NOT TESTABLE` — DEVICE UNAVAILABLE (§11.15.17.5) |
| G-F29-1 (snapshot fallback resets `firstSeenAt`/`lastSeenAt`/`healthScore`) | **NOT FIXED — out of scope**, reported separately (§11.15.17.8) |
| Implementation | **`COMPLETE`** — final report in `F29_PHASE2_IMPLEMENTATION_REPORT.md` (21 sections, verdict `IMPLEMENTED — SOME VERIFICATION BLOCKED`) |
| Follow-ups | `FOLLOW-UP REQUIRED` — UI surface (OD-8), three-run stability, the retention sweep, device verification — blocked on explicit approval (§17 gate) |

---

### 11.15.17 Phase 2 implementation record — `IMPLEMENTED` `TESTED`

This subsection is the authoritative record of what Phase 2 actually built, ran and verified. Where it disagrees with a Phase 1 subsection above, this subsection wins.

#### 11.15.17.1 Files

| File | Status | What it is |
|------|--------|------------|
| `app/src/main/java/vn/unlimit/vpngate/history/ServerHistoryTypes.kt` | `IMPLEMENTED` | The vocabulary: `NetworkType` (3 values), `HistoryEventType` (7 values), `HistoryOutcome`, `HistoryFailureReason` (13 constants), `HistoryRecord`, `ServerHistorySink`, `ServerHistoryView`. Pure Kotlin — no Room, no Android, no `automode` import. |
| `app/src/main/java/vn/unlimit/vpngate/history/ServerHistoryEntities.kt` | `IMPLEMENTED` | The three Room entities. **No foreign keys** — a cascade on `VPNGateItem.hostName` would delete the whole history on every `replaceAll`. |
| `app/src/main/java/vn/unlimit/vpngate/history/HistoryCounters.kt` | `IMPLEMENTED` | `nextSummary` / `nextProtocol` — the whole counter arithmetic as total pure functions. |
| `app/src/main/java/vn/unlimit/vpngate/history/ServerHistoryDao.kt` | `IMPLEMENTED` | The only writer. `record(record)` is one `@Transaction`: blank-identity drop, `CONNECTED`-token idempotency, summary upsert, protocol upsert, event insert. |
| `app/src/main/java/vn/unlimit/vpngate/history/RoomServerHistorySink.kt` | `IMPLEMENTED` | `Dispatchers.IO` hop + error containment. `CancellationException` is **rethrown**; every other `Exception` is logged and swallowed. |
| `app/src/main/java/vn/unlimit/vpngate/history/NetworkTypeClassifier.kt` | `IMPLEMENTED` | `classify(activeTransports, physicalTransports)` is pure; `current(context)` is the Android read and never throws. |
| `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeHistory.kt` | `IMPLEMENTED` | `HistoryTier`, `historyTierOf`, `historyComparator`, `viewOf`. |
| `app/src/main/java/vn/unlimit/vpngate/db/AppDatabase.kt` | `IMPLEMENTED` | Version 8 → 9, three entities registered, `serverHistoryDao()`, `MIGRATION_8_9`. |
| `app/src/main/java/vn/unlimit/vpngate/App.kt` | `IMPLEMENTED` | Migration registered, `serverHistoryDao` and `serverHistorySink` exposed. |
| `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeEngine.kt` | `IMPLEMENTED` | Wires `historyProvider` (one IO read per run), `historySink`, `networkTypeProvider`. |
| `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt` | `IMPLEMENTED` | Ten recording hooks, the history tie-break inside the equal-quality window, and the hoisted attempt context. `VPNGateItem` DDL untouched. |
| `app/schemas/vn.unlimit.vpngate.db.AppDatabase/9.json` | `IMPLEMENTED` | Exported by KSP (`:app:kspProDebugKotlin`), never hand-edited. |

No Gradle file, no manifest, no string resource and no `VPNGateItem` column was changed.

#### 11.15.17.2 Schema (as exported, not as intended)

Three tables, no foreign keys, no `DEFAULT` clauses (Room derives `NOT NULL` from Kotlin nullability; a Kotlin `= 0` default is applied by the constructor, not by SQLite).

- `server_history_summary` — PK `hostName`. **20 columns** (read from the exported `9.json`, not counted by eye): 1 `TEXT NOT NULL` PK, **5 nullable timestamps** (`firstObservedAt`, `lastAttemptAt`, `lastSuccessAt`, `lastFailureAt`, `lastTimeoutAt`), **11 `INTEGER NOT NULL` counters** (`successCount`, `failureCount`, `timeoutCount`, `consecutiveFailures`, `wifiSuccessCount`, `wifiFailureCount`, `cellularSuccessCount`, `cellularFailureCount`, `unknownNetworkSuccessCount`, `unknownNetworkFailureCount`, `qualityRejectionCount`) and **3 nullable text** (`lastSuccessProtocol`, `lastSuccessNetwork`, `lastFailureReason`).
- `server_history_protocol` — composite PK `(hostName, protocol)`. 8 columns.
- `server_history_event` — PK `id INTEGER PRIMARY KEY AUTOINCREMENT`. 12 columns, 4 indexes: `index_server_history_event_hostName_createdAt`, `…_hostName_protocol_createdAt`, `…_eventType_createdAt`, `…_attemptToken_eventType`.

`MIGRATION_8_9` is three `CREATE TABLE IF NOT EXISTS` + four `CREATE INDEX IF NOT EXISTS` and nothing else — no `ALTER`, no `UPDATE`, no backfill. The history layer starts empty, which is exactly the "unknown, not bad" state the ordering expects.

#### 11.15.17.3 Deviation from the Phase 1 barrier design — `KNOWN LIMITATION`

Phase 1 §11.15.8 proposed a **barrier**: a candidate with no summary row is never moved and never moved past. Phase 2 replaced it with **tier 2 = INCONCLUSIVE** inside a tiered lexicographic comparator.

The rule OD-6 protects — "an unknown server is never demoted below a proven success, and a known-bad server never sinks below an unknown one" — is satisfied exactly: tier 2 sits between tier 1 (any success) and tier 3 (a failure streak).

Why the deviation: the barrier is not expressible as a comparator key, and a non-transitive comparator makes `sortedWith`'s TimSort throw `IllegalArgumentException: Comparison method violates its general contract!` on real list sizes. The tier comparator is transitive by construction (ordinals and stored numbers, compared in a fixed order). The observable ordering is the same for the cases OD-6 names; the difference is that tier 2 now also orders *within* itself by recency, which a barrier could not do.

Tier table (implemented):

| Tier | Meaning |
|------|---------|
| 0 | verified success **on the current physical network type** |
| 1 | verified success on some other network type |
| 2 | no conclusive history — no row, or only inconclusive outcomes |
| 3 | server-attributable failures since the last success |

Within a tier: most recent `lastSuccessAt` first, then fewest `consecutiveFailures`, then fewest `failureCount`. A full tie keeps the incoming order (the sort is stable). When the current network is `UNKNOWN`, tier 0 is unreachable — an unidentified network earns no tier-0 credit, even for successes that were themselves recorded as `UNKNOWN`.

#### 11.15.17.4 Deviation from OD-9's "awaited" — `KNOWN LIMITATION`

OD-9 chose *awaited* over *fire-and-forget*. Nine of the ten hooks are awaited. The tenth — `CONNECTION_LOST` — cannot be: `reportArmedTunnelLoss` is reached from `TunnelStateWatcher.onTunnelLost`, an OS callback with no coroutine to suspend in. It is dispatched onto the controller's `scope`, so it still leaves the callback thread, and it cannot affect anything because the tunnel is already gone. The protocol is captured *before* the launch and the field is cleared synchronously, so the non-awaited coroutine can never observe a half-updated pair.

#### 11.15.17.5 Device-dependent items — `NOT TESTABLE`

No device was available; no ADB command was issued; nothing was installed. The following are therefore `NOT TESTABLE` and are **not** claimed as verified:

1. A real upgrade of an existing v8 database to v9 (the DDL is proven against the export, but no on-device migration ran).
2. `NetworkTypeClassifier.current(context)` against a real `ConnectivityManager` — the pure `classify` is tested; the Android read is not.
3. The armed-tunnel double-callback path and the `CONNECTED` idempotency under a real duplicate callback.
4. That a history write never blocks a live connection on a real locked database.
5. Any observable change in Auto Connect's server choice on a real network.

#### 11.15.17.6 Verification actually run — `TESTED`

Exact commands, in order, all from `C:\Dev\SoftEthergai` with `JAVA_HOME` pointing at the JDK 21 that ships with Android Studio (the project targets JVM 17; the system default is JDK 8, which cannot build it) and `CI=true`:

| Command | Result |
|---------|--------|
| `./gradlew :app:compileProDebugKotlin` | BUILD SUCCESSFUL |
| `./gradlew :app:kspProDebugKotlin` | BUILD SUCCESSFUL — exported `9.json` |
| `./gradlew :app:testProDebugUnitTest --tests "vn.unlimit.vpngate.history.*" --tests "vn.unlimit.vpngate.db.Migration8to9Test" --tests "vn.unlimit.vpngate.automode.AutoModeHistory*"` | BUILD SUCCESSFUL — 98 tests |
| `./gradlew :app:testProDebugUnitTest` | BUILD SUCCESSFUL — **1162 tests, 0 failures, 0 errors, 1 skipped**, 92 suites |
| `./gradlew :SoftEtherClient:testDebugUnitTest` | BUILD SUCCESSFUL — **184 tests, 0 failures** |
| `./gradlew :sstpClient:test :vpnLib:test` | BUILD SUCCESSFUL — no test sources |
| `./gradlew :app:assembleProDebug` | BUILD SUCCESSFUL — `app-pro-debug.apk`, 63,884,364 bytes, SHA-256 `2d7acfec2ecc619a60624af6a23cdd020c0418f7deb439d51fefe24a73436424` |
| `./gradlew --offline --rerun-tasks :app:testProDebugUnitTest :SoftEtherClient:testDebugUnitTest` | BUILD SUCCESSFUL — **confirmation rerun on the delivered tree**: `:app` 92 suites / 1162 tests / 0 failures / 0 errors / 1 skipped; `:SoftEtherClient` 13 suites / 184 tests / 0 failures |
| `./gradlew :app:lintProDebug` | **104 errors, 178 warnings, 9 hints — identical to the documented pre-F.29 baseline.** Zero findings in any F.29 file: `AutoModeController.kt`, `AutoModeHistory.kt` and all six `history/` files do not appear in the report at all |

New suites (98 tests): `Migration8to9Test` 9, `HistoryCountersTest` 21, `ServerHistoryRecorderTest` 16, `NetworkTypeClassifierTest` 15, `AutoModeHistoryOrderingTest` 20, `AutoModeHistoryRecordingTest` 17.

`versionCode` is still `144` and `versionName` still `2.4.11-dev` (read from the merged manifest of the APK above) — **no release version was invented, claimed or bumped.**

A one-off static comparison of the migration DDL against `9.json` (whitespace-insensitive, all 7 schema objects) reported **PARITY: EXACT**; the same comparison is asserted at runtime by `Migration8to9Test.the generated version 9 schema matches the migration DDL exactly`.

**Three defects were found and fixed during Phase 2 — two by the tests, one by the compiler:**

1. **Compiler:** the success funnel referenced `protocol` / `attemptToken` / `attemptNetwork` / `attemptStartedAt`, which are loop locals that are out of scope where the funnel sits (after the protocol loop closes). Fixed by hoisting them to per-server function-scope variables, reset for each server.
2. **Test (`AutoModeHistoryRecordingTest.a timed-out protocol records an unattributed timeout and not a failure`):** `TIMED_OUT` was recorded with `outcome = FAILURE`, which contradicted §8.3 and the event row's own meaning. Fixed to `NEUTRAL`.
3. **Test (`a cancelled sink write is rethrown rather than reported as a failure`):** `RoomServerHistorySink` caught `Throwable`, which would have swallowed a `CancellationException` and turned a cancelled write into "the write succeeded". Fixed to rethrow `CancellationException` and contain `Exception`.

**Four documentation defects were also found in this record during a final cross-check against the exported schema and the source, and corrected:**

1. §11.15.17.2 claimed `server_history_summary` had **21 columns**; the exported `9.json` shows **20** (1 `TEXT NOT NULL` PK, 5 nullable timestamps, 11 `INTEGER NOT NULL` counters, 3 nullable text). Corrected.
2. §11.15.17.1 claimed `HistoryFailureReason` had **14 constants**; there are **13**. Corrected.
3. The `HistoryEventType` header comment claimed "six of these have a producer"; **five** do — `QUALITY_REJECTED` and `SERVER_FIRST_SEEN` both have none. Corrected in the source, and the `SERVER_FIRST_SEEN` rationale is now stated explicitly in §11.15.12.
4. The §11.15.12 planned-file note gave a wrong reason for the absent `SERVER_FIRST_SEEN` producer ("the taxonomy has no first-seen event"); the taxonomy **does** declare it. The real reason — `firstObservedAt` is stamped once by whichever event arrives first, so a dedicated event would carry no new fact — is now recorded.

`--offline` was used for every Gradle invocation; all dependencies were already cached, so no network fetch was required or performed.

Lint is reported as a **non-blocking** result for this phase: the 104 errors are the documented pre-F.29 backlog (69 of the 70 `MissingTranslation` errors are `vi`), the count is unchanged from the baseline, and none of the 291 findings names an F.29 file. Lint is not configured as a gate for this task and was not treated as one; it is recorded here so the number is not mistaken for a regression.

#### 11.15.17.7 What is *not* claimed

| Claim | Status |
|-------|--------|
| On-device behaviour of any kind | `NOT TESTABLE` — DEVICE UNAVAILABLE |
| A real v8 → v9 upgrade | `NOT TESTABLE` — DEVICE UNAVAILABLE |
| Three consecutive green runs (acceptance criterion 8) | `FOLLOW-UP REQUIRED` — one verified run |
| UI surface for history (OD-8) | `FOLLOW-UP REQUIRED` — second slice by decision |
| G-F29-1 | **NOT FIXED** — out of scope, see §11.15.17.8 |
| `healthScore` gaining a production reader | out of scope — unchanged by this phase |
| A lint run with zero errors | `NOT CLAIMED` — 104 pre-existing errors, unchanged; none in an F.29 file |
| An improved physical connection success rate | `NOT TESTABLE` — DEVICE UNAVAILABLE; this is the feature's *goal*, not a verified outcome |

#### 11.15.17.8 G-F29-1 — pre-existing defect, reported and deliberately not fixed

`G-F29-1` was identified in Phase 1 (§11.15.2.1) and the Phase 2 instruction explicitly scoped it out. It is restated here so it is not lost:

The snapshot fallback path reads no previous row, and `toVPNGateItem()` has no `firstSeenAt` / `lastSeenAt` / `healthScore` fields, so a refresh served from a snapshot resets all three to `0`. The F.29 history tables are **unaffected** — they are separate entities with no foreign key and are never read or written by the refresh path — but the defect is real, pre-existing, and still present at HEAD.

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
| 9 | **FINDING T5-SLOT-TEARDOWN residual (attempt=N stuck in TEARDOWN after success)** | Observed in the v1.0.10 device run: a holder logged `attempt=2 phase=TEARDOWN, reporting deadline expired (still blocked)` and blocked later attempts (`Start refused: native slot still held`) for ~1 min; FIX-2 fired 3× but no `FORCED_RELEASE_AFTER_TIMEOUT` fired. Capture the exact timing window in a future device run; confirm whether the 90 s TEARDOWN bound is reached before Auto Mode stops. See §1.6.4. | P2_MEDIUM (opportunistic) | **OPEN — scope narrowed by v1.0.11** (§1.6.5): the residual fired **7×** in the hardening device run and **self-healed in 7/7** (`Native cleanup confirmed … structure destroyed and slot released` within ~1 s, next attempt proceeded normally). The mechanism is now understood from source (a `NOOP_REENTRANT` completion for the same attempt id satisfies `TunnelStateWatcher`'s exact-attempt-id wait before the real `CONFIRMED` arrives — `AndroidConnectionAdapter.kt:226`, `SoftEtherVpnService.kt:1081–1106`, `ConnectionController.kt:937`). Still **UNKNOWN**: the upstream trigger for the re-entrant teardown call, and why the same log line corresponded to a ~1 s window in v1.0.11 but a ~1 min window in v1.0.10. Classification in `docs/reports/AUDIT_AXES_A_B_C_v1.0.11.md` §2. **→ v1.0.48 closed the residual (additive note, §1.6.41):** the "confirm whether the 90 s TEARDOWN bound is reached before Auto Mode stops" question is answered — it never could be. `markTeardownRequested` re-stamped `teardownRequestedAtMs` on every call, and both the idempotent `destroyResources()` call sites and `adapter.disconnect()` on every `NATIVE_BUSY` refusal kept restarting the clock, so the bound was unreachable by construction. FIX-1 now stamps only the first teardown request per attempt; FIX-2 makes the refusal report `heldFor=<N>ms` and routes recovery through the registry's own entry point. The historical observations above (v1.0.10 ~1 min, v1.0.11 self-healed 7/7) are unchanged and remain accurate for their runs. Device re-verification of the recovery is PENDING |
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

> **Item A status (v1.0.44 — ROUTING-REACHABILITY-AND-EPERM-DIAGNOSTICS, §1.6.36):** item **A** was already
> implemented at the binding level (`a9e16fd`, recorded v1.0.12–v1.0.13) and the OPEN-first / VPN-fallback
> ordering landed in `c4c0311` (v1.0.27), but the opt-in that selects the VPN rung — `vpn_always_prefer_vpn` —
> had **no writer**, so the "active VPN path" half of item A was unreachable for users. `DataUtil` +
> `SettingsScreen` now provide that writer and its toggle, making the opt-in genuinely user-reachable (off by
> default). Item A moves from "implemented but inert" to "implemented and reachable". The binding remains
> per-request via `Network.getSocketFactory()` (`boundToSocketFactory`), never `bindProcessToNetwork()`.
> Evidence: SOURCE CODE + TEST.

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

> **Item status (v1.0.39 — RETENTION-IMPL-PHASE-1):** item **G** ("Safe merge and persistence") is
> now **PARTIAL**. The cumulative-retention merge (`mergeAll`) lands in this phase: the single writer
> (`ServerListUpdates.daoStore.replaceAll`) now **merges** a fresh network observation into the stored
> dataset (so a server the feed no longer lists is kept) instead of replacing it, while a fallback
> still replaces wholesale via the retained `replaceAll`. Manual-server preservation (the Z1-IMPL-D
> half of G) was already LANDED at v1.0.23. The remaining G work — the §9.3 retention-priority prune
> and the max-server cap (phases 2–3 of the retention plan) — is **NOT IMPLEMENTED**, and item H's
> Android-runtime / device coverage stays open. Evidence: SOURCE CODE + TEST (11 new JVM tests); see
> §1.6.31.

> **Item status (v1.0.41 — TRAFFIC-PATH-DYNAMIC phase 1):** the recorded limitation on item **A** is
> **closed**. The v1.0.29 note above states that `GlobalVpnTracker.isTunnelUsable()` exists but is not
> consumed by production routing; that is no longer true. `productionRouting()` now feeds
> `isTunnelUsableForRouting()` into `CollectorRoutingInput.tunnelUsable`
> (`VpnServerRepository.kt:119–132`) and `resolvePrimary()` requires `preferVpn && tunnelUsable`
> before choosing the VPN rung (`:149–151`), so an opted-in user with a dead tunnel goes straight to
> OPEN instead of paying a VPN timeout. Item **A**'s description above is retained verbatim; this note
> supersedes its status in substance and does not edit it. Per-source path memory (the §11.8
> "retain useful source/path outcome information" requirement) remains **OPEN — phase 2**. Evidence:
> SOURCE CODE + TEST; see §1.6.34.

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
| `DEFAULT_ATTEMPT_TIMEOUT_MS` | 8_000L (v1.0.52; was 12_000L) | `AutoModeController.kt` | Auto Mode per-attempt timeout |
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

| 1.0.38 | 2026-10-19 | VERSION-BUMP-PAST-2.4.10 (device-free; no source, test, or signing change) | **Resolves the version-identity collision recorded in §1.6.12 bullet 5 (v1.0.19).** HEAD declared `versionCode 143` / `versionName "2.4.10"` — the same identity as tag `p2.4.10` — while it stood 60+ commits ahead of that tag, so every device install since v1.0.19, including the v1.0.37 hardware verification, reported `versionCode 143` and was indistinguishable by version from the `p2.4.10` artifact. `app/build.gradle` now declares **`versionCode 144`** / **`versionName "2.4.11"`** inside the `pro` flavor, the only place in the repository that declares the application version; a two-line comment above the fields cites §1.6.30. Discovery was exhaustive and read-only: no second `build.gradle`/`.properties`/`.json`/`.toml` declares a version, **no test** under `app/src/test/` or `app/src/androidTest/` references `versionCode`/`versionName`/`143`/`2.4.10` (so no test changed and no count moved), and the seven `BuildConfig.VERSION_NAME` / `VERSION_CODE` readers are symbolic, display-only, and follow the bump automatically. One comment in `CollectorSnapshotStore.kt` still says "the 143 / 2.4.10 release line", but it is a historical citation of commit `0714628` used as mapping evidence — not a version declaration — and that file is FROZEN. `aapt2 dump badging` on the rebuilt APK confirms `versionCode='144' versionName='2.4.11'`, so the manifest came from the bumped Gradle values, not a stale cache. JVM unchanged: `:app` **812 / 0 / 0 / 1**, `:SoftEtherClient` **173 / 0 / 0 / 0**. This does **not** make 2.4.11 releasable: a cut still requires the fail-closed release keystore (§1.6.10 bullet 8 / **RISK-A**), no signing configuration was touched, and **no tag was created** (still five). The v1.0.37 AutoConnect contract (§1.6.29) and its hardware verification are untouched. |
| 1.0.39 | 2026-10-19 | RETENTION-IMPL-PHASE-1 (device-free; foundation of Cumulative Server Retention, spec §1.6.31) | **Lays the foundation of Cumulative Server Retention (`SERVER_SOURCE_ARCHITECTURE.md` §9.1–§9.2).** At HEAD `fed8787`, `VPNGateItem` carried no retention fields and the single writer replaced the whole table on every accepted refresh. This phase: (a) adds `firstSeenAt` / `lastSeenAt` / `healthScore` to `VPNGateItem` and moves Room `version 7 → 8` via `MIGRATION_7_8` (three additive `ALTER TABLE … ADD COLUMN … INTEGER NOT NULL DEFAULT 0` — no DROP/DELETE/TRUNCATE/CREATE TABLE, so existing rows survive); `App.kt` registers the migration and the KSP-exported `8.json` matches the DDL; (b) rewires `ServerListUpdates.daoStore.replaceAll` to **merge** a fresh network observation (`mergeAll`: new server gets `firstSeenAt = now`, returning server keeps `firstSeenAt` and refreshes `lastSeenAt`, quiet stored servers are retained, manual servers survive the overlay, commit inside `replaceAll`'s `@Transaction`) while a fallback still replaces wholesale via the retained `replaceAll`; `ServerListUpdater` is untouched; (c) the phase-1 `healthScore` is `0 if lastSeenAt==0; 100 if score>0 && ping>0; 50 if score>0 || ping>0; 25 otherwise`. 11 new JVM tests (5 migration + 6 retention); no existing test changed. `:app` **823 / 0 / 0 / 1**, `:SoftEtherClient` **173 / 0 / 0 / 0**. `:app:assembleProDebug` SUCCESSFUL under `CI=true`; APK `versionCode='144' versionName='2.4.11-dev'`, SHA-256 `79c102728581023f77f2542053fce11131a0263563f2c86c08c9aa70a6de3c17`. Roadmap item G is now **PARTIAL** (merge landed; §9.3 retention-priority prune + max-server cap are phases 2–3, NOT IMPLEMENTED). Phases 2–3 and a real-device 7→8 upgrade are **UNKNOWN**. No tag was created; §22 byte-identical. |
| 1.0.40 | 2026-10-20 | AUTOCONNECT-DEVICE-BUGS-FIX (device-free; two of the three device-reported Auto Mode bugs, spec §1.6.32) | **Closes the button-state mismatch (BUG-1) and the repeating battery prompt (BUG-3); defers BUG-2.** BUG-1: the hero button was bound exclusively to `AutoModeState`, whose only writer is `AutoModeController.setState` (`Connected` published only at `:2050`), so a manual connect (`DetailActivity.kt:318`) left the button reading "Disconnected" while the D-4 guard (`AutoModeEngine.kt:137`) saw the live tunnel and refused the tap (`AutoModeController.kt:1059–1060`). `AutoModeViewModel` now publishes a combined `AutoModeDisplayState` built by the pure `combineAutoModeState(modeState, tunnelStatus)` — the same reconciliation `HomeScreen.activeServerOf()` already performed (`:693–711`) — from `combine(AutoModeEngine.stateFlow, GlobalVpnTracker.vpnState)`; `AutoModeScreen` renders it and branches the tap on `isExternal`, tearing an externally-owned tunnel down through the new `disconnectExternalTunnel()` → `stopAndDisconnect()` → `disconnectNow()` (the D-4 refusal cannot serve that case). One deliberate divergence from the triage: a live tunnel **outranks** `Error` so the button and the guard agree. Additive string `auto_mode_state_connected_external` ("Connected (manual)"). BUG-3: the gate was the live OS state alone (`AutoModeScreen.kt:201–209`) with a transient `remember` flag and no persisted "asked" preference anywhere, so "Later" re-prompted on every press; `DataUtil` gains `SETTING_BATTERY_OPT_ASKED` + `hasAskedBatteryOptimization()` / `setAskedBatteryOptimization()` (the `SETTING_LOCALE_ANSWERED` precedent), written on every dialog exit, so the prompt is **once per install**. BUG-2 (MS-SSTP never attempted) is **NOT fixed and NOT claimed** — the distinguishing evidence is a device log, and the user's dataset remains **UNKNOWN**. 14 new JVM tests (8 display-state + 6 persisted-flag) using an in-memory `SharedPreferences` seam, because this module has JUnit 4 only; no existing test changed. `:app` **837 / 0 / 0 / 1**, `:SoftEtherClient` **173 / 0 / 0 / 0**. `:app:assembleProDebug` SUCCESSFUL under `CI=true`; APK `versionCode='144' versionName='2.4.11-dev'` (**version unchanged**), SHA-256 `5bed6128ceb382e9619567eefd2ee989189c26ac2e36fc2efc566ec3bf60c91a`. `AutoModeController.kt`, `AutoModeEngine.kt`, `GlobalVpnTracker.kt`, `HomeScreen.kt` and all build/manifest files untouched. All three bugs remain **NOT device-verified**. No tag was created; §22 byte-identical. |

| 1.0.41 | 2026-10-20 | TRAFFIC-PATH-DYNAMIC phase 1, Option B (device-free; operator-authorised; spec §1.6.34) | **The collector's VPN rung now requires a *usable* tunnel, not merely a CONNECTED one.** `resolvePrimary()` (`VpnServerRepository.kt:149–151`) returns `VPN` only when `preferVpn && tunnelUsable`; an opted-in user whose tunnel is dead goes straight to OPEN instead of paying a full VPN timeout before `fetchOnChosenPath`'s fallback (the fallback itself is untouched). `productionRouting()` (`:119–132`) feeds `GlobalVpnTracker.isTunnelUsableForRouting()` instead of the weaker `isConnected && currentVpnNetwork() != null`, and no longer ORs live tunnel state into `preferVpn` (the v1.0.27 FIX-3a property is preserved and pinned by T-NEW-36). The new `isTunnelUsableForRouting()` (`GlobalVpnTracker.kt:459–467`) is a grace-period wrapper over the **unchanged** `isTunnelUsable()`: connected + OS-bound + (passing traffic right now, or no RX ever observed and no TUN EIO inside `EIO_WINDOW_MS`); the grace branch is gated on `rxCounter == 0`, so it cannot mask a tunnel that carried traffic and then died. Its pure core `routingUsableFromSignals(...)` is file-scope (`:797`) so a plain JVM can test it. `connectedAtMs` (`:311`, getter `:321`, stamped on the CONNECTED transition `:617–625`) is added as a diagnostic anchor and is deliberately **not** read by the decision. **FIX-1 was already done** — `TunnelHealth.noteRxAdvance()` is called at `ConnectionController.kt:1157` (one call per received batch, R4b) — and the two comments that claimed otherwise (`SoftEtherVpnService.kt:1735–1740`, `GlobalVpnTracker.kt:357–363`) are corrected comment-only. 9 new JVM tests (6 `GlobalVpnTrackerRoutingTest` + 3 `VpnServerRepositoryRoutingDynamicTest`); no existing test changed. `:app` **846 / 0 / 0 / 1**, `:SoftEtherClient` **173 / 0 / 0 / 0**. `:app:assembleProDebug` SUCCESSFUL under `CI=true`; APK `versionCode='144' versionName='2.4.11-dev'` (**version unchanged**), SHA-256 `1340fed9771828d3b9655c4b05c0d2778ed737e9bd18b6363b16c44d2902159f`. Per-source path memory remains **phase 2**, and the change is **NOT device-verified**. §12.5.1 row 10 is stale but FROZEN and is annotated only in §1.6.34. No tag was created; §22 byte-identical. |
| 1.0.42 | 2026-10-20 | AUTOCONNECT-CORRECTNESS-CLUSTER (device-free; operator-authorised revision; spec §1.6.35) | **Four Auto Mode corrections that share one cause: a value or a position captured once and then treated as still true.** **F1** — `attemptTimeoutMs: Long` becomes `attemptTimeoutMsProvider: () -> Long`, read at attempt time through `attemptTimeoutMs()`, because `AutoModeEngine.ensure()` builds the controller exactly once while `AndroidConnectionAdapter` re-reads `SETTING_AUTO_MODE_TIMEOUT_SECONDS` on every call, so a Settings change used to apply only after a process restart. **B-3** — `nativeSlotAwaitMs` becomes a provider too (production keeps `5 × T`, the v1.0.36 30 s floor and the D-2 60 s cap), because both budgets derive from the same preference and fixing one alone would have left the other frozen. Both helpers are positivity-only guards (`coerceAtLeast(1L)`); the first-draft 1 s / 5 s floors were removed because `getAutoModeTimeoutSeconds()` already clamps to 5..60 s at `DataUtil.kt:361` and the floors only overrode short test budgets. **F2** — a new `isNetworkAvailable: () -> Boolean` seam (default `true`), a new `ERROR_NO_NETWORK = "no_network"` terminal, and two guards (before the loop, before each attempt) stop an offline run instead of marking every server `TIMED_OUT` and giving each a `WedgeTracker` strike — two such runs used to exclude every healthy server for the rest of the process. The terminal is mapped to a new `auto_mode_error_no_network` string in `AutoModeScreen`, and `GlobalVpnTracker.isAnyNetworkAvailable()` supplies it, failing **open** on a missing context. **F6** — the Next-Server continuation moves from `resumeFromIndex: Int` to `resumeFromServerKey: String?` (`ip|hostname`), resolved with `indexOfFirst { … } + 1`, and the skip filter switches from IP to the same identity (that part **is** a bug fix: an IP-only filter also dropped a different server sharing an IP). **F6 is a deliberate reversal of the v1.0.31 decision**, not a bug fix — the v1.0.31 KDoc explicitly accepted index drift; the accepted cost is that a candidate which became better and now sorts before the connected server is skipped by this continuation, though not by the next fresh press. A vanished server now restarts at the top and logs it. 12 new JVM tests in four new files (4 + 4 + 1 + 3); existing test edits are mechanical only (13 constructor call sites in 7 files). `:app` **858 / 0 / 0 / 1**, `:SoftEtherClient` **173 / 0 / 0 / 0**. `:app:assembleProDebug` SUCCESSFUL under `CI=true`; APK `versionCode='144' versionName='2.4.11-dev'` (**version unchanged**), SHA-256 `e8a4bb1400e9046c1c0b4b34218b18da10c127a1992407ead8062f5fd5a67ecd`. **NOT device-verified.** No tag was created; §22 byte-identical. |

| 1.0.43 | 2026-10-20 | ROUTING-REACHABILITY-AND-EPERM-DIAGNOSTICS (device-free; operator-authorised; spec §1.6.36) | **Closes two v1.0.43 device findings.** M1 — the `vpn_always_prefer_vpn` opt-in had no writer (so the v1.0.41 VPN routing rung was unreachable for every user) — `DataUtil` gains `getPrefersVpnForCollector()` / `setPrefersVpnForCollector()` reusing the exact key literal `"vpn_always_prefer_vpn"`, and `SettingsScreen`'s Auto Mode section gains a toggle (off by default). M2 — `HttpFetcher.boundToNetwork` is wrapped in `try/catch (Throwable)` logging `[bind] ok network=…` on success and `[bind] FAILED network=… errno=…` on failure, re-throwing (control flow unchanged). 7 new JVM tests in two new files (4 + 3); `:app` **865/0/0/1**, `:SoftEtherClient` **173/0/0/0**; `:app:assembleProDebug` SUCCESSFUL under `CI=true`, APK SHA-256 `ed82fc058796a45698cd41a90d4dcb1fb20ecbc05198b30d57eaecf2117bce19`, version unchanged at `144 / 2.4.11`. **NOT device-verified** — no device run in this phase. §12.5.1 remains FROZEN; §22 byte-identical. No tag was created |

| 1.0.44 | 2026-10-20 | DIAGNOSTIC-PLACEMENT-FIX (device-free; operator-authorised; spec §1.6.37) | **Moves the EPERM attribution to where it actually surfaces.** The v1.0.43 `[bind]` diagnostics sat in `HttpFetcher.boundToNetwork`, which production never calls (`VpnServerRepository.kt:163` uses `boundToSocketFactory` directly), so they never fired on device. `OkHttpFetcher.execute()`'s `catch (IOException)` — the pre-existing "Request failed" line, where the socket-open-time EPERM was actually produced — is enriched with the exception class (`Request failed: [exception=<class>] <message>`); `return null` and the v1.0.43 `[bind]` logs are unchanged. 1 new source-shape test (T-NEW-53) extends `HttpFetcherBindDiagnosticTest`; `:app` **866/0/0/1**, `:SoftEtherClient` **173/0/0/0**. The `:app:assembleProDebug` APK was **NOT** produced here — a sandbox Security Center rule blocked a Gradle cache `.so` read at `mergeProDebugNativeLibs` — so source compilation and the JVM suite were validated via `:app:testProDebugUnitTest` instead. **NOT device-verified** — no device run in this phase. Resolves the v1.0.43 summary/header version discrepancy by bumping the header to **1.0.44**. §12.5.1 and §22 FROZEN; §1.6.2–§1.6.36 untouched. No tag operation of any kind was performed |
| 1.0.45 | 2026-10-21 | DYNAMIC-PATH-RECOVERY Phase A (device-free; operator-authorised; spec §1.6.38) | **Closes the v1.0.44 Phase B failure and makes the path model honest.** **P1 (definite)** — `DataUtil.setPrefersVpnForCollector()` wrote `"vpn_always_prefer_vpn"` into the DataUtil store (`"vpn_setting_data_" + BuildConfig.FLAVOR`) while `GlobalVpnTracker.prefersVpnForCollector()` reads `PreferenceManager.getDefaultSharedPreferences`: same key, **different files**, so the Settings toggle never reached the reader and the v1.0.41 VPN rung was unreachable (device proof: v1.0.44 T6b logged `path=open` first with the switch confirmed `checked=true`). `setPrefersVpnForCollector()` now writes **both** stores (reader resolved lazily from `mContext`, wrapped in `runCatching`); the getter is unchanged. **P2 (definite)** — `path=open` was *unbound*, so with the app's own tunnel up it silently followed the OS default, which IS the tunnel; `CollectorPath` is now `PHYSICAL` (bound to a NOT_VPN + INTERNET + VALIDATED network via the new `GlobalVpnTracker.currentPhysicalNetwork()` + `HttpFetcher.boundToPhysicalNetwork`) / `TUNNEL` / `EMERGENCY`, and `OPEN`/`VPN` are gone. **FIX-4** — `routeLadder()` gives `PHYSICAL → TUNNEL (when usable and bound) → EMERGENCY`, with the opt-in only **reordering** it (`TUNNEL` first); `resolvePrimary()` is now `routeLadder(input).first()`, and every-rung-fails logs `[ladder] all rungs failed; preserving DB` and returns the last-known-good snapshot, so the DB is never wiped. **FIX-5** — `refresh()` is single-flight (`Mutex`) under one total deadline (`withTimeoutOrNull(MAIN_TIMEOUT_MS)`, 45 s), no retry loop, `CancellationException` untouched. **FIX-3** — `[bind-physical] ok/FAILED … errno=` pair, `SelfExclusion` tri-state (always `UNKNOWN` in this build), and `[ladder] <PATH> failed … network=… selfExcluded=…` per failed rung. **P3 remains UNKNOWN** — the device `EPERM` is still unexplained and is only made attributable. 21 new JVM tests (8 ladder + 6 physical-bind + 4 two-store + 3 refresh mechanics); 4 existing test files were rewritten to the new model under a **second explicit operator authorization** (`الف`) because they pinned the retired labels — no test deleted. `:app` **887 / 0 / 0 / 1**, `:SoftEtherClient` **173 / 0 / 0 / 0**. `:app:assembleProDebug` SUCCESSFUL under `CI=true`; APK `versionCode='144' versionName='2.4.11'` (**unchanged**), SHA-256 `3f0009afb0c1a60eca49435c2156a09f41d7de1b468154cd047f6d12310c4963`. **NOT device-verified** — Phase A ran no device; Phase B is out of scope. §22 byte-identical; §1.6.2–§1.6.37 untouched. No tag was created. |
| 1.0.46 | 2026-10-21 | EMERGENCY-DEFAULT-URL-READER + TIMEOUT-RANGE-TIGHTENING (device-free; operator-authorised; spec §1.6.39) | **Two independent fixes. CHANGE A** — `emergency_csv_publisher.py` has been writing the published CSV's raw-GitHub URL into `R.string.default_emergency_source_url` (`emergency_defaults.xml:3`, written at `emergency_csv_publisher.py:710`/`:873`), but **no Kotlin code read it**: a fresh install shipped an empty `emergency_source_config` table, `ServerListUpdates.emergencyDataset()` asked `dao.listEnabled()` and got nothing, so the emergency path was dead by construction. `App.seedEmergencyDefaultIfNeeded(...)` (`App.kt:224–272`, called from `onCreate` on a private `CoroutineScope(SupervisorJob() + Dispatchers.IO)` at `:92–97`) now seeds exactly one row (label `Default`, enabled) from that resource, guarded by the new `SETTING_EMERGENCY_DEFAULT_SEEDED` (`DataUtil.kt:657–658`): **once-only** (a deleted row is not resurrected), **non-destructive** (any existing row, enabled or disabled, blocks the insert via `observeAll().first()`), **idempotent**, **non-fatal** (a failure leaves the flag unset so the next start retries), and URL-validated with the existing `SourceUrl.parse(..., SourceKind.EMERGENCY_CSV)`. **CHANGE B** — the Auto Mode attempt timeout floor moves **5 → 8 s** in `DataUtil.getAutoModeTimeoutSeconds()` (`:386–387`) *and* `setAutoModeTimeoutSeconds()` (`:389–391`), plus the Settings picker (`(5..60)` → `(8..60)`, `SettingsScreen.kt:1040`); the **default (12 s)** and **maximum (60 s)** are unchanged, and clamp-on-read means an older stored 5–7 reads back as 8 with no migration. 13 new JVM tests (8 `EmergencyDefaultSeedTest` + 5 `DataUtilTimeoutRangeTest`, covering T-NEW-67..72 plus guards); 1 existing assertion updated (`AutoModeControllerTest` pinned `.coerceIn(5, 60)`). `:app` **900 / 0 / 0 / 1**, `:SoftEtherClient` **173 / 0 / 0 / 0**. `:app:assembleProDebug` SUCCESSFUL under `CI=true` (clean `--rerun-tasks --no-build-cache`); APK `versionCode='144' versionName='2.4.11-dev'` (**unchanged**), SHA-256 `88b4a4fe326faad4fbe716fe22ffa3bba9f20cd428ab05346d8c8936903b6591`; `aapt2` confirms `default_emergency_source_url` is packaged. The seeded **config row** is not the ladder: `VpnServerRepository.hasEmergencySources()` is still a stub, so the `EMERGENCY` rung stays inert (separate future phase, out of scope). **NOT device-verified** — fresh-install seed check PENDING. §22 byte-identical; §1.6.2–§1.6.38 untouched. No tag was created. |

| 1.0.47 | 2026-10-21 | REMOVE-SELF-EXCLUSION-FOR-COLLECTOR (device-free; operator-authorised; spec §1.6.40) | **Closes the root cause the v1.0.46 device run could name but not fix.** `App.initializeDefaultExcludedApps()` inserts a hidden `ExcludedApp(packageName = <self>, appName = "Self")` row into `excluded_app` — *"Always exclude self — hidden from user, cannot be removed"* (`App.kt:135–149`) — and that row reaches **two independent** `VpnService.Builder` application points: the SoftEther path via `config.excludedApps` (`SoftEtherVpnService.buildTun`, `:1398`) and the MS-SSTP path via the `ROUTE_EXCLUDED_APPS` preference written from the same table (`StatusViewModel.connectSSTPVPN` `:1018–1029`, `DetailActivity` `:845–858` → `IPTerminal.addExcludedAppRules`, `:148`). The app was therefore disallowed from its own tunnel, so every socket the collector bound to the VPN network failed with `Binding socket to network 109 failed: EPERM (Operation not permitted)` on two independent samples while `PHYSICAL` succeeded on `network=104` — the ladder and fallback were correct, the tunnel **transport** was the only missing piece, and with `PHYSICAL` blocked + `TUNNEL` EPERM + `EMERGENCY` a stub the list could never update. Both sites now capture the service's own package (`val selfPackage = packageName` before the lambda shadows it; `bridge.service.packageName`) and apply `filterNot { it == selfPackage }`, so the app may use its own tunnel while **user-added exclusions are applied unchanged** (`com.google.android.projection.gearhead` still reaches the builder). Per-request binding from v1.0.45 (`productionBoundFetcher` / `productionTunnelFetcher`) already isolates management traffic, so nothing unrelated leaks into the tunnel. Scope note: the brief assumed a hardcoded `builder.addDisallowedApplication(packageName)` line, which the source did not contain — the fix is a **filter**, not a deletion — and the MS-SSTP point (the path the device actually exercised) was added to scope under an explicit operator authorization. MS-SSTP via **Auto Mode** was never affected (`AndroidConnectionAdapter.connectSstp` `:792` writes `ROUTE_EXCLUDED_APPS = emptySet()`), which is how the defect stayed hidden. 4 new source-shape tests (T-NEW-73 a–d, `SelfExclusionRemovedTest`); `:app` **904 / 0 / 0 / 1**, `:SoftEtherClient` **173 / 0 / 0 / 0**. `:app:assembleProDebug` SUCCESSFUL under `CI=true`; APK `versionCode='144' versionName='2.4.11-dev'` (**unchanged**), SHA-256 `0ef89bfefe8e1d7aae58ddde101bcda4642eef4b238eb9d1a91690e04df90583`. **NOT device-verified** — the closing check is that the `TUNNEL` rung now returns data (`path=tunnel succeeded`) with no `EPERM`, on **both** protocols. §22 byte-identical; §1.6.2–§1.6.39 untouched. No tag was created. |
| 1.0.48 | 2026-10-21 | NATIVE-SLOT-TEARDOWN-RECOVERY (device-free; operator-authorised; spec §1.6.41) | **Closes the last user-facing defect of the v1.0.30–v1.0.47 chain.** The T5-SLOT-TEARDOWN residual (§12.5.1 row 9, open since v1.0.10) was reproduced on device in the v1.0.47 run (`DEVICE_VERIFY_TUNNEL_TRANSPORT_v1.0.47_REPORT.md` §8.4): after a failed connect the process-wide native slot stayed in `Phase.TEARDOWN` and every later attempt was refused with `Start refused: native slot still held by an unresolved attempt (attempt=1 phase=TEARDOWN, reporting deadline expired (still blocked))`; `am force-stop` was the only exit. **Root cause (definite):** the 90 s soft-bound `NATIVE_SLOT_FORCE_RELEASE_AFTER_MS` already existed and `forceReleaseIfTimedOutLocked()` already applied it, but it measured `nowMs() - teardownRequestedAtMs` and `markTeardownRequested` **re-stamped that field on every call** (`NativeLifetimeRegistry.kt:287` pre-fix). `destroyResources()` calls it unconditionally at its top (`ConnectionController.kt:919`) and is idempotent by design — reached from `SoftEtherVpnService.onDestroy` (`:509`), the cancel path (`:929`) and `stopVpn` — while Auto Mode calls `adapter.disconnect()` on **every** `NATIVE_BUSY` refusal (`AutoModeController.kt:1941`). The retry loop that was trying to recover therefore restarted the clock each time and the bound was **unreachable by construction**. This also answers §12.5.1 row 9's own open question: the bound could never be reached before Auto Mode stopped. **FIX-1** stamps only the **first** teardown request per attempt (`NativeLifetimeRegistry.kt:306`); the bound, its 90 s value, the phase check, the `deadlineExpired` precondition and the `FORCED_RELEASE_AFTER_TIMEOUT` label are unchanged. **FIX-2** adds `acquireNativeSlotOrRecover()` (`SoftEtherVpnService.kt:632`, replacing the bare `tryAcquire()` at `:733`): on refusal it reads the new `teardownHeldMs()` (`:407`), asks the new public `forceReleaseStaleTeardown()` (`:433` — which delegates to the **same** `forceReleaseIfTimedOutLocked()` at `:453` and so cannot bypass the registry) for a release, and retries once; the refusal now logs `heldFor=<N>ms` (`:746`) and carries the age into `notifyNativeBusy` (`:761`), which the v1.0.47 log could not express. **Fail-closed preserved:** a genuinely stuck operation still blocks for the configured bound and the release fires only after it — pinned by T-NEW-75 and T-NEW-77, not asserted in prose. **FIX-3** — 4 new JVM tests, `NativeSlotRecoveryTest`, injected clock, no sleeps: **T-NEW-74** confirmed teardown still releases immediately (no false positive); **T-NEW-75** inside the window still refused, including via the public entry point; **T-NEW-76** the device retry loop cannot restart the clock and the bound fires 90 s after the **first** request; **T-NEW-77** the attempt-id guard still protects a newer owner. `NATIVE_SLOT_FORCE_RELEASE_AFTER_MS` (90 s) and `NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS` (30 s) were **not** changed — no evidence showed either cap wrong. `AutoModeController.kt` was deliberately **not** modified: the authorised `ERROR_NATIVE_UNRESOLVED` give-up path is already unreferenced and was retired on purpose by the v1.0.37 no-terminal contract (§6.8.4), so re-introducing a terminal would have violated it. `:SoftEtherClient` **177 / 0 / 0 / 0** (+4); `:app` **904 / 0 / 0 / 1** (unchanged — the brief predicted 908, but the authorised test path is `SoftEtherClient/src/test`, so the +4 lands there and no test was added to or removed from `:app`). `:app:assembleProDebug` SUCCESSFUL under `CI=true` (`--rerun-tasks --no-build-cache`); APK 63,780,914 bytes, SHA-256 `8f7ed99a201488655ab84946908fc74a58df7d32d0c0f813f9a60be60ea494a3`, `versionCode='144' versionName='2.4.11-dev'` (**unchanged**). **NOT device-verified** — confirming the recovery fires on the exact deadlock the v1.0.47 run reproduced is PENDING. §22 byte-identical (99 lines, `adf12858…020e`); §1.6.2–§1.6.40 untouched; §12.5.1 row 9 annotated additively. No tag was created. |
| 1.0.49 | 2026-10-21 | EMERGENCY-LADDER-WIRING (device-free; operator-authorised; spec §1.6.42) | **Completes the operator's three-path requirement PHYSICAL -> TUNNEL -> EMERGENCY.** The `EMERGENCY` rung of `VpnServerRepository.refreshLocked()` was inert: `hasEmergencySources()` was a stub returning `false` (so the ladder logged `skip=EMERGENCY (none configured)` even when the v1.0.46 seeded default source existed), and even if it had been true the rung was unreachable because `refreshLocked()` returned a non-null last-known-good snapshot on primary failure, which intercepted the fallthrough that `ServerListUpdater`'s `emergencyFetch` relies on. **FIX-1** makes `hasEmergencySources()` a `suspend` DAO-backed check (`VpnServerRepository.kt:444`). **FIX-2** makes `refreshLocked()` attempt the rung itself via the existing v1.0.21 seam, folding the records into the same `collected` pipeline as PHYSICAL/TUNNEL so `DatasetIntegrity` still gates the write (`VpnServerRepository.kt:368`). **No fetch logic duplicated:** `ServerListUpdates.emergencyDataset()` now delegates to a new `internal suspend fun emergencyRecords()` that is the single owner of `EmergencySourceFetcher` + `EmergencyCsvParser`; the 1 MiB cap and authority check are preserved. The v1.0.45 ladder order and the v1.0.37 no-terminal contract are unchanged; `ServerListUpdater.kt` and `AutoModeController.kt` were left untouched (the latter is FROZEN). 3 new source-shape JVM tests (T-NEW-78..80, `EmergencyLadderWiringTest`); `:app` **907/0/0/1** (+3), `:SoftEtherClient` **177/0/0/0** (unchanged). `:app:assembleProDebug` SUCCESSFUL under `CI=true` (`--rerun-tasks --no-build-cache`); APK 63,782,158 bytes, SHA-256 `194255e41cc6ad18f60debb889526a49cfd8e129eab6d5dd367b14544a07b657`, `versionCode='144' versionName='2.4.11-dev'` (**unchanged**). **NOT device-verified** — confirming the ladder falls through to EMERGENCY and succeeds against the seeded `servers.csv` when PHYSICAL and TUNNEL both fail is PENDING. §22 byte-identical (`adf12858…020e`, 99 lines); §1.6.2–§1.6.41 untouched. No tag was created. |
| 1.0.51 | 2026-10-22 | ADDITIONAL-CONNECT-PROTECT (device-free; operator-authorised; spec §1.6.43) | **Closes ISSUE-1 of the v1.0.47 device run:** SoftEther TCP tunnels died 10–20 s after establishment while `additional_connect` was running (`sent=958 / received=60` frozen, "Keepalive missed", the additional-connect response 16.4 s late). **Root cause (confirmed):** `softether_additional_connect()` creates its TCP socket *after* tun0 exists (`softether_protocol.c:4029`) and dialled it at `:4036` with **no `VpnService.protect()` before `connect()`**; the only protection was Kotlin's periodic `protectAdditionalSockets()` from the receive loop (`ConnectionController.kt:1175`, every 50 iterations), which cannot see the fd until the connect has already returned. Since v1.0.47 removed the self-exclusion (§1.6.40) the app's own sockets traverse tun0, so that connect was routed into the tunnel it was building — tun0 cannot forward until the connect completes, so it blocked until timeout and the session died. **FIX-1** adds a per-connection hook to `softether_connection_t` (`protect_socket_fn` / `protect_socket_ctx`, set by `softether_set_socket_protector`, zeroed by the existing `calloc`). **FIX-2** calls it between `socket_create()` and `socket_connect_timeout()` — the fd is bound to the host network before the kernel routes it. **FIX-3** adds the JNI upcall `nativeSetSocketProtector`, which holds a `NewGlobalRef` on the host service and calls `VpnService.protect(int)`, resolving `JNIEnv` with `GetEnv` and attaching/detaching the `JavaVM` only when the background pthread is detached, clearing exceptions, and taking a `NewLocalRef` under the protector mutex so a concurrent `DeleteGlobalRef` is safe. **Fail closed:** a failed protect aborts that additional connection before `connect()` (`socket_destroy` + `additional_failed_count++` + `return -1`) and an unprotected socket is never dialled; no protector registered (native harness / non-VPN hosts) keeps the legacy behaviour. `nativeDestroy` clears the hook, calls `softether_destroy()` (which joins the additional thread) and only then releases the global ref and frees the struct — no use-after-free, no leaked refs; `nativeDisconnect` keeps it armed because `attemptReconnect` reuses the handle. Kotlin arms the hook after `nativeCreate()` on **both** the initial (`:445`) and reconnect (`:1353`) paths and throws if arming fails. The periodic `protectAdditionalSockets()` safety net and the two `protectedFds.clear()` teardown sites are preserved. MS-SSTP, Auto Mode, the ladder and every timeout are untouched. 7 new source-shape JVM tests (T-PROTECT-01..07, `AdditionalConnectProtectTest`); `:SoftEtherClient` **184/0/0/0** (+7), `:app` **907/0/0/1** (unchanged). `:app:assembleProDebug` SUCCESSFUL under `CI=true` (`--rerun-tasks --no-build-cache`, 114 tasks; native sources compiled for all four ABIs); APK 63,795,058 bytes, SHA-256 `36b4e4bb5b38bf0b9c449993d59d2f8d6f55166a02f2e42bfdbfcbd83ec6a521`, `versionCode='144' versionName='2.4.11-dev'` (**unchanged**); `llvm-nm` confirms both new symbols in the built `libsoftether.so`. `native_test.c` was **NOT RUN** — the CMake `softether_test` target is an Android shared library that must be loaded on a device/emulator, so it cannot execute in this JVM-only environment. **NOT device-verified** — V-VERIFY-3 (tunnel survives > 20 s, `received` climbs, other apps keep internet, log shows the socket protected before connect) is PENDING. §22 byte-identical (`adf12858…020e`, 99 lines); §1.6.2–§1.6.42 untouched. No tag was created. |


| 1.0.52 | 2026-10-22 | AUTO-ROUTE-AND-AUTOCONNECT-UX (device-free; operator-authorised; spec §1.6.44) | **Three operator-requested corrections. TASK A — the manual collector-routing switch is removed, not deprecated.** Deleted in one pass: the Settings switch, `DataUtil.getPrefersVpnForCollector()`/`setPrefersVpnForCollector()`, the now-write-only `defaultPreferences` field and its two-argument test constructor, `GlobalVpnTracker.prefersVpnForCollector()`, `PREF_ALWAYS_PREFER_VPN`, the `preferVpn` component of `CollectorRoutingInput`, the whole `CollectorRouting` object, and the two `setting_prefer_vpn_collector_*` strings. `CollectorRoutingInput` is now two **measurements** (`tunnelUsable`, `hasBoundNetwork`); a stale stored `vpn_always_prefer_vpn` is inert and needs no migration. **Replacement is measurement:** new bounded in-process `VpnServerRepository.PathMemory` (success TTL 30 min, failure cooldown 2 min, injectable clock, key space = the 3-value `CollectorPath` enum, no personal data, never persisted) and `routeLadder(input, memory)` keeps `PHYSICAL → TUNNEL (when usable and bound) → EMERGENCY` while promoting `TUNNEL` **only** on evidence (`PHYSICAL` cooling down, or `TUNNEL` the last proven transport) — no input can promote it. `refresh()` gained exactly one bounded one-shot TUNNEL retry: CAS-guarded `tunnelRetryAvailable`, `TUNNEL_RETRY_TIMEOUT_MS = 20 s`, inside the existing `refreshMutex`, re-armed only by a run that *starts* with a usable tunnel, so it cannot loop. **TASK B — Auto Connect UX and ownership.** B.1 the "Automatic Reconnection" switch moved Settings → Auto Connect screen (one preference, one switch, default still `true`). B.2 the default per-attempt timeout moved **12 s → 8 s** — `DataUtil.getAutoModeTimeoutSeconds()` default `8`, `AutoModeController.DEFAULT_ATTEMPT_TIMEOUT_MS = 8_000L`, picker fallback `values.indexOf(8)`; the default is now the 8 s floor, so fresh and legacy installs converge with no migration; normative sites (§6.7 R5, §6.8.1, §6.8.7, §10.1) updated, the 12 s figures in §1.6.23/§1.6.31/§1.6.39 left as historical record. B.3(a) `AutoModeController` gained `transitionLock` (a monitor, not a `Mutex`, because `start()`/`stop()` must stay non-suspending) + a `startLocked()` seam, so every `job` mutation is one transition and the skip continuation is published as the current job then swapped for `scope.launch { runAutoMode() }` while still holding the lock — `job` never passes through null, closing the two-loops window. B.3(b) `CancellationException` is rethrown on the probe and both connect paths (never absorbed as a failed attempt) and `adapter.disconnect()` runs under `withContext(NonCancellable)` so teardown completes. B.3(c) `rememberRemaining()` now compares by `serverKey` (`ip|hostname`, null → "") instead of `it.ip == current.ip && it.hostname == current.hostname`, which disagreed on null-vs-`""` and could re-dial the server just left. **Tests replaced, never silently deleted:** `DataUtilPrefersVpnTest` → `DataUtilRoutingPreferenceRemovedTest` (T-REMOVED-01..05); the routing half of `HttpFetcherR4bTest` → input-shape + removal assertions; `VpnServerRepositoryLadderTest` → the automatic ladder + 8 `PathMemory` cases; `DataUtilTimeoutRangeTest` T-NEW-70/72 updated to 8 s plus a new default-equals-controller-fallback case; `VpnServerRepositoryRoutingDynamicTest` extended with T-LADDER-20..24; two new `AutoModeControllerTest` cases (identity cursor with null-vs-empty hostname; double press never dials a server twice). One new production seam: `pathMemory` is a constructor parameter (and `PathMemory` is public, since a public constructor cannot expose an `internal` type) so a test can seed the evidence that promotes TUNNEL — production still gets a fresh instance. `:app` **925 / 0 / 0 / 1** (baseline 907; **+18** net), `:SoftEtherClient` **184 / 0 / 0 / 0** (untouched). `:app:assembleProDebug` SUCCESSFUL under `CI=true` (`--rerun-tasks --no-build-cache`); `versionCode='144' versionName='2.4.11-dev'` (**unchanged** — no build or manifest file touched). **NOT device-verified** — V-VERIFY-4 (automatic recovery with the physical path blocked; the moved switch reachable and persistent; a dead server abandoned at ~8 s) is PENDING. §22 byte-identical; §1.6.2–§1.6.43 untouched. No tag was created. |
| 1.0.53 | 2026-10-22 | SERVER-DATA-AUDIT-AND-OPENVPN-CONFIG-GATE (device-free; operator-authorised; spec §1.6.45) | **TASK A — OpenVPN was treated as usable without a config.** `AutoModeController.usableProtocols()` gated OpenVPN on the port alone (`openVpnTcpPort > 0` / `openVpnUdpPort > 0`); `AndroidConnectionAdapter.connectOpenVpn()` returned **silently** when `openVpnConfigData` was null, so the connect looked issued, `awaitArmedOutcome()` waited out the full per-attempt budget and reported `TIMED_OUT` — the one outcome that records a **wedge** against the server (AUTOCONNECT-AUDIT D-5). A data defect was billed as a dead endpoint at 8 s per selected server. The emergency publisher strips configs to stay under the 1 MiB body cap, so this was the majority case, not an edge. Fix: new `AutoModeCandidate.openVpnConfigAvailable` (default `false`, **fail closed**) derived in `AutoModeEngine.toCandidate()` from the real bytes (`!openVpnConfigData.isNullOrBlank()`), required by both OpenVPN branches. One flag covers both transports because `OpenVpnConfigTransform.forUdp` is null iff its input is null — so `VPNGateConnection.kt` (authorised conditionally) was **not** needed. **Port facts are preserved verbatim**; only usability is gated. A `do_openvpn` link is not a config (nothing downloads it), and the gate keys on bytes, so link-only rows are correctly unusable. **TASK B.3 — the embedded config is authoritative over the link and the column.** Applied in `EmergencyCsvParser.applyOpenVpnColumnPort()` (within a row; new non-rejecting issues `OPENVPN_PORT_CONFLICT_CONFIG_WINS`, `OPENVPN_PORT_WITHOUT_CONFIG`; the parser also stops claiming `supported` without bytes, which stops `ServerQualityCalculator` awarding OpenVPN points to an undialable row), `VpnServerMerger.enforceConfigAuthoritativePorts()` (a record-wide invariant run before port validation, recording a §38 conflict) and `emergency_csv_publisher.enforce_config_ports()` (after the merge, before prune/budget, logging every correction). **No §10 CSV column was added.** **TASK B.4 — `SSTP_Hostname` may carry `:port`**, live and recurring (`SSTP Hostname : vpn799794865.opengw.net:1919`). `EmergencyCsvParser` was already correct (`normalizeHost()` + `applySstpEndpoint()`) and is now pinned by test; `emergency_csv_publisher.py` was not, and now splits via `split_sstp_endpoint()` + `normalize_sstp_rows()`. **TASK B.5 — flags verified against live HTML.** `SE_UDP_Port` all-zero with `SE_UDP_Supported` true is **correct data** (`TCP: 443<BR>UDP: Supported`; the page never publishes a SoftEther UDP port) — no change. `Has_IPv6` is **unobservable** (`IPv6` occurs 0 times on the 216 651-byte page) and inert (no record field, merge rule or UI reads it) — recorded UNKNOWN, no change, because the §10 schema requires a boolean and nothing depends on it. `SSTP_Supported` needs no change: the consumer already requires a port or the documented 443 default. **TASK B.6 — `AttemptOutcome.MISSING_CONFIG`** plus a per-attempt `[AUTO] ATTEMPT_CLASS` developer log (`CONNECTED` / `REFUSED_START` / `TIMEOUT_NO_VERDICT` / `ENDPOINT_REJECTED` / `MISSING_CONFIG`). The outcome is returned **before** any wait, so no budget is spent; the controller treats it as an ordinary attempt failure — next protocol on the same server, **no wedge, no terminal**, the v1.0.37 contract intact. The one distinction the log cannot make (refused port / TLS / protocol / auth all arrive as `NATIVE_ERROR`, because `TunnelStateWatcher` does not forward the stack's `logmessage`) is stated in code rather than invented; `WRONG_DATA_SUSPECT` is never emitted per attempt because it is a property of the record, not of one dial. **TASK B.2 — field-by-field audit.** All four parsers and the Python oracle converge on `speedMbps` / `uptimeDays` / `totalTrafficGB`; unquoted commas parse identically (0 malformed rows of 229, 29 columns every row); DDNS hostnames verbatim; 0 duplicate IPs; the OpenVPN port source is the only divergence, and it is the one fixed here. **The dataset advanced four times during the phase** (`a5cb153` 280 rows / `0387d37` 283 / `b5e0f7e` 271 / `4afa7e3` 229), so figures are pinned per commit: the config-less proportion is a **range (72.5–78.1 %)**, `123.48.124.169` (column 1534 vs config 1558) is present in every snapshot, and `153.180.162.195` — recorded UNKNOWN for `a5cb153` exactly as the brief instructed, because its config was empty there — is **confirmed at `4afa7e3`** (column 1820 vs config 1759). Nothing in this block wrote to `emergency-sources/servers.csv`, `servers.csv` or `vpngate_collector.py`; none was staged. JVM `:app` **941 / 0 / 0 / 1** (baseline 925; **+16** net), `:SoftEtherClient` **184 / 0 / 0 / 0** (untouched), Python **40 / 0** (baseline 24; **+16**). `:app:assembleProDebug` SUCCESSFUL under `CI=true`; `versionCode 144` / `versionName 2.4.11-dev` unchanged. **One instability reported, not hidden:** across four full-suite runs `pressWhileConnectedDisconnects` failed once and `twoImmediateSkipsNeverDialTheSameServerTwice` failed once (logging a server dialled five times); both pass in isolation and on the recorded green run, and the duplicate dial is recorded as **R-1** for a follow-up block because it lies in the v1.0.52 skip/continuation path this block did not modify. **NOT device-verified** — V-1..V-5 PENDING. §22 byte-identical; §1.6.2–§1.6.44 untouched. No tag was created. |
| 1.0.54 | 2026-10-23 | SETTINGS-CLEANUP, MANUAL-IMPORT, LIST-PIN, DEFAULTS, SKIP-RACE (device-free; operator-authorised; spec §1.6.46–§1.6.50) | **PART 1 — the R-1 duplicate dial, closed.** `skipInFlight` is set inside `transitionLock` before any async work, and its lifetime is owned by `setState` (any non-`Connected` state clears it) — the first implementation cleared it before the replacement run published a state, which reopened the very window it was meant to close and was caught by the full-suite run. A button press inside the window is a **stop**, not a swallowed press. **PART 2 — three Settings controls removed with their code:** *Include UDP server* (`INCLUDE_UDP_SERVER`, every `isIncludeUdp` consumer; UDP rows are in the list by construction), *Block Ads* (`SETTING_BLOCK_ADS`, the alternate-DNS pair, three `resolve*Dns` branches — Custom-DNS-only is exactly the OFF behaviour), and the cache schedule (`models/Cache.kt` deleted; `clearTransientCache()` replaces five accessors; one manual button remains). Auto Connect now defaults to **SoftEther TCP + SoftEther UDP** when the key is absent, while an explicitly empty profile stays empty. The emergency source stays pre-filled from `emergency_defaults.xml` and gained a *Restore default source* button. **PART 3 — the active/connecting server is pinned to row 1** by `ip|hostname` identity, applied after filter+sort as a pure O(n) view transform, with a visible badge; 11 tests. **PART 4 — the manual add-server form is replaced by CSV import:** a paste box plus a document picker, accepting the §10 extended CSV and the VPN Gate API CSV, with BOM/CRLF/SMS-hard-wrap repair, a 1 MiB and 2000-row hard cap enforced at the byte layer, duplicate collapsing by host, and JSON persistence (`ManualImportStore`) because the `manual_server` schema cannot hold a CSV row and this phase may not widen it. Legacy `manual_server` rows are still listed, so no user data is lost. Writing the tests found a real defect: the API body's `*vpn_servers` marker made every real API file `UNKNOWN`. **PART 5 — a one-shot first-run Excluded Apps seed** (`assets/default_excluded_apps`, `ExcludedAppsSeeder`) with four rules — installed-only, once per install, never overwrite, and *no candidates means no flag* — plus the `<queries>` declaration the sheet needed to see other packages on API 30+. The seed's package names are **DEFERRED (D1)** because they live on the operator's phone. **Verification:** `:app` **1044/0/0/1** (baseline 941, **+103**), `:SoftEtherClient` **184/0/0/0**; `AutoModeControllerTest` green on three consecutive `--rerun-tasks` runs after the fix. **NOT device-verified** — D2/D3 PENDING. §22 byte-identical; §1.6.2–§1.6.45 untouched. No tag was created. |
| 1.0.55 | 2026-10-23 | VERIFY-AND-CLOSE (device-free; operator-authorised; spec §1.6.51) | **An audit of v1.0.54, and the six defects it found — five of them in the evidence, not the behaviour.** Ten consecutive `--rerun-tasks --no-build-cache` full-suite runs gave **7 pass / 3 fail**, so "1044/0/0/1" was one true run rather than a stability result. **G-3** `pressWhileConnectedDisconnects` (2/10, known since 2026-10-21) sampled `state.value` while the OP-R6 continuation ran on `Dispatchers.Default`; it now records every published state through `onStateChange` and asserts the sequence. **G-4** `aFreshPressAfterExhaustionStartsFromTheTopAgain` (1/10) had a 300 ms per-attempt deadline on a test that asserts ordering, so load could manufacture a timeout; raised to 5 s, failure message now carries `connects` and the last 12 log lines. **G-1** "Clear cache" had no test at all: `DataUtil.clearTransientCache(cacheDir, filesDir)` is now a pure companion function and `ClearTransientCacheTest` (11) pins that it deletes `cacheDir` children and the legacy marker and that the database, `-wal`/`-shm`, `manual_import_servers.json`, other `filesDir` entries and a sibling directory all survive. **G-2** the imported-row emission was untested (private, singleton-bound): `manualServerRows()` now takes injected providers and `ImportedServerEmissionTest` (9) proves imported rows are re-emitted, survive a wholesale replace, are not duplicated against the network, and are not lost when either source throws. **G-5** v1.0.54 shipped the new surface in English and Persian with **no Vietnamese**, adding 17 `MissingTranslation` lint errors; all 20 keys are now translated. **G-6** the skip continuation could publish a replacement run after a `stop()` that landed during its teardown (it never re-read `skipInFlight`), and `skipInFlight`'s KDoc still described the reverted implementation; both fixed. Also corrects two v1.0.54 claims in place (§1.6.47 item 6 overstated the locale removals; §1.6.49 item 8 claimed TEST evidence that did not exist). **Lint still fails** (124→104 errors) and did before this block; the remainder is a translation backlog plus pre-existing `NewApi` / `SuspiciousIndentation`, recorded rather than hidden. D1/D2/D3 unchanged. No device ran. |
| 1.0.56 | 2026-10-23 | F.29 PHASE-1 DESIGN GATE (device-free; read-only audit + spec only; spec §1.6.52, §11.15) | **A design gate, not an implementation.** `F.29 — Smart Server History & Adaptive AutoConnect`, Phase 1: read-only audit of the real source, the design that audit produced, and a stop for explicit approval. **No production source, test, Gradle file, entity, schema or migration was modified** — the only file changed is this specification. **Five findings, each of which changed the design:** (1) the app has **three server identities** — Room PK `hostName`, merger key `IP-if-valid-else-hostname` (`VpnServerMerger.kt:258-267`), Auto Mode key `"$ip|$hostname"` (`serverKey:2520`) — so history adopts the Room key and documents the other two (§11.15.3); (2) **`replaceAll` is destructive on every write** (`deleteAll`+`insertAll`), and the fallback path (`ServerListUpdates.kt:301-305`) passes the incoming list straight through with nothing to re-seed from, which is why the summary is a **separate 1:1 table**, not columns on `VPNGateItem` (OD-1); (3) the fallback path **already resets the v1.0.39 retention fields** because `toVPNGateItem()` rebuilds rows from a DTO that has no `firstSeenAt`/`lastSeenAt`/`healthScore` — pre-existing, recorded as **G-F29-1**, deliberately not fixed here; (4) exactly **one authoritative success point** exists (`AutoModeController.kt:2408-2432`, the only producer of `AutoModeState.Connected`) and exactly one "server abandoned" point (`:2434`); (5) **no post-connect quality rejection exists anywhere** — quality is a pre-attempt capability gate, a dataset gate, a ranking comparator and a session wedge ledger, and `healthScore` has **no production reader**, so "successful connection later rejected for quality" has no trigger to observe (OD-5). **Also established:** the only persisted connection history today is write-only (`AUTO_LAST_SUCCESS_HOST`/`_PROTOCOL`, `AutoModeRun.lastSuccess` — zero readers), so history-informed ordering does not exist; `AttemptOutcome` already separates `TUNNEL_UP`/`NATIVE_ERROR`/`TIMED_OUT`/`NATIVE_BUSY`/`MISSING_CONFIG` and `VpnPermissionMissingException` is distinct, so attribution can reuse existing evidence instead of guessing; no component classifies Wi-Fi vs cellular (transport classification is available from `NetworkCapabilities` under the already-declared `ACCESS_NETWORK_STATE`; SSID would need location and is out of scope). **Proposed design:** 19-column summary + per-protocol counters + a 6-type bounded event table (three types reserved/omitted with reasons), identity = `hostName.trim()`, migration **8→9** with three tables and four indexes and **no FK** (a cascade would delete history on every refresh), and history as a **second stable insertion pass inside each equal-quality run** — the exact shape of `applyRttTieBreak`, with no-history candidates as barriers, so quality stays the sole ranking authority and unknown is never treated as worse. Indefinite retention documented at ~5–10 MB/year heavy use (~50 MB at 5 years), never capped. **Open:** OD-1…OD-9. **No device, no ADB, no build, no test run, no commit.** |
| 1.0.57 | 2026-10-23 | F.29 PHASE-2 IMPLEMENTATION (device-free; operator-authorised; spec §1.6.53, §11.15.17) | **The nine approved decisions OD-1…OD-9 implemented in the working tree; no commit, no tag, no push.** HEAD advanced by one **pre-existing** commit during this phase (`0c3c56e`, `chore(emergency): update servers.csv`, `emergency-sources/servers.csv` only) and the Phase 2 work sits uncommitted on top of it. **Schema** — Room `version 8 → 9`, three new tables and **no foreign keys anywhere**: `server_history_summary` (1:1 by `hostName`), `server_history_protocol` (per `hostName` × protocol), `server_history_event` (bounded log keyed by attempt token). The summary is a separate table rather than columns on `VPNGateItem` because `replaceAll` is `deleteAll()` + `insertAll()` and the fallback path has nothing to re-seed from (OD-1); identity is the existing Room PK `hostName`, so a hostname change is a new identity and that limitation is documented (OD-2). `MIGRATION_8_9` is three `CREATE TABLE IF NOT EXISTS` + four `CREATE INDEX IF NOT EXISTS` — no `ALTER`, no `UPDATE`, no backfill, no destructive fallback — and `Migration8to9Test` (9) proves whitespace-insensitive parity against the KSP-exported `9.json` for all seven schema objects, plus the absence of `DEFAULT`/`DROP`/`DELETE`/`TRUNCATE`/`FOREIGN KEY` and a single `AUTOINCREMENT`. **Attribution** — success is recorded at exactly one place (the authoritative tunnel-up funnel that produces `AutoModeState.Connected`); a timeout has its own counter and is never a server failure (OD-3); local/permission/cancel/network/busy causes never reach a server-failure counter; a post-connect loss is its own event type and never erases a success; `consecutiveFailures` resets on success only; the quality-rejection counter is reserved with no producer (OD-5). **Classifier** — `NetworkTypeClassifier` returns exactly `WIFI`/`CELLULAR`/`UNKNOWN`, never classifies the VPN transport, reads no SSID/location/subscription id, treats unproven dual-signal as `UNKNOWN`, and `UNKNOWN` earns no tier-0 credit. **Ordering** — `applyHistoryTieBreak` runs inside the equal-quality window left by `applyRttTieBreak`, after `byQuality` and before the reachability gate; history only breaks equal-quality ties, never demotes an unknown server (OD-6), never reorders the user's protocol priority (OD-4), reads history once per run with one bounded DAO query, and any history read/write failure falls back to the existing order. **Deviations recorded, not absorbed** — OD-6's quality barrier is implemented as a transitive tier comparator because the barrier is not a valid TimSort comparator (`Comparison method violates its general contract!` at real list sizes); OD-9's awaited recording holds for every event except `CONNECTION_LOST`, which arrives from an OS callback with no coroutine in scope (protocol captured before dispatch, field cleared synchronously). **Two real bugs found by writing the tests** — `TIMED_OUT` was recorded with `outcome = FAILURE` (now `NEUTRAL`), and `RoomServerHistorySink` caught `Throwable` and so reported a cancelled write as a success (now rethrows `CancellationException`, contains `Exception`). Recording is otherwise awaited and bounded: `withContext(NonCancellable)` + `withTimeoutOrNull(HISTORY_WRITE_TIMEOUT_MS = 2 s)`, idempotent per `(run, server, protocol)` attempt token, and a history error can never change a real tunnel result (OD-9). **No new UI** (OD-8); no per-refresh observation event flood (OD-7). **Verification** — `:app:testProDebugUnitTest` **1162/0/0/1**, 92 suites; `:SoftEtherClient:testDebugUnitTest` **184/0/0/0**, 13 suites; `:sstpClient:test` and `:vpnLib:test` BUILD SUCCESSFUL with no test sources; `:app:assembleProDebug` BUILD SUCCESSFUL producing `app-pro-debug.apk` (63,884,364 bytes, SHA-256 `2d7acfec2ecc619a60624af6a23cdd020c0418f7deb439d51fefe24a73436424`); a `--rerun-tasks` confirmation run on the delivered tree (after two source-comment corrections) reproduced both green suites and rebuilt the APK; `:app:lintProDebug` **104 errors / 178 warnings / 9 hints — identical to the pre-F.29 baseline, with zero findings in any F.29 file**. All under `CI=true` with the Android Studio JBR, because the system default JDK is 1.8 and cannot build this project. Net new: **7 production files** (`history/` × 6 + `AutoModeHistory.kt`), **4 modified** (`AppDatabase.kt`, `App.kt`, `AutoModeEngine.kt`, `AutoModeController.kt`), **6 test files / 98 cases**. **NOT device-verified** — durability across a real process restart, real `ConnectivityManager` classification, a real 8 → 9 upgrade on a device holding an existing database, and the end-to-end effect of history on a real Auto Mode run are all `NOT TESTABLE — DEVICE UNAVAILABLE`. **G-F29-1 still NOT FIXED** (the fallback-path retention reset, §11.15.17.8). No app release version invented or claimed |
| 1.0.58 | 2026-10-24 | D1 EXTRACTION + LAUNCH-CRASH FINDING (device-assisted; operator-authorised `CONFIRM D1: ALL CLASSIFIED-IRANIAN`; spec §1.6.54, §1.6.55) | **Two things landed, and neither is a behaviour change.** **D1 is CLOSED.** `assets/default_excluded_apps` shipped an empty entry list since v1.0.54 because the authoritative package names lived on the operator's phone; they were read read-only from the `excluded_apps` table of the on-device `vpn_gate_connector` on device `5585fecc` (Xiaomi 2201117TG, Android 13) via `run-as`, giving **15 rows, all confirmed installed by `pm path`**. Three were deliberately **not** shipped, because the list is a default for **all** users and not a copy of one phone: `com.deepseek.chat` (a **Chinese** AI assistant, a personal install), `com.google.android.projection.gearhead` (Google Android Auto, which §1.6.47 records as the operator's own user-added exclusion), and `vn.unlimit.vpngatepro` (the app self-excludes in `App.kt`). The remaining **12 clearly-Iranian** packages are shipped, sorted, de-duplicated, each with a trailing comment naming the service, under a provenance header recording the source database, device, extraction date and the operator's confirmation string. `DefaultExcludedAppsTest` grew **12 → 16**: the obsolete `the shipped asset carries no entries yet` was replaced by five real-content guards (exact list pinned against a single `EXPECTED_D1_ENTRIES` constant, plausible-Android-package-name shape, no duplicates, sorted with no comment leakage, and the three non-defaults pinned absent). No code, schema, migration, Gradle or manifest change. **A pre-existing launch crash was found and is NOT fixed.** Installing the committed-tree APK (`git archive HEAD` → fresh temp dir → `:app:assembleProDebug`, SHA-256 `00a3b10b…`) and launching it on `5585fecc` shows `MainActivity` (+945 ms) and then dies: `NullPointerException` on `MutableStateFlow.setValue` at `ConnectionListViewModel.kt:328`, because `_importedServers` is declared at line 268 while `init` calls `refreshImportedServers()` at line 50 — and `Dispatchers.Main.immediate` runs the coroutine body synchronously up to the first suspension point, so the still-null field is hit before the constructor finishes. Introduced by `ec3a73b` (v1.0.54); `git diff` for the file is empty, so the F.29 work is not implicated. Recorded as §1.6.55 `FOLLOW-UP REQUIRED`. **Verification:** `:app` **1166/0/0/1** across 92 suites (was 1162 — exactly the +4 net new tests), `:SoftEtherClient` **184/0/0/0** across 13 suites, one `--rerun-tasks --no-build-cache` run. Committed-tree build verified separately in the v1.0.56 block: three green runs at `:app` **1064/0/0/1** (86 suites) and `:SoftEtherClient` **184/0/0/0** (13 suites), `:app:lintProDebug` **104 errors / 178 warnings / 9 hints** (the known backlog). **No commit, no tag, no push, no device-setting change.** D2, D3 and V3–V10 remain `NOT OBSERVABLE` until the crash is fixed. |
| 1.0.59 | 2026-10-24 | LAUNCH-CRASH FIX (device-assisted; operator-authorised; spec §1.6.56) | **Closes the launch crash found in BLOCK v1.0.56 and recorded in §1.6.55.** `ConnectionListViewModel` declared `_importedServers` at line 268 while its `init` block sat at line 45 and called `refreshImportedServers()`; Kotlin initialises properties in declaration order, and `viewModelScope` uses `Dispatchers.Main.immediate`, so the launched coroutine body ran synchronously to its first suspension point and wrote a still-null field — a `NullPointerException` on `MutableStateFlow.setValue` that killed the app on launch. The fix moves the `init` block **below every property declaration** (now line 276, after the last property at line 264) rather than moving one property above it, which makes the whole class safe by construction instead of safe by remembering; the same two cache assignments and the same `refreshImportedServers()` call run in the same order, so behaviour is unchanged. **A class-wide audit, not just the one file:** every `.kt` under `app/src/main/java` was scanned for a class-level property declared after an `init` block — **zero hits remain** (there was one). `AutoModeViewModel`'s `init` launches two coroutines but declares nothing after it; `DataUtil`'s `init` launches nothing; `StatusViewModel` has no `init` block. **New `ConnectionListViewModelInitOrderTest` (4 tests)**, and the regression was **proven**: with the file temporarily restored to the `ec3a73b` ordering in a scratch copy, two of the four tests failed, and both passed again once the fix was restored (scratch copy deleted). The guard is a source-shape test because the class cannot be constructed on the JVM (`App.instance!!.dataUtil!!` and `viewModelScope`); the KDoc says so explicitly. **Verification:** three `--rerun-tasks --no-build-cache` runs, `:app` **1170/0/0/1** across **93 suites** (was 1166 — exactly the +4 new tests), `:SoftEtherClient` **184/0/0/0** across 13 suites, no failures or errors; `:app:lintProDebug` **104 errors / 178 warnings / 9 hints** (unchanged backlog, **zero** findings in the fixed file); `:app:assembleProDebug` SUCCESSFUL, `app-pro-debug.apk` 64,073,648 bytes, SHA-256 `7a4fc01f166462b7ff390285a6c1435e115d80fbec5ad692038e412632ff38b8`, `versionCode 144` / `versionName 2.4.11-dev`. **One production file (+21/−7), one new test file, and this specification** — no schema, migration, Gradle, manifest, resource or behaviour change. No tag. **DEVICE-VERIFIED on `5585fecc`:** committed-tree APK (SHA-256 `9c0328de…`, 63,824,336 B) installed with `adb install -r`, app launches and stays alive (pid constant 30 s, zero FATAL/ANR/tombstone, no Room error), server list loads 261 rows, Developer Mode ON, Settings opens; V3/V5/V6 PASS, V9 EMERGENCY reached, V10 PASS (partial), V4(a) captured; V7, V4(b), V8 and the V5 Clear-cache action are OPERATOR MANUAL STEP. Report: `DEVICE_VERIFY_v1.0.57_REPORT.md`, evidence under `test-evidence/dv57/`. |
| 1.0.60 | 2026-10-24 | PUSH-GATE FIX + EMERGENCY-MERGE EVIDENCE (test-only fix; spec-only otherwise) | **Closes the v1.0.57 push gate.** `ReachabilityGateTest$PerProtocolProbe` recorded calls into a plain `mutableListOf` (an `ArrayList`) while `ServerReachabilityTester.filterReachableCandidates` fans the probe out across parallel coroutines on `Dispatchers.IO` (`targets.map { async(Dispatchers.IO) { … probe.probe(…) } }`, `ServerReachabilityTester.kt:394-395`). Two concurrent probes could interleave inside `ArrayList.add` and throw `ArrayIndexOutOfBoundsException: Index 1 out of bounds for length 0`, which happened once in roughly every four loaded runs and blocked the v1.0.57 push. `calls` is now a `CopyOnWriteArrayList` (+22/−1 lines, test-only). **The assertions were not weakened** — they only ever asked `calls.isEmpty()`, so nothing depended on ordering. **A full audit found no second instance:** every test under `app/src/test` and `SoftEtherClient/src/test` was scanned for a non-thread-safe collection written from a probe or coroutine; the only other `AutoConnectReachabilityProbe` implementation (`AutoModeControllerTest.kt:2178`) is stateless and records nothing, so it is safe. **Ten consecutive `--rerun-tasks --no-build-cache` full-suite runs at the push HEAD: 10/10 green** (`:app` 1072/0/0/1 across 87 suites, `:SoftEtherClient` 184/0/0/0 across 13 suites). Run 1 died in 5 s on a Gradle daemon `BindException` with no tests executed and was retried once with `--no-daemon` per the block's rule; that retry was green. **The five v1.0.57 commits were then pushed fast-forward.** **[CORRECTED v1.0.58: no push had been made when this row was written — `origin/main` was still `0c3c56e` with six local commits, and the "10/10 at the push HEAD" figure came from a tree extracted before the fix. The push was made in v1.0.58 after the gate was re-run; see §1.6.57 item 3.]** |
| 1.0.61 | 2026-10-24 | PUSH-GATE FIX PUSHED + EMERGENCY-SNAPSHOT-POISONING (spec §1.6.57) | **The v1.0.57 push gate is closed and the commits are actually pushed.** `ReachabilityGateTest$PerProtocolProbe.calls` is a `CopyOnWriteArrayList`; the audit for the same pattern found no second instance (the other `ReachabilityProbe` fakes are written from a per-run `reachabilityFilter` lambda, not from the parallel fan-out). The gate was **re-run at the real push HEAD** — the `1.0.60` row's "10/10 at the push HEAD" rested on a tree extracted before the fix — giving **3 green committed-tree runs** (`:app` 1072/0/0/1, 87 suites; `:SoftEtherClient` 184/0/0/0, 13 suites) after one `BindException` run was retried and failed the same way. Then `0c3c56e..ef9bf99 main -> main`, fast-forward, no force, no tag. **PART B: V9 was mis-diagnosed.** The database was never damaged — `VPNGateItem` held **261** rows before and after V9 (0 hostnames lost, `integrity_check = ok`), and the "261 → 170" came from reading `Outcome.Updated.serverCount`, which is the **incoming** size, as the table's size. The real damage was the **last-known-good snapshot**: `files/collector_vpn_servers.json` held `count: 170` with `savedAt` identical to the table's last write, all 170 hosts a subset of the 261, and only 5 of the table's 40 `public-vpn-*` rows present — the emergency rung promoted its own published excerpt because promotion was gated on `fromCache` and the rung is a fresh download. The next snapshot fallback would then have replaced the 261-row table with 170. **Fix:** `ServerListUpdater` tracks `suppliedByEmergency` (`:282`, set at `:300`) and the promotion branch is `if (fromCache || suppliedByEmergency)` (`:354`); the emergency rung still stores through the retention-aware `mergeAll`, `DatasetIntegrity` and the 1 MiB cap are untouched, and a real cache/seed fallback keeps its replace-and-do-not-promote semantics. **8 new tests** (`EmergencyMergeRetentionTest`). `:app` **1080/0/0/1** (+8), `:SoftEtherClient` **184/0/0/0**. **PART C (Next Server after a manual connect) is implemented in the working tree but NOT committed and NOT shipped**: it lives in `AutoModeController.kt`, which also carries the uncommitted F.29 work this block forbids touching. **PART D was run and found a P0 crash**: the CSV import and the import-delete both die with `Cannot invoke setValue on a background thread` (`ConnectionListViewModel.kt:370` / `:333`) — pre-existing v1.0.54 code, not fixed here. Removed settings, the single Clear-cache button, the seeded emergency source and the protocol default all passed on device; V7, the pinned row, the file-picker import and the double press are NOT device-verified. §22 byte-identical; §1.6.2–§1.6.56 untouched; §11.7 extended with the promotion rule. No tag was created. |

| 1.0.62 | 2026-10-25 | IMPORT-CRASH FIX + v1.0.54 LEFTOVERS + DUPLICATE-IP AUDIT (device-assisted; operator-authorised; spec §1.6.58) | **Closes the P0 import crash found in §1.6.57 item 9.** `ConnectionListViewModel.materializeImported()` (`:370`) and `removeImportedServer()` (`:333`) assigned `MutableLiveData.value` from inside `withContext(Dispatchers.IO)`, throwing `IllegalStateException: Cannot invoke setValue on a background thread` *after* the data work completed; fixed with `publishLiveData(target, value, Dispatchers.Main.immediate)` — a main-thread hop, **not** `postValue` (which would coalesce two rapid publishes). An audit found 11 other LiveData writes in `app/src/main`; only these two ran on an IO context, and no LiveData exists outside `:app`. **7 new tests** (`LiveDataPublishThreadTest`): a thread-asserting `ArchTaskExecutor` delegate proves the harness (setValue off-main throws exactly as the device did), `publishLiveData` is shown to land-and-finish from a background thread and to propagate a failure, and a source-shape guard bans any `MutableLiveData .value =` inside a `withContext(Dispatchers.IO|Default)` block (comments/strings stripped first; KDoc states its limit). **PART B — v1.0.54 leftovers closed:** B1 the stale "…will turn off Block Ads setting when enabled" string removed from `values`/`values-fa`/`values-vi`; B2 `ManualCsvImport` rejects inner-whitespace/control-char host/IP per row (closes the device-found `test-a. example` row) while still trimming leading/trailing; B3 the log `Server list updated atomically: N servers` corrected to `N incoming, M stored` — the exact misreading behind the false V9 finding in §1.6.57. **5 new tests** (`ManualCsvImportTest`). **PART C — duplicate-IP audit (no code change):** the real device database was pulled byte-exactly and queried — **261 rows, 261 distinct IPv4 values, 0 duplicate-IP groups, 0 rows of inflation**, 0 duplicate host names, 0 fields with padded/inner whitespace; the historical config-less twin `vpn813516587` is **gone** and `123.48.124.169` now has a single row. The double-dial vector is nevertheless real in code — `AutoModeEngine.defaultServers()` does no IP-level de-duplication and the run's only guard is `attempted.add(wedgeKey(server))` where `wedgeKey`→`serverKey`→`"ip|hostname"` — so a duplicate pair *would* be dialled twice in one run; with zero duplicate rows it cannot fire. The smallest fix (de-duplicate by IPv4 in `AutoModeEngine.defaultServers()`, keeping the row with a usable config) lies outside this block's authorisation and inside the F.29-modified files, so it was **stopped and reported**. A prior revision of this row wrongly claimed the pair had been observed and was API-versus-emergency; both statements are corrected. **PART D — DEVICE-VERIFIED on `5585fecc`:** committed-tree APK installed `-r`; extended CSV import 2/1 and API CSV import 2/0 with no crash, delete no crash, re-import idempotent, survives Refresh + restart, test servers deleted, device restored (DB 0 test rows). **PART E — committed-tree gate:** **10/10 green** from a `git archive HEAD` tree with `--rerun-tasks` — 1276 tests per run (1092 in `:app` across 89 suites, 184 in `:SoftEtherClient` across 13), 0 failures, 0 errors, 1 skip, 177–182 s each — and runs 1–3 are the three consecutive runs the brief also requires. Two earlier harness attempts were aborted by `Gradle could not start your build … java.net.BindException: Address already in use: bind` with **zero tests executed**; both are preserved and reported, and the ten counted runs needed **zero** retries. The documentation commit was then pushed fast-forward (`0fd4875..e40df8e main -> main`, no force, no tag) and the full suites were re-run from a fresh `git archive origin/main` extraction — **1092/0/0/1** (89 suites) and **184/0/0/0** (13 suites), BUILD SUCCESSFUL in 3m 17s. Full table: `DEVICE_VERIFY_v1.0.59_REPORT.md` §G/§H.**Counts:** `:app` **1092/0/0/1** (89 suites; +12 = 7 + 5), `:SoftEtherClient` **184/0/0/0** (untouched). `:app:assembleProDebug` SUCCESSFUL; `versionCode`/`versionName` unchanged. §22 byte-identical; §1.6.2–§1.6.57 untouched. No tag. Report: `DEVICE_VERIFY_v1.0.59_REPORT.md`. |

| 1.0.63 | 2026-10-25 | F.29 COMMITTED + NEXT-SERVER-AFTER-MANUAL + ONE-CANDIDATE-PER-IPv4 (device-assisted; spec §1.6.59) | **The F.29 working tree became eight reviewable commits and the two proven-independent follow-ups landed on top.** F.29: `049c7e3` data layer (three tables, `MIGRATION_8_9`, `9.json`), `303baaf` wiring, `2639a42` recording + equal-quality ties, `4b04cd7` 98 tests + the phase-2 report. Follow-ups: `9d50166` bounds the once-per-run history read at **1500 ms** (degrading to no history, logging once) with 4 tests, `acb1a39` makes Next Server resume **after** a manually-connected server by `ip|hostname` with 7 tests, `afa580b` keeps **one candidate per IPv4** with 8 tests, `51fe1d2` renames the collector's CSV to `collector_servers_full.csv` (+4/−2). **One defect in the split, reported not hidden:** `303baaf` does not compile alone (its new constructor parameters name types whose imports land in `2639a42`); `049c7e3`, `2639a42` and `4b04cd7` do. **`acb1a39` was amended once:** its first version filtered the manual server out and restarted from the top, which re-dialled every server the user had passed — four of the five new tests caught it. Room 8→9 unchanged and additive (3 tables, 4 indexes, no FK, no backfill; the four existing tables byte-identical in the schema JSON). **Downgrade warning [NORMATIVE]:** no `fallbackToDestructiveMigration()`, so an older build opening a v9 database throws instead of wiping — and no test covers that path. The tie-break is recorded with its non-effects (cannot add, remove or fail a candidate; empty history reproduces the pre-F.29 order). §22 byte-identical; §1.6.2–§1.6.58 untouched. No tag, no version bump — F.29 is committed, not released. Report: `DEVICE_VERIFY_v1.0.61_REPORT.md`. |

### Verification Status

| Section | Verification |
|---------|-------------|
| Project Baseline (§1) | VERIFIED — `build.gradle`, `gradle-wrapper.properties`, module structure |
| §1.6.29 — AUTOCONNECT-FIX-7 (v1.0.37) | VERIFIED at SOURCE CODE + TEST level — adapter `lastArmedBusyRefused` and both controller branches inspected; `:app` **812/0/0/1**, `:SoftEtherClient` **173/0/0/0**; 8 tests rewritten and 4 added. **NOT device-verified** — no device run in this phase; Fix-A remains UNVERIFIED |
| §1.6.30 — VERSION-BUMP-PAST-2.4.10 (v1.0.38) | HISTORICAL — the collision and the bump are source-verifiable (`app/build.gradle` is the only version declaration; no test pins it); APK `versionCode='144' versionName='2.4.11'` confirmed by `aapt2 dump badging`; JVM unchanged at `:app` **812/0/0/1**, `:SoftEtherClient` **173/0/0/0**. Release-readiness (RISK-A keystore) is **INFERENCE** from §1.6.10 bullet 8 — no release build was attempted and no tag exists |
| §1.6.31 — RETENTION-IMPL-PHASE-1 (v1.0.39) | VERIFIED at SOURCE CODE + TEST level — `VPNGateItem` retention fields and Room `version 8` / `MIGRATION_7_8` inspected; `mergeAll` and `retentionHealthScore` inspected; the `8.json` schema matches the DDL; 11 new JVM tests (5 migration + 6 retention) all green; `:app` **823/0/0/1**, `:SoftEtherClient` **173/0/0/0**; `:app:assembleProDebug` SUCCESSFUL under `CI=true`, APK SHA-256 `79c10272…3c17`. The 7→8 upgrade is JVM-verified only — a real-device replay is **UNKNOWN**; phases 2–3 (§9.3 prune, max-server cap) are **NOT IMPLEMENTED**. Item G (§12.5.2) is **PARTIAL** |
| §1.6.32 — AUTOCONNECT-DEVICE-BUGS-FIX (v1.0.40) | VERIFIED at SOURCE CODE + TEST level — `combineAutoModeState` and `AutoModeDisplayState` inspected; the screen's render + tap branch and the three battery-dialog exits inspected; 14 new JVM tests (8 display-state + 6 persisted-flag) all green; `:app` **837/0/0/1**, `:SoftEtherClient` **173/0/0/0**; `:app:assembleProDebug` SUCCESSFUL under `CI=true`, APK SHA-256 `5bed6128…c91a`, version unchanged at `144 / 2.4.11-dev`. **NOT device-verified** — no device run in this phase, so BUG-1's display fix and BUG-3's one-shot prompt are SOURCE CODE + TEST only. **BUG-2 (MS-SSTP never attempted) is DEFERRED** — the user's dataset is **UNKNOWN** and no fix is claimed |
| §1.6.34 — TRAFFIC-PATH-DYNAMIC phase 1 (v1.0.41) | VERIFIED at SOURCE CODE + TEST level — `isTunnelUsableForRouting` and its pure core `routingUsableFromSignals` inspected; `productionRouting` and `resolvePrimary` inspected; the two stale comments corrected comment-only (no code change in `SoftEtherVpnService.kt`); 9 new JVM tests (6 + 3) all green with `VpnServerRepositoryR4bTest` (10), `VpnServerRepositoryRoutingTest` (8), `GlobalVpnTrackerTest` (17) and `RxAdvanceWiringTest` (7) unchanged and passing; `:app` **846/0/0/1**, `:SoftEtherClient` **173/0/0/0**; `:app:assembleProDebug` SUCCESSFUL under `CI=true`, APK SHA-256 `1340fed9…159f`, version unchanged at `144 / 2.4.11-dev`. **NOT device-verified** — no device run in this phase, so "opt-in + dead tunnel → OPEN from the first attempt" is SOURCE CODE + TEST only. §12.5.1 row 10 remains FROZEN and stale; it is annotated only in §1.6.34 |
| §1.6.35 — AUTOCONNECT-CORRECTNESS-CLUSTER (v1.0.42) | VERIFIED at SOURCE CODE + TEST level — `attemptTimeoutMsProvider`, `nativeSlotAwaitMsProvider` and `isNetworkAvailable` inspected in the constructor, and both `coerceAtLeast(1L)` helpers plus the single `serverKey(ip, hostname)` formatter inspected; the two network guards sit after the protocol check and inside the loop, before `wedgeKey`/`attempted`; `ERROR_NO_NETWORK` is published from exactly those two sites and mapped in `AutoModeScreen` to `auto_mode_error_no_network`; `GlobalVpnTracker.isAnyNetworkAvailable()` inspected (fails open on a missing context); `resumeFromServerKey` replaces `resumeFromIndex` at all five read/write sites and the skip filter uses `serverKey`; 12 new JVM tests (4 + 4 + 1 + 3) all green, with the four OP-R6/skip tests from v1.0.31/v1.0.37 and both `…IsCappedToTheAttemptBudget` tests passing unchanged; `:app` **858/0/0/1**, `:SoftEtherClient` **173/0/0/0**; `:app:assembleProDebug` SUCCESSFUL under `CI=true`, APK SHA-256 `e8a4bb14…7ecd`, version unchanged at `144 / 2.4.11-dev`. **NOT device-verified** — no device run in this phase, so "Settings change applies to the next attempt", "offline stops at once" and "re-sort does not shift the continuation" are SOURCE CODE + TEST only. §12.5.1 remains FROZEN; §22 byte-identical |
| §1.6.36 — ROUTING-REACHABILITY-AND-EPERM-DIAGNOSTICS (v1.0.43) | VERIFIED at SOURCE CODE + TEST level — `DataUtil.getPrefersVpnForCollector()` / `setPrefersVpnForCollector()` inspected and confirmed to reuse the exact key literal `"vpn_always_prefer_vpn"` (independent of the frozen `GlobalVpnTracker.PREF_ALWAYS_PREFER_VPN`); the `SettingsScreen` Auto Mode `SettingSwitchRow` inspected (binds `dataUtil.get/setPrefersVpnForCollector()`, defaults off); `HttpFetcher.boundToNetwork` inspected — wrapped in `try/catch (Throwable)` logging `[bind] ok network=…` / `[bind] FAILED network=… errno=…` and re-throwing (control flow unchanged); 7 new JVM tests (4 + 3) all green with `DataUtilPrefersVpnTest` and `HttpFetcherBindDiagnosticTest` added and the rest of the suite unchanged; `:app` **865/0/0/1**, `:SoftEtherClient` **173/0/0/0**; `:app:assembleProDebug` SUCCESSFUL under `CI=true`, APK SHA-256 `ed82fc058796a45698cd41a90d4dcb1fb20ecbc05198b30d57eaecf2117bce19`, version unchanged at `144 / 2.4.11`. **NOT device-verified** — no device run in this phase, so "the opt-in is now user-reachable" and "the `[bind] FAILED` line attributes an EPERM" are SOURCE CODE + TEST only. §12.5.1 remains FROZEN; §22 byte-identical |
| §1.6.37 — DIAGNOSTIC-PLACEMENT-FIX (v1.0.44) | VERIFIED at SOURCE CODE + TEST level — `HttpFetcher.kt` `OkHttpFetcher.execute()` catch inspected and enriched with the exception class (the network handle already carried in `e.message`); control flow (`return null`) and the v1.0.43 `[bind]` logs confirmed unchanged; `VpnServerRepository.kt:163` confirmed to call `boundToSocketFactory` directly (so `boundToNetwork` is unused in production); 1 new source-shape test (T-NEW-53) extends `HttpFetcherBindDiagnosticTest` and passes; `:app` **866/0/0/1**, `:SoftEtherClient` **173/0/0/0**. The `:app:assembleProDebug` APK was NOT produced in this environment (a sandbox Security Center rule blocked a Gradle cache `.so` read at `mergeProDebugNativeLibs`); source compilation and the JVM suite were validated via `:app:testProDebugUnitTest`. **NOT device-verified** — no device run in this phase. §12.5.1 and §22 FROZEN; §1.6.2–§1.6.36 untouched |
| §1.6.38 — DYNAMIC-PATH-RECOVERY Phase A (v1.0.45) | VERIFIED at SOURCE CODE + TEST level — the two-store write (`DataUtil.setPrefersVpnForCollector` → both `vpn_setting_data_<flavor>` and `PreferenceManager.getDefaultSharedPreferences`), the three-value `CollectorPath` with `boundToPhysicalNetwork`, `GlobalVpnTracker.currentPhysicalNetwork()` / `SelfExclusion` / `isSelfExcludedFromOwnTunnel()`, `VpnServerRepository.routeLadder()` + `resolvePrimary()` + the single-flight/deadline `refresh()`, and the `[bind-physical]` / `[ladder]` diagnostics were all inspected; 21 new JVM tests plus 5 updated test files pass (`:app` 887/0/0/1, `:SoftEtherClient` 173/0/0/0) and `:app:assembleProDebug` SUCCESSFUL. **P3 (the device `EPERM` mechanism) is UNKNOWN**, `SelfExclusion` is `UNKNOWN` in this build, `EMERGENCY` has no configured source, and **no device ran** — Phase B PENDING. Evidence: SOURCE CODE + TEST; device UNKNOWN. |
| §1.6.39 — EMERGENCY-DEFAULT-READER + TIMEOUT-RANGE (v1.0.46) | HISTORICAL — attributed to this revision and its phase report (`EMERGENCY_DEFAULT_READER_REPORT.md`). CHANGE A: the absence of any reader for `R.string.default_emergency_source_url` before this phase, the new `App.seedEmergencyDefaultIfNeeded` (once-only via `SETTING_EMERGENCY_DEFAULT_SEEDED`, non-destructive via `observeAll().first()`, non-fatal) and its `onCreate` invocation are **SOURCE CODE**; the once/idempotent/non-destructive behaviour is **TEST** (`EmergencyDefaultSeedTest`, 8 cases). CHANGE B: the 5 → 8 s floor in both `DataUtil` clamps and the `(8..60)` picker is **SOURCE CODE**, pinned by **TEST** (`DataUtilTimeoutRangeTest`, 5 cases). Counts `:app` 900/0/0/1 and `:SoftEtherClient` 173/0/0/0, APK `88b4a4fe…b6591`, and the packaged `default_emergency_source_url` (`aapt2 dump resources`) are **TEST**. **Device verification PENDING** — a fresh-install seed check has not run. The `EMERGENCY` ladder rung stays inert (`hasEmergencySources()` stub); that is **SOURCE CODE**, not a defect of this phase. |
| §1.6.40 — REMOVE-SELF-EXCLUSION (v1.0.47) | HISTORICAL — attributed to this revision and its phase report (`REMOVE_SELF_EXCLUSION_REPORT.md`). The hidden `Self` row in `excluded_app` (`App.kt:135–149`), the two application points (`SoftEtherVpnService.kt:1398`, `IPTerminal.kt:148`), and the `filterNot { it == selfPackage }` guard at both are **SOURCE CODE**, pinned by **TEST** (`SelfExclusionRemovedTest`, 4 cases, including that user exclusions still reach the builder). The `EPERM` on `network=109` with `PHYSICAL` succeeding on `network=104` is **PHYSICAL DEVICE** (`DEVICE_VERIFY_v1.0.46_REPORT.md` §7.4). Counts `:app` 904/0/0/1 and `:SoftEtherClient` 173/0/0/0 and APK `0ef89bfe…0583` are **TEST**. **Device verification PENDING** — whether the `TUNNEL` rung now returns data with no `EPERM` has not been observed on either protocol. |
| §1.6.41 — NATIVE-SLOT-TEARDOWN-RECOVERY (v1.0.48) | HISTORICAL — attributed to this revision and its phase report (`NATIVE_SLOT_TEARDOWN_RECOVERY_REPORT.md`). The deadlock reproduction is **PHYSICAL DEVICE** (`DEVICE_VERIFY_TUNNEL_TRANSPORT_v1.0.47_REPORT.md` §8.4, `Start refused: … phase=TEARDOWN, reporting deadline expired (still blocked)`). The root cause — `markTeardownRequested` re-stamping `teardownRequestedAtMs`, combined with the three idempotent `destroyResources()` call sites and `adapter.disconnect()` on every `NATIVE_BUSY` — is **SOURCE CODE** (`NativeLifetimeRegistry.kt:287` pre-fix, `ConnectionController.kt:919`, `SoftEtherVpnService.kt:509`/`:929`, `AutoModeController.kt:1941`). FIX-1 (`NativeLifetimeRegistry.kt:306`), FIX-2 (`SoftEtherVpnService.kt:632`, `:733`, `:746`, `:761`; `NativeLifetimeRegistry.kt:407`, `:433`) and the unchanged bound (`:536`) are **SOURCE CODE**. The fail-closed preservation and the recovery are **TEST** (`NativeSlotRecoveryTest`, 4 cases, T-NEW-74..77). Counts `:SoftEtherClient` 177/0/0/0, `:app` 904/0/0/1 and APK `8f7ed99a…494a3` are **TEST**. **Device verification PENDING** — whether the recovery actually fires on the reproduced deadlock, and whether the new `heldFor=<N>ms` field makes the window attributable, are **UNKNOWN**. |
| §1.6.42 — EMERGENCY-LADDER-WIRING (v1.0.49) | HISTORICAL — attributed to this revision and its phase report (`EMERGENCY_LADDER_WIRING_REPORT.md`). The two inertness reasons — the `hasEmergencySources()` stub (`VpnServerRepository.kt:448` pre-fix) and the snapshot intercepting the `ServerListUpdater.emergencyFetch` fallthrough — are **SOURCE CODE**. FIX-1 (`VpnServerRepository.kt:444`), FIX-2 (`VpnServerRepository.kt:368`; `ServerListUpdates.kt:146` `emergencyRecords`) and the unchanged v1.0.45 ladder are **SOURCE CODE**. The reuse-without-duplication (one `EmergencySourceFetcher()` instantiation) and the `DatasetIntegrity` gate are **SOURCE CODE + TEST** (`EmergencyLadderWiringTest`, 3 cases, T-NEW-78..80). Counts `:app` 907/0/0/1, `:SoftEtherClient` 177/0/0/0 and APK `194255e4…b657` are **TEST**. **Device verification PENDING** — whether the ladder actually falls through to EMERGENCY and succeeds against the seeded `servers.csv` when PHYSICAL and TUNNEL both fail, and whether `DatasetIntegrity` still rejects a regressing emergency dataset, are **UNKNOWN**. |
| §1.6.43 — ADDITIONAL-CONNECT-PROTECT (v1.0.51) | HISTORICAL — attributed to this revision and its phase report (`ADDITIONAL_CONNECT_PROTECT_REPORT.md`). The defect (the additional TCP socket dialled at `softether_protocol.c:4036` with no protect between `socket_create` at `:4029` and the connect, while Kotlin's sweep only runs from the receive loop at `ConnectionController.kt:1175`) is **SOURCE CODE**, corroborated by the **PRIOR REPORT** device log of 2026-10-21 18:16. The hook (`protect_socket_fn`/`protect_socket_ctx`, `softether_set_socket_protector`), its call site between create and connect, the JNI upcall (`nativeSetSocketProtector`, `GetEnv`/`AttachCurrentThread`/`DetachCurrentThread`, `ExceptionCheck`/`Clear`, `NewGlobalRef`/`NewLocalRef`/`DeleteGlobalRef`), the release-after-join ordering in `nativeDestroy`, and the two Kotlin arming sites are **SOURCE CODE**. The fail-closed abort, the ordering, the teardown release ordering, and the preservation of the safety net are **TEST** (`AdditionalConnectProtectTest`, 7 cases, T-PROTECT-01..07). Counts `:SoftEtherClient` 184/0/0/0, `:app` 907/0/0/1 and APK `36b4e4bb…a521` are **TEST**, as is the presence of both symbols in the built `libsoftether.so`. **Device verification PENDING** — whether a SoftEther TCP tunnel now survives > 20 s with `additional_connect` active, whether the `received` counter climbs, and whether the on-device log shows the socket protected before connect are **UNKNOWN** (V-VERIFY-3). |
| §1.6.44 — AUTO-ROUTE-AND-AUTOCONNECT-UX (v1.0.52) | VERIFIED at SOURCE CODE + TEST level, **NOT device-verified**. **SOURCE CODE:** the removal set (Settings row, `DataUtil` accessors + the write-only `defaultPreferences` field and its two-argument constructor, `GlobalVpnTracker.prefersVpnForCollector()`, `PREF_ALWAYS_PREFER_VPN`, `CollectorRoutingInput.preferVpn`, the `CollectorRouting` object, the two `setting_prefer_vpn_collector_*` strings) inspected as absent; `PathMemory` (bounded `HashMap<CollectorPath, Long>` pairs, injectable clock, TTL/cooldown, `synchronized` on every method), `routeLadder(input, memory)` and the `fetchOnChosenPath` `recordSuccess`/`recordFailure` wiring inspected; the one-shot retry inspected as CAS-guarded, `refreshMutex`-scoped, `withTimeoutOrNull(TUNNEL_RETRY_TIMEOUT_MS)`-bounded and re-armed only by a run that starts with a usable tunnel; the `transitionLock` critical sections, the `startLocked()` seam, the two `CancellationException` rethrows, the `withContext(NonCancellable) { adapter.disconnect() }` sites and the `serverKey` comparison in `rememberRemaining()` inspected; the moved switch inspected on `AutoModeScreen` and absent from `SettingsScreen`; the 8 s default inspected in `DataUtil`, `AutoModeController.DEFAULT_ATTEMPT_TIMEOUT_MS` and the picker fallback. **TEST:** 18 net new JVM cases across `VpnServerRepositoryLadderTest` (rewritten: base order, evidence-only promotion, invariants, 8 `PathMemory` cases), `VpnServerRepositoryRoutingDynamicTest` (T-LADDER-20..24), `HttpFetcherR4bTest` (routing half replaced by removal assertions), `DataUtilRoutingPreferenceRemovedTest` (T-REMOVED-01..05, replacing the deleted `DataUtilPrefersVpnTest`), `DataUtilTimeoutRangeTest` (T-NEW-70/70b/72) and two `AutoModeControllerTest` cases (null-vs-`""` identity cursor; double press never dials a server twice). Counts `:app` **925 / 0 / 0 / 1** (baseline 907), `:SoftEtherClient` **184 / 0 / 0 / 0** (untouched), and the `:app:assembleProDebug` gate, are **TEST**. **PENDING (UNKNOWN, V-VERIFY-4):** whether the collector now recovers on its own when the physical path is blocked (run 1 fails on PHYSICAL, run 2 logs `[ladder] order=TUNNEL,PHYSICAL,EMERGENCY` with no user action); whether the moved switch is reachable on the Auto Connect screen, absent from Settings and persistent across a restart; and whether a dead server is abandoned at ~8 s rather than ~12 s. |
| §1.6.45 — SERVER-DATA-AUDIT-AND-OPENVPN-CONFIG-GATE (v1.0.53) | VERIFIED at SOURCE CODE + TEST level, **NOT device-verified**. **SOURCE CODE:** the gate (`openVpnConfigAvailable` required by **both** OpenVPN branches of `usableProtocols()`), the fail-closed default on `AutoModeCandidate`, its derivation from the real bytes in `AutoModeEngine.toCandidate()`, and the untouched port fields, inspected; `AttemptOutcome.MISSING_CONFIG` returned **before** `awaitArmedTunnel` in `awaitArmedOutcome()`, `lastMissingConfig` cleared per attempt in `armTunnelWatch()`, and the controller branch that `continue`s to the next protocol on the same server (no wedge, no wait, no terminal) inspected; the `[AUTO] ATTEMPT_CLASS` emission and the `classifyAttempt()` mapping inspected, with the distinction it cannot make stated in code rather than guessed; `EmergencyCsvParser.applyOpenVpnColumnPort()` (three cases, `OPENVPN_PORT_CONFLICT_CONFIG_WINS`, `OPENVPN_PORT_WITHOUT_CONFIG`, and `supported` no longer claimed without bytes) and `VpnServerMerger.enforceConfigAuthoritativePorts()` (run before port validation, recording a §38 conflict) inspected; `emergency_csv_publisher.py`'s `split_sstp_endpoint()` / `normalize_sstp_rows()` / `enforce_config_ports()` inspected as wired between the `sanitize_row` filter and `dedupe_hostnames`. `SE_UDP_Port`, `Has_IPv6` and `SSTP_Supported` inspected as **deliberately unchanged** (§1.6.45 items 12–14). **TEST:** 16 net new JVM cases — 4 in `AutoModeControllerTest` (config absent, HTML-link-only, port facts preserved, `MISSING_CONFIG` rotation to the next protocol), 4 in the new `AutoConnectAttemptClassificationTest` (including a source-shape guard that the `lastMissingConfig` check precedes `awaitArmedTunnel`), 6 in `EmergencyCsvParserTest` (port conflict, port without config, three SSTP `host:port` cases, merged API+emergency rows) and 2 in `MergerOracleTest` — plus 16 net new Python cases (9 `ConfigAuthoritativePorts`, 7 `SstpHostPortSplit`). Counts `:app` **941 / 0 / 0 / 1** (baseline 925), `:SoftEtherClient` **184 / 0 / 0 / 0** (untouched), Python `test_emergency_csv_publisher.py` **40 / 0** (baseline 24), and the `:app:assembleProDebug` gate, are **TEST**. One instability is disclosed rather than hidden: `pressWhileConnectedDisconnects` and `twoImmediateSkipsNeverDialTheSameServerTwice` each failed once across four full-suite runs (the latter logging one server dialled five times), both pass in isolation and on the recorded green run, and the duplicate dial is recorded as **R-1** for a follow-up block because it lies in the v1.0.52 skip/continuation path this revision did not modify. **PENDING (UNKNOWN, V-1..V-5):** that a config-less OpenVPN server is never selected and no 8 s stall precedes the protocol rotation; that the gate does not shrink the usable set when configs are present; that `123.48.124.169` dials 1558 rather than 1534; that an SSTP row whose hostname carries `:port` reaches the intended endpoint instead of failing to resolve; and that `ATTEMPT_CLASS MISSING_CONFIG` never appears in a device log — its appearance would itself be a defect report. |
| §1.6.46–§1.6.50 — v1.0.54 (SKIP-RACE, SETTINGS-CLEANUP, LIST-PIN, MANUAL-IMPORT, EXCLUDED-APPS-SEED) | VERIFIED at SOURCE CODE + TEST level only. `:app` **1044/0/0/1**, `:SoftEtherClient` **184/0/0/0**; 108 new tests across 7 new classes, 3 new Auto Mode tests, 2 obsolete Settings tests deleted. PART 1's coalescing is proven with a gated teardown rather than a raced one, and the class was re-run three times with `--rerun-tasks`. **NOT device-verified:** D2 (pinned row, import via paste and file, the four removed Settings controls, default protocol priority, the single cache button, and a Next-Server double press) and D3 (V-VERIFY-3/4) are PENDING — no device ran. **D1 CLOSED in v1.0.58** — the 12 confirmed Iranian package names were extracted from device `5585fecc` on 2026-10-24 and shipped (§1.6.54). D2 and D3 remain PENDING. |
| §1.6.54 — v1.0.58 (D1 EXTRACTION AND SHIPMENT) | **IMPLEMENTED + TESTED.** PHYSICAL DEVICE: the 15-row `excluded_apps` table of `vpn_gate_connector` on device `5585fecc` was read read-only via `run-as`; all 15 confirmed installed by `pm path`. OPERATOR STATEMENT: `CONFIRM D1: ALL CLASSIFIED-IRANIAN`. SOURCE CODE + TEST: `assets/default_excluded_apps` now carries the 12 confirmed Iranian package names (sorted, de-duplicated, commented, with a provenance header) and `DefaultExcludedAppsTest` grew 12 → 16 with five real-content guards. `:app` **1166/0/0/1** (92 suites), `:SoftEtherClient` **184/0/0/0** (13 suites), one `--rerun-tasks --no-build-cache` run. Three rows were deliberately **not** shipped (DeepSeek — Chinese/personal; Android Auto — Google/user-added; the app itself — self-excluded in `App.kt`) and that decision is pinned by a test. No code, schema, migration, Gradle or manifest change. |
| §1.6.55 — v1.0.58 (LAUNCH CRASH, pre-existing) | **KNOWN LIMITATION / FOLLOW-UP REQUIRED.** PHYSICAL DEVICE: installing the committed-tree APK (SHA-256 `00a3b10b…`, from `git archive HEAD` = `0c3c56e`) on `5585fecc` and launching it shows `MainActivity` (+945 ms) then a fatal `NullPointerException` on `MutableStateFlow.setValue` at `ConnectionListViewModel.kt:328`. SOURCE CODE: `_importedServers` is declared at line 268 but `init` calls `refreshImportedServers()` at line 50, and `Dispatchers.Main.immediate` runs the coroutine body synchronously up to the first suspension point, so the still-null field is hit before the constructor ends. Introduced by `ec3a73b` (v1.0.54); `git diff` for the file is empty, so the F.29 work is **not** implicated. **NOT FIXED** — out of scope. Consequence: D2, D3 and V3–V10 are all `NOT OBSERVABLE` until it is fixed. Evidence: `test-evidence/dv56/CRASH_FINDING.md`, `test-evidence/dv56/crash_launch_logcat.txt` (untracked). |
| §1.6.56 — v1.0.59 (LAUNCH-CRASH FIX) | **FIXED + TESTED + DEVICE-VERIFIED.** PHYSICAL DEVICE: the committed-tree APK (SHA-256 `9c0328de…`) installed with `adb install -r` on `5585fecc` launches and stays alive (pid constant 30 s, zero FATAL/ANR/tombstone, no Room error), the server list loads 261 rows, Developer Mode is ON, Settings opens. V3 PASS, V5 PASS, V6 PASS, V9 EMERGENCY reached, V10 PASS (partial), V4(a) captured; V7, V4(b), V8 and the V5 Clear-cache action are OPERATOR MANUAL STEP. Committed-tree runs: `:app` **1068/0/0/1** (87 suites), `:SoftEtherClient` **184/0/0/0** (13 suites), three `--rerun-tasks --no-build-cache` runs. SOURCE CODE: `init` moved below every property declaration in `ConnectionListViewModel.kt` (+21/−7); a scan of every `.kt` under `app/src/main/java` finds **zero** classes with a property declared after an `init` block. TEST: new `ConnectionListViewModelInitOrderTest` (4) and the regression **proven** against the `ec3a73b` ordering in a scratch copy (2 of 4 failed, then passed again once the fix was restored). Three `--rerun-tasks --no-build-cache` runs: `:app` **1170/0/0/1** (93 suites), `:SoftEtherClient` **184/0/0/0** (13 suites). Lint 104/178/9, unchanged, zero findings in the fixed file. APK SHA-256 `7a4fc01f…`, `versionCode 144` / `versionName 2.4.11-dev`. |
| §1.6.51 — v1.0.55 (VERIFY-AND-CLOSE) | VERIFIED at SOURCE CODE + TEST level. Ten pre-fix full-suite runs: 7 pass / 3 fail. Post-fix: three consecutive `--rerun-tasks --no-build-cache` runs, all green; `:app` **1064/0/0/1**, `:SoftEtherClient` **184/0/0/0**. New tests: `ClearTransientCacheTest` (11), `ImportedServerEmissionTest` (9). Two flaky tests repaired, one relaunch window closed, 20 Vietnamese keys added. **NOT device-verified:** D1, D2 and D3 unchanged — no device ran. Lint fails at 104 errors (was 124) and did before this block. |
| §1.6.52 / §11.15 — v1.0.56 (F.29 PHASE-1 DESIGN GATE) | **DESIGN ONLY — nothing implemented, nothing tested, no device.** Evidence for every finding is SOURCE CODE at HEAD `1cace61`: the three identities (`VPNGateItem` `@PrimaryKey`, `VpnServerMerger.kt:258-267`, `AutoModeController.serverKey:2520`); the destructive write (`VPNGateItemDao.replaceAll`, `ServerListUpdates.kt:301-305`, `:486-527`); **G-F29-1**, the fallback-path retention reset (`VPNGateItem` retention defaults + `toVPNGateItem()` with no such fields + `replaceAll` without a previous-row read — consequence labelled INFERENCE); the single success funnel (`:2408-2432`) and the single abandonment point (`:2434`); the five-value `AttemptOutcome` and `classifyAttempt()`; `VpnPermissionMissingException` at `:1940`; the four existing quality mechanisms (`supports():2635`, `DatasetIntegrity:180-195`, `byQuality:2626`, `WedgeTracker.EXCLUSION_THRESHOLD:2763`) and the absence of any post-connect quality rejection; `healthScore` with no production reader; `AUTO_LAST_SUCCESS_HOST`/`_PROTOCOL` and `AutoModeRun.lastSuccess` write-only; the three network-detection components and the absence of any transport classifier; `ACCESS_NETWORK_STATE` in the manifest. Proposed design is labelled PROPOSED DESIGN throughout; nine open decisions (OD-1…OD-9) are OPEN and block implementation. **NOT TESTABLE** without a device: summary/event durability across a real process restart (11.15.11 items 23–24) and real `ConnectivityManager` transport classification (11.15.7). No build, no test run, no Gradle invocation, no commit, no ADB. |
| §1.6.53 / §11.15.17 — v1.0.57 (F.29 PHASE-2 IMPLEMENTATION) | **IMPLEMENTED — automated verification passed; device verification BLOCKED.** **SOURCE CODE:** the three entities with no foreign keys (`ServerHistoryEntities.kt`), the pure counter arithmetic (`HistoryCounters.kt` `nextSummary`/`nextProtocol`), the vocabulary (`ServerHistoryTypes.kt`), the single-writer `@Transaction record()` with blank-identity drop + `CONNECTED`-token idempotency (`ServerHistoryDao.kt`), the `Dispatchers.IO` sink with `CancellationException` rethrow (`RoomServerHistorySink.kt`), the pure `classify` + never-throwing `current` (`NetworkTypeClassifier.kt`), the tier model and `historyComparator` (`AutoModeHistory.kt`), `version 9` + `MIGRATION_8_9` + `serverHistoryDao()` (`AppDatabase.kt`), the wiring (`App.kt`, `AutoModeEngine.kt`) and the ten recording hooks + the tie-break inside the equal-quality window (`AutoModeController.kt`) were all inspected. **TEST:** 98 new cases in 6 new files — `Migration8to9Test` 9 (bounded version, exact object set, no `DEFAULT`/`DROP`/`DELETE`/`TRUNCATE`/`FOREIGN KEY`, single `AUTOINCREMENT`, whitespace-insensitive DDL parity against all 7 exported objects in `9.json`), `HistoryCountersTest` 21, `ServerHistoryRecorderTest` 16 (a fake DAO implementing the interface so the real `@Transaction` body runs), `NetworkTypeClassifierTest` 15, `AutoModeHistoryOrderingTest` 20 (including a TimSort transitivity check on 8 candidates), `AutoModeHistoryRecordingTest` 17 (drives the real controller). Counts `:app` **1162/0/0/1** (baseline 1064) and `:SoftEtherClient` **184/0/0/0** (untouched) and the `:app:assembleProDebug` APK (SHA-256 `2d7acfec…`, `versionCode 144` / `versionName 2.4.11-dev` unchanged) are **TEST**; a `--rerun-tasks` rerun on the delivered tree reproduced both suites green; `:app:lintProDebug` reports **104 errors / 178 warnings / 9 hints — unchanged from the pre-F.29 baseline, with zero findings in any F.29 file**. Two real defects were found and fixed by writing the tests (`TIMED_OUT` outcome, `Throwable` catch). Two deviations from the Phase 1 design are recorded as `KNOWN LIMITATION` in §11.15.17.3–§11.15.17.4. **NOT device-verified — DEVICE UNAVAILABLE:** summary/event durability across a real process restart, real `ConnectivityManager` classification, a real 8 → 9 upgrade on a device holding an existing database, and the end-to-end effect of history on a real Auto Mode run are all **UNKNOWN**. **G-F29-1 is NOT FIXED** and is restated in §11.15.17.8. No commit, no tag, no push, no release version claimed |
| §1.6.57 — v1.0.58 (PUSH-GATE FIX + EMERGENCY-SNAPSHOT-POISONING) | **PUSHED + TESTED; PART C NOT SHIPPED; PART D PENDING.** TEST: 3 green committed-tree full runs at the push HEAD (`:app` 1072/0/0/1, 87 suites; `:SoftEtherClient` 184/0/0/0, 13 suites), then `0c3c56e..ef9bf99` fast-forward. The PART B change brings `:app` to **1080/0/0/1** (+8, `EmergencyMergeRetentionTest`). PHYSICAL DEVICE: the V9 database was re-pulled and is intact (261 rows, 0 hostnames lost), and the damaged artefact — `files/collector_vpn_servers.json` at `count: 170` with `savedAt` equal to the table's last write — was measured byte-exactly; that is the evidence the fix is aimed at. **PART C (`skipFromExternal`) is implemented in the working tree and deliberately not committed** (shared file with the uncommitted F.29 work), so V7 remains open and nothing in PART C is shipped. **PART D ran and found a P0 crash**: import and import-delete throw `setValue on a background thread` (`ConnectionListViewModel.kt:370`/`:333`), pre-existing v1.0.54 code, not fixed here. Removed settings, the single Clear-cache button, the seeded emergency source, the protocol default and a 2-of-3 CSV paste all passed. **NOT device-verified:** V7, the pinned row, the file-picker import, the fresh-install protocol default, the double press, import idempotence. §22 byte-identical. No tag was created |
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
| Dynamic Server-Source Access (§11.8) | PARTIAL — requirement stated; since v1.0.45 (§1.6.38) the network-path half is implemented as an explicit `PHYSICAL`/`TUNNEL`/`EMERGENCY` ladder (no unbound "open"), while per-source path memory remains phase 2 |
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
