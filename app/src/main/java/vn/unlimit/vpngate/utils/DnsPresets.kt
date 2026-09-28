package vn.unlimit.vpngate.utils

/**
 * Single source of truth for the Custom DNS presets.
 *
 * Adding or changing a resolver belongs here only — the Settings UI and the
 * VPN DNS resolution sites both read from this list, so nothing is
 * hard-coded in more than one place.
 *
 * Addresses are the providers' published resolvers:
 *  - Google / Cloudflare: documented public resolvers.
 *  - Shecan: `dns.shecan.ir` — 178.22.122.100 (A) and 185.51.200.2 (PTR
 *    `dns.shecan.ir`), verified against live DNS.
 *  - Quad9: `dns.quad9.net` — 9.9.9.9 and 149.112.112.112 (filtered, TLS
 *    validated), verified against live DNS.
 */
object DnsPresets {

    data class Preset(
        val id: String,
        val labelRes: Int,
        val primary: String,
        val secondary: String,
    )

    const val ID_GOOGLE = "GOOGLE"
    const val ID_CLOUDFLARE = "CLOUDFLARE"
    const val ID_SHECAN = "SHECAN"
    const val ID_QUAD9 = "QUAD9"

    /** Sentinel meaning "user typed the addresses by hand". */
    const val ID_CUSTOM = "CUSTOM"

    val GOOGLE = Preset(ID_GOOGLE, vn.unlimit.vpngate.R.string.dns_preset_google, "8.8.8.8", "8.8.4.4")
    val CLOUDFLARE =
        Preset(ID_CLOUDFLARE, vn.unlimit.vpngate.R.string.dns_preset_cloudflare, "1.1.1.1", "1.0.0.1")
    val SHECAN = Preset(ID_SHECAN, vn.unlimit.vpngate.R.string.dns_preset_shecan, "178.22.122.100", "185.51.200.2")
    val QUAD9 = Preset(ID_QUAD9, vn.unlimit.vpngate.R.string.dns_preset_quad9, "9.9.9.9", "149.112.112.112")

    /** Selectable presets, in display order. */
    val all: List<Preset> = listOf(GOOGLE, CLOUDFLARE, SHECAN, QUAD9)

    /**
     * Resolve a stored preset id to its addresses. Anything unknown — including
     * the [ID_CUSTOM] sentinel and a corrupted/legacy value — yields null, which
     * the UI renders as "Custom" with the user's own text.
     */
    fun byId(id: String?): Preset? = all.firstOrNull { it.id == id }

    /**
     * The preset matching an address pair, or [ID_CUSTOM] when the user typed
     * something that is not exactly one of the presets.
     */
    fun idFor(primary: String, secondary: String): String =
        all.firstOrNull { it.primary == primary && it.secondary == secondary }?.id ?: ID_CUSTOM
}
