@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.sys

import platform.posix.FD_CLOEXEC
import platform.posix.F_GETFD
import platform.posix.close
import platform.posix.fcntl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LockTest {
    private fun file(tag: String) = "${tempDir()}/rtok-lock-$tag-${pid()}".also { runCatching { removeFile(it) } }

    /** A spawned child that inherited a flock fd keeps the lock held after the holder is gone. */
    @Test fun lockFdsAreCloseOnExec() {
        val path = file("cloexec")
        val a = assertNotNull(tryLockExclusive(path))
        assertEquals(FD_CLOEXEC, fcntl(a, F_GETFD) and FD_CLOEXEC)
        assertNull(tryLockExclusive(path))
        close(a)
        val b = lockExclusive(path)
        assertEquals(FD_CLOEXEC, fcntl(b, F_GETFD) and FD_CLOEXEC)
        close(b)
        removeFile(path)
    }

    /** A detached child started while the lock is held must not carry it past the holder's release. */
    @Test fun aDetachedChildDoesNotKeepTheLock() {
        val path = file("child")
        val fd = assertNotNull(tryLockExclusive(path))
        assertEquals(true, spawnDetached("/bin/sleep", listOf("5"), emptyMap(), ByteArray(0)))
        close(fd)
        val again = assertNotNull(tryLockExclusive(path), "the sleeping child still holds the lock")
        close(again)
        removeFile(path)
    }

    /** `getcwd` fails once the directory is removed under the process; that is an I/O error, not a crash. */
    @Test fun cwdOfARemovedDirectoryIsAnIoError() {
        val before = cwd()
        val gone = "${tempDir()}/rtok-cwd-gone-${pid()}"
        createDirAll(gone)
        setCwd(gone)
        try {
            platform.posix.rmdir(gone)
            assertFailsWith<IoException> { cwd() }
        } finally {
            setCwd(before)
        }
    }
}
