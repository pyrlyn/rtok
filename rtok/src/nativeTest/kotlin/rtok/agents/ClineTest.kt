// Port of agents/cline/mod.rs tests, plus plugin.rs `path_follows_the_config_it_is_given` and an MCP surface pair.
package rtok.agents

import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.fileName
import rtok.sys.isDir
import rtok.sys.join
import rtok.sys.readToString
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClineTest {
    private class Scratch(
        val cfg: Config,
        val dir: String,
        val cline: Cline,
    )

    private fun scratch(
        name: String,
        dry: Boolean = false,
        yes: Boolean = false,
        mcp: Boolean = false,
    ): Scratch {
        val dir = join(tempDir(), "rtok-cline-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)
        val src = join(dir, "repo")
        createDirAll(join(src, "plugins/cline/hooks"))
        writeString(join(src, "plugins/cline/hooks/rtok-hook"), "#!/bin/sh\nexec rtok hook \"$(basename \"$0\")\"\n")
        val s = Config().setup
        val setup =
            s.copy(
                dryRun = dry,
                yes = yes,
                backup = false,
                mcp = mcp,
                cline = s.cline.copy(hooksPath = join(dir, "Hooks"), mcpPath = join(dir, "cline_mcp_settings.json")),
                vscode = s.vscode.copy(codeUserDir = join(dir, "User")),
            )
        return Scratch(Config().copy(setup = setup), dir, Cline { rel -> PluginSrc.joinRel(src, rel) })
    }

    @Test fun hooksFollowTheTableAndTheConfig() {
        val s = scratch("table", dry = true)
        assertEquals(HookEvents.installed("cline").map { it.first }, Cline.HOOKS.map { fileName(it.path(s.cfg)) })
        assertTrue(Cline.HOOKS.all { it.path(s.cfg).startsWith(s.dir) })
        removeDirAll(s.dir)
    }

    @Test fun dryRunNamesThePluginAndKetchAndWritesNothing() {
        val s = scratch("dry", dry = true)
        val lines = s.cline.apply(s.cfg, Kind.Cli, Mode.Install)
        assertEquals(HookEvents.installed("cline").size, lines.size, "$lines")
        assertTrue(lines.all { "plugins/cline" in it && "ketch install pyrlyn/rtok" in it }, "$lines")
        assertFalse(exists(s.cfg.setup.cline.hooksPath))
        removeDirAll(s.dir)
    }

    @Test fun yesLinksEveryEventSecondApplyIsNoChanges() {
        val s = scratch("yes", yes = true)
        val first = s.cline.apply(s.cfg, Kind.Cli, Mode.Install)
        assertTrue(first.all { it.startsWith("+ plugin") }, "$first")
        assertEquals(listOf("hooks", "plugin"), s.cline.installed(s.cfg, Kind.Cli))
        assertEquals(List(first.size) { NO_CHANGES }, s.cline.apply(s.cfg, Kind.Cli, Mode.Install))
        removeDirAll(s.dir)
    }

    @Test fun removeTakesBackOnlyOursAndKeepsForeign() {
        val s = scratch("rm", yes = true)
        val foreign = join(s.cfg.setup.cline.hooksPath, "PreToolUse")
        createDirAll(foreign)
        writeString(join(foreign, "mine.sh"), "mine")
        s.cline.apply(s.cfg, Kind.Cli, Mode.Install)
        assertTrue(isDir(foreign))
        val lines = s.cline.apply(s.cfg, Kind.Cli, Mode.Remove)
        assertTrue(lines.any { "leave" in it && "PreToolUse" in it }, "$lines")
        assertEquals("mine", readToString(join(foreign, "mine.sh")))
        assertTrue(s.cline.installed(s.cfg, Kind.Cli).isEmpty())
        removeDirAll(s.dir)
    }

    @Test fun mcpGoesToBothSurfacesAndEachVariantReportsItsOwnFile() {
        val s = scratch("mcp", yes = true, mcp = true)
        s.cline.apply(s.cfg, Kind.Cli, Mode.Install)
        val cli = s.cfg.setup.cline.mcpPath
        val ext = join(s.cfg.setup.vscode.codeUserDir, "globalStorage/saoudrizwan.claude-dev/settings/cline_mcp_settings.json")
        assertEquals(listOf(cli), s.cline.files(s.cfg, Kind.Cli))
        assertEquals(listOf(ext), s.cline.files(s.cfg, Kind.Desktop))
        assertTrue(listOf(cli, ext).all { "\"rtok\"" in readToString(it) })
        assertEquals(listOf("hooks", "plugin", "mcp"), s.cline.installed(s.cfg, Kind.Desktop))
        s.cline.apply(s.cfg, Kind.Cli, Mode.Remove)
        assertTrue(listOf(cli, ext).none { "\"rtok\"" in readToString(it) })
        removeDirAll(s.dir)
    }

    @Test fun clineIsInTheRegistry() {
        assertTrue(Agents.agent("cline") is Cline)
    }
}
