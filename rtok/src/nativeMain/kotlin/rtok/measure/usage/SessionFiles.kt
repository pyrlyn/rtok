// The walk every file-based reader of src/measure/usage/ shares: the host's files under each dir, oldest name first,
// each read whole and parsed, and the first file that could not be read or held nothing parsable.
package rtok.measure.usage

import rtok.measure.HostSlices
import rtok.measure.pathsWithExt
import rtok.store.UsageSlice
import rtok.sys.fileName
import rtok.sys.readToString

/** The files under [dirs] with one of [exts] that [keep] accepts, sorted. */
internal fun sessionFiles(
    dirs: List<String>,
    cutoff: Long,
    exts: List<String> = listOf("jsonl"),
    keep: (String) -> Boolean = { true },
): List<String> = dirs.flatMap { d -> exts.flatMap { pathsWithExt(d, cutoff, it) } }.filter(keep).sorted()

/** [parse] each of [paths]; null from it (or an unreadable file) names the file. */
internal fun readEach(
    paths: List<String>,
    parse: (path: String, text: String) -> List<UsageSlice>?,
): HostSlices {
    val out = ArrayList<UsageSlice>()
    var unreadable: String? = null
    for (p in paths) {
        val rows = runCatching { readToString(p) }.getOrNull()?.let { parse(p, it) }
        if (rows == null) unreadable = unreadable ?: p else out += rows
    }
    return out to unreadable
}

/** Rust `Path::file_stem`. */
internal fun stem(path: String): String = fileName(path)?.let { n -> n.lastIndexOf('.').takeIf { it > 0 }?.let { n.substring(0, it) } ?: n } ?: ""
