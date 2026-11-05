package vn.unlimit.vpngate.repository

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent tombstones for servers the user deleted from the long-press menu.
 *
 * A network refresh merges incoming feed rows with stored ones and *retains*
 * rows the feed did not mention (see [mergeAll] / [mergeFallback]). Without a
 * tombstone, deleting a server would be undone by the very next update, which
 * is not what "delete server" means. Keyed by the trimmed Room identity
 * `hostName`, exactly like favourites, the manual quota and the history tables.
 *
 * The set is deliberately small and unbounded by the retention cap: it holds
 * only host names, never server rows. It is read at every persistence and
 * display boundary so a deleted host cannot re-enter by any path.
 */
object DeletedServers {

    private const val PREFS = "deleted_servers"
    private const val KEY_SET = "hosts"

    @Volatile
    private var prefs: SharedPreferences? = null

    /** In-memory mirror so reads never touch disk on the UI thread. */
    @Volatile
    private var cached: Set<String> = emptySet()

    fun initialise(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            cached = normalise(p.getStringSet(KEY_SET, emptySet()).orEmpty())
            prefs = p
        }
    }

    /**
     * A background refresh can run before any ViewModel exists. Fall back to
     * the application singleton so the tombstones are loaded on first use by
     * whichever boundary gets there first. No-op on a plain JVM test runtime.
     */
    private fun ensureInitialised() {
        if (prefs != null) return
        val app = runCatching { vn.unlimit.vpngate.App.instance }.getOrNull() ?: return
        initialise(app)
    }

    private fun normalise(raw: Collection<String>): Set<String> =
        raw.map { it.trim() }.filter { it.isNotEmpty() }
            .mapTo(LinkedHashSet()) { it.lowercase() }

    private fun keyOf(hostName: String?): String? =
        hostName?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()

    /** Current tombstones. Safe from any thread. */
    fun snapshot(): Set<String> { ensureInitialised(); return cached }

    fun isDeleted(hostName: String?): Boolean {
        val key = keyOf(hostName) ?: return false
        ensureInitialised()
        return key in cached
    }

    /** Marks a host as deleted and persists it before any row is removed. */
    fun mark(hostName: String?) {
        val key = keyOf(hostName) ?: return
        ensureInitialised()
        synchronized(this) {
            val next = LinkedHashSet(cached).also { it.add(key) }
            cached = next
            prefs?.edit()?.putStringSet(KEY_SET, next)?.apply()
        }
    }

    /** Drops rows whose host has been deleted. */
    fun <T> filter(rows: Iterable<T>, hostOf: (T) -> String?): List<T> {
        ensureInitialised()
        val deleted = cached
        if (deleted.isEmpty()) return rows.toList()
        return rows.filterNot { isDeletedIn(deleted, hostOf(it)) }
    }

    private fun isDeletedIn(deleted: Set<String>, hostName: String?): Boolean {
        val key = keyOf(hostName) ?: return false
        return key in deleted
    }

    /**
     * TEST SEAM ONLY — plain-JVM tests have no Android Context. Production
     * always goes through [initialise].
     */
    internal fun replaceForTest(hosts: Collection<String>) {
        cached = normalise(hosts)
    }
}
