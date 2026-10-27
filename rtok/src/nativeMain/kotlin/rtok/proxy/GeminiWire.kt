// Port of src/proxy/gemini.rs (`…/models/{model}:generateContent` and `:streamGenerateContent`, T51.3).
// Tool results are user-role `functionResponse` parts keyed by function `name`; usage is `usageMetadata`.
package rtok.proxy

import kotlinx.serialization.json.*
import rtok.plugin.sdk.*

object GeminiWire : Wire {
    /** `promptTokenCount` contains the cached slice; `thoughtsTokenCount` is billed as output. */
    private val fields =
        UsageFields(
            container = "usageMetadata",
            input = "promptTokenCount",
            output = "candidatesTokenCount",
            cacheRead = "cachedContentTokenCount",
            inputIncludesCache = true,
            outputExtra = "thoughtsTokenCount",
        )

    /** The rewritten payload is `response` only, so the function name stays visible to the model. */
    override fun toolResults(req: WireRequest): List<ToolResultRef> =
        buildList {
            for (e in turnEntries(req, "contents")) {
                val parts = (e.obj["parts"] as? JsonArray).takeIf { e.obj.str("role") == "user" }.orEmpty()
                parts.forEachIndexed { j, p ->
                    val fr = (p as? JsonObject)?.get("functionResponse") as? JsonObject
                    val name = fr.str("name")
                    if (name != null && "response" in fr!!) {
                        add(ToolResultRef(req, listOf("contents", e.index, "parts", j, "functionResponse", "response"), e.turn, name))
                    }
                }
            }
        }

    override fun matches(path: String) = path.endsWith(":generateContent") || path.endsWith(":streamGenerateContent")

    override val provider get() = Wires.GEMINI_PROVIDER
    override val api get() = Wires.API_GEMINI

    /** The model travels in the path; the body carries none. */
    override fun model(
        path: String,
        body: JsonElement?,
    ): String? {
        val last = path.substringAfterLast('/')
        return if (':' in last) last.substringBefore(':').ifEmpty { null } else null
    }

    /** A non-SSE stream answers an array; the totals ride on the last usage carrier. */
    override fun usageFromBody(body: JsonElement): Usage? = if (body is JsonArray) body.asReversed().firstNotNullOfOrNull { findUsage(it, fields) } else findUsage(body, fields)

    override fun usageFromSse(event: JsonElement) = findUsage(event, fields)
}
