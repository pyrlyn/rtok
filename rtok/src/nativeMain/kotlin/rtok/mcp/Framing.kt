// Stdio MCP framing for the foreign-server wrapper (mcp/wrap.rs `Framing`, `read_frame`, `write_frame`):
// newline-delimited JSON and `Content-Length` headers, per frame, fed incrementally from a pipe.
package rtok.mcp

import rtok.hook.MAX_FRAME

enum class Framing {
    Line,
    Header,

    /**
     * A malformed header block (unparseable `Content-Length`, a declared length over [MAX_FRAME], or a body
     * shorter than the one declared): the exact bytes consumed so far, forwarded with no framing added.
     */
    Raw,
}

class Frame(
    val framing: Framing,
    val body: ByteArray,
)

/**
 * Splits a byte stream into frames as bytes arrive. [next] returns null when the buffered bytes do not hold a
 * whole frame yet; once `eof` is true every buffered byte comes out exactly once (fail open, D4).
 */
class FrameReader {
    private var data = ByteArray(INITIAL)
    private var start = 0
    private var end = 0

    fun feed(
        bytes: ByteArray,
        n: Int = bytes.size,
    ) {
        if (end + n > data.size) {
            val live = end - start
            val next = ByteArray(maxOf(data.size, live + n) * 2)
            data.copyInto(next, 0, start, end)
            data = next
            start = 0
            end = live
        }
        bytes.copyInto(data, end, 0, n)
        end += n
    }

    fun next(eof: Boolean): Frame? {
        while (start < end && isSpace(data[start])) start++
        if (start == end) return null
        val first = data[start]
        // A JSON frame starts with `{` or `[`; only a header block starts with a letter.
        return if (first != UPPER_C && first != LOWER_C) line(eof) else header(eof)
    }

    private fun line(eof: Boolean): Frame? {
        val nl = indexOf(NL, start)
        val stop =
            if (nl >= 0) {
                nl
            } else if (eof) {
                end
            } else {
                return null
            }
        var cut = stop
        while (cut > start && (data[cut - 1] == NL || data[cut - 1] == CR)) cut--
        val body = data.copyOfRange(start, cut)
        start = if (nl >= 0) nl + 1 else end
        return Frame(Framing.Line, body)
    }

    private fun header(eof: Boolean): Frame? {
        var pos = start
        var len: ULong? = null
        while (true) {
            val nl = indexOf(NL, pos)
            // Stream closed mid-header: forward the real bytes that arrived rather than drop them.
            if (nl < 0) return if (eof) raw(end) else null
            var trimmed = nl + 1
            while (trimmed > pos && isSpace(data[trimmed - 1])) trimmed--
            val lineStart = pos
            pos = nl + 1
            if (trimmed == lineStart) break
            val text = runCatching { data.decodeToString(lineStart, trimmed, throwOnInvalidSequence = true) }.getOrNull() ?: continue
            val colon = text.indexOf(':')
            if (colon >= 0 && text.substring(0, colon).equals("content-length", ignoreCase = true)) {
                len = text.substring(colon + 1).trim().toULongOrNull()
            }
        }
        val headerEnd = pos
        // No parseable `Content-Length`, or one over the hook frame cap: the header goes out raw and the
        // following bytes stay on the stream, so a bogus claim is never buffered or waited on.
        val claimed = len?.takeIf { it <= MAX_FRAME.toULong() }?.toInt() ?: return raw(headerEnd)
        if (end - headerEnd < claimed) return if (eof) raw(end) else null
        val body = data.copyOfRange(headerEnd, headerEnd + claimed)
        start = headerEnd + claimed
        return Frame(Framing.Header, body)
    }

    private fun raw(upTo: Int): Frame {
        val body = data.copyOfRange(start, upTo)
        start = upTo
        return Frame(Framing.Raw, body)
    }

    private fun indexOf(
        b: Byte,
        from: Int,
    ): Int {
        for (i in from until end) if (data[i] == b) return i
        return -1
    }

    companion object {
        private const val INITIAL = 64 * 1024
        private const val NL = '\n'.code.toByte()
        private const val CR = '\r'.code.toByte()
        private const val UPPER_C = 'C'.code.toByte()
        private const val LOWER_C = 'c'.code.toByte()

        /** Rust `u8::is_ascii_whitespace`: space, tab, LF, FF, CR. */
        private fun isSpace(b: Byte) = b == ' '.code.toByte() || b == '\t'.code.toByte() || b == NL || b == 0x0c.toByte() || b == CR

        /** One frame on the wire with its framing restored. */
        fun encode(
            framing: Framing,
            body: ByteArray,
        ): ByteArray =
            when (framing) {
                Framing.Line -> body + NL
                Framing.Header -> "Content-Length: ${body.size}\r\n\r\n".encodeToByteArray() + body
                Framing.Raw -> body
            }
    }
}
