// Small JSON accessors shared by the session-file readers of src/measure (serde_json `get`, `pointer`, `as_str`,
// `as_i64`), so each reader reads fields the same way.
package rtok.measure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import rtok.json.StrictJson

/** `serde_json::from_str::<Value>`: null when [line] is not JSON ([StrictJson]). */
internal fun parseJson(line: String): JsonElement? = StrictJson.parseOrNull(line)

/** `v.get(key)`. */
internal fun JsonElement?.field(key: String): JsonElement? = (this as? JsonObject)?.get(key)

/** `v.pointer("/a/b")`: object keys and array indexes. */
internal fun JsonElement?.pointer(path: String): JsonElement? =
    path.split('/').drop(1).fold(this) { e, k ->
        when (e) {
            is JsonObject -> e[k]
            is JsonArray -> k.toIntOrNull()?.let(e::getOrNull)
            else -> null
        }
    }

/** `as_str`. */
internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** `as_i64`: an integer number, never a string or a fraction. */
internal fun JsonElement?.int(): Long? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

/** `as_bool`. */
internal fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

/** An RFC 3339 timestamp as unix seconds; 0 when it does not parse (jiff `Timestamp`). */
@OptIn(kotlin.time.ExperimentalTime::class)
internal fun rfc3339Seconds(s: String?): Long =
    s?.let {
        runCatching {
            kotlin.time.Instant
                .parse(it)
                .epochSeconds
        }.getOrNull()
    } ?: 0

/** A token counter: `as_i64`, missing is 0, a negative count is 0. */
internal fun JsonElement?.count(key: String): Long = field(key).int()?.coerceAtLeast(0) ?: 0
