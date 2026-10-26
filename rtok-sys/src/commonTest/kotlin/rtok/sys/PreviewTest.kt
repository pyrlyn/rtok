package rtok.sys

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreviewTest {
    @Test
    fun diffModePrintsTheDiffsThenTheTotals() {
        val p = Preview().edit("a.toml", "x\n", "y\nz\n").edit("same", "s\n", "s\n")
        val out = p.render(PreviewMode.Diff)
        assertTrue(out.startsWith("--- a/a.toml\n"), out)
        assertTrue(out.endsWith("1 file changed, 2 insertions(+), 1 deletion(-)\n"), out)
        assertEquals(1, p.edits.size, "an unchanged file is not an edit")
    }

    @Test
    fun statModePrintsOneAlignedLinePerEditAndNoDiff() {
        val p = Preview().edit("a", "", "1\n2\n3\n").edit("long/name", "1\n", "")
        assertEquals(
            " a         | 3 +++\n long/name | 1 -\n2 files changed, 3 insertions(+), 1 deletion(-)\n",
            p.render(PreviewMode.Stat),
        )
    }

    @Test
    fun statBarsShrinkPastFortyMarksAndKeepAMarkForSmallEdits() {
        val big = (0 until 400).joinToString("") { "$it\n" }
        val out = Preview().edit("big", "", big).edit("small", "", "1\n").render(PreviewMode.Stat)
        assertTrue(out.contains("| 400 ${"+".repeat(40)}\n"), out)
        assertTrue(out.contains(" small |   1 +\n"), out)
    }

    @Test
    fun removalsPrintSizeAndFileCountInBothModes() {
        val p = Preview().remove("wt/a/target", 13_000_000_000, 48_213).remove("wt/b/node_modules", 1024, 1)
        val want =
            "- wt/a/target        12.1 GB  48 213 files\n" +
                "- wt/b/node_modules   1.0 KB  1 file\n" +
                "2 paths, 48 214 files, -12.1 GB\n"
        assertEquals(want, p.render(PreviewMode.Diff))
        assertEquals(want, p.render(PreviewMode.Stat))
    }

    @Test
    fun anEmptyPreviewRendersNothing() {
        val p = Preview()
        assertTrue(p.isEmpty())
        assertEquals("", p.render(PreviewMode.Diff))
        assertEquals(PreviewTotals(), p.totals())
    }
}
