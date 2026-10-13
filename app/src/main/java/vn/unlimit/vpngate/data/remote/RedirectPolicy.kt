package vn.unlimit.vpngate.data.remote

/**
 * Stage 2C-A: where a fetch is allowed to end up.
 *
 * A redirect must never be able to move a canonical fetch to an unrelated
 * host, nor a mirror fetch to a different mirror's identity. The policy is
 * a pure value so it can be unit-tested without a network, and it is
 * enforced twice: by [OkHttpFetcher] while following the chain, and again
 * at acceptance time against the reported final URL.
 */
data class RedirectPolicy(
    /** Exact authorities this source may end up on (`host` or `host:port`). */
    val allowedAuthorities: Set<String>,
    val allowedSchemes: Set<String> = setOf(
        SourceIdentity.HTTPS,
        SourceIdentity.HTTP,
    ),
    /** http -> https on the same authority is a normal upgrade, not an escape. */
    val allowSecureUpgrade: Boolean = true,
    val maxHops: Int = DEFAULT_MAX_HOPS,
) {

    /** @return null when the hop is allowed, otherwise why it is not. */
    fun rejectionReason(fromUrl: String, toUrl: String): String? {
        val from = SourceUrl.parse(fromUrl, SourceKind.CANONICAL_HTML) ?: return "unparsable source url"
        val to = SourceUrl.parse(toUrl, SourceKind.CANONICAL_HTML) ?: return "unparsable redirect target"

        if (to.scheme !in allowedSchemes) return "scheme not allowed: ${to.scheme}"
        if (from.scheme == SourceIdentity.HTTPS && to.scheme == SourceIdentity.HTTP) {
            return "https downgrade not allowed"
        }
        if (!allowSecureUpgrade && from.scheme != to.scheme) {
            return "scheme change not allowed"
        }
        if (to.authority !in allowedAuthorities) {
            return "host not in source allowlist: ${to.authority}"
        }
        return null
    }

    fun allows(fromUrl: String, toUrl: String): Boolean = rejectionReason(fromUrl, toUrl) == null

    /** True when a reported final URL still belongs to this source. */
    fun allowsFinal(finalUrl: String): Boolean {
        val to = SourceUrl.parse(finalUrl, SourceKind.CANONICAL_HTML) ?: return false
        if (to.scheme !in allowedSchemes) return false
        return to.authority in allowedAuthorities
    }

    companion object {
        const val DEFAULT_MAX_HOPS = 5

        /** Canonical fetches may move between the apex and the www host. */
        val CANONICAL_AUTHORITIES = setOf("www.vpngate.net", "vpngate.net")

        fun canonical(): RedirectPolicy =
            RedirectPolicy(allowedAuthorities = CANONICAL_AUTHORITIES)

        /** A mirror keeps its own identity: same host, and same port when it had one. */
        fun forMirror(identity: SourceIdentity): RedirectPolicy =
            RedirectPolicy(allowedAuthorities = setOf(identity.authority))
    }
}
