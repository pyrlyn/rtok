// The single place that picks the TOML backend. Everything else in rtok-config uses `Toml`,
// which exposes only the backend-neutral `rtok.toml` API.
package rtok.config

import rtok.toml.TomlDocument
import rtok.toml.TomlReader
import rtok.toml.TomlValues
import rtok.toml.ktoml.KtomlReader

typealias TomlException = rtok.toml.TomlException
typealias TomlDoc = TomlDocument

object Toml {
    var reader: TomlReader = KtomlReader
    fun parse(text: String) = reader.parse(text)
    fun write(root: kotlinx.serialization.json.JsonObject) = reader.write(root)
    fun parseDoc(text: String): TomlDoc = reader.read(text)
    fun isInteger(p: kotlinx.serialization.json.JsonPrimitive) = TomlValues.isInteger(p)
    fun isFloat(p: kotlinx.serialization.json.JsonPrimitive) = TomlValues.isFloat(p)
    fun isBool(p: kotlinx.serialization.json.JsonPrimitive) = TomlValues.isBool(p)
}
