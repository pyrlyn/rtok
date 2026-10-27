// `fs::symlink_metadata` with the Unix fields disk-usage walks need (`MetadataExt`: blocks, nlink, dev, ino), and
// `File::set_modified` for tests and tools that age a file.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.sys

import kotlinx.cinterop.*
import platform.posix.*

private const val NANOS_PER_SECOND = 1_000_000_000L

/** What `lstat(2)` reports about a path; [mtimeNanos] is nanoseconds since the epoch. */
data class Meta(
    val kind: EntryKind,
    val len: Long,
    val blocks: Long,
    val nlink: Long,
    val dev: Long,
    val ino: Long,
    val mtimeNanos: Long,
)

/** `fs::symlink_metadata`. @throws IoException with the errno's kind. */
fun symlinkMeta(p: String): Meta =
    memScoped {
        val st = alloc<stat>()
        if (lstat(p, st.ptr) != 0) throw IoException(ioKind(errno), "lstat $p: errno $errno")
        val kind =
            when (st.st_mode.toInt() and S_IFMT) {
                S_IFLNK -> EntryKind.Symlink
                S_IFREG -> EntryKind.File
                S_IFDIR -> EntryKind.Dir
                else -> EntryKind.Other
            }
        Meta(kind, st.st_size, st.st_blocks, st.st_nlink.toLong(), st.st_dev.toLong(), st.st_ino.toLong(), mtimeNanos(st))
    }

/** `File::set_modified`: access and modification time of [p] set to [nanos] since the epoch. */
fun setModified(
    p: String,
    nanos: Long,
) {
    memScoped {
        val times = allocArray<timespec>(2)
        for (i in 0..1) {
            times[i].tv_sec = nanos / NANOS_PER_SECOND
            times[i].tv_nsec = nanos % NANOS_PER_SECOND
        }
        if (utimensat(AT_FDCWD, p, times, 0) != 0) throw IoException(ioKind(errno), "utimensat $p: errno $errno")
    }
}
