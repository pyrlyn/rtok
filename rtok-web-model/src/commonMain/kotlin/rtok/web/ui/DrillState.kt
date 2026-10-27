// Port of web/src/pages/graph3d/drillState.ts: the drill-down state carried in the URL, breadcrumbs, editor links.
package rtok.web.ui

/** A drill focus: one symbol. */
data class DrillFocus(
    val path: String,
    val name: String,
)

/** Where the drill-down is (T329.22). */
data class DrillState(
    val project: String,
    val expand: List<String> = emptyList(),
    val focus: DrillFocus? = null,
    val depth: Int = DEPTH_DEFAULT,
)

const val DEPTH_DEFAULT = 1
const val DEPTH_MAX = 4
private const val MAX_EXPAND = 50
private const val MAX_TEXT = 500
private val PROJECT_ID = Regex("""^\d{1,9}$""")

private fun text(v: String?) = v?.take(MAX_TEXT)?.takeIf { it.isNotEmpty() }

/** Untrusted query params in (`p`, `x` repeated, `fp`, `fn`, `d`); null unless they name a project. */
fun parseDrill(raw: Map<String, List<String>>): DrillState? {
    val project = text(raw["p"]?.firstOrNull())?.takeIf { PROJECT_ID.matches(it) } ?: return null
    val path = text(raw["fp"]?.firstOrNull())
    val name = text(raw["fn"]?.firstOrNull())
    val depth = raw["d"]?.firstOrNull()?.toDoubleOrNull()?.toInt() ?: DEPTH_DEFAULT
    return DrillState(
        project,
        (raw["x"] ?: emptyList()).mapNotNull { text(it) }.distinct().take(MAX_EXPAND),
        if (path != null && name != null) DrillFocus(path, name) else null,
        if (depth in 1..DEPTH_MAX) depth else DEPTH_DEFAULT,
    )
}

/** The state as query params, defaults left out. */
fun drillSearch(s: DrillState?): List<Pair<String, String>> {
    if (s == null) return emptyList()
    val out = mutableListOf("p" to s.project)
    s.expand.forEach { out += "x" to it }
    s.focus?.let {
        out += "fp" to it.path
        out += "fn" to it.name
        if (s.depth != DEPTH_DEFAULT) out += "d" to s.depth.toString()
    }
    return out
}

/** One breadcrumb step; [to] is null for "here", [overview] for the level-1 list. */
data class Crumb(
    val label: String,
    val to: DrillState? = null,
    val overview: Boolean = false,
)

/** `All projects / rtok / src/x.rs`. */
fun breadcrumb(
    s: DrillState,
    name: String,
): List<Crumb> {
    val here = s.focus?.path ?: s.expand.lastOrNull()
    return listOfNotNull(
        Crumb("All projects", overview = true),
        if (here != null) Crumb(name, s.copy(expand = emptyList(), focus = null)) else Crumb(name),
        here?.let { Crumb(it) },
    )
}

/** Toggles a file in or out of the expansion; a focus ends here. */
fun toggleFile(
    s: DrillState,
    path: String,
): DrillState = s.copy(focus = null, expand = if (path in s.expand) s.expand - path else s.expand + path)

/** A project's drill at its defaults. */
fun openProject(id: Long) = DrillState(id.toString())

/** Another project's symbol, focused. */
fun openSymbol(
    project: Long,
    focus: DrillFocus,
) = openProject(project).copy(focus = focus)

/** `vscode://file/<root>/<path>:<line>`, each segment encoded except a drive colon. */
fun editorLink(
    root: String,
    path: String,
    line: Long,
    encode: (String) -> String,
): String {
    val parts =
        "$root/$path"
            .replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() }
            .map { encode(it).replace("%3A", ":").replace("%3a", ":") }
    return "vscode://file/${parts.joinToString("/")}" + if (line > 0) ":$line" else ""
}
