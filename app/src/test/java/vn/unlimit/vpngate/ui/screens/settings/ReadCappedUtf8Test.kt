package vn.unlimit.vpngate.ui.screens.settings

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * v1.0.54 (§1.6.49) PART 4 — [readCappedUtf8], the byte-level guard in front of
 * the CSV importer.
 *
 * The document picker returns a stream of unknown length chosen by whoever
 * sent the file, so the cap has to be enforced *before* the bytes become a
 * `String` — a 40 MB attachment decoded to a String would cost ~80 MB of heap
 * on a device that may not have it, and it would happen before
 * [vn.unlimit.vpngate.repository.ManualCsvImport]'s own cap could run.
 *
 * Pure JVM: a [ByteArrayInputStream] in, a byte array (or null) out.
 */
class ReadCappedUtf8Test {

    private fun stream(vararg bytes: Byte): InputStream = ByteArrayInputStream(bytes)

    @Test
    fun `a small stream is returned whole`() {
        val payload = "HostName,IP\n".toByteArray(Charsets.UTF_8)
        assertArrayEquals(payload, readCappedUtf8(stream(*payload), 1024))
    }

    @Test
    fun `an empty stream returns an empty array, not null`() {
        // Empty and "too large" are different answers: the caller must be able
        // to say "the file is empty" rather than "the file is too big".
        val result = readCappedUtf8(stream(), 1024)
        assertEquals(0, result?.size)
    }

    @Test
    fun `a stream exactly at the cap is accepted`() {
        val payload = ByteArray(100) { 'a'.code.toByte() }
        assertEquals(100, readCappedUtf8(stream(*payload), 100)?.size)
    }

    @Test
    fun `a stream one byte over the cap is refused`() {
        val payload = ByteArray(101) { 'a'.code.toByte() }
        assertNull(readCappedUtf8(stream(*payload), 101 - 1))
    }

    @Test
    fun `a stream far over the cap is refused`() {
        val payload = ByteArray(64 * 1024) { 0 }
        assertNull(readCappedUtf8(stream(*payload), 1024))
    }

    @Test
    fun `a read failure returns null instead of throwing`() {
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("device gone")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("device gone")
        }
        assertNull(readCappedUtf8(broken, 1024))
    }

    @Test
    fun `a short read that ends early still returns what was read`() {
        // SAF streams legitimately return fewer bytes than asked for; a reader
        // that treats one short read as EOF would truncate a valid file.
        val payload = "abcdefghij".toByteArray(Charsets.UTF_8)
        val dribble = object : InputStream() {
            private var index = 0
            override fun read(): Int =
                if (index < payload.size) payload[index++].toInt() and 0xFF else -1

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (index >= payload.size) return -1
                b[off] = payload[index++]
                return 1
            }
        }
        assertArrayEquals(payload, readCappedUtf8(dribble, 1024))
    }

    @Test
    fun `the cap does not depend on the stream reporting a length`() {
        // The picker's stream usually reports available() == 0 or a lie; the
        // guard must count bytes it actually saw.
        val payload = ByteArray(4096) { 7 }
        val noLength = object : InputStream() {
            private var index = 0
            override fun read(): Int =
                if (index < payload.size) payload[index++].toInt() and 0xFF else -1

            override fun available(): Int = 0
        }
        assertNull(readCappedUtf8(noLength, 2048))
    }

    @Test
    fun `a UTF-8 multibyte payload is counted in bytes not characters`() {
        // Persian CSV headers are common here. Counting characters would let a
        // file through at roughly twice its byte size.
        val payload = "سرور,آدرس\n".repeat(100).toByteArray(Charsets.UTF_8)
        assertTrue(payload.size > 100)
        assertNull(readCappedUtf8(stream(*payload), payload.size - 1))
        assertArrayEquals(payload, readCappedUtf8(stream(*payload), payload.size))
    }
}
