// Port of the tests of src/doctor/mcp_fix.rs.
package rtok.doctor

import rtok.agents.Hosts
import rtok.agents.McpSurfaces
import rtok.config.Config
import kotlin.test.Test
import kotlin.test.assertEquals

class McpFixTest {
    private fun loc(name: String): Loc {
        val agent = Hosts.host("claude")!!
        val spec = McpSurfaces.surfaces(agent.id, Config(), agent.variants[0].kind)[0].spec
        return Loc(spec.configPath, "mcpServers.$name", spec, name)
    }

    @Test
    fun anEntryThatIsNotThereIsRefused() {
        val raw = """{"mcpServers": {"a": {"command": "x"}}}"""
        assertEquals("an entry could not be located", McpFix.remove(raw, "/c.json", listOf(loc("b"))).exceptionOrNull()?.message)
    }

    @Test
    fun aJsonEntryGoesAndEveryOtherByteStays() {
        val l = loc("b")
        val raw = "{\n  // keep\n  \"mcpServers\": {\"a\": {\"command\": \"x\"}, \"b\": {\"command\": \"x\"}},\n  \"z\": 1\n}\n"
        val got = McpFix.remove(raw, l.spec.configPath, listOf(l)).getOrThrow()
        assertEquals("{\n  // keep\n  \"mcpServers\": {\"a\": {\"command\": \"x\"}},\n  \"z\": 1\n}\n", got)
    }

    @Test
    fun aTomlServerTableGoesAndTheRestStays() {
        val spec = McpSurfaces.surfaces("codex", Config(), Hosts.host("codex")!!.variants[0].kind)[0].spec
        val l = Loc(spec.configPath, "mcp_servers.b", spec, "b")
        val raw = "# top\nmodel = \"o\"\n\n[mcp_servers.a]\ncommand = \"x\"\n\n[mcp_servers.b]\ncommand = \"x\"\n"
        val got = McpFix.remove(raw, spec.configPath, listOf(l)).getOrThrow()
        assertEquals("# top\nmodel = \"o\"\n\n[mcp_servers.a]\ncommand = \"x\"\n", got)
    }
}
