package rtok.plugins.graph

import kotlin.test.*

class TallyTest {
    @Test fun countsDistinctNamesFilesAndProjects() {
        Tally.arm()
        Tally.hit("/a", "f", listOf("x.rs", "y.rs"))
        Tally.hit("/a", "f", listOf("x.rs"))
        Tally.hit("/b", "g", listOf("x.rs"))
        Tally.hit("/b", "chain", emptyList())
        assertEquals(TallyCounts(3, 3, 2), Tally.take())
    }

    @Test fun rewindDropsWhatAFailedBackendRecorded() {
        Tally.arm()
        Tally.hit("/a", "f", listOf("x.rs"))
        val m = Tally.mark()
        Tally.hit("/b", "g", listOf("y.rs"))
        Tally.rewind(m)
        assertEquals(TallyCounts(1, 1, 1), Tally.take())
    }

    @Test fun renameCreditsTheRowsSinceTheMarkToOneSymbol() {
        Tally.arm()
        Tally.hit("/a", "kept", listOf("x.rs"))
        val m = Tally.mark()
        Tally.hit("/a", "foo_a", listOf("y.rs"))
        Tally.hit("/b", "foo_b", listOf("z.rs"))
        Tally.rename(m, "foo")
        assertEquals(TallyCounts(2, 3, 2), Tally.take())
        Tally.arm()
        Tally.hit("/a", "n", listOf("x.rs"))
        Tally.rename(0, "")
        assertEquals(0, Tally.take().symbols, "an empty name keeps the rows only")
    }

    @Test fun nothingIsKeptUnlessArmed() {
        Tally.take()
        Tally.hit("/a", "f", listOf("x.rs"))
        assertEquals(TallyCounts(), Tally.take())
        assertEquals(0, Tally.mark())
    }
}
