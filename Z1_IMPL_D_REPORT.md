# Z1-IMPL-D REPORT — Manual Server Entry + MANUAL-Preservation Merge (v1.0.22)

**Roadmap §12.5.2 item D** — plus the preservation half of item G.
**Repository:** `C:\Dev\SoftEthergai` · **Branch:** `main` · **Device:** DETACHED (device-free phase)

---

## 1. Verdict

Item D landed. Manual servers persist in their own table and are **re-emitted on every accepted
write** of the server list, so an update that replaces the whole list can no longer delete the user's
own entries. Zero enabled manual servers leaves behaviour byte-identical, which is proved two ways.

Four commits, one fast-forward push. No device verification of any kind.

---

## 2. Pre-conditions (all PASS)

| Check | Result |
|---|---|
| `HEAD == origin/main` | `e4daa7b35ac44e427d3cd9928b11aff13a9d1d00` ✔ |
| Branch | `main` ✔ |
| Tracked modifications | 0 ✔ |
| Spec version | 1.0.21 ✔ |
| §1.6.14 present / §22 present | ✔ (lines 969 / 2818) |
| Tags local / remote | 5 / 5 ✔ |
| `SourceKind.MANUAL` present | ✔ (`SourceIdentity.kt:38`) |
| Baselines | `:app` 724/0/0/1 · `:SoftEtherClient` 173/0/0/0 · APK `b85a7aba…` |

---

## 3. §11.11 mapping

§11.11 defines **rules**, not a schema — it says so explicitly: *"Do not design or implement a new
database schema here."* `SERVER_SOURCE_ARCHITECTURE.md` §4.1 fixes the minimum as *"a single server
record (host/IP + at least one protocol+port)"*. The landed model is exactly that plus what the
feature needs to be usable. **No field was invented beyond it** (§1.6.15 bullet 0).

Every §11.11 rule that item D can satisfy is satisfied:

| §11.11 rule | How |
|---|---|
| "Manual servers must survive ordinary online server-list updates" | `withManualRows` at the single write (§6) |
| "a manually entered record … must not be silently overwritten" | Preservation is absolute; a value collision is written into `conflicts` (§38), never dropped |
| "Preserve source provenance … distinct, retrievable" | `manual` is its own authority group in `VpnServerMerger`; the entry lives in its own table |
| "A record missing from one partial source response must not automatically be deleted" | Manual rows are not part of any response, so a short response cannot remove them |

---

## 4. Model / entity / DAO

**`ManualServer.kt`** — `id, host, port, protocol, label, notes, enabled, createdAt, updatedAt`,
plus `fromEntity`/`toEntity`, `normalizeProtocol` (case-insensitive, canonical spelling wins),
`isValidHost` (= `VpnUtil.validIp`, IPv4 only — see §14), `isValidPort` (1..65535).

**`ManualServerEntity.kt`** — `@Entity(tableName = "manual_server")` with
`indices = [Index(value = ["host","port","protocol"], unique = true)]` and
`@ColumnInfo(defaultValue = "1")` on `enabled`.

**`ManualServerDao.kt`** — `observeAll()` / `listAll()` / `listEnabled()` / `findByKey()` /
`upsert()` / `deleteById()`. Mirrors `EmergencySourceConfigDao`.

**`ManualServerStore.kt`** (`repository/`) — the DAO is **injected**, so the store is pure JVM and the
preservation invariant is testable without Android, Room or the network. `add()` validates and refuses
at the boundary (returns `false`, stores nothing); `toItem()` maps each protocol to **only the columns
that protocol owns** and writes no telemetry for a server that was never crawled.

---

## 5. Migration 6 → 7 (verbatim DDL)

```sql
CREATE TABLE IF NOT EXISTS `manual_server` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    `host` TEXT NOT NULL,
    `port` INTEGER NOT NULL,
    `protocol` TEXT NOT NULL,
    `label` TEXT,
    `notes` TEXT,
    `enabled` INTEGER NOT NULL DEFAULT 1,
    `createdAt` INTEGER NOT NULL,
    `updatedAt` INTEGER NOT NULL);
CREATE UNIQUE INDEX IF NOT EXISTS `index_manual_server_host_port_protocol`
    ON `manual_server` (`host`, `port`, `protocol`);
```

Two additive statements. No `DROP`, no `DELETE`, no `TRUNCATE`, and no recreation of `VPNGateItem`,
`excluded_apps` or `emergency_source_config`. `AppDatabase.kt` registers the entity and moves to
**version 7**; `App.kt` registers `MIGRATION_6_7` and exposes `manualServerDao`.
`app/schemas/vn.unlimit.vpngate.db.AppDatabase/7.json` is the KSP export and is compared against this
DDL by `Migration6to7Test`.

---

## 6. Preservation invariant — exact code location

Two places, because they are two different promises.

### 6.1 Record-layer precedence — `merger/VpnServerMerger.kt`

```kotlin
private val SOURCE_PRIORITY = mapOf("api" to 0, "html" to 10, "manual" to 30)
```

`api 0 > html 10 > mirror 20 > manual 30`.

### 6.2 The write-boundary merge — `repository/ServerListUpdates.kt`

`internal fun withManualRows(networkRows, manualRows)`, called from
`ServerListUpdates.Store.replaceAll`:

```kotlin
override fun replaceAll(items: List<VPNGateItem>) {
    dao?.replaceAll(withManualRows(items, manual.rows))
}
```

The manual rows are read inside the `fetch` and `emergencyFetch` lambdas (the only suspend points
under the updater's gate) into a `LastManualRows` holder, because `Store.replaceAll` is deliberately
not suspend and `ServerListUpdater` is not in this phase's authorized file set.

**Why the write point and not a rung:** `ServerListUpdater` replaces the stored list wholesale, so the
only place a manual row can be guaranteed to survive is the composition of the list that gets written.
Doing it there also means it holds whichever rung supplied the network dataset — the primary fetch,
the repository's snapshot/stored/bundled-seed fallbacks, or the emergency rung from item C.

### 6.3 The policy decision — this was put to the operator

The phase brief's STEP 5 said *"MANUAL wins on collision (the user explicitly added it)."* The frozen
architecture document says the opposite in three places: §3.3 *"canonical (api/html) always wins over
emergency over manual"*, §4.2 *"never promoted above canonical"*, and line 180
*"Dedup preserves the record with the highest-priority source (canonical > emergency > manual)"*.

The operator confirmed the **arch-doc reading**, and the code follows it. Concretely:

- **Preservation is absolute.** A manual record is never deleted. `mergeRecords` dedupes by identity
  and merges and has no path that drops a record for being low-priority, and the write boundary
  re-emits the user's rows on every accepted write.
- **Precedence applies only to a value both sides claim.** When a manual entry and a collected record
  describe the same server and disagree on a scalar, the collected value wins — and the disagreement
  is written into the record's `conflicts` list (§38), so a manual record is never *silently*
  overwritten (§11.11).
- **At the row layer** a host the network already serves does not get a second, duplicate manual row;
  the entry itself is untouched in `manual_server` and is re-emitted on the next refresh in which the
  feeds stop listing that host (§4.3).

### 6.4 The tests that prove it

| Test | Proves |
|---|---|
| `no enabled manual server leaves the network dataset untouched` | `assertSame` — the caller's own list **instance** is returned |
| `a disabled manual server behaves exactly like none` | disabled ⇒ not re-emitted, but the row is kept |
| `an enabled manual server survives a refresh that replaces the list` | the row, its protocol, its port, its label, and its zero telemetry |
| `a manual server survives two refreshes back to back` | two consecutive replacements |
| `removing a manual server makes it disappear on the next refresh` | removal takes effect, and `assertSame` again |
| `a manual server whose host the network already serves is not added twice` | the collision rule, both directions |
| `manual is the lowest authority in the record-layer merge` | `sourcePriority` = 0 / 10 / 30 |
| `a colliding manual record survives and its value is recorded not discarded` | 1 merged record, canonical value wins, `conflicts` holds **both** values, `sources` still contains `manual` |
| `a manual record with no collected counterpart keeps every field` | the common case is untouched |
| `two manual servers on one host do not collide on the storage key` | one row per Room identity |

---

## 7. Wire-in

`ServerListUpdates` — no new rung. The manual merge is orthogonal to the rung chain: it runs on every
accepted write, not as a fallback. **Invariant: zero enabled manual servers ⇒ zero behaviour change**
— `withManualRows` returns its input **by identity** when the manual list is empty, so no caller
observes a different list, order or element set.

`ConnectionListViewModel` — `manualServers: StateFlow<List<ManualServer>>` plus
`addManualServer` / `removeManualServer` / `toggleManualServer`.
`SettingsScreen` — a "Manual Servers" section after the Emergency section: list with enable toggle and
remove, plus an add form (IPv4 address, port, protocol picker, optional label and notes), reusing
existing components. 16 new `manual_server_` strings.

One in-scope correction in an already-authorized file: `emergency_source_summary` still said *"Nothing
is fetched from them yet"*, which has been false since item C. It now describes the real behaviour.

---

## 8. UI / VM / strings

- `SettingsScreen.kt` +203 lines (section, `manualProtocolLabel` helper, `FilterChip` import).
- `ConnectionListViewModel.kt` +70 lines.
- `strings.xml` +19/−1.
- Protocol display names come from the record-layer vocabulary, so a manual row and a collected row
  name the same protocol identically.

---

## 9. Tests

**`:app` = 747 / 0 / 0 / 1** against a baseline of **724 / 0 / 0 / 1** — **+23** (16 in
`ManualServerPreservationTest`, 7 in `Migration6to7Test`). Zero failures, zero errors, ignored count
unchanged. **All 724 pre-existing tests pass unchanged**, including every `ServerListUpdater` case —
that is the regression evidence for the zero-behaviour-change invariant.

**`:SoftEtherClient` = 173 / 0 / 0 / 0** — identical to baseline.

One real defect was found and fixed during testing: `ManualServer.normalizeProtocol` lower-cased its
input while `VpnProtocolNames.L2TP_IPSEC` is `"l2tpIpsec"`, so every L2TP manual entry was silently
rejected as an unknown protocol. The test caught it; matching is now case-insensitive and the
canonical spelling is what gets stored.

---

## 10. Build

`CI=true ./gradlew :app:assembleProDebug` — **SUCCESSFUL**.
APK SHA-256 `f1d768c9aa1b56fb29ddaea0d773e0f8dfdf0841790dd8bf0e4bd0f8c19a4e33` (65,978,641 bytes).
Differs from the prior `b85a7aba…`, as expected for new code.

---

## 11. Diff-scope

Exactly the authorized set — nothing else:

| | File |
|---|---|
| NEW | `data/remote/ManualServer.kt`, `ManualServerEntity.kt`, `ManualServerDao.kt`, `repository/ManualServerStore.kt` |
| NEW | `app/src/test/.../repository/ManualServerPreservationTest.kt`, `app/src/test/.../db/Migration6to7Test.kt` |
| NEW | `app/schemas/vn.unlimit.vpngate.db.AppDatabase/7.json` (KSP export, staged in commit 1 per the brief) |
| MOD | `db/AppDatabase.kt`, `App.kt`, `merger/VpnServerMerger.kt`, `repository/ServerListUpdates.kt`, `viewmodels/ConnectionListViewModel.kt`, `ui/screens/settings/SettingsScreen.kt`, `res/values/strings.xml` |

Spec hunks are confined to §11.11, §1.6.1, §1.6.15 and §20, as the phase required. A status note I
first drafted under **§12.5.2** was removed once the hunk list showed it was outside the permitted
set; §12.5.1 and §12.5.2 are therefore byte-identical to v1.0.21. The changelog row that referenced
it was corrected to say so.

---

## 12. Spec edits (E1–E8)

- **E1** header → version **1.0.22**, HEAD `e4daa7b…`, `Last updated` 2026-10-16.
- **E2** §1.6.1 push-state note appended (existing notes untouched).
- **E3** new **§1.6.15** with 10 evidence-labelled bullets (a bullet 0 was added to record where the
  field set came from, so the rest could cite it).
- **E4** §11.11 annotated with the implementation status. Historical text retained in full, including
  its own "do not implement a schema here" sentence, which is now superseded for this table only and
  says so.
- **E5** §12.5.1 unchanged — no row tracks item D.
- **E6** §20 changelog row for 1.0.22.
- **E7** §20 verification row for §1.6.15 (HISTORICAL).
- **E8** §22 untouched — verified **byte-identical, 99 lines / 6,504 bytes**.

---

## 13. Frozen-section identity

| Section | Old | New | |
|---|---|---|---|
| §22 | 99 / 6504 | 99 / 6504 | **identical** |
| §1.6.2 – §1.6.14 | 793 / 62,904 | 793 / 62,904 | **identical** |
| §12.5.1 + §12.5.2 | — | — | **identical** |
| HEAD tags | 5 local / 5 remote | 5 local / 5 remote | unchanged |

---

## 14. Limitations and negative assurance

**Four limitations are recorded rather than hidden:**

1. **`SourceIdentity.kt` is not in this phase's authorized file set**, so `SourceKind.MANUAL` still
   falls through to `mirror_unnumbered` for its `sourceTag`. The merger's manual authority is keyed on
   the literal `"manual"`, which is correct for every record the merger can actually see in this
   phase — manual records never reach `SourceIdentity` today. **Recommendation:** add
   `MANUAL_TAG = "manual"` to `SourceIdentity` in a later phase.
2. **Two manual entries on the same IPv4** (different ports or protocols) both persist, but the stored
   table's primary key is `hostName`, so `VPNGateItem.dedupeForStorage` collapses them to one row by
   its existing Stage 2F-A "most connectable, then first inserted" order. Both entries remain in
   `manual_server` and in Settings; only one can occupy a row. Changing that ordering would mean
   editing `VPNGateItem.kt`, which is outside the authorized set — and inventing a second row policy
   was judged worse than reusing the one that already exists.
3. **An OpenVPN manual entry stores a port but no profile.** The app dials OpenVPN from
   `openVpnConfigData`, so such a row is not connectable until items E/F supply a profile.
4. **A manual address must be an IPv4 literal.** `normalizeServer` rejects a record without a valid
   IPv4 and `toConnection` returns null without one, so a bare DNS name would be accepted by the form
   and then silently dropped. It is refused at the `add` boundary instead. Evidence: **INFERENCE**
   from those two rejection points, not a verified device behaviour.

**Negative assurance:** no device or `adb` command; no tag operation of any kind; no force, no reset,
no clean, no stash, no checkout, no restore; no `git add -A` / `git add .`; no `build.gradle` change
and no new dependency; C5 not executed; `D:\PMC\Documents\Android\SoftEthergai` untouched; no existing
report file modified; `SERVER_SOURCE_ARCHITECTURE.md` untouched;
`EmergencyCsvParser.kt` / `EmergencySourceFetcher.kt` untouched. Every Gradle invocation ran with
`CI=true`. One push, fast-forward.

---

## 15. Recommendations (not executed)

1. Add `MANUAL_TAG` to `SourceIdentity.kt` so `SourceKind.MANUAL` no longer reads as a mirror.
2. Decide a row-level policy for two manual entries on one IPv4 (or surface it in the Settings UI).
3. Device phase: add a manual server, run a live refresh, and confirm the row survives it — plus a real
   6→7 database upgrade. Both are currently **UNKNOWN**.
4. Items E/F (text/file/share import) would also close limitation 3.
5. Cumulative Server Retention (arch doc §9) remains the structural gap: an update still replaces
   rather than merges.
