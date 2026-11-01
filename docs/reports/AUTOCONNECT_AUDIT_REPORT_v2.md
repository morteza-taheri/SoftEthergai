# AUTOCONNECT-AUDIT REPORT v2 — رفتار فعلی AutoConnect (فاز فقط-خواندنی)

> **وضعیت:** جدید، untracked، **Commit نشده** · **تاریخ:** 2026-10-18 · **دستگاه:** DETACHED (بدون adb)
> **برچسب‌های شواهد:** `SOURCE CODE` / `TEST` / `PHYSICAL DEVICE` / `INFERENCE` / `UNKNOWN` / `PRIOR REPORT`
> **نکته:** فایل `AUTOCONNECT_AUDIT_REPORT.md` (اجرای قبلی) از قبل وجود داشته و **دست نخورده** باقی مانده است.

---

## §1 — حکم کلی (Verdict)

**PARTIAL.**

- **MATCH:** R1 (تنظیم‌پذیری در Settings)، R2 (سرور→پروتکل→توقف بر اساس timeout)، R6 (کلید خواندن timeout)، R7 (لایهٔ اجرای timeout)، R8 (تو در تو بودن حلقه) — همگی با کد منبع تأیید شدند (`SOURCE CODE`).
- **PARTIAL:** R3 (بودجهٔ اسمی هر تلاش = T و جمع اسمی داخل یک سرور = N×T است، اما زمان دیواری واقعی می‌تواند به‌واسطهٔ waitهای bounding‌شدهٔ اضافه فراتر رود یا با ترمینال‌ها کوتاه شود — اندازهٔ واقعی بدون دستگاه `UNKNOWN`)؛ R5 (قاعده در §6.7 R4/R5/R7 و §6.3 مستند است، اما **مقدار پیش‌فرضِ spec (5 s) با مقدار پیش‌فرضِ کد (12 s) ناسازگار است**، فرمول N×T در spec نیست، کلید Settings و قاعدهٔ exception-`break` ذکر نشده‌اند).
- **MISMATCH:** R4 («شکست غیر-timeout» همیشه به همان قاعدهٔ timeout عمل نمی‌کند — رفتار بسته به کلاس شکست متفاوت است؛ جدول کامل در §6).

منطقِ انتقال کاملاً در همین repo قابل‌خواندن است (Kotlin در `app/`، کد C در `SoftEtherClient/src/main/cpp/`) — هیچ رفتارِ حیاتی در nativeِ ناخوانا پنهان نیست (`SOURCE CODE`). شرطِ STOPِ «ابهام» فعال نشد.

---

## §2 — پیش‌شرط‌ها (Step 1 — در آغاز همین فاز)

| مورد | مقدار | برچسب |
|---|---|---|
| `git rev-parse HEAD` | `c5fa4362a0c1a39166112fd1f6be552418af17dd` | `SOURCE CODE` |
| `git rev-parse origin/main` | `c5fa4362a0c1a39166112fd1f6be552418af17dd` (برابر با HEAD ✓) | `SOURCE CODE` |
| شاخه | `main` ✓ | `SOURCE CODE` |
| تعداد تگ‌ها | **5** ✓ | `SOURCE CODE` |
| نسخهٔ spec | `**Document version:** 1.0.30` (خط 8) ✓ | `SOURCE CODE` |
| فایل‌های track‌شدهٔ تغییریافته | **هیچ** (فقط `??` : `.workbuddy-ai/`, `emergency/`, `scripts/`, `test-evidence/`) ✓ | `SOURCE CODE` |
| §1.6.22 حاضر است | بله — خط 1779 `#### 1.6.22 Spec Integrity and Concurrent Worktree Lesson (v1.0.30)` | `SOURCE CODE` |

**هیچ شرطِ ABORT فعال نشد.** `HttpFetcher.kt` قبلاً در `9d569c3` commit شده و تمیز است (`PRIOR REPORT` + تأیید `SOURCE CODE`). تستِ skip‌شدهٔ `HtmlParserOracleTest.softetherUdpOnlyNeverBorrowsOpenvpnTcp` مربوط به AutoConnect نیست (`PRIOR REPORT`). این فاز **هیچ build/test اجرا نکرد** — فقط متن تست‌ها خوانده شد (`TEST`).

---

## §3 — قاعدهٔ مورد انتظار operator (نقل‌قول عینی)

> EXPECTED RULE (operator, 2026-10-18):
>   R1. timeout T (seconds) is settable in Settings.
>   R2. For each SERVER:
>         for each selected PROTOCOL (in selection order):
>             allow T seconds for that protocol on that server
>             if it does not connect in T seconds -> next protocol
>         if no protocols left -> next server
>       If no servers left -> stop.
>   R3. Max time on one server = N_protocols × T seconds.
>   R4. On non-timeout failure (auth error, DNS fail, socket
>       refusal): same transition rule? or different?
>   R5. This rule MUST be documented in PROJECT_SPECIFICATION.md.

(derived) R6 — Where is the timeout READ from (Settings key)? / R7 — Where is the timeout ENFORCED (which layer — Kotlin, native, OkHttp)? / R8 — Loop nesting: server-outer/protocol-inner, or flat?

## §4 — قراردادِ فعلیِ spec (نقل‌قول عینی با شمارهٔ خط، یا «spec silent»)

> نکته: §11 این سند «Binding Design Decisions» است و به timeout مربوط نمی‌شود؛ قراردادِ AutoConnect در **§6** (بخصوص §6.7 R4/R5/R7)، **§10**، **§16.4** و **§17** است.

**R1 — تنظیم‌پذیری در Settings — spec آدرس می‌دهد:**
- خط 2364: ``- The timeout for an attempt is **per server, per protocol** and is **configurable in Settings** (the `attemptTimeoutMs` plumbing in `AutoModeEngine.ensure()`, §10.1).``

**R2 — قاعدهٔ حلقه و انتقال روی timeout — spec آدرس می‌دهد:**
- خط 2353 (§6.7 R4): ``- One-attempt-per-server (§6.5): if a server fails on its highest-priority usable protocol, the next usable protocol follows, then the next server.``
- خط 2367 (§6.7 R5): ``- **Stop condition:** If the armed tunnel wait (`awaitArmedTunnel`, §6.4) does not complete within `attemptTimeoutMs`, the attempt is declared failed and the next (server, protocol) pair is tried.``
- خطوط 2299–2301 (§6.5): `### 6.5 One-Attempt-Per-Server (§10)` و ``Auto Mode enforces that each server is tried at most once per protocol per run. If a server fails, it is not retried within the same run — the controller moves to the next candidate.``
- خط 2391 (§6.7 R7a): ``- **(a)** The end of the server list is reached (all sorted candidates attempted without a verified tunnel) → run ends with `ERROR_NO_SERVER` (§6.3).``
- **یادداشت نگارشی:** نمودار §6.3 (خط 2264) تخت نوشته شده: `    for each (server, protocol) in ordered candidates:` — spec چارت lifecycle را flat بیان می‌کند، در حالی که متن R4 (خط 2353) معنای تو-در-تو را صریح دارد. (`SOURCE CODE` — نقل از spec)

**R3 — حداکثر زمانِ یک سرور = N×T — spec SILENT:**
- هیچ خطی از spec فرمول «max time on one server = N_protocols × T» را بیان نمی‌کند. نزدیک‌ترین متن، خط 2366 که فقط استقلالِ پنجره‌ها را می‌گوید: ``- If the user has enabled multiple protocols, each protocol attempt has its **own independent** timeout window (a server that fails on protocol A within 5 s does not consume protocol B's 5 s budget).`` — سقفِ جمعِ N×T اعلام نشده است. (`SOURCE CODE` — نقل از spec)

**R4 — قاعدهٔ انتقال روی شکستِ غیر-timeout — spec PARTIAL:**
- NATIVE_BUSY (بلوکیِ محلی): §16.4 قاعدهٔ 1 (خط 3467): ``1. **A NATIVE_BUSY refusal is NOT a server failure.** It must never trigger server rotation. The refusal is scoped to `sessionPolicy.sessionGeneration`.`` — **مستند**.
- شکستِ generic: §6.3 خطوط 2274–2279: `        if native-busy:` / `            // do NOT rotate — the refusal is local, not a server problem` / `            continue to next (server, protocol)` / `        else:` / `            // server genuinely did not come up; try next` / `            continue`
- `NATIVE_ERROR` (شکستِ سریعِ native): فقط به‌صورتِ اتفاقی در تاریخچهٔ §1.6.20 (خطوط 1724 و 1742: `` `AttemptOutcome.NATIVE_ERROR` ``) — **قرارداد رفتاریِ صریح در §6.3/§6.7 ندارد** (`SOURCE CODE` — نقل از spec).
- **شکستِ exception در `connect()` (که در کد به «next server» می‌پرد): spec SILENT.**
- **توقف‌های ترمینال (`ERROR_NATIVE_UNRESOLVED` / `ERROR_VPN_PERMISSION`):** خط 2393 (§6.7 R7): ``- **Stop condition:** There is **no other implicit stop condition.** AutoConnect does not stop on a single server failure, on a NATIVE_BUSY refusal (§6.4 — it continues without rotating), or on any timeout short of exhausting the list.`` — اشاره‌ای به ترمینالِ «slot هرگز آزاد نشد» یا «VPN permission» در این متن نیست (`SOURCE CODE` — نقل از spec).

**R5 — مستندسازیِ قاعده — spec PARTIAL:** §6.7 R4/R5/R7 + §6.3/§6.5/§6.4 (نقل‌قول‌های بالا) + خط 2369: ``- **Testable:** With default Settings, each protocol attempt is bounded by ~5 s; the documented implementation must apply `attemptTimeoutMs` to **both** the armed `select` wait (`awaitArmedTunnel`) **and** the native race (`ConnectionConfig.connectTimeoutMs`), not merely log it.`` — **اما:** پیش‌فرضِ اعلام‌شده 5 ثانیه است (خط 2365: `- **Default: 5 seconds per protocol attempt.**` و خط 711: `... app settings remained at their defaults (Auto-Mode timeout 5 s, ...)`) در حالی که کد پیش‌فرض 12 ثانیه دارد (§5.1). کلید Settings، فرمول N×T، کسرِ native (4/5) و قاعدهٔ exception-`break` در spec نیستند.

**R6 — کلید Settings — spec SILENT:** نام کلید `SETTING_AUTO_MODE_TIMEOUT_SECONDS` و تابع `getAutoModeTimeoutSeconds()` در هیچ خطی از spec نیامده؛ فقط «configurable in Settings» (خط 2364).

**R7 — لایهٔ اجرا — spec آدرس می‌دهد (بدون جزئیاتِ کسر):** خط 2369 (نقل‌قول بالا) هر دو لایه را الزامی می‌کند؛ §10.1 خط 2651: ``- **Verified tunnel wait:** Waits for `STATE_CONNECTED` via `TunnelStateWatcher`, with `attemptTimeoutMs` bound`` و خط 2656: ``- `DEFAULT_ATTEMPT_TIMEOUT_MS` — per-attempt timeout (configurable, see `AutoModeEngine`)``؛ §17 خط 3501 مقدار را خالی می‌گذارد: ``| `DEFAULT_ATTEMPT_TIMEOUT_MS` | — | `AutoModeController.kt` | Auto Mode per-attempt timeout |``. **نسبتِ 4/5 و کفِ 1500 ms در spec نیست.** (`SOURCE CODE` — نقل از spec)

**R8 — تو-در-تویی حلقه — spec نیمه‌صریح:** خط 2353 معنای تو-در-تو را می‌گوید (نقل‌قول بالا)، اما چارت §6.3 (خط 2264) تخت است؛ معادل بودنِ ترتیبِ تلاش‌ها بین این دو قرائت با §5.2 تأیید می‌شود (`INFERENCE`).

## §5 — نقشهٔ رفتارِ پیاده‌سازی (file:line)

### §5.1 — منبعِ حقیقتِ Settings (خواندن/نوشتن/پیش‌فرض)

- **کلید:** `SETTING_AUTO_MODE_TIMEOUT_SECONDS` — `DataUtil.kt:537`: ``const val SETTING_AUTO_MODE_TIMEOUT_SECONDS: String = "SETTING_AUTO_MODE_TIMEOUT_SECONDS"`` (`SOURCE CODE`)
- **خواندن:** `DataUtil.kt:332-333`: ``fun getAutoModeTimeoutSeconds(): Int =`` / ``getIntSetting(SETTING_AUTO_MODE_TIMEOUT_SECONDS, 12).coerceIn(5, 60)`` — **پیش‌فرض 12 ثانیه، دامنهٔ 5..60**؛ ذخیره در `SharedPreferences` (`DataUtil.kt:34/40`) (`SOURCE CODE`)
- **نوشتن (UI):** `SettingsScreen.kt:190` (`remember { mutableStateOf(dataUtil.getAutoModeTimeoutSeconds()) }`)، ردیف تنظیمات در خط 527-530 با عنوان `R.string.setting_auto_timeout_label`، و picker در خطوط 1014-1022: ``val values = (5..60).toList()``، ``options = values.map { "$it seconds" }``، ``dataUtil.setAutoModeTimeoutSeconds(values[index])`` (`SOURCE CODE`)
- **رشتهٔ عنوان:** `strings.xml:266`: `<string name="setting_auto_timeout_label">Auto Mode Connection Timeout</string>` (`SOURCE CODE`)
- **دو جایِ خواندنِ همان کلید (یک منبع حقیقت):**
  1. `AutoModeEngine.kt:74-75`: `// §3 §4 §5 §14 Read timeout from Settings per attempt (per-server, not total)` و ``attemptTimeoutMs = du.getAutoModeTimeoutSeconds().toLong() * 1000,`` (`SOURCE CODE`)
  2. `AndroidConnectionAdapter.kt:588-600`: کامنتِ `AutoModeEngine already reads this exact setting ... keeps ONE source of truth (the preference)` و ``val attemptTimeoutMs = dataUtil.getAutoModeTimeoutSeconds().toLong() * 1000`` (`SOURCE CODE`)
- **اشتقاقِ وابسته:** `AutoModeEngine.kt:82-83`: ``nativeSlotAwaitMs = (du.getAutoModeTimeoutSeconds().toLong() * 1000L * 5L)`` / ``.coerceAtMost(AutoModeController.NATIVE_SLOT_AWAIT_MAX_MS),`` — یعنی **waitِ آزادسازیِ slot = min(5×T, 60 s)** (سقف‌ها: `AutoModeController.kt:391` `NATIVE_SLOT_AWAIT_MS = 30_000L` و `:397` `NATIVE_SLOT_AWAIT_MAX_MS = 60_000L`) (`SOURCE CODE`)
- **سه عددِ متناقض برای یک تنظیم:** پیش‌فرضِ Settings در کد = **12 s** (`DataUtil.kt:333`)؛ fallbackِ سازندهٔ کنترلر = ``DEFAULT_ATTEMPT_TIMEOUT_MS = 25_000L`` (`AutoModeController.kt:331` — فقط وقتی caller مقدار ندهد؛ production همیشه مقدارِ Settings را می‌دهد)؛ اعلامِ spec = **5 s** (خط 2365). (`SOURCE CODE` + `INFERENCE` دربارهٔ اینکه fallback در production استفاده نمی‌شود)
- تنظیمِ probeِ reachability مستقل و **غیر-تنظیم‌پذیر** است: `AutoModeEngine.kt:89` ``timeoutMs = 1200,`` (پیش‌pre-pass، نه بودجهٔ اتصال). (`SOURCE CODE`)

### §5.2 — ساختارِ حلقه (R8)

حلقهٔ **سرور-بیرونی / پروتکل-درونی** — تودرتو، نه تخت (`SOURCE CODE`):

- بیرونی — `AutoModeController.kt:1365`: `for (server in servers) {`
  - استثناهای `continue` قبل از شمارش تلاش: تکراری (`:1369` `if (!attempted.add(key)) continue`)، حذفِ session پس از ۲ wedge (`:1375-1381` با `WedgeTracker.EXCLUSION_THRESHOLD = 2` در `:1892`)، و نبودِ پروتکلِ قابل‌استفاده (`:1396-1400`).
  - `val supported = usableProtocols(server, priorities)` (`:1396`) که ترتیبِ انتخابِ کاربر را حفظ می‌کند: `AutoModeController.kt:1046-1048`: ``fun usableProtocols(...): List<AutoModeProtocol> = priorities.filter { it.isUsableFor(candidate) }`` — و `priorities` از ``protocolPriorityProvider = { du.getAutoModeProtocolPriority() }`` می‌آید (`AutoModeEngine.kt:67`). (`SOURCE CODE`)
- درونی — `AutoModeController.kt:1416`: `for (protocol in supported) {`
  - ترتیبِ هر تلاش: ``adapter.armTunnelWatch(protocol)`` (`:1435`) ← ``adapter.connect(server, protocol)`` (`:1438`) ← ``val outcome = adapter.awaitArmedOutcome(protocol, attemptTimeoutMs)`` (`:1450`). (`SOURCE CODE`)
- **شمارشِ N×M:** تک‌تکِ زوج‌های (سرور, پروتکل) حداکثر یک‌بار در هر run زده می‌شوند (§6.5 spec + `attempted`-set در `:1362/1369`). (`SOURCE CODE`)
- **نمودارِ معادلِ spec (§6.3 خط 2264 `for each (server, protocol) in ordered candidates:`) با این حلقهٔ تودرتو از نظر ترتیبِ تلاش هم‌ارز است، چون لیستِ candidates بر حسبِ سرور و سپس اولویتِ پروتکل پیموده می‌شود (`INFERENCE`).

### §5.3 — نقطهٔ اجرای timeout (کجا تایمر واقعاً زندگی می‌کند) — R7

**دو لایه، هر دو قابل‌خواندن در همین repo؛ نه OkHttp** (`SOURCE CODE`):

**لایهٔ ۱ — Kotlin coroutine (۱۰۰٪ T):**
- فراخوانی: `AutoModeController.kt:1450` ``val outcome = adapter.awaitArmedOutcome(protocol, attemptTimeoutMs)`` (و retry: `:1542`).
- اجرا: `AndroidConnectionAdapter.kt:333` (مسیرِ ساده) ``withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false`` و `:349-356` (مسیرِ select):
  ```
  withTimeoutOrNull(timeoutMs) {
      kotlinx.coroutines.selects.select<Boolean> {
          deferred.onAwait { true }
          busy.onAwait { false }
          failed.onAwait { false }
      }
  } ?: false
  ```
  کامنتِ `:344-348` صریح می‌گوید: `The timeout is the SAME user-configured per-attempt budget (attemptTimeoutMs, PROJECT_SPECIFICATION.md §6.7 R5)` و `the native race is bounded separately via ConnectionConfig.connectTimeoutMs`.
- تفسیرِ نتیجه: `AndroidConnectionAdapter.kt:413-429` `awaitArmedOutcome` — `TUNNEL_UP` / `NATIVE_ERROR` (شکستِ گزارش‌شده) / `NATIVE_BUSY` / در غیر اینصورت `TIMED_OUT`.

**لایهٔ ۲ — native (۴⁄۵ × T، کف ۱۵۰۰ ms):**
- `AndroidConnectionAdapter.kt:74-75`: ``private const val NATIVE_TIMEOUT_FRACTION_NUM = 4L`` / ``..._DEN = 5L``؛ `:84`: ``MIN_NATIVE_CONNECT_TIMEOUT_MS = 1_500L``
- `:616-618`: ``val nativeConnectTimeoutMs = (attemptTimeoutMs * NATIVE_TIMEOUT_FRACTION_NUM / NATIVE_TIMEOUT_FRACTION_DEN).coerceAtLeast(MIN_NATIVE_CONNECT_TIMEOUT_MS)`` ← `:636`: ``connectTimeoutMs = nativeConnectTimeoutMs.toInt(),`` داخلِ `ConnectionConfig` (`:602-615` کامنتِ D-1: بودجهٔ native باید **سخت‌گیرانه کوچک‌تر** از waitِ کنترلر باشد تا verdictِ native زودتر برسد).
- انتقال به native: `ConnectionController.kt:584` (و `:1352`): ``client.setTimeout(nativeHandle, config.connectTimeoutMs)`` ← `SoftEtherClient.kt:203-208`: ``fun setTimeout(handle: Long, timeoutMs: Int) { if (handle != 0L) nativeSetOption(handle, OPTION_TIMEOUT, timeoutMs.toLong()) }``
- JNI: `softether_jni.c:543-552`: `case 1: // OPTION_TIMEOUT` با range-check `[100,120000]` و ``conn->timeout_ms = (int)value;``
- مصرف در کدِ اتصالِ C (`softether_protocol.c`): `socket_connect_timeout(..., conn->timeout_ms)` در خطوط **2769/2778/3991**؛ handshakeِ TLS در **2849-2856**؛ auth در **1600** (``int auth_timeout_ms = conn->timeout_ms > 0 ? conn->timeout_ms : 30000;``)؛ transport race در **2424** (``ctrl->race_timeout_ms = conn->timeout_ms;   // STEP 6: no 5s floor``)؛ RUDP در **1714** و NAT-T در **1689**؛ پیش‌فرضِ اولیهٔ `conn->timeout_ms = 30000` در **1012** که با JNI بازنویسی می‌شود. (`SOURCE CODE`)
- **OkHttp ربطی به AutoConnect ندارد:** timeoutهای OkHttp فقط در `HttpFetcher.kt:108-109` (`.connectTimeout(10, TimeUnit.SECONDS)` / `.readTimeout(15, ...)`) برای دریافتِ لیست سرورها هستند. (`SOURCE CODE`)
- **آریتمتیک (`INFERENCE`):** در دامنهٔ مجازِ Settings (5..60 s)، 4⁄5×T همیشه ≥ 4000 ms > کفِ 1500 ms است، پس کف در production غیرفعال است؛ و 4000..48000 داخلِ range جدولیِ JNI `[100,120000]` است.

### §5.4 — انتقال روی TIMEOUT (R2)

با `outcome == AttemptOutcome.TIMED_OUT` (`AutoModeController.kt:309-310`: `/** The wait expired with no tunnel and no refusal. A genuine attempt failure. */`) (`SOURCE CODE`):

1. `AutoModeController.kt:1578-1583`: لاگِ صریح:
   ```
   "[AUTO] TIMED_OUT after ${attemptTimeoutMs}ms on " +
       "${server.hostname ?: server.ip}/${protocol.id}; " +
       "cleanup then next protocol (no same-server retry)"
   ```
   و کامنتِ `:1575-1577`: `TIMED_OUT is terminal for THIS attempt only — there is deliberately no same-server retry. The rotation below (next protocol, then next server) is its recovery.`
2. ثبتِ wedge (حداکثر یک‌بار برای هر سرور در هر run): `:1587-1590` با `recordWedge` (`:1743-1745`، آستانهٔ حذفِ session = 2 در `:1892`).
3. پاک‌سازی: `:1607-1608` ``adapter.log("[AUTO] Protocol failed: ${protocol.id}")`` و ``adapter.disconnect()``
4. انتظارِ آزادشدنِ slot (برای غیر از `NATIVE_ERROR`): `:1620-1632` ``if (outcome != AttemptOutcome.NATIVE_ERROR && !adapter.awaitNativeSlotRelease(nativeSlotAwaitMs))`` — در حالتِ عادی همین‌جا برمی‌گردد؛ اگر آزاد نشد → **توقفِ ترمینال** `ERROR_NATIVE_UNRESOLVED` (`:1628-1631`).
5. چون `connectedProtocol == null` است (`:1411`)، حلقهٔ درونی ادامه می‌یابد → **پروتکل بعدی، روی همان سرور** (`:1416`). پس از اتمامِ حلقهٔ پروتکل → `:1661` ``adapter.log("[AUTO] Connection failed: ...")`` → **سرور بعدی** (`:1365`). پس از اتمامِ حلقهٔ سرور → **توقف** (`§5.6`). (`SOURCE CODE`)

**پاسخ به R2 operator: تطابق کامل برای مسیر timeout** — T برای هر پروتکل روی هر سرور، سپس پروتکل بعدی، سپس سرور بعدی، سپس توقف.

### §5.5 — انتقال روی شکستِ غیر-TIMEOUT (R4)

چهار کلاسِ شکست، **چهار رفتار متفاوت** (`SOURCE CODE`):

**(a) `NATIVE_ERROR` — شکستِ سریعِ native (پورتِ ردشده، handshakeِ مردود، جوابِ غیر-SoftEther):** → **پروتکل بعدی (مانند timeout)** — `AutoModeController.kt:1599-1605`:
```
"[AUTO] NATIVE_ERROR on ${server.hostname ?: server.ip}/" +
        "${protocol.id}; the stack rejected this endpoint, " +
        "moving to the next protocol"
```
سپس `:1607-1608` disconnect، و چون `:1620` شرطِ `outcome != NATIVE_ERROR` دارد، **waitِ slot رد می‌شود** و حلقهٔ درونی مستقیم ادامه می‌یابد. (`SOURCE CODE`)
> تعریف: `AutoModeController.kt:312-327` — کامنتِ D-4 می‌گوید این verdictِ زودهنگام قبلاً کل بودجهٔ T را می‌سوزاند.

**(b) `NATIVE_BUSY` — بلوکیِ محلی (slot هنوز در اختیارِ عملیاتِ قبلیِ ماست):** → **پروتکلِ همان را یک‌بار دوباره امتحان کن؛ در غیر اینصورت توقفِ کل run** — `AutoModeController.kt:1486-1546`:
- `:1509` ``val released = adapter.awaitNativeSlotRelease(nativeSlotAwaitMs)``
- اگر آزاد نشد: `:1514-1522` لاگِ `"[AUTO] Native slot not released within ${nativeSlotAwaitMs}ms ...; stopping"` + `giveUpWithUnresolvedNativeOperation()` + ``setState(AutoModeState.Error(ERROR_NATIVE_UNRESOLVED))`` + `return` → **توقفِ ترمینال**.
- اگر آزاد شد: `:1524-1546` — ``adapter.log("[AUTO] Native slot released; retrying ${protocol.id} ...")`` ← arm+connect دوباره ← ``val retryOutcome = adapter.awaitArmedOutcome(protocol, attemptTimeoutMs)`` (`:1542`) ← اگر `TUNNEL_UP` نبود، به مسیرِ شکستِ عادی برمی‌گردد → پروتکل بعدی. (`SOURCE CODE`)
> کامنتِ D-2 در `:1478-1502` سیاست قبلی (دو retryِ ثابتِ 2 s سپس توقفِ کل run) را لغوشده اعلام می‌کند.

**(c) exception در `connect()` (شکستِ سطحِ ترانسپорт — socket refusal و غیره):** → **سرور بعدی (نه پروتکل بعدی!)** — `AutoModeController.kt:1445-1448`:
```
} catch (e: Exception) {
    adapter.log("[AUTO] Connect threw ${e.javaClass.simpleName}: ${e.message}")
    adapter.disconnect()
    break // transport-level failure: move to the next server
}
```
این `break` حلقهٔ **درونی** (`for (protocol in supported)`) را می‌شکند؛ پروتکل‌های باقی‌ماندهٔ همان سرور **امتحان نمی‌شوند** و کنترل به حلقهٔ سرور می‌رسد. (`SOURCE CODE`)
> **این دقیقاً جایی است که قاعدهٔ یکنواختِ R4 operator نقض می‌شود** — با timeout (که پروتکلِ بعدی را می‌زند) متفاوت است.

**(d) `VpnPermissionMissingException`:** → **توقفِ ترمینال** — `:1439-1444` (و مسیرِ retry: `:1531-1536`): ``adapter.log("[AUTO] VPN permission missing; stopping Auto Mode")`` + ``setState(AutoModeState.Error(ERROR_VPN_PERMISSION))`` + `return`. (`SOURCE CODE`)

**(e) کنترلِ اضافیِ «مالکیت tunnel» (Stage 2F-F.28 Fix C+H):** قبل از اعلامِ شکست، اگر tunnelِ خودِ اپ عملاً برقرار باشد → **موفقیت** نه شکست — `:1569-1573` ``if (adapter.isOwnTunnelEstablished()) { ... treating attempt as connected ... break }``. (`SOURCE CODE`)

**خلاصهٔ جدول انتقال (`SOURCE CODE`):**

| وضعیتِ پایانِ تلاش | انتقال |
|---|---|
| `TUNNEL_UP` / tunnel برقرار (`:1462`, `:1569`) | **موفقیت → پایانِ run** (`:1635-1658`) |
| `TIMED_OUT` (`:1578`) | پروتکلِ بعدی (بدون retryِ همان پروتکل) |
| `NATIVE_ERROR` (`:1599`) | پروتکلِ بعدی (بدون waitِ slot) |
| `NATIVE_BUSY` آزادشدنِ slot (`:1524`) | retryِ **همان** پروتکل، سپس در صورت شکست → پروتکلِ بعدی |
| `NATIVE_BUSY` بدون آزادشدن (`:1510`) | **توقفِ کل run** — `ERROR_NATIVE_UNRESOLVED` |
| exception در `connect()` (`:1445`) | **سرورِ بعدی** (پروتکل‌های باقی‌مانده رد می‌شوند) |
| `VpnPermissionMissing` (`:1439`) | **توقفِ کل run** — `ERROR_VPN_PERMISSION` |
| skip کاربر (`:1454-1460`) | **سرورِ بعدی** (`break // user skip: next SERVER (not next protocol)`) |

### §5.6 — شرایطِ توقف (Stop condition)

1. **پایانِ لیستِ سرورها** → `AutoModeController.kt:1664-1666`: ``adapter.log("[AUTO] All compatible servers failed")`` / ``setState(AutoModeState.Error(ERROR_NO_SERVER)) // no auto loop back to #1`` / `job = null` (`SOURCE CODE`)
2. **لیستِ سرورها از ابتدا خالی/بدون سرورِ سازگار** → `:1356-1359` و `:1349-1352` (`ERROR_NO_SERVER`) (`SOURCE CODE`)
3. **توقفِ ترمینالِ native** → `ERROR_NATIVE_UNRESOLVED` (`:1520`، `:1629`) (`SOURCE CODE`)
4. **توقفِ ترمینالِ مجوز** → `ERROR_VPN_PERMISSION` (`:1442`، `:1534`) (`SOURCE CODE`)
5. **لغوِ توسط کاربر (press مجدد)** → بررسیِ `currentCoroutineContext().isActive` در `:1366` و `:1417` (`if (!currentCoroutineContext().isActive) return`) (`SOURCE CODE`)
6. **راه‌اندازیِ مجددِ run با دکمه** (self-healing ندارد — `:1665` `// no auto loop back to #1`) (`SOURCE CODE`)

> نکتهٔ spec (`SOURCE CODE` — نقل از spec): خط 2393 ادعا می‌کند `There is **no other implicit stop condition.**` علاوه بر دو شرطِ R7 — در حالی که موارد ۳ و ۴ در کد وجود دارند و در آن متنِ spec نیامده‌اند.

## §6 — جدولِ شکاف (GAP TABLE — R1..R8)

| # | انتظارِ operator | واقعیت (file:line) | حکم |
|---|---|---|---|
| **R1** | timeout T (ثانیه) در Settings قابل تنظیم است | کلید `SETTING_AUTO_MODE_TIMEOUT_SECONDS` (`DataUtil.kt:537`)، getter با clamp 5..60 و پیش‌فرض 12 (`DataUtil.kt:332-333`)، pickerِ UI در `SettingsScreen.kt:1014-1022` با `values = (5..60)`، عنوان `strings.xml:266` | **MATCH** `SOURCE CODE` |
| **R2** | برای هر سرور ← هر پروتکلِ انتخابی (به ترتیبِ انتخاب): T ثانیه؛ timeout → پروتکلِ بعدی؛ اتمامِ پروتکل‌ها → سرورِ بعدی؛ اتمامِ سرورها → توقف | حلقهٔ سرور `AutoModeController.kt:1365` ← حلقهٔ پروتکل `:1416` (ترتیبِ کاربر از `usableProtocols` `:1046-1048` حفظ می‌شود)؛ wait با T در `:1450`؛ timeout → ادامهٔ حلقهٔ درونی `:1578/1607-1608` → پروتکلِ بعدی؛ اتمامِ درونی → `:1661` → سرورِ بعدی؛ اتمامِ بیرونی → `:1664-1666` `ERROR_NO_SERVER` | **MATCH** `SOURCE CODE` (نکته: برای exceptionِ `connect()` رفتار متفاوت است — به R4) |
| **R3** | حداکثر زمانِ یک سرور = N_protocols × T | بودجهٔ اسمیِ هر تلاش دقیقاً T است (`AutoModeEngine.kt:75`؛ اجرای `AndroidConnectionAdapter.kt:349`؛ نسخهٔ native 4⁄5×T در `:616-618`)، پس **جمعِ بودجهٔ تلاش‌ها = N×T**. اما زمانِ دیواری: به ازایِ هر تلاشِ شکست‌خورده یک `awaitNativeSlotRelease(min(5T, 60s))` (`AutoModeEngine.kt:82-83` + `AutoModeController.kt:1620`) و teardownِ ≤5s (`AndroidConnectionAdapter.kt:235` `TEARDOWN_TIMEOUT_MS`) و در NATIVE_BUSY یک retryِ کاملِ T (`:1542`) اضافه می‌شود؛ برعکس، wedge-exclusion (`:1375`) و ترمینال‌ها (`:1520/:1442`) می‌توانند کوتاه‌تر کنند. | **PARTIAL** — نابودیِ اسمی N×T درست است؛ سقفِ دیواریِ دقیق = N×(T + min(5T,60s) + 5s) در بدترین حالت (`INFERENCE` از مقادیرِ bounding)؛ مقدارِ واقعیِ دستگاهی **`UNKNOWN`** |
| **R4** | شکستِ غیر-timeout (auth/DNS/refusal): همان قاعده یا متفاوت؟ | **متفاوت، بسته به کلاس** (جدولِ §5.5): `NATIVE_ERROR` → پروتکلِ بعدی (مانند timeout) `:1599`؛ exception در `connect()` → **سرورِ بعدی** (پروتکل‌های باقی‌مانده رد می‌شوند) `:1445-1448`؛ `NATIVE_BUSY` → retryِ همان پروتکل `:1524-1546` یا در صورت گیرکردنِ slot → **توقفِ کل run** `:1514-1522`؛ `VpnPermissionMissing` → **توقف** `:1439-1444` | **MISMATCH** نسبت به قاعدهٔ یکنواختِ «همان انتقال» (کد عمداً چهار رفتار متمایز دارد؛ `SOURCE CODE`) — البته هیچ‌کدام hang نمی‌کنند، پس «انتقالِ نه-توقف-نگه‌داشتن» برقرار است |
| **R5** | این قاعده MUST در `PROJECT_SPECIFICATION.md` مستند شود | مستند: §6.7 R4 (خط 2353: «next usable protocol follows, then the next server»)، §6.7 R5 (خطوط 2360-2371: per-server per-protocol، Settings، هر دو لایه)، §6.7 R7 (2387-2395: توقف‌ها)، §6.3 (2262-2281)، §6.5 (2299-2301)، §16.4 (3465-3472). **شکاف‌ها:** (i) پیش‌فرضِ spec **5 s** (خط 2365/711) ≠ کدِ **12 s** (`DataUtil.kt:333`) ≠ fallbackِ **25 s** (`AutoModeController.kt:331`)؛ (ii) فرمولِ N×T هیچ‌جا نیست؛ (iii) کلیدِ Settings و کسرِ 4⁄5 در spec نیستند؛ (iv) قاعدهٔ exception-`break` (§5.5c) و ترمینال‌های 3-4 (§5.6) در spec نیستند یا با خط 2393 تناقض دارند | **PARTIAL** `SOURCE CODE` |
| **R6** | timeout از کجا READ می‌شود (کلیدِ Settings)؟ | کلید `SETTING_AUTO_MODE_TIMEOUT_SECONDS` (`DataUtil.kt:537`) ← `getAutoModeTimeoutSeconds()` (`DataUtil.kt:332-333`) ← دو خواندن: `AutoModeEngine.kt:75` (بودجهٔ کنترلر) و `AndroidConnectionAdapter.kt:600` (بودجهٔ native)؛ SharedPreferences (`DataUtil.kt:34/40`) | **MATCH** `SOURCE CODE` |
| **R7** | timeout در کدام لایه ENFORCE می‌شود (Kotlin / native / OkHttp)؟ | **Kotlin:** `withTimeoutOrNull(timeoutMs)` در `AndroidConnectionAdapter.kt:333` و `:349-356` (۱۰۰٪ T). **Native:** `ConnectionConfig.connectTimeoutMs` = 4⁄5×T با کف 1500 (`AndroidConnectionAdapter.kt:616-618,636`) → `ConnectionController.kt:584` `client.setTimeout` → JNI `softether_jni.c:543-552` `OPTION_TIMEOUT` → `conn->timeout_ms` مصرف‌شده در `softether_protocol.c:1600/1714/2424/2769/2849/3991`. **OkHttp: نه** (فقط `HttpFetcher.kt:108-109` برای دریافتِ لیست) | **MATCH** `SOURCE CODE` — هر دو لایه دقیقاً همان‌طور که spec خط 2369 می‌خواهد بسته‌اند |
| **R8** | تو در تو (server-outer/protocol-inner) یا تخت؟ | **تو در تو:** `AutoModeController.kt:1365` (بیرونی) / `:1416` (درونی). نمودارِ §6.3 spec (خط 2264) تخت نوشته شده ولی هم‌ارز است (§5.2) | **MATCH** `SOURCE CODE` |

**خلاصهٔ حکم: PARTIAL** — رفتارِ هستهٔ R1/R2/R6/R7/R8 با قاعدهٔ operator می‌خواند؛ R3 در سطحِ بودجه درست ولی سقفِ دیواری دقیق نیست (`UNKNOWN` بدون دستگاه)؛ R4 عمداً چند-شاخه است (یکنواخت نیست)؛ R5 مستند اما با ناسازگاریِ عددِ پیش‌فرض و چند شکافِ سکوت.

## §7 — تست‌هایی که رفتار را قبلاً pin کرده‌اند (`TEST` — خواندنِ متنِ تست، بدون اجرا)

**روی مسیرِ timeout/چرخش:**
- `AutoModeControllerTest.kt:2187` `timedOutRotatesCursorWithoutSameServerRetry` (T7): سرورِ دو-پروتکلی که هر دو `TIMED_OUT` می‌شوند → هر پروتکل دقیقاً یک‌بار dial می‌شود (`:2210-2217`)، run با `ERROR_NO_SERVER` تمام می‌شود (`:2205-2209`)، لاگِ `TIMED_OUT after` + `no same-server retry` باید باشد (`:2218-2221`)، و هرگز وارد مسیرِ retryِ بلوکی نمی‌شود (`:2225-2228`).
- `AutoModeControllerTest.kt:2143` `releasedSlotRetryFailureMovesOnToTheNextProtocol`: شکستِ retry پس از آزادشدنِ slot → پروتکلِ بعدی.
- `AutoModeControllerTest.kt:2002` `secondRefusalAfterReleaseRotatesToTheNextProtocol`.
- `AutoModeControllerTest.kt:2242` `timedOutThenNativeBusyOnNextProtocolWaitsAndRetries` (T8): `TIMED_OUT` روی یک پروتکل سپس `NATIVE_BUSY` روی بعدی → انتظار + retry.
- `AutoModeControllerTest.kt:866` `skipToNextServerMovesToNextCandidateWithoutWaiting`: skip کاربر → سرورِ بعدی بدون انتظار.

**روی پِلُمبِ timeout (دو-لایه‌ای):**
- `AutoModeEndToEndTest.kt:212` `connectSoftEtherPlumbsAttemptTimeoutMs` (source-shape): `AndroidConnectionAdapter.kt` باید شاملِ `connectTimeoutMs = nativeConnectTimeoutMs.toInt()` (`:222-225`)، ثابت‌های کسر (`:230-236`)، `getAutoModeTimeoutSeconds()` (`:237-240`)، و `withTimeoutOrNull(timeoutMs)` + `select<Boolean>` (`:241-246`) باشد.
- `AutoModeNativeBusyTest.kt:501` `the default attempt timeout is unchanged`: ``assertEquals(25_000L, AutoModeController.DEFAULT_ATTEMPT_TIMEOUT_MS)`` (`:505`) — **عددِ 25 s را pin می‌کند، نه 12 s و نه 5 s را.**
- `AutoModeNativeBusyTest.kt:590/627` `autoModeSkipDoesNotHangAwaitArmedTunnel` / `autoModeTunnelErrorDoesNotHangAwaitArmedTunnel`: انتظارِ مسلح‌شده باید در بودجهٔ per-attempt تمام شود (قراردادِ FIXِ BUG 2/3 — کامنت در `:508-522`).

**روی چرخشِ سرور/توقف:**
- `AutoModeNativeBusyTest.kt:356` `genuine failures still rotate protocols and servers`؛ `:261` `native-busy refusal does not advance through the protocols of one server`؛ `:241` `a slot that never releases still does not rotate to a second server` (توقف به‌جای چرخش)؛ `:309` `native-busy refusal never reports ERROR_NO_SERVER`.
- `AutoModeLifecycleF28Test.kt:167` `TEST C - timeout with an owned tunnel established is treated as connected`؛ `:365` `TEST H - a timeout leaves no owned tunnel behind`.
- `AutoModeServerCursorTest.kt:74/93/111`: `currentServerIndex` دقیقاً موقعیتِ سرورِ در حال تلاش را نشان می‌دهد.
- `AutoModeEndToEndTest.kt:140/195`: cursor پس از اتمامِ سرورها جلو می‌رود.

**چیزی که هیچ تستی pin نمی‌کند (شکافِ تست):**
- اینکه پیش‌فرضِ Settings **12 s** است یا اینکه spec **5 s** می‌گوید (تصادفی میان `12/25/5` هیچ‌کدام با هم دیگر را نگرفته‌اند — `INFERENCE` از فقدانِ assertion؛ تنها assertionِ موجود 25_000 است).
- **سقفِ N×T برای یک سرور** — هیچ تستی زمانِ جمع‌شدهٔ یک سرور را نمی‌سنجد (`UNKNOWN`/شکافِ تست).
- انتقالِ exception-در-`connect()` (§5.5c) — تستی که `break`-به-سرور-بعدی را pin کند پیدا نشد (`SOURCE CODE` — جست‌وجو در `app/src/test`).

---

## §8 — مواردی که تأییدشان نیاز به مشاهدهٔ دستگاه دارد (`UNKNOWN`)

1. **زمانِ دیواریِ واقعیِ هر تلاش روی دستگاه ≈ T** — سطحِ کد فقط bound می‌دهد؛ اندازه‌گیریِ زمان در این فاز ممنوع بود (`UNKNOWN`).
2. **اینکه verdictِ native (4⁄5×T) همیشه قبل از timeoutِ کنترلر (T) می‌رسد** — طراحیِ D-1 این را می‌خواهد (`AndroidConnectionAdapter.kt:602-615`)؛ تأییدِ race در اجرا دستگاهی است (`UNKNOWN`).
3. **زمانِ واقعیِ یک سرور در برابرِ N×T** — بستگی به اینکه آیا `awaitNativeSlotRelease` در عمل فوراً برمی‌گردد یا تا سقفِ min(5T,60s) می‌ماند (`UNKNOWN`).
4. **رفتارِ NATIVE_BUSY/teardown روی دستگاهِ واقعی** (آزادشدنِ slot در چه بازه‌ای) — `UNKNOWN`.
5. **ادعای `isOwnTunnelEstablished` و `TunnelStateWatcher` در شرایطِ real-world** — خواندنِ کد کاملِ شرایطِ مرزیِ آن را نداشتیم (`UNKNOWN`).

> نکته: منطقِ تصمیمِ خودِ حلقه هیچ `UNKNOWN` ندارد — Kotlin و native هر دو در repo قابل‌خواندن‌اند؛ شرطِ STOPِ «ابهام در منطقِ انتقال» برقرار نشد (`SOURCE CODE`).

## §9 — پیشنهادها (فقط پیشنهاد — هیچ‌کدام آغاز نشده و هیچ ویرایشی انجام نشده)

**(a) تغییراتِ فقط-مستنداتی (اگر operator عددِ پیش‌فرضِ 12 s را مرجع بداند):**
1. اصلاحِ عددِ پیش‌فرضِ spec از **5 s** به **12 s** در §6.7 R5 (خط 2365) و خط 711 — یا بالعکس، اگر 5 s مدنظر است، تغییرِ کد (که دیگر «فقط-مستنداتی» نیست).
2. افزودنِ فرمولِ **«بودجهٔ اسمیِ تلاش‌های یک سرور = N×T؛ سقفِ دیواری با اضافه‌های §5.5/§5.6»** به §6.7 R5 — یا صراحتاً نوشتنِ اینکه N×T فقط بودجهٔ تلاش است نه سقفِ دیواری.
3. ثبتِ کلیدِ `SETTING_AUTO_MODE_TIMEOUT_SECONDS` و دامنهٔ 5..60 در نزدیکِ §10.1/§17.
4. ثبتِ جدولِ کاملِ انتقالِ §5.5 (چهار رفتارِ غیر-timeout) و ترمینال‌های §5.6 — یا نرم‌کردنِ ادعای مطلقِ خط 2393 (`no other implicit stop condition`).
5. ثبتِ کسرِ 4⁄5 و کفِ 1500 ms در §6.4/§10.5 (کنترلِ دو-لایه‌ای خط 2369 بدون جزئیاتِ کسر ناقص است).

**(b) اصلاحِ کد (فقط اگر operator قاعدهٔ یکنواختِ R4 یا سقفِ دقیقِ N×T را الزامی بداند):**
1. اگر «exception در `connect()` → پروتکلِ بعدی» مدنظر است: تغییرِ `AutoModeController.kt:1448` از `break` به ادامهٔ حلقهٔ پروتکل — **تصمیمِ operator می‌خواهد؛ این فاز شروع نکرد.**
2. اگر سقفِ دیواریِ سختِ N×T مدنظر است: بازنگری در `nativeSlotAwaitMs` (5T/60s) — باز هم تصمیمِ بعدی.
3. رفعِ ناسازگاریِ عددِ پیش‌فرض (تک‌عددی‌سازیِ 5/12/25).

**(c) افزودنی‌های تست (پیشنهادی):**
1. تستی که پیش‌فرضِ `getAutoModeTimeoutSeconds()` (12) و clamp (5..60) را pin کند — یک‌خطی که ناسازگاریِ 5-vs-12 را می‌گرفت.
2. تستی که سقفِ N×T (یا مدلِ واقعیِ آن) را با virtual-time بسنجد.
3. تستی که `break`-به-سرور-بعدیِ exception-در-`connect()` (§5.5c) را رفتاری pin کند — `connectSoftEtherPlumbsAttemptTimeoutMs` الان source-shape است و «plumbed-and-ignored» را تشخیص نمی‌دهد (`INFERENCE`).
4. تستی که عددِ `DEFAULT_ATTEMPT_TIMEOUT_MS` را با مقدارِ Settingsِ پیش‌فرض هم‌خوان کند (یا توضیحِ دلیلِ تفاوتِ 25-vs-12).

**(d) بخشِ پیشنهادیِ spec (فقط پیشنهاد — ویرایشی نشده):** یک زیربخشِ نُرمَتیو مثلاً **§6.8 «Per-Attempt Timeout and Rotation Contract»** شامل: تودرتوییِ حلقه، پنجرهٔ مستقلِ هر پروتکل، **جدولِ کاملِ انتقال برای هر 4 مقدارِ `AttemptOutcome` + exception + skip + permission**، ترمینال‌های §5.6، و مدلِ واقعیِ سقفِ زمانِ سرور. (§6.7 دکمه را پوشش می‌دهد؛ §6.8 حلقهٔ تلاش را.)

---

## §10 — تضمینِ منفی (Negative assurance)

در این فاز:

1. **هیچ فایلِ موجودی ویرایش نشد** — `PROJECT_SPECIFICATION.md`، تمام `*.kt`، تمام `docs/reports/*`، و `AUTOCONNECT_AUDIT_REPORT.md` (اجرای قبلی) همگی دست‌نخورده ماندند (`SOURCE CODE` — تمیزیِ `git status` پس از نوشتن، در ادامهٔ همین پاسخ تأیید می‌شود).
2. **تنها فایلِ ایجادشده:** `AUTOCONNECT_AUDIT_REPORT_v2.md` — جدید، **untracked**، **commit نشد** (طبق درخواستِ کاربر برای نامِ متفاوت).
3. **هیچ `git add` / `commit` / `push` / `tag` / `reset` / `checkout` اجرا نشد** — HEAD طبق روال `c5fa4362…` باقی ماند.
4. **هیچ build یا test اجرا نشد** — نه Gradle، نه unit-test؛ فقط متنِ منابع و تست‌ها خوانده شد.
5. **هیچ دستورِ adb/دستگاهی اجرا نشد** — دستگاه DETACHED ماند.
6. **`D:\PMC\Documents\Android\SoftEthergai` لمس نشد.**
7. **هیچ بخشِ §22 / §1.6.x / §11.x / §12.5.x ویرایش نشد** (اصلِ این فاز: هیچ ویرایشِ tracked).
8. هر ادعای این گزارش برچسبِ شواهد دارد؛ جایی که قابل‌تعیین از منبع نبود، `UNKNOWN` ثبت شده و **حدس زده نشد**.
9. **STOP:** پس از این گزارش متوقف می‌شوم؛ فازِ بعدی به‌صورتِ مستقل پیشنهاد نمی‌شود — توصیه‌های §9 صرفاً «پیشنهادِ ثبت‌شده» و آغازنشده‌اند.

*پایان گزارش.*
