// Port of fs-kit `tree::clone_tree` (feature "clone"): a directory copied as cheaply as the file system allows,
// links kept, every file's modification time kept.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.sys

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import platform.posix.errno
import platform.posix.readlink
import platform.posix.symlink
import rtok.sys.spawn.rtok_clone_or_copy

/** What [cloneTree] did with the regular files it met: [cloned] share blocks with the source, [copied] went in full. */
data class Cloned(
    val cloned: Long = 0,
    val copied: Long = 0,
)

private const val LINK_MAX = 4096

/**
 * Copies the directory [src] into [dest] (created): each regular file a copy-on-write clone where the volume can
 * make one, a full copy where it cannot; links recreated, never followed; modification times kept, so a build tool
 * sees the copies exactly as old as the originals. @throws IoException naming the path that failed.
 */
fun cloneTree(
    src: String,
    dest: String,
): Cloned {
    var cloned = 0L
    var copied = 0L

    fun walk(
        from: String,
        to: String,
    ) {
        createDirAll(to)
        for (name in readDir(from)) {
            val f = join(from, name)
            val t = join(to, name)
            val meta = symlinkMeta(f)
            when (meta.kind) {
                EntryKind.Dir -> {
                    walk(f, t)
                }

                EntryKind.Symlink -> {
                    copyLink(f, t)
                }

                else -> {
                    when (rtok_clone_or_copy(f, t)) {
                        1 -> cloned++
                        0 -> copied++
                        else -> throw IoException(ioKind(errno), "clone $f: errno $errno")
                    }
                    setModified(t, meta.mtimeNanos)
                }
            }
        }
    }
    if (symlinkMeta(src).kind != EntryKind.Dir) throw IoException(IoException.Kind.Other, "$src: not a directory")
    walk(src, dest)
    return Cloned(cloned, copied)
}

internal fun copyLink(
    from: String,
    to: String,
) {
    val target =
        memScoped {
            val buf = allocArray<ByteVar>(LINK_MAX)
            val n = readlink(from, buf, LINK_MAX.toULong())
            if (n < 0) throw IoException(ioKind(errno), "readlink $from: errno $errno")
            buf.readBytes(n.toInt()).decodeToString()
        }
    if (symlink(target, to) != 0) throw IoException(ioKind(errno), "symlink $to: errno $errno")
}
