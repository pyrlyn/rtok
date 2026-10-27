// Port of the file names of src/tasks/disk.rs: `<id> - <slug>.md`.
package rtok.tasks

import rtok.store.TaskId
import rtok.sys.extension
import rtok.sys.fileName

/** Longest slug in a file name; the title itself lives in the front matter. */
internal const val SLUG_MAX = 48

/** The id a file name carries: `R12 - ship-it.md` → `R12`. */
internal fun fileId(path: String): TaskId? =
    fileName(path)
        ?.takeIf { extension(path) == "md" }
        ?.removeSuffix(".md")
        ?.takeIf { " - " in it }
        ?.let { runCatching { TaskId.parse(it.substringBefore(" - ")) }.getOrNull() }

internal fun diskFileName(
    id: TaskId,
    title: String,
) = "$id - ${slug(title)}.md"

/** Lower-case ASCII letters and digits joined by single dashes; `task` when nothing is left. */
internal fun slug(title: String): String {
    val out = StringBuilder()
    for (c in title) {
        if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') {
            out.append(c.lowercaseChar())
        } else if (out.isNotEmpty() && !out.endsWith('-')) {
            out.append('-')
        }
        if (out.length >= SLUG_MAX) break
    }
    return out.toString().trimEnd('-').ifEmpty { "task" }
}
