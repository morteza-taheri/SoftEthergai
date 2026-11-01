# D1 — Default Excluded-Apps Candidates (BLOCK v1.0.56, PART B)

**Status: CONFIRMED AND SHIPPED.** The operator replied `CONFIRM D1: ALL CLASSIFIED-IRANIAN`.
The 12 clearly-Iranian package names are now in `app/src/main/assets/default_excluded_apps`.
Recorded in `PROJECT_SPECIFICATION.md` §1.6.54; document version 1.0.58.

Source: `excluded_apps` table of the on-device database `vpn_gate_connector`
(15 rows), read read-only via `run-as` from device `5585fecc` (Xiaomi 2201117TG,
Android 13 / API 33, package `vn.unlimit.vpngatepro`, versionCode 144,
versionName 2.4.11-dev). Evidence label: **PHYSICAL DEVICE** (database read) +
**SOURCE CODE** (the asset and its loader rules).

`appName` is the label the app itself resolved from the device's
`PackageManager` at the moment the operator excluded each app, so it is the
device-resolved label. `dumpsys package` on Android 13 does not expose an
`applicationLabel=` field, so no second label source was available.

All 15 packages are currently **INSTALLED** on the device (`pm path` returns an
APK path for every one).

## Full table

| # | Package | Device label | Installed | Proposed classification | Reason |
|---|---------|--------------|:---------:|-------------------------|--------|
| 1 | `app.rbmain.a` | Rubika | yes | **clearly Iranian** | Rubika — Iranian social/messaging platform. |
| 2 | `cab.snapp.passenger` | Snapp | yes | **clearly Iranian** | Snapp — Iranian ride-hailing service. |
| 3 | `com.byagowi.persiancalendar` | Persian Calendar | yes | **clearly Iranian** | Persian (Jalali) calendar; Iranian developer. |
| 4 | `com.deepseek.chat` | DeepSeek | yes | **not Iranian** | DeepSeek is a **Chinese** AI assistant (Hangzhou, China). Looks like a personal install. |
| 5 | `com.farsitel.bazaar` | Bazaar | yes | **clearly Iranian** | Cafe Bazaar — the Iranian Android app store. |
| 6 | `com.google.android.projection.gearhead` | Android Auto | yes | **not Iranian** | Google's Android Auto. §1.6.47 records this as the device's **user-added** exclusion, kept deliberately. |
| 7 | `com.myirancell` | My Irancell | yes | **clearly Iranian** | MTN Irancell — Iranian mobile operator self-service. |
| 8 | `com.pmb.mobile` | همراه بانک ملت (Bank Mellat) | yes | **clearly Iranian** | Bank Mellat — Iranian bank. |
| 9 | `com.rahand.qbank` | QBank | yes | **clearly Iranian** | Iranian exam question-bank app (Rahand). |
| 10 | `ir.bmi.bam.nativeweb` | Baam \| بام | yes | **clearly Iranian** | Baam — Iranian ride-hailing; `ir.` TLD prefix. |
| 11 | `ir.easytrader.orbis.m.twa` | ایزی تریدر اربیس (EasyTrader Arbis) | yes | **clearly Iranian** | Arbis — Iranian brokerage; `ir.` TLD prefix. |
| 12 | `ir.eitaa.messenger` | Eitaa | yes | **clearly Iranian** | Eitaa — Iranian messenger; `ir.` TLD prefix. |
| 13 | `ir.nasim` | Bale | yes | **clearly Iranian** | Bale — Iranian messenger; `ir.` TLD prefix. |
| 14 | `org.rajman.neshan.traffic.tehran.navigator` | Neshan | yes | **clearly Iranian** | Neshan — Iranian map/navigation (Tehran), by Rajman. |
| 15 | `vn.unlimit.vpngatepro` | Self | yes | **not a user app** | The app's **own automatic self-exclusion**, inserted by code (`App.kt`), not by the operator. |

## Summary of the proposal

- **Clearly Iranian — 12 packages** (candidates to ship as the default):
  `app.rbmain.a`, `cab.snapp.passenger`, `com.byagowi.persiancalendar`,
  `com.farsitel.bazaar`, `com.myirancell`, `com.pmb.mobile`, `com.rahand.qbank`,
  `ir.bmi.bam.nativeweb`, `ir.easytrader.orbis.m.twa`, `ir.eitaa.messenger`,
  `ir.nasim`, `org.rajman.neshan.traffic.tehran.navigator`
- **Not Iranian — 2 packages** (must NOT ship as a default, they are the
  operator's personal/Google exclusions):
  `com.deepseek.chat` (Chinese AI assistant), `com.google.android.projection.gearhead` (Google Android Auto)
- **Not a user app — 1 package** (the app excludes itself automatically):
  `vn.unlimit.vpngatepro`

## Why the two exclusions matter

This asset ships to **every** user of the app as a first-run default, not just
to this operator. Shipping `com.deepseek.chat` or Android Auto would silently
exclude a Chinese AI assistant and a Google system app inside every user's
tunnel, which is neither Iranian nor wanted. §1.6.47 also records that
Android Auto's exclusion was added deliberately by the operator on this device,
so it belongs to this device's data, not to the shipped default.

`vn.unlimit.vpngatepro` is excluded by `App.kt`'s own seeding logic, so putting
it in the asset would be a duplicate.

## Confirmation received (B3)

```
CONFIRM D1: ALL CLASSIFIED-IRANIAN
```

Completed on 2026-10-24:

- `app/src/main/assets/default_excluded_apps` — 12 entries, sorted,
  de-duplicated, each with a trailing comment, under a provenance header.
- `DefaultExcludedAppsTest` — 12 → 16 tests; the obsolete "carries no entries
  yet" guard replaced by five real-content guards.
- `:app:testProDebugUnitTest` **1166/0/0/1** (92 suites), `:SoftEtherClient`
  **184/0/0/0** (13 suites), one `--rerun-tasks --no-build-cache` run.
- `PROJECT_SPECIFICATION.md` → 1.0.58, new §1.6.54 (and §1.6.55 for the
  launch crash found in the same block).

Not yet done: the `feat(excluded-apps): ship default Iranian app list` commit.
No commit was made, so nothing is staged and HEAD is unchanged.
