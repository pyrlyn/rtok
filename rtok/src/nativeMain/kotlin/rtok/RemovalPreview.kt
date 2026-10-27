// Port of src/removal_preview.rs: what a removal frees, and the shared `- path  size  files` lines with their totals.
package rtok

import rtok.sys.Preview
import rtok.sys.PreviewMode
import rtok.sys.diskUsageUntil

object RemovalPreview {
    /** Bytes and files under [path], symlinks not followed. */
    fun sizeOf(path: String): Pair<Long, Long> = diskUsageUntil(path, null).let { it.bytes to it.files }

    /** The removal lines and totals; empty for no rows. Output is never painted (off a terminal, as Rust's is). */
    fun render(rows: List<Triple<String, Long, Long>>): String {
        val preview = Preview()
        for ((path, bytes, files) in rows) preview.remove(path, bytes, files)
        return preview.render(PreviewMode.Stat)
    }
}
