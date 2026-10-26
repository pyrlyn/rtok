package rtok.mcp

import kotlin.test.Test
import kotlin.test.assertEquals

class LineReaderTest {
    private fun source(
        bytes: ByteArray,
        chunk: Int = 3,
    ): ByteSource {
        var at = 0
        return ByteSource { buf ->
            val n = minOf(chunk, buf.size, bytes.size - at)
            bytes.copyInto(buf, 0, at, at + n)
            at += n
            n
        }
    }

    /** A long line answers `-32700` downstream and a non-UTF-8 byte does not end the loop. */
    @Test fun nextLineSkipsLongLinesAndSurvivesBadUtf8() {
        val input = "0123456789\n{\"id\":1}\n".encodeToByteArray() + byteArrayOf(0xff.toByte(), '\n'.code.toByte()) + "1234\n".encodeToByteArray()
        val r = LineReader(source(input), 5)
        val got = generateSequence { r.next() }.toList()
        assertEquals(listOf("{", "{", "\uFFFD\n", "1234\n"), got)
        val r2 = LineReader(source("0123456789\n{\"id\":1}\n".encodeToByteArray()), 5)
        assertEquals("{", r2.next())
    }

    @Test fun aLastLineWithoutNewlineIsReturned() {
        val r = LineReader(source("a\nb".encodeToByteArray()))
        assertEquals(listOf("a\n", "b"), generateSequence { r.next() }.toList())
    }
}
