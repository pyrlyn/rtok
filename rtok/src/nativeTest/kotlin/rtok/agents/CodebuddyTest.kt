// Port of agents/codebuddy/mod.rs tests: the MCP file pick, foreign bytes kept in JSONC, the table hook rows, the
// plugin as the hooks singleton with MCP independent of it, and the offer naming both CLI calls and the WorkBuddy dir.
package rtok.agents

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config
import rtok.mcp.config.Jsonc
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.join
import rtok.sys.readToString
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodebuddyTest {
    private class Scratch(
        val cfg: Config,
        val dir: String,
    )

    private fun scratch(
        name: String,
        dry: Boolean = false,
        yes: Boolean = false,
    ): Scratch {
        val dir = join(tempDir(), "rtok-codebuddy-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)
        val s = Config().setup
        val setup = s.copy(dryRun = dry, yes = yes, backup = false, codebuddy = s.codebuddy.copy(dir = join(dir, "cb"), workbuddyDir = join(dir, "wb")))
        return Scratch(Config().copy(setup = setup), dir)
    }

    private val noCli = Codebuddy({ "/src/$it" }, { _, _, _ -> "unexpected call" }, { false })

    @Test fun theMcpFileIsTheFirstExistingOneElseDotMcpJson() {
        val s = scratch("pick")
        val d = join(s.dir, "cb")
        assertEquals(join(d, ".mcp.json"), Codebuddy.mcpPath(s.cfg, Kind.Cli))
        createDirAll(d)
        writeString(join(d, "mcp.json"), "{}")
        assertEquals(join(d, "mcp.json"), Codebuddy.mcpPath(s.cfg, Kind.Cli))
        writeString(join(d, ".mcp.json"), "{}")
        assertEquals(join(d, ".mcp.json"), Codebuddy.mcpPath(s.cfg, Kind.Cli))
        assertTrue(Agents.agent("codebuddy") is Codebuddy)
        removeDirAll(s.dir)
    }

    @Test fun mcpKeepsCommentsAndForeignServersAndIsIdempotent() {
        val s = scratch("mcp")
        val path = Codebuddy.mcpPath(s.cfg, Kind.Cli)
        createDirAll(join(s.dir, "cb"))
        val before = "{\n  // mine\n  \"mcpServers\": {\"other\": {\"command\": \"x\"}}\n}\n"
        writeString(path, before)
        assertTrue(McpRun.install(s.cfg, Codebuddy.mcpSpec(s.cfg, Kind.Cli)).startsWith("mcpServers.rtok: "))
        val rtok = (Jsonc.parse(readToString(path)).jsonObject["mcpServers"]!!.jsonObject["rtok"])!!.jsonObject
        assertEquals(JsonPrimitive("stdio"), rtok["type"])
        assertTrue("// mine" in readToString(path))
        assertEquals(NO_CHANGES, McpRun.install(s.cfg, Codebuddy.mcpSpec(s.cfg, Kind.Cli)))
        assertEquals("- mcpServers.rtok", McpRun.remove(s.cfg, Codebuddy.mcpSpec(s.cfg, Kind.Cli)))
        assertTrue("// mine" in readToString(path) && "other" in readToString(path))
        removeDirAll(s.dir)
    }

    @Test fun hooksUseTheTableRowsAndLeaveForeignHooksAlone() {
        val s = scratch("hooks")
        val settings = Codebuddy.settingsPath(s.cfg, Kind.Cli)
        createDirAll(join(s.dir, "cb"))
        writeString(settings, """{"hooks":{"Stop":[{"hooks":[{"type":"command","command":"echo other"}]}]}}""")
        val lines = noCli.apply(s.cfg, Kind.Cli, Mode.Install)
        assertTrue("hook PreToolUse --host codebuddy" in readToString(settings), "$lines")
        assertEquals(listOf("hooks", "mcp"), noCli.installed(s.cfg, Kind.Cli))
        assertTrue(noCli.apply(s.cfg, Kind.Cli, Mode.Install).all { it == NO_CHANGES })
        noCli.apply(s.cfg, Kind.Cli, Mode.Remove)
        val left = readToString(settings)
        assertTrue("echo other" in left && "--host codebuddy" !in left, left)
        assertEquals(emptyList(), noCli.installed(s.cfg, Kind.Cli))
        removeDirAll(s.dir)
    }

    @Test fun thePluginIsTheHooksSingletonButMcpStays() {
        val s = scratch("singleton")
        Codebuddy.run(s.cfg, Kind.Cli, false)
        createDirAll(join(s.dir, "cb/plugins"))
        writeString(join(s.dir, "cb/plugins/installed_plugins.json"), """{"plugins":{"rtok@rtok":{}}}""")
        noCli.apply(s.cfg, Kind.Cli, Mode.Install)
        assertFalse(" hook PreToolUse" in readToString(Codebuddy.settingsPath(s.cfg, Kind.Cli)))
        assertTrue(exists(Codebuddy.mcpPath(s.cfg, Kind.Cli)))
        assertEquals(listOf("mcp", "plugin"), noCli.installed(s.cfg, Kind.Cli))
        removeDirAll(s.dir)
    }

    @Test fun theOfferNamesBothCliCallsAndTheConfigDirForWorkbuddy() {
        val s = scratch("offer", dry = true, yes = true)
        val out = noCli.plugin(s.cfg, Kind.Desktop, false)
        val wb = join(s.dir, "wb")
        assertTrue("CODEBUDDY_CONFIG_DIR=$wb codebuddy plugin marketplace add /src/plugins/codebuddy --name rtok" in out, out)
        assertTrue("&& CODEBUDDY_CONFIG_DIR=$wb codebuddy plugin install rtok@rtok -s user" in out && "ketch install pyrlyn/rtok" in out, out)
        val calls = mutableListOf<List<String>>()
        val live = Codebuddy({ "/src/$it" }, { _, a, _ -> null.also { calls += a } }, { true })
        val real = s.cfg.copy(setup = s.cfg.setup.copy(dryRun = false))
        assertEquals("+ plugin plugins/codebuddy → rtok", live.plugin(real, Kind.Cli, false))
        assertEquals(listOf("marketplace", "install"), calls.map { it[1] })
        assertTrue(noCli.plugin(real, Kind.Cli, false).contains("not on PATH"))
        removeDirAll(s.dir)
    }
}
