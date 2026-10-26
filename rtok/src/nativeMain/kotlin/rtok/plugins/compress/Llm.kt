// Port of the transport-free parts of src/plugins/compress/llm.rs — the P28 compressor's prompt, version
// key, code detection and credential rules. The HTTP client and the background queue (llm.rs `Compressor`,
// queue.rs) wait for an approved HTTP client / async runtime; until then `[plugins.compress.llm]` only
// changes which stored summaries are read.
package rtok.plugins.compress

import kotlinx.serialization.json.jsonPrimitive
import rtok.config.Toml
import rtok.store.hexSha256
import rtok.sys.rustLines

private const val VERSION_HASH_CHARS = 12

/** The wire provider the compressor request is shaped for. */
private const val PROVIDER = "anthropic"

/** The compressor prompt (data, not code): `system` and `user` from `prompt.toml`. */
class Prompt(
    val system: String,
    val user: String,
) {
    companion object {
        val DEFAULT: Prompt by lazy {
            val doc = Toml.parse(PROMPT_FILE)

            fun field(k: String) =
                doc
                    .getValue(k)
                    .jsonPrimitive.content
                    .trim()
            Prompt(field("system"), field("user"))
        }
    }
}

/** The cache key beside the archive id: the prompt file's hash and the model. */
fun version(model: String) = "${hexSha256(PROMPT_FILE.encodeToByteArray()).take(VERSION_HASH_CHARS)}+$model"

/** Credential headers of the agent turn, forwarded to the compressor call; held in memory only. */
class Auth private constructor(
    val headers: List<Pair<String, String>>,
) {
    companion object {
        private val HEADERS = listOf("x-api-key", "authorization", "anthropic-version", "anthropic-beta")

        /** Null without a credential: the call would only fail. */
        fun of(headers: List<Pair<String, String>>): Auth? {
            fun has(n: String) = headers.any { it.first.equals(n, ignoreCase = true) }
            return if (has("x-api-key") || has("authorization")) Auth(headers) else null
        }

        /** Credential and version headers of an Anthropic-wire request; any other provider's key never crosses. */
        fun fromHeaders(
            headers: List<Pair<String, String>>,
            provider: String?,
        ): Auth? {
            if (provider != PROVIDER) return null
            return of(HEADERS.mapNotNull { n -> headers.firstOrNull { it.first.equals(n, ignoreCase = true) }?.let { n to it.second } })
        }
    }
}

/** A diff or source file: these wait for `code = true`, since a summary loses the lines the agent edits against. */
fun isCode(text: String): Boolean {
    val lines = text.rustLines().filter { it.isNotBlank() }
    if (lines.any { it.startsWith("diff --git ") || it.startsWith("@@ ") }) return true
    val coded = lines.count { it.trimEnd().let { t -> t.endsWith('{') || t.endsWith('}') || t.endsWith(';') } }
    return coded * 100 >= lines.size * 35
}
