# BLOCK v1.0.77 — EXTERNAL-REVIEW REMEDIATION AND FAST-PATH HARDENING

**Repository:** `morteza-taheri/SoftEthergai` · **Branch:** `main`
**Baseline:** `744767c` (v2.4.14 / 147) · **This block:** `218ecbd`, `da0c6c8`, `6081565` (v2.4.15 / 148)
**Device:** `5585fecc` (2201117TG, Android 13 / API 33) — **attached and authorised**
**Date:** 2026-10-31

---

## A. Inputs

Two independent external reviews were supplied by the operator:

1. `technical-review.md` — a 501-file static review (no code executed) covering native/JNI, SSTP,
   `vpnLib`, the publisher and the docs.
2. An inline code-and-docs read ("I only read the code and the document and ran nothing") that
   concentrated on the Auto Mode control flow.

**Both were written against stale snapshots.** Every claim was therefore re-verified against the tree
at `744767c` before a single line was changed. Claims that no longer hold are recorded in §D rather
than silently "fixed".

---

## B. Verified-true and FIXED in this block

| # | Finding (source) | What the code actually did | Fix | Evidence |
|---|---|---|---|---|
| 1 | The known-good fast path had **no tests** (review 2, its top finding) | `knownGoodFastTarget` + the fast-path block were uncovered | **12 new tests**: no history, UNKNOWN network, stale success, failure after success, quality rejection, network-bucket isolation, remembered protocol, disabled protocol never revived, success origin, failure hand-off exactly once, not used for a Try Next continuation, press honoured | TEST |
| 2 | **A press during the fast path was swallowed** (review 2, inferred) | `skipRequested` was only consumed at the top of the per-server loop, whose `set(false)` cleared a press that landed inside the fast attempt — the tap did nothing | the fast path reads the flag after its attempt, cancels, records `ATTEMPT_CANCELLED`/`USER_CANCELLED` as **NEUTRAL**, and continues without re-dialling that server | SOURCE CODE + TEST |
| 3 | **Connection origin was memory-only** (`REQ-ORIGIN-001`, *Missing* in the spec since v1.0.70) | `runOrigin` lived in the controller; a restart with a live tunnel degraded the label to MANUAL | `persistOrigin` writes `(origin, ip\|hostname)` at the verified-connect funnel; `stop()`/`disconnectNow()` clear it; `combineAutoModeState` trusts a record only when its key matches the live tunnel | SOURCE CODE + TEST + **PHYSICAL DEVICE** |
| 4 | Release-URL check had **no path boundary** (review 2, minor) | `startsWith("/morteza-taheri/SoftEthergai")` also accepted `/morteza-taheri/SoftEthergaiXYZ` | whole-segment match (exact or followed by `/`) + a lookalike test | SOURCE CODE + TEST |
| 5 | **Export could freeze the UI** (review 1 #10) | `while (not granted) requestPermissions(...)` — the condition cannot change inside the call, so a denial spins the UI thread forever | asks **once**, resumes from `onRequestPermissionsResult(REQUEST_WRITE_STORAGE)`, ends with a message on refusal | SOURCE CODE |
| 6 | **Export silently failed on Android 10+** (review 1 #10/#11) | direct `FileOutputStream` into public Downloads is blocked by scoped storage | `MediaStore.Downloads` on API 29+ (no permission at all); legacy path kept for 24..28; stream closed with `use` | SOURCE CODE + lint `@RequiresApi` |
| 7 | **Feed data became a file name** (review 1 #11) | `getName(useUdp) + ".ovpn"` used verbatim | strict `[A-Za-z0-9._-]` allowlist, leading dots stripped, 80-char cap, `vpn_profile` fallback | SOURCE CODE |
| 8 | **Response body was unbounded** (review 1 #12) | `raw.body?.string()` buffered everything | declared length checked, undeclared bodies peeked at `MAX_RESPONSE_BYTES + 1` (8 MB) and **refused**, never truncated | SOURCE CODE |
| 9 | **Backups carried credentials** (review 1 #13) | `allowBackup=true`, no rules | `backup_rules.xml` + `data_extraction_rules.xml` exclude every domain (database, file, sharedpref, external) | SOURCE CODE + merged-manifest check |
| 10 | **`QUERY_ALL_PACKAGES` in the SSTP library** (review 1) | merged manifest requested visibility of every package | permission removed; the existing `<queries>` MAIN/LAUNCHER covers the app picker | SOURCE CODE + merged-manifest check |
| 11 | **SSTP teardown mutex never released** (review 1 #9) | `mutex.tryLock()` with no unlock → every later `kill()` returned a stale `destructionJob` | unlock in `finally`; `destructionJob` cleared | SOURCE CODE |

---

## C. PHYSICAL DEVICE evidence (build 148, installed `-r`)

```
12:34:22  [AUTO] ATTEMPT_CLASS CONNECTED outcome=TUNNEL_UP protocol=SOFTETHER_TCP
12:34:22  [AUTO] Connected successfully
          prefs: SETTING_LAST_CONNECTION_ORIGIN     = AUTO_CONNECT
          prefs: SETTING_LAST_CONNECTION_SERVER_KEY = 118.241.169.191|vpn940496795
12:35:21  [AUTO] Disconnect requested from the connected state
          tun0 = 0 · prefs: 0 occurrences of LAST_CONNECTION  (record cleared)

12:35:41  [AUTO] Started
12:35:41  [AUTO] Fast path: known-good vpn129214879 via SOFTETHER_UDP      ← history + remembered protocol
12:35:53  [AUTO] Fast path failed in 12029 ms; continuing with the normal pipeline
12:36:05  [AUTO] Trying #1 vpn601425201                                    ← the fast server is NOT re-dialled
12:36:13  [AUTO] Connected successfully                                    ← tun0 up
          prefs: SETTING_LAST_CONNECTION_SERVER_KEY = 202.122.252.243|vpn601425201
```

- Version after install: **148 / 2.4.15-dev**; launch clean, **0 FATAL EXCEPTION**.
- Merged manifest: `dataExtractionRules="@xml/data_extraction_rules"`, `fullBackupContent="@xml/backup_rules"`,
  and `QUERY_ALL_PACKAGES` appears **only inside XML comments** (permission gone).

**Not reproduced on the device, and stated rather than implied:** a real process death with a live
tunnel (the VPN service shares the app process, so killing the process also tears the tunnel down) —
the persistence *mechanism* is device-verified, the restart *path* is covered by 6 pure tests.

---

## D. Verified-FALSE claims (no change made)

| Claim | Reality at `744767c` |
|---|---|
| "`app-update/version.json` is not in the repo; the raw URL 404s, so the checker is inert" | **Tracked** (`git ls-files app-update/`), raw URL answers **HTTP 200**, and the device has fetched it |
| "`gradle/wrapper/gradle-wrapper.jar` is absent, so a clean checkout cannot build" | **Tracked.** The reviewer's archive stripped binaries; every build in this project runs from `git archive` |
| "Route policy P0: VPN-only while a tunnel is active is unsafe" | **Approved decision** `REQ-ROUTE-001` (v1.0.70). Only the stale README wording was wrong, corrected in v1.0.76 |
| "Doc header is 1.0.71 and two rows say *This revision*" | **TRUE** — fixed here (header → 1.0.77; 1.0.70 and 1.0.75 markers cleared; 1.0.76 and 1.0.77 rows added) |
| "1566 tests counted, none executed" | TRUE of the reviewer's session. This block executed them: **1402 (`:app`) + 184 (`:SoftEtherClient`)** |

---

## E. Accepted and DEFERRED to a dedicated block

Genuine and serious; none can be validated without native/device work. Recorded in spec §12.11.3 so
the next block starts from an agreed list:

native RUDP lifetime races (`conn->rudp`, `rudp_get_udp_fd`); the timed-out native operation losing its
ownership lease (`NativeLifetimeRegistry`); unauthenticated HTTP bootstrap/mirror sources and the
unsigned emergency CSV; OpenVPN imported profiles without server-identity checks
(`clearDefaults()` → `mCheckRemoteCN = false`); SSTP handshake blocking / packet bounds / reconnect
serialization; SSTP password storage; profile-encryption plaintext fallback; `ConnectionController`
catching broad `Exception`; publisher private-IP validation; merged-manifest and merged-resource
verification; TLS 1.0/1.1 and `SSL_VERIFY_NONE`.

---

## F. Gates

| Gate | Result |
|---|---|
| `:app:testProDebugUnitTest` + `:SoftEtherClient:testDebugUnitTest` (committed tree, `--rerun`) | **1402 / 1 skipped / 0 failures / 0 errors** and **184 / 0 / 0 / 0** — BUILD SUCCESSFUL |
| Two further full runs from `git archive HEAD` | see §G |
| `:app:lintProDebug` | **102 errors / 185 warnings / 9 hints** — errors **exactly at the 102 baseline**, zero new findings from this block (the +7 warnings are `UseKtx`/`TypographyEllipsis`/`PluralsCandidate` from the v1.0.76 update checker, committed before this block) |
| New tests | **19** (12 fast path, 6 persisted origin, 1 path boundary) |

---

## G. Final state

- Commits: `218ecbd` (the block), `da0c6c8` (lint `@RequiresApi`), `6081565` (version 148 / 2.4.15).
- APK: built from `git archive HEAD`, installed on `5585fecc` as **148 / 2.4.15-dev**.
- Working tree: **0 modified** files (untracked evidence folders only).
- `app-update/version.json` deliberately **left at 146** (the newest *published* release). Bumping it
  without publishing a GitHub release would point users at an APK that does not exist.
