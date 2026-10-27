@file:Suppress("MagicNumber")

// Port of the src/tasks/disk.rs claim-lock tests.

package rtok.tasks

import rtok.store.TaskId
import rtok.sys.PipedChild
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.isFile
import rtok.sys.listDir
import rtok.sys.pid
import rtok.sys.readBytes
import rtok.sys.readToString
import rtok.sys.removeDirAll
import rtok.sys.removeFile
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun id(s: String) = TaskId.parse(s)

private fun new(title: String) = NewTask(title)

private fun tmp(name: String): String {
    val dir = "${tempDir()}/rtok-kt-lock-$name-${pid()}"
    runCatching { removeDirAll(dir) }
    return dir
}

class ClaimLockTest {
    @Test fun aLeftoverLockFileIsTakenAndAHeldOneIsKept() {
        val dir = tmp("lock")
        createDirAll(dir)
        val path = "$dir/.claim.lock"
        writeString(path, "")
        lockClaim(dir).close()
        assertTrue(isFile(path), "the lock file is stable, never unlinked")
        val held = lockClaim(dir)
        val err = assertFailsWith<TaskException> { lockClaim(dir) }
        assertTrue("locked" in err.message!!, err.message)
        held.close()
        lockClaim(dir).close()
        removeDirAll(dir)
    }

    @Test fun anOldLockFileDoesNotLetASecondClaimantIn() {
        val dir = tmp("lock-held-old")
        val a = lockClaim(dir)
        // A paused holder looks "stale" by mtime; the lock must still exclude.
        PipedChild.spawn("/usr/bin/touch", listOf("-t", "200001010000", "$dir/.claim.lock")).waitExit()
        assertTrue("locked" in assertFailsWith<TaskException> { lockClaim(dir) }.message!!)
        a.close()
        val b = lockClaim(dir)
        assertTrue("locked" in assertFailsWith<TaskException> { lockClaim(dir) }.message!!)
        b.close()
        removeDirAll(dir)
    }

    @Test fun aKilledHolderReleasesTheLock() {
        val dir = tmp("lock-kill")
        createDirAll(dir)
        val ready = "$dir/child-ready"
        val script = "use Fcntl qw(:flock); open(F, '>>', \$ARGV[0]) or die; flock(F, LOCK_EX) or die; open(R, '>', \$ARGV[1]); close(R); sleep 60"
        val child = PipedChild.spawn("/usr/bin/perl", listOf("-e", script, "$dir/.claim.lock", ready))
        repeat(500) { if (!exists(ready)) platform.posix.usleep(20_000u) }
        assertTrue(exists(ready), "child never locked")
        val err = assertFailsWith<TaskException> { lockClaim(dir) }
        assertTrue("locked" in err.message!!, err.message)
        child.kill()
        lockClaim(dir).close()
        removeDirAll(dir)
    }
}
