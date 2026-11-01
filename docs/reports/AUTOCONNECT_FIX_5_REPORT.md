# AUTOCONNECT-FIX-5 — گزارش فاز (v1.0.35)

**تاریخ:** 2026-10-19
**مبنا:** `c69d2a0bdf3ebf64b2eae554a369757b57d31c9f` — spec v1.0.34
**وضعیت دستگاه:** DETACHED (بدون فرمان دستگاه در این فاز)

---

## §1 — حکم (Verdict)

فاز **کامل** شد. سه کار انجام شد: دو گزارش فاز قبل کامیت شدند، دروازهٔ retry پس از رهاسازی slot پیاده‌سازی شد، و spec به v1.0.35 رسید.

| مورد | نتیجه | برچسب |
|---|---|---|
| دو گزارش قبلی tracked شدند | ✓ | SOURCE CODE |
| دروازهٔ `lastTeardownWasResolved()` | ✓ | SOURCE CODE |
| skip در سایت retry | ✓ (`AutoModeController.kt:1813`) | SOURCE CODE |
| دو تست جدید | ✓ هر دو PASS | TEST |
| `:app` | 803/0/0/1 → **805/0/0/1** | TEST |
| `:SoftEtherClient` | **173/0/0/0** (بیتغییر) | TEST |
| APK | `8FB20696…92FD801` (63,752,258 بایت) | TEST |
| spec | **1.0.35**، §1.6.27 موجود | SOURCE CODE |
| §22 | 99 خط / 6,504 بایت — یکسان | SOURCE CODE |

**دستاورد اصلی:** کنترلر دیگر برای retry ای که registry از پیش رد کرده، ~۶ ثانیه teardown اضافی نمی‌پردازد.

---

## §2 — پیش‌شرط‌ها

| مورد | مقدار | برچسب |
|---|---|---|
| `HEAD` | `c69d2a0bdf3ebf64b2eae554a369757b57d31c9f` | SOURCE CODE |
| `origin/main` | همان (برابر) | SOURCE CODE |
| شاخه | `main` | SOURCE CODE |
| تغییرات tracked در شروع | هیچ | SOURCE CODE |
| spec | `1.0.34` | SOURCE CODE |
| تگ‌ها | ۵ | SOURCE CODE |
| `DEVICE_VERIFY_AUTOCONNECT_FIX_4_REPORT.md` | ۲۶۰ خط / ۱۷,۶۹۷ بایت، سرخط `#` | SOURCE CODE |
| `SLOT_RACE_INVESTIGATION_PREP_REPORT.md` | ۳۴۳ خط / ۲۲,۴۵۷ بایت، سرخط `#` | SOURCE CODE |

هیچ شرط ABORT برقرار نبود.

---

## §3 — شواهد دستگاهی که این فاز را هدایت کرد

از `DEVICE_VERIFY_AUTOCONNECT_FIX_4_REPORT.md` §15 (PHYSICAL DEVICE):

- ۱۲ بار retry پس از رهاسازی slot در یک نشست.
- ۱۰ بار `NATIVE_ERROR` در ~۰.۱ ثانیه، ۲ بار `NATIVE_BUSY` در ~۵ ثانیه.
- **صفر** بار `TUNNEL_UP`.
- در **۱۱ از ۱۲** مورد، teardown قبلی `NATIVE_UNRESOLVED` بود.

این همان حالتی است که خودِ KDoc می‌گوید: «ownership is retained and every later start is refused by the registry». پس retry از پیش محکوم به رد شدن بود، ولی هزینهٔ teardown خودش را می‌پرداخت.

---

## §4 — اصلاح: دروازهٔ retry

### ۴.۱ — قرارداد رابط

`ConnectionAdapter` یک متد جدید گرفت (`AutoModeController.kt:322`):

```kotlin
fun lastTeardownWasResolved(): Boolean = true
```

پیش‌فرض `true` است تا هر test double که teardown را مدل نمی‌کند، رفتار قبلی خود را دقیقاً حفظ کند.

### ۴.۲ — پیاده‌سازی واقعی

`AndroidConnectionAdapter` نتیجه‌ای را که از قبل محاسبه می‌کرد، حالا نگه می‌دارد:

```kotlin
// AndroidConnectionAdapter.kt:106
@Volatile
private var lastTeardownOutcome: TeardownCompletion.Outcome? = null

// :302 — در پایان disconnect()
lastTeardownOutcome = result?.outcome

// :318-319
override fun lastTeardownWasResolved(): Boolean =
    lastTeardownOutcome != TeardownCompletion.Outcome.NATIVE_UNRESOLVED
```

**چرا فقط `NATIVE_UNRESOLVED`؟** چهار خروجی teardown وجود دارد و تنها یکی شروع بعدی را رد می‌کند:

| خروجی | معنا | شروع بعدی مجاز؟ |
|---|---|---|
| `NATIVE_CONFIRMED` | ساختارها آزاد شدند | بله |
| `NATIVE_UNRESOLVED` | ownership حفظ شده؛ هر start بعدی رد می‌شود | **خیر** |
| `NOOP_REENTRANT` | teardown دوباره وارد شد، چیزی برای آزادکردن نبود | بله |
| `FOREIGN_STACK_TERMINAL` | هیچ ادعایی دربارهٔ cleanup بومی ندارد | بله |

و `null` (timeout در `disconnect()`) عمداً «resolved» شمرده می‌شود: حالت نامعلوم است و این اصلاح فقط همان حالتی را محدود می‌کند که شواهد دستگاه پوشش می‌دهد. (SOURCE CODE)

### ۴.۳ — سایت skip

پیش از retry، بلافاصله پس از تأیید آزادشدن slot (`AutoModeController.kt:1813`):

```kotlin
if (!adapter.lastTeardownWasResolved()) {
    adapter.log(
        "[AUTO] Skipping retry on ${protocol.id}: previous teardown " +
                "was unresolved",
    )
    continue
}
adapter.log("[AUTO] Native slot released; retrying ${protocol.id} on …")
```

`continue` به حلقهٔ `for (protocol in supported)` (خط ۱۵۵۷) می‌رود، یعنی protocol بعدی — نه retry همان protocol.

**نکتهٔ ساختاری که هنگام پیاده‌سازی روشن شد:** retry بلوک cleanup جداگانه‌ای ندارد؛ پس از شکست به مسیر مشترک می‌افتد (`own-tunnel re-check` → wedge → `Protocol failed` → `disconnect()` → slot wait). بنابراین `continue` آن مسیر مشترک را هم رد می‌کند. این در این حالت خاص بی‌خطر است: `NATIVE_BUSY` یعنی registry پیش از هر تلاش بومی start را رد کرده، پس تونلی از خودمان وجود ندارد که re-check بخواهد adopt کند. این استدلال در کد کامنت شده است. (SOURCE CODE + INFERENCE)

**حسابداری wedge دست‌نخورده ماند:** `nativeBusyThisServer++` و `recordWedge` **پیش از** دروازه اجرا می‌شوند، پس رد شدن دوم همچنان wedge ثبت می‌کند — و تست T-NEW-13 همین را pin می‌کند. (SOURCE CODE + TEST)

---

## §5 — تست‌های افزوده‌شده

| تست | چه چیزی را pin می‌کند |
|---|---|
| `unresolvedTeardownSkipsThePostReleaseRetry` (`:3161`) | با `lastTeardownResolved=false`: خط skip لاگ می‌شود، هر protocol دقیقاً **یک بار** dial می‌شود (بدون retry)، اجرا به سرور بعدی می‌رود و Connected می‌شود، و wedge برای رد شدن دوم همچنان ثبت می‌شود |
| `resolvedTeardownKeepsThePostReleaseRetry` (`:3214`) | با `lastTeardownResolved=true`: retry انجام می‌شود (دو dial همان protocol)، اجرا Connected می‌شود، و هیچ خط skip تولید نمی‌شود |

هیچ تست موجودی تغییر نکرد یا حذف نشد. فیلد جدید fake (`lastTeardownResolved`) پیش‌فرض `true` دارد (`:92`)، که دلیل عبور بدون تغییر تست‌های چرخش قبلی است — از جمله `repeatedNativeBusyRecordsAWedgeAndRotates` که توالی دقیق dialها را assert می‌کند.

---

## §6 — ویرایش‌های spec (E1–E8)

| # | ویرایش | وضعیت |
|---|---|---|
| E1 | سرصفحه: نسخه `1.0.35`، HEAD `c69d2a0`، تاریخ `2026-10-19` | ✓ |
| E2 | یادداشت push-state v1.0.35 در §1.6.1 | ✓ |
| E3 | §6.8.3: زیرنکته در سطر `NATIVE_BUSY` + پاراگراف «post-release retry gate» | ✓ |
| E4 | بخش جدید §1.6.27 (۸ بند) | ✓ |
| E5 | §12.5.1 / §12.5.2 | بدون تغییر ✓ |
| E6 | سطر 1.0.35 در Changelog §20 | ✓ |
| E7 | سطر §1.6.27 در Verification Status (HISTORICAL) | ✓ |
| E8 | §22 | دست‌نخورده ✓ |

**یادداشت E1:** فیلد `HEAD` روی `c69d2a0` (کامیت پایهٔ فاز) تنظیم شد، چون یک کامیت نمی‌تواند هش خودش را در خود داشته باشد. این در یادداشت push-state صریحاً نوشته شده — همان رویهٔ v1.0.34.

---

## §7 — آزمون‌های JVM

| ماژول | قبل | بعد | دلتا |
|---|---|---|---|
| `:app` (`testProDebugUnitTest`) | 803 / 0 / 0 / 1 | **805 / 0 / 0 / 1** | +2 تست، skip بدون تغییر |
| `:SoftEtherClient` (`test`) | 173 / 0 / 0 / 0 | **173 / 0 / 0 / 0** | بدون تغییر |

هر دو اجرا با `CI=true` و `--rerun-tasks --no-build-cache`. هر دو تست جدید PASS. هیچ regression.

---

## §8 — ساخت

`CI=true ./gradlew :app:assembleProDebug --rerun-tasks --no-build-cache` → **BUILD SUCCESSFUL** (۴m ۵۸s).

| مورد | مقدار |
|---|---|
| SHA-256 | `8FB206964E86E7A5166F75200C77443A94463579390789FB18570A9F092FD801` |
| اندازه | ۶۳,۷۵۲,۲۵۸ بایت |
| تفاوت با v1.0.34 | بله — `4ABC4636…4B1435` |

**توضیح تفاوت:** هیچ منبع native تغییر نکرد (Fix-A دست‌نخورده است)؛ تفاوت از کد Kotlin کامپایل‌شده در APK می‌آید. (INFERENCE — مورد انتظار)

---

## §9 — دامنهٔ diff

```
 PROJECT_SPECIFICATION.md                           |  77 +++++++++++++-
 app/src/main/java/.../AndroidConnectionAdapter.kt  |  39 ++++++++
 app/src/main/java/.../AutoModeController.kt        |  56 +++++++++++
 app/src/test/java/.../AutoModeControllerTest.kt    | 111 +++++++++++++++++++++
 4 files changed, 279 insertions(+), 4 deletions(-)
```

فقط چهار فایل مجاز. هیچ فایل دیگری تغییر نکرد.

---

## §10 — یکسانی بایتی §22

```
§22 IDENTICAL — 99 خط / 6,504 بایت
```

همچنین بررسی شد:

- §1.6.2–§1.6.25: **یکسان** (۱,۶۶۹ خط).
- §1.6.26: **یکسان** (۸۵ خط) — Fix-B دست‌نخورده ماند.

---

## §11 — تضمین منفی

- بدون فرمان دستگاه / adb.
- بدون build یا test بدون `CI=true`.
- بدون `git add -A` / `git add .` — همه با مسیر دقیق.
- بدون `push --force` / `reset` / `clean` / `stash` / `checkout` / `restore`.
- بدون عملیات تگ.
- بدون حذف هیچ تست.
- بدون تغییر `softether_protocol.c` یا هر منبع native دیگر (Fix-A دست‌نخورده).
- بدون تغییر شاخهٔ wedge-on-terminal (Fix-B دست‌نخورده).
- بدون تغییر `NATIVE_SLOT_AWAIT_MS` / `NATIVE_SLOT_AWAIT_MAX_MS`.
- بدون تغییر `GlobalVpnTracker.kt`، `build.gradle`، manifestها.
- C5 اجرا نشد.
- `D:\PMC\Documents\Android\SoftEthergai` دست‌نخورده.

---

## §12 — آنچه همچنان UNVERIFIED است

هر دو اصلاح v1.0.34 در این فاز **دست‌نخورده** و **تأییدنشده** ماندند:

| اصلاح | وضعیت | دلیل |
|---|---|---|
| **Fix-A** — مهلت handshake روی RUDP (`softether_protocol.c:2935`) | **UNVERIFIED** | سایت در `if (result == ERR_NONE)` است؛ روی شبکهٔ ۲۰۲۶-۱۰-۱۹، `softether_try_nat_t_connect` با `error=6` برمی‌گردد و بلوک وارد نمی‌شود. صفر خط `[TLS] handshake entry (RUDP)` در ۵ اجرا. |
| **Fix-B** — wedge روی ترمینال `NATIVE_BUSY` (§1.6.26 بند ۴) | **UNVERIFIED** | هیچ اجرایی به `ERROR_NATIVE_UNRESOLVED` نرسید؛ ۵/۵ اجرا Connected شدند. |

هیچ‌کدام **رد** نشده‌اند. هر دو در spec به‌صراحت UNVERIFIED ثبت شده‌اند. (UNKNOWN)

---

## §13 — توصیه برای فاز بعد (پیشنهاد، شروع نشده)

سه گزینه، با هزینه/فایدهٔ صریح:

**الف) تلاش دوباره برای راستی‌آزمایی Fix-A.** تنها راهش تکرار `DEVICE-VERIFY` روی شبکه/زمانی است که `NAT-T rendezvous` موفق شود. نشانهٔ ورود به سایت: خط `[TLS] handshake entry (RUDP): … applied=yes`. **مشکل:** تا وقتی endpoint در مرحلهٔ rendezvous شکست می‌خورد، این فاز قابل اجرا نیست و کنترل آن در دست ما نیست. (INFERENCE)

**ب) Fix-6 — مهلت روی حلقهٔ handshake.** طبق `SLOT_RACE_INVESTIGATION_PREP_REPORT.md`، `SO_RCVTIMEO` یک `recv()` را محدود می‌کند نه کل handshake؛ نردبان `max_attempts=100` هر بار `recv()` تازه با همان ۴۰۰۰ms آغاز می‌کند (بدترین حالت ≈ ۴۰۱ ثانیه). اصلاح پیشنهادی: نمونه‌برداری `errno` پس از `SSL_do_handshake` و `break` روی `WANT_READ` + `EAGAIN` — همان الگوی D-1 که در `ssl_read` (`aes_wrapper.c:570-614`) موجود است، حدود ۵ خط در `:479`. **مشکل:** این هم امروز روی دستگاه قابل راستی‌آزمایی نیست، چون همان سایت دسترس‌ناپذیر است. (SOURCE CODE)

**ج) فاز ویژگی غیرمرتبط** (نسخه، retention، یا کار UI). تنها گزینه‌ای که امروز بازدهی قابل‌اندازه‌گیری دارد، چون به دسترس‌پذیری endpoint وابسته نیست. (INFERENCE)

**توصیهٔ من:** گزینهٔ (ج) — با یک یادداشت باز برای (الف/ب). دو فاز متوالی است که روی مسیری کار می‌کنیم که دستگاه اجازهٔ آزمونش را نمی‌دهد؛ ادامهٔ این چرخه ارزش اطلاعاتی پایینی دارد.

**نکتهٔ باز که در گزارش v1.0.34 هم ثبت شد و هنوز حل نشده:** حذف سرور (exclusion) در جریان طبیعی مشاهده‌نشدنی است، چون کنترلر مکان‌نما را حفظ می‌کند (`Continuing from server #N of 88`) و سرور wedge‌خورده تا چرخش کامل دوباره کاندید نمی‌شود. اگر Fix-B واقعاً مهم است، پیش‌شرط مشاهدهٔ آن باید طراحی شود. (INFERENCE)
