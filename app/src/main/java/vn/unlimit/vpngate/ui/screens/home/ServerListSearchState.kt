package vn.unlimit.vpngate.ui.screens.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import vn.unlimit.vpngate.models.VPNGateConnection
import vn.unlimit.vpngate.models.VPNGateConnectionList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Owns the searchable state of the server list: the active keyword and the
 * rows that are actually displayed.
 *
 * [VPNGateConnectionList] is a mutable collection that the query path mutates
 * in place (`filter()` and `advancedFilter()` both clear and refill the
 * receiver and return `this`). Storing that instance in Compose state made
 * every search invisible: the state value never changed identity, so Compose
 * skipped recomposition and the rendered snapshot stayed on the pre-search
 * rows. This class publishes an immutable copy instead, so a re-filtered
 * result is a genuinely different state value.
 */
class ServerListSearchState {
    /** Text currently held by the search field. */
    var keyword: String by mutableStateOf("")

    /** True while the search field is open. */
    var isSearching: Boolean by mutableStateOf(false)

    /**
     * Immutable snapshot of the rows to display. Never the shared
     * [VPNGateConnectionList] instance.
     */
    var rows: List<VPNGateConnection>? by mutableStateOf(null)
        private set

    private var refreshJob: Job? = null

    /**
     * Records a new search-field value. An empty keyword closes the field,
     * matching the previous UI behaviour.
     */
    fun onQueryChanged(query: String) {
        keyword = query
        isSearching = query.isNotEmpty()
    }

    /** Opens the search field without altering the keyword. */
    fun openSearch() {
        isSearching = true
    }

    /** Closes the search field and drops the keyword. */
    fun closeSearch() {
        isSearching = false
        keyword = ""
    }

    /**
     * The keyword that must constrain the next query, or `""` when the list is
     * not being searched. Blank input is treated as "no keyword" so whitespace
     * cannot silently filter everything away.
     */
    fun effectiveKeyword(): String = if (isSearching) keyword.trim() else ""

    /** Number of rows currently displayed. */
    val rowCount: Int
        get() = rows?.size ?: 0

    /**
     * Publishes [model] as an immutable snapshot. Copying here is what makes a
     * re-filtered result observable to Compose.
     */
    fun publish(model: VPNGateConnectionList?) {
        rows = model?.toList()
    }

    /**
     * Cancels any in-flight refresh and launches [block] instead, so a slow
     * query for an older keyword can never publish over a newer one. [block]
     * runs in [scope] and is expected to call [publish] on the main thread.
     */
    fun startRefresh(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit) {
        refreshJob?.cancel()
        refreshJob = scope.launch(block = block)
    }

    /** Cancels any in-flight refresh without starting a new one. */
    fun cancelRefresh() {
        refreshJob?.cancel()
        refreshJob = null
    }
}