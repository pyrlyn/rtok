// Port of agents/codewhale/mod.rs tests (support_matches_* lives with the Hosts table), plus own checks for comment
// preservation and a broken config.
package rtok.agents

import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.join
import rtok.sys.readToString
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import rtok.sys.writeString
import tomlaot.TomlDoc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodewhaleTest {
    private val agent = Codewhale()

    private fun cfg(
        name: String,
        dry: Boolean = false,
    ): Config {
        val dir = join(tempDir(), "rtok-codewhale-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)
        val s = Config().setup
        return Config().copy(setup = s.copy(dryRun = dry, backup = false, codewhale = s.codewhale.copy(dir = dir)))
    }

    private fun cleanup(c: Config) = removeDirAll(c.setup.codewhale.dir)

    @Test fun dryRunNamesOneAdditionAndCreatesNothing() {
        val c = cfg("dry", dry = true)
        val out = Codewhale.run(c, false)
        assertTrue("+ [[hooks.hooks]] message_submit" in out && "1 addition" in out, out)
        assertFalse(exists(Codewhale.configPath(c)))
        assertTrue(agent.installed(c, Kind.Cli).isEmpty())
        assertTrue(Agents.agent("codewhale") is Codewhale)
        cleanup(c)
    }

    @Test fun applyIsIdempotentAndRemoveKeepsForeignHooksAndServers() {
        val c = cfg("apply")
        writeString(Codewhale.configPath(c), "# mine\n[[hooks.hooks]]\nevent = \"turn_end\"\ncommand = \"echo hi\"\n")
        writeString(Codewhale.mcpPath(c), """{"mcpServers":{"not-ours":{"command":"uvx","args":["y"]}}}""")
        assertTrue("1 addition" in Codewhale.run(c, false))
        assertTrue(StdioMcp.register(c, Codewhale.mcpPath(c), "codewhale", typed = true).startsWith("mcpServers.rtok: "))
        assertEquals(NO_CHANGES, Codewhale.run(c, false))
        assertEquals(NO_CHANGES, StdioMcp.register(c, Codewhale.mcpPath(c), "codewhale", typed = true))
        val raw = readToString(Codewhale.configPath(c))
        assertTrue("enabled = true" in raw && "echo hi" in raw && "# mine" in raw, raw)
        val hooks = TomlDoc.parse(raw).arrayOfTables("hooks.hooks")
        assertEquals(2, hooks.size)
        val ours = hooks.single { it.str("event") == Codewhale.EVENT }
        assertTrue(ours.str("command")!!.endsWith("rtok hook UserPromptSubmit --host codewhale"))
        assertEquals(c.setup.hookTimeoutS.toLong(), ours.int("timeout_secs"))
        assertTrue("\"type\": \"stdio\"" in readToString(Codewhale.mcpPath(c)))
        assertEquals(listOf("hooks", "mcp"), agent.installed(c, Kind.Cli))
        assertEquals("1 removed", Codewhale.run(c, true))
        assertEquals(NO_CHANGES, Codewhale.run(c, true))
        assertEquals("- mcpServers.rtok", StdioMcp.unregister(c, Codewhale.mcpPath(c), "codewhale"))
        val left = readToString(Codewhale.configPath(c))
        assertTrue("rtok hook" !in left && "echo hi" in left, left)
        val mcp = readToString(Codewhale.mcpPath(c))
        assertTrue("\"not-ours\"" in mcp && "\"rtok\"" !in mcp, mcp)
        assertTrue(agent.installed(c, Kind.Cli).isEmpty())
        cleanup(c)
    }

    @Test fun installDoesNotFlipAnExplicitEnabledFalse() {
        val c = cfg("disabled")
        writeString(Codewhale.configPath(c), "[hooks]\nenabled = false # off for now\n")
        Codewhale.run(c, false)
        val raw = readToString(Codewhale.configPath(c))
        assertTrue(raw.startsWith("[hooks]\nenabled = false # off for now\n\n[[hooks.hooks]]\n"), raw)
        cleanup(c)
    }

    @Test fun aBrokenConfigIsAnErrorAndStaysUntouched() {
        val c = cfg("broken")
        writeString(Codewhale.configPath(c), "[hooks\n")
        assertFailsWith<IllegalStateException> { Codewhale.run(c, false) }
        assertEquals("[hooks\n", readToString(Codewhale.configPath(c)))
        cleanup(c)
    }

    @Test fun aUserEditedTableIsKeptOnRemove() {
        val c = cfg("edited")
        Codewhale.run(c, false)
        writeString(Codewhale.configPath(c), readToString(Codewhale.configPath(c)).replace("timeout_secs = 5", "timeout_secs = 60"))
        val out = Codewhale.run(c, true)
        assertTrue(out.startsWith("leave "), out)
        assertTrue("timeout_secs = 60" in readToString(Codewhale.configPath(c)))
        cleanup(c)
    }
}
