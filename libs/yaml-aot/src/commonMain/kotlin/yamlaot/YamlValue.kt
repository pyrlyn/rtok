// A YAML value as the reader returns it: the JSON-like core (null, booleans, integers, floats, strings, sequences,
// mappings with string keys in file order). Floats keep their source text; nothing is reinterpreted beyond the YAML
// 1.2 core schema.
package yamlaot

class YamlAotException(
    message: String,
) : Exception(message)

sealed interface YamlValue {
    data object Null : YamlValue

    data class Bool(
        val value: Boolean,
    ) : YamlValue

    data class Int(
        val value: Long,
    ) : YamlValue

    data class Float(
        val text: String,
    ) : YamlValue

    data class Str(
        val value: String,
    ) : YamlValue

    data class Seq(
        val items: List<YamlValue>,
    ) : YamlValue

    data class Map(
        val entries: LinkedHashMap<String, YamlValue>,
    ) : YamlValue {
        operator fun get(key: String) = entries[key]
    }

    companion object {
        private val INT = Regex("^[-+]?(0|[1-9][0-9]*)$")
        private val FLOAT = Regex("^[-+]?(\\.[0-9]+|[0-9]+(\\.[0-9]*)?)([eE][-+]?[0-9]+)?$|^[-+]?\\.(inf|Inf|INF)$|^\\.(nan|NaN|NAN)$")

        /** A plain (unquoted) scalar under the core schema. */
        fun plain(text: String): YamlValue =
            when {
                text.isEmpty() || text in setOf("~", "null", "Null", "NULL") -> Null
                text in setOf("true", "True", "TRUE") -> Bool(true)
                text in setOf("false", "False", "FALSE") -> Bool(false)
                INT.matches(text) -> text.toLongOrNull()?.let(::Int) ?: Float(text)
                FLOAT.matches(text) -> Float(text)
                else -> Str(text)
            }
    }
}
