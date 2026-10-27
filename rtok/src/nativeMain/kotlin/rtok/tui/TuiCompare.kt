// Port of src/tui/compare.rs (T485): the Graph tab's compare view, the terminal counterpart of the web page's Compare
// mode. It computes nothing: the answer is GraphDiffs.pageOf, the function the page's `diff` request and
// `rtok graph diff --json` run, so the counts, the callers and the row cap are theirs. This is the typed input and
// the rendering of the report with the web panel's wording and marks (`+ − ~ →`), so colour is never the only cue.
package rtok.tui

import rtok.plugins.graph.DiffDef
import rtok.plugins.graph.DiffMove
import rtok.plugins.graph.DiffProject
import rtok.plugins.graph.DiffReport
import tui.Color
import tui.Key
import tui.Line
import tui.Span
import tui.Style

/** The web panel's marks (`CHANGE_MARK` in compare.ts). */
private const val ADDED = "+"
private const val REMOVED = "−"
private const val CHANGED = "~"
private const val MOVED = "→"
private const val CALLERS_SHOWN = 5
private val OK = Color.Green
private val ERR = Color.Red
private val WARN_C = Color.Yellow
private val ACCENT_C = Color.Cyan

class TuiCompare :
    TuiPanel<Unit>(
        "compare",
        "type ref, PROJECT:REF or export path · Enter run compare · ↑/↓ PgUp/PgDn scroll · c compare again · Esc close",
    ) {
    private var input = ""

    /** The registry id handed to the diff, and the name the panel calls it by. */
    private var project = "" to ""

    override fun open(
        k: Key,
        target: Pair<Int, String>?,
    ): PanelStage<Unit>? {
        if (k != Key.Char("c")) return null
        input = ""
        return if (target == null) {
            PanelStage.Shown(errorLines("no project to compare"))
        } else {
            project = target.first.toString() to target.second
            PanelStage.Prompt(Unit)
        }
    }

    override fun prompt(
        ask: Unit,
        k: Key,
        actions: TuiActions,
    ): PanelStage<Unit>? =
        when (k) {
            Key.Esc -> {
                PanelStage.Closed
            }

            Key.Enter -> {
                shown { compareLines(actions.compare(project.first, input)) }
            }

            else -> {
                input = edit(input, k)
                null
            }
        }

    override fun promptLines(ask: Unit): List<Line> =
        listOf(
            listOf(Span("compare ${project.second} with: $input▏")),
            listOf(Span("a ref, refs or PROJECT:REF separated by spaces, or the path of a saved export; empty is HEAD", MUTED)),
        )
}

private fun mark(
    m: String,
    c: Color,
) = Span("$m ", Style(fg = c, bold = true))

private fun head(
    title: String,
    n: Int,
): List<Line> = if (n > 0) listOf(listOf(Span("$title ($n)", Style(bold = true)))) else emptyList()

private fun row(
    m: String,
    c: Color,
    text: String,
): Line = listOf(Span("  "), mark(m, c), Span(text))

private fun at(d: DiffDef) = "${d.path}:${d.line}"

private fun MutableList<Line>.defs(
    title: String,
    rows: List<DiffDef>,
    m: String,
    c: Color,
) {
    addAll(head(title, rows.size))
    for (d in rows) {
        val how =
            when (d.signatureChanged) {
                true -> "  [signature]"
                false -> "  [body]"
                null -> ""
            }
        add(row(m, c, "${d.name}  ${d.kind}$how  ${at(d)}"))
        val callers = d.callers.orEmpty()
        callers.take(CALLERS_SHOWN).forEach { add(listOf(Span("      called by $it", MUTED))) }
        if (callers.size > CALLERS_SHOWN) add(listOf(Span("      +${callers.size - CALLERS_SHOWN} more callers", MUTED)))
    }
}

private fun MutableList<Line>.moves(
    title: String,
    rows: List<DiffMove>,
    m: String,
    c: Color,
) {
    addAll(head(title, rows.size))
    for (r in rows) {
        val name = if (r.from.name == r.to.name) r.to.name else "${r.from.name} → ${r.to.name}"
        val place = if (r.from.path == r.to.path) at(r.to) else "${r.from.path} → ${r.to.path}"
        add(row(m, c, "$name  $place"))
    }
}

/** The counts a reader checks against `rtok graph diff`: renamed counts as changed, as in the web panel. */
private fun counts(d: DiffProject): Line =
    listOf(
        Triple(ADDED, OK, d.added.size to "added"),
        Triple(REMOVED, ERR, d.removed.size to "removed"),
        Triple(CHANGED, WARN_C, (d.changed.size + d.renamed.size) to "changed"),
        Triple(MOVED, ACCENT_C, d.moved.size to "moved"),
    ).flatMap { (m, c, nw) -> listOf(mark(m, c), Span("${nw.first} ${nw.second}   ")) }

private fun MutableList<Line>.project(
    r: DiffReport,
    d: DiffProject,
) {
    add(listOf(Span("${d.project} vs ${r.from} → ${r.to}", Style(bold = true))))
    add(counts(d))
    val total =
        d.added.size + d.removed.size + d.changed.size + d.renamed.size + d.moved.size + d.edgesAdded.size + d.edgesRemoved.size +
            d.notAnalysed.size
    if (total == 0) add(listOf(Span("No graph changes.", MUTED)))
    defs("changed", d.changed, CHANGED, WARN_C)
    defs("removed", d.removed, REMOVED, ERR)
    moves("renamed", d.renamed, CHANGED, WARN_C)
    moves("moved", d.moved, MOVED, ACCENT_C)
    defs("added", d.added, ADDED, OK)
    for ((title, mc, rows) in listOf(Triple("edges added", ADDED to OK, d.edgesAdded), Triple("edges removed", REMOVED to ERR, d.edgesRemoved))) {
        addAll(head(title, rows.size))
        rows.forEach { e -> add(row(mc.first, mc.second, "${e.scope.ifEmpty { e.path }} → ${e.name}")) }
    }
    addAll(head("changed, not analysed", d.notAnalysed.size))
    d.notAnalysed.forEach { add(row("!", WARN_C, "${it.path}  (${it.reason})")) }
    if (d.more > 0) add(listOf(Span("${d.more} more rows are not shown; run `rtok graph diff` for all of them.", Style(fg = WARN_C))))
}

/** compare.rs `lines`: the report as the panel shows it. */
fun compareLines(r: DiffReport): List<Line> {
    val out = mutableListOf<Line>()
    for (d in r.projects) {
        out.project(r, d)
        out.add(emptyList())
    }
    if (r.projects.isEmpty()) out.add(listOf(Span("Nothing to compare for this project.", MUTED)))
    for ((title, mc, rows) in listOf(Triple("links added", ADDED to OK, r.linksAdded), Triple("links removed", REMOVED to ERR, r.linksRemoved))) {
        out.addAll(head(title, rows.size))
        rows.forEach { l -> out.add(row(mc.first, mc.second, "${l.from} → ${l.to} (${l.kind})")) }
    }
    r.notes
        .trim()
        .lines()
        .filter { it.isNotEmpty() }
        .forEach { out.add(listOf(Span(it, MUTED))) }
    return out
}
