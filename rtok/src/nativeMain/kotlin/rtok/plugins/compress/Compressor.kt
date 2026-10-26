// Port of src/plugins/compress/llm.rs `Compressor` — one model call per archived tool result (P28,
// T385.9.2), through rtok's own HTTP interface (Ktor adapter by default).
package rtok.plugins.compress

import kotlinx.serialization.json.*
import rtok.config.CompressLlm
import rtok.config.Config
import rtok.http.HttpClient
import rtok.http.HttpClientFactory
import rtok.http.HttpRequest
import rtok.http.HttpSettings
import rtok.http.ktor.KtorHttp
import rtok.recordLog
import rtok.store.*

private const val DEFAULT_ANTHROPIC_VERSION = "2023-06-01"

/** Rust `crate::lane::HEADER`: the proxy keeps internal calls out of the agent's accounting. */
private const val LANE_HEADER = "x-rtok-lane"
private const val CONNECT_TIMEOUT_MS = 10_000L

sealed class Outcome {
    data object Cached : Outcome()

    data class Skipped(
        val why: String,
    ) : Outcome()

    data class Rejected(
        val dropped: List<String>,
    ) : Outcome()

    data class Failed(
        val error: String,
    ) : Outcome()

    data object Stored : Outcome()
}

class Compressor private constructor(
    private val full: Config,
    private val http: HttpClient,
) : AutoCloseable {
    private val cfg: CompressLlm = full.plugins.compress.llm
    private val archiveDir = full.core.archiveDir

    /** `<url or the local proxy>/v1/messages`. */
    val url: String =
        run {
            val base =
                cfg.url
                    .trim()
                    .ifEmpty {
                        val host =
                            when (val b = full.proxy.bind) {
                                "0.0.0.0", "::", "" -> "127.0.0.1"
                                else -> b
                            }
                        "http://$host:${full.proxy.port}"
                    }.trimEnd('/')
            "$base/v1/messages"
        }

    /** The cache key half beside the archive id. */
    val version: String = version(cfg.model)

    companion object {
        /** Null while the flag is off, so a disabled build holds no client at all. */
        fun create(
            cfg: Config,
            http: HttpClientFactory = KtorHttp.FACTORY,
        ): Compressor? {
            if (!cfg.plugins.compress.llm.enabled) return null
            val read =
                cfg.plugins.compress.llm.timeoutS
                    .coerceAtLeast(1u)
                    .toLong() * 1000
            return Compressor(cfg, http.create(HttpSettings(CONNECT_TIMEOUT_MS, read, read)))
        }
    }

    /** Summarise unless cached or not worth a call; never throws, every failure is warned. */
    suspend fun run(
        store: Store,
        auth: Auth,
        archiveId: String,
    ): Outcome {
        val outcome = tryRun(store, auth, archiveId)
        when (outcome) {
            is Outcome.Failed -> {
                warn(store, "${short(archiveId)}: ${outcome.error}")
            }

            is Outcome.Rejected -> {
                warn(store, "${short(archiveId)}: summary dropped ${outcome.dropped.size} must-keep span(s), not stored: ${outcome.dropped.joinToString(" | ")}")
            }

            else -> {}
        }
        return outcome
    }

    private suspend fun tryRun(
        store: Store,
        auth: Auth,
        archiveId: String,
    ): Outcome {
        try {
            if (store.archiveSummary(archiveId, version) != null) return Outcome.Cached
        } catch (e: Exception) {
            return Outcome.Failed("cache read: ${e.message}")
        }
        val bytes =
            try {
                store.getArchive(archiveId, archiveDir)
            } catch (e: Exception) {
                return Outcome.Failed("archive read: ${e.message}")
            }
                ?: return Outcome.Skipped("archive payload missing")
        val text = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull() ?: return Outcome.Skipped("not text")
        if (bytes.size.toULong() > cfg.maxInputBytes) return Outcome.Skipped("too large")
        if (!cfg.code && isCode(text)) return Outcome.Skipped("diff or source")
        val spans = spans(text)
        if (spans.size.toUInt() > cfg.maxSpans) return Outcome.Skipped("too many must-keep spans")
        val summary =
            try {
                ask(auth, text, spans)
            } catch (e: Exception) {
                return Outcome.Failed(e.message ?: e.toString())
            }
        if (summary.isEmpty() || summary.encodeToByteArray().size >= bytes.size) return Outcome.Skipped("summary not shorter than the original")
        val gone = dropped(spans, summary)
        if (gone.isNotEmpty()) return Outcome.Rejected(gone.map { it.text })
        return try {
            store.putArchiveSummary(archiveId, version, summary)
            Outcome.Stored
        } catch (e: Exception) {
            Outcome.Failed("cache write: ${e.message}")
        }
    }

    /** One request on the `internal` lane; the model's text, trimmed. */
    internal suspend fun ask(
        auth: Auth,
        text: String,
        spans: List<Span>,
    ): String {
        val res = http.post(HttpRequest(url, requestHeaders(auth), requestBody(text, spans).toString().encodeToByteArray()))
        check(res.isSuccess) { "upstream status ${statusLine(res.status)}" }
        val reply = Json.parseToJsonElement(res.text()).jsonObject
        check((reply["stop_reason"] as? JsonPrimitive)?.contentOrNull != "max_tokens") { "summary cut off at max_output_tokens" }
        return (reply["content"] as? JsonArray)
            .orEmpty()
            .filter { (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "text" }
            .mapNotNull { (it.jsonObject["text"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .joinToString("")
            .trim()
    }

    internal fun requestHeaders(auth: Auth): List<Pair<String, String>> {
        val h = mutableListOf("content-type" to "application/json", LANE_HEADER to "internal")
        if (auth.headers.none { it.first.equals("anthropic-version", ignoreCase = true) }) h += "anthropic-version" to DEFAULT_ANTHROPIC_VERSION
        return h + auth.headers
    }

    /** Placeholders filled after splitting at `{{text}}`, so a tool result containing `{{must_keep}}` stays text. */
    internal fun requestBody(
        text: String,
        spans: List<Span>,
    ): JsonObject {
        val mustKeep = spans.joinToString("\n") { "- ${it.text}" }
        val p = Prompt.DEFAULT
        val at = p.user.indexOf("{{text}}")
        val (head, tail) = if (at < 0) p.user to "" else p.user.substring(0, at) to p.user.substring(at + "{{text}}".length)
        return buildJsonObject {
            put("model", cfg.model)
            put("max_tokens", cfg.maxOutputTokens.toLong())
            put("system", p.system)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", head.replace("{{must_keep}}", mustKeep) + text + tail)
                }
            }
        }
    }

    private fun warn(
        store: Store,
        message: String,
    ) = recordLog(full, store, null, null, "warn", "plugin", "compress", message)

    override fun close() = http.close()
}

private fun short(id: String) = id.take(12)

/** reqwest's `StatusCode` Display: `503 Service Unavailable`. */
private fun statusLine(code: Int) = STATUS_REASONS[code]?.let { "$code $it" } ?: "$code"

private val STATUS_REASONS =
    mapOf(
        400 to "Bad Request",
        401 to "Unauthorized",
        403 to "Forbidden",
        404 to "Not Found",
        408 to "Request Timeout",
        413 to "Payload Too Large",
        429 to "Too Many Requests",
        500 to "Internal Server Error",
        502 to "Bad Gateway",
        503 to "Service Unavailable",
        504 to "Gateway Timeout",
        529 to "<unknown status code>",
    )
