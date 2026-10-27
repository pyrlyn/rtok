package rtok.log

internal actual fun fileLock(
    fd: Int,
    op: Int,
): Int = platform.posix.flock(fd, op)
