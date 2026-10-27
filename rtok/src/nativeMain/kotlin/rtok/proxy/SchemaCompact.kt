// Port of T686 schema prose compaction in src/proxy/tools_rewrite.rs: first sentence of every `description`,
// and `default`/`examples` that only restate the schema. Names, `type`, `required` and `enum` are never written.
package rtok.proxy

import kotlinx.serialization.json.*

internal object SchemaCompact {
    /** Keywords whose value maps names to subschemas; a property called `description` stays out of reach. */
    private val SCHEMA_MAPS = listOf("properties", "\$defs", "definitions", "patternProperties")
    private val SCHEMA_CHILDREN =
        listOf(
            "items",
            "additionalProperties",
            "not",
            "if",
            "then",
            "else",
            "contains",
            "propertyNames",
            "unevaluatedProperties",
            "anyOf",
            "oneOf",
            "allOf",
            "prefixItems",
        )

    /** T686: first sentence of every `description`, and restating `default`/`examples`; never names or types. */
    fun compactSchema(node: JsonElement): JsonElement =
        when (node) {
            is JsonArray -> {
                JsonArray(node.map(::compactSchema))
            }

            is JsonObject -> {
                val m = LinkedHashMap<String, JsonElement>(node)
                val d = m["description"].str()
                val first = d?.let { Sentences.sentences(it).firstOrNull()?.trim() }
                if (d != null && !first.isNullOrEmpty() && first != d) m["description"] = JsonPrimitive(first)
                dropRestated(m)
                for (k in SCHEMA_MAPS) (m[k] as? JsonObject)?.let { c -> m[k] = JsonObject(c.mapValues { compactSchema(it.value) }) }
                for (k in SCHEMA_CHILDREN) m[k]?.let { m[k] = compactSchema(it) }
                if (m == node) node else JsonObject(m)
            }

            else -> {
                node
            }
        }

    /** Drop `examples` that all repeat `default` or an `enum` member, then a zero-value `default`. */
    private fun dropRestated(m: MutableMap<String, JsonElement>) {
        val default = m["default"]
        val members = m["enum"] as? JsonArray
        val examples = m["examples"] as? JsonArray
        val restated = examples != null && examples.all { e -> default == e || members?.contains(e) == true }
        val zero =
            when (m["type"].str()) {
                "boolean" -> default == JsonPrimitive(false)
                "integer", "number" -> (default as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull == 0.0
                "string" -> (default as? JsonPrimitive)?.let { it.isString && it.content.isEmpty() } == true
                "array" -> (default as? JsonArray)?.isEmpty() == true
                "object" -> (default as? JsonObject)?.isEmpty() == true
                else -> false
            }
        if (restated) m.remove("examples")
        if (zero) m.remove("default")
    }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
