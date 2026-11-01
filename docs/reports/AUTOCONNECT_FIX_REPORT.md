# AUTOCONNECT-FIX — Revision Report

| Field | Value |
| --- | --- |
| Revision | 1.0.31 |
| Date | 2026-10-18 |
| HEAD | `c5fa4362a0c1a39166112fd1f6be552418af17dd` |
| Scope | Device-free; two AutoConnect audits closed |
| Spec | `PROJECT_SPECIFICATION.md` §1.6.1, §1.6.23, §6.5, §6.7, §6.8, §20 |

## 1. What was wrong

Two independent audits of the Auto Connect subsystem found four gaps. The audits
were run at different times and reached the same conclusions from different
evidence, which is recorded in §1.6.23 as the reconciliation section.

1. **D-1 — default attempt timeout drifted.** Three figures described one setting:
   `DEFAULT_ATTEMPT_TIMEOUT_MS = 25_000L` in `AutoModeController.kt`,
   `getIntSetting(SETTING_AUTO_MODE_TIMEOUT_SECONDS, 12)` in `DataUtil.kt`,
   and 5 s in the spec. Nothing pinned any of them, so the drift was invisible to
   CI.
2. **D-2 — a thrown exception advanced to the next *server*, not the next
   *protocol*.** A `break` at `AutoModeController.kt:1446` meant a server that
   crashed on its first protocol was never retried on its second.
3. **D-4 — the tunnel-busy guard had no production caller.** `isTunnelBusy()`
   existed only in `startOrRefuseIfNoServers()`, which nothing calls. Pressing
   the button during a manual connection dispatched a second, competing
   `ACTION_CONNECT`.
4. **D-5 / OP-R6 — pressing while Connected restarted at #1 instead of moving
   on.** The documented meaning of a Connected press was "continue", but the code
   restarted the list from the top.

## 2. What changed

| ID | Change | File:line |
| --- | --- | --- |
| D-1 | `DEFAULT_ATTEMPT_TIMEOUT_MS` `25_000L` → `12_000L`; `DataUtil` default `12` with `.coerceIn(5, 60)` | `AutoModeController.kt:231`, `DataUtil.kt` |
| D-2 | `break` → `continue` on the thrown-exception path | `AutoModeController.kt:1446` |
| D-4 | `isTunnelBusy()` guard wired to the production button path; `Disconnected`/`Error` press refused with `[AUTO] start refused: a tunnel is already active` | `AutoModeController.kt:1010` |
| D-5 | `resumeFromIndex = currentServerIndex + 1` recorded on a Connected press, consumed at the next run start, reset when the list is exhausted | `AutoModeController.kt:532`, `:1026`, `:1773` |
| D-6 | Four terminal conditions documented (§6.7 R7); native connect budget is 80 % of `T` (`NATIVE_TIMEOUT_FRACTION_NUM/DEN = 4/5`, `MIN_NATIVE_CONNECT_TIMEOUT_MS = 1_500L`) | `AndroidConnectionAdapter.kt:74-75`, `:84` |
| D-7 | New §6.8 Per-Attempt Timeout and Rotation Contract | `PROJECT_SPECIFICATION.md` |
| D-8 | Six new tests T-NEW-1..T-NEW-6 | `AutoModeControllerTest.kt` |

## 3. Tests

Run under `CI=true` with `--rerun-tasks --no-build-cache` (§22 L2):

| Module | Result |
| --- | --- |
| `:app` | **796 tests, 0 failed, 0 errors, 1 skipped** |
| `:SoftEtherClient` | 173 tests, 0 failed, 0 errors, 0 skipped |

The single skip is `HtmlParserOracleTest.softetherUdpOnlyNeverBorrowsOpenvpnTcp`,
an `AssumptionViolatedException` (data-dependent, not a failure). This skip is
unchanged from before the revision.

The six new tests are T-NEW-1 (D-1 default), T-NEW-2 (D-4 refusal), T-NEW-3
(D-5 continue), T-NEW-4 (D-5 edge — last server), T-NEW-5 (D-2 next protocol),
T-NEW-6 (D-5 reset). The `DEFAULT_ATTEMPT_TIMEOUT_MS` assertion in
`AutoModeNativeBusyTest.kt:511` was corrected to `12_000L`.

One pre-existing test (`pressWhileConnectedDisconnects`, Test 10) asserted the
pre-v1.0.31 behaviour (`!isRunning` after a Connected press). It was updated to
assert the v1.0.31 contract: a Connected press starts a continuation run that
terminates with `ERROR_NO_SERVER` when the list is exhausted. This is a contract
change, not a defect.

## 4. Build

Standalone `:app:assembleProDebug` under `CI=true` succeeded.

| Artefact | SHA-256 |
| --- | --- |
| `app/build/outputs/apk/pro/debug/app-pro-debug.apk` | `1CCFC62EA723E4696005C6F6F7FF07F544D657DF6A5F738233B8E07986A03F1D` |

## 5. What was not touched

- No device, no tag, no force, no `git add -A`.
- §22 byte-identical (99 lines / 6,504 bytes).
- §1.6.2–§1.6.22 untouched.
- No audit reports modified; `docs/reports/` left as-is.