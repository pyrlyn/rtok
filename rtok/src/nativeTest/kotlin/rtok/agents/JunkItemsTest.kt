// Port of src/agents/junk_items.rs tests.
package rtok.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val DAY = 86_400L
private const val HOUR = 3_600L
private const val MINUTE = 60L
private const val OLD = 35L
private const val DUMP = 12L
private const val WEEKS3 = 21L
private const val THREE = 3
private const val S10 = 10L
private const val S20 = 20L
private const val S30 = 30L

class JunkItemsTest {
    private fun view() = JunkView(shown = 2, exact = true, days = 30u, now = 100 * DAY, home = "/home/u")

    private fun row(
        path: String,
        size: Long,
        last: Long?,
    ) = JunkRow("claude", "cache", "safe", path, size, last, "cache (research.md §22)", true, null, "", "research.md §22", true, true)

    @Test fun aKindShowsItsLargestItemsThenTheRestAsOneLine() {
        val g = JunkItems.groups(listOf(row("/a", 10, null), row("/b", 30, null), row("/c", 20, null)), view())[0]
        val text = g.lines(view(), "  ")
        assertTrue(text.startsWith("  /b  30  last used unknown"), text)
        assertEquals(THREE, text.lines().count { it.isNotEmpty() }, text)
        assertTrue(text.endsWith("  +1 more (10)\n"), text)
    }

    @Test fun itemsZeroPrintsNoItemLineAndAllPrintsEveryItem() {
        val rows = { listOf(row("/a", 10, null), row("/b", 30, null)) }
        val none = view().copy(shown = 0)
        assertEquals("", JunkItems.groups(rows(), none)[0].lines(none, ""))
        val all = view().copy(shown = JunkItems.shownArg("all"))
        assertEquals(
            2,
            JunkItems
                .groups(rows(), all)[0]
                .lines(all, "")
                .lines()
                .count { it.isNotEmpty() },
        )
        assertFailsWith<IllegalArgumentException> { JunkItems.shownArg("x") }
    }

    @Test fun minSizeHidesSmallerItemsAndSortOrdersByTimeOrPath() {
        val v = view().copy(minSize = 15, shown = 9)
        assertEquals(2, JunkItems.groups(listOf(row("/a", S10, null), row("/b", S30, null), row("/c", S20, null)), v)[0].shown.size)
        val byTime = view().copy(sort = JunkSort.LastUsed, shown = 9)
        val order = JunkItems.groups(listOf(row("/a", 1, 500), row("/b", 1, null), row("/c", 1, 100)), byTime)[0].shown.map { it.path }
        assertEquals(listOf("/c", "/a", "/b"), order, "oldest first, unknown last")
        val byPath = view().copy(sort = JunkSort.Path, shown = 9)
        assertEquals("/a", JunkItems.groups(listOf(row("/b", 1, null), row("/a", 1, null)), byPath)[0].shown[0].path)
    }

    @Test fun reasonsNameTheThresholdAndAFutureTime() {
        val v = view()
        assertEquals("not touched for 35 days (threshold 30)", JunkItems.reason("sessions", "x", v.now - OLD * DAY, v))
        assertEquals("crash dump 12 days old", JunkItems.reason("crash-dumps", "x", v.now - DUMP * DAY, v))
        assertEquals("cache (research.md §22)", JunkItems.reason("cache", "research.md §22", null, v))
        assertTrue(JunkItems.reason("sessions", "x", v.now + MINUTE, v).endsWith("future timestamp"))
    }

    @Test fun timesAndPathsReadLikeTheCard() {
        val v = view()
        assertTrue(JunkItems.whenUsed(v.now - WEEKS3 * DAY, v).startsWith("3 weeks ago ("))
        assertTrue(JunkItems.whenUsed(v.now - HOUR, v).startsWith("1 hour ago ("))
        assertEquals("unknown", JunkItems.whenUsed(null, v))
        assertEquals("~/.claude/cache", JunkItems.tilde("/home/u/.claude/cache", v.home))
        assertEquals("/var/x", JunkItems.tilde("/var/x", v.home))
    }

    @Test fun aReviewItemIsListedWithWhyItIsNotCleared() {
        val r = JunkRow.ofItem("claude", JunkItem("index", "review", "/x", 5, "research.md §22"))
        assertTrue(!r.willClear)
        assertEquals("review kind: add --include review", r.skipReason)
        val g = JunkItems.groups(listOf(r), view().copy(shown = 9))[0]
        assertTrue(g.lines(view(), "").contains("(not cleared: review kind"))
    }
}
