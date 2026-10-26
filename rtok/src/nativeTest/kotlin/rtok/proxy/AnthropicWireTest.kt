package rtok.proxy

import kotlin.test.*
import kotlinx.serialization.json.*
import rtok.plugin.sdk.*
import rtok.plugins.archive.rewriteBlobs
import rtok.support.*

class AnthropicWireTest {
    @Test fun extractsOnlyOldAnthropicToolResults() {
        val req = WireRequest(AnthropicWire, Json.parseToJsonElement("""{"messages":[
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"one","content":"old"}]},
            {"role":"assistant","content":"ok"},
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"two","content":"live"}]}]}"""))
        val r = req.toolResults()
        assertEquals(2, r.size); assertEquals("one", r[0].id); assertEquals(1, r[0].turn); assertEquals(0, r[1].turn)
    }

    /** T55.15 (archive): `source.data` is never rewritten; the same bytes in a text block still shrink. */
    @Test fun imageSourceDataIsNeverRewritten() = withRuntime("blobs-image", compress { it.copy(liveBlobs = true) }) { cx ->
        val uri = "data:image/png;base64," + "aB3dE5g7".repeat(900)
        val u = JsonPrimitive(uri)
        val req = WireRequest(AnthropicWire, Json.parseToJsonElement("""{"messages":[
            {"role":"user","content":[{"type":"text","text":$u},{"type":"image","source":{"type":"base64","media_type":"image/png","data":$u}}]},
            {"role":"assistant","content":"ok"},{"role":"user","content":[{"type":"text","text":$u}]},
            {"role":"assistant","content":"ok"},{"role":"user","content":[{"type":"text","text":"small"}]}]}"""))
        val original = req.body
        val ms = rewriteBlobs(req.liveBlobs(), Ctx(cx))
        assertEquals(1, ms.size, "the text blob shrinks, the image never"); assertTrue(ms.all { it.kind == "live_blob" })
        val m0 = { b: JsonElement -> b.jsonObject["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray }
        assertEquals(m0(original)[1], m0(req.body)[1])
        assertTrue(m0(req.body)[0].jsonObject["text"]!!.jsonPrimitive.content.startsWith("[archived "))
    }
}
