// Port of the text and digest helpers in src/proxy/semantic_cache.rs: canonical JSON (sorted keys), typed
// digests without `cache_control`, and the flattened text each content block contributes to the key.
package rtok.proxy

import kotlinx.serialization.json.*
import rtok.store.SerdeJson
import rtok.store.hexSha256

internal object CacheText {
    fun encode(v: JsonElement) = SerdeJson.encode(canonical(v))

    /** sha256 of the canonical JSON without `cache_control`; [blankText] empties string `text`/`content`. */
    fun digest(
        v: JsonElement,
        blankText: Boolean,
    ): String {
        fun walk(e: JsonElement): JsonElement =
            when (e) {
                is JsonObject -> {
                    JsonObject(
                        e.filterKeys { it != "cache_control" }.mapValues { (k, x) ->
                            val blank = blankText && (k == "text" || k == "content") && x is JsonPrimitive && x.isString
                            if (blank) JsonPrimitive("") else walk(x)
                        },
                    )
                }

                is JsonArray -> {
                    JsonArray(e.map(::walk))
                }

                else -> {
                    e
                }
            }
        return hexSha256(encode(walk(v)).encodeToByteArray())
    }

    fun contentText(v: JsonElement): String =
        when {
            v is JsonPrimitive && v.isString -> v.content
            v is JsonArray -> v.joinToString("\n", transform = ::blockText)
            else -> ""
        }

    /** One block's contribution (T55.14, T303): text, tool ids and input, binary sources as sha256, else the block. */
    private fun blockText(b: JsonElement): String {
        val text = b.str("text") ?: (b as? JsonPrimitive)?.takeIf { it.isString }?.content
        val o = b as? JsonObject
        return when {
            text != null -> {
                text
            }

            o == null -> {
                encode(b)
            }

            else -> {
                when (val kind = o.str("type").orEmpty()) {
                    "tool_result" -> "tool_result ${o.str("tool_use_id").orEmpty()}: ${contentText(o["content"] ?: JsonNull)}"
                    "tool_use" -> "tool_use ${o.str("id").orEmpty()} ${o.str("name").orEmpty()} ${encode(o["input"] ?: JsonNull)}"
                    "image", "document" -> "$kind ${hexSha256(encode(o["source"] ?: JsonNull).encodeToByteArray())}"
                    "image_url" -> "image_url ${hexSha256((o["image_url"].str("url")).orEmpty().encodeToByteArray())}"
                    else -> encode(o)
                }
            }
        }
    }

    /** Object keys sorted recursively; array order is meaningful and kept. */
    fun canonical(v: JsonElement): JsonElement =
        when (v) {
            is JsonObject -> JsonObject(v.entries.sortedBy { it.key }.associate { it.key to canonical(it.value) })
            is JsonArray -> JsonArray(v.map(::canonical))
            else -> v
        }
}
