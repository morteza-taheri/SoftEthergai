# BLOCK v1.0.69 PART 2 — Audit & Decision Report (STOPPED per stop conditions)

> Read-only. **No source/test/Gradle/cache/DB/device change. No commit/push.**

## 1. Executive Verdict

**BLOCKED — the approved numerical scoring parameters cannot be recovered from the source or spec.** The
existing ranking is a **tier-ordinal comparator with no weights, no half-life, no Laplace smoothing, and no
decay** — stated explicitly in `AutoModeHistory.kt:47–54` ("There is no weight, no score, no decay, no
cooldown and no threshold"). Implementing a numeric formula would require **inventing** values the brief
explicitly forbids. The exploration **run boundary exists** (`runSequence`/`runToken`,
`AutoModeController.kt:2112/2237`) but there is **no attempted-candidate tracking** and no exploration
selection.

## 2. Initial Working-Tree State

HEAD `f9b3b9d`; 9 tracked source files modified + `ServerListUpdaterTest.kt` (Checkpoint 10 test); untracked
PART 1 files/reports preserved; `git diff --check` clean. **Nothing changed in this checkpoint.**

## 3. Source Audit Findings [SOURCE CODE]

- `sortedForAutoConnect` = `byQuality` (speed DESC, ping, sessions, score) → RTT tie-break → history
  tie-break (`applyHistoryTieBreak`) → the v1.0.67 `demoteUnhealthy` stable partition.
- `historyComparator` / `historyTierOf` (`AutoModeHistory.kt:56–112`): tiers
  `CURRENT_NETWORK_SUCCESS(0) / OTHER_NETWORK_SUCCESS(1) / INCONCLUSIVE(2) / SERVER_FAILURES(3)`; inside a
  tier: most-recent success, then fewest consecutive failures, then fewest total failures. **All keys are
  ordinals / stored counters / timestamps — no numeric weights.**
- `hasHistory = successCount + failureCount + timeoutCount > 0` (`ServerHistoryTypes.kt`), OD-6 neutral.
- Exploration: `runSequence.incrementAndGet()` (`:2112`) and `historyToken(runToken, server, protocol)`
  (`:2237`) give a run identity; **no attempted-this-run set, no exploration selector.**
- VPN-first routing, 8 s timeout, one-candidate-per-IPv4, reachability gate — all present and untouched.

## 4. Missing Decisions (require the operator)

1. **Numeric parameters.** The exact weights (`w1/w2/w3`, penalties `p1/p2`), recency **half-life** (τ), and
   **Laplace** smoothing (α) are **not** in the source or spec. Per the brief, I will not invent them.
   - **Option (a):** keep the existing **tier-ordinal comparator** as the final design (no numeric rewrite) and
     implement **only the one-exploration mechanism**.
   - **Option (b):** provide the exact numeric values for a new `AutoConnectScorer`.
2. **Exploration policy.** The earlier approved default is **Option A** (exploration candidate placed second,
   attempted only if the first pick fails). It needs an "attempted-this-run" set keyed by `runToken`. Confirm
   A (vs B: every K-th run first, cost ≤ 2×8 s).

## 5. Recommendation

**Option (a)** — respect the existing "no invented numbers" design and the brief's prohibition: keep the
tier-ordinal ranking and add only the **exactly-one exploration** mechanism (Option A, keyed by `runToken`,
respecting IPv4 dedup / reachability / VPN-first / 8 s timeout). This is small, testable, and needs no invented
constants. If the operator wants a true numeric score, supply the weights/half-life/Laplace and I will implement
that exact formula.

## 6. Change-Control Confirmation

No commit · No tag · No push · No release · No Gradle/dependency change · No cache copy/deletion · No Security
Center bypass · No app-data/manual-DB change · No unrelated cleanup.

---

## Checkpoint 11 — Option A implemented (exactly-one exploration)

- **Tier-ordinal ranking unchanged** — no weights/half-life/Laplace/threshold added.
- **New pure helper** `List<T>.withExplorationSecond(startAt, key, eligible)` in `AutoModeHistory.kt`: moves **at most one** eligible candidate to `startAt+1` (second), never the first pick, returns the list unchanged when none is eligible (no fabricated candidate).
- **Wired in `runAutoMode`:** after the ranked list + `startAt` are known, `servers = servers.withExplorationSecond(...)` with `eligible = historyTierOf(view, runNetworkType) >= INCONCLUSIVE` (a previously-failed/uncertain server, never a proven current-network success). The existing per-run `attempted` set (`AutoModeController.kt:2107/2158`) guarantees the candidate is attempted at most once and never repeated across loop iterations. Because it is placed **second**, it is attempted only if the first pick fails (Option A).
- **Preserved:** VPN-first routing, protocol eligibility, reachability gate, one-candidate-per-IPv4, 8 s timeout, Next Server resume (`startAt`), tier-ordinal order of the rest.
- **Tests [AUTOMATED TEST]:** `AutoModeHistoryOrderingTest` → **24 tests, 0 failures, 0 errors** (+4: move-one, no-eligible-unchanged, first-pick-never-chosen, resume-from-server). Build: `:app:testProDebugUnitTest` on `automode.*` + `ServerListUpdaterTest` + `QualityRejectWindowTest` → **BUILD SUCCESSFUL (2m49s)**, no regression.
- **Change control:** no commit/push; no Gradle/dependency/cache/DB/device change.
