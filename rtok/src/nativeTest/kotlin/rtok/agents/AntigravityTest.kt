// Port of agents/antigravity/mod.rs tests (skill sync waits for the skills port): the CLI offer is printed, a staged
// copy counts as installed and stays on remove; the desktop tree is linked on `--yes`, a foreign tree is left alone.
package rtok.agents

import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.join
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AntigravityTest {
    private class Scratch(
        val cfg: Config,
        val dir: String,
        val agy: Antigravity,
    )

    private fun scratch(
        name: String,
        dry: Boolean = false,
        yes: Boolean = false,
    ): Scratch {
        val dir = join(tempDir(), "rtok-agy-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)
        val src = join(dir, "repo")
        createDirAll(join(src, "plugins/antigravity"))
        writeString(join(src, "plugins/antigravity/plugin.json"), "{\"name\":\"rtok\"}\n")
        val s = Config().setup
        val setup =
            s.copy(
                dryRun = dry,
                yes = yes,
                backup = false,
                antigravity = s.antigravity.copy(pluginsPath = join(dir, "plugins"), cliPluginsPath = join(dir, "cli-plugins")),
            )
        return Scratch(Config().copy(setup = setup), dir, Antigravity { rel -> PluginSrc.joinRel(src, rel) })
    }

    @Test fun cliOfferNamesAgyAndKetchBehindYesAndWritesNothing() {
        assertEquals(listOf(NO_CHANGES, NO_CHANGES), scratch("no").let { it.agy.apply(it.cfg, Kind.Cli, Mode.Install) })
        val s = scratch("cli", yes = true)
        val out = s.agy.apply(s.cfg, Kind.Cli, Mode.Install).first()
        assertTrue("agy plugin install" in out && "plugins/antigravity" in out && "ketch install pyrlyn/rtok" in out, out)
        assertFalse(exists(Antigravity.cliPluginDest(s.cfg)))
        assertTrue(Agents.agent("antigravity") is Antigravity)
        removeDirAll(s.dir)
    }

    @Test fun aStagedCliCopyIsInstalledAndKeptOnRemove() {
        val s = scratch("staged")
        createDirAll(Antigravity.cliPluginDest(s.cfg))
        writeString(join(Antigravity.cliPluginDest(s.cfg), "plugin.json"), "{\"name\":\"rtok\"}")
        assertEquals(listOf("plugin"), s.agy.installed(s.cfg, Kind.Cli))
        assertTrue(
            s.agy
                .apply(s.cfg, Kind.Cli, Mode.Remove)
                .first()
                .startsWith("keep antigravity-cli/plugins/rtok"),
        )
        assertTrue(exists(Antigravity.cliPluginDest(s.cfg)))
        removeDirAll(s.dir)
    }

    @Test fun desktopLinksOnYesOnceAndRemovesExactlyOurs() {
        val s = scratch("desk", yes = true)
        assertTrue(
            s.agy
                .apply(s.cfg, Kind.Desktop, Mode.Install)
                .first()
                .startsWith("+ plugin"),
        )
        assertEquals(listOf("plugin"), s.agy.installed(s.cfg, Kind.Desktop))
        assertEquals(listOf(NO_CHANGES, NO_CHANGES), s.agy.apply(s.cfg, Kind.Desktop, Mode.Install))
        assertTrue(
            s.agy
                .apply(s.cfg, Kind.Desktop, Mode.Remove)
                .first()
                .startsWith("- plugin"),
        )
        assertEquals(emptyList(), s.agy.installed(s.cfg, Kind.Desktop))
        removeDirAll(s.dir)
    }

    @Test fun aForeignDesktopTreeIsLeftAloneAndNotInstalled() {
        val s = scratch("foreign", yes = true)
        createDirAll(Antigravity.PLUGIN.path(s.cfg))
        writeString(join(Antigravity.PLUGIN.path(s.cfg), "mine.txt"), "mine")
        s.agy.apply(s.cfg, Kind.Desktop, Mode.Install)
        assertTrue(exists(join(Antigravity.PLUGIN.path(s.cfg), "mine.txt")))
        assertEquals(emptyList(), s.agy.installed(s.cfg, Kind.Desktop))
        removeDirAll(s.dir)
    }
}
