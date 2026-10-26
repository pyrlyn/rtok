// The format-keeping TOML edit config.rs gets from toml_edit, for the shape rtok writes: its entry is a standard
// `[<keyPath>.<name>]` table (values inline) and created parents stay implicit (no empty `[mcp]` header). Every
// line outside that table is kept byte for byte. An entry the user wrote as `name = { … }` on one line inside
// `[<keyPath>]` is replaced or removed too; other spellings (dotted keys spread over the file) are refused.
package rtok.mcp.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object TomlEdit {
    /** [text] with `[keyPath.name]` set to [entry], or removed when [entry] is `null`. */
    fun set(
        path: String,
        text: String,
        keyPath: List<String>,
        name: String,
        entry: JsonObject?,
    ): String {
        val lines = text.lines().toMutableList().also { if (it.lastOrNull() == "") it.removeAt(it.lastIndex) }
        val header = header(keyPath + name)
        val start = lines.indexOfFirst { stripped(it) == header }
        when {
            start >= 0 -> {
                replaceSection(lines, start, header, entry)
            }

            else -> {
                val inline = inlineLine(lines, keyPath, name)
                inline?.let { lines.removeAt(it) }
                if (entry != null) {
                    append(lines, header, entry)
                } else if (inline == null) {
                    throw McpConfigException("$path: ${(keyPath + name).joinToString(".")} is not a [table] rtok can edit; remove it by hand")
                }
            }
        }
        return join(lines)
    }

    /** Replace (or drop) the `[header]` section at [start], up to the next header and its blank separator. */
    private fun replaceSection(
        lines: MutableList<String>,
        start: Int,
        header: String,
        entry: JsonObject?,
    ) {
        var end = start + 1
        while (end < lines.size && !stripped(lines[end]).startsWith("[")) end++
        while (end > start + 1 && lines[end - 1].isBlank()) end--
        repeat(end - start) { lines.removeAt(start) }
        if (entry != null) {
            lines.addAll(start, listOf(header) + bodyLines(entry))
        } else if (start > 0 && lines[start - 1].isBlank() && lines.getOrNull(start)?.isBlank() != false) {
            lines.removeAt(start - 1)
        }
    }

    private fun append(
        lines: MutableList<String>,
        header: String,
        entry: JsonObject,
    ) {
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
        if (lines.isNotEmpty()) lines += ""
        lines += header
        lines += bodyLines(entry)
    }

    private fun join(lines: List<String>) = if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"

    /** A one-line `name = …` under `[keyPath]`. */
    private fun inlineLine(
        lines: List<String>,
        keyPath: List<String>,
        name: String,
    ): Int? {
        val table = lines.indexOfFirst { stripped(it) == header(keyPath) }
        val key = key(name)
        return if (table < 0) {
            null
        } else {
            (table + 1 until lines.size)
                .asSequence()
                .map { it to stripped(lines[it]) }
                .takeWhile { !it.second.startsWith("[") }
                .firstOrNull { (_, l) -> l.startsWith(key) && l.removePrefix(key).trimStart().startsWith("=") }
                ?.first
        }
    }

    private fun header(keys: List<String>) = keys.joinToString(".", "[", "]") { key(it) }

    /** [line] without surrounding blanks and a trailing `# comment` outside a quoted key. */
    private fun stripped(line: String): String {
        val t = line.trim()
        if (!t.startsWith("[")) return t
        val close = t.lastIndexOf(']')
        return if (close >= 0) {
            t
                .substring(0, close + 1)
                .replace(Regex("\\s*\\.\\s*"), ".")
                .replace("[ ", "[")
                .replace(" ]", "]")
        } else {
            t
        }
    }

    private fun bodyLines(entry: JsonObject) = entry.map { (k, v) -> "${key(k)} = ${value(v)}" }

    private val bare = Regex("[A-Za-z0-9_-]+")

    private fun key(k: String) = if (bare.matches(k)) k else quote(k)

    private fun quote(s: String) = Json.encodeToString(JsonElement.serializer(), JsonPrimitive(s))

    private fun value(v: JsonElement): String =
        when (v) {
            JsonNull -> "\"\""
            is JsonPrimitive -> if (v.isString) quote(v.content) else v.content
            is JsonArray -> v.joinToString(", ", "[", "]") { value(it) }
            is JsonObject -> if (v.isEmpty()) "{}" else v.entries.joinToString(", ", "{ ", " }") { (k, x) -> "${key(k)} = ${value(x)}" }
        }
}
