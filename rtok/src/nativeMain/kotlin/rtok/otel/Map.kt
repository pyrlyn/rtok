// Port of src/otel/map.rs: ledger rows → GenAI-convention spans and log records.
@file:Suppress("MagicNumber", "ReturnCount")

package rtok.otel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import rtok.config.Otel
import rtok.store.Call
import rtok.store.CallDetail
import rtok.store.LogRow
import rtok.store.Session

private const val NS_PER_MS = 1e6

/** `invoke_agent {host}`: the root every call span hangs from. Sent once `ended_at` is set. */
fun sessionSpan(
    se: Session,
    host: String?,
): Span {
    val h = host ?: "agent"
    val attrs = mutableListOf(s("gen_ai.operation.name", "invoke_agent"), s("gen_ai.agent.name", h), s("gen_ai.conversation.id", se.id))
    listOf("rtok.project" to se.project, "rtok.cwd" to se.cwd, "rtok.source" to se.source).forEach { (k, v) -> v?.let { attrs += s(k, it) } }
    val start = secondsToNs(se.startedAt)
    return Span(traceId(se.id), spanId("session", se.id), null, "invoke_agent $h", Kind.Internal, start, se.endedAt?.let(::secondsToNs) ?: start, attrs, emptyList(), Status.Unset)
}

private fun parse(text: String?): JsonElement? = text?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }

private fun JsonElement?.field(key: String): JsonElement? = (this as? JsonObject)?.get(key)

private fun JsonElement?.str(key: String): String? = (field(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** A JSON value as attribute text: strings verbatim, anything else serialised, null absent. */
private fun jsonText(v: JsonElement?): String? =
    when {
        v == null || v is JsonNull -> null
        v is JsonPrimitive && v.isString -> v.content
        else -> v.toString()
    }

/** The first of `keys` in a JSON body, else the whole body — the fullest record we hold. */
private fun pick(
    body: String?,
    keys: List<String>,
): String? {
    val v = parse(body ?: return null) ?: return null
    return keys.firstNotNullOfOrNull { jsonText(v.field(it)) } ?: body
}

/** Content attributes obey `content` and `content_bytes`; a cut names the archive id (D4). */
private class Content(
    private val cfg: Otel,
    private val attrs: MutableList<Attr>,
) {
    fun add(
        key: String,
        text: String?,
        archive: String?,
    ) {
        if (!cfg.content) return
        archive?.let { attrs += s("rtok.archive.id", it) }
        val bytes = (text ?: return).encodeToByteArray()
        val cap = cfg.contentBytes.toInt()
        if (bytes.size <= cap) {
            attrs += s(key, text)
            return
        }
        var cut = cap
        while (cut > 0 && (bytes[cut].toInt() and 0xC0) == 0x80) cut--
        attrs += s(key, bytes.decodeToString(0, cut))
        attrs += b("rtok.content.truncated", true)
    }
}

/** One `calls` row → one span, shaped by its surface (hook / mcp / proxy). */
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun callSpan(
    call: Call,
    d: CallDetail,
    cfg: Otel,
): Span {
    val start = secondsToNs(call.ts)
    val end = start + ((call.ms ?: 0.0).coerceAtLeast(0.0) * NS_PER_MS).toLong()
    val attrs = mutableListOf(s("gen_ai.conversation.id", call.sessionId), s("rtok.surface", call.surface), s("rtok.kind", call.kind), i("rtok.call.id", call.id.toLong()))
    d.host?.let { attrs += s("rtok.host", it) }
    call.plugin?.let { attrs += s("rtok.plugin", it) }
    val content = Content(cfg, attrs)
    val req = d.io?.requestJson
    val resp = d.io?.responseJson
    val reqArchive = d.io?.requestArchive
    val respArchive = d.io?.responseArchive
    var kind = Kind.Internal
    val name =
        when (call.surface) {
            "hook" -> {
                val event = call.name.orEmpty()
                val v = parse(req)
                attrs += s("rtok.hook.event", event)
                val tool = v.str("tool_name")
                if ((event == "PreToolUse" || event == "PostToolUse") && tool != null) {
                    attrs += s("gen_ai.operation.name", "execute_tool")
                    attrs += s("gen_ai.tool.name", tool)
                    v.str("tool_use_id")?.let { attrs += s("gen_ai.tool.call.id", it) }
                    content.add("gen_ai.tool.call.arguments", jsonText(v.field("tool_input")), reqArchive)
                    if (event == "PostToolUse") content.add("gen_ai.tool.call.result", jsonText(v.field("tool_response")), reqArchive)
                    "execute_tool $tool"
                } else {
                    v.str("prompt")?.let { p ->
                        val msgs =
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("role", "user")
                                        put(
                                            "parts",
                                            buildJsonArray {
                                                add(
                                                    buildJsonObject {
                                                        put("type", "text")
                                                        put("content", p)
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                            }
                        content.add("gen_ai.input.messages", msgs.toString(), reqArchive)
                    }
                    "hook $event"
                }
            }

            "mcp" -> {
                val tool = call.name.orEmpty()
                attrs += s("gen_ai.operation.name", "execute_tool")
                attrs += s("gen_ai.tool.name", tool)
                content.add("gen_ai.tool.call.arguments", req, reqArchive)
                content.add("gen_ai.tool.call.result", resp, respArchive)
                "execute_tool $tool"
            }

            "proxy" -> {
                kind = Kind.Client
                val model = d.model ?: "unknown"
                attrs += s("gen_ai.operation.name", "chat")
                attrs += s("gen_ai.request.model", model)
                d.provider?.let { attrs += s("gen_ai.provider.name", it) }
                d.usage?.let { u ->
                    attrs += s("rtok.api", u.api)
                    attrs += i("gen_ai.usage.input_tokens", u.input)
                    attrs += i("gen_ai.usage.output_tokens", u.output)
                    attrs += i("gen_ai.usage.cache_read.input_tokens", u.cacheRead)
                    attrs += i("gen_ai.usage.cache_creation.input_tokens", u.cacheCreate)
                }
                content.add("gen_ai.input.messages", pick(req, listOf("messages", "input")), reqArchive)
                content.add("gen_ai.output.messages", pick(resp, listOf("content", "choices", "output")), respArchive)
                "chat $model"
            }

            else -> {
                "${call.surface} ${call.name.orEmpty()}"
            }
        }
    val events =
        d.tokens.map { t ->
            val a = mutableListOf(s("rtok.phase", t.phase), s("rtok.source", t.source), i("rtok.tokens", t.nTokens))
            t.plugin?.let { a += s("rtok.plugin", it) }
            t.bytes?.let { a += i("rtok.bytes", it) }
            Event("rtok.plugin.run", secondsToNs(t.ts), a)
        } +
            d.measurements.map { m ->
                val a =
                    mutableListOf(
                        s("rtok.plugin", m.plugin),
                        s("rtok.kind", m.kind),
                        i("rtok.before_bytes", m.beforeBytes),
                        i("rtok.after_bytes", m.afterBytes),
                        i("rtok.est_before", m.estBefore.toLong()),
                        i("rtok.est_after", m.estAfter.toLong()),
                        i("rtok.tokens.saved", m.estBefore.toLong() - m.estAfter.toLong()),
                    )
                m.refId?.let { a += s("rtok.ref_id", it) }
                Event("rtok.measurement", start, a)
            }
    val status =
        if (call.ok != 0) {
            Status.Unset
        } else {
            attrs += s("error.type", "_OTHER")
            Status.Error(call.error ?: "error")
        }
    return Span(traceId(call.sessionId), spanId("call", call.id.toString()), spanId("session", call.sessionId), name, kind, start, end, attrs, events, status)
}

private const val SEV_TRACE = 1
private const val SEV_DEBUG = 5
private const val SEV_INFO = 9
private const val SEV_WARN = 13
private const val SEV_ERROR = 17

/** One `logs` row → one log record on the session's trace. */
fun logRecord(row: LogRow): LogRecord {
    val level = row.level.lowercase()
    val severity =
        when (level) {
            "trace" -> SEV_TRACE
            "debug" -> SEV_DEBUG
            "warn", "warning" -> SEV_WARN
            "error" -> SEV_ERROR
            else -> SEV_INFO
        }
    val attrs = mutableListOf(s("rtok.source", row.source), s("rtok.name", row.name))
    row.plugin?.let { attrs += s("rtok.plugin", it) }
    row.fields?.let { attrs += s("rtok.fields", it) }
    return LogRecord(secondsToNs(row.ts), severity, level.uppercase(), row.message, attrs, row.session?.let(::traceId), row.callId?.let { spanId("call", it.toString()) })
}
