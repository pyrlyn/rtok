// The request builders of DrillView.tsx, ComparePanel.tsx and ExportMenu.tsx: the `graph`, `diff`, `export` and
// `import` frames the Graph page sends.
package rtok.web.ui

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** `{"graph":{project, expand, focus, depth, limit, query}}` for a drill state. */
fun drillRequest(
    s: DrillState,
    limit: Int,
    query: String = "",
): String =
    buildJsonObject {
        putJsonObject("graph") {
            put("project", s.project)
            putJsonArray("expand") { s.expand.forEach { add(JsonPrimitive(it)) } }
            val f = s.focus
            if (f == null) {
                put("focus", JsonNull)
                put("depth", JsonNull)
            } else {
                putJsonObject("focus") {
                    put("path", f.path)
                    put("name", f.name)
                }
                put("depth", s.depth)
            }
            put("limit", limit)
            put("query", query)
        }
    }.toString()

/** `{"diff":{project, from, to?}}`: compare against refs ([to] null is the working tree). */
fun diffRequest(
    project: String,
    from: List<String>,
    to: String? = null,
): String =
    buildJsonObject {
        putJsonObject("diff") {
            put("project", project)
            putJsonArray("from") { from.forEach { add(JsonPrimitive(it)) } }
            to?.let { put("to", it) }
        }
    }.toString()

/** `{"diff":{project, export:{name,text}}}`: compare against a saved export. */
fun diffExportRequest(
    project: String,
    name: String,
    text: String,
): String =
    buildJsonObject {
        putJsonObject("diff") {
            put("project", project)
            putJsonObject("export") {
                put("name", name)
                put("text", text)
            }
        }
    }.toString()

/** `{"export":{project, level, focus?, depth?, format}}`; png is not offered (see docs/web.md). */
fun exportRequest(
    project: String,
    level: String,
    format: String,
    focus: String? = null,
    depth: Int? = null,
): String =
    buildJsonObject {
        putJsonObject("export") {
            put("project", project)
            put("level", level)
            focus?.let { put("focus", it) }
            depth?.let { put("depth", it) }
            put("format", format)
        }
    }.toString()

/** `{"import":{name,text}}`. */
fun importRequest(
    name: String,
    text: String,
): String =
    buildJsonObject {
        putJsonObject("import") {
            put("name", name)
            put("text", text)
        }
    }.toString()
