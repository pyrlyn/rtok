package rtok.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rtok.MemoryStatusView
import rtok.support.diskConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerTest {
    private fun parse(s: String?) = Json.parseToJsonElement(s!!)

    private fun call(
        server: Server,
        name: String,
        args: String = "{}",
    ): JsonObject = parse(server.handleLine("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":$args}}""")).jsonObject["result"]!!.jsonObject

    private fun text(r: JsonObject) =
        r["content"]!!
            .jsonArray[0]
            .jsonObject["text"]!!
            .jsonPrimitive.content

    private fun isError(r: JsonObject) = r["isError"]!!.jsonPrimitive.boolean

    @Test fun malformedLineAnswersParseError() {
        val (cfg, _) = diskConfig("mcp-parse")
        Server.open(cfg, "mcp-t1").use { s ->
            val e = parse(s.handleLine("{nope")).jsonObject["error"]!!.jsonObject
            assertEquals(-32700, e["code"]!!.jsonPrimitive.int)
            assertNull(s.handleLine("   "))
        }
    }

    @Test fun nonObjectLineAnswersInvalidRequest() {
        val (cfg, _) = diskConfig("mcp-invalid")
        Server.open(cfg, "mcp-t2").use { s ->
            assertEquals(
                -32600,
                parse(s.handleLine("42"))
                    .jsonObject["error"]!!
                    .jsonObject["code"]!!
                    .jsonPrimitive.int,
            )
            assertEquals(
                -32600,
                parse(s.handleLine("[]"))
                    .jsonObject["error"]!!
                    .jsonObject["code"]!!
                    .jsonPrimitive.int,
            )
            val nf = parse(s.handleLine("""{"jsonrpc":"2.0","id":3,"method":"nope"}""")).jsonObject["error"]!!.jsonObject
            assertEquals(-32601, nf["code"]!!.jsonPrimitive.int)
        }
    }

    @Test fun batchAnswersWithAnArrayAndNotificationsGetNothing() {
        val (cfg, _) = diskConfig("mcp-batch")
        Server.open(cfg, "mcp-t3").use { s ->
            val out = parse(s.handleLine("""[{"jsonrpc":"2.0","id":1,"method":"ping"},{"jsonrpc":"2.0","method":"notifications/initialized"}]""")) as JsonArray
            assertEquals(1, out.size)
            assertNull(s.handleLine("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        }
    }

    @Test fun initializeAnswersNameVersionAndAsksARootsClientForRoots() {
        val (cfg, _) = diskConfig("mcp-init")
        Server.open(cfg, "mcp-t4").use { s ->
            val r = parse(s.handleLine("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{"roots":{}}}}""")).jsonObject["result"]!!.jsonObject
            assertEquals("2025-03-26", r["protocolVersion"]!!.jsonPrimitive.content)
            assertEquals("rtok", r["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            val ask = parse(s.handleLine("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")).jsonObject
            assertEquals("roots/list", ask["method"]!!.jsonPrimitive.content)
            assertNull(s.handleLine("""{"jsonrpc":"2.0","id":"rtok-roots","result":{"roots":[]}}"""))
        }
    }

    @Test fun toolsListMarksOnlyReadOnlyTools() {
        val (cfg, _) = diskConfig("mcp-list")
        Server.open(cfg, "mcp-t5").use { s ->
            val tools = parse(s.handleLine("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
            val names = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }
            assertTrue("expand" in names && "ping" in names, names.toString())
            for (t in tools.map { it.jsonObject }) {
                val ro =
                    t["annotations"]
                        ?.jsonObject
                        ?.get("readOnlyHint")
                        ?.jsonPrimitive
                        ?.boolean ?: false
                assertEquals(t["name"]!!.jsonPrimitive.content in Protocol.READ_ONLY_TOOLS, ro, t.toString())
            }
        }
    }

    @Test fun pingRepliesWithTheDisplayName() {
        val (cfg, _) = diskConfig("mcp-ping")
        Server.open(cfg, "mcp-t6").use { s -> assertEquals("MCP Claude жив", text(call(s, "ping", """{"agent":"Claude"}"""))) }
    }

    @Test fun failedCallSetsIsErrorAndNeverDoublesThePrefix() {
        val (cfg, _) = diskConfig("mcp-fail")
        Server.open(cfg, "mcp-t7").use { s ->
            val r = call(s, "nope")
            assertTrue(isError(r))
            assertEquals("unknown tool: nope", text(r))
            val missing = call(s, "expand")
            assertTrue(isError(missing))
            assertEquals("invalid params: missing `id` (got: no arguments)", text(missing))
            assertFalse(text(call(s, "expand", """{"id":"zzzz"}""")).startsWith("Error"))
        }
    }

    @Test fun toolsAllowListFiltersListingAndCalls() {
        val (base, _) = diskConfig("mcp-allow")
        val cfg = base.copy(mcp = base.mcp.copy(tools = listOf("read")))
        val names = Server.listedFor(cfg).map { it.def.name }
        assertTrue("ping" in names && "expand" in names, names.toString())
        assertFalse("mem_search" in names, names.toString())
    }

    @Test fun toolsCallIsRecordedAsAnMcpCall() {
        val (cfg, _) = diskConfig("mcp-record")
        Server.open(cfg, "mcp-t8").use { s -> assertEquals("no notes match", text(call(s, "mem_search", """{"query":"nothing"}"""))) }
        assertEquals(1uL, MemoryStatusView.collect(cfg, null, "1d").calls.memSearch)
    }

    @Test fun oneShotCallUsesTheSameInvokeAsToolsCall() {
        val (cfg, _) = diskConfig("mcp-oneshot")
        assertEquals("MCP x жив", Mcp.call(cfg, "ping", Json.parseToJsonElement("""{"agent":"x"}""")))
        assertEquals("unknown tool: nope", runCatching { Mcp.call(cfg, "nope", null) }.exceptionOrNull()?.message)
    }

    @Test fun runServesStdioUntilEof() {
        val (cfg, _) = diskConfig("mcp-run")
        val input = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n\n".encodeToByteArray()
        var at = 0
        val src =
            ByteSource { buf ->
                val n = minOf(buf.size, input.size - at)
                input.copyInto(buf, 0, at, at + n)
                at += n
                n
            }
        val out = ArrayList<String>()
        Mcp.run(cfg, src) { out += it }
        assertEquals(listOf("""{"jsonrpc":"2.0","id":1,"result":{}}"""), out)
    }
}
