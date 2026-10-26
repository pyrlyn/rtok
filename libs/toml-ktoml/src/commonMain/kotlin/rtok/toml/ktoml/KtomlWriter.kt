// JSON tree → TOML text through ktoml's encoder (comments are not preserved).
package rtok.toml.ktoml

import com.akuleshov7.ktoml.Toml
import kotlinx.serialization.*
import kotlinx.serialization.builtins.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.*

internal object KtomlWriter {
    private val toml = Toml()
    /** A TOML document; always newline-terminated, so a line appended later starts on its own line. */
    fun write(root: JsonObject): String = toml.encodeToString(TableSerializer, root).let { if (it.isEmpty() || it.endsWith("\n")) it else it + "\n" }

    /** Encodes a table; ktoml picks table/array/primitive layout from each value's descriptor. */
    private object TableSerializer : SerializationStrategy<JsonObject> {
        @OptIn(ExperimentalSerializationApi::class)
        override val descriptor = buildClassSerialDescriptor("rtok.toml.Table")
        override fun serialize(encoder: Encoder, value: JsonObject) {
            val d = DynamicDescriptor.of(value)
            val c = encoder.beginStructure(d)
            value.entries.forEachIndexed { i, (_, v) -> encodeElement(c, d, i, v) }
            c.endStructure(d)
        }
    }

    private fun encodeElement(c: CompositeEncoder, d: SerialDescriptor, i: Int, v: JsonElement) {
        when (v) {
            is JsonObject -> c.encodeSerializableElement(d, i, TableSerializer, v)
            is JsonArray -> c.encodeSerializableElement(d, i, ArraySerializer, v)
            is JsonNull -> c.encodeStringElement(d, i, "")
            is JsonPrimitive -> when {
                v.isString -> c.encodeStringElement(d, i, v.content)
                v.booleanOrNull != null -> c.encodeBooleanElement(d, i, v.boolean)
                rtok.toml.TomlValues.isInteger(v) -> c.encodeLongElement(d, i, v.long)
                else -> c.encodeDoubleElement(d, i, v.double)
            }
        }
    }

    private object ArraySerializer : SerializationStrategy<JsonArray> {
        @OptIn(ExperimentalSerializationApi::class)
        override val descriptor = listSerialDescriptor(String.serializer().descriptor)
        override fun serialize(encoder: Encoder, value: JsonArray) {
            val d = DynamicDescriptor.list(value)
            val c = encoder.beginCollection(d, value.size)
            value.forEachIndexed { i, v -> encodeElement(c, d, i, v) }
            c.endStructure(d)
        }
    }

    /** Per-value descriptors so mixed-type tables encode with the right element kinds. */
    @OptIn(ExperimentalSerializationApi::class)
    private object DynamicDescriptor {
        fun kindOf(v: JsonElement): SerialDescriptor = when (v) {
            is JsonObject -> of(v)
            is JsonArray -> list(v)
            is JsonNull -> String.serializer().descriptor
            is JsonPrimitive -> when {
                v.isString -> String.serializer().descriptor
                v.booleanOrNull != null -> Boolean.serializer().descriptor
                rtok.toml.TomlValues.isInteger(v) -> Long.serializer().descriptor
                else -> Double.serializer().descriptor
            }
        }
        fun of(o: JsonObject): SerialDescriptor = buildClassSerialDescriptor("rtok.toml.T${o.hashCode()}") {
            for ((k, v) in o) element(k, kindOf(v))
        }
        fun list(a: JsonArray): SerialDescriptor = listSerialDescriptor(a.firstOrNull()?.let { kindOf(it) } ?: String.serializer().descriptor)
    }
}
