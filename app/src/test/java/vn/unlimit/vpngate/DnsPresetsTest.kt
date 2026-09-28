package vn.unlimit.vpngate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.utils.DnsPresets

/**
 * Custom DNS presets (user request): presets live in exactly one place
 * ([DnsPresets]) and the published resolver addresses are correct.
 */
class DnsPresetsTest {

    @Test
    fun fourPresets_areDefinedAndUnique() {
        assertEquals(4, DnsPresets.all.size)
        assertEquals(DnsPresets.all.size, DnsPresets.all.map { it.id }.toSet().size)
        assertEquals(
            DnsPresets.all.size,
            DnsPresets.all.map { it.primary to it.secondary }.toSet().size,
        )
    }

    @Test
    fun publishedAddresses_areCorrect() {
        assertEquals("8.8.8.8", DnsPresets.GOOGLE.primary)
        assertEquals("8.8.4.4", DnsPresets.GOOGLE.secondary)
        assertEquals("1.1.1.1", DnsPresets.CLOUDFLARE.primary)
        assertEquals("1.0.0.1", DnsPresets.CLOUDFLARE.secondary)
        // dns.shecan.ir: 178.22.122.100 (A record) and 185.51.200.2 (PTR -> dns.shecan.ir)
        assertEquals("178.22.122.100", DnsPresets.SHECAN.primary)
        assertEquals("185.51.200.2", DnsPresets.SHECAN.secondary)
        // dns.quad9.net: 9.9.9.9 and 149.112.112.112
        assertEquals("9.9.9.9", DnsPresets.QUAD9.primary)
        assertEquals("149.112.112.112", DnsPresets.QUAD9.secondary)
    }

    @Test
    fun everyPresetAddress_isSyntacticallyValidIpv4() {
        for (preset in DnsPresets.all) {
            for (address in listOf(preset.primary, preset.secondary)) {
                val octets = address.split(".")
                assertEquals("${preset.id}: wrong octet count in $address", 4, octets.size)
                for (octet in octets) {
                    assertTrue(
                        "${preset.id}: non numeric octet in $address",
                        octet.isNotEmpty() && octet.all { it.isDigit() },
                    )
                    val value = octet.toInt()
                    assertTrue("${preset.id}: octet out of range in $address", value in 0..255)
                }
            }
        }
    }

    @Test
    fun byId_resolvesPresets_andRejectsUnknownOrCustom() {
        assertEquals(DnsPresets.SHECAN, DnsPresets.byId(DnsPresets.ID_SHECAN))
        assertNull(DnsPresets.byId(DnsPresets.ID_CUSTOM))
        assertNull(DnsPresets.byId(null))
        assertNull(DnsPresets.byId("NONSENSE"))
    }

    @Test
    fun idFor_recognizesPresets_andFallsBackToCustom() {
        assertEquals(DnsPresets.ID_GOOGLE, DnsPresets.idFor("8.8.8.8", "8.8.4.4"))
        assertEquals(DnsPresets.ID_QUAD9, DnsPresets.idFor("9.9.9.9", "149.112.112.112"))
        assertEquals(DnsPresets.ID_CUSTOM, DnsPresets.idFor("1.1.1.1", "9.9.9.9"))
        assertEquals(DnsPresets.ID_CUSTOM, DnsPresets.idFor("not-an-ip", "also-not"))
        assertEquals(DnsPresets.ID_CUSTOM, DnsPresets.idFor("", ""))
    }
}