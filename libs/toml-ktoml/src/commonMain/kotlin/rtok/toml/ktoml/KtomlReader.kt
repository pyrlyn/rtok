// TomlReader adapter over ktoml (github.com/orchestr7/ktoml). The only file that imports ktoml.
package rtok.toml.ktoml

import com.akuleshov7.ktoml.TomlInputConfig
import com.akuleshov7.ktoml.parsers.TomlParser
import com.akuleshov7.ktoml.tree.nodes.*
import com.akuleshov7.ktoml.tree.nodes.pairs.values.*
import kotlinx.serialization.json.*
import rtok.toml.TomlDocument
import rtok.toml.TomlException
import rtok.toml.TomlReader

object KtomlReader : TomlReader {
    private val parser = TomlParser(TomlInputConfig(ignoreUnknownNames = true))

    override fun read(text: String): TomlDocument {
        val file =
            try {
                parser.parseString(escapeQuotesForKtoml(text))
            } catch (e: Exception) {
                throw TomlException("TOML parse error: ${e.message}", e)
            }
        val lines = HashMap<String, Int>()
        val root = TreeBuilder(lines).obj(file.children, emptyList())
        return Doc(root, lines)
    }

    override fun write(root: JsonObject): String =
        try {
            KtomlWriter.write(root)
        } catch (e: Exception) {
            throw TomlException("TOML write error: ${e.message}", e)
        }

    private class Doc(
        override val root: JsonObject,
        private val lines: Map<String, Int>,
    ) : TomlDocument {
        override fun lineOf(dotted: String) = lines[dotted] ?: 1
    }
}

/** ktoml AST → JSON tree, recording the first line of each dotted key. */
private class TreeBuilder(
    val lines: MutableMap<String, Int>,
) {
    fun obj(
        nodes: List<TomlNode>,
        path: List<String>,
    ): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for (n in nodes) {
            if (n is TomlStubEmptyNode) continue
            val key = n.name
            val here = path + key
            if (n !is TomlTable || !n.isSynthetic) lines.getOrPut(here.joinToString(".")) { n.lineNo }
            val v = node(n, here)
            val old = out[key]
            // ktoml accepts duplicate keys (last wins); TOML 1.0 and Rust `toml` reject them.
            if (old != null && (old !is JsonObject || v !is JsonObject)) throw TomlException("TOML parse error at line ${n.lineNo}: duplicate key '${here.joinToString(".")}'")
            out[key] = merge(old, v)
        }
        return JsonObject(out)
    }

    private fun merge(
        old: JsonElement?,
        new: JsonElement,
    ): JsonElement = if (old is JsonObject && new is JsonObject) JsonObject(old + new.mapValues { (k, v) -> merge(old[k], v) }) else new

    private fun node(
        n: TomlNode,
        path: List<String>,
    ): JsonElement =
        when (n) {
            // `[[a.b.c]]` makes `a` and `b` synthetic ARRAY tables too; only the one holding elements is an array.
            is TomlTable -> if (n.type == TableType.ARRAY && n.children.any { it is TomlArrayOfTablesElement }) array(n, path) else obj(n.children, path)

            is TomlInlineTable -> obj(n.children, path)

            is TomlKeyValuePrimitive -> value(n.value)

            is TomlKeyValueArray -> value(n.value)

            else -> obj(n.children, path)
        }

    private fun array(
        n: TomlTable,
        path: List<String>,
    ) = JsonArray(n.children.filterIsInstance<TomlArrayOfTablesElement>().map { obj(it.children, path) })

    private fun value(v: TomlValue): JsonElement =
        when (v) {
            is TomlArray -> JsonArray((v.content as List<*>).map { item(it) })
            is TomlLong -> JsonPrimitive(v.content as Long)
            is TomlUnsignedLong -> JsonPrimitive(v.content as ULong)
            is TomlDouble -> JsonPrimitive(v.content as Double)
            is TomlBoolean -> JsonPrimitive(v.content as Boolean)
            is TomlNull -> JsonNull
            else -> JsonPrimitive(v.content.toString()) // basic/literal strings, datetimes
        }

    private fun item(x: Any?): JsonElement =
        when (x) {
            is TomlValue -> value(x)
            is List<*> -> JsonArray(x.map { item(it) })
            is TomlNode -> obj(x.children, emptyList())
            else -> JsonPrimitive(x?.toString())
        }
}
