// Tests of the agents/command.rs host side: `parse_hosts`, `setup_flags`, `update` with nothing installed and with an
// absent host (its key named, then refused), and `install`/`uninstall` on Windsurf's file in a scratch dir.
package rtok.agents

import kotlinx.serialization.json.Json
import rtok.config.Config
import rtok.sys.createDirAll
import rtok.sys.join
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandTest {
    private fun env(present: Boolean = true) = AgentsRun.Env(Agents::agent, { _, _, _ -> present }, { true }, { _, v, _, o -> "${v.name} ${o::class.simpleName}\n" })

    private fun windsurf(): Pair<Config, String> {
        val dir = join(tempDir(), "rtok-cmd-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)
        val c = Config()
        return c.copy(setup = c.setup.copy(backup = false, windsurf = c.setup.windsurf.copy(configPath = join(dir, "mcp_config.json")))) to dir
    }

    @Test fun hostsAreCommaSeparatedAndEmptyIsRefused() {
        assertEquals(listOf("opencode", "cursor"), AgentsCommand.parseHosts(" opencode, ,cursor "))
        assertEquals("unknown host: ,", assertFailsWith<AgentsFailure> { AgentsCommand.parseHosts(",") }.message)
    }

    @Test fun setupFlagsOverlayOnlyWhatWasGiven() {
        assertNull(AgentsCommand.setupFlags())
        val flags = AgentsCommand.setupFlags(dryRun = true, proxy = true, modes = listOf("lite", "toon"), source = "local")
        assertEquals<Any?>(Json.parseToJsonElement("""{"setup":{"dry_run":true,"proxy":true,"modes":["lite","toon"],"source":"local"}}"""), flags)
    }

    @Test fun installThenUninstallThenUpdateHasNothingToDo() {
        val (cfg, dir) = windsurf()
        val install = AgentsCommand.install(cfg, "windsurf", Mode.Install, cli = false, desktop = false, all = false, env = env())
        assertEquals("Windsurf Applied\n", install.out)
        assertFalse(install.reinstallFailed)
        assertEquals(
            "Windsurf Applied\n",
            AgentsCommand
                .update(cfg, null, false, false, false, env())
                .out
                .lines()
                .first() + "\n",
        )
        AgentsCommand.install(cfg, "windsurf", Mode.Remove, cli = false, desktop = false, all = false, env = env(present = false))
        val nothing = AgentsCommand.update(cfg, null, false, false, false, AgentsRun.Env(present = { _, _, _ -> true }, agent = { if (it == "windsurf") Agents.agent(it) else null }))
        assertEquals("nothing to update: rtok is not installed in any host (rtok agents install <host>)\n", nothing.out)
        removeDirAll(dir)
    }

    @Test fun updateNamesTheKeyForAnAbsentHostBeforeTheRunRefusesIt() {
        val (cfg, dir) = windsurf()
        val warned = mutableListOf<String>()
        assertFailsWith<AgentsFailure> { AgentsCommand.update(cfg, "windsurf", false, false, false, env(present = false), warned::add) }
        assertTrue(warned.single().startsWith("warning: windsurf is not installed"), warned.toString())
        removeDirAll(dir)
    }

    @Test fun aFailedClaudeReinstallIsFlagged() {
        assertTrue(AgentsOutput("  plugin rtok@rtok ${Claude.REINSTALL_FAILED} boom\n").reinstallFailed)
    }
}
