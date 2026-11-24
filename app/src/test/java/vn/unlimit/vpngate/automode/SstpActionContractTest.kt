package vn.unlimit.vpngate.automode

import kittoku.osc.service.SstpVpnService
import vn.unlimit.vpngate.activities.DetailActivity
import org.junit.Assert.assertEquals
import org.junit.Test

class SstpActionContractTest {
    @Test
    fun autoModeUsesTheSstpServiceActionContract() {
        assertEquals("kittoku.osc.connect", SstpVpnService.ACTION_CONNECT)
        assertEquals("kittoku.osc.disconnect", SstpVpnService.ACTION_DISCONNECT)
        assertEquals(SstpVpnService.ACTION_CONNECT, DetailActivity.ACTION_VPN_CONNECT)
        assertEquals(SstpVpnService.ACTION_DISCONNECT, DetailActivity.ACTION_VPN_DISCONNECT)
    }
}
