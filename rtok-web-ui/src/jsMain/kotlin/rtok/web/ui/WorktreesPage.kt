// Port of web/src/pages/Worktrees.tsx: the `rtok worktree list` table with a state filter and a text search
// kept in the URL (`?state=`, `?q=`); gc and clean stay CLI-only verdicts.
package rtok.web.ui

import kotlinx.html.FlowContent
import kotlinx.html.b
import kotlinx.html.div
import kotlinx.html.p
import kotlinx.html.span

private val STATE_TONE =
    mapOf("main" to "info", "dirty" to "warn", "unmerged" to "warn", "merged" to "ok", "stale" to "muted", "orphan" to "fail")

private fun FlowContent.statePill(state: String) = pill(STATE_TONE[state] ?: "muted", state)

private fun FlowContent.worktreesBody(
    app: App,
    view: WorktreesView,
) {
    view.message?.let { m ->
        panel("worktrees") { p("muted") { +m } }
        return
    }
    val state = param("state", "all")
    val rows = view.rows.filter { matchesWorktree(it, state, param("q", "")) }
    div("toolbar") {
        searchBox(app, "worktrees-q", "path, branch, owner")
        toggles(app, "state", listOf("all") + WORKTREE_STATES)
        span("muted small") { +"${rows.size} shown" }
    }
    panel("worktrees", view.total) {
        dataTable(
            "worktrees",
            rows,
            listOf(
                Column("worktree") { r ->
                    b { +baseName(r.path) }
                    +" "
                    span("muted") {
                        +r.branch
                            .takeIf { it != "-" }
                            .orEmpty()
                            .ifEmpty { "no branch" }
                    }
                },
                Column("owner") { r -> +r.owner },
                Column("agent") { r -> +"${r.agent} ${r.agentState}".trim() },
                Column("state") { r -> statePill(r.state) },
                Column("seen") { r -> +r.seen },
                Column("modified") { r -> +r.modified },
                Column("source", right = true) { r -> +r.source },
                Column("cache", right = true) { r -> span("muted") { +r.cache } },
            ),
            "No worktree matches: clear the filter or pick another state.",
        )
        if (view.note.isNotEmpty()) p("muted small") { +view.note }
    }
}

/** The Worktrees page (Worktrees.tsx). */
val WorktreesPage =
    PageView { out, snap, app ->
        out.textPage(app, snap, "worktrees", "rtok worktree list", "`rtok worktree list`; gc and clean stay CLI-only verdicts") {
            worktreesBody(app, parseWorktrees(it))
        }
    }
