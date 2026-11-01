# DEVICE-VERIFY-AUTOCONNECT-FIX-3 — Report

**Phase:** DEVICE-VERIFY-AUTOCONNECT-FIX-3 (v1.0.33)
**Repo:** `C:\Dev\SoftEthergai` · branch `main`
**HEAD at start and at end:** `518c9b767cff168acf74dfb313cca0487193b5e5` == `origin/main`
**Device:** `5585fecc` — 2201117TG (`spes_global`), Android 13 (SDK 33), MIUI V140
**Status of this file:** untracked, **NOT committed** (per brief)

---

## §1 — Verdict

**PARTIAL PASS — instrumentation and budget floor both verified on hardware; the run still terminates,
and the diagnostic has now identified the mechanism.**

| # | Purpose (from brief) | Result |
|---|---|---|
| 1 | `SLOT_UNRELEASED_DIAG` fires from site 1695 (NATIVE_BUSY branch) | **PASS** — 5/5 runs, correct site signature |
| 2 | 30 s floor in effect (`30000`, not `25000`, at T = 5 s) | **PASS** — `budget=30000ms` 5/5; stop line reads `within 30000ms` |
| 3 | Full payload captured to classify the root cause | **PASS** — full payload 5/5; classification in §7 |
| 4 | Is 30 s sufficient? | **NO** — the run still stops, 5/5 |

Per the brief's taxonomy this is case **B** (slot-stop occurred **and** `SLOT_UNRELEASED_DIAG` captured),
not case A (no slot-stop) and not case C (slot-stop without the diagnostic).

The headline is not the verdict word, it is §7: the payload plus the native trace pins the cause to a
**single unbounded call**, and it shows that **no slot-wait budget can ever fix it**. (PHYSICAL DEVICE +
SOURCE CODE + INFERENCE)

---

## §2 — Pre-conditions

| Check | Expected | Observed | Result |
|---|---|---|---|
| `git rev-parse HEAD` | `518c9b76…` | `518c9b767cff168acf74dfb313cca0487193b5e5` | OK |
| `git rev-parse origin/main` | equal to HEAD | `518c9b767cff168acf74dfb313cca0487193b5e5` | OK |
| `git branch --show-current` | `main` | `main` | OK |
| tracked modifications | none | none (`git status --porcelain=v1 \| grep -vE "^\?\?"` empty) | OK |
| spec version | 1.0.33 | `**Document version:** 1.0.33` (line 8) | OK |
| §1.6.25 present | present | `#### 1.6.25 AUTOCONNECT-FIX-3 — Instrumentation and Budget Floor (v1.0.33)` (line 1988) | OK |
| tag count | 5 | 5 | OK |
| `adb devices` lists 5585fecc | `device` | `5585fecc device product:spes_global model:2201117TG device:spes transport_id:1` | OK |

**One transient precondition failure, disclosed.** The first `adb devices -l` of this phase returned an
**empty** list — no device at all — and `adb start-server` / `kill-server` did not change it. A
`PowerShell` `Get-PnpDevice` probe returned nothing as well. On the next attempt (after the operator
re-issued the brief) the device appeared normally and stayed attached for the whole run. Nothing was
installed, changed or cleared during that window; the empty state was a USB/transport condition, not an
app condition. (PHYSICAL DEVICE / UNKNOWN as to cause)

---

## §3 — Install + smoke

| Step | Result |
|---|---|
| `adb install -r app-pro-debug.apk` | `Performing Streamed Install` → **`Success`** |
| `dumpsys package vn.unlimit.vpngatepro` | `versionCode=143 minSdk=24 targetSdk=37` |
| `pm path` | `/data/app/~~Hi0RGjBFb9Ygr8rFwrDm8A==/vn.unlimit.vpngatepro-X9_ZOXTnRJE7neGQ0rh5XA==/base.apk` |
| APK pulled back **after** install and hashed | `42d90c3889abf4ecf31a7e1487a9b05c795cdfd38d1fe136d50309aecab9d14e`, 63,749,658 bytes — **identical to the pre-install file** |
| Launch (`monkey … LAUNCHER 1`) | `Events injected: 1` |
| `pidof` after 8 s | `6247` |
| FATAL / `AndroidRuntime` / `ANR in` in the launch window | none |

The pull-back-and-rehash step matters: it proves the binary that ran is the binary that was audited, not
merely that an install succeeded. The hash matches `AUTOCONNECT_FIX_3_REPORT.md` §9 exactly, and the
report-correction commit `518c9b7` touched only a `.md` file, so no rebuild was required. (PHYSICAL
DEVICE + SOURCE CODE)

---

## §4 — 30 s floor verification

**Device preference** (`run-as … cat shared_prefs/vpn_setting_data_pro.xml`):

```
SETTING_AUTO_MODE_TIMEOUT_SECONDS" value="5"
```

**Derivation** (`AutoModeEngine.kt`, v1.0.33):

```
nativeSlotAwaitMs = (5 * 1000 * 5) = 25_000
    .coerceAtLeast(NATIVE_SLOT_AWAIT_MS = 30_000)   → 30_000
    .coerceAtMost (NATIVE_SLOT_AWAIT_MAX_MS = 60_000) → 30_000
```

**Observed on device**, all five runs, verbatim:

```
[AUTO] Connection timeout = 5 seconds
[AUTO] Native slot not released within 30000ms (attempt=1 phase=CONNECTING); stopping
[AUTO] SLOT_UNRELEASED_DIAG waited=30064ms budget=30000ms …
```

`budget=30000ms` and `within 30000ms` with the device preference still at `5` is direct proof that the
v1.0.33 floor is live: the v1.0.32 derivation would have produced `25000ms`. **PASS.** (PHYSICAL DEVICE)

Measured wait per run: 30064 / 30100 / 30002 / 30011 / 30036 ms — i.e. the poll loop exits within 100 ms
of the deadline every time, which also confirms `awaitNativeSlotRelease` is not leaking extra time.

---

## §5 — Scenario runs and their outcomes

Five runs, no forced scenario, no settings changed. Each run was started by tapping the same centre
control at `(541, 566)` — the bounds centre read from `uiautomator dump`, not eyeballed from a
downscaled screenshot.

| Run | `Trying #1` | Stop | waited | registry attempt | Server | Outcome |
|---|---|---|---|---|---|---|
| 1 | 20:01:21.139 | 20:01:56.382 | 30064 ms | 1 | vpn131251792 | slot-stop → terminal |
| 2 | 20:02:52.333 | 20:03:27.546 | 30100 ms | 2 | vpn131251792 | slot-stop → terminal |
| 3 | 20:04:20.125 | 20:04:55.254 | 30002 ms | 3 | vpn131251792 | slot-stop → terminal |
| 4 | 20:05:34.529 | 20:06:09.668 | 30011 ms | 4 | vpn131251792 | slot-stop → terminal |
| 5 | 20:06:30.770 | 20:07:05.938 | 30036 ms | 5 | vpn131251792 | slot-stop → terminal |

**0 happy-path, 5 slot-stops.** Every run selected the *same* server (`vpn131251792`, `112.69.69.79`,
SoftEther TCP badge) and every run died on **`Trying #1`** — no run ever reached `Trying #2`.

Each terminal was followed by the same recovery sequence, verbatim:

```
[AUTO] Requesting recovery teardown so the unresolved native operation can be released and a later attempt admitted
SoftEtherVpnService: onStartCommand: action=vn.unlimit.softether.DISCONNECT
SoftEtherVpnService: Using caller-supplied teardown attempt id N
SoftEtherProtocol: Force-interrupted primary socket fd=…
SoftEtherProtocol: Force-interrupted NAT-T UDP fd=…
ConnectionController: Native handle -… torn down
ConnectionController: Released process-wide native slot for this attempt
```

So the slot is released **only** by the forced teardown that the terminal itself triggers — never
organically. UI ends in `Error` / "Auto Mode failed. Tap to try again." each time, and a tap starts a
fresh run, i.e. the terminal is recoverable but self-perpetuating.

**No wedge was ever recorded and no server was ever excluded** — `grep -E "recording a wedge|NATIVE_BUSY x|Excluding .* wedges"` returns **nothing** across all five logs. See §7.4 for why that is structural
rather than accidental. (PHYSICAL DEVICE)

---

## §6 — SLOT_DIAG payload (verbatim, all five)

```
10-18 20:01:56.388  6247  7850 D AutoMode: [AUTO] SLOT_UNRELEASED_DIAG waited=30064ms budget=30000ms outcome=NATIVE_BUSY protocol=SOFTETHER_TCP server=vpn131251792 tracker=CONNECTING ownTunnel=false registry=attempt=1 phase=CONNECTING
10-18 20:03:27.557  6247 10198 D AutoMode: [AUTO] SLOT_UNRELEASED_DIAG waited=30100ms budget=30000ms outcome=NATIVE_BUSY protocol=SOFTETHER_TCP server=vpn131251792 tracker=CONNECTING ownTunnel=false registry=attempt=2 phase=CONNECTING
10-18 20:04:55.266  6247 10417 D AutoMode: [AUTO] SLOT_UNRELEASED_DIAG waited=30002ms budget=30000ms outcome=NATIVE_BUSY protocol=SOFTETHER_TCP server=vpn131251792 tracker=CONNECTING ownTunnel=false registry=attempt=3 phase=CONNECTING
10-18 20:06:09.677  6247 10610 D AutoMode: [AUTO] SLOT_UNRELEASED_DIAG waited=30011ms budget=30000ms outcome=NATIVE_BUSY protocol=SOFTETHER_TCP server=vpn131251792 tracker=CONNECTING ownTunnel=false registry=attempt=4 phase=CONNECTING
10-18 20:07:05.948  6247 10588 D AutoMode: [AUTO] SLOT_UNRELEASED_DIAG waited=30036ms budget=30000ms outcome=NATIVE_BUSY protocol=SOFTETHER_TCP server=vpn131251792 tracker=CONNECTING ownTunnel=false registry=attempt=5 phase=CONNECTING
```

### §6.1 Proof that this is site 1695 and not site 1834

The two sites are distinguishable by the shape of the *preceding* stop line:

| Site | Format | Has `after <OUTCOME> on <PROTO>`? |
|---|---|---|
| 1695 (NATIVE_BUSY) | `Native slot not released within ${ms}ms (${describe()}); stopping` | **no** |
| 1834 (post-attempt) | `Native slot not released within ${ms}ms after ${outcome} on ${protocol} (${describe()}); stopping` | yes |

Every one of the five stop lines observed reads:

```
[AUTO] Native slot not released within 30000ms (attempt=N phase=CONNECTING); stopping
```

— no `after … on …` clause. **This is site 1695**, the site that in the v1.0.32 device run terminated
every run while carrying no diagnostic. It now carries one. **Purpose 1 satisfied.** (PHYSICAL DEVICE +
SOURCE CODE)

### §6.2 Payload field-by-field

| Field | Value | Reading |
|---|---|---|
| `waited` | 30002–30100 ms | the full budget elapsed; not an early exit |
| `budget` | 30000 ms | v1.0.33 floor, live |
| `outcome` | `NATIVE_BUSY` | see §7.2 — this is **self-inflicted**, not a leftover |
| `protocol` | `SOFTETHER_TCP` | the loop never advanced past the first protocol |
| `server` | `vpn131251792` | same host every run |
| `tracker` | `CONNECTING` | app-level status, `GlobalVpnTracker` |
| `ownTunnel` | `false` | `isOwnTunnelEstablished()` — no tunnel was ever established |
| `registry` | `attempt=N phase=CONNECTING` | `NativeLifetimeRegistry.describe()` — the attempt was never resolved |

---

## §7 — Root-cause classification

### §7.1 The brief's trichotomy does not fit — and that is itself a finding

| Hypothesis | Stipulated payload | Observed | Verdict |
|---|---|---|---|
| **H1** post-establishment leak | `tracker=CONNECTED`, `ownTunnel=true` | `tracker=CONNECTING`, `ownTunnel=false` | **EXCLUDED** — no tunnel was ever up |
| **H2** `TunnelStateWatcher` race | `tracker=CONNECTED`, `ownTunnel=false` | `tracker=CONNECTING` | **EXCLUDED** — tracker never reached CONNECTED |
| **H3** native-side hang | `tracker=DISCONNECTED`, `ownTunnel=false` | `tracker=CONNECTING` | **MECHANISM CORRECT, FIELD VALUE WRONG** |

The observed state is a fourth combination the brief did not enumerate:
**`tracker=CONNECTING` + `ownTunnel=false` + `registry phase=CONNECTING`** — the app and the native
registry agree that an attempt is *in progress*; nobody believes a tunnel exists; and that attempt never
finishes. Reported as-is rather than forced into H3. (PHYSICAL DEVICE)

### §7.2 Why `outcome=NATIVE_BUSY` appears on the very first attempt

This looked contradictory at first — a refusal usually means *a previous* operation is stuck, yet this is
`Trying #1`. `AndroidConnectionAdapter.kt:413-429` resolves it:

```kotlin
val up = awaitArmedTunnel(protocol, timeoutMs)      // false: no tunnel in 5 s
if (up) return TUNNEL_UP
if (lastArmedFailure) return NATIVE_ERROR
return if (isNativeSlotBusy()) NATIVE_BUSY else TIMED_OUT
```

`isNativeSlotBusy()` is true because **this attempt's own** native operation is still running. So
`NATIVE_BUSY` here means "our own op has not resolved", not "someone else holds the slot". The
controller then enters the 30 s wait for a release that is the *current* operation's own release.
(SOURCE CODE)

### §7.3 What the native operation is actually doing — the cause

Full per-run native timeline (run 5 shown; runs 1–5 are identical in shape):

```
20:06:30.770  [AUTO] Trying #1 vpn131251792
20:06:30.978  nativeConnectWithHub called (useTcp=1)              ← attempt admitted
20:06:31.453  [TLS] handshake entry: conn->timeout_ms=4000, socket_fd=86, SO_RCVTIMEO applied=yes
20:06:31.453  Starting TLS handshake with 112.69.69.79
20:06:32.277  TLS handshake successful                            ← TCP path is healthy and bounded
20:06:36.530  Direct login failed (2); retrying via NAT-T + RUDP fallback
20:06:36.886  NAT-T resolved public UDP endpoint 112.69.69.79:8747
20:06:37.428  rt: valid segment from 8747 -> ESTABLISHED
20:06:37.428  Starting TLS handshake with 112.69.69.79            ← RUDP handshake: NO "handshake entry" line
              ··· 28.5 s of total silence ···
20:07:05.938  [AUTO] Native slot not released within 30000ms …
20:07:06.090  ConnectionController: Native handle -… torn down     ← released only by the forced teardown
```

The two `Starting TLS handshake` lines are **not** equivalent. The first is preceded by the
`[TLS] handshake entry` discriminator (P5a) and by `socket_apply_timeout()`; the second is not.

Source, `softether_protocol.c`:

```c
/* TCP branch — line 2838-2850 */
int so_timeout_applied = (socket_apply_timeout(conn->socket_fd, conn->timeout_ms) == 0);
LOGD("[TLS] handshake entry: conn->timeout_ms=%d, socket_fd=%d, SO_RCVTIMEO applied=%s", …);
result = perform_tls_handshake(conn, connect_host);          /* line 2859 */

/* NAT-T + RUDP fallback — line 2903-2913 */
if (result != ERR_NONE && !conn->using_nat_t) {
    LOGD("Direct login failed (%d); retrying via NAT-T + RUDP fallback", result);
    softether_disconnect(conn);
    result = softether_try_nat_t_connect(conn, host, resolved_ip, port);
    if (result == ERR_NONE) {
        result = perform_tls_handshake(conn, connect_host);  /* line 2908 — NO socket_apply_timeout */
```

`perform_tls_handshake` (`:1218-1251`) applies **no** timeout of its own: it logs, sets
`state = STATE_TLS_HANDSHAKE`, and calls blocking `ssl_connect`. The only bound on this path is the
caller's `socket_apply_timeout()` — which the RUDP call site does not perform.

**The `max_attempts = 100` guard never engages.** `aes_wrapper.c:458-488` retries only when
`SSL_do_handshake` returns `SSL_ERROR_WANT_READ/WANT_WRITE`. Across all five runs there are **zero**
`SSL handshake attempt N: need more data` lines and **zero** `SSL handshake did not complete after 100
attempts` lines. The first `SSL_do_handshake` therefore never returned at all: the RUDP BIO read blocks
indefinitely rather than reporting `EAGAIN`. (SOURCE CODE + PHYSICAL DEVICE)

### §7.4 Consequences that follow directly

1. **The terminal is unavoidable at any budget.** The hang is unbounded, not late. 30 s, 60 s
   (`NATIVE_SLOT_AWAIT_MAX_MS`) or 600 s would produce the identical terminal, only later. The v1.0.33
   floor is still the right change — it is correct on its own terms and removes the 292 ms-late
   mis-termination recorded in the v1.0.32 phase — but it **cannot** close this defect. (INFERENCE,
   supported by §7.3)
2. **BUG-B's wedge can never fire for this failure mode.** `nativeBusyThisServer` is declared at
   `AutoModeController.kt:1556`, inside the per-server loop, and reaches
   `NATIVE_BUSY_WEDGE_THRESHOLD = 2` only if the same server produces **two** refusals **inside one run**.
   This run terminates on the first one, so the counter is structurally stuck at 1 — hence no
   `recording a wedge`, no `Excluding …`, and `vpn131251792` re-wins selection in all five runs. The
   v1.0.32 device run saw the same host four times for exactly this reason. (SOURCE CODE + PHYSICAL DEVICE)
3. **The trigger is the endpoint, not the phone.** This host completes TCP connect and TLS, then fails
   the VPNCONNECT watermark (`read_http_response: failed reading headers at byte 0` → `server may not be
   SoftEther`), which forces the RUDP fallback into the unbounded call. A well-behaved SoftEther node
   would never enter this path. (PHYSICAL DEVICE + INFERENCE)

---

## §8 — Crash / ANR scan

| Check | Result |
|---|---|
| `logcat -d -b all \| grep -E "FATAL EXCEPTION\|AndroidRuntime\|ANR in\|Force finishing"` | **no matches** |
| Process identity | PID **6247** at launch (19:59) and PID **6247** at the end (20:08) — **continuous** across five terminals |
| Service lifecycle | `Service created` → `Service destroyed` per run, cleanly; no orphan service |
| Native teardown | `nativeDestroy completed` after every run; `Released process-wide native slot for this attempt` every time |

No STOP condition was triggered. (PHYSICAL DEVICE)

---

## §9 — What could NOT be tested, and why

1. **A happy-path run.** Zero of five runs connected. The reachable-server ordering put the same broken
   endpoint first every time, and because the run dies on `Trying #1` it never rotates — so the
   opportunity to reach a second server never arose. Not a defect of the test; a consequence of §7.4(2).
2. **The `slot released` success branch.** `[AUTO] Native slot released after Nms` was never emitted.
   Organic release never happened in any run, so the healthy branch of `awaitNativeSlotRelease` is
   unexercised on this build.
3. **`outcome=TIMED_OUT` / `NATIVE_ERROR` at the post-attempt site (1834).** Never reached; the first
   attempt's `awaitArmedOutcome` already returned `NATIVE_BUSY`.
4. **`Continuing from server #N` (OP-R6).** No run reached a Connected state, so there was nothing to
   continue from. OP-R6 was device-verified in the v1.0.32 phase and was not re-exercised here.
5. **Whether a different, healthy server behaves correctly.** Not tested — doing so would have required
   forcing a scenario (server selection), which the brief forbids.
6. **`/data/anr`** unreadable without root; not needed here since no ANR occurred.

---

## §10 — Evidence labels per claim

| Claim | Label |
|---|---|
| Budget floor reads 30000 on a T=5 s device | PHYSICAL DEVICE |
| Diagnostic fires from site 1695 | PHYSICAL DEVICE + SOURCE CODE (message-shape discriminator) |
| Payload is `CONNECTING / false / CONNECTING` in 5/5 runs | PHYSICAL DEVICE |
| `outcome=NATIVE_BUSY` is self-inflicted | SOURCE CODE (`AndroidConnectionAdapter.kt:427`) |
| RUDP fallback handshake has no timeout | SOURCE CODE (`softether_protocol.c:2908` vs `:2839`) |
| `SSL_do_handshake` blocked on its first call | PHYSICAL DEVICE (zero retry lines) + SOURCE CODE (`aes_wrapper.c:458-488`) |
| Any budget would fail identically | INFERENCE (from the two rows above) |
| Wedge counter structurally stuck at 1 | SOURCE CODE (`:1556`) + PHYSICAL DEVICE (no wedge lines) |
| No crash / ANR / process death | PHYSICAL DEVICE |
| Endpoint fails the watermark then hangs on RUDP | PHYSICAL DEVICE (log timeline) |

---

## §11 — Negative assurance

- **No** uninstall and **no** data clear on the device.
- **No** device setting changed; `SETTING_AUTO_MODE_TIMEOUT_SECONDS` read, never written.
- **No** synthetic race injection, no thread manipulation, no forced server selection.
- **No** source, test or spec file modified; `git status` shows **zero** tracked modifications at the end.
- **No** commit and **no** push. This report is untracked.
- **No** tag operation (count still 5).
- **No** `git add -A` / `git add .`; nothing was staged.
- **No** `--force` / `reset` / `clean` / `stash` / `checkout` / `restore`.
- **C5** was not executed.
- `D:\PMC\Documents\Android\SoftEthergai` was never touched.
- The APK was installed with `-r` only; no prior app data was removed.

---

## §12 — Recommendations (proposed only; **not begun**, no next phase assumed)

1. **Bound the RUDP fallback handshake.** The one-line asymmetry is the defect: the TCP branch calls
   `socket_apply_timeout()` before `perform_tls_handshake`, the RUDP branch does not
   (`softether_protocol.c:2839` vs `:2908`). Note that `SO_RCVTIMEO` alone may be insufficient here
   because RUDP reads do not go through a plain `recv()` on the UDP fd — the bound likely has to live in
   the RUDP transport's read path, or be enforced around the whole `nativeConnectWithHub` call.
   (INFERENCE — needs a source-level phase, not a budget change)
2. **Add the `[TLS] handshake entry` discriminator to the RUDP call site too**, so the next device log
   answers "bounded or not?" for both paths from one grep. The P5a precedent already exists.
3. **Make the wedge counter survive the terminal**, or count a `NATIVE_BUSY`-derived terminal as a
   strike, otherwise §7.4(2) keeps re-selecting the same broken endpoint forever.
4. **Do not raise `NATIVE_SLOT_AWAIT_MS` again** on the assumption that "more time will help". Per §7.3
   the wait is unbounded, so a larger budget only lengthens every failed run by that amount.
5. Re-run this same five-run protocol after the native bound exists; the discriminator is
   `SLOT_UNRELEASED_DIAG` count = 0 and at least one `[AUTO] Native slot released after …ms` line.

---

*End of report.*
