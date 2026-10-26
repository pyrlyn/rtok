// Port of agents/zed/mod.rs tests: `context_servers.rtok` in Zed's JSONC settings, comments and foreign servers kept.
package rtok.agents

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZedTest {
    private fun cfg(
        name: String,
        dry: Boolean = false,
    ): Pair<Config, String> {
        val dir = join(tempDir(), "rtok-zed-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)
        val s = Config().setup
        return Config().copy(setup = s.copy(dryRun = dry, backup = false, zed = s.zed.copy(configPath = join(dir, "settings.json")))) to dir
    }

    private fun servers(path: String) = (Jsonc.parse(readToString(path)) as JsonObject)["context_servers"]?.jsonObject

    @Test fun dryRunNamesTheChangeAndCreatesNothing() {
        val (c, dir) = cfg("dry", dry = true)
        val out = Zed.apply(c, Kind.Cli, Mode.Install).single()
        assertTrue(out.startsWith("context_servers.rtok: ") && "mcp --host zed" in out, out)
        assertFalse(exists(c.setup.zed.configPath))
        assertTrue(Agents.agent("zed") === Zed)
        removeDirAll(dir)
    }

    @Test fun applyIntoAMissingFileIsIdempotent() {
        val (c, dir) = cfg("missing")
        Zed.apply(c, Kind.Cli, Mode.Install)
        assertEquals(JsonPrimitive("zed"), servers(c.setup.zed.configPath)!!["rtok"]!!.jsonObject["args"]!!.jsonArray.last())
        assertEquals(listOf(NO_CHANGES), Zed.apply(c, Kind.Cli, Mode.Install))
        assertEquals(listOf("mcp"), Zed.installed(c, Kind.Cli))
        removeDirAll(dir)
    }

    @Test fun applyKeepsCommentsTrailingCommasAndForeignServers() {
        val (c, dir) = cfg("comments")
        val path = c.setup.zed.configPath
        writeString(path, "// Zed settings\n{\n  \"theme\": \"One Dark\", // mine\n  \"context_servers\": {\n    \"other\": {\"command\": \"x\"},\n  },\n}\n")
        Zed.apply(c, Kind.Cli, Mode.Install)
        val raw = readToString(path)
        assertTrue("// Zed settings" in raw && "// mine" in raw, raw)
        assertTrue("other" in servers(path)!! && "rtok" in servers(path)!!)
        assertEquals("- context_servers.rtok", Zed.apply(c, Kind.Cli, Mode.Remove).single())
        assertNull(servers(path)!!["rtok"])
        assertTrue("other" in servers(path)!! && "// mine" in readToString(path))
        assertEquals(emptyList(), Zed.installed(c, Kind.Cli))
        removeDirAll(dir)
    }

    @Test fun malformedInputIsAnErrorAndWritesNothing() {
        val (c, dir) = cfg("bad")
        val path = c.setup.zed.configPath
        writeString(path, "{ \"theme\": ")
        assertFailsWith<Exception> { Zed.apply(c, Kind.Cli, Mode.Install) }
        assertEquals("{ \"theme\": ", readToString(path))
        removeDirAll(dir)
    }
}
