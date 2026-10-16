package vn.unlimit.vpngate.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import vn.unlimit.vpngate.data.model.CollectorLog
import vn.unlimit.vpngate.models.VPNGateItem

/**
 * The single, safe path for replacing the server list.
 *
 * Everything that can produce a fresh list — the Update button on the server
 * list screen and the periodic background update — goes through here, so both
 * share one download → parse → validate → atomic-replace sequence and one
 * concurrency guard.
 *
 * Two invariants this class exists to guarantee:
 *
 *  1. **The current list is never destroyed by a failed update.** The store is
 *     only touched once a complete, validated dataset is in hand, so network
 *     failure, an empty body, malformed content, a parser exception or a
 *     dataset that is structurally unusable all leave the previous list
 *     exactly as it was.
 *  2. **Two updates never run at once.** A second request while one is in
 *     flight is ignored rather than queued: a queue would just replay a
 *     download the user no longer needs, and the result would be discarded by
 *     the newer request anyway. The guard is process-wide, so a scheduled
 *     update and a manual one cannot overlap either.
 *
 *  3. **A list is never replaced by a less connectable one.** A dataset that
 *     carries no OpenVPN profile at all, when the stored list has some, is
 *     treated as a degraded response and the stored list is kept. The same
 *     holds for a dataset that is too small, that collapses against the
 *     stored row count, or that loses a whole protocol (Stage 2C-B).
 *
 *  4. **This class is the only thing that writes the stored dataset.**
 *     Anything that merely needs servers — Auto Connect most of all — asks
 *     for them here and may never persist them itself. A caller that needs a
 *     dataset and finds none stored uses [acquire], which runs the very same
 *     acceptance sequence instead of writing around it.
 *
 *  5. **A fallback dataset is a candidate, not an observation.** When
 *     [fetchedFromCache] reports that what came back is the last-known-good
 *     snapshot, the stored list or the bundled seed, it is accepted by exactly
 *     the same rules as a network dataset and may populate an empty store —
 *     but it is not a new observation, so it never replaces the
 *     last-known-good snapshot and never counts as a successful refresh.
 *
 *  6. **The emergency rung is last and is not a bypass.** It runs only when
 *     [fetch] produced nothing at all, it is injected (so a caller with no
 *     configured source cannot reach it), and a dataset it supplies goes
 *     through the identical acceptance sequence — including
 *     `DatasetIntegrity`, which is what refuses an emergency file too small
 *     to be a server list when a healthy list is already stored.
 *
 * Pure JVM: [fetch] and [store] are injected, so the whole contract is
 * unit-testable without Android, Room or the network.
 */
class ServerListUpdater(
    private val fetch: suspend () -> List<VPNGateItem>?,
    private val store: Store,
    /**
     * What the dataset [fetch] just returned was able to provide. Defaults
     * to "everything", so an unaware caller is still checked strictly; the
     * production wiring reports the capability of the run's real sources.
     */
    private val fetchCapability: suspend () -> SourceCapability = { SourceCapability.UNKNOWN },
    /**
     * Whether the dataset [fetch] just returned came from a fallback rather
     * than from the network — the last-known-good snapshot, the stored list or
     * the bundled seed. Such a dataset is still judged by the same acceptance
     * rules, so it may legitimately populate an empty store, but it must not
     * be recorded as a successful refresh: the caller decides whether to
     * advance its "last updated" clock from it. Defaults to "network", which
     * is what every caller that has no fallback path means.
     */
    private val fetchedFromCache: suspend () -> Boolean = { false },
    /**
     * The emergency rung (`SERVER_SOURCE_ARCHITECTURE.md` §2.5 path 3).
     *
     * Tried only when [fetch] returned `null` — no dataset at all. [fetch]
     * already covers the open and VPN rungs and the repository's own
     * snapshot / stored / bundled-seed fallbacks, so this really is the last
     * rung before the update gives up and keeps what is stored.
     *
     * Injected rather than looked up so this class stays the pure-JVM
     * orchestrator it has always been, and so a caller with no emergency
     * sources supplies `{ null }` and the rung cannot run at all. The
     * production wiring builds this in `ServerListUpdates`.
     *
     * `null` (or an empty list) means "nothing to say" and falls through to
     * the unchanged `Outcome.Preserved(NETWORK_FAILURE, …)`. A non-empty
     * result is judged by exactly the same acceptance sequence as a network
     * dataset — `DatasetIntegrity` included, which is what stops a small
     * emergency file from replacing a healthy stored list (§2.7).
     */
    private val emergencyFetch: suspend () -> List<VPNGateItem>? = { null },
) {

    /**
     * Persistence boundary. [replaceAll] must be atomic — the implementation
     * deletes and re-inserts inside one transaction — so a crash mid-write can
     * never leave a half-empty table behind.
     */
    interface Store {
        /** The dataset currently in use. Used for reporting, never for writing. */
        fun currentCount(): Int

        /**
         * How many of the stored servers carry an OpenVPN profile.
         *
         * The API serves the profile list and the server list as two
         * independent feeds, so a dataset can arrive with servers but no
         * profiles at all. Swapping such a list in over one that has profiles
         * would leave every stored server unable to connect, so the updater
         * needs to know whether the current list has profiles to protect.
         * Implementations that cannot answer report 0.
         */
        fun currentOpenVpnConfigCount(): Int = 0

        /**
         * The stored dataset itself, used as the comparison baseline for
         * dataset integrity (Stage 2C-B). Empty means "nothing stored yet",
         * in which case only the absolute floor applies. Implementations that
         * cannot afford the read report an empty list.
         */
        fun currentDataset(): List<VPNGateItem> = emptyList()

        /**
         * Persist [items] as the new last-known-good snapshot, once they have
         * been accepted and the table already holds them. Implementations must
         * never destroy the previous snapshot before the new one is safely
         * written, and return false (rather than throw) when the snapshot
         * could not be written.
         */
        fun saveLastKnownGood(items: List<VPNGateItem>): Boolean = true

        /** Atomically swap the stored dataset. Only ever called with a valid, non-empty list. */
        fun replaceAll(items: List<VPNGateItem>)
    }

    /** Why an update ended the way it did. */
    sealed interface Outcome {
        /**
         * A valid dataset was downloaded and swapped in.
         *
         * [snapshotSaved] is false only when the table was replaced but the
         * last-known-good snapshot could not be written; the stored list is
         * then newer than the snapshot, which still holds the previous
         * dataset and is therefore never worse than nothing.
         *
         * [sourcedFromCache] is true when the accepted dataset came from the
         * last-known-good snapshot, the stored list or the bundled seed rather
         * than from a network fetch. It is reported, never used to weaken a
         * check: the dataset went through the identical acceptance sequence.
         * Its only job is to keep a caller from treating a fallback as a
         * successful refresh.
         */
        data class Updated(
            val serverCount: Int,
            val snapshotSaved: Boolean = true,
            val sourcedFromCache: Boolean = false,
        ) : Outcome

        /**
         * The update did not produce a usable dataset. [serverCount] is the
         * number of servers still stored — the pre-existing, still-valid list.
         */
        data class Preserved(
            val reason: Reason,
            val serverCount: Int,
        ) : Outcome

        /** Another update was already running; this request did nothing. */
        data object SkippedConcurrent : Outcome
    }

    /** The specific failure that caused the previous list to be kept. */
    enum class Reason {
        /** The request never completed (offline, timeout, HTTP error). */
        NETWORK_FAILURE,

        /** The response body was empty or whitespace. */
        EMPTY_RESPONSE,

        /** A response arrived but could not be parsed. */
        PARSE_FAILURE,

        /** Parsing produced a dataset that is not usable (no usable servers). */
        INVALID_DATASET,

        /**
         * The new dataset is structurally fine but is not worth storing: it
         * has lost the OpenVPN profiles the stored list had, it is too small
         * to be a server list, it collapsed against the stored row count, or
         * it lost a whole protocol. In every case the stored list is kept.
         */
        DEGRADED_DATASET,

        /** Storing the validated dataset failed; the old list was kept. */
        STORE_FAILURE,

        /**
         * Stage 2F-A (E-1): the store refused the dataset because it violated
         * a constraint — in practice a duplicate primary key. That is a data
         * shape problem with a known cause, which is why it is not folded into
         * [STORE_FAILURE]. Rows are deduplicated by Room identity before
         * storing, so this is now a defensive outcome rather than a routine
         * one; it exists so the logs can say "the data collided" instead of
         * "the database failed".
         */
        STORE_CONFLICT,
    }

    /**
     * Guards the whole update, process-wide rather than per instance: the
     * server list screen and the scheduler each build their own updater, and
     * two of those must never download and write at the same time. [Mutex]
     * rather than a plain flag so the guard is released even if the body
     * throws, and so a caller can never observe a half-finished update as
     * "not running".
     */
    private val gate: Mutex get() = updateGate

    @Volatile
    private var running = false

    /** True while an update is in flight. Read-only diagnostics for the UI. */
    val isRunning: Boolean get() = running

    /**
     * Suspends until the update holding the guard has finished, then returns.
     *
     * A caller that got [Outcome.SkippedConcurrent] needs this: its own request
     * did nothing, so the only way for it to end up with a list is to wait for
     * the update it collided with and publish that result. Waiting on the guard
     * itself (rather than on a flag) is exact — it cannot observe a moment
     * between "acquired" and "work started" — and it returns immediately when
     * nothing is running.
     */
    suspend fun awaitRunningUpdate() {
        gate.lock()
        gate.unlock()
    }

    /**
     * Runs one update. Never throws: every failure is reported as
     * [Outcome.Preserved] so a caller cannot accidentally treat an error as a
     * successful refresh and clear the list.
     */
    suspend fun update(): Outcome {
        // tryLock, not withLock: a concurrent request must be *ignored*, not
        // queued behind the running one.
        if (!gate.tryLock()) return Outcome.SkippedConcurrent
        running = true
        try {
            var downloaded = try {
                fetch()
            } catch (e: Exception) {
                return Outcome.Preserved(classify(e), store.currentCount())
            }

            // Read once, inside the gate, right after the fetch: the fetch
            // lambda is what knows whether it returned an observation of the
            // network or a fallback it already had.
            val fromCache = try {
                fetchedFromCache()
            } catch (e: Exception) {
                false
            }

            if (downloaded == null) {
                // Emergency rung. Every failure is contained here: a source
                // that yields nothing, a body that is refused or oversized,
                // or a lambda that throws all leave `emergency` null, and the
                // outcome is exactly what it was before this rung existed.
                val emergency = try {
                    emergencyFetch()
                } catch (e: Exception) {
                    CollectorLog.d("Emergency fetch threw: ${e.message}")
                    null
                }
                if (emergency.isNullOrEmpty()) {
                    return Outcome.Preserved(Reason.NETWORK_FAILURE, store.currentCount())
                }
                CollectorLog.d("Emergency rung supplied ${emergency.size} server(s); using it")
                downloaded = emergency
            }
            if (downloaded.isEmpty()) {
                return Outcome.Preserved(Reason.EMPTY_RESPONSE, store.currentCount())
            }

            // Structural validation, independent of whatever the parser already
            // did: a server without an address can never be dialled.
            val usable = downloaded.filter { isUsable(it) }
            if (usable.isEmpty()) {
                return Outcome.Preserved(Reason.INVALID_DATASET, store.currentCount())
            }

            // Stage 2F-A (E-1): collapse the dataset to one row per Room
            // identity before anything judges or stores it. The table's primary
            // key is hostName while the collector merges on IP, so the same
            // host name on two addresses would abort the insert and cost the
            // user the whole refresh. Doing it here — rather than only inside
            // the DAO — means dataset integrity validates exactly the rows that
            // will be stored, and the snapshot cannot drift from the table.
            val usableRows = VPNGateItem.dedupeForStorage(usable)
            if (usableRows.size != usable.size) {
                CollectorLog.d(
                    "Collapsed ${usable.size} row(s) to ${usableRows.size} by Room identity " +
                        "(hostName is the primary key; the collector merges on IP)",
                )
            }
            if (dropsOpenVpnProfiles(usableRows)) {
                return Outcome.Preserved(Reason.DEGRADED_DATASET, store.currentCount())
            }

            // Stage 2C-B: the dataset must also be worth storing. Everything
            // above proved the response was received and parsed; this proves
            // it is a server list rather than a truncated remnant, judged
            // against the dataset that is already stored.
            val capability = try {
                fetchCapability()
            } catch (e: Exception) {
                SourceCapability.UNKNOWN
            }
            val verdict = DatasetIntegrity.evaluate(usableRows, store.currentDataset(), capability)
            if (!verdict.accepted) {
                CollectorLog.d(
                    "Dataset rejected (${verdict.defect}): ${verdict.detail}; " +
                        "keeping ${store.currentCount()} stored server(s)",
                )
                return Outcome.Preserved(Reason.DEGRADED_DATASET, store.currentCount())
            }

            return try {
                store.replaceAll(usableRows)
                // Only now, after the dataset is stored and known good, does
                // it become the last-known-good fallback. A rejected dataset
                // never reaches this point, so it can never poison it.
                val snapshotSaved = if (fromCache) {
                    // A snapshot / stored / bundled-seed dataset is not a new
                    // observation, so it must not be promoted to "the last
                    // thing we saw". The file already holds the last accepted
                    // network dataset, and that is exactly what must survive.
                    true
                } else {
                    try {
                        store.saveLastKnownGood(usableRows)
                    } catch (e: Exception) {
                        false
                    }
                }
                Outcome.Updated(usableRows.size, snapshotSaved, fromCache)
            } catch (e: Exception) {
                // Stage 2F-A (E-1): a key collision is a data problem with a
                // known cause, not a disk failure. Reporting it separately keeps
                // the two distinguishable in logs without ever putting SQLite's
                // own message in front of a user.
                if (isKeyConflict(e)) {
                    CollectorLog.d(
                        "Storage rejected the dataset on a primary-key conflict: " +
                            "${e.javaClass.simpleName}. This should be unreachable now that " +
                            "rows are deduplicated by Room identity before storing.",
                    )
                    Outcome.Preserved(Reason.STORE_CONFLICT, store.currentCount())
                } else {
                    Outcome.Preserved(Reason.STORE_FAILURE, store.currentCount())
                }
            }
        } finally {
            running = false
            gate.unlock()
        }
    }

    /**
     * Returns the dataset a consumer may work with right now.
     *
     * The stored dataset is returned whenever there is one, with no network
     * access and no write of any kind: an already-accepted list is never
     * re-fetched, re-judged or re-stored just because somebody asked for it.
     *
     * Only an empty store triggers an acquisition, and that acquisition is
     * [update] — the same mutex, the same [DatasetIntegrity] verdict, the same
     * single write. A caller therefore cannot end up persisting anything that
     * the Update button would have rejected, which is what made a direct
     * `replaceAll` from a consumer unsafe.
     *
     * If an update is already running, this waits for it and re-reads the
     * store, so the dataset another writer just accepted is returned instead of
     * anything obtained in parallel. Nothing here can overwrite it.
     *
     * @return the stored dataset, or an empty list when there is none and none
     *   could be accepted.
     */
    suspend fun acquire(): List<VPNGateItem> {
        val stored = readDataset()
        if (stored.isNotEmpty()) return stored

        if (update() is Outcome.SkippedConcurrent) {
            awaitRunningUpdate()
        }

        // Re-read rather than trust the outcome: the authoritative dataset is
        // whatever the store holds, including one another writer just accepted.
        return readDataset()
    }

    private fun readDataset(): List<VPNGateItem> = try {
        store.currentDataset()
    } catch (e: Exception) {
        emptyList()
    }

    /** A parser that blew up is a parse failure; anything else is the network's fault. */
    private fun classify(e: Exception): Reason =
        if (e is ServerListParseException) Reason.PARSE_FAILURE else Reason.NETWORK_FAILURE

    private fun isUsable(item: VPNGateItem): Boolean {
        val host = item.hostName.trim()
        if (host.isEmpty()) return false
        val ip = item.ip?.trim().orEmpty()
        if (ip.isEmpty()) return false
        return ip.any { it.isDigit() || it == '.' } || host.contains('.')
    }

    /**
     * A list is degraded relative to the stored one when it drops the OpenVPN
     * profiles the stored list has. Such a list is not useless — the servers
     * are real — but it is strictly less connectable, and a transient feed
     * failure (the API serves servers and profiles separately) must not be
     * able to turn a working list into an unconnectable one.
     */
    private fun dropsOpenVpnProfiles(items: List<VPNGateItem>): Boolean {
        val stored = store.currentOpenVpnConfigCount()
        if (stored <= 0) return false
        return items.none { !it.openVpnConfigData.isNullOrBlank() }
    }

    companion object {
        /** Process-wide update guard, shared by every updater instance. */
        private val updateGate = Mutex()

        /**
         * Stage 2F-A (E-1): did the store fail on a constraint rather than on
         * the medium?
         *
         * Matched by class name rather than by type so this file stays a
         * pure-JVM unit-test target: the SQLite exception type only exists on a
         * device, and a test can then supply a stand-in that behaves like one.
         * The walk is bounded and inspects type names only, so no database text
         * is ever surfaced.
         */
        internal fun isKeyConflict(e: Throwable): Boolean {
            var current: Throwable? = e
            var depth = 0
            while (current != null && depth < 8) {
                val name = current.javaClass.simpleName
                if (name.contains("ConstraintException") || name.contains("ConstraintViolation")) {
                    return true
                }
                current = current.cause
                depth++
            }
            return false
        }
    }
}

/** Thrown by a [ServerListUpdater.fetch] when a response could not be parsed. */
class ServerListParseException(cause: Throwable) : Exception(cause)
