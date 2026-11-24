package vn.unlimit.vpngate.automode

import kittoku.osc.service.SstpVpnService
import org.junit.Assert.assertEquals
import org.junit.Test

class SstpActionContractTest {
    @Test
    fun autoModeUsesTheSstpServiceActionContract() {
        assertEquals("kittoku.osc.connect", SstpVpnService.ACTION_CONNECT)
        assertEquals("kittoku.osc.disconnect", SstpVpnService.ACTION_DISCONNECT)
    }
}
