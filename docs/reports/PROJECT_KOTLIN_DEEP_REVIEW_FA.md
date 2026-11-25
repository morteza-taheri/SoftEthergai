# بررسی عمیق ساختار پروژه و تمامی فایلهای Kotlin — SoftEthergai

**تاریخ بررسی:** ۲۰۲۶-۱۱-۲۳  
**مخزن:** `C:\Dev\SoftEthergai` (HEAD `173ed41`)  
**روش:** خوانده و تحلیل تمامی فایلهای `.kt` در چهار ماژول (`app`, `SoftEtherClient`, `sstpClient`, `vpnLib`)، به همراههمراه با فایلهای ساخت، مانیفست، تستها و کد JNI/C مرتبط. یافتههای بحرانی با خواندن مجدد خطوط دقیق کد راستیآزمایی شدهاند.

---

## ۱. خلاصه اجرایی

| ماژول | فایلهای Kotlin | خطوط | نقش |
|---|---:|---:|---|
| `app` | ۲۳۹ (۱۱۸ در `src/main`) | ۷۲٬۵۵۸ (۳۵٬۷۲۸ در main) | رابط Compose، ریپازیتوری، پایگاهداده Room، اتوکانکت، پارسرها |
| `SoftEtherClient` | ۳۸ (۱۸ در main) | ۱۴٬۱۷۴ (۶٬۹۸۴ در main) | سرویس VPN بومی SoftEther + لایه JNI |
| `sstpClient` | ۷۴ (۷۲ در main) | ۷٬۲۵۷ | پشته SSTP/PPP/MS-SSTP سرویس VPN |
| `vpnLib` | ۲ | ۱۸۶ | OpenVPN: رمزگذاری پروفایل و کنترل DPC1 |
| **جمع** | **۳۵۳** | **۹۴٬۱۷۵** | — |

**وضعیت کلی:** پروژه از نظر معماری بالغ است، مستندات داخلی گستردهای دارد و بسیاری از مسائل ظریف (generation guards، جلوگیری از TimSort comparator violations، بستن تکمیلشده handleها، امنیت HttpFetcher) قبلاً حل شدهاند. با این حال **چهار مشکل بحرانی/بالا در اتوکانکت و اتصال SSTP** وجود دارد که تجربه کاربر را بهطور مستقیم خراب میکند:

1. **اتوکانکت نمیتواند SSTP متصل کند** — رشتهٔ action نادرست نادرست است؛ سرویس SSTP بهجای اتصال، قطع اتصال را اجرا میکند.
2. **دکمه «توقف» اتوکانکت، ریکانکت خودکار ۲ ثانیه بعد را لغو نمیکند** — تونل دوباره بالا میآید.
3. **«سرور بعدی» پس از تمام شدن لیست، همان سرور قبلی را مجدداً dial میکند** — دکمه بیاثر بهنظر میرسد.
4. **رهایی اجباری native slot هنگامی که عملیات بومی هنوز در حال اجراست** — دو نشست میتوانند همزمان وارد JNI شوند (نقض هدف اصلی registry).

علاوه بر اینها، `KeepAliveManager` عملاً قادر به تشخیص مرگ اتصال SoftEther نیست و برخیشرایط رقابتی (race condition)های thread-safety و نشت حافظه/دیتابیس در طولانی مدت وجود دارد.

---

## ۲. نقشه ساختار و معماری

### ۲.۱ لایهبندی `app`

```
App.kt (service locator: dataUtil + 5 DAO + historySink + scopes)
 ├─ activities: MainActivity (Compose host), DetailActivity (God activity, ~1525 خط), Splash, L2TP
 ├─ ui: AppRoot → NavHost → Home / Status / AutoMode / Detail / Settings / About / L2TP
 │       └─ ViewModels: ConnectionListViewModel, StatusViewModel (~1100 خط), AutoModeViewModel
 ├─ automode: AutoModeEngine → AutoModeController (4032 خط، ماشین وضعیت خالص JVM)
 │             + AndroidConnectionAdapter (1026 خط، پل به سرویسها)
 │             + TunnelStateWatcher / TunnelLivenessProbe / Watchdog / OwnerUidReader / LogStore / Notifier
 ├─ repository: VpnServerRepository (979 خط) ← VpnGateSources ← parserها ← VpnServerMerger
 │              + ServerListUpdater / ServerListUpdates / ServerActions / ManualImport / DatasetIntegrity
 ├─ data: Room v11 (AppDatabase + DAOها)، DataUtil (Gson/SharedPreferences)، snapshot JSON
 ├─ network: AutoConnectReachability, ServerReachabilityTester
 ├─ history/ranking/state: quality reject window, history counters, GlobalVpnTracker (889 خط)
 └─ utils/update/collector/models/merger/provider/favourites/db
```

### ۲.۲ ماژولهای کتابخانهای

- **SoftEtherClient:** `SoftEtherVpnService` (۱۹۰۴ خط) مالک TUN/FGS/notification است؛ `ConnectionController` (۱۸۴۹ خط) handshake، DHCP و حلقههای TX/RX را هدایت میکند؛ چهار لایه مالکیت منبع: `NativeLifetimeRegistry`، `NativeHandleSlot` + `NativeAttemptCoordinator`، `TunOwnershipCoordinator` و `SoftEtherSessionPolicy`; امنیت مبتنی بر TOFU fingerprint است (`SSL_VERIFY_NONE` در native).
- **sstpClient:** ۷۲ فایل، سرویس `SstpVpnService` + `Controller` (مراحل SSL → SSTP CallConnect → PPP → LCP → auth → IPCP/IPv6CP → TUN) + `SSLTerminal`، `IPTerminal`، `IncomingManager` و پیادهسازیهای PAP/CHAP/MS-CHAPv2/EAP و MD4 دستنویس.
- **vpnLib:** تنها دو فایل Kotlin بومی (`ProfileEncryption`، `AppCustomControl`)؛ بقیه upstream ics-openvpn است.

**نکته معماری:** اما وابستگیها عمدتاً از طریق `App.instance!!` و `lateinit`ها بههم وصلاند؛ هیچ/ts DI وجود ندارد و ViewModelها قابل ساخت در تست واحد نیستند (`ConnectionListViewModelInitOrderTest` صراحتاً این را پذیرفته و بهجای آن تست متن سورس مینویسد).

---

## ۳. بخش ویژه: اتوکانکت / AutoMode

### ۳.۱ جریان کاری فعلی (آنطور که از کد استخراج شد)

```
DataUtil + VPNGateConnection dataset
   ↓
AutoModeEngine (singleton app-scope) → AutoModeController.runAutoMode()
   1) guard: پروتکل فعال / شبکه / لیست خالی
   2) fast-path تاریخچه (یک تلاش به سرور شناختهشده)
   3) رتبهبندی: quality → RTT → history → known-good ↑ / unhealthy ↓ → cooldown → single exploration
   4) دروازه reachability (سهحالته: REACHABLE / UNREACHABLE / UNKNOWN؛ فقط فیلترکننده)
   5) به ازای هر سرور × هر پروتکل: watcher مسلح → connect → awaitArmedOutcome
      (TUNNEL_UP / TIMED_OUT / NATIVE_ERROR / NATIVE_BUSY / MISSING_CONFIG)
      → disconnect → انتظار slot بومی → چرخش
   6) publishConnected() → ذخیره origin → watcher + لiveness probe + watchdog → نوشتن history
   ↓
AndroidConnectionAdapter → SoftEther / OpenVPN / SSTP VpnService
   ↓
AutoModeEngine.stateFlow → AutoModeViewModel → AutoModeScreen
   + AutoModeLogStore (ring buffer 2000 خط)، AutoModeNotifier، RoomServerHistorySink
```

### ۳.۲ یافتههای بحرانی و بالا

#### **A1 [بحرانی] — اتوکانکت هرگز SSTP متصل نمیکند؛ intent ارسالی باعث قطع سرویس میشود**
`app/.../automode/AndroidConnectionAdapter.kt:46-47, 182, 720, 898, 924`

```kotlin
private const val ACTION_VPN_CONNECT = "vn.unlimit.vpngate.VPN_CONNECT"
private const val ACTION_VPN_DISCONNECT = "vn.unlimit.vpngate.VPN_DISCONNECT"
...
val intent = Intent(context, SstpVpnService::class.java).setAction(ACTION_VPN_CONNECT)
```

در حالی که `sstpClient/.../service/SstpVpnService.kt:56-57, 269-315` فقط actions زیر را میشناسد:

```kotlin
internal const val ACTION_VPN_CONNECT = "kittoku.osc.connect"
internal const val ACTION_VPN_DISCONNECT = "kittoku.osc.disconnect"
```

**پیامد:** هر action دیگری وارد شاخه `else` میشود: `controller?.disconnect()`، پاک کردن `HOME_CONNECTED_IP` و `close()`. یعنی اتوکانکت بهجای برقراری SSTP، سرویس را متوقف میکند و حتی یک نشست زنده SSTP را میکشد. مسیرهای دستی (`DetailActivity.kt:1520-1521`) و Status از رشتههای صحیح استفاده میکنند، بنابراین مشکل منحصر به اتوکانکت است.

**راه حل:** قرارداد actionها را در یک شیء مشترک (`object SstpContract`) تعریف و در سه نقطهٔ adapter (`connect`، `disconnect`، تشخیص teardown) از همان ثابت استفاده کنید؛ سپس تست اتصال SSTP در اتوکانکت اضافه شود.

#### **A2 [بالا] — «توقف»، ریکانکت معوقه را لغو نمیکند**
`app/.../automode/AutoModeController.kt:1484, 1567, 1579-1600, 1733-1781, 1909-1946`

```kotlin
scope.launch { onUnexpectedDisconnect(dropped) }   // Job ذخیره نمیشود
...
setState(AutoModeState.Disconnected)
kotlinx.coroutines.delay(reconnectInitialDelayMs)   // ۲ ثانیه در پروداکشن
...
if (isRunning) { ...; return }
start()
```

`stop()` و `disconnectNow()` تنها `connectedWatcher`، `job` و `networkRetryJob` را لغو میکنند، نه این coroutine را (`stop()` خطوط ۱۷۳۳-۱۷۸۱). `isConnectingToAnotherServer()` وضعیت `DISCONNECTING` را نادیده میگیرد، بنابراین post-delay به `start()` میرسد.

**پیامد:** کاربر دکمه Stop را میزند، وضعیت «قطع» نمایش داده میشود و ۲ ثانیه بعد تونل دوباره متصل میشود.

**راه حل:** `reconnectJob: Job?` نگه دارید و در `stop()`/`disconnectNow()` لغو کنید؛ یک `generation: Long` در زمان loss بگیرید و بعد از delay دوباره چک کنید. تست: `invokeArmedTunnelLost → stop() → advanceTime → بدون connect جدید`.

#### **A3 [بالا] — «سرور بعدی» post exhaustion همان سرور ترکشده را مجدداً dial میکند**
`app/.../automode/AutoModeController.kt:1048, 1069, 1097-1114`

```kotlin
val currentKey = connectedCandidate?.let { serverKey(it) } ?: ...   // :1048 درست captured
...
connectedCandidate = null                                          // :1069
job = scope.launch {
    ...
    if (untried.isEmpty()) {
        val leaving = connectedCandidate?.let { serverKey(it) }      // :1101 → همیشه null
        sessionTriedKeys = if (leaving == null) HashSet() else HashSet(listOf(leaving))
```

کامنت خط ۱۰۹۷ میگوید «فقط سرور ترکشده نگه داشته شود» ولی فیلد قبل از اجرای lambda پاک شده است.

**پیامد:** بعد از استفاده عادی، فشردن Next دوباره همان سرور را انتخاب میکند؛ کاربر احساس میکند دکمه کار نمیکند. تست موجود `AutoModeNextServerIdentityTest.skipFiltersByIdentityNotByIp:238-266` هنوز رفتار قدیمی (`ERROR_NO_MORE_UNTRIED`) را میسنجد و این مسیر را پوشش نمیدهد.

**راه حل:** `val leavingKey = currentKey` را قبل از پاک کردن بگیرید و در خط ۱۱۰۱ از آن استفاده کنید؛ تستی اضافه شود که اولین dial چرخه جدید، میزبان متفاوتی داشته باشد.

### ۳.۳ یافتههای متوسط

| کد | محل | مشکل و پیامد | پیشنهاد |
|---|---|---|---|
| **A4** | `AutoModeLogStore.kt:41-51` | `add()` یک read-modify-write غیراتمیک روی `MutableStateFlow` است و از logcat thread، listenerهای OpenVPN/SoftEther و controller بهطور همزمان فراخوانی میشود → از دست رفتن خطوط و نقض سقف ۲۰۰۰ خط. | `Mutex`/`synchronized` یا `StateFlow.update` تحت قفل. |
| **A5** | `ranking/ServerQualityCalculator.kt:38-46` | ping نامشخص (`0.0` از `VpnModels.kt:369-373`) امتیاز ۲۲/۲۵ میگیرد و از سرور ۱۰۰ms (امتیاز ۱۵) بهتر رتبهبندی میشود. تنها tie-break کنترلر (`AutoModeController.kt:3881-3884`)آن را اصلاح میکند؛ امتیاز هنوز در مرتبسازی لیست و tie-break نهایی نشت میکند. هیچ تستی برای این کلاس وجود ندارد. | `ping <= 0 -> 0.0` قبل از شاخه `<=40`؛ تست oracle برای ۰ در برابر ۱۰۰. |
| **A6** | `automode/TunnelLivenessProbe.kt:152-177` | تنها TCP connect به `1.1.1.1:443` دلیل زنده بودن تونل محسوب میشود. Captive portal/proxyای که تمام SYNها را قبول کند، تونل مرده را زنده نشان میدهد؛ شبکهای که 1.1.1.1 را مسدود کند، تونل سالم را مرده. وابستگی ثابت به Cloudflare. | socket probe را به `Network` تونل (`GlobalVpnTracker.currentVpnNetwork()?.socketFactory`) ببندید، host قابل تنظیم/چرخشی و hysteresis نگه دارید. |
| **A7** | `AutoModeController.kt:957, 1756, 1931, 2533, 2557, 2771` | `sessionTriedKeys` یک `HashSet` ساده است که هم از UI thread (`stop()/disconnectNow()` → `clear()`) و هم از `Dispatchers.Default` (`add()`) تغییر میکند → احتمال فساد set و dial مجدد. | `ConcurrentHashMap.newKeySet()` یا محافظت از تمامی دسترسیها با `transitionLock`. |
| **A8** | `automode/AndroidConnectionAdapter.kt:396-403` | در `select<Boolean>` نتیجهٔ `deferred.onAwait { true }` نادیده میگیرد، در حالی که `skipCurrent()` آن را با `false` تکمیل میکند (`:713`) → skip بهعنوان `TUNNEL_UP` طبقهبندی میشود. فعلاً توسط `skipRequested` پوشانده شده، ولی primitive اشتباه است. | `deferred.onAwait { it }`. |
| **A9** | `history/ServerHistoryDao.kt:103-104, 120-136` + `AppDatabase.kt:237-272` | جدول eventهای تاریخچه append-only و بدون retention است؛ تنها purge دستی حذف میکند (`ServerActions.kt:38`). یک نشست طولانی اتوکانکت صدها ردیف در ساعت مینویسد. | حفظ N روز/N ردیف بهازای host در یک نقطه نگهداری دورهای. |
| **A10** | `network/AutoConnectReachability.kt:199-218, 245-285` + `ServerReachabilityTester.kt:217-234, 253-264` | probeها به رابط فیزیکی بسته نشدهاند و `ip` دور را مستقیم به `InetSocketAddress`/`InetAddress.getByName` میدهند؛ مسیر emergency CSV/ip literals را اعتباسازی میکند ولی لایه automode نه. علاوه بر آن `_testedCount.value += 1` از تا ۶۰ coroutine IO غیراتمیک است. | اعتبارسنجی IPv4 literal، بستن به physical `Network`، شمارنده اتمیک. |

### ۳.۴ یافتههای کم اهمیت

- `ui/screens/automode/AutoModeScreen.kt:305-335` — بلوک تکراری `if (count == 0)` و غیرقابل دسترس.
- `AutoModeScreen.kt:180-189` — `LaunchedEffect(Unit)` مقادیر `state`/`context` را در اولین ترکیب میگیرد؛ فیلتر پروتکل لحظهای اشتباه نمایش داده میشود. key را `state` کنید یا `derivedStateOf`.
- `automode/AndroidConnectionAdapter.kt:959-972` — DNS پیشفرض hard-coded به `8.8.8.8`/`8.4.4`؛ مسیر system DNS را پیشنهاد دهید.
- `AutoModeController.kt:2409-2410, 2420, 2904, 3502` — چند مسیر پایانی `job = null` را خارج از `transitionLock` مینویسند؛کلیک در این پنجره بهعنوان «در حال اجرا» رد میشود.
- `AutoModeModuleLogTap.kt:117-139` — subprocess `logcat` تنها با `detach()` (app-scoped، عملاً فراخوانی نمیشود) متوقف میشود؛ تمام طول عمر پروسس در حال اجراست.
- `AutoModeScreen.kt:829-838` — `Surface.clickable` فاقد `role = Role.Button` و `onClickLabel` است؛ TalkBack فقط توضیح آیکون را میخواند.

### ۳.۵ شکافهای تست اتوکانکت

1. تستی برای Stop در حین تأخیر ریکانکت وجود ندارد (`AutoModeReconnectTest` T6 فقط ثابت تأخیر را میسنجد).
2. مسیر «همه سرورها امتحان شدند» و هویت سرور ترکشده پوشش داده نمیشود؛ تست موجود رفتار منسوخ را assert میکند.
3. تست استرس برای `AutoModeLogStore`، `sessionTriedKeys` و `_testedCount` وجود ندارد.
4. **هیچ تستی برای `ServerQualityCalculator` وجود ندارد** → باگ ping=0 در CI دیده نمیشود.
5. تست اتصال/بستن probeها به رابط فیزیکی و رفتار captive portal وجود ندارد (`AutoConnectProbeTransportTest` فقط طبقهبندی را میسنجد).
6. تست Composeای برای `AutoModeScreen` و نوتیفیکیشن وجود ندارد.

**نکتهٔ مثبت:** مکانیزم teardown/ownership (`TunnelStateWatcher`، `TeardownCompletion` identity gating، `VpnOwnerUidReader` + `OwnedVpnDecision`)بسیار دقیق است؛ مقایسهکنندههای رتبهبندی transitive و پایداراند؛ ذخیرهٔ origin دارای `@SerializedName` صریح و decoder امن است؛ هیچ credential متنی در لاگها یافت نشد (`vpn`/`vpn` credential عمومی پروتکل VPN Gate است).

---

## ۴. سایر بخشهای `app`

### بالا

- **`AndroidManifest.xml:103`** — سرویس OpenVPN با `foregroundServiceType="specialUse"` بدون `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" .../>` تعریف شده است.targetSdk 37 است؛ اعلامیه ناقص میتواند باعث رد Play Console یا امتناع سیستم از `startForeground()` در Android 14+ شود.  
  **رفع:** اضافه کردن subtype `vpn` (همانطور که سرویس SoftEther آن را درست انجام داده و counterpart در `SoftEtherClient/AndroidManifest.xml` وجود دارد).
- **`MainActivity.kt:86-90` (با `:45-56`)** — گیرنده پخش با `RECEIVER_EXPORTED` ثبت شده و هر نصبشده دیگری میتواند `ACTION_CLEAR_CACHE` را پخش کند تا لیست سرورها (`postValue(null)`) پاک شود. از `RECEIVER_NOT_EXPORTED` استفاده کنید (همه فرستندهها درون پروسساند).
- **`ui/screens/status/StatusViewModel.kt:151, 290-295`** — `connectionName` از `mVpnGateConnection!!` استفاده میکند در حالی که `AppRoot.kt:124-132` اجازه ورود به تب Status با `lastVPNConnection == null` را میدهد (حالت SSTP-only) → NPE و کرش تب Status. fallback null-safe اضافه کنید.
- **`.gitlab-ci.yml:20, 46, 64-65`** —jobها به flavor `free` ارجاع میدهند ولی `app/build.gradle:107-129` فقط flavor `pro` دارد → pipeline دائماً قرمز است.
- **`app/src/androidTest/.../ExcludeAppsTest.kt:117`** — به `vn.unlimit.vpngate.adapter.AppSelectionAdapter` ارجاع میدهد که وجود ندارد → `connectedAndroidTest` حتی کامپایل نمیشود.

### متوسط

- **`App.kt:120`** — `allowMainThreadQueries()` بهصورت سراسری Room را روی main thread مجاز میکند؛ خواندنهای واقعی روی main در `App.kt:227,244`، `ConnectionListViewModel.kt:302` و `ExcludeAppsManager.kt:104` رخ میدهد. حذف شود + IO scope.
- **`utils/DataUtil.kt:160-162`** — `setIntSetting(key, retVal--)` مقدار قدیمی را ذخیره و مقدار کاستهشده را برمیگرداند → شمارندهٔ مخفی کردن پیام اپراتور هرگز کاهش نمییابد (`DetailActivity.kt:497`). رفع: `setIntSetting(key, retVal - 1); return retVal - 1`.
- **`ui/screens/home/HomeScreen.kt:155-178`** — فیلتر کردن `vpnGateConnectionList.value` (LiveData مشترک) درون `withContext(Dispatchers.IO)` و mutate درجا توسط `ServerListSearchState.kt:16-22` → race با publish شدن لیست جدید و خواندن main thread. کپی عمیق قبل از فیلتر.
- **`data/remote/SourceIdentity.kt:281-283`** — IP literalها فقط چک محدودهٔ octet میشوند و `isGlobalIpv4()` تنها برای mirrorها اعمال میشود؛ `http://192.168.1.1/servers.csv`یا ارسال آن بهعنوان emergency sourceقبول میشود (SSRF/time probing در صورت دستکاری تنظیمات/backup). در `parse()` اعمال شود.
- **`data/remote/SourceValidation.kt:354-368` + `network_security_config.xml:9-17`** — mirrorهای HTTPای accept میشوند که سیاست cleartext خودشان مسدود میکند؛ کشف mirror عملاً در پروداکشن مرده است. HTTPS اجباری شود.
- **`SplashActivity.kt:56-61`** — App Link (`app.vpngate-connector.com`, `autoVerify=true` در مانیفست:66-75) بدون انتقال `intent.data` به MainActivity است → deep link هیچمسیریابی نمیشود.
- **ساخت:** Kotlin `2.4.0` در برابر KSP `2.3.12` (`build.gradle:3,18`)؛ هم `annotationProcessor` و هم `ksp` برای Room؛ `lifecycle-extensions:2.2.0` منسوخ در کنار lifecycle 2.9.4؛ Retrofit 3.0.0 واسطههای unused. همساز کنید، حذف کنید.
- **`app/build.gradle:174-181`** — variant.زمان اشکالزدایی باید `CI` تنظیم شود تا تستها اجرا شوند. debug را محلی هم فعال بگذارید.
- **identity سرور OpenVPN (`DetailActivity.kt:1089-1105`, `StatusViewModel.kt:1075-1084`)** — هشدار `SEC-F5` تنها لاگ/Toast است و اتصال بدون `ca`/`remote-cert-tls` ادامه مییابد. حداقل تأیید صریح کاربر یا refuse پیشفرض.
- **`utils/ExcludeAppsManager.kt:36-50`** — delete سپس insert در یک transaction نیست و از `delay(100)` برای «اطمینان» استفاده شده؛ شمارش دکمه با `notifyAppsSaved` ناسازگار است. `@Transaction` + حذف delay + شمارش واحد.

### کم اهمیت

- ترجمهها: `values/strings.xml` ۴۲۴ رشته، `values-fa` ۳۸۳ (۴۱ کم)، `values-vi` ۳۲۹ (۹۵ کم) — کلیدهایی مانند `auto_mode_error_no_network` و `emergency_source_*` به انگلیسی fallback میشوند.
- `proguard-rules.pro:22-27,34-49,76-123` شامل Glide، `ServerActivity`/`PaidServer` و Retrofit است که وجود ندارند.
- `WRITE_EXTERNAL_STORAGE` در مانیفست:9-10 بدون استفاده واقعی اعلام شده است.
- `DetailActivity.kt:1081` — `config.toByteArray()` از charset پیشفرض پلتفرم استفاده میکند؛ `Charsets.UTF_8` صریح کنید.

---

## ۵. SoftEtherClient

### بحرانی

**S1 — رهایی اجباری slot زمانی که عملیات بومی هنوز در حال اجراست**  
`NativeLifetimeRegistry.kt:453-469, 492-508` + `SoftEtherVpnService.kt:575-604, 632-666` + `ConnectionController.kt:392-402`

```
"FORCED_RELEASE_AFTER_TIMEOUT: ... native operation may still be running. Releasing slot to unblock new attempt."
```

`abandonWedgedConnectingAttemptIfAny()` فقط وضعیت Kotlin را پاک میکند و destroyای dispatch نمیکند، سپس lease بهزور پس گرفته میشود. در حالی که رشتهٔ تلاش قبلی هنوز داخل `nativeConnectWithHub` مسدود است، کنترلر دومی میتواند وارد `nativeCreate()` شود — دقیقاً همان همزیستی دو structای که registry برای جلوگیری از آن ساخته شده. نتیجه: همپوشانی socket/fd/native thread دو نشست.

**رفع:** slot رهاییشدهٔ اجباری را برای تمام طول عمر پروسس terminal کنید (الگوی `NativeHandleSlot.isLost`) یا cancellation بومی واقعی اضافه کنید. نباید timeout زمانی را proof اتمام native دانست.

### بالا

- **S2 — کالبک تأخیری 상태 post-teardown وضعیت terminal را بازمینویسد**  
  `SoftEtherVpnService.kt:1689-1690, 1752-1757, 1764-1766`: `pendingStateRunnable` بعد از ۲۰۰ ms وضعیتconnecting را broadcast میکند؛ `stopVpn()` (`1115-1310`) فقط مستقیم `sendConnectionStateBroadcast(STATE_DISCONNECTED)` میفرستد ( `:1028` pattern مشابه) و کالبک معوقه را حذف نمیکند (تنها `updateConnectionState` terminal آنرا حذف میکند). `GlobalVpnTracker` و `TunnelStateWatcher` ممکن است وضعیت قدیمی را زنده بگیرند. رفع: `removeCallbacks` در `stopVpn()`/`onDestroy()` + generation capture.
- **S3 — تشخیص مرگ اتصال keepalive عملاً فعال نمیشود**  
  `ConnectionController.kt:1220-1225` روی هر poll خالی (`nativeReceiveBatch()==0`) `keepAlive.recordReceived()` را صدا میزند و `:1641-1654` تنها `recordSent()` مینویسد («handled in native layer»). از آنجا که `KeepAliveManager.kt:93-103` فقط بر اساس `now - lastReceivedTime > timeoutMs` Vie میشود، deadline دائماً ریست میشود و `isConnectionDead()` هرگز true نمیشود. کاربر «متصل» میماند در حالی که traffic وجود ندارد. رفع: تمایز idle-poll از keepalive واقعی در native و محاسبه بر اساس `TunnelHealth.lastRxAdvanceAtMs`.

### متوسط

- **S4 — TOFU ip را اشتباه برچسب میزند و identity را bind نمیکند** (`ConnectionController.kt:574-581`) — `ServerKey(hostname = config.serverHost, ip = config.serverHost, ...)` در حالی که مستندات `ip` را «resolved IP» تعریف میکنند (`ServerFingerprintStore.kt:81-93`)؛ native با `SSL_VERIFY_NONE` کار میکند (`ConnectionController.kt:723-729`). DNS rotation/poisoning با همان certificate بهعنوان MATCH پذیرفته میشود. `ServerFingerprint.isWellFormed` در مسیر verify استفاده نمیشود. رفع: ارسال IP واقعی peer و الزام pin 64-hex معتبر.
- **S5 — `currentState` بدون lock/volatile از چند coroutine نوشته میشود** (`ConnectionController.kt:178-182, 389, 637, 829, 1356-1357, 1751-1780`) و `:1356` رویداد CONNECTING تکراری emits میکند.
- **S6 — لیست listenerهای سراسری بدون synchronisation ویک callback روی thread ثبتکننده اجرا میشود** (`SoftEtherVpnService.kt:166-169, 243-249, 289-305`).
- **S7 — TUN fd توسط سه مالک بسته میشود** (`TunTerminal.kt:45-46, 82-87`، `ConnectionController.kt:990-996`، `TunOwnershipCoordinator.kt:234-243`) — `dup` نشده و close دوباره با بازیافت شدن fd number میتواند به منبع دیگری آسیب بزند.
- **S8 — `JNI_OnLoad` پرچم SIGPIPE را سراسری تغییر میدهد** (`src/main/cpp/jni/softether_jni.c:134-139`) — `signal(SIGPIPE, SIG_IGN)` تمام پروسس را تحت تأثیر قرار میدهد. از `MSG_NOSIGNAL` استفاده شود.

### کم اهمیت و تست

- لاگ شامل IP محلی (`:366`) و `Build.FINGERPRINT` بهعنوان `osProductId` (`:374`) است (پروتکلrequired، ولی بررسی policy لاگ توصیه میشود).
- `SoftEtherVpnService.kt:431` — notification id برابر `hashCode()` رشته channel است؛ ثابت صریح بدهید.
- `SoftEtherClient.kt:110-152` — `externalHandle ?: nativeHandle` بدون guard مالکیت؛ residual است ولی قابل پاکسازی.
- **شکاف تست:** تستی برای `KeepAliveManager` وجود ندارد (دقیقاً مربوط به S3)، چرخهٔ `pendingStateRunnable` تست نشده، `verifyTofuAfterHandshake()` و reconnect pinning تست نشده، assert بومی `nativeReceiveBatch` فقط کامنت است (N-01..N-05)، androidTestها به سرورهای زنده وابستهاند و آفلاین اجرا نمیشوند.

**نکته مثبت:** بررسی مرزهای آرایه JNI کامل است (`softether_jni.c:403-419, 443-456, 479-495, 541-558, 830-836, 862-870`)؛ fingerprint extraction تحت `ssl_lifetime_lock` است؛ `NativeHandleSlot` معنای terminal `Lost` دارد و تست شده؛ CMake release کتابخانه تست و نمادها را حذف میکند؛ الگوی `START_NOT_STICKY` و self-exclusion مستند است.

---

## ۶. sstpClient و vpnLib

### بالا

- **T1 — قطع اتصال، cleanup غیرهمزمان خود را لغو میکند**  
  `SstpVpnService.kt:301-315` → `Controller.kt:330-374` cleanup را روی `bridge.service.scope` launch میکند، سپس `close()` → `stopSelf()` → `onDestroy()` (`:589-601`) همان scope را cancel میکند. `destructionJob` شامل `CALL_DISCONNECT`، `cancelClients()`، `observer.close()` و `closeTerminals()` ممکن است نیمهکاره بماند → socket/TUN/NetworkObserver نشت میکند. رفع: cleanup joinable و منتظر بماند یا synchronously قبل stopSelf.
- **T2 — نوشتن SharedPreferences به ازای هر بستهٔ IP**  
  `SstpVpnService.kt:604-619, 664-671` ← `process.kt:315` و `OutgoingManager.kt:104`. برای هر packet یک `Editor` + `apply()` → contention و disk write در pps بالا. تجمع در حافظه و ذخیرهٔ دورهای در `publishTrafficSnapshot()`.

### متوسط

- **T3 — مسیرهای اتصال اپ، validatePrefs را رد میکنند** (`SstpVpnService.kt:224-231` hostname verification وقتی `SSL_DO_VERIFY=false` غیرفعال است؛ تنها `SstpTileService.kt:82` اعتبارسنجی میکند؛ `DetailActivity.kt:934-956` و `AndroidConnectionAdapter.kt:880-899` مستقیم start میکنند) → تنظیمات قدیمی/دستکاریشده میتواند TLS را بدون verify رها کند. `checkPreferences()` قبل از هر start.
- **T4 — URI خالی SAF بهجای null، validate را دور میزند** (`preference/accessor/uri.kt:8-11` + `check.kt:52` + `SSLTerminal.kt:121-124` → `DocumentFile.fromTreeUri(...)!!` NPE).
- **T5 — route نامعتبر مانع بالا آمدن تونل نمیشود** (`IPTerminal.kt:93-95, 162-186` نتیجه Boolean `addCustomRoutes()` نادیده گرفته میشود و `:113` باز هم `PROCEEDED` ارسال میکند).
- **T6 — EOF در TUN به جای shutdown کنترلشده exception میدهد** (`IPTerminal.kt:194-198` → `position(-1)`); مشکل مشابه در `SSLTerminal.kt:456-460` حل شده ولی اینجا نه.
- **T7 — بفر DPC1 بدون سقف** (`vpnLib/.../AppCustomControl.kt:40,56` — `pendingMessage += ...` بدون timeout/حد → OOM از طرف management peer)؛ `Base64.decode` خارج از JSONException handler است.
- **T8 — عدم همسازی KSP/Kotlin** (همان M-build در `build.gradle:3,18`) میتواند codegen Room را بشکند.

### کم اهمیت

- `PAPFrame.kt:47-66` — طول ACK/NAK بر اساس getter خالی محاسبه میشود و همیشه ۰ است.
- `PAPClient.kt:49-56` + `PAPFrame.kt:40-43` — credential US-ASCII و طول truncate به بایت (با `vpn`/`vpn` فعلی latent است).
- `SaveCertFragment.kt:13-20` — `getByteArrayExtra` میتواند null باشد و محدودیتی بر حجم cert صادرشده نیست.
- `SstpVpnService.kt:107-119, 142-150` — لیست `trafficListeners` بدون synchronisation و state companion بین restartها باقی میماند.
- `ProfileEncryption.kt:25,45,57` — `mMasterKey!!` سراسری و non-volatile قبل از init توسط `ProfileManager.java:59` → NPE.
- `check.kt:43-45` — `SSL_PORT=0` پذیرفته میشود.

### تست

تنها `sstpClient/src/test/.../HostnameValidationTest.kt` وجود دارد و تست سوم آن (:40-53) متن سورس را میسنجد، نه رفتار. **هیچ تستی** برای قرارداد action (A1 را همان لحظه میگرفت)، MS-CHAPv2/MD4 vectors، مرزهای فریم، certificate/hostname verification، چرخهٔ reconnect (T1) و DPC1 fragmentation وجود ندارد.

**نکته مثبت:** hardening سیستماتیک مرزها در `IncomingManager.kt:46-61, 178-276`، `process.kt:299-319` و `SSLTerminal.kt:60-71, 289-303, 350-354, 456-460`؛ generation guard قوی (`SstpVpnService.kt:319-344, 383-409`)؛ IPv6CP graceful degradation؛ رمزگذاری پروفایل بدون downgrade به plaintext؛ عدم لاگ کردن credentialها.

---

## ۷. جدول اولویت اقدام

| اولویت | مورد | اثر |
|---|---|---|
| **P0** | A1: تصحیح ثابت action مربوط به SSTP در adapter | اتوکانکت SSTP از «کشتن سرویس» به اتصال واقعی تغییر میکند |
| **P0** | S1: رهایی اجباری native slot | جلوگیری از همزمانی دو session بومی و کرش/فساد fd |
| **P0** | A2: لغو reconnect job در stop/disconnect | دکمه stop واقعاً متوقف میکند |
| **P1** | A3: حفظ هویت سرور ترکشده | دکمه «سرور بعدی» واقعاً پیش میرود |
| **P1** | S3: درست کردن keepalive dead-detection | وضعیت «متصل بدون ترافیک» حذف میشود |
| **P1** | S2: حذف pendingStateRunnable موقع stop | وضعیت UI بعد از قطع، دوباره connecting نمیشود |
| **P1** | H1/H2/H3/H4/H5 broaden app (FGS subtype، receiver، NPE، CI flavor، instrumented test) | پایداری نصب و CI |
| **P2** | A4-A10، T1-T8، M1-M11 | thread-safety، retention، امنیت، عملکرد، تمیزی ساخت |
| **P3** | localization، ProGuard، dead deps، accessibility | کیفیت و نگهداری بلندمدت |

## ۸. پیشنهادهای ساختاری برای ارتقاء

1. **استخراج ماشین وضعیت اتوکانکت از Android:** `AutoModeController` خالص JVM است، ولی توسط ۹ provider به `App.instance` وصل شده است. تزریقconstructor وابستگیها، تستهای determinism و فاجعهسنجی (fault injection) را ممکن میکند.
2. **یک «cyclic token» واحد برای تمام coroutineهای اتوکانکت:** run/skip/stop/reconnect/network-retry/liveness/watchdog باید نسل جاری را چک کنند؛ این کار تمام چهار باگ لایف سایکل بالا را از ریشه حل میکند.
3. **قانون «هر منبع یک مالک»:** TUN fd، native handle و notification channel را تکمالک کنید (`TunOwnershipCoordinator` الگوی خوبی است).
4. **استراتژی تست:** اضافه Robolectric + `coroutines-test` + Turbine، hermetic JNI testها، و اجرای `connectedAndroidTest` در CI؛ تستهای source-text scanning (`LiveDataPublishThreadTest`, `HostnameValidationTest`) را با تست رفتاری جایگزین کنید.
5. **بهداشت ساخت:** هماهنگی KSP/Kotlin، حذف flavor/setups مرده، اعمال `allowMainThreadQueries=false`، اضافه کردن lint baseline و R8، و پاکسازی localization.
6. **امنیت:** hostname/Certificate pinning واقعی در SoftEther و SSTP،رد پروفایلهای فاقد CA، ممنوعیت IP خصوصی در تمام منابع دور، و backward-compatible FGS subtype.

---

*این report صرفاً تحلیل ایستا (static) است؛ توصیه میشود قبل از اعمال تغییرات، چهار مورد P0 با تستهای هدفمند روی دستگاه واقعی repro شوند.*
