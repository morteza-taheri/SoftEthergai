# NATIVE-SLOT-TEARDOWN-RECOVERY — v1.0.48

**Repo:** `C:\Dev\SoftEthergai` · **Branch:** `main` · **Device:** DETACHED (device-free phase)
**Spec:** 1.0.47 → **1.0.48** (§1.6.41)
**Baseline:** `966e8d48abd30466052a2bfbd281c3f4d4d16954` = `origin/main` `[git]`

---

## §1 حکم

**ریشهٔ واقعی پیدا شد و رفع شد — و با آنچه در گزارش v1.0.47 فرض شده بود متفاوت است.**

گزارش v1.0.47 می‌گفت سوکت «به‌طور نامحدود» در `Phase.TEARDOWN` قفل می‌ماند و مکانیزم `FORCED_RELEASE_AFTER_TIMEOUT` «شلیک نمی‌کند». بررسی کد نشان داد مکانیزم **وجود دارد و منطقش درست است** — اما شرط زمانی‌اش از فیلدی خوانده می‌شد که **در هر فراخوان بازنویسی می‌شد**. یعنی تایمر ۹۰ ثانیه‌ای هرگز به پایان نمی‌رسید، چون هر تلاش برای بازیابی، همان تایمر را از صفر شروع می‌کرد.

این یک حلقهٔ خودشکننده بود: Auto Mode بعد از هر refusal دستور `adapter.disconnect()` می‌فرستاد → `destroyResources()` دوباره اجرا می‌شد → `markTeardownRequested` مهر زمانی را بازنویسی می‌کرد → پنجرهٔ ۹۰ ثانیه از نو شروع می‌شد.

| لایه | تغییر | وضعیت |
|---|---|---|
| **FIX-1** | `markTeardownRequested` فقط **اولین** درخواست teardown هر attempt را مهر می‌زند | ✅ رفع ریشه‌ای |
| **FIX-2** | سرویس سن دارنده را گزارش می‌کند و بازیابی را از نقطهٔ ورود خودِ رجیستری می‌خواهد | ✅ مسیر بازیابی + قابلیت تشخیص |
| **FIX-3** | ۴ تست، هر دو نیمهٔ قرارداد را pin می‌کند | ✅ ۴/۴ سبز |

**تضمین fail-closed حفظ شده است:** یک عملیات واقعاً گیرکرده هنوز برای مدت configured bound بلاک می‌کند، و آزادسازی فقط **بعد از** آن رخ می‌دهد.

---

## §2 پیش‌شرط‌ها

```
HEAD:        966e8d48abd30466052a2bfbd281c3f4d4d16954
origin/main: 966e8d48abd30466052a2bfbd281c3f4d4d16954     ← MATCH   [git]
Branch:      main
Tracked mods: M vpngate_collector.py  (استثنای مجاز — پیش از این بلوک)
Spec:        1.0.47                                        ← MATCH   [git]
Tags:        5                                             ← MATCH   [git]
```

هیچ شرط توقفی فعال نشد.

---

## §3 مکانیزم دقیق مرگ‌قفل

### ۳.۱ زنجیرهٔ فراخوان

`destroyResources()` در `ConnectionController.kt:907` در **خط ۹۱۹** — پیش از هر بررسی idempotency و پیش از هر فراخوان بلاک‌کنندهٔ native — این را اجرا می‌کند:

```kotlin
NativeLifetimeRegistry.process.markTeardownRequested(nativeLease)
```

و `destroyResources()` **دقیقاً به این دلیل idempotent طراحی شده** که چند بار صدا زده شود. کامنت خودِ کد در `SoftEtherVpnService.kt:928`:

> `// Idempotent — safe even if the controller already tore itself down.`

سه نقطهٔ فراخوان:

| نقطه | فایل:خط |
|---|---|
| مسیر `onDestroy` | `SoftEtherVpnService.kt:509` |
| مسیر cancel | `SoftEtherVpnService.kt:929` |
| `stopVpn` | `SoftEtherVpnService.kt:1046+` |

### ۳.۲ حلقهٔ خودشکننده

در Auto Mode، هر refusal با یک درخواست teardown پاسخ داده می‌شود (`AutoModeController.kt:1941`):

```kotlin
adapter.disconnect()
```

پس چرخه این بود:

```
تلاش اتصال → refusal (NATIVE_BUSY)
   → adapter.disconnect() → stopVpn → destroyResources()
   → markTeardownRequested() → teardownRequestedAtMs = now()   ← ساعت از صفر
   → تلاش بعدی → refusal  (چون ۹۰ ثانیه از «آخرین» درخواست نگذشته)
   → ... تکرار
```

### ۳.۳ چرا soft-bound شلیک نمی‌کرد

```kotlin
private fun forceReleaseIfTimedOutLocked(): String? {
    if (!deadlineExpired) return null
    if (activePhase != Phase.TEARDOWN) return null
    if (teardownRequestedAtMs == 0L) return null
    val heldMs = nowMs() - teardownRequestedAtMs     // ← لنگر اشتباه
    if (heldMs < NATIVE_SLOT_FORCE_RELEASE_AFTER_MS) return null
    ...
}
```

منطق بی‌عیب است؛ فقط `teardownRequestedAtMs` معنای «چه زمانی این attempt وارد TEARDOWN شد» را نداشت — معنای «آخرین درخواست teardown چه زمانی رسید» را داشت.

**این همچنین به پرسش بازِ خودِ §12.5.1 ردیف ۹ پاسخ می‌دهد:** «آیا bound ۹۰ ثانیه‌ای پیش از توقف Auto Mode فرا می‌رسد؟» — **هرگز نمی‌رسید، به‌دلیل ساختار.**

### ۳.۴ شاهد دستگاه

`DEVICE_VERIFY_TUNNEL_TRANSPORT_v1.0.47_REPORT.md` §8.4 `[PHYSICAL DEVICE]`:

```
Start refused: native slot still held by an unresolved attempt
    (attempt=1 phase=TEARDOWN, reporting deadline expired (still blocked))
```

`describe()` پسوند «reporting deadline expired» را فقط وقتی می‌دهد که `deadlineExpired == true` باشد. پس دو شرط اول برقرار بودند و گیت باقی‌مانده همان لنگر زمانی بود.

---

## §4 FIX-1 / FIX-2 / FIX-3

### FIX-1 — `NativeLifetimeRegistry.kt:306`

**قبل:**

```kotlin
fun markTeardownRequested(lease: Lease?) = mutate(lease) {
    it.activePhase = Phase.TEARDOWN
    it.teardownRequestedAtMs = nowMs()
}
```

**بعد:**

```kotlin
fun markTeardownRequested(lease: Lease?) = mutate(lease) {
    it.activePhase = Phase.TEARDOWN
    // v1.0.48 (§1.6.41) FIX-1: stamp only the FIRST teardown request for this attempt.
    // ...
    if (it.teardownRequestedAtMs == 0L) it.teardownRequestedAtMs = nowMs()
}
```

**چیزی که عمداً تغییر نکرد:** مقدار `NATIVE_SLOT_FORCE_RELEASE_AFTER_MS` (۹۰ ثانیه، `:536`)، `NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS` (۳۰ ثانیه)، بررسی فاز، پیش‌شرط `deadlineExpired`، و برچسب `FORCED_RELEASE_AFTER_TIMEOUT`. `[SOURCE CODE]`

### FIX-2 — `SoftEtherVpnService.kt`

دو عضو جدید در رجیستری:

| عضو | خط | نقش |
|---|---|---|
| `teardownHeldMs(): Long` | `:407` | سن دارنده — فقط خواندنی، بدون تغییر حالت |
| `forceReleaseStaleTeardown(): Boolean` | `:433` | نقطهٔ ورود عمومی که به **همان** `forceReleaseIfTimedOutLocked()` (`:453`) واگذار می‌کند |

**قبل** (`SoftEtherVpnService.kt`, محل claim):

```kotlin
val nativeLease = NativeLifetimeRegistry.process.tryAcquire()
if (nativeLease == null) {
    Log.w(TAG, "Start refused: native slot still held by an unresolved attempt " +
            "(${NativeLifetimeRegistry.process.describe()})")
    notifyNativeBusy(sessionPolicy.sessionGeneration, NativeLifetimeRegistry.process.describe())
    return
}
```

**بعد:**

```kotlin
val nativeLease = acquireNativeSlotOrRecover()          // :733
if (nativeLease == null) {
    val heldMs = NativeLifetimeRegistry.process.teardownHeldMs()
    Log.w(TAG, "Start refused: native slot still held by an unresolved attempt " +
            "(${NativeLifetimeRegistry.process.describe()}, heldFor=${heldMs}ms)")   // :746
    notifyNativeBusy(sessionPolicy.sessionGeneration,
        "${NativeLifetimeRegistry.process.describe()}, heldFor=${heldMs}ms")        // :761
    return
}
```

`acquireNativeSlotOrRecover()` (`:632`) این‌گونه کار می‌کند:

1. `tryAcquire()` — مسیر عادی.
2. در صورت refusal: `teardownHeldMs()` را **قبل** از هر اقدامی می‌خواند (تا تشخیص، حالتی را نام ببرد که باعث refusal شد، نه حالت بعد از آن).
3. `forceReleaseStaleTeardown()` — اگر آزاد شد، یک بار `tryAcquire()` مجدد.
4. اگر نشد، refusal با سن دارنده ثبت می‌شود.

**صداقت مهندسی:** چون `tryAcquire()` خودش همان بررسی را انجام می‌دهد، شاخهٔ «reclaimed» در عمل فقط زمانی فعال می‌شود که bound دقیقاً بین دو فراخوان رد شود. ارزش واقعی FIX-2 **تشخیص** است: پیش از این، یک لاگ دستگاه نمی‌توانست «۴ ثانیه تا بازیابی» را از «هرگز بازیابی نمی‌شود» تفکیک کند. `[SOURCE CODE]`

### FIX-3 — `NativeSlotRecoveryTest.kt`

فایل جدید، JUnit 4، ساعت تزریقی، **بدون sleep**.

| تست | چه چیزی را pin می‌کند |
|---|---|
| **T-NEW-74** | teardown تأییدشده هنوز **فوراً** آزاد می‌کند؛ هیچ خط `FORCED_RELEASE` تولید نمی‌شود؛ `lastReleaseOutcome == NORMAL` |
| **T-NEW-75** | دارنده در فاصلهٔ `bound - 1` هنوز **refused** است؛ `forceReleaseStaleTeardown()` هم `false` می‌دهد؛ چیزی لاگ نمی‌شود |
| **T-NEW-76** | **تست رگرسیون اصلی** — ۴ retry رد‌شده که هرکدام یک `markTeardownRequested` تازه می‌فرستند، نمی‌توانند ساعت را ریست کنند؛ bound دقیقاً ۹۰ ثانیه بعد از **اولین** درخواست شلیک می‌کند |
| **T-NEW-77** | پس از اینکه attempt جدید مالک شد، teardown دیررسِ lease قدیمی no-op است؛ و `forceReleaseStaleTeardown()` حتی سه bound بعد هم مالک جدید را آزاد نمی‌کند |

`[TEST]`

---

## §5 شمارش JVM

| ماژول | قبل | بعد | دلتا |
|---|---|---|---|
| `:SoftEtherClient` | 173 / 0 / 0 / 0 | **177 / 0 / 0 / 0** | **+4** |
| `:app` | 904 / 0 / 0 / 1 | **904 / 0 / 0 / 1** | 0 |

**هیچ رگرسیونی وجود ندارد.** هیچ تستی حذف، جابه‌جا یا ضعیف نشد.

**انحراف از پیش‌بینی بریف (مستند):** بریف انتظار `:app 908/0/0/1` داشت، اما مسیر مجاز تست `SoftEtherClient/src/test/java/vn/unlimit/softether/NativeSlotRecoveryTest.kt` است. بنابراین ۴+ در همان ماژول ظاهر شد و `:app` دست‌نخورده ماند. افزودن یک فایل تست به `app/src/test` نقض «NO OTHER FILES» بود؛ ترجیح دادم شمارش را صادقانه گزارش کنم تا فایل غیرمجاز بسازم.

`[TEST]`

---

## §6 Build

```
CI=true ./gradlew :app:assembleProDebug --rerun-tasks --no-build-cache
BUILD SUCCESSFUL in 17m 5s — 114 actionable tasks: 114 executed
```

| مورد | مقدار |
|---|---|
| حجم APK | `63,780,914` بایت |
| SHA-256 | `8f7ed99a201488655ab84946908fc74a58df7d32d0c0f813f9a60be60ea494a3` |
| `versionCode` | `144` (بدون تغییر) |
| `versionName` | `2.4.11-dev` (بدون تغییر) |

فضای دیسک در شروع build: `223G` کل، `11G` آزاد (۹۶٪ مصرف). `[TEST]`

---

## §7 دامنهٔ diff

```
 M SoftEtherClient/src/main/java/vn/unlimit/softether/NativeLifetimeRegistry.kt
 M SoftEtherClient/src/main/java/vn/unlimit/softether/SoftEtherVpnService.kt
 M vpngate_collector.py                      ← استثنای مجاز، پیش از این بلوک
?? SoftEtherClient/src/test/java/vn/unlimit/softether/NativeSlotRecoveryTest.kt

NativeLifetimeRegistry.kt  | 59 ++++++++++++++++-
SoftEtherVpnService.kt     | 75 +++++++++++++++++++++-
```

**`AutoModeController.kt` تغییر نکرد** — دلیل در §9.

**اسپک، ۶ هانک، همه در جای مجاز:**

| هانک | ناحیه |
|---|---|
| `@@ -7,2 +7,2 @@` | سرصفحه (نسخه + HEAD) |
| `@@ -453,0 +454,2 @@` | یادداشت push-state در §1.6.1 |
| `@@ -2954,0 +2957,28 @@` | بخش جدید §1.6.41 |
| `@@ -4656 +4686 @@` | یادداشت افزودنی ردیف ۹ در §12.5.1 |
| `@@ -5103,0 +5134 @@` | ردیف changelog 1.0.48 |
| `@@ -5121,0 +5153 @@` | ردیف Verification Status |

`§1.6.2` تا `§1.6.40` دست‌نخورده‌اند. `[git]`

---

## §8 یکسانی §22

```
قبل:  99 خط  adf12858b72ed13742252c512a8728e9141e415cc55d09c6409af4e8e622020e
بعد:  99 خط  adf12858b72ed13742252c512a8728e9141e415cc55d09c6409af4e8e622020e
diff -q  →  §22 IDENTICAL
```

`[git]` + `[TEST]`

---

## §9 چرا `AutoModeController.kt` تغییر نکرد

بریف این فایل را «فقط مسیر `ERROR_NATIVE_UNRESOLVED` / give-up» مجاز کرده بود. بررسی نشان داد:

1. `giveUpWithUnresolvedNativeOperation()` (خط ۲۳۱۶) **بدون ارجاع** است — کامنت خط ۲۱۵۱ خودش می‌گوید «now unreferenced (kept ...)».
2. مسیرهای `NATIVE_BUSY` عمداً terminal تولید نمی‌کنند: `no terminal, per §6.8.4` (`:1918`, `:2128`).
3. یعنی قرارداد **no-terminal** در v1.0.37 آن مسیر را عامدانه بازنشسته کرده است.

افزودن یک terminal جدید برای نشان‌دادن بازیابی، **نقض همان قرارداد** و خارج از قید FROZEN بریف بود. بنابراین بازیابی کاملاً زیر Auto Mode قرار گرفت، جایی که به همهٔ فراخوان‌ها کمک می‌کند و هیچ قراردادی را نمی‌شکند. `[SOURCE CODE]`

---

## §10 آنچه باقی مانده — تأیید روی دستگاه (PENDING)

**UNKNOWN.** هیچ دستگاه در این فاز اجرا نشد. آزمونِ بستن این فاز:

1. یک اتصال SoftEther را عمداً شکست بدهید (یا بگذارید بمیرد).
2. تلاش دوم را آغاز کنید.
3. انتظار: خط refusal اکنون `heldFor=<N>ms` را نشان می‌دهد.
4. انتظار: وقتی `N` از ۹۰ ثانیه گذشت، تلاش بعدی پذیرفته می‌شود و یکی از این دو ثبت می‌شود:
   - `[REGISTRY] FORCED_RELEASE_AFTER_TIMEOUT: attempt=N held for X ms past deadline …`
   - `[REGISTRY-RECOVERY] reclaimed the native slot after Xms in TEARDOWN`
5. انتظار منفی: **قبل** از پایان bound، هیچ‌کدام از این دو خط نباید ظاهر شود و refusal باید ادامه یابد.

نکتهٔ مهم برای آن اجرا: چون `tryAcquire()` خودش بررسی را انجام می‌دهد، خط `[REGISTRY]` (از داخل رجیستری) احتمالاً **قبل** از `[REGISTRY-RECOVERY]` ظاهر می‌شود — و اگر خط اول ظاهر شود، خط دوم اصلاً لازم نیست.

---

## §11 تضمین منفی

- هیچ فایلی خارج از مجموعهٔ مجاز تغییر نکرد.
- هیچ تستی حذف نشد؛ هیچ تستی ضعیف نشد.
- `NATIVE_SLOT_FORCE_RELEASE_AFTER_MS` و `NATIVE_SLOT_FORCE_RELEASE_CONNECTING_AFTER_MS` تغییر نکردند.
- تضمین fail-closed حذف یا تضعیف نشد (T-NEW-75 و T-NEW-77 آن را pin می‌کنند).
- قرارداد no-terminal در §6.8.4 و ladder در v1.0.45 دست‌نخورده‌اند.
- `HttpFetcher.kt`, `VpnServerRepository.kt`, `GlobalVpnTracker.kt`, `DataUtil.kt`, `SettingsScreen.kt`, `strings.xml`, `IPTerminal.kt`, `AutoModeEngine.kt`, `AutoModeScreen.kt`, `AutoModeViewModel.kt` و فایل‌های Emergency تغییر نکردند.
- `build.gradle`، manifest‌ها و schema‌ها تغییر نکردند.
- هیچ tag ساخته نشد؛ هیچ `--force` استفاده نشد.
- §22 بایت‌به‌بایت یکسان است.
- `D:\PMC\Documents\Android\SoftEthergai` لمس نشد.
- هیچ دستور دستگاه/adb اجرا نشد.

---

**پایان گزارش.**
