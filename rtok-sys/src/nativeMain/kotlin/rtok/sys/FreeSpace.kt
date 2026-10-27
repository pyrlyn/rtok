// Port of rtok-sys `free_bytes`: the bytes an unprivileged process may still write on the volume of a path.
package rtok.sys

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.errno
import rtok.sys.spawn.statvfs

/** `f_bavail × f_frsize` of the volume holding the existing [path]; IoException when statvfs fails. */
@OptIn(ExperimentalForeignApi::class)
fun freeBytes(path: String): Long =
    memScoped {
        val st = alloc<statvfs>()
        if (statvfs(path, st.ptr) != 0) throw IoException(ioKind(errno), "statvfs $path failed (errno $errno)")
        st.f_bavail.toLong() * st.f_frsize.toLong()
    }
