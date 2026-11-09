@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.sys

import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.setenv
import platform.posix.unsetenv
import kotlin.test.*

internal const val PRIVATE_DIR = 0x1c0 // 0o700

/** A fresh private directory for one test; the test removes it. */
internal fun scratch() = createTempDir("rtok-fs-test-")

class FsTest {
    @Test fun createDirAllMakesMissingParentsAndAcceptsADirectoryOrALinkToOne() {
        val root = scratch()
        try {
            val deep = join(root, "a/b/c")
            createDirAll(deep)
            assertTrue(isDir(deep))
            createDirAll(deep)
            symlink(deep, join(root, "link"))
            createDirAll(join(root, "link"))
        } finally {
            removeDirAll(root)
        }
    }

    @Test fun createDirAllFailsWhenAFileIsInTheWay() {
        val root = scratch()
        try {
            val file = join(root, "file")
            writeString(file, "x")
            assertFailsWith<IoException> { createDirAll(file) }
            assertFailsWith<IoException> { createDirAll(join(file, "sub")) }
        } finally {
            removeDirAll(root)
        }
    }

    @Test fun tempDirFallsBackToTmpForAnEmptyRootOrMissingTmpdir() {
        val saved = getenv("TMPDIR")?.toKString()
        val root = scratch()
        try {
            val file = join(root, "file")
            writeString(file, "")
            for (bad in listOf("", "/", "//", join(root, "missing"), file)) {
                setenv("TMPDIR", bad, 1)
                assertEquals("/tmp", tempDir(), "TMPDIR=$bad")
            }
            setenv("TMPDIR", "$root//", 1)
            assertEquals(root, tempDir())
        } finally {
            if (saved == null) unsetenv("TMPDIR") else setenv("TMPDIR", saved, 1)
            removeDirAll(root)
        }
    }

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
