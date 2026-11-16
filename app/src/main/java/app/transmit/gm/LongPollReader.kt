package app.transmit.gm

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Splits the long-poll response stream into its payloads. The server streams one JSON array,
 * `[[payload,payload,...]]`, writing each payload (itself a JSON array) as it happens, so this
 * finds element boundaries by bracket depth rather than waiting for the response to end.
 */
class LongPollReader(private val input: InputStream) {
    private val buf = ByteArray(16 * 1024)
    private var len = 0
    private var pos = 0
    private var opened = false

    /** The next payload's JSON, or null once the stream ends (cleanly with `]]`, or cut off). */
    fun next(): String? {
        if (!opened) {
            // The response opens with the outer array and the payload list: "[[".
            if (!expect('[') || !expect('[')) return null
            opened = true
        }
        val out = ByteArrayOutputStream()
        var depth = 0
        var inString = false
        var escaped = false
        while (true) {
            val b = readByte() ?: return null
            val c = b.toInt().toChar()
            if (depth == 0) {
                when {
                    c == '[' -> { depth = 1; out.write(b.toInt()) }
                    c == ']' -> return null // end of the payload list
                    else -> Unit // commas and whitespace between payloads
                }
                continue
            }
            out.write(b.toInt())
            when {
                inString && escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '[' -> depth++
                c == ']' -> if (--depth == 0) return out.toString(Charsets.UTF_8.name())
            }
        }
    }

    private fun expect(ch: Char): Boolean {
        while (true) {
            val b = readByte() ?: return false
            val c = b.toInt().toChar()
            if (c.isWhitespace()) continue
            return c == ch
        }
    }

    private fun readByte(): Byte? {
        if (pos == len) {
            len = input.read(buf)
            pos = 0
            if (len <= 0) {
                len = 0
                return null
            }
        }
        return buf[pos++]
    }
}
