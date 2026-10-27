// Port of web/src/pages.ts: the page list, mirroring `model::pages()` (page id and the snapshot field it reads).
// The sidebar and routes are built from this one list.
package rtok.web.ui

/** One screen of the UI: its route id and the snapshot field it reads. */
data class Page(
    val id: String,
    val field: String,
)

val PAGES: List<Page> =
    listOf(
        Page("overview", "usage"),
        Page("plugins", "plugins"),
        Page("calls", "calls"),
        Page("sessions", "sessions"),
        Page("doctor", "doctor"),
        Page("logs", "logs"),
        Page("skills", "skills"),
        Page("stats", "stats"),
        Page("graph", "graph"),
        Page("hosts", "hosts"),
        Page("config", "config"),
        Page("services", "services"),
        Page("worktrees", "worktrees"),
        Page("usage", "agent_usage"),
        Page("tasks", "tasks"),
    )

/** The page for a location path: `/` is Overview (router.tsx index redirect), an unknown path is null. */
fun pageFor(path: String): Page? {
    val id = path.trim('/').substringBefore('/').ifEmpty { "overview" }
    return PAGES.firstOrNull { it.id == id }
}
