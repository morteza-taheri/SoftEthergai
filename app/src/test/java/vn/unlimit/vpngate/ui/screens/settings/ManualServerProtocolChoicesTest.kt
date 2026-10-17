package vn.unlimit.vpngate.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.data.model.VpnProtocolNames

/**
 * Regression tests for the "SSTP is not visible in the protocol picker" defect
 * (v1.0.25).
 *
 * The reported symptom had two independent causes and this file pins the one
 * that is testable on the JVM:
 *
 *  1. **Layout.** The picker was a plain `Row` with no wrap and no scroll, so
 *     on a phone-width screen the fourth chip was laid out past the right edge
 *     and clipped. That is a rendering fact, not a data fact — no JVM test can
 *     observe it, and the fix is asserted by reading the composable.
 *  2. **A hardcoded protocol list.** The picker listed its four protocols
 *     inline instead of deriving them from [VpnProtocolNames.ALL]. That is the
 *     durable hazard: a protocol added to the record layer would silently never
 *     appear in this form, and nothing would fail. The list is now the named
 *     constant [MANUAL_SERVER_PROTOCOL_CHOICES], and these tests hold it to
 *     [VpnProtocolNames.ALL].
 *
 * A second cause matters because the first is easy to reintroduce: the next
 * person who adds a protocol will edit [VpnProtocolNames] and may not know this
 * form exists.
 */
class ManualServerProtocolChoicesTest {

    /**
     * The picker must offer exactly the protocols the record layer understands.
     *
     * Compared as sets, deliberately: the form's order is its own (it matches
     * the filter sheet) and is not what this test is protecting.
     */
    @Test
    fun `the picker offers exactly the protocols the record layer understands`() {
        assertEquals(
            VpnProtocolNames.ALL.toSet(),
            MANUAL_SERVER_PROTOCOL_CHOICES.toSet(),
        )
    }

    /** SSTP specifically — the protocol the operator reported as missing. */
    @Test
    fun `sstp is offered`() {
        assertTrue(
            "SSTP must be selectable in the manual-server form",
            VpnProtocolNames.SSTP in MANUAL_SERVER_PROTOCOL_CHOICES,
        )
    }

    /**
     * No protocol may appear twice.
     *
     * A duplicate would render two chips that set the same value, which reads
     * as a UI glitch and makes the selected state ambiguous.
     */
    @Test
    fun `no protocol is offered twice`() {
        assertEquals(
            MANUAL_SERVER_PROTOCOL_CHOICES.size,
            MANUAL_SERVER_PROTOCOL_CHOICES.toSet().size,
        )
    }

    /**
     * The form must not invent a protocol key.
     *
     * `ManualServerStore.add` rejects any protocol outside
     * [VpnProtocolNames.ALL], so a chip carrying an unknown key would be
     * accepted by the UI and then refused at the boundary with a generic
     * "invalid" message.
     */
    @Test
    fun `every offered protocol is a known record-layer protocol`() {
        MANUAL_SERVER_PROTOCOL_CHOICES.forEach { protocol ->
            assertTrue(
                "unknown protocol key in the picker: $protocol",
                protocol in VpnProtocolNames.ALL,
            )
        }
    }
}
