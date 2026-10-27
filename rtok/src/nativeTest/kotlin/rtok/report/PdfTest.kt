// Port of the tests in src/report/pdf.rs and tests/report_pdf.rs (T22.3, T167, T632, T633, T36.13): multibyte
// labels, pagination of any chart height, the bottom margin, char-safe word splits, and contents/outline page
// numbers that land on the heading's page, plus the headings-in-order and empty-store checks over the bytes.
@file:Suppress("MagicNumber")

package rtok.report

import rtok.model.ReportCalls
import rtok.model.ReportCallsSection
import rtok.model.ReportSavings
import rtok.model.ReportSavingsSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfTest {
    private val heads = listOf("Window", "Savings", "Calls", "Cache", "Expand", "Config", "Doctor", "Recommendations")

    @Test
    fun barsTruncatesLongMultibyteLabelWithoutFailing() {
        val ops = mutableListOf<PdfOp>()
        Pdf.bars(ops, Pdf.TOP, listOf("é".repeat(30) to 5L))
        assertEquals("é".repeat(26), (ops[0] as PdfOp.Text).text)
    }

    @Test
    fun aChartOfAnyHeightPaginates() {
        for (n in listOf(Pdf.MAX_BARS, 150)) {
            val pairs = (0 until n).map { "p$it" to it.toLong() }
            val (starts, pages) = Pdf.paginate(listOf(Sec("Savings", listOf(Block.Chart("t", pairs)))))
            assertEquals(listOf(0), starts)
            assertEquals(n, pages.flatten().sumOf { (it as? PageItem.Chart)?.pairs?.size ?: 0 }, "every pair drawn once")
            assertTrue(pages.all { p -> p.sumOf { it.cost().toDouble() } <= Pdf.AVAIL + 1e-3 })
        }
    }

    @Test
    fun chartRowsStayAboveTheBottomMargin() {
        for ((bars, paras) in listOf(56 to 0, 56 to 12, 150 to 5, 30 to 40)) {
            val pairs = (0 until bars).map { "p$it" to 1L + it }
            val blocks = (0 until paras).map { Block.Para("line $it") } + Block.Chart("t", pairs)
            val (_, pages) = Pdf.paginate(listOf(Sec("Savings", blocks)))
            for (page in pages) {
                for (op in Pdf.contentPage(page)) {
                    val y = if (op is PdfOp.Text) op.y else (op as PdfOp.Fill).y
                    assertTrue(y >= Pdf.mm(Pdf.BOTTOM) - 0.01f, "y $y pt is below the bottom margin")
                }
            }
        }
    }

    @Test
    fun splitWordSplitsOnCharsNotBytes() {
        assertEquals(listOf("éé", "éé", "é"), PdfText.splitWord("ééééé", 2))
        assertEquals(listOf("short"), PdfText.splitWord("short", 26))
    }

    @Test
    fun anOrphanHeadingMovesWithItsBlockInsteadOfVanishing() {
        val queue = ArrayDeque(listOf(PageItem.Heading("Orphan"), PageItem.Line("body")))
        assertTrue(Pdf.chunk(queue, Pdf.HEADING_H).isEmpty(), "the heading moves with its block")
        assertEquals(2, queue.size, "nothing is consumed on the way out")
    }

    @Test
    fun sectionPageNumbersMatchHeadingPages() {
        val pdf = PdfBytes(Pdf.render(fatDoc(40, 120)))
        val pages = pdf.positions("/Type/Page").size - pdf.positions("/Type/Pages").size
        assertTrue(pages >= 3, "need a multi-page report, got $pages")
        for (title in heads) {
            val body = pdf.bodyHeadingPage(title)
            assertEquals(body + 1, pdf.tocPageNum(title), "$title: toc")
            assertEquals(body, pdf.bookmarkPage(title), "$title: outline")
        }
    }

    @Test
    fun pdfHasTheHtmlHeadingsInOrderAndTheCharts() {
        val doc = ReportFixtures.oneRow()
        val html = Html.render(doc)
        heads.forEach { assertTrue(">$it</h2>" in html, "html heading $it") }
        val pdf = PdfBytes(Pdf.render(doc))
        assertTrue(pdf.text.startsWith("%PDF-"), "magic")
        assertTrue("%%EOF" in pdf.text.takeLast(8), "trailer")
        assertTrue(pdf.positions("(Contents)").isNotEmpty(), "contents page")
        val first = heads.map { pdf.positions("($it").first() }
        val last = heads.map { pdf.positions("($it").last() }
        assertEquals(first.sorted(), first, "contents order matches html")
        assertEquals(last.sorted(), last, "body order matches html")
        val plugin =
            doc.ledgers.savings.rows
                .first()
                .plugin
        listOf("($plugin)", "(saved tokens per plugin)").forEach { assertTrue(pdf.positions(it).isNotEmpty(), "chart keeps $it") }
    }

    @Test
    fun emptyStorePdfKeepsAllHeadings() {
        val pdf = PdfBytes(Pdf.render(ReportFixtures.zero()))
        assertTrue(pdf.text.startsWith("%PDF-"))
        heads.forEach { assertTrue(pdf.positions("($it").isNotEmpty(), "heading $it") }
        assertTrue(pdf.positions("(No rows in window.)").isNotEmpty())
    }

    private fun fatDoc(
        savingsRows: Int,
        configRows: Int,
    ): Document {
        val b = ReportFixtures.zero()
        val l = b.ledgers
        val rows = (0 until savingsRows).map { ReportSavings("plugin-${it.toString().padStart(3, '0')}", 1uL, 10, 5, 5) }
        return b.copy(
            ledgers =
                l.copy(
                    window = l.window.copy(callsInWindow = 7uL, callsTotal = 7uL, measurements = 3uL, usage = 3uL),
                    savings = ReportSavingsSection(rows, savingsRows.toULong(), savingsRows * 5L, listOf("filter")),
                    calls = ReportCallsSection(listOf(ReportCalls("hook", 2uL, 2uL, 2.0, 4.0)), 2uL, 2uL, emptyList()),
                ),
            config = (0 until configRows).map { ConfigEntry("section.test.key-${it.toString().padStart(3, '0')}", "value", "user") },
        )
    }
}
