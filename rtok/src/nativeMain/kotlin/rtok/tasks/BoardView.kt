// `rtok task board`: the four columns of a project's tasks, as text and as JSON.
package rtok.tasks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

/** Finished columns show this many tasks, the most recently updated, unless asked for all. */
private const val RECENT = 10

/** The characters of an assignee that fit a card line. */
private const val ASSIGNEE_WIDTH = 8

private const val SCHEMA = 1

/** One column: [total] tasks, of which [tasks] are shown. */
class BoardColumn(
    val status: Status,
    val total: Int,
    val tasks: List<Task>,
)

class BoardView(
    val path: String,
    val layout: String,
    val columns: List<BoardColumn>,
    val warnings: List<String>,
) {
    fun toJson() =
        JsonObject(
            mapOf(
                "schema" to JsonPrimitive(SCHEMA),
                "kind" to JsonPrimitive("board"),
                "board" to JsonObject(mapOf("path" to JsonPrimitive(path), "layout" to JsonPrimitive(layout))),
                "remote" to JsonNull,
                "columns" to
                    JsonArray(
                        columns.map { c ->
                            JsonObject(mapOf("status" to JsonPrimitive(c.status.str), "tasks" to JsonArray(c.tasks.map { TASK_JSON.encodeToJsonElement(it) })))
                        },
                    ),
                "warnings" to JsonArray(warnings.map(::JsonPrimitive)),
            ),
        )

    fun text(): String =
        columns.joinToString("\n") { c ->
            buildString {
                append("${c.status.str} (${c.total})\n")
                for (t in c.tasks) append("  ${t.id}  ${t.title}${cardMarkers(t)}\n")
                if (c.total > c.tasks.size) append("  … ${c.total - c.tasks.size} more (--all)\n")
            }
        }
}

private fun cardMarkers(t: Task) =
    buildString {
        if (t.priority != DEFAULT_PRIORITY) append("  p${t.priority}")
        t.assignee?.let { append("  @${it.take(ASSIGNEE_WIDTH)}") }
        if (t.status.isActive && t.blockedBy.isNotEmpty()) append("  blocked by ${t.blockedBy.joinToString(", ")}")
    }

/** Every task in its column. Works for any layout: the files and plan layouts map onto the same four columns. */
fun TaskProject.board(all: Boolean): BoardView {
    val tasks = adapter.list(Filter(all = true))
    val columns =
        BOARD_COLUMNS.map { status ->
            val inColumn = tasks.filter { it.status == status }
            val shown = if (status.isActive || all) inColumn else inColumn.sortedByDescending { it.updatedAt }.take(RECENT)
            BoardColumn(status, inColumn.size, shown)
        }
    return BoardView(boardDir, layout, columns, (adapter as? BoardAdapter)?.warnings.orEmpty())
}
