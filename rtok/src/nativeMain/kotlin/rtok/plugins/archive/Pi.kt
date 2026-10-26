// Port of src/plugins/archive/pi.rs — T70.2: the pi `context` carrier for the archive live zone.
// pi results are `role: "toolResult"` messages keyed by camelCase `toolCallId`; the rewrite is the
// same `rewrite` + `rewriteBlobs` the proxy runs, there is no second implementation.
package rtok.plugins.archive

import kotlinx.serialization.json.*
import rtok.plugin.sdk.*

/** pi's message array as a wire: turns are user messages counted from the end, as the proxy wires count them. */
object PiWire : ToolResults {
    private fun JsonElement.role() = ((this as? JsonObject)?.get("role") as? JsonPrimitive)?.contentOrNull

    private inline fun walk(req: WireRequest, each: (Int, JsonObject, Int) -> Unit) {
        val entries = req.body as? JsonArray ?: return
        val total = entries.count { it.role() == "user" }
        var seen = 0
        entries.forEachIndexed { i, m ->
            val o = m as? JsonObject ?: return@forEachIndexed
            if (o.role() == "user") seen++
            each(i, o, total - seen)
        }
    }

    override fun toolResults(req: WireRequest): List<ToolResultRef> = buildList {
        walk(req) { i, m, turn ->
            if (m.role() != "toolResult") return@walk
            val id = (m["toolCallId"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@walk
            if ("content" in m) add(ToolResultRef(req, listOf(i, "content"), turn, id))
        }
    }

    /** User message text: whole strings and `text` parts (the `live_blobs` gate is in `rewriteBlobs`). */
    override fun liveBlobs(req: WireRequest): List<BlobRef> = buildList {
        walk(req) { i, m, turn ->
            if (m.role() != "user") return@walk
            when (val c = m["content"]) {
                is JsonPrimitive -> if (c.isString) add(BlobRef(req, listOf(i, "content"), turn))
                is JsonArray -> c.forEachIndexed { j, p ->
                    val po = p as? JsonObject ?: return@forEachIndexed
                    if ((po["type"] as? JsonPrimitive)?.contentOrNull == "text" && "text" in po) add(BlobRef(req, listOf(i, "content", j, "text"), turn))
                }
                else -> {}
            }
        }
    }
}

/**
 * `rtok archive rewrite --stdin`: input that is not a JSON array, or where nothing is eligible, comes back
 * verbatim (the extension's "no change" test is a byte compare). Store errors leave the array alone (D1).
 */
fun rewriteStdin(input: ByteArray, cx: Ctx): ByteArray {
    val messages = runCatching { Json.parseToJsonElement(input.decodeToString(throwOnInvalidSequence = true)) }.getOrNull()
    if (messages !is JsonArray || !archiveCfg(cx).enabled) return input
    // The shared rewrite writes a plain pointer string; pi renders `content[].text`, so restore the array shape.
    val wasArray = messages.map { (it as? JsonObject)?.let { o -> (o["role"] as? JsonPrimitive)?.contentOrNull == "toolResult" && o["content"] is JsonArray } == true }
    val req = WireRequest(PiWire, messages)
    val ms = rewrite(req.toolResults(), cx) + rewriteBlobs(req.liveBlobs(), cx)
    if (ms.isEmpty()) return input
    for (m in ms) runCatching { cx.record(m) }.onFailure { cx.log("error", "plugin", "archive", "measurement: ${it.message}") }
    val out = (req.body as JsonArray).mapIndexed { i, m ->
        val o = m as? JsonObject
        val p = (o?.get("content") as? JsonPrimitive)?.takeIf { it.isString }
        if (wasArray[i] && o != null && p != null)
            JsonObject(LinkedHashMap(o).also { it["content"] = buildJsonArray { addJsonObject { put("type", "text"); put("text", p) } } })
        else m
    }
    return JsonArray(out).toString().encodeToByteArray()
}
