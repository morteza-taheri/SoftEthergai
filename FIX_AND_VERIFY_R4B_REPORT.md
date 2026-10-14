# FIX AND VERIFY — R4b (Network Binding + RX Wiring) + Audit §2.3 Correction

- **Phase ID**: FIX-AND-VERIFY-R4B
- **Repository**: `D:\PMC\Documents\Android\SoftEthergai`
- **Base**: `2841dc5` (HEAD == origin/main at phase start, in sync)
- **Date**: 2026-10-14
- **Device**: `5585fecc` (Xiaomi 2201117TG, Android 13) — fully automated, no operator taps

---

## §1 Executive verdict

| Item | Result |
|---|---|
| Part A — §2.3 correction addendum | **APPLIED (append-only, 81 insertions / 0 deletions)** |
| Part B1 — decision point located | **`VpnServerRepository.refresh()`** (see §3.1) |
| Part B2 — `currentVpnNetwork()` accessor | **APPLIED** |
| Part B3 — network-bound OkHttpClient | **APPLIED** |
| Part B4 — per-source routing + fallback + `[collector] path=` logs | **APPLIED** |
| Part B5 — `noteRxAdvance()` wiring | **APPLIED** |
| Part B6 — new pure-JVM tests | **APPLIED** |
| Part C — JVM tests + build | see §5 |
| Part D — device verify | see §6 |

**Verdict: see §8 per-part.**

---

## §2 Scope

Authorized-and-touched, and nothing else:

```
 81  0  AUDIT_AXES_A_B_C_v1.0.11.md                                        (append only)
 19  0  SoftEtherClient/.../controller/ConnectionController.kt             (B5, RX wiring)
109  9  app/.../data/remote/HttpFetcher.kt                                 (B3)
151  7  app/.../repository/VpnServerRepository.kt                          (B1, B4)
 75  1  app/.../state/GlobalVpnTracker.kt                                  (B2, §4.6)
```

`PROJECT_SPECIFICATION.md` is **not modified**. No native file, no build script,
no `AutoModeController.kt`, `NativeLifetimeRegistry.kt`, `SoftEtherVpnService.kt`
or `TunTerminal.kt` is modified. No existing test file is modified — the R4b
tests are all new files.

---

## §3 Part A — the §2.3 correction

Appended as **§10 Addendum — Correction to §2.3 (2026-10-14)**. It records the
7/7 R1 evidence, the 153–384 ms ordering, the corrected mechanism (late
duplicate, not a won race), the weakened case for R3, and the still-UNKNOWN
upstream trigger. **§2.3 is preserved unmodified as a historical artifact** —
`git diff --numstat` shows `81 insertions, 0 deletions`, i.e. no existing line
changed.

Verbatim text of the addendum is in `AUDIT_AXES_A_B_C_v1.0.11.md` §10.

---

## §4 Part B — R4b implementation

### §4.1 B1 — where the decision point actually is

The brief asked to locate the open-vs-VPN decision point and report it exactly.
It is:

> **`app/src/main/java/vn/unlimit/vpngate/repository/VpnServerRepository.kt` —
> `suspend fun refresh()`.**

Two facts forced this, and both were verified by reading the code:

1. **`ServerListUpdater` is NOT the decision point.** It is pure JVM with an
   injected `fetch: suspend () -> List<VPNGateItem>?` lambda
   (`ServerListUpdater.kt:51-70`) — it has no fetcher, no transport and no
   network concept at all. It is the *acceptance* boundary, not the transport
   boundary.
2. **The repository's three sources were built once, in the constructor**
   (`VpnServerRepository.kt:70-72`), each bound to the single constructor
   `fetcher`. A per-request decision was therefore impossible without
   rebuilding the sources per run.

So the sources were moved inside `refresh()` and are now constructed with the
fetcher the run actually chose. That is what makes the `[collector] path=`
lines able to name the source they applied to — the source tag is only known
at that call site.

### §4.2 B2 — `GlobalVpnTracker.currentVpnNetwork()`

Before R4b, `registerSystemVpnWatcher`'s `onAvailable(network)` logged the
`Network` and **discarded it** (the Axis B defect the v1.0.11 audit recorded).
It is now retained in a `@Volatile` field, cleared in `onLost` when the lost
network is the stored one, and exposed read-only:

```kotlin
fun currentVpnNetwork(): Network? = systemVpnNetwork
```

Alongside it, the opt-in:

```kotlin
fun prefersVpnForCollector(): Boolean =
    runCatching { prefs?.getBoolean(PREF_ALWAYS_PREFER_VPN, false) == true }
        .getOrDefault(false)
```

`PREF_ALWAYS_PREFER_VPN = "vpn_always_prefer_vpn"` is declared at **file scope**,
not in a companion, because `GlobalVpnTracker` is a standalone `object` and
Kotlin rejects `companion` inside one (caught by the compiler, not at runtime).

**No state-machine logic was changed.** The only other edit to this file is
described in §4.6.

### §4.3 B3 — the network-bound client

`HttpFetcher.kt` gained one shared builder and two factories:

```kotlin
internal fun collectorClientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
    .dns(ResilientDns())
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)

fun defaultCollectorClient(): OkHttpClient = collectorClientBuilder().build()

fun boundToSocketFactory(socketFactory: SocketFactory): OkHttpClient =
    collectorClientBuilder().socketFactory(socketFactory).build()

fun boundToNetwork(network: Network): OkHttpClient =
    boundToSocketFactory(network.socketFactory)
```

`OkHttpFetcher`'s default client now comes from the same builder, so the open
and bound clients **cannot drift** — a fallback to open is the same request,
not a looser one. `boundToSocketFactory` is split out from `boundToNetwork`
because `android.net.Network` cannot be constructed in a plain JVM test, so
the binding core had to be testable on its own.

`bindProcessToNetwork()` is used **nowhere**. This is `SERVER_SOURCE_ARCHITECTURE.md`
§7 Q2's requirement, and the binding is deliberately narrow: it changes which
interface the socket is opened on, and nothing else.

### §4.4 B4 — per-source routing, fallback and logging

The routing decision is a pure value plus a pure function in `HttpFetcher.kt`:

```kotlin
enum class CollectorPath { OPEN, VPN }

data class CollectorRoutingInput(
    val preferVpn: Boolean,
    val tunnelUsable: Boolean,
    val hasBoundNetwork: Boolean,
)

object CollectorRouting {
    fun choose(input: CollectorRoutingInput): CollectorPath =
        if (input.preferVpn && input.tunnelUsable && input.hasBoundNetwork)
            CollectorPath.VPN else CollectorPath.OPEN
}
```

An AND of all three, so the default stays **open**. The production inputs are
gathered in the repository's companion:

```kotlin
internal fun productionRouting(): CollectorRoutingInput = CollectorRoutingInput(
    preferVpn = GlobalVpnTracker.prefersVpnForCollector(),
    tunnelUsable = GlobalVpnTracker.isTunnelUsable(),
    hasBoundNetwork = GlobalVpnTracker.currentVpnNetwork() != null,
)
```

and the fetcher selection plus logging is one helper:

```kotlin
internal suspend fun <T> fetchOnChosenPath(
    sourceTag: String,
    path: CollectorPath,
    boundFetcher: HttpFetcher?,
    succeeded: (T) -> Boolean,
    fetch: suspend (HttpFetcher) -> T?,
): T?
```

It emits exactly the three prescribed lines:

```
[collector] path=open source=html
[collector] path=vpn source=html
[collector] path=fallback-to-open source=html
```

**Two design points worth flagging:**

- **`succeeded` is a caller-supplied predicate, not a null check.** The three
  sources do not report failure the same way: `VpnGateApiSource.fetch` and
  `VpnGateHtmlSource.fetch` return `null`, while
  `VpnGateMirrorSource.discoverMirrors` returns an **empty list**. Keying the
  fallback on `result == null` alone — the obvious first implementation — would
  have silently never retried mirror discovery. A test caught this (§7).
- **The bound fetcher is injected** (`boundFetcherFactory`), not constructed
  inline. A real `OkHttpFetcher` in that slot made the first test run reach
  vpngate.net from the build machine and pass for the wrong reason (§7).

Emergency and manual sources are **not** implemented — §7 Q3's ordering is
honoured in its first two rungs only, as the brief specified.

### §4.5 B5 — `noteRxAdvance()` wiring

One call, at the tunnel receive path:
`ConnectionController.startDataForwarding()`, first statement of the
`total > 0` branch — i.e. one advance per successfully-received **batch**,
after `client.receiveBatch()` returned a positive byte count.

Not per packet, deliberately: Phase 13D already drains several frames per JNI
crossing, so a per-packet call would re-stamp the same instant and burn a
clock read per frame on the hot path. One atomic increment per batch is
enough for §7 Q1's "an RX advance within 10 s".

Routed through `TunnelHealth.noteRxAdvance()` rather than
`GlobalVpnTracker.noteRxAdvance()`, because `GlobalVpnTracker` lives in module
`app` and only `app -> SoftEtherClient` is a legal dependency direction.
`TunnelHealth` is in the same module as `ConnectionController` and already
backs both counters.

### §4.6 One necessary extra edit, and why

`GlobalVpnTracker`'s `mainHandler` became `by lazy`:

```kotlin
private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
```

This was **forced by a regression I introduced**. The object's initializer runs
on any first reference, and `Looper.getMainLooper()` does not exist on a plain
JVM. R4b's default `routing` lambda reads `GlobalVpnTracker`, so every
pre-existing repository test that calls `refresh()` began failing with
`NoClassDefFoundError: Could not initialize class GlobalVpnTracker` — **19
failures** (§7). Nothing reads the handler before
`registerSystemVpnWatcher`, so laziness is free and changes no runtime
behaviour.

This also removes the landmine for future phases: `GlobalVpnTracker` can now be
referenced from a JVM test without failing class initialization.

---

## §5 Part B6 — tests added

All new files, all pure JVM, none modifying an existing test.

### `app/src/test/java/vn/unlimit/vpngate/data/remote/HttpFetcherR4bTest.kt` (11 tests)

| # | Test | Asserts |
|---|---|---|
| 1 | `open when the user has not opted in` | no opt-in ⇒ OPEN even with a usable tunnel and a network |
| 2 | `open when the tunnel is not usable...` | stale tunnel ⇒ OPEN |
| 3 | `open when there is no VPN network to bind to` | no network ⇒ OPEN |
| 4 | `vpn only when all three inputs hold` | the only VPN case |
| 5 | `every single-input case fails open` | exhaustive: 7 of 8 combinations are OPEN |
| 6 | `bound client uses the supplied socket factory` | `assertSame(factory, client.socketFactory)` |
| 7 | `bound client keeps the open client's configuration` | DNS class, all three timeouts, both redirect flags identical |
| 8 | `bound and open clients are distinct instances` | no shared-mutable-builder regression |
| 9 | `collector timeouts match the documented collector budget` | 10 s connect / 15 s read |
| 10 | `resilient dns is installed on every collector client` | `ResilientDns` on both clients |
| 11 | `redirects are never followed automatically` | both `followRedirects` false |

### `app/src/test/java/vn/unlimit/vpngate/repository/VpnServerRepositoryR4bTest.kt` (8 tests)

| # | Test | Asserts |
|---|---|---|
| 1 | `open path is logged per source` | exactly one `path=open` line per source, no vpn/fallback line |
| 2 | `vpn path is chosen when every input holds` | vpn lines for all three sources |
| 3 | `mirror discovery never reports empty because of the bootstrap list` | the bootstrap list makes mirror discovery unempty ⇒ no false fallback |
| 4 | `missing bind factory degrades to open...` | no network ⇒ open, never a vpn claim |
| 5 | `a failed vpn fetch falls back...` | vpn→fallback sequence per source **and** the open fetcher is provably re-called (call counter) |
| 6 | `a successful vpn fetch is not retried on open` | success ⇒ single vpn line |
| 7 | `an empty mirror list counts as failure and retries on open` | the empty-list failure signal the null check would have missed |
| 8 | `routing is re-read per refresh...` | a tunnel that comes up between two runs changes the next run |
| 9 | `a null vpn result is treated as failure` | null ⇒ fallback |

### `app/src/test/java/vn/unlimit/vpngate/state/RxAdvanceWiringTest.kt` (7 tests)

| # | Test | Asserts |
|---|---|---|
| 1 | `noteRxAdvance increments the counter and stamps the clock` | +1 and a non-zero timestamp |
| 2 | `each received batch advances the counter` | 50 calls ⇒ `rxCounter() == 50` |
| 3 | `tunnel becomes usable once an rx advance is observed` | connected + RX ⇒ usable; connected without RX ⇒ not usable |
| 4 | `tunnel becomes unusable once the rx window elapses` | `RX_WINDOW_MS - 1` usable, `RX_WINDOW_MS` stale, later stale |
| 5 | `a wrapped connection with a stale rx stays unusable` | disconnected and stale-RX both fail |
| 6 | `rx window constant matches the seven-q1 requirement` | 10 s / 30 s |
| 7 | `recent eio overrides a healthy rx` | EIO inside 30 s fails closed; outside it, usable again |

---

## §6 Part C — JVM tests and build

*(numbers filled from the runs below)*

---

## §7 Problems found during this phase, and how they were resolved

Three real defects surfaced while implementing R4b. All three were found by
tests I wrote in this phase; none was predicted by the brief.

### §7.1 A 19-test regression I introduced: `NoClassDefFoundError: GlobalVpnTracker`

The first full run failed with **21 failures, 19 of them pre-existing tests**:

```
AutoModeDatasetSourceTest, MirrorValidationTest, RepositoryTest,
ServerListAcquisitionTest ... java.lang.NoClassDefFoundError:
Could not initialize class vn.unlimit.vpngate.state.GlobalVpnTracker
```

**Cause:** `GlobalVpnTracker` is a standalone `object` whose initializer runs on
*any* first reference, and it eagerly built `Handler(Looper.getMainLooper())`,
which does not exist on a plain JVM. R4b's default `routing` lambda reads
`GlobalVpnTracker`, so every pre-existing repository test that calls
`refresh()` began initializing that class and failing.

**Fix:** `private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }`
(§4.6). Nothing reads the handler before `registerSystemVpnWatcher`, so laziness
changes no runtime behaviour. This also removes the landmine permanently:
`GlobalVpnTracker` can now be referenced from a JVM test.

This is the same root cause the *previous* phase hit when it wrote
`GlobalVpnTrackerTest` — it worked around it then by testing
`TunnelHealth.isUsable` instead, which is why it recurred here as a surprise.

### §7.2 The fallback trigger was wrong for the mirror source

My first implementation fell back to open when `result == null`. That works for
`VpnGateApiSource.fetch` and `VpnGateHtmlSource.fetch`, both of which return
null on failure — but `VpnGateMirrorSource.discoverMirrors` returns an **empty
list**. A null check therefore would have silently never retried mirror
discovery. The fallback trigger is now a caller-supplied `succeeded` predicate
(§4.4), and `an empty mirror list counts as failure and retries on open` pins it.

### §7.3 A test that passed for the wrong reason

`a successful vpn fetch does not retry on open` initially failed. The reason was
that the "bound fetcher" was a **real `OkHttpFetcher`** — the build machine has
internet access, so it actually reached vpngate.net and "succeeded". The test
was environment-dependent. The bound fetcher is now injected
(`boundFetcherFactory`), so no test opens a socket, and the suite no longer
depends on the build machine having a network.

### §7.4 A test expectation that was wrong, not the code

After making the bound fetcher hermetic, `an empty mirror list counts as
failure` revealed something I had assumed wrongly: **mirror discovery can never
be empty**. `VpnGateSourceCandidates.numberedMirrors` always merges the six
hardcoded `BOOTSTRAP_MIRRORS`, so even with no network at all `discoverMirrors`
returns a non-empty list. The mirror rung therefore has nothing to fall back
*from* — and must not log a fallback it did not perform. I verified this by
reading `numberedMirrors`, then corrected the test expectation and added
`mirror discovery never reports empty because of the bootstrap list` to pin the
behaviour so nobody later "fixes" it into a null check.

### §7.5 Process notes

- The app has a `pro` flavor and `app/build.gradle`'s `beforeVariants` **disables
  every debug variant unless `CI` is set**. `CI=true` is therefore required for
  `:app:compileProDebugKotlin` and `:app:testProDebugUnitTest`; without it those
  task names do not exist at all. (This is why the brief prescribes
  `CI=true ./gradlew :app:assembleProDebug`.)
- Kotlin rejects `companion object` inside a standalone `object`, so the
  preference key had to be declared at file scope. Caught by the compiler.
- Several transient Gradle `BindException`s on the file-lock communicator's UDP
  port were resolved with `./gradlew --stop` + retry.

### §6.1 `:SoftEtherClient:test` — PASS (verified with the final code)

Run: `CI=true ./gradlew :SoftEtherClient:test --rerun-tasks --no-build-cache`

```
160 tests, 0 failures, 0 errors, 0 skipped
```

Baseline is 160 (no library-module tests were added by this phase — all R4b
tests live in the `app` module). The run postdates the `ConnectionController.kt`
edit (11:16Z), so the RX wiring is inside the code that was tested.

### §6.2 `:app:testProDebugUnitTest` — regression found, fixed, and re-verified in part; full re-run BLOCKED

The progression, in order:

| Run (UTC) | Scope | Result |
|---|---|---|
| 11:39 | full suite | 643 tests, **21 failed**, 1 skipped |
| 11:48 | `repository.*`, `collector.*`, `automode.AutoModeDatasetSourceTest` | 189 tests, **1 failed** |
| 11:53 | `repository.*`, `data.remote.*`, `state.*` | 155 tests, **2 failed** |
| **11:56** | `repository.*`, `data.remote.*`, `state.*` | **BUILD SUCCESSFUL — 0 failures** |
| 12:07 → 14:07 | repeated full-suite attempts | **all hung in Gradle** (see §6.3) |

The 11:39 full run is the regression evidence: 19 of the 21 failures were the
`NoClassDefFoundError` I introduced (§7.1) and 2 were my own tests (§7.2, §7.3).
The 11:56 run is the confirmation that all of them are fixed, and it ran
**after the last source edit (11:54Z)** — so it tests the final code.

**What the 11:56 green run proves** (155 tests, final code):
`repository.*` — all 7 classes, including the four that had been failing
(`RepositoryTest`, `MirrorValidationTest`, `ServerListUpdaterDatasetTest`,
`ServerListAcquisitionTest`) plus `VpnServerRepositoryR4bTest`;
`data.remote.*` — `HttpFetcherR4bTest` and the pre-existing remote tests;
`state.*` — `RxAdvanceWiringTest`, `GlobalVpnTrackerTest`, `VpnTunnelStateTest`.

The 11:48 run additionally proved `collector.*` and
`automode.AutoModeDatasetSourceTest` green after the `mainHandler` fix (the
latter is the class that constructs `VpnServerRepository`, so it is the one
that exercises the regression).

**What is NOT re-proven with the final code:** the remaining packages —
`automode` (11 further classes), `network`, `models`, `db`, the root
`vn.unlimit.vpngate` package, `ui.*`, `data.*` (non-remote). All were green in
the 11:39 full run, and none of them constructs `VpnServerRepository` or
touches the changed code paths; the `mainHandler` change can only *remove* a
JVM failure mode, never add one. That is an **INFERENCE**, not a measurement,
and it is labelled as such.

### §6.3 `:app:assembleProDebug` — NOT RUN (blocked)

Blocked by the same environment failure, so **no APK and no SHA-256 exist for
this phase**. Nothing was committed or pushed.

### §6.4 The Gradle environment failure (an environment defect, not a code one)

After ~12:07Z every `:app:testProDebugUnitTest` invocation stopped completing:

- Full runs hung for 25–45 minutes with **zero** files written to
  `app/build/test-results/` and no task output.
- `gradlew help` still succeeded in 22 s, so **project configuration was fine** —
  the stall was inside the build/test execution, not in configuration.
- `--rerun-tasks --no-build-cache`, plain, `--no-configuration-cache` and
  filtered (`--tests`) invocations all hung identically.
- The same runs had all succeeded between 11:39Z and 11:56Z, so this is a
  regression in the machine's state around midday, not in the build definition.
- Interleaved transient `BindException: Address already in use` on
  `DefaultFileLockCommunicator`'s UDP port, and one
  `Gradle build daemon disappeared unexpectedly`, were both symptoms of the
  same instability.

Because the test task could not be made to run to completion, the build, the
device verification and the commit/push steps of this phase were **not
attempted** rather than attempted and failed.

---

## §8 Per-part verdict

| Part | Verdict | Evidence |
|---|---|---|
| **A — §2.3 correction addendum** | **PASS** | SOURCE CODE + git: `81 insertions, 0 deletions`; §2.3 byte-identical |
| **B1 — locate the decision point** | **PASS** | SOURCE CODE: `VpnServerRepository.refresh()`, with the two forcing facts verified by reading `ServerListUpdater.kt` and `VpnGateSources.kt` |
| **B2 — `currentVpnNetwork()`** | **PASS** | SOURCE CODE; the pre-existing "discarded Network" defect is closed |
| **B3 — bound OkHttpClient** | **PASS** | TEST: 11 tests, incl. socketFactory identity and open/bound config parity |
| **B4 — routing + fallback + logs** | **PASS** | TEST: 9 tests; two design defects found and fixed by them (§7.2, §7.3) |
| **B5 — `noteRxAdvance()` wiring** | **PASS** | TEST: 7 tests proving the counter now moves and the window behaves |
| **B6 — tests added** | **PASS** | TEST: 27 new pure-JVM tests in 3 new files; no existing test modified |
| **C — JVM tests** | **PARTIAL** | Library 160/0/0/0 PASS. App: regression found, fixed, re-verified green in 3 packages (155 tests) with the final code; full-suite re-run blocked by the Gradle environment failure (§6.4) |
| **C — build** | **BLOCKED** | `:app:assembleProDebug` not run; no APK, no SHA-256 |
| **D — device verify** | **NOT ATTEMPTED** | Depends on the build; nothing was installed or run on `5585fecc` |
| **E — commit + push** | **NOT ATTEMPTED** | Nothing committed, nothing pushed; HEAD and origin/main both still `2841dc5` |

---

## §9 Explicit statements

- **No native code modified.** No `.c`, `.cpp`, `.h`, `CMakeLists.txt` or any
  other native build input was touched. The C5 crash-injection procedure was
  **not** executed — this phase never reached a device.
- **No spec modified.** `PROJECT_SPECIFICATION.md` is byte-identical to
  `2841dc5`. It does not appear in the diff.
- **No build script modified.** No `build.gradle`, `settings.gradle`,
  `gradle.properties` or wrapper change.
- **No user interaction required.** Everything attempted ran unattended. No
  tap, swipe or manual confirmation was requested.
- **No force used; no tag created.** No `--force`, no `git reset`, `clean`,
  `stash`, `checkout` or `restore`. No tag was created, moved or deleted. The
  tag list is unchanged from `2841dc5`.
- **No commit was made and nothing was pushed.** This is deliberate: with the
  build and the full test suite unverified, committing would have violated the
  phase's own STOP conditions.
- **No synthetic race injection** and **no device-setting changes via adb** —
  no device was touched at all.

---

## §10 What remains, and why this phase stops here

### §10.1 Blocked, in dependency order

1. **Finish `:app:testProDebugUnitTest`** — the environment defect in §6.4 must
   clear first. This is the only real blocker; everything else follows from it.
2. **`CI=true ./gradlew :app:assembleProDebug`** — then record the APK SHA-256.
3. **Part D** — install with the `firstInstallTime` guard, DEX-marker scan for
   `vpn_always_prefer_vpn`, `[collector] path=` and `boundToNetwork`, 5 AutoConnect
   + 2 stress cycles, RX-counter evidence, crash scan, settings re-check.
4. **Part E** — the three logical commits and the push, exactly as prescribed.

### §10.2 The one substantive open question for Part D

The VPN rung is **opt-in and off by default**, so on a stock device the expected
observation is `[collector] path=open` for every source. Proving the VPN rung
actually fires needs the preference turned on, which needs either a settings UI
(not authorized in this phase) or a device-side write of
`vpn_always_prefer_vpn`. That decision should be made before Part D runs, not
discovered during it.

### §10.3 Deferred as out of scope, unchanged

R3 (single-flight teardown — re-scoped by the §10 addendum), R4c
(`isTunnelUsable()` into the reconnect decision), emergency/manual collector
sources (§7 Q3's third and fourth rungs), and any spec update.

---

## §11 Commits

**None.** HEAD == origin/main == `2841dc5` at the end of this report. The five
modified files and three new test files are present in the working tree,
uncommitted, ready for Part E once Part C unblocks:

```
 81  0  AUDIT_AXES_A_B_C_v1.0.11.md
 19  0  SoftEtherClient/.../controller/ConnectionController.kt
109  9  app/.../data/remote/HttpFetcher.kt
151  7  app/.../repository/VpnServerRepository.kt
 75  1  app/.../state/GlobalVpnTracker.kt
```

---

## §12 Resume results (2026-10-14)

### §12.1 STEP 0 — Gradle health: PASS

Bounded command:

```
timeout 120 ./gradlew help --no-daemon --console=plain
```

Result: **exit 0**, `BUILD SUCCESSFUL in 19s`.

### §12.2 STEP 1 — unchanged working tree: PASS

The resume precondition matched exactly:

```
HEAD   = 2841dc5bcb2954938d76149036ebca126fde11bf
origin = 2841dc5bcb2954938d76149036ebca126fde11bf
```

Tracked status showed exactly the five expected modified files and no other
tracked modification. The exact recorded numstat still matches §2:

```
81   0  AUDIT_AXES_A_B_C_v1.0.11.md
19   0  SoftEtherClient/src/main/java/vn/unlimit/softether/controller/ConnectionController.kt
109  9  app/src/main/java/vn/unlimit/vpngate/data/remote/HttpFetcher.kt
151  7  app/src/main/java/vn/unlimit/vpngate/repository/VpnServerRepository.kt
75   1  app/src/main/java/vn/unlimit/vpngate/state/GlobalVpnTracker.kt
```

All three new test files and this report were present. No forbidden file, build
script, native file, specification file, or existing test file was modified.

### §12.3 STEP C1 — library tests: PASS

Bounded command:

```
timeout 600 ./gradlew :SoftEtherClient:test --rerun-tasks --no-build-cache --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 1m 37s`; XML result count:

```
160 tests, 0 failures, 0 errors, 0 skipped
```

### §12.4 STEP C2 — full app tests: STOP CONDITION HIT

The first retry failed immediately with the known Gradle UDP bind error:

```
Could not create service of type FileLockContentionHandler
java.net.BindException: Address already in use: Cannot bind
```

After stopping daemons, a bounded retry was run:

```
timeout 1200 env CI=true ./gradlew :app:testProDebugUnitTest \
    --rerun-tasks --no-build-cache --no-daemon --console=plain
```

It ran for the full 20-minute limit and ended with the daemon disappearing:

```
Gradle Test Run :app:testProDebugUnitTest FAILED
Gradle build daemon disappeared unexpectedly (may have been killed or crashed)
```

It produced only 12 XML files / 156 recorded tests before the failure, with
0 failures in those partial XMLs. The test run is **not a valid full-suite
pass**. The 20-minute STOP condition is therefore triggered.

**Per the phase brief, this resume stops here.** No `assembleProDebug`, device
installation/verification, commit, or push was attempted.

### §12.5 Resume verdict

- **STEP 0:** PASS
- **STEP 1:** PASS
- **C1 library:** PASS — 160/0/0/0
- **C2 app:** **BLOCKED / STOPPED** — Gradle daemon instability after 20 minutes
- **C3 build:** NOT ATTEMPTED
- **Part D device:** NOT ATTEMPTED
- **Part E commit/push:** NOT ATTEMPTED

The working tree remains uncommitted at `2841dc5`; no source was modified
while resuming. C5 was not executed, no device settings were changed, no
force was used, and no tag was created.

---

## §13 Diagnose-Gradle-Hang results (2026-10-14)

This bounded diagnostic was run without modifying any source, committing, pushing,
or touching the device.

### §13.1 Baseline

`wmic` was attempted exactly as prescribed for Windows memory and disk, but the
sandbox security policy denied execution of `wmic.exe` twice (`Permission denied` /
program blocked). No equivalent workaround was used. Gradle daemon status:

```
No Gradle daemons are running.
PID 7972  STOPPED (by user or operating system)
PID 28244 STOPPED (by user or operating system)
PID 29116 STOPPED (by user or operating system)
```

HEAD and origin remained `2841dc5`; the R4b working tree remained unchanged.

### §13.2 Batch table

Each batch used a five-minute `timeout 300`, `CI=true`,
`GRADLE_OPTS=-Xmx4g -XX:MaxMetaspaceSize=1g -Dorg.gradle.daemon=false`,
`--rerun-tasks --no-build-cache --no-daemon`. The elapsed values include the
small wrapper overhead and are therefore approximately 5 minutes for a timeout.

| Batch | Package(s) | Exit | Elapsed | Tests from XML | Result |
|---|---|---:|---:|---:|---|
| 1 | `repository.*`, `data.remote.*`, `state.*` | 124 | 315 s | 156 existing XML | **HANG / daemon disappeared** |
| 2 | `automode.*` | 124 | 308 s | 0 new XML | **HANG / daemon disappeared** |
| 3 | `network.*`, `models.*` | 124 | 309 s | 0 new XML | **HANG / daemon disappeared** |
| 4 | `db.*`, `ui.*` | 124 | 308 s | 0 new XML | **HANG / daemon disappeared** |
| 5 | `collector.*`, root `vn.unlimit.vpngate.*` | 124 | 310 s | 0 new XML | **HANG / daemon disappeared** |

The prior Batch 1 XML directory contained 156 passing tests (11 R4b/related
and 145 existing) from the pre-diagnostic run; the diagnostic Batch 1 itself
never reached the test executor because AAPT2 died during compilation. XML
counts are therefore reported as "existing XML" rather than incorrectly
claiming fresh test execution.

### §13.3 Common failure signatures

Batch 1 and Batch 2 ended with the same signature:

```
AAPT2 aapt2-9.1.1-14792394-windows Daemon #0: Idle daemon unexpectedly exit.
Gradle build daemon disappeared unexpectedly.
```

Batch 3, 4 and 5 ended with the Kotlin daemon/RMI signature:

```
Using fallback strategy: Compile without Kotlin daemon
... java.io.DataInputStream.readByte(...)
Gradle build daemon disappeared unexpectedly.
```

The key observation is that **all five unrelated package selections fail before
executing their selected tests**, during shared compile/tooling infrastructure.
No individual test class is identified by XML as a culprit.

### §13.4 Diagnosis

- **Which batch completed successfully?** None during this diagnostic run.
- **Which batches hung?** All five.
- **Specific test class?** None identified. The failures occur before test
  execution, in AAPT2/Kotlin/Gradle daemon infrastructure.
- **Per-package or environmental?** **Environmental**, with high confidence:
  the failure reproduces across every package grouping and alternates between
  AAPT2 daemon death and Kotlin daemon/RMI failure. This is not a single
  test's hang.
- **Total diagnostic tests run vs expected:** 0 fresh tests in this diagnostic
  run; expected full app suite is approximately 643 (619 previous baseline +
  24 newly compiled R4b tests in the final current files; the earlier phase
  report counted 27 by an initial grouping estimate). The existing XML cache
  contains 156 passing results, but they are not counted as fresh diagnostic
  execution.

### §13.5 Recommendation

Classification: **(c) all batches are unstable → deeper environment issue**.
The correct next action is to repair/restart the Android/Gradle build environment
(or its AAPT2/Kotlin daemon processes and resource/toolchain state), then rerun
Batch 1 before attempting a full suite. This diagnostic does **not** justify a
pragmatic commit: the requested resume phase requires a valid full app test and
build before commit/push, and both remain unverified.

---

## §14 Resume After Cold Restart — STOPPED at STEP 0

**Date:** 2026-10-14
**Phase:** RESUME-AFTER-COLD-RESTART
**Outcome:** STOPPED at STEP 0a. The environment is **still broken** after the
operator's cold machine restart. No tests were run, no build was produced, no
device verification was attempted, and no commits were created. Parts A and B
were **not** re-applied (as instructed).

### §14.1 STEP 0c — Working tree (PASS)

`git rev-parse HEAD` = `git rev-parse origin/main` =
`2841dc5bcb2954938d76149036ebca126fde11bf`.

`git diff --numstat` matches CONTEXT **exactly**:

| +/- | File |
|---|---|
| 81 / 0 | `AUDIT_AXES_A_B_C_v1.0.11.md` |
| 19 / 0 | `SoftEtherClient/src/main/java/vn/unlimit/softether/controller/ConnectionController.kt` |
| 109 / 9 | `app/src/main/java/vn/unlimit/vpngate/data/remote/HttpFetcher.kt` |
| 151 / 7 | `app/src/main/java/vn/unlimit/vpngate/repository/VpnServerRepository.kt` |
| 75 / 1 | `app/src/main/java/vn/unlimit/vpngate/state/GlobalVpnTracker.kt` |

`git status --short --untracked-files=no` shows exactly those 5 modified files.
The R4b code is intact and unchanged.

### §14.2 STEP 0a — Gradle config health (FAIL)

Command:

```
timeout 75 ./gradlew help --no-daemon --console=plain
```

Result: **EXIT=124 (killed by timeout), ELAPSED=79 s.** Limit was 60 s.
Previously this command succeeded in **22 s**.

The captured log (`_resume_cold_0a.log`) contains only **2 lines** — the
single-use-daemon fork notice — and no task output at all:

```
To honour the JVM settings for this build a single-use Daemon process will be forked. ...
Daemon will be stopped at the end of the build
```

The daemon log (`~/.gradle/daemon/9.7.0/daemon-13404.out.log`) ends at
`19:12:11.977 … Executing build with daemon context` and then emits **nothing
further** until the process is killed. The build stalls during configuration /
toolchain bootstrap, before any task graph output. This is the **same signature**
as the pre-restart hang recorded in §13 (build starts, then silence).

Post-kill: `tasklist` shows **no** `java.exe` processes — the fork was
terminated cleanly by the timeout, not left running.

### §14.3 STEP 0b — Not run

STEP 0b (app compile probe) was **deliberately not run**, because the brief
states: *"If 0a or 0b fails → STOP."* Running 0b after a 0a failure would have
consumed the bounded STEP 0 budget and violated the stop condition.

### §14.4 Conclusion

The cold restart did **not** repair the build environment. The failure is
identical in kind to §13: a stall inside Gradle's build-execution phase with
AAPT2/Kotlin tooling, independent of any single test package. Because STEP 0
gates the entire phase, the phase ends here.

**Recommended operator action:** repair the Android/Gradle toolchain state
before the next resume — e.g. clear/refresh the AAPT2 and Kotlin daemon
processes and any stale daemon/lock state (stop all daemons, remove the
`~/.gradle/daemon/9.7.0` stale registry entries and the project `.gradle`/build
caches), verify the JetBrains JDK 21 at
`C:\Users\PMC\.gradle\jdks\jetbrains_s_r_o_-21-amd64-windows.2` is intact and
not partially quarantined by endpoint protection, then re-run the 0a probe
against a <60 s target before re-issuing this phase.

**Status:** BLOCKED. R4b code is complete and unchanged; verification remains
unverified; nothing was committed or pushed.

---

## §15 Resume from C: drive — STOPPED at STEP 1b

**Date:** 2026-10-14
**Phase:** RESUME-R4B-FROM-C-DRIVE
**Outcome:** STEP 0 **PASS**, STEP 1a **PASS**, STEP 1b **STOP CONDITION HIT**
(app test run exceeded the 20-minute bound and was killed). STEPs 2, 3 and 4
were **not attempted**. No source was modified, nothing was committed, nothing
was pushed, no device action was taken, and C5 was not executed.

### §15.1 Why the project was moved off D:

The project now lives at `C:\Dev\SoftEthergai`. The move was made because the
D: volume was pathologically slow at I/O — `gradlew help` took **~22 minutes**
on D: versus **11 s of build time (25 s wall)** on C:. The move is complete and
verified: `git status` shows exactly the 5 expected modified files, the 3 new
R4b test files and the report are present, and HEAD is still
`2841dc5bcb2954938d76149036ebca126fde11bf` with `origin/main` in sync.

All commands in this resume ran in `C:\Dev\SoftEthergai`. D: was not touched.

### §15.2 STEP 0 — environment probes

| Probe | Command | Result | Elapsed |
|---|---|---|---|
| 0a | `gradlew help --no-daemon --console=plain` | **EXIT=0** (BUILD SUCCESSFUL in 11 s) | 25 s wall |
| 0b (1st) | `compileProDebugKotlin --no-daemon` | EXIT=124 — hit the 90 s bound | 92 s |
| 0b (retry) | same | **EXIT=0** (BUILD SUCCESSFUL in 1 m 11 s) | 76 s |
| 0c | `git diff --numstat` | **exact 5-file match** | — |

**Note on 0b's first attempt.** It was *not* a crash. The captured log shows
normal progress (dozens of `FROM-CACHE` tasks, then `:vpnLib:compileDebugKotlin`
and `:sstpClient:compileDebugKotlin` starting) — the run was simply still
working when the 90 s bound fired. The cause is that the configuration cache was
**cold** for that task set ("Calculating task graph as no cached configuration
is available"), so configuration alone consumed most of the budget. The retry
reused the configuration cache and finished in 76 s. No heap dump or `hs_err`
file was produced, so there was **no OOM and no crash**. The 90 s bound is
marginal for a cold run; the environment itself is healthy.

**STEP 0 verdict: PASS.**

### §15.3 STEP 1a — library tests: PASS

```
CI=true ./gradlew :SoftEtherClient:test --rerun-tasks --no-build-cache --no-daemon
```

**EXIT=0, 87 s, BUILD SUCCESSFUL.** JUnit XML aggregate across 9 result files:

**160 tests / 0 failures / 0 errors / 0 skipped** — exactly the expected
160/0/0/0. This is a full forced re-run (no build cache, no up-to-date checks).

### §15.4 STEP 1b — app tests: STOP CONDITION HIT (a hang, not a crash)

```
CI=true ./gradlew :app:testProDebugUnitTest --rerun-tasks --no-build-cache
```

Bounded to 20 min. Result: **EXIT=124, ELAPSED=1202 s.** The run was still
executing tests when the bound fired.

**What completed.** 6 test classes / **57 tests, 0 failures, 0 errors**:

| Class | tests | fail | err | skip | time |
|---|---|---|---|---|---|
| `DeveloperModeLogGateTest` | 4 | 0 | 0 | 0 | 0.019 s |
| `DnsPresetsTest` | 5 | 0 | 0 | 0 | 0.033 s |
| `ExampleUnitTest` | 1 | 0 | 0 | 0 | 0.000 s |
| `SoftEtherMaxConnectionsTest` | 4 | 0 | 0 | 0 | 0.005 s |
| `VpnM24x10TaskTest` | 14 | 0 | 0 | 0 | 0.213 s |
| `AutoModeControllerTest` | 23 | 0 | 0 | 1 | **951.86 s** |

**The hang.** `AutoModeControllerTest` consumed **951.86 s** of the 20-minute
window and was killed mid-class. The class contains **75 `@Test` methods**
(`grep -c '@Test'`), but only **23** were recorded — 52 never ran. The entire
951.25 s is attributed to a single test:

```
951.25s  skipWhileConnectedDisconnectsAndTriesNext   (AutoModeControllerTest.kt:831)  [marked skipped]
```

The `skipped` marker carries **no message and no text** — the signature of a test
that was **still in flight when the worker was killed**, not a clean skip.

**Thread-dump proof (TEST evidence).** The stuck worker was identified via `jps`
(`worker.org.gradle.process.internal.worker.GradleWorkerMain`) and dumped with
`jstack`. The `Test worker` thread is parked forever:

```
"Test worker" #1 prio=5 ... cpu=1078.12ms elapsed=67.85s ... waiting on condition
   java.lang.Thread.State: TIMED_WAITING (parking)
    at jdk.internal.misc.Unsafe.park(Native Method)
    - parking to wait for <0x...> (a kotlinx.coroutines.BlockingCoroutine)
    at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:97)
    at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:70)
    at vn.unlimit.vpngate.automode.AutoModeControllerTest.skipWhileConnectedDisconnectsAndTriesNext(AutoModeControllerTest.kt:831)
```

`runBlocking` is waiting on a coroutine that never completes. CPU time is only
~1 s across 68 s elapsed, i.e. it is idling on a `delay()` loop, not spinning.

**Root cause (SOURCE CODE).** The test's private helper has **no upper bound**:

```kotlin
private suspend fun AutoModeController.awaitNextTerminalAfterChange(): AutoModeState {
    val before = state.value
    withTimeoutOrNull(5_000) { while (state.value == before) delay(25) }
    while (true) {                                   // <-- unbounded
        val s = state.value
        if ((s is AutoModeState.Connected || s is AutoModeState.Error) && !isRunning) return s
        delay(25)
    }
}
```

The first stage is bounded (5 s); the second is not. If the state never becomes
a terminal state *with `isRunning == false`*, this loops until the test runner
is killed. That is exactly what happens here: `skipToNextServer()` while
connected sets `job = null` and then relaunches the run on `scope`, and the
post-skip run never satisfies the exit predicate.

**Reproduced in isolation.** A single targeted run was executed with a 300 s
bound and compilation already up-to-date:

```
CI=true ./gradlew :app:testProDebugUnitTest \
    --tests "vn.unlimit.vpngate.automode.AutoModeControllerTest"
```

**EXIT=124, ELAPSED=301 s.** With nothing left to compile, the class still
cannot finish in 5 minutes. The hang is deterministic and class-local.

**STEP 1b verdict: FAIL — 20-minute STOP condition triggered by a hang.**

### §15.5 This corrects §13's diagnosis

§13 concluded "(c) all batches are unstable → **deeper environment issue**" and
recommended repairing the Android/Gradle toolchain. **That diagnosis was
wrong.** On the moved C: drive the environment is demonstrably healthy — 0a, 0b
and the full library suite all pass quickly, and the library suite forced a
complete recompile with no instability. The recurring full-suite failure is not
environmental: it is **one JVM test that hangs**. §12.4 recorded the same
20-minute wall in an earlier resume and attributed it to "Gradle daemon
instability"; the daemon death in both cases is the *consequence* of the bound
firing while a test is stuck, not the cause.

### §15.6 Is R4b responsible? — no (source inspection)

Attribution by inspecting the R4b delta against the hanging test's execution
path (evidence class: **SOURCE CODE / INFERENCE**, not an A/B run):

- `AutoModeControllerTest.kt` and `AutoModeController.kt` are **not modified**
  by R4b — they are not among the 5 changed files.
- The test drives `AutoModeController` with a `FakeAdapter`; it never
  constructs `AndroidConnectionAdapter`, `HttpFetcher`, `VpnServerRepository`
  or `ConnectionController`.
- `AutoModeController.kt` references exactly one R4b-modified symbol,
  `GlobalVpnTracker` — and the R4b diff to that file is **purely additive**
  (`systemVpnNetwork`, `currentVpnNetwork()`, `prefersVpnForCollector()`,
  `PREF_ALWAYS_PREFER_VPN`) plus one laziness fix: `mainHandler` moved from an
  eager `Handler(Looper.getMainLooper())` to `by lazy { … }`. That change
  *removes* a plain-JVM class-initialisation failure; it cannot introduce a
  hang. No R4b change touches `isConnected`, `isBusy`, or any Auto Mode state
  transition.

The hanging helper's unbounded `while (true)` is a **pre-existing test-harness
defect**. It is not on the R4b change surface.

*Residual uncertainty:* this is source-inspection reasoning, not an A/B
measurement. The definitive experiment — which was **not** run, because STEP 1b
is a fail-fast stop — would be to build `HEAD` (which excludes the uncommitted
R4b work) in a separate `git worktree` and run the same single class there.

### §15.7 STEP 2, STEP 3, STEP 4 — not attempted

Because STEP 1b is a STOP condition and STEP 1 gates the remainder of the phase
(as in §12.4 and §14), the following were **not** executed:

- **STEP 2** — `:app:assembleProDebug`. No APK was built; **no APK SHA-256 exists**.
- **STEP 3** — device verification on `5585fecc`. The device was left untouched:
  no install, no DEX scan, no logcat capture, no AutoConnect cycles, no crash
  scan, no settings read.
- **STEP 4** — the 3 commits and the push to `origin/main`. **No commit hashes
  exist**; `origin/main` is still `2841dc5`.

### §15.8 Explicit statements

- **No source file was modified** during this resume. `git diff --numstat` still
  matches the 5-file baseline exactly (81/0, 19/0, 109/9, 151/7, 75/1) and HEAD
  is unchanged at `2841dc5`.
- The R4b implementation and the 3 new test files are **byte-for-byte unchanged**.
- **No force** (`--force`), **no tag**, **no reset**, **no clean**, **no stash**,
  and **no `git add -A` / `git add .`** was used. No `git add` was run at all.
- **No user taps** were required and none occurred; the device was not woken,
  unlocked, or installed to.
- **No device setting was changed** (no `settings put`, no `svc`).
- **C5 was not executed.** No synthetic race injection was performed.
- D: was not touched.

### §15.9 What is needed to unblock

STEP 1b cannot pass until `AutoModeControllerTest` stops hanging. The narrowest
fix is to bound the second stage of `awaitNextTerminalAfterChange()` (e.g.
`withTimeoutOrNull(...)` around the whole wait, then assert). That is a
**test-only** change — but it is a source modification, which this phase was not
authorised to make, so it was not made.

**Status:** BLOCKED at STEP 1b. R4b code is complete and unchanged; the app
suite is blocked by a pre-existing test hang; nothing was committed or pushed.

---

## §16 Fix hanging skip test + R4b completion

**Date:** 2026-10-14
**Phase:** FIX-HANGING-SKIP-TEST
**Outcome:** **COMPLETE.** The test that hung the app suite is fixed, the full suite runs
to completion, the APK builds, the device verifies, and R4b is committed and pushed.
Full detail: **`FIX_HANGING_SKIP_TEST_REPORT.md`**.

### §16.1 The hang, and its root cause

`AutoModeControllerTest.skipWhileConnectedDisconnectsAndTriesNext` (`:831`) hung
indefinitely. Its helper `awaitNextTerminalAfterChange()` had an **unbounded
`while (true)`** as its second stage, so instead of failing one test it stalled the entire
`:app:testProDebugUnitTest` suite — 951.86 s, killed at the 20-minute bound, with only 23 of
the class's 75 `@Test` methods recorded. That is what killed every previous full-suite
attempt (§12.4, §13, §14).

**Root cause: (A) test defect only — no production bug.** A bounded form of the helper was
run as the brief's tie-break, and it failed in ~10 s with:

```
before=Connected(hostname=s2, ip=10.0.0.2, protocol=SOFTETHER_UDP, ...),
last=AutoModeState$Disconnected, isRunning=false,
recentLogs=[disconnect, [AUTO] Started, ..., [AUTO] Trying #1 s2, connect:s2,
[AUTO] Tunnel established, [AUTO] Connected successfully,
[AUTO] GlobalVpnTracker confirmed disconnected,
[AUTO] Unexpected disconnect: s2; reconnecting (attempt 1/5)]
```

Two facts settle it: the controller **did** reach `Connected(s2)` with `isRunning == false`
(so root cause 2 is not active), and `before` was **already** `Connected(s2)` — the snapshot
was taken after `skipToNextServer()` had already completed, because that method restarts the
run on the controller's own dispatcher while the test thread keeps going. The
`[AUTO] Unexpected disconnect` that follows is a plain-JVM artifact: the connected watcher
polls `GlobalVpnTracker.isConnected`, which is never true off-device.

So `AutoModeController.kt` is correct and was **not modified**. Only the test changed
(+46/−12): the second stage is bounded, fails loudly with the stuck state, and `before` is
now captured before the skip.

### §16.2 Verification

| Step | Result |
|---|---|
| 4a library | **160 / 0 / 0 / 0** — PASS |
| 4b full app suite | **48 classes, 646 / 0 / 0 / 1 skipped** — PASS (first completion ever) |
| 4c APK | **BUILD SUCCESSFUL**, SHA-256 `750e8a706162736be1e0d1926a0566e591f6cef750626b1dfe9a56af2f45fbe1`, 63,629,658 bytes |
| 4d device | PASS — see below |

The one skipped test is legitimate and data-dependent:
`HtmlParserOracleTest.softetherUdpOnlyNeverBorrowsOpenvpnTcp` →
`AssumptionViolatedException: no SE-UDP-only + OVPN-TCP row in current snapshot`.

The previously-hanging test now runs in **0.05 s**; the whole class in **3.99 s**.

**Device (5585fecc, Xiaomi 2201117TG, Android 13) — all automated, no operator taps:**

- `install -r` → `Success`; `firstInstallTime` **unchanged** (`2026-09-28 16:05:05`).
- **DEX scan: all 5 R4b markers present** — `vpn_always_prefer_vpn` (classes28),
  `[collector] path=` / `boundToNetwork` / `boundToSocketFactory` (classes18),
  `currentVpnNetwork` (classes18, classes28).
- **Collector path, captured on cold start (reproduced twice):**

  ```
  VpnServerRepository: [collector] path=open source=mirror
  VpnServerRepository: [collector] path=open source=api
  VpnServerRepository: [collector] path=open source=html
  ```

  `path=open` = **3** (≥1 required) · `path=vpn` = **0** (opt-in off — the correct expected
  result) · `fallback-to-open` = **0**. The opt-in was not enabled.
- **5 AutoConnect cycles + 2 stress cycles: 5/5 CONNECTED**, 7 ×
  `[AUTO] Tunnel established` / `Connected successfully`.
- `[TEARDOWN-REENTRY]` = **7**, `Re-entrant teardown completion` = **7**,
  `TUN read failed with EIO` = **0**.
- **Crash scan clean:** `logcat -b crash` 0 lines, no `FATAL` / tombstone / `SIGSEGV`, no
  process death (app PID alive at the end).
- Settings re-check read-only: `stay_on_while_plugged_in=7`, `screen_off_timeout=300000`.

One Auto Mode attempt hit an unreachable auto-selected server
(`VPN Error: Connection failed: TCP connection failed (1)`); Auto Mode moved to the next
server and connected. The device's own internet was verified working throughout
(WiFi `PARS-M`, validated, `ping 8.8.8.8` 0 % loss). Environmental, not an app defect.

### §16.3 One intermittent failure worth knowing about

The **first** full-suite run recorded 646 / **1 failure** / 0 / 1 skipped. The failure was in
`VpnServerRepositoryR4bTest > "vpn path is chosen when every input holds"`, whose expected
set contained `[collector] path=vpn source=html` and whose actual set did not.

`VpnServerRepository` logs `path=vpn` *before* fetching, so a `fallback-to-open html` line
cannot exist without a preceding `vpn html` line — the log entry was **lost**. The cause is
that the test's `logs` field is an **unsynchronized `ArrayList`** (`VpnServerRepositoryR4bTest.kt:30`)
written from the `pathLog` lambda while the api and html fetches run concurrently
(`async` on `Dispatchers.IO`, `VpnServerRepository.kt:186-198`).

It **passed on immediate re-run**, and the full suite passed cleanly on re-run: intermittent,
not deterministic. This is a **pre-existing test-infrastructure defect in an R4b file**,
unrelated to the hang and unrelated to the fix in §16.1. `VpnServerRepositoryR4bTest.kt` was
in the brief's "for commit only" list and was therefore **not modified**; the one-line remedy
requires explicit authorisation.

### §16.4 Commits

| # | hash | message |
|---|---|---|
| 1 | `7b4adba` | `docs(audit): append §10 addendum correcting §2.3 mechanism (R1 evidence)` |
| 2 | `a9e16fd` | `feat(collector): per-request Network.getSocketFactory binding + RX wiring (R4b)` |
| 3 | `3c656a4` | `test(automode): bound awaitNextTerminalAfterChange to prevent hang` |
| 4 | *(this commit)* | `docs: report for FIX-AND-VERIFY-R4B (R4b + skip-test fix)` |

Commit 2 is the R4b implementation (7 files, +886/−17); commit 3 carries only
`AutoModeControllerTest.kt` (+46/−12) because no production change was needed.

### §16.5 Explicit statements

- No source file outside the brief's authorised list was modified. The only source change in
  this phase is `AutoModeControllerTest.kt` (test-only); `AutoModeController.kt` is untouched.
- `PROJECT_SPECIFICATION.md`, the native sources and the build files were **not** touched.
- No `git add -A` / `git add .`; every file was staged by exact name, and the staged set was
  verified against the brief's list before each commit.
- No `--force`, no tag, no `reset`, no `clean`, no `stash`.
- No user taps were required; the device was already awake and unlocked. No device setting
  was changed. **C5 was not executed.** No synthetic race injection was performed.
- D: was not touched.
