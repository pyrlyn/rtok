// The request-line reader of `rtok mcp` (mcp.rs `next_line`): bad UTF-8 becomes U+FFFD, and a
// line over the cap is drained and answered as the unparsable stub `{`.
package rtok.mcp

/** A byte stream: fills [buf] and returns how many bytes it read, 0 at EOF. */
fun interface ByteSource {
    fun read(buf: ByteArray): Int
}

class LineReader(
    private val src: ByteSource,
    private val max: Int = MAX_LINE,
) {
    private val chunk = ByteArray(CHUNK)
    private var pos = 0
    private var len = 0

    private fun fill(): Boolean {
        if (pos < len) return true
        len = src.read(chunk).coerceAtLeast(0)
        pos = 0
        return len > 0
    }

    /** The next line with its `\n`, `null` at EOF. */
    fun next(): String? {
        val line = ByteArrayBuilder()
        var over = false
        var any = false
        while (fill()) {
            any = true
            val nl = (pos until len).firstOrNull { chunk[it] == '\n'.code.toByte() }
            val end = nl?.plus(1) ?: len
            if (!over) {
                line.append(chunk, pos, end)
                if (line.size > max) over = true
            }
            pos = end
            if (nl != null) break
        }
        return when {
            !any -> null
            over -> "{"
            else -> line.toByteArray().decodeToString()
        }
    }

    private class ByteArrayBuilder {
        private var buf = ByteArray(CHUNK)
        var size = 0
            private set

        fun append(
            src: ByteArray,
            from: Int,
            to: Int,
        ) {
            val n = to - from
            if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n))
            src.copyInto(buf, size, from, to)
            size += n
        }

        fun toByteArray() = buf.copyOf(size)
    }

    companion object {
        /** Longest request line kept in memory. Tool arguments are notes and paths, far below this. */
        const val MAX_LINE = 8 shl 20
        private const val CHUNK = 64 * 1024
    }
}
