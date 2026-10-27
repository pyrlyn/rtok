// The Tasks page in Compose: `rtok task list --all` as a board (four status columns) or a list with a detail panel.
// Read-only. The view is kept in localStorage (`rtok-tasks-view`), the search in `?q=`, the selection in `?sel=`.
// Compose UI: @Composable functions are PascalCase and layout sizes are literal dp/sp values.
@file:Suppress("FunctionNaming", "MagicNumber")

package rtok.web.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import kotlinx.browser.localStorage
import kotlinx.serialization.json.JsonObject
import rtok.web.ui.TASK_COLUMNS
import rtok.web.ui.TASK_STATUS_TONE
import rtok.web.ui.TaskBoard
import rtok.web.ui.TaskCard
import rtok.web.ui.cardsIn
import rtok.web.ui.obj
import rtok.web.ui.str
import rtok.web.ui.taskBoard
import rtok.web.ui.taskMarkers
import rtok.web.ui.taskStatusTitle

private const val VIEW_KEY = "rtok-tasks-view"
private val TASK_VIEWS = listOf("board", "list")

@Composable
private fun StatusPill(status: String) = Pill(TASK_STATUS_TONE[status] ?: "muted", taskStatusTitle(status))

/** The GitHub or GitLab issue; only http(s) links reach the model. */
@Composable
private fun Issue(t: TaskCard) {
    val x = t.external ?: return
    val uri = LocalUriHandler.current
    Box(Modifier.clickable { uri.openUri(x.url) }) { Txt("${x.adapter} #${x.number}", color = LocalPalette.current.accent) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Card(t: TaskCard) =
    Panel(t.id, t.title, Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            t.parent?.let { Muted("in $it") }
            t.assignee?.let { Muted(it) }
            Issue(t)
        }
        val marks = taskMarkers(t)
        if (marks.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { marks.forEach { Pill(it.tone, it.label) } }
    }

@Composable
private fun Columns(
    board: TaskBoard,
    query: String,
) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    TASK_COLUMNS.forEach { (id, title) ->
        val cards = cardsIn(board.tasks, id, query)
        Panel(title, "${cards.size}", Modifier.weight(1f)) {
            if (cards.isEmpty()) Muted("none") else cards.forEach { Card(it) }
        }
    }
}

@Composable
private fun TaskList(
    app: AppState,
    board: TaskBoard,
    query: String,
) {
    val rows = TASK_COLUMNS.flatMap { (id, _) -> cardsIn(board.tasks, id, query) }
    val selected = rows.firstOrNull { it.id == app.param("sel", "") } ?: rows.firstOrNull()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel("tasks", "select a task for its details", Modifier.weight(1f)) {
            Table(
                rows,
                listOf(
                    Col<TaskCard>("id") { t -> Box(Modifier.clickable { app.setParam("sel", t.id, "") }) { Txt(t.id, color = LocalPalette.current.accent, bold = true) } },
                    Col("title", 3f) { Txt(it.title) },
                    Col("status") { StatusPill(it.status) },
                    Col("prio", 0.6f) { Txt("P${it.priority}") },
                    Col("agent") { Muted(it.assignee ?: "-") },
                ),
                "No task matches: clear the filter.",
            )
        }
        selected?.let { t ->
            Panel(t.id, taskStatusTitle(t.status), Modifier.weight(1f)) {
                Txt(t.title)
                listOf(
                    "parent" to (t.parent ?: "none"),
                    "priority" to "P${t.priority}",
                    "agent" to (t.assignee ?: "none"),
                    "needs" to t.blockedBy.joinToString(", ").ifEmpty { "none" },
                    "conflicts" to t.conflictsWith.joinToString(", ").ifEmpty { "none" },
                    "parallel" to if (t.parallel) "yes" else "no",
                ).forEach { (k, v) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Muted(k)
                        Txt(v)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Muted("issue")
                    if (t.external != null) Issue(t) else Txt("none")
                }
            }
        }
    }
}

@Composable
private fun Tasked(
    app: AppState,
    board: TaskBoard,
) {
    var view by remember { mutableStateOf(runCatching { localStorage.getItem(VIEW_KEY) }.getOrNull()?.takeIf { it in TASK_VIEWS } ?: TASK_VIEWS.first()) }
    val query = app.param("q", "")
    val shown = TASK_COLUMNS.sumOf { (id, _) -> cardsIn(board.tasks, id, query).size }
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SearchBox(app, "id, title, agent")
        TASK_VIEWS.forEach { v ->
            Box(
                Modifier.clickable {
                    view = v
                    runCatching { localStorage.setItem(VIEW_KEY, v) }
                },
            ) { Txt(v, color = if (v == view) p.accent else p.muted) }
        }
        Muted("$shown of ${board.tasks.size} · ${board.adapter}")
    }
    when {
        board.tasks.isEmpty() -> Panel("tasks") { Muted("No tasks yet. Add one with `rtok task create \"<title>\"`.") }
        view == "board" -> Columns(board, query)
        else -> TaskList(app, board, query)
    }
    if (board.hidden > 0) Muted("${board.hidden} older done or closed tasks are not shown; `rtok task list --all` has them.")
}

/** The Tasks page: the empty state, the server's sentence, or the board. */
val TasksPage =
    PageView { snap, app ->
        val page = snap["tasks"] as? JsonObject
        val board = page?.obj("board")
        when {
            page == null -> Pending("tasks", "rtok task list --all")
            page.str("state") == "unconfigured" -> Panel("No tasks configured") { Muted("This project has no [tasks] section. Run `rtok task init` in it to start one.") }
            board == null -> Panel("tasks", "rtok task list --all") { Muted(page.str("text").orEmpty().trim()) }
            else -> Tasked(app, taskBoard(board))
        }
    }
