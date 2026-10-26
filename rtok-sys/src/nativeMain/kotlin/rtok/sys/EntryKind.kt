// Symlink-aware file queries (Rust `symlink_metadata`, `read_link`, `os::unix::fs::symlink`) and a
// mode-keeping file copy, for installers that link or copy a tree into a host's config.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.sys

import kotlinx.cinterop.*
import platform.posix.*

/** What `lstat(2)` finds at a path: the link itself, never its target. */
enum class EntryKind { File, Dir, Symlink, Other }

/** `symlink_metadata().file_type()`; null when nothing (not even a dangling link) is there. */
fun entryKind(p: String): EntryKind? =
    memScoped {
        val st = alloc<stat>()
        if (lstat(p, st.ptr) != 0) return null
        when (st.st_mode.toInt() and S_IFMT) {
            S_IFLNK -> EntryKind.Symlink
            S_IFREG -> EntryKind.File
            S_IFDIR -> EntryKind.Dir
            else -> EntryKind.Other
        }
    }

/** `fs::read_link`: the target as written; null when [p] is not a link. */
fun readLink(p: String): String? =
    memScoped {
        val buf = allocArray<ByteVar>(PATH_MAX + 1)
        val n = readlink(p, buf, PATH_MAX.convert()).toInt()
        if (n < 0) null else buf.readBytes(n).decodeToString()
    }

/** `os::unix::fs::symlink(target, link)`. */
fun symlink(
    target: String,
    link: String,
) {
    if (platform.posix.symlink(target, link) != 0) throw IoException(IoException.Kind.Other, "symlink $target → $link: errno $errno")
}

/** Copy one file's bytes and permission bits (`fs::copy`), following links on the source side. */
fun copyFile(
    src: String,
    dest: String,
) {
    writeBytes(dest, readBytes(src))
    fileMode(src)?.let { chmod(dest, it.convert()) }
}
