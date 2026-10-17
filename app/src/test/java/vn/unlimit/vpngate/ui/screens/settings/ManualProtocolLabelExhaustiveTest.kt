package vn.unlimit.vpngate.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import vn.unlimit.vpngate.data.model.VpnProtocolNames

/**
 * v1.0.26: every record-layer protocol has its own label, and an unknown key
 * gets none.
 *
 * This replaces a `when` whose fallback was `else -> MS-SSTP`. That fallback
 * was a silent lie: a protocol added to [VpnProtocolNames.ALL] later would have
 * been *offered* correctly — `ManualServerProtocolChoicesTest` pins the picker
 * list to `ALL` — but *rendered* as "MS-SSTP", and nothing would have failed.
 *
 * **Why this is a test and not a compile-time guard.** The requested fix was an
 * exhaustive `when` with no `else`, matching how `SourceIdentity.sourceTag` was
 * closed in v1.0.23. That is not achievable here, and the reason is structural
 * rather than an omission: [VpnProtocolNames] is an `object` of `const val`
 * Strings, and Kotlin has no exhaustive `when` over a `String` subject — the
 * compiler always demands an `else`. Getting a compile-time guarantee would
 * mean converting the protocol keys to an enum or sealed type, which changes
 * the record layer and every call site.
 *
 * So the guard is a test-time one: add a protocol to `ALL` without adding a
 * branch to [manualProtocolLabelRes] and this class fails, on the change that
 * introduces the gap. Weaker than the compiler, but it does fail.
 */
class ManualProtocolLabelExhaustiveTest {

    @Test
    fun `every record-layer protocol has a label`() {
        VpnProtocolNames.ALL.forEach { protocol ->
            assertNotNull(
                "no label branch for protocol '$protocol' — add one to manualProtocolLabelRes",
                manualProtocolLabelRes(protocol),
            )
        }
    }

    @Test
    fun `no two protocols share a label`() {
        val ids = VpnProtocolNames.ALL.map { manualProtocolLabelRes(it) }
        assertEquals(
            "two protocols resolving to the same string would make them " +
                "indistinguishable in the picker and in the stored-server list",
            ids.size,
            ids.toSet().size,
        )
    }

    /**
     * The defect this closes, stated as a test: an unknown key must not be
     * rendered as some other protocol's name.
     *
     * `null` is the signal for "I do not know this key", and the caller then
     * shows the raw key — visibly odd rather than confidently wrong.
     */
    @Test
    fun `an unknown protocol has no label and is never mapped to sstp`() {
        assertNull(
            "an unknown key must not borrow another protocol's label",
            manualProtocolLabelRes("wireguard"),
        )
        assertNull(manualProtocolLabelRes(""))
        assertNull(manualProtocolLabelRes("SOFTETHER"))
    }
}
