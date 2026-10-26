package rtok

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatPreviewTest {
    private val diff = "--- a/f\n+++ b/f\n@@ -1,2 +1,3 @@\n keep\n-old\n+new\n+more\n"

    @AfterTest
    fun reset() = StatPreview.set(false)

    @Test
    fun totalsCloseTheDiff() {
        StatPreview.set(false)
        val out = StatPreview.showFile("f", diff)
        assertTrue(out.startsWith("--- a/f"), out)
        assertTrue(out.endsWith("1 file changed, 2 insertions(+), 1 deletion(-)"), out)
    }

    @Test
    fun statReplacesTheDiff() {
        StatPreview.set(true)
        val out = StatPreview.showFile("f", diff)
        assertFalse(out.contains("@@"), out)
        assertTrue(out.contains("f | 3 ++-"), out)
        assertTrue(out.contains("1 file changed"), out)
    }

    @Test
    fun aPaintedDiffCountsTheSame() {
        val painted = diff.replace("+new", "\u001b[32m+new\u001b[0m")
        assertEquals(StatPreview.sides(diff), StatPreview.sides(painted))
    }

    @Test
    fun nothingChangedPrintsNothing() {
        assertEquals("", StatPreview.showFile("f", ""))
    }
}
