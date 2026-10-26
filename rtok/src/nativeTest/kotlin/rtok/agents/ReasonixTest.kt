// Port of agents/reasonix/mod.rs tests: one `[[plugins]]` table named rtok, every foreign byte kept, an edited table
// left on remove, and a `plugins` key of another shape refused.
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

class ReasonixTest {
    private val foreign = "# my reasonix\ndefault_model = \"x\"\n\n[[plugins]]\nname = \"other\"\ncommand = \"other\"\n"

    private fun cfg(
        name: String,
        dry: Boolean = false,
    ): Pair<Config, String> {
        val dir = join(tempDir(), "rtok-reasonix-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)
        val s = Config().setup
        return Config().copy(setup = s.copy(dryRun = dry, backup = false, reasonix = s.reasonix.copy(dir = dir))) to dir
    }

    @Test fun theDefaultDirIsTheReasonixHome() {
        assertTrue(Reasonix.configPath(Config()).endsWith(".reasonix/config.toml"))
        assertTrue(Agents.agent("reasonix") === Reasonix)
    }

    @Test fun dryRunNamesTheTableAndCreatesNothing() {
        val (c, dir) = cfg("dry", dry = true)
        val out = Reasonix.apply(c, Kind.Cli, Mode.Install).single()
        assertTrue(out.startsWith("plugins.rtok: ") && "mcp --host reasonix" in out, out)
        assertFalse(exists(Reasonix.configPath(c)))
        removeDirAll(dir)
    }

    @Test fun installAddsOneTableKeepsForeignBytesAndRemoveRestoresTheFile() {
        val (c, dir) = cfg("keep")
        val path = Reasonix.configPath(c)
        writeString(path, foreign)
        Reasonix.register(c)
        val raw = readToString(path)
        assertTrue(raw.startsWith(foreign), raw)
        val ours = TomlDoc.parse(raw).arrayOfTables("plugins").single { it.str("name") == "rtok" }
        assertEquals(rtokMcpCommand(), ours.str("command"))
        assertEquals("[\"mcp\", \"--host\", \"reasonix\"]", ours["args"]!!.render())
        assertEquals(listOf("mcp"), Reasonix.installed(c, Kind.Cli))
        assertEquals(NO_CHANGES, Reasonix.register(c))
        assertEquals("- plugins.rtok", Reasonix.unregister(c))
        assertEquals(foreign, readToString(path))
        assertEquals(NO_CHANGES, Reasonix.unregister(c))
        val only = "default_model = \"x\"\n"
        writeString(path, only)
        Reasonix.register(c)
        Reasonix.unregister(c)
        assertEquals(only, readToString(path))
        removeDirAll(dir)
    }

    @Test fun aUserEditedTableStaysOnRemove() {
        val (c, dir) = cfg("edited")
        val path = Reasonix.configPath(c)
        writeString(path, "[[plugins]]\nname = \"rtok\"\ncommand = \"rtok\"\nargs = [\"mcp\", \"--verbose\"]\n")
        assertTrue(Reasonix.unregister(c).startsWith("leave plugins.rtok"))
        assertTrue("--verbose" in readToString(path))
        removeDirAll(dir)
    }

    @Test fun aPluginsKeyOfAnotherShapeIsAnErrorNotOverwritten() {
        val (c, dir) = cfg("shape")
        val path = Reasonix.configPath(c)
        writeString(path, "plugins = []\n")
        assertFailsWith<IllegalStateException> { Reasonix.register(c) }
        assertEquals("plugins = []\n", readToString(path))
        removeDirAll(dir)
    }
}
