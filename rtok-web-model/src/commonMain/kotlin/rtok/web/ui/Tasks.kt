// Port of web/src/pages/Tasks.tsx's logic (T441.13.7): the board's columns, a card's markers and the search filter
// over the snapshot's `tasks.board`.
package rtok.web.ui

import kotlinx.serialization.json.JsonObject

/** Tasks.tsx `COLUMNS`: the statuses of `rtok task`, left to right, with their titles. */
val TASK_COLUMNS = listOf("open" to "open", "in-progress" to "in progress", "done" to "done", "closed" to "closed")

/** `rtok task`'s default priority, which the CLI leaves out of its output too. */
const val DEFAULT_TASK_PRIORITY = 2

/** Tasks.tsx `STATUS_TONE`. */
val TASK_STATUS_TONE = mapOf("open" to "muted", "in-progress" to "info", "done" to "ok", "closed" to "muted")

fun taskStatusTitle(id: String) = TASK_COLUMNS.firstOrNull { it.first == id }?.second ?: id

data class TaskLink(
    val adapter: String,
    val number: Long,
    val url: String,
)

/** A `TaskCard` off the wire. */
data class TaskCard(
    val id: String,
    val title: String,
    val status: String,
    val parent: String? = null,
    val priority: Int = DEFAULT_TASK_PRIORITY,
    val assignee: String? = null,
    val blockedBy: List<String> = emptyList(),
    val conflictsWith: List<String> = emptyList(),
    val parallel: Boolean = false,
    val external: TaskLink? = null,
)

/** A `TaskBoard` off the wire. */
data class TaskBoard(
    val adapter: String,
    val tasks: List<TaskCard>,
    val hidden: Int,
)

fun taskCard(o: JsonObject) =
    TaskCard(
        id = o.str("id").orEmpty(),
        title = o.str("title").orEmpty(),
        status = o.str("status") ?: "open",
        parent = o.str("parent"),
        priority = o.num("priority")?.toInt() ?: DEFAULT_TASK_PRIORITY,
        assignee = o.str("assignee"),
        blockedBy = o.strings("blocked_by"),
        conflictsWith = o.strings("conflicts_with"),
        parallel = o.flag("parallel"),
        // Tasks.tsx `Issue`: the server only sends http(s) links; checked again, it becomes an href.
        external =
            o.obj("external")?.let { x ->
                x.str("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let {
                    TaskLink(x.str("adapter").orEmpty(), x.num("number")?.toLong() ?: 0, it)
                }
            },
    )

fun taskBoard(o: JsonObject) = TaskBoard(o.str("adapter").orEmpty(), o.objects("tasks").map(::taskCard), o.num("hidden")?.toInt() ?: 0)

/** Tasks.tsx `Marker`. */
data class TaskMarker(
    val key: String,
    val tone: String,
    val label: String,
)

/** Tasks.tsx `markers`: what a card says besides its title. A plain task shows none. */
fun taskMarkers(t: TaskCard): List<TaskMarker> =
    buildList {
        if (t.priority != DEFAULT_TASK_PRIORITY) {
            val tone =
                when (t.priority) {
                    0 -> "fail"
                    1 -> "warn"
                    else -> "muted"
                }
            add(TaskMarker("priority", tone, "P${t.priority}"))
        }
        if (t.blockedBy.isNotEmpty()) add(TaskMarker("needs", "info", "needs ${t.blockedBy.joinToString(", ")}"))
        if (t.conflictsWith.isNotEmpty()) add(TaskMarker("conflicts", "warn", "conflicts ${t.conflictsWith.joinToString(", ")}"))
        if (t.parallel) add(TaskMarker("parallel", "ok", "parallel"))
    }

/** Tasks.tsx `cardsIn`: the cards in [column] matching [query], most urgent first (ids already in order). */
fun cardsIn(
    tasks: List<TaskCard>,
    column: String,
    query: String,
): List<TaskCard> {
    val q = query.trim().lowercase()
    return tasks
        .filter { t ->
            t.status == column &&
                (q.isEmpty() || listOfNotNull(t.id, t.title, t.assignee, t.parent).any { it.lowercase().contains(q) })
        }.sortedBy { it.priority }
}
