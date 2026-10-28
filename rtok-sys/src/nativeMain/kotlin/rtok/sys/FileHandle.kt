// Port of the `same_file::Handle` subset rtok uses: an open file that knows its identity (device + inode), so a
// follower can tell "the path now names another file" apart from "the file grew", and still read the old one.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.sys

import kotlinx.cinterop.*
import platform.posix.*

class FileHandle private constructor(
    private val file: CPointer<FILE>,
    private val dev: Long,
    private val ino: Long,
) : AutoCloseable {
    /** Same file on disk (`Handle == Handle`), whatever path either was opened by. */
    fun sameFile(other: FileHandle?): Boolean = other != null && dev == other.dev && ino == other.ino

    /** The whole file from its start through this handle; null when the read fails. */
    fun readAll(): ByteArray? = if (fseek(file, 0, SEEK_SET) != 0) null else runCatching { readAllFrom(file) }.getOrNull()

    override fun close() {
        fclose(file)
    }

    companion object {
        /** `Handle::from_path`: null when [path] cannot be opened for reading. */
        fun open(path: String): FileHandle? {
            val f = fopen(path, "rb") ?: return null
            val id =
                memScoped {
                    val st = alloc<stat>()
                    if (fstat(fileno(f), st.ptr) == 0) st.st_dev.toLong() to st.st_ino.toLong() else null
                }
            return id?.let { (dev, ino) -> FileHandle(f, dev, ino) } ?: null.also { fclose(f) }
        }
    }
}
