package co.candyhouse.sesame.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Debug-only in-memory diagnostics. Callers must never append credentials or payloads. */
object P2DiagnosticLog {
    private const val MAX_LINES = 600
    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun append(tag: String, message: String) {
        val line = "${formatter.format(Date())} $tag $message"
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    fun snapshot(): String = synchronized(lock) { lines.joinToString("\n") }

    fun clear() = synchronized(lock) { lines.clear() }
}
