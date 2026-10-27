// Port of src/batch.rs: `rtok batch submit|status|fetch` (T385.12.1), a thin client for the providers' own Batch APIs
// that sends every call through `rtok proxy`, so the proxy's `batch` lane sees them. It never converts a sync request
// into a Batch job and never rewrites a JSONL line: the Anthropic body is the caller's lines joined into `requests`,
// the OpenAI file is uploaded as it is. A fetched result is written whole once the download is complete, so a torn
// download never leaves a file behind (Rust streams and removes the partial file).
package rtok

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import rtok.http.HttpClient
import rtok.http.HttpClientFactory
import rtok.http.HttpException
import rtok.http.HttpRequest
import rtok.http.HttpResponse
import rtok.http.HttpSettings
import rtok.json.StrictJson
import rtok.sys.createNewBytes
import rtok.sys.exists
import rtok.sys.readToString

enum class BatchProvider(
    /** The environment variable holding this provider's API key. */
    val keyEnv: String,
) {
    Anthropic("ANTHROPIC_API_KEY"),
    Openai("OPENAI_API_KEY"),
    ;

    companion object {
        /** The provider that issued [id]: Message Batch ids start with `msgbatch_`, OpenAI's with `batch_`. */
        fun ofId(id: String): BatchProvider = if (id.startsWith("msgbatch_")) Anthropic else Openai

        /** clap's `ValueEnum` names. */
        fun parse(name: String): BatchProvider? = entries.firstOrNull { it.name.lowercase() == name }
    }
}

class BatchException(
    message: String,
) : Exception(message)

private fun bail(message: String): Nothing = throw BatchException(message)

/** One provider reached through one proxy base URL. */
class BatchApi(
    base: String,
    private val provider: BatchProvider,
    key: String,
    private val http: HttpClientFactory,
) {
    private val base: String
    private val key: String

    init {
        if (!(base.startsWith("http://") || base.startsWith("https://"))) bail("proxy url must start with http:// or https://: $base")
        if (key.isBlank()) bail("${provider.keyEnv} is not set")
        this.base = base.trimEnd('/')
        this.key = key.trim()
    }

    private fun headers(): List<Pair<String, String>> =
        when (provider) {
            BatchProvider.Anthropic -> listOf("x-api-key" to key, "anthropic-version" to "2023-06-01")
            BatchProvider.Openai -> listOf("authorization" to "Bearer $key")
        }

    /** reqwest's `Client::new()` sets no timeout. */
    private fun <T> client(body: suspend (HttpClient) -> T): T = http.create(HttpSettings(NO_LIMIT, NO_LIMIT, NO_LIMIT)).use { c -> runBlocking { body(c) } }

    private fun send(
        post: Boolean,
        path: String,
        extra: List<Pair<String, String>> = emptyList(),
        body: ByteArray = ByteArray(0),
    ): HttpResponse {
        val req = HttpRequest("$base$path", headers() + extra, body)
        val resp =
            try {
                client { if (post) it.post(req) else it.get(req) }
            } catch (e: HttpException) {
                bail("cannot reach the proxy (is `rtok proxy` running?): ${e.message}")
            }
        if (resp.isSuccess) return resp
        bail("${statusLine(resp.status)}: ${resp.text().take(ERROR_SNIPPET)}")
    }

    private fun text(
        post: Boolean,
        path: String,
        extra: List<Pair<String, String>> = emptyList(),
        body: ByteArray = ByteArray(0),
    ): String = send(post, path, extra, body).text()

    private fun jsonPost(
        path: String,
        body: String,
    ) = text(true, path, listOf("content-type" to "application/json"), body.encodeToByteArray())

    /** Create a batch from the JSONL at [file]; returns the provider's batch object. */
    fun submit(file: String): String {
        val raw = runCatching { readToString(file) }.getOrElse { bail("read $file: ${it.message}") }
        val (lines, url) = validate(raw, provider)
        return when (provider) {
            BatchProvider.Anthropic -> {
                jsonPost("/v1/messages/batches", "{\"requests\":[${lines.joinToString(",")}]}")
            }

            BatchProvider.Openai -> {
                if (MULTIPART_BOUNDARY in raw) bail("$file contains the multipart boundary $MULTIPART_BOUNDARY")
                val b = MULTIPART_BOUNDARY
                val form =
                    "--$b\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\nbatch\r\n" +
                        "--$b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"batch.jsonl\"\r\n" +
                        "Content-Type: application/jsonl\r\n\r\n$raw\r\n--$b--\r\n"
                val uploaded = StrictJson.parse(text(true, "/v1/files", listOf("content-type" to "multipart/form-data; boundary=$b"), form.encodeToByteArray()))
                val fileId = ((uploaded as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bail("the upload answered without a file id")
                val create =
                    buildJsonObject {
                        put("input_file_id", fileId)
                        put("endpoint", url)
                        put("completion_window", "24h")
                    }
                jsonPost("/v1/batches", create.toString())
            }
        }
    }

    fun status(id: String): String {
        checkId(id)
        return text(false, batchPath(id))
    }

    /** Writes the results of a finished batch to [out], which must not exist yet; returns the bytes written. */
    fun fetch(
        id: String,
        out: String,
    ): Long {
        checkId(id)
        // Before the first request: a refused target should not cost a download.
        if (exists(out)) bail("$out exists; results are never overwritten")
        val path =
            when (provider) {
                BatchProvider.Anthropic -> {
                    "${batchPath(id)}/results"
                }

                BatchProvider.Openai -> {
                    val batch = StrictJson.parse(status(id)) as? JsonObject
                    val str = { k: String -> (batch?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content }
                    val file = str("output_file_id") ?: bail("batch $id has no output file yet (status: ${str("status") ?: "unknown"})")
                    checkId(file)
                    "/v1/files/$file/content"
                }
            }
        val body = send(false, path).body
        runCatching { createNewBytes(out, body) }.getOrElse { bail("create $out (it must not exist): ${it.message}") }
        return body.size.toLong()
    }

    private fun batchPath(id: String) =
        when (provider) {
            BatchProvider.Anthropic -> "/v1/messages/batches/$id"
            BatchProvider.Openai -> "/v1/batches/$id"
        }

    companion object {
        const val MULTIPART_BOUNDARY = "rtok-batch-7f3a9c1e"

        /** How much of an error body is echoed; a provider error is short, a proxy HTML page is not. */
        const val ERROR_SNIPPET = 300
        private const val NO_LIMIT = Long.MAX_VALUE

        /** An id goes into a URL path: keep it to the characters providers issue. */
        fun checkId(id: String) {
            if (id.isEmpty() || !id.all(::idChar)) bail("not a batch or file id: ${debugStr(id)}")
        }

        private fun idChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-'

        /** Rust's `{:?}` of a plain string. */
        private fun debugStr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        /**
         * The non-empty lines of [raw] once each is checked to be a request of [provider]'s shape, plus the endpoint
         * every OpenAI line targets (empty for Anthropic).
         */
        fun validate(
            raw: String,
            provider: BatchProvider,
        ): Pair<List<String>, String> {
            val required =
                when (provider) {
                    BatchProvider.Anthropic -> listOf("custom_id", "params")
                    BatchProvider.Openai -> listOf("custom_id", "method", "url", "body")
                }
            val lines = ArrayList<String>()
            var url = ""
            // str::lines: split on \n, drop one trailing \r per line.
            raw.split('\n').let { if (raw.endsWith('\n')) it.dropLast(1) else it }.map { it.removeSuffix("\r") }.forEachIndexed { i, line ->
                if (line.isBlank()) return@forEachIndexed
                val n = i + 1
                val v = StrictJson.parseOrNull(line) ?: bail("line $n: not JSON")
                val obj = v as? JsonObject
                required.firstOrNull { obj?.get(it) == null }?.let { bail("line $n: missing `$it`") }
                if (provider == BatchProvider.Openai) {
                    val here = (obj!!["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                    if (url.isEmpty()) {
                        url = here
                    } else if (url != here) {
                        bail("line $n: url `$here` differs from `$url`; a batch targets one endpoint")
                    }
                }
                lines += line.trim()
            }
            if (lines.isEmpty()) bail("no requests in the file")
            return lines to url
        }

        /** reqwest's `StatusCode` display: the code and its canonical reason. */
        fun statusLine(code: Int): String = REASONS[code]?.let { "$code $it" } ?: "$code <unknown status code>"

        private val REASONS =
            mapOf(
                400 to "Bad Request",
                401 to "Unauthorized",
                403 to "Forbidden",
                404 to "Not Found",
                405 to "Method Not Allowed",
                408 to "Request Timeout",
                409 to "Conflict",
                413 to "Payload Too Large",
                415 to "Unsupported Media Type",
                422 to "Unprocessable Entity",
                429 to "Too Many Requests",
                500 to "Internal Server Error",
                501 to "Not Implemented",
                502 to "Bad Gateway",
                503 to "Service Unavailable",
                504 to "Gateway Timeout",
            )
    }
}
