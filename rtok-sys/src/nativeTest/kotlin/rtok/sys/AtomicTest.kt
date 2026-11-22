@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.sys

import kotlinx.cinterop.convert
import platform.posix.chmod
import kotlin.test.*

private const val OWNER_ONLY = 0x180 // 0o600
private const val MODE_BITS = 0x1ff // 0o777

class AtomicTest {
    @Test fun replacesTheFileKeepsItsModeAndLeavesNoTempFile() {
        val root = scratch()
        try {
            val file = join(root, "state.json")
            writeAtomic(file, "one".encodeToByteArray())
            chmod(file, OWNER_ONLY.convert())
            writeAtomic(file, "two".encodeToByteArray())
            assertEquals("two", readToString(file))
            assertEquals(OWNER_ONLY, assertNotNull(fileMode(file)) and MODE_BITS)
            assertEquals(listOf("state.json"), listDir(root))
        } finally {
            removeDirAll(root)
        }
    }

    @Test fun writesThroughASymlinkInsteadOfReplacingIt() {
        val root = scratch()
        try {
            val target = join(root, "target")
            val link = join(root, "link")
            writeString(target, "old")
            symlink(target, link)
            writeAtomic(link, "new".encodeToByteArray())
            assertEquals(EntryKind.Symlink, entryKind(link))
            assertEquals("new", readToString(target))
        } finally {
            removeDirAll(root)
        }
    }

    @Test fun aMissingParentFailsWithoutLeavingATempFile() {
        val root = scratch()
        try {
            assertFailsWith<IoException> { writeAtomic(join(root, "missing/file"), ByteArray(1)) }
            assertEquals(emptyList(), listDir(root))
        } finally {
            removeDirAll(root)
        }
    }
}
