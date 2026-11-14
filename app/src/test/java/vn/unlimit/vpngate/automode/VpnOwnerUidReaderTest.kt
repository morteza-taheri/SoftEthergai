package vn.unlimit.vpngate.automode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression for the Android 10 (API 29) launch crash: a compiled call to
 * `NetworkCapabilities.getOwnerUid()` throws `NoSuchMethodError` there, killing
 * the foreground process. The reader must never link that method on API < 30
 * and must degrade to `null` when the framework omits or refuses it.
 */
class VpnOwnerUidReaderTest {

    class CapabilitiesWithOwner {
        fun getOwnerUid(): Int = OWNER_UID
    }

    class CapabilitiesWithoutOwner

    class CapabilitiesThatRefuse {
        fun getOwnerUid(): Int = throw IllegalStateException("hidden API blocked")
    }

    @Test
    fun `reads the owner uid on API 30 and above`() {
        assertEquals(OWNER_UID, VpnOwnerUidReader.read(CapabilitiesWithOwner(), sdkInt = 30))
        assertEquals(OWNER_UID, VpnOwnerUidReader.read(CapabilitiesWithOwner(), sdkInt = 33))
    }

    @Test
    fun `never links the method on API 29 where getOwnerUid does not exist`() {
        assertNull(VpnOwnerUidReader.read(CapabilitiesWithOwner(), sdkInt = 29))
        assertNull(VpnOwnerUidReader.read(CapabilitiesWithOwner(), sdkInt = 24))
    }

    @Test
    fun `returns null when the framework lacks the method`() {
        assertNull(VpnOwnerUidReader.read(CapabilitiesWithoutOwner(), sdkInt = 30))
    }

    @Test
    fun `returns null instead of crashing when reflection is refused`() {
        assertNull(VpnOwnerUidReader.read(CapabilitiesThatRefuse(), sdkInt = 30))
    }

    private companion object {
        const val OWNER_UID = 10397
    }
}
