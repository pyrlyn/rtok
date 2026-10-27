// Port of web/src/pages/text.ts worktrees (parseWorktrees, matchesWorktree) and Worktrees.tsx baseName.
package rtok.web.ui

/** text.ts `WORKTREE_STATES`. */
val WORKTREE_STATES = listOf("main", "dirty", "unmerged", "merged", "stale", "orphan")

data class WorktreeRow(
    val path: String = "-",
    val branch: String = "-",
    val owner: String = "-",
    val agent: String = "-",
    val agentState: String = "-",
    val state: String = "-",
    val seen: String = "-",
    val modified: String = "-",
    val source: String = "-",
    val cache: String = "-",
)

/** Either a one-line message ("reading worktrees…", "not a git repository") or the table. */
data class WorktreesView(
    val message: String? = null,
    val rows: List<WorktreeRow> = emptyList(),
    val total: String = "",
    val note: String = "",
)

private val CELL_GAP = Regex("\\s{2,}")

/** text.ts `cells`: columns are separated by two or more spaces. */
fun cells(line: String) = line.trim().split(CELL_GAP)

private fun WorktreeRow.with(
    header: String,
    v: String,
): WorktreeRow =
    when (header) {
        "path" -> copy(path = v)
        "branch" -> copy(branch = v)
        "owner" -> copy(owner = v)
        "agent" -> copy(agent = v)
        "agent state" -> copy(agentState = v)
        "state" -> copy(state = v)
        "seen" -> copy(seen = v)
        "modified" -> copy(modified = v)
        "source" -> copy(source = v)
        "cache" -> copy(cache = v)
        else -> this
    }

private val TOTAL = Regex(" worktrees: ")
private val NOTE = Regex("\\((.*)\\)")
private val NOTE_TAIL = Regex(" \\(.*$")

/** text.ts `parseWorktrees`. */
fun parseWorktrees(text: String): WorktreesView {
    val all = lines(text).filter { it.isNotBlank() }
    val first = all.firstOrNull().orEmpty()
    if (first.isEmpty() || first.startsWith("reading worktrees") || first.startsWith("not a git repository")) {
        return WorktreesView(message = first.trim().ifEmpty { "no worktrees" })
    }
    val header = cells(first)
    val totalLine = all.firstOrNull { TOTAL.containsMatchIn(it) }.orEmpty()
    val rows =
        all.drop(1).filter { it != totalLine }.map { l ->
            val c = cells(l)
            header.foldIndexed(WorktreeRow()) { i, row, h -> row.with(h, c.getOrNull(i) ?: "-") }
        }
    return WorktreesView(
        rows = rows,
        total = totalLine.replace(NOTE_TAIL, ""),
        note =
            NOTE
                .find(totalLine)
                ?.groupValues
                ?.get(1)
                .orEmpty(),
    )
}

/** text.ts `matchesWorktree`. */
fun matchesWorktree(
    r: WorktreeRow,
    state: String,
    query: String,
): Boolean {
    val q = query.trim().lowercase()
    return (state == "all" || r.state == state) &&
        (q.isEmpty() || "${r.path} ${r.branch} ${r.owner}".lowercase().contains(q))
}

/** Worktrees.tsx `baseName`: the directory name tells worktrees apart. */
fun baseName(path: String) = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }
