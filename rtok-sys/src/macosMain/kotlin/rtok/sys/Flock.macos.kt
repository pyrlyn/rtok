package rtok.sys

actual fun fileLock(
    fd: Int,
    op: Int,
): Int = platform.posix.flock(fd, op)
