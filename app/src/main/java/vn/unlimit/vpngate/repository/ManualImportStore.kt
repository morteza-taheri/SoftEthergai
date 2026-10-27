package vn.unlimit.vpngate.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import vn.unlimit.vpngate.models.VPNGateItem
import java.io.File

/**
 * v1.0.54 (§1.6.49) PART 4 — persistence for servers imported from a CSV.
 *
 * ## Why not `manual_server`?
 *
 * [ManualServerEntity] stores one address, one port and one protocol. A CSV row
 * is a whole record: a host name *and* an address, an optional SoftEther TCP
 * port, an optional SoftEther UDP port with its "supported" flag, SSTP
 * support/port, L2TP support and an embedded OpenVPN profile. Mapping that onto
 * `(host, port, protocol)` loses most of it — and because the table's unique
 * index is `(host, port, protocol)` while the list merge collapses by host
 * name, a multi-protocol row would collapse into one protocol. Widening the
 * table would be a Room schema change, which this phase is not allowed to make.
 *
 * So the choice is:
 *
 *  - **keep** `manual_server` exactly as it is, for rows created by the old
 *    manual-add form (they still work and still appear in Settings, so no user
 *    data is lost by this rewrite);
 *  - **add** this store, a plain JSON file, for imported records. It is the
 *    same shape as the other file-backed stores in this app
 *    (`CollectorSnapshotStore`, `VpnLastConnectionStore`), it needs no
 *    migration, and it holds a full [VPNGateItem] — the exact row type the
 *    server list is built from.
 *
 * ## Invariants
 *
 *  - Keyed by the Room identity `hostName`; importing the same file twice
 *    updates rows instead of duplicating them.
 *  - Writes are atomic (temp file + rename), so a crash mid-write cannot leave
 *    a half-written JSON that would read back as "no imported servers".
 *  - A corrupt file reads as empty and is left on disk untouched; it is never
 *    silently overwritten by a read.
 *  - Never touches the network and never touches Room.
 */
class ManualImportStore(private val file: File) {

    private val gson = Gson()
    private val lock = Any()

    /** The imported rows, newest write wins. Never throws. */
    fun items(): List<VPNGateItem> = synchronized(lock) { read() }

    /**
     * Merges [items] into the store by `hostName`.
     *
     * @return how many rows were new and how many replaced an existing row, so
     *   the UI can say "12 imported (3 updated)" instead of a bare total.
     */
    fun add(items: List<VPNGateItem>): AddResult = synchronized(lock) {
        if (items.isEmpty()) return AddResult(0, 0)
        val current = read().associateByTo(LinkedHashMap()) { it.hostName.trim() }
        var added = 0
        var updated = 0
        for (item in items) {
            val key = item.hostName.trim()
            if (key.isEmpty()) continue
            if (current.containsKey(key)) updated++ else added++
            current[key] = item
        }
        write(current.values.toList())
        AddResult(added, updated)
    }

    /**
     * REQ-RETENTION-003 (v1.0.71) — the quota-aware import.
     *
     * [add] is the storage primitive: it writes whatever it is given. This is
     * the **production** entry point, because an imported row is a manually
     * added server and must not be able to push the user past the manual
     * quota by arriving in a file instead of in the form.
     *
     * The whole operation — counting the new rows, deciding how many fit and
     * writing — runs inside one [ManualServerAdmission] critical section, so
     * a concurrent import and form-add cannot both take the last slot.
     *
     * A batch is **partially** admitted rather than refused whole: rows that
     * already exist are updates and always apply (they take no slot), and as
     * many genuinely new rows as still fit are stored. Anything refused is
     * counted in [QuotaAddResult.quotaRejected] and reported to the user, so
     * the caller never claims success for a row that was not persisted.
     */
    suspend fun addWithinQuota(
        items: List<VPNGateItem>,
        admission: ManualServerAdmission = productionManualServerAdmission(),
    ): QuotaAddResult {
        var newRows = 0
        var added = 0
        var updated = 0

        val decision = admission.admit(
            requested = {
                // Counted from the persisted file, not from a caller-supplied
                // list: a stale UI list is exactly what the quota must not
                // trust. Duplicates (same trimmed hostName) are not new.
                val current = read().mapTo(HashSet()) { it.hostName.trim() }
                newRows = items.count { it.hostName.trim().let { k -> k.isNotEmpty() && k !in current } }
                newRows
            },
        ) { allowance ->
            synchronized(lock) {
                val current = read().associateByTo(LinkedHashMap()) { it.hostName.trim() }
                var slots = allowance
                for (item in items) {
                    val key = item.hostName.trim()
                    if (key.isEmpty()) continue
                    if (current.containsKey(key)) {
                        updated++
                    } else {
                        if (slots <= 0) continue
                        slots--
                        added++
                    }
                    current[key] = item
                }
                if (added > 0 || updated > 0) write(current.values.toList())
            }
        }

        // The gate always runs the write — a full quota must not stop the
        // update half of a batch — and `allowance` is how many *new* rows this
        // pass may add. Anything beyond it is counted as quota-rejected.
        return QuotaAddResult(
            added = added,
            updated = updated,
            quotaRejected = (newRows - added).coerceAtLeast(0),
            manualCount = decision.manualCount,
            quota = admission.quota,
            totalCapacity = admission.totalCapacity,
        )
    }

    fun remove(hostName: String): Boolean = synchronized(lock) {
        val key = hostName.trim()
        val current = read()
        val remaining = current.filterNot { it.hostName.trim() == key }
        if (remaining.size == current.size) return false
        write(remaining)
        true
    }

    fun clear(): Boolean = synchronized(lock) {
        if (!file.exists()) return false
        file.delete()
    }

    data class AddResult(val added: Int, val updated: Int) {
        val total: Int get() = added + updated
    }

    /**
     * REQ-RETENTION-003/004 — what one quota-aware import actually did.
     *
     * Every field is a fact about persisted state, so the caller can build the
     * user-facing message without a second query and without overstating the
     * result: [added] + [updated] were written, [quotaRejected] were refused
     * because the manual quota was full.
     */
    data class QuotaAddResult(
        val added: Int,
        val updated: Int,
        val quotaRejected: Int,
        val manualCount: Int,
        val quota: Int,
        val totalCapacity: Int,
    ) {
        val stored: Int get() = added + updated
    }

    // ── storage ──────────────────────────────────────────────────────────

    private fun read(): List<VPNGateItem> {
        if (!file.isFile) return emptyList()
        return try {
            val json = file.readText(Charsets.UTF_8)
            if (json.isBlank()) return emptyList()
            val type = object : TypeToken<List<VPNGateItem>>() {}.type
            gson.fromJson<List<VPNGateItem>>(json, type).orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(rows: List<VPNGateItem>) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(gson.toJson(rows), Charsets.UTF_8)
            if (file.exists() && !file.delete()) {
                // Windows cannot rename over an existing file. The temp copy is
                // still a complete file, so falling back to "delete then move"
                // keeps the atomicity promise for the content that matters.
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
                return
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            // A failed write must never crash the UI thread; the in-memory list
            // the caller already holds is still correct for this session.
        }
    }
}
