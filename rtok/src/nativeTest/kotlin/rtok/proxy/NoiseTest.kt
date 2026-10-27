package rtok.proxy

import kotlinx.serialization.json.*
import kotlin.test.*

/** Port of src/proxy/noise.rs tests. */
class NoiseTest {
    private val noisy = "\u001b[31mFAIL\u001b[0m a\u200bb\u0007 \r\n"
    private val clean = "FAIL ab \r\n"

    private fun j(v: String) = JsonPrimitive(v).toString()

    private fun stripped(
        dialect: rtok.plugin.sdk.ToolResults,
        body: String,
    ) = Json.parseToJsonElement(Noise.strip(dialect, body.encodeToByteArray()).decodeToString()).jsonObject

    @Test fun anthropicResultsAndUserTextAreCleaned() {
        val n = j(noisy)
        val body =
            """{"model":"m","n":1.5,"messages":[
            {"role":"user","content":[
              {"type":"tool_result","tool_use_id":"t","content":$n},
              {"type":"tool_result","tool_use_id":"u","content":[{"type":"text","text":$n}]},
              {"type":"text","text":$n}]},
            {"role":"assistant","content":[{"type":"text","text":$n}]}]}"""
        val got = stripped(AnthropicWire, body)
        val orig = Json.parseToJsonElement(body).jsonObject
        val blocks = got["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(clean, blocks[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(
            clean,
            blocks[1]
                .jsonObject["content"]!!
                .jsonArray[0]
                .jsonObject["text"]!!
                .jsonPrimitive.content,
        )
        assertEquals(clean, blocks[2].jsonObject["text"]!!.jsonPrimitive.content)
        // The model's own turns are never rewritten: thinking blocks are signed.
        assertEquals(orig["messages"]!!.jsonArray[1], got["messages"]!!.jsonArray[1])
        assertEquals(orig["model"] to orig["n"], got["model"] to got["n"])
    }

    @Test fun chatToolMessagesAreCleaned() {
        val got = stripped(OpenAiChatWire, """{"messages":[{"role":"tool","tool_call_id":"c","content":${j(noisy)}}]}""")
        assertEquals(
            clean,
            got["messages"]!!
                .jsonArray[0]
                .jsonObject["content"]!!
                .jsonPrimitive.content,
        )
    }

    @Test fun aCleanOrUnparseableBodyKeepsItsBytes() {
        // Spacing a re-serialisation would change.
        val cleanBody =
            """{ "messages": [ {"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":"ok\r\n  "}]} ] }"""
                .encodeToByteArray()
        assertSame(cleanBody, Noise.strip(AnthropicWire, cleanBody))
        val junk = "\u001b[31mnot json".encodeToByteArray()
        assertSame(junk, Noise.strip(AnthropicWire, junk))
    }

    @Test fun strippingTwiceChangesNothing() {
        val body = """{"messages":[{"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":${j(noisy)}}]}]}"""
        val once = Noise.strip(AnthropicWire, body.encodeToByteArray())
        assertContentEquals(once, Noise.strip(AnthropicWire, once))
    }
}
