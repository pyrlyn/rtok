@file:Suppress("MagicNumber")

package rtok.proxy

import kotlinx.serialization.json.*
import kotlin.test.*

/** Port of the Wire tests in src/proxy/gemini.rs. */
class GeminiWireTest {
    @Test fun geminiMatchesGenerateAndStreamActionsOnly() {
        assertTrue(GeminiWire.matches("/v1beta/models/gemini-2.0-flash:generateContent"))
        assertTrue(GeminiWire.matches("/v1/models/gemini-2.0-flash:streamGenerateContent"))
        for (p in listOf("/v1/messages", "/v1/chat/completions", "/v1/responses", "/v1beta/models")) assertFalse(GeminiWire.matches(p), p)
    }

    @Test fun geminiModelComesFromThePath() {
        assertEquals("gemini-2.0-flash", GeminiWire.model("/v1beta/models/gemini-2.0-flash:generateContent", null))
        assertEquals("gemini-2.0-flash", GeminiWire.model("/v1beta/models/gemini-2.0-flash:streamGenerateContent", js("{}")))
        assertNull(GeminiWire.model("/v1beta/models", null))
    }

    @Test fun geminiKeysFunctionResponsesByNameAndCountsUserTurns() {
        val r =
            req(
                GeminiWire,
                """{"contents":[
            {"role":"user","parts":[{"text":"hi"}]},
            {"role":"model","parts":[{"functionCall":{"name":"read","args":{}}}]},
            {"role":"user","parts":[{"functionResponse":{"name":"read","response":"old"}}]},
            {"role":"model","parts":[{"functionCall":{"name":"run","args":{}}}]},
            {"role":"user","parts":[{"functionResponse":{"name":"run","response":"live"}}]}]}""",
            ).toolResults()
        assertEquals(listOf("read" to 1, "run" to 0), r.map { it.id to it.turn })
        assertEquals(JsonPrimitive("old"), r[0].content)
    }

    @Test fun geminiResponseWithoutANameIsNotAResult() = assertTrue(req(GeminiWire, """{"contents":[{"role":"user","parts":[{"functionResponse":{"response":"x"}}]}]}""").toolResults().isEmpty())

    @Test fun geminiReadsUsageFromBodyArrayAndSse() {
        val usage = """{"usageMetadata":{"promptTokenCount":12,"cachedContentTokenCount":7,"candidatesTokenCount":3,"totalTokenCount":15}}"""
        val expected = Usage(5, 0, 7, 3)
        assertEquals(expected, GeminiWire.usageFromBody(js(usage)))
        assertEquals(expected, GeminiWire.usageFromSse(js(usage)))
        assertEquals(expected, GeminiWire.usageFromBody(js("""[{"candidates":[]},$usage]""")))
        assertNull(GeminiWire.usageFromBody(js("""[{"candidates":[]}]""")))
        assertEquals(15, GeminiWire.providerTotal(expected))
    }

    @Test fun geminiThinkingTokensCountAsOutput() {
        val usage = js("""{"usageMetadata":{"promptTokenCount":12,"candidatesTokenCount":3,"thoughtsTokenCount":40,"totalTokenCount":55}}""")
        val expected = Usage(12, 0, 0, 43)
        assertEquals(expected, GeminiWire.usageFromBody(usage))
        assertEquals(expected, GeminiWire.usageFromSse(usage))
        assertEquals(55, GeminiWire.providerTotal(expected), "matches totalTokenCount")
    }
}
