// Port of src/proxy/openai_responses.rs (Responses API, T11.3).
package rtok.proxy

import kotlinx.serialization.json.*
import rtok.plugin.sdk.*

object OpenAiResponsesWire : Wire {
    /** `input_tokens` counts cached tokens too; streamed usage rides on the terminal event under `response`. */
    private val fields =
        UsageFields(
            altParent = "response",
            input = "input_tokens",
            output = "output_tokens",
            cacheRead = "cached_tokens",
            cacheReadDetails = "input_tokens_details",
            inputIncludesCache = true,
        )
    private val terminal = setOf("response.completed", "response.incomplete", "response.failed")

    /**
     * `function_call_output` items of `input[]`, keyed by `call_id`, payload in `output`. With
     * `previous_response_id` the history is server-side, so there is nothing old to rewrite.
     */
    override fun toolResults(req: WireRequest): List<ToolResultRef> {
        val prev = (req.body as? JsonObject)?.get("previous_response_id")
        if (prev != null && prev !is JsonNull) return emptyList()
        return turnEntries(req, "input").mapNotNull { e ->
            val output = e.obj.str("type") == "function_call_output" && e.obj.str("role") != "user"
            val id = e.obj.str("call_id")?.takeIf { output && "output" in e.obj }
            if (id == null) {
                null
            } else {
                ToolResultRef(req, listOf("input", e.index, "output"), e.turn, id)
            }
        }
    }

    override fun matches(path: String) = path == "/v1/responses"

    override val api get() = Wires.API_OPENAI_RESPONSES

    override fun usageFromBody(body: JsonElement) = findUsage(body, fields)

    override fun usageFromSse(event: JsonElement) = if (event.str("type") in terminal) findUsage(event, fields) else null
}
