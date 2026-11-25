# Master Execution Report — SoftEthergai (2026-11-24)

## 1. Initial repository state

- Repository: `C:\Dev\SoftEthergai`, branch `main`, HEAD `173ed410977e9f1b60afb03c3363a3f62f9eac17` (2026-11-14).
- Working tree before implementation: clean except one untracked analysis report from the previous session (`docs/reports/PROJECT_KOTLIN_DEEP_REVIEW_FA.md`).
- Version: `versionCode 154`, `versionName 2.4.20` — **unchanged**.
- Toolchain: AGP `9.1.1`, Gradle `9.7.0`, Kotlin `2.4.0`, KSP `2.3.12`, JetBrains JDK `21.0.10`, compile/targetSdk `37`, minSdk `24`.
- Modules: `:app` (main), `:SoftEtherClient`, `:sstpClient`, `:vpnLib`. All three library modules are **vendored tracked files**, not gitlinks (`git ls-files --stage | grep ^160000` is empty); `.gitmodules` is stale metadata.
- Baseline JVM suite (clean PATH, see §16): **1444 tests, 4 failed, 1 skipped**. Failures: one stale AutoMode test (`skipFiltersByIdentityNotByIp`) and three `EmergencyMergeRetentionTest` cases caused by the old 300-row default cap. Baseline debug build had previously succeeded (APK 61 MB).

## 2. Previous conversation requirements recovered

The external `chatgpt.com/c/...` link was **not accessed**; no claim is made about its contents. Requirements recovered from the current session and the master prompt:

- Preserve existing architecture, database, protocols, settings, UI and completed lifecycle work (F.28AD-B, F.28AC).
- No destructive Git operations, no commit/push, no version bump, no release publication.
- Prioritise Auto Mode stability: rotation, cooldown, backoff/jitter, stale-callback safety, user cancellation, no overlapping attempts.
- Preserve server retention priorities: healthy servers, protocol metadata, connection history, provenance; do not limit storage to one API response.
- Dynamic, validated mirror discovery; strict source/redirect validation; no weakened TLS.
- Separate metadata from full OpenVPN configuration; on-demand config retrieval; preserve custom/paid profiles.
- Evidence-driven, test-first implementation; distinguish JVM, emulator and device verification.

## 3. Problems identified

See the earlier read-only audit and `docs/reports/PROJECT_KOTLIN_DEEP_REVIEW_FA.md`. This execution focused on the confirmed defects that directly affect the stated objectives:

| ID | Problem | Classification |
|---|---|---|
| A1 | Auto Mode used wrong SSTP intent actions; service fell through to disconnect | CONFIRMED BUG |
| A2 | Reconnect coroutine survived `stop()` and redialled after ~2 s | CONFIRMED BUG |
| A3 | Leaving-server identity lost when a Next-Server cycle reset | CONFIRMED BUG |
| A4 | Concurrent log-store writes lost lines | CONFIRMED BUG |
| A7 | `sessionTriedKeys` was a mutable `HashSet` guarded only by `@Volatile` | CONFIRMED BUG |
| A10 | Reachability counters used non-atomic `value++`/`+=` | CONFIRMED BUG |
| S2 | Delayed non-terminal state runnable survived `stopVpn()` | CONFIRMED BUG |
| S3 | Native receive timeout was interpreted as peer keepalive | CONFIRMED BUG |
| S5 | Duplicate state callbacks and unsynchronised `currentState` | CONFIRMED BUG / ARCHITECTURAL RISK |
| CAP | Default retained-server cap 300 barely exceeded one API response | CONFIRMED DESIGN DEFECT |
| UP1 | API-26 `registerDefaultNetworkCallback` called unguarded (minSdk 24) | POTENTIAL ISSUE |
| UP2 | `OpenVPNService.onRevoke` could dereference null management | POTENTIAL ISSUE |
| S1 | Native force-release while JNI may still be executing | ARCHITECTURAL RISK — **not fixed in this batch** |

## 4. Root causes

- **A1:** duplicated action-string constants in the adapter instead of a shared contract.
- **A2:** fire-and-forget `scope.launch` with no `Job` ownership and no session epoch.
- **A3:** a local `currentKey` was captured, but the coroutine re-read a field that had already been nulled.
- **A4/A7/A10:** mutable shared state updated from several threads without a single serialization point.
- **S2:** terminal publication bypassed `handleConnectionState`, so the pending runnable was not removed.
- **S3:** native `softether_fill_recv_queue` returned `0` for keepalive, empty poll and timeout; Kotlin treated every `0` as received traffic.
- **S5:** setter emitted a callback and two call sites emitted the same transition again.
- **CAP:** configured ceiling defaulted to 300 while the requirement is to retain discoveries from several sources.
- **S1:** the native lifetime ledger equates a wall-clock deadline with native completion and has no cancellation channel into `nativeConnectWithHub`.

## 5. Architecture changes

- Added `AutoModeRecoveryPolicy` (pure JVM) and made `AutoModeController` own a `recoveryJob` plus a `sessionEpoch`.
- Centralised Auto-Reconnect dispatch in `launchRecovery()`. Fresh starts, stop and disconnect cancel/epoch-invalidate pending recovery.
- Introduced an explicit native receive event enum (`softether_rx_event_t`) across C core → JNI → Kotlin (`RxEvent`).
- Made the SSTP action strings a public contract in `sstpClient` and imported them in `AndroidConnectionAdapter`.
- Kept the existing state machine, Room schema, merge rules, service lifecycle and native ownership model intact; no architecture was replaced.

## 6. Auto Mode improvements

- Bounded exponential backoff with symmetric bounded jitter (base = injected settle delay, cap 2 min).
- Consecutive-loss counter resets on a verified connection; after `RECONNECT_MAX_ATTEMPTS` consecutive losses the policy pauses for 60 s and resumes a fresh backoff cycle — it **never** publishes `ERROR_RECONNECT_LIMIT` any more.
- After the first loss, the dropped server is moved to the end of the reconnect ordering instead of being seeded first; identity-based filtering (`ip|hostname`) is preserved.
- Stop/disconnect increments `sessionEpoch`, cancels the owned recovery job, clears the tried-set and resets the policy; a stale callback is rejected before and after the backoff.
- `startLocked()` supersedes any pending recovery, preventing overlapping runs.
- The failed-server cooldown, quality-rejection window, wedge tracker, history reasons and network-retry path remain in place and are used by the new flow.
- Failure categories are unchanged and remain attributable through `HistoryFailureReason` (handshake/auth/timeout/transport/remote loss vs. user cancellation).

## 7. VPN Gate collection improvements

- Default server-list capacity raised from **300 → 1000** (`ServerListCap.DEFAULT`, `DataUtil.DEFAULT_SERVER_LIST_CAP`); range remains 10–2000 and remains user-configurable. This also resolves the three `EmergencyMergeRetentionTest` baseline failures, which assumed a dataset of 311 rows.
- Verified that the existing collector already uses canonical HTML + API + dynamic `sites.aspx` mirror discovery (`VpnGateSourceCandidates`, `VpnGateMirrorSource`, `VpnGateUrls.MIRRORS_URL`, `MAX_MIRRORS = 12`), caches mirrors, keeps provenance and revalidates candidates.
- Verified existing merge rules: `api > html > mirror > manual`, inferred-vs-explicit SSTP 443 distinction, independent SoftEther TCP/UDP and OpenVPN TCP/UDP records, no L2TP 1701 inference, bounded response/row caps.
- No collector rewrite was performed; the existing design already satisfies most of the stated requirements.

## 8. CSV bandwidth optimization

- **Not implemented in this batch.** The API still carries Base64 OpenVPN profiles. Removing that field requires a coordinated schema change, on-demand downloader and cache invalidation; doing it partially would break existing connections.
- Existing safeguards are retained: 8 MB body cap, cancellation, per-hop redirect validation, gzip handling by OkHttp, bounded parsing and cached snapshots.
- Conditional GET/ETag persistence and single-flight refresh are **not** implemented; see §17.

## 9. OpenVPN configuration retrieval

- **Not implemented in this batch.** No `openvpn_download.aspx` downloader or `sid`/`hid` reference storage exists in the current model (`VPNGateItem` has no such fields). Adding it requires a Room migration, parser changes, cache policy and adapter integration.
- Existing embedded-profile path, custom/paid profiles and `OvpnConfigTransform` were left untouched.
- `vpnLib.onRevoke` now guards a null management channel (upstream selective patch).

## 10. Mirror discovery and validation

- Already implemented in the current repository; verified by source inspection, not re-tested on a live network in this environment:
  - `https://www.vpngate.net/en/sites.aspx` is fetched dynamically (`MIRRORS_URL`).
  - Discovered/cached/bootstrap mirrors carry distinct `SourceKind` values and cannot be reported as canonical HTML/API.
  - `SourceValidation` rejects non-directory pages and invalid mirror links; `RedirectPolicy` keeps mirror identity.
  - Up to 12 mirrors are used; known bootstrap IP mirrors are the only cleartext hosts allowed by network security config.

## 11. Submodule comparison table

| Module | Local identity | Upstream default / HEAD | Recommendation |
|---|---|---|---|
| vpngate-connector (reference) | fork @ `173ed41`, no gitlinks | `master` / `3b33d04` (2026-11-20) | DO NOT UPDATE wholesale; cherry-pick crash fixes separately |
| SoftEtherClient | vendored snapshot, locally hardened | `main` / `7310cb8` (2026-11-06) | DO NOT UPDATE; upstream would restore test `.so`, debug symbols and OpenSSL prebuilts |
| vpnLib | vendored snapshot, locally hardened | `main` / `c2ed737` (2026-11-09) | DO NOT UPDATE wholesale; one null-guard patch applied |
| sstpClient | vendored snapshot | `vpngate-connector` / `2a3b7db` (2026-11-06) | DO NOT UPDATE wholesale; resource rename/dependency bump not applied |

Closest upstream snapshot SHAs are blob-similarity estimates, not authoritative vendoring commits. All upstream SHAs came from successful `git ls-remote`/clone commands.

## 12. Submodules updated or deliberately not updated

- **Updated:** none as a unit. No gitlink pointer changed.
- **Selectively applied:** API-26 network-callback fallback in `SoftEtherVpnService.kt`; `onRevoke` null guard in `OpenVPNService.java`.
- **Deliberately not updated:** wholesale replacement of any vendored module; SSTP resource rename; vpnLib minify flag; OpenSSL prebuilts. Local hardening (CMake `BUILD_TESTING` off, stripped release library, double-R8 guard) would be lost.

## 13. Files changed

| File | Why |
|---|---|
| `app/.../automode/AndroidConnectionAdapter.kt` | use shared SSTP action contract; fix `select` payload bug |
| `app/.../automode/AutoModeController.kt` | owned recovery job, epoch, policy, rotation, leaving-key and concurrent set fixes |
| `app/.../automode/AutoModeRecoveryPolicy.kt` *(new)* | pure backoff/jitter/pause policy |
| `app/.../automode/AutoModeLogStore.kt` | serialise concurrent writes |
| `app/.../network/ServerReachabilityTester.kt` | atomic counter helpers |
| `app/.../repository/ServerListCap.kt`, `app/.../utils/DataUtil.kt` | default capacity 1000 |
| `sstpClient/.../SstpVpnService.kt` | publish action constants |
| `SoftEtherClient/.../SoftEtherVpnService.kt` | cancel pending state runnable; API-26 callback guard |
| `SoftEtherClient/.../client/SoftEtherClient.kt` | RxEvent contract and JNI signature |
| `SoftEtherClient/.../controller/ConnectionController.kt` | deduplicated state setter; event-driven keepalive |
| `SoftEtherClient/src/main/cpp/...` (4 files) | explicit native receive event |
| `vpnLib/.../OpenVPNService.java` | onRevoke null guard |
| Tests listed in §14 | regressions and policy coverage |

No version, manifest, CI workflow, signing or release configuration was changed.

## 14. Tests added or updated

- New: `AutoModeRecoveryPolicyTest` (6 tests), `RxEventContractTest` (3 tests).
- Updated: `AutoModeReconnectTest` (new non-terminal recovery case; stop-during-backoff regression; fast injected pause), `AutoModeNextServerIdentityTest` (new-cycle semantics), `AutoModeControllerTest` (owned dispatch shape), `AutoModeLogStoreTest` (4-thread concurrent writer), `ServerListCapTest` (1000 default / 500 manual quota).

## 15. Tests executed and exact results

- Baseline full JVM suite: **1444 tests, 4 failed, 1 skipped** (before implementation).
- After implementation, full JVM suite: **1452 tests, 1 failed, 1 skipped**; the remaining failure was a source-shape assertion expecting the old inline `scope.launch` dispatch. It was updated, and `--tests '*AutoModeControllerTest*'` then completed with **BUILD SUCCESSFUL**.
- **Final full suite:** `:app:testProDebugUnitTest` 1452 tests / 0 failures / 0 errors / 1 skipped; `:SoftEtherClient:testDebugUnitTest` 191 / 0 / 0 / 0; `:sstpClient:testDebugUnitTest` 3 / 0 / 0 / 0. Total **1646 tests, 0 failures, 0 errors, 1 skipped**. Gradle printed `BUILD SUCCESSFUL in 9m 53s`.
- Debug build with native compilation: **BUILD SUCCESSFUL** (`assembleProDebug`), including the changed C/JNI code.
- No emulator or physical-device test was executed in this environment.

## 16. Build results

- Commands were run with a clean PATH containing only the JBR `bin`, `C:\Windows\System32` and `C:\Windows`, with `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT` and `CI=true` set. This avoids AGP passing an unquoted `-Djava.library.path` containing paths with spaces, which previously broke JVM test workers.
- `:app:assembleProDebug` + `:SoftEtherClient:testDebugUnitTest` + changed app tests: **BUILD SUCCESSFUL**.
- Release compilation: `./gradlew :app:assembleProRelease` was attempted and **blocked by the repository's own fail-closed signing guard** at `app/build.gradle:166`: “Release build blocked: release signing configuration is incomplete. Missing: release keystore file (keyStore/release.jks), SIGN_KEY_PASSWORD …”. No release artifact was produced, published or uploaded.

## 17. Remaining issues

- **S1 native force-release:** a timeout can still free the slot while JNI is executing. Fixing it needs a dedicated native executor + abort-aware JNI cancellation and device verification; deliberately not attempted here.
- On-demand OpenVPN config download and Base64 removal from ordinary collection (§§8–9).
- ETag/conditional GET persistence and explicit single-flight refresh in the collector.
- `ServerHistoryDao` event retention remains unbounded (documented OD-5 decision).
- Static analysis/lint was not run in this batch.
- Baseline Python end-to-end failures (loopback IP filtering) are unrelated and were not modified.

## 18. Physical-device tests still required

1. Auto Mode SSTP connect/disconnect with the corrected action contract.
2. Stop during reconnect backoff: no tunnel may return after the delay.
3. Repeated unstable server losses: rotation, pause and resume without terminal error.
4. SoftEther silent-peer keepalive: a blackholed peer must be detected; a busy tunnel must not.
5. JNI receive event mapping for data, keepalive, idle timeout and close.
6. Existing F.28AD-B attempt-ID propagation (T11) was not reopened.
7. Native force-release scenario in S1 with thread dumps and a non-responsive TLS server.

## 19. Risks and recommendations

- The new recovery policy changes the terminal-error behaviour deliberately; product/spec text should be updated to match (old `ERROR_RECONNECT_LIMIT` wording remains in `PROJECT_SPECIFICATION.md`).
- Raising the default cap to 1000 increases storage and cold-start query work; keep the configurable ceiling and monitor `eventCount`/DB size on device.
- The native receive enum changes an internal ABI between vendored C and Kotlin; both were rebuilt together successfully, but device verification is mandatory.
- Do not cherry-pick upstream module heads wholesale; local hardening is intentional.
- Commit the working tree in small logical groups after device verification; this execution intentionally made no commit and no push.

---

**Validation block — final full suite (before the additional fixes below):** `BUILD SUCCESSFUL in 9m 53s`; app 1452 / 0 / 0 / 1, SoftEtherClient 191 / 0 / 0 / 0, sstpClient 3 / 0 / 0 / 0 (tests / failures / errors / skipped). Debug APK build with native compilation: `BUILD SUCCESSFUL`. Release assembly: blocked by signing guard as documented in §16.

---

## 20. Follow-up fixes after the user’s clarification (same day)

| User item | Honest status | Evidence |
|---|---|---|
| Auto Mode repeatedly reconnecting then stopping | **Implemented, not device-verified** — owned recovery job, epoch, rotation, backoff/jitter, bounded pause | §6 above; `AutoModeRecoveryPolicy.kt` |
| Server list refresh through the app’s own tunnel | **Already implemented before this work** — per-request `Network.getSocketFactory()` binding, TUNNEL rung primary when usable | `HttpFetcher.kt:134-186,446-455`, `VpnServerRepository.kt:290-310,743-820` |
| Refresh through another app’s VPN | **Newly improved** — explicit `currentDefaultNetwork()` rung bound to `activeNetwork` instead of an unbound client | `GlobalVpnTracker.kt`, `VpnServerRepository.kt` (`defaultFetcherFactory`) |
| Configurable server-list update timeout | **Newly implemented** — `SETTING_SERVER_LIST_UPDATE_TIMEOUT_SECONDS`, 10–180 s default 45 s, Settings row + picker (fa/en/vi), wired into both repository factories | `DataUtil.kt`, `SettingsScreen.kt`, `strings.xml`, `ServerListUpdates.kt` |
| SSTP protocol not working | **NOT FIXED end-to-end** — only the Auto Mode SSTP action contract was fixed (A1). Manual SSTP already used correct actions. No device test was possible. | §6; device verification required |
| CSV import/export TCP↔UDP “swap” | **Newly fixed** — export used the 15-column API dialect and dropped all port columns, so re-import reconstructed a TCP-only profile. Export now emits the 29-column dialect with all TCP/UDP fields; regression test added | `ServerActions.kt`, `ServerActionsTest.kt` |
| `openvpn_download.aspx` per-server config | **Implemented later the same day** — `sid`/`hid` are captured at collect time, persisted (Room 11→12) and the profile is downloaded on demand; see §22 | `VpnGateHtmlParser.kt`, `VPNGateItem.kt`, `AppDatabase.kt`, `OpenVpnConfigDownloader.kt`, `AndroidConnectionAdapter.kt` |
| Dynamic mirrors from `sites.aspx` | **Already implemented before this work** — discovered/cached/bootstrap mirrors, validation, redirect confinement, cap 12 | `VpnGateSources.kt:24,91-101,207-255` |
| Reduce CSV size / bandwidth | **Implemented later the same day** — conditional GET (`ETag`/`Last-Modified`/304) with a bounded validator cache, plus on-demand configs so profiles no longer have to be carried; see §22 | `HttpCache.kt`, `HttpFetcher.kt`, `OpenVpnConfigDownloader.kt` |

Extra tests run after these follow-up fixes: `--tests '*ServerActionsTest*' --tests '*DataUtilTimeoutRangeTest*' --tests '*VpnServerRepositoryDynamicVpnPathTest*'` → **BUILD SUCCESSFUL**; `--tests '*VpnServerRepositoryRoutingDynamicTest*'` → **BUILD SUCCESSFUL** after adjusting a diagnostic string that violated the source-shape guard. `:app:assembleProDebug` with resources and native code → **BUILD SUCCESSFUL**.

**Final full suite after ALL follow-up fixes:** `BUILD SUCCESSFUL in 9m 51s`; app **1454 / 0 / 0 / 1**, SoftEtherClient **191 / 0 / 0 / 0**, sstpClient **3 / 0 / 0 / 0** — total **1648 tests, 0 failures, 0 errors, 1 skipped**.

---

## 21. Physical-device verification (same day)

- Device: `2201117TG`, Android 13, `adb` serial `5585fecc`.
- The installed `vn.unlimit.vpngatepro` had a different signing signature. With the user's explicit approval it was uninstalled and the freshly built debug APK installed with `adb install -r`; both commands returned `Success`. The user was warned that local app data would be removed.
- Launch smoke test: `SplashActivity → MainActivity`, no `FATAL EXCEPTION` in logcat, UI rendered with 99 servers and working bottom navigation.
- **Manual SSTP CONFIRMED WORKING on device:** selected a server exposing `SSTP`, chose `Connect via MS-SSTP`, accepted the Android VPN permission dialog. UI then reported `Connected MS-SSTP with IP: 10.234.53.218`, with a VPN network active. Disconnect returned the UI to `Disconnected MS-SSTP`.
- Server-list screen refreshed successfully on ordinary Wi-Fi (`Last updated: 24 Nov 2026 - 17:13`, 99 servers). Refresh with a foreign-app VPN, and refresh through the app's own tunnel, were **not** device-verified.
- Auto Mode SSTP and the unstable-server cycle were **not** exercised on device; JVM tests pass but are not a substitute.
- The new Settings timeout row was compiled and unit-tested, but the bottom Settings list could not be scrolled reliably with `adb input swipe` on this MIUI device, so its visual presence was not confirmed on screen.
- Screenshots: `C:\Dev\tools\device-screen-5.png` (connection request), `device-screen-7.png` (SSTP connected), `device-screen-8.png` (disconnected), `device-screen-16.png` (refreshed server list).
- No commit, push, release or APK publication was performed.

---

## 22. Third pass — on-demand OpenVPN configs (G-7) and conditional GET (G-5)

The two items §20 still listed as unimplemented were the user's explicit
requests #6 and #8. Both are now implemented.

### 22.1 G-7 — the per-server `.ovpn` profile, downloaded on demand

**Before.** The app only ever consumed the API feed's
`OpenVPN_ConfigData_Base64` column. Consequences:

- a server discovered through the HTML page had **no profile at all**, even
  though the page had just advertised one (the page's `do_openvpn.aspx` link
  was parsed for `tcp=`/`udp=` only, and `sid`/`hid` were discarded);
- every refresh carried every profile inline — which is the bulk of the feed
  the user reported as slow;
- Auto Mode gated OpenVPN on `openVpnConfigAvailable`, so those servers were
  never even attempted.

**Now.**

1. `VpnGateHtmlParser` records `protocols.openvpn.downloadSid` /
   `downloadHid` from the link's own query string. These are the identifiers
   VPN Gate's download endpoint requires.
2. They travel through the record layer → `VpnConnectionMapper` →
   `VPNGateConnection.openVpnSid/openVpnHid` (with Parcel support) →
   `VPNGateItem` (two nullable `TEXT` columns, `MIGRATION_11_12`, additive and
   back-fill-free).
3. `OpenVpnConfigDownloader` builds the documented endpoint
   `/common/openvpn_download.aspx?sid=..&udp=..&host=..&port=..&hid=../name.ovpn`,
   tries the canonical host first and then the official IP mirrors, confines
   redirects to the base's own authority, and refuses any body that is not a
   profile (an HTML error page is never handed to `ConfigParser`).
4. `AndroidConnectionAdapter.connect` calls `ensureOpenVpnProfile` before
   dispatching to the OpenVPN stack: if a profile is present it does nothing;
   if it is absent but `sid`/`hid` exist it fetches that one profile (bounded
   to 12 s) and stores it in the canonical `openVpnConfigData` slot, so the
   existing UDP/domain transforms apply to a downloaded profile exactly as to
   a collected one.
5. `AutoModeEngine.toCandidate` now treats *obtainable* as available:
   `openVpnConfigAvailable = !openVpnConfigData.isNullOrBlank() || canDownloadOpenVpnConfig`.
   If the download fails, the attempt ends as an ordinary `MISSING_CONFIG`
   failure — the same outcome the old gate forced up front, but decided on
   evidence instead of a guess.

**What this buys.** The profile no longer has to be carried in the collected
list at all; a server is dialable the moment it is listed. The `sid`/`hid`
pair costs ~30 bytes per server.

### 22.2 G-5 — conditional GET, so an unchanged feed costs two headers

**Before.** Every refresh downloaded the canonical HTML (~0.5 MB) and the API
CSV (~0.9 MB) in full, whether or not anything had changed.

**Now.** `HttpCache` is a bounded, process-wide LRU of *body + validators*
(at most 8 URLs, 2 MB per body, 8 MB total). `OkHttpFetcher` sends
`If-None-Match` / `If-Modified-Since` when an entry exists, and on
`304 Not Modified` returns the remembered body as a normal success
(`servedFromValidatorCache = true`) instead of the megabyte.

Safety properties, stated precisely:

- a body is **only** returned when the server itself answers 304, which is the
  server asserting the body is current;
- a body with no `ETag` and no `Last-Modified` is never retained (nothing to
  revalidate with);
- a cache miss, an expired validator, or a 200 behaves exactly as before;
- 304 is handled **before** the redirect branch — it is inside `300..399` but
  is not a redirect, and treating it as one would have discarded the body;
- the fetcher's `conditional` flag can disable all of it for diagnostics.

Because the returned body is the identical, previously validated body, every
downstream stage (validation, merge, scoring, storage) is untouched. That is
why this could be added without changing a single repository code path.

### 22.3 Tests added in this pass

| Test class | Tests | What it pins down |
|---|---|---|
| `OpenVpnConfigDownloaderTest` | 6 | exact endpoint construction; UDP-only servers; base-to-base fallback; HTML never accepted as a profile; incomplete request never sent; hostnames with a dot not given the `opengw.net` suffix |
| `HttpCacheTest` | 8 | validators remembered; `Last-Modified` alone suffices; validator-less bodies never retained; oversized bodies refused; bounded LRU eviction; `clear`; byte-for-byte round trip of a CSV body |

### 22.4 Environment note (not a code defect)

The Gradle **test worker** failed to start in this environment with
`Could not find or load main class Files\WorkBuddyAI\resources\vendor\PortableGit\Program`.
Cause: the host `PATH` contains a malformed entry (embedded `"` and `;`), which
corrupts the `-Djava.library.path` value Gradle derives from `PATH`. Running
the suite with a sanitized `PATH` starts the worker normally. This affects the
build machine only, not the app.

### 22.5 Still open after this pass

- **HTML-only (config-free) refresh.** With G-7 in place the API CSV is no
  longer required for functionality, so demoting it to a fallback is now a
  safe, self-contained change. It is **not** done: it alters merge inputs and
  needs its own regression pass.
- **Base64 removal from ordinary collection** depends on that demotion.
- **Device verification** of Auto Mode on an unstable server, Auto Mode SSTP,
  refresh through a foreign VPN and refresh through the app's own tunnel.

---

## 23. Fourth pass — verification of an external code review (8 findings)

An external review was supplied by the user with eight findings and the request
"check whether these were fixed". Every finding was checked against the code.
**Four were real and are now fixed; two were already correct; two are
partly real and partly wrong.** Nothing was accepted or rejected on the
review's authority alone.

### 23.1 Finding-by-finding verdict

| # | Finding | Verdict | Evidence |
|---|---|---|---|
| 1 | One failed liveness probe can tear down a healthy tunnel (3 s samples × threshold 3, probe interval 8 s + 3 s timeout, silence window 15 s) | **REAL — FIXED** | See §23.2. Arithmetic confirmed: a single timed-out probe produced a gap of `interval + timeout + timeout` = 14 s against a 15 s window, so ~1 s of margin; any jitter fired the watchdog |
| 2 | The watchdog is seeded with proof at connect, so a blocked probe target kills every tunnel | **REAL — MITIGATED** | Seeding is *correct* (a tunnel that was just verified has proof by definition; without it the watchdog could never fire at all). The actual defect was the single hard-coded target. `TunnelLivenessProbe` now tries 4 independent anycast/resolver targets and succeeds on the first round trip |
| 3 | Potential NPE: `if (watching != null && isX(watching!!))` — `endAttempt()` on another thread can null it between the check and `!!` | **REAL — FIXED** | Three sites in `TunnelStateWatcher` (`onSoftEtherStateChanged`, `onSoftEtherNativeBusy`, `updateState`). Each now reads the volatile once into a local |
| 4 | `onTunnelLost` has no protocol filter, so a late terminal event from stack A can report loss for a session carried by stack B | **REAL — FIXED** | Confirmed: every stack's terminal event called `onTunnelLost` unconditionally. This is a direct cause of "connects then immediately disconnects" and is now the highest-impact fix of this pass |
| 5 | `disconnect()` always arms a SoftEther teardown waiter, so switching from OpenVPN/SSTP stalls 5 s | **NOT A DEFECT** | Refuted by reading the service: `stopVpn`'s idle/re-entrant branch calls `notifyTeardownComplete` with the **caller-supplied** id, so the waiter is satisfied immediately (as `NOOP_REENTRANT`). No 5 s stall |
| 6 | Race in `TunnelLivenessProbe`: a cancelled loop's `finally { running.set(false) }` can clear the flag for a newer loop | **REAL — FIXED** | Added a `generation` counter; a loop only clears `running` if its own generation is current |
| 7 | `AutoModeModuleLogTap` leaks the `logcat` process; `AutoModeLogStore.add()` copies the whole list per line; `clear()` is unlocked | **PARTLY REAL — FIXED (leak + lock); copy accepted** | `stopLogcatTap()` only interrupted the thread and never called `destroy()`, and `Thread.interrupt()` does not unblock `readLine()` on a process stream → one leaked `logcat` per attach/detach. Now destroyed on every exit path. `clear()` now takes the same lock as `add()`. The O(n) list copy is inherent to exposing `StateFlow<List<Line>>` and is bounded at 2000 lines; not changed |
| 8 | Smaller items | **MIXED** | `associateBy { it.hostName }` vs `viewOf`'s `trim()` — **REAL, fixed**. `richness()` missing `seTcpPort > 0` — **WRONG**, it has that condition (`AutoModeEngine.kt:375`). `VpnOwnerUidReader` API level — harmless: the reader uses reflection by method name, so the number in the comment is documentation only |

### 23.2 Finding 1 in detail — why this was the user's main symptom

The reported behaviour is "on an unstable server Auto Mode connects and
disconnects repeatedly". Two independent mechanisms produced exactly that:

**Mechanism A (finding 1) — false loss on a healthy tunnel.**
`TunnelLivenessWatchdog` samples once per second and reports a loss after
`silenceTimeout (15 s) + threshold (3 samples)` ≈ 17–18 s without proof. The
probe proves life every 8 s, so on a good link proofs arrive at 0 s, 8 s, 16 s…
and the watchdog never fires. But a single probe that *times out* (3 s) pushes
the next attempt a further 8 s away, so the next possible success is at
`8 + 3 + 8 = 19 s` — past the 17–18 s deadline. The tunnel is then declared
lost and Auto Mode reconnects, even though nothing was wrong with it. On a
merely *slow* link this repeats indefinitely.

Fixed by three changes that together restore a wide margin:

1. `DEFAULT_SILENCE_TIMEOUT_MS`: 15 s → **30 s**.
2. A failed probe now retries after **2 s** (`PROBE_RETRY_MS`) instead of a
   full 8 s interval, bounding the gap caused by one failure to 5 s.
3. A regression test asserts the resulting detection point (32 s) and that both
   invariants (`interval + timeout < window`, and the same for the failure
   path) hold.

Trade-off, stated plainly: a genuinely dead tunnel is now detected in ~32 s
instead of ~18 s. That is the correct direction — a false disconnect interrupts
a working session, whereas a late detection only delays a reconnect Auto Mode
performs anyway.

**Mechanism B (finding 4) — cross-stack loss attribution.**
While connected over OpenVPN or SSTP, a *late* SoftEther terminal event (from
the teardown of the previous attempt, which finishes after the next tunnel is
up) called `onTunnelLost` and the controller — seeing `Connected` — tore the
live session down. Now `TunnelStateWatcher` records which stack reported
CONNECTED and accepts a loss only from that same stack, at most once per
session.

### 23.3 Tests added in this pass

`TunnelLossIdentityTest` (7 tests): a foreign stack's terminal event reports no
loss; the owning stack's does; a duplicated terminal event reports once; an
unconnected watcher never reports; the silence window absorbs a 16 s probe gap
and fires at exactly 32 s; both timing invariants hold; the probe offers ≥2
distinct targets.

### 23.4 What this pass did *not* change

- The liveness probe still runs through the tunnel (the review confirms the app
  is not excluded from its own tunnel in SoftEther and SSTP; for OpenVPN a user
  who puts the app outside the allowed-apps list would make the probe exit the
  tunnel and always look alive — unchanged, and worth a future guard).
- `AutoModeLogStore` still re-emits the whole list per line.

### 23.5 Final verification state after this pass

| Module | Test classes | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|---|
| `:app` | 121 | 1475 | 0 | 0 | 1 |
| `:SoftEtherClient` | 15 | 191 | 0 | 0 | 0 |
| `:sstpClient` | 1 | 3 | 0 | 0 | 0 |
| **Total** | **137** | **1669** | **0** | **0** | **1** |

`:app:assembleProDebug` → **BUILD SUCCESSFUL**. The APK was rebuilt after these
fixes (`app/build/outputs/apk/pro/debug/app-pro-debug.apk`).

**Device re-verification of the watchdog change is PENDING**: the phone
(`5585fecc`) disconnected from USB during this pass and `adb devices` reports no
attached device. The specific check still owed is an Auto Mode session left
connected for ~90 s to confirm no `No proof of life` line appears on a healthy
tunnel. Everything else in §21 remains as verified earlier today, and the Room
11 → 12 migration was confirmed on that device (`user_version = 12` with the
existing 164 KB database, 276 servers still listed afterwards).

---

## 24. Fifth pass — a complete acquisition system, designed against measured data

The request was to study `https://www.vpngate.net/en/`,
`https://www.vpngate.net/en/sites.aspx` and
`http://www.vpngate.net/api/iphone/`, and build a system that obtains the full
server list *with every protocol's data* even on a filtered, slow link, plus
per-server OpenVPN configs.

Everything below was measured against the live endpoints on 2026-11-24, not
assumed.

### 24.1 What each endpoint actually provides

| Endpoint | Raw | Gzipped | Servers | Protocol columns |
|---|---|---|---|---|
| `https://www.vpngate.net/en/` | 218 KB | **35 KB** | 100 | **all three** |
| `https://www.vpngate.net/api/iphone/` | **1.33 MB** | 956 KB | 99 | **none** |

Two measurements decided the whole design:

1. **The CSV is 99.1% Base64.** 1,320,944 of 1,333,577 bytes is
   `OpenVPN_ConfigData_Base64`. The actual metadata is **12.6 KB**.
2. **The CSV has no protocol data at all.** Its 15 columns are
   `HostName, IP, Score, Ping, Speed, CountryLong, CountryShort,
   NumVpnSessions, Uptime, TotalUsers, TotalTraffic, LogType, Operator,
   Message, OpenVPN_ConfigData_Base64` — there is no SoftEther column, no SSTP
   column, no L2TP column. A list built from the CSV alone cannot offer SSTP or
   SoftEther, which is a plausible contributor to "SSTP does not work".

The HTML table, by contrast, has one column per protocol. Measured across the
100 rows of the live page:

| Protocol | Servers offering it | What the cell publishes |
|---|---|---|
| SoftEther (SSL-VPN) | 97 | `TCP: 443` and/or `UDP: Supported` |
| OpenVPN | 99 | `do_openvpn.aspx?fqdn=..&ip=..&tcp=443&udp=1195&sid=..&hid=..` |
| MS-SSTP | 80 | `SSTP Hostname : <host>` — **no port at all** |
| L2TP/IPsec | 21 | presence only, no port |

Three SoftEther cell shapes occur and all must be handled: TCP+UDP (76),
UDP-only (17), TCP-only (4). SSTP publishes **no port**, so 443 is a
*protocol default* and must be recorded as `DEFAULT` provenance, never as an
explicitly published fact. The existing parser already does exactly this.

### 24.2 The system, in four tiers

```
TIER 0  TRANSPORT      own tunnel  ->  foreign VPN  ->  physical  ->  mirrors
TIER 1  SOURCE         HTML (all protocols)   [CSV only if HTML yields nothing]
TIER 2  CONFIG         per-server .ovpn on demand via sid/hid
TIER 3  FALLBACK       last-known-good DB snapshot -> bundled seed
```

**Tier 0 — transport.** Unchanged in intent (TUNNEL when a bound VPN network
is usable, else PHYSICAL, else EMERGENCY), but the mirror rung now actually
works; see 24.3.

**Tier 1 — source order.** The HTML page is the primary source. The CSV is
fetched **only when the HTML source produced zero records**. This is the
single largest change in this pass: a normal refresh now transfers ~35 KB
instead of ~1.39 MB — a **~40x reduction** — while carrying *more* protocol
data than before.

The one thing the CSV has that the page does not is the inline `.ovpn`
profile. That is no longer needed at collection time, because the page
publishes `sid` and `hid` for every server that has a profile, and

```
https://www.vpngate.net/common/openvpn_download.aspx
    ?sid=<sid>&udp=<0|1>&host=<fqdn>&port=<port>&hid=<hid>/name.ovpn
```

serves that one profile. Verified against the live page: all 99 OpenVPN rows
carry a complete `fqdn, hid, ip, sid, tcp, udp` parameter set.

**Tier 2 — config on demand.** Already implemented in the previous pass
(`OpenVpnConfigDownloader`, `MIGRATION_11_12`, `AndroidConnectionAdapter`).
This pass closed the remaining hole: **the manual connect path**. `DetailActivity`
used to hide OpenVPN entirely when no profile was stored
(`conn.openVpnConfigData != null`), which would have made an HTML-only list
unable to connect over OpenVPN at all. It now offers OpenVPN whenever a
profile exists **or** is obtainable, downloads it on the first connect, and
shows a progress toast while doing so.

**Tier 3 — fallback.** Unchanged: snapshot, then bundled seed.

### 24.3 Why the list did not update: the mirror allowlist was static

This is the root cause of the reported symptom, and it was a local defect, not
censorship alone.

VPN Gate's mirrors are **plain HTTP only**. Verified:

```
https://150.40.105.20:16086/en/   -> connection failed (no TLS listener)
http://150.40.105.20:16086/en/    -> 200, 217,902 bytes
```

The app expressed that exception as a fixed list of six IPs in
`network_security_config.xml`. The directory, however, publishes *different*
mirrors each day. On 2026-11-24 it listed:

```
150.40.105.20:16086   150.40.105.19:35399
150.40.105.5:32536    133.18.161.231:45457
```

Only one of those four was in the static list. The other three were refused
by the platform with `CLEARTEXT communication ... not permitted` — so on a
network where `vpngate.net` is filtered, the canonical HTTPS host was blocked
by the censor and the fallbacks were blocked by the app itself.

**Fix.** The platform flag is now permissive and the real restriction moved
into code that can follow the directory:

- `CleartextMirrorPolicy` — permits cleartext only for hosts that are (a)
  registered mirrors (bootstrap, discovered from `sites.aspx`, or restored
  from the persisted cache), or (b) global IPv4 literals inside the two /24
  ranges the directory actually uses (`150.40.105.0/24`, `14.40.125.0/24`).
  `vpngate.net` and `tsukuba.ac.jp` are **never** cleartext.
- `CleartextMirrorInterceptor` — installed on every collector client via
  `collectorClientBuilder()`, so it covers the default client, every
  network-bound client and the config downloader. Because the collector
  follows redirects by hand, each hop is re-checked: a mirror cannot bounce the
  app onto an unapproved cleartext host.
- `numberedMirrors()` registers the run's whole mirror set **before** any of
  them is fetched, so a mirror discovered seconds ago is usable immediately.
- `OpenVpnConfigDownloader.defaultBases()` now includes the registered
  mirrors, so the per-server download benefits from the same discovery.

Security posture, stated plainly: the platform-level cleartext flag is
weaker, and the compensating control is code + tests rather than an XML file.
The trade was taken deliberately because the previous control was *provably
blocking the app's own fallbacks*, i.e. it failed open in the direction that
mattered. Ten tests pin the rule, including that `vpngate.net` can never be
fetched over HTTP and that loopback/RFC1918/CGNAT addresses are refused even
inside a mirror range.

### 24.4 Worst case, end to end

| Situation | What happens |
|---|---|
| Normal network | HTML only, ~35 KB, all protocols, profiles on demand |
| `vpngate.net` filtered, mirror alive | mirror HTML, same result, ~218 KB |
| Both filtered, app tunnel up | refresh rides the tunnel, canonical HTTPS |
| Both filtered, foreign VPN up | PHYSICAL binds to the default network (the VPN) |
| HTML fails entirely | CSV fallback (1.33 MB) — OpenVPN profiles inline, no SSTP |
| Everything fails | last-known-good DB snapshot, then bundled seed |
| Only a few servers reachable | still connectable: profile fetched per server |

### 24.5 Auto Connect — deep review result

The controller was re-read along its decision paths (start guards, fast path,
per-server loop, protocol loop, slot release, recovery). The defects that
produced the reported "connects and disconnects repeatedly, then stops" were
already found and fixed in the previous pass and are re-confirmed here:

- **False loss on a healthy tunnel** — silence window 15 s → **30 s**, and a
  failed probe retries in **2 s** instead of a full 8 s interval, so one
  timed-out probe can no longer exhaust the window (worst-case gap 5 s vs 30 s).
- **Cross-stack loss attribution** — a late terminal event from the previous
  stack can no longer tear down a live session; only the stack that reported
  CONNECTED may report its loss, once per session.
- **Terminal reconnect limit** — `ERROR_RECONNECT_LIMIT` is gone; repeated
  losses now produce bounded exponential backoff with jitter, rotate away from
  the dropped server after the first loss, and **pause** (60 s) instead of
  dying. A user Stop still cancels everything and nothing reconnects after it.
- **Multi-target probe** — four independent targets, success on the first, so
  one filtered address cannot condemn every tunnel.

Two structural observations recorded but **not** changed, because each would
alter documented behaviour and needs the operator's decision:

1. `isRunning` is `job?.isActive == true`, so after a successful connect the
   run job is complete while the tunnel is up. `onUnexpectedDisconnect` ends by
   calling `start()`, which internally cancels the `recoveryJob` it is itself
   running in. This is harmless today (the replacement job is launched on the
   controller's own scope, and a cancelled coroutine that is already returning
   completes normally), but it is fragile: the recovery path should hand the
   restart to a fresh job rather than cancelling its own.
2. A session never re-dials a server it has already visited
   (`sessionTriedKeys`), and a reconnect over an exhausted tail falls back to
   the dropped server alone. That is the documented "never wrap silently"
   rule, but it means a very long session converges on one server. A
   "recycle the session after N attempts" policy would fix the tail case.

### 24.6 The `sid` is a page token, not a server identity — verified live

Fetching the operator's own example link:

```
https://www.vpngate.net/common/openvpn_download.aspx
    ?sid=1795539088851&udp=1&host=vpn851923881.opengw.net&port=1628&hid=24992073&/...ovpn
-> HTTP 302, Location: /
```

The link had already expired. Comparing it with the live page shows why:

- `sid` = `1795539088851` in the operator's example, `1795539642828` on the page
  fetched minutes later, and **the same value on every row of that page**. It is
  a per-page-generation timestamp.
- `hid` is per server and stable (`24992073` for one host, `24869502` for
  another) — that is the real identity.

So storing `sid` is only valid while the page that produced it is current. The
downloader now handles that:

1. first pass — try every known base with the stored `sid`;
2. on failure, re-read that base's `/en/` page **once**, take the fresh `sid`
   published next to this server's `hid`, and retry.

Cost on the healthy path: nothing. Cost on the stale path: one 35 KB page read,
which is still 38x cheaper than re-downloading the CSV. `hid` matching is done
in both parameter orders, because the link's parameter order is not a contract.
Two tests pin it: the stale-`sid` retry, and "a page with no `sid` for this
`hid` still yields no profile" (no infinite retry, no fabricated body).

### 24.7 Auto Connect: the permanent stop, found and fixed

The reported symptom — "on an unstable server it connects and disconnects
repeatedly, and after a while it stops retrying" — had a second cause beyond
the false-loss and cross-stack bugs already fixed:

**`runAutoMode` ended every exhausted walk with a terminal.**
`setState(Error(ERROR_NO_SERVER))` was reached when the run had dialled every
candidate without a tunnel, and it was explicitly documented as
`// no auto loop back to #1`. In a reconnect flow that is a dead end: a live
tunnel is lost, recovery re-runs over the session tail, the tail is exhausted,
the run ends, and Auto Mode stays dead until the user presses the button.

Two changes, scoped deliberately:

1. **`onUnexpectedDisconnect` no longer names an exhausted tail.** When the
   remaining candidates are empty it clears `sessionTriedKeys` and starts a
   genuinely new cycle, instead of overriding the run with only the dropped
   server (which failed once and then hit the terminal).
2. **The terminal is now conditional on the run's origin.** A run Auto Mode
   started by itself (`AUTO_RECONNECT`) schedules a fresh cycle through the same
   recovery policy — exponential backoff with jitter, 60 s pause after a run of
   failures — instead of ending. A run the **user** started still ends with
   `ERROR_NO_SERVER`, because "I tried everything and it failed" is a real
   answer to an explicit press.

That distinction is what keeps the change honest: it fixes the reported
behaviour without silently redefining what a manual press means, and the
existing contract test (`allServersFailYieldsError`) still passes unmodified.

A third, smaller change: the recovery coroutine now detaches itself from
`recoveryJob` before calling `start()`. `startLocked()` cancels any pending
recovery before launching a run, which is correct for a user press but used to
land on the calling coroutine itself. It was harmless only because that
coroutine was already returning; the restart no longer depends on that
coincidence.

### 24.8 Final verification state (pass 5)

| Module | Test classes | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|---|
| `:app` | 122 | 1488 | 0 | 0 | 1 |
| `:SoftEtherClient` | 15 | 191 | 0 | 0 | 0 |
| `:sstpClient` | 1 | 3 | 0 | 0 | 0 |
| **Total** | **138** | **1682** | **0** | **0** | **1** |

`:app:assembleProDebug` → **BUILD SUCCESSFUL**
(`app/build/outputs/apk/pro/debug/app-pro-debug.apk`).

Tests added or changed in this pass:

| Test | What it pins |
|---|---|
| `CleartextMirrorPolicyTest` (10) | canonical site never cleartext; bootstrap allowed; a mirror discovered today allowed; new address inside a mirror range allowed; outside ranges refused; non-global addresses refused; registration by URL/authority/host; registering `vpngate.net` does not open it; reset |
| `OpenVpnConfigDownloaderTest` +2 | stale `sid` replaced from the current page (matched by `hid`); a page with no `sid` for that `hid` yields no profile |
| `RepositoryTest` +1 / ~1 | HTML-only dataset has protocol columns, no inline profiles, sid/hid present; CSV fallback still produces a dataset with inline profiles when the page cannot be parsed |

Not verified on a device in this pass: the USB device was disconnected
throughout. Everything in §21 stands, and the two checks still owed are the
90-second healthy-tunnel watchdog check and an Auto Mode session against an
unstable server to observe the new fresh-cycle behaviour.
