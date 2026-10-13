package vn.unlimit.vpngate.repository

/**
 * When the server list screen is allowed to start a network update on its own.
 *
 * The rule exists because "opening the list" is not a user request to
 * refresh it. Re-entering the screen used to call the API unconditionally, so
 * every visit cost a download, and on a list that is already scheduled to
 * refresh (see [ServerListUpdateScheduler]) it could also interleave with that
 * scheduled update.
 *
 * Freshness is owned by exactly two things: the Update button, and the
 * scheduler when an update is actually due. The screen only loads when it has
 * nothing at all to show.
 *
 * Pure, so the rule is testable without a ViewModel, Room or the network.
 */
object ServerListScreenPolicy {

    /**
     * @param cachedServerCount servers already stored locally.
     * @return true only when there is nothing stored to display. A list that
     *   has never been populated is due for a load by definition — that is
     *   also the case [ServerListUpdateScheduler.isDue] reports — whereas a
     *   populated list is shown as-is and refreshed only on demand.
     */
    fun shouldLoadOnOpen(cachedServerCount: Int): Boolean = cachedServerCount <= 0
}
