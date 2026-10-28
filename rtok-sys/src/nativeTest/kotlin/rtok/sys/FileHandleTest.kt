package rtok.sys

import kotlin.test.*

class FileHandleTest {
    private fun rename(
        a: String,
        b: String,
    ) = check(platform.posix.rename(a, b) == 0)

    @Test fun identityFollowsTheFileNotThePath() {
        val dir = join(tempDir(), "rtok-handle-${pid()}")
        removeDirAll(dir)
        createDirAll(dir)
        val path = join(dir, "a.log")
        writeString(path, "x\n")
        val a = assertNotNull(FileHandle.open(path))
        val same = assertNotNull(FileHandle.open(path))
        assertTrue(a.sameFile(same))
        rename(path, "$path.1")
        writeString(path, "y\n")
        val next = assertNotNull(FileHandle.open(path))
        assertFalse(a.sameFile(next), "a new file at the same path is another file")
        assertFalse(a.sameFile(null))
        appendString("$path.1", "z\n")
        assertEquals("x\nz\n", a.readAll()?.decodeToString(), "the handle reads the renamed file")
        removeFile("$path.1")
        assertEquals("x\nz\n", a.readAll()?.decodeToString(), "and still reads it once deleted")
        assertNull(FileHandle.open(join(dir, "missing")))
        listOf(a, same, next).forEach(FileHandle::close)
        removeDirAll(dir)
    }
}
