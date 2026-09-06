package vn.unlimit.vpngate.automode

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared Auto Mode log window store (user request 2026-08-30):
 * a bounded, timestamped, source-tagged ring buffer of log lines fed by
 * [AutoModeModuleLogTap] (the real per-module outputs) and the Auto Mode
 * controller lines. The UI collects [lines], filters by the currently
 * connected protocol's modules, and offers copy/clear actions.
 *
 * Every line records which protocol stack produced it so the panel can
 * show exactly the output of the module that is connecting (e.g. only
 * SoftEther lines while Auto Mode drives SoftEther).
 */
object AutoModeLogStore {

    /** Which stack a line originates from — drives the per-protocol filter. */
    enum class Source { AUTO, SOFTETHER, OPENVPN, SSTP, SYSTEM }

    data class Line(
        val timestamp: Long,
        val source: Source,
        val text: String,
    )

    private const val MAX_LINES = 2000

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines

    @Volatile
    private var paused = false

    fun add(source: Source, text: String) {
        if (paused || text.isBlank()) return
        val line = Line(System.currentTimeMillis(), source, text.replace('\n', ' ').trim())
        val current = _lines.value
        val updated = if (current.size >= MAX_LINES) {
            current.takeLast(MAX_LINES - 1) + line
        } else {
            current + line
        }
        _lines.value = updated
    }

    /** All lines joined for clipboard copy (all sources, not filtered). */
    fun asText(): String = _lines.value.joinToString("\n") { format(it) }

    fun format(line: Line): String {
        val stamp = synchronized(timeFormat) { timeFormat.format(Date(line.timestamp)) }
        return "$stamp ${line.source.name}: ${line.text}"
    }

    fun clear() {
        _lines.value = emptyList()
    }

    /**
     * Filter lines for the log panel: while Auto Mode is driving a specific
     * protocol, show that protocol's module output plus the AUTO lines;
     * with no active protocol show everything (SYSTEM included).
     */
    fun filterFor(protocol: AutoModeProtocol?): List<Line> {
        if (protocol == null) return _lines.value
        val moduleSource = when (protocol) {
            AutoModeProtocol.SOFTETHER_TCP, AutoModeProtocol.SOFTETHER_UDP -> Source.SOFTETHER
            AutoModeProtocol.OPENVPN_TCP, AutoModeProtocol.OPENVPN_UDP -> Source.OPENVPN
            AutoModeProtocol.MS_SSTP -> Source.SSTP
            AutoModeProtocol.L2TP_IPSEC -> Source.SYSTEM
        }
        return _lines.value.filter { it.source == Source.AUTO || it.source == moduleSource }
    }

    /** L2TP connects via the OS Settings UI — nothing to tap, silence the panel. */
    fun setPaused(value: Boolean) {
        paused = value
    }
}
