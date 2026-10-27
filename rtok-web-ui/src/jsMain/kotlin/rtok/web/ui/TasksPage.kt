// Port of web/src/pages/Tasks.tsx (T441.13.7): the project's `rtok task list --all` as a board (four status columns)
// or a list with a detail panel. Read-only. The view is kept in localStorage (`rtok-tasks-view`), the search in `?q=`
// and the list's selected task in `?sel=`.
package rtok.web.ui

import kotlinx.browser.localStorage
import kotlinx.css.CssBuilder
import kotlinx.html.FlowContent
import kotlinx.html.UL
import kotlinx.html.a
import kotlinx.html.b
import kotlinx.html.button
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.h2
import kotlinx.html.header
import kotlinx.html.js.onClickFunction
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.ul
import kotlinx.serialization.json.JsonObject

private const val VIEW_KEY = "rtok-tasks-view"
private val VIEWS = listOf("board", "list")

private fun storedView() = runCatching { localStorage.getItem(VIEW_KEY) }.getOrNull()?.takeIf { it in VIEWS } ?: VIEWS.first()

private fun FlowContent.statusPill(status: String) = pill(TASK_STATUS_TONE[status] ?: "muted", taskStatusTitle(status))

/** Tasks.tsx `Issue`: the GitHub or GitLab issue; only http(s) links get here. */
private fun FlowContent.issue(t: TaskCard) {
    val x = t.external ?: return
    a(x.url, target = "_blank") {
        attributes["rel"] = "noreferrer noopener"
        +"${x.adapter} #${x.number}"
    }
}

private fun UL.card(t: TaskCard) {
    li("card task") {
        div("task-head") {
            b("mono accent") { +t.id }
            span { +t.title }
        }
        if (t.parent != null || t.assignee != null || t.external != null) {
            p("muted small task-meta") {
                t.parent?.let { span { +"in $it" } }
                t.assignee?.let { span { +it } }
                issue(t)
            }
        }
        val marks = taskMarkers(t)
        if (marks.isNotEmpty()) {
            div("task-marks") {
                attributes["role"] = "group"
                attributes["aria-label"] = "markers"
                marks.forEach { pill(it.tone, it.label) }
            }
        }
    }
}

private fun FlowContent.columns(
    board: TaskBoard,
    query: String,
) {
    div("task-columns") {
        TASK_COLUMNS.forEach { (id, title) ->
            val cards = cardsIn(board.tasks, id, query)
            section("task-column") {
                attributes["aria-label"] = title
                header {
                    h2 { +title }
                    pill(TASK_STATUS_TONE[id] ?: "muted", cards.size.toString())
                }
                if (cards.isEmpty()) p("muted small") { +"none" } else ul { cards.forEach { card(it) } }
            }
        }
    }
}

private fun FlowContent.list(
    app: App,
    board: TaskBoard,
    query: String,
) {
    val rows = TASK_COLUMNS.flatMap { (id, _) -> cardsIn(board.tasks, id, query) }
    val selected = rows.firstOrNull { it.id == param("sel", "") } ?: rows.firstOrNull()
    div("split") {
        panel("tasks", "select a task for its details") {
            dataTable(
                "tasks",
                rows,
                listOf(
                    Column("id") { t ->
                        button(classes = "link") {
                            onClickFunction = { setParam(app, "sel", t.id, "") }
                            b("mono accent") { +t.id }
                        }
                    },
                    Column("title") { t -> +t.title },
                    Column("status") { t -> statusPill(t.status) },
                    Column("prio", right = true) { t -> +"P${t.priority}" },
                    Column("agent") { t -> span("muted") { +(t.assignee ?: "-") } },
                ),
                "No task matches: clear the filter.",
            )
        }
        selected?.let { detail(it) }
    }
}

private fun FlowContent.detail(t: TaskCard) {
    panel(t.id, taskStatusTitle(t.status)) {
        p { +t.title }
        kv(
            listOf(
                "parent" to { +(t.parent ?: "none") },
                "priority" to { +"P${t.priority}" },
                "agent" to { +(t.assignee ?: "none") },
                "needs" to { +t.blockedBy.joinToString(", ").ifEmpty { "none" } },
                "conflicts" to { +t.conflictsWith.joinToString(", ").ifEmpty { "none" } },
                "parallel" to { +(if (t.parallel) "yes" else "no") },
                "issue" to { if (t.external != null) issue(t) else +"none" },
            ),
        )
    }
}

private fun FlowContent.viewToggles(
    app: App,
    view: String,
) {
    div("toggles") {
        attributes["role"] = "group"
        attributes["aria-label"] = "View"
        VIEWS.forEach { v ->
            button(classes = if (v == view) "on" else null) {
                attributes["aria-pressed"] = (v == view).toString()
                onClickFunction = {
                    runCatching { localStorage.setItem(VIEW_KEY, v) }
                    app.refresh()
                }
                +v
            }
        }
    }
}

private fun FlowContent.tasked(
    app: App,
    board: TaskBoard,
) {
    val view = storedView()
    val query = param("q", "")
    val shown = TASK_COLUMNS.sumOf { (id, _) -> cardsIn(board.tasks, id, query).size }
    div("toolbar") {
        searchBox(app, "tasks-q", "id, title, agent")
        viewToggles(app, view)
        span("muted small") { +"$shown of ${board.tasks.size} · ${board.adapter}" }
    }
    when {
        board.tasks.isEmpty() -> {
            panel("tasks") {
                p("muted") {
                    +"No tasks yet. Add one with "
                    code { +"rtok task create \"<title>\"" }
                    +"."
                }
            }
        }

        view == "board" -> {
            columns(board, query)
        }

        else -> {
            list(app, board, query)
        }
    }
    if (board.hidden > 0) {
        p("muted small") { +"${board.hidden} older done or closed tasks are not shown; `rtok task list --all` has them." }
    }
}

/** The Tasks page (Tasks.tsx `TasksBody`): the empty state, the server's sentence, or the board. */
val TasksPage =
    PageView { out, snap, app ->
        val page = snap["tasks"] as? JsonObject
        val board = page?.obj("board")
        when {
            page == null -> {
                pendingPage("tasks", "rtok task list --all").render(out, snap, app)
            }

            page.str("state") == "unconfigured" -> {
                out.panel("No tasks configured") {
                    p("muted") {
                        +"This project has no [tasks] section. Run "
                        code { +"rtok task init" }
                        +" in it to start one."
                    }
                }
            }

            board == null -> {
                out.panel("tasks", "rtok task list --all") {
                    p("muted small") {
                        attributes["role"] = if (page.str("state") == "reading") "status" else "alert"
                        +page.str("text").orEmpty().trim()
                    }
                }
            }

            else -> {
                out.tasked(app, taskBoard(board))
            }
        }
    }

/** Tasks.tsx: the board's four columns and its cards. */
internal fun CssBuilder.taskStyles() {
    rule(".task-columns") {
        put("display", "grid")
        put("grid-template-columns", "repeat(auto-fit, minmax(14rem, 1fr))")
        put("gap", "12px")
        put("align-items", "start")
    }
    rule(".task-column") {
        put("display", "flex")
        put("flex-direction", "column")
        put("gap", "8px")
        put("padding", "10px")
        put("border", "1px solid var(--border)")
        put("border-radius", "8px")
        put("min-width", "0")
    }
    rule(".task-column header") {
        put("display", "flex")
        put("gap", "8px")
        put("align-items", "center")
    }
    rule(".task-column ul") {
        put("display", "flex")
        put("flex-direction", "column")
        put("gap", "8px")
        put("list-style", "none")
        put("margin", "0")
        put("padding", "0")
    }
    rule(".task") {
        put("display", "flex")
        put("flex-direction", "column")
        put("gap", "6px")
        put("padding", "10px")
    }
    rule(".task-head") {
        put("display", "flex")
        put("gap", "8px")
        put("align-items", "baseline")
        put("overflow-wrap", "anywhere")
    }
    rule(".task-meta, .task-marks") {
        put("display", "flex")
        put("flex-wrap", "wrap")
        put("gap", "4px 12px")
    }
}
