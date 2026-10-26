// Port of src/agents/mod.rs `load_toml`: a host's TOML config as a lossless [TomlDoc] (comments and layout kept). An
// absent file is an empty document; an unreadable or invalid one is an error, so a config that was not read is never
// overwritten.
package rtok.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.config.Toml
import rtok.config.TomlException
import rtok.sys.exists
import rtok.sys.readToString
import tomlaot.TomlDoc
import tomlaot.TomlTable
import tomlaot.TomlValue

object HostToml {
    fun load(path: String): TomlDoc {
        if (!exists(path)) return TomlDoc.parse("")
        val raw = readToString(path)
        try {
            Toml.parse(raw)
        } catch (e: TomlException) {
            throw IllegalStateException("$path: ${e.message}", e)
        }
        return TomlDoc.parse(raw)
    }

    /** A table as JSON for an ownership check: scalars as they are, raw values (arrays) parsed when they are JSON. */
    fun toJson(t: TomlTable): JsonObject =
        JsonObject(
            t.keys().associateWith { k ->
                when (val v = t[k]) {
                    is TomlValue.Str -> JsonPrimitive(v.value)
                    is TomlValue.Int -> JsonPrimitive(v.value)
                    is TomlValue.Bool -> JsonPrimitive(v.value)
                    is TomlValue.Raw -> runCatching { Json.parseToJsonElement(v.text) }.getOrDefault(JsonPrimitive(v.text)) as JsonElement
                    null -> JsonPrimitive(null as String?)
                }
            },
        )

    /** A TOML array of basic strings, as one raw value. */
    fun strings(items: List<String>): TomlValue = TomlValue.Raw(items.joinToString(", ", "[", "]") { TomlValue.quote(it) })
}
