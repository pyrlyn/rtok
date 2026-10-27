package rtok.sys

/** `flock(2)` on [fd]: 0 on success. Linux exports it from `platform.linux` (where `platform.posix.flock` is the struct). */
expect fun fileLock(
    fd: Int,
    op: Int,
): Int
