package vn.unlimit.softether.client

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The JNI receive contract is now explicit. A JVM test can pin the mapping
 * even though the actual socket behaviour needs a device test.
 */
class RxEventContractTest {

    @Test
    fun nativeValuesMapToDistinctEvents() {
        assertEquals(RxEvent.DATA, RxEvent.fromNative(1))
        assertEquals(RxEvent.KEEPALIVE, RxEvent.fromNative(2))
        assertEquals(RxEvent.IDLE_TIMEOUT, RxEvent.fromNative(3))
        assertEquals(RxEvent.SKIPPED, RxEvent.fromNative(4))
        assertEquals(RxEvent.ERROR, RxEvent.fromNative(5))
    }

    @Test
    fun unknownAndMissingValuesFailClosedToError() {
        assertEquals(RxEvent.ERROR, RxEvent.fromNative(0))
        assertEquals(RxEvent.ERROR, RxEvent.fromNative(99))
    }

    @Test
    fun idleTimeoutIsNotDataOrKeepalive() {
        val idle = RxEvent.fromNative(3)
        assertEquals(false, idle == RxEvent.DATA)
        assertEquals(false, idle == RxEvent.KEEPALIVE)
    }
}
