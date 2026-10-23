package vn.unlimit.vpngate.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * v1.0.54 (§1.6.48) PART 3 — pure ordering tests for the frozen row.
 *
 * No Android, no Compose, no Room: [ServerListPinning] is a list transform, so
 * its whole contract is provable here. The Compose wiring only decides *which*
 * key to pass (connected / connecting / the Auto Connect candidate), and that
 * mapping is the same `activeServerOf` the highlight already uses.
 */
class ServerListPinningTest {

    private data class Row(val ip: String?, val host: String?)

    private val keyOf: (Row) -> String? = { ServerListPinning.identity(it.ip, it.host) }

    private fun pin(rows: List<Row>, pinned: String?) = ServerListPinning.pin(rows, keyOf, pinned)

    private val a = Row("10.0.0.1", "a.example")
    private val b = Row("10.0.0.2", "b.example")
    private val c = Row("10.0.0.3", "c.example")

    @Test
    fun `identity is ip pipe hostname`() {
        assertEquals("10.0.0.1|a.example", ServerListPinning.identity("10.0.0.1", "a.example"))
        assertEquals("10.0.0.1|", ServerListPinning.identity("10.0.0.1", null))
        assertNull("a row with neither component has no identity", ServerListPinning.identity(null, "  "))
    }

    @Test
    fun `connected server is frozen at row 1`() {
        val pinned = pin(listOf(a, b, c), ServerListPinning.identity(b.ip, b.host))
        assertEquals(listOf(b, a, c), pinned)
    }

    @Test
    fun `connecting server is frozen at row 1 by the same rule`() {
        // CONNECTING and CONNECTED both arrive as an ActiveServer; the pin does
        // not care which, so the same assertion covers both.
        val pinned = pin(listOf(a, b, c), ServerListPinning.identity(c.ip, c.host))
        assertEquals(listOf(c, a, b), pinned)
    }

    @Test
    fun `candidate switching moves the pin to the new server`() {
        val rows = listOf(a, b, c)
        assertEquals(listOf(b, a, c), pin(rows, ServerListPinning.identity(b.ip, b.host)))
        // Auto Connect moved on: the pin follows, and the old server returns to
        // its normal place.
        assertEquals(listOf(c, a, b), pin(rows, ServerListPinning.identity(c.ip, c.host)))
    }

    @Test
    fun `same IP with a different hostname is a different server and is not pinned`() {
        val twin = Row("10.0.0.1", "other.example")
        val rows = listOf(a, twin, b)
        val pinned = pin(rows, ServerListPinning.identity(a.ip, a.host))
        // `a` is already first, so nothing moves — and the twin is untouched.
        assertSame(rows, pinned)
        assertEquals("10.0.0.1|other.example", keyOf(twin))
    }

    @Test
    fun `an absent server leaves the list untouched by identity`() {
        val rows = listOf(a, b, c)
        assertSame(rows, pin(rows, ServerListPinning.identity("10.9.9.9", "gone.example")))
    }

    @Test
    fun `an empty list is returned unchanged`() {
        val rows = emptyList<Row>()
        assertSame(rows, pin(rows, "10.0.0.1|a.example"))
    }

    @Test
    fun `a null pin is a no-op`() {
        val rows = listOf(a, b, c)
        assertSame(rows, pin(rows, null))
    }

    @Test
    fun `filter active and the pinned row filtered out leaves the visible list untouched`() {
        // The filter ran first and removed `b`; pinning must not resurrect it or
        // reorder what the user asked to see.
        val filtered = listOf(a, c)
        assertSame(filtered, pin(filtered, ServerListPinning.identity(b.ip, b.host)))
    }

    @Test
    fun `the pinned row is moved, never duplicated`() {
        val pinned = pin(listOf(a, b, c), ServerListPinning.identity(b.ip, b.host))
        assertEquals(3, pinned.size)
        assertEquals(1, pinned.count { keyOf(it) == ServerListPinning.identity(b.ip, b.host) })
    }

    @Test
    fun `a 1000-row list pins in one pass without losing rows`() {
        val rows = (0 until 1000).map { Row("10.1.${it / 256}.${it % 256}", "s$it.example") }
        val target = rows[999]
        val pinned = pin(rows, ServerListPinning.identity(target.ip, target.host))
        assertEquals(1000, pinned.size)
        assertEquals(target, pinned.first())
        assertEquals(rows.dropLast(1), pinned.drop(1))
    }
}
