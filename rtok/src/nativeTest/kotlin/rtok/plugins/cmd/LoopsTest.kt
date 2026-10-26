// Port of the src/plugins/cmd/loops.rs tests (7).
package rtok.plugins.cmd

import kotlin.test.*

class LoopsTest {
    @Test fun pollingLinesCollapseWithFirstAndLastValues() {
        val raw = (1..20).joinToString("") { "attempt $it: status=pending\n" }
        assertEquals("attempt #: status=pending ×20 (vals: 1..20)", Loops.collapse(raw))
    }

    @Test fun aChangedLineEndsTheRunAndStaysVerbatim() {
        val raw = (1..9).joinToString("") { "attempt $it: status=pending\n" } + "attempt 10: status=ready\nattempt 11: status=ready\n"
        assertEquals("attempt #: status=pending ×9 (vals: 1..9)\nattempt 10: status=ready\nattempt 11: status=ready", Loops.collapse(raw))
    }

    @Test fun errorLinesAreNeverCollapsedAndSplitTheRun() {
        val raw = "poll 1 ok\npoll 2 ok\npoll 3 ok\npoll 4 failed: connection refused\npoll 5 failed: connection refused\npoll 6 ok\npoll 7 ok\npoll 8 ok\n"
        assertEquals("poll # ok ×3 (vals: 1..3)\npoll 4 failed: connection refused\npoll 5 failed: connection refused\npoll # ok ×3 (vals: 6..8)", Loops.collapse(raw))
    }

    @Test fun identicalLinesAndBlankSeparatorsCollapseToACount() {
        val raw = "{\"status\":\"starting\"}\n\n{\"status\":\"starting\"}\n\n{\"status\":\"starting\"}\n\n{\"status\":\"up\"}\n"
        assertEquals("{\"status\":\"starting\"} ×3\n\n{\"status\":\"up\"}", Loops.collapse(raw))
    }

    @Test fun aNumberThatGoesUpAndDownShowsItsSmallestAndLargest() = assertEquals("took # ms ×4 (vals: 3..9)", Loops.collapse("took 5 ms\ntook 9 ms\ntook 3 ms\ntook 7 ms\n"))

    @Test fun twoLinesAreLeftAlone() {
        assertNull(Loops.collapse("poll 1\npoll 2\n"))
        assertNull(Loops.collapse(""))
    }

    @Test fun idsAndTimestampsAreFieldsToo() {
        val raw = "12:00:01 job 9f8e7d6c5b4a ready\n12:00:02 job 1a2b3c4d5e6f ready\n12:00:03 job aabbccddeeff ready\n"
        assertEquals("12:00:# job # ready ×3 (vals: 01..03, 9f8e7d6c5b4a..aabbccddeeff)", Loops.collapse(raw))
    }
}
