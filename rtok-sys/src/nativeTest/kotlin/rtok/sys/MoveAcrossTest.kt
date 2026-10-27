package rtok.sys

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val OLD_NANOS = 1_600_000_000_000_000_000L

class MoveAcrossTest {
    private fun root(tag: String) = "${tempDir()}/rtok-move-$tag-${pid()}".also { removeDirAll(it) }

    /** The EXDEV fallback of the Linux trash: a tree arrives whole (links kept, times kept) and the source is gone. */
    @Test fun aTreeMovedByCopyArrivesWholeAndTheSourceIsGone() {
        val r = root("tree")
        val src = "$r/src"
        createDirAll("$src/sub")
        writeString("$src/sub/a.txt", "a\n")
        setModified("$src/sub/a.txt", OLD_NANOS)
        platform.posix.symlink("sub/a.txt", "$src/link")
        moveByCopy(src, "$r/dst")
        assertFalse(exists(src))
        assertEquals("a\n", readToString("$r/dst/sub/a.txt"))
        assertEquals(OLD_NANOS, symlinkMeta("$r/dst/sub/a.txt").mtimeNanos)
        assertEquals(EntryKind.Symlink, symlinkMeta("$r/dst/link").kind)
        removeDirAll(r)
    }

    @Test fun aFileMovedByCopyKeepsItsTime() {
        val r = root("file")
        createDirAll(r)
        writeString("$r/f", "x")
        setModified("$r/f", OLD_NANOS)
        moveByCopy("$r/f", "$r/g")
        assertFalse(exists("$r/f"))
        assertEquals(OLD_NANOS, symlinkMeta("$r/g").mtimeNanos)
        removeDirAll(r)
    }

    @Test fun aFailedCopyLeavesTheSourceWhole() {
        val r = root("fail")
        createDirAll(r)
        writeString("$r/f", "x")
        assertFailsWith<IoException> { moveByCopy("$r/f", "$r/missing/dir/g") }
        assertTrue(exists("$r/f"))
        removeDirAll(r)
    }
}
