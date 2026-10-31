# BLOCK v1.0.78 — NATIVE LIFETIME, SSTP BOUNDS, PUBLISHER ADMISSION

**Repository:** `morteza-taheri/SoftEthergai` · **Branch:** `main`
**Baseline:** `875bc2d` (v2.4.15 / 148) · **This block:** `713c2b8` (v2.4.16 / 149)
**Device:** `5585fecc` (2201117TG, Android 13 / API 33) — attached, authorised, **used**
**Date:** 2026-10-31

This block works through the items §12.11.3 deferred, in the order the external review ranked them.
It is the first block in this project to change **native C** under the ownership rules the review
called release-blocking.

---

## A. Native RUDP lifetime — what was actually wrong

The refcount protocol (N-15) was already in place: `rudp_create` publishes `refcount = 1`,
`rudp_destroy` sets `destroying` and drops the owner's reference, `rudp_destroy_final` frees the
context **and closes the socket** only when the count reaches zero. The gap was at four entry points
that read the context without claiming it first.

| Site | Defect | Fix |
|---|---|---|
| `rudp_get_udp_fd` | `return ctx->udp_fd;` — **no reference at all**. The JNI layer calls it on a context a concurrent `softether_disconnect()` may be destroying, so the returned number could belong to an already-closed (or reused) socket | acquire → copy the `int` → release |
| `rudp_is_active` | five field reads with no reference; a teardown starting between them could report a half-destroyed context as active | one reference held across the whole decision |
| `rudp_poll` | `if (ctx == NULL || !ctx->inited) return;` — inspected a field **before** acquiring, i.e. possibly inside `rudp_destroy()` | acquire first; the `inited` branch releases and returns |
| `rudp_send` | same pre-check order (`inited`, `peer_addr_set`) | acquire first; **every** early exit releases |
| `nativeGetRudpSocketFd` (JNI) | `if (conn->rudp) return rudp_get_udp_fd(conn->rudp);` — no claim, while teardown CASes `conn->rudp` to NULL and destroys it. The sequence `read pointer → [free] → deref` was reachable on the poller thread | snapshot the fd under `connect_mutex`, exactly as the NAT-T sibling (`N-02`/`N-12`) already did. The mutex is `PTHREAD_MUTEX_RECURSIVE` (`softether_protocol.c:1039`) so a same-thread call cannot deadlock; both Java callers run once per connect, never in a poll loop |

`rudp_recv` already acquired first and needed no change — the review's line range included it, but the
code there was already correct.

---

## B. SSTP transport bounds

| Defect | Evidence | Fix |
|---|---|---|
| `Socket(host, port)` — OS default connect timeout (minutes), and `soTimeout` set only **after** the SSTP HTTP setup | `SSLTerminal.kt:150, 304` | `connect(InetSocketAddress, CONNECT_TIMEOUT_MS = 10 s)` + `soTimeout = HANDSHAKE_TIMEOUT_MS` **before** the handshake |
| Header loops appended one byte at a time with **no ceiling and no EOF check**: `read()` returning `-1` became `U+FFFF` and the loop never ended | proxy loop `:241-248`, SSTP loop `:283-294` | EOF → `ERR_UNEXPECTED_MESSAGE`; `MAX_HTTP_HEADER_BYTES = 16 KiB` on both loops |
| `inboundBuffer.limit(limit + readSize)` with `readSize == -1` → negative limit | `:383` | `readSize < 0` → `EOFException` (controlled shutdown, not `IllegalArgumentException` from a data-plane coroutine) |
| A DATA frame declaring < 8 bytes passed `4..bufferSize`, then `packetSize - 8` produced a **negative** payload length for `writePacket` | `IncomingManager.kt:131-151`, `process.kt:299-308` | `MIN_DATA_PACKET_SIZE = 8` (4 SSTP + 2 PPP HDLC + 2 PPP protocol) enforced in the dispatcher **and** the extractor |
| A reconnect called `controller?.kill(false, null)` and immediately built a new controller, so the old async cleanup could reach `service.close()` (`stopForeground` + `stopSelf`) after the new session started | `SstpVpnService.kt:270-285` | monotonic **session generation**; only the current generation may stop the service (`closeIfCurrent`) |
| `mutex.tryLock()` with no unlock | `Controller.kt:325` | fixed in v1.0.77; this block adds the generation guard on top |

---

## C. Publisher admission — the feed that ships inside the app

| Defect | Fix |
|---|---|
| `normalize_ip` validated IPv4 **syntax** only: loopback, RFC1918, link-local, CGNAT, multicast, reserved and unspecified all passed — and were then handed to `probe_tcp`, which opens a real connection | `is_public_ipv4` filters the dataset **before** `health_check()` and before the CSV is written; `dropped_non_public` is reported in the summary |
| `--published-raw-url` was written verbatim into `emergency_defaults.xml`, i.e. into the APK | `validate_published_url`: `https` + allowlisted host (`github.com`, `raw.githubusercontent.com`) + the `/morteza-taheri/SoftEthergai` path segment (same rule the update checker uses) |
| `requests.get()` followed redirects by default — any host, including a plaintext downgrade | `http_get` walks the chain itself (max 5 hops), refusing cross-host hops and `https → http` |

`ipaddress.is_global` alone was **not** sufficient: Python reports multicast (224.0.0.0/4) as global.
The predicate therefore spells out every non-unicast scope. A test caught this.

---

## D. Deliberately NOT changed (recorded in spec §12.12.4)

- **`NativeLifetimeRegistry` clearing ownership on a timeout.** The review is right that a timer is not
  proof that native work stopped — but their proposed fix ("retain the lease until native exit") turns
  a recoverable timeout into a permanent wedge, because the native side has no cancel-and-join. The
  honest fix is a native cancellation protocol or process isolation; that is its own block.
- **OpenVPN `clearDefaults()` → `mCheckRemoteCN = false`.** Changes which profiles import at all; needs
  its own compatibility testing.
- **Profile-encryption plaintext fallback.** Profiles live in app-private storage; failing closed would
  silently lose the user's profile without crossing a security boundary.
- **TLS 1.0/1.1 and `SSL_VERIFY_NONE`.** Raising the floor can refuse servers that only offer TLS 1.0;
  needs a device survey first.
- **Unauthenticated HTTP bootstrap sources / unsigned emergency CSV.** Provenance is a release-control
  design decision (signature + pinned key), not a code tweak.

---

## E. Verification

### Builds

| Target | Result |
|---|---|
| `:SoftEtherClient:assembleDebug` (native, 4 ABIs) | **BUILD SUCCESSFUL** — only the pre-existing `AttachCurrentThread` cast warning at `softether_jni.c:60` |
| `:sstpClient:compileDebugKotlin` | **BUILD SUCCESSFUL** |
| `:app:assembleProDebug` from `git archive HEAD` | **BUILD SUCCESSFUL** — 63,981,189 bytes |

### Automated tests

- `:app:testProDebugUnitTest` — **1402 tests / 1 skipped / 0 failures / 0 errors**
- `:SoftEtherClient:testDebugUnitTest` — **184 / 0 / 0 / 0**
- `tests/test_emergency_publisher_validation.py` — **29 passed** (public/loopback/RFC1918/link-local/
  CGNAT/multicast/reserved/unspecified/broadcast/malformed; https/host/lookalike-path/other-owner/bare
  path; same-host/cross-host/downgrade/plain-http redirects)
- `:app:lintProDebug` — **102 errors / 185 warnings / 9 hints** — unchanged from the baseline, zero new
  findings

### Physical device (build 149, installed `-r` over 148)

```
13:58:38  [AUTO] Trying #1 vpn601425201
13:58:40  ConnectionController: RUDP socket fd=100 protected from TUN routing   ← the changed JNI path ran
13:58:41  [AUTO] ATTEMPT_CLASS CONNECTED outcome=TUNNEL_UP protocol=SOFTETHER_UDP   ← the RUDP transport
          ping 8.8.8.8: 3/3 received, 0% loss, 384–509 ms
+30 s     pid unchanged (13844), tun0 up, ping 3/3 again, 0 signals
13:59:44  [AUTO] Disconnect requested from the connected state → tun0 = 0, process alive
```

- `Fatal signal` / `SIGSEGV` / `SIGABRT` / `libsoftether` crash lines: **0** across connect, 30 s of
  traffic, and teardown.
- The RUDP socket was protected and carried real traffic, i.e. the refcount changes did not break the
  data plane — which was the main risk of touching `rudp_send` / `rudp_poll` / `rudp_get_udp_fd`.

### Not verifiable in this block

The native race itself (needs a stress harness or a device under sustained connect/disconnect load), the
SSTP handshake bounds against a deliberately hostile peer, and the publisher against a live redirecting
source. All three are stated rather than implied.
