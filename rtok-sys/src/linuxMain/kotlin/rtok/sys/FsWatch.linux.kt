// inotify recursive watch (notify's Linux backend): one watch per directory, new directories watched as they
// appear, a queue overflow reported as a rescan. Polled by the caller, no thread.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.sys

import kotlinx.cinterop.*
import platform.linux.*
import platform.posix.*

private class LinuxWatch(
    private val fd: Int,
) : FsWatch {
    private val dirs = HashMap<Int, String>()
    private val buf = ByteArray(BUF)

    fun add(dir: String) {
        val wd = inotify_add_watch(fd, dir, MASK.toUInt())
        if (wd >= 0) dirs[wd] = dir
        for (name in runCatching { listDir(dir) }.getOrDefault(emptyList())) {
            val p = join(dir, name)
            if (isDir(p) && fileMode(p)?.let { it and S_IFMT == S_IFLNK } != true) add(p)
        }
    }

    override fun next(timeoutMs: Long): FsEvent? =
        memScoped {
            val pfd = alloc<pollfd>()
            pfd.fd = fd
            pfd.events = POLLIN.toShort()
            val r = poll(pfd.ptr, 1u, timeoutMs.toInt())
            if (r == 0 || (r < 0 && errno == EINTR)) return null
            if (r < 0) throw FsWatchClosed()
            val n = buf.usePinned { read(fd, it.addressOf(0), buf.size.convert()) }.toInt()
            if (n <= 0) throw FsWatchClosed()
            val paths = ArrayList<String>()
            var rescan = false
            var off = 0
            buf.usePinned { pin ->
                while (off < n) {
                    val ev = (pin.addressOf(off)).reinterpret<inotify_event>().pointed
                    val len = ev.len.toInt()
                    val name = if (len > 0) (pin.addressOf(off + HEADER)).reinterpret<ByteVar>().toKString() else ""
                    val mask = ev.mask.toInt()
                    if (mask and IN_Q_OVERFLOW != 0) rescan = true
                    dirs[ev.wd]?.let { d ->
                        val p = if (name.isEmpty()) d else join(d, name)
                        paths += p
                        if (mask and IN_ISDIR != 0 && mask and (IN_CREATE or IN_MOVED_TO) != 0) add(p)
                    }
                    if (mask and IN_IGNORED != 0) dirs.remove(ev.wd)
                    off += HEADER + len
                }
            }
            FsEvent(paths, rescan)
        }

    override fun close() {
        close(fd)
    }

    companion object {
        const val BUF = 64 * 1024
        val HEADER = sizeOf<inotify_event>().toInt()
        val MASK = IN_CREATE or IN_DELETE or IN_MODIFY or IN_MOVED_FROM or IN_MOVED_TO or IN_CLOSE_WRITE or IN_ATTRIB or IN_DELETE_SELF
    }
}

actual fun watchRecursive(root: String): FsWatch {
    if (!isDir(root)) throw IoException(IoException.Kind.NotFound, "watch $root: no such directory")
    val fd = inotify_init1(IN_CLOEXEC)
    if (fd < 0) throw IoException(IoException.Kind.Other, "inotify_init1: errno $errno")
    return LinuxWatch(fd).apply { add(root) }
}
