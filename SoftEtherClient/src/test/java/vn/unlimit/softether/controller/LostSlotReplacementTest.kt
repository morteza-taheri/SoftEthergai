package vn.unlimit.softether.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SEC-F4 (deep-review refresh, 2026-11-05): once a handle's destruction was
 * never proven, the slot is terminal. Publishing a replacement handle made the
 * controller look healthy while the old native object's fate was unknown.
 */
class LostSlotReplacementTest {

    @Test
    fun `a fresh slot accepts a published handle`() {
        val slot = NativeHandleSlot()
        assertTrue(slot.publish(0x1234L))
        assertEquals(0x1234L, slot.current)
        assertFalse(slot.isLost)
    }

    @Test
    fun `a lost slot refuses a replacement handle`() {
        val slot = NativeHandleSlot()
        assertTrue(slot.publish(0x1234L))

        val claim = slot.claimHandle()
        assertTrue(claim is NativeHandleClaim.Claimed)
        slot.settleLost()               // destruction attempted but unproven

        assertTrue(slot.isLost)
        assertFalse("a lost slot must not publish a replacement", slot.publish(0x5678L))
        assertEquals("the old handle must stay claimed/cleared", 0L, slot.current)
    }

    @Test
    fun `a slot with an unresolved destroy refuses a replacement`() {
        val slot = NativeHandleSlot()
        assertTrue(slot.publish(0x1234L))

        val claim = slot.claimHandle()
        assertTrue(claim is NativeHandleClaim.Claimed)
        // No settleDestroyed()/settleLost() yet: ownership is in flight.

        assertFalse("an in-flight destroy must block publication", slot.publish(0x5678L))
        assertEquals(0L, slot.current)
        slot.settleDestroyed()
    }

    @Test
    fun `a proven destroy still allows a later reconnect replacement`() {
        val slot = NativeHandleSlot()
        assertTrue(slot.publish(0x1234L))
        assertTrue(slot.claimHandle() is NativeHandleClaim.Claimed)
        slot.settleDestroyed()

        assertFalse(slot.isLost)
        assertTrue("proven cleanup may be followed by a replacement", slot.publish(0x5678L))
        assertEquals(0x5678L, slot.current)
    }
}
