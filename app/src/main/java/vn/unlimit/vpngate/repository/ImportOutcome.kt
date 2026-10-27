package vn.unlimit.vpngate.repository

import vn.unlimit.vpngate.models.VPNGateItem

/**
 * REQ-RETENTION-003/004 (v1.0.71) — the result of one CSV import.
 *
 * Kept separate from [ManualCsvImport.Result] because the parser answers "what
 * was in the text?" and this answers "what was actually persisted?". Those
 * differ exactly when the manual quota is full, and conflating them is how a UI
 * ends up claiming an import that never happened.
 *
 * Lives in its own file rather than beside `ConnectionListViewModel`: that file
 * is guarded by a source-shape test which scans for class-level `val`
 * declarations, and a second class in it would be indistinguishable from a
 * ViewModel property to that scan.
 *
 * @param parse the parser's verdict, unchanged.
 * @param stored what the quota-aware store did, or null when parsing failed.
 */
data class ImportOutcome(
    val parse: ManualCsvImport.Result,
    val stored: ManualImportStore.QuotaAddResult? = null,
) {
    /** True when the manual quota refused at least one new row. */
    val quotaReached: Boolean get() = (stored?.quotaRejected ?: 0) > 0

    /** Rows actually written (new + updated); 0 when parsing failed. */
    val storedCount: Int get() = stored?.stored ?: 0

    /**
     * Rows the user asked for but did not get: parse-level rejects plus the
     * rows the quota refused.
     */
    val rejectedCount: Int
        get() = (parse as? ManualCsvImport.Result.Success)?.rejectedCount.orZero() +
            (stored?.quotaRejected ?: 0)

    private fun Int?.orZero(): Int = this ?: 0

    /** The parsed rows, or empty when parsing failed. */
    val parsedItems: List<VPNGateItem>
        get() = (parse as? ManualCsvImport.Result.Success)?.items.orEmpty()
}
