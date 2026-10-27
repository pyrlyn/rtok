@file:Suppress("MagicNumber")

package rtok.proxy

import rtok.config.SemanticCache
import rtok.config.envVar
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

private fun prompt(body: String) = CacheKey.buildPrompt(Wires.forPath("/v1/messages")!!, js(body), SemanticCache())!!

private fun ask(t: String) = prompt("""{"model":"m","messages":[{"role":"user","content":${kotlinx.serialization.json.JsonPrimitive(t)}}]}""")

private val fixture = SemanticCache(embedBackend = "fixture")

/** Port of the cache tests in src/proxy/semantic_cache.rs (rstest cases as loops). */
class ResponseCacheTest {
    @Test fun directHitOnExactReplay() {
        val p = prompt("""{"model":"claude-test","messages":[{"role":"user","content":"hello"}]}""")
        val c = ResponseCache()
        c.store(p, SemanticCache(), "resp".encodeToByteArray(), "application/json", 200)
        val hit = c.lookup(p, SemanticCache())!!
        assertTrue(hit.direct)
        assertEquals("resp", hit.response.decodeToString())
    }

    @Test fun p9FixtureAuditZeroFalseHits() {
        val cfg = SemanticCache(enabled = true, threshold = 0.99f, embedBackend = "fixture")
        val r = CacheAudit.audit("${envVar("RTOK_FIXTURES")}/p9_semantic_cache_corpus", cfg).getOrThrow()
        assertEquals(0, r.falseHitPairs, "false hits at ${r.hitRate} hit_rate")
    }

    @Test fun anErrorResponseIsNeverCached() {
        val p = ask("hello")
        val c = ResponseCache()
        for (s in listOf(429, 500, 529)) {
            c.store(p, SemanticCache(), "overloaded".encodeToByteArray(), null, s)
            assertNull(c.lookup(p, SemanticCache()), "$s was cached")
        }
    }

    @Test fun hashBackendSkipsSemanticTier() {
        val c = ResponseCache()
        c.store(ask("a"), SemanticCache(), "ra".encodeToByteArray(), null, 200)
        assertNull(c.lookup(ask("b"), SemanticCache()))
    }

    /** T532: fields the flattened text drops still tell two requests apart, in both tiers. */
    @Test fun typedFieldsJoinTheCacheKey() {
        val cases =
            listOf(
                Triple("""[{"type":"image_url","image_url":{"url":"u","detail":"low"}}]""", """[{"type":"image_url","image_url":{"url":"u","detail":"high"}}]""", null),
                Triple(
                    """[{"type":"tool_result","tool_use_id":"t","content":"OK","is_error":false}]""",
                    """[{"type":"tool_result","tool_use_id":"t","content":"OK","is_error":true}]""",
                    null,
                ),
                Triple("\"hi\"", "\"hi\"", "alice" to "bob"),
                Triple("""[{"type":"text","text":"tool_result t: OK"}]""", """[{"type":"tool_result","tool_use_id":"t","content":"OK"}]""", null),
            )
        for ((a, b, names) in cases) {
            fun p(
                content: String,
                name: String?,
            ) = prompt("""{"model":"m","messages":[{"role":"user","content":$content${name?.let { ",\"name\":\"$it\"" }.orEmpty()}}]}""")
            val (pa, pb) = p(a, names?.first) to p(b, names?.second)
            assertNotEquals(CacheKey.canonicalHash(pa).toList(), CacheKey.canonicalHash(pb).toList(), a)
            val c = ResponseCache()
            c.store(pa, fixture, "first".encodeToByteArray(), null, 200)
            assertNull(c.lookup(pb, fixture), "both tiers miss: $a")
            assertEquals("first", c.lookup(pa, fixture)!!.response.decodeToString())
        }
    }

    @Test fun storeDropsExpiredEntriesAndReplacesItsOwn() {
        val clock = TestTimeSource()
        val c = ResponseCache(clock)
        c.store(ask("a"), fixture, "ra".encodeToByteArray(), null, 200)
        c.store(ask("a"), fixture, "ra".encodeToByteArray(), null, 200)
        assertEquals(1, c.sizes.second, "a replay replaces, it does not stack")
        clock += (fixture.ttlS.toLong() + 1).seconds
        c.store(ask("b"), fixture, "rb".encodeToByteArray(), null, 200)
        assertEquals(1 to 1, c.sizes)
    }

    @Test fun theSameQuestionUnderAnotherSystemPromptMisses() {
        fun sys(s: String) = prompt("""{"model":"m","system":"$s","messages":[{"role":"user","content":"hello"}]}""")
        val c = ResponseCache()
        c.store(sys("Answer in French"), fixture, "bonjour".encodeToByteArray(), null, 200)
        assertNull(c.lookup(sys("Answer in German"), fixture))
        assertNotNull(c.lookup(sys("Answer in French"), fixture))
    }

    @Test fun onlyNearIdenticalPromptsClearTheThreshold() {
        val cases =
            listOf(
                Triple("add a test for the parser", "delete the build directory", false),
                Triple("rename foo to bar", "rename bar to foo", false),
                Triple("is x > 5", "is x < 5", false),
                Triple("исправь баг в foo.rs", "удали foo.rs", false),
                Triple("Fix the  bug", "fix the bug", true),
            )
        for ((a, b, hit) in cases) {
            val sim = ResponseCache.cosine(ResponseCache.embedVector(ask(a)), ResponseCache.embedVector(ask(b)))
            assertEquals(hit, sim >= SemanticCache().threshold, "$a / $b: $sim")
            assertTrue(hit || sim < 0.9f, "$sim")
        }
    }

    @Test fun emptyCorpusAuditsToZero() {
        val dir = rtok.support.tmpDir("empty-corpus")
        rtok.sys.writeString("$dir/corpus.json", """{"entries": []}""")
        val r = CacheAudit.audit(dir, SemanticCache()).getOrThrow()
        assertEquals(0 to 0f, r.semanticPairs to r.hitRate)
    }
}
