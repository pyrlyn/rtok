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

private fun Char.isAsciiAlnum() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

/** Lower-case ASCII letters and digits joined by single dashes; `task` when nothing is left. */
internal fun slug(title: String) = slugOf(title, Char::isAsciiAlnum)

/** The board's slug: every Unicode letter and digit is kept, so a Cyrillic title still names its file. */
internal fun unicodeSlug(title: String) = slugOf(title, Char::isLetterOrDigit)

private fun slugOf(
    title: String,
    keep: (Char) -> Boolean,
): String {
    val out = StringBuilder()
    for (c in title) {
        if (keep(c)) {
            out.append(c.lowercaseChar())
        } else if (out.isNotEmpty() && !out.endsWith('-')) {
            out.append('-')
        }
        if (out.length >= SLUG_MAX) break
    }
    return out.toString().trimEnd('-').ifEmpty { "task" }
}
