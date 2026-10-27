@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.sys

import rtok.sys.spawn.rtok_clone_or_copy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val OLD_NANOS = 1_600_000_000_123_000_000L
private const val MICRO = 1_000L

class CloneTest {
    /** A clone or a full copy keeps the source's modification time on every platform (Linux used to stamp "now"). */
    @Test fun cloneOrCopyKeepsTheModificationTime() {
        val root = "${tempDir()}/rtok-clone-${pid()}"
        removeDirAll(root)
        createDirAll(root)
        val src = join(root, "a.bin")
        writeString(src, "payload\n")
        setModified(src, OLD_NANOS)
        val dst = join(root, "b.bin")
        assertTrue(rtok_clone_or_copy(src, dst) >= 0)
        assertEquals("payload\n", readToString(dst))
        assertEquals(symlinkMeta(src).mtimeNanos / MICRO, symlinkMeta(dst).mtimeNanos / MICRO)
        removeDirAll(root)
    }
}
