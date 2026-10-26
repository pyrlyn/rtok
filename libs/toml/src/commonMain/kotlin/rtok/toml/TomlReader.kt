// Backend-neutral TOML reading API. Implementations (adapters) live in their own modules, e.g.
// `:toml-ktoml`; callers depend only on this module so the backend can be swapped.
package rtok.toml

import kotlinx.serialization.json.*

/** Parse failure; the message should name the 1-based line when the backend knows it. */
class TomlException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A parsed document as a kotlinx JSON tree (datetimes kept as strings) plus source positions. */
interface TomlDocument {
    val root: JsonObject
    /** 1-based line where [dotted] key or table was defined; 1 when unknown. */
    fun lineOf(dotted: String): Int
}

interface TomlReader {
    /** @throws TomlException on invalid TOML. */
    fun read(text: String): TomlDocument
    fun parse(text: String): JsonObject = read(text).root
    /** Serialize a tree back to TOML. Comments and original formatting are not preserved. */
    fun write(root: JsonObject): String
}

/** Scalar kind checks on trees produced by any [TomlReader] (TOML keeps 1 and 1.0 apart). */
object TomlValues {
    fun isInteger(p: JsonPrimitive) = !p.isString && p.longOrNull != null && p.content.none { it == '.' || it == 'e' || it == 'E' }
    fun isFloat(p: JsonPrimitive) = !p.isString && !isInteger(p) && p.doubleOrNull != null
    fun isBool(p: JsonPrimitive) = !p.isString && p.booleanOrNull != null
}
