// The `trash` crate's Linux delete: the freedesktop.org home trash (`$XDG_DATA_HOME/Trash`, else
// `~/.local/share/Trash`), a `files/<name>` rename plus its `info/<name>.trashinfo`. Only the home trash: a path on
// another volume (rename fails with EXDEV) is copied into it and then deleted, as the crate does.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.sys

import kotlinx.cinterop.toKString
import platform.posix.EXDEV
import platform.posix.errno
import platform.posix.getenv
import platform.posix.rename

actual fun moveToTrash(path: String) {
    val abs = if (path.startsWith("/")) path else "${cwd()}/$path"
    val data = getenv("XDG_DATA_HOME")?.toKString()?.takeIf { it.startsWith("/") } ?: "${getenv("HOME")?.toKString() ?: "/tmp"}/.local/share"
    val trash = "$data/Trash"
    createDirAll("$trash/files")
    createDirAll("$trash/info")
    val base = abs.trimEnd('/').substringAfterLast('/')
    val now =
        kotlin.time.Clock.System
            .now()
            .toString()
            .substringBefore('.')
            .removeSuffix("Z")
    val info = "[Trash Info]\nPath=${percentEncode(abs)}\nDeletionDate=$now\n".encodeToByteArray()
    val name = reserveName(trash, base, info)
    if (rename(abs, "$trash/files/$name") != 0) {
        val e = errno
        try {
            if (e != EXDEV) throw IoException(ioKind(e), "cannot move $path to trash: errno $e")
            moveByCopy(abs, "$trash/files/$name")
        } catch (x: IoException) {
            removeFile("$trash/info/$name.trashinfo")
            throw x
        }
    }
}

/**
 * The spec's reservation: an exclusive create of the info file claims the name, so two processes trashing the same
 * base name at once never pick one name (the rename would replace the first one's file).
 */
private fun reserveName(
    trash: String,
    base: String,
    info: ByteArray,
): String {
    var name = base
    var n = 1
    while (true) {
        val infoFile = "$trash/info/$name.trashinfo"
        if (!exists("$trash/files/$name") && !exists(infoFile)) {
            try {
                createNewBytes(infoFile, info)
                return name
            } catch (e: IoException) {
                // Lost the race for this name: try the next one. Any other failure is real.
                if (!exists(infoFile)) throw e
            }
        }
        name = "$base.${n++}"
    }
}

/** The trashinfo `Path=` escaping: every byte outside unreserved characters and `/` as `%XX`. */
internal fun percentEncode(p: String): String =
    p.encodeToByteArray().joinToString("") { b ->
        val c = b.toInt() and BYTE_MASK
        if (c.toChar().isLetterOrDigit() && c < ASCII_END || c.toChar() in "-._~/") c.toChar().toString() else "%" + c.toString(HEX).uppercase().padStart(2, '0')
    }

private const val BYTE_MASK = 0xff
private const val ASCII_END = 0x80
private const val HEX = 16
