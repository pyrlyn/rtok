// The Worktrees page in Compose: the `rtok worktree list` table with `?state=` and `?q=` filters; gc and clean
// stay CLI-only verdicts.
// Compose UI: @Composable functions are PascalCase and layout sizes are literal dp/sp values.
@file:Suppress("FunctionNaming", "MagicNumber")

package rtok.web.compose

import rtok.web.ui.WORKTREE_STATES
import rtok.web.ui.WorktreeRow
import rtok.web.ui.baseName
import rtok.web.ui.matchesWorktree
import rtok.web.ui.parseWorktrees

private val STATE_TONE =
    mapOf("main" to "info", "dirty" to "warn", "unmerged" to "warn", "merged" to "ok", "stale" to "muted", "orphan" to "fail")

/** The Worktrees page. */
val WorktreesPage =
    PageView { snap, app ->
        TextPage(app, snap, "worktrees", "rtok worktree list", "`rtok worktree list`; gc and clean stay CLI-only verdicts") { text ->
            val view = parseWorktrees(text)
            val msg = view.message
            if (msg != null) {
                Panel("worktrees") { Muted(msg) }
                return@TextPage
            }
            val rows = view.rows.filter { matchesWorktree(it, app.param("state", "all"), app.param("q", "")) }
            Toolbar {
                SearchBox(app, "path, branch, owner")
                Toggles(app, "state", listOf("all") + WORKTREE_STATES)
                Muted("${rows.size} shown")
            }
            Panel("worktrees", view.total) {
                Table(
                    rows,
                    listOf(
                        Col<WorktreeRow>("worktree", 3f) { r ->
                            Txt("${baseName(r.path)}  ${r.branch.takeIf { it != "-" }.orEmpty().ifEmpty { "no branch" }}", bold = true)
                        },
                        Col("owner") { Txt(it.owner) },
                        Col("agent") { Txt("${it.agent} ${it.agentState}".trim()) },
                        Col("state") { Pill(STATE_TONE[it.state] ?: "muted", it.state) },
                        Col("seen") { Txt(it.seen) },
                        Col("modified") { Txt(it.modified) },
                        Col("source") { Txt(it.source) },
                        Col("cache") { Muted(it.cache) },
                    ),
                    "No worktree matches: clear the filter or pick another state.",
                )
                if (view.note.isNotEmpty()) Muted(view.note)
            }
        }
    }
