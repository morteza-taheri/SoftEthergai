# DEVICE-VERIFY-AUTOCONNECT-FIX-4-UNFILTERED — گزارش راستی‌آزمایی

**تاریخ:** 2026-10-19
**دستگاه:** 5585fecc — `2201117TG` (spes)، Android 13 (SDK 33)
**مبنا:** `d884062aaf8be20cc87a9cb19bf3600b415ef4db` — spec v1.0.35
**APK:** `8FB206964E86E7A5166F75200C77443A94463579390789FB18570A9F092FD801`

---

## §1 — حکم (Verdict)

**برای Fix-A: `UNTESTABLE`.**

سایت RUDP در `softether_protocol.c:2935` در هیچ‌یک از چهار اجرا وارد نشد — و این بار **نه به دلیل فیلترینگ**، بلکه چون پیش‌شرط ورود به آن هرگز رخ نداد: هر اتصال TCP با موفقیت login شد، پس شاخهٔ «Direct login failed → NAT-T + RUDP fallback» اجرا نشد. شاهد: **صفر** خط `NAT-T rendezvous` و **صفر** خط `handshake entry (RUDP)` در چهار اجرا. (PHYSICAL DEVICE)

**اما یک یافتهٔ جانبی مهم و تأییدشده به‌دست آمد:** روی شبکهٔ بدون فیلتر، **Fix-B سه بار اجرا شد و هر سه بار یک endpoint سالم را wedge زد** — در حالی که همان endpoint تونل کامل برقرار کرده بود (TLS + Hello + `DHCP SUCCESS`). یک بار هم سرور از سشن حذف شد.

| سنجه | مقدار | برچسب |
|---|---|---|
| `handshake entry (RUDP)` | **۰** | PHYSICAL DEVICE |
| `NAT-T rendezvous` | **۰** | PHYSICAL DEVICE |
| `SSL handshake attempt` | **۲** (فاصله ~۴.۱s، مسیر TCP) | PHYSICAL DEVICE |
| `Recording a wedge on the NATIVE_BUSY terminal` | **۳** | PHYSICAL DEVICE |
| `Excluding … after 2 wedges` | **۱** | PHYSICAL DEVICE |
| `SLOT_UNRELEASED_DIAG` | **۳** (همه با `ownTunnel=true`) | PHYSICAL DEVICE |
| `DHCP SUCCESS` | **۴** | PHYSICAL DEVICE |
| کرش / ANR | ۰ | PHYSICAL DEVICE |

---

## §2 — پیش‌شرط‌ها و بررسی شبکه

| مورد | مقدار | برچسب |
|---|---|---|
| `HEAD` | `d884062aaf8be20cc87a9cb19bf3600b415ef4db` | SOURCE CODE |
| `origin/main` | همان (برابر) | SOURCE CODE |
| spec | `1.0.35` | SOURCE CODE |
| تغییرات tracked | هیچ | SOURCE CODE |
| دستگاه | `5585fecc  device` | PHYSICAL DEVICE |

### بررسی شبکه

| آزمون | نتیجه |
|---|---|
| `ping -c 2 8.8.8.8` | **۰ از ۲** — با ICMP Redirect از `192.168.137.1` |
| `ping google.com` | **موفق** — `142.251.110.100`، ۷۵۶ms |
| `ping 1.1.1.1` | **موفق** — ۵۷۰ms |
| `curl https://github.com` از دستگاه | **HTTP 200** |
| `ping x2.x5.servers.nat-traversal.softether-network.net` | **موفق** → `130.158.6.60` (public8.softether.com) |
| `ping -c 2 130.158.6.60` | **۲ از ۲**، ۸۸۷–۹۱۴ms |
| اینترنت میزبان (همان مسیر ICS) | github=200، google=200، ping 8.8.8.8 = ۰٪ loss |

**انحراف از بریف که باید ثبت شود:** شرط ABORT «ping 8.8.8.8 fails» از نظر فنی برقرار شد، اما **شبکه سالم است**. شاهد: `google.com`، `1.1.1.1` و `github.com` هر سه پاسخ دادند و سرور NAT-T هم پاسخ ICMP داد. شکست `8.8.8.8` یک artifact مسیر ICS است (پاسخ `Redirect Network(New nexthop: 192.168.1.1)` از `192.168.137.1`)، نه نبود اینترنت. ادامه دادم و این تصمیم را ثبت می‌کنم. (PHYSICAL DEVICE + INFERENCE)

**نکتهٔ توپولوژی:** دستگاه روی `192.168.137.6` (wlan0) است — یعنی هات‌اسپات ویندوز (ICS)، نه یک AP مستقل. مسیر اینترنت همان مسیر میزبان است که بدون فیلتر تأیید شد.

---

## §3 — نصب و دود

| گام | نتیجه | برچسب |
|---|---|---|
| SHA-256 درخت کاری | `8FB20696…92FD801` — مطابق مبنا | TEST |
| `install -r` | `Success` | PHYSICAL DEVICE |
| `versionCode` | `143` | PHYSICAL DEVICE |
| اجرا | PID `27910`، بدون FATAL/ANR | PHYSICAL DEVICE |
| `SETTING_AUTO_MODE_TIMEOUT_SECONDS` | `5` (بدون تغییر) | PHYSICAL DEVICE |

PID در تمام نشست ثابت ماند (`27910`).

---

## §4 — سیگنال‌های هر اجرا

### اجرا ۱ — `vpn611507027` (`219.62.85.155:1309`)

```
09:06:30.801  [AUTO] Started
09:06:33.280  [AUTO] Trying #1 vpn611507027
09:06:34.689  [TLS] handshake entry: conn->timeout_ms=4000, socket_fd=96, SO_RCVTIMEO applied=yes
09:06:36.057  TLS handshake successful
09:06:36.649  Got HTTP 200 with application/octet-stream - server sent Hello!
09:06:38.704  DHCP SUCCESS: IP=10.211.1.12 gw=10.211.254.254
09:06:44.813  additional_connect: SUCCESS slot=1
09:06:53.527  additional_connect: SUCCESS slot=2
09:07:08.537  Recording a wedge on the NATIVE_BUSY terminal for vpn611507027
09:07:08.538  Native slot not released within 30000ms (attempt=1 phase=CONNECTED); stopping
09:07:08.540  SLOT_UNRELEASED_DIAG … tracker=CONNECTED ownTunnel=true
09:07:08.740  Destroying connection in state: CONNECTED
```

**NAT-T:** هیچ. **RUDP entry:** هیچ. **نتیجه:** Error.

### اجرا ۲ — همان سرور

```
09:12:00.765  [AUTO] Started
09:12:03.245  Trying #1 vpn611507027
09:12:07.406  Got HTTP 200 … server sent Hello!
09:12:12.622  DHCP SUCCESS: IP=10.211.1.12
09:12:18.448  SSL handshake attempt 1: need more data (error=2)
09:12:22.544  SSL handshake attempt 2: need more data (error=2)     ← فاصله ۴.۰۹۶s
09:12:38.372  Recording a wedge on the NATIVE_BUSY terminal for vpn611507027
09:12:38.373  Excluding vpn611507027 from this session after 2 wedges.
09:12:38.376  SLOT_UNRELEASED_DIAG … tracker=DISCONNECTED ownTunnel=false
                     registry=attempt=2 phase=TEARDOWN, reporting deadline expired (still blocked)
```

**NAT-T:** هیچ. **RUDP entry:** هیچ. **نتیجه:** Error + حذف سرور از سشن.

### اجرا ۳ — `vpn805442422`

```
09:16:00.365  Skipping vpn611507027: excluded for this session after 2 wedges
09:16:00.365  Trying #1 vpn805442422
09:16:03.875  DHCP SUCCESS: IP=10.211.1.24
09:16:04.330  Connected successfully
```

**نتیجه:** Connected در ۶ ثانیه. این تنها اجرای بدون ترمینال بود.

### اجرا ۴ — `vpn523361413`

```
09:17:01.354  [AUTO] Started   (Continuing from server #3 of 85)
09:17:03.669  Trying #1 vpn523361413
09:17:26.677  TLS handshake successful
09:17:27.420  Got HTTP 200 … server sent Hello!
09:17:30.389  DHCP SUCCESS: IP=10.211.1.7
09:17:38.936  Recording a wedge on the NATIVE_BUSY terminal for vpn523361413
09:17:38.940  SLOT_UNRELEASED_DIAG … tracker=CONNECTED ownTunnel=true
```

**نتیجه:** Error، پس از اینکه تونل کامل بالا آمده بود.

---

## §5 — شواهد قاطع

### ۵.۱ — Fix-A: وارد نشد، پس تأیید یا رد نشد

| سیگنال | انتظار | مشاهده |
|---|---|---|
| `NAT-T rendezvous for …` | اگر login شکست بخورد | **۰** |
| `handshake entry (RUDP)` | اگر سایت وارد شود | **۰** |
| `SSL handshake attempt` روی مسیر RUDP | اگر handshake بلوکه شود | **۰** |

علت ساختاری: `softether_protocol_login` روی هر چهار سرور **موفق** شد (`Got HTTP 200 … server sent Hello!` + auth). شاخهٔ `if (result != ERR_NONE && !conn->using_nat_t)` در `softether_protocol.c:2903` شرط ورود به RUDP است و هرگز true نشد. (SOURCE CODE + PHYSICAL DEVICE)

**پس شبکهٔ بدون فیلتر هم سایت Fix-A را قابل‌آزمون نمی‌کند** — چون فیلترینگ مورد نظر (شکست login با TCP سالم) روی این شبکه وجود ندارد. (INFERENCE)

### ۵.۲ — SO_RCVTIMEO روی مسیر TCP قطعاً کار می‌کند

دو خط `SSL handshake attempt` در اجرا ۲ با فاصلهٔ **۴.۰۹۶ ثانیه** آمدند:

```
09:12:18.448  attempt 1
09:12:22.544  attempt 2
```

`timeout_ms=4000` است، پس این فاصله دقیقاً یعنی `recv()` با `SO_RCVTIMEO` منقضی شد، `EAGAIN` تولید شد، OpenSSL آن را به `WANT_READ` ترجمه کرد و نردبان یک پله بالا رفت. **این مکانیزم — که Prep Investigation پیش‌بینی کرده بود — روی مسیر TCP عملاً تأیید شد.** (PHYSICAL DEVICE)

اما این handshake‌ها مربوط به اتصال‌های **additional** بودند (پس از `DHCP SUCCESS`)، نه مسیر RUDP. پس این شاهد برای Fix-A نیست؛ شاهد برای درستی خودِ مکانیزم مهلت است.

### ۵.۳ — Fix-B: اجرا شد، اما رویداد محرک را اشتباه تشخیص داد

سه بار اجرا شد (اجراهای ۱، ۲، ۴). در هر سه:

- `SLOT_UNRELEASED_DIAG` مقدار `tracker=CONNECTED` و `ownTunnel=true` داشت (در اجرا ۲: `DISCONNECTED`/`false`).
- پیش از آن `DHCP SUCCESS` با IP معتبر ثبت شده بود.
- پیام wedge می‌گوید: «the endpoint itself never accepted the native attempt».
- در واقعیت: **endpoint تلاش بومی را پذیرفت، TLS و Hello و DHCP کامل شد.**

پس فرض Fix-B (بند ۴ §1.6.26) — «ترمینال NATIVE_BUSY یعنی endpoint هرگز تلاش را نپذیرفت» — در این نشست **نقض شد**. (PHYSICAL DEVICE در برابر SOURCE CODE)

**زنجیرهٔ کامل خسارت (اجرا ۲):** wedge #1 → wedge #2 → `Excluding vpn611507027 from this session after 2 wedges` → اجرا ۳ آن سرور سالم را رد کرد.

### ۵.۴ — مکانیزم محتمل (INFERENCE)

اگر start واقعاً رد شده بود، هیچ تونلی نمی‌توانست بالا بیاید. اما تونل بالا آمد و `ownTunnel=true` بود. دو توضیح ممکن:

1. **محتمل‌تر:** `awaitArmedOutcome` یک اتصالِ **موفق** را `NATIVE_BUSY` گزارش کرد؛ سپس کنترلر برای slot ای منتظر ماند که **همان تونل زندهٔ خودش** آن را نگه داشته بود — و هرگز آزاد نمی‌شد. نتیجه: ترمینال ۳۰ ثانیه‌ای و تخریب تونل سالم.
2. کمتر محتمل: start رد شد و تونلی که بالا آمد متعلق به عملیات قبلی بود.

در هر دو حالت، نتیجهٔ کاربری یکی است: «Auto Mode failed» در حالی که VPN کار می‌کرد. این همان تضاد D-5 است که در §1.6.26 به‌عنوان «narrowed» ثبت شد، و اکنون با یک wedge نادرست تشدید شده است.

---

## §6 — معنای این یافته برای کاربران ایران

این فاز دربارهٔ فیلترینگ ایران چیزی را **رد یا تأیید نکرد**؛ اما یک تفکیک مهم به‌دست داد:

| محیط | رفتار مشاهده‌شده | سایت RUDP |
|---|---|---|
| شبکهٔ فیلترشده (۲۰۲۶-۱۰-۱۹ صبح، فاز قبل) | `NAT-T rendezvous failed: error=6`، سپس اتصال از سرور دیگر | وارد نشد |
| شبکهٔ بدون فیلتر (این فاز) | login روی TCP موفق، بدون نیاز به fallback | وارد نشد |

یعنی در **هر دو** محیط، مسیر RUDP استفاده نشد — با دو دلیل متفاوت. برای کاربر ایرانی، مسیر RUDP تنها در الگوی خاصی فعال می‌شود: TCP+TLS و watermark کار کنند ولی login رد شود (مثلاً فیلترینگ لایهٔ محتوا روی پاسخ login). این الگو در هیچ‌کدام از دو نشست بازتولید نشد. (INFERENCE)

**نتیجهٔ عملی:** Fix-A هنوز نه تأیید شده و نه رد. اما هزینهٔ آن صفر است و ریسکش هم صفر — یک `setsockopt` اضافی روی مسیری که امروز استفاده نمی‌شود.

**در مقابل، یافتهٔ Fix-B هزینهٔ واقعی دارد:** روی شبکهٔ بدون فیلتر، ۳ از ۴ اجرا تونل سالم را تخریب کردند و یک سرور سالم حذف شد. این برای کاربر ایرانی مستقیماً مضر است، چون همان مکانیزمی که قرار بود endpointهای خراب را کنار بگذارد، endpointهای سالم را کنار می‌گذارد.

---

## §7 — توصیهٔ Fix-6

**اولویت اول دیگر مهلت handshake نیست — اصلاح تضاد D-5 است.**

### ۷.۱ — پیشنهاد اصلی: ترمینال NATIVE_BUSY باید تونل خودمان را بررسی کند

سایت ترمینال در `AutoModeController.kt:1750-1785` هیچ بررسی‌ای برای «آیا تونل خودمان بالاست؟» ندارد. اما همان بررسی در مسیر post-attempt **موجود** است (`:1877`، `val ownTunnelUp = adapter.isOwnTunnelEstablished()`).

پیشنهاد مشخص: پیش از `giveUpWithUnresolvedNativeOperation()` در `:1784`، همان بررسی تکرار شود:

```kotlin
// پیش از ترمینال
if (adapter.isOwnTunnelEstablished()) {
    adapter.log("[AUTO] Tunnel is up despite NATIVE_BUSY; treating attempt as connected")
    connectedProtocol = protocol
    break            // یا مسیر Connected موجود
}
```

و در همان حالت، **wedge ثبت نشود** (چون endpoint سالم است).

**چرا این کار درست است:** شاهد دستگاه `ownTunnel=true` را در همان لحظه ثبت کرده است؛ کد این سیگنال را دارد ولی در این شاخه نادیده می‌گیرد. (PHYSICAL DEVICE + SOURCE CODE)

### ۷.۲ — پیشنهاد دوم: wedge-on-terminal باید مشروط شود

اگر ۷.۱ اعمال نشود، حداقل باید شرط wedge در `:1750-1757` محدود شود به حالتی که `ownTunnel=false` **و** `trackerStatus != CONNECTED` باشد. در غیر این صورت Fix-B باید **بازگردانده شود**، چون ضررش از فایده‌اش بیشتر است.

### ۷.۳ — Fix-6 اصلی (مهلت handshake) فعلاً بی‌اولویت است

طبق `SLOT_RACE_INVESTIGATION_PREP_REPORT.md`، `max_attempts=100` در بدترین حالت ~۴۰۱ ثانیه طول می‌کشد. اصلاح پیشنهادی همان‌جا (نمونه‌برداری `errno` در `aes_wrapper.c:479`، ~۵ خط) درست است، **اما امروز قابل راستی‌آزمایی نیست** چون سایت RUDP در هیچ‌یک از دو شبکه وارد نشد. انجام دادنش یعنی یک تغییر دیگر روی مسیری که دستگاه اجازهٔ آزمونش را نمی‌دهد.

---

## §8 — برچسب شواهد

| ادعا | برچسب |
|---|---|
| شبکه بدون فیلتر است (google/1.1.1.1/github/NAT-T همه پاسخ دادند) | PHYSICAL DEVICE |
| شکست `ping 8.8.8.8` artifact مسیر ICS است | PHYSICAL DEVICE + INFERENCE |
| APK نصب‌شده = build ممیزی‌شده | TEST |
| صفر `NAT-T rendezvous` و صفر `handshake entry (RUDP)` در ۴ اجرا | PHYSICAL DEVICE |
| login روی TCP در هر ۴ اجرا موفق شد | PHYSICAL DEVICE |
| `SO_RCVTIMEO` با فاصلهٔ ۴.۰۹۶s روی مسیر TCP عمل کرد | PHYSICAL DEVICE |
| Fix-B سه بار اجرا و سه بار endpoint سالم را wedge زد | PHYSICAL DEVICE |
| `DHCP SUCCESS` پیش از هر سه ترمینال | PHYSICAL DEVICE |
| حذف سرور سالم پس از ۲ wedge | PHYSICAL DEVICE |
| علت دقیق اینکه چرا اتصال موفق NATIVE_BUSY گزارش شد | UNKNOWN (دو فرضیه در §۵.۴) |
| اینکه Fix-A روی شبکهٔ فیلترشده کار می‌کند یا نه | UNKNOWN |

---

## §9 — تضمین منفی

- هیچ تنظیم دستگاهی تغییر نکرد؛ `SETTING_AUTO_MODE_TIMEOUT_SECONDS` روی `5` ماند (فقط خوانده شد).
- بدون `uninstall` / `pm clear`؛ نصب با `-r`.
- هیچ فایل source / test / spec تغییر نکرد.
- هیچ `git add` / `commit` / `push` / تگ انجام نشد.
- هیچ build یا test اجرا نشد.
- C5 اجرا نشد.
- `D:\PMC\Documents\Android\SoftEthergai` دست‌نخورده.
- تنها فایل‌های ایجادشده: این گزارش و `test-evidence/dv4u/*` — همه untracked.
