// Port of src/tui/exporter.rs (T329.41): the Graph tab's export form and saved-export view, the terminal counterpart
// of `rtok graph export`. The write is ExportView.write, the function the command's `--output` ends in, with its
// defaults (redaction on, scale 1, pretty JSON), so the file is the CLI's bytes. The view of a saved export opens the
// file with ExportView.read and writes nothing.
package rtok.tui

import rtok.plugins.graph.Export
import rtok.plugins.graph.ExportFormat
import rtok.plugins.graph.ExportLevel
import tui.Color
import tui.Key
import tui.Line
import tui.Span
import tui.Style

/** Rows of nodes the view lists; the file has them all. */
private const val NODES_SHOWN = 40
private const val NAME_PAD = 7
private const val PATH_FIELD = 3
private val FORMATS = listOf(ExportFormat.Json, ExportFormat.Svg, ExportFormat.Png)
private val LEVELS = listOf(ExportLevel.Overview, ExportLevel.Symbols)
private val WARN_STYLE = Style(fg = Color.Yellow)

/** exporter.rs `Form`: format, level, redact and the file to write; [at] is the field with the keys. */
data class ExportForm(
    val format: ExportFormat = ExportFormat.Json,
    val level: ExportLevel = ExportLevel.Overview,
    val redact: Boolean = true,
    val path: String = "",
    val at: Int = PATH_FIELD,
    val note: String = "",
) {
    /** Space, ←/→ on a choice: the next value, wrapping. */
    fun cycle(): ExportForm =
        when (at) {
            0 -> copy(format = FORMATS[(FORMATS.indexOf(format) + 1) % FORMATS.size])
            1 -> copy(level = LEVELS[(LEVELS.indexOf(level) + 1) % LEVELS.size])
            2 -> copy(redact = !redact)
            else -> this
        }

    fun lines(project: String): List<Line> {
        val rows =
            listOf(
                "format" to format.name.lowercase(),
                "level" to level.name.lowercase(),
                "redact" to if (redact) "on" else "off: home, user name and absolute paths are written",
                "file" to if (at == PATH_FIELD) "$path▏" else path,
            )
        return listOf(listOf(Span("export the graph of $project", Style(bold = true)))) +
            rows.mapIndexed { i, (name, value) -> listOf(Span("${if (i == at) "▸ " else "  "}${name.padEnd(NAME_PAD)}$value")) } +
            listOf(listOf(Span("svg and png draw the 200 best connected nodes. File and symbol names are included; source text never is.", MUTED))) +
            (if (note.isEmpty()) emptyList() else listOf(listOf(Span(note, WARN_STYLE))))
    }
}

/** What the panel asks for while a field has the keys: the form, or the path of a saved export to view. */
enum class ExportAsk { FORM, OPEN }

class TuiExport :
    TuiPanel<ExportAsk>(
        "export",
        "↑/↓ choose field · Space change format, level, redact · type file to write, or export to view · Enter write / view · Esc close",
    ) {
    var form = ExportForm()
        private set
    private var input = ""

    /** The registry id handed to the export, and the name the panel calls it by. */
    private var project = "" to ""

    override fun open(
        k: Key,
        target: Pair<Int, String>?,
    ): PanelStage<ExportAsk>? =
        when {
            k == Key.Char("e") && target == null -> {
                PanelStage.Shown(errorLines("no project to export"))
            }

            // The choices stay, as a second export of the same graph differs in one field; the name does not.
            k == Key.Char("e") && target != null -> {
                project = target.first.toString() to target.second
                form = form.copy(at = PATH_FIELD, path = "", note = "")
                PanelStage.Prompt(ExportAsk.FORM)
            }

            k == Key.Char("v") -> {
                input = ""
                PanelStage.Prompt(ExportAsk.OPEN)
            }

            else -> {
                null
            }
        }

    override fun prompt(
        ask: ExportAsk,
        k: Key,
        actions: TuiActions,
    ): PanelStage<ExportAsk>? =
        if (ask == ExportAsk.FORM) {
            formKey(k, actions)
        } else {
            when (k) {
                Key.Esc -> PanelStage.Closed
                Key.Enter -> input.trim().takeIf { it.isNotEmpty() }?.let { p -> shown { exportViewLines(actions.readExport(p), p) } }
                else -> null.also { input = edit(input, k) }
            }
        }

    private fun formKey(
        k: Key,
        actions: TuiActions,
    ): PanelStage<ExportAsk>? {
        val f = form
        val onPath = f.at == PATH_FIELD
        var next: PanelStage<ExportAsk>? = null
        when {
            k == Key.Esc -> next = PanelStage.Closed
            k == Key.Up -> form = f.copy(at = (f.at - 1).coerceAtLeast(0))
            k == Key.Down -> form = f.copy(at = (f.at + 1).coerceAtMost(PATH_FIELD))
            !onPath && (k == Key.Left || k == Key.Right || k == Key.Char(" ")) -> form = f.cycle()
            onPath && (k == Key.Backspace || k is Key.Char) -> form = f.copy(path = edit(f.path, k))
            k == Key.Enter && f.path.isBlank() -> form = f.copy(note = "name the file to write, for example graph.json")
            k == Key.Enter -> next = shown { exportWritten(actions.writeExport(project.first, f), f) }
        }
        return next
    }

    override fun promptLines(ask: ExportAsk): List<Line> =
        if (ask == ExportAsk.FORM) {
            form.lines(project.second)
        } else {
            listOf(
                listOf(Span("view the saved export: $input▏")),
                listOf(Span("the path of a JSON file `rtok graph export` wrote; it is only read", MUTED)),
            )
        }
}

private fun banner(
    glyph: String,
    msg: String,
    c: Color,
): Line = listOf(Span("$glyph ", Style(fg = c, bold = true)), Span(msg, Style(bold = true)))

/** exporter.rs `write`'s answer: the bytes written, and a warning when redaction was off. */
private fun exportWritten(
    n: Int,
    f: ExportForm,
): List<Line> =
    listOf(banner("✓", "wrote $n bytes to ${f.path.trim()}", Color.Green)) +
        if (f.redact) {
            emptyList()
        } else {
            listOf(listOf(Span("redaction was off: the file holds the home directory, the user name and absolute paths.", WARN_STYLE)))
        }

/** exporter.rs `lines`: the saved export, read-only. */
fun exportViewLines(
    e: Export,
    path: String,
): List<Line> {
    val name = { id: Int -> e.projects.find { it.id == id }?.name ?: id.toString() }
    val m = e.meta
    val yes = { b: Boolean -> if (b) "yes" else "no" }
    val head = { title: String, n: Int -> listOf(Span("$title ($n)", Style(bold = true))) }
    val focus = m.focus?.let { " of $it" }.orEmpty()
    val out =
        mutableListOf(
            banner("ℹ", "viewing export from $path (read-only)", Color.Cyan),
            listOf(
                Span(
                    "${m.level}$focus · scope ${m.scope.joinToString(", ")} · exported ${rtok.log.stamp(m.exportedAt)} UTC · " +
                        "rtok ${m.rtokVersion} · redacted ${yes(m.redacted)} · partial ${yes(m.partial)}",
                ),
            ),
            head("projects", e.projects.size),
        )
    e.projects.forEach { out += listOf(Span("  ${it.name}  ${it.backend}  ${it.health}  ${it.root}")) }
    out += head("links", e.links.size)
    e.links.forEach { out += listOf(Span("  ${name(it.from)} → ${name(it.to)} (${it.kind}, ${it.references} references)")) }
    out += head("nodes", e.nodes.size)
    e.nodes.take(NODES_SHOWN).forEach { out += listOf(Span("  ${it.kind}  ${it.name}  ${it.path}:${it.line}  (${name(it.project)})")) }
    val more = (e.nodes.size - NODES_SHOWN).coerceAtLeast(0)
    if (more > 0) out += listOf(Span("$more more nodes are in the file; `rtok graph export --from` draws them.", MUTED))
    out += head("edges", e.edges.size)
    m.notes.forEach { out += listOf(Span(it, MUTED)) }
    return out
}
