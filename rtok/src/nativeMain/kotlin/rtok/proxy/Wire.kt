// Port of the ToolResults helpers in src/proxy/wire.rs (the rest of the wire lands with Phase 7).
package rtok.proxy

import kotlinx.serialization.json.*
import rtok.plugin.sdk.WireRequest

internal fun JsonElement?.str(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

/** One entry of a turn-ordered array: its index, the object, and its turn (user messages after it). */
internal class TurnEntry(
    val index: Int,
    val obj: JsonObject,
    val turn: Int,
)

/**
 * Rust `turn_setup` + the per-wire loop: walks `body[field]`, counting `user` entries from the end.
 * An entry's turn is taken after counting it when it is a user message (Rust `seen += 1` first).
 */
internal fun turnEntries(
    req: WireRequest,
    field: String,
): List<TurnEntry> {
    val entries = (req.body as? JsonObject)?.get(field) as? JsonArray ?: return emptyList()
    val total = entries.count { it.str("role") == "user" }
    var seen = 0
    return entries.mapIndexedNotNull { i, e ->
        val o = e as? JsonObject ?: return@mapIndexedNotNull null
        if (o.str("role") == "user") seen++
        TurnEntry(i, o, total - seen)
    }
}
