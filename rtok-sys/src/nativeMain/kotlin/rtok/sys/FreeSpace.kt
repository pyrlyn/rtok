// Port of rtok-sys `free_bytes`: the bytes an unprivileged process may still write on the volume of a path.
package rtok.sys

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.errno
import rtok.sys.spawn.rtok_free_bytes

/** `f_bavail × f_frsize` of the volume holding the existing [path]; IoException when statvfs fails. */
@OptIn(ExperimentalForeignApi::class)
fun freeBytes(path: String): Long {
    val n = rtok_free_bytes(path)
    if (n < 0) throw IoException(ioKind(errno), "statvfs $path failed (errno $errno)")
    return n
}
