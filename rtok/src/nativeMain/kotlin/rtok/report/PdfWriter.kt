// A minimal, dependency-free PDF 1.7 writer for the report (stands in for printpdf in src/report/pdf.rs): A4 pages
// of uncompressed content streams over the two builtin Helvetica faces, plus a flat outline of bookmarks. Streams stay
// uncompressed so every figure is greppable in the artifact.
package rtok.report

/** One drawing operation on a page; coordinates are points from the bottom-left corner. */
sealed class PdfOp {
    data class Text(
        val x: Float,
        val y: Float,
        val bold: Boolean,
        val size: Float,
        val text: String,
    ) : PdfOp()

    data class Fill(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val gray: Float,
    ) : PdfOp()
}

/** A bookmark: its title and the 0-based index of the page it opens. */
data class PdfBookmark(
    val title: String,
    val page: Int,
)

object PdfWriter {
    private const val A4_W = "595.28"
    private const val A4_H = "841.89"
    private const val FIRST_PAGE_ID = 5
    private const val BYTE_MASK = 0xFF
    private const val LATIN1_MAX = 255
    private const val XREF_DIGITS = 10
    private const val HEX = 16
    private const val UNIT_DIGITS = 4

    /** The document bytes: catalog 1, pages 2, resources 3, outline 4, then a page and its stream per page. */
    fun write(
        title: String,
        pages: List<List<PdfOp>>,
        marks: List<PdfBookmark>,
    ): ByteArray {
        val objs = mutableListOf<ByteArray>()
        val pageId = { i: Int -> FIRST_PAGE_ID + 2 * i }
        val markBase = FIRST_PAGE_ID + 2 * pages.size
        val kids = pages.indices.joinToString(" ") { "${pageId(it)} 0 R" }
        objs += ascii("<</Type/Catalog/Pages 2 0 R/Outlines 4 0 R/PageMode/UseOutlines>>")
        objs += ascii("<</Type/Pages/Kids[$kids]/Count ${pages.size}>>")
        objs += ascii("<</Font<</F1<</Type/Font/Subtype/Type1/BaseFont/Helvetica/Encoding/WinAnsiEncoding>>/F2<</Type/Font/Subtype/Type1/BaseFont/Helvetica-Bold/Encoding/WinAnsiEncoding>>>>>>")
        val outline = if (marks.isEmpty()) "" else "/First $markBase 0 R/Last ${markBase + marks.size - 1} 0 R"
        objs += ascii("<</Type/Outlines$outline/Count ${marks.size}>>")
        pages.forEachIndexed { i, ops ->
            objs += ascii("<</Type/Page/Parent 2 0 R/MediaBox[0 0 $A4_W $A4_H]/Resources 3 0 R/Contents ${pageId(i) + 1} 0 R>>")
            val body = stream(ops)
            objs += ascii("<</Length ${body.size}>>\nstream\n") + body + ascii("\nendstream")
        }
        marks.forEachIndexed { i, m ->
            val prev = if (i > 0) "/Prev ${markBase + i - 1} 0 R" else ""
            val next = if (i < marks.size - 1) "/Next ${markBase + i + 1} 0 R" else ""
            objs += ascii("<</Title<${utf16Hex(m.title)}>/Dest[${pageId(m.page)} 0 R/XYZ null null null]/Parent 4 0 R$prev$next>>")
        }
        return assemble(title, objs)
    }

    private fun assemble(
        title: String,
        objs: List<ByteArray>,
    ): ByteArray {
        val out = ArrayList<Byte>()

        fun put(b: ByteArray) = b.forEach { out.add(it) }
        put(ascii("%PDF-1.7\n%"))
        put(byteArrayOf(0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte()))
        put(ascii("\n"))
        val offsets =
            objs.mapIndexed { i, body ->
                val at = out.size
                put(ascii("${i + 1} 0 obj\n"))
                put(body)
                put(ascii("\nendobj\n"))
                at
            }
        val infoId = objs.size + 1
        val infoAt = out.size
        put(ascii("$infoId 0 obj\n<</Title<${utf16Hex(title)}>/Producer(rtok)>>\nendobj\n"))
        val xref = out.size
        put(ascii("xref\n0 ${infoId + 1}\n0000000000 65535 f \n"))
        (offsets + infoAt).forEach { put(ascii("${it.toString().padStart(XREF_DIGITS, '0')} 00000 n \n")) }
        put(ascii("trailer\n<</Size ${infoId + 1}/Root 1 0 R/Info $infoId 0 R>>\nstartxref\n$xref\n%%EOF\n"))
        return out.toByteArray()
    }

    private fun stream(ops: List<PdfOp>): ByteArray {
        val out = ArrayList<Byte>()
        for (op in ops) {
            when (op) {
                is PdfOp.Text -> {
                    val font = if (op.bold) "F2" else "F1"
                    ascii("BT\n/$font ${num(op.size)} Tf\n${num(op.x)} ${num(op.y)} Td\n").forEach { out.add(it) }
                    literal(op.text).forEach { out.add(it) }
                    ascii(" Tj\nET\n").forEach { out.add(it) }
                }

                is PdfOp.Fill -> {
                    val g = num(op.gray)
                    ascii("$g $g $g rg\n${num(op.x)} ${num(op.y)} ${num(op.width)} ${num(op.height)} re\nf\n").forEach { out.add(it) }
                }
            }
        }
        return out.toByteArray()
    }

    /** A PDF literal string of Latin-1 text: parentheses and backslashes escaped, one byte per char. */
    fun literal(text: String): ByteArray {
        val out = ArrayList<Byte>()
        out.add('('.code.toByte())
        for (c in text) {
            if (c == '(' || c == ')' || c == '\\') out.add('\\'.code.toByte())
            out.add((if (c.code <= LATIN1_MAX) c.code else '?'.code).and(BYTE_MASK).toByte())
        }
        out.add(')'.code.toByte())
        return out.toByteArray()
    }

    /** A text string as `FEFF` plus UTF-16BE hex, the form viewers show in the bookmarks pane. */
    fun utf16Hex(title: String): String =
        "FEFF" +
            title
                .map {
                    it.code
                        .toString(HEX)
                        .uppercase()
                        .padStart(UNIT_DIGITS, '0')
                }.joinToString("")

    private fun num(v: Float): String = rtok.measure.stats.fixed(v.toDouble(), 2)

    private fun ascii(s: String): ByteArray = s.encodeToByteArray()
}
