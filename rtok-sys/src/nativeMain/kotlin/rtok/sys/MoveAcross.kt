// A move that works where rename(2) cannot: across file systems (EXDEV), as the Linux home trash needs for a path on
// another volume.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.sys

import platform.posix.errno
import rtok.sys.spawn.rtok_clone_or_copy

/**
 * Moves [from] to [to] (which must not exist) by copying and then deleting the source: a directory as [cloneTree]
 * copies it, a file as a clone or full copy with its times kept, a link recreated. If the copy fails, the partial
 * copy is removed and the source is left whole. @throws IoException naming the path that failed.
 */
fun moveByCopy(
    from: String,
    to: String,
) {
    val meta = symlinkMeta(from)
    try {
        when (meta.kind) {
            EntryKind.Dir -> {
                cloneTree(from, to)
            }

            EntryKind.Symlink -> {
                copyLink(from, to)
            }

            else -> {
                if (rtok_clone_or_copy(from, to) < 0) throw IoException(ioKind(errno), "copy $from: errno $errno")
                setModified(to, meta.mtimeNanos)
            }
        }
    } catch (e: IoException) {
        if (symlinkExists(to)) removeTree(to)
        throw e
    }
    removeTree(from)
}

private fun symlinkExists(p: String): Boolean = runCatching { symlinkMeta(p) }.isSuccess
