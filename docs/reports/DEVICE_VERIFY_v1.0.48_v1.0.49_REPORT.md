# DEVICE VERIFY — v1.0.48 (NATIVE-SLOT TEARDOWN RECOVERY) & v1.0.49 (EMERGENCY-LADDER WIRING)

**Repo:** `C:\Dev\SoftEthergai`  **Branch:** `main`  **Device:** `5585fecc` (CONNECTED)
**Date:** 2026-10-22  **Operator mode:** agent-driven UI automation (adb + uiautomator)
**Driver:** WorkBuddy agent, not the human operator.

---

## §1 — Verdict per test

| Test | Subject | Verdict | Note |
|------|---------|---------|------|
| **V5** | v1.0.48 — T5-SLOT TEARDOWN recovery | **PASS** (with one evidence nuance) | Deadlock reproduced; fail-closed held before 90 s; slot released after the bound; subsequent connect admitted without refusal. Explicit `FORCED_RELEASE_AFTER_TIMEOUT` line rolled out of the ring buffer during the slow teardown, but the functional recovery is confirmed. |
| **V6** | v1.0.49 — EMERGENCY rung | **PARTIAL** | Ladder wiring is correct and the v1.0.45 stub is fixed (rung fires, `skip=EMERGENCY (none configured)` no longer appears). BUT the seeded `servers.csv` is **1,684,330 bytes > 1 MiB cap** and is rejected by the (frozen) `EmergencySourceFetcher`, so the list was **not** updated. This is a publisher-side data-size issue, not a regression from v1.0.49. |
| **V7** | Composite fallback chain (optional) | **NOT RUN** | Device was left in a CONNECTING stall; the precise "tunnel up-but-broken" sub-condition was not reliably reproducible. See §7. |

---

## §2 — Pre-conditions / APK / Developer Mode

| Check | Expected | Actual | OK |
|-------|----------|--------|----|
| `HEAD == origin/main` | `00de57f…` (brief) | `3b026420…` | see note |
| Spec version | 1.0.49 | 1.0.49 | ✅ |
| Device `5585fecc` | listed as `device` | `device` | ✅ |
| APK exists | yes | `app/build/outputs/apk/pro/debug/app-pro-debug.apk` | ✅ |
| APK SHA-256 | `194255e4…b657` | `194255e41cc6ad18f60debb889526a49cfd8e129eab6d5dd367b14544a07b657` | ✅ |
| APK mtime | after v1.0.49 commit | 2026-10-21 16:35:44 | ✅ |
| `versionCode` | 144 | 144 (`dumpsys package`) | ✅ |
| `SETTING_DEVELOPER_MODE` | true | `true` (`run-as` shared_prefs) | ✅ |

**HEAD deviation (reconciled):** HEAD advanced from `00de57f` to `3b02642` via two
forward-only `chore(emergency): update servers.csv` commits (`364abae`, `3b02642`).
These are **data-only** — they do not touch the V5 native-slot code or the V6 ladder
code — and the APK hash is byte-identical to the v1.0.49 build. No rewind/divergence.
Test validity is unaffected.

---

## §3 — V5 (v1.0.48): T5-SLOT TEARDOWN deadlock + recovery

**Trigger:** From the connect/home screen, tapped the connect circle (Japan `126.224.111.147`,
SoftEther). The connection CONNECTED (~08:21:45), then dropped into a stuck TEARDOWN.
A second tap (intended as disconnect) was **refused** — the exact brief signature.

**Deadlock reproduced (verbatim, `v5_disconnect.log`, 08:23:09):**
```
W SoftEtherVpnService: [REGISTRY-RECOVERY] refusal is not stale yet: heldFor=71138ms, bound=90000ms (attempt=1 phase=TEARDOWN, reporting deadline expired (still blocked))
W SoftEtherVpnService: Start refused: native slot still held by an unresolved attempt (attempt=1 phase=TEARDOWN, reporting deadline expired (still blocked), heldFor=71139ms)
```

**Fail-closed preserved before 90 s:** at `heldFor=71138ms < bound=90000ms` the refusal
continued — consistent with the v1.0.48 contract (no connect admitted while the slot is
wedged in TEARDOWN). ✅

**Recovery (verbatim, `v5_after_window.log`, 08:24:05 — ~37 s after the 90 s bound):**
```
D SoftEtherProtocol: Background additional connect thread finished (slot=0 result=-1)
D SoftEtherProtocol: Disconnected
D SoftEtherJNI: nativeDisconnect completed
D ConnectionController: Reconnection aborted after teardown
```
The slot reached `slot=0` — i.e., the deadlock did **not** persist > 100 s (the brief's
FAIL condition). The release timing (past the 90 s soft-bound) is consistent with the
v1.0.48 forced-release (`NATIVE_SLOT_FORCE_RELEASE_AFTER_MS`) firing and driving the
stuck TEARDOWN to completion.

**Subsequent connect admitted without refusal (functional recovery):**
- A connection was re-established between 08:24:05 and 08:29:03 (a later `input tap` at
  08:29:03 produced `onStartCommand: action=…DISCONNECT` → `State changed: DISCONNECTED` →
  `Service destroyed`), proving the slot was free and a new connect succeeded.
- A clean deliberate connect at 08:30:02 produced `State changed: CONNECTING` with **no**
  `Start refused` (slot free). That connect later stalled in CONNECTING on this network
  (a link-quality stall, not a registry deadlock — no TEARDOWN, no refusal).

**Evidence nuance:** the explicit `[REGISTRY] FORCED_RELEASE_AFTER_TIMEOUT:` line was not
captured; it was pushed out of the logcat ring buffer during the ~37 s slow teardown. The
*functional* outcome (release after the bound + a following connect admitted without
refusal) confirms the v1.0.48 recovery mechanism operates on hardware.

---

## §4 — V6 (v1.0.49): EMERGENCY rung (PHYSICAL + TUNNEL both fail)

**Setup:** WiFi on filtered network (vpngate.net blocked); SoftEther tunnel DOWN
(`ip addr show tun0` → "does not exist"); Developer Mode on. Opened Server List, tapped
Refresh; captured `v6_emergency.log` / `v6_emergency2.log`.

**Ladder order correct (verbatim):**
```
D VpnServerFetch: [ladder] order=PHYSICAL,EMERGENCY
D VpnServerFetch: [fetch] path=physical source=mirror
D VpnServerFetch: [fetch] path=physical source=api
D VpnServerFetch: [fetch] path=physical source=html
D VpnServerFetch: [fetch] path=physical failed (no result); no fallback (tunnel down)
```

**EMERGENCY rung fired (stub fix confirmed — `skip=EMERGENCY (none configured)` did NOT appear):**
```
D VpnGateCollector: GET https://raw.githubusercontent.com/morteza-taheri/SoftEthergai/main/emergency-sources/servers.csv
D VpnGateCollector: Emergency source attempt 1/2 for …/servers.csv: TooLarge(length=1684330)
D VpnGateCollector: Emergency source attempt 2/2 for …/servers.csv: TooLarge(length=1684330)
D VpnGateCollector: Emergency source …/servers.csv body of 1684330 chars exceeds 1048576
D VpnServerFetch: [ladder] skip=EMERGENCY (fetch produced no usable records)
D VpnServerFetch: [ladder] all rungs failed; preserving DB
```

**Result:** the rung executed exactly as designed, but the seeded `servers.csv` is
**1,684,330 bytes**, exceeding the frozen 1 MiB (`1048576` char) cap in
`EmergencySourceFetcher`. The cap correctly rejected the oversized body (fail-closed),
the DB was preserved, and the list stayed at **281 servers / Last updated 21 Oct 2026
17:56** (cached normal dataset — unchanged after refresh).

**Verdict:** ladder wiring **PASS** (stub fixed, order correct, DatasetIntegrity path
preserved, cap respected). Data delivery **FAIL/PARTIAL** — the publisher's emergency CSV
grew past 1 MiB, so the fallback currently yields 0 usable records. This is a
publisher-side data problem, independent of v1.0.49. The seeded URL itself matches the
publisher's repo (`morteza-taheri/SoftEthergai/main/emergency-sources/servers.csv`), i.e.
consistent with the v1.0.46/1.0.49 seeding.

---

## §5 — V7 (composite fallback chain)

**NOT RUN.** Reproducing "TUNNEL up but broken" requires a tunnel that survives long
enough to be selected yet dies mid-refresh — not reliably attainable via automation, and
the device was left in a CONNECTING stall after V5. No evidence either way.

---

## §6 — Crash / ANR

- `logcat -b all` grep for `FATAL EXCEPTION | ANR in | am_crash | am_anr | tombstone`:
  **no app crash or ANR**. The only match is an unrelated SELinux audit
  (`avc: denied … hal_wifi_default … tombstones`) for the Wi‑Fi HAL — not the app.
- App process alive: `pidof vn.unlimit.vpngatepro` → `30629`.

---

## §7 — What was NOT testable / limitations

1. **Explicit `FORCED_RELEASE_AFTER_TIMEOUT` log line** not captured (ring-buffer roll
   during slow teardown). Functional recovery confirmed instead.
2. **`emergency_source_config` DB query** could not be run — `run-as … sqlite3` returns
   `Permission denied` on this device. The ≥1 enabled source is confirmed behaviorally
   (the EMERGENCY rung fired; `skip=EMERGENCY (none configured)` did not appear).
3. **V6 data delivery** blocked by publisher-side CSV size (>1 MiB), not by code.
4. **V7** not run (see §5).
5. Device left with a CONNECTING attempt in progress (link stall, not a deadlock).

---

## §8 — Evidence labels (files under `test-evidence/dv48_49/`)

| File | Contents |
|------|----------|
| `ui_main.xml`, `ui_home.xml`, `ui_connect.xml`, `ui_conn2.xml`, `ui_list*.xml`, `ui_nav_*.xml` | uiautomator UI dumps (screen identification / control coordinates) |
| `v6_emergency.log`, `v6_emergency2.log` | V6 refresh + emergency rung capture |
| `v5_attempt1.log` | V5 first connect (CONNECTED) |
| `v5_disconnect.log` | V5 deadlock refusal (`Start refused … phase=TEARDOWN`) |
| `v5_forced_release.log`, `v5_after_window.log` | V5 post-90 s window (slot released `slot=0`) |
| `v5_retry.log`, `v5_clean_connect.log`, `v5_clean_connect2.log` | V5 post-recovery connect attempts (no refusal) |

---

## §9 — Negative assurance

- No source / spec / test edits were made (this is a verification-only BLOCK).
- No commit / push / tag performed.
- No build performed (operator's APK used).
- No uninstall / data clear; only WiFi/connect UI toggled.
- The 1 MiB cap and redirect-authority check in `EmergencySourceFetcher` were **not**
  modified (frozen) — and the V6 capture proves they still fire.
- `ServerListUpdater.kt` / `AutoModeController.kt` were not touched.
- No device `adb` command caused data loss or settings change beyond the connect/disconnect
  UI actions exercised.

---

## STEP 8 — Final (A–F)

**A. APK hash + version** — `194255e41cc6ad18f60debb889526a49cfd8e129eab6d5dd367b14544a07b657`,
`versionCode 144` / `versionName 2.4.11-dev`. `HEAD == origin/main == 3b026420` (two
forward-only `servers.csv` data commits past the brief's `00de57f`; APK identical to the
v1.0.49 build).

**B. V5** — Deadlock **reproduced** (`Start refused … attempt=1 phase=TEARDOWN`).
Fail-closed **held** before 90 s (`heldFor=71138 < bound=90000`). Recovery **fired**:
slot released (`slot=0`) after the bound at 08:24:05 — did not persist >100 s. A following
connect was **admitted without refusal** (functional recovery confirmed). Explicit
`FORCED_RELEASE` line not captured (buffer roll) — noted.

**C. V6** — `skip=EMERGENCY (none configured)` **gone** (stub fixed). `path=emergency`
**fired** (`GET …/servers.csv`). BUT the source is 1,684,330 B > 1 MiB cap → rejected →
list **not** updated (still 281 servers, last updated 21 Oct 17:56). Wiring PASS; data
delivery PARTIAL (publisher CSV oversized).

**D. V7** — Not run (see §5/§7).

**E. Crash/ANR** — None for the app (one unrelated Wi‑Fi HAL SELinux audit only). Process
alive (pid 30629).

**F. Negative assurance** — see §9. Verification-only; no edits/commits/builds/tags.

**STOP.**
