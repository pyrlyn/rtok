// Port of agents/goose/mod.rs tests: only rtok's block in a fresh file, added after the last extension with every other
// byte kept, an empty header going with the entry, Goose's re-saved defaults not an edit, an edited entry rewritten and
// left on remove, a foreign `rtok` left, dry-run, CRLF, refused documents, quoting, no final newline.
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
import yamlaot.Yaml
import yamlaot.YamlText
import yamlaot.YamlValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class GooseTest {
    private val foreign =
        "# my goose\nGOOSE_PROVIDER: openai\nextensions:\n  # tools\n  developer:\n    type: builtin\n    name: developer\n    enabled: true\n\nGOOSE_MODE: auto\n"

    private fun scratch(
        name: String,
        dry: Boolean = false,
        body: String? = null,
    ): Pair<Config, String> {
        val dir = join(tempDir(), "rtok-goose-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)
        val s = Config().setup
        val setup = s.copy(dryRun = dry, backup = false, goose = s.goose.copy(configPath = join(dir, "config.yaml")))
        body?.let { writeString(setup.goose.configPath, it) }
        return Config().copy(setup = setup) to dir
    }

    private fun Config.text() = readToString(Goose.configPath(this))

    private fun run(
        c: Config,
        mode: Mode,
    ) = Goose.apply(c, Kind.Cli, mode)[0]

    @Test fun aMissingFileGetsOnlyRtoksBlockAndRemovalDeletesItAgain() {
        val (c, dir) = scratch("fresh")
        assertTrue(run(c, Mode.Install).startsWith("extensions.rtok: "))
        val body = c.text()
        assertTrue(body.startsWith("extensions:\n  rtok:\n    name: rtok\n") && "args: [mcp, --host, goose]" in body, body)
        assertEquals(listOf("mcp"), Goose.installed(c, Kind.Cli))
        assertEquals(NO_CHANGES, run(c, Mode.Install))
        assertEquals("- extensions.rtok", run(c, Mode.Remove))
        assertEquals("", c.text())
        assertEquals(NO_CHANGES, run(c, Mode.Remove))
        assertEquals(emptyList(), Goose.installed(c, Kind.Cli))
        assertTrue(Agents.agent("goose") === Goose)
        removeDirAll(dir)
    }

    @Test fun installAddsAfterTheLastExtensionAndKeepsEveryOtherByte() {
        val (c, dir) = scratch("foreign", body = foreign)
        run(c, Mode.Install)
        assertEquals(foreign.replace("    enabled: true\n\nGOOSE_MODE", "    enabled: true\n${Goose.render(2, rtokMcpCommand(), "\n")}\nGOOSE_MODE"), c.text())
        assertEquals("- extensions.rtok", run(c, Mode.Remove))
        assertEquals(foreign, c.text())
        removeDirAll(dir)
    }

    @Test fun anEmptyHeaderTakesTheBlockAndGoesWithIt() {
        val (c, dir) = scratch("header", body = "extensions:\nGOOSE_MODE: auto\n")
        run(c, Mode.Install)
        assertTrue("extensions:\n  rtok:\n" in c.text())
        run(c, Mode.Remove)
        assertEquals("GOOSE_MODE: auto\n", c.text())
        removeDirAll(dir)
    }

    @Test fun resavedDefaultsAreNotAnEditButAChangedEntryIs() {
        val (c, dir) = scratch("edits")
        run(c, Mode.Install)
        writeString(Goose.configPath(c), c.text() + "    envs: {}\n    env_keys: []\n    timeout: 300\n")
        assertEquals(NO_CHANGES, run(c, Mode.Install))
        assertEquals("- extensions.rtok", run(c, Mode.Remove))
        assertEquals("", c.text())
        run(c, Mode.Install)
        val edited = c.text().replace("enabled: true", "enabled: false")
        writeString(Goose.configPath(c), edited)
        assertTrue(run(c, Mode.Remove).startsWith("leave extensions.rtok"))
        assertEquals(edited, c.text())
        assertTrue(run(c, Mode.Install).startsWith("extensions.rtok: "))
        assertTrue("enabled: true" in c.text())
        removeDirAll(dir)
    }

    @Test fun aForeignRtokKeyIsNeverRemoved() {
        val body = "extensions:\n  rtok:\n    type: stdio\n    cmd: other\n    args: [x]\n"
        val (c, dir) = scratch("notours", body = body)
        assertTrue("not rtok's" in run(c, Mode.Remove))
        assertEquals(body, c.text())
        removeDirAll(dir)
    }

    @Test fun dryRunCrlfAndNoFinalNewline() {
        val (dry, d1) = scratch("dry", dry = true)
        assertTrue(run(dry, Mode.Install).startsWith("extensions.rtok: "))
        assertTrue(!exists(Goose.configPath(dry)))
        val src = "GOOSE_MODE: auto\r\nextensions:\r\n  dev:\r\n    type: builtin\r\n"
        val (crlf, d2) = scratch("crlf", body = src)
        run(crlf, Mode.Install)
        val body = crlf.text()
        assertEquals(body.count { it == '\n' }, body.split("\r\n").size - 1, body)
        run(crlf, Mode.Remove)
        assertEquals(src, crlf.text())
        val (nonl, d3) = scratch("nonl", body = "GOOSE_MODE: auto")
        run(nonl, Mode.Install)
        assertTrue(nonl.text().startsWith("GOOSE_MODE: auto\nextensions:\n"), nonl.text())
        Yaml.parse(nonl.text())
        listOf(d1, d2, d3).forEach(::removeDirAll)
    }

    @Test fun anUnreadableDocumentOrAFlowExtensionsValueIsAnErrorAndUntouched() {
        for (body in listOf("extensions: [\n  oops", "extensions: {}\n", "extensions: 3\n")) {
            val (c, dir) = scratch("bad", body = body)
            assertFails(body) { run(c, Mode.Install) }
            assertEquals(body, c.text())
            removeDirAll(dir)
        }
    }

    @Test fun aPathWithSpacesIsQuotedSoTheYamlReadsBack() {
        assertEquals("\"/My Apps/rtok\"", YamlText.scalar("/My Apps/rtok"))
        val v = (Yaml.parse(Goose.render(2, "C:\\Users\\me\\rtok.exe", "\n")) as YamlValue.Map)["rtok"] as YamlValue.Map
        assertEquals(YamlValue.Str("C:\\Users\\me\\rtok.exe"), v["cmd"])
        assertEquals(YamlValue.Str("goose"), (v["args"] as YamlValue.Seq).items[2])
    }
}
