package vn.unlimit.vpngate.favourites

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

/**
 * BLOCK v1.0.81 — the per-server favourite flag.
 *
 * A star on a server row that the user can switch on and off. Two guarantees:
 *
 *  1. **Persistence** — stored as a `Set<String>` of trimmed host names in
 *     SharedPreferences, so the flag survives restarts and app updates.
 *  2. **Survival** — [vn.unlimit.vpngate.repository.capRetainedServers] keeps a
 *     favourite row the way it keeps a manual row: never pruned, whatever the
 *     quality ranking says and however many servers the feed returns.
 *
 * Keyed by the trimmed `hostName` because that is the identity the retention
 * layer, the manual-server quota and the history summary all already use. A
 * server whose host name changes is a different server to every one of those.
 *
 * The Compose state lives here rather than in a ViewModel so a star tapped on
 * one screen is already correct on the next one without a reload.
 */
object FavouriteServers {

    private const val PREFS = "favourite_servers"
    private const val KEY_SET = "hosts"

    private var prefs: SharedPreferences? = null

    /** Compose-visible snapshot of the favourited host names. */
    private val _keys = mutableStateOf<Set<String>>(emptySet())
    val keys: State<Set<String>> get() = _keys

    /** Loads the persisted set. Safe to call more than once. */
    fun initialise(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _keys.value = normalise(p.getStringSet(KEY_SET, emptySet()).orEmpty())
    }

    /** The current set, for callers that are not composables. */
    fun snapshot(): Set<String> = _keys.value

    fun isFavourite(hostName: String?): Boolean {
        val key = keyOf(hostName) ?: return false
        return key in _keys.value
    }

    /** Flips the flag and returns the NEW state. */
    fun toggle(hostName: String?): Boolean {
        val key = keyOf(hostName) ?: return false
        val current = _keys.value
        val next = if (key in current) current - key else current + key
        _keys.value = next
        persist(next)
        return key in next
    }

    fun setAll(hostNames: Set<String>) {
        val next = normalise(hostNames)
        _keys.value = next
        persist(next)
    }

    /** Drops flags for hosts that no longer exist (used by the purge action). */
    fun removeAll(hostNames: Collection<String>) {
        val drop = normalise(hostNames.toSet())
        if (drop.isEmpty()) return
        val next = _keys.value - drop
        _keys.value = next
        persist(next)
    }

    fun clear() {
        _keys.value = emptySet()
        persist(emptySet())
    }

    private fun persist(value: Set<String>) {
        prefs?.edit()?.putStringSet(KEY_SET, value)?.apply()
    }

    private fun keyOf(hostName: String?): String? =
        hostName?.trim()?.takeIf { it.isNotEmpty() }

    private fun normalise(value: Set<String>): Set<String> =
        value.mapNotNull { keyOf(it) }.toSet()
}
