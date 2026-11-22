// The one JSON tree reader of the port, as strict as `serde_json::from_str::<Value>`. kotlinx's tree reader takes an
// unquoted word (`garbage`, `NaN`, `01`) as a literal even with `isLenient = false`; serde rejects it. Every literal
// that is not a string must therefore be `true`, `false`, `null` or an RFC 8259 number.
package rtok.json

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object StrictJson {
    private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    /** serde_json's recursion limit: the first nesting level it refuses. */
    private const val MAX_DEPTH = 128

    /** Parse [text] into a tree. @throws SerializationException when serde_json would reject it. */
    fun parse(text: String): JsonElement {
        // Checked before parsing: every walk of a deeper tree (this one, an encoder, a merge) recurses, and a stack
        // overflow on Kotlin/Native kills the process instead of throwing.
        if (tooDeep(text)) throw SerializationException("recursion limit exceeded")
        val root = Json.parseToJsonElement(text)
        bare(root)?.let { throw SerializationException("expected value, found unquoted '$it'") }
        return root
    }

    /** [parse], or null when [text] is not strict JSON. */
    fun parseOrNull(text: String): JsonElement? =
        try {
            parse(text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun tooDeep(text: String): Boolean {
        var depth = 0
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inString && c == '\\' -> i++
                inString -> inString = c != '"'
                c == '"' -> inString = true
                c == '[' || c == '{' -> depth++
                c == ']' || c == '}' -> depth--
            }
            if (depth >= MAX_DEPTH) return true
            i++
        }
        return false
    }

    /** The first unquoted literal in [e] that is no JSON keyword or number; null when there is none. */
    private fun bare(e: JsonElement): String? =
        when (e) {
            is JsonNull -> null
            is JsonObject -> e.values.firstNotNullOfOrNull(::bare)
            is JsonArray -> e.firstNotNullOfOrNull(::bare)
            is JsonPrimitive -> e.content.takeUnless { e.isString || it == "true" || it == "false" || NUMBER.matches(it) }
        }
}
