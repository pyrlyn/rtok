package rtok.sys

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

private const val LEN = 5000

class DiskUsageTest {
    @Test fun countsAllocatedBlocksOnceAndStopsAtTheDeadline() {
        val d = "${tempDir()}/rtok-du-${pid()}"
        removeDirAll(d)
        createDirAll("$d/a")
        writeString("$d/a/f", "x".repeat(LEN))
        symlink("$d/a/f", "$d/link")
        val u = diskUsageUntil(d, null)
        assertEquals(2L, u.files)
        assertTrue(u.bytes >= LEN, "$u")
        assertTrue(!u.denied && !u.timedOut)
        assertTrue(diskUsageUntil(d, TimeSource.Monotonic.markNow()).timedOut)
        assertEquals(0L, diskUsageUntil("$d/missing", null).bytes)
        removeDirAll(d)
    }

    @Test fun readDirTellsMissingFromOther() {
        val e = runCatching { readDir("/nonexistent-rtok-dir") }.exceptionOrNull() as IoException
        assertEquals(IoException.Kind.NotFound, e.kind)
    }
}
