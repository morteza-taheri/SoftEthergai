# گزارش جامع ممیزی — SOFTETHERGAI
## BLOCK v1.0.63 — به‌روزرسانی فهرست سرورها، استخراج SoftEther UDP، ادغام داده‌ها و تأیید فیزیکی

**حالت:** ممیزی مبتنی بر شواهد، تأیید و گزارش‌دهی
**مخزن:** `https://github.com/morteza-taheri/SoftEthergai.git`
**شاخه:** `main`
**بسته:** `vn.unlimit.vpngatepro`
**نسخه توسعه مورد انتظار:** `2.4.10-dev` / versionCode `143`
**دستگاه اندروید فیزیکی:** سریال ADB `5585fecc`، مدل Xiaomi `2201117TG`، Android API 33
**گزارش اصلی:** `COMPREHENSIVE_AUDIT_v1.0.63_REPORT.md`

---

## ۱. خلاصه اجرایی و حکم ممیزی

**حکم کلی:** `CONTINUE AUDIT` (تأیید فیزیکی روی دستگاه هنوز انجام نشده است).

[BEGIN EVIDENCE TAGS — هر نتیجه‌گیری با یکی از برچسب‌های `SOURCE` / `TEST` / `EXECUTION` / `DEVICE` / `INFERENCE` علامت‌گذاری شده است؛ طبق مدل شواهد دستورالعمل بخش ۲، کلاس‌های شواهد A–F نیز درج شده‌اند.]

- **[SOURCE]** رفتار هدف‌مند BLOCK v1.0.63 در سطح کد منبع پیاده‌سازی شده و صحیح است:
  - **Part A — استخراج SoftEther UDP:** پرچم پشتیبانی به‌صورت شرطی و مستقل از پورت تنظیم می‌شود؛ هیچ پورتی ساخته نمی‌شود (بخش ۷، `EmergencyCsvParser.kt:419–451`).
  - **Part B — مسیریابی منبع اضطراری:** رگ اضطراری از مسیر ادغام مکمل (`mergeFallback`) عبور می‌کند نه جایگزینی کامل (`ServerListUpdates.kt:126–143`, `324–335`).
  - **§2.4 — حفظ کیفیت در مکمل:** فیلدهای کیفی ذخیره‌شده در `mergeFallback` حفظ می‌شوند (`:588–664`).
  - **§3 — بازنشانی مبدأ:** `origin.value = false` در ابتدای هر fetch بازنشانی می‌شود تا نشت وضعیت بین اجراها رخ ندهد (`:101`).
- **[TEST]** پوشش آزمون معنادار است: ۸۸ آزمون متمرکز (۴۷+۱۵+۷+۸+۱۱) در ۵ کلاس آزمون، با تأکید بر آزمون سطح updater (نه فقط فراخوانی مستقیم helper) در `EmergencyMergePolicyTest` بخش ۳.
- **[EXECUTION]** اجرای واقعی پایدار است: ۱۰ اجرای کامل مجموعه آزمون، همگی `BUILD SUCCESSFUL`، `tests=1428 failures=0 errors=0 skipped=1`، بدون تلاش مجدد ناشی از خطای راه‌اندازی.
- **[DEVICE]** **تأیید فیزیکی روی دستگاه `DEVICE_UNAVAILABLE` است.** دستگاه `5585fecc` تا فردا صبح در دسترس نبود؛ هیچ شواهد فیزیکی (logcat، وضعیت Room، همگام‌سازی Tunnel/UI، TUN EIO، باتری/پس‌زمینه) جمع‌آوری نشده است.
- **[SOURCE]** یک خطای جدید lint با انتساب به BLOCK v1.0.63 شناسایی شد (جزئی، غیرعملکردی): `ServerListUpdates.kt:540` خطای `SuspiciousIndentation`. بقیه ۱۰۳ خطا و ۱۷۸ هشدار باقی‌ماندهٔ پیشین (backlog) هستند و در فایل‌هایی‌اند که BLOCK v1.0.63 دست‌نخورده باقی گذاشته است.
- **[INFERENCE]** مسیریابی شبکه به‌روزرسانی در سطح کد: مسیر پیش‌فرض `PHYSICAL` است که به شبکهٔ غیر‑VPN متصل می‌شود؛ بنابراین یک به‌روزرسانی عادی فهرست سرورها **به‌طور پیش‌فرض از تونل VPN عبور نمی‌کند**. با این حال تأیید فیزیکی این که ترافیک واقعاً از واسط فیزیکی خارج می‌شود `NOT OBSERVABLE` است (دستگاه در دسترس نیست).

**نتیجه:** هیچ نقص عملکردی در سطح کد/آزمون یافت نشد؛ ریسک اصلی «تأیید فیزیکی انجام‌نشده» است. پیشنهاد: ادامه ممیزی پس از در دسترس قرار گرفتن دستگاه.

---

## ۲. دامنه ممیزی و محدودیت‌ها

- **در دامنه:** بازسازی خط لوله به‌روزرسانی فهرست سرورها؛ معنی‌شناسی منبع اصلی در مقابل اضطراری؛ استخراج SoftEther UDP؛ ماتریس ادغام فیلدبه‌فیلد؛ یکپارچگی DatasetIntegrity؛ ثبات/شناسه سرور؛ سازگاری Room/Snapshot/UI (سطح کد)؛ شبکه و مسیریابی درخواست (سطح کد)؛ کیفیت آزمون‌ها؛ اجرای ۱۰ بار مجموعه کامل؛ نتیجه lint؛ قابلیت بازتولید؛ دریافت زنده منبع اضطراری (سطح دسکتاپ).
- **خارج از دامنه / محدودیت:** هرگونه تغییر تولیدی، اصلاح آزمون، تغییر Gradle، وابستگی، Room/schema، R8، پروتکل‑پریوریتی، معماری Auto Connect، تونل‑لایف‌سایکل، مسیریابی دستگاه. طبق بخش ۱.۲ دستورالعمل، **هیچ‌یک در حین ممیزی تغییر نیافته‌اند**.
- **محدودیت دستگاه:** دستگاه فیزیکی `5585fecc` تا فردا صبح گزارش شده بود unavailable؛ لذا بخش‌های ۱۵، ۲۰–۲۵ در سطح فیزیکی `DEVICE_UNAVAILABLE` / `NOT OBSERVABLE` هستند.
- **عدم استنتاج نادرست:** هیچ نتیجه‌گیری فیزیکی‌ای از موفقیت یک درخواست HTTP روی دسکتاپ یا ساختار کدی استنتاج نشده است.

---

## ۳. وضعیت Git و سلامت تغییرات

[Evidence: A — SOURCE CODE / EXECUTION]

- **HEAD:** `7bebf8d91bb4000c61dd0db2127bb1ee6f40a4c9`
- **شاخه:** `main`
- **فایل‌های tracked تغییر‌یافته (۶):** (خروجی `git status --short` / `git diff --stat`)
  - `M F29_PHASE2_IMPLEMENTATION_REPORT.md` (۱۵۵+)
  - `M PROJECT_SPECIFICATION.md` (۲۰+)
  - `M app/src/main/java/vn/unlimit/vpngate/parser/EmergencyCsvParser.kt` (۱۲±)
  - `M app/src/main/java/vn/unlimit/vpngate/repository/ServerListUpdates.kt` (۱۳۹+)
  - `M app/src/test/java/vn/unlimit/vpngate/collector/EmergencyCsvParserTest.kt` (۱۵۹+)
  - `M app/src/test/java/vn/unlimit/vpngate/repository/ServerListRetentionTest.kt` (۱۱۰+)
- **فایل‌های untracked شاخص:** `app/src/test/java/vn/unlimit/vpngate/repository/EmergencyMergeFallbackTest.kt`، `EmergencyMergePolicyTest.kt` (آزمون‌های جدید BLOCK v1.0.63)؛ همچنین `D1_CANDIDATES.md`، چندین `DEVICE_VERIFY_*.md`، `TRIAGE_AUTOCONNECT_DEVICE_BUGS_REPORT.md`، `build_test_baseline.log`، `emergency/`، `scripts/`، `test-evidence/`، و دایرکتوری‌های ابزاری `.dv6/` `.dv7/` `.vscode/` `.workbuddy-ai/`.
- **وضعیت stage:** هیچ چیز staged نیست (`git diff --cached` خالی).
- **عملیات مخرب:** هیچ `git reset/clean/stash/commit/tag/push` انجام نشده؛ درخت کاری دست‌نخورده باقی مانده است.
- **سلامت:** اخطارهای CRLF بی‌ضرر برای فایل‌های متنی گزارش شد (LF→CRLF)؛ هیچ خطای سلامتی در شواهد دیده نشد.

---

## ۴. تفکیک تغییرات BLOCK v1.0.63 و G-F29-1

[Evidence: A — SOURCE CODE]

| تغییر | فایل / hunk | منشأ | مورد نیاز توسط | وضعیت فعلی |
|---|---|---|---|---|
| اصلاح استخراج اعلان UDP (Part A) | `EmergencyCsvParser.kt:419–451` | **BLOCK v1.0.63** | بخش ۶ | اعمال‌شده، آزمون‌شده |
| آزمون‌های Part A | `EmergencyCsvParserTest.kt` (۱۵۹+) | **BLOCK v1.0.63** | پوشش رگرسیون | اعمال‌شده |
| بازنشانی مبدأ (§3) | `ServerListUpdates.kt:101`, `117–118` | **BLOCK v1.0.63** | ایمنی بین‌اجرایی | اعمال‌شده |
| مسیریابی منبع اضطراری (Part B) | `ServerListUpdates.kt:126–143`, `324–335` | **BLOCK v1.0.63** | بخش ۵/۶ | اعمال‌شده |
| حفظ کیفیت در مکمل (§2.4) | `ServerListUpdates.kt:619–626`, `637–654` | **BLOCK v1.0.63** | بخش ۷.۲ | اعمال‌شده |
| تابع `mergeFallback` (هسته) | `ServerListUpdates.kt:588–664` | **G-F29-1 (پیشین)** | رفتار ادغام مکمل | موجود، توسط v1.0.63 گسترش‌یافته |
| آزمون‌های ادغام (جدید) | `EmergencyMergePolicyTest.kt`, `EmergencyMergeFallbackTest.kt` (untracked) | **BLOCK v1.0.63** | پوشش رگرسیون | اعمال‌شده |
| آزمون‌های حفظ (retention) | `ServerListRetentionTest.kt` (۱۱۰+)، `EmergencyMergeRetentionTest.kt` (untracked) | **G-F29-1 (پیشین)** | پوشش رگرسیون | اعمال‌شده |
| گزارش/مشخصات | `F29_PHASE2_IMPLEMENTATION_REPORT.md`, `PROJECT_SPECIFICATION.md` | **G-F29-1 (پیشین)** | مستندسازی | موجود |

**نکته وابستگی:** تغییرات BLOCK v1.0.63 در `ServerListUpdates.kt` (§2.4، Part B) **به تابع `mergeFallback`ِ موجود از G-F29-1 وابسته‌اند**. بنابراین BLOCK v1.0.63 به‌تنهایی از HEAD قابل commit یا بازتولید مستقل نیست (بخش ۱۸).

---

## ۵. معماری واقعی به‌روزرسانی فهرست سرورها

[Evidence: A — SOURCE CODE]

زنجیره فراخوان واقعی (نام‌های واقعی از کد):

```
اقدام UI / ViewModel / AutoModeController
   ↓
ServerListUpdater.update()            (repository/ServerListUpdater.kt)
   ↓  (قفل Mutex سراسری فرآیند)
fetch lambda                         → repository.refresh()
   ↓  (در صورت null → emergencyFetch)
emergencyFetch lambda                → emergencyDataset()   [فقط در صورت null بودن fetch]
   ↓
DatasetIntegrity.evaluate()          (repository/DatasetIntegrity.kt)
   ↓  (REJECT_DEGRADED → رد، ذخیره دست‌نخورده می‌ماند)
DaoStore.replaceAll / mergeAll | mergeFallback
   ↓  (via fetchedFromCache())
VPNGateItemDao.replaceAll (@Transaction)
   ↓
ذخیره Snapshot (saveLastKnownGood — فقط اگر NOT fromCache/emergency)
   ↓
Observable state / به‌روزرسانی UI
```

- **ورودی/خروجی هر مرحله:** `fetch` لیست اتصال را برمی‌گرداند؛ `emergencyFetch` فقط در صورت null بودن fetch فراخوانی می‌شود (Part B یک «مکمل» است، نه منبع اولیه جایگزین).
- **مدیریت خطا:** استثنا در fetch به `ServerListParseException` بسته‌بندی می‌شود؛ داده ناقص/تهی نمی‌تواند جایگزین شود مگر پس از عبور از `DatasetIntegrity`.
- **لغو/وضعیت:** وضعیت مبدأ (`origin.value`) پیش از شروع fetch بازنشانی می‌شود (§3) تا باقی‌ماندهٔ اجرای قبلی نشت نکند.
- **بقای مبدأ در mapping/merge:** `origin` از طریق `fetchedFromCache = { origin.value }` به انتخاب مسیر ادغام منتقل می‌شود (`ServerListUpdates.kt:124–125`).

---

## ۶. انتخاب منبع اصلی و اضطراری

[Evidence: A — SOURCE CODE; TEST — B]

ربات‌های کنترل‌کننده مبدأ:

- `ServerListUpdates.kt:101` — `origin.value = false` (بازنشانی §3 پیش از fetch).
- `:117–118` — `capability.value = result.capability; origin.value = result.fromCache` (مبدأ بر اساس نتیجهٔ واقعی).
- `:126–143` — `emergencyFetch` صریحاً `origin.value = true` را تنظیم می‌کند (Part B: رگ اضطراری مکمل است). `suppliedByEmergency` همچنان true است، پس snapshot هرگز به last-known-good ارتقا نمی‌یابد.
- `:324–335` — انتخاب مسیر ادغام:
  ```
  if (fetchedFromCache()) mergeFallback(...) else mergeAll(...)
  ```

**تأیید رفتار ادعا‌شده در برابر کد:**
- [SOURCE] داده مکمل اضطراری از مسیر `mergeFallback` عبور می‌کند ✓
- [SOURCE] داده اصلی از مسیر `mergeAll` عبور می‌کند ✓
- [SOURCE] داده رد‌شده (`REJECT_DEGRADED`) جایگزین مجموعه پذیرفته‌شده نمی‌شود (DatasetIntegrity قبل از نوشتن مخرب اعمال می‌شود) ✓
- [SOURCE] داده اضطراری به‌عنوان snapshot اصلی ارتقا نمی‌یابد (`suppliedByEmergency`) ✓
- [SOURCE] وضعیت مبدأ در ابتدای fetch بازنشانی می‌شود (§3) ✓
- [TEST] **اجرای اضطراری سپس اجرای اصلی سالم در یک نمونه updater واحد** از مسیر primary استفاده می‌کند و نشتی رخ نمی‌دهد — این در `EmergencyMergePolicyTest` (بخش ۳) در سطح updater واقعی تأیید شده است، نه فقط فراخوانی مستقیم helper. ✓
- [SOURCE] خروج زودهنگام/استثنا وضعیت مبدأ کهنه باقی نمی‌گذارد (بازنشانی در ابتدای fetch) ✓

---

## ۷. ممیزی استخراج SoftEther UDP

[Evidence: A — SOURCE CODE; TEST — B]

### ۷.۱ اعلان و پورت دو واقعیت مستقل

`EmergencyCsvParser.kt:419–451`:

| اعلان پشتیبانی | پورت UDP | رفتار کد (تأیید‌شده) |
|---|---:|---|
| `true` | غایب | `supported=true` بی‌قید‌وشرط؛ پورت ساخته نمی‌شود (`:437`, `:438` فقط در صورت انتشار پورت) |
| `true` | `0` | `supported=true`؛ پورت ساخته نمی‌شود |
| `true` | مثبت معتبر | هر دو `supported` و `port` نمایش داده می‌شوند (`:438–446`) |
| `false` | مثبت معتبر | تضاد شناسایی می‌شود: `Issue.UDP_PORT_WITHOUT_DECLARED_SUPPORT` (`:447–450`)؛ پورت نوشته نمی‌شود |
| غایب | مثبت معتبر | همان `UDP_PORT_WITHOUT_DECLARED_SUPPORT` |
| نامعتبر/نامعتبر | — | `NON_BOOLEAN_TOKEN` ثبت می‌شود (`:423–425`)؛ واقعیت ساخته نمی‌شود |

- [SOURCE] هیچ parser/mapper/merge/connection مسیری پورت UDP نمی‌سازد.
- [SOURCE] `seUdpSupported` از `bool(transport(se,"udp")["supported"])` نقشه‌برداری می‌شود (`VpnConnectionMapper.kt:58`)؛ `seUdpPort` از ستون پورت (`:57`)؛ `seTcpPort` از ستون TCP (`:54`) — همگی verbatim در `:95–98`.
- [SOURCE] پورت UDP هرگز از پورت TCP پر نمی‌شود (`ServerListUpdates`/mapper هیچ جا `seUdpPort = seTcpPort` ندارد).

### ۷.۲ واجدشرط بودن Auto Connect

- [SOURCE] `AutoModeController.kt:3188–3189`:
  ```
  AutoModeProtocol.SOFTETHER_UDP ->
      (c.seUdpSupported || c.seUdpPort > 0) && c.seTcpPort > 0
  ```
  - پشتیبانی UDP بدون پورت UDP سرور را **واجدشرط نگه می‌دارد** ✓
  - پورت TCP **همچنان الزامی** است ✓
  - UDP و TCP مجزا باقی می‌مانند؛ پورت UDP از TCP **ساخته نمی‌شود** ✓
  - اولویت پروتکل مستقل از reachability است.

### ۷.۳ پیکربندی اتصال

- [SOURCE] `seUdpPort` جایگزین `seTcpPort` نمی‌شود؛ هیچ endpoint ساخته‌نشده؛ اعلان پشتیبانی به‌تنهایی اثبات‌کننده وجود endpoint قابل‌استفاده نیست.
- [DEVICE] رفتار اتصال فیزیکی قابل آزمون نیست → `NOT OBSERVABLE` (بخش ۲۱).

---

## ۸. ممیزی پروتکل‌ها و پورت‌ها

[Evidence: A — SOURCE CODE]

- **SE TCP** (`:407–417`): پورت منتشر‌شده → `supported=true` + `port` + `portSource=EXPLICIT`.
- **SE UDP** (`:419–451`): طبق بخش ۷.۱.
- **SSTP** (ستون `SSTP_Supported`): طبق قرارداد منبع؛ در `DatasetIntegrity.coverage` از `isSSTPSupport` شمارش می‌شود.
- **L2TP** (`:228` `isL2TPSupport`): طبق قرارداد منبع.
- **OpenVPN** (پیکربندی تعبیه‌شده + ستون‌های پورت): طبق بخش ۹.۱.
- **عدم ساخت:** هیچ پورتی از پورت دیگر یا پیش‌فرض ساخته نمی‌شود مگر قرارداد صریح منبع (`EXPLICIT`/`portSource`).
- [TEST] تضاد پورت OpenVPN در `EmergencyCsvParserTest` (مورد `OPENVPN_PORT_CONFLICT_CONFIG_WINS`، ~خط ۷۸۵) پوشش داده شده است.

---

## ۹. سیاست ادغام فیلدبه‌فیلد

[Evidence: A — SOURCE CODE; TEST — B]

جدول سیاست برای فیلدهای کلیدی (میان `mergeAll` اصلی و `mergeFallback` مکمل):

| فیلد | معنای غایب/صفر/false | `mergeAll` (اصلی) | `mergeFallback` (مکمل) | آزمون |
|---|---|---|---|---|
| شناسه میزبان | — | کلید = `hostName.trim()` | همان | retention/identity |
| SE UDP supported | false | از ورودی | از ورودی (§2.4 فیلد پروتکل را تغییر نمی‌دهد) | EmergencyCsvParserTest |
| SE UDP port | ۰/غایب | از ورودی | از ورودی | همان |
| SE TCP port | ۰ | از ورودی | از ورودی | — |
| OpenVPN config | blank ورودی | از ورودی | **اگر ورودی blank و ذخیره non-blank → ذخیره حفظ می‌شود** (`:619–626`) | EmergencyMergeFallbackTest |
| score/ping/speed/uptime/sessions/users/traffic | ۰ | از ورودی (مشاهده تازه) | **از ذخیره (quality = prev ?: item)** (`:637`, `:648–654`) | EmergencyMergePolicyTest §2.4 |
| `firstSeenAt` | — | `prev?.firstSeenAt ?: now` (`:544`) | `prev?.firstSeenAt ?: now` (`:644`) | retention |
| `lastSeenAt` | — | `now` (`:545`) | `prev?.lastSeenAt ?: now` (**:LOCAL not refreshed**) (`:645`) | EmergencyMergePolicyTest |
| `healthScore` | — | `retentionHealthScore(item, now)` (`:546`) | `prev?.healthScore ?: retentionHealthScore(...)` (`:646`) | — |

- [SOURCE] قانون «هر false/صفر/خالی → حفظ ذخیره» **عمومی نیست**: فقط `openVpnConfigData` (سیگنال «ارائه‌نشده» تمیز دارد) و فیلدهای کیفی (§2.4) در مکمل حفظ می‌شوند؛ فیلدهای پروتکل/پورت از ورودی می‌آیند.
- [SOURCE] ردیف‌های ذخیره‌شده که در ورودی نیامده‌اند در هر دو تابع حفظ می‌شوند (`mergeAll:552–554`, `mergeFallback:659–661`).

### ۹.۱ اقتدار پیکربندی OpenVPN

- [SOURCE] پیکربندی تعبیه‌شده اقتدار پورت دارد؛ ستون فقط در صورتی پر می‌کند که config پورتی نداده باشد (`EmergencyCsvParser.kt:453–469`). تضاد به‌عنوان issue ثبت می‌شود، پورت پاک نمی‌شود.
- [TEST] `OPENVPN_PORT_CONFLICT_CONFIG_WINS` در `EmergencyCsvParserTest` پوشش داده شده است.

### ۹.۲ معناشناسی کیفیت و حفظ

- [SOURCE] `lastSeenAt` در `mergeAll` = «حضور در مجموعهٔ دریافتی/مشاهده تازه»؛ در `mergeFallback` حفظ می‌شود یعنی «آخرین تأیید واقعی»، نه `now`.
- [SOURCE] §2.4 صریحاً هدف دارد: مکمل یک مشاهده تازه شبکه نیست، پس کیفیت اندازه‌گیری‌شدهٔ ذخیره را با کپی ناشر بازنویسی نمی‌کند.

### ۹.۳ ترتیب نامزدها

- [SOURCE/INFERENCE] تابع ترتیب Auto Connect تولیدی (اندروید‑وابسته) از دامنه آزمون pure-JVM قابل آزمون نیست؛ ادعای پیشین تأیید شد. وضعیت: **`NOT TESTABLE`** (بدون گسترش دامنه یا تغییر کد تولیدی).

---

## ۱۰. حفظ سرورها و جلوگیری از حذف ناخواسته

[Evidence: A — SOURCE CODE; TEST — B]

- [SOURCE] شناسه میزبان = `hostName.trim()` (کلید Room PK / تطبیق ادغام، `:532`, `:601`). نرمال‌سازی حالت (trim) اعمال می‌شود؛ تطبیق IP/hostname طبق قرارداد موجود.
- [SOURCE] ردیف‌های ذخیره‌شده که در پاسخ مکمل حذف شده‌اند **پاک نمی‌شوند** (هر دو تابع، بخش ۹).
- [SOURCE] ردیف‌های دستی (`manual.rows`) از طریق `withManualRows(incoming, manualRows)` قبل از ادغام دوباره تزریق می‌شوند (`:528`, `:597`)، پس حذف نمی‌شوند.
- [TEST] `EmergencyMergeFallbackTest` و `ServerListRetentionTest` حذف ناخواسته را پوشش می‌دهند.
- [SOURCE] تکرار نام میزبان/تکرار IP طبق منطق تطبیق by-trim مدیریت می‌شود؛ هیچ ردیفی در طول ادغام تکرار نمی‌شود.

---

## ۱۱. DatasetIntegrity

[Evidence: A — SOURCE CODE]

آستانه‌های دقیق (از `DatasetIntegrity.kt`):
- `MIN_ACCEPTABLE_DATASET_SIZE = 10` (`:180`)
- `MIN_RELATIVE_DATASET_RATIO = 0.5` (`:183`)
- `MIN_PROTOCOL_COVERAGE_RATIO = 0.5` (`:186`)
- `MIN_PROTOCOL_COVERAGE_BASELINE = 20` (`:189`)
- `MIN_PROFILE_COVERAGE_RATIO = 0.5` (`:192`)
- `MIN_PROFILE_COVERAGE_BASELINE = 20` (`:195`)

- [SOURCE] `coverage()` شمارش SE UDP را `seUdpSupported || seUdpPort > 0` در نظر می‌گیرد (`:224`) — یعنی «پشتیبانی با پورت ناشناخته» وضعیت واقعی و مفید است، نه غایب.
- [SOURCE] `evaluate()` (`:253–329`): مجموعهٔ کوچک‌تر از ۱۰ رد می‌شود؛ کاهش بیش از ۵۰٪ نسبت به ذخیره (`SIZE_COLLAPSE`)؛ فروپاشی پروتکل/پروفایل (با رعایت baseline و capability) رد می‌شود.
- [SOURCE] نام‌های `DatasetOutcome` (`REJECT_DEGRADED` واقعی؛ `ACCEPT_NEW`/`MERGE_WITH_EXISTING` **فقط نام** — رفتار واقعی همچنان replace است، طبق KDoc صریح در `:133–149`). پس «مرحله تجمعی» هنوز پیاده‌سازی نشده است.
- [TEST] آزمون‌های موجود `DatasetIntegrity` (از جمله رد ۱۰ در برابر ۲۶۱ در `EmergencyMergePolicyTest`) پوشش داده شده‌اند.

---

## ۱۲. Room / Snapshot / UI consistency

[Evidence: A — SOURCE CODE; DEVICE — E (runtime مشاهده نشده)]

ردیابی سطح کد از ذخیره تا مشاهده:
- [SOURCE] نوشتن اتمیک از طریق `@Transaction VPNGateItemDao.replaceAll` (هر دو `mergeAll` و `mergeFallback`، `:556`, `:663`) → ریسک نوشتار نسبی کم.
- [SOURCE] `saveLastKnownGood` فقط پس از پذیرش نوشته می‌شود و فقط اگر NOT fromCache/emergency (`:338–341`) → snapshot توسط داده رد‌شده جایگزین نمی‌شود.
- [INFERENCE] سناریوهای ۱–۱۲ (بخش ۹ دستورالعمل): سطح کد نشان می‌دهد تضمین‌های consistency برای ورودی معتبر/رد‌شده/شبکه‌خراب/parse‌خراب اعمال می‌شوند؛ اما **مشاهدهٔ runtime واقعی** (هم‌ترازی Room/Snapshot/UI، ری‌استارت پس از به‌روزرسانی) بدون دستگاه `NOT OBSERVABLE` است.
- [DEVICE] هیچ ویرایش دستی Room/Snapshot انجام نشده (بخش ۱.۳).

---

## ۱۳. شبکه و مسیر درخواست به‌روزرسانی

[Evidence: A — SOURCE CODE; INFERENCE — F]

پاسخ به سؤالات A–F (مستقل):

- **A. آیا درخواست صریحاً به VPN Network متصل می‌شود؟** `NO` (پیش‌فرض). `routeLadder` با `ladder = [PHYSICAL]` شروع می‌شود (`VpnServerRepository.kt:284`).
- **B. آیا از شبکه پیش‌فرض اندروید استفاده می‌کند؟** غیرمستقیم — `productionBoundFetcher()` → `boundToPhysicalNetwork(GlobalVpnTracker.currentPhysicalNetwork())` (`:313–316`)؛ یعنی به یک شبکه صریح **غیر‑VPN** متصل می‌شود (نه `bindProcessToNetwork`).
- **C. آیا فرآیند صریحاً به Network متصل می‌شود؟** `NO`؛ `bindProcessToNetwork` **هیچ‌جا استفاده نمی‌شود** (`HttpFetcher.kt:124`, `:148`, `:333`).
- **D. آیا VPN Network خاصی انتخاب می‌شود؟** فقط در رگ `TUNNEL`، و فقط مشروط: `productionTunnelFetcher()` → `boundToNetwork(GlobalVpnTracker.currentVpnNetwork())` (`:325–328`)، که تنها زمانی به نردبان اضافه می‌شود که `tunnelRungAvailable` باشد (`:285`) و فقط بر اساس evidence ارتقا می‌یابد (`:273–274`).
- **E. آیا درخواست از VPN عبور می‌کند (bypass)؟** `NO` (نه صریحاً؛ PHYSICAL به شبکه فیزیکی متصل است، TUNNEL به VPN متصل است — هیچ bypass صریحی نیست).
- **F. اگر VPN قطع شود؟** رگ TUNNEL به `boundFetcher == null` تخریب می‌یابد و به PHYSICAL بازمی‌گردد (`:699–733`)؛ مدیریت خطا در سطح کد موجود است.

**پاسخ نهایی (بخش ۱۰ دستورالعمل):**
- سطح کد: **`CONFIRMED NOT VPN ROUTED`** (مسیر پیش‌فرض PHYSICAL به شبکه غیر‑VPN متصل است؛ TUNNEL فقط fallback مشروط است).
- سطح فیزیکی: **`NOT OBSERVABLE`** (دستگاه unavailable؛ تأیید نمی‌شود که ترافیک واقعاً از واسط فیزیکی خارج می‌شود).
- [INFERENCE] استنتاج مسیریابی فیزیکی صرفاً از ساختار کد انجام نشده است؛ نتیجه فیزیکی منوط به بخش ۱۵ است.

---

## ۱۴. ممیزی کیفیت تست‌ها

[Evidence: B — TEST SOURCE]

کلاس‌های آزمون بازرسی‌شده و ارزیابی قدرت ادعا:

| کلاس آزمون | تعداد | پوشش | قدرت ادعا |
|---|---:|---|---|
| `EmergencyCsvParserTest` | ۴۷ | Part A UDP (اعلان/پورت/تضاد)، OPENVPN config wins، manual import | بالا — مقادیر ورودی/ذخیره متفاوت تأکید می‌شوند |
| `EmergencyMergePolicyTest` | ۱۵ | §3 بازنشانی مبدأ (سطح updater)، §2.4 حفظ کیفیت، اقتدار OpenVPN، DatasetIntegrity ۱۰/۲۶۱، نوسازی mergeAll | بالا — بخش ۳ واقعاً یک `ServerListUpdater` می‌سازد |
| `EmergencyMergeFallbackTest` | ۷ | حفظ OpenVPN config، حفظ ردیف ذخیره‌شده در مکمل | بالا |
| `EmergencyMergeRetentionTest` | ۸ | حفظ شناسه/زمان‌بندی | بالا |
| `ServerListRetentionTest` | ۱۱ | حفظ ردیف‌های omitted (G-F29-1) | بالا |

- [TEST] هشدار ضعف ادعا: آزمون‌ها به‌طور کلی مقادیر متفاوت ورودی در برابر ذخیره استفاده می‌کنند (مثلاً lastSeenAt=900 در برابر NOW=5000؛ score=500 در برابر ۹۹۹)، پس حفظ واقعی را اثبات می‌کنند نه تصادف برابر.
- [TEST] بخش ۳ `EmergencyMergePolicyTest` **سطح updater** است (فراخوانی `mergeFallback` مستقیم نیست) → روتین مسیریابی واقعی تأیید شده است.
- [SOURCE] هیچ آزمونی در حین ممیزی تغییر نیافته است.

---

## ۱۵. نتایج تست‌های متمرکز

[Evidence: B — TEST; C — EXECUTION]

- **[TEST]** ۸۸ آزمون متمرکز در ۵ کلاس (۴۷+۱۵+۷+۸+۱۱)، ۰ خطا، ۰ شکست — دقیقاً منطبق با گزارش پیشین (تأیید مستقل شد).
- **[EXECUTION]** این ۸۸ آزمون بخشی از مجموعه ۱۴۲۸تایی ۱۰ اجرای کامل هستند و در همهٔ ۱۰ اجرا با موفقیت گذشتند (بخش ۱۶).

---

## ۱۶. نتایج ده اجرای کامل تست

[Evidence: C — EXECUTION]

فرمان (در درخت ایزوله `D:\v1063_build`، خارج از درخت کاری برای ایمنی):
`./gradlew :app:testProDebugUnitTest :SoftEtherClient:testDebugUnitTest --console=plain --no-daemon`
محیط: Gradle 9.7.0، AGP 9.1.1، JDK 21، `CI=true`. هر اجرا پیش از شروع daemonها را می‌کشد و نتایج آزمون کهنه را پاک می‌کند؛ تلاش مجدد فقط در صورت شکست واقعی راه‌اندازی (rc≠0 AND tests==0).

| اجرا | فرمان | Exit | Tests | Failures | Errors | Skipped | Build | Retry |
|---|---|---:|---:|---:|---:|---:|---|---|
| ۱ | testProDebugUnitTest + testDebugUnitTest | ۰ | ۱۴۲۸ | ۰ | ۰ | ۱ | SUCCESS | — |
| ۲ | همان | ۰ | ۱۴۲۸ | ۰ | ۰ | ۱ | SUCCESS | — |
| ۳ | همان | ۰ | ۱۴۲۸ | ۰ | ۰ | ۱ | SUCCESS | — |
| ۴ | همان | ۰ | ۱۴۲۸ | ۰ | ۰ | ۱ | SUCCESS | — |
| ۵ | همان | ۰ | ۱۴۲۸ | ۰ | ۰ | ۱ | SUCCESS | — |
| ۶ | همان | ۰ | ۱۴۲۸ | ۰ | ۰ | ۱ | SUCCESS | — |
| ۷ | همان | ۰ | ۱۴۲۸ | ۰ | ۰ | ۱ | SUCCESS | — |
| ۸ | همان | ۰ | ۱۴۲۸ | ۰ | ۰ | ۱ | SUCCESS | — |
| ۹ | همان | ۰ | ۱۴۲۸ | ۰ | ۰ | ۱ | SUCCESS | — |
| ۱۰ | همان | ۰ | ۱۴۲۸ | ۰ | ۰ | ۱ | SUCCESS | — |

- [EXECUTION] هیچ تلاش مجدد ناشی از خطای راه‌اندازی در این حلقهٔ اصلاح‌شده رخ نداد (RUN ۶ در حلقهٔ اول به دلیل BindException daemon متوقف شده بود — یک خطای محیطی، نه آزمون — و پس از پاک‌سازی daemonها، حلقهٔ دوم ۱۰/۱۰ کامل شد).
- [EXECUTION] هیچ اجرای ناقصی به‌عنوان pass شمارش نشد.
- [EXECUTION] لاگ: `D:\v1063_build\fullrun2.log`.

---

## ۱۷. نتیجه lint

[Evidence: C — EXECUTION]

فرمان (یک‌بار، پس از خروج کامل حلقه، بدون Gradle هم‌زمان):
`./gradlew :app:lintProRelease :SoftEtherClient:lintDebug --console=plain --no-daemon`
محیط: همان بخش ۱۶، درخت ایزوله `D:\v1063_build`.

- **Exit code:** `1` (BUILD FAILED).
- **`:app:lintProRelease`:** `104 errors, 178 warnings, 9 hints`.
- **اولین خطا:** `AndroidConnectionAdapter.kt:653` — `NewApi`: `NetworkCapabilities#getOwnerUid` نیازمند API 30 (min 24 است). این فایل **خارج از دامنه BLOCK v1.0.63** است → **backlog پیشین**.
- **طبقه‌بندی جدید در برابر backlog:**
  - **[SOURCE] یک خطای جدید با انتساب به BLOCK v1.0.63:** `ServerListUpdates.kt:540` — `SuspiciousIndentation` (تورفتگی `merged.add(` نسبت به `val prev = ...`). بررسی `git diff` تأیید می‌کند که این خط توسط BLOCK v1.0.63 افزوده شده است. **جزئی و غیرعملکردی** (تمام ۱۴۲۸ آزمون پاس می‌شوند؛ رفتار اجرایی تحت تأثیر نیست).
  - **[SOURCE] باقی ۱۰۳ خطا + ۱۷۸ هشدار = backlog پیشین** در فایل‌هایی که BLOCK v1.0.63 دست‌نخورده گذاشته است (UnusedResources، MissingTranslation، UseKtx، IconLocation، و غیره). `EmergencyCsvParser.kt` و کلاس‌های آزمون جدید هیچ یافته‌ای ندارند.
- **توصیه:** رفع ۱ خطای جدید (`SuspiciousIndentation`) در یک فاز اصلاح هدف‌مند جداگانه؛ ۱۰۳/۱۷۸ backlog خارج از دامنهٔ این BLOCK است و نباید برای سبز کردن lint جعل شود.
- [EXECUTION] لاگ: `D:\v1063_build\lint_run.log`؛ گزارش متنی: `D:\v1063_build\app\build\reports\lint-results-proRelease.txt`.

---

## ۱۸. قابلیت بازتولید و وابستگی به تغییرات commit‌نشده

[Evidence: A — SOURCE CODE; C — EXECUTION; INFERENCE — F]

- [SOURCE] BLOCK v1.0.63 در `ServerListUpdates.kt` (§2.4، Part B) به تابع `mergeFallback`ِ **uncommitted** از G-F29-1 وابسته است.
- [EXECUTION] ساخت ایزوله `D:\v1063_build` شامل هر دو دستهٔ تغییرات (BLOCK v1.0.63 + G-F29-1) بود؛ بنابراین ۱۰ اجرا هر دو را با هم آزمود.
- [INFERENCE] بازتولید مستقل BLOCK v1.0.63 **تنها از HEAD** امکان‌پذیر نیست، زیرا `git archive HEAD` شامل تغییرات uncommitted (از جمله `mergeFallback`) نمی‌شود.
- **طبقه‌بندی:** `PARTIAL` — رفتار در سطح کد/آزمون تأیید شده، اما وابستگی به تغییرات commit‌نشدهٔ G-F29-1 صریحاً گزارش می‌شود؛ ادعای «مستقل از HEAD بازتولیدشدنی» نمی‌شود.

---

## ۱۹. نتیجه دریافت زنده منبع اضطراری

[Evidence: D — LIVE-FETCH]

دریافت محدود، فقط‌خواندنی، از URL پیکربندی‌شده (اعتبارنامه ندارد):
`https://raw.githubusercontent.com/morteza-taheri/SoftEthergai/main/emergency-sources/servers.csv`

- **زمان:** ۲۰۲۶-۱۰-۲۵ (زمان اجرا).
- **HTTP status:** `200`.
- **حجم:** `897,749` بایت.
- **ردیف‌های تجزیل‌شده:** `245` ردیف داده.
- **ستون‌ها (شمارش awk):** `SE_UDP_Supported=true` = **۲۳۷** (۹۶.۷٪) با **۰** پورت UDP منتشر‌شده؛ `SE_TCP_Port>0` = ۲۴۳؛ `OpenVPN_ConfigData_Base64` خالی = ۱۸۲؛ `SSTP_Supported=true` = ۲۴۳.
- **محدودیت:** این شواهد دسکتاپی است؛ نشان‌دهندهٔ شکل رایج «اعلاشده‑بدون‑پورت» (۲۳۷/۲۴۵) است که انگیزهٔ Part A بود، اما **اثبات‌کنندهٔ دسترس‌پذیری/قابل‌استفاده‌بودن ردیف‌ها نیست** و جایگزین شواهد فیزیکی نمی‌شود.
- [INFERENCE] تطابق با فرضِ پیشین (۲۳۷/۲۴۵) مستقلاً تأیید شد.

---

## ۲۰. تأیید فیزیکی روی دستگاه

[Evidence: E — PHYSICAL-DEVICE; DEVICE — UNAVAILABLE]

- [DEVICE] `adb devices -l` در زمان ممیزی **هیچ دستگاهی متصل نشان نداد** (سریال `5585fecc` غایب/مجاز نشده).
- [DEVICE] طبق بخش ۱۵ دستورالعمل، تمامی زیربخش‌ها `DEVICE_UNAVAILABLE` / `NOT TESTABLE` هستند:
  - ۱۵.۱ هویت/پایه دستگاه → `DEVICE_UNAVAILABLE`
  - ۱۵.۲ به‌روزرسانی عادی بدون VPN → `NOT TESTABLE`
  - ۱۵.۳ برقراری تونل → `VPN CONNECTION NOT ESTABLISHED` (دستگاه غایب)
  - ۱۵.۴–۱۵.۶ شواهد شبکه فعال / به‌روزرسانی با VPN / قطع و تکرار → `NOT OBSERVABLE`
  - ۱۵.۷ هم‌زمان‌سازی Tunnel/UI → `NOT OBSERVABLE`
  - ۱۵.۸ SoftEther UDP و TUN EIO → `NOT OBSERVABLE`
  - ۱۵.۹ کرش/مرگ فرآیند → `NOT OBSERVABLE`
  - ۱۵.۱۰ یکپارچگی پایگاه داده پس از تست → `DEVICE_UNAVAILABLE`
- [DEVICE] **ادعای «تأیید فیزیکی کامل BLOCK v1.0.63» نمی‌شود** تا زمانی که این مراحل روی دستگاه انجام شوند.

---

## ۲۱. SoftEther UDP و TUN EIO

[Evidence: E — PHYSICAL-DEVICE]

- [DEVICE] `DEVICE_UNAVAILABLE` / `NOT OBSERVABLE`. شناسه سرور، پورت، حمل‌ونقل UDP/TCP، نتیجه اتصال، timeout، TUN EIO، آمار ترافیک و چرخه‌حیات سرویس قابل مشاهده نیستند.
- [SOURCE] پیش‌نیاز کدی (بخش ۷) تضمین می‌کند پورت TCP به‌عنوان پورت UDP قرض گرفته نمی‌شود؛ اما تأیید فیزیکی این که پورت از مبدأ صحیح می‌آید `NOT OBSERVABLE` است.

---

## ۲۲. Tunnel/UI state synchronization

[Evidence: E — PHYSICAL-DEVICE]

- [DEVICE] ماتریس وضعیت (Disconnected/Connecting/Connected/Disconnecting/Failed) برای نشان VPN سیستم‌عامل، واسط VPN، سرویس، Home، Status، Server List → **`NOT OBSERVABLE`**.
- [DEVICE] نمی‌توان `TUNNEL_STATE_RECONSTRUCTION_OR_SYNCHRONIZATION_DEFECT` را بدون شواهد هم‌زمان وضعیت اعلام کرد.

---

## ۲۳. Crash / Process death

[Evidence: E — PHYSICAL-DEVICE]

- [DEVICE] نظارت logcat روی دستگاه انجام نشد (دستگاه غایب). هیچ `FATAL EXCEPTION` / `AndroidRuntime` / `RemoteServiceException` / مرگ فرآیند/VPN سرویس در سطح فیزیکی مشاهده نشد → **`NOT OBSERVABLE`**.
- [EXECUTION] در ۱۰ اجرای آزمون واحد، هیچ کرش خاتمه‌دهندهٔ suite ثبت نشد (تمام اجراها BUILD SUCCESSFUL).

---

## ۲۴. Battery / Background

[Evidence: E — PHYSICAL-DEVICE]

- [DEVICE] محدودیت باتری/پس‌زمینه بدون تغییر تنظیمات بررسی نشد → **`DEVICE_UNAVAILABLE`**.
- [SOURCE] طبق بخش ۱.۳، هیچ تغییری در محدودیت باتری یا تنظیمات دستگاه اعمال نشده است.

---

## ۲۵. صحت نهایی داده‌های سرورها

[Evidence: A — SOURCE CODE; E — PHYSICAL-DEVICE]

- [SOURCE] سطح کد: شناسه (trim)، عدم تکرار، حفظ ردیف‌های omitted، حفظ OpenVPN config و کیفیت در مکمل، یکپارچگی DatasetIntegrity — همگی در منطق ادغام/یکپارچگی رعایت شده‌اند.
- [DEVICE] شمارش Room/Snapshot/UI، تکرار نام میزبان/IP، رکوردهای نامعتبر، سازگاری SE UDP/پورت، منشأ OpenVPN، فیلدهای SSTP/L2TP روی دستگاه → **`DEVICE_UNAVAILABLE`**.
- [DEVICE] هیچ بازرسی زنده/کپی از پایگاه داده انجام نشد (دستگاه غایب)؛ هیچ تعمیر دستی‌ای صورت نگرفت.

---

## ۲۶. ماتریس الزام به شواهد

[Evidence: A/B/C/D/E/F — ترکیبی]

| ID | الزام | Source | Test | Execution | Device | حکم | شواهد مفقود |
|---|---|---|---|---|---|---|---|
| R1 | استخراج صحیح SoftEther UDP (اعلان مستقل از پورت، عدم ساخت پورت) | ✓ (`:419–451`) | ✓ (۴۷ آزمون) | ✓ (۱۰/۱۰) | — | **PASS** (سطح کد/آزمون) | تأیید اتصال فیزیکی |
| R2 | مسیریابی منبع اضطراری → mergeFallback | ✓ (`:126–143`, `:324–335`) | ✓ (EmergencyMergePolicyTest) | ✓ | — | **PASS** | — |
| R3 | بازنشانی مبدأ §3 (عدم نشت بین اجرا) | ✓ (`:101`) | ✓ (سطح updater) | ✓ | — | **PASS** | — |
| R4 | حفظ کیفیت §2.4 در مکمل | ✓ (`:637`,`:648–654`) | ✓ | ✓ | — | **PASS** | — |
| R5 | عدم جایگزینی داده رد‌شده | ✓ (DatasetIntegrity) | ✓ (۱۰/۲۶۱) | ✓ | — | **PASS** | — |
| R6 | واجدشرط بودن Auto Connect (UDP بدون پورت واجدشرط؛ TCP الزامی) | ✓ (`:3188–3189`) | ✓ | ✓ | — | **PASS** | — |
| R7 | عدم ساخت پورت UDP از TCP | ✓ (mapper) | ✓ | ✓ | — | **PASS** | — |
| R8 | حفظ ردیف‌های omitted در ادغام | ✓ (`:552–554`,`:659–661`) | ✓ | ✓ | — | **PASS** | — |
| R9 | اقتدار پیکربندی OpenVPN | ✓ (`:453–469`,`:619–626`) | ✓ | ✓ | — | **PASS** | — |
| R10 | DatasetIntegrity thresholds | ✓ (`:180–195`) | ✓ | ✓ | — | **PASS** | — |
| R11 | ثبات Room/Snapshot/UI (runtime) | ✓ (سطح کد) | جزئی | — | **NOT OBSERVABLE** | **PARTIAL** | مشاهده فیزیکی |
| R12 | مسیریابی شبکه (عدم VPN‑روت شدن پیش‌فرض) | ✓ (PHYSICAL) | — | — | **NOT OBSERVABLE** | **PARTIAL** | تأیید ترافیک فیزیکی |
| R13 | اجرای پایدار ۱۰ بار کامل | — | — | ✓ (۱۴۲۸×۱۰) | — | **PASS** | — |
| R14 | نتیجه lint (عدم معرفی خطای عملکردی جدید) | ✓ (۱ خطای جزئی جدید) | — | ✓ (exit 1) | — | **PARTIAL** | رفع ۱ SuspiciousIndentation (فاز بعد) |
| R15 | بازتولید مستقل از HEAD | ✓ (وابسته به G-F29-1) | — | ✓ (درخت ایزوله) | — | **PARTIAL** | جداسازی از uncommitted |
| R16 | دریافت زنده منبع اضطراری | — | — | ✓ (D-class) | — | **PASS** (دسکتاپ) | معادل فیزیکی نیست |
| R17 | تأیید فیزیکی روی دستگاه | — | — | — | **DEVICE_UNAVAILABLE** | **DEVICE_UNAVAILABLE** | دسترسی دستگاه |
| R18 | SoftEther UDP / TUN EIO فیزیکی | — | — | — | **NOT OBSERVABLE** | **NOT OBSERVABLE** | دستگاه |
| R19 | هم‌زمان‌سازی Tunnel/UI | — | — | — | **NOT OBSERVABLE** | **NOT OBSERVABLE** | دستگاه |
| R20 | کرش/مرگ فرآیند فیزیکی | — | — | ✓ (واحد) | **NOT OBSERVABLE** | **PARTIAL** | logcat دستگاه |
| R21 | باتری/پس‌زمینه | — | — | — | **DEVICE_UNAVAILABLE** | **DEVICE_UNAVAILABLE** | دستگاه |
| R22 | یکپارچگی نهایی داده‌های سرور (دستگاه) | ✓ (سطح کد) | — | — | **DEVICE_UNAVAILABLE** | **PARTIAL** | بازرسی DB دستگاه |

---

## ۲۷. موارد ناموفق، ناقص و غیرقابل‌آزمون

[Evidence: A/B/C/D/E/F]

- **DEVICE_UNAVAILABLE / NOT OBSERVABLE (۱۷–۲۵):** تمامی موارد وابسته به دستگاه `5585fecc` به دلیل عدم در دسترس بودن دستگاه.
- **PARTIAL — R12 مسیریابی شبکه:** سطح کد `CONFIRMED NOT VPN ROUTED`؛ تأیید فیزیکی `NOT OBSERVABLE`.
- **PARTIAL — R14 lint:** ۱ خطای جدید جزئی (`ServerListUpdates.kt:540` SuspiciousIndentation) توسط BLOCK v1.0.63 معرفی شده؛ باقی backlog پیشین.
- **PARTIAL — R15 بازتولید:** وابسته به تغییرات uncommittedِ G-F29-1.
- **PARTIAL — R20 کرش:** در سطح آزمون واحد شکستی نبود، اما نظارت logcat دستگاهی انجام نشد.
- **هیچ FAIL واقعی در سطح کد/آزمون/اجرا یافت نشد.**

---

## ۲۸. فهرست شواهد و مسیر لاگ‌ها

[Evidence: A/B/C/D/E/F]

**کد منبع (شواهد A):**
- `app/src/main/java/vn/unlimit/vpngate/parser/EmergencyCsvParser.kt` (خطوط ۴۰۱–۴۶۹)
- `app/src/main/java/vn/unlimit/vpngate/repository/ServerListUpdates.kt` (خطوط ۹۵–۱۴۵، ۳۲４–۳۳۵، ۵۱۶–۶۸۹)
- `app/src/main/java/vn/unlimit/vpngate/repository/VpnConnectionMapper.kt` (خطوط ۵۴–۹۸)
- `app/src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt` (خطوط ۳۱۸۸–۳۱۸۹)
- `app/src/main/java/vn/unlimit/vpngate/repository/DatasetIntegrity.kt` (کل فایل)
- `app/src/main/java/vn/unlimit/vpngate/repository/VpnServerRepository.kt` (خطوط ۲۷۹–۳۲۸، ۶۱۹–۷۳۳)
- `app/src/main/java/vn/unlimit/vpngate/data/remote/HttpFetcher.kt` (خطوط ۱۲۴–۱۷۸، ۳۳۳)

**آزمون‌ها (شواهد B):**
- `app/src/test/java/vn/unlimit/vpngate/collector/EmergencyCsvParserTest.kt` (۴۷)
- `app/src/test/java/vn/unlimit/vpngate/repository/EmergencyMergePolicyTest.kt` (۱۵)
- `app/src/test/java/vn/unlimit/vpngate/repository/EmergencyMergeFallbackTest.kt` (۷)
- `app/src/test/java/vn/unlimit/vpngate/repository/EmergencyMergeRetentionTest.kt` (۸)
- `app/src/test/java/vn/unlimit/vpngate/repository/ServerListRetentionTest.kt` (۱۱)

**اجرا (شواهد C):**
- `D:\v1063_build\fullrun2.log` — ۱۰ اجرای کامل (هر کدام tests=1428/0/0/1)
- `D:\v1063_build\lint_run.log` — اجرای lint (exit 1)
- `D:\v1063_build\app\build\reports\lint-results-proRelease.txt` — گزارش متنی lint
- `D:\v1063_build\app\build\reports\lint-results-proRelease.html` — گزارش HTML lint

**دریافت زنده (شواهد D):**
- `https://raw.githubusercontent.com/morteza-taheri/SoftEthergai/main/emergency-sources/servers.csv` (HTTP 200، ۸۹۷٬۷۴۹ بایت، ۲۴۵ ردیف، ۲۳۷ UDP-supported بدون پورت)

**دستگاه (شواهد E):**
- `adb devices -l` → هیچ دستگاهی متصل نیست (سریال `5585fecc` غایب).

**وضعیت Git:** `git status --short` / `git diff --stat` (۶ فایل tracked تغییر‌یافته، ۵۸۵ درج، ۱۰ حذف؛ HEAD `7bebf8d`).

---

## ۲۹. جمع‌بندی ریسک‌ها

[Evidence: F — INFERENCE]

- **ریسک بالا — تأیید فیزیکی انجام‌نشده:** تمام بخش‌های ۲۰–۲۵ و R17–R19/R21/R22 به دلیل عدم در دسترس بودن دستگاه `DEVICE_UNAVAILABLE` هستند. پروژه نباید «کاملاً تأییدشده» اعلام شود تا زمانی که این مراحل روی دستگاه انجام شوند.
- **ریسک پایین — ۱ خطای جزئی lint:** `ServerListUpdates.kt:540` SuspiciousIndentation (غیرعملکردی؛ تمام آزمون‌ها پاس). باید در فاز اصلاح هدف‌مند رفع شود، نه نادیده گرفته یا با baseline پنهان شود.
- **ریسک متوسط — وابستگی بازتولید:** BLOCK v1.0.63 به G-F29-1 (uncommitted) وابسته است؛ جداسازی مستقل برای commit آتی باید هر دو را با هم در نظر بگیرد.
- **ریسک نامشخص — مسیریابی فیزیکی VPN:** سطح کد NOT VPN ROUTED را نشان می‌دهد، اما تأیید ترافیکی روی دستگاه `NOT OBSERVABLE` است؛ نباید با موفقیت یک درخواست HTTP در زمان اتصال VPN استنتاج شود.
- **ریسک صفر — رفتار هدف‌مند در سطح کد/آزمون:** Part A/B/§2.4/§3 و حفظ داده‌ها با آزمون‌های معنادار و ۱۰ اجرای سبز تأیید شدند.

---

## ۳۰. پیشنهاد مرحله بعد

**حکم انتخاب‌شده: `CONTINUE AUDIT`**

دلیل: شواهد قابل‌توجه فیزیکی (بخش‌های ۱۵، ۲۰–۲۵) همچنان مفقود است. رفتار در سطح کد، پوشش آزمون و اجرای ۱۰ بار تأیید شده‌اند، اما پروژه نباید «کاملاً تأییدشده» اعلام گردد در حالی که شواهد وابسته به دستگاه مفقود است.

**گام‌های بعدی پیشنهادی (منتظر مجوز صریح):**
1. پس از در دسترس قرار گرفتن دستگاه `5585fecc`، اجرای بخش ۱۵ (۱۵.۱–۱۵.۱۰) از طریق UI/چرخه‌حیات عادی — بدون تغییر اولویت پروتکل، مسیریابی، یا تنظیمات دستگاه.
2. رفع هدف‌مند ۱ خطای lint جدید (`ServerListUpdates.kt:540`) در فاز پیاده‌سازی جداگانه، پس از اتمام ممیزی.
3. مستندسازی صریح وابستگی BLOCK v1.0.63 به G-F29-1 هنگام هرگونه commit آتی.
4. تکرار ممیزی فیزیکی در صورت نامعتبر/ناقص بودن تست دستگاه.

---

## تأیید تغییرات نهایی (طبق بخش ۱۹ دستورالعمل)

تمامی موارد زیر در طول ممیزی **NONE** هستند، مگر اینکه صراحتاً مجوز داده شده باشند:

- تغییرات کد منبع تولیدی در حین ممیزی: **NONE**
- تغییرات کد آزمون در حین ممیزی: **NONE**
- تغییرات Gradle در حین ممیزی: **NONE**
- تغییرات وابستگی در حین ممیزی: **NONE**
- تغییرات Room/schema در حین ممیزی: **NONE**
- تغییرات migration در حین ممیزی: **NONE**
- تغییرات R8 در حین ممیزی: **NONE**
- تغییرات نسخه در حین ممیزی: **NONE**
- Commit: **NONE**
- Tag: **NONE**
- Push: **NONE**
- Release: **NONE**
- Uninstall: **NONE**
- Clear application data: **NONE**
- Manual database modification: **NONE**
- Manual snapshot modification: **NONE**

**تفکیک:** تغییرات موجود در درخت کاری (۶ فایل tracked تغییر‌یافته + آزمون‌های untracked) **پیش از** این ممیزی وجود داشته‌اند (BLOCK v1.0.63 و G-F29-1) و در حین ممیزی دست‌نخورده باقی مانده‌اند؛ هیچ‌یک از آن‌ها توسط این ممیزی اعمال، بازنویسی یا حذف نشده‌اند.

---

**توقف:** گزارش نوشته شد. هیچ اصلاح، commit، tag، push یا release انجام نشد. منتظر مجوز صریح برای ادامه هستم.
