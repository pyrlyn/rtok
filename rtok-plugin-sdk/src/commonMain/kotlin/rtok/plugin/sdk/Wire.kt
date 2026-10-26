// Port of crates/rtok-plugin-sdk/src/wire.rs — a provider request's tool results, seen through
// the wire format's adapter. Kotlin JSON is immutable, so a ref is a path into WireRequest.body
// whose `content` setter writes the new value back.
package rtok.plugin.sdk

import kotlinx.serialization.json.*

/** A location in a JSON tree: String = object key, Int = array index. */
typealias JsonPath = List<Any>

fun JsonElement.at(path: JsonPath): JsonElement? =
    path.fold(this as JsonElement?) { e, k ->
        when (k) {
            is String -> (e as? JsonObject)?.get(k)
            is Int -> (e as? JsonArray)?.getOrNull(k)
            else -> null
        }
    }

fun JsonElement.with(
    path: JsonPath,
    value: JsonElement,
): JsonElement {
    if (path.isEmpty()) return value
    val k = path[0]
    val rest = path.subList(1, path.size)
    return when {
        k is String && this is JsonObject -> JsonObject(LinkedHashMap(this).also { it[k] = (this[k] ?: JsonNull).with(rest, value) })
        k is Int && this is JsonArray -> JsonArray(toMutableList().also { it[k] = it[k].with(rest, value) })
        else -> this
    }
}

open class BodyRef(
    protected val req: WireRequest,
    val path: JsonPath,
    val turn: Int,
) {
    var content: JsonElement
        get() = req.body.at(path) ?: JsonNull
        set(v) {
            req.body = req.body.with(path, v)
        }
}

class ToolResultRef(
    req: WireRequest,
    path: JsonPath,
    turn: Int,
    val id: String,
) : BodyRef(req, path, turn)

class BlobRef(
    req: WireRequest,
    path: JsonPath,
    turn: Int,
) : BodyRef(req, path, turn)

class SkillRef(
    req: WireRequest,
    path: JsonPath,
    turn: Int,
    val id: String,
    val name: String,
) : BodyRef(req, path, turn)

/** Implemented once per wire format (Anthropic, OpenAI chat/responses, Gemini). */
interface ToolResults {
    fun toolResults(req: WireRequest): List<ToolResultRef>

    fun skills(req: WireRequest): List<SkillRef> = emptyList()

    fun liveBlobs(req: WireRequest): List<BlobRef> = emptyList()

    fun skillRefs(req: WireRequest): List<SkillRef> = emptyList()
}

class WireRequest(
    private val wire: ToolResults,
    var body: JsonElement,
) {
    fun toolResults() = wire.toolResults(this)

    fun skills() = wire.skills(this)

    fun liveBlobs() = wire.liveBlobs(this)

    fun skillRefs() = wire.skillRefs(this)
}
