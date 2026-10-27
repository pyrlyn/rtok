// Byte-level readers of a rendered PDF for the tests: object offsets, page content starts, the contents page and
// outline destinations (the helpers of src/report/pdf.rs's tests).
@file:Suppress("MagicNumber")

package rtok.report

internal class PdfBytes(
    bytes: ByteArray,
) {
    /** Latin-1 view: one char per byte, so string offsets are byte offsets. */
    val text: String = bytes.map { (it.toInt() and 0xFF).toChar() }.joinToString("")

    fun positions(needle: String): List<Int> {
        val out = mutableListOf<Int>()
        var at = text.indexOf(needle)
        while (at >= 0) {
            out.add(at)
            at = text.indexOf(needle, at + 1)
        }
        return out
    }

    private fun objectOffset(id: Int): Int = positions("$id 0 obj").first { it == 0 || !text[it - 1].isDigit() }

    private fun digitsAfter(s: String): Int = s.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }.toInt()

    fun pageObjectIds(): List<Int> {
        val kids = text.indexOf("/Kids[")
        val slice = text.substring(kids, text.indexOf(']', kids))
        return Regex("(\\d+) 0 R").findAll(slice).map { it.groupValues[1].toInt() }.toList()
    }

    private fun pageContentStarts(): List<Int> =
        pageObjectIds().map { id ->
            val off = objectOffset(id)
            val body = text.substring(off, text.indexOf("endobj", off))
            objectOffset(digitsAfter(body.substringAfter("/Contents")))
        }

    private fun pageIndex(offset: Int): Int = pageContentStarts().indexOfLast { it <= offset }.coerceAtLeast(0)

    fun tocPageNum(title: String): Int {
        val pos = positions("($title  ").first()
        return digitsAfter(text.substring(pos, pos + 32))
    }

    fun bodyHeadingPage(title: String): Int = pageIndex(positions("($title)").last())

    fun bookmarkPage(title: String): Int {
        val needle = "/Title<${PdfWriter.utf16Hex(title)}>/Dest["
        val pos = text.indexOf(needle) + needle.length
        val id = text.substring(pos, pos + 16).takeWhile { it.isDigit() }.toInt()
        return pageObjectIds().indexOf(id)
    }
}
