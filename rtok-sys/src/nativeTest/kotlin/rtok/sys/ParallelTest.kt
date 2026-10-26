package rtok.sys

import kotlin.test.*

class ParallelTest {
    @Test fun resultsArriveInInputOrderWhateverTheFinishingOrder() {
        val items = (0 until 200).toList()
        val seen = ArrayList<Pair<Int, Int>>()
        eachOrdered(items, { i ->
            if (i % 7 == 0) platform.posix.usleep(200u)
            i * i
        }) { i, r -> seen += i to r }
        assertEquals(items.map { it to it * it }, seen)
    }

    @Test fun aSinkErrorStopsTheRun() {
        var calls = 0
        assertFailsWith<IllegalStateException> {
            eachOrdered((0 until 100).toList(), { it }) { i, _ ->
                calls++
                check(i < 3)
            }
        }
        assertEquals(4, calls)
    }
}
