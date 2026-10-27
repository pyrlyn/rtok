// Port of web/src/pages/Plugins.tsx `matchesPlugin` and the toggle request: pure logic shared with tests.
package rtok.web.ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** tableSearch.ts `SHOW` and Plugins.tsx `SHOW_LABEL`. */
val PLUGIN_SHOW = listOf("all" to "all", "on" to "enabled", "off" to "disabled", "saves" to "saves tokens")

/** The plugin's surfaces (`hook`, `mcp`, `proxy`). */
fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

/** Plugins.tsx `matchesPlugin`: the show group and a case-insensitive match on id, title and summary. */
fun matchesPlugin(
    p: JsonObject,
    show: String,
    query: String,
): Boolean {
    val q = query.trim().lowercase()
    val enabled = p.flag("enabled")
    val shown =
        when (show) {
            "on" -> enabled
            "off" -> !enabled
            "saves" -> p.flag("saves_tokens")
            else -> true
        }
    val text = "${p.str("id")} ${p.str("title")} ${p.str("summary")}".lowercase()
    return shown && (q.isEmpty() || q in text)
}

/** The `/ws` request the switch sends: `{"set":{"key":"plugins.<id>.enabled","value":<next>}}`. */
fun pluginToggle(
    id: String,
    next: Boolean,
): String =
    buildJsonObject {
        putJsonObject("set") {
            put("key", "plugins.$id.enabled")
            put("value", next)
        }
    }.toString()
