# DEVICE-VERIFY — TUNNEL TRANSPORT (v1.0.47)

**Device:** `5585fecc` — Redmi Note 11 (`2201117TG`), `spes_global`, Android 13، غیر‌روت
**Package:** `vn.unlimit.vpngatepro` (debuggable)
**Branch:** `main` — HEAD `966e8d48abd30466052a2bfbd281c3f4d4d16954` = `origin/main` `[git]`
**Spec:** `PROJECT_SPECIFICATION.md` **1.0.47** `[git]`
**دامنه:** فقط تأیید روی سخت‌افزار. هیچ فایل منبع/اسپک/تست تغییر نکرد، هیچ build/commit/push/tag انجام نشد.

---

## §0 خلاصه اجرایی

هدف این بلوک پاسخ به یک پرسش بود: **آیا حذف self-exclusion در v1.0.47 باعث می‌شود rung `TUNNEL` واقعاً داده تحویل دهد، یا مسیر MS-SSTP که قبلاً `EPERM` می‌داد هنوز خراب است؟**

پاسخ: **مسیر MS-SSTP دیگر `EPERM` نمی‌دهد.** در تمام اجراهای این بلوک، تعداد رخداد `EPERM` **صفر** بود. سوکت‌های collector با موفقیت به شبکهٔ VPN بایند شدند (`[bind] ok network=118`) و ترافیک آن‌ها واقعاً از آدرس تونل (`10.234.75.30`) خارج شد. علاوه بر این، در تست T-D یک تحویل کامل end-to-end ثبت شد:

```
[fetch] path=tunnel succeeded after physical failed
```

سه نکتهٔ محیطی که مستقل از v1.0.47 هستند و در §8 توضیح داده شده‌اند، بخشی از سنجش «تحویل داده» را محدود کردند: سرورهای عمومی SoftEther ناپایدارند، endpoint `api` در بازهٔ تست down بود، و سیاست cleartext آندروید آینه‌های `http://` را مسدود می‌کند.

---

## §1 حکم هر تست

| تست | موضوع | حکم |
|---|---|---|
| **T-A** | تحویل داده روی SoftEther SSL-VPN/TCP | **PASS جزئی** — بایند و خروج از تونل تأیید شد، `EPERM=0`؛ تحویل end-to-end به‌دلیل خرابی سرور SoftEther مشاهده نشد |
| **T-B** | تحویل داده روی MS-SSTP (**مسیر اصلی `EPERM`**) | **PASS** — `EPERM=0`، بایند موفق، داده از طریق تونل دریافت شد |
| **T-C** | حفظ exclusion کاربر (`gearhead`) | **PASS** — `10190`/`20190` هنوز حذف‌شده، `10341` (خود اپ) اکنون داخل تونل |
| **T-D** | رگرسیون: toggle OFF → مسیر physical | **PASS** — `order=PHYSICAL,TUNNEL,EMERGENCY` و در پایان `path=tunnel succeeded after physical failed` |

---

## §2 پیش‌شرط‌ها

### STEP 1 — وضعیت مخزن

```
HEAD:        966e8d48abd30466052a2bfbd281c3f4d4d16954
origin/main: 966e8d48abd30466052a2bfbd281c3f4d4d16954     ← MATCH  [git]
**Document version:** 1.0.47                              ← MATCH  [git]
```

### STEP 2 — نصب و هویت باینری

| مورد | مقدار | وضعیت |
|---|---|---|
| نصب | `adb install -r` → `Success` | PASS |
| حجم APK | `63,780,018` بایت | — |
| SHA-256 فایل نصب‌شده (خوانده‌شده از دستگاه) | `0ef89bfefe8e1d7aae58ddde101bcda4642eef4b238eb9d1a91690e04df90583` | **مطابق baseline** |
| `versionCode` | `144` | PASS |
| `versionName` | `2.4.11-dev` | PASS |
| `flags` | `[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP ]` | PASS |
| `lastUpdateTime` | `2026-10-21 13:29:19` | — |

هش APK نصب‌شده مستقیماً با `exec-out cat` از `/data/app/.../base.apk` خوانده و روی هاست محاسبه شد؛ **بایت‌به‌بایت** با گیت بلوک قبل یکسان است. `[PHYSICAL DEVICE]`

### Developer Mode

```
<boolean name="SETTING_DEVELOPER_MODE" value="true" />
```

`[PHYSICAL DEVICE]` — و به‌صورت بصری هم تأیید شد: سوییچ «Developer Mode (logging)» در صفحهٔ Setting با رنگ accent و دستگیره در سمت راست (روشن) دیده می‌شود (`test-evidence/dv47/s02_devmode.png`). شرط توقف «Developer Mode OFF در STEP 2» رخ نداد؛ نیازی به اقدام اپراتور نبود.

### وضعیت اولیهٔ دستگاه

- هیچ تونلی متصل نبود: `Device "tun0" does not exist.`
- `vpn_always_prefer_vpn = false` (هر دو store)
- `ROUTE_EXCLUDED_APPS = { vn.unlimit.vpngatepro, com.google.android.projection.gearhead }`

---

## §3 T-A — SoftEther SSL-VPN over TCP

### STEP 3 — اجرا

مسیر: `Server List` → کارت `public-vpn-75.opengw.net` → `Connect to this server` → دیالوگ انتخاب پروتکل → **SoftEther SSL-VPN over TCP** (گزینهٔ اول، «Available on port 443»).

`[PHYSICAL DEVICE]` دیالوگ سه گزینه داشت و گزینهٔ اول به‌صراحت انتخاب شد (`s08_after_connect.png`).

### نتیجهٔ اتصال

```
13:47:48.870  State changed: CONNECTING
13:47:52.964  State changed: CONNECTED ip=10.234.59.150
```

تونل بالا آمد: `inet 10.234.59.150/16 scope global tun0` `[PHYSICAL DEVICE]`

### اجرای ladder

بلافاصله پس از CONNECTED، refresh اجرا شد:

```
13:47:53.726  VpnServerFetch: [ladder] order=TUNNEL,PHYSICAL,EMERGENCY
13:47:53.731  VpnGateCollector: [bind-physical] ok network=110
13:47:53.732  VpnGateCollector: [bind] ok network=117        ← بایند تونل موفق
13:47:53.738  VpnServerFetch: [fetch] path=tunnel source=mirror
13:47:53.738  VpnServerRepository: [collector] path=tunnel source=mirror
```

**نکتهٔ کلیدی — اثبات خروج واقعی از تونل:** درخواست‌های ناموفق، آدرس مبدأ را داخل تونل نشان می‌دهند:

```
Request failed: [SocketTimeoutException] failed to connect to /150.40.105.23 (port 64629)
    from /10.234.59.150 (port 45688) after 10000ms
```

یعنی سوکت collector واقعاً روی `tun0` بایند شده و از IP تونل خارج می‌شود — دقیقاً همان چیزی که پیش از v1.0.47 با `EPERM` شکست می‌خورد.

### شمارش EPERM

```
grep -c EPERM  →  0
```

`[PHYSICAL DEVICE]` در هیچ‌کدام از لاگ‌های T-A حتی یک رخداد `EPERM` وجود ندارد.

### چرا تحویل کامل نشد (خرابی سرور، نه باگ اپ)

سرور `public-vpn-75` ناپایدار است. ثبت کامل مرگ تونل:

```
13:45:35.540  ConnectionController: VPN socket fd=6 protected from TUNN
13:45:36.867  State changed: CONNECTED ip=10.234.59.150
13:45:36.984  KeepAliveManager: Keepalive received recorded
13:45:37.349  KeepAliveManager: Keepalive received recorded     ← آخرین keepalive سالم
13:45:47.911  KeepAliveManager: Keepalive missed (1/3)
13:45:49.917  ConnectionController: Connection appears dead (keepalive timeout)
13:48:06.198  State changed: DISCONNECTED ip=10.234.59.150
```

`[PHYSICAL DEVICE]` تونل SoftEther حدود **۱۳ ثانیه** پس از اتصال می‌میرد. ضمناً `ConnectionController: Stats: sent=894 bytes (12 pkts), received=0 bytes (0 pkts)` نشان می‌دهد data-plane سرور هیچ بایتی برنمی‌گرداند: تمام درخواست‌های بایندشده به تونل پس از ۱۰ ثانیه timeout شدند.

**نکتهٔ مهم:** خط `VPN socket fd=6 protected from TUN routing` ثابت می‌کند سوکت کنترل SoftEther با `VpnService.protect()` محافظت می‌شود (`ConnectionController.kt:727`). بنابراین حذف self-exclusion در v1.0.47 **حلقهٔ مسیریابی** ایجاد نمی‌کند؛ مرگ تونل ناشی از سرور است.

### آزمون سرور دوم

`public-vpn-109.opengw.net` پروتکل SoftEther را رد کرد:

```
SoftEtherProtocol: VPNCONNECT watermark failed — server may not be SoftEther
SoftEtherProtocol: NAT-T rendezvous failed: error=6
SoftEtherProtocol: Authentication failed: 1 (TCP connection failed)
```

`[PHYSICAL DEVICE]`

### حکم T-A

**PASS جزئی.** ادعای اصلی v1.0.47 (نبود `EPERM` و بایند موفق به شبکهٔ VPN) تأیید شد؛ تحویل end-to-end روی SoftEther مشاهده نشد چون سرورهای عمومی SoftEther یا تونل را زنده نگه نمی‌دارند یا data-plane مرده دارند. این محدودیت محیطی است، نه رگرسیون.

---

## §4 T-B — MS-SSTP (**مسیر اصلی `EPERM`**)

### STEP 4 — اجرا

`Server List` → `public-vpn-75.opengw.net` → `Connect to this server` → **Connect via MS-SSTP** (گزینهٔ سوم، «Available on TCP 443 (default)»).

```
13:49:14  MS-SSTP انتخاب شد
UP at 6s: inet 10.234.75.30/32 scope global tun0
```

### اجرای ladder — اجرای اول

```
13:49:34.471  VpnServerFetch: [ladder] order=TUNNEL,PHYSICAL,EMERGENCY
13:49:34.476  VpnGateCollector: [bind-physical] ok network=110
13:49:34.477  VpnGateCollector: [bind] ok network=118        ← بایند تونل موفق
13:49:34.482  VpnServerFetch: [fetch] path=tunnel source=mirror
13:49:44.602  VpnServerFetch: [fetch] path=tunnel source=api
13:49:44.602  VpnServerFetch: [fetch] path=tunnel source=html
```

### داده‌ای که از تونل دریافت شد

```
13:49:36.053  Accepted mirror_unnumbered from http://121.186.186.97:40587/en/sites.aspx (4 mirror link(s))
13:49:47.537  HTML servers: 100
13:49:47.537  Accepted html from https://www.vpngate.net/en/ (html: 10 columns, 100 rows)
13:49:47.726  Accepted mirror_2 from http://150.40.105.19:35399/en/ (10 columns, 100 rows)
```

`[PHYSICAL DEVICE]` — سه منبع مستقل از طریق تونل دادهٔ معتبر برگرداندند. اثبات خروج از تونل:

```
from /10.234.75.30        (تنها آدرس مبدأ مشاهده‌شده در فاز tunnel)
```

### شمارش EPERM

```
grep -c EPERM  →  0
```

**این همان مسیری است که در v1.0.46 و قبل‌تر `EPERM` تولید می‌کرد. اکنون صفر است.**

### اجرای دوم (تکرارپذیری)

Refresh دوم در `13:51:43` دقیقاً همان الگو را داد:

```
13:51:43.920  [fetch] path=tunnel source=mirror
13:51:45.716  Accepted mirror_unnumbered (4 mirror link(s))
13:51:57.142  HTML servers: 100
13:51:57.142  Accepted mirror_2 (100 rows)
EPERM = 0
```

### چرا rung در پایان «no result» گفت

```
13:49:58.275  [fetch] path=tunnel failed (no result); falling back to physical
13:50:02.432  [fetch] path=physical failed (no result)
```

`[PHYSICAL DEVICE]` — **مسیر physical هم دقیقاً همان لحظه شکست خورد.** علت، خرابی سراسری endpoint `api` بود:

```
13:52:02  No response from api: https://www.vpngate.net/api/iphone/
```

`[SOURCE CODE]` بر اساس `VpnServerRepository.kt:303–327`، موفقیت منبع `mirror` با `{ it.isNotEmpty() }` سنجیده می‌شود و `api`/`html` با `{ true }`؛ شکست هر دو مسیر (tunnel و physical) در یک بازهٔ زمانی نشان می‌دهد عامل، دسترسی به منبع است نه لایهٔ انتقال.

### حکم T-B

**PASS.** معیار قاطع این بلوک — «آیا مسیر MS-SSTP هنوز `EPERM` می‌دهد؟» — پاسخ **منفی** است. بایند موفق، خروج از IP تونل، و دریافت واقعی داده (۱۰۰ ردیف از دو منبع) همگی ثبت شدند.

---

## §5 T-C — حفظ exclusion کاربر

### STEP 5 — بررسی

**۱) pref دست‌نخورده باقی مانده (طبق طراحی):**

```xml
<set name="ROUTE_EXCLUDED_APPS">
    <string>vn.unlimit.vpngatepro</string>
    <string>com.google.android.projection.gearhead</string>
</set>
```

`[PHYSICAL DEVICE]` — v1.0.47 فقط *اعمال* را فیلتر می‌کند، نه محتویات pref را. پس باقی‌ماندن نام پکیج خود اپ در pref **مورد انتظار** است.

**۲) وضعیت واقعی در سطح OS (شاهد قاطع):**

```
Uids: <{0-10189, 10191-20189, 20191-99999}>
OwnerUid: 10341   AdminUids: [10341]
```

`[PHYSICAL DEVICE]` با تطبیق به UIDها:

| پکیج | userId | داخل تونل؟ |
|---|---|---|
| `vn.unlimit.vpngatepro` (خود اپ) | `10341` | **بله** — داخل بازهٔ `10191-20189` |
| `com.google.android.projection.gearhead` | `10190` | **خیر** — شکاف دقیقاً روی `10190` |
| (همان اپ در user 10) | `20190` | **خیر** — شکاف دقیقاً روی `20190` |

شکاف‌های `10190` و `20190` دقیقاً همان UIDهای `gearhead` هستند. یعنی: **exclusion کاربر حفظ شده، self-exclusion حذف شده.**

**۳) ساخت تونل بدون خطا:**

```
grep -hiE "NameNotFoundException|Excluded app not found"  →  0 رخداد
```

`[PHYSICAL DEVICE]` — هیچ پکیجی هنگام `addDisallowedApplication` پیدا نشد که خطا بدهد؛ تونل هر دو بار (SoftEther و MS-SSTP) تمیز ساخته شد.

### حکم T-C

**PASS.**

---

## §6 T-D — رگرسیون: toggle OFF

### STEP 6 — اجرا

تونل MS-SSTP هنوز بالا بود (`10.234.75.30`). سوییچ «Try tunnel first for server-list update» خاموش شد:

```
vpn_setting_data_pro.xml          → vpn_always_prefer_vpn = false
vn.unlimit.vpngatepro_preferences.xml → vpn_always_prefer_vpn = false
```

`[PHYSICAL DEVICE]` — هر دو store خاموش.

### نتیجه

```
13:55:27.391  [ladder] order=PHYSICAL,TUNNEL,EMERGENCY
13:55:27.394  [bind-physical] ok network=110
13:55:27.395  [bind] ok network=118
13:55:27.396  [fetch] path=physical source=mirror
13:55:37.460  [fetch] path=physical source=api
13:55:37.461  [fetch] path=physical source=html
13:55:43.383  [fetch] path=physical failed (no result); falling back to tunnel
13:55:52.467  [fetch] path=physical failed (no result); falling back to tunnel
13:55:59.200  Accepted html from https://www.vpngate.net/en/ (10 columns, 100 rows)
13:55:59.860  Accepted mirror_2 from http://150.40.105.19:35399/en/ (100 rows)
13:55:59.861  [fetch] path=tunnel succeeded after physical failed
```

`EPERM = 0`

### حکم T-D

**PASS — و این تست، شاهدِ کاملِ تحویل داده است.**

سه چیز هم‌زمان تأیید شد:
1. با toggle خاموش، `PHYSICAL` رانگ اول است (رگرسیون ندارد).
2. `TUNNEL` هنوز در ladder حاضر است (چون تونل usable است) اما **ترجیح** نمی‌شود.
3. وقتی physical شکست خورد، fallback به تونل انجام شد و **موفق** بود: `path=tunnel succeeded after physical failed` با ۱۰۰ سرور و صفر `EPERM`.

این خط، مؤثرترین اثبات این بلوک است: rung `TUNNEL` واقعاً داده تحویل می‌دهد.

---

## §7 کرش / ANR

### STEP 8

```
خطوط اسکن‌شده (بافر جاری): 1199
FATAL EXCEPTION:  0
ANR in:           0
Force finishing:  0
Process death:    0
PID:              28395  (پایدار در کل بازهٔ T-B → T-D)
```

اسکن تجمعی روی تمام فایل‌های `test-evidence/dv47/*.log`:

```
FATAL EXCEPTION: 0   |   ANR in: 0   |   Force finishing: 0
```

`[PHYSICAL DEVICE]` — هیچ کرش، ANR یا مرگ پروسه‌ای رخ نداد. تنها force-stopها، اقدام عمدی خودِ تست بود (برای پاک‌سازی native slot — §8).

---

## §8 آنچه قابل تست نبود / محدودیت‌ها

این موارد **مستقل از v1.0.47** هستند و باید جدا از نتیجهٔ بلوک خوانده شوند:

1. **سرورهای عمومی SoftEther ناپایدارند.** `public-vpn-75` تونل SoftEther را می‌پذیرد اما ~۱۳ ثانیه بعد با `keepalive timeout` می‌میرد و data-plane آن صفر بایت برمی‌گرداند. `public-vpn-109` اصلاً SoftEther نیست (`VPNCONNECT watermark failed`). به همین دلیل تحویل end-to-end روی SoftEther (T-A) مشاهده نشد؛ اما تحویل روی MS-SSTP (T-D) کامل ثبت شد.

2. **endpoint `api` در بازهٔ تست down بود.** `https://www.vpngate.net/api/iphone/` پاسخ نداد و همین باعث شد هم `path=tunnel` و هم `path=physical` در T-B «no result» بدهند. این خرابی سراسری است، نه تمایز بین دو مسیر.

3. **سیاست cleartext آندروید آینه‌های `http://` را مسدود می‌کند** (`CLEARTEXT communication ... not permitted by network security policy`). این رفتار پیش از این بلوک هم وجود داشت و به لایهٔ انتقال ربطی ندارد.

4. **native slot در حالت TEARDOWN قفل می‌ماند.** پس از یک اتصال ناموفق، تلاش مجدد رد شد:
   ```
   SoftEtherVpnService: Start refused: native slot still held by an unresolved attempt
       (attempt=1 phase=TEARDOWN, reporting deadline expired (still blocked))
   ```
   رفع موقت با `am force-stop` + راه‌اندازی مجدد. این یک نقص **پیش‌موجود** در چرخهٔ teardown است و در دامنهٔ v1.0.47 نیست؛ اما ارزش ثبت دارد.

5. **تست رفتاری `VpnService.Builder`** روی JVM ممکن نیست (نیازمند `VpnService` و `PackageManager` واقعی)؛ به همین دلیل تأیید در سطح دستگاه انجام شد.

6. **`vpngate_collector.py` در working tree تغییر یافته است** (`M`, mtime `2026-10-21 09:39`) — **پیش از شروع این بلوک** و خارج از دامنهٔ آن. من این فایل را نه خوانده‌ام و نه تغییر داده‌ام.

---

## §9 برچسب شواهد

| ادعا | برچسب |
|---|---|
| HEAD = origin/main = `966e8d4`، spec = `1.0.47` | `[git]` |
| APK نصب‌شده `0ef89bfe…`، `versionCode=144` | `[PHYSICAL DEVICE]` |
| `SETTING_DEVELOPER_MODE=true` + سوییچ روشن | `[PHYSICAL DEVICE]` |
| `[ladder] order=…`، `[bind] ok network=…`، `path=tunnel`، `EPERM=0` | `[PHYSICAL DEVICE]` (logcat) |
| خروج از `10.234.59.150` / `10.234.75.30` | `[PHYSICAL DEVICE]` (پیام خطای سوکت) |
| `Uids: <{0-10189, 10191-20189, 20191-99999}>` | `[PHYSICAL DEVICE]` (`dumpsys connectivity`) |
| `userId=10341` / `userId=10190` | `[PHYSICAL DEVICE]` (`dumpsys package`) |
| محافظت سوکت با `protect()` | `[SOURCE CODE]` (`ConnectionController.kt:727`) |
| معیار موفقیت منبع `mirror`/`api`/`html` | `[SOURCE CODE]` (`VpnServerRepository.kt:303–327`) |
| ترتیب ladder و شرط `tunnelUsable && hasBoundNetwork` | `[SOURCE CODE]` (`VpnServerRepository.kt:167–177`) |
| علت مرگ تونل SoftEther = سمت سرور | `[INFERENCE]` بر پایهٔ `[PHYSICAL DEVICE]` |
| آیا همین رفتار روی سرور SoftEther سالم تکرار می‌شود | `[UNKNOWN]` — سرور سالم SoftEther در دسترس نبود |

**فایل‌های شاهد:** `test-evidence/dv47/tA_softether.log`، `tA_connect_death.log`، `tB_sstp.log`، `tB_sstp_full.log`، `tB_sstp2.log`، `tB_final.log`، `tD_regression.log`، `tD_final.log`، `step8_full.log`، `step8_final.log`، و تصاویر `s01`–`s12`.

---

## §10 تضمین منفی

- **هیچ** فایل منبعی (Kotlin/XML/C++) تغییر نکرد.
- **هیچ** فایل تستی تغییر نکرد و **هیچ** تست جدیدی نوشته نشد.
- **هیچ** تغییری در `PROJECT_SPECIFICATION.md` انجام نشد.
- **هیچ** build اجرا نشد؛ APK موجود نصب شد.
- **هیچ** commit / push / tag / `--force` انجام نشد.
- **هیچ** uninstall یا data-clear انجام نشد؛ دادهٔ کاربر حفظ شد.
- تنظیمات دستگاه فراتر از سوییچ «Try tunnel first»، Developer Mode و WiFi تغییر نکرد.
- در پایان، دستگاه به وضعیت اولیه بازگشت: **toggle خاموش**، **بدون تونل** (`tun0` وجود ندارد)، Developer Mode روشن (همان‌طور که از ابتدا بود).

**وضعیت نهایی دستگاه**

```
tun0:                 وجود ندارد
vpn_always_prefer_vpn: false
SETTING_DEVELOPER_MODE: true
PID اپ:               28395 (پایدار، بدون کرش)
```

---

**پایان گزارش — توقف.**
