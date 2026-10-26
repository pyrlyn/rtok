// Tests of agents/mod.rs `list`, `list_ids`, `info` and `block`: one block per variant, info resolving its hosts
// first, and a real block's app, config, module and plugin lines on Windsurf's file in a scratch dir.
package rtok.agents

import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.join
import rtok.sys.listDir
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallListTest {
    private fun scratch(name: String) = join(tempDir(), "rtok-list-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)

    private fun env() = AgentsRun.Env(Agents::agent, { _, _, _ -> false }, { true }, { _, v, _, o -> "${v.kind.label}: ${v.name} ${o::class.simpleName}\n" })

    @Test fun listPrintsOneBlockPerVariantAndInfoResolvesFirst() {
        val e = env()
        val out = AgentsList.list(Config(), e)
        for (head in listOf("CLI: Claude Code", "Desktop: Claude Desktop", "CLI: Cursor CLI", "Desktop: Cursor", "CLI: Codex", "CLI: OpenCode", "CLI: pi", "Desktop: Windsurf", "Desktop: Zed")) {
            assertTrue("$head NotFound\n" in out, "$head missing:\n$out")
        }
        assertEquals("CLI: Codex NotFound", AgentsList.info(Config(), listOf("codex"), e).lines().first())
        assertFailsWith<AgentsFailure> { AgentsList.info(Config(), listOf("nope"), e) }
    }

    @Test fun aRealBlockShowsAppConfigAndModuleRows() {
        val dir = scratch("block")
        val c = Config()
        val cfg = c.copy(setup = c.setup.copy(windsurf = c.setup.windsurf.copy(configPath = join(dir, "mcp_config.json"))))
        val a = Agents.agent("windsurf")!!
        val v = Hosts.host("windsurf")!!.variants.first()
        val nf = Blocks.block(a, v, cfg, Outcome.NotFound) { "x" }
        assertTrue(nf.startsWith("Desktop: Windsurf — not found\n"), nf)
        assertTrue("  config  ${cfg.setup.windsurf.configPath}\n" in nf, nf)
        assertFalse("plugins" in nf, nf)
        val applied = Blocks.block(a, v, cfg, Outcome.Applied(Mode.Install, listOf(NO_CHANGES))) { "x" }
        assertTrue(applied.startsWith("Desktop: Windsurf — already installed\n"), applied)
        assertTrue("  plugins\n" in applied, applied)
        assertTrue("− hooks   not supported" in applied, applied)
        val skip = Blocks.block(a, v, cfg, Outcome.NotInstalled) { "x" }
        assertEquals("Desktop: Windsurf — not installed\n  skip    nothing of rtok here; run `rtok agents install windsurf`\n", skip)
        removeDirAll(dir)
    }
}
