// The `--ai` rendering's tables (src/report/ai.rs `table`): the toon plugin's encoding, or `k=v` lines.
package rtok.report

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.plugins.toon.encode
import rtok.plugins.toon.tabularKeys
import rtok.report.ReportText.flat

/** A uniform string table: the toon encoder when the shape is tabular, `k=v` lines otherwise. Ends with `\n`. */
internal fun aiTable(
    keys: List<String>,
    rows: List<List<String>>,
): String {
    val json = rows.map { r -> JsonObject(keys.zip(r).associate { (k, v) -> k to JsonPrimitive(flat(v)) }) }
    val arr = kotlinx.serialization.json.JsonArray(json)
    if (tabularKeys(arr, 1) != null) return encode(json, keys) + "\n"
    return rows.joinToString("\n") { r -> keys.zip(r).joinToString(" ") { (k, v) -> "$k=${flat(v)}" } } + "\n"
}
