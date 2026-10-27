// Port of src/doctor/mcp_fix.rs. The MCP half of `doctor --fix` (T331.6): drop the chosen entries from a host's
// config. JSON and JSONC go through the same byte-preserving editor as hooks; a TOML `[mcp_servers.<name>]` table goes
// through `:toml-aot`, which keeps every other byte. The result is parsed back and must hold the old servers minus the
// removed ones, or it is not used.
package rtok.doctor

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import rtok.mcp.config.Format
import rtok.mcp.config.Jsonc
import rtok.mcp.config.JsoncItems
import rtok.mcp.config.McpConfig
import rtok.mcp.config.McpFs
import rtok.mcp.config.McpSpec
import rtok.mcp.config.Seg
import tomlaot.Shape
import tomlaot.TomlDoc

@Suppress("ReturnCount")
internal object McpFix {
    private const val MORE = "the edit would change more than the duplicate entries"

    /** One file's text, served to the `rtok-mcp` reader the doctor check also uses. */
    private class One(
        val file: String,
        val text: String,
    ) : McpFs {
        override fun read(path: String) = text.takeIf { path == file }

        override fun write(
            path: String,
            text: String,
        ): Unit = error("the guard never writes")
    }

    private fun servers(
        text: String,
        file: String,
        spec: McpSpec,
    ): Map<String, JsonElement>? = runCatching { McpConfig.readServers(One(file, text), spec) }.getOrNull()

    private fun dropEntry(
        text: String,
        file: String,
        l: Loc,
    ): String? {
        if (l.spec.format == Format.Toml) {
            val doc = runCatching { TomlDoc.parse(text) }.getOrNull() ?: return null
            val parent = l.spec.keyPath.joinToString(".")
            val path = "$parent.${l.name}"
            val gone =
                when (doc.shape(path)) {
                    Shape.Table -> doc.removeTable(path)
                    Shape.Value -> (if (parent.isEmpty()) doc.root else doc.table(parent))?.remove(l.name) == true
                    else -> false
                }
            return doc.toString().takeIf { gone }
        }
        val segs = (l.spec.keyPath + l.name).map { Seg.Key(it) }
        val (next, found) = runCatching { JsoncItems.removeAt(text, file, segs) }.getOrNull() ?: return null
        return next.takeIf { found }
    }

    /** [value] without the object member at [keys]. */
    private fun without(
        value: JsonElement,
        keys: List<String>,
    ): JsonElement {
        if (keys.isEmpty() || value !is JsonObject) return value
        val head = keys.first()
        if (keys.size == 1) return JsonObject(value - head)
        val child = value[head] ?: return value
        return JsonObject(value + (head to without(child, keys.drop(1))))
    }

    /** [raw] without the entries [locs] name, or why the edit cannot be trusted. */
    fun remove(
        raw: String,
        file: String,
        locs: List<Loc>,
    ): Result<String> {
        var body = raw
        for (l in locs) body = dropEntry(body, file, l) ?: return Result.failure(IllegalStateException("an entry could not be located"))
        val refused = Result.failure<String>(IllegalStateException(MORE))
        for (l in locs) {
            val want = servers(raw, file, l.spec)?.toMutableMap() ?: return refused
            val got = servers(body, file, l.spec) ?: return refused
            for (same in locs.filter { it.spec.keyPath == l.spec.keyPath }) want.remove(same.name)
            if (want != got.toMap()) return refused
        }
        // The servers tables match; the rest of a JSON file must too. A TOML table is edited in place.
        if (locs.any { it.spec.format != Format.Toml }) {
            var old = Jsonc.parseOrNull(raw) ?: return refused
            val new = Jsonc.parseOrNull(body) ?: return refused
            for (l in locs) old = without(old, l.spec.keyPath + l.name)
            if (old != new) return refused
        }
        return Result.success(body)
    }
}
