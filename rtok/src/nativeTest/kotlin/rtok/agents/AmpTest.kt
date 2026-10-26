// Port of agents/amp/mod.rs tests: the dotted `amp.mcpServers` key, JSONC comments kept, a lone settings.jsonc.
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmpTest {
    private fun cfg(
        name: String,
        dry: Boolean = false,
    ): Pair<Config, String> {
        val dir = join(tempDir(), "rtok-amp-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)
        val s = Config().setup
        return Config().copy(setup = s.copy(dryRun = dry, backup = false, amp = s.amp.copy(settingsPath = join(dir, "settings.json")))) to dir
    }

    private fun root(path: String) = Jsonc.parse(readToString(path)) as JsonObject

    @Test fun theDefaultPathIsTheAmpUserSettingsFile() {
        val p = Amp.settingsPath(Config())
        assertTrue(p.endsWith(".config/amp/settings.json") || p.endsWith(".config/amp/settings.jsonc"), p)
        assertTrue(Agents.agent("amp") === Amp)
    }

    @Test fun dryRunNamesTheFileAndCreatesNothing() {
        val (c, dir) = cfg("dry", dry = true)
        val out = Amp.apply(c, Kind.Cli, Mode.Install).single()
        assertTrue(out.startsWith("amp.mcpServers.rtok: ") && "mcp --host amp" in out, out)
        assertFalse(exists(Amp.settingsPath(c)))
        assertEquals(emptyList(), Amp.installed(c, Kind.Cli))
        removeDirAll(dir)
    }

    @Test fun theEntrySitsUnderTheDottedKeyAndForeignSettingsSurvive() {
        val (c, dir) = cfg("entry")
        val path = Amp.settingsPath(c)
        writeString(path, "{\n  // keep me\n  \"amp.mcpServers\": {\"other\": {\"command\": \"x\"}},\n  \"amp.tools.disable\": [\"a\"]\n}\n")
        Amp.apply(c, Kind.Cli, Mode.Install)
        val r = root(path)
        val e = r[AMP_SERVERS]!!.jsonObject["rtok"]!!.jsonObject
        assertEquals(JsonPrimitive("mcp"), e["args"]!!.jsonArray[0])
        assertNull(e["type"])
        assertEquals(JsonPrimitive("x"), r[AMP_SERVERS]!!.jsonObject["other"]!!.jsonObject["command"])
        assertEquals(JsonPrimitive("a"), r["amp.tools.disable"]!!.jsonArray[0])
        assertNull(r["amp"])
        assertTrue("// keep me" in readToString(path))
        assertEquals(listOf("mcp"), Amp.installed(c, Kind.Cli))
        assertEquals(listOf(NO_CHANGES), Amp.apply(c, Kind.Cli, Mode.Install))
        Amp.apply(c, Kind.Cli, Mode.Remove)
        val left = root(path)[AMP_SERVERS]!!.jsonObject
        assertNull(left["rtok"])
        assertTrue("other" in left)
        assertEquals(emptyList(), Amp.installed(c, Kind.Cli))
        removeDirAll(dir)
    }

    @Test fun aLoneSettingsJsoncIsEditedInPlace() {
        val (c, dir) = cfg("jsonc")
        val json = c.setup.amp.settingsPath
        val jsonc = join(dir, "settings.jsonc")
        writeString(jsonc, "{ // mine\n}\n")
        assertEquals(jsonc, Amp.settingsPath(c))
        Amp.apply(c, Kind.Cli, Mode.Install)
        assertFalse(exists(json))
        assertTrue("// mine" in readToString(jsonc))
        assertEquals(listOf("mcp"), Amp.installed(c, Kind.Cli))
        // Both present: the .json file wins, as the docs list it first.
        writeString(json, "{}\n")
        assertEquals(json, Amp.settingsPath(c))
        removeDirAll(dir)
    }
}
