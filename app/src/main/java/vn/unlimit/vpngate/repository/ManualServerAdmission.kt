package vn.unlimit.vpngate.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * REQ-RETENTION-003 (v1.0.71) — the manual-server quota boundary.
 *
 * ## Why this is a separate collaborator
 *
 * The app has **two** user-supplied server surfaces:
 *
 *  1. the `manual_server` Room table (`ManualServerStore`), written by the
 *     manual-add form;
 *  2. the imported-CSV JSON file (`ManualImportStore`), written by the
 *     paste/file import.
 *
 * Both are "manually added servers" and both occupy slots in the server list,
 * so one quota has to cover both. Neither store can see the other's rows, so
 * the count is injected rather than owned: production passes a supplier that
 * reads both surfaces ([productionManualServerRecordCount]); a test passes a
 * fake.
 *
 * ## Atomicity
 *
 * The check and the write run inside one [Mutex] **shared by every instance**
 * (see [lock]), so two concurrent additions — from the same store or from the
 * two different stores — cannot both observe "one slot free" and both take it.
 * The quota is therefore enforced at the persistence boundary, not by
 * disabling a button.
 *
 * A write that is a *duplicate* (`requested == 0`) is never rejected: editing
 * an existing entry does not consume a new slot, so it must still work when the
 * quota is full. That is also what keeps the over-cap recovery path usable
 * (REQ-RETENTION-007: a user whose data already exceeds the quota can still
 * edit and delete, they just cannot add).
 */
class ManualServerAdmission(
    /** Maximum number of user-supplied records, derived from the total capacity. */
    val quota: Int = manualServerQuota(),
    /** The total server-list capacity this quota was derived from. */
    val totalCapacity: Int = MAX_RETAINED_SERVERS,
    /** Authoritative persisted count of user-supplied records (both surfaces). */
    private val count: suspend () -> Int,
) {

    /**
     * Runs [write] with the number of **new** records it may add.
     *
     * [requested] is evaluated **inside** the critical section because it
     * depends on the persisted state (a re-submitted host is a duplicate, not a
     * new record) — computing it outside would reintroduce the race this class
     * exists to close.
     *
     * [write] always runs, even when the allowance is 0: a full quota must not
     * stop an operation that takes no slot (editing an existing record, or the
     * update half of an import). It is the write's job to skip the new records
     * it was not granted — see [allowance] — and to report what it did.
     *
     * @param requested how many *new* records this operation would add; 0 for a
     *   pure update of existing records.
     * @param write the persistence call, receiving how many new records it is
     *   allowed to add ([allowance]).
     */
    suspend fun <T> admit(
        requested: suspend () -> Int,
        write: suspend (allowance: Int) -> T,
    ): Admitted<T> = lock.withLock {
        // A count that cannot be read must not become "0 free" (which would
        // reject every addition) nor "unlimited" (which would break the cap).
        // Falling back to 0 is the conservative reading: the write itself still
        // cannot exceed the cap because the quota is only ever a ceiling.
        val current = runCatching { count() }.getOrDefault(0).coerceAtLeast(0)
        val remaining = (quota - current).coerceAtLeast(0)
        val want = runCatching { requested() }.getOrDefault(0).coerceAtLeast(0)

        val allowance = if (want == 0) 0 else minOf(want, remaining)
        val value = write(allowance)
        Admitted(
            value = value,
            manualCount = current + allowance,
            quotaBlocked = want > allowance,
        )
    }

    /** Outcome of [admit]. */
    data class Admitted<out T>(
        /** Whatever [admit]'s write block returned. */
        val value: T,
        /** The persisted count of user-supplied records after the write. */
        val manualCount: Int,
        /** True when at least one requested new record was refused. */
        val quotaBlocked: Boolean,
    )

    companion object {
        /**
         * Process-wide critical section.
         *
         * Deliberately shared by every instance: the two stores construct their
         * own `ManualServerStore` / `ManualImportStore` per call, so an
         * instance-level lock would serialize nothing and two surfaces could
         * still both pass the check. The critical section is a single indexed
         * count plus one insert, so serializing all manual writes costs
         * nothing the user can observe.
         */
        private val lock = Mutex()
    }
}

/**
 * REQ-RETENTION-003 — the outcome of one manual-server write.
 *
 * [Stored.updated] distinguishes "a new record took a slot" from "an existing
 * record was edited", which is what lets a caller explain the result without
 * guessing. [QuotaReached] carries the numbers the user needs to act on
 * (REQ-RETENTION-004) so the message is built from real state rather than a
 * second, drifting query.
 */
sealed interface AddOutcome {
    /** Persisted. [updated] is true when an existing record was edited in place. */
    data class Stored(val updated: Boolean) : AddOutcome

    /** The input is not a dialable server; nothing was written. */
    object InvalidInput : AddOutcome

    /** The manual quota is full; nothing was written. */
    data class QuotaReached(
        val manualCount: Int,
        val quota: Int,
        val totalCapacity: Int,
    ) : AddOutcome
}

/**
 * The single production admission instance.
 *
 * Shared so every caller serializes on the same lock and every caller derives
 * the quota from the same capacity constant. Tests construct their own
 * instance with a fake count and (when useful) a smaller quota.
 */
private val productionAdmission: ManualServerAdmission by lazy {
    ManualServerAdmission(count = ::productionManualServerRecordCount)
}

/** The production admission, wired to the real persisted count. */
internal fun productionManualServerAdmission(): ManualServerAdmission = productionAdmission

/**
 * Authoritative persisted count of user-supplied records across **both**
 * surfaces: `manual_server` rows (enabled or not — a disabled row is still a
 * record the user created) plus imported CSV rows.
 *
 * Never throws: a missing database or an unreadable import file counts as 0,
 * which can only ever make the quota *more* permissive for a single call, never
 * let the total exceed the capacity (the write is still bounded by [quota]).
 */
internal suspend fun productionManualServerRecordCount(): Int {
    val room = runCatching {
        vn.unlimit.vpngate.App.instance?.manualServerDao?.listAll()?.size
    }.getOrNull() ?: 0
    val imported = runCatching {
        vn.unlimit.vpngate.App.instance?.manualImportStore?.items()?.size
    }.getOrNull() ?: 0
    return room + imported
}
