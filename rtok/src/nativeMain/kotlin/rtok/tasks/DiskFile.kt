// Port of the file format of src/tasks/disk.rs: names, slugs, and the YAML front matter render and parse.
package rtok.tasks

import rtok.store.TaskId
import yamlaot.Yaml
import yamlaot.YamlText
import yamlaot.YamlValue

/** The line that closes a front matter, with its newlines. */
internal const val FENCE = "\n---\n"

private fun StringBuilder.yamlList(
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

private fun YamlValue.text(key: String): String =
    when (this) {
        is YamlValue.Str -> value
        is YamlValue.Int -> value.toString()
        is YamlValue.Float -> text
        is YamlValue.Bool -> value.toString()
        else -> bail("$key: want a string")
    }

private fun YamlValue.Map.long(key: String): Long = (this[key] as? YamlValue.Int)?.value ?: bail("$key: want an integer")

private fun YamlValue.Map.ids(key: String): List<TaskId> =
    when (val v = this[key]) {
        null, YamlValue.Null -> emptyList()
        is YamlValue.Seq -> v.items.map { TaskId.parse(it.text(key)) }
        else -> bail("$key: want a list")
    }.distinct().sorted()

private fun YamlValue.Map.optText(key: String): String? = this[key]?.takeIf { it != YamlValue.Null }?.text(key)

private fun YamlValue.Map.priority(): Int =
    when (val p = this["priority"]) {
        null -> DEFAULT_PRIORITY
        is YamlValue.Int -> p.value.toInt()
        else -> bail("priority: want an integer")
    }

private fun YamlValue.Map.parallel(): Boolean =
    when (val p = this["parallel"]) {
        null -> false
        is YamlValue.Bool -> p.value
        else -> bail("parallel: want a boolean")
    }

internal fun parseDisk(raw: String): Task {
    val text = raw.replace("\r\n", "\n")
    if (!text.startsWith("---\n")) bail("no front matter (the file must start with ---)")
    val rest = text.removePrefix("---\n")
    val at = rest.indexOf(FENCE)
    val (yaml, body) =
        when {
            at >= 0 -> rest.substring(0, at) to rest.substring(at + FENCE.length)
            rest.endsWith("\n---") -> rest.removeSuffix("\n---") to ""
            else -> bail("front matter is not closed with ---")
        }
    val head = Yaml.parse(yaml) as? YamlValue.Map ?: bail("the front matter is not a mapping")
    val id = TaskId.parse(head["id"]?.text("id") ?: bail("missing field `id`"))
    return Task.open(id, head["title"]?.text("title") ?: bail("missing field `title`"), "", 0).copy(
        description = body.trim(),
        status = Status.parse(head["status"]?.text("status") ?: bail("missing field `status`")),
        parent = head.optText("parent")?.let(TaskId::parse),
        createdAt = head.long("created_at"),
        updatedAt = head.long("updated_at"),
        priority = head.priority(),
        assignee = head.optText("assignee")?.takeIf { it.isNotEmpty() },
        blockedBy = head.ids("blocked_by"),
        conflictsWith = head.ids("conflicts_with"),
        parallel = head.parallel(),
    )
}
