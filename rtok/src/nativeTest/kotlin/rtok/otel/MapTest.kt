@file:Suppress("MagicNumber")

package rtok.otel

import kotlinx.serialization.json.Json
import rtok.config.Otel
import rtok.store.Call
import rtok.store.CallDetail
import rtok.store.CallIo
import rtok.store.CallMeasurement
import rtok.store.CallUsage
import rtok.store.LogRow
import rtok.store.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Port of map.rs's tests.
class MapTest {
    private fun call(
        surface: String,
        kind: String,
        name: String,
    ) = Call(7, 1_700_000_000, "s1", null, null, null, null, surface, kind, null, name, 25.0, 1, null, null)

    private fun io(
        req: String?,
        resp: String?,
    ) = CallIo(7, 0, 0, null, null, req, resp, null, null)

    private fun Span.attr(key: String) = attrs.firstOrNull { it.first == key }?.second

    private fun Span.text(key: String) = (attr(key) as? AttrValue.Str)?.v

    @Test fun postToolUseIsAnExecuteToolSpan() {
        val req = """{"hook_event_name":"PostToolUse","tool_name":"Read","tool_use_id":"toolu_1","tool_input":{"file_path":"/x"},"tool_response":"body"}"""
        val sp = callSpan(call("hook", "hook", "PostToolUse"), CallDetail(io = io(req, null)), Otel())
        assertEquals("execute_tool Read", sp.name)
        assertEquals(Kind.Internal, sp.kind)
        assertEquals(spanId("session", "s1"), sp.parent)
        assertEquals(traceId("s1"), sp.traceId)
        assertEquals(25_000_000, sp.endNs - sp.startNs)
        assertEquals("execute_tool", sp.text("gen_ai.operation.name"))
        assertEquals("Read", sp.text("gen_ai.tool.name"))
        assertEquals("toolu_1", sp.text("gen_ai.tool.call.id"))
        assertEquals("""{"file_path":"/x"}""", sp.text("gen_ai.tool.call.arguments"))
        assertEquals("body", sp.text("gen_ai.tool.call.result"))
        assertEquals("PostToolUse", sp.text("rtok.hook.event"))
        val p = callSpan(call("hook", "hook", "UserPromptSubmit"), CallDetail(io = io("""{"prompt":"hi"}""", null)), Otel())
        assertEquals("hook UserPromptSubmit", p.name)
        assertTrue(p.text("gen_ai.input.messages")!!.contains("\"content\":\"hi\""))
    }

    @Test fun proxyCallIsAChatSpanWithUsage() {
        val d =
            CallDetail(
                io = io("""{"model":"claude-x","messages":[{"role":"user","content":"q"}]}""", """{"content":[{"type":"text","text":"a"}]}"""),
                usage = CallUsage("anthropic", 100, 10, 20, 30),
                measurements = listOf(CallMeasurement("proxy", "raw", 10, 5, 3, 1, null)),
                provider = "anthropic",
                model = "claude-x",
            )
        val sp = callSpan(call("proxy", "api_request", "/v1/messages"), d, Otel())
        assertEquals("chat claude-x", sp.name)
        assertEquals(Kind.Client, sp.kind)
        assertEquals("anthropic", sp.text("gen_ai.provider.name"))
        assertEquals(AttrValue.Int(100), sp.attr("gen_ai.usage.input_tokens"))
        assertEquals(AttrValue.Int(30), sp.attr("gen_ai.usage.output_tokens"))
        assertEquals(AttrValue.Int(20), sp.attr("gen_ai.usage.cache_read.input_tokens"))
        assertEquals(AttrValue.Int(10), sp.attr("gen_ai.usage.cache_creation.input_tokens"))
        val parsed = { k: String -> Json.parseToJsonElement(sp.text(k)!!) }
        assertEquals(Json.parseToJsonElement("""[{"role":"user","content":"q"}]"""), parsed("gen_ai.input.messages"))
        assertEquals(Json.parseToJsonElement("""[{"type":"text","text":"a"}]"""), parsed("gen_ai.output.messages"))
        assertEquals(1, sp.events.size)
        assertEquals("rtok.measurement", sp.events[0].name)
        assertTrue(i("rtok.tokens.saved", 2) in sp.events[0].attrs)
    }

    @Test fun contentIsCappedWithTheArchiveIdOrDropped() {
        val big = "x".repeat(100 * 1024)
        val d = CallDetail(io = io(big, big).copy(responseArchive = "arc1"))
        val sp = callSpan(call("mcp", "tool_call", "symbol"), d, Otel(contentBytes = 1024u))
        assertEquals("execute_tool symbol", sp.name)
        assertEquals(1024, sp.text("gen_ai.tool.call.result")!!.length)
        assertEquals("arc1", sp.text("rtok.archive.id"))
        assertEquals(AttrValue.Bool(true), sp.attr("rtok.content.truncated"))
        val off = callSpan(call("mcp", "tool_call", "symbol"), d, Otel(content = false))
        assertTrue(off.attrs.all { (k, _) -> !k.startsWith("gen_ai.tool.call.") && k != "rtok.archive.id" })
        assertEquals("symbol", off.text("gen_ai.tool.name"))
    }

    @Test fun failuresAndLogsCarryStatusAndIds() {
        val c = call("hook", "hook", "PreToolUse").copy(ok = 0, error = "boom")
        val sp = callSpan(c, CallDetail(), Otel())
        assertEquals(Status.Error("boom"), sp.status)
        assertEquals("_OTHER", sp.text("error.type"))
        val r = logRecord(LogRow(1, 5, "warn", "otel", "flush", "s1", 7, null, "m", null))
        assertEquals(13 to "WARN", r.severity to r.severityText)
        assertEquals(traceId("s1"), r.traceId)
        assertEquals(spanId("call", "7"), r.spanId)
        val root = sessionSpan(Session("s1", null, "p", null, null, 1, 2), "claude")
        assertEquals("invoke_agent claude", root.name)
        assertEquals(spanId("session", "s1"), root.spanId)
        assertEquals(secondsToNs(2), root.endNs)
    }
}
