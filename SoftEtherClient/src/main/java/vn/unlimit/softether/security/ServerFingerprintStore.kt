package vn.unlimit.softether.security

/**
 * SEC-01 — Trust-On-First-Use (TOFU) certificate-fingerprint pinning.
 *
 * ## Design source
 *
 * This implements **Design B** from `AUDIT_SECURITY_AND_RISK_B_v1.0.14.md` §2:
 *
 * > On the **first** successful TLS handshake to a given endpoint, compute the SHA-256 of
 * > the leaf certificate and persist `(serverId -> fingerprint)`. On every subsequent
 * > connect, recompute and compare: a **mismatch blocks the connection**. A brand-new
 * > endpoint is TOFU-accepted and recorded.
 *
 * The native tunnel keeps `SSL_VERIFY_NONE` (VPN Gate relays use self-signed certificates
 * and the client connects by IP literal), so this store is the *only* place the certificate
 * is checked. The fingerprint is extracted natively after the handshake
 * (`ssl_get_peer_cert_fingerprint`, exposed as `SoftEtherClient.getPeerFingerprint`) and the
 * decision is made here.
 *
 * ## Why this file has no Android imports
 *
 * Everything in this file is pure Kotlin so the TOFU decision logic is unit-testable on the
 * JVM. The persistent backing store lives in `ServerFingerprintStoreAndroid` (SharedPreferences);
 * the in-memory implementation below is what the tests drive, and it also serves as the
 * reference semantics for the Android-backed one.
 *
 * ## Deliberately NOT implemented here
 *
 * The **opt-in strict mode** ("a pin must exist before connect is allowed") is a *default-off*
 * sub-option in §2.4. It is not part of this phase, and the default TOFU behaviour below is
 * exactly what §2.3/§2.4 specify: first use auto-accepts, only a later mismatch blocks.
 */
enum class VerifyResult {
    /** A pin already exists and matches the presented fingerprint — proceed. */
    MATCH,

    /** No pin existed; the presented fingerprint was stored — proceed (first use). */
    FIRST_USE,

    /** A pin exists and the presented fingerprint differs — block (possible MITM). */
    MISMATCH,

    /**
     * No pin existed **and** no fingerprint was presented (e.g. the native extraction
     * returned null). Nothing to compare and nothing to store — treated as first use.
     */
    MISSING,
}

/** The policy decision a [VerifyResult] maps to at the connect boundary. */
enum class TofuDecision {
    /** Continue the connection as normal. */
    PROCEED,

    /** Reject the connection and route through the existing error/teardown path. */
    BLOCK,
}

/**
 * Map a [VerifyResult] to the connect-path decision.
 *
 * MATCH, FIRST_USE and MISSING all proceed (the design auto-accepts a brand-new endpoint);
 * only MISMATCH blocks. This is the single "policy hook" the controller consults, so the
 * decision table is stated once and is directly unit-testable.
 */
fun VerifyResult.toDecision(): TofuDecision = when (this) {
    VerifyResult.MATCH, VerifyResult.FIRST_USE, VerifyResult.MISSING -> TofuDecision.PROCEED
    VerifyResult.MISMATCH -> TofuDecision.BLOCK
}

/**
 * The identity a pin is keyed by.
 *
 * The design's open sub-question "fingerprint key (IP:port vs relay identity)" (§2.4/§6) is
 * resolved for this phase as the full endpoint tuple: the configured host, the resolved IP and
 * the port. In the current VPN Gate flow the app connects by IP, so [hostname] and [ip] usually
 * hold the same value — the field is kept separate so a future DNS-name path keys correctly
 * without a migration.
 */
data class ServerKey(
    val hostname: String,
    val ip: String,
    val port: Int,
) {
    /**
     * Stable storage key. Case-insensitive on the host/ip parts (DNS names and IPv6 hex are
     * case-insensitive) and includes the port, so two endpoints that share a host but differ
     * in port never collide.
     */
    val canonical: String
        get() = "${hostname.trim().lowercase()}|${ip.trim().lowercase()}|$port"
}

/**
 * Persistence + TOFU logic for per-endpoint certificate fingerprints.
 *
 * Implementations store a lowercase-hex SHA-256 fingerprint keyed by [ServerKey.canonical].
 * The core logic ([verify]) is defined by [InMemoryServerFingerprintStore] and mirrored by the
 * Android-backed implementation.
 */
interface ServerFingerprintStore {
    /** The stored fingerprint for [key], or null when no pin exists. */
    fun lookup(key: ServerKey): String?

    /** Record [fingerprintHex] as the pin for [key], replacing any existing pin. */
    fun store(key: ServerKey, fingerprintHex: String)

    /**
     * TOFU decision for a freshly presented [fingerprintHex].
     *
     * - no pin + a fingerprint  -> store it and return [VerifyResult.FIRST_USE]
     * - no pin + no fingerprint -> [VerifyResult.MISSING] (nothing to pin)
     * - pin == fingerprint      -> [VerifyResult.MATCH]
     * - pin != fingerprint      -> [VerifyResult.MISMATCH] (fail closed)
     * - pin exists + no fingerprint presented -> [VerifyResult.MISMATCH] (cannot re-verify a
     *   pinned endpoint, so do not proceed — see [InMemoryServerFingerprintStore.verify])
     */
    fun verify(key: ServerKey, fingerprintHex: String): VerifyResult

    /** Forget the pin for [key] (explicit user re-trust). */
    fun clear(key: ServerKey)
}

/**
 * Pure in-memory reference implementation of [ServerFingerprintStore].
 *
 * This is the normative TOFU semantics: [verify] is the only place the decision is made, and
 * it auto-stores on first use (the design's "TOFU-accepted and recorded"). The Android-backed
 * store delegates to the same logic against a persistent map.
 */
class InMemoryServerFingerprintStore : ServerFingerprintStore {

    private val pins = LinkedHashMap<String, String>()

    override fun lookup(key: ServerKey): String? = pins[key.canonical]

    override fun store(key: ServerKey, fingerprintHex: String) {
        pins[key.canonical] = fingerprintHex.trim().lowercase()
    }

    override fun verify(key: ServerKey, fingerprintHex: String): VerifyResult {
        val result = tofuVerify(pins[key.canonical], fingerprintHex)
        if (result == VerifyResult.FIRST_USE) store(key, fingerprintHex)
        return result
    }

    override fun clear(key: ServerKey) {
        pins.remove(key.canonical)
    }
}

/**
 * The single TOFU decision rule, shared by the in-memory and Android-backed stores so the two
 * can never disagree. [existing] is the currently pinned fingerprint (null when none);
 * [presentedRaw] is the fingerprint freshly extracted from the peer certificate.
 *
 * - no pin + a fingerprint  -> [VerifyResult.FIRST_USE] (the caller must store it)
 * - no pin + no fingerprint -> [VerifyResult.MISSING] (nothing to pin; treat as first use)
 * - pin == fingerprint      -> [VerifyResult.MATCH]
 * - pin != fingerprint      -> [VerifyResult.MISMATCH]
 * - pin exists + no fingerprint presented -> [VerifyResult.MISMATCH]: a previously pinned
 *   endpoint that can no longer be re-verified must fail closed, not silently proceed.
 */
internal fun tofuVerify(existing: String?, presentedRaw: String): VerifyResult {
    val presented = presentedRaw.trim()
    if (existing == null) {
        return if (presented.isEmpty()) VerifyResult.MISSING else VerifyResult.FIRST_USE
    }
    if (presented.isEmpty()) return VerifyResult.MISMATCH
    return if (existing.equals(presented, ignoreCase = true)) {
        VerifyResult.MATCH
    } else {
        VerifyResult.MISMATCH
    }
}

/**
 * Lowercase-hex helpers for certificate fingerprints.
 *
 * Kept dependency-free so both the JNI bridge and the tests share one canonical encoding:
 * 32 bytes -> 64 lowercase hex characters.
 */
object ServerFingerprint {

    /** Encode raw fingerprint [bytes] as lowercase hex (no separators). */
    fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        val digits = "0123456789abcdef"
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = digits[v ushr 4]
            out[i * 2 + 1] = digits[v and 0x0F]
        }
        return String(out)
    }

    /** A well-formed SHA-256 fingerprint is exactly 64 lowercase/uppercase hex characters. */
    fun isWellFormed(hex: String): Boolean =
        hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
