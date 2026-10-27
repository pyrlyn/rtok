// Port of src/report/pdf.rs (T22.3): the PDF rendering of [Document], the same eight sections in the same order,
// paged on A4 with a contents page and viewer bookmarks. It formats; it computes nothing (D24). The charts are the
// HTML bars' series drawn natively: identical pairs, order and titles.
package rtok.report

/** One P22 section: the fixed title (the HTML `<h2>`) plus its content blocks. */
internal data class Sec(
    val title: String,
    val blocks: List<Block>,
)

internal sealed class Block {
    data class Para(
        val text: String,
    ) : Block()

    data class Chart(
        val title: String,
        val pairs: List<Pair<String, Long>>,
    ) : Block()
}

internal sealed class PageItem {
    abstract fun cost(): Float

    data class Heading(
        val title: String,
    ) : PageItem() {
        override fun cost() = Pdf.HEADING_H
    }

    data class Line(
        val text: String,
    ) : PageItem() {
        override fun cost() = Pdf.LEAD
    }

    data class Chart(
        val title: String,
        val pairs: List<Pair<String, Long>>,
    ) : PageItem() {
        override fun cost() = Pdf.CHART_TITLE_H + pairs.size * Pdf.BAR_STEP + Pdf.CHART_TAIL
    }
}

object Pdf {
    internal const val TOP = 282.0f
    internal const val BOTTOM = 15.0f
    private const val LEFT = 15.0f
    internal const val LEAD = 4.6f
    private const val BODY = 9.5f
    private const val WIDTH = 100
    internal const val AVAIL = TOP - BOTTOM
    internal const val HEADING_H = 9.0f
    internal const val CHART_TITLE_H = 6.0f
    internal const val BAR_STEP = 5.2f
    internal const val CHART_TAIL = 2.0f
    internal val MAX_BARS = ((AVAIL - CHART_TITLE_H - CHART_TAIL) / BAR_STEP).toInt()
    private const val TITLE_SIZE = 18.0f
    private const val HEAD_SIZE = 13.0f
    private const val CHART_SIZE = 10.0f
    private const val BAR_SIZE = 8.0f
    private const val LABEL_CHARS = 26
    private const val BAR_X = 63.0f
    private const val BAR_W = 90.0f
    private const val MM_PT = 2.834_645_7f
    private const val TITLE_GAP = 9f
    private const val WINDOW_GAP = 10f
    private const val CONTENTS_GAP = 8f
    private const val BAR_DROP = 3f
    private const val BAR_MIN = 0.6f
    private const val BAR_H = 3.2f
    private const val BAR_GRAY = 0.25f

    /** The whole document as PDF bytes (A4, builtin Helvetica only). */
    fun render(doc: Document): ByteArray {
        val secs = PdfSections.sections(doc)
        val (starts, pages) = paginate(secs)
        val marks = listOf(PdfBookmark("rtok report", 0)) + secs.zip(starts) { s, i -> PdfBookmark(s.title, physicalPage(i) - 1) }
        val out = listOf(tocPage(doc, secs, starts)) + pages.map { contentPage(it) }
        return PdfWriter.write("rtok report", out, marks)
    }

    /** The 1-based page number of a section's first content page: the contents page comes first. */
    internal fun physicalPage(contentIndex: Int) = contentIndex + 2

    /** Each section's first content page index, and the items on every content page. */
    internal fun paginate(secs: List<Sec>): Pair<List<Int>, List<List<PageItem>>> {
        val starts = mutableListOf<Int>()
        val pages = mutableListOf<MutableList<PageItem>>(mutableListOf())
        for (sec in secs) {
            val queue = ArrayDeque(items(sec))
            var recorded = false
            while (queue.isNotEmpty()) {
                val used = pages.last().sumOf { it.cost().toDouble() }.toFloat()
                val take = chunk(queue, maxOf(AVAIL - used, 0f))
                if (take.isEmpty()) {
                    pages.add(mutableListOf())
                } else {
                    if (!recorded) starts.add(pages.size - 1)
                    recorded = true
                    pages.last().addAll(take)
                }
            }
        }
        return starts to pages
    }

    /** A section's heading, wrapped lines, and charts split at [MAX_BARS] (an empty chart still shows its title). */
    private fun items(sec: Sec): List<PageItem> =
        listOf(PageItem.Heading(sec.title)) +
            sec.blocks.flatMap { b ->
                when (b) {
                    is Block.Para -> {
                        PdfText.wrap(b.text, WIDTH).map { PageItem.Line(it) }
                    }

                    is Block.Chart -> {
                        b.pairs
                            .chunked(MAX_BARS)
                            .ifEmpty { listOf(emptyList()) }
                            .map { PageItem.Chart(b.title, it) }
                    }
                }
            }

    /** Up to [rest] millimetres of items; a chart is atomic, an orphan heading goes back to the queue (T167). */
    internal fun chunk(
        queue: ArrayDeque<PageItem>,
        rest: Float,
    ): List<PageItem> {
        val take = mutableListOf<PageItem>()
        var cost = 0f
        while (queue.isNotEmpty() && cost + queue.first().cost() <= rest) {
            cost += queue.first().cost()
            take.add(queue.removeFirst())
        }
        val orphan = take.singleOrNull() is PageItem.Heading && rest < AVAIL
        if (queue.isNotEmpty() && orphan) queue.addFirst(take.removeAt(0))
        return take
    }

    /** Page 0: title, window, and the contents list with page numbers. */
    private fun tocPage(
        doc: Document,
        secs: List<Sec>,
        starts: List<Int>,
    ): List<PdfOp> {
        val w = doc.ledgers.window
        val ops = mutableListOf<PdfOp>()
        var y = TOP
        show(ops, LEFT, y, true, TITLE_SIZE, "rtok report")
        y -= TITLE_GAP
        show(ops, LEFT, y, false, BODY, "Window ${w.fromDate} -> ${w.toDate} (${w.since})")
        y -= WINDOW_GAP
        show(ops, LEFT, y, true, HEAD_SIZE, "Contents")
        y -= CONTENTS_GAP
        secs.zip(starts).forEach { (sec, start) ->
            show(ops, LEFT, y, false, BODY, "${sec.title}  ${physicalPage(start)}")
            y -= LEAD
        }
        return ops
    }

    /** One content page: headings, wrapped lines, and native bar charts. */
    internal fun contentPage(items: List<PageItem>): List<PdfOp> {
        val ops = mutableListOf<PdfOp>()
        var y = TOP
        for (item in items) {
            when (item) {
                is PageItem.Heading -> {
                    y -= 2f
                    show(ops, LEFT, y, true, HEAD_SIZE, item.title)
                    y -= HEADING_H - 2f
                }

                is PageItem.Line -> {
                    show(ops, LEFT, y, false, BODY, item.text)
                    y -= LEAD
                }

                is PageItem.Chart -> {
                    show(ops, LEFT, y, true, CHART_SIZE, item.title)
                    y = bars(ops, y - CHART_TITLE_H, item.pairs)
                }
            }
        }
        return ops
    }

    /** The HTML bars in millimetres: a bar per pair scaled to the maximum, label left (26 chars), value right. */
    internal fun bars(
        ops: MutableList<PdfOp>,
        top: Float,
        pairs: List<Pair<String, Long>>,
    ): Float {
        var y = top
        pairs.zip(ReportText.barShares(pairs)).forEach { (pair, share) ->
            show(ops, LEFT, y, false, BAR_SIZE, pair.first.take(LABEL_CHARS))
            val w = share.toFloat() * BAR_W
            ops.add(PdfOp.Fill(mm(BAR_X), mm(y - BAR_DROP), mm(maxOf(w, BAR_MIN)), mm(BAR_H), BAR_GRAY))
            show(ops, BAR_X + w + 2f, y, false, BAR_SIZE, pair.second.toString())
            y -= BAR_STEP
        }
        return y - CHART_TAIL
    }

    /** One text line at ([x], [y]) millimetres from the bottom-left corner. */
    private fun show(
        ops: MutableList<PdfOp>,
        x: Float,
        y: Float,
        bold: Boolean,
        size: Float,
        text: String,
    ) {
        ops.add(PdfOp.Text(mm(x), mm(y), bold, size, PdfText.ansi(text)))
    }

    internal fun mm(x: Float) = x * MM_PT
}
