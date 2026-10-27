// Port of the tests in src/report/mod.rs, markdown.rs and html.rs (T105): bar shares, the shown archive id, and
// the Markdown/HTML snapshots of the zero, one-row and hostile documents. The `.snap` files are the Rust ones.
@file:Suppress("MagicNumber")

package rtok.report

import snapshot.SnapshotSettings
import snapshot.assertSnapshot
import snapshot.withSettings
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportTextTest {
    @Test fun barSharesScalesToTheMaxAndFloorsNegatives() = assertEquals(listOf(1.0, 0.5, 0.0), ReportText.barShares(listOf("a" to 10L, "b" to 5L, "c" to -3L)))

    @Test fun barSharesAllZeroIsAllZero() = assertEquals(listOf(0.0, 0.0), ReportText.barShares(listOf("a" to 0L, "b" to 0L)))

    @Test fun barSharesEmptyIsEmpty() = assertEquals(emptyList(), ReportText.barShares(emptyList()))

    @Test fun shownArchiveIdShortensOnlyAFullSha() {
        assertEquals("abababab", ReportText.shownArchiveId("ab".repeat(32)))
        assertEquals("abababab", ReportText.shownArchiveId("abababab"))
        assertEquals("f".repeat(12), ReportText.shownArchiveId("f".repeat(12)))
        assertEquals("<script>alert(1)</script>", ReportText.shownArchiveId("<script>alert(1)</script>"))
    }
}

class MarkdownReportTest {
    /** No `NaN`/`inf` reaches the document and every table keeps the width of its header row. */
    private fun holds(out: String) {
        assertFalse("NaN" in out, out)
        assertFalse("inf" in out.lowercase(), out)
        var width = 0
        for (line in out.lines()) {
            if (line.startsWith('|')) {
                val pipes = line.count { it == '|' } - Regex("""\\\|""").findAll(line).count()
                if (width == 0) width = pipes else assertEquals(width, pipes, "table row broken: $line")
            } else {
                width = 0
            }
        }
    }

    private fun snap(
        name: String,
        out: String,
    ) = withSettings(SnapshotSettings(prefix = "rtok__report__markdown__tests")) { assertSnapshot(name, out, "out") }

    @Test fun zeroRowsSnapshot() {
        val out = Markdown.render(ReportFixtures.zero())
        holds(out)
        snap("zero", out)
    }

    @Test fun oneRowSnapshot() {
        val out = Markdown.render(ReportFixtures.oneRow())
        holds(out)
        assertContains(out, "9223372036854775807", message = "very large numbers")
        snap("one_row", out)
    }

    @Test fun aFullArchiveIdInTheExpandListPrintsEightHexChars() {
        val id = "ab".repeat(32)
        val d = ReportFixtures.oneRow()
        val doc = d.copy(ledgers = d.ledgers.copy(expand = d.ledgers.expand.copy(expandedIds = listOf(id))))
        val short = rtok.shortId(id)
        val md = Markdown.render(doc)
        assertContains(md, "Expanded: $short.")
        assertFalse(id in md)
        val html = Html.render(doc)
        assertContains(html, "<code>$short</code>")
        assertFalse(id in html)
    }

    @Test fun hostileTextSnapshot() {
        val out = Markdown.render(ReportFixtures.hostile())
        holds(out)
        assertContains(out, "\\|", message = "the table pipe is escaped")
        for (line in out.lines().filter { it.startsWith('|') && "second line" in it }) assertTrue("script" in line, "the cell folded to one row: $line")
        snap("hostile", out)
    }
}

class HtmlReportTest {
    private fun holds(out: String) {
        assertFalse("NaN" in out, out)
        assertFalse("inf" in out.lowercase(), out)
        assertFalse("<script>" in out, "no injected HTML")
    }

    private fun snap(
        name: String,
        out: String,
    ) = withSettings(SnapshotSettings(prefix = "rtok__report__html__tests")) { assertSnapshot(name, out, "out") }

    @Test fun zeroRowsSnapshot() {
        val out = Html.render(ReportFixtures.zero())
        holds(out)
        snap("zero", out)
    }

    @Test fun oneRowSnapshot() {
        val out = Html.render(ReportFixtures.oneRow())
        holds(out)
        assertContains(out, "9223372036854775807")
        snap("one_row", out)
    }

    @Test fun hostileTextSnapshot() {
        val out = Html.render(ReportFixtures.hostile())
        holds(out)
        assertContains(out, "&lt;script&gt;", message = "escaped, not injected")
        snap("hostile", out)
    }
}
