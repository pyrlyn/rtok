// Port of `audit_corpus` in src/proxy/semantic_cache.rs: every ordered pair of a corpus whose prompts differ but
// share a scope, scored by the semantic tier; a pair over the threshold with different responses is a false hit.
package rtok.proxy

import kotlinx.serialization.json.*
import rtok.config.SemanticCache
import rtok.store.SerdeJson

object CacheAudit {
    data class Report(
        val falseHitPairs: Int,
        val semanticPairs: Int,
        val hitRate: Float,
    )

    private class Entry(
        val hash: List<Byte>,
        val scope: List<Byte>,
        val embedding: FloatArray,
        val response: List<Byte>,
    )

    /** Audit `dir/corpus.json`; a failure is its message. */
    fun audit(
        dir: String,
        cfg: SemanticCache,
    ): Result<Report> =
        runCatching {
            val entries = load(dir)
            val n = entries.size
            var semantic = 0
            var falseHits = 0
            for (i in 0 until n) {
                for (j in 0 until n) {
                    val (a, b) = entries[i] to entries[j]
                    val comparable = i != j && a.hash != b.hash && a.scope == b.scope && cfg.embedBackend != "hash"
                    if (comparable && ResponseCache.cosine(a.embedding, b.embedding) >= cfg.threshold) {
                        semantic++
                        if (a.response != b.response) falseHits++
                    }
                }
            }
            val pairs = n * maxOf(n - 1, 0)
            Report(falseHits, semantic, if (pairs == 0) 0f else semantic.toFloat() / pairs)
        }

    private fun load(dir: String): List<Entry> {
        val doc = SerdeJson.parse(rtok.sys.readToString("$dir/corpus.json")) ?: error("corpus.json is not JSON")
        val rows = (doc as? JsonObject)?.get("entries") as? JsonArray ?: error("corpus.json missing entries")
        val wire = Wires.forPath("/v1/messages")!!
        return rows.map { row ->
            val id = row.str("id") ?: error("entry id")
            val body = SerdeJson.parse(rtok.sys.readToString("$dir/${row.str("request") ?: error("entry request")}")) ?: error("$id request json")
            val prompt = CacheKey.buildPrompt(wire, body, SemanticCache()) ?: error("$id prompt")
            val response = rtok.sys.readBytes("$dir/${row.str("response") ?: error("entry response")}")
            val embedding =
                ((row as JsonObject)["embedding"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.toFloat() }
                    ?.toFloatArray()
                    ?: ResponseCache.embedVector(prompt)
            Entry(CacheKey.canonicalHash(prompt).toList(), CacheKey.scopeHash(prompt).toList(), embedding, response.toList())
        }
    }
}
