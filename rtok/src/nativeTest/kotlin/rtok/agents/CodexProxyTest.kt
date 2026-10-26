// Port of agents/codex/mod.rs proxy tests: dry-run, idempotent apply and strip, the replaced `model_provider` restored
// byte for byte after a re-install (T307), and a hand-edited provider table left unless `--yes`.
package rtok.agents

import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config
import rtok.sys.createDirAll
import rtok.sys.join
import rtok.sys.readToString
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodexProxyTest {
    private fun scratch(
        name: String,
        dry: Boolean = false,
        yes: Boolean = false,
    ): Pair<Config, String> {
        val dir = join(tempDir(), "rtok-codex-proxy-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)
        val s = Config().setup
        val setup = s.copy(dryRun = dry, yes = yes, backup = false, codex = s.codex.copy(configPath = join(dir, "config.toml")))
        return Config().copy(setup = setup) to dir
    }

    @Test fun dryRunShowsOneChangeAndTouchesNothing() {
        val (c, dir) = scratch("dry", dry = true)
        val path = c.setup.codex.configPath
        writeString(path, "# keep me\n")
        val out = CodexProxy.run(c, false)
        assertEquals(2, out.split("+ [model_providers.rtok]").size)
        assertTrue("8790/v1" in out && "revert:" in out, out)
        assertEquals("# keep me\n", readToString(path))
        removeDirAll(dir)
    }

    @Test fun applyIsIdempotentAndRemoveStrips() {
        val (c, dir) = scratch("apply")
        val path = c.setup.codex.configPath
        writeString(path, "# keep me\nmodel = \"o3\"\n")
        assertTrue("[model_providers.rtok]" in CodexProxy.run(c, false))
        assertEquals(NO_CHANGES, CodexProxy.run(c, false))
        val raw = readToString(path)
        assertTrue("model_provider = \"rtok\"" in raw && "base_url" in raw && "# keep me" in raw, raw)
        assertTrue("proxy" in Codex().installed(c, Kind.Cli))
        assertEquals("- [model_providers.rtok]", CodexProxy.run(c, true))
        assertEquals(NO_CHANGES, CodexProxy.run(c, true))
        assertEquals("# keep me\nmodel = \"o3\"\n", readToString(path))
        removeDirAll(dir)
    }

    @Test fun removeRestoresTheReplacedModelProvider() {
        val (c, dir) = scratch("restore")
        val path = c.setup.codex.configPath
        val before = "# keep me\nmodel_provider = \"openai\"\nmodel = \"o3\"\n"
        writeString(path, before)
        assertTrue("revert: set model_provider to openai" in CodexProxy.run(c, false))
        val raw = readToString(path)
        assertTrue("model_provider = \"rtok\" # rtok: was \"openai\"\n" in raw, raw)
        writeString(path, raw.replace("8790", "1"))
        assertTrue("revert: set model_provider to openai" in CodexProxy.run(c, false))
        assertEquals(NO_CHANGES, CodexProxy.run(c, false))
        assertEquals("- [model_providers.rtok]\nrestore model_provider = \"openai\"", CodexProxy.run(c, true))
        assertEquals(before, readToString(path))
        assertEquals(NO_CHANGES, CodexProxy.run(c, true))
        removeDirAll(dir)
    }

    @Test fun removeLeavesAHandEditedProviderTable() {
        val (c, dir) = scratch("edited")
        val path = c.setup.codex.configPath
        writeString(path, "model_provider = \"openai\"\n")
        CodexProxy.run(c, false)
        val edited = readToString(path).replace("http://127.0.0.1:8790/v1", "http://example.test/v1")
        assertTrue("example.test" in edited, edited)
        writeString(path, edited)
        val out = CodexProxy.run(c, true)
        assertTrue(out.startsWith("leave model_providers.rtok"), out)
        assertEquals(edited, readToString(path))
        val yes = c.copy(setup = c.setup.copy(yes = true))
        assertTrue("restore model_provider = \"openai\"" in CodexProxy.run(yes, true))
        assertEquals("model_provider = \"openai\"\n", readToString(path))
        assertFalse("proxy" in Codex().installed(c, Kind.Cli))
        removeDirAll(dir)
    }
}
