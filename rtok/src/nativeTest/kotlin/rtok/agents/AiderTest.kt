// Port of agents/aider/mod.rs tests: `openai-api-base` set in place or appended, comments and quotes kept, and ownership
// read from the URL authority (mod.rs `loopback_port`), never from substrings.
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiderTest {
    private fun cfg(
        name: String,
        dry: Boolean = false,
    ): Pair<Config, String> {
        val dir = join(tempDir(), "rtok-aider-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)
        val s = Config().setup
        return Config().copy(setup = s.copy(dryRun = dry, backup = false, proxy = true, aider = s.aider.copy(configPath = join(dir, ".aider.conf.yml")))) to dir
    }

    private val url = ProxyUrl.openai(Config())

    @Test fun dryRunShowsOneKeyAndCreatesNothing() {
        val (c, dir) = cfg("dry", dry = true)
        assertEquals("+ openai-api-base: $url\nrevert: remove openai-api-base", Aider.apply(c, Kind.Cli, Mode.Install).single())
        assertFalse(exists(c.setup.aider.configPath))
        assertTrue(Agents.agent("aider") === Aider)
        removeDirAll(dir)
    }

    @Test fun applyIsIdempotentKeepsCommentsAndRemoveStripsOnlyOurs() {
        val (c, dir) = cfg("apply")
        val path = c.setup.aider.configPath
        writeString(path, "# mine\nmodel: gpt-4o\n")
        Aider.run(c, false)
        assertEquals("# mine\nmodel: gpt-4o\nopenai-api-base: $url\n", readToString(path))
        assertEquals(NO_CHANGES, Aider.run(c, false))
        assertEquals(listOf("proxy"), Aider.installed(c, Kind.Cli))
        assertEquals("- openai-api-base", Aider.run(c, true))
        assertEquals("# mine\nmodel: gpt-4o\n", readToString(path))
        removeDirAll(dir)
    }

    @Test fun aForeignValueIsReplacedKeepingItsCommentAndNamedInTheRevert() {
        val (c, dir) = cfg("quoted")
        val path = c.setup.aider.configPath
        writeString(path, "  openai-api-base: \"https://api.example/v1\" # team\n")
        assertEquals("+ openai-api-base: $url\nrevert: set openai-api-base to https://api.example/v1", Aider.run(c, false))
        assertEquals("  openai-api-base: $url # team\n", readToString(path))
        removeDirAll(dir)
    }

    @Test fun ownershipReadsTheAuthorityNotSubstrings() {
        val (c, dir) = cfg("authority")
        val path = c.setup.aider.configPath
        val foreign =
            listOf(
                "https://api.example/localhost:8790/v1",
                "https://api.example/v1?base=http://127.0.0.1:8790",
                "https://localhost:8790@api.example/v1",
                "https://127.0.0.1:8790@api.example",
                "https://localhost.example:8790/v1",
                "http://127.0.0.1:87901/v1",
                "http://127.0.0.1:879/v1",
                "ftp://127.0.0.1:8790/v1",
                "localhost:8790",
                "not a url localhost:8790",
            )
        for (v in foreign) {
            val raw = "model: x\nopenai-api-base: $v\n"
            writeString(path, raw)
            assertEquals(NO_CHANGES, Aider.run(c, true), v)
            assertEquals(raw, readToString(path), v)
            assertEquals(emptyList(), Aider.installed(c, Kind.Cli), v)
        }
        for (v in listOf("http://localhost:8790/v1", "https://127.0.0.1:8790", "http://[::1]:8790/v1", "\"http://LOCALHOST:8790/v1\"")) {
            writeString(path, "model: x\nopenai-api-base: $v\n")
            assertEquals(listOf("proxy"), Aider.installed(c, Kind.Cli), v)
            Aider.run(c, true)
            assertEquals("model: x\n", readToString(path), v)
        }
        removeDirAll(dir)
    }
}
