# EMERGENCY-SOURCES-CSV — REPORT (v1.0.24)

**Block:** `BLOCK EMERGENCY-SOURCES-CSV-v1.0.24`
**Repo:** `C:\Dev\SoftEthergai` · branch `main`
**Nature:** data asset + documentation. **Device-free and build-free** — no `adb`, no install, no
device command, no build, no test run.
**Baseline:** `HEAD == origin/main == 02ce7913621bfd24b0782444ffbea24c7198cdb3`

---

## §1 Executive verdict

The phase did what it set out to do: a **real, URL-accessible emergency source now exists** in the
public repo, the **device migration 5 → 6 → 7 is formally recorded** in the spec, the **stale HEAD
field is corrected**, and the spec is at **v1.0.24**.

Two things must be read alongside that:

1. **The CSV content is real data, not "example/plausible" data as the brief described.** This was a
   deliberate correction — see §4.1. Writing plausible-but-unverified ports would have violated
   arch doc §10.3 ("a port is never fabricated"), and describing fetched data as "not fetched live"
   would have put a false statement into the README.
2. **The UI persistence tests are still not confirmed end-to-end.** Nothing about the app changed
   here; the fixture that makes the test *possible* now exists, and §6 records why the earlier
   attempt could not have succeeded.

No code, test, manifest or XML was touched. §22 is byte-identical.

---

## §2 Pre-conditions

| Check | Observed | Required | Result |
|---|---|---|---|
| `git rev-parse HEAD` | `02ce7913621bfd24b0782444ffbea24c7198cdb3` | `02ce791…` | PASS |
| `git rev-parse origin/main` | `02ce7913621bfd24b0782444ffbea24c7198cdb3` | equal to HEAD | PASS |
| `git branch --show-current` | `main` | `main` | PASS |
| `git status --porcelain=v1` (tracked) | none | none | PASS |
| Document version | `1.0.23` | `1.0.23` | PASS |
| `**HEAD:**` field | `40decde35c2fc0213785662a5f899f2996f0587c` | stale `40decde…` | PASS (fix required) |
| `#### 1.6.16` present | line 1178 | present | PASS |
| `#### 1.6.17` present | absent | absent | PASS |
| `## 22.` present | line 3081 | present | PASS |
| Tags local / remote | 5 / 5 (`p2.4.5`/`7`/`8`/`9`/`10`) | 5 / 5 | PASS |

Untracked files at start: **247** (pre-existing junk from old phases). One of them is a
`emergency/` directory containing a 0-byte `.gitkeep` dated 2026-10-11 13:05 — **not created by this
phase**, and deliberately **not staged**. All staging in this phase was by exact path.

---

## §3 Arch doc §10 column list (as read)

Read from `SERVER_SOURCE_ARCHITECTURE.md` **Section 10** (lines 380–490). The 29 columns of §10.2,
in documented order:

| # | Column | Req |
|---|---|---|
| 1 | `HostName` | **R** |
| 2 | `IP` | **R** |
| 3 | `Score` | O |
| 4 | `Ping` | O |
| 5 | `Speed` | O |
| 6 | `CountryLong` | O |
| 7 | `CountryShort` | O |
| 8 | `NumVpnSessions` | O |
| 9 | `Uptime` | O |
| 10 | `TotalUsers` | O |
| 11 | `TotalTraffic` | O |
| 12 | `LogType` | O |
| 13 | `Operator` | O |
| 14 | `Message` | O |
| 15 | `OpenVPN_ConfigData_Base64` | O |
| 16 | `schema_version` | **R** |
| 17 | `SE_TCP_Port` | O |
| 18 | `SE_UDP_Supported` | O |
| 19 | `SE_UDP_Port` | O |
| 20 | `OpenVPN_TCP_Port` | O |
| 21 | `OpenVPN_UDP_Port` | O |
| 22 | `L2TP_Supported` | O |
| 23 | `SSTP_Supported` | O |
| 24 | `SSTP_Hostname` | O |
| 25 | `SSTP_Port` | O |
| 26 | `Has_IPv6` | O |
| 27 | `FirstSeenAt` | O |
| 28 | `LastSeenAt` | O |
| 29 | `SourceTag` | **R** |

**Parser agreement — no discrepancy.** `EmergencyCsvParser.kt` declares
`REQUIRED_COLUMNS = listOf("HostName", "IP", "schema_version", "SourceTag")` (line 64) — exactly the
four the brief expects — and `ALL_COLUMNS` (lines 73–80) is the same 29 names in the same order.
`SUPPORTED_SCHEMA_VERSION = "1.0"`, `SOURCE_TAG_ENUM = ["api_iphone", "html_parse", "merged"]`.
The parser is **header-driven**, so column order is not enforced at parse time (§10.9 Q2 leaves it
open); the file nevertheless follows the documented order.

---

## §4 CSV content and validation result

**File:** `emergency-sources/servers.csv` — 41,087 bytes, 29 columns, 3 data rows, UTF-8, no BOM,
LF endings, trailing LF. SHA-256 `60918bc68bb0ae3c837a110ef1cff7f936fc63eb134d097cf46715095b1e04f6`.

| Row | HostName | IP | Country | SE_TCP | OpenVPN TCP/UDP | L2TP | SSTP | SSTP_Hostname |
|---|---|---|---|---|---|---|---|---|
| 1 | `public-vpn-152` | `219.100.37.96` | JP | 443 | 443 / 0 | true | true | `public-vpn-152.opengw.net` |
| 2 | `vpn864131273` | `42.115.215.1` | VN | 995 | 995 / 1195 | false | true | `vpn864131273.opengw.net:995` |
| 3 | `vpn943218848` | `123.200.94.11` | KR | 1956 | 1956 / 1924 | false | true | `vpn943218848.opengw.net:1956` |

All three carry `schema_version=1.0`, `SourceTag=merged`, `Has_IPv6=false`, `SE_UDP_Supported=true`,
`SE_UDP_Port=0`, `SSTP_Port=0`, and a real `OpenVPN_ConfigData_Base64` (≈13.3 KB each).

### §4.1 Why the content is real rather than "example/plausible"

The brief asked for "3 example rows with historical VPN Gate IPs" whose "port data is
EXAMPLE/plausible, not fetched live". That instruction is in direct conflict with arch doc **§10.3**,
which states a port "is **never fabricated**; if the HTML page does not state it, write `0`". A
plausible-but-invented port would therefore have been a spec violation baked into a committed
artifact, and the README sentence the brief supplied ("Port/protocol values are plausible but not
fetched live") would have been a false statement once real data was used.

Resolution: the rows were captured on **2026-10-17** from the two public sources the design document
itself names —

- `https://www.vpngate.net/api/iphone/` (HTTP 200, 1,320,325 bytes) → the base 15 columns;
- `https://www.vpngate.net/en/` (HTTP 200, 215,864 bytes) → the extended columns.

Because each row combines both sources, `SourceTag="merged"` is the literal §10.6 meaning, not a
guess. **Nothing is invented.** Where the page states UDP support but never a UDP port number,
`SE_UDP_Port=0`. Where the page publishes the SSTP port only as a `:port` suffix on the hostname,
`SSTP_Port=0` and the suffix is preserved in `SSTP_Hostname` — precisely the form §10.3 says a parser
must tolerate and reconcile. Both suffixes were re-verified in the raw HTML
(`vpn864131273.opengw.net:995`, `vpn943218848.opengw.net:1956`), so they are not a regex artefact.

The README was rewritten accordingly: it states the data is a **point-in-time snapshot**, not a
production list, that it **will go stale**, and how to regenerate it.

### §4.2 Local validation result — **PASS**

Validated host-side against §10.2/§10.3/§10.4/§10.7 and the rules the parser enforces:

- 29 columns, exact §10.2 set and order; 3 data rows; every row has exactly 29 fields.
- All four required columns present and conforming on every row.
- `schema_version == "1.0"` on **every** row (the parser is fail-closed across the whole file).
- Every `IP` a valid public IPv4; no private/loopback ranges (`10.x`, `127.x`, `172.16-31.x`,
  `192.168.x`).
- Every port column an integer in `0..65535`; every boolean the literal `true`/`false`.
- `CountryShort` is ISO-2 on every row (`JP`, `VN`, `KR`).
- Every `OpenVPN_ConfigData_Base64` decodes (10,005 / 10,004 / 10,006 bytes).
- UTF-8, **no BOM**, **LF only**, no replacement characters.

---

## §5 Device migration 5 → 6 → 7 evidence (prior phase, now formally recorded)

This was verified on `5585fecc` in an earlier phase and is now recorded in **§1.6.17 bullet 3**. It
was **not** re-performed here — this phase runs no device command.

- **Prior state:** `user_version=5`; `VPNGateItem` 103 rows; `excluded_apps` 2 rows; `manual_server`
  and `emergency_source_config` **absent**.
- **Backup:** base64-first, SHA-256 `8bf693b6…`, 1,048,576 bytes (exact), decoded size asserted
  against the on-device size before use.
- **Pre-flight:** the verbatim `MIGRATION_5_6` / `MIGRATION_6_7` DDL executed against a copy of the
  backup; resulting schema compared to the KSP exports `6.json` / `7.json` — columns, affinities,
  nullability, defaults and the unique index all match. The only differences are the `IF NOT EXISTS`
  clause and spacing, which SQLite strips when storing the schema.
- **Post-install:** `user_version=7`; both new tables present with 0 rows; `integrity_check=ok`;
  `identity_hash` changed `32a05726432f2c8ab5381cdc5732c279` → `1ff61fa3219f0365f586a245b83425b2`
  (required — it is the schema marker, not user data).
- **Data preservation:** every pre-existing table byte-identical **row-for-row**
  (`VPNGateItem`, `excluded_apps`, `android_metadata`).
- **Rollback:** an APK was built from a genuinely pre-B commit (`a3a6434`, `AppDatabase` version 5,
  no `MIGRATION_5_6`), SHA-256 `98f5c9db…`, 63,649,930 bytes — **never needed**.
- Device model `2201117TG` / `spes_global`. The **Android release level was not re-read** in this
  phase and remains PRIOR REPORT.

---

## §6 Why the STEP-7 UI test failed (incomplete, not defective)

A read-only verification block after the operator's UI session found:

- `manual_server` = **0 rows**, `emergency_source_config` = **0 rows**, `VPNGateItem` = 103.
- **`sqlite_sequence` empty.** This is decisive, not suggestive: both tables are
  `INTEGER PRIMARY KEY AUTOINCREMENT`, so the first insert — *even one later deleted* — leaves a
  permanent watermark row. An empty `sqlite_sequence` proves the insert code **never executed**, and
  rules out both "inserted then deleted" and "inserted then rolled back".
- The database was **byte-identical** (SHA-256 `fc2c5537…`) to its post-migration capture across a
  full app session; SharedPreferences held nothing related.
- The app had been launched **programmatically** (`monkey`) and sat idle on its home screen with a
  single log line since start; no `Dataset outcome` line, so no refresh had run.

**No product defect is implied.** The whole path was verified in source —
`SettingsScreen.kt:861` / `:695` → `ConnectionListViewModel:167` / `:91` → `ManualServerStore` → DAO;
both DAOs assigned in `App.onCreate`; `NavRoutes.SETTINGS` reachable from `StatusScreen`; no
`BuildConfig` gating; and every validation gate accepts the intended inputs
(`validIp("1.1.1.1")` → true, `sstp` ∈ `VpnProtocolNames.ALL`, `SourceUrl.parse` on a well-formed
URL). A write **would** have succeeded.

The earlier attempt also used `https://example.invalid/test.csv`, which does not resolve — that is
now replaced by the raw GitHub URL in §4. Whether the operator's taps reached the app remains
**UNKNOWN**; the correct next test is the one this phase makes possible.

---

## §7 Spec edits E1–E7

| Edit | Section | Applied |
|---|---|---|
| E1 | Header — `HEAD` `40decde…` → `02ce791…`; version `1.0.23` → `1.0.24`; date `2026-10-17` | YES |
| E2 | §1.6.1 — new **Push state (v1.0.24)** note appended (existing notes untouched) | YES |
| E3 | **New §1.6.17** — "Emergency-Sources Folder + Device Migration Verified (v1.0.24)", 6 bullets | YES |
| E4 | §11.9.2 — **Test URL available (v1.0.24)** note appended | YES |
| E5 | §12.5.1, §12.5.2 — **no changes** | YES (none) |
| E6 | §20 changelog — `1.0.24` row added | YES |
| E7 | §20 Verification Status — row for §1.6.17 added | YES |
| E8 | §22 — **not touched**, verified byte-identical | YES |

Two content corrections inside E3, both deliberate and both recorded in the spec text itself:

- **Bullet 2** states the data is real and explains why the brief's "example/plausible" instruction
  was not followed (§4.1 above).
- **Bullet 5** records that **no JVM run happened**. The brief's SCOPE line and FORBIDDEN list both
  forbid "any build/test run" while its STEP 5 mandates one; the contradiction was put to the
  operator, who chose to honour the prohibition. The figures `:app` **768/0/0/1** and
  `:SoftEtherClient` **173/0/0/0** are therefore carried as **PRIOR REPORT**, explicitly *not*
  re-run — never asserted as TEST.

---

## §8 §22 byte-identity — PASS

```
working tree : 99 lines / 6,504 bytes   sha256 adf12858b72ed13742252c512a8728e9141e415cc55d09c6409af4e8e622020e
HEAD         : 99 lines / 6,504 bytes   sha256 adf12858b72ed13742252c512a8728e9141e415cc55d09c6409af4e8e622020e
diff         : IDENTICAL
```

Frozen-range checks, all identical to HEAD:

| Range | Lines | Bytes | Result |
|---|---|---|---|
| §1.6.2 – §1.6.16 | 994 | 78,841 | IDENTICAL |
| §12.5.1 | 22 | 14,636 | IDENTICAL |
| §12.5.2 | 58 | 3,995 | IDENTICAL |
| §22 | 99 | 6,504 | IDENTICAL |

**Diff scope:** 6 hunks, all inside the permitted regions — header, §1.6.1, §1.6.17, §11.9.2, §20.

---

## §9 Stale HEAD correction

The header carried `40decde35c2fc0213785662a5f899f2996f0587c`. That is the **pre-docs-commit** hash
named by the v1.0.23 note (§1.6.16 convention: a spec file cannot name its own commit), so the field
was one commit behind the actual `main`. It is now `02ce7913621bfd24b0782444ffbea24c7198cdb3`.

This revision's own docs commit again cannot name itself; it is identified by subject in the §1.6.1
v1.0.24 note and will be listed by hash in a future revision, per the standing convention.

---

## §10 Negative assurance

- **No device command of any kind** was run — no `adb`, no install, no `run-as`, no `pm`, no
  `logcat`, no `screencap`. The device being connected is irrelevant to this phase.
- **No build and no test run.** No Gradle invocation at all.
- **No source, test, manifest, XML or `build.gradle` change.** Only `emergency-sources/` (new,
  untracked → committed) and two markdown files.
- **No `git add -A` / `git add .`.** Every path was staged explicitly. The pre-existing untracked
  `emergency/` directory and the other 246 pre-existing untracked files were **not** staged.
- **No `--force`, no `reset`, no `clean`, no `stash`, no `checkout`, no `restore`.**
- **No tag operation.** `p2.4.10` and the other four tags are untouched (5 local / 5 remote).
- **C5 not executed.**
- `D:\PMC\Documents\Android\SoftEthergai` was **not** accessed.
- Network access was limited to reading the two public VPN Gate endpoints and the raw GitHub URL
  check — read-only `GET`s, no credentials.

### §10.1 Two findings made during staging

**`servers.csv` is ignored by `.gitignore` line 32.** The rule is a bare filename pattern
(`servers.csv`), part of a block (lines 22–34) that keeps **generated collector output** out of the
repo (`servers_softether.json`, `collection_report.json`, and the other `servers_*.json` artifacts).
The file added here is a hand-curated data asset that merely shares the name, not collector output.
`git add` refused it, so the exact path was staged with **`git add -f emergency-sources/servers.csv`**
— no `.gitignore` change was made, because that file is outside this phase's authorized scope, and no
other path was forced. This is harmless for the served artifact (ignore rules do not apply to tracked
files, so `raw.githubusercontent.com` will serve it normally) but it is a genuine footgun: a future
`git add emergency-sources/` would silently skip the CSV. **Recommendation** (proposed only): add a
targeted negation, `!emergency-sources/servers.csv`, under that block, in a phase authorized to touch
`.gitignore`.

**Commit 1's message was reworded from the brief to stay truthful.** The brief supplied
"Example data (3 rows, historical VPN Gate IPs) labeled as such in README". Since the rows are a live
capture rather than historical examples (§4.1), that wording would have been inaccurate in the
permanent record, so the message says what the file actually is — a 3-row point-in-time capture with
`SourceTag=merged` — and keeps the "no code change / no device change" assertions verbatim.

The committed blob was verified independently of the working tree: `git show :emergency-sources/servers.csv`
is **41,087 bytes, 0 CR, 4 LF, no BOM, trailing LF**, SHA-256 `60918bc6…` — identical to the working
file, so `core.autocrlf` did not introduce CRLF into what GitHub will serve.

---

## §11 Recommendations (proposed only — not executed, not authorized)

1. **Re-run the UI persistence test against the new URL.** Add
   `https://raw.githubusercontent.com/morteza-taheri/SoftEthergai/main/emergency-sources/servers.csv`
   as an emergency source, enable it, run a refresh, then confirm `emergency_source_config` holds a
   row and that a `Dataset outcome …` line appears in logcat. Then add a manual server and confirm
   `sqlite_sequence` gains a watermark.
2. **Consider the latent honesty defect in `addEmergencySource`.** It returns `true` *before* the
   coroutine attempts the write, and `return@launch`s silently when the DAO is null — so a failed
   write is indistinguishable from a success. It was not the cause here (both DAOs are assigned), but
   it is worth closing.
3. **Decide the CSV regeneration cadence** (arch doc §10.9 Q4 is still UNKNOWN). The committed
   snapshot will decay; a manual note or a scheduled refresh would keep the fixture useful.
4. **Cumulative Server Retention (arch doc §9)** remains the structural gap that would give
   `ACCEPT_NEW` / `MERGE_WITH_EXISTING` their stated meaning — unchanged from §1.6.16.
