// The replace-by-rename write every durable rtok file goes through (fs-kit `write::atomic`). It lives here, below
// the agent SDK, because task files, daemon state and caches need it as much as host configs do.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.sys

import kotlinx.cinterop.convert
import platform.posix.chmod
import platform.posix.rename
import kotlin.random.Random

private const val TMP_RADIX = 16

/** Write to a sibling temp file, then rename over [path]: a reader sees the old bytes or the new ones, never a part. */
fun writeAtomic(
    path: String,
    bytes: ByteArray,
) {
    // Renaming over a dotfiles symlink would replace the link itself, so the write lands on its target instead.
    val dest = if (entryKind(path) == EntryKind.Symlink) canonicalize(path) ?: path else path
    // The random part keeps two threads of one process off the same temp file.
    val tmp = "${parent(dest) ?: "."}/.${fileName(dest)}.tmp-${pid()}-${Random.nextLong().toULong().toString(TMP_RADIX)}"
    try {
        writeBytes(tmp, bytes)
    } catch (e: IoException) {
        runCatching { removeFile(tmp) }
        throw e
    }
    fileMode(dest)?.let { chmod(tmp, it.convert()) }
    if (rename(tmp, dest) != 0) {
        runCatching { removeFile(tmp) }
        throw IoException(IoException.Kind.Other, "$dest: rename failed")
    }
}
