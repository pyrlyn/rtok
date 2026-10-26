@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package rtok.sys

import platform.posix.*

/** Opens (creating) `path` and takes a non-blocking exclusive flock; the fd while held, null when another process holds it. */
fun tryLockExclusive(path: String): Int? {
    val fd = open(path, O_CREAT or O_WRONLY, 0x1a4) // 0644
    if (fd < 0) throw IoException(IoException.Kind.Other, "cannot open $path")
    if (flock(fd, LOCK_EX or LOCK_NB) != 0) { close(fd); return null }
    return fd
}
