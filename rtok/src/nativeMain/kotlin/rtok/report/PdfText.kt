// Text helpers of src/report/pdf.rs: word wrap and the WinAnsi fold for the builtin Helvetica.
package rtok.report

internal object PdfText {
    private const val LATIN1_END = 256
    private const val ASCII_END = 128

    /** Word wrap to [width] columns (byte lengths, as the Rust renderer counts); overlong words split on chars. */
    fun wrap(
        s: String,
        width: Int,
    ): List<String> {
        val lines = mutableListOf<String>()
        val cur = StringBuilder()
        for (piece in s.split(' ').flatMap { splitWord(it, width) }) {
            if (cur.isNotEmpty() && utf8(cur.toString()) + 1 + utf8(piece) > width) {
                lines.add(cur.toString())
                cur.clear()
            }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(piece)
        }
        if (cur.isNotEmpty()) lines.add(cur.toString())
        return lines
    }

    /** A word longer than [width] chars as [width]-char chunks, on code points rather than bytes. */
    fun splitWord(
        word: String,
        width: Int,
    ): List<String> {
        val cps = codePoints(word)
        if (cps.size <= width) return listOf(word)
        return cps.chunked(width).map { it.joinToString("") }
    }

    /** WinAnsi: map the punctuation the report emits, pass Latin-1, degrade the rest to `?`; drop tabs and CRs. */
    fun ansi(s: String): String =
        codePoints(s).joinToString("") { c ->
            when (c) {
                "→" -> "->"
                "—", "–", "·" -> "-"
                "…" -> "..."
                "‘", "’" -> "'"
                "“", "”" -> "\""
                "\t" -> " "
                "\r" -> ""
                else -> if (latin1(c)) c else "?"
            }
        }

    private fun codePoints(s: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            val n = if (s[i].isHighSurrogate() && i + 1 < s.length) 2 else 1
            out.add(s.substring(i, i + n))
            i += n
        }
        return out
    }

    /** ASCII passes as is; the rest of Latin-1 unless it is a C1 control. */
    private fun latin1(c: String): Boolean {
        val code = if (c.length == 1) c[0].code else LATIN1_END
        return code < ASCII_END || (code < LATIN1_END && !c[0].isISOControl())
    }

    private fun utf8(s: String) = s.encodeToByteArray().size
}
