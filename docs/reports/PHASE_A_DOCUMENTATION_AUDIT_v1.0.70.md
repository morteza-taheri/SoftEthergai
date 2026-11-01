# PHASE A REPORT — Documentation Audit and Canonical Update (v1.0.70)

**Date:** 2026-10-27 06:xx local · **Branch:** `main` · **HEAD:** `08c6abe48be766be76830d249af41e625b6aa3ff`
**Mode:** documentation only — no application source, test, build, dependency, schema, or configuration change.

---

## 1. Current repository baseline

| Item | Value |
|---|---|
| Branch | `main` (tracks `origin/main`) |
| HEAD | `08c6abe48be766be76830d249af41e625b6aa3ff` — matches `origin/main` |
| Version | `versionCode 144`, `versionName "2.4.11"` (+ `-dev` for debug) |
| Pre-existing modifications | `HttpFetcher.kt` (+48/−1, v1.0.66 B-1 fix), `VpnServerRepository.kt` (−1, dead `MIRROR_TIMEOUT_MS`), `ServerListUpdaterTest.kt` (+28, identical-dataset test), `AutoModeHistoryRecordingTest.kt` (+71, quality-reject/OD-9 tests) |
| Device | `adb devices` empty → **DEVICE_UNAVAILABLE** |
| Canonical docs before | `PROJECT_SPECIFICATION.md` v1.0.63, 2026-10-25, HEAD `51fe1d2`; `SERVER_SOURCE_ARCHITECTURE.md` (design) |

## 2. Documents inspected

`PROJECT_SPECIFICATION.md` (6687 lines; header, §1.6.38/39/44/45/50, §6.3, §11.11, §11.13–§11.15, §12.5, §13, changelog), `SERVER_SOURCE_ARCHITECTURE.md` (§7, §9), `README.md` (overview only), `AGENTS.md` (commit convention), and the untracked v1.0.64–v1.0.69 reports (evidence only, not authority). `docs/reports/` was treated as historical.

## 3. Findings by requirement ID (pre-implementation)

| ID | Finding | Document status before | Implementation (audited) |
|---|---|---|---|
| REQ-PROTO-001 | Default is `[SOFTETHER_TCP, SOFTETHER_UDP]`; approved order is UDP→TCP→MS-SSTP | **Contradictory** (line 3168) | GAP |
| REQ-TIMEOUT-001 | 8 s default, 8–60 clamp; approved 12/8/30 | **Contradictory** (line 4196) + internal history conflict (line 449 says 12 s) | GAP |
| REQ-RET-001 | No 500 cap anywhere; arch proposed 1000; phase 1 add-only | **Missing** | MISSING |
| REQ-SE-001 | 4 default / 8 cap implemented but not stated as a requirement | **Missing** (as requirement) | IMPLEMENTED |
| REQ-DEV-001 | Developer Mode default false in source; no requirement text | **Missing** (as requirement) | IMPLEMENTED |
| REQ-DNS-001 | `USE_CUSTOM_DNS` key exists; default not stated | **Missing** (as requirement) | IMPLEMENTED (verified in Phase B) |
| REQ-ROUTE-001 | Spec documents a TUNNEL→PHYSICAL fallback ladder; approved policy is VPN-only while active | **Contradictory + stale** | GAP |
| REQ-STATE-001 | Two state models reconciled, no single owner declared | **Partial** | PARTIAL |
| REQ-ORIGIN-001 | Manual origin synthesised; no persisted three-value origin | **Missing** | MISSING |
| REQ-EXCL-001 | Four normative rules + installed-only seed documented | **Accurate** | IMPLEMENTED |
| REQ-EVID-001 | SOURCE/DEVICE/INFERENCE labels used, no closed result-class set | **Partial** | PRACTISED |
| REQ-TEST-001 | No repository-wide requirement→test matrix | **Missing** | PARTIAL |

## 4. Documents changed

1. `PROJECT_SPECIFICATION.md` — header advanced to v1.0.70 / 2026-10-27 / HEAD `08c6abe`; new normative **§12.8 "Approved baseline"** with 11 requirement IDs, statuses, verification methods and four deferred decisions; historical contradictions annotated in place (protocol order, timeout); a v1.0.64–v1.0.70 revision record appended.
2. `SERVER_SOURCE_ARCHITECTURE.md` — §9 currency note rewritten (phase 1 implemented; cap now **500**, not 1000); §9.1 cap bullet updated with the approved 500 and manual-record protection; §9.3 annotated with the real field mapping (`healthScore`, protocol count, `lastSuccessAt`/`successCount`, `lastSeenAt`); §7 Q3 annotated as **superseded** by REQ-ROUTE-001.

## 5. Contradictions resolved

- Timeout: 8/8–60 → **12/8–30** (history retained, superseded marker added).
- Protocol order: TCP→UDP → **UDP→TCP→SSTP**.
- Routing: open-first/fallback → **VPN-only while active**.
- Retention default: 1000 → **500**.
- Architecture §9 "NOT IMPLEMENTED" → phase 1 implemented, phase 2/3 now approved scope.

## 6. Known gaps retained (not hidden)

500-cap tie-breaking and manual-record counting; connection-origin storage/reconstruction; single authoritative state owner; make-before-break support (service refuses a second tunnel); real-SQLite migration coverage; TUN EIO correlation schema; crash taxonomy; battery/background procedure.

## 7. Decisions required from the user

1. Tie-break at the 500 boundary and whether manual records count toward the cap.
2. Connection-origin storage (Room migration vs preference) and reconstruction.
3. Single source of truth for tunnel state.
4. Whether make-before-break is ever in scope.

## 8. Validation performed

- Header/version/date verified against live `git rev-parse HEAD`.
- New section and all annotations located by `grep` after writing.
- Diff reviewed: only the two documents changed; the four pre-existing source/test modifications are byte-identical to the session start (same `git diff --stat` for them).
- No contradictory value was left un-annotated: `grep` for "8 seconds", "8–60", "1000", "SOFTETHER_TCP, SOFTETHER_UDP" now returns the historical text **with** a supersession note.

## 9. Exact changed-file list (Phase A)

- `PROJECT_SPECIFICATION.md` (+55/−2)
- `SERVER_SOURCE_ARCHITECTURE.md` (+7/−1)

## 10. Diff summary

Specification: header 3 lines; 2 supersession blockquotes; new §12.8 (~45 lines); 8-row revision table. Architecture: 3 annotations + 1 rewritten currency note.

## 11. Confirmation

**Phase A did not modify application source, tests, Gradle, dependencies, Room schema, migrations, R8, or configuration.** The four modified source/test files present in `git status` pre-date this task and were not touched.
