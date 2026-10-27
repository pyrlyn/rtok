@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

// Port of the lock, clock and skip-warning helpers of src/tasks/disk.rs, shared by both disk layouts.

package rtok.tasks

import rtok.sys.createDirAll
import rtok.sys.tryLockExclusive

private const val LOCK_TRIES = 50
private const val LOCK_WAIT_US = 20_000u

private val warned = mutableSetOf<String>()
private val warnedLock = rtok.sys.Mutex()

/** Where skipped-task warnings go; Rust logs them through `log::warn`. Set by the CLI, silent by default. */
var taskWarningSink: (String) -> Unit = {}

/** Warn that a task file was left out of a read, once per process per message. */
internal fun warnSkipped(error: Exception) {
    val message = error.message.orEmpty()
    if (warnedLock.hold { warned.add(message) }) taskWarningSink("skipping a task that does not parse: $message")
}

/** Holds the exclusive `flock` on `<dir>/.claim.lock`; the file is never unlinked. */
internal class DiskGuard(
    private val fd: Int,
) : ClaimLock {
    override fun close() {
        platform.posix.close(fd)
    }
}

/** An exclusive flock on the stable `<dir>/.claim.lock`, polled for a bounded time. */
internal fun lockClaim(dir: String): DiskGuard {
    createDirAll(dir)
    val path = "$dir/.claim.lock"
    repeat(LOCK_TRIES) {
        tryLockExclusive(path)?.let { return DiskGuard(it) }
        platform.posix.usleep(LOCK_WAIT_US)
    }
    bail("disk tasks: $path is locked")
}

internal fun nowSecs(): Long = platform.posix.time(null)
