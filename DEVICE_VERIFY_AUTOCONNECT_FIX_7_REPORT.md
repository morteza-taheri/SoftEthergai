# DEVICE VERIFY — AUTOCONNECT-FIX-7 (v1.0.37)

**Device:** `5585fecc` (Xiaomi `2201117TG`, `spes`)
**Build under test:** `4FE4417469C7C7829DED3C7B723705DC77678B28FAA14345E057108BE9EB6E68`
(63,755,062 bytes), installed with `-r` at 12:01:30
**Installed package:** `vn.unlimit.vpngatepro` — versionCode 143, versionName `2.4.10-dev`
**Baseline:** `HEAD == origin/main == e7f7f93` · spec v1.0.37 · §1.6.29
**Setting:** `SETTING_AUTO_MODE_TIMEOUT_SECONDS = 5` — **read and left unchanged**
**Date:** 2026-10-19, 12:03–12:16
**Evidence directory:** `test-evidence/dv7/`

---

## §1. Verdict

**PASS on every assertion that this session could exercise. One assertion was NOT OBSERVED, and the
reason it could not fire is itself the fix under test.**

| # | Assertion | Result |
|---|---|---|
| 1a | **Zero** `ERROR_NATIVE_UNRESOLVED` across 5 runs (v1.0.36: 2/5) | **PASS** — 0 occurrences |
| 1b | At least one `Slot-wait expired … moving on (no terminal, per §6.8.4)` | **NOT OBSERVED** — 0 occurrences; see §3.3 |
| 1c | No `Recording a wedge on the NATIVE_BUSY terminal` | **PASS** — 0 occurrences |
| 1d | Any error ends with `All compatible servers failed` | **PASS (vacuous)** — no run ended in error |
| 2 | OP-R6 main button → `Continuing from server #N+1 of M`, next dial is N+1 | **PASS** |
| 3 | OP-R6 try-next → first dial is the server after N, no restart | **PASS** |
| 4 | `[TLS] handshake entry (RUDP)` | **ABSENT** — Fix-A remains UNVERIFIED |

No crash, no ANR, and the process never restarted (PID 8715 throughout).

---

## §2. Pre-conditions

| Check | Expected | Observed | Result |
|---|---|---|---|
| `HEAD` == `origin/main` | `e7f7f93` | `e7f7f93a42779787c8d19b3f4eeabcb12e0ad9b5` | PASS |
| Spec version | 1.0.37 | 1.0.37 | PASS |
| APK SHA-256 | `4FE44174…9EB6E68` | identical | PASS |
| Device visible | `5585fecc` | `5585fecc device` | PASS |
| Timeout setting | 5 s, unchanged | `<int name="SETTING_AUTO_MODE_TIMEOUT_SECONDS" value="5" />` | PASS |
| Package name | — | `vn.unlimit.vpngatepro` (pro flavor) | note |

The package is the **pro** flavor; `vn.unlimit.vpngate` does not exist on this device.

---

## §3. Test 1 — five AutoConnect runs

### 3.1 Per-run results

| Run | Time | Start point | Servers walked | Outcome |
|---|---|---|---|---|
| 1 | 12:04:23 | fresh (#1) | #1 `vpn611507027` TCP+UDP `TIMED_OUT`; #2 `vpn805442422` TCP `TIMED_OUT`, UDP **Connected** | Connected |
| 2 | 12:05:47 | `Continuing from server #3 of 85` | #3 `vpn523361413` | Connected (4.5 s) |
| 3 | 12:06:26 | `Continuing from server #4 of 85` | #4 `vpn105246560` | Connected |
| 4 | 12:07:04 | `Continuing from server #5 of 85` | #5 `vpn825186256` | Connected (`notification missed`) |
| 5 | 12:13:00 | fresh (#1) | #1 TCP+UDP `TIMED_OUT`; #2 TCP+UDP `TIMED_OUT`; #3 `vpn523361413` **Connected** | Connected |

Runs 2–4 were started by pressing the main button while Connected, which is the documented
"continue from the next server" path (OP-R6) — each is a full AutoConnect run.

### 3.2 The v1.0.36 scenario, reproduced and survived

Run 1 and run 5 are the exact configuration that produced the v1.0.36 regression: **the first server
fails and the run used to die there.**

```
12:04:30.437 [AUTO] TIMED_OUT after 5000ms on vpn611507027/SOFTETHER_TCP; cleanup then next protocol
12:04:39.704 [AUTO] TIMED_OUT after 5000ms on vpn611507027/SOFTETHER_UDP; cleanup then next protocol
12:04:43.602 [AUTO] Connection failed: vpn611507027
12:04:43.602 [AUTO] Trying #2 vpn805442422
...
12:04:53.267 [AUTO] Connected successfully
```

In v1.0.36 this same sequence terminated the run with `ERROR_NATIVE_UNRESOLVED` (2 of 5 runs, §7.1 of
`DEVICE_VERIFY_AUTOCONNECT_FIX_6_REPORT.md`). Here it walked to #2 and connected.

Run 5 repeated it with both protocols of the first **two** servers failing (four `TIMED_OUT` in a
row) and still reached #3.

### 3.3 Why assertion 1b could not fire — and why that is the result

`Slot-wait expired …` is emitted from exactly two branches, both guarded by `!released`:

- the `NATIVE_BUSY` branch — reached only on a **genuine refusal**;
- the post-attempt branch — reached when the slot is still held after `min(30 s, T) = 5 s`.

This session produced:

| Metric | Count |
|---|---|
| `NATIVE_BUSY` events (any form) | **0** |
| `TIMED_OUT` events | 7 |
| Post-attempt slot waits that **released** | 4 (`1010`, `1110`, `1311`, `1613` ms) |
| Post-attempt slot waits that expired | **0** |

So the NATIVE_BUSY branch never ran, and every post-attempt wait released in ~1.0–1.6 s against a
5 s budget. The string is therefore **unreachable** in this session.

That is the fix working: the seven events that would have been classified `NATIVE_BUSY` under
v1.0.36's `isNativeSlotBusy()` re-check are now correctly `TIMED_OUT`. The assertion is retained as
a **latent** check — it will fire on a device state where a refusal genuinely holds the slot past the
budget, which this session did not produce.

### 3.4 Wedge accounting (positive side-evidence)

Run 5 logged the successor mechanism:

```
12:13:07.795 [AUTO] Excluding vpn611507027 from this session after 2 wedges.
```

Two `TIMED_OUT` strikes accumulated **across runs** (one in run 1, one in run 5) — exactly what the
rewritten `aTimedOutWedgeAccumulatesAcrossRunsAndExcludesTheServer` pins, and exactly what the
withdrawn v1.0.34 terminal wedge used to do through a path that no longer exists.

---

## §4. Test 2 — OP-R6, main button while Connected

State before: Connected to `vpn523361413` (server **#3** of 85). Pressed the main button.

```
12:14:26.937 [AUTO] Continuing from server #4 of 85
12:14:26.938 [AUTO] Trying #1 vpn105246560
12:14:31.510 [AUTO] Connected successfully
```

- `Continuing from server #4 of 85` — **N+1 = 4** for N = 3. **PASS.**
- Next dial `vpn105246560` is the **4th** server in the sorted list (run 3 dialled it as #4). It is
  **not** `vpn611507027`, the list's #1. **PASS.**

**Indexing nuance (must be read with the log):** the `Trying #1` in that line is **relative to the
sliced remaining list**, not to the original 85. The absolute position is carried by the
`Continuing from server #4 of 85` line immediately above it. A reader who greps only `Trying #`
would conclude the run restarted from #1; the two lines together are the correct evidence.

---

## §5. Test 3 — OP-R6, "Try next server" while Connected

State before: Connected to `vpn105246560` (server **#4**). Tapped **Try next server**.

```
12:15:00.560 [AUTO] Connected server skipped by user; trying next
12:15:01.319 [AUTO] Teardown confirmed complete (teardown#17(session=1, NATIVE_CONFIRMED))
12:15:01.320 [AUTO] Started
12:15:01.322 [AUTO] Servers available = 81
12:15:03.162 [AUTO] Trying #1 vpn825186256
12:15:08.408 [AUTO] Connected successfully
```

- The intent line is present. **PASS.**
- The new run is seeded with **81** candidates (the tail after #4 of 85), and its first dial
  `vpn825186256` is the server **after** #4 — the same server run 4 dialled as #5. **PASS.**
- No restart from #1: `vpn611507027` is never dialled again. **PASS.**
- The teardown preceding the new run was `NATIVE_CONFIRMED`, i.e. the skip is a clean user move, not
  an unexpected drop.

---

## §6. Test 4 — Fix-A (`[TLS] handshake entry (RUDP)`)

| Check | Result |
|---|---|
| `handshake entry (RUDP)` in all five runs | **0** |
| `handshake entry (RUDP)` in the two OP-R6 runs | **0** |
| Plain `handshake entry: … applied=yes` | 8 (all `timeout_ms=4000`, `applied=yes`) |
| RUDP initialisation lines | present (`rudp_init_client`, `RUDP: using TCP server IP`, `server key V2 received`, `RUDP socket fd=… protected from TUN routing`) |

RUDP was **used** (run 1 connected over `SOFTETHER UDP`), but the **NAT-T fallback handshake site**
that Fix-A bounds (`softether_protocol.c:2935`) was never reached — no NAT-T rendezvous occurred, so
the `(RUDP)`-tagged entry was never printed.

**Fix-A remains its own UNVERIFIED item.** This session neither confirms nor refutes it.

---

## §7. Side findings

1. **Auto Mode screen vs. real tunnel state (pre-existing, not a v1.0.37 regression).** After run 4
   the Auto Mode screen read `Disconnected / Tap to connect` while `tun0` was still up and
   keepalives were still being received; the **Status** tab correctly showed
   `Tap button to disconnect from SoftEther:Japan[14.101.10.239][TCP:1352]`. A tap in that state is
   refused by the D-4 guard with `start refused: a tunnel is already active` — the guard is correct,
   the Auto Mode screen's state is stale. This is the same inconsistency recorded in the v1.0.36
   device report; it is **not** touched by this phase and is **not** a regression. Run 5 was
   therefore taken after disconnecting from the Status tab.
2. **`run05.log` is an aborted attempt, not a run.** It contains a single line —
   `start refused: a tunnel is already active` — and no `[AUTO] Started`. It is kept in the evidence
   set for completeness and is excluded from the five-run aggregate; `run05b.log` is the fifth run.
3. **Process stability.** PID `8715` from 12:03 to 12:16; empty crash buffer; `no ANR has occurred
   since boot`.
4. **Slot releases are consistently fast** (1010–1613 ms), well inside the 5 s capped budget. Under
   the v1.0.36 classification these same instants were read as refusals; the release latency itself
   is unchanged, only its interpretation.

---

## §8. Evidence inventory (`test-evidence/dv7/`)

| File | Content |
|---|---|
| `run01.log`, `run01_utf8.log` | Run 1 (UTF-16 original + UTF-8 conversion; `>` redirection produced UTF-16) |
| `run02.log` … `run05b.log` | Runs 2–5 (UTF-8) |
| `run05.log` | Aborted attempt (guard refusal) — not a run |
| `all5.log` | Concatenation of the five runs |
| `opr6_main.log` | Test 2 |
| `opr6_trynext.log` | Test 3 |
| `run01.png` … `run05b.png`, `opr6_main.png`, `opr6_trynext.png` | Screenshots at each terminal |
| `status_tab.png`, `status_after_disconnect.png` | The UI-state inconsistency of §7.1 |
| `final_state.png` | Session end: Connected `vpn825186256`, `tun0 = 10.211.1.63/16` |

---

## §9. Negative assurance

- No source, test, spec or build file was modified; no commit, no push, no tag.
- `SETTING_AUTO_MODE_TIMEOUT_SECONDS` was **read**, not written.
- No app data was cleared; the installed build is the v1.0.37 APK from `origin/main` (`e7f7f93`).
- No `adb root`, no `/data` extraction beyond `run-as` for the read-only preference check.
- The device is left **Connected** (`vpn825186256`, `10.211.1.63/16`), the same end state the
  previous device-verify phase left.

---

## §10. Recommendation

The v1.0.37 contract is **confirmed on hardware** for the scenario it was written for: a first-server
failure no longer ends the run, and neither OP-R6 path restarts from the top of the list.

What a next device phase should target is the one path this session could not reach: a **genuine
refusal whose slot outlives the 5 s budget**, which is the only way to exercise
`Slot-wait expired … moving on` and to prove that the expiry really does reach the next server rather
than merely logging. Reproducing it needs a device state where a previous native operation is stuck
(the v1.0.32–v1.0.34 reports show the endpoints that did this), not a healthy network.
