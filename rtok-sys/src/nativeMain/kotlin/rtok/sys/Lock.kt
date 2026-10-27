@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.sys

import platform.posix.*

/** Opens (creating) `path` and takes a non-blocking exclusive flock; the fd while held, null when another process holds it. */
fun tryLockExclusive(path: String): Int? {
    val fd = open(path, O_CREAT or O_WRONLY, 0x1a4) // 0644
    if (fd < 0) throw IoException(IoException.Kind.Other, "cannot open $path")
    if (flock(fd, LOCK_EX or LOCK_NB) != 0) {
        close(fd)
        return null
    }
    return fd
}

/** An in-process mutual-exclusion lock; [hold] blocks until it is free. Not reentrant. */
class Mutex {
    private val held = kotlin.concurrent.AtomicInt(0)

    fun lock() {
        while (!held.compareAndSet(0, 1)) usleep(50u)
    }

    fun unlock() {
        held.value = 0
    }

    inline fun <T> hold(body: () -> T): T {
        lock()
        try {
            return body()
        } finally {
            unlock()
        }
    }
}

/**
 * Whether another open file description holds an exclusive flock on the existing [path] (`File::try_lock` probe as
 * junk_kinds.rs `lock_held`): opened read-write, falling back to read-only; a lock taken here is released at once.
 * @throws IoException when the file cannot be opened or locked for another reason.
 */
fun lockHeld(path: String): Boolean {
    var fd = open(path, O_RDWR)
    if (fd < 0) fd = open(path, O_RDONLY)
    if (fd < 0) throw IoException(ioKind(errno), "open $path: errno $errno")
    try {
        if (flock(fd, LOCK_EX or LOCK_NB) == 0) return false
        if (errno == EWOULDBLOCK) return true
        throw IoException(ioKind(errno), "flock $path: errno $errno")
    } finally {
        close(fd)
    }
}

/** `rtok_sys::process_alive`: a signal-0 probe of a positive [pid]. */
fun processAlive(pid: Int): Boolean = pid > 0 && kill(pid, 0) == 0
