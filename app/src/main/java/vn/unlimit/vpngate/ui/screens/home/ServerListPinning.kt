package vn.unlimit.vpngate.ui.screens.home

import vn.unlimit.vpngate.models.VPNGateConnection

/**
 * v1.0.54 (§1.6.48) PART 3 — the "freeze the active server at row 1" rule.
 *
 * ## What it does
 *
 * The server the app is **connected to**, **connecting to**, or **testing as
 * the current Auto Connect candidate** is moved to the top of the visible list
 * and marked, so the user can find it without scrolling. That is the whole
 * feature: a display transform over the already-filtered, already-sorted list.
 *
 * ## What it deliberately is NOT
 *
 * - **Not an ordering authority.** Auto Connect's order comes from
 *   `sortedForAutoConnect()` and the candidate list it resolved; pinning is
 *   applied to the *view* only, after filtering and sorting, so it can never
 *   change which server is dialled next.
 * - **Not state.** The pinned identity is derived from the existing
 *   `AutoModeState` / `GlobalVpnTracker` snapshot the screen already collects;
 *   there is no second state machine, no Room write and no network access.
 * - **Not index-based.** The key is the same stable `ip|hostname` identity the
 *   Auto Connect continuation and the wedge ledger use, so a re-sort, a filter
 *   change or a list refresh cannot pin the wrong row. Matching by IP alone is
 *   explicitly rejected: two different servers can share an address (NAT / load
 *   balancer), and pinning one of them because the other is active would be a
 *   lie.
 *
 * ## Cost
 *
 * One linear scan plus one list copy, O(n) for n rows and only when an input
 * actually changes (the caller memoises on the key). A 1000-row list costs one
 * pass, never a per-frame recomputation, and no row is ever duplicated: the
 * pinned row is *moved*, not copied.
 */
internal object ServerListPinning {

    /**
     * The stable identity of a list row: `ip|hostname`.
     *
     * Empty components are normalised to `""` (the same shape
     * `AutoModeController.serverKey` uses), and a row with neither component
     * has no identity at all, so it can never be pinned.
     */
    fun identity(ip: String?, hostName: String?): String? {
        val host = hostName?.trim().orEmpty()
        val address = ip?.trim().orEmpty()
        if (host.isEmpty() && address.isEmpty()) return null
        return "$address|$host"
    }

    fun identityOf(connection: VPNGateConnection): String? =
        identity(connection.ip, connection.hostName)

    /**
     * [items] with the row whose key equals [pinnedKey] moved to index 0.
     *
     * Returns the caller's list **by identity** when there is nothing to do
     * (no pinned key, an empty list, the row is absent — e.g. filtered out by
     * the user's search — or it is already first). That no-op guarantee is what
     * keeps every existing call site and test unaffected when nothing is
     * active.
     */
    fun <T> pin(
        items: List<T>,
        keyOf: (T) -> String?,
        pinnedKey: String?,
    ): List<T> {
        if (pinnedKey == null || items.size < 2) return items
        var index = -1
        for (i in items.indices) {
            if (keyOf(items[i]) == pinnedKey) {
                index = i
                break
            }
        }
        if (index <= 0) return items
        val out = ArrayList<T>(items.size)
        out.add(items[index])
        for (i in items.indices) {
            if (i != index) out.add(items[i])
        }
        return out
    }
}
