// Port of the file format of src/tasks/disk.rs: names, slugs, and the YAML front matter render and parse.
@file:Suppress("TooManyFunctions")

package rtok.tasks

import rtok.store.TaskId
import yamlaot.Yaml
import yamlaot.YamlText
import yamlaot.YamlValue

/** The line that closes a front matter, with its newlines. */
internal const val FENCE = "\n---\n"

internal fun StringBuilder.yamlList(
    key: String,
    ids: List<TaskId>,
) {
    if (ids.isEmpty()) return
    append("$key:\n")
    ids.forEach { append("  - ").append(YamlText.scalar(it.toString())).append('\n') }
}

/** The task file: front matter in serde field order with its skips, then the body. */
internal fun renderDisk(task: Task): String {
    val yaml =
        buildString {
            append("id: ${YamlText.scalar(task.id.toString())}\n")
            append("title: ${YamlText.scalar(task.title)}\n")
            append("status: ${task.status.str}\n")
            task.parent?.let { append("parent: ${YamlText.scalar(it.toString())}\n") }
            append("created_at: ${task.createdAt}\n")
            append("updated_at: ${task.updatedAt}\n")
            if (task.priority != DEFAULT_PRIORITY) append("priority: ${task.priority}\n")
            task.assignee?.let { append("assignee: ${YamlText.scalar(it)}\n") }
            yamlList("blocked_by", task.blockedBy)
            yamlList("conflicts_with", task.conflictsWith)
            if (task.parallel) append("parallel: true\n")
        }
    val body = task.description.trimEnd()
    return if (body.isEmpty()) "---\n$yaml---\n" else "---\n$yaml---\n\n$body\n"
}

internal fun YamlValue.text(key: String): String =
    when (this) {
        is YamlValue.Str -> value
        is YamlValue.Int -> value.toString()
        is YamlValue.Float -> text
        is YamlValue.Bool -> value.toString()
        else -> bail("$key: want a string")
    }

internal fun YamlValue.Map.long(key: String): Long = (this[key] as? YamlValue.Int)?.value ?: bail("$key: want an integer")

internal fun YamlValue.Map.ids(key: String): List<TaskId> =
    when (val v = this[key]) {
        null, YamlValue.Null -> emptyList()
        is YamlValue.Seq -> v.items.map { TaskId.parse(it.text(key)) }
        else -> bail("$key: want a list")
    }.distinct().sorted()

internal fun YamlValue.Map.optText(key: String): String? = this[key]?.takeIf { it != YamlValue.Null }?.text(key)

internal fun YamlValue.Map.priority(): Int =
    when (val p = this["priority"]) {
        null -> DEFAULT_PRIORITY
        is YamlValue.Int -> p.value.toInt()
        else -> bail("priority: want an integer")
    }

internal fun YamlValue.Map.parallel(): Boolean =
    when (val p = this["parallel"]) {
        null -> false
        is YamlValue.Bool -> p.value
        else -> bail("parallel: want a boolean")
    }

/** `(front matter text, body)` of a task file; CRLF is read as LF. */
internal fun splitFrontMatter(raw: String): Pair<String, String> {
    val text = raw.replace("\r\n", "\n")
    if (!text.startsWith("---\n")) bail("no front matter (the file must start with ---)")
    val rest = text.removePrefix("---\n")
    val at = rest.indexOf(FENCE)
    return when {
        at >= 0 -> rest.substring(0, at) to rest.substring(at + FENCE.length)
        rest.endsWith("\n---") -> rest.removeSuffix("\n---") to ""
        else -> bail("front matter is not closed with ---")
    }
}

internal fun parseHead(yaml: String): YamlValue.Map = Yaml.parse(yaml) as? YamlValue.Map ?: bail("the front matter is not a mapping")

/** The fields the files layout and the board share; the caller supplies what they spell differently. */
internal fun YamlValue.Map.toTask(
    body: String,
    status: Status,
    createdAt: Long,
    updatedAt: Long,
): Task {
    val id = TaskId.parse(this["id"]?.text("id") ?: bail("missing field `id`"))
    return Task.open(id, this["title"]?.text("title") ?: bail("missing field `title`"), "", 0).copy(
        description = body.trim(),
        status = status,
        parent = optText("parent")?.let(TaskId::parse),
        createdAt = createdAt,
        updatedAt = updatedAt,
        priority = priority(),
        assignee = optText("assignee")?.takeIf { it.isNotEmpty() },
        blockedBy = ids("blocked_by"),
        conflictsWith = ids("conflicts_with"),
        parallel = parallel(),
    )
}

internal fun parseDisk(raw: String): Task {
    val (yaml, body) = splitFrontMatter(raw)
    val head = parseHead(yaml)
    return head.toTask(
        body,
        Status.parse(head["status"]?.text("status") ?: bail("missing field `status`")),
        head.long("created_at"),
        head.long("updated_at"),
    )
}
