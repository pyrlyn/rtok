// Port of graph3d/ComparePanel.tsx, ExportMenu.tsx and ImportedView.tsx: the drill-down's compare panel, JSON/SVG
// export and the imported view. PNG export waits on a decision (docs/web.md).
package rtok.web.ui

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.html.FlowContent
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.b
import kotlinx.html.button
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.input
import kotlinx.html.js.onChangeFunction
import kotlinx.html.js.onClickFunction
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.pre
import kotlinx.html.span
import kotlinx.html.ul
import kotlinx.serialization.json.JsonObject
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.url.URL
import org.w3c.files.FileReader
import org.w3c.files.get

internal fun FlowContent.compare(
    s: DrillState,
    dp: JsonObject?,
) {
    panel("compare", GraphDrill.diff?.let { "${it.str("from")} → ${it.str("to")}" }) {
        div("toolbar") {
            input(InputType.text) {
                attributes["id"] = "compare-ref"
                value = GraphDrill.compareRef
                attributes["aria-label"] = "compare against ref"
                onChangeFunction = { e -> GraphDrill.compareRef = (e.target as HTMLInputElement).value }
            }
            button {
                onClickFunction = { GraphDrill.app?.send(diffRequest(s.project, GraphDrill.compareRef.split(' ').filter { it.isNotEmpty() })) }
                +"compare"
            }
            file("compare with export…") { name, text -> GraphDrill.app?.send(diffExportRequest(s.project, name, text)) }
            if (GraphDrill.diff != null) {
                button {
                    onClickFunction = {
                        GraphDrill.diff = null
                        GraphDrill.app?.refresh()
                    }
                    +"clear"
                }
            }
        }
        if (GraphDrill.diff != null && dp == null) p("muted") { +"No changes in this project." }
        dp?.let { d ->
            p { diffCounts(d).filter { it.second > 0 }.forEach { (k, n) -> pill(CHANGE_TONE[k] ?: "info", "$k $n") } }
        }
        GraphDrill.diff
            ?.str("notes")
            ?.takeIf { it.isNotEmpty() }
            ?.let { p("muted small") { +it } }
    }
}

internal fun FlowContent.exportImport(s: DrillState) {
    panel("export") {
        div("toolbar") {
            listOf("overview", "symbols").forEach { level ->
                listOf("json", "svg").forEach { fmt ->
                    button {
                        onClickFunction = { GraphDrill.app?.send(exportRequest(s.project, level, fmt, s.focus?.name, s.focus?.let { s.depth })) }
                        +"$level .$fmt"
                    }
                }
            }
            file("import export…") { name, text -> GraphDrill.app?.send(importRequest(name, text)) }
        }
        p("muted small") { +"PNG export is not available in this build." }
        GraphDrill.imported?.let { (name, ex) -> importedView(name, ex) }
    }
}

private fun FlowContent.importedView(
    name: String,
    ex: JsonObject,
) {
    p {
        b { +name }
        span("muted") { +" ${ex.str("schema") ?: ""}" }
    }
    div("grid kpis") {
        listOf("projects", "links", "nodes", "edges").forEach { k -> kpi(k, fmt(ex.objects(k).size.toDouble()), "") }
    }
    ul("plain") {
        attributes["aria-label"] = "imported projects"
        ex.objects("projects").forEach { pr ->
            li {
                b { +(pr.str("name") ?: "") }
                span("muted small") { +" ${pr.str("root") ?: ""}" }
            }
        }
    }
}

private fun FlowContent.file(
    label: String,
    onText: (String, String) -> Unit,
) {
    span("file") {
        +"$label "
        input(InputType.file) {
            attributes["aria-label"] = label
            accept = ".json,application/json"
            onChangeFunction = { e ->
                val f = (e.target as HTMLInputElement).files?.get(0)
                if (f != null) {
                    val r = FileReader()
                    r.onload = { onText(f.name, r.result as String) }
                    r.readAsText(f)
                }
            }
        }
    }
}
