// The drill-down's compare panel (ref or saved export, change counts) and JSON/SVG export with the imported view.
// PNG export waits on a decision (docs/web.md). Files are picked and downloaded through small browser interop.
// Compose UI: @Composable functions are PascalCase and layout sizes are literal dp/sp values.
@file:Suppress("FunctionNaming", "MagicNumber")
@file:OptIn(ExperimentalWasmJsInterop::class)

package rtok.web.compose

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonObject
import rtok.web.ui.CHANGE_TONE
import rtok.web.ui.DrillState
import rtok.web.ui.diffCounts
import rtok.web.ui.diffExportRequest
import rtok.web.ui.diffRequest
import rtok.web.ui.exportRequest
import rtok.web.ui.fmt
import rtok.web.ui.importRequest
import rtok.web.ui.objects
import rtok.web.ui.str

/** Opens the browser's file picker for JSON and hands back the name and text of the chosen file. */
@JsFun(
    """(cb) => { const i = document.createElement('input'); i.type = 'file'; i.accept = '.json,application/json';
    i.onchange = () => { const f = i.files[0]; if (f) f.text().then(t => cb(f.name, t)); }; i.click(); }""",
)
private external fun pickJson(cb: (String, String) -> Unit)

/** Saves a base64 file the server built (`export` reply). */
@JsFun("(mime, data, name) => { const a = document.createElement('a'); a.href = 'data:' + mime + ';base64,' + data; a.download = name; a.click(); }")
private external fun download(
    mime: String,
    data: String,
    name: String,
)

/** Registers the `export` reply: the server's file is saved at once. */
fun AppState.installExport() = on("export") { f -> f.body["file"]?.let { it as? JsonObject }?.let { download(it.str("mime") ?: "", it.str("data") ?: "", it.str("name") ?: "graph") } }

/** Compare and export below the drill scene. */
@Composable
fun DrillPanels(
    c: DrillController,
    s: DrillState,
    dp: JsonObject?,
) {
    Compare(c, s, dp)
    Export(c, s)
}

@Composable
private fun Compare(
    c: DrillController,
    s: DrillState,
    dp: JsonObject?,
) = Panel("compare", c.diff?.let { "${it.str("from")} → ${it.str("to")}" }) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicTextField(
            c.compareRef,
            { c.compareRef = it },
            Modifier.width(160.dp).border(1.dp, p.border, RoundedCornerShape(6.dp)).padding(6.dp),
            singleLine = true,
            textStyle = TextStyle(color = p.fg, fontSize = 13.sp),
            cursorBrush = SolidColor(p.accent),
        )
        Action("compare") { c.send(diffRequest(s.project, c.compareRef.split(' ').filter { it.isNotEmpty() })) }
        Action("compare with export…") { pickJson { name, text -> c.send(diffExportRequest(s.project, name, text)) } }
        if (c.diff != null) Action("clear") { c.diff = null }
    }
    if (c.diff != null && dp == null) Muted("No changes in this project.")
    dp?.let { d -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { diffCounts(d).filter { it.second > 0 }.forEach { (k, n) -> Pill(CHANGE_TONE[k] ?: "info", "$k $n") } } }
    c.diff
        ?.str("notes")
        ?.takeIf { it.isNotEmpty() }
        ?.let { Muted(it) }
}

@Composable
private fun Export(
    c: DrillController,
    s: DrillState,
) = Panel("export") {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("overview", "symbols").forEach { level ->
            listOf("json", "svg").forEach { f ->
                Action("$level .$f") { c.send(exportRequest(s.project, level, f, s.focus?.name, s.focus?.let { s.depth })) }
            }
        }
        Action("import export…") { pickJson { name, text -> c.send(importRequest(name, text)) } }
    }
    Muted("PNG export is not available in this build.")
    c.imported?.let { (name, ex) ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt(name, bold = true)
            Muted(ex.str("schema") ?: "")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("projects", "links", "nodes", "edges").forEach { k -> Kpi(k, fmt(ex.objects(k).size.toDouble()), "") }
        }
        ex.objects("projects").forEach { pr ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(pr.str("name") ?: "", bold = true)
                Muted(pr.str("root") ?: "")
            }
        }
    }
}
