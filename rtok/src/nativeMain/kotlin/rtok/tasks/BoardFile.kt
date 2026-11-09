// The board's task file: `<dir>/<column>/<ID>-<slug>.md`, a YAML front matter (no `status`: the folder is the status)
// and the description as the body, with a closing report under a final `## Report` heading.
@file:OptIn(kotlin.time.ExperimentalTime::class)

package rtok.tasks

import rtok.store.TaskId
import yamlaot.YamlText
import yamlaot.YamlValue
import kotlin.time.Instant

internal const val REPORT_HEADING = "## Report"

/** The keys the board writes itself; any other key in a file is kept as it is on rewrite. */
private val KNOWN_KEYS = setOf("id", "title", "created", "updated", "created_at", "updated_at", "status", "parent", "priority", "assignee", "blocked_by", "conflicts_with", "parallel")

internal fun isoTime(secs: Long): String = Instant.fromEpochSeconds(secs).toString()

private fun parseIso(text: String): Long? = runCatching { Instant.parse(text).epochSeconds }.getOrNull()

/** `created`/`updated` as ISO 8601 text or unix seconds, whichever key the file has; [fallback] when it has neither. */
private fun YamlValue.Map.time(
    fallback: Long,
    vararg keys: String,
): Long {
    val (key, value) = keys.firstNotNullOfOrNull { k -> (this[k] as? YamlValue.Int ?: this[k] as? YamlValue.Str)?.let { k to it } } ?: return fallback
    return when (value) {
        is YamlValue.Int -> value.value
        else -> parseIso((value as YamlValue.Str).value) ?: bail("$key: want an ISO 8601 time, got ${value.value}")
    }
}

/** The file name a board task gets: no spaces, so the id is the text up to the first dash. */
internal fun boardFileName(task: Task) = "${task.id}-${unicodeSlug(task.title)}.md"

/** The id a board file name starts with; also reads the old `<ID> - <slug>.md`. */
internal fun boardFileId(name: String): TaskId? =
    name
        .takeIf { it.endsWith(".md") }
        ?.removeSuffix(".md")
        ?.takeWhile { it != '-' && it != ' ' }
        ?.let { runCatching { TaskId.parse(it) }.getOrNull() }

/** [extra] is the raw text of the keys this format does not know, already newline-terminated. */
internal fun renderBoard(
    task: Task,
    extra: String = "",
): String {
    val yaml =
        buildString {
            append("id: ${YamlText.scalar(task.id.toString())}\n")
            append("title: ${YamlText.scalar(task.title)}\n")
            append("created: ${isoTime(task.createdAt)}\n")
            append("updated: ${isoTime(task.updatedAt)}\n")
            task.parent?.let { append("parent: ${YamlText.scalar(it.toString())}\n") }
            if (task.priority != DEFAULT_PRIORITY) append("priority: ${task.priority}\n")
            task.assignee?.let { append("assignee: ${YamlText.scalar(it)}\n") }
            yamlList("blocked_by", task.blockedBy)
            yamlList("conflicts_with", task.conflictsWith)
            if (task.parallel) append("parallel: true\n")
            append(extra)
        }
    val report = task.report?.trim()?.takeIf { it.isNotEmpty() }
    val body =
        buildString {
            append(task.description.trim())
            if (report != null) {
                if (isNotEmpty()) append("\n\n")
                append("$REPORT_HEADING\n\n$report")
            }
        }
    return if (body.isEmpty()) "---\n$yaml---\n" else "---\n$yaml---\n\n$body\n"
}

/** `(description, report)` of a body: the report is what follows the last `## Report` line. */
private fun splitReport(body: String): Pair<String, String?> {
    val lines = body.lines()
    val at = lines.indexOfLast { it.trimEnd() == REPORT_HEADING }
    if (at < 0) return body to null
    val report = lines.drop(at + 1).joinToString("\n").trim()
    return lines.take(at).joinToString("\n") to report.ifEmpty { null }
}

/** Parse a board file found in the column [status]. A `status:` key in the file is ignored: the folder wins. */
internal fun parseBoard(
    raw: String,
    status: Status,
    fallbackTime: Long,
): Task {
    val (yaml, body) = splitFrontMatter(raw)
    val head = parseHead(yaml)
    val (description, report) = splitReport(body.trim())
    return head.toTask(description, status, head.time(fallbackTime, "created", "created_at"), head.time(fallbackTime, "updated", "updated_at")).copy(report = report)
}

/** The top-level keys of [yaml] that are not [KNOWN_KEYS], with their nested lines, as written. */
internal fun unknownKeys(yaml: String): String {
    var keep = false
    val out = StringBuilder()
    for (line in yaml.lines()) {
        val top = line.isNotEmpty() && !line[0].isWhitespace() && line[0] != '#' && line[0] != '-'
        if (top) keep = line.substringBefore(':').trim().trim('"', '\'') !in KNOWN_KEYS
        if (keep) out.append(line).append('\n')
    }
    return out.toString().trimEnd('\n').let { if (it.isEmpty()) "" else "$it\n" }
}
