@file:Suppress("MagicNumber")

package rtok.proxy

import kotlinx.serialization.json.*
import rtok.config.SemanticCache
import kotlin.test.*

private val anthropic get() = Wires.forPath("/v1/messages")!!

internal fun key(
    body: String,
    wire: Wire = anthropic,
) = CacheKey.canonicalHash(CacheKey.buildPrompt(wire, js(body), SemanticCache())!!).toList()

/** Port of the cache-key tests in src/proxy/semantic_cache.rs. */
class CacheKeyTest {
    /** Audit 2026-10-19: one org key used from two OpenAI projects shared cached answers. */
    @Test fun openaiProjectIsPartOfTheCaller() {
        fun with(p: String) = CacheKey.callerIdentity(listOf("authorization" to "Bearer sk-org", "openai-project" to p))
        assertNotEquals(with("proj_a"), with("proj_b"))
        assertEquals(with("proj_a"), with("proj_a"))
    }

    @Test fun toolResultTextJoinsTheCacheKey() {
        fun body(r: String) =
            """{"model":"m","messages":[{"role":"assistant","content":[{"type":"tool_use","id":"t","name":"run","input":{}}]},
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":"$r"},{"type":"text","text":"summarize the result"}]}]}"""
        for (b in listOf(body("tests passed: 200 ok"), body("tests FAILED: 1 broken"))) assertTrue(CacheKey.eligible(js(b), SemanticCache()))
        assertNotEquals(key(body("tests passed: 200 ok")), key(body("tests FAILED: 1 broken")))
    }

    @Test fun toolUseIdsJoinTheCacheKey() {
        fun body(id: String) =
            """{"model":"m","messages":[{"role":"assistant","content":[{"type":"tool_use","id":"$id","name":"run","input":{}}]},
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"$id","content":"same output"}]}]}"""
        assertNotEquals(key(body("t-1")), key(body("t-2")))
    }

    @Test fun toolUseInputJoinsTheCacheKey() {
        fun body(input: String) =
            """{"model":"m","messages":[{"role":"assistant","content":[{"type":"tool_use","id":"t","name":"run","input":$input}]},
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":"same output"}]}]}"""
        assertNotEquals(key(body("""{"cmd":"ls"}""")), key(body("""{"cmd":"rm -rf /"}""")))
        assertEquals(key(body("""{"a":1,"b":2}""")), key(body("""{"b":2,"a":1}""")))
    }

    @Test fun thinkingBlockJoinsTheCacheKey() {
        fun body(t: String) = """{"model":"m","messages":[{"role":"assistant","content":[{"type":"thinking","thinking":"$t","signature":"sig"},{"type":"text","text":"the answer"}]}]}"""
        assertNotEquals(key(body("maybe it's 4")), key(body("maybe it's 5")))
    }

    @Test fun openaiToolCallsJoinTheCacheKey() {
        val chat = Wires.forPath("/v1/chat/completions")!!

        fun body(args: String) = """{"model":"m","messages":[{"role":"assistant","tool_calls":[{"id":"c1","type":"function","function":{"name":"run","arguments":${JsonPrimitive(args)}}}]}]}"""
        assertNotEquals(key(body("{\"cmd\":\"ls\"}"), chat), key(body("{\"cmd\":\"rm -rf /\"}"), chat))
    }

    @Test fun imageBlocksHashIntoTheCacheKey() {
        fun body(d: String) = """{"model":"m","messages":[{"role":"user","content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"$d"}},{"type":"text","text":"what is in the picture?"}]}]}"""
        assertNotEquals(key(body("aB3dE5g7".repeat(600))), key(body("7g5E3dBa".repeat(600))))
    }

    @Test fun nonBase64ImageAndDocumentSourcesJoinTheCacheKey() {
        fun k(
            kind: String,
            source: String,
        ) = key("""{"model":"m","messages":[{"role":"user","content":[{"type":"$kind","source":$source},{"type":"text","text":"describe it"}]}]}""")
        assertNotEquals(k("image", """{"type":"url","url":"https://a.test/1.png"}"""), k("image", """{"type":"url","url":"https://a.test/2.png"}"""))
        assertNotEquals(k("document", """{"type":"file","file_id":"file_1"}"""), k("document", """{"type":"file","file_id":"file_2"}"""))
        assertNotEquals(
            k("document", """{"type":"text","media_type":"text/plain","data":"alpha"}"""),
            k("document", """{"type":"text","media_type":"text/plain","data":"beta"}"""),
        )
        assertNotEquals(k("document", """{"type":"content","content":"alpha"}"""), k("document", """{"type":"content","content":"beta"}"""))
        assertEquals(k("image", """{"type":"url","url":"https://a.test/1.png"}"""), k("image", """{"url":"https://a.test/1.png","type":"url"}"""))
    }

    @Test fun samplingParamsJoinTheCacheKey() {
        fun h(
            field: String,
            v: String,
        ) = key("""{"model":"m","messages":[{"role":"user","content":"hi"}],"$field":$v}""")
        val pairs =
            listOf(
                Triple("max_tokens", "256", "1024"),
                Triple("temperature", "0.0", "1.0"),
                Triple("top_p", "0.1", "0.9"),
                Triple("tool_choice", """{"type":"auto"}""", """{"type":"none"}"""),
                Triple("thinking", """{"type":"enabled","budget_tokens":1024}""", """{"type":"disabled"}"""),
                Triple("stop_sequences", """["a"]""", """["b"]"""),
            )
        for ((f, a, b) in pairs) assertNotEquals(h(f, a), h(f, b), f)
        assertEquals(
            key("""{"model":"m","max_tokens":256,"temperature":0.5,"messages":[{"role":"user","content":"hi"}]}"""),
            key("""{"temperature":0.5,"messages":[{"role":"user","content":"hi"}],"max_tokens":256,"model":"m"}"""),
        )

        fun uid(u: String) = key("""{"model":"m","messages":[{"role":"user","content":"hi"}],"metadata":{"user_id":"$u"}}""")
        assertEquals(uid("u1"), uid("u2"), "metadata.user_id must not split the cache")
    }

    /** T533: method, path and query each change the scope; the raw target never appears in it. */
    @Test fun theCacheScopeNamesMethodAndTarget() {
        val base = CacheKey.cacheScope(emptyList(), "POST", "https://a.test/v1/messages?key=secret")
        assertEquals(base, CacheKey.cacheScope(emptyList(), "POST", "https://a.test/v1/messages?key=secret"))
        for ((m, t) in listOf(
            "GET" to "https://a.test/v1/messages?key=secret",
            "POST" to "https://b.test/v1/messages?key=secret",
            "POST" to "https://a.test/v1/complete?key=secret",
            "POST" to "https://a.test/v1/messages?key=other",
        )) {
            assertNotEquals(base, CacheKey.cacheScope(emptyList(), m, t), "$m $t")
        }
        assertFalse("secret" in base)
    }

    @Test fun keyOrderAndCacheControlDoNotSplitTheKey() {
        fun ask(block: String) = key("""{"model":"m","system":[{"type":"text","text":"s"}],"messages":[{"role":"user","content":[$block]}]}""")
        val plain = ask("""{"type":"text","text":"hello"}""")
        assertEquals(plain, ask("""{"text":"hello","type":"text"}"""))
        assertEquals(plain, ask("""{"type":"text","text":"hello","cache_control":{"type":"ephemeral"}}"""))
    }
}
