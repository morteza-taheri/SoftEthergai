package vn.unlimit.vpngate.update

/**
 * BLOCK v1.0.76 — APP UPDATE CHECKER: the pure vocabulary.
 *
 * ## What this module is
 *
 * The app checks a small JSON manifest published in this repository
 * (`app-update/version.json`, fetched from raw.githubusercontent.com) and
 * compares it with the installed build. Everything that can be decided
 * without Android lives here so it is exhaustively unit-testable on a plain
 * JVM: the manifest parser, the version comparison and the weekly-check
 * scheduling decision.
 *
 * ## Fail-closed parsing
 *
 * The manifest is parsed with bounded field extraction, NOT a general JSON
 * parser: any missing or malformed field makes the whole manifest `null`,
 * which the caller reports as "unknown" and never as "update available".
 * A hostile or truncated body can therefore never trigger a dialog or a
 * browser intent. Bodies over [MAX_MANIFEST_BYTES] are refused for the same
 * reason.
 *
 * ## No auto-install
 *
 * The checker only ever REPORTS. Installing is the user's action: the UI
 * opens the release page in a browser. Nothing in this module touches
 * package installation, downloads or the device database.
 */
data class AppUpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val releasePageUrl: String,
    val notes: String? = null,
) {
    companion object {
        /** The raw URL the app fetches. Same self-publish pattern as the emergency CSV. */
        const val MANIFEST_URL: String =
            "https://raw.githubusercontent.com/morteza-taheri/SoftEthergai/main/app-update/version.json"

        /** The page the dialog opens; GitHub redirects /latest to the newest release. */
        const val FALLBACK_RELEASE_PAGE_URL: String =
            "https://github.com/morteza-taheri/SoftEthergai/releases/latest"

        /** A manifest larger than this is a hostile body, not a version file. */
        const val MAX_MANIFEST_BYTES: Int = 64 * 1024
    }
}

/** What one check concluded. `Unknown` never opens a dialog's download button. */
sealed class AppUpdateDecision {
    /** The installed build is the newest published one. */
    object UpToDate : AppUpdateDecision()

    /**
     * A newer build is published. [manifest] carries the version name and the
     * page to open; the caller never derives them from anywhere else.
     */
    data class UpdateAvailable(val manifest: AppUpdateManifest) : AppUpdateDecision()

    /** The manifest could not be fetched or parsed. Report, never navigate. */
    data class Unknown(val reason: String) : AppUpdateDecision()
}

object AppUpdateCheck {

    /**
     * Parse a fetched manifest body, fail-closed.
     *
     * @return the manifest, or null when the body is oversized, blank, or
     *   missing any required field — the caller cannot tell a truncated body
     *   from a hostile one and must not act on either.
     */
    fun parseManifest(body: String?): AppUpdateManifest? {
        if (body == null) return null
        if (body.toByteArray(Charsets.UTF_8).size > AppUpdateManifest.MAX_MANIFEST_BYTES) return null
        if (body.isBlank()) return null

        val versionCode = Regex("\"versionCode\"\\s*:\\s*(\\d+)")
            .find(body)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (versionCode <= 0) return null
        val versionName = Regex("\"versionName\"\\s*:\\s*\"([^\"]+)\"")
            .find(body)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: return null
        val releasePageUrl = Regex("\"releasePageUrl\"\\s*:\\s*\"([^\"]+)\"")
            .find(body)?.groupValues?.get(1)?.takeIf { it.startsWith("https://") }
            ?: return null
        val notes = Regex("\"notes\"\\s*:\\s*\"([^\"]*)\"")
            .find(body)?.groupValues?.get(1)

        return AppUpdateManifest(
            versionCode = versionCode,
            versionName = versionName,
            releasePageUrl = releasePageUrl,
            notes = notes?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Compare the parsed manifest with the installed build.
     *
     * A manifest whose versionCode is not strictly greater than the installed
     * one is "up to date" — the repository can legitimately be behind the
     * installed build (a dev build ahead of the published manifest).
     */
    fun decide(
        manifest: AppUpdateManifest?,
        installedVersionCode: Int,
    ): AppUpdateDecision {
        if (manifest == null) {
            return AppUpdateDecision.Unknown("manifest unavailable or unparsable")
        }
        return if (manifest.versionCode > installedVersionCode) {
            AppUpdateDecision.UpdateAvailable(manifest)
        } else {
            AppUpdateDecision.UpToDate
        }
    }

    /**
     * The weekly automatic check: run only when the last check is older than
     * [intervalMs]. `lastCheckAt <= 0` means "never checked" — check now.
     */
    fun shouldAutoCheck(lastCheckAtMs: Long, nowMs: Long, intervalMs: Long = WEEKLY_INTERVAL_MS): Boolean {
        if (lastCheckAtMs <= 0) return true
        return nowMs - lastCheckAtMs >= intervalMs
    }

    /** The automatic check interval: once a week (D-10). */
    const val WEEKLY_INTERVAL_MS: Long = 7L * 24L * 60L * 60L * 1000L
}
