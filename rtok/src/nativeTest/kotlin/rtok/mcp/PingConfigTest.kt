package rtok.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PingConfigTest {
    private fun arr(vararg s: String) = JsonArray(s.map { JsonPrimitive(it) })

    private fun obj(vararg p: Pair<String, JsonElement>) = JsonObject(mapOf(*p))

    private fun fixture(
        shape: Shape,
        cmd: String,
    ): String {
        val h = shape.host
        if (shape.toml) {
            return "[mcp_servers.other]\ncommand = \"decoy\"\nargs = [\"no\"]\n\n[mcp_servers.rtok]\n" +
                "command = \"$cmd\"\nargs = [\"mcp\", \"$h\"]\nenv = { PING_HOST = \"$h\" }\n"
        }
        val env = obj("PING_HOST" to JsonPrimitive(h))
        val entry = if (shape.argv) obj("command" to arr(cmd, "mcp", h), "env" to env) else obj("command" to JsonPrimitive(cmd), "args" to arr("mcp", h), "env" to env)
        val decoy = if (shape.argv) obj("command" to arr("decoy", "no")) else obj("command" to JsonPrimitive("decoy"), "args" to arr("no"))
        var node: JsonElement = obj("rtok" to entry, "other" to decoy)
        for (key in shape.keys.reversed().drop(1)) node = obj(key to node)
        return node.toString()
    }

    @Test fun serverOnlyReadsCommandAndArgsFromEachHostConfig() {
        for (shape in Ping.SHAPES) {
            val cmd = "/opt/ping/${shape.host}"
            val files = mapOf("/cfg/${shape.host}" to fixture(shape, cmd), "/cfg/miss" to "{\"unrelated\":true}")
            val got = assertNotNull(Ping.launchFromRead(files::get, listOf("/cfg/miss", "/cfg/${shape.host}"), shape.host), shape.host)
            assertEquals(cmd, got.command, shape.host)
            assertEquals(listOf("mcp", shape.host), got.args, shape.host)
            assertEquals(listOf("PING_HOST" to shape.host), got.env, shape.host)
            assertNotEquals("decoy", got.command)
        }
    }

    @Test fun jsoncCommentsAndUnknownHosts() {
        val body = "{\n // c\n \"mcpServers\": {\"rtok\": {\"command\": \"rtok\", \"args\": [\"mcp\",],},},\n}"
        assertEquals(Launch("rtok", listOf("mcp"), emptyList()), Ping.launchFromBody(body, "claude"))
        assertNull(Ping.launchFromBody(body, "nope"))
        assertNull(Ping.launchFromBody("{\"mcpServers\":{\"rtok\":{\"command\":\"\"}}}", "claude"))
    }

    @Test fun headlessHostsHaveAShapeAndPromptNamesTheAgent() {
        Ping.HEADLESS.forEach { assertNotNull(Ping.shapeFor(it.host), it.host) }
        assertEquals("MCP Claude Code жив", Ping.pingText("Claude Code"))
        assertTrue(Ping.promptFor("Codex CLI").contains("agent=\"Codex CLI\""))
    }
}
