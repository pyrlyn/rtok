package rtok.sys

import kotlin.test.*

internal const val PRIVATE_DIR = 0x1c0 // 0o700

/** A fresh private directory for one test; the test removes it. */
internal fun scratch() = createTempDir("rtok-fs-test-")

class FsTest {
    @Test fun createTempDirMakesAFreshPrivateDirectoryEachTime() {
        val a = scratch()
        val b = scratch()
        try {
            assertNotEquals(a, b)
            assertTrue(a.startsWith("${tempDir()}/rtok-fs-test-"), a)
            assertTrue(isDir(a) && isDir(b))
            assertEquals(PRIVATE_DIR, fileMode(a))
        } finally {
            removeDirAll(a)
            removeDirAll(b)
        }
    }

    @Test fun withTempFileHandsOverTheBytesAndRemovesItsDirectory() {
        var path = ""
        val got =
            withTempFile("rtok-fs-test-", "in.txt", "hello".encodeToByteArray()) {
                path = it
                readToString(it)
            }
        assertEquals("hello", got)
        assertNull(entryKind(parent(path)!!))
    }

    @Test fun withTempFileRemovesItsDirectoryWhenTheBlockThrows() {
        var path = ""
        assertFailsWith<IllegalStateException> {
            withTempFile("rtok-fs-test-", "in.txt", ByteArray(0)) {
                path = it
                error("boom")
            }
        }
        assertNull(entryKind(parent(path)!!))
    }
}
