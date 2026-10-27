// Port of web/src/api/ws.ts (transport-free parts): the `/ws` URL, reconnect backoff and frame parsing.
package rtok.web.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The link to the server, as the status pill shows it. */
enum class LinkState { CONNECTING, OPEN, CLOSED }

/** `ws(s)://host/ws`, with `?token=` when there is one (ws.ts `wsUrl`). */
fun wsUrl(
    protocol: String,
    host: String,
    token: String?,
    encode: (String) -> String,
): String {
    val scheme = if (protocol == "https:") "wss:" else "ws:"
    val query = if (token.isNullOrEmpty()) "" else "?token=${encode(token)}"
    return "$scheme//$host/ws$query"
}

private const val BASE_DELAY_MS = 500L
private const val MAX_DELAY_MS = 10_000L
private const val MAX_SHIFT = 20

/** The wait before reconnect [attempt] (0-based): doubling from 500 ms, capped at 10 s. */
fun retryDelayMs(attempt: Int): Long = minOf(BASE_DELAY_MS shl attempt.coerceIn(0, MAX_SHIFT), MAX_DELAY_MS)

/** One server frame: a snapshot, the server's snapshot error fallback, or a typed reply. */
sealed interface Frame {
    val req: Int?

    data class Snapshot(
        val body: JsonObject,
        override val req: Int? = null,
    ) : Frame

    data class SnapshotError(
        val error: String,
        override val req: Int? = null,
    ) : Frame

    /** Any other typed frame (`message`, `expand`, `calls`, `graph`, ...); pages read [body] by [type]. */
    data class Reply(
        val type: String,
        val body: JsonObject,
        override val req: Int? = null,
    ) : Frame
}

private val json = Json { isLenient = false }

/** A frame from raw text, or null for anything that is not a typed JSON object (ws.ts `parseFrame`). */
fun parseFrame(raw: String): Frame? {
    val obj = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
    return obj?.str("type")?.let { frameOf(obj, it) }
}

private fun frameOf(
    obj: JsonObject,
    type: String,
): Frame? {
    val req = (obj["req"] as? JsonPrimitive)?.intOrNull
    val error = (obj["error"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return when {
        type != "snapshot" -> Frame.Reply(type, obj, req)
        obj["plugins"] is JsonArray -> Frame.Snapshot(obj, req)
        error != null -> Frame.SnapshotError(error, req)
        else -> null
    }
}

/** A string field of [obj], or null. */
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A number field of [obj] as Double, or null. */
fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()

/** True when [key] holds JSON `true`. */
fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.jsonPrimitive?.content == "true"
