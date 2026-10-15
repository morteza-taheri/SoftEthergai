package vn.unlimit.softether.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SEC-01 — pure-JVM tests for the TOFU certificate-fingerprint store.
 *
 * These exercise [InMemoryServerFingerprintStore] and the shared [tofuVerify] rule — the same
 * logic the production [ServerFingerprintStoreAndroid] delegates to — so no Android context is
 * needed. The design being pinned is `AUDIT_SECURITY_AND_RISK_B_v1.0.14.md` §2 (Design B):
 * first use is accepted and recorded, a later mismatch blocks.
 */
class ServerFingerprintStoreTest {

    private val key = ServerKey(hostname = "150.40.105.6", ip = "150.40.105.6", port = 443)

    private fun hex(fill: Int): String = "%02x".format(fill).repeat(32) // 64 hex chars

    // ------------------------------------------------------------------ E1.1

    /** E1.1: an empty store accepts a brand-new endpoint and records its fingerprint. */
    @Test
    fun emptyStoreYieldsFirstUseAndRecords() {
        val store = InMemoryServerFingerprintStore()

        assertNull("nothing must be pinned before the first connect", store.lookup(key))

        val result = store.verify(key, hex(0x11))

        assertEquals(VerifyResult.FIRST_USE, result)
        assertEquals(
            "first use must record the fingerprint",
            hex(0x11),
            store.lookup(key),
        )
        assertEquals("first use must proceed", TofuDecision.PROCEED, result.toDecision())
    }

    // ------------------------------------------------------------------ E1.2

    /** E1.2: a repeat connect with the same fingerprint matches (and does not re-store). */
    @Test
    fun repeatConnectWithSameFingerprintMatches() {
        val store = InMemoryServerFingerprintStore()
        store.verify(key, hex(0x22))

        val result = store.verify(key, hex(0x22))

        assertEquals(VerifyResult.MATCH, result)
        assertEquals(TofuDecision.PROCEED, result.toDecision())
    }

    // ------------------------------------------------------------------ E1.3

    /** E1.3: a different fingerprint after first use is a MISMATCH and blocks. */
    @Test
    fun differentFingerprintAfterFirstUseIsMismatch() {
        val store = InMemoryServerFingerprintStore()
        store.verify(key, hex(0x33))

        val result = store.verify(key, hex(0x44))

        assertEquals(VerifyResult.MISMATCH, result)
        assertEquals(TofuDecision.BLOCK, result.toDecision())
        assertEquals(
            "a mismatch must not overwrite the original pin",
            hex(0x33),
            store.lookup(key),
        )
    }

    // ------------------------------------------------------------------ E1.4

    /**
     * E1.4: MISSING (no pin AND no presented fingerprint) is treated as first use — the policy
     * proceeds, exactly like FIRST_USE, and nothing is stored (there is nothing to pin).
     */
    @Test
    fun missingIsTreatedAsFirstUse() {
        val store = InMemoryServerFingerprintStore()

        val result = store.verify(key, "")

        assertEquals(VerifyResult.MISSING, result)
        assertEquals(
            "MISSING must follow the same proceed-policy as FIRST_USE",
            VerifyResult.FIRST_USE.toDecision(),
            result.toDecision(),
        )
        assertNull("nothing may be pinned when no fingerprint was presented", store.lookup(key))
    }

    /** E1.4b: a *pinned* endpoint that presents no fingerprint must fail closed, not proceed. */
    @Test
    fun pinnedEndpointWithNoFingerprintFailsClosed() {
        val store = InMemoryServerFingerprintStore()
        store.verify(key, hex(0x55))

        val result = store.verify(key, "")

        assertEquals(VerifyResult.MISMATCH, result)
        assertEquals(TofuDecision.BLOCK, result.toDecision())
    }

    /** E1.4c: fingerprints compare case-insensitively (hex casing is not significant). */
    @Test
    fun fingerprintComparisonIsCaseInsensitive() {
        val store = InMemoryServerFingerprintStore()
        store.verify(key, "AB".repeat(32))

        assertEquals(VerifyResult.MATCH, store.verify(key, "ab".repeat(32)))
    }

    // ------------------------------------------------------------------ E1.5

    /** E1.5: clear() forgets the pin, so the next connect is a fresh first use. */
    @Test
    fun clearForgetsThePin() {
        val store = InMemoryServerFingerprintStore()
        store.verify(key, hex(0x66))

        store.clear(key)

        assertNull("clear() must remove the pin", store.lookup(key))
        assertEquals(
            "after clear(), the endpoint is a brand-new first use again",
            VerifyResult.FIRST_USE,
            store.verify(key, hex(0x77)),
        )
    }

    // ------------------------------------------------------------------ E1.6

    /** E1.6: two endpoints that share a host but differ in port never collide. */
    @Test
    fun sameHostDifferentPortDoesNotCollide() {
        val store = InMemoryServerFingerprintStore()
        val key443 = ServerKey(hostname = "relay.example", ip = "1.2.3.4", port = 443)
        val key995 = ServerKey(hostname = "relay.example", ip = "1.2.3.4", port = 995)

        store.verify(key443, hex(0x88))

        assertNull("the :995 pin must be independent of the :443 pin", store.lookup(key995))
        assertEquals(
            "a different port is a different endpoint (first use)",
            VerifyResult.FIRST_USE,
            store.verify(key995, hex(0x99)),
        )
        assertEquals(
            "the :443 pin must be unaffected",
            VerifyResult.MATCH,
            store.verify(key443, hex(0x88)),
        )
    }

    /** E1.6b: the canonical key includes the port (regression guard for the storage key). */
    @Test
    fun canonicalKeyIncludesPort() {
        val a = ServerKey(hostname = "h", ip = "1.1.1.1", port = 443).canonical
        val b = ServerKey(hostname = "h", ip = "1.1.1.1", port = 444).canonical

        assertTrue("canonical must contain the port", a.contains("443"))
        assertFalse("different ports must produce different keys", a == b)
    }

    // ------------------------------------------------------- fingerprint helpers

    /** The hex encoding is canonical: 32 bytes -> 64 lowercase hex chars, round-trippable. */
    @Test
    fun hexEncodingIsCanonical() {
        val bytes = ByteArray(32) { it.toByte() }
        val encoded = ServerFingerprint.toHex(bytes)

        assertEquals(64, encoded.length)
        assertEquals(encoded, encoded.lowercase())
        assertTrue(ServerFingerprint.isWellFormed(encoded))
        assertTrue(encoded.startsWith("00010203"))
        assertTrue(encoded.endsWith("1f"))
    }

    @Test
    fun wellFormedRejectsBadFingerprints() {
        assertFalse(ServerFingerprint.isWellFormed(""))
        assertFalse(ServerFingerprint.isWellFormed("ab".repeat(31)))   // 62 chars
        assertFalse(ServerFingerprint.isWellFormed("zz".repeat(32)))   // non-hex
        assertTrue(ServerFingerprint.isWellFormed("deadBEEF".repeat(8)))
    }
}

/**
 * SEC-01 follow-up (FIX-SEC01-RECONNECT) — source-shape regression guard for the TOFU **call sites**.
 *
 * ## Why this is a source-shape assertion
 *
 * `ConnectionController` cannot be constructed in a JVM unit test: it needs a `SoftEtherVpnService`
 * context, `ParcelFileDescriptor`, coroutines, and the native library. So the TOFU *decision* is covered
 * behaviourally by [ServerFingerprintStoreTest], and the *placement* of the check — which is what the
 * reconnect gap was — is pinned here by reading the controller source. This is the same technique
 * `SoftEtherClientHandleGuardTest` uses for the same reason.
 *
 * ## The invariant
 *
 * The TOFU check must run on **both** paths that complete a new TLS handshake: the initial connect
 * (`performConnectInner`) and every reconnect (`attemptReconnect`). Dropping it from either is a security
 * regression — an unchecked handshake — so both call sites are asserted.
 */
class Sec01ReconnectTofuCoverageTest {

    // The Gradle unit-test CWD for this module is `SoftEtherClient/`.
    private val controllerSource = File(
        "src/main/java/vn/unlimit/softether/controller/ConnectionController.kt",
    )

    private fun readController(): String {
        assertTrue(
            "ConnectionController.kt must be readable from the module test CWD (${controllerSource.absolutePath})",
            controllerSource.exists(),
        )
        return controllerSource.readText()
    }

    /** The reconnect path must run the same TOFU check as the initial connect. */
    @Test
    fun reconnectPathRunsTheTofuCheck() {
        val text = readController()

        val start = text.indexOf("private suspend fun attemptReconnect()")
        assertTrue("attemptReconnect() must exist", start >= 0)
        // The function ends at the next top-level member declaration.
        val nextMember = text.indexOf("\n    private fun ", start + 1)
        val reconnectBody = text.substring(start, if (nextMember < 0) text.length else nextMember)

        assertTrue(
            "attemptReconnect() must call verifyTofuAfterHandshake — otherwise a relay substituted " +
                "after first use goes undetected on every reconnect",
            reconnectBody.contains("verifyTofuAfterHandshake(nativeHandle)"),
        )
        assertTrue(
            "the reconnect path must block on a MISMATCH decision",
            reconnectBody.contains("TofuDecision.BLOCK"),
        )
        assertTrue(
            "the reconnect path must report the mismatch through the existing error channel",
            reconnectBody.contains("onError(\"TLS fingerprint mismatch\")"),
        )
    }

    /** Both handshake-completing paths must share the one check. */
    @Test
    fun bothHandshakePathsUseTheSharedCheck() {
        val text = readController()
        val occurrences = Regex("""verifyTofuAfterHandshake\(nativeHandle\)""").findAll(text).count()
        assertEquals(
            "verifyTofuAfterHandshake must be called from exactly the two handshake-completing paths " +
                "(initial connect + reconnect)",
            2,
            occurrences,
        )
    }
}
