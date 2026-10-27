// Port of src/proxy/batch_results.rs (T385.4, `[proxy.batch] parse_results`): provider Batch result files (JSONL)
// as `usage` rows. Observation only: a bad line, a failed request or a body without counters yields no row.
// Anthropic `…/batches/{id}/results` lines carry `result.message.usage`; OpenAI `/v1/files/{id}/content` lines
// carry `response.body.usage` of the batched endpoint (Chat Completions or Responses).
package rtok.proxy

import kotlinx.serialization.json.*
import rtok.store.*

object BatchResults {
    /** Where a response may hold a results file. */
    enum class Source {
        /** Anthropic `…/batches/{id}/results`: already a Batch-lane call. */
        Batch,

        /** OpenAI `/v1/files/{id}/content`: only the lines found say it was Batch output. */
        File,
    }

    /** One result line's counters; [id] is the line's own request id. */
    data class ResultUsage(
        val model: String?,
        val api: String,
        val usage: Usage,
        val id: String? = null,
    )

    /** Whether a successful `GET path` on [lane] is worth reading as a results file. */
    fun source(
        lane: Lane,
        method: String,
        path: String,
    ): Source? =
        when {
            method != "GET" -> null
            lane == Lane.Batch && path.endsWith("/results") -> Source.Batch
            lane == Lane.Files && path.endsWith("/content") -> Source.File
            else -> null
        }

    /**
     * Write the `usage` rows of [body] under [callId] and re-tag a file download that turned out to be Batch
     * output. [scope] (the target without its query) plus a line's id identifies one result (T583), so a
     * replayed or partial-then-whole download adds only what is new; a line without an id counts each time.
     */
    fun record(
        store: Store,
        session: String,
        callId: Int,
        fallbackModel: String?,
        source: Source,
        scope: String,
        body: ByteArray,
    ): Int {
        val parsed = parse(body)
        if (parsed.isEmpty()) return 0
        val rows =
            parsed.map { r ->
                val u = r.usage
                val key = r.id?.let { hexSha256("${r.api}\u0000$scope\u0000$it".encodeToByteArray()) }
                UsageRowInput(r.model ?: fallbackModel, r.api, longArrayOf(u.input, u.cacheCreate, u.cacheRead, u.output), key)
            }
        val written = store.insertUsageRows(session, callId, rows)
        if (source == Source.File) store.setCallKind(callId, Lane.Batch.kind)
        return written
    }

    /** Every line that carries usage; split on bytes so a malformed or truncated line costs only itself. */
    fun parse(body: ByteArray): List<ResultUsage> =
        buildList {
            var start = 0
            for (i in 0..body.size) {
                if (i == body.size || body[i] == '\n'.code.toByte()) {
                    lineUsage(body.copyOfRange(start, i))?.let(::add)
                    start = i + 1
                }
            }
        }

    private fun lineUsage(bytes: ByteArray): ResultUsage? {
        val text = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
        val line = text?.let(SerdeJson::parse) as? JsonObject ?: return null
        val result = line["result"]
        return if (result != null) anthropic(line, result) else openai(line)
    }

    private fun anthropic(
        line: JsonObject,
        result: JsonElement,
    ): ResultUsage? {
        val message = (result as? JsonObject)?.get("message")?.takeIf { result.str("type") == "succeeded" }
        return message?.let { found(AnthropicWire, Wires.API_ANTHROPIC, result, it) }?.copy(id = lineId(line, "custom_id"))
    }

    private fun openai(line: JsonObject): ResultUsage? {
        val response = line["response"] as? JsonObject
        val code = (response?.get("status_code") as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        val body = response?.get("body")?.takeIf { code == null || code in 200..299 } ?: return null
        val responses = body.str("object") == "response"
        val wire = if (responses) OpenAiResponsesWire else OpenAiChatWire
        val api = if (responses) Wires.API_OPENAI_RESPONSES else Wires.API_OPENAI_CHAT
        // OpenAI's `id` is unique per request line; `custom_id` is the caller's own label.
        return found(wire, api, body, body)?.copy(id = lineId(line, "id", "custom_id"))
    }

    private fun lineId(
        line: JsonObject,
        vararg fields: String,
    ) = fields.firstNotNullOfOrNull { f -> line.str(f)?.takeIf { it.isNotEmpty() } }

    private fun found(
        wire: Wire,
        api: String,
        usageIn: JsonElement,
        modelIn: JsonElement,
    ) = wire.usageFromBody(usageIn)?.let { ResultUsage(modelIn.str("model"), api, it) }
}
