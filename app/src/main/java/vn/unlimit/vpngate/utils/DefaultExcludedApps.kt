package vn.unlimit.vpngate.utils

import android.content.Context

/**
 * v1.0.54 (§1.6.50) PART 5 — the bundled default Excluded Apps seed.
 *
 * ## Why an asset and not a `const val` array
 *
 * The list is data, not code: it changes when the operator's app set changes,
 * it is expected to grow to a few dozen entries, and every entry needs a
 * comment explaining why it is there. An asset keeps the data editable without
 * touching Kotlin, and — the reason that matters here — keeps a wrong entry
 * from requiring a code review of a source file that also holds logic.
 *
 * ## Why the parse is separate from the read
 *
 * [parse] is pure: text in, package names out. That is what the tests exercise,
 * and it means the *rules* (comments, blanks, trimming, ordering, duplicates)
 * are pinned without an Android `Context` or an asset file.
 *
 * ## What this deliberately does NOT do
 *
 * It does not decide whether a package is installed and it does not write to
 * the database. Both belong to [ExcludedAppsSeeder], which owns the one-shot
 * rule. Splitting them this way means "which packages do we want" and "should
 * we write now" are separately testable and separately wrong-able.
 */
object DefaultExcludedApps {

    /** Asset path, relative to `assets/`. */
    const val ASSET_NAME: String = "default_excluded_apps"

    /**
     * Extracts package names from the seed text.
     *
     * Rules, in order: a line is cut at the first `#` (so a trailing comment on
     * an entry works), trimmed, and dropped when empty. Order is preserved
     * because the operator wrote it in a meaningful order and the sheet lists
     * seeded apps in insertion order. Duplicates are collapsed to the first
     * occurrence — a repeated package would otherwise insert twice and, with
     * `OnConflictStrategy.REPLACE`, silently reorder nothing but waste a write.
     *
     * A package name is used verbatim. Validating the shape here would mean
     * duplicating Android's package-name grammar, and a rejected line would be
     * a silently missing exclusion; an impossible name simply fails the
     * installed check in the seeder and is skipped there, which is the same
     * outcome without a second grammar.
     */
    fun parse(text: String): List<String> {
        val seen = LinkedHashSet<String>()
        // A BOM is not whitespace, so `trim()` would keep it and the first
        // package name would become "\uFEFFcom.a" — an entry that matches
        // nothing and seeds silently zero rows. Strip it once, here.
        val body = if (text.startsWith('\uFEFF')) text.substring(1) else text
        for (rawLine in body.lineSequence()) {
            val cut = rawLine.indexOf('#')
            val line = (if (cut >= 0) rawLine.substring(0, cut) else rawLine).trim()
            if (line.isEmpty()) continue
            seen.add(line)
        }
        return seen.toList()
    }

    /**
     * Reads and parses the bundled asset.
     *
     * A missing or unreadable asset yields an empty list rather than throwing:
     * the seed is a convenience, and a packaging mistake must not stop the app
     * from starting.
     *
     * An empty result is *not* a completed seed. The asset ships empty on
     * purpose (see its header), so treating "no candidates" as "done" would
     * burn the one-shot flag on a build that has nothing to seed and leave a
     * later build — the one that actually carries the package names — unable
     * to seed existing installs. [ExcludedAppsSeeder] therefore only sets the
     * flag when there were candidates to consider.
     */
    fun load(context: Context): List<String> = try {
        context.assets.open(ASSET_NAME).use { stream ->
            parse(stream.readBytes().toString(Charsets.UTF_8))
        }
    } catch (e: Exception) {
        emptyList()
    }
}
