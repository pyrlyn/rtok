// Port of the cache half of src/proxy/semantic_cache.rs (P31): an opt-in two-tier response cache. The direct
// tier is keyed by the canonical hash; the semantic tier (off with `embed_backend = "hash"`) compares the
// last user message's feature-hashed vector among prompts sharing everything else. Only 2xx is stored.
package rtok.proxy

import rtok.config.SemanticCache
import rtok.plugin.sdk.Measurement
import rtok.store.SEMANTIC_CACHE_HIT_KIND
import rtok.store.hashEmbed
import kotlin.math.sqrt
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class CacheHit(
    val response: ByteArray,
    val contentType: String?,
    val status: Int,
    val similarity: Float,
    val direct: Boolean,
    val hashHex: String,
)

class ResponseCache(
    private val clock: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    private class Entry(
        val hash: List<Byte>,
        val response: ByteArray,
        val contentType: String?,
        val status: Int,
        val at: ComparableTimeMark,
    )

    private class Row(
        val vector: FloatArray,
        val scope: List<Byte>,
        val entry: Entry,
    )

    private val direct = LinkedHashMap<List<Byte>, Entry>()
    private val semantic = mutableListOf<Row>()

    internal val sizes get() = direct.size to semantic.size

    private fun SemanticCache.ttl() = maxOf(ttlS, 1uL).toLong().seconds

    private fun Entry.fresh(cfg: SemanticCache) = at.elapsedNow() < cfg.ttl()

    fun lookup(
        prompt: CachePrompt,
        cfg: SemanticCache,
    ): CacheHit? {
        val hash = CacheKey.canonicalHash(prompt).toList()
        val exact = direct[hash]?.takeIf { it.fresh(cfg) }
        return exact?.let { hit(it, 1f, true) } ?: if (cfg.embedBackend == "hash") null else semanticHit(prompt, cfg)
    }

    private fun semanticHit(
        prompt: CachePrompt,
        cfg: SemanticCache,
    ): CacheHit? {
        val query = embedVector(prompt)
        val scope = CacheKey.scopeHash(prompt).toList()
        val best =
            semantic
                .filter { it.entry.fresh(cfg) && it.scope == scope }
                .map { cosine(it.vector, query) to it.entry }
                .filter { it.first >= cfg.threshold }
                .maxByOrNull { it.first }
        return best?.let { (sim, e) -> hit(e, sim, false) }
    }

    fun store(
        prompt: CachePrompt,
        cfg: SemanticCache,
        response: ByteArray,
        contentType: String?,
        status: Int,
    ) {
        // A 429/529/500 replayed for `ttl_s` is an outage the cache would prolong.
        if (status !in HTTP_OK) return
        val hash = CacheKey.canonicalHash(prompt).toList()
        direct.entries.removeAll { !it.value.fresh(cfg) }
        semantic.removeAll { it.entry.hash == hash || !it.entry.fresh(cfg) }
        val e = Entry(hash, response.copyOf(), contentType, status, clock.markNow())
        if (cfg.embedBackend != "hash") semantic += Row(embedVector(prompt), CacheKey.scopeHash(prompt).toList(), e)
        direct[hash] = e
    }

    private fun hit(
        e: Entry,
        sim: Float,
        direct: Boolean,
    ) = CacheHit(e.response, e.contentType, e.status, sim, direct, CacheKey.hex8(e.hash.toByteArray()))

    companion object {
        fun measurement(
            hit: CacheHit,
            callId: Int?,
            nbytes: Int,
        ): Measurement {
            val est = maxOf(nbytes / BYTES_PER_TOKEN, 1).toUInt()
            val r = kotlin.math.round(hit.similarity.toDouble() * SIM_SCALE).toLong()
            val sim = "${r / SIM_SCALE.toLong()}." + (r % SIM_SCALE.toLong()).toString().padStart(4, '0')
            return Measurement("proxy", SEMANTIC_CACHE_HIT_KIND, nbytes.toULong(), nbytes.toULong(), est, est, "$sim:${hit.hashHex}", callId)
        }

        private const val SIM_SCALE = 10000.0
        private const val BYTES_PER_TOKEN = 4
        private const val HEX = 16
        private val HTTP_OK = 200..299
        private const val DIMS = 256u

        /** Words, symbols and adjacent pairs of the last user message, feature-hashed. Case and spacing match. */
        internal fun embedVector(p: CachePrompt): FloatArray {
            val last =
                p.messages
                    .lastOrNull { it.first == "user" }
                    ?.second
                    .orEmpty()
            val words = mutableListOf<String>()
            val word = StringBuilder()
            var i = 0
            val text = "$last "
            while (i < text.length) {
                val pair = text[i].isHighSurrogate() && i + 1 < text.length
                val c = text[i]
                val cp = if (pair) "$c${text[i + 1]}".let { codePoint(it) } else c.code
                i += if (pair) 2 else 1
                if (!pair && c.isLetterOrDigit()) {
                    word.append(c.lowercase())
                    continue
                }
                if (word.isNotEmpty()) words += word.toString().also { word.clear() }
                if (pair || !c.isWhitespace()) words += "u" + cp.toString(HEX)
            }
            val pairs = words.zipWithNext { a, b -> a + b }
            return hashEmbed((words + pairs).joinToString(" "), DIMS)
        }

        @Suppress("MagicNumber") // UTF-16 surrogate arithmetic
        private fun codePoint(pair: String) = ((pair[0].code - 0xD800) shl 10) + (pair[1].code - 0xDC00) + 0x10000

        internal fun cosine(
            a: FloatArray,
            b: FloatArray,
        ): Float {
            val n = minOf(a.size, b.size)
            var dot = 0f
            for (k in 0 until n) dot += a[k] * b[k]
            val na = sqrt(a.fold(0f) { s, x -> s + x * x })
            val nb = sqrt(b.fold(0f) { s, x -> s + x * x })
            return if (n == 0 || na == 0f || nb == 0f) 0f else dot / (na * nb)
        }
    }
}
