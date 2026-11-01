# DEVICE-VERIFY-AUTOCONNECT-FIX-4 — گزارش راستی‌آزمایی دستگاه

**تاریخ:** 2026-10-19
**دستگاه:** 5585fecc — `2201117TG` (spes)، Android 13 (SDK 33)
**مبنا:** `c69d2a0bdf3ebf64b2eae554a369757b57d31c9f` — spec v1.0.34، §1.6.26
**APK:** `4ABC463606B83217D75422136DA6678097DAAC666D63BF5AB3B7BA6A624B1435`

---

## §1 — حکم (Verdict)

**دسته‌بندی: E — «غیرمنتظره» (unexpected).**

سناریوی شکستی که این فاز برای راستی‌آزمایی طراحی شده بود **روی این دستگاه و در این شبکه بازتولید نشد**. هیچ‌کدام از پنج اجرا به ترمینال `NATIVE_BUSY` نرسیدند؛ هر پنج اجرا با موفقیت Connected شدند.

سیگنال قاطع: در کل نشست، `SLOT_UNRELEASED_DIAG` **صفر** بار و `Native slot released after` **۲۴** بار ثبت شد. در مبنای v1.0.33 همین endpoint در **۵/۵** اجرا به `SLOT_UNRELEASED_DIAG waited≈30000ms` رسیده بود. (PHYSICAL DEVICE)

سه یافتهٔ تعیین‌کننده:

1. **Fix-A هرگز اجرا نشد (نه اینکه شکست بخورد).** سایت RUDP در `softether_protocol.c:2935` تنها زمانی وارد می‌شود که `softether_try_nat_t_connect` موفق شود. روی این دستگاه، rendezvous با `error=6` شکست می‌خورد و کنترل پیش از رسیدن به handshake برمی‌گردد. شاهد: `handshake entry (RUDP)` **صفر** بار در ۵ اجرا. (PHYSICAL DEVICE + SOURCE CODE)
2. **Fix-B هرگز اجرا نشد.** هیچ اجرایی با `ERROR_NATIVE_UNRESOLVED` تمام نشد، پس ترمینال `NATIVE_BUSY` رخ نداد و شاخهٔ جدید wedge-on-terminal صفر بار فراخوانی شد. شاهد: رشتهٔ `Recording a wedge on the NATIVE_BUSY terminal` **صفر** بار. (PHYSICAL DEVICE)
3. **بهبودِ کاربری محقق شد، اما نه به‌واسطهٔ v1.0.34.** هر ۵ اجرا Connected شدند؛ اما علت قابل‌اثبات، تغییر رفتار خود endpoint است (شکست سریع rendezvous) نه کد جدید. (INFERENCE)

**نتیجه:** هیچ‌یک از دو اصلاح v1.0.34 در این نشست **تأیید** یا **رد** نشد. پیش‌شرط شکست ناپدید شد. (INFERENCE)

---

## §2 — پیش‌شرط‌ها

| مورد | مقدار | برچسب |
|---|---|---|
| `git rev-parse HEAD` | `c69d2a0bdf3ebf64b2eae554a369757b57d31c9f` | SOURCE CODE |
| `git rev-parse origin/main` | همان (برابر) | SOURCE CODE |
| شاخه | `main` | SOURCE CODE |
| تغییرات tracked | هیچ | SOURCE CODE |
| `**Document version:**` | `1.0.34` | SOURCE CODE |
| تعداد تگ | ۵ | SOURCE CODE |
| `adb devices` | `5585fecc  device` | PHYSICAL DEVICE |
| مدل / اندروید | `2201117TG` / 13 (SDK 33) | PHYSICAL DEVICE |
| `SETTING_AUTO_MODE_TIMEOUT_SECONDS` | `5` (بدون تغییر) | PHYSICAL DEVICE |

مشتقات تنظیمات: `native timeout_ms = 5 × 1000 × 4/5 = 4000ms`، `slot-wait budget = max(5×5, 30) = 30000ms`. (SOURCE CODE — `AndroidConnectionAdapter.kt:629`)

**نکتهٔ ترابری USB:** در میانهٔ کار، daemon شبیه‌ساز adb بین دو فراخوانی چند بار از بین رفت و دستگاه موقتاً از لیست حذف شد. طبق مجوز بریف، `adb kill-server` / `start-server` انجام و کار ادامه یافت. هیچ تنظیم دستگاهی تغییر نکرد. (PHYSICAL DEVICE)

---

## §3 — نصب و دود (Smoke)

| گام | نتیجه | برچسب |
|---|---|---|
| SHA-256 درخت کاری | `4ABC4636…1435` — مطابق مبنا | TEST |
| `install -r` | `Success` (بدون پاک‌سازی داده) | PHYSICAL DEVICE |
| `versionCode` | `143` | PHYSICAL DEVICE |
| `pm path` | `/data/app/~~g53nIg98HqGDoETYbs1T_Q==/…/base.apk` | PHYSICAL DEVICE |
| APK کشیده‌شده و دوباره هش‌شده | `4ABC4636…1435` — **یکسان با build ممیزی‌شده** | TEST |
| اجرا (smoke) | PID `18596`، بدون FATAL/ANR | PHYSICAL DEVICE |
| ترجیح زمان‌سنج | `5` | PHYSICAL DEVICE |

باینریِ نصب‌شده بایت‌به‌بایت همان build ممیزی‌شده است؛ پس هر رفتار مشاهده‌شده به کد `c69d2a0` نسبت‌دادنی است. (TEST)

---

## §4 — اجرا ۱ (تنها اجرای «کامل» با چند سرور)

بازه: `07:45:01` → `07:47:38` (۱۵۷ ثانیه)، ۴ سرور، ۳ wedge، پایان: **Connected**.

```
07:45:01.150  [AUTO] Started                      (timeout = 5s, 109 servers, 88 reachable)
07:45:02.801  [AUTO] Trying #1 vpn131251792
07:45:03.077  Connecting to 112.69.69.79:1446
07:45:07.272  Failed to connect to server          ← TCP+TLS پس از ~۴.۲s
07:45:07.272  TCP+TLS failed (1); falling back to parallel UDP race
07:45:07.272  Parallel race: 3 transports, race timeout 4000 ms
07:45:11.377  VPNCONNECT watermark failed — server may not be SoftEther
07:45:11.377  Direct login failed (2); retrying via NAT-T + RUDP fallback
07:45:11.377  NAT-T rendezvous for 112.69.69.79:1446 ...
07:45:11.699  NAT-T rendezvous failed: error=6      ← پیش از handshake برمی‌گردد
07:45:11.699  Authentication failed: 1 (TCP connection failed)
07:45:21.510  [AUTO] Native slot released after 13525ms
07:45:26.576  Attempt after the slot was released did not come up (NATIVE_BUSY)
07:45:35.167  NATIVE_BUSY x2 on vpn131251792; recording a wedge
...
07:47:34.706  [TLS] handshake entry: conn->timeout_ms=4000, socket_fd=96, SO_RCVTIMEO applied=yes
07:47:38.937  Tunnel established / Connected successfully  (vpn636797683)
```

**تحلیل:** سرور #1 (همان `vpn131251792` مبنای v1.0.33) این بار در ۱۳.۵ ثانیه رها شد و چرخه ادامه یافت. مسیر RUDP هرگز به `perform_tls_handshake` نرسید، چون `NAT-T rendezvous failed: error=6` در `softether_protocol.c:2904` مقدار غیر-`ERR_NONE` برمی‌گرداند و بلوک `if (result == ERR_NONE)` — که سایت Fix-A داخل آن است — اجرا نمی‌شود. (SOURCE CODE + PHYSICAL DEVICE)

---

## §5 — اجرا ۲ و ۳ (پی‌درپی، سریع)

| اجرا | بازه | سرور | زمان | wedge | نتیجه |
|---|---|---|---|---|---|
| ۲ | `07:48:33` → `07:48:39` | `vpn661075949` (#5) | ۶s | ۰ | Connected |
| ۳ | `07:48:44` → `07:48:52` | `vpn948322276` (#6) | ۸s | ۰ | Connected |

**نکتهٔ روش:** فشار دادن دکمهٔ توان حتی در حالت Connected یک **اجرای تازه** آغاز می‌کند (teardown + `[AUTO] Started`). بنابراین «یک ضربه = یک اجرا». در اجرای دوم، دو ضربهٔ پشت‌سرهم دو اجرا را در یک capture انداخت؛ دادهٔ اجرای سوم از بافر زندهٔ همان نشست استخراج شد (`run02b_live.log`). هیچ اجرایی حذف یا بازسازی نشد. (PHYSICAL DEVICE)

---

## §6 — اجرا ۴ و ۵

| اجرا | بازه | سرورها | wedge | نتیجه |
|---|---|---|---|---|
| ۴ | `07:49:44` → `07:51:42` (۱۱۸s) | `vpn137307590`، `vpn917250768`، `vpn531263335` | ۲ | Connected |
| ۵ | `07:52:29` → `07:53:15` (۴۶s) | `vpn390261346`، `vpn451071809` | ۱ | Connected |

الگوی تکرارشونده در هر سرور شکست‌خورده:

```
Native slot released after ~13.4s
Attempt after the slot was released did not come up (NATIVE_ERROR)   ← ~۰.۱s
Teardown finished but NATIVE CLEANUP UNCONFIRMED (NATIVE_UNRESOLVED)
Native slot released after ~6.0s
NATIVE_BUSY x2 on <server>; recording a wedge
Connection failed: <server>
```

یعنی هر سرور شکست‌خورده ≈ ۵۵ ثانیه هزینه دارد: دو پروتکل × (۱۳.۵s تلاش + ۳.۵s teardown) به‌علاوهٔ retry. (PHYSICAL DEVICE)

**استثناء مهم:** در اجرای ۵ یک بار `Teardown confirmed complete (NATIVE_CONFIRMED)` ثبت شد — یعنی مسیر تأییدشدهٔ teardown هم روی این دستگاه کار می‌کند، نه فقط مسیر `NATIVE_UNRESOLVED`. (PHYSICAL DEVICE)

---

## §7 — آهنگ تلاش‌های handshake

**اندازه‌گیری: ممکن نشد — صفر رخداد.**

`grep -c "SSL handshake attempt"` روی تمام captureها = **۰**.

این «بلعیده‌شدن EAGAIN» نیست؛ توضیح ساختاری دارد: آن خط تنها در شاخهٔ `SSL_ERROR_WANT_READ / WANT_WRITE` نوشته می‌شود (`aes_wrapper.c:479`). صفر بودن آن یعنی هر handshake یا در همان فراخوانی اول موفق شد، یا با خطای غیر-WANT (مثل `SSL_ERROR_SYSCALL`) شکست خورد. (SOURCE CODE)

پنج `handshake entry` ثبت‌شده — همه از نوع **TCP** (بدون پسوند `(RUDP)`) و همه با `applied=yes`:

| # | زمان | `socket_fd` | `timeout_ms` | `SO_RCVTIMEO` |
|---|---|---|---|---|
| ۱ | `07:47:34.706` | 96 | 4000 | yes |
| ۲ | `07:48:35.820` | 96 | 4000 | yes |
| ۳ | `07:48:48.666` | 96 | 4000 | yes |
| ۴ | `07:51:38.840` | 99 | 4000 | yes |
| ۵ | `07:53:12.358` | 96 | 4000 | yes |

**این پنج خط تأیید می‌کنند که دیسکرمیناتور v1.0.34 روی مسیر TCP زنده و درست است** (`timeout_ms` منطبق با انتظار ۴۰۰۰، `applied=yes`). (PHYSICAL DEVICE)

---

## §8 — آهنگ در برابر بودجهٔ کنترلر

قابل اندازه‌گیری نبود، چون هیچ handshake ای به مرحلهٔ تلاش مجدد نرسید.

اما یک اندازه‌گیری جایگزین به‌دست آمد: **مدت عملیات بومی هر سرور شکست‌خورده ≈ ۱۳.۵ ثانیه** است (`Native slot released after 13525ms` / `13434ms` / `13343ms` / `13426ms` / `13642ms`). این عدد از بودجهٔ ۳۰٬۰۰۰ms کنترلر **کمتر** است — یعنی در این نشست، عملیات بومی همیشه پیش از انقضای بودجه تمام شد و ترمینال هرگز فعال نشد. (PHYSICAL DEVICE)

در مبنای v1.0.33 همین عملیات هرگز تمام نمی‌شد (۲۸.۵ ثانیه سکوت و سپس teardown اجباری). (PRIOR REPORT — `DEVICE_VERIFY_AUTOCONNECT_FIX_3_REPORT.md` §7.3)

---

## §9 — شواهد Fix-B (wedge)

| سنجه | مقدار | برچسب |
|---|---|---|
| `NATIVE_BUSY x2 … recording a wedge` (مسیر per-run، v1.0.32) | **۶** | PHYSICAL DEVICE |
| `Recording a wedge on the NATIVE_BUSY terminal` (مسیر جدید، v1.0.34) | **۰** | PHYSICAL DEVICE |
| `excluded for this session` | **۰** | PHYSICAL DEVICE |
| ترمینال `ERROR_NATIVE_UNRESOLVED` | **۰** | PHYSICAL DEVICE |

شش سرورِ wedge‌خورده (هرکدام ۱ wedge): `vpn131251792`، `vpn752839599`، `vpn275348493`، `vpn137307590`، `vpn917250768`، `vpn390261346`.

**چرا حذف (exclusion) مشاهده نشد — دو علت ساختاری:**

1. هیچ سروری دو بار انتخاب نشد. کنترلر موقعیت خود را حفظ می‌کند: `Continuing from server #5 of 88`، سپس `#6`، `#7`، `#10`. پس سرور wedge‌خورده تا چرخش کامل ۸۸ سرور دوباره کاندید نمی‌شود. (PHYSICAL DEVICE)
2. آستانهٔ حذف ۲ است و هر سرور فقط ۱ wedge گرفت.

نتیجه: **مسیر حذف در جریان طبیعی این نشست قابل مشاهده نبود** — نه به این دلیل که کار نمی‌کند، بلکه چون پیش‌شرط انتخاب مجدد رخ نداد. (INFERENCE)

---

## §10 — نتیجهٔ کاربری

| اجرا | زمان تا Connected | سرور |
|---|---|---|
| ۱ | ۱۵۷s | `vpn636797683` |
| ۲ | ۶s | `vpn661075949` |
| ۳ | ۸s | `vpn948322276` |
| ۴ | ۱۱۸s | `vpn531263335` |
| ۵ | ۴۶s | `vpn451071809` |

**۵ از ۵ اجرا Connected شدند.** هیچ اجرایی به «Auto Mode failed» نرسید. در مبنای v1.0.33، **۰ از ۵** اجرا Connected شده بود. (PHYSICAL DEVICE)

میانگین زمان تا اتصال: ۶۷ ثانیه. بدترین حالت: ۱۵۷ ثانیه (۴ سرور). بهترین: ۶ ثانیه.

---

## §11 — بررسی کرش / ANR

| سنجه | نتیجه |
|---|---|
| `FATAL EXCEPTION` | ۰ |
| `ANR in` | ۰ |
| `Force finishing` | ۰ |
| PID | `18596` از ابتدا تا انتها بدون تغییر |

هیچ رویداد توقف‌دهنده‌ای رخ نداد؛ نیازی به STOP نبود. (PHYSICAL DEVICE)

---

## §12 — آنچه قابل آزمون نبود و چرا

1. **سایت Fix-A (`[TLS] handshake entry (RUDP)`)**: دسترس‌ناپذیر. `softether_try_nat_t_connect` روی این شبکه با `error=6` شکست می‌خورد، پس بلوک `if (result == ERR_NONE)` در `softether_protocol.c:2904` وارد نمی‌شود. این یک محدودیت **محیطی** است، نه نقص کد. (PHYSICAL DEVICE + SOURCE CODE)
2. **اثربخشی مهلت روی handshake RUDP**: همان دلیل. حتی اگر Fix-A نقص هم داشت، این نشست آن را آشکار نمی‌کرد.
3. **ترمینال wedge-on-terminal (Fix-B)**: هیچ اجرایی به `ERROR_NATIVE_UNRESOLVED` نرسید.
4. **حذف سرور پس از ۲ wedge**: نیازمند انتخاب مجدد همان سرور در همان پروسه است که با پیشروی مکان‌نما رخ نداد.
5. **علت تغییر رفتار endpoint**: اینکه چرا `112.69.69.79` امروز در مرحلهٔ rendezvous شکست می‌خورد ولی دیروز تا ESTABLISHED پیش می‌رفت — از سورس قابل استنتاج نیست. **UNKNOWN**.

---

## §13 — برچسب شواهد

| ادعا | برچسب |
|---|---|
| APK نصب‌شده = build ممیزی‌شده (هش یکسان) | TEST |
| `handshake entry` مسیر TCP با `applied=yes` و `timeout_ms=4000` | PHYSICAL DEVICE |
| صفر بودن `handshake entry (RUDP)` | PHYSICAL DEVICE |
| صفر بودن `SLOT_UNRELEASED_DIAG` و ۲۴ رهاسازی slot | PHYSICAL DEVICE |
| ۵/۵ اتصال موفق | PHYSICAL DEVICE |
| ۶ wedge از مسیر per-run، ۰ از مسیر terminal | PHYSICAL DEVICE |
| علت نرسیدن به سایت RUDP (`error=6` پیش از handshake) | SOURCE CODE + PHYSICAL DEVICE |
| صفر بودن `SSL handshake attempt` = موفقیت/خطای غیر-WANT، نه بلعیدن EAGAIN | SOURCE CODE |
| اینکه بهبود نتیجه از v1.0.34 نیست بلکه از تغییر رفتار endpoint است | INFERENCE |
| علت تغییر رفتار endpoint | UNKNOWN |
| مبنای v1.0.33 (۵/۵ شکست، ۲۸.۵s سکوت) | PRIOR REPORT |

---

## §14 — تضمین منفی

- هیچ `uninstall` / `pm clear` انجام نشد؛ نصب با `-r` و بدون از دست رفتن داده.
- هیچ تنظیم دستگاهی تغییر نکرد؛ `SETTING_AUTO_MODE_TIMEOUT_SECONDS` روی `5` ماند (فقط خوانده شد).
- هیچ تزریق رقابت مصنوعی یا دست‌کاری thread انجام نشد؛ صفر `adb shell am`، صفر `setprop`، صفر `settings put`.
- هیچ فایل source / test / spec تغییر نکرد.
- هیچ `git add` / `commit` / `push` / تگ انجام نشد.
- هیچ build اجرا نشد.
- C5 اجرا نشد.
- `D:\PMC\Documents\Android\SoftEthergai` دست‌نخورده.
- تنها فایل‌های ایجادشده: این گزارش و `test-evidence/dv4/*` — همه untracked.

---

## §15 — توصیهٔ فنی برای فاز بعد (پیشنهاد، نه شروع)

**۱. هیچ اصلاح بومی جدید لازم نیست.** سایت Fix-A از نظر ساختاری درست است و تنها مانع، دسترس‌ناپذیری آن روی این شبکه است. افزودن BIO سفارشی یا deadline check پیش از آنکه سایت واقعاً اجرا شود، حدس‌وگمان است.

**۲. پرهزینه‌ترین بخش باقی‌مانده، retry پس از رهاسازی slot است.**
شاهد: از ۱۲ بار `Attempt after the slot was released…`، **۱۰** بار `NATIVE_ERROR` (≈۰.۱s) و **۲** بار `NATIVE_BUSY` (≈۵s) بود؛ **صفر** بار `TUNNEL_UP`. در ۱۱ از ۱۲ مورد، teardown قبلی `NATIVE_UNRESOLVED` بود — یعنی همان حالتی که خود کد می‌گوید «a new native attempt will be refused».
پیشنهاد مشخص: در `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt:1764-1790`، وقتی teardown قبلی `NATIVE_UNRESOLVED` بوده، از retry بلافاصله صرف‌نظر شود و مسیر شکست معمول طی شود. صرفه‌جویی تخمینی: ~۵s به ازای هر سرور شکست‌خورده. (SOURCE CODE + PHYSICAL DEVICE)

**۳. برای راستی‌آزمایی واقعی Fix-A**، این فاز باید روی شبکه/زمانی تکرار شود که در آن `NAT-T rendezvous` موفق می‌شود؛ نشانهٔ ورود به سایت، خط `[TLS] handshake entry (RUDP): … applied=yes` است. تا آن زمان Fix-A در وضعیت **UNVERIFIED** می‌ماند.

**۴. برای راستی‌آزمایی Fix-B**، باید همان سرور wedge‌خورده در همان پروسه دو بار انتخاب شود؛ با رفتار فعلی مکان‌نما این حالت به‌طور طبیعی رخ نمی‌دهد. (INFERENCE)
