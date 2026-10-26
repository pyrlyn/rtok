package rtok.sys

import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class FsWatchTest {
    @Test fun aWriteBelowTheRootIsDelivered() {
        val dir =
            canonicalize(
                join(tempDir(), "rtok-fswatch-${pid()}").also {
                    removeDirAll(it)
                    createDirAll(join(it, "sub"))
                },
            )!!
        val w = watchRecursive(dir)
        val target = join(dir, "sub/a.rs")
        val deadline = TimeSource.Monotonic.markNow() + 10.seconds
        var seen = false
        var n = 0
        while (!seen && !deadline.hasPassedNow()) {
            // Rewritten until seen: a write before the stream is live is never delivered.
            writeString(target, "fn a() {} // ${n++}\n")
            val e = w.next(200) ?: continue
            seen = e.paths.any { it == target }
        }
        w.close()
        removeDirAll(dir)
        assertTrue(seen, "no event for $target")
    }

    @Test fun aMissingRootIsRefused() {
        assertFailsWith<IoException> { watchRecursive(join(tempDir(), "rtok-fswatch-missing-${pid()}")) }
    }

    @Test fun aClosedQueueThrowsAfterDraining() {
        val q = FsEventQueue()
        q.push(FsEvent(listOf("/x")))
        q.end()
        assertEquals(listOf("/x"), q.next(10)?.paths)
        assertFailsWith<FsWatchClosed> { q.next(10) }
    }
}
