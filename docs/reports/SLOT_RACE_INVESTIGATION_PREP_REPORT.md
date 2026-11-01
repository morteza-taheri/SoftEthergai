# SLOT-RACE-INVESTIGATION-PREP — آیا Fix-A واقعاً handshake را محدود میکند؟

**فاز:** SLOT-RACE-INVESTIGATION-PREP (فقط-خواندنی)
**تاریخ:** 2026-10-18
**ریپو:** `C:\Dev\SoftEthergai` · شاخه `main` · HEAD `c69d2a0`
**ماهیت:** device-free. هیچ فرمان adb اجرا نشد. هیچ build/test/commit انجام نشد.

---

## §1 — حکم (Verdict)

**گزینه (a): Fix-A از نظر مکانیکی کافی است** — با یک قید درجه‌دوم که باید ثبت شود.

سه شرط لازم هر سه برقرارند:

1. handshake از **socket BIO** توکار OpenSSL می‌خواند، نه BIO سفارشی — `SSL_set_fd` در
   `aes_wrapper.c:433` و **هیچ** `BIO_new*` / `BIO_meth*` در کد پروژه وجود ندارد. (SOURCE CODE)
2. `SO_RCVTIMEO` روی fd جفت‌سوکت RUDP دقیقاً همان fd‌ای است که OpenSSL از آن `recv()` می‌کند،
   و fd در حالت **blocking** است (تنها `fds[0]` غیرمسدودکننده شده). (SOURCE CODE)
3. `EAGAIN` توسط هیچ لایه‌ای بلعیده نمی‌شود: `SSL_do_handshake` مقدار `-1` و
   `SSL_get_error` مقدار `SSL_ERROR_WANT_READ` را برمی‌گرداند، و حلقهٔ `max_attempts` فعال می‌شود.
   (SOURCE CODE + قرارداد مستند OpenSSL)

**قید درجه‌دوم (مهم):** `SO_RCVTIMEO` هر `recv()` را محدود می‌کند، **نه کل handshake** را. حلقهٔ
`max_attempts = 100` هر بار که `WANT_READ` می‌گیرد یک `recv()` تازه با همان مهلت ۴۰۰۰ms آغاز
می‌کند. بنابراین سقف عملی handshake برابر است با:

```
100 × (timeout_ms + 10ms) ≈ 100 × 4010ms ≈ 401s ≈ 6.7 دقیقه
```

یعنی Fix-A «بی‌نهایت» را به «۶.۷ دقیقه» تبدیل می‌کند، نه به «۴ ثانیه». برای بودجه‌های امروز
(کف ۳۰s و سقف ۶۰s) این یعنی verdict بومی هنوز **بعد از** تسلیم کنترلر می‌رسد. پیشنهاد Fix-5 در §5
همین شکاف را می‌بندد. (SOURCE CODE + محاسبه)

---

## §2 — پیش‌شرط‌ها

| بررسی | مقدار | برچسب |
|---|---|---|
| `git rev-parse HEAD` | `c69d2a0bdf3ebf64b2eae554a369757b57d31c9f` | SOURCE CODE |
| `git rev-parse origin/main` | `c69d2a0bdf3ebf64b2eae554a369757b57d31c9f` | SOURCE CODE |
| HEAD == origin/main | بله | SOURCE CODE |
| شاخه | `main` | SOURCE CODE |
| نسخهٔ spec | `1.0.34` | SOURCE CODE |
| تغییرات tracked | هیچ | SOURCE CODE |
| تعداد تگ | ۵ | SOURCE CODE |

هیچ‌یک از شرایط ABORT برقرار نشد.

---

## §3 — Q1: آیا handshake واقعاً از socket BIO می‌خواند؟

**پاسخ: بله.** (SOURCE CODE + قرارداد API)

مسیر کامل، با ارجاع:

| مرحله | محل | واقعیت |
|---|---|---|
| اتصال fd به SSL | `aes_wrapper.c:433` — `SSL_set_fd(ctx->ssl, socket_fd)` | تنها نقطهٔ اتصال در کل پروژه |
| BIO سفارشی | جست‌وجوی `BIO_new` / `BIO_set_nbio` / `BIO_ctrl` / `BIO_meth` در `softether-core/` و `jni/` | **صفر مورد** — همهٔ نتایج `BIO_METHOD` متعلق به هدرهای OpenSSL همراه است، نه کد پروژه |
| `SSL_set_bio` دستی | جست‌وجو در کد پروژه | **صفر مورد** |
| فراخوانی handshake | `aes_wrapper.c:471` — `SSL_do_handshake(ctx->ssl)` | بدون دخالت BIO واسط |

`SSL_set_fd` طبق قرارداد مستند OpenSSL یک `BIO_s_socket` می‌سازد و آن را برای rbio و wbio تنظیم
می‌کند؛ `BIO_s_socket` از `recv()`/`send()` مستقیم روی fd استفاده می‌کند. (INFERENCE از قرارداد
مستند API — نه از سورس)

**محدودیت صادقانه:** سورس OpenSSL در ریپو نیست. `SoftEtherClient/src/main/cpp/openssl/` فقط
`include/` و دو پوشهٔ تست (`tlslite-ng`، `wycheproof`) دارد؛ هیچ `.c` وجود ندارد. کتابخانه‌ها به
صورت **باینری پیش‌ساخته** در `SoftEtherClient/src/main/jniLibs/<abi>/libssl.a` و `libcrypto.a`
لینک می‌شوند (`CMakeLists.txt:23-36`). بنابراین پیاده‌سازی دقیق `sock_read` در این ریپو
**قابل خواندن نیست** و برچسب آن **UNKNOWN (در ریپو)** است. تنها راه تأیید قطعی، مشاهدهٔ رفتار روی
دستگاه است (§7).

---

## §4 — Q2: آیا `SO_RCVTIMEO` روی fd جفت‌سوکت منتشر می‌شود؟

**پاسخ: بله.** (SOURCE CODE)

### ۴.۱ — جفت‌سوکت و دو سر آن

`rudp_transport.c:1214`:

```c
// Socket pair: [0] = worker end, [1] = app-facing end
if (socketpair(AF_UNIX, SOCK_STREAM, 0, fds) != 0) { … }
if (rt_set_nonblocking(fds[0]) != 0) { … }   // :1218 — فقط سر worker
t->worker_fd = fds[0];                        // :1226
t->app_fd    = fds[1];                        // :1227 — هرگز غیرمسدودکننده نمی‌شود
```

`rudp_transport_get_fd` (`:1312-1314`) مقدار `t->app_fd` را برمی‌گرداند، و
`softether_try_nat_t_connect` آن را در `conn->socket_fd` می‌گذارد (`softether_protocol.c:1724`).

نتیجهٔ کلیدی: `fds[1]` یک `AF_UNIX` / `SOCK_STREAM` **blocking** است و تنها مصرف‌کنندهٔ آن
OpenSSL است. `rt_set_nonblocking` (`:1117-1121`) فقط روی `fds[0]` و `udp_fd` صدا زده می‌شود.
(SOURCE CODE)

### ۴.۲ — چه کسی داده را در جفت‌سوکت می‌نویسد

- `rt_pump_recv_to_pair` (`:681-694`): دادهٔ رسیده از سیم را با
  `send(t->worker_fd, …)` (`:688`) به سر worker می‌نویسد → همان داده روی `fds[1]` خواندنی می‌شود.
- `rt_pump_pair_to_send` (`:700-715`): خروجی اپ را با `recv(t->worker_fd, …)` (`:705`) از سر
  worker می‌خواند.

پس وقتی سرور هیچ داده‌ای نمی‌فرستد، worker هیچ چیزی روی `fds[1]` نمی‌نویسد و `recv()` روی
`fds[1]` تا بی‌نهایت مسدود می‌ماند — دقیقاً همان ۲۸.۵ ثانیه سکوت §7.3. (SOURCE CODE + PHYSICAL DEVICE)

### ۴.۳ — محل اعمال مهلت

`socket_apply_timeout` (`tcp_socket.c:469-494`) هر دو `SO_RCVTIMEO` و `SO_SNDTIMEO` را با
`setsockopt` روی همان fd تنظیم می‌کند و در صورت شکست `-1` برمی‌گرداند.

در شاخهٔ RUDP، ترتیب دقیقاً درست است (`softether_protocol.c:2904-2935`):

```
softether_disconnect(conn);                       // fd قبلی بسته می‌شود
result = softether_try_nat_t_connect(...);        // fd جدید = app_fd
if (result == ERR_NONE) {
    socket_apply_timeout(conn->socket_fd, conn->timeout_ms);   // :2935
    LOGD("[TLS] handshake entry (RUDP): …");                   // :2941
    result = perform_tls_handshake(conn, connect_host);         // :2953
```

هیچ پنجره‌ای بین `setsockopt` و handshake وجود ندارد، و `softether_try_nat_t_connect` خودش هیچ
`SO_RCVTIMEO`ای تنظیم نمی‌کند (تنها سایت‌های `setsockopt(SO_RCVTIMEO)` در کل `softether-core/`:
`tcp_socket.c:197`، `softether_protocol.c:1604`، `:3062`، `:4084`، و خود `socket_apply_timeout`).
(SOURCE CODE)

### ۴.۴ — آیا کسی مهلت را پاک می‌کند؟

**خیر.** تنها بازنویسیِ بعدی، تنظیم مجدد روی **۵۰۰۰ms** برای مسیر داده است
(`softether_protocol.c:3057-3062`) — یعنی یک `set`، نه یک `clear`. هیچ `tv = {0,0}` و هیچ
`SO_RCVTIMEO` صفرِ عمدی در مسیر handshake وجود ندارد. (SOURCE CODE)

### ۴.۵ — مقدار واقعی روی دستگاه

زنجیرهٔ مقدار:

```
ConnectionConfig.connectTimeoutMs (پیش‌فرض 30000 — ConnectionConfig.kt:42)
   ← AndroidConnectionAdapter.kt:629-630: 4/5 × T، کف 1500ms
   ← ConnectionController.kt:584: client.setTimeout(nativeHandle, config.connectTimeoutMs)
   ← softether_jni.c:551: conn->timeout_ms = value   (محدودهٔ مجاز 100..120000)
```

با `T = 5000ms` (تنظیم ذخیره‌شدهٔ دستگاه): `timeout_ms = 4000`. این عیناً با لاگ دستگاه می‌خواند:
`conn->timeout_ms=4000` در `DEVICE_VERIFY_AUTOCONNECT_FIX_3_REPORT.md:226`. (SOURCE CODE + PHYSICAL DEVICE)

### ۴.۶ — تصحیح یک استنباط نادرست در گزارش قبلی

توصیهٔ ۱ در §12 گزارش fix-3 می‌گوید:

> «`SO_RCVTIMEO` alone may be insufficient here because RUDP reads do not go through a plain
> `recv()` on the UDP fd — the bound likely has to live in the RUDP transport's read path»

این استنباط **نادرست** است و این فاز آن را رد می‌کند: OpenSSL روی **UDP fd** نمی‌خواند؛ روی
`fds[1]` جفت‌سوکت می‌خواند. آن `recv()` روی UDP متعلق به thread کارگر است (`recvfrom` در
`rudp_transport.c:941`) و ربطی به handshake ندارد. پس مهلت روی fd درست اعمال شده و نیازی به
تغییر مسیر خواندن RUDP نیست. (SOURCE CODE — ردِ INFERENCE قبلی)

---

## §5 — Q3: اگر Fix-A کافی نباشد، Fix-5 چه شکلی است؟

### ۵.۱ — پیشنهاد اصلی: قطع نردبان روی `EAGAIN` در حلقهٔ handshake

**محل دقیق:** `aes_wrapper.c:458-490`، بلافاصله پس از `SSL_get_error` در `:479`.

**شکاف:** حلقهٔ handshake، برخلاف `ssl_read`، **errno را نمونه‌برداری نمی‌کند**. `ssl_read`
دقیقاً همین درس را در AUTOCONNECT-AUDIT D-1 گرفته است (`aes_wrapper.c:570-600`): وقتی
`WANT_READ` با `errno == EAGAIN` همراه است، یعنی «مهلت سوکت منقضی شد»، نه «داده در راه است»، و
همان‌جا `break` می‌کند. حلقهٔ handshake این تفکیک را ندارد و ۱۰۰ بار دیگر تلاش می‌کند.

**تغییر پیشنهادی (۵ خط، هم‌شکل با اصل موجود):**

```c
        errno = 0;
        pthread_mutex_lock(&g_openssl_lock);
        result = SSL_do_handshake(ctx->ssl);
        const int hs_errno = errno;          // قبل از SSL_get_error نمونه‌برداری شود
        pthread_mutex_unlock(&g_openssl_lock);
        …
        if (ssl_error == SSL_ERROR_WANT_READ || ssl_error == SSL_ERROR_WANT_WRITE) {
            if (hs_errno == EAGAIN || hs_errno == EWOULDBLOCK) {
                LOGE("SSL handshake: SO_RCVTIMEO expired (errno=%d) after %d attempt(s)",
                     hs_errno, attempt + 1);
                break;                        // مهلت منقضی شد، نه «داده در راه»
            }
            … موجود …
        }
```

**اثر:** سقف handshake از `100 × 4000ms` به `~4000ms` می‌رسد، یعنی هم‌رده با مسیر TCP و با
بودجهٔ تلاش. (SOURCE CODE + INFERENCE از اثر)

**ریسک:** اگر `SSL_do_handshake` دادهٔ جزئی خوانده باشد و سپس مهلت منقضی شود، `break` زودهنگام
یک handshake سالم اما کند را قطع می‌کند. کاهش این ریسک: `break` فقط وقتی `attempt == 0` یا وقتی
هیچ بایتی از peer نرسیده باشد؛ یا یک سقف زمانی مطلق (مثلاً `timeout_ms`) روی کل حلقه. (INFERENCE)

### ۵.۲ — گزینه‌های جایگزین و ارزیابی

| گزینه | محل | مزیت | ریسک |
|---|---|---|---|
| **BIO سفارشی با deadline** | جایگزینی `SSL_set_fd` با `BIO_new` سفارشی در `aes_wrapper.c:433` | کنترل کامل، مهلت مطلق | پرهزینه؛ باید `BIO_meth_new`/`BIO_meth_set_read` نوشته شود؛ ریسک رگرسیون در کل مسیر TLS (هم TCP هم RUDP) |
| **مهلت مطلق دور `nativeConnectWithHub`** | لایهٔ Kotlin/کنترلر | ساده، بدون تغییر native | مشکل را «پنهان» می‌کند نه حل؛ thread بومی همچنان گیر می‌کند و slot را نگه می‌دارد |
| **`fds[1]` را non-blocking کردن** | `rudp_transport.c:1227` | `recv()` فوراً `EAGAIN` می‌دهد | **خطرناک**: مسیر دادهٔ بعدی (`ssl_read`) هم non-blocking می‌شود و بدون `poll` چرخهٔ بیهوده می‌زند |
| **کاهش `max_attempts`** | `aes_wrapper.c:454` | یک‌خطی | روی TCP هم اثر می‌گذارد؛ عدد جادویی جدید |
| **پیشنهاد ۵.۱** | `aes_wrapper.c:479` | هم‌شکل با اصل D-1 موجود، ۵ خط، فقط مسیر خطا را تغییر می‌دهد | ریسک ۵.۱ |

**توصیه:** اگر اجرای فردا نشان داد نردبان واقعاً می‌چرخد (یعنی خطوط `SSL handshake attempt N` با
فاصلهٔ ~۴ ثانیه تکرار می‌شوند)، Fix-5 باید گزینهٔ ۵.۱ باشد، نه BIO سفارشی. (INFERENCE)

### ۵.۳ — «اگر Fix-A کار کند، Fix-5 لازم نیست»

اگر اجرای فردا نشان دهد اولین `recv()` با مهلت برمی‌گردد و handshake در ≤ یک چرخه شکست می‌خورد،
Fix-5 لازم نیست. اما حتی در آن حالت، قید §1 (ضرب `max_attempts`) باقی است و فقط «کار نمی‌کند»
نمی‌شود — «کند کار می‌کند» می‌شود. (INFERENCE)

---

## §6 — تطبیق با شواهد دستگاه (§7.3 گزارش fix-3)

| ادعای گزارش fix-3 | سازگاری با سورس | برچسب |
|---|---|---|
| خط `[TLS] handshake entry` برای مسیر RUDP **نبود** | درست — این خط در v1.0.34 اضافه شد (`softether_protocol.c:2941`) | SOURCE CODE |
| صفر خط `SSL handshake attempt N` در هر ۵ اجرا | با سورس سازگار است: بدون `SO_RCVTIMEO`، اولین `recv()` هرگز برنمی‌گردد، پس `SSL_do_handshake` هرگز برنمی‌گردد و حلقه یک بار هم تکرار نمی‌شود | SOURCE CODE + PHYSICAL DEVICE |
| صفر خط `did not complete after 100 attempts` | همان دلیل بالا — `:510` هرگز اجرا نشد | SOURCE CODE |
| `conn->timeout_ms=4000`، `SO_RCVTIMEO applied=yes` روی مسیر TCP | زنجیرهٔ §4.5 عیناً ۴۰۰۰ را تولید می‌کند | SOURCE CODE + PHYSICAL DEVICE |
| «فقط teardown اجباری آن را پایان داد» | با سورس سازگار است: بستن fd از thread دیگر روی `recv()` مسدودِ بدون مهلت اثر قطعی ندارد؛ با مهلت، حداکثر ۴ ثانیه بعد برمی‌گردد | SOURCE CODE + INFERENCE |

**نکتهٔ مهم:** شواهد دستگاه **قبلی** (pre-fix) هستند. هیچ شاهدی دربارهٔ رفتار **پس از** Fix-A وجود
ندارد. (PHYSICAL DEVICE — pre-fix only)

---

## §7 — سه سازوکار و سیگنال دقیق logcat برای تأیید/رد

| # | سازوکار | سیگنال تأیید | سیگنال رد |
|---|---|---|---|
| **M1** | مهلت کار می‌کند: `SO_RCVTIMEO` روی جفت‌سوکت اعمال می‌شود، `EAGAIN` به OpenSSL می‌رسد، نردبان فعال می‌شود | `Applied socket timeout 4000 ms to fd=NN` سپس `[TLS] handshake entry (RUDP): … SO_RCVTIMEO applied=yes` سپس **حداکثر ~۴ ثانیه بعد** اولین `SSL handshake attempt 1: need more data (error=2)` و تکرار با فاصلهٔ ~۴ ثانیه | خط `handshake entry (RUDP)` می‌آید ولی تا >۱۰ ثانیه هیچ خط `attempt` نمی‌آید → `EAGAIN` بلعیده شده |
| **M2** | مهلت بلعیده می‌شود (لایهٔ واسط `EAGAIN` را retry می‌کند) | — | `handshake entry (RUDP) … applied=yes` + سکوت >۱۰ ثانیه + هیچ `attempt` |
| **M3** | fd از قبل non-blocking بود و مشکل جای دیگری است | `attempt` ها با فاصلهٔ ~۱۰ms (نه ~۴ ثانیه) و سپس `did not complete after 100 attempts` در ~۱ ثانیه | — |
| **M4** | `setsockopt` روی `AF_UNIX` شکست می‌خورد | — | `handshake entry (RUDP) … applied=no` + `TLS handshake over RUDP proceeding WITHOUT a socket timeout` |

**وضعیت M3 از قبل رد شده است:** اگر fd non-blocking بود، اجرای v1.0.33 (بدون Fix-A) باید خطوط
`attempt` تولید می‌کرد. صفر خط در ۵ اجرا یعنی fd مسدودکننده بوده. (SOURCE CODE + PHYSICAL DEVICE)

---

## §8 — ریسک‌هایی که فقط با مشاهدهٔ دستگاه روشن می‌شوند (UNKNOWN)

1. **آیا `BIO_s_socket` در این build خاص، `EAGAIN` را retry-flag می‌کند؟** قرارداد مستند OpenSSL
   می‌گوید بله، اما `libssl.a` پیش‌ساخته و سورسش در ریپو نیست. → **UNKNOWN**؛ تأیید با M1.
2. **آیا بستن fd از thread teardown، `recv()` مسدود را بیدار می‌کند؟** روی Linux رفتار تضمینی
   ندارد. با مهلت، این سؤال بی‌اهمیت می‌شود (حداکثر ۴s). → **UNKNOWN**، اما ریسک کاهش یافته.
3. **آیا worker واقعاً هیچ داده‌ای نمی‌فرستد؟** اگر keepalive های RUDP روی جفت‌سوکت نوشته شوند،
   `recv()` داده می‌گیرد و OpenSSL آن را به‌عنوان رکورد بد رد می‌کند (خطای متفاوت). → **UNKNOWN**؛
   سیگنال: خطای `wrong version number` یا `SSL handshake failed` بلافاصله.
4. **مقدار واقعی `T` روی دستگاه فردا.** اگر کاربر آن را تغییر داده باشد، `timeout_ms` و در نتیجه
   فاصلهٔ خطوط `attempt` تغییر می‌کند. → با خط `handshake entry (RUDP)` مستقیماً خوانده می‌شود.

---

## §9 — برچسب شواهد هر ادعا

| ادعا | برچسب |
|---|---|
| `SSL_set_fd` تنها نقطهٔ اتصال است | SOURCE CODE (`aes_wrapper.c:433`) |
| هیچ BIO سفارشی در پروژه نیست | SOURCE CODE (grep صفر مورد) |
| `SSL_set_fd` از `BIO_s_socket` استفاده می‌کند | INFERENCE از قرارداد مستند OpenSSL؛ پیاده‌سازی **UNKNOWN (در ریپو)** |
| `fds[1]` مسدودکننده و مخصوص OpenSSL است | SOURCE CODE (`rudp_transport.c:1214-1227`, `:1312`) |
| `SO_RCVTIMEO` روی همان fd اعمال می‌شود | SOURCE CODE (`softether_protocol.c:2935`) |
| مهلت پاک نمی‌شود؛ فقط به ۵۰۰۰ms بازنویسی می‌شود | SOURCE CODE (`:3062`) |
| `timeout_ms = 4000` روی دستگاه | SOURCE CODE (زنجیرهٔ §4.5) + PHYSICAL DEVICE (`fix-3:226`) |
| `EAGAIN` به `WANT_READ` تبدیل می‌شود | INFERENCE از قرارداد OpenSSL؛ **UNKNOWN** برای این build |
| حلقهٔ handshake `errno` را نمونه‌برداری نمی‌کند | SOURCE CODE (`aes_wrapper.c:458-490`) |
| `ssl_read` این کار را می‌کند (اصل D-1) | SOURCE CODE (`aes_wrapper.c:570-614`) |
| سقف عملی ≈ ۴۰۱ ثانیه | محاسبه از SOURCE CODE (`max_attempts=100`، `usleep(10000)`، `timeout_ms=4000`) |
| استنباط «باید در مسیر خواندن RUDP باشد» نادرست است | SOURCE CODE (ردِ INFERENCE قبلی) |
| رفتار post-fix روی دستگاه | **UNKNOWN** — هیچ شاهدی وجود ندارد |

---

## §10 — تضمین منفی

| ادعا | وضعیت |
|---|---|
| فرمان دستگاه / adb | **خیر** |
| build یا test | **خیر** |
| `git add` / `commit` / `push` | **خیر** |
| عملیات تگ | **خیر** |
| ویرایش source / test / spec | **خیر** |
| هر فایل تغییریافته جز ایجاد همین گزارش | **خیر** |
| C5 | **خیر** |
| `D:\PMC\Documents\Android\SoftEthergai` | **دست‌نخورده** |

تنها فایل ایجادشده: `SLOT_RACE_INVESTIGATION_PREP_REPORT.md` (untracked).

---

## §11 — چک‌لیست پیش‌پرواز اجرای فردا (پاسخ «آیا Fix-A کار کرد؟» در ۳۰ ثانیه)

به همین ترتیب grep کنید:

```bash
# 1) مهلت اعمال شد؟
grep -n "handshake entry (RUDP)" logcat.txt
#    انتظار: "… SO_RCVTIMEO applied=yes"  (اگر "no" → M4، Fix-A بی‌اثر)

# 2) نردبان فعال شد؟ (مهم‌ترین سیگنال)
grep -n "SSL handshake attempt" logcat.txt
#    صفر خط  → M2 (EAGAIN بلعیده شده) → Fix-5 لازم است
#    ≥۱ خط   → M1 (Fix-A کار می‌کند)

# 3) فاصلهٔ زمانی خطوط attempt
grep -nE "handshake attempt|handshake entry \(RUDP\)" logcat.txt
#    فاصلهٔ ~۴ ثانیه  → مهلت واقعاً منقضی می‌شود (M1)
#    فاصلهٔ ~۱۰ms     → M3 (fd non-blocking)

# 4) آیا نردبان تمام شد یا با teardown قطع شد؟
grep -nE "did not complete after 100 attempts|Native slot not released|Native slot released after" logcat.txt
#    "did not complete" → نردبان کامل چرخید (≈۴۰۱s) → قید §1 تأیید شد → Fix-5 لازم است
#    "Native slot released after" → Fix-A به teardown اجازهٔ اثر داد (بهبود واقعی)

# 5) شکست سریع یا خطای متفاوت؟
grep -nE "TLS handshake failed over RUDP|wrong version|SSL handshake failed" logcat.txt
```

**تفسیر یک‌خطی:**

- `applied=yes` + خط `attempt` با فاصلهٔ ~۴s → **Fix-A کار می‌کند**؛ اگر نردبان کامل چرخید، Fix-5
  (پیشنهاد §5.1) برای رساندن verdict به داخل بودجهٔ تلاش لازم است.
- `applied=yes` + صفر خط `attempt` → **Fix-A کار نمی‌کند**؛ M2.
- `applied=no` → **M4**؛ `setsockopt` روی `AF_UNIX` شکست خورده.

---

*پایان گزارش. کامیت نشده است.*
