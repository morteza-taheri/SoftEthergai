package vn.unlimit.softether.terminal

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import vn.unlimit.softether.TunnelHealth
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages TUN interface for VPN tunnel
 * Handles reading from and writing to the VPN interface
 */
class TunTerminal(
    private val vpnInterface: ParcelFileDescriptor,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "TunTerminal"
        // R6 (v1.0.11 audit §5.5): the old `MAX_PACKET_SIZE = 32767` was dead — a
        // whole-tree search found zero references to it. Rather than keep an unused
        // constant that disagrees with the actual allocation, it is removed and the
        // relationship is recorded here: the TUN MTU is 1500 (SoftEtherVpnService
        // sets `setMtu(config.mtu)` with `ConnectionConfig.mtu = 1500`), and
        // BUFFER_SIZE is 8192, so the buffer is comfortably sufficient for a full
        // packet — a `read()` on a TUN fd returns one packet per call and a packet
        // larger than the buffer would be truncated silently, which cannot happen
        // at 8192 vs an MTU of 1500.
        internal const val BUFFER_SIZE = 8192 // 8KB buffer; > 1500 TUN MTU (see above)

        /**
         * R5: true when an IOException message denotes a TUN `EIO`. Matches the raw
         * errno token ("EIO") and Android's localized English rendering
         * ("Input/output error"), since the message text is not guaranteed stable
         * across versions or locales.
         */
        fun isTunEio(message: String): Boolean =
            message.contains("EIO") || message.contains("Input/output error")
    }

    private val inputStream = FileInputStream(vpnInterface.fileDescriptor)
    private val outputStream = FileOutputStream(vpnInterface.fileDescriptor)
    private val isRunning = AtomicBoolean(false)

    private var onPacketReceived: ((ByteArray, Int, Int) -> Unit)? = null
    private var onError: ((Exception) -> Unit)? = null

    /**
     * Start reading from TUN interface.
     * [onPacket] receives (buffer, offset, length) slices of an internal
     * scratch buffer that is reused for the next packet â€” consumers must
     * not retain the slice beyond the callback (Phase 13E: zero-copy).
     */
    fun start(onPacket: (ByteArray, Int, Int) -> Unit, onError: (Exception) -> Unit) {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "TUN terminal already running")
            return
        }

        this.onPacketReceived = onPacket
        this.onError = onError

        scope.launch(Dispatchers.IO) {
            readLoop()
        }

        Log.d(TAG, "TUN terminal started")
    }

    /**
     * Stop TUN terminal
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) return

        Log.d(TAG, "Stopping TUN terminal")

        try {
            inputStream.close()
            outputStream.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing TUN streams", e)
        }
    }

    /**
     * Write packet to TUN interface
     * @param packet IP packet to write
     * @return Number of bytes written
     */
    fun write(packet: ByteArray): Int {
        if (!isRunning.get()) return -1

        return try {
            outputStream.write(packet)
            outputStream.flush()
            packet.size
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to TUN", e)
            -1
        }
    }

    /**
     * Write a slice of [buffer] to the TUN interface without copying
     * (Phase 13D: lets batched receive avoid per-packet copyOf).
     * @return Number of bytes written
     */
    fun write(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!isRunning.get()) return -1

        return try {
            outputStream.write(buffer, offset, length)
            outputStream.flush()
            length
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to TUN", e)
            -1
        }
    }

    /**
     * Read loop for TUN interface
     */
    private suspend fun readLoop() {
        val buffer = ByteArray(BUFFER_SIZE)

        while (isRunning.get() && scope.isActive) {
            try {
                val length = withContext(Dispatchers.IO) {
                    inputStream.read(buffer)
                }

                when {
                    length > 0 -> {
                        // Got a packet â€” pass the slice directly, no copy
                        onPacketReceived?.invoke(buffer, 0, length)
                    }
                    length < 0 -> {
                        // EOF - connection closed
                        Log.d(TAG, "TUN interface closed")
                        break
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    // Check for EBADF (Bad file descriptor) which happens when interface is closed externally
                    val msg = e.message ?: ""
                    if (e is java.io.IOException && (msg.contains("EBADF") || msg.contains("Bad file descriptor"))) {
                        Log.d(TAG, "TUN interface closed (EBADF), stopping read loop")
                    } else if (e is java.io.IOException && isTunEio(msg)) {
                        // R5 (v1.0.11 audit §5.2): a revoked TUN interface surfaces on
                        // several Android versions as an IOException whose text is
                        // EIO-based rather than EBADF. It previously fell through to
                        // onError() and was funnelled into triggerReconnect(), i.e. a
                        // clean shutdown was misread as a transient I/O error.
                        //
                        // This branch mirrors the EBADF treatment: record the event on
                        // the process-wide TUN-EIO counter (R4a), stop the loop cleanly,
                        // and do NOT call onError. Match both the raw errno text and the
                        // localized message form.
                        //
                        // The counter lives in the top-level TunnelHealth object (this module), not in
                        // GlobalVpnTracker: `app → SoftEtherClient` is the only module
                        // dependency direction, and this file is in SoftEtherClient.
                        Log.w(TAG, "TUN read failed with EIO (interface revoked?), stopping read loop")
                        TunnelHealth.noteTunEio()
                        isRunning.set(false)
                        Log.d(TAG, "TUN read loop ended")
                        return
                    } else {
                        Log.e(TAG, "Error reading from TUN", e)
                        onError?.invoke(e)
                    }
                }
                break
            }
        }

        isRunning.set(false)
        Log.d(TAG, "TUN read loop ended")
    }
}
